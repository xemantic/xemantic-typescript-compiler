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
 * (M0.4, round 641): pins for the checkSuperRefInRebindingScope (TS2660
 * `super` references inside regular-function rebinding scopes) spine
 * migration. The walk threads ONE downward boolean: `rebound` starts TRUE
 * at top-level statements, a FunctionDeclaration/FunctionExpression body
 * RESETS it to true, an arrow PRESERVES it, a class-member body/property
 * initializer resets to FALSE at the direct level (only a nested fn inside
 * re-rebinds), a ModuleBlock preserves it. Frozen quirks: `super(...)`
 * callees are skipped (TS2337 territory) while the CALL ARGUMENTS are
 * walked; object literals are skipped entirely (the sibling
 * checkSuperInObjectLiterals owns them); the for-INITIALIZER expression is
 * walked but for-condition/incrementor are NOT; class EXPRESSIONS are
 * never walked. All expectations verified against the pre-migration
 * walker.
 */
class M04SuperRebindSpineMigrationTest {

    // ── fires: rebound=true contexts ───────────────────────────────────────

    @Test
    fun `TS2660 - super at top level`() {
        diagnose(
            """
            super.x;
            """
        ) should {
            have(any { it.code == 2660 })
        }
    }

    @Test
    fun `TS2660 - super in a function declaration body`() {
        diagnose(
            """
            function f() { super.x; }
            """
        ) should {
            have(any { it.code == 2660 })
        }
    }

    @Test
    fun `TS2660 - super in a function expression body`() {
        diagnose(
            """
            const g = function() { super.x; };
            """
        ) should {
            have(any { it.code == 2660 })
        }
    }

    @Test
    fun `TS2660 - arrow at top level preserves the rebinding scope`() {
        diagnose(
            """
            const h = () => super.x;
            """
        ) should {
            have(any { it.code == 2660 })
        }
    }

    @Test
    fun `TS2660 - nested function inside a class method re-rebinds`() {
        diagnose(
            """
            class C {
                m() {
                    function g() { super.x; }
                }
            }
            """
        ) should {
            have(any { it.code == 2660 })
        }
    }

    @Test
    fun `TS2660 - nested function expression inside a property initializer`() {
        diagnose(
            """
            class C {
                p = function() { return super.x; };
            }
            """
        ) should {
            have(any { it.code == 2660 })
        }
    }

    @Test
    fun `TS2660 - nested function inside a static block`() {
        diagnose(
            """
            class C {
                static {
                    const f = function() { super.x; };
                }
            }
            """
        ) should {
            have(any { it.code == 2660 })
        }
    }

    @Test
    fun `TS2660 - element access receiver and argument`() {
        val ds = diagnose(
            """
            function f(a: any) {
                super[0];
                a[super.x];
            }
            """
        )
        assertEquals(2, ds.count { it.code == 2660 })
    }

    @Test
    fun `TS2660 - super method call emits at the receiver`() {
        diagnose(
            """
            function f() { super.x(); }
            """
        ) should {
            have(any { it.code == 2660 })
        }
    }

    // ── silent: rebound=false contexts (class members) ─────────────────────

    @Test
    fun `negative control - class method direct level is not a rebinding scope`() {
        diagnose(
            """
            class B { x = 1; }
            class C extends B {
                m() { super.x; }
            }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - constructor direct level`() {
        diagnose(
            """
            class B { x = 1; }
            class C extends B {
                constructor() { super(); super.x; }
            }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - accessor bodies direct level`() {
        diagnose(
            """
            class B { x = 1; }
            class C extends B {
                get g() { return super.x; }
                set s(v: number) { super.x; }
            }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - property initializer direct level`() {
        diagnose(
            """
            class B { x = 1; }
            class C extends B {
                p = super.x;
            }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - static block direct level`() {
        diagnose(
            """
            class B { static x = 1; }
            class C extends B {
                static { super.x; }
            }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - arrow inside a class method preserves the member scope`() {
        diagnose(
            """
            class B { x = 1; }
            class C extends B {
                m() { const a = () => super.x; }
            }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - a class declaration inside a function body still resets its members`() {
        diagnose(
            """
            function f() {
                class B { x = 1; }
                class D extends B {
                    m() { super.x; }
                }
            }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    // ── frozen quirks: super() callee, object literals, class expressions ──

    @Test
    fun `negative control - a super call callee is skipped`() {
        diagnose(
            """
            function f() { super(); }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `TS2660 - super call ARGUMENTS are still walked`() {
        val ds = diagnose(
            """
            function f() { super(super.x); }
            """
        )
        assertEquals(1, ds.count { it.code == 2660 })
    }

    @Test
    fun `TS2660 - a parenthesized super callee is not the skipped shape`() {
        diagnose(
            """
            function f() { (super)(); }
            """
        ) should {
            have(any { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - object literal property values are skipped entirely`() {
        diagnose(
            """
            function f() {
                const o = { p: super.x };
            }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - object literal method bodies belong to the sibling pass`() {
        diagnose(
            """
            function f() {
                const o = { m() { super.x; } };
            }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - class expression members are never walked`() {
        diagnose(
            """
            function f() {
                const c = class { m() { super.x; } };
            }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    // ── structural reach: the frozen edge set ──────────────────────────────

    @Test
    fun `TS2660 - a for initializer expression is walked`() {
        diagnose(
            """
            function f() { for (super.x; ;) {} }
            """
        ) should {
            have(any { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - a for condition is not walked`() {
        diagnose(
            """
            function f() { for (; super.x; ) {} }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - a for incrementor is not walked`() {
        diagnose(
            """
            function f() { for (; ; super.x) {} }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `TS2660 - for-of and for-in subject expressions are walked`() {
        val ds = diagnose(
            """
            function f() {
                for (const a of super.x) {}
                for (const k in super.y) {}
            }
            """
        )
        assertEquals(2, ds.count { it.code == 2660 })
    }

    @Test
    fun `TS2660 - a switch case expression is walked`() {
        diagnose(
            """
            function f(n: number) {
                switch (n) { case super.x: break; }
            }
            """
        ) should {
            have(any { it.code == 2660 })
        }
    }

    @Test
    fun `TS2660 - try catch finally blocks are walked`() {
        val ds = diagnose(
            """
            function f() {
                try { super.a; } catch (e) { super.b; } finally { super.c; }
            }
            """
        )
        assertEquals(3, ds.count { it.code == 2660 })
    }

    @Test
    fun `TS2660 - a namespace body preserves the top-level rebinding scope`() {
        diagnose(
            """
            namespace N {
                super.x;
            }
            """
        ) should {
            have(any { it.code == 2660 })
        }
    }

    @Test
    fun `TS2660 - a function inside a namespace`() {
        diagnose(
            """
            namespace N {
                export function f() { super.x; }
            }
            """
        ) should {
            have(any { it.code == 2660 })
        }
    }

    @Test
    fun `TS2660 - template span and tagged template positions`() {
        val ds = diagnose(
            """
            function f(tag: any) {
                const t = `a${'$'}{super.x}b`;
                tag`c${'$'}{super.y}d`;
            }
            """
        )
        assertEquals(2, ds.count { it.code == 2660 })
    }

    @Test
    fun `TS2660 - unary and cast operand positions`() {
        val ds = diagnose(
            """
            function f() {
                void super.a;
                typeof super.b;
                (super.c as any);
                super.d!;
            }
            """
        )
        assertEquals(4, ds.count { it.code == 2660 })
    }

    @Test
    fun `TS2660 - binary chain conditional and spread positions`() {
        val ds = diagnose(
            """
            function f(g: any, n: number) {
                const a = 1 + super.a;
                n ? super.b : super.c;
                g(...super.d);
                new (super.e)();
            }
            """
        )
        assertEquals(5, ds.count { it.code == 2660 })
    }

    // ── run gates ──────────────────────────────────────────────────────────

    @Test
    fun `negative control - a dts file is never checked`() {
        diagnose(
            """
            function f() { super.x; }
            """,
            fileName = "t.d.ts",
        ) should {
            have(none { it.code == 2660 })
        }
    }
}
