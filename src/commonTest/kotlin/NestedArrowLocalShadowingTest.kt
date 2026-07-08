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
 * Round 447 (self-compile burn-down): a NESTED arrow / function-expression body's own annotated
 * `let`/`const x` must SHADOW an outer same-named binding for property-access reads inside that body.
 * The property-access walker recorded params when entering an arrow/fn-expr body but never called
 * `applyBodyLocalShadowing` for the body's local declarations, so completions.ts's inner
 * `let exportInfo: SymbolExportInfo | FutureSymbolExportInfo` (in a nested callback) did NOT shadow the
 * enclosing `const exportInfo: ExportInfoMap`, and `exportInfo.exportKind` FP-fired TS2339 "does not
 * exist on type 'ExportInfoMap'" (×8 on the services profile → 0).
 */
class NestedArrowLocalShadowingTest {

    private fun diag(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `a nested arrow's inner annotated local shadows an outer same-named binding - no TS2339`() {
        diag(
            """
            // @strict: true
            interface Outer { count: number; }
            interface Inner { exportKind: number; symbol: string; }
            declare function getOuter(): Outer;
            declare function forEach(cb: (info: Inner[]) => void): void;
            export function run() {
                const exportInfo = getOuter();
                forEach((info) => {
                    let exportInfo: Inner = info[0];
                    return exportInfo.exportKind;
                });
                return exportInfo.count;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a nested function-expression's inner annotated local shadows an outer binding - no TS2339`() {
        diag(
            """
            // @strict: true
            interface Outer { count: number; }
            interface Inner { exportKind: number; }
            declare function getOuter(): Outer;
            export function run() {
                const exportInfo = getOuter();
                const cb = function (info: Inner) {
                    let exportInfo: Inner = info;
                    return exportInfo.exportKind;
                };
                return exportInfo.count;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `the inner shadow does not leak - an outer read after the arrow keeps the outer type - TS2339`() {
        // Firewall: after the nested arrow, `exportInfo` reads must resolve to `Outer` (not `Inner`),
        // so accessing an Inner-only member fires; AND a genuine miss on the inner type fires too.
        diag(
            """
            // @strict: true
            interface Outer { count: number; }
            interface Inner { exportKind: number; }
            declare function getOuter(): Outer;
            declare function forEach(cb: (info: Inner[]) => void): void;
            export function run() {
                const exportInfo = getOuter();
                forEach((info) => {
                    let exportInfo: Inner = info[0];
                    const bad = exportInfo.nope;
                });
                const bad2 = exportInfo.exportKind;
            }
            """
        ) should {
            have(any { it.code == 2339 && it.message.contains("nope") })
            have(any { it.code == 2339 && it.message.contains("exportKind") })
        }
    }
}
