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

package com.xemantic.typescript.tsgo.runtime

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * NOT Go API — a dense, port-internal index for the objects tsgo's checker keys its link stores by (`*ast.Node`,
 * `*ast.Symbol`; the porter makes their classes extend this one, docs/goport-lowering.md § 3, "Link keys").
 *
 * Go's `map[*Node]*V` hashes a pointer; on the JVM the same probe reads the key's header for its identity hash and
 * lands at a random slot, so the checker's walk over a file's nodes in order turns into a cache miss per link read.
 * [goLinkIndex] numbers objects in creation order (in per-thread blocks), so the nodes of one file — and the links
 * a checker keeps for them in a [GoLinkTable] — sit next to each other. It is NOT tsgo's `ast.GetNodeId` (assigned
 * lazily, in an order the node builder and emit resolver depend on); nothing observable reads it. `-1` once the
 * index space is spent: such a key falls back to the store's map.
 */
abstract class GoLinkKey {
    @kotlin.jvm.JvmField
    val goLinkIndex: Int = goNextLinkIndex()
}

@OptIn(ExperimentalAtomicApi::class)
private val linkIndexCounter = AtomicInt(0)

/** Indices handed to one thread at a time: objects of one file, built by one goroutine, stay contiguous. */
internal const val LINK_INDEX_BLOCK = 1024

/** Past this, [goNextLinkIndex] answers -1 (a long-lived host can create more objects than an Int numbers). */
private const val LINK_INDEX_LIMIT = Int.MAX_VALUE - LINK_INDEX_BLOCK * 4096

/** A new block's first index, or -1 when the index space is spent. */
@OptIn(ExperimentalAtomicApi::class)
internal fun goLinkIndexBlock(): Int {
    if (linkIndexCounter.load() > LINK_INDEX_LIMIT) return -1
    val start = linkIndexCounter.fetchAndAdd(LINK_INDEX_BLOCK)
    return if (start in 0..LINK_INDEX_LIMIT) start else -1
}

/** This thread's next index ([goLinkIndexBlock] when its block is used up). */
internal expect fun goNextLinkIndex(): Int

/**
 * A sparse array from [GoLinkKey.goLinkIndex] to a value: three levels (64 K indices per directory entry, 64 per
 * leaf), each allocated on first write, so memory follows the index ranges actually touched. A read is three
 * dependent loads, the upper two nearly always cached.
 */
class GoLinkTable {
    private var top: Array<Array<Array<Any?>?>?> = arrayOfNulls(16)

    operator fun get(i: Int): Any? {
        val t = top
        val hi = i ushr 16
        if (hi >= t.size) return null
        val mid = t[hi] ?: return null
        val leaf = mid[(i ushr 6) and 1023] ?: return null
        return leaf[i and 63]
    }

    operator fun set(i: Int, v: Any?) {
        val hi = i ushr 16
        if (hi >= top.size) top = top.copyOf(maxOf(hi + 1, top.size * 2))
        val mid = top[hi] ?: arrayOfNulls<Array<Any?>?>(1024).also { top[hi] = it }
        val m = (i ushr 6) and 1023
        val leaf = mid[m] ?: arrayOfNulls<Any?>(64).also { mid[m] = it }
        leaf[i and 63] = v
    }
}
