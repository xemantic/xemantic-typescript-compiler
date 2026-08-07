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
 * Round 474 (the tsc jsTyping.ts `SafeList` family): a return annotation naming a
 * conflated `type X` THIS FILE declares (`type SafeList = Map-like` in jsTyping.ts
 * vs `interface SafeList { [name: string]: … }` in editorServices.ts — the
 * last-wins Interface+TypeAlias merge resolves the WRONG one) is checked against
 * the TRUE file-local alias BODY ([returnSourceSatisfiesFileLocalAliasBody]);
 * a relating source suppresses, a non-relating one still fires.
 */
class ReturnFileLocalAliasBodyTest {

    private val decls = """
        // @strict: true
        // @module: nodenext
        // @filename: editorServices.ts
        export interface SafeList {
            match: string;
            exclude?: string[];
        }
        export function useSafeList(s: SafeList): void { void s; }
    """.trimIndent()

    @Test
    fun `return relating to the file-local alias body is suppressed`() {
        diagnose(
            decls + """

            // @filename: jsTyping.ts
            export type SafeList = Map<string, string>;
            export function loadSafeList(entries: [string, string][]): SafeList {
                return new Map(entries);
            }
            export function loadTypesMap(entries: [string, string][] | undefined): SafeList | undefined {
                if (entries) {
                    return new Map(entries);
                }
                return undefined;
            }
            """.trimIndent(),
            directives = "",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a source failing the alias body still fires`() {
        diagnose(
            decls + """

            // @filename: jsTyping.ts
            export type SafeList = Map<string, string>;
            export function bad(): SafeList {
                return "not a map";
            }
            """.trimIndent(),
            directives = "",
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
