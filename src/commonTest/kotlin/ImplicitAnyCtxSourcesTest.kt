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
 * Round 435c: four TS7006 contextual-typing sources from the round-431 residual
 * triage, each pinned with its tsc-source shape:
 *
 * 1. NAMESPACE-LOCAL annotations — `const map: ManyToManyPathMap = {…}` inside
 *    `namespace BuilderState` (the walker's annotation resolution bridges the
 *    enclosing namespace onto inferenceNamespaceStack for that one call).
 * 2. Declared-by-INITIALIZER locals — `var addLazyDiagnostic = (arg: () => void)
 *    => {…}` then `addLazyDiagnostic = cb => cb()` (checker.ts): the assignment
 *    RHS types from the initializer's inferred type.
 * 3. The Map.get idiom — `let rule = cache.get(k); rule = node => …` where
 *    `cache: Map<K, (node: X) => Y> | undefined` (parenthesizerRules.ts): the
 *    local types as the Map's VALUE type argument.
 * 4. NULLISH constituents don't disable member contextual typing — a return
 *    annotation `Host | undefined` still types the returned literal's member
 *    arrows (watchUtilities.ts readFile); a REAL primitive alternative still
 *    does disable it (the corpus-pinned `string | FullRule` rule).
 */
class ImplicitAnyCtxSourcesTest {

    private fun ts7006s(source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")
            .diagnostics.filter { it.code == 7006 }

    /** Namespace-local interface annotation provides object-literal member ctx. */
    @Test fun namespaceLocalAnnotationProvidesMemberCtx() {
        val diags = ts7006s(
            """
            namespace BuilderState {
                export interface ManyToManyPathMap {
                    getKeys(v: string): Set<string> | undefined;
                    deleteKey(k: string): boolean;
                }
                export function create(): ManyToManyPathMap {
                    const map: ManyToManyPathMap = {
                        getKeys: v => undefined,
                        deleteKey: k => false,
                    };
                    return map;
                }
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS7006, got: $diags")
    }

    /** Declared-by-initializer local: assignment RHS arrow params type from it. */
    @Test fun arrowInitializerLocalTypesLaterAssignment() {
        val diags = ts7006s(
            """
            function outer() {
                var addLazyDiagnostic = (arg: () => void) => { arg(); };
                function run() {
                    const old = addLazyDiagnostic;
                    addLazyDiagnostic = cb => cb();
                    addLazyDiagnostic = old;
                }
                run();
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS7006, got: $diags")
    }

    /** NEGATIVE control (the pinned evolving-any rule): a local with NEITHER
     *  annotation NOR initializer keeps TS7006 firing on the assigned arrow. */
    @Test fun untypedUninitializedLocalStillFires() {
        val diags = ts7006s(
            """
            function outer() {
                let mark;
                mark = tag => tag;
                return mark;
            }
            """.trimIndent()
        )
        assertTrue(diags.isNotEmpty(), "expected TS7006 for the evolving-any local")
    }

    /** The parenthesizerRules Map.get idiom. */
    @Test fun mapGetInitializedLocalTypesLaterAssignment() {
        val diags = ts7006s(
            """
            function outer() {
                let cache: Map<number, (node: string) => string> | undefined;
                function rule(operator: number) {
                    cache ||= new Map();
                    let parenthesizerRule = cache.get(operator);
                    if (!parenthesizerRule) {
                        parenthesizerRule = node => node;
                        cache.set(operator, parenthesizerRule);
                    }
                    return parenthesizerRule;
                }
                return rule;
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS7006, got: $diags")
    }

    /** A `Host | undefined` return annotation still contextually types the
     *  returned literal's member arrows (nullish constituents are discriminated
     *  out of contextual unions). */
    @Test fun nullishUnionReturnAnnotationStillProvidesMemberCtx() {
        val diags = ts7006s(
            """
            interface CachedHost {
                readFile(path: string, encoding?: string): string | undefined;
            }
            declare const host: CachedHost;
            function create(ok: boolean): CachedHost | undefined {
                if (!ok) return undefined;
                return {
                    readFile: (path, encoding) => host.readFile(path, encoding),
                };
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS7006, got: $diags")
    }

    /** NEGATIVE control (corpus-pinned rule): a REAL primitive union alternative
     *  still disables member contextual typing. */
    @Test fun primitiveUnionAlternativeStillFires() {
        val diags = ts7006s(
            """
            interface FullRule {
                normalize(match: string): string;
            }
            type Rule = string | FullRule;
            function make(): Rule {
                return {
                    normalize: match => match,
                };
            }
            """.trimIndent()
        )
        assertTrue(diags.isNotEmpty(), "expected TS7006 under the union-with-primitive rule")
    }
}
