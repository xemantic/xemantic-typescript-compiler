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
 * A discriminated-union `switch` whose cases cover EVERY member narrows the discriminant to
 * `never` in the `default` clause (tsc's exhaustiveness narrowing) — so `default: assertNever(x)`
 * / `assertType<never>(x)` type-checks. The default-clause negative narrowing already dropped the
 * matched members but returned `null` (no narrowing) when ALL were dropped; it now returns
 * `never`. Self-compile FP family (`Debug.assertNever`/`assertType<never>` in exhaustive switches,
 * programDiagnostics/tsbuildPublic).
 *
 * FP-safe: only literal-/enum-kind members are dropped (a wide-kind member is kept), so an empty
 * result is a genuine exhaustiveness proof — a NON-exhaustive switch narrows to the surviving
 * members and the `never`-param call still errors.
 */
class ExhaustiveSwitchNeverNarrowingTest {

    private val assertNever = "declare function assertNever(x: never): never;\n"

    @Test
    fun `exhaustive string-literal-discriminant switch default narrows to never - no TS2345`() {
        diagnose(
            assertNever +
                """
                type Shape = { kind: "a", a: number } | { kind: "b", b: string };
                function f(s: Shape): number {
                    switch (s.kind) {
                        case "a": return s.a;
                        case "b": return s.b.length;
                        default: return assertNever(s);
                    }
                }
                """,
            directives = "",
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `exhaustive enum-member-discriminant switch default narrows to never - no TS2345`() {
        diagnose(
            assertNever +
                """
                enum K { A, B }
                type Shape = { kind: K.A, a: number } | { kind: K.B, b: string };
                function f(s: Shape): number {
                    switch (s.kind) {
                        case K.A: return s.a;
                        case K.B: return s.b.length;
                        default: return assertNever(s);
                    }
                }
                """,
            directives = "",
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - non-exhaustive switch keeps the uncovered member - TS2345 fires`() {
        // 'c' is not cased, so reaching the default the discriminant is `{ kind: "c"; ... }`,
        // NOT `never` — the never-param call must still error.
        diagnose(
            assertNever +
                """
                type Shape = { kind: "a", a: number } | { kind: "b", b: string } | { kind: "c", c: boolean };
                function f(s: Shape): number {
                    switch (s.kind) {
                        case "a": return s.a;
                        case "b": return s.b.length;
                        default: return assertNever(s);
                    }
                }
                """,
            directives = "",
        ) should {
            have(any { it.code == 2345 && it.message.contains("\"c\"") })
        }
    }
}
