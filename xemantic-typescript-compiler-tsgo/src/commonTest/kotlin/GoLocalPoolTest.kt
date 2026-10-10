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
import com.xemantic.typescript.tsgo.go.github_com.zeebo.xxh3.Hasher
import com.xemantic.typescript.tsgo.go.github_com.zeebo.xxh3.hashString128
import com.xemantic.typescript.tsgo.runtime.GoLocalPool
import kotlin.test.Test

/**
 * The per-thread pool behind pooled locals (docs/goport-lowering.md § 3, "Pooled locals"): a nested acquire
 * never hands out a value still held, a re-acquired value is Go's zero again, and a reset Hasher keeps no
 * state from its previous key (only its buffer's capacity).
 */
class GoLocalPoolTest {

    private val pool = GoLocalPool({ Hasher() }, { it.goReset() })

    @Test
    fun `a nested acquire takes the next slot and a re-acquired value is zero again`() {
        val s = pool.stack()
        val outer = s.acquire()
        outer.writeString("outer key, half written")
        val inner = s.acquire()
        assert(inner !== outer)
        inner.writeString("inner")
        assert(inner.sum128() == hashString128("inner"))
        s.release()
        // The outer key is untouched by the nested one.
        outer.writeString("!")
        assert(outer.sum128() == hashString128("outer key, half written!"))
        s.release()
        assert(s.held == 0)
        val again = s.acquire()
        assert(again === outer)
        assert(again.sum128() == hashString128(""))
        s.release()
    }

    @Test
    fun `a hasher reset after a long input hashes a short one exactly`() {
        val s = pool.stack()
        val h = s.acquire()
        h.writeString("x".repeat(5000))
        s.release()
        val h2 = s.acquire()
        assert(h2 === h)
        h2.writeU8('y'.code)
        assert(h2.sum128() == hashString128("y"))
        h2.writeString("z".repeat(3000))
        assert(h2.sum128() == hashString128("y" + "z".repeat(3000)))
        s.release()
    }
}
