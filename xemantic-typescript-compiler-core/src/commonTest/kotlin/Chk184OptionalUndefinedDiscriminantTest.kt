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
 * (CHK.184) — a union whose members carry `?: undefined` properties is narrowed by a condition
 * on that DISCRIMINANT property: `{ a: T; b?: undefined } | { b: T; a?: undefined }` under
 * `w.a` / `!w.a` / `w.a === undefined` / `typeof w.a` / `'a' in w`. [DiscriminantFactsNarrowing]
 * is tsgo's `narrowTypeByDiscriminant` with the optionality fold this checker lacked; the `in`
 * operator keeps a member whose property is OPTIONAL on the `!in` side (tsgo
 * `isTypePresencePossible`); B81.1c's receiver-narrowing suppression asks EVERY constituent of a
 * narrowed union; and (CHK.180)'s written-union refusal in [WrittenReceiverTypes] is lifted.
 *
 * Every expectation is tsgo 7.0.2's row over the same text (`build/bench/p18240-agent/cells*`,
 * each cell's `export {};` first line replaced by the directive line), `line,col: TScode message`.
 *
 * Residue NOT closed (recorded, not pinned): `'a' in w` over `a?: never` without
 * exactOptionalPropertyTypes — tsgo reads `w.a` as `string[] | undefined` (TS18048), we read the
 * optional `never` member as contributing nothing and stay silent (pre-existing).
 */
class Chk184OptionalUndefinedDiscriminantTest {

    private fun rows(body: String, directives: String = "// @strict: true"): List<String> =
        diagnose(body.trimIndent(), directives).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }

    private val u2 = "type U = { a: string[]; b?: undefined } | { b: string[]; a?: undefined };"

    private fun u2Param(guard: String) = rows("$u2\nfunction p(w: U) { $guard }\nexport {}")

    // --- truthiness: tsgo-silent controls (each was a false TS18048 'w.b') ------------------

    @Test
    fun `truthy ternary on a parameter is silent`() {
        assert(u2Param("w.a ? w.a.valueOf() : w.b.valueOf();").isEmpty())
    }

    @Test
    fun `if else on a parameter is silent`() {
        assert(u2Param("if (w.a) { w.a.valueOf() } else { w.b.valueOf() }").isEmpty())
    }

    @Test
    fun `negated guard on a parameter is silent`() {
        assert(u2Param("if (!w.a) { w.b.valueOf() } else { w.a.valueOf() }").isEmpty())
    }

    @Test
    fun `logical and or on a parameter are silent`() {
        assert(u2Param("!w.a && w.b.valueOf();").isEmpty())
        assert(u2Param("w.a || w.b.valueOf();").isEmpty())
    }

    @Test
    fun `truthy ternary on a file-level const is silent`() {
        assert(rows("$u2\ndeclare const w: U;\nw.a ? w.a.valueOf() : w.b.valueOf();\nexport {}").isEmpty())
    }

    @Test
    fun `required undefined member narrows too`() {
        val r = rows("type U = { a: string[]; b: undefined } | { b: string[]; a: undefined };\n" +
            "function p(w: U) { w.a ? w.a.valueOf() : w.b.valueOf(); }\nexport {}")
        assert(r.isEmpty())
    }

    @Test
    fun `optional never member folds to undefined`() {
        val r = rows("type U = { a: string[]; b?: never } | { b: string[]; a?: never };\n" +
            "function p(w: U) { w.a ? w.a.valueOf() : w.b.valueOf(); }\nexport {}")
        assert(r.isEmpty())
    }

    // --- equality and typeof ---------------------------------------------------------------

    @Test
    fun `strict equality with undefined is silent`() {
        assert(u2Param("w.a === undefined ? w.b.valueOf() : w.a.valueOf();").isEmpty())
        assert(u2Param("w.a !== undefined ? w.a.valueOf() : w.b.valueOf();").isEmpty())
    }

    @Test
    fun `typeof undefined guard is silent`() {
        assert(u2Param("typeof w.a === 'undefined' ? w.b.valueOf() : w.a.valueOf();").isEmpty())
    }

    // --- tsgo rows that must STILL report ---------------------------------------------------

    @Test
    fun `a primitive member is not discriminated by truthiness`() {
        val r = rows("type U = { a: string; b?: undefined } | { b: string; a?: undefined };\n" +
            "function p(w: U) { w.a ? w.a.valueOf() : w.b.valueOf(); }\nexport {}")
        assert(r == listOf("2,42: TS18048 'w.b' is possibly 'undefined'."))
    }

    @Test
    fun `three members keep the undefined of the survivors`() {
        val r = rows("type U = { a: string[]; b?: undefined; c?: undefined } | { b: string[]; a?: undefined; c?: undefined } | { c: string[]; a?: undefined; b?: undefined };\n" +
            "function p(w: U) { if (!w.a) { w.b.valueOf() } else { w.a.valueOf() } }\nexport {}")
        assert(r == listOf("2,32: TS18048 'w.b' is possibly 'undefined'."))
    }

    // --- the `in` operator -----------------------------------------------------------------

    @Test
    fun `not in keeps the member whose property is optional`() {
        val r = u2Param("'a' in w ? w.a.valueOf() : w.b.valueOf();")
        assert(r == listOf("2,31: TS18048 'w.a' is possibly 'undefined'."))
    }

    @Test
    fun `not in over a single object with an optional property keeps it`() {
        val r = rows("type O = { a?: string; b: number };\n" +
            "function p(o: O) { if (!('a' in o)) { o.b.valueOf(); const q: string = o; } }\nexport {}")
        assert(r == listOf("2,60: TS2322 Type 'O' is not assignable to type 'string'."))
    }

    @Test
    fun `not in over optional never is silent under exactOptionalPropertyTypes`() {
        val r = rows("type U = { a: string[]; b?: never } | { b: string[]; a?: never };\n" +
            "function p(w: U) { 'a' in w ? w.a.valueOf() : w.b.valueOf(); }\nexport {}",
            "// @strict: true\n// @exactOptionalPropertyTypes: true")
        assert(r.isEmpty())
    }

    // --- body locals: (CHK.180)'s written-union refusal lifted ------------------------------

    @Test
    fun `body-local const truthy ternary is silent`() {
        val r = rows("$u2\ndeclare function mk(): U;\nfunction p() { const w: U = mk(); w.a ? w.a.valueOf() : w.b.valueOf(); }\nexport {}")
        assert(r.isEmpty())
    }

    @Test
    fun `body-local const unguarded read reports as tsgo`() {
        val r = rows("$u2\ndeclare function mk(): U;\nfunction p() { const w: U = mk(); w.b.valueOf(); }\nexport {}")
        assert(r == listOf("3,35: TS18048 'w.b' is possibly 'undefined'."))
    }
}
