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
 * (CHK.14) round 947 — **the span of an `abstract new (…) => T` node starts at the
 * `abstract`, not at the `new`.**
 *
 * The parser consumes the `abstract` modifier before the ordinary constructor-type
 * production runs, so the production's own `getPos()` would report the `new` and the
 * node would silently exclude the keyword that makes it abstract. **Nothing in the
 * CORE can see that**: no diagnostic in this compiler is positioned from a
 * `ConstructorType`'s `pos` (the TS1386 union rule spans from the union MEMBER start to
 * `prevTokenEnd`), so the four-arm core ablation reads the mistake as RED 0. This
 * module is where a node span is an answer rather than an implementation detail —
 * round 910's whole subject — and this pin is what makes the bound measurable.
 */
class AbstractConstructorTypeSpanTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val source = "class K { x = 1 }\ntype W = abstract new (x: number) => K;\n"

    private fun project(): Project = Project.open(
        "/proj",
        InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                "/proj/src/a.ts" to source,
            ),
        ),
    )

    @Test
    fun `the abstract keyword is inside the constructor type node`() {
        val p = project()
        val abstractAt = source.indexOf("abstract")
        val info = p.nodeInfoAt("/proj/src/a.ts", abstractAt + 1)
        assert(info != null)
        val chain = listOf(info.kind) + info.ancestorKinds
        assert("ConstructorType" in chain)
    }

    @Test
    fun `the constructor type node starts at the abstract keyword`() {
        val p = project()
        val abstractAt = source.indexOf("abstract")
        val newAt = source.indexOf("new ")
        val info = p.nodeInfoAt("/proj/src/a.ts", abstractAt + 1)
        assert(info != null)
        // Whichever node the narrowest lookup lands on, the enclosing constructor type
        // must not begin at the `new` — that is exactly the dropped-span mistake.
        assert(info.start <= abstractAt)
        assert(info.start < newAt)
    }

    @Test
    fun `regression guard - a plain constructor type still starts at its new keyword`() {
        val plain = "class K { x = 1 }\ntype V = new (x: number) => K;\n"
        val p = Project.open(
            "/proj",
            InMemoryVfs(
                mapOf(
                    "/proj/tsconfig.json" to config,
                    "/proj/src/a.ts" to plain,
                ),
            ),
        )
        val newAt = plain.indexOf("new ")
        val info = p.nodeInfoAt("/proj/src/a.ts", newAt + 1)
        assert(info != null)
        val chain = listOf(info.kind) + info.ancestorKinds
        assert("ConstructorType" in chain)
    }
}
