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
import java.io.File
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
 * is the always-on guard for the functions split so far: [forEachChild], the
 * traversal primitive every walk in the compiler goes through (round 802
 * measured it at **9,750 bytecodes**, round 803 split it), and
 * `Checker.checkMemberAccessMissingCore`, which at **46,567 bytecodes** was the
 * largest method in the compiler and round 789's largest leaf in the compile
 * (round 804 split it), and `Checker.checkPropertyAccessInExpr` at **9,062**
 * (round 805 split it), and `Checker.ccetSpineEnter` at **8,686** — which runs at
 * EVERY node of every file (round 806 split it) — and
 * `Checker.checkArgumentsAgainstSignatureCore` at **23,890** (round 807 split it),
 * and — sub-item (d) — `Checker.checkDuplicateDeclarations` at **12,935**
 * (round 812 split it), and — sub-item (e) — `CompilerOptionsKt.applyDirective`
 * at **13,694** (round 815 split it) and `TypeScriptCompiler.compileParsedCore`
 * at **21,535** (round 816 split it).
 *
 * **Round 821 took the census to ZERO** with
 * `Checker.tryInferSingleTypeParamFromArgs` (11,930), so [KNOWN_OVER_LIMIT] is
 * empty and [CENSUS_RATCHET] is 0. From here this test's only job is to catch the
 * NEXT method that crosses the limit — which is the job it was always for.
 *
 * It reads the compiled class file off the test classpath and parses the `Code`
 * attribute length directly — the same number `javap` prints and the same number
 * HotSpot compares against the limit.
 */
class HugeMethodLimitTest {

    private companion object {
        /** HotSpot's `HugeMethodLimit` (product-flag constant, not tunable at runtime). */
        const val HUGE_METHOD_LIMIT = 8000

        /**
         * (JIT.1)(f) round 817 — **THE RATCHET**, and the number that must only
         * ever go DOWN.
         *
         * This family grew unnoticed for 800 rounds because nothing could see it:
         * a method crossing 8,000 bytecodes is a silent, permanent, whole-run
         * performance cliff that the corpus (which measures meaning, not cost),
         * `cost_gate.py` (whose counters do not move) and
         * `-XX:+PrintCompilation` (which prints nothing — the compile is never
         * *proposed*, so it is never *skipped*) are all blind to. Round 802 found
         * **19** over the limit by running a census for the first time.
         *
         * The honest gate today is therefore a RATCHET, not a zero: the census
         * stands at [CENSUS_RATCHET] and every one of those methods is NAMED in
         * [KNOWN_OVER_LIMIT], so a NEW offender fails immediately while the known
         * ones are worked off one round at a time. **`0` is the end state, not a
         * precondition.**
         *
         * **ROUND 821: THE END STATE IS REACHED — the census is 0 and
         * [KNOWN_OVER_LIMIT] is empty.** The ratchet keeps its shape rather than
         * becoming an equality check, because its job is now the opposite one: it
         * fails the moment a NEW method crosses 8,000 bytecodes, which is how this
         * family grew unnoticed for 800 rounds in the first place. A red gate here
         * is never fixed by raising the number.
         *
         * THE TIGHTENING RULE, which the second test below enforces mechanically:
         * when a split lands, DROP the method from [KNOWN_OVER_LIMIT] and set
         * [CENSUS_RATCHET] to the new census — `every method over the limit is one
         * of the known, named offenders` FAILS on a stale entry, so the ratchet
         * cannot silently stay loose after the work that should have tightened it.
         * Raising either number is never the fix for a red gate: split the method.
         *
         * `scripts/huge_methods.py --fail-over <CENSUS_RATCHET>` is the same gate
         * outside the suite (it is what a round runs by hand next to
         * `cost_gate.py`); wiring it into Gradle's `check` is a build-system
         * change and is owner-gated as queue item (JIT.3).
         */
        const val CENSUS_RATCHET = 0

        /**
         * The whole census, named — `<binary class name>#<method simple name>`.
         *
         * **It is EMPTY as of round 821, which closes (JIT.1).** The last entry
         * was `Checker.tryInferSingleTypeParamFromArgs` at **11,930** bytecodes,
         * and it was the only target in the arc that no contiguity argument
         * settled: its bytecodes are FLAT (largest 25-line window 449) and one
         * `for (tp in orderedTps)` loop holds essentially all of them. It was
         * split from a scripted DATA-FLOW analysis instead — read/write sets,
         * liveness and an exit classification per region — which found that the
         * only value REBOUND across a boundary is `tpSawAnyArg` (returned, not
         * fielded) and that `candidates` is append-only (so it crosses as a
         * parameter). See `TispSplitTest` and `scripts/tisp_split_analyze.py`.
         *
         * `Checker.<clinit>` came off this list at round 820. It was a static
         * initializer whose 10,339 bytecodes were the class's `object`-level
         * COLLECTION constants — a `private const val` of a primitive costs
         * `<clinit>` nothing, because it carries a `ConstantValue` attribute —
         * so it shrank by hoisting the seven largest literals into the top-level
         * `ckConst*` builders pinned below. No A/B in this repo could price it:
         * a static initializer runs once, at class load.
         *
         * The `Transformer` entries are gone: round 817 split `transform`, round
         * 818 `transformClassBody` and round 819 `transformToCommonJS`. All three
         * sit on the EMIT path, so every `--noEmit` A/B in this arc was blind to
         * them and their gate was the corpus suite's emit baselines.
         *
         * **`Checker.<clinit>` was on this list because THIS TEST FOUND IT, on
         * its first run, and the reason it had never been seen is the reason a
         * second instrument was worth building.** `scripts/huge_methods.py` reads
         * `javap`, and `javap` renders the static initializer as `static {};` —
         * with no parameter list — so the script's method-header regex (which
         * requires a `(`) never started a method there and charged all 10,339 of
         * its bytecodes to whatever method happened to precede it in the class
         * file. That method is `access$checkBigintPropertyNames$emit`, a **16-byte
         * access bridge**, which the queue had consequently carried as a
         * 10,339-bytecode split target since round 802. The script is fixed; the
         * lesson is that this test parses `Code` attribute lengths straight out of
         * the class file, so it has no rendering to misread.
         */
        val KNOWN_OVER_LIMIT = emptySet<String>()

        /**
         * `<binary class name>#<method>` -> `Code` length, for EVERY method of the
         * compiled main output, computed once.
         *
         * The population is exactly `scripts/huge_methods.py`'s: the directory the
         * main classes are compiled into, located from a marker resource rather
         * than hard-coded, so it follows the build layout. The two instruments
         * differ by a few bytes on the same method — the script reports the OFFSET
         * of the last opcode, this reports the `Code` attribute LENGTH (what
         * HotSpot itself compares) — which cannot change which side of 8,000 a
         * method falls on in any real case.
         */
        val wholeProgramCensus: Map<String, Int> by lazy { computeCensus() }

        private fun computeCensus(): Map<String, Int> {
            val marker = "com/xemantic/typescript/compiler/Checker.class"
            val url = HugeMethodLimitTest::class.java.classLoader.getResource(marker)
                ?: fail("$marker is not on the test classpath")
            if (url.protocol != "file") fail("main classes are not a directory on the classpath: $url")
            var root = File(url.toURI())
            repeat(marker.count { it == '/' } + 1) { root = root.parentFile }
            if (!File(root, marker).isFile) fail("could not locate the main classes root from $url")
            val classFiles = root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()
            val out = LinkedHashMap<String, Int>()
            var methods = 0
            var largest = 0
            for (f in classFiles) {
                val binary = f.relativeTo(root).path
                    .removeSuffix(".class").replace(File.separatorChar, '.')
                for ((name, size) in ClassFileReader(f.readBytes()).methodCodeSizes()) {
                    methods++
                    if (size > largest) largest = size
                    out["$binary#$name"] = size
                }
            }
            // POSITIVE CONTROLS. A census that silently read nothing would pass the
            // ratchet vacuously, which is exactly the failure round 814's empty
            // diagnostic list produced. Measured at round 817: 578 classes, 14,001
            // methods, largest 28,991. `largest` is deliberately checked against a
            // bound BELOW the limit (`walkFunctionBodiesInExpr` is 7,702), so the
            // control still holds in the end state where nothing is over 8,000.
            val classCount = classFiles.size
            assert(classCount >= 400)
            assert(methods >= 10_000)
            assert(largest > 5_000)
            return out
        }
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
    fun `the whole-program huge-method census is at or below the ratchet`() {
        val over = wholeProgramCensus.filterValues { it > HUGE_METHOD_LIMIT }
        if (over.size > CENSUS_RATCHET) fail(
            "(JIT.1)(f) RATCHET: ${over.size} methods are over HotSpot's HugeMethodLimit " +
                "of $HUGE_METHOD_LIMIT bytecodes, and the budget is $CENSUS_RATCHET.\n" +
                "  new offenders : ${(over.keys - KNOWN_OVER_LIMIT).sorted()}\n" +
                "  whole census  : ${over.toList().sortedByDescending { it.second }}\n" +
                "A method above the limit is NEVER JIT-compiled by C1 or C2 — it runs in " +
                "the interpreter for the entire process, however hot it gets, and no other " +
                "gate in this repo can see that. Split it; do not raise CENSUS_RATCHET."
        )
    }

    @Test
    fun `every method over the limit is one of the known, named offenders`() {
        val over = wholeProgramCensus.filterValues { it > HUGE_METHOD_LIMIT }
        val fresh = (over.keys - KNOWN_OVER_LIMIT).sorted()
        if (fresh.isNotEmpty()) fail(
            "(JIT.1)(f) NEW methods over HotSpot's HugeMethodLimit: " +
                "${fresh.map { it to over[it] }} — split them, do not name them here."
        )
        val fixed = (KNOWN_OVER_LIMIT - over.keys).sorted()
        if (fixed.isNotEmpty()) fail(
            "(JIT.1)(f) TIGHTEN THE RATCHET: $fixed are no longer over the limit. " +
                "Drop them from KNOWN_OVER_LIMIT and set CENSUS_RATCHET to ${over.size}."
        )
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

    /**
     * (JIT.1)(c) round 805 — the four helpers `checkPropertyAccessInExpr` was
     * split into. It was **9,062 bytecodes**; its four largest `when` arms are now
     * `cpaExpr*` functions and the entry is 4,728.
     */
    private val cpaExprSplitParts = setOf(
        "cpaExprArrowFunction",
        "cpaExprObjectLiteral",
        "cpaExprFunctionExpression",
        "cpaExprClassExpression",
    )

    @Test
    fun `checkPropertyAccessInExpr is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val fn = sizes["checkPropertyAccessInExpr"]
            ?: fail("checkPropertyAccessInExpr not found in Checker")
        // Positive control: the parse really did read a large method (it is 4,728
        // bytecodes after the split), so a zero cannot pass this vacuously.
        assert(fn > 2000)
        assert(fn < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the checkPropertyAccessInExpr split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val missing = cpaExprSplitParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in Checker: $missing")
        val parts = sizes.filterKeys { it in cpaExprSplitParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the checkPropertyAccessInExpr split parts each carry a real share of the body`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val parts = sizes.filterKeys { it in cpaExprSplitParts }
        assert(parts.size == 4)
        // Measured smallest part: 528 bytecodes (the ClassExpression arm).
        assert(parts.values.min() > 300)
        // ... and the sum must still be the bulk of what left the entry, i.e. the
        // arms were MOVED, not deleted. Measured sum: 4,261.
        assert(parts.values.sum() > 3000)
    }

    /**
     * (JIT.1)(d) round 806 — the three helpers `ccetSpineEnter` was split into.
     * It was **8,686 bytecodes** and it runs at EVERY node of every file; three of
     * its four `when (node.kindId)` arms moved out and the entry is 2,474.
     */
    private val ccetEnterSplitParts = setOf(
        "ccetEnterBlock",
        "ccetEnterClassDeclaration",
        "ccetEnterFunctionLike",
    )

    @Test
    fun `ccetSpineEnter is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val fn = sizes["ccetSpineEnter"] ?: fail("ccetSpineEnter not found in Checker")
        // Positive control: the parse really did read a substantial method (it is
        // 2,474 bytecodes after the split), so a zero cannot pass this vacuously.
        assert(fn > 1000)
        assert(fn < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the ccetSpineEnter split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val missing = ccetEnterSplitParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in Checker: $missing")
        val parts = sizes.filterKeys { it in ccetEnterSplitParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the ccetSpineEnter split parts each carry a real share of the body`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val parts = sizes.filterKeys { it in ccetEnterSplitParts }
        assert(parts.size == 3)
        // Measured smallest part: 1,328 bytecodes (the FunctionLike arm).
        assert(parts.values.min() > 800)
        // ... and the sum must still be the bulk of what left the entry, i.e. the
        // arms were MOVED, not deleted. Measured sum: 6,122.
        assert(parts.values.sum() > 4500)
    }

    /**
     * (JIT.1)(f) round 807 — the thirteen helpers `checkArgumentsAgainstSignatureCore`
     * was split into. It was **23,890 bytecodes**, 3.0x the limit and the largest
     * `Checker` method left after round 804. Unlike (a)-(d) this one moves runs of
     * a LOOP BODY, so each helper hands a control signal back to the entry; the
     * behavioural gate for those signals is `CaasSplitTest`.
     */
    private val caasSplitParts = setOf(
        "caasPrologueWalkers",
        "caasSingleTypeParamWalkers",
        "caasWalkerArgChecks",
        "caasObjectLiteralVsObjectParam",
        "caasObjLitProtoOverride",
        "caasObjLitMissingRequired",
        "caasObjLitPerPropertyMismatch",
        "caasNullishArgGates",
        "caasObjectLiteralVsTypeParam",
        "caasTypeParamConstraintArg",
        "caasArgKindAndIndexSignature",
        "caasNonSimpleParamChecks",
        "caasTailGatesAndRelation",
    )

    @Test
    fun `checkArgumentsAgainstSignatureCore is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val core = sizes["checkArgumentsAgainstSignatureCore"]
            ?: fail("checkArgumentsAgainstSignatureCore not found in Checker")
        // Positive control: the parse really did read a large method (it is 7,173
        // bytecodes after the split), so a zero cannot pass this vacuously.
        assert(core > 3000)
        assert(core < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the checkArgumentsAgainstSignature split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val missing = caasSplitParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in Checker: $missing")
        val parts = sizes.filterKeys { it in caasSplitParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the checkArgumentsAgainstSignature split parts each carry a real share of the body`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val parts = sizes.filterKeys { it in caasSplitParts }
        assert(parts.size == 13)
        // Measured smallest part: 456 bytecodes (the object-literal-vs-object
        // dispatcher, whose three long blocks are themselves helpers).
        assert(parts.values.min() > 300)
        // ... and the sum must still be the bulk of the original 23,890, i.e. the
        // body was MOVED, not deleted. Measured sum: ~16,600.
        assert(parts.values.sum() > 12000)
    }

    /**
     * (JIT.1)(c) round 808 — the seven helpers `checkVarDeclAssignabilityCore`
     * was split into. It was **19,296 bytecodes**, 2.4x the limit and the largest
     * `Checker` method left after round 807. Its body is a STRAIGHT-LINE
     * statement sequence, so each helper holds one contiguous run of the
     * committed `CtaSections` level-B partition and hands a return signal back;
     * the behavioural gate for those signals is `CvdaSplitTest`.
     */
    private val cvdaSplitParts = setOf(
        "cvdaPrologueWalkers",
        "cvdaRecordInferredLocalType",
        "cvdaEarlyInitGates",
        "cvdaNestedInitTargets",
        "cvdaMidGates",
        "cvdaPostRelationGates",
        "cvdaElaborateMismatch",
    )

    @Test
    fun `checkVarDeclAssignabilityCore is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val core = sizes["checkVarDeclAssignabilityCore"]
            ?: fail("checkVarDeclAssignabilityCore not found in Checker")
        // Positive control: the parse really did read a substantial method (it is
        // 3,535 bytecodes after the split), so a zero cannot pass this vacuously.
        assert(core > 1500)
        assert(core < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the checkVarDeclAssignability split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val missing = cvdaSplitParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in Checker: $missing")
        val parts = sizes.filterKeys { it in cvdaSplitParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the checkVarDeclAssignability split parts each carry a real share of the body`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val parts = sizes.filterKeys { it in cvdaSplitParts }
        assert(parts.size == 7)
        // Measured smallest part: 392 bytecodes (the unannotated-declaration
        // recording, the shortest run of the level-B partition).
        assert(parts.values.min() > 250)
        // ... and the sum must still be the bulk of the original 19,296, i.e. the
        // body was MOVED, not deleted. Measured sum: ~15,400.
        assert(parts.values.sum() > 12000)
    }

    /**
     * (JIT.1)(g) round 809 — the nine helpers `checkAssignmentExpressionCore` was
     * split into. It was **18,100 bytecodes**, 2.3x the limit and the largest
     * `Checker` method left after round 808. Like (c) it is a STRAIGHT-LINE
     * statement sequence, so each helper holds one contiguous run of the committed
     * `CtaSections` **level-E** partition and seven of the nine hand a return
     * signal back; the behavioural gate for those signals is `CaeSplitTest`.
     */
    private val caeSplitParts = setOf(
        "caePrototypeMemberAssign",
        "caeModuleAliasAndLibPairShapes",
        "caeForeignTpTargetAndClassRhs",
        "caeIndexSigAndSignatureGuards",
        "caeUnionAndMissingPropertyGuards",
        "caeElaborateMismatch",
        "caeLegacyDeclaredStringPath",
        "caeThisPropertyAssign",
        "caeElementAccessAssign",
    )

    @Test
    fun `checkAssignmentExpressionCore is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val core = sizes["checkAssignmentExpressionCore"]
            ?: fail("checkAssignmentExpressionCore not found in Checker")
        // Positive control: the parse really did read a substantial method (it is
        // 3,861 bytecodes after the split), so a zero cannot pass this vacuously.
        assert(core > 1500)
        assert(core < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the checkAssignmentExpression split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val missing = caeSplitParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in Checker: $missing")
        val parts = sizes.filterKeys { it in caeSplitParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the checkAssignmentExpression split parts each carry a real share of the body`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val parts = sizes.filterKeys { it in caeSplitParts }
        assert(parts.size == 9)
        // Measured smallest part: 453 bytecodes (the element-access write tail).
        assert(parts.values.min() > 300)
        // ... and the sum must still be the bulk of the original 18,100, i.e. the
        // body was MOVED, not deleted. Measured sum: ~13,600.
        assert(parts.values.sum() > 10000)
    }

    /**
     * (JIT.1)(h) round 810 — the two helpers `checkReturnAssignabilityCore` was
     * split into. It was **9,743 bytecodes**, and unlike (c)/(f)/(g) it is only
     * 1.2x the limit, so two regions of the committed `CtaSections` **level-C**
     * partition were enough: the guard cluster the partition classifies as the
     * dedicated-walker layer, and the TS2322 elaboration it measures at ONE reach
     * per compiler self-compile. The behavioural gate is `CraSplitTest`.
     */
    private val craSplitParts = setOf(
        "craGuardWalkers",
        "craElaborateReturnMismatch",
    )

    @Test
    fun `checkReturnAssignabilityCore is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val core = sizes["checkReturnAssignabilityCore"]
            ?: fail("checkReturnAssignabilityCore not found in Checker")
        // Positive control: the parse really did read a substantial method (it is
        // 4,052 bytecodes after the split), so a zero cannot pass this vacuously.
        assert(core > 1500)
        assert(core < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the checkReturnAssignability split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val missing = craSplitParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in Checker: $missing")
        val parts = sizes.filterKeys { it in craSplitParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the checkReturnAssignability split parts each carry a real share of the body`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val parts = sizes.filterKeys { it in craSplitParts }
        assert(parts.size == 2)
        // Measured smaller part: 1,851 bytecodes (the TS2322 elaboration).
        assert(parts.values.min() > 1200)
        // ... and the sum must still be the bulk of the original 9,743, i.e. the
        // body was MOVED, not deleted. Measured sum: 5,557.
        assert(parts.values.sum() > 4500)
    }

    /**
     * (JIT.1)(c) round 811 — the four helpers `checkSingleCallExpressionTypesCore`
     * was split into. At **15,567 bytecodes** it was the largest `Checker` method
     * left, and the boundaries are four contiguous runs of the committed
     * `CallSections` partition (round 734's (CALL.1)(a) instrument): the seven
     * dedicated prologue walkers behind round 793's `ccetPrologueMayFire` gate —
     * which STAYS in the entry — plus the three branches the same partition's exit
     * census prices at 0.2% of invocations or less. The behavioural gate is
     * `CcetSplitTest`.
     */
    private val ccetSplitParts = setOf(
        "ccetPrologueWalkers",
        "ccetUnionCalleeChecks",
        "ccetNoCallSignatureDiagnostics",
        "ccetExplicitTypeArguments",
    )

    @Test
    fun `checkSingleCallExpressionTypesCore is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val core = sizes["checkSingleCallExpressionTypesCore"]
            ?: fail("checkSingleCallExpressionTypesCore not found in Checker")
        // Positive control: the parse really did read a substantial method (it is
        // 5,149 bytecodes after the split), so a zero cannot pass this vacuously.
        assert(core > 2000)
        assert(core < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the checkSingleCallExpressionTypes split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val missing = ccetSplitParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in Checker: $missing")
        val parts = sizes.filterKeys { it in ccetSplitParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the checkSingleCallExpressionTypes split parts each carry a real share of the body`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val parts = sizes.filterKeys { it in ccetSplitParts }
        assert(parts.size == 4)
        // Measured smallest part: 2,068 bytecodes (the prologue walkers).
        assert(parts.values.min() > 1500)
        // ... and the sum must still be the bulk of the original 15,567, i.e. the
        // body was MOVED, not deleted. Measured sum: 10,361.
        assert(parts.values.sum() > 8000)
    }

    /**
     * (JIT.1)(d) round 812 — the five helpers `checkDuplicateDeclarations` was
     * split into. It was **12,935 bytecodes**, the largest `Checker` method still
     * over the limit; the entry is 2,801 and holds the collection loop every
     * statement list pays for, while every helper sits behind the entry's
     * `if (group.size < 2) continue`.
     */
    private val cddSplitParts = setOf(
        "cddCheckImportBindings",
        "cddCheckMergedEnums",
        "cddCheckMergedTypeParameters",
        "cddCheckExportUniformity",
        "cddCheckValueRedeclarations",
    )

    @Test
    fun `checkDuplicateDeclarations is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val fn = sizes["checkDuplicateDeclarations"]
            ?: fail("checkDuplicateDeclarations not found in Checker")
        // Positive control: the parse really did read a large method (it is 2,801
        // bytecodes after the split), so a zero cannot pass this vacuously.
        assert(fn > 1200)
        assert(fn < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the checkDuplicateDeclarations split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val missing = cddSplitParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in Checker: $missing")
        val parts = sizes.filterKeys { it in cddSplitParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the checkDuplicateDeclarations split parts each carry a real share of the body`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val parts = sizes.filterKeys { it in cddSplitParts }
        assert(parts.size == 5)
        // Measured smallest part: 955 bytecodes (the merged-enum checks).
        assert(parts.values.min() > 600)
        // ... and the sum must still be the bulk of the original 12,935, i.e. the
        // body was MOVED, not deleted. Measured sum: 10,115.
        assert(parts.values.sum() > 8000)
    }

    /**
     * (JIT.1)(d) round 813 — the seven helpers `checkIndexSigInStatement` was
     * split into. It was **10,928 bytecodes**; the entry is **1,010** and holds
     * only the dispatch every statement pays for (the type-alias and
     * variable-statement branches, the `when` that decides whether the statement
     * has members at all, the `ModuleDeclaration` recursion, the two index-
     * signature lookups and the "no usable string index type" early return).
     */
    private val cisSplitParts = setOf(
        "cisCheckNumericNamePropsVsNumberIndex",
        "cisFindStringIndexSig",
        "cisCheckAnonIndexValueConflict",
        "cisCheckNamedInterfaceIndexValueConflict",
        "cisCheckNumericMethodsVsNumberIndex",
        "cisCheckMethodsVsPrimitiveStringIndex",
        "cisCheckPropsVsStringIndex",
    )

    @Test
    fun `checkIndexSigInStatement is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val fn = sizes["checkIndexSigInStatement"]
            ?: fail("checkIndexSigInStatement not found in Checker")
        // Positive control: the parse really did read this method (it is 1,010
        // bytecodes after the split), so a zero cannot pass this vacuously.
        assert(fn > 500)
        assert(fn < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the checkIndexSigInStatement split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val missing = cisSplitParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in Checker: $missing")
        val parts = sizes.filterKeys { it in cisSplitParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the checkIndexSigInStatement split parts each carry a real share of the body`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val parts = sizes.filterKeys { it in cisSplitParts }
        assert(parts.size == 7)
        // Measured smallest part: 359 bytecodes (the numeric-name property loop).
        assert(parts.values.min() > 250)
        // ... and the sum must still be the bulk of the original 10,928, i.e. the
        // body was MOVED, not deleted. Measured sum: 9,693.
        assert(parts.values.sum() > 7000)
    }

    /**
     * (JIT.1)(d) round 814 — the ten helpers the `Checker` CONSTRUCTOR was split
     * into. `<init>` was **11,298 bytecodes**; the entry is **5,538**, and that
     * residue is not the pass sequence but the class's **494 property
     * initializers**, which a constructor cannot delegate away. The ten helpers
     * are contiguous runs of the `init` body's ordered `pass("name") { … }`
     * dispatch sequence.
     */
    private val ctorSplitParts = setOf(
        "initSetupPasses",
        "initDeclarationOnlyPasses",
        "initCheckPasses1",
        "initCheckPasses2",
        "initCheckPasses3",
        "initCheckPasses4",
        "initCheckPasses5",
        "initCheckPasses6",
        "initCheckPasses7",
        "initCheckPasses8",
    )

    @Test
    fun `the Checker constructor is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val ctor = sizes["<init>"] ?: fail("<init> not found in Checker: ${sizes.size} methods")
        // Positive control, and it is load-bearing here: Kotlin emits a 72-byte
        // synthetic `<init>` bridge beside the real constructor, so a reader that
        // kept the LAST `<init>` would report 72 and pass this vacuously.
        assert(ctor > 3000)
        assert(ctor < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the Checker constructor split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val missing = ctorSplitParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in Checker: $missing")
        val parts = sizes.filterKeys { it in ctorSplitParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the Checker constructor split parts carry the dispatch sequence`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val parts = sizes.filterKeys { it in ctorSplitParts }
        assert(parts.size == 10)
        // The smallest is `initDeclarationOnlyPasses` at 12 bytecodes — ONE pass
        // dispatch — so this family's usual "every part carries a real share"
        // floor does not apply: the cut criterion was size, and the guard's body
        // is one statement. What must hold is that the EIGHT checking runs are
        // each a substantial slice (measured min 415) and that the whole moved
        // sequence is still there.
        val runs = parts.filterKeys { it.startsWith("initCheckPasses") }
        assert(runs.size == 8)
        assert(runs.values.min() > 300)
        // ... and the sum must still be the bulk of what left `<init>`
        // (11,298 - 5,538 = 5,760). Measured sum: 5,794.
        assert(parts.values.sum() > 4500)
    }

    /**
     * (JIT.1)(e) round 815 — the four runs `applyDirective` was split into. It
     * was **13,694 bytecodes** for 85 `when (key)` arms, i.e. ~160 EACH, because
     * every arm is an `options.copy(...)` on a ~150-field data class and so
     * compiles to a `copy$default` call site carrying the whole argument vector
     * plus the default bitmasks. The size is the arm count times the data
     * class's field count — it says nothing about how much the function does.
     */
    private val applyDirectiveParts = setOf(
        "applyDirectiveArms1",
        "applyDirectiveArms2",
        "applyDirectiveArms3",
        "applyDirectiveArms4",
    )

    @Test
    fun `applyDirective is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.CompilerOptionsKt")
        val fn = sizes["applyDirective"] ?: fail("applyDirective not found in CompilerOptionsKt")
        // Positive control: the entry is a chain of four calls, so it is SMALL —
        // the control that the parse really read this class is the parts below.
        assert(fn > 20)
        assert(fn < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the applyDirective split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.CompilerOptionsKt")
        val missing = applyDirectiveParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in CompilerOptionsKt: $missing")
        val parts = sizes.filterKeys { it in applyDirectiveParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the applyDirective split parts each carry a real share of the arms`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.CompilerOptionsKt")
        val parts = sizes.filterKeys { it in applyDirectiveParts }
        assert(parts.size == 4)
        // Measured smallest run: 2,240 bytecodes (15 arms).
        assert(parts.values.min() > 1500)
        // ... and the sum must still be the bulk of the original 13,694, i.e. the
        // arms were MOVED, not deleted. Measured sum: 13,704 — 99 MORE than the
        // monolith once the 89-bytecode entry is added, which is the family's
        // recurring confirmation that a bytecode count is a THRESHOLD predicate
        // and not a cost model.
        assert(parts.values.sum() > 11000)
    }

    /**
     * (JIT.1)(e) round 816 — the ten helpers `compileParsedCore` was split into.
     * It was **21,535 bytecodes**, and the split is the first in this family
     * whose parts sum to LESS than the monolith: **20,294, i.e. 1,241 FEWER**.
     * The mechanism is worth knowing before writing another one — the monolith's
     * `var options` is captured by the non-inline worker lambdas of the
     * `--workers` branch, so Kotlin boxed it into a `Ref$ObjectRef` and charged
     * **168 `getfield` + `checkcast` pairs**, ~6 bytes each, to reads spread over
     * the whole 1,780-line body. As a helper PARAMETER it is an immutable local
     * again and every one of those reads is a plain `aload`.
     */
    private val compileParsedCoreParts = setOf(
        "cpcCheckDeprecatedOptions",
        "cpcCheckEmitOptionConflicts",
        "cpcCheckModuleAndLibOptions",
        "cpcCheckProjectShapeOptions",
        "cpcCompileSingleFile",
        "cpcCompileMultiFile",
        "cpcScanFiles",
        "cpcBindAndCheck",
        "cpcTransformAndEmit",
        "cpcRequireOnlyOrphans",
    )

    @Test
    fun `compileParsedCore is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.TypeScriptCompiler")
        val fn = sizes["compileParsedCore"]
            ?: fail("compileParsedCore not found in TypeScriptCompiler")
        // Positive control: the entry is the option head plus ten calls, so it is
        // SMALL — that the parse really read this class is pinned by the parts below.
        assert(fn > 100)
        assert(fn < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the compileParsedCore split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.TypeScriptCompiler")
        val missing = compileParsedCoreParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in TypeScriptCompiler: $missing")
        val parts = sizes.filterKeys { it in compileParsedCoreParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the compileParsedCore split parts each carry a real share of the pipeline`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.TypeScriptCompiler")
        val parts = sizes.filterKeys { it in compileParsedCoreParts }
        assert(parts.size == 10)
        // Measured smallest part: 595 bytecodes (`cpcRequireOnlyOrphans`).
        assert(parts.values.min() > 400)
        // Measured largest: 5,111 (`cpcCompileMultiFile`), i.e. real headroom.
        assert(parts.values.max() < 6500)
        // ... and the sum must still be the bulk of the original 21,535 — the runs
        // were MOVED, not deleted. Measured sum: 20,001 (20,294 with the entry).
        assert(parts.values.sum() > 18000)
    }

    /**
     * (JIT.1)(e) round 817 — the seven helpers `Transformer.transform` was split
     * into. It was **8,934 bytecodes** and is the first target in the arc on the
     * EMIT path, so every `--noEmit` A/B in this arc is blind to it and its
     * behavioural gate is the corpus suite's emit baselines plus
     * `TransformSplitTest`.
     */
    private val transformParts = setOf(
        "tfCollectTopLevelNames",
        "tfCollectHelperStatements",
        "tfLiftLeadingComments",
        "tfInjectTslibImport",
        "tfElideInternalImportAliases",
        "tfWrapNoLibMetadataArgs",
        "tfInjectCreateRequireHeader",
    )

    @Test
    fun `Transformer transform is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Transformer")
        val fn = sizes["transform"] ?: fail("transform not found in Transformer")
        // Positive control: the entry still carries the per-file state reset, the
        // stage plumbing and the two module-format branches, so it is far from
        // empty — a zero cannot pass this vacuously.
        assert(fn > 1500)
        assert(fn < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the Transformer transform split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Transformer")
        val missing = transformParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in Transformer: $missing")
        val parts = sizes.filterKeys { it in transformParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the Transformer transform split parts each carry a real share of the pipeline`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Transformer")
        val parts = sizes.filterKeys { it in transformParts }
        assert(parts.size == 7)
        // Region sizes were MEASURED before the edit with
        // scripts/method_bytes_by_line.py: 1,272 / 1,050 / 550 / 1,071 / 283 /
        // 275 / 389 bytecodes of the 8,934, i.e. 4,890 moved.
        assert(parts.values.min() > 200)
        assert(parts.values.sum() > 4000)
    }

    /**
     * (JIT.1)(e) round 818 — the nine helpers `Transformer.transformClassBody`
     * was split into. It was **16,233 bytecodes**, 2.0x the limit and the second
     * largest method in the compiler. Like round 817's target it is on the EMIT
     * path, so its behavioural gate is the corpus suite's emit baselines plus
     * `TransformClassBodySplitTest` — no `--noEmit` A/B can see it at all.
     */
    private val classBodyParts = setOf(
        "tcbLowerAutoAccessors",
        "tcbExtractComputedPropertyKeys",
        "tcbAllocatePrivateState",
        "tcbBuildInstanceInitializers",
        "tcbBuildTransformedConstructor",
        "tcbBuildOutputMembers",
        "tcbCaptureClassAlias",
        "tcbEmitAliasAndPrivateState",
        "tcbEmitStaticFieldTrailing",
    )

    @Test
    fun `Transformer transformClassBody is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Transformer")
        val fn = sizes["transformClassBody"] ?: fail("transformClassBody not found in Transformer")
        // Positive control: the entry still carries the member categorisation, the
        // private-field downlevel, the private environment push and the whole
        // trailing-statement tail, so it is far from empty — a zero cannot pass
        // this vacuously.
        assert(fn > 2000)
        assert(fn < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the transformClassBody split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Transformer")
        val missing = classBodyParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in Transformer: $missing")
        val parts = sizes.filterKeys { it in classBodyParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the transformClassBody split parts each carry a real share of the body`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Transformer")
        val parts = sizes.filterKeys { it in classBodyParts }
        assert(parts.size == 9)
        // Region sizes were MEASURED before the edit with
        // scripts/method_bytes_by_line.py: 584 / 1,112 / 1,832 / 1,161 / 1,338 /
        // 1,677 / 1,259 / 1,264 / 979 bytecodes of the 16,233, i.e. 11,206 moved.
        assert(parts.values.min() > 400)
        assert(parts.values.sum() > 9000)
    }

    /**
     * (JIT.1)(e) round 819 — the nineteen helpers `Transformer.transformToCommonJS`
     * was split into. It was **28,991 bytecodes**, 3.6x the limit and the LARGEST
     * method in the compiler. Two of them — the `VariableStatement` and
     * `ImportDeclaration` arms — carry a ONE-ITERATION FRAME, because six
     * `continue`s in them targeted the caller's loop; see
     * `scripts/tcjs_split_apply.py` and `TransformToCommonJsSplitTest`.
     */
    private val commonJsParts = setOf(
        "tcjsDetectModuleShape",
        "tcjsCollectDeclaredNames",
        "tcjsCollectNamespaceExports",
        "tcjsCollectExportClauses",
        "tcjsSplitPrologueDirectives",
        "tcjsTransformVariableStatement",
        "tcjsTransformFunctionDeclaration",
        "tcjsTransformClassDeclaration",
        "tcjsTransformExportAssignment",
        "tcjsTransformImportDeclaration",
        "tcjsTransformExportDeclaration",
        "tcjsTransformOtherStatement",
        "tcjsExtractEarlyPrePreamble",
        "tcjsPrependHoistedVars",
        "tcjsRewriteExportMutations",
        "tcjsCollectInternalAliasNames",
        "tcjsElideUnusedImports",
        "tcjsMoveDetachedHeaderComments",
        "tcjsInsertHelpersAndPrologue",
    )

    @Test
    fun `Transformer transformToCommonJS is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Transformer")
        val fn = sizes["transformToCommonJS"] ?: fail("transformToCommonJS not found in Transformer")
        // Positive control: the entry still holds the ~65 collection declarations,
        // the main `when` dispatch, the dynamic-import rewrite, the re-export
        // placement, the default-export reordering and the void0 chain, so it is
        // far from empty — a zero cannot pass this vacuously.
        assert(fn > 1500)
        assert(fn < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the transformToCommonJS split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Transformer")
        val missing = commonJsParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in Transformer: $missing")
        val parts = sizes.filterKeys { it in commonJsParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the transformToCommonJS split parts each carry a real share of the body`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Transformer")
        val parts = sizes.filterKeys { it in commonJsParts }
        assert(parts.size == 19)
        // Region sizes were MEASURED before the edit with
        // scripts/method_bytes_by_line.py: 1,383 / 2,285 / 1,182 / 923 / 322 /
        // 4,451 / 449 / 425 / 415 / 2,756 / 2,372 / 745 / 738 / 757 / 913 /
        // 1,224 / 2,749 / 700 / 1,775 bytecodes of the 28,991, i.e. 26,564 moved.
        assert(parts.values.min() > 300)
        assert(parts.values.sum() > 24000)
    }

    /**
     * (JIT.1)(e) round 820 — the seven top-level builders `Checker.<clinit>`'s
     * companion constants were hoisted into. They live in `CheckerKt` (a Kotlin
     * file class), not in `Checker`: a companion `private fun` would be an
     * instance method on `Checker$Companion`, which `<clinit>` would have to
     * reach through the very static field it is in the middle of installing.
     */
    private val clinitHoistParts = setOf(
        "ckConstNodeBuiltinModules",
        "ckConstKeywordIdentifiers",
        "ckConstKnownGlobals",
        "ckConstDomGlobalNames",
        "ckConstValueOnlyGlobals",
        "ckConstKnownGenericBuiltins",
        "ckConstLibMinTargetBase",
    )

    @Test
    fun `Checker's static initializer is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val clinit = sizes["<clinit>"] ?: fail("<clinit> not found in Checker")
        // Positive control: the companion still initialises ~270 other
        // properties, so the initializer is far from empty and a zero — which is
        // what a mis-parse of the class file would produce — cannot pass this.
        assert(clinit > 1500)
        assert(clinit < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every hoisted companion-constant builder is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.CheckerKt")
        val missing = clinitHoistParts - sizes.keys
        if (missing.isNotEmpty()) fail("hoisted builders not found in CheckerKt: $missing")
        val parts = sizes.filterKeys { it in clinitHoistParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the hoisted builders each carry a real share of the initializer`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.CheckerKt")
        val parts = sizes.filterKeys { it in clinitHoistParts }
        assert(parts.size == 7)
        // Region sizes were MEASURED before the edit with
        // scripts/clinit_split_analyze.py: 2,992 / 1,368 / 787 / 671 / 553 / 497 /
        // 371 bytecodes of the 10,339, i.e. 7,239 moved. The smallest is
        // NODE_BUILTIN_MODULES' 52 strings.
        assert(parts.values.min() > 250)
        assert(parts.values.sum() > 6000)
    }

    /**
     * (JIT.1)(e) round 821 — the three helpers
     * `Checker.tryInferSingleTypeParamFromArgs` was split into, **and the last
     * entry this census ever had**. It was **11,930 bytecodes** and it is the only
     * target in the arc whose boundaries no contiguity argument could choose: its
     * bytecodes are FLAT (largest 25-line window 449 of 11,930, 22% of them
     * INLINED stdlib bodies charged to their call sites) and one
     * `for (tp in orderedTps)` loop holds essentially everything. The seams came
     * from a scripted DATA-FLOW analysis — `scripts/tisp_split_analyze.py` — and
     * the behavioural gate is `TispSplitTest`.
     */
    private val tispSplitParts = setOf(
        "tispGatherAnchorCandidates",
        "tispGatherCallbackCandidates",
        "tispCheckConstraint",
    )

    @Test
    fun `tryInferSingleTypeParamFromArgs is below HotSpot's HugeMethodLimit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val fn = sizes["tryInferSingleTypeParamFromArgs"]
            ?: fail("tryInferSingleTypeParamFromArgs not found in Checker")
        // Positive control: the entry still holds the parameter-shape gate, the
        // anchor ordering, the candidate selection and the whole conflict leg (it
        // is 1,869 bytecodes after the split), so a zero cannot pass this
        // vacuously.
        assert(fn > 800)
        assert(fn < HUGE_METHOD_LIMIT)
    }

    @Test
    fun `every part of the tryInferSingleTypeParamFromArgs split is below the limit`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val missing = tispSplitParts - sizes.keys
        if (missing.isNotEmpty()) fail("split parts not found in Checker: $missing")
        val parts = sizes.filterKeys { it in tispSplitParts }
        val over = parts.filterValues { it >= HUGE_METHOD_LIMIT }
        if (over.isNotEmpty()) fail("over HotSpot's HugeMethodLimit: $over")
    }

    @Test
    fun `the tryInferSingleTypeParamFromArgs split parts each carry a real share of the loop`() {
        val sizes = codeSizes("com.xemantic.typescript.compiler.Checker")
        val parts = sizes.filterKeys { it in tispSplitParts }
        assert(parts.size == 3)
        // Region sizes were MEASURED before the edit with the wrap-corrected
        // per-line attribution in scripts/tisp_split_analyze.py --bytes:
        // 3,109 / 5,470 / 1,566 bytecodes of the 11,930, i.e. 10,145 moved.
        assert(parts.values.min() > 1000)
        // ... and the largest helper must keep real headroom under the limit
        // (measured 5,388), because this function accretes inference rules
        // continuously and pass 2 is where most of them land.
        assert(parts.values.max() < 6500)
        assert(parts.values.sum() > 8000)
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
                        // (JIT.1)(d) round 814: a NAME can repeat — Kotlin emits a
                        // synthetic `<init>` bridge for default arguments beside the
                        // real constructor, and overloads share a simple name. Keep the
                        // LARGEST, which is the one HotSpot's limit would bite; a
                        // last-wins map reported the 72-byte bridge as `<init>` and
                        // would have passed the constructor's limit pin vacuously.
                        val len = u4()
                        out[name] = maxOf(out[name] ?: 0, len)
                        p = end
                    } else skip(attrLen)
                }
            }
            return out
        }
    }
}
