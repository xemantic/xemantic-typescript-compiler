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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.64) AN `&&` IF-CONDITION NARROWED **NEITHER** OPERAND, AND THE GAP IS AT THE
 * ASSIGNMENT AND RETURN READERS ONLY.
 *
 * The flow walk handles `&&` correctly, so a MEMBER-ACCESS reader and an ARGUMENT reader
 * are both right; round 784's gate confines [Checker.checkReturnAssignabilityCore]'s (and
 * the assignment walker's) narrowing block to an object-ish/union target, so for a
 * PRIMITIVE target those two readers fall back to [Checker.currentLocalTypes] — which the
 * legacy if-arm machinery fills through [Checker.extractNullNarrowing], a helper that
 * answers ONE `(name, type)` pair and has no `&&` arm at all. A DECLARATION with the same
 * primitive target narrows one line away, which is exactly why the gap was invisible.
 *
 * [Checker.extractNarrowingsFromCondition] decomposes the (left-nested, iteratively
 * flattened) `&&` spine and returns a LIST, at most one entry per name. `||` is
 * deliberately NOT decomposed — a disjunct tells you nothing about the then-branch — and
 * that refusal is a control below, as is an `&&` of two booleans that narrows nothing.
 *
 * Measured on tsc's own sources: this removes the three `sourcemap.ts:164/165/166` rows
 * from the (CHK.63) price, 11 -> 8. tsc 7.0.2 is silent on every positive here and reports
 * both negative controls.
 *
 * RESIDUE, deliberately not pinned: a conjunct that narrows a LATER conjunct's subject
 * (`x !== undefined && typeof x === "number"`) is extracted against the UN-narrowed map,
 * because chaining would need the narrowings installed between conjuncts.
 */
class AndConditionNarrowsEveryOperandTest {

    private val prelude = """
        let zzzP1: number = 0;
        let zzzP2: number = 0;
        let zzzP3: number = 0;
        let zzzQs: string = "";
    """.trimIndent() + "\n"

    /** POSITIVE — the ASSIGNMENT reader, both operands of a two-conjunct `&&`. */
    @Test
    fun `an and-condition narrows both operands at the assignment reader`() {
        diagnose(
            prelude +
                "function zzzB3(zzzS: number | string, zzzT: number | string): void {\n" +
                "  if (typeof zzzS === \"number\" && typeof zzzT === \"number\") { zzzP1 = zzzS; zzzP2 = zzzT; }\n" +
                "}",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    /** POSITIVE — the RETURN reader. */
    @Test
    fun `an and-condition narrows at the return reader`() {
        diagnose(
            prelude +
                "function zzzB4(zzzS: number | string, zzzT: boolean): number {\n" +
                "  if (typeof zzzS === \"number\" && zzzT) { return zzzS; }\n" +
                "  return 0;\n" +
                "}",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    /** POSITIVE — the three-conjunct `sourcemap.ts:164/165/166` shape. */
    @Test
    fun `a three-conjunct and-condition narrows every operand at the assignment reader`() {
        diagnose(
            prelude +
                "function zzzB5(zzzS: number | string, zzzT: number | string, zzzU: number | string): void {\n" +
                "  if (typeof zzzS === \"number\" && typeof zzzT === \"number\" && typeof zzzU === \"number\") {\n" +
                "    zzzP1 = zzzS; zzzP2 = zzzT; zzzP3 = zzzU;\n" +
                "  }\n" +
                "}",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    /** POSITIVE — the narrowing operand may be the RIGHT conjunct. */
    @Test
    fun `an and-condition narrows when the guard is the right conjunct`() {
        diagnose(
            prelude +
                "function zzzB6(zzzS: number | string, zzzT: boolean): void {\n" +
                "  if (zzzT && typeof zzzS === \"number\") { zzzP1 = zzzS; }\n" +
                "}",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    /**
     * POSITIVE, and the VALUE pin — the narrowed type is NAMED. Before the fix this row
     * read `Type 'string | number' is not assignable to type 'string'`; tsc 7.0.2 says
     * `Type 'number' is not assignable to type 'string'`.
     */
    @Test
    fun `the type named at the assignment reader is the narrowed one`() {
        val rows = diagnose(
            prelude +
                "function zzzB7(zzzS: number | string, zzzT: boolean): void {\n" +
                "  if (typeof zzzS === \"number\" && zzzT) { zzzQs = zzzS; }\n" +
                "}",
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'number' is not assignable to type 'string'.")
    }

    /** CONTROL — a single condition already narrowed and must keep doing so. */
    @Test
    fun `control - a single condition still narrows at the assignment reader`() {
        diagnose(
            prelude +
                "function zzzB1(zzzS: number | string): void {\n" +
                "  if (typeof zzzS === \"number\") { zzzP1 = zzzS; }\n" +
                "}",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    /** CONTROL — the DECLARATION reader already narrowed under `&&` and must keep doing so. */
    @Test
    fun `control - the declaration reader still narrows under an and-condition`() {
        diagnose(
            prelude +
                "function zzzB8(zzzS: number | string, zzzT: boolean): void {\n" +
                "  if (typeof zzzS === \"number\" && zzzT) { const zzzR: number = zzzS; }\n" +
                "}",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    /**
     * NEGATIVE CONTROL — an `&&` of two plain booleans narrows nothing, so the assignment
     * is still an error. A decomposition that narrowed unconditionally would delete this
     * row. tsc 7.0.2 reports it.
     */
    @Test
    fun `negative control - an and-condition that narrows nothing leaves the row`() {
        val rows = diagnose(
            prelude +
                "function zzzB9(zzzS: number | string, zzzT: boolean, zzzU: boolean): void {\n" +
                "  if (zzzT && zzzU) { zzzP1 = zzzS; }\n" +
                "}",
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string | number' is not assignable to type 'number'.")
    }

    /**
     * POSITIVE GUARD — a `typeof x === "object"` conjunct must NOT be installed.
     * [Checker.typeofTypeGuardToType] answers `anyType` for `"object"` and `"function"`
     * ("too broad to narrow precisely"), so decomposing that conjunct would install `any`
     * — a WIDENING — and `any` is assignable to everything, which DELETES this true
     * positive. Measured on tsc's own sources before the refusal was added: 13 captured
     * hovers went from tsc's own `object`/`unknown` to `any`.
     *
     * RESIDUE, deliberately NOT pinned as passing: the SINGLE-condition form of the same
     * shape (`if (typeof zzzO === "object") { zzzQs = zzzO; }`) is SILENT here where tsc
     * 7.0.2 reports it — that is the shipped `anyType` narrowing on the pre-existing path,
     * which this round does not touch. And our rendering of the row below is the
     * UN-narrowed `string | object` where tsc says `object`.
     */
    @Test
    fun `a typeof-object conjunct is refused because any is a widening`() {
        val rows = diagnose(
            prelude +
                "function zzzBb(zzzO: object | string, zzzT: boolean): void {\n" +
                "  if (typeof zzzO === \"object\" && zzzT) { zzzQs = zzzO; }\n" +
                "}",
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string | object' is not assignable to type 'string'.")
    }

    /**
     * NEGATIVE CONTROL — `||` is NOT decomposed: a disjunct says nothing about the
     * then-branch, so the row survives. tsc 7.0.2 reports it.
     */
    @Test
    fun `negative control - an or-condition is not decomposed`() {
        val rows = diagnose(
            prelude +
                "function zzzBa(zzzS: number | string, zzzT: boolean): void {\n" +
                "  if (typeof zzzS === \"number\" || zzzT) { zzzP1 = zzzS; }\n" +
                "}",
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string | number' is not assignable to type 'number'.")
    }
}
