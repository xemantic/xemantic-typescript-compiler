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
 * Round 466: array-element contextual typing in the TS7006 walker, with a
 * NESTED-INTERFACE annotation retry — `const priorities: Priority[] = [{ high:
 * t => …, low: t => … }]` where `interface Priority` is declared INSIDE a
 * function (B83.5-unbound, so the annotation resolved `Array<any>` and the
 * object-literal elements lost their member fn context — tsc inferFromUsage.ts,
 * TS7006 ×6). Pins the element-ctx propagation, the nested-interface resolution
 * gates (unique + non-generic + unbound elsewhere), and the still-firing
 * negative controls.
 */
class NestedInterfaceArrayCtxTest {

    @Test
    fun `object-literal elements of a nested-interface array annotation contextually type member arrows`() {
        diagnose(
            """
            interface Type { flags: number; }
            export function outer(checker: { getStringType(): Type }) {
                interface Priority {
                    high: (t: Type) => boolean;
                    low: (t: Type) => boolean;
                }
                const priorities: Priority[] = [
                    { high: t => t === checker.getStringType(), low: t => !!(t.flags & 3) },
                ];
                return priorities;
            }
            """,
            directives = "// @strict: true\n// @noImplicitAny: true",
        ) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `a top-level interface array annotation contextually types member arrows too`() {
        diagnose(
            """
            interface Type { flags: number; }
            interface Priority {
                high: (t: Type) => boolean;
            }
            export function outer() {
                const priorities: Priority[] = [
                    { high: t => !!(t.flags & 3) },
                ];
                return priorities;
            }
            """,
            directives = "// @strict: true\n// @noImplicitAny: true",
        ) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - an un-annotated array keeps TS7006 on member arrows`() {
        diagnose(
            """
            interface Type { flags: number; }
            export function outer() {
                const priorities = [
                    { high: (t) => !!(t.flags & 3) },
                ];
                return priorities;
            }
            """,
            directives = "// @strict: true\n// @noImplicitAny: true",
        ) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `an arrow element of a directly-annotated fn-type array is contextually typed`() {
        diagnose(
            """
            type Cb = (t: number) => boolean;
            const cbs: Cb[] = [t => t > 0];
            """,
            directives = "// @strict: true\n// @noImplicitAny: true",
        ) should {
            have(none { it.code == 7006 })
        }
    }
}
