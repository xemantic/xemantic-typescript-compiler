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
 * (CHK.109): an INLINE LITERAL callee is typed, so every TS2349 arm can reach it.
 *
 * ## The defect
 *
 * [Checker.getCalleeType] dispatches on the callee's node class and every literal
 * expression fell to its `else -> anyType`. The callee therefore arrived at
 * `checkSingleCallExpressionTypesCore` as `anyType` and left at that function's
 * `calleeType === anyType || calleeType === errorType` bail — ABOVE the union arm,
 * the signature lookup and `ccetNoCallSignatureDiagnostics` alike, so (CHK.104)'s
 * whole family was unreachable for `({})()`, `({ a: 1 })()` and `[1]()` however
 * complete its evidence was. The type itself was never the problem: the SAME literal
 * reads `{ a: number; }` at a declaration, and through a variable (CHK.104)'s object
 * arm already reported. Measured on one fixture, 1 row here against 16 in BOTH
 * tsgo 7.0.2 and pristine `typescript@6.0.3`.
 *
 * ## The two halves
 *
 * The literal arms in [Checker.getCalleeType] answer [Checker.getTypeOfExpression] —
 * there is no name to resolve and no declaration to find, so the expression IS the
 * value. `true`/`false` need their own leg because they are RESERVED WORDS the Parser
 * renders as an `Identifier`, so they arrive at the Identifier arm and resolve to
 * nothing; the leg sits on that arm's MISS path, where no ordinary callee pays for it.
 *
 * [Checker.calleeObjectTableIsComplete] then admits an inline object (and regex)
 * literal on SYNTACTIC evidence. That is what separates this from (CHK.104): the
 * type-only rule refuses an EMPTY anonymous object, because `{}` minted from a literal
 * and `{}` left by an unfinished resolution are the same type and no predicate over the
 * TYPE can separate them — but the EXPRESSION can, and `({})()` is the pin that says so
 * while `const o = {}; o()` stays a stated false negative.
 *
 * ## FORM divergences this makes VISIBLE and does not cause
 *
 * Four displays differ from both references, and all four reproduce on the parent
 * binary at a DECLARATION position, where this item's mechanism is not involved
 * (measured): `[]` is `any[]` here and `never[]` there; `[() => 1]` is
 * `() => number[]` here and `(() => number)[]` there (a missing parenthesization in
 * `typeToString`); an object literal's GETTER is `readonly g: any` here and
 * `readonly g: number` there; and a computed key keeps its brackets, `{ ["k"]: number; }`
 * against `{ k: number; }`. The rows FIRE, at the right span and code — the divergence
 * is the sub-line's rendering only, and each is a separate pre-existing defect.
 *
 * ## Stated refusals - deliberately NOT pinned
 *
 * `(class {})()` is TS2348 `Value of type 'typeof (Anonymous class)' is not callable.`
 * in both references and stays silent here (that arm is gated on an `Identifier` callee
 * and the display is a naming mechanism this compiler has not got), and `new ({})()` is
 * TS2351 in both and stays silent (the `new` path's emitters are all gated on an
 * `Identifier` or `PropertyAccessExpression` callee). Per CLAUDE.md a known-open gap is
 * recorded in the session note, never pinned as a control.
 */
class InlineLiteralCalleeTest {

    private val directives = "// @strict: true\n// @target: es2020"

    private fun chainOf(d: List<Diagnostic>): List<String> =
        d.first { it.code == 2349 }.messageChain

    // ---- the object half: SYNTACTIC evidence of a complete member table ---------

    /**
     * The discriminating pin of the whole item: an EMPTY anonymous object is exactly
     * what [Checker.calleeObjectTableIsComplete]'s type-only evidence must refuse, so
     * this row can only come from the callee EXPRESSION.
     */
    @Test
    fun `an inline empty object literal callee reports`() {
        val d = diagnose("({})();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type '{}' has no call signatures."))
    }

    @Test
    fun `an inline object literal callee reports its own member table`() {
        val d = diagnose("({ a: 1 })();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type '{ a: number; }' has no call signatures."))
    }

    /**
     * A METHOD is a member, not a call signature — the object itself is still not
     * callable, and the member table the sub-line prints is the literal's own.
     */
    @Test
    fun `an object literal method does not make the object callable`() {
        val d = diagnose("({ m() { return 1; } })();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type '{ m(): number; }' has no call signatures."))
    }

    /**
     * The SYNTACTIC admission is about the inline literal and is NOT a widening of the
     * type-only rule beside it: the identical `{}` reached through a variable stays a
     * stated (CHK.104) false negative, because there the type is all the reader has.
     */
    @Test
    fun `negative control - an empty object through a variable stays silent`() {
        val d = diagnose("const o = {};\no();\n", directives)
        assert(d.none { it.code == 2349 })
    }

    // ---- the array half ---------------------------------------------------------

    @Test
    fun `an inline array literal callee reports`() {
        val d = diagnose("[1]();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'number[]' has no call signatures."))
    }

    @Test
    fun `an inline array literal of objects reports the element shape`() {
        val d = diagnose("[{ a: 1 }]();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type '{ a: number; }[]' has no call signatures."))
    }

    // ---- the primitive half -----------------------------------------------------

    @Test
    fun `an inline string literal callee reports with the String wrapper`() {
        val d = diagnose("(\"x\")();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'String' has no call signatures."))
    }

    @Test
    fun `an inline number literal callee reports with the Number wrapper`() {
        val d = diagnose("(1)();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'Number' has no call signatures."))
    }

    @Test
    fun `an inline template literal callee reports with the String wrapper`() {
        val d = diagnose("(`t`)();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'String' has no call signatures."))
    }

    /**
     * `true` and `false` are RESERVED WORDS the Parser renders as an `Identifier`, so
     * this row proves the Identifier arm's own MISS-path leg rather than the literal
     * arms beside it.
     */
    @Test
    fun `an inline boolean literal callee reports with the Boolean wrapper`() {
        val d = diagnose("(true)();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'Boolean' has no call signatures."))
    }

    /**
     * A regex literal's type is the LIB `RegExp` interface, which
     * [Checker.calleeObjectTableIsComplete]'s declaration test refuses as a lib type —
     * so this row, like the empty object's, comes from the callee EXPRESSION and from
     * nowhere else.
     */
    @Test
    fun `an inline regex literal callee reports with the RegExp type`() {
        val d = diagnose("(/x/)();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'RegExp' has no call signatures."))
    }

    // ---- the shapes the unwrap has to reach -------------------------------------

    @Test
    fun `nested parentheses do not hide an inline literal callee`() {
        val d = diagnose("((({ a: 1 })))();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type '{ a: number; }' has no call signatures."))
    }

    @Test
    fun `an optional call on an inline literal callee reports`() {
        val d = diagnose("({})?.();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type '{}' has no call signatures."))
    }

    /**
     * `tryEmitUncallableTypeArgs` owns the same row for an explicit-type-argument call
     * and runs AFTER the spine ((CHK.104)'s dedupe). It is gated on an `Identifier`
     * callee, so an inline literal never reaches it — the count is what says so.
     */
    @Test
    fun `explicit type arguments on an inline literal callee report exactly once`() {
        val d = diagnose("({})<number>();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type '{}' has no call signatures."))
    }

    /**
     * Arguments do not change the verdict and do not add an arity row — both references
     * report the single TS2349 for `({ a: 1 })(1, 2)`.
     */
    @Test
    fun `arguments do not add a second row to an inline literal callee`() {
        val d = diagnose("({ a: 1 })(1, 2);\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(d.none { it.code == 2554 })
    }

    // ---- the span ---------------------------------------------------------------

    /**
     * Both references anchor at the PARENTHESIZED callee - column 1 of `({})();` - and
     * not at the literal one character in.
     */
    @Test
    fun `the row is anchored at the parenthesized callee`() {
        val d = diagnose("({ a: 1 })();\n", directives)
        val row = d.first { it.code == 2349 }
        assert(row.start == 0)
        assert(row.length == 10)
    }

    // ---- negative controls: a CALLABLE inline literal stays legal ----------------

    @Test
    fun `negative control - an inline arrow callee stays legal`() {
        val d = diagnose("(() => 1)();\n", directives)
        assert(d.none { it.code == 2349 })
    }

    @Test
    fun `negative control - an inline function expression callee stays legal`() {
        val d = diagnose("(function () { return 1; })();\n", directives)
        assert(d.none { it.code == 2349 })
    }

    @Test
    fun `negative control - an inline arrow callee with arguments stays legal`() {
        val d = diagnose("((x: number) => x)(1);\n", directives)
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - an inline async arrow callee stays legal`() {
        val d = diagnose("(async () => 1)();\n", directives)
        assert(d.none { it.code == 2349 })
    }

    // ---- negative controls: a literal RECEIVER is not a callee -------------------

    @Test
    fun `negative control - an array literal receiver stays legal`() {
        val d = diagnose("const n: number = [1].length;\n", directives)
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - an object literal receiver stays legal`() {
        val d = diagnose("const n: number = ({ a: 1 }).a;\n", directives)
        assert(d.isEmpty())
    }
}
