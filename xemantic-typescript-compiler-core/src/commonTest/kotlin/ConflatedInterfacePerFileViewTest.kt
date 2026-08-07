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
 * Round 473 (Blocker #3, the tsc server-profile protocol.ts family): a top-level
 * `interface X` declared in ≥2 module files (server/protocol.ts's `Diagnostic
 * { start: Location }` vs compiler/types.ts's `Diagnostic { start: number |
 * undefined }`) merges into ONE chimera globals symbol. References now resolve
 * the PER-FILE view their context selects ([conflatedPerFileInterfaceType]):
 * `protocol.Diagnostic` → the namespace import's module, a bare `Diagnostic` →
 * the checking file's own/imported declaration; heritage (`FileSpan extends
 * TextSpan`) resolves from the derived declaration's file.
 */
class ConflatedInterfacePerFileViewTest {

    private val decls = """
        // @module: nodenext
        // @filename: types.ts
        export interface Diagnostic {
            code: number;
            start: number | undefined;
            messageText: string;
        }
        export interface TextSpan {
            start: number;
            length: number;
        }
        export interface DiagnosticWithLocation extends Diagnostic {
            start: number;
        }
        // @filename: protocol.ts
        export interface Location {
            line: number;
            offset: number;
        }
        export interface TextSpan {
            start: Location;
            end: Location;
        }
        export interface FileSpan extends TextSpan {
            file: string;
        }
        export interface Diagnostic {
            start: Location;
            end: Location;
            text: string;
        }
    """.trimIndent()

    @Test
    fun `bare and qualified references resolve each file's own view`() {
        diagnose(
            decls + """

            // @filename: session.ts
            import { Diagnostic, DiagnosticWithLocation, TextSpan } from "./types.js";
            import * as protocol from "./protocol.js";
            declare function positionToLineOffset(pos: number): protocol.Location;
            export function formatDiag(diag: Diagnostic): protocol.Diagnostic {
                return {
                    start: positionToLineOffset(diag.start!),
                    end: positionToLineOffset(diag.start! + 1),
                    text: diag.messageText,
                };
            }
            export function toFileSpan(file: string, span: TextSpan): protocol.FileSpan {
                return {
                    start: positionToLineOffset(span.start),
                    end: positionToLineOffset(span.start + span.length),
                    file,
                };
            }
            export function widen(d: DiagnosticWithLocation): Diagnostic {
                return d;
            }
            """.trimIndent(),
        ) should {
            have(none {
                it.code == 2322 || it.code == 2739 || it.code == 2740 ||
                    it.code == 2345 || it.code == 2365 || it.code == 2430
            })
        }
    }

    @Test
    fun `negative control - the OTHER file's member is absent from the per-file view`() {
        diagnose(
            decls + """

            // @filename: session.ts
            import { Diagnostic } from "./types.js";
            export function bad(diag: Diagnostic): void {
                diag.text;
            }
            """.trimIndent(),
        ) should {
            // `text` exists only on protocol.ts's Diagnostic — the merged chimera
            // would resolve it silently; the per-file types.ts view must fire.
            have(any { it.code == 2339 })
        }
    }
}
