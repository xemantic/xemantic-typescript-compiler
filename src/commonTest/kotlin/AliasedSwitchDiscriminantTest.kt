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
 * M3.4 (round 425): a `switch` on an ALIASED discriminant narrows the aliased
 * receiver — `const kind1 = m1.kind; switch (kind1) { case Kind.Simple:
 * m1.source }` (tsc's own `compareTypeMappers`, TypeScript 4.4 "CFA of aliased
 * conditions and discriminants"). The round-423 flow back-walk
 * (`aliasedConditionInitializer`) is the const-ness proof: it bails on
 * reassignment of the alias or of the walked root between the switch and the
 * alias declaration, so the alias provably still holds `<ref>.kind`'s value.
 */
class AliasedSwitchDiscriminantTest {

    private val mapperShape = """
        interface Type { id: number }
        const enum TypeMapKind { Simple, Array, Function, Composite }
        type TypeMapper =
            | { kind: TypeMapKind.Simple; source: Type; target: Type; }
            | { kind: TypeMapKind.Array; sources: readonly Type[]; }
            | { kind: TypeMapKind.Function; func: (t: Type) => Type; }
            | { kind: TypeMapKind.Composite; mapper1: TypeMapper; mapper2: TypeMapper; };
        declare function compareTypes(a: Type, b: Type): number;
    """.trimIndent()

    @Test
    fun `switch on an aliased enum discriminant narrows the receiver`() {
        diagnose(
            mapperShape + """

            function compareTypeMappers(m1: TypeMapper) {
                const kind1 = m1.kind;
                switch (kind1) {
                    case TypeMapKind.Simple:
                        return compareTypes(m1.source, m1.target);
                    case TypeMapKind.Composite:
                        return m1.mapper1;
                }
                return 0;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `reassigned receiver between alias and switch withholds the narrowing`() {
        diagnose(
            mapperShape + """

            declare function otherMapper(): TypeMapper;
            function f(m1: TypeMapper) {
                const kind1 = m1.kind;
                m1 = otherMapper();
                switch (kind1) {
                    case TypeMapKind.Simple:
                        // m1 was reassigned after the alias captured its kind —
                        // the narrowing must NOT apply; the access is a genuine error.
                        return m1.source;
                }
                return 0;
            }
            """
        ) should {
            have(any { it.code == 2339 })
        }
    }

    @Test
    fun `reassigned alias before the switch withholds the narrowing`() {
        diagnose(
            mapperShape + """

            function f(m1: TypeMapper, m2: TypeMapper) {
                let kind1 = m1.kind;
                kind1 = m2.kind;
                switch (kind1) {
                    case TypeMapKind.Simple:
                        return m1.source;
                }
                return 0;
            }
            """
        ) should {
            have(any { it.code == 2339 })
        }
    }

    @Test
    fun `direct discriminant switch keeps working`() {
        diagnose(
            mapperShape + """

            function f(m1: TypeMapper) {
                switch (m1.kind) {
                    case TypeMapKind.Simple:
                        return compareTypes(m1.source, m1.target);
                }
                return 0;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }
}
