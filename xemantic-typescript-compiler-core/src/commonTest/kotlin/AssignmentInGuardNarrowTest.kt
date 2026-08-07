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
 * M3.4 (round 416): the tsc idiom `if (!x.y) { x.y = new Map() } x.y.method()` FP'd TS18048 for a
 * PROPERTY-PATH target (es2015.ts `state.labeledNonLocalBreaks`, builder.ts) — the identifier form
 * (`if (!m) { m = new Map() } m.set()`) and the straight-line form (`x.y = new Map(); x.y.m()`)
 * both worked, but the property-path-assign-in-a-branch, joined with the else, did not.
 *
 * Root cause: `narrowByAssignmentRhs`'s non-nullish-RHS branch used
 * `narrowByExcludingNullUndefined(antecedent)`, where the then-branch antecedent is `x.y` already
 * narrowed to bare `undefined` by the `!x.y` guard. Excluding nullish from a NON-union `undefined`
 * returns `undefined` unchanged, so the then-arm re-adds `undefined` at the branch join → FP. But
 * an assignment OVERWRITES the reference (tsc `getAssignmentReducedType(declared, rhsType)`), so the
 * post-state must be the DECLARED type minus nullish, independent of the pre-assignment narrowing.
 * Fixed by basing the exclusion on `declaredType` (straight-line is unaffected — antecedent equals
 * declaredType there).
 */
class AssignmentInGuardNarrowTest {

    @Test
    fun `property-path assign-in-guard block then use - no TS18048`() {
        diagnose(
            """
            interface State { m?: Map<string, string>; }
            export function f(state: State, k: string): void {
                if (!state.m) { state.m = new Map<string, string>(); }
                state.m.set(k, k);
            }
            """
        ) should {
            have(none { it.code == 18048 })
        }
    }

    @Test
    fun `property-path assign-in-guard no braces then use - no TS18048`() {
        diagnose(
            """
            interface State { m?: Map<string, string>; }
            export function f(state: State, k: string): void {
                if (!state.m) state.m = new Map<string, string>();
                state.m.set(k, k);
            }
            """
        ) should {
            have(none { it.code == 18048 })
        }
    }

    @Test
    fun `identifier assign-in-guard still works - no TS18048`() {
        diagnose(
            """
            export function f(m: Map<string, string> | undefined, k: string): void {
                if (!m) { m = new Map<string, string>(); }
                m.set(k, k);
            }
            """
        ) should {
            have(none { it.code == 18048 })
        }
    }

    @Test
    fun `assign to a possibly-undefined value STILL fires - negative control`() {
        // FP-safety: only a DEFINITELY-non-nullish RHS (`new X()` / object / …) overwrites to
        // non-nullish. Assigning a possibly-undefined identifier does not narrow, so the use fires.
        diagnose(
            """
            interface State { m?: Map<string, string>; }
            export function f(state: State, k: string, other: Map<string, string> | undefined): void {
                if (!state.m) { state.m = other; }
                state.m.set(k, k);
            }
            """
        ) should {
            have(any { it.code == 18048 })
        }
    }
}
