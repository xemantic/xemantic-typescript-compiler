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

// NOT `com.xemantic.typescript.compiler`, and NOT `...compiler.bench` either — this file
// declares a top-level `main(Array<String>)` and so do `src/commonMain/kotlin/Main.kt`
// and `BenchMain.kt`. On the JVM those are distinct classes (`MainKt` / `BenchMainKt` /
// `CorpusScreenMainKt`) and coexist; Kotlin/Native mangles a top-level function to
// `kfun:<package>#main(kotlin.Array<kotlin.String>){}` with NO file component, so two in
// one package are ONE symbol and the test link becomes a hard `ld.lld: duplicate symbol`
// (round 775). Every commonTest entry point needs its own package.
package com.xemantic.typescript.compiler.corpus

import com.xemantic.typescript.compiler.DEFAULT_CASES_DIR
import com.xemantic.typescript.compiler.TypeScriptCompiler
import com.xemantic.typescript.compiler.errorsMatchBaseline
import com.xemantic.typescript.compiler.readText
import com.xemantic.typescript.compiler.sameAs
import com.xemantic.typescript.compiler.toBaseline
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.measureTime

/**
 * **A FAST SCREEN FOR BLAST-RADIUS MEASUREMENT. NEVER A REPLACEMENT FOR THE GRADLE
 * SUITE, WHICH REMAINS THE COMMIT GATE.**
 *
 * This driver re-runs every ACTIVE (non-`@Ignore`d) generated corpus subtest of BOTH
 * baseline channels — the `.errors.txt` diagnostics channel and the `.js` EMIT channel —
 * in ONE JVM, outside Gradle, in ~90 seconds against the ~4 minutes a full `jvmTest`
 * costs. Its whole purpose is to turn a family round's question *"how many green
 * baselines would this rule move?"* from an argument into a measurement, cheaply enough
 * to ask it of a THROWAWAY build before any of the work is committed to.
 *
 * ## The two channels
 *
 * The generator emits exactly two kinds of subtest per case and this driver reproduces
 * both, each through the SUITE'S OWN comparison helper:
 *
 *  * [Channel.ERRORS] — `…compile(source, file, opts).errorsMatchBaseline(Path(b))`;
 *  * [Channel.EMIT] — `…compile(source, file, opts).toBaseline(casesDir).sameAs(Path(b))`.
 *
 * **The EMIT channel is why round (P18.95) existed.** `--noEmit` skips
 * `Transformer.transform` and `Emitter.emit` entirely (round 738's `skipEmitOutputs`
 * gate), so the 8-profile grid, `cost_gate.py` and every other `--noEmit` instrument in
 * this repo is STRUCTURALLY BLIND to a change in emitted bytes. Before this channel
 * existed the JS-emit family of (LEGACY.0b) had no blast-radius instrument at all, which
 * is why it was deferred five rounds running. Select one channel with `--errors` /
 * `--emit`; the default is both.
 *
 * What it deliberately does NOT cover, and why it can never be the gate:
 *
 *  * every hand-written `*Test.kt` pin in `src/commonTest` — which is where this
 *    repo's negative controls live, and round (P18.92) measured a real defect that
 *    ONLY a negative control saw while the corpus was clean;
 *  * the `.types`/`.symbols` channels, the `-project` module, the externals module,
 *    the KIR backend;
 *  * `@Ignore`d subtests — by construction, since the ignored set is what a
 *    (LEGACY.0b) round is trying to shrink. To measure whether a PENDING row now
 *    passes, name it with `--include` (which ignores the `@Ignore`).
 *
 * ## How the population is derived
 *
 * From the GENERATED test sources, not from a hand-kept list — so the screen cannot
 * drift from the suite. The live tree is
 * `xemantic-typescript-compiler-core/build/generated/typescript-tests/`; **the tree at
 * the REPO ROOT `build/generated/typescript-tests/` is a pre-module-split leftover that
 * is frozen and carries ZERO `@Ignore` lines** — a census taken off it reads a
 * plausible-but-wrong corpus and has already misled two agents (CLAUDE.md, (LEGACY.0b)
 * step 3). [generatedTestsDir] names the live one and [main] REFUSES a tree whose
 * `@Ignore` count is zero.
 *
 * ## Why it cannot silently disagree with the suite
 *
 * Three properties, all structural rather than asserted:
 *
 *  1. It calls the SAME [errorsMatchBaseline] the generated errors test calls, so that
 *     comparison — baseline-absent handling, CRLF normalisation, `.d.ts` stripping — is
 *     the suite's by construction and not a second implementation of it.
 *  2. It calls the SAME [toBaseline] + [sameAs] pair the generated emit test calls,
 *     which is what carries `stripDtsSection`, the mixed-line-ending normalisation and
 *     the baseline's provenance header (the `casesDir` argument a conformance case
 *     passes is recovered from the generated source, not guessed).
 *  3. It reads both the case and the baseline through the SAME [readText], so the
 *     UTF-16 BOM decode is the suite's too. Re-implementing that is exactly what made
 *     this harness's first incarnation report two false regressions ((P18.92)).
 *
 * ## The floor, per channel
 *
 * A shrunken population reads EXACTLY like a clean run: zero mismatches out of zero
 * subtests is the most reassuring output this program can print and the least
 * informative. So [main] exits non-zero when a SELECTED channel's active count falls
 * below that channel's floor ([errorsFloor] / [emitFloor]) — any parser drift, a wrong
 * tree, or a half-generated build is a refusal, never a green line. The floor is
 * per-channel deliberately: a single combined floor is met by one healthy channel while
 * the other has collapsed to nothing.
 *
 * ## Usage
 *
 * ```
 * java -cp <jvm-test-classpath> \
 *     com.xemantic.typescript.compiler.corpus.CorpusScreenMainKt [options]
 * ```
 * driven by `scripts/corpus-screen.sh`, which resolves the classpath and refuses a
 * stale one. Options:
 *
 *  * `--errors` / `--emit` run only that channel (default: both)
 *  * `--floor N`          floor for EVERY selected channel (default [errorsFloor] / [emitFloor])
 *  * `--filter SUBSTR`    only subtests whose baseline name contains SUBSTR (waives the floors)
 *  * `--include SUBSTR`   additionally run `@Ignore`d subtests whose baseline contains SUBSTR
 *  * `--diff N`           print the first N mismatches' full diff (default 0 — names only)
 *  * `--list`             print the population and exit without compiling
 *
 * Exit code is 0 only when every selected channel meets its floor AND no mismatch was
 * found in any of them.
 */

/** The LIVE generated-test tree. See the class note: the repo-root one is frozen. */
private const val generatedTestsDir =
    "xemantic-typescript-compiler-core/build/generated/typescript-tests/com/xemantic/typescript/compiler"

/**
 * Refuse below this many ACTIVE errors subtests. Measured 3,046 active of 3,160 at
 * (LEGACY.0b) step 9; the floor sits a little under it so that landing a family round —
 * which UN-ignores subtests and so only ever RAISES the count — never trips it, while a
 * parser or tree-selection mistake (which collapses the count by hundreds) always does.
 */
private const val errorsFloor = 2_800

/**
 * Refuse below this many ACTIVE emit subtests. Measured 5,658 active of 5,692 at
 * (LEGACY.0b) step 10, with the same margin rule as [errorsFloor]. Note the emit channel
 * is nearly TWICE the size of the errors channel — the "~3,100 `.js` subtests" this
 * file's own note used to quote was an under-count.
 */
private const val emitFloor = 5_200

/** Which baseline channel a generated subtest compares. */
private enum class Channel(val label: String) {
    /** `.errorsMatchBaseline(Path("….errors.txt"))` — the diagnostics channel. */
    ERRORS("errors"),

    /** `.toBaseline(casesDir).sameAs(Path("….js"))` — the emitted-JavaScript channel. */
    EMIT("emit"),
}

/** One generated corpus subtest, recovered verbatim from the generated source. */
private data class Subtest(
    val testName: String,
    val channel: Channel,
    val sourcePath: String,
    val fileName: String,
    val options: Map<String, String>,
    val baselinePath: String,
    /**
     * The `casesDir` argument the generated `toBaseline(...)` call passes, which a
     * conformance case overrides because its baseline's provenance header echoes the
     * case's REAL location. Null for the errors channel and for the flat compiler corpus.
     */
    val casesDir: String?,
    val ignored: Boolean,
) {
    /** The baseline's basename, which is how the ledger and `tsgoPendingBaselines` name it. */
    val baselineName: String get() = baselinePath.substringAfterLast('/')
}

private val testFunRe = Regex("""^\s{4}fun `([^`]+)`\(\) \{""")
private val sourceRe = Regex("""^\s+val source = Path\("([^"]+)"\)\.readText\(\)""")
private val compileRe = Regex("""^\s+TypeScriptCompiler\(\)\.compile\(source, "([^"]+)"(?:, mapOf\((.*)\))?\)\s*$""")
private val baselineRe = Regex("""^\s+\.errorsMatchBaseline\(Path\("([^"]+)"\)\)""")

/**
 * The emit channel's compile line, which differs from [compileRe] only in the trailing
 * `.toBaseline(...)`. The optional group is the `casesDir` a conformance case passes;
 * matching it rather than defaulting it is what keeps a conformance baseline's
 * provenance header identical to the suite's.
 */
private val emitCompileRe = Regex(
    """^\s+TypeScriptCompiler\(\)\.compile\(source, "([^"]+)"(?:, mapOf\((.*?)\))?\)\.toBaseline\((?:"([^"]*)")?\)\s*$"""
)
private val emitBaselineRe = Regex("""^\s+\.sameAs\(Path\("([^"]+)"\)\)""")
private val optionRe = Regex(""""([^"]+)" to "([^"]*)"""")

/**
 * The two `$`-interpolated roots the generated bodies spell. Resolved textually rather
 * than by evaluating the constants, because the generated file is read as TEXT here.
 * They must stay in step with `TypeScriptTestSupport.kt`; [main] refuses a path it
 * cannot expand, so a renamed constant is loud rather than silently unmatched.
 */
private val pathRoots = listOf(
    "\$typeScriptCasesDir" to "typescript-repo/tests/cases/compiler",
    "\$typeScriptConformanceDir" to "typescript-repo/tests/cases/conformance",
    "\$typeScriptGoBaselineDir" to "typescript-go-repo/testdata/baselines/reference/submodule",
    "\$typeScriptBaselineDir" to "typescript-repo/tests/baselines/reference",
    "\$typeScriptGoRepoDir" to "typescript-go-repo",
    "\$typeScriptRepoDir" to "typescript-repo",
)

private fun expand(raw: String): String? {
    if (!raw.startsWith("$")) return raw
    for ((token, dir) in pathRoots) {
        if (raw.startsWith(token)) return dir + raw.substring(token.length)
    }
    return null
}

/**
 * Parses one generated source file into its subtests, both channels.
 *
 * The parse is deliberately STRICT: a body whose three lines do not match the
 * generator's shape for its channel is reported through [unparsed] rather than skipped,
 * because silently dropping subtests is the failure mode the floors exist to catch and a
 * strict parser catches it one step earlier, with the offending name in hand. A `fun`
 * whose name matches NEITHER channel is likewise unparsed — the generator emits exactly
 * these two kinds, so a third one is drift that must be looked at, not ignored.
 */
private fun parseFile(text: String, unparsed: MutableList<String>): List<Subtest> {
    val out = mutableListOf<Subtest>()
    val lines = text.split("\n")
    var i = 0
    while (i < lines.size) {
        val m = testFunRe.find(lines[i])
        if (m == null) { i++; continue }
        val testName = m.groupValues[1]
        val channel = when {
            testName.contains("has expected errors") -> Channel.ERRORS
            testName.contains("compiles to JavaScript matching") -> Channel.EMIT
            else -> { unparsed += testName; i++; continue }
        }
        // `@kotlin.test.Ignore` sits above the `@Test`, which sits above the `fun`.
        val ignored = (1..3).any { back ->
            i - back >= 0 && lines[i - back].trim() == "@kotlin.test.Ignore"
        }
        val src = if (i + 1 < lines.size) sourceRe.find(lines[i + 1]) else null
        val cmp = if (i + 2 < lines.size) {
            if (channel == Channel.ERRORS) compileRe.find(lines[i + 2]) else emitCompileRe.find(lines[i + 2])
        } else null
        val base = if (i + 3 < lines.size) {
            if (channel == Channel.ERRORS) baselineRe.find(lines[i + 3]) else emitBaselineRe.find(lines[i + 3])
        } else null
        if (src == null || cmp == null || base == null) {
            unparsed += testName
            i++
            continue
        }
        val sourcePath = expand(src.groupValues[1])
        val baselinePath = expand(base.groupValues[1])
        if (sourcePath == null || baselinePath == null) {
            unparsed += testName
            i++
            continue
        }
        val opts = cmp.groupValues[2]
            .let { if (it.isEmpty()) emptyMap() else optionRe.findAll(it).associate { r -> r.groupValues[1] to r.groupValues[2] } }
        out += Subtest(
            testName = testName,
            channel = channel,
            sourcePath = sourcePath,
            fileName = cmp.groupValues[1],
            options = opts,
            baselinePath = baselinePath,
            casesDir = if (channel == Channel.EMIT) cmp.groupValues[3].ifEmpty { null } else null,
            ignored = ignored,
        )
        i += 4
    }
    return out
}

private fun listGeneratedFiles(): List<Path> {
    val dir = Path(generatedTestsDir)
    if (SystemFileSystem.metadataOrNull(dir) == null) return emptyList()
    return SystemFileSystem.list(dir).filter { it.name.endsWith(".kt") }.sortedBy { it.name }
}

/**
 * Runs one subtest through the SUITE's own comparison helper for its channel.
 *
 * The two calls are copied from the generator's own bodies and must stay that way: a
 * second implementation of either comparison is precisely how this screen would come to
 * disagree with the gate it is screening for.
 */
private fun Subtest.compare() {
    val source = Path(sourcePath).readText()
    val result = TypeScriptCompiler().compile(source, fileName, options)
    when (channel) {
        Channel.ERRORS -> result.errorsMatchBaseline(Path(baselinePath))
        Channel.EMIT -> result.toBaseline(casesDir ?: DEFAULT_CASES_DIR) sameAs Path(baselinePath)
    }
}

fun main(args: Array<String>) {
    var floorOverride: Int? = null
    var filter: String? = null
    var include: String? = null
    var diffs = 0
    var listOnly = false
    val channels = mutableSetOf<Channel>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--floor" -> { floorOverride = args[++i].toInt() }
            "--filter" -> { filter = args[++i] }
            "--include" -> { include = args[++i] }
            "--diff" -> { diffs = args[++i].toInt() }
            "--list" -> listOnly = true
            "--errors" -> channels += Channel.ERRORS
            "--emit" -> channels += Channel.EMIT
            else -> {
                println("corpus-screen: unknown argument '${args[i]}'")
                println(
                    "usage: --errors | --emit | --floor N | --filter SUBSTR | " +
                        "--include SUBSTR | --diff N | --list"
                )
                kotlin.system.exitProcess(2)
            }
        }
        i++
    }
    if (channels.isEmpty()) channels += Channel.entries

    val files = listGeneratedFiles()
    if (files.isEmpty()) {
        println("corpus-screen: REFUSED — no generated tests under $generatedTestsDir")
        println("corpus-screen: run `./gradlew generateTypeScriptTests` from the repo root first")
        kotlin.system.exitProcess(3)
    }

    val unparsed = mutableListOf<String>()
    val all = files.flatMap { parseFile(it.readText(), unparsed) }
    val ignoredCount = all.count { it.ignored }

    println(
        "corpus-screen: ${files.size} generated file(s), ${all.size} subtest(s) " +
            Channel.entries.joinToString(", ", "(", ")") { c -> "${all.count { it.channel == c }} ${c.label}" } +
            ", $ignoredCount @Ignore'd"
    )
    if (unparsed.isNotEmpty()) {
        println("corpus-screen: REFUSED — ${unparsed.size} subtest(s) did not match the generator's shape:")
        unparsed.take(10).forEach { println("    $it") }
        println("corpus-screen: the parser has drifted from the generator; fix it rather than lowering the floor")
        kotlin.system.exitProcess(3)
    }
    if (ignoredCount == 0) {
        println("corpus-screen: REFUSED — the tree carries ZERO @Ignore lines.")
        println("corpus-screen: that is the signature of the FROZEN pre-module-split tree at the repo")
        println("corpus-screen: root; the live one is $generatedTestsDir")
        kotlin.system.exitProcess(3)
    }

    val selected = all.filter { s ->
        val active = !s.ignored || (include != null && s.baselineName.contains(include))
        s.channel in channels && active && (filter == null || s.baselineName.contains(filter))
    }

    // A per-CHANNEL floor: one combined number is met by a healthy channel while its
    // sibling has collapsed to nothing. `--filter` waives them (it selects a family on
    // purpose); `--include` does not, because it only ever ADDS rows.
    if (filter == null) {
        var refused = false
        for (c in channels) {
            val floor = floorOverride ?: when (c) {
                Channel.ERRORS -> errorsFloor
                Channel.EMIT -> emitFloor
            }
            val n = selected.count { it.channel == c }
            if (n < floor) {
                println("corpus-screen: REFUSED — $n active ${c.label} subtest(s), below the floor of $floor.")
                refused = true
            }
        }
        if (refused) {
            println("corpus-screen: a shrunken population reads exactly like a clean run; not reporting one.")
            kotlin.system.exitProcess(3)
        }
    }

    if (listOnly) {
        selected.forEach {
            println("${if (it.ignored) "IGNORED " else "active  "}${it.channel.label.padEnd(7)}${it.baselineName}")
        }
        println("corpus-screen: ${selected.size} subtest(s) listed")
        return
    }

    var mismatched = 0
    var errored = 0
    val shown = mutableListOf<String>()
    for (c in channels) {
        val ofChannel = selected.filter { it.channel == c }
        var compared = 0
        var chanMismatched = 0
        var chanErrored = 0
        val elapsed = measureTime {
            for (s in ofChannel) {
                if (SystemFileSystem.metadataOrNull(Path(s.sourcePath)) == null) {
                    chanErrored++
                    println("MISSING-CASE ${s.baselineName} (${s.sourcePath})")
                    continue
                }
                compared++
                try {
                    s.compare()
                } catch (e: AssertionError) {
                    chanMismatched++
                    println("MISMATCH [${c.label}] ${s.baselineName}")
                    if (shown.size < diffs) shown += "=== [${c.label}] ${s.baselineName}\n${e.message}"
                } catch (e: Exception) {
                    chanMismatched++
                    println("THREW    [${c.label}] ${s.baselineName}: ${e::class.simpleName}: ${e.message?.take(200)}")
                }
            }
        }
        mismatched += chanMismatched
        errored += chanErrored
        println(
            "corpus-screen: [${c.label}] compared $compared subtest(s) in ${elapsed.inWholeMilliseconds} ms — " +
                "$chanMismatched mismatch(es), $chanErrored missing case file(s)"
        )
    }
    shown.forEach { println(it) }

    println(
        "corpus-screen: TOTAL ${selected.size} subtest(s) over " +
            channels.joinToString("+") { it.label } +
            " — $mismatched mismatch(es), $errored missing case file(s)"
    )
    println("corpus-screen: this is a SCREEN, not the gate; `./gradlew jvmTest` remains the commit gate")
    if (mismatched > 0 || errored > 0) kotlin.system.exitProcess(1)
}
