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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * Round 475 (Blocker #3, the completions.ts `Request` family): a file-local
 * `type Request = <union of inline type literals>` loses the last-wins merge to another
 * file's `export interface Request` (Interface+TypeAlias do not merge), so in the alias's
 * OWN file every position naming `Request` resolves to the wrong interface (the chimera).
 * Three coupled extensions:
 *  - `returnSourceSatisfiesFileLocalAliasBody` iterates EVERY union member of the return
 *    annotation (was: sole non-nullish member only) — `CompletionData | Request | undefined`;
 *  - `objectLiteralMatchesConflatedFileLocalTypeAlias` checks inline TYPE-LITERAL alias-body
 *    constituents (was: TypeReference constituents only) — suppresses the excess-property
 *    TS2353 against the wrong union member;
 *  - `checkMemberAccessMissing`'s union branch suppresses a TS2339 when a MULTI-member
 *    receiver union contains an own-file conflated alias member (the chimera makes
 *    discriminant narrowing unmodelable) AND the property exists on some member or
 *    alias-body constituent — a name missing everywhere still fires.
 */
class ConflatedAliasUnionMemberTest {

    private fun compile(@Language("typescript") source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "entry.ts").diagnostics

    private val protocolFile = """
        // @Filename: protocol.ts
        export interface Message { seq: number; type: string; }
        export interface Request extends Message { command: string; arguments?: any; }
    """

    private val completionsPrelude = """
        // @Filename: completions.ts
        export const enum CompletionDataKind { Data, JsDocTagName, JsDocTag, JsDocParameterName, Keywords }
        interface CompletionData {
            readonly kind: CompletionDataKind.Data;
            readonly isJsxIdentifierExpected: boolean | undefined;
        }
        type Request =
            | { readonly kind: CompletionDataKind.JsDocTagName | CompletionDataKind.JsDocTag; }
            | { readonly kind: CompletionDataKind.JsDocParameterName; tag: string; }
            | { readonly kind: CompletionDataKind.Keywords; keywordCompletions: readonly string[]; isNewIdentifierLocation: boolean; };
    """

    @Test
    fun `returned inline type literal matches an alias-body constituent - no TS2322 or TS2353`() {
        compile(
            """
            // @strict: true
            $protocolFile
            $completionsPrelude
            export function getCompletionData(x: number): CompletionData | Request | undefined {
                if (x === 1) {
                    return { kind: CompletionDataKind.JsDocTagName };
                }
                if (x === 2) {
                    return { kind: CompletionDataKind.JsDocParameterName, tag: "t" };
                }
                if (x === 3) {
                    return { kind: CompletionDataKind.Keywords, keywordCompletions: [], isNewIdentifierLocation: false };
                }
                return undefined;
            }
            """
        ) should {
            have(none { it.code == 2322 })
            have(none { it.code == 2353 })
        }
    }

    @Test
    fun `member access on a union containing the chimera member - no TS2339`() {
        compile(
            """
            // @strict: true
            $protocolFile
            $completionsPrelude
            declare function getCompletionData(x: number): CompletionData | Request | undefined;
            export function consume(x: number): string | undefined {
                const completionData = getCompletionData(x);
                if (!completionData) return undefined;
                if (completionData.kind !== CompletionDataKind.Data) {
                    return "request";
                }
                const b = completionData.isJsxIdentifierExpected;
                return b ? "jsx" : "no";
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - returned object matching no alias constituent still fires`() {
        compile(
            """
            // @strict: true
            $protocolFile
            $completionsPrelude
            export function bad(x: number): CompletionData | Request | undefined {
                if (x === 1) {
                    return { bogus: 1 };
                }
                return undefined;
            }
            """
        ) should {
            have(any { it.code == 2353 || it.code == 2322 })
        }
    }

    @Test
    fun `negative control - non-conflated union target keeps normal checking`() {
        // No sibling `interface Request` anywhere: Request resolves normally and a
        // wrong return still fires through the standard paths.
        compile(
            """
            // @strict: true

            // @Filename: completions.ts
            export const enum CompletionDataKind { Data, JsDocTagName }
            interface CompletionData { readonly kind: CompletionDataKind.Data; }
            type Request = { readonly kind: CompletionDataKind.JsDocTagName; };
            export function bad(x: number): CompletionData | Request | undefined {
                if (x === 1) {
                    return { wrongProp: true };
                }
                return undefined;
            }
            """
        ) should {
            have(any { it.code == 2353 || it.code == 2322 })
        }
    }
}
