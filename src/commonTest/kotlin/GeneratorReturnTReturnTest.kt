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
 * Round 435: a GENERATOR's `return expr` is checked against the return
 * annotation's TReturn (tsc getIterationTypeOfGeneratorFunctionReturnType),
 * never against the iterator type itself. tsc's own checker.ts does
 * `function* generateJsxAttributes(node): ElaborationIterator { if (!length(…))
 * return; … }` — the bare `return;` is legal because IterableIterator's TReturn
 * defaults to `any`.
 *
 * The unwrap is AST-side in checkReturnAssignability: a Generator-family
 * reference (Generator/AsyncGenerator/Iterator/IterableIterator/…) with an
 * EXPLICIT 2nd type argument re-targets the whole return check at that node;
 * every other annotation shape (single-arg forms, aliases whose body we cannot
 * see) has TReturn = any → the return check is skipped.
 */
class GeneratorReturnTReturnTest {

    private fun ts2322s(source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")
            .diagnostics.filter { it.code == 2322 }

    /** Bare `return;` in a generator annotated with a single-arg IterableIterator
     *  (TReturn = any) draws nothing — the tsc checker.ts ElaborationIterator shape. */
    @Test fun bareReturnInGeneratorWithSingleArgIteratorAnnotationIsLegal() {
        val diags = ts2322s(
            """
            type ElabIter = IterableIterator<{ a: number }>;
            function* gen(items: number[]): ElabIter {
                if (!items.length) return;
                for (const i of items) yield { a: i };
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }

    /** `return undefined;` through an ALIAS annotation (TReturn invisible → any). */
    @Test fun returnUndefinedInGeneratorWithAliasAnnotationIsLegal() {
        val diags = ts2322s(
            """
            type ElabIter = IterableIterator<{ a: number }>;
            function* gen(items: number[]): ElabIter {
                if (!items.length) return undefined;
                yield { a: 1 };
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }

    /** Generator METHOD bodies get the same rule (the isGenerator flag threads
     *  through the MethodDeclaration checkFunctionBody site). */
    @Test fun bareReturnInGeneratorMethodIsLegal() {
        val diags = ts2322s(
            """
            class C {
                *gen(items: number[]): IterableIterator<number> {
                    if (!items.length) return;
                    yield 1;
                }
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }

    /** NEGATIVE control: an explicit TReturn still checks — `return 42` against
     *  Generator<Y, string, N> fires TS2322 number→string through the unwrap. */
    @Test fun explicitTReturnMismatchStillFires() {
        val diags = ts2322s(
            """
            function* gen(): Generator<{ a: number }, string, unknown> {
                yield { a: 1 };
                return 42;
            }
            """.trimIndent()
        )
        assertTrue(
            diags.any { it.message == "Type 'number' is not assignable to type 'string'." },
            "expected TS2322 number->string via the TReturn unwrap, got: $diags"
        )
    }

    /** NEGATIVE control: a matching explicit TReturn stays clean. */
    @Test fun explicitTReturnMatchIsLegal() {
        val diags = ts2322s(
            """
            function* gen(): Generator<{ a: number }, string, unknown> {
                yield { a: 1 };
                return "done";
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }

    /** NEGATIVE control: a NON-generator's `return undefined` against a concrete
     *  annotation still fires — the gate is generator-scoped. */
    @Test fun nonGeneratorReturnUndefinedStillFires() {
        val diags = ts2322s(
            """
            function f(): { a: number } {
                return undefined;
            }
            """.trimIndent()
        )
        assertTrue(diags.isNotEmpty(), "expected TS2322 for non-generator return undefined")
    }
}
