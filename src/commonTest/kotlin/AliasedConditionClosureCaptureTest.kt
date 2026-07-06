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
 * Round 424 — the aliased-condition back-walk follows a closure boundary and
 * if/else joins (tsc builder.ts `canCopyEmitSignatures`):
 *
 *     const canCopy = options.composite && oldState?.emitSignatures && …;
 *     if (useOldState) { … } else { … }        // ← a FlowBranchLabel on the path
 *     files.forEach(path => {                  // ← a FlowStart on the path
 *         if (canCopy) { oldState.emitSignatures.get(path) }  // narrows oldState
 *     });
 *
 * The round-423 back-walk bailed at ANY node that wasn't a plain assignment or
 * condition, so an alias declared OUTSIDE the closure never narrowed inside it.
 * Now: a FlowStart follows the closure's outer flow gated by the B464
 * captured-name rules applied to BOTH the alias name and the walked reference's
 * root; a FlowBranchLabel requires every reachable antecedent to independently
 * prove value preservation and land on the same declaration; FlowCall /
 * FlowArrayMutation are value-preserving (a call cannot rebind an enclosing
 * let/const binding — tsc's const-alias narrowing likewise ignores
 * closure-mediated rebinding, gating on isConstantVariable).
 */
class AliasedConditionClosureCaptureTest {

    private val prelude = """
        interface OldState { emitSignatures?: Map<string, string>; compilerOptions: object; }
        interface Options { composite?: boolean; }
        declare function affectsPath(a: object, b: object): boolean;
    """

    private fun diags(body: String): List<Diagnostic> =
        TypeScriptCompiler().compile(
            "// @strict: true\n" + (prelude + body).trimIndent(), "t.ts",
        ).diagnostics

    @Test
    fun capturedAliasNarrowsInsideClosureAcrossIfElseJoin() {
        val d = diags(
            """
            export function createState(options: Options, oldState: OldState | undefined, useOldState: boolean, files: Map<string, string>) {
                const canCopy = options.composite &&
                    oldState?.emitSignatures &&
                    !affectsPath(options, oldState.compilerOptions);
                if (useOldState) {
                    console.log("old");
                }
                else {
                    console.log("fresh");
                }
                files.forEach((_value, sourceFilePath) => {
                    if (canCopy) {
                        const sig = oldState.emitSignatures.get(sourceFilePath);
                        console.log(sig);
                    }
                });
            }
            """,
        )
        assertTrue(
            d.none { it.code == 18048 },
            "a captured const alias must narrow oldState inside the closure; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun reassignmentInsideClosureBeforeTestStillFires() {
        val d = diags(
            """
            export function g(options: Options, oldState: OldState | undefined, files: Map<string, string>, fresh: OldState | undefined) {
                const canCopy = options.composite && oldState?.emitSignatures;
                files.forEach(() => {
                    oldState = fresh;
                    if (canCopy) {
                        console.log(oldState.emitSignatures);
                    }
                });
            }
            """,
        )
        assertTrue(
            d.any { it.code == 18048 },
            "a closure-internal reassignment of the walked root must keep TS18048; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun reassignmentAfterClosureStillFires() {
        // The B464 reassignedAfterNames gate: an assignment at/after the closure
        // in the enclosing function withholds the captured narrowing (the closure
        // may run after the reassignment).
        val d = diags(
            """
            export function h(options: Options, oldState: OldState | undefined, files: Map<string, string>, fresh: OldState | undefined) {
                const canCopy = options.composite && oldState?.emitSignatures;
                files.forEach(() => {
                    if (canCopy) {
                        console.log(oldState.emitSignatures);
                    }
                });
                oldState = fresh;
            }
            """,
        )
        assertTrue(
            d.any { it.code == 18048 },
            "a post-closure reassignment of the walked root must keep TS18048; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun mutableAliasReassignedBetweenDeclAndClosureStillFires() {
        val d = diags(
            """
            export function k(options: Options, oldState: OldState | undefined, files: Map<string, string>, other: boolean) {
                let canCopy = options.composite && oldState?.emitSignatures;
                canCopy = other ? undefined : canCopy;
                files.forEach(() => {
                    if (canCopy) {
                        console.log(oldState.emitSignatures);
                    }
                });
            }
            """,
        )
        assertTrue(
            d.any { it.code == 18048 },
            "an alias reassigned between its declaration and the closure must keep TS18048; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
