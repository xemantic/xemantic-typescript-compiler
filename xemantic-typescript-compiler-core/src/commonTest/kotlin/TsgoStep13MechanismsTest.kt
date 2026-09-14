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
 * (LEGACY.0b) step 13, round (P18.98): three checker mechanisms where TypeScript 7 (tsgo
 * 7.0.2, the ONLY compatibility target) differs from the tsc-6 transcription this checker
 * carried, plus the two leaf-promotion sites. Every expectation below was read off
 * `tools/tsgo-7.0.2/lib/tsc -p .` over the SAME fixture text (the fixtures are the round's
 * probe projects verbatim), never hand-derived.
 *
 *  * **M1** — no per-program spelling-suggestion cap. tsc 6 stopped looking after
 *    `maximumSuggestionCount` (10) unresolved-name reports; tsgo's
 *    `getSuggestedSymbolForNonexistentSymbol` (checker.go) runs for every one, so the 11th+
 *    misspelling is still TS2552 with its `'x' is declared here.` related row.
 *  * **M2** — TS6198 *All destructured elements are unused.* is tsgo's
 *    `reportUnusedBindingElements`: recursive from the ROOT pattern, ARRAY patterns as well
 *    as object patterns, an omitted slot counting as unused, an array rest exempting no
 *    sibling, and a `_`-prefixed element USED unless it is an object-pattern shorthand
 *    (`isUnreferencedVariableDeclaration`). Parameters and `for (const … of …)` heads take
 *    the same reporter.
 *  * **M3** — the global `Object` source. tsgo's `Relater.reportErrorResults` (relater.go)
 *    reports *The 'Object' type is assignable to very few other types…* INSIDE the ordinary
 *    relation error — head, hint, then the elaboration — for every non-primitive target;
 *    tsc 6 made it the TS2696 top code, and TypeScript 7's baselines carry ZERO TS2696 rows.
 *    A type-parameter target is the one exception: `reportRelationError` clears the chain
 *    for the unconstrained arm and prints the constraint line ABOVE the hint otherwise.
 *  * **M4** — `reportRelationError` returns without a head when the chain's next message is
 *    an excess-property error (and `reportError` drops *Types of property 'p' are
 *    incompatible.* the same way), so an excess property found under a type assertion is
 *    TS2353 at the property and nothing else.
 */
class TsgoStep13MechanismsTest {

    // ── M1: no suggestion cap ────────────────────────────────────────────────

    private val m1Source = """
        var blob;
        bob; bob; bob; bob; bob; bob; bob; bob; bob; bob;
        bob; bob;
        zzqx;
        function myFunction1() { }
        myFunction2;
    """

    private fun m1(): List<Diagnostic> = diagnose(m1Source, directives = "// @strict: false")

    /**
     * `maximum10SpellingSuggestions`' shape: ten `bob`s on line 2 exhaust tsc 6's cap and
     * tsgo still answers `Did you mean 'blob'?` for the two on line 3, each with
     * `'blob' is declared here.` at `(1,5)`.
     */
    @Test
    fun `M1 - the 11th and 12th misspellings still get a suggestion and its declared-here row`() {
        val rows = m1().filter { it.line == 3 }
        assert(rows.size == 2)
        assert(rows.all { it.code == 2552 })
        assert(rows.all { it.message == "Cannot find name 'bob'. Did you mean 'blob'?" })
        assert(rows.map { it.character } == listOf(1, 6))
        for (row in rows) {
            val related = row.relatedInformation.single()
            assert(related.code == 2728)
            assert(related.message == "'blob' is declared here.")
            assert(related.line == 1)
            assert(related.character == 5)
        }
    }

    /** Every one of the twelve `bob`s carries the suggestion — no TS2304 among them. */
    @Test
    fun `M1 - no unresolved bob falls back to TS2304`() {
        val bobs = m1().filter { it.message.startsWith("Cannot find name 'bob'") }
        assert(bobs.size == 12)
        assert(bobs.none { it.code == 2304 })
    }

    /**
     * `commonMissingSemicolons`' shape one position past the cap: a user function is still
     * suggested for a name declared AFTER twelve unresolved reports — tsgo `(6,1)`.
     */
    @Test
    fun `M1 - a user declaration is still suggested after twelve unresolved names`() {
        val row = m1().single { it.line == 6 }
        assert(row.code == 2552)
        assert(row.message == "Cannot find name 'myFunction2'. Did you mean 'myFunction1'?")
        val related = row.relatedInformation.single()
        assert(related.message == "'myFunction1' is declared here.")
        assert(related.line == 5)
        assert(related.character == 10)
    }

    /** Negative control: a name with no close candidate is plain TS2304 whatever the count. */
    @Test
    fun `M1 negative control - a name with no close candidate stays TS2304`() {
        val row = m1().single { it.line == 4 }
        assert(row.code == 2304)
        assert(row.message == "Cannot find name 'zzqx'.")
        assert(row.relatedInformation.isEmpty())
    }

    // ── M2: recursive TS6198 grouping ───────────────────────────────────────

    private val m2Directives =
        "// @strict: true\n// @target: es2015\n// @noUnusedLocals: true\n// @noUnusedParameters: true"

    private val m2Source = """
        export {};
        declare const o: any;
        function t1() {
            const [, a] = o;
            const [b, [c, d]] = o;
            const [e, [f, g]] = o; g;
            const { _h, i } = o; i;
            const [_j, _k] = o;
            const { l: _l, m } = o; m;
            const [n, ...rest] = o;
            const { p, ...rest2 } = o;
            const { q1: { q2, q3 }, q4 } = o;
            const { r1: { r2, r3 }, r4 } = o; r4;
            const [[s1, s2]] = o;
            for (const [q, r] of o) {}
            for (const [[s, t]] of o) {}
            for (const { u, v } of o) {}
            for (const [_w, x] of o) {}
            for (const [_w2, _x2] of o) {}
        }
        function t2([[a, b]]: any, { c, d }: any, [_e, _f]: any, { _g, _h }: any, [, i]: any) {}
        t1; t2;
    """

    private fun m2(): List<Diagnostic> = diagnose(m2Source, directives = m2Directives)

    private fun m2Grouped(line: Int): Diagnostic = m2().single { it.code == 6198 && it.line == line }

    private fun m2NothingOn(line: Int) = m2().none { it.line == line }

    /** `const [b, [c, d]] = o;` — one TS6198 over the whole pattern, tsgo `(5,11)`. */
    @Test
    fun `M2 - an all-unused array pattern groups into one TS6198 over the pattern`() {
        val row = m2Grouped(5)
        assert(row.message == "All destructured elements are unused.")
        assert(row.character == 11)
        assert(row.length == "[b, [c, d]]".length)
        assert(m2().none { it.code == 6133 && it.line == 5 })
    }

    /** `const { q1: { q2, q3 }, q4 } = o;` — the OUTER pattern is grouped, tsgo `(12,11)`. */
    @Test
    fun `M2 - a nested object pattern groups from the root when every leaf is unused`() {
        val row = m2Grouped(12)
        assert(row.character == 11)
        assert(row.length == "{ q1: { q2, q3 }, q4 }".length)
        assert(m2().none { it.code == 6133 && it.line == 12 })
    }

    /**
     * `const { r1: { r2, r3 }, r4 } = o; r4;` — a USED leaf in the outer pattern stops the
     * grouping there and the DESCENT still reaches the inner `{ r2, r3 }`, tsgo `(13,17)`.
     * Green on the pre-change binary by coincidence (the old innermost grouping answered the
     * same) and RED under the arm that drops the descent (a4) — it pins the recursion's
     * boundary, not the grouping.
     */
    @Test
    fun `M2 - a used outer leaf stops the grouping and the descent still reaches the nested pattern`() {
        val row = m2Grouped(13)
        assert(row.character == 17)
        assert(row.length == "{ r2, r3 }".length)
        assert(m2().none { it.code == 6133 && it.line == 13 })
    }

    /** `const [[s1, s2]] = o;` — a one-element outer recurses into `[s1, s2]`, tsgo `(14,12)`. */
    @Test
    fun `M2 - a one-element outer array pattern recurses into its nested pattern`() {
        val row = m2Grouped(14)
        assert(row.character == 12)
        assert(row.length == "[s1, s2]".length)
    }

    /** `const [, a] = o;` — the omitted slot has no name and counts as unused, tsgo `(4,11)`. */
    @Test
    fun `M2 - an omitted array slot counts as an unused element`() {
        val row = m2Grouped(4)
        assert(row.character == 11)
        assert(row.length == "[, a]".length)
        assert(m2().none { it.code == 6133 && it.line == 4 })
    }

    /** `const [n, ...rest] = o;` — an ARRAY rest exempts nothing, tsgo `(10,11)`. */
    @Test
    fun `M2 - an array rest element exempts no sibling`() {
        val row = m2Grouped(10)
        assert(row.character == 11)
        assert(row.length == "[n, ...rest]".length)
        assert(m2().none { it.code == 6133 && it.line == 10 })
    }

    /** `for (const [q, r] of o) {}` / `for (const { u, v } of o) {}` — tsgo `(15,16)`, `(17,16)`. */
    @Test
    fun `M2 - a for-of head pattern groups`() {
        assert(m2Grouped(15).character == 16)
        assert(m2Grouped(15).length == "[q, r]".length)
        assert(m2Grouped(17).character == 16)
        assert(m2Grouped(17).length == "{ u, v }".length)
        assert(m2().none { it.code == 6133 && (it.line == 15 || it.line == 17) })
    }

    /** `for (const [[s, t]] of o) {}` — the inner `[s, t]`, tsgo `(16,17)`. */
    @Test
    fun `M2 - a nested for-of head pattern recurses`() {
        val row = m2Grouped(16)
        assert(row.character == 17)
        assert(row.length == "[s, t]".length)
    }

    /** `function t2([[a, b]]: any, { c, d }: any, …, [, i]: any)` — tsgo `(21,14)`, `(21,28)`, `(21,75)`. */
    @Test
    fun `M2 - destructuring parameters group with the same recursion`() {
        val cols = m2().filter { it.code == 6198 && it.line == 21 }.map { it.character ?: 0 }.sorted()
        assert(cols == listOf(14, 28, 58, 75))
        assert(m2().none { it.code == 6133 && it.line == 21 })
    }

    /**
     * Negative control (green on the pre-change binary too): `{ _g, _h }: any` —
     * object-pattern SHORTHAND `_` elements are unused and still group, tsgo `(21,58)`.
     */
    @Test
    fun `M2 negative control - shorthand underscore parameter elements still group`() {
        val row = m2().single { it.code == 6198 && it.line == 21 && it.character == 58 }
        assert(row.length == "{ _g, _h }".length)
    }

    /** `const { _h, i } = o; i;` — a lone shorthand `_h` is REPORTED, tsgo `(7,13)`. */
    @Test
    fun `M2 - a lone shorthand underscore element is reported`() {
        val row = m2().single { it.line == 7 }
        assert(row.code == 6133)
        assert(row.message == "'_h' is declared but its value is never read.")
        assert(row.character == 13)
    }

    /** Negative control: `const [e, [f, g]] = o; g;` — a used leaf keeps the per-element rows. */
    @Test
    fun `M2 negative control - a pattern with one used leaf stays per-element`() {
        val rows = m2().filter { it.line == 6 }.sortedBy { it.character }
        assert(rows.map { it.code } == listOf(6133, 6133))
        assert(rows.map { it.message } == listOf(
            "'e' is declared but its value is never read.",
            "'f' is declared but its value is never read.",
        ))
        assert(rows.map { it.character } == listOf(12, 16))
    }

    /** Negative control: `_` array elements and renamed `_` object elements are USED. */
    @Test
    fun `M2 negative control - underscore array and renamed elements are used`() {
        assert(m2NothingOn(8))   // const [_j, _k] = o;
        assert(m2NothingOn(9))   // const { l: _l, m } = o; m;
        assert(m2NothingOn(19))  // for (const [_w2, _x2] of o) {}
        assert(m2().filter { it.line == 18 }.map { it.message } ==
            listOf("'x' is declared but its value is never read."))
    }

    /** Negative control: `{ p, ...rest2 }` — an OBJECT rest still exempts its siblings. */
    @Test
    fun `M2 negative control - an object rest still exempts its siblings`() {
        val rows = m2().filter { it.line == 11 }
        assert(rows.map { it.message } == listOf("'rest2' is declared but its value is never read."))
        assert(rows.single().character == 19)
    }

    /** Negative control: `[_e, _f]: any` — a `_` array PARAMETER pattern reports nothing. */
    @Test
    fun `M2 negative control - underscore array parameter elements are used`() {
        assert(m2().none { it.line == 21 && (it.character ?: 0) in 41..48 })
    }

    // ── M3: the Object source ────────────────────────────────────────────────

    private val objectHint =
        "The 'Object' type is assignable to very few other types. Did you mean to use the 'any' type instead?"

    private val m3Source = """
        declare var x: Object;
        var y: RegExp;
        y = x;
        var w: Error = new Object();
        var anon: { a: number } = x;
        var one: { a: number; b: string; c: boolean } = x;
        type Al = { k: number };
        var al: Al = x;
        var s: string = x;
        interface I { toString(): number }
        var i: I;
        i = x;
        var u: RegExp | Error = x;
        class C { p = 1 }
        var c: C = x;
        function tp<T>(t: T, v: Object) { t = v; }
        declare var plain: { toString(): string };
        var y2: RegExp;
        y2 = plain;
    """

    private fun m3(): List<Diagnostic> = diagnose(m3Source, directives = "// @strict: false")

    private fun m3Row(line: Int): Diagnostic = m3().single { it.line == line }

    /** `y = x;` — `assigningFromObjectToAnythingElse`'s row 3, at an ASSIGNMENT. */
    @Test
    fun `M3 - the Object source is a chain line under TS2322 at an assignment`() {
        val row = m3Row(3)
        assert(row.code == 2322)
        assert(row.character == 1)
        assert(row.length == 1)
        assert(row.message == "Type 'Object' is not assignable to type 'RegExp'.")
        assert(row.messageChain == listOf(
            "  $objectHint",
            "    Type 'Object' is missing the following properties from type 'RegExp': exec, test, source, global, and 11 more.",
        ))
    }

    /** `var w: Error = new Object();` — the same at a DECLARATION, two missing properties. */
    @Test
    fun `M3 - the Object source is a chain line under TS2322 at a declaration`() {
        val row = m3Row(4)
        assert(row.code == 2322)
        assert(row.character == 5)
        assert(row.message == "Type 'Object' is not assignable to type 'Error'.")
        assert(row.messageChain == listOf(
            "  $objectHint",
            "    Type 'Object' is missing the following properties from type 'Error': name, message",
        ))
    }

    /** `var c: C = x;` — ONE missing property takes the *Property 'p' is missing* form. */
    @Test
    fun `M3 - a single missing property takes the 2741 form under the hint`() {
        val row = m3Row(15)
        assert(row.code == 2322)
        assert(row.message == "Type 'Object' is not assignable to type 'C'.")
        assert(row.messageChain == listOf(
            "  $objectHint",
            "    Property 'p' is missing in type 'Object' but required in type 'C'.",
        ))
        assert(row.relatedInformation.map { it.message } == listOf("'p' is declared here."))
    }

    /** `var anon: { a: number } = x;` / `var al: Al = x;` — anonymous and alias targets too. */
    @Test
    fun `M3 - anonymous and alias targets carry the hint too`() {
        val anon = m3Row(5)
        assert(anon.code == 2322)
        assert(anon.message == "Type 'Object' is not assignable to type '{ a: number; }'.")
        assert(anon.messageChain == listOf(
            "  $objectHint",
            "    Property 'a' is missing in type 'Object' but required in type '{ a: number; }'.",
        ))
        val al = m3Row(8)
        assert(al.message == "Type 'Object' is not assignable to type 'Al'.")
        assert(al.messageChain == listOf(
            "  $objectHint",
            "    Property 'k' is missing in type 'Object' but required in type 'Al'.",
        ))
        val one = m3Row(6)
        assert(one.messageChain == listOf(
            "  $objectHint",
            "    Type 'Object' is missing the following properties from type '{ a: number; b: string; c: boolean; }': a, b, c",
        ))
    }

    /**
     * `var u: RegExp | Error = x;` — a union target: tsgo prints the hint as the WHOLE chain
     * (its `getBestMatchingType` finds no constituent for the `Object` interface). Only the
     * hint's PLACE is pinned here: through `diagnose()`'s embedded lib the pre-existing B50.3
     * union-constituent drill still appends `Type 'Object' is not assignable to type 'RegExp'.`
     * and its missing line beneath — a residue outside this round (the real-lib project path
     * prints the hint alone, tsgo-identical).
     */
    @Test
    fun `M3 - a union target carries the hint as its first chain line`() {
        val row = m3Row(13)
        assert(row.code == 2322)
        assert(row.message == "Type 'Object' is not assignable to type 'Error | RegExp'.")
        assert(row.messageChain.firstOrNull() == "  $objectHint")
    }

    /** `i = x;` — `objectTypeHidingMembersOfObjectAssignmentCompat2`'s shape: hint ABOVE the elaboration. */
    @Test
    fun `M3 - the hint sits above an existing elaboration chain`() {
        val row = m3Row(12)
        assert(row.code == 2322)
        assert(row.message == "Type 'Object' is not assignable to type 'I'.")
        assert(row.messageChain == listOf(
            "  $objectHint",
            "    The types returned by 'toString()' are incompatible between these types.",
            "      Type 'string' is not assignable to type 'number'.",
        ))
    }

    /** TS2696 has no producer left: TypeScript 7's baselines carry none. */
    @Test
    fun `M3 - no diagnostic carries TS2696`() {
        assert(m3().none { it.code == 2696 })
    }

    /** Negative control: a PRIMITIVE target takes tsgo's primitives arm — no hint. */
    @Test
    fun `M3 negative control - a primitive target carries no hint`() {
        val row = m3Row(9)
        assert(row.code == 2322)
        assert(row.message == "Type 'Object' is not assignable to type 'string'.")
        assert(row.messageChain.isEmpty())
    }

    /** Negative control: an unconstrained type-PARAMETER target clears the chain — no hint. */
    @Test
    fun `M3 negative control - an unconstrained type parameter target carries no hint`() {
        val row = m3Row(16)
        assert(row.code == 2322)
        assert(row.message == "Type 'Object' is not assignable to type 'T'.")
        assert(row.messageChain == listOf(
            "  'T' could be instantiated with an arbitrary type which could be unrelated to 'Object'.",
        ))
    }

    /** Negative control: a non-`Object` source with the same shape keeps its plain form. */
    @Test
    fun `M3 negative control - a non-Object source keeps its plain missing-property form`() {
        val row = m3Row(19)
        assert(row.code == 2740)
        assert(row.messageChain.isEmpty())
        assert(row.message.startsWith("Type '{ toString(): string; }' is missing the following properties from type 'RegExp':"))
    }

    // ── M4: the excess property is the whole diagnostic ─────────────────────

    private val m4Source = """
        <{ id: number; }[]>[{ foo: "s" }];
        <{ id: number; }[]>[{ foo: "s" }, {}];
        <string[]>[1];
    """

    private fun m4(): List<Diagnostic> = diagnose(m4Source, directives = "// @strict: true")

    /** `arrayCast`: TS2353 at `foo`, `(1,23)` width 3, with no *Conversion of type* head. */
    @Test
    fun `M4 - an excess property inside an array cast is the whole diagnostic`() {
        val row = m4().single { it.line == 1 }
        assert(row.code == 2353)
        assert(row.character == 23)
        assert(row.length == 3)
        assert(row.message == "Object literal may only specify known properties, and 'foo' does not exist in type '{ id: number; }'.")
        assert(row.messageChain.isEmpty())
        assert(m4().none { it.code == 2352 && it.line == 1 })
    }

    /** Negative control: the `{}` element widens the array to `{}[]` and nothing is reported. */
    @Test
    fun `M4 negative control - a widening element keeps the cast silent`() {
        assert(m4().none { it.line == 2 })
    }

    /**
     * Negative control: the same array-cast emitter with NO excess property keeps its
     * TS2352 head and comparability chain — tsgo `(3,1)`, chain
     * `Type 'number' is not comparable to type 'string'.`.
     */
    @Test
    fun `M4 negative control - an array cast without an excess property stays TS2352`() {
        val row = m4().single { it.line == 3 }
        assert(row.code == 2352)
        assert(row.character == 1)
        assert(row.message == "Conversion of type 'number[]' to type 'string[]' may be a mistake because neither type sufficiently overlaps with the other. If this was intentional, convert the expression to 'unknown' first.")
        assert(row.messageChain == listOf("  Type 'number' is not comparable to type 'string'."))
    }
}
