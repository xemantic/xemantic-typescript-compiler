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
 * (CHK.85)(b) — a `let`/`const` local initialized from an enum member READS as both
 * references read it. Every row reproduced against tsgo 7.0.2 AND pristine
 * `typescript@6.0.3` before any code was written; the two references agree on every row.
 *
 * FOUR SEAMS, each with its own negative control, because each fails silently on its own:
 *
 * S1 — the FLOW reduction: `let k = K.A` / `k = K.B` reduces the declared enum (or an
 * enum-carrying union) to the assigned member (tsc's `getAssignmentReducedType`). A member
 * of a FOREIGN enum answers the DECLARED type, never `never`.
 *
 * S2 — the READERS: an enum-flavoured target is narrowable at the var-decl and return
 * positions, so `const w: K.B = k` reads the flow type (and, after `k = K.B`, stops being
 * an ours-only false positive).
 *
 * S3 — the SYMBOL half of the const rule: a file-level `const k = K.A` answers `K.A` to
 * every consumer (the argument gate, the `never` arm, the TS2367 pass), and stays FRESH
 * through the identifier for an object-literal member (`ObjLitEnumMemberWideningTest`).
 *
 * S4 — the BODY-LOCAL recordings and the TS2367 flow read: the arith frame and the
 * argument gate's frame both hold an enum-initialized body local, and an identifier
 * operand of `===` reads its flow type (a `never` operand reports nothing, as tsc).
 *
 * GUARDS measured in both references: a nested FUNCTION DECLARATION and a class method
 * read the declared `K`; an arrow inside a function keeps `K.A` unless the local is
 * reassigned after it. RECORDED residues (the expectation is OURS): a loop that reassigns
 * reads `'K'` where tsc reads `'K.A | K.B'` ((CHK.69)'s loop-label law); a FILE-level arrow
 * and an object-literal method read `'K'` where tsc continues the flow (B464 mints a
 * closure `FlowStart` only under an enclosing function); the assignment position keeps
 * its suppression-only read (`'K'` for `'K.A'`, a form divergence).
 *
 * GRADED with a same-flavour literal MEMBER target (`const w: K.B = k`, `takeB(k)`,
 * `nv(k)`, `k === K.B`) — the primitive mis-assignment probe prints `Type 'K'` on both
 * sides (tsc's `reportRelationError` generalization) and is blind here.
 */
class EnumMemberLocalFlowTest {

    private val prelude = """
        enum K { A = 1, B = 2, C = 3 }
        enum S { P = "p", Q = "q" }
        enum J { X = 9 }
        enum Cmp { X = 1 }
        declare function takeB(x: K.B): void
        declare function nv(x: never): void
        declare const cond: boolean
        export {}
    """.trimIndent() + "\n"

    /** 0-based line of the first line appended after [prelude] (line 0 is the directive). */
    private val rowLine = prelude.count { it == '\n' } + 1

    private fun messages(source: String): List<String> = diagnose(prelude + source).map { it.message }

    /** tsc's 1-based column of [needle]'s first character in [source]. */
    private fun col(source: String, needle: String): Int = source.indexOf(needle) + 1

    private val notB = "Type 'K.A' is not assignable to type 'K.B'."
    private val argNotB = "Argument of type 'K.A' is not assignable to parameter of type 'K.B'."
    private val argNever = "Argument of type 'K.A' is not assignable to parameter of type 'never'."
    private val noOverlap =
        "This comparison appears to be unintentional because the types 'K.A' and 'K.B' have no overlap."

    // ---------------------------------------------------------------------
    // S1 — the flow reduction at a file-level `let`
    // ---------------------------------------------------------------------

    @Test
    fun `a let initialized from a member reads that member at a declaration`() {
        val src = "let k = K.A; const w: K.B = k"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(notB))
        assert(d[0].code == 2322)
        assert(d[0].line == rowLine)
        assert(d[0].character == col(src, "w:"))
    }

    @Test
    fun `negative control - after a reassignment the declaration is silent`() {
        assert(messages("let k = K.A; k = K.B; const w: K.B = k").isEmpty())
    }

    @Test
    fun `a let initialized from a member reads that member at an argument`() {
        val src = "let k = K.A; takeB(k)"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(argNotB))
        assert(d[0].code == 2345)
        assert(d[0].character == col(src, "k)"))
    }

    @Test
    fun `negative control - after a reassignment the argument is silent`() {
        assert(messages("let k = K.A; k = K.B; takeB(k)").isEmpty())
    }

    @Test
    fun `a let initialized from a member reads that member at a return`() {
        val src = "function r(): K.B { let k = K.A; return k }"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(notB))
        // Both references anchor a return-position TS2322 at the `return` keyword.
        assert(d[0].character == col(src, "return"))
    }

    @Test
    fun `negative control - after a reassignment the return is silent`() {
        assert(messages("function r(): K.B { let k = K.A; k = K.B; return k }").isEmpty())
    }

    @Test
    fun `a let initialized from a member reads that member at the never arm`() {
        assert(messages("let k = K.A; nv(k)") == listOf(argNever))
    }

    @Test
    fun `an annotated let reduces its whole enum to the assigned member`() {
        assert(messages("let k: K = K.A; const w: K.B = k") == listOf(notB))
    }

    @Test
    fun `an enum-carrying union declared type reduces to the assigned member`() {
        assert(messages("let k: K | undefined = K.A; const w: K.B = k") == listOf(notB))
    }

    @Test
    fun `a later assignment to an enum-carrying union reduces to the assigned member`() {
        assert(messages("let k: K | undefined\nk = K.A\nconst w: K.B = k") == listOf(notB))
    }

    @Test
    fun `a string enum local reads its member the same way`() {
        assert(messages("let s = S.P; const w: S.Q = s") == listOf("Type 'S.P' is not assignable to type 'S.Q'."))
    }

    @Test
    fun `negative control - a reassigned string enum local is silent`() {
        assert(messages("let s = S.P; s = S.Q; const w: S.Q = s").isEmpty())
    }

    @Test
    fun `a member of a foreign enum answers the declared type and never the bottom type`() {
        // `k = J.X` is its own TS2322; the read after it is tsc's declared-type fallback
        // (`getAssignmentReducedTypeWorker` returns `declaredType` when the filter empties).
        // A `never` answer there would DELETE the second diagnostic.
        val d = diagnose(prelude + "let k: K = K.A; k = J.X; const w: K.B = k")
        assert(d.map { it.code } == listOf(2322, 2322))
        assert(d[1].message == "Type 'K' is not assignable to type 'K.B'.")
    }

    @Test
    fun `a branch join of two members reads the union in declaration order with tsc's elaboration`() {
        val d = diagnose(prelude + "let k = K.A; if (cond) { k = K.B }; const w: K.B = k")
        assert(d.map { it.message } == listOf("Type 'K.A | K.B' is not assignable to type 'K.B'."))
        assert(d[0].messageChain == listOf("  Type 'K.A' is not assignable to type 'K.B'."))
    }

    @Test
    fun `a branch join of two members reports at an argument and at the never arm`() {
        val d = diagnose(prelude + "let k = K.A; if (cond) { k = K.B }; takeB(k); nv(k)")
        assert(d.map { it.message } == listOf(
            "Argument of type 'K.A | K.B' is not assignable to parameter of type 'K.B'.",
            "Argument of type 'K.A | K.B' is not assignable to parameter of type 'never'.",
        ))
    }

    // ---------------------------------------------------------------------
    // S2 — the readers admit an enum-flavoured target
    // ---------------------------------------------------------------------

    @Test
    fun `a body-local let reads its member at a declaration`() {
        val src = "function b() { let k = K.A; const w: K.B = k }"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(notB))
        assert(d[0].character == col(src, "w:"))
    }

    @Test
    fun `negative control - a reassigned body-local let is silent at a declaration`() {
        assert(messages("function b() { let k = K.A; k = K.B; const w: K.B = k }").isEmpty())
    }

    @Test
    fun `a parameter narrowed by an equality guard reads the member at a declaration`() {
        assert(messages("function f(k: K) { if (k === K.A) { const w: K.B = k } }") == listOf(notB))
    }

    @Test
    fun `a body-local let reads its member at a return`() {
        assert(messages("function g(): K.B { let k = K.A; return k }") == listOf(notB))
    }

    // ---------------------------------------------------------------------
    // S3 — the symbol half of the const rule
    // ---------------------------------------------------------------------

    @Test
    fun `a file-level const reads its member at the never arm`() {
        val src = "const k = K.A; nv(k)"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(argNever))
        assert(d[0].character == col(src, "k)"))
    }

    @Test
    fun `a file-level const reads its member at an argument`() {
        assert(messages("const k = K.A; takeB(k)") == listOf(argNotB))
    }

    @Test
    fun `a file-level const compared to another member is an unintentional comparison`() {
        val src = "const k = K.A; if (k === K.B) { }"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(noOverlap))
        assert(d[0].code == 2367)
        assert(d[0].character == col(src, "k ==="))
    }

    @Test
    fun `a const of a one-member enum keeps the member in the comparison display`() {
        // (CHK.92)(d)'s d15: both references print the fresh `Cmp.X`, ours printed `Cmp`
        // through the widened symbol half.
        assert(messages("const c1 = Cmp.X; if (c1 === 5) { }") == listOf(
            "This comparison appears to be unintentional because the types 'Cmp.X' and '5' have no overlap."
        ))
    }

    @Test
    fun `an imported const reads its member in another file`() {
        // The symbol half is the ONLY reader for a const declared in ANOTHER file: the
        // importing file's flow holds no assignment of it (S1 cannot serve it) and no
        // local map recorded it — which is what makes this pin discriminate S3 where
        // every same-file read is also served by the flow or the local half.
        val d = TypeScriptCompiler().compile(
            """
            // @strict: true
            // @Filename: a.ts
            export enum K { A = 1, B = 2, C = 3 }
            export const k = K.A
            // @Filename: b.ts
            import { k, K } from "./a"
            declare function nv(x: never): void
            nv(k)
            """.trimIndent()
        ).diagnostics
        assert(d.map { it.message } == listOf(argNever))
    }

    @Test
    fun `a file-level const captured by a function declaration keeps its member`() {
        assert(messages("const k = K.A; function f() { const w: K.B = k }") == listOf(notB))
    }

    // ---------------------------------------------------------------------
    // S4 — body-local recordings and the TS2367 flow read
    // ---------------------------------------------------------------------

    @Test
    fun `a file-level let compared to another member is an unintentional comparison`() {
        val src = "let k = K.A; if (k === K.B) { }"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(noOverlap))
        assert(d[0].character == col(src, "k ==="))
    }

    @Test
    fun `an annotated let compared to another member is an unintentional comparison`() {
        assert(messages("let k: K = K.A; if (k === K.B) { }") == listOf(noOverlap))
    }

    @Test
    fun `a body-local let compared to another member is an unintentional comparison`() {
        assert(messages("function b() { let k = K.A; if (k === K.B) { } }") == listOf(noOverlap))
    }

    @Test
    fun `a body-local const compared to another member is an unintentional comparison`() {
        assert(messages("function b() { const k = K.A; if (k === K.B) { } }") == listOf(noOverlap))
    }

    @Test
    fun `negative control - a reassigned body-local let compares silently`() {
        assert(messages("function b() { let k = K.A; k = K.B; if (k === K.B) { } }").isEmpty())
    }

    @Test
    fun `a comparison nested under an equality guard reads the guarded member`() {
        assert(messages("let k = K.A; if (k === K.A) { if (k === K.B) { } }") == listOf(noOverlap))
    }

    @Test
    fun `a parameter guarded by an early return compares as the remaining member`() {
        assert(messages("function f(k: K) { if (k !== K.A) return; if (k === K.B) { } }") == listOf(noOverlap))
    }

    @Test
    fun `negative control - an operand narrowed to never reports no comparison`() {
        // tsc: `never` is comparable to everything, so an unreachable comparison is
        // silent — and refusing the answer back to the declared `K.A` would manufacture
        // the ours-only row this checker used to ship for exactly this shape.
        assert(messages("function f(k: K.A) { if (k !== K.A) { if (k === K.B) { } } }").isEmpty())
    }

    @Test
    fun `a body-local const reads its member at an argument`() {
        val src = "function b() { const k = K.A; takeB(k) }"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(argNotB))
        assert(d[0].character == col(src, "k)"))
    }

    @Test
    fun `a body-local let reads its member at the never arm`() {
        assert(messages("function b() { let k = K.A; nv(k) }") == listOf(argNever))
    }

    @Test
    fun `negative control - a reassigned body-local let is silent at an argument`() {
        assert(messages("function b() { let k = K.A; k = K.B; takeB(k) }").isEmpty())
    }

    @Test
    fun `negative control - a property-access operand keeps its declared read`() {
        // The (CHK.91) discriminant rows are pinned on the declared read of `o.kind`; the
        // flow read is confined to an IDENTIFIER operand.
        assert(messages("const o = { kind: K.A, x: 1 }\nif (o.kind === K.B) { }").isEmpty())
    }

    // ---------------------------------------------------------------------
    // S1/S4 — a conditional of members, the reporting walk, the widened recorder
    // (every row below was found on tsc's own sources by the 8-profile grid and
    // reproduced against both references before it was pinned)
    // ---------------------------------------------------------------------

    @Test
    fun `a let initialized from a conditional of members reads the member union`() {
        val d = diagnose(prelude + "let x = cond ? K.A : K.B; const w: K.B = x")
        assert(d.map { it.message } == listOf("Type 'K.A | K.B' is not assignable to type 'K.B'."))
        assert(d[0].messageChain == listOf("  Type 'K.A' is not assignable to type 'K.B'."))
        assert(messages("let x = cond ? K.A : K.B; takeB(x)") ==
            listOf("Argument of type 'K.A | K.B' is not assignable to parameter of type 'K.B'."))
        assert(messages("let x = cond ? K.A : K.B; if (x === K.C) { }") == listOf(
            "This comparison appears to be unintentional because the types 'K.A | K.B' and 'K.C' have no overlap."
        ))
    }

    @Test
    fun `a body-local initialized from a conditional of members reads the member union`() {
        // `literalTypeOfExpression` answers null for a conditional whose branches are both
        // member ACCESSES, so neither frame recorded such a local and it read `any` in a
        // body where its file-level twin read `K` through the symbol half.
        val union = "This comparison appears to be unintentional because the types 'K.A | K.B' and 'K.C' have no overlap."
        assert(messages("function b() { let x = cond ? K.A : K.B; if (x === K.C) { } }") == listOf(union))
        assert(messages("function b() { const x = cond ? K.A : K.B; if (x === K.C) { } }") == listOf(union))
        assert(messages("function b() { let x = cond ? K.A : K.B; takeB(x) }") ==
            listOf("Argument of type 'K.A | K.B' is not assignable to parameter of type 'K.B'."))
    }

    @Test
    fun `a widened let assigned a computed number reads the whole enum again`() {
        // tsc's own checker.ts `variance` (its `getVariancesWorker`): the declared type is
        // `K | undefined`, the `|` assignment reduces it to `K`, and `=== K.C` overlaps —
        // three ours-only rows (a TS2367 and two `possibly 'undefined'` on the `|=`) until
        // the arith recorder widened a `let`'s members and a foreign member stopped
        // answering the declared type.
        val src = """
            function v(a: boolean, b: boolean, um: boolean) {
                let variance = cond ? cond ? K.A : K.B : cond ? K.C : undefined
                if (variance === undefined) {
                    variance = (a ? K.B : 0) | (b ? K.C : 0)
                    if (variance === K.C && a) { variance = K.A }
                    if (um) { variance |= K.B }
                }
                return variance
            }
        """.trimIndent()
        assert(messages(src).isEmpty())
    }

    @Test
    fun `a member assigned inside an undefined guard compares as that member`() {
        // The tsc row the widened recorder GAINS: `v3 = K.B` inside the `=== undefined`
        // guard reduces `K | undefined` to `K.B`, so `=== K.C` has no overlap.
        val src = """
            function h(um: boolean) {
                let v3 = cond ? K.A : undefined
                if (v3 === undefined) {
                    v3 = K.B
                    if (v3 === K.C && um) { v3 = K.A }
                    if (um) { v3 |= K.B }
                }
                return v3
            }
        """.trimIndent()
        assert(messages(src) == listOf(
            "This comparison appears to be unintentional because the types 'K.B' and 'K.C' have no overlap."
        ))
    }

    @Test
    fun `negative control - an unresolvable overwrite resets the reporting read to the declared type`() {
        // tsc's own classifier.ts: `token = scanner.reScanTemplateToken()` past a `case`
        // narrowing. The suppression walks keep the stale antecedent (sound for them); a
        // REPORTING read must not, or `if (token === K.B)` is an ours-only TS2367.
        // The callee must be UNRESOLVABLE (tsc's `scanner` is a local of an untyped
        // factory): a resolvable one answers its return type through the call arm and
        // never reaches the fallthrough this pin is about.
        val src = """
            declare const anyScanner: any
            function cl() { let t: K = anyScanner.rescan(); switch (t) { case K.A: t = anyScanner.rescan(); if (t === K.B) { } } }
        """.trimIndent()
        assert(messages(src).isEmpty())
    }

    @Test
    fun `an enum imported under another name is still a member receiver`() {
        // The receiver-name pre-gate admits every import-binding local name, so a renamed
        // import resolves; both references print the enum's own name.
        val d = TypeScriptCompiler().compile(
            """
            // @strict: true
            // @Filename: a.ts
            export enum K { A = 1, B = 2, C = 3 }
            // @Filename: b.ts
            import { K as Q } from "./a"
            let q = Q.A
            const w: Q.B = q
            """.trimIndent()
        ).diagnostics
        assert(d.map { it.message } == listOf(notB))
    }

    // ---------------------------------------------------------------------
    // Guards — where the flow stops (both references)
    // ---------------------------------------------------------------------

    @Test
    fun `a nested function declaration reads the declared enum`() {
        val src = "function b() { let k = K.A; function f() { const w: K.B = k } }"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("Type 'K' is not assignable to type 'K.B'."))
        assert(d[0].character == col(src, "w:"))
    }

    @Test
    fun `a file-level let captured by a function declaration reads the declared enum`() {
        assert(messages("let k = K.A; function f() { const w: K.B = k }") ==
            listOf("Type 'K' is not assignable to type 'K.B'."))
    }

    @Test
    fun `a class method reads the declared enum`() {
        assert(messages("let k = K.A; class C { m() { const w: K.B = k } }") ==
            listOf("Type 'K' is not assignable to type 'K.B'."))
    }

    @Test
    fun `an arrow inside a function keeps the captured member`() {
        assert(messages("function b() { let k = K.A; const g = () => { const w: K.B = k } }") == listOf(notB))
    }

    @Test
    fun `negative control - an arrow followed by a reassignment reads the declared enum`() {
        assert(messages("function b() { let k = K.A; const g = () => { const w: K.B = k }; k = K.B }") ==
            listOf("Type 'K' is not assignable to type 'K.B'."))
    }

    // ---------------------------------------------------------------------
    // Recorded residues — the expectation below is OURS, both references differ
    // ---------------------------------------------------------------------

    @Test
    fun `residue - a loop that reassigns reads the declared enum where tsc reads the join`() {
        // (CHK.69): a loop label with an assigning back edge answers the declared type;
        // tsc prints `Type 'K.A | K.B' is not assignable to type 'K.B'.` with an
        // elaboration. FORM only — both report.
        assert(messages("function b() { let k = K.A; for (let i = 0; i < 2; i++) { k = K.B } const w: K.B = k }") ==
            listOf("Type 'K' is not assignable to type 'K.B'."))
    }

    @Test
    fun `residue - a file-level arrow reads the declared enum where tsc continues the flow`() {
        // B464 mints a closure FlowStart only under an ENCLOSING function; tsc prints
        // `Type 'K.A'` here. FORM only — both report.
        assert(messages("let k = K.A; const g = () => { const w: K.B = k }") ==
            listOf("Type 'K' is not assignable to type 'K.B'."))
    }

    @Test
    fun `residue - an object-literal method reads the declared enum where tsc continues the flow`() {
        // tsc's flow-container loop continues through an object-literal method
        // (`isObjectLiteralOrClassExpressionMethodOrAccessor`); ours stops. FORM only.
        assert(messages("let k = K.A; const o = { m() { const w: K.B = k } }") ==
            listOf("Type 'K' is not assignable to type 'K.B'."))
    }

    @Test
    fun `residue - the assignment position keeps its suppression-only read`() {
        // Round 468's enum-target assignment narrowing substitutes only when the narrowed
        // type relates, so `w = k` prints the declared `'K'` where tsc prints `'K.A'`.
        // FORM only; the MEANING half (silent after `k = K.B`) is pinned beside it.
        assert(messages("function b() { let k = K.A; let w: K.B; w = k }") ==
            listOf("Type 'K' is not assignable to type 'K.B'."))
        assert(messages("function b() { let k = K.A; k = K.B; let w: K.B; w = k }").isEmpty())
    }
}
