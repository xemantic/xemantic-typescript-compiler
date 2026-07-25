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

    @Test
    fun assertsInferredTpWithNonNullishConstraintChainNarrows() {
        diagnose(
            prelude + """
            declare namespace Debug {
                export function assertNode<T extends Nd, U extends T>(node: T | undefined, test: (node: T) => node is U): asserts node is U;
            }
            declare function isIdent(node: Nd): node is Ident;
            export function f(node: FnDecl): string {
                Debug.assertNode(node.name, isIdent);
                return node.name.escapedText;
            }
            """
        ) should {
            have(none { it.code == 18048 })
        }
    }

    @Test
    fun overloadedAssertWithAnnotationLessImplNarrows() {
        // The real Debug.assertNode shape: an overload cluster whose
        // valueDeclaration is the annotation-less impl — the resolver must
        // prefer the TypePredicate-bearing overload, else every consumer bails
        // before narrowing.
        diagnose(
            prelude + """
            declare namespace Debug {
                export function assertNode<T extends Nd, U extends T>(node: T | undefined, test: (node: T) => node is U): asserts node is U;
                export function assertNode(node: Nd | undefined, test: ((node: Nd) => boolean) | undefined): void;
            }
            declare function isIdent(node: Nd): node is Ident;
            export function f(node: FnDecl): string {
                Debug.assertNode(node.name, isIdent);
                return node.name.escapedText;
            }
            """
        ) should {
            have(none { it.code == 18048 })
        }
    }

    @Test
    fun testArgInfersPredicateTargetPrecisely() {
        // The inferred U must come from the TEST argument's own predicate
        // (`isIdent: node is Ident`), narrowing a union receiver down to Ident —
        // the constraint-chain minimal claim alone (drop-nullish) would leave
        // `Ident | StringLit` and trade the TS18048 for a TS2339 on escapedText.
        diagnose(
            prelude + """
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
            """
        ) should {
            have(none { it.code == 18048 || it.code == 2339 })
        }
    }

    @Test
    fun assertsUnconstrainedTpStillFires() {
        diagnose(
            prelude + """
            declare namespace Dbg {
                export function assertThing<V>(x: V | undefined): asserts x is V;
            }
            export function g(node: FnDecl): string {
                Dbg.assertThing(node.name);
                return node.name.escapedText;
            }
            """
        ) should {
            have(any { it.code == 18048 })
        }
    }

    @Test
    fun noAssertStillFires() {
        diagnose(
            prelude + """
            export function h(node: FnDecl): string {
                return node.name.escapedText;
            }
            """
        ) should {
            have(any { it.code == 18048 })
        }
    }
}
