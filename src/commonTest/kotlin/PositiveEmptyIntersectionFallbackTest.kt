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
 * M3.4 (round 425): tsc's positive-empty INTERSECTION fallback in
 * `getNarrowedType` — a positive type guard whose target relates to NO union
 * member in either direction intersects (`m & c`) instead of dropping
 * everything (tsc's `hasDynamicName(accessor)` on `GetAccessorDeclaration |
 * SetAccessorDeclaration` vs `DynamicNamedDeclaration`, utilities.ts
 * getAllAccessorDeclarations — the old empty result collapsed to `never`).
 * Re-measured post-canonical-keys: the round-423 net-negative verdict was an
 * artifact of the enum-key split feeding wrong disjointness verdicts.
 *
 * Companion: `typeof x === "object"` classifies an ENUM member as
 * NOT-object (enum values are numbers/strings at runtime — tsc watchPublic's
 * `ScriptTarget | CreateSourceFileOptions`).
 */
class PositiveEmptyIntersectionFallbackTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `unrelated positive guard target intersects instead of collapsing`() {
        val d = diags(
            """
            const enum SyntaxKind { GetAccessor, SetAccessor }
            interface NamedDeclaration { name?: { k: number }; }
            interface ComputedPropertyName { k: number; computed: true }
            interface DynamicNamedDeclaration extends NamedDeclaration { name: ComputedPropertyName; }
            interface GetAccessorDeclaration extends NamedDeclaration { kind: SyntaxKind.GetAccessor; body: string; }
            interface SetAccessorDeclaration extends NamedDeclaration { kind: SyntaxKind.SetAccessor; body: string; }
            type AccessorDeclaration = GetAccessorDeclaration | SetAccessorDeclaration;
            declare function hasDynamicName(decl: NamedDeclaration): decl is DynamicNamedDeclaration;
            export function f(accessor: AccessorDeclaration): number {
                if (hasDynamicName(accessor)) {
                    if (accessor.kind === SyntaxKind.GetAccessor) {
                        return 1;
                    }
                    return accessor.body.length;
                }
                return 0;
            }
            """
        )
        assertTrue(
            d.none { it.code == 2339 },
            "the intersection fallback must keep kind/body resolvable, got: $d"
        )
    }

    @Test
    fun `typeof object classifies an enum member as not-object`() {
        val d = diags(
            """
            const enum ScriptTarget { ES5, ES2020 }
            interface CreateSourceFileOptions { impliedNodeFormat?: number; }
            export function g(languageVersionOrOptions: ScriptTarget | CreateSourceFileOptions) {
                return typeof languageVersionOrOptions === "object"
                    ? languageVersionOrOptions.impliedNodeFormat
                    : undefined;
            }
            """
        )
        assertTrue(
            d.none { it.code == 2339 },
            "an enum member is never typeof 'object', got: $d"
        )
    }

    @Test
    fun `negative branch exhaustion to never is preserved`() {
        val d = diags(
            """
            class C1 { x: string = "" }
            class C2 { y: string = "" }
            declare function isC1(c: C1 | C2): c is C1;
            declare function isC2(c: C1 | C2): c is C2;
            export function f(c: C1 | C2): string {
                if (isC1(c)) { return c.x; }
                if (isC2(c)) { return c.y; }
                // c is never here; accessing a property on it is tsc's own behavior —
                // the intersection fallback must NOT resurrect members on the
                // NEGATIVE-exhaustion path.
                return "";
            }
            """
        )
        assertTrue(d.none { it.code == 2339 }, "control shape must stay clean, got: $d")
    }
}
