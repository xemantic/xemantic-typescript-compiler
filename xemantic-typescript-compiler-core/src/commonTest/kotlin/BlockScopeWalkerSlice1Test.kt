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
 * (CHK.173) G5 S2 slice 1 — block scoping in the ARITHMETIC walker
 * (`spineArithOpenBlockScope`) and the MEMBER-ACCESS walker (`cpaOpenBlockScope` on the
 * spine, `cpaLegacyOpenScope` in the legacy statement walker that still checks function
 * bodies nested in an expression).
 *
 * Both walkers keep ONE flat map per function, so a `let`/`const` declared by a nested
 * block, a `catch`, a case block or a `for` header that shadows an outer name read as
 * the OUTER binding (false TS2365 / TS2551 / TS2339 / TS18048 on legal code), and a
 * block's inner recording leaked past the block. Every expectation here was measured
 * against tsgo 7.0.2 (`build/bench/p18212-agent/{mx,nest,lk}`); where tsgo reports a
 * row this slice does not produce (a catch variable's `unknown`, TS2454 across case
 * clauses), the pin asserts only the absence of the false row and says so.
 */
class BlockScopeWalkerSlice1Test {

    private fun rowsOf(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code}: ${it.message}" }.sorted()

    // ── arith: for-header and block frames ──────────────────────────────────

    @Test
    fun `a11 a for header let shadowing a string parameter is silent`() {
        val rows = rowsOf("export function f(zed: string) { for (let zed = 0; zed < 1; zed++) {} }")
        assert(rows.isEmpty())
    }

    @Test
    fun `b1 a for header let shadowing a parameter is silent in its body`() {
        val rows = rowsOf(
            "export function f(zed: string) { for (let zed = 0; zed < 1; zed++) { const p: number = zed; } }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `b3 a for header let shadowing a file level const is silent`() {
        val rows = rowsOf(
            "declare const zed: string; export function f() { for (let zed = 0; zed < 1; zed++) { zed.toFixed(); } }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `y10 a for header binding does not leak past the loop`() {
        val rows = rowsOf(
            "export function f(zed: string) { for (let zed = 0; zed < 1; zed++) {} zed.charAt(0); const s: string = zed; }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `z2 a block const shadowing a parameter does not leak into a later comparison`() {
        val rows = rowsOf("export function f(zed: string) { { const zed = 1; } if (zed < \"a\") {} }")
        assert(rows.isEmpty())
    }

    @Test
    fun `e1 a block const shadowing a file level const does not leak into a later comparison`() {
        val rows = rowsOf("declare const zed: string; export function f() { { const zed = 1; } if (zed < \"a\") {} }")
        assert(rows.isEmpty())
    }

    @Test
    fun `l5 a block const does not leak over a file level const the walker never recorded`() {
        // The outer `zed` is absent from the walker's map (a call initializer is not recorded)
        // but bound at file level: the scope notes it absent so the inner `1` is dropped at the
        // block's end instead of reaching `zed < "a"` (tsgo: silent, `zed` is `string`).
        val rows = rowsOf(
            "declare function mks(): string; const zed = mks(); export function f() { { const zed = 1; } if (zed < \"a\") {} }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `e5 a catch variable shadowing a parameter is not compared as the parameter`() {
        // tsgo reports TS18046 ('zed' is of type 'unknown') — the catch variable's type is slice 2.
        val rows = rowsOf("export function f(zed: string) { try {} catch (zed) { if (zed < 1) {} } }")
        assert(rows.none { it.contains("TS2365") })
    }

    @Test
    fun `e2 a var in a shadowing block survives the block for arithmetic`() {
        val rows = rowsOf("export function f(zed: string) { { const zed = 1; var w = 2; } if (w < \"a\") {} }")
        assert(rows == listOf("1:68 TS2365: Operator '<' cannot be applied to types 'number' and 'string'."))
    }

    // ── cpa on the spine ────────────────────────────────────────────────────

    @Test
    fun `b2 a for header let shadowing a parameter is silent for arithmetic and members`() {
        val rows = rowsOf("export function f(zed: string) { for (let zed = 0; zed < 1; zed++) { zed.toFixed(); } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `b6 a block const shadowing a string parameter is silent`() {
        val rows = rowsOf("export function f(zed: string) { { const zed = 1; zed.toFixed(); const q: number = zed; } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `m8 a block destructured const shadowing a parameter is silent`() {
        val rows = rowsOf(
            """
            declare function mk(): { zed: number };
            interface Outer { o: string }
            export function f(zed: Outer) { { const { zed } = mk(); zed.toFixed(); } }
            """,
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `m9 a block annotated let shadowing a parameter is silent`() {
        val rows = rowsOf(
            """
            interface Outer { o: string }
            export function f(zed: Outer) { { let zed: number = 1; zed.toFixed(); } }
            """,
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `x6 a case clause const shadowing a parameter is silent`() {
        val rows = rowsOf(
            "export function f(zed: string) { switch (1) { case 1: const zed = 1; zed.toFixed(); const q: number = zed; } }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `z1 case clauses share one scope`() {
        // tsgo reports TS2678 and TS2454 twice (the INNER zed, used before assigned in case 2).
        val rows = rowsOf(
            "export function f(zed: string) { switch (1) { case 1: const zed = 1; break; case 2: zed.toFixed(); } }",
        )
        assert(rows.none { it.contains("TS2551") })
    }

    @Test
    fun `x15 if then and else blocks each shadow a parameter`() {
        val rows = rowsOf(
            "export function f(zed: string) { if (zed.length) { const zed = 1; zed.toFixed(); } else { const zed = true; const b: boolean = zed; } }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `x18 a labeled block shadows a parameter`() {
        val rows = rowsOf("export function f(zed: string) { label: { const zed = 1; zed.toFixed(); } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `y1 a catch variable is not read as the parameter`() {
        // tsgo reports TS18046 at `zed.toFixed()` — the catch variable's `unknown` is slice 2.
        val rows = rowsOf(
            "declare function takesNum(n: number): void; export function f(zed: string) { try {} catch (zed) { takesNum(zed as number); zed.toFixed(); } }",
        )
        assert(rows.none { it.contains("TS2551") })
    }

    @Test
    fun `y11 a block const from a member read shadows a parameter`() {
        val rows = rowsOf(
            "declare function mk(): { zed: number }; export function f(zed: string) { { const zed = mk().zed; zed.toFixed(); } zed.charAt(0); }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `z7 a block inside a case clause shadows a parameter`() {
        val rows = rowsOf(
            "export function f(zed: string) { switch (zed) { case \"a\": { const zed = 1; zed.toFixed(); } } }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `a block const shadowing a nullable parameter is not reported possibly undefined`() {
        // The (P18.211) Round A arm read the OUTER `RegExp | undefined` here: TS18048 on legal code.
        val rows = rowsOf("export function f(zed: RegExp | undefined) { { const zed = 1; zed.toFixed(); } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `p1 a block const records its element access type`() {
        val rows = rowsOf("export function f(zed: string) { { const zed = [1][0]; zed.nope; } }")
        assert(rows == listOf("1:60 TS2339: Property 'nope' does not exist on type 'number'."))
    }

    @Test
    fun `e7 a nested block reads the inner const and the after block read the parameter`() {
        val rows = rowsOf("export function f(zed: string) { { const zed = [1][0]; { zed.nope; } } zed.nope; }")
        assert(
            rows == listOf(
                "1:62 TS2339: Property 'nope' does not exist on type 'number'.",
                "1:76 TS2339: Property 'nope' does not exist on type 'string'.",
            ),
        )
    }

    @Test
    fun `e8 a block after a for of loop reads its own const`() {
        val rows = rowsOf(
            "export function f(zed: string) { for (const zed of [[1][0]]) { const q = zed; } { const zed = [1][0]; zed.nope; } }",
        )
        assert(rows == listOf("1:107 TS2339: Property 'nope' does not exist on type 'number'."))
    }

    @Test
    fun `e9 a block const with no outer name is unchanged`() {
        val rows = rowsOf("export function f() { { const zed = [1][0]; zed.nope; } }")
        assert(rows == listOf("1:49 TS2339: Property 'nope' does not exist on type 'number'."))
    }

    @Test
    fun `p2 control - an after block read is the parameter`() {
        val rows = rowsOf("export function f(zed: string) { { const zed = 1; } zed.nope; }")
        assert(rows == listOf("1:57 TS2339: Property 'nope' does not exist on type 'string'."))
    }

    @Test
    fun `p3 control - a var in a block is not block scoped`() {
        val rows = rowsOf("export function f(zed: string) { { var zed = \"x\"; zed.nope; } }")
        assert(rows == listOf("1:55 TS2339: Property 'nope' does not exist on type 'string'."))
    }

    @Test
    fun `e3 control - a var recorded in a shadowing block survives it`() {
        val rows = rowsOf("export function f(zed: string) { { const zed = [1][0]; var w = [1][0]; } w.nope; zed.nope; }")
        assert(
            rows == listOf(
                "1:76 TS2339: Property 'nope' does not exist on type 'number'.",
                "1:86 TS2339: Property 'nope' does not exist on type 'string'.",
            ),
        )
    }

    @Test
    fun `p4 control - an inner member read does not leak past the block`() {
        val rows = rowsOf(
            "declare function mk(): { zed: number }; export function f(zed: string) { { const zed = mk().zed; } zed.charAt(0); }",
        )
        assert(rows.isEmpty())
    }

    // ── cpa in the legacy walker: bodies nested in an expression ──────────

    @Test
    fun `n01 an arrow body block shadowing its parameter is silent`() {
        val rows = rowsOf("export const g = (zed: string) => { { const zed = 1; zed.toFixed() } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `n03 an arrow body for header shadowing its parameter is silent`() {
        val rows = rowsOf("export const g = (zed: string) => { for (let zed = 0; zed < 1; zed++) { zed.toFixed() } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `n07 a function expression body block shadowing its parameter is silent`() {
        val rows = rowsOf("export const g = function (zed: string) { { const zed = 1; zed.toFixed() } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `n08 an arrow body block reads the inner const and the after block read the parameter`() {
        val rows = rowsOf("export const g = (zed: string) => { { const zed = [1][0]; zed.nope } zed.nope }")
        assert(
            rows == listOf(
                "1:63 TS2339: Property 'nope' does not exist on type 'number'.",
                "1:74 TS2339: Property 'nope' does not exist on type 'string'.",
            ),
        )
    }

    @Test
    fun `n10 arrow body case clauses share one scope`() {
        // tsgo additionally reports TS2454 at `zed` (the inner binding, used before assigned).
        val rows = rowsOf(
            "export const g = (zed: string) => { switch (1) { case 1: const zed = 1; break; case 2: zed.toFixed() } }",
        )
        assert(rows == listOf("1:85 TS2678: Type '2' is not comparable to type '1'."))
    }

    @Test
    fun `n11 control - a var in an arrow body block is not block scoped`() {
        val rows = rowsOf("export const g = (zed: string) => { { var zed = \"x\"; zed.nope } }")
        assert(rows == listOf("1:58 TS2339: Property 'nope' does not exist on type 'string'."))
    }
}
