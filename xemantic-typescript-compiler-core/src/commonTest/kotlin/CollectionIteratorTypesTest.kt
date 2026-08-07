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
 * `SetIterator<T>` / `MapIterator<T>` / `ArrayIterator<T>` are declared in
 * `lib.es2015.iterable.d.ts` (they are the return types of `Set`/`Map`/`Array`'s
 * `keys()`/`values()`/`entries()`), so they resolve at es2020 and up. The embedded lib
 * lacked them, so tsc's own `core.ts` (`function* getElementIterator(): SetIterator<T>`)
 * and `transformers/utilities.ts` (`MapIterator`) FP'd TS2552/TS2304. Added as (arity-1)
 * interfaces to the embedded lib; corpus-neutral (no generated baseline references them).
 */
class CollectionIteratorTypesTest {

    @Test
    fun `SetIterator MapIterator ArrayIterator resolve as type names - no TS2304 or TS2552`() {
        diagnose(
            """
            function* setGen(): SetIterator<number> { yield 1; }
            function* mapGen(): MapIterator<string> { yield "a"; }
            function* arrGen(): ArrayIterator<boolean> { yield true; }
            let s: SetIterator<number> = setGen();
            let m: MapIterator<string> = mapGen();
            let a: ArrayIterator<boolean> = arrGen();
            """,
            directives = "",
        ) should {
            have(none { it.code == 2304 || it.code == 2552 })
        }
    }

    @Test
    fun `a genuinely unknown iterator type still errors - negative control`() {
        diagnose("let x: NotAnIterator<number> = null as any;", directives = "") should {
            have(any { it.code == 2304 || it.code == 2552 })
        }
    }
}
