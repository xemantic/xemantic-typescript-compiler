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
 * INV.4(d) walker 6 (round 535): the function-call arity pass
 * checkArgumentCounts (TS2554/TS2555/TS2575) migrated onto the check spine —
 * the per-file driver plus the recursion walkers (checkArgCountInStatements /
 * checkArgCountInStatement / checkArgCountInExpr(Core)) are DELETED; reach
 * becomes a memoized DEPTH classifier (the legacy argCountDepth recursion
 * counter reproduced per node, with the ≤200 cap), and the downward map
 * context (funcParams / classCtorParams / argCountFnDepth / argCountSuperCtor)
 * is rebuilt PULL-based at each emission from the ancestor chain with
 * per-list-owner memoized levels (the maps are pure functions of the chain —
 * every list overlay reads its WHOLE statement list, never a position-ordered
 * prefix). The bounded leaf utilities (collectFuncDecls / paramInfo /
 * minusParamShadowedNames / resolveInheritedCtorArities / emitTS2554* /
 * emitTS2555TooFew) stay intact. The producer sibling
 * checkSpreadNonIterableIntoFixedArity moves BEFORE the spine (its
 * spreadNonIterableHandledCalls set is consulted by the TS2554 too-many arm).
 *
 * All pins verified against the OLD walker (pre-migration checker) — a pure
 * reach-preserving migration. Reach quirks pinned as negative controls:
 * `new` callee expressions, switch CASE expressions, parameter defaults,
 * class heritage arguments, enum member initializers, object-literal
 * accessor bodies, shorthand destructuring defaults, computed property names
 * are all unreached; the legacy depth-200 recursion cap prunes deeply
 * parenthesized subtrees. Shadowing quirks pinned: params / destructured
 * params / fn-expr own names / body-locals (fnDepth-gated) / for-of loop
 * vars shadow outer functions; class-EXPRESSION member bodies get NEITHER
 * the param shadow NOR the fnDepth increment (the legacy ClassExpression arm
 * passed funcParams through untouched), and a class-expression constructor
 * never rebinds argCountSuperCtor.
 */
class Inv4SpineBatch26Test {

    // ── core emission contexts ──────────────────────────────────────────────

    @Test
    fun `top-level call with too few arguments fires TS2554`() {
        val d = diagnose("""
            function f(a: number, b: number) {}
            f(1);
        """)
        assert(d.count { it.code == 2554 } == 1)
        d should { have(any { it.code == 2554 && it.message == "Expected 2 arguments, but got 1." }) }
    }

    @Test
    fun `top-level call with too many arguments fires TS2554`() {
        val d = diagnose("""
            function f(a: number) {}
            f(1, 2, 3);
        """)
        assert(d.count { it.code == 2554 } == 1)
        d should { have(any { it.code == 2554 && it.message == "Expected 1 arguments, but got 3." }) }
    }

    @Test
    fun `rest-param call below the required minimum fires TS2555`() {
        val d = diagnose("""
            function f(a: number, ...rest: number[]) {}
            f();
        """)
        assert(d.count { it.code == 2555 } == 1)
    }

    @Test
    fun `overload arity gap fires TS2575`() {
        val d = diagnose("""
            function f(a: number): void;
            function f(a: number, b: number, c: number): void;
            function f(a: number, b?: number, c?: number): void {}
            f(1, 2);
        """)
        assert(d.count { it.code == 2575 } == 1)
        d should { have(any { it.code == 2575 && it.message.contains("either 1 or 3") }) }
    }

    @Test
    fun `call outside every overload arity range fires TS2554`() {
        val d = diagnose("""
            function f(a: number): void;
            function f(a: number, b: number): void;
            function f(a: number, b?: number): void {}
            f(1, 2, 3, 4);
        """)
        assert(d.count { it.code == 2554 } == 1)
    }

    @Test
    fun `new expression against constructor arity fires TS2554 both directions`() {
        val d = diagnose("""
            class C {
                constructor(a: number, b: number) {}
            }
            const x = new C(1);
            const y = new C(1, 2, 3);
        """)
        assert(d.count { it.code == 2554 } == 2)
    }

    @Test
    fun `inherited constructor arity via forward-referenced base fires TS2554`() {
        val d = diagnose("""
            class D extends B {}
            class B {
                constructor(a: number) {}
            }
            const x = new D();
        """)
        assert(d.count { it.code == 2554 } == 1)
    }

    @Test
    fun `super call arity in a class-declaration constructor fires TS2554`() {
        val d = diagnose("""
            class B {
                constructor(a: number, b: number) {}
            }
            class D extends B {
                constructor() {
                    super(1);
                }
            }
        """)
        assert(d.count { it.code == 2554 } == 1)
    }

    @Test
    fun `negative control - class-EXPRESSION constructor super is never arity-checked`() {
        diagnose("""
            class B {
                constructor(a: number, b: number) {}
            }
            const c = class extends B {
                constructor() {
                    super(1);
                }
            };
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `namespace-internal function call inside the namespace body is checked`() {
        val d = diagnose("""
            namespace N {
                function g(a: number) {}
                g(1, 2);
            }
        """)
        assert(d.count { it.code == 2554 } == 1)
    }

    @Test
    fun `dotted namespace body calls are checked`() {
        val d = diagnose("""
            namespace A.B {
                function g(a: number) {}
                g(1, 2);
            }
        """)
        assert(d.count { it.code == 2554 } == 1)
    }

    @Test
    fun `negative control - namespace-internal function is invisible at file level`() {
        diagnose("""
            namespace N {
                export function g(a: number) {}
            }
            g(1, 2);
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `const arrow initializer arity is checked - B64_2`() {
        val d = diagnose("""
            const f = (a: number, b: number) => a + b;
            f(1);
        """)
        assert(d.count { it.code == 2554 } == 1)
    }

    @Test
    fun `calls in nested expression positions are checked`() {
        val d = diagnose("""
            function f(a: number) {}
            declare const cond: boolean;
            if (cond) { f(1, 2); }
            while (f(1, 2) as any) { break; }
            const arr = [f(1, 2)];
            const obj = { p: f(1, 2) };
            const t = `x${'$'}{f(1, 2)}y`;
        """)
        assert(d.count { it.code == 2554 } == 5)
    }

    @Test
    fun `arrow expression body and function-expression body calls are checked`() {
        val d = diagnose("""
            function f(a: number) {}
            const g = () => f(1, 2);
            const h = function () { f(1, 2); };
        """)
        assert(d.count { it.code == 2554 } == 2)
    }

    @Test
    fun `class member bodies and property initializers are checked`() {
        val d = diagnose("""
            function f(a: number) {}
            class C {
                p = f(1, 2);
                m() { f(1, 2); }
                get a() { f(1, 2); return 1; }
            }
        """)
        assert(d.count { it.code == 2554 } == 3)
    }

    @Test
    fun `class-expression member bodies and property initializers are checked`() {
        val d = diagnose("""
            function f(a: number) {}
            const c = class {
                p = f(1, 2);
                m() { f(1, 2); }
            };
        """)
        assert(d.count { it.code == 2554 } == 2)
    }

    @Test
    fun `tagged template substitution count is arity-checked`() {
        val d = diagnose("""
            function tag(strings: any) {}
            tag`a${'$'}{1}b`;
        """)
        assert(d.count { it.code == 2554 } == 1)
        d should { have(any { it.code == 2554 && it.message == "Expected 1 arguments, but got 2." }) }
    }

    @Test
    fun `explicit type arguments filter the overload set for arity`() {
        val d = diagnose("""
            function foo<T>(a: T, b: T): void;
            function foo(a: string): void;
            function foo(a: any, b?: any): void {}
            foo<number>(1);
        """)
        assert(d.count { it.code == 2554 } == 1)
        d should { have(any { it.code == 2554 && it.message == "Expected 2 arguments, but got 1." }) }
    }

    // ── spread-argument rules ───────────────────────────────────────────────

    @Test
    fun `negative control - a spread argument suppresses too-few`() {
        diagnose("""
            function f(a: number, b: number) {}
            declare const xs: number[];
            f(...xs);
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `too-many stands with a trailing spread argument`() {
        val d = diagnose("""
            function f(a: number) {}
            declare const xs: number[];
            f(1, 2, ...xs);
        """)
        assert(d.count { it.code == 2554 } == 1)
    }

    @Test
    fun `negative control - new with a spread argument suppresses too-few`() {
        diagnose("""
            class C {
                constructor(a: number, b: number) {}
            }
            declare const xs: number[];
            const x = new C(...xs);
        """) should {
            have(none { it.code == 2554 })
        }
    }

    // ── reach quirks (negative controls, legacy walker geometry) ────────────

    @Test
    fun `negative control - a new-expression CALLEE expression is unreached`() {
        diagnose("""
            function getCtor(a: number): any { return null; }
            const x = new (getCtor())();
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `negative control - switch CASE expressions are unreached`() {
        val d = diagnose("""
            function f(a: number): number { return a; }
            declare const x: number;
            switch (x) {
                case f(): break;
            }
        """)
        d should { have(none { it.code == 2554 }) }
    }

    @Test
    fun `switch SUBJECT expression and clause statements are reached`() {
        val d = diagnose("""
            function f(a: number): number { return a; }
            switch (f(1, 2)) {
                default:
                    f(1, 2);
            }
        """)
        assert(d.count { it.code == 2554 } == 2)
    }

    @Test
    fun `negative control - parameter default initializers are unreached`() {
        diagnose("""
            function f(a: number): number { return a; }
            function g(x = f()) {}
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `negative control - class heritage arguments are unreached`() {
        diagnose("""
            function mix(a: number): any { return class {}; }
            class C extends mix() {}
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `negative control - enum member initializers are unreached`() {
        diagnose("""
            function f(a: number): number { return a; }
            enum E { A = f() }
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `negative control - object-literal accessor bodies are unreached`() {
        diagnose("""
            function f(a: number) {}
            const o = {
                get a() { f(); return 1; },
                set b(v: number) { f(); },
            };
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `negative control - shorthand destructuring defaults are unreached`() {
        diagnose("""
            function f(a: number): number { return a; }
            declare const obj: any;
            let x: any;
            ({ x = f() } = obj);
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `negative control - computed property names are unreached`() {
        diagnose("""
            function f(a: number): string { return "k"; }
            const o = { [f()]: 1 };
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `legacy depth-200 recursion cap prunes deeply parenthesized calls`() {
        val deep = "(".repeat(205) + "f()" + ")".repeat(205)
        diagnose("""
            function f(a: number): number { return a; }
            const x = $deep;
        """) should {
            have(none { it.code == 2554 })
        }
        val shallow = "(".repeat(50) + "f()" + ")".repeat(50)
        val d = diagnose("""
            function f(a: number): number { return a; }
            const x = $shallow;
        """)
        assert(d.count { it.code == 2554 } == 1)
    }

    @Test
    fun `binary right-spine operands stay below the depth cap`() {
        // A right-leaning `2 ** 2 ** … ** f()` chain (right-associative, so
        // the parser nests to the RIGHT) is absorbed by the legacy
        // right-spine loop — the call at the leaf stays checked at ANY chain
        // length. (Left-nested chains consume depth; right-nested don't.)
        val chain = "2 ** ".repeat(300) + "f()"
        val d = diagnose("""
            function f(a: number): number { return a; }
            const x = $chain;
        """)
        assert(d.count { it.code == 2554 } == 1)
    }

    // ── lexical shadowing (M1.11 + B64.2 + 17.126) ──────────────────────────

    @Test
    fun `negative control - a parameter shadows a same-named outer function`() {
        diagnose("""
            function f(a: number, b: number) {}
            function g(f: any) {
                f();
            }
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `negative control - a destructured parameter shadows a same-named outer function`() {
        diagnose("""
            function f(a: number, b: number) {}
            function g({ f }: any) {
                f();
            }
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `negative control - a function expression's own name shadows the outer function`() {
        diagnose("""
            function f(a: number, b: number) {}
            const v = function f() {
                f(1, 2, 3);
            };
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `negative control - a body-local var shadows an outer function inside a function body`() {
        diagnose("""
            function f(a: number, b: number) {}
            function g() {
                var f: any;
                f();
            }
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `negative control - for-of loop binding names shadow outer functions in the body`() {
        diagnose("""
            function parse(a: number, b: number) {}
            function g(xs: any) {
                for (const { parse } of xs) {
                    parse();
                }
            }
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `a block-level function declaration overrides the outer arity in its list`() {
        val d = diagnose("""
            function f(a: number, b: number) {}
            {
                function f() {}
                f(1);
            }
        """)
        assert(d.count { it.code == 2554 } == 1)
        d should { have(any { it.code == 2554 && it.message == "Expected 0 arguments, but got 1." }) }
    }

    @Test
    fun `class-DECLARATION method parameters shadow outer functions`() {
        diagnose("""
            function f(a: number, b: number) {}
            class C {
                m(f: any) {
                    f();
                }
            }
        """) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `class-EXPRESSION method parameters do NOT shadow outer functions`() {
        // The legacy ClassExpression arm walks member bodies with funcParams
        // UNTOUCHED (no minusParamShadowedNames, no argCountFnDepth++) —
        // bug-compat asymmetry vs the ClassDeclaration arm.
        val d = diagnose("""
            function f(a: number, b: number) {}
            const c = class {
                m(f: any) {
                    f();
                }
            };
        """)
        assert(d.count { it.code == 2554 } == 1)
    }

    @Test
    fun `class-EXPRESSION member body locals do NOT shadow at top level`() {
        // Same asymmetry: no argCountFnDepth++ for class-expression member
        // bodies, so the fnDepth-gated var-shadow removal never fires when
        // the class expression sits at the top level.
        val d = diagnose("""
            function f(a: number, b: number) {}
            const c = class {
                m() {
                    var f: any;
                    f();
                }
            };
        """)
        assert(d.count { it.code == 2554 } == 1)
    }

    @Test
    fun `top-level var redeclaration does not suppress the arity check`() {
        // The fnDepth-gated var-shadow removal is nested-lists-only — a
        // TOP-level `var f` alongside `function f` keeps the check alive.
        val d = diagnose("""
            function f(a: number, b: number) {}
            var f: any;
            f(1);
        """)
        d should { have(any { it.code == 2554 }) }
    }

    // ── cross-file overlay (B434, script-mode) ──────────────────────────────

    @Test
    fun `cross-file script-mode call is arity-checked against the declaring file`() {
        val d = diagnose(
            """
            // @filename: a.ts
            function crossF(a: number, b: number) {}
            // @filename: b.ts
            crossF(1);
            """,
        )
        assert(d.count { it.code == 2554 } == 1)
    }

    @Test
    fun `negative control - a local binding shadows the cross-file function`() {
        diagnose(
            """
            // @filename: a.ts
            function crossF(a: number, b: number) {}
            // @filename: b.ts
            var crossF: any;
            crossF(1);
            """,
        ) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `negative control - cross-file duplicate function names are skipped`() {
        diagnose(
            """
            // @filename: a.ts
            function crossF(a: number, b: number) {}
            // @filename: b.ts
            function crossF(a: number) {}
            // @filename: c.ts
            crossF(9, 9, 9);
            """,
        ) should {
            have(none { it.code == 2554 })
        }
    }
}
