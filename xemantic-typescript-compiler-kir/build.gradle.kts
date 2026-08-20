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
