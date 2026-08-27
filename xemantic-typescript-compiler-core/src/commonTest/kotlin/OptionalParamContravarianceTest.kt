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

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.62) An OPTIONAL source parameter's type is `T | undefined` — tsc's `addOptionality`
 * — so `(x?: string) => void` IS assignable to `(x: string | undefined) => void`. We modelled
 * it as plain `T`, so the target's `undefined` constituent had nowhere to go under the
 * contravariant test and the relation FP-rejected. Measured on the server profile at
 * `project.ts:2277` — `ServerHost` -> `GetPackageJsonEntrypointsHost`, whose
 * `readDirectory: CompilerHost["readDirectory"]` reaches a METHOD signature through an
 * indexed access and has required `extensions` / `includes` where `System.readDirectory`'s
 * are optional. tsc 7.0.2 is silent on every positive below.
 *
 * RESIDUE, deliberately not pinned (round 765 — a pin on a known-open gap is a countdown,
 * not a guard): the other half of tsc's model widens the TARGET parameter too, which makes
 * `(x: string) => void` NOT assignable to `(x?: string) => void`. tsc reports that; we are
 * still silent. Closing it is a REJECTION change and is priced separately.
 */
class OptionalParamContravarianceTest {

    private val matrix = """
        type ZzzOpt = (zzzX?: string) => void;
        type ZzzReq = (zzzX: string) => void;
        type ZzzUni = (zzzX: string | undefined) => void;
        declare const zzzOpt: ZzzOpt;
        declare const zzzReq: ZzzReq;
        declare const zzzUni: ZzzUni;
    """.trimIndent() + "\n"

    @Test
    fun `an optional source parameter relates to a target parameter that spells out undefined`() {
        diagnose(matrix + "const zzzA2: ZzzUni = zzzOpt;") should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `the indexed-access method-signature property shape from the server profile relates`() {
        diagnose(
            """
            interface ZzzCH {
                zzzRd?(
                    zzzRoot: string, zzzExt: readonly string[],
                    zzzExc: readonly string[] | undefined, zzzInc: readonly string[],
                    zzzD?: number
                ): string[]
            }
            interface ZzzGH { zzzRd: ZzzCH["zzzRd"] }
            interface ZzzSys {
                zzzRd(
                    zzzPath: string, zzzExt?: readonly string[], zzzExc?: readonly string[],
                    zzzInc?: readonly string[], zzzD?: number
                ): string[]
            }
            declare const zzzS: ZzzSys;
            const zzzG: ZzzGH = zzzS;
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `the same shape written as a plain function-type property relates too`() {
        diagnose(
            """
            interface ZzzGH3 {
                zzzRd: (
                    zzzRoot: string, zzzExt: readonly string[],
                    zzzExc: readonly string[] | undefined, zzzInc: readonly string[],
                    zzzD?: number
                ) => string[]
            }
            interface ZzzSys {
                zzzRd(
                    zzzPath: string, zzzExt?: readonly string[], zzzExc?: readonly string[],
                    zzzInc?: readonly string[], zzzD?: number
                ): string[]
            }
            declare const zzzS: ZzzSys;
            const zzzG3: ZzzGH3 = zzzS;
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `control - a REQUIRED source parameter still refuses a target parameter admitting undefined`() {
        val diagnostics = diagnose(matrix + "const zzzA5: ZzzUni = zzzReq;")
        val rows = diagnostics.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'ZzzReq' is not assignable to type 'ZzzUni'.")
        assert(rows[0].character == 7)
    }

    @Test
    fun `control - the three directions that already related are unmoved`() {
        diagnose(
            matrix +
                "const zzzA1: ZzzReq = zzzOpt;\n" +
                "const zzzA4: ZzzOpt = zzzUni;\n" +
                "const zzzA6: ZzzReq = zzzUni;",
        ) should {
            have(none { it.code == 2322 })
        }
    }
}
