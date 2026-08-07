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

import kotlin.time.measureTimedValue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.system.exitProcess

/**
 * CLI entry point for the xemantic TypeScript compiler — whole-project build.
 *
 * Usage:
 * ```
 * xemantic-typescript-compiler [--project|-p <dir-or-tsconfig>] [--noEmit] [path]
 * ```
 *
 * `path` (or `--project`) points at a directory containing `tsconfig.json`, or
 * directly at a tsconfig file; it defaults to the current directory. The compiler
 * loads the config, resolves the file graph (relative + `node_modules`), type-checks
 * the program, and (unless `--noEmit`) writes JS/declaration outputs to `outDir`.
 */
fun main(args: Array<String>) {
    val code = runCli(args)
    // Only on failure: exiting explicitly with 0 is the same as returning, and
    // `exitProcess` from a NORMAL completion would cut short anything the
    // runtime still wants to do on the way out.
    if (code != 0) exitProcess(code)
}

/**
 * Runs the CLI and RETURNS its exit code instead of ending the process.
 *
 * The split exists because the compile server calls this in-process: an
 * `exitProcess` inside the compile would take the DAEMON down with the first
 * project that has an error. It also lets the server report the real exit code
 * rather than deducing one by looking for "FAILED" in the captured stdout.
 *
 * **0 means the compile found no errors, 1 means it found some** — matching
 * `tsc`, which CI pipelines and shell `&&` chains both assume. This changed on
 * 2026-08-08: it used to return 0 unconditionally and report the outcome only in
 * the summary line, which made `xtsc` silently unusable as a build-failure
 * signal, and made the answer depend on whether a daemon happened to be running.
 * Non-zero from the CLIENT, by contrast, can also mean the request never ran
 * (see XTSC_CLIENT_UNAVAILABLE).
 */
fun runCli(args: Array<String>): Int {
    var project = "."
    var outDir: String? = null
    var noEmit = false
    var listAll = false
    var watch = false
    var watchVerify = false
    var incremental = false
    var passTiming = false
    // (FRONT.2) round 801 — print the flow-scan census. Set by --frontEnd and
    // --verifyFlowScan; a LOCAL rather than a read of `FrontEnd.mode`, which
    // the FrontEnd report block clears before this one runs.
    var flowScanReport = false
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--noEmit", "--noemit" -> noEmit = true
            "--watch", "-w" -> watch = true
            "--incremental" -> incremental = true
            "--watchVerify", "--watchverify" -> { watch = true; watchVerify = true }
            "--listAll", "--listall" -> listAll = true
            "--passTiming", "--passtiming" -> passTiming = true
            "--verifyMappedCache", "--verifymappedcache" -> {
                passTiming = true; PassTiming.verifyMappedCache = true
            }
            // (SETUP.2): the produced-vs-consumed census of buildFileLocalTypeMaps
            // — `calls` vs `distinct` at its single read site, plus the share of
            // entries nothing ever asks for (the only part a deferral DELETES
            // rather than moves). Implies --passTiming so the row it prices is on
            // the same run.
            "--fltmCensus", "--fltmcensus" -> {
                passTiming = true; FltmCensus.reset(); FltmCensus.on = true
            }
            // (TYPE.1)(a): attribute the getTypeOfExpression calls BY CALLER —
            // the only measurement that can test ARCHITECTURE-RETHINK § 0.1
            // stage 3's claim that "several handlers independently type the
            // same node". Costs a stack walk per OUTERMOST call, so it is an
            // offline attribution mode, never a production one.
            "--typeOfExprCallers", "--typeofexprcallers" -> {
                passTiming = true; PassTiming.callerAttr = true
            }
            // (DISPATCH.1)(a): the opt-in per-kind handler-table derivation.
            "--dispatchProbe", "--dispatchprobe" -> {
                SpineDispatch.reset(); SpineDispatch.mode = SpineDispatch.PROBE
            }
            "--dispatchGated", "--dispatchgated" -> {
                SpineDispatch.reset(); SpineDispatch.mode = SpineDispatch.GATED
            }
            // (SPINE.1)(a): the opt-in intra-handler attribution.
            "--spineSections", "--spinesections" -> {
                SpineSections.reset(); SpineSections.mode = SpineSections.ON
                repeat(200) { SpineSections.calibrate() }
            }
            // (CALL.1)(a): the opt-in intra-function attribution of
            // checkSingleCallExpressionTypes.
            "--callSections", "--callsections" -> {
                CallSections.reset(); CallSections.mode = CallSections.ON
            }
            // (CALL.2)(a): the opt-in intra-function attribution of
            // checkArgumentsAgainstSignature. The `Coarse` variant keeps only the
            // anchors, so an ON-vs-COARSE pair gives the per-boundary cost
            // differentially — the only calibration round 734 found trustworthy.
            "--argSections", "--argsections" -> {
                ArgSections.reset(); ArgSections.mode = ArgSections.ON
            }
            "--argSectionsCoarse", "--argsectionscoarse" -> {
                ArgSections.reset(); ArgSections.mode = ArgSections.COARSE
            }
            // (CALL.5)(b): the already-relates pre-gate on the argument check's
            // two unconditional narrowing arms. CENSUS keeps the OLD behaviour
            // and only records the verdict, so it reproduces the pre-change
            // binary and is a legitimate grid baseline; ON acts on it.
            "--argNarrowCensus", "--argnarrowcensus" -> {
                ArgNarrowGate.reset(); ArgNarrowGate.mode = ArgNarrowGate.CENSUS
            }
            "--verifyArgNarrowGate", "--verifyargnarrowgate" -> {
                ArgNarrowGate.reset(); ArgNarrowGate.mode = ArgNarrowGate.CENSUS
            }
            "--argNarrowGate", "--argnarrowgate" -> {
                ArgNarrowGate.reset(); ArgNarrowGate.mode = ArgNarrowGate.ON
            }
            "--argNarrowGateOff", "--argnarrowgateoff" -> {
                ArgNarrowGate.reset(); ArgNarrowGate.mode = ArgNarrowGate.OFF
            }
            // (CALL.3)(a): the opt-in attribution INSIDE narrowTypeFromFlow — the
            // arrivals-vs-distinct census plus the per-arrival split. `Coarse`
            // keeps only the whole-walk anchor, so an ON-vs-COARSE pair prices
            // the probe boundary differentially.
            "--narrowSections", "--narrowsections" -> {
                NarrowSections.reset(); NarrowSections.mode = NarrowSections.ON
            }
            "--narrowSectionsCoarse", "--narrowsectionscoarse" -> {
                NarrowSections.reset(); NarrowSections.mode = NarrowSections.COARSE
            }
            // (CALL.4): ON plus the `getReferencePath` rows, which are an order
            // of magnitude more frequent than the `narrowBy*` leaves — an
            // ON-vs-DEEP pair is their own differential.
            "--narrowSectionsDeep", "--narrowsectionsdeep" -> {
                NarrowSections.reset(); NarrowSections.mode = NarrowSections.DEEP
            }
            // (TYPE.2)(a): the opt-in attribution INSIDE spineCtaM3StatementAnchor
            // (level A, by callee) and checkVarDeclAssignability (level B, by
            // section). `Coarse` keeps only the per-level anchors, so an
            // ON-vs-COARSE pair prices the probe boundary differentially.
            "--ctaSections", "--ctasections" -> {
                CtaSections.reset(); CtaSections.mode = CtaSections.ON
            }
            "--ctaSectionsCoarse", "--ctasectionscoarse" -> {
                CtaSections.reset(); CtaSections.mode = CtaSections.COARSE
            }
            // (IANY.1): the opt-in attribution of spineIanyEnterNode — the last
            // of round 732's six biggest spine handlers with no attribution
            // round. Two spans per node (edge dispatch / own kind arms), whose
            // boundary count is a function of the node count alone, so a
            // before/after read of its rows carries no round-793 correction.
            "--ianySections", "--ianysections" -> {
                IanySections.reset(); IanySections.mode = IanySections.ON
            }
            // (IANY.1) the gate as a switch: restores the pre-798 behaviour
            // exactly, so ONE binary carries both arms — this run reproduces the
            // pre-change binary and is a legitimate grid baseline.
            "--ianyGateOff", "--ianygateoff" -> {
                IanySections.gateOff = true
            }
            // (IANY.1) round 799: the arm pre-gate as a switch — restores the
            // pre-799 19-arm `is` chain for every parent kind, so one binary
            // carries both arms of the residue-row read.
            "--ianyArmGateOff", "--ianyarmgateoff" -> {
                IanySections.armGateOff = true
            }
            // (IANY.1) round 800: the CALL/NEW argument-edge gate as a switch —
            // restores the pre-800 arm (every argument resolves its callee), so
            // one binary carries both arms of the row read and the grid baseline.
            "--ianyArgGateOff", "--ianyarggateoff" -> {
                IanySections.argGateOff = true
            }
            // (ENGINE.2): the opt-in attribution INSIDE checkPropertyAccessInExpr
            // (level P, recursive) and checkSinglePropertyAccess (level Q). The
            // property-access path is ~16% of a check-only compile — the largest
            // single block of checking work, and the one the (ENGINE.1) arc never
            // reached. `Coarse` is the differential calibration counterpart;
            // `Census` reads NO timestamps and answers G4 (does the walk visit a
            // node twice?) without polluting a timing run.
            "--cpaSections", "--cpasections" -> {
                CpaSections.reset(); CpaSections.mode = CpaSections.ON
            }
            "--cpaSectionsCoarse", "--cpasectionscoarse" -> {
                CpaSections.reset(); CpaSections.mode = CpaSections.COARSE
            }
            "--cpaSectionsCensus", "--cpasectionscensus" -> {
                CpaSections.reset(); CpaSections.mode = CpaSections.CENSUS
            }
            // (ENGINE.2d)(a): keep the PRE-gate behaviour of the round-425
            // loop-entry retry and COUNT the cases where skipping it would have
            // changed the answer. Reads no timestamp; independent of the mode.
            "--verifyLoopRetry", "--verifyloopretry" -> {
                CpaSections.verifyLoopRetry = true
            }
            // ... and its control: verify over EVERY retry call, including the
            // loop-crossing ones the gate never skips. A non-zero type-diff here
            // is what makes the skippable population's zero non-vacuous.
            "--verifyLoopRetryAll", "--verifyloopretryall" -> {
                CpaSections.verifyLoopRetry = true; CpaSections.verifyLoopRetryAll = true
            }
            // (ENGINE.2f) round 794: keep the round-424 UNION loop-entry retry
            // WALKING and honour its verdict — so the run reproduces the
            // pre-substitution binary and IS the grid baseline — while comparing
            // the substituted candidate against the re-walked one at instance,
            // member-set and verdict granularity.
            "--verifyUnionRetry", "--verifyunionretry" -> {
                CpaSections.verifyUnionRetry = true
            }
            // ... and its positive control: run the same comparison over the
            // COMPLEMENT (the loop-CROSSING calls the substitution never serves),
            // where round 424's reason for existing says the two walks must
            // disagree. A live instrument reports differences there.
            "--verifyUnionRetryAll", "--verifyunionretryall" -> {
                CpaSections.verifyUnionRetry = true; CpaSections.verifyUnionRetryAll = true
            }
            // (ENGINE.2d)(b): evaluate checkMemberAccessMissing's flow-suppression
            // predicate BOTH eagerly (where the blocks used to run) and deferred
            // (after the body), honour the EAGER verdict — so the run reproduces
            // the pre-change binary's output — and count every disagreement.
            "--verifyDeferSuppression", "--verifydefersuppression" -> {
                CpaSections.verifyDeferSuppression = true
            }
            // ... and its positive control: the deferred evaluation is handed an
            // unresolvable property name, so a live comparator MUST report
            // differences. A zero here would mean the instrument is dead.
            "--verifyDeferSuppressionBogus", "--verifydefersuppressionbogus" -> {
                CpaSections.verifyDeferSuppression = true
                CpaSections.verifyDeferSuppressionBogus = true
            }
            // (ENGINE.2e) round 792: price a candidate WHOLE-FUNCTION pre-gate.
            // Computes "does the property already resolve on the receiver's own
            // (apparent) type" BEFORE the body, honours NOTHING, and splits the
            // body's measured time by that verdict — so the prize is a MEASURE,
            // never a count — while recording how many of the calls the gate
            // would skip actually emitted. That last number is the falsifier.
            "--cmamPreGate", "--cmampregate" -> {
                CpaSections.reset(); CpaSections.mode = CpaSections.ON
                CpaSections.preGateProbe = true
            }
            // ... and its positive control: the gate answers yes for EVERY call,
            // so the "bodies that emitted" column must become non-zero. A dead
            // instrument would read 0 here too.
            "--cmamPreGateBogus", "--cmampregatebogus" -> {
                CpaSections.reset(); CpaSections.mode = CpaSections.ON
                CpaSections.preGateProbe = true; CpaSections.preGateBogus = true
            }
            // (ENGINE.2g) round 793: price the call-expression PROLOGUE pre-gate.
            // Computes the gate, HONOURS NOTHING (so the run reproduces the
            // pre-change binary and IS the grid's baseline output), splits the
            // prologue's measured time by the verdict, and counts how many of the
            // calls it would skip actually emitted or returned. That last column
            // is the falsifier.
            // (ENGINE.2h) round 795: the deferred TS2793 `implRelated` probe.
            // Evaluates it EAGERLY (at the position it used to occupy) and again
            // DEFERRED (at its single reader), honours the EAGER verdict — so the
            // run reproduces the pre-change binary and IS the grid's baseline —
            // and compares the two at `Diagnostic` granularity.
            "--verifyImplRelated", "--verifyimplrelated" -> {
                CallSections.verifyImplRelated = true
            }
            // ... and the FREE complement control (round 790): the same
            // comparison over every single-signature call, not only the ones
            // that reach an emission.
            "--verifyImplRelatedAll", "--verifyimplrelatedall" -> {
                CallSections.verifyImplRelated = true
                CallSections.verifyImplRelatedAll = true
            }
            // ... and the POSITIVE control: the deferred evaluation drops the
            // `allArgumentsMatch` gate, so the diff column must move.
            "--verifyImplRelatedBogus", "--verifyimplrelatedbogus" -> {
                CallSections.verifyImplRelated = true
                CallSections.verifyImplRelatedAll = true
                CallSections.verifyImplRelatedBogus = true
            }
            "--ccetPreGate", "--ccetpregate" -> {
                CallSections.reset(); CallSections.mode = CallSections.ON
                CallSections.preGateProbe = true
            }
            // ... and its positive control: the gate refutes EVERY call, so the
            // "of those FIRED" column must report the profile's true prologue
            // firing count. A dead instrument would read 0 here too.
            "--ccetPreGateBogus", "--ccetpregatebogus" -> {
                CallSections.reset(); CallSections.mode = CallSections.ON
                CallSections.preGateProbe = true; CallSections.preGateBogus = true
            }
            // (FRONT.1): the opt-in front-end attribution — section 0.1 stage 5,
            // ~20% of the compile and never profiled. Per-FILE spans, so no
            // calibration counterpart is needed.
            "--frontEnd", "--frontend" -> {
                FrontEnd.reset(); FrontEnd.mode = FrontEnd.ON
                FlowScan.reset(); flowScanReport = true
            }
            // (FRONT.2) round 801 — the B464 reassignment-scan A/B and its
            // equivalence verifier. Both scanners are in the binary, so these
            // select an arm rather than needing a second build.
            "--flowScanLegacy", "--flowscanlegacy" -> {
                FlowScan.reset(); FlowScan.legacy = true
            }
            "--verifyFlowScan", "--verifyflowscan" -> {
                FlowScan.reset(); FlowScan.verify = true; flowScanReport = true
            }
            "--flowScanBogus", "--flowscanbogus" -> {
                FlowScan.bogus = true
            }
            "--flowEagerSet", "--floweagerset" -> {
                FlowScan.eagerSet = true
            }
            "--partitionCheck", "--partitioncheck" -> {
                i++; if (i < args.size) PartitionCheck.workers = args[i].toIntOrNull() ?: 0
            }
            "--workers" -> {
                i++; if (i < args.size) ParallelCheckMode.workers = args[i].toIntOrNull() ?: 0
            }
            // (AUDIT.3): price the globals-lookup population by AMPLIFICATION —
            // N reads of the same key under ONE timestamp pair, so the ~89 ns
            // pair cannot dominate the ~57 ns it is measuring. Needs the
            // instrumented table, hence --passTiming. Two runs at different N
            // solve for the per-read cost with the pair cost cancelling.
            "--globalsAmp", "--globalsamp" -> {
                passTiming = true
                i++
                if (i < args.size) {
                    GlobalsAmp.reset()
                    GlobalsAmp.reads = args[i].toIntOrNull() ?: 0
                }
            }
            // (AOT.4)(c) round 840(c): send the emitted tree somewhere other than the
            // config's outDir. The AOT trainer needs it — training WITH emit is worth
            // ~1.26 s on an emitting compile (docs/perf/aot-cache.md § 9) and a training
            // run must never write into the user's project. Ignored under --noEmit, and
            // (deliberately) not threaded through the --incremental/--watch paths.
            "--outDir", "--outdir" -> { i++; if (i < args.size) outDir = args[i] }
            "--project", "-p" -> { i++; if (i < args.size) project = args[i] }
            "--help", "-h" -> { printUsage(); return 0 }
            else -> if (!a.startsWith("-")) project = a
        }
        i++
    }

    println("xemantic-typescript-compiler — whole-project build")
    println(
        "project: $project${if (noEmit) "  (noEmit)" else ""}" +
            if (outDir != null && !noEmit) "  (outDir: $outDir)" else "",
    )

    if (passTiming) {
        PassTiming.reset()
        PassTiming.enabled = true
    }
    val (result, duration) = measureTimedValue {
        // INV.7(d3): under --incremental, a persisted .xtsbuildinfo (same compiler
        // build id, hash-validated inputs) steers a partition recheck of only the
        // changed files' reverse-dependency closure; anything non-local, or any
        // validation failure, is a plain full build.
        if (incremental) TsBuildInfo.build(SystemVfs, project, noEmit, XTSC_BUILD_ID, log = ::println)
        else ProjectCompiler(SystemVfs).build(project, noEmit, outDir = outDir)
    }

    println("config:  ${result.configPath}")
    println("files:   ${result.rootFiles.size} root, ${result.programFiles.size} in program")
    if (result.unresolved.isNotEmpty()) {
        println("unresolved imports: ${result.unresolved.size} (e.g. ${
            result.unresolved.take(5).joinToString(", ") { "'${it.second}'" }
        })")
    }
    if (!noEmit) println("emitted: ${result.written.size} output file(s)")

    printDiagnostics(result.diagnostics, listAll)
    PartitionCheck.reportLines.forEach { println(it) }
    println("time:    ${duration.inWholeMilliseconds} ms")
    if (passTiming) PassTiming.dump(::println)
    if (FltmCensus.on) print(FltmCensus.report())
    if (SpineDispatch.mode == SpineDispatch.PROBE) {
        println(SpineDispatch.report())
        println("== (DISPATCH.1) csv ==")
        print(SpineDispatch.csv())
        println("== (DISPATCH.1) csv end ==")
        SpineDispatch.mode = SpineDispatch.OFF
    }
    if (SpineSections.mode == SpineSections.ON) {
        println(SpineSections.report())
        println("== (SPINE.1) csv ==")
        print(SpineSections.csv())
        println("== (SPINE.1) csv end ==")
        SpineSections.mode = SpineSections.OFF
    }
    if (CallSections.mode == CallSections.ON) {
        println(CallSections.report())
        println("== (CALL.1) csv ==")
        print(CallSections.csv())
        println("== (CALL.1) csv end ==")
        CallSections.mode = CallSections.OFF
    } else if (CallSections.verifyImplRelated) {
        println(CallSections.implRelatedReport())
    }
    if (ArgNarrowGate.mode == ArgNarrowGate.CENSUS) {
        println(ArgNarrowGate.report())
    }
    if (ArgSections.mode != ArgSections.OFF) {
        println(ArgSections.report())
        println("== (CALL.2) csv ==")
        print(ArgSections.csv())
        println("== (CALL.2) csv end ==")
        ArgSections.mode = ArgSections.OFF
    }
    if (NarrowSections.mode != NarrowSections.OFF) {
        println(NarrowSections.report())
        println("== (CALL.3) csv ==")
        print(NarrowSections.csv())
        println("== (CALL.3) csv end ==")
        NarrowSections.mode = NarrowSections.OFF
    }
    if (FrontEnd.mode != FrontEnd.OFF) {
        println(FrontEnd.report())
        println("== (FRONT.1) csv ==")
        print(FrontEnd.csv())
        println("== (FRONT.1) csv end ==")
        FrontEnd.mode = FrontEnd.OFF
    }
    if (flowScanReport) {
        print(FlowScan.report())
        FlowScan.verify = false; FlowScan.bogus = false
    }
    if (IanySections.mode != IanySections.OFF) {
        println(IanySections.report())
        println("== (IANY.1) csv ==")
        print(IanySections.csv())
        println("== (IANY.1) csv end ==")
        IanySections.mode = IanySections.OFF
    }
    if (CtaSections.mode != CtaSections.OFF) {
        println(CtaSections.report())
        println("== (TYPE.2) csv ==")
        print(CtaSections.csv())
        println("== (TYPE.2) csv end ==")
        CtaSections.mode = CtaSections.OFF
    }
    if (CpaSections.mode != CpaSections.OFF || CpaSections.verifyLoopRetry ||
        CpaSections.verifyDeferSuppression || CpaSections.verifyUnionRetry
    ) {
        println(CpaSections.report())
        println("== (ENGINE.2) csv ==")
        print(CpaSections.csv())
        println("== (ENGINE.2) csv end ==")
        CpaSections.mode = CpaSections.OFF
    }
    println(if (result.errorCount == 0) "OK — 0 errors" else "FAILED — ${result.errorCount} error(s)")
    if (watch) runWatchLoop(project, result.configPath, noEmit, listAll, watchVerify, result)
    return if (result.errorCount == 0) 0 else 1
}

/**
 * INV.7(c1): the minimal watch loop — full rebuild per relevant change batch
 * (incremental reuse is the separate .tsbuildinfo-style item). Change events
 * stream from [fileEvents] over the config's directory tree; a batch is
 * everything that arrives until the tree stays quiet for 200 ms; a batch
 * triggers a rebuild iff it touches a compilation-relevant file (source /
 * config extensions) outside the output directory.
 */
private fun runWatchLoop(
    project: String, configPath: String, noEmit: Boolean, listAll: Boolean,
    verify: Boolean, initial: ProjectCompiler.Result,
) {
    val watchRoot = configPath.substringBeforeLast('/', ".").ifEmpty { project }
    println("watching: $watchRoot  (Ctrl-C to exit)")
    var prev = initial
    runCompilerPipeline {
        coroutineScope {
            val changes = Channel<String>(Channel.UNLIMITED)
            launch {
                fileEvents(watchRoot).collect { changes.send(it) }
            }
            while (true) {
                val batch = awaitChangeBatch(changes, quietMs = 200)
                val relevant = batch.filterTo(mutableSetOf()) { watchRelevant(it) }
                if (relevant.isEmpty()) continue
                println()
                // INV.7(d1): incremental recheck over the reverse-dependency
                // closure when eligible; full rebuild otherwise (or when the
                // incremental outcome is invalid — program shape changed).
                val incremental = noEmit &&
                    WatchIncremental.incrementalEligible(relevant, prev) { SystemVfs.readText(it) }
                val (result, duration) = measureTimedValue {
                    if (incremental) {
                        val closure = WatchIncremental.recheckClosure(relevant, prev.importEdges)
                        println("change detected (${relevant.size} file(s)) — incremental recheck of ${closure.size}/${prev.programFiles.size} file(s)…")
                        val fresh = ProjectCompiler(SystemVfs).build(project, noEmit, recheckOnly = closure)
                        if (WatchIncremental.incrementalOutcomeValid(relevant, prev, fresh)) {
                            val merged = fresh.copy(
                                diagnostics = WatchIncremental.mergeDiagnostics(prev, fresh.diagnostics, closure),
                            )
                            if (verify) {
                                val full = ProjectCompiler(SystemVfs).build(project, noEmit)
                                val a = merged.diagnostics.map { "${it.fileName}|${it.start}|${it.code}|${it.message}" }.sorted()
                                val b = full.diagnostics.map { "${it.fileName}|${it.start}|${it.code}|${it.message}" }.sorted()
                                println(if (a == b) "watchVerify: INCREMENTAL ≡ FULL (${b.size} diagnostics)"
                                        else "watchVerify: DIVERGED — incremental=${a.size} full=${b.size} (REPORT THIS)")
                            }
                            merged
                        } else {
                            println("program shape changed — full rebuild…")
                            ProjectCompiler(SystemVfs).build(project, noEmit)
                        }
                    } else {
                        println("change detected (${relevant.size} file(s)) — rebuilding…")
                        ProjectCompiler(SystemVfs).build(project, noEmit)
                    }
                }
                prev = result
                printDiagnostics(result.diagnostics, listAll)
                println("time:    ${duration.inWholeMilliseconds} ms")
                println(if (result.errorCount == 0) "OK — 0 errors" else "FAILED — ${result.errorCount} error(s)")
            }
        }
    }
}

/** A change is compilation-relevant iff it is a source/config file outside build outputs. */
internal fun watchRelevant(path: String): Boolean {
    if ("/node_modules/" in path) return false
    val name = path.substringAfterLast('/')
    return name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".js") ||
        name.endsWith(".jsx") || name.endsWith(".mts") || name.endsWith(".cts") ||
        name.endsWith(".mjs") || name.endsWith(".cjs") ||
        name == "tsconfig.json" || name == "package.json"
}

/**
 * Collect one DEBOUNCED batch: block for the first event, then keep draining
 * until the channel stays quiet for [quietMs]. Extracted for direct testing.
 */
internal suspend fun awaitChangeBatch(
    changes: ReceiveChannel<String>,
    quietMs: Long,
): Set<String> {
    val batch = mutableSetOf(changes.receive())
    while (true) {
        val next = withTimeoutOrNull(quietMs.milliseconds) { changes.receive() } ?: break
        batch.add(next)
    }
    return batch
}

private fun printDiagnostics(diagnostics: List<Diagnostic>, listAll: Boolean = false) {
    val errors = diagnostics.filter { it.category == DiagnosticCategory.Error }
    val warnings = diagnostics.filter { it.category == DiagnosticCategory.Warning }
    println("diagnostics: ${errors.size} error(s), ${warnings.size} warning(s)")
    if (diagnostics.isEmpty()) return

    // Count by code for a compact overview.
    val byCode = diagnostics.groupingBy { it.code }.eachCount().entries.sortedByDescending { it.value }
    println("  by code: " + byCode.joinToString(", ") { "TS${it.key}×${it.value}" })

    // Show the first errors in detail (--listAll: every error, for run-to-run FP diffing).
    val shown = if (listAll) errors else errors.take(30)
    for (d in shown) {
        val loc = if (d.fileName != null && d.line != null) "${d.fileName}:${d.line}:${d.character ?: 1}" else (d.fileName ?: "")
        println("  $loc - error TS${d.code}: ${d.message}")
        // --listAll: print elaboration chains too (indented; never matches the
        // `error TS` grep the A/B diffs key on) — TS2769/TS2322 triage needs them.
        if (listAll) d.messageChain.forEach { line -> println("      |$line") }
    }
    if (errors.size > shown.size) println("  ... and ${errors.size - shown.size} more error(s)")
}

private fun printUsage() {
    println(
        """
        Usage: xemantic-typescript-compiler [--project|-p <dir-or-tsconfig>] [--noEmit] [path]

          path / --project   directory containing tsconfig.json, or a tsconfig path (default: .)
          --noEmit           type-check only; do not write outputs
          --outDir <dir>     write the emitted tree here instead of the config's outDir
                             (relative to the CWD; inert under --noEmit/--incremental)
          --listAll          print every error (default: first 30) — for run-to-run FP diffing
          --passTiming       print the INV.0 per-pass wall-time table + recompute counters
          --dispatchProbe    (DISPATCH.1) per-handler/per-kind spine attribution
          --dispatchGated    (DISPATCH.1) run only the derived per-kind handler table
          --spineSections    (SPINE.1) intra-handler attribution of the two hot leaves
          --ianySections     (IANY.1) attribution of spineIanyEnterNode, split by whether
                             the contextual state it defines can be read at all
          --ianyGateOff      (IANY.1) restore the pre-798 behaviour of that handler — the
                             equivalence baseline, in the same binary
          --ianyArmGateOff   (IANY.1) restore the pre-799 parent-edge dispatch (the full
                             19-arm `is` chain for every parent kind)
          --ianyArgGateOff   (IANY.1) restore the pre-800 CALL/NEW argument edge (resolve
                             the callee for every argument, reader or not)
          --callSections     (CALL.1) intra-function attribution of checkSingleCallExpressionTypes
          --argSections      (CALL.2) intra-function attribution of checkArgumentsAgainstSignature
          --argSectionsCoarse  the same, anchors only — the differential calibration counterpart
          --argNarrowCensus  (CALL.5)(b) evaluate the argument-narrowing already-relates
                             gate, keep the OLD behaviour, and report per arm how many
                             refusals would have SUBSTITUTED a different type
                             (--verifyArgNarrowGate is the same thing under its
                              equivalence-instrument name; --argNarrowGate acts on the
                              gate, --argNarrowGateOff restores the pre-796 behaviour)
          --narrowSections   (CALL.3) intra-walk attribution of narrowTypeFromFlow (arrivals vs distinct)
          --narrowSectionsCoarse  the same, whole-walk anchor only — the calibration counterpart
          --narrowSectionsDeep  (CALL.4) the same plus the getReferencePath rows
          --ctaSections      (TYPE.2) attribution of spineCtaM3StatementAnchor + checkVarDeclAssignability
          --ctaSectionsCoarse  the same, per-level anchors only — the differential calibration counterpart
          --cpaSections      (ENGINE.2) attribution of checkPropertyAccessInExpr + checkSinglePropertyAccess
          --cpaSectionsCoarse  the same, entry anchors only — the differential calibration counterpart
          --cpaSectionsCensus  counters and distinct-node sets only; reads no timestamps
          --verifyLoopRetry  (ENGINE.2d)(a) keep the round-425 loop-entry retry and COUNT
                             every call where skipping it would change the answer
          --verifyUnionRetry  (ENGINE.2f) keep the round-424 UNION loop-entry retry walking
                             and honour it, while comparing the substituted candidate against
                             the re-walked one (--verifyUnionRetryAll = the complement control)
          --verifyImplRelated  (ENGINE.2h) evaluate the TS2793 "implementation would have
                             succeeded" probe BOTH eagerly and deferred, honour the eager
                             verdict, and compare at Diagnostic granularity
                             (--verifyImplRelatedAll = the free complement population,
                              --verifyImplRelatedBogus = the positive control)
          --verifyDeferSuppression  (ENGINE.2d)(b) evaluate checkMemberAccessMissing's flow
                             suppression BOTH eagerly and deferred, honour the eager verdict,
                             and count every disagreement (--verifyDeferSuppressionBogus = control)
          --cmamPreGate      (ENGINE.2e) compute checkMemberAccessMissing's whole-function
                             pre-gate WITHOUT honouring it: prints the population it would
                             skip, the body time behind it, and how many of those calls
                             emit — the falsifier (--cmamPreGateBogus = control)
          --frontEnd         (FRONT.1) front-end attribution: config / crawl / parse / imports / bind
          --flowScanLegacy   (FRONT.2) run the pre-801 B464 reassignment scanner (A/B arm)
          --verifyFlowScan   (FRONT.2) run BOTH scanners and report divergences
          --flowScanBogus    (FRONT.2) positive control: corrupt the fast scanner
          --flowEagerSet     (FRONT.2) build B464 suffix sets eagerly (pre-801 arm)
          --typeOfExprCallers  (TYPE.1) attribute the getTypeOfExpression calls by CALLER + co-occurrence
          --incremental      persist/reuse tsconfig.xtsbuildinfo across processes (recheck only changes under --noEmit)
          --watch, -w        stay running and rebuild on file changes (incremental recheck under --noEmit)
          --watchVerify      --watch + diff every incremental result against a full rebuild (INV.7(d1) gate)
          --partitionCheck N run N sequential partition checkers and diff vs the full run (INV.6(6b))
          --workers N        parallel share-nothing partition check on N threads (INV.6(6c); line order may differ)
          --globalsAmp N     (AUDIT.3) price one globals[name] probe: N reads under one timestamp pair (implies --passTiming)
          --fltmCensus       (SETUP.2) produced-vs-consumed census of buildFileLocalTypeMaps (implies --passTiming)
                             + the INV.3(a) globals-lookup conflation classification
          --help, -h         show this help
        """.trimIndent()
    )
}
