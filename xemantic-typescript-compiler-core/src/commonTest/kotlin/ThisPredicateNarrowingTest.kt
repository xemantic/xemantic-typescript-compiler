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
 * Round 444 (self-compile burn-down): a `this is X` type-guard METHOD (`isUnion(): this is UnionType`,
 * exactly the tsc `Type`/`Symbol`/`Node` public-API guards) narrows the method-call RECEIVER, not an
 * argument. `narrowByCallPredicate` bailed on it twice: (1) the `this` subject of a `this is X`
 * predicate parses as a `ThisType` node (not an Identifier), so `predicateParamName` extraction
 * returned null; (2) even with the name, the narrowed reference is the receiver, not an argument, so
 * the arg-path fast-path/paramIdx logic never matched. Consequence: `type.isUnion() ? type.types :
 * [type]` FP'd TS2339 on `type.types` (the `Type` receiver stayed un-narrowed, and `UnionType.types`
 * is not on `Type`) — `.types` on `Type` ×20 on the services profile.
 *
 * Fix: `narrowByCallPredicate` recognises a `ThisType` predicate subject as `"this"`, computes the
 * method-call receiver's reference path up front (participating in the P0 fast-path), and — for a
 * `this` predicate — narrows the RECEIVER via the existing single-type/union narrowing logic.
 * Suppression-only (only narrows the receiver DOWN to the guard's declared subtype).
 */
class ThisPredicateNarrowingTest {

    private val prelude = """
        interface Base { flags: number; }
        interface UnionT extends Base { types: Base[]; }
        interface Base {
            isUnion(): this is UnionT;
        }
        declare const t: Base;
    """.trimIndent() + "\n"

    @Test
    fun `a this-predicate method narrows the receiver in a ternary - no TS2339`() {
        diagnose(prelude + "const arr: Base[] = t.isUnion() ? t.types : [t];") should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a this-predicate method narrows the receiver in an if body - no TS2339`() {
        diagnose(
            prelude + """
            function f(): Base[] {
                if (t.isUnion()) {
                    return t.types;
                }
                return [t];
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a this-predicate guard on a DIFFERENT receiver does not narrow this one - TS2339`() {
        // Firewall: the guard narrows only its own receiver. `s.isUnion()` must not narrow `t`, so
        // `t.types` (types is on UnionT, not Base) still fires — the narrowing is receiver-scoped.
        diagnose(
            prelude + """
            declare const s: Base;
            const arr: Base[] = s.isUnion() ? t.types : [t];
            """.trimIndent()
        ) should {
            have(any { it.code == 2339 && it.message.contains("types") })
        }
    }

    @Test
    fun `a this-predicate added by a cross-file interface augmentation narrows - no TS2339`() {
        // Mirrors tsc: `Type.isUnion()` is added to compiler/types.ts's `Type` via a
        // `declare module "../compiler/types.js"` augmentation in services/types.ts.
        TypeScriptCompiler().compile(
            """
            // @strict: true
            // @module: nodenext
            // @moduleResolution: nodenext

            // @Filename: types.ts
            export interface Type { flags: number; }
            export interface UnionType extends Type { types: Type[]; }

            // @Filename: services.ts
            import * as types from "./types.js";
            declare module "./types.js" {
                export interface Type {
                    isUnion(): this is UnionType;
                }
            }
            export function f(t: types.Type): types.Type[] {
                return t.isUnion() ? t.types : [t];
            }
            """.trimIndent(),
            "services.ts"
        ).diagnostics should {
            have(none { it.code == 2339 })
        }
    }
}
