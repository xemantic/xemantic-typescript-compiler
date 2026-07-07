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
 * M1.12 (round 425): a type guard whose target is a UNION distributes the
 * narrow-DOWN direction over the CANDIDATE union members (tsc `getNarrowedType`
 * runs `mapType(candidate, c => ...)`). The old code tested `targetUnion <:
 * member`, which requires EVERY candidate assignable to the member and
 * essentially never holds — so `isPrivateIdentifierClassElementDeclaration(node)`
 * (target = 5-member union of subtype interfaces) dropped every member of
 * `MethodDeclaration | AccessorDeclaration` → `never` → FP TS2339 on the
 * positive branch (tsc classFields.ts:841–859, never×5).
 *
 * Invariant pinned: per-candidate narrow-down keeps the RELATED candidates
 * (suppression), while a member related to NO candidate is still dropped and
 * the negative branch stays untouched.
 */
class UnionCandidateGuardNarrowingTest {

    private val classFieldsShape = """
        interface Identifier { ident: string; }
        interface PrivateIdentifier { priv: string; }

        interface MethodDeclaration { name: Identifier | PrivateIdentifier; body: string; m: number }
        interface GetAccessorDeclaration { name: Identifier | PrivateIdentifier; body: string; g: number }
        interface SetAccessorDeclaration { name: Identifier | PrivateIdentifier; body: string; s: number }

        interface PrivateIdentifierMethodDeclaration extends MethodDeclaration { name: PrivateIdentifier; }
        interface PrivateIdentifierGetAccessorDeclaration extends GetAccessorDeclaration { name: PrivateIdentifier; }
        interface PrivateIdentifierSetAccessorDeclaration extends SetAccessorDeclaration { name: PrivateIdentifier; }

        type AccessorDeclaration = GetAccessorDeclaration | SetAccessorDeclaration;
        type PrivateClassElementDeclaration =
            | PrivateIdentifierMethodDeclaration
            | PrivateIdentifierGetAccessorDeclaration
            | PrivateIdentifierSetAccessorDeclaration;

        declare function isPrivate(node: MethodDeclaration | AccessorDeclaration): node is PrivateClassElementDeclaration;
        declare function accessPrivateIdentifier(name: PrivateIdentifier): string;
    """.trimIndent()

    @Test
    fun `union-target guard narrows each member down to its related candidates`() {
        diagnose(
            classFieldsShape + """

            function visit(node: MethodDeclaration | AccessorDeclaration) {
                if (!isPrivate(node)) { return "skip"; }
                const info = accessPrivateIdentifier(node.name);
                return info + node.body;
            }
            """
        ) should {
            have(none { it.code == 2339 || it.code == 2345 })
        }
    }

    @Test
    fun `De-Morgan early return keeps the positive narrowing on fall-through`() {
        diagnose(
            classFieldsShape + """

            declare function shouldTransform(node: PrivateClassElementDeclaration): boolean;
            function visit(node: MethodDeclaration | AccessorDeclaration) {
                if (!isPrivate(node) || !shouldTransform(node)) { return "skip"; }
                return accessPrivateIdentifier(node.name) + node.body;
            }
            """
        ) should {
            have(none { it.code == 2339 || it.code == 2345 })
        }
    }

    @Test
    fun `single-target guard narrow-down behavior is unchanged`() {
        diagnose(
            """
            interface Type { flags: number; }
            interface TupleTypeReference extends Type { target: { readonly: boolean }; }
            declare function isTupleType(type: Type): type is TupleTypeReference;

            function check(t: Type | undefined) {
                if (t !== undefined && isTupleType(t)) {
                    return t.target.readonly;
                }
                return false;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    /**
     * Round 425 companion (the REAL classFields.ts blocker): the enum-member
     * discriminant key space must be CANONICAL across resolution paths. A file
     * without its own `SyntaxKind` import resolves the enum via `globals` (the
     * merged, possibly alias-origin instance), while an importing file resolves
     * it via the barrel resolver (the declaring file's local instance) — keys
     * built from different symbol ids look pairwise DISJOINT and the guard
     * drops every union member → `never` → FP TS2339.
     */
    @Test
    fun `enum discriminant keys are canonical across files with different resolution paths`() {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to
                    """{ "compilerOptions": { "strict": true, "outDir": "./dist" }, "include": ["src/**/*.ts"] }""",
                // alphabetically FIRST: contributes "SyntaxKind" to globals as an alias
                "/proj/src/a_first.ts" to """
                    import { SyntaxKind } from "./z_types.js";
                    export const firstKind: SyntaxKind = SyntaxKind.MethodDeclaration;
                """.trimIndent(),
                // no SyntaxKind import: primes the key cache via the GLOBALS instance
                "/proj/src/m_prime.ts" to """
                    import { MethodDeclaration, AccessorDeclaration, isPrivate } from "./z_types.js";
                    export function prime(node: MethodDeclaration | AccessorDeclaration) {
                        if (!isPrivate(node)) { return "skip"; }
                        return node.body;
                    }
                """.trimIndent(),
                // imports SyntaxKind: keys resolve via the import-alias path
                "/proj/src/v_visitor.ts" to """
                    import { SyntaxKind, MethodDeclaration, AccessorDeclaration, PrivateIdentifier, isPrivate } from "./z_types.js";
                    declare function accessPrivateIdentifier(name: PrivateIdentifier): string;
                    export const usedKind: SyntaxKind = SyntaxKind.GetAccessor;
                    export function visit(node: MethodDeclaration | AccessorDeclaration) {
                        if (!isPrivate(node)) { return "skip"; }
                        return accessPrivateIdentifier(node.name) + node.body;
                    }
                """.trimIndent(),
                "/proj/src/z_types.ts" to """
                    export const enum SyntaxKind { Identifier, PrivateIdentifier, MethodDeclaration, GetAccessor, SetAccessor }
                    export interface Identifier { readonly kind: SyntaxKind.Identifier; ident: string; }
                    export interface PrivateIdentifier { readonly kind: SyntaxKind.PrivateIdentifier; priv: string; }
                    export interface MethodDeclaration { readonly kind: SyntaxKind.MethodDeclaration; name: Identifier | PrivateIdentifier; body: string; }
                    export interface GetAccessorDeclaration { readonly kind: SyntaxKind.GetAccessor; name: Identifier | PrivateIdentifier; body: string; }
                    export interface SetAccessorDeclaration { readonly kind: SyntaxKind.SetAccessor; name: Identifier | PrivateIdentifier; body: string; }
                    export interface PrivateIdentifierMethodDeclaration extends MethodDeclaration { name: PrivateIdentifier; }
                    export interface PrivateIdentifierGetAccessorDeclaration extends GetAccessorDeclaration { name: PrivateIdentifier; }
                    export interface PrivateIdentifierSetAccessorDeclaration extends SetAccessorDeclaration { name: PrivateIdentifier; }
                    export type AccessorDeclaration = GetAccessorDeclaration | SetAccessorDeclaration;
                    export type PrivateClassElementDeclaration =
                        | PrivateIdentifierMethodDeclaration
                        | PrivateIdentifierGetAccessorDeclaration
                        | PrivateIdentifierSetAccessorDeclaration;
                    export function isPrivate(node: MethodDeclaration | AccessorDeclaration): node is PrivateClassElementDeclaration {
                        return true;
                    }
                """.trimIndent(),
            )
        )
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val fps = result.diagnostics.filter { it.code == 2339 }
        have(fps.isEmpty())
    }

    @Test
    fun `negative branch of a union-target guard is untouched`() {
        diagnose(
            classFieldsShape + """

            declare function visitEachChild(node: MethodDeclaration | AccessorDeclaration): string;
            function visit(node: MethodDeclaration | AccessorDeclaration) {
                if (!isPrivate(node)) {
                    // negative branch: node keeps the declared union
                    return visitEachChild(node);
                }
                return node.body;
            }
            """
        ) should {
            have(none { it.code == 2339 || it.code == 2345 })
        }
    }
}
