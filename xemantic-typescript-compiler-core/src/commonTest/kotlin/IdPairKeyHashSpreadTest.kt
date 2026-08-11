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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (HASH.1)(b) round 890 — [packIdPair] must SPREAD, not merely be injective.
 *
 * The sibling of `NodeKeyHashSpreadTest`, and the same silent defect one key
 * family over: `java.lang.Long.hashCode()` folds a packed Long's two halves
 * together with XOR, so `(a shl 32) or b` hashes to exactly `a xor b`. For the
 * checker's id-pair keys the collapse is worse than it looks in the abstract,
 * because Type and Symbol ids are minted SEQUENTIALLY and the pairs a relation
 * asks about are overwhelmingly NEIGHBOURS — an instantiation against its
 * target, a union against a member it was built from. Every such pair hashes
 * into the handful of buckets `1, 2, 3, …`.
 *
 * That is not a hypothesis: round 890 captured `Relation.cache`'s real key
 * population on the compiler profile and **43,080 keys collapse onto 18,201
 * distinct hashes, the commonest being `XOR == 1` with 1,140 keys in ONE
 * bucket**, leaving 27.3% of all keys past the treeify threshold. The pins
 * below reproduce that shape rather than a synthetic one.
 *
 * **Every pin compares against the un-mixed packing written out LONGHAND inside
 * the pin**, which is the round-889 lesson: an earlier `nodeKey` pin compared
 * bucket occupancy against a property of its fixture and stayed GREEN under the
 * ablation, i.e. it was measuring the fixture. Written this way, stripping the
 * finalizer from [packIdPair] makes the two sides the same expression and every
 * inequality fails by construction.
 *
 * The bucket arithmetic mirrors `java.util.HashMap` exactly: the table index is
 * `(hash xor (hash ushr 16)) and (capacity - 1)` over `Long.hashCode()`, and a
 * bucket TREEIFIES at 8 entries. Kotlin/Native has no `java.util.HashMap`, but
 * these are claims about the KEY's distribution, which is a property of the
 * packing alone — so they are meaningful on every target.
 */
class IdPairKeyHashSpreadTest {

    /** `java.lang.Long.hashCode()` — the fold every `Map<Long, ·>` starts from. */
    private fun longHash(v: Long): Int = (v xor (v ushr 32)).toInt()

    /** `java.util.HashMap.hash()` — the spread applied on top of it. */
    private fun bucketOf(key: Long, capacity: Int): Int {
        val h = longHash(key)
        return (h xor (h ushr 16)) and (capacity - 1)
    }

    /** The packing [packIdPair] would be without its finalizer. */
    private fun rawPack(a: Int, b: Int): Long =
        (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)

    private fun histogram(keys: List<Long>, capacity: Int): IntArray {
        val counts = IntArray(capacity)
        for (k in keys) counts[bucketOf(k, capacity)]++
        return counts
    }

    @Test
    fun `neighbouring ids are the worst case and they must not share one bucket`() {
        // The measured pathology in its pure form: 2,000 relation queries between
        // a type and the type minted immediately after it. Un-mixed, `a xor b` is
        // 1 for every one of them, so all 2,000 land in bucket 1 — a single
        // red-black tree standing in for the whole map.
        val pairs = (0 until 2_000).map { (2 + 2 * it) to (3 + 2 * it) }
        val capacity = 1 shl 12
        val mixedWorst = histogram(pairs.map { packIdPair(it.first, it.second) }, capacity).max()
        val rawWorst = histogram(pairs.map { rawPack(it.first, it.second) }, capacity).max()
        assert(rawWorst == pairs.size)
        assert(mixedWorst < 8)
    }

    @Test
    fun `the compiler profile's relation key shape spreads instead of treeifying`() {
        // 43,080 distinct pairs over ids up to ~84,000 — the measured population
        // size and the measured mix, where a fifth of the queries are between ids
        // within 64 of each other and the rest are spread. This synthetic is
        // MILDER than the real thing (its worst bucket is 76 against the profile's
        // 1,140, because tsc's own relation traffic concentrates on neighbours
        // harder than a stride can), and it still treeifies 19.2% of its keys.
        val capacity = 1 shl 16
        val keysMixed = ArrayList<Long>(43_080)
        val keysRaw = ArrayList<Long>(43_080)
        for (i in 0 until 43_080) {
            val src = (i * 40_503) % 84_259 + 1
            val tgt = if (i % 5 == 0) src + 1 + i % 64 else (src * 7 + 13) % 84_337 + 1
            keysMixed.add(packIdPair(src, tgt))
            keysRaw.add(rawPack(src, tgt))
        }
        val mixed = histogram(keysMixed.distinct(), capacity)
        val raw = histogram(keysRaw.distinct(), capacity)
        assert(mixed.count { it >= 8 } == 0)
        assert(raw.count { it >= 8 } > 0)
        assert(mixed.count { it > 0 } > raw.count { it > 0 })
    }

    @Test
    fun `the diagonal does not collapse onto one bucket`() {
        // `a xor a == 0` for every self-pair, so an un-mixed identity relation
        // puts EVERY (T, T) query in bucket 0 however many types the program has.
        val capacity = 1 shl 10
        val mixed = histogram((1..500).map { packIdPair(it, it) }, capacity)
        val raw = histogram((1..500).map { rawPack(it, it) }, capacity)
        assert(raw[0] == 500)
        assert(mixed.max() < 8)
    }

    @Test
    fun `the packing stays injective so two id pairs never share a key`() {
        // NOT a discriminating pin — multiplication by an ODD constant modulo
        // 2^64 is a bijection, so the raw and mixed packings are equally
        // injective and no fixture can tell them apart (round 807: say so rather
        // than claim coverage). What it defends is a FUTURE change that reaches
        // for a cheaper mix and loses injectivity, which would silently confuse
        // two unrelated type pairs in the relation cache.
        val pairs = ArrayList<Pair<Int, Int>>()
        for (a in 1..90) for (b in 1..90) pairs.add(a to b)
        val keys = pairs.map { packIdPair(it.first, it.second) }.toSet()
        assert(keys.size == pairs.size)
        assert(packIdPair(3, 5) != packIdPair(5, 3))
    }
}
