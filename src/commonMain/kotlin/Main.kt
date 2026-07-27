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
    var project = "."
    var noEmit = false
    var listAll = false
    var watch = false
    var watchVerify = false
    var incremental = false
    var passTiming = false
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
            "--partitionCheck", "--partitioncheck" -> {
                i++; if (i < args.size) PartitionCheck.workers = args[i].toIntOrNull() ?: 0
            }
            "--workers" -> {
                i++; if (i < args.size) ParallelCheckMode.workers = args[i].toIntOrNull() ?: 0
            }
            "--project", "-p" -> { i++; if (i < args.size) project = args[i] }
            "--help", "-h" -> { printUsage(); return }
            else -> if (!a.startsWith("-")) project = a
        }
        i++
    }

    println("xemantic-typescript-compiler — whole-project build")
    println("project: $project${if (noEmit) "  (noEmit)" else ""}")

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
        else ProjectCompiler(SystemVfs).build(project, noEmit)
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
    println(if (result.errorCount == 0) "OK — 0 errors" else "FAILED — ${result.errorCount} error(s)")
    if (watch) runWatchLoop(project, result.configPath, noEmit, listAll, watchVerify, result)
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
          --listAll          print every error (default: first 30) — for run-to-run FP diffing
          --passTiming       print the INV.0 per-pass wall-time table + recompute counters
          --dispatchProbe    (DISPATCH.1) per-handler/per-kind spine attribution
          --dispatchGated    (DISPATCH.1) run only the derived per-kind handler table
          --spineSections    (SPINE.1) intra-handler attribution of the two hot leaves
          --callSections     (CALL.1) intra-function attribution of checkSingleCallExpressionTypes
          --argSections      (CALL.2) intra-function attribution of checkArgumentsAgainstSignature
          --argSectionsCoarse  the same, anchors only — the differential calibration counterpart
          --narrowSections   (CALL.3) intra-walk attribution of narrowTypeFromFlow (arrivals vs distinct)
          --narrowSectionsCoarse  the same, whole-walk anchor only — the calibration counterpart
          --incremental      persist/reuse tsconfig.xtsbuildinfo across processes (recheck only changes under --noEmit)
          --watch, -w        stay running and rebuild on file changes (incremental recheck under --noEmit)
          --watchVerify      --watch + diff every incremental result against a full rebuild (INV.7(d1) gate)
          --partitionCheck N run N sequential partition checkers and diff vs the full run (INV.6(6b))
          --workers N        parallel share-nothing partition check on N threads (INV.6(6c); line order may differ)
                             + the INV.3(a) globals-lookup conflation classification
          --help, -h         show this help
        """.trimIndent()
    )
}
