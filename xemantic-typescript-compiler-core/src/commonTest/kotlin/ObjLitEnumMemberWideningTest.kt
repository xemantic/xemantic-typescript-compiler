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
 * (CHK.91) — the (CHK.85)(a) unblocker: an object literal's MUTABLE member widens a FRESH
 * enum-member value to its enum unless the member's contextual type keeps the singleton
 * (tsc's `checkExpressionForMutableLocation` / `getWidenedLiteralLikeTypeForContextualType`
 * / `isLiteralOfContextualType`). Every row reproduced against tsgo 7.0.2 AND pristine
 * `typescript@6.0.3` before any code was written; the two references agree on every row.
 *
 * THREE RULES, each with its own negative control, because each fails silently on its own:
 *
 * RULE 1 — WIDENING: `const o = { v: K.A }` makes `o.v` a `K`, so `const w: K.A = o.v` and
 * `return o.v` report (lost true positives) and `o.v = K.B` is legal (an ours-only false
 * positive before). The whole-object relation, generic inference and destructuring then
 * report through the engines that already existed, and `if (o.kind === K.B)` is no longer
 * an ours-only TS2367.
 *
 * RULE 2 — FRESHNESS: only an enum-member ACCESS expression is fresh. A non-fresh value — a
 * typed identifier, a `const` local, an `as` expression, a shorthand — keeps its singleton;
 * a conditional or `||`/`??` of fresh accesses widens member by member.
 *
 * RULE 3 — the contextual KEEP, pulled from the parent chain where the push never reached:
 * a discriminated enum `kind` stays selected at a union-annotated declaration, nested under
 * one, in a conditional return, an arrow expression body, a union-target assignment, a plain
 * return, an argument and a `Map.set` — decided by tsc's SOME rule, so `kind?: E.X` and
 * `0 | 1` keep where an every-constituent test would widen.
 */
class ObjLitEnumMemberWideningTest {

    private val prelude = """
        enum K { A, B, C }
        enum S { X = "x", Y = "y" }
        enum E { X, Y }
        enum DK { Sym, Lab, Kw }
        interface ISym { type: DK.Sym; symbol: string }
        interface ILab { type: DK.Lab; node: number }
        interface IKw { type: DK.Kw; node: number }
        type IDef = ISym | ILab | IKw
        interface IBag { definition: IDef; references: number[] }
        declare const s: string
        declare const n: number
        declare const cond: boolean
        declare const maybe: K.B | undefined
        declare const dk: DK
        declare const ka: K.A
        declare function take(d: IDef): void
        declare function id<T>(x: T): T
        declare function probeK(x: K.A): void
    """.trimIndent() + "\n"

    /** 0-based line of the first line appended after [prelude] (line 0 is the directive); `character` is tsc's 1-based column. */
    private val rowLine = prelude.count { it == '\n' } + 1

    private fun messages(source: String): List<String> = diagnose(prelude + source).map { it.message }

    // ---------------------------------------------------------------------
    // RULE 1 — a fresh enum member in a mutable object-literal member widens
    // ---------------------------------------------------------------------

    @Test
    fun `a mutable member holding a fresh enum member reads as the whole enum at a declaration`() {
        val d = diagnose(prelude + "const o = { v: K.A }\nconst w: K.A = o.v")
        assert(d.map { it.message } == listOf("Type 'K' is not assignable to type 'K.A'."))
        assert(d[0].code == 2322)
        assert(d[0].line == rowLine + 1)
        assert(d[0].character == 7)
    }

    @Test
    fun `a mutable member holding a fresh enum member reads as the whole enum at a return`() {
        val d = diagnose(prelude + "const o = { v: K.A }\nfunction ret(): K.A { return o.v }")
        assert(d.map { it.message } == listOf("Type 'K' is not assignable to type 'K.A'."))
        assert(d[0].code == 2322)
        assert(d[0].line == rowLine + 1)
        // Both references anchor a return-position TS2322 at the `return` keyword.
        assert(d[0].character == 23)
    }

    @Test
    fun `negative control - assigning another member to the widened member is legal`() {
        assert(messages("const o = { v: K.A }\no.v = K.B").isEmpty())
    }

    @Test
    fun `a string enum member widens to its enum the same way`() {
        assert(messages("const so = { s: S.X }\nconst sw: S.X = so.s") ==
            listOf("Type 'S' is not assignable to type 'S.X'."))
    }

    @Test
    fun `the whole-object relation sees the widened member`() {
        assert(messages("const o3 = { v: K.A }\nconst t3: { v: K.A } = o3") ==
            listOf("Type '{ v: K; }' is not assignable to type '{ v: K.A; }'."))
    }

    @Test
    fun `generic inference sees the widened member`() {
        // tsc's contextual type for the argument is the bare `T`, which keeps nothing.
        assert(messages("const r = id({ v: K.A })\nconst w3: K.A = r.v") ==
            listOf("Type 'K' is not assignable to type 'K.A'."))
    }

    @Test
    fun `destructuring sees the widened member`() {
        assert(messages("const o3 = { v: K.A, w: 1 }\nconst { v } = o3\nconst w4: K.A = v") ==
            listOf("Type 'K' is not assignable to type 'K.A'."))
    }

    @Test
    fun `comparing the widened member to another member is no longer an unintentional comparison`() {
        assert(messages("const oc = { kind: K.A, x: 1 }\nif (oc.kind === K.B) { probeK(K.A) }").isEmpty())
    }

    @Test
    fun `negative control - the comparison rule still reports a member local against another member`() {
        val d = diagnose(prelude + "if (ka === K.B) { probeK(K.A) }")
        assert(d.map { it.code } == listOf(2367))
    }

    // ---------------------------------------------------------------------
    // RULE 2 — only a fresh enum-member ACCESS widens
    // ---------------------------------------------------------------------

    @Test
    fun `a typed identifier value is not fresh and keeps its member`() {
        assert(messages("const o2 = { v: ka }\nconst w2: K.A = o2.v").isEmpty())
    }

    @Test
    fun `a const local value is not fresh and keeps its member`() {
        assert(messages("const k = K.A\nconst o4 = { v: k }\nconst w: K.A = o4.v").isEmpty())
    }

    @Test
    fun `an as expression value is not fresh and keeps its member`() {
        assert(messages("const o7 = { v: K.A as K.A }\nconst w7: K.A = o7.v").isEmpty())
    }

    @Test
    fun `a shorthand member is not fresh and keeps its member`() {
        assert(messages("const v = K.A\nconst o5 = { v }\nconst w5: K.A = o5.v").isEmpty())
    }

    @Test
    fun `a conditional of fresh members widens`() {
        assert(messages("const oc = { v: cond ? K.A : K.B }\nconst wc: K.A | K.B = oc.v") ==
            listOf("Type 'K' is not assignable to type 'K.A | K.B'."))
    }

    @Test
    fun `a nullish-coalesced fresh member widens`() {
        assert(messages("const oo = { v: maybe ?? K.A }\nconst wo: K.A | K.B = oo.v") ==
            listOf("Type 'K' is not assignable to type 'K.A | K.B'."))
    }

    @Test
    fun `a parenthesized fresh member widens`() {
        assert(messages("const op = { v: (K.A) }\nconst wp: K.A = op.v") ==
            listOf("Type 'K' is not assignable to type 'K.A'."))
    }

    // ---------------------------------------------------------------------
    // RULE 3 — the contextual keep, pulled where the push never reached
    // ---------------------------------------------------------------------

    @Test
    fun `a union-annotated declaration keeps its discriminant`() {
        assert(messages("const d1: IDef = { type: DK.Kw, node: n }").isEmpty())
    }

    @Test
    fun `negative control - a union-annotated declaration still reports a non-discriminant mismatch`() {
        // The pull must KEEP `type: DK.Sym` here as well: with it widened the message
        // reads `{ type: DK; symbol: number; }`, a display no reference prints.
        assert(messages("const d2: IDef = { type: DK.Sym, symbol: n }") ==
            listOf("Type '{ type: DK.Sym; symbol: number; }' is not assignable to type 'IDef'."))
    }

    @Test
    fun `a literal nested under a union-annotated declaration keeps its discriminant`() {
        assert(messages("const nb: IBag = { definition: { type: DK.Lab, node: n }, references: [] }").isEmpty())
    }

    @Test
    fun `a conditional return keeps its discriminant`() {
        assert(messages("function r5(): IBag[] { return n ? [{ definition: { type: DK.Sym, symbol: s }, references: [] }] : [] }").isEmpty())
    }

    @Test
    fun `a returned array keeps its discriminant`() {
        assert(messages("function r4(): IBag[] { return [{ definition: { type: DK.Kw, node: n }, references: [] }] }").isEmpty())
    }

    @Test
    fun `an arrow expression body keeps its discriminant`() {
        assert(messages("const r7 = (): IDef => ({ type: DK.Kw, node: n })").isEmpty())
    }

    @Test
    fun `a union-target assignment keeps its discriminant`() {
        assert(messages("let asg: IDef\nasg = { type: DK.Kw, node: n }").isEmpty())
    }

    @Test
    fun `negative control - a union-target assignment still reports a widened discriminant`() {
        assert(messages("let asg: IDef\nasg = { type: dk, node: n }") ==
            listOf("Type '{ type: DK; node: number; }' is not assignable to type 'IDef'."))
    }

    @Test
    fun `a plain return keeps its discriminant`() {
        assert(messages("function c1(): IDef { return { type: DK.Kw, node: n } }").isEmpty())
    }

    @Test
    fun `a return inside an if without a block keeps its discriminant`() {
        assert(messages("function c2(): IDef { if (n) return { type: DK.Sym, symbol: s }\nreturn { type: DK.Kw, node: n } }").isEmpty())
    }

    @Test
    fun `an argument keeps its discriminant`() {
        assert(messages("take({ type: DK.Kw, node: n })").isEmpty())
    }

    @Test
    fun `an optional enum-member context keeps the member`() {
        // Discriminated by the pull alone: this checker models an optional member's type
        // as the plain `E.X` ((CHK.61)(b)), so the every-constituent test below cannot see
        // the `undefined` here — the next pin spells it out.
        assert(messages("const opt: { kind?: E.X } = { kind: E.X }").isEmpty())
    }

    @Test
    fun `an undefined-carrying enum-member context keeps the member`() {
        // tsc's SOME rule: `E.X | undefined` holds a literal of the member's flavour. An
        // every-constituent test (`enumComparisonAtoms`) reads `undefined` as not
        // enum-flavoured and widens, and the per-property emitter then reports `E`.
        assert(messages("const optu: { kind: E.X | undefined } = { kind: E.X }").isEmpty())
    }

    @Test
    fun `a number-literal union context keeps a numeric member`() {
        assert(messages("const lit: { v: 0 | 1 } = { v: E.X }").isEmpty())
    }

    @Test
    fun `a string-literal context keeps a string member of that value`() {
        assert(messages("const sl: { s: \"x\" } = { s: S.X }").isEmpty())
    }

    @Test
    fun `negative control - a primitive context widens and still relates`() {
        assert(messages("const num: { v: number } = { v: E.X }").isEmpty())
    }

    @Test
    fun `negative control - a kept member still reports against another member`() {
        assert(messages("const bad: { v: K.A } = { v: K.B }") ==
            listOf("Type 'K.B' is not assignable to type 'K.A'."))
    }

    @Test
    fun `an annotated object target keeps the member for a later read`() {
        assert(messages("const ann: { v: K.A } = { v: K.A }\nconst annW: K.A = ann.v").isEmpty())
    }

    @Test
    fun `a union-of-objects annotation keeps the member`() {
        assert(messages("const ann: { v: K.A } | { w: K.B } = { v: K.A }").isEmpty())
    }

    @Test
    fun `a callback whose contextual return type holds the member keeps it`() {
        assert(messages("declare function mk(f: () => { v: K.A }): void\nmk(() => ({ v: K.A }))").isEmpty())
    }

    @Test
    fun `a map set argument keeps its discriminant through the receiver instantiation`() {
        // The shape is tsbuildPublic.ts's `state.projectStatus.set(path, { type:
        // UpToDateStatusType.ComputingUpstream })`, the one `Map.set` position the
        // argument emitter reaches on the profiles (the (P18.18) arm's 1477:47 row).
        val shape = """
            enum UT { Unbuildable, UpToDate, ComputingUpstream }
            interface Unb { type: UT.Unbuildable; reason: string }
            interface Utd { type: UT.UpToDate; newest: number }
            interface Cu { type: UT.ComputingUpstream }
            type Status = Unb | Utd | Cu
            interface State<T> { projectStatus: Map<string, Status>; x: T }
            declare const ut: UT
        """.trimIndent() + "\n"
        val kept = messages(shape + "function f<T>(state: State<T>, p: string) { state.projectStatus.set(p, { type: UT.ComputingUpstream }) }")
        assert(kept.isEmpty())
        // The control that the emitter reaches this position at all: a WIDENED
        // discriminant is reported there (a pre-existing row; its display names the
        // uninstantiated `V` where both references print `Status`, a recorded residue).
        val widened = diagnose(prelude + shape + "function g<T>(state: State<T>, p: string) { state.projectStatus.set(p, { type: ut }) }")
        assert(widened.map { it.code } == listOf(2345))
    }

    @Test
    fun `a const assertion is recorded as unobservable until the assertion is modelled`() {
        // (CHK.85)(c): `as const` is unmodelled — the member reads `any` on every binary
        // this repo has shipped, so tsc's const-context keep (piece 2 of the rule) cannot
        // be pinned by a value here. This is an ARM pin (round 812's law): it records the
        // edge rather than claiming coverage, and it reddens the day the assertion is
        // modelled so that the keep gets its value pin then.
        assert(messages("const ac = { v: K.A } as const\nconst acs: string = ac.v").isEmpty())
    }
}
