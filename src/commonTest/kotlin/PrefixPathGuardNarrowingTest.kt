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
 * Round 424 — a type guard on a RECEIVER narrows a PROPERTY PATH through it
 * (tsc moduleNameResolver.ts `getAutomaticTypeDirectiveNames`):
 *
 *     function usesWildcardTypes(options): options is CompilerOptions & { types: string[] };
 *     if (!usesWildcardTypes(options)) return options.types ?? [];
 *     … options.types.map(…)     // ← `options.types` is non-nullish here
 *
 * The predicate argument's path ("options") is a proper dot-PREFIX of the
 * walked path ("options.types"); when the tail resolves on the predicate
 * target type to a REQUIRED property with a provably non-nullish type, the
 * positive branch drops nullish from the walked path. Minimal claim
 * (drop-nullish only, never substitute the resolved type) — suppression-safe.
 *
 * Sharp corners pinned: property OPTIONALITY is a symbol attribute, not folded
 * into the property type, so `types?: string[]` resolving to `string[]` must
 * NOT satisfy the claim unless some intersection constituent declares the
 * property required; and the NEGATIVE branch proves nothing about the
 * property.
 */
class PrefixPathGuardNarrowingTest {

    private val prelude = """
        interface CompilerOptions { types?: string[]; typeRoots?: string[]; }
        declare function usesWildcardTypes(options: CompilerOptions): options is CompilerOptions & { types: string[]; };
        declare function dedupe(x: string[]): string[];
    """

    private fun diags(body: String): List<Diagnostic> =
        TypeScriptCompiler().compile(
            "// @strict: true\n" + (prelude + body).trimIndent(), "t.ts",
        ).diagnostics

    @Test
    fun receiverGuardNarrowsPropertyPathAcrossLoops() {
        val d = diags(
            """
            export function f(options: CompilerOptions, roots: string[]): string[] {
                if (!usesWildcardTypes(options)) {
                    return options.types ?? [];
                }
                const wildcardMatches: string[] = [];
                for (const root of roots) {
                    for (const dir of roots) {
                        wildcardMatches.push(root + dir);
                    }
                }
                return dedupe(options.types.map(t => t === "*" ? "w" : t));
            }
            """,
        )
        assertTrue(
            d.none { it.code == 18048 },
            "a receiver guard pinning `types: string[]` must prove options.types non-nullish; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun guardNotPinningThePropertyStillFires() {
        // The intersection pins typeRoots, NOT types — types stays optional
        // (`types?: string[]` resolves to `string[]`; the OPTIONALITY lives on
        // the symbol, which the claim must consult).
        val d = diags(
            """
            declare function isOpts(options: CompilerOptions): options is CompilerOptions & { typeRoots: string[] };
            export function g(options: CompilerOptions): string[] {
                if (!isOpts(options)) {
                    return [];
                }
                return options.types.map(t => t);
            }
            """,
        )
        assertTrue(
            d.any { it.code == 18048 },
            "a guard that does not pin `types` must keep TS18048; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun negativeBranchProvesNothingAboutTheProperty() {
        val d = diags(
            """
            export function h(options: CompilerOptions): string[] {
                if (usesWildcardTypes(options)) {
                    return [];
                }
                return options.types.map(t => t);
            }
            """,
        )
        assertTrue(
            d.any { it.code == 18048 },
            "the negative guard branch must keep TS18048 on the property path; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
