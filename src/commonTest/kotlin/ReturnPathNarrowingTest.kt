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
 * M3.4 (round 413): the return-assignability path is now a flow-narrowing consumer for a
 * named object (Interface/Reference) return target. A returned Identifier/PropertyAccess
 * narrowed by a preceding user guard/assert must be checked against the NARROWED type, not
 * the wider declared type — tsc's own `toBuilderProgramStateWithDefinedProgram(state) {
 * Debug.assert(isDefined(state)); return state; }`. Suppression-only + FP-safe: the
 * narrowed type is substituted only when it is a strict improvement that makes the return
 * relate, so a genuine mismatch keeps firing. Scoped to Interface/Reference targets
 * (mirrors the round-410 assignment-RHS narrowing gate); primitive-target return narrowing
 * is a separate future extension.
 */
class ReturnPathNarrowingTest {

    // Type-literal aliases (→ Type.Object, the shape the return-path missing-property
    // TS2739 emit is gated to — a named interface resolves to Type.Interface instead).
    // `Big` requires `b`, `c` that `Small` genuinely LACKS, so returning a `Small` where
    // `Big` is expected is a missing-property TS2739 (builder.ts's
    // ReusableBuilderProgramState → BuilderProgramStateWithDefinedProgram).
    private val guardDecls = """
        // @strict: true
        type Small = { a: number };
        type Big = { a: number; b: number; c: number };
        declare function isDefined(x: Small): x is Big;
        declare function assertDefined(x: Small): asserts x is Big;
    """.trimIndent()

    /** A type-guard narrows the returned value to the subtype that has the missing props. */
    @Test fun guardNarrowedReturnToSubtype() {
        val source = guardDecls + "\n" + """
            function g(x: Small): Big {
                if (isDefined(x)) {
                    return x;
                }
                throw new Error();
            }
        """.trimIndent() + "\n"
        val result = TypeScriptCompiler().compile(source, "guardRet.ts")
        assertTrue(
            result.diagnostics.none { it.code == 2739 || it.code == 2740 || it.code == 2741 || it.code == 2322 },
            "guard-narrowed return to the subtype must be assignable, got: " +
                result.diagnostics.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    /** The bare-assert form (`assertDefined(x); return x;`) — builder.ts's exact idiom. */
    @Test fun assertNarrowedReturnToSubtype() {
        val source = guardDecls + "\n" + """
            function g(x: Small): Big {
                assertDefined(x);
                return x;
            }
        """.trimIndent() + "\n"
        val result = TypeScriptCompiler().compile(source, "assertRet.ts")
        assertTrue(
            result.diagnostics.none { it.code == 2739 || it.code == 2740 || it.code == 2741 || it.code == 2322 },
            "assert-narrowed return must be assignable, got: " +
                result.diagnostics.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    /**
     * Negative control: WITHOUT the guard, `Base` is missing `Defined`'s required props —
     * the return check must still fire (proves the narrowing, not a blanket suppression).
     */
    @Test fun unnarrowedReturnStillErrors() {
        val source = guardDecls + "\n" + """
            function g(x: Small): Big {
                return x;
            }
        """.trimIndent() + "\n"
        val result = TypeScriptCompiler().compile(source, "unnarrowedRet.ts")
        assertTrue(
            result.diagnostics.any { it.code == 2739 || it.code == 2740 || it.code == 2741 || it.code == 2322 },
            "negative control lost: unnarrowed Small → Big return must error, got: " +
                result.diagnostics.joinToString { "TS${it.code}" },
        )
    }

    /**
     * Negative control: a narrowed value returned where an UNRELATED interface is expected
     * still errors — the narrowed type must FAIL the relation (not be blanket-substituted).
     */
    @Test fun narrowedButUnrelatedReturnErrors() {
        val source = guardDecls + "\n" + """
            type Other = { q: string; r: string };
            function g(x: Small): Other {
                assertDefined(x);
                return x;
            }
        """.trimIndent() + "\n"
        val result = TypeScriptCompiler().compile(source, "wrongRet.ts")
        assertTrue(
            result.diagnostics.any { it.code == 2739 || it.code == 2740 || it.code == 2741 || it.code == 2322 },
            "a Defined-narrowed value returned where an unrelated Other is expected must still error, got: " +
                result.diagnostics.joinToString { "TS${it.code}" },
        )
    }
}
