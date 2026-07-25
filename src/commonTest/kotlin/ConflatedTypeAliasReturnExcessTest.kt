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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * Round 447 (Blocker #3, self-compile burn-down): the EXCESS-property (TS2353) complement of round
 * 444's alias's-own-file member-access bail. In the file that DECLARES `type X = A | B | …`, a
 * `return { … }` is excess-checked against `X`, which — via the last-wins Interface+TypeAlias merge —
 * resolves to a SIBLING file's unrelated `interface X`. tsc's own fixAddMissingMember.ts declares
 * `type Info = TypeLikeDeclarationInfo | EnumInfo | …` while 12 sibling codefix files declare
 * `interface Info`, so `return { kind: InfoKind.Enum, … }` FP-fired TS2353 "'kind' does not exist in
 * type 'Info'" (6 on the services profile → 0). The union member interfaces are ALSO conflated across
 * files, so the satisfaction check reads each member interface AST-side (pollution-proof).
 *
 * The cross-file conflation is a whole-program (file-order / merge-pollution) phenomenon, but — unlike
 * the round-443 receiver leak — this shape DOES reproduce in a small multi-file program (verified via
 * the CLI: without the bail the clean return FP's TS2353; with it the excess-`bogusExtra` firewall
 * still fires). These tests pin both directions.
 */
class ConflatedTypeAliasReturnExcessTest {

    private fun compile(@Language("typescript") source: String, primary: String) =
        TypeScriptCompiler().compile(source.trimIndent(), primary).diagnostics

    @Test
    fun `a return object literal matching a constituent of the file-local alias union does not FP - no TS2353`() {
        // `type Info = A | B` (a.ts) conflates with sibling `interface Info` (b.ts, c.ts). A returned
        // object literal that satisfies constituent A must not be excess-checked against the wrong
        // merged interface.
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            interface A { kind: 1; label: string; }
            interface B { kind: 2; count: number; }
            type Info = A | B;
            export function getInfo(x: boolean): Info | undefined {
                if (x) return { kind: 1, label: "hi" };
                return { kind: 2, count: 3 };
            }

            // @Filename: b.ts
            export interface Info { other: string; }

            // @Filename: c.ts
            export interface Info { another: number; }
            """,
            primary = "a.ts",
        ) should {
            have(none { it.code == 2353 })
        }
    }

    @Test
    fun `a return object literal matching NO constituent still fires - TS2353`() {
        // Firewall: an excess property present on neither A nor B means the object satisfies no
        // constituent, so the excess check must still fire (the fix only suppresses matching objects).
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            interface A { kind: 1; label: string; }
            interface B { kind: 2; count: number; }
            type Info = A | B;
            export function getInfo(): Info | undefined {
                return { kind: 1, label: "hi", bogusExtra: 5 };
            }

            // @Filename: b.ts
            export interface Info { other: string; }
            """,
            primary = "a.ts",
        ) should {
            have(any { it.code == 2353 })
        }
    }

    @Test
    fun `union member interfaces that are themselves conflated across files still suppress correctly - no TS2353`() {
        // The union members (`Func`) are ALSO conflated: a sibling file declares `interface Func` with
        // EXTRA members. Reading the merged (polluted) member set would make the object look
        // missing-required; the AST-side per-file read avoids that.
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            interface Enum { kind: 1; token: string; }
            interface Func { kind: 2; call: string; token: string; }
            type Info = Enum | Func;
            export function getInfo(x: boolean): Info | undefined {
                if (x) return { kind: 1, token: "t" };
                return { kind: 2, call: "c", token: "t" };
            }

            // @Filename: b.ts
            export interface Func { kind: 9; extra1: string; extra2: number; declaration: string; }

            // @Filename: c.ts
            export interface Info { other: string; }
            """,
            primary = "a.ts",
        ) should {
            have(none { it.code == 2353 })
        }
    }

    @Test
    fun `a plain single-file return against a union alias is unaffected - firewall`() {
        // Sanity: with no conflation the ordinary excess check must still fire for a genuine error.
        diagnose(
            """
            interface A { kind: 1; label: string; }
            interface B { kind: 2; count: number; }
            type Info = A | B;
            export function f(): Info { return { kind: 1, label: "x", junk: 1 }; }
            """
        ) should {
            have(any { it.code == 2353 })
        }
    }
}
