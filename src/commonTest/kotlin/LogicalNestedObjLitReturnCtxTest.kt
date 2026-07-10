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
 * Round 468: the return-path object-literal CONTEXT (round 462/464b) also reaches
 * an objlit nested as the RIGHT operand of a returned `&&`/`||`/`??` — tsc
 * distributes the contextual type through logical operands, so
 * `return refs && { refs, d: n }` (tsc inlineVariable's getInliningInfo shape)
 * gets the per-property context that lets guard-narrowed values substitute.
 */
class LogicalNestedObjLitReturnCtxTest {

    private val prelude = """
        interface Base { kind: string; }
        interface Derived extends Base { extra: number; }
        declare function isDerived(n: Base): n is Derived;
        declare function getRefs(n: Base): string[] | undefined;
    """.trimIndent()

    @Test
    fun `an and-nested object literal gets the return context for guard-narrowed values`() {
        diagnose(
            prelude + """

            interface Info { refs: string[]; d: Derived; }
            function getInfo(n: Base): Info | undefined {
                if (!isDerived(n)) return undefined;
                const refs = getRefs(n);
                return refs && { refs, d: n };
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `an and-nested object literal gets the context from a multi-member union target`() {
        diagnose(
            prelude + """

            interface Info { refs: string[]; d: Derived; }
            interface ErrorInfo { error: string; }
            function getInfo(n: Base): Info | ErrorInfo | undefined {
                if (!isDerived(n)) return { error: "no" };
                const refs = getRefs(n);
                return refs && { refs, d: n };
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an un-narrowed value in the and-nested object literal still fires`() {
        diagnose(
            prelude + """

            interface Info { refs: string[]; d: Derived; }
            function getInfo(n: Base): Info | undefined {
                const refs = getRefs(n);
                return refs && { refs, d: n };
            }
            """,
        ) should {
            // No guard — `n` stays Base, genuinely not assignable to Derived.
            have(any { it.code == 2322 })
        }
    }
}
