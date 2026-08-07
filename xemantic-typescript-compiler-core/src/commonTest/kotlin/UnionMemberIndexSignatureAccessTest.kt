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
 * Round 470: a UNION member carrying a STRING INDEX SIGNATURE provides every named
 * property — tsc resolves the access through the index signature
 * (`settingsOrHost.getCompilationSettings` on `CompilerOptions |
 * MinimalResolutionCacheHost` resolves via CompilerOptions' `[option: string]: …`,
 * tsc documentRegistry.ts). Pins the union suppression and the negative control
 * (a union with NO index-sig member still fires TS2339).
 */
class UnionMemberIndexSignatureAccessTest {

    private val prelude = """
        interface Options {
            strict?: boolean;
            [option: string]: string | boolean | undefined;
        }
        interface Host {
            getCompilationSettings(): Options;
        }
    """.trimIndent()

    @Test
    fun `a union member with a string index signature provides any named property`() {
        diagnose(
            prelude + """

            function getSettings(settingsOrHost: Options | Host) {
                if (typeof settingsOrHost.getCompilationSettings === "function") {
                    return (settingsOrHost as Host).getCompilationSettings();
                }
                return settingsOrHost as Options;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a union with no index-signature member still fires TS2339`() {
        diagnose(
            prelude + """

            interface Plain { strict?: boolean }
            function f(x: Plain | Host) {
                return x.nonexistentMember;
            }
            """
        ) should {
            have(any { it.code == 2339 && "nonexistentMember" in it.message })
        }
    }

    @Test
    fun `direct access on the index-signature interface itself stays clean`() {
        diagnose(
            prelude + """

            function f(o: Options) {
                return o.anythingGoes;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }
}
