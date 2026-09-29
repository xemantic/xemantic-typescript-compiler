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
 * (CHK.173) B5b (N13) — a UNION right-hand side in assignment narrowing. `a ??= c ? gs() :
 * emptyArr` (tsc program.ts `automaticTypeDirectiveNames ??= …`), `a = u`, `a ||= o.p`,
 * `a = arr[0]` and an annotated declaration's initializer whose type is a union (`string[] |
 * never[]`) used to keep the declared nullish type, because the resolving arms refuse to
 * FILTER a declared union by a union (round 463's lenient member relation) — a shipped
 * false TS18048 on a parameter. A union with no nullish / `void` / `any` / `unknown`
 * member now only REMOVES the declared type's nullish members, never filters the others. A conditional of LITERALS of different primitive kinds (`c ? "x" :
 * 1`) is reduced as tsgo reduces it (literals relate exactly). An identifier or
 * conditional branch that is a bare OPTIONAL parameter `v?: T` types `T | undefined` for
 * these arms, which also closes the older non-union gap (`a = v` dropped a true row).
 *
 * Every fixture is a cell of `build/bench/p18225-agent/cells/<group>` and every expectation
 * is tsgo 7.0.2's WHOLE row list for it. A type-parameter member of the union is read
 * through its base constraint. Residues NOT pinned (tsgo in brackets): an UNCONSTRAINED
 * or nullable-constrained type parameter is refused (`t: T | string[]` keeps `| undefined`
 * [`T | string[]`]); `a = o?.r` and a `Record` element read
 * `rec["k"]` still report [silent]; so does a right-hand side that is a BODY local (`a = v`
 * with a block `const v`, typed `any` in the property-access ambient — the G1 family); tsgo FILTERS a union right-hand
 * side (`string | number` into `string | number | boolean`) where this arm only removes
 * nullish, which no cell here can see.
 */
class FlowUnionRhsAssignmentTest {

    private val STRICT = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"

    private val N13 = "declare const c: boolean; declare function gs(): string[]; declare const emptyArr: never[];"

    private val M1 = "declare const c: boolean; declare function gs(): string[]; declare const emptyArr: never[]; declare const u: string[] | never[]; declare const nu: string[] | undefined; declare const arr: (string[] | never[])[]; interface Cat { k: \"c\"; meow(): void } interface Dog { k: \"d\"; bark(): void } declare const cd: Cat | Dog; declare const o: { p: string[] | never[]; q: Cat | Dog };"

    private val M2 = "interface TE { kind: 1; type: { kind: number } } interface TL { kind: 2; lit: true } interface Tag { typeExpression: TE } declare function finishNode<T>(n: T, pos: number): T; declare function isObj(t: { kind: number }): boolean; declare const lit: TL; declare const c: boolean;"

    private val M3 = "declare const c: boolean; interface O { p?: string[] | never[]; q: string[] | never[] | undefined; r: string[] | never[] } declare const o: O; declare const m: Map<string, string[] | never[]>; declare const rec: Record<string, string[] | never[]>;"

    private val M4 = "declare const c: boolean; interface O { p?: string[] }; declare const o: O;"

    private val M5 = "declare const c: boolean; declare const emptyArr: never[];"

    private fun rowsOf(prelude: String, body: String): List<String> =
        diagnose(prelude + "\n" + body + "\nexport {}\n", STRICT)
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `a parameter defaulted by a nullish assignment of a union conditional is not possibly undefined`() {
        val rows = rowsOf(N13, "function f(a: string[] | undefined) { a ??= c ? gs() : emptyArr; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a parameter defaulted by an or-assignment of a union conditional is not possibly undefined`() {
        val rows = rowsOf(M1, "function f(a: string[] | undefined) { a ||= c ? gs() : emptyArr; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a parameter overwritten by a union conditional is not possibly undefined`() {
        val rows = rowsOf(M1, "function f(a: string[] | undefined) { a = c ? gs() : emptyArr; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a nullish assignment of a union identifier removes undefined`() {
        val rows = rowsOf(M1, "function f(a: string[] | undefined) { a ??= u; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a nullish assignment of a union property removes undefined`() {
        val rows = rowsOf(M1, "function f(a: string[] | undefined) { a ??= o.p; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a plain assignment of a union identifier removes undefined`() {
        val rows = rowsOf(M1, "function f(a: string[] | undefined) { a = u; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a nullish assignment of a union element removes undefined`() {
        val rows = rowsOf(M1, "function f(a: string[] | undefined) { a ??= arr[0]; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a plain assignment of a union conditional without a call branch removes undefined`() {
        val rows = rowsOf(M1, "function f(a: string[] | undefined) { a = c ? u : emptyArr; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `an or-assignment of a union identifier removes undefined`() {
        val rows = rowsOf(M1, "function f(a: string[] | undefined) { a ||= u; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a plain assignment of a union property removes undefined`() {
        val rows = rowsOf(M1, "function f(a: string[] | undefined) { a = o.p; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a plain assignment of a union element removes undefined`() {
        val rows = rowsOf(M1, "function f(a: string[] | undefined) { a = arr[0]; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a nullish assignment of a union removes null`() {
        val rows = rowsOf(M1, "function f(a: string[] | null) { a ??= u; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `an or-assignment of a union removes null and undefined`() {
        val rows = rowsOf(M1, "function f(a: string[] | null | undefined) { a ||= u; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `control - a nullish assignment of a nullable union keeps undefined`() {
        val rows = rowsOf(M1, "function f(a: string[] | undefined) { a ??= nu; a.length }")
        val expected = listOf(
            "2:49 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - a union conditional with an undefined branch keeps undefined`() {
        val rows = rowsOf(M1, "function f(a: string[] | undefined) { a ??= c ? gs() : undefined; a.length }")
        val expected = listOf(
            "2:67 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - an and-assignment of a union keeps undefined`() {
        val rows = rowsOf(M1, "function f(a: string[] | undefined) { a &&= u; a.length }")
        val expected = listOf(
            "2:48 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a union right-hand side does not filter the other declared members`() {
        val rows = rowsOf(M1, "function f(a: Cat | Dog | undefined) { a = cd; a.meow() }")
        val expected = listOf(
            "2:50 TS2339 Property 'meow' does not exist on type 'Cat | Dog'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a nullish assignment of a union property does not filter the other declared members`() {
        val rows = rowsOf(M1, "function f(a: Cat | Dog | undefined) { a ??= o.q; a.bark() }")
        val expected = listOf(
            "2:53 TS2339 Property 'bark' does not exist on type 'Cat | Dog'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `an or-assignment of a union identifier does not filter the other declared members`() {
        val rows = rowsOf(M1, "function f(a: Cat | Dog | undefined) { a ||= cd; a.k }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a body local read after a nullish assignment of a union conditional names the non-nullish type`() {
        val rows = rowsOf(M1, "function f() { let a: string[] | undefined; a ??= c ? gs() : emptyArr; const n: number = a }")
        val expected = listOf(
            "2:78 TS2322 Type 'string[]' is not assignable to type 'number'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a body local read after an or-assignment of a union conditional names the non-nullish type`() {
        val rows = rowsOf(M1, "function f() { let a: string[] | undefined; a ||= c ? gs() : emptyArr; const n: number = a }")
        val expected = listOf(
            "2:78 TS2322 Type 'string[]' is not assignable to type 'number'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a body local read after a plain assignment of a union conditional names the non-nullish type`() {
        val rows = rowsOf(M1, "function f() { let a: string[] | undefined; a = c ? gs() : emptyArr; const n: number = a }")
        val expected = listOf(
            "2:76 TS2322 Type 'string[]' is not assignable to type 'number'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a body local read after a nullish assignment of a union identifier names the non-nullish type`() {
        val rows = rowsOf(M1, "function f() { let a: string[] | undefined; a ??= u; const n: number = a }")
        val expected = listOf(
            "2:60 TS2322 Type 'string[]' is not assignable to type 'number'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `an annotated declaration initialized with a union conditional names the non-nullish type`() {
        val rows = rowsOf(M1, "function f() { let a: string[] | undefined = c ? gs() : emptyArr; const n: number = a }")
        val expected = listOf(
            "2:73 TS2322 Type 'string[]' is not assignable to type 'number'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `an annotated declaration initialized with a union identifier names the non-nullish type`() {
        val rows = rowsOf(M1, "function f() { let a: string[] | undefined = u; const n: number = a }")
        val expected = listOf(
            "2:55 TS2322 Type 'string[]' is not assignable to type 'number'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - a body local read after a nullish assignment of a nullable union keeps undefined`() {
        val rows = rowsOf(M1, "function f() { let a: string[] | undefined; a ??= nu; const n: number = a }")
        val expected = listOf(
            "2:61 TS2322 Type 'string[] | undefined' is not assignable to type 'number'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a body local overwritten by an object union names the union without undefined`() {
        val rows = rowsOf(M1, "function f() { let a: Cat | Dog | undefined; a = cd; const n: number = a }")
        val expected = listOf(
            "2:60 TS2322 Type 'Cat | Dog' is not assignable to type 'number'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a conditional of literals of two primitive kinds reduces the declared union`() {
        val rows = rowsOf(M1, "function f() { let a: string | number | boolean | undefined; a = c ? \"x\" : 1; const n: null = a }")
        val expected = listOf(
            "2:85 TS2322 Type 'string | number' is not assignable to type 'null'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a parameter overwritten by a conditional of literals of two primitive kinds is not possibly undefined`() {
        val rows = rowsOf(M1, "function f(a: string | number | boolean | undefined) { a = c ? \"x\" : 1; a.toFixed() }")
        val expected = listOf(
            "2:75 TS2339 Property 'toFixed' does not exist on type 'string | number'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a union ternary of a property and an identifier removes undefined`() {
        val rows = rowsOf(M2, "function f(te: TE | TL | undefined, tag: Tag | undefined) { te = tag ? tag.typeExpression : lit; te.kind }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a union ternary without a call branch removes undefined`() {
        val rows = rowsOf(M2, "function f(te: TE | TL | undefined, tag: Tag) { te = c ? tag.typeExpression : lit; te.kind }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a union ternary with a call branch behind a guard removes undefined`() {
        val rows = rowsOf(M2, "function f(te: TE | TL | undefined, tag: Tag | undefined) { te = tag && tag.typeExpression && !isObj(tag.typeExpression.type) ? tag.typeExpression : finishNode(lit, 0); te.kind }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `control - an overwrite from a nullable local keeps undefined`() {
        val rows = rowsOf(M2, "function f(te: TE | TL | undefined) { let t2: TE | TL | undefined; t2 = c ? lit : undefined; te = t2; te.kind }")
        val expected = listOf(
            "2:103 TS18048 'te' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a parameter overwritten by literals of two primitive kinds is not possibly undefined`() {
        val rows = rowsOf(M2, "function f(a: string | number | undefined) { a = c ? \"x\" : 1; a.valueOf() }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `an or-assignment of a union conditional with a non-null branch removes undefined`() {
        val rows = rowsOf(M3, "function f(a: string[] | undefined, b: string[] | null) { a ||= c ? o.r : b!; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `an or-assignment of a union property removes undefined`() {
        val rows = rowsOf(M3, "function f(a: string[] | undefined) { a ||= o.r; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a plain assignment of a union removes null and undefined`() {
        val rows = rowsOf(M3, "function f(a: string[] | null | undefined) { a = o.r; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a conditional of literals reduces a literal union declared type`() {
        val rows = rowsOf(M3, "function f(a: \"x\" | 1 | true | undefined) { a = c ? \"x\" : 1; a.valueOf() }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `control - an optional property of union type keeps undefined`() {
        val rows = rowsOf(M3, "function f(a: string[] | undefined) { a ??= o.p; a.length }")
        val expected = listOf(
            "2:50 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - an optional parameter of union type keeps undefined`() {
        val rows = rowsOf(M3, "function f(a: string[] | undefined, v?: string[] | never[]) { a = v; a.length }")
        val expected = listOf(
            "2:70 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - a nullish assignment from an optional parameter of union type keeps undefined`() {
        val rows = rowsOf(M3, "function f(a: string[] | undefined, v?: string[] | never[]) { a ??= v; a.length }")
        val expected = listOf(
            "2:72 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - a property whose union carries undefined keeps it`() {
        val rows = rowsOf(M3, "function f(a: string[] | undefined) { a = o.q; a.length }")
        val expected = listOf(
            "2:48 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - an optional class property of union type keeps undefined`() {
        val rows = rowsOf(M3, "class K { x?: string[] | never[]; m(a: string[] | undefined) { a = this.x; a.length } }")
        val expected = listOf(
            "2:76 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - an optional destructured parameter of union type keeps undefined`() {
        val rows = rowsOf(M3, "function f(a: string[] | undefined, { v }: { v?: string[] | never[] }) { a = v; a.length }")
        val expected = listOf(
            "2:81 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - a map lookup keeps undefined`() {
        val rows = rowsOf(M3, "function f(a: string[] | undefined) { a = m.get(\"k\"); a.length }")
        val expected = listOf(
            "2:55 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - a union conditional with an optional property branch keeps undefined`() {
        val rows = rowsOf(M3, "function f(a: string[] | undefined) { a = c ? o.r : o.p; a.length }")
        val expected = listOf(
            "2:58 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - a union assignment in one branch keeps undefined after the join`() {
        val rows = rowsOf(M3, "function f(a: string[] | undefined) { if (c) { a = o.r } a.length }")
        val expected = listOf(
            "2:58 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - a later undefined assignment wins`() {
        val rows = rowsOf(M3, "function f(a: string[] | undefined) { a = o.r; a = undefined; a.length }")
        val expected = listOf(
            "2:63 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - a literal conditional with an undefined branch keeps undefined`() {
        val rows = rowsOf(M3, "function f(a: string | undefined) { a = c ? \"x\" : undefined; a.length }")
        val expected = listOf(
            "2:62 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - a later undefined assignment wins over a literal union`() {
        val rows = rowsOf(M3, "function f(a: string | number | undefined) { a = c ? \"x\" : 1; a = undefined; a.valueOf() }")
        val expected = listOf(
            "2:78 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - an any right-hand side keeps undefined`() {
        val rows = rowsOf(M3, "function f(a: string | undefined) { const x = c ? \"x\" : 1; a = x as any; a.length }")
        val expected = listOf(
            "2:74 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `an assignment from an optional parameter keeps undefined`() {
        val rows = rowsOf(M4, "function f(a: string[] | undefined, v?: string[]) { a = v; a.length }")
        val expected = listOf(
            "2:60 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a nullish assignment from an optional parameter keeps undefined`() {
        val rows = rowsOf(M4, "function f(a: string[] | undefined, v?: string[]) { a ??= v; a.length }")
        val expected = listOf(
            "2:62 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a union conditional with an optional parameter branch keeps undefined`() {
        val rows = rowsOf(M4, "function f(a: string[] | undefined, v?: string[] | never[]) { a = c ? v : []; a.length }")
        val expected = listOf(
            "2:79 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `an assignment from an optional parameter of union type keeps undefined`() {
        val rows = rowsOf(M4, "function f(a: string[] | undefined, v?: string[] | never[]) { a = v; a.length }")
        val expected = listOf(
            "2:70 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a defaulted parameter of union type is not optional`() {
        val rows = rowsOf(M4, "function f(a: string[] | undefined, v: string[] | never[] = []) { a = v; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `an element of a union rest parameter removes undefined`() {
        val rows = rowsOf(M4, "function f(a: string[] | undefined, ...v: (string[] | never[])[]) { a = v[0]; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a union with a type parameter constrained to a non-nullish type removes undefined`() {
        val rows = rowsOf(M5, "function f<T extends string[]>(a: string[] | undefined, t: T | never[]) { a = t; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a nullish assignment of a union with an object-constrained type parameter removes undefined`() {
        val rows = rowsOf(M5, "function f<T extends object>(a: T | string[] | undefined, t: T | string[]) { a ??= t; const n: number = a }")
        val expected = listOf(
            "2:93 TS2322 Type 'T | string[]' is not assignable to type 'number'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a union of arrays of a type parameter removes undefined`() {
        val rows = rowsOf(M5, "function f<T extends string[]>(a: T[] | undefined, t: T[] | never[]) { a = t; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a union conditional with a type-parameter branch removes undefined`() {
        val rows = rowsOf(M5, "function f<T extends string>(a: string[] | T | undefined, t: T | never[]) { a = c ? t : emptyArr; a.length }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `control - a union with a type parameter constrained to a nullable type keeps undefined`() {
        val rows = rowsOf(M5, "function f<T extends string[] | undefined>(a: string[] | undefined, t: T | never[]) { a = t; a.length }")
        val expected = listOf(
            "2:94 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - a nullish assignment of a union with a nullable-constrained type parameter keeps undefined`() {
        val rows = rowsOf(M5, "function f<T extends string[] | undefined>(a: string[] | undefined, t: T | never[]) { a ??= t; a.length }")
        val expected = listOf(
            "2:96 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - a union with a type parameter constrained to a null type keeps null`() {
        val rows = rowsOf(M5, "function f<T extends string[] | null>(a: string[] | null, t: T | never[]) { a = t; a.length }")
        val expected = listOf(
            "2:84 TS18047 'a' is possibly 'null'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `an arrow parameter shadowing an optional parameter is not optional`() {
        val rows = rowsOf(M5, "function f(a: string[] | undefined, v?: string[] | never[]) { const g = (v: string[] | never[]) => { a = v; a.length } }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `control - an optional parameter read inside a block keeps undefined`() {
        val rows = rowsOf(M5, "function f(a: string[] | undefined, v?: string[] | never[]) { if (c) { a = v; a.length } }")
        val expected = listOf(
            "2:79 TS18048 'a' is possibly 'undefined'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a for-of binding shadowing an optional parameter is not optional`() {
        val rows = rowsOf(M5, "function f(a: string[] | undefined, v?: string[] | never[]) { for (const v of [emptyArr]) { a = v; a.length } }")
        val expected = emptyList<String>()
        assert(rows == expected)
    }
}
