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
 * Round 471: switch-over-enum exhaustion compares VALUE domains, not member
 * names — an ALIAS member (`NameContainsNonURISafeCharacters =
 * NameContainsInvalidCharacters`, tsc jsTyping.ts NameValidationResult) shares
 * a covered member's value, and tsc's case narrowing removes every member with
 * that value, so the `default: Debug.assertNever(result)` is legal. A member
 * with a genuinely distinct uncovered value still defeats exhaustion.
 */
class EnumValueAliasExhaustiveSwitchTest {

    @Test
    fun `an alias member sharing a covered value exhausts the switch`() {
        diagnose(
            """
            declare function assertNever(x: never): never;
            const enum R { Ok, Empty, TooLong, Invalid, NonURISafe = Invalid }
            function render(result: R): string {
                switch (result) {
                    case R.Ok: return "ok";
                    case R.Empty: return "empty";
                    case R.TooLong: return "long";
                    case R.Invalid: return "invalid";
                    default:
                        assertNever(result);
                }
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - an uncovered distinct-valued member still fires`() {
        diagnose(
            """
            declare function assertNever(x: never): never;
            const enum E { A, B, C }
            function f(e: E): string {
                switch (e) {
                    case E.A: return "a";
                    case E.B: return "b";
                    default:
                        assertNever(e);
                }
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
