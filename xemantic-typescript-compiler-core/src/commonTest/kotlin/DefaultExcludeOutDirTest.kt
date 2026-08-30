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
 * (CFG.1) A tsconfig that specifies no `exclude` still excludes what it EMITS INTO.
 *
 * tsc's rule (`commandLineParser.ts`): when `exclude` is absent,
 * `excludeSpecs = filter([outDir, declarationDir], d => !!d)`. We had only the other
 * half — the three package folders — so any project that had ever run a
 * declaration-emitting build read its own output back in: everything under the output directory matches the default
 * everything-include and a `.d.ts` is a root extension. Measured against tsgo 7.0.2 on a
 * two-file project, its program was **1 file and ours was 2**.
 *
 * **Both costs are real and the first is a correctness one.** A stale declaration
 * beside its source is a duplicate declaration tsc does not report; and every file of
 * the emitted tree is then crawled, read, parsed, bound and checked ON EVERY KEYSTROKE,
 * which is the incremental FLOOR an editor pays.
 *
 * **Why no gate in this repo could see it.** The generated corpus hands the compiler
 * sources directly and materialises no directory at all, so it has no glob to get wrong;
 * and the eight dashboard profiles all restrict `include` to a `src` subtree, under
 * `dist` never matched in the first place — they are a CONTROL for this change, not
 * coverage of it. Only a `-project` fixture through [ProjectCompiler] and a [Vfs] can
 * express it.
 */
class DefaultExcludeOutDirTest {

    /** `src/a.ts` plus the `dist/` a previous `tsc --declaration` would have left. */
    private fun projectWith(configJson: String) = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to configJson,
            "/proj/src/a.ts" to "export interface Point { x: number }\nexport const o: Point = { x: 0 };\n",
            "/proj/dist/a.d.ts" to "export interface Point { x: number }\nexport declare const o: Point;\n",
            "/proj/dist/a.js" to "export const o = { x: 0 };\n",
        ),
    )

    @Test
    fun `an emitted declaration is not a root file`() {
        val result = ProjectCompiler(
            projectWith("""{ "compilerOptions": { "strict": true, "outDir": "dist", "declaration": true } }"""),
        ).build("/proj", noEmit = true)

        // tsgo 7.0.2 answers exactly this one file for the same project.
        assert(result.rootFiles.size == 1)
        assert(result.rootFiles.single().endsWith("/src/a.ts"))
        assert(result.programFiles.none { it.startsWith("/proj/dist/") })
    }

    @Test
    fun `declarationDir is excluded as well as outDir`() {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to
                    """{ "compilerOptions": { "strict": true, "outDir": "js", "declarationDir": "types", "declaration": true } }""",
                "/proj/src/a.ts" to "export const a = 1;\n",
                "/proj/types/a.d.ts" to "export declare const a: number;\n",
                "/proj/js/a.js" to "export const a = 1;\n",
            ),
        )
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        assert(result.rootFiles.size == 1)
        assert(result.programFiles.none { it.startsWith("/proj/types/") })
    }

    @Test
    fun `a stale declaration no longer duplicates its own source`() {
        // The VALUE half: a global-script declaration in the output tree collides with
        // the one in the source tree. A count pin alone cannot say that the file being
        // dropped is the one that was doing harm.
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to
                    """{ "compilerOptions": { "strict": true, "outDir": "dist", "declaration": true } }""",
                "/proj/src/g.ts" to "declare const VERSION: string;\n",
                "/proj/dist/g.d.ts" to "declare const VERSION: string;\n",
            ),
        )
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        assert(result.diagnostics.none { it.code == 2451 })
        assert(result.diagnostics.none { it.code == 2300 })
    }

    @Test
    fun `an explicit exclude REPLACES the default, as in tsc`() {
        // tsc's default is not additive: naming any `exclude` drops the outDir rule.
        // Pinned because it is the direction a "just add outDir to the defaults"
        // implementation gets wrong, and because it is what makes this fixture's
        // control below meaningful.
        val result = ProjectCompiler(
            projectWith(
                """{ "compilerOptions": { "strict": true, "outDir": "dist", "declaration": true }, "exclude": ["nothing-at-all"] }""",
            ),
        ).build("/proj", noEmit = true)

        assert(result.rootFiles.size == 2)
        assert(result.rootFiles.any { it.startsWith("/proj/dist/") })
    }

    @Test
    fun `a project with no outDir is unchanged`() {
        // Control: the change may not disturb a project that emits beside its sources.
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to """{ "compilerOptions": { "strict": true } }""",
                "/proj/src/a.ts" to "export const a = 1;\n",
                "/proj/src/b.ts" to "export const b = 2;\n",
            ),
        )
        assert(ProjectCompiler(vfs).build("/proj", noEmit = true).rootFiles.size == 2)
    }

    @Test
    fun `node_modules is still excluded`() {
        // Control: the package folders survive the new rule.
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to
                    """{ "compilerOptions": { "strict": true, "outDir": "dist" } }""",
                "/proj/src/a.ts" to "export const a = 1;\n",
                "/proj/node_modules/pkg/index.ts" to "export const p = 1;\n",
            ),
        )
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        assert(result.rootFiles.size == 1)
        assert(result.rootFiles.single().endsWith("/src/a.ts"))
    }
}
