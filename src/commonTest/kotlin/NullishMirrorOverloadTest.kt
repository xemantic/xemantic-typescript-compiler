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
 * Round 463: the NULLISH-MIRROR overload idiom in flow narrowing — a nested
 * overload cluster `f(x: T, …): R;` / `f(x: T | undefined, …): R | undefined;`
 * (+ impl), tsc's `instantiateType`. The unique-decl resolver treats ≥2 same-named
 * declarations as ambiguous, so `type.restrictiveInstantiation =
 * instantiateType(type, mapper)` never proved the property path non-nullish and
 * the immediately-following read FP'd TS18048 (checker.ts:21170). tsc picks the
 * FIRST applicable overload; between the two mirror signatures the only
 * applicability dimension is argument nullishness, so a provably non-nullish
 * argument at every conditional position selects the first (non-nullish-return)
 * signature. Also pins the barrel-import fallback in typeNodeDefinitelyNonNullish
 * (interface/class/enum via merged globals — never type aliases, the round-443
 * conflation trap).
 */
class NullishMirrorOverloadTest {

    private val prelude = """
        interface Type { flags: number; restrictiveInstantiation?: Type; }
        interface TypeMapper { kind: number; }
        declare const restrictiveMapper: TypeMapper;
    """.trimIndent()

    @Test
    fun `a non-nullish arg to a nullish-mirror overload pair proves the assigned property path non-nullish`() {
        diagnose(prelude + """
            function outer() {
                function getRestrictiveInstantiation(type: Type) {
                    if (type.restrictiveInstantiation) {
                        return type.restrictiveInstantiation;
                    }
                    type.restrictiveInstantiation = instantiateType(type, restrictiveMapper);
                    type.restrictiveInstantiation.restrictiveInstantiation = type.restrictiveInstantiation;
                    return type.restrictiveInstantiation;
                }
                function instantiateType(type: Type, mapper: TypeMapper | undefined): Type;
                function instantiateType(type: Type | undefined, mapper: TypeMapper | undefined): Type | undefined;
                function instantiateType(type: Type | undefined, mapper: TypeMapper | undefined): Type | undefined {
                    return type;
                }
                return getRestrictiveInstantiation;
            }
        """) should {
            have(none { it.code == 18048 })
        }
    }

    @Test
    fun `negative control - a nullable arg makes no claim and the read stays possibly undefined`() {
        diagnose(prelude + """
            function outer(maybe: Type | undefined) {
                function getRestrictiveInstantiation(type: Type) {
                    if (type.restrictiveInstantiation) {
                        return type.restrictiveInstantiation;
                    }
                    type.restrictiveInstantiation = instantiateType(maybe, restrictiveMapper);
                    type.restrictiveInstantiation.restrictiveInstantiation = type.restrictiveInstantiation;
                    return type.restrictiveInstantiation;
                }
                function instantiateType(type: Type, mapper: TypeMapper | undefined): Type;
                function instantiateType(type: Type | undefined, mapper: TypeMapper | undefined): Type | undefined;
                function instantiateType(type: Type | undefined, mapper: TypeMapper | undefined): Type | undefined {
                    return type;
                }
                return getRestrictiveInstantiation;
            }
        """) should {
            have(any { it.code == 18048 })
        }
    }

    @Test
    fun `negative control - a nullish-FIRST overload pair makes no claim`() {
        diagnose(prelude + """
            function outer() {
                function getRestrictiveInstantiation(type: Type) {
                    if (type.restrictiveInstantiation) {
                        return type.restrictiveInstantiation;
                    }
                    type.restrictiveInstantiation = instantiateType(type, restrictiveMapper);
                    type.restrictiveInstantiation.restrictiveInstantiation = type.restrictiveInstantiation;
                    return type.restrictiveInstantiation;
                }
                function instantiateType(type: Type | undefined, mapper: TypeMapper | undefined): Type | undefined;
                function instantiateType(type: Type, mapper: TypeMapper | undefined): Type;
                function instantiateType(type: Type | undefined, mapper: TypeMapper | undefined): Type | undefined {
                    return type;
                }
                return getRestrictiveInstantiation;
            }
        """) should {
            have(any { it.code == 18048 })
        }
    }
}
