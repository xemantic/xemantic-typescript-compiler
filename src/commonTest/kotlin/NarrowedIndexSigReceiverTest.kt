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
 * Round 479: an index signature provides every (string-index) / numeric
 * (number-index) property, so the narrowed-to-single-Object TS2339 emission must
 * bail for a receiver whose narrowed interface carries one — tsc resolves
 * `settings.typeScriptVersion` on `interface CompilerSettings { [name: string]:
 * string }` through the index signature (harnessIO.ts compileFiles). The
 * UN-narrowed receiver path already had this bail; the narrowed single-interface
 * emission (union-with-undefined receiver truthy-narrowed) missed it.
 */
class NarrowedIndexSigReceiverTest {

    @Test
    fun `narrowed receiver with string index sig draws no TS2339`() {
        diagnose(
            """
            interface CompilerSettings {
                [name: string]: string;
            }
            export function f(settings: CompilerSettings | undefined): void {
                if (settings) {
                    const v = settings.typeScriptVersion;
                    void v;
                }
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `number index sig provides numeric names only`() {
        diagnose(
            """
            interface Row {
                [i: number]: string;
            }
            export function f(row: Row | undefined): void {
                if (row) {
                    const v = row[0];
                    void v;
                }
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - narrowed receiver without index sig still fires`() {
        diagnose(
            """
            interface Plain {
                known: string;
            }
            export function f(p: Plain | undefined): void {
                if (p) {
                    const v = p.missingProp;
                    void v;
                }
            }
            """,
        ) should {
            have(any { it.code == 2339 && "missingProp" in it.message })
        }
    }
}
