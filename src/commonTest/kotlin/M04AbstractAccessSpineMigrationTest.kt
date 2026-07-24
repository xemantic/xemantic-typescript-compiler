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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * (M0.4, round 653): pins for the checkAbstractMemberAccessInConstructor
 * (TS2715 — a `this.X` reference inside a constructor body or a class-field
 * initializer where X is an abstract member of the surrounding class, own or
 * inherited) spine migration. TS2715 is UNIQUE to this pass (the only `2715`
 * in Checker.kt), so its count is a clean reach signal.
 *
 * The legacy pass reached classes via a ROUTING recursion
 * (walkClassesForAbstractAccess / walkExprForNestedClasses) over a FILE-scoped
 * classMap prepass, and ran the EMISSION recursion (findAbstractAccessIn*,
 * threading a downward `inDeferredFn` flag) per reached class. The migration
 * anchors the emission half at ClassDeclaration / ClassExpression enters under
 * a memoized reach classifier reproducing the routing arms; the routing
 * walkers SURVIVE for the one path that is not driven from the file root — the
 * emission walker's own ClassExpression arm, which processes a nested class
 * inline (and is what makes the double-processing pinned below legacy
 * behaviour).
 *
 * Frozen quirks pinned in BOTH directions: statement bodies (fn/namespace/
 * block/if-then/loop-body/switch-clause/try/catch/finally/labeled) are
 * reached but if/loop/switch HEADS are not; an arrow / function-expression
 * body is the RESTRICTED three-statement-kind walk (Expression/Return/
 * Variable only — a class DECLARATION or an `if`-nested class there is
 * NEVER reached, unlike round 651's Ab fold) and its variable initializers
 * go through the plain expression arm, so the VariableStatement NAME OVERRIDE
 * (an anonymous `const C = class {…}` taking the variable's name) applies at
 * statement level only. All expectations were verified against the LEGACY
 * pass first.
 */
class M04AbstractAccessSpineMigrationTest {

    private fun ts2715(ds: List<Diagnostic>) = ds.count { it.code == 2715 }

    // ── Core emissions ────────────────────────────────────────────────────

    @Test
    fun `own abstract property read in the constructor draws TS2715`() {
        val ds = diagnose(
            """
            abstract class C {
              abstract p: number;
              constructor() { this.p; }
            }
            """
        )
        assertEquals(1, ts2715(ds))
        val d = ds.single { it.code == 2715 }
        assertEquals("p".length, d.length)
        assertTrue(d.message.contains("Abstract property 'p' in class 'C'"))
    }

    @Test
    fun `own abstract property read in a field initializer draws TS2715`() {
        val ds = diagnose(
            """
            abstract class C {
              abstract p: number;
              q = this.p;
            }
            """
        )
        assertEquals(1, ts2715(ds))
    }

    @Test
    fun `inherited abstract property names the DECLARING class`() {
        val ds = diagnose(
            """
            abstract class B { abstract p: number; }
            abstract class C extends B {
              constructor() { super(); this.p; }
            }
            """
        )
        assertEquals(1, ts2715(ds))
        assertTrue(ds.single { it.code == 2715 }.message.contains("in class 'B'"))
    }

    @Test
    fun `a concrete override removes the inherited abstract name`() {
        val ds = diagnose(
            """
            abstract class B { abstract p: number; }
            class C extends B {
              p = 1;
              constructor() { super(); this.p; }
            }
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `inheritance chains two levels deep`() {
        val ds = diagnose(
            """
            abstract class A { abstract p: number; }
            abstract class B extends A { }
            abstract class C extends B {
              constructor() { super(); this.p; }
            }
            """
        )
        assertEquals(1, ts2715(ds))
        assertTrue(ds.single { it.code == 2715 }.message.contains("in class 'A'"))
    }

    @Test
    fun `a chained access this-p-q still fires on the receiver`() {
        val ds = diagnose(
            """
            abstract class C {
              abstract p: { q: number };
              constructor() { this.p.q; }
            }
            """
        )
        assertEquals(1, ts2715(ds))
    }

    @Test
    fun `destructuring declaration from this fires per abstract key`() {
        val ds = diagnose(
            """
            abstract class C {
              abstract p: number;
              abstract r: number;
              constructor() { const { p, r } = this; }
            }
            """
        )
        assertEquals(2, ts2715(ds))
    }

    @Test
    fun `destructuring assignment from this fires per abstract key`() {
        val ds = diagnose(
            """
            abstract class C {
              abstract p: number;
              constructor() { let p: number; ({ p } = this); }
            }
            """
        )
        assertEquals(1, ts2715(ds))
    }

    // ── Deferred-`this` boundaries (the inDeferredFn flag) ────────────────

    @Test
    fun `negative control - an arrow body in the constructor is deferred`() {
        val ds = diagnose(
            """
            abstract class C {
              abstract p: number;
              constructor() { const f = () => this.p; }
            }
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `negative control - a function expression in the constructor is deferred`() {
        val ds = diagnose(
            """
            abstract class C {
              abstract p: number;
              constructor() { const f = function () { this.p; }; }
            }
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `negative control - an arrow field initializer is deferred`() {
        val ds = diagnose(
            """
            abstract class C {
              abstract p: number;
              q = () => this.p;
            }
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `negative control - an object-literal method body in the constructor is deferred`() {
        val ds = diagnose(
            """
            abstract class C {
              abstract p: number;
              constructor() { const o = { m() { this.p; } }; }
            }
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `an object-literal property VALUE in the constructor is NOT deferred`() {
        val ds = diagnose(
            """
            abstract class C {
              abstract p: number;
              constructor() { const o = { v: this.p }; }
            }
            """
        )
        assertEquals(1, ts2715(ds))
    }

    // ── Member-kind gates on the emission half ────────────────────────────

    @Test
    fun `negative control - a method body is not the constructor`() {
        val ds = diagnose(
            """
            abstract class C {
              abstract p: number;
              m() { this.p; }
            }
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `negative control - a STATIC field initializer is skipped`() {
        val ds = diagnose(
            """
            abstract class C {
              abstract p: number;
              static q = this.p;
            }
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `a nested statement inside the constructor body is walked`() {
        val ds = diagnose(
            """
            abstract class C {
              abstract p: number;
              constructor() {
                if (1) { this.p; }
                while (1) { this.p; }
                switch (1) { case 1: this.p; }
              }
            }
            """
        )
        assertEquals(3, ts2715(ds))
    }

    @Test
    fun `negative control - a TRY body inside the constructor is NOT walked`() {
        // FROZEN: the EMISSION walk (findAbstractAccessInStmt) has no
        // TryStatement arm — unlike the ROUTING walk, which descends
        // try/catch/finally.
        val ds = diagnose(
            """
            abstract class C {
              abstract p: number;
              constructor() {
                try { this.p; } catch (e) { this.p; } finally { this.p; }
              }
            }
            """
        )
        assertEquals(0, ts2715(ds))
    }

    // ── Routing reach: statement positions ────────────────────────────────

    @Test
    fun `a class inside a function body is reached`() {
        val ds = diagnose(
            """
            function f() {
              abstract class C {
                abstract p: number;
                constructor() { this.p; }
              }
            }
            """
        )
        assertEquals(1, ts2715(ds))
    }

    @Test
    fun `a class inside a namespace body is reached`() {
        val ds = diagnose(
            """
            namespace N {
              abstract class C {
                abstract p: number;
                constructor() { this.p; }
              }
            }
            """
        )
        assertEquals(1, ts2715(ds))
    }

    @Test
    fun `a class inside an if-then block is reached`() {
        val ds = diagnose(
            """
            if (1) {
              abstract class C {
                abstract p: number;
                constructor() { this.p; }
              }
            }
            """
        )
        assertEquals(1, ts2715(ds))
    }

    @Test
    fun `a class inside a loop body, switch clause, try, catch and finally is reached`() {
        val ds = diagnose(
            """
            for (;;) {
              abstract class A { abstract p: number; constructor() { this.p; } }
            }
            switch (1) {
              case 1:
                abstract class B { abstract p: number; constructor() { this.p; } }
            }
            try {
              abstract class D { abstract p: number; constructor() { this.p; } }
            } catch (e) {
              abstract class E { abstract p: number; constructor() { this.p; } }
            } finally {
              abstract class F { abstract p: number; constructor() { this.p; } }
            }
            lab: {
              abstract class G { abstract p: number; constructor() { this.p; } }
            }
            """
        )
        assertEquals(6, ts2715(ds))
    }

    @Test
    fun `a class inside a member body is reached (the step-a member descent)`() {
        val ds = diagnose(
            """
            class Outer {
              m() {
                abstract class C {
                  abstract p: number;
                  constructor() { this.p; }
                }
              }
            }
            """
        )
        assertEquals(1, ts2715(ds))
    }

    @Test
    fun `negative control - a class inside a class-DECLARATION property initializer is NOT reached`() {
        // step (a) descends member BODIES only — never property initializers.
        val ds = diagnose(
            """
            class Outer {
              f = (class D { abstract p: number; constructor() { this.p; } });
            }
            """
        )
        assertEquals(0, ts2715(ds))
    }

    // ── Routing reach: expression positions ───────────────────────────────

    @Test
    fun `a NAMED class expression in a call argument is reached`() {
        val ds = diagnose(
            """
            declare function f(x: any): void;
            f(class D { abstract p: number; constructor() { this.p; } });
            """
        )
        assertEquals(1, ts2715(ds))
        assertTrue(ds.single { it.code == 2715 }.message.contains("in class 'D'"))
    }

    @Test
    fun `negative control - an ANONYMOUS class expression in a call argument draws nothing`() {
        // className == null → the emission half returns before walking.
        val ds = diagnose(
            """
            declare function f(x: any): void;
            f(class { abstract p: number; constructor() { this.p; } });
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `an anonymous class expression in a var initializer takes the VARIABLE name`() {
        val ds = diagnose(
            """
            const C = class { abstract p: number; constructor() { this.p; } };
            """
        )
        assertEquals(1, ts2715(ds))
        assertTrue(ds.single { it.code == 2715 }.message.contains("in class 'C'"))
    }

    @Test
    fun `a named class expression in a var initializer keeps its OWN name`() {
        val ds = diagnose(
            """
            const C = class D { abstract p: number; constructor() { this.p; } };
            """
        )
        assertEquals(1, ts2715(ds))
        assertTrue(ds.single { it.code == 2715 }.message.contains("in class 'D'"))
    }

    @Test
    fun `negative control - a PARENTHESIZED anonymous class initializer gets no name override`() {
        // the legacy VariableStatement arm tests `init is ClassExpression`
        // WITHOUT unwrapping parens, so this routes through the plain
        // expression arm → className == null → nothing.
        val ds = diagnose(
            """
            const C = (class { abstract p: number; constructor() { this.p; } });
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `a class expression in a return, throw, ternary and array literal is reached`() {
        val ds = diagnose(
            """
            declare const cond: boolean;
            function f1() { return class A { abstract p: number; constructor() { this.p; } }; }
            function f2() { throw class B { abstract p: number; constructor() { this.p; } }; }
            const t = cond ? class D { abstract p: number; constructor() { this.p; } } : 0;
            const arr = [class E { abstract p: number; constructor() { this.p; } }];
            """
        )
        assertEquals(4, ts2715(ds))
    }

    @Test
    fun `negative control - an if CONDITION class expression is not reached`() {
        val ds = diagnose(
            """
            if (class D { abstract p: number; constructor() { this.p; } }) { }
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `negative control - a for-HEAD class expression is not reached`() {
        val ds = diagnose(
            """
            for (let i: any = class D { abstract p: number; constructor() { this.p; } }; ; ) { }
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `the switch SUBJECT IS a routing position`() {
        // FROZEN: the routing walk's SwitchStatement arm walks the subject
        // expression as well as the clause statements (an if/loop HEAD is
        // NOT walked — pinned above).
        val ds = diagnose(
            """
            switch (class D { abstract p: number; constructor() { this.p; } }) { }
            """
        )
        assertEquals(1, ts2715(ds))
    }

    @Test
    fun `negative control - an object-literal METHOD body is not a routing position`() {
        val ds = diagnose(
            """
            const o = { m() { return class D { abstract p: number; constructor() { this.p; } }; } };
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `an object-literal property VALUE is a routing position`() {
        val ds = diagnose(
            """
            const o = { v: class D { abstract p: number; constructor() { this.p; } } };
            """
        )
        assertEquals(1, ts2715(ds))
    }

    // ── The RESTRICTED arrow / function-expression body walk ──────────────

    @Test
    fun `negative control - a class DECLARATION in an arrow body is never reached`() {
        // the restricted walk handles Expression/Return/Variable statements
        // only — unlike round 651's Ab fold, which walks arrow bodies fully.
        val ds = diagnose(
            """
            const f = () => {
              abstract class C { abstract p: number; constructor() { this.p; } }
            };
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `a named class expression in an arrow body variable initializer IS reached`() {
        val ds = diagnose(
            """
            const f = () => {
              const K = class D { abstract p: number; constructor() { this.p; } };
            };
            """
        )
        assertEquals(1, ts2715(ds))
        assertTrue(ds.single { it.code == 2715 }.message.contains("in class 'D'"))
    }

    @Test
    fun `negative control - the arrow-body variable initializer gets NO name override`() {
        // the restricted walk routes initializers through the plain
        // expression arm, so an anonymous class stays nameless → silent.
        val ds = diagnose(
            """
            const f = () => {
              const K = class { abstract p: number; constructor() { this.p; } };
            };
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `negative control - an if-nested class expression in an arrow body is not reached`() {
        val ds = diagnose(
            """
            const f = () => {
              if (1) { const K = class D { abstract p: number; constructor() { this.p; } }; }
            };
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `a class expression in a CONCISE arrow body is reached`() {
        val ds = diagnose(
            """
            const f = () => class D { abstract p: number; constructor() { this.p; } };
            """
        )
        assertEquals(1, ts2715(ds))
    }

    @Test
    fun `a class expression in a function-expression body return IS reached`() {
        val ds = diagnose(
            """
            const f = function () {
              return class D { abstract p: number; constructor() { this.p; } };
            };
            """
        )
        assertEquals(1, ts2715(ds))
    }

    // ── Nesting: the outer map never leaks, and the legacy DOUBLE reach ───

    @Test
    fun `negative control - the outer abstract map does not leak into a nested class`() {
        val ds = diagnose(
            """
            abstract class C {
              abstract p: number;
              constructor() {
                const K = class D { constructor() { this.p; } };
              }
            }
            """
        )
        assertEquals(0, ts2715(ds))
    }

    @Test
    fun `a nested class expression inside a processed constructor is processed TWICE`() {
        // FROZEN legacy multiplicity: such a class is reached BOTH by the
        // routing walk (step (a) descends the constructor body) AND by the
        // emission walk's own ClassExpression arm, so its own TS2715 is
        // emitted twice. The migration keeps the emission walker's inline
        // processing verbatim, so the count is preserved.
        val ds = diagnose(
            """
            abstract class C {
              abstract p: number;
              constructor() {
                const K = class D { abstract q: number; constructor() { this.q; } };
              }
            }
            """
        )
        assertEquals(2, ts2715(ds))
    }

    @Test
    fun `a nested class in a NON-processed constructor is processed once`() {
        // the outer class has no abstract members → its emission half returns
        // before walking, so only the routing reach remains.
        val ds = diagnose(
            """
            class C {
              constructor() {
                const K = class D { abstract q: number; constructor() { this.q; } };
              }
            }
            """
        )
        assertEquals(1, ts2715(ds))
    }

    // ── File gate ─────────────────────────────────────────────────────────

    @Test
    fun `negative control - a d_ts file is skipped`() {
        val ds = diagnose(
            """
            declare abstract class C {
              abstract p: number;
              constructor();
            }
            """,
            fileName = "t.d.ts",
        )
        assertEquals(0, ts2715(ds))
    }
}
