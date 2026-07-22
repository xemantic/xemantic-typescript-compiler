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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * (M0.4, round 644): pins for the checkExpandoFunctionNestedReads (B431 —
 * TS2339 for a nested-function read of an expando-function property never
 * declared by a file-scope `Foo.prop =` write) spine migration. Three legacy
 * walks: (a) the TOP-LEVEL candidate scan (uniquely-named top-level fns not
 * merged with any other decl kind), (b) collectExpandoDecls — the file-scope
 * `Foo.prop =` write collector that walks statement + expression positions
 * but NEVER descends into function-likes, and (c) the read walk carrying the
 * inNestedFn flag (true inside fn-like bodies AND param defaults) and the
 * shadow chain (param names + top-level body var/fn/class locals; a
 * fn-expression's own name). Frozen quirks pinned both directions: class
 * bodies, namespace bodies, objlit METHODS, template spans,
 * typeof/delete/void operands, and for-in/of loop INITIALIZERS are never
 * walked; a write via element access / compound assignment / a non-Identifier
 * receiver is never collected; a nested-block body local does NOT shadow.
 * All expectations verified against the pre-migration walker.
 */
class M04ExpandoSpineMigrationTest {

    private fun expando(d: List<Diagnostic>, prop: String, fn: String) =
        d.any { it.code == 2339 && it.message == "Property '$prop' does not exist on type 'typeof $fn'." }

    // ── fires: nested-fn reads of undeclared expando props ────────────────

    @Test
    fun `TS2339 - basic nested function read`() {
        val d = diagnose(
            """
            function Foo() {}
            function g() { Foo.bar; }
            """
        )
        d should { have(expando(d, "bar", "Foo")) }
    }

    @Test
    fun `TS2339 - exact position and length at the property name`() {
        val src = "function Foo() {}\nfunction g() { Foo.bar; }"
        val d = diagnose(src, directives = "")
        val hit = d.first { it.code == 2339 }
        assertEquals("Property 'bar' does not exist on type 'typeof Foo'.", hit.message)
        assertEquals(src.indexOf("bar;"), hit.start)
        assertEquals(3, hit.length)
    }

    @Test
    fun `TS2339 - function expression body`() {
        val d = diagnose(
            """
            function Foo() {}
            const h = function() { Foo.bar; };
            """
        )
        d should { have(expando(d, "bar", "Foo")) }
    }

    @Test
    fun `TS2339 - arrow expression body`() {
        val d = diagnose(
            """
            function Foo() {}
            const h = () => Foo.bar;
            """
        )
        d should { have(expando(d, "bar", "Foo")) }
    }

    @Test
    fun `TS2339 - nested function parameter default`() {
        val d = diagnose(
            """
            function Foo() {}
            function g(p = Foo.bar) {}
            """
        )
        d should { have(expando(d, "bar", "Foo")) }
    }

    @Test
    fun `TS2339 - three levels of function nesting`() {
        val d = diagnose(
            """
            function Foo() {}
            function a() { function b() { function c() { Foo.deep; } } }
            """
        )
        d should { have(expando(d, "deep", "Foo")) }
    }

    @Test
    fun `TS2339 - objlit property VALUE function expression fires while an objlit METHOD is silent`() {
        val d = diagnose(
            """
            function Foo() {}
            const a = { m() { Foo.viaMethod; } };
            const b = { m: function() { Foo.viaValue; } };
            """
        )
        d should {
            have(expando(d, "viaValue", "Foo"))
            have(none { it.code == 2339 && it.message.contains("viaMethod") })
        }
    }

    @Test
    fun `TS2339 - switch subject and case expression in a nested function`() {
        val d = diagnose(
            """
            function Foo() {}
            function g() { switch (Foo.subj) { case Foo.cas: break; } }
            """
        )
        d should {
            have(expando(d, "subj", "Foo"))
            have(expando(d, "cas", "Foo"))
        }
    }

    @Test
    fun `TS2339 - await and yield operands in nested functions`() {
        val d = diagnose(
            """
            function Foo() {}
            async function g() { await Foo.aw; }
            function* h() { yield Foo.yi; }
            """
        )
        d should {
            have(expando(d, "aw", "Foo"))
            have(expando(d, "yi", "Foo"))
        }
    }

    @Test
    fun `TS2339 - reads in if for while do try catch finally return throw positions`() {
        val d = diagnose(
            """
            function Foo() {}
            function g(): any {
                if (Foo.c1) {}
                for (Foo.c2; Foo.c3; Foo.c4) { break; }
                while (Foo.c5) { break; }
                do { break; } while (Foo.c6);
                try { Foo.c7; } catch (e) { Foo.c8; } finally { Foo.c9; }
                lbl: Foo.c10;
                if (Foo.c1) { return Foo.c11; }
                throw Foo.c12;
            }
            """
        )
        d should {
            for (i in 1..12) have(expando(d, "c$i", "Foo"))
        }
    }

    @Test
    fun `TS2339 - call and new argument positions and conditional branches`() {
        val d = diagnose(
            """
            function Foo() {}
            declare function use(...a: any[]): any;
            declare const K: any;
            function g() {
                use(Foo.arg, true ? Foo.t : Foo.f);
                new K(Foo.narg);
                use([Foo.el, { p: Foo.pv, ...(Foo.sp as any) }]);
                use((Foo.pa), Foo.nn!, Foo.cast as any, Foo.idx["k"], Foo.ea[0]);
            }
            """
        )
        d should {
            for (p in listOf("arg", "t", "f", "narg", "el", "pv", "sp", "pa", "nn", "cast", "idx", "ea"))
                have(expando(d, p, "Foo"))
        }
    }

    @Test
    fun `TS2339 - a write INSIDE a function does not declare and its own LHS read fires`() {
        val d = diagnose(
            """
            function Foo() {}
            function w() { Foo.late = 1; }
            function r() { Foo.late; }
            """
        )
        // Both the nested write's LHS and the sibling read fire — the
        // collector never descends into function bodies.
        assertEquals(2, d.count { it.code == 2339 && it.message.contains("'late'") })
    }

    @Test
    fun `TS2339 - compound assignment and element-access writes do not declare`() {
        val d = diagnose(
            """
            function Foo() {}
            Foo["ele"] = 1;
            function g() { Foo.ele; Foo.cmp += 1; Foo.inc++; }
            """
        )
        d should {
            have(expando(d, "ele", "Foo"))
            have(expando(d, "cmp", "Foo"))
            have(expando(d, "inc", "Foo"))
        }
    }

    @Test
    fun `TS2339 - a template-span write at file scope is not collected`() {
        val d = diagnose(
            """
            function Foo() {}
            `${'$'}{Foo.tpl = 1}`;
            function g() { Foo.tpl; }
            """
        )
        d should { have(expando(d, "tpl", "Foo")) }
    }

    @Test
    fun `TS2339 - a nested-BLOCK body local does not shadow`() {
        val d = diagnose(
            """
            function Foo() {}
            function g() { { let Foo: any; } Foo.bar; }
            """
        )
        d should { have(expando(d, "bar", "Foo")) }
    }

    @Test
    fun `TS2339 - a sibling function's shadow does not leak`() {
        val d = diagnose(
            """
            function Foo() {}
            function a() { var Foo: any; }
            function b() { Foo.bar; }
            """
        )
        d should { have(expando(d, "bar", "Foo")) }
    }

    @Test
    fun `TS2339 - optional-chain read fires`() {
        val d = diagnose(
            """
            function Foo() {}
            function g() { Foo?.opt; }
            """
        )
        d should { have(expando(d, "opt", "Foo")) }
    }

    @Test
    fun `TS2339 - an exported top-level function is still a candidate`() {
        val d = diagnose(
            """
            export function Foo() {}
            function g() { Foo.bar; }
            """
        )
        d should { have(expando(d, "bar", "Foo")) }
    }

    // ── silent: declared props (file-scope writes in walked positions) ─────

    @Test
    fun `negative control - file-scope writes declare across every walked statement position`() {
        diagnose(
            """
            function Foo() {}
            Foo.a = 1;
            const t = (Foo.b = 2);
            if (Foo.c = 3 as any) { Foo.d = 4; } else { Foo.e = 5; }
            for (Foo.f = 6; Foo.g = 7 as any; Foo.h = 8) { break; }
            while (Foo.i = 9 as any) { break; }
            do { break; } while (Foo.j = 10 as any);
            switch (Foo.k = 11 as any) { case (Foo.l = 12 as any): break; default: Foo.m = 13; }
            try { Foo.n = 14; } catch (ex) { Foo.o = 15; } finally { Foo.p = 16; }
            lbl: Foo.q = 17;
            function g() {
                Foo.a; Foo.b; Foo.c; Foo.d; Foo.e; Foo.f; Foo.g; Foo.h;
                Foo.i; Foo.j; Foo.k; Foo.l; Foo.m; Foo.n; Foo.o; Foo.p; Foo.q;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - writes nested in expressions declare - chains comma ternary literals spread`() {
        diagnose(
            """
            function Foo() {}
            declare let y: any;
            y = Foo.a = Foo.b = 1;
            const c = ((Foo.d = 2), y ? Foo.e = 3 : Foo.f = 4);
            const l = [Foo.g = 5, { p: Foo.h = 6, ...(Foo.i = 7 as any) }];
            function g() { Foo.a; Foo.b; Foo.d; Foo.e; Foo.f; Foo.g; Foo.h; Foo.i; }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a file-scope write BELOW the reading function still declares`() {
        diagnose(
            """
            function Foo() {}
            function g() { Foo.later; }
            Foo.later = 1;
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    // ── silent: unreached read positions (frozen walker gaps) ──────────────

    @Test
    fun `negative control - class bodies and namespace bodies are never walked`() {
        diagnose(
            """
            function Foo() {}
            class K { m() { Foo.viaClass; } }
            namespace N { export function g() { Foo.viaNs; } }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - template spans and typeof operands inside nested functions are not walked`() {
        diagnose(
            """
            function Foo() {}
            function g() { `${'$'}{Foo.tpl}`; typeof Foo.tof; }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a for-of loop-head destructuring INITIALIZER is not walked`() {
        diagnose(
            """
            function Foo() {}
            function g() { for ([Foo.q] of [[1]]) { break; } }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - top-level reads never fire`() {
        diagnose(
            """
            function Foo() {}
            Foo.top;
            const t = Foo.topInit;
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    // ── silent: candidate gating ───────────────────────────────────────────

    @Test
    fun `negative control - a function merged with another declaration kind is not a candidate`() {
        diagnose(
            """
            function A() {}
            interface A {}
            function B() {}
            namespace B {}
            function C() {}
            var C: any;
            function g() { A.x; B.x; C.x; }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - duplicate top-level function names are not candidates`() {
        val d = diagnose(
            """
            function D() {}
            function D() {}
            function g() { D.x; }
            """
        )
        d should { have(none { it.code == 2339 }) }
    }

    @Test
    fun `negative control - a NESTED function is not a candidate`() {
        diagnose(
            """
            function outer() {
                function Inner() {}
                function g() { Inner.x; }
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    // ── silent: shadowing ──────────────────────────────────────────────────

    @Test
    fun `negative control - params and top-level body locals shadow the candidate`() {
        diagnose(
            """
            function Foo() {}
            function a(Foo: any) { Foo.viaParam; }
            function b() { var Foo: any; Foo.viaVar; }
            function c() { let Foo: any; Foo.viaLet; }
            function d() { function Foo() {} Foo.viaFn; }
            function e() { class Foo {} Foo.viaClass; }
            """
        ) should {
            have(none { it.code == 2339 && it.message.contains("typeof Foo") })
        }
    }

    @Test
    fun `negative control - an outer function's shadow reaches nested functions`() {
        diagnose(
            """
            function Foo() {}
            function outer() { var Foo: any; function inner() { Foo.bar; } }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a function expression's own name shadows`() {
        diagnose(
            """
            function Foo() {}
            const h = function Foo() { Foo.own; };
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    // ── silent: runtime properties and dts ─────────────────────────────────

    @Test
    fun `negative control - runtime function-object properties never fire`() {
        // `Foo.name` additionally draws a TS2339 from the general
        // property-access checker (display `'() => void'`) — the expando
        // pass's own emissions all display `'typeof Foo'`, so the pin keys
        // on that message shape.
        diagnose(
            """
            function Foo() {}
            function g() { Foo.toString; Foo.call; Foo.apply; Foo.prototype; Foo.length; Foo.name; }
            """
        ) should {
            have(none { it.code == 2339 && it.message.contains("typeof Foo") })
        }
    }

    @Test
    fun `negative control - dts files are skipped`() {
        diagnose(
            """
            declare function Foo(): void;
            declare function g(): void;
            """,
            fileName = "t.d.ts",
        ) should {
            have(none { it.code == 2339 })
        }
    }
}
