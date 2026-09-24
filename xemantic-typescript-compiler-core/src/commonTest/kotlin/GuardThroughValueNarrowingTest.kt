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
 * (CHK.158), round P18.190 — a type guard REACHED THROUGH A VALUE narrows: a const alias
 * (`const g = isNum`, `const isArr = Array.isArray`), a destructured member
 * (`const { isArray } = Array` — rxjs `argsArgArrayOrObject.ts:14`), an object-literal property
 * (`u.isNum(x)`), a parameter typed by a guard ALIAS, a guard-typed annotation, and the body-local
 * forms of the first two. The flow resolver read the predicate off the callee's DECLARATION, and a
 * variable, a binding element or a property assignment carries none; tsgo reads it off the call's
 * effects SIGNATURE (flow.go `getEffectsSignature`), i.e. off the callee's TYPE, and so does
 * `Checker.predicateDeclFromCalleeSignature` now. The same signature path resolves an OVERLOAD set
 * (tsgo `getResolvedSignature` when several signatures exist), where the declaration path answered
 * the FIRST overload's predicate — both at the flow resolver and at B378's if-arm installer.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, measured over `build/scratch-p18190/pins` on the
 * identical text; the prelude is lines 1-4, so a one-line fixture is on line 5. Each probe is
 * chosen so that the narrowed type is PRINTED (`ps(v)` after a number guard), so an un-narrowed
 * binary reads `'string | number'` rather than silence.
 *
 * NOT PINNED (residues, unchanged): `asserts x is T` through an annotated variable (tsgo narrows,
 * we do not — the hot `flowCalleeMayHaveAssertEffects` gate is declaration-only) and TS2775 for an
 * un-annotated assertion alias; a GENERIC guard whose type parameter is inferred from ANOTHER
 * argument (`isT(v, 1)`, direct or through a variable); a `this is T` method on a union whose
 * members declare it separately.
 */
class GuardThroughValueNarrowingTest {

    private val prelude = """
        declare function pn(n: number): void;
        declare function ps(s: string): void;
        declare function isNum(x: unknown): x is number;
        declare const lib: { isNum(x: unknown): x is number; isNumP: (x: unknown) => x is number };

    """.trimIndent()

    /** Every row as `line:column code message`, sorted; a one-line fixture is on line 5. */
    private fun rows(source: String): List<String> =
        diagnose(prelude + source.trimIndent())
            .map { "${it.line}:${it.character} ${it.code} ${it.message}" }.sorted()

    @Test
    fun `a guard held in a const alias narrows`() {
        val actual = rows(
            """
            const g = isNum; export function f(v: number | string) { if (g(v)) ps(v); }
            """
        )
        val expected = listOf(
            "5:71 2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a guard read off a property into a const narrows`() {
        val actual = rows(
            """
            const g = lib.isNum; export function f(v: number | string) { if (g(v)) ps(v); }
            """
        )
        val expected = listOf(
            "5:75 2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a guard destructured with a rename narrows - rxjs argsArgArrayOrObject`() {
        val actual = rows(
            """
            const { isNum: g } = lib; export function f(v: number | string) { if (g(v)) ps(v); }
            """
        )
        val expected = listOf(
            "5:80 2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a guard destructured from a function-typed property narrows`() {
        val actual = rows(
            """
            const { isNumP } = lib; export function f(v: number | string) { if (isNumP(v)) ps(v); }
            """
        )
        val expected = listOf(
            "5:83 2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a guard held in an object literal property narrows through the property call`() {
        val actual = rows(
            """
            const u = { isNum }; export function f(v: number | string) { if (u.isNum(v)) ps(v); }
            """
        )
        val expected = listOf(
            "5:81 2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a parameter typed by a guard type alias narrows`() {
        val actual = rows(
            """
            type G = (x: unknown) => x is number; export function f(v: number | string, g: G) { if (g(v)) ps(v); }
            """
        )
        val expected = listOf(
            "5:98 2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a const annotated with a guard function type narrows`() {
        val actual = rows(
            """
            const g: (x: unknown) => x is number = isNum; export function f(v: number | string) { if (g(v)) ps(v); }
            """
        )
        val expected = listOf(
            "5:100 2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a body-local const alias of a guard narrows`() {
        val actual = rows(
            """
            export function f(v: number | string) { const g = isNum; if (g(v)) ps(v); }
            """
        )
        val expected = listOf(
            "5:71 2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a body-local destructured guard narrows`() {
        val actual = rows(
            """
            export function f(v: number | string) { const { isNum: g } = lib; if (g(v)) ps(v); }
            """
        )
        val expected = listOf(
            "5:80 2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `the else branch of a guard alias call narrows by the negation`() {
        val actual = rows(
            """
            const g = isNum; export function f(v: number | string) { if (g(v)) {} else pn(v); }
            """
        )
        val expected = listOf(
            "5:79 2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a negated guard alias with an early return narrows the rest of the body`() {
        val actual = rows(
            """
            export function f(v: number | string) { const g = isNum; if (!g(v)) return; ps(v); }
            """
        )
        val expected = listOf(
            "5:80 2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a guard alias narrows a member-access receiver in a conditional`() {
        val actual = rows(
            """
            declare function isNB(x: unknown): x is number | boolean; const g = isNB; export function f(v: number | boolean | string) { return g(v) ? v.toUpperCase() : 0; }
            """
        )
        val expected = listOf(
            "5:141 2339 Property 'toUpperCase' does not exist on type 'number | boolean'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `an overloaded guard held in a const narrows by the resolved overload`() {
        val actual = rows(
            """
            declare function isX(x: unknown): x is number; declare function isX(x: unknown, y: number): x is string; const g = isX; export function f(v: number | string | boolean) { if (g(v, 1)) pn(v); }
            """
        )
        val expected = listOf(
            "5:187 2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a direct overload call resolving to a non-guard member does not narrow`() {
        val actual = rows(
            """
            declare function isY(x: unknown): x is number; declare function isY(x: unknown, y: number): boolean; export function f(v: number | string) { if (isY(v, 1)) pn(v); }
            """
        )
        val expected = listOf(
            "5:160 2345 Argument of type 'string | number' is not assignable to parameter of type 'number'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a direct overload call narrows by the resolved member predicate, not the first`() {
        val actual = rows(
            """
            declare function isX(x: unknown): x is number; declare function isX(x: unknown, y: number): x is string; export function f(v: number | string | boolean) { if (isX(v, 1)) pn(v); }
            """
        )
        val expected = listOf(
            "5:174 2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `negative control - a const alias of a non-guard function does not narrow`() {
        val actual = rows(
            """
            declare function notGuard(x: unknown): boolean; const g = notGuard; export function f(v: number | string) { if (g(v)) ps(v); }
            """
        )
        val expected = listOf(
            "5:122 2345 Argument of type 'string | number' is not assignable to parameter of type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `the rxjs object-literal member reader sees the destructured guard narrowing`() {
        val actual = rows(
            """
            const { isNum: g } = lib; export function f(v: number | string) { if (g(v)) { const r: { a: number } = { a: v }; } }
            """
        )
        val expected = emptyList<String>()
        assert(actual == expected)
    }

    @Test
    fun `a guard alias narrows at the assignment reader`() {
        val actual = rows(
            """
            const g = isNum; export function f(v: number | string) { let r = 0; if (g(v)) { r = v; } return r; }
            """
        )
        val expected = emptyList<String>()
        assert(actual == expected)
    }

    @Test
    fun `a guard alias narrows at the return reader`() {
        val actual = rows(
            """
            const g = isNum; export function f(v: number | string): number { if (g(v)) return v; return 0; }
            """
        )
        val expected = emptyList<String>()
        assert(actual == expected)
    }
}
