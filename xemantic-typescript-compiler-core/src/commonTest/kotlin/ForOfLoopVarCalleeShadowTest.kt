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
 * Round 479: a for-of/for-in LOOP VARIABLE shadows a same-named outer/global
 * binding in the call-types walker's arg-position typing — evaluatorImpl.ts's
 * top-level `for (const symbolName of symbolNames)` otherwise resolved
 * arg-position `symbolName` through merged globals to utilitiesPublic's
 * `function symbolName(symbol: Symbol): string` → FP TS2345.
 */
class ForOfLoopVarCalleeShadowTest {

    @Test
    fun `top-level for-of loop var shadows a cross-file function in arg position`() {
        diagnose(
            """
            // @module: nodenext
            // @filename: utils.ts
            export interface Sym { name: string; }
            export function symbolName(symbol: Sym): string { return symbol.name; }
            // @filename: evaluator.ts
            declare function defineIt(target: object, name: string): void;
            const symbolNames = ["iterator", "asyncIterator"];
            const FakeSymbol = {};
            for (const symbolName of symbolNames) {
                defineIt(FakeSymbol, symbolName);
            }
            export {};
            """,
            directives = "// @strict: true",
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a wrong-typed loop-body arg still fires`() {
        diagnose(
            """
            declare function wantsNumber(n: number): void;
            const names = ["a", "b"];
            for (const nm of names) {
                wantsNumber("nope");
            }
            """,
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
