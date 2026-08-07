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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * M1.12: the TS2774 uncalled-function check types a `let X = <bareIdent>` initializer by
 * resolving `<bareIdent>` — but the check's syntactic pass sets up no local param scope, so
 * `getTypeOfExpression(<bareIdent>)` resolves in FILE/GLOBAL scope and can find an OUTER
 * binding that this scope's parameter shadows. tsc's own `checker.ts` trips exactly this:
 * `function signaturesRelatedTo(…, reportErrors: boolean, …) { … let shouldElaborateErrors =
 * reportErrors; … if (shouldElaborateErrors) … }` — the boolean param `reportErrors` was
 * mis-resolved to a callable and `if (shouldElaborateErrors)` FP'd TS2774.
 *
 * The fix takes the type from the uncalled-scope's OWN knowledge of the binding (the boolean
 * param, or null for an untyped one) rather than the unreliable global resolution, for BOTH
 * the same-scope case (`shadowed`/`into`) and an enclosing scope already on the stack
 * (`isUncalledShadowed`/`lookupUncalledTypedLocal` — a `let X = param` in a nested block). A
 * same-scope *function* alias is still recorded as callable, so a genuine
 * `let f = localFn; if (f)` keeps firing.
 */
class UncalledFunctionParamTypeTest {

    @Test
    fun `let x = booleanParam shadowing an outer function - no TS2774`() {
        // Without the fix, `getTypeOfExpression(reportErrors)` finds the outer `function
        // reportErrors` (a callable) instead of the boolean parameter, so `if (shouldElaborate)`
        // FP'd TS2774. The param's boolean type must win.
        diagnose(
            """
            function reportErrors(): boolean { return true; }
            function related(reportErrors: boolean): void {
                let shouldElaborate = reportErrors;
                shouldElaborate = false;
                if (shouldElaborate) {}
            }
            """,
            directives = "",
        ) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `let x = booleanParam in a NESTED block shadowing an outer function - no TS2774`() {
        // The exact checker.ts shape: the `let` sits in a nested block of the function, so the
        // param `reportErrors` is not in the block's own collected scope but IS in the enclosing
        // function scope already on the uncalled stack (isUncalledShadowed/lookupUncalledTypedLocal).
        diagnose(
            """
            function reportErrors(): boolean { return true; }
            function related(reportErrors: boolean): void {
                if (reportErrors) {
                    let shouldElaborate = reportErrors;
                    if (shouldElaborate) {}
                }
            }
            """,
            directives = "",
        ) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `let x = localFunction still fires TS2774 - negative control - no over-suppression`() {
        // FP-safety boundary: a same-scope binding that is genuinely a FUNCTION is recorded as
        // callable, so aliasing it and testing it in a condition must STILL fire TS2774. This is
        // exactly what distinguishes the fix from a blanket bail on bare-identifier initializers.
        diagnose(
            """
            function related(): void {
                function helper(): boolean { return true; }
                let x = helper;
                if (x) {}
            }
            """,
            directives = "",
        ) should {
            have(any { it.code == 2774 })
        }
    }
}
