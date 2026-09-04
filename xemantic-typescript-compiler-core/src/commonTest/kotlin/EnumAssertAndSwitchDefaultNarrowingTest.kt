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
 * (REL.2) round 765: the two negative-direction shapes round 764 left open on a BARE
 * ENUM subject — an `asserts k is K.A | K.B` call, and a switch `default:` clause.
 *
 * Both are the same finite-domain question round 764 answered for `k !== K.A`: tsc
 * models a literal enum AS the union of its members, so an assertion SUBSTITUTES the
 * asserted members and a `default:` clause SUBTRACTS the cased ones. Ours is one
 * member-LESS `Type.Object`, so both answered the whole enum.
 *
 * **The switch half is enum-specific, not switch machinery** — which is the correction
 * round 764's note invites: the `default:` path has narrowed a literal-union subject
 * since round 425, and `a literal-union switch default still narrows` pins that it did
 * so BEFORE this change too. The gap was exactly the non-`Type.Union` subject, which is
 * what a bare enum is.
 *
 * **The assert half is an ORDER question.** [Checker]'s assert tail asks
 * `t <: targetType` and returns `t` when it holds — and enum -> MEMBER is still decided
 * VACUOUSLY (the leniency (REL.2) exists to close), so the assertion narrowed nothing.
 * `narrowByCallPredicate`'s positive arm avoids this by asking the other direction
 * first; reordering the general test would change every non-enum assertion, so the enum
 * case gets its own arm behind the round-746 owner rule — which is what
 * `an interface-target assertion still narrows` and `an unrelated enum's member
 * asserts nothing` protect.
 *
 * Probe discipline is round 762/763's, with (PARITY.2)'s correction: the shared probe is
 * a `never` parameter, not the PRIMITIVE `string` it was until the enum arm of
 * `Checker.baseTypeOfLiteralType` landed. Neither target is satisfied by an enum-shaped
 * type, so the narrowed type is always NAMED in the TS2345 message and silence can never
 * be mistaken for narrowing — but tsc's `reportRelationError` generalizes an enum-member
 * source to its parent enum at any target that cannot hold a singleton, so at a `string`
 * parameter `K.A | K.B`, `K.B` and the un-narrowed `K` all render `K` and these pins
 * would go BLIND rather than red. `never` is a target tsc suppresses the generalization
 * for, which also makes every expectation below byte-identical to tsgo 7.0.2 AND pristine
 * `typescript@6.0.3` (measured). The interface control keeps its own `string` probe: a
 * non-literal source is never generalized, and the round-441 `never`-parameter arm would
 * discard its `Dog` narrow. The order pin removes a MIDDLE member — round 764's
 * first attempt removed the LAST one, whose survivors were exactly its prelude's alias,
 * so the interned union displayed the alias name instead of the member order.
 */
class EnumAssertAndSwitchDefaultNarrowingTest {

    private val prelude = """
        enum K { A, B, C, D }
        declare function assertAB(k: K): asserts k is K.A | K.B;
        declare function assertB(k: K): asserts k is K.B;
        declare function isAB(k: K): k is K.A | K.B;
        declare function probe(x: never): void;

    """.trimIndent()

    private fun narrowedTo(body: String, expected: String) {
        diagnose(prelude + body) should {
            have(any { it.code == 2345 && it.message.contains("Argument of type '$expected'") })
        }
    }

    // ---- targets: these all answered the bare enum `K` before this round ----

    @Test
    fun `an asserts call on a bare enum substitutes the asserted members`() {
        narrowedTo("export function f(k: K) { assertAB(k); probe(k); }", "K.A | K.B")
    }

    @Test
    fun `an asserts call to a SINGLE member on a bare enum substitutes that member`() {
        narrowedTo("export function f(k: K) { assertB(k); probe(k); }", "K.B")
    }

    @Test
    fun `a switch default on a bare enum subtracts the cased member`() {
        narrowedTo(
            "export function f(k: K) { switch (k) { case K.A: break; default: probe(k); } }",
            "K.B | K.C | K.D",
        )
    }

    @Test
    fun `a switch default subtracting a MIDDLE member keeps declaration order`() {
        narrowedTo(
            "export function f(k: K) { switch (k) { case K.B: break; default: probe(k); } }",
            "K.A | K.C | K.D",
        )
    }

    @Test
    fun `a switch default subtracts every cased member of a fallthrough group`() {
        narrowedTo(
            "export function f(k: K) { switch (k) { case K.A: case K.B: break; default: probe(k); } }",
            "K.C | K.D",
        )
    }

    // ---- controls: unchanged before and after ----

    @Test
    fun `a literal-union switch default still narrows - the gap is enum-specific`() {
        // Round 425's DefaultClause arm; it has ALWAYS answered this, which is why the
        // switch half of this round is not a change to the switch machinery.
        // (PARITY.1)(b-residue) / (PARITY.2): the shared `probe` is now a `never`
        // parameter — the one target tsc suppresses the source generalization for —
        // which is what keeps a LITERAL UNION named in full here as well.
        narrowedTo(
            """
            type S = "a" | "b" | "c";
            export function f(s: S) { switch (s) { case "a": break; default: probe(s); } }
            """.trimIndent(),
            "\"b\" | \"c\"",
        )
    }

    @Test
    fun `a mixed enum and literal case list keeps the whole enum`() {
        // A literal case cannot be subtracted from an enum's member domain, so the
        // subtraction must bail rather than remove only the enum-keyed cases.
        narrowedTo(
            """
            export function f(k: K) {
              switch (k as K | "z") { case K.A: break; case "z": break; default: probe(k); }
            }
            """.trimIndent(),
            "K",
        )
    }

    @Test
    fun `an unrelated enum's member asserts nothing`() {
        // The round-746 owner rule: `J.X` is not a member of `K`.
        narrowedTo(
            """
            enum J { X, Y }
            declare function assertJX(j: J): asserts j is J.X;
            export function f(k: K, j: J) { assertJX(j); probe(k); }
            """.trimIndent(),
            "K",
        )
    }

    @Test
    fun `an interface-target assertion still narrows`() {
        // The general assert tail must keep its own order — a widening of the new arm
        // out of the enum sub-case fails HERE rather than on a corpus baseline.
        narrowedTo(
            """
            interface Animal { legs: number }
            interface Dog extends Animal { bark(): void }
            declare function assertDog(a: Animal): asserts a is Dog;
            declare function probeS(x: string): void;
            export function f(a: Animal) { assertDog(a); probeS(a); }
            """.trimIndent(),
            "Dog",
        )
    }

    @Test
    fun `a positive type guard on a bare enum still narrows`() {
        // Round 763's arm, untouched by this round.
        narrowedTo("export function f(k: K) { if (isAB(k)) { probe(k); } }", "K.A | K.B")
    }

    // RETIRED round 768: `an exhaustive enum switch default is not subtracted twice` pinned
    // that the argument keeps reporting the declared enum here — the round-462 `never`
    // DISCARD, which round 765 itself recorded as an open gap and (REL.4) closed. Retired
    // rather than edited, per this suite's own rule: the shape is owned in its correct form
    // by `EnumExhaustionToNeverTest > an exhaustive bare enum switch default delivers never
    // to a string parameter`, the same source with the inverted expectation.
}
