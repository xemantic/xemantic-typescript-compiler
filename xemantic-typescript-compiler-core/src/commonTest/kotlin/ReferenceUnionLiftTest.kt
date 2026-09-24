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
 * (CHK.167) round 1, round P18.192 — a NON-NULLISH UNION source whose every constituent the gate admits
 * one at a time is related to an OBJECT-family target at the DECLARATION, ASSIGNMENT and
 * PROPERTY-ASSIGNMENT readers when the source is an identifier or a property access
 * (`Checker.canUseTypeEngineReferenceUnionLift`). Before it `canUseTypeEngine` refused every union ->
 * object pair, so `const r: number[] = x` with `x: number[] | string` was silent where tsgo 7.0.2 reports
 * TS2322. In the same round three flow gaps that the opening would have turned into false positives
 * were closed: a declaration's initializer now narrows (`let v: U = []`,
 * `Checker.declarationInitializerReducedType`), `x === kk` against an object-typed reference drops the
 * primitive constituents (`Checker.objectValueEqualityNarrow`), an assignment whose value does not
 * relate to a stale antecedent reduces the declared union (`Checker.assignmentReduceBase`), and the
 * anonymous-object narrowing clause of the two readers covers object-carrying unions.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, measured over `build/scratch-p18192/pins` on the identical
 * text less the three `// @` directive lines the harness strips, so the prelude is lines 1-5 and a
 * one-line fixture is on line 6. A row is `line:column code message` with its chain appended as
 * ` / <line>`.
 *
 * RESIDUES (measured, pinned as `residue - …` so a later round recognises the countdown): the RETURN and
 * ARGUMENT readers (round 2), a call / conditional / `||` / chained-assignment source (refused by
 * construction), a nullish union (round 3), an array against a tuple (refused by design). DISPLAY
 * residues, asserted by head line only: where the failing constituent is an OBJECT, tsgo prints that
 * constituent's own elaboration (`Property 'k' is missing in type 'L' but required in type 'K'.`) where
 * we print `Type 'L' is not assignable to type 'K'.`; the property-assignment reader prints no union chain
 * at all; and a flow-narrowed source that still fails is displayed by its DECLARED type
 * (`Type 'number | K'`) where tsgo prints the narrowed one (`Type 'number'`).
 */
class ReferenceUnionLiftTest {

    private val prelude = """
        // @useRealLibs: true
        // @strict: true
        // @target: es2020
        interface K { k: 1 }
        interface L { l: 2 }
        declare const kk: K;
        declare function mk(): K | L;
        declare const c: boolean;

    """.trimIndent()

    private fun compile(source: String): List<Diagnostic> =
        diagnose(prelude + source.trimIndent(), directives = "")

    /** Every row as `line:column code message / chain…`, sorted. */
    private fun rows(source: String): List<String> =
        compile(source).map { d ->
            "${d.line}:${d.character} ${d.code} ${d.message}" +
                d.messageChain.joinToString("") { " / " + it.trim() }
        }.sorted()

    /** Every row as `line:column code message` — head line only (for the display residues). */
    private fun heads(source: String): List<String> =
        compile(source).map { "${it.line}:${it.character} ${it.code} ${it.message}" }.sorted()

    // --- the opening: the three readers, reference sources -------------------------------------

    @Test
    fun `a declaration relates a string or array union to an array target`() {
        val actual = rows("function f(x: number[] | string) { const r: number[] = x; }")
        val expected = listOf(
            "6:42 2322 Type 'string | number[]' is not assignable to type 'number[]'. / " +
                "Type 'string' is not assignable to type 'number[]'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a declaration relates an interface union to an interface target`() {
        val actual = heads("function f(x: K | L) { const r: K = x; }")
        val expected = listOf("6:30 2322 Type 'K | L' is not assignable to type 'K'.")
        assert(actual == expected)
    }

    @Test
    fun `a top-level declaration relates a declared union to an array target`() {
        val actual = rows(
            """
            declare const g: number[] | string;
            const r: number[] = g;
            """
        )
        val expected = listOf(
            "7:7 2322 Type 'string | number[]' is not assignable to type 'number[]'. / " +
                "Type 'string' is not assignable to type 'number[]'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `an assignment relates a union to an array target`() {
        val actual = rows("function f(x: number[] | string) { let y: number[]; y = x; y; }")
        val expected = listOf(
            "6:53 2322 Type 'string | number[]' is not assignable to type 'number[]'. / " +
                "Type 'string' is not assignable to type 'number[]'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a property assignment relates a union to an array target`() {
        val actual = heads("function f(x: number[] | string, h: { p: number[] }) { h.p = x; }")
        val expected = listOf("6:56 2322 Type 'string | number[]' is not assignable to type 'number[]'.")
        assert(actual == expected)
    }

    @Test
    fun `a property-access source is related as an identifier is`() {
        val actual = rows("function f(o: { v: number[] | string }) { const r: number[] = o.v; }")
        val expected = listOf(
            "6:49 2322 Type 'string | number[]' is not assignable to type 'number[]'. / " +
                "Type 'string' is not assignable to type 'number[]'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `negative control - a union target is unchanged and relates`() {
        val actual = rows("function f(a: K | L, h: { p: K | L }) { const r: K | L = a; h.p = a; }")
        assert(actual.isEmpty())
    }

    // --- narrowed at the site: must stay silent ------------------------------------------------

    @Test
    fun `negative control - typeof narrowing reaches all three readers`() {
        val actual = rows(
            "function f(x: number[] | string) { if (typeof x !== \"string\") { " +
                "const r: number[] = x; let y: number[]; y = x; const h: { p: number[] } = { p: [] }; h.p = x; } }"
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `negative control - a discriminant narrows an anonymous-object union at declaration and assignment`() {
        val actual = rows(
            "function f(x: { kind: \"a\"; a: 1 } | { kind: \"b\"; b: 1 }) { if (x.kind === \"a\") { " +
                "const r: { kind: \"a\"; a: 1 } = x; let y: { kind: \"a\"; a: 1 }; y = x; y; } }"
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `negative control - an in-operator narrows an anonymous-object union at declaration`() {
        val actual = rows(
            "function f(x: { kind: \"a\"; a: 1 } | { kind: \"b\"; b: 1 }) { if (\"a\" in x) { " +
                "const r: { kind: \"a\"; a: 1 } = x; } }"
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `negative control - an array-literal initializer narrows a declared union`() {
        val actual = rows(
            "function f() { let v: number[] | string = []; const r: number[] = v; let y: number[]; y = v; y; }"
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `negative control - an identifier initializer narrows a declared union`() {
        val actual = rows("function f() { let v: K | number = kk; const r: K = v; }")
        assert(actual.isEmpty())
    }

    @Test
    fun `negative control - an object-literal initializer narrows a declared union`() {
        val actual = rows("function f() { let v: K | number = { k: 1 }; const r: K = v; }")
        assert(actual.isEmpty())
    }

    @Test
    fun `an object-literal initializer narrows a declared union to its object constituent`() {
        val actual = rows("function f() { let v: K | number = { k: 1 }; const r: number = v; }")
        assert(actual == listOf("6:52 2322 Type 'K' is not assignable to type 'number'."))
    }

    @Test
    fun `an object-literal initializer keeps an object keyword constituent`() {
        val actual = rows("function f() { let v: object | number = { k: 1 }; const r: number = v; }")
        assert(actual == listOf("6:57 2322 Type 'object' is not assignable to type 'number'."))
    }

    @Test
    fun `negative control - a reassignment overwrites a stale literal narrowing`() {
        val actual = rows("function f() { let v: K | number = 1; v = kk; const r: K = v; }")
        assert(actual.isEmpty())
    }

    @Test
    fun `a reassignment back to a primitive narrows to the primitive`() {
        // tsgo: `6:74 2322 Type 'number' is not assignable to type 'K'.` — ours displays the DECLARED
        // type (the flow type fails the relation, so the suppression-only narrowing keeps the raw one).
        val actual = compile("function f() { let v: K | number = kk; v = 1; const r: number = v; const q: K = v; }")
            .map { "${it.line}:${it.character} ${it.code}" }
        assert(actual == listOf("6:74 2322"))
    }

    @Test
    fun `negative control - strict equality with an object reference narrows away the primitive`() {
        val actual = rows("function f(x: K | number) { if (x === kk) { const r: K = x; let y: K; y = x; y; } }")
        assert(actual.isEmpty())
    }

    @Test
    fun `negative control - loose equality with an object reference narrows as strict does`() {
        val actual = rows("function f(x: K | number) { if (x == kk) { const r: K = x; } }")
        assert(actual.isEmpty())
    }

    @Test
    fun `equality narrowing keeps an object keyword constituent`() {
        val actual = rows("function f(x: object | number) { if (x === kk) { const r: number = x; } }")
        assert(actual == listOf("6:56 2322 Type 'object' is not assignable to type 'number'."))
    }

    @Test
    fun `equality narrowing keeps a primitive the object type accepts`() {
        val actual = rows(
            """
            interface HL { length: number }
            declare const hl: HL;
            function f(x: HL | number | string) { if (x === hl) { const r: number = x; } }
            """
        )
        val expected = listOf(
            "8:61 2322 Type 'string | HL' is not assignable to type 'number'. / " +
                "Type 'string' is not assignable to type 'number'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `an inequality does not narrow its then branch`() {
        val actual = rows("function f(x: K | number) { if (x !== kk) { const r: K = x; } else { const q: K = x; } }")
        val expected = listOf(
            "6:51 2322 Type 'number | K' is not assignable to type 'K'. / Type 'number' is not assignable to type 'K'.",
        )
        assert(actual == expected)
    }

    // --- still refused this round: tsgo reports, we are silent (countdowns) --------------------

    @Test
    fun `residue - a call source stays refused`() {
        // tsgo: 6:22 2322 Type 'K | L' is not assignable to type 'K'.
        assert(rows("function f() { const r: K = mk(); }").isEmpty())
    }

    @Test
    fun `residue - a conditional source stays refused`() {
        // tsgo: 6:32 2322 Type 'K | L' is not assignable to type 'K'.
        assert(rows("function f(a: K, b: L) { const r: K = c ? a : b; }").isEmpty())
    }

    @Test
    fun `residue - a logical-or source stays refused`() {
        // tsgo: 6:30 2322 Type 'K | L' is not assignable to type 'K'.
        assert(rows("function f(a: K | L) { const r: K = a || kk; }").isEmpty())
    }

    @Test
    fun `residue - a chained assignment source stays refused`() {
        // tsgo: 6:48 2322 Type 'K | L' is not assignable to type 'K'.
        assert(rows("function f(a: K | L) { let y: K; let z: K | L; y = z = a; y; }").isEmpty())
    }

    @Test
    fun `residue - a nullish union stays refused at declaration`() {
        // tsgo: 6:33 2322 Type 'K | null' is not assignable to type 'K'. / Type 'null' is not assignable to type 'K'.
        assert(rows("function f(a: K | null) { const r: K = a; }").isEmpty())
    }

    @Test
    fun `residue - a nullish union stays refused at property assignment`() {
        // tsgo: 6:45 2322 Type 'K | undefined' is not assignable to type 'K'. / Type 'undefined' is not assignable to type 'K'.
        assert(rows("function f(a: K | undefined, h: { p: K }) { h.p = a; }").isEmpty())
    }

    @Test
    fun `residue - the return reader is not opened`() {
        // tsgo: 6:27 2322 Type 'K | L' is not assignable to type 'K'.
        assert(rows("function f(a: K | L): K { return a; }").isEmpty())
    }

    @Test
    fun `residue - the argument reader is not opened`() {
        // tsgo: 7:29 2345 Argument of type 'K | L' is not assignable to parameter of type 'K'.
        val actual = rows(
            """
            declare function take(p: K): void;
            function f(a: K | L) { take(a); }
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `residue - an array constituent against a tuple target stays refused`() {
        // tsgo: 6:52 2322 Type 'number[] | [number, number]' is not assignable to type '[number, number]'.
        assert(rows("function f(a: number[] | [number, number]) { const r: [number, number] = a; }").isEmpty())
    }
}
