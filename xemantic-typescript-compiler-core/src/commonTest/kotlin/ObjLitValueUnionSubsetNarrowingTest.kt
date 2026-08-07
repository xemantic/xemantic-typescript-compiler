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
 * Round 459: `objLitValueNullishStrip` (the gate on flow-narrowed object-literal
 * property VALUES and array-literal ELEMENTS) also accepts a nullish-FREE strict
 * MEMBER-SUBSET narrow of a union source — relations are monotone for union
 * sources, so a member-subset substitution can only suppress a false mismatch.
 *
 * tsc's readConfigFile (commandLineParser.ts): `isString(textOrDiagnostic) ?
 * parse(...) : { config: {}, error: textOrDiagnostic }` — the FALSE branch
 * narrows `string | Diagnostic` to `Diagnostic`, which is not a nullish strip,
 * so the round-438 gate rejected it → FP against `{ error?: Diagnostic }`.
 *
 * The round-438 shadowing hazard (an inner same-named const over-narrowing to
 * `undefined`) stays excluded: a nullish narrowed type never qualifies.
 */
class ObjLitValueUnionSubsetNarrowingTest {

    private val prelude = """
        interface Diagnostic { code: number; }
        declare function isString(text: unknown): text is string;
        declare function tryReadFile(fileName: string): string | Diagnostic;

    """.trimIndent()

    @Test
    fun `guard-narrowed union value in an object literal narrows to the member subset - no TS2322`() {
        diagnose(prelude + """
            declare function parseJson(fileName: string, text: string): { config?: any; error?: Diagnostic };
            export function readConfigFile(fileName: string): { config?: any; error?: Diagnostic; } {
                const textOrDiagnostic = tryReadFile(fileName);
                return isString(textOrDiagnostic) ? parseJson(fileName, textOrDiagnostic) : { config: {}, error: textOrDiagnostic };
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an unguarded union value still fires TS2322`() {
        diagnose(prelude + """
            export function neg(fileName: string): { config?: any; error?: Diagnostic; } {
                const textOrDiagnostic = tryReadFile(fileName);
                return { config: {}, error: textOrDiagnostic };
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }
}
