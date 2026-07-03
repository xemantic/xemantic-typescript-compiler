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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Local corner-case tests for M1.2a (round 385): definite-assignment analysis
 * respects the "control-flow graph too large" bail.
 *
 * tsc disables flow analysis for a container whose flow walk exceeds its budget
 * (`flowAnalysisDisabled`) and reports TS2563 there — after which
 * `getFlowTypeOfReference` returns errorType, so definite-assignment produces NO
 * TS2454 in the same container. tsc emits TS2563 OR TS2454, never both (and on its
 * own sources, neither). Our per-file B399 proxy emitted TS2563 but left the
 * TS2454 walkers running — stacking contradictory FPs on giant real-world files
 * (20 of them on the self-compile compiler profile).
 *
 * Sharp signals: the small control proves the TS2454 emitter fires on this exact
 * shape (so the big test's absence is the SUPPRESSION working, not a vacuous
 * never-fired), and the big test requires TS2563 to actually be present (so the
 * suppression is gated on the bail having fired, not blanket TS2454 removal).
 */
class CfaTooLargeBailTest {

    /**
     * `let v: number` never assigned, then read — a TS2454 "used before being
     * assigned" shape our definite-assignment walkers fire on — padded with [ifs]
     * conditional assignments to an UNRELATED var to scale the flow-node count
     * (each `if` contributes several flow nodes; 100 stays far under the B399
     * threshold of 2,000 nodes, 3,000 sails past it).
     */
    private fun conditionalAssignSource(ifs: Int): String = buildString {
        append("// @strict: true\n")
        append("declare const b: boolean;\n")
        append("let p: number;\n")
        append("let v: number;\n")
        repeat(ifs) { append("if (b) { p = 1; }\n") }
        append("const w: number = v;\n")
    }

    /** Control: small CFG — no TS2563, and the TS2454 emitter DOES fire. */
    @Test fun smallCfgKeepsDefiniteAssignmentAnalysis() {
        val result = TypeScriptCompiler().compile(conditionalAssignSource(ifs = 100), "small.ts")
        assertTrue(
            result.diagnostics.none { it.code == 2563 },
            "control broken: 100 ifs should stay under the too-large threshold"
        )
        assertTrue(
            result.diagnostics.any { it.code == 2454 },
            "control broken: conditional-assign-then-read must be TS2454 on a small CFG, got: " +
                result.diagnostics.joinToString { "TS${it.code}" }
        )
    }

    /** Too-large CFG: TS2563 fires and suppresses every TS2454 in the file (tsc emits neither, but our per-file proxy reports the bail). */
    @Test fun tooLargeCfgBailsOutOfDefiniteAssignment() {
        val result = TypeScriptCompiler().compile(conditionalAssignSource(ifs = 3000), "big.ts")
        assertTrue(
            result.diagnostics.any { it.code == 2563 },
            "expected the too-large bail (TS2563) on a ~9000-flow-node file, got: " +
                result.diagnostics.map { "TS${it.code}" }.distinct().joinToString()
        )
        assertTrue(
            result.diagnostics.none { it.code == 2454 },
            "TS2454 must be suppressed after the too-large bail (tsc emits TS2563 OR TS2454, never both)"
        )
    }
}
