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
 * Round 471: a MODULE file's own `interface Symbol` (tsc compiler/types.ts)
 * merges with the same-named LIB global via mergeSymbolTable, so the merged
 * interface demands lib-only members (`description` from es2019) that tsc
 * never requires — tsc's module-scoped interface shadows the lib global for
 * importing files. isLibPhantomMemberOfModuleInterface skips members whose
 * declarations are ALL lib declarations when the interface is also declared
 * top-level in a module file (FN-not-FP: a file genuinely meaning the lib
 * interface loses a real missing-member error, accepted). Cleared
 * services.ts:656 (TS2420 SymbolObject) + :725 (TS2345) on the services
 * profile.
 */
class LibPhantomMemberModuleInterfaceTest {

    @Test
    fun `a class implementing a module-declared Symbol interface skips lib-phantom members`() {
        diagnose(
            """
            // @filename: types.ts
            export interface Symbol {
                flags: number;
                getName(): string;
            }
            // @filename: services.ts
            import { Symbol } from "./types";
            class SymbolObject implements Symbol {
                flags: number = 0;
                getName(): string { return ""; }
                check(checker: (s: Symbol) => void) {
                    checker(this);
                }
            }
            """
        ) should {
            have(none { it.code == 2420 || it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a genuinely missing module-interface member still fires`() {
        diagnose(
            """
            // @filename: types.ts
            export interface Symbol {
                flags: number;
                getName(): string;
            }
            // @filename: services.ts
            import { Symbol } from "./types";
            class BadSymbol implements Symbol {
                flags: number = 0;
            }
            """
        ) should {
            have(any { it.code == 2420 })
        }
    }
}
