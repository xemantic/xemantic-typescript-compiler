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
 * (CHK.153), round P18.184 — a function-expression ARGUMENT whose callee's parameter
 * declares `this:` has a contextual `this`, so TS2683 must not fire, whatever kind of
 * binding the callee's RECEIVER is. `callArgHasContextualThis` runs on the TS2683 spine
 * under the file's RESTING locals, so it resolved only a FILE-LEVEL receiver: a parameter,
 * a body local, a destructured parameter or a class-method parameter read as unresolved
 * and TS2683 fired where tsgo 7.0.2 is silent (rxjs `range.ts:78`, `timer.ts:178`,
 * `scheduleArray.ts:22`). The receiver is now resolved LEXICALLY, innermost scope first.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, measured over the cells in
 * `build/scratch-p18184/cells`. The controls that KEEP TS2683 are the half that matters: a
 * callee with no `this:`, an arrow, and three SHADOWS (block, destructured parameter,
 * parameter over a same-named file-level binding) where resolving the wrong binding would
 * suppress a row tsgo reports.
 *
 * NOT PINNED (a pre-existing display residue, identical for a FILE-LEVEL receiver on the
 * parent binary): through the GENERIC `Sched`, `const n: number = this` reads
 * `Type 'Action<any>'` here against tsgo's `Type 'Action<unknown>'` — an uninferred type
 * parameter in the contextual `this:` renders `any`.
 */
class CallbackReceiverContextualThisTest {

    private val prelude = """
        interface Action<T> { schedule(state?: T, delay?: number): void; }
        interface Sched { schedule<T>(work: (this: Action<T>, state: T) => void, delay?: number, state?: T): void; }
        interface Plain { schedule(work: (state: number) => void): void; }
    """.trimIndent() + "\n"

    private val ts2683 = "2683 'this' implicitly has type 'any' because it does not have a type annotation."

    /** Every row as `line:column code message`, sorted. */
    private fun rows(source: String): List<String> =
        diagnose(prelude + source.trimIndent()).map { "${it.line}:${it.character} ${it.code} ${it.message}" }.sorted()

    @Test
    fun `a parameter receiver carries the callee's contextual this`() {
        val rows = rows("""
            export function f(s: Sched) {
              s.schedule(function () { this.schedule(); });
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `a non-generic callee parameter carries it too`() {
        val rows = rows("""
            interface Sched1 { schedule(work: (this: Action<number>, state: number) => void): void; }
            export function f(s: Sched1) {
              s.schedule(function () { this.schedule(); });
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `a body-local receiver typed by its initializer carries it`() {
        val rows = rows("""
            declare function mk(n: number): Sched;
            export function f(n: number) {
              const s = mk(n);
              s.schedule(function () { this.schedule(); });
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `a destructured parameter receiver carries it`() {
        val rows = rows("""
            export function f({ s }: { s: Sched }) {
              s.schedule(function () { this.schedule(); });
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `a nullable let receiver assigned before the call carries it`() {
        val rows = rows("""
            declare function mk(): Sched;
            export function f() {
              let s: Sched | undefined;
              s = mk();
              s.schedule(function () { this.schedule(); });
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `a receiver captured by a nested function carries it`() {
        val rows = rows("""
            export function f(s: Sched) {
              function g() {
                s.schedule(function () { this.schedule(); });
              }
              g();
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `a member chain receiver carries it`() {
        val rows = rows("""
            interface Holder { b: Sched }
            export function f(a: Holder) {
              a.b.schedule(function () { this.schedule(); });
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `a class method parameter receiver carries it`() {
        val rows = rows("""
            export class K {
              run(s: Sched) { s.schedule(function () { this.schedule(); }); }
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `a function-typed parameter called directly carries it`() {
        val rows = rows("""
            export function f(sched: (work: (this: Action<number>) => void) => void) {
              sched(function () { this.schedule(); });
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `a union receiver carries it when any constituent declares this`() {
        val rows = rows("""
            export function f(s: Sched | Plain) {
              s.schedule(function () { this.schedule(); });
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `an optional-chain and a non-null receiver carry it`() {
        val rows = rows("""
            export function f(s: Sched | undefined) {
              s?.schedule(function () { this.schedule(); });
              s!.schedule(function () { this.schedule(); });
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `the body is checked against the contextual this and only its own error remains`() {
        val rows = rows("""
            interface Sched1 { schedule(work: (this: Action<number>, state: number) => void): void; }
            export function f(s: Sched1) {
              s.schedule(function () { const n: number = this; });
            }
        """)
        assert(rows == listOf("6:34 2322 Type 'Action<number>' is not assignable to type 'number'."))
    }

    @Test
    fun `control - a callee parameter without this keeps TS2683`() {
        val rows = rows("""
            export function f(s: Plain) {
              s.schedule(function () { this.schedule(); });
            }
        """)
        assert(rows == listOf("5:28 $ts2683"))
    }

    @Test
    fun `control - an arrow callback keeps its lexical this and TS2683`() {
        val rows = rows("""
            export function f(s: Sched) {
              s.schedule(() => { this.schedule(); });
            }
        """)
        assert(rows == listOf("5:22 $ts2683"))
    }

    @Test
    fun `control - a block-scoped shadow without this keeps TS2683`() {
        val rows = rows("""
            export function f(s: Sched) {
              {
                const s = {} as Plain;
                s.schedule(function () { this.schedule(); });
              }
            }
        """)
        assert(rows == listOf("7:30 $ts2683"))
    }

    @Test
    fun `a block-scoped shadow with this is read in preference to the outer parameter`() {
        val rows = rows("""
            export function f(s: Plain) {
              {
                const s = {} as Sched;
                s.schedule(function () { this.schedule(); });
              }
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `control - a destructured parameter shadowing an outer receiver keeps TS2683`() {
        val rows = rows("""
            export function f(s: Sched) {
              function g({ s }: { s: Plain }) {
                s.schedule(function () { this.schedule(); });
              }
              return g;
            }
        """)
        assert(rows == listOf("6:30 $ts2683"))
    }

    @Test
    fun `control - a parameter shadowing a same-named file-level receiver keeps TS2683`() {
        val rows = rows("""
            declare const s: Sched;
            export function f(s: Plain) {
              s.schedule(function () { this.schedule(); });
            }
        """)
        assert(rows == listOf("6:28 $ts2683"))
    }

    @Test
    fun `control - a local class shadowing a file-level receiver stops the walk and keeps TS2683`() {
        val rows = rows("""
            declare const s: Sched;
            export function f() {
              class s { static schedule(work: (state: number) => void) {} }
              s.schedule(function () { this.x; });
            }
        """)
        assert(rows == listOf("7:28 $ts2683"))
    }

    @Test
    fun `control - an un-annotated parameter shadowing a file-level receiver keeps TS2683`() {
        val rows = rows("""
            declare const s: Sched;
            declare function mkPlain(): Plain;
            export function f(s = mkPlain()) {
              s.schedule(function () { this.schedule(); });
            }
        """)
        assert(rows == listOf("7:28 $ts2683"))
    }

    @Test
    fun `control - noImplicitThis off is silent`() {
        val rows = diagnose(prelude + """
            export function f(s: Plain) {
              s.schedule(function () { this.schedule(); });
            }
        """.trimIndent(), directives = "// @strict: true\n// @noImplicitThis: false")
        assert(rows.isEmpty())
    }
}
