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
 * Round 429 (M3.1): the call-types pass respects lexical SHADOWING for three
 * scope shapes that previously resolved a bare-identifier ARG to the WRONG
 * outer declaration:
 *
 *  1. a NESTED function's body-local (`let host = node.parent`) shadowing an
 *     ENCLOSING function's param (`createTypeChecker(host: TypeCheckerHost)`)
 *     — tsc checker.ts getAliasSymbolForTypeNode, ×14 self-compile;
 *  2. a DESTRUCTURED param binding name colliding with a same-named file-level/
 *     imported FUNCTION (`function createWatcher({ useCaseSensitiveFileNames }:
 *     Opts)` vs moduleNameResolver's `function useCaseSensitiveFileNames(state)`)
 *     — tsc sys.ts, ×9 self-compile;
 *  3. an ARROW param shadowing an enclosing binding (this walker deliberately
 *     does not type arrow params, so the enclosing type leaked in).
 *
 * All suppression-only: the shadow registers anyType, never a concrete type.
 */
class CallTypesScopeShadowingTest {

    @Test
    fun `nested-fn body-local shadowing an enclosing param does not FP as arg`() {
        diagnose(
            """
            interface TypeCheckerHost { getSourceFiles(): string[]; }
            interface Nd { parent: Nd; kind: number; }
            declare function isTypeAlias(node: Nd): boolean;
            export function createTypeChecker(host: TypeCheckerHost) {
                function getAliasSymbolForTypeNode(node: Nd) {
                    let host = node.parent;
                    return isTypeAlias(host);
                }
                return [getAliasSymbolForTypeNode, host];
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `block-scoped const in a nested block shadows the enclosing param too`() {
        diagnose(
            """
            interface TypeCheckerHost { getSourceFiles(): string[]; }
            interface Nd { parent: Nd; kind: number; }
            declare function isTypeAlias(node: Nd): boolean;
            export function createTypeChecker(host: TypeCheckerHost) {
                function blockScoped(node: Nd) {
                    if (node.kind === 1) {
                        const host = node.parent;
                        return isTypeAlias(host);
                    }
                    return false;
                }
                return [blockScoped, host];
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `destructured param colliding with a file-level function does not FP as arg`() {
        diagnose(
            """
            interface ModuleResolutionState { host: unknown; }
            export function useCaseSensitiveFileNames(state: ModuleResolutionState) {
                return !!state.host;
            }
            interface Opts { useCaseSensitiveFileNames: boolean; other: string; }
            declare function getStringComparer(ignoreCase: boolean): (a: string, b: string) => number;
            export function createWatcher({ useCaseSensitiveFileNames, other }: Opts) {
                const comparer = getStringComparer(useCaseSensitiveFileNames);
                return [comparer, other];
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `arrow param shadowing an enclosing param does not FP as arg`() {
        diagnose(
            """
            interface TypeCheckerHost { getSourceFiles(): string[]; }
            interface Nd { parent: Nd; kind: number; }
            declare function isTypeAlias(node: Nd): boolean;
            export function createTypeChecker(host: TypeCheckerHost) {
                const walk = (host: Nd) => isTypeAlias(host);
                return [walk, host];
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `arrow body-local shadowing an enclosing param does not FP as arg`() {
        diagnose(
            """
            interface TypeCheckerHost { getSourceFiles(): string[]; }
            interface Nd { parent: Nd; kind: number; }
            declare function isTypeAlias(node: Nd): boolean;
            export function createTypeChecker(host: TypeCheckerHost) {
                const walk = (node: Nd) => {
                    const host = node.parent;
                    return isTypeAlias(host);
                };
                return [walk, host];
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - the enclosing param itself still fires as a wrong arg`() {
        // No shadow anywhere: passing the ENCLOSING param where a different
        // interface is required must keep firing.
        diagnose(
            """
            interface TypeCheckerHost { getSourceFiles(): string[]; }
            interface Nd { parent: Nd; kind: number; }
            declare function isTypeAlias(node: Nd): boolean;
            export function createTypeChecker(host: TypeCheckerHost) {
                return isTypeAlias(host);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a var redeclaring the SAME function's param keeps the param type`() {
        // `var x` redeclaring a same-function param is a REDECLARATION (param
        // wins), NOT a shadow — the genuine wrong-arg check must keep firing.
        diagnose(
            """
            interface TypeCheckerHost { getSourceFiles(): string[]; }
            interface Nd { parent: Nd; kind: number; }
            declare function isTypeAlias(node: Nd): boolean;
            export function f(host: TypeCheckerHost) {
                var host;
                return isTypeAlias(host);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - non-shadowed wrong-typed simple param still fires`() {
        diagnose(
            """
            declare function canUse(name: string): boolean;
            export function f(count: number): boolean {
                return canUse(count);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
