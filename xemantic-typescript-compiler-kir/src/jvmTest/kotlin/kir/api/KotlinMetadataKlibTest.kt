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
import com.xemantic.typescript.compiler.kir.exportTypeScriptApi
import com.xemantic.typescript.compiler.kir.exportTypeScriptProjectApi
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * The artifact, verified by the only thing that can verify it: a CONSUMER.
 *
 * A metadata klib is a binary nobody reads by eye, and every failure mode it
 * has is silent — a legacy layout written under a `.klib` name resolves to
 * nothing, a declaration typed with a name the consumer cannot see resolves to
 * nothing, and both look exactly like success from the producing side. So each
 * pin here compiles Kotlin source AGAINST the artifact, which is what a Kotlin
 * Multiplatform `commonMain` does with it.
 *
 * The negative controls are the other half and are not optional: a round trip
 * that passes because the consumer compiles whatever it is given would pass for
 * an EMPTY klib too.
 */
class KotlinMetadataKlibTest {

    /**
     * The whole pipeline: TypeScript in, a klib out, Kotlin compiled against it.
     */
    @Test
    fun `a TypeScript library is consumable from common Kotlin`() {
        val export = exportOf(
            """
            export const VERSION: string = "1.0"

            export function greet(name: string, times: number): string {
                return name.repeat(times)
            }

            export class Counter {
                private value: number
                constructor(start: number) { this.value = start }
                increment(by: number): number {
                    this.value = this.value + by
                    return this.value
                }
            }

            export enum Kind { Alpha = "a", Beta = "b" }
            """
        )
        assert(export.successful)
        val klib = export.klib!!
        assert(klib.exists())
        val consumed = consume(
            klib,
            """
            package consumer

            import ts.Counter
            import ts.Kind
            import ts.VERSION
            import ts.greet

            public fun use(): String {
                val counter = Counter(1.0)
                val total: Double = counter.increment(2.0)
                val kind: String = Kind.Alpha
                return VERSION + greet("hi", total) + kind
            }
            """
        )
        assert(consumed.successful)
    }

    /**
     * NEGATIVE CONTROL for the round trip above: a name the library does not
     * export must not resolve.
     *
     * Without it, the positive pin says only that the consumer compiled — which
     * an empty artifact would also allow.
     */
    @Test
    fun `a name the library does not export does not resolve`() {
        val export = exportOf(
            """
            function internalHelper(): number { return 1 }
            export function exported(): number { return internalHelper() }
            """
        )
        assert(export.successful)
        val consumed = consume(
            export.klib!!,
            """
            package consumer
            public fun use(): Double = ts.internalHelper()
            """
        )
        assert(!consumed.successful)
        assert(consumed.errors.any { it.contains("internalHelper") })
    }

    /**
     * NEGATIVE CONTROL for the TYPES: the erasure reaches the artifact.
     *
     * `greet` takes a `Double` because TypeScript's `number` is one, and a
     * consumer passing an `Int` must be rejected — which is the difference
     * between a typed artifact and a bag of names.
     */
    @Test
    fun `the erased parameter types are enforced on the consumer`() {
        val export = exportOf("export function greet(times: number): string { return \"\" }")
        assert(export.successful)
        val consumed = consume(
            export.klib!!,
            """
            package consumer
            public fun use(): String = ts.greet(1)
            """
        )
        assert(!consumed.successful)
    }

    /**
     * The artifact is a KLIB, not the legacy metadata layout.
     *
     * `metadataKlib` is a boolean whose wrong value produces a perfectly good
     * artifact of the OTHER kind, under the same name, with no diagnostic — so
     * the only thing between this project and shipping an unusable file is a
     * pin on the layout itself.
     */
    @Test
    fun `the artifact has the klib layout`() {
        val export = exportOf("export const VERSION: string = \"1.0\"")
        assert(export.successful)
        val entries = ZipFile(export.klib!!.toFile()).use { zip ->
            zip.entries().toList().map { it.name }
        }
        assert(entries.any { it == "default/manifest" })
        assert(entries.any { it.startsWith("default/linkdata/") })
    }

    /** A program the checker rejects has no API to export. */
    @Test
    fun `a program with type errors is not exported`() {
        val export = exportOf("export const broken: string = 42")
        assert(!export.successful)
        assert(export.klib == null)
        assert(export.typeErrors.isNotEmpty())
    }

    /**
     * A real library's shape: an `index.ts` that re-exports, and the surface
     * followed through it.
     *
     * `export { … } from` and `export *` are how a package states its API, and
     * a module the entry does NOT re-export must stay off the surface — that
     * last part is the pin, because taking the union of every file's `export`
     * would pass everything else here.
     */
    @Test
    fun `a project's public API is its entry module's exports`() {
        val project = Files.createTempDirectory("xtsc-kir-api-project")
        project.resolve("tsconfig.json").writeText(
            """{ "compilerOptions": { "strict": true, "module": "esnext" } }"""
        )
        val sources = project.resolve("src")
        sources.createDirectories()
        sources.resolve("greeting.ts").writeText(
            """
            export function greet(name: string): string { return "hi " + name }
            """.trimIndent()
        )
        sources.resolve("version.ts").writeText(
            """
            export const VERSION: string = "1.0"
            """.trimIndent()
        )
        sources.resolve("internal.ts").writeText(
            """
            export function secret(): number { return 42 }
            """.trimIndent()
        )
        sources.resolve("index.ts").writeText(
            """
            export { greet } from "./greeting"
            export * from "./version"
            """.trimIndent()
        )
        val klib = Files.createTempDirectory("xtsc-kir-api-out").resolve("lib.klib")
        val export = exportTypeScriptProjectApi(
            projectPath = project.toString(),
            entryFileName = "index.ts",
            outputKlib = klib,
        )
        assert(export.successful)
        assert(export.api.declarations.map { it.name }.sorted() == listOf("VERSION", "greet"))
        val consumed = consume(
            klib,
            """
            package consumer
            public fun use(): String = ts.greet("world") + ts.VERSION
            """
        )
        assert(consumed.successful)
        val leaked = consume(
            klib,
            """
            package consumer
            public fun use(): Double = ts.secret()
            """
        )
        assert(!leaked.successful)
    }

    /**
     * A REAL library, its own published source, exported and consumed.
     *
     * `mitt` 3.0.1 — the event emitter this module's corpus already compiles
     * and runs — whose public API is one default-exported generic factory. It
     * is the pin that says the surface walk survives contact with source
     * nobody here wrote: a `.d.ts`-free module whose API is stated with
     * `export default`, generic type parameters and an interface return type.
     *
     * What it exports is `mitt(all: Any?): Any?`, and that is the honest state
     * of this slice rather than an accident — the `Emitter` interface it
     * returns is a property bag at run time and no common Kotlin type names one
     * yet (`docs/kir-kotlin-metadata.md` §3.1). When that changes, this pin is
     * where it will show.
     */
    @Test
    fun `a real library - mitt - is exported and consumed`() {
        val export = exportMitt(runtime = false)
        val klib = export.klib!!
        assert(export.successful)
        assert(export.refusals.isEmpty())
        assert(export.api.declarations.map { it.name } == listOf("mitt"))
        val consumed = consume(
            klib,
            """
            package consumer
            public fun emitter(): Any? = ts.mitt(null)
            """
        )
        assert(consumed.successful)
    }

    /**
     * The same library WITH the runtime surface: `mitt` becomes
     * `mitt(all: JsObject?): JsObject`.
     *
     * `Emitter<Events>` is an interface `mitt.ts` declares, so it is a shape
     * this program writes and the gate lets it be a bag; its type parameters
     * erase, as they do in TypeScript and on the JVM.
     */
    @Test
    fun `a real library exports its object types when the runtime is on`() {
        val export = exportMitt(runtime = true)
        assert(export.successful)
        val mitt = export.api.declarations.filterIsInstance<KotlinFunction>().single()
        assert(mitt.returnType == KirRuntimeApi.jsObject)
        assert(mitt.parameters.single().type == KirRuntimeApi.jsObject.asNullable())
    }

    /**
     * `smol-toml`'s own source: 1,082 lines, seven modules, and the signature a
     * Kotlin caller would actually use — `parse(toml: String, options: JsObject?):
     * JsObject`.
     *
     * Its `options` is `ParseOptions & { integersAsBigInt: … }`, the branded
     * shape §3's intersection rule exists for; without that rule this reads
     * `Any?`, which is what it read when the rule was written.
     */
    @Test
    fun `a real parser exports a usable signature`() {
        val project = Files.createTempDirectory("xtsc-kir-api-toml")
        listOf(
            "tsconfig.json", "src/util.ts", "src/error.ts", "src/date.ts",
            "src/primitive.ts", "src/struct.ts", "src/extract.ts", "src/parse.ts",
            "src/temporal-shim.d.ts", "src/main.ts",
        ).forEach { relative ->
            val target = project.resolve(relative)
            target.parent.createDirectories()
            target.writeText(resource("toml/$relative"))
        }
        val out = Files.createTempDirectory("xtsc-kir-api-toml-out")
        val export = exportTypeScriptProjectApi(
            projectPath = project.toString(),
            entryFileName = "parse.ts",
            outputKlib = out.resolve("toml.klib"),
            runtimeKlib = out.resolve("runtime.klib"),
        )
        assert(export.successful)
        val parse = export.api.declarations.filterIsInstance<KotlinFunction>()
            .single { it.name == "parse" }
        assert(parse.parameters.map { it.type } ==
            listOf(KotlinType.STRING, KirRuntimeApi.jsObject.asNullable()))
        assert(parse.returnType == KirRuntimeApi.jsObject)
        val consumed = consume(
            listOf(export.klib!!, export.runtimeKlib!!),
            """
            package consumer
            import com.xemantic.typescript.compiler.kir.runtime.JsObject
            public fun title(): Any? {
                val document: JsObject = ts.parse("title = 'x'", null)
                return document.get("title")
            }
            """
        )
        assert(consumed.successful)
    }

    private fun exportMitt(runtime: Boolean): KotlinMetadataExport {
        val project = Files.createTempDirectory("xtsc-kir-api-mitt")
        listOf("tsconfig.json", "src/mitt.ts", "src/main.ts").forEach { relative ->
            val target = project.resolve(relative)
            target.parent.createDirectories()
            target.writeText(resource("mitt-consumer/$relative"))
        }
        val out = Files.createTempDirectory("xtsc-kir-api-mitt-out")
        return exportTypeScriptProjectApi(
            projectPath = project.toString(),
            entryFileName = "mitt.ts",
            outputKlib = out.resolve("mitt.klib"),
            runtimeKlib = if (runtime) out.resolve("runtime.klib") else null,
        )
    }

    private fun resource(name: String): String {
        val url = javaClass.getResource("/projects/$name") ?: error("no resource '$name'")
        return url.openStream().use { it.readBytes().decodeToString() }
    }

    /**
     * (KAPI.3): with the runtime's own metadata klib, an exported object type is
     * a `JsObject` a consumer can READ, and an array is a `JsArray` it can index.
     *
     * The pin is the difference between "a parser returns something" and "a
     * parser returns something you can read", and the negative control below is
     * what says the runtime klib is doing the work rather than the consumer
     * being lenient.
     */
    @Test
    fun `with the runtime klib a consumer reads an object and indexes an array`() {
        val export = exportOf(RUNTIME_LIBRARY, runtime = true)
        assert(export.successful)
        val consumed = consume(
            listOf(export.klib!!, export.runtimeKlib!!),
            """
            package consumer

            import com.xemantic.typescript.compiler.kir.runtime.JsArray
            import com.xemantic.typescript.compiler.kir.runtime.JsObject

            public fun use(): Any? {
                val config: JsObject = ts.parseConfig("title")
                val names: JsArray = ts.names()
                names.push("x")
                return config.get("title") ?: names[0.0]
            }
            """
        )
        assert(consumed.successful)
    }

    /**
     * NEGATIVE CONTROL for the pin above: WITHOUT the runtime klib the same
     * library exports those positions as `Any?`, so the same consumer must not
     * compile.
     */
    @Test
    fun `without the runtime klib the same consumer does not compile`() {
        val export = exportOf(RUNTIME_LIBRARY, runtime = false)
        assert(export.successful)
        assert(export.runtimeKlib == null)
        val consumed = consume(
            export.klib!!,
            """
            package consumer
            import com.xemantic.typescript.compiler.kir.runtime.JsObject
            public fun use(): Any? {
                val config: JsObject = ts.parseConfig("title")
                return config.get("title")
            }
            """
        )
        assert(!consumed.successful)
    }

    private fun exportOf(source: String, runtime: Boolean = false): KotlinMetadataExport {
        val directory = Files.createTempDirectory("xtsc-kir-api-out")
        return exportTypeScriptApi(
            "api.ts",
            source.trimIndent(),
            directory.resolve("lib.klib"),
            runtimeKlib = if (runtime) directory.resolve("runtime.klib") else null,
        )
    }

    private companion object {
        /** One library, exported twice — with the runtime surface and without. */
        val RUNTIME_LIBRARY = """
            export interface Config { title: string }

            export function parseConfig(text: string): Config {
                return { title: text }
            }

            export function names(): string[] { return [] }
        """
    }

    /**
     * Compiles [source] as a Kotlin Multiplatform common module against [klib].
     *
     * The same entry point the artifact was produced with, which is deliberate:
     * a consumer of a metadata klib IS a metadata compilation, so nothing about
     * the round trip is simulated.
     */
    private fun consume(klib: Path, source: String): MetadataKlibResult =
        consume(listOf(klib), source)

    private fun consume(classpath: List<Path>, source: String): MetadataKlibResult {
        val output = Files.createTempDirectory("xtsc-kir-api-consumer").resolve("consumer.klib")
        return compileMetadataKlib(source.trimIndent(), output, "consumer", classpath = classpath)
    }

}
