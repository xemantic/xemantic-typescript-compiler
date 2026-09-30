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
 * (CHK.177) round S1 — a relation-error / TS2339 SOURCE whose reference is declared with a
 * union-alias annotation displays the ALIAS name exactly when the shown type has the alias's
 * member set (plus an annotated or optional-parameter nullish part), as tsgo 7.0.2 does; a
 * narrowed subset, an inline annotation and a generalized literal keep the expansion. Every
 * expectation is tsgo 7.0.2's own text for the census cell named in the KDoc
 * (`build/scratch-p18232-census/cells`, `build/bench/p18233-agent/cells`). See
 * `AliasCarrierDisplay.kt`.
 */
class AliasCarrierSourceDisplayTest {

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    /** tsgo 7.0.2, cell `m01`. */
    @Test
    fun `a file-level const read names its alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; declare const u: U; const n: number = u; export {}""") == listOf(
            "1:96 TS2322 Type 'U' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `m02`. */
    @Test
    fun `a parameter read names its alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; function f(u: U) { const n: number = u } export {}""") == listOf(
            "1:95 TS2322 Type 'U' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `f01`. */
    @Test
    fun `a parameter narrowed by if-else and joined back names its alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; function f(u: U) { if (u.k === 'a') { } else { } const n: number = u } export {}""") == listOf(
            "1:125 TS2322 Type 'U' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `f12`. */
    @Test
    fun `a switch that joins back names its alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; function f(u: U) { switch (u.k) { case 'a': break; case 'b': break } const n: number = u } export {}""") == listOf(
            "1:145 TS2322 Type 'U' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `f07`. */
    @Test
    fun `a let assigned the alias type names its alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; declare function h(): U; function f() { let u: U; u = h(); const n: number = u } export {}""") == listOf(
            "1:135 TS2322 Type 'U' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `h03`. */
    @Test
    fun `a let initialized from an inline union names its alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; declare function mk2(): A | B; function f() { let u: U = mk2(); const n: number = u } export {}""") == listOf(
            "1:140 TS2322 Type 'U' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `f08`. */
    @Test
    fun `a returned parameter names its alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; function r(u: U): number { return u } export {}""") == listOf(
            "1:97 TS2322 Type 'U' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `f16`. */
    @Test
    fun `an assignment source names its alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; declare const u: U; let n: number; n = u; export {}""") == listOf(
            "1:105 TS2322 Type 'U' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `m13`. */
    @Test
    fun `an argument names its alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; declare function take(n: number): void; declare const u: U; take(u); export {}""") == listOf(
            "1:135 TS2345 Argument of type 'U' is not assignable to parameter of type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `h10`. */
    @Test
    fun `a never target keeps the alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; function f(u: U) { const n: never = u } export {}""") == listOf(
            "1:95 TS2322 Type 'U' is not assignable to type 'never'.",
        ))
    }

    /** tsgo 7.0.2, cell `f09`. */
    @Test
    fun `a literal alias against a literal target keeps its name`() {
        assert(rows("""type K = 'a' | 'b'; declare const k: K; const n: 'c' = k; export {}""") == listOf(
            "1:47 TS2322 Type 'K' is not assignable to type '\"c\"'.",
        ))
    }

    /** tsgo 7.0.2, cell `h01`. */
    @Test
    fun `an alias plus undefined annotation names alias pipe undefined`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; function f(u: U | undefined) { const n: number = u } export {}""") == listOf(
            "1:107 TS2322 Type 'U | undefined' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `x01`. */
    @Test
    fun `an alias plus null and undefined keeps nullish order`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; function f(u: U | undefined | null) { const n: number = u } export {}""") == listOf(
            "1:114 TS2322 Type 'U | null | undefined' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `x03`. */
    @Test
    fun `an optional parameter names alias pipe undefined`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; function f(u?: U) { const n: number = u } export {}""") == listOf(
            "1:96 TS2322 Type 'U | undefined' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `h06`. */
    @Test
    fun `null narrowed off names the bare alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; function f(u: U | null) { if (u === null) return; const n: number = u } export {}""") == listOf(
            "1:126 TS2322 Type 'U' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `f13`. */
    @Test
    fun `undefined narrowed off by truthiness names the bare alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; function f(u: U | undefined) { if (!u) return; const n: number = u } export {}""") == listOf(
            "1:123 TS2322 Type 'U' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `x06`. */
    @Test
    fun `parens and non-null assertion are unwrapped`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; function f(u: (U)) { const n: number = u!; const m: number = (u) } export {}""") == listOf(
            "1:97 TS2322 Type 'U' is not assignable to type 'number'.",
            "1:119 TS2322 Type 'U' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `x07`. */
    @Test
    fun `an argument with a nullable alias annotation names alias pipe undefined`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; declare function take(n: number): void; function f(u: U | undefined) { take(u) } export {}""") == listOf(
            "1:146 TS2345 Argument of type 'U | undefined' is not assignable to parameter of type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `m12`. */
    @Test
    fun `TS2339 on a parameter receiver names its alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; function f(u: U) { return u.a } export {}""") == listOf(
            "1:98 TS2339 Property 'a' does not exist on type 'U'.",
        ))
    }

    /** tsgo 7.0.2, cell `m19`. */
    @Test
    fun `TS2339 after a no-op if names its alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; declare const c: boolean; function f(u: U) { if (c) { } return u.a } export {}""") == listOf(
            "1:135 TS2339 Property 'a' does not exist on type 'U'.",
        ))
    }

    /** tsgo 7.0.2, cell `g07`. */
    @Test
    fun `TS2339 on a narrowed nullable parameter names its alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type C = { k: 'c' }; type U = A | B; function f(u: U | undefined) { if (!u) return; return u.a } export {}""") == listOf(
            "1:147 TS2339 Property 'a' does not exist on type 'U'.",
        ))
    }

    /** tsgo 7.0.2, cell `x08`. */
    @Test
    fun `TS2339 reports on the non-null part and names the bare alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; function f(u: U | undefined) { return u.a } export {}""") == listOf(
            "1:108 TS18048 'u' is possibly 'undefined'.",
            "1:110 TS2339 Property 'a' does not exist on type 'U'.",
        ))
    }

    /** tsgo 7.0.2, cell `m09`. */
    @Test
    fun `two aliases with the same members each keep their own name`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; type V = A | B; declare const u: U; const n: number = u; declare const v: V; const m: number = v; export {}""") == listOf(
            "1:112 TS2322 Type 'U' is not assignable to type 'number'.",
            "1:153 TS2322 Type 'V' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `x09`. */
    @Test
    fun `an interface alias receiver keeps its name`() {
        assert(rows("""interface IA { k: 'a'; a: string } interface IB { k: 'b' } type AB = IA | IB; declare const u: AB; u.a; function g(w: AB) { return w.a } export {}""") == listOf(
            "1:102 TS2339 Property 'a' does not exist on type 'AB'.",
            "1:134 TS2339 Property 'a' does not exist on type 'AB'.",
        ))
    }

    /** tsgo 7.0.2, cell `f03`. */
    @Test
    fun `negative control - a narrowed subset is not named`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type C = { k: 'c' }; type U = A | B | C; function f(u: U) { if (u.k === 'c') return; const n: number = u } export {}""") == listOf(
            "1:145 TS2322 Type 'A | B' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `f04`. */
    @Test
    fun `negative control - a narrowed subset is not named at TS2322 or TS2339`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type C = { k: 'c' }; type U = A | B | C; declare const u: U; if (u.k === 'c') throw 0; const n: number = u; u.a; export {}""") == listOf(
            "1:147 TS2322 Type 'A | B' is not assignable to type 'number'.",
            "1:164 TS2339 Property 'a' does not exist on type 'A | B'.",
        ))
    }

    /** tsgo 7.0.2, cell `f06`. */
    @Test
    fun `negative control - an assignment-narrowed single member is not named`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; declare const a: A; function f() { let u: U = a; const n: number = u } export {}""") == listOf(
            "1:125 TS2322 Type 'A' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `m10`. */
    @Test
    fun `negative control - an inline union annotation is not named`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; declare const x: A | B; const m: number = x; declare const u: U; const n: number = u; export {}""") == listOf(
            "1:100 TS2322 Type 'A | B' is not assignable to type 'number'.",
            "1:141 TS2322 Type 'U' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `m07`. */
    @Test
    fun `negative control - a generalized literal alias is not named`() {
        assert(rows("""type K = 'a' | 'b'; declare const k: K; const n: number = k; export {}""") == listOf(
            "1:47 TS2322 Type 'string' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `g04`. */
    @Test
    fun `negative control - a shadowing parameter names its own alias not the outer const`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type C = { k: 'c' }; type U = A | B; type V = A | B | C; declare const u: V; function f(u: U) { return u.a } export {}""") == listOf(
            "1:159 TS2339 Property 'a' does not exist on type 'U'.",
        ))
    }

    /** tsgo 7.0.2, cell `g05`. */
    @Test
    fun `negative control - the mirrored shadowing names the parameter alias`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type C = { k: 'c' }; type U = A | B; type V = A | B | C; declare const u: U; function f(u: V) { return u.a } export {}""") == listOf(
            "1:159 TS2339 Property 'a' does not exist on type 'V'.",
        ))
    }

    /** tsgo 7.0.2, cell `m15`. */
    @Test
    fun `negative control - an interface alias still names through B416`() {
        assert(rows("""interface IA { k: 'a'; a: string } interface IB { k: 'b' } type U = IA | IB; declare const u: U; const n: number = u; export {}""") == listOf(
            "1:104 TS2322 Type 'U' is not assignable to type 'number'.",
        ))
    }

    /** tsgo 7.0.2, cell `x10`. */
    @Test
    fun `negative control - an inner parameter with an inline annotation shadows an aliased outer one`() {
        assert(rows("""type A = { k: 'a', a: string }; type B = { k: 'b' }; type U = A | B; function f(u: U) { function g(u: A | B) { const n: number = u } } export {}""") == listOf(
            "1:118 TS2322 Type 'A | B' is not assignable to type 'number'.",
        ))
    }

    private fun fileRows(source: String): List<String> =
        diagnose(source).map {
            "${it.fileName?.substringAfterLast('/')} ${it.line}:${it.character} TS${it.code} ${it.message}"
        }

    /**
     * tsgo 7.0.2, cell `y01`: a GLOBAL script declaration read from another file. The lexical walk finds
     * no binding and the name resolves through `globals`; the pre-(P18.233) TS2339 carrier named it this
     * way, so losing it would have been a display regression.
     */
    @Test
    fun `a global script const read from another file names its alias`() {
        assert(fileRows("""
            // @Filename: /proj/a.ts
            type A = { a: 1 };
            type B = { b: 2 };
            type U = A | B;
            declare const u: U;
            // @Filename: /proj/b.ts
            const n: number = u;
            u.c;
        """.trimIndent()) == listOf(
            "b.ts 1:7 TS2322 Type 'U' is not assignable to type 'number'.",
            "b.ts 2:3 TS2339 Property 'c' does not exist on type 'U'.",
        ))
    }

    /** tsgo 7.0.2, cell `y02`: an IMPORTED binding resolves through the import to its declaration. */
    @Test
    fun `an imported const names its alias`() {
        assert(fileRows("""
            // @Filename: /proj/a.ts
            type A = { a: 1 };
            type B = { b: 2 };
            export type U = A | B;
            export declare const u: U;
            // @Filename: /proj/b.ts
            import { u } from "./a";
            const n: number = u;
            u.c;
        """.trimIndent()) == listOf(
            "b.ts 2:7 TS2322 Type 'U' is not assignable to type 'number'.",
            "b.ts 3:3 TS2339 Property 'c' does not exist on type 'U'.",
        ))
    }
}
