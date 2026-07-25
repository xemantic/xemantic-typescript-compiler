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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * Round 408: a fresh EMPTY array literal assigned to an array target is
 * contextually typed by the target — `(x = [])` has x's array type, NOT the
 * `any[]` we default a bare `[]` to (B87.6).
 *
 * The tsc default-init idiom `(x || (x = [])).push(v)` / `(x ??= []).push(v)`
 * used to type the receiver as `T[] | any[]`, whose `.push` mis-resolves to a
 * UNION of two differing call signatures → spurious TS2349 "not callable".
 * Now the union collapses to `T[]` and the call resolves.
 *
 * Sharp signals: no TS2349 on the idiom; the assignment expression's type is
 * the target's (`number[]`, revealed by a mismatch probe), not `any[]`.
 */
class EmptyArrayAssignmentTypeTest {

    private fun diagnosticsOf(@Language("typescript") source: String, name: String = "arr.ts") =
        TypeScriptCompiler().compile(source, name).diagnostics

    private fun assertNo2349(@Language("typescript") source: String) {
        diagnosticsOf(source) should {
            have(none { it.code == 2349 })
        }
    }

    /** `(x || (x = [])).push(v)` — the `||` default-init idiom. */
    @Test fun orDefaultInitPush() {
        assertNo2349(
            """
            // @strict: true
            interface C { errors?: number[]; }
            function f(c: C) {
                (c.errors || (c.errors = [])).push(1);
            }
            """.trimIndent() + "\n",
        )
    }

    /** `(x ??= []).push(v)` — the `??=` default-init idiom. */
    @Test fun nullishAssignDefaultInitPush() {
        assertNo2349(
            """
            // @strict: true
            interface C { sites?: number[]; }
            function f(c: C) {
                (c.sites ??= []).push(1);
            }
            """.trimIndent() + "\n",
        )
    }

    /** A local `let a: string[] | undefined;` variant of the idiom. */
    @Test fun localVarOrDefaultInitPush() {
        assertNo2349(
            """
            // @strict: true
            function f() {
                let a: string[] | undefined;
                (a || (a = [])).push("x");
            }
            """.trimIndent() + "\n",
        )
    }

    /**
     * The assignment expression's type is the TARGET's array type, not `any[]`:
     * `const bad: symbol = (x = []);` reports `number[]`, proving the contextual typing.
     */
    @Test fun emptyArrayAssignTypesAsTarget() {
        val msg = diagnosticsOf(
            """
            // @strict: true
            function f() {
                let a: number[] = [1];
                const bad: symbol = (a = []);
            }
            """.trimIndent() + "\n",
        ).filter { it.code == 2322 }.joinToString { it.message }
        have(msg.contains("'number[]'"), "expected the assignment to type as 'number[]'")
        have(!msg.contains("any[]"), "assignment should NOT type as any[]")
    }

    /**
     * Negative control: a NON-empty array literal is NOT retyped as the target —
     * `(a = [true])` stays `boolean[]` (only the empty `[]` is contextually collapsed),
     * so a `boolean[]` → `number[]` mismatch still fires TS2322.
     */
    @Test fun nonEmptyArrayNotRetyped() {
        diagnosticsOf(
            """
            // @strict: true
            function f() {
                let a: number[] = [];
                const bad: number[] = (a = [true] as any as boolean[]);
            }
            """.trimIndent() + "\n",
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
