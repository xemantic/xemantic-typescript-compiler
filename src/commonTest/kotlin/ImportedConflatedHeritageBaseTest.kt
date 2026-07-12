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
 * Round 480 (Blocker #3): a derived interface that merely IMPORTS a CONFLATED
 * base resolves the base per-file through its own import + barrel star chain
 * (`ServerHost extends System` in server/types.ts vs fakesHosts' unrelated
 * `class System` — the chimera demanded vfs/output/exitCode from every
 * ServerHost implementer). PAIRED with the derived-vs-chimera relation bail
 * (conflatedChimeraTargetSourceHasPerFileBase): a derived instance carrying
 * the per-file base still relates to a chimera-typed param of the base name
 * (ParseConfigFileHost → host: ParseConfigHost).
 */
class ImportedConflatedHeritageBaseTest {

    private val decls = """
        // @module: nodenext
        // @filename: sys.ts
        export interface System {
            write(s: string): void;
            realpath?(path: string): string;
        }
        // @filename: fakes.ts
        export class System {
            public vfs: string[] = [];
            public output: string[] = [];
            public exitCode: number | undefined;
            public write(s: string): void { this.output.push(s); }
        }
        // @filename: barrel.ts
        export * from "./sys.js";
    """.trimIndent()

    @Test
    fun `imported conflated heritage base resolves the per-file view`() {
        diagnose(
            decls + """

            // @filename: servertypes.ts
            import { System } from "./barrel.js";
            export interface ServerHost extends System {
                setTimeout(cb: (...args: unknown[]) => void, ms: number): unknown;
            }
            // @filename: impl.ts
            import { ServerHost } from "./servertypes.js";
            class SessionServerHost implements ServerHost {
                write(s: string): void { void s; }
                setTimeout(cb: (...args: unknown[]) => void, ms: number): unknown { void cb; return ms; }
            }
            export const host: ServerHost = new SessionServerHost();
            """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(none { it.code == 2322 || it.code == 2739 || it.code == 2740 || it.code == 2420 })
        }
    }

    @Test
    fun `negative control - a genuinely missing base member still fires`() {
        diagnose(
            decls + """

            // @filename: servertypes.ts
            import { System } from "./barrel.js";
            export interface ServerHost extends System {
                setTimeout(cb: (...args: unknown[]) => void, ms: number): unknown;
            }
            // @filename: impl.ts
            import { ServerHost } from "./servertypes.js";
            export const host: ServerHost = {
                setTimeout: (cb: (...args: unknown[]) => void, ms: number): unknown => { void cb; return ms; },
            };
            """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 2322 || it.code == 2739 || it.code == 2741 })
        }
    }
}
