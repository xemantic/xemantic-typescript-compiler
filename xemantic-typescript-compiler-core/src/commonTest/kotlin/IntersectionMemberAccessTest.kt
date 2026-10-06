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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.234) round (P18.309): a member read on an INTERSECTION receiver that no constituent has —
 * TS2339 / TS2551 for a property access, TS7053 for a string-literal key — against tsgo 7.0.2
 * (cells under `build/bench/p18309-agent/m2/`; every expected row is tsgo's, 1-based column).
 * The rule is deliberately conservative: a constituent whose member table this checker cannot
 * vouch for (a mapped / utility type such as `Omit<…>`, a nested union, `any`) reports nothing.
 */
class IntersectionMemberAccessTest {

    private val strict = "// @strict: true\n// @target: es2020\n// @useRealLibs: true"

    private fun rows(source: String, directives: String = strict): List<String> =
        diagnose(source, directives = directives, fileName = "t.ts")
            .map { d ->
                "${d.line}:${d.character} TS${d.code} ${d.message}" +
                    d.relatedInformation.joinToString("") { " [${it.line}:${it.character} ${it.message}]" }
            }
            .sorted()

    private fun missing(line: Int, col: Int, name: String, type: String) =
        "$line:$col TS2339 Property '$name' does not exist on type '$type'."

    @Test
    fun `a property no object constituent has is TS2339 on the alias, the literal and the interface form`() {
        val r = rows(
            """
            type A = { a: number };
            type B = { b: string };
            interface I { i: number }
            interface J { j: string }
            declare const ij: I & J;
            export function f(x: A & B) {
                return [x.a, x.b, x.c, ij.i, ij.k];
            }
            export const v = ({ a: 1 } as { a: number } & { b: number }).c;
            """,
        )
        assert(
            r == listOf(
                missing(7, 25, "c", "A & B"),
                missing(7, 37, "k", "I & J"),
                missing(9, 62, "c", "{ a: number; } & { b: number; }"),
            ).sorted(),
        )
    }

    @Test
    fun `a type parameter, a class instance, an array, a function and an empty-object brand constituent`() {
        val r = rows(
            """
            class C { p = 1 }
            declare const c: C & { q: number };
            declare const arr: number[] & { tag: 1 };
            declare const fn: (() => void) & { tag: 1 };
            export function f<T>(x: T & { a: number }, y: T & {}, s: string & {}) {
                return [x.a, x.nope, y.nope, s.length, s.nope, c.p, c.q, c.r, arr.length, arr.nope, fn.call, fn.nope];
            }
            """,
        )
        assert(
            r == listOf(
                missing(6, 20, "nope", "T & { a: number; }"),
                missing(6, 28, "nope", "T & {}"),
                missing(6, 46, "nope", "string & {}"),
                missing(6, 64, "r", "C & { q: number; }"),
                missing(6, 83, "nope", "number[] & { tag: 1; }"),
                missing(6, 101, "nope", "(() => void) & { tag: 1; }"),
            ).sorted(),
        )
    }

    @Test
    fun `a write target and a nested intersection alias are reported`() {
        val r = rows(
            """
            type A = ({ a: number } & { b: number }) & { c: number };
            declare const x: A;
            declare const y: { a: number } & { b: number };
            y.c = 1;
            export const v = [x.a, x.c, x.d];
            """,
        )
        assert(r == listOf(missing(4, 3, "c", "{ a: number; } & { b: number; }"), missing(5, 31, "d", "A")).sorted())
    }

    @Test
    fun `a close name is TS2551 with the related row on the property form only`() {
        val r = rows(
            """
            declare const x: { alpha: number } & { beta: string };
            export const v = x.alpah;
            export const w = x['alpah'];
            """,
        )
        val ts2551 = "TS2551 Property 'alpah' does not exist on type '{ alpha: number; } & { beta: string; }'. Did you mean 'alpha'?"
        assert(r == listOf("2:20 $ts2551 [1:20 'alpha' is declared here.]", "3:20 $ts2551"))
    }

    @Test
    fun `a string-literal key no constituent has is TS7053 with its chain`() {
        val d = diagnose(
            """
            type A = { a: number } & { b: string };
            declare const x: A;
            const k = 'c' as const;
            export const v = [x['c'], x['a'], x[k]];
            """,
            directives = strict, fileName = "t.ts",
        )
        val r = d.map { "${it.line}:${it.character} TS${it.code} ${it.message} | ${it.messageChain.joinToString()}" }
        val msg = "TS7053 Element implicitly has an 'any' type because expression of type '\"c\"' can't be used to index type 'A'. |   Property 'c' does not exist on type 'A'."
        assert(r == listOf("4:19 $msg", "4:35 $msg"))
    }

    /** `n['c']` on a numeric-index constituent is tsgo's TS7015 (not modelled here), never TS7053. */
    @Test
    fun `negative control - an index signature, any, an in guard and the Object prototype`() {
        val r = rows(
            """
            declare const s: { a: number } & { [k: string]: unknown };
            declare const n: { a: number } & { [i: number]: string };
            declare const y: any & { a: number };
            declare const z: { a: number } & { b: string };
            export function f() {
                if ('c' in z) { return z.c; }
                return [s.zzz, s['yyy'], n[0], y.nope, z.toString(), z.hasOwnProperty('a'), n['c']];
            }
            """,
        )
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - TS7053 is not reported without noImplicitAny`() {
        val r = rows(
            """
            declare const x: { a: number } & { b: number };
            export const v = [x['c'], x['alpah']];
            """,
            "// @strict: true\n// @noImplicitAny: false\n// @target: es2020\n// @useRealLibs: true",
        )
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - a getter and a setter constituent together have the property`() {
        val r = rows(
            """
            type I2 = { get a(): number } & { set a(v: number) };
            declare let i2: I2;
            i2.a = 2;
            """,
        )
        assert(r.isEmpty())
    }

    @Test
    fun `residue - a mapped or utility constituent is not trusted and reports nothing`() {
        // tsgo: TS2339 for `c` and `zz` on 'Omit<O, "c"> & { d: 1; }' (3,31) and (3,36); this checker's
        // `Omit` materialization is known to drop members (measured on `ky`), so it stays silent.
        val r = rows(
            """
            interface O { a: number; b: string; c: boolean }
            declare const x: Omit<O, 'c'> & { d: 1 };
            export const v = [x.a, x.d, x.c, x.zz];
            """,
        )
        assert(r.isEmpty())
    }
}
