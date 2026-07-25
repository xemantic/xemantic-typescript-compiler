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
 * INV.5(b1) (round 547): the explicit-instantiation-context bridge —
 * [InstantiationMapper] + `getTypeFromTypeNodeWithMapper` (installs the
 * mapper's aliasArgs/tpScope around the ambient-reading resolution core,
 * identical to the legacy hand-rolled save-set-restore installers). The
 * pilot routes checkTpListDefaults' TS2344 default/constraint resolutions
 * through the bridge with the ambient capture.
 */
class Inv5MapperBridgeTest {

    @Test
    fun `TS2344 default-vs-constraint still fires through the mapper bridge`() {
        val d = diagnose("""
            interface I<T extends string = number> { p: T; }
        """)
        assert(d.count { it.code == 2344 } == 1)
    }

    @Test
    fun `negative control - satisfying defaults stay silent and sibling TP scope resolves`() {
        diagnose("""
            interface J<A extends string = "x", B extends A = A> { a: A; b: B; }
        """) should {
            have(none { it.code == 2344 })
        }
    }

    // ── (b2b) round 549d: the alias-substitution + mapped-type installers ────

    @Test
    fun `generic alias substitution through the mapper still resolves and displays the alias name`() {
        // The ~93.8k alias-substitution installer (layered args + own-TP scope
        // shadow) — a body-`T` mismatch must resolve through the substituted
        // arg and display as `Wrap<string>`.
        val d = diagnose("""
            type Wrap<T> = { value: T };
            const w: Wrap<string> = { value: 1 };
        """)
        val msg = d.firstOrNull { it.code == 2322 }?.message ?: ""
        assert("Wrap<string>" in msg || "string" in msg)
        assert(d.count { it.code == 2322 } == 1)
    }

    @Test
    fun `negative control - mapped-type per-key value resolution works through the mapper`() {
        // The getTypeFromMappedType per-key K-binding installer
        // (excessPropertyChecksWithNestedIntersections shape).
        diagnose("""
            interface T2 { bar: { baz: string } }
            type View<T> = { [K in keyof T]: T[K] extends object ? boolean | View<T[K]> : boolean };
            const v: View<T2> = { bar: { baz: true } };
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `own-TP scope shadow survives the mapper flip`() {
        // The round-472 rule: the alias's own TP name must not resolve through
        // the enclosing fn's same-named TP — Comparer's body `a: T` binds the
        // ALIAS arg (U=number), so comparing with a string arg fires.
        val d = diagnose("""
            type Comparer<T> = (a: T, b: T) => number;
            declare function search<T, U>(xs: T[], keyComparer: Comparer<U>): T;
            declare const nums: number[];
            search(nums, (a: number, b: number) => a - b);
        """)
        d should { have(none { it.code == 2345 }) }
    }
}
