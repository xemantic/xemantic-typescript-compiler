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
 * (INV.0) step 6b — the [MemberNames] seam's own pins (ledger row 9).
 *
 * The move is VERBATIM and "nothing changed" is pinned by the corpus. What only a
 * test at this level can state is the family's own contract, and it is an AGREEMENT
 * contract rather than a value one: a member's name is asked at TWO places — where
 * the member table is REGISTERED and where a read of it is RESOLVED — and this class
 * is now the single source of truth for both. When those two drifted, round 935
 * measured one compile emitting a CORRECT `TS2322` beside a FALSE `TS2339` for the
 * same member, and (CHK.40)(c) measured a string-named METHOD registered correctly
 * and typed `any`. Both failures are of the shape a value assertion cannot see: the
 * answer that IS produced is right, and a second answer is wrong.
 *
 * So every pin below reads a member back through a deliberately WRONG target type.
 * The `TS2322` it reports names the type the read resolved to — which proves the
 * resolution side — and the ABSENCE of `TS2339` beside it proves the registration
 * side agreed. A pin asserting only that "it compiles" would pass on a drifted
 * binary, because a drifted binary still reports the `TS2322`.
 *
 * ABLATION RESULT, recorded rather than claimed — see the round note.
 */
class MemberNamesTest {

    /**
     * Round 935's own shape. A computed key whose expression is a file-level `const`
     * is LATE-BOUND: the member is registered under `p`, so a read of `.p` must
     * resolve to `number` and nothing may report that `p` does not exist.
     */
    @Test
    fun `a computed key from a const string is registered and resolved under the same name`() {
        val d = diagnose(
            """
            const K = "p";
            const obj = { [K]: 1 };
            const probe: string = obj.p;
            """.trimIndent(),
        )
        assert(d.any { it.code == 2322 && "'number'" in it.message })
        assert(d.none { it.code == 2339 })
    }

    /** The same for an ENUM MEMBER key, which resolves through a different arm. */
    @Test
    fun `a computed key from an enum member is registered and resolved under the same name`() {
        val d = diagnose(
            """
            enum E { A = "ea" }
            const obj = { [E.A]: 1 };
            const probe: string = obj.ea;
            """.trimIndent(),
        )
        assert(d.any { it.code == 2322 && "'number'" in it.message })
        assert(d.none { it.code == 2339 })
    }

    /**
     * (CHK.40)(c): a STRING-named method. The property form of the same member was
     * byte-correct while the method form typed `any`, which is why the pin is written
     * on the method form and reads its RETURN type back.
     */
    @Test
    fun `a string-named method is registered and typed under the same name`() {
        val d = diagnose(
            """
            interface I { "m-x"(n: number): void }
            declare const i: I;
            const probe: string = i["m-x"](1);
            """.trimIndent(),
        )
        assert(d.any { it.code == 2322 && "'void'" in it.message })
        assert(d.none { it.code == 2339 })
    }

    /**
     * The hop limit is the one file-private top-level declaration this extraction had
     * to widen to `internal` (`LATE_BIND_ALIAS_HOPS`, 8), and it is shared across the
     * new boundary — four sites here, one left in `Checker`. These two pins are a
     * PAIR and only the pair is evidence: a chain SHORT enough late-binds (so the
     * member exists and a wrong-target read reports `TS2322`), and a chain LONGER
     * than the limit does NOT (so the object type is `{}` and the read reports
     * `TS2339`). Raising or deleting the limit turns the second into the first.
     */
    @Test
    fun `a const alias chain within the hop limit still late-binds`() {
        val d = diagnose(
            """
            const A1 = "sh"; const A2 = A1; const A3 = A2;
            const obj = { [A3]: 1 };
            const probe: string = obj.sh;
            """.trimIndent(),
        )
        assert(d.any { it.code == 2322 && "'number'" in it.message })
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `a const alias chain past the hop limit does not late-bind`() {
        val d = diagnose(
            """
            const B1 = "dp"; const B2 = B1; const B3 = B2; const B4 = B3;
            const B5 = B4; const B6 = B5; const B7 = B6; const B8 = B7;
            const B9 = B8; const B10 = B9; const B11 = B10; const B12 = B11;
            const obj = { [B12]: 1 };
            const probe: string = obj.dp;
            """.trimIndent(),
        )
        assert(d.any { it.code == 2339 })
        assert(d.none { it.code == 2322 })
    }
}
