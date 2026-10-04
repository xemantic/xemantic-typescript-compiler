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
import kotlin.test.Test

/**
 * (INV.0) (P18.294) The module-resolution family as it lives in `ModuleResolutionChecks` after the
 * extraction: one pin per entry point — `checkUnresolvedModules` (TS2307, TS5097),
 * `checkRelativeImportsInAmbientModules` (TS2439), `checkNestedAmbientModules` (TS2435),
 * `checkImportEqualsRequireOfNonModule` and `checkNamespaceImportOfNonModule` (TS2306),
 * `checkJsxImportResolutions` (TS6142) — plus the
 * re-pointed helper `emitTS2307` reached for a bare specifier. Every expected row is tsgo 7.0.2's
 * (cells under `build/bench/p18294-agent/matrix/`, 1-based column); all nine cells read
 * byte-identical on both arms of the move.
 *
 * Two pre-existing divergences, shared by both arms and NOT asserted here: tsgo also reports
 * TS2307 at `./bar`'s specifier inside the ambient module (2:24) where we report only TS2439; and
 * tsgo's TS2306 names the target by its full path where ours names the basename.
 */
class ModuleResolutionChecksCollaboratorTest {

    private val esm = "// @strict: true\n// @target: es2020\n// @module: esnext\n// @moduleResolution: bundler"

    private fun rows(source: String, directives: String = esm): List<String> =
        diagnose(source, directives = directives)
            .map { "${it.fileName}:${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `an import of a missing relative module is TS2307`() {
        val r = rows(
            """
            // @Filename: /p/main.ts
            import { x } from "./missing";
            export const y = x;
            """,
        )
        assert(r == listOf("/p/main.ts:1:19 TS2307 Cannot find module './missing' or its corresponding type declarations."))
    }

    @Test
    fun `an import of a missing bare package is TS2307 through the re-pointed emitter`() {
        val r = rows(
            """
            // @Filename: /p/main.ts
            import { v } from "fs-nope";
            export const w = v;
            """,
        )
        assert(r == listOf("/p/main.ts:1:19 TS2307 Cannot find module 'fs-nope' or its corresponding type declarations."))
    }

    @Test
    fun `a dot-ts import path without allowImportingTsExtensions is TS5097`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            export const a = 1;
            // @Filename: /p/main.ts
            import { a } from "./a.ts";
            export const b = a;
            """,
        )
        assert(r == listOf("/p/main.ts:1:19 TS5097 An import path can only end with a '.ts' extension when 'allowImportingTsExtensions' is enabled."))
    }

    @Test
    fun `a relative import inside an ambient module declaration is TS2439`() {
        val r = rows(
            """
            // @Filename: /p/amb.d.ts
            declare module "foo" {
                import x = require("./bar");
                export { x };
            }
            // @Filename: /p/main.ts
            export {};
            """,
        ).filter { " TS2439 " in it }
        assert(r == listOf("/p/amb.d.ts:2:5 TS2439 Import or export declaration in an ambient module declaration cannot reference module through relative module name."))
    }

    @Test
    fun `an ambient module nested in a namespace is TS2435`() {
        val r = rows(
            """
            // @Filename: /p/main.ts
            namespace N {
                declare module "inner" {
                    export const v: number;
                }
            }
            """,
        )
        assert(r == listOf("/p/main.ts:2:20 TS2435 Ambient modules cannot be nested in other modules or namespaces."))
    }

    @Test
    fun `import equals require of a script file is TS2306`() {
        val d = diagnose(
            """
            // @Filename: /p/b.ts
            var bvar = 1;
            // @Filename: /p/main.ts
            import b = require("./b");
            export const z = b;
            """,
            directives = "// @strict: true\n// @target: es2020\n// @module: commonjs",
        ).map { "${it.fileName}:${it.line}:${it.character} TS${it.code}" }
        assert(d == listOf("/p/main.ts:1:20 TS2306"))
    }

    @Test
    fun `a namespace import of a script package in node_modules is TS2306`() {
        val r = rows(
            """
            // @Filename: /p/node_modules/pkg/index.d.ts
            declare var pv: number;
            // @Filename: /p/main.ts
            import * as m from "pkg";
            export const n = m;
            """,
        )
        assert(r == listOf("/p/main.ts:1:20 TS2306 File '/p/node_modules/pkg/index.d.ts' is not a module."))
    }

    @Test
    fun `a relative import resolving to a tsx file with jsx unset is TS6142`() {
        val r = rows(
            """
            // @Filename: /p/comp.tsx
            export const Comp = 1;
            // @Filename: /p/main.ts
            import { Comp } from "./comp";
            export const q = Comp;
            """,
        )
        assert(r == listOf("/p/main.ts:1:22 TS6142 Module './comp' was resolved to '/p/comp.tsx', but '--jsx' is not set."))
    }

    @Test
    fun `negative control - a resolvable relative import is silent`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            export const a = 1;
            // @Filename: /p/main.ts
            import { a } from "./a";
            export const b = a;
            """,
        )
        assert(r.isEmpty())
    }
}
