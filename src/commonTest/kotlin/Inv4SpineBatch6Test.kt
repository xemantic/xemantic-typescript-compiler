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
 * INV.4(b) batch 6 (round 517): three passes migrated onto the check spine —
 * TS1030/TS1029/TS1044 duplicate/mis-ordered modifiers (from the deleted
 * `checkDuplicateModifiers` walk family; `checkModifiers` +
 * `checkInvalidImportEqualsModifiers` retained), TS1039/TS1254/TS1066/TS1031
 * ambient initializers (from the deleted `checkAmbientInitializers` walk;
 * emission helpers retained), and TS2678 switch/case literal comparability
 * (from the deleted `checkSwitchCaseComparable` walk family; the per-list
 * const/annotated binding maps reproduced by a preceding-sibling scan).
 */
class Inv4SpineBatch6Test {

    // ── TS1030/TS1029: duplicate / mis-ordered modifiers ────────────────────

    @Test
    fun `duplicate declare modifier fires TS1030`() {
        diagnose(
            """
            declare declare var x: number;
            """,
        ) should {
            have(any { it.code == 1030 })
        }
    }

    @Test
    fun `duplicate readonly on a class member fires TS1030`() {
        diagnose(
            """
            class C {
                public readonly readonly x: number = 1;
            }
            """,
        ) should {
            have(any { it.code == 1030 })
        }
    }

    @Test
    fun `visibility modifier after static fires TS1029`() {
        diagnose(
            """
            class C {
                static public x: number = 1;
            }
            """,
        ) should {
            have(any { it.code == 1029 })
        }
    }

    @Test
    fun `export after declare fires TS1029`() {
        diagnose(
            """
            declare export var x: number;
            """,
        ) should {
            have(any { it.code == 1029 })
        }
    }

    @Test
    fun `duplicate export inside a namespace body fires TS1030`() {
        diagnose(
            """
            namespace M {
                export export var x = 1;
            }
            """,
        ) should {
            have(any { it.code == 1030 })
        }
    }

    @Test
    fun `duplicate declare inside a bare block fires TS1030`() {
        diagnose(
            """
            {
                declare declare var x: number;
            }
            """,
        ) should {
            have(any { it.code == 1030 })
        }
    }

    @Test
    fun `negative control - for-loop body is outside the old walk reach`() {
        // The old checkDuplicateModifiers walk had no ForStatement case — a
        // duplicate-modifier statement in a loop body was never visited. The
        // spine migration reproduces that reach exactly.
        diagnose(
            """
            for (;;) {
                declare declare var x: number;
            }
            """,
        ) should {
            have(none { it.code == 1030 })
        }
    }

    @Test
    fun `negative control - non-top-level function skips the modifier check (B459b)`() {
        // TS1184 owns position-illegal modifiers on a nested function; the
        // duplicate-modifier grammar check is suppressed there.
        diagnose(
            """
            function outer() {
                export export function inner() {}
            }
            """,
        ) should {
            have(none { it.code == 1030 })
        }
    }

    @Test
    fun `negative control - ambient import-equals skips TS1029 (B61-5g)`() {
        diagnose(
            """
            declare namespace M {
                declare export import a = b.c;
            }
            """,
        ) should {
            have(none { it.code == 1029 })
        }
    }

    // ── TS1039/TS1254/TS1066/TS1031: ambient initializers ───────────────────

    @Test
    fun `declare var with initializer fires TS1039`() {
        diagnose(
            """
            declare var x = 1;
            """,
        ) should {
            have(any { it.code == 1039 })
        }
    }

    @Test
    fun `negative control - declare const with literal initializer is fine`() {
        diagnose(
            """
            declare const x = 1;
            """,
        ) should {
            have(none { it.code == 1039 || it.code == 1254 })
        }
    }

    @Test
    fun `declare const with non-literal initializer fires TS1254`() {
        diagnose(
            """
            declare function foo(): number;
            declare const x = foo();
            """,
        ) should {
            have(any { it.code == 1254 })
        }
    }

    @Test
    fun `declare enum with non-constant member initializer fires TS1066`() {
        diagnose(
            """
            declare function foo(): number;
            declare enum E {
                A = foo(),
            }
            """,
        ) should {
            have(any { it.code == 1066 })
        }
    }

    @Test
    fun `negative control - same-enum member reference is a constant initializer (B162)`() {
        diagnose(
            """
            declare enum E {
                A = 1,
                B = A,
            }
            """,
        ) should {
            have(none { it.code == 1066 })
        }
    }

    @Test
    fun `declare class property initializer fires TS1039`() {
        diagnose(
            """
            declare class C {
                x = 1;
            }
            """,
        ) should {
            have(any { it.code == 1039 })
        }
    }

    @Test
    fun `top-level initializer in a dts file fires TS1039`() {
        diagnose(
            """
            var x = 1;
            """,
            fileName = "t.d.ts",
        ) should {
            have(any { it.code == 1039 })
        }
    }

    @Test
    fun `var initializer inside a declare namespace fires TS1039`() {
        diagnose(
            """
            declare namespace M {
                var x = 1;
            }
            """,
        ) should {
            have(any { it.code == 1039 })
        }
    }

    @Test
    fun `export modifier on a class member fires TS1031`() {
        diagnose(
            """
            class C {
                export x: number = 1;
            }
            """,
        ) should {
            have(any { it.code == 1031 })
        }
    }

    @Test
    fun `negative control - method bodies are outside the old ambient-init reach`() {
        // The old checkAmbientInitializers walk never descended class member
        // bodies — a declare-var-with-initializer there stays unreported
        // (a signal-driven widening candidate, preserved as-is).
        diagnose(
            """
            class C {
                m() {
                    declare var x = 1;
                }
            }
            """,
        ) should {
            have(none { it.code == 1039 })
        }
    }

    // ── TS2678: switch/case literal comparability ────────────────────────────

    @Test
    fun `case literal not comparable to const-narrowed switch subject fires TS2678`() {
        diagnose(
            """
            const x = "foo";
            switch (x) {
                case "bar":
                    break;
            }
            """,
        ) should {
            have(any { it.code == 2678 })
        }
    }

    @Test
    fun `negative control - matching case literal is fine`() {
        diagnose(
            """
            const x = "foo";
            switch (x) {
                case "foo":
                    break;
            }
            """,
        ) should {
            have(none { it.code == 2678 })
        }
    }

    @Test
    fun `case literal outside an annotated union fires TS2678`() {
        diagnose(
            """
            declare var r: number | "hello";
            switch (r) {
                case "world":
                    break;
            }
            """,
        ) should {
            have(any { it.code == 2678 })
        }
    }

    @Test
    fun `negative control - let bindings widen and are not tracked`() {
        diagnose(
            """
            let x = "foo";
            switch (x) {
                case "bar":
                    break;
            }
            """,
        ) should {
            have(none { it.code == 2678 })
        }
    }

    @Test
    fun `negative control - const in an OUTER block does not reach an inner-block switch`() {
        // The old walker built its binding maps per statement LIST (fresh maps
        // per nested block) — the sibling-scan reproduction preserves that.
        diagnose(
            """
            const x = "foo";
            {
                switch (x) {
                    case "bar":
                        break;
                }
            }
            """,
        ) should {
            have(none { it.code == 2678 })
        }
    }

    @Test
    fun `negative control - const declared after the switch is not tracked`() {
        diagnose(
            """
            switch (q) {
                case "bar":
                    break;
            }
            const q = "foo";
            """,
        ) should {
            have(none { it.code == 2678 })
        }
    }

    @Test
    fun `switch inside an arrow body sees same-body const bindings`() {
        diagnose(
            """
            const f = () => {
                const x = 1;
                switch (x) {
                    case 2:
                        break;
                }
            };
            """,
        ) should {
            have(any { it.code == 2678 })
        }
    }

    @Test
    fun `class identifier case against a literal subject fires TS2678 with typeof display`() {
        diagnose(
            """
            class K {}
            const x = 1;
            switch (x) {
                case K:
                    break;
            }
            """,
        ) should {
            have(any { it.code == 2678 && "typeof K" in it.message })
        }
    }
}
