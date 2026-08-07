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
 * Round 463: `nearestPrecedingObjectLiteralDecl` (the B290 element-access
 * receiver-shape recovery for B83.5-unbound block locals) matched ANNOTATED
 * declarations too, so an annotated `const result: ExtendsResult = { options: {} }`
 * keyed the noImplicitAny index checks off the bare INITIALIZER literal — tsc's
 * commandLineParser.ts `result[propertyName]` (propertyName: "include" | "exclude"
 * | "files", all optional members of the annotation) FP'd TS7053, even though the
 * access-site `result` was a DIFFERENT binding (a nested function's annotated
 * param). An annotated declaration's type is its annotation; the helper now skips
 * annotated decls and lets the normal typed path own them.
 */
class AnnotatedObjLitReceiverIndexTest {

    @Test
    fun `an annotated objlit decl does not key the index check off the initializer literal`() {
        diagnose("""
            interface ExtendsResult {
                options: { [k: string]: unknown };
                include?: string[];
                exclude?: string[];
                files?: string[];
            }
            function outer(flag: boolean, values: string[]) {
                if (flag) {
                    const result: ExtendsResult = { options: {} };
                    inner(result);
                }
                function inner(result: ExtendsResult) {
                    const set = (propertyName: "include" | "exclude" | "files") => {
                        result[propertyName] = values;
                    };
                    set("include");
                }
            }
        """) should {
            have(none { it.code == 7053 })
        }
    }

    @Test
    fun `negative control - an UN-annotated objlit local still fires TS7053 on a missing union key`() {
        diagnose("""
            function f(values: string[]) {
                const result = { options: {} };
                const set = (propertyName: "include" | "exclude" | "files") => {
                    result[propertyName] = values;
                };
                set("include");
            }
        """) should {
            have(any { it.code == 7053 })
        }
    }
}
