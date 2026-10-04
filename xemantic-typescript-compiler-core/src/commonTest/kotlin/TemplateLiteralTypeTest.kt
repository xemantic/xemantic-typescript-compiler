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
 * (P18.287) (CHK.216) template literal TYPES ([TemplateLiteralTypes]): before this round a
 * template resolved to a fresh `string` intrinsic, so `'5' extends \`-${string}\`` was TRUE and
 * no literal was ever rejected by a template. Every expectation is tsgo 7.0.2's full output for
 * the fixture (`tools/tsgo-7.0.2/lib/tsc`, `strict`, `target: es2022`), row for row.
 */
class TemplateLiteralTypeTest {

    private fun rows(source: String): List<String> =
        diagnose(source, "// @strict: true\n// @target: es2022")
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }
            .sorted()

    /** The census repro (`repro/isneg`): a number literal stringified into a template and tested
     *  against a template it does not match. tsgo: silent; before: three false positives. */
    @Test
    fun `a literal not matching a template takes the false branch`() {
        val r = rows(
            """
            type M<S> = S extends `-${'$'}{string}` ? true : false;
            export const a: M<'5'> = false;
            export const b: M<`${'$'}{5}`> = false;
            type N<T extends number> = `${'$'}{T}` extends `-${'$'}{string}` ? true : false;
            export const c: N<5> = false;
            type P<T extends number> = `${'$'}{T}`;
            export const d: P<5> = '5';
            """,
        )
        assert(r.isEmpty())
    }

    /** Literal against template: a match, a mismatch, several placeholders, `${number}`,
     *  `${bigint}`, `${boolean}` (a union span) and a union text span. */
    @Test
    fun `a string literal relates to a template by matching`() {
        val r = rows(
            """
            export const e1: `-${'$'}{string}` = '-5';
            export const e2: `-${'$'}{string}` = '5';
            export const e3: `${'$'}{number}px` = '12px';
            export const e4: `${'$'}{number}px` = 'apx';
            export const e5: `${'$'}{bigint}` = '12';
            export const e6: `${'$'}{bigint}` = '1.5';
            export const e7: `${'$'}{boolean}` = 'true';
            export const e8: `${'$'}{boolean}` = 'yes';
            export const e9: `a${'$'}{string}b${'$'}{string}c` = 'axbyc';
            export const e10: `a${'$'}{string}b${'$'}{string}c` = 'axbyd';
            export const e11: `${'$'}{'x' | 'y'}-${'$'}{number}` = 'x-1';
            export const e12: `${'$'}{'x' | 'y'}-${'$'}{number}` = 'z-1';
            export const n1: `${'$'}{number}` = 'Infinity';
            export const n2: `${'$'}{number}` = '';
            export const n3: `${'$'}{number}` = '.5';
            export const m1: `a${'$'}{string}b` = 'ab';
            export const m2: `a${'$'}{string}b` = 'a';
            """,
        )
        assert(
            r == listOf(
                "10:14 TS2322 Type '\"axbyd\"' is not assignable to type '`a${'$'}{string}b${'$'}{string}c`'.",
                "12:14 TS2322 Type '\"z-1\"' is not assignable to type '`x-${'$'}{number}` | `y-${'$'}{number}`'.",
                "13:14 TS2322 Type '\"Infinity\"' is not assignable to type '`${'$'}{number}`'.",
                "14:14 TS2322 Type '\"\"' is not assignable to type '`${'$'}{number}`'.",
                "17:14 TS2322 Type '\"a\"' is not assignable to type '`a${'$'}{string}b`'.",
                "2:14 TS2322 Type '\"5\"' is not assignable to type '`-${'$'}{string}`'.",
                "4:14 TS2322 Type '\"apx\"' is not assignable to type '`${'$'}{number}px`'.",
                "6:14 TS2322 Type '\"1.5\"' is not assignable to type '`${'$'}{bigint}`'.",
                "8:14 TS2322 Type '\"yes\"' is not assignable to type '\"false\" | \"true\"'.",
            ),
        )
    }

    /** A template relates to `string`, to a template with the same texts by its spans, and to
     *  nothing narrower; an all-literal template IS a string literal, a `never` span is `never`. */
    @Test
    fun `a template relates to string and is normalised`() {
        val r = rows(
            """
            declare const t1: `-${'$'}{string}`;
            export const s1: string = t1;
            export const s2: number = t1;
            export const tt1: `${'$'}{string}` = t1;
            export const tt2: `-${'$'}{number}` = t1;
            declare const t2: `-${'$'}{number}`;
            export const tt3: `-${'$'}{string}` = t2;
            export const l1: '-5' = t1;
            export const n1: `${'$'}{"a"}` = 'b';
            export const n2: `${'$'}{never}x` = 'x';
            export const n3: `${'$'}{"a" | "b"}x` = 'cx';
            export const n4: `${'$'}{null}-${'$'}{undefined}` = 'null-undefinedx';
            """,
        )
        assert(
            r == listOf(
                "11:14 TS2322 Type '\"cx\"' is not assignable to type '\"ax\" | \"bx\"'.",
                "12:14 TS2322 Type '\"null-undefinedx\"' is not assignable to type '\"null-undefined\"'.",
                // tsgo prints `Type '"x"'` here: a `never` target keeps the literal (pre-existing
                // display rule of this checker, not a template one).
                "10:14 TS2322 Type 'string' is not assignable to type 'never'.",
                "3:14 TS2322 Type '`-${'$'}{string}`' is not assignable to type 'number'.",
                "5:14 TS2322 Type '`-${'$'}{string}`' is not assignable to type '`-${'$'}{number}`'.",
                "8:14 TS2322 Type '`-${'$'}{string}`' is not assignable to type '\"-5\"'.",
                "9:14 TS2322 Type '\"b\"' is not assignable to type '\"a\"'.",
            ).sorted(),
        )
    }

    /** A template EXPRESSION in a template / literal context (or `as const`) has a template type:
     *  tsgo's `checkTemplateExpression`. Without it rxjs' `ajax.ts:438` is a false positive. */
    @Test
    fun `a template expression in a literal context is a template type`() {
        val r = rows(
            """
            type D = 'upload' | 'download';
            type E = 'load' | 'progress';
            declare function take(x: `${'$'}{D}_${'$'}{E}`): void;
            export function f(d: D, e: E, s: string) {
              take(`${'$'}{d}_${'$'}{e}` as const);
              const a: `${'$'}{D}_${'$'}{E}` = `${'$'}{d}_${'$'}{e}`;
              const b: `${'$'}{D}_${'$'}{E}` = `${'$'}{s}_${'$'}{e}`;
              const c: `x-${'$'}{string}` = `x-${'$'}{s}`;
              const g: `x-${'$'}{string}` = `y-${'$'}{s}`;
              return [a, b, c, g];
            }
            """,
        )
        assert(
            r == listOf(
                "7:9 TS2322 Type '`${'$'}{string}_load` | `${'$'}{string}_progress`' is not assignable to type '\"download_load\" | \"download_progress\" | \"upload_load\" | \"upload_progress\"'.",
                "9:9 TS2322 Type '`y-${'$'}{string}`' is not assignable to type '`x-${'$'}{string}`'.",
            ),
        )
    }

    /** Control: a template expression with NO contextual type is `string` (tsgo), so an
     *  un-annotated const initialised by one keeps `string` — and a `string` source stays
     *  assignable to a template here (tsgo reports `2:58`; the strict answer would turn every
     *  template EXPRESSION this checker types `string` into a false positive). */
    @Test
    fun `control - a template expression without context and a string source`() {
        val r = rows(
            """
            export function f<K extends string>(k: K): `${'$'}{K}!` { return `${'$'}{k}!`; }
            export function g(s: string) { const k = `x-${'$'}{s}`; const t: `x-${'$'}{string}` = k; return t; }
            type Px = `${'$'}{number}px`;
            export function h(n: number): Px { return `${'$'}{n}px`; }
            export const arr: `id-${'$'}{number}`[] = ['id-1', 'id-2'];
            """,
        )
        assert(r.isEmpty())
    }

    /** An un-annotated const initialised by a template expression is `string` (tsgo), not
     *  `` `x-${number}` ``; and a literal argument against a template parameter is reported ONCE —
     *  by the general argument check, the B297 walker now standing aside for a decidable template. */
    @Test
    fun `an un-annotated template const is string and an argument row is not doubled`() {
        val r = rows(
            """
            export function g(n: number) { const k = `x-${'$'}{n}`; const z: number = k; return z; }
            declare function f(x: `-${'$'}{string}`): void;
            f('5');
            f('-5');
            """,
        )
        assert(
            r == listOf(
                "1:58 TS2322 Type 'string' is not assignable to type 'number'.",
                "3:3 TS2345 Argument of type '\"5\"' is not assignable to parameter of type '`-${'$'}{string}`'.",
            ),
        )
    }

    /** In a conditional's `extends` the check type is a TYPE, so a plain `string` is decided:
     *  `string extends \`x${string}\`` is false. */
    @Test
    fun `a conditional decides string and templates against a template`() {
        val r = rows(
            """
            type IsT<S> = S extends `x${'$'}{string}` ? true : false;
            export const a: IsT<string> = true;
            export const b: IsT<'xa'> = false;
            export const c: IsT<`x${'$'}{number}`> = false;
            export const d: IsT<`y${'$'}{string}`> = true;
            """,
        )
        assert(
            r == listOf(
                "2:14 TS2322 Type 'true' is not assignable to type 'false'.",
                "3:14 TS2322 Type 'false' is not assignable to type 'true'.",
                "4:14 TS2322 Type 'false' is not assignable to type 'true'.",
                "5:14 TS2322 Type 'true' is not assignable to type 'false'.",
            ),
        )
    }

    /** The spans are PARSED now: an undeclared name inside one is TS2304, a type parameter is in
     *  scope, `infer` binds, `typeof` reads a value. */
    @Test
    fun `a name inside a template span is resolved`() {
        val r = rows(
            """
            export type A = `${'$'}{Undecl}x`;
            export type F<T extends string> = `${'$'}{T}-${'$'}{Lowercase<T>}`;
            export type G<S> = S extends `${'$'}{infer R}x${'$'}{infer Q extends number}` ? [R, Q] : never;
            export type H = `${'$'}{typeof v}`;
            const v = 'a';
            """,
        )
        assert(r == listOf("1:20 TS2304 Cannot find name 'Undecl'."))
    }

    /** A key remap through a template (`as \`get_${K & string}\``) and a template filter. */
    @Test
    fun `a template in a mapped type as clause`() {
        val r = rows(
            """
            type G<T> = { [K in keyof T as `get_${'$'}{K & string}`]: () => T[K] };
            declare const g: G<{ a: number; b: string }>;
            export const g1: number = g.get_a();
            export const g2: number = g.get_b();
            type F<T> = { [K in keyof T as K extends `x${'$'}{string}` ? K : never]: T[K] };
            declare const f: F<{ xa: 1; yb: 2; x: 3 }>;
            export const f1: 2 = f.xa;
            export const f2 = f.yb;
            """,
        )
        assert(
            r == listOf(
                "4:14 TS2322 Type 'string' is not assignable to type 'number'.",
                "7:14 TS2322 Type '1' is not assignable to type '2'.",
                "8:21 TS2339 Property 'yb' does not exist on type 'F<{ xa: 1; yb: 2; x: 3; }>'.",
            ),
        )
    }

    /** The display escapes like tsgo's printer: a tab is `\t`, CRLF is `\r\n`. */
    @Test
    fun `a template displays its escaped texts`() {
        val r = rows(
            """
            declare const t: `${'$'}{string}:\t${'$'}{number}\r\n`;
            export const n: number = t;
            """,
        )
        assert(r == listOf("2:14 TS2322 Type '`${'$'}{string}:\\t${'$'}{number}\\r\\n`' is not assignable to type 'number'."))
    }
}
