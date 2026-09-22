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
 */


package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.142)(b): an object SOURCE whose discriminant members are UNIONS of the target
 * constituents' discriminants is assignable to a discriminated union - tsgo's
 * `typeRelatedToDiscriminatedType` (`internal/checker/relater.go:3989`), called from the
 * TAIL of `structuredTypeRelatedTo` (`relater.go:3893`), below the ordinary union-target
 * dispatch. Our union-target arm had only the plain "relates to SOME constituent" rule
 * plus round 744's intersection split, so every shape below was TS2322 on legal code.
 *
 * EVERY EXPECTED VALUE HERE WAS MEASURED against `tools/tsgo-7.0.2/lib/tsc --noEmit -p`
 * on the same fixture, one fixture per directory, rather than reasoned out - the
 * algorithm has four separable parts and three of them are counter-intuitive.
 *
 * The four parts, and the pin that names each:
 *
 *  1. THE DISCRIMINANT FILTER is tsgo's `isDiscriminantProperty` (`relater.go:1087`):
 *     non-uniform across the target's constituents AND at least one constituent's type a
 *     LITERAL, and non-generic. So a `number`-vs-`string` difference does NOT qualify and
 *     a `true`/`false` one DOES. It is NOT `getKeyPropertyName`, which additionally
 *     demands uniqueness and would admit at most one axis.
 *  2. THE CARTESIAN PRODUCT runs over ALL such members, not a single key.
 *  3. THE 25-COMBINATION CAP (`relater.go:4013`) is directly observable, so it is a
 *     constant to carry rather than a budget to choose.
 *  4. `matchingTypes` collects EVERY constituent a combination matches and the source's
 *     NON-discriminant members are compared against ALL of them (`relater.go:4077`).
 *     That is what refutes the naive "each split relates to SOME constituent" rule.
 *
 * The negative half is as load-bearing as the positive one: part 1 and part 4 are both
 * REFUSALS, and a leg written without either accepts programs tsgo rejects - which no
 * corpus baseline in this repo can see, because the canonical reference fixture
 * (`assignmentCompatWithDiscriminatedUnion`) has no case file in this sparse clone and
 * zero mentions in the generated tree.
 */
class DiscriminatedUnionSourceSplitTest {

    private fun errs(source: String): List<Diagnostic> = diagnose(source)

    private fun assignabilityErrors(source: String): Int =
        errs(source).count { it.code == 2322 }

    // ---- the leg itself --------------------------------------------------------------

    @Test
    fun `an object source splitting on one literal discriminant relates to the union`() {
        assert(
            assignabilityErrors(
                """
                interface Link  { type: 'link';  raw: string }
                interface Image { type: 'image'; raw: string }
                declare const src: { type: 'image' | 'link'; raw: string };
                const t: Link | Image = src;
                """
            ) == 0
        )
    }

    @Test
    fun `the canonical IteratorResult shape relates through a boolean discriminant`() {
        assert(
            assignabilityErrors(
                """
                type S = { done: boolean, value: number };
                type T = { done: true, value: number } | { done: false, value: number };
                declare let s: S;
                declare let t: T;
                t = s;
                """
            ) == 0
        )
    }

    @Test
    fun `a split that drops constituents of the target still relates`() {
        // The canonical fixture's Example2: the source's `a` is `0 | 2`, so `{ a: 1 }` is
        // never selected and only two of the three constituents are ever matched.
        assert(
            assignabilityErrors(
                """
                type S = { a: 0 | 2, b: 4 };
                type T = { a: 0, b: 1 | 4 } | { a: 1, b: 2 } | { a: 2, b: 3 | 4 };
                declare let s: S;
                declare let t: T;
                t = s;
                """
            ) == 0
        )
    }

    @Test
    fun `a nullable second discriminant is an axis and not merely a member`() {
        // The canonical fixture's GH18421. `undefined` is a UNIT type in tsc, so `value`
        // is the SECOND discriminant; without that the product collapses onto `kind`
        // alone and neither combination finds a constituent.
        assert(
            assignabilityErrors(
                """
                type Foo = { kind: "a" | "b", value: number }
                         | { kind: "a", value: undefined }
                         | { kind: "b", value: undefined };
                function test(obj: { kind: "a" | "b", value: number | undefined }) {
                    let x1: Foo = obj;
                }
                """
            ) == 0
        )
    }

    // ---- part 1: the discriminant FILTER ---------------------------------------------

    @Test
    fun `a non-literal difference is not a discriminant and the assignment is refused`() {
        // tsgo reports here too: `number` and `string` differ but neither is a literal,
        // so `CheckFlagsHasLiteralType` is never set and the source has no axis at all.
        assert(
            assignabilityErrors(
                """
                interface A { k: number; raw: string }
                interface B { k: string; raw: string }
                declare const src: { k: number | string; raw: string };
                const t: A | B = src;
                """
            ) == 1
        )
    }

    @Test
    fun `a member uniform across the target is not a discriminant`() {
        // `m` is the SAME literal in both constituents, so it is uniform and excluded;
        // `x` is non-uniform and non-literal, so it is excluded too. No axis remains and
        // tsgo refuses - a leg that treated any union-typed member as an axis accepts it.
        assert(
            assignabilityErrors(
                """
                interface A { m: 'same'; x: number }
                interface B { m: 'same'; x: string }
                declare const src: { m: 'same'; x: number | string };
                const t: A | B = src;
                """
            ) == 1
        )
    }

    @Test
    fun `a boolean literal pair is a discriminant`() {
        assert(
            assignabilityErrors(
                """
                interface Y { ok: true;  raw: string }
                interface N { ok: false; raw: string }
                declare const src: { ok: boolean; raw: string };
                const t: Y | N = src;
                """
            ) == 0
        )
    }

    @Test
    fun `an optional discriminant accepts the undefined axis`() {
        // `GH30170` in the canonical fixture. tsgo compares a combination against
        // `addOptionality(getNonMissingTypeOfSymbol(targetProp))`, so under strictNullChecks
        // an OPTIONAL member's type carries `| undefined` and the `undefined` axis selects
        // `Yellow`. Without that widening this combination matches nothing and the whole
        // assignment is refused - and NO OTHER PIN HERE SEES IT (measured: the arm that
        // drops the widening reddens none of the rest).
        assert(
            assignabilityErrors(
                """
                interface Blue { color: 'blue' }
                interface Yellow { color?: 'yellow' }
                declare const src: { color: 'blue' | 'yellow' | undefined };
                const t: Blue | Yellow = src;
                """
            ) == 0
        )
    }

    // ---- part 2: the CARTESIAN PRODUCT -----------------------------------------------

    @Test
    fun `two discriminants relate when every combination is covered`() {
        assert(
            assignabilityErrors(
                """
                interface AA { p: 'a'; q: 'x'; z: number }
                interface AB { p: 'a'; q: 'y'; z: number }
                interface BA { p: 'b'; q: 'x'; z: number }
                interface BB { p: 'b'; q: 'y'; z: number }
                declare const src: { p: 'a' | 'b'; q: 'x' | 'y'; z: number };
                const t: AA | AB | BA | BB = src;
                """
            ) == 0
        )
    }

    @Test
    fun `two discriminants are refused when one combination is uncovered`() {
        // Drops `BB`, so the combination `p = 'b'` with `q = 'y'` matches nothing. A leg
        // that split on a SINGLE key would accept this, because `p` alone and `q` alone
        // are each fully covered.
        assert(
            assignabilityErrors(
                """
                interface AA { p: 'a'; q: 'x'; z: number }
                interface AB { p: 'a'; q: 'y'; z: number }
                interface BA { p: 'b'; q: 'x'; z: number }
                declare const src: { p: 'a' | 'b'; q: 'x' | 'y'; z: number };
                const t: AA | AB | BA = src;
                """
            ) == 1
        )
    }

    // ---- part 3: the 25-COMBINATION CAP ----------------------------------------------

    /**
     * A single-axis union of [n] fully covered constituents, i.e. exactly [n]
     * combinations. GENERATED rather than written out because the pin is a BOUNDARY and
     * a hand-typed 26-item list that is silently short by one is a vacuous pin; the
     * constituent count is asserted below so the generator cannot drift either.
     */
    private fun cappedFixture(n: Int): String {
        val ifaces = (0 until n).joinToString("\n") { "interface I$it { p: 'p$it'; z: number }" }
        val union = (0 until n).joinToString(" | ") { "I$it" }
        val axis = (0 until n).joinToString(" | ") { "'p$it'" }
        return "$ifaces\ndeclare const src: { p: $axis; z: number };\nconst t: $union = src;\n"
    }

    /** Declared constituents, source axis values and combinations, which are all [n]. */
    private fun combinationsOf(fixture: String): Int {
        val declared = fixture.lines().count { it.startsWith("interface I") }
        val constituents = fixture.lines().last { it.startsWith("const t:") }
            .substringAfter("const t: ").substringBefore(" = src;").split(" | ").size
        val axis = fixture.lines().last { it.startsWith("declare const src:") }
            .substringAfter("{ p: ").substringBefore("; z: number }").split(" | ").size
        assert(declared == constituents)
        assert(declared == axis)
        return declared
    }

    @Test
    fun `a fully covered 25-way discriminated union is at the cap and relates`() {
        val fixture = cappedFixture(25)
        assert(combinationsOf(fixture) == 25)
        assert(assignabilityErrors(fixture) == 0)
    }

    @Test
    fun `a fully covered 26-way discriminated union is over the cap and is refused`() {
        val fixture = cappedFixture(26)
        assert(combinationsOf(fixture) == 26)
        assert(assignabilityErrors(fixture) == 1)
    }

    // ---- part 4: matchingTypes, and the non-discriminant comparison -------------------

    @Test
    fun `a combination matching two constituents is checked against both of them`() {
        // `'b'` matches BOTH `B` and `C`, and `C` rejects `x`. The naive rule - each
        // split relates to SOME constituent - accepts this; tsgo reports it, because
        // `matchingTypes` collects every match and compares the non-discriminants
        // against all of them.
        assert(
            assignabilityErrors(
                """
                interface A { k: 'a'; x: number }
                interface B { k: 'b'; x: number }
                interface C { k: 'b'; x: string }
                declare const src: { k: 'a' | 'b'; x: number };
                const t: A | B | C = src;
                """
            ) == 1
        )
    }

    @Test
    fun `a matched constituent requiring a member the source lacks is refused`() {
        // The canonical fixture's Example4: the source is selected into `B` by `k`, and
        // `B` requires a `c` the source has not got.
        assert(
            assignabilityErrors(
                """
                interface A { k: 'a'; b: number }
                interface B { k: 'b'; b: number; c: string }
                declare const src: { k: 'a' | 'b'; b: number };
                const t: A | B = src;
                """
            ) == 1
        )
    }

    @Test
    fun `a non-discriminant member that fails is still refused`() {
        // Every combination matches, and the excluded set must cover the DISCRIMINANTS
        // only - a leg that excluded too much would accept `z: string` against
        // `z: number`.
        assert(
            assignabilityErrors(
                """
                interface A { k: 'a'; z: number }
                interface B { k: 'b'; z: number }
                declare const src: { k: 'a' | 'b'; z: string };
                const t: A | B = src;
                """
            ) == 1
        )
    }
}
