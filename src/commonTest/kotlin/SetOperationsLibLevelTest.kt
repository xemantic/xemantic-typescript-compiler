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
 * Self-compile burn-down: the ES2024/ES2025 Set set-operations (`union`/`intersection`/
 * `difference`/`symmetricDifference`/`isSubsetOf`/…) live in lib.es2025.collection.d.ts in the
 * pinned tsc, so at a sub-ES2025 lib the `Set` shape lacks them — tsc's own `core.ts` writes
 * `const set: Set<T> = { has, add, delete, clear, size, forEach, keys, values, entries }` and
 * relies on that. The embedded lib carried the methods unconditionally, so the shim FP'd TS2740
 * "missing union, intersection, …". They are now LIB_MIN_TARGET-gated at ESNext (our top target;
 * ScriptTarget has no ES2025): absent at es2020, present at esnext.
 */
class SetOperationsLibLevelTest {

    @Test
    fun `a partial Set literal at a sub-esnext lib is not missing the set-ops - no TS2740`() {
        diagnose(
            """
            export function f<T>(): Set<T> {
                const set: Set<T> = {
                    add(v: T): Set<T> { return set; },
                    clear(): void {},
                    delete(v: T): boolean { return true; },
                    forEach(cb: (value: T, value2: T, s: Set<T>) => void): void {},
                    has(v: T): boolean { return true; },
                    size: 0,
                    entries() { return undefined as any; },
                    keys() { return undefined as any; },
                    values() { return undefined as any; },
                };
                return set;
            }
            """,
            directives = "// @strict: false\n// @lib: es2020",
        ) should {
            have(none { it.code == 2740 })
            have(none { it.code == 2739 })
        }
    }

    @Test
    fun `set-ops are available at esnext - regression control`() {
        diagnose(
            """
            export function f(a: Set<number>, b: Set<number>): Set<number> {
                return a.union(b).intersection(b);
            }
            """,
            directives = "// @strict: false\n// @target: esnext",
        ) should {
            have(none { it.code == 2550 })
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `set-ops are unavailable at es2020 - TS2550 fires on access`() {
        // FP-safety: the filter is real — accessing a filtered method errors, mirroring tsc's
        // "change your target library" diagnostic.
        diagnose(
            """
            export function f(a: Set<number>, b: Set<number>): boolean {
                return a.isSubsetOf(b);
            }
            """,
            directives = "// @strict: false\n// @lib: es2020",
        ) should {
            have(any { it.code == 2550 || it.code == 2339 })
        }
    }
}
