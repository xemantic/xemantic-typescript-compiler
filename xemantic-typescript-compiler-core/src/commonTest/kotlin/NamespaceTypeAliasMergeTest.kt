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
 * (P18.292) F10 — a `type X` alias and a same-named `namespace X` are ONE symbol in tsgo's
 * binder (`TypeAliasExcludes` is `Type` and a namespace is neither), so `X.Member` in a
 * type position resolves through the namespace where this binder kept two symbols and the
 * last one written won the scope — TS2702 whenever the alias came second (type-fest's
 * `export namespace PackageJson { … } export type PackageJson = …`, 25 rows).
 *
 * The merge has three consequences, each measured against `tools/tsgo-7.0.2/lib/tsc`:
 * a VALUE read of a value-less merged name is TS2708 (tsgo asks the namespace question
 * before the type one) — `typeof X` included; the VALUE of a merged name is the
 * namespace, never the alias's type (it types `any` here, as a plain namespace does);
 * and a namespace in a `.d.ts` is an EXPORT CONTEXT, so its un-`export`ed members are
 * exported (tsgo's `setExportContextFlag`) — the TS2694 that surfaced once the TS2702
 * stopped hiding it.
 */
class NamespaceTypeAliasMergeTest {

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `namespace then alias - the alias member reference resolves through the namespace`() {
        val r = rows("""
            export namespace P { export type Person = { name: string } }
            export type P = { author?: P.Person };
            declare const p: P; export const a: P.Person | undefined = p.author;
            const bad: P.Person = { name: 1 };
        """)
        assert(r == listOf("4:25 TS2322 Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `alias then namespace is unchanged`() {
        val r = rows("""
            export type P = { author?: P.Person };
            export namespace P { export type Person = { name: string } }
            const bad: P.Person = { name: 1 };
        """)
        assert(r == listOf("3:25 TS2322 Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `a generic alias merged with a namespace keeps its type parameters`() {
        val r = rows("""
            export namespace P { export type Opt = boolean }
            export type P<T> = { v: T; o?: P.Opt };
            const x: P<number> = { v: "s" }; const y: P.Opt = 1;
        """)
        assert(r == listOf(
            "3:24 TS2322 Type 'string' is not assignable to type 'number'.",
            "3:40 TS2322 Type 'number' is not assignable to type 'boolean'.",
        ))
    }

    @Test
    fun `script-file alias and namespace merge too`() {
        val r = rows("""
            namespace G { export type Sub = string }
            type G = { k: G.Sub };
            const g: G = { k: 1 };
        """)
        assert(r == listOf("3:16 TS2322 Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `negative control - a bare alias used as a namespace is still TS2702`() {
        val r = rows("""
            export type P = { kind: string };
            const v: P.K = 1;
        """)
        assert(r == listOf("2:10 TS2702 'P' only refers to a type, but is being used as a namespace here."))
    }

    @Test
    fun `a value read of a value-less merged name is TS2708 not TS2693`() {
        val r = rows("""
            export type P = { kind: string };
            export namespace P { export type K = string }
            const v = P; const w = P.K; type T = typeof P;
        """)
        assert(r == listOf(
            "3:11 TS2708 Cannot use namespace 'P' as a value.",
            "3:24 TS2708 Cannot use namespace 'P' as a value.",
            "3:45 TS2708 Cannot use namespace 'P' as a value.",
        ))
    }

    @Test
    fun `an interface merged with a value-less namespace reads TS2708 as well`() {
        val r = rows("""
            export interface P { a: number }
            export namespace P { export type K = string }
            type T = typeof P; const v = P;
        """)
        assert(r == listOf(
            "3:17 TS2708 Cannot use namespace 'P' as a value.",
            "3:30 TS2708 Cannot use namespace 'P' as a value.",
        ))
    }

    @Test
    fun `typeof a plain value-less namespace is TS2708`() {
        val r = rows("""
            export namespace N { export type K = string }
            type T = typeof N;
        """)
        assert(r == listOf("2:17 TS2708 Cannot use namespace 'N' as a value."))
    }

    @Test
    fun `negative control - typeof a parameter shadowing the merged name reports nothing about it`() {
        val r = rows("""
            export type P = { a: number };
            export namespace P { export type K = string }
            export function f(P: string) { type U = typeof P; return 1 }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `the value of an alias merged with an instantiated namespace is not the alias type`() {
        val r = rows("""
            export type P = { kind: string };
            export namespace P { export const make = (k: string): P => ({ kind: k }) }
            const m: number = P.make; const q = P; const r: number = q.make;
        """)
        // tsgo also reports `q.make` (3:46) — the namespace object's type, which this
        // checker does not model for any namespace; the false TS2339 on `'P'` is gone.
        assert(r == listOf("3:7 TS2322 Type '(k: string) => P' is not assignable to type 'number'."))
    }

    @Test
    fun `an imported merged name - type and value positions`() {
        val r = rows("""
            // @Filename: /proj/src/pj.ts
            export namespace P { export type Person = { name: string }; export const make = (): P => ({}) }
            export type P = { author?: P.Person };
            // @Filename: /proj/src/t.ts
            import { P } from "./pj";
            const bad: P.Person = { name: 1 }; const q = P; const r: number = q.make;
        """)
        // tsgo also reports `q.make` (2:55, the namespace object's type — unmodelled here
        // for every namespace); before the merge this read TS2702 x2 and a false TS2339.
        assert(r == listOf("2:25 TS2322 Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `a namespace in a declaration file exports its members implicitly`() {
        val r = rows("""
            // @Filename: /proj/src/pj.d.ts
            export namespace P { type Person = { name: string } }
            export type P = { author?: P.Person };
            // @Filename: /proj/src/t.ts
            import type { P } from "./pj";
            const bad: P.Person = { name: 1 };
        """)
        assert(r == listOf("2:25 TS2322 Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `negative control - an export declaration in a declaration-file namespace ends the export context`() {
        val r = rows("""
            // @Filename: /proj/src/pj.d.ts
            export namespace N { type X = string; export type Z = number; export {} }
            // @Filename: /proj/src/t.ts
            import type { N } from "./pj";
            const z: N.Z = "s"; const x: N.X = 1;
        """)
        // tsgo reads the same TS2694 (its namespace display names the declaring file) and
        // the `N.Z` TS2322; it does not resolve the unexported `N.X`, where this checker
        // still does (the 2:27 row, pre-existing and unchanged).
        assert(r.any { it.startsWith("2:32 TS2694 ") && it.endsWith("has no exported member 'X'.") })
        assert(r.contains("2:7 TS2322 Type 'string' is not assignable to type 'number'."))
    }
}
