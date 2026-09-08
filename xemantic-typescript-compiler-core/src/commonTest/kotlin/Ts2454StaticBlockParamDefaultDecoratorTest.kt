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
 * (CHK.115)(a)/(b)/(c): a `static { }` block's assignments ESCAPE, a parameter's DEFAULT is
 * a read position, and a DECORATOR expression is one too.
 *
 * Every expectation here is read from pristine `typescript@6.0.3`, which agreed with tsgo
 * 7.0.2 on all 43 measured rows. **Every fixture is FUNCTION-SCOPED**: `SpineDaFrame`'s
 * `enableLeak` is false at file level by design (a file-level `let` may be assigned
 * externally), so the file-level spelling of any shape here is silent on a working binary
 * and a broken one alike.
 *
 * ## (a) a static block runs at CLASS-EVALUATION time, in the ENCLOSING flow
 *
 * tsc's binder says so in one line — `isImmediatelyInvoked = <an IIFE> || node.kind ===
 * ClassStaticBlockDeclaration` (binder.ts:1010) — so a `static { }` gets no fresh `Start`
 * flow and `currentFlow` is NOT restored on the way out. Its statements are therefore
 * ordinary statements of the enclosing flow, which is why `markClassStaticBlockAssignments`
 * runs the FULL `markAssignments` lattice over them and not a "some assignment exists"
 * scan: a conditional assignment, and one inside a `try`, must still leave the variable
 * unassigned, and a `while (true) { … break }` must not.
 *
 * **NOTHING ELSE IN A CLASS BODY ESCAPES, and the boundary is measured rather than
 * assumed.** A method body, an INSTANCE property initializer and — the sharp one — a
 * STATIC property initializer all keep reporting in both references, even though a static
 * initializer also runs at class-evaluation time: tsc gives a `PropertyDeclaration` WITH an
 * initializer its OWN control-flow container (binder.ts:3872), precisely so its assignments
 * do not escape, while a method/accessor/constructor is a plain non-invoked one.
 *
 * ## (b) a parameter DEFAULT answers to the LEAK, not to the live set
 *
 * A default is evaluated in the fn-like's own flow, so tsc reaches it with `isOuterVariable`
 * true and reports only when the outer `let` is assigned NOWHERE (`isNeverInitialized`,
 * checker.ts:31196). Measured, that is the whole population: `f(p = e)` with `e` assigned
 * BEFORE **or AFTER** the declaration is silent in both references, and with `e` assigned
 * nowhere both report. **The item named only a PARAMETER PROPERTY; all five spellings are
 * silent** — a plain function, an arrow, a method, a constructor and an object-literal
 * method — plus a binding pattern's own element defaults, which no `Expression` walk can
 * reach because a `BindingElement` is not one.
 *
 * ## (c) a MEMBER decorator answers to the LEAK and a CLASS decorator to the LIVE set
 *
 * They are opposite cases and both are pinned, because a single rule would be wrong for one
 * of them. A `ClassDeclaration` is not a control-flow container in tsc (binder.ts:3816), so
 * a class decorator's `flowContainer` is the enclosing function and the ordinary flow
 * analysis runs — `@mk(e) class A {}` REPORTS when `e` is assigned only afterwards. Every
 * class MEMBER is its own control-flow container, so a member decorator takes
 * `isOuterVariable && !isNeverInitialized` and is silent in that same shape.
 *
 * ## measured and deliberately NOT closed here
 *
 *  * a READ in a static block is still decided by the B78.2 leak, so a LATER static block's
 *    assignment silences it — `class A { static { use(e) } static { e = "x" } }` reports at
 *    the read in both references and we do not. Static blocks are flow-ORDERED, which one
 *    leak set per class cannot express; it is the round-469 closure rule reaching a
 *    construct that is not a closure.
 *  * a static block inside a nested `function` still suppresses an outer read
 *    (`let e; function inner() { class A { static { e = "x" } } } use(e)`), which both
 *    references report.
 *  * `(d)` of the item is untouched: a `switch` with no `default` stays BLOCKED on tsc's
 *    `isExhaustiveSwitchStatement`, and a `catch` block holding an unassigned CALL stays
 *    the (CHK.105) accepted price.
 */
class Ts2454StaticBlockParamDefaultDecoratorTest {

    private val prelude = """
        declare function use(s: string): void;
        declare function mk(s: string): any;
    """.trimIndent()

    private fun d(body: String): List<Diagnostic> = diagnose(prelude + "\n" + body.trimIndent())

    private fun ts2454(body: String): List<Diagnostic> = d(body).filter { it.code == 2454 }

    private fun assertOneE(rows: List<Diagnostic>) {
        assert(rows.size == 1)
        assert(rows[0].code == 2454)
        assert(rows[0].message == "Variable 'e' is used before being assigned.")
    }

    // ---- (a) the escape -----------------------------------------------------------

    @Test
    fun `a static block's assignment escapes into the enclosing flow`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { static { e = "x"; } }
              use(e);
              return A;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `a class EXPRESSION's static block assignment escapes`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              const A = class { static { e = "x"; } };
              use(e);
              return A;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `a DESTRUCTURING assignment in a static block escapes`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { static { [e] = ["x"]; } }
              use(e);
              return A;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `a base clause does not stop the escape`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { }
              class B extends A { static { e = "x"; } }
              use(e);
              return B;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `a while-true loop inside a static block assigns definitely`() {
        val rows = ts2454(
            """
            declare const c: boolean;
            function outer() {
              let e: string;
              class A { static { while (true) { if (c) { e = "x"; break; } } } }
              use(e);
              return A;
            }
            """
        )
        assert(rows.isEmpty())
    }

    // ---- (a) POSITIVE CONTROLS — the escape must not become blanket silence -------

    @Test
    fun `positive control - a CONDITIONAL assignment in a static block does not escape`() {
        val rows = ts2454(
            """
            declare const c: boolean;
            function outer() {
              let e: string;
              class A { static { if (c) { e = "x"; } } }
              use(e);
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `positive control - an assignment inside a try in a static block does not escape`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { static { try { e = "x"; } catch { } } }
              use(e);
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `positive control - an INSTANCE property initializer's assignment does not escape`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { p = (e = "x"); }
              use(e);
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `positive control - a STATIC property initializer's assignment does not escape`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { static p = (e = "x"); }
              use(e);
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `positive control - a method body's assignment does not escape`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { m() { e = "x"; } }
              use(e);
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `positive control - a read BEFORE the class still reports`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              use(e);
              class A { static { e = "x"; } }
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `positive control - a class declared in an if branch does not escape`() {
        val rows = ts2454(
            """
            declare const c: boolean;
            function outer() {
              let e: string;
              if (c) { class A { static { e = "x"; } } }
              use(e);
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `positive control - a static block's own READ still reports`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { static { use(e); } }
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `positive control - a static block's assignment still suppresses a sibling arrow property read`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { static { e = "x"; } g = () => use(e); }
              return A;
            }
            """
        )
        assert(rows.isEmpty())
    }

    // ---- (b) parameter defaults ---------------------------------------------------

    @Test
    fun `a PARAMETER PROPERTY default is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { constructor(public p = e) { } }
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a constructor parameter default is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { constructor(p: string = e) { use(p); } }
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a function declaration parameter default is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              function f(p: string = e) { use(p); }
              return f;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `an arrow parameter default is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              const f = (p: string = e) => use(p);
              return f;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a method parameter default is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { m(p: string = e) { use(p); } }
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `an object literal method parameter default is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              const o = { m(p: string = e) { use(p); } };
              return o;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a BINDING PATTERN element default is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              function f({ a = e }: { a?: string }) { use(a); }
              return f;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `an arrow NESTED in a parameter default is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              function f(g: () => void = () => use(e)) { g(); }
              return f;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a class expression's static block nested in a parameter default is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              function f(C: any = class { static { use(e); } }) { return C; }
              return f;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `positive control - a parameter of the same name shadows the outer let`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              function f(e: string, p: string = e) { use(p); }
              return f;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `positive control - an assignment AFTER the declaration silences a parameter default`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              function f(p: string = e) { use(p); }
              e = "z";
              return f;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `positive control - an assignment BEFORE the declaration silences a parameter default`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              e = "z";
              function f(p: string = e) { use(p); }
              return f;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `positive control - a parameter property default that assigns still reports at a later read`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { constructor(public p = (e = "x")) { } }
              use(e);
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    // ---- (c) decorators -----------------------------------------------------------

    @Test
    fun `a CLASS decorator expression is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              @mk(e)
              class A { }
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a METHOD decorator expression is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { @mk(e) m() { } }
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a PROPERTY decorator expression is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { @mk(e) p = 1; }
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a property decorator on a property with NO initializer is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { @mk(e) p!: number; }
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `an ACCESSOR decorator expression is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { @mk(e) get v() { return 1; } }
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a CLASS decorator REPORTS when the variable is assigned only afterwards`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              @mk(e)
              class A { }
              e = "z";
              return A;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `positive control - a MEMBER decorator is silent when the variable is assigned afterwards`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { @mk(e) m() { } }
              e = "z";
              return A;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `positive control - a class decorator is silent when the variable is assigned before`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              e = "z";
              @mk(e)
              class A { }
              return A;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `positive control - a decorator naming no uninitialized variable reports nothing`() {
        val all = d(
            """
            declare function deco(x: any, y: any): any;
            function outer() {
              let e: string;
              class A { @deco m() { } }
              return A;
            }
            """
        )
        assert(all.none { it.code == 2454 })
    }

    @Test
    fun `a PARAMETER decorator expression is TS2454 under experimentalDecorators`() {
        val rows = diagnose(
            prelude + "\n" + """
            function outer() {
              let e: string;
              class A { m(@mk(e) q: number) { return q; } }
              return A;
            }
            """.trimIndent(),
            directives = "// @strict: true\n// @experimentalDecorators: true",
        ).filter { it.code == 2454 }
        assertOneE(rows)
    }

    @Test
    fun `positive control - a parameter decorator without experimentalDecorators is TS1206 alone`() {
        val all = d(
            """
            function outer() {
              let e: string;
              class A { m(@mk(e) q: number) { return q; } }
              return A;
            }
            """
        )
        // Both references stop at the syntax error: a parameter decorator is not legal
        // here, so it is never evaluated and carries no read position.
        assert(all.any { it.code == 1206 })
        assert(all.none { it.code == 2454 })
    }

    // ---- spans --------------------------------------------------------------------

    @Test
    fun `the parameter default row is anchored at the default expression`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              function f(p: string = e) { use(p); }
              return f;
            }
            """
        )
        assertOneE(rows)
        assert(rows[0].length == 1)
        // pristine `typescript@6.0.3` and tsgo 7.0.2 both anchor this row at column 26.
        assert(rows[0].character == 26)
    }

    @Test
    fun `the member decorator row is anchored at the decorator's own argument`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { @mk(e) m() { } }
              return A;
            }
            """
        )
        assertOneE(rows)
        assert(rows[0].length == 1)
        // pristine `typescript@6.0.3` and tsgo 7.0.2 both anchor this row at column 17.
        assert(rows[0].character == 17)
    }
}
