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
 * Round 479: a MODULE file's top-level namespace never merges with another
 * module file's same-named class/function in real tsc (module scopes are
 * isolated) — the merged `globals` symbol is a mergeSymbolTable conflation
 * artifact, so TS2433 must not fire (compiler/debug.ts `namespace Debug` vs
 * harness fourslashInterfaceImpl's unrelated `class Debug`). Script-file
 * namespaces keep the check.
 */
class ModuleFileNamespaceSplitTest {

    @Test
    fun `module-file namespace with a same-named class in another module file draws no TS2433`() {
        diagnose(
            """
            // @module: nodenext
            // @filename: debug.ts
            export namespace Debug {
                export function assert(x: unknown): void { void x; }
            }
            // @filename: other.ts
            export class Debug {
                marker = 1;
            }
            """,
            directives = "// @strict: true",
        ) should {
            have(none { it.code == 2433 })
        }
    }

    @Test
    fun `negative control - script-file cross-file namespace and class still fire`() {
        diagnose(
            """
            // @filename: a.ts
            class Debug {
                marker = 1;
            }
            // @filename: b.ts
            namespace Debug {
                export function assert(x: unknown): void { void x; }
            }
            """,
        ) should {
            have(any { it.code == 2433 })
        }
    }
}
