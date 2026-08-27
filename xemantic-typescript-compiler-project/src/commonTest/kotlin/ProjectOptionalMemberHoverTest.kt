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
 * (CHK.61)(b) DISPLAY HALF — an OPTIONAL member's hover carries `| undefined`,
 * and a guarded read does not.
 *
 * ## Why this is a hover test and not a diagnostic one
 *
 * The compiler drops an optional property's `| undefined` at the resolution
 * (`computeRawTypeOfPropertyAccess` answers the member's DECLARED type), so before
 * this round `o.p` where `p?: number` hovered `number` — a confident wrong answer,
 * not a missing one. Adding the constituent at the resolution instead is MEASURED
 * and REFUSED for now: it is sound only together with opening
 * `canUseTypeEngine`'s nullish-union-vs-primitive gate, and that pair costs nine
 * net false positives on the eight dashboard profiles. So the fix is confined to
 * the capture, and this is the only channel that can see it.
 *
 * ## The expectations are tsc's, not ours
 *
 * Every value below was read out of **tsc 7.0.2's own language server**
 * (`tools/tsgo-7.0.2/lib/tsc --lsp -stdio`, `scripts/lsp_hover.py`) over a fixture
 * with the same shapes: bare `zzzOpt` -> `number | undefined`, bare `zzzOptStr` ->
 * `string | undefined`, and all three guarded reads -> `number`. tsc renders the
 * declaration (`(property) ZzzShape.zzzOpt?: number | undefined`); we render the
 * type alone, which is this compiler's existing hover shape and not part of what
 * this round changes.
 *
 * ## What makes each pin non-vacuous
 *
 * The two BARE pins fail on the parent binary, which answers `number` / `string`
 * there. The three GUARDED pins are the controls the confinement exists for — the
 * widening is applied and then RE-NARROWED, so they must keep reading `number`;
 * they pass on the parent too and are labelled controls rather than counted as
 * coverage, except that arm `d1` (widen without re-narrowing) reddens exactly
 * them. The REQUIRED pin is a control in the other direction: a required member
 * must be untouched. Two of the guarded shapes use `&&`, deliberately: the legacy
 * if-arm machinery the refused CHECKING half trips over does not handle `&&`,
 * while the flow walk this leg calls does — so they also record which narrowing
 * mechanism the display half is on.
 */
class ProjectOptionalMemberHoverTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "strict": true, "module": "esnext" },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/main.ts"

    private val main = """
        interface ZzzShape { zzzOpt?: number; zzzReq: number; zzzOptStr?: string }
        declare const zzzInst: ZzzShape;
        declare const zzzFlag: boolean;
        declare const zzzUn: { zzzMix?: number } | { zzzMix: string };

        class ZzzBase { zzzBaseOpt?: number }
        class ZzzDer extends ZzzBase {
            zzzD(): void { super.zzzBaseOpt; }
        }

        class ZzzC {
            zzzOptField?: number;
            zzzM(): void {
                this.zzzOptField;
                if (this.zzzOptField) { this.zzzOptField; }
            }
        }

        function zzzUse(): void {
            zzzInst.zzzOpt;
            zzzInst.zzzReq;
            zzzInst.zzzOptStr;
            if (zzzInst.zzzOpt) { zzzInst.zzzOpt; }
            if (zzzInst.zzzOpt !== undefined && zzzFlag) { zzzInst.zzzOpt; }
            if (zzzFlag && zzzInst.zzzOpt) { zzzInst.zzzOpt; }
            zzzUn.zzzMix;
        }
    """.trimIndent() + "\n"

    private fun project(): Project = Project.open(
        "/proj",
        InMemoryVfs(mapOf("/proj/tsconfig.json" to config, mainFile to main)),
    )

    /** The offset of [sub] at or after the first occurrence of [anchor]. */
    private fun caret(anchor: String, sub: String): Int {
        val start = main.indexOf(anchor)
        check(start >= 0) { "anchor absent: $anchor" }
        val at = main.indexOf(sub, start)
        check(at >= 0) { "sub absent: $sub after $anchor" }
        return at
    }

    private fun hover(offset: Int): String? =
        project().quickInfoAt(mainFile, offset)?.displayString

    // --- coverage ------------------------------------------------------------------

    @Test
    fun `an optional member hovers with the undefined constituent`() {
        assert(hover(caret("    zzzInst.zzzOpt;", "zzzOpt;")) == "number | undefined")
    }

    @Test
    fun `a second optional member of another type hovers with undefined too`() {
        assert(hover(caret("zzzInst.zzzOptStr;", "zzzOptStr;")) == "string | undefined")
    }

    @Test
    fun `an optional FIELD read through this hovers with the undefined constituent`() {
        assert(hover(caret("        this.zzzOptField;", "zzzOptField;")) == "number | undefined")
    }

    // --- controls ------------------------------------------------------------------

    @Test
    fun `control - a REQUIRED member is unaffected`() {
        assert(hover(caret("zzzInst.zzzReq;", "zzzReq;")) == "number")
    }

    @Test
    fun `control - a truthiness-guarded optional member narrows back`() {
        assert(hover(caret("if (zzzInst.zzzOpt) { zzzInst.zzzOpt; }", "zzzOpt; }")) == "number")
    }

    @Test
    fun `control - an optional member guarded by the FIRST conjunct narrows back`() {
        assert(hover(caret("if (zzzInst.zzzOpt !== undefined && zzzFlag)", "zzzOpt; }")) == "number")
    }

    @Test
    fun `control - an optional member guarded by the SECOND conjunct narrows back`() {
        assert(hover(caret("if (zzzFlag && zzzInst.zzzOpt)", "zzzOpt; }")) == "number")
    }

    @Test
    fun `a UNION receiver widens when the member is optional on ANY constituent`() {
        // tsc 7.0.2's LSP over the same shape: `(property) zzzMix?: string | number
        // | undefined`. The verdict must NOT be asked of the union itself —
        // `getPropertyOfType`'s union arm answers ONE constituent's symbol (round
        // 916), so it would depend on constituent ORDER; here the optional one is
        // written FIRST and the required one second, and the sibling ordering is
        // covered by the ablation rather than by a second fixture.
        assert(hover(caret("zzzUn.zzzMix;", "zzzMix;")) == "string | number | undefined")
    }

    @Test
    fun `RESIDUE - a super receiver does not widen`() {
        // tsc says `number | undefined`; we say `number`. `super`'s member symbol
        // would have to be resolved off the DERIVED table, where an override's
        // optionality may differ from the base declaration the caret names, so the
        // leg refuses rather than guesses. Recorded with the value we answer so the
        // divergence is visible rather than absent.
        assert(hover(caret("super.zzzBaseOpt;", "zzzBaseOpt;")) == "number")
    }

    @Test
    fun `control - a guarded optional FIELD read through this narrows back`() {
        assert(hover(caret("if (this.zzzOptField) { this.zzzOptField; }", "zzzOptField; }")) == "number")
    }
}
