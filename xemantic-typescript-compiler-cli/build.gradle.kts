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
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
    id("xemantic-typescript-compiler.convention")
}

// The root project applies the xemantic conventions, which reach into every
// project and leave `archivesName` unset here; without this, configuring
// `jvmJar` fails with "archiveBaseName has no value available".
//
// `project.name`, so the jar is `xemantic-typescript-compiler-cli-jvm-<ver>.jar`
// and cannot collide with the compiler's own `xemantic-typescript-compiler-jvm-
// *.jar` — a name the AOT scripts GLOB for (CLAUDE.md's jar-naming contract).
base {
    archivesName = project.name
}

// THE POINT OF THIS MODULE, AS IN `-client`, IS WHAT IT DOES *NOT* DEPEND ON.
//
// It is the one-shot CLI and nothing else: the compiler core, and no transport.
// The GraalVM image used to be built from the DAEMON module, whose entry point
// is the mode dispatcher, so the shipped ahead-of-time binary carried the whole
// daemon stack — ktor-network, its slf4j dependency, the socket machinery — into
// a closed-world analysis of a process that can never serve or contact a daemon.
// `LeanClasspathTest` is the pin on that edge, and it asserts absence at RUN
// time rather than reading this file.
//
// kotlinx-serialization is NOT removed by this split and no pin pretends
// otherwise: `TsConfigLoader` parses tsconfig.json with it, so it arrives
// through the compiler core and is reachable from any entry point at all.
kotlin {

    // As in `-api` and `-client`: this module's surface is what a shipped binary
    // is built from, so it is declared rather than inferred.
    explicitApi()

    jvm()

    sourceSets {

        jvmMain {
            dependencies {
                // `api`: this module's `main` delegates to the compiler's `runCli`,
                // so a consumer compiles against both. NOTE the deliberate absence
                // of `:xemantic-typescript-compiler-api` — it exports ktor-network,
                // which is exactly what this module exists not to carry.
                api(project(":xemantic-typescript-compiler-core"))
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

/**
 * Runs a shell command, streaming its output to the Gradle console.
 * Throws an [IllegalStateException] if the process exits with a non-zero code.
 */
fun runCommand(vararg cmd: String, workingDir: File = projectDir) {
    val exitCode = ProcessBuilder(*cmd)
        .directory(workingDir)
        .inheritIO()
        .start()
        .waitFor()
    check(exitCode == 0) { "Command failed (exit $exitCode): ${cmd.joinToString(" ")}" }
}

// ---------------------------------------------------------------------------
// AOT — GraalVM native-image
// ---------------------------------------------------------------------------
//
// Ahead-of-time compiles the JVM target into a standalone native executable.
// Measured round 771 on the compiler profile: 13,350 ms against the JVM's
// 26,272 ms (1.97x), with `--listAll` output BYTE-IDENTICAL to the JVM's on all
// eight bench profiles, 392 MB RSS against a 4 GB heap allowance. The win is
// not faster code — it is the ~14.7 s of JVM warm-up a one-shot CLI run never
// amortizes. Full derivation and caveats: docs/perf/aot-native-image.md.
//
//   ./gradlew :xemantic-typescript-compiler-cli:nativeImage
//   ./gradlew :…-cli:nativeImage -PgraalvmHome=/opt/graalvm
//   ./gradlew :…-cli:nativeImage -PnativeImageHeap=6g   # builder heap, default 5g
//   ./gradlew :…-cli:nativeImage -PnativeImageArgs="--pgo-instrument" \
//       -PnativeImageOutput=xtsc-instrumented
//   ./gradlew :…-cli:nativeImage -PnativeImageArgs="--pgo=/abs/path/xtsc.iprof"
//
// THE TASK LIVES HERE, NOT IN THE DAEMON MODULE (MOD.7). Its entry point is the
// LEAN CLI: a one-shot binary can never serve or contact a daemon, so building
// it from the mode dispatcher put ktor-network, slf4j and the socket machinery
// into the closed-world analysis for nothing. The refusal of --serve/--daemon
// that replaces them is in `cli/Main.kt`, and is what keeps this from being
// round 840's silent wrong success. `scripts/xtsc` is UNAFFECTED and still runs
// the dispatcher — it has a daemon to reach.
//
// DELIBERATELY NOT wired into `build`/`check`: it needs a GraalVM JDK *and* a
// working C toolchain (gcc + binutils + libc headers + zlib), which a plain
// JVM CI runner does not have, and it adds ~2 min. Nothing about the normal
// build changes if GraalVM is absent — the task simply fails with the message
// below when explicitly asked for.
//
// Reflection metadata lives in the CORE module's
// src/jvmMain/resources/META-INF/native-image/... and is picked up from the
// classpath automatically; it is 18 entries, all kotlinx-coroutines atomic field
// updaters. There is ZERO application reflection (the TypeScript libs are
// embedded as Kotlin string constants rather than resources), which is why
// `--no-fallback` succeeds unassisted. Round 840(b)'s one open question —
// whether closed-world analysis needs help with `UnixDomainSocketAddress` /
// `ServerSocketChannel.open(StandardProtocolFamily.UNIX)` — is MOOT for this
// image, because nothing on this classpath opens a socket at all. Regenerate
// metadata with the tracing agent only if a dependency starts reflecting:
//   java -agentlib:native-image-agent=config-output-dir=<dir> -cp ... \
//       com.xemantic.typescript.compiler.cli.MainKt ...
//
// `-O3 -march=native` was measured and is worth NOTHING here (13,325 vs
// 13,335 ms) — the residual 15% against JVM peak is the absence of PGO. Do not
// add codegen flags expecting a win; PGO itself goes through -PnativeImageArgs.

// (MOD.7). The image's entry point is the lean one-shot CLI, which REFUSES the
// daemon modes rather than silently treating them as project arguments — the
// third option between "carry the whole transport" (the daemon dispatcher, where
// this task used to live) and round 840's measured silent wrong success (the
// bare `…compiler.MainKt`: `--serve --socket /tmp/x.sock` bound no socket, took
// the socket path as the project, emitted 173 files, exit 0).
//
// `NativeImageEntryPointTest` reads this line — it must stay a single
// `val nativeImageMainClass = "…"` assignment — and checks the named class
// actually exists with a `main`, which a string comparison alone cannot.
val nativeImageMainClass = "com.xemantic.typescript.compiler.cli.MainKt"

// `tasks.register`, not `by tasks.registering`: the delegated form is deprecated
// in Gradle 9.6 and this build is kept warning-clean.
val nativeImage = tasks.register("nativeImage") {
    group = "build"
    description = "Ahead-of-time compiles the JVM target into a native executable (needs GraalVM + a C toolchain)."

    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    dependsOn(jvmMain.compileTaskProvider)

    val classpathFiles = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    inputs.files(classpathFiles)
    inputs.property("mainClass", nativeImageMainClass)

    // Resolved at CONFIGURATION time so the task's inputs are honest.
    val graalHome = (project.findProperty("graalvmHome") as String?)
        ?: System.getenv("GRAALVM_HOME")
    val builderHeap = (project.findProperty("nativeImageHeap") as String?) ?: "5g"

    // Extra arguments, space separated, appended after the standard ones and
    // before the main class (native-image takes its options first). Added for the
    // PGO cycle — `--pgo-instrument`, then `--pgo=/abs/path/x.iprof` — which
    // Oracle GraalVM has and GraalVM Community does NOT: with CE the builder
    // rejects the flag, so a failure here is a distribution question first.
    // Splitting on whitespace means a path containing a space cannot be passed;
    // pass an absolute path without one.
    val extraArgs = (project.findProperty("nativeImageArgs") as String?)
        ?.split(" ", "\t", "\n")
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    // The output NAME, so an instrumented image and a final one can sit side by
    // side in build/native/ instead of overwriting each other. It is an input
    // AND selects the declared output — both, or Gradle would consider a
    // differently-named image up to date.
    val outputName = (project.findProperty("nativeImageOutput") as String?) ?: "xtsc"
    inputs.property("nativeImageArgs", extraArgs)
    inputs.property("outputName", outputName)

    val outputDir = layout.buildDirectory.dir("native")
    val binaryFile = outputDir.map { it.file(outputName) }
    outputs.file(binaryFile)

    doLast {
        // Prefer an explicit GraalVM home; fall back to whatever is on PATH so a
        // GraalVM-provisioned CI runner works with no extra configuration.
        val fromHome = graalHome?.let { File(it).resolve("bin/native-image") }
        val onPath = System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.map { File(it).resolve("native-image") }
            ?.firstOrNull { it.canExecute() }
        val tool = when {
            fromHome != null && fromHome.canExecute() -> fromHome.absolutePath
            fromHome != null -> error(
                "native-image not found at $fromHome — is '$graalHome' a GraalVM JDK?"
            )
            onPath != null -> onPath.absolutePath
            // Checked explicitly rather than left to ProcessBuilder, which reports
            // this as a bare `IOException: Cannot run program "native-image"` that
            // says nothing about what to install or which flag to pass.
            else -> error(
                "native-image not found. Install a GraalVM JDK and either put its " +
                    "bin/ on PATH, set GRAALVM_HOME, or pass -PgraalvmHome=/path/to/graalvm. " +
                    "A C toolchain (gcc, binutils, libc headers, zlib) is also required."
            )
        }

        val out = outputDir.get().asFile
        out.mkdirs()
        val binary = out.resolve(outputName)

        // A missing C toolchain is the most likely failure and its message is
        // opaque (it aborts inside CCompilerInvoker), so say so up front.
        logger.lifecycle(
            "Building native image via $tool (needs gcc + binutils + libc headers + zlib on PATH) ..."
        )
        runCommand(
            *(
                listOf(
                    tool,
                    "-cp", classpathFiles.joinToString(File.pathSeparator) { it.absolutePath },
                    "-o", binary.absolutePath,
                    "--no-fallback",
                    "-J-Xmx$builderHeap"
                ) + extraArgs + nativeImageMainClass
                ).toTypedArray()
        )
        logger.lifecycle("Native executable: $binary")
    }
}
