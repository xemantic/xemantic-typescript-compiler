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
 * (REL.1)(c) step 4, round 748: an `enum` declared inside a function or arrow body
 * is RESOLVABLE IN TYPE POSITION.
 *
 * Such an enum is never conventionally bound (B83.5), so before this round the type
 * resolver had two distinct failure modes, and the SECOND is why the fix could not be
 * a "when the lookup misses" fallback — there is no miss to detect:
 *  - a UNIQUE name resolved to nothing, the annotation degraded to `any`, and every
 *    check on it went silent (with no TS2304 either — the INV.4(c)(iii) family finds
 *    the name through the INV.2(c) lexical scopes, which the type resolver did not
 *    consult);
 *  - a name SHADOWING an outer one resolved to the OUTER symbol, so the annotation was
 *    judged against the wrong enum's value domain — a WRONG answer, not a missing one.
 *
 * The fix is `lexicalTypeSymbolForNode`, consulted FIRST in `resolveTypeNameToSymbol`'s
 * Identifier branch: an innermost-first walk of the node's ancestor chain over the
 * INV.2(c) `lexicalScopes` table of the node's OWNING file, returning the first
 * SCOPE-SPACE (`scope.symbols`, id <= -2) enum symbol. Scope-space only is what makes
 * it containable — the lexical pass declares into that space ONLY where the main binder
 * bound nothing (`declareLexical`'s `existing` skip), so a conventionally-bound name
 * keeps resolving exactly as before, and the innermost-first order IS the shadowing
 * rule for free.
 *
 * MEASURED BLAST RADIUS (round 748, before the fix was written): over the whole corpus
 * only TWO test sources declare a block-scoped enum AND use it in type position
 * (`enumAssignmentCompat6` — the shadowing mode; `unusedLocalsInMethod4` — the silent
 * `any` mode), and the tsc compiler profile has exactly two more, both silent-`any`
 * (`debug.ts`'s `Connection`, `program.ts`'s `SeenPackageName`). Corpus baselines
 * changed: ZERO. Profile: 46, composition unchanged.
 *
 * Three of the four pins below FAIL on a build of unmodified `7ab9b215` — the first two
 * because the diagnostic does not fire at all there, the third because it fires when it
 * must not. The fourth is the deliberate-NON-generalisation control: the other B83.5
 * kinds (class / interface / type alias) stay unresolved, which is a 62x larger change
 * by the same census and is NOT part of this slice.
 */
class FunctionScopedEnumTypePositionTest {

    /**
     * The silent-`any` mode, in the shape `unusedLocalsInMethod4` and both profile sites
     * have: a UNIQUE enum name declared in a function body. Before this round the
     * annotation degraded to `any` and the assignment went unchecked.
     */
    @Test
    fun `a uniquely named enum declared in a function body is checked in type position`() {
        val diagnostics = diagnose(
            """
            function f() {
                enum E { A = "a", B = "b" }
                let x: E = 0;
            }
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 1)
        assert(diagnostics[0].message == "Type '0' is not assignable to type 'E'.")
    }

    /**
     * The shadowing mode, in the direction that was SILENT before: the outer enum's
     * `Warning` is `0`, so resolving to it accepts `0`; the inner one is a string enum
     * and must reject it. Firing here proves the INNER symbol won.
     */
    @Test
    fun `an enum shadowing an outer one resolves to the inner declaration`() {
        val diagnostics = diagnose(
            """
            enum Outer { Warning = 0 }
            function f() {
                enum Outer { Warning = "w" }
                let b: Outer = 0;
            }
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 1)
        assert(diagnostics[0].message == "Type '0' is not assignable to type 'Outer'.")
    }

    /**
     * The same shadowing rule in the OTHER direction, which is the one that proves the
     * fix is a resolution-ORDER change rather than a fallback: the outer enum's
     * `Warning` is `7` and would REJECT `0`, the inner one's is `0` and accepts it.
     * Before this round the outer symbol won and this emitted a spurious TS2322.
     */
    @Test
    fun `an inner enum widens what the outer one would have rejected`() {
        val diagnostics = diagnose(
            """
            enum Outer { Warning = 7 }
            function f() {
                enum Outer { Warning = 0 }
                let b: Outer = 0;
            }
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.isEmpty())
    }

    /**
     * Negative control - the slice is ENUM-ONLY by construction. A block-scoped
     * `interface` is unbound for the same B83.5 reason and stays unresolved, so its
     * annotation still degrades to `any` and the assignment is still unchecked.
     * Generalising to the other B83.5 kinds is a much larger change (round 748's
     * census: 51 corpus files for `interface` alone against 2 for `enum`) and is
     * deliberately not part of this one. Passes on both builds; it exists so that a
     * future widening has to change this pin on purpose.
     */
    @Test
    fun `negative control - a block scoped interface stays unresolved in type position`() {
        val diagnostics = diagnose(
            """
            function f() {
                interface I { a: string }
                let x: I = 0;
            }
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.isEmpty())
    }
}
