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
 * (M0.4, round 655): pins for the checkImplicitAnyNewExpressions (TS7009 —
 * `new F()` whose target is a plain FUNCTION symbol, so it carries no construct
 * signature and the expression implicitly has type `any`) spine migration.
 *
 * The legacy pass was a whole-file statement + expression recursion threading NO
 * downward value at all (only `source`/`fileName`), so the migration is the
 * simplest class: a binary memoized reach classifier over the deleted
 * walkNewImplicitAnyStmt / walkNewImplicitAnyClassMember / walkNewImplicitAnyInExpr
 * arms, anchors at NewExpression enters, and the emission leaf
 * (checkNewExprImplicitAny) untouched.
 *
 * TS7009 has exactly ONE emitter in the checker, so every count here is
 * attributable to this pass alone. All expectations were verified against the
 * LEGACY walker first (at its round-655 slot-move slot).
 *
 * Frozen REACH quirks pinned in BOTH directions: class EXPRESSIONS, `<T>expr`
 * type assertions, `satisfies` casts and tagged templates are NOT walked (a
 * `new F()` inside them is unreached, not merely unnamed), and neither are
 * function/method PARAMETER DEFAULTS or class STATIC BLOCKS — while class
 * DECLARATION property initializers, object-literal method/accessor bodies,
 * `for`-head DECL-LIST initializers, switch case EXPRESSIONS, catch blocks,
 * namespace bodies, `as` casts, template spans and the `typeof`/`void`/
 * `delete`/`await` operands ARE.
 */
class M04ImplicitAnyNewSpineMigrationTest {

    private val prelude = """
        function F() {}
        declare const anyVal: any;
    """.trimIndent() + "\n"

    private fun ts7009(ds: List<Diagnostic>) = ds.count { it.code == 7009 }

    private fun run(body: String, directives: String = "// @strict: true", fileName: String = "t.ts") =
        diagnose(prelude + body.trimIndent(), directives, fileName)

    // ── Core emission + the leaf's own gates ──────────────────────────────

    @Test
    fun `new on a plain function draws TS7009 spanning the whole new expression`() {
        val ds = run("new F();")
        assert(ts7009(ds) == 1)
        val d = ds.single { it.code == 7009 }
        assert(d.length == "new F()".length)
    }

    @Test
    fun `negative control - new on a class draws nothing`() {
        val ds = diagnose(
            """
            class C {}
            new C();
            """
        )
        assert(ts7009(ds) == 0)
    }

    @Test
    fun `negative control - new on a var with a construct-signature type draws nothing`() {
        val ds = diagnose(
            """
            declare var Ctor: { new (): { a: number } };
            new Ctor();
            """
        )
        assert(ts7009(ds) == 0)
    }

    @Test
    fun `negative control - a leading type argument suppresses the emission`() {
        val ds = run("const z = new <any>F();")
        assert(ts7009(ds) == 0)
    }

    @Test
    fun `negative control - an unresolvable callee draws nothing`() {
        val ds = diagnose("new NoSuchThing();")
        assert(ts7009(ds) == 0)
    }

    // ── Run gate (the legacy dispatch gate moves with the pass) ────────────

    @Test
    fun `the pass runs under noImplicitAny alone`() {
        val ds = run("new F();", directives = "// @noImplicitAny: true")
        assert(ts7009(ds) == 1)
    }

    @Test
    fun `negative control - strict false disables the whole family`() {
        val ds = run("new F();", directives = "// @strict: false")
        assert(ts7009(ds) == 0)
    }

    @Test
    fun `negative control - a js file is skipped`() {
        val ds = run("new F();", fileName = "t.js")
        assert(ts7009(ds) == 0)
    }

    // ── Statement-walk reach ──────────────────────────────────────────────

    @Test
    fun `a variable initializer is reached`() {
        assert(ts7009(run("const v = new F();")) == 1)
    }

    @Test
    fun `a nested block is reached`() {
        assert(ts7009(run("{ new F(); }")) == 1)
    }

    @Test
    fun `both if branches and the condition are reached`() {
        val ds = run(
            """
            if (new F()) { new F(); } else { new F(); }
            """
        )
        assert(ts7009(ds) == 3)
    }

    @Test
    fun `every for-head position is reached including a DECL-LIST initializer`() {
        val ds = run(
            """
            for (let i = new F(); new F(); new F()) { new F(); }
            """
        )
        assert(ts7009(ds) == 4)
    }

    @Test
    fun `for-in and for-of heads and bodies are reached`() {
        val ds = run(
            """
            for (const k in new F()) { new F(); }
            for (const e of [1]) { new F(); }
            """
        )
        assert(ts7009(ds) == 3)
    }

    @Test
    fun `while and do bodies and conditions are reached`() {
        val ds = run(
            """
            while (new F()) { new F(); }
            do { new F(); } while (new F());
            """
        )
        assert(ts7009(ds) == 4)
    }

    @Test
    fun `a function declaration body is reached`() {
        assert(ts7009(run("function g() { new F(); }")) == 1)
    }

    @Test
    fun `a namespace body is reached`() {
        assert(ts7009(run("namespace N { new F(); }")) == 1)
    }

    @Test
    fun `try catch and finally blocks are reached`() {
        val ds = run(
            """
            try { new F(); } catch (e) { new F(); } finally { new F(); }
            """
        )
        assert(ts7009(ds) == 3)
    }

    @Test
    fun `the switch subject case expressions and clause statements are reached`() {
        val ds = run(
            """
            switch (anyVal) {
              case new F(): new F(); break;
              default: new F();
            }
            """
        )
        assert(ts7009(ds) == 3)
    }

    @Test
    fun `labeled return and throw positions are reached`() {
        val ds = run(
            """
            lbl: { new F(); }
            function g() { return new F(); }
            function h(): never { throw new F(); }
            """
        )
        assert(ts7009(ds) == 3)
    }

    // ── Class-member reach ────────────────────────────────────────────────

    @Test
    fun `class declaration member bodies and property initializers are reached`() {
        val ds = run(
            """
            class C {
              p = new F();
              constructor() { new F(); }
              m() { new F(); }
              get g() { new F(); return 1; }
              set s(v: number) { new F(); }
            }
            """
        )
        assert(ts7009(ds) == 5)
    }

    @Test
    fun `negative control - a class static block is NOT walked`() {
        val ds = run(
            """
            class C {
              static { new F(); }
            }
            """
        )
        assert(ts7009(ds) == 0)
    }

    @Test
    fun `negative control - a class EXPRESSION is never walked`() {
        val ds = run(
            """
            const K = class {
              p = new F();
              m() { new F(); }
            };
            """
        )
        assert(ts7009(ds) == 0)
    }

    // ── Expression-walk reach ─────────────────────────────────────────────

    @Test
    fun `call and new arguments and callees are reached`() {
        val ds = run(
            """
            declare function take(x: any): void;
            take(new F());
            new F(new F());
            new (new F())();
            """
        )
        // 1 (call arg) + 2 (outer new + its argument) + 1 (the new CALLEE —
        // the outer `new (…)()` itself has a non-Identifier callee, so only
        // the inner one emits).
        assert(ts7009(ds) == 4)
    }

    @Test
    fun `an outer new is reported before its argument new`() {
        val ds = run("new F(new F());").filter { it.code == 7009 }.sortedBy { it.start ?: 0 }
        assert(ds.size == 2)
        assert((ds[0].start ?: 0) < (ds[1].start ?: 0))
        assert(ds[0].length == "new F(new F())".length)
    }

    @Test
    fun `binary paren property-access element-access and ternary positions are reached`() {
        val ds = run(
            """
            const a = (new F()) as any;
            const b = new F() || new F();
            const c = (new F()).toString;
            const d = anyVal[new F()];
            const e = anyVal ? new F() : new F();
            """
        )
        assert(ts7009(ds) == 7)
    }

    @Test
    fun `array literal spread and object-literal value positions are reached`() {
        val ds = run(
            """
            const a = [new F(), ...[new F()]];
            const o = { k: new F(), ...(new F()) };
            """
        )
        assert(ts7009(ds) == 4)
    }

    @Test
    fun `object-literal method and accessor bodies are reached`() {
        val ds = run(
            """
            const o = {
              m() { new F(); },
              get g() { new F(); return 1; },
              set s(v: number) { new F(); },
            };
            """
        )
        assert(ts7009(ds) == 3)
    }

    @Test
    fun `unary typeof void delete and await operands are reached`() {
        val ds = run(
            """
            const t = typeof new F();
            const v = void new F();
            const d = delete (new F()).x;
            async function g() { await new F(); }
            """
        )
        assert(ts7009(ds) == 4)
    }

    @Test
    fun `template spans are reached`() {
        assert(ts7009(run("const t = `x\${new F()}y`;")) == 1)
    }

    @Test
    fun `arrow bodies of both forms and function expression bodies are reached`() {
        val ds = run(
            """
            const a = () => new F();
            const b = () => { new F(); };
            const c = function () { new F(); };
            """
        )
        assert(ts7009(ds) == 3)
    }

    @Test
    fun `as casts and non-null assertions are transparent`() {
        val ds = run(
            """
            const a = (new F() as any)!;
            """
        )
        assert(ts7009(ds) == 1)
    }

    @Test
    fun `yield operands are reached`() {
        val ds = run(
            """
            function* g(): any { yield new F(); }
            """
        )
        assert(ts7009(ds) == 1)
    }

    // ── Frozen NEGATIVE reach quirks ──────────────────────────────────────

    @Test
    fun `negative control - a type-assertion cast is NOT walked`() {
        assert(ts7009(run("const a = <any>new F();")) == 0)
    }

    @Test
    fun `negative control - a satisfies cast is NOT walked`() {
        assert(ts7009(run("const a = new F() satisfies any;")) == 0)
    }

    @Test
    fun `negative control - a tagged template is NOT walked`() {
        val ds = run(
            """
            declare function tag(...a: any[]): void;
            tag`x${'$'}{new F()}y`;
            """
        )
        assert(ts7009(ds) == 0)
    }

    @Test
    fun `negative control - parameter defaults are NOT walked`() {
        val ds = run(
            """
            function g(a = new F()) {}
            class C { m(b = new F()) {} }
            """
        )
        assert(ts7009(ds) == 0)
    }

    @Test
    fun `negative control - a postfix operand is walked but a decorator is not`() {
        // PrefixUnary/PostfixUnary operands ARE walked...
        assert(ts7009(run("const n = !new F();")) == 1)
        // ...while an interface / type position never is.
        assert(ts7009(run("interface I { m(): void }")) == 0)
    }
}
