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
 * Round 422 (Pattern C2, the `.kind`-discriminated-union half deferred by rounds 414/415):
 * a `switch (x.kind)` with NO default over a UNION whose every member declares a readable
 * REQUIRED `kind` annotation is TERMINATING when the cases cover every member's key — tsc
 * narrows the discriminant to `never` after all cases (its own `getMappedType` over
 * `TypeMapper`, `getAssignmentTargetKind` over AST nodes). `requiredUnionDiscriminantKeys`
 * claims exhaustive ONLY when everything resolves; any gap (missing member, optional
 * `kind?:`, nullish receiver, unreadable annotation) bails and TS2366 STANDS — the
 * FP-safety contract, since `.errors.txt` corpus tests are disabled and this analysis is
 * gated only by the full suite + these controls.
 */
class UnionKindDiscriminantExhaustiveSwitchTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile(
            "// @strict: true\n// @noImplicitReturns: true\n" + source.trimIndent(), "t.ts",
        ).diagnostics

    private val mapperDecls = """
        export const enum TypeMapKind { Simple, Array, Deferred }
        interface T { id: number; }
        type TypeMapper =
            | { kind: TypeMapKind.Simple; source: T; }
            | { kind: TypeMapKind.Array; sources: readonly T[]; }
            | { kind: TypeMapKind.Deferred; targets: (() => T)[]; };
    """

    @Test
    fun `exhaustive union-kind switch with no default is terminating`() {
        val d = diags(
            """
            $mapperDecls
            export function f(t: T, mapper: TypeMapper): T {
                switch (mapper.kind) {
                    case TypeMapKind.Simple: return t;
                    case TypeMapKind.Array: return t;
                    case TypeMapKind.Deferred: return t;
                }
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2366 || it.code == 7030 || it.code == 2355 },
            "an exhaustive `.kind` union switch must count as terminating; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `multi-valued kind member counts all its values`() {
        val d = diags(
            """
            export const enum K { A, B, C }
            interface T { id: number; }
            type U = { kind: K.A; a: T; } | { kind: K.B | K.C; bc: T; };
            export function f(t: T, u: U): T {
                switch (u.kind) {
                    case K.A: return t;
                    case K.B: return t;
                    case K.C: return t;
                }
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2366 || it.code == 7030 },
            "a `kind: K.B | K.C` member requires BOTH values covered — and they are; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `missing member keeps TS2366 firing`() {
        val d = diags(
            """
            $mapperDecls
            export function f(t: T, mapper: TypeMapper): T {
                switch (mapper.kind) {
                    case TypeMapKind.Simple: return t;
                    case TypeMapKind.Array: return t;
                }
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2366 },
            "a switch missing the Deferred member must keep TS2366; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `optional kind property keeps TS2366 firing`() {
        val d = diags(
            """
            export const enum K { A, B }
            interface T { id: number; }
            type U = { kind?: K.A; a: T; } | { kind: K.B; b: T; };
            export function f(t: T, u: U): T {
                switch (u.kind) {
                    case K.A: return t;
                    case K.B: return t;
                }
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2366 },
            "an optional `kind?:` means the value set includes undefined — TS2366 must stand; " +
                "got: " + d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `possibly-undefined receiver keeps TS2366 firing`() {
        val d = diags(
            """
            $mapperDecls
            export function f(t: T, mapper: TypeMapper | undefined): T {
                switch (mapper.kind) {
                    case TypeMapKind.Simple: return t;
                    case TypeMapKind.Array: return t;
                    case TypeMapKind.Deferred: return t;
                }
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2366 },
            "a `TypeMapper | undefined` receiver has no readable kind on the undefined member " +
                "— TS2366 must stand; got: " + d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `mixed enum and string-literal kinds prove exhaustive together`() {
        val d = diags(
            """
            export const enum K { A = "a", B = "b" }
            interface T { id: number; }
            type U = { kind: K.A; a: T; } | { kind: K.B; b: T; } | { kind: "other"; o: T; };
            export function f(t: T, u: U): T {
                switch (u.kind) {
                    case K.A: return t;
                    case K.B: return t;
                    case "other": return t;
                }
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2366 || it.code == 7030 },
            "string-literal kinds join the key space (`lit:s:`), so the mix proves exhaustive; " +
                "got: " + d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
