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
 * (CHK.62) An object literal that SPREADS an INTERSECTION lost every one of the
 * intersection's members: [Checker.spreadGuaranteedProps] handled `Type.Union` and
 * `Type.Object` and answered `emptyMap()` for `Type.Intersection`, so
 * `{ ...mk(), insertString }` with `mk(): FileLocationRequestArgs & { endLine: number;
 * endOffset: number }` reported TS2739/TS2740 for the five properties the spread does
 * supply (tsc harness `client.ts:242`, silent in tsc 7.0.2).
 *
 * The controls are the two SHAPES the arm did not have to be taught — a spread of a
 * plain interface and of a plain object type were already correct — plus the negative
 * control that a genuinely missing property is still reported. Without them a pin
 * asserting silence cannot tell "the intersection is merged" from "this literal is
 * never checked".
 */
class SpreadOfIntersectionTest {

    private val prelude = """
        interface ZzzBase { zzzFile: string; zzzLine: number; zzzOff: number }
        interface ZzzFmt extends ZzzBase { zzzEndLine: number; zzzEndOff: number }
        interface ZzzChg extends ZzzFmt { zzzIns?: string }
        declare function zzzMkIx(): ZzzBase & { zzzEndLine: number; zzzEndOff: number };
        declare function zzzMkIf(): ZzzFmt;
        declare const zzzObj: {
            zzzFile: string; zzzLine: number; zzzOff: number;
            zzzEndLine: number; zzzEndOff: number
        };
    """.trimIndent() + "\n"

    @Test
    fun `an object literal spreading an intersection keeps the intersection's members`() {
        diagnose(
            prelude + """
            const zzzX: ZzzChg = { ...zzzMkIx(), zzzIns: "x" };
            """.trimIndent(),
        ) should {
            have(none { it.code == 2739 })
            have(none { it.code == 2740 })
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `control - a spread of a plain interface was already correct`() {
        diagnose(
            prelude + """
            const zzzY: ZzzChg = { ...zzzMkIf(), zzzIns: "x" };
            """.trimIndent(),
        ) should {
            have(none { it.code == 2739 })
            have(none { it.code == 2740 })
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `control - a spread of a plain object type was already correct`() {
        diagnose(
            prelude + """
            const zzzZ: ZzzChg = { ...zzzObj, zzzIns: "x" };
            """.trimIndent(),
        ) should {
            have(none { it.code == 2739 })
            have(none { it.code == 2740 })
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a property NO constituent of the intersection supplies is still missing`() {
        val diagnostics = diagnose(
            prelude + """
            interface ZzzNeed extends ZzzChg { zzzAbsent: number }
            const zzzW: ZzzNeed = { ...zzzMkIx(), zzzIns: "x" };
            """.trimIndent(),
        )
        val missing = diagnostics.filter { it.code == 2739 || it.code == 2740 || it.code == 2741 }
        assert(missing.size == 1)
        // tsc 7.0.2 byte-for-byte: the SOURCE display names the five members the
        // intersection spread supplies, so this row is also the positive's strongest
        // form — on the parent binary it is TS2740 naming those five as MISSING.
        assert(missing[0].code == 2741)
        assert(
            missing[0].message ==
                "Property 'zzzAbsent' is missing in type '{ zzzFile: string; zzzLine: number; " +
                "zzzOff: number; zzzEndLine: number; zzzEndOff: number; zzzIns: string; }' " +
                "but required in type 'ZzzNeed'."
        )
    }
}
