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
import kotlin.test.Test

/**
 * (CHK.196) stage 1: hover over a binding annotated `typeof A` names the CONSTRUCTOR side the
 * way tsgo 7.0.2's LSP does (`scripts/lsp_hover.py`, `build/bench/p18253-agent/hv`):
 * `const t: typeof A`, and `const u: typeof Box` for a generic class — not the instance type
 * and not a construct-signature literal. `typeToString` is shared by diagnostics and
 * [Project.quickInfoAt], so the display rule reaches both.
 */
class ProjectTypeofClassHoverTest {

    private val config =
        """{ "compilerOptions": { "strict": true, "target": "es2022", "module": "esnext", "moduleResolution": "bundler", "noEmit": true, "lib": ["es2022"], "types": [] } }"""

    private val main =
        "class A { static s = 1 }\ndeclare const t: typeof A;\nclass Box<T> { v!: T }\ndeclare const u: typeof Box;\nexport {}\n"

    private fun hoverOf(local: String): String? = Project.open(
        "/proj",
        InMemoryVfs(mapOf("/proj/tsconfig.json" to config, "/proj/a.ts" to main)),
    ).quickInfoAt("/proj/a.ts", main.indexOf("const $local") + 6)?.displayString

    @Test
    fun `hover over a typeof A binding names typeof A`() {
        val t = hoverOf("t")
        assert(t == "typeof A")
    }

    @Test
    fun `hover over a typeof binding of a generic class names typeof Box`() {
        val u = hoverOf("u")
        assert(u == "typeof Box")
    }
}
