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

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 424 — union-receiver TS2339 suppression must survive a loop boundary.
 *
 * The shape (tsc's own `parseResponseFile`, commandLineParser.ts): a local const
 * with a union type (`const text = tryReadFile(…)` → `string | Diagnostic`), a
 * pre-loop early-return guard (`if (!isString(text)) return;`), then member reads
 * INSIDE a `while` loop (`text.charCodeAt(pos)`). The plain narrowing walk washes
 * back to the declared union at a FlowLoopLabel (back-edge safety), so the union
 * TS2339 elaboration FP'd. The fix retries with the loop-entry-following variant,
 * SUPPRESSION-ONLY.
 *
 * The sharp corner this pins: the "plain walk did not narrow" gate must be
 * STRUCTURAL, not identity — any `&&`/`||` condition on the walked path creates a
 * 2-antecedent FlowBranchLabel whose union of [declared, declared] MINTS a fresh
 * Type.Union instance (getUnionType does not intern), so an identity `===` gate
 * silently misses the wash exactly when the loop body contains a compound
 * condition.
 */
class LoopEntryUnionSuppressionTest {

    private val prelude = """
        interface Diagnostic { code: number; length: number | undefined; }
        declare function tryReadFile(fileName: string): string | Diagnostic;
        function isString(text: unknown): text is string {
            return typeof text === "string";
        }
    """

    private fun assertNo2339(d: List<Diagnostic>, what: String) {
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `a guarded union read inside a while-true loop does not fire TS2339`() {
        val d = diagnose(
            prelude + """
            export function f(fileName: string) {
                const text = tryReadFile(fileName);
                if (!isString(text)) return;
                while (true) {
                    return text.charCodeAt(0);
                }
            }
            """,
        )
        assertNo2339(d, "pre-loop-guarded union read inside while(true)")
    }

    @Test
    fun `a guarded read after an inner loop with a compound condition does not fire TS2339`() {
        // The identity-gate trap: the inner while's `&&` condition puts a
        // 2-antecedent FlowBranchLabel on the walked path, so the plain walk
        // returns a FRESH structurally-identical union — the retry gate must
        // compare member sets, not instances.
        val d = diagnose(
            prelude + """
            export function f(fileName: string) {
                const text = tryReadFile(fileName);
                if (!isString(text)) return;
                let pos = 0;
                while (true) {
                    while (pos < text.length && text.charCodeAt(pos) <= 32) pos++;
                    if (pos >= text.length) break;
                    const start = pos;
                    return text.substring(start, pos);
                }
            }
            """,
        )
        assertNo2339(d, "guarded union read after an inner loop with a compound condition")
    }

    @Test
    fun `a compound if-condition inside a loop does not fire TS2339`() {
        val d = diagnose(
            prelude + """
            export function f(fileName: string, pos: number) {
                const text = tryReadFile(fileName);
                if (!isString(text)) return;
                while (true) {
                    if (pos < text.length && text.charCodeAt(pos) <= 32) pos++;
                    return text.substring(pos, 1);
                }
            }
            """,
        )
        assertNo2339(d, "guarded union read after a compound if condition inside a loop")
    }

    @Test
    fun `negative control - an unguarded union read inside a loop still fires TS2339`() {
        // Negative control: with NO guard the loop-entry retry finds no
        // narrowing (the pre-loop flow still carries the full union), so the
        // genuine TS2339 must stand.
        diagnose(
            prelude + """
            export function f(fileName: string) {
                const text = tryReadFile(fileName);
                while (true) {
                    return text.charCodeAt(0);
                }
            }
            """,
        ) should {
            have(any { it.code == 2339 && it.message.contains("charCodeAt") })
        }
    }

    @Test
    fun `negative control - the opposite guard polarity inside a loop still fires TS2339`() {
        // Negative control: the guard proves the WRONG branch (positive branch
        // returns) — past the if, text is Diagnostic, so a string member read
        // must keep firing.
        diagnose(
            prelude + """
            export function f(fileName: string) {
                const text = tryReadFile(fileName);
                if (isString(text)) return;
                while (true) {
                    return text.charCodeAt(0);
                }
            }
            """,
        ) should {
            have(any { it.code == 2339 && it.message.contains("charCodeAt") })
        }
    }
}
