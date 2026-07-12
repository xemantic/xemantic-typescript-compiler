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
 * Round 481: the round-445 spread-poisons-to-any rule now lives at the TYPE
 * level — getTypeOfObjectLiteral returns `any` for a literal spreading an
 * any/error value (tsc semantics), so a NESTED member value
 * `{ ...unresolvable, prop }` can never mismatch its declared member type
 * (harnessLanguageService's `typingsInstaller: { ...nullTypingsInstaller,
 * globalTypingsCacheLocation }` FP'd both the per-property leaf and, once that
 * was suppressed, the coarse whole-object relation).
 */
class NestedObjLitUnresolvedSpreadTest {

    @Test
    fun `a nested member value spreading an any value never mismatches its member type`() {
        diagnose(
            """
            interface Installer { attach(): void; detach(): void; }
            interface Opts { installer: Installer; n: number; }
            declare const someAny: any;
            function make(): Opts {
                const opts: Opts = {
                    installer: {
                        ...someAny.nullInstaller,
                        cacheLocation: "x",
                    },
                    n: 2,
                };
                return opts;
            }
            """,
        ) should {
            have(none { it.code == 2322 || it.code == 2739 || it.code == 2740 || it.code == 2741 })
        }
    }

    @Test
    fun `negative control - a spread-free nested member missing properties still fires`() {
        diagnose(
            """
            interface Installer { attach(): void; detach(): void; }
            interface Opts { installer: Installer; n: number; }
            function make(): Opts {
                const opts: Opts = {
                    installer: {} as unknown as Installer,
                    n: 2,
                };
                const bad: Installer = { attach() {} };
                return opts;
            }
            """,
        ) should {
            have(any { it.code == 2741 || it.code == 2739 || it.code == 2322 })
        }
    }
}
