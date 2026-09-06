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
 * (CHK.93) STAGE 1 — const assertions (`x as const`, `<const>x`) carry LITERAL types
 * through a const context — and STAGE 2, readonly-ness (see the section at the bottom
 * and [ReadonlyTupleTest] for the declared-type twin). Every row was reproduced against
 * tsgo 7.0.2 AND pristine `typescript@6.0.3` before any code was written (the recon's
 * `chk85c/` r01-r30 plus the probes of this round); the two references agree on every
 * row bar one union member ORDER (noted where it applies).
 *
 * FIVE DELIVERABLES, each with a negative control:
 *
 * (a) a const assertion answers its OPERAND's const-context type in
 *     `getTypeOfExpressionCore` (tsc `checkAssertionWorker` →
 *     `getRegularTypeOfLiteralType`); before this a `const` type reference fell off
 *     `getTypeFromTypeReference`'s ladder and every object / array / enum-member
 *     const assertion was `any`. A REGULAR literal never widens, at a `let` as at a
 *     `const` — decided off the AST ([Checker.isConstAssertionExpr]) because a boolean
 *     literal is a shared singleton here.
 * (b) `getTypeOfObjectLiteral` keeps a member's literal type under a const context,
 *     the context computed ONCE per literal (tsc `checkObjectLiteral`'s `inConstContext`);
 *     the literal is frozen against `widenType` (the Object.freeze precedent) and its
 *     scalar members are registered non-widening, so a `let` read keeps the literal.
 * (c) `getTypeOfArrayLiteral` under a const context builds a TUPLE of const-context
 *     elements, so `([1, 2] as const)[0]` reads `1` — with the relation rule the tuple
 *     needed and every declared tuple was missing: a tuple relates to `Array<T>` /
 *     `ReadonlyArray<T>` when every element relates to `T` (a false TS2740 before).
 * (d) TS1355 generalised to tsc's `isValidConstAssertionArgument` — an identifier, a
 *     call, `null` / `undefined`, a non-enum member access, in both spellings.
 * (e) the PREREQUISITE: `checkPropertyAccessAssignment` judges a literal right-hand
 *     side by its literal type on the rejecting path (`mo.v = "a"` against `v: "a"` was
 *     an ours-only TS2322 `'string'` with no `as const` anywhere) and prints the literal
 *     where it does not relate.
 *
 * STAGE 2 — READONLY-NESS (landed after stage 1):
 * (f) a const-context object member is READ-ONLY — the minted member (property,
 *     shorthand, method, and a spread COPY made inside a const context) joins the
 *     `Readonly<T>` / `Object.freeze` side-channel, so a write is TS2540, a `delete` is
 *     TS2704, and the display carries `readonly `; a spread OUTSIDE a const context keeps
 *     the literal types and drops the bit (tsc `getSpreadType`'s `readonly` argument).
 * (g) a const array is a READONLY TUPLE unless its contextual type has a MUTABLE
 *     array-like constituent (tsc's `isMutableArrayLikeType`) — its members fall to
 *     `ReadonlyArray` (`push` → TS2339), it never relates to a mutable array or tuple
 *     (TS4104 at every position), its slots are read-only (TS2540 `'0'`), and it displays
 *     `readonly [1, 2]`.
 * Still recorded at today's answer: TS2493 out of range (r20) — not modelled.
 *
 * RECORDED residues (ours): the argument elaboration through a const assertion prints
 * the TARGET literal widened (`'string'` for `"b"` — (CHK.92)(a)'s emitter, not this
 * round's); a template with substitutions is `string`, not tsc's template literal type;
 * a `let` read of an enum-member element widens to the enum; a spread ELEMENT keeps a
 * const array at `any`. (A body-local scalar argument, silent when this class was
 * written, reads its literal since (CHK.95) — `BodyLocalLiteralArgumentTest`.)
 */
class ConstAssertionTest {

    private val prelude = """
        enum K { A, B }
        declare function probe(x: never): void
        declare function takeB(x: "b"): void
        declare const cond: boolean
        export {}
    """.trimIndent() + "\n"

    /** 0-based line of the first line appended after [prelude] (line 0 is the directive). */
    private val rowLine = prelude.count { it == '\n' } + 1

    private fun messages(source: String): List<String> = diagnose(prelude + source).map { it.message }

    /** tsc's 1-based column of [needle]'s first character in [source]. */
    private fun col(source: String, needle: String): Int = source.indexOf(needle) + 1

    /** tsc's 1-based column of the LAST occurrence of [needle] in [source]. */
    private fun colLast(source: String, needle: String): Int = source.lastIndexOf(needle) + 1

    private val aNotB = "Type '\"a\"' is not assignable to type '\"b\"'."
    private val ts1355 = "A 'const' assertion can only be applied to references to enum members, " +
        "or string, number, boolean, array, or object literals."

    // ---------------------------------------------------------------------
    // (a) the assertion types its operand
    // ---------------------------------------------------------------------

    @Test
    fun `a const-asserted object literal member reads its literal type - r01`() {
        val src = "const zs: \"b\" = ({ v: \"a\" } as const).v"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(aNotB))
        assert(d[0].code == 2322)
        assert(d[0].line == rowLine)
        assert(d[0].character == col(src, "zs"))
    }

    @Test
    fun `negative control - without the assertion the member widens - r11`() {
        assert(messages("const zs: \"b\" = { v: \"a\" }.v") == listOf("Type 'string' is not assignable to type '\"b\"'."))
    }

    @Test
    fun `negative control - satisfies alone widens and a plain cast is the cast`() {
        assert(messages("const zo = { v: \"a\" } satisfies { v: string }; const zs: \"b\" = zo.v") ==
            listOf("Type 'string' is not assignable to type '\"b\"'."))
        assert(messages("const zt: \"b\" = ({ v: \"a\" } as { v: string }).v") ==
            listOf("Type 'string' is not assignable to type '\"b\"'."))
    }

    @Test
    fun `an enum member through a const object keeps the member - r02`() {
        val src = "const zw: K.B = ({ v: K.A } as const).v"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("Type 'K.A' is not assignable to type 'K.B'."))
        assert(d[0].character == col(src, "zw"))
    }

    @Test
    fun `the prefix spelling types its operand too - r07`() {
        assert(messages("const zs: \"b\" = (<const>{ v: \"a\" }).v") == listOf(aNotB))
    }

    @Test
    fun `a const assertion under satisfies keeps the literal - r08`() {
        val src = "const zo = { v: \"a\" } as const satisfies { v: string }; const zs: \"b\" = zo.v"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(aNotB))
        assert(d[0].character == col(src, "zs"))
    }

    @Test
    fun `a direct enum member const assertion is the member - r19`() {
        assert(messages("const zw: K.B = K.A as const") == listOf("Type 'K.A' is not assignable to type 'K.B'."))
    }

    @Test
    fun `a boolean member keeps its literal - r22`() {
        assert(messages("const zb: false = ({ v: true } as const).v") == listOf("Type 'true' is not assignable to type 'false'."))
    }

    @Test
    fun `parentheses around the operand and the assertion are transparent - r23`() {
        assert(messages("const zs: \"b\" = (({ v: (\"a\") } as const)).v") == listOf(aNotB))
    }

    @Test
    fun `a shorthand member reads the const local's literal - r28`() {
        val src = "const zv = \"a\"; const zo = { zv } as const; const zs: \"b\" = zo.zv"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(aNotB))
        assert(d[0].character == col(src, "zs"))
    }

    @Test
    fun `a nested object literal reads the context through its parent - r05`() {
        val src = "const zo = { a: { b: \"x\" } } as const; const zs: \"y\" = zo.a.b"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("Type '\"x\"' is not assignable to type '\"y\"'."))
        assert(d[0].character == col(src, "zs"))
    }

    @Test
    fun `a spread of a const object keeps the literal types and stays writable - r13`() {
        // Both references: the literal types survive the spread, the readonly bit does
        // not — `zm.v = "b"` reports the LITERAL mismatch (the (e) display) and not TS2540.
        val src = "const zc = { v: \"a\" } as const; const zm = { ...zc }; zm.v = \"b\"; const zs: \"b\" = zm.v"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("Type '\"b\"' is not assignable to type '\"a\"'.", aNotB))
        assert(d.map { it.code } == listOf(2322, 2322))
        assert(d[0].character == col(src, "zm.v = "))
        assert(d[1].character == col(src, "zs"))
    }

    @Test
    fun `a let initialized by a const assertion keeps the regular literal - r25`() {
        val src = "let zx = \"a\" as const; const zy: \"b\" = zx"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(aNotB))
        assert(d[0].character == col(src, "zy"))
    }

    @Test
    fun `a let initialized by a boolean const assertion keeps true`() {
        assert(messages("let zb = true as const; const zc: false = zb") == listOf("Type 'true' is not assignable to type 'false'."))
    }

    @Test
    fun `a let initialized by a const assertion refuses another literal`() {
        val src = "let zx = \"a\" as const; zx = \"b\""
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("Type '\"b\"' is not assignable to type '\"a\"'."))
        assert(d[0].character == colLast(src, "zx = "))
    }

    @Test
    fun `negative control - a let initialized by a plain literal widens`() {
        assert(messages("let zx = \"a\"; const zy: \"b\" = zx") == listOf("Type 'string' is not assignable to type '\"b\"'."))
    }

    @Test
    fun `a let initialized by a const object or tuple keeps them`() {
        assert(messages("let zo = { v: \"a\" } as const; const zs: \"b\" = zo.v") == listOf(aNotB))
        assert(messages("let zt = [1, 2] as const; const ze: 2 = zt[0]") == listOf("Type '1' is not assignable to type '2'."))
    }

    @Test
    fun `a const-context literal survives widening as an array element`() {
        // The path where `widenType` reaches a const-context object or tuple: the SYMBOL
        // half of an inferred declaration (a class property here — a file-level local is
        // served by the recorder, which widens by its own table) whose initializer is an
        // ordinary array literal holding the const literal as an ELEMENT, so the
        // `Array<T>` argument is widened member by member. The frozen mark is what keeps
        // the literal (arm a8 read `'string'` / `'number'` without it); every other shape
        // is guarded syntactically before `widenType` runs.
        assert(messages("class ZC { p = [{ v: \"a\" } as const]; q = [[1, 2] as const] } const zc = new ZC(); " +
            "const zs: \"b\" = zc.p[0].v; const ze: 2 = zc.q[0][0]") ==
            listOf(aNotB, "Type '1' is not assignable to type '2'."))
    }

    @Test
    fun `a class property initialized by a const assertion keeps it`() {
        assert(messages("class ZC { p = { v: \"a\" } as const; q = [1, 2] as const } const zc = new ZC(); const zs: \"b\" = zc.p.v") ==
            listOf(aNotB))
        assert(messages("class ZC { q = [1, 2] as const } const zc = new ZC(); const ze: 2 = zc.q[0]") ==
            listOf("Type '1' is not assignable to type '2'."))
    }

    @Test
    fun `a returned const assertion infers the const-context type`() {
        assert(messages("function zr() { return [1, \"a\"] as const } const zs: \"b\" = zr()[1]") == listOf(aNotB))
        assert(messages("function zp(): \"b\" { return ({ v: \"a\" } as const).v }") == listOf(aNotB))
    }

    @Test
    fun `body-local const assertions read the same at every declaration`() {
        val src = "function zfn() { let zx = \"a\" as const; const zy: \"b\" = zx; " +
            "const zo = { v: \"a\" } as const; const zs: \"b\" = zo.v; " +
            "const zt = [1, 2] as const; const ze: 2 = zt[0]; }"
        assert(messages(src) == listOf(aNotB, aNotB, "Type '1' is not assignable to type '2'."))
    }

    @Test
    fun `the never arm prints the const-context types`() {
        val src = "const o1 = { v: \"a\" } as const; probe(o1); const a1 = [1, 2] as const; probe(a1); " +
            "const e1 = K.A as const; probe(e1); probe(({ v: \"a\" } as const).v); probe(([1, 2] as const)[0])"
        assert(messages(src) == listOf(
            "Argument of type '{ readonly v: \"a\"; }' is not assignable to parameter of type 'never'.",
            "Argument of type 'readonly [1, 2]' is not assignable to parameter of type 'never'.",
            "Argument of type 'K.A' is not assignable to parameter of type 'never'.",
            "Argument of type '\"a\"' is not assignable to parameter of type 'never'.",
            "Argument of type '1' is not assignable to parameter of type 'never'.",
        ))
    }

    @Test
    fun `a const-asserted scalar argument reads the literal`() {
        assert(messages("let zy = \"a\" as const; takeB(zy); const zo = { v: \"a\" } as const; takeB(zo.v)") == listOf(
            "Argument of type '\"a\"' is not assignable to parameter of type '\"b\"'.",
            "Argument of type '\"a\"' is not assignable to parameter of type '\"b\"'.",
        ))
    }

    // ---------------------------------------------------------------------
    // (b) the object literal keeps its members, the context computed once
    // ---------------------------------------------------------------------

    @Test
    fun `a let read of a const object member keeps the regular literal`() {
        val src = "const zo = { v: \"a\" } as const; let zw = zo.v; zw = \"b\""
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("Type '\"b\"' is not assignable to type '\"a\"'."))
        assert(d[0].character == colLast(src, "zw = "))
    }

    @Test
    fun `a conditional member keeps a union of its literals`() {
        // Both references report the union; pristine orders it `"b" | "a"` and tsgo
        // `"a" | "b"` — this checker's order is tsgo's (FORM, recorded).
        assert(messages("const zo = { x: cond ? \"a\" : \"b\" } as const; const zs: \"c\" = zo.x") ==
            listOf("Type '\"a\" | \"b\"' is not assignable to type '\"c\"'."))
    }

    @Test
    fun `a const-asserted discriminant member is the literal`() {
        assert(messages("declare const s: number; const zk: \"other\" = ({ type: \"symbol\" as const, s }).type") ==
            listOf("Type '\"symbol\"' is not assignable to type '\"other\"'."))
    }

    @Test
    fun `a nested array member is a readonly tuple whose element reads its literal`() {
        assert(messages("const zo = { x: [1, \"a\"] } as const; const zs: \"b\" = zo.x[1]; const zn: number = zo.x") == listOf(
            aNotB,
            "Type 'readonly [1, \"a\"]' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `a spread inside a const context keeps both halves`() {
        assert(messages("const zc = { v: \"a\" } as const; const zm = { ...zc, w: 1 } as const; const zs: \"b\" = zm.v; const zn: 2 = zm.w") ==
            listOf(aNotB, "Type '1' is not assignable to type '2'."))
    }

    // ---------------------------------------------------------------------
    // (c) the array literal becomes a tuple
    // ---------------------------------------------------------------------

    @Test
    fun `a const array element reads its literal - r04`() {
        val src = "const zt = [1, 2] as const; const ze: 2 = zt[0]"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("Type '1' is not assignable to type '2'."))
        assert(d[0].character == col(src, "ze"))
    }

    @Test
    fun `an inline const array element reads its literal - r14 and the prefix form`() {
        assert(messages("const zs: \"b\" = ([\"a\"] as const)[0]") == listOf(aNotB))
        assert(messages("const zs: \"b\" = (<const>[\"a\"])[0]") == listOf(aNotB))
    }

    @Test
    fun `an object element of a const array reads the context - r21`() {
        val src = "const za = [{ v: \"a\" }] as const; const zs: \"b\" = za[0].v"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(aNotB))
        assert(d[0].character == col(src, "zs"))
    }

    @Test
    fun `enum members in a const tuple keep the member`() {
        assert(messages("const za = [K.A, K.B] as const; const zw: K.B = za[0]") ==
            listOf("Type 'K.A' is not assignable to type 'K.B'."))
    }

    @Test
    fun `a let read of a const tuple element keeps the regular literal`() {
        val src = "const zt = [1, 2] as const; let ze = zt[0]; ze = 2"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("Type '2' is not assignable to type '1'."))
        assert(d[0].character == colLast(src, "ze = "))
    }

    @Test
    fun `iterating a const tuple reads the union of its literals and length is literal`() {
        assert(messages("const zd = [\"dependencies\", \"peerDependencies\"] as const; for (const zf of zd) { const zs: \"x\" = zf; }") ==
            listOf("Type '\"dependencies\" | \"peerDependencies\"' is not assignable to type '\"x\"'."))
        assert(messages("const zd = [\"a\", \"b\"] as const; const zn: string = zd.length") ==
            listOf("Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `an empty const array is an empty readonly tuple`() {
        assert(messages("const ze = [] as const; const zn: number = ze") ==
            listOf("Type 'readonly []' is not assignable to type 'number'."))
    }

    @Test
    fun `a const tuple under a mutable array context is mutable and relates`() {
        // Both references are SILENT on the first two: the contextual `number[]` is a
        // MUTABLE array-like, so the tuple is mutable and assignable (stage 2's
        // `isMutableArrayLikeType` subtlety); a readonly context keeps it readonly, which
        // relates too. The third is the negative control.
        assert(messages("const zarr: number[] = [1, 2] as const; zarr.push(3)").isEmpty())
        assert(messages("const zt = [1, 2] as const; const zr: readonly number[] = zt").isEmpty())
        val d = diagnose(prelude + "const zs2: string[] = [1, 2] as const")
        assert(d.map { it.code } == listOf(2740))
    }

    @Test
    fun `a declared tuple relates to an array of its element type`() {
        // A pre-existing false TS2740 the relation rule of (c) removes: in tsc a
        // tuple's base type is `Array<union of its elements>`.
        assert(messages("declare const mt: [1, 2]; const za: number[] = mt; const zb: readonly number[] = mt").isEmpty())
        val d = diagnose(prelude + "declare const mu: [number, string]; const zd: number[] = mu")
        assert(d.map { it.code } == listOf(2740))
    }

    @Test
    fun `a tuple narrowed out of a union by an array guard has every Array member`() {
        // tsc's own services/utilities.ts `diagnosticToString`: `isArray(diag)` narrows
        // `DM | DA` (DA a tuple) to the tuple — which the (c) relation rule now lets
        // through the guard — and `diag.slice(1)` must not read as a missing member.
        // Existence only (the call's type stays `any`, pre-existing); a genuinely
        // absent member is the control.
        val shape = "type DM = { code: number }; type DA = [message: DM, ...args: (string | number)[]]; " +
            "declare function isArray(value: any): value is readonly any[]; "
        assert(messages(shape + "export function zf(diag: DM | DA): unknown { return isArray(diag) ? diag.slice(1) : diag.code; }").isEmpty())
        assert(messages(shape + "export function zg(diag: DM | DA): unknown { return isArray(diag) ? diag.nope : diag.code; }") ==
            listOf("Property 'nope' does not exist on type 'DA'."))
    }

    // ---------------------------------------------------------------------
    // (d) TS1355
    // ---------------------------------------------------------------------

    @Test
    fun `an identifier operand is TS1355 at the operand - r06`() {
        val src = "const zx = \"a\"; const zy = zx as const"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(ts1355))
        assert(d[0].code == 1355)
        assert(d[0].line == rowLine)
        assert(d[0].character == col(src, "zx as"))
        assert(d[0].length == 2)
    }

    @Test
    fun `a call operand is TS1355 - r27`() {
        val src = "declare function zg(): string; const zy = zg() as const"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(ts1355))
        assert(d[0].character == colLast(src, "zg()"))
    }

    @Test
    fun `the prefix spelling reports TS1355 too`() {
        val src = "const zx = \"a\"; const zy = <const>zx"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(ts1355))
        assert(d[0].character == colLast(src, "zx"))
        assert(d[0].length == 2)
    }

    @Test
    fun `null and undefined operands are TS1355`() {
        val src = "const zn = null as const; const zu = undefined as const"
        val d = diagnose(prelude + src)
        assert(d.map { it.code } == listOf(1355, 1355))
        assert(d[0].character == col(src, "null"))
        assert(d[1].character == col(src, "undefined"))
    }

    @Test
    fun `a member access whose base is not an enum is TS1355`() {
        // An enum MEMBER's member, a namespace member, a body-local object's member.
        assert(diagnose(prelude + "const zx = K.A.toString as const").map { it.code } == listOf(1355))
        assert(diagnose(prelude + "namespace N { export const x = 1 } const zy = N.x as const").map { it.code } == listOf(1355))
        assert(diagnose(prelude + "function zf() { const zl = { a: 1 }; return zl.a as const }").map { it.code } == listOf(1355))
    }

    @Test
    fun `negative control - every valid operand form is silent`() {
        assert(messages("const a1 = K.A as const; const a2 = K[\"A\"] as const; const a3 = (K).A as const; " +
            "const a4 = -1 as const; const a5 = +1 as const; const a6 = `a` as const; const a7 = (1) as const; " +
            "const a8 = [1] as const; const a9 = {} as const; const b1 = true as const; const b2 = <const>\"a\"; " +
            "function zf() { enum L { P } return L.P as const }").isEmpty())
    }

    @Test
    fun `negative control - an unresolved base reports only its own TS2304`() {
        // tsc reports TS1355 beside the TS2304; this checker skips the member-access
        // verdict for a base it cannot resolve (a deliberate conservatism, recorded).
        assert(diagnose(prelude + "const zz = unknownBase.x as const").map { it.code } == listOf(2304))
    }

    // ---------------------------------------------------------------------
    // (e) the prerequisite: a literal property write
    // ---------------------------------------------------------------------

    @Test
    fun `a literal write to a literal-typed member is silent`() {
        assert(messages("declare const mo: { v: \"a\" }; mo.v = \"a\"; declare const mn: { n: 1 }; mn.n = 1; " +
            "declare const me: { v: K.A }; me.v = K.A; declare const ms: { v: string }; ms.v = \"b\"").isEmpty())
    }

    @Test
    fun `a wrong literal write prints the literal`() {
        val src = "declare const mo: { v: \"a\" }; mo.v = \"b\"; declare const mn: { n: 1 }; mn.n = 2"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(
            "Type '\"b\"' is not assignable to type '\"a\"'.",
            "Type '2' is not assignable to type '1'.",
        ))
        assert(d[0].character == col(src, "mo.v = "))
        assert(d[1].character == col(src, "mn.n = "))
    }

    @Test
    fun `negative control - a literal write against an object member keeps the base display`() {
        assert(messages("declare const mo: { v: { x: number } }; mo.v = \"b\"") ==
            listOf("Type 'string' is not assignable to type '{ x: number; }'."))
    }

    // ---------------------------------------------------------------------
    // Stage 2 (g): the const array is a READONLY tuple
    // ---------------------------------------------------------------------

    private fun ts4104(source: String, target: String) =
        "The type '$source' is 'readonly' and cannot be assigned to the mutable type '$target'."

    @Test
    fun `a const tuple against a mutable array is TS4104 - r03`() {
        val src = "const zt = [1, 2] as const; const zarr: number[] = zt"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(ts4104("readonly [1, 2]", "number[]")))
        assert(d[0].code == 4104)
        assert(d[0].line == rowLine)
        assert(d[0].character == col(src, "zarr"))
        assert(d[0].length == 4)
        assert(d[0].messageChain.isEmpty())
        assert(messages("const ze = [] as const; const za: number[] = ze") == listOf(ts4104("readonly []", "number[]")))
    }

    @Test
    fun `a const tuple against a mutable tuple is TS4104 and readonly targets accept it - r18`() {
        val src = "const zt = [1, 2] as const; const zu: [1, 2] = zt; const zr: readonly [1, 2] = zt; const za: readonly number[] = zt"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(ts4104("readonly [1, 2]", "[1, 2]")))
        assert(d[0].character == col(src, "zu"))
    }

    @Test
    fun `push on a const tuple is TS2339 on the readonly display - r09`() {
        val src = "const zt = [1, 2] as const; zt.push(3)"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("Property 'push' does not exist on type 'readonly [1, 2]'."))
        assert(d[0].code == 2339)
        assert(d[0].character == col(src, "push"))
        assert(d[0].length == 4)
        assert(messages("const zt = [1, 2] as const; const zs = zt.slice(); const zn: number = zt.length; zt.nope") ==
            listOf("Property 'nope' does not exist on type 'readonly [1, 2]'."))
    }

    @Test
    fun `a const tuple slot write is TS2540 on the slot`() {
        val src = "const zt = [1, 2] as const; zt[0] = 1"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("Cannot assign to '0' because it is a read-only property."))
        assert(d[0].code == 2540)
        assert(d[0].character == colLast(src, "0"))
    }

    @Test
    fun `the const tuple displays with the readonly prefix - r26`() {
        assert(messages("const zt = [1, 2] as const; const zz: string = zt") ==
            listOf("Type 'readonly [1, 2]' is not assignable to type 'string'."))
    }

    @Test
    fun `a const array under a mutable contextual type at every position stays mutable`() {
        // tsc `checkArrayLiteral`: readonly only when NO constituent of the contextual
        // type is a mutable array-like — the return, argument, assignment, class
        // property and union-annotation contexts are all passed through the assertion.
        assert(messages("function zf(): number[] { return [1, 2] as const }").isEmpty())
        assert(messages("declare function zg(x: number[]): void; zg([1, 2] as const)").isEmpty())
        assert(messages("let za: number[] = []; za = [1, 2] as const").isEmpty())
        assert(messages("class ZC { p: number[] = [1, 2] as const }").isEmpty())
        assert(messages("const zu: number[] | string = [1, 2] as const").isEmpty())
        assert(messages("const zc: { x: number[] } = { x: [1, 2] } as const").isEmpty())
    }

    @Test
    fun `a const array under a readonly contextual type is readonly`() {
        assert(messages("const zro: readonly number[] = [1, 2] as const; zro.push(3)") ==
            listOf("Property 'push' does not exist on type 'readonly number[]'."))
        assert(messages("const zo = { x: [1, 2] } as const; const zn: number = zo.x") ==
            listOf("Type 'readonly [1, 2]' is not assignable to type 'number'."))
    }

    @Test
    fun `residue - an out-of-range const tuple index is silent - r20`() {
        // tsc: TS2322 `undefined` + TS2493 — not modelled.
        assert(messages("const zt = [1, 2] as const; const zu: number = zt[5]").isEmpty())
    }

    // ---------------------------------------------------------------------
    // Stage 2 (f): const-context members are read-only
    // ---------------------------------------------------------------------

    private fun ts2540(name: String) = "Cannot assign to '$name' because it is a read-only property."

    @Test
    fun `a literal write to a const-asserted member is TS2540 alone - r10`() {
        val src = "const zo = { v: \"a\" } as const; zo.v = \"a\""
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(ts2540("v")))
        assert(d[0].code == 2540)
        assert(d[0].line == rowLine)
        assert(d[0].character == colLast(src, "v = "))
        assert(d[0].length == 1)
    }

    @Test
    fun `an enum write to a const-asserted member is TS2540 alone - r30`() {
        // tsc reports the readonly write and does NOT judge the value (B435's rule).
        val src = "const zo = { v: K.A } as const; zo.v = K.B"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(ts2540("v")))
        assert(d[0].character == colLast(src, "v = "))
    }

    @Test
    fun `a method and a shorthand member of a const object are read-only`() {
        val src = "const zo = { m() { return 1 } } as const; zo.m = () => 2"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(ts2540("m")))
        assert(d[0].character == colLast(src, "m = "))
        val src2 = "const zv = \"a\"; const zs = { zv } as const; zs.zv = \"a\""
        val d2 = diagnose(prelude + src2)
        assert(d2.map { it.message } == listOf(ts2540("zv")))
        assert(d2[0].character == colLast(src2, "zv = "))
    }

    @Test
    fun `a spread inside a const context is read-only`() {
        val src = "const zc = { v: \"a\" } as const; const zm = { ...zc } as const; zm.v = \"b\""
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(ts2540("v")))
        assert(d[0].character == colLast(src, "v = "))
    }

    @Test
    fun `delete of a const-asserted member is TS2704 alone`() {
        val src = "const zo = { v: \"a\" } as const; delete zo.v"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("The operand of a 'delete' operator cannot be a read-only property."))
        assert(d[0].code == 2704)
        assert(d[0].character == colLast(src, "zo.v"))
        assert(d[0].length == 4)
    }

    @Test
    fun `negative control - a plain object literal member stays writable`() {
        assert(messages("const zo = { v: \"a\" }; zo.v = \"b\"; const zt = [1, 2]; zt.push(3); zt[0] = 5; delete zo.v").map { it } ==
            listOf("The operand of a 'delete' operator must be optional."))
    }

    @Test
    fun `the const object displays its members with the readonly prefix - r12`() {
        assert(messages("const zo = { v: \"a\", n: 1, b: true } as const; const zz: number = zo") ==
            listOf("Type '{ readonly v: \"a\"; readonly n: 1; readonly b: true; }' is not assignable to type 'number'."))
    }

    // ---------------------------------------------------------------------
    // Recorded residues (the expectation is OURS)
    // ---------------------------------------------------------------------

    @Test
    fun `residue - the argument elaboration through a const assertion widens the target display - r24`() {
        // Both references: `Type '"a"' is not assignable to type '"b"'.` at `v`. The
        // position and the source are theirs; the target `'string'` is (CHK.92)(a)'s
        // per-property emitter widening the target literal for display, not this round's.
        val src = "declare function zf(x: { v: \"b\" }): void; zf({ v: \"a\" } as const)"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("Type '\"a\"' is not assignable to type 'string'."))
        assert(d[0].code == 2322)
        assert(d[0].character == col(src, "v: \"a\" } as const"))
    }

    @Test
    fun `an excess property under a const assertion is still reported`() {
        val src = "declare function zf(x: { v: \"a\" }): void; zf({ v: \"a\", extra: 1 } as const)"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(
            "Object literal may only specify known properties, and 'extra' does not exist in type '{ v: \"a\"; }'.",
        ))
        assert(d[0].character == col(src, "extra"))
    }

    @Test
    fun `residue - a template with substitutions stays string`() {
        // tsc: the template literal type `` `p${number}` ``.
        assert(messages("declare const zi: number; const zk = `p\${zi}` as const; const zn: number = zk") ==
            listOf("Type 'string' is not assignable to type 'number'."))
    }
}
