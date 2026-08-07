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
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
    id("xemantic-typescript-compiler.convention")
}

base {
    archivesName = project.name
}

// The compile server holds a whole compiler in memory, so this module is
// JVM-only by nature. The thin CLIENT is the piece that becomes a native
// binary, and it depends on `-api` alone.
kotlin {

    jvm()

    sourceSets {

        jvmMain {
            dependencies {
                // `api`: the dispatcher delegates to the compiler's own `main`,
                // so a consumer of this module compiles against both.
                api(project(":xemantic-typescript-compiler-core"))
                // `api`: CompileServer.request returns a CompileResponse, and
                // the transport types appear in its signatures.
                api(project(":xemantic-typescript-compiler-api"))
                // The single-threaded compile dispatcher — see invariant 1 in
                // CompileServer's KDoc; this is not an optional convenience.
                implementation(libs.kotlinx.coroutines.core)
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
 * Materializes `build/install/lib` — this module's jar plus its whole runtime
 * classpath, the exact shape a distribution's `XTSC_HOME/lib` has.
 *
 * `scripts/xtsc` globs it as its development fallback, so the launcher takes
 * the SAME code path in the tree as it does when installed, and its classpath
 * cannot drift from the build's. It replaces an earlier fallback that pasted
 * together known jar names and a cached `cp.txt` holding the COMPILER's
 * dependency tail — which silently omitted this module's own dependencies
 * (`-api`, ktor, coroutines) the moment the transport gained them, and
 * presented as ClassNotFoundException at run time rather than as a build error.
 *
 * Wired into `assemble` because a launcher that works only after an extra,
 * undocumented task is a launcher that appears broken.
 */
val xtscLib by tasks.registering(Sync::class) {
    group = "build"
    description = "Stages the daemon jar and its runtime classpath as a distribution-shaped lib dir."
    from(tasks.named("jvmJar"))
    from(kotlin.targets.getByName("jvm").compilations.getByName("main").runtimeDependencyFiles)
    into(layout.buildDirectory.dir("install/lib"))
}

tasks.named("assemble") { dependsOn(xtscLib) }

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
//   ./gradlew nativeImage                         # uses GRAALVM_HOME
//   ./gradlew nativeImage -PgraalvmHome=/opt/graalvm
//   ./gradlew nativeImage -PnativeImageHeap=6g    # builder heap, default 5g
//
// DELIBERATELY NOT wired into `build`/`check`: it needs a GraalVM JDK *and* a
// working C toolchain (gcc + binutils + libc headers + zlib), which a plain
// JVM CI runner does not have, and it adds ~2 min. Nothing about the normal
// build changes if GraalVM is absent — the task simply fails with the message
// below when explicitly asked for.
//
// Reflection metadata lives in src/jvmMain/resources/META-INF/native-image/...
// and is picked up from the classpath automatically; it is 18 entries, all
// kotlinx-coroutines atomic field updaters. There is ZERO application
// reflection (the TypeScript libs are embedded as Kotlin string constants
// rather than resources), which is why `--no-fallback` succeeds unassisted.
// That survives round 840's entry-point swap: the server path's only candidate
// was kotlinx-serialization, and `javap -c CompileServer` shows all four
// serializer resolutions are the compiler-plugin intrinsic — direct
// `invokevirtual …$Companion.serializer()`, no reflective
// `SerializersKt.serializer(KType)` lookup — while serialization was ALREADY
// reachable from the old entry point anyway (TsConfigLoader parses tsconfig).
// UNVERIFIED on this box (no GraalVM installed, so `nativeImage` cannot run
// here): whether native-image's closed-world analysis needs help with
// `UnixDomainSocketAddress` / `ServerSocketChannel.open(StandardProtocolFamily
// .UNIX)`. If the image builds but `--serve` fails at run time, that is the
// first place to look. Regenerate metadata with the tracing agent only if a
// dependency starts reflecting (trace the ACTUAL entry point, the dispatcher):
//   java -agentlib:native-image-agent=config-output-dir=<dir> -cp ... server.XtscMainKt ...
//
// `-O3 -march=native` was measured and is worth NOTHING here (13,325 vs
// 13,335 ms) — the residual 15% against JVM peak is the absence of PGO, which
// GraalVM CE cannot do. Do not add codegen flags expecting a win.

// (AOT.4)(b), round 840, owner-approved 2026-08-06. The image's entry point is
// the MODE DISPATCHER, not the one-shot compiler, so the native binary can BE
// the thin client its own KDoc says it is for: `--serve` runs the compile
// server, `--daemon` forwards to a running one, and anything else delegates
// verbatim to `com.xemantic.typescript.compiler.main(args)` — a strict
// superset, so every existing invocation behaves exactly as before. Pointed at
// `MainKt` (as it was until round 840) the image silently treated `--serve` and
// `--daemon` as project arguments and compiled instead, exit 0: measured on the
// stale 2026-07-30 binary, `--serve --socket /tmp/x.sock` bound no socket, took
// the socket path as the project, and emitted 173 files.
val nativeImageMainClass = "com.xemantic.typescript.compiler.server.XtscMainKt"

val nativeImage by tasks.registering {
    group = "build"
    description = "Ahead-of-time compiles the JVM target into a native executable (needs GraalVM + a C toolchain)."

    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    dependsOn(jvmMain.compileTaskProvider)

    val classpathFiles = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    inputs.files(classpathFiles)
    inputs.property("mainClass", nativeImageMainClass)

    val outputDir = layout.buildDirectory.dir("native")
    val binaryFile = outputDir.map { it.file("xtsc") }
    outputs.file(binaryFile)

    // Resolved at CONFIGURATION time so the task's inputs are honest.
    val graalHome = (project.findProperty("graalvmHome") as String?)
        ?: System.getenv("GRAALVM_HOME")
    val builderHeap = (project.findProperty("nativeImageHeap") as String?) ?: "5g"

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
        val binary = out.resolve("xtsc")

        // A missing C toolchain is the most likely failure and its message is
        // opaque (it aborts inside CCompilerInvoker), so say so up front.
        logger.lifecycle(
            "Building native image via $tool (needs gcc + binutils + libc headers + zlib on PATH) ..."
        )
        runCommand(
            tool,
            "-cp", classpathFiles.joinToString(File.pathSeparator) { it.absolutePath },
            "-o", binary.absolutePath,
            "--no-fallback",
            "-J-Xmx$builderHeap",
            nativeImageMainClass
        )
        logger.lifecycle("Native executable: $binary")
    }
}
