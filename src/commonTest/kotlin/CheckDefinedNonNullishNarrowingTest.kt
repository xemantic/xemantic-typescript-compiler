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
 * Round 461: the `Debug.checkDefined` shape — a call whose resolved callee returns a
 * bare OWN type parameter `T` while some parameter is annotated `T | undefined` (or
 * `T | null | undefined`) proves the assigned reference NON-nullish for flow
 * narrowing: inference binds T to the argument's non-nullish part, and the callee's
 * contract is to return the defined value. Gated to calls WITHOUT explicit type
 * arguments. Extends [callRhsHasNonNullishReturnAnnotation], whose syntactic
 * classifier deliberately bails on own-TP returns.
 *
 * tsc-source shape: program.ts:4041 — `sourceFile = Debug.checkDefined(
 * commandLine.options.configFile)` in one branch of an if/else whose join must see
 * `sourceFile` non-undefined on BOTH paths.
 */
class CheckDefinedNonNullishNarrowingTest {

    private val single = """
        declare function checkDefined<T>(value: T | undefined | null, message?: string): T;
        interface JsonFile { fileName: string }
        interface Ref { commandLine: number; sourceFile: JsonFile }
        declare function getFile(): JsonFile | undefined;
        declare const useCfg: boolean;
    """.trimIndent()

    @Test
    fun `checkDefined-shape assignment proves the reference non-nullish at an if-else join`() {
        diagnose(single + """
            export function f(): Ref | undefined {
                let commandLine: number | undefined;
                let sourceFile: JsonFile | undefined;
                if (useCfg) {
                    commandLine = 1;
                    sourceFile = checkDefined(getFile());
                }
                else {
                    sourceFile = getFile();
                    if (sourceFile === undefined) {
                        return undefined;
                    }
                    commandLine = 2;
                }
                const resolvedRef: Ref = { commandLine, sourceFile };
                return resolvedRef;
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a bare-TP return WITHOUT the nullish-param shape does not prove non-nullish`() {
        diagnose(single + """
            declare function identity<T>(value: T): T;
            export function f(): Ref | undefined {
                let sourceFile: JsonFile | undefined;
                if (useCfg) {
                    sourceFile = identity(getFile());
                }
                else {
                    sourceFile = getFile();
                    if (sourceFile === undefined) {
                        return undefined;
                    }
                }
                const resolvedRef: Ref = { commandLine: 1, sourceFile };
                return resolvedRef;
            }
        """) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - explicit nullish type argument keeps the reference nullable`() {
        diagnose(single + """
            export function f(): Ref | undefined {
                let sourceFile: JsonFile | undefined;
                if (useCfg) {
                    sourceFile = checkDefined<JsonFile | undefined>(getFile());
                }
                else {
                    sourceFile = getFile();
                    if (sourceFile === undefined) {
                        return undefined;
                    }
                }
                const resolvedRef: Ref = { commandLine: 1, sourceFile };
                return resolvedRef;
            }
        """) should {
            have(any { it.code == 2322 })
        }
    }
}
