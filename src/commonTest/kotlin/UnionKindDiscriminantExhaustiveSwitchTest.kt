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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

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
        diagnose(
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
            directives = "// @strict: true\n// @noImplicitReturns: true",
        ) should {
            have(none { it.code == 2366 || it.code == 7030 || it.code == 2355 })
        }
    }

    @Test
    fun `multi-valued kind member counts all its values`() {
        diagnose(
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
            directives = "// @strict: true\n// @noImplicitReturns: true",
        ) should {
            have(none { it.code == 2366 || it.code == 7030 })
        }
    }

    @Test
    fun `missing member keeps TS2366 firing`() {
        diagnose(
            """
            $mapperDecls
            export function f(t: T, mapper: TypeMapper): T {
                switch (mapper.kind) {
                    case TypeMapKind.Simple: return t;
                    case TypeMapKind.Array: return t;
                }
            }
            """,
            directives = "// @strict: true\n// @noImplicitReturns: true",
        ) should {
            have(any { it.code == 2366 })
        }
    }

    @Test
    fun `optional kind property keeps TS2366 firing`() {
        diagnose(
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
            directives = "// @strict: true\n// @noImplicitReturns: true",
        ) should {
            have(any { it.code == 2366 })
        }
    }

    @Test
    fun `possibly-undefined receiver does not fail exhaustiveness`() {
        // Round 424 DELIBERATE flip of the round-422 conservative pin: tsc
        // computes switch exhaustiveness over the NON-NULLISH part of the
        // receiver and reports the possibly-undefined ACCESS separately
        // (TS18048 at `mapper.kind`) — it emits NO TS2366 here. Our TS18048
        // emitter for this shape is a known M3.4 gap, but the exhaustiveness
        // verdict must match tsc (the round-423 reassigned-let pin requires
        // the same receiver-nullish drop).
        diagnose(
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
            directives = "// @strict: true\n// @noImplicitReturns: true",
        ) should {
            have(none { it.code == 2366 || it.code == 7030 })
        }
    }

    @Test
    fun `mixed enum and string-literal kinds prove exhaustive together`() {
        diagnose(
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
            directives = "// @strict: true\n// @noImplicitReturns: true",
        ) should {
            // string-literal kinds join the key space (`lit:s:`), so the mix proves
            // exhaustive
            have(none { it.code == 2366 || it.code == 7030 })
        }
    }
}
