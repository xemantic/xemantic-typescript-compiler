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
 * Round 438 (M3.4, self-compile burn-down): an object-literal property VALUE that is a
 * flow-narrowed bare Identifier reads its narrowed type — tsc types property values as
 * expressions in flow context, but getTypeOfIdentifier does not consult narrowing, so
 * `specs = append(specs, x); return { moduleSpecifiers: specs }` (tsc's moduleSpecifiers.ts)
 * kept `specs`'s wider `string[] | undefined` and FP'd against the interface's non-undefined
 * property.
 *
 * NULLISH-STRIP-gated (objLitValueNullishStrip): only accept the narrowing when it strips
 * nullish from a union (`X | undefined` → `X`). A narrow-DOWN to an unrelated subtype, or the
 * name-based-flow SHADOWING hazard (tsc's builder.ts inner `const affected = state.program`
 * under an outer `if (!affected)` narrowing to `undefined`), is rejected — those keep the raw
 * type, so this can only strip a proven-absent nullish member (suppression-only).
 */
class FreshObjectPropertyNarrowingTest {

    @Test
    fun `property value narrowed by assignment to non-null is accepted`() {
        diagnose(
            """
            interface R { kind: string; moduleSpecifiers: readonly string[]; ok: boolean; }
            declare function append<T>(to: T[] | undefined, value: T): T[];
            export function f(spec: string): R {
                let specs: string[] | undefined;
                specs = append(specs, spec); // narrows specs to string[]
                return { kind: "n", moduleSpecifiers: specs, ok: true };
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `shorthand property value narrowed by a guard is accepted`() {
        diagnose(
            """
            interface R { specs: readonly string[]; }
            export function f(specs: string[] | undefined): R {
                if (specs === undefined) throw new Error();
                return { specs }; // shorthand: specs narrowed to string[]
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `genuinely nullable property value still fires - negative control`() {
        // No guard/assignment narrows `specs` — it stays `string[] | undefined`, which is
        // NOT assignable to the interface's non-undefined property.
        diagnose(
            """
            interface R { specs: readonly string[]; }
            export function f(specs: string[] | undefined): R {
                return { specs };
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
