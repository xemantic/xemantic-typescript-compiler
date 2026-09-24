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
 * (CHK.152) step 3, round P18.198 — a NAMED argument against a UNION (or nullable) parameter of
 * named objects is related at the argument, and a PROPERTY-ACCESS argument whose relation fails
 * gets a SECOND CHANCE: its member re-read off the FLOW-NARROWED receiver, with the member's own
 * path narrowing on top.
 *
 * tsgo types `parent.parent` after `isImportClause(parent)` by narrowing the receiver first and
 * the member path second (`checkPropertyAccessExpressionOrQualifiedName` over a
 * `checkNonNullExpression` receiver, then `getFlowTypeOfAccessExpression`, checker.go ~11361).
 * This checker narrows a receiver only when its raw type is already a union, so admitting union
 * parameters alone (the a8 arm of (P18.183)) reported `Node` against
 * `ImportDeclaration | JSDocImportTag` on harness `services/utilities.ts:1366` and
 * `completions.ts:3275` — legal code. `Checker.argMemberRereadFromNarrowedReceiver` answers both;
 * it is asked only on the rejecting path and adopted only as a REFINEMENT of the reader's own
 * type, never `never` / `any`. The union chain is `Checker.argNamedVsUnionParamChain`: the
 * nullable strip, the best constituent, a missing member before a member type.
 *
 * The last test pins the seams of `checkArgumentsAgainstSignatureCore`'s split (`caasArgTypeFor`,
 * the argument-type computation moved out verbatim): the narrowed identifier, the contextually
 * typed arrow, the literal source and a second argument after a contextual one.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW over the identical text (`build/scratch-p18198/pins`,
 * `strict`, `target: es2020`). A row is `line:column code message` with its chain appended as
 * ` / <line>`. Residue, asserted head-only below: a union argument's chain names the LAST failing
 * constituent where tsgo names the first against a primitive target (pre-existing, identical for
 * an argument that arrives narrowed).
 */
class NarrowedReceiverArgumentTest {

    private val prelude = """
        export {};
        interface Node { kind: number; parent: Node }
        interface ImportDeclaration extends Node { kind: 1; id: 1 }
        interface JSDocImportTag extends Node { kind: 2; jt: 1 }
        interface ImportClause extends Node { kind: 3; parent: ImportDeclaration | JSDocImportTag; isTypeOnly: boolean }
        interface JsxElement extends Node { kind: 5; je: 1 }
        interface JsxFragment extends Node { kind: 6; jf: 1 }
        interface JsxAttribute extends Node { kind: 7; ja: 1 }
        interface JsxSpreadAttribute extends Node { kind: 8; js: 1 }
        interface JsxExpression extends Node { kind: 9; parent: JsxElement | JsxFragment | JsxAttribute | JsxSpreadAttribute }
        declare function isImportClause(n: Node): n is ImportClause;
        declare function isJsxExpression(n: Node): n is JsxExpression;
        declare function isJsxElement(n: Node): n is JsxElement;
        declare function isJsxFragment(n: Node): n is JsxFragment;
        declare function adj(n: ImportDeclaration | JSDocImportTag): void;
        declare function adjN(n: ImportDeclaration | undefined): void;
        declare function ctx(a: JsxAttribute | JsxSpreadAttribute): void;
        declare function ctxF(a: JsxFragment | JsxAttribute): void;
        declare function ctxE(a: JsxElement): void;
        declare function pn(n: number): void;
    """.trimIndent()

    private fun diagnostics(line21: String) =
        diagnose(prelude + "\n" + line21, directives = "// @strict: true\n// @target: es2020")

    private fun rows(line21: String): List<String> =
        diagnostics(line21).map { d ->
            "${d.line}:${d.character} ${d.code} ${d.message}" +
                d.messageChain.joinToString("") { " / " + it.trim() }
        }.sorted()

    private fun heads(line21: String): List<String> =
        diagnostics(line21).map { d -> "${d.line}:${d.character} ${d.code} ${d.message}" }.sorted()

    @Test
    fun `the narrowed receiver's member relates - the harness utilities shape is legal`() {
        val ifAnd = rows("function u1(parent: Node) { if (isImportClause(parent) && parent.isTypeOnly) { adj(parent.parent); } }")
        val and = rows("function u2(parent: Node) { isImportClause(parent) && adj(parent.parent); }")
        val early = rows("function u3(parent: Node) { if (!isImportClause(parent)) return; adj(parent.parent); }")
        val path = rows("function u12(h: { p: Node }) { if (isImportClause(h.p)) adj(h.p.parent); }")
        assert(ifAnd.isEmpty())
        assert(and.isEmpty())
        assert(early.isEmpty())
        assert(path.isEmpty())
    }

    @Test
    fun `negated guards narrow the re-read member - the harness completions shape is legal`() {
        val ternary = rows("function j1(parent: Node) { return isJsxExpression(parent) && !isJsxElement(parent.parent) && !isJsxFragment(parent.parent) ? ctx(parent.parent) : undefined; }")
        val early = rows("function j4(parent: Node) { if (!isJsxExpression(parent) || isJsxElement(parent.parent) || isJsxFragment(parent.parent)) return; ctx(parent.parent); }")
        assert(ternary.isEmpty())
        assert(early.isEmpty())
    }

    @Test
    fun `an earlier guard on the receiver does not widen the re-read member`() {
        // The member's path narrowing starts from the re-read; an earlier `isJsxElement(parent)`
        // resets `parent.parent` through the prefix arm to `JsxElement`'s own `parent` (`Node`),
        // so the join comes back `ImportDeclaration | JSDocImportTag | Node` — the harness
        // `services/utilities.ts:1366` measurement. tsgo resets to the re-read and is silent.
        val block = rows("function pj2(parent: Node) { if (isJsxElement(parent)) { pn(1); } if (isImportClause(parent) && parent.isTypeOnly) adj(parent.parent); }")
        val early = rows("function pj5(parent: Node) { if (isJsxElement(parent) && parent.kind === 5) { return; } if (isImportClause(parent) && parent.isTypeOnly) { adj(parent.parent); } }")
        assert(block.isEmpty())
        assert(early.isEmpty())
    }

    @Test
    fun `a re-read that still fails is the type the message names`() {
        val pathNarrowed = rows("function k4(parent: Node) { if (isJsxExpression(parent) && isJsxElement(parent.parent)) ctxF(parent.parent); }")
        val primitive = heads("function pn1(parent: Node) { if (isImportClause(parent) && parent.isTypeOnly) pn(parent.parent); }")
        assert(pathNarrowed == listOf(
            "21:94 2345 Argument of type 'JsxElement' is not assignable to parameter of type 'JsxAttribute | JsxFragment'." +
                " / Property 'jf' is missing in type 'JsxElement' but required in type 'JsxFragment'.",
        ))
        assert(primitive == listOf(
            "21:82 2345 Argument of type 'ImportDeclaration | JSDocImportTag' is not assignable to parameter of type 'number'.",
        ))
    }

    @Test
    fun `a named argument against a union or nullable parameter reports as tsgo`() {
        val union = rows("function u4(parent: Node) { adj(parent.parent); }")
        val nullableMissing = rows("function u7(parent: Node) { adjN(parent.parent); }")
        val classArg = rows("interface S1 { a: string } interface S2 { b: string } class CQ { a = 0 } declare const cq: CQ; declare function z(p: S1 | S2): void; z(cq);")
        val nullParam = rows("interface S1 { a: string } interface Q { a: number } declare const q: Q; declare function zn(p: S1 | null): void; zn(q);")
        val undefinedParam = rows("interface S1 { a: string } interface Q { a: number } declare const q: Q; declare function zu(p: S1 | undefined): void; zu(q);")
        assert(union == listOf(
            "21:33 2345 Argument of type 'Node' is not assignable to parameter of type 'ImportDeclaration | JSDocImportTag'." +
                " / Property 'jt' is missing in type 'Node' but required in type 'JSDocImportTag'.",
        ))
        assert(nullableMissing == listOf(
            "21:34 2741 Property 'id' is missing in type 'Node' but required in type 'ImportDeclaration'.",
        ))
        assert(classArg == listOf(
            "21:136 2345 Argument of type 'CQ' is not assignable to parameter of type 'S1 | S2'." +
                " / Type 'CQ' is not assignable to type 'S1'. / Types of property 'a' are incompatible." +
                " / Type 'number' is not assignable to type 'string'.",
        ))
        assert(nullParam == listOf(
            "21:118 2345 Argument of type 'Q' is not assignable to parameter of type 'S1'." +
                " / Types of property 'a' are incompatible. / Type 'number' is not assignable to type 'string'.",
        ))
        assert(undefinedParam == listOf(
            "21:123 2345 Argument of type 'Q' is not assignable to parameter of type 'S1'." +
                " / Types of property 'a' are incompatible. / Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `negative control - legal arguments to union and nullable parameters stay silent`() {
        val actual = rows("interface S1 { a: string } interface S2 { b: string } interface G<T> { a: T } declare const s: S1; declare const g: G<string>; declare function zn(p: S1 | null): void; declare function zu(p?: S1 | S2): void; zn(s); zu(g); zn(null); zu(undefined); zu();")
        assert(actual.isEmpty())
    }

    @Test
    fun `negative control - a re-read that is not a refinement changes nothing`() {
        val actual = rows("interface Q7 { q: 1 } interface R7 { r: 1 } interface N7 { kind: number; parent: Q7 } interface E7 { kind: 1; parent: R7 } declare function isE7(n: unknown): n is E7; declare function tq(p: Q7): void; function x(n: N7) { isE7(n) && tq(n.parent); }")
        assert(actual.isEmpty())
    }

    @Test
    fun `negative control - an unreachable argument keeps its declared type`() {
        val actual = rows("function k2(parent: Node) { if (!isJsxExpression(parent)) return; return; ctxE(parent.parent); }")
        assert(actual == listOf(
            "21:80 2741 Property 'je' is missing in type 'Node' but required in type 'JsxElement'.",
        ))
    }

    @Test
    fun `the split argument-type computation keeps narrowing context and literals`() {
        val narrowedIdentifier = rows("function s1(x: string | number) { if (typeof x === \"string\") pn(x); }")
        val contextualArrow = rows("declare function cb(f: (s: string) => void): void; cb((s) => pn(s));")
        val literal = rows("declare function lit(x: \"a\" | \"b\"): void; lit(\"c\");")
        val afterContextual = rows("declare function two(f: (s: string) => void, o: { m: number }): void; two((s) => {}, { m: \"x\" });")
        assert(narrowedIdentifier == listOf(
            "21:65 2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
        ))
        assert(contextualArrow == listOf(
            "21:65 2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
        ))
        assert(literal == listOf(
            "21:47 2345 Argument of type '\"c\"' is not assignable to parameter of type '\"a\" | \"b\"'.",
        ))
        assert(afterContextual == listOf(
            "21:88 2322 Type 'string' is not assignable to type 'number'.",
        ))
    }
}
