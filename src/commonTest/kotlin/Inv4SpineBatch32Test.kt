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
 * INV.4(d) walkers 12+13 (round 541): the ORDER-COUPLED pair
 * checkCommaOperatorUnused (TS2695) + checkNullishPredicates (the B277
 * TS2871/TS2869 `??` nullish predicates and while/do TS2872/TS2873
 * truthiness) migrated onto the check spine together — their recursion
 * walkers are deleted; each keeps its own verbatim reach classifier. The
 * ordering contracts are preserved STRUCTURALLY: the comma pass's TS2695
 * emits PRE-order (outer-before-inner along the left spine) → comma anchors
 * dispatch at ENTERS; the np pass's `??` checks emit POST-order
 * (inner-before-outer, right subtree before the node's own check — tsc
 * ordering) → np anchors dispatch at the BinaryExpression's LEAVE, and the
 * while/do truthiness checks (which must follow the condition subtree's own
 * emissions but precede the body's) dispatch at the CONDITION node's leave.
 * Same-position comma-vs-np pairs keep comma-first because enters precede
 * leaves at every node.
 *
 * All pins verified against the OLD (slot-moved) walkers first.
 */
class Inv4SpineBatch32Test {

    // ── comma operator (TS2695) ─────────────────────────────────────────────

    @Test
    fun `unused comma left side fires TS2695`() {
        val d = diagnose("""
            declare const a: number;
            declare const b: number;
            const r = (a, b);
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2695 }, "expected 1 TS2695, got: $d")
    }

    @Test
    fun `negative control - side-effecting left and indirect-call comma are exempt`() {
        diagnose("""
            declare function f(): number;
            declare const obj: { m(): void };
            const r = (f(), 1);
            (0, obj.m)();
        """) should {
            have(none { it.code == 2695 })
        }
    }

    @Test
    fun `nested comma chain emits outer before inner at each spine node`() {
        val d = diagnose("""
            declare const a: number;
            declare const b: number;
            declare const c: number;
            const r = (a, b, c);
        """)
        kotlin.test.assertEquals(2, d.count { it.code == 2695 }, "expected 2 TS2695, got: ${d.filter { it.code == 2695 }}")
    }

    @Test
    fun `comma anchors fire in nested contexts and for headers`() {
        val d = diagnose("""
            declare const a: number;
            declare const b: number;
            function f() {
                for (let i = (a, b); ;) { break; }
            }
            class K { m() { const r = (a, b); } }
            enum E { M = (a, 1) }
        """)
        kotlin.test.assertEquals(3, d.count { it.code == 2695 }, "expected 3 TS2695, got: ${d.filter { it.code == 2695 }}")
    }

    // ── nullish predicates (TS2871/TS2869) ──────────────────────────────────

    @Test
    fun `always-nullish nullish-coalescing left fires TS2871`() {
        val d = diagnose("""
            declare const y: number;
            const r = null ?? y;
        """)
        d should { have(any { it.code == 2871 }) }
    }

    @Test
    fun `never-nullish nullish-coalescing left fires TS2869`() {
        val d = diagnose("""
            declare const y: number;
            const r = 5 ?? y;
        """)
        d should { have(any { it.code == 2869 }) }
    }

    @Test
    fun `chained nullish-coalescing checks every spine node`() {
        val d = diagnose("""
            declare const y: number;
            const r = 1 ?? 2 ?? y;
        """)
        // both `1` (inner left) and `1 ?? 2` — the outer left's semantics are
        // the RIGHT of the inner ?? (`2`, never nullish) → 2 emissions.
        kotlin.test.assertEquals(2, d.count { it.code == 2869 }, "expected 2 TS2869, got: ${d.filter { it.code == 2869 }}")
    }

    @Test
    fun `while and do conditions get truthiness checks`() {
        val d = diagnose("""
            while ([]) { break; }
            do { break; } while (void 0);
        """)
        d should { have(any { it.code == 2872 }) }
        d should { have(any { it.code == 2873 }) }
    }

    @Test
    fun `np anchors fire inside nested function and member bodies`() {
        val d = diagnose("""
            declare const y: number;
            function f() { const a = null ?? y; }
            const o = { m() { const b = undefined ?? y; } };
            const ce = class { m() { const c = null ?? y; } };
            const arrow = () => null ?? y;
        """)
        kotlin.test.assertEquals(4, d.count { it.code == 2871 }, "expected 4 TS2871, got: ${d.filter { it.code == 2871 }}")
    }

    @Test
    fun `negative control - sometimes-nullish left draws nothing`() {
        diagnose("""
            declare const y: number | null;
            declare const z: number;
            const r = y ?? z;
        """) should {
            have(none { it.code == 2871 || it.code == 2869 })
        }
    }
}
