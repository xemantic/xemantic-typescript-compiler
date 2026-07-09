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
import kotlin.test.assertTrue

/**
 * Round 458: `xtsc foo.ts` — a bare SOURCE-file argument — must be compiled as a
 * single-file program with default options (like `tsc foo.ts`), NOT loaded as a
 * tsconfig. Parsing a `.ts` as JSON yields a garbage config, and downstream a
 * corrupt lib binderResult (its `sourceFile.text` no longer matching its statement
 * positions) that crashed the checker with a StringIndexOutOfBounds on ANY input
 * (even `const x = 1;`). [ProjectCompiler.build] now detects a non-`.json` file
 * argument and builds it directly.
 */
class SingleFileBuildTest {

    @Test
    fun `a bare source file compiles cleanly (no crash, no spurious diagnostics)`() {
        val vfs = InMemoryVfs(mapOf("/work/a.ts" to "const x: number = 1;\nexport const y = x + 1;\n"))
        val result = ProjectCompiler(vfs).build("/work/a.ts", noEmit = true)
        assertEquals(
            emptyList(), result.diagnostics,
            "a bare source file must compile without diagnostics (and must not crash)",
        )
        assertTrue("/work/a.ts" in result.programFiles, "the file itself is the single root")
    }

    @Test
    fun `a bare source file with a real type error reports it`() {
        val vfs = InMemoryVfs(mapOf("/work/bad.ts" to "const n: number = \"not a number\";\n"))
        val result = ProjectCompiler(vfs).build("/work/bad.ts", noEmit = true)
        assertTrue(
            result.diagnostics.any { it.code == 2322 },
            "a bare source file is still type-checked — TS2322 must fire; got ${result.diagnostics.map { it.code }}",
        )
    }

    @Test
    fun `a bare source file follows its relative imports`() {
        val vfs = InMemoryVfs(
            mapOf(
                "/work/main.ts" to "import { add } from \"./math.js\";\nexport const r: number = add(1, 2);\n",
                "/work/math.ts" to "export function add(a: number, b: number): number { return a + b; }\n",
            ),
        )
        val result = ProjectCompiler(vfs).build("/work/main.ts", noEmit = true)
        assertEquals(emptyList(), result.diagnostics, "the imported module resolves and type-checks")
        assertTrue("/work/math.ts" in result.programFiles, "the relative import is walked into the program")
    }
}
