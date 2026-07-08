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
 * Round 448 (self-compile burn-down, services): a fresh object literal returned against a UNION of
 * object types discriminated by a LITERAL property (`return { type: "cases" }` vs
 * `... | { type: "cases"; } | { type: "none"; } | ...`). getTypeOfObjectLiteral WIDENS the discriminant
 * to its base primitive (`{ type: string }`), so it matched no union member and the coarse return
 * relation FP-fired TS2322. The return path now retries the union relation with `freshObjLitRange` set
 * (round 435), recovering the un-widened literal per member so the object relates to its discriminated
 * member (completions.ts getSymbolCompletionFromEntryId — 5 returns → 0). Suppression-only: an object
 * matching no member still fires.
 */
class DiscriminatedUnionReturnTest {

    private val prelude = """
        // @strict: true
        interface Sym { type: "symbol"; symbol: number; location: number; }
        declare const cd: number;
    """.trimIndent() + "\n"

    @Test
    fun `object literals returned against a discriminated union do not FP TS2322`() {
        diagnose(
            prelude + """
            function f(x: number):
                Sym | { type: "request"; request: number; } | { type: "literal"; literal: string; }
                    | { type: "cases"; } | { type: "none"; } {
                if (x === 0) return { type: "cases" };
                if (x === 1) return { type: "none" };
                if (x === 2) return { type: "request", request: cd };
                if (x === 3) return { type: "literal", literal: "a" };
                return { type: "symbol", symbol: 1, location: 2 };
            }
            """.trimIndent(),
            directives = "",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `firewall - an object with a non-matching discriminant still FP-fires TS2322`() {
        diagnose(
            prelude + """
            function bad(x: number): Sym | { type: "cases"; } | { type: "none"; } {
                if (x === 0) return { type: "wrong" };
                return { type: "cases" };
            }
            """.trimIndent(),
            directives = "",
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `firewall - a matching discriminant but a wrong property TYPE still FP-fires TS2322`() {
        diagnose(
            prelude + """
            function bad(x: number): Sym | { type: "cases"; } {
                return { type: "symbol", symbol: "s", location: 2 };
            }
            """.trimIndent(),
            directives = "",
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
