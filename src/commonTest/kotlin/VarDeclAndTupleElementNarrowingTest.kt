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
 * Round 461: guard-narrowed references reach two more consumers.
 *
 * 1. The VAR-DECL initializer gets the same relation-passes-gated flow narrowing the
 *    assignment (round 410/438/456) and return (round 413/438) paths already have —
 *    a named-object / union / intersection annotated `let x: U = init` narrows a
 *    reference init by preceding guards (tsc parser.ts:6245 parseJsxElementName).
 *
 * 2. [elementAssignableToSlot] (the round-446 array-literal→variadic-tuple AST check)
 *    narrows a reference ELEMENT — `isString(x) ? [x] : x[0]` checks the narrowed
 *    `string`, not the declared union (tsc builder.ts:518 getNewEmitSignature vs
 *    `EmitSignature = string | [signature: string]`).
 */
class VarDeclAndTupleElementNarrowingTest {

    @Test
    fun `var-decl initializer narrowed by a preceding negative guard relates to the union annotation`() {
        diagnose("""
            interface Identifier2 { kind: 80; text: string }
            interface ThisExpr { kind: 110 }
            interface PropAccess { kind: 211; name: string }
            interface JsxNsName { kind: 295; namespace: string }
            type JsxTagNameExpression = Identifier2 | ThisExpr | PropAccess | JsxNsName;
            declare function parseJsxTagName(): JsxTagNameExpression;
            declare function isJsxNamespacedName(node: JsxTagNameExpression): node is JsxNsName;
            export function parseJsxElementName(): PropAccess | Identifier2 | ThisExpr {
                const initialExpression = parseJsxTagName();
                if (isJsxNamespacedName(initialExpression)) {
                    return null as any;
                }
                let expression: PropAccess | Identifier2 | ThisExpr = initialExpression;
                return expression;
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an unguarded init against the narrower union annotation still fires`() {
        diagnose("""
            interface Identifier2 { kind: 80; text: string }
            interface ThisExpr { kind: 110 }
            interface PropAccess { kind: 211; name: string }
            interface JsxNsName { kind: 295; namespace: string }
            type JsxTagNameExpression = Identifier2 | ThisExpr | PropAccess | JsxNsName;
            declare function parseJsxTagName(): JsxTagNameExpression;
            export function parseJsxElementName(): void {
                const initialExpression = parseJsxTagName();
                let expression: PropAccess | Identifier2 | ThisExpr = initialExpression;
            }
        """) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `tuple-slot element uses its guard-narrowed type in a ternary return arm`() {
        diagnose("""
            export type EmitSignature = string | [signature: string];
            declare function isString(x: unknown): x is string;
            export function getNewEmitSignature(flag: boolean, oldEmitSignature: EmitSignature): EmitSignature {
                return flag ?
                    oldEmitSignature :
                    isString(oldEmitSignature) ? [oldEmitSignature] : oldEmitSignature[0];
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an element whose narrowed type still mismatches the tuple slot fires`() {
        diagnose("""
            export type EmitSignature = string | [signature: string];
            declare function isNum(x: unknown): x is number;
            export function f(flag: boolean, v: number | boolean): EmitSignature {
                return isNum(v) ? [v] : "x";
            }
        """) should {
            have(any { it.code == 2322 })
        }
    }
}
