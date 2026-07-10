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
 * Round 463: a for-of/for-in loop-header `const X` (B83.5-unbound, like every
 * block-scoped local) whose name collides with a top-level FUNCTION merged into
 * `globals` resolved through globals in argument position — tsc's
 * `for (const patternText of paths[key])` (moduleSpecifiers.ts:929) resolved
 * core.ts's exported `function patternText(): string`, so `normalizePath(
 * patternText)` FP'd TS2345 "'() => string' is not assignable to 'string'".
 * `shadowCallTypesDeclList`'s Identifier branch now registers a global-colliding
 * local into the `currentParamBindingNames` side set (anyType, suppression-only —
 * a concrete `currentLocalTypes` recording still wins because the side set is
 * consulted after it).
 */
class LoopVarGlobalFnShadowTest {

    @Test
    fun `a for-of loop var shadowing a top-level function is not the function in arg position`() {
        diagnose("""
            function patternText(): string { return "p"; }
            declare function normalizePath(path: string): string;
            function walk(paths: { [key: string]: readonly string[] }) {
                for (const key in paths) {
                    for (const patternText of paths[key]) {
                        const normalized = normalizePath(patternText);
                    }
                }
                return patternText();
            }
        """) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a plain block const shadowing a top-level function is not the function in arg position`() {
        diagnose("""
            function label(): string { return "l"; }
            declare function print(s: string): void;
            function f(flag: boolean) {
                if (flag) {
                    const label = "on";
                    print(label);
                }
            }
        """) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a genuine bad arg from a typed param still fires TS2345`() {
        diagnose("""
            declare function normalizePath(path: string): string;
            function f(n: number) {
                normalizePath(n);
            }
        """) should {
            have(any { it.code == 2345 })
        }
    }
}
