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
 * Round 438 (M3.4, self-compile burn-down): the call-ARGUMENT guard-narrow-DOWN
 * (round 428b/429c) substitutes a genuine, relation-verified refinement of a
 * non-union interface arg — but was gated to `arg is Identifier`, so a
 * PROPERTY-ACCESS arg like `getExports(node.left)` inside
 * `if (isIdentifier(node.left))` (Expression narrowed DOWN to Identifier) still
 * FP'd TS2345. A PropertyAccess's built-in narrowing only refines UNION receivers,
 * not a non-union interface DOWN to a subtype, so it needs the same explicit narrow
 * as a bare Identifier. tsc's module/system transformers `getExports(node.left)`,
 * checker.ts / binder.ts narrow-then-pass sites.
 */
class CallArgPropertyAccessGuardNarrowTest {

    @Test
    fun `property-access arg narrowed down by a guard is accepted`() {
        diagnose(
            """
            interface Node { kind: number; }
            interface Expression extends Node { _e: any; }
            interface Identifier extends Expression { _id: any; }
            interface BinaryExpression extends Expression { left: Expression; }
            declare function isIdentifier(n: Node): n is Identifier;
            declare function getExports(id: Identifier): void;

            export function f(node: BinaryExpression): void {
                if (isIdentifier(node.left)) {
                    getExports(node.left); // node.left : Expression narrowed to Identifier
                }
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `bare identifier arg still narrows - positive control`() {
        diagnose(
            """
            interface Node { kind: number; }
            interface Expression extends Node { _e: any; }
            interface Identifier extends Expression { _id: any; }
            declare function isIdentifier(n: Node): n is Identifier;
            declare function getExports(id: Identifier): void;

            export function f(x: Expression): void {
                if (isIdentifier(x)) { getExports(x); }
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `unguarded property-access arg still fires - negative control`() {
        diagnose(
            """
            interface Node { kind: number; }
            interface Expression extends Node { _e: any; }
            interface Identifier extends Expression { _id: any; }
            interface BinaryExpression extends Expression { left: Expression; }
            declare function getExports(id: Identifier): void;

            export function f(node: BinaryExpression): void {
                getExports(node.left); // no guard — Expression ≁ Identifier
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
