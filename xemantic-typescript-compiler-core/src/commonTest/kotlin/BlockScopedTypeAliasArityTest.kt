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
 * (CHK.19) round 945 — **a type alias declared inside a function body SHADOWS an outer or lib
 * one, and the TS2314 arity check now asks it.**
 *
 * CLAUDE.md's B83.5: `Binder.bindStatement` never binds a declaration nested in a function
 * body, so `function f() { type Omit<T> = … }` is in no `locals` table. `getTypeParamInfo` is
 * a whole-program, NAME-keyed scan with no node context, so it answered with the LIB's
 * two-parameter `Omit` and a one-argument use drew
 * `Generic type 'Omit' requires 2 type argument(s)` — `conditionalTypes1` line 297.
 *
 * The consult is round 748's `lexicalTypeSymbolForNode` shape one declaration kind over:
 * a name gate computed once, then an ancestor walk over the INV.2(c) `lexicalScopes`
 * reading `scope.symbols` ONLY — never `LexicalScope.existing`, which aliases the main
 * binder's table. Because `declareLexical` skips any name the main binder already bound in
 * that container, a scope-space hit can only be a declaration the conventional tables do not
 * have, so this cannot change how any bound name resolves — which is what keeps it out of the
 * INV.3 minefield.
 *
 * B83.5's other half is pinned here too: the SILENT variant. A body-scoped alias whose name
 * shadows NOTHING was always accepted (it degrades to `any`), so only the shadowing half was
 * ever a diagnostic.
 */
class BlockScopedTypeAliasArityTest {

    @Test
    fun `a function-body type alias shadows the lib one of the same name`() {
        diagnose("""
            function f50() {
                type Omit<T extends object> = { [K in keyof T]: T[K] };
                type A = Omit<{ a: void; b: never }>;
            }
        """) should {
            have(none { it.code == 2314 })
            have(none { it.code == 2707 })
        }
    }

    @Test
    fun `the lib arity still applies OUTSIDE the body that shadows it`() {
        diagnose("""
            function f50() {
                type Omit<T extends object> = { [K in keyof T]: T[K] };
                type A = Omit<{ a: void }>;
            }
            type Outside = Omit<{ a: 1; b: 2 }>;
        """) should {
            have(any { it.code == 2314 })
        }
    }

    @Test
    fun `a body-scoped alias's OWN arity is enforced`() {
        diagnose("""
            function f() {
                type Pair<A, B> = [A, B];
                type Bad = Pair<string>;
            }
        """) should {
            have(any { it.code == 2314 })
        }
    }

    @Test
    fun `a body-scoped alias used with its own correct arity is accepted`() {
        diagnose("""
            function f() {
                type Pair<A, B> = [A, B];
                type Good = Pair<string, number>;
            }
        """) should {
            have(none { it.code == 2314 })
            have(none { it.code == 2707 })
        }
    }

    @Test
    fun `two sibling bodies each answer with their OWN declaration`() {
        diagnose("""
            function a() {
                type Loc<X> = X;
                type Ok = Loc<string>;
            }
            function b() {
                type Loc<X, Y> = [X, Y];
                type AlsoOk = Loc<string, number>;
            }
        """) should {
            have(none { it.code == 2314 })
        }
    }

    // ---- B83.5's silent half, and the controls -----------------------------------------

    @Test
    fun `a body-scoped alias that shadows nothing was never a diagnostic`() {
        diagnose("""
            function f() {
                type OnlyHere<T> = T[];
                type Use = OnlyHere<string>;
            }
        """) should {
            have(none { it.code == 2314 })
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `a file-level alias still shadows the lib one`() {
        diagnose("""
            type Omit<T extends object> = { [K in keyof T]: T[K] };
            type A = Omit<{ a: void }>;
        """) should {
            have(none { it.code == 2314 })
        }
    }

    @Test
    fun `the lib generic used with its real arity stays silent`() {
        diagnose("type A = Omit<{ a: 1; b: 2 }, 'a'>;") should {
            have(none { it.code == 2314 })
        }
    }

    @Test
    fun `the lib generic used with too few arguments is still TS2314`() {
        diagnose("type A = Omit<{ a: 1; b: 2 }>;") should {
            have(any { it.code == 2314 })
        }
    }
}
