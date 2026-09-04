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
 * (REL.4) round 770 — the IMPLICIT no-case-matched edge out of a `default`-less
 * switch.
 *
 * The flow builder joined that edge as a chain of "every case EXPRESSION is falsy"
 * [FlowCondition]s, which is the truth only for the `switch (true) { case <cond>: }`
 * idiom; for a discriminant switch the case expression is a VALUE, so the chain
 * narrowed nothing and the code AFTER an exhaustive `default`-less switch saw the
 * whole declared type. tsc encodes the same edge as a switch-clause flow with an
 * EMPTY clause range and reads `clauseStart === clauseEnd` as "treat as default".
 *
 * The three shapes are tsc's own: `checker.ts:11536`
 * (`resolutionTargetHasProperty`, a bare-enum subject), `checker.ts:37648`
 * (`resolveSignature`, a `.kind`-discriminated union) and
 * `transformers/declarations.ts:1739` (`transformTopLevelDeclaration`, TWO
 * consecutive `default`-less switches whose arms all return).
 */
class PostSwitchFallThroughNarrowingTest {

    @Test
    fun `a bare enum exhausted by a default-less switch is never after it`() {
        diagnose("""
            enum TypeSystemPropertyName { Type, DeclaredType, WriteType }
            declare function assertNever(member: never): never;
            function f(propertyName: TypeSystemPropertyName): boolean {
                switch (propertyName) {
                    case TypeSystemPropertyName.Type: return true;
                    case TypeSystemPropertyName.DeclaredType: return false;
                    case TypeSystemPropertyName.WriteType: return true;
                }
                return assertNever(propertyName);
            }
        """) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a discriminated union exhausted by a default-less switch is never after it`() {
        diagnose("""
            interface CallExpr { kind: "call"; c: number }
            interface NewExpr { kind: "new"; n: number }
            interface TaggedExpr { kind: "tagged"; t: number }
            type CallLikeExpression = CallExpr | NewExpr | TaggedExpr;
            declare function assertNever(node: never, message?: string): never;
            function resolveSignature(node: CallLikeExpression): number {
                switch (node.kind) {
                    case "call": return node.c;
                    case "new": return node.n;
                    case "tagged": return node.t;
                }
                assertNever(node, "should be unreachable");
            }
        """) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `two consecutive default-less switches together exhaust the union`() {
        diagnose("""
            interface ImportDecl { kind: "import"; i: number }
            interface ClassDecl { kind: "class"; c: number }
            interface EnumDecl { kind: "enum"; e: number }
            type Painted = ImportDecl | ClassDecl | EnumDecl;
            declare function assertNever(node: never, message?: string): never;
            function transformTopLevelDeclaration(input: Painted): number {
                switch (input.kind) {
                    case "import": return input.i;
                }
                switch (input.kind) {
                    case "class": return input.c;
                    case "enum": return input.e;
                }
                return assertNever(input, "Unhandled top-level node");
            }
        """) should {
            have(none { it.code == 2345 })
        }
    }

    /**
     * The narrowed type is read off the MESSAGE, not off an assignability verdict:
     * `checkTypeRelatedToCore` still decides enum-vs-MEMBER vacuously — (REL.2)'s
     * open item — so both `takeC(k)` and `takeA(k)` would pass whatever this edge
     * answers. A parameter that cannot be satisfied vacuously is what makes the message
     * name the type the edge actually produced.
     *
     * (PARITY.2): that parameter is `never`, not the `string` it was — since the enum arm
     * of `Checker.baseTypeOfLiteralType` landed, a `string` target generalizes an
     * enum-member source to its parent enum, so `K.C` and an un-narrowed `K` would read
     * the same and this pin would go BLIND. Measured byte-identical to tsgo 7.0.2 and
     * pristine `typescript@6.0.3` at the `never` target.
     */
    @Test
    fun `a partially covering default-less switch subtracts the covered enum members`() {
        diagnose("""
            enum K { A, B, C }
            declare function takeNever(s: never): void;
            function f(k: K) {
                switch (k) {
                    case K.A: return;
                    case K.B: return;
                }
                takeNever(k);
            }
        """) should {
            have(any { it.code == 2345 && it.message.contains("type 'K.C' is not assignable") })
        }
    }

    @Test
    fun `negative control - a default-less switch whose arms break still reaches the whole enum`() {
        diagnose("""
            enum K { A, B }
            declare function assertNever(k: never): never;
            function f(k: K) {
                switch (k) {
                    case K.A: break;
                    case K.B: break;
                }
                assertNever(k);
            }
        """) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a non-exhaustive default-less switch keeps the TS2345`() {
        diagnose("""
            enum K { A, B, C }
            declare function assertNever(k: never): never;
            function f(k: K) {
                switch (k) {
                    case K.A: return;
                    case K.B: return;
                }
                assertNever(k);
            }
        """) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `a default-less typeof switch excludes every case tag after it`() {
        diagnose("""
            declare function takeNumber(x: number): void;
            function f(x: string | number) {
                switch (typeof x) {
                    case "string": return;
                }
                takeNumber(x);
            }
        """) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a switch WITH a default is unaffected by the no-match edge`() {
        diagnose("""
            enum K { A, B }
            declare function assertNever(k: never): never;
            function f(k: K): number {
                switch (k) {
                    case K.A: return 1;
                    case K.B: return 2;
                    default: return assertNever(k);
                }
            }
        """) should {
            have(none { it.code == 2345 })
        }
    }

    /**
     * The read is a PROPERTY ACCESS, not an argument: `never` is assignable to every
     * parameter, so a `takeSquare(shape)` probe passes whether this edge answers the
     * square member or collapses to `never`. Reading `shape.s` is the one probe that
     * fails on the collapse — which is exactly how the no-match edge first broke
     * `narrowByClauseExpressionInSwitchTrue3` (positive narrowing by clause 0).
     */
    @Test
    fun `a switch on true with no default still narrows through the condition chain`() {
        diagnose("""
            interface Circle { kind: "circle"; r: number }
            interface Square { kind: "square"; s: number }
            type Shape = Circle | Square;
            function f(shape: Shape): number {
                switch (true) {
                    case shape.kind === "circle": return shape.r;
                }
                return shape.s;
            }
        """) should {
            have(none { it.code == 2339 })
        }
    }
}
