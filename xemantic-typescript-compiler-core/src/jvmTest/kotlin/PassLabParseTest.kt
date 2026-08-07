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
import kotlin.test.Test

/**
 * M0.1 (round 619): pins the PURE parser of the pass-lab file. The file-loading
 * side effects (PassLab.ensureLoaded) are deliberately NOT exercised here — a
 * once-per-JVM global toggle poisons the whole test fork, so ensureLoaded is
 * covered only by the manual triage protocol runs (PLAN-PHASE-5.md (M0.1)).
 */
class PassLabParseTest {

    @Test
    fun `parses census, disables, comments, and blank lines`() {
        val config = parsePassLabLines(
            listOf(
                "# tail triage batch 1",
                "",
                "census",
                "disable checkObjectSpreadInvalidTypes",
                "disable checkSymbolToStringConversions",
                "  disable checkAbstractClassInstantiation  ",
                "disable ", // empty name — ignored
                "unknown directive is ignored",
            ),
        )
        assert(config.census == true)
        assert(
            config.disabled == setOf( "checkObjectSpreadInvalidTypes", "checkSymbolToStringConversions", "checkAbstractClassInstantiation", )
        )
    }

    @Test
    fun `negative control - an empty file configures nothing`() {
        val config = parsePassLabLines(emptyList())
        assert(config.census == false)
        assert(config.disabled.isEmpty())
    }
}
