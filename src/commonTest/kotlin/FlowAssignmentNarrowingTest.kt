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
 * M1.4-prep (round 386): assignment-effect narrowing beyond literal RHS.
 *
 * The tsc self-compile's TS18048 family is dominated by shapes like
 * `context.pragmas = new Map(); context.pragmas.has(...)` and
 * `result.cache ??= new Set(); result.cache.add(...)` — a PROPERTY-PATH
 * assignment with a structurally non-nullish RHS must narrow the reference.
 * Pre-fix, property-path assignments created NO FlowAssignment node at all
 * (binder gap), and the walker only understood literal RHSes.
 *
 * Sharp signals: TS2722 (possibly-undefined optional-member call — its
 * emitter consults getNarrowedTypeForReference) and TS2345 (the B469
 * flow-narrowed call-arg consumer), each with a negative control; plus the
 * `&&=` control pinning that the compound handling stays sound (a nullish
 * LHS survives `&&=`, so it must NOT narrow).
 */
class FlowAssignmentNarrowingTest {

    private fun diagnosticsOf(source: String) =
        TypeScriptCompiler().compile(source, "flowassign.ts").diagnostics

    private fun assertNone(source: String, vararg codes: Int) {
        val hits = diagnosticsOf(source).filter { it.code in codes.toSet() }
        assertTrue(
            hits.isEmpty(),
            "expected none of TS${codes.joinToString("/TS")}, got: " +
                hits.joinToString { "TS${it.code}: ${it.message}" }
        )
    }

    private fun assertSome(source: String, vararg codes: Int) {
        val diags = diagnosticsOf(source)
        assertTrue(
            diags.any { it.code in codes.toSet() },
            "negative control lost — expected one of TS${codes.joinToString("/TS")}, got: " +
                diags.joinToString { "TS${it.code}" }
        )
    }

    /** Negative controls: unassigned optional member call / maybe-undefined args error. */
    @Test fun withoutAssignmentSignalsFire() {
        assertSome(
            """
            // @strict: true
            function f(c: { p?: () => void }) {
                c.p();
            }
            """.trimIndent() + "\n",
            2722,
        )
        assertSome(
            """
            // @strict: true
            declare function takesString(s: string): void;
            function f(c: { p: string | undefined }, y: string) {
                takesString(c.p);
            }
            """.trimIndent() + "\n",
            2345,
        )
    }

    /** A property-path `=` with an arrow RHS narrows the optional member before a call. */
    @Test fun propertyAssignArrowNarrowsOptionalCall() {
        assertNone(
            """
            // @strict: true
            function f(c: { p?: () => void }) {
                c.p = () => {};
                c.p();
            }
            """.trimIndent() + "\n",
            2722,
        )
    }

    /** A property-path `=` with a new-expression RHS (through a cast) narrows too. */
    @Test fun propertyAssignNewThroughCastNarrows() {
        assertNone(
            """
            // @strict: true
            function f(c: { p?: () => void }) {
                c.p = new Function("return 0") as () => void;
                c.p();
            }
            """.trimIndent() + "\n",
            2722,
        )
    }

    /** A property-path `=` with a template-literal RHS narrows a string|undefined member. */
    @Test fun propertyAssignTemplateNarrowsArg() {
        assertNone(
            """
            // @strict: true
            declare function takesString(s: string): void;
            function f(c: { p: string | undefined }, y: string) {
                c.p = `v-${'$'}{y}`;
                takesString(c.p);
            }
            """.trimIndent() + "\n",
            2345,
        )
    }

    /** `x ??= <non-nullish>` leaves the identifier non-nullish. */
    @Test fun identifierNullishAssignNarrows() {
        assertNone(
            """
            // @strict: true
            declare function takesString(s: string): void;
            function f(x: string | undefined) {
                x ??= "default";
                takesString(x);
            }
            """.trimIndent() + "\n",
            2345,
        )
    }

    /** `c.p ??= <non-nullish>` narrows the property path. */
    @Test fun propertyNullishAssignNarrows() {
        assertNone(
            """
            // @strict: true
            declare function takesString(s: string): void;
            function f(c: { p: string | undefined }) {
                c.p ??= "default";
                takesString(c.p);
            }
            """.trimIndent() + "\n",
            2345,
        )
    }

    /** `&&=` must NOT narrow — a nullish LHS is left unassigned by it. */
    @Test fun andAndAssignDoesNotNarrow() {
        assertSome(
            """
            // @strict: true
            declare function takesString(s: string): void;
            function f(x: string | undefined) {
                x &&= "value";
                takesString(x);
            }
            """.trimIndent() + "\n",
            2345,
        )
    }
}
