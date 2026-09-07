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
 * (CHK.101) stage 1 — THREE flow-narrowing defects around nullish types, each measured
 * against tsgo 7.0.2 AND pristine `typescript@6.0.3` before any code was written (the two
 * agree on every row pinned here):
 *
 *  1. LOOSE equality against a nullish keyword tests BOTH nullish values —
 *     ([Checker.narrowByLooseNullishEquality], tsc's `TypeFacts.EQUndefinedOrNull` /
 *     `NEUndefinedOrNull`). `x != null` on a `Pr | undefined` removed only the `null`
 *     that was never there;
 *  2. a DEFAULTED parameter does not see `undefined` inside the body
 *     ([Checker.defaultStrippedParamType], tsc's `getTypeForVariableLikeDeclaration`);
 *  3. a COMPOUND LOGICAL ASSIGNMENT's expression VALUE is the LHS part that survives the
 *     short circuit unioned with the RHS, not the whole declared LHS
 *     ([Checker.combineBinaryTypes]): `x ??= v` is `NonNullable<x> | v`, `x ||= v` is
 *     `truthy(x) | v`, `x &&= v` is `falsy(x) | v`.
 *
 * TWO OF THE THREE WERE SHIPPED FALSE POSITIVES, and that is why they are gradeable at
 * all: with a PRIMITIVE non-nullish part the argument reader already emitted, so
 * `f(x: string | undefined) { if (x != null) takeS(x) }`,
 * `f(x: string | undefined = "a") { takeS(x) }` and `takeS(b.s ??= "a")` each reported an
 * ours-only `TS2345` neither reference has, and the same `??=` printed `Pr | undefined`
 * for `Pr` wherever a row was reported at all. Every pin below is
 * therefore a VALUE pin naming the narrowed type through a deliberately-wrong primitive
 * parameter, never an absence assertion — the type the checker actually built is printed
 * in the message.
 *
 * WHY THE ITEM'S OWN DELIVERABLE IS NOT HERE. (CHK.101)(a) — emitting the refusal of a
 * nullish-carrying union at an object-ish target — was BUILT, measured correct on 20 of
 * 20 reference rows, and REFUSED on the 8-profile grid: the reader half added 19-21
 * ours-only rows per profile and the argument half 4-7, every one of them a LOST
 * NARROWING that is invisible today only because the diagnostic is missing. The three
 * fixes here are two of those gaps; the four that remain are named in the round note.
 */
class NullishUnionAssignabilityTest {

    private val prelude = """
        interface Pr { pk: number }
        interface Box { q: Pr | undefined; s: string | undefined; n: number }
        declare function mk(): Pr
        declare function takeS(s: string): void
        declare function useOne(p: Pr): void
        declare function take13(n: 1 | 3): void
        declare function take23(n: 2 | 3): void
        export {}
    """.trimIndent() + "\n"

    private fun a(t: String) = "Argument of type '$t' is not assignable to parameter of type 'string'."

    private fun run(src: String) = diagnose(prelude + src, directives = "// @strict: true")

    /** Exactly the rows [expected] — code and message — in order. */
    private fun rows(src: String, vararg expected: Pair<Int, String>) {
        val got = run(src).map { it.code to it.message }
        assert(got == expected.toList())
    }

    private fun silent(src: String) {
        val got = run(src).map { it.code to it.message }
        assert(got.isEmpty())
    }

    // -----------------------------------------------------------------------
    // 1. LOOSE nullish equality.
    // -----------------------------------------------------------------------

    @Test
    fun `a loose inequality against null removes undefined as well`() =
        rows("export function f(x: Pr | undefined) { if (x != null) { takeS(x) } }", 2345 to a("Pr"))

    @Test
    fun `a loose inequality against null removes null as well`() =
        rows("export function f(x: Pr | null) { if (x != null) { takeS(x) } }", 2345 to a("Pr"))

    @Test
    fun `a loose inequality against null removes both nullish constituents at once`() =
        rows("export function f(x: Pr | null | undefined) { if (x != null) { takeS(x) } }", 2345 to a("Pr"))

    @Test
    fun `a loose inequality against undefined removes null as well`() =
        rows("export function f(x: Pr | null | undefined) { if (x != undefined) { takeS(x) } }", 2345 to a("Pr"))

    @Test
    fun `a reversed loose inequality against null narrows the same way`() =
        rows("export function f(x: Pr | undefined) { if (null != x) { takeS(x) } }", 2345 to a("Pr"))

    @Test
    fun `a loose equality against null with an early return removes both`() =
        rows("export function f(x: Pr | null | undefined) { if (x == null) return; takeS(x) }", 2345 to a("Pr"))

    @Test
    fun `the true branch of a loose nullish equality keeps only the nullish constituents`() =
        rows("export function f(x: Pr | undefined) { if (x == null) { takeS(x) } }", 2345 to a("undefined"))

    @Test
    fun `a shipped false positive closed - a primitive union narrowed by a loose null test`() =
        silent("export function f(x: string | undefined) { if (x != null) { takeS(x) } }")

    @Test
    fun `a shipped false positive closed - a primitive union narrowed by a loose null return`() =
        silent("export function f(x: string | null | undefined) { if (x == null) return; takeS(x) }")

    @Test
    fun `negative control - a STRICT inequality against null still keeps undefined`() =
        rows("export function f(x: Pr | undefined) { if (x !== null) { takeS(x) } }", 2345 to a("Pr | undefined"))

    @Test
    fun `negative control - a STRICT inequality against undefined still keeps null`() =
        rows("export function f(x: Pr | null) { if (x !== undefined) { takeS(x) } }", 2345 to a("Pr | null"))

    // The literal controls take a target that HOLDS a literal of the same base, because
    // at a plain `string`/`number` target tsc GENERALIZES a literal source to its base
    // ((PARITY.1)(b)) and the narrowed and un-narrowed answers both print `number` — a
    // blind control. Against `1 | 3` the generalization is suppressed and the two are
    // distinguishable. Both rows measured identical in tsgo 7.0.2 and pristine 6.0.3.
    @Test
    fun `negative control - a loose equality against a non-nullish literal still narrows`() =
        rows(
            "export function f(x: 1 | 2) { if (x == 1) { take23(x) } }",
            2345 to "Argument of type '1' is not assignable to parameter of type '2 | 3'.",
        )

    @Test
    fun `negative control - a loose inequality against a non-nullish literal still narrows`() =
        rows(
            "export function f(x: 1 | 2) { if (x != 1) { take13(x) } }",
            2345 to "Argument of type '2' is not assignable to parameter of type '1 | 3'.",
        )

    @Test
    fun `negative control - a strict inequality against a non-nullish literal still narrows`() =
        rows(
            "export function f(x: 1 | 2) { if (x !== 1) { take13(x) } }",
            2345 to "Argument of type '2' is not assignable to parameter of type '1 | 3'.",
        )

    // -----------------------------------------------------------------------
    // 2. A DEFAULTED parameter.
    // -----------------------------------------------------------------------

    @Test
    fun `a defaulted parameter does not see undefined inside the body`() =
        rows("export function f(x: Pr | undefined = { pk: 1 }) { takeS(x) }", 2345 to a("Pr"))

    @Test
    fun `a defaulted parameter whose default is a call does not see undefined`() =
        rows("export function f(x: Pr | undefined = mk()) { takeS(x) }", 2345 to a("Pr"))

    @Test
    fun `a shipped false positive closed - a defaulted primitive parameter`() =
        silent("export function f(x: string | undefined = \"a\") { takeS(x) }")

    @Test
    fun `a parameter defaulted to null keeps its null`() =
        rows("export function f(x: Pr | null = null) { takeS(x) }", 2345 to a("Pr | null"))

    @Test
    fun `a defaulted parameter strips only undefined and keeps null`() =
        rows("export function f(x: Pr | null | undefined = mk()) { takeS(x) }", 2345 to a("Pr | null"))

    @Test
    fun `a parameter whose default is itself undefined keeps its undefined`() =
        rows("export function f(x: Pr | undefined = undefined) { takeS(x) }", 2345 to a("Pr | undefined"))

    @Test
    fun `negative control - an undefaulted parameter keeps its undefined`() =
        rows("export function f(x: Pr | undefined) { takeS(x) }", 2345 to a("Pr | undefined"))

    @Test
    fun `negative control - an optional parameter with no annotation-borne undefined is unchanged`() =
        rows("export function f(x: Pr = mk()) { takeS(x) }", 2345 to a("Pr"))

    // -----------------------------------------------------------------------
    // 3. A COMPOUND LOGICAL ASSIGNMENT's expression value.
    // -----------------------------------------------------------------------

    @Test
    fun `the value of a nullish assignment drops the nullish part of its target`() =
        rows("export function f(b: Box) { takeS(b.q ??= mk()) }", 2345 to a("Pr"))

    @Test
    fun `the value of an or-assignment drops the falsy part of its target`() =
        rows("export function f(b: Box) { takeS(b.q ||= mk()) }", 2345 to a("Pr"))

    @Test
    fun `the value of an and-assignment keeps the falsy part of its target`() =
        rows("export function f(b: Box) { takeS(b.q &&= mk()) }", 2345 to a("Pr | undefined"))

    @Test
    fun `a nullish assignment on a parameter drops the nullish part`() =
        rows("export function f(x: Pr | undefined) { takeS(x ??= mk()) }", 2345 to a("Pr"))

    @Test
    fun `an and-assignment on a parameter keeps the falsy part`() =
        rows("export function f(x: Pr | undefined) { takeS(x &&= mk()) }", 2345 to a("Pr | undefined"))

    // RECORDED BLIND, not claimed: both readers below are silent for an object-ish
    // target on the parent binary AND on this one, because the mechanism that would
    // observe a wrong `Pr | undefined` here is (CHK.101)(a), which this round measured
    // and REFUSED. Arm a5 reddens neither. They are kept because they are correct
    // assertions that become live the moment (a) lands — the shape is exactly tsc's own
    // `getBaseConstructorTypeOfClass` memoization idiom — and because a future round
    // landing (a) must not have to rediscover them.
    @Test
    fun `recorded blind - returning a nullish assignment is silent at an object target`() =
        silent("export function f(b: Box): Pr { return b.q ??= mk() }")

    @Test
    fun `recorded blind - a nullish assignment at an object-typed parameter`() =
        silent("export function f(b: Box) { useOne(b.q ??= mk()) }")

    @Test
    fun `a nullish assignment whose right-hand side is undefined keeps the nullish part`() =
        rows("export function f(b: Box) { takeS(b.q ??= undefined) }", 2345 to a("Pr | undefined"))

    @Test
    fun `negative control - a plain assignment's value is its right-hand side`() =
        rows("export function f(b: Box) { takeS(b.q = mk()) }", 2345 to a("Pr"))

    @Test
    fun `negative control - a non-nullish target is unchanged by all three operators`() =
        rows(
            "export function f(b: Box) { takeS(b.n ??= 1); takeS(b.n ||= 1); takeS(b.n &&= 1) }",
            2345 to a("number"), 2345 to a("number"), 2345 to a("number"),
        )

    @Test
    fun `a shipped false positive closed - a nullish assignment to a primitive union`() =
        silent("export function f(b: Box) { takeS(b.s ??= \"a\") }")
}
