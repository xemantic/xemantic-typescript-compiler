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
 * (CHK.94) — a TUPLE's `Array` members get TYPES and its calls get checked. Every row
 * was reproduced against pristine `typescript@6.0.3` AND tsgo 7.0.2 before any code was
 * written (`scratchpad/chk94/a_*`, `c_*` — 90 + 38 cells — and `chk94-impl/p`); the two
 * references agree on every row bar the ORDER of a tuple's element union, which pristine
 * prints `(2 | 1)[]` and tsgo `(1 | 2)[]` (FORM only). Ours orders as tsgo, and that is
 * what the pins below spell.
 *
 * The mechanism (`Checker.tupleArrayBase`, tsc's `getTupleBaseType` inherited by
 * `resolveObjectTypeMembers`): a tuple's own member table holds only its numbered slots
 * and `length`, so every `Array<T>` member read on it answered `any` — `t.slice(1)`,
 * `t.map`, `t.push(3)`, `t.indexOf("x")` were all silent, and a callback parameter was
 * never contextually typed. The base is an INTERNED `Array<union of elements>` (or
 * `ReadonlyArray<…>` for a readonly / const tuple), consulted on the MISS path only, at
 * [computeRawTypeOfPropertyAccess] (a plain receiver) and [resolveMemberPropertyType] (a
 * union constituent or a flow-narrowed receiver); the member's type comes through the
 * same `resolveGenericPropertyType` instantiation `arr.push` on `number[]` takes, so the
 * call path, overload selection, argument checking and callback contextual typing follow
 * with no further edit. A REST slot is stored as the rest's ARRAY type and is INDEXED into
 * the base (`[number, ...string[]]` → `Array<string | number>`); a rest tuple's `length`
 * is `number`; an OPTIONAL slot joins `undefined` (strictNullChecks); an empty tuple's
 * base is `Array<never>`; a rest slot that is not an array-like (`...T`) refuses the base.
 *
 * Two knock-ons the matrix found, each a PRE-EXISTING false positive on arrays that the
 * typed tuple members inherited: an array-literal argument against a literal-union
 * element (`t.concat([1])` against `ConcatArray<1 | 2>`) was passed over by every
 * overload — `arrayLitLiteralElemsSatisfyParam`, at both overload-acceptance sites; and a
 * union callee whose generic members have IDENTICAL type parameters (`Array<1>.map` beside
 * `Array<1 | 2>.map`) reported TS2349 "none of those signatures are compatible" where tsc
 * combines them — `unionCalleeGenericSignaturesIncompatible` keeps TS2349 for the genuinely
 * incompatible pair (`betterErrorForUnionCall`) and answers silence otherwise.
 *
 * RECORDED residues (all pre-existing on plain arrays, measured on the HEAD binary): the
 * argument display prints the widened `'number'` where tsc prints `'3'` (`a.push(3)` on a
 * `(1 | 2)[]` prints the same), so the TS2345 pins assert the PARAMETER half, the code and
 * the position; a call on a UNION receiver (`[1] | [1, 2]`, and `1[] | (1 | 2)[]` alike)
 * resolves the member but its RESULT is `any` (the union-of-callables return is not
 * combined); `this.t.push(3)` on a class property is silent ((CHK.46), `number[]` too);
 * `t.sort()` answers `any` (`this` return, `number[]` too); `t.filter(x => x === 1)` is
 * `(1 | 2)[]` where tsc infers the predicate `1[]`; TS2769's position sits on the array
 * literal where tsc's sits on its element; `const [a, b] = t` and `t[5]` (TS2493) are
 * out of scope.
 */
class TupleArrayMembersTest {

    private val prelude = """
        declare const mt: [1, 2]
        declare const rt: readonly [1, 2]
        declare const st: [1, "a"]
        declare const nt: [a: 1, b: 2]
        declare const rest: [number, ...string[]]
        declare const opt: [1, 2?]
        declare const et: []
        const ct = [1, 2] as const
        export {}
    """.trimIndent() + "\n"

    /** 0-based line of the first line appended after [prelude] (line 0 is the directive). */
    private val rowLine = prelude.count { it == '\n' } + 1

    private fun diags(source: String): List<Diagnostic> = diagnose(prelude + source)

    private fun messages(source: String): List<String> = diags(source).map { it.message }

    private fun col(source: String, needle: String): Int = source.indexOf(needle) + 1

    private fun notBoolean(type: String) = "Type '$type' is not assignable to type 'boolean'."

    /** The PARAMETER half of a TS2345 (the argument display is a recorded residue, see the KDoc). */
    private fun paramOf(d: Diagnostic): String = d.message.substringAfter(" is not assignable to parameter of type ")

    // ---------------------------------------------------------------------
    // Array members on a mutable tuple: result types
    // ---------------------------------------------------------------------

    @Test
    fun `slice on a tuple is the element-union array in tsgo's order`() {
        val src = "const r: boolean = mt.slice(1)"
        val d = diags(src)
        assert(d.map { it.message } == listOf(notBoolean("(1 | 2)[]")))
        assert(d[0].code == 2322)
        assert(d[0].line == rowLine)
        assert(d[0].character == col(src, "r:"))
    }

    @Test
    fun `map on a tuple instantiates the callback's U from the identity`() {
        assert(messages("const r: boolean = mt.map(x => x)") == listOf(notBoolean("(1 | 2)[]")))
    }

    @Test
    fun `map's callback return drives the result`() {
        // `String(x)` needs the real lib's `StringConstructor` call signature.
        val d = diagnose(prelude + "const r: boolean = mt.map(x => String(x))", directives = REAL_LIBS)
        assert(d.map { it.message } == listOf(notBoolean("string[]")))
    }

    @Test
    fun `join on a tuple is a string`() {
        assert(messages("const r: boolean = mt.join(\",\")") == listOf(notBoolean("string")))
    }

    @Test
    fun `indexOf on a tuple returns number and checks its argument`() {
        val src = "const r: boolean = mt.indexOf(\"x\")"
        val d = diags(src).sortedBy { it.character }
        assert(d.size == 2)
        assert(d[0].code == 2322 && d[0].message == notBoolean("number"))
        assert(d[1].code == 2345 && paramOf(d[1]) == "'1 | 2'.")
        assert(d[1].character == col(src, "\"x\""))
    }

    @Test
    fun `reduce on a tuple is the element type`() {
        assert(messages("const r: boolean = mt.reduce((a, b) => a + b, 0)") == listOf(notBoolean("number")))
    }

    @Test
    fun `a mixed tuple's base unions its elements`() {
        assert(messages("const r: boolean = st.slice(1)") == listOf(notBoolean("(\"a\" | 1)[]")))
    }

    @Test
    fun `a named tuple has the same base`() {
        assert(messages("const r: boolean = nt.slice(1)") == listOf(notBoolean("(1 | 2)[]")))
    }

    @Test
    fun `an annotated literal tuple is the same receiver`() {
        assert(messages("const t2: [1, 2] = [1, 2]; const r: boolean = t2.slice(1)") == listOf(notBoolean("(1 | 2)[]")))
    }

    @Test
    fun `a non-literal tuple's base is the primitive array`() {
        assert(messages("const t2: [number, number] = [1, 2]; const r: boolean = t2.slice(1)") == listOf(notBoolean("number[]")))
    }

    // ---------------------------------------------------------------------
    // callback contextual typing
    // ---------------------------------------------------------------------

    @Test
    fun `forEach's callback parameter is the element union`() {
        val src = "mt.forEach(x => { const r: boolean = x })"
        val d = diags(src)
        assert(d.map { it.message } == listOf(notBoolean("number")))
        assert(d[0].character == col(src, "r:"))
    }

    @Test
    fun `forEach's callback parameter on a mixed tuple`() {
        assert(messages("st.forEach(x => { const r: boolean = x })") == listOf(notBoolean("string | number")))
    }

    @Test
    fun `map's callback parameter is the element union`() {
        assert(messages("mt.map(x => { const r: boolean = x; return x })") == listOf(notBoolean("number")))
    }

    // ---------------------------------------------------------------------
    // argument checking through the base
    // ---------------------------------------------------------------------

    @Test
    fun `push of a foreign number is TS2345 against the element union`() {
        val src = "mt.push(3)"
        val d = diags(src)
        assert(d.size == 1)
        assert(d[0].code == 2345)
        assert(paramOf(d[0]) == "'1 | 2'.")
        assert(d[0].line == rowLine)
        assert(d[0].character == col(src, "3"))
    }

    @Test
    fun `push of a string is TS2345 against the element union`() {
        val d = diags("mt.push(\"z\")")
        assert(d.size == 1 && d[0].code == 2345 && paramOf(d[0]) == "'1 | 2'.")
    }

    @Test
    fun `includes checks its argument`() {
        val d = diags("mt.includes(3)")
        assert(d.size == 1 && d[0].code == 2345 && paramOf(d[0]) == "'1 | 2'.")
    }

    @Test
    fun `a tuple parameter's members are checked inside the body`() {
        val src = "function zf(t: [1, 2]) { t.push(3) }"
        val d = diags(src)
        assert(d.size == 1 && d[0].code == 2345 && paramOf(d[0]) == "'1 | 2'.")
        assert(d[0].character == col(src, "3"))
    }

    @Test
    fun `a flow-narrowed tuple receiver reads the base`() {
        val src = "declare const m: [1, 2] | undefined; if (m) { m.push(3) }"
        val d = diags(src)
        assert(d.size == 1 && d[0].code == 2345 && paramOf(d[0]) == "'1 | 2'.")
        assert(d[0].character == col(src, "3"))
    }

    // ---------------------------------------------------------------------
    // readonly and const tuples fall to ReadonlyArray
    // ---------------------------------------------------------------------

    @Test
    fun `a readonly tuple's slice is typed through ReadonlyArray`() {
        assert(messages("const r: boolean = rt.slice(1)") == listOf(notBoolean("(1 | 2)[]")))
    }

    @Test
    fun `a readonly tuple's indexOf checks its argument`() {
        val d = diags("rt.indexOf(\"x\")")
        assert(d.size == 1 && d[0].code == 2345 && paramOf(d[0]) == "'1 | 2'.")
    }

    @Test
    fun `a const tuple's map is typed through ReadonlyArray`() {
        assert(messages("const r: boolean = ct.map(x => x)") == listOf(notBoolean("(1 | 2)[]")))
    }

    @Test
    fun `a readonly tuple still has no push`() {
        assert(messages("rt.push(3)") == listOf("Property 'push' does not exist on type 'readonly [1, 2]'."))
    }

    @Test
    fun `a const tuple still has no push`() {
        assert(messages("ct.push(3)") == listOf("Property 'push' does not exist on type 'readonly [1, 2]'."))
    }

    // ---------------------------------------------------------------------
    // rest, optional, empty, generic
    // ---------------------------------------------------------------------

    @Test
    fun `a rest tuple's base indexes the rest slot`() {
        assert(messages("const r: boolean = rest.slice(1)") == listOf(notBoolean("(string | number)[]")))
    }

    @Test
    fun `a rest tuple's indexOf takes the indexed union and returns number`() {
        val src = "const r: boolean = rest.indexOf(\"x\")"
        assert(messages(src) == listOf(notBoolean("number")))
    }

    @Test
    fun `a rest tuple's push refuses a foreign type and keeps both element kinds`() {
        val src = "rest.push(true); rest.push(\"s\"); rest.push(2)"
        val d = diags(src)
        assert(d.size == 1 && d[0].code == 2345 && paramOf(d[0]) == "'string | number'.")
        assert(d[0].character == col(src, "true"))
    }

    @Test
    fun `a leading rest tuple indexes its rest slot too`() {
        val d = diags("declare const lr: [...string[], number]; lr.push(true)")
        assert(d.size == 1 && d[0].code == 2345 && paramOf(d[0]) == "'string | number'.")
    }

    @Test
    fun `a named rest tuple is a rest tuple`() {
        assert(messages("declare const nr: [n: number, ...r: string[]]; const q: boolean = nr.indexOf(\"x\")") == listOf(notBoolean("number")))
    }

    @Test
    fun `a rest tuple's length is number`() {
        assert(messages("const r: 3 = rest.length") == listOf("Type 'number' is not assignable to type '3'."))
    }

    @Test
    fun `negative control - a fixed tuple's length stays literal`() {
        assert(messages("const r: 3 = mt.length") == listOf("Type '2' is not assignable to type '3'."))
    }

    @Test
    fun `a rest slot that is not an array-like refuses the base`() {
        // `...T` on a type parameter: tsc's base is `(number | T[number])[]`; ours has no
        // indexed-access type for it and answers `any` (silence) rather than a wrong base.
        assert(diags("function zg<T extends unknown[]>(t: [number, ...T]) { const r: boolean = t.slice(1) }").isEmpty())
    }

    @Test
    fun `an optional slot joins undefined to the base`() {
        assert(messages("const r: boolean = opt.slice(1)") == listOf(notBoolean("(1 | 2 | undefined)[]")))
    }

    @Test
    fun `an optional slot's push accepts undefined and refuses a foreign number`() {
        val src = "opt.push(undefined); opt.push(3)"
        val d = diags(src)
        assert(d.size == 1 && d[0].code == 2345 && paramOf(d[0]) == "'1 | 2 | undefined'.")
        assert(d[0].character == col(src, "3"))
    }

    @Test
    fun `an empty tuple's base is never array`() {
        val src = "const r: boolean = et.slice(); et.push(1)"
        val d = diags(src).sortedBy { it.character }
        assert(d.size == 2)
        assert(d[0].message == notBoolean("never[]"))
        assert(d[1].code == 2345 && paramOf(d[1]) == "'never'.")
    }

    @Test
    fun `a generic tuple's slice is the parameter array`() {
        assert(messages("function zh<T>(t: [T, T]) { const r: boolean = t.slice(1) }") == listOf(notBoolean("T[]")))
    }

    // ---------------------------------------------------------------------
    // the overload rescue for an array-literal argument
    // ---------------------------------------------------------------------

    // `ConcatArray<T>` is the real lib's (`Array.concat`'s parameter); the embedded lib has
    // none, and a hand-written number-index interface is accepted by `number[]` here
    // (a pre-existing relation leniency), so these pins run under the real libs.
    private val REAL_LIBS = "// @strict: true\n// @useRealLibs: true"

    private val overloads = "declare function zo(xs: ConcatArray<1 | 2>): number; declare function zo(xs: string): string\n"

    @Test
    fun `an array literal of literals selects a literal-index overload`() {
        assert(diagnose(prelude + overloads + "const r: number = zo([1])", directives = REAL_LIBS).isEmpty())
    }

    @Test
    fun `negative control - an array literal outside the literal union still fails every overload`() {
        val d = diagnose(prelude + overloads + "zo([3])", directives = REAL_LIBS)
        assert(d.map { it.code } == listOf(2769))
    }

    @Test
    fun `negative control - a widened array variable is not rescued`() {
        val d = diagnose(prelude + overloads + "const xs = [1]; zo(xs)", directives = REAL_LIBS)
        assert(d.map { it.code } == listOf(2769))
    }

    @Test
    fun `concat of a literal array on a tuple is silent under the real lib`() {
        val d = diagnose(prelude + "const c: (1 | 2)[] = mt.concat([1])", directives = REAL_LIBS)
        assert(d.isEmpty())
    }

    @Test
    fun `concat of a literal array on a literal-union array is silent under the real lib`() {
        val d = diagnose(prelude + "declare const la: (1 | 2)[]; const c: (1 | 2)[] = la.concat([1])", directives = REAL_LIBS)
        assert(d.isEmpty())
    }

    @Test
    fun `concat of a foreign literal array on a tuple is refused under the real lib`() {
        val d = diagnose(prelude + "const c: number[] = mt.concat([9])", directives = REAL_LIBS)
        assert(d.map { it.code } == listOf(2769))
    }

    // ---------------------------------------------------------------------
    // union receivers and the union-callee refinement
    // ---------------------------------------------------------------------

    @Test
    fun `a union of tuples resolves a generic member without TS2349`() {
        // The RESULT of the call is `any` (recorded residue: a union of callables is not
        // combined) — what is pinned is that the base gives the union a member and the
        // refinement keeps tsc's combination from reading as "not callable".
        assert(diags("declare const u: [1] | [1, 2]; const r: boolean = u.map(x => x)").isEmpty())
    }

    @Test
    fun `a union of arrays with identical generic members is not TS2349`() {
        assert(diags("declare const u: 1[] | (1 | 2)[]; const r: boolean = u.map(x => x)").isEmpty())
    }

    /**
     * (CHK.97) the union is now COMBINED, so the rest position carries
     * `Array<number & string>` = `never[]` and the argument is checked against its
     * ELEMENT — `Argument of type '1' is not assignable to parameter of type 'never'.`
     * on tsgo 7.0.2 and pristine `typescript@6.0.3` alike. What this pin still owns is
     * that the union is not TS2349; the silence it asserted was the pre-combination
     * state, not the reference answer.
     */
    @Test
    fun `a union of rest-parameter callables is not TS2349 - its element intersects to never`() {
        val d = diags("declare const u: ((...xs: number[]) => void) | ((...xs: string[]) => void); u(1)")
        assert(d.map { it.code to it.message } == listOf(
            2345 to "Argument of type '1' is not assignable to parameter of type 'never'."
        ))
    }

    @Test
    fun `negative control - generic members with different constraints stay TS2349`() {
        val d = diags("declare const u: (<T extends number>(a: T) => void) | (<T>(a: string) => void); u(\"\")")
        assert(d.map { it.code } == listOf(2349))
        assert(d[0].message == "This expression is not callable.")
    }

    @Test
    fun `negative control - generic members with different counts stay TS2349`() {
        val d = diags("declare const u: (<T>(x: T) => T) | (<T, U>(x: T) => U); u(1)")
        assert(d.map { it.code } == listOf(2349))
    }

    @Test
    fun `a union of tuples combines a non-generic member's parameters`() {
        // tsc's `combineSignaturesOfUnionMembers` intersects `indexOf`'s parameter across
        // `Array<1>` and `Array<1 | 2>` (B516's combinable case here), so the argument is
        // judged against `1` — this is the one shape on which the union constituent's
        // member TYPE (the `resolveMemberPropertyType` site) is observable.
        val src = "declare const u: [1] | [1, 2]; u.indexOf(\"x\"); u.indexOf(2); u.indexOf(1)"
        val d = diags(src).sortedBy { it.character }
        assert(d.size == 2)
        assert(d.all { it.code == 2345 && paramOf(it) == "'1'." })
        assert(d[0].character == col(src, "\"x\""))
        assert(d[1].character == col(src, "indexOf(2)") + "indexOf(".length)
    }

    @Test
    fun `a union of arrays combines a non-generic member's parameters the same way`() {
        // The same B516 path on plain arrays printed `1 & 1 | 2` and rejected `u.indexOf(1)`
        // before this round (a pre-existing defect the typed tuple members made reachable).
        val src = "declare const u: 1[] | (1 | 2)[]; u.indexOf(\"x\"); u.indexOf(2); u.indexOf(1)"
        val d = diags(src)
        assert(d.size == 2)
        assert(d.all { it.code == 2345 && paramOf(it) == "'1'." })
    }

    @Test
    fun `a union member's slots and length are untouched`() {
        val d = diags("declare const u: [1] | [1, 2]; const r: boolean = u.length; const q: boolean = u[0]")
        assert(d.map { it.message } == listOf(notBoolean("number"), notBoolean("number")))
    }

    // ---------------------------------------------------------------------
    // guards: legal calls stay silent
    // ---------------------------------------------------------------------

    @Test
    fun `negative control - legal tuple method calls are silent`() {
        assert(diags("const zs: boolean = mt.some(x => x === 1); const zj: string = mt.join(\",\"); " +
            "mt.forEach(x => { const n: number = x }); const zc: (1 | 2)[] = mt.concat([1]); " +
            "declare function zg2(...xs: number[]): void; zg2(...mt); const zl: number = mt.length; " +
            "const z0: 1 = mt[0]; for (const e of mt) { const n2: number = e }").isEmpty())
    }

    @Test
    fun `negative control - an array literal of tuple slots is an array`() {
        val src = "declare const p: [number, number]; [p[0], p[1]].forEach(x => { const r: boolean = x })"
        assert(messages(src) == listOf(notBoolean("number")))
    }

    @Test
    fun `negative control - a member on neither the tuple nor Array is still TS2339`() {
        assert(messages("mt.nope") == listOf("Property 'nope' does not exist on type '[1, 2]'."))
    }

    @Test
    fun `negative control - a plain array's members are unchanged`() {
        val src = "declare const a: number[]; const r: boolean = a.slice(1); a.push(\"s\")"
        val d = diags(src).sortedBy { it.character }
        assert(d.size == 2)
        assert(d[0].message == notBoolean("number[]"))
        assert(d[1].code == 2345 && paramOf(d[1]) == "'number'.")
    }
}
