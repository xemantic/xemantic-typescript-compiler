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
 * (PARITY.1)(b-residue) and (b2) — the source-display generalization at the FOUR
 * remaining emitter positions, and the enum-member half of `getBaseTypeOfLiteralType`.
 *
 * `TypeDisplayParityTest` pins the DECLARATION and ASSIGNMENT displays that round
 * (P18.14) landed. A trace over the `Diagnostic` constructor censused the rest: the
 * three positions the queue item names are served by **six** emitters, not three —
 * `caasTailGatesAndRelation` (argument), `craElaborateReturnMismatch` (return),
 * `emitPerPropertyMismatchesForObjectLiteral` (an object-literal member in a variable
 * declaration), `caasObjLitPerPropertyMismatch` (an object literal passed as an
 * argument), `checkNestedObjLitPropTypes` (a NESTED object-literal member, and an
 * object literal in a `return`) and `checkRestArgsAgainstArrayElementType` (a rest
 * argument). All six now take the one shared
 * `Checker.relationErrorSourceDisplayType`.
 *
 * Every expected string below is transcribed from a measurement of BOTH
 * `tools/tsgo-7.0.2/lib/tsc` and pristine `typescript@6.0.3` over a scratch project,
 * which agree on every row here except one that is called out at its own pin.
 *
 * The rule, tsc's `reportRelationError`: generalize the source to its base type unless
 * the target could hold a top-level singleton (`never`, a literal, an enum, or a union
 * containing one of those). "Base type" is `getBaseTypeOfLiteralType`, whose ENUM arm is
 * measured and deliberately REFUSED here — `Checker.baseTypeOfLiteralType`'s KDoc carries
 * the measurement, the classification (FORM, not the MEANING the (P18.14) note recorded)
 * and the instrument that blocks it.
 */
class TypeDisplayParityPositionsTest {

    private val prelude = """
        interface Iface { x: number }
        enum One { X = 1, Y = 2 }
        enum Comp { A = 1, B = 2 }
        enum Other { Z = 1 }
        declare const sU: "a" | "b";
        declare const nU: 1 | 2;
        declare const mU: "a" | 1;
        declare const s1: "a";
        declare const em: One.X;
        declare const eu: One.X | Comp.B;
        declare function argNum(p: number): void;
        declare function argStr(p: string): void;
        declare function argNever(p: never): void;
        declare function argLit(p: "x" | "y"): void;
        declare function argIface(p: Iface): void;
        declare function argOne23(p: 123): void;
        declare function argRest(...p: number[]): void;
        declare function argObj(p: { m: number }): void;

    """.trimIndent()

    private fun only(source: String, code: Int): Diagnostic {
        val rows = diagnose(prelude + source).filter { it.code == code }
        assert(rows.size == 1)
        return rows[0]
    }

    // ------------------------------------------------------- ARGUMENT position

    @Test
    fun `an argument literal union collapses to its base primitive`() {
        assert(
            only("argNum(sU);", 2345).message ==
                "Argument of type 'string' is not assignable to parameter of type 'number'."
        )
    }

    @Test
    fun `an argument numeric literal union collapses to number`() {
        assert(
            only("argStr(nU);", 2345).message ==
                "Argument of type 'number' is not assignable to parameter of type 'string'."
        )
    }

    @Test
    fun `an argument mixed-base literal union collapses per constituent`() {
        assert(
            only("argNum(mU);", 2345).message ==
                "Argument of type 'string | number' is not assignable to parameter of type 'number'."
        )
    }

    @Test
    fun `an argument literal union collapses against a named interface parameter`() {
        assert(
            only("argIface(sU);", 2345).message ==
                "Argument of type 'string' is not assignable to parameter of type 'Iface'."
        )
    }

    @Test
    fun `a single-literal REFERENCE argument collapses too`() {
        assert(
            only("argNum(s1);", 2345).message ==
                "Argument of type 'string' is not assignable to parameter of type 'number'."
        )
    }

    @Test
    fun `negative control - a never parameter keeps the argument literal union`() {
        assert(
            only("argNever(sU);", 2345).message ==
                "Argument of type '\"a\" | \"b\"' is not assignable to parameter of type 'never'."
        )
    }

    @Test
    fun `negative control - a never parameter keeps a single argument literal`() {
        assert(
            only("argNever(s1);", 2345).message ==
                "Argument of type '\"a\"' is not assignable to parameter of type 'never'."
        )
    }

    @Test
    fun `negative control - a literal-union parameter keeps the argument literal union`() {
        assert(
            only("argLit(sU);", 2345).message ==
                "Argument of type '\"a\" | \"b\"' is not assignable to parameter of type '\"x\" | \"y\"'."
        )
    }

    @Test
    fun `negative control - a FRESH literal argument still widens against a base parameter`() {
        assert(
            only("argNum(true);", 2345).message ==
                "Argument of type 'boolean' is not assignable to parameter of type 'number'."
        )
    }

    @Test
    fun `negative control - a FRESH literal argument keeps its literal against a literal parameter`() {
        assert(
            only("argOne23(true);", 2345).message ==
                "Argument of type 'true' is not assignable to parameter of type '123'."
        )
    }

    @Test
    fun `a REST argument literal union collapses to its base primitive`() {
        assert(
            only("argRest(sU);", 2345).message ==
                "Argument of type 'string' is not assignable to parameter of type 'number'."
        )
    }

    // --------------------------------------------------------- RETURN position

    @Test
    fun `a returned literal union collapses to its base primitive`() {
        assert(
            only("function r(): number { return sU; }", 2322).message ==
                "Type 'string' is not assignable to type 'number'."
        )
    }

    @Test
    fun `a returned literal union collapses against a named interface`() {
        assert(
            only("function r(): Iface { return sU; }", 2322).message ==
                "Type 'string' is not assignable to type 'Iface'."
        )
    }

    @Test
    fun `negative control - a never return type keeps the returned literal union`() {
        assert(
            only("function r(): never { return sU; }", 2322).message ==
                "Type '\"a\" | \"b\"' is not assignable to type 'never'."
        )
    }

    @Test
    fun `a never return type keeps a single returned literal`() {
        // Pre-existing divergence, fixed by the same `never` guard: this read
        // `Type 'string'` before, where both reference compilers read the literal.
        assert(
            only("function r(): never { return s1; }", 2322).message ==
                "Type '\"a\"' is not assignable to type 'never'."
        )
    }

    // ------------------------------------------- OBJECT-LITERAL-MEMBER position

    @Test
    fun `an object-literal member literal union collapses to its base primitive`() {
        assert(
            only("const o: { m: number } = { m: sU };", 2322).message ==
                "Type 'string' is not assignable to type 'number'."
        )
    }

    @Test
    fun `a NESTED object-literal member literal union collapses too`() {
        assert(
            only("const o: { a: { m: number } } = { a: { m: sU } };", 2322).message ==
                "Type 'string' is not assignable to type 'number'."
        )
    }

    @Test
    fun `an object literal passed as an ARGUMENT collapses its member literal union`() {
        assert(
            only("argObj({ m: sU });", 2322).message ==
                "Type 'string' is not assignable to type 'number'."
        )
    }

    @Test
    fun `an object literal in a RETURN collapses its member literal union`() {
        assert(
            only("function r(): { m: number } { return { m: sU }; }", 2322).message ==
                "Type 'string' is not assignable to type 'number'."
        )
    }

    @Test
    fun `negative control - a never member target keeps the literal union`() {
        assert(
            only("const o: { m: never } = { m: sU };", 2322).message ==
                "Type '\"a\" | \"b\"' is not assignable to type 'never'."
        )
    }

    @Test
    fun `a never member target keeps a single literal`() {
        // The object-literal emitters had NO keep-guard at all before this round —
        // they widened unconditionally, so this read `Type 'string'`.
        assert(
            only("const o: { m: never } = { m: s1 };", 2322).message ==
                "Type '\"a\"' is not assignable to type 'never'."
        )
    }

    // ------------------------------- the enum-carrying UNION target keeps the source

    @Test
    fun `a union target holding an enum keeps a single literal source`() {
        // tsc's `typeCouldHaveTopLevelSingletonTypes` RECURSES through a union, and an
        // enum type is itself a union of its members there, so an enum-flavored
        // constituent suppresses the generalization. Ours had the union recursion in
        // `propTypeContainsLiteral` but no enum arm, so this widened to `'string'`;
        // tsgo 7.0.2 and pristine `typescript@6.0.3` both read `'"a"'`.
        assert(
            only("const x: boolean | One.Y = s1;", 2322).message ==
                "Type '\"a\"' is not assignable to type 'boolean | One.Y'."
        )
    }

    @Test
    fun `a union target holding a whole enum keeps a single literal source`() {
        assert(
            only("const x: boolean | One = s1;", 2322).message ==
                "Type '\"a\"' is not assignable to type 'boolean | One'."
        )
    }

    @Test
    fun `a union target holding an enum keeps a single literal ARGUMENT`() {
        assert(
            only("declare function argU(p: boolean | One.Y): void;\nargU(s1);", 2345).message ==
                "Argument of type '\"a\"' is not assignable to parameter of type 'boolean | One.Y'."
        )
    }

    // --------------------------- (b2) the ENUM-MEMBER arm is measured and REFUSED here

    @Test
    fun `negative control - an enum member source is unchanged by this round`() {
        // The enum half of `getBaseTypeOfLiteralType` is NOT wired — see
        // `Checker.baseTypeOfLiteralType`'s KDoc for the measurement and the reason.
        // This pin is a CONTROL that the round did not move it, not a pin of the
        // divergence: it asserts what BOTH this compiler and the (REL.2) enum-narrowing
        // probe families rely on today.
        assert(
            only("const d: string = em;", 2322).message ==
                "Type 'One.X' is not assignable to type 'string'."
        )
    }

    @Test
    fun `negative control - a never target keeps the enum member`() {
        assert(
            only("const n: never = em;", 2322).message ==
                "Type 'One.X' is not assignable to type 'never'."
        )
    }

    @Test
    fun `negative control - another enum target keeps the enum member`() {
        assert(
            only("const x: Other = em;", 2322).message ==
                "Type 'One.X' is not assignable to type 'Other'."
        )
    }
}
