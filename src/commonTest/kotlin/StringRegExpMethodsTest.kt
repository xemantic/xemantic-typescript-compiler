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
 * Round 429b (M3.1 histogram burn-down): the embedded lib's String methods
 * accept a RegExp search argument like the real lib — `replace`/`replaceAll`
 * take `searchValue: string | RegExp` (replaceValue simplified to `any` per the
 * embedded-lib doctrine: the real lib's second overload takes a replacer
 * FUNCTION), `search`/`split` take `string | RegExp`. Previously
 * `x.replace(/^(\d)/, "_$1")` FP'd TS2345 'RegExp' vs 'string' — ×19
 * self-compile (tsc uses regex replace/split pervasively).
 */
class StringRegExpMethodsTest {

    @Test
    fun `replace with a RegExp search value and string replacement is clean`() {
        diagnose(
            """
            export function makeIdentifier(moduleName: string): string {
                return moduleName.replace(/^(\d)/, "_${'$'}1").replace(/\W/g, "_");
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `replace with a RegExp search value and function replacer is clean`() {
        diagnose(
            """
            const fileNameLowerCaseRegExp = /[^İıßa-z0-9\\/:\-_. ]+/g;
            function toLowerCase(x: string) { return x.toLowerCase(); }
            export function toFileNameLowerCase(x: string): string {
                return fileNameLowerCaseRegExp.test(x) ?
                    x.replace(fileNameLowerCaseRegExp, toLowerCase) :
                    x;
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `split and search with a RegExp are clean`() {
        diagnose(
            """
            export function f(text: string): number {
                const lines = text.split(/\r\n?|\n/);
                return lines.length + text.search(/x/);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `replaceAll with a RegExp is clean`() {
        diagnose(
            """
            export function f(text: string): string {
                return text.replaceAll(/a/g, "b");
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a number arg to a plain string param still fires`() {
        // NOTE: `replace(42, "x")` is an accepted FALSE NEGATIVE of this change —
        // `string | RegExp` is a union with an interface member, so the conservative
        // TS2345 arg check bails for that parameter (which is exactly the mechanism
        // that suppresses the RegExp FP). The control pins that plain string-param
        // arg checking on the SAME interface still fires.
        diagnose(
            """
            export function f(text: string): number {
                return text.indexOf(42);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
