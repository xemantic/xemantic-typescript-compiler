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
 * Round 458: a fresh object literal RETURNED against an INTERFACE (or anonymous
 * object) target with a literal(-union) member. getTypeOfObjectLiteral widens a
 * literal property (`kind: "ambient"` → `string`), so the coarse relation FP-fired
 * TS2322 against `interface ModuleSpecifierResult { kind: "node_modules" | … |
 * "ambient"; … }`. The round-448 fresh-object-literal retry (which recovers the
 * un-widened literal per PropertyAssignment) was gated to UNION targets only; this
 * broadens it to Interface / anonymous-object targets. Suppression-only — the retry
 * suppresses only when the relation then PASSES, so a genuine mismatch still fires.
 */
class ReturnObjectLiteralInterfaceLiteralMemberTest {

    @Test
    fun `object literal with a widened literal property relates to an interface literal-union member`() {
        diagnose(
            """
            interface Result {
                kind: "node_modules" | "paths" | "relative" | "ambient";
                specifiers: string[];
                cached: boolean;
            }
            function make(): Result {
                return { kind: "ambient", specifiers: [], cached: false };
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `object literal relates to an anonymous-object target with a literal member`() {
        diagnose(
            """
            function make(): { tag: "a" | "b"; n: number } {
                return { tag: "b", n: 1 };
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - wrong literal value still fires against an interface member`() {
        // "zzz" is not in the target union, so the retry fails and the error stands.
        diagnose(
            """
            interface Result { kind: "a" | "b"; n: number; }
            function make(): Result {
                return { kind: "zzz", n: 1 };
            }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a genuinely missing property still fires`() {
        diagnose(
            """
            interface Result { kind: "a" | "b"; n: number; }
            function make(): Result {
                return { kind: "a" };
            }
            """,
        ) should {
            have(any { it.code == 2739 || it.code == 2741 || it.code == 2322 })
        }
    }
}
