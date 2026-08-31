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

package com.xemantic.typescript.compiler.project

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.ProjectCompiler
import kotlin.test.Test

/**
 * (INC.78) THE ROOT-FILE SET IS WHAT `include` AND `exclude` SAY IT IS — end to end,
 * through `ProjectCompiler`, over a real `Vfs`.
 *
 * `GlobMatcherTest` is the differential against the pattern's own regex; this is the
 * other half, and it is needed for the reason (CFG.1) records: the instrument for
 * anything deciding ROOT-FILE SELECTION is a `-project` fixture, because the corpus
 * harness materialises no directory at all and every dashboard profile scopes its
 * `include` to a subtree that stresses no wildcard shape. The observable is the
 * program's own file SET — not a diagnostic, since a wrongly-adopted or wrongly-
 * dropped root reports nothing here at all.
 *
 * ## What each fixture file discriminates
 *
 * Every file is import-free, so the program IS the root set and nothing the crawl
 * does can mask a selection defect. The fixture is built so that a matcher taking
 * the head-and-tail shortcut on a pattern whose MIDDLE is constrained is caught:
 * `extra` + a one-segment wildcard accepts `extra/e.ts` and must refuse
 * `extra/deep/f.ts`, which is exactly the path a `startsWith` plus `endsWith` test
 * would wrongly adopt.
 */
class ProjectRootFileGlobTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "noEmit": true },""" +
            """ "include": ["src/**/*.ts", "extra/*.ts"],""" +
            """ "exclude": ["src/**/*.spec.ts", "src/skip"] }"""

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            // Included by the exact head-and-tail shape, at two depths.
            "/proj/src/a.ts" to "export const a = 1;\n",
            "/proj/src/nested/b.ts" to "export const b = 2;\n",
            // Excluded by a wildcard exclude that also takes that shape.
            "/proj/src/nested/c.spec.ts" to "export const c = 3;\n",
            // Excluded by an extension-less exclude, i.e. a whole directory.
            "/proj/src/skip/d.ts" to "export const d = 4;\n",
            // Included by a pattern whose wildcard is INSIDE a segment — the shape that
            // may not take the shortcut.
            "/proj/extra/e.ts" to "export const e = 5;\n",
            // Same head, same tail, one directory deeper: the head-and-tail test accepts
            // it and the pattern does not.
            "/proj/extra/deep/f.ts" to "export const f = 6;\n",
            // Named by no include at all.
            "/proj/other/g.ts" to "export const g = 7;\n",
        ),
    )

    private fun rootsOf(): List<String> =
        ProjectCompiler(vfs()).build("/proj", noEmit = true)
            .programFiles.filter { it.startsWith("/proj/") }.sorted()

    @Test
    fun `the program is exactly the files the patterns select`() {
        assert(
            rootsOf() == listOf(
                "/proj/extra/e.ts",
                "/proj/src/a.ts",
                "/proj/src/nested/b.ts",
            )
        )
    }

    @Test
    fun `a one-segment wildcard does not reach a nested file`() {
        assert("/proj/extra/deep/f.ts" !in rootsOf())
    }

    @Test
    fun `an extensionless exclude drops the whole directory`() {
        assert("/proj/src/skip/d.ts" !in rootsOf())
    }

    @Test
    fun `a wildcard exclude drops only what it names`() {
        val roots = rootsOf()
        assert("/proj/src/nested/c.spec.ts" !in roots)
        assert("/proj/src/nested/b.ts" in roots)
    }
}
