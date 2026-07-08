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
 * Round 448 (self-compile burn-down, services): a DESTRUCTURED `const { hasReturn } = info`
 * shadows a same-file module-level `function hasReturn`, so `hasReturn ? … : …` must NOT FP-fire
 * TS2774 "this condition will always return true since this function is always defined". The
 * TS2774 walker's shadow-collector handled only a simple `const x` (skipped binding patterns), so
 * the destructured `hasReturn` was resolved to the function (jsDoc.ts getDocCommentTemplateAtPosition
 * → 0). Binding-pattern names are now registered as shadows.
 */
class UncalledDestructuredShadowTest {

    @Test
    fun `a destructured local shadowing a same-file function does not FP TS2774`() {
        diagnose(
            """
            interface Info { commentOwner: number; hasReturn?: boolean; }
            function hasReturn(node: number): boolean { return node > 0; }
            declare const info: Info;
            export function template(): string {
                const { commentOwner, hasReturn } = info;
                return (hasReturn ? "a" : "") + commentOwner;
            }
            """
        ) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `firewall - a genuine uncalled same-file function in a condition still FP-fires TS2774`() {
        diagnose(
            """
            function f(): boolean { return true; }
            export function g(): string {
                return (f ? "a" : "");
            }
            """
        ) should {
            have(any { it.code == 2774 })
        }
    }
}
