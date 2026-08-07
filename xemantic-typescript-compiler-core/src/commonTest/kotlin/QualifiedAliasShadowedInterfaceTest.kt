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
 * Round 477 (Blocker #3, tsc session.ts:475): a QUALIFIED `ns.Name` naming an
 * interface SHADOWED by a same-named `type Name` alias in a DIFFERENT module
 * file (the last-wins Interface+TypeAlias merge — session.ts's own `type Event =
 * <T>(body, eventName) => void` vs protocol.ts's `interface Event extends
 * Message`) resolves through the namespace import to the target module's
 * per-file view ([conflatedPerFileInterfaceType] with [interfaceDeclFilesAll]).
 * QUALIFIED-only — bare references keep the alias-conflation status quo.
 */
class QualifiedAliasShadowedInterfaceTest {

    private val decls = """
        // @module: nodenext
        // @strict: true
        // @filename: protocol.ts
        export interface Message {
            seq: number;
            type: "request" | "response" | "event";
        }
        export interface Event extends Message {
            type: "event";
            event: string;
            body?: any;
        }
    """.trimIndent()

    @Test
    fun `qualified reference resolves the protocol interface - not the local alias`() {
        diagnose(
            decls + """

            // @filename: session.ts
            import * as protocol from "./protocol.js";
            export type Event = <T extends object>(body: T, eventName: string) => void;
            export function toEvent(eventName: string, body: object): protocol.Event {
                return {
                    seq: 0,
                    type: "event",
                    event: eventName,
                    body,
                };
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2322 || it.code == 2739 || it.code == 2740 || it.code == 2741 })
        }
    }

    @Test
    fun `negative control - a missing required inherited member still fires`() {
        diagnose(
            decls + """

            // @filename: session.ts
            import * as protocol from "./protocol.js";
            export type Event = <T extends object>(body: T, eventName: string) => void;
            export function toEvent(eventName: string, body: object): protocol.Event {
                return {
                    type: "event",
                    event: eventName,
                    body,
                };
            }
            """.trimIndent(),
        ) should {
            // `seq` (inherited from Message) is required and absent.
            have(any { it.code == 2322 || it.code == 2739 || it.code == 2741 })
        }
    }
}
