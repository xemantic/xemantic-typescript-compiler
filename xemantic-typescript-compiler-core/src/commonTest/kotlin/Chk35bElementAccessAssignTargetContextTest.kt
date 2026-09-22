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
 * (CHK.35b) An ELEMENT-ACCESS assignment target supplies a contextual type to its
 * right-hand side, exactly as a property-access target already did.
 *
 * Before this, `bag["x"] = function (t) { … }` drew an ours-only TS7006 / TS7019 on
 * legal code — tsgo types `t` and reports whatever is wrong INSIDE the body. Measured
 * over an 18-cell matrix against tsgo 7.0.2 (the sole reference since the owner's
 * 2026-09-12 directive): every element-access form failed and the key's shape was
 * irrelevant — a string literal, a computed key, a literal-typed key variable, a
 * numeric index, a nested receiver, an arrow right-hand side and a rest parameter
 * alike — while an Identifier target, a PropertyAccess target, a nested
 * `nest.inner.cb`, a variable declaration, a call argument and a body-local receiver
 * all already agreed.
 *
 * tsgo has ONE predicate for the whole family: `getContextualTypeForAssignmentExpression`
 * (checker.go:29599) gates on `ast.IsAccessExpression(left)` — property AND element
 * access — and answers `getTypeOfExpression(left)`.
 *
 * WHY THE PINS ASSERT A TS2322 AND NOT A SILENCE. A missing TS7006 cannot tell "the
 * parameter was typed" from "the parameter is `any` and something else suppressed the
 * row" — `any` draws no implicit-any diagnostic either. Every pin below therefore
 * carries a deliberate mis-assignment inside the function body whose TS2322 message
 * NAMES the type the parameter was given, which only a correctly typed parameter can
 * produce. The TS7006 absence is asserted beside it, never instead of it.
 *
 * DELIBERATELY OUT OF SCOPE, and pinned as such at the bottom: the compound assignment
 * operators (`||=`, `&&=`, `??=`), where tsgo's `getContextualTypeForBinaryOperand`
 * shares ONE case label with `=` and this checker's pull reader gates on
 * `SyntaxKind.Equals` alone — measured silent here for an Identifier and a
 * PropertyAccess target too, i.e. an OPERATOR-axis gap that predates this family; and
 * a `this` receiver, which is (CHK.141)/(CHK.35d) — B101 makes
 * `getTypeOfExpression(this)` answer `anyType`, so `this.bag["x"] = fn` and
 * `this.cb = fn` are now silent in exactly the same way.
 */
class Chk35bElementAccessAssignTargetContextTest {

    private val prelude = """
        interface Tok { kind: string }
    """.trimIndent() + "\n"

    private val tokNotNum = "Type 'Tok' is not assignable to type 'number'."
    private val tokArrNotNum = "Type 'Tok[]' is not assignable to type 'number'."

    private fun rows(body: String, code: Int): List<Diagnostic> =
        diagnose(prelude + body.trimIndent(), directives = "// @strict: true")
            .filter { it.code == code }

    private fun implicitAnyRows(body: String): List<Diagnostic> =
        diagnose(prelude + body.trimIndent(), directives = "// @strict: true")
            .filter { it.code == 7006 || it.code == 7019 }

    // ------------------------------------------------------- the key's shape is irrelevant

    @Test
    fun `an element access with a string literal key types the assigned function's parameter`() {
        val src = """
            declare const bag: { [k: string]: (a: Tok) => void };
            bag["x"] = function (t) { const bad: number = t; };
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    @Test
    fun `an element access with a computed key types the assigned function's parameter`() {
        val src = """
            declare const bag: { [k: string]: (a: Tok) => void };
            declare const key: string;
            bag[key] = function (t) { const bad: number = t; };
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    @Test
    fun `an element access naming a declared property types the assigned function's parameter`() {
        val src = """
            declare const obj: { cb: (a: Tok) => void };
            obj["cb"] = function (t) { const bad: number = t; };
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    @Test
    fun `an element access with a literal-typed key variable types the assigned function's parameter`() {
        val src = """
            declare const obj: { cb: (a: Tok) => void };
            declare const k: "cb";
            obj[k] = function (t) { const bad: number = t; };
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    @Test
    fun `an element access with a numeric index types the assigned function's parameter`() {
        val src = """
            declare const arr: ((a: Tok) => void)[];
            arr[0] = function (t) { const bad: number = t; };
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    @Test
    fun `an element access on a nested receiver types the assigned function's parameter`() {
        val src = """
            declare const nest: { inner: { [k: string]: (a: Tok) => void } };
            nest.inner["x"] = function (t) { const bad: number = t; };
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    // ------------------------------------------------------- the right-hand side's shape

    @Test
    fun `an arrow assigned through an element access has its parameter typed`() {
        val src = """
            declare const bag: { [k: string]: (a: Tok) => void };
            bag["x"] = (t) => { const bad: number = t; };
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    @Test
    fun `a rest parameter assigned through an element access is typed from the slot`() {
        val src = """
            declare const bag: { [k: string]: (...args: Tok[]) => void };
            bag["x"] = function (...args) { const bad: number = args; };
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokArrNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    // --------------------------------- the receiver lives where the property-access arm looks

    @Test
    fun `an element access on a body-local receiver types the assigned function's parameter`() {
        val src = """
            function zf(): void {
              const o: { r: { [k: string]: (a: Tok) => void } } = { r: {} };
              o.r["x"] = function (t) { const bad: number = t; };
            }
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    @Test
    fun `an element access on an outer function's local types the parameter from a nested function`() {
        val src = """
            function zf(): void {
              const o: { r: { [k: string]: (a: Tok) => void } } = { r: {} };
              function zi(s: string): void {
                o.r[s] = function (t) { const bad: number = t; };
              }
              zi("x");
            }
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    // ------------------------------------- the key bound by the walk - marked's own shape

    @Test
    fun `a key that is the enclosing function's own parameter still resolves the slot`() {
        val src = """
            declare const bag: { [k: string]: (a: Tok) => void };
            function zf(s: string): void {
              bag[s] = function (t) { const bad: number = t; };
            }
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    @Test
    fun `a member read on a key bound by the walk still resolves the slot`() {
        val src = """
            interface Ext { name: string }
            declare const bag: { [k: string]: (a: Tok) => void };
            function zf(e: Ext): void {
              bag[e.name] = function (t) { const bad: number = t; };
            }
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    @Test
    fun `the typed parameter reaches an argument position`() {
        val src = """
            declare const bag: { [k: string]: (a: Tok) => void };
            declare function zt(x: string): void;
            bag["x"] = function (t) { zt(t); };
        """
        assert(rows(src, 2345).map { it.message } ==
            listOf("Argument of type 'Tok' is not assignable to parameter of type 'string'."))
    }

    /**
     * MEASURED, not assumed: the TS2339 member-existence reader is silent for an
     * ASSIGNMENT target of EITHER kind — `obj.cb = function (t) { t.nope }` is silent
     * here too, and has been all along — while the same body in a call ARGUMENT
     * reports. tsgo 7.0.2 reports all three. So the silence is (CHK.39)/(CHK.98)'s
     * per-READER residue on the assignment position and is not this family's to give;
     * what this round owns is that element access now answers exactly as property
     * access does, which is what this pin asserts. It is written as a VALUE pin over
     * the whole fixture — the call-argument row is its non-vacuous member — so it
     * reddens if the two target kinds ever diverge in EITHER direction, and it keeps
     * holding on the day the shared residue closes and both rows appear together.
     */
    @Test
    fun `residue - the member-existence reader treats both access targets alike`() {
        val d = diagnose(prelude + """
            declare const bag: { [k: string]: (a: Tok) => void };
            declare const obj: { cb: (a: Tok) => void };
            declare function take(cb: (a: Tok) => void): void;
            bag["x"] = function (t) { t.nope; };
            obj.cb = function (t) { t.nope; };
            take(function (t) { t.nope; });
        """.trimIndent(), directives = "// @strict: true")
        assert(d.filter { it.code == 2339 }.map { it.message } ==
            listOf("Property 'nope' does not exist on type 'Tok'."))
        assert(d.none { it.code == 7006 || it.code == 7019 })
    }

    // ------------------------------------------------------------------------- controls
    // Every one of these agreed with tsgo BEFORE the change and must still agree; they
    // are what says the element arm was added beside the existing ones and not on top
    // of them.

    @Test
    fun `control - an identifier assignment target still types the parameter`() {
        val src = """
            let cb: (a: Tok) => void;
            cb = function (t) { const bad: number = t; };
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    @Test
    fun `control - a property access assignment target still types the parameter`() {
        val src = """
            declare const obj: { cb: (a: Tok) => void };
            obj.cb = function (t) { const bad: number = t; };
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    @Test
    fun `control - a nested property access assignment target still types the parameter`() {
        val src = """
            declare const nest: { inner: { cb: (a: Tok) => void } };
            nest.inner.cb = function (t) { const bad: number = t; };
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    @Test
    fun `control - a variable declaration annotation still types the parameter`() {
        val src = """
            const zf: (a: Tok) => void = function (t) { const bad: number = t; };
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    @Test
    fun `control - a call argument still types the parameter`() {
        val src = """
            declare function take(cb: (a: Tok) => void): void;
            take(function (t) { const bad: number = t; });
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    @Test
    fun `control - a property access on a body-local receiver still types the parameter`() {
        val src = """
            function zf(): void {
              const o: { cb: (a: Tok) => void } = { cb: () => {} };
              o.cb = function (t) { const bad: number = t; };
            }
        """
        assert(rows(src, 2322).map { it.message } == listOf(tokNotNum))
        assert(implicitAnyRows(src).isEmpty())
    }

    // --------------------------------------------------------------- negative controls
    // The arm answers a SLOT or it answers nothing; it must never invent one.

    @Test
    fun `negative control - an any receiver supplies no contextual type`() {
        val src = """
            declare const bag: any;
            bag["x"] = function (t) { const bad: number = t; };
        """
        assert(rows(src, 2322).isEmpty())
        assert(implicitAnyRows(src).map { it.code } == listOf(7006))
    }

    @Test
    fun `negative control - a literal key naming no member supplies no contextual type`() {
        val src = """
            declare const obj: { cb: (a: Tok) => void };
            obj["nope"] = function (t) { const bad: number = t; };
        """
        assert(rows(src, 2322).isEmpty())
        assert(implicitAnyRows(src).map { it.code } == listOf(7006))
    }
}
