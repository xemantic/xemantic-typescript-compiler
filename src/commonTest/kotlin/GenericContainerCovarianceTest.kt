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
 * Round 435e: two coupled relation fixes exposed by tsc's getContainingNodeArray
 * family (`NodeArray<TemplateSpan>` returned where `NodeArray<Node> | undefined`
 * is declared, ×23):
 *
 * 1. The same-target covariant arg-shortcut's RE-ENTRY gate requires the pair
 *    shape on BOTH relation stacks — a UNION-target decomposition re-pushes only
 *    the SOURCE Reference, and the old source-side-only test deferred to
 *    structural comparison, which FPs on Array-method contravariance for any
 *    generic container extending ReadonlyArray. A genuine expanding-cycle
 *    re-entry (Observable-vs-Observable through members) pushes both sides and
 *    still defers (the corpus recursiveTypeComparison/infinitelyExpandingTypes
 *    pins gate that).
 *
 * 2. A BARE `new C()` (no type args, no ctor args) assigned where the target
 *    contains a Reference to the same class C is contextually instantiated from
 *    the target (tsc's own nodeChildren.ts `map = new WeakMap()` against
 *    `WeakMap<Node, readonly Node[] | undefined>`).
 */
class GenericContainerCovarianceTest {

    private fun ts2322s(source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")
            .diagnostics.filter { it.code == 2322 }

    private val nodeArrayDefs = """
        const enum SyntaxKind { Unknown = 0, TemplateSpan = 239 }
        interface ReadonlyTextRange { readonly pos: number; readonly end: number; }
        interface Node2 extends ReadonlyTextRange {
            readonly kind: SyntaxKind;
            readonly flags: number;
            readonly parent: Node2;
        }
        interface TemplateSpan2 extends Node2 {
            readonly kind: SyntaxKind.TemplateSpan;
            readonly expression: string;
        }
        interface NodeArray2<T extends Node2> extends ReadonlyArray<T>, ReadonlyTextRange {
            readonly hasTrailingComma: boolean;
        }
    """.trimIndent()

    /** The getContainingNodeArray shape: covariant container return through a
     *  union target must relate via the same-target arg shortcut. */
    @Test fun covariantContainerThroughUnionTargetIsLegal() {
        val diags = ts2322s(
            """
            $nodeArrayDefs
            declare const spans: NodeArray2<TemplateSpan2>;
            function f(): NodeArray2<Node2> | undefined {
                if (!spans.length) return undefined;
                return spans;
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }

    /** NEGATIVE control: an incompatible element type still fails through the
     *  union target (the shortcut compares args, it does not rubber-stamp). */
    @Test fun incompatibleElementThroughUnionTargetStillFires() {
        val diags = ts2322s(
            """
            $nodeArrayDefs
            interface Unrelated { readonly notANode: string; }
            declare const uns: ReadonlyArray<Unrelated>;
            function g(): ReadonlyArray<Node2> | undefined {
                return uns;
            }
            """.trimIndent()
        )
        assertTrue(diags.isNotEmpty(), "expected TS2322 for the unrelated element type")
    }

    /** The nodeChildren.ts shape: a bare `new WeakMap()` contextually
     *  instantiates from the assignment target. */
    @Test fun bareNewContainerAssignmentIsLegal() {
        val diags = ts2322s(
            """
            interface Node2 { kind: number; }
            declare const cache: WeakMap<Node2, WeakMap<Node2, readonly Node2[] | undefined>>;
            declare const key: Node2;
            function f() {
                let map = cache.get(key);
                if (map === undefined) {
                    map = new WeakMap();
                }
                return map;
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }

    /** NEGATIVE control: a bare `new` of a DIFFERENT class still fails. */
    @Test fun bareNewOfDifferentClassStillFires() {
        val diags = ts2322s(
            """
            class Left<T> { l: T | undefined; }
            class Right<T> { r: T | undefined; }
            let x: Left<number> | undefined;
            x = new Right();
            """.trimIndent()
        )
        assertTrue(diags.isNotEmpty(), "expected TS2322 for the mismatched class")
    }
}
