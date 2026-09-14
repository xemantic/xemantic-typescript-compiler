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

import com.xemantic.typescript.compiler.TypeScriptCompiler
import com.xemantic.typescript.compiler.errorsMatchBaseline
import com.xemantic.typescript.compiler.readText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.measureTime

/**
 * **A FAST SCREEN FOR BLAST-RADIUS MEASUREMENT. NEVER A REPLACEMENT FOR THE GRADLE
 * SUITE, WHICH REMAINS THE COMMIT GATE.**
 *
 * This driver re-runs every ACTIVE (non-`@Ignore`d) `errors.txt` subtest of the
 * generated corpus in ONE JVM, outside Gradle, in ~30 seconds — against the ~4 minutes
 * a full `jvmTest` costs. Its whole purpose is to turn a family round's question
 * *"how many green baselines would this rule move?"* from an argument into a
 * measurement, cheaply enough to ask it of a THROWAWAY build before any of the work
 * is committed to.
 *
 * What it deliberately does NOT cover, and why it can never be the gate:
 *
 *  * the `.js` EMIT subtests (~3,100 more) — a checker change that moves emitted
 *    bytes is invisible here;
 *  * every hand-written `*Test.kt` pin in `src/commonTest` — which is where this
 *    repo's negative controls live, and round (P18.92) measured a real defect that
 *    ONLY a negative control saw while the corpus was clean;
 *  * the `.types`/`.symbols` channels, the `-project` module, the externals module;
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
 * Two properties, both structural rather than asserted:
 *
 *  1. It calls the SAME [errorsMatchBaseline] the generated test calls, so the
 *     comparison — baseline-absent handling, CRLF normalisation, `.d.ts` stripping —
 *     is the suite's by construction and not a second implementation of it.
 *  2. It reads both the case and the baseline through the SAME [readText], so the
 *     UTF-16 BOM decode is the suite's too. Re-implementing that is exactly what made
 *     this harness's first incarnation report two false regressions ((P18.92)).
 *
 * ## The floor
 *
 * A shrunken population reads EXACTLY like a clean run: zero mismatches out of zero
 * subtests is the most reassuring output this program can print and the least
 * informative. So [main] exits non-zero when the parsed active count falls below
 * [defaultFloor] — any parser drift, a wrong tree, or a half-generated build is a
 * refusal, never a green line.
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
 *  * `--floor N`          minimum active subtests; below it the run REFUSES (default [defaultFloor])
 *  * `--filter SUBSTR`    only subtests whose baseline name contains SUBSTR (lowers the floor to 0)
 *  * `--include SUBSTR`   additionally run `@Ignore`d subtests whose baseline contains SUBSTR
 *  * `--diff N`           print the first N mismatches' full diff (default 0 — names only)
 *  * `--list`             print the population and exit without compiling
 *
 * Exit code is 0 only when the floor is met AND no mismatch was found.
 */

/** The LIVE generated-test tree. See the class note: the repo-root one is frozen. */
private const val generatedTestsDir =
    "xemantic-typescript-compiler-core/build/generated/typescript-tests/com/xemantic/typescript/compiler"

/**
 * Refuse below this many ACTIVE errors subtests. Measured 3,021 active of 3,160 at (LEGACY.0b) step 8;
 * the floor sits a little under it so that landing a family round — which UN-ignores
 * subtests and so only ever RAISES the count — never trips it, while a parser or
 * tree-selection mistake (which collapses the count by hundreds) always does.
 */
private const val defaultFloor = 2_800

/** One generated `errors.txt` subtest, recovered verbatim from the generated source. */
private data class Subtest(
    val testName: String,
    val sourcePath: String,
    val fileName: String,
    val options: Map<String, String>,
    val baselinePath: String,
    val ignored: Boolean,
) {
    /** The baseline's basename, which is how the ledger and `tsgoPendingBaselines` name it. */
    val baselineName: String get() = baselinePath.substringAfterLast('/')
}

private val testFunRe = Regex("""^\s{4}fun `([^`]+)`\(\) \{""")
private val sourceRe = Regex("""^\s+val source = Path\("([^"]+)"\)\.readText\(\)""")
private val compileRe = Regex("""^\s+TypeScriptCompiler\(\)\.compile\(source, "([^"]+)"(?:, mapOf\((.*)\))?\)\s*$""")
private val baselineRe = Regex("""^\s+\.errorsMatchBaseline\(Path\("([^"]+)"\)\)""")
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
 * Parses one generated source file into its errors subtests.
 *
 * The parse is deliberately STRICT: a body whose four lines do not match the generator's
 * shape is reported through [unparsed] rather than skipped, because silently dropping
 * subtests is the failure mode the floor exists to catch and a strict parser catches it
 * one step earlier, with the offending line in hand.
 */
private fun parseFile(text: String, unparsed: MutableList<String>): List<Subtest> {
    val out = mutableListOf<Subtest>()
    val lines = text.split("\n")
    var i = 0
    while (i < lines.size) {
        val m = testFunRe.find(lines[i])
        if (m == null || !m.groupValues[1].contains("has expected errors")) { i++; continue }
        val testName = m.groupValues[1]
        // `@kotlin.test.Ignore` sits above the `@Test`, which sits above the `fun`.
        val ignored = (1..3).any { back ->
            i - back >= 0 && lines[i - back].trim() == "@kotlin.test.Ignore"
        }
        val src = if (i + 1 < lines.size) sourceRe.find(lines[i + 1]) else null
        val cmp = if (i + 2 < lines.size) compileRe.find(lines[i + 2]) else null
        val base = if (i + 3 < lines.size) baselineRe.find(lines[i + 3]) else null
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
        out += Subtest(testName, sourcePath, cmp.groupValues[1], opts, baselinePath, ignored)
        i += 4
    }
    return out
}

private fun listGeneratedFiles(): List<Path> {
    val dir = Path(generatedTestsDir)
    if (SystemFileSystem.metadataOrNull(dir) == null) return emptyList()
    return SystemFileSystem.list(dir).filter { it.name.endsWith(".kt") }.sortedBy { it.name }
}

fun main(args: Array<String>) {
    var floor = defaultFloor
    var filter: String? = null
    var include: String? = null
    var diffs = 0
    var listOnly = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--floor" -> { floor = args[++i].toInt() }
            "--filter" -> { filter = args[++i]; floor = 0 }
            "--include" -> { include = args[++i] }
            "--diff" -> { diffs = args[++i].toInt() }
            "--list" -> listOnly = true
            else -> {
                println("corpus-screen: unknown argument '${args[i]}'")
                println("usage: --floor N | --filter SUBSTR | --include SUBSTR | --diff N | --list")
                kotlin.system.exitProcess(2)
            }
        }
        i++
    }

    val files = listGeneratedFiles()
    if (files.isEmpty()) {
        println("corpus-screen: REFUSED — no generated tests under $generatedTestsDir")
        println("corpus-screen: run `./gradlew generateTypeScriptTests` from the repo root first")
        kotlin.system.exitProcess(3)
    }

    val unparsed = mutableListOf<String>()
    val all = files.flatMap { parseFile(it.readText(), unparsed) }
    val ignoredCount = all.count { it.ignored }

    println("corpus-screen: ${files.size} generated file(s), ${all.size} errors subtest(s), $ignoredCount @Ignore'd")
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
        active && (filter == null || s.baselineName.contains(filter))
    }

    if (filter == null && include == null && selected.size < floor) {
        println("corpus-screen: REFUSED — ${selected.size} active subtest(s), below the floor of $floor.")
        println("corpus-screen: a shrunken population reads exactly like a clean run; not reporting one.")
        kotlin.system.exitProcess(3)
    }

    if (listOnly) {
        selected.forEach { println("${if (it.ignored) "IGNORED " else "active  "}${it.baselineName}") }
        println("corpus-screen: ${selected.size} subtest(s) listed")
        return
    }

    var compared = 0
    var mismatched = 0
    var errored = 0
    val shown = mutableListOf<String>()
    val elapsed = measureTime {
        for (s in selected) {
            val source = try {
                Path(s.sourcePath).readText()
            } catch (e: Exception) {
                errored++
                println("MISSING-CASE ${s.baselineName} (${s.sourcePath})")
                continue
            }
            compared++
            try {
                TypeScriptCompiler().compile(source, s.fileName, s.options)
                    .errorsMatchBaseline(Path(s.baselinePath))
            } catch (e: AssertionError) {
                mismatched++
                println("MISMATCH ${s.baselineName}")
                if (shown.size < diffs) shown += "=== ${s.baselineName}\n${e.message}"
            } catch (e: Exception) {
                mismatched++
                println("THREW    ${s.baselineName}: ${e::class.simpleName}: ${e.message?.take(200)}")
            }
        }
    }
    shown.forEach { println(it) }

    println(
        "corpus-screen: compared $compared subtest(s) in ${elapsed.inWholeMilliseconds} ms — " +
            "$mismatched mismatch(es), $errored missing case file(s)"
    )
    println("corpus-screen: this is a SCREEN, not the gate; `./gradlew jvmTest` remains the commit gate")
    if (mismatched > 0 || errored > 0) kotlin.system.exitProcess(1)
}
