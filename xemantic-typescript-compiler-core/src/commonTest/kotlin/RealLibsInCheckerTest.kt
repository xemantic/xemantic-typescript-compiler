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
 * M2.1(d) (round 390): `// @useRealLibs: true` routes the checker's default
 * library through [RealLibSnapshots] (the real TypeScript lib .d.ts set)
 * instead of the embedded BUILTIN_LIB_SOURCE. These are end-to-end smoke pins:
 * a whole compile against the real es5 layer (and a multi-file lib selection
 * whose cross-file interface merging must work) stays clean, while unresolved
 * names still error — proving the real lib table actually took the embedded
 * one's place rather than being merged on top of it or dropped.
 */
class RealLibsInCheckerTest {

    @Test
    fun `core es5 globals resolve through the real lib`() {
        diagnose(
            """
            // @useRealLibs: true
            const n: number = Math.floor(1.5);
            const j: string = JSON.stringify({ a: 1 });
            const p: number = parseInt("42");
            const arr: number[] = [1, 2, 3];
            """,
            directives = "",
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `multi-file lib selection merges interfaces across lib layers`() {
        // es2016's Array.includes lives in es2016.array.include.d.ts and must MERGE
        // onto es5's interface Array<T> — the cross-file lib merge the snapshot's
        // per-file binds rely on.
        diagnose(
            """
            // @useRealLibs: true
            // @lib: es2016
            // @target: es2016
            const b: boolean = [1, 2, 3].includes(2);
            """,
            directives = "",
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `unresolved names still error under real libs`() {
        diagnose(
            """
            // @useRealLibs: true
            const x = definitelyNotAGlobal;
            """,
            directives = "",
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `embedded lib remains the default without the flag`() {
        diagnose("const n: number = Math.floor(1.5);", directives = "") should {
            have(isEmpty())
        }
    }
}
