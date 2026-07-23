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
 * (M0.4, round 646): pins for the checkConstLiteralComparisons (B98.r101
 * TS2367 for a for-INIT `const x = <bare literal>` compared to a DIFFERENT
 * literal via ==/===/!=/!==) spine migration. The walk threads ONE downward
 * map (name → const-literal), populated ONLY by the ForStatement arm
 * (for-init const bare-literal declarations, scoped to cond/incr/body);
 * every statement LIST applies a whole-list SHADOW prepass (any
 * VariableStatement declaration name removes an inherited entry —
 * order-independent), and fn-decl/fn-expr/arrow boundaries remove their
 * parameter names. Frozen quirks pinned both directions: block consts
 * never track; class METHOD/constructor parameters do NOT shadow (only
 * fn-decl/fn-expr/arrow parameters do); a catch VARIABLE does not shadow;
 * accessor bodies, object-literal values, template spans, new-expression
 * CALLEES, for-in/of HEADS, and inner for-init declaration INITIALIZERS
 * are never walked. All expectations verified against the pre-migration
 * walker.
 */
class M04ConstLitSpineMigrationTest {

    private fun msg(a: String, b: String) =
        "This comparison appears to be unintentional because the types '$a' and '$b' have no overlap."

    // ── fires: the four operators, operand shapes, literal kinds ──────────

    @Test
    fun `for-init const vs a different numeric literal in the condition fires`() {
        diagnose("for (const x = 1; x == 2; ) { }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `strict-equality in the incrementor fires`() {
        diagnose("for (const x = 1; false; x === 2) { }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `not-equals inside the for body fires`() {
        diagnose("for (const x = 1; ; ) { if (x != 2) { break; } }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `strict-not-equals inside the for body fires`() {
        diagnose("for (const x = 1; ; ) { if (x !== 2) { break; } }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `string const vs a different string literal fires with quoted displays`() {
        diagnose("""for (const s = "a"; s == "b"; ) { }""") should {
            have(any { it.code == 2367 && it.message == msg("\"a\"", "\"b\"") })
        }
    }

    @Test
    fun `negative numeric const displays with the minus sign`() {
        diagnose("for (const n = -1; n == 1; ) { }") should {
            have(any { it.code == 2367 && it.message == msg("-1", "1") })
        }
    }

    @Test
    fun `literal on the left of the tracked const fires`() {
        diagnose("for (const x = 1; 2 == x; ) { }") should {
            have(any { it.code == 2367 && it.message == msg("2", "1") })
        }
    }

    @Test
    fun `two for-init consts with different values fire ident-vs-ident`() {
        diagnose("for (const a = 1; ; ) { for (const b = 2; a == b; ) { } }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `same-value comparisons never fire`() {
        diagnose(
            """
            for (const x = 1; x == 1; ) { }
            for (const a = 2; ; ) { for (const b = 2; a == b; ) { } }
            """
        ) should {
            have(none { it.code == 2367 })
        }
    }

    // ── fires: reached positions ───────────────────────────────────────────

    @Test
    fun `nested function body without a shadowing parameter fires`() {
        diagnose("for (const x = 1; ; ) { function h() { return x == 2; } }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `arrow expression body fires`() {
        diagnose("for (const x = 1; ; ) { const c = () => x == 2; }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `function expression body fires`() {
        diagnose("for (const x = 1; ; ) { const c = function () { return x == 2; }; }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `class method body fires and a method parameter does not shadow - frozen`() {
        diagnose("for (const x = 1; ; ) { class C { m(x: number) { return x == 2; } } }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `constructor body fires and a constructor parameter does not shadow - frozen`() {
        diagnose("for (const x = 1; ; ) { class C { constructor(x: number) { if (x == 2) { } } } }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `class property initializer fires`() {
        diagnose("for (const x = 1; ; ) { class C { p = x == 2; } }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `switch case statements fire`() {
        diagnose("for (const x = 1; ; ) { switch (0) { case 0: if (x === 2) { } } }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `catch block fires and the catch variable does not shadow - frozen`() {
        diagnose("for (const x = 1; ; ) { try { } catch (x) { if (x == 2) { } } }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `non-block for body statement fires`() {
        diagnose("for (const x = 1; ; ) if (x == 2) { }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `while and do-while conditions inside the for body fire`() {
        val ds = diagnose(
            """
            for (const x = 1; ; ) { while (x == 2) { } }
            for (const y = 3; ; ) { do { } while (y === 4); }
            """
        )
        ds should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
            have(any { it.code == 2367 && it.message == msg("3", "4") })
        }
    }

    @Test
    fun `namespace body statements fire`() {
        diagnose("namespace N { for (const x = 1; x == 2; ) { } }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `new-expression arguments fire`() {
        diagnose(
            """
            class K { constructor(a: boolean) { } }
            for (const x = 1; ; ) { new K(x == 2); }
            """
        ) should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `labeled block inside the for body fires`() {
        diagnose("for (const x = 1; ; ) { lbl: { if (x == 2) break lbl; } }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `variable initializer in the for body fires`() {
        diagnose("for (const x = 1; ; ) { const b = x == 2; }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `array literal element and negated paren fire`() {
        val ds = diagnose(
            """
            for (const x = 1; ; ) { [x == 2]; }
            for (const y = 3; ; ) { !(y === 4); }
            """
        )
        ds should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
            have(any { it.code == 2367 && it.message == msg("3", "4") })
        }
    }

    @Test
    fun `call arguments fire`() {
        diagnose(
            """
            function p(b: boolean) { }
            for (const x = 1; ; ) { p(x == 2); }
            """
        ) should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `return expression fires`() {
        diagnose("function r() { for (const x = 1; ; ) { return x == 2; } }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    @Test
    fun `for-in and for-of bodies fire`() {
        diagnose("for (const x = 1; ; ) { for (const k in { a: 1 }) { if (x == 2) { } } }") should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }

    // ── silent: tracking gates ─────────────────────────────────────────────

    @Test
    fun `negative control - a block const never tracks`() {
        diagnose("function f() { const x = 1; if (x == 2) { } }") should {
            have(none { it.code == 2367 })
        }
    }

    @Test
    fun `negative control - let annotated and non-literal for-init decls do not track`() {
        diagnose(
            """
            function g(): number { return 3; }
            for (let a = 1; a == 2; ) { }
            for (const b: number = 1; b == 2; ) { }
            for (const c = g(); c == 2; ) { }
            """
        ) should {
            have(none { it.code == 2367 })
        }
    }

    @Test
    fun `negative control - a same-name block declaration shadows the for-init const`() {
        diagnose(
            """
            function g(): number { return 3; }
            for (const x = 1; ; ) { { const x = g(); if (x == 2) { } } }
            """
        ) should {
            have(none { it.code == 2367 })
        }
    }

    @Test
    fun `negative control - the shadow is whole-list so a use before the declaration is silent`() {
        diagnose(
            """
            function g(): number { return 3; }
            for (const x = 1; ; ) { { if (x == 2) { } const x = g(); } }
            """
        ) should {
            have(none { it.code == 2367 })
        }
    }

    @Test
    fun `negative control - function and arrow parameters shadow`() {
        diagnose(
            """
            for (const x = 1; ; ) { function g2(x: number) { if (x == 2) { } } }
            for (const y = 3; ; ) { const c = (y: number) => y == 4; }
            """
        ) should {
            have(none { it.code == 2367 })
        }
    }

    @Test
    fun `negative control - a comparison against a non-literal expression is silent`() {
        diagnose(
            """
            function g(): number { return 3; }
            for (const x = 1; x == g(); ) { }
            """
        ) should {
            have(none { it.code == 2367 })
        }
    }

    // ── silent: unreached positions ────────────────────────────────────────

    @Test
    fun `negative control - object literal property values are unreached`() {
        diagnose("for (const x = 1; ; ) { const o = { p: x == 2 }; }") should {
            have(none { it.code == 2367 })
        }
    }

    @Test
    fun `negative control - template spans are unreached`() {
        diagnose("for (const x = 1; ; ) { const t = `\${x == 2}`; }") should {
            have(none { it.code == 2367 })
        }
    }

    @Test
    fun `negative control - a new-expression callee is unreached`() {
        diagnose("for (const x = 1; ; ) { new ((x == 2) as any)(); }") should {
            have(none { it.code == 2367 })
        }
    }

    @Test
    fun `negative control - a for-of head is unreached`() {
        diagnose("for (const x = 1; ; ) { for (const k of [x == 2]) { } }") should {
            have(none { it.code == 2367 })
        }
    }

    @Test
    fun `negative control - an inner for-init declaration initializer is unreached`() {
        diagnose("for (const x = 1; ; ) { for (const y = x == 2 ? 1 : 2; ; ) { break; } }") should {
            have(none { it.code == 2367 })
        }
    }

    @Test
    fun `negative control - accessor bodies are unreached`() {
        diagnose("for (const x = 1; ; ) { class D { get g() { return x == 2; } } }") should {
            have(none { it.code == 2367 })
        }
    }

    // ── silent: file gates ─────────────────────────────────────────────────

    @Test
    fun `negative control - dts files are skipped`() {
        diagnose("for (const x = 1; x == 2; ) { }", fileName = "t.d.ts") should {
            have(none { it.code == 2367 })
        }
    }

    @Test
    fun `negative control - js files are skipped without checkJs`() {
        diagnose(
            "for (const x = 1; x == 2; ) { }",
            directives = "// @allowJs: true",
            fileName = "t.js",
        ) should {
            have(none { it.code == 2367 })
        }
    }

    @Test
    fun `js files fire with checkJs`() {
        diagnose(
            "for (const x = 1; x == 2; ) { }",
            directives = "// @allowJs: true\n// @checkJs: true",
            fileName = "t.js",
        ) should {
            have(any { it.code == 2367 && it.message == msg("1", "2") })
        }
    }
}
