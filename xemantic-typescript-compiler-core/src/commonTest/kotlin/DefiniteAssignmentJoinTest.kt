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
 * (CHK.105): TS2454 for an `if` JOIN, and the TS2448 co-emit's real `assumeInitialized`.
 *
 * ## (a) the co-emit rule was CONST-NESS and is really the TYPE
 *
 * B78.1 read the co-emit rule off `typeGuardNarrowsIndexedAccessOfKnownProperty10` and
 * recorded it as "a reachable `const x = init` used out of order fires TS2448 only".
 * Measured against BOTH tsgo 7.0.2 and pristine `typescript@6.0.3`, that baseline's
 * const is `const id = foo.bar` with `Foo.bar: any`, and what suppresses tsc's TS2454
 * there is tsc's `assumeInitialized` on `AnyOrUnknown | Void` — the TYPE. With an
 * ordinary type both references report TS2448 AND TS2454 at the same position.
 *
 * The other half of `assumeInitialized` that this population reaches is
 * `isOuterVariable`: a CLASS STATIC INITIALIZER is a different control-flow container
 * from the module-level declaration, and there both references report TS2448 alone
 * (the corpus's `classStaticInitializersUsePropertiesBeforeDeclaration`).
 *
 * ## (b) the `if` join
 *
 * `markAssignments` scanned BOTH branches of an `if` unconditionally, so
 * `let b: string; if (cond) b = "a"; use(b)` removed `b` from the uninitialized set —
 * "a set, not a flow lattice". The lattice it needs already existed: round 450's
 * `daWalkStmt`, written for `while (true)`, models sequential flow, the if/else join and
 * abrupt completion. It is now consulted per variable, CONSERVATIVE TO REMOVE — every
 * shape the walk bails on keeps the previous removal, so the only behaviour that changes
 * is what the walk can prove.
 *
 * One guard the naive form needed, found by the 8-profile grid: tsc's binder makes the
 * flow UNREACHABLE after a call to a never-returning function, so
 * `if (a) { x = 1 } else { Debug.fail("…") }` leaves `x` assigned. Deciding that needs
 * the callee's return type, which this walker must not resolve, so an unassigned CALL
 * statement bails — measured on `services/codefixes/fixPropertyOverrideAccessor.ts:83`,
 * two ours-only rows on three profiles without it.
 *
 * ## Stated residues - deliberately NOT pinned
 *
 * Three shapes both references report and this checker still does not: a `try { x = … }
 * catch {}` join, a read inside an EXPRESSION-bodied arrow (the block-bodied form
 * already reports), and a `switch` with no `default`. The last was BUILT and REVERTED:
 * requiring a default costs **two ours-only TS2454 on all eight profiles** at
 * `checker.ts:38141`, whose switch over `node.kind` has no default and IS exhaustive —
 * tsc's `isExhaustiveSwitchStatement` proves it and this checker cannot.
 */
class DefiniteAssignmentJoinTest {

    private val prelude = """
        declare function use(s: string): void;
        declare const cond: boolean;
    """.trimIndent()

    // ---- (a) the TS2448 co-emit -------------------------------------------------

    @Test
    fun `a reachable const used before its declaration co-emits TS2454`() {
        val d = diagnose(
            """
            function f() {
              const c = x;
              const x = 1;
              return c;
            }
            """.trimIndent()
        )
        assert(d.count { it.code == 2448 } == 1)
        assert(d.count { it.code == 2454 } == 1)
        assert(d.first { it.code == 2454 }.message == "Variable 'x' is used before being assigned.")
    }

    @Test
    fun `negative control - an any-typed const stays TS2448 only`() {
        val d = diagnose(
            """
            function f() {
              const p: any = q;
              const q: any = 1;
              return p;
            }
            """.trimIndent()
        )
        assert(d.count { it.code == 2448 } == 1)
        assert(d.count { it.code == 2454 } == 0)
    }

    @Test
    fun `negative control - an inferred any const stays TS2448 only`() {
        val d = diagnose(
            """
            interface Foo { bar: any }
            declare const foo: Foo;
            function f() {
              const p = q;
              const q = foo.bar;
              return p;
            }
            """.trimIndent()
        )
        assert(d.count { it.code == 2448 } == 1)
        assert(d.count { it.code == 2454 } == 0)
    }

    @Test
    fun `negative control - a class static initializer stays TS2448 only`() {
        val d = diagnose(
            """
            class Foo {
              static m = ObjLiteral.A;
            }
            const ObjLiteral = { A: 1 };
            """.trimIndent()
        )
        assert(d.count { it.code == 2448 } == 1)
        assert(d.count { it.code == 2454 } == 0)
    }

    // ---- (b) the `if` join ------------------------------------------------------

    @Test
    fun `an if without an else leaves the variable possibly unassigned`() {
        val d = diagnose(
            prelude + """

            function f() {
              let b: string;
              if (cond) b = "a";
              use(b);
            }
            """
        )
        assert(d.count { it.code == 2454 } == 1)
        assert(d.first { it.code == 2454 }.message == "Variable 'b' is used before being assigned.")
    }

    @Test
    fun `negative control - both branches assigning is definite`() {
        diagnose(
            prelude + """

            function f() {
              let h: string;
              if (cond) h = "a"; else h = "b";
              use(h);
            }
            """
        ) should { have(none { it.code == 2454 }) }
    }

    @Test
    fun `a branch that returns instead of assigning is definite`() {
        diagnose(
            prelude + """

            function f() {
              let h: string;
              if (cond) h = "a"; else return;
              use(h);
            }
            """
        ) should { have(none { it.code == 2454 }) }
    }

    @Test
    fun `a branch that throws instead of assigning is definite`() {
        diagnose(
            prelude + """

            function f() {
              let h: string;
              if (cond) h = "a"; else throw new Error();
              use(h);
            }
            """
        ) should { have(none { it.code == 2454 }) }
    }

    /**
     * tsc's binder makes the flow unreachable after a call to a never-returning function,
     * so the else branch leaves `h` assigned. The callee's return type is what decides it
     * and this walker must not resolve one, so an unassigned CALL statement bails — the
     * shipped instance is `services/codefixes/fixPropertyOverrideAccessor.ts:83`
     * (`Debug.fail(...)`), which produced two ours-only rows on three profiles.
     */
    @Test
    fun `a branch whose only statement is a call is not claimed as unassigned`() {
        diagnose(
            prelude + """

            declare function fail(msg: string): never;

            function f() {
              let h: string;
              if (cond) h = "a"; else fail("nope");
              use(h);
            }
            """
        ) should { have(none { it.code == 2454 }) }
    }

    @Test
    fun `a never-assigned let still reports`() {
        val d = diagnose(
            prelude + """

            function f() {
              let a: string;
              use(a);
            }
            """
        )
        assert(d.count { it.code == 2454 } == 1)
    }

    @Test
    fun `negative control - an assignment before the read is definite`() {
        diagnose(
            prelude + """

            function f() {
              let a: string;
              a = "x";
              if (cond) a = "y";
              use(a);
            }
            """
        ) should { have(none { it.code == 2454 }) }
    }

    @Test
    fun `negative control - a definite assignment assertion is silent`() {
        diagnose(
            prelude + """

            function f() {
              let r!: string;
              use(r);
            }
            """
        ) should { have(none { it.code == 2454 }) }
    }

    @Test
    fun `negative control - a nullish-including annotation is silent`() {
        diagnose(
            prelude + """

            function f() {
              let m: string | undefined;
              if (cond) m = "a";
              return m;
            }
            """
        ) should { have(none { it.code == 2454 }) }
    }
}
