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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * EP.2f (round 677): a const-enum member with a COMPUTED initializer must fold.
 *
 * The Transformer's same-file collector accepted only a numeric or string
 * literal (plus a negated numeric) and returned null — "non-constant, don't
 * inline" — for everything else, while the Checker's cross-module evaluator had
 * folded shifts and bitwise operators all along. So a same-file const enum
 * stopped inlining at its first computed member: tsc's own
 * `debug.ts` — `const enum Connection { Up = 1 << 0, …, UpDown = Up | Down }` —
 * lost 25 of its 121 reads to exactly that.
 *
 * The numeric operator table now lives in one place ([tsFoldNumericBinary]) so
 * the two evaluators cannot drift apart again.
 */
class ComputedConstEnumFoldingTest {

    private fun emit(@Language("typescript") src: String): String =
        TypeScriptCompiler().compile("// @target: es2020\n" + src.trimIndent()).javascript
            ?: error("no js")

    @Test
    fun `shift initializers fold`() {
        val js = emit(
            """
            const enum F { A = 1 << 0, B = 1 << 1, C = 1 << 4 }
            export const v = [F.A, F.B, F.C];
            """
        )
        assert("1 /* F.A */" in js)
        assert("2 /* F.B */" in js)
        assert("16 /* F.C */" in js)
    }

    @Test
    fun `a member combining PRIOR members by bare name folds`() {
        val js = emit(
            """
            const enum F { Up = 1 << 0, Down = 1 << 1, UpDown = Up | Down }
            export const v = F.UpDown;
            """
        )
        assert("3 /* F.UpDown */" in js)
    }

    @Test
    fun `a chain of computed members folds transitively`() {
        // tsc's Connection shape: each level built from the previous.
        val js = emit(
            """
            const enum C {
                None = 0, Up = 1 << 0, Down = 1 << 1, Left = 1 << 2, Right = 1 << 3,
                UpDown = Up | Down, LeftRight = Left | Right,
                UpDownLeft = UpDown | Left, UpDownLeftRight = UpDown | LeftRight,
            }
            export const v = [C.UpDown, C.UpDownLeft, C.UpDownLeftRight];
            """
        )
        assert("3 /* C.UpDown */" in js)
        assert("7 /* C.UpDownLeft */" in js)
        assert("15 /* C.UpDownLeftRight */" in js)
    }

    @Test
    fun `every arithmetic and bitwise operator folds`() {
        val js = emit(
            """
            const enum M {
                Add = 2 + 3, Sub = 7 - 2, Mul = 3 * 4, Div = 12 / 4, Mod = 7 % 4,
                Pow = 2 ** 3, Or = 5 | 2, And = 6 & 3, Xor = 5 ^ 3,
                Shl = 1 << 3, Shr = 16 >> 2, UShr = 16 >>> 2,
            }
            export const v = [M.Add, M.Sub, M.Mul, M.Div, M.Mod, M.Pow, M.Or, M.And, M.Xor, M.Shl, M.Shr, M.UShr];
            """
        )
        listOf(
            "5 /* M.Add */", "5 /* M.Sub */", "12 /* M.Mul */", "3 /* M.Div */",
            "3 /* M.Mod */", "8 /* M.Pow */", "7 /* M.Or */", "2 /* M.And */",
            "6 /* M.Xor */", "8 /* M.Shl */", "4 /* M.Shr */", "4 /* M.UShr */",
        ).forEach { assert(it in js) }
    }

    @Test
    fun `parenthesized and unary initializers fold`() {
        val js = emit(
            """
            const enum P { A = (1 << 2) | 1, B = -3, C = ~0 }
            export const v = [P.A, P.B, P.C];
            """
        )
        assert("5 /* P.A */" in js)
        assert("-3 /* P.B */" in js)
        assert("-1 /* P.C */" in js)
    }

    @Test
    fun `a member of ANOTHER const enum folds`() {
        val js = emit(
            """
            const enum Base { X = 4 }
            const enum Derived { Y = Base.X | 1 }
            export const v = Derived.Y;
            """
        )
        assert("5 /* Derived.Y */" in js)
    }

    @Test
    fun `auto-increment resumes after a computed member`() {
        val js = emit(
            """
            const enum A { P = 1 << 2, Q, R }
            export const v = [A.P, A.Q, A.R];
            """
        )
        assert("4 /* A.P */" in js)
        assert("5 /* A.Q */" in js)
        assert("6 /* A.R */" in js)
    }

    @Test
    fun `a nested namespace const enum folds`() {
        // The Connection enum sits inside `namespace Debug` in tsc's debug.ts.
        val js = emit(
            """
            namespace D {
                const enum C { Up = 1 << 0, Down = 1 << 1, UpDown = Up | Down }
                export function f() { return C.UpDown; }
            }
            export const v = D.f();
            """
        )
        assert("3 /* C.UpDown */" in js)
    }

    // ── negative controls: non-constant initializers must NOT be invented ──

    @Test
    fun `negative control - a FORWARD reference does not fold`() {
        val js = emit(
            """
            const enum F { A = B, B = 2 }
            export const v = F.A;
            """
        )
        assert("2 /* F.A */" !in js)
    }

    @Test
    fun `negative control - a call expression initializer does not fold`() {
        val js = emit(
            """
            declare function g(): number;
            const enum F { A = 1, B = g() }
            export const v = F.B;
            """
        )
        assert("/* F.B */" !in js)
    }

    @Test
    fun `negative control - an unrelated identifier does not fold`() {
        val js = emit(
            """
            declare const outside: number;
            const enum F { A = outside }
            export const v = F.A;
            """
        )
        assert("/* F.A */" !in js)
    }

    @Test
    fun `negative control - a string member still disables auto-increment`() {
        val js = emit(
            """
            const enum S { A = "x" }
            export const v = S.A;
            """
        )
        assert("\"x\" /* S.A */" in js)
    }

    // ── the shared operator table ─────────────────────────────────────────

    @Test
    fun `tsFoldNumericBinary implements JS semantics`() {
        assert(tsFoldNumericBinary(1.0, SyntaxKind.LessThanLessThan, 3.0) == 8.0)
        assert(tsFoldNumericBinary(16.0, SyntaxKind.GreaterThanGreaterThan, 2.0) == 4.0)
        assert(tsFoldNumericBinary(5.0, SyntaxKind.Bar, 2.0) == 7.0)
        assert(tsFoldNumericBinary(6.0, SyntaxKind.Ampersand, 3.0) == 2.0)
        assert(tsFoldNumericBinary(5.0, SyntaxKind.Caret, 3.0) == 6.0)
        assert(tsFoldNumericBinary(2.0, SyntaxKind.AsteriskAsterisk, 3.0) == 8.0)
        // `>>>` is unsigned over 32 bits: -1 >>> 28 === 15
        assert(tsFoldNumericBinary(-1.0, SyntaxKind.GreaterThanGreaterThanGreaterThan, 28.0) == 15.0)
    }

    @Test
    fun `negative control - a non-constant operator yields null`() {
        assert(tsFoldNumericBinary(1.0, SyntaxKind.EqualsEqualsEquals, 1.0) == null)
        assert(tsFoldNumericBinary(1.0, SyntaxKind.AmpersandAmpersand, 1.0) == null)
    }
}
