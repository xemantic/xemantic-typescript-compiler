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
 * Round 460: getTypeOfFunction zipped the this-DROPPED paramSymbols against the
 * this-KEPT decl.parameters, shifting every param type by one for a `this`-param
 * function — core.ts's `multiMapAdd<K, V>(this: MultiMap<K, V>, key: K, value: V)`
 * built the signature `(key: MultiMap<K, V>, value: K)` → FP TS2322 at
 * `map.add = multiMapAdd`. A this-param function now resolves each symbol's type
 * from its own declaration (and `this` no longer counts toward minArgumentCount);
 * functions WITHOUT a this param keep the legacy positional zip (deliberately —
 * it keeps call-site arg alignment for leading binding-pattern params, which are
 * also dropped from paramSymbols per the round-446 gotcha).
 */
class ThisParamSignatureAlignmentTest {

    @Test
    fun `this-param function assigns to a matching method property - no TS2322`() {
        diagnose("""
            interface MultiMap<K, V> extends Map<K, V[]> {
                add(key: K, value: V): V[];
                remove(key: K, value: V): void;
            }
            export function createMultiMap<K, V>(): MultiMap<K, V> {
                const map = new Map<K, V[]>() as MultiMap<K, V>;
                map.add = multiMapAdd;
                map.remove = multiMapRemove;
                return map;
            }
            function multiMapAdd<K, V>(this: MultiMap<K, V>, key: K, value: V) {
                let values = this.get(key);
                if (values !== undefined) { values.push(value); }
                else { this.set(key, values = [value]); }
                return values;
            }
            function multiMapRemove<K, V>(this: MultiMap<K, V>, key: K, value: V) {
                const values = this.get(key);
                if (values !== undefined) { }
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a this-param function with genuinely wrong params still fires`() {
        diagnose("""
            interface Holder { fn(key: string): void; }
            declare const h: Holder;
            function impl(this: Holder, key: number) {}
            h.fn = impl;
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }
}
