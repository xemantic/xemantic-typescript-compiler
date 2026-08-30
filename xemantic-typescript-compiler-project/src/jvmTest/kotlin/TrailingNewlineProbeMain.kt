/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 */

package com.xemantic.typescript.compiler.project

import com.xemantic.typescript.compiler.Diagnostic
import java.io.File

/**
 * Sweep the number of TRAILING NEWLINES in one file and report every diagnostic
 * set that differs from the modal one.
 *
 * Two arms, because they attribute the defect to different halves:
 *  - `fresh` — a NEW [Project] per step, so the answer is a function of the TEXT
 *    alone and of nothing this class retains;
 *  - `incr` — ONE [Project] edited step by step, which is what an editor does.
 *
 * A row that appears in `fresh` is a whole-program checking defect keyed on
 * something the trailing whitespace moves (the last statement's `end` is the only
 * span in a file that trailing whitespace moves at all). A row that appears only
 * in `incr` is an incremental-state defect.
 *
 * ```
 * java -cp <classes:deps> \
 *   com.xemantic.typescript.compiler.project.TrailingNewlineProbeMainKt \
 *   <projectDir> <fileSuffix> <maxNewlines> [arms]
 * ```
 */
fun main(args: Array<String>) {
    require(args.size >= 3) { "usage: <projectDir> <fileSuffix> <maxNewlines> [fresh|incr|both]" }
    val dir = args[0]
    val suffix = args[1]
    val max = args[2].toInt()
    val arms = if (args.size > 3) args[3] else "both"

    val probe = Project.open(dir)
    val target = probe.files.firstOrNull { it.endsWith(suffix) }
        ?: error("no program file ends with '$suffix'; files=${probe.files.take(20)}")
    println("target=$target programFiles=${probe.files.size}")
    probe.close()

    val onDisk = File(target).readText()
    val base = onDisk.trimEnd('\n') + "\n"

    fun sig(ds: List<Diagnostic>): String = ds
        .map { "${it.code}@${it.fileName?.substringAfterLast('/')}:${it.line}:${it.character}" }
        .sorted().joinToString(",")

    fun detail(text: String, ds: List<Diagnostic>): String = ds.joinToString("\n") { d ->
        val s = d.start ?: -1
        val l = d.length ?: 0
        val span = if (d.fileName == target && s >= 0 && s + l <= text.length)
            text.substring(s, (s + l).coerceAtMost(text.length)).replace("\n", "\\n")
        else "<other file or no span>"
        "      TS${d.code} ${d.fileName?.substringAfterLast('/')}:${d.line}:${d.character} " +
            "start=$s len=$l span=[${span.take(90)}] ${d.message.take(160)}"
    }

    fun report(arm: String, results: List<Triple<Int, String, List<Diagnostic>>>) {
        val modal = results.groupingBy { it.second }.eachCount().maxByOrNull { it.value }!!.key
        println("--- arm=$arm steps=${results.size} modalRows=${modal.count { it == ',' } + if (modal.isEmpty()) 0 else 1}")
        println("    modal=[${modal.take(200)}]")
        var odd = 0
        for ((n, s, ds) in results) {
            if (s == modal) continue
            odd++
            println("    n=$n DIFFERS rows=${ds.size}")
            println(detail(base + "\n".repeat(n), ds))
        }
        println("--- arm=$arm divergentSteps=$odd of ${results.size}")
    }

    if (arms == "fresh" || arms == "both") {
        val results = ArrayList<Triple<Int, String, List<Diagnostic>>>()
        for (n in 0..max) {
            File(target).writeText(base + "\n".repeat(n))
            val p = Project.open(dir)
            val ds = try { p.diagnostics() } finally { p.close() }
            results.add(Triple(n, sig(ds), ds))
        }
        report("fresh", results)
    }

    if (arms == "incr" || arms == "both") {
        File(target).writeText(base)
        val p = Project.open(dir)
        p.diagnostics() // warm; the first build in a process is its own thing
        val results = ArrayList<Triple<Int, String, List<Diagnostic>>>()
        for (n in 0..max) {
            p.updateFile(target, base + "\n".repeat(n))
            val ds = p.diagnostics()
            results.add(Triple(n, sig(ds), ds))
        }
        p.close()
        report("incr", results)
    }

    if (arms == "of" || arms == "both" || arms == "ide") {
        File(target).writeText(base)
        val p = Project.open(dir)
        p.diagnostics()
        val results = ArrayList<Triple<Int, String, List<Diagnostic>>>()
        for (n in 0..max) {
            p.updateFile(target, base + "\n".repeat(n))
            val ds = p.diagnosticsOf(listOf(target))
            results.add(Triple(n, sig(ds), ds))
        }
        p.close()
        report("diagnosticsOf", results)
    }

    if (arms == "ide") {
        // What an IntelliJ host really does per keystroke: annotate the buffer, then
        // prepare it for hover on idle, then ask again — the sequence that keeps a
        // live checker retained across queries.
        File(target).writeText(base)
        val p = Project.open(dir)
        p.diagnostics()
        val results = ArrayList<Triple<Int, String, List<Diagnostic>>>()
        for (n in 0..max) {
            p.updateFile(target, base + "\n".repeat(n))
            val a = p.diagnosticsOf(listOf(target))
            p.prepare(listOf(target))
            val b = p.diagnosticsOf(listOf(target))
            val c = p.diagnostics(target)
            val ds = (a + b + c).distinct()
            results.add(Triple(n, sig(ds), ds))
        }
        p.close()
        report("ideSequence", results)
    }

    if (arms == "shift") {
        // Newlines at the TOP: every node in the file moves, which is the widest
        // exposure of any cross-file position-keyed collision there is. FRESH
        // project per step, so anything found is a function of the TEXT alone.
        val results = ArrayList<Triple<Int, String, List<Diagnostic>>>()
        for (n in 0..max) {
            val content = "\n".repeat(n) + base
            File(target).writeText(content)
            val p = Project.open(dir)
            val ds = try { p.diagnostics() } finally { p.close() }
            results.add(Triple(n, sig(ds), ds))
            if (ds.isNotEmpty()) println("  shift n=$n rows=${ds.size}\n" + detail(content, ds))
        }
        report("shiftFresh", results)
    }

    if (arms == "shiftIncr") {
        File(target).writeText(base)
        val p = Project.open(dir)
        p.diagnostics()
        val results = ArrayList<Triple<Int, String, List<Diagnostic>>>()
        for (n in 0..max) {
            val content = "\n".repeat(n) + base
            p.updateFile(target, content)
            val ds = p.diagnosticsOf(listOf(target))
            results.add(Triple(n, sig(ds), ds))
            if (ds.isNotEmpty()) println("  shiftIncr n=$n rows=${ds.size}\n" + detail(content, ds))
        }
        p.close()
        report("shiftIncr", results)
    }

    if (arms == "cycles") {
        // IDEMPOTENCE. The content returns to the SAME few states over and over,
        // exactly as an editor's buffer does, while the project accumulates
        // incremental state. If an answer for one state changes between cycles,
        // the defect is retained state and not the text.
        File(target).writeText(base)
        val p = Project.open(dir)
        p.diagnostics()
        val states = (0..3).map { base + "\n".repeat(it) }
        val seen = HashMap<Int, MutableSet<String>>()
        for (cycle in 0 until max) {
            for ((i, text) in states.withIndex()) {
                p.updateFile(target, text)
                val a = p.diagnosticsOf(listOf(target))
                p.prepare(listOf(target))
                p.quickInfoAt(target, text.indexOf("scenarios") + 2)
                val b = p.diagnostics()
                val ds = (a + b).distinct()
                val s = sig(ds)
                if (seen.getOrPut(i) { LinkedHashSet() }.add(s) && seen[i]!!.size > 1) {
                    println("  cycle=$cycle state=$i NEW ANSWER rows=${ds.size}")
                    println(detail(text, ds))
                }
            }
        }
        p.close()
        for ((i, s) in seen) println("state=$i distinctAnswers=${s.size} ${if (s.size > 1) "UNSTABLE" else "stable"}")
    }

    File(target).writeText(onDisk)
    println("restored $target")
}
