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
 * (REL.1)(c): `checkEnumAsgInFunctionScopes` (B583) — RETIRED round 749. This class is now
 * its ablation evidence, and it is worth keeping the two-round history the pins record.
 *
 * Round 746 recorded B583 as "blocked on a display rule". Round 747's ablation corrected
 * that: with B583 PassLab-disabled the relation reproduced SIX of `enumAssignmentCompat6`'s
 * eight diagnostics byte-for-byte (the namespace-vs-namespace half, the first pin below) but
 * emitted NOTHING AT ALL for the other two — so the display was the SECOND blocker, behind a
 * RESOLUTION one. That first blocker was B83.5 in TYPE position: an `enum` declared inside a
 * function or arrow body is never conventionally bound, and a name SHADOWING an outer one
 * resolved to the OUTER symbol — a wrong answer rather than a missing one, which is why the
 * fix could not be a fallback. Round 748 closed it (`lexicalTypeSymbolForNode`), which left
 * only `import("f").DiagnosticCategory`, and round 749 closed that
 * (`enumModuleImportPrefix`) and deleted the walker.
 *
 * Measured ownership at the moment of retirement: over the six generated classes carrying
 * every TS2416/enum baseline (3,904 tests), B583 PassLab-disabled failed exactly ONE test —
 * `enumAssignmentCompat6` — and inside it only the two `f.ts` lines, which the second pin
 * holds byte-exactly. Both pins passed BEFORE the retirement and pass after it: that is the
 * retirement evidence. The pins measuring the new display RULE (shapes the walker could not
 * structurally reach) live in [EnumModuleQualifiedDisplayTest].
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
     * The retirement target, now met. A module-scoped enum shadowed by one declared inside
     * an IIFE: tsc names the outer one `import("f").DC`, because at the error position its
     * bare name belongs to the inner enum. This was B583's exclusive shape; since round 749
     * the identical bytes come from the relation plus [enumModuleImportPrefix], measured by
     * deleting the walker and diffing the whole of `enumAssignmentCompat6`.
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
