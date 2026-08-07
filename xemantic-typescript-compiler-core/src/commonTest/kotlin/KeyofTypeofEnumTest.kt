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
 * Round 470: `keyof typeof <Enum>` resolves to the union of the enum's member-NAME
 * string literals (previously `typeof Enum` washed to anyType, so keyof gave
 * `string | number | symbol` and the tsc navigateTo.ts idiom
 * `Enum[value] as keyof typeof Enum` FP'd TS2322 against a matching literal union).
 * Pins the positive resolution, the value-merge conservative bail, and the
 * negative control (a NON-covering literal-union target still errors).
 */
class KeyofTypeofEnumTest {

    @Test
    fun `enum reverse-map cast to keyof typeof Enum satisfies the member-name literal union`() {
        diagnose(
            """
            enum PatternMatchKind { exact, prefix, substring, camelCase }
            declare const raw: { matchKind: PatternMatchKind };
            const k: "exact" | "prefix" | "substring" | "camelCase" =
                PatternMatchKind[raw.matchKind] as keyof typeof PatternMatchKind;
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `keyof typeof Enum in a returned object literal member position`() {
        diagnose(
            """
            enum Kind { exact, prefix }
            interface Item { matchKind: "exact" | "prefix"; name: string }
            declare const raw: { matchKind: Kind; name: string };
            function create(): Item {
                return {
                    name: raw.name,
                    matchKind: Kind[raw.matchKind] as keyof typeof Kind,
                };
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - keyof typeof Enum is NOT assignable to a narrower literal union`() {
        diagnose(
            """
            enum Kind { exact, prefix, substring }
            declare const s: string;
            const bad: "exact" | "prefix" = s as keyof typeof Kind;
            """
        ) should {
            have(any { it.code == 2322 && "substring" in it.message })
        }
    }

    @Test
    fun `string-valued enum member names still resolve by NAME not value`() {
        diagnose(
            """
            enum Ext { Ts = ".ts", Js = ".js" }
            declare const s: string;
            const k: "Ts" | "Js" = s as keyof typeof Ext;
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `conservative bail - an enum merged with a namespace keeps prior behavior`() {
        // A clodule merge contributes value members beyond the enum members —
        // the resolver must bail (no manufactured literal union → no new error).
        diagnose(
            """
            enum E { a, b }
            namespace E { export const helper = 1; }
            declare const s: string;
            const k: string = s as keyof typeof E;
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }
}
