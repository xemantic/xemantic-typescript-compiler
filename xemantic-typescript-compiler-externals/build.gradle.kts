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

// THE KOTLIN EXTERNALS GENERATOR (owner directive 2026-09-01, Phase 18):
// consumes the CHECKED program — the same `CheckedNodeSink`/`CheckedLens`
// facts interface the KIR backend consumes — and emits Kotlin `external`
// declarations whose types are the checker's answers. This is the gap Dukat
// and Karakum never closed: both translate `.d.ts` SYNTAX, so everything whose
// answer is not in the syntax (overload selection, generic instantiation,
// conditional and mapped types) is where they stop; this module starts from
// the resolved type graph instead.
//
// JVM-first (pre-approved 2026-09-01). Sources live in `src/commonMain` so a
// native target later is a build-file change, exactly as in `-project`.
kotlin {

    // A contract with out-of-tree callers, so the surface is declared rather
    // than inferred — as in `-project`, `-cli` and `-client`.
    explicitApi()

    jvm()

    sourceSets {

        commonMain {
            dependencies {
                api(project(":xemantic-typescript-compiler-core"))
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.xemantic.kotlin.test)
            }
        }

        jvmTest {
            dependencies {
                // The COMPILE GATE: generated Kotlin must compile, checked
                // in-test with the embedded compiler — the same dependency the
                // KIR backend already carries, so nothing new enters the build.
                implementation(libs.kotlin.compiler.embeddable)
            }
        }

    }

}
