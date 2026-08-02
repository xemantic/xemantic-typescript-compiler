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
import kotlin.test.fail

/**
 * (JIT.1) — the gate for the ONE thing no other instrument in this repo can see.
 *
 * HotSpot's `DontCompileHugeMethods` is a PRODUCT flag defaulting to `true` and
 * `HugeMethodLimit` is **8,000 bytecodes**: a method whose `Code` attribute is
 * longer than that is NEVER compiled by C1 or C2 and runs in the interpreter for
 * the whole process, however hot it gets. Nothing else notices — the corpus does
 * not measure cost, `cost_gate.py`'s counters do not move, and
 * `-XX:+PrintCompilation` prints no "too large" line (the compile is never
 * *proposed*, so it is never *skipped*; round 802 grepped an 11,796-line log and
 * got 0 hits). `scripts/huge_methods.py` is the whole-program census; this test
 * is the always-on guard for the two functions split so far: [forEachChild], the
 * traversal primitive every walk in the compiler goes through (round 802
 * measured it at **9,750 bytecodes**, round 803 split it), and
 * `Checker.checkMemberAccessMissingCore`, which at **46,567 bytecodes** was the
 * largest method in the compiler and round 789's largest leaf in the compile
 * (round 804 split it).
 *
 * It reads the compiled class file off the test classpath and parses the `Code`
 * attribute length directly — the same number `javap` prints and the same number
 * HotSpot compares against the limit.
 */
class HugeMethodLimitTest {

    private companion object {
        /** HotSpot's `HugeMethodLimit` (product-flag constant, not tunable at runtime). */
        const val HUGE_METHOD_LIMIT = 8000
    }

    /** method simple name -> `Code` attribute length, for one compiled class. */
    private fun codeSizes(binaryName: String): Map<String, Int> {
        val resource = binaryName.replace('.', '/') + ".class"
        val bytes = HugeMethodLimitTest::class.java.classLoader
            .getResourceAsStream(resource)?.readBytes()
            ?: fail("$resource is not on the test classpath")
        return ClassFileReader(bytes).methodCodeSizes()
    }

    @Test
    fun `forEachChild is below HotSpot's HugeMethodLimit so it can be JIT-compiled at all`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.NodeWalkKt")
        val forEachChild = sizes["forEachChild"] ?: fail("forEachChild not found in NodeWalkKt: ${sizes.keys}")
        // Positive control: the parse really did read a large method, so a silently
        // empty/zero result cannot pass this test vacuously.
        assert(forEachChild > 200)
        assert(forEachChild < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every method of the traversal primitive's file is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.NodeWalkKt")
        assert(sizes.size >= 4)
        val over = sizes.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the split parts each carry a real share of the enumeration`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.NodeWalkKt")
        // Every part must hold substantial work: a "split" that left one part
        // holding everything would pass the limit test only by luck.
        val parts = sizes.filterKeys { it.startsWith("forEachChild") }
        assert(parts.size >= 3)
        assert(parts.values.min() > 500)
    }

    /**
     * (JIT.1)(b) round 804 — the ten helpers `checkMemberAccessMissingCore` was
     * split into. It was **46,567 bytecodes**, 5.8x the limit and the single
     * largest method in the compiler; round 789 measured it as the largest leaf in
     * the compile as well.
     */
    private val cmamSplitParts = setOf(
        "cmamCheckLiteralAndNewReceiver",
        "cmamCheckCallAndAccessReceiver",
        "cmamGeneralReceiverType",
        "cmamCheckCastAndNamespaceReceiver",
        "cmamCheckUnionReceiverNarrowing",
        "cmamCheckNonIdentifierReceiver",
        "cmamCheckIdentSymbolTypeGates",
        "cmamCheckIdentSymbolValueGates",
        "cmamCheckResolvedObjectType",
        "cmamEmitMissingProperty",
    )

    @Test
    fun `checkMemberAccessMissingCore is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val core = sizes["checkMemberAccessMissingCore"]
            ?: fail("checkMemberAccessMissingCore not found in Checker")
        // Positive control: the parse really did read a large method (it is 6,425
        // bytecodes after the split), so a zero cannot pass this vacuously.
        assert(core > 2000)
        assert(core < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the checkMemberAccessMissing split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val missing = cmamSplitParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in Checker: $missing")
        val parts = sizes.filterKeys { it in cmamSplitParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the checkMemberAccessMissing split parts each carry a real share of the body`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val parts = sizes.filterKeys { it in cmamSplitParts }
        assert(parts.size == 10)
        // A "split" that left one part holding everything would clear the limit
        // test by luck. Measured smallest part: 611 bytecodes.
        assert(parts.values.min() > 400)
        // ... and the sum must still be the bulk of the original 46,567, i.e. the
        // body was MOVED, not deleted.
        assert(parts.values.sum() > 20000)
    }

    /** A minimal JVM class-file reader — enough to walk to each method's `Code` length. */
    private class ClassFileReader(private val b: ByteArray) {
        private var p = 0
        private fun u1(): Int = b[p++].toInt() and 0xff
        private fun u2(): Int = (u1() shl 8) or u1()
        private fun u4(): Int = (u2() shl 16) or u2()
        private fun skip(n: Int) { p += n }

        fun methodCodeSizes(): Map<String, Int> {
            require(u4() == -0x35014542) { "not a class file" } // 0xCAFEBABE
            skip(4) // minor, major
            val cpCount = u2()
            val utf8 = HashMap<Int, String>()
            var i = 1
            while (i < cpCount) {
                when (val tag = u1()) {
                    1 -> { val len = u2(); utf8[i] = String(b, p, len, Charsets.UTF_8); skip(len) }
                    3, 4, 9, 10, 11, 12, 17, 18 -> skip(4)
                    5, 6 -> { skip(8); i++ } // long/double take two slots
                    7, 8, 16, 19, 20 -> skip(2)
                    15 -> skip(3)
                    else -> error("unknown constant pool tag $tag at #$i")
                }
                i++
            }
            skip(2 + 2 + 2)          // access, this, super
            skip(2 * u2())           // interfaces
            repeat(u2()) {           // fields
                skip(6); repeat(u2()) { skip(2); skip(u4()) }
            }
            val out = LinkedHashMap<String, Int>()
            repeat(u2()) {           // methods
                skip(2)
                val name = utf8[u2()] ?: "?"
                skip(2)
                repeat(u2()) {
                    val attrName = utf8[u2()]
                    val attrLen = u4()
                    if (attrName == "Code") {
                        val end = p + attrLen
                        skip(4)      // max_stack, max_locals
                        out[name] = u4()
                        p = end
                    } else skip(attrLen)
                }
            }
            return out
        }
    }
}
