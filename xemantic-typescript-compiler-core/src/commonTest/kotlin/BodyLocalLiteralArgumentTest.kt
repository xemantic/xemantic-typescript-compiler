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
 * (CHK.95) — the argument gate's BODY-LOCAL scalar locals. `const s = "a"; takeB(s)` was
 * silent in every body context (function, arrow, method, nested function, annotated
 * function) for every non-enum scalar initializer, for every `let`, and for an ANNOTATED
 * primitive local, while the same lines reported at file level and an enum initializer
 * reported everywhere. Every expectation below was measured against tsgo 7.0.2 AND
 * pristine `typescript@6.0.3` before any code was written; the two agree on every row
 * this class pins except where a residue says otherwise.
 *
 * TWO SEAMS: (a) the ccet body-local pre-scan ([Checker.shadowCallTypesDeclList]) records
 * an un-annotated local whose initializer is a RESOLUTION-FREE scalar literal (the subset
 * of [Checker.literalTypeOfExpression] that types no expression — a `const`, or a
 * const-asserted `let`, keeps the literal; a `let`/`var` records its base primitive per
 * (WIDEN.1)); (b) the leave-time [Checker.ccetApplyDeclRecordings] records an intrinsic
 * primitive or single-literal ANNOTATION beside the union-of-literals arm it always had.
 *
 * NEGATIVE CONTROLS: a compatible argument stays silent; a same-named SECOND local in one
 * body reads `any` (round 460's ambiguity rule, now also for an annotated pair); a local
 * named after a lib global or a file-level binding keeps the collision guard's `any`; a
 * MUTABLE boolean local is deliberately NOT recorded — tsc's `boolean` is `true | false`
 * and narrows by assignment where ours is an intrinsic the gate cannot reduce, so
 * `let b = true; b = false; takeF(b)` would have been a false positive (measured; the
 * FILE-level twin already is one, pre-existing).
 *
 * RECORDED residues (the expectation is OURS): a ternary / `||` / `??` initializer is
 * `any` (tsc `"a" | "c"`); a member of a body-local object literal is `any` (tsc
 * `string`); `s === "b"` on such a local is silent (the TS2367 reader is a different
 * recorder — TS2678 on a `switch` already fires); a `for (const s of …)` head is `any`;
 * a template with substitutions and a `+` concatenation are `any` (not literals); a
 * mutable boolean prints nothing where tsc prints `'true'`.
 */
class BodyLocalLiteralArgumentTest {

    private val prelude = """
        enum K { A, B }
        declare function takeB(x: "b"): void
        declare function takeN(x: 2): void
        declare function takeF(x: false): void
        declare function takeS(x: string): void
        declare function takeNum(x: number): void
        declare function takeBig(x: 2n): void
        declare function takeO(x: "b"): void
        declare function takeO(x: number): void
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

    private fun arg(from: String, to: String) =
        "Argument of type '$from' is not assignable to parameter of type '$to'."

    private val aNotB = arg("\"a\"", "\"b\"")
    private val stringNotB = arg("string", "\"b\"")
    private val oneNotTwo = arg("1", "2")
    private val numberNotTwo = arg("number", "2")
    private val trueNotFalse = arg("true", "false")

    /** One TS2345 row at [needle]'s first occurrence, with [message]. */
    private fun assertOneRow(src: String, message: String, needle: String) {
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(message))
        assert(d[0].code == 2345)
        assert(d[0].line == rowLine)
        assert(d[0].character == col(src, needle))
    }

    // ---------------------------------------------------------------------
    // (a) the const arm, context by context
    // ---------------------------------------------------------------------

    @Test
    fun `a body-local string const reads its literal at an argument in a function`() {
        assertOneRow("function f() { const s = \"a\"; takeB(s) }", aNotB, "s)")
    }

    @Test
    fun `a body-local string const reads its literal in an arrow`() {
        assertOneRow("const g = () => { const s = \"a\"; takeB(s) }", aNotB, "s)")
    }

    @Test
    fun `a body-local string const reads its literal in a method`() {
        assertOneRow("class C { m() { const s = \"a\"; takeB(s) } }", aNotB, "s)")
    }

    @Test
    fun `a body-local string const reads its literal in a nested function`() {
        assertOneRow("function outer() { function inner() { const s = \"a\"; takeB(s) } }", aNotB, "s)")
    }

    @Test
    fun `a body-local string const reads its literal in an annotated function`() {
        assertOneRow("function f(): void { const s = \"a\"; takeB(s) }", aNotB, "s)")
    }

    @Test
    fun `a file-level string const still reads its literal - the symbol half`() {
        assertOneRow("const s = \"a\"; takeB(s)", aNotB, "s)")
    }

    @Test
    fun `a body-local numeric const reads its literal`() {
        assertOneRow("function f() { const n = 1; takeN(n) }", oneNotTwo, "n)")
    }

    @Test
    fun `a body-local negated numeric const reads its literal`() {
        assertOneRow("function f() { const n = -1; takeN(n) }", arg("-1", "2"), "n)")
    }

    @Test
    fun `a body-local boolean const reads its literal`() {
        assertOneRow("function f() { const b = true; takeF(b) }", trueNotFalse, "b)")
    }

    @Test
    fun `a body-local bigint const reads its literal`() {
        assertOneRow("function f() { const b = 1n; takeBig(b) }", arg("1n", "2n"), "b)")
    }

    @Test
    fun `a body-local template const reads its literal`() {
        assertOneRow("function f() { const s = `a`; takeB(s) }", aNotB, "s)")
    }

    @Test
    fun `a parenthesized initializer reads its literal`() {
        assertOneRow("function f() { const s = (\"a\"); takeB(s) }", aNotB, "s)")
    }

    @Test
    fun `a non-null-asserted initializer reads its literal`() {
        assertOneRow("function f() { const s = \"a\"!; takeB(s) }", aNotB, "s)")
    }

    @Test
    fun `a satisfies initializer reads its literal`() {
        assertOneRow("function f() { const s = \"a\" satisfies string; takeB(s) }", aNotB, "s)")
    }

    @Test
    fun `a const-asserted initializer reads its literal`() {
        assertOneRow("function f() { const s = \"a\" as const; takeB(s) }", aNotB, "s)")
    }

    @Test
    fun `two consts in one statement each read their own literal`() {
        val src = "function f() { const s = \"a\", t = \"b\"; takeB(s); takeB(t) }"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(aNotB))
        assert(d[0].character == col(src, "s)"))
    }

    @Test
    fun `an overload set judges the literal per candidate`() {
        val src = "function f() { const s = \"a\"; takeO(s) }"
        val d = diagnose(prelude + src)
        assert(d.map { it.code } == listOf(2769))
        assert(d[0].message == "No overload matches this call.")
        assert(d[0].messageChain == listOf(
            "  Overload 1 of 2, '(x: \"b\"): void', gave the following error.",
            "    Argument of type '\"a\"' is not assignable to parameter of type '\"b\"'.",
            "  Overload 2 of 2, '(x: number): void', gave the following error.",
            "    Argument of type 'string' is not assignable to parameter of type 'number'.",
        ))
        assert(d[0].character == col(src, "s)"))
    }

    @Test
    fun `a use before the declaration still reads the literal beside TS2448`() {
        val d = diagnose(prelude + "function f() { takeB(s); const s = \"a\" }")
        assert(d.map { it.code }.sorted() == listOf(2345, 2448))
        assert(d.first { it.code == 2345 }.message == aNotB)
    }

    // ---------------------------------------------------------------------
    // (a) the let / var arm — base primitive, (WIDEN.1)
    // ---------------------------------------------------------------------

    @Test
    fun `a body-local string let reads string`() {
        assertOneRow("function f() { let s = \"a\"; takeB(s) }", stringNotB, "s)")
    }

    @Test
    fun `a body-local numeric let reads number`() {
        assertOneRow("function f() { let n = 1; takeN(n) }", numberNotTwo, "n)")
    }

    @Test
    fun `a body-local bigint let reads bigint`() {
        assertOneRow("function f() { let b = 1n; takeBig(b) }", arg("bigint", "2n"), "b)")
    }

    @Test
    fun `a body-local var reads string`() {
        assertOneRow("function f() { var s = \"a\"; takeB(s) }", stringNotB, "s)")
    }

    @Test
    fun `a reassigned string let still reads string`() {
        // tsc: the declared `string` is not a union, so the assignment does not narrow it.
        assertOneRow("function f() { let s = \"a\"; s = \"b\"; takeB(s) }", stringNotB, "s)")
    }

    @Test
    fun `a const-asserted let keeps its literal`() {
        assertOneRow("function f() { let s = \"a\" as const; takeB(s) }", aNotB, "s)")
    }

    @Test
    fun `a const-asserted boolean let keeps its literal`() {
        assertOneRow("function f() { let b = true as const; takeF(b) }", trueNotFalse, "b)")
    }

    @Test
    fun `a let in a nested block reads string`() {
        assertOneRow("function f() { if (cond) { let s = \"a\"; takeB(s) } }", stringNotB, "s)")
    }

    @Test
    fun `a for-loop let reads number`() {
        assertOneRow("function f() { for (let i = 0; i < 3; i++) { takeN(i) } }", numberNotTwo, "i)")
    }

    // ---------------------------------------------------------------------
    // (b) the annotated arm
    // ---------------------------------------------------------------------

    @Test
    fun `an annotated string const reads its annotation`() {
        assertOneRow("function f() { const s: string = \"a\"; takeB(s) }", stringNotB, "s)")
    }

    @Test
    fun `an annotated literal const reads its annotation`() {
        assertOneRow("function f() { const s: \"a\" = \"a\"; takeB(s) }", aNotB, "s)")
    }

    @Test
    fun `an annotated number const reads its annotation`() {
        assertOneRow("function f() { const n: number = 1; takeN(n) }", numberNotTwo, "n)")
    }

    @Test
    fun `an annotated number let reads its annotation`() {
        assertOneRow("function f() { let n: number = 1; takeN(n) }", numberNotTwo, "n)")
    }

    @Test
    fun `an annotated boolean const reads boolean`() {
        // tsc prints `'true'` (the initializer narrows the declared `boolean`); the
        // declared type is what this frame records — a form residue.
        assertOneRow("function f() { const b: boolean = true; takeF(b) }", arg("boolean", "false"), "b)")
    }

    @Test
    fun `an annotated literal union still reads its flow type`() {
        assertOneRow("function f() { let s: \"a\" | \"b\" = \"a\"; takeB(s) }", aNotB, "s)")
    }

    // ---------------------------------------------------------------------
    // closures inherit the frame
    // ---------------------------------------------------------------------

    @Test
    fun `an arrow closure reads the enclosing const literal`() {
        assertOneRow("function f() { const s = \"a\"; const g = () => takeB(s) }", aNotB, "s)")
    }

    @Test
    fun `an arrow closure reads the enclosing let as string`() {
        assertOneRow("function f() { let s = \"a\"; const g = () => takeB(s) }", stringNotB, "s)")
    }

    @Test
    fun `a nested function declaration reads the enclosing const literal`() {
        assertOneRow("function f() { const s = \"a\"; function g() { takeB(s) } }", aNotB, "s)")
    }

    // ---------------------------------------------------------------------
    // negative controls
    // ---------------------------------------------------------------------

    @Test
    fun `negative control - a compatible string const is silent`() {
        assert(messages("function f() { const s = \"a\"; takeS(s) }").isEmpty())
    }

    @Test
    fun `negative control - a compatible numeric const is silent`() {
        assert(messages("function f() { const n = 1; takeNum(n) }").isEmpty())
    }

    @Test
    fun `negative control - a compatible string let is silent`() {
        assert(messages("function f() { let s = \"a\"; takeS(s) }").isEmpty())
    }

    @Test
    fun `negative control - a reassigned mutable boolean is not recorded`() {
        // tsc narrows `boolean` (= `true | false`) by the assignment to `false` and is
        // silent; our `boolean` is an intrinsic the gate cannot narrow, so a MUTABLE
        // boolean local is left `any` rather than reported as `'boolean'`.
        assert(messages("function f() { let b = true; b = false; takeF(b) }").isEmpty())
    }

    @Test
    fun `residue - a mutable boolean local reads nothing where tsc reads true`() {
        assert(messages("function f() { let b = true; takeF(b) }").isEmpty())
        assert(messages("function f() { let b: boolean = true; takeF(b) }").isEmpty())
    }

    @Test
    fun `negative control - two sibling blocks declaring one name read any`() {
        // tsc reports the first block's `"a"`; the flat frame cannot tell the two
        // bindings apart — round 460's ambiguity rule, silence is the safe direction.
        assert(messages("function f() { { const s = \"a\"; takeB(s) } { const s = \"b\"; takeB(s) } }").isEmpty())
    }

    @Test
    fun `negative control - two sibling blocks with annotated locals read any`() {
        // Without the pre-scan's body-name set the second block read the FIRST block's
        // annotation (`string` against `2`, a false positive).
        assert(messages(
            "function f() { { const s: string = \"a\"; takeS(s) } { const s: number = 1; takeN(s) } }"
        ).isEmpty())
    }

    @Test
    fun `negative control - a shadowing inner const reads any`() {
        assert(messages("function f() { const s = \"b\"; if (cond) { const s = \"a\"; takeB(s) } }").isEmpty())
        assert(messages("function f() { const s = \"a\"; if (cond) { const s = \"b\" } takeB(s) }").isEmpty())
    }

    @Test
    fun `negative control - a block const shadowing a parameter reads any`() {
        assert(messages("function f(s: \"b\") { takeB(s); { const s = \"a\"; takeB(s) } }").isEmpty())
    }

    @Test
    fun `negative control - a local named after a file-level binding reads any`() {
        assert(messages("const zq = \"b\"; function f() { const zq = \"a\"; takeB(zq) }").isEmpty())
    }

    @Test
    fun `negative control - a local named after a lib global reads any`() {
        // Under the real libs (round 725's `@useRealLibs`, `dom` included) `name` and
        // `length` are lib globals — the collision guard keeps such a local `any` (tsc
        // reads `"a"` / `1`); the same shape under a free name reports in the same run.
        val real = "// @strict: true\n// @useRealLibs: true"
        fun realMessages(src: String) = diagnose(prelude + src, directives = real).map { it.message }
        assert(realMessages("function f() { const name = \"a\"; takeB(name) }").isEmpty())
        assert(realMessages("function f() { const length = 1; takeN(length) }").isEmpty())
        assert(realMessages("function f() { const tap = \"a\"; takeB(tap) }") == listOf(aNotB))
    }

    @Test
    fun `negative control - a callback parameter shadows the enclosing const`() {
        // tsc reads the contextual `number`; the parameter's `any` shadow is what keeps
        // the enclosing `"a"` out ((CHK.42)'s rule) — silent, never `"a"`.
        assert(messages("function f() { const s = \"a\"; [1].forEach(s => takeN(s)) }").isEmpty())
    }

    // ---------------------------------------------------------------------
    // recorded residues — the expectation is OURS
    // ---------------------------------------------------------------------

    @Test
    fun `residue - a conditional initializer is not recorded`() {
        // tsc: `"a" | "c"`. The pre-scan resolves nothing, and a ternary's branches are
        // typed by getTypeOfExpression (the B420 first-touch hazard).
        assert(messages("function f() { const s = cond ? \"a\" : \"c\"; takeB(s) }").isEmpty())
        assert(messages("function f() { const s = cond ? \"a\" : null; if (s) takeB(s) }").isEmpty())
    }

    @Test
    fun `residue - a body-local object member is not recorded`() {
        // tsc: `string`.
        assert(messages("function f() { const o = { v: \"a\" }; takeB(o.v) }").isEmpty())
    }

    @Test
    fun `residue - the TS2367 reader does not see the body-local literal`() {
        // tsc: TS2367 `'"a"' and '"b"'`; TS2678 on a switch already fires.
        assert(messages("function f() { const s = \"a\"; if (s === \"b\") {} }").isEmpty())
        assert(messages("function f() { const s = \"a\"; switch (s) { case \"b\": break } }") ==
            listOf("Type '\"b\"' is not comparable to type '\"a\"'."))
    }

    @Test
    fun `residue - a for-of head const is not recorded`() {
        // (CHK.96)(f) types a `for…of` head in the ccet frame over a `string` or an
        // `Array<T>` only; a const-asserted literal is a READONLY TUPLE, which that arm
        // deliberately leaves `any` (a readonly array-like loop variable surfaced a
        // pre-existing discriminant-narrowing gap on tsc's own sources).
        assert(messages("function f() { for (const s of [\"a\"] as const) { takeB(s) } }").isEmpty())
    }

    @Test
    fun `residue - a non-literal string initializer is not recorded`() {
        // tsc: `string` for both.
        assert(messages("function f() { const s = `a\${cond}`; takeB(s) }").isEmpty())
        assert(messages("function f() { const s = \"a\" + \"b\"; takeB(s) }").isEmpty())
    }
}
