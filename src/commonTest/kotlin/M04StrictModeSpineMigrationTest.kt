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

/**
 * (M0.4, round 645): pins for the checkStrictModeIdentifiers (TS1100
 * restricted-name bindings + TS2630 eval inc/dec + TS1215 module-file
 * restricted names + the top-level `var eval` TS2300/TS6203 lib-collision
 * pair) spine migration. THREE per-file routing modes: module files route
 * top-level VariableStatement/FunctionDeclaration through the TS1215 arms
 * (initializers/bodies continuing into the STRICT walk) and every other
 * top-level statement through the strict walk; strict files (strict/
 * alwaysStrict option or a "use strict" first statement) route all
 * top-level statements through the strict walk + the top-level `var eval`
 * TS2300 pair; non-strict files route through the fn-local SEARCHING walk
 * (only fn-decl/fn-expr/arrow bodies carrying their OWN "use strict"
 * prologue flip to the strict walk; the search descends only fn-decl
 * bodies, expression statements, var initializers, blocks, if branches,
 * and namespace bodies — loops/switch/try/call-args are never searched).
 * Frozen quirks pinned both directions: class subtrees never emit (the
 * legacy class-element walk ran with an EMPTY restricted set); for-in
 * heads / while conditions / for-head decl initializers / conditional
 * expressions / object+array literals / arrow expression bodies are not
 * walked in strict regions; module top-level fn params and var decl TYPE
 * annotations are not checked; a fn-local prologue fn's own name/params
 * are not checked. All expectations verified against the pre-migration
 * walker.
 */
class M04StrictModeSpineMigrationTest {

    // ── STRICT mode (globalStrict via @strict: true) ───────────────────────

    @Test
    fun `TS1100 - top-level var eval in a strict file`() {
        diagnose("var eval = 1;") should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS2300 pair - top-level var eval collides with the lib declaration`() {
        val ds = diagnose("var eval = 1;")
        val dup = ds.filter { it.code == 2300 }
        val libSide = dup.count { it.fileName == "lib.es5.d.ts" }
        val fileSide = dup.count { it.fileName == "t.ts" && it.relatedInformation.any { r -> r.code == 6203 } }
        ds should {
            have(libSide == 1)
            have(fileSide == 1)
        }
    }

    @Test
    fun `TS1100 - top-level var arguments in a strict file draws no TS2300 pair`() {
        diagnose("var arguments = 1;") should {
            have(any { it.code == 1100 })
            have(none { it.code == 2300 })
        }
    }

    @Test
    fun `TS1100 - let eval fires TS1100 but not the var-only TS2300 pair`() {
        diagnose("let eval = 1;") should {
            have(any { it.code == 1100 })
            have(none { it.code == 2300 })
        }
    }

    @Test
    fun `TS1100 - function named eval`() {
        diagnose("function eval() {}") should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - function parameter named arguments`() {
        diagnose("function f(arguments: number) {}") should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - function expression name and parameter both fire`() {
        val ds = diagnose("var x = function eval(arguments: number) {};")
        val count = ds.count { it.code == 1100 }
        ds should { have(count == 2) }
    }

    @Test
    fun `TS1100 - arrow function parameter in a var initializer`() {
        diagnose("var x = (eval: number) => 0;") should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - assignment target eval at top level`() {
        diagnose("eval = 5;") should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - compound assignment target eval`() {
        diagnose("eval += 1;") should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 and TS2630 - postfix increment of eval`() {
        diagnose("eval++;") should {
            have(any { it.code == 1100 })
            have(any { it.code == 2630 })
        }
    }

    @Test
    fun `TS1100 - prefix decrement of arguments fires TS1100 without the eval-only TS2630`() {
        diagnose("--arguments;") should {
            have(any { it.code == 1100 })
            have(none { it.code == 2630 })
        }
    }

    @Test
    fun `TS1100 - function type parameter name in a var type annotation`() {
        diagnose("var f: (eval: string) => void;") should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - fn type param inside a parenthesized union annotation`() {
        diagnose("var f: ((arguments: string) => void) | null;") should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - type literal method parameter in a var annotation`() {
        diagnose("var o: { m(eval: string): void };") should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - interface method parameter named eval`() {
        diagnose("interface I { m(eval: string): void; }") should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - interface property NAMED arguments is legal`() {
        diagnose("interface I { arguments: number; }") should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - class member bodies never fire TS1100`() {
        // The legacy class-element walk ran with an EMPTY restricted set
        // (restricted minus arguments/eval — TS1210 owns class bodies), so a
        // class subtree can never emit TS1100. Frozen as unreached.
        diagnose(
            """
            class C {
                m(eval: string) { var arguments = 1; }
            }
            """
        ) should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - fires inside an if block`() {
        diagnose("if (1) { var eval = 1; }") should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - catch variable named eval`() {
        diagnose("try {} catch (eval) {}") should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - fires inside a switch case clause`() {
        diagnose(
            """
            declare var x: number;
            switch (x) { case 1: var eval = 1; }
            """
        ) should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - fires inside a namespace body`() {
        diagnose("namespace N { var eval = 1; }") should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - fires inside a while body`() {
        diagnose("while (1) { var eval = 1; }") should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - a for-in head declaration name is not checked`() {
        diagnose(
            """
            declare var o: any;
            for (var eval in o) {}
            """
        ) should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - a while condition expression is not walked`() {
        diagnose(
            """
            declare var c: any;
            while (eval = c) {}
            """
        ) should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - a for-head declaration initializer is not walked`() {
        diagnose("for (var i = function(eval: any) {};;) { break; }") should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - a conditional expression is not walked`() {
        diagnose("var x = 1 ? function(eval: any) {} : null;") should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - object literal property values are not walked`() {
        diagnose("var o = { m: function(eval: any) {} };") should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - array literal elements are not walked`() {
        diagnose("var a = [function(eval: any) {}];") should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - an arrow EXPRESSION body is not walked`() {
        diagnose("var x = () => (eval = 5);") should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - a throw expression is not walked`() {
        diagnose("throw function(eval: any) {};") should {
            have(none { it.code == 1100 })
        }
    }

    // ── MODULE mode (TS1215 top-level arms) ────────────────────────────────

    @Test
    fun `TS1215 - module top-level var eval draws TS1215 not TS1100 and no TS2300 pair`() {
        diagnose(
            """
            export {};
            var eval = 1;
            """
        ) should {
            have(any { it.code == 1215 })
            have(none { it.code == 1100 })
            have(none { it.code == 2300 })
        }
    }

    @Test
    fun `TS1215 - module top-level function named eval`() {
        diagnose(
            """
            export {};
            function eval() {}
            """
        ) should {
            have(any { it.code == 1215 })
        }
    }

    @Test
    fun `negative control - module top-level fn params are not checked`() {
        diagnose(
            """
            export {};
            function f(eval: any) {}
            """
        ) should {
            have(none { it.code == 1215 })
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - a module fn BODY is strict-walked`() {
        diagnose(
            """
            export {};
            function f() { var eval = 1; }
            """
        ) should {
            have(any { it.code == 1100 })
            have(none { it.code == 1215 })
        }
    }

    @Test
    fun `TS1100 - a module top-level var INITIALIZER is strict-walked`() {
        diagnose(
            """
            export {};
            var x = function(eval: any) {};
            """
        ) should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - a module top-level var TYPE annotation is not walked`() {
        diagnose(
            """
            export {};
            var f: (eval: string) => void;
            """
        ) should {
            have(none { it.code == 1100 })
            have(none { it.code == 1215 })
        }
    }

    @Test
    fun `TS1100 - a module top-level non-var non-fn statement takes the strict walk`() {
        diagnose(
            """
            export {};
            if (1) { var eval = 1; }
            """
        ) should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - a module declare function named eval is skipped`() {
        diagnose(
            """
            export {};
            declare function eval(): void;
            """
        ) should {
            have(none { it.code == 1215 })
            have(none { it.code == 1100 })
        }
    }

    // ── FN-LOCAL mode (non-strict file, "use strict" prologue bodies) ──────

    @Test
    fun `TS1100 - fn-decl body with its own use-strict prologue`() {
        diagnose(
            """
            function f() { "use strict"; var eval = 1; }
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - nested fn-decl with a prologue inside a non-strict fn`() {
        diagnose(
            """
            function o() { function i() { "use strict"; var arguments = 1; } }
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - fn-expression with a prologue via a var initializer`() {
        diagnose(
            """
            var x = function() { "use strict"; var eval = 1; };
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - arrow with a prologue via a var initializer`() {
        diagnose(
            """
            var x = () => { "use strict"; var eval = 1; };
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - a prologue fn-expression's OWN name and params are not checked`() {
        diagnose(
            """
            var x = function eval(arguments: any) { "use strict"; };
            """,
            directives = "// @strict: false",
        ) should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - the fn-local search does not descend loops`() {
        diagnose(
            """
            function f() { while (1) { var g = function() { "use strict"; var eval = 1; }; } }
            """,
            directives = "// @strict: false",
        ) should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - the fn-local search does not descend call arguments`() {
        diagnose(
            """
            declare function h(a: any): void;
            h(function() { "use strict"; var eval = 1; });
            """,
            directives = "// @strict: false",
        ) should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - the fn-local search descends if branches`() {
        diagnose(
            """
            if (1) { var g = function() { "use strict"; var eval = 1; }; }
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - the fn-local search descends namespace bodies`() {
        diagnose(
            """
            namespace N { function f() { "use strict"; var eval = 1; } }
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - a use-strict directive that is not the FIRST statement does not arm the body`() {
        diagnose(
            """
            function f() { var a = 1; "use strict"; var eval = 2; }
            """,
            directives = "// @strict: false",
        ) should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `TS1100 - a file-level use-strict first statement arms a non-strict file`() {
        diagnose(
            """
            "use strict";
            var eval = 1;
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 1100 })
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `negative control - no TS1100 in a plain non-strict file`() {
        diagnose(
            "var eval = 1;",
            directives = "// @strict: false",
        ) should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `negative control - dts files are skipped entirely`() {
        diagnose(
            """
            "use strict";
            var eval = 1;
            """,
            fileName = "t.d.ts",
        ) should {
            have(none { it.code == 1100 })
        }
    }
}
