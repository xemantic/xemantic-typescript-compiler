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
 * Round 480: an inline arrow arg whose every RETURN is a string literal in the
 * param's literal-union return is contextually valid — tsc's contextual return
 * typing keeps the literals un-widened (vfsUtil's `this._walk(path, true,
 * (err, res) => { … return "retry"; … return "throw"; })` vs a callback typed
 * `(…) => "retry" | "throw"`).
 */
class FnArgLiteralReturnsTest {

    private val prelude = """
        interface WalkResult { depth: number; }
        declare function walk(
            path: string,
            noFollow?: boolean,
            onError?: (error: Error, fragment: WalkResult) => "retry" | "throw",
        ): WalkResult;
        declare function walk(
            path: string,
            noFollow?: boolean,
            onError?: (error: Error, fragment: WalkResult) => "stop" | "retry" | "throw",
        ): WalkResult | undefined;
    """.trimIndent()

    @Test
    fun `literal-returning callback arg matches the literal-union param`() {
        diagnose(prelude + """

            export function mkdirp(path: string): void {
                walk(path, true, (error, result) => {
                    if (error.message === "ENOENT") {
                        void result;
                        return "retry";
                    }
                    return "throw";
                });
                walk(path, true, () => "stop");
            }
        """.trimIndent()) should {
            have(none { it.code == 2769 || it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a wrong literal still fires`() {
        diagnose(prelude + """

            export function bad(path: string): void {
                walk(path, true, () => "explode");
            }
        """.trimIndent()) should {
            have(any { it.code == 2345 || it.code == 2769 })
        }
    }
}
