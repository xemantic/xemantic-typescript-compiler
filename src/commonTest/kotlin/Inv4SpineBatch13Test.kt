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
 * INV.4(b) batch 13 (round 520): TS1117/TS1118/TS2300 duplicate
 * object-literal members + TS1359 reserved-word identifiers migrated onto
 * the check spine from the deleted `checkDuplicateObjectLiteralProperties` /
 * `walkForObjectLiterals` / `walkNodeForObjectLiterals` and
 * `checkReservedWordIdentifiers` / `walkForReservedWords` /
 * `walkForReservedWordsInExpr` walk families.
 *
 * The duplicate check's destructuring-assignment-LHS skip (`({a: x, a: y} =
 * obj)` is a PATTERN — duplicate targets are legal JS) is reproduced as a
 * came-from-child parent walk: climb from the literal through
 * pattern-position parents and skip iff a `=` BinaryExpression is reached
 * with the climbed child as its LEFT.
 *
 * Widenings (fail pre-migration): ternary conditions, parameter defaults,
 * object-literal METHOD bodies, a destructuring default VALUE, class
 * property-initializer arrows, new-expression var initializers, and a
 * var-init arrow's expression body — positions the old walks never
 * descended; both rule families are position-independent tsc grammar.
 */
class Inv4SpineBatch13Test {

    // ── TS1117/TS1118/TS2300 — old reach: pre-verified against the OLD walker ──

    @Test
    fun `duplicate object literal property fires TS1117`() {
        diagnose(
            """
            const o = { a: 1, a: 2 };
            """,
        ) should {
            have(any { it.code == 1117 })
        }
    }

    @Test
    fun `duplicate property in a nested object literal fires TS1117`() {
        diagnose(
            """
            const o = { p: { a: 1, a: 2 } };
            """,
        ) should {
            have(any { it.code == 1117 })
        }
    }

    @Test
    fun `duplicate object literal methods fire TS1117`() {
        diagnose(
            """
            const o = { m() { return 1; }, m() { return 2; } };
            """,
        ) should {
            have(any { it.code == 1117 })
        }
    }

    @Test
    fun `duplicate get accessors fire TS2300 and TS1118`() {
        diagnose(
            """
            const o = { get g() { return 1; }, get g() { return 2; } };
            """,
        ) should {
            have(any { it.code == 2300 })
            have(any { it.code == 1118 })
        }
    }

    @Test
    fun `object literal in a return statement fires TS1117`() {
        diagnose(
            """
            function f() { return { a: 1, a: 2 }; }
            """,
        ) should {
            have(any { it.code == 1117 })
        }
    }

    @Test
    fun `negative control - a clean get set pair emits nothing`() {
        diagnose(
            """
            const o = { get g() { return 1; }, set g(v: number) {} };
            """,
        ) should {
            have(none { it.code == 1117 })
            have(none { it.code == 1118 })
            have(none { it.code == 2300 })
        }
    }

    @Test
    fun `negative control - destructuring assignment LHS duplicate targets are legal`() {
        diagnose(
            """
            let x: number, y: number;
            ({ a: x, a: y } = { a: 1 });
            """,
        ) should {
            have(none { it.code == 1117 })
        }
    }

    @Test
    fun `negative control - nested destructuring assignment LHS duplicates are legal`() {
        diagnose(
            """
            let x: number, y: number;
            ({ p: { a: x, a: y } } = { p: { a: 1 } });
            """,
        ) should {
            have(none { it.code == 1117 })
        }
    }

    @Test
    fun `the RHS of a destructuring assignment is still checked`() {
        diagnose(
            """
            let x: number;
            ({ a: x } = { a: 1, a: 2 });
            """,
        ) should {
            have(any { it.code == 1117 })
        }
    }

    // ── TS1117 widenings: FAIL pre-migration ────────────────────────────────

    @Test
    fun `widening - duplicate properties in a ternary condition fire TS1117`() {
        diagnose(
            """
            const z = ({ a: 1, a: 2 }) ? 1 : 2;
            """,
        ) should {
            have(any { it.code == 1117 })
        }
    }

    @Test
    fun `widening - duplicate properties in a parameter default fire TS1117`() {
        diagnose(
            """
            function f(x = { a: 1, a: 2 }) {}
            """,
        ) should {
            have(any { it.code == 1117 })
        }
    }

    @Test
    fun `widening - duplicate properties inside an object literal method body fire TS1117`() {
        diagnose(
            """
            const o = { m() { return { a: 1, a: 2 }; } };
            """,
        ) should {
            have(any { it.code == 1117 })
        }
    }

    @Test
    fun `widening - a destructuring default VALUE literal is checked`() {
        diagnose(
            """
            let q: object;
            ({ q = { a: 1, a: 2 } } = {});
            """,
        ) should {
            have(any { it.code == 1117 })
        }
    }

    // ── TS1359 — old reach: pre-verified against the OLD walker ─────────────

    @Test
    fun `await as an async function parameter fires TS1359`() {
        diagnose(
            """
            async function f(await: number) {}
            """,
        ) should {
            have(any { it.code == 1359 })
        }
    }

    @Test
    fun `await as an async class method parameter fires TS1359`() {
        diagnose(
            """
            class C { async m(await: number) {} }
            """,
        ) should {
            have(any { it.code == 1359 })
        }
    }

    @Test
    fun `await as an async arrow initializer parameter fires TS1359`() {
        diagnose(
            """
            const f = async (await: number) => 1;
            """,
        ) should {
            have(any { it.code == 1359 })
        }
    }

    @Test
    fun `enum named await fires TS1359`() {
        diagnose(
            """
            enum await { A }
            """,
        ) should {
            have(any { it.code == 1359 })
        }
    }

    @Test
    fun `enum named yield fires TS1359`() {
        diagnose(
            """
            enum yield { A }
            """,
        ) should {
            have(any { it.code == 1359 })
        }
    }

    @Test
    fun `negative control - await as a non-async function parameter is legal`() {
        diagnose(
            """
            function g(await: number) {}
            """,
            directives = "// @strict: false",
        ) should {
            have(none { it.code == 1359 })
        }
    }

    // ── TS1359 widenings: FAIL pre-migration ────────────────────────────────

    @Test
    fun `widening - async arrow in a class property initializer fires TS1359`() {
        diagnose(
            """
            class C { f = async (await: number) => 1; }
            """,
        ) should {
            have(any { it.code == 1359 })
        }
    }

    @Test
    fun `widening - async arrow inside a new-expression var initializer fires TS1359`() {
        diagnose(
            """
            declare class Foo { constructor(cb: unknown); }
            const x = new Foo(async (await: number) => 1);
            """,
        ) should {
            have(any { it.code == 1359 })
        }
    }

    @Test
    fun `widening - async arrow nested in a var-init arrow expression body fires TS1359`() {
        diagnose(
            """
            const f = () => async (await: number) => 1;
            """,
        ) should {
            have(any { it.code == 1359 })
        }
    }

    // ── shared negative controls ─────────────────────────────────────────────

    @Test
    fun `negative control - distinct properties emit nothing`() {
        diagnose(
            """
            const o = { a: 1, b: 2, m() { return 3; } };
            async function f(x: number) {}
            enum Color { Red }
            """,
        ) should {
            have(none { it.code == 1117 })
            have(none { it.code == 1118 })
            have(none { it.code == 1359 })
        }
    }
}
