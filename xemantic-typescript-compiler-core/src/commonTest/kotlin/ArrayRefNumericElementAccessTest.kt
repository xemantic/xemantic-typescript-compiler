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
 * Round 472: a numeric ELEMENT access on an Array-typed PROPERTY-ACCESS receiver
 * (`w.items[0]`) reaches checkMemberAccessMissing's "knockout" single-interface
 * branch with propName "0" — under REAL LIBS the Array reference's target passes
 * every gate (single lib InterfaceDeclaration, no heritage, no Class flag) and
 * `getPropertyOfType(arr, "0")` misses (arrays answer numeric keys via the
 * numeric INDEX SIGNATURE, not members) → FP TS2339 "Property '0' does not
 * exist on type '(SourceFile | Bundle)[]'" (tsc emitter.ts:994
 * `transform.transformed[0]`). The branch now bails for Array/ReadonlyArray
 * references. Embedded-lib runs never hit the gate (different Array shape),
 * hence the `@lib` directive.
 */
class ArrayRefNumericElementAccessTest {

    @Test
    fun `a numeric element access on an array-typed member is not a missing property`() {
        diagnose(
            """
            interface Wrapper { items: string[]; }
            declare const w: Wrapper;
            const x = w.items[0];
            """.trimIndent(),
            directives = "// @strict: true\n// @lib: es2020\n// @target: es2020",
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `the generic transform-result shape resolves its numeric element access`() {
        diagnose(
            """
            interface Node2 { kindNum: number; }
            interface SourceFile extends Node2 { fileName: string; }
            interface Bundle extends Node2 { sourceFiles: SourceFile[]; }
            interface TransformationResult<T extends Node2> {
                transformed: T[];
            }
            declare const transform: TransformationResult<SourceFile | Bundle>;
            const sourceFileOrBundle = transform.transformed[0];
            """.trimIndent(),
            directives = "// @strict: true\n// @lib: es2020\n// @target: es2020",
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a genuinely missing member on a single generic interface still fires`() {
        // The knockout branch's own shape (a Type.Reference receiver to a single
        // non-merged heritage-free interface) — a genuinely absent member keeps firing.
        diagnose(
            """
            interface Obs<T> { value: T; }
            interface Wrapper2 { o: Obs<string>; }
            declare const w2: Wrapper2;
            const y = w2.o.missingMember;
            """.trimIndent(),
            directives = "// @strict: true\n// @lib: es2020\n// @target: es2020",
        ) should {
            have(any { it.code == 2339 })
        }
    }
}
