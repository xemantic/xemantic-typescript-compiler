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
 * (REL.1)(c) round 747: what `checkEnumAsgInFunctionScopes` (B583) STILL owns, and why it
 * is not a display problem.
 *
 * Round 746 recorded B583 as "blocked on a display rule" — it prints
 * `import("f").DiagnosticCategory` for a module-scoped enum shadowed by a local one, which
 * the general path cannot say. **The ablation says that reading is incomplete.** With B583
 * PassLab-disabled, the relation reproduces SIX of `enumAssignmentCompat6`'s eight
 * diagnostics byte-for-byte (the namespace-vs-namespace half, pinned below), and for the
 * remaining two it emits NOTHING AT ALL — so the display is the SECOND blocker, behind a
 * resolution one.
 *
 * The first blocker is B83.5, in TYPE position: an `enum` declared inside a function or
 * arrow body is never conventionally bound, so its name is invisible to
 * `getTypeFromTypeReference`. Measured, and the two failure modes differ:
 *  - a UNIQUE name resolves to nothing, so the annotation degrades to `any` and every
 *    check on it goes silent (no TS2304 either — the INV.4(c)(iii) family finds the name
 *    through the INV.2(c) lexical scopes, which the type resolver does not consult);
 *  - a name that SHADOWS an outer one resolves to the OUTER symbol, which is worse than
 *    nothing: `let b: DC = 0` inside the shadowing body is judged against the outer enum's
 *    value domain.
 * B583 sidesteps both by collecting the inner enums from the AST itself (`eafsScanIife`),
 * which is exactly why it is still the only pass that can answer here.
 *
 * Measured ownership, so the next agent does not re-derive it: over the six generated
 * classes that carry every TS2416/enum baseline (3,904 tests), B583 PassLab-disabled fails
 * exactly ONE test — `enumAssignmentCompat6` — and inside it only the two `f.ts` lines.
 *
 * Both pins pass today. The first is a REGRESSION GUARD for the half the relation already
 * owns; the second is the retirement target, landed early so that whoever closes the
 * resolution gap has the byte-exact goal in hand.
 */
class EnumShadowedInFunctionScopeTest {

    /**
     * The half the relation reproduces: two namespace-scoped enums of the same simple name,
     * assigned to each other inside a function body. Measured byte-identical with B583
     * ablated — message, chain and position.
     */
    @Test
    fun `two same named namespace enums assigned in a function body carry tscs qualified chain`() {
        val diagnostics = diagnose(
            """
            namespace numerics { export enum DiagnosticCategory { Warning, Error } }
            namespace strings { export enum DiagnosticCategory { Warning = "Warning", Error = "Error" } }

            function f(x: numerics.DiagnosticCategory, y: strings.DiagnosticCategory) {
                x = y;
            }
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 1)
        assert(
            diagnostics[0].message ==
                "Type 'strings.DiagnosticCategory' is not assignable to type 'numerics.DiagnosticCategory'.",
        )
        assert(
            diagnostics[0].messageChain == listOf(
                "  Each declaration of 'DiagnosticCategory.Warning' differs in its value, " +
                    "where '0' was expected but '\"Warning\"' was given.",
            ),
        )
    }

    /**
     * The retirement target. A module-scoped enum shadowed by one declared inside an IIFE:
     * tsc names the outer one `import("f").DC`, because at the error position its bare name
     * belongs to the inner enum. Owned by B583 today; the general path is silent here until
     * a function-body enum declaration becomes resolvable in type position.
     */
    @Test
    fun `an enum shadowed inside an IIFE reports both directions with the import display`() {
        val diagnostics = diagnose(
            """
            export enum DC { Warning, Error }

            export let x: DC;

            (() => {
                enum DC { Warning = "Warning", Error = "Error" }
                function f(y: DC) {
                    x = y;
                    y = x;
                }
            })()
            """,
            fileName = "f.ts",
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 2)
        assert(diagnostics[0].message == "Type 'DC' is not assignable to type 'import(\"f\").DC'.")
        assert(
            diagnostics[0].messageChain == listOf(
                "  Each declaration of 'DC.Warning' differs in its value, " +
                    "where '0' was expected but '\"Warning\"' was given.",
            ),
        )
        assert(diagnostics[1].message == "Type 'import(\"f\").DC' is not assignable to type 'DC'.")
        assert(
            diagnostics[1].messageChain == listOf(
                "  Each declaration of 'DC.Warning' differs in its value, " +
                    "where '\"Warning\"' was expected but '0' was given.",
            ),
        )
    }
}
