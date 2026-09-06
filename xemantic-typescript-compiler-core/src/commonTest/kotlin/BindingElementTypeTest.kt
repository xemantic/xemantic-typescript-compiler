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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.96) stage 1 — DESTRUCTURED BINDINGS GET THEIR TYPES. Before this, an ARRAY /
 * tuple pattern, a default, a nested pattern, a union receiver, a `for…of` pattern head
 * and every pattern PARAMETER read `any` at every reader, and an OBJECT pattern's
 * top-level member read `any` at the ARGUMENT and TS2367 readers (in the body AND at
 * file level). One pure function, [Checker.bindingElementType] — tsc's
 * `getBindingElementTypeFromParentType` — now answers seven plug points: the cta
 * declaration reader, the cta pattern-param registrar, the shared
 * `populateParameterLocalTypes` (26 callers: the cpa, ccet and spineArith frames), the
 * ccet leave-time recorder, the spineArith recorder, the three `for…of` head arms and
 * the SYMBOL half (`getTypeOfVariableOrProperty`'s new `BindingElement` arm — what a
 * FILE-level destructured name answers). Every expectation below was measured against
 * tsgo 7.0.2 AND pristine `typescript@6.0.3` before any code was written; the two agree
 * on every row this class pins (union member order included).
 *
 * NEGATIVE CONTROLS (recorded refusals, each of which a wrong answer would turn into an
 * ours-only row): `const [x] = [1, "a"]` reads the contextual TUPLE's `number` slot (the
 * array literal's own `(string | number)[]` would be a false positive); `{ o: od = 5 }`
 * prints `number`, never `number | 5` ((CHK.66)'s subtype drop at the default join); a
 * tuple whose REST has been collapsed refuses; `noUncheckedIndexedAccess` refuses (B221
 * owns it); a FUNCTION-shaped member keeps round 464b's refusal at the declaration
 * reader; a refusal still registers `any` — round 475's contract — so a same-file
 * function named like a refused destructured name is never resolved in its place.
 *
 * RECORDED residues (the expectation is OURS; stage 2 of the item): an object REST is
 * `any`; a `for (const [k, v] of map)` head over a `Map` is `any`; a CONTEXTUAL pattern
 * parameter (`arr.map(({ p }) => …)`) is `any`; a return type inferred from a pattern
 * param's body is `any`; an index past a tuple's slots in a body is silent (TS2493's
 * walker reads file-level declarations); two same-named destructured locals in one body
 * both read `any` ((CHK.95)'s per-body rule, the safe direction).
 */
class BindingElementTypeTest {

    private val prelude = """
        interface I { p: number; q: string; o?: number; s?: string; n: { r: string }; cb: (x: number) => void; u: number | string }
        declare const obj: I
        declare const tup: [number, string]
        declare const otup: [number, string?]
        declare const rtup: readonly [number, string, boolean]
        declare const restTup: [number, ...string[]]
        declare const arrN: number[]
        declare const objs: I[]
        declare const uni: { p: number } | { p: string }
        declare function takeB(b: boolean): void
        enum K { A, B }
        declare const kk: { k: K }
        export {}
    """.trimIndent() + "\n"

    /** 0-based line of the first line appended after [prelude] (line 0 is the directive). */
    private val rowLine = prelude.count { it == '\n' } + 1

    private fun col(source: String, needle: String): Int = source.indexOf(needle) + 1

    private fun decl(t: String) = "Type '$t' is not assignable to type 'boolean'."
    private fun arg(t: String) = "Argument of type '$t' is not assignable to parameter of type 'boolean'."
    private fun cmp(a: String, b: String) =
        "This comparison appears to be unintentional because the types '$a' and '$b' have no overlap."

    /** Exactly the rows [expected], in order, each at [needles]'s first occurrence on the one source line. */
    private fun assertRows(src: String, expected: List<Pair<Int, String>>, vararg needles: String) {
        val d = diagnose(prelude + src)
        assert(d.map { it.code to it.message } == expected)
        for ((i, n) in needles.withIndex()) {
            assert(d[i].line == rowLine)
            assert(d[i].character == col(src, n))
        }
    }

    private fun assertOne(src: String, code: Int, message: String, needle: String) =
        assertRows(src, listOf(code to message), needle)

    private fun assertSilent(src: String) {
        val d = diagnose(prelude + src)
        assert(d.isEmpty())
    }

    // ---------------------------------------------------------------------
    // the DECLARATION reader, body scope — arrays and tuples
    // ---------------------------------------------------------------------

    @Test
    fun `an array pattern over an array reads the element`() =
        assertOne("function f() { const [a1, a2] = arrN; const w: boolean = a1 }", 2322, decl("number"), "w:")

    @Test
    fun `a tuple pattern reads each slot`() =
        assertRows(
            "function f() { const [t1, t2] = tup; const w1: boolean = t1; const w2: boolean = t2 }",
            listOf(2322 to decl("number"), 2322 to decl("string")), "w1:", "w2:",
        )

    @Test
    fun `a readonly tuple pattern reads its slot`() =
        assertOne("function f() { const [r1] = rtup; const w: boolean = r1 }", 2322, decl("number"), "w:")

    @Test
    fun `a hole skips its slot`() =
        assertOne("function f() { const [, h2] = tup; const w: boolean = h2 }", 2322, decl("string"), "w:")

    @Test
    fun `an optional tuple slot reads with undefined`() =
        assertOne("function f() { const [a, b] = otup; const w: boolean = b }", 2322, decl("string | undefined"), "w:")

    @Test
    fun `a rest over a tuple reads the sliced tuple`() =
        assertOne("function f() { const [h, ...rest] = tup; const w: boolean = rest }", 2322, decl("[string]"), "w:")

    @Test
    fun `a rest over a readonly tuple reads a mutable slice`() =
        assertOne("function f() { const [h, ...rest] = rtup; const w: boolean = rest }", 2322, decl("[string, boolean]"), "w:")

    @Test
    fun `a rest over an array reads the array`() =
        assertOne("function f() { const [h, ...rest] = arrN; const w: boolean = rest }", 2322, decl("number[]"), "w:")

    @Test
    fun `a nested array pattern recurses`() =
        assertOne("function f() { const [[a]] = [[1]]; const w: boolean = a }", 2322, decl("number"), "w:")

    @Test
    fun `a nested object pattern under an array pattern recurses`() =
        assertOne("function f() { const [{ p }] = objs; const w: boolean = p }", 2322, decl("number"), "w:")

    @Test
    fun `a let array pattern reads its slot`() =
        assertOne("function f() { let [l1] = tup; const w: boolean = l1 }", 2322, decl("number"), "w:")

    @Test
    fun `a let array pattern slot judges a later assignment`() =
        assertOne("function f() { let [a] = tup; a = \"s\" }", 2322, "Type 'string' is not assignable to type 'number'.", "a =")

    // ---------------------------------------------------------------------
    // the DECLARATION reader — defaults
    // ---------------------------------------------------------------------

    @Test
    fun `a const default over an array keeps the literal beside the element`() =
        assertOne("function f() { const [d = \"x\"] = arrN; const w: boolean = d }", 2322, decl("number | \"x\""), "w:")

    @Test
    fun `a let default over an array widens the literal`() =
        assertOne("function f() { let [d = \"x\"] = arrN; const w: boolean = d }", 2322, decl("string | number"), "w:")

    @Test
    fun `an optional member with a subtype default drops the literal - the subtype drop`() =
        assertOne("function f() { const { o: od = 5 } = obj; const w: boolean = od }", 2322, decl("number"), "w:")

    @Test
    fun `an optional string member with a const default reads string`() =
        assertOne("function f() { const { s = \"z\" } = obj; const w: boolean = s }", 2322, decl("string"), "w:")

    @Test
    fun `a union member with a default joins`() =
        assertOne("function f() { const { u = 5 } = obj; const w: boolean = u }", 2322, decl("string | number"), "w:")

    @Test
    fun `a nested pattern with a default recurses through the join`() =
        assertOne("function f() { const { n: { r } = { r: \"z\" } } = obj; const w: boolean = r }", 2322, decl("string"), "w:")

    @Test
    fun `a default over a contextual tuple literal keeps the literal beside the element`() =
        assertOne("function f() { const [x = \"s\"] = [1]; const w: boolean = x }", 2322, decl("number | \"s\""), "w:")

    // ---------------------------------------------------------------------
    // the DECLARATION reader — object shapes the recorder did not cover
    // ---------------------------------------------------------------------

    @Test
    fun `a nested object pattern recurses`() =
        assertOne("function f() { const { n: { r } } = obj; const w: boolean = r }", 2322, decl("string"), "w:")

    @Test
    fun `a union receiver lifts per constituent`() =
        assertOne("function f() { const { p: up } = uni; const w: boolean = up }", 2322, decl("string | number"), "w:")

    @Test
    fun `a string-literal property name reads the member`() =
        assertOne("function f() { const { \"q\": sq } = obj; const w: boolean = sq }", 2322, decl("string"), "w:")

    @Test
    fun `a computed literal property name reads the member`() =
        assertOne("function f() { const { [\"p\"]: cp } = obj; const w: boolean = cp }", 2322, decl("number"), "w:")

    @Test
    fun `a primitive receiver reads its apparent member`() =
        assertOne("function f() { const { length } = \"abc\"; const w: boolean = length }", 2322, decl("number"), "w:")

    @Test
    fun `an enum-typed member reads the enum`() =
        assertOne("function f() { const { k } = kk; const w: boolean = k }", 2322, decl("K"), "w:")

    @Test
    fun `a contextual tuple literal reads the widened element`() =
        assertOne("function f() { const [x] = [1, \"a\"]; takeB(x) }", 2345, arg("number"), "x)")

    // ---------------------------------------------------------------------
    // the ARGUMENT reader (ccet) — body, file level, closure, nested function
    // ---------------------------------------------------------------------

    @Test
    fun `a body object pattern reaches the argument gate`() =
        assertOne("function f() { const { p } = obj; takeB(p) }", 2345, arg("number"), "p)")

    @Test
    fun `a body tuple pattern reaches the argument gate`() =
        assertOne("function f() { const [t] = tup; takeB(t) }", 2345, arg("number"), "t)")

    @Test
    fun `a file-level object pattern reaches the argument gate - the symbol half`() =
        assertOne("const { p: fp } = obj; takeB(fp)", 2345, arg("number"), "fp)")

    @Test
    fun `a file-level tuple pattern reaches the argument gate - the symbol half`() =
        assertOne("const [ft] = tup; takeB(ft)", 2345, arg("number"), "ft)")

    @Test
    fun `a file-level default joins at the symbol half`() =
        assertOne("const [fd = \"x\"] = arrN; const w: boolean = fd", 2322, decl("number | \"x\""), "w:")

    @Test
    fun `a file-level nested pattern over a contextual tuple literal reaches the argument gate`() =
        assertOne("const [[nested]] = [[1]]; takeB(nested)", 2345, arg("number"), "nested)")

    @Test
    fun `a closure reads the enclosing body's pattern name at the argument gate`() =
        assertOne("function f() { const { p } = obj; const g = () => { takeB(p) } }", 2345, arg("number"), "p)")

    @Test
    fun `a nested function reads the enclosing body's pattern name at the argument gate`() =
        assertOne("function f() { const { p } = obj; function inner() { takeB(p) } }", 2345, arg("number"), "p)")

    @Test
    fun `a narrowed pattern name reaches the argument gate narrowed`() =
        assertOne(
            "function f() { const { u } = obj; if (typeof u === \"string\") { takeB(u) } }",
            2345, arg("string"), "u)",
        )

    @Test
    fun `an optional member reaches the argument gate with undefined`() =
        assertOne("function f() { const { o } = obj; takeB(o) }", 2345, arg("number | undefined"), "o)")

    // ---------------------------------------------------------------------
    // the TS2367 reader (spineArith) — body, file level, parameter
    // ---------------------------------------------------------------------

    @Test
    fun `a body object pattern reaches the comparison reader`() =
        assertOne("function f() { const { p: cp } = obj; if (cp === \"x\") {} }", 2367, cmp("number", "string"), "cp ===")

    @Test
    fun `a body tuple pattern reaches the comparison reader`() =
        assertOne("function f() { const [a] = tup; if (a === \"x\") {} }", 2367, cmp("number", "string"), "a ===")

    @Test
    fun `a file-level object pattern reaches the comparison reader - the symbol half`() =
        assertOne("const { p: fcp } = obj; if (fcp === \"x\") {}", 2367, cmp("number", "string"), "fcp ===")

    @Test
    fun `a pattern parameter reaches the comparison reader`() =
        assertOne("function f({ p }: I) { if (p === \"x\") {} }", 2367, cmp("number", "string"), "p ===")

    // ---------------------------------------------------------------------
    // pattern PARAMETERS — the registrar (cta) and populateParameterLocalTypes
    // ---------------------------------------------------------------------

    @Test
    fun `an object pattern parameter reaches the argument gate`() =
        assertOne("function f({ p }: I) { takeB(p) }", 2345, arg("number"), "p)")

    @Test
    fun `an array pattern parameter reads its slots at both readers`() =
        assertRows(
            "function f([a, b]: [number, string]) { takeB(a); const w: boolean = b }",
            listOf(2345 to arg("number"), 2322 to decl("string")), "a)", "w:",
        )

    @Test
    fun `a rest pattern parameter reads its slot`() =
        assertOne(
            "function f(...[c]: [boolean]) { const w: number = c }", 2322,
            "Type 'boolean' is not assignable to type 'number'.", "w:",
        )

    @Test
    fun `residue - an arrow's own parameters are any at the argument gate - pre-existing`() {
        // `ccetEnterFunctionLike` registers every own parameter NAME as `any` when the
        // arrow has no `FunctionType` annotation — an IDENTIFIER `(p: number) =>` as much
        // as a pattern, on HEAD before this round. tsc reports both.
        assertSilent("const f = ({ p }: I) => { takeB(p) }")
        assertSilent("const g = (p: number) => { takeB(p) }")
    }

    @Test
    fun `a method's object pattern parameter reaches the argument gate`() =
        assertOne("class C { m({ p }: I) { takeB(p) } }", 2345, arg("number"), "p)")

    @Test
    fun `a pattern parameter with a default keeps the annotation`() =
        assertOne("function f({ p }: I = obj) { takeB(p) }", 2345, arg("number"), "p)")

    @Test
    fun `an optional member of a pattern parameter with a default drops undefined`() =
        assertRows(
            "function f({ o = 3 }: I) { const w: boolean = o; takeB(o) }",
            listOf(2322 to decl("number"), 2345 to arg("number")), "w:", "o)",
        )

    @Test
    fun `an optional member of a pattern parameter without a default keeps undefined`() =
        assertOne("function f({ o }: I) { takeB(o) }", 2345, arg("number | undefined"), "o)")

    @Test
    fun `a pattern parameter reaches the property-access reader`() =
        assertOne(
            "function f({ p }: I) { p.nope }", 2339,
            "Property 'nope' does not exist on type 'number'.", "nope",
        )

    @Test
    fun `a narrowed pattern parameter member reaches the argument gate narrowed`() =
        assertOne(
            "function f({ u }: I) { if (typeof u === \"string\") { takeB(u) } }",
            2345, arg("string"), "u)",
        )

    // ---------------------------------------------------------------------
    // for-of heads
    // ---------------------------------------------------------------------

    @Test
    fun `a for-of array pattern head binds each slot`() =
        assertRows(
            "function f() { for (const [a, b] of [tup]) { const w: boolean = a; takeB(b) } }",
            listOf(2322 to decl("number"), 2345 to arg("string")), "w:", "b)",
        )

    @Test
    fun `a for-of object pattern head binds the member`() =
        assertOne("function f() { for (const { p } of objs) { takeB(p) } }", 2345, arg("number"), "p)")

    @Test
    fun `a for-of identifier head reaches the argument gate`() =
        assertOne("function f() { for (const s of [\"a\"]) { takeB(s) } }", 2345, arg("string"), "s)")

    @Test
    fun `a for-of object pattern head reaches the property-access reader`() =
        assertOne(
            "function f() { for (const { n } of objs) { n.nope } }", 2339,
            "Property 'nope' does not exist on type '{ r: string; }'.", "nope",
        )

    @Test
    fun `a file-level for-of pattern head reaches the argument gate - the symbol half`() =
        assertOne("for (const [a] of [tup]) { takeB(a) }", 2345, arg("number"), "a)")

    // ---------------------------------------------------------------------
    // negative controls
    // ---------------------------------------------------------------------

    @Test
    fun `negative control - an array literal initializer reads the contextual tuple slot`() =
        assertSilent("function f() { const [x] = [1, \"a\"]; const wq: number = x }")

    @Test
    fun `negative control - a subtype default never prints beside its supertype`() {
        val d = diagnose(prelude + "function f() { const { o: od = 5 } = obj; const w: boolean = od }")
        assert(d.none { "number | 5" in it.message })
        assert(d.map { it.message } == listOf(decl("number")))
    }

    @Test
    fun `negative control - a collapsed-rest tuple refuses`() =
        assertSilent("function f() { const [a, ...r] = restTup; const w: boolean = a; const w2: boolean = r }")

    @Test
    fun `negative control - noUncheckedIndexedAccess refuses`() {
        val d = diagnose(
            prelude + "function f() { const [a] = arrN; const w: boolean = a }",
            directives = "// @strict: true\n// @noUncheckedIndexedAccess: true",
        )
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - a function-shaped member keeps round 464b's refusal`() =
        assertSilent("function f() { const { cb } = obj; const w: boolean = cb }")

    @Test
    fun `negative control - a compatible pattern name stays silent everywhere`() =
        assertSilent("function f({ q }: I) { const [a] = tup; const { p } = obj; const w: number = a + p; takeB(q === \"s\") }")

    @Test
    fun `negative control - a refused name still shadows a same-file function - round 475`() {
        // `{ ...alpha }` is an object REST, which stage 1 REFUSES; the name must still
        // register `any`, or the read resolves the same-named FUNCTION below and prints
        // its signature. tsc reports the rest's object type here (a recorded residue).
        val src = "function f({ ...alpha }: I) { const w: boolean = alpha }\nfunction alpha(): number { return 1 }"
        val d = diagnose(prelude + src)
        assert(d.none { "=>" in it.message })
        assert(d.isEmpty())
    }

    @Test
    fun `a pattern parameter shadows a cross-file function with its member type - round 475`() {
        val d = diagnose(
            """
            // @Filename: other.ts
            export function alpha(): { x: number } { return { x: 1 } }

            // @Filename: main.ts
            import { alpha } from "./other"
            interface Inner { alpha: string }
            export function f({ alpha }: Inner) { alpha.x }
            export function j({ alpha }: Inner) { return { alpha } }
            export const use = alpha()
            """,
        )
        d should {
            have(map { it.code to it.message } == listOf(2339 to "Property 'x' does not exist on type 'string'."))
        }
    }

    // ---------------------------------------------------------------------
    // the SYMBOL half — read directly, because every diagnostic reader at file
    // level is ALSO served by a walk-scoped recorder (the ccet leave-time and
    // spineArith arms), so no diagnostic pin can see this arm alone; what only
    // the symbol serves is a consumer with no walk — the oracle's hover, an
    // exported binding read across files (a pre-existing binder gap today)
    // ---------------------------------------------------------------------

    @Test
    fun `the symbol half - a file-level pattern name's symbol answers its binding element type`() {
        val options = CompilerOptions()
        val src = """
            interface I { p: number; o?: number; n: { r: string } }
            declare const obj: I
            declare const tup: [number, string]
            const { p: fp, o: fo, n: { r: fr } } = obj
            const [ft, ...frest] = tup
            const [fd = "x"] = [1]
            let [fl] = tup
        """.trimIndent()
        val result = Binder(options).bind(Parser(src, "/proj/a.ts").parse())
        val checker = Checker(options, listOf(result), isMultiFileSource = true)
        val locals = result.locals
        val fp = locals["fp"]; val fo = locals["fo"]; val fr = locals["fr"]
        val ft = locals["ft"]; val frest = locals["frest"]; val fd = locals["fd"]; val fl = locals["fl"]
        assert(fp != null && fo != null && fr != null && ft != null && frest != null && fd != null && fl != null)
        assert(checker.getTypeOfSymbol(fp).toString() == "number")
        assert(checker.getTypeOfSymbol(fo).toString() == "number | undefined")
        assert(checker.getTypeOfSymbol(fr).toString() == "string")
        assert(checker.getTypeOfSymbol(ft).toString() == "number")
        val restSlots = (checker.getTypeOfSymbol(frest) as? Type.Object)?.tupleElementTypes?.map { it.toString() }
        assert(restSlots == listOf("string"))
        assert(checker.getTypeOfSymbol(fd).toString() == "number | \"x\"")
        assert(checker.getTypeOfSymbol(fl).toString() == "number")
    }

    // ---------------------------------------------------------------------
    // the four mechanisms the 8-profile grid forced — each a false positive the
    // typed bindings surfaced on tsc's own sources, closed at its root
    // ---------------------------------------------------------------------

    @Test
    fun `an indexed access of an optional property includes undefined - moduleSpecifiers 242`() =
        assertSilent(
            "interface UP { readonly ending?: \"auto\" | \"js\" }\n" +
                "declare function pref(p: UP[\"ending\"], n: number): void\n" +
                "function f({ ending }: UP) { pref(ending, 1) }",
        )

    @Test
    fun `Required strips the undefined an optional property's indexed access carries - expressionToTypeNode`() =
        assertSilent(
            "interface Tracker { report?(node: number): void }\n" +
                "interface Ctx { tracker: Required<Pick<Tracker, \"report\">> }\n" +
                "function f(context: Ctx) { context.tracker.report(1) }",
        )

    @Test
    fun `a generic guard whose target has no inference site narrows to its constraint - watchUtilities 628`() =
        assertSilent(
            "interface BP { getProgramOrUndefined(): number; b: 1 }\n" +
                "interface Pr { kind: \"p\"; getX(): number }\n" +
                "declare function isBP<T extends BP>(program: Pr | BP): program is T\n" +
                "declare function isArr(p: unknown): p is readonly string[]\n" +
                "function f({ program }: { program: Pr | BP | readonly string[] | undefined }) {\n" +
                "  if (!program) return false\n" +
                "  const real = isArr(program) ? undefined : isBP(program) ? program.getProgramOrUndefined() : program\n" +
                "}",
        )

    @Test
    fun `a function member of a type-parameter-carrying annotation is refused - watchPublic 152`() =
        assertSilent(
            "interface BP { b: 1 }\n" +
                "type Create<T extends BP> = (n: number, old?: T) => T\n" +
                "interface IPO<T extends BP> { names: readonly string[]; createProgram?: Create<T> }\n" +
                "declare const cesd: Create<BP>\n" +
                "function f<T extends BP = BP>({ createProgram, names }: IPO<T>): T {\n" +
                "  createProgram = createProgram || cesd as any as Create<T>\n" +
                "  return createProgram(1)\n" +
                "}",
        )

    @Test
    fun `a function member of a concrete annotation still types`() =
        assertOne(
            "function f({ cb }: I) { const w: boolean = cb }", 2322,
            decl("(x: number) => void"), "w:",
        )

    // ---------------------------------------------------------------------
    // recorded residues — the expectation is OURS
    // ---------------------------------------------------------------------

    @Test
    fun `negative control - a multi-element const pattern over a discriminated union refuses`() {
        // tsc re-reads such an element from the FLOW-NARROWED parent at every use
        // (`getNarrowedTypeOfSymbol`): `a` is `never` in the exhausted `default`, so the
        // lifted `[1] | []` would be a false TS2322 (the corpus's `arrayDestructuringInSwitch2`).
        val src = "type X = { kind: \"a\", a: [1] } | { kind: \"b\", a: [] }\n" +
            "function foo(x: X): 1 { const { kind, a } = x; switch (kind) { case \"a\": return a[0]; case \"b\": return 1; default: return a } }"
        val d = diagnose(prelude + src)
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `a single-element const pattern over a union still lifts`() =
        assertOne("function f() { const { p } = uni; takeB(p) }", 2345, arg("string | number"), "p)")

    @Test
    fun `a let pattern over a union lifts whatever its element count`() =
        assertOne("function f() { let { p, ...rest } = uni; const w: boolean = p }", 2322, decl("string | number"), "w:")

    @Test
    fun `residue - an object rest is any`() =
        assertSilent("function f() { const { p, ...others } = obj; const w: boolean = others }")

    @Test
    fun `residue - a contextual pattern parameter is any`() =
        assertSilent("objs.map(({ p }) => { const w: boolean = p; return p })")

    @Test
    fun `residue - a for-of over a Map head is any`() {
        val d = diagnose(
            prelude + "declare const m: Map<string, number>\nfunction f() { for (const [k, v] of m) { const w: boolean = k } }",
            directives = "// @strict: true\n// @useRealLibs: true",
        )
        assert(d.isEmpty())
    }
}
