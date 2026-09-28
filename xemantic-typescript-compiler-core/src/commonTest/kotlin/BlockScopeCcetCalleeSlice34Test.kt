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
 * (CHK.173) G5 S2 slices 3 and 4 — block scoping in the ARGUMENT walker (ccet:
 * `ccetOpenBlockScope`, and the body pre-scan leaving nested `let`/`const` and `for`
 * headers to it) and the scope-space consult in the CALLEE resolver
 * (`getCalleeType` -> `NameResolver.lexicalValueSymbolForNode`).
 *
 * The argument walker's frame is one map per function, and its body pre-scan skipped a
 * destructured leaf named like a parameter and never saw a `for` header or a `catch`
 * variable, so each read as the PARAMETER at a call argument (a false TS2345); an inner
 * recording also leaked past its block. A `function` nested in a block or body is bound in
 * no conventional table (B83.5), so a same-named outer binding answered its calls (a false
 * TS2349, and the nested function's own parameters went unchecked). Every expectation was
 * measured against tsgo 7.0.2 (`build/bench/p18214-agent/{cells,cells4,pins}`).
 */
class BlockScopeCcetCalleeSlice34Test {

    private fun rowsOf(source: String, directives: String = "// @strict: true"): List<String> =
        diagnose(source, directives).map { "${it.line}:${it.character} TS${it.code}: ${it.message}" }.sorted()

    private val prelude = "declare function takesNum(n: number): void; declare function takesBool(b: boolean): void; " +
        "declare function mk(): { zed: number };"

    // ── slice 3: the argument walker ────────────────────────────────────────

    @Test
    fun `x13 a for header let shadowing a parameter is not read as the parameter at an argument`() {
        val rows = rowsOf("$prelude export function f(zed: string) { for (let zed = 0; zed < 1; zed++) { takesNum(zed); } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `y7 a for header const shadowing a parameter is not read as the parameter at an argument`() {
        val rows = rowsOf("$prelude export function f(zed: string) { for (const zed = 1; ; ) { takesNum(zed); break; } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `y2 a block destructured leaf shadowing a parameter is not read as the parameter`() {
        val rows = rowsOf("$prelude export function f(zed: string) { { const { zed } = mk(); takesNum(zed); } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `y8 a narrowed catch variable shadowing a parameter is not read as the parameter`() {
        val rows = rowsOf("$prelude export function f(zed: string) { try {} catch (zed) { if (typeof zed === 'number') takesNum(zed); } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a block destructured leaf reads its own type at an argument`() {
        val rows = rowsOf("$prelude export function f(zed: string) { { const { zed } = mk(); takesBool(zed); } }")
        assert(rows == listOf("1:198 TS2345: Argument of type 'number' is not assignable to parameter of type 'boolean'."))
    }

    @Test
    fun `an untyped catch variable reads unknown at an argument under strict`() {
        val rows = rowsOf("$prelude export function f(zed: string) { try {} catch (zed) { takesBool(zed); } }")
        assert(rows == listOf("1:195 TS2345: Argument of type 'unknown' is not assignable to parameter of type 'boolean'."))
    }

    @Test
    fun `an annotated block const reads its own type at an argument`() {
        val rows = rowsOf("$prelude export function f(zed: string) { { const zed: boolean = true; takesNum(zed); } }")
        assert(rows == listOf("1:202 TS2345: Argument of type 'boolean' is not assignable to parameter of type 'number'."))
    }

    @Test
    fun `the parameter is read again after the shadowing block closes`() {
        val rows = rowsOf("$prelude export function f(zed: string) { { const { zed } = mk(); } takesNum(zed); }")
        assert(rows == listOf("1:199 TS2345: Argument of type 'string' is not assignable to parameter of type 'number'."))
    }

    @Test
    fun `the parameter is read again after a shadowing for header`() {
        val rows = rowsOf("$prelude export function f(zed: string) { for (let zed = 0; zed < 1; zed++) {} takesNum(zed); }")
        assert(rows == listOf("1:210 TS2345: Argument of type 'string' is not assignable to parameter of type 'number'."))
    }

    @Test
    fun `a file level const is read again after a block destructured leaf of its name`() {
        val rows = rowsOf("$prelude declare const zed: string; export function f() { { const { zed } = mk(); } takesNum(zed); }")
        assert(rows == listOf("1:215 TS2345: Argument of type 'string' is not assignable to parameter of type 'number'."))
    }

    @Test
    fun `a file level function is read again after a block destructured leaf of its name`() {
        // The block's leaf enters the side set (`any`) at the scope's enter; the restore
        // must take it out again, or the outer function reads `any` past the block.
        val rows = rowsOf("$prelude declare function zed(): void; export function f() { { const { zed } = mk(); } takesNum(zed); }")
        assert(rows == listOf("1:218 TS2345: Argument of type '() => void' is not assignable to parameter of type 'number'."))
    }

    @Test
    fun `a destructured parameter is read again after a block const of its name`() {
        val rows = rowsOf("$prelude export function f({ zed }: { zed: string }) { { const zed = 1; takesNum(zed); } takesNum(zed); }")
        assert(rows == listOf("1:220 TS2345: Argument of type 'string' is not assignable to parameter of type 'number'."))
    }

    @Test
    fun `a block destructured leaf does not leak past its block`() {
        val rows = rowsOf("$prelude export function f() { { const { zed } = { zed: 1 }; } takesBool(zed); }")
        assert(rows == listOf("1:195 TS2304: Cannot find name 'zed'."))
    }

    @Test
    fun `two sibling blocks each read their own literal const`() {
        val rows = rowsOf("$prelude export function f() { { const s = \"a\"; takesNum(s); } { const s = 1; takesNum(s); } }")
        assert(rows == listOf("1:179 TS2345: Argument of type 'string' is not assignable to parameter of type 'number'."))
    }

    @Test
    fun `negative control - a block var is function scoped and still reaches the argument after the block`() {
        val rows = rowsOf("$prelude export function f() { { var zed = \"x\"; } takesNum(zed); }")
        assert(rows == listOf("1:181 TS2345: Argument of type 'string' is not assignable to parameter of type 'number'."))
    }

    @Test
    fun `a case clause const is shared by the later clauses and not read as the parameter`() {
        // tsgo also reports TS2454 at 1:235 (used before assigned) — not produced here.
        val rows = rowsOf("$prelude export function f(zed: string, k: number) { switch (k) { case 1: const zed = 1; break; case 2: takesNum(zed); } }")
        assert(rows.none { it.contains("TS2345") })
    }

    // ── slice 4: the callee resolver ────────────────────────────────────────

    @Test
    fun `x5a a block function shadowing a file level const is callable`() {
        val rows = rowsOf("declare const g5: string; export function h() { { function g5(n: number) { return n; } g5(1); } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `x5b a body function shadowing a file level const checks its own parameters`() {
        val rows = rowsOf("declare const g6: string; export function h() { function g6(n: number) { return n; } return g6(\"s\"); }")
        assert(rows == listOf("1:96 TS2345: Argument of type 'string' is not assignable to parameter of type 'number'."))
    }

    @Test
    fun `a nested overload set shadowing a file level const resolves as a set`() {
        val rows = rowsOf(
            """
            declare const g1: string;
            export function h1() { function g1(n: number): number; function g1(s: string): string; function g1(x: any) { return x; } g1(true); g1(1); g1("a"); }
            """
        )
        assert(rows.size == 1 && rows[0].startsWith("2:125 TS2769: No overload matches this call."))
    }

    @Test
    fun `a block const shadowing a body function is the callee inside the block only`() {
        val rows = rowsOf("export function h3() { function f(n: number) { return n; } { const f = \"s\"; f(); } return f(1); }")
        assert(rows == listOf("1:77 TS2349: This expression is not callable."))
    }

    @Test
    fun `a block function shadowing a callback parameter checks its own parameters inside the block`() {
        val rows = rowsOf("export function h7(cb: (n: number) => void) { { function cb(s: string) {} cb(1); } cb(\"s\"); }")
        assert(rows == listOf(
            "1:78 TS2345: Argument of type 'number' is not assignable to parameter of type 'string'.",
            "1:87 TS2345: Argument of type 'string' is not assignable to parameter of type 'number'.",
        ))
    }

    @Test
    fun `negative control - a nested function's parameter shadows an enclosing nested function`() {
        val rows = rowsOf("export function h2() { function f(n: number) { return n; } function inner(f: string) { f(); } return inner; }")
        assert(rows == listOf("1:88 TS2349: This expression is not callable."))
    }
}
