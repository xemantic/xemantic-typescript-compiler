/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * xemantic-typescript-compiler - a conformant TypeScript compiler and type
 * checker that runs on JVM, native, and WebAssembly
 * Copyright (C) 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * As a special exception, this file contains Helper Code covered by the
 * xemantic-typescript-compiler Output Exception; additional permissions
 * are granted as described in the file LICENSE-EXCEPTION.
 */

package com.xemantic.typescript.compiler.kir.api

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.kir.KotlinMetadataExport
import com.xemantic.typescript.compiler.kir.compileTypeScriptProjectToJvm
import com.xemantic.typescript.compiler.kir.compileTypeScriptToJvm
import com.xemantic.typescript.compiler.kir.emit.GeneratedProgramClasspath
import com.xemantic.typescript.compiler.kir.emit.RecordingMessageCollector
import com.xemantic.typescript.compiler.kir.exportTypeScriptApi
import com.xemantic.typescript.compiler.kir.exportTypeScriptProjectApi
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * (KAPI.2, KAPI.4) The PLATFORM half: do the JVM classes the KIR backend emits for a
 * program agree with the metadata klib exported from the SAME program?
 *
 * The failure this exists for is the worst-shaped one available — a Kotlin
 * Multiplatform consumer's `commonMain` type-checks against the klib and its
 * `jvmMain` does not link — and nothing on either producing side can see it.
 * So the instrument is two consumers of ONE export, built from the exported API
 * itself: one reference per exported declaration (`::greet`, `Counter::class`,
 * `::Counter`, `Counter::increment`, `Kind::Alpha`), compiled as common code
 * against the klib and as JVM code against the emitted classes, each reference
 * on its own line so an error names the declaration that did not resolve.
 *
 * The POSITIVE CONTROL is what makes a JVM-side verdict mean something: the same
 * exported API source compiled by kotlinc's own JVM compiler is what an agreeing
 * platform artifact looks like, and the JVM consumer resolves every reference
 * against it. And because resolving is not linking, the last pin RUNS a JVM
 * consumer against the emitted classes, with node's answers as the expected ones.
 *
 * History: (KAPI.2) measured that the JVM consumer resolved NOTHING — every emitted
 * class was `@Metadata(k = 3)`, synthetic and empty, and the JVM names disagreed
 * (`MainKt`/`ApiKt`, `x$get`/`getX`, `object$`/`object`, no enum class). (KAPI.4)
 * made kotlinc declare the API and KIR define it (`KirApiDefinition`), and these
 * pins now hold the agreement instead of the divergence.
 */
class KirPlatformAgreementTest {

    @Test
    fun `the JVM consumer resolves every declaration of the API kotlinc compiles`() {
        val agreement = agreementOf(LIBRARY)
        assert(agreement.commonUnresolved.isEmpty())
        assert(agreement.stubUnresolved.isEmpty())
        assert(agreement.references == listOf(
            "::VERSION", "::counter", "::greet", "::TWICE", "::bump", "Counter::class", "::Counter",
            "Counter::increment", "Point::class", "::Point", "Point::x", "Point::double", "::origin",
            "Kind", "Kind::Alpha", "Kind::Beta", "::`object`",
        ))
    }

    @Test
    fun `the JVM consumer resolves every declaration the KIR backend emits`() {
        val agreement = agreementOf(LIBRARY)
        assert(agreement.commonUnresolved.isEmpty())
        assert(agreement.jvmUnresolved.isEmpty())
    }

    /**
     * Kotlin reads the exported classes' metadata, the API facade's and the
     * constants object's; the lowered program's own facade is a file facade that
     * declares nothing (it lends its file metadata to the classes it holds).
     */
    @Test
    fun `the exported classes carry kotlinc's metadata`() {
        val agreement = agreementOf(LIBRARY)
        assert(agreement.kirMetadataKinds == mapOf(
            "ts.Counter" to 1, "ts.Kind" to 1, "ts.MainKt" to 2, "ts.Point" to 1, "ts.TypeScriptApiKt" to 2,
        ))
        assert(agreement.stubMetadataKinds ==
            mapOf("ts.Counter" to 1, "ts.Kind" to 1, "ts.Point" to 1, "ts.TypeScriptApiKt" to 2))
    }

    /** Every JVM member kotlinc writes for the API exists in the emitted classes, by name and erasure. */
    @Test
    fun `the erased JVM signatures agree`() {
        val agreement = agreementOf(LIBRARY)
        assert(agreement.missingOnJvm.isEmpty())
        assert(agreement.missingFacadeMembers.isEmpty())
        assert(agreement.missingClassMembers.isEmpty())
    }

    /**
     * The JVM consumer LINKS and RUNS against the emitted classes, with no `main`:
     * the first call into the API runs the library's module inits (`VERSION` and
     * `TWICE` are assigned there), a field and a getter of an exported class read
     * the lowered members, and the module variable a Kotlin caller sees is the one
     * the library's own code bumps.
     *
     * Expected from tsgo 7.0.2 + node over the same calls (`1.0|abab|hihi|3|0|1|2|2|2|4|5|10|0|a|b|o`),
     * plus two the JavaScript importer cannot spell: a Kotlin WRITE through the
     * exported `var` (`counter = 10.0`, then `bump()` is 11) and the same read back.
     */
    @Test
    fun `a JVM consumer runs the library through its exported API`() {
        val work = Files.createTempDirectory("xtsc-kapi4-run")
        val classes = work.resolve("kir")
        val compiled = compileTypeScriptToJvm("api.ts", LIBRARY.trimIndent(), classes, packageName = PACKAGE, exportApi = true)
        assert(compiled.successful)
        val runtime = GeneratedProgramClasspath.minimal()
        val consumerClasses = work.resolve("consumer")
        val errors = compileJvm(
            """
            package consumer
            import ts.*
            fun n(value: Double): String = value.toInt().toString()
            fun run(): String {
                val out = mutableListOf(VERSION, TWICE, greet("hi", 2.0))
                out += n(Counter(1.0).increment(2.0))
                out += n(counter)
                out += listOf(n(bump()), n(bump()), n(counter))
                val p = Point(2.0)
                out += listOf(n(p.x), n(p.double))
                p.x = 5.0
                out += listOf(n(p.x), n(p.double), n(origin().x))
                out += listOf(Kind.Alpha, Kind.Beta, `object`("o"))
                counter = 10.0
                out += listOf(n(bump()), n(counter))
                return out.joinToString("|")
            }
            """.trimIndent(),
            "Consumer.kt", listOf(classes) + runtime, consumerClasses,
        )
        assert(errors.isEmpty())
        val loader = URLClassLoader((listOf(consumerClasses, classes) + runtime).map { it.toUri().toURL() }.toTypedArray(), null)
        val output = loader.loadClass("consumer.ConsumerKt").getMethod("run").invoke(null)
        assert(output == "1.0|abab|hihi|3|0|1|2|2|2|4|5|10|0|a|b|o|11|11")
    }

    /**
     * A module runs ONCE, as in JavaScript, when a program uses both doors: `main`
     * runs the module inits, the lowered code then moves `counter`, and the API's
     * first use must not run them again and reset it.
     */
    @Test
    fun `the API does not evaluate a module main already evaluated`() {
        val work = Files.createTempDirectory("xtsc-kapi4-once")
        val classes = work.resolve("kir")
        val compiled = compileTypeScriptToJvm("api.ts", LIBRARY.trimIndent(), classes, packageName = PACKAGE, exportApi = true)
        assert(compiled.successful)
        val runtime = GeneratedProgramClasspath.minimal()
        val loader = URLClassLoader((listOf(classes) + runtime).map { it.toUri().toURL() }.toTypedArray(), null)
        val lowered = loader.loadClass("$PACKAGE.MainKt")
        lowered.getMethod("main").invoke(null)
        lowered.getMethod("counter\$set", Double::class.javaPrimitiveType).invoke(null, 7.0)
        val api = loader.loadClass("$PACKAGE.TypeScriptApiKt")
        val counter = api.getMethod("getCounter").invoke(null)
        assert(counter == 7.0)
    }

    @Test
    fun `a real library - mitt - agrees with its klib`() {
        val agreement = agreementOfProject("mitt-consumer", MITT_FILES, exportEntry = "mitt.ts")
        assert(agreement.references == listOf("::mitt"))
        assert(agreement.commonUnresolved.isEmpty())
        assert(agreement.stubUnresolved.isEmpty())
        assert(agreement.jvmUnresolved.isEmpty())
        assert(agreement.missingOnJvm.isEmpty())
        assert(agreement.missingFacadeMembers.isEmpty())
    }

    @Test
    fun `a real parser - smol-toml - agrees with its klib`() {
        val agreement = agreementOfProject("toml", TOML_FILES, exportEntry = "parse.ts")
        assert(agreement.references == listOf("::parse"))
        assert(agreement.commonUnresolved.isEmpty())
        assert(agreement.stubUnresolved.isEmpty())
        assert(agreement.jvmUnresolved.isEmpty())
        assert(agreement.missingOnJvm.isEmpty())
        assert(agreement.missingFacadeMembers.isEmpty())
    }

    // ---- the instrument ---------------------------------------------------------------

    private class Agreement(
        val references: List<String>,
        val commonUnresolved: Set<String>,
        val jvmUnresolved: Set<String>,
        val stubUnresolved: Set<String>,
        val kirMetadataKinds: Map<String, Int>,
        val stubMetadataKinds: Map<String, Int>,
        /** Classes kotlinc writes for the API that the KIR backend does not. */
        val missingOnJvm: List<String>,
        /** The API facade's members, absent from every KIR class under the same JVM signature. */
        val missingFacadeMembers: List<String>,
        /** Members of the API's classes absent from the KIR class of the same name. */
        val missingClassMembers: List<String>,
    )

    private fun agreementOf(source: String): Agreement {
        val work = Files.createTempDirectory("xtsc-kapi2")
        val classes = work.resolve("kir")
        val compiled = compileTypeScriptToJvm("api.ts", source.trimIndent(), classes, packageName = PACKAGE, exportApi = true)
        assert(compiled.successful)
        val export = exportTypeScriptApi("api.ts", source.trimIndent(), work.resolve("lib.klib"),
            packageName = PACKAGE, runtimeKlib = work.resolve("runtime.klib"))
        return measure(export, classes, work)
    }

    private fun agreementOfProject(name: String, files: List<String>, exportEntry: String): Agreement {
        val project = Files.createTempDirectory("xtsc-kapi2-$name")
        files.forEach { relative ->
            val target = project.resolve(relative)
            target.parent.createDirectories()
            target.writeText(resource("$name/$relative"))
        }
        val work = Files.createTempDirectory("xtsc-kapi2-$name-out")
        val classes = work.resolve("kir")
        // The program's own entry is the acceptance driver: the backend compiles a PROGRAM,
        // and the library's declarations are emitted with it.
        val compiled = compileTypeScriptProjectToJvm(
            project.toString(), "main.ts", classes, packageName = PACKAGE, apiEntryFileName = exportEntry,
        )
        assert(compiled.successful)
        val export = exportTypeScriptProjectApi(project.toString(), exportEntry, work.resolve("lib.klib"),
            packageName = PACKAGE, runtimeKlib = work.resolve("runtime.klib"))
        return measure(export, classes, work)
    }

    private fun measure(export: KotlinMetadataExport, kirClasses: Path, work: Path): Agreement {
        assert(export.successful)
        val references = referencesOf(export.api)
        val consumer = buildString {
            appendLine("package consumer")
            appendLine("import $PACKAGE.*")
            references.forEachIndexed { i, reference -> appendLine("val r$i = $reference") }
        }
        val firstReferenceLine = 3
        fun unresolved(errors: List<String>): Set<String> = errors.mapNotNull { error ->
            Regex(""":(\d+):\d+ """).find(error)?.groupValues?.get(1)?.toInt()
                ?.let { references.getOrNull(it - firstReferenceLine) }
        }.toSet()

        val klibs = listOf(export.klib!!, export.runtimeKlib!!)
        val common = compileMetadataKlib(consumer, work.resolve("consumer.klib"), "consumer", classpath = klibs)
        val runtime = GeneratedProgramClasspath.minimal()
        // What a platform artifact that AGREES looks like: kotlinc's own JVM classes for the
        // exported API source, against the same runtime the KIR program links.
        val stubs = work.resolve("stubs")
        val stubCompile = compileJvm(export.source, API_FILE, runtime, stubs)
        assert(stubCompile.isEmpty())
        val againstKir = compileJvm(consumer, "Consumer.kt", listOf(kirClasses) + runtime, work.resolve("c1"))
        val againstStubs = compileJvm(consumer, "Consumer.kt", listOf(stubs) + runtime, work.resolve("c2"))

        val kir = surfaceOf(kirClasses, runtime)
        val stub = surfaceOf(stubs, runtime)
        val facade = "$PACKAGE.${API_FILE.removeSuffix(".kt")}Kt"
        val kirMembers = kir.members.values.flatten().toSet()
        return Agreement(
            references = references,
            commonUnresolved = unresolved(common.errors),
            jvmUnresolved = unresolved(againstKir),
            stubUnresolved = unresolved(againstStubs),
            kirMetadataKinds = kir.kinds,
            stubMetadataKinds = stub.kinds,
            missingOnJvm = stub.kinds.keys.filter { it !in kir.kinds }.sorted().map { "$it (class)" },
            missingFacadeMembers = stub.members[facade].orEmpty().filter { it !in kirMembers }.sorted(),
            missingClassMembers = stub.members.filterKeys { it != facade && it in kir.members }
                .flatMap { (name, members) -> members.filter { it !in kir.members.getValue(name) }.map { "$name.$it" } }
                .sorted(),
        )
    }

    /** One reference per exported declaration, in the order the API lists them. */
    private fun referencesOf(api: KotlinApiModule): List<String> = api.declarations.flatMap { declaration ->
        when (declaration) {
            is KotlinFunction, is KotlinProperty -> listOf("::${declaration.name}")
            is KotlinClass -> listOf("${declaration.name}::class") +
                (if (declaration.constructorParameters != null) listOf("::${declaration.name}") else emptyList()) +
                declaration.members.map { "${declaration.name}::${it.name}" }
            is KotlinConstantObject -> listOf(declaration.name) +
                declaration.members.map { "${declaration.name}::${it.name}" }
        }
    }

    private class Surface(val kinds: Map<String, Int>, val members: Map<String, List<String>>)

    /** Each class's `@Metadata` kind and its public JVM members, read back by reflection. */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun surfaceOf(classes: Path, runtime: List<Path>): Surface {
        val loader = URLClassLoader((listOf(classes) + runtime).map { it.toUri().toURL() }.toTypedArray(), null)
        val metadata = loader.loadClass("kotlin.Metadata")
        val kind = metadata.getMethod("k")
        val names = Files.walk(classes).use { paths ->
            paths.filter { it.toString().endsWith(".class") }
                .map { classes.relativize(it).toString().removeSuffix(".class").replace(File.separatorChar, '.') }
                .toList()
        }
        val kinds = HashMap<String, Int>()
        val members = HashMap<String, List<String>>()
        names.sorted().forEach { name ->
            val type = loader.loadClass(name)
            val annotation = type.getAnnotation(metadata.asSubclass(Annotation::class.java))
            kinds[name] = annotation?.let { kind.invoke(it) as Int } ?: 0
            members[name] = type.declaredConstructors.filter { Modifier.isPublic(it.modifiers) }
                .map { "<init>(${it.parameterTypes.joinToString(", ") { p -> p.typeName }})" } +
                type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                    .map { "${it.name}(${it.parameterTypes.joinToString(", ") { p -> p.typeName }}): ${it.returnType.typeName}" }
        }
        return Surface(kinds, members)
    }

    /** Compiles one Kotlin file for the JVM; the errors, or an empty list. */
    private fun compileJvm(source: String, fileName: String, classpath: List<Path>, output: Path): List<String> {
        val sourceFile = Files.createTempDirectory("xtsc-kapi2-src").resolve(fileName)
        Files.writeString(sourceFile, source)
        val messages = RecordingMessageCollector()
        K2JVMCompiler().exec(messages, Services.EMPTY, K2JVMCompilerArguments().apply {
            freeArgs = listOf(sourceFile.toString())
            destination = output.toString()
            this.classpath = classpath.joinToString(File.pathSeparator)
            noStdlib = true
            noReflect = true
            jvmTarget = "17"
            suppressWarnings = true
        })
        return messages.errors
    }

    private fun resource(name: String): String {
        val url = javaClass.getResource("/projects/$name") ?: error("no resource '$name'")
        return url.openStream().use { it.readBytes().decodeToString() }
    }

    private companion object {
        const val PACKAGE = "ts"

        /** The file the JVM half compiles the API from, so the two facades have one name. */
        const val API_FILE = "TypeScriptApi.kt"

        /** Every declaration kind the export writes, and a keyword name. */
        val LIBRARY = """
            export const VERSION: string = "1.0"
            export let counter: number = 0
            export function greet(name: string, times: number): string { return name.repeat(times) }
            export const TWICE: string = greet("ab", 2)
            export function bump(): number { counter = counter + 1; return counter }
            export class Counter {
                private value: number
                constructor(start: number) { this.value = start }
                increment(by: number): number { this.value = this.value + by; return this.value }
            }
            export class Point {
                x: number
                constructor(x: number) { this.x = x }
                get double(): number { return this.x * 2 }
            }
            export function origin(): Point { return new Point(0) }
            export enum Kind { Alpha = "a", Beta = "b" }
            export function object(fun: string): string { return fun }
        """

        val MITT_FILES = listOf("tsconfig.json", "src/mitt.ts", "src/main.ts")

        val TOML_FILES = listOf(
            "tsconfig.json", "src/util.ts", "src/error.ts", "src/date.ts",
            "src/primitive.ts", "src/struct.ts", "src/extract.ts", "src/parse.ts",
            "src/temporal-shim.d.ts", "src/main.ts",
        )
    }

}
