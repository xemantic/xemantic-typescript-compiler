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
 * (LEGACY.0b) step 8 — TypeScript 7 reports TS2300 at EVERY declaration of a duplicated
 * class or interface member, and drops TS2717 when the group's first member is a METHOD.
 *
 * **Every expectation below was read off `tools/tsgo-7.0.2/lib/tsc` on one fixture
 * carrying all fourteen shapes, and our own output on that fixture is byte-identical to
 * it across all 29 rows — silences included.** Nothing here is derived from tsc 6, whose
 * answer differs for most of these and is no longer a reference (owner directive
 * 2026-09-12).
 *
 * The three negative controls are the load-bearing part, because every one of them is a
 * shape the change could plausibly have started reporting: a legal get/set PAIR, a
 * legal METHOD OVERLOAD set, and a static/instance pair that shares a spelling and is
 * not a duplicate at all.
 *
 * `@strict: false` throughout so TS2564 (no initializer, not definitely assigned) stays
 * out of the counts — it is orthogonal and would make every assertion here a subtraction.
 */
class DuplicateMemberReportedAtEveryDeclarationTest {

    private val opts = "// @strict: false"

    private fun dup(source: String) =
        diagnose(source, opts).filter { it.code == 2300 }.map { it.message }

    private fun subsequent(source: String) =
        diagnose(source, opts).filter { it.code == 2717 }.map { it.message }

    // ------------------------------------------------------------------ the rule

    @Test
    fun `a method and a later property are BOTH duplicate identifiers`() {
        val d = dup("class C { a(): number { return 0 } a: number = 1; }")
        assert(d == listOf("Duplicate identifier 'a'.", "Duplicate identifier 'a'."))
    }

    @Test
    fun `a property and a later method are BOTH duplicate identifiers`() {
        val d = dup("class C { b: number = 1; b(): number { return 0 } }")
        assert(d == listOf("Duplicate identifier 'b'.", "Duplicate identifier 'b'."))
    }

    @Test
    fun `two properties are BOTH duplicate identifiers - the first is not exempt`() {
        val d = dup("class C { c: number = 1; c: string = ''; }")
        assert(d == listOf("Duplicate identifier 'c'.", "Duplicate identifier 'c'."))
    }

    @Test
    fun `accessors followed by a property flag ALL THREE - not the property alone`() {
        val d = dup("class C { get e(): string { return '' } set e(v: string) {} e: number = 1; }")
        assert(d.size == 3)
        assert(d.all { it == "Duplicate identifier 'e'." })
    }

    @Test
    fun `two getters are both flagged - a duplicated accessor is not a legal pair`() {
        val d = dup("class C { get f(): number { return 0 } get f(): number { return 0 } }")
        assert(d == listOf("Duplicate identifier 'f'.", "Duplicate identifier 'f'."))
    }

    @Test
    fun `an interface reports at every duplicate member declaration`() {
        val d = dup("interface I { p: number; p: string; }")
        assert(d == listOf("Duplicate identifier 'p'.", "Duplicate identifier 'p'."))
    }

    // ------------------------------------------------------- TS2717 and its method gate

    @Test
    fun `two properties of different types still carry TS2717 at the second`() {
        val s = subsequent("class C { c: number = 1; c: string = ''; }")
        assert(s == listOf(
            "Subsequent property declarations must have the same type.  " +
                "Property 'c' must be of type 'number', but here has type 'string'."
        ))
    }

    @Test
    fun `two properties of the SAME type carry no TS2717`() {
        assert(subsequent("class C { d: number = 1; d: number = 2; }").isEmpty())
    }

    @Test
    fun `a method-first group carries NO TS2717 however the property types differ`() {
        assert(subsequent("class C { a(): number { return 0 } a: number = 1; }").isEmpty())
    }

    /**
     * The shape that refutes the obvious reading of the method gate. One might expect the
     * two PROPERTIES to pair with each other once the method has been excused; tsgo says
     * no, because `symbolTable[name]` keeps pointing at the method after the binder splits
     * the first property off, so the third member has no property to be "subsequent" to.
     * Measured: three TS2300 and zero TS2717.
     */
    @Test
    fun `a method then two properties is three TS2300 and still no TS2717`() {
        val src = "class C { g(): number { return 0 } g: number = 1; g: string = ''; }"
        assert(dup(src).size == 3)
        assert(subsequent(src).isEmpty())
    }

    @Test
    fun `an accessor-first group takes its TS2717 reference type from the getter`() {
        val s = subsequent("class C { get e(): string { return '' } set e(v: string) {} e: number = 1; }")
        assert(s == listOf(
            "Subsequent property declarations must have the same type.  " +
                "Property 'e' must be of type 'string', but here has type 'number'."
        ))
    }

    // ------------------------------------------------------------------ the group's name

    /**
     * Every row of a group carries the FIRST declaration's WRITTEN spelling — tsgo renders
     * one `symbolToString` and hands it to every `c.error`. `0b11` and `3` are one member
     * (the scanner normalises a numeric member name), and both rows say `'0b11'`.
     */
    @Test
    fun `both rows are named after the first declaration's own spelling`() {
        val d = dup("class C { 0b11 = ''; 3 = ''; }")
        assert(d == listOf("Duplicate identifier '0b11'.", "Duplicate identifier '0b11'."))
    }

    @Test
    fun `a double-quoted string member keeps its own quotes in both rows`() {
        val d = dup("class C { \"k\": number = 1; k: string = ''; }")
        assert(d == listOf("Duplicate identifier '\"k\"'.", "Duplicate identifier '\"k\"'."))
    }

    @Test
    fun `a single-quoted string member keeps ITS quote character`() {
        val d = dup("class C { 'l': number = 1; l: string = ''; }")
        assert(d == listOf("Duplicate identifier ''l''.", "Duplicate identifier ''l''."))
    }

    @Test
    fun `an interface string member is quoted the same way`() {
        val d = dup("interface I { 'm': number; m: string; }")
        assert(d == listOf("Duplicate identifier ''m''.", "Duplicate identifier ''m''."))
    }

    /**
     * The name and the SPAN are different quantities and must stay so. `k` is one
     * character of source and its row is named `'"k"'` — three. Reading the squiggle
     * length off the name is what made `duplicateStringNamedProperty1` squiggle `artist`
     * for eight characters.
     */
    @Test
    fun `the squiggle is the node's own width even when the name is the other member's`() {
        val d = diagnose("class C { \"k\": number = 1; k: string = ''; }", opts)
            .filter { it.code == 2300 }
        assert(d.size == 2)
        assert(d[0].length == 3) // "k"
        assert(d[1].length == 1) // k
    }

    /** TS2717 names the SYMBOL, not the written spelling — so it stays `'k'`, not `'"k"'`. */
    @Test
    fun `TS2717 beside it still names the plain symbol`() {
        val s = subsequent("class C { \"k\": number = 1; k: string = ''; }")
        assert(s == listOf(
            "Subsequent property declarations must have the same type.  " +
                "Property 'k' must be of type 'number', but here has type 'string'."
        ))
    }

    // ------------------------------------------- the TS6200 amalgamation is gone

    /**
     * tsc 6 collapsed a pair of files conflicting in EIGHT OR MORE names into one TS6200
     * ("Definitions of the following identifiers conflict with those in another file:
     * a, b, c") per file, plus a related TS6201. TypeScript 7 has neither diagnostic —
     * they appear nowhere in tsgo's source and in none of its baselines — so the
     * threshold is not raised, it is removed.
     *
     * Eight is the exact boundary, so this fixture is the first input the old code
     * collapsed. Measured against tsgo on the same two files: sixteen TS2300, eight per
     * file, and our output is row-for-row identical to it.
     */
    @Test
    fun `eight cross-file conflicts are sixteen TS2300 and no TS6200`() {
        val names = (1..8).map { "d$it" }
        val d = diagnose(
            buildString {
                appendLine("// @filename: f1.ts")
                appendLine("interface T {")
                names.forEach { appendLine("    $it: () => string;") }
                appendLine("}")
                appendLine("// @filename: f2.ts")
                appendLine("interface T {")
                names.forEach { appendLine("    $it(): number;") }
                appendLine("}")
            },
            opts,
        )
        assert(d.count { it.code == 2300 } == 16)
        assert(d.none { it.code == 6200 })
        assert(d.none { it.code == 6201 })
        // every conflicting name is named once per file, not amalgamated into a list
        assert(names.all { n -> d.count { it.code == 2300 && it.message == "Duplicate identifier '$n'." } == 2 })
    }

    /**
     * The control for the boundary: SEVEN names were already per-identifier under the old
     * threshold, so this case must be unchanged by the removal. Without it an ablation
     * restoring `if (pc.names.size < 8)` could only be seen by the eight-name pin, and a
     * threshold moved rather than removed would read as a pass.
     */
    @Test
    fun `seven cross-file conflicts were already per-identifier and stay so`() {
        val names = (1..7).map { "d$it" }
        val d = diagnose(
            buildString {
                appendLine("// @filename: f1.ts")
                appendLine("interface T {")
                names.forEach { appendLine("    $it: () => string;") }
                appendLine("}")
                appendLine("// @filename: f2.ts")
                appendLine("interface T {")
                names.forEach { appendLine("    $it(): number;") }
                appendLine("}")
            },
            opts,
        )
        assert(d.count { it.code == 2300 } == 14)
        assert(d.none { it.code == 6200 })
    }

    // ---------------------------------------------------------- negative controls

    @Test
    fun `negative control - a get set pair is legal and reports nothing`() {
        val src = "class C { get i(): number { return 0 } set i(v: number) {} }"
        assert(dup(src).isEmpty())
        assert(subsequent(src).isEmpty())
    }

    @Test
    fun `negative control - method overloads are legal and report nothing`() {
        val src = "class C { j(x: number): void; j(x: string): void; j(x: any): void {} }"
        assert(dup(src).isEmpty())
        assert(subsequent(src).isEmpty())
    }

    @Test
    fun `negative control - a static and an instance member of one name do not collide`() {
        val src = "class C { static h: number = 1; h: string = ''; }"
        assert(dup(src).isEmpty())
        assert(subsequent(src).isEmpty())
    }
}
