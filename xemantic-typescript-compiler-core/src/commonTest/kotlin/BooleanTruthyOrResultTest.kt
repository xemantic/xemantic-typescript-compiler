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
 * Round 471: tsc's truthy facts map a `boolean` left operand of `||` to `true`
 * — `preferences.includeCompletionsWithSnippetText || undefined` is
 * `true | undefined`, not `boolean | undefined` (tsc completions.ts
 * getEntryForObjectLiteralMethodCompletion returning `{ isSnippet }` against
 * `isSnippet?: true`). Gated: when the RIGHT side is boolean-like the left
 * stays `boolean` (tsc reduces `true | boolean` by subsumption, which our
 * getUnionType does not model).
 */
class BooleanTruthyOrResultTest {

    @Test
    fun `boolean or undefined satisfies an optional true member`() {
        diagnose(
            """
            interface Prefs { includeSnippets?: boolean; }
            declare const preferences: Prefs;
            declare function build(): string;
            function f(): { insertText: string; isSnippet?: true; } | undefined {
                const isSnippet = preferences.includeSnippets || undefined;
                const insertText = build();
                return { insertText, isSnippet };
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `boolean or boolean stays boolean`() {
        diagnose(
            """
            declare const a: boolean;
            declare const b: boolean;
            const keep: boolean = a || b;
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - true or undefined does not satisfy false or undefined`() {
        diagnose(
            """
            interface P { s?: boolean; }
            declare const p: P;
            const bad: false | undefined = p.s || undefined;
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
