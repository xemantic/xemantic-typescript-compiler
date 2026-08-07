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
 * (M0.4, round 654): pins for the checkIncDecTypeParamOperands (TS2356 — a
 * `++`/`--` whose operand is `this.X` for a class property, or a local
 * variable, annotated as a bare UNCONSTRAINED type parameter) spine migration.
 *
 * The legacy pass threaded a TRIPLE of name sets down a statement + expression
 * recursion: `tparams` (in-scope UNCONSTRAINED type-param names, accumulated at
 * class / function-declaration / method boundaries), `tpProps` (the enclosing
 * class DECLARATION's bare-TP-annotated properties, rebuilt per class) and
 * `tpLocals` (bare-TP-annotated locals, rebuilt per function-like BODY by a
 * body-wide prepass whose descent is NARROWER than the scan's — it never
 * enters nested function or class bodies).
 *
 * TS2356 has three other emitters in the checker (the arithmetic pass, the
 * for-in/c-for redeclaration walker, and a corpus-unique pinDiag walker), all
 * position-disjoint from this one; every fixture here is shaped so only this
 * pass can fire — the operand is a bare unconstrained type parameter, which
 * the arithmetic pass classifies as neither number-like nor a bail.
 *
 * Frozen REACH quirks pinned in BOTH directions: only ClassDeclaration and
 * FunctionDeclaration open scopes, so arrows, function EXPRESSIONS and class
 * EXPRESSIONS are NEVER walked; a class PROPERTY INITIALIZER is walked with
 * BOTH name sets EMPTIED (so `this.tp++` there draws nothing) and a class
 * STATIC BLOCK is not walked at all; the expression walk covers call
 * arguments / element access / ternaries / array literals / casts but NOT
 * object literals, template spans, `await`/`typeof`/`void`/`delete` operands
 * or comma lists. All expectations verified against the legacy pass FIRST.
 */
class M04IncDecTypeParamSpineMigrationTest {

    private fun ts2356(ds: List<Diagnostic>) = ds.count { it.code == 2356 }

    // ── Core emissions ────────────────────────────────────────────────────

    @Test
    fun `this-property typed as a bare unconstrained type param draws TS2356`() {
        val ds = diagnose(
            """
            class C<T> {
              a!: T;
              foo() { this.a++; }
            }
            """
        )
        assert(ts2356(ds) == 1)
        val d = ds.single { it.code == 2356 }
        assert(d.length == "this.a".length)
    }

    @Test
    fun `a local typed as a bare unconstrained type param draws TS2356`() {
        val ds = diagnose(
            """
            function f<T>(): void {
              let x!: T;
              x++;
            }
            """
        )
        assert(ts2356(ds) == 1)
        assert(ds.single { it.code == 2356 }.length == "x".length)
    }

    @Test
    fun `prefix and postfix - increment and decrement all fire`() {
        val ds = diagnose(
            """
            function f<T>(): void {
              let x!: T;
              x++;
              x--;
              ++x;
              --x;
            }
            """
        )
        assert(ts2356(ds) == 4)
    }

    @Test
    fun `a parenthesized operand still fires`() {
        val ds = diagnose(
            """
            function f<T>(): void {
              let x!: T;
              (x)++;
            }
            """
        )
        assert(ts2356(ds) == 1)
    }

    @Test
    fun `negative control - a CONSTRAINED type param is never flagged`() {
        val ds = diagnose(
            """
            class C<T extends number> {
              a!: T;
              foo() { this.a++; }
            }
            function f<U extends string>(): void {
              let x!: U;
              x++;
            }
            """
        )
        assert(ts2356(ds) == 0)
    }

    @Test
    fun `negative control - a type param reference WITH type arguments is not bare`() {
        val ds = diagnose(
            """
            class C<T> {
              a!: Array<T>;
              foo() { this.a++; }
            }
            """
        )
        assert(ts2356(ds) == 0)
    }

    @Test
    fun `negative control - a concrete annotation draws nothing`() {
        val ds = diagnose(
            """
            class C<T> {
              a!: number;
              foo() { this.a++; }
            }
            """
        )
        assert(ts2356(ds) == 0)
    }

    @Test
    fun `negative control - a non-this receiver draws nothing`() {
        val ds = diagnose(
            """
            class C<T> {
              a!: T;
              foo(other: C<T>) { other.a++; }
            }
            """
        )
        assert(ts2356(ds) == 0)
    }

    // ── Type-param scope accumulation ─────────────────────────────────────

    @Test
    fun `a method's OWN type params are in scope for its locals`() {
        val ds = diagnose(
            """
            class C {
              foo<M>(): void {
                let x!: M;
                x++;
              }
            }
            """
        )
        assert(ts2356(ds) == 1)
    }

    @Test
    fun `the class type param is in scope inside a nested function declaration`() {
        val ds = diagnose(
            """
            class C<T> {
              foo(): void {
                function inner(): void {
                  let x!: T;
                  x++;
                }
              }
            }
            """
        )
        assert(ts2356(ds) == 1)
    }

    @Test
    fun `an outer function's type param is in scope in a nested function declaration`() {
        val ds = diagnose(
            """
            function outer<T>(): void {
              function inner(): void {
                let x!: T;
                x++;
              }
            }
            """
        )
        assert(ts2356(ds) == 1)
    }

    @Test
    fun `a constructor body sees the class type param and its properties`() {
        val ds = diagnose(
            """
            class C<T> {
              a!: T;
              constructor() { this.a++; }
            }
            """
        )
        assert(ts2356(ds) == 1)
    }

    @Test
    fun `accessor bodies see the class properties`() {
        val ds = diagnose(
            """
            class C<T> {
              a!: T;
              get g(): number { this.a++; return 1; }
              set s(v: number) { this.a--; }
            }
            """
        )
        assert(ts2356(ds) == 2)
    }

    @Test
    fun `negative control - tpProps is EMPTIED inside a nested function declaration`() {
        // FROZEN: a FunctionDeclaration body is entered with tpProps =
        // emptySet(), so `this.a++` inside it draws nothing even though the
        // class property is a bare type param.
        val ds = diagnose(
            """
            class C<T> {
              a!: T;
              foo(): void {
                function inner(): void { this.a++; }
              }
            }
            """
        )
        assert(ts2356(ds) == 0)
    }

    @Test
    fun `negative control - a class PROPERTY INITIALIZER is walked with both sets emptied`() {
        val ds = diagnose(
            """
            class C<T> {
              a!: T;
              b = this.a++;
            }
            """
        )
        assert(ts2356(ds) == 0)
    }

    @Test
    fun `negative control - a STATIC BLOCK is not walked`() {
        val ds = diagnose(
            """
            class C<T> {
              static a: any;
              static { let x!: T; x++; }
            }
            """
        )
        assert(ts2356(ds) == 0)
    }

    // ── Never-walked function-like boundaries ─────────────────────────────

    @Test
    fun `negative control - an ARROW body is never walked`() {
        val ds = diagnose(
            """
            function f<T>(): void {
              const g = () => { let x!: T; x++; };
            }
            """
        )
        assert(ts2356(ds) == 0)
    }

    @Test
    fun `negative control - a FUNCTION EXPRESSION body is never walked`() {
        val ds = diagnose(
            """
            function f<T>(): void {
              const g = function () { let x!: T; x++; };
            }
            """
        )
        assert(ts2356(ds) == 0)
    }

    @Test
    fun `negative control - a CLASS EXPRESSION is never walked`() {
        val ds = diagnose(
            """
            const K = class<T> {
              a!: T;
              foo() { this.a++; }
            };
            """
        )
        assert(ts2356(ds) == 0)
    }

    // ── Statement-walk reach ──────────────────────────────────────────────

    @Test
    fun `nested statement bodies are walked`() {
        val ds = diagnose(
            """
            function f<T>(cond: boolean): void {
              let x!: T;
              if (cond) { x++; } else { x--; }
              { x++; }
              while (cond) { x++; }
              do { x++; } while (cond);
              lab: x++;
            }
            """
        )
        assert(ts2356(ds) == 6)
    }

    @Test
    fun `loop HEADS are walked - for init - condition and incrementor`() {
        val ds = diagnose(
            """
            function f<T>(): void {
              let x!: T;
              for (let i = (x++); (x--); x++) { }
            }
            """
        )
        assert(ts2356(ds) == 3)
    }

    @Test
    fun `for-in and for-of HEAD expressions are walked`() {
        val ds = diagnose(
            """
            function f<T>(): void {
              let x!: T;
              for (const k in (x++) as any) { }
              for (const v of (x--) as any) { }
            }
            """
        )
        assert(ts2356(ds) == 2)
    }

    @Test
    fun `the if CONDITION is walked`() {
        val ds = diagnose(
            """
            function f<T>(): void {
              let x!: T;
              if (x++) { }
            }
            """
        )
        assert(ts2356(ds) == 1)
    }

    @Test
    fun `switch subject and clause bodies are walked`() {
        val ds = diagnose(
            """
            function f<T>(): void {
              let x!: T;
              switch (x++) {
                case 1: x++; break;
                default: x--;
              }
            }
            """
        )
        assert(ts2356(ds) == 3)
    }

    @Test
    fun `try - catch and finally blocks are walked`() {
        val ds = diagnose(
            """
            function f<T>(): void {
              let x!: T;
              try { x++; } catch (e) { x++; } finally { x++; }
            }
            """
        )
        assert(ts2356(ds) == 3)
    }

    @Test
    fun `return - throw and variable initializers are walked`() {
        val ds = diagnose(
            """
            function f<T>(): any {
              let x!: T;
              const v = (x++);
              if (v) { throw (x++); }
              return (x++);
            }
            """
        )
        assert(ts2356(ds) == 3)
    }

    @Test
    fun `a namespace body is walked`() {
        val ds = diagnose(
            """
            namespace N {
              function f<T>(): void {
                let x!: T;
                x++;
              }
            }
            """
        )
        assert(ts2356(ds) == 1)
    }

    // ── Expression-walk reach ─────────────────────────────────────────────

    @Test
    fun `call callees and arguments - element access - ternaries and array literals are walked`() {
        val ds = diagnose(
            """
            declare function g(a: any, b: any): any;
            declare const arr: any;
            function f<T>(cond: boolean): void {
              let x!: T;
              g(x++, x--);
              arr[x++];
              cond ? x++ : x--;
              [x++, x--];
              new g(x++, x--);
            }
            """
        )
        assert(ts2356(ds) == 9)
    }

    @Test
    fun `casts and non-null assertions are transparent`() {
        val ds = diagnose(
            """
            function f<T>(): void {
              let x!: T;
              (x++) as any;
              (x++)!;
              <any>(x++);
              (x++) satisfies any;
            }
            """
        )
        assert(ts2356(ds) == 4)
    }

    @Test
    fun `binary operands on both sides are walked`() {
        val ds = diagnose(
            """
            function f<T>(): void {
              let x!: T;
              (x++) + (x--) + (x++);
            }
            """
        )
        assert(ts2356(ds) == 3)
    }

    @Test
    fun `a property-access RECEIVER is walked`() {
        val ds = diagnose(
            """
            function f<T>(): void {
              let x!: T;
              ((x++) as any).foo;
            }
            """
        )
        assert(ts2356(ds) == 1)
    }

    @Test
    fun `negative control - an OBJECT LITERAL value is not walked`() {
        val ds = diagnose(
            """
            function f<T>(): void {
              let x!: T;
              const o = { v: x++ };
            }
            """
        )
        assert(ts2356(ds) == 0)
    }

    @Test
    fun `negative control - template spans - typeof - void - delete and await operands are not walked`() {
        val ds = diagnose(
            """
            async function f<T>(): Promise<void> {
              let x!: T;
              const s = `${'$'}{x++}`;
              typeof (x++);
              void (x++);
              await (x++);
            }
            """
        )
        assert(ts2356(ds) == 0)
    }

    @Test
    fun `a spread element is walked`() {
        val ds = diagnose(
            """
            declare function g(...a: any[]): any;
            function f<T>(): void {
              let x!: T;
              g(...((x++) as any));
            }
            """
        )
        assert(ts2356(ds) == 1)
    }

    // ── tpLocals: the body-wide prepass and its narrower descent ──────────

    @Test
    fun `a local declared in a nested BLOCK is visible to the whole body`() {
        // the prepass is body-WIDE and block-blind: a `let x!: T` inside an
        // if-block registers for the entire function body.
        val ds = diagnose(
            """
            function f<T>(cond: boolean): void {
              if (cond) { let x!: T; }
              x++;
            }
            """
        )
        assert(ts2356(ds) == 1)
    }

    @Test
    fun `a for-INIT local declaration registers in the body prepass`() {
        val ds = diagnose(
            """
            function f<T>(): void {
              for (let x!: T; ; ) { x++; }
            }
            """
        )
        assert(ts2356(ds) == 1)
    }

    @Test
    fun `negative control - the prepass does not descend a nested function body`() {
        // the inner declaration belongs to the inner scope only, so the OUTER
        // body's `x++` is not flagged (the inner one is).
        val ds = diagnose(
            """
            function f<T>(): void {
              function inner(): void { let x!: T; x++; }
              x++;
            }
            """
        )
        assert(ts2356(ds) == 1)
    }

    @Test
    fun `negative control - a local of the SAME name in a sibling function does not leak`() {
        val ds = diagnose(
            """
            function a<T>(): void { let x!: T; }
            function b(): void { x++; }
            """
        )
        assert(ts2356(ds) == 0)
    }

    // ── File gates ────────────────────────────────────────────────────────

    @Test
    fun `negative control - a d_ts file is skipped`() {
        val ds = diagnose(
            """
            declare function f<T>(): void;
            """,
            fileName = "t.d.ts",
        )
        assert(ts2356(ds) == 0)
    }

    @Test
    fun `negative control - a js file is skipped`() {
        val ds = diagnose(
            """
            function f() {
              let x;
              x++;
            }
            """,
            directives = "// @allowJs: true\n// @checkJs: true",
            fileName = "t.js",
        )
        assert(ts2356(ds) == 0)
    }
}
