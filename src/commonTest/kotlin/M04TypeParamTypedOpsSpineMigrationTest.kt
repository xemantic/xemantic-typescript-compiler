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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * (M0.4, round 658): pins for the checkTypeParamTypedOps (B60.12 — TS2339 /
 * TS2349 / TS2351 for property access, call and `new` on a value whose type is
 * an EFFECTIVELY UNCONSTRAINED type parameter) spine migration, written
 * against the LEGACY walkers at their post-spine init slot 14''g.
 *
 * The pass is a DOWNWARD-MAP migration of the round-635 order-dependent
 * flavour, and these pins fix the three things that make it one:
 *
 *  - `tpVars` (name → TypeParameter AST) is MUTATED IN STATEMENT ORDER: a
 *    `var x: T` records, and only the statements AFTER it see the recording;
 *  - the recording LEAKS through if / loop / try / namespace descents (the
 *    same map object is threaded), so a record in an outer statement is
 *    visible inside a later block;
 *  - every FUNCTION-LIKE body REBUILDS the map from its own parameters, so
 *    nothing leaks INTO a nested function — and the TP SCOPE is re-pushed
 *    there too (`withInternedTpScope`), which is the pass's one ambient.
 *
 * Reach is narrower than most tail walks and is pinned in both directions:
 * class DECLARATION method/ctor/accessor bodies are walked, but arrow and
 * function-EXPRESSION bodies, class property initializers, `for` heads and
 * `switch` statements have NO arm at all.
 */
class M04TypeParamTypedOpsSpineMigrationTest {

    private fun ts2339(ds: List<Diagnostic>) = ds.count { it.code == 2339 }
    private fun ts2349(ds: List<Diagnostic>) = ds.count { it.code == 2349 }
    private fun ts2351(ds: List<Diagnostic>) = ds.count { it.code == 2351 }

    private fun run(body: String) = diagnose(body.trimIndent(), "// @strict: true")
    private fun runNonStrict(body: String) = diagnose(body.trimIndent(), "// @strict: false")

    // ── The three emissions ───────────────────────────────────────────────

    @Test
    fun `property access on an unconstrained TypeParam param draws TS2339`() {
        val ds = run("function f<T>(t: T) { t.foo; }")
        assertEquals(1, ts2339(ds))
        val d = ds.single { it.code == 2339 }
        assertEquals("Property 'foo' does not exist on type 'T'.", d.message)
        assertEquals("foo".length, d.length)
    }

    @Test
    fun `calling an unconstrained TypeParam param draws TS2349 with the call-signature chain`() {
        val ds = run("function f<T>(t: T) { t(); }")
        assertEquals(1, ts2349(ds))
        val d = ds.single { it.code == 2349 }
        assertEquals("This expression is not callable.", d.message)
        assertEquals(listOf("  Type '{}' has no call signatures."), d.messageChain)
    }

    @Test
    fun `new-ing an unconstrained TypeParam param draws TS2351 with the construct-signature chain`() {
        val ds = run("function f<T>(t: T) { new t(); }")
        assertEquals(1, ts2351(ds))
        assertEquals(
            listOf("  Type '{}' has no construct signatures."),
            ds.single { it.code == 2351 }.messageChain,
        )
    }

    @Test
    fun `negative control - element access on a TypeParam draws nothing`() {
        assertEquals(0, ts2339(run("function f<T>(t: T) { t[1]; }")))
    }

    @Test
    fun `an Object-prototype member fires under strictNullChecks`() {
        assertEquals(1, ts2339(run("function f<T>(t: T) { t.toString; }")))
    }

    @Test
    fun `negative control - an Object-prototype member is skipped when NOT strict`() {
        assertEquals(0, ts2339(runNonStrict("function f<T>(t: T) { t.toString; }")))
    }

    @Test
    fun `a non-prototype member still fires when NOT strict`() {
        assertEquals(1, ts2339(runNonStrict("function f<T>(t: T) { t.foo; }")))
    }

    // ── Which type parameters count as effectively unconstrained ───────────

    @Test
    fun `negative control - a CONSTRAINED type parameter is not tracked`() {
        assertEquals(0, ts2339(run("function f<T extends { foo: number }>(t: T) { t.foo; }")))
    }

    @Test
    fun `negative control - a constrained TP is not tracked even for a missing member`() {
        assertEquals(0, ts2339(run("function f<T extends { foo: number }>(t: T) { t.bar; }")))
    }

    @Test
    fun `an explicit extends any type parameter IS tracked`() {
        assertEquals(1, ts2339(run("function f<T extends any>(t: T) { t.foo; }")))
    }

    @Test
    fun `a self-recursive type alias constraint IS tracked`() {
        val ds = run(
            """
            type R = { next: R };
            function f<T extends R>(t: T) { t.foo; }
            """
        )
        assertEquals(1, ts2339(ds))
    }

    // ── tpVars sources (annotated local, alias copy, generic-call inference) ─

    @Test
    fun `an annotated local var of TypeParam type is tracked`() {
        assertEquals(1, ts2339(run("function f<T>() { var x: T; x.foo; }")))
    }

    @Test
    fun `a var initialized FROM a tracked var inherits the tracking`() {
        assertEquals(1, ts2339(run("function f<T>(t: T) { var y = t; y.foo; }")))
    }

    @Test
    fun `a var initialized from a single-TP generic call with a bare-T return is tracked`() {
        val ds = run(
            """
            declare function id<T>(x: T): T;
            function f<T>(t: T) { var y = id(t); y.foo; }
            """
        )
        assertEquals(1, ts2339(ds))
    }

    @Test
    fun `negative control - a var initialized from a NON-generic call is not tracked`() {
        val ds = run(
            """
            declare function mk(x: any): any;
            function f<T>(t: T) { var y = mk(t); y.foo; }
            """
        )
        assertEquals(0, ts2339(ds))
    }

    // ── Statement ORDER (the map is mutated as statements proceed) ──────────

    @Test
    fun `a recording is visible to LATER statements only`() {
        // `x.foo` before the declaration is unreached by the recording; after it fires.
        val ds = run("function f<T>() { var x: T; x.foo; }")
        assertEquals(1, ts2339(ds))
    }

    @Test
    fun `two later uses of one recording both fire`() {
        assertEquals(2, ts2339(run("function f<T>() { var x: T; x.foo; x.bar; }")))
    }

    // ── The recording LEAKS through statement descents ─────────────────────

    @Test
    fun `the recording leaks into a later if-block and its condition`() {
        val ds = run(
            """
            declare const c: boolean;
            function f<T>(t: T) { if (c) { t.foo; } else { t.bar; } }
            """
        )
        assertEquals(2, ts2339(ds))
    }

    @Test
    fun `the recording leaks into loop conditions incrementors and bodies`() {
        val ds = run(
            """
            function f<T>(t: T) {
              while (t.a) { t.b; }
              do { t.c; } while (t.d);
              for (; t.e; t.f) { t.g; }
            }
            """
        )
        assertEquals(7, ts2339(ds))
    }

    @Test
    fun `the recording leaks into try catch and finally blocks`() {
        val ds = run(
            "function f<T>(t: T) { try { t.a; } catch (e) { t.b; } finally { t.c; } }"
        )
        assertEquals(3, ts2339(ds))
    }

    @Test
    fun `the recording leaks into a namespace body and return and throw positions`() {
        val ds = run(
            """
            function f<T>(t: T) {
              namespace N { }
              throw t.a;
            }
            function g<T>(t: T) { return t.b; }
            """
        )
        assertEquals(2, ts2339(ds))
    }

    // ── Function-like bodies REBUILD the map (nothing leaks inward) ─────────

    @Test
    fun `negative control - a nested FunctionDeclaration does not inherit the outer recording`() {
        val ds = run("function f<T>(t: T) { function g() { t.foo; } }")
        assertEquals(0, ts2339(ds))
    }

    @Test
    fun `a nested FunctionDeclaration tracks its OWN type parameter`() {
        assertEquals(1, ts2339(run("function f() { function g<U>(u: U) { u.foo; } }")))
    }

    // ── Reach: class members walked, expression-fns and heads not ───────────

    @Test
    fun `class DECLARATION method ctor and accessor bodies are walked`() {
        val ds = run(
            """
            class C<T> {
              constructor(a: T) { a.p; }
              m(b: T) { b.p; }
              get g(): number { var x: T; x.p; return 1; }
              set s(v: T) { v.p; }
            }
            """
        )
        assertEquals(4, ts2339(ds))
    }

    @Test
    fun `negative control - an ARROW body has no arm`() {
        assertEquals(0, ts2339(run("function f<T>(t: T) { const a = () => t.foo; }")))
    }

    @Test
    fun `negative control - a function EXPRESSION body has no arm`() {
        assertEquals(0, ts2339(run("function f<T>(t: T) { const a = function () { return t.foo; }; }")))
    }

    @Test
    fun `negative control - a class property INITIALIZER has no arm`() {
        assertEquals(0, ts2339(run("class C<T> { declare v: T;\n  p = this.v; }")))
    }

    @Test
    fun `negative control - a switch statement has no arm`() {
        val ds = run("function f<T>(t: T) { switch (t.a) { case 1: t.b; break; } }")
        assertEquals(0, ts2339(ds))
    }

    @Test
    fun `negative control - a for-head INITIALIZER has no arm`() {
        assertEquals(0, ts2339(run("function f<T>(t: T) { for (var q = t.a; ; ) { } }")))
    }

    // ── Emission positions inside an expression ────────────────────────────

    @Test
    fun `the emission descends binary chains parens unary and ternaries`() {
        val ds = run(
            """
            function f<T>(t: T) {
              t.a + t.b + t.c;
              (t.d);
              !t.e;
              t.f ? t.g : t.h;
            }
            """
        )
        assertEquals(8, ts2339(ds))
    }

    @Test
    fun `the emission descends call and new ARGUMENTS and a non-TP callee`() {
        val ds = run(
            """
            declare function take(x: any): void;
            function f<T>(t: T) { take(t.a); new Object(t.b); }
            """
        )
        assertEquals(2, ts2339(ds))
    }

    @Test
    fun `a chained access reports only the OUTERMOST TypeParam receiver`() {
        // `t.a.b` — the receiver of `.b` is `t.a`, not a tracked name, so the
        // walker recurses and reports `.a` on T only.
        val ds = run("function f<T>(t: T) { t.a.b; }")
        assertEquals(1, ts2339(ds))
        assertTrue(ds.single { it.code == 2339 }.message.contains("Property 'a'"))
    }

    @Test
    fun `a variable-declaration INITIALIZER is an emission position`() {
        assertEquals(1, ts2339(run("function f<T>(t: T) { var z = t.foo; }")))
    }
}
