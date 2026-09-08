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
 * (CHK.112)(a): the definite-assignment pass reaches a CLASS MEMBER.
 *
 * Every expectation here is read from pristine `typescript@6.0.3`, which agreed with
 * tsgo 7.0.2 on every row of the measured matrix.
 *
 * ## it is NOT the expression-body gap (CHK.110)(b) closed
 *
 * (CHK.110) named `class Inner { g = () => use(e) }` as part of its expression-bodied
 * arrow gap. Measured pre-change, a BLOCK-bodied arrow, a `function` expression, an
 * IIFE and a BARE IDENTIFIER in a class property initializer were ALL silent, so the
 * class-member path was absent in every member flavour rather than the expression body
 * being what it lacked. Two independent mechanisms carried that silence:
 *
 *  * `spineDaEdge`'s class-DECLARATION arm listed only the three BODY-bearing member
 *    kinds, so everything under a `PropertyDeclaration` was `DA_NONE` and the arrow's
 *    own leak set came back EMPTY — which is why the (CHK.110)(b) handler, correct in
 *    itself, did nothing there. The class-EXPRESSION arm already listed it.
 *  * nothing ever walked a property initializer that is a plain EXPRESSION. It has no
 *    statement list, so no frame is opened for it, and no fn-like, so
 *    `spineDaExpressionBody` never sees it — `checkUsesOfUninitialized`'s
 *    `ClassDeclaration` arm walked the heritage clauses and nothing else, and
 *    `findUninitializedRefs` has no `ClassExpression` arm at all. That is
 *    `spineDaClassMembers`.
 *
 * ## the same edit is a FALSE POSITIVE fix in the other direction
 *
 * A class body's assignments were never collected for the B78.2 leak either — a class
 * EXPRESSION's method bodies were, a class DECLARATION's nothing — so an assignment in
 * `class A { m() { e = "x" } }` left `e` in the leak and a read in a SIBLING closure
 * drew an ours-only TS2454 both references are silent about. Six measured shapes closed
 * with it (a method, an accessor, a property initializer, an arrow property, a static
 * block, and a class expression's property initializer). As in (CHK.110)(a), a shape
 * that failed in BOTH directions is what attributed the mechanism.
 *
 * ## what the leak set buys, and why the frame's live set would be wrong
 *
 * `spineDaClassMembers` runs against a COPY of the B78.2 leak, never the frame's live
 * set, and both halves of that are pinned below. A class body's assignments must NOT
 * escape the class statement — a member runs at instantiation / class evaluation, so
 * `class C { a = (e = "x") } use(e)` still reports at `use(e)` in both references, which
 * is exactly the escape (CHK.110)(a) had to remove from the `try` walk. And an
 * assignment ANYWHERE in the class body suppresses a read in ANY member IN EITHER ORDER
 * (`class C { b = e; a = (e = "x") }` is silent in both references), which is what the
 * leak already encodes and a live set does not.
 *
 * ## measured and deliberately NOT closed here
 *
 *  * a FILE-LEVEL `let` reaches no leak at all — `SpineDaFrame.enableLeak` is false at
 *    the file level by design, because a file-level `let` may be assigned externally —
 *    so the item's own headline fixture is silent for a SECOND reason that has nothing
 *    to do with class members, and every fixture here is function-scoped for that
 *    reason. A file-level read inside a plain nested `function` is silent too, on the
 *    same binary and for the same conservatism.
 *  * a static block's ASSIGNMENTS escaping into the enclosing flow. A `static { … }`
 *    runs at class-evaluation time, so both references are silent at a statement read
 *    after it, and we report — a pre-existing ours-only row this round leaves exactly
 *    as it found it. That is an ESCAPE into `markAssignments`, a different mechanism
 *    from this one; its READS are closed here.
 *  * a PARAMETER PROPERTY's default (`constructor(public p = e)`) — the legacy
 *    default-argument drop — and a DECORATOR expression, an unlisted `spineDaEdge`
 *    edge. Both report in both references.
 */
class Ts2454ClassMemberInitializerTest {

    private val prelude = """
        declare function use(s: string): void;
        declare function take(n: number): void;
    """.trimIndent()

    private fun d(body: String): List<Diagnostic> = diagnose(prelude + "\n" + body.trimIndent())

    private fun ts2454(body: String): List<Diagnostic> = d(body).filter { it.code == 2454 }

    private fun assertOneE(rows: List<Diagnostic>) {
        assert(rows.size == 1)
        assert(rows[0].code == 2454)
        assert(rows[0].message == "Variable 'e' is used before being assigned.")
    }

    // ---- reads in a class DECLARATION's members ---------------------------------

    @Test
    fun `a bare identifier in a class property initializer is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { g = e; }
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a class property initializer read reports nothing else`() {
        val all = d(
            """
            function outer() {
              let e: string;
              class Inner { g = e; }
              return Inner;
            }
            """
        )
        assert(all.size == 1)
        assert(all[0].code == 2454)
    }

    @Test
    fun `a STATIC class property initializer is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { static g = e; }
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `an expression-bodied arrow in a class property initializer is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { g = () => use(e); }
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a block-bodied arrow in a class property initializer is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { g = () => { use(e); }; }
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a function expression in a class property initializer is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { g = function () { use(e); }; }
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `an IIFE in a class property initializer is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { g = (() => use(e))(); }
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `an object literal inside a class property initializer is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { g = { p: e }; }
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a computed member name is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { [e] = 1; }
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a static block body is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { static { use(e); } }
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }

    // ---- reads in a class EXPRESSION's members -----------------------------------

    @Test
    fun `a bare identifier in a class expression property initializer is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              const C = class { g = e; };
              return C;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a class expression static block body is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              const C = class { static { use(e); } };
              return C;
            }
            """
        )
        assertOneE(rows)
    }

    // ---- the ESCAPE controls - a class body's assignments stay inside it ---------

    @Test
    fun `an instance property initializer assignment does NOT escape the class`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { a = (e = "x"); }
              use(e);
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a STATIC property initializer assignment does NOT escape the class`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { static a = (e = "x"); }
              use(e);
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a method assignment does NOT escape the class`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { m() { e = "x"; } }
              use(e);
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }

    // ---- the leak-suppression controls - an assignment anywhere in the body ------

    @Test
    fun `an earlier member's assignment silences a later member's read`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { a = (e = "x"); b = e; }
              return Inner;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `a LATER member's assignment silences an earlier member's read`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { b = e; a = (e = "x"); }
              return Inner;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `a sibling class's member assignment silences the read`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { a = (e = "x"); }
              class B { b = e; }
              return [A, B];
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `a method assignment in the same class silences a property read`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { b = e; m() { e = "x"; } }
              return A;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `a class method assignment silences a sibling closure's read`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { m() { e = "x"; } }
              function f() { use(e); }
              return [A, f];
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `a class property initializer assignment silences a sibling closure's read`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { a = (e = "x"); }
              function f() { use(e); }
              return [A, f];
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `an arrow property's assignment silences a sibling closure's read`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { a = () => { e = "x"; }; }
              function f() { use(e); }
              return [A, f];
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `a class accessor assignment silences a sibling closure's read`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { get g() { e = "x"; return 1; } }
              function f() { use(e); }
              return [A, f];
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `a static block assignment silences a sibling closure's read`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { static { e = "x"; } }
              function f() { use(e); }
              return [A, f];
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `a class expression property initializer assignment silences a sibling closure's read`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              const A = class { a = (e = "x"); };
              function f() { use(e); }
              return [A, f];
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - with NO assignment in the class a sibling closure's read still reports`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class A { m() { return 1; } }
              function f() { use(e); }
              return [A, f];
            }
            """
        )
        assertOneE(rows)
    }

    // ---- the assumeInitialized suppressions must survive -------------------------

    @Test
    fun `negative control - an any-typed declaration is silent in a class property`() {
        val rows = ts2454(
            """
            function outer() {
              let e: any;
              class Inner { g = e; }
              return Inner;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - an unknown-typed declaration is silent in a class property`() {
        val rows = ts2454(
            """
            function outer() {
              let e: unknown;
              class Inner { g = e; }
              return Inner;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - a void-typed declaration is silent in a class property`() {
        val rows = ts2454(
            """
            function outer() {
              let e: void;
              class Inner { g = e; }
              return Inner;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - a definite-assignment asserted let is silent in a class property`() {
        val rows = ts2454(
            """
            function outer() {
              let e!: string;
              class Inner { g = e; }
              return Inner;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - a let assigned before the class is silent`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              e = "a";
              class Inner { g = e; }
              return Inner;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - a property initializer that itself assigns is silent`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { g = (e = "x"); }
              return Inner;
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - a bang-asserted property with no initializer is silent`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { g!: string; }
              return Inner;
            }
            """
        )
        assert(rows.isEmpty())
    }

    // ---- regression controls for the member paths that already worked ------------

    @Test
    fun `a class method body read is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { m() { use(e); } }
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a constructor body read is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { constructor() { use(e); } }
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a getter body read is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              class Inner { get g() { use(e); return 1; } }
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a class expression method body read is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: string;
              const C = class { m() { use(e); } };
              return C;
            }
            """
        )
        assertOneE(rows)
    }

    @Test
    fun `a heritage clause read is TS2454`() {
        val rows = ts2454(
            """
            function outer() {
              let e: { new (): object };
              class Inner extends e {}
              return Inner;
            }
            """
        )
        assertOneE(rows)
    }
}
