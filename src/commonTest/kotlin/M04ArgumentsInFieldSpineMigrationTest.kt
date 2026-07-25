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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * (M0.4, round 655 tail): pins for the checkArgumentsInClassFieldInitializers
 * (TS2815 — an `arguments` reference in a class PROPERTY INITIALIZER or a class
 * STATIC BLOCK) spine migration, written against the LEGACY walkers at their
 * round-655-tail slot-move slot.
 *
 * The pass runs TWO INTERLEAVED walks (the round-640 shape) that RE-ENTER each
 * other (the round-653 shape):
 *
 *  - the ROUTING walk (findClassesForTS2815InStatements/-InExpr) whose only job
 *    is to find non-`declare` class declarations/expressions, and
 *  - the EMISSION walk (checkExprForTS2815Arguments /
 *    checkStatementsForTS2815Arguments) entered at each property initializer and
 *    static-block body, where a bare `arguments` Identifier emits.
 *
 * The re-entries are what make the two walks inseparable: inside the EMISSION
 * walk an ordinary FunctionExpression / FunctionDeclaration / object-literal
 * method body binds its OWN `arguments`, so it hands back to the ROUTING walk
 * (a class nested there is still found), while a ClassExpression hands to the
 * member dispatch. Arrow functions stay in the EMISSION walk (they inherit
 * `arguments`), including their parameter DEFAULTS.
 *
 * The two walks reach DIFFERENT positions and that asymmetry is the migration's
 * whole risk surface, so it is pinned in both directions: the ROUTING walk
 * descends only statement BODIES (no if condition, no loop head, no switch
 * subject, no case expression), while the EMISSION walk descends every one of
 * them. TS2815 has exactly one emitter, so every count is attributable to this
 * pass alone.
 */
class M04ArgumentsInFieldSpineMigrationTest {

    private fun ts2815(ds: List<Diagnostic>) = ds.count { it.code == 2815 }

    private fun run(@Language("typescript") body: String) = diagnose(body.trimIndent(), "// @target: esnext")

    // ── Core emissions ────────────────────────────────────────────────────

    @Test
    fun `arguments in a property initializer draws TS2815 at the identifier`() {
        val ds = run("class C { p = arguments; }")
        assert(ts2815(ds) == 1)
        assert(ds.single { it.code == 2815 }.length == "arguments".length)
    }

    @Test
    fun `arguments in a static block draws TS2815`() {
        assert(ts2815(run("class C { static { arguments; } }")) == 1)
    }

    @Test
    fun `an arrow in a property initializer stays in the emission walk`() {
        assert(ts2815(run("class C { p = () => arguments; }")) == 1)
    }

    @Test
    fun `an arrow BLOCK body and its parameter DEFAULT are both emission positions`() {
        val ds = run(
            """
            class C {
              p = (a = arguments) => { return arguments; };
            }
            """
        )
        assert(ts2815(ds) == 2)
    }

    @Test
    fun `negative control - a method body binds its own arguments`() {
        assert(ts2815(run("class C { m() { return arguments; } }")) == 0)
    }

    @Test
    fun `negative control - a function EXPRESSION in an initializer binds its own arguments`() {
        assert(ts2815(run("class C { p = function () { return arguments; }; }")) == 0)
    }

    @Test
    fun `negative control - a function DECLARATION in a static block binds its own arguments`() {
        assert(ts2815(run("class C { static { function f() { return arguments; } } }")) == 0)
    }

    @Test
    fun `negative control - an object-literal method in an initializer binds its own arguments`() {
        assert(ts2815(run("class C { p = { m() { return arguments; } }; }")) == 0)
    }

    @Test
    fun `an object-literal property VALUE stays in the emission walk`() {
        assert(ts2815(run("class C { p = { k: arguments }; }")) == 1)
    }

    @Test
    fun `negative control - a declare class is skipped`() {
        assert(ts2815(run("declare class C { p = arguments; }")) == 0)
    }

    // ── The re-entry boundary (emission walk → routing walk → members) ─────

    @Test
    fun `a class nested in a method body is still found by the routing walk`() {
        assert(ts2815(run("class C { m() { class D { q = arguments; } } }")) == 1)
    }

    @Test
    fun `a class nested in a function EXPRESSION inside an initializer is found`() {
        val ds = run(
            """
            class C {
              p = function () { class D { q = arguments; } };
            }
            """
        )
        assert(ts2815(ds) == 1)
    }

    @Test
    fun `a class DECLARATION inside a static block reaches its members`() {
        assert(ts2815(run("class C { static { class D { q = arguments; } } }")) == 1)
    }

    @Test
    fun `a class EXPRESSION inside a property initializer reaches its members`() {
        assert(ts2815(run("class C { p = class { q = arguments; }; }")) == 1)
    }

    @Test
    fun `an inner class member body hands back to the routing walk`() {
        // D.m() has its own `arguments`; only D.q emits.
        val ds = run(
            """
            class C {
              static { class D { q = arguments; m() { return arguments; } } }
            }
            """
        )
        assert(ts2815(ds) == 1)
    }

    // ── ROUTING-walk reach (finding classes outside any initializer) ───────

    @Test
    fun `the routing walk finds classes in fn bodies namespaces blocks and try clauses`() {
        val ds = run(
            """
            function f() { class A { p = arguments; } }
            namespace N { class B { p = arguments; } }
            { class C2 { p = arguments; } }
            try { class D { p = arguments; } } catch (e) { class E { p = arguments; } }
            """
        )
        assert(ts2815(ds) == 5)
    }

    @Test
    fun `the routing walk descends variable initializers and call arguments`() {
        val ds = run(
            """
            declare function take(x: any): void;
            const a = class { p = arguments; };
            take(class { p = arguments; });
            """
        )
        assert(ts2815(ds) == 2)
    }

    @Test
    fun `negative control - the routing walk does NOT descend an if CONDITION`() {
        assert(ts2815(run("if ((class { p = arguments })) { }")) == 0)
    }

    @Test
    fun `negative control - the routing walk does NOT descend a for HEAD`() {
        val ds = run("for (let i = (class { p = arguments }); false; ) { }")
        assert(ts2815(ds) == 0)
    }

    @Test
    fun `negative control - the routing walk does NOT descend a while CONDITION`() {
        assert(ts2815(run("while ((class { p = arguments })) { break; }")) == 0)
    }

    @Test
    fun `negative control - the routing walk does NOT descend the switch subject or a case expression`() {
        val ds = run(
            """
            declare const anyVal: any;
            switch (class { p = arguments }) { }
            switch (anyVal) { case (class { q = arguments }): break; }
            """
        )
        assert(ts2815(ds) == 0)
    }

    @Test
    fun `the routing walk DOES descend loop and clause BODIES`() {
        val ds = run(
            """
            declare const anyVal: any;
            while (anyVal) { class A { p = arguments; } }
            switch (anyVal) { case 1: { class B { p = arguments; } } break; }
            """
        )
        assert(ts2815(ds) == 2)
    }

    // ── EMISSION-walk reach (the asymmetry: heads ARE walked here) ─────────

    @Test
    fun `the emission walk descends an if CONDITION unlike the routing walk`() {
        assert(ts2815(run("class C { static { if (arguments) { } } }")) == 1)
    }

    @Test
    fun `the emission walk descends every for-head position`() {
        val ds = run(
            """
            class C {
              static { for (let i = arguments; arguments; arguments) { arguments; } }
            }
            """
        )
        assert(ts2815(ds) == 4)
    }

    @Test
    fun `the emission walk descends the switch subject and case expressions`() {
        val ds = run(
            """
            class C {
              static {
                switch (arguments) {
                  case arguments: arguments; break;
                  default: arguments;
                }
              }
            }
            """
        )
        assert(ts2815(ds) == 4)
    }

    @Test
    fun `the emission walk descends loop heads bodies and try clauses`() {
        val ds = run(
            """
            class C {
              static {
                while (arguments) { arguments; }
                do { arguments; } while (arguments);
                for (const k in arguments) { arguments; }
                try { arguments; } catch (e) { arguments; } finally { arguments; }
              }
            }
            """
        )
        assert(ts2815(ds) == 9)
    }

    @Test
    fun `the emission walk descends casts unary operands template spans and comma chains`() {
        val ds = run(
            """
            class C {
              a = arguments as any;
              b = <any>arguments;
              c = arguments satisfies any;
              d = arguments!;
              e = typeof arguments;
              f = void arguments;
              g = `x${'$'}{arguments}y`;
              h = [arguments, ...arguments];
              i = arguments ? arguments : arguments;
            }
            """
        )
        assert(ts2815(ds) == 12)
    }

    @Test
    fun `the emission walk descends call new member and element access positions`() {
        val ds = run(
            """
            declare function take(x: any): void;
            class C {
              a = take(arguments);
              b = new Object(arguments);
              c = (arguments as any).x;
              d = (arguments as any)[arguments as any];
            }
            """
        )
        assert(ts2815(ds) == 5)
    }

    @Test
    fun `a nested arrow chain keeps the emission context`() {
        assert(ts2815(run("class C { p = () => () => () => arguments; }")) == 1)
    }

    @Test
    fun `negative control - a plain identifier named otherwise never emits`() {
        assert(ts2815(run("class C { p = someArgs; }")) == 0)
    }
}
