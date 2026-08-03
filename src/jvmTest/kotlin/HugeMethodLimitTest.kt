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
 * at **13,694** (round 815 split it).
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
