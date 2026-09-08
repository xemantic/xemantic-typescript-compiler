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
import kotlin.test.Test

/**
 * (CHK.110)(a)/(b): the `try`/`catch` JOIN and an EXPRESSION-bodied arrow's body.
 *
 * Every expectation here is read from pristine `typescript@6.0.3`, which agreed with
 * tsgo 7.0.2 row for row over the whole measured matrix.
 *
 * ## (a) the silence was in `checkUsesOfUninitialized`, not in `markAssignments`
 *
 * The (CHK.110) item names two candidates for what swallowed
 * `let d: string; try { d = "a"; } catch {} use(d)` and it is NEITHER of them.
 * `markAssignments` really has no `TryStatement` arm, and B223's
 * `checkTryCatchOnlyAssignedVarReads` only ever looks at a `var x = init` DECLARED
 * inside the try block — but `checkUsesOfUninitialized`'s own `TryStatement` arm ran
 * `markAssignments(s, uninitialized)` over the try block's statements against the
 * CALLER's live set, so the try block's assignments escaped the try statement
 * unconditionally.
 *
 * The tell that it was there and not in `markAssignments` is a shape the same arm gets
 * WRONG in the other direction: `try { } catch {} finally { d = "c"; }` was an ours-only
 * TS2454 both references are silent about, because that arm walks the try block ONLY.
 * One escape, two opposite defects.
 *
 * The fix is a copy plus a real merge. tsc gives the catch clause the try block's ENTRY
 * flow (an exception may fire anywhere inside the try), so the continuation is definitely
 * assigned only by the `finally`, or on every path that can reach it.
 *
 * ## (b) an expression-bodied arrow has no statement list, so it had no frame
 *
 * A `SpineDaFrame` is opened at a `Block`, so `() => { use(e); }` was checked and
 * `() => use(e)` was silent — for a bare arrow, a nested one, an IIFE, an `async` one
 * and an object-literal property alike. The names to carry in are exactly the B78.2
 * leak set the block-bodied path already carries, minus the arrow's own parameters.
 *
 * ## Stated prices, each measured
 *
 * A catch block holding an UNASSIGNED CALL cannot be told from one that ends in a
 * never-returning call, so it is suppressed — (CHK.105)'s `bailOnUnassignedCall`, and
 * without it `try { d = compute(); } catch { fail("boom"); }` is an ours-only row. The
 * price is that `catch (e) { report(e); }` is suppressed too. A block round 450's walk
 * BAILS on (a nested `try`, a labeled statement) likewise keeps the pre-(CHK.110)
 * removal.
 *
 * ## A pre-existing ours-only row closed on the way
 *
 * `try { throw 1; } catch { x = "b"; } use(x)` was TS2454 here and silent in both
 * references before (CHK.110): the try block cannot complete normally, so the
 * continuation is reached only by the catch, which assigns.
 *
 * ## Not this item, and measured to be pre-existing
 *
 * A read inside a CLASS PROPERTY INITIALIZER is silent for the block-bodied arrow, the
 * function expression and a bare identifier alike — the class-member leak path is absent
 * from the definite-assignment pass entirely, not an expression-body gap.
 */
class Ts2454TryJoinAndArrowBodyTest {

    private val prelude = """
        declare function use(s: string): void;
        declare function compute(): string;
        declare function fail(m: string): never;
        declare const cond: boolean;
    """.trimIndent()

    private fun d(body: String): List<Diagnostic> = diagnose(prelude + "\n" + body.trimIndent())

    private fun ts2454(body: String): List<Diagnostic> = d(body).filter { it.code == 2454 }

    // ---- (a) the try/catch join -------------------------------------------------

    @Test
    fun `a try-only assignment with an empty catch is TS2454 after the try`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { x = "a"; } catch {}
              use(x);
            }
            """
        )
        assert(rows.size == 1)
        assert(rows[0].message == "Variable 'x' is used before being assigned.")
    }

    @Test
    fun `a catch-only assignment is TS2454 after the try`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { if (cond) { throw 1; } } catch { x = "b"; }
              use(x);
            }
            """
        )
        assert(rows.size == 1)
        assert(rows[0].message == "Variable 'x' is used before being assigned.")
    }

    @Test
    fun `a nested try inside a try block does not escape either level`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { try { x = "a"; } catch {} } catch {}
              use(x);
            }
            """
        )
        assert(rows.size == 1)
    }

    @Test
    fun `a try inside a plain block still merges`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              { try { x = "a"; } catch {} }
              use(x);
            }
            """
        )
        assert(rows.size == 1)
    }

    @Test
    fun `a try with a trailing empty finally still merges`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { x = "a"; } catch {} finally { }
              use(x);
            }
            """
        )
        assert(rows.size == 1)
    }

    @Test
    fun `negative control - both the try and the catch assign`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { x = "a"; } catch { x = "b"; }
              use(x);
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - only the finally assigns`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { } catch {} finally { x = "c"; }
              use(x);
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - a try with a finally and no catch`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { x = "a"; } finally { }
              use(x);
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - the catch rethrows`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { x = "a"; } catch (e) { throw e; }
              use(x);
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - the catch returns`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { x = "a"; } catch { return; }
              use(x);
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - a read inside the try after its own assignment`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { x = "a"; use(x); } catch {}
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - the catch assigns on both branches of an if`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { x = compute(); } catch { if (cond) { x = "a"; } else { x = "b"; } }
              use(x);
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - a catch holding an unassigned call is suppressed`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { x = compute(); } catch { fail("boom"); }
              use(x);
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `a try that returns after assigning reports at a read after the try`() {
        val rows = ts2454(
            """
            function f(): string {
              let x: string;
              try { x = compute(); return "a"; } catch { }
              use(x);
              return "z";
            }
            """
        )
        assert(rows.size == 1)
    }

    @Test
    fun `a try that throws after assigning reports at a read after the try`() {
        val rows = ts2454(
            """
            function f(): string {
              let x: string;
              try { x = compute(); throw 1; } catch { }
              use(x);
              return "z";
            }
            """
        )
        assert(rows.size == 1)
    }

    @Test
    fun `a switch inside the try does not survive an empty catch`() {
        val rows = ts2454(
            """
            function f(k: number) {
              let x: string;
              try { switch (k) { case 1: x = "a"; break; default: x = "b"; } } catch { }
              use(x);
            }
            """
        )
        assert(rows.size == 1)
    }

    @Test
    fun `a nested try whose inner try returns still reports`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { try { x = compute(); return; } catch {} } catch {}
              use(x);
            }
            """
        )
        assert(rows.size == 1)
    }

    @Test
    fun `negative control - the try and the catch both return so the continuation is unreachable`() {
        val rows = ts2454(
            """
            function f(a: string, b: string): string {
              let x: string;
              try { x = compute(); return a; } catch { return b; }
              use(x);
              return "z";
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - the try throws and only the catch assigns`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { throw 1; } catch { x = "b"; }
              use(x);
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - the try returns after assigning and the catch assigns too`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { x = compute(); return; } catch { x = "b"; }
              use(x);
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - assigned before the try`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              x = "z";
              try { } catch {}
              use(x);
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - an any-typed declaration is not TS2454 across a try`() {
        val rows = ts2454(
            """
            function f() {
              let x: any;
              try { x = "a"; } catch {}
              use(x);
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - a switch in the try assigns everywhere and the catch assigns`() {
        val rows = ts2454(
            """
            function f(k: number) {
              let x: string;
              try { switch (k) { case 1: x = "a"; break; default: x = "b"; } } catch { x = "c"; }
              use(x);
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - the try assigns and a switch in the catch assigns everywhere`() {
        val rows = ts2454(
            """
            function f(k: number) {
              let x: string;
              try { x = compute(); } catch { switch (k) { case 1: x = "a"; break; default: x = "b"; } }
              use(x);
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - a while-true in the try assigns before its break`() {
        val rows = ts2454(
            """
            function f() {
              let x: string;
              try { while (true) { x = "a"; break; } } catch { x = "c"; }
              use(x);
            }
            """
        )
        assert(rows.isEmpty())
    }

    // ---- (b) the expression-bodied arrow ----------------------------------------

    @Test
    fun `a read inside an expression-bodied arrow is TS2454`() {
        val rows = ts2454(
            """
            function f() {
              let e: string;
              const g = () => use(e);
              g();
            }
            """
        )
        assert(rows.size == 1)
        assert(rows[0].message == "Variable 'e' is used before being assigned.")
    }

    @Test
    fun `regression control - the block-bodied arrow twin still reports`() {
        val rows = ts2454(
            """
            function f() {
              let e: string;
              const g = () => { use(e); };
              g();
            }
            """
        )
        assert(rows.size == 1)
    }

    @Test
    fun `a nested expression-bodied arrow reports`() {
        val rows = ts2454(
            """
            function f() {
              let e: string;
              const g = () => () => use(e);
              g();
            }
            """
        )
        assert(rows.size == 1)
    }

    @Test
    fun `an immediately invoked expression-bodied arrow reports`() {
        val rows = ts2454(
            """
            function f() {
              let e: string;
              (() => use(e))();
            }
            """
        )
        assert(rows.size == 1)
    }

    @Test
    fun `an async expression-bodied arrow reports`() {
        val rows = ts2454(
            """
            function f() {
              let e: string;
              const g = async () => use(e);
              void g;
            }
            """
        )
        assert(rows.size == 1)
    }

    @Test
    fun `an expression-bodied arrow in an object literal property reports`() {
        val rows = ts2454(
            """
            function f() {
              let e: string;
              const o = { g: () => use(e) };
              void o;
            }
            """
        )
        assert(rows.size == 1)
    }

    @Test
    fun `an expression-bodied arrow reading through a nested argument reports`() {
        val rows = ts2454(
            """
            function f() {
              let e: string;
              const g = () => use([e, "x"].join(""));
              g();
            }
            """
        )
        assert(rows.size == 1)
    }

    @Test
    fun `an expression-bodied arrow with an unrelated parameter reports`() {
        val rows = ts2454(
            """
            function f() {
              let e: string;
              const g = (q: string) => use(e + q);
              g("x");
            }
            """
        )
        assert(rows.size == 1)
    }

    @Test
    fun `negative control - an arrow parameter shadowing the outer name`() {
        val rows = ts2454(
            """
            function f() {
              let e: string;
              const g = (e: string) => use(e);
              g("x");
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - an expression-bodied arrow that assigns`() {
        val rows = ts2454(
            """
            function f() {
              let e: string;
              const g = () => (e = "x");
              g();
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - assigned before the expression-bodied arrow`() {
        val rows = ts2454(
            """
            function f() {
              let e: string;
              e = "z";
              const g = () => use(e);
              g();
            }
            """
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - an any-typed declaration read in an expression-bodied arrow`() {
        val rows = ts2454(
            """
            function f() {
              let e: any;
              const g = () => use(e);
              g();
            }
            """
        )
        assert(rows.isEmpty())
    }
}
