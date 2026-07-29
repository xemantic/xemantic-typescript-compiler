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
 * (REL.2) round 764: the NEGATIVE direction on a bare enum — `k !== K.A`, the else
 * branch of `k === K.A`, and a type guard's FALSE branch — must SUBTRACT from the
 * enum's member domain instead of answering the whole enum.
 *
 * Round 763 closed the positive direction, which needs no decomposition (the narrowed
 * type IS the tested member) and left this one, because it is the half that changes
 * type DISPLAY: `K.B | K.C | K.D` where the answer used to be `K`. tsc models a
 * literal enum AS the union of its members, so this is ordinary union filtering there;
 * we mint one member-LESS `Type.Object` for the whole enum, so a subtractive narrow
 * had nothing to subtract FROM. [Checker.enumMemberTypesOf] supplies the domain in
 * DECLARATION order — which is what makes the display `K.B | K.C | K.D` and not an
 * id-ordered permutation — and [Checker.enumMinusMembers] does the subtraction, gated
 * to a target that is entirely members of THIS enum.
 *
 * Probe discipline is round 762/763's: a `string` target is satisfied by no
 * enum-shaped type, so the narrowed type is always NAMED in the TS2345 message.
 * Note that the corresponding EXHAUSTIVE shape (a guard covering every member, whose
 * false branch is `never`) is deliberately unpinned — `never` is assignable to
 * `string`, so it is silent, and silence cannot tell it from a narrow that never ran.
 */
class EnumNegativeNarrowingTest {

    private val prelude = """
        enum K { A, B, C, D }
        enum J { X = 100, Y = 200 }
        type U = K.A | K.B | K.C;
        declare function probe(x: string): void;
        declare function isAB(k: K): k is K.A | K.B;

    """.trimIndent()

    private fun narrowedTo(body: String, expected: String) {
        diagnose(prelude + body) should {
            have(any { it.code == 2345 && it.message.contains("Argument of type '$expected'") })
        }
    }

    // ---- targets: each answers the whole enum `K` on the unmodified build ----

    @Test
    fun `not-equals subtracts the tested member from a bare enum`() {
        narrowedTo(
            "export function f(k: K) { if (k !== K.A) { probe(k); } }",
            "K.B | K.C | K.D",
        )
    }

    @Test
    fun `the else branch of a triple-equals subtracts the tested member`() {
        narrowedTo(
            "export function f(k: K) { if (k === K.A) { } else { probe(k); } }",
            "K.B | K.C | K.D",
        )
    }

    @Test
    fun `a type guard's false branch subtracts the guard target from a bare enum`() {
        narrowedTo(
            "export function f(k: K) { if (!isAB(k)) { probe(k); } }",
            "K.C | K.D",
        )
    }

    @Test
    fun `a type guard's false branch subtracts in a ternary`() {
        narrowedTo("export function f(k: K) { return isAB(k) ? 0 : probe(k); }", "K.C | K.D")
    }

    @Test
    fun `the subtraction keeps DECLARATION order, not the order of the removed member`() {
        // Removing a MIDDLE member: the survivors must still print in declaration
        // order, which an id-ordered or a remove-then-append result would not.
        // Deliberately NOT `k !== K.D`, whose survivors are exactly `U` — the interned
        // union carries that alias's display name, so the pin would read `U` and
        // measure the alias table instead of the order.
        narrowedTo(
            "export function f(k: K) { if (k !== K.B) { probe(k); } }",
            "K.A | K.C | K.D",
        )
    }

    // ---- negative controls ----

    @Test
    fun `negative control - a member of an UNRELATED enum subtracts nothing`() {
        // Fails any version that keys on the EnumLiteral flag without the round-746
        // owner rule: `J.X` is not in `K`'s domain, so the answer stays the whole enum.
        narrowedTo("export function f(k: K) { if (k !== J.X) { probe(k); } }", "K")
    }

    @Test
    fun `negative control - an interface-target guard's false branch is untouched`() {
        // The round-760 `!isModifier(node)` shape: `t` is not an enum, so the new
        // subtraction must decline and leave the existing subtype test in charge.
        narrowedTo(
            """
            interface Node0 { pos: number }
            interface Ident0 extends Node0 { text: string }
            declare function isIdent(n: Node0): n is Ident0;
            export function g(n: Node0) { if (!isIdent(n)) { probe(n); } }
            """.trimIndent(),
            "Node0",
        )
    }

    @Test
    fun `negative control - a reference already declared as a member union is unchanged`() {
        // The pre-existing union path (round 763) must not be re-routed through the
        // decomposition: `U` is already `K.A | K.B | K.C`, so `D` never appears.
        narrowedTo("export function f(k: U) { if (k !== K.A) { probe(k); } }", "K.B | K.C")
    }

    @Test
    fun `negative control - the positive direction still answers the tested member`() {
        narrowedTo("export function f(k: K) { if (k === K.A) { probe(k); } }", "K.A")
    }

    @Test
    fun `negative control - a switch default clause still answers the whole enum`() {
        // NOT a target: the default clause returns before any narrowing is attempted
        // (`narrowBySwitchClause` bails on a `DefaultClause` in the range), and that
        // path is not enum-specific. Pinned so the gap is recorded as measured rather
        // than overlooked — this is the one shape of the negative direction round 764
        // did not close.
        narrowedTo(
            "export function f(k: K) { switch (k) { case K.A: break; default: probe(k); } }",
            "K",
        )
    }
}
