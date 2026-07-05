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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M3.4 (self-compile burn-down): a user type-guard `x is C` narrowing a union whose members are
 * SUPERTYPES of `C` (`c <: member`) must narrow DOWN to `C`, not collapse to `never`. tsc's
 * `getNarrowedType` (assumeTrue) does `m <: c ? m : c <: m ? c : never` per constituent; our
 * `narrowByCallPredicate` positive branch only kept `m <: c`, so `Expression | PropertyName`
 * narrowed by `is TaggedTemplateExpression` became `never` and a member access FP'd TS2339 on
 * `never`. tsc's own `parser.ts` (`isTaggedTemplateExpression(node)` on `Expression |
 * PropertyName`) and `checker.ts` rely on this.
 */
class TypeGuardUnionNarrowNoNeverTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `guard target is a subtype of a union member - narrows down, not never`() {
        val d = diags(
            """
            interface Node { kind: number; }
            interface Expression extends Node { _expressionBrand: any; }
            interface PropertyName extends Node { _nameBrand: any; }
            interface TaggedTemplate extends Expression { template: string; }
            declare function isTaggedTemplate(n: Expression | PropertyName): n is TaggedTemplate;

            export function f(node: Expression | PropertyName): string {
                if (isTaggedTemplate(node)) {
                    return node.template; // node : TaggedTemplate (not never)
                }
                return "";
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2339 },
            "the guard must narrow `node` to TaggedTemplate (not never) so `node.template` " +
                "resolves; got: " + d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `deep multi-level chain - narrows down through inheritance`() {
        // Mirrors the real depth: TaggedTemplateExpression is ~6 interfaces below Expression.
        val d = diags(
            """
            interface Node { kind: number; }
            interface Expression extends Node { _e: any; }
            interface UnaryExpression extends Expression { _u: any; }
            interface UpdateExpression extends UnaryExpression { _up: any; }
            interface LeftHandSideExpression extends UpdateExpression { _l: any; }
            interface MemberExpression extends LeftHandSideExpression { _m: any; }
            interface PrimaryExpression extends MemberExpression { _p: any; }
            interface TaggedTemplate extends MemberExpression { template: string; }
            interface PropertyName extends Node { _n: any; }
            declare function isTagged(n: Expression | PropertyName): n is TaggedTemplate;

            export function f(node: Expression | PropertyName): string {
                if (isTagged(node)) { return node.template; }
                return "";
            }
            """,
        )
        assertTrue(d.none { it.code == 2339 }, "got: " + d.joinToString { "TS${it.code}: ${it.message}" })
    }

    @Test
    fun `member already a subtype is kept as-is`() {
        // `A | B` narrowed by `is A` keeps A (m <: c), drops B — a property on A resolves.
        val d = diags(
            """
            interface Base { k: number; }
            interface A extends Base { a: string; }
            interface B extends Base { b: number; }
            declare function isA(x: A | B): x is A;
            export function f(x: A | B): string {
                if (isA(x)) { return x.a; }
                return "";
            }
            """,
        )
        assertTrue(d.none { it.code == 2339 }, "got: " + d.joinToString { "TS${it.code}: ${it.message}" })
    }

    @Test
    fun `unrelated guard target still narrows to nothing usable - negative control`() {
        // If the guard target is unrelated to every member, the narrowed value is never and a
        // member access on it is still an error (tsc behaves the same).
        val d = diags(
            """
            interface A { a: string; }
            interface B { b: number; }
            interface C { c: boolean; }
            declare function isC(x: A | B): x is C & (A | B);
            export function f(x: A | B): void {
                if (isC(x)) { const q = x.nonexistent; }
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2339 },
            "a genuinely-absent property in the narrowed branch must still fire TS2339; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
