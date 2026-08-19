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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.12) round 942 — `[Symbol.hasInstance]` NARROWING.
 *
 * `x instanceof C` where `C`'s type declares `[Symbol.hasInstance](v): v is T` narrows to
 * `T` in tsc, and the predicate OVERRIDES both `prototype` and every construct signature
 * (`narrowTypeByInstanceof`). Round 838's [instanceTypeOfConstructorValue] named that leg
 * as its one deliberate omission, so a constructor whose instance type is only expressible
 * through the predicate — a GENERIC construct signature, SEVERAL construct signatures, or
 * one returning `any` — narrowed nothing and every member read was TS2339.
 *
 * Measured on pristine `typeGuardsWithInstanceOfBySymbolHasInstance`: 5 ours-only rows
 * -> 0, and one pristine row GAINED (`obj9.bar2` on the narrowed `E1`).
 *
 * Two rules here were read off PRISTINE's own baseline and re-read off `tsgo 7.0.2`, which
 * reproduces that baseline row for row on this fixture:
 *  - a USABLE predicate DECIDES: `v is any` narrows nothing and must NOT fall through to
 *    the construct signature (pristine reports `string | F` at lines 142/143);
 *  - an `instanceof` reaches `getNarrowedType(..., checkDerived = true)` even when the
 *    candidate came from a predicate, so a UNION candidate is distributed and its
 *    narrow-down direction is the NOMINAL base-chain test, not assignability.
 */
class SymbolHasInstanceNarrowingTest {

    @Test
    fun `a hasInstance predicate beats the prototype property`() {
        diagnose(
            """
                interface P1 { p1: number }
                interface P2 { p2: string }
                interface PP {
                    prototype: P1;
                    new (): P1;
                    [Symbol.hasInstance](value: unknown): value is P2;
                }
                declare var Pv: PP;
                declare var a1: P1 | P2;
                if (a1 instanceof Pv) { const n: number = a1.p2; }
            """
        ) should {
            have(any { it.code == 2322 && "'string'" in it.message && "'number'" in it.message })
        }
    }

    @Test
    fun `a hasInstance predicate answers where SEVERAL construct signatures cannot`() {
        diagnose(
            """
                interface A { foo: string }
                interface C1 { foo: string; c: string; bar1: number }
                interface C2 { foo: string; c: string; bar2: number }
                interface CC {
                    new (v: string): C1;
                    new (v: number): C2;
                    [Symbol.hasInstance](value: unknown): value is C1 | C2;
                }
                declare var Cv: CC;
                declare var o: C1 | A;
                if (o instanceof Cv) { o.bar1; }
            """
        ) should { have(none { it.code == 2339 }) }
    }

    @Test
    fun `a structural supertype member is DROPPED rather than mapped onto the whole union candidate`() {
        diagnose(
            """
                interface A { foo: string }
                interface C1 { foo: string; c: string; bar1: number }
                interface C2 { foo: string; c: string; bar2: number }
                interface CC {
                    new (v: string): C1;
                    new (v: number): C2;
                    [Symbol.hasInstance](value: unknown): value is C1 | C2;
                }
                declare var Cv: CC;
                declare var o: C1 | A;
                if (o instanceof Cv) { o.bar2; }
            """
        ) should { have(any { it.code == 2339 && "'C1'" in it.message }) }
    }

    @Test
    fun `a hasInstance predicate answers where a GENERIC construct signature cannot`() {
        diagnose(
            """
                interface B<T> { foo: T }
                interface BC {
                    new <T>(): B<T>;
                    [Symbol.hasInstance](value: unknown): value is B<any>;
                }
                declare var Bv: BC;
                declare var o: B<number> | string;
                if (o instanceof Bv) { const n: string = o.foo; }
            """
        ) should {
            have(any { it.code == 2322 && "'number'" in it.message && "'string'" in it.message })
        }
    }

    @Test
    fun `a base-interface member narrows DOWN to a single predicate target`() {
        diagnose(
            """
                interface B0 { foo: string }
                interface D1 extends B0 { d1: number }
                interface DD { [Symbol.hasInstance](value: unknown): value is D1; }
                declare var Dv: DD;
                declare var p: B0 | string;
                if (p instanceof Dv) { const n: string = p.d1; }
            """
        ) should {
            have(any { it.code == 2322 && "'number'" in it.message && "'string'" in it.message })
        }
    }

    @Test
    fun `a base-interface member narrows DOWN to every constituent of a union predicate target`() {
        diagnose(
            """
                interface B0 { foo: string }
                interface D1 extends B0 { d1: number }
                interface D2 extends B0 { d2: number }
                interface EE { [Symbol.hasInstance](value: unknown): value is D1 | D2; }
                declare var Ev: EE;
                declare var q: B0 | string;
                if (q instanceof Ev) { q.d1; }
            """
        ) should {
            have(any { it.code == 2339 && "'D1 | D2'" in it.message })
        }
    }

    @Test
    fun `bound - an any predicate target narrows nothing and does not fall back`() {
        diagnose(
            """
                interface Q { q: number }
                interface QQ {
                    new (): Q;
                    [Symbol.hasInstance](value: unknown): value is any;
                }
                declare var Qv: QQ;
                declare var a2: Q | string;
                if (a2 instanceof Qv) { a2.q; }
            """
        ) should { have(any { it.code == 2339 }) }
    }

    @Test
    fun `bound - a predicate over a non-first parameter falls back to the construct signature`() {
        diagnose(
            """
                interface R { r: number }
                interface RR {
                    new (): R;
                    [Symbol.hasInstance](self: unknown, value: unknown): value is R;
                }
                declare var Rv: RR;
                declare var a3: R | string;
                if (a3 instanceof Rv) { const n: string = a3.r; }
            """
        ) should {
            have(any { it.code == 2322 && "'number'" in it.message && "'string'" in it.message })
        }
    }

    @Test
    fun `negative control - a constructor value with no hasInstance still narrows through its construct signature`() {
        diagnose(
            """
                interface S { s: number }
                interface SS { new (): S; }
                declare var Sv: SS;
                declare var a4: S | string;
                if (a4 instanceof Sv) { const n: string = a4.s; }
            """
        ) should {
            have(any { it.code == 2322 && "'number'" in it.message && "'string'" in it.message })
        }
    }
}
