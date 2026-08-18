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

package com.xemantic.typescript.compiler.project

import com.xemantic.typescript.compiler.CompilerOptions
import com.xemantic.typescript.compiler.computeParserFlags
import java.io.File

/**
 * (GATE.2) [TokenIndexInvariants] pointed at a LOCAL tree of real TypeScript.
 *
 * ## Why this is a `main` and not a test
 *
 * The corpus it is useful on — `build/bench/tsc-project-*`, the 78 files of tsc's
 * own sources, which is where `(BUG.2)` actually showed — is a **local artifact**.
 * It does not exist on a fresh checkout and it does not exist in CI, so a test that
 * read it would PASS QUIETLY wherever the files are absent, which is precisely the
 * failure mode this repo has paid for twice (round 853's frozen classpath, round
 * 873's dead daemon: a green run that tested nothing). Anything conditional on a
 * local artifact must therefore refuse rather than skip, and a refusal belongs in a
 * runner rather than in the suite.
 *
 * The permanent, hermetic half of the gate is `TokenIndexGateTest`, which runs the
 * same [TokenIndexInvariants] over corpora that exist wherever the build does.
 *
 * ## Running it
 *
 * `scripts/round920-token-gate.sh`, which resolves the module's test classpath and
 * REFUSES a directory that holds no TypeScript. Directly:
 *
 * ```
 * java -cp <classpath> com.xemantic.typescript.compiler.project.RealSourceTokenGateMainKt <dir>...
 * ```
 *
 * Exit code 1 on any violation, 2 when an argument names nothing usable — never 0
 * for "there was nothing to check".
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: RealSourceTokenGateMain [--time] <dir-or-file>...")
        kotlin.system.exitProcess(2)
    }
    if (args[0] == "--time") {
        timeIndexBuild(args.drop(1))
        return
    }
    val files = ArrayList<File>()
    for (argument in args) {
        val root = File(argument)
        if (!root.exists()) {
            println("REFUSED: '$argument' does not exist — this runner is local-only and will not pass quietly")
            kotlin.system.exitProcess(2)
        }
        if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".ts") || it.name.endsWith(".tsx")) }
                .forEach { files.add(it) }
        } else {
            files.add(root)
        }
    }
    if (files.isEmpty()) {
        println("REFUSED: no .ts/.tsx under ${args.joinToString(" ")}")
        kotlin.system.exitProcess(2)
    }
    files.sortBy { it.path }

    val options = CompilerOptions()
    var characters = 0L
    var tokens = 0L
    var identifiers = 0L
    var longest = 0
    var longestIn = ""
    val violations = ArrayList<TokenIndexInvariants.Violation>()
    val byRule = LinkedHashMap<TokenIndexInvariants.Rule, Int>()
    val filesWithViolations = ArrayList<String>()

    for (file in files) {
        val text = file.readText()
        val report = TokenIndexInvariants.check(
            text,
            file.path,
            computeParserFlags(file.path, text, options),
        )
        characters += report.characters
        tokens += report.tokens
        identifiers += report.identifiers
        if (report.longestToken > longest) {
            longest = report.longestToken
            longestIn = file.path
        }
        if (report.violations.isNotEmpty()) {
            filesWithViolations.add(file.path)
            for (violation in report.violations) {
                byRule[violation.rule] = (byRule[violation.rule] ?: 0) + 1
                if (violations.size < 400) violations.add(violation)
            }
        }
        println(
            "${file.path}: ${report.characters} chars, ${report.tokens} tokens, " +
                "${report.identifiers} identifiers, longest token ${report.longestToken}, " +
                "${report.violations.size} violation(s)",
        )
    }

    println()
    println("=== ${files.size} file(s), $characters chars, $tokens tokens, $identifiers identifiers ===")
    println("longest token $longest in $longestIn")
    println("files with violations: ${filesWithViolations.size} of ${files.size}")
    for ((rule, count) in byRule) println("  $rule: $count (capped per file)")
    if (violations.isNotEmpty()) {
        println()
        println("--- first ${violations.size} violation(s) ---")
        for (violation in violations) println(violation)
        kotlin.system.exitProcess(1)
    }
    println("ALL INVARIANTS HOLD")
}

/**
 * (GATE.2) What taking the contextual lexemes from the parse COSTS, measured as the
 * whole `SourceIndex.of` — parse, scan and the extra tree walk — with the oracle on
 * and off on the same files in one process.
 *
 * The question is only about a HOST's position query: nothing in the compile path
 * builds a `SourceIndex`, which is why `cost_gate.py` reads +0.00% on all 20
 * counters for this round. The extra work is one iterative walk over the file's own
 * AST, i.e. the same order as the parse that just produced it.
 *
 * Interleaved arms and a discarded first round, because a single before/after pair
 * on a JIT-warming process measures the warm-up (CLAUDE.md's warm A/B protocol);
 * five rounds is enough for a quantity this size and the point is the RATIO.
 */
private fun timeIndexBuild(targets: List<String>) {
    val files = ArrayList<File>()
    for (argument in targets) {
        val root = File(argument)
        if (!root.exists()) {
            println("REFUSED: '$argument' does not exist")
            kotlin.system.exitProcess(2)
        }
        if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".ts") }.forEach { files.add(it) }
        } else {
            files.add(root)
        }
    }
    if (files.isEmpty()) {
        println("REFUSED: no .ts under ${targets.joinToString(" ")}")
        kotlin.system.exitProcess(2)
    }
    val options = CompilerOptions()
    val sources = files.map { Triple(it.path, it.readText(), 0) }
        .map { (path, text, _) -> Triple(path, text, computeParserFlags(path, text, options)) }
    val characters = sources.sumOf { it.second.length }
    var on = Long.MAX_VALUE
    var off = Long.MAX_VALUE
    repeat(6) { round ->
        for (oracle in listOf(true, false)) {
            val start = System.nanoTime()
            for ((path, text, flags) in sources) SourceIndex.of(text, path, flags, oracle)
            val elapsed = System.nanoTime() - start
            // The first round is the JIT ramp and is run for both arms, so neither
            // is credited with warming the other, and recorded for neither.
            if (round > 0) {
                if (oracle) on = minOf(on, elapsed) else off = minOf(off, elapsed)
            }
        }
    }
    println("${files.size} file(s), $characters chars")
    println("SourceIndex.of, oracle ON : ${on / 1_000_000} ms")
    println("SourceIndex.of, oracle OFF: ${off / 1_000_000} ms")
    println("the oracle costs ${(on - off) / 1_000_000} ms (${(on - off) * 100.0 / off}%)")
}
