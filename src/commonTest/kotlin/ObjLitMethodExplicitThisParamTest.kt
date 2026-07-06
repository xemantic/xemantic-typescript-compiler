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
 * Round 428b (M3.1): inside an object-literal method, an EXPLICIT `this` PARAMETER
 * annotation wins over the object-literal contextual `this` in the call-types walker.
 * tsc debug.ts's Object.defineProperties idiom:
 *
 *   Object.defineProperties(ctor.prototype, {
 *       __tsDebuggerDisplay: {
 *           value(this: Node) { return isIdentifier(this) ? ... : ...; }
 *       },
 *   });
 *
 * typed `this` as `{ value(this: Node): any; }` (the objlit) and FP'd TS2345 ×36
 * at every `isFoo(this)` call.
 */
class ObjLitMethodExplicitThisParamTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `explicit this param annotation types this inside objlit method`() {
        val d = diags(
            """
            interface Node2 { kind: number; }
            declare function isIdentifier(node: Node2): boolean;
            export const props = {
                value(this: Node2) {
                    return isIdentifier(this);
                },
            };
            """
        )
        assertTrue(d.none { it.code == 2345 }, "expected no TS2345, got: $d")
    }

    @Test
    fun `negative control - objlit this without this-param still checks against objlit type`() {
        // Without an explicit `this` param, the objlit contextual this stays: passing
        // `this` (the objlit) where a Node2 is required still fires TS2345.
        val d = diags(
            """
            interface Node2 { kind: number; }
            declare function isIdentifier(node: Node2): boolean;
            export const props = {
                value() {
                    return isIdentifier(this);
                },
            };
            """
        )
        assertTrue(d.any { it.code == 2345 }, "expected TS2345 for objlit this vs Node2, got: $d")
    }

    @Test
    fun `guard-narrowed this arg relates through the refined type`() {
        // debug.ts's __tsDebuggerDisplay: `isIdentifier(this) ? idText(this) : …` —
        // the ternary guard narrows `this: Node2` DOWN to Identifier2, so the
        // idText(MemberName-ish) arg check must pass.
        val d = diags(
            """
            interface Node2 { kind: number; }
            interface Identifier2 extends Node2 { kind: 80; text: string; }
            declare function isIdentifier(node: Node2): node is Identifier2;
            declare function idText(node: Identifier2): string;
            export const props = {
                value(this: Node2) {
                    return isIdentifier(this) ? idText(this) : "other";
                },
            };
            """
        )
        assertTrue(d.none { it.code == 2345 }, "expected no TS2345, got: $d")
    }

    @Test
    fun `numeric enum arg is assignable to a number param`() {
        // debug.ts's formatEnum(this.flags, …) — FlowFlags (numeric enum) vs `number`.
        val d = diags(
            """
            enum FlowFlags { Unreachable = 1, Start = 2 }
            interface FlowNode2 { flags: FlowFlags; }
            declare function formatEnum(value: number, isFlags: boolean): string;
            export const props = {
                get(this: FlowNode2) {
                    return formatEnum(this.flags, true);
                },
            };
            """
        )
        assertTrue(d.none { it.code == 2345 }, "expected no TS2345, got: $d")
    }

    @Test
    fun `unresolvable this-param annotation binds nothing rather than the objlit`() {
        val d = diags(
            """
            declare function wantsNumber(x: number): void;
            export const props = {
                value(this: SomeUnknownType) {
                    wantsNumber(this as any);
                },
            };
            """
        )
        assertTrue(d.none { it.code == 2345 }, "expected no TS2345, got: $d")
    }
}
