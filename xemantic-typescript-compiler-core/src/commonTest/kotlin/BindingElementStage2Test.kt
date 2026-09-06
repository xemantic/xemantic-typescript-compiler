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
 * (CHK.96) stage 2 — the five deliverables stage 1 left, each measured against tsgo
 * 7.0.2 AND pristine `typescript@6.0.3` before any code was written (the two agree on
 * every row here, union member order included):
 *
 *  1. an OBJECT REST reads tsc's `getRestType` ([Checker.objectRestType]): the apparent
 *     type's members minus the pattern's named ones, copied MUTABLE, minus `private` /
 *     `protected` / `#private` members and a CLASS's methods and accessors, with the
 *     index signatures carried; a generic source (`Omit<T, K>` in tsc) refuses;
 *  2. `[Symbol.iterator]`-typed sources ([Checker.iterationYieldTypeOf]): a `Map` /
 *     `Set` / `ReadonlyMap` / generator / `Iterable<T>` / `IterableIterator<T>` head and
 *     destructuring source, and a union subject at the declaration reader — with the
 *     instantiator now rebuilding a TUPLE as a tuple ([Checker.instantiateTupleElements]),
 *     which is what makes `MapIterator<[K, V]>`'s slot readable at all;
 *  3. CONTEXTUAL pattern parameters (`objs.map(({ p }) => …)`) at the declaration,
 *     argument, property-access and return-inference readers, from a call argument, a
 *     variable annotation, an object-literal member, a function expression;
 *  4. the pattern's IMPLIED contextual type: every array-literal element widens, the
 *     empty literal is the empty tuple, an index past a tuple's slots is `undefined`,
 *     and TS2493 names the literal's tuple;
 *  5. the destructured-discriminant CARRY ([Checker.destructuredDiscriminantCarry]):
 *     tsc's `getNarrowedTypeOfSymbol` — a non-rest, non-defaulted element of a `const` /
 *     unassigned-parameter pattern with two or more elements over a UNION parent is
 *     re-read at every use from the parent narrowed by its sibling elements' flow
 *     types, so `payload` is `number` under `if (kind === "a")` and `never` in an
 *     exhausted `default` (the corpus's `arrayDestructuringInSwitch2`, whose shape stage
 *     1 could only REFUSE).
 *
 * RECORDED residues (the expectation is OURS, pre-existing and outside the item): the
 * TS2367 reader has no contextual types for a call-argument arrow and no anonymous
 * object versus primitive verdict; a binding element naming NO member of its parent
 * (`({ nope }) => …`, `const { nope } = obj`) reports nothing here; `new Set([1, 2])`
 * constructs `Set<any>`; an object literal's un-annotated getter reads `any`; TS2493 for
 * a DECLARED tuple is not reported inside a body; a body-local carried discriminant is
 * not re-read at the property-access reader (its parameter twin is).
 */
class BindingElementStage2Test {

    private val prelude = """
        interface R { p: number; q: string; o?: number; m(): void; readonly r: boolean }
        declare const robj: R
        interface I { p: number; q: string; o?: number; kind: "a" | "b" }
        declare const objs: I[]
        type Act = { kind: "a"; payload: number } | { kind: "b"; payload: string }
        declare const act: Act
        declare function takeB(b: boolean): void
        export {}
    """.trimIndent() + "\n"

    /** 0-based line of the first line appended after [prelude] (line 0 is the directive). */
    private val rowLine = prelude.count { it == '\n' } + 1

    private fun col(source: String, needle: String): Int = source.indexOf(needle) + 1

    private fun decl(t: String) = "Type '$t' is not assignable to type 'boolean'."
    private fun arg(t: String) = "Argument of type '$t' is not assignable to parameter of type 'boolean'."
    private fun missing(p: String, t: String) = "Property '$p' does not exist on type '$t'."

    private val restOfR = "{ q: string; o?: number | undefined; m(): void; r: boolean; }"

    /** Exactly the rows [expected], in order, each at [needles]'s first occurrence on the one source line. */
    private fun assertRows(src: String, expected: List<Pair<Int, String>>, vararg needles: String, realLibs: Boolean = false) {
        val d = diagnose(prelude + src, directives = if (realLibs) "// @strict: true\n// @useRealLibs: true" else "// @strict: true")
        assert(d.map { it.code to it.message } == expected)
        for ((i, n) in needles.withIndex()) {
            if (n.isEmpty()) continue  // a multi-line fixture: the row is pinned by its text alone
            assert(d[i].line == rowLine)
            assert(d[i].character == col(src, n))
        }
    }

    private fun assertOne(src: String, code: Int, message: String, needle: String, realLibs: Boolean = false) =
        assertRows(src, listOf(code to message), needle, realLibs = realLibs)

    private fun assertSilent(src: String, realLibs: Boolean = false) {
        val d = diagnose(prelude + src, directives = if (realLibs) "// @strict: true\n// @useRealLibs: true" else "// @strict: true")
        assert(d.isEmpty())
    }

    // ---------------------------------------------------------------------
    // 1. object REST
    // ---------------------------------------------------------------------

    @Test
    fun `a rest reads the members the pattern does not name - readonly dropped - the interface method kept`() =
        assertOne("function f() { const { p, ...rest } = robj; const w: boolean = rest }", 2322, decl(restOfR), "w:")

    @Test
    fun `a rest's member reads through it`() =
        assertOne("function f() { const { p, ...rest } = robj; const w: boolean = rest.q }", 2322, decl("string"), "w:")

    @Test
    fun `a rest reaches the property-access reader`() =
        assertOne("function f() { const { p, ...rest } = robj; rest.nope }", 2339, missing("nope", restOfR), "nope")

    @Test
    fun `a rest omits the named member at the property-access reader`() =
        assertOne("function f() { const { p, ...rest } = robj; rest.p }", 2339, missing("p", restOfR), "p }")

    @Test
    fun `a rest over a class drops private protected methods and accessors`() =
        assertOne(
            "class C2 { p = 1; private priv = 2; protected prot = 3; q = \"s\"; meth() {} get acc() { return 1 } }\n" +
                "function f(c: C2) { const { p, ...rest } = c; const w: boolean = rest }",
            2322, decl("{ q: string; }"), "",
        ).let {
            val d = diagnose(prelude + "class C2 { p = 1; private priv = 2; protected prot = 3; q = \"s\"; meth() {} get acc() { return 1 } }\nfunction f(c: C2) { const { p, ...rest } = c; rest.acc; rest.meth }")
            assert(d.map { it.message } == listOf(missing("acc", "{ q: string; }"), missing("meth", "{ q: string; }")))
        }

    @Test
    fun `refusal - a conditional of array literals under an array pattern is not typed`() {
        // tsc pushes the pattern's implied contextual type into BOTH branches, so slot 0 of
        // `c ? [n, undefined] : [o.pos, o.end]` is `number` (tsgo and pristine agree, and
        // slot 1 is `number | undefined`). Reconstructing that needs each element's
        // FLOW-NARROWED type at its own branch, which `getTypeOfExpression` never gives, so
        // the whole pattern refuses and both leaves keep `anyType`. Typing it from the
        // DECLARED types instead read slot 0 as `number | { pos: number; end: number; }` —
        // an ours-only TS2322 on `services.ts:3264`, on all three profiles carrying it.
        // (CHK.107) owns the narrowed reconstruction.
        val src = "declare const por: number | { pos: number; end: number }\n" +
            "function g() { const [s, e] = typeof por === \"number\" ? [por, undefined] : [por.pos, por.end]\n" +
            "  const w1: boolean = s; const w2: boolean = e }"
        assert(diagnose(prelude + src).isEmpty())
    }

    @Test
    fun `a rest member access is reported ONCE - the dedicated walker dedupes against the spine`() {
        // `checkObjectRestUnspreadableAccess` (B353/B357/B359) owned this row before stage 2
        // typed the rest; the spine's member reader now emits it too for a NON-generic source,
        // and that pass runs FIRST, so the identity test lives in the walker. The corpus's
        // `destructuringUnspreadableIntoRest` went 50 -> 72 rows without it (22 duplicates).
        val src = "class C3 { constructor(public pub: string, private priv: string) {}\n" +
            "  m() { const { ...rest } = this as C3; rest.priv } }"
        val d = diagnose(prelude + src)
        assert(d.map { it.code to it.message } == listOf(2339 to missing("priv", "{ pub: string; }")))
    }

    @Test
    fun `a rest over a class drops a private constructor parameter property and a hash-private field`() =
        assertOne(
            "class C3 { constructor(private x: number, public y: string) {} #h = 1; z = 2 }\n" +
                "function f(c: C3) { const { y, ...rest } = c; const w: boolean = rest }",
            2322, decl("{ z: number; }"), "",
        )

    @Test
    fun `a rest carries the index signature`() =
        assertOne(
            "declare const idx: { [k: string]: number; p: number }\nfunction f() { const { p, ...rest } = idx; const w: boolean = rest }",
            2322, decl("{ [k: string]: number; }"), "",
        )

    @Test
    fun `a rest over a union rests each constituent`() =
        assertOne(
            "declare const uni: { p: number; a: string } | { p: number; b: boolean }\nfunction f() { const { p, ...rest } = uni; const w: boolean = rest }",
            2322, decl("{ a: string; } | { b: boolean; }"), "",
        )

    @Test
    fun `a rest naming nothing else is the whole object`() =
        assertOne(
            "function f() { const { ...all } = robj; const w: boolean = all }", 2322,
            decl("{ p: number; q: string; o?: number | undefined; m(): void; r: boolean; }"), "w:",
        )

    @Test
    fun `a rest omitting an optional member keeps the rest`() =
        assertOne(
            "function f() { const { o, ...rest } = robj; const w: boolean = rest }", 2322,
            decl("{ p: number; q: string; m(): void; r: boolean; }"), "w:",
        )

    @Test
    fun `a rest under a renamed sibling omits the property name not the binding name`() =
        assertOne(
            "function f() { const { q: qq, ...rest } = robj; const w: boolean = rest }", 2322,
            decl("{ p: number; o?: number | undefined; m(): void; r: boolean; }"), "w:",
        )

    @Test
    fun `a nested rest reads the nested object`() =
        assertOne(
            "function f() { const { n: { r, ...inner } } = { n: { r: 1, s: \"x\" } }; const w: boolean = inner }", 2322,
            decl("{ s: string; }"), "w:",
        )

    @Test
    fun `a rest over an interface with heritage keeps the inherited members`() =
        assertOne(
            "interface J extends I { z: number }\ndeclare const j: J\nfunction f() { const { z, kind, ...rest } = j; const w: boolean = rest }",
            2322, decl("{ p: number; q: string; o?: number | undefined; }"), "",
        )

    @Test
    fun `a pattern parameter's rest reaches both readers`() =
        assertRows(
            "function f({ p, ...rest }: R) { const w: boolean = rest; takeB(rest) }",
            listOf(2322 to decl(restOfR), 2345 to arg(restOfR)), "w:", "rest)",
        )

    @Test
    fun `a body rest reaches the argument gate`() =
        assertOne("function f() { const { p, ...rest } = robj; takeB(rest) }", 2345, arg(restOfR), "rest)")

    @Test
    fun `a let rest reads the rest`() =
        assertOne("function f() { let { p, ...rest } = robj; const w: boolean = rest }", 2322, decl(restOfR), "w:")

    @Test
    fun `a file-level rest reaches the declaration reader - the symbol half`() =
        assertOne("const { p: fp, ...frest } = robj; const fw: boolean = frest", 2322, decl(restOfR), "fw:")

    @Test
    fun `a for-of head's rest reads the rest`() =
        assertOne("function f() { for (const { p, ...rest } of [robj]) { const w: boolean = rest } }", 2322, decl(restOfR), "w:")

    @Test
    fun `negative control - a rest over a type parameter refuses`() =
        // tsc answers `Omit<T, "p">` there; a mapped alias is (EXT.11b)'s bare `any` here.
        assertSilent("function g<T extends R>(t: T) { const { p, ...rest } = t; const w: boolean = rest }")

    @Test
    fun `negative control - a rest over any stays any`() =
        assertSilent("declare const anyv: any\nfunction f() { const { p, ...rest } = anyv; const w: boolean = rest }")

    @Test
    fun `an object-literal spread copy is mutable outside a const context`() =
        assertOne(
            "interface S { p: number; readonly r: boolean; m(): void }\ndeclare const sv: S\nfunction f() { const w: boolean = { ...sv } }",
            2322, decl("{ p: number; r: boolean; m(): void; }"), "",
        )

    // ---------------------------------------------------------------------
    // 2. iterables
    // ---------------------------------------------------------------------

    private val iterPrelude = "declare const m: Map<string, number>\ndeclare const s: Set<string>\n" +
        "declare const rm: ReadonlyMap<string, number>\ndeclare function gen(): Generator<number, void, unknown>\n" +
        "declare const it: Iterable<boolean>\ndeclare const ii: IterableIterator<string>\n"

    @Test
    fun `a for-of tuple head over a Map binds the key and the value`() =
        assertRows(
            iterPrelude + "function f() { for (const [k, v] of m) { const w: boolean = k; takeB(v) } }",
            listOf(2322 to decl("string"), 2345 to arg("number")), realLibs = true,
        )

    @Test
    fun `a for-of identifier head over a Map is the entry tuple`() =
        assertOne(iterPrelude + "function f() { for (const e of m) { const w: boolean = e } }", 2322, decl("[string, number]"), "", realLibs = true)

    @Test
    fun `a for-of head over a Set reads the element`() =
        assertOne(iterPrelude + "function f() { for (const x of s) { const w: boolean = x } }", 2322, decl("string"), "", realLibs = true)

    @Test
    fun `a for-of tuple head over a ReadonlyMap binds both`() =
        assertRows(
            iterPrelude + "function f() { for (const [k, v] of rm) { const w: boolean = k; takeB(v) } }",
            listOf(2322 to decl("string"), 2345 to arg("number")), realLibs = true,
        )

    @Test
    fun `a for-of head over a generator reads the yield type`() =
        assertOne(iterPrelude + "function f() { for (const g of gen()) { const w: boolean = g } }", 2322, decl("number"), "", realLibs = true)

    @Test
    fun `a for-of head over an Iterable reads its argument`() =
        assertOne(iterPrelude + "function f() { for (const b of it) { const w: number = b } }", 2322, "Type 'boolean' is not assignable to type 'number'.", "", realLibs = true)

    @Test
    fun `a for-of head over an IterableIterator reads its argument`() =
        assertOne(iterPrelude + "function f() { for (const q of ii) { const w: boolean = q } }", 2322, decl("string"), "", realLibs = true)

    @Test
    fun `a for-of head over Map entries keys and values`() =
        assertRows(
            iterPrelude + "function f() { for (const [k] of m.entries()) { const w: boolean = k } for (const k2 of m.keys()) { const w2: boolean = k2 } for (const v of m.values()) { const w3: boolean = v } }",
            listOf(2322 to decl("string"), 2322 to decl("string"), 2322 to decl("number")), realLibs = true,
        )

    @Test
    fun `a for-of tuple head over an array's entries binds the index and the element`() =
        assertRows(
            iterPrelude + "declare const arr: number[]\nfunction f() { for (const [i, v] of arr.entries()) { const w: boolean = i; const w2: boolean = v } }",
            listOf(2322 to decl("number"), 2322 to decl("number")), realLibs = true,
        )

    @Test
    fun `an array pattern over a Map reads the entry tuple per slot`() =
        assertOne(iterPrelude + "function f() { const [a, b] = m; const w: boolean = a }", 2322, decl("[string, number]"), "", realLibs = true)

    @Test
    fun `an array pattern over a Set a generator and an Iterable reads the yield type`() =
        assertRows(
            iterPrelude + "function f() { const [a] = s; const w: boolean = a; const [b] = gen(); const w2: boolean = b; const [c] = it; const w3: number = c }",
            listOf(2322 to decl("string"), 2322 to decl("number"), 2322 to "Type 'boolean' is not assignable to type 'number'."), realLibs = true,
        )

    @Test
    fun `a rest over a Map reads the entry array`() =
        assertOne(iterPrelude + "function f() { const [k, ...rest] = m; const w: boolean = rest }", 2322, decl("[string, number][]"), "", realLibs = true)

    @Test
    fun `a Map head's binding reaches the argument and property-access readers`() =
        assertRows(
            iterPrelude + "function f() { for (const [k, v] of m) { takeB(k); k.nope } }",
            listOf(2345 to arg("string"), 2339 to missing("nope", "string")), realLibs = true,
        )

    @Test
    fun `a file-level Map head reaches the readers - the symbol half`() =
        assertRows(
            iterPrelude + "for (const [fk, fv] of m) { const fw: boolean = fk; takeB(fv) }",
            listOf(2322 to decl("string"), 2345 to arg("number")), realLibs = true,
        )

    @Test
    fun `an entry bound by a Map head destructures in the body`() =
        assertOne(iterPrelude + "function f() { for (const e of m) { const [k, v] = e; const w: boolean = k } }", 2322, decl("string"), "", realLibs = true)

    @Test
    fun `a union of iterables iterates as the union of their elements at the declaration reader`() =
        assertOne(
            iterPrelude + "function f(u: Map<string, number> | Set<string>) { for (const e of u) { const w: boolean = e } }",
            2322, decl("string | [string, number]"), "", realLibs = true,
        )

    @Test
    fun `negative control - the embedded lib's Map declares no iterator and its head stays any`() =
        assertSilent(iterPrelude + "function f() { for (const [k, v] of m) { const w: boolean = k } }")

    @Test
    fun `the fast path names Iterable under the embedded lib too`() =
        assertOne(iterPrelude + "function f() { for (const b of it) { const w: number = b } }", 2322, "Type 'boolean' is not assignable to type 'number'.", "")

    @Test
    fun `negative control - an any subject stays any`() =
        assertSilent(iterPrelude + "function f() { for (const x of m as any) { const w: boolean = x } }", realLibs = true)

    @Test
    fun `a tuple instantiated through a generic keeps its slots`() =
        assertOne(
            "interface Box<T> { v: T }\ndeclare const bx: Box<[number, string]>\nfunction f() { const [a, b] = bx.v; const w: boolean = b }",
            2322, decl("string"), "",
        )

    // ---------------------------------------------------------------------
    // 3. contextual pattern parameters
    // ---------------------------------------------------------------------

    @Test
    fun `a map callback's object pattern reaches the declaration reader`() =
        assertOne("objs.map(({ p }) => { const w: boolean = p; return p })", 2322, decl("number"), "w:")

    @Test
    fun `a map callback's object pattern reaches the argument gate`() =
        assertOne("objs.map(({ p }) => { takeB(p); return p })", 2345, arg("number"), "p)")

    @Test
    fun `a map callback's object pattern reaches the property-access reader`() =
        assertOne("objs.map(({ p }) => { p.nope; return p })", 2339, missing("nope", "number"), "nope")

    @Test
    fun `a forEach callback's optional member reads with undefined and a default drops it`() =
        assertRows(
            "objs.forEach(({ o }) => { const w: boolean = o }); objs.forEach(({ o = 3 }) => { const w2: boolean = o })",
            listOf(2322 to decl("number | undefined"), 2322 to decl("number")), "w:", "w2:",
        )

    @Test
    fun `a forEach callback's renamed member and rest read`() =
        assertRows(
            "objs.forEach(({ p: rn }) => { const w: boolean = rn }); objs.forEach(({ p, ...rest }) => { const w2: boolean = rest })",
            listOf(2322 to decl("number"), 2322 to decl("{ q: string; o?: number | undefined; kind: \"a\" | \"b\"; }")), "w:", "w2:",
        )

    @Test
    fun `a user callee's contextual object pattern reaches both readers`() =
        assertRows(
            "declare function take(cb: (i: I) => void): void\ntake(({ p }) => { const w: boolean = p; takeB(p) })",
            listOf(2322 to decl("number"), 2345 to arg("number")),
        )

    @Test
    fun `a contextual array pattern reads the tuple slots`() =
        assertRows(
            "declare function takeTup(cb: (t: [number, string]) => void): void\ntakeTup(([a, b]) => { const w: boolean = a; const w2: boolean = b })",
            listOf(2322 to decl("number"), 2322 to decl("string")),
        )

    @Test
    fun `an annotated pattern member's method parameter pattern reads the member`() =
        assertRows(
            "const v2: { m: (i: I) => void } = { m({ p }) { const w: boolean = p } }\nconst v3: { m: (i: I) => void } = { m: ({ p }) => { const w2: boolean = p } }",
            listOf(2322 to decl("number"), 2322 to decl("number")),
        )

    @Test
    fun `a variable annotation's pattern parameter reaches the declaration and argument readers`() =
        assertRows(
            "const f: (i: I) => void = ({ p }) => { const w: boolean = p; takeB(p) }",
            listOf(2322 to decl("number"), 2345 to arg("number")), "w:", "p)",
        )

    @Test
    fun `a function expression's contextual pattern reads the member`() =
        assertRows(
            "const g: (i: I) => void = function ({ q }) { const w: boolean = q }\nobjs.map(function ({ p }) { const w2: boolean = p; return p })",
            listOf(2322 to decl("string"), 2322 to decl("number")),
        )

    @Test
    fun `a map callback's pattern parameter feeds the return inference`() =
        assertRows(
            "const r = objs.map(({ p }) => p); const wr: boolean = r\nconst r2 = objs.map(({ q }) => { return q }); const wr2: boolean = r2",
            listOf(2322 to decl("number[]"), 2322 to decl("string[]")),
        )

    @Test
    fun `a pattern beside an identifier parameter types both`() =
        assertRows(
            "objs.map(({ p }, i) => { const w: boolean = p; const w2: boolean = i; return p })",
            listOf(2322 to decl("number"), 2322 to decl("number")), "w:", "w2:",
        )

    @Test
    fun `a contextual array pattern over an array-literal array reads its element union`() =
        // `[[1, "a"]]` is `(string | number)[][]` in both references — no pattern implies a tuple here.
        assertRows(
            "[[1, \"a\"]].map(([n, s]) => { const w: boolean = n; const w2: boolean = s })",
            listOf(2322 to decl("string | number"), 2322 to decl("string | number")), "w:", "w2:",
        )

    @Test
    fun `a body-scoped map callback's pattern reaches every reader`() =
        assertRows(
            "function body() { objs.map(({ p }) => { const w: boolean = p; takeB(p); return p }) }",
            listOf(2322 to decl("number"), 2345 to arg("number")), "w:", "p)",
        )

    @Test
    fun `negative control - an annotated pattern parameter keeps its annotation over the context`() =
        assertOne(
            "declare function take(cb: (i: I) => void): void\ntake(({ p }: { p: string }) => { const w: boolean = p })",
            2322, decl("string"), "",
        )

    @Test
    fun `negative control - a contextual pattern parameter with no signature stays implicitly any`() {
        val d = diagnose(prelude + "const o = { m({ p }) { const w: boolean = p } }")
        assert(d.map { it.code } == listOf(7031))
    }

    @Test
    fun `residue - a contextual pattern naming no member of the parent is silent`() =
        // tsc: TS2339 at the binding element; no walker here reports a binding element
        // that names nothing, for a contextual or an annotated pattern alike.
        assertSilent("objs.map(({ nope }) => nope)")

    // ---------------------------------------------------------------------
    // 4. the implied contextual type of an array-literal initializer
    // ---------------------------------------------------------------------

    @Test
    fun `a rest over a contextual tuple literal widens every element`() =
        assertOne("function f() { const [a, ...rest] = [1, \"x\", true]; const w: boolean = rest }", 2322, decl("[string, boolean]"), "w:")

    @Test
    fun `a const variable element widens like a fresh literal`() =
        assertOne("function f() { const one = 1; const [a] = [one]; const w: boolean = a }", 2322, decl("number"), "w:")

    @Test
    fun `an index past the literal's slots is undefined and TS2493 names the tuple`() =
        assertRows(
            "function f() { const [a, b] = [1]; const w: boolean = b }",
            listOf(2322 to decl("undefined"), 2493 to "Tuple type '[number]' of length '1' has no element at index '1'."), "w:", "b]",
        )

    @Test
    fun `an index past a declared tuple's slots is undefined`() =
        assertOne("declare const t1: [number]\nfunction f() { const [a, b] = t1; const w: boolean = b }", 2322, decl("undefined"), "")

    @Test
    fun `a default over the empty literal reads the default`() =
        assertOne("function f() { const [x = 1] = []; const w: boolean = x }", 2322, decl("number"), "w:")

    @Test
    fun `the empty literal keeps its own TS2493 wording`() =
        assertRows(
            "function f() { const [a] = []; const w: boolean = a }",
            listOf(2322 to decl("undefined"), 2493 to "Tuple type '[]' of length '0' has no element at index '0'."), "w:", "a]",
        )

    @Test
    fun `a contextual tuple literal's enum member widens to its enum`() =
        assertOne("enum K { A, B }\nfunction f() { const [a] = [K.A]; const w: boolean = a }", 2322, decl("K"), "")

    // ---------------------------------------------------------------------
    // 5. the destructured-discriminant carry
    // ---------------------------------------------------------------------

    @Test
    fun `a const pattern's sibling narrows the element at the declaration reader`() =
        assertOne("function f() { const { kind, payload } = act; if (kind === \"a\") { const w: boolean = payload } }", 2322, decl("number"), "w:")

    @Test
    fun `a const pattern's sibling narrows the element at the argument gate`() =
        assertOne("function f() { const { kind, payload } = act; if (kind === \"a\") { takeB(payload) } }", 2345, arg("number"), "payload)")

    @Test
    fun `an un-narrowed element reads the lifted union`() =
        assertOne("function f() { const { kind, payload } = act; const w: boolean = payload }", 2322, decl("string | number"), "w:")

    @Test
    fun `a pattern parameter's sibling narrows the element at both readers`() =
        assertRows(
            "function f({ kind, payload }: Act) { if (kind === \"b\") { const w: boolean = payload; payload.nope } }",
            listOf(2322 to decl("string"), 2339 to missing("nope", "string")), "w:", "nope",
        )

    @Test
    fun `a switch on the sibling narrows per clause`() =
        assertRows(
            "function f() { const { kind, payload } = act; switch (kind) { case \"a\": takeB(payload); break; case \"b\": const w: boolean = payload; break } }",
            listOf(2345 to arg("number"), 2322 to decl("string")), "payload)", "w:",
        )

    @Test
    fun `an exhausted default reads the element as never - the corpus's arrayDestructuringInSwitch2`() {
        val src = "type X = { kind: \"a\", a: [1] } | { kind: \"b\", a: [] }\n" +
            "function foo(x: X): 1 { const { kind, a } = x; switch (kind) { case \"a\": return a[0]; case \"b\": return 1; default: return a } }"
        val d = diagnose(prelude + src)
        assert(d.isEmpty())
    }

    @Test
    fun `a negated sibling test narrows the other way`() =
        assertOne("function f() { const { kind, payload } = act; if (kind !== \"a\") { const w: boolean = payload } }", 2322, decl("string"), "w:")

    @Test
    fun `a let pattern does not carry`() =
        assertOne("function f() { let { kind, payload } = act; if (kind === \"a\") { const w: boolean = payload } }", 2322, decl("string | number"), "w:")

    @Test
    fun `a defaulted element does not carry`() =
        assertOne("function f() { const { kind, payload = \"z\" } = act; if (kind === \"a\") { const w: boolean = payload } }", 2322, decl("string | number"), "w:")

    @Test
    fun `an assigned pattern parameter does not carry`() =
        assertOne(
            "function f({ kind, payload }: Act) { kind = \"b\"; if (kind === \"a\") { const w: boolean = payload } }",
            2322, decl("string | number"), "w:",
        )

    @Test
    fun `a for-of pattern head carries`() =
        assertOne("function f() { for (const { kind, payload } of [act]) { if (kind === \"b\") { takeB(payload) } } }", 2345, arg("string"), "payload)")

    @Test
    fun `a closure reads the carried element`() =
        assertOne("function f({ kind, payload }: Act) { const g = () => { if (kind === \"a\") { const w: boolean = payload } } }", 2322, decl("number"), "w:")

    @Test
    fun `a file-level const pattern carries - the symbol half`() =
        assertOne("const { kind: fk, payload: fpay } = act; if (fk === \"a\") { const fw: boolean = fpay }", 2322, decl("number"), "fw:")

    @Test
    fun `the element's own narrowing composes with the carry`() =
        assertRows(
            "function f() { const { kind, payload } = act; if (typeof payload === \"string\") { const w: boolean = payload; const w2: boolean = kind } }",
            listOf(2322 to decl("string"), 2322 to decl("string")), "w:", "w2:",
        )

    @Test
    fun `negative control - an object-literal member built from the element is the un-narrowed union`() =
        assertOne(
            "function f() { const { kind, payload } = act; const inner = { payload }; if (kind === \"a\") { const w: boolean = inner.payload } }",
            2322, decl("string | number"), "w:",
        )
}
