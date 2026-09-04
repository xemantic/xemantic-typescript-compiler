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
 * (REL.2)(B)+(C), round 763: a reference whose declared type is an ENUM, or a union
 * of enum MEMBERS, must narrow like any other discriminated reference.
 *
 * Two independent defects, landed together because they share
 * [Checker.narrowUnionByLiteral]:
 *
 * **(B)** an enum member is not a literal NODE, so
 * [Checker.literalTypeOfExpression] answered `null` for `K.A` and
 * [Checker.narrowByEquality] bailed — `===` never narrowed against an enum member
 * AT ALL. Measured on every shape, including a reference already declared as the
 * explicit member union `K.A | K.B | K.C`, which is why round 760's label ("`===`
 * narrowing across an `||`") is wrong: the `||` is incidental.
 *
 * **(C)** an enum's own type is a member-LESS `Type.Object`, so it is neither a
 * `Type.Union` nor a `Type.Interface` and fell through the argument site's
 * narrowability gate. The narrowing itself was always right — it was never
 * reached. Hence the split visible in these pins: an `if` BLOCK types its body from
 * `currentLocalTypes` and narrowed correctly all along, while a ternary, an `&&`,
 * an early-return guard and a `switch` all need the flow read the gate refused.
 * (tsc parser.ts:2629 / 3762x2 / 8444 / 8728.) NOT a decomposition of the enum into
 * `K.A | K.B | ...`: [Checker.narrowByCallPredicate]'s single-type arm already
 * answers correctly once it is reached.
 *
 * **The instrument is a `never` probe target** — (PARITY.2); it was a PRIMITIVE
 * (`declare function probe(x: string)`) until the enum arm of
 * `Checker.baseTypeOfLiteralType` landed. Neither target can be satisfied by anything
 * enum-shaped, so the narrowed type is always named in the TS2345 message and "no
 * diagnostic" can never be mistaken for "narrowed"; what changed is that a PRIMITIVE
 * target now takes tsc's `reportRelationError` generalization, which renders `K.A`,
 * `K.A | K.B` and the un-narrowed `K` as one string — so every pin here would have gone
 * BLIND rather than red. `never` is one of the three targets tsc suppresses the
 * generalization for, which additionally makes every expectation below byte-identical to
 * tsgo 7.0.2 AND pristine `typescript@6.0.3` (measured), where `'K.A'` at a `string`
 * parameter was ours-only.
 *
 * A branded-object target reports silence for a member-less enum type (they relate
 * vacuously — the very leniency (REL.2) is about) and a `number` target accepts every
 * numeric enum; round 762 lost a probe to the first and this file nearly lost one to
 * the second.
 */
class EnumFlowNarrowingRel2Test {

    private val prelude = """
        enum K { A, B, C, D }
        enum J { X = 100, Y = 200 }
        type U = K.A | K.B | K.C;
        declare function probe(x: never): void;
        declare function isAB(k: K): k is K.A | K.B;

    """.trimIndent()

    private fun narrowedTo(body: String, expected: String) {
        diagnose(prelude + body) should {
            have(any { it.code == 2345 && it.message.contains("Argument of type '$expected'") })
        }
    }

    // ---- (C): every FLOW-read position on an ENUM-declared reference ----

    @Test
    fun `a type guard narrows an enum-declared reference in a ternary`() {
        narrowedTo("export function f(k: K) { return isAB(k) ? probe(k) : 0; }", "K.A | K.B")
    }

    @Test
    fun `a type guard narrows an enum-declared reference as an amp-amp right operand`() {
        narrowedTo("export function f(k: K) { return isAB(k) && probe(k); }", "K.A | K.B")
    }

    @Test
    fun `a type guard narrows an enum-declared reference after an early-return guard`() {
        narrowedTo(
            "export function f(k: K) { if (!isAB(k)) { return; } probe(k); }",
            "K.A | K.B",
        )
    }

    // ---- (B): equality and switch against an enum MEMBER ----

    @Test
    fun `triple-equals narrows an enum-declared reference to the tested member`() {
        narrowedTo("export function f(k: K) { if (k === K.A) { probe(k); } }", "K.A")
    }

    @Test
    fun `triple-equals across an or-join unions the tested members`() {
        narrowedTo(
            "export function f(k: K) { if (k === K.A || k === K.B) { probe(k); } }",
            "K.A | K.B",
        )
    }

    @Test
    fun `triple-equals narrows a reference already declared as a member union`() {
        narrowedTo("export function f(k: U) { if (k === K.A) { probe(k); } }", "K.A")
    }

    @Test
    fun `not-equals removes the tested member from a member union`() {
        narrowedTo("export function f(k: U) { if (k !== K.A) { probe(k); } }", "K.B | K.C")
    }

    @Test
    fun `a switch case narrows an enum-declared subject to the case member`() {
        narrowedTo(
            "export function f(k: K) { switch (k) { case K.A: probe(k); break; } }",
            "K.A",
        )
    }

    @Test
    fun `a fallthrough switch range narrows to the union of its case members`() {
        narrowedTo(
            "export function f(k: K) { switch (k) { case K.A: case K.B: probe(k); break; } }",
            "K.A | K.B",
        )
    }

    // ---- negative controls: each fails a plausible over-reaching fix ----

    @Test
    fun `negative control - an if BLOCK still narrows an enum-declared reference`() {
        // Typed from currentLocalTypes, not from the flow read (C) unblocked. Passes
        // on both builds by design: it fails any fix that reroutes the if-block path.
        narrowedTo("export function f(k: K) { if (isAB(k)) { probe(k); } }", "K.A | K.B")
    }

    @Test
    fun `negative control - a member of an UNRELATED enum does not narrow`() {
        // Fails any fix that matches on the EnumLiteral flag without checking the
        // owning enum — the (REL.1)(c) round-746 rule that keeps `Z.Foo.A` out of
        // `X.Foo`.
        narrowedTo("export function f(k: K) { if (k === J.X) { probe(k); } }", "K")
    }

    @Test
    fun `negative control - a numeric literal union still narrows by value`() {
        // (PARITY.1)(b-residue) / (PARITY.2): the shared `probe` is now a `never`
        // parameter — the one target tsc suppresses the source generalization for —
        // which is what keeps a LITERAL UNION named in full here as well.
        narrowedTo("export function f(k: 1 | 2 | 3) { if (k === 1) { probe(k); } }", "1")
    }

    @Test
    fun `negative control - an unguarded enum reference keeps its declared type`() {
        narrowedTo("export function f(k: K) { probe(k); }", "K")
    }

    @Test
    fun `negative control - a mixed enum and string discriminant switch still filters`() {
        // The (B) switch arm collects member types BESIDE the literal list rather than
        // folding them in; folding would make this mixed switch bail, because the
        // discriminant paths key their cases through literalDiscriminantKeyOfType,
        // which has no answer for an enum-member type.
        diagnose(
            """
            enum Kind { Acc, Field }
            interface A { kind: Kind.Acc; acc: string; }
            interface B { kind: Kind.Field; fld: number; }
            interface C { kind: "plain"; plain: boolean; }
            declare function take(n: number): void;
            export function f(x: A | B | C) {
                switch (x.kind) {
                    case Kind.Field: take(x.fld); break;
                    case "plain": take(x.plain ? 1 : 0); break;
                }
            }
            """.trimIndent(),
        ) should { have(none { it.code == 2339 }) }
    }
}
