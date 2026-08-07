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
 * Round 474 (Blocker #3, the tsc rules.ts `keyof FormatCodeSettings` family): a
 * `type X` in one file SHADOWS the `interface X` of another via the last-wins
 * Interface+TypeAlias merge (protocol.ts's `type FormatCodeSettings =
 * ChangePropertyTypes<…>` — an unmodeled generic body → anyType), so
 * `keyof FormatCodeSettings` gave `string | number | symbol` and every
 * `hasProperty(options, optionName)` arg FP'd TS2345 against `key: string`.
 * [keyofShadowedInterfaceKeyUnion] recovers the literal key union AST-side
 * (own + `extends`-inherited names); the invalid-key control proves the union
 * is REAL, not a blanket suppression.
 */
class KeyofShadowedInterfaceTest {

    private val decls = """
        // @module: nodenext
        // @filename: servicesTypes.ts
        export interface EditorSettings {
            readonly baseIndentSize?: number;
            readonly indentSize?: number;
        }
        export interface FormatCodeSettings extends EditorSettings {
            readonly insertSpaceAfterCommaDelimiter?: boolean;
        }
        export function hasProperty(map: object, key: string): boolean {
            return key in map;
        }
        // @filename: protocol.ts
        import * as ts from "./servicesTypes.js";
        type ChangePropertyTypes<T, U> = { [K in keyof T]: K extends keyof U ? U[K] : T[K] };
        export type FormatCodeSettings = ChangePropertyTypes<ts.FormatCodeSettings, { insertSpaceAfterCommaDelimiter: string; }>;
    """.trimIndent()

    @Test
    fun `keyof over the shadowed interface is string-assignable`() {
        diagnose(
            decls + """

            // @filename: rules.ts
            import { FormatCodeSettings, hasProperty } from "./servicesTypes.js";
            export function isOptionEnabled(optionName: keyof FormatCodeSettings): (options: FormatCodeSettings) => boolean {
                return options => options && hasProperty(options, optionName) && !!options[optionName];
            }
            export const own = isOptionEnabled("insertSpaceAfterCommaDelimiter");
            export const inherited = isOptionEnabled("indentSize");
            """.trimIndent(),
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `positive control - an invalid key fails the recovered literal union`() {
        diagnose(
            decls + """

            // @filename: rules.ts
            import { FormatCodeSettings } from "./servicesTypes.js";
            export function isOptionEnabled(optionName: keyof FormatCodeSettings): string {
                return String(optionName);
            }
            export const bad = isOptionEnabled("noSuchOption");
            """.trimIndent(),
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
