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

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("xemantic-typescript-compiler.convention")
}

base {
    archivesName = project.name
}

// SPIKE (branch `spike/ts-to-kotlin-ir`): the Kotlin-IR backend.
//
// Where `-core`'s Transformer/Emitter pair lowers the checked TypeScript AST to
// JavaScript TEXT, this module lowers it to Kotlin IR — the same intermediate
// representation kotlinc's own frontend produces — and then hands that IR to
// Kotlin's JVM backend, which lowers it and writes `.class` files. The point of
// stopping at IR rather than at bytecode is that IR is the fork point for every
// Kotlin backend: JVM today, JS/Native/Wasm for free later.
//
// JVM-only and deliberately so: it embeds the Kotlin compiler.
kotlin {

    // As in `-project` and `-cli`: the generated IR links against this module's
    // runtime by symbol, so its surface is a contract with emitted code and is
    // declared rather than inferred.
    explicitApi()

    jvm()

    sourceSets {

        jvmMain {
            dependencies {
                api(project(":xemantic-typescript-compiler-core"))
                // The Kotlin compiler itself: we construct its IR, then drive
                // its JVM pipeline phases in-process. `compileOnly` is NOT an
                // option — this is a runtime dependency of the backend.
                implementation(libs.kotlin.compiler.embeddable)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.xemantic.kotlin.test)
            }
        }

    }

}

// ---------------------------------------------------------------------------
// THE KOTLIN/NATIVE BACKEND
//
// The JVM path drives kotlinc's own phases in-process (`KotlinIrEmitter`), so it
// is a function call and needs no build wiring. Kotlin/Native has no equivalent
// phase API in 2.4 — there is `cli/pipeline/jvm`, `.../web`, `.../wasm` and no
// `.../native` — so the relationship inverts: konanc is the driver and this
// module's backend rides in as an `IrGenerationExtension` (`KirNativePlugin.kt`),
// loaded with `-Xplugin`. That means a second JVM with a plugin classpath, which
// is what these tasks assemble.
//
//   ./gradlew :xemantic-typescript-compiler-kir:kirNativeCompile \
//       -PkirProject=<dir> -PkirEntry=main.ts -PkirOutput=<path>
//
// `scripts/kir-native.sh` is a wrapper over exactly this, so there is one
// implementation rather than two that drift.
//
// Measured, and the reason any of it is worth having:
// `docs/perf/kir-backend-levers.md` § 6.
// ---------------------------------------------------------------------------

/**
 * The Kotlin/Native distribution, which Gradle downloads for any native target.
 *
 * Pinned to the build's own Kotlin version rather than "the newest directory
 * present": `~/.konan` accumulates one per version, and compiling against a
 * different one than the plugin was built for is a class-format fight that
 * surfaces deep inside the compiler.
 */
fun konanHome(): File {
    (project.findProperty("konanHome") as String? ?: System.getenv("KONAN_HOME"))
        ?.let { return File(it) }
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val host = when {
        os.startsWith("linux") && arch in setOf("amd64", "x86_64") -> "linux-x86_64"
        os.startsWith("linux") && arch in setOf("aarch64", "arm64") -> "linux-aarch64"
        os.startsWith("mac") && arch in setOf("aarch64", "arm64") -> "macos-aarch64"
        os.startsWith("mac") -> "macos-x86_64"
        else -> error("no Kotlin/Native host mapping for $os/$arch — pass -PkonanHome")
    }
    val version = libs.versions.kotlin.get()
    val home = File(System.getProperty("user.home"), ".konan/kotlin-native-prebuilt-$host-$version")
    check(home.isDirectory) {
        "no Kotlin/Native distribution at $home — pass -PkonanHome, or let Gradle " +
            "provision one by building any native target"
    }
    return home
}

/**
 * A path property, resolved against the REPOSITORY ROOT rather than the daemon.
 *
 * `File(relative).absoluteFile` uses the JVM's `user.dir`, and the Gradle daemon's
 * is its own directory — so `-PkirProject=build/bench/...` looked for
 * `~/.gradle/daemon/9.7.0/build/bench/...`. Round 873's defect, one process over.
 */
fun resolvePath(value: String): File {
    val file = File(value)
    return if (file.isAbsolute) file else File(rootDir, value)
}

/**
 * Runs a command, streaming its output to [log], and fails naming the log.
 *
 * `project.exec {}` is not available in a Gradle 9 Kotlin DSL `doLast` block
 * (CLAUDE.md), hence `ProcessBuilder`.
 */
fun runProcess(command: List<String>, log: File, environment: Map<String, String> = emptyMap()) {
    log.parentFile.mkdirs()
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.to(log))
        .also { it.environment().putAll(environment) }
        .start()
    val status = process.waitFor()
    if (status != 0) {
        error("`${command.first()}` failed with $status — see $log\n" +
            log.readLines().filterNot { it.startsWith("WARNING") || it.startsWith("\tat ") }
                .take(20).joinToString("\n"))
    }
}

val kirNativeWork: Provider<Directory> = layout.buildDirectory.dir("kir-native")

/**
 * The native runtime, DERIVED from the JVM one rather than forked.
 *
 * The two must answer identically, so there is no second copy to drift: the
 * generator replaces exactly what Kotlin/Native lacks and fails on any anchor it
 * cannot find. See `scripts/kir_native_runtime.py`.
 */
val kirNativeRuntimeSource by tasks.registering {
    val generator = rootProject.layout.projectDirectory.file("scripts/kir_native_runtime.py")
    val jvmRuntime = layout.projectDirectory
        .file("src/jvmMain/kotlin/kir/runtime/JsRuntime.kt")
    val generated = kirNativeWork.map { it.file("JsRuntime.kt") }
    inputs.file(generator)
    inputs.file(jvmRuntime)
    outputs.file(generated)
    doLast {
        runProcess(
            listOf("python3", generator.asFile.path, jvmRuntime.asFile.path,
                generated.get().asFile.path),
            kirNativeWork.get().file("runtime-generate.log").asFile,
        )
    }
}

/** The runtime as a klib, which every generated native program links against. */
val kirNativeRuntimeKlib by tasks.registering {
    dependsOn(kirNativeRuntimeSource)
    val generated = kirNativeWork.map { it.file("JsRuntime.kt") }
    val klib = kirNativeWork.map { it.file("jsruntime.klib") }
    inputs.file(generated)
    outputs.file(klib)
    doLast {
        val output = klib.get().asFile.path.removeSuffix(".klib")
        runProcess(
            listOf("${konanHome()}/bin/konanc", generated.get().asFile.path,
                "-produce", "library", "-o", output),
            kirNativeWork.get().file("runtime-compile.log").asFile,
        )
    }
}

/** Compiles a TypeScript project to a Kotlin/Native binary through the KIR backend. */
val kirNativeCompile by tasks.registering {
    group = "kir"
    description = "Compiles a TypeScript project to a Kotlin/Native binary " +
        "(-PkirProject=<dir> -PkirEntry=<file> -PkirOutput=<path> [-PkirLibrary])"
    dependsOn(kirNativeRuntimeKlib, "compileKotlinJvm", "jvmProcessResources")
    // Parameterized by properties, so there is nothing for Gradle to stamp.
    outputs.upToDateWhen { false }
    doLast {
        val projectDir = resolvePath(
            project.findProperty("kirProject") as String?
                ?: error("-PkirProject=<TypeScript project directory> is required")
        )
        val entry = project.findProperty("kirEntry") as String? ?: "main.ts"
        val output = resolvePath(
            project.findProperty("kirOutput") as String?
                ?: error("-PkirOutput=<output binary, without .kexe> is required")
        )
        check(projectDir.isDirectory) { "no project directory '$projectDir'" }

        val work = kirNativeWork.get().asFile

        // THE SEED. The entry point is resolved by the FRONTEND, which never sees
        // the generated `main` — `-e program.main` answers "could not find"
        // however valid the IR is — so the seed declares the `main` konanc finds
        // and the plugin gives it a body calling the lowered one.
        val seed = File(work, "seed/Seed.kt")
        seed.parentFile.mkdirs()
        seed.writeText(
            "// The `main` konanc resolves. Its body is replaced by the KIR native\n" +
                "// plugin with a call to the entry point lowered from the TypeScript program.\n" +
                "fun main() {\n}\n"
        )

        // THE PLUGIN CLASSPATH: this module's classes and RESOURCES — the
        // registrar is found by ServiceLoader and its META-INF/services file is a
        // resource, which Gradle stages in a different directory; omit it and the
        // plugin is never discovered, konanc exits 0 having compiled the empty
        // seed, and the binary is the size of a hello-world.
        //
        // Deliberately NOT the Kotlin compiler or standard library: the native
        // compiler brings its own, and a second copy on the plugin path is a
        // version fight nobody wins.
        val excluded = listOf(
            "kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect",
            "kotlin-script-runtime", "kotlin-daemon-embeddable", "kotlin-build-tools-api",
        )
        val tail = configurations.getByName("jvmRuntimeClasspath").resolve()
        val pluginPath = buildList {
            add(layout.buildDirectory.dir("classes/kotlin/jvm/main").get().asFile)
            add(layout.buildDirectory.dir("processedResources/jvm/main").get().asFile)
            addAll(tail.filterNot { jar -> excluded.any { jar.name.startsWith(it) } })
        }

        // COROUTINES AHEAD OF THE COMPILER. The front end runs INSIDE konanc, and
        // 1.11.0 renamed `runBlocking`'s JVM entry point to `runBlockingK` while
        // keeping the old one — so the newer jar satisfies both callers and the
        // compiler's own bundled copy satisfies only the compiler. A plugin
        // classloader cannot fix this: its parent is the compiler's, and
        // parent-first means the older copy always wins.
        val coroutines = tail.firstOrNull { it.name.startsWith("kotlinx-coroutines-core-jvm") }
            ?: error("no kotlinx-coroutines on the KIR runtime classpath")

        val konan = konanHome()
        val produce =
            if (project.hasProperty("kirLibrary")) listOf("-produce", "library") else listOf("-opt")
        val log = File(work, "compile.log")
        runProcess(
            listOf(
                "${System.getProperty("java.home")}/bin/java", "-ea", "-Xmx6G",
                "-Dfile.encoding=UTF-8", "-Dkonan.home=$konan",
                "-cp", "$coroutines:$konan/konan/lib/kotlin-native-compiler-embeddable.jar",
                "org.jetbrains.kotlin.cli.utilities.MainKt", "konanc", seed.path,
            ) + pluginPath.map { "-Xplugin=$it" } + listOf(
                "-l", File(work, "jsruntime.klib").path,
            ) + produce + listOf("-o", output.path),
            log,
            mapOf("XTSC_KIR_PROJECT" to projectDir.path, "XTSC_KIR_ENTRY" to entry),
        )

        // POSITIVE CONTROL. The plugin announces itself; without that line the
        // compile ran WITHOUT the backend and produced a binary of the empty seed,
        // at exit 0 and with nothing in the log to say so.
        val announcement = log.readLines().firstOrNull { it.startsWith("xtsc-kir-native:") }
            ?: error("the KIR plugin did not run — konanc compiled the seed alone. See $log")
        logger.lifecycle(announcement)
        logger.lifecycle("kir-native: wrote $output.kexe")
    }
}
