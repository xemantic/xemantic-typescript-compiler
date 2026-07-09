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
 * Round 459: the default-init idiom `(x.p || (x.p = [])).push(v)` on a NULLABLE
 * array PROPERTY (tsc's binder.ts addAntecedent — `antecedent: FlowNode[] |
 * undefined`). Two coupled rules:
 *
 * (1) An assignment TARGET types against its DECLARED type, never the
 * flow-narrowed read (M1.9) — inside the `||` right operand the read of `x.p`
 * is narrowed to the falsy `undefined`, which defeated the round-408 empty-`[]`
 * contextual rule; `combineBinaryTypes`' Equals arm recomputes the RAW property
 * type for a PropertyAccess target with a fresh empty-array RHS.
 *
 * (2) `contextualAssignmentRhsType` accepts a UNION target whose sole
 * non-nullish member is Array-family — the `[]` types as that member, so the
 * `||` result collapses to ONE array type (identity-dedup) and `.push` resolves
 * a single signature instead of the two-differing-sigs union → no TS2349.
 */
class NullableArrayDefaultInitPushTest {

    @Test
    fun `property target default-init push - no TS2349`() {
        diagnose("""
            interface FlowNode { id: number; }
            interface FlowLabel { antecedent: FlowNode[] | undefined; }
            function addAntecedent(label: FlowLabel, antecedent: FlowNode) {
                (label.antecedent || (label.antecedent = [])).push(antecedent);
            }
        """.trimIndent()) should {
            have(none { it.code == 2349 })
        }
    }

    @Test
    fun `identifier target default-init push - no TS2349`() {
        diagnose("""
            interface FlowNode { id: number; }
            function f(xs: FlowNode[] | undefined, v: FlowNode) {
                (xs || (xs = [])).push(v);
            }
        """.trimIndent()) should {
            have(none { it.code == 2349 })
        }
    }

    @Test
    fun `negative control - a wrong arg to the collapsed push still fires TS2345`() {
        // Proves the receiver collapses to the PRECISE `string[]` (not `any[]`,
        // which would silently accept the number).
        diagnose("""
            interface Box { p: string[] | undefined; }
            function f(x: Box) {
                (x.p || (x.p = [])).push(123);
            }
        """.trimIndent()) should {
            have(any { it.code == 2345 })
        }
    }
}
