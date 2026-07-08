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
 * Type-argument constraint satisfaction (TS2344) through a constraint CHAIN whose intermediate
 * type resolves imprecisely in our engine. Two self-compile FP families:
 *
 *  1. An enum-member-union constraint (`TKind extends JSDocSyntaxKind` where `JSDocSyntaxKind =
 *     SyntaxKind.A | SyntaxKind.B`) collapses to `any` (enum members resolve to `any`), so a
 *     `Token<TKind>` type-arg (Token's param `extends SyntaxKind`) FP'd TS2344 — a DIRECT
 *     `Token<JSDocSyntaxKind>` arg is skipped because the arg itself is `any`, so a TypeParam arg
 *     whose constraint is `any` must be too (parser.ts `parseOptionalTokenJSDoc`, ×2).
 *  2. A UNION type argument / type-parameter DEFAULT (`Visitor<TIn extends Node, TOut extends
 *     Node | undefined = TIn | undefined>`) where a UNION member is a TypeParam whose own
 *     constraint satisfies (`TIn | undefined` vs `Node | undefined`) — the whole-union relation
 *     misses `TIn <: Node | undefined` because we have no TypeParam-source-via-constraint rule
 *     (types.ts `Visitor`, ×1).
 */
class GenericConstraintChainTs2344Test {

    @Test
    fun `enum-member-union constraint satisfies the enum-typed callee constraint - no TS2344`() {
        diagnose(
            """
            enum SK { A, B, C, D }
            type JSDocSK = SK.A | SK.B;
            type Token<TK extends SK> = { kind: TK };
            function g<TK extends JSDocSK>(t: TK): Token<TK> { return { kind: t }; }
            """,
            directives = "",
        ) should {
            have(none { it.code == 2344 })
        }
    }

    @Test
    fun `union type-arg with a TypeParam member whose constraint satisfies - no TS2344`() {
        diagnose(
            """
            type MyNode = { k: number };
            type Wrap<T extends MyNode | undefined> = { v: T };
            function baz<TIn extends MyNode>(): Wrap<TIn | undefined> { return null as any; }
            """,
            directives = "",
        ) should {
            have(none { it.code == 2344 })
        }
    }

    @Test
    fun `union type-parameter DEFAULT with a TypeParam member whose constraint satisfies - no TS2344`() {
        // `TOut`'s default `TIn | undefined` must satisfy `TOut`'s constraint `MyNode | undefined`
        // — the default-validation path (checkTpListDefaults) needs the same union handling.
        diagnose(
            """
            type MyNode = { k: number };
            type Visitor<TIn extends MyNode = MyNode, TOut extends MyNode | undefined = TIn | undefined> =
                (n: TIn) => TOut;
            """,
            directives = "",
        ) should {
            have(none { it.code == 2344 })
        }
    }

    @Test
    fun `negative control - union type-arg whose member constraint does NOT satisfy - TS2344 fires`() {
        // `Other` is unrelated to `MyNode`, so `TIn | undefined` (TIn extends Other) does not
        // satisfy `MyNode | undefined` — the union skip must not fire and TS2344 must be emitted.
        diagnose(
            """
            type MyNode = { k: number };
            type Other = { o: string };
            type Wrap<T extends MyNode | undefined> = { v: T };
            function baz<TIn extends Other>(): Wrap<TIn | undefined> { return null as any; }
            """,
            directives = "",
        ) should {
            have(any { it.code == 2344 })
        }
    }

    @Test
    fun `negative control - bare union with an unrelated member - TS2344 fires`() {
        // `string` is not assignable to `MyNode | undefined`, so the union skip must not fire.
        diagnose(
            """
            type MyNode = { k: number };
            type Wrap<T extends MyNode | undefined> = { v: T };
            type Bad = Wrap<MyNode | string>;
            """,
            directives = "",
        ) should {
            have(any { it.code == 2344 })
        }
    }
}
