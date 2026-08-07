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
