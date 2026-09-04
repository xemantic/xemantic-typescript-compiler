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
 * (PARITY.2): the probe is a `never` parameter, not the PRIMITIVE `string` it was
 * until the enum arm of `Checker.baseTypeOfLiteralType` landed. tsc's
 * `reportRelationError` generalizes an enum-member source to its parent enum at any
 * target that cannot hold a singleton, so at a `string` parameter `K.B | K.C | K.D` and
 * an un-narrowed `K` render the SAME string and every pin here would go BLIND rather
 * than red. `never` is a target tsc suppresses the generalization for, so the survivors
 * are named in full — and every expectation below is byte-identical to tsgo 7.0.2 AND
 * pristine `typescript@6.0.3`, measured. The one exception, kept on `string`
 * DELIBERATELY, is the interface control: the round-441 `never`-parameter arm DISCARDS
 * any narrowed result that is not `never` (outside its enum exception), so at a `never`
 * target that pin would read the declared `Node0` whatever the subtype test did.
 *
 * Probe discipline is round 762/763's: neither target is satisfied by an
 * enum-shaped type, so the narrowed type is always NAMED in the TS2345 message.
 * Note that the corresponding EXHAUSTIVE shape (a guard covering every member, whose
 * false branch is `never`) is deliberately unpinned — `never` is assignable to
 * `string`, so it is silent, and silence cannot tell it from a narrow that never ran.
 *
 * Round 765 RETIRED this class's switch-`default:` control (see the note at the foot of
 * the class). It had pinned the one shape round 764 left open, and round 765 closed it;
 * the shape now lives in [EnumAssertAndSwitchDefaultNarrowingTest] with the correct
 * expectation. **The lesson, which cost a suite failure to learn: pinning a KNOWN-OPEN
 * gap as a control plants a landmine for whichever round closes it** — such a control
 * asserts behaviour we intend to change, so it is a countdown, not a guard. Prefer
 * recording an open gap in the session note; if it really must be pinned, the closing
 * round has to be told where to look, which is what this paragraph now does.
 */
class EnumNegativeNarrowingTest {

    private val prelude = """
        enum K { A, B, C, D }
        enum J { X = 100, Y = 200 }
        type U = K.A | K.B | K.C;
        declare function probe(x: string): void;
        declare function probeX(x: never): void;
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
            "export function f(k: K) { if (k !== K.A) { probeX(k); } }",
            "K.B | K.C | K.D",
        )
    }

    @Test
    fun `the else branch of a triple-equals subtracts the tested member`() {
        narrowedTo(
            "export function f(k: K) { if (k === K.A) { } else { probeX(k); } }",
            "K.B | K.C | K.D",
        )
    }

    @Test
    fun `a type guard's false branch subtracts the guard target from a bare enum`() {
        narrowedTo(
            "export function f(k: K) { if (!isAB(k)) { probeX(k); } }",
            "K.C | K.D",
        )
    }

    @Test
    fun `a type guard's false branch subtracts in a ternary`() {
        narrowedTo("export function f(k: K) { return isAB(k) ? 0 : probeX(k); }", "K.C | K.D")
    }

    @Test
    fun `the subtraction keeps DECLARATION order - not the order of the removed member`() {
        // Removing a MIDDLE member: the survivors must still print in declaration
        // order, which an id-ordered or a remove-then-append result would not.
        // Deliberately NOT `k !== K.D`, whose survivors are exactly `U` — the interned
        // union carries that alias's display name, so the pin would read `U` and
        // measure the alias table instead of the order.
        narrowedTo(
            "export function f(k: K) { if (k !== K.B) { probeX(k); } }",
            "K.A | K.C | K.D",
        )
    }

    // ---- negative controls ----

    @Test
    fun `negative control - a member of an UNRELATED enum subtracts nothing`() {
        // Fails any version that keys on the EnumLiteral flag without the round-746
        // owner rule: `J.X` is not in `K`'s domain, so the answer stays the whole enum.
        narrowedTo("export function f(k: K) { if (k !== J.X) { probeX(k); } }", "K")
    }

    @Test
    fun `negative control - an interface-target guard's false branch is untouched`() {
        // The round-760 `!isModifier(node)` shape: `t` is not an enum, so the new
        // subtraction must decline and leave the existing subtype test in charge.
        // (PARITY.2): DELIBERATELY still the `string` probe — the round-441
        // `never`-parameter arm discards every narrowed result that is not `never`
        // outside its enum exception, so a `never` target here would read the declared
        // `Node0` however the subtype test behaved (measured: tsc reads `Ident0` at
        // `never`, we read `Node0`). Unaffected by the generalization either way: a
        // non-literal source is never generalized.
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
        narrowedTo("export function f(k: U) { if (k !== K.A) { probeX(k); } }", "K.B | K.C")
    }

    @Test
    fun `negative control - the positive direction still answers the tested member`() {
        narrowedTo("export function f(k: K) { if (k === K.A) { probeX(k); } }", "K.A")
    }

    // RETIRED round 765: `negative control - a switch default clause still answers the
    // whole enum`. It pinned the shape round 764 had NOT closed, and round 765 closed it
    // — the same source now answers `K.B | K.C | K.D`, so the control was asserting
    // pre-fix behaviour. The shape is now owned, in its correct form, by
    // [EnumAssertAndSwitchDefaultNarrowingTest]: `a switch default on a bare enum
    // subtracts the cased member` is this exact source with the inverted expectation,
    // beside a middle-member order pin and a fallthrough-group pin. Nothing is lost by
    // the retirement, and keeping both would have left two pins on one shape disagreeing.
}
