/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 */

package com.xemantic.typescript.compiler

import kotlin.system.measureNanoTime

/**
 * (INC.82) MEASUREMENT-ONLY: price the three named members of the crawl's
 * ~9 ms concurrent half, in ISOLATION. An isolated per-operation probe is an
 * UPPER BOUND on a removal, never the removal.
 *
 * One arm per invocation is not possible for micro-arms without paying a whole
 * JVM per number, so the arms are ABBA-rotated WITHIN the process and the
 * script rotates the ARM ORDER across processes as well.
 */
fun main(args: Array<String>) {
    val dir = args.getOrNull(0) ?: "build/bench/many-small-2400-dom"
    val reps = args.getOrNull(1)?.toInt() ?: 12
    val reversed = args.getOrNull(2) == "rev"

    val files = ArrayList<String>()
    fun walk(d: String) {
        for (e in SystemVfs.listEntries(d).sortedBy { it.path }) {
            if (e.isDirectory) walk(e.path) else if (e.path.endsWith(".ts")) files.add(e.path)
        }
    }
    walk("$dir/src")
    val contents = files.map { SystemVfs.readText(it) ?: "" }
    val bytes = contents.sumOf { it.length.toLong() }
    val awaitFiles = contents.count { it.contains("await") }
    println("files=${files.size} chars=$bytes awaitFiles=$awaitFiles")

    // Fresh, equal String instances — what a real re-read hands the cache.
    val copies = contents.map { String(it.toCharArray()) }
    // A content set that DOES contain `await` — the population the regex needs.
    val awaited = contents.map { "$it\nexport async function z() { await Promise.resolve(); }\n" }

    val esnext = CompilerOptions(module = ModuleKind.ESNext)
    val cjs = CompilerOptions(module = ModuleKind.CommonJS)
    println("effectiveModule esnext=${esnext.effectiveModule} cjs=${cjs.effectiveModule}")

    // Candidate 3's population: the real specifier lists, as the crawl sees them.
    val specLists: List<List<String>> = contents.map { c ->
        Regex("""from\s+"([^"]+)"""").findAll(c).map { it.groupValues[1] }.toList()
    }
    println("specifiers total=${specLists.sumOf { it.size }} maxPerFile=${specLists.maxOf { it.size }}")

    var sink = 0L
    val samples = HashMap<String, MutableList<Double>>()
    fun record(a: String, n: Long) { samples.getOrPut(a) { ArrayList() }.add(n / 1_000_000.0) }

    val arms = listOf(
        "flagsES", "flagsCJS", "flagsCJSawait", "eqIdentity", "eqFreshEqual", "specSet", "assocBy"
    )
    fun run(arm: String): Long = when (arm) {
        "flagsES" -> {
            var s = 0L
            for (i in files.indices) s += computeParserFlags(files[i], contents[i], esnext).hashCode().toLong()
            s
        }
        "flagsCJS" -> {
            var s = 0L
            for (i in files.indices) s += computeParserFlags(files[i], contents[i], cjs).hashCode().toLong()
            s
        }
        "flagsCJSawait" -> {
            var s = 0L
            for (i in files.indices) s += computeParserFlags(files[i], awaited[i], cjs).hashCode().toLong()
            s
        }
        "eqIdentity" -> {
            var s = 0L
            for (i in contents.indices) if (contents[i] == contents[i]) s++
            s
        }
        "eqFreshEqual" -> {
            var s = 0L
            for (i in contents.indices) if (contents[i] == copies[i]) s++
            s
        }
        "specSet" -> {
            var s = 0L
            for (l in specLists) s += l.toSet().size.toLong()
            s
        }
        "assocBy" -> {
            val idx = files.associateBy { it }
            var s = 0L
            for (p in files) s += idx.getValue(p).length.toLong()
            s
        }
        else -> 0L
    }

    val order = if (reversed) (arms.reversed() + arms) else (arms + arms.reversed())
    repeat(reps) {
        for (arm in order) {
            val n = measureNanoTime { sink += run(arm) }
            record(arm, n)
        }
    }
    for (arm in arms) {
        val xs = samples.getValue(arm).sorted().drop(2)
        println("ARM $arm median=${"%.4f".format(xs[xs.size / 2])} ms  min=${"%.4f".format(xs.first())} max=${"%.4f".format(xs.last())} n=${xs.size}")
    }
    println("sink=$sink")
}
