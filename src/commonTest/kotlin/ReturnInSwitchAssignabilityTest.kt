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
 * Round 431c (M3.1): `returnTypeNode` threading through the switch/try/if arms of
 * BOTH assignability dispatchers (checkTypeAssignabilityInStatements /
 * checkTypeAssignabilityInStmt). A `return` inside a switch case or try block
 * previously fell to the STRING-based return check (returnTypeNode dropped at
 * those arms), which cannot resolve a type-alias union — `return undefined`
 * against `VisitResult<Node | undefined>` (= `T | readonly Node[]`) FP'd TS2322
 * ×12 on the tsc self-compile (esDecorators/declarations fallbackVisitor).
 * The engine path resolves the alias and accepts the union member.
 */
class ReturnInSwitchAssignabilityTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `return undefined in a switch case against an alias union with undefined is clean`() {
        val d = diags(
            """
            interface Node2 { kind: number; }
            type VisitResult<T extends Node2 | undefined> = T | readonly Node2[];
            export function fallbackVisitor(node: Node2): VisitResult<Node2 | undefined> {
                switch (node.kind) {
                    case 1:
                        return undefined;
                    default:
                        return node;
                }
            }
            """
        )
        assertTrue(d.none { it.code == 2322 }, "expected no TS2322, got: $d")
    }

    @Test
    fun `return undefined in a try block against a nullable union is clean`() {
        val d = diags(
            """
            interface Node2 { kind: number; }
            type MaybeNode = Node2 | undefined;
            export function f(node: Node2): MaybeNode {
                try {
                    return undefined;
                } catch {
                    return node;
                }
            }
            """
        )
        assertTrue(d.none { it.code == 2322 }, "expected no TS2322, got: $d")
    }

    @Test
    fun `negative control - a genuine mismatch in a switch case still fires`() {
        val d = diags(
            """
            export function f(k: number): number {
                switch (k) {
                    case 1:
                        return "not a number";
                    default:
                        return 0;
                }
            }
            """
        )
        assertTrue(d.any { it.code == 2322 }, "expected TS2322 for string-vs-number, got: $d")
    }

    @Test
    fun `negative control - undefined against a non-nullable switch return still fires`() {
        val d = diags(
            """
            interface Node2 { kind: number; }
            export function f(node: Node2): Node2 {
                switch (node.kind) {
                    case 1:
                        return undefined;
                    default:
                        return node;
                }
            }
            """
        )
        assertTrue(d.any { it.code == 2322 }, "expected TS2322 for undefined-vs-Node2, got: $d")
    }
}
