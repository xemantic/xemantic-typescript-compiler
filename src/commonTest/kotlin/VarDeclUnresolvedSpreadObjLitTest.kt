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
 * Round 472: a `const w: T = { ...anyExpr, … }` — an object literal spreading an
 * any/unresolved value — is typed `any` by tsc (the spread poisons the whole
 * object), so it can never be "missing" required target properties. The round-445
 * rule existed only on the RETURN path; the var-decl path FP'd TS2740 on tsc's
 * completions.ts:2391 `const writer: EmitTextWriter = { ...baseWriter, write: …,
 * … }` where baseWriter comes from an unresolvable `.js`-barrel namespace call.
 */
class VarDeclUnresolvedSpreadObjLitTest {

    @Test
    fun `a var-decl objlit spreading an any value is never missing target properties`() {
        diagnose(
            """
            interface EmitTextWriter {
                write(s: string): void;
                writeKeyword(s: string): void;
                writeOperator(s: string): void;
                writePunctuation(s: string): void;
                writeSpace(s: string): void;
            }
            declare const someAny: any;
            function make() {
                const baseWriter = someAny.createWriter("x");
                const writer: EmitTextWriter = {
                    ...baseWriter,
                    write: (s: string) => baseWriter.write(s),
                };
                return writer;
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2739 || it.code == 2740 || it.code == 2741 || it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a spread-free objlit missing properties still fires`() {
        diagnose(
            """
            interface EmitTextWriter {
                write(s: string): void;
                writeKeyword(s: string): void;
                writeOperator(s: string): void;
                writePunctuation(s: string): void;
                writeSpace(s: string): void;
            }
            function make2() {
                const writer: EmitTextWriter = {
                    write: (s: string) => {},
                };
                return writer;
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2739 || it.code == 2740 })
        }
    }

    @Test
    fun `negative control - a spread of a CONCRETE partial value keeps missing-prop checking`() {
        // The spread type resolves (not any) and guarantees only `write` — the
        // remaining members are genuinely missing, so the check must keep firing.
        diagnose(
            """
            interface EmitTextWriter {
                write(s: string): void;
                writeKeyword(s: string): void;
                writeOperator(s: string): void;
                writePunctuation(s: string): void;
                writeSpace(s: string): void;
            }
            declare const partial: { write(s: string): void; };
            function make3() {
                const writer: EmitTextWriter = { ...partial };
                return writer;
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2739 || it.code == 2740 })
        }
    }
}
