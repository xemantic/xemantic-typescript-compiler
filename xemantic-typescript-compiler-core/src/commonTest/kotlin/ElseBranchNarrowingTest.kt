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
 * (CHK.157), round P18.189 — the ELSE branch of an `if` is narrowed by the NEGATED condition at the
 * readers that consult `currentLocalTypes` (the ASSIGNMENT, RETURN and object-literal readers under
 * round 784's gate). Before this only the THEN branch was, so rxjs `Subscriber.ts:220`
 * (`if (isFunction(o) || !o) {} else { p = o }`) was an ours-only TS2322 still carrying `null` and
 * the function constituent, while a declaration or an argument in the same branch was right (they
 * ask the flow walk). tsgo narrows the false branch of every guard (`narrowType*` with
 * `assumeTrue = false`), and the false branch of `a || b` is `a` false THEN `b` false.
 *
 * Two neighbouring defects closed with it, both visible only through the else branch: a
 * `typeof x !== "…"` guard narrowed NOTHING (`extractNullNarrowing`'s `!==` arm returned before
 * reaching its `typeof` arm — then-branch included), and `x === undefined` / `x == null` had no
 * narrow-TO-nullish arm (the else of `x !== undefined`).
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, measured over `build/scratch-p18189/cells/pins` on
 * the identical text; the prelude is lines 1-4, so a one-line fixture is on line 5.
 * `Partial` is declared LOCALLY on the alias line: the `diagnose()` harness's embedded lib has
 * none, and a lib-utility reference resolves to the error type before the rule under test (tsgo
 * reads the identical rows with the local declaration).
 *
 * The ARROW pins are the ones that reach the LEGACY walk (`checkTypeAssignabilityInStatements` /
 * `checkTypeAssignabilityInStmt`): an arrow body is outside every spine-anchor chain, while a
 * function-declaration body is anchored on the spine — measured by ablation, the two legacy
 * installs are invisible to every function-declaration fixture here.
 *
 * NOT PINNED (pre-existing residues, unchanged): the else of a CONJUNCTION (`a && b`, whose negation
 * is a disjunction tsgo narrows to a union); the rest of a statement list after an `if` whose then
 * EXITS and which HAS an else (the (CHK.64)(ii) early-exit install is `elseStatement == null` only);
 * a guard that leaves `never` in the else (refused by design: `never` relates to everything).
 */
class ElseBranchNarrowingTest {

    private val prelude = """
        declare function isStr(v: any): v is string;
        declare function isFn(v: any): v is (...args: any[]) => any;
        interface Obs { next: (value: number) => void; complete: () => void; }
        type Partial<T> = { [K in keyof T]?: T[K] }; type P = Partial<Obs>;

    """.trimIndent()

    /** Every row as `line:column code message`, sorted; a one-line fixture is on line 5. */
    private fun rows(source: String): List<String> =
        diagnose(prelude + source.trimIndent())
            .map { "${it.line}:${it.character} ${it.code} ${it.message}" }.sorted()

    @Test
    fun `the negated disjunction of a guard call and a truthiness test narrows the else - rxjs Subscriber`() {
        val actual = rows(
            """
            export function rx(o: P | ((value: number) => void) | null) { let n: number; if (isFn(o) || !o) {} else { n = o; } }
            """
        )
        val expected = listOf(
            "5:107 2322 Type 'Partial<Obs>' is not assignable to type 'number'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `the rxjs Subscriber assignment itself is legal`() {
        val actual = rows(
            """
            export function rxOk(o: P | ((value: number) => void) | null) { let p: P; if (isFn(o) || !o) {} else { p = o; } }
            """
        )
        val expected = emptyList<String>()
        assert(actual == expected)
    }

    @Test
    fun `a truthiness guard narrows the else of its negation`() {
        val actual = rows(
            """
            export function truthy(o: string | number | null) { let n: boolean; if (!o) {} else { n = o; } }
            """
        )
        val expected = listOf(
            "5:87 2322 Type 'string | number' is not assignable to type 'boolean'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `an equality with null removes null in the else`() {
        val actual = rows(
            """
            export function eqNull(o: string | number | null) { let n: boolean; if (o === null) {} else { n = o; } }
            """
        )
        val expected = listOf(
            "5:95 2322 Type 'string | number' is not assignable to type 'boolean'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a typeof equality removes that type in the else`() {
        val actual = rows(
            """
            export function tyEq(o: string | number | null) { let n: boolean; if (typeof o === "string") {} else { n = o; } }
            """
        )
        val expected = listOf(
            "5:104 2322 Type 'number | null' is not assignable to type 'boolean'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a typeof inequality narrows its then branch`() {
        val actual = rows(
            """
            export function tyNeqThen(o: string | number | null) { let n: boolean; if (typeof o !== "string") { n = o; } }
            """
        )
        val expected = listOf(
            "5:101 2322 Type 'number | null' is not assignable to type 'boolean'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a type-guard call narrows its else by the false branch`() {
        val actual = rows(
            """
            export function guardElse(o: string | number | null) { let n: boolean; if (isStr(o)) {} else { n = o; } }
            """
        )
        val expected = listOf(
            "5:96 2322 Type 'number | null' is not assignable to type 'boolean'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a negated type-guard call narrows its else by the true branch`() {
        val actual = rows(
            """
            export function negGuardElse(o: string | number | null) { let n: boolean; if (!isStr(o)) {} else { n = o; } }
            """
        )
        val expected = listOf(
            "5:100 2322 Type 'string' is not assignable to type 'boolean'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `an or-chain narrows the else by each disjunct in order`() {
        val actual = rows(
            """
            export function disj(o: string | number | null) { let n: boolean; if (o === null || typeof o === "string") {} else { n = o; } }
            """
        )
        val expected = listOf(
            "5:118 2322 Type 'number' is not assignable to type 'boolean'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `the else of an inequality with undefined is undefined`() {
        val actual = rows(
            """
            export function undefElse(o: string | undefined) { let n: boolean; if (o !== undefined) {} else { n = o; } }
            """
        )
        val expected = listOf(
            "5:99 2322 Type 'undefined' is not assignable to type 'boolean'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `the else of a loose inequality with null is its nullish constituents`() {
        val actual = rows(
            """
            export function looseElse(o: string | null | undefined) { let n: boolean; if (o != null) {} else { n = o; } }
            """
        )
        val expected = listOf(
            "5:100 2322 Type 'null | undefined' is not assignable to type 'boolean'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `the return reader sees the else narrowing`() {
        val actual = rows(
            """
            export function ret(o: string | number | null): boolean | undefined { if (o === null) {} else { return o; } }
            """
        )
        val expected = listOf(
            "5:97 2322 Type 'string | number' is not assignable to type 'boolean | undefined'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `an else-if chain compounds every negated condition`() {
        val actual = rows(
            """
            export function chain(o: string | number | boolean | null) {
              let n: bigint;
              if (o === null) {} else if (typeof o === "string") {} else if (typeof o === "number") { n = o; } else { n = o; }
            }
            """
        )
        val expected = listOf(
            "7:107 2322 Type 'boolean' is not assignable to type 'bigint'.",
            "7:91 2322 Type 'number' is not assignable to type 'bigint'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a nested else compounds with the enclosing then`() {
        val actual = rows(
            """
            export function nestedElse(o: string | number | null) {
              let n: boolean;
              if (o !== null) { if (typeof o === "string") {} else { n = o; } } else { n = o; }
            }
            export function writeBack(o: string | number | null) { if (o === null) {} else { o = null; } }
            """
        )
        val expected = listOf(
            "7:58 2322 Type 'number' is not assignable to type 'boolean'.",
            "7:76 2322 Type 'null' is not assignable to type 'boolean'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `an assignment back to the reference in the else checks against the declared type`() {
        val actual = rows(
            """
            
            """
        )
        val expected = emptyList<String>()
        assert(actual == expected)
    }

    @Test
    fun `an arrow body else is narrowed on the legacy statement-list walk`() {
        val actual = rows(
            """
            export const arrowElse = (o: string | number | null) => { let n: boolean; if (o === null) {} else { n = o; } };
            """
        )
        val expected = listOf(
            "5:101 2322 Type 'string | number' is not assignable to type 'boolean'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `an arrow body nested else is narrowed on the legacy nested walk`() {
        val actual = rows(
            """
            export const arrowNested = (o: string | number | null, q: boolean) => { let n: boolean; if (q) { if (o === null) {} else { n = o; } } };
            """
        )
        val expected = listOf(
            "5:124 2322 Type 'string | number' is not assignable to type 'boolean'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `an arrow body else-if chain compounds on the legacy walk`() {
        val actual = rows(
            """
            export const arrowChain = (o: string | number | null) => { let n: boolean; if (typeof o === "string") {} else if (o === null) {} else { n = o; } };
            """
        )
        val expected = listOf(
            "5:137 2322 Type 'number' is not assignable to type 'boolean'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `an arrow body assignment back to the reference in the else checks against the declared type`() {
        val actual = rows(
            """
            export const arrowWriteBack = (o: string | number | null) => { if (o === null) {} else { o = null; } };
            """
        )
        val expected = emptyList<String>()
        assert(actual == expected)
    }

    @Test
    fun `a top-level else is narrowed on the spine anchor too`() {
        val actual = diagnose(
            """
            declare const t: string | number | null;
            let tn: boolean;
            if (t === null) {} else { tn = t; }
            """
        ).map { "${it.line}:${it.character} ${it.code} ${it.message}" }.sorted()
        val expected = listOf(
            "3:27 2322 Type 'string | number' is not assignable to type 'boolean'.",
        )
        assert(actual == expected)
    }
}
