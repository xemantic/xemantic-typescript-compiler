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
 * Round 472: a CAPTURED read of an auto-typed `let x;` inside an `if` whose
 * condition TRUTHY-TESTS the variable is provably assigned (undefined is falsy),
 * so tsc reports no TS7034/TS7005 — the closure-position flow inside the guard
 * carries the evolved non-undefined type. tsc's own symbolDisplay.ts:917/935:
 * `let indexInfos; if (kind === …) { indexInfos = get(); } … if (flags & Sig &&
 * indexInfos) { indexInfos.forEach((info, i) => … indexInfos.length …); }` —
 * the LAST real services-profile FP family. An UNGUARDED captured read keeps
 * firing (the controlFlowNoImplicitAny f9/f10 reference-baseline behavior).
 */
class TruthyGuardedCapturedReadTest {

    @Test
    fun `a truthy-guarded captured read is provably assigned - no implicit any`() {
        diagnose(
            """
            interface IndexInfo { keyType: string; }
            declare const cond: boolean;
            declare function getInfos(): IndexInfo[];
            declare function run(cb: (info: IndexInfo, i: number) => void): void;
            function addFullSymbolName(flags: number) {
                let indexInfos;
                if (cond) {
                    indexInfos = getInfos();
                }
                if (flags && indexInfos) {
                    run((info, i) => {
                        if (i !== indexInfos.length - 1) {
                            return;
                        }
                    });
                }
            }
            """.trimIndent(),
            directives = "// @strict: true\n// @noImplicitAny: true",
        ) should {
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }

    @Test
    fun `a not-equals-undefined guard also proves assignment`() {
        diagnose(
            """
            declare const cond: boolean;
            declare function run(cb: () => void): void;
            function f() {
                let x;
                if (cond) {
                    x = 1;
                }
                if (x !== undefined) {
                    run(() => {
                        const z = x;
                    });
                }
            }
            """.trimIndent(),
            directives = "// @strict: true\n// @noImplicitAny: true",
        ) should {
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }

    @Test
    fun `negative control - an unguarded captured read still fires - f10`() {
        diagnose(
            """
            declare const cond: boolean;
            function f10() {
                let x;
                if (cond) {
                    x = 1;
                }
                if (cond) {
                    x = "hello";
                }
                const y = x;
                const f = () => {
                    const z = x;
                };
            }
            """.trimIndent(),
            directives = "// @strict: true\n// @noImplicitAny: true",
        ) should {
            have(any { it.code == 7034 })
            have(any { it.code == 7005 })
        }
    }

    @Test
    fun `negative control - a guard on a DIFFERENT variable does not suppress`() {
        diagnose(
            """
            declare const cond: boolean;
            declare function run(cb: () => void): void;
            function f() {
                let x;
                if (cond) {
                    x = 1;
                }
                if (cond) {
                    run(() => {
                        const z = x;
                    });
                }
            }
            """.trimIndent(),
            directives = "// @strict: true\n// @noImplicitAny: true",
        ) should {
            have(any { it.code == 7034 })
        }
    }
}
