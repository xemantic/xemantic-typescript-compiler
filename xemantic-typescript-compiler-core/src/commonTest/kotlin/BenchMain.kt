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

// NOT `com.xemantic.typescript.compiler` — this file declares a top-level
// `main(Array<String>)`, and so does `src/commonMain/kotlin/Main.kt`. On the JVM those
// are two distinct classes (`MainKt` / `BenchMainKt`) and coexist happily; Kotlin/Native
// mangles a top-level function to `kfun:<package>#main(kotlin.Array<kotlin.String>){}`
// with NO file component, so in one package they are ONE symbol — and the test binary
// links the main klib together with the test klib, which makes it a hard `ld.lld:
// duplicate symbol` at link time (round 775). Only the native TEST link can see this:
// the main executable link never pulls in commonTest. Keep any future commonTest
// entry point out of the compiler's own package.
package com.xemantic.typescript.compiler.bench

import com.xemantic.typescript.compiler.ArgSections
import com.xemantic.typescript.compiler.CallSections
import com.xemantic.typescript.compiler.CpaSections
import com.xemantic.typescript.compiler.CtaSections
import com.xemantic.typescript.compiler.LibTypeCensus
import com.xemantic.typescript.compiler.PassTiming
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SpineDispatch
import com.xemantic.typescript.compiler.SystemVfs
import kotlin.time.measureTimedValue

/**
 * Warm in-process whole-project benchmark entry point (NOT a test — carries no
 * `@Test` methods, so `jvmTest` runs are unaffected). Each iteration performs a
 * complete rebuild: tsconfig load, glob discovery, module resolution, parse,
 * bind and check.
 *
 * This runs **check-only**: `noEmit = true` reaches `ProjectCompiler`'s
 * `skipEmitOutputs`, so since round 738 nothing is transformed or emitted at all
 * (the earlier "emit-to-memory, only the disk writes are skipped" note here
 * predates that gate and was stale). The number is therefore directly comparable
 * to the arc's `--noEmit` figures — `ab-interleaved.sh` and `cost_gate.py` — and
 * NOT to the emit-mode CI ratio in `bench-history/`.
 *
 * **This is the only harness that measures a WARM compile.** `bench-compile-tsc.sh`
 * forks a fresh JVM per run, so its `--warmup N` warms the page cache and never
 * the JIT; every archived CI row is a cold single-shot.
 *
 * ## The WARM per-pass table (opt-in 4th argument)
 *
 * Every `--passTiming` attribution table in `docs/perf` was produced by a COLD
 * one-shot `MainKt` JVM, so every per-pass row on record prices interpreted or
 * half-compiled code. Passing `passTiming` as the 4th argument makes this
 * harness run ONE ADDITIONAL instrumented rebuild **after** the measured loop
 * and dump [PassTiming]'s table from it — i.e. attribution over a fully
 * JIT-compiled compiler, which no other harness can produce.
 *
 * Two properties make that number trustworthy, and a change here must keep
 * both:
 *
 *  1. **The probe never runs inside a measured iteration.** The instrumentation
 *     is not free (round 733 measured the probe alone moving `checkSpine` by
 *     +29 ms, and the section probes cost far more), so the reported `medianMs`
 *     must stay probe-free. The instrumented rebuild prints its OWN wall ms on
 *     a separate `instrumented` line, which is what prices the probe against
 *     the uninstrumented median rather than hiding inside it.
 *  2. **It self-falsifies like every other iteration.** An instrumented rebuild
 *     that answers a different program is not a measurement of this one, so its
 *     `files`/`errors` are compared against the measured iterations and a
 *     disagreement aborts with a marked line and a non-zero exit.
 *
 * Usage:
 * ```
 * java -cp <test-classpath> com.xemantic.typescript.compiler.bench.BenchMainKt \
 *     <projectDir> <warmupIters> <measuredIters> [passTiming|<tier>[,<tier>...]]
 * ```
 *
 * Since round 846 the 4th argument may instead be a comma-separated list of
 * probe TIERS (`rows` | `spine` | `full`), one instrumented rebuild each, in
 * the given order — `rows,full,rows,full` measures the SAME warm code at ~513
 * probe boundaries and at ~2 M, twice each, in one process. `full − rows` is
 * then the probe's own price, differentially, with nothing else varying; and
 * the `rows` table's absolutes are the ones a warm lever can be sized against.
 * `passTiming` remains an exact alias for `full`.
 *
 * Round 847 adds a fourth tier name, `dispatch`, which is NOT a `PassTiming`
 * tier at all: it leaves the pass probe off and runs `SpineDispatch.PROBE`
 * (round 732) for one rebuild, printing the per-handler x per-kind report and
 * its CSV. That is the only instrument that can attribute `checkSpine` — 66% of
 * the warm artifact — below the enter/leave split, and until now it had only
 * ever been run in a COLD one-shot JVM.
 * Prints one JSON object per measured iteration, then a `summary` line — and,
 * with the 4th argument present, an `instrumented` line followed by the INV.0
 * pass-timing table. The 4th argument is OFF unless it is `passTiming` / `true`
 * / `1`; omitting it leaves this harness behaving exactly as it did before the
 * argument existed (this is what `scripts/ab-warm.sh` invokes, and its parser
 * reads only the `iter` lines).
 */
/**
 * The probe tiers this harness understands as its 4th argument.
 *
 * `rows` / `spine` / `full` are [PassTiming]'s three tiers (round 846) and
 * `dispatch` is round 847's per-handler [SpineDispatch] probe. Round 849 adds
 * the four INTRA-HANDLER probes that until now had only ever been run in a COLD
 * one-shot JVM — `cta` (`spineCtaM3StatementAnchor`, 17.7% of the warm artifact),
 * `cpa` (`cpaSpineLeave`, 12.8%) and `arg` (`checkArgumentsAgainstSignature`,
 * reached from the largest handler `ccetSpineLeave`) — each with its `*coarse`
 * counterpart, because round 734's law is that a probe boundary may only be
 * priced by an ON-vs-COARSE DIFFERENTIAL and never by an empty-span loop. Round
 * 847 measured that a probe boundary is ~1.85x more expensive cold than warm, so
 * every cold section table on record needs its own warm calibration before its
 * rows can be read as warm shares. `libtypes` is round 849's (WARM.3) census.
 */
internal val TIERS = listOf(
    "rows", "spine", "full", "dispatch",
    "cta", "ctacoarse", "cpa", "cpacoarse", "arg", "argcoarse", "libtypes",
    // (WARM.5) round 851 — the LAST unprobed region of the warm top four:
    // `checkSingleCallExpressionTypes`, i.e. the ~60% of `ccetSpineLeave` that
    // `arg` does not reach (callee resolution, overload selection, the round-793
    // prologue).
    "call", "callcoarse",
)

/**
 * Arm the probe a tier names, and zero its counters.
 *
 * Split out of [main] so the ORDER of arm → measure → **report** → disarm is a
 * property a test can pin. Round 850 found the pre-851 code disarming before
 * dumping, which made every `*coarse` report print `mode: ON` — the data was
 * unaffected but the label was always wrong, and a label is what a reader
 * classifies an arm by.
 */
internal fun tierBegin(tier: String) {
    PassTiming.enabled = false
    PassTiming.reset()
    when (tier) {
        "dispatch" -> { SpineDispatch.reset(); SpineDispatch.mode = SpineDispatch.PROBE }
        "cta" -> { CtaSections.reset(); CtaSections.mode = CtaSections.ON }
        "ctacoarse" -> { CtaSections.reset(); CtaSections.mode = CtaSections.COARSE }
        "cpa" -> { CpaSections.reset(); CpaSections.mode = CpaSections.ON }
        "cpacoarse" -> { CpaSections.reset(); CpaSections.mode = CpaSections.COARSE }
        "arg" -> { ArgSections.reset(); ArgSections.mode = ArgSections.ON }
        "argcoarse" -> { ArgSections.reset(); ArgSections.mode = ArgSections.COARSE }
        "call" -> { CallSections.reset(); CallSections.mode = CallSections.ON }
        "callcoarse" -> { CallSections.reset(); CallSections.mode = CallSections.COARSE }
        "libtypes" -> { LibTypeCensus.reset(); LibTypeCensus.enabled = true }
        else -> {
            PassTiming.detail = tier == "full"
            PassTiming.spineDetail = tier != "rows"
            PassTiming.enabled = true
        }
    }
}

/**
 * The tier's report text — produced while the probe is still armed, so a
 * `report()` that labels itself from its own `mode` labels itself correctly.
 */
internal fun tierReport(tier: String): String = when (tier) {
    "dispatch" -> SpineDispatch.report() + "\n== (DISPATCH.1) csv ==\n" + SpineDispatch.csv()
    "cta", "ctacoarse" ->
        CtaSections.report() + "\n== (TYPE.2) csv ==\n" + CtaSections.csv() + "== (TYPE.2) csv end =="
    "cpa", "cpacoarse" ->
        CpaSections.report() + "\n== (ENGINE.2) csv ==\n" + CpaSections.csv() + "== (ENGINE.2) csv end =="
    "arg", "argcoarse" ->
        ArgSections.report() + "\n== (CALL.2) csv ==\n" + ArgSections.csv() + "== (CALL.2) csv end =="
    "call", "callcoarse" ->
        CallSections.report() + "\n== (CALL.1) csv ==\n" + CallSections.csv() + "== (CALL.1) csv end =="
    "libtypes" -> LibTypeCensus.report()
    // The pass probe is disarmed BEFORE its dump, exactly as it was pre-851 —
    // only the section probes need to stay armed through their report, and only
    // because each labels its arm from its own `mode`.
    else -> buildString { PassTiming.enabled = false; PassTiming.dump { appendLine(it) } }
}

/** Disarm every probe and release its counters. Safe to call for any tier. */
internal fun tierStop() {
    PassTiming.enabled = false
    SpineDispatch.mode = SpineDispatch.OFF
    CtaSections.mode = CtaSections.OFF
    CpaSections.mode = CpaSections.OFF
    ArgSections.mode = ArgSections.OFF
    CallSections.mode = CallSections.OFF
    LibTypeCensus.enabled = false
    SpineDispatch.reset()
    CtaSections.reset()
    CpaSections.reset()
    ArgSections.reset()
    CallSections.reset()
    LibTypeCensus.reset()
}

fun main(args: Array<String>) {
    val project = args.getOrNull(0)
        ?: error("usage: BenchMainKt <projectDir> <warmup> <iters> [passTiming]")
    val warmup = args.getOrNull(1)?.toIntOrNull() ?: 3
    val iters = args.getOrNull(2)?.toIntOrNull() ?: 10
    // Opt-in, and deliberately NOT a "any 4th argument means yes" test: a typo
    // would then silently buy a probe-contaminated extra rebuild.
    //
    // (WARM.1)(c) round 846: the argument is a COMMA-SEPARATED LIST of probe
    // TIERS, each of which runs its own instrumented rebuild after the measured
    // loop. `passTiming` is `full` (the pre-846 behaviour, exactly). A list such
    // as `rows,full,rows,full` is the DIFFERENTIAL the tiers exist for: the SAME
    // code measured at ~513 boundaries and at ~2 M, twice each, inside ONE warm
    // process, so `full − rows` is the probe's own price with nothing else
    // varying (round 734's law — never an empty-span loop).
    val tiers: List<String> = when (val flag = args.getOrNull(3)?.lowercase()) {
        null, "", "false", "0", "off" -> emptyList()
        "passtiming", "true", "1", "on" -> listOf("full")
        else -> flag.split(",").map { it.trim() }.filter { it.isNotEmpty() }.also { list ->
            val bad = list.filter { it !in TIERS }
            if (list.isEmpty() || bad.isNotEmpty()) {
                error(
                    "usage: 4th argument must be `passTiming`, omitted, or a comma-separated " +
                        "list of tiers (${TIERS.joinToString("|")}) — not '$flag'"
                )
            }
        }
    }
    val instrumented = tiers.isNotEmpty()

    repeat(warmup) {
        ProjectCompiler(SystemVfs).build(project, noEmit = true)
    }

    val times = mutableListOf<Double>()
    var files = 0
    var errors = 0
    // The measured loop's own answer, for the instrumented rebuild to be held
    // against. Recorded from the FIRST iteration; `measuredDrift` remembers
    // whether the loop was even self-consistent, since an instrumented rebuild
    // cannot be validated against a reference that already moved.
    var refFiles = -1
    var refErrors = -1
    var measuredDrift = false
    repeat(iters) { i ->
        val (result, duration) = measureTimedValue {
            ProjectCompiler(SystemVfs).build(project, noEmit = true)
        }
        val ms = duration.inWholeMicroseconds / 1000.0
        times.add(ms)
        files = result.programFiles.size
        errors = result.errorCount
        if (refFiles < 0) {
            refFiles = files; refErrors = errors
        } else if (files != refFiles || errors != refErrors) {
            measuredDrift = true
        }
        // The probe's own falsification: an in-process rebuild shares whatever state
        // the pipeline does not reset (id counters, interning caches, the Vfs object),
        // so a WARM number is only a measurement while every iteration still answers
        // the SAME program. A drifting errors/files column means state is leaking and
        // the timings below it measure a different compile, not a faster one.
        println("""{"iter":$i,"ms":$ms,"files":${result.programFiles.size},"errors":${result.errorCount}}""")
    }

    val sorted = times.sorted()
    val median = sorted[sorted.size / 2]
    println(
        """{"summary":true,"project":"$project","files":$files,"errors":$errors,""" +
            """"warmup":$warmup,"iters":$iters,"medianMs":$median,"minMs":${sorted.first()},"maxMs":${sorted.last()}}"""
    )

    if (!instrumented) return

    // --- the WARM per-pass table --------------------------------------------
    // AFTER the summary, so nothing above this line has paid for a probe. The
    // enabled=false/reset()/enabled=true sequence is deliberate: `reset()` does
    // NOT clear `enabled` (nor `censusMode`/`disabledPasses`), so clearing it
    // first is what guarantees the counters start from zero even if some
    // earlier code in this process had the instrumentation on.
    for ((run, tier) in tiers.withIndex()) {
        // (WARM.4) round 847 — the `dispatch` tier is NOT a PassTiming tier: it
        // leaves the pass probe entirely OFF and runs the round-732
        // `SpineDispatch` PROBE instead, which is the only instrument that
        // attributes `checkSpine` PER HANDLER x PER KIND. Every warm row in
        // `docs/perf/dispatch-table.md` was a COLD one-shot `MainKt` run; this
        // makes the same table takeable inside a JIT-warm process, and — because
        // the probe's own code is cold on its first instrumented rebuild exactly
        // as round 846 measured for tier 3 — a tier LIST must give it at least
        // two draws per process before a number is quoted.
        // (WARM.4)(b) round 849 — the INTRA-handler probes, warm. Each is its
        // own object with its own `mode`, and each `*coarse` twin keeps ONLY
        // that probe's partition anchors, so the ON-minus-COARSE difference
        // prices its boundary differentially inside one process.
        tierBegin(tier)
        val (probeResult, probeDuration) = measureTimedValue {
            ProjectCompiler(SystemVfs).build(project, noEmit = true)
        }
        val probeMs = probeDuration.inWholeMicroseconds / 1000.0
        val probeFiles = probeResult.programFiles.size
        val probeErrors = probeResult.errorCount
        // `overheadMs` is the whole point of printing this separately: it is the
        // price of the instrumentation on an otherwise identical warm rebuild, so a
        // reader can say how much of any per-pass row is the probe.
        println(
            """{"instrumented":true,"tier":"$tier","run":$run,"ms":$probeMs,""" +
                """"files":$probeFiles,"errors":$probeErrors,""" +
                """"medianMs":$median,"overheadMs":${probeMs - median}}"""
        )
        if (measuredDrift || probeFiles != refFiles || probeErrors != refErrors) {
            tierStop()
            println(
                """{"instrumentedFalsified":true,"tier":"$tier","expectedFiles":$refFiles,""" +
                    """"expectedErrors":$refErrors,"gotFiles":$probeFiles,""" +
                    """"gotErrors":$probeErrors,"measuredDrift":$measuredDrift}"""
            )
            PassTiming.reset()
            PassTiming.detail = true
            PassTiming.spineDetail = true
            error(
                "!! ABORT — the instrumented rebuild (tier $tier) answered a DIFFERENT program " +
                    "than the measured iterations (expected $refFiles files / $refErrors errors, " +
                    "got $probeFiles / $probeErrors; measured iterations themselves drifted: " +
                    "$measuredDrift). A per-pass table taken from a different compile " +
                    "attributes nothing about the one that was timed — the instrumentation " +
                    "is either changing behaviour, or in-process state is leaking across " +
                    "rebuilds. No table is printed."
            )
        }
        // REPORT FIRST, THEN DISARM — round 850's label defect. `report()` reads
        // its own `mode` to label the arm, so clearing the mode before dumping
        // made every `*coarse` table print `mode: ON`. Pinned by
        // `BenchTierReportTest`.
        println(tierReport(tier))
        tierStop()
    }
    PassTiming.detail = true
    PassTiming.spineDetail = true
    // Leave the process's instrumentation exactly as this harness found it: the
    // table is dumped, so the counters (and the multi-million-entry distinct-node
    // set behind them) have no further reader.
    PassTiming.reset()
}
