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
 * (CHK.155), round P18.187 — a read captured by an EXPRESSION-BODIED arrow follows tsgo's
 * outer-variable rule for TS2454: tsgo 7.0.2 `checkIdentifier` (checker.go) computes
 * `assumeInitialized := … || (isOuterVariable && !isNeverInitialized) || …`, and
 * `isNeverInitialized` is `!isSymbolAssignedDefinitely(symbol)` — a DEFINITE assignment
 * (`=`, `??=`, `||=`, `&&=`) ANYWHERE in the declaring function, nested closures included.
 * `walkExprForFlowTS2454`'s arrow arm used to mask only the assignments inside THAT arrow, so
 * `s` assigned in a sibling closure (`sched(() => { s = mk() })`) and read under an
 * `if`/`while`/`for`/`switch`/`try` drew an ours-only TS2454 (rxjs `TestScheduler.ts:158`).
 * Block-bodied arrows and function expressions already agreed; the arm now uses the same
 * function-wide set.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, measured over `build/scratch-p18187/cells`.
 * Every fixture is FUNCTION-scoped (a file-level `let` never leaks here by design).
 *
 * NOT PINNED (pre-existing, unchanged): a never-assigned `let` declared inside a BLOCK-bodied
 * arrow and read in a nested expression arrow under an `if` is silent here where tsgo
 * reports; `if (n) s = mk(); if (m) { s.x() }` is silent here where tsgo reports (the flow
 * walk's OR-semantics across branches).
 */
class OuterVariableCapturedReadTest {

    private val prelude = """
        interface Sub { unsubscribe(): void }
        declare function mk(): Sub;
        declare function sched(f: () => void): void;
        declare function pn(n: number): void;

    """.trimIndent()

    /** Every row as `line:column code message`, sorted; the fixture starts on line 6. */
    private fun rows(source: String): List<String> =
        diagnose(prelude + "\n" + source.trimIndent())
            .map { "${it.line}:${it.character} ${it.code} ${it.message}" }.sorted()

    private fun unassigned(line: Int, col: Int, name: String = "s") =
        "$line:$col 2454 Variable '$name' is used before being assigned."

    @Test
    fun `a captured read under an if is assumed initialized when a sibling closure assigns`() {
        val actual = rows(
            """
            export function f(n: number) {
              let s: Sub;
              sched(() => { s = mk(); });
              if (n !== 0) { sched(() => s.unsubscribe()); }
            }
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `a captured read in a while, for, switch and try body is assumed initialized`() {
        val actual = rows(
            """
            export function f(n: number) {
              let s: Sub;
              sched(() => { s = mk(); });
              while (n) { sched(() => s.unsubscribe()); }
              for (let i = 0; i < n; i++) sched(() => s.unsubscribe());
              switch (n) { case 1: sched(() => s.unsubscribe()); }
              try { sched(() => s.unsubscribe()); } finally { pn(n); }
            }
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `an assignment textually after the capturing arrow also counts`() {
        val actual = rows(
            """
            export function f(n: number) {
              let s: Sub;
              if (n) sched(() => s.unsubscribe());
              if (n > 1) s = mk();
            }
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `a var, a nested arrow and a number probe follow the same rule`() {
        val actual = rows(
            """
            export function f(n: number) {
              var s: Sub;
              let k: number;
              sched(() => { s = mk(); k = 1; });
              if (n) sched(() => sched(() => s.unsubscribe()));
              if (n) sched(() => pn(k));
            }
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `a nested closure's own pass does not replace the enclosing function's set`() {
        val actual = rows(
            """
            export function f(n: number) {
              let s: Sub;
              sched(() => { let t: Sub; t = mk(); t.unsubscribe(); s = mk(); });
              if (n) sched(() => s.unsubscribe());
            }
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `a file-level captured read follows the same rule`() {
        val actual = rows(
            """
            declare const k: number;
            let s: Sub;
            let never: Sub;
            sched(() => { s = mk(); });
            if (k) sched(() => s.unsubscribe());
            if (k) sched(() => never.unsubscribe());
            export {}
            """
        )
        assert(actual == listOf(unassigned(11, 20, "never")))
    }

    @Test
    fun `must still report - a captured read of a variable never assigned anywhere`() {
        val actual = rows(
            """
            export function f(n: number) {
              let s: Sub;
              if (n) { sched(() => s.unsubscribe()); }
              for (let i = 0; i < n; i++) sched(() => s.unsubscribe());
            }
            """
        )
        assert(actual == listOf(unassigned(8, 24), unassigned(9, 43)))
    }

    @Test
    fun `must still report - a compound assignment is not a definite assignment`() {
        val actual = rows(
            """
            export function f(n: number) {
              let s: number;
              sched(() => { s += 1; });
              if (n) sched(() => pn(s));
            }
            """
        )
        assert(actual == listOf(unassigned(8, 17), unassigned(9, 25)))
    }

    @Test
    fun `must still report - a direct read in the same function after a conditional assignment`() {
        val actual = rows(
            """
            export function f(n: number) {
              let s: Sub;
              if (n) s = mk();
              s.unsubscribe();
            }
            """
        )
        assert(actual == listOf(unassigned(9, 3)))
    }

    @Test
    fun `must still report - a direct read under an if is not rescued by a closure assignment`() {
        val actual = rows(
            """
            export function f(n: number) {
              let s: Sub;
              sched(() => { s = mk(); });
              if (n) { s.unsubscribe(); }
            }
            """
        )
        assert(actual == listOf(unassigned(9, 12)))
    }

    @Test
    fun `control - strictNullChecks off reports nothing`() {
        val actual = diagnose(
            prelude + """
            export function f(n: number) {
              let s: Sub;
              if (n) { sched(() => s.unsubscribe()); }
            }
            """.trimIndent(),
            directives = "// @strict: true\n// @strictNullChecks: false",
        ).filter { it.code == 2454 }
        assert(actual.isEmpty())
    }
}
