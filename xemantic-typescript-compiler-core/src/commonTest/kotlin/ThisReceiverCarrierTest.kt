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
 * (CHK.61)(a) A BARE `this` RECEIVER CARRIED NO TYPE, SO EVERY MEMBER READ THROUGH IT WAS
 * `any` — AND `any` IS LEGAL EVERYWHERE, so the whole family failed in the FALSE-NEGATIVE
 * direction with nothing to see. `computeRawTypeOfPropertyAccess` types the receiver with
 * `getTypeOfExpression`, which answers `any` for `Identifier("this")` (there is no `this`
 * binding in `currentLocalTypes` for an ordinary method body);
 * [Checker.thisReceiverCarrierType] supplies [Checker.currentClassForThis]'s declared
 * instance type instead, and only where the receiver already typed `any`/`error`.
 *
 * Measured on `build/chk60/br/b2.ts`: **3 of tsc 7.0.2's 7 rows before, all 7 after, at
 * tsc's own positions**. The 8-profile grid is `added=0 removed=0` — which it was NOT
 * before (CHK.62)/(CHK.62b)/(CHK.61)(1) closed the four gaps the `any` was hiding.
 *
 * RESIDUE, deliberately not pinned (round 765): the OPTIONAL member's row reads
 * `Type 'number'` where tsc reads `Type 'number | undefined'` — that is (CHK.61)(b), the
 * dropped `| undefined` at `computeRawTypeOfPropertyAccess`'s three `prop != null` returns,
 * still open and priced separately.
 */
class ThisReceiverCarrierTest {

    private val cls = """
        class ZzzC8 {
          zzzReq: number = 1
          zzzOpt?: number
          zzzM() {
            const zzzA: string = this.zzzReq
            const zzzE: string = this.zzzOpt
          }
          zzzN(): string { return this.zzzReq }
        }
    """.trimIndent() + "\n"

    @Test
    fun `a this member read is typed by the enclosing class and not by any`() {
        val rows = diagnose(cls).filter { it.code == 2322 }
        assert(rows.size == 3)
        // `const zzzA: string = this.zzzReq` — tsc 7.0.2 reports (5,11) on the same shape.
        assert(rows[0].line == 5)
        assert(rows[0].character == 11)
        assert(rows[0].message == "Type 'number' is not assignable to type 'string'.")
        // the OPTIONAL member; the message is (CHK.61)(b) residue, the ROW is the point
        assert(rows[1].line == 6)
        assert(rows[1].character == 11)
        // the method-RETURN position
        assert(rows[2].line == 8)
        assert(rows[2].character == 20)
        assert(rows[2].message == "Type 'number' is not assignable to type 'string'.")
    }

    @Test
    fun `a this member read in an ARROW body inside the method is typed too`() {
        // An arrow preserves `this`, so the carrier must survive the nested body.
        diagnose(
            """
            class ZzzC7 {
              zzzReq: number = 1
              zzzM() { const zzzF = () => { const zzzA: string = this.zzzReq; return zzzA }; return zzzF }
            }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `control - an ordinary object receiver was always typed and is unmoved`() {
        val rows = diagnose(
            """
            declare const zzzObj: { zzzReq: number };
            const zzzC: string = zzzObj.zzzReq;
            """,
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'number' is not assignable to type 'string'.")
    }

    @Test
    fun `control - a this OUTSIDE any class has no carrier and stays silent`() {
        // There is no enclosing class, so `currentClassForThis` is null and the receiver
        // falls back to `any` exactly as before. tsc reports TS2683 here (an implicit-any
        // `this`), which is a separate, unimplemented rule - the point of this control is
        // that the carrier does NOT invent a type.
        diagnose(
            """
            function zzzFree() { const zzzA: string = this.zzzReq; return zzzA }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `control - a class member that genuinely IS a string is accepted`() {
        // The carrier must not manufacture a rejection either.
        diagnose(
            """
            class ZzzC6 { zzzS: string = "a"; zzzM() { const zzzA: string = this.zzzS; return zzzA } }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }
}
