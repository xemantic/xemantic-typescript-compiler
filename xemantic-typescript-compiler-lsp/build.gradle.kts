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
    id("xemantic-typescript-compiler.convention")
}

base {
    archivesName = project.name
}

// THE LSP SERVER (owner directive 2026-09-01, Phase 18): JSON-RPC 2.0 over
// stdio, mapped onto the `-project` embedding API — so anyone can try the
// checker from any editor in five minutes. No lsp4j (JVM-only Java; this repo
// is KMP): the protocol layer is kotlinx-serialization-json over kotlinx-io,
// both already in the version catalog.
//
// JVM now; distributed later as a GraalVM native image through the EXISTING
// nativeImage configuration (a Kotlin/Native target is a later item). Sources
// live in `src/commonMain` so that move is a build-file change.
kotlin {

    // As in `-project`: this module is a contract with out-of-tree callers
    // (every LSP client), so its surface is declared rather than inferred.
    explicitApi()

    jvm()

    sourceSets {

        commonMain {
            dependencies {
                api(project(":xemantic-typescript-compiler-project"))
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.io.core)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.xemantic.kotlin.test)
                implementation(libs.kotlinx.io.core)
            }
        }

    }

}

// ---------------------------------------------------------------------------
// AOT — GraalVM native-image (the (LSP.2) distribution artifact)
// ---------------------------------------------------------------------------
//
// The EXISTING nativeImage configuration, mirrored from `-cli` (pre-approved
// 2026-09-01: "distributed as a GraalVM native image through the EXISTING
// nativeImage configuration"). Same contract: needs a GraalVM JDK plus a C
// toolchain, DELIBERATELY not wired into `build`/`check`, fails with an
// explanatory message when asked for without them. The closed-world analysis
// has the same inputs as the cli image — core's 18 reflection-metadata entries
// on the classpath, no application reflection, no sockets — so `--no-fallback`
// is expected to succeed unassisted; the first verified build is recorded in
// the session note that lands it.
//
//   ./gradlew :xemantic-typescript-compiler-lsp:nativeImage
//   ./gradlew :…-lsp:nativeImage -PgraalvmHome=/opt/graalvm
//   ./gradlew :…-lsp:nativeImage -PnativeImageHeap=6g
//
// The entry point is the stdio LSP main — a server whose only transport is
// stdin/stdout, which is exactly what a native image can carry with none of
// the daemon's ktor machinery (the MOD.7 lesson, inherited).

/** Runs [cmd], inheriting stdio. Throws on a non-zero exit. */
fun runCommand(vararg cmd: String, workingDir: File = projectDir) {
    val exitCode = ProcessBuilder(*cmd)
        .directory(workingDir)
        .inheritIO()
        .start()
        .waitFor()
    check(exitCode == 0) { "Command failed (exit $exitCode): ${cmd.joinToString(" ")}" }
}

val nativeImageMainClass = "com.xemantic.typescript.compiler.lsp.XtscLspMainKt"

val nativeImage = tasks.register("nativeImage") {
    group = "build"
    description = "Ahead-of-time compiles the LSP server into a native executable (needs GraalVM + a C toolchain)."

    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    dependsOn(jvmMain.compileTaskProvider)

    val classpathFiles = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    inputs.files(classpathFiles)
    inputs.property("mainClass", nativeImageMainClass)

    val graalHome = (project.findProperty("graalvmHome") as String?)
        ?: System.getenv("GRAALVM_HOME")
    val builderHeap = (project.findProperty("nativeImageHeap") as String?) ?: "5g"
    val extraArgs = (project.findProperty("nativeImageArgs") as String?)
        ?.split(" ", "\t", "\n")
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    val outputName = (project.findProperty("nativeImageOutput") as String?) ?: "xtsc-lsp"
    inputs.property("nativeImageArgs", extraArgs)
    inputs.property("outputName", outputName)

    val outputDir = layout.buildDirectory.dir("native")
    val binaryFile = outputDir.map { it.file(outputName) }
    outputs.file(binaryFile)

    doLast {
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
            else -> error(
                "native-image not found. Install a GraalVM JDK and either put its " +
                    "bin/ on PATH, set GRAALVM_HOME, or pass -PgraalvmHome=/path/to/graalvm. " +
                    "A C toolchain (gcc, binutils, libc headers, zlib) is also required."
            )
        }
        val out = outputDir.get().asFile
        out.mkdirs()
        val binary = out.resolve(outputName)
        logger.lifecycle(
            "Building native LSP image via $tool (needs gcc + binutils + libc headers + zlib on PATH) ..."
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
        logger.lifecycle("Native LSP executable: $binary")
    }
}
