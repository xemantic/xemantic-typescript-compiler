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
 * (M0.4, round 624): pins for the checkArrayPushDiscriminatedUnionElements
 * (B473/B482/B487*) spine migration — the legacy scan's REACH quirks (only
 * expression-statement / var-initializer / return / if-condition call
 * positions; try/switch/labeled/throw bodies and every nested function
 * EXPRESSION unreached) and its localAnns scoping (list-level recording,
 * Block-copy boundaries). All expectations verified against the
 * pre-migration walker; the `[].splice(start, delete, item)` B487 shape is
 * the deterministic emission probe (TS2345 'not assignable to never').
 */
class M04ArrayPushSpineMigrationTest {

    @Test
    fun `empty-array splice value item fires TS2345 at statement position`() {
        diagnose(
            """
            [].splice(1, 0, "x")
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `empty-array splice fires in return position`() {
        diagnose(
            """
            function f() {
                return [].splice(1, 0, "x")
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `empty-array splice fires as a bare if-branch statement`() {
        diagnose(
            """
            declare const c: boolean
            if (c) [].splice(1, 0, "x")
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a try block is unreached by the scan`() {
        diagnose(
            """
            try {
                [].splice(1, 0, "x")
            } catch (e) {}
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a switch clause is unreached by the scan`() {
        diagnose(
            """
            declare const k: number
            switch (k) {
                case 0:
                    [].splice(1, 0, "x")
                    break
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - an arrow body is unreached by the scan`() {
        diagnose(
            """
            const g = () => [].splice(1, 0, "x")
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a nested expression position is unreached by the scan`() {
        diagnose(
            """
            const n = ([].splice(1, 0, "x")).length
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `push of a no-common-properties arg against a weak element type fires via the local annotation`() {
        diagnose(
            """
            interface Opts { a?: number; b?: string }
            function f(err: { msg: string }) {
                let changes: Opts[] = []
                changes.push(err)
            }
            """
        ) should {
            have(any { it.code == 2559 })
        }
    }

    @Test
    fun `negative control - a block-scoped receiver annotation does not leak past its block`() {
        diagnose(
            """
            interface Opts { a?: number; b?: string }
            function f(err: { msg: string }) {
                {
                    let changes: Opts[] = []
                }
                changes.push(err)
            }
            """
        ) should {
            have(none { it.code == 2559 })
        }
    }
}
