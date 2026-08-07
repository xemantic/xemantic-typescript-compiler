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
 * Round 470: a `this.prop = <arrow>` assignment resolves the arrow's contextual
 * type from the ENCLOSING CLASS's property annotation, so an un-annotated arrow
 * param is contextually typed and TS7006 is suppressed (tsc services.ts
 * SourceMapSourceObject: `this.skipTrivia = skipTrivia || (pos => pos)` where
 * `skipTrivia?: ((pos: number) => number) | undefined`). Pins the `||`-nested
 * context flow, a direct assignment, and the negative controls.
 */
class ThisPropertyAssignContextTest {

    @Test
    fun `an arrow assigned to an annotated class property via OR gets its context`() {
        diagnose(
            """
            class C {
                skipTrivia?: ((pos: number) => number) | undefined;
                constructor(skipTrivia?: (pos: number) => number) {
                    this.skipTrivia = skipTrivia || (pos => pos);
                }
            }
            """,
            directives = "// @strict: true\n// @noImplicitAny: true",
        ) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `a direct this-property arrow assignment gets its context`() {
        diagnose(
            """
            class C {
                handler: (value: string) => number = () => 0;
                init() {
                    this.handler = value => value.length;
                }
            }
            """,
            directives = "// @strict: true\n// @noImplicitAny: true",
        ) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - an any-typed class property provides no context`() {
        diagnose(
            """
            class C {
                other: any;
                init() {
                    this.other = (x => x);
                }
            }
            """,
            directives = "// @strict: true\n// @noImplicitAny: true",
        ) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - a method body arrow with no target context still fires`() {
        diagnose(
            """
            class C {
                m() {
                    const f = (x => x);
                    return f;
                }
            }
            """,
            directives = "// @strict: true\n// @noImplicitAny: true",
        ) should {
            have(any { it.code == 7006 })
        }
    }
}
