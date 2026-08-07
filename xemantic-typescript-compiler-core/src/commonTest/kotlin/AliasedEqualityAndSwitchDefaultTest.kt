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
 * M3.4 (round 425): two symmetric discriminant-narrowing extensions —
 *  - an ALIASED `===` discriminant (`const optType = opt.type;
 *    if (optType === "listOrElement") { opt.element }`) narrows the receiver
 *    through the round-423 flow back-walk (tsc commandLineParser's
 *    convertJsonOption), the `===` sibling of the aliased-switch rule;
 *  - a switch DEFAULT clause narrows NEGATIVELY by every case literal/enum
 *    key of the whole switch (tsc executeCommandLine's getPossibleValues:
 *    six literal cases leave only the Map-typed CommandLineOptionOfCustomType
 *    in the default).
 */
class AliasedEqualityAndSwitchDefaultTest {

    private val optShape = """
        interface OptBase { name: string; }
        interface CustomOpt extends OptBase { type: Map<string, string | number>; deprecatedKeys?: Set<string>; }
        interface StringOpt extends OptBase { type: "string"; }
        interface ListOpt extends OptBase { type: "list" | "listOrElement"; element: OptBase; }
        interface ObjOpt extends OptBase { type: "object"; }
        type Opt = CustomOpt | StringOpt | ListOpt | ObjOpt;
    """.trimIndent()

    @Test
    fun `aliased equality discriminant narrows the receiver`() {
        diagnose(
            optShape + """

            declare function convertOne(opt: OptBase): number;
            export function convertJsonOption(opt: Opt): number {
                const optType = opt.type;
                if (optType === "listOrElement") {
                    return convertOne(opt.element);
                }
                return 0;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `reassigned receiver between alias and comparison withholds the narrowing`() {
        diagnose(
            optShape + """

            declare function other(): Opt;
            export function f(opt: Opt): number {
                const optType = opt.type;
                opt = other();
                if (optType === "listOrElement") {
                    return opt.element.name.length;
                }
                return 0;
            }
            """
        ) should {
            have(any { it.code == 2339 && it.message.contains("'element'") })
        }
    }

    @Test
    fun `switch default narrows negatively by all case literals`() {
        diagnose(
            optShape + """

            export function getPossibleValues(option: Opt): string {
                switch (option.type) {
                    case "string":
                        return option.type;
                    case "list":
                    case "listOrElement":
                        return "list";
                    case "object":
                        return "";
                    default:
                        const inverted: string[] = [];
                        option.type.forEach((value, name) => {
                            if (!option.deprecatedKeys?.has(name)) {
                                inverted.push(name);
                            }
                        });
                        return inverted.join("/");
                }
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `switch default keeps a wide non-literal member`() {
        diagnose(
            """
            declare function takes(s: string): number;
            export function neg(x: "a" | string): number {
                switch (x) {
                    case "a": return 0;
                    default: return takes(x);
                }
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }
}
