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
 * (JIT.1)(b) round 804 — the behavioural gate for the ten-way split of
 * `checkMemberAccessMissingCore`.
 *
 * The function was **46,567 bytecodes, 5.8x HotSpot's 8,000-byte
 * `HugeMethodLimit`**, so HotSpot never JIT-compiled it: it ran in the
 * interpreter for the entire process. It is now an entry plus ten `cmam*`
 * helpers, each holding one contiguous run of the original body along round
 * 789's level-R section boundaries.
 *
 * **What `HugeMethodLimitTest` cannot see, and this class pins.** A size check
 * proves the parts are small; it says nothing about whether they still run in
 * the right ORDER, whether a section's early exit still stops the ones below it,
 * or whether a value that used to live in a local still reaches the section that
 * reads it. This function is a PIPELINE of ~24 guarded emission blocks with 99
 * whole-function `return`s: each block's silence is what lets the next one speak,
 * and the display type is decided in one section and printed in another.
 *
 * So there is one pin per section (asserting the exact display it owns, because
 * the display is what distinguishes the sections from each other), plus two SEAM
 * pins:
 *
 * * **the display-type override crosses the receiver-type boundary.** It is
 *   assigned inside the general receiver-type path and read by the emission tail
 *   — the one value that used to cross a section boundary in a local. It is now
 *   RETURNED (as the second half of a pair) rather than stashed in a field, and
 *   `n.length` is the shape that can tell: `length` is a RUNTIME_PROPERTY, so the
 *   tail returns silently unless the override arrived non-null.
 * * **an emitting section still stops the tail.** `this.nope` in a static method
 *   emits and returns from the R_STATIC section; if that signal were dropped the
 *   emission tail would report the same access a second time.
 */
class CmamSplitTest {

    // ---------------------------------------------------------------- sections

    @Test
    fun `R_LITERAL - a string-literal receiver still reports the literal itself`() {
        val d = diagnose(""""foo".missing;""")
        assert(d.any {
            it.code == 2339 && it.message == "Property 'missing' does not exist on type '\"foo\"'."
        })
    }

    @Test
    fun `R_NEW - a new-expression receiver still reports the class`() {
        val d = diagnose(
            """
            class C { m(): void {} }
            new C().nope;
            """,
        )
        assert(d.any {
            it.code == 2339 && it.message == "Property 'nope' does not exist on type 'C'."
        })
    }

    @Test
    fun `R_CALL - a call receiver resolving to a primitive still reports the primitive`() {
        val d = diagnose(
            """
            function fnum(): number { return 1; }
            fnum().bogus;
            """,
        )
        assert(d.any {
            it.code == 2339 && it.message == "Property 'bogus' does not exist on type 'number'."
        })
    }

    @Test
    fun `R_PAEA - a member-access receiver resolving to a primitive still reports the primitive`() {
        val d = diagnose(
            """
            class Q { qq: number = 1; }
            declare const q: Q;
            q.qq.bogus;
            """,
        )
        assert(d.any {
            it.code == 2339 && it.message == "Property 'bogus' does not exist on type 'number'."
        })
    }

    @Test
    fun `R_STATIC - this inside a static method still reports typeof the class`() {
        val d = diagnose(
            """
            class K { static sm(): void { this.nope; } }
            """,
        )
        assert(d.any {
            it.code == 2339 && it.message == "Property 'nope' does not exist on type 'typeof K'."
        })
    }

    @Test
    fun `R_OT_PRE - a cast receiver still reports the asserted interface`() {
        val d = diagnose(
            """
            interface Emp {}
            ((0 as any) as Emp).nope;
            """,
        )
        assert(d.any {
            it.code == 2339 && it.message == "Property 'nope' does not exist on type 'Emp'."
        })
    }

    @Test
    fun `R_OT_UNION - a union receiver still reports the union and names a missing member`() {
        val d = diagnose(
            """
            interface AA1 { p1: string }
            interface BB1 { p2: string }
            declare let u: AA1 | BB1;
            u.zzz;
            """,
        )
        val hit = d.single { it.code == 2339 }
        assert(hit.message == "Property 'zzz' does not exist on type 'AA1 | BB1'.")
        // The chain line is built in the same section and is what distinguishes the
        // union elaboration from every other emission in the function.
        assert(hit.messageChain == listOf("  Property 'zzz' does not exist on type 'AA1'."))
    }

    @Test
    fun `R_OT_NONIDENT - a prototype receiver still reports the instance type`() {
        val d = diagnose(
            """
            class PC { px: number = 1; }
            PC.prototype.nope;
            """,
        )
        assert(d.any {
            it.code == 2339 && it.message == "Property 'nope' does not exist on type 'PC'."
        })
    }

    @Test
    fun `R_OT_IDENT type gates - an instance method read off the class still reports typeof`() {
        val d = diagnose(
            """
            class C { m(): void {} }
            C.m;
            """,
        )
        assert(d.any {
            it.code == 2339 && it.message == "Property 'm' does not exist on type 'typeof C'."
        })
    }

    @Test
    fun `R_OT_IDENT value gates - a mistyped enum member still suggests the real one`() {
        val d = diagnose(
            """
            enum E { AA }
            E.aa;
            """,
        )
        assert(d.any {
            it.code == 2551 &&
                it.message == "Property 'aa' does not exist on type 'typeof E'. Did you mean 'AA'?"
        })
    }

    @Test
    fun `R_OT_IDENT value gates - a missing namespace member still reports typeof the namespace`() {
        val d = diagnose(
            """
            namespace N { export const x = 1; }
            N.y;
            """,
        )
        assert(d.any {
            it.code == 2339 && it.message == "Property 'y' does not exist on type 'typeof N'."
        })
    }

    @Test
    fun `R_EMPTYPROPS - a receiver inferred from an empty literal still reports the empty type`() {
        val d = diagnose(
            """
            const z = {};
            z.x;
            """,
        )
        assert(d.any {
            it.code == 2339 && it.message == "Property 'x' does not exist on type '{}'."
        })
    }

    @Test
    fun `R_POSTGATE - this on a derived class still reports the derived class`() {
        val d = diagnose(
            """
            class Base { bp: number = 1; }
            class D extends Base { m(): void { this.nope; } }
            """,
        )
        assert(d.any {
            it.code == 2339 && it.message == "Property 'nope' does not exist on type 'D'."
        })
    }

    @Test
    fun `R_PROP - a static member read through this still reports TS2576`() {
        val d = diagnose(
            """
            class S { static st: number = 1; m(): void { this.st; } }
            """,
        )
        assert(d.any {
            it.code == 2576 && it.message ==
                "Property 'st' does not exist on type 'S'. " +
                "Did you mean to access the static member 'S.st' instead?"
        })
    }

    @Test
    fun `R_EMIT - the spelling suggestion tail still fires`() {
        val d = diagnose(
            """
            interface Sug { alpha: string }
            declare const sg: Sug;
            sg.alpah;
            """,
        )
        assert(d.any {
            it.code == 2551 &&
                it.message == "Property 'alpah' does not exist on type 'Sug'. Did you mean 'alpha'?"
        })
    }

    // ------------------------------------------------------------------- seams

    /**
     * THE SEAM PIN for the split's one cross-section value. `length` is a
     * RUNTIME_PROPERTY, so the emission tail bails silently unless the
     * display-type override — assigned in the general receiver-type path and
     * returned across the boundary — arrived non-null; and it must ALSO name the
     * primitive rather than its wrapper. `s.length` is the complement: the same
     * machinery, a property that genuinely exists, silence.
     */
    @Test
    fun `SEAM - the display-type override survives the receiver-type boundary`() {
        val onNumber = diagnose(
            """
            declare var n: number;
            n.length;
            """,
        )
        assert(onNumber.any {
            it.code == 2339 && it.message == "Property 'length' does not exist on type 'number'."
        })
        val onString = diagnose(
            """
            declare var s: string;
            s.length;
            """,
        )
        assert(onString.none { it.code == 2339 })
        // The same receiver with a genuinely absent property still names the
        // PRIMITIVE, not the wrapper interface it was resolved through.
        val other = diagnose(
            """
            declare var n: number;
            n.bogus;
            """,
        )
        assert(other.any {
            it.code == 2339 && it.message == "Property 'bogus' does not exist on type 'number'."
        })
    }

    /**
     * THE SEAM PIN for the return signals. A section that emits used to `return`
     * from the whole function; it now returns `true` and the caller must honour
     * it. R_STATIC is the shape where dropping that signal is VISIBLE: the
     * receiver type it would fall through to is the enclosing class, whose member
     * table also lacks the property, so the emission tail reports it a second
     * time.
     */
    @Test
    fun `SEAM - an emitting section still stops the sections below it`() {
        val d = diagnose(
            """
            class K { static sm(): void { this.nope; } }
            """,
        )
        assert(d.count { it.code == 2339 || it.code == 2551 } == 1)
    }

    /**
     * Non-vacuity for the whole class: every fixture above in ONE program, with
     * the exact diagnostic count. A split that duplicated or dropped a section
     * changes this number even where no individual display changed.
     */
    @Test
    fun `every section fires exactly once in one program`() {
        val d = diagnose(
            """
            declare var n: number;
            n.length;
            n.bogus;
            enum E { AA }
            E.aa;
            "foo".missing;
            class C { m(): void {} }
            C.m;
            new C().nope;
            class K { static sm(): void { this.nope; } }
            namespace N { export const x = 1; }
            N.y;
            interface I { a: string }
            declare const i: I;
            i.b;
            const z = {};
            z.x;
            interface AA1 { p1: string }
            interface BB1 { p2: string }
            declare let u: AA1 | BB1;
            u.zzz;
            class Base { bp: number = 1; }
            class D extends Base { m(): void { this.nope; } }
            class PC { px: number = 1; }
            PC.prototype.nope;
            function fnum(): number { return 1; }
            fnum().bogus;
            class Q { qq: number = 1; }
            declare const q: Q;
            q.qq.bogus;
            interface Emp {}
            ((0 as any) as Emp).nope;
            """,
        )
        // Measured, not guessed: the program holds SIXTEEN property-missing
        // fixtures and yields exactly sixteen emissions — fifteen TS2339 plus the
        // enum's TS2551. (A first draft asserted 16 TS2339 by counting the
        // fixtures and forgetting that one of them is the TS2551; the red test is
        // recorded in the round note.)
        assert(d.count { it.code == 2339 } == 15)
        assert(d.count { it.code == 2551 } == 1)
        assert(d.count { it.code == 2339 || it.code == 2551 } == 16)
    }
}
