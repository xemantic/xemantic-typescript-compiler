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
 */

package com.xemantic.typescript.compiler

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Round 436g (M3.4): a guard-gated ternary RETURN arm narrows by the condition
 * — `return isNamedTupleMember(m) || isParameter(m) ? m : undefined` types the
 * true arm as the guard targets (tsc utilities.ts's
 * memberIfLabeledElementDeclaration family). checkConditionalReturnBranches
 * substitutes the flow-narrowed arm type only when it is a refinement that
 * makes the branch relate (suppression-only).
 */
class TernaryGuardedReturnArmTest {

    private fun ts2322s(source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")
            .diagnostics.filter { it.code == 2322 }

    private val prelude = """
        interface Node3 { kind: number }
        interface NamedTupleMember extends Node3 { name: string }
        interface ParameterDeclaration extends Node3 { paramName: string }
        declare function isNamedTupleMember(n: Node3): n is NamedTupleMember;
        declare function isParameter(n: Node3): n is ParameterDeclaration;
    """.trimIndent()

    /** The utilities.ts shape: ||-combined guards gate the true arm. */
    @Test fun guardGatedTernaryArmNarrows() {
        val d = ts2322s(
            prelude + "\n" +
                """
                function memberIfLabeled(member: Node3): NamedTupleMember | ParameterDeclaration | undefined {
                    return isNamedTupleMember(member) || isParameter(member) ? member : undefined;
                }
                """.trimIndent()
        )
        assertTrue(d.isEmpty(), "expected no TS2322, got: $d")
    }

    /** Single-guard variant. */
    @Test fun singleGuardTernaryArmNarrows() {
        val d = ts2322s(
            prelude + "\n" +
                """
                function paramIf(member: Node3): ParameterDeclaration | undefined {
                    return isParameter(member) ? member : undefined;
                }
                """.trimIndent()
        )
        assertTrue(d.isEmpty(), "expected no TS2322, got: $d")
    }

    /** NEGATIVE control: an UN-guarded arm still fires. */
    @Test fun unGuardedTernaryArmStillFires() {
        val d = ts2322s(
            prelude + "\n" +
                """
                function bad(member: Node3, c: boolean): ParameterDeclaration | undefined {
                    return c ? member : undefined;
                }
                """.trimIndent()
        )
        assertTrue(d.isNotEmpty(), "expected TS2322 for un-narrowed 'Node3' arm")
    }
}
