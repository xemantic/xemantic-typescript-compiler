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

    @Test
    fun `namespace-local interface annotation provides object-literal member context`() {
        diagnose(
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
            """
        ) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `declared-by-initializer local types a later assignment RHS arrow`() {
        diagnose(
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
            """
        ) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - an untyped uninitialized local keeps TS7006 firing`() {
        // The pinned evolving-any rule: a local with NEITHER annotation NOR
        // initializer keeps TS7006 firing on the assigned arrow.
        diagnose(
            """
            function outer() {
                let mark;
                mark = tag => tag;
                return mark;
            }
            """
        ) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `map get initialized local types a later assignment`() {
        // The parenthesizerRules Map.get idiom.
        diagnose(
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
            """
        ) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `nullish union return annotation still provides member context`() {
        // A `Host | undefined` return annotation still contextually types the
        // returned literal's member arrows (nullish constituents are
        // discriminated out of contextual unions).
        diagnose(
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
            """
        ) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - a real primitive union alternative still fires`() {
        // Corpus-pinned rule: a REAL primitive union alternative disables
        // member contextual typing.
        diagnose(
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
            """
        ) should {
            have(any { it.code == 7006 })
        }
    }
}
