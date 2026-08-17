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
// `project.name`, so the jar is `xemantic-typescript-compiler-project-jvm-
// <ver>.jar` and cannot collide with the compiler's own
// `xemantic-typescript-compiler-jvm-*.jar` — a name the AOT scripts GLOB for
// (CLAUDE.md's jar-naming contract).
base {
    archivesName = project.name
}

// THE EMBEDDING API: a `Project` a host application opens, queries for
// diagnostics, edits IN MEMORY, and re-queries.
//
// It depends on the compiler core and on NO transport: this module is what a
// build tool, an IDE plugin or a test harness compiles against, and none of them
// should inherit ktor-network (which is what `-api` exports) merely to type-check
// a directory. That is the same edge `-cli` draws for the one-shot binary.
//
// `api(core)`, not `implementation`: `Diagnostic`, `DiagnosticCategory` and `Vfs`
// appear in this module's own public signatures, so a consumer necessarily
// compiles against them.
kotlin {

    // As in `-api`, `-cli` and `-client`: this module IS a contract with
    // out-of-tree callers, so its surface is declared rather than inferred.
    explicitApi()

    // JVM only, deliberately, and for the same reason `-core` is: this module
    // pulls the whole compiler in, and Checker.kt costs minutes per native link
    // (CLAUDE.md's native-build protocol). `src/commonMain` / `src/commonTest`
    // are nevertheless where the sources live, so adding a native target later is
    // a build-file change rather than a source move — which is also why the tests
    // obey the Kotlin/Native-compatible commonTest rules.
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

    }

}
