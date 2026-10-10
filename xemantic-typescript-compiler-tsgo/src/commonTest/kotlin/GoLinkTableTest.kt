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
import com.xemantic.typescript.tsgo.runtime.GoLinkKey
import com.xemantic.typescript.tsgo.runtime.GoLinkTable
import kotlin.test.Test

private class Key : GoLinkKey()

/**
 * The dense link index and table behind the `core.LinkStore` override (docs/goport-perf.md § 14): every key gets
 * its own index, keys made one after another on a thread get consecutive ones (what keeps a file's links together),
 * and the paged table answers exactly what was stored, across leaf, directory and growth boundaries.
 */
class GoLinkTableTest {

    @Test
    fun `keys get distinct indices - consecutive on one thread`() {
        val keys = List(3000) { Key() }
        val idx = keys.map { it.goLinkIndex }
        assert(idx.all { it >= 0 })
        assert(idx.toSet().size == idx.size)
        // Within one 1024-index block the numbering is consecutive; across blocks it only increases.
        assert(idx.zipWithNext().all { (a, b) -> b > a })
        assert(idx.zipWithNext().count { (a, b) -> b == a + 1 } >= idx.size - 4)
    }

    @Test
    fun `the table answers what was stored and null elsewhere - across page and directory boundaries`() {
        val t = GoLinkTable()
        val at = listOf(0, 1, 63, 64, 65, 1023 * 64 + 63, 65_535, 65_536, 70_000, 1_048_576 * 3 + 5, 2_000_000_000)
        for (i in at) t[i] = "v$i"
        for (i in at) assert(t[i] == "v$i")
        for (i in listOf(2, 62, 66, 65_534, 65_537, 1_048_576 * 3 + 4, 1_999_999_999)) assert(t[i] == null)
        t[64] = "again"
        assert(t[64] == "again" && t[63] == "v63")
    }
}
