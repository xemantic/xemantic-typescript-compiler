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
