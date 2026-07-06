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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Round 428 (M3.1 slice): call-site inference for tsc core.ts's `append<T>` idiom —
 * the single biggest TS2322 self-compile family (`Type 'T[]' is not assignable to
 * type 'Statement[]'` ×130+):
 *
 *   let leading: Statement[] | undefined;
 *   leading = append(leading, statement);   // tsc: OK — T = Statement
 *
 * Four coupled mechanisms, each pinned below:
 *  1. `tryInferSingleTypeParamFromArgs` accepts a `T | undefined` / `T[] | undefined`
 *     UNION param (nullableUnionOfTpMode) and strips nullish members from a UNION arg.
 *  2. An `anyType` arg (an unmodeled local like a for-of loop var) contributes NO
 *     candidate at the RETURN-TYPE inference site instead of killing the inference
 *     (the arg-vs-param site keeps the hard bail — its consumers emit).
 *  3. `getReturnTypeOfCallExpression`'s multi-sig path runs single-TP inference for
 *     overloaded ALL-GENERIC callees (every `append` overload is generic).
 *  4. The string-layer `isAssignableTo` treats an array-literal source vs a `T[]`
 *     target (T an enclosing fn's type param) as unknowable — `return [value]`
 *     inside the generic fn body no longer FPs against `T[] | undefined`.
 */
class GenericAppendInferenceTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    private val appendOverloads = """
        declare function append<T extends {}>(to: T[], value: T | undefined): T[];
        declare function append<T extends {}>(to: T[] | undefined, value: T): T[];
        declare function append<T extends {}>(to: T[] | undefined, value: T | undefined): T[] | undefined;
    """

    @Test
    fun `overloaded all-generic append call assigns back without TS2322`() {
        val d = diags(
            """
            $appendOverloads
            interface Statement { kind: number; }
            export function collect(items: Statement[], s: Statement): Statement[] {
                let leading: Statement[] | undefined;
                leading = append(leading, s);
                return leading ?? [];
            }
            """
        )
        assertTrue(d.none { it.code == 2322 }, "expected no TS2322, got: $d")
    }

    @Test
    fun `single-sig generic with nullable union params infers through assignment`() {
        val d = diags(
            """
            interface Statement { kind: number; }
            function appendOne<T extends {}>(to: T[] | undefined, value: T): T[] {
                if (to === undefined) return [value];
                to.push(value);
                return to;
            }
            export function collect(items: Statement[], s: Statement): Statement[] {
                let leading: Statement[] | undefined;
                leading = appendOne(leading, s);
                return leading ?? [];
            }
            """
        )
        assertTrue(d.none { it.code == 2322 }, "expected no TS2322, got: $d")
    }

    @Test
    fun `anyType arg contributes no candidate instead of killing return inference`() {
        // `item` is a for-of loop variable — unmodeled, resolves to anyType. T must
        // still anchor from the `leading` array arg.
        val d = diags(
            """
            interface Statement { kind: number; }
            declare function appendOne<T extends {}>(to: T[] | undefined, value: T): T[];
            export function collect(items: Statement[]): Statement[] {
                let leading: Statement[] | undefined;
                for (const item of items) {
                    leading = appendOne(leading, item);
                }
                return leading ?? [];
            }
            """
        )
        assertTrue(d.none { it.code == 2322 }, "expected no TS2322, got: $d")
    }

    @Test
    fun `nullish arg to nullable union param contributes nothing but does not bail`() {
        val d = diags(
            """
            interface Statement { kind: number; }
            declare function append<T extends {}>(to: T[] | undefined, value: T | undefined): T[] | undefined;
            export function f(s: Statement) {
                let leading: Statement[] | undefined;
                leading = append(undefined, s);
            }
            """
        )
        assertTrue(d.none { it.code == 2322 }, "expected no TS2322, got: $d")
    }

    @Test
    fun `array literal return inside generic fn body does not FP against T array union`() {
        val d = diags(
            """
            export function appendOne<T extends {}>(to: T[] | undefined, value: T | undefined): T[] | undefined {
                if (value === undefined) return to;
                if (to === undefined) return [value];
                to.push(value);
                return to;
            }
            """
        )
        assertTrue(d.none { it.code == 2322 }, "expected no TS2322, got: $d")
    }

    @Test
    fun `array literal return inside generic fn body vs bare T array does not FP`() {
        val d = diags(
            """
            export function wrap<T extends {}>(value: T): T[] {
                return [value];
            }
            """
        )
        assertTrue(d.none { it.code == 2322 }, "expected no TS2322, got: $d")
    }

    @Test
    fun `named type-guard identifier arg skips the multi-sig inference path`() {
        // tsc's checker.ts getNamesOfDeclaration shape: `filter(arr, isFoo)` selects
        // the GUARD overload whose S binds from the predicate — an inference we don't
        // model. The new inference path must SKIP (previous outcome preserved: no
        // emission), not infer the boolean overload's `(Identifier | undefined)[]`
        // and FP against the declared `Identifier[]` return.
        val d = diags(
            """
            interface Identifier { kind: 80; text: string; }
            declare function isIdentifierAndNotUndefined(node: unknown): node is Identifier;
            declare function filter<T, U extends T>(array: T[], f: (x: T) => x is U): U[];
            declare function filter<T>(array: T[], f: (x: T) => boolean): T[];
            export function getNames(maybe: (Identifier | undefined)[]): Identifier[] {
                return filter(maybe, isIdentifierAndNotUndefined);
            }
            """
        )
        assertTrue(d.none { it.code == 2322 }, "expected no TS2322, got: $d")
    }

    @Test
    fun `array literal assigns to a union target with an array member`() {
        // Round 428c: the string-layer union check treats an array-literal source vs
        // an array-ish union member as unknowable (tsc sourcemap.ts's
        // `sourcesContent = []` vs `(string | null)[] | undefined`, `return []` vs
        // `string | string[] | undefined`).
        val d = diags(
            """
            export function f(): void {
                let sourcesContent: (string | null)[] | undefined;
                if (!sourcesContent) sourcesContent = [];
                sourcesContent.push(null);
            }
            export function g(value: string): string | string[] | undefined {
                if (value === "") {
                    return [];
                }
                return value;
            }
            """
        )
        assertTrue(d.none { it.code == 2322 }, "expected no TS2322, got: $d")
    }

    @Test
    fun `negative control - array literal vs union WITHOUT array member still fires`() {
        val d = diags(
            """
            export function g(): string | number | undefined {
                return [];
            }
            """
        )
        assertTrue(d.any { it.code == 2322 }, "expected TS2322 for [] vs string | number | undefined, got: $d")
    }

    @Test
    fun `negative control - genuinely wrong call result still fires TS2322`() {
        // appendNum returns number[] regardless — assigning to Statement[] must error.
        val d = diags(
            """
            interface Statement { kind: number; }
            declare function appendNum<T extends {}>(to: T[] | undefined, value: T): T[];
            export function f(s: Statement[]) {
                let leading: Statement[] | undefined;
                leading = appendNum([1, 2], 3);
            }
            """
        )
        assertTrue(d.any { it.code == 2322 }, "expected TS2322 for number[] into Statement[], got: $d")
    }

    @Test
    fun `negative control - non-array return inside generic fn still fires`() {
        // `return 3` against `T[]` is a kind mismatch regardless of T.
        val d = diags(
            """
            export function bad<T extends {}>(value: T): T[] {
                return 3;
            }
            """
        )
        assertTrue(d.any { it.code == 2322 }, "expected TS2322 for `return 3` vs T[], got: $d")
    }
}
