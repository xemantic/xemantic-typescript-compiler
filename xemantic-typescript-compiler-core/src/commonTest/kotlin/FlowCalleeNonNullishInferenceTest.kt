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
 * Pins the round-464 fix cluster that clears tsc's `getTypeAtFlowNode`
 * (checker.ts:29132 — `return type;` where `let type: FlowType | undefined`
 * is assigned in every if/else-if branch):
 *
 *  1. a BARREL-imported UNAMBIGUOUS type alias (`FlowType = Type |
 *     IncompleteType`) proves non-nullish through the merged-globals
 *     fallback in [typeNodeDefinitelyNonNullish] (the declarations list is
 *     polluted with ImportSpecifiers — the gate counts TypeAliasDeclarations);
 *  2. an UN-ANNOTATED flow callee proves non-nullishness from its body
 *     returns (`convertAutoToAny(type: Type) { return t === a ? anyType :
 *     type; }`), with closure-var ternary leaves resolved via the
 *     program-wide unique-name map ([uniqueNestedVarDeclByName]);
 *  3. an un-annotated param DEFAULTED from an annotated sibling
 *     (`initialType = declaredType`) types as the sibling's annotation.
 */
class FlowCalleeNonNullishInferenceTest {

    private fun build(@Language("typescript") checkerSource: String): ProjectCompiler.Result {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to
                    """{ "compilerOptions": { "strict": true, "module": "nodenext", "target": "es2020" }, "include": ["src/**/*"] }""",
                "/proj/src/types.ts" to """
                    export interface Type { id: number; }
                    export interface IncompleteType { flags: 0; type: Type; }
                    export type FlowType = Type | IncompleteType;
                    export interface FlowNode { flags: number; antecedent: FlowNode; }
                """.trimIndent(),
                "/proj/src/barrel.ts" to """export * from "./types.js";""",
                "/proj/src/checker.ts" to checkerSource.trimIndent(),
            )
        )
        return ProjectCompiler(vfs).build("/proj", noEmit = true)
    }

    @Test
    fun `the getTypeAtFlowNode composite - every branch proves non-nullish so the return relates`() {
        val result = build(
            """
            import { Type, FlowType, FlowNode } from "./barrel.js";
            export function createChecker() {
                var autoType = mkType();
                var anyType2 = mkType();
                function mkType(): Type { return { id: 0 }; }
                function getFlowTypeOfReference(declaredType: Type, initialType = declaredType) {
                    return getTypeAtFlowNode;
                    function getTypeAtFlowCondition(f: FlowNode): FlowType { return initialType; }
                    function getTypeAtFlowBranchLabel(f: FlowNode): FlowType { return initialType; }
                    function getTypeAtFlowLoopLabel(f: FlowNode): FlowType { return initialType; }
                    function convertAutoToAny(type: Type) {
                        return type === autoType ? anyType2 : type;
                    }
                    function getTypeAtFlowNode(flow: FlowNode): FlowType {
                        while (true) {
                            const flags = flow.flags;
                            let type: FlowType | undefined;
                            if (flags & 32) {
                                type = getTypeAtFlowCondition(flow);
                            }
                            else if (flags & 64) {
                                type = flags & 1024 ?
                                    getTypeAtFlowBranchLabel(flow) :
                                    getTypeAtFlowLoopLabel(flow);
                            }
                            else if (flags & 2) {
                                type = initialType;
                            }
                            else {
                                type = convertAutoToAny(declaredType);
                            }
                            return type;
                        }
                    }
                }
                return getFlowTypeOfReference;
            }
            """
        )
        result.diagnostics should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an un-annotated callee with a bare return-semicolon keeps the undefined`() {
        val result = build(
            """
            import { Type, FlowType, FlowNode } from "./barrel.js";
            export function createChecker() {
                function maybe(t: Type) {
                    if (t.id > 0) return t;
                    return undefined;
                }
                function f(flow: FlowNode, declaredType: Type): FlowType {
                    let type: FlowType | undefined;
                    type = maybe(declaredType);
                    return type;
                }
                return f;
            }
            """
        )
        result.diagnostics should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a sibling-defaulted param from a NULLABLE sibling stays unproven`() {
        val result = build(
            """
            import { Type, FlowType, FlowNode } from "./barrel.js";
            export function f(declaredType: Type | undefined, initialType = declaredType): FlowType {
                let type: FlowType | undefined;
                type = initialType;
                return type;
            }
            """
        )
        result.diagnostics should {
            have(any { it.code == 2322 })
        }
    }
}
