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
 * (CHK.173) G5 S2 slice 2 — block scoping in the DECLARATION / ASSIGNMENT / RETURN
 * readers (the cta walker: `ctaBlockScopeEnter` on the spine, `ctaLegacyScoped` in the
 * legacy statement walker that still checks function bodies nested in an expression).
 *
 * The cta frame chain keeps ONE localTypes family per function, so a `let`/`const`, a
 * catch variable or a `for` binding that a nested scope declares read as the OUTER
 * binding (a false TS2322 on `x = 1` / `const p: number = x`), and a block's inner
 * literal leaked past the block. Every expectation was measured against tsgo 7.0.2
 * (`build/bench/p18213-agent/{mat/mx,nest,nest2,ex,sf}`).
 */
class BlockScopeCtaSlice2Test {

    private fun rowsOf(source: String, directives: String = "// @strict: true"): List<String> =
        diagnose(source, directives).map { "${it.line}:${it.character} TS${it.code}: ${it.message}" }.sorted()

    // ── the assignment reader ───────────────────────────────────────────────

    @Test
    fun `a4 a block annotated let shadowing a file level const accepts its own type`() {
        val rows = rowsOf("declare const zed: RegExp; export function f() { { let zed: number; zed = 1; } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a7 a block annotated let shadowing a parameter accepts its own type`() {
        val rows = rowsOf("export function f(zed: string) { { let zed: number; zed = 1; } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a13 a block let with no annotation shadowing a parameter is not judged as the parameter`() {
        val rows = rowsOf("export function f(zed: string) { { let zed; zed = 1; } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a9 a catch variable shadowing a parameter accepts any assignment`() {
        val rows = rowsOf("export function f(zed: string) { try {} catch (zed) { zed = 1; } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a10 a catch variable shadowing a file level const accepts any assignment`() {
        val rows = rowsOf("declare const zed: RegExp; export function f() { try {} catch (zed) { zed = 1; } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `x1 a for header let shadowing a parameter accepts its own type in the body`() {
        val rows = rowsOf("export function f(zed: string) { for (let zed = 0; zed < 1; ) { zed = 5; } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `z4 a for of let shadowing a parameter accepts its element type`() {
        val rows = rowsOf("export function f(zed: string) { for (let zed of [1]) { zed = 2; } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `x2 a narrowing then block that redeclares the narrowed name accepts the inner type`() {
        val rows = rowsOf("export function f(zed: string | undefined) { if (zed) { let zed: number; zed = 1; } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `x3 a block let in a script file shadowing a global accepts its own type`() {
        val rows = rowsOf("declare const zed2: RegExp; function f() { { let zed2: number; zed2 = 1; } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `u3 case clauses share one scope for the assignment reader`() {
        val rows = rowsOf(
            "export function f(zed: string) { switch (1) { case 1: let zed: number; zed = 2; break; default: zed = 3; } }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `y5 a block let with no annotation is not judged as the parameter`() {
        val rows = rowsOf(
            "declare function takesNum(n: number): void; " +
                "export function f(zed: string) { { let zed; zed = 1; takesNum(zed); } }",
        )
        assert(rows.isEmpty())
    }

    // ── the declaration and return readers ──────────────────────────────────

    @Test
    fun `m10 a block annotated let shadowing a file level const reads its own type`() {
        val rows = rowsOf(
            "interface Outer { o: string }\ndeclare const zed: Outer;\n" +
                "export function f() { { let zed: number = 1; const p: number = zed; } }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `b5 a catch variable is unknown under strict`() {
        val rows = rowsOf("export function f(zed: string) { try {} catch (zed) { const p: number = zed; } }")
        assert(rows == listOf("1:61 TS2322: Type 'unknown' is not assignable to type 'number'."))
    }

    @Test
    fun `m11 a catch variable shadowing a file level const is unknown`() {
        val rows = rowsOf(
            "interface Outer { o: string }\ndeclare const zed: Outer;\n" +
                "export function f() { try {} catch (zed) { const p: number = zed; } }",
        )
        assert(rows == listOf("3:50 TS2322: Type 'unknown' is not assignable to type 'number'."))
    }

    @Test
    fun `n20 an unknown annotated catch variable is unknown`() {
        val rows = rowsOf("export function f(zed: string) { try {} catch (zed: unknown) { const q: number = zed; } }")
        assert(rows == listOf("1:70 TS2322: Type 'unknown' is not assignable to type 'number'."))
    }

    @Test
    fun `n19 an any annotated catch variable is any`() {
        val rows = rowsOf("export function f(zed: string) { try {} catch (zed: any) { const q: number = zed; } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `c1 a catch variable is any when strict is off`() {
        val rows = rowsOf(
            "export function f(zed: string) { try {} catch (zed) { const q: number = zed; } }",
            directives = "// @strict: false",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `u1 a catch variable is any without useUnknownInCatchVariables`() {
        val rows = rowsOf(
            "export function f(zed: string) { try {} catch (zed) { const q: number = zed; } }",
            directives = "// @strict: true\n// @useUnknownInCatchVariables: false",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `x7 a destructured catch leaf is any`() {
        val rows = rowsOf("export function f(zed: string) { try {} catch ({ zed }) { const p: number = zed; } }")
        assert(rows == listOf("1:50 TS2339: Property 'zed' does not exist on type 'unknown'."))
    }

    @Test
    fun `n24 a returned catch variable is unknown`() {
        val rows = rowsOf("export function f(zed: string): number { try { return 1 } catch (zed) { return zed; } }")
        assert(rows == listOf("1:73 TS2322: Type 'unknown' is not assignable to type 'number'."))
    }

    @Test
    fun `b7 a block const does not leak past the block over a parameter`() {
        val rows = rowsOf("export function f(zed: string) { { const zed = 1; } const s: string = zed; zed.charAt(0); }")
        assert(rows.isEmpty())
    }

    @Test
    fun `b8 a block const does not leak past the block over a file level const`() {
        val rows = rowsOf(
            "declare const zed: string; export function f() { { const zed = 1; } const s: string = zed; zed.charAt(0); }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `m13 a block const does not leak over an interface typed file level const`() {
        val rows = rowsOf(
            "interface Outer { o: string }\ndeclare const zed: Outer;\n" +
                "export function f() { { const zed = 1; } const p: Outer = zed; zed.o; }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `b9 a narrowing then block const does not leak past the if`() {
        val rows = rowsOf("export function f(zed: string) { if (zed) { const zed = 1; zed.toFixed(); } const s: string = zed; }")
        assert(rows.isEmpty())
    }

    @Test
    fun `b12 a block inside a case clause does not leak past the switch`() {
        val rows = rowsOf(
            "export function f(zed: string) { switch (1) { case 1: { const zed = 1; zed.toFixed(); } } const s: string = zed; }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `u8 a returned block const reads its own type`() {
        val rows = rowsOf("export function f(zed: string): number { { const zed = 1; return zed; } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `u9 a returned block const of the wrong type is reported with its own type`() {
        val rows = rowsOf("export function f(zed: string): number { { const zed = 'x'; return zed; } }")
        assert(rows == listOf("1:61 TS2322: Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `z1 case clauses share one scope - no per clause false row`() {
        // tsgo reports TS2454 twice for the inner `zed` read in case 2 (not produced here);
        // a per-clause scope would add a false TS2322 judging it as the `string` parameter.
        val rows = rowsOf(
            "export function f(zed: string) { switch (1) { case 1: const zed = 1; break; case 2: zed.toFixed(); const q: number = zed; } }",
        )
        assert(rows.none { it.contains("TS2322") })
    }

    // ── the legacy walker: bodies nested in an expression ───────────────────

    @Test
    fun `n7 an arrow body block let with no annotation is not judged as the outer parameter`() {
        val rows = rowsOf("export function f(zed: string) { [1].forEach(() => { { let zed; zed = 1; } }); }")
        assert(rows.isEmpty())
    }

    @Test
    fun `n2 an arrow body block const does not leak past the block`() {
        val rows = rowsOf("export function f(zed: string) { const g = () => { { const zed = 1; } const s: string = zed; }; }")
        assert(rows.isEmpty())
    }

    @Test
    fun `n3 a function expression catch variable accepts any assignment`() {
        val rows = rowsOf("export function f(zed: string) { const g = function () { try {} catch (zed) { zed = 1; } }; }")
        assert(rows.isEmpty())
    }

    @Test
    fun `n10 an arrow body for header let accepts its own type in the body`() {
        val rows = rowsOf(
            "export function f(zed: string) { const g = () => { for (let zed = 0; zed < 1; zed++) { zed = 5; } }; }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `n17 an arrow inside a catch body reads the catch variable as unknown`() {
        val rows = rowsOf("export function f(zed: string) { try { } catch (zed) { const g = () => { const q: number = zed; }; } }")
        assert(rows == listOf("1:80 TS2322: Type 'unknown' is not assignable to type 'number'."))
    }

    @Test
    fun `t13 a nested function expression block annotation does not leak past the block`() {
        val rows = rowsOf(
            "export function f(zed: string) { const g = function () { { let zed: number = 1; zed = 2; } zed = 'a'; }; }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `t14 an arrow body block annotated let without initializer does not leak`() {
        val rows = rowsOf("export function f(zed: string) { const g = () => { { let zed: number; } zed = 'a'; }; }")
        assert(rows.isEmpty())
    }

    @Test
    fun `t16 an arrow body after block read is the outer parameter`() {
        val rows = rowsOf("export function f(zed: string) { const g = () => { { const zed = 1; } const t: number = zed; }; }")
        assert(rows == listOf("1:77 TS2322: Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `s15 a nested function declaration block annotation does not leak past the block`() {
        val rows = rowsOf("export function f(zed: string) { function g() { { let zed: number = 1; } zed = 'a'; } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `t19 an arrow body block let is judged against its own annotation`() {
        val rows = rowsOf("export function f(zed: string) { const g = () => { { let zed: number = 1; zed = 'b'; } }; }")
        assert(rows == listOf("1:75 TS2322: Type 'string' is not assignable to type 'number'."))
    }

    // ── the (CHK.64)(ii) early-exit rule stays off in a shadow-only scope ───

    private val earlyExitPrelude =
        "declare let c: boolean; export function f(zed: string, o: string | undefined, u: number | string) "

    @Test
    fun `g3 an early exit in a shadowing block does not survive a later reassignment in a loop`() {
        val rows = rowsOf(earlyExitPrelude + "{ let s: string; { const zed = 1; if (!o) return; for (;;) { s = o; o = undefined; } } }")
        assert(rows == listOf("1:160 TS2322: Type 'string | undefined' is not assignable to type 'string'."))
    }

    @Test
    fun `g4 an early exit in a shadowing block does not survive a later reassignment`() {
        // tsgo prints `Type 'undefined'` (the assignment-narrowed type); the row is what is pinned.
        val rows = rowsOf(earlyExitPrelude + "{ let s: string; { const zed = 1; if (o === undefined) return; o = undefined; s = o; } }")
        assert(rows.size == 1 && rows[0].startsWith("1:177 TS2322: "))
    }

    // ── controls ────────────────────────────────────────────────────────────

    @Test
    fun `control - a var in a nested function block is function scoped and keeps its annotation`() {
        val rows = rowsOf("export function f(zed: string) { function g() { { var zed: number = 1; } zed = 'a'; } }")
        assert(rows == listOf("1:74 TS2322: Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `control - a var in an arrow body block keeps its annotation after the block`() {
        val rows = rowsOf("export function f(zed: string) { const g = () => { { var zed: number = 1; } zed = 'a'; }; }")
        assert(rows == listOf("1:77 TS2322: Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `control - a var in a block redeclaring a parameter is not block scoped`() {
        val rows = rowsOf("export function f(zed: string) { { var zed = 1; } }")
        assert(rows == listOf(
            "1:40 TS2403: Subsequent variable declarations must have the same type.  Variable 'zed' must be of type 'string', but here has type 'number'.",
        ))
    }

    @Test
    fun `control - an after block read after a shadowing block is the parameter`() {
        val rows = rowsOf("export function f(zed: string) { { const zed = 1; } zed = 'b'; const t: number = zed; }")
        assert(rows == listOf("1:70 TS2322: Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `control - a nested block reads the enclosing block's const`() {
        val rows = rowsOf("export function f(zed: string) { { const zed = 1; { const q: string = zed; } } }")
        assert(rows == listOf("1:59 TS2322: Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `control - a then block that redeclares the narrowed name reads the inner const`() {
        val rows = rowsOf("export function f(zed: string | undefined) { if (zed) { const zed = 1; const q: string = zed; } }")
        assert(rows == listOf("1:78 TS2322: Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `control - a for of const is read as its element type`() {
        val rows = rowsOf("export function f(zed: string) { for (const zed of ['a']) { const q: number = zed; } }")
        assert(rows == listOf("1:67 TS2322: Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `control - a block const with no outer name is unchanged`() {
        val rows = rowsOf("export function f() { { const zed = 1; const s: string = zed; } }")
        assert(rows == listOf("1:46 TS2322: Type 'number' is not assignable to type 'string'."))
    }
}
