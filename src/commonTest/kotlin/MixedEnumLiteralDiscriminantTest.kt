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
 * Round 422 (M3.4): a discriminated union MIXING string-enum-member discriminants with a
 * plain string-LITERAL discriminant narrows on the unified key space — tsc's
 * PrivateIdentifierInfo (`kind: PrivateIdentifierKind.Accessor | … | "untransformed"`,
 * classFields.ts). Before, the literal-typed member had no representation in the enum key
 * space, so it survived EVERY enum-member case and the over-wide union FP'd TS2339 on
 * variant-specific properties. The `lit:s:` keys are string-only and DISJOINT from enum
 * keys (a string enum member never equals a plain string literal in tsc narrowing;
 * numeric enums ARE number-comparable, so numeric literals stay conservatively KEPT).
 */
class MixedEnumLiteralDiscriminantTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    private val decls = """
        export const enum PrivateIdentifierKind { Field = "f", Method = "m", Accessor = "a" }
        interface AccessorInfo { kind: PrivateIdentifierKind.Accessor; brandCheckIdentifier: string; getterName?: string; }
        interface MethodInfo { kind: PrivateIdentifierKind.Method; brandCheckIdentifier: string; methodName: string; }
        interface FieldInfo { kind: PrivateIdentifierKind.Field; brandCheckIdentifier: string; isStatic: boolean; variableName: string; }
        interface UntransformedInfo { kind: "untransformed"; }
        type PrivateIdentifierInfo = AccessorInfo | MethodInfo | FieldInfo | UntransformedInfo;
    """

    @Test
    fun `enum-member switch cases drop the string-literal member`() {
        val d = diags(
            """
            $decls
            export function helper(info: PrivateIdentifierInfo): string | undefined {
                switch (info.kind) {
                    case PrivateIdentifierKind.Accessor:
                        return info.getterName;
                    case PrivateIdentifierKind.Method:
                        return info.methodName;
                    case PrivateIdentifierKind.Field:
                        return info.isStatic ? info.variableName : undefined;
                    case "untransformed":
                        return undefined;
                    default:
                        return undefined;
                }
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2339 },
            "each enum-member case must narrow out the `kind: \"untransformed\"` member; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `equality guard on an enum member drops the string-literal member`() {
        val d = diags(
            """
            $decls
            export function f(info: PrivateIdentifierInfo): string | undefined {
                if (info.kind === PrivateIdentifierKind.Accessor) {
                    return info.getterName;
                }
                return undefined;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2339 },
            "`info.kind === Kind.Accessor` must narrow out the literal-typed member; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `wrong-variant property access still fires`() {
        val d = diags(
            """
            $decls
            export function bad(info: PrivateIdentifierInfo): string | undefined {
                switch (info.kind) {
                    case PrivateIdentifierKind.Method:
                        return info.getterName;
                    default:
                        return undefined;
                }
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2339 && "getterName" in it.message },
            "accessing an Accessor-only property in the Method case must keep firing TS2339; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `pure string-literal discriminated union still narrows via the literal path`() {
        val d = diags(
            """
            interface A { kind: "a"; onlyA: string; }
            interface B { kind: "b"; onlyB: string; }
            export function f(x: A | B): string {
                switch (x.kind) {
                    case "a": return x.onlyA;
                    case "b": return x.onlyB;
                }
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2339 },
            "pure-literal discriminant switches must keep narrowing; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `numeric-literal discriminant member is conservatively kept in a numeric-enum case`() {
        // A NUMERIC enum member and a same-valued numeric literal ARE comparable in tsc,
        // so the literal member must NOT be dropped from the enum-member case — tsc keeps
        // it too, and both report TS2339 for a property only on the enum-annotated variant.
        val d = diags(
            """
            export const enum NumKind { A = 1, B = 2 }
            interface EnumVariant { kind: NumKind.A; onlyEnum: string; }
            interface LitVariant { kind: 1; onlyLit: string; }
            interface Other { kind: NumKind.B; }
            export function f(x: EnumVariant | LitVariant | Other): string | undefined {
                switch (x.kind) {
                    case NumKind.A:
                        return x.onlyEnum;
                    default:
                        return undefined;
                }
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2339 && "onlyEnum" in it.message },
            "the `kind: 1` member must be conservatively KEPT (numeric enums are " +
                "number-comparable), so `x.onlyEnum` stays an error exactly as in tsc; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
