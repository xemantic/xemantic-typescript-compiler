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
 * Round 424b — the DebugTypeMapper slice (tsc debug.ts): an explicit-type-arg
 * assert re-types `this`, and a `switch (this.kind)` then discriminant-narrows
 * the asserted union:
 *
 *     class DebugTypeMapper {
 *         declare kind: TypeMapKind;
 *         __debugToString(): string {
 *             type<TypeMapper>(this);            // asserts value is T, T := TypeMapper
 *             switch (this.kind) {
 *                 case TypeMapKind.Simple: return `${this.source} -> ${this.target}`;
 *                 …
 *
 * Three coupled pieces: (1) `asserts value is <TP>` binds the TP from the
 * call's EXPLICIT type arguments, and an assertion on an `any`/`unknown`
 * reference RE-TYPES it (the relation gate would trivially pass and keep the
 * useless `any`); (2) checkMemberAccessMissing consults flow narrowing for
 * `this` receivers (getTypeOfExpression(this) is deliberately anyType, B101 —
 * the round-418 suppression never applied); (3) the exhaustive-switch receiver
 * typing recovers an anyType receiver through the same assert re-type.
 */
class ThisAssertsNarrowingTest {

    private val prelude = """
        export const enum TypeMapKind { Simple, Array, Merged }
        interface Ty { id: number; }
        type TypeMapper =
            | { kind: TypeMapKind.Simple; source: Ty; target: Ty; }
            | { kind: TypeMapKind.Array; sources: readonly Ty[]; }
            | { kind: TypeMapKind.Merged; mapper1: TypeMapper; mapper2: TypeMapper; };
        declare function type<T>(value: unknown): asserts value is T;
    """

    private fun diags(body: String): List<Diagnostic> =
        TypeScriptCompiler().compile(
            "// @strict: true\n" + (prelude + body).trimIndent(), "t.ts",
        ).diagnostics

    @Test
    fun explicitTypeArgAssertRetypesThisAndSwitchNarrows() {
        val d = diags(
            """
            export class DebugTypeMapper {
                declare kind: TypeMapKind;
                debugToString(): string {
                    type<TypeMapper>(this);
                    switch (this.kind) {
                        case TypeMapKind.Simple:
                            return `${'$'}{this.source.id} -> ${'$'}{this.target.id}`;
                        case TypeMapKind.Array:
                            return `${'$'}{this.sources.length}`;
                        case TypeMapKind.Merged:
                            return `${'$'}{this.mapper1} ${'$'}{this.mapper2}`;
                    }
                }
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2339 || it.code == 2366 || it.code == 7030 },
            "the assert re-types this and the switch narrows + is exhaustive; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun withoutTheAssertThisMembersStillFire() {
        // Negative control: no assert — the class-chain TS2339 on a member the
        // class does not declare must keep firing.
        val d = diags(
            """
            export class DebugTypeMapper {
                declare kind: TypeMapKind;
                debugToString(): number {
                    return this.bogusMember;
                }
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2339 && it.message.contains("bogusMember") },
            "a genuinely-missing this-member must keep TS2339 without an assert; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun assertedUnionMemberMissingPropertyStillFires() {
        // Negative control: the assert + switch narrow to the Array member,
        // which genuinely lacks `source` — the suppression requires the
        // property on EVERY narrowed member.
        val d = diags(
            """
            export class DebugTypeMapper {
                declare kind: TypeMapKind;
                debugToString(): Ty {
                    type<TypeMapper>(this);
                    switch (this.kind) {
                        case TypeMapKind.Array:
                            return this.source;
                        default:
                            throw new Error("x");
                    }
                }
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2339 && it.message.contains("source") },
            "a property absent from the narrowed member must keep TS2339; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
