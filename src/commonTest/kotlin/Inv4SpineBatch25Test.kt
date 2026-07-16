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
 * INV.4(d) walker 5 (round 534): the SET-based definite-assignment pass
 * checkDefiniteAssignment (TS2454) migrated onto the check spine — the
 * per-file driver plus the two recursion walkers
 * (checkDefiniteAssignmentInNestedScopes / checkDefiniteAssignmentInExprContext)
 * are DELETED; the per-statement-LIST core (collect → closure-removal →
 * checkUses → mark → nestedLeak, in statement order) runs as spine FRAME
 * steps at core-list statement enters, and the recursion's reach becomes a
 * memoized multi-state ancestor classifier ([spineDaEdge] — the deleted
 * arms verbatim). The bounded per-statement leaf utilities
 * (collectUninitializedVars / checkUsesOfUninitialized / markAssignments /
 * collectLetUninitializedSoFar / collectAllAssignmentsAnywhere /
 * collectClosureAssignedNames) stay intact. The sibling flow-graph passes
 * (checkDefiniteAssignmentViaFlowGraph — which DEDUPS against this pass's
 * emitted positions — and checkTryCatchOnlyAssignedVarReads) move to right
 * after the spine dispatch, preserving the legacy set-pass-first order.
 *
 * All pins verified against the OLD walker (pre-migration checker) — a pure
 * reach-preserving migration. Reach quirks pinned as negative controls:
 * if-CONDITION function expressions, throw-expression function expressions,
 * class-DECLARATION property initializers, and for/param-default positions
 * are unreached; try-block statement lists get NO per-statement set-pass
 * step (the flow pass owns those bodies); the B78.2 leak is dropped at
 * every block-statement boundary (if/loop/switch/labeled descent, Block
 * fresh cores, ModuleBlocks) and carried only over fn-boundary +
 * expression-context edges.
 */
class Inv4SpineBatch25Test {

    // ── core behavior preserved ─────────────────────────────────────────────

    @Test
    fun `fn-body straight-line read before assignment fires TS2454`() {
        val d = diagnose("""
            function f() {
                let x: number;
                x.toString();
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
        d should { have(any { it.code == 2454 && it.message == "Variable 'x' is used before being assigned." }) }
    }

    @Test
    fun `file-level script straight-line read fires TS2454`() {
        val d = diagnose("""
            let x: number;
            x.toString();
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    @Test
    fun `negative control - assigned before use is silent`() {
        diagnose("""
            function f() {
                let x: number;
                x = 1;
                x.toString();
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - B78 file-level let read in function body draws no leak TS2454`() {
        diagnose("""
            let x: number;
            function f() { return x + 1; }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `fn-level let leaks into nested function body when never assigned`() {
        val d = diagnose("""
            function outer() {
                let x: number;
                function inner() { return x + 1; }
                return inner();
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    @Test
    fun `negative control - an assignment anywhere in the scope suppresses the leak`() {
        diagnose("""
            function outer() {
                let x: number;
                function inner() { return x + 1; }
                x = 1;
                return inner();
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - round-469 closure assignment suppresses even the straight-line read`() {
        diagnose("""
            function outer() {
                let x: number;
                function assignIt() { x = 1; }
                x.toString();
                assignIt();
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - a same-named parameter shadows the leaked outer name`() {
        diagnose("""
            function outer() {
                let x: number;
                function inner(x: number) { return x + 1; }
                return inner(1);
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - while-true definite assignment before break clears the var`() {
        diagnose("""
            function f() {
                let t: number;
                while (true) { t = 1; break; }
                t.toString();
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `compound assignment is a read-modify-write and fires TS2454`() {
        val d = diagnose("""
            function f() {
                let e: number;
                e |= 1;
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    @Test
    fun `negative control - non-null asserted bare identifier read is exempt`() {
        diagnose("""
            function f() {
                let x: number;
                return x!;
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    // ── suppression filters on the candidate set ────────────────────────────

    @Test
    fun `negative control - a type including undefined needs no definite assignment`() {
        diagnose("""
            function f() {
                let x: number | undefined;
                return x;
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - definite assignment assertion suppresses TS2454`() {
        diagnose("""
            function f() {
                let x!: number;
                x.toString();
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - any-typed var is never tracked`() {
        diagnose("""
            function f() {
                let x: any;
                return x + 1;
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - explicit strict false suppresses the whole pass`() {
        diagnose("""
            function f() {
                let x: number;
                x.toString();
            }
        """, directives = "// @strict: false") should {
            have(none { it.code == 2454 })
        }
    }

    // ── reach quirks: expression-context edges ──────────────────────────────

    @Test
    fun `negative control - an arrow inside an if CONDITION is unreached`() {
        diagnose("""
            function f() {
                let x: number;
                if ((() => x.toFixed())()) { }
                x = 1;
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - a function expression inside a throw expression is unreached`() {
        diagnose("""
            function f() {
                let x: string;
                throw (() => x.length)();
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `a variable-statement initializer IIFE body is reached with the leak`() {
        val d = diagnose("""
            function f() {
                let x: number;
                const y = (() => { return x + 1; })();
                return y;
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    @Test
    fun `a return-expression IIFE body is reached with the leak`() {
        val d = diagnose("""
            function f() {
                let x: number;
                return (() => { return x + 1; })();
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    @Test
    fun `an arrow EXPRESSION body passes the leak through to a nested block-bodied arrow`() {
        val d = diagnose("""
            function f() {
                let x: number;
                const g = () => () => { return x + 1; };
                return g;
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    @Test
    fun `an object-literal method body is reached with the leak`() {
        val d = diagnose("""
            function f() {
                let x: number;
                const o = { m() { return x + 1; } };
                return o;
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    // ── reach quirks: class declarations vs class expressions ───────────────

    @Test
    fun `a class-expression method body is reached with the leak`() {
        val d = diagnose("""
            function f() {
                let x: number;
                const C = class { m() { return x + 1; } };
                return C;
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    @Test
    fun `a class-expression property initializer arrow is reached with the leak`() {
        val d = diagnose("""
            function f() {
                let x: number;
                const C = class { p = () => { return x + 1; }; };
                return C;
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    @Test
    fun `negative control - a class-DECLARATION property initializer arrow is unreached`() {
        diagnose("""
            function f() {
                let x: number;
                class C { p = () => { return x + 1; }; }
                return C;
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `a class-declaration method body is reached with the leak`() {
        val d = diagnose("""
            function f() {
                let x: number;
                class C { m() { return x + 1; } }
                return C;
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    @Test
    fun `negative control - a statement-order-late let is not in an earlier class expression leak`() {
        diagnose("""
            function f() {
                const C = class { m() { return x + 1; } };
                let x: number;
                x = 1;
                return C;
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    // ── reach quirks: block/leak-drop boundaries ────────────────────────────

    @Test
    fun `a nested block runs a fresh core over its own declarations`() {
        val d = diagnose("""
            function f() {
                let x: number;
                {
                    let y: string;
                    y.length;
                }
                x = 1;
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
        d should { have(any { it.code == 2454 && it.message == "Variable 'y' is used before being assigned." }) }
    }

    @Test
    fun `a read inside a nested block still fires against the outer scope set`() {
        val d = diagnose("""
            function f() {
                let x: number;
                {
                    x.toString();
                }
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    @Test
    fun `negative control - the leak is dropped at an if-body function declaration`() {
        diagnose("""
            function f(c: boolean) {
                let x: number;
                if (c) {
                    function g() { return x + 1; }
                    g();
                }
                x = 1;
                return x;
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - the leak is dropped at a switch-clause function declaration`() {
        diagnose("""
            function f(k: number) {
                let x: number;
                switch (k) {
                    case 1:
                        function g() { return x + 1; }
                        g();
                }
                x = 1;
                return x;
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    // ── flow-pass companions (order-coupling guards) ─────────────────────────

    @Test
    fun `a read inside an if body fires via the flow-graph companion pass`() {
        val d = diagnose("""
            function f(c: boolean) {
                let a: number;
                if (c) {
                    a.toFixed();
                }
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    @Test
    fun `a read inside a try body fires exactly once across both passes`() {
        val d = diagnose("""
            function f() {
                try {
                    let x: number;
                    x.toString();
                } finally { }
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    @Test
    fun `a straight-line read fires exactly once - the flow pass dedups against the set pass`() {
        val d = diagnose("""
            function f() {
                let x: number;
                const y = x + 1;
                return y;
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    // ── namespaces / ambient ────────────────────────────────────────────────

    @Test
    fun `a namespace body runs its own core`() {
        val d = diagnose("""
            namespace N {
                let x: number;
                x.toString();
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    @Test
    fun `a dotted namespace body runs its own core`() {
        val d = diagnose("""
            namespace A.B {
                let x: number;
                x.toString();
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }

    @Test
    fun `negative control - a declare function body is absent so nothing fires`() {
        diagnose("""
            declare function f(x: number): number;
            let x: number;
            x = f(1);
        """) should {
            have(none { it.code == 2454 })
        }
    }

    // ── ordering-sensitive statement shapes ─────────────────────────────────

    @Test
    fun `comma operator assigns left before right reads it`() {
        diagnose("""
            function f() {
                let t0: number;
                let t1: number;
                t1 = (t0 = 1, t0);
                return t1;
            }
        """) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `an assignment RHS read of another uninitialized var fires`() {
        val d = diagnose("""
            function f() {
                let a: number;
                let b: number;
                a = b;
                return a;
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
        d should { have(any { it.code == 2454 && it.message == "Variable 'b' is used before being assigned." }) }
    }

    @Test
    fun `class heritage expression reads fire at the class statement`() {
        val d = diagnose("""
            function f() {
                let base: new () => object;
                class C extends base { }
                return C;
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2454 }, "expected 1 TS2454, got: $d")
    }
}
