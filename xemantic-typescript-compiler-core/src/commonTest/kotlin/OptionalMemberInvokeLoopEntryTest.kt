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
 * Round 454 (M3.4, self-compile burn-down): the TS2722 "invoke a possibly-undefined optional
 * member" suppression consulted the plain flow narrower, which washes a reference back to its
 * declared type at a loop's FlowLoopLabel (back-edge safety). tsc's own moduleNameResolver.ts
 * guards two optional host methods BEFORE a loop — `if (host.directoryExists &&
 * host.getDirectories) { … for (…) { if (host.directoryExists(root)) … } }` — and calls them
 * INSIDE; the plain narrower dropped the pre-loop guard so `host.directoryExists(root)` FP-fired
 * TS2722. `propertyAccessNarrowedNonNull` now retries with the loop-entry-following variant
 * (follows antecedent[0], so the pre-loop narrowing flows into the loop body). Suppression-only.
 */
class OptionalMemberInvokeLoopEntryTest {

    private val prelude = """
        interface Host {
            directoryExists?: (path: string) => boolean;
            getDirectories?: (path: string) => string[];
        }
        declare function getRoots(): string[] | undefined;
    """.trimIndent() + "\n"

    @Test
    fun `an optional method guarded before a loop is not TS2722 inside the loop`() {
        diagnose(
            prelude +
            """
            export function walk(host: Host): void {
                if (host.directoryExists && host.getDirectories) {
                    const roots = getRoots();
                    if (roots) {
                        for (const root of roots) {
                            if (host.directoryExists(root)) {
                                for (const d of host.getDirectories(root)) {}
                            }
                        }
                    }
                }
            }
            """
        ) should {
            have(none { it.code == 2722 })
        }
    }

    @Test
    fun `an unguarded optional method call in a loop still fires TS2722 - negative control`() {
        diagnose(
            prelude +
            """
            export function walkNeg(host: Host, roots: string[]): void {
                for (const root of roots) {
                    host.directoryExists(root);
                }
            }
            """
        ) should {
            have(any { it.code == 2722 })
        }
    }
}
