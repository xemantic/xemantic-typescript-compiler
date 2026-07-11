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
 * Round 474 (the tsc project.ts `this.autoImportProviderHost = false` family): a
 * LITERAL property write whose literal the target's declared union annotation
 * SYNTACTICALLY contains is always legal — the engine widened the literal
 * (`false` → `boolean`) before the relation, FP'ing against
 * `AutoImportProviderProject | false | undefined`. [checkPropertyAccessAssignment]
 * reuses the round-436c syntactic membership proof
 * ([returnUnionSyntacticallyContainsLiteral]).
 */
class PropertyWriteLiteralUnionMemberTest {

    private val prelude = """
        class AutoImportProviderProject {
            marker = 1;
        }
    """.trimIndent()

    @Test
    fun `a false literal write to a union containing false is legal`() {
        diagnose(
            prelude + """

            class Project {
                autoImportProviderHost: AutoImportProviderProject | false | undefined;
                close(): void {
                    this.autoImportProviderHost = false;
                }
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a string-literal write to a union containing it is legal`() {
        diagnose(
            prelude + """

            class Project {
                kind: "configured" | "inferred" | undefined;
                mark(): void {
                    this.kind = "configured";
                }
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a literal absent from the union still fires`() {
        diagnose(
            prelude + """

            class Project {
                kind: "configured" | "inferred" | undefined;
                mark(): void {
                    this.kind = "external";
                }
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
