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
val xtscLib = tasks.register<Sync>("xtscLib") {
    group = "build"
    description = "Stages the daemon jar and its runtime classpath as a distribution-shaped lib dir."
    from(tasks.named("jvmJar"))
    from(kotlin.targets.getByName("jvm").compilations.getByName("main").runtimeDependencyFiles)
    into(layout.buildDirectory.dir("install/lib"))
}

tasks.named("assemble") { dependsOn(xtscLib) }

// THE GRAALVM NATIVE IMAGE IS NOT BUILT HERE ANY MORE (MOD.7). It moved to
// :xemantic-typescript-compiler-cli, whose entry point is the lean one-shot
// CLI: a binary that can never serve or contact a daemon has no use for
// ktor-network, its slf4j tail or the socket machinery, and building the image
// from this module's mode dispatcher put all of it into the closed-world
// analysis. The lean entry REFUSES --serve/--daemon rather than treating them
// as project arguments, which is what keeps the move from re-creating round
// 840's silent wrong success; `AotCacheGuardTest` pins that the task has not
// come back here, and the CLI module's `NativeImageEntryPointTest` pins what it
// is built from over there.
//
// The JVM launcher is UNAFFECTED: `scripts/xtsc` and `scripts/xtsc-aot` still
// run `…compiler.server.XtscMainKt`, because they do have a daemon to reach.
