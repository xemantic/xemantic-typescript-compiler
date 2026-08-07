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
 * Round 479 (tsc checkYieldExpression: `noImplicitAny &&
 * !expressionResultIsUnused(node)`): a statement-position `yield x;` discards
 * the yield's RESULT, so its implicit-any result draws no TS7057 (tsc harness
 * typeWriter.ts forEachASTNode); a yield whose result is CONSUMED still fires.
 */
class StatementYieldNoTs7057Test {

    @Test
    fun `statement-position yield draws no TS7057`() {
        diagnose(
            """
            function* walk(items: number[]) {
                for (const item of items) {
                    yield item;
                }
            }
            void walk;
            """,
        ) should {
            have(none { it.code == 7057 })
        }
    }

    @Test
    fun `negative control - a consumed yield result still fires`() {
        diagnose(
            """
            function* gen(items: number[]) {
                for (const item of items) {
                    const back = yield item;
                    void back;
                }
            }
            void gen;
            """,
        ) should {
            have(any { it.code == 7057 })
        }
    }
}
