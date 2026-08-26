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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.41) THE GUARDED REASSIGNMENT of a union-typed reference —
 * `if (typeof c === 'function') c = c();` and its type-assertion sibling
 * `c = (await c(x)) as T` — which the flow walk could not reduce, so every
 * member read after the join was a false TS2339.
 *
 * Neither shape is reachable for any other arm of `narrowByAssignmentRhs`. In
 * the CALL form the callee IS the walked reference, and `getTypeOfExpression`
 * never narrows (CLAUDE.md), so typing `c()` asks about the whole declared
 * union; `resolvedCallReturnTypeForFlow` reads a `FunctionDeclaration`'s return
 * annotation, which a parameter is not. The ANTECEDENT is the callee's type at
 * that point, and that is what this reads.
 *
 * **EVERY POSITIVE IS PAIRED WITH ITS NEGATIVE HALF**, because a pin asserting a
 * diagnostic is GONE cannot tell a fix from a silencing: after the reassignment
 * a member that exists on NEITHER constituent must still report, and must report
 * it against the NARROWED type (`'A'`, not `'A | F'`) — which only a real
 * reduction can produce. Every expectation is tsc 7.0.2's, read off
 * `tools/tsgo-7.0.2/lib/tsc --noEmit` over the same source, including the
 * refusals in the last two tests.
 *
 * The shapes are knip's `plugins/ava/index.ts` and `plugins/eleventy/index.ts`
 * verbatim; the 8-profile dashboard is structurally blind to both, because it is
 * one codebase and tsc's own sources do not write them.
 */
class GuardedReassignmentNarrowingTest {

    private val prelude = """
        type A = { files: string[] };
        type F = () => A;
        type B = { other: number };
        declare const anyv: any;
    """.trimIndent() + "\n"

    // --- the CALL form: `c = c()` --------------------------------------------

    @Test
    fun `a member of the reassigned constituent resolves after a guarded self-call`() {
        val d = diagnose(
            prelude + "export function f(c: A | F) { if (typeof c === 'function') c = c(); return c.files; }"
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `a member on NEITHER constituent still reports after a guarded self-call`() {
        val d = diagnose(
            prelude + "export function f(c: A | F) { if (typeof c === 'function') c = c(); return c.nope; }"
        )
        assert(d.count { it.code == 2339 } == 1)
    }

    /**
     * The half a silencing fix cannot satisfy: the surviving diagnostic must name
     * the REDUCED type. tsc says `Property 'nope' does not exist on type 'A'.`
     */
    @Test
    fun `the surviving diagnostic names the reduced type and not the declared union`() {
        val d = diagnose(
            prelude + "export function f(c: A | F) { if (typeof c === 'function') c = c(); return c.nope; }"
        )
        val messages = d.filter { it.code == 2339 }.map { it.message }
        assert(messages == listOf("Property 'nope' does not exist on type 'A'."))
    }

    @Test
    fun `negative control - without the reassignment the union member read still reports`() {
        val d = diagnose(prelude + "export function f(c: A | F) { return c.files; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    // --- the ASSERTION form: `c = <anything> as T` ---------------------------

    @Test
    fun `a type assertion right-hand side reduces the declared union`() {
        val d = diagnose(
            prelude + "export function f(c: A | F) { if (typeof c === 'function') c = c() as A; return c.files; }"
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `a member on NEITHER constituent still reports after an assertion reassignment`() {
        val d = diagnose(
            prelude + "export function f(c: A | F) { if (typeof c === 'function') c = c() as A; return c.nope; }"
        )
        val messages = d.filter { it.code == 2339 }.map { it.message }
        assert(messages == listOf("Property 'nope' does not exist on type 'A'."))
    }

    /**
     * The assertion selects the OTHER constituent, so the reduction has to be a
     * real one in BOTH directions — the member that used to resolve now does not,
     * and the one that did not now does.
     */
    @Test
    fun `an assertion to the other constituent reduces towards it`() {
        val d = diagnose(
            prelude +
                "export function f(c: A | B) { c = anyv as B; return c.files; }\n" +
                "export function g(c: A | B) { c = anyv as B; return c.other; }"
        )
        val messages = d.filter { it.code == 2339 }.map { it.message }
        assert(messages == listOf("Property 'files' does not exist on type 'B'."))
    }

    // --- the refusals --------------------------------------------------------

    /**
     * `as any` must not launder the union away: `any` relates to every member, so
     * reducing by it is either a no-op or a collapse, and tsc keeps the union.
     */
    @Test
    fun `negative control - an assertion to any does not reduce the union`() {
        val d = diagnose(prelude + "export function f(c: A | B) { c = anyv as any; return c.files; }")
        val messages = d.filter { it.code == 2339 }.map { it.message }
        assert(messages == listOf("Property 'files' does not exist on type 'A | B'."))
    }

    /**
     * With NO guard the self-call is itself an error (TS2349, one constituent is
     * not callable), so there is no post-state to invent: the union survives and
     * BOTH diagnostics appear, exactly as under tsc. The `(F)` in the rendered
     * union is a PRE-EXISTING display divergence (tsc writes `A | F`) that this
     * item does not touch — it is present with and without the fix.
     */
    @Test
    fun `negative control - an UNGUARDED self-call refuses to reduce and keeps both errors`() {
        val d = diagnose(prelude + "export function f(c: A | F) { c = c(); return c.files; }")
        assert(d.count { it.code == 2349 } == 1)
        val messages = d.filter { it.code == 2339 }.map { it.message }
        assert(messages == listOf("Property 'files' does not exist on type 'A | (F)'."))
    }
}
