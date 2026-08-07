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
 * (M0.4): pins for the checkProtectedMemberReadAccess spine migration (B446 —
 * TS2445/TS2446 protected READ access + the class-method WRITE check). Pins the
 * emitters' gates and the legacy container-scan/walker reach, both directions:
 * top-level ExpressionStatements are walked with the file-level topVars map
 * while NAMESPACE-level ExpressionStatements are not; the downward vars map is
 * statement-order MUTATED (a `new C()` local records after its declaration and
 * the recording LEAKS out of if/loop blocks) and is COPIED at nested
 * fn-expression / fn-declaration boundaries (which also reset `this`, the
 * lexical class, and the in-class-method write gate) while arrows inherit
 * everything; a write LHS `obj.m = …` is accessibility-checked only inside a
 * class method and its SUBTREE is never read-walked; NewExpression arguments,
 * object literals, switch bodies, for-of head expressions, class property
 * initializers, nested classes, and expression-bodied container arrows are
 * unreached. All expectations verified green against the pre-migration legacy
 * pass first.
 */
class M04ProtectedReadSpineMigrationTest {

    private val prelude = """
        class Base {
            protected x: number = 1;
            protected static sx: number = 2;
            y: number = 3;
        }
        class Derived extends Base { }
        declare var b: Base;
    """.trimIndent() + "\n"

    // ── top-level reads (the topVars map) ──────────────────────────────────

    @Test
    fun `top-level read of a protected member fires TS2445`() {
        diagnose(prelude + "b.x;") should {
            have(any { it.code == 2445 &&
                it.message == "Property 'x' is protected and only accessible within class 'Base' and its subclasses." })
        }
    }

    @Test
    fun `top-level read of a protected static fires TS2445`() {
        diagnose(prelude + "Base.sx;") should {
            have(any { it.code == 2445 })
        }
    }

    @Test
    fun `negative control - top-level read of a public member is clean`() {
        diagnose(prelude + "b.y;") should {
            have(none { it.code == 2445 || it.code == 2446 })
        }
    }

    @Test
    fun `top-level new-inferred const is tracked in topVars`() {
        diagnose(prelude + "const t = new Base();\nt.x;") should {
            have(any { it.code == 2445 })
        }
    }

    @Test
    fun `negative control - namespace-level expression statement is unreached`() {
        diagnose(
            prelude + """
            namespace N {
                export declare var nb: Base;
                nb.x;
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2445 || it.code == 2446 })
        }
    }

    // ── class-method reads (TS2445 / TS2446) ───────────────────────────────

    @Test
    fun `method of an unrelated class reading a protected member fires TS2445`() {
        diagnose(
            prelude + """
            class Reader {
                read(p: Base): number { return p.x; }
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2445 })
        }
    }

    @Test
    fun `negative control - subclass method reading through a subclass instance is clean`() {
        diagnose(
            prelude + """
            class R2 extends Base {
                read(p: R2): number { return p.x; }
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2445 || it.code == 2446 })
        }
    }

    @Test
    fun `reading through a base-class instance from a subclass method fires TS2446`() {
        diagnose(
            prelude + """
            class R3 extends Base {
                read(p: Base): number { return p.x; }
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2446 &&
                it.message == "Property 'x' is protected and only accessible through an instance of class 'R3'. This is an instance of class 'Base'." })
        }
    }

    @Test
    fun `negative control - this access to an inherited protected member is clean`() {
        diagnose(
            prelude + """
            class R4 extends Base {
                read(): number { return this.x; }
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2445 || it.code == 2446 })
        }
    }

    @Test
    fun `constructor bodies are reached`() {
        diagnose(
            prelude + """
            class C7 {
                constructor(p: Base) { p.x; }
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2445 })
        }
    }

    @Test
    fun `getter bodies are reached and locals record`() {
        diagnose(
            prelude + """
            class G1 {
                get v(): number { const a = new Base(); return a.x; }
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2445 })
        }
    }

    @Test
    fun `namespace-level class methods are reached`() {
        diagnose(
            prelude + """
            namespace N2 {
                export class R {
                    read(p: Base): number { return p.x; }
                }
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2445 })
        }
    }

    @Test
    fun `negative control - method reading a top-level var is not checked`() {
        // topVars feeds ONLY top-level ExpressionStatements; a method body's
        // vars map holds params + body locals, so `b.x` inside a method
        // resolves no receiver class and draws nothing from this pass.
        diagnose(
            prelude + """
            class M1 {
                m(): number { return b.x; }
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2445 || it.code == 2446 })
        }
    }

    // ── statics ────────────────────────────────────────────────────────────

    @Test
    fun `negative control - subclass method reading a protected static is clean`() {
        diagnose(
            prelude + """
            class S2 extends Base {
                get(): number { return Base.sx; }
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2445 || it.code == 2446 })
        }
    }

    @Test
    fun `this-param fallback is not allowed for protected statics`() {
        diagnose(
            prelude + "function sf(this: Derived): number { return Base.sx; }"
        ) should {
            have(any { it.code == 2445 })
        }
    }

    // ── free functions and the this-param fallback ─────────────────────────

    @Test
    fun `free function reading a protected member fires TS2445`() {
        diagnose(prelude + "function rf(p: Base): number { return p.x; }") should {
            have(any { it.code == 2445 })
        }
    }

    @Test
    fun `negative control - this-param class grants protected access in a free function`() {
        diagnose(
            prelude + "function tf(this: Derived, p: Derived): number { return p.x; }"
        ) should {
            have(none { it.code == 2445 || it.code == 2446 })
        }
    }

    // ── writes (the pmrInClassMethod gate) ─────────────────────────────────

    @Test
    fun `write to a protected member inside a class method fires TS2445`() {
        diagnose(
            prelude + """
            class W {
                wr(p: Base): void { p.x = 5; }
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2445 })
        }
    }

    @Test
    fun `negative control - write in a plain free function is not checked by this pass`() {
        diagnose(prelude + "function wf(p: Base): void { p.x = 5; }") should {
            have(none { it.code == 2445 })
        }
    }

    @Test
    fun `negative control - write check is suppressed inside a nested function expression`() {
        diagnose(
            prelude + """
            class W2 {
                m(): void {
                    const g = function (p: Base): void { p.x = 5; };
                }
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2445 })
        }
    }

    @Test
    fun `arrow write inherits the class-method write gate`() {
        diagnose(
            prelude + """
            class A3 {
                m(p: Base): void {
                    const g = (): void => { p.x = 5; };
                }
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2445 })
        }
    }

    @Test
    fun `write LHS subtree is never read-walked`() {
        diagnose(
            prelude + """
            class W4 {
                m(p: Base, arr: Array<{ q: number }>): void {
                    arr[p.x].q = 0;
                }
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2445 })
        }
    }

    @Test
    fun `the same access in read position descends and fires`() {
        diagnose(
            prelude + """
            class W5 {
                m(p: Base, arr: Array<{ q: number }>): number {
                    return arr[p.x].q;
                }
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2445 })
        }
    }

    // ── the statement-order-mutated vars map ───────────────────────────────

    @Test
    fun `a new-instantiated local is tracked for protected reads`() {
        diagnose(
            prelude + """
            function f(): number {
                const a = new Base();
                return a.x;
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2445 })
        }
    }

    @Test
    fun `a recording leaks out of an if block`() {
        diagnose(
            prelude + """
            function f(c: boolean): number {
                if (c) { var a = new Base(); }
                return a.x;
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2445 })
        }
    }

    @Test
    fun `identifier-copy recording tracks the class`() {
        diagnose(
            prelude + """
            function f(p: Base): number {
                const q = p;
                return q.x;
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2445 })
        }
    }

    // ── nested function boundaries vs arrows ───────────────────────────────

    @Test
    fun `nested function expression copies captured var classes for reads`() {
        diagnose(
            prelude + """
            class C6 extends Base {
                m(): void {
                    const g = function (p: C6): number { return p.x; };
                }
            }
            """.trimIndent()
        ) should {
            // The nested fn-expr RESETS this/lexical class (legacy
            // pmrProcessNestedFn), so even inside a subclass the read has no
            // granting class.
            have(any { it.code == 2445 })
        }
    }

    @Test
    fun `nested function declaration copies captured vars`() {
        diagnose(
            prelude + """
            function outer(p: Base): void {
                function inner(): number { return p.x; }
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2445 })
        }
    }

    @Test
    fun `expression-bodied arrow in a method inherits this and fires TS2446`() {
        diagnose(
            prelude + """
            class A2 extends Base {
                m(p: Base): void {
                    const g = (): number => p.x;
                }
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2446 })
        }
    }

    // ── container-scan reach ───────────────────────────────────────────────

    @Test
    fun `block-bodied top-level arrow initializer is reached`() {
        diagnose(prelude + "const g = (p: Base): number => { return p.x; };") should {
            have(any { it.code == 2445 })
        }
    }

    @Test
    fun `negative control - expression-bodied top-level arrow initializer is unreached`() {
        diagnose(prelude + "const h = (p: Base) => p.x;") should {
            have(none { it.code == 2445 })
        }
    }

    @Test
    fun `negative control - class property initializers are unreached`() {
        diagnose(
            prelude + """
            class P1 {
                v = b.x;
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2445 })
        }
    }

    @Test
    fun `negative control - a class nested in a function body is unreached`() {
        diagnose(
            prelude + """
            function f(): void {
                class Inner {
                    m(p: Base): number { return p.x; }
                }
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2445 })
        }
    }

    // ── walker reach quirks ────────────────────────────────────────────────

    @Test
    fun `negative control - new-expression arguments are unreached`() {
        diagnose(
            prelude + """
            class Box { constructor(n: number) { } }
            function f(p: Base): void {
                const o = new Box(p.x);
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2445 })
        }
    }

    @Test
    fun `negative control - switch bodies are unreached`() {
        diagnose(
            prelude + """
            function f(p: Base, n: number): void {
                switch (n) { default: p.x; }
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2445 })
        }
    }

    @Test
    fun `negative control - object literal values are unreached`() {
        diagnose(
            prelude + """
            function f(p: Base): void {
                const o = { v: p.x };
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2445 })
        }
    }

    @Test
    fun `negative control - for-of head expression is unreached`() {
        diagnose(
            prelude + """
            function f(p: Base): void {
                for (const q of [p.x]) { }
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2445 })
        }
    }

    @Test
    fun `for-of body is reached`() {
        diagnose(
            prelude + """
            function f(p: Base, arr: number[]): void {
                for (const q of arr) { p.x; }
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2445 })
        }
    }

    @Test
    fun `while conditions and template spans are reached`() {
        diagnose(
            prelude + """
            function f(p: Base): string {
                while (p.x) { break; }
                return `v=${'$'}{p.x}`;
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2445 })
        }
    }
}
