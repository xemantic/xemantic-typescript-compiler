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
 * Round 474 (Blocker #3, the tsc executeCommandLine.ts `formatMessage` family): an
 * IMPORTED callee whose name collides with a same-named exported function in an
 * UNRELATED file resolves through its OWN identity-matched import
 * ([importedCalleeFunctionType]) — the merged `globals` symbol's valueDeclaration
 * is file-order-dependent, so `formatMessage(diag, "url")` (compiler/utilities'
 * `(message: DiagnosticMessage, ...args)`) was arg-checked against
 * server/session.ts's `formatMessage(msg, logger: Logger, byteLength, newLine)`
 * → FP TS2345 'string' vs 'Logger'. Gated to a genuine collision; the wrong-arg
 * negative control proves the CORRECT signature still checks.
 */
class ImportedCalleeCollisionTest {

    private val decls = """
        // @module: nodenext
        // @filename: compilerUtils.ts
        export interface Msg { key: string; }
        export function fmt(message: Msg, ...args: string[]): string {
            return message.key + args.join(",");
        }
        // @filename: serverSession.ts
        export interface Logger { info(s: string): void; }
        export function fmt(msg: object, logger: Logger, byteLength: (s: string) => number, newLine: string): string {
            logger.info(newLine);
            return String(byteLength(newLine));
        }
    """.trimIndent()

    @Test
    fun `an imported callee wins over an unrelated same-named cross-file function`() {
        diagnose(
            decls + """

            // @filename: main.ts
            import { fmt, Msg } from "./compilerUtils.js";
            declare const m: Msg;
            export const s = fmt(m, "https://x");
            """.trimIndent(),
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a wrong arg against the imported signature still fires`() {
        diagnose(
            decls + """

            // @filename: main.ts
            import { fmt, Msg } from "./compilerUtils.js";
            declare const m: Msg;
            export const bad = fmt(m, true as unknown as symbol);
            """.trimIndent(),
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `no collision - a plain imported callee keeps its established resolution`() {
        diagnose(
            """
            // @module: nodenext
            // @filename: only.ts
            export function greet(name: string): string { return name; }
            // @filename: main.ts
            import { greet } from "./only.js";
            export const ok = greet("hi");
            export const bad = greet(42 as unknown as symbol);
            """.trimIndent(),
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
