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
import java.lang.reflect.Modifier
import kotlin.test.Test

/**
 * (SERVE.1): **THE MECHANISM PIN** — parsing every documented CLI flag and then
 * restoring must leave the process-global mode objects bit-for-bit as they were.
 *
 * ## What it is really guarding
 *
 * Round 843 fixed the five instrumentation flags then known to leak, by hand, in
 * a block at the end of `runCli`. Six more were already leaking and were not in
 * that block — `CpaSections.verify*`, `FlowScan.legacy`/`eagerSet`,
 * `IanySections.*GateOff`, `ArgNarrowGate.mode`, `ParallelCheckMode.workers`,
 * `PartitionCheck.workers` — and two more (`SpineDispatch.mode == GATED`,
 * `CallSections.preGateProbe`) were leaking without anyone having noticed at all.
 * A hand-maintained restore list is a second list that must be kept in step with
 * the argument loop by hand, and that is exactly the failure. So the guard here
 * is not "these N flags are restored"; it is **"whatever the argument loop
 * touched, the ledger put back"**, checked by JVM reflection over every declared
 * field of every mode object. A NEW flag that writes a mode field directly fails
 * this test the day it is added.
 *
 * ## Why the comparison is trustworthy
 *
 * The state is normalized with each object's own `reset()` before the snapshot,
 * so counters start at their canonical values and cannot drift under test
 * ordering. **No `reset()` in this codebase clears a MODE field** — they clear
 * counters, arrays and transient cursors — with exactly one exception,
 * `PartitionCheck.reset()`, which clears `workers`; that one is deliberately NOT
 * called here, because normalizing it would mask a broken `--partitionCheck`
 * restore.
 *
 * ## Why it cannot pass vacuously
 *
 * Three separate ways:
 * - the sweep must leave the ledger holding writes ([ModeLedger.pending] > 0);
 * - the parsed state must DIFFER from the snapshot before the restore — a test
 *   that drove nothing would satisfy "equal afterwards" trivially;
 * - and every mode in [BEHAVIOUR_CHANGING] must be among the fields that moved,
 *   so dropping a flag from the sweep fails here rather than silently narrowing
 *   the pin. Those are the modes that change what the compiler DECIDES rather
 *   than merely what it measures, which is why the leak is a correctness bug.
 */
class CliModeRestoreTest {

    private companion object {

        /**
         * Every process-global object whose fields the CLI argument loop writes.
         *
         * The list is of OBJECTS, not fields — the fields are enumerated
         * reflectively, which is what makes a newly added flag covered without
         * anybody remembering to extend anything here.
         */
        val MODE_OBJECTS = listOf(
            PassTiming::class.java,
            FltmCensus::class.java,
            GlobalsAmp::class.java,
            SpineAmp::class.java,
            SpineDispatch::class.java,
            SpineSections::class.java,
            CallSections::class.java,
            ArgSections::class.java,
            ArgNarrowGate::class.java,
            NarrowSections::class.java,
            CtaSections::class.java,
            IanySections::class.java,
            CpaSections::class.java,
            FrontEnd::class.java,
            CrawlParseCache::class.java,
            TavGate::class.java,
            SpineMask::class.java,
            FlowScan::class.java,
            FlowIndex::class.java,
            PartitionCheck::class.java,
            ParallelCheckMode::class.java,
            FlowCensus::class.java,
            SrcScan::class.java,
            MapCensus::class.java,
            IterCensus::class.java,
            ReachMemoCensus::class.java,
            MergeCensus::class.java,
            BindMutationCheck::class.java,
            ShareBind::class.java,
            MergeClone::class.java,
            NodeAnswers::class.java,
        )

        /**
         * The modes that change what the compiler DECIDES, not just what it
         * records — the reason (SERVE.1) is a correctness item and not a
         * performance one. Each selects a different code path: a legacy scanner,
         * a pre-gate arm restored, a gate disabled, a whole parallel driver.
         */
        val BEHAVIOUR_CHANGING = listOf(
            "ParallelCheckMode.workers",
            "ShareBind.enabled",
            "MergeClone.enabled",
            "PartitionCheck.workers",
            "SpineDispatch.mode",
            "ArgNarrowGate.mode",
            "FlowScan.legacy",
            "FlowIndex.legacy",
            "FlowScan.eagerSet",
            "IanySections.gateOff",
            "IanySections.armGateOff",
            "IanySections.argGateOff",
            "CpaSections.verifyLoopRetry",
            "CpaSections.verifyUnionRetry",
            "CpaSections.verifyDeferSuppression",
            "CallSections.verifyImplRelated",
            "TavGate.off",
            "SrcScan.off",
            "SrcScan.bogus",
            "SpineMask.off",
        )
    }

    /**
     * Puts the counter state at its canonical value so the snapshot is stable
     * under any test ordering. See the class KDoc for why `PartitionCheck.reset`
     * is the one omission.
     */
    private fun normalizeCounters() {
        PassTiming.reset()
        FltmCensus.reset()
        GlobalsAmp.reset()
        SpineAmp.reset()
        SpineDispatch.reset()
        SpineSections.reset()
        CallSections.reset()
        ArgSections.reset()
        ArgNarrowGate.reset()
        NarrowSections.reset()
        CtaSections.reset()
        IanySections.reset()
        CpaSections.reset()
        FrontEnd.reset()
        CrawlParseCache.reset()
        TavGate.reset()
        SpineMask.reset()
        FlowScan.reset()
        FlowCensus.reset()
        SrcScan.reset()
        MapCensus.reset()
        ReachMemoCensus.reset()
        MergeCensus.reset()
        BindMutationCheck.reset()
        ShareBind.reset()
        MergeClone.reset()
        PartitionCheck.reportLines.clear()
    }

    private fun render(v: Any?): String = when (v) {
        null -> "null"
        is LongArray -> "LongArray#" + v.contentHashCode()
        is IntArray -> "IntArray#" + v.contentHashCode()
        is BooleanArray -> "BooleanArray#" + v.contentHashCode()
        is Array<*> -> "Array#" + v.contentDeepHashCode()
        is Collection<*> -> "Collection#" + v.size
        is Map<*, *> -> "Map#" + v.size
        is Function<*> -> "fn"
        else -> v.toString()
    }

    /** `Object.field` -> rendered value, for every mutable field of every mode object. */
    private fun snapshot(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (cls in MODE_OBJECTS) {
            val instance = cls.getDeclaredField("INSTANCE").get(null)
            for (f in cls.declaredFields) {
                if (f.name == "INSTANCE" || f.isSynthetic) continue
                // `const val` / `val` compile to final fields and cannot be a mode.
                if (Modifier.isFinal(f.modifiers)) continue
                f.isAccessible = true
                val owner = if (Modifier.isStatic(f.modifiers)) null else instance
                out["${cls.simpleName}.${f.name}"] = render(f.get(owner))
            }
        }
        return out
    }

    private fun differences(before: Map<String, String>, after: Map<String, String>): List<String> =
        (before.keys + after.keys).sorted().mapNotNull { k ->
            val b = before[k]
            val a = after[k]
            if (b == a) null else "$k: $b -> $a"
        }

    /**
     * Every `--flag` token the usage text documents, which is the CLI's own
     * registry of what exists. Each is followed by `"2"` so the flags that
     * consume a value get one; for the rest that token is just a positional
     * argument, and nothing here ever compiles a project.
     *
     * `--help` is excluded because it stops the parse.
     */
    private fun sweepArgs(): Array<String> {
        val flags = Regex("--[A-Za-z]+").findAll(usageText())
            .map { it.value }
            .distinct()
            .filter { it != "--help" }
            .toList()
        assert(flags.size > 40)
        return flags.flatMap { listOf(it, "2") }.toTypedArray()
    }

    @Test
    fun `parsing every documented flag and restoring leaves every mode object untouched`() {
        normalizeCounters()
        val before = snapshot()
        val ledger = ModeLedger()
        parseCliArgs(sweepArgs(), ledger)

        // Non-vacuity 1: the sweep really wrote modes.
        assert(ledger.pending > 20)
        // Non-vacuity 2: and they really are visible in the objects.
        val moved = differences(before, snapshot()).map { it.substringBefore(":") }
        assert(moved.size > 20)
        // Non-vacuity 3: and the sweep reached every mode that changes a DECISION.
        val unreached = BEHAVIOUR_CHANGING.filter { it !in moved }
        assert(unreached.isEmpty())

        ledger.restore()
        // `--spineSections` runs 200 calibration rounds, which move counters that
        // no argument set and the ledger is right not to own; re-normalizing
        // cannot mask a mode, because no reset() clears one (and the one that
        // would, PartitionCheck's, is not called).
        normalizeCounters()
        val leaked = differences(before, snapshot())
        assert(leaked.isEmpty())
    }

    @Test
    fun `the usage text documents every flag the argument loop accepts`() {
        // The sweep can only cover flags the usage text names, so an undocumented
        // flag is an uncovered flag. Aliases (the all-lowercase spellings) are
        // deliberately not documented and are excluded here.
        val documented = Regex("--[A-Za-z]+").findAll(usageText()).map { it.value }.toSet()
        val undocumented = ACCEPTED_FLAGS.filter { it !in documented }
        assert(undocumented.isEmpty())
    }
}

/**
 * The canonical spelling of every flag `parseCliArgs` accepts.
 *
 * Hand-maintained on purpose and checked against the usage text above: it is a
 * second reading of the same list, so a flag added to the parser and to neither
 * of these two places is a flag nobody documented and nobody swept.
 */
private val ACCEPTED_FLAGS = listOf(
    "--noEmit", "--watch", "--incremental", "--watchVerify", "--listAll",
    "--passTiming", "--passTimingRows", "--passTimingSpine", "--verifyMappedCache",
    "--fltmCensus", "--typeOfExprCallers", "--dispatchProbe", "--dispatchGated",
    "--spineSections", "--callSections", "--argSections", "--argSectionsCoarse",
    "--argNarrowCensus", "--verifyArgNarrowGate", "--argNarrowGate", "--argNarrowGateOff",
    "--narrowSections", "--narrowSectionsCoarse", "--narrowSectionsDeep",
    "--ctaSections", "--ctaSectionsCoarse", "--ianySections", "--ianyGateOff",
    "--ianyArmGateOff", "--ianyArgGateOff", "--cpaSections", "--cpaSectionsCoarse",
    "--cpaSectionsCensus", "--verifyLoopRetry", "--verifyLoopRetryAll",
    "--verifyUnionRetry", "--verifyUnionRetryAll", "--verifyDeferSuppression",
    "--verifyDeferSuppressionBogus", "--cmamPreGate", "--cmamPreGateBogus",
    "--verifyImplRelated", "--verifyImplRelatedAll", "--verifyImplRelatedBogus",
    "--ccetPreGate", "--ccetPreGateBogus", "--frontEnd", "--parseAmp", "--parseCacheOff",
    "--tavOff", "--tavGateOff", "--spineMaskOff", "--flowScanLegacy",
    "--verifyFlowScan", "--flowScanBogus", "--flowEagerSet", "--flowIndexLegacy",
    "--flowCensus", "--srcScanCensus", "--srcScanFilterOff", "--verifySrcScan", "--srcScanBogus",
    "--mapCensus", "--perFileScopeAmp", "--flowMapReplay", "--lexLevelAmp",
    "--typeNodeKeyCensus", "--typeNodeKeyAmp", "--boxedKeyCensus", "--boxedKeyAmp",
    "--iterCensus", "--iterAmp",
    "--reachMemoCensus", "--nodeAnswers",
    "--mergeCensus", "--bindMutationCheck", "--shareBind", "--mergeClone", "--partitionCheck",
    "--workers", "--globalsAmp", "--spineAmp", "--outDir", "--project", "--help",
)
