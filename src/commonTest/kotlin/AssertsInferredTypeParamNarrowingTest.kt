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
 * Round 424 — `asserts node is U` where U is the callee's own INFERRED type
 * param (tsc `Debug.assertNode<T extends Node, U extends T>(node: T |
 * undefined, test: (node: T) => node is U): asserts node is U`, called as
 * `Debug.assertNode(node.name, isIdentifier)` — transformers/ts.ts:2012).
 *
 * U is unresolvable at the call site (no bindings → errorType), but its
 * constraint CHAIN (`U extends T`, `T extends Node`) pins non-nullishness —
 * the assertion at minimum excludes nullish from the asserted path. Minimal
 * claim only; an UNCONSTRAINED type param proves nothing (V could be
 * instantiated `undefined`) and must keep the diagnostic.
 */
class AssertsInferredTypeParamNarrowingTest {

    private val prelude = """
        interface Nd { kind: number; }
        interface Ident extends Nd { escapedText: string; }
        interface FnDecl extends Nd { name?: Ident; }
    """

    private fun diags(body: String): List<Diagnostic> =
        TypeScriptCompiler().compile(
            "// @strict: true\n" + (prelude + body).trimIndent(), "t.ts",
        ).diagnostics

    @Test
    fun assertsInferredTpWithNonNullishConstraintChainNarrows() {
        val d = diags(
            """
            declare namespace Debug {
                export function assertNode<T extends Nd, U extends T>(node: T | undefined, test: (node: T) => node is U): asserts node is U;
            }
            declare function isIdent(node: Nd): node is Ident;
            export function f(node: FnDecl): string {
                Debug.assertNode(node.name, isIdent);
                return node.name.escapedText;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 18048 },
            "asserts-U with constraint chain U -> T -> Nd must exclude nullish; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun overloadedAssertWithAnnotationLessImplNarrows() {
        // The real Debug.assertNode shape: an overload cluster whose
        // valueDeclaration is the annotation-less impl — the resolver must
        // prefer the TypePredicate-bearing overload, else every consumer bails
        // before narrowing.
        val d = diags(
            """
            declare namespace Debug {
                export function assertNode<T extends Nd, U extends T>(node: T | undefined, test: (node: T) => node is U): asserts node is U;
                export function assertNode(node: Nd | undefined, test: ((node: Nd) => boolean) | undefined): void;
            }
            declare function isIdent(node: Nd): node is Ident;
            export function f(node: FnDecl): string {
                Debug.assertNode(node.name, isIdent);
                return node.name.escapedText;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 18048 },
            "an overloaded assert with an annotation-less impl must still narrow; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun testArgInfersPredicateTargetPrecisely() {
        // The inferred U must come from the TEST argument's own predicate
        // (`isIdent: node is Ident`), narrowing a union receiver down to Ident —
        // the constraint-chain minimal claim alone (drop-nullish) would leave
        // `Ident | StringLit` and trade the TS18048 for a TS2339 on escapedText.
        val d = diags(
            """
            interface StringLit extends Nd { text: string; }
            interface ModuleDecl extends Nd { name: Ident | StringLit; }
            declare namespace Debug {
                export function assertNode<T extends Nd, U extends T>(node: T | undefined, test: (node: T) => node is U): asserts node is U;
                export function assertNode(node: Nd | undefined, test: ((node: Nd) => boolean) | undefined): void;
            }
            declare function isIdent(node: Nd): node is Ident;
            export function f(node: FnDecl | ModuleDecl): string {
                Debug.assertNode(node.name, isIdent);
                return node.name.escapedText;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 18048 || it.code == 2339 },
            "test-arg inference must narrow node.name to Ident (no TS18048, no TS2339); got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun assertsUnconstrainedTpStillFires() {
        val d = diags(
            """
            declare namespace Dbg {
                export function assertThing<V>(x: V | undefined): asserts x is V;
            }
            export function g(node: FnDecl): string {
                Dbg.assertThing(node.name);
                return node.name.escapedText;
            }
            """,
        )
        assertTrue(
            d.any { it.code == 18048 },
            "an UNCONSTRAINED asserted type param proves nothing — TS18048 must stand; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun noAssertStillFires() {
        val d = diags(
            """
            export function h(node: FnDecl): string {
                return node.name.escapedText;
            }
            """,
        )
        assertTrue(
            d.any { it.code == 18048 },
            "no assert at all — TS18048 must stand; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
