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

/**
 * (WARM.19) round 871 — the crawl's parse, reused ACROSS `ProjectCompiler.build`
 * calls in one process, which is what a `--serve` daemon request is.
 *
 * ## The measurement that justifies it
 *
 * A daemon request re-reads and re-PARSES all 78 files of the compiler profile
 * every time: INV.1(e)'s `preParsed` map is a local of `build()`, so its reuse
 * is from the crawl to the compilation core WITHIN one request and never from
 * one request to the next. Measured through the shipping `--serve` path
 * (`docs/perf/warm-serve-request-attribution.md`), one parse round over the
 * program is **133 ms = 1.9% of a warm request**, obtained by amplification
 * (`--parseAmp`, round 759's method) because the crawl's WALL is a concurrent
 * pipeline whose read+parse CPU sums to 6-9x it and which also carries a ~42 ms
 * fixed floor that no parse elimination can touch.
 *
 * ## Why this is safe, stated as the three properties it rests on
 *
 * 1. **The parse is a PURE function of `(source, fileName, flags)`.** The only
 *    process state a `Parser` touches is its own; the one stamp that looks
 *    global, `TypeParameter.internSalt`, is `fileName.hashCode()`.
 * 2. **The AST is never written after `indexSourceFile`.** `NodeBase`'s three
 *    `var`s (`nodeId`, `parent`, `kindId`) are stamped at the end of
 *    `Parser.parse()` and by nothing else; the Transformer synthesises fresh
 *    nodes with `copy()` and the Checker keeps its state in side tables.
 * 3. **The precedent is load-bearing and old.** `RealLibSnapshots` has cached
 *    the PARSED lib files process-wide since M2.1(c), on exactly this argument
 *    — "a `SourceFile` AST is immutable under checking … so one parse per lib
 *    file serves every program forever" — and re-BINDS them per program because
 *    symbol merging mutates its inputs. This cache is the same split applied to
 *    the program's own files: the parse is shared, the bind is not.
 *
 * ## Invalidation, which is the whole risk
 *
 * The key is the file's **CONTENT**, not its mtime, size or a stat: the crawl
 * reads every file on every request anyway (the read is 74-352 ms of CPU that
 * this cache does NOT remove and is not trying to), so the content is in hand
 * before the question is asked. A hit therefore requires the same path, the
 * same bytes and the same [ParserFlags] — INV.1(e)'s own gate, hoisted from a
 * per-build local to a process-global map — and there is no window in which a
 * stale tree can be served, because staleness is not expressible: different
 * bytes are a different key.
 *
 * That is the property to preserve. **Weakening the hit condition to an mtime
 * or a size comparison would make an edited file serve its previous tree, with
 * no crash, no diff on any dashboard profile (whose files never change between
 * requests) and no failing corpus test.** `CrawlParseCacheTest`'s edit pins are
 * the only instrument that sees it.
 *
 * ## Memory
 *
 * At most ONE entry per path: an edit REPLACES the entry rather than adding
 * one, so a daemon serving an editor holds one program's trees, not one per
 * keystroke. That bound is the reason the map is keyed by path with the content
 * stored *inside* the value, rather than by `(path, content)`.
 *
 * ## Threading
 *
 * The crawl parses concurrently on `Dispatchers.Default`, so this map is READ
 * from N workers and WRITTEN only from the single-threaded fold that runs after
 * each frontier's flow has been drained — the same discipline
 * `FrontEnd.addCrawlFile` and `RealLibSnapshots.prewarmParsedLibFiles` use, and
 * for the same reason (round 825: a plain `HashMap.getOrPut` from N worker
 * threads is a data race with no exception to find it by). [lookup] must stay
 * read-only and [store] must stay off the concurrent path.
 */
internal object CrawlParseCache {

    /** path -> its most recent parse. The record carries the content and flags it was parsed from. */
    private val entries = HashMap<String, PreParsedFile>()

    /**
     * The in-binary OFF arm — production is `true`.
     *
     * It exists so the capture can be measured as a CONTROLLED row on ONE
     * binary (round 793: a change that removes a region also removes its probe
     * boundaries, and here nothing moves except whether the parse happens), and
     * so the ablation has a switch that is not a source edit.
     */
    var enabled: Boolean = true

    /** Census, reported by `--frontEnd`. Reset by [reset], never by a compile. */
    var hits: Long = 0
    var misses: Long = 0

    /**
     * (INC.64) census — crawled files that were handed to [Dispatchers.Default] for a
     * PARSE, as opposed to answered from this cache on the thread the read left them on.
     *
     * It exists because the claim it pins is a COMPLEXITY one and only a count can state
     * one: a warm incremental build must dispatch `misses` times, not `files` times. The
     * shipped crawl used to hop unconditionally, which on an application-shaped project
     * is 2 x files thread handoffs to schedule a ~1 us map probe.
     *
     * Written ONLY from the single-threaded fold in `readAndScanBatch`, exactly like
     * [hits]/[misses] — a `++` from the concurrent workers is round 825's data race with
     * no exception to find it by.
     */
    var parseDispatches: Long = 0

    /** How many paths are held. Bounded by the number of distinct files crawled. */
    val size: Int get() = entries.size

    /**
     * The cached parse of [fileName], or null when this process has not parsed
     * exactly these bytes under exactly these flags.
     *
     * Read-only: called from the crawl's concurrent workers.
     */
    fun lookup(fileName: String, source: String, flags: ParserFlags): PreParsedFile? {
        if (!enabled) return null
        val e = entries[fileName] ?: return null
        // Flags first: a cheap structural compare before a possibly-megabyte
        // string compare, and the two are equally load-bearing (INV.1(e): a
        // parse-affecting parameter outside ParserFlags makes this gate match a
        // tree parsed WITHOUT it).
        if (e.flags != flags) return null
        if (e.content != source) return null
        return e
    }

    /**
     * Records [parsed] as the parse of [fileName], replacing any previous one.
     *
     * Called only from the single-threaded fold after a frontier's concurrent
     * flow has been drained. Storing a record that came from [lookup] is a
     * no-op assignment and is deliberately allowed, so the caller needs no
     * hit/miss branch.
     */
    fun store(fileName: String, parsed: PreParsedFile) {
        if (!enabled) return
        entries[fileName] = parsed
    }

    /**
     * The entry held for [fileName], whatever its content and flags.
     *
     * Exists for the pins: the lying-sentinel technique has to substitute a
     * TREE while keeping the record's content and flags exactly as the driver
     * computed them, and computing those independently in a test is how a pin
     * comes to measure its own flag arithmetic instead of the cache.
     */
    fun peek(fileName: String): PreParsedFile? = entries[fileName]

    /** Drops every entry and both counters; leaves [enabled] alone (it is a mode). */
    fun reset() {
        entries.clear()
        hits = 0
        misses = 0
        parseDispatches = 0
    }
}

/**
 * (INC.36) The parse THIS PROCESS has already made of exactly [source] as
 * [fileName] under exactly [flags], or null when it has not.
 *
 * The ONE read-only window onto [CrawlParseCache], and it exists for a measured
 * reason: `Project.sourceIndexOf` was parsing the program a SECOND time. A
 * whole-program `referencesAt` sweep on tsc's own 78 sources retained 264 MB, of
 * which `Project.sourceIndexes` was 114.7 MB and this cache 103.0 MB — the same
 * 78 files, the same bytes, the same flags, two trees
 * (`docs/perf/language-service-retention.md`).
 *
 * ## What it is safe to do with the answer
 *
 * READ it. The three properties this cache's own KDoc rests on carry straight
 * over: the parse is a pure function of `(source, fileName, flags)`, the AST is
 * never written after `indexSourceFile`, and `RealLibSnapshots` has shared
 * parsed lib trees across programs since M2.1(c) on exactly this argument. What
 * is NOT shared is the BIND — symbol merging mutates its inputs — so a consumer
 * of this must be a consumer of SYNTAX.
 *
 * ## Why this is a lookup and not a parse-or-store
 *
 * A `parseAndStore` shape would close the one gap this leaves (a file asked
 * about before any build parses privately, and a later build parses it again),
 * and it is deliberately not offered: [CrawlParseCache.store] must stay off the
 * concurrent path (round 825 — a plain `HashMap` write from N workers is a race
 * with no exception to find it by), and a caller here cannot promise it is not
 * running beside a crawl. [CrawlParseCache.lookup] is documented read-only and
 * is already called from the crawl's own workers; adding a reader adds nothing.
 *
 * A miss is not an error and needs no branch at the call site beyond "parse it
 * yourself": staleness is not expressible, because different bytes are a
 * different key.
 */
fun parsedSourceOrNull(fileName: String, source: String, flags: ParserFlags): SourceFile? =
    CrawlParseCache.lookup(fileName, source, flags)?.sourceFile
