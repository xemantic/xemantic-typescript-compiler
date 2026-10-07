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

package com.xemantic.typescript.tsgo

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoMap
import com.xemantic.typescript.tsgo.runtime.GoMapAbsent
import com.xemantic.typescript.tsgo.runtime.goProbeValue
import kotlin.test.Test

// Pins the single-probe comma-ok rule (docs/goport-lowering.md § 3): `v, ok := m[k]` lowers to
// `val t = m.probe(k)`, `v = goProbeValue(t) { zero }`, `ok = t !== GoMapAbsent`. Go answers
// (nil, true) for a key stored with a nil value and (zero, false) for an absent key or a nil map.
class MapProbeTest {

    @Test
    fun `a stored nil value is present and an absent key answers the zero value`() {
        val m = GoMap.make<String, Any?>(GoElem.ref())
        m["nil"] = null
        m["one"] = 1
        val stored = m.probe("nil")
        assert(stored !== GoMapAbsent && goProbeValue<Any?>(stored) { "zero" } == null)
        val one = m.probe("one")
        assert(one !== GoMapAbsent && goProbeValue<Any?>(one) { "zero" } == 1)
        val absent = m.probe("absent")
        assert(absent === GoMapAbsent && goProbeValue<Any?>(absent) { "zero" } == "zero")
    }

    @Test
    fun `a nil map reads every key as absent`() {
        val m = GoMap.nil<String, String>(GoElem.STRING)
        val r = m.probe("k")
        assert(r === GoMapAbsent && goProbeValue<String>(r) { "" } == "")
    }
}
