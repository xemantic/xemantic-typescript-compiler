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
 * INV.4(b) batch 12 (round 519): TS1042/TS1184 object-literal member
 * modifiers migrated onto the check spine from the deleted
 * `checkObjectLiteralModifiers` / `walkForObjectLiteralModifiers` /
 * `walkExprForObjectLiteralModifiers` walk family. The per-property emission
 * became an ObjectLiteralExpression-enter handler; nested object literals
 * are visited by their own enters. Widenings (fail pre-migration):
 * parameter-default and spread-operand positions the old expression walk
 * never descended — position-independent tsc grammar
 * (checkGrammarObjectLiteralExpression runs per object literal).
 */
class Inv4SpineBatch12Test {

    // ── old reach: pre-verified against the OLD walker ──────────────────────

    @Test
    fun `access modifier on an object literal method fires TS1042 and TS1184`() {
        diagnose(
            """
            const o = { public m() {} };
            """,
        ) should {
            have(any { it.code == 1042 })
            have(any { it.code == 1184 })
        }
    }

    @Test
    fun `access modifier on an object literal accessor fires TS1042 without TS1184`() {
        diagnose(
            """
            const o = { public get g() { return 1; } };
            """,
        ) should {
            have(any { it.code == 1042 })
            have(none { it.code == 1184 })
        }
    }

    @Test
    fun `modifier on a property assignment fires TS1042`() {
        diagnose(
            """
            const o = { public x: 1 };
            """,
        ) should {
            have(any { it.code == 1042 })
        }
    }

    @Test
    fun `nested object literal method modifier fires TS1042`() {
        diagnose(
            """
            const o = { inner: { private m() {} } };
            """,
        ) should {
            have(any { it.code == 1042 })
        }
    }

    @Test
    fun `object literal in a return statement fires TS1042`() {
        diagnose(
            """
            function f() {
                return { protected m() {} };
            }
            """,
        ) should {
            have(any { it.code == 1042 })
        }
    }

    // ── widenings: FAIL pre-migration (old walk never reached these) ────────

    @Test
    fun `widening - object literal in a parameter default fires TS1042`() {
        diagnose(
            """
            function f(x = { public m() {} }) {}
            """,
        ) should {
            have(any { it.code == 1042 })
        }
    }

    @Test
    fun `widening - object literal as a spread operand fires TS1042`() {
        diagnose(
            """
            const o = { ...({ public m() {} }) };
            """,
        ) should {
            have(any { it.code == 1042 })
        }
    }

    // ── negative controls ────────────────────────────────────────────────────

    @Test
    fun `negative control - plain object literal methods are fine`() {
        diagnose(
            """
            const o = { m() {}, get g() { return 1; }, x: 1 };
            """,
        ) should {
            have(none { it.code == 1042 })
            have(none { it.code == 1184 })
        }
    }

    @Test
    fun `negative control - class member access modifiers are fine`() {
        diagnose(
            """
            class C { public m() {} private x = 1; }
            """,
        ) should {
            have(none { it.code == 1042 })
            have(none { it.code == 1184 })
        }
    }

    @Test
    fun `negative control - async object literal method is fine`() {
        diagnose(
            """
            const o = { async m() { return 1; } };
            """,
        ) should {
            have(none { it.code == 1042 })
        }
    }
}
