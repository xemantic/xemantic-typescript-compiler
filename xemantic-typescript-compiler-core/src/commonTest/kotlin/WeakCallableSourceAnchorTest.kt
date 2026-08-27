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
 * (CHK.59) THE WEAK-TYPE ANCHOR MOVES TO THE **EXPRESSION** EXACTLY WHEN THE CODE IS
 * **TS2560**, AT ALL THREE NON-ARGUMENT POSITIONS.
 *
 * (CHK.58) closed the TS2559/TS2560 split but left a CALLABLE source refused outright
 * at the var-decl / return / assignment positions, because its anchor is not the one a
 * non-callable source takes. The rule, read out of tsc 7.0.2 over
 * `build/chk59/ora/w7.ts`, `w8.ts` and `w9.ts` and corroborated by tsgo's own
 * `relater.go`:
 *
 *  * `checkTypeRelatedToAndOptionallyElaborate` calls `elaborateError` FIRST, whose
 *    first act is `elaborateDidYouMeanToCallOrConstruct` — construct signatures then
 *    call signatures. When some signature's return type is related to the target it
 *    RE-REPORTS with the ERROR NODE set to the EXPRESSION and attaches the TS6213 /
 *    TS6212 related row; otherwise the ordinary `checkTypeRelatedToEx` runs with the
 *    position's own error node.
 *  * That is the SAME predicate [Checker.weakCallResultSatisfiesTarget] already uses to
 *    choose between TS2560 and TS2559 — so in this compiler the two coincide by
 *    construction: **TS2560 ⇒ the expression's span; TS2559 ⇒ the var NAME / the
 *    `return` keyword / the LHS reference**, unchanged.
 *
 * Measured, over three positions x three source shapes (`w8.ts`):
 *
 * | source | tsc code | tsc var decl | tsc return | tsc assignment |
 * |---|---|---|---|---|
 * | `() => { zzzT: number }` (related) | TS2560 | 3:22 EXPR | 4:34 EXPR | 6:9 EXPR |
 * | `() => { zzzZ: string }` (disjoint) | TS2559 | 3:7 NAME | 4:27 `return` | 6:1 LHS |
 * | `() => number` | TS2559 | 8:7 NAME | 9:27 `return` | 11:1 LHS |
 * | `new (s: string) => { zzzT: number }` | TS2560 | 13:22 EXPR | 14:34 EXPR | 16:9 EXPR |
 *
 * **[Diagnostic.character] IS THE CLI'S 1-BASED COLUMN VERBATIM** — re-deriving it as
 * 0-based reddened nine pins in (CHK.57) and three in (CHK.56).
 *
 * A **FUNCTION EXPRESSION** source is DELIBERATELY still refused and that is a measured
 * conservatism, not an oversight: tsc's `getErrorSpanForNode` maps a `FunctionExpression`
 * to its own NAME, so `= function zzzNamed() {…}` anchors at `zzzNamed` (col 31 of
 * `build/chk59/ora/wa.ts` line 3) while the anonymous form lands back on the var name
 * (col 7) — two anchors neither of which is the expression. An ARROW has no such
 * mapping (`getErrorSpanForArrowFunction` starts at the arrow's first token), which is
 * why the arrow half IS closed here.
 */
class WeakCallableSourceAnchorTest {

    /**
     * THE HEADLINE — a callable identifier whose call result IS related to the weak
     * target. tsc 7.0.2 over `w7.ts`: TS2560 at `(3,22)`, `(4,34)` and `(6,9)`, i.e.
     * the EXPRESSION in all three positions. All three were SILENT before.
     */
    @Test
    fun `a callable source whose result satisfies the target anchors at the expression`() {
        val d = diagnose("""
            interface ZzzS7 { zzzT?: number; zzzE?(): void }
            declare const zzzG7: () => { zzzT: number };
            const zzzW7: ZzzS7 = zzzG7;
            function zzzH7(): ZzzS7 { return zzzG7; }
            let zzzV7: ZzzS7 = {};
            zzzV7 = zzzG7;
        """)
        assert(d.map { it.code } == listOf(2560, 2560, 2560))
        assert(d.all {
            it.message == "Value of type '() => { zzzT: number; }' has no properties " +
                "in common with type 'ZzzS7'. Did you mean to call it?"
        })
        assert(d.map { it.line } == listOf(3, 4, 6))
        assert(d.map { it.character } == listOf(22, 34, 9))
        assert(d.all { it.relatedInformation.map { r -> r.code } == listOf(6212) })
    }

    /**
     * THE COMPLEMENT — a callable whose call result is DISJOINT from the target keeps
     * the non-callable anchors. tsc 7.0.2 over `w8.ts`: TS2559 at `(3,7)` the var NAME,
     * `(4,27)` the `return` KEYWORD and `(6,1)` the LHS. This is the pin that makes the
     * anchor rule a RULE rather than "callable sources move".
     */
    @Test
    fun `a callable source whose result is disjoint keeps the non-callable anchor`() {
        val d = diagnose("""
            interface ZzzS8 { zzzT?: number; zzzE?(): void }
            declare const zzzA8: () => { zzzZ: string };
            const zzzW8: ZzzS8 = zzzA8;
            function zzzH8(): ZzzS8 { return zzzA8; }
            let zzzV8: ZzzS8 = {};
            zzzV8 = zzzA8;
        """)
        assert(d.map { it.code } == listOf(2559, 2559, 2559))
        assert(d.all {
            it.message ==
                "Type '() => { zzzZ: string; }' has no properties in common with type 'ZzzS8'."
        })
        assert(d.map { it.line } == listOf(3, 4, 6))
        assert(d.map { it.character } == listOf(7, 27, 1))
        assert(d.all { it.relatedInformation.isEmpty() })
    }

    /**
     * A PRIMITIVE call result — the same TS2559 anchors. tsc 7.0.2 over `w8.ts` lines
     * 7-11: `(8,7)`, `(9,27)`, `(11,1)`.
     */
    @Test
    fun `a callable source returning a primitive keeps the non-callable anchor`() {
        val d = diagnose("""
            interface ZzzS8 { zzzT?: number; zzzE?(): void }
            declare const zzzB8: () => number;
            const zzzW9: ZzzS8 = zzzB8;
            function zzzH9(): ZzzS8 { return zzzB8; }
            let zzzV9: ZzzS8 = {};
            zzzV9 = zzzB8;
        """)
        assert(d.map { it.code } == listOf(2559, 2559, 2559))
        assert(d.all {
            it.message == "Type '() => number' has no properties in common with type 'ZzzS8'."
        })
        assert(d.map { it.line } == listOf(3, 4, 6))
        assert(d.map { it.character } == listOf(7, 27, 1))
    }

    /**
     * A CONSTRUCT-signature source. tsc 7.0.2 over `w8.ts` lines 12-16: TS2560 at
     * `(13,22)`, `(14,34)`, `(16,9)` — the expression — and note the MAIN message still
     * says *Did you mean to call it?* while only the RELATED row switches to TS6213.
     */
    @Test
    fun `a construct signature source anchors at the expression with the new related row`() {
        val d = diagnose("""
            interface ZzzS8 { zzzT?: number; zzzE?(): void }
            declare const zzzC8: new (s: string) => { zzzT: number };
            const zzzWA: ZzzS8 = zzzC8;
            function zzzHA(): ZzzS8 { return zzzC8; }
            let zzzVA: ZzzS8 = {};
            zzzVA = zzzC8;
        """)
        assert(d.map { it.code } == listOf(2560, 2560, 2560))
        assert(d.all {
            it.message == "Value of type 'new (s: string) => { zzzT: number; }' has no " +
                "properties in common with type 'ZzzS8'. Did you mean to call it?"
        })
        assert(d.map { it.line } == listOf(3, 4, 6))
        assert(d.map { it.character } == listOf(22, 34, 9))
        assert(d.all { it.relatedInformation.map { r -> r.code } == listOf(6213) })
    }

    /**
     * AN ARROW-FUNCTION source — the shape the queue warned about
     * (`this.handler = () => {…}` against a weak member is exactly this, and tsc
     * reports it). tsc 7.0.2 over `w9.ts`: `(2,22)`, `(3,34)`, `(5,9)`, all TS2560 at
     * the arrow's first token.
     */
    @Test
    fun `an arrow function source anchors at the arrow`() {
        val d = diagnose("""
            interface ZzzS9 { zzzT?: number; zzzE?(): void }
            const zzzWB: ZzzS9 = () => ({ zzzT: 1 });
            function zzzHB(): ZzzS9 { return () => ({ zzzT: 1 }); }
            let zzzVB: ZzzS9 = {};
            zzzVB = () => ({ zzzT: 1 });
        """)
        assert(d.map { it.code } == listOf(2560, 2560, 2560))
        assert(d.map { it.line } == listOf(2, 3, 5))
        assert(d.map { it.character } == listOf(22, 34, 9))
    }

    /**
     * AN ARROW whose result is disjoint takes the NAME anchor, not the arrow's.
     * tsc 7.0.2 over `w9.ts` line 6: `(6,7)`, TS2559 — so the arrow half obeys the same
     * rule as the identifier half and nothing about "the source is an arrow" moves it.
     */
    @Test
    fun `an arrow function whose result is disjoint keeps the var name anchor`() {
        val d = diagnose("""
            interface ZzzS9 { zzzT?: number; zzzE?(): void }
            const zzzWC: ZzzS9 = () => 3;
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message == "Type '() => number' has no properties in common with type 'ZzzS9'.")
        assert(d[0].line == 2)
        assert(d[0].character == 7)
    }

    /**
     * THE MEMBER-ASSIGNMENT SHAPE the queue named as the FP risk — an arrow assigned to
     * a weak MEMBER, through a property access rather than a plain identifier. tsc
     * 7.0.2 over `build/chk59/dbg/d1.ts` line 5: `(5,21)`, the arrow. tsc reports it,
     * and so do we.
     */
    @Test
    fun `an arrow assigned to a weak member reports at the arrow`() {
        val d = diagnose("""
            interface ZzzS9 { zzzT?: number; zzzE?(): void }
            declare const zzzObj: { zzzHandler?: ZzzS9 };
            zzzObj.zzzHandler = () => ({ zzzT: 1 });
        """)
        assert(d.map { it.code } == listOf(2560))
        assert(d[0].line == 3)
        assert(d[0].character == 21)
    }

    /**
     * REFUSAL, AND A **PRE-EXISTING** ONE THIS ROUND ONLY MEASURED — a `this.<member>`
     * assignment target is silent for the weak rule at every source shape, callable and
     * not. tsc 7.0.2 over `build/chk59/dbg/d1.ts` reports `(2,62)` for the arrow AND
     * `(3,44)` for a plain `number` source, so this is not the anchor change: the cause
     * is one layer down, in [Checker.weakAssignmentTarget]'s property-access leg, where
     * [Checker.getTypeOfExpression] answers `any` for `this.<optional member>` — the
     * same probe reads `const zzzProbe: string = this.zzzHandler` as silent, where tsc
     * says `Type 'ZzzS9 | undefined' is not assignable to type 'string'`.
     * **THIS IS A REFUSAL PIN RECORDING A DIVERGENCE, NOT COVERAGE.**
     */
    @Test
    fun `refusal - a this member assignment target stays silent`() {
        val d = diagnose("""
            interface ZzzS9 { zzzT?: number; zzzE?(): void }
            class ZzzK9 { zzzHandler?: ZzzS9; zzzM() { this.zzzHandler = () => ({ zzzT: 1 }); } }
            class ZzzK8 { zzzHandler?: ZzzS9; zzzM() { this.zzzHandler = 12 as unknown as number; } }
        """)
        assert(d.none { it.code == 2559 || it.code == 2560 })
    }

    /**
     * REFUSAL — a FUNCTION EXPRESSION source stays silent at all three positions. tsc
     * 7.0.2 DOES report (`build/chk59/ora/wa.ts` lines 2-3: TS2560 at `(2,7)` for the
     * anonymous form and `(3,31)` for the named one), but neither anchor is the
     * expression: `getErrorSpanForNode` maps a `FunctionExpression` to its own NAME.
     * Recording the divergence rather than shipping a row at the wrong span.
     * **THIS IS A REFUSAL PIN, NOT COVERAGE.**
     */
    @Test
    fun `refusal - a function expression source stays silent`() {
        val d = diagnose("""
            interface ZzzSA { zzzT?: number; zzzE?(): void }
            const zzzWD: ZzzSA = function () { return { zzzT: 1 }; };
            const zzzWE: ZzzSA = function zzzNamed() { return { zzzT: 1 }; };
        """)
        assert(d.none { it.code == 2559 || it.code == 2560 })
    }

    /**
     * CONTROL — a callable source that DOES share a property name with the weak target
     * is silent in all three positions, on tsc and here alike. The property is what
     * cancels the rule, so this is the guard against the anchor change turning into a
     * blanket "callable sources report".
     */
    @Test
    fun `negative control - a callable source sharing a property is silent`() {
        val d = diagnose("""
            interface ZzzSB { zzzT?: number; zzzE?(): void }
            declare const zzzGB: { zzzT: number; (): number };
            const zzzWG: ZzzSB = zzzGB;
            function zzzHG(): ZzzSB { return zzzGB; }
            let zzzVG: ZzzSB = {};
            zzzVG = zzzGB;
        """)
        assert(d.isEmpty())
    }
}
