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
 * INV.4(d) walker 10 (round 539): the always-truthy/falsy condition pass
 * checkAlwaysTruthy (TS2872/TS2873 + the TS1345 void-call condition, TS2845
 * enum-member truthiness, and the `!`-operand falsy check) migrated onto the
 * check spine — the recursion walkers (checkAlwaysTruthyInStatements /
 * -InStatement / -InExpr) are deleted; reach is a memoized classifier, the
 * B69.11 inArrowExprBody flag and the if-else-chain prevTruthy state
 * pull-derive from the ancestor chain, and the per-node anchors dispatch at
 * If/While/Do/For/ternary/binary/prefix-not enters.
 *
 * All pins verified against the OLD (slot-moved) walker first. The sharpest
 * bug-compat pins: if/while/do and TERNARY conditions are checked only by
 * the whole-expression predicates (their SUB-expressions are never walked),
 * while FOR conditions are fully walked; the B69.11 numeric-|| suppression
 * applies anywhere below an arrow EXPRESSION body edge (no reset at nested
 * function-body boundaries — the flag survives into a nested fn-expr's
 * block).
 */
class Inv4SpineBatch30Test {

    // ── core emissions ──────────────────────────────────────────────────────

    @Test
    fun `always-truthy or-left fires TS2872 in expression positions`() {
        val d = diagnose("""
            declare const y: boolean;
            const a = ({}) || y;
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2872 }, "expected 1 TS2872, got: $d")
    }

    @Test
    fun `numeric-literal or-left fires TS2872 except inside arrow expression bodies`() {
        val d1 = diagnose("""
            declare const y: number;
            const a = 1 || y;
        """)
        kotlin.test.assertEquals(1, d1.count { it.code == 2872 }, "top level: expected 1 TS2872, got: $d1")
        val d2 = diagnose("""
            declare const y: number;
            const f = () => 1 || y;
        """)
        kotlin.test.assertEquals(0, d2.count { it.code == 2872 }, "arrow expr body: expected 0 TS2872, got: $d2")
    }

    @Test
    fun `the arrow-expr-body suppression survives into a nested function block`() {
        // The legacy inArrowExprBody flag is set at the arrow expression body
        // edge and never reset at nested function boundaries.
        diagnose("""
            declare const y: number;
            const f = () => (function () { return 1 || y; })();
        """) should {
            have(none { it.code == 2872 })
        }
    }

    @Test
    fun `always-falsy if condition fires TS2873`() {
        val d = diagnose("""
            declare const x: number;
            if (void x) { }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2873 }, "expected 1 TS2873, got: $d")
    }

    @Test
    fun `logical-not falsy operand fires TS2873`() {
        val d = diagnose("""
            const b = !(void 0);
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2873 }, "expected 1 TS2873, got: $d")
    }

    @Test
    fun `void-returning call condition fires TS1345`() {
        val d = diagnose("""
            function v(): void { }
            if (v()) { }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 1345 }, "expected 1 TS1345, got: $d")
    }

    @Test
    fun `falsy enum member reference in a ternary condition fires TS2845`() {
        val d = diagnose("""
            enum E { Zero = 0, One = 1 }
            declare const a: number;
            declare const b: number;
            const r = E.Zero ? a : b;
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2845 }, "expected 1 TS2845, got: $d")
    }

    @Test
    fun `always-falsy ternary condition fires TS2873`() {
        val d = diagnose("""
            declare const a: number;
            declare const b: number;
            const r = (void 0) ? a : b;
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2873 }, "expected 1 TS2873, got: $d")
    }

    // ── condition sub-expression reach quirks ───────────────────────────────

    @Test
    fun `negative control - if and ternary condition SUB-expressions are not walked`() {
        diagnose("""
            declare const x: boolean;
            declare const a: number;
            declare const b: number;
            if (({}) || x) { }
            const r = (({}) || x) ? a : b;
        """) should {
            have(none { it.code == 2872 })
        }
    }

    @Test
    fun `for conditions ARE fully walked`() {
        val d = diagnose("""
            declare const x: boolean;
            for (; ({}) || x;) { break; }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2872 }, "expected 1 TS2872, got: $d")
    }

    @Test
    fun `negative control - while and do condition sub-expressions are not walked`() {
        diagnose("""
            declare const x: boolean;
            while (({}) || x) { break; }
            do { break; } while (({}) || x);
        """) should {
            have(none { it.code == 2872 })
        }
    }

    // ── nested-context reach ────────────────────────────────────────────────

    @Test
    fun `anchors fire in every reached nested context`() {
        val d = diagnose("""
            declare const y: number;
            function f() { const a = 1 || y; }
            class K {
                m() { const a = 1 || y; }
                p = 1 || y;
            }
            const ce = class { m() { const a = 1 || y; } };
            const o = { m() { const a = 1 || y; } };
            const fe = function () { const a = 1 || y; };
            namespace NS { const a = 1 || y; }
            const t = `x${'$'}{1 || y}`;
        """)
        kotlin.test.assertEquals(8, d.count { it.code == 2872 }, "expected 8 TS2872, got: ${d.filter { it.code == 2872 }}")
    }

    @Test
    fun `inner or-left checks fire on every binary in a left-spine chain`() {
        val d = diagnose("""
            declare const y: number;
            declare const z: number;
            const a = (1 || y) || (2 || z);
        """)
        // 1||y left (numeric), the whole (1||y) as left of the outer || is NOT
        // always-truthy-syntactic... expected: the two numeric-literal lefts.
        kotlin.test.assertEquals(2, d.count { it.code == 2872 }, "expected 2 TS2872, got: ${d.filter { it.code == 2872 }}")
    }
}
