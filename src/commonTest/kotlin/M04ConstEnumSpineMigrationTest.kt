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
import kotlin.test.assertEquals

/**
 * (M0.4): pins for the checkConstEnumDiagnostics spine migration — the
 * const-enum cluster's three sub-parts and their legacy quirks, both
 * directions: (1) declaration-level TS2474/2477/2478 fire for top-level AND
 * namespace-reachable const enums but never for one nested in a function
 * body (collectConstEnumDecls descends ModuleBlocks only); (2) the TS2567
 * const-enum + instantiated-namespace merge scan covers TOP-LEVEL statement
 * pairs only (a namespace-nested pair draws no TS2567 from this pass) and
 * requires the namespace to be INSTANTIATED; (3) the TS2475/2476 usage walk
 * runs ONLY in files that declare at least one (namespace-reachable) const
 * enum themselves, walks FunctionDeclaration bodies and namespace bodies
 * but NOT arrow/fn-expression/class/objlit-method bodies, skips typeof
 * operands, export-assignment RHS, shorthand property names, and
 * property/element-access bases, and flags non-string-literal element-access
 * keys (TS2476) while also walking the key expression itself. All
 * expectations verified green against the pre-migration legacy pass first.
 */
class M04ConstEnumSpineMigrationTest {

    // ── part 1: declaration-level TS2474 / TS2477 / TS2478 ────────────────

    @Test
    fun `division by zero initializer fires TS2477`() {
        diagnose(
            """
            const enum E { G = 1 / 0 }
            """
        ) should {
            have(any { it.code == 2477 &&
                it.message == "'const' enum member initializer was evaluated to a non-finite value." })
        }
    }

    @Test
    fun `NaN initializer fires TS2478`() {
        diagnose(
            """
            const enum E { H = 0 / 0 }
            """
        ) should {
            have(any { it.code == 2478 &&
                it.message == "'const' enum member initializer was evaluated to disallowed value 'NaN'." })
        }
    }

    @Test
    fun `non-existent member reference fires TS2474`() {
        val ds = diagnose(
            """
            const enum E1 { Y = E1.Z, Y1 = E1["W"] }
            """
        )
        assertEquals(2, ds.count { it.code == 2474 &&
            it.message == "const enum member initializers must be constant expressions." })
    }

    @Test
    fun `negative control - forward reference to an EXISTING member draws no TS2474`() {
        diagnose(
            """
            const enum E1 { X = E1.Y, Y = 1 }
            """
        ) should {
            have(none { it.code == 2474 })
        }
    }

    @Test
    fun `negative control - a const enum nested in a function body draws no decl-level diagnostics`() {
        // collectConstEnumDecls descends ModuleBlocks only — a function-body
        // const enum is never collected (and B83.5 leaves it unbound).
        diagnose(
            """
            function f() {
                const enum E { G = 1 / 0, H = 0 / 0 }
            }
            """
        ) should {
            have(none { it.code == 2477 || it.code == 2478 })
        }
    }

    @Test
    fun `negative control - dts files are skipped entirely`() {
        diagnose(
            """
            declare const enum E { G = 1 / 0 }
            """,
            fileName = "t.d.ts",
        ) should {
            have(none { it.code == 2477 })
        }
    }

    // ── part 2: TS2567 const enum + instantiated namespace merge ──────────

    @Test
    fun `top-level const enum plus instantiated namespace fires TS2567 on both names`() {
        val ds = diagnose(
            """
            const enum E { A }
            namespace E {
                export var x = 1;
            }
            """
        )
        assertEquals(2, ds.count { it.code == 2567 &&
            it.message == "Enum declarations can only merge with namespace or other enum declarations." })
    }

    @Test
    fun `negative control - an UNinstantiated namespace merge draws no TS2567`() {
        diagnose(
            """
            const enum E { A }
            namespace E {
                export type T = number;
            }
            """
        ) should {
            have(none { it.code == 2567 })
        }
    }

    @Test
    fun `negative control - a namespace-NESTED merge pair draws no TS2567 from this pass`() {
        // The legacy merge scan covers TOP-LEVEL statements only.
        diagnose(
            """
            namespace Outer {
                const enum E { A }
                namespace E {
                    export var x = 1;
                }
            }
            """
        ) should {
            have(none { it.code == 2567 })
        }
    }

    // ── part 3: TS2475 value-position usage ───────────────────────────────

    @Test
    fun `bare const enum in a variable initializer fires TS2475`() {
        diagnose(
            """
            const enum E { A }
            var x = E;
            """
        ) should {
            have(any { it.code == 2475 })
        }
    }

    @Test
    fun `bare const enum as call argument and in array literal fires TS2475`() {
        val ds = diagnose(
            """
            const enum E { A }
            declare function foo(t: any): void;
            foo(E);
            var y = [E];
            """
        )
        assertEquals(2, ds.count { it.code == 2475 })
    }

    @Test
    fun `bare const enum inside a FunctionDeclaration body fires TS2475`() {
        diagnose(
            """
            const enum E { A }
            function f() { return E; }
            """
        ) should {
            have(any { it.code == 2475 })
        }
    }

    @Test
    fun `bare const enum inside a namespace body fires TS2475`() {
        diagnose(
            """
            const enum E { A }
            namespace N {
                var x = E;
            }
            """
        ) should {
            have(any { it.code == 2475 })
        }
    }

    @Test
    fun `bare const enum in a switch case expression fires TS2475`() {
        diagnose(
            """
            const enum E { A }
            declare var n: any;
            switch (n) {
                case E: break;
            }
            """
        ) should {
            have(any { it.code == 2475 })
        }
    }

    @Test
    fun `negative control - property access base draws no TS2475`() {
        diagnose(
            """
            const enum E { A }
            var x = E.A;
            """
        ) should {
            have(none { it.code == 2475 })
        }
    }

    @Test
    fun `negative control - typeof operand draws no TS2475`() {
        diagnose(
            """
            const enum E { A }
            var x = typeof E;
            """
        ) should {
            have(none { it.code == 2475 })
        }
    }

    @Test
    fun `negative control - export assignment RHS draws no TS2475`() {
        diagnose(
            """
            // @module: commonjs
            const enum E { A }
            export = E;
            """
        ) should {
            have(none { it.code == 2475 })
        }
    }

    @Test
    fun `negative control - arrow function bodies are not walked`() {
        // The legacy usage walker reaches FunctionDeclaration bodies via its
        // statement arms but has no arrow/fn-expression arm in walkExpr.
        diagnose(
            """
            const enum E { A }
            const f = () => { return E; };
            const g = function() { return E; };
            """
        ) should {
            have(none { it.code == 2475 })
        }
    }

    @Test
    fun `negative control - class and object-literal method bodies are not walked`() {
        diagnose(
            """
            const enum E { A }
            class C { m() { return E; } }
            const o = { m() { return E; } };
            """
        ) should {
            have(none { it.code == 2475 })
        }
    }

    @Test
    fun `negative control - shorthand property name draws no TS2475`() {
        diagnose(
            """
            const enum E { A }
            const o = { E };
            """
        ) should {
            have(none { it.code == 2475 })
        }
    }

    @Test
    fun `negative control - a file without its own const enum skips the usage walk`() {
        // The legacy pass gates the WHOLE per-file body (incl. the usage walk)
        // on collectConstEnumDecls being non-empty for that file — a file
        // referencing only a script-global const enum is skipped.
        diagnose(
            """
            // @filename: decl.ts
            const enum G { A }
            // @filename: use.ts
            var x = G;
            """
        ) should {
            have(none { it.code == 2475 })
        }
    }

    // ── part 3: TS2476 element-access keys ────────────────────────────────

    @Test
    fun `numeric and identifier element-access keys fire TS2476`() {
        val ds = diagnose(
            """
            const enum E2 { A }
            var name = "A";
            var y0 = E2[1];
            var y1 = E2[name];
            """
        )
        assertEquals(2, ds.count { it.code == 2476 &&
            it.message == "A const enum member can only be accessed using a string literal." })
    }

    @Test
    fun `template expression key fires TS2476`() {
        diagnose(
            """
            const enum E2 { A }
            var name = "A";
            var y2 = E2[`${"$"}{name}`];
            """
        ) should {
            have(any { it.code == 2476 })
        }
    }

    @Test
    fun `negative control - string literal and no-substitution template keys draw no TS2476`() {
        diagnose(
            """
            const enum E2 { A }
            var y0 = E2["A"];
            var y1 = E2[`A`];
            """
        ) should {
            have(none { it.code == 2476 })
        }
    }

    @Test
    fun `element-access key expression is itself walked for TS2475`() {
        // E[F] — TS2476 for the non-literal key AND TS2475 for the bare F.
        val ds = diagnose(
            """
            const enum E { A }
            const enum F { B }
            var y = E[F];
            """
        )
        ds should {
            have(any { it.code == 2476 })
            have(any { it.code == 2475 })
        }
    }
}
