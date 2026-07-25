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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (M0.4, round 647): pins for the checkSuperInObjectLiterals (TS2659
 * `super` in object-literal members below ES2015 / TS2660 `super` in
 * object-literal property functions) spine migration. The walk threads ONE
 * downward boolean `superValid`: FALSE at top-level statements,
 * fn-decl/fn-expr bodies RESET it to false, arrows/blocks/ModuleBlocks
 * PRESERVE, class-member bodies + property initializers set it from the
 * containing class's `extends` clause, objlit method/accessor bodies set
 * TRUE. Emissions happen ONLY at object-literal properties: method/accessor
 * bodies draw TS2659 when target < ES2015; a PropertyAssignment whose
 * initializer is a FunctionExpression draws TS2660 unconditionally, an
 * ArrowFunction initializer only when !superValid — all via the BOUNDED
 * findObjLitSuperRefs leaf, which never descends nested object literals,
 * arrows, or function expressions. All expectations verified against the
 * pre-migration walker.
 */
class M04ObjLitSuperSpineMigrationTest {

    // ── TS2660 fires: superValid=false contexts ────────────────────────────

    @Test
    fun `TS2660 - function expression property at top level`() {
        val ds = diagnose(
            """
            const o = { p: function() { return super.x; } };
            """
        )
        assert(ds.count { it.code == 2660 } == 1)
    }

    @Test
    fun `TS2660 - arrow expression body at top level`() {
        val ds = diagnose(
            """
            const o = { p: () => super.x };
            """
        )
        assert(ds.count { it.code == 2660 } == 1)
    }

    @Test
    fun `TS2660 - arrow block body at top level`() {
        val ds = diagnose(
            """
            const o = { p: () => { return super.x; } };
            """
        )
        assert(ds.count { it.code == 2660 } == 1)
    }

    @Test
    fun `TS2660 - function expression is unconditional even in a derived class method`() {
        val ds = diagnose(
            """
            class A { x = 1; }
            class B extends A {
                m() {
                    const o = { p: function() { return super.x; } };
                }
            }
            """
        )
        assert(ds.count { it.code == 2660 } == 1)
    }

    @Test
    fun `TS2660 - arrow in a NON-derived class method fires`() {
        val ds = diagnose(
            """
            class C {
                m() {
                    const o = { p: () => super.x };
                }
            }
            """
        )
        assert(ds.count { it.code == 2660 } == 1)
    }

    @Test
    fun `TS2660 - a function declaration body inside a derived method resets superValid`() {
        val ds = diagnose(
            """
            class A { x = 1; }
            class B extends A {
                m() {
                    function g() {
                        const o = { p: () => super.x };
                    }
                }
            }
            """
        )
        assert(ds.count { it.code == 2660 } == 1)
    }

    @Test
    fun `TS2660 - two offending properties emit once each`() {
        val ds = diagnose(
            """
            const a = { p: function() { return super.x; }, q: () => super.y };
            """
        )
        assert(ds.count { it.code == 2660 } == 2)
    }

    // ── TS2660 silent: superValid=true contexts ────────────────────────────

    @Test
    fun `negative control - arrow in a derived class method inherits super`() {
        diagnose(
            """
            class A { x = 1; }
            class B extends A {
                m() {
                    const o = { p: () => super.x };
                }
            }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - arrow in a derived class property initializer`() {
        diagnose(
            """
            class A { x = 1; }
            class B extends A {
                p = { q: () => super.x };
            }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - arrow in a derived class static block`() {
        diagnose(
            """
            class A { static x = 1; }
            class B extends A {
                static {
                    const o = { p: () => super.x };
                }
            }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `TS2660 - arrow in a non-derived class static block fires`() {
        val ds = diagnose(
            """
            class C {
                static {
                    const o = { p: () => super.x };
                }
            }
            """
        )
        assert(ds.count { it.code == 2660 } == 1)
    }

    @Test
    fun `negative control - an object literal method body provides super to nested literals`() {
        // The nested literal's arrow inherits superValid=true from the outer
        // objlit METHOD body; the outer 2659 leaf bails at the nested literal.
        diagnose(
            """
            const o = { m() { const i = { p: () => super.x }; } };
            """
        ) should {
            have(none { it.code == 2660 })
            have(none { it.code == 2659 })
        }
    }

    @Test
    fun `negative control - an arrow returning an arrow hides super from the leaf`() {
        // The bounded leaf bails on a nested ArrowFunction, and the general
        // walk emits nothing outside objlit properties — frozen quirk.
        diagnose(
            """
            const o = { p: () => () => super.x };
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - only a DIRECT fn-expr initializer emits`() {
        // A parenthesized/comma-wrapped function expression takes the
        // preserve arm (no leaf call) — frozen quirk.
        diagnose(
            """
            const o = { p: (0, function() { return super.x; }) };
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    // ── TS2659: target below ES2015 ────────────────────────────────────────

    @Test
    fun `TS2659 - object literal method at default target`() {
        val ds = diagnose(
            """
            const o = { m() { return super.x; } };
            """
        )
        assert(ds.count { it.code == 2659 } == 1)
        assert(ds.count { it.code == 2660 } == 0)
    }

    @Test
    fun `TS2659 - get and set accessor bodies`() {
        val ds = diagnose(
            """
            const o = {
                get g() { return super.a; },
                set s(v: number) { super.b; }
            };
            """
        )
        assert(ds.count { it.code == 2659 } == 2)
    }

    @Test
    fun `negative control - no TS2659 or TS2660 at es2015`() {
        diagnose(
            """
            const o = { m() { return super.x; } };
            """,
            directives = "// @strict: true\n// @target: es2015",
        ) should {
            have(none { it.code == 2659 })
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `TS2660 - fn-expr property still fires at es2015`() {
        val ds = diagnose(
            """
            const o = { p: function() { return super.x; } };
            """,
            directives = "// @strict: true\n// @target: es2015",
        )
        assert(ds.count { it.code == 2660 } == 1)
    }

    @Test
    fun `TS2659 and TS2660 mix in one literal`() {
        val ds = diagnose(
            """
            const o = { m() { return super.a; }, p: function() { return super.b; } };
            """
        )
        assert(ds.count { it.code == 2659 } == 1)
        assert(ds.count { it.code == 2660 } == 1)
    }

    // ── the bounded leaf's frozen coverage ─────────────────────────────────

    @Test
    fun `TS2659 - leaf reaches through binary chains`() {
        val ds = diagnose(
            """
            const o = { m() { return 1 + super.x; } };
            """
        )
        assert(ds.count { it.code == 2659 } == 1)
    }

    @Test
    fun `TS2659 - leaf statement coverage - if while var return`() {
        val ds = diagnose(
            """
            const o = { m() {
                if (super.a) { super.b; }
                while (super.c) { }
                const v = super.d;
            } };
            """
        )
        assert(ds.count { it.code == 2659 } == 4)
    }

    @Test
    fun `TS2659 - leaf call callee and arguments`() {
        val ds = diagnose(
            """
            const o = { m() { super.f(super.g); } };
            """
        )
        assert(ds.count { it.code == 2659 } == 2)
    }

    @Test
    fun `negative control - leaf does not handle throw try or switch statements`() {
        diagnose(
            """
            const o = {
                m1() { throw super.a; },
                m2() { try { super.b; } catch (e) { } },
                m3() { switch (super.c) { } }
            };
            """
        ) should {
            have(none { it.code == 2659 })
        }
    }

    @Test
    fun `negative control - leaf does not descend new or element access`() {
        diagnose(
            """
            const o = {
                m1() { new (super.x)(); },
                m2() { super["y"]; }
            };
            """
        ) should {
            have(none { it.code == 2659 })
        }
    }

    // ── structural reach: the frozen edge set ──────────────────────────────

    @Test
    fun `TS2660 - a throw expression is walked`() {
        val ds = diagnose(
            """
            throw { p: function() { return super.x; } };
            """
        )
        assert(ds.count { it.code == 2660 } == 1)
    }

    @Test
    fun `TS2660 - an export-equals expression is walked`() {
        val ds = diagnose(
            """
            export = { p: function() { return super.x; } };
            """
        )
        assert(ds.count { it.code == 2660 } == 1)
    }

    @Test
    fun `TS2659 - a for-in head expression is walked`() {
        val ds = diagnose(
            """
            for (const k in { m() { return super.x; } }) { }
            """
        )
        assert(ds.count { it.code == 2659 } == 1)
    }

    @Test
    fun `TS2659 - a for-of head expression is walked`() {
        val ds = diagnose(
            """
            for (const v of [{ m() { return super.x; } }]) { }
            """
        )
        assert(ds.count { it.code == 2659 } == 1)
    }

    @Test
    fun `TS2660 - a for-head expression initializer is walked`() {
        val ds = diagnose(
            """
            let o;
            for (o = { p: function() { return super.x; } }; ; ) { break; }
            """
        )
        assert(ds.count { it.code == 2660 } == 1)
    }

    @Test
    fun `negative control - a for-head declaration-list initializer is not walked`() {
        diagnose(
            """
            for (let o = { p: function() { return super.x; } }; ; ) { break; }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - for condition and incrementor are not walked`() {
        diagnose(
            """
            let c: any;
            for (; c = { p: () => super.a }; c = { q: () => super.b }) { break; }
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - class expression members are never walked`() {
        diagnose(
            """
            const C = class {
                m() {
                    const o = { p: function() { return super.x; } };
                }
            };
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `negative control - object literal member parameter defaults are not walked`() {
        diagnose(
            """
            const o = { m(a = { p: function() { return super.x; } }) { } };
            """
        ) should {
            have(none { it.code == 2660 })
        }
    }

    @Test
    fun `TS2660 - a spread assignment operand is walked`() {
        val ds = diagnose(
            """
            const o = { ...{ p: function() { return super.x; } } };
            """
        )
        assert(ds.count { it.code == 2660 } == 1)
    }

    @Test
    fun `TS2660 - a template span expression is walked`() {
        val ds = diagnose(
            """
            const t = `a${'$'}{ ({ p: function() { return super.x; } }) }b`;
            """
        )
        assert(ds.count { it.code == 2660 } == 1)
    }

    @Test
    fun `TS2660 - switch clause statements are walked`() {
        val ds = diagnose(
            """
            switch (1) {
                case 2:
                    const o = { p: () => super.x };
                    break;
                default:
                    const q = { r: () => super.y };
            }
            """
        )
        assert(ds.count { it.code == 2660 } == 2)
    }

    @Test
    fun `TS2660 - do-statement body and labeled statement are walked`() {
        val ds = diagnose(
            """
            do { var o = { p: () => super.x }; } while (false);
            l: { const q = { r: () => super.y }; }
            """
        )
        assert(ds.count { it.code == 2660 } == 2)
    }

    @Test
    fun `TS2660 - a namespace body preserves the top-level scope`() {
        val ds = diagnose(
            """
            namespace N {
                export const o = { p: () => super.x };
            }
            """
        )
        assert(ds.count { it.code == 2660 } == 1)
    }

    @Test
    fun `TS2660 - try catch finally blocks are walked`() {
        val ds = diagnose(
            """
            try { const a = { p: () => super.a }; }
            catch (e) { const b = { q: () => super.b }; }
            finally { const c = { r: () => super.c }; }
            """
        )
        assert(ds.count { it.code == 2660 } == 3)
    }

    @Test
    fun `TS2660 - exactly once for a super nested two literals deep`() {
        // The outer anchor's leaf bails at the inner literal; only the inner
        // literal's own arrow property emits.
        val ds = diagnose(
            """
            const o = { p: () => { const i = { q: () => super.x }; } };
            """
        )
        assert(ds.count { it.code == 2660 } == 1)
    }

    // ── run gates ──────────────────────────────────────────────────────────

    @Test
    fun `negative control - a dts file is never checked`() {
        diagnose(
            """
            const o = { p: function() { return super.x; } };
            """,
            fileName = "t.d.ts",
        ) should {
            have(none { it.code == 2660 })
        }
    }
}
