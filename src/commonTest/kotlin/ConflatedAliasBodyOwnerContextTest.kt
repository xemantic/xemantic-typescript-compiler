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
 * Round 474 (Blocker #3, the tsc server-profile extractSymbol.ts family): a type
 * ALIAS whose body references a CONFLATED interface name (`type RangeToExtract =
 * { errors: readonly Diagnostic[] } | …` where `Diagnostic` is declared top-level
 * in ≥2 module files) resolves the body in the alias's DECLARING file's view
 * ([resolveTypeAliasBodyWithOwnerContext]) — a lazy null-context first touch
 * previously baked the merged chimera into [declaredTypes], so a conforming
 * `{ errors: [createFileDiagnostic(…)] }` return FP'd TS2322. Companion: the
 * `readonly Diagnostic[]` TypeOperator node must not enter the structural
 * [nodeTypes] cache (the [isConflatedInterfaceRefNode] TypeOperator arm).
 */
class ConflatedAliasBodyOwnerContextTest {

    private val decls = """
        // @module: nodenext
        // @filename: types.ts
        export interface Diagnostic {
            category: number;
            code: number;
            messageText: string;
        }
        export interface DiagnosticWithLocation extends Diagnostic {
            file: string;
            start: number;
            length: number;
        }
        // @filename: utilities.ts
        import { DiagnosticWithLocation } from "./types.js";
        export function createFileDiagnostic(start: number, length: number, msg: string): DiagnosticWithLocation {
            return { category: 1, code: 2, messageText: msg, file: "f", start, length };
        }
        // @filename: protocol.ts
        export interface Diagnostic {
            start: string;
            end: string;
            text: string;
        }
    """.trimIndent()

    @Test
    fun `objlit return against a union alias of typelits over an imported conflated interface`() {
        diagnose(
            decls + """

            // @filename: extract.ts
            import { Diagnostic } from "./types.js";
            import { createFileDiagnostic } from "./utilities.js";
            interface TargetRange { readonly range: string[]; }
            export type RangeToExtract = {
                readonly targetRange?: never;
                readonly errors: readonly Diagnostic[];
            } | {
                readonly targetRange: TargetRange;
                readonly errors?: never;
            };
            export function getRangeToExtract(span: number, invoked = true): RangeToExtract {
                if (span === 0 && !invoked) {
                    return { errors: [createFileDiagnostic(span, 0, "empty")] };
                }
                return { targetRange: { range: [] } };
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `readonly array of a conflated interface as a direct return annotation`() {
        diagnose(
            decls + """

            // @filename: extract.ts
            import { Diagnostic } from "./types.js";
            import { createFileDiagnostic } from "./utilities.js";
            export function collect(span: number): readonly Diagnostic[] {
                return [createFileDiagnostic(span, 0, "empty")];
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `non-union alias of a single typelit over the conflated element`() {
        diagnose(
            decls + """

            // @filename: extract.ts
            import { Diagnostic } from "./types.js";
            import { createFileDiagnostic } from "./utilities.js";
            export type ErrorResult = { readonly errors: readonly Diagnostic[] };
            export function fail(span: number): ErrorResult {
                return { errors: [createFileDiagnostic(span, 0, "empty")] };
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a genuinely wrong member value still fires through the alias`() {
        diagnose(
            decls + """

            // @filename: extract.ts
            import { Diagnostic } from "./types.js";
            export type ErrorResult = { readonly errors: readonly Diagnostic[] };
            export function fail(): ErrorResult {
                return { errors: "not an array" };
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
