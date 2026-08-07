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
 * Round 477 (Blocker #3): a same-named `const enum X` declared in TWO different
 * MODULE files (server protocol.ts's `NewLineKind { Crlf, Lf }` vs compiler
 * types.ts's `NewLineKind { CarriageReturnLineFeed, LineFeed }`) merges into ONE
 * globals symbol, so a bare-enum discriminant annotation read "all members"
 * across BOTH declarations and a switch covering one complete file-version
 * FP'd TS2366. Real tsc never merges module-scoped enums —
 * [coveredExhaustsConflatedEnumSubset] relaxes the exhaustiveness comparison to
 * per-file member subsets ([conflatedEnumFileSubsets]).
 *
 * Companion: [unionDiscriminantKeysFromAnnotation] — the AST-side fallback when
 * the union member types are ALSO conflation-poisoned (protocol.ts's `type
 * CompilerOptions = …` alias shadows compiler types.ts's interface via the
 * last-wins Interface+TypeAlias merge, so the resolved member is any/error and
 * the resolved-type walk bails).
 */
class ConflatedEnumSwitchExhaustiveTest {

    private val decls = """
        // @module: nodenext
        // @strict: true
        // @noImplicitReturns: true
        // @filename: types.ts
        export const enum NewLineKind {
            CarriageReturnLineFeed = 0,
            LineFeed = 1,
        }
        export interface CompilerOptions {
            newLine?: NewLineKind;
        }
        export interface PrinterOptions {
            newLine?: NewLineKind;
        }
        // @filename: protocol.ts
        export const enum NewLineKind {
            Crlf = "Crlf",
            Lf = "Lf",
        }
        export type CompilerOptions = NewLineKind | string;
        export const dummy = 1;
    """.trimIndent()

    @Test
    fun `switch covering one file's complete enum version is exhaustive`() {
        diagnose(
            decls + """

            // @filename: utilities.ts
            import { CompilerOptions, PrinterOptions, NewLineKind } from "./types.js";
            export function getNewLineCharacter(options: CompilerOptions | PrinterOptions): string {
                switch (options.newLine) {
                    case NewLineKind.CarriageReturnLineFeed:
                        return "\r\n";
                    case NewLineKind.LineFeed:
                    case undefined:
                        return "\n";
                }
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2366 || it.code == 7030 })
        }
    }

    @Test
    fun `negative control - a switch missing one member of the file version still fires`() {
        diagnose(
            decls + """

            // @filename: utilities.ts
            import { CompilerOptions, PrinterOptions, NewLineKind } from "./types.js";
            export function getNewLineCharacter(options: CompilerOptions | PrinterOptions): string {
                switch (options.newLine) {
                    case NewLineKind.CarriageReturnLineFeed:
                        return "\r\n";
                    case undefined:
                        return "\n";
                }
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2366 })
        }
    }

    @Test
    fun `negative control - a MIXED cover spanning both file versions is not exhaustive`() {
        diagnose(
            decls + """

            // @filename: other.ts
            import { NewLineKind as ProtoKind } from "./protocol.js";
            import { CompilerOptions, PrinterOptions, NewLineKind } from "./types.js";
            export function f(options: CompilerOptions | PrinterOptions): string {
                switch (options.newLine) {
                    case NewLineKind.CarriageReturnLineFeed:
                    case undefined:
                        return "\n";
                    case ProtoKind.Crlf:
                        return "x";
                }
            }
            """.trimIndent(),
        ) should {
            // CarriageReturnLineFeed + Crlf covers NEITHER file-version completely.
            have(any { it.code == 2366 })
        }
    }
}
