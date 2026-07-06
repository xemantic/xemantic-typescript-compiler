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
 * M1.12 (round 419): a UNION whose member is itself an INTERSECTION
 * (`PropertyAccessExpression | (ElementAccessExpression & Declaration & {…})` — tsc's
 * `BindableStaticAccessExpression`) must resolve a property that exists on the intersection arm.
 * `getPropertyOfType` has no Intersection branch and `typeHasOwnProperty` bails on a
 * `Type.Intersection` member, so the whole union access FP'd TS2339 (`.parent`, ~28 sites in
 * binder.ts/utilities.ts). The fix folds an intersection member's constituents in the property
 * resolution AND in the switch/discriminant-narrowing annotation read.
 */
class IntersectionMemberPropertyTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `property inherited by an intersection union-member resolves`() {
        val d = diags(
            """
            interface Node { parent: Node; }
            interface ElementAccessExpression extends Node { argumentExpression: string; }
            interface Declaration { name: string; }
            interface PropertyAccessExpression extends Node { expression: Node; }
            type Bindable = PropertyAccessExpression | (ElementAccessExpression & Declaration & { expression: any });

            export function f(x: Bindable) {
                return x.parent;      // .parent is on PropertyAccessExpression AND the intersection arm (via Node)
            }
            export function g(x: Bindable) {
                return x.expression;  // .expression is on PropertyAccessExpression AND the intersection's {expression:any}
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2339 },
            "a property present on every union member (folding the intersection arm) must resolve; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `switch on kind filters an intersection member with a non-matching discriminant`() {
        val d = diags(
            """
            declare const enum Kind { Import, Export, Binding }
            interface Base { kind: Kind; }
            interface ImportDecl extends Base { kind: Kind.Import; moduleSpecifier: string; }
            interface ExportDecl extends Base { kind: Kind.Export; moduleSpecifier: string; }
            interface BindingPat extends Base { kind: Kind.Binding; name: string; }
            type Node = ImportDecl | ExportDecl | (BindingPat & { parent: object });

            export function getSpec(node: Node): string | undefined {
                switch (node.kind) {
                    case Kind.Import:
                    case Kind.Export:
                        return node.moduleSpecifier; // BindingPat arm filtered → no TS2339
                    case Kind.Binding:
                        return node.name;
                }
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2339 },
            "the intersection member (Kind.Binding) must be filtered from the Import|Export case; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `FP-safety - a property missing on a plain union still fires TS2339`() {
        // The intersection-member fold must not disturb the plain-union missing-property
        // emission (the well-resolved-union elaboration path).
        val d = diags(
            """
            interface A { a: number; }
            interface B { b: number; }
            type U = A | B;

            export function f(x: U) {
                return x.nope; // `nope` on neither A nor B → TS2339 must still fire
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2339 && it.message.contains("nope") },
            "a genuinely-missing property on a plain union must still fire TS2339; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `FP-safety - a property on only SOME union members still fires TS2339`() {
        val d = diags(
            """
            interface A { a: number; shared: string; }
            interface B { b: number; }
            interface C { c: number; }
            type U = A | (B & C);

            export function f(x: U) {
                return x.shared; // on A but NOT on (B & C) → union access still fails
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2339 && it.message.contains("shared") },
            "a property present on only some members must still fire TS2339; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
