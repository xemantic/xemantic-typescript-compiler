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
 * (LEGACY.0b) — TS7009 for a `new` whose callee is NOT a bare identifier.
 *
 * Every row here was measured against `tools/tsgo-7.0.2/lib/tsc` over one mixed fixture
 * BEFORE the rule was written, positives and negatives together. tsc's rule is about the
 * RESOLVED SIGNATURE: `checkCallExpression` reports when the signature's declaration is
 * not a constructor / construct signature / constructor type, i.e. when a CALL signature
 * was used for `new`. Our arm reproduces that from the callee TYPE — call signatures
 * present AND construct signatures absent — which is positive evidence in both
 * directions, so a callee this checker cannot type (it answers `anyType`) is refused
 * rather than reported.
 *
 * **THE NEGATIVE HALF IS WHAT MAKES THIS FALSIFIABLE**, and two of its rows were found by
 * the corpus screen rather than by reading:
 *  * a namespace-qualified CLASS is silent — here for a DIFFERENT reason than in tsc,
 *    because (CHK.73) types a class value as its INSTANCE type, which carries neither
 *    signature kind, where tsc sees a construct signature. Same verdict today; the day
 *    (CHK.73) is fixed this arm must stay silent for the tsc reason instead.
 *  * a CLODULE (`declare namespace M { class C; function C }`) is silent only because the
 *    refusal consults B511's `findNamespaceMemberClassDecl`: class+function do not merge
 *    in this binder, so neither the callee type nor the resolved symbol can see the
 *    construct side. That row is `constructorOverloads4`, which the screen reddened.
 *
 * **WHAT THIS DOES NOT CLOSE.** `commonjsAccessExports.errors.txt` needs the same rows in
 * a `.js` file, and the whole ccet family — which owns this emitter's ambient — returns
 * early on `spineIsJsLike` (`ccetSpineLeave`). The identifier-callee TS7009 fires in JS
 * because it lives on a different anchor (`spineNaEnterNode`). Closing the ledger row
 * therefore means either relaxing that file gate, which (P18.130) measures as its own
 * hazard, or a JS-only path whose receiver resolution is shadow-safe — the anchor that
 * runs in JS installs only `currentFileLocals`, so a body-local receiver would resolve to
 * a same-named file-level binding (round 911).
 */
class NewExprImplicitAnyNonIdentifierCalleeTest {

    private val message =
        "'new' expression, whose target lacks a construct signature, implicitly has an 'any' type."

    private fun rows(source: String): List<String> =
        diagnose(source).filter { it.code == 7009 }.map { it.message }

    private fun count(source: String): Int = diagnose(source).count { it.code == 7009 }

    // ------------------------------------------------------------------ positives

    @Test
    fun `an object-literal method property callee reports`() {
        assert(rows("const O = { m: function () {} };\nnew O.m();") == listOf(message))
    }

    @Test
    fun `a namespace function callee reports`() {
        assert(rows("namespace N { export function f() {} }\nnew N.f();") == listOf(message))
    }

    @Test
    fun `an element-access callee reports`() {
        assert(rows("const arr = [function () {}];\nnew arr[0]();") == listOf(message))
    }

    @Test
    fun `an annotated function-type property callee reports`() {
        assert(rows("declare const h: { g: () => void };\nnew h.g();") == listOf(message))
    }

    @Test
    fun `a static method callee reports`() {
        assert(rows("class Base { static make(): void {} }\nnew Base.make();") == listOf(message))
    }

    @Test
    fun `the row is anchored at the new keyword, not at the callee`() {
        // tsgo prints `(2,1)` for a `new` starting line 2 — the whole expression, where
        // the sibling TS2351 family anchors at the CALLEE. A pin on the code alone cannot
        // see an anchor that drifted onto the property.
        val d = diagnose("declare const h: { g: () => void };\nnew h.g();").single { it.code == 7009 }
        assert(d.line == 2)
        assert(d.character == 1)
    }

    // ------------------------------------------------------------------ negatives

    @Test
    fun `negative control - a namespace-qualified class is silent`() {
        assert(count("namespace C { export class K {} }\nnew C.K();") == 0)
    }

    @Test
    fun `negative control - a construct-signature-typed property is silent`() {
        assert(count("declare const holder: { ctor: { new (): object } };\nnew holder.ctor();") == 0)
    }

    @Test
    fun `negative control - an any-typed property callee is silent`() {
        // tsc's `resolveUntypedCall` returns before the check, so an `any` callee never
        // reports — and this is also the property that keeps the rule off every callee
        // this checker failed to type.
        assert(count("declare const o: { p: any };\nnew o.p();") == 0)
    }

    @Test
    fun `negative control - a clodule keeps its construct side`() {
        assert(
            count(
                """
                declare namespace M {
                    export class Function {
                        constructor(...args: string[]);
                    }
                    export function Function(...args: any[]): any;
                    export function Function(...args: string[]): Function;
                }
                new M.Function("return 5");
                """.trimIndent(),
            ) == 0,
        )
    }

    @Test
    fun `an identifier callee still reports exactly once`() {
        // The identifier path is a DIFFERENT emitter (`checkNewExprImplicitAny`, symbol-
        // based, on the `spineNa` anchor). Running both for one expression would
        // double-emit, which only a COUNT can see.
        assert(count("function plain() {}\nnew plain();") == 1)
    }
}
