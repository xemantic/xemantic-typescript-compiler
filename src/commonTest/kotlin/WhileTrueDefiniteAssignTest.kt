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
 * Round 450 (self-compile burn-down, TS2454 -2 compiler / -2 services): a `while (true)`
 * loop's ONLY normal exit is a `break`, so a variable assigned before EVERY exiting break
 * is definitely assigned after the loop (tsc's definite-assignment for constant-true loops).
 * The pervasive scanner idiom `let tok: T; while (true) { if (...) { tok = X; break; } … }
 * return tok;` (scanner.ts scanTemplateAndSetTokenValue) FP-fired TS2454.
 *
 * The definite-assignment walk is SOUND (never over-clears): a break that does not assign,
 * a plain `while (cond)` (body may run zero times), and a `try`/labeled statement that could
 * hide an exiting break all keep the variable uninitialized.
 */
class WhileTrueDefiniteAssignTest {

    @Test
    fun `while(true) with assign-before-every-break definitely assigns`() {
        diagnose(
            """
            function scan(text: string, end: number): number {
                let pos = 0;
                let resultingToken: number;
                while (true) {
                    if (pos >= end) { resultingToken = 1; break; }
                    if (text[pos] === "a") { resultingToken = 2; break; }
                    pos++;
                }
                return resultingToken;
            }
            """,
        ) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `while(true) with if-else both assigning and breaking`() {
        diagnose(
            """
            function scan(end: number): number {
                let tok: number;
                while (true) {
                    if (end > 0) { tok = 1; break; }
                    else { tok = 2; break; }
                }
                return tok;
            }
            """,
        ) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - a break that does not assign still fires`() {
        diagnose(
            """
            function scan(end: number): number {
                let tok: number;
                while (true) {
                    if (end > 0) { tok = 1; break; }
                    if (end < 0) { break; }
                    end++;
                }
                return tok;
            }
            """,
        ) should {
            have(any { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - a non-true while condition still fires`() {
        diagnose(
            """
            function scan(cond: boolean): number {
                let tok: number;
                while (cond) {
                    tok = 1;
                    break;
                }
                return tok;
            }
            """,
        ) should {
            have(any { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - a break inside a try bails conservatively`() {
        diagnose(
            """
            function scan(end: number): number {
                let tok: number;
                while (true) {
                    try {
                        if (end > 0) { tok = 1; break; }
                    } finally { }
                    end++;
                }
                return tok;
            }
            """,
        ) should {
            have(any { it.code == 2454 })
        }
    }
}
