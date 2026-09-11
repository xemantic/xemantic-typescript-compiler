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
 * (CHK.119) AN EXPANDO CANDIDATE'S TS2339 — WHICH WRITES DECLARE A MEMBER, AND WHAT
 * THE RECEIVER IS CALLED.
 *
 * The queue item says a `function` used as a member-access receiver **never**
 * reports TS2339 at any nesting site. Measured, that is false: B431
 * (`Checker.spineExEnterNode`) reports for a top-level, uniquely-named
 * `FunctionDeclaration` read from inside a nested function — and **every row it
 * emitted carried a display neither reference produces**, which the item does not
 * mention. Two independent defects, both closed here; the general
 * function-receiver gap the item is really about is NOT, and is left in the queue
 * with the measurements that size it.
 *
 * ### 1. FOUR EXPANDO WRITE FORMS, AND ONLY ONE WAS COLLECTED
 *
 * Measured against `tools/tsgo-7.0.2/lib/tsc` AND pristine `typescript@6.0.3`:
 *
 * | write                | declares | before |
 * |----------------------|----------|--------|
 * | `F.tag = 1`          | yes      | collected |
 * | `F["tag"] = 1`       | yes      | **not collected -> ours-only FP** |
 * | `` F[`tag`] = 1 ``   | yes      | **not collected -> ours-only FP** |
 * | `` `${F.tag = 1}` `` | yes      | **not collected -> ours-only FP** |
 * | `` t`${F.tag = 1}` ``| yes      | **not collected -> ours-only FP** |
 * | `F[0] = 1`           | **no**   | correct |
 * | `F[k] = 1`           | **no**   | correct |
 *
 * The `ElementAccessExpression` LHS was simply not recognised as an assignment
 * target, and a `TemplateExpression` was not descended into **at all**. The last
 * two rows are NEGATIVE CONTROLS rather than conservatism: we already agreed with
 * both references there, so admitting either would turn an AGREE row into a lost
 * diagnostic.
 *
 * ### 2. THE DISPLAY DEPENDS ON WHETHER THE FUNCTION HAS EXPANDOS AT ALL
 *
 * B431 rendered `typeof $name` unconditionally. Both references name a function
 * with NO expando member by its SIGNATURE — `() => void`, `<T>(zzzX: T) => T` — and
 * one that carries expandos as `typeof $name`.
 *
 * **THE ORDER OF THE TWO FIXES IS LOAD-BEARING.** Before the collector was widened,
 * `F["tag"] = 1` and `` `${F.tag = 1}` `` left `declared` EMPTY for a function that
 * plainly has expandos — so the display rule alone would have renamed exactly those
 * to their signature and turned two AGREE rows into wrong ones. Collector first.
 *
 * ### 3. WHAT THIS DOES NOT CLOSE
 *
 * The item's own headline shape — a function receiver read at FILE level, or a
 * `const f = () => {}`, or a function-typed PARAMETER — is still silent, because
 * B431's candidate set is top-level uniquely-named `FunctionDeclaration`s and its
 * emission requires a nested read. Those pins are here as REFUSALS, named as such,
 * so the residue is a recorded decision rather than an absence someone has to
 * rediscover. Closing them needs expando members modelled on the function TYPE,
 * which is a different piece of work — the item keeps it.
 */
class ExpandoReceiverDisplayTest {

    private fun t2339(d: List<Diagnostic>) = d.filter { it.code == 2339 }

    // --- 1. the four newly-collected write forms ----------------------------

    @Test
    fun `a string-literal element-access write declares the member`() {
        val d = diagnose(
            """
            function ZzzS() {}
            ZzzS["tag"] = 1;
            function zzzG() { ZzzS.tag; }
            """
        )
        assert(t2339(d).isEmpty())
    }

    @Test
    fun `a no-substitution-template element-access write declares the member`() {
        val d = diagnose(
            """
            function ZzzN() {}
            ZzzN[`tag`] = 1;
            function zzzG() { ZzzN.tag; }
            """
        )
        assert(t2339(d).isEmpty())
    }

    @Test
    fun `a template-span write declares the member`() {
        val d = diagnose(
            """
            function ZzzT() {}
            `${'$'}{ZzzT.tag = 1}`;
            function zzzG() { ZzzT.tag; }
            """
        )
        assert(t2339(d).isEmpty())
    }

    @Test
    fun `a TAGGED-template-span write declares the member`() {
        val d = diagnose(
            """
            declare function zzzTag(s: TemplateStringsArray, ...v: unknown[]): string;
            function ZzzTt() {}
            const zzzS = zzzTag`${'$'}{ZzzTt.tag = 1}`;
            function zzzG() { ZzzTt.tag; }
            """
        )
        assert(t2339(d).isEmpty())
    }

    // --- the two NEGATIVE CONTROLS: these must NOT declare -------------------

    /**
     * A NUMERIC index declares nothing — both references report the later read, and
     * we already agreed with them. Admitting it would lose a true diagnostic.
     */
    @Test
    fun `negative control - a numeric element-access write declares nothing`() {
        val d = diagnose(
            """
            function ZzzNum() {}
            ZzzNum[0] = 1;
            function zzzG() { ZzzNum.zzzProbe; }
            """
        )
        assert(t2339(d).size == 1)
        assert(t2339(d).single().message.contains("'zzzProbe'"))
    }

    /** A COMPUTED index is not a statically-known name, and likewise declares nothing. */
    @Test
    fun `negative control - a computed element-access write declares nothing`() {
        val d = diagnose(
            """
            declare const zzzK: string;
            function ZzzComp() {}
            ZzzComp[zzzK] = 1;
            function zzzG() { ZzzComp.tag; }
            """
        )
        assert(t2339(d).any { it.message.contains("'tag'") })
    }

    // --- 2. the display ------------------------------------------------------

    @Test
    fun `a function with NO expando is named by its signature`() {
        val d = diagnose(
            """
            function ZzzA() {}
            function zzzG() { ZzzA.zzzProbe; }
            """
        )
        assert(
            t2339(d).single().message ==
                "Property 'zzzProbe' does not exist on type '() => void'."
        )
    }

    /** The signature is rendered by the type engine, so a GENERIC one comes out right. */
    @Test
    fun `a GENERIC function with no expando is named by its full signature`() {
        val d = diagnose(
            """
            function ZzzG2<T>(zzzX: T): T { return zzzX; }
            function zzzG() { ZzzG2.zzzProbe; }
            """
        )
        assert(
            t2339(d).single().message ==
                "Property 'zzzProbe' does not exist on type '<T>(zzzX: T) => T'."
        )
    }

    @Test
    fun `a function WITH an expando keeps the typeof display`() {
        val d = diagnose(
            """
            function ZzzB() {}
            ZzzB.tag = 1;
            function zzzG() { ZzzB.zzzProbe; }
            """
        )
        assert(
            t2339(d).single().message ==
                "Property 'zzzProbe' does not exist on type 'typeof ZzzB'."
        )
    }

    /**
     * THE PIN THAT MAKES THE ORDER OF THE TWO FIXES VISIBLE. With the display rule
     * but WITHOUT the collector fix, `declared` is empty here and this renders
     * `() => void` — a wrong answer on a row that was previously right.
     */
    @Test
    fun `a function whose only expando came through element access keeps typeof`() {
        val d = diagnose(
            """
            function ZzzC() {}
            ZzzC["tag"] = 1;
            function zzzG() { ZzzC.zzzProbe; }
            """
        )
        assert(
            t2339(d).single().message ==
                "Property 'zzzProbe' does not exist on type 'typeof ZzzC'."
        )
    }

    /**
     * `F.tag ||= 1` is NOT an expando declaration — both references agree — so the
     * function has no expandos and takes the signature display. This separates
     * "has a write" from "has an expando".
     */
    @Test
    fun `a logical-assignment write is not an expando so the signature is used`() {
        val d = diagnose(
            """
            function ZzzD() {}
            ZzzD.tag ||= 1;
            function zzzG() { ZzzD.zzzProbe; }
            """
        )
        assert(
            t2339(d).any {
                it.message == "Property 'zzzProbe' does not exist on type '() => void'."
            }
        )
    }

    // --- 3. the residue, pinned as refusals ---------------------------------

    /**
     * (CHK.124) CLOSED — this pin shipped for one round as
     * `residue - a file-level read of a function receiver is silent` and fired on the
     * round that fixed it, which is the convention working.
     *
     * B431's emission used to require `spineExStatus(node) == EX_NESTED`, so the
     * item's own headline shape — a FILE-LEVEL read, with no nesting at all — was
     * silent where both references report. EX_TOP is now admitted; EX_NONE still is
     * not, which is what keeps the read-walk residues ((CHK.126)) out.
     */
    @Test
    fun `a file-level read of a function receiver reports`() {
        val d = diagnose(
            """
            function ZzzA() {}
            const zzzP = ZzzA.zzzProbe;
            """
        )
        assert(
            t2339(d).single().message ==
                "Property 'zzzProbe' does not exist on type '() => void'."
        )
    }

    /**
     * (CHK.124) A DANGLING DOT MUST STAY SILENT HERE. The parser gives
     * `f<number,string>.` a zero-width synthesized `Identifier("")` and reports
     * TS1003; admitting EX_TOP without an empty-name guard made that grow a
     * `Property '' does not exist on type …` row beside it, which broke the
     * `genericCallWithoutArgs` corpus baseline. This is the pin for that guard.
     */
    @Test
    fun `negative control - a dangling dot draws no member diagnostic`() {
        val d = diagnose("function ZzzD2() {}\nZzzD2.")
        assert(t2339(d).isEmpty())
        assert(d.any { it.code == 1003 })
    }

    /** **residue - an arrow/`const` receiver is not a B431 candidate at all.** */
    @Test
    fun `residue - an arrow-initialized const receiver is silent`() {
        val d = diagnose(
            """
            const ZzzF = () => {};
            function zzzG() { ZzzF.zzzProbe; }
            """
        )
        assert(t2339(d).isEmpty())
    }

    /** **residue - a function-typed PARAMETER receiver is silent.** */
    @Test
    fun `residue - a function-typed parameter receiver is silent`() {
        val d = diagnose(
            "function zzzG(zzzCb: () => void) { zzzCb.zzzProbe; }"
        )
        assert(t2339(d).isEmpty())
    }

    // --- 1b. (CHK.127) an object literal is a HARD STOP for the collector ----

    /**
     * (CHK.127) THE COLLECTOR ALSO OVER-DECLARED, WHICH IS THE OPPOSITE DIRECTION
     * FROM THE FOUR FORMS ABOVE.
     *
     * Measured over twelve positions against tsgo 7.0.2 AND pristine 6.0.3, which
     * agree on every cell. DECLARES: an expression statement, a comma operand, a
     * ternary branch, a chained assignment, an ARRAY-LITERAL element, a CALL
     * argument, a parenthesized expression, a unary operand. DOES NOT: an
     * object-literal property value, a spread, a computed-key property value — and
     * an array literal nested inside any of those, or an object literal nested
     * inside an array. That last pair is why this is a HARD STOP and not a rule
     * about the immediate parent, and it is what the two nesting pins below hold.
     */
    @Test
    fun `an objlit property-value write does NOT declare`() {
        val d = diagnose(
            """
            function ZzzO() {}
            const zzzH = { p: ZzzO.tag = 1 };
            function zzzG() { ZzzO.tag; }
            """
        )
        assert(t2339(d).any { it.message.contains("'tag'") })
    }

    @Test
    fun `a spread write does NOT declare`() {
        val d = diagnose(
            """
            function ZzzS2() {}
            const zzzH = { ...(ZzzS2.tag = 1 as any) };
            function zzzG() { ZzzS2.tag; }
            """
        )
        assert(t2339(d).any { it.message.contains("'tag'") })
    }

    /** An ARRAY element DOES declare — the control that the stop is not blanket. */
    @Test
    fun `negative control - an array-literal element write DOES declare`() {
        val d = diagnose(
            """
            function ZzzA2() {}
            const zzzH = [ZzzA2.tag = 1];
            function zzzG() { ZzzA2.tag; }
            """
        )
        assert(t2339(d).none { it.message.contains("'tag'") })
    }

    /** …and so do a CALL argument, a parenthesized expression and a unary operand. */
    @Test
    fun `negative control - call argument parenthesized and unary writes DO declare`() {
        val d = diagnose(
            """
            declare function zzzF(x: unknown): void;
            function ZzzC2() {}
            zzzF(ZzzC2.a = 1);
            const zzzP = (ZzzC2.b = 2);
            const zzzU = !(ZzzC2.c = 3);
            function zzzG() { ZzzC2.a; ZzzC2.b; ZzzC2.c; }
            """
        )
        assert(t2339(d).isEmpty())
    }

    /**
     * THE PAIR THAT MAKES IT A HARD STOP: an array literal INSIDE an objlit value
     * does not declare, and an objlit INSIDE an array does not either. A rule
     * written about the immediate parent gets both of these wrong.
     */
    @Test
    fun `the stop is transitive in both nesting directions`() {
        val d = diagnose(
            """
            function ZzzN2() {}
            const zzzH1 = { p: [ZzzN2.inArr = 1] };
            const zzzH2 = [{ p: ZzzN2.inObj = 2 }];
            function zzzG() { ZzzN2.inArr; ZzzN2.inObj; }
            """
        )
        assert(t2339(d).any { it.message.contains("'inArr'") })
        assert(t2339(d).any { it.message.contains("'inObj'") })
    }

    // --- the real Function surface must stay legal ---------------------------

    /**
     * `call`/`apply`/`bind`/`length`/`name`/`prototype` are real members of a
     * function object and are legal in all three compilers — `RUNTIME_PROPERTIES`
     * is what keeps them out, above the display. If a future round widens the
     * receiver arm, this is the pin that notices it firing on them.
     */
    @Test
    fun `the real Function members stay legal on an expando candidate`() {
        val d = diagnose(
            """
            function ZzzR() {}
            function zzzG() { ZzzR.call; ZzzR.apply; ZzzR.bind; ZzzR.length; ZzzR.prototype; }
            """
        )
        assert(t2339(d).isEmpty())
    }
}
