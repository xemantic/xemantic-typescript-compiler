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

// (EXT.17) THE KOTLIN/JS COMPILE GATE'S STDLIB, DECLARED RATHER THAN LOCATED
// (owner-approved 2026-09-03).
//
// `JsCompileCheck` drives `K2JSCompiler` over the generator's REAL output —
// `@file:JsModule`, `external`, `@JsName`, nested `external object`s — which
// the metadata gate cannot see (it compiles the annotation-free variant). A
// Kotlin/JS compilation resolves every name from the Kotlin/JS stdlib KLIB,
// and nothing else in this build pulls that artifact: before this block the
// gate found it in the Gradle cache only by accident of some other build
// having downloaded it, and on a fresh box (CI) it skipped. It found two
// SILENT generator defects the day it first ran, so skipping is not free.
//
// Artifact-only notation (`@klib`) deliberately: it bypasses variant-aware
// resolution, so this configuration needs none of the Kotlin/JS platform
// attributes and cannot be handed a JVM or Native variant by mistake. The
// version is the compiler's own — a klib carries `abi_version`, the compiler
// refuses one newer than itself, and an older one measures the wrong stdlib
// ([JsStdlib] states the same invariant on the locating side, which stays as
// the fallback for a developer running the gate outside Gradle).
val kotlinStdlibJsDependencies = configurations.dependencyScope("kotlinStdlibJsDependencies")

val kotlinStdlibJs = configurations.resolvable("kotlinStdlibJs") {
    extendsFrom(kotlinStdlibJsDependencies.get())
}

dependencies {
    kotlinStdlibJsDependencies("org.jetbrains.kotlin:kotlin-stdlib-js:${libs.versions.kotlin.get()}@klib")
}

tasks.named<Test>("jvmTest") {
    // `Gradle does not forward -D to the test JVM` (CLAUDE.md, 2026-08-21), so
    // this is an ENVIRONMENT variable — the same one a developer sets by hand.
    val klib = kotlinStdlibJs.get().elements.map { it.single().asFile.absolutePath }
    inputs.files(kotlinStdlibJs).withPropertyName("kotlinStdlibJs")
    doFirst {
        environment("XTSC_KOTLIN_STDLIB_JS", klib.get())
    }
}
