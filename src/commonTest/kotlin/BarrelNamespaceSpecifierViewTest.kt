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
 * Round 479 (Blocker #3, the harness/client.ts family): the `protocol` namespace
 * imported by NAME through a barrel — `import { protocol } from
 * "./_namespaces/ts.server.js"` where the barrel chain re-exports
 * `import * as protocol from "./ts.server.protocol.js"; export { protocol };` —
 * must resolve a CONFLATED `protocol.TextSpan` to protocol.ts's per-file view
 * (start/end are Locations), not the merged chimera or compiler/types.ts's
 * `{ start: number; length: number }` view.
 */
class BarrelNamespaceSpecifierViewTest {

    private val decls = """
        // @module: nodenext
        // @filename: types.ts
        export interface TextSpan {
            start: number;
            length: number;
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
        export interface CodeEdit extends TextSpan {
            newText: string;
        }
        // @filename: nsinner.ts
        export * from "./protocol.js";
        // @filename: nsbarrel.ts
        import * as protocol from "./nsinner.js";
        export { protocol };
        // @filename: outerbarrel.ts
        export * from "./nsbarrel.js";
    """.trimIndent()

    @Test
    fun `specifier-imported namespace resolves the conflated interface per-file`() {
        diagnose(
            decls + """

            // @filename: client.ts
            import { protocol } from "./outerbarrel.js";
            declare function lineOffsetToPosition(lineOffset: protocol.Location): number;
            export function decodeSpan(span: protocol.TextSpan): number {
                if (span.start.line === 1 && span.start.offset === 1) {
                    return 0;
                }
                return lineOffsetToPosition(span.start) + lineOffsetToPosition(span.end);
            }
            export function convert(edit: protocol.CodeEdit): number {
                return decodeSpan(edit);
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2339 || it.code == 2345 || it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a property absent from the per-file view still fires`() {
        diagnose(
            decls + """

            // @filename: client.ts
            import { protocol } from "./outerbarrel.js";
            export function bad(span: protocol.TextSpan): number {
                return (span as any) && span.bogusMember;
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2339 && "bogusMember" in it.message })
        }
    }
}
