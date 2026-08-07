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
 * INV.4(b) batch 11 (round 519): the break/continue jump-target family
 * (TS1104/TS1105/TS1107/TS1115/TS1116 + the TS1344 label-on-declaration
 * rule) migrated onto the check spine from the deleted `checkJumpTargets` /
 * `checkJumpInStatements` / `checkJumpInStatement` / `checkJumpInExpr` walk
 * family. The threaded inIteration/inSwitch/labelNames/
 * crossedFunctionBoundary flags became ONE parent-chain walk from the jump
 * statement — a direct mirror of tsc checkGrammarBreakOrContinueStatement's
 * `while (current)` loop. Widenings (fail pre-migration): object-literal
 * method bodies, class static blocks, and expression positions the old
 * statement walk never descended; plus the tsc-faithful nested-label
 * iteration test (`L1: L2: for(...)` — tsc isIterationStatement with
 * lookInLabeledStatements=true) which the old walk got wrong.
 */
class Inv4SpineBatch11Test {

    // ── old reach: pre-verified against the OLD walker ──────────────────────

    @Test
    fun `top-level break fires TS1105`() {
        diagnose("break;") should {
            have(any { it.code == 1105 })
        }
    }

    @Test
    fun `top-level continue fires TS1104`() {
        diagnose("continue;") should {
            have(any { it.code == 1104 })
        }
    }

    @Test
    fun `break directly inside a function body fires TS1107`() {
        diagnose(
            """
            function f() { break; }
            """,
        ) should {
            have(any { it.code == 1107 })
        }
    }

    @Test
    fun `break inside a function nested in a loop fires TS1107`() {
        diagnose(
            """
            for (;;) { function f() { break; } }
            """,
        ) should {
            have(any { it.code == 1107 })
        }
    }

    @Test
    fun `break inside an arrow initializer body fires TS1107`() {
        diagnose(
            """
            const f = () => { break; };
            """,
        ) should {
            have(any { it.code == 1107 })
        }
    }

    @Test
    fun `labeled break to an unknown label fires TS1116`() {
        diagnose(
            """
            X: while (true) { break Y; }
            """,
        ) should {
            have(any { it.code == 1116 })
        }
    }

    @Test
    fun `labeled continue to a non-iteration label fires TS1115`() {
        diagnose(
            """
            L: { continue L; }
            """,
        ) should {
            have(any { it.code == 1115 })
        }
    }

    @Test
    fun `label on a declaration statement fires TS1344`() {
        diagnose(
            """
            L: var x = 1;
            """,
        ) should {
            have(any { it.code == 1344 })
        }
    }

    @Test
    fun `negative control - explicit alwaysStrict false suppresses TS1344`() {
        diagnose(
            """
            L: var x = 1;
            """,
            directives = "// @alwaysStrict: false",
        ) should {
            have(none { it.code == 1344 })
        }
    }

    @Test
    fun `negative control - break inside a loop is fine`() {
        diagnose("while (true) { break; }") should {
            have(none { it.code == 1105 })
        }
    }

    @Test
    fun `negative control - break inside a switch case is fine`() {
        diagnose(
            """
            switch (1 as number) { case 1: break; }
            """,
        ) should {
            have(none { it.code == 1105 })
        }
    }

    @Test
    fun `negative control - labeled break to an enclosing block label is fine`() {
        diagnose(
            """
            L: { break L; }
            """,
        ) should {
            have(none { it.code == 1116 })
        }
    }

    @Test
    fun `negative control - labeled continue to an enclosing loop label is fine`() {
        diagnose(
            """
            L: for (;;) { continue L; }
            """,
        ) should {
            have(none { it.code == 1115 })
        }
    }

    @Test
    fun `negative control - break at namespace top level draws no TS1105`() {
        // The old walk passed inSwitch=true into namespace bodies (TS1036
        // owns ambient-context statements); reproduced as the ModuleBlock
        // ancestor arm.
        diagnose(
            """
            namespace N { break; }
            """,
        ) should {
            have(none { it.code == 1105 })
        }
    }

    // ── widenings: FAIL pre-migration (old walk never reached these) ────────

    @Test
    fun `widening - break inside an object literal method fires TS1107`() {
        diagnose(
            """
            const o = { m() { break; } };
            """,
        ) should {
            have(any { it.code == 1107 })
        }
    }

    @Test
    fun `widening - break inside a class static block fires TS1107`() {
        diagnose(
            """
            class C { static { break; } }
            """,
            directives = "// @target: es2022",
        ) should {
            have(any { it.code == 1107 })
        }
    }

    @Test
    fun `widening - TS1344 fires inside an arrow in an if condition`() {
        diagnose(
            """
            if ((() => { L: var x = 1; return true; })()) {}
            """,
        ) should {
            have(any { it.code == 1344 })
        }
    }

    @Test
    fun `faithfulness fix - continue to a doubly-labeled loop label is fine`() {
        // tsc isIterationStatement(lookInLabeledStatements=true): `continue L1`
        // where L1 labels `L2: for(...)` is LEGAL; the old walk tested the
        // labeled statement's immediate child only and false-fired TS1115.
        diagnose(
            """
            L1: L2: for (;;) { continue L1; }
            """,
        ) should {
            have(none { it.code == 1115 })
        }
    }
}
