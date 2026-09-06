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
 * Round 465 (M3.4): the IIFE-const fn pattern — `const f = (() => { return g;
 * function g(…): R {…} })()` — classifies a call `f(…)` by the RETURNED
 * function's own annotation in the flow non-nullish classifier
 * (callRhsHasNonNullishReturnAnnotation), so tsc core.ts's
 * `uiComparerCaseSensitive ??= createUIStringComparer(uiLocale);
 * return uiComparerCaseSensitive(a, b);` proves the callee non-nullish
 * (TS2349 'not callable' was firing on the surviving `| undefined`).
 */
class IifeConstFnNonNullishTest {

    private val comparerShape = """
        enum Comparison { LessThan = -1, EqualTo = 0, GreaterThan = 1 }
        type Comparer<T> = (a: T, b: T) => Comparison;
        let uiComparerCaseSensitive: Comparer<string> | undefined;
        let uiLocale: string | undefined;
    """.trimIndent()

    @Test
    fun `a nullish-assign from an IIFE-const fn call proves the callee non-nullish`() {
        diagnose(
            comparerShape + """

            const createUIStringComparer = (() => {
                return createIntlCollatorStringComparer;

                function createIntlCollatorStringComparer(locale: string | undefined): Comparer<string> {
                    return (a, b) => Comparison.EqualTo;
                }
            })();

            export function compareStringsCaseSensitiveUI(a: string, b: string): Comparison {
                uiComparerCaseSensitive ??= createUIStringComparer(uiLocale);
                return uiComparerCaseSensitive(a, b);
            }
            """
        ) should {
            have(none { it.code == 2349 })
        }
    }

    @Test
    fun `negative control - an IIFE returning a NULLABLE-returning fn keeps the error`() {
        diagnose(
            comparerShape + """

            const createMaybeComparer = (() => {
                return make;

                function make(locale: string | undefined): Comparer<string> | undefined {
                    return undefined;
                }
            })();

            export function compare(a: string, b: string): Comparison {
                uiComparerCaseSensitive ??= createMaybeComparer(uiLocale);
                return uiComparerCaseSensitive(a, b);
            }
            """
        ) should {
            // (CHK.97) a NULLISH union callee is TS2722 — tsc checks nullability
            // before it asks for signatures; both references agree on this shape.
            have(any { it.code == 2722 })
            have(none { it.code == 2349 })
        }
    }

    @Test
    fun `negative control - without the nullish-assign the nullable callee keeps the error`() {
        diagnose(
            comparerShape + """

            export function compare(a: string, b: string): Comparison {
                return uiComparerCaseSensitive(a, b);
            }
            """
        ) should {
            // (CHK.97) a NULLISH union callee is TS2722 — tsc checks nullability
            // before it asks for signatures; both references agree on this shape.
            have(any { it.code == 2722 })
            have(none { it.code == 2349 })
        }
    }
}
