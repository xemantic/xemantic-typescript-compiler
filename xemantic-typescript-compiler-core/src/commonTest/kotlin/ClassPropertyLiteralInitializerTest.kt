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
 * (P18.274) (CHK.212): a class property's initializer is checked against the declared type
 * WITH it as the contextual type — tsgo's `checkPropertyDeclaration` ->
 * `checkVariableLikeDeclaration` — so `b: "a" | "b" = "a"` keeps its literal. Plus the
 * var-decl / assignment legacy-path sibling (`let n: -1 | 1 = -1`, `w = -1`), whose
 * engine-confirmed literal fell through to the string fallback because `-1` is a
 * `PrefixUnaryExpression` and `true` an `Identifier`. Every expected row is tsgo 7.0.2's.
 */
class ClassPropertyLiteralInitializerTest {

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `literal initializers of literal-union class properties are legal`() {
        val r = rows("""
            type BT = "arraybuffer" | "blob";
            enum E { A, B, C }
            export class W {
              a: BT = 'arraybuffer';
              readonly b: BT = 'blob';
              c: 1 | 2 = 1;
              d: true | 0 = true;
              e: E.A | E.B = E.A;
              static g: BT = 'blob';
              o: { k: BT; n: number } = { k: 'blob', n: 1 };
              arr: BT[] = ['blob'];
              neg: -1 | 1 = -1;
              bi: 1n | 2n = 1n;
              tpl: BT = `blob`;
              p?: BT = 'blob';
            }
            export class G<T> {
              x: BT = 'blob';
              y: T | "z" = "z";
              readonly q: "u" | "v" = "u";
            }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `a literal outside the union still reports with its literal display`() {
        val r = rows("""
            type BT = "arraybuffer" | "blob";
            enum E { A, B, C }
            export class W {
              bad1: BT = 'nope';
              bad2: 1 | 2 = 3;
              bad3: E.A | E.B = E.C;
              static bad4: BT = 'x';
              readonly bad5: "u" | "v" = "w";
            }
        """)
        assert(r == listOf(
            "4:3 TS2322 Type '\"nope\"' is not assignable to type 'BT'.",
            "5:3 TS2322 Type '3' is not assignable to type '1 | 2'.",
            "6:3 TS2322 Type 'E.C' is not assignable to type 'E.A | E.B'.",
            "7:10 TS2322 Type '\"x\"' is not assignable to type 'BT'.",
            "8:12 TS2322 Type '\"w\"' is not assignable to type '\"u\" | \"v\"'.",
        ))
    }

    @Test
    fun `negative and boolean literals in a var decl and an assignment are legal`() {
        val r = rows("""
            let d: true | "x" = true;
            let neg: -1 | 1 = -1;
            const d2: true | 0 = true;
            let w: -1 | "x" = "x";
            w = -1;
            function f() { let n2: -1 | 1 = -1; return n2; }
            export { d, neg, d2, w, f };
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - a wrong negative literal in a var decl still reports`() {
        val r = rows("""
            let neg: -1 | 1 = -2;
            export { neg };
        """)
        assert(r == listOf("1:5 TS2322 Type '-2' is not assignable to type '-1 | 1'."))
    }
}
