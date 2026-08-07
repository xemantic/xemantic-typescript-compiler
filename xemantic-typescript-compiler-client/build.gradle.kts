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

// THE POINT OF THIS MODULE IS WHAT IT DOES *NOT* DEPEND ON.
//
// It depends on `-api` and nothing else — never on the compiler. That edge is
// the whole arc: the shipped `xtsc` binary stops carrying a 230k-line compiler
// it only ever asks to run somewhere else. It also means native targets are
// affordable here: the compiler module keeps them off because Checker.kt costs
// minutes per link, while this module links in seconds.
kotlin {

    // As in `-api`: this module's surface is a contract a shipped binary is built
    // from, so it is declared rather than inferred.
    explicitApi()

    jvm()

    // Native stays behind the repo's existing opt-in flag rather than a new one.
    //
    // mingwX64 is DELIBERATELY ABSENT, and it is the transport that is fine while
    // the SPAWN is not: ktor-network implements AF_UNIX on Windows behind a
    // runtime probe, so a Windows client could talk to a running daemon today,
    // but `spawnDetached` is written against fork/setsid/execvp, which Windows
    // has no equivalent of — it needs CreateProcess with DETACHED_PROCESS. Adding
    // the target before that exists would not fail the build, it would ship a
    // client that cannot start what it depends on. Tracked as (MOD.6).
    if (project.findProperty("enableNativeTargets") == "true") {
        macosArm64 {
            binaries.executable { entryPoint = "com.xemantic.typescript.compiler.client.main" }
        }
        linuxX64 {
            binaries.executable { entryPoint = "com.xemantic.typescript.compiler.client.main" }
        }
    }

    sourceSets {

        commonMain {
            dependencies {
                api(project(":xemantic-typescript-compiler-api"))
                implementation(libs.kotlinx.coroutines.core)
                // Filesystem access in COMMON code — it covers mingw as well as
                // posix, which is what keeps `absolutePathIfExists` out of the
                // expect/actual list.
                implementation(libs.kotlinx.io.core)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.xemantic.kotlin.test)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
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
// A SECOND ahead-of-time client: GraalVM native-image over the jvm target.
// ---------------------------------------------------------------------------
//
// Not a replacement for the Kotlin/Native binaries above — a complement, and the
// reason is Windows. `spawnDetached` on native is fork/setsid/execvp, which
// Windows has no equivalent of, whereas the JVM actual is `ProcessBuilder` and
// works there already; ktor's JVM `UnixSocketAddress` likewise delegates to
// `java.net.UnixDomainSocketAddress`, which has supported Windows AF_UNIX since
// JEP 380. So this arm can cover the platform (MOD.6) with the actuals that
// already exist, instead of hand-writing CreateProcess cinterop.
//
// THE ONE THING THAT MAKES IT NON-TRIVIAL: ktor's JVM UnixSocketAddress reaches
// java.net.UnixDomainSocketAddress REFLECTIVELY — `Class.forName`, then
// `getMethod("of", String)` and `getMethod("getPath")` — because ktor's own
// baseline predates Java 16. Closed-world analysis cannot see through that, so
// without the reachability metadata beside this module's sources the image
// builds cleanly and then fails at RUN time with "Unix domain sockets are
// unsupported before Java 16" on a JDK 25. The metadata is generated with the
// tracing agent (`nativeClientAgent`), never hand-written.
val nativeClientMainClass = "com.xemantic.typescript.compiler.client.MainKt"

val clientLib = tasks.register<Sync>("clientLib") {
    group = "build"
    description = "Stages the client jar and its runtime classpath (distribution-shaped)."
    from(tasks.named("jvmJar"))
    from(kotlin.targets.getByName("jvm").compilations.getByName("main").runtimeDependencyFiles)
    into(layout.buildDirectory.dir("install/lib"))
}

fun graalHome(): File? =
    (project.findProperty("graalvmHome") as String?)?.let { File(it) }
        ?: System.getenv("GRAALVM_HOME")?.let { File(it) }

val nativeClientImage = tasks.register("nativeClientImage") {
    group = "build"
    description = "Ahead-of-time compiles the JVM client with GraalVM native-image."
    dependsOn(clientLib)
    val libDir = layout.buildDirectory.dir("install/lib")
    val binary = layout.buildDirectory.file("native/xtsc-graal")
    inputs.dir(libDir)
    inputs.property("mainClass", nativeClientMainClass)
    outputs.file(binary)
    doLast {
        val home = graalHome()
            ?: throw GradleException(
                "native-image needs a GraalVM JDK: set GRAALVM_HOME or pass " +
                    "-PgraalvmHome=/path/to/graalvm"
            )
        val tool = File(home, "bin/native-image")
        if (!tool.canExecute()) throw GradleException("native-image not found at $tool")
        val jars = libDir.get().asFile.listFiles { f: File -> f.extension == "jar" }
            ?.sortedBy { it.name } ?: emptyList()
        val out = binary.get().asFile
        out.parentFile.mkdirs()
        runCommand(
            tool.absolutePath,
            "-cp", jars.joinToString(File.pathSeparator) { it.absolutePath },
            "-o", out.absolutePath,
            "--no-fallback",
            nativeClientMainClass,
        )
        logger.lifecycle("Native client: $out")
    }
}

/**
 * Runs the JVM client under native-image's tracing agent to (re)generate the
 * reachability metadata committed beside this module's sources.
 *
 * Needs a daemon already listening on -PsocketPath, and a project to compile at
 * -PprojectPath: the agent records what a REAL request touches, and a run that
 * fails to reach the socket records nothing about the socket.
 */
val nativeClientAgent = tasks.register("nativeClientAgent") {
    group = "build"
    description = "Regenerates native-image reachability metadata by tracing a real request."
    dependsOn(clientLib)
    val libDir = layout.buildDirectory.dir("install/lib")
    val metadataDir = file(
        "src/jvmMain/resources/META-INF/native-image/" +
            "com.xemantic.typescript/xemantic-typescript-compiler-client"
    )
    val socket = project.findProperty("socketPath") as String? ?: "/tmp/xtsc-agent.sock"
    // NOT `projectPath`: that is a built-in Gradle project property of type
    // org.gradle.util.Path, and reading it as a String fails at task creation.
    val tsProject = project.findProperty("tsProject") as String? ?: "."
    doLast {
        // The agent ships WITH GraalVM, so this must run on that JDK specifically:
        // a toolchain lookup by version would happily pick a non-Graal JDK of the
        // same version, where the agent simply does not exist.
        val home = graalHome() ?: throw GradleException(
            "the tracing agent needs a GraalVM JDK: set GRAALVM_HOME or -PgraalvmHome=..."
        )
        metadataDir.mkdirs()
        val jars = libDir.get().asFile.listFiles { f: File -> f.extension == "jar" }
            ?.sortedBy { it.name } ?: emptyList()
        // The exit code is IGNORED here on purpose: the client mirrors the
        // compiler and exits non-zero whenever the traced project has
        // diagnostics, which says nothing about whether the trace succeeded.
        val exit = ProcessBuilder(
            File(home, "bin/java").absolutePath,
            "-agentlib:native-image-agent=config-output-dir=${metadataDir.absolutePath}",
            "-cp", jars.joinToString(File.pathSeparator) { it.absolutePath },
            nativeClientMainClass,
            "--socket", socket, "--no-spawn", "--noEmit", tsProject,
        ).directory(projectDir).inheritIO().start().waitFor()
        logger.lifecycle("traced client exited $exit")
        check(File(metadataDir, "reachability-metadata.json").isFile) {
            "the agent produced no metadata - did the daemon answer on $socket?"
        }
        logger.lifecycle("Reachability metadata written to $metadataDir")
    }
}
