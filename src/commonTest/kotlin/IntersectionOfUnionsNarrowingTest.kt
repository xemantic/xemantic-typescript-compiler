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
 * (REL.4)(a) cause 6, round 777 — a type-guard chain over an INTERSECTION OF UNIONS.
 *
 * tsc normalizes `(A | B) & (C | D)` into a top-level UNION of pairwise intersections
 * at construction and drops the combinations whose discriminant properties are disjoint
 * unit types, so flow narrowing has a member list to peel and an exhaustive guard chain
 * reaches `never`. We store intersections un-distributed, so the same chain fell into the
 * single-type narrowing path, where one guard can only answer "the whole intersection" or
 * `never` — and `Debug.assertNever(node)` at the tail of tsc's own
 * `replaceDecoratorsAndModifiers` (nodeFactory.ts:7112, subject
 * `HasModifiers & HasDecorators`) therefore saw the declared type.
 *
 * The shapes below are that site's, reduced: `HasDecorators` is a strict subset of
 * `HasModifiers`, so the reduced union is exactly `HasDecorators` and the chain's arms
 * are exactly its members.
 *
 * READ THE NEGATIVE, NOT THE POSITIVE: `never` is assignable to everything, so a
 * reference washed to `never` is silent at almost every use. Every probe here assigns
 * to `string` and reads the narrowed type off the TS2322/TS2345 message, which is the
 * only target that distinguishes "narrowed to never" (silent) from "narrowed to one
 * member" (names the member) from "not narrowed at all" (names the intersection).
 *
 * AND THE PROBE IS AN ASSIGNMENT, NOT A CALL ARGUMENT, WHICH IS NOT INTERCHANGEABLE
 * (measured round 777): flow narrowing is opt-in per emission site, and the argument
 * gate performs the flow read only on its special arms — a `never` parameter (round
 * 441/462/766) and a bare-enum argument (round 764). A `declare function take(s:
 * string)` probe therefore reports the DECLARED type whatever the narrowing did, and
 * five pins written that way passed identically on a fixed and an ablated build.
 * `const probe: string = node` reads the real flow type. The single argument-position
 * assertion below is the `assertNever` one, i.e. exactly the arm that does read it.
 */
class IntersectionOfUnionsNarrowingTest {

    private val prelude = """
        declare function assertNever(value: never, message?: string): never;
        const enum SyntaxKind { Param, Prop, Method, GetAcc, SetAcc, ClassExpr, ClassDecl, Fn, Iface }
        interface Node { readonly kind: SyntaxKind; }
        interface ParameterDeclaration extends Node { readonly kind: SyntaxKind.Param; readonly p: string; }
        interface PropertyDeclaration extends Node { readonly kind: SyntaxKind.Prop; readonly pd: string; }
        interface MethodDeclaration extends Node { readonly kind: SyntaxKind.Method; readonly m: string; }
        interface GetAccessorDeclaration extends Node { readonly kind: SyntaxKind.GetAcc; readonly g: string; }
        interface SetAccessorDeclaration extends Node { readonly kind: SyntaxKind.SetAcc; readonly s: string; }
        interface ClassExpression extends Node { readonly kind: SyntaxKind.ClassExpr; readonly ce: string; }
        interface ClassDeclaration extends Node { readonly kind: SyntaxKind.ClassDecl; readonly cd: string; }
        interface FunctionDeclaration extends Node { readonly kind: SyntaxKind.Fn; readonly f: string; }
        interface InterfaceDeclaration extends Node { readonly kind: SyntaxKind.Iface; readonly i: string; }
        type HasModifiers =
            | ParameterDeclaration
            | PropertyDeclaration
            | MethodDeclaration
            | GetAccessorDeclaration
            | SetAccessorDeclaration
            | ClassExpression
            | ClassDeclaration
            | FunctionDeclaration
            | InterfaceDeclaration;
        type HasDecorators =
            | ParameterDeclaration
            | PropertyDeclaration
            | MethodDeclaration
            | GetAccessorDeclaration
            | SetAccessorDeclaration
            | ClassExpression
            | ClassDeclaration;
        declare function isParameter(node: Node): node is ParameterDeclaration;
        declare function isPropertyDeclaration(node: Node): node is PropertyDeclaration;
        declare function isMethodDeclaration(node: Node): node is MethodDeclaration;
        declare function isGetAccessorDeclaration(node: Node): node is GetAccessorDeclaration;
        declare function isSetAccessorDeclaration(node: Node): node is SetAccessorDeclaration;
        declare function isClassExpression(node: Node): node is ClassExpression;
        declare function isClassDeclaration(node: Node): node is ClassDeclaration;

    """.trimIndent()

    /** The site itself: tsc's `replaceDecoratorsAndModifiers`, arm for arm. */
    @Test
    fun `a type-guard ternary chain exhausting an intersection of unions reaches never`() {
        diagnose(
            prelude + """
            function replaceDecoratorsAndModifiers(node: HasModifiers & HasDecorators) {
                return isParameter(node) ? node.p :
                    isPropertyDeclaration(node) ? node.pd :
                    isMethodDeclaration(node) ? node.m :
                    isGetAccessorDeclaration(node) ? node.g :
                    isSetAccessorDeclaration(node) ? node.s :
                    isClassExpression(node) ? node.ce :
                    isClassDeclaration(node) ? node.cd :
                    assertNever(node);
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2345 })
        }
    }

    /** The same exhaustion written as sequential early-return guards. */
    @Test
    fun `sequential guards exhausting an intersection of unions reach never`() {
        diagnose(
            prelude + """
            function f(node: HasModifiers & HasDecorators) {
                if (isParameter(node)) return;
                if (isPropertyDeclaration(node)) return;
                if (isMethodDeclaration(node)) return;
                if (isGetAccessorDeclaration(node)) return;
                if (isSetAccessorDeclaration(node)) return;
                if (isClassExpression(node)) return;
                if (isClassDeclaration(node)) return;
                const probe: string = node;
                return probe;
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2322 })
        }
    }

    /**
     * The sharp half: ONE guard short of exhaustion must name EXACTLY the surviving
     * member. A build that does not distribute names the whole intersection here; a
     * build that over-narrows is silent. Only a correct subtraction says
     * `ClassDeclaration`.
     */
    @Test
    fun `one guard short of exhaustion leaves exactly the remaining member`() {
        diagnose(
            prelude + """
            function f(node: HasModifiers & HasDecorators) {
                if (isParameter(node)) return;
                if (isPropertyDeclaration(node)) return;
                if (isMethodDeclaration(node)) return;
                if (isGetAccessorDeclaration(node)) return;
                if (isSetAccessorDeclaration(node)) return;
                if (isClassExpression(node)) return;
                const probe: string = node;
                return probe;
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("'ClassDeclaration' is not assignable") })
        }
    }

    /**
     * The reduction that makes the distribution correct: only the combinations whose
     * `.kind` discriminants agree survive. `HasDecorators` members that are NOT in the
     * second operand must be gone, so a guard for one of them subtracts nothing and the
     * two remaining members are still reachable.
     */
    @Test
    fun `the distributed view keeps only the combinations whose discriminants agree`() {
        diagnose(
            prelude + """
            type Left = ParameterDeclaration | PropertyDeclaration | MethodDeclaration;
            type Right = PropertyDeclaration | MethodDeclaration | ClassDeclaration;
            function f(node: Left & Right) {
                if (isPropertyDeclaration(node)) return;
                if (isMethodDeclaration(node)) return;
                const probe: string = node;
                return probe;
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2322 })
        }
    }

    /** The same overlap, one guard short — the survivor is named, not washed away. */
    @Test
    fun `an overlapping intersection one guard short names the surviving member`() {
        diagnose(
            prelude + """
            type Left = ParameterDeclaration | PropertyDeclaration | MethodDeclaration;
            type Right = PropertyDeclaration | MethodDeclaration | ClassDeclaration;
            function f(node: Left & Right) {
                if (isPropertyDeclaration(node)) return;
                const probe: string = node;
                return probe;
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("'MethodDeclaration' is not assignable") })
        }
    }

    /**
     * Regression guard, and it must hold BEFORE and after: an intersection subject that
     * no guard has touched keeps its own identity and DISPLAY. The distributed form is
     * a view adopted only where it subtracts something, so a program that never narrows
     * must be unable to tell it exists.
     */
    @Test
    fun `an untouched intersection subject still displays as the intersection`() {
        diagnose(
            prelude + """
            function f(node: HasModifiers & HasDecorators) {
                const probe: string = node;
                return probe;
            }
            """.trimIndent(),
        ) should {
            have(
                any {
                    it.code == 2322 && it.message.contains("'HasModifiers & HasDecorators' is not assignable")
                },
            )
        }
    }

    /**
     * Regression guard: an intersection with NO union operand — tsc's
     * `CompilerOptions & { types: string[] }` shape — does not distribute at all and its
     * narrowing is untouched. Holds before and after.
     */
    @Test
    fun `negative control - an intersection with no union operand is not distributed`() {
        diagnose(
            prelude + """
            interface Brand { readonly brand: string; }
            function f(node: ParameterDeclaration & Brand) {
                if (isPropertyDeclaration(node)) return;
                const probe: string = node;
                return probe;
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("'ParameterDeclaration & Brand' is not assignable") })
        }
    }

    /**
     * The intersection has union operands but the guard is for a member the reduced
     * view does not contain, so nothing may be subtracted: the whole intersection must
     * survive, named as itself.
     */
    @Test
    fun `a guard for an absent member subtracts nothing from the distributed view`() {
        diagnose(
            prelude + """
            type Left = ParameterDeclaration | PropertyDeclaration;
            type Right = ParameterDeclaration | PropertyDeclaration | MethodDeclaration;
            declare function isFunctionDeclaration(node: Node): node is FunctionDeclaration;
            function f(node: Left & Right) {
                if (isFunctionDeclaration(node)) return;
                const probe: string = node;
                return probe;
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("'Left & Right' is not assignable") })
        }
    }
}
