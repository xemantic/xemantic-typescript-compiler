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

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import kotlin.test.Test

/**
 * M1.3: tsconfig `types` / `typeRoots` / `node_modules/@types` acquisition in
 * [ProjectCompiler]. The pinned invariant is the SHARP one: a type package that
 * declares an ambient GLOBAL can enter the program only through acquisition
 * (no import references it), so inclusion is observable as the global name
 * resolving (no TS2304) and the entry file joining `programFiles` — and
 * NON-inclusion as the exact inverse.
 */
class TypesAcquisitionTest {

    private val gadgetEntry = "/proj/node_modules/@types/gadget/lib/main.d.ts"
    private val widgetEntry = "/proj/node_modules/@types/widget/index.d.ts"

    /**
     * `gadget` (entry via package.json `types` field) and `widget` (entry via the
     * `index.d.ts` fallback) declare ambient globals and are never imported —
     * only type acquisition can pull their files into the program.
     */
    private fun project(tsconfig: String, extra: Map<String, String> = emptyMap()) = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to tsconfig,
            "/proj/src/index.ts" to "export const total: number = gadget + widget;",
            "/proj/node_modules/@types/gadget/package.json" to """{ "types": "./lib/main.d.ts" }""",
            "/proj/node_modules/@types/gadget/lib/main.d.ts" to "declare var gadget: number;",
            "/proj/node_modules/@types/widget/index.d.ts" to "declare var widget: number;",
        ) + extra,
    )

    private fun cannotFindName(result: ProjectCompiler.Result, name: String): Boolean =
        result.diagnostics.any { it.code == 2304 && it.message.contains("'$name'") }

    @Test
    fun autoIncludesEveryTypePackageWhenTypesUnspecified() {
        val result = ProjectCompiler(project("""{ "include": ["src/**/*.ts"] }"""))
            .build("/proj", noEmit = true)
        val program = result.programFiles.toSet()
        assert(gadgetEntry in program)
        assert(widgetEntry in program)
        have(result.diagnostics.none { it.code == 2304 })
    }

    @Test
    fun typesFieldSelectsSubset() {
        val result = ProjectCompiler(
            project("""{ "compilerOptions": { "types": ["gadget"] }, "include": ["src/**/*.ts"] }"""),
        ).build("/proj", noEmit = true)
        val program = result.programFiles.toSet()
        assert(gadgetEntry in program)
        have(widgetEntry !in program, "'types' must exclude unlisted packages")
        have(!cannotFindName(result, "gadget"), "listed package's global resolves")
        have(cannotFindName(result, "widget"), "unlisted package's global must NOT resolve")
    }

    @Test
    fun emptyTypesDisablesAutoInclusion() {
        val result = ProjectCompiler(
            project("""{ "compilerOptions": { "types": [] }, "include": ["src/**/*.ts"] }"""),
        ).build("/proj", noEmit = true)
        val program = result.programFiles.toSet()
        have(gadgetEntry !in program && widgetEntry !in program, "types:[] disables acquisition")
        have(cannotFindName(result, "gadget") && cannotFindName(result, "widget"))
    }

    @Test
    fun missingRequestedTypesPackageReportsTs2688() {
        val result = ProjectCompiler(
            project("""{ "compilerOptions": { "types": ["gadget", "nope"] }, "include": ["src/**/*.ts"] }"""),
        ).build("/proj", noEmit = true)
        have(
            result.diagnostics.any { it.code == 2688 && it.message.contains("'nope'") },
            "explicitly requested but unresolvable name reports TS2688",
        )
        have(
            result.diagnostics.none { it.code == 2688 && it.message.contains("'gadget'") },
            "resolvable requested name must not report TS2688",
        )
    }

    @Test
    fun typeRootsReplacesDefaultAtTypesScan() {
        val result = ProjectCompiler(
            project(
                """{ "compilerOptions": { "typeRoots": ["./typings"] }, "include": ["src/**/*.ts"] }""",
                mapOf("/proj/typings/env/index.d.ts" to "declare var gadget: number; declare var widget: number;"),
            ),
        ).build("/proj", noEmit = true)
        val program = result.programFiles.toSet()
        assert("/proj/typings/env/index.d.ts" in program)
        have(
            gadgetEntry !in program && widgetEntry !in program,
            "explicit typeRoots must REPLACE the node_modules/@types default, not extend it",
        )
        have(result.diagnostics.none { it.code == 2304 })
    }

    @Test
    fun typeRootWalkUpFindsAncestorNodeModules() {
        val vfs = InMemoryVfs(
            mapOf(
                "/repo/packages/app/tsconfig.json" to """{ "include": ["src/**/*.ts"] }""",
                "/repo/packages/app/src/index.ts" to "export const t: number = hoisted;",
                // Hoisted monorepo layout: @types two directories above the config.
                "/repo/node_modules/@types/hoistedlib/index.d.ts" to "declare var hoisted: number;",
            ),
        )
        val result = ProjectCompiler(vfs).build("/repo/packages/app", noEmit = true)
        assert("/repo/node_modules/@types/hoistedlib/index.d.ts" in result.programFiles.toSet())
        have(result.diagnostics.none { it.code == 2304 })
    }

    @Test
    fun scopeDirInsideTypeRootAutoDiscovered() {
        val result = ProjectCompiler(
            project(
                """{ "include": ["src/**/*.ts"] }""",
                mapOf(
                    "/proj/src/index.ts" to "export const t: number = scopedThing;",
                    "/proj/node_modules/@types/@myscope/thing/index.d.ts" to "declare var scopedThing: number;",
                ),
            ),
        ).build("/proj", noEmit = true)
        assert("/proj/node_modules/@types/@myscope/thing/index.d.ts" in result.programFiles.toSet())
        have(!cannotFindName(result, "scopedThing"))
    }

    @Test
    fun scopedTypesNameResolvesViaMangledDirectory() {
        val result = ProjectCompiler(
            project(
                """{ "compilerOptions": { "types": ["@myscope/thing"] }, "include": ["src/**/*.ts"] }""",
                mapOf(
                    "/proj/src/index.ts" to "export const t: number = scopedThing;",
                    // DefinitelyTyped publishes @scope/name as scope__name under @types.
                    "/proj/node_modules/@types/myscope__thing/index.d.ts" to "declare var scopedThing: number;",
                ),
            ),
        ).build("/proj", noEmit = true)
        assert("/proj/node_modules/@types/myscope__thing/index.d.ts" in result.programFiles.toSet())
        have(result.diagnostics.none { it.code == 2688 }, "mangled probe must satisfy the request")
        have(!cannotFindName(result, "scopedThing"))
    }

    @Test
    fun referenceTypesDirectiveResolvesEvenWithEmptyTypes() {
        // `types: []` disables AUTOMATIC inclusion only — an explicit
        // `/// <reference types="..." />` still pulls the package in (the parser
        // records the directive as a module specifier; the resolver's @types
        // fallback finds the package).
        val result = ProjectCompiler(
            project(
                """{ "compilerOptions": { "types": [] }, "include": ["src/**/*.ts"] }""",
                mapOf(
                    "/proj/src/index.ts" to
                        "/// <reference types=\"reflib\" />\nexport const r: number = refthing;",
                    "/proj/node_modules/@types/reflib/index.d.ts" to "declare var refthing: number;",
                ),
            ),
        ).build("/proj", noEmit = true)
        assert("/proj/node_modules/@types/reflib/index.d.ts" in result.programFiles.toSet())
        have(!cannotFindName(result, "refthing"))
    }
}
