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
 * Round 459: a truthy ASSIGNMENT condition narrows its target — the assignment
 * evaluates to the assigned value, so `while (child = tryParse(() => …))` sees
 * `child` truthy inside the body. tsc's parser JSDoc loops (parseTypedefTag,
 * parser.ts:9638): `let child: JSDocTypeTag | … | false; while (child =
 * tryParse(…)) { if (child.kind === SyntaxKind.JSDocTypeTag) childTypeTag =
 * child; }` — the `false` member otherwise survived every downstream
 * discriminant filter and FP'd TS2322 on the assignment.
 */
class AssignmentConditionTruthyNarrowingTest {

    private val prelude = """
        enum SyntaxKind { JSDocTypeTag = 1, JSDocPropertyTag = 2, JSDocTemplateTag = 3 }
        interface JSDocTypeTag { kind: SyntaxKind.JSDocTypeTag; t: string; }
        interface JSDocPropertyTag { kind: SyntaxKind.JSDocPropertyTag; p: string; }
        interface JSDocTemplateTag { kind: SyntaxKind.JSDocTemplateTag; m: string; }
        declare function parseChildPropertyTag(indent: number): JSDocTypeTag | JSDocPropertyTag | JSDocTemplateTag | false;
        declare function tryParse<T>(cb: () => T): T;

    """.trimIndent()

    @Test
    fun `while-assignment condition narrows the false member away - no TS2322`() {
        diagnose(prelude + """
            function f(indent: number) {
                let child: JSDocTypeTag | JSDocPropertyTag | JSDocTemplateTag | false;
                let childTypeTag: JSDocTypeTag | undefined;
                while (child = tryParse(() => parseChildPropertyTag(indent))) {
                    if (child.kind === SyntaxKind.JSDocTemplateTag) {
                        break;
                    }
                    if (child.kind === SyntaxKind.JSDocTypeTag) {
                        childTypeTag = child;
                    }
                }
                return childTypeTag;
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - without the kind guard the assignment still fires TS2322`() {
        diagnose(prelude + """
            function g(indent: number) {
                let child: JSDocTypeTag | JSDocPropertyTag | false;
                let childTypeTag: JSDocTypeTag | undefined;
                while (child = tryParse(() => parseChildPropertyTag(indent))) {
                    childTypeTag = child;
                }
                return childTypeTag;
            }
        """) should {
            have(any { it.code == 2322 })
        }
    }
}
