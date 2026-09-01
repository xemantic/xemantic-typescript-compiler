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
