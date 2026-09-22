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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.35a): a function expression assigned DIRECTLY to a member does not draw TS2683.
 *
 * ## The rule, measured over six shapes against tsgo 7.0.2
 *
 * tsgo is SILENT for `o.m = function () { this }` and `o["k"] = function () { this }` — the
 * assignment supplies the `this` — and REPORTS for three neighbours: an Identifier LHS
 * (`v = function () { this }`), an IIFE (`q.x = function () { this }()`) and a NESTED
 * function expression. Those three are the ablation targets: an arm that tested "is there a
 * `=` anywhere above me" instead of the IMMEDIATE parent would silence all three.
 *
 * They fall out of the conjuncts rather than needing arms of their own. An Identifier LHS is
 * excluded explicitly; an IIFE's parent is the `CallExpression`, so the arm ABOVE this one
 * decides it and answers false for a callee; a nested function expression's parent is its own
 * enclosing statement.
 *
 * ## Why the test is syntactic
 *
 * The previously-reverted attempt ((P18.164)) keyed on the TARGET TYPE declaring a `this:`
 * parameter — tsgo's real model — and moved 0 rows on the library it was written for. The
 * recorded reason was that `marked` declares no `this:`; re-measured, that is true of its
 * `walkTokens` cluster only, and its OTHER cluster's target
 * (`RendererExtensionFunction<ParserOutput, RendererOutput>`) does declare one. The type-keyed
 * test could not see it because that target resolves to `any`: a contextual parameter type
 * mentioning a free type parameter collapses, which is its own item ((CHK.35c)). A syntactic
 * test is immune to that, which is the whole reason this one is syntactic.
 *
 * ## What this deliberately does NOT do
 *
 * It SUPPRESSES; it does not type `this` as the receiver. The residue is pinned below and is a
 * false NEGATIVE — the conservative direction — measured at zero rows on both libraries, all
 * eight dashboard profiles and the active corpus. Typing `this` as the receiver without also
 * implementing tsgo's precedence rule (a contextual `this:` OUTRANKS the receiver) would add a
 * false POSITIVE wherever the body forwards `this` to a callee that declares its own, which is
 * exactly what the library this serves does.
 *
 * ## Why these pins and not the grid or the screen
 *
 * All eight profiles carry ZERO TS2683 rows, so the grid cannot move. Of the 50 corpus
 * baselines containing TS2683, exactly two also contain a member-assigned function expression,
 * and in neither is the row AT such a `this` — `thisBinding2` (ACTIVE) is an IIFE, i.e. the
 * ablation target, and `classCanExtendConstructorFunction` is `checkJs`-gated. So both standing
 * instruments are CONTROLS here and these pins are the gate.
 *
 * ## What the ablation actually discriminated
 *
 * a1 (arm removed): the two suppression pins RED. a2 (the realistic over-broad mistake -- search
 * for ANY enclosing assignment rather than testing the IMMEDIATE parent): the NESTED pin RED.
 * The IIFE pin stayed green in BOTH, and not because it is blind: an IIFE's function expression
 * has the `CallExpression` as its parent, never the `BinaryExpression`, so it cannot reach this
 * arm at all. It is a guard against a future arm that walks ancestors, and `thisBinding2` is the
 * corpus baseline that would catch that. The `pp.right === parent` conjunct is likewise measured
 * REDUNDANT (a3, 0 RED) and kept as a barrier rather than claimed as load-bearing.
 */
class MemberAssignedFunctionThisTest {

    private val recv = "declare const o: { m: unknown; [key: string]: unknown };\n" +
        "declare const q: { x: unknown };\n"

    @Test
    fun `a function expression assigned to a property access gets its this from the assignment`() {
        diagnose(recv + "o.m = function () { return this; };") should {
            have(none { it.code == 2683 })
        }
    }

    @Test
    fun `a function expression assigned to an element access gets its this from the assignment`() {
        diagnose(recv + """o["k"] = function () { return this; };""") should {
            have(none { it.code == 2683 })
        }
    }

    @Test
    fun `an Identifier assignment target still reports TS2683`() {
        // ABLATION TARGET. tsgo reports here: a bare variable supplies no receiver.
        diagnose(recv + "let v: unknown;\nv = function () { return this; };") should {
            have(any { it.code == 2683 })
        }
    }

    @Test
    fun `an immediately-invoked function expression still reports TS2683`() {
        // ABLATION TARGET, and it is the shape of the one ACTIVE corpus baseline that could
        // move (`thisBinding2`). The fn-expr's parent is the CallExpression, not the `=`.
        diagnose(recv + "q.x = function () { return this; }();") should {
            have(any { it.code == 2683 })
        }
    }

    @Test
    fun `a nested function expression under a member assignment still reports TS2683`() {
        // ABLATION TARGET. Only the IMMEDIATE parent may satisfy the arm.
        diagnose(recv + "q.x = function () { return function () { return this; }; };") should {
            have(any { it.code == 2683 })
        }
    }

    @Test
    fun `residue - this is suppressed rather than typed so a bad member on it is not reported`() {
        // MEASURED 2026-09-22: tsgo answers
        // `Property 'nope' does not exist on type '{ m: unknown; }'` (TS2339) and we are
        // SILENT. A deliberate false NEGATIVE — before this change the same line drew a WRONG
        // TS2683, so the divergence moved rather than appeared — and it is what (CHK.35d), the
        // `this`-as-receiver model, closes. Asserting today's divergent answer on purpose.
        diagnose("declare const o: { m: unknown };\no.m = function () { return this.nope; };") should {
            have(none { it.code == 2339 })
        }
    }
}
