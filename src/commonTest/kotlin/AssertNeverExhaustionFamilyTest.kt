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
 * Round 460: three exhaustive-switch → `never` narrowing shapes from tsc's own
 * sources whose `Debug.assertNever(x)` / `assertType<never>(x)` argument FP'd
 * TS2345:
 * (1) a NON-union subject — a SINGLE interface (after a type guard narrowed the
 *     union away) whose declared discriminant annotation is an enum-member-union
 *     alias fully covered by the cases (programDiagnostics.ts:346,
 *     `isReferencedFile(reason)` + `switch (reason.kind)` over ReferencedFileKind);
 * (2) a `Debug.type<T>(node)` assert whose T is a FUNCTION-BODY-local type alias
 *     (B83.5-unbound → never resolved; declarations/diagnostics.ts:702's
 *     WithIsolatedDeclarationDiagnostic);
 * (3) a `Debug.type<T>(node)` target union containing a lib `Exclude<T, U>`
 *     member (no union distribution in our conditional evaluation → the member
 *     resolved anyType and poisoned the union; utilities.ts:12082's
 *     HasInferredType).
 */
class AssertNeverExhaustionFamilyTest {

    @Test
    fun `single-interface subject with enum-alias kind - exhaustive switch default is never`() {
        diagnose("""
            enum FileIncludeKind { RootFile, Import, ReferenceFile, TypeReferenceDirective, LibReferenceDirective }
            type ReferencedFileKind = FileIncludeKind.Import | FileIncludeKind.ReferenceFile | FileIncludeKind.TypeReferenceDirective | FileIncludeKind.LibReferenceDirective;
            interface RootFile { kind: FileIncludeKind.RootFile; index: number; }
            interface ReferencedFile { kind: ReferencedFileKind; file: string; index: number; }
            type FileIncludeReason = RootFile | ReferencedFile;
            declare function isReferencedFile(reason: FileIncludeReason): reason is ReferencedFile;
            declare function assertNever(member: never, message?: string): never;
            function f(reason: FileIncludeReason) {
                if (isReferencedFile(reason)) {
                    let message: string;
                    switch (reason.kind) {
                        case FileIncludeKind.Import: message = "a"; break;
                        case FileIncludeKind.ReferenceFile: message = "b"; break;
                        case FileIncludeKind.TypeReferenceDirective: message = "c"; break;
                        case FileIncludeKind.LibReferenceDirective: message = "d"; break;
                        default: assertNever(reason);
                    }
                    return message;
                }
            }
        """.trimIndent()) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a NON-exhaustive switch on a single-interface subject keeps the TS2345`() {
        diagnose("""
            enum FileIncludeKind { Import, ReferenceFile }
            type ReferencedFileKind = FileIncludeKind.Import | FileIncludeKind.ReferenceFile;
            interface ReferencedFile { kind: ReferencedFileKind; file: string; }
            declare function assertNever(member: never): never;
            function f(reason: ReferencedFile) {
                switch (reason.kind) {
                    case FileIncludeKind.Import: break;
                    default: assertNever(reason);
                }
            }
        """.trimIndent()) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `function-body-local type alias resolves as a Debug type assert target`() {
        diagnose("""
            enum SyntaxKind { GetAccessor = 1, SetAccessor = 2, Parameter = 3 }
            interface GetAccessorDeclaration { readonly kind: SyntaxKind.GetAccessor; g: string; }
            interface SetAccessorDeclaration { readonly kind: SyntaxKind.SetAccessor; s: string; }
            interface ParameterDeclaration { readonly kind: SyntaxKind.Parameter; p: string; }
            interface Node { readonly kind: SyntaxKind; }
            declare namespace Debug {
                export function type<T>(value: unknown): asserts value is T;
            }
            declare function assertType<T>(_: T): void;
            function outer() {
                return getDiagnostic;
                type LocalUnion = GetAccessorDeclaration | SetAccessorDeclaration | ParameterDeclaration;
                function getDiagnostic(node: Node) {
                    Debug.type<LocalUnion>(node);
                    switch (node.kind) {
                        case SyntaxKind.GetAccessor:
                        case SyntaxKind.SetAccessor:
                        case SyntaxKind.Parameter:
                            return true;
                        default:
                            assertType<never>(node);
                            return false;
                    }
                }
            }
        """.trimIndent()) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `Exclude member in the assert target union expands member-wise`() {
        diagnose("""
            enum SyntaxKind { Parameter = 1, PropertyDeclaration = 2, JsxAttribute = 3, EnumMember = 4, BinaryExpression = 5 }
            interface Node { readonly kind: SyntaxKind; }
            interface ParameterDeclaration extends Node { readonly kind: SyntaxKind.Parameter; p: string; }
            interface PropertyDeclaration extends Node { readonly kind: SyntaxKind.PropertyDeclaration; q: string; }
            interface JsxAttribute extends Node { readonly kind: SyntaxKind.JsxAttribute; j: string; }
            interface EnumMember extends Node { readonly kind: SyntaxKind.EnumMember; e: string; }
            interface BinaryExpression extends Node { readonly kind: SyntaxKind.BinaryExpression; b: string; }
            type VariableLikeDeclaration = ParameterDeclaration | PropertyDeclaration | JsxAttribute | EnumMember;
            type HasInferredType =
                | Exclude<VariableLikeDeclaration, JsxAttribute | EnumMember>
                | BinaryExpression;
            declare namespace Debug {
                export function type<T>(value: unknown): asserts value is T;
            }
            declare function assertType<T>(_: T): void;
            function hasInferredType(node: Node): boolean {
                Debug.type<HasInferredType>(node);
                switch (node.kind) {
                    case SyntaxKind.Parameter:
                    case SyntaxKind.PropertyDeclaration:
                    case SyntaxKind.BinaryExpression:
                        return true;
                    default:
                        assertType<never>(node);
                        return false;
                }
            }
        """.trimIndent()) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - an EXCLUDED member's case is not covered so default stays non-never`() {
        diagnose("""
            enum SyntaxKind { Parameter = 1, JsxAttribute = 2 }
            interface Node { readonly kind: SyntaxKind; }
            interface ParameterDeclaration extends Node { readonly kind: SyntaxKind.Parameter; p: string; }
            interface JsxAttribute extends Node { readonly kind: SyntaxKind.JsxAttribute; j: string; }
            type U = Exclude<ParameterDeclaration | JsxAttribute, JsxAttribute> | JsxAttribute;
            declare namespace Debug {
                export function type<T>(value: unknown): asserts value is T;
            }
            declare function assertType<T>(_: T): void;
            function f(node: Node): boolean {
                Debug.type<U>(node);
                switch (node.kind) {
                    case SyntaxKind.Parameter:
                        return true;
                    default:
                        assertType<never>(node);
                        return false;
                }
            }
        """.trimIndent()) should {
            have(any { it.code == 2345 })
        }
    }
}
