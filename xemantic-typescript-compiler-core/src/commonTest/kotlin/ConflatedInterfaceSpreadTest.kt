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
 * Round 477 (Blocker #3, tsc session.ts:3994): an object literal SPREADING a
 * value whose type names a CONFLATED interface (`{ ...textSpan, contextStart,
 * contextEnd }` where `protocol.TextSpan` merges with compiler types.ts's
 * `TextSpan` into a chimera — the spread source's fn-return shell caches
 * eagerly with null context, B198) is unknowable at the return/ternary-arm
 * emission — [objectLiteralSpreadsConflatedInterface], suppression-only.
 */
class ConflatedInterfaceSpreadTest {

    private val decls = """
        // @module: nodenext
        // @strict: true
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
        export interface TextSpanWithContext extends TextSpan {
            contextStart?: Location;
            contextEnd?: Location;
        }
    """.trimIndent()

    @Test
    fun `spread of a conflated-interface-typed value is unknowable at a ternary return arm`() {
        diagnose(
            decls + """

            // @filename: session.ts
            import { TextSpan } from "./types.js";
            import * as protocol from "./protocol.js";
            declare function toProtocolTextSpan(span: TextSpan): protocol.TextSpan;
            export function toSpanWithContext(
                span: TextSpan,
                contextSpan: TextSpan | undefined,
            ): protocol.TextSpanWithContext {
                const textSpan = toProtocolTextSpan(span);
                const contextTextSpan = contextSpan && toProtocolTextSpan(contextSpan);
                return contextTextSpan ?
                    { ...textSpan, contextStart: contextTextSpan.start, contextEnd: contextTextSpan.end } :
                    textSpan;
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2322 || it.code == 2739 || it.code == 2740 })
        }
    }

    @Test
    fun `negative control - a spread of a NON-conflated interface with a real mismatch still fires`() {
        diagnose(
            """
            // @strict: true
            interface Plain {
                a: number;
            }
            interface Target {
                a: number;
                b: string;
            }
            declare const p: Plain;
            function f(): Target {
                return { ...p };
            }
            """.trimIndent(),
            directives = "",
        ) should {
            // `b` is required and neither the spread nor the literal provides it.
            have(any { it.code == 2322 || it.code == 2739 || it.code == 2741 })
        }
    }
}
