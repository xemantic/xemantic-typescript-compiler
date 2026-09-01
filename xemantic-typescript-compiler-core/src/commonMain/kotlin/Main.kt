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
/**
 * The CLI's parsed arguments — everything the argument loop decides that is NOT
 * a process-global debug mode.
 *
 * Split out of [runCli] for (SERVE.1): it makes the argument loop drivable on
 * its own, which is what lets `CliModeRestoreTest` feed it every documented
 * flag and then check — reflectively, over the mode objects' declared fields —
 * that [ModeLedger.restore] puts every one of them back. Driving `runCli`
 * instead would compile a project, and a compile legitimately moves counters.
 */
internal class CliArgs {
    var project: String = "."
    var outDir: String? = null
    var noEmit: Boolean = false
    var listAll: Boolean = false
    var watch: Boolean = false
    var watchVerify: Boolean = false
    var incremental: Boolean = false
    var passTiming: Boolean = false
    /**
     * (FRONT.2) round 801 — print the flow-scan census. Set by `--frontEnd` and
     * `--verifyFlowScan`; held here rather than read back off `FrontEnd.mode`,
     * which the FrontEnd report block clears before this one runs.
     */
    var flowScanReport: Boolean = false
    /** `--help`/`-h` was seen; the caller prints usage and stops. */
    var help: Boolean = false
}

/**
 * Parses [args], recording every process-global debug MODE it turns on in
 * [modes] so the caller can put them all back.
 *
 * **THE CALL CONVENTION IS THE GUARD (SERVE.1).** A `when` arm here writes a
 * global mode field ONLY through `modes.set(Obj::field, value)`. A bare
 * `Obj.field = value` compiles, works in the one-shot CLI, and silently
 * reconfigures every later request on a `--serve` daemon; nothing in the type
 * system can see the difference, so `CliModeRestoreTest` is what does.
 */
internal fun parseCliArgs(args: Array<String>, modes: ModeLedger): CliArgs {
    val o = CliArgs()
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--noEmit", "--noemit" -> o.noEmit = true
            "--watch", "-w" -> o.watch = true
            "--incremental" -> o.incremental = true
            "--watchVerify", "--watchverify" -> { o.watch = true; o.watchVerify = true }
            "--listAll", "--listall" -> o.listAll = true
            "--passTiming", "--passtiming" -> o.passTiming = true
            // (WARM.1)(c) round 846 — the probe's cheaper TIERS. `--passTiming`
            // costs ~50% of a WARM rebuild, which makes every warm absolute in
            // its table untrustworthy; these keep the per-pass ROWS and drop the
            // per-call bookkeeping that is the actual price. `rows` also runs
            // the PRODUCTION spine walk, so `checkSpine`'s row is un-perturbed.
            // Everything they drop reads 0 in the dump and the dump SAYS so.
            "--passTimingRows", "--passtimingrows" -> {
                o.passTiming = true
                modes.set(PassTiming::detail, false)
                modes.set(PassTiming::spineDetail, false)
            }
            "--passTimingSpine", "--passtimingspine" -> {
                o.passTiming = true
                modes.set(PassTiming::detail, false)
                modes.set(PassTiming::spineDetail, true)
            }
            "--verifyMappedCache", "--verifymappedcache" -> {
                o.passTiming = true; modes.set(PassTiming::verifyMappedCache, true)
            }
            // (SETUP.2): the produced-vs-consumed census of buildFileLocalTypeMaps
            // — `calls` vs `distinct` at its single read site, plus the share of
            // entries nothing ever asks for (the only part a deferral DELETES
            // rather than moves). Implies --passTiming so the row it prices is on
            // the same run.
            "--fltmCensus", "--fltmcensus" -> {
                o.passTiming = true; FltmCensus.reset(); modes.set(FltmCensus::on, true)
            }
            // (TYPE.1)(a): attribute the getTypeOfExpression calls BY CALLER —
            // the only measurement that can test ARCHITECTURE-RETHINK § 0.1
            // stage 3's claim that "several handlers independently type the
            // same node". Costs a stack walk per OUTERMOST call, so it is an
            // offline attribution mode, never a production one.
            "--typeOfExprCallers", "--typeofexprcallers" -> {
                o.passTiming = true; modes.set(PassTiming::callerAttr, true)
            }
            // (DISPATCH.1)(a): the opt-in per-kind handler-table derivation.
            "--dispatchProbe", "--dispatchprobe" -> {
                SpineDispatch.reset(); modes.set(SpineDispatch::mode, SpineDispatch.PROBE)
            }
            "--dispatchGated", "--dispatchgated" -> {
                SpineDispatch.reset(); modes.set(SpineDispatch::mode, SpineDispatch.GATED)
            }
            // (SPINE.1)(a): the opt-in intra-handler attribution.
            "--spineSections", "--spinesections" -> {
                SpineSections.reset(); modes.set(SpineSections::mode, SpineSections.ON)
                repeat(200) { SpineSections.calibrate() }
            }
            // (CALL.1)(a): the opt-in intra-function attribution of
            // checkSingleCallExpressionTypes.
            "--callSections", "--callsections" -> {
                CallSections.reset(); modes.set(CallSections::mode, CallSections.ON)
            }
            // (WARM.5) round 851: the anchors-only twin, so the probe's own
            // boundary is priced by an ON-vs-COARSE differential (round 734).
            "--callSectionsCoarse", "--callsectionscoarse" -> {
                CallSections.reset(); modes.set(CallSections::mode, CallSections.COARSE)
            }
            // (CALL.2)(a): the opt-in intra-function attribution of
            // checkArgumentsAgainstSignature. The `Coarse` variant keeps only the
            // anchors, so an ON-vs-COARSE pair gives the per-boundary cost
            // differentially — the only calibration round 734 found trustworthy.
            "--argSections", "--argsections" -> {
                ArgSections.reset(); modes.set(ArgSections::mode, ArgSections.ON)
            }
            "--argSectionsCoarse", "--argsectionscoarse" -> {
                ArgSections.reset(); modes.set(ArgSections::mode, ArgSections.COARSE)
            }
            // (CALL.5)(b): the already-relates pre-gate on the argument check's
            // two unconditional narrowing arms. CENSUS keeps the OLD behaviour
            // and only records the verdict, so it reproduces the pre-change
            // binary and is a legitimate grid baseline; ON acts on it.
            "--argNarrowCensus", "--argnarrowcensus" -> {
                ArgNarrowGate.reset(); modes.set(ArgNarrowGate::mode, ArgNarrowGate.CENSUS)
            }
            "--verifyArgNarrowGate", "--verifyargnarrowgate" -> {
                ArgNarrowGate.reset(); modes.set(ArgNarrowGate::mode, ArgNarrowGate.CENSUS)
            }
            "--argNarrowGate", "--argnarrowgate" -> {
                ArgNarrowGate.reset(); modes.set(ArgNarrowGate::mode, ArgNarrowGate.ON)
            }
            "--argNarrowGateOff", "--argnarrowgateoff" -> {
                ArgNarrowGate.reset(); modes.set(ArgNarrowGate::mode, ArgNarrowGate.OFF)
            }
            // (CALL.3)(a): the opt-in attribution INSIDE narrowTypeFromFlow — the
            // arrivals-vs-distinct census plus the per-arrival split. `Coarse`
            // keeps only the whole-walk anchor, so an ON-vs-COARSE pair prices
            // the probe boundary differentially.
            "--narrowSections", "--narrowsections" -> {
                NarrowSections.reset(); modes.set(NarrowSections::mode, NarrowSections.ON)
            }
            "--narrowSectionsCoarse", "--narrowsectionscoarse" -> {
                NarrowSections.reset(); modes.set(NarrowSections::mode, NarrowSections.COARSE)
            }
            // (CALL.4): ON plus the `getReferencePath` rows, which are an order
            // of magnitude more frequent than the `narrowBy*` leaves — an
            // ON-vs-DEEP pair is their own differential.
            "--narrowSectionsDeep", "--narrowsectionsdeep" -> {
                NarrowSections.reset(); modes.set(NarrowSections::mode, NarrowSections.DEEP)
            }
            // (TYPE.2)(a): the opt-in attribution INSIDE spineCtaM3StatementAnchor
            // (level A, by callee) and checkVarDeclAssignability (level B, by
            // section). `Coarse` keeps only the per-level anchors, so an
            // ON-vs-COARSE pair prices the probe boundary differentially.
            "--ctaSections", "--ctasections" -> {
                CtaSections.reset(); modes.set(CtaSections::mode, CtaSections.ON)
            }
            "--ctaSectionsCoarse", "--ctasectionscoarse" -> {
                CtaSections.reset(); modes.set(CtaSections::mode, CtaSections.COARSE)
            }
            // (IANY.1): the opt-in attribution of spineIanyEnterNode — the last
            // of round 732's six biggest spine handlers with no attribution
            // round. Two spans per node (edge dispatch / own kind arms), whose
            // boundary count is a function of the node count alone, so a
            // before/after read of its rows carries no round-793 correction.
            "--ianySections", "--ianysections" -> {
                IanySections.reset(); modes.set(IanySections::mode, IanySections.ON)
            }
            // (WARM.3) round 849: the PRIZE MEASUREMENT for a process-global lib
            // TYPE cache — a produced-vs-consumed census plus the outermost-mint
            // nanos at the two mint boundaries, split lib vs non-lib.
            "--libTypeCensus", "--libtypecensus" -> {
                LibTypeCensus.reset(); modes.set(LibTypeCensus::enabled, true)
            }
            // (IANY.1) the gate as a switch: restores the pre-798 behaviour
            // exactly, so ONE binary carries both arms — this run reproduces the
            // pre-change binary and is a legitimate grid baseline.
            "--ianyGateOff", "--ianygateoff" -> {
                modes.set(IanySections::gateOff, true)
            }
            // (IANY.1) round 799: the arm pre-gate as a switch — restores the
            // pre-799 19-arm `is` chain for every parent kind, so one binary
            // carries both arms of the residue-row read.
            "--ianyArmGateOff", "--ianyarmgateoff" -> {
                modes.set(IanySections::armGateOff, true)
            }
            // (IANY.1) round 800: the CALL/NEW argument-edge gate as a switch —
            // restores the pre-800 arm (every argument resolves its callee), so
            // one binary carries both arms of the row read and the grid baseline.
            "--ianyArgGateOff", "--ianyarggateoff" -> {
                modes.set(IanySections::argGateOff, true)
            }
            // (ENGINE.2): the opt-in attribution INSIDE checkPropertyAccessInExpr
            // (level P, recursive) and checkSinglePropertyAccess (level Q). The
            // property-access path is ~16% of a check-only compile — the largest
            // single block of checking work, and the one the (ENGINE.1) arc never
            // reached. `Coarse` is the differential calibration counterpart;
            // `Census` reads NO timestamps and answers G4 (does the walk visit a
            // node twice?) without polluting a timing run.
            "--cpaSections", "--cpasections" -> {
                CpaSections.reset(); modes.set(CpaSections::mode, CpaSections.ON)
            }
            "--cpaSectionsCoarse", "--cpasectionscoarse" -> {
                CpaSections.reset(); modes.set(CpaSections::mode, CpaSections.COARSE)
            }
            "--cpaSectionsCensus", "--cpasectionscensus" -> {
                CpaSections.reset(); modes.set(CpaSections::mode, CpaSections.CENSUS)
            }
            // (ENGINE.2d)(a): keep the PRE-gate behaviour of the round-425
            // loop-entry retry and COUNT the cases where skipping it would have
            // changed the answer. Reads no timestamp; independent of the mode.
            "--verifyLoopRetry", "--verifyloopretry" -> {
                modes.set(CpaSections::verifyLoopRetry, true)
            }
            // ... and its control: verify over EVERY retry call, including the
            // loop-crossing ones the gate never skips. A non-zero type-diff here
            // is what makes the skippable population's zero non-vacuous.
            "--verifyLoopRetryAll", "--verifyloopretryall" -> {
                modes.set(CpaSections::verifyLoopRetry, true)
                modes.set(CpaSections::verifyLoopRetryAll, true)
            }
            // (ENGINE.2f) round 794: keep the round-424 UNION loop-entry retry
            // WALKING and honour its verdict — so the run reproduces the
            // pre-substitution binary and IS the grid baseline — while comparing
            // the substituted candidate against the re-walked one at instance,
            // member-set and verdict granularity.
            "--verifyUnionRetry", "--verifyunionretry" -> {
                modes.set(CpaSections::verifyUnionRetry, true)
            }
            // ... and its positive control: run the same comparison over the
            // COMPLEMENT (the loop-CROSSING calls the substitution never serves),
            // where round 424's reason for existing says the two walks must
            // disagree. A live instrument reports differences there.
            "--verifyUnionRetryAll", "--verifyunionretryall" -> {
                modes.set(CpaSections::verifyUnionRetry, true)
                modes.set(CpaSections::verifyUnionRetryAll, true)
            }
            // (ENGINE.2d)(b): evaluate checkMemberAccessMissing's flow-suppression
            // predicate BOTH eagerly (where the blocks used to run) and deferred
            // (after the body), honour the EAGER verdict — so the run reproduces
            // the pre-change binary's output — and count every disagreement.
            "--verifyDeferSuppression", "--verifydefersuppression" -> {
                modes.set(CpaSections::verifyDeferSuppression, true)
            }
            // ... and its positive control: the deferred evaluation is handed an
            // unresolvable property name, so a live comparator MUST report
            // differences. A zero here would mean the instrument is dead.
            "--verifyDeferSuppressionBogus", "--verifydefersuppressionbogus" -> {
                modes.set(CpaSections::verifyDeferSuppression, true)
                modes.set(CpaSections::verifyDeferSuppressionBogus, true)
            }
            // (ENGINE.2e) round 792: price a candidate WHOLE-FUNCTION pre-gate.
            // Computes "does the property already resolve on the receiver's own
            // (apparent) type" BEFORE the body, honours NOTHING, and splits the
            // body's measured time by that verdict — so the prize is a MEASURE,
            // never a count — while recording how many of the calls the gate
            // would skip actually emitted. That last number is the falsifier.
            "--cmamPreGate", "--cmampregate" -> {
                CpaSections.reset(); modes.set(CpaSections::mode, CpaSections.ON)
                modes.set(CpaSections::preGateProbe, true)
            }
            // ... and its positive control: the gate answers yes for EVERY call,
            // so the "bodies that emitted" column must become non-zero. A dead
            // instrument would read 0 here too.
            "--cmamPreGateBogus", "--cmampregatebogus" -> {
                CpaSections.reset(); modes.set(CpaSections::mode, CpaSections.ON)
                modes.set(CpaSections::preGateProbe, true)
                modes.set(CpaSections::preGateBogus, true)
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
                modes.set(CallSections::verifyImplRelated, true)
            }
            // ... and the FREE complement control (round 790): the same
            // comparison over every single-signature call, not only the ones
            // that reach an emission.
            "--verifyImplRelatedAll", "--verifyimplrelatedall" -> {
                modes.set(CallSections::verifyImplRelated, true)
                modes.set(CallSections::verifyImplRelatedAll, true)
            }
            // ... and the POSITIVE control: the deferred evaluation drops the
            // `allArgumentsMatch` gate, so the diff column must move.
            "--verifyImplRelatedBogus", "--verifyimplrelatedbogus" -> {
                modes.set(CallSections::verifyImplRelated, true)
                modes.set(CallSections::verifyImplRelatedAll, true)
                modes.set(CallSections::verifyImplRelatedBogus, true)
            }
            "--ccetPreGate", "--ccetpregate" -> {
                CallSections.reset(); modes.set(CallSections::mode, CallSections.ON)
                modes.set(CallSections::preGateProbe, true)
            }
            // ... and its positive control: the gate refutes EVERY call, so the
            // "of those FIRED" column must report the profile's true prologue
            // firing count. A dead instrument would read 0 here too.
            "--ccetPreGateBogus", "--ccetpregatebogus" -> {
                CallSections.reset(); modes.set(CallSections::mode, CallSections.ON)
                modes.set(CallSections::preGateProbe, true)
                modes.set(CallSections::preGateBogus, true)
            }
            // (FRONT.1): the opt-in front-end attribution — section 0.1 stage 5,
            // ~20% of the compile and never profiled. Per-FILE spans, so no
            // calibration counterpart is needed.
            "--frontEnd", "--frontend" -> {
                FrontEnd.reset(); modes.set(FrontEnd::mode, FrontEnd.ON)
                FlowScan.reset(); o.flowScanReport = true
            }
            // (WARM.19) round 871 — price the CRAWL PRE-PARSE by amplification
            // (round 759), because the crawl's WALL is a concurrent pipeline
            // whose read+parse CPU sums to 6-9x it: `r` extra parses per file
            // make the wall `floor + (1 + r) * C`, and two values of `r` cancel
            // the floor. This is the upper bound on what a cross-request parse
            // cache in a `--serve` daemon could ever return. Arms `--frontEnd`
            // itself, since the arithmetic falsifier prints in its table.
            // (WARM.19) — the cross-request parse cache's OFF arm, in the same
            // binary, so the capture is a controlled row rather than a
            // two-build difference (round 793).
            "--parseCacheOff", "--parsecacheoff" -> {
                modes.set(CrawlParseCache::enabled, false)
            }
            // (WARM.21) round 874 — the TAV pass's OFF arm, for pricing it off
            // the whole-rebuild wall. It deliberately does NOT arm `--frontEnd`:
            // the census's own extra chain walks would land inside the very
            // measurement this flag exists to take. Its falsifier is the error
            // count, which moves because the pass stops emitting.
            "--tavOff", "--tavoff" -> {
                modes.set(FrontEnd::tavOff, true)
            }
            // (WARM.21) — the pre-874 path, in the SAME binary, so the capture
            // is a controlled row and the ablation has a non-source switch.
            "--tavGateOff", "--tavgateoff" -> {
                modes.set(TavGate::off, true)
            }
            // (WARM.13b) — the pre-888 straight-line spine prologue, in the SAME
            // binary, so the equivalence pin and any A/B are a controlled row.
            "--spineMaskOff", "--spinemaskoff" -> {
                modes.set(SpineMask::off, true)
            }
            "--parseAmp", "--parseamp" -> {
                i++
                if (i < args.size) {
                    FrontEnd.reset(); modes.set(FrontEnd::mode, FrontEnd.ON)
                    FlowScan.reset(); o.flowScanReport = true
                    modes.set(FrontEnd::parseAmp, args[i].toIntOrNull() ?: 0)
                }
            }
            // (FRONT.2) round 801 — the B464 reassignment-scan A/B and its
            // equivalence verifier. Both scanners are in the binary, so these
            // select an arm rather than needing a second build.
            "--flowScanLegacy", "--flowscanlegacy" -> {
                FlowScan.reset(); modes.set(FlowScan::legacy, true)
            }
            "--verifyFlowScan", "--verifyflowscan" -> {
                FlowScan.reset(); modes.set(FlowScan::verify, true); o.flowScanReport = true
            }
            "--flowScanBogus", "--flowscanbogus" -> {
                modes.set(FlowScan::bogus, true)
            }
            // (WARM.12) round 865 — the flow-node produced-vs-consumed census.
            // Counters only, never a timestamp: the minting walk cannot be
            // partitioned by timing at a price below its own size (round 864
            // § 6), so what is deletable in it is decided by population.
            "--flowCensus", "--flowcensus" -> {
                FlowCensus.reset(); modes.set(FlowCensus::on, true)
            }
            // (WARM.19) round 895 — the whole-source `indexOf` family: the ~50
            // pin walkers that ask every file whether it contains some
            // corpus-unique literal. A timestamp pair IS affordable here (a
            // scan is tens of microseconds against a ~90 ns pair), so this
            // census reports nanos as well as counts.
            "--srcScanCensus", "--srcscancensus" -> {
                SrcScan.reset(); modes.set(SrcScan::on, true)
            }
            // The pre-895 path in the SAME binary: no filter, no filter build.
            // The A/B arm and the switch `SrcScanEquivalenceTest` ablates.
            "--srcScanFilterOff", "--srcscanfilteroff" -> {
                modes.set(SrcScan::off, true)
            }
            // Runs the real scan even where the filter refused, and counts the
            // disagreements. A divergence is a soundness failure.
            "--verifySrcScan", "--verifysrcscan" -> {
                SrcScan.reset(); modes.set(SrcScan::verify, true); modes.set(SrcScan::on, true)
            }
            // The POSITIVE CONTROL for --verifySrcScan: corrupts the filter build
            // so it refuses needles that ARE present. Without it a zero
            // divergence count cannot tell a sound gate from a dead instrument
            // (round 790).
            "--srcScanBogus", "--srcscanbogus" -> {
                modes.set(SrcScan::bogus, true)
            }
            // (WARM.23) round 896 — the map-key candidate census: the three
            // in-progress sentinel sets' populations AND max live sizes (round
            // 890's law), and the perFileScope probe count.
            "--mapCensus", "--mapcensus" -> {
                MapCensus.reset(); modes.set(MapCensus::on, true)
            }
            // (WARM.23) round 759's amplification for ONE `perFileScope[path]`
            // probe: N probes under one timestamp pair; NEGATIVE N is the in-situ
            // empty bracket. Two positive N cancel the boundary.
            "--perFileScopeAmp", "--perfilescopeamp" -> {
                i++
                if (i < args.size) {
                    MapCensus.reset()
                    modes.set(MapCensus::perFileScopeReads, args[i].toIntOrNull() ?: 0)
                    modes.set(MapCensus::on, true)
                }
            }
            // (WARM.28/29) round 759's amplification for candidate (2): N probes of a
            // real `LexicalScope.symbols` map, N evaluations of the 64-bit
            // proof-of-absence filter, and N parallel-array linear scans — one
            // timestamp pair each, cyclically rotated so no arm owns a position.
            "--lexLevelAmp", "--lexlevelamp" -> {
                i++
                if (i < args.size) {
                    MapCensus.reset()
                    modes.set(MapCensus::lexLevelAmp, args[i].toIntOrNull() ?: 0)
                    modes.set(MapCensus::on, true)
                }
            }
            // (WARM.30) round 903 — the deep AST-VALUE key on `nodeTypes`: the
            // hit/miss/bypassed split, the PROBE-weighted key subtree size (round
            // 902's law) and the unindexed-key population a `(file, nodeId)`
            // successor cannot address.
            // (WARM.31) round 904 — round 899 § 33.8 candidate (6): which maps hold
            // the `Integer.equals` key-side leaf work. A per-SITE population count,
            // because what a swap returns is population x a premium shared by every
            // site in the family, so the population is the only per-site unknown.
            "--boxedKeyCensus", "--boxedkeycensus" -> {
                MapCensus.reset()
                modes.set(MapCensus::boxedKeyCensus, true)
                modes.set(MapCensus::on, true)
            }
            // (WARM.31) round 759's amplification, TWO arms (round 898: fewer arms,
            // bigger r) on `Relation.cache` — the largest non-refused member of the
            // family: the real boxed `HashMap<Long,·>` probe, and a `LongKeyMap`
            // populated in lockstep. `A - B` is the boxed-key premium the whole
            // family would be priced at.
            "--boxedKeyAmp", "--boxedkeyamp" -> {
                i++
                if (i < args.size) {
                    MapCensus.reset()
                    modes.set(MapCensus::boxedKeyAmp, args[i].toIntOrNull() ?: 0)
                    modes.set(MapCensus::on, true)
                }
            }
            // (WARM.32) the ITERATOR-ALLOCATION family: how many list iterations
            // `forEachChild` and the INV.4 edge classifiers really perform, and over
            // how many ELEMENTS — a per-call cost times a call count is the wrong
            // quantity when a third of the lists are empty.
            "--iterCensus", "--itercensus" -> {
                IterCensus.reset()
                modes.set(IterCensus::census, true)
                modes.set(IterCensus::on, true)
            }
            // (WARM.32) the iterator-vs-indexed PREMIUM, two arms under one timestamp
            // pair each, IN SITU at both populations' real call sites and in ABBA
            // rotation. A slope: run two `r` and fit p(r) = cost + boundary/r PER ARM
            // (round 904 — a single-r A - B over-reads by up to 23%).
            "--iterAmp", "--iteramp" -> {
                i++
                if (i < args.size) {
                    IterCensus.reset()
                    modes.set(IterCensus::amp, args[i].toIntOrNull() ?: 0)
                    modes.set(IterCensus::on, true)
                }
            }
            "--typeNodeKeyCensus", "--typenodekeycensus" -> {
                MapCensus.reset()
                modes.set(MapCensus::typeNodeKeyCensus, true)
                modes.set(MapCensus::on, true)
            }
            // (WARM.30) round 759's amplification, THREE arms: the real deep-key
            // probe, the same probe against a `(file, nodeId)` LongKeyMap populated
            // in lockstep, and `isPerFileDependentRefNode` — the row's second owner,
            // which every leaf profile charges to the map.
            "--typeNodeKeyAmp", "--typenodekeyamp" -> {
                i++
                if (i < args.size) {
                    MapCensus.reset()
                    modes.set(MapCensus::typeNodeKeyAmp, args[i].toIntOrNull() ?: 0)
                    modes.set(MapCensus::on, true)
                }
            }
            // (WARM.23) candidate (3): replay each file's real nodeToFlow key
            // sequence into a fresh `mutableMapOf` and a fresh LongKeyMap, N reps,
            // ABBA within the file.
            "--flowMapReplay", "--flowmapreplay" -> {
                i++
                if (i < args.size) {
                    MapCensus.reset()
                    modes.set(MapCensus::flowReplayReps, args[i].toIntOrNull() ?: 0)
                    modes.set(MapCensus::on, true)
                }
            }
            // (PERF.HW.g) round 881 — the `mergeSingleSymbol` shape, which is the
            // single blocker to sharing one bind across `--workers` checkers.
            // Counters only; `docs/parallel-bind-sharing.md` § 4 stage 1.
            "--mergeCensus", "--mergecensus" -> {
                MergeCensus.reset(); modes.set(MergeCensus::enabled, true)
            }
            // (PERF.HW.h) round 882 — stage-1 closer: fingerprint every binder
            // Symbol before the check and re-compare after, so a mutation from a
            // site nobody grepped for is still caught.
            // (PERF.HW.i) round 883 — one shared bind for all workers. Opt-in:
            // sound only while no program symbol merges into `globals`.
            // (PERF.HW.k) restore the pre-clone in-place merge — the control arm
            // for --bindMutationCheck, which the fix itself drives to zero.
            "--mergeClone", "--mergeclone" -> {
                MergeClone.reset(); modes.set(MergeClone::enabled, true)
            }
            "--shareBind", "--sharebind" -> {
                ShareBind.reset(); modes.set(ShareBind::enabled, true)
            }
            "--bindMutationCheck", "--bindmutationcheck" -> {
                BindMutationCheck.reset(); modes.set(BindMutationCheck::enabled, true)
            }
            // (WARM.22) round 875 — the INV.4 reach machinery as ONE population.
            // Counters only: the family is 43 classifiers of which the largest
            // is 0.86% of a warm rebuild, so nothing in it can be timed against
            // its own boundary (round 850) — what a design change acts on is
            // the EDGE EVALUATION count, and that is a count of structure.
            "--reachCensus", "--reachcensus" -> {
                ReachCensus.reset(); modes.set(ReachCensus::on, true)
            }
            // (WARM.33) round 906 — every ACCESS to the 45 per-file reach/depth
            // memos, fed to a set-associative model of BOTH layouts. There is no
            // clock in it: a locality change cannot be amplified (a repeated
            // probe is an L1 hit by the second repetition), so the instrument is
            // a MISS-COUNT DELTA, which is a counter and therefore deterministic.
            "--reachMemoCensus", "--reachmemocensus" -> {
                ReachMemoCensus.reset(); modes.set(ReachMemoCensus::on, true)
            }
            // (INV.1) the per-file node-answer store, Stage 1 of the inversion.
            // A checker reads the mode ONCE at construction; the counters print
            // after `time:` and are cleared there, like PassTiming's.
            "--nodeAnswers", "--nodeanswers" -> {
                NodeAnswers.reset(); modes.set(NodeAnswers::enabled, true)
            }
            // (WARM.22) — the EDGE amplifier. Arms the census too, because its
            // arithmetic falsifier prints in that table and a slope with no
            // falsifier beside it is not a measurement (round 759).
            "--reachAmp", "--reachamp" -> {
                i++
                if (i < args.size) {
                    ReachCensus.reset(); modes.set(ReachCensus::on, true)
                    modes.set(ReachCensus::amp, args[i].toIntOrNull() ?: 0)
                }
            }
            // (WARM.11) round 864 — the INV.2(b) side-table A/B. Both fills are
            // in the binary, so this selects an arm rather than needing a
            // second build (round 795: a verify flag that restores the old
            // computation is also the instrument that prices it).
            "--flowIndexLegacy", "--flowindexlegacy" -> {
                modes.set(FlowIndex::legacy, true)
            }
            "--flowEagerSet", "--floweagerset" -> {
                modes.set(FlowScan::eagerSet, true)
            }
            "--partitionCheck", "--partitioncheck" -> {
                i++; if (i < args.size) modes.set(PartitionCheck::workers, args[i].toIntOrNull() ?: 0)
            }
            "--workers" -> {
                i++; if (i < args.size) modes.set(ParallelCheckMode::workers, args[i].toIntOrNull() ?: 0)
            }
            // (AUDIT.3): price the globals-lookup population by AMPLIFICATION —
            // N reads of the same key under ONE timestamp pair, so the ~89 ns
            // pair cannot dominate the ~57 ns it is measuring. Needs the
            // instrumented table, hence --passTiming. Two runs at different N
            // solve for the per-read cost with the pair cost cancelling.
            // (WARM.14) round 867: the same escape one level up — `N` extra
            // passes over the consultations a per-kind dispatch table would
            // SKIP, under ONE timestamp pair, so `s_p` (2-14 ns, an order of
            // magnitude below a probe boundary) can be read off the slope. A
            // NEGATIVE N is the control arm, which suppresses every
            // consultation and prices the loop skeleton at the same site.
            // Behaviour-free: the amplified set is the table's SKIP set, whose
            // handlers do nothing at those kinds (round 732 § 4).
            "--spineAmp", "--spineamp" -> {
                o.passTiming = true
                i++
                if (i < args.size) {
                    SpineAmp.reset()
                    modes.set(SpineAmp::reps, args[i].toIntOrNull() ?: 0)
                }
            }
            "--globalsAmp", "--globalsamp" -> {
                o.passTiming = true
                i++
                if (i < args.size) {
                    GlobalsAmp.reset()
                    modes.set(GlobalsAmp::reads, args[i].toIntOrNull() ?: 0)
                }
            }
            // (AOT.4)(c) round 840(c): send the emitted tree somewhere other than the
            // config's outDir. The AOT trainer needs it — training WITH emit is worth
            // ~1.26 s on an emitting compile (docs/perf/aot-cache.md § 9) and a training
            // run must never write into the user's project. Ignored under --noEmit, and
            // (deliberately) not threaded through the --incremental/--watch paths.
            "--outDir", "--outdir" -> { i++; if (i < args.size) o.outDir = args[i] }
            "--project", "-p" -> { i++; if (i < args.size) o.project = args[i] }
            "--help", "-h" -> { o.help = true; return o }
            else -> if (!a.startsWith("-")) o.project = a
        }
        i++
    }
    return o
}

fun runCli(args: Array<String>): Int {
    // (SERVE.1): every debug mode an argument turns on is restored when this
    // call returns, however it returns. `main` is no longer a process's single
    // act — `CompileServer` calls this once per request inside ONE long-lived
    // JVM — so a mode left set here is a permanent, invisible reconfiguration
    // of every LATER request on that server, and several of these modes select
    // a different code path rather than merely a probe.
    val modes = ModeLedger()
    return try {
        runCliCore(args, modes)
    } finally {
        modes.restore()
    }
}

private fun runCliCore(args: Array<String>, modes: ModeLedger): Int {
    val o = parseCliArgs(args, modes)
    if (o.help) { printUsage(); return 0 }
    val project = o.project
    val outDir = o.outDir
    val noEmit = o.noEmit
    val listAll = o.listAll
    val watch = o.watch
    val watchVerify = o.watchVerify
    val incremental = o.incremental
    val passTiming = o.passTiming
    val flowScanReport = o.flowScanReport

    println("xemantic-typescript-compiler — whole-project build")
    println(
        "project: $project${if (noEmit) "  (noEmit)" else ""}" +
            if (outDir != null && !noEmit) "  (outDir: $outDir)" else "",
    )

    if (passTiming) {
        PassTiming.reset()
        modes.set(PassTiming::enabled, true)
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
    // (SERVE.1): the report is an ACCUMULATING list, not a mode, so the ledger
    // cannot put it back — and left standing it is printed again by every later
    // request on a warm server, which is the one leak in this function visible
    // in the response TEXT rather than only in the flag.
    PartitionCheck.reportLines.clear()
    println("time:    ${duration.inWholeMilliseconds} ms")
    if (NodeAnswers.enabled) {
        // (INV.1) the receipt, then the counters dropped — the ledger restores
        // the FLAG; counters are each object's own reset(), as for PassTiming.
        println("nodeAnswers: recorded ${NodeAnswers.recordedTotal} expression type(s) in ${NodeAnswers.filesTotal} file(s)")
        NodeAnswers.reset()
    }
    if (passTiming) PassTiming.dump(::println)
    if (FltmCensus.on) print(FltmCensus.report())
    if (passTiming) {
        // (SERVE.1): every instrumentation FLAG this request turned on —
        // `enabled`, `verifyMappedCache`, `callerAttr`, the `--passTimingRows`
        // tiers, `FltmCensus.on`, `GlobalsAmp.reads` — is put back by the
        // ledger's `restore()` when this call returns, to the value it HELD
        // rather than to a guessed default (round 619). What is left here is the
        // one thing a ledger cannot do: DROP the counters and the
        // multi-million-entry distinct-node set the dump has just consumed.
        // `reset()` deliberately does not touch
        // `enabled`/`censusMode`/`disabledPasses`.
        PassTiming.reset()
    }
    if (SpineDispatch.mode == SpineDispatch.PROBE) {
        println(SpineDispatch.report())
        println("== (DISPATCH.1) csv ==")
        print(SpineDispatch.csv())
        println("== (DISPATCH.1) csv end ==")
    }
    if (SpineSections.mode == SpineSections.ON) {
        println(SpineSections.report())
        println("== (SPINE.1) csv ==")
        print(SpineSections.csv())
        println("== (SPINE.1) csv end ==")
    }
    if (CallSections.mode != CallSections.OFF) {
        println(CallSections.report())
        println("== (CALL.1) csv ==")
        print(CallSections.csv())
        println("== (CALL.1) csv end ==")
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
    }
    if (NarrowSections.mode != NarrowSections.OFF) {
        println(NarrowSections.report())
        println("== (CALL.3) csv ==")
        print(NarrowSections.csv())
        println("== (CALL.3) csv end ==")
    }
    if (FrontEnd.mode != FrontEnd.OFF) {
        println(FrontEnd.report())
        println("== (FRONT.1) csv ==")
        print(FrontEnd.csv())
        println("== (FRONT.1) csv end ==")
    }
    if (flowScanReport) {
        print(FlowScan.report())
    }
    if (ReachCensus.on) {
        print(ReachCensus.report())
        println("== (WARM.22) csv ==")
        print(ReachCensus.csv())
        println("== (WARM.22) csv end ==")
    }
    if (FlowCensus.on) {
        print(FlowCensus.report())
    }
    if (SrcScan.on) {
        print(SrcScan.report())
    }
    if (MapCensus.on) {
        print(MapCensus.report())
    }
    if (IterCensus.on) {
        print(IterCensus.report())
    }
    if (IanySections.mode != IanySections.OFF) {
        println(IanySections.report())
        println("== (IANY.1) csv ==")
        print(IanySections.csv())
        println("== (IANY.1) csv end ==")
    }
    if (LibTypeCensus.enabled) {
        println(LibTypeCensus.report())
    }
    if (CtaSections.mode != CtaSections.OFF) {
        println(CtaSections.report())
        println("== (TYPE.2) csv ==")
        print(CtaSections.csv())
        println("== (TYPE.2) csv end ==")
    }
    if (CpaSections.mode != CpaSections.OFF || CpaSections.verifyLoopRetry ||
        CpaSections.verifyDeferSuppression || CpaSections.verifyUnionRetry
    ) {
        println(CpaSections.report())
        println("== (ENGINE.2) csv ==")
        print(CpaSections.csv())
        println("== (ENGINE.2) csv end ==")
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
    println(usageText())
}

/**
 * The `--help` text.
 *
 * A function rather than an inline `println` because it is the CLI's own
 * registry of what flags exist: `CliModeRestoreTest` reads every `--flag` token
 * out of it and drives [parseCliArgs] with the lot, so a documented flag that
 * leaks a mode fails the suite. The residual that leaves — an UNDOCUMENTED
 * flag is not driven, and so not covered — is the reason the same test also
 * asserts that a named list of the behaviour-changing modes was actually
 * reached by that sweep.
 */
internal fun usageText(): String =
        """
        Usage: xemantic-typescript-compiler [--project|-p <dir-or-tsconfig>] [--noEmit] [path]

          path / --project   directory containing tsconfig.json, or a tsconfig path (default: .)
          --noEmit           type-check only; do not write outputs
          --outDir <dir>     write the emitted tree here instead of the config's outDir
                             (relative to the CWD; inert under --noEmit/--incremental)
          --listAll          print every error (default: first 30) — for run-to-run FP diffing
          --passTiming       print the INV.0 per-pass wall-time table + recompute counters
          --passTimingRows   as --passTiming but ONLY the pass rows (no per-call counters, no
                             profiled spine walk) — the cheap tier for a WARM table, (WARM.1)(c)
          --passTimingSpine  as --passTimingRows plus the per-node SPINE sub-rows
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
          --callSectionsCoarse the same, anchors only — the differential calibration counterpart
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
          --libTypeCensus    (WARM.3) prize measurement for a process-global LIB TYPE cache:
                             produced-vs-consumed at the declaredTypes / member-table mint
                             boundaries, plus outermost-mint nanos split lib vs non-lib
          --verifyLoopRetry  (ENGINE.2d)(a) keep the round-425 loop-entry retry and COUNT
                             every call where skipping it would change the answer
          --verifyLoopRetryAll  the same over EVERY retry call, including the loop-crossing
                             ones the gate never skips — the control that makes the zero live
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
          --ccetPreGate      (ENGINE.2g) compute the call-expression prologue pre-gate WITHOUT
                             honouring it, and count how many skipped calls emit
                             (--ccetPreGateBogus = control)
          --cmamPreGate      (ENGINE.2e) compute checkMemberAccessMissing's whole-function
                             pre-gate WITHOUT honouring it: prints the population it would
                             skip, the body time behind it, and how many of those calls
                             emit — the falsifier (--cmamPreGateBogus = control)
          --frontEnd         (FRONT.1) front-end attribution: config / crawl / parse / imports / bind
          --parseCacheOff    (WARM.19) do not reuse a crawl parse across ProjectCompiler.build
                             calls in this process — the in-binary OFF arm of the cross-request
                             parse cache (inert for a one-shot CLI, which builds once)
          --parseAmp N       (WARM.19) N EXTRA crawl pre-parses per file, inside the crawl span;
                             arms --frontEnd. Two values of N cancel the crawl's fixed floor and
                             give the wall cost of ONE parse round = the ceiling on a
                             cross-request parse cache in the --serve daemon
          --tavOff           (WARM.21) skip the TAV per-identifier pass (TS2693/TS2708) entirely
                             — a MEASUREMENT arm read off the whole-rebuild wall, never
                             production; its falsifier is that those diagnostics disappear
          --tavGateOff       (WARM.21) restore the pre-874 TAV path: every reached identifier
                             pays the reach walk, the level lookup and the chain queries, with
                             no name-candidate gate in front. The in-binary OFF arm that makes
                             the capture a controlled row
          --spineMaskOff     (WARM.13b) restore the pre-888 spine prologue: all 46 enter
                             handlers consulted at every node, with no per-kind skip mask.
                             The in-binary OFF arm for the equivalence pin and the A/B
          --flowScanLegacy   (FRONT.2) run the pre-801 B464 reassignment scanner (A/B arm)
          --verifyFlowScan   (FRONT.2) run BOTH scanners and report divergences
          --flowScanBogus    (FRONT.2) positive control: corrupt the fast scanner
          --flowEagerSet     (FRONT.2) build B464 suffix sets eagerly (pre-801 arm)
          --flowIndexLegacy  (WARM.11) build the INV.2(b) side table by the pre-864 whole-tree walk
          --flowCensus       (WARM.12) flow nodes minted vs ever read by any checker consumer
          --srcScanCensus    (WARM.19) the whole-source substring-scan family: calls, characters
                             scanned, characters the n-gram filter refused, and the nanos of
                             both mechanisms
          --srcScanFilterOff (WARM.19) restore the pre-895 path — every whole-source scan runs
                             with no n-gram filter and no filter build. The in-binary OFF arm
                             for the equivalence pin and the A/B
          --verifySrcScan    (WARM.19) run the real scan even where the filter refused and count
                             the disagreements; a divergence is a soundness failure
          --srcScanBogus     (WARM.19) positive control: corrupt the filter build so it refuses
                             needles that ARE present, which --verifySrcScan must then see
          --mapCensus        (WARM.23) the map-key candidates round 894 ranked: the three
                             in-progress sentinel sets (adds, re-entries and MAX LIVE SIZE)
                             and the perFileScope file-PATH probe count
          --perFileScopeAmp N (WARM.23) price one perFileScope[path] probe: N probes under one
                             timestamp pair; NEGATIVE N is the in-situ empty bracket
          --lexLevelAmp N    (WARM.28/29) price one LexicalScope.symbols probe, the 64-bit
                             proof-of-absence filter, AND a parallel-array linear scan: N of each
                             under one timestamp pair, cyclically rotated — the boundary cancels
                             between the arms
          --typeNodeKeyCensus (WARM.30) the nodeTypes deep AST-value key: hit/miss/bypassed,
                             the PROBE-weighted key subtree size, and the unindexed keys a
                             (file, nodeId) successor cannot address
          --boxedKeyCensus   (WARM.31) the residual boxed-primitive map/set keys: per-SITE
                             operation counts, max live size and key range, against the
                             ~1.7 M ops one site needs to be worth a swap on its own
          --boxedKeyAmp N    (WARM.31) the boxed-key PREMIUM in two arms under one timestamp
                             pair each, on Relation.cache: the real HashMap<Long,·> probe and
                             a LongKeyMap populated in lockstep — A - B is what a swap returns
          --iterCensus       (WARM.32) the iterator-allocation family: how many list iterations
                             forEachChild's 70 child positions and the INV.4 edge classifiers'
                             145 identity tests perform, over how many elements, with the
                             empty/singleton split and the concrete List classes
          --iterAmp N        (WARM.32) the iterator-vs-indexed PREMIUM in two arms under one
                             timestamp pair each, in situ at both populations' real call sites —
                             run two N and fit p(r) = cost + boundary/r per arm
          --typeNodeKeyAmp N (WARM.30) price it in three arms under one timestamp pair each:
                             the real deep-key probe, a (file, nodeId) LongKeyMap populated in
                             lockstep, and isPerFileDependentRefNode — the row's second owner
          --flowMapReplay N  (WARM.23) replay each file's real nodeToFlow key sequence into a
                             fresh mutableMapOf and a fresh LongKeyMap, N reps, ABBA per file —
                             what a container swap RECOVERS, not what the old one costs
          --mergeClone       (PERF.HW.k) copy a binder-owned symbol before the merge writes to it
                             (tsc/tsgo design). EXPERIMENTAL: sound only once the getMergedSymbol
                             forwarding table lands — see docs/parallel-bind-sharing.md
          --shareBind        (PERF.HW.i) under --workers, bind the program ONCE and share it with
                             every worker instead of N full binds. Sound for an ALL-MODULE program
                             (docs/parallel-bind-sharing.md); a program with global script files
                             mutates binder output and must not use it
          --bindMutationCheck (PERF.HW.h) does the checker mutate binder-owned Symbol state
                             anywhere at all? Fingerprints every reachable Symbol before the
                             check and re-compares after (docs/parallel-bind-sharing.md)
          --mergeCensus      (PERF.HW.g) mergeSingleSymbol's shape: how many binder Symbols the
                             checker ADOPTS by reference versus mutates, and how many of those
                             mutations reach binder-owned state — the blocker to sharing one
                             bind across --workers checkers (docs/parallel-bind-sharing.md)
          --reachCensus      (WARM.22) per-classifier census of the INV.4 reach machinery:
                             consultations, memo hits, ascents and EDGE EVALUATIONS per
                             classifier — the population any change to the family acts on
          --reachAmp N       (WARM.22) N EXTRA evaluations of two reach EDGE predicates (49
                             and 106 arms) per fold, under one timestamp pair; arms
                             --reachCensus. Two values of N cancel the boundary and give the
                             cost of ONE edge evaluation, and the arm-count pair gives its
                             slope in arms
          --nodeAnswers      (INV.1) record the walk's own type answer for every expression into
                             a per-file store (Stage 1 of docs/INVERSION-DESIGN.md); prints the
                             recorded-node count after `time:` — the flag-on cost receipt
          --reachMemoCensus  (WARM.33) every ACCESS to the 45 per-file INV.4 reach/depth memos
                             (probe, ascent, write), the per-node consultation histogram, and a
                             set-associative LRU model of BOTH layouts — 45 arrays versus one
                             row per node — reported as a miss-count delta per cache level
          --typeOfExprCallers  (TYPE.1) attribute the getTypeOfExpression calls by CALLER + co-occurrence
          --verifyMappedCache  recompute every served INV.5(c) mapped-cache hit and split
                             shape-different serves from id-only ones (implies --passTiming)
          --incremental      persist/reuse tsconfig.xtsbuildinfo across processes (recheck only changes under --noEmit)
          --watch, -w        stay running and rebuild on file changes (incremental recheck under --noEmit)
          --watchVerify      --watch + diff every incremental result against a full rebuild (INV.7(d1) gate)
          --partitionCheck N run N sequential partition checkers and diff vs the full run (INV.6(6b))
          --workers N        parallel share-nothing partition check on N threads (INV.6(6c); line order may differ)
          --globalsAmp N     (AUDIT.3) price one globals[name] probe: N reads under one timestamp pair (implies --passTiming)
          --spineAmp N       (WARM.14) price one REJECTING spine handler consultation: N extra skipped-handler passes
                             per node under one timestamp pair; NEGATIVE N is the control arm (implies --passTiming)
          --fltmCensus       (SETUP.2) produced-vs-consumed census of buildFileLocalTypeMaps (implies --passTiming)
                             + the INV.3(a) globals-lookup conflation classification
          --help, -h         show this help
        """.trimIndent()
