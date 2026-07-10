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
 * Round 471: an object-literal member whose value was an OPTIONAL-member read
 * (`const comparer = { typeOrder: preferences.organizeImportsTypeOrder }`) is
 * `T | undefined` in tsc — optionality is a symbol attribute our member typing
 * drops (the round-424 gap), so a later write `comparer.typeOrder =
 * <X | undefined>` FP'd TS2322 (tsc organizeImports.ts:115). The write TARGET
 * is widened with `| undefined` (suppression-only) via
 * objLitMemberValueWasOptionalRead in checkPropertyAccessAssignment.
 */
class OptionalMemberReadObjLitWriteTargetTest {

    private val prelude = """
        interface Prefs { order?: "last" | "inline" | "first"; }
        declare const prefs: Prefs;
    """.trimIndent()

    @Test
    fun `writing X or undefined to an optional-read objlit member relates`() {
        diagnose(
            prelude + """
            declare function detect(): { order: "last" | "inline" | "first" | undefined } | undefined;
            function g() {
                const comparer = { order: prefs.order };
                const d = detect();
                if (d) {
                    const { order } = d;
                    comparer.order = comparer.order ?? order;
                }
                return comparer;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `writing a bare undefined to an optional-read objlit member relates`() {
        diagnose(
            prelude + """
            function g() {
                const comparer = { order: prefs.order };
                comparer.order = undefined;
                return comparer;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a wrong-category write to an optional-read member still fires`() {
        diagnose(
            prelude + """
            declare const n: number;
            function g() {
                const comparer = { order: prefs.order };
                comparer.order = n;
                return comparer;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a required-member read does not widen the write target`() {
        diagnose(
            """
            interface PrefsReq { order: "last" | "inline" | "first"; }
            declare const prefsReq: PrefsReq;
            declare const maybe: "last" | undefined;
            function g() {
                const comparer = { order: prefsReq.order };
                comparer.order = maybe;
                return comparer;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
