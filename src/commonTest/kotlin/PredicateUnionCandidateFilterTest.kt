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
 * Round 727: a second type guard applied to an ALREADY-NARROWED (non-union)
 * reference must FILTER the guard target's union constituents against the
 * current type, not hand back the whole candidate union.
 *
 * tsc's `getNarrowedType` never compares a value against the candidate as a
 * whole — it `mapType`s over the candidate and keeps, per constituent `c`,
 * whichever of the current type / `c` is the subtype, dropping `c` when
 * neither direction relates. Our single-type positive branch fell straight
 * through to the whole candidate, so after
 * `isAssignmentExpression(node) && isNamedEvaluation(node, …)` every later
 * `node.left` / `node.right` / `node.operatorToken` resolved on a 9-member
 * union of unrelated `X & { … }` intersections and FP'd TS2339 (tsc
 * transformers/esDecorators.ts `visitAssignmentElement`, four sites).
 */
class PredicateUnionCandidateFilterTest {

    private val shape = """
        interface Node { kind: string }
        interface Ident extends Node { kind: "id"; text: string }
        interface PropAssign extends Node { kind: "pa"; name: Ident; initializer: Node }
        interface VarDecl extends Node { kind: "vd"; name: Ident; initializer: Node }
        interface Assign extends Node { kind: "bin"; left: Node; right: Node; op: string }
        type Elem = PropAssign | VarDecl | Assign;
        type Named =
            | PropAssign & { readonly name: Ident; readonly initializer: Node }
            | VarDecl & { readonly name: Ident; readonly initializer: Node }
            | Assign & { readonly left: Ident; readonly right: Node };
        declare function isAssign(n: Node): n is Assign;
        declare function isNamed(n: Node): n is Named;
    """.trimIndent()

    @Test
    fun `a union guard target narrows to the constituents related to the already-narrowed reference`() {
        diagnose(
            shape + """

            function visitAssignmentElement(node: Elem): void {
                if (isAssign(node)) {
                    if (isNamed(node)) {
                        const r = node.right;
                        const t = node.left.text;
                    }
                    const l = node.left;
                    const o = node.op;
                }
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `the surviving constituents are only the related ones - a dropped constituent's property still fails`() {
        // `PropAssign` cannot survive the `isAssign` narrowing, so `.op` (which it
        // lacks) must become legal; `.extra` lives on only ONE of the two surviving
        // constituents, so it must stay an error. Before the fix BOTH failed.
        diagnose(
            """
            interface Node { kind: string }
            interface PropAssign extends Node { kind: "pa"; name: string; initializer: Node }
            interface Assign extends Node { kind: "bin"; left: Node; right: Node; op: string }
            interface SubA extends Assign { extra: number }
            interface SubB extends Assign { other: number }
            type Elem = PropAssign | Assign;
            declare function isAssign(n: Node): n is Assign;
            declare function isNamed(n: Node): n is PropAssign | SubA | SubB;

            function f(node: Elem): void {
                if (isAssign(node)) {
                    if (isNamed(node)) {
                        const ok = node.op;
                        const bad = node.extra;
                    }
                }
            }
            """,
        ) should {
            have(any { it.code == 2339 && it.message.contains("'extra'") })
            have(none { it.code == 2339 && it.message.contains("'op'") })
        }
    }

    @Test
    fun `negative control - without a prior narrowing the whole candidate union survives`() {
        diagnose(
            shape + """

            function f(node: Elem): void {
                if (isNamed(node)) {
                    const r = node.right;
                }
            }
            """,
        ) should {
            have(any { it.code == 2339 && it.message.contains("'right'") })
        }
    }

    @Test
    fun `negative control - a candidate unrelated in both directions is still not narrowed away to nothing`() {
        // No constituent of `Other` relates to `Assign` in either direction, so the
        // filter keeps nothing and the whole-candidate fallback must remain — the
        // reference stays usable rather than collapsing to `never`.
        diagnose(
            """
            interface Node { kind: string }
            interface Assign extends Node { kind: "bin"; left: Node; op: string }
            interface Foo extends Node { kind: "foo"; foo: number }
            interface Bar extends Node { kind: "bar"; bar: number }
            type Elem = Foo | Assign;
            declare function isAssign(n: Node): n is Assign;
            declare function isOther(n: Node): n is Foo | Bar;

            function f(node: Elem): void {
                if (isAssign(node)) {
                    if (isOther(node)) {
                        const k = node.kind;
                    }
                }
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }
}
