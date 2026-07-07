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
 */

package com.xemantic.typescript.compiler

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Round 435f: an assignment TARGET whose type contains a FOREIGN type parameter
 * is a local typed from an UN-INFERRED generic call return — tsc infers those
 * TPs at the call site, so checking anything against the raw type is
 * meaningless. Mirrors the round-431e SOURCE gate on the assignment path.
 *
 * tsc's own shape (the visitor family ×15): `let expression = visitNode(node,
 * visitor, isExpression)` types the local as visitNode's raw return
 * `TOut | (TIn & undefined) | (TVisited & undefined)`; the later
 * `expression = factory.createX(…)` reassignment must not check against it.
 */
class ForeignTpAssignmentTargetTest {

    private fun ts2322s(source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")
            .diagnostics.filter { it.code == 2322 }

    /** The visitor shape: reassigning a local typed by an un-inferred generic
     *  call return draws nothing. */
    @Test fun reassignmentAgainstUnInferredGenericReturnIsLegal() {
        val diags = ts2322s(
            """
            interface Node2 { kind: number; }
            interface Expression2 extends Node2 { _e: any; }
            declare function visitNode<TIn extends Node2 | undefined, TVisited extends Node2 | undefined, TOut extends Node2>(
                node: TIn, visitor: (n: Node2) => Node2, test?: (n: Node2) => boolean): TOut | (TIn & undefined) | (TVisited & undefined);
            declare function makeExpr(): Expression2;
            declare const visitor: (n: Node2) => Node2;
            function f(right: Expression2) {
                let expression = visitNode(right, visitor);
                expression = makeExpr();
                return expression;
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }

    /** NEGATIVE control: a CONCRETE-typed target still checks. */
    @Test fun concreteTargetStillFires() {
        val diags = ts2322s(
            """
            function f() {
                let s: string;
                s = 42;
                return s;
            }
            """.trimIndent()
        )
        assertTrue(diags.isNotEmpty(), "expected TS2322 for number -> string")
    }
}
