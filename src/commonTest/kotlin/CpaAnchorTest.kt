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
 * (cpa-m3a) round 581: the first checkPropertyAccess EMISSION moves —
 * Var/Expr/Return statements' expression walks run from the spine anchor
 * while the legacy arms truncate their duplicates. The emit-twice contract:
 * every diagnostic appears EXACTLY ONCE (0 = lost to truncation without an
 * anchor; 2 = anchored without the skip). Statements inside arrow/fn-expr/
 * class-expression bodies are emitted VIA the containing statement's anchor
 * walk (never separately anchored — the double-emit hazard).
 */
class CpaAnchorTest {

    private fun count2339(source: String): Int =
        diagnose(source).count { it.code == 2339 }

    @Test
    fun `top-level expression statement emits exactly once`() {
        val n = count2339("""
            interface O { a: number }
            declare const o: O;
            o.missing;
        """)
        assert(n == 1)
    }

    @Test
    fun `var initializer and return expression emit exactly once`() {
        val nVar = count2339("""
            interface O { a: number }
            declare const o: O;
            const x = o.missing;
        """)
        assert(nVar == 1)
        val nRet = count2339("""
            interface O { a: number }
            declare const o: O;
            function f() { return o.missing; }
        """)
        assert(nRet == 1)
    }

    @Test
    fun `namespace body and nested-position statements emit exactly once`() {
        val nNs = count2339("""
            interface O { a: number }
            declare const o: O;
            namespace N { export const y = o.missing; }
        """)
        assert(nNs == 1)
        val nIf = count2339("""
            interface O { a: number }
            declare const o: O;
            declare const cond: boolean;
            function g() {
                if (cond) { o.missing; }
            }
        """)
        assert(nIf == 1)
        val nSwitch = count2339("""
            interface O { a: number }
            declare const o: O;
            declare const k: number;
            function h() {
                switch (k) { case 1: o.missing; break; }
            }
        """)
        assert(nSwitch == 1)
    }

    @Test
    fun `arrow and fn-expr body statements emit exactly once via the containing anchor`() {
        val nArrow = count2339("""
            interface O { a: number }
            declare const o: O;
            const g = () => { return o.missing; };
        """)
        assert(nArrow == 1)
        val nFe = count2339("""
            interface O { a: number }
            declare const o: O;
            const fe = function () { o.missing; };
        """)
        assert(nFe == 1)
    }

    @Test
    fun `class method and property-initializer accesses emit exactly once`() {
        val nMethod = count2339("""
            interface O { a: number }
            declare const o: O;
            class C {
                m() { return o.missing; }
            }
        """)
        assert(nMethod == 1)
        val nThis = count2339("""
            class D {
                m() { return this.missing; }
            }
        """)
        assert(nThis == 1)
    }

    @Test
    fun `condition and subject walks emit exactly once`() {
        // (cpa-m3b): conditions/subjects/incrementors anchor at the
        // EXPRESSION node's leave (probe-safe + state-correct).
        val nIf = count2339("""
            interface O { a: number }
            declare const o: O;
            if (o.missing) {}
        """)
        assert(nIf == 1)
        val nWhile = count2339("""
            interface O { a: number }
            declare const o: O;
            function f() { while (o.missing) {} }
        """)
        assert(nWhile == 1)
        val nFor = count2339("""
            interface O { a: number }
            declare const o: O;
            function g() { for (let i = 0; o.missing; i++) {} }
        """)
        assert(nFor == 1)
        val nSwitch = count2339("""
            interface O { a: number }
            declare const o: O;
            declare const k: number;
            function h() {
                switch (o.missing) { case o.missing: break; }
            }
        """)
        assert(nSwitch == 2)
    }

    @Test
    fun `throw and enum-initializer walks emit exactly once`() {
        val nThrow = count2339("""
            interface O { a: number }
            declare const o: O;
            function f() { throw o.missing; }
        """)
        assert(nThrow == 1)
        val nEnum = count2339("""
            interface O { a: number }
            declare const o: O;
            enum E { x = 1, y = o.missing }
        """)
        assert(nEnum == 1)
    }

    @Test
    fun `arrow body inside an anchored condition emits exactly once`() {
        // The condition anchor's walk owns the arrow body's emissions — the
        // nested statement must never be separately anchored (double-emit).
        val n = count2339("""
            interface O { a: number }
            declare const o: O;
            if ([1].some(x => { return o.missing; })) {}
        """)
        assert(n == 1)
    }

    @Test
    fun `class heritage and property-initializer walks emit exactly once`() {
        // (cpa-m3c): heritage anchors at the heritage expression's leave with
        // the OUTER ect; property initializers at the member's leave with the
        // member-loop ambient (classType + per-member inStatic).
        val nHeritage = count2339("""
            interface O { a: number }
            declare const ns: O;
            class C extends ns.missingBase {}
        """)
        assert(nHeritage == 1)
        val nProp = count2339("""
            interface O { a: number }
            declare const o: O;
            class C {
                p = o.missing;
                static q = o.missing;
            }
        """)
        assert(nProp == 2)
    }

    @Test
    fun `class-expression heritage and members stay owned by the containing anchor`() {
        val n = count2339("""
            interface O { a: number }
            declare const o: O;
            const ce = class {
                p = o.missing;
                m() { return o.missing; }
            };
        """)
        assert(n == 2)
    }

    @Test
    fun `negative control - valid accesses stay silent`() {
        val n = count2339("""
            interface O { a: number }
            declare const o: O;
            o.a;
            const x = o.a;
            const g = () => { return o.a; };
        """)
        assert(n == 0)
    }
}
