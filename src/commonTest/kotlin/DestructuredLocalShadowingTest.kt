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
 * M3.1 (round 436): a DESTRUCTURED function-body local (`const { version, major }
 * = parsePartial(text)`) shadows a same-named outer binding, but its binding
 * names live in no local type map — a bare-identifier call arg fell through
 * `getTypeOfIdentifier` to the merged globals and resolved the OUTER binding
 * (tsc's imported `version: string` at semver.ts createComparator calls; the
 * imported `function length(...)` at checker.ts getDiagnosticForCallNode) →
 * FP TS2345. `shadowCallTypesDeclList` now registers destructured-local binding
 * names into the fn-scoped `currentParamBindingNames` side set (anyType,
 * suppression-only — the same mechanism as round 429's destructured params).
 */
class DestructuredLocalShadowingTest {

    private fun ts2345s(source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")
            .diagnostics.filter { it.code == 2345 }

    /** The semver.ts shape: destructured `version` shadows a file-level string. */
    @Test fun destructuredLocalShadowsFileLevelConst() {
        val diags = ts2345s(
            """
            export const version = "5.0";
            interface Version { major: number; minor: number }
            declare function createComparator(op: string, operand: Version): number;
            declare function parsePartial(text: string): { version: Version; major: string } | undefined;
            export function f(text: string) {
                const result = parsePartial(text);
                if (!result) return false;
                const { version, major } = result;
                createComparator(">=", version);
                return major;
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2345, got: $diags")
    }

    /** The checker.ts shape: destructured `length` shadows a global function. */
    @Test fun destructuredLocalShadowsGlobalFunction() {
        val diags = ts2345s(
            """
            declare function length(array: readonly any[] | undefined): number;
            declare function useNum(n: number): void;
            declare function getSpan(): { start: number; length: number };
            export function f() {
                const { start, length } = getSpan();
                useNum(length);
                return start;
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2345, got: $diags")
    }

    /** ARRAY-pattern variant of the same shadow. */
    @Test fun arrayDestructuredLocalShadowsGlobalFunction() {
        val diags = ts2345s(
            """
            declare function length(array: readonly any[] | undefined): number;
            declare function useNum(n: number): void;
            declare function getPair(): [number, number];
            export function f() {
                const [start, length] = getPair();
                useNum(length);
                return start;
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2345, got: $diags")
    }

    /** NEGATIVE control: a NON-shadowed outer function arg still fires — the
     *  side-set registration must not leak beyond the declaring function. */
    @Test fun outerFunctionArgOutsideShadowingFnStillFires() {
        val diags = ts2345s(
            """
            declare function length(array: readonly any[] | undefined): number;
            declare function useNum(n: number): void;
            declare function getSpan(): { start: number; length: number };
            export function shadowed() {
                const { length } = getSpan();
                return length;
            }
            export function unshadowed() {
                useNum(length);
            }
            """.trimIndent()
        )
        assertTrue(diags.isNotEmpty(),
            "expected TS2345 for the function-valued `length` arg outside the shadowing fn")
    }
}
