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
 * (CHK.39) A hover on a CONTEXTUALLY-TYPED parameter — the user-visible half of
 * the item, and the one no diagnostic pin can see.
 *
 * Until (CHK.39) contextual typing here supplied an ARITY and nothing else:
 * `spineIanyFnExprEnter` decided TS7006 from the contextual signature's
 * parameter COUNT, so a covered parameter went quiet — and then stayed `any` to
 * every reader of a type, including [Project.quickInfoAt]. So a hover on the
 * parameter of a callback said `any` for EVERY codebase.
 *
 * The three expectations are READ OUT of tsc 7.0.2's own language server
 * (`tools/tsgo-7.0.2/lib/tsc --lsp -stdio`, via `scripts/lsp_hover.py`), never
 * hand-written — round 924's rule, and it answers `(parameter) x: N` for all
 * three carets here.
 *
 * The caret is on the USE of the parameter rather than on its declaration: a
 * declaration is a name the checker types from its own annotation, and there is
 * none, so a use is the only position that can distinguish `N` from `any`.
 */
class ProjectContextualParamHoverTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val fileName = "/proj/src/a.ts"

    private val source = """
        export interface N { kind: number }
        export interface V { m(node: N): void }
        declare function take(f: (n: N) => void): void;
        take((cbArg) => { const useIt = cbArg; });
        const objm: V = { m(methArg) { const useM = methArg; } };
        const propa: { m: (n: N) => void } = { m: (paArg) => { const useP = paArg; } };
    """.trimIndent() + "\n"

    private fun project(): Project = Project.open(
        "/proj",
        InMemoryVfs(mapOf("/proj/tsconfig.json" to config, fileName to source)),
    )

    /** The offset of the `n`-th occurrence (0-based) of [needle]. */
    private fun offsetOf(needle: String, occurrence: Int = 0): Int {
        var at = -1
        repeat(occurrence + 1) { at = source.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at
    }

    @Test
    fun `a hover on a CALL-ARGUMENT arrow's parameter answers the contextual type`() {
        val info = project().quickInfoAt(fileName, offsetOf("cbArg", 1) + 1)
        assert(info != null)
        assert(info.displayString == "N")
    }

    @Test
    fun `a hover on an OBJECT-LITERAL METHOD's parameter answers the contextual type`() {
        val info = project().quickInfoAt(fileName, offsetOf("methArg", 1) + 1)
        assert(info != null)
        assert(info.displayString == "N")
    }

    @Test
    fun `a hover on a PROPERTY-ASSIGNMENT arrow's parameter answers the contextual type`() {
        val info = project().quickInfoAt(fileName, offsetOf("paArg", 1) + 1)
        assert(info != null)
        assert(info.displayString == "N")
    }

    /**
     * NEGATIVE CONTROL. A parameter with NO contextual type at all must still
     * answer `any` — the pull writes only where the position supplies a concrete
     * type, so a fix that simply typed every parameter as something would pass
     * the three above and fail here.
     */
    @Test
    fun `negative control - a parameter with no contextual type still answers any`() {
        val text = source + "export function free(loose) { const useLoose = loose; }\n"
        val p = Project.open(
            "/proj",
            InMemoryVfs(mapOf("/proj/tsconfig.json" to config, fileName to text)),
        )
        val at = text.indexOf("loose", text.indexOf("useLoose"))
        val info = p.quickInfoAt(fileName, at + 1)
        assert(info != null)
        assert(info.displayString == "any")
    }
}
