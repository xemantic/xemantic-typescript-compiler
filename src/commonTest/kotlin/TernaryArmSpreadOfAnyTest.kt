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
 * Round 474 (the tsc session.ts toFileSpanWithContext family): a ternary ARM that
 * is an object literal SPREADING an any/error-typed value is `any` in tsc (the
 * spread poisons the whole object — round 445's rule, previously wired only into
 * the direct-return and var-decl paths; [checkConditionalReturnBranches] now
 * applies it per-arm). `context ? { ...fileSpan, contextStart, contextEnd } :
 * fileSpan` where fileSpan's type is unresolvable must not report the arm as
 * missing the spread's properties.
 */
class TernaryArmSpreadOfAnyTest {

    @Test
    fun `ternary arm spreading an unresolvable value is not checked`() {
        diagnose(
            """
            interface FileSpan { file: string; start: number; end: number; }
            interface FileSpanWithContext extends FileSpan {
                contextStart?: number;
                contextEnd?: number;
            }
            declare const anyValue: any;
            export function toFileSpanWithContext(hasContext: boolean): FileSpanWithContext {
                const fileSpan = anyValue.makeSpan();
                return hasContext ?
                    { ...fileSpan, contextStart: 1, contextEnd: 2 } :
                    fileSpan;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a resolvable arm missing required props still fires`() {
        diagnose(
            """
            interface FileSpan { file: string; start: number; end: number; }
            export function bad(hasContext: boolean): FileSpan {
                return hasContext ?
                    { file: "f" } :
                    { file: "g", start: 0, end: 1 };
            }
            """,
        ) should {
            have(any { it.code == 2322 || it.code == 2739 || it.code == 2741 })
        }
    }
}
