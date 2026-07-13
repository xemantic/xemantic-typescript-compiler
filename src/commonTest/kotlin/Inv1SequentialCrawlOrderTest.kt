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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * INV.1(a) (round 491): the import-graph crawl became a cold Flow collected
 * through the [runCompilerPipeline] seam. It must stay BEHAVIOR-IDENTICAL to
 * the imperative loop it replaced — in particular the program-file ORDER
 * (seeds in seed order, then BFS discovery order) is load-bearing: it becomes
 * the binder's file order, which fixes global symbol-id allocation (the
 * documented ~350-test reshuffle on drift). These pin that order on a diamond
 * import graph (dedup: `shared` reached from both `a` and `b` appears ONCE, at
 * its FIRST discovery position), the (importer, specifier) attribution of
 * unresolved imports, and run-to-run determinism — the invariant any future
 * concurrent INV.1(b) crawl must also satisfy (deterministic emission order,
 * never completion order).
 */
class Inv1SequentialCrawlOrderTest {

    private fun diamondProject() = InMemoryVfs(
        mapOf(
            "/p/tsconfig.json" to """{ "files": ["src/index.ts"] }""",
            "/p/src/index.ts" to """
                import "./a";
                import "./b";
                import "totally-missing-pkg";
                export const i = 1;
            """.trimIndent(),
            "/p/src/a.ts" to """import "./shared"; export const a = 1;""",
            "/p/src/b.ts" to """
                import "./shared";
                import "./c";
                export const b = 1;
            """.trimIndent(),
            "/p/src/shared.ts" to "export const s = 1;",
            "/p/src/c.ts" to "export const c = 1;",
        )
    )

    @Test
    fun `program files preserve seed-then-BFS discovery order with first-discovery dedup`() {
        val result = ProjectCompiler(diamondProject()).build("/p", noEmit = true)
        assertEquals(
            listOf(
                "/p/src/index.ts", // the sole seed (tsconfig `files`)
                "/p/src/a.ts",     // index's imports, specifier order
                "/p/src/b.ts",
                "/p/src/shared.ts", // a's import — first discovery wins the position
                "/p/src/c.ts",      // b's import (its `./shared` dedups away)
            ),
            result.programFiles,
        )
    }

    @Test
    fun `unresolved bare specifiers are attributed to their importer`() {
        val result = ProjectCompiler(diamondProject()).build("/p", noEmit = true)
        assertEquals(listOf("/p/src/index.ts" to "totally-missing-pkg"), result.unresolved)
    }

    @Test
    fun `two identical builds are byte-deterministic - the INV1b invariant`() {
        val r1 = ProjectCompiler(diamondProject()).build("/p", noEmit = true)
        val r2 = ProjectCompiler(diamondProject()).build("/p", noEmit = true)
        assertEquals(r1.programFiles, r2.programFiles)
        assertEquals(r1.diagnostics, r2.diagnostics)
        assertEquals(r1.unresolved, r2.unresolved)
    }
}
