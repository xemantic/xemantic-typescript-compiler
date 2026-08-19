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
 * INV.4(b) batch 10 (round 519): TS2373 parameter-initializer forward
 * references (plus the ES5 hoisted-body-var TS2454 companion) migrated onto
 * the check spine from the deleted `checkParamInitForwardRef` /
 * `walkForParamInitForwardRef` walk family. `checkForwardRefsInParams` (with
 * `findForwardParamRefs` / `findForwardParamRefsInBlock` /
 * `collectHoistedVarNamesFromStmts`) is retained as the per-function core;
 * the spine dispatches it from every BODIED function-like's enter. The old
 * statement walk never reached arrow functions, function expressions,
 * object-literal methods, or class-EXPRESSION members — TS2373 is a
 * position-independent tsc grammar rule, so those become faithful widenings
 * (pinned below; they FAIL pre-migration by design).
 */
class Inv4SpineBatch10Test {

    // ── old reach: pre-verified against the OLD walker ──────────────────────

    @Test
    fun `function declaration param referencing a later param fires TS2373`() {
        diagnose(
            """
            function f(a = b, b: number) {}
            """,
        ) should {
            have(any { it.code == 2373 })
        }
    }

    @Test
    fun `class method param referencing a later param fires TS2373`() {
        diagnose(
            """
            class C {
                m(a = b, b: number) {}
            }
            """,
        ) should {
            have(any { it.code == 2373 })
        }
    }

    @Test
    fun `constructor param referencing a later param fires TS2373`() {
        diagnose(
            """
            class C {
                constructor(a = b, b: number) {}
            }
            """,
        ) should {
            have(any { it.code == 2373 })
        }
    }

    @Test
    fun `nested function declaration inside a function body fires TS2373`() {
        diagnose(
            """
            function outer() {
                function inner(a = b, b: number) {}
            }
            """,
        ) should {
            have(any { it.code == 2373 })
        }
    }

    @Test
    fun `namespace-nested function fires TS2373`() {
        diagnose(
            """
            namespace N {
                export function f(a = b, b: number) {}
            }
            """,
        ) should {
            have(any { it.code == 2373 })
        }
    }

    @Test
    fun `ES5 hoisted body var referenced from a param initializer fires TS2373 and TS2454`() {
        // Unset @target keeps the raw checker target below ES2015, enabling
        // the hoisted-body-var leg (params share the fn scope with vars).
        diagnose(
            """
            function f(a = b) {
                var b: number;
            }
            """,
            directives = DOWNLEVEL_ES5,
        ) should {
            have(any { it.code == 2373 })
            have(any { it.code == 2454 })
        }
    }

    // ── widenings: FAIL pre-migration (old walk never reached these) ────────

    @Test
    fun `widening - arrow function param referencing a later param fires TS2373`() {
        diagnose(
            """
            const f = (a = b, b: number) => a;
            """,
        ) should {
            have(any { it.code == 2373 })
        }
    }

    @Test
    fun `widening - function expression param referencing a later param fires TS2373`() {
        diagnose(
            """
            const f = function (a = b, b: number) {};
            """,
        ) should {
            have(any { it.code == 2373 })
        }
    }

    @Test
    fun `widening - object literal method param referencing a later param fires TS2373`() {
        diagnose(
            """
            const o = {
                m(a = b, b: number) {},
            };
            """,
        ) should {
            have(any { it.code == 2373 })
        }
    }

    @Test
    fun `widening - class expression method param referencing a later param fires TS2373`() {
        diagnose(
            """
            const C = class {
                m(a = b, b: number) {}
            };
            """,
        ) should {
            have(any { it.code == 2373 })
        }
    }

    // ── negative controls ────────────────────────────────────────────────────

    @Test
    fun `negative control - param referencing an EARLIER param is fine`() {
        diagnose(
            """
            function f(a: number, b = a) {}
            """,
        ) should {
            have(none { it.code == 2373 })
        }
    }

    @Test
    fun `negative control - bodyless overload signature draws no TS2373`() {
        // Signatures without bodies were never checked by the old walk
        // (initializer-in-signature is TS2371 territory).
        diagnose(
            """
            declare function f(a = b, b: number): void;
            """,
        ) should {
            have(none { it.code == 2373 })
        }
    }

    @Test
    fun `negative control - IIFE own param shadows the later param`() {
        diagnose(
            """
            function f(a = ((b: number) => b)(1), b: number) {}
            """,
        ) should {
            have(none { it.code == 2373 })
        }
    }

    @Test
    fun `negative control - later param referenced only from a DEFERRED arrow body is fine`() {
        // A non-IIFE arrow in the initializer defers evaluation — no TS2373.
        diagnose(
            """
            function f(a = () => b, b: number) {}
            """,
        ) should {
            have(none { it.code == 2373 })
        }
    }
}
