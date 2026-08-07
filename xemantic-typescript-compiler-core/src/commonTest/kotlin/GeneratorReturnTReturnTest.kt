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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

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

    @Test
    fun `bare return in a generator with a single-arg IterableIterator annotation is legal`() {
        // TReturn = any — the tsc checker.ts ElaborationIterator shape.
        diagnose(
            """
            type ElabIter = IterableIterator<{ a: number }>;
            function* gen(items: number[]): ElabIter {
                if (!items.length) return;
                for (const i of items) yield { a: i };
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `return undefined in a generator with an alias annotation is legal`() {
        // TReturn is invisible through the alias → any.
        diagnose(
            """
            type ElabIter = IterableIterator<{ a: number }>;
            function* gen(items: number[]): ElabIter {
                if (!items.length) return undefined;
                yield { a: 1 };
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `bare return in a generator method is legal`() {
        // The isGenerator flag threads through the MethodDeclaration
        // checkFunctionBody site.
        diagnose(
            """
            class C {
                *gen(items: number[]): IterableIterator<number> {
                    if (!items.length) return;
                    yield 1;
                }
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an explicit TReturn mismatch still fires`() {
        // `return 42` against Generator<Y, string, N> fires TS2322
        // number→string through the TReturn unwrap.
        diagnose(
            """
            function* gen(): Generator<{ a: number }, string, unknown> {
                yield { a: 1 };
                return 42;
            }
            """
        ) should {
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
    }

    @Test
    fun `negative control - a matching explicit TReturn stays clean`() {
        diagnose(
            """
            function* gen(): Generator<{ a: number }, string, unknown> {
                yield { a: 1 };
                return "done";
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a non-generator return undefined still fires`() {
        // The gate is generator-scoped.
        diagnose(
            """
            function f(): { a: number } {
                return undefined;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
