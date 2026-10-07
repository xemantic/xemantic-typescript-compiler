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

package com.xemantic.typescript.tsgo.go.maps

import com.xemantic.typescript.tsgo.go.iter.Seq
import com.xemantic.typescript.tsgo.go.iter.Seq2
import com.xemantic.typescript.tsgo.runtime.GoMap
import com.xemantic.typescript.tsgo.runtime.goEq

/** `maps.Keys(m)` (unspecified order, as in Go). */
fun <K, V> keys(m: GoMap<K, V>): Seq<K> = { yield ->
    m.range { k, _ -> yield(k) }
}

/** `maps.Values(m)`. */
fun <K, V> values(m: GoMap<K, V>): Seq<V> = { yield ->
    m.range { _, v -> yield(v) }
}

/** `maps.All(m)`. */
fun <K, V> all(m: GoMap<K, V>): Seq2<K, V> = { yield ->
    m.range { k, v -> yield(k, v) }
}

/** `maps.Clone(m)`: nil stays nil. */
fun <K, V> clone(m: GoMap<K, V>): GoMap<K, V> = m.goClone()

/** `maps.Copy(dst, src)`. */
fun <K, V> copy(dst: GoMap<K, V>, src: GoMap<K, V>) {
    src.range { k, v ->
        dst[k] = dst.elem.copyValue(v)
        true
    }
}

/** `maps.Equal(m1, m2)`. */
fun <K, V> equal(m1: GoMap<K, V>, m2: GoMap<K, V>): Boolean {
    if (m1.len != m2.len) return false
    var eq = true
    m1.range { k, v1 ->
        val (v2, ok) = m2.lookup(k)
        if (!ok || !goEq(v1, v2)) {
            eq = false
            false
        } else {
            true
        }
    }
    return eq
}

/** `maps.DeleteFunc(m, del)`. */
fun <K, V> deleteFunc(m: GoMap<K, V>, del: (K, V) -> Boolean) {
    m.range { k, v ->
        if (del(k, v)) m.delete(k)
        true
    }
}

/** `maps.Insert(m, seq)`. */
fun <K, V> insert(m: GoMap<K, V>, seq: Seq2<K, V>) {
    seq { k, v ->
        m[k] = v
        true
    }
}
