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
 * (CHK.47) AN **OUTER BINDING OF THE SAME NAME** DEFEATED EVERY BLOCK-SCOPED
 * RECEIVER, AND THE MESSAGE THEN NAMED THE **OUTER** TYPE.
 *
 * `lookupPerFileForNode` is keyed by the FILE, so for a receiver identifier it
 * answers the file-level declaration of that spelling however deeply the reference
 * is nested — and CLAUDE.md's B83.5 leaves every block-scoped declaration out of
 * the binder tables, so the shadowing declaration was invisible to it and to
 * `getTypeOfSymbol` alike. Three of the four shapes below were therefore wrong in
 * the worst direction a checker can be wrong: a CONFIDENT message naming a type the
 * expression does not have. Measured against `tools/tsgo-7.0.2/lib/tsc` on the
 * SHIPPED binary, through the CLI over identical source:
 *
 * | # | shape | ours | tsgo |
 * |---|---|---|---|
 * | A | `const inner: Deep` + `const { inner } = h; inner.zzznope` | `Deep` | `Inner` |
 * | B | `function alpha()` + `function f({ alpha }: Inner) { alpha.zzznope }` | `typeof alpha` | `string` |
 * | C | `const cc: Deep` + `const cc = h.inner; cc.zzznope` | SILENT | `Inner` |
 * | D | `const dd: Deep` + `const dd: Inner = h.inner; dd.zzznope` | `Deep` | `Inner` |
 *
 * All four now match tsgo exactly, message for message and column for column.
 *
 * ### THREE MECHANISMS, NOT ONE — and the queue item named only the first
 *
 *  1. `Checker.cmamLexicalValueShadow` refuses the per-file symbol when an inner
 *     lexical VALUE binding shadows the name, which routes A/C/D into the branch
 *     that reads `currentLocalTypes` and (CHK.46)'s two block-scoped helpers.
 *     `cmamShadowReadingWins` decides which of the two readings wins, and D is what
 *     discriminates it: an ANNOTATED body-local has a real `currentLocalTypes` entry
 *     and must keep it.
 *  2. B is not that walker at all. `spineExEnterNode` — the B431 expando-function
 *     anchor — carries its OWN shadow test, and `spineExFnShadows` compared
 *     `(x.name as? Identifier)?.text`, i.e. it was blind to every destructuring
 *     form. `spineExBindingNameShadows` recurses into binding patterns.
 *  3. C additionally needed round 512's un-inferable-shadow bail to stand down where
 *     a block-scoped helper CAN name the inner binding's type. Its premise is that
 *     the receiver is unknowable; (CHK.46) made that premise false for one
 *     population, and where neither helper answers the bail is untouched.
 *
 * ### CALIBRATION
 *
 * `added=0 removed=0` on all eight tsc profiles, zero corpus baselines moved,
 * `cost_gate` PASSES unrebaselined with `output.errors` 46. On **knip** the change
 * is worth **66 -> 49 diagnostics, seventeen removals and no additions**, and every
 * one of the seventeen is a FALSE POSITIVE confirmed silent in tsgo 7.0.2 — fifteen
 * of them `Property '0' does not exist on type 'Plugin'`, where a `for (const plugin
 * of …)` loop variable had been resolving to the file's own `const plugin: Plugin`,
 * and two the same shape for `Args` at a callback PARAMETER. That row family is the
 * one `PLAN-PHASE-5.md` had already flagged as "a resolution/collision, not
 * narrowing" and left uncharacterized.
 *
 * ### VACUITY
 *
 * Every one of the four pins FAILED on a parent binary rebuilt in this session, and
 * failed with the WRONG VALUE visible in the power-assert diagram (A/B/D) or with no
 * diagnostic at all (C) — never by construction. They are value assertions on the
 * message, which is the only thing that separates "reports about the outer type"
 * from "reports about the inner one"; a count-only pin passes on both binaries for
 * A, B and D.
 */
class ShadowedReceiverTypeTest {

    private val prelude = """
        interface Inner { alpha: string }
        interface Deep { beta: number }
        interface Holder { inner: Inner }
        declare const h: Holder;
        declare function use(x: unknown): void;
        declare const dcl: Deep;
    """.trimIndent() + "\n"

    @Test
    fun `A - a body-local DESTRUCTURED name under a colliding file-level const`() {
        val d = diagnose(
            prelude + "const inner: Deep = dcl;\nuse(inner.beta);\n" +
                "export function f() { const { inner } = h; use(inner.zzznope); }"
        )
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Inner'.")
    }

    @Test
    fun `B - a destructured PARAMETER under a colliding file-level function`() {
        val d = diagnose(
            prelude + "export function alpha() {}\n" +
                "export function f({ alpha }: Inner) { use(alpha.zzznope); }"
        )
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'string'.")
    }

    @Test
    fun `C - an UN-ANNOTATED body-local const under a colliding file-level const`() {
        val d = diagnose(
            prelude + "const cc: Deep = dcl;\nuse(cc.beta);\n" +
                "export function f() { const cc = h.inner; use(cc.zzznope); }"
        )
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Inner'.")
    }

    @Test
    fun `D - an ANNOTATED body-local const under a colliding file-level const`() {
        val d = diagnose(
            prelude + "const dd: Deep = dcl;\nuse(dd.beta);\n" +
                "export function f() { const dd: Inner = h.inner; use(dd.zzznope); }"
        )
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Inner'.")
    }
}
