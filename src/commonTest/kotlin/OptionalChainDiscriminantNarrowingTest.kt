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
 * Round 422 (M3.4): an OPTIONAL discriminant access proves the receiver non-nullish on
 * the TRUE branch — `x?.kind === RHS` can only hold when `x` is non-nullish, because
 * `undefined?.kind` evaluates to `undefined`, which never equals a definitely-non-nullish
 * RHS (an enum member or a non-nullish literal). tsc's own checker.ts:8061:
 *
 *   if (signature.declaration?.kind === SyntaxKind.JSDocSignature &&
 *       signature.declaration.parent.kind === SyntaxKind.JSDocOverloadTag) { … }
 *
 * `narrowByDiscriminantProperty` previously KEPT nullish union members (the
 * `getApparentType(member) !is Type.Object` early-keep), so `signature.declaration`
 * stayed possibly-undefined in the `&&` RHS and the body → FP TS18048. The drop is
 * gated: positive branch only (`!==` keeps nullish — `undefined?.kind !== RHS` is true
 * when x IS undefined), and only for a provably non-nullish RHS.
 */
class OptionalChainDiscriminantNarrowingTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    private val decls = """
        export enum SyntaxKind { JSDocSignature = 1, JSDocOverloadTag = 2, Other = 3 }
        interface Node { kind: SyntaxKind; parent: Node; }
        interface JSDocSignature extends Node { kind: SyntaxKind.JSDocSignature; jsSig: string; }
        interface OtherDecl extends Node { kind: SyntaxKind.Other; }
        interface Signature { declaration?: JSDocSignature | OtherDecl; }
    """

    @Test
    fun `optional-chain enum discriminant proves receiver non-nullish in AND rhs and body`() {
        val d = diags(
            """
            $decls
            export function f(signature: Signature): string | undefined {
                if (signature.declaration?.kind === SyntaxKind.JSDocSignature &&
                    signature.declaration.parent.kind === SyntaxKind.JSDocOverloadTag) {
                    return "" + signature.declaration.parent.kind;
                }
                return undefined;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 18048 },
            "`x?.kind === Enum.Member` (true) must drop undefined from x; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `optional-chain string-literal discriminant proves receiver non-nullish`() {
        val d = diags(
            """
            interface Named { name?: { text: string; parent: Named }; }
            export function f(n: Named): Named | undefined {
                if (n.name?.text === "x") {
                    return n.name.parent;
                }
                return undefined;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 18048 },
            "`x?.text === \"lit\"` (true) must drop undefined from x; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `negative comparison keeps nullish - access in true branch still fires`() {
        val d = diags(
            """
            $decls
            export function f(signature: Signature): SyntaxKind | undefined {
                if (signature.declaration?.kind !== SyntaxKind.JSDocSignature) {
                    return signature.declaration.parent.kind;
                }
                return undefined;
            }
            """,
        )
        assertTrue(
            d.any { it.code == 18048 },
            "`x?.kind !== Enum.Member` is TRUE when x is undefined — the access must still " +
                "fire TS18048; got: " + d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `undefined RHS keeps nullish - access still fires`() {
        val d = diags(
            """
            $decls
            export function f(signature: Signature): SyntaxKind | undefined {
                if (signature.declaration?.kind === undefined) {
                    return signature.declaration.parent.kind;
                }
                return undefined;
            }
            """,
        )
        assertTrue(
            d.any { it.code == 18048 },
            "`x?.kind === undefined` holds when x IS undefined — the access must still fire " +
                "TS18048; got: " + d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `non-optional discriminant access is unaffected`() {
        val d = diags(
            """
            $decls
            export function f(s: { declaration: JSDocSignature | OtherDecl }): string {
                if (s.declaration.kind === SyntaxKind.JSDocSignature) {
                    return s.declaration.jsSig;
                }
                return "";
            }
            """,
        )
        assertTrue(
            d.none { it.code == 18048 || it.code == 2339 },
            "plain (non-`?.`) discriminant narrowing must keep working; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
