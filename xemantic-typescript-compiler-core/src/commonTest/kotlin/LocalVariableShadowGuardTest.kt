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
 * (CHK.173) G5 S1 + S3 — a LOCAL variable that shadows an outer binding no longer
 * resolves to the OUTER one where no walk table recorded it.
 *
 * S1 ([LocalShadowGuard.shadowedByLocalVariable], asked at the bottom of
 * `getTypeOfIdentifierConventional`): a read whose name is bound between itself and
 * the file by a parameter / `var` / `let` / `const` / `catch` / `for`-header binding,
 * but which fell through every walk table to a file / global answer, now answers
 * `any`. S3 ([LocalShadowGuard.dropInheritedUninitializedStrings]): a function-top
 * `let x;` no longer keeps the outer `x`'s annotation string in the frame's legacy
 * string map, which reported `x = 1` against the outer type.
 *
 * Every expectation below is tsgo 7.0.2's over the same source (same code, message and
 * 1-based position). The FALSE-POSITIVE pins are silent in tsgo; the CONTROLS carry a
 * tsgo row that must survive — above all the block `function` / `class` / `enum`
 * controls, which put the name in the file's local-variable gate set (`other(zed)`) so
 * the ascent RUNS and must yield to the scope-space consult.
 *
 * Not covered here (the census's S2, per walker): a block / catch / for-header local
 * shadowing a PARAMETER or a FILE-LEVEL variable the declaration walker already
 * recorded — the walk table holds the outer entry, so the ladder never reaches S1.
 */
class LocalVariableShadowGuardTest {

    private val realLibs = "// @strict: true\n// @useRealLibs: true"

    private fun rows(d: List<Diagnostic>): List<String> =
        d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    // --- S1: false positives on legal code, tsgo silent ------------------------------

    @Test
    fun `a destructured catch binding shadowing a lib global does not answer the global`() {
        val d = diagnose(
            """
            export function f() { try { } catch ({ performance }: any) { const p: boolean = performance; takesNum(performance); } }
            declare function takesNum(n: number): void;
            """,
            realLibs,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a for-header let shadowing a lib global is not the global in arithmetic or member reads`() {
        val d = diagnose(
            """
            export function f() { for (let performance = 0; performance < 1; performance++) { performance.toFixed(); takesNum(performance); } }
            declare function takesNum(n: number): void;
            """,
            realLibs,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a catch variable shadowing a lib global reads any when catch variables are any`() {
        val d = diagnose(
            """
            export function f() { try { } catch (performance) { performance.nope; const b: boolean = performance; } }
            """,
            realLibs + "\n// @useUnknownInCatchVariables: false",
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a function-top destructured const read by the member walker does not name the outer type`() {
        val d = diagnose(
            """
            declare const perf: { now(): number } | undefined;
            declare function tryGet(): { perf: { now(): number } } | undefined;
            function f() { const p = tryGet(); if (!p) return; const { perf } = p; const q: boolean = perf; return perf.now() }
            export {}
            """,
        )
        // tsgo also reports the TS2322 below; the outer-typed TS2339 the member walker
        // printed on a `perf.nope` read is the wrong-text shape S1 retires.
        assert(rows(d) == listOf("3:78 TS2322 Type '{ now(): number; }' is not assignable to type 'boolean'."))
    }

    @Test
    fun `a function-top destructured const read through a missing member names no outer union`() {
        val d = diagnose(
            """
            declare const perf: { now(): number } | undefined;
            declare function tryGet(): { perf: { now(): number } } | undefined;
            function f() { const p = tryGet(); if (!p) return; const { perf } = p; perf.nope; }
            export {}
            """,
        )
        assert(d.none { "| undefined" in it.message })
    }

    // --- S3: the function-top `let x;` assignment --------------------------------------

    @Test
    fun `a function-top let without initializer shadowing a file-level const accepts an assignment`() {
        val d = diagnose(
            """
            declare const zed: RegExp | undefined;
            export function f() { let zed; zed = 1; const p: number = zed; }
            """,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a function-top let without initializer shadowing a file-level let accepts an assignment`() {
        val d = diagnose(
            """
            let zed: string = "";
            export function f() { let zed; zed = 1; }
            """,
        )
        assert(d.isEmpty())
    }

    // --- controls ---------------------------------------------------------------------

    @Test
    fun `control - a block function shadowing a file-level const keeps its own type`() {
        val d = diagnose(
            """
            declare const zed: RegExp;
            export function other(zed: number) { return zed; }
            export function f() { { function zed(): number { return 1; } const p: string = zed(); } }
            """,
        )
        assert(rows(d) == listOf("3:68 TS2322 Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `control - a block class shadowing a file-level const keeps its own type`() {
        val d = diagnose(
            """
            declare const zed: RegExp;
            export function other(zed: number) { return zed; }
            export function f() { { class zed { m = 1; } const p: string = new zed().m; } }
            """,
        )
        assert(rows(d) == listOf("3:52 TS2322 Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `control - a block enum shadowing a file-level const keeps its own type`() {
        val d = diagnose(
            """
            declare const zed: RegExp;
            export function other(zed: number) { return zed; }
            export function f() { { enum zed { A } const p: string = zed.A; } }
            """,
        )
        assert(rows(d) == listOf("3:46 TS2322 Type 'zed' is not assignable to type 'string'."))
    }

    @Test
    fun `control - a var redeclaring a parameter without initializer keeps the parameter type`() {
        val d = diagnose(
            """
            export function f(zed: string) { var zed; zed = 1; }
            """,
        )
        assert(rows(d) == listOf(
            "1:38 TS2403 Subsequent variable declarations must have the same type.  Variable 'zed' must be of type 'string', but here has type 'any'.",
            "1:43 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `control - a block var redeclaring a parameter keeps the parameter type after the block`() {
        val d = diagnose(
            """
            export function f(zed: string) { { var zed: string; } const p: number = zed; }
            """,
        )
        assert(rows(d) == listOf("1:61 TS2322 Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `control - a destructured catch binding with no outer binding stays silent`() {
        val d = diagnose(
            """
            export function f() { try { } catch ({ zed }: any) { const p: boolean = zed; } }
            """,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `control - an initialized function-top let shadowing a file-level const keeps its inferred type`() {
        val d = diagnose(
            """
            declare const zed: RegExp;
            export function f() { let zed = 0; const p: string = zed; }
            """,
        )
        assert(rows(d) == listOf("2:42 TS2322 Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `control - a for-of binding types inside the loop and the outer is read after it`() {
        val d = diagnose(
            """
            declare const zed: RegExp;
            export function f() { for (const zed of [1]) { const p: string = zed; } const q: number = zed.lastIndex; }
            """,
        )
        assert(rows(d) == listOf("2:54 TS2322 Type 'number' is not assignable to type 'string'."))
    }
}
