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
 * (P18.274) (LIBS.2): the date-fns residue mechanisms, each against tsgo 7.0.2 —
 * F11 (an exhaustive `switch` over a literal union spelled through an ALIAS), OPTDISC
 * (`v.type === "other"` over a constituent with `type?: undefined`), STARSAME (two
 * `export *` reaching ONE binding) and M2 (an unresolved bare package under NodeNext).
 */
class DateFnsResidueTest {

    private fun rows(source: String, directives: String = "// @strict: true"): List<String> =
        diagnose(source, directives).map { "${it.fileName?.substringAfterLast('/')}:${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `an exhaustive switch over an aliased literal union terminates`() {
        val r = rows("""
            type Day = 0 | 1 | 2;
            type S = "a" | "b";
            type B = boolean;
            type N = 1 | null | undefined;
            export function f1(d: Day): string { switch (d) { case 0: return "x"; case 1: case 2: return "y"; } }
            export function f3(s: S): number { switch (s) { case "a": return 1; case "b": return 2; } }
            export function f4(b: B): number { switch (b) { case true: return 1; case false: return 2; } }
            export function f5(n: N): number { switch (n) { case 1: return 1; case null: return 2; case undefined: return 3; } }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - a non-exhaustive or non-unit aliased switch still reports TS2366`() {
        val r = rows("""
            type Day = 0 | 1 | 2;
            type N = 1 | null | undefined;
            type Wide = 0 | 1 | number;
            export function f2(d: Day): string { switch (d) { case 0: return "x"; case 1: return "y"; } }
            export function f6(n: N): number { switch (n) { case 1: return 1; case null: return 2; } }
            export function f7(w: Wide): number { switch (w) { case 0: return 1; case 1: return 2; } }
            export function f8(d: Day): string { switch (d) { case 0: return "x"; case 1: break; case 2: return "y"; } }
        """)
        assert(r == listOf(
            "t.ts:4:29 TS2366 Function lacks ending return statement and return type does not include 'undefined'.",
            "t.ts:5:27 TS2366 Function lacks ending return statement and return type does not include 'undefined'.",
            "t.ts:6:30 TS2366 Function lacks ending return statement and return type does not include 'undefined'.",
            "t.ts:7:29 TS2366 Function lacks ending return statement and return type does not include 'undefined'.",
        ))
    }

    @Test
    fun `an equality with a value literal drops a constituent whose discriminant is undefined`() {
        val r = rows("""
            type V = { type?: undefined; one: string } | { type: "other"; other: string };
            type V2 = { type: undefined; one: string } | { type: "other"; other: string };
            export function f(v: V): string {
              if (v.type === "other") return v.other;
              return v.one;
            }
            export function g(v: V2): string {
              if (v.type === "other") return v.other;
              return v.one;
            }
            export function k(v: V): string {
              if (v.type !== "other") return v.one;
              return v.other;
            }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - the undefined-discriminant constituent is still the only one on the false branch`() {
        val r = rows("""
            type V = { type?: undefined; one: string } | { type: "other"; other: string };
            export function f(v: V): string {
              if (v.type === "other") return v.one;
              return v.other;
            }
        """)
        assert(r == listOf(
            "t.ts:3:36 TS2339 Property 'one' does not exist on type '{ type: \"other\"; other: string; }'.",
            "t.ts:4:12 TS2339 Property 'other' does not exist on type '{ type?: undefined; one: string; }'.",
        ))
    }

    @Test
    fun `two export stars reaching one binding are not ambiguous`() {
        val r = rows("""
            // @Filename: lf.ts
            export const lf: Record<string, number> = {};
            // @Filename: a.ts
            import { lf } from "./lf";
            export { lf };
            // @Filename: b.ts
            import { lf } from "./lf";
            export { lf };
            // @Filename: t.ts
            export * from "./a";
            export * from "./b";
        """, "// @strict: true\n// @module: esnext")
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - two export stars reaching different bindings stay ambiguous`() {
        val r = rows("""
            // @Filename: lf.ts
            export const lf = 1;
            // @Filename: a.ts
            import { lf } from "./lf";
            export { lf };
            // @Filename: e.ts
            export const lf = 9;
            // @Filename: t.ts
            export * from "./a";
            export * from "./e";
        """, "// @strict: true\n// @module: esnext")
        assert(r == listOf(
            "t.ts:2:1 TS2308 Module \"./a\" has already exported a member named 'lf'. Consider explicitly re-exporting to resolve the ambiguity.",
        ))
    }

    @Test
    fun `an unresolved bare package under nodenext is TS2307`() {
        val r = rows("""
            // @Filename: other.ts
            export const o = 1;
            // @Filename: t.ts
            import { vi } from "vitest";
            import { o } from "./other.js";
            export const q = [vi, o];
        """, "// @strict: true\n// @module: nodenext")
        assert(r == listOf(
            "t.ts:1:20 TS2307 Cannot find module 'vitest' or its corresponding type declarations.",
        ))
    }
}
