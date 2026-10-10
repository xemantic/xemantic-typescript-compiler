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

package com.xemantic.typescript.tsgo.go.github_com.zeebo.xxh3

import com.xemantic.typescript.tsgo.runtime.GoArray
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.goBytesToString

// `github.com/zeebo/xxh3` v1.1.0 — the UNSEEDED one-shot hashes (`Hash`, `HashString`, `Hash128`,
// `HashString128`), ported from the library's pure-Go generic path (`hash64.go`, `hash128.go`,
// `accumScalar` in `accum_generic.go`). The SIMD paths the Go library picks on amd64/arm64 compute
// the same function by design; the oracle vectors in `Xxh3OracleTest` are real Go output on amd64
// (so they exercise the AVX2 path against this scalar port), and so are the streaming [Hasher]'s
// (`New`, `Write`, `WriteString`, `Sum64`, `Sum128` — the checker's cache keys). NOT ported: the
// seeded variants.
//
// Inputs are Go byte strings (docs/goport-runtime.md § 5): every char is one byte. All arithmetic is
// on `Long` (two's complement wraps exactly like Go's `uint64`); unsigned shifts are `ushr`.

/**
 * `xxh3.Uint128`: the value is `hi << 64 | lo`.
 */
class Uint128(val hi: ULong = 0uL, val lo: ULong = 0uL) {
    // IMMUTABLE, so no `goCopy()`: tsgo never writes a field of a Uint128 (the `val`s make the compiler
    // check that), sharing one is unobservable, and without the member the porter emits no copy at
    // all — it was ~4% of a check's allocation (docs/goport-perf.md § 7).

    /** Go struct `==`. */
    fun goEquals(other: Uint128): Boolean = hi == other.hi && lo == other.lo

    fun goHash(): Int = hi.hashCode() * 31 + lo.hashCode()

    // A map KEY by value (`map[CacheHashKey]*Type`, the checker's instantiation caches): Go compares the
    // struct, so Kotlin's equals/hashCode must too (added by the (TSGO.2) lowering round).
    override fun equals(other: Any?): Boolean = other is Uint128 && goEquals(other)

    override fun hashCode(): Int = goHash()

    /** `u.Bytes()`: the canonical big-endian 16 bytes. */
    fun bytes(): GoArray<Int> {
        val a = GoArray(16, GoElem.INT)
        for (i in 0 until 8) {
            a[i] = ((hi shr (56 - 8 * i)) and 0xFFuL).toInt()
            a[8 + i] = ((lo shr (56 - 8 * i)) and 0xFFuL).toInt()
        }
        return a
    }

    override fun toString(): String = "{$hi $lo}"
}

/** `xxh3.Hash(b)`. */
fun hash(b: GoSlice<Int>): ULong = hashString(goBytesToString(b))

/** `xxh3.HashString(s)`. */
fun hashString(s: String): ULong = hashAny(s).toULong()

/** `xxh3.Hash128(b)`. */
fun hash128(b: GoSlice<Int>): Uint128 = hashString128(goBytesToString(b))

/** `xxh3.HashString128(s)`. */
fun hashString128(s: String): Uint128 {
    val r = LongArray(2)
    hashAny128(s, r)
    return Uint128(r[0].toULong(), r[1].toULong())
}

/**
 * `xxh3.Hasher` (unseeded), Go's streaming state machine verbatim (`hasher.go`): input is buffered
 * up to one block plus one stripe; a full buffer is folded into the accumulators only when MORE
 * input arrives, and a first write longer than the buffer is consumed block by block without
 * copying. `Sum64`/`Sum128` do not change the state and equal the one-shot hash of everything
 * written. Go's zero value is usable (it resets itself on first use); so is `Hasher()`.
 */
class Hasher {
    // NOT Go's layout, same states (docs/goport-perf.md § 6): the checker's key builder makes a Hasher
    // per cache key and writes a few dozen bytes, so the buffer is a ByteArray grown on demand, `acc` is
    // allocated only once a block is folded, and a short input is hashed straight from the buffer
    // ([hashBytes128]) with no intermediate String.
    private var acc: LongArray? = null
    private var blk = 0L
    private var len = 0
    private var keyed = false
    private var buf: ByteArray = EMPTY_BUF

    /** `h.Reset()`. */
    fun reset() {
        acc = null
        blk = 0
        len = 0
    }

    /**
     * NOT Go API — back to Go's ZERO value in place, keeping the buffer's capacity (its bytes past
     * `len` are never read): what a pooled `keyBuilder` local (`GoLocalPool`) is reset to on acquire.
     */
    fun goReset() {
        acc = null
        blk = 0
        len = 0
        keyed = false
    }

    /** `h.BlockSize()`, `h.Size()`. */
    fun blockSize(): Int = STRIPE
    fun size(): Int = 8

    /** `h.Write(b)` → (len(b), nil). */
    fun write(b: GoSlice<Int>): com.xemantic.typescript.tsgo.runtime.Tuple2<Int, com.xemantic.typescript.tsgo.runtime.GoError?> {
        val n = b.len
        if (n <= STRIPE) {
            ensureKey()
            for (i in 0 until n) put(b[i])
        } else {
            update(goBytesToString(b))
        }
        return com.xemantic.typescript.tsgo.runtime.Tuple2(n, null)
    }

    /** `h.WriteString(s)` → (len(s), nil). */
    fun writeString(s: String): com.xemantic.typescript.tsgo.runtime.Tuple2<Int, com.xemantic.typescript.tsgo.runtime.GoError?> {
        update(s)
        return com.xemantic.typescript.tsgo.runtime.Tuple2(s.length, null)
    }

    /** NOT Go API — `h.Write([]byte{b})` without the slice. */
    fun writeU8(b: Int) {
        ensureKey()
        put(b)
    }

    /** NOT Go API — the 4 little-endian bytes of [v] (what `hashWrite32` writes). */
    fun writeU32le(v: Int) {
        ensureKey()
        put(v)
        put(v ushr 8)
        put(v ushr 16)
        put(v ushr 24)
    }

    /** NOT Go API — the 8 little-endian bytes of [v]. */
    fun writeU64le(v: Long) {
        writeU32le(v.toInt())
        writeU32le((v ushr 32).toInt())
    }

    private fun ensureKey() {
        if (!keyed) {
            keyed = true
            reset()
        }
    }

    private fun accs(): LongArray = acc ?: initialAccs().also { acc = it }

    private fun ensureBuf(need: Int) {
        if (buf.size >= need) return
        var n = maxOf(buf.size * 2, 64)
        while (n < need) n *= 2
        buf = buf.copyOf(minOf(n, BUF_SIZE))
    }

    /** One byte of input (the low 8 bits of [b]): the per-byte step of [update]. */
    private fun put(b: Int) {
        if (len >= BUF_SIZE) foldFullBuffer()
        if (len >= buf.size) ensureBuf(len + 1)
        buf[len++] = b.toByte()
    }

    /** A full buffer folds its first block when more input arrives; the last stripe stays. */
    private fun foldFullBuffer() {
        accumBlock(accs(), latin1(buf, BLOCK), 0)
        blk++
        len = STRIPE
        buf.copyInto(buf, 0, BLOCK, BLOCK + STRIPE)
    }

    private fun update(s: String) {
        ensureKey()
        var p = 0
        val n = s.length
        // first write of more than a buffer: whole blocks straight from the input
        while (len == 0 && n - p > BUF_SIZE) {
            accumBlock(accs(), s, p)
            p += BLOCK
            blk++
        }
        while (p < n) {
            if (len < BUF_SIZE) {
                val k = minOf(BUF_SIZE - len, n - p)
                ensureBuf(len + k)
                for (i in 0 until k) buf[len + i] = s[p + i].code.toByte()
                len += k
                p += k
                continue
            }
            foldFullBuffer()
        }
    }

    /** `h.Sum64()`. */
    fun sum64(): ULong {
        ensureKey()
        val data = latin1(buf, len)
        if (blk == 0L) return hashAny(data).toULong()
        val l = blk * BLOCK + len
        var a = l * PRIME64_1
        val accs = accs().copyOf()
        if (len > 0) accumScalar(accs, data, len)
        a += mulFold64(accs[0] xor K[11], accs[1] xor K[19])
        a += mulFold64(accs[2] xor K[27], accs[3] xor K[35])
        a += mulFold64(accs[4] xor K[43], accs[5] xor K[51])
        a += mulFold64(accs[6] xor K[59], accs[7] xor K[67])
        return xxh3Avalanche(a).toULong()
    }

    /** `h.Sum128()`. */
    fun sum128(): Uint128 {
        ensureKey()
        if (blk == 0L) return if (len <= 240) hashBytes128(buf, len) else hashString128(latin1(buf, len))
        val data = latin1(buf, len)
        val l = blk * BLOCK + len
        var lo = l * PRIME64_1
        var hi = (l * PRIME64_2).inv()
        val accs = accs().copyOf()
        if (len > 0) accumScalar(accs, data, len)
        lo += mulFold64(accs[0] xor K[11], accs[1] xor K[19])
        hi += mulFold64(accs[0] xor K[117], accs[1] xor K[125])
        lo += mulFold64(accs[2] xor K[27], accs[3] xor K[35])
        hi += mulFold64(accs[2] xor K[133], accs[3] xor K[141])
        lo += mulFold64(accs[4] xor K[43], accs[5] xor K[51])
        hi += mulFold64(accs[4] xor K[149], accs[5] xor K[157])
        lo += mulFold64(accs[6] xor K[59], accs[7] xor K[67])
        hi += mulFold64(accs[6] xor K[165], accs[7] xor K[173])
        return Uint128(xxh3Avalanche(hi).toULong(), xxh3Avalanche(lo).toULong())
    }

    /** `h.Sum(b)`: [b] with the big-endian `Sum64` appended. */
    fun sum(b: GoSlice<Int>): GoSlice<Int> {
        val v = sum64()
        var out = b
        for (i in 0 until 8) out = out.append1(((v shr (56 - 8 * i)) and 0xFFuL).toInt())
        return out
    }

    /** A Go value copy (the checker embeds a `Hasher` by value in its key builder). */
    fun goCopy(): Hasher {
        val c = Hasher()
        c.acc = acc?.copyOf()
        c.blk = blk
        c.len = len
        c.keyed = keyed
        c.buf = if (buf.isEmpty()) EMPTY_BUF else buf.copyOf()
        return c
    }
}

private val EMPTY_BUF = ByteArray(0)

/** The first [n] bytes of [b] as a Go byte string (the long-input paths, which take a String). */
private fun latin1(b: ByteArray, n: Int): String {
    val cs = CharArray(n)
    for (i in 0 until n) cs[i] = (b[i].toInt() and 0xFF).toChar()
    return cs.concatToString()
}

// ---- hash128.go over a byte buffer, inputs of at most 240 bytes (the Hasher's short keys) ----
// The same function as [hashAny128]'s three short paths, reading a ByteArray instead of a String.

private fun readU8(b: ByteArray, o: Int): Long = (b[o].toInt() and 0xFF).toLong()

private fun readU16(b: ByteArray, o: Int): Long = readU8(b, o) or (readU8(b, o + 1) shl 8)

private fun readU32(b: ByteArray, o: Int): Long =
    readU8(b, o) or (readU8(b, o + 1) shl 8) or (readU8(b, o + 2) shl 16) or (readU8(b, o + 3) shl 24)

private fun readU64(b: ByteArray, o: Int): Long = readU32(b, o) or (readU32(b, o + 4) shl 32)

private fun hashBytes128(s: ByteArray, l: Int): Uint128 {
    val ll = l.toLong()
    if (l <= 16) {
        var lo: Long
        when {
            l > 8 -> {
                val bitflipl = K[32] xor K[40]
                val bitfliph = K[48] xor K[56]
                val inputLo = readU64(s, 0)
                var inputHi = readU64(s, l - 8)
                val x = inputLo xor inputHi xor bitflipl
                var mh = mulHi(x, PRIME64_1)
                var ml = x * PRIME64_1
                ml += (ll - 1) shl 54
                inputHi = inputHi xor bitfliph
                mh += inputHi + (inputHi and 0xFFFFFFFFL) * (PRIME32_2 - 1)
                ml = ml xor reverseBytes64(mh)
                var hi = mulHi(ml, PRIME64_2)
                lo = ml * PRIME64_2
                hi += mh * PRIME64_2
                return Uint128(xxh3Avalanche(hi).toULong(), xxh3Avalanche(lo).toULong())
            }
            l > 3 -> {
                val bitflip = K[16] xor K[24]
                val inputLo = readU32(s, 0)
                val inputHi = readU32(s, l - 4)
                val input64 = inputLo + (inputHi shl 32)
                val keyed = input64 xor bitflip
                val m = PRIME64_1 + (ll shl 2)
                var hi = mulHi(keyed, m)
                lo = keyed * m
                hi += lo shl 1
                lo = lo xor (hi ushr 3)
                lo = lo xor (lo ushr 35)
                lo *= RRMXMX_MUL
                lo = lo xor (lo ushr 28)
                return Uint128(xxh3Avalanche(hi).toULong(), lo.toULong())
            }
            l == 3 -> lo = (readU16(s, 0) shl 16) + readU8(s, 2) + (3L shl 8)
            l == 2 -> lo = ((readU16(s, 0) * ((1L shl 24) + 1)) ushr 8) + (2L shl 8)
            l == 1 -> lo = readU8(s, 0) * ((1L shl 24) + (1L shl 16) + 1) + (1L shl 8)
            else -> return Uint128(0x99aa06d3014798d8uL, 0x6001c324468d497fuL)
        }
        var hi = (reverseBytes32(lo.toInt()).rotateLeft(13).toLong()) and 0xFFFFFFFFL
        lo = lo xor (k32(0) xor k32(4))
        hi = hi xor (k32(8) xor k32(12))
        return Uint128(xxh64AvalancheSmall(hi).toULong(), xxh64AvalancheSmall(lo).toULong())
    }
    val r = LongArray(2)
    r[0] = 0L
    r[1] = ll * PRIME64_1
    if (l <= 128) {
        if (l > 32) {
            if (l > 64) {
                if (l > 96) {
                    round128(r, readU64(s, 6 * 8), readU64(s, 7 * 8), readU64(s, l - 8 * 8), readU64(s, l - 7 * 8), K[112], K[120], K[96], K[104])
                }
                round128(r, readU64(s, 4 * 8), readU64(s, 5 * 8), readU64(s, l - 6 * 8), readU64(s, l - 5 * 8), K[80], K[88], K[64], K[72])
            }
            round128(r, readU64(s, 2 * 8), readU64(s, 3 * 8), readU64(s, l - 4 * 8), readU64(s, l - 3 * 8), K[48], K[56], K[32], K[40])
        }
        round128(r, readU64(s, 0), readU64(s, 8), readU64(s, l - 2 * 8), readU64(s, l - 8), K[16], K[24], K[0], K[8])
    } else {
        for (g in 0 until 4) {
            val o = 32 * g
            round128(r, readU64(s, o), readU64(s, o + 8), readU64(s, o + 16), readU64(s, o + 24), K[o + 16], K[o + 24], K[o], K[o + 8])
        }
        r[0] = xxh3Avalanche(r[0])
        r[1] = xxh3Avalanche(r[1])
        val top = l and 31.inv()
        var i = 4 * 32
        while (i < top) {
            round128(r, readU64(s, i), readU64(s, i + 8), readU64(s, i + 16), readU64(s, i + 24), K[i - 109], K[i - 101], K[i - 125], K[i - 117])
            i += 32
        }
        round128(r, readU64(s, l - 16), readU64(s, l - 8), readU64(s, l - 32), readU64(s, l - 24), K[119], K[127], K[103], K[111])
    }
    finish128(r, ll)
    return Uint128(r[0].toULong(), r[1].toULong())
}
/** `xxh3.New()`. */
fun new(): Hasher = Hasher()

// ---- constants (consts.go) ----

private const val STRIPE = 64
private const val BLOCK = 1024
private const val BUF_SIZE = 1088 // BLOCK + STRIPE

private const val PRIME32_1: Long = 2654435761L
private const val PRIME32_2: Long = 2246822519L
private const val PRIME32_3: Long = 3266489917L
private const val PRIME64_1: Long = -7046029288634856825L
private const val PRIME64_2: Long = -4417276706812531889L
private const val PRIME64_3: Long = 1609587929392839161L
private const val PRIME64_4: Long = -8796714831421723037L
private const val PRIME64_5: Long = 2870177450012600261L

/** `0x9fb21c651e98df25`. */
private const val RRMXMX_MUL: Long = -6939452855193903323L

/** `0x165667919e3779f9`. */
private const val AVALANCHE_MUL: Long = 0x165667919e3779f9L

/** The 192-byte default secret (`key`). */
private val KEY: IntArray = intArrayOf(
    0xb8, 0xfe, 0x6c, 0x39, 0x23, 0xa4, 0x4b, 0xbe, 0x7c, 0x01, 0x81, 0x2c, 0xf7, 0x21, 0xad, 0x1c,
    0xde, 0xd4, 0x6d, 0xe9, 0x83, 0x90, 0x97, 0xdb, 0x72, 0x40, 0xa4, 0xa4, 0xb7, 0xb3, 0x67, 0x1f,
    0xcb, 0x79, 0xe6, 0x4e, 0xcc, 0xc0, 0xe5, 0x78, 0x82, 0x5a, 0xd0, 0x7d, 0xcc, 0xff, 0x72, 0x21,
    0xb8, 0x08, 0x46, 0x74, 0xf7, 0x43, 0x24, 0x8e, 0xe0, 0x35, 0x90, 0xe6, 0x81, 0x3a, 0x26, 0x4c,
    0x3c, 0x28, 0x52, 0xbb, 0x91, 0xc3, 0x00, 0xcb, 0x88, 0xd0, 0x65, 0x8b, 0x1b, 0x53, 0x2e, 0xa3,
    0x71, 0x64, 0x48, 0x97, 0xa2, 0x0d, 0xf9, 0x4e, 0x38, 0x19, 0xef, 0x46, 0xa9, 0xde, 0xac, 0xd8,
    0xa8, 0xfa, 0x76, 0x3f, 0xe3, 0x9c, 0x34, 0x3f, 0xf9, 0xdc, 0xbb, 0xc7, 0xc7, 0x0b, 0x4f, 0x1d,
    0x8a, 0x51, 0xe0, 0x4b, 0xcd, 0xb4, 0x59, 0x31, 0xc8, 0x9f, 0x7e, 0xc9, 0xd9, 0x78, 0x73, 0x64,
    0xea, 0xc5, 0xac, 0x83, 0x34, 0xd3, 0xeb, 0xc3, 0xc5, 0x81, 0xa0, 0xff, 0xfa, 0x13, 0x63, 0xeb,
    0x17, 0x0d, 0xdd, 0x51, 0xb7, 0xf0, 0xda, 0x49, 0xd3, 0x16, 0x55, 0x26, 0x29, 0xd4, 0x68, 0x9e,
    0x2b, 0x16, 0xbe, 0x58, 0x7d, 0x47, 0xa1, 0xfc, 0x8f, 0xf8, 0xb8, 0xd1, 0x7a, 0xd0, 0x31, 0xce,
    0x45, 0xcb, 0x3a, 0x8f, 0x95, 0x16, 0x04, 0x28, 0xaf, 0xd7, 0xfb, 0xca, 0xbb, 0x4b, 0x40, 0x7e,
)

/** `K[o]` = the little-endian `uint64` of the secret at byte offset `o` (Go's `key64_<o>` constants). */
private val K: LongArray = LongArray(KEY.size - 7) { o ->
    var v = 0L
    for (i in 7 downTo 0) v = (v shl 8) or KEY[o + i].toLong()
    v
}

/** `uint32` reads of the secret (Go's `key32_<o>` constants). */
private fun k32(o: Int): Long = K[o] and 0xFFFFFFFFL

// ---- reads and mixers (utils.go) ----

private fun readU8(s: String, o: Int): Long = (s[o].code and 0xFF).toLong()

private fun readU16(s: String, o: Int): Long = readU8(s, o) or (readU8(s, o + 1) shl 8)

private fun readU32(s: String, o: Int): Long =
    readU8(s, o) or (readU8(s, o + 1) shl 8) or (readU8(s, o + 2) shl 16) or (readU8(s, o + 3) shl 24)

private fun readU64(s: String, o: Int): Long = readU32(s, o) or (readU32(s, o + 4) shl 32)

/** The high 64 bits of the unsigned 128-bit product (`bits.Mul64`'s `hi`). */
private fun mulHi(x: Long, y: Long): Long {
    val x0 = x and 0xFFFFFFFFL
    val x1 = x ushr 32
    val y0 = y and 0xFFFFFFFFL
    val y1 = y ushr 32
    val w0 = x0 * y0
    val t = x1 * y0 + (w0 ushr 32)
    val w2 = t ushr 32
    val w1 = x0 * y1 + (t and 0xFFFFFFFFL)
    return x1 * y1 + w2 + (w1 ushr 32)
}

private fun mulFold64(x: Long, y: Long): Long = mulHi(x, y) xor (x * y)

private fun reverseBytes64(x: Long): Long {
    var v = x
    var r = 0L
    for (i in 0 until 8) {
        r = (r shl 8) or (v and 0xFF)
        v = v ushr 8
    }
    return r
}

private fun reverseBytes32(x: Int): Int =
    ((x and 0xFF) shl 24) or ((x and 0xFF00) shl 8) or ((x ushr 8) and 0xFF00) or (x ushr 24)

private fun xxh64AvalancheSmall(x0: Long): Long {
    var x = x0
    x *= PRIME64_2
    x = x xor (x ushr 29)
    x *= PRIME64_3
    x = x xor (x ushr 32)
    return x
}

private fun xxhAvalancheSmall(x0: Long): Long {
    var x = x0
    x = x xor (x ushr 33)
    x *= PRIME64_2
    x = x xor (x ushr 29)
    x *= PRIME64_3
    x = x xor (x ushr 32)
    return x
}

private fun xxh3Avalanche(x0: Long): Long {
    var x = x0
    x = x xor (x ushr 37)
    x *= AVALANCHE_MUL
    x = x xor (x ushr 32)
    return x
}

private fun rrmxmx(h0: Long, len: Long): Long {
    var h = h0
    h = h xor (h.rotateLeft(49) xor h.rotateLeft(24))
    h *= RRMXMX_MUL
    h = h xor ((h ushr 35) + len)
    h *= RRMXMX_MUL
    h = h xor (h ushr 28)
    return h
}

// ---- the long-input accumulator (accumScalar with the default secret) ----

/** One 64-byte stripe at input offset [p] against the secret at offset [k]. */
private fun accumulateStripe(accs: LongArray, s: String, p: Int, k: Int) {
    for (j in 0 until 8) {
        val dv = readU64(s, p + 8 * j)
        val dk = dv xor K[k + 8 * j]
        accs[j xor 1] += dv
        accs[j] += (dk and 0xFFFFFFFFL) * (dk ushr 32)
    }
}

private fun accumScalar(accs: LongArray, s: String, len: Int) {
    var p = 0
    var l = len
    while (l > BLOCK) {
        for (i in 0 until 16) accumulateStripe(accs, s, p + i * STRIPE, 8 * i)
        p += BLOCK
        l -= BLOCK
        // scramble
        for (j in 0 until 8) {
            var a = accs[j]
            a = a xor (a ushr 47)
            a = a xor K[128 + 8 * j]
            a *= PRIME32_1
            accs[j] = a
        }
    }
    if (l > 0) {
        val t = (l - 1) / STRIPE
        for (i in 0 until t) accumulateStripe(accs, s, p + i * STRIPE, 8 * i)
        // the last stripe ends at the end of the input, against the secret at 192 - 64 - 7
        accumulateStripe(accs, s, len - STRIPE, 121)
    }
}

/** `accumBlockScalar`: one 1024-byte block at [p] (16 stripes) then the scramble. */
private fun accumBlock(accs: LongArray, s: String, p: Int) {
    for (i in 0 until 16) accumulateStripe(accs, s, p + i * STRIPE, 8 * i)
    for (j in 0 until 8) {
        var a = accs[j]
        a = a xor (a ushr 47)
        a = a xor K[128 + 8 * j]
        a *= PRIME32_1
        accs[j] = a
    }
}

private fun initialAccs(): LongArray = longArrayOf(
    PRIME32_3, PRIME64_1, PRIME64_2, PRIME64_3,
    PRIME64_4, PRIME32_2, PRIME64_5, PRIME32_1,
)

// ---- hash64.go ----

private fun hashAny(s: String): Long {
    val l = s.length
    var acc: Long
    when {
        l <= 16 -> {
            when {
                l > 8 -> {
                    val inputlo = readU64(s, 0) xor (K[24] xor K[32])
                    val inputhi = readU64(s, l - 8) xor (K[40] xor K[48])
                    val folded = mulFold64(inputlo, inputhi)
                    return xxh3Avalanche(l.toLong() + reverseBytes64(inputlo) + inputhi + folded)
                }
                l > 3 -> {
                    val input1 = readU32(s, 0)
                    val input2 = readU32(s, l - 4)
                    val input64 = input2 + (input1 shl 32)
                    val keyed = input64 xor (K[8] xor K[16])
                    return rrmxmx(keyed, l.toLong())
                }
                l == 3 -> acc = (readU16(s, 0) shl 16) + readU8(s, 2) + (3L shl 8)
                l == 2 -> acc = ((readU16(s, 0) * ((1L shl 24) + 1)) ushr 8) + (2L shl 8)
                l == 1 -> acc = readU8(s, 0) * ((1L shl 24) + (1L shl 16) + 1) + (1L shl 8)
                else -> return 0x2d06800538d394c2L
            }
            acc = acc xor (k32(0) xor k32(4))
            return xxhAvalancheSmall(acc)
        }

        l <= 128 -> {
            acc = l.toLong() * PRIME64_1
            if (l > 32) {
                if (l > 64) {
                    if (l > 96) {
                        acc += mulFold64(readU64(s, 6 * 8) xor K[96], readU64(s, 7 * 8) xor K[104])
                        acc += mulFold64(readU64(s, l - 8 * 8) xor K[112], readU64(s, l - 7 * 8) xor K[120])
                    }
                    acc += mulFold64(readU64(s, 4 * 8) xor K[64], readU64(s, 5 * 8) xor K[72])
                    acc += mulFold64(readU64(s, l - 6 * 8) xor K[80], readU64(s, l - 5 * 8) xor K[88])
                }
                acc += mulFold64(readU64(s, 2 * 8) xor K[32], readU64(s, 3 * 8) xor K[40])
                acc += mulFold64(readU64(s, l - 4 * 8) xor K[48], readU64(s, l - 3 * 8) xor K[56])
            }
            acc += mulFold64(readU64(s, 0) xor K[0], readU64(s, 8) xor K[8])
            acc += mulFold64(readU64(s, l - 2 * 8) xor K[16], readU64(s, l - 8) xor K[24])
            return xxh3Avalanche(acc)
        }

        l <= 240 -> {
            acc = l.toLong() * PRIME64_1
            for (i in 0 until 8) acc += mulFold64(readU64(s, 16 * i) xor K[16 * i], readU64(s, 16 * i + 8) xor K[16 * i + 8])
            acc = xxh3Avalanche(acc)
            val top = l and 15.inv()
            var i = 8 * 16
            while (i < top) {
                acc += mulFold64(readU64(s, i) xor K[i - 125], readU64(s, i + 8) xor K[i - 117])
                i += 16
            }
            acc += mulFold64(readU64(s, l - 16) xor K[119], readU64(s, l - 8) xor K[127])
            return xxh3Avalanche(acc)
        }

        else -> {
            acc = l.toLong() * PRIME64_1
            val accs = initialAccs()
            accumScalar(accs, s, l)
            acc += mulFold64(accs[0] xor K[11], accs[1] xor K[19])
            acc += mulFold64(accs[2] xor K[27], accs[3] xor K[35])
            acc += mulFold64(accs[4] xor K[43], accs[5] xor K[51])
            acc += mulFold64(accs[6] xor K[59], accs[7] xor K[67])
            return xxh3Avalanche(acc)
        }
    }
}

// ---- hash128.go ----

/** One 32-byte round of the 17..240 paths: `(hi, lo)` updated in [r] (`r[0]` = hi, `r[1]` = lo). */
private fun round128(r: LongArray, a0: Long, a1: Long, b0: Long, b1: Long, kb0: Long, kb1: Long, ka0: Long, ka1: Long) {
    // acc.Hi += mulFold64(b0^kb0, b1^kb1); acc.Hi ^= a0 + a1
    // acc.Lo += mulFold64(a0^ka0, a1^ka1); acc.Lo ^= b0 + b1
    r[0] += mulFold64(b0 xor kb0, b1 xor kb1)
    r[0] = r[0] xor (a0 + a1)
    r[1] += mulFold64(a0 xor ka0, a1 xor ka1)
    r[1] = r[1] xor (b0 + b1)
}

/** Writes `(hi, lo)` into [r]. */
private fun hashAny128(s: String, r: LongArray) {
    val l = s.length
    val ll = l.toLong()
    when {
        l <= 16 -> {
            var lo: Long
            when {
                l > 8 -> {
                    val bitflipl = K[32] xor K[40]
                    val bitfliph = K[48] xor K[56]
                    val inputLo = readU64(s, 0)
                    var inputHi = readU64(s, l - 8)
                    val x = inputLo xor inputHi xor bitflipl
                    var mh = mulHi(x, PRIME64_1)
                    var ml = x * PRIME64_1
                    ml += (ll - 1) shl 54
                    inputHi = inputHi xor bitfliph
                    mh += inputHi + (inputHi and 0xFFFFFFFFL) * (PRIME32_2 - 1)
                    ml = ml xor reverseBytes64(mh)
                    var hi = mulHi(ml, PRIME64_2)
                    lo = ml * PRIME64_2
                    hi += mh * PRIME64_2
                    r[0] = xxh3Avalanche(hi)
                    r[1] = xxh3Avalanche(lo)
                    return
                }
                l > 3 -> {
                    val bitflip = K[16] xor K[24]
                    val inputLo = readU32(s, 0)
                    val inputHi = readU32(s, l - 4)
                    val input64 = inputLo + (inputHi shl 32)
                    val keyed = input64 xor bitflip
                    val m = PRIME64_1 + (ll shl 2)
                    var hi = mulHi(keyed, m)
                    lo = keyed * m
                    hi += lo shl 1
                    lo = lo xor (hi ushr 3)
                    lo = lo xor (lo ushr 35)
                    lo *= RRMXMX_MUL
                    lo = lo xor (lo ushr 28)
                    r[0] = xxh3Avalanche(hi)
                    r[1] = lo
                    return
                }
                l == 3 -> lo = (readU16(s, 0) shl 16) + readU8(s, 2) + (3L shl 8)
                l == 2 -> lo = ((readU16(s, 0) * ((1L shl 24) + 1)) ushr 8) + (2L shl 8)
                l == 1 -> lo = readU8(s, 0) * ((1L shl 24) + (1L shl 16) + 1) + (1L shl 8)
                else -> {
                    r[0] = 0x99aa06d3014798d8uL.toLong()
                    r[1] = 0x6001c324468d497fL
                    return
                }
            }
            var hi = (reverseBytes32(lo.toInt()).rotateLeft(13).toLong()) and 0xFFFFFFFFL
            lo = lo xor (k32(0) xor k32(4))
            hi = hi xor (k32(8) xor k32(12))
            r[0] = xxh64AvalancheSmall(hi)
            r[1] = xxh64AvalancheSmall(lo)
        }

        l <= 128 -> {
            r[0] = 0L
            r[1] = ll * PRIME64_1
            if (l > 32) {
                if (l > 64) {
                    if (l > 96) {
                        round128(r, readU64(s, 6 * 8), readU64(s, 7 * 8), readU64(s, l - 8 * 8), readU64(s, l - 7 * 8), K[112], K[120], K[96], K[104])
                    }
                    round128(r, readU64(s, 4 * 8), readU64(s, 5 * 8), readU64(s, l - 6 * 8), readU64(s, l - 5 * 8), K[80], K[88], K[64], K[72])
                }
                round128(r, readU64(s, 2 * 8), readU64(s, 3 * 8), readU64(s, l - 4 * 8), readU64(s, l - 3 * 8), K[48], K[56], K[32], K[40])
            }
            round128(r, readU64(s, 0), readU64(s, 8), readU64(s, l - 2 * 8), readU64(s, l - 8), K[16], K[24], K[0], K[8])
            finish128(r, ll)
        }

        l <= 240 -> {
            r[0] = 0L
            r[1] = ll * PRIME64_1
            for (g in 0 until 4) {
                val o = 32 * g
                // here the Go code pairs (i0, i1) with the Lo key and (i2, i3) with the Hi key
                round128(r, readU64(s, o), readU64(s, o + 8), readU64(s, o + 16), readU64(s, o + 24), K[o + 16], K[o + 24], K[o], K[o + 8])
            }
            r[0] = xxh3Avalanche(r[0])
            r[1] = xxh3Avalanche(r[1])
            val top = l and 31.inv()
            var i = 4 * 32
            while (i < top) {
                round128(r, readU64(s, i), readU64(s, i + 8), readU64(s, i + 16), readU64(s, i + 24), K[i - 109], K[i - 101], K[i - 125], K[i - 117])
                i += 32
            }
            // last 32 bytes: here (i2, i3) feed the Lo key and (i0, i1) the Hi key
            round128(r, readU64(s, l - 16), readU64(s, l - 8), readU64(s, l - 32), readU64(s, l - 24), K[119], K[127], K[103], K[111])
            finish128(r, ll)
        }

        else -> {
            var lo = ll * PRIME64_1
            var hi = (ll * PRIME64_2).inv()
            val accs = initialAccs()
            accumScalar(accs, s, l)
            lo += mulFold64(accs[0] xor K[11], accs[1] xor K[19])
            hi += mulFold64(accs[0] xor K[117], accs[1] xor K[125])
            lo += mulFold64(accs[2] xor K[27], accs[3] xor K[35])
            hi += mulFold64(accs[2] xor K[133], accs[3] xor K[141])
            lo += mulFold64(accs[4] xor K[43], accs[5] xor K[51])
            hi += mulFold64(accs[4] xor K[149], accs[5] xor K[157])
            lo += mulFold64(accs[6] xor K[59], accs[7] xor K[67])
            hi += mulFold64(accs[6] xor K[165], accs[7] xor K[173])
            r[0] = xxh3Avalanche(hi)
            r[1] = xxh3Avalanche(lo)
        }
    }
}

/** The common tail of the 17..240 paths. */
private fun finish128(r: LongArray, ll: Long) {
    val hi = r[0]
    val lo = r[1]
    r[0] = -xxh3Avalanche(lo * PRIME64_1 + hi * PRIME64_4 + ll * PRIME64_2)
    r[1] = xxh3Avalanche(hi + lo)
}
