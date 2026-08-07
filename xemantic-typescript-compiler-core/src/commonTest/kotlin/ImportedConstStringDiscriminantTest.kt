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
 * Round 477 (tsc typingInstallerAdapter.ts:233): a `kind: <AliasName>`
 * discriminant annotation read from a file that only IMPORTS the merged
 * const+type-alias name (`ActionSet` — the round-473 Variable+TypeAlias merge)
 * resolves through the merged GLOBALS symbol's TypeAliasDeclaration — the
 * checking file's local symbol is an IMPORT alias whose declarations are
 * ImportSpecifiers, which previously bailed the whole
 * [enumMemberKeysOfTypeNode] read → the default-clause exhaustiveness never
 * fired → `default: assertType<never>(response)` FP'd TS2345.
 */
class ImportedConstStringDiscriminantTest {

    private val decls = """
        // @module: nodenext
        // @strict: true
        // @filename: shared.ts
        export type ActionSet = "action::set";
        export type ActionInvalidate = "action::invalidate";
        export const ActionSet: ActionSet = "action::set";
        export const ActionInvalidate: ActionInvalidate = "action::invalidate";
        // @filename: types.ts
        import { ActionSet, ActionInvalidate } from "./shared.js";
        export interface SetTypings {
            readonly kind: ActionSet;
            readonly typings: string[];
        }
        export interface InvalidateCachedTypings {
            readonly kind: ActionInvalidate;
            readonly projectName: string;
        }
        export type ResponseUnion = SetTypings | InvalidateCachedTypings;
    """.trimIndent()

    @Test
    fun `exhaustive switch default narrows an imported-alias-kind union to never`() {
        diagnose(
            decls + """

            // @filename: adapter.ts
            import { ActionSet, ActionInvalidate } from "./shared.js";
            import { ResponseUnion } from "./types.js";
            declare function assertType<T>(value: T): void;
            export function handle(response: ResponseUnion): void {
                switch (response.kind) {
                    case ActionSet:
                        break;
                    case ActionInvalidate:
                        break;
                    default:
                        assertType<never>(response);
                }
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a NON-exhaustive switch default still fires`() {
        diagnose(
            decls + """

            // @filename: adapter.ts
            import { ActionSet } from "./shared.js";
            import { ResponseUnion } from "./types.js";
            declare function assertType<T>(value: T): void;
            export function handle(response: ResponseUnion): void {
                switch (response.kind) {
                    case ActionSet:
                        break;
                    default:
                        assertType<never>(response);
                }
            }
            """.trimIndent(),
        ) should {
            // InvalidateCachedTypings is not covered — response is NOT never.
            have(any { it.code == 2345 })
        }
    }
}
