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
 * (M0.4): pins for the checkPropertyInitialization spine migration (TS2564
 * strict-property-initialization). Pins the per-class emitter's gates and the
 * legacy statement/expression walker's reach BOTH directions — including the
 * legacy walk's MULTIPLICITY quirk: the ClassDeclaration statement arm walks
 * method/constructor/accessor bodies TWICE (once via checkClassPropertyInit's
 * own nested recursion, once via the arm's member loop), so a nested class
 * inside such a body has its properties checked (and its diagnostics emitted)
 * twice per enclosing level — while ClassExpression member bodies, static
 * block bodies, and property initializers are walked exactly once. All
 * expectations verified green against the pre-migration legacy pass first.
 */
class M04PropertyInitSpineMigrationTest {

    // ── emitter gates ──────────────────────────────────────────────────────

    @Test
    fun `uninitialized annotated property fires TS2564`() {
        diagnose("class C { p: number; }") should {
            have(any { it.code == 2564 &&
                it.message == "Property 'p' has no initializer and is not definitely assigned in the constructor." })
        }
    }

    @Test
    fun `negative control - initialized property is clean`() {
        diagnose("class C { p: number = 1; }") should {
            have(none { it.code == 2564 })
        }
    }

    @Test
    fun `negative control - optional and definite-assigned properties are clean`() {
        diagnose("class C { a?: number; b!: number; }") should {
            have(none { it.code == 2564 })
        }
    }

    @Test
    fun `negative control - declare static abstract and untyped properties are clean`() {
        diagnose("""
            abstract class C {
                declare d: number;
                static s: number;
                abstract a: number;
                untyped;
                anyTyped: any;
            }
        """.trimIndent()) should {
            have(none { it.code == 2564 })
        }
    }

    @Test
    fun `negative control - type including undefined is clean`() {
        diagnose("class C { p: number | undefined; }") should {
            have(none { it.code == 2564 })
        }
    }

    @Test
    fun `constructor assignment suppresses TS2564`() {
        diagnose("""
            class C {
                p: number;
                constructor() { this.p = 1; }
            }
        """.trimIndent()) should {
            have(none { it.code == 2564 })
        }
    }

    @Test
    fun `constructor parameter property counts as assigned`() {
        diagnose("""
            class C {
                p: number;
                constructor(public p2: number) { this.p = p2; }
            }
        """.trimIndent()) should {
            have(none { it.code == 2564 })
        }
    }

    @Test
    fun `string literal property names are excluded but literal computed names fire`() {
        val ds = diagnose("""
            class C {
                "lit": number;
                ["comp"]: number;
            }
        """.trimIndent())
        assert(ds.count { it.code == 2564 } == 1)
        ds should {
            have(any { it.code == 2564 && it.message.contains("[\"comp\"]") })
        }
    }

    @Test
    fun `abstract class declaration still checks non-abstract properties`() {
        diagnose("abstract class C { p: number; }") should {
            have(any { it.code == 2564 })
        }
    }

    @Test
    fun `negative control - declare class is skipped`() {
        diagnose("declare class C { p: number; }") should {
            have(none { it.code == 2564 })
        }
    }

    @Test
    fun `negative control - explicit strict false suppresses the pass`() {
        diagnose("class C { p: number; }", directives = "// @strict: false") should {
            have(none { it.code == 2564 })
        }
    }

    @Test
    fun `negative control - explicit strictPropertyInitialization false suppresses the pass`() {
        diagnose(
            "class C { p: number; }",
            directives = "// @strict: true\n// @strictPropertyInitialization: false",
        ) should {
            have(none { it.code == 2564 })
        }
    }

    // ── reach: statement positions ─────────────────────────────────────────

    @Test
    fun `class in a namespace body fires`() {
        diagnose("namespace N { export class C { p: number; } }") should {
            have(any { it.code == 2564 })
        }
    }

    @Test
    fun `negative control - class in a declare namespace is skipped`() {
        diagnose("declare namespace N { class C { p: number; } }") should {
            have(none { it.code == 2564 })
        }
    }

    @Test
    fun `class in a function body fires once`() {
        val ds = diagnose("function f() { class C { p: number; } }")
        assert(ds.count { it.code == 2564 } == 1)
    }

    @Test
    fun `class in an if branch and a labeled loop body fires`() {
        val ds = diagnose("""
            if (true) { class A { p: number; } }
            outer: while (true) { class B { q: string; } }
        """.trimIndent())
        assert(ds.count { it.code == 2564 } == 2)
    }

    @Test
    fun `class in a switch case and a try block fires`() {
        val ds = diagnose("""
            switch (1) { case 1: { class A { p: number; } } }
            try { class B { q: string; } } catch (e) { class C { r: string; } }
        """.trimIndent())
        assert(ds.count { it.code == 2564 } == 3)
    }

    // ── reach: the multiplicity quirk ──────────────────────────────────────

    @Test
    fun `class nested in a method body is checked twice - the legacy double walk`() {
        val ds = diagnose("""
            class Outer {
                m() { class Inner { p: number; } }
            }
        """.trimIndent())
        assert(ds.count { it.code == 2564 } == 2)
    }

    @Test
    fun `class nested in a constructor body is checked twice`() {
        val ds = diagnose("""
            class Outer {
                constructor() { class Inner { p: number; } }
            }
        """.trimIndent())
        assert(ds.count { it.code == 2564 } == 2)
    }

    @Test
    fun `two levels of method-body nesting multiply to four`() {
        val ds = diagnose("""
            class Outer {
                m() {
                    class Mid {
                        n() { class Inner { p: number; } }
                    }
                }
            }
        """.trimIndent())
        assert(ds.count { it.code == 2564 } == 4)
    }

    @Test
    fun `class in a static block body is checked once`() {
        val ds = diagnose("""
            class Outer {
                static { class Inner { p: number; } }
            }
        """.trimIndent())
        assert(ds.count { it.code == 2564 } == 1)
    }

    @Test
    fun `class expression in a property initializer is checked once`() {
        val ds = diagnose("""
            class Outer {
                f = class Inner { p: number; };
            }
        """.trimIndent())
        assert(ds.count { it.code == 2564 } == 1)
    }

    @Test
    fun `class nested in a class-expression method body is checked once`() {
        val ds = diagnose("""
            const x = class Ce {
                m() { class Inner { p: number; } }
            };
        """.trimIndent())
        assert(ds.count { it.code == 2564 } == 1)
    }

    // ── reach: expression positions ────────────────────────────────────────

    @Test
    fun `class expression in a variable initializer fires`() {
        val ds = diagnose("const x = class C { p: number; };")
        assert(ds.count { it.code == 2564 } == 1)
    }

    // NOTE: checkPropertyInitInExpr's abstract-ClassExpression gate is
    // UNREACHABLE via parse — parseClassExpression never sets modifiers, and
    // `= abstract class C {}` error-recovers `abstract` as an identifier
    // expression with the class re-parsed as a STATEMENT-level
    // ClassDeclaration (which the abstract-CLASS-declaration rule checks).
    // These two pins record that recovery shape.
    @Test
    fun `abstract class expression recovery parses a statement class and fires`() {
        val ds = diagnose("const x = abstract class C { p: number; };")
        assert(ds.count { it.code == 2564 } == 1)
    }

    @Test
    fun `abstract class expression recovery keeps the method-body double walk`() {
        val ds = diagnose("""
            const x = abstract class Ce {
                m() { class Inner { p: number; } }
            };
        """.trimIndent())
        assert(ds.count { it.code == 2564 } == 2)
    }

    @Test
    fun `class expression reaches through assignment call new array and object literal`() {
        val ds = diagnose("""
            let a: any;
            a = class A { p: number; };
            f(class B { q: number; });
            new Date(class C { r: number; } as any);
            [class D { s: number; }];
            const o = { v: class E { t: number; } };
            declare function f(x: any): void;
        """.trimIndent())
        assert(ds.count { it.code == 2564 } == 5)
    }

    @Test
    fun `class expression in ternary arms and template span fires but condition is unreached`() {
        val ds = diagnose("""
            declare var c: boolean;
            const t = c ? class A { p: number; } : class B { q: number; };
            const u = (class Cond { x: number; } as any) ? 1 : 2;
            const s = `x${'$'}{class T { y: number; }}`;
        """.trimIndent())
        assert(ds.count { it.code == 2564 } == 3)
    }

    @Test
    fun `negative control - if condition and for-in left are unreached`() {
        diagnose("""
            if (class A { p: number; } as any) { }
            for (const k in (class B { q: number; } as any)) { }
        """.trimIndent()) should {
            // the for-in EXPRESSION is walked; the if CONDITION is not
            have(none { it.code == 2564 && it.message.contains("'p'") })
        }
    }

    @Test
    fun `for-of expression is reached`() {
        val ds = diagnose("for (const v of [class A { p: number; }]) { }")
        assert(ds.count { it.code == 2564 } == 1)
    }

    @Test
    fun `arrow body direct statements are reached but nested blocks are not`() {
        val ds = diagnose("""
            const f = () => { const c = class A { p: number; }; };
            const g = () => { if (true) { const c = class B { q: number; } } };
            const h = () => class R { r: number; };
        """.trimIndent())
        assert(ds.count { it.code == 2564 } == 2)
        ds should {
            have(none { it.code == 2564 && it.message.contains("'q'") })
        }
    }

    @Test
    fun `function expression body direct return is reached`() {
        val ds = diagnose("const f = function () { return class A { p: number; }; };")
        assert(ds.count { it.code == 2564 } == 1)
    }

    @Test
    fun `negative control - enum member initializers and method parameter defaults are unreached`() {
        diagnose("""
            enum E { A = (class X { p: number; } as any) }
            class C { m(a = class Y { q: number; }) { } }
        """.trimIndent()) should {
            have(none { it.code == 2564 })
        }
    }
}
