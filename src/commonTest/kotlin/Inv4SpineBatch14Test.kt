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
 * INV.4(b) batch 14 (round 520): TS1212/TS1213/TS1214/TS2480/TS18006
 * strict-mode reserved words migrated onto the check spine from the deleted
 * `checkStrictModeReservedWords` / `walkForStrictReserved` /
 * `walkStmtForStrictReserved` walk family. The threaded
 * isStrict/isExpressionStrict/inClass/realStrict flags became ONE shared
 * ancestor-chain context computation (`spineStrictReservedCtx`: collect the
 * parent chain, walk it DOWN applying the old descent arms — any
 * non-descended ancestor kind returns null = the old no-visit).
 *
 * Reach is deliberately NOT widened (this family is corpus-tuned with
 * per-position FP firewalls — interfaceNaming1, commonMissingSemicolons,
 * constructorStaticParamName …): while/do/for/switch/try bodies, accessor
 * bodies, arrow/fn-expression bodies, and class-expression members stay
 * unvisited, pinned negative below as signal-driven widening candidates.
 * Every pin here passes against the OLD walker (pre-verified) — this is a
 * pure reach-preserving migration.
 */
class Inv4SpineBatch14Test {

    // ── strict-mode positives (default directives = @strict: true) ──────────

    @Test
    fun `reserved word as var name fires TS1212`() {
        diagnose(
            """
            var package = 1;
            """,
        ) should {
            have(any { it.code == 1212 })
        }
    }

    @Test
    fun `let as a let-declaration name fires TS2480`() {
        diagnose(
            """
            let let = 1;
            """,
        ) should {
            have(any { it.code == 2480 })
        }
    }

    @Test
    fun `reserved word as function name fires TS1212`() {
        diagnose(
            """
            function private() {}
            """,
        ) should {
            have(any { it.code == 1212 })
        }
    }

    @Test
    fun `reserved word as function parameter fires TS1212`() {
        diagnose(
            """
            function f(public) {}
            """,
        ) should {
            have(any { it.code == 1212 })
        }
    }

    @Test
    fun `reserved word as interface name fires TS1212`() {
        diagnose(
            """
            interface package {}
            """,
        ) should {
            have(any { it.code == 1212 })
        }
    }

    @Test
    fun `reserved word as enum name fires TS1212`() {
        diagnose(
            """
            enum static { A }
            """,
        ) should {
            have(any { it.code == 1212 })
        }
    }

    @Test
    fun `reserved word in expression position fires TS1212`() {
        diagnose(
            """
            package;
            """,
        ) should {
            have(any { it.code == 1212 })
        }
    }

    @Test
    fun `reserved word inside a namespace body fires TS1212`() {
        diagnose(
            """
            namespace N { export var package = 1; }
            """,
        ) should {
            have(any { it.code == 1212 })
        }
    }

    @Test
    fun `reserved class-expression name in a var initializer fires TS1213`() {
        diagnose(
            """
            var c = class package {};
            """,
        ) should {
            have(any { it.code == 1213 })
        }
    }

    @Test
    fun `reserved leftmost qualifier of a var type annotation fires TS1212`() {
        diagnose(
            """
            var b: package.bar;
            """,
        ) should {
            have(any { it.code == 1212 })
        }
    }

    @Test
    fun `reserved class method parameter fires TS1213`() {
        diagnose(
            """
            class C { m(let: number) {} }
            """,
        ) should {
            have(any { it.code == 1213 })
        }
    }

    @Test
    fun `string-named constructor field fires TS18006`() {
        diagnose(
            """
            class C { "constructor" = 1; }
            """,
        ) should {
            have(any { it.code == 18006 })
        }
    }

    @Test
    fun `reserved var name in a module file fires TS1214`() {
        diagnose(
            """
            export {};
            var private = 1;
            """,
        ) should {
            have(any { it.code == 1214 })
        }
    }

    // ── class auto-strictness (fires even under @strict false) ──────────────

    @Test
    fun `reserved class name fires TS1213 in a non-strict file`() {
        diagnose(
            """
            class package {}
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 1213 })
        }
    }

    @Test
    fun `reserved var name in a class method body fires TS1213 in a non-strict file`() {
        diagnose(
            """
            class C { m() { var let = 1; } }
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 1213 })
        }
    }

    // ── explicit non-strict suppression + the fn-body reach quirk ───────────

    @Test
    fun `negative control - reserved var name is fine in an explicitly non-strict file`() {
        diagnose(
            """
            var package = 1;
            """,
            directives = "// @strict: false",
        ) should {
            have(none { it.code == 1212 })
            have(none { it.code == 1213 })
            have(none { it.code == 1214 })
        }
    }

    @Test
    fun `let let in a non-strict file still fires TS2480 without TS1212`() {
        diagnose(
            """
            let let = 1;
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2480 })
            have(none { it.code == 1212 })
        }
    }

    @Test
    fun `old-reach quirk - a non-strict function body is unvisited so let let emits nothing`() {
        diagnose(
            """
            function f() { let let = 1; }
            """,
            directives = "// @strict: false",
        ) should {
            have(none { it.code == 2480 })
            have(none { it.code == 1212 })
        }
    }

    @Test
    fun `a use-strict prologue file fires TS1212 despite explicit non-strict options`() {
        diagnose(
            """
            "use strict";
            var package = 1;
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 1212 })
        }
    }

    // ── the interface-implements real-strict rule (interfaceNaming1) ────────

    @Test
    fun `interface as var name does NOT fire under target-derived strictness`() {
        diagnose(
            """
            var interface = 1;
            """,
            directives = "// @target: es2015",
        ) should {
            have(none { it.code == 1212 })
        }
    }

    @Test
    fun `package as var name DOES fire under target-derived strictness`() {
        diagnose(
            """
            var package = 1;
            """,
            directives = "// @target: es2015",
        ) should {
            have(any { it.code == 1212 })
        }
    }

    @Test
    fun `a nested use-strict prologue upgrades interface to real-strict TS1212`() {
        diagnose(
            """
            function f() { "use strict"; var interface = 1; }
            """,
            directives = "// @target: es2015",
        ) should {
            have(any { it.code == 1212 })
        }
    }

    // ── old-reach gates: pinned negative (signal-driven widening candidates) ─

    @Test
    fun `negative control - a while body is unvisited by the old reach`() {
        diagnose(
            """
            while (true) { var package = 1; }
            """,
        ) should {
            have(none { it.code == 1212 })
        }
    }

    @Test
    fun `negative control - a get accessor body is unvisited by the old reach`() {
        diagnose(
            """
            class C { get g() { var let = 1; return 1; } }
            """,
        ) should {
            have(none { it.code == 1212 })
            have(none { it.code == 1213 })
        }
    }

    @Test
    fun `negative control - normal identifiers emit nothing`() {
        diagnose(
            """
            var x = 1;
            let y = 2;
            class C { m(z: number) {} }
            function f(w: string) {}
            """,
        ) should {
            have(none { it.code == 1212 })
            have(none { it.code == 1213 })
            have(none { it.code == 1214 })
            have(none { it.code == 2480 })
            have(none { it.code == 18006 })
        }
    }
}
