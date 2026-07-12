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
 * Round 479 (tsc isUntypedFunctionCall): a value of the global lib `Function`
 * type IS callable — the call is untyped and returns any
 * (`new Function("…")()`, fourslashImpl.ts verifyEval).
 */
class FunctionTypeUntypedCallTest {

    @Test
    fun `calling a new Function instance draws no TS2349`() {
        diagnose(
            """
            const evaluation = new Function("return 1 + 1;")();
            void evaluation;
            """,
        ) should {
            have(none { it.code == 2349 })
        }
    }

    @Test
    fun `negative control - calling a user class instance still fires`() {
        diagnose(
            """
            class Widget { size = 1; }
            const w = (new Widget())();
            void w;
            """,
        ) should {
            have(any { it.code == 2349 })
        }
    }
}
