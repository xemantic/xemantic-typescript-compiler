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
 * (REL.2)(C) round 764: the enum arm of the call-argument narrowability gate is a
 * SECOND CHANCE — the flow walk is paid for only on the REJECTING path.
 *
 * Round 763 opened that gate unconditionally, which is what made a bare enum narrow
 * at an argument position at all, and it cost 3,406 flow walks (`narrow.walks`
 * 71,323 -> 74,729). In tsc's own sources an enum-typed argument overwhelmingly goes
 * to a parameter of that same enum, where the DECLARED type already satisfies the
 * parameter and no narrowing can change any verdict — so the walk is skipped there,
 * exactly as `overloadNarrowedArgType`'s round-743 sibling does for `asserts`.
 *
 * **These pins are all CONTROLS: this change has no observable behaviour, by
 * construction.** A second chance can only turn a rejection into an acceptance, and
 * the skipped branch is the one where the raw type was already accepted — so there is
 * no shape that fails on the unmodified build and passes here. The measurements that
 * DO discriminate are `narrow.walks` and the 8-arm grid; what this file protects is
 * the two ways the change could go wrong instead:
 *
 * 1. the skip manufacturing a diagnostic where the raw enum was fine (the first five);
 * 2. the skip swallowing the REJECTING path the round-763 arm exists for, in either
 *    of its two argument shapes, or leaking out of the enum sub-case into the
 *    corpus-pinned Interface arm beside it (the last three).
 *
 * The probe discipline is round 762/763's: neither probe target is satisfied by an
 * enum-shaped type, so the narrowed type is always NAMED in the TS2345 message and
 * silence can never be mistaken for narrowing.
 *
 * (PARITY.2): the two ENUM walk-path pins use `probeX`, a `never` parameter, because
 * tsc's `reportRelationError` generalizes an enum-member source to its parent enum at a
 * `string` parameter — so they would read `K` for every narrow and go BLIND. The
 * INTERFACE control keeps the `string` probe: the round-441 `never`-parameter arm
 * discards a narrowed result that is not `never` outside its enum exception, so at a
 * `never` target it would read the declared `Node0` however the subtype test behaved
 * (measured: tsc reads `Ident0` there, we read `Node0`).
 */
class EnumArgumentSecondChanceTest {

    private val prelude = """
        enum K { A, B, C, D }
        declare function isAB(k: K): k is K.A | K.B;
        declare function takeK(k: K): void;
        declare function probe(x: string): void;
        declare function probeX(x: never): void;

    """.trimIndent()

    /** The SKIP path: the parameter already accepts the declared enum, so no walk. */
    private fun silent(body: String) {
        diagnose(prelude + body) should { have(none { it.code == 2345 }) }
    }

    /** The WALK path: the parameter rejects everything, so the narrowed type is named. */
    private fun narrowedTo(body: String, expected: String) {
        diagnose(prelude + body) should {
            have(any { it.code == 2345 && it.message.contains("Argument of type '$expected'") })
        }
    }

    // ---- the skip path: an already-satisfied parameter stays silent everywhere ----

    @Test
    fun `an already-satisfied enum argument is silent in a ternary`() {
        silent("export function f(k: K) { return isAB(k) ? takeK(k) : 0; }")
    }

    @Test
    fun `an already-satisfied enum argument is silent as an amp-amp right operand`() {
        silent("export function f(k: K) { return isAB(k) && takeK(k); }")
    }

    @Test
    fun `an already-satisfied enum argument is silent after an early-return guard`() {
        silent("export function f(k: K) { if (!isAB(k)) { return; } takeK(k); }")
    }

    @Test
    fun `an already-satisfied enum argument is silent in a switch case`() {
        silent("export function f(k: K) { switch (k) { case K.A: takeK(k); break; } }")
    }

    @Test
    fun `an already-satisfied enum argument is silent in an if block`() {
        silent("export function f(k: K) { if (isAB(k)) { takeK(k); } }")
    }

    @Test
    fun `an unguarded already-satisfied enum argument is silent`() {
        silent("export function f(k: K) { takeK(k); }")
    }

    // ---- the walk path: the second chance must not disable the round-763 arm ----

    @Test
    fun `a rejecting parameter still names the narrowed type for an identifier argument`() {
        narrowedTo("export function f(k: K) { return isAB(k) ? probeX(k) : 0; }", "K.A | K.B")
    }

    @Test
    fun `a rejecting parameter still names the narrowed type for a property-access argument`() {
        // The round-763 arm admits a PropertyAccessExpression as well as an Identifier;
        // round 763's own pins only covered the Identifier shape.
        narrowedTo(
            """
            interface Holder { k: K }
            export function g(h: Holder) { return isAB(h.k) ? probeX(h.k) : 0; }
            """.trimIndent(),
            "K.A | K.B",
        )
    }

    @Test
    fun `negative control - a non-enum interface argument still narrows on the rejecting path`() {
        // The corpus-pinned Interface/unknown/string/number arms beside the enum one
        // must keep walking UNCONDITIONALLY — this fails any widening of the skip
        // beyond the enum sub-case that also drops their `n <: ctxApplied` leg.
        narrowedTo(
            """
            interface Node0 { pos: number }
            interface Ident0 extends Node0 { text: string }
            declare function isIdent(n: Node0): n is Ident0;
            export function g(n: Node0) { return isIdent(n) ? probe(n) : 0; }
            """.trimIndent(),
            "Ident0",
        )
    }
}
