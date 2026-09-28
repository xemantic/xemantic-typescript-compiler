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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.173) G5 residue c6 — a `function` nested in a function body whose name collides with
 * an OUTER binding. `shadowNestedFunctionNames` writes `any` into the walk table for it (so
 * the outer declaration never answers), which left step 10b's scope-space override nothing
 * to replace: the call, and every value read, typed `any` and the nested function's own
 * parameters went unchecked. `Checker.shadowedScopeValueType` consults
 * `NameResolver.lexicalValueSymbolForNode` for a TABLE `any` at both the value read and the
 * callee. A UNIQUE nested name (a lookup miss, no table entry) keeps its `any` — step 10b's
 * measured rule — and a nearer value-space binding stops the ascent.
 *
 * Every expectation is tsgo 7.0.2's (`build/bench/p18215-agent/mat`).
 */
class NestedFunctionShadowAnyConsultTest {

    private fun rowsOf(source: String): List<String> =
        diagnose(source, "// @strict: true").map { "${it.line}:${it.character} TS${it.code}: ${it.message}" }.sorted()

    private fun codesOf(source: String): List<String> =
        diagnose(source, "// @strict: true").map { "${it.line}:${it.character} TS${it.code}" }.sorted()

    private val outerFn = "declare function g(n: number): number;\n"

    @Test
    fun `c6 a nested function named like an outer function checks its own parameters`() {
        val rows = rowsOf(outerFn + "export function h() { function g(s: string) { return s; } return g(1); }")
        assert(rows == listOf("2:68 TS2345: Argument of type 'number' is not assignable to parameter of type 'string'."))
    }

    @Test
    fun `negative control - a matching argument to the nested function is silent`() {
        val rows = rowsOf(outerFn + "export function h() { function g(s: string) { return s; } return g(\"x\"); }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a nested function named like an outer class checks its own parameters`() {
        val rows = rowsOf("declare class g { }\nexport function h() { function g(n: number) { return n; } g(\"x\"); }")
        assert(rows == listOf("2:61 TS2345: Argument of type 'string' is not assignable to parameter of type 'number'."))
    }

    @Test
    fun `a nested overload set named like an outer function resolves as a set`() {
        val rows = codesOf(
            outerFn + "export function h() { function g(a: string): void; function g(a: boolean): void; " +
                "function g(a: any) {} g(1); g(\"x\"); }",
        )
        assert(rows == listOf("2:106 TS2769"))
    }

    @Test
    fun `a nested function called before its declaration is hoisted`() {
        val rows = rowsOf(outerFn + "export function h() { const r = g(1); function g(s: string) { return s; } return r; }")
        assert(rows == listOf("2:35 TS2345: Argument of type 'number' is not assignable to parameter of type 'string'."))
    }

    @Test
    fun `a recursive nested function calls itself`() {
        val rows = rowsOf(outerFn + "export function h() { function g(s: string): string { return g(1); } return g; }")
        assert(rows == listOf("2:64 TS2345: Argument of type 'number' is not assignable to parameter of type 'string'."))
    }

    /** Only the declaration read: `const f2 = g; f2(1)` is TS2345 at 2:76 in tsgo and silent
     *  here — an un-annotated `const` initialised with a function types `any` at its call
     *  whatever the initialiser (the same gap as `const g = (s: string) => s; g(1)`), a
     *  residue of its own. */
    @Test
    fun `a value read of the nested function is the nested function`() {
        val rows = rowsOf(outerFn + "export function h() { function g(s: string) { return s; } const k: number = g; return k; }")
        assert(rows == listOf("2:65 TS2322: Type '(s: string) => string' is not assignable to type 'number'."))
    }

    @Test
    fun `the nested function passed as an argument is the nested function`() {
        val rows = rowsOf(
            outerFn + "declare function take(cb: (n: number) => void): void;\n" +
                "export function h() { function g(s: string) { return s; } take(g); }",
        )
        assert(
            rows == listOf(
                "3:64 TS2345: Argument of type '(s: string) => string' is not assignable to parameter of type '(n: number) => void'.",
            ),
        )
    }

    @Test
    fun `the nested function's return type reaches a declaration`() {
        val rows = rowsOf(outerFn + "export function h() { function g(s: string) { return s; } const x: number = g(\"a\"); return x; }")
        assert(rows == listOf("2:65 TS2322: Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `a call from an inner function reaches the enclosing nested function`() {
        val rows = rowsOf(
            outerFn + "export function h() { function g(s: string) { return s; } function inner() { return g(1); } return inner; }",
        )
        assert(rows == listOf("2:87 TS2345: Argument of type 'number' is not assignable to parameter of type 'string'."))
    }

    @Test
    fun `the innermost of two nested functions wins`() {
        val rows = rowsOf(
            outerFn + "export function h() { function g(s: string) { return s; } function inner() { " +
                "function g(b: boolean) { return b; } return g(1); } return inner; }",
        )
        assert(rows == listOf("2:124 TS2345: Argument of type 'number' is not assignable to parameter of type 'boolean'."))
    }

    @Test
    fun `negative control - an inner typed parameter shadows the nested function`() {
        val rows = rowsOf(
            outerFn + "export function h() { function g(s: string) { return s; } " +
                "function inner(g: (n: number) => void) { g(1); g(\"x\"); } return inner; }",
        )
        assert(rows == listOf("2:108 TS2345: Argument of type 'string' is not assignable to parameter of type 'number'."))
    }

    /** Discriminates the rejected design (drop the table `any` instead of consulting past it):
     *  an `any`-annotated parameter is in no walk table, so without the inherited `any` the
     *  outer `declare function g(n: number)` would answer `g("x")`. */
    @Test
    fun `negative control - an inner any parameter shadows the nested function`() {
        val rows = rowsOf(
            outerFn + "export function h() { function g(s: string) { return s; } " +
                "function inner(g: any) { g(1); g(\"x\"); } return inner; }",
        )
        assert(rows.isEmpty())
    }

    /** RESIDUE, not a control: tsgo reports TS2345 at 1:70. A UNIQUE nested name has no table
     *  entry and keeps its `any` — step 10b's measured rule (making it real costs 19-20
     *  ours-only rows on every profile). This pin flips when that rule is lifted. */
    @Test
    fun `residue - a unique nested function name still types any at the call`() {
        val rows = rowsOf("export function h() { function g2(s: string) { return s; } return g2(1); }")
        assert(rows.isEmpty())
    }
}
