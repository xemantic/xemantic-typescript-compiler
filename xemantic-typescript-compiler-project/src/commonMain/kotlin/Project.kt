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

import com.xemantic.typescript.compiler.Cancellation
import com.xemantic.typescript.compiler.CancellationSignal
import com.xemantic.typescript.compiler.CapturedDeclaration
import com.xemantic.typescript.compiler.CapturedDefinition
import com.xemantic.typescript.compiler.CompilerOptions
import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.ImportClause
import com.xemantic.typescript.compiler.ImportSpecifier
import com.xemantic.typescript.compiler.NamespaceImport
import com.xemantic.typescript.compiler.ProjectStateSnapshot
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.ParserFlags
import com.xemantic.typescript.compiler.PathUtil
import com.xemantic.typescript.compiler.ProgramRecheck
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.RecheckHolder
import com.xemantic.typescript.compiler.SignatureCaptureSpan
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.TsConfigLoader
import com.xemantic.typescript.compiler.TypeCaptureRequest
import com.xemantic.typescript.compiler.TypeCaptureSpan
import com.xemantic.typescript.compiler.Vfs
import com.xemantic.typescript.compiler.computeParserFlags
import com.xemantic.typescript.compiler.parsedSourceOrNull

/**
 * An open TypeScript project a host application can query for diagnostics and
 * edit IN MEMORY.
 *
 * This is the embedding API: a build tool, an IDE plugin or a test harness opens a
 * project once, asks it what is wrong, applies the buffers its user is typing into,
 * and asks again — without the edits ever reaching the filesystem.
 *
 * ```kotlin
 * val project = Project.open("/path/to/project")
 * project.diagnostics()                       // as the project is on disk
 * project.updateFile("/path/to/project/src/a.ts", editorBuffer)
 * project.diagnostics("/path/to/project/src/a.ts")   // as the user is typing it
 * project.close()
 * ```
 *
 * ## Positions
 *
 * [positionAt] / [offsetAt] translate between the compiler's 0-based character
 * offsets and the 1-based (line, character) coordinates an editor and
 * [Diagnostic] both speak. They read TEXT and never a program, so unlike every
 * other member here they do NOT build — a host may convert a coordinate on a
 * dirty project without paying for a compile. See [LineMap] for the terminator
 * rules and for where our own compiler disagrees with itself about them.
 *
 * ## Syntax
 *
 * [nodeInfoAt] answers what the syntax tree says is at an offset. Like the position
 * conversions and unlike everything else here it does NOT build: it PARSES the one
 * file asked about, with the parser flags the project's `tsconfig.json` implies,
 * and caches that parse the same way the line indexes are cached. A caller gets a
 * value ([NodeInfo]), never a node — see that class for why, and [SourceIndex] for
 * why "the node at an offset" needs more than the node's own `pos`/`end`.
 *
 * ## Semantics
 *
 * [quickInfoAt] answers what the TYPE CHECKER computed at an offset, and
 * [definitionsAt] where the name at an offset is declared. Unlike the two families
 * above they BUILD, and they build with the position handed IN: the compiler's
 * answers to "what is the type here" and "what does this name refer to" are
 * functions of state that exists only while the checker walks, so they are recorded
 * in place rather than asked for afterwards. See those methods, and
 * `TypeCaptureRequest` in the core for the measurement that decided it.
 *
 * Because a query IS a build, the two single-caret members are the WRONG default
 * for an interactive host: describing one caret both ways is two compiles and
 * describing a screenful is dozens. [semanticsAt] takes many offsets and
 * [fileSemantics] takes a whole file, and each is ONE compile — the positions go in
 * as a set and the checker records at all of them during the single walk it was
 * going to perform anyway. Reach for those; the single-caret pair remains for the
 * host that genuinely has one caret and one question.
 *
 * ## References
 *
 * [referencesAt] and [documentHighlightsAt] answer the converse question — not
 * "where does this name point" but "what else points where this name does" — and
 * they are the same batched build turned inside out: every identifier in the
 * program (or in one file) is resolved in ONE walk and grouped by the DECLARATION
 * SET each one resolved to. So the grouping key is a value, no `Symbol` crosses
 * the boundary, and a name match is never what decides identity.
 *
 * ## Completions
 *
 * [completionsAt] answers what may be WRITTEN at a caret. It is the one query whose
 * position cannot be a node — the user is mid-identifier or sitting just after a
 * `.` — so it resolves the caret against the token stream and reports what it found
 * ([CompletionKind]) beside the candidates. Both halves are answered: members from
 * the receiver's type, free names from the lexical scope chain. Keywords are not,
 * for the reason stated there.
 *
 * ## Signature help
 *
 * [signatureHelpAt] answers what may be PASSED at a caret: every signature the
 * callee has, in declaration order, which of them applies to the arguments typed so
 * far, and which parameter the caret's argument lands on. Its anchor is
 * [completionsAt]'s kind of question rather than [quickInfoAt]'s — there is no node
 * at a caret in `f(a, |)`, and for an argument list the user has not closed the
 * call's own real end lies BEFORE the caret — so the call is recovered by bracket
 * matching over the token stream and the argument index by counting commas.
 *
 * ## What this class is NOT
 *
 * It is not a full language service: there is no rename, no keyword completion, and
 * no incremental reuse of a previous build's internal state.
 * A query on a dirty project is a FULL rebuild, and that is a property of the
 * compiler rather than a shortcut taken here — `ProjectCompiler.Result` is a flat
 * value (paths, diagnostics, an import graph) that retains no AST, no binder
 * output and no checker; the checker's construction IS the compilation
 * (`docs/ARCHITECTURE-RETHINK.md`). What makes a re-query cheap anyway is the
 * compiler's process-global, CONTENT-keyed parse cache, which every unedited file
 * hits — so the second build of an N-file project re-parses only what changed. Do
 * not add "incremental" reuse on top of this class; the seam for it does not exist
 * yet.
 *
 * ## Emit
 *
 * Every build this class performs passes `noEmit = true`. A tool that opens a
 * project to ask questions about it must never scatter JavaScript through the
 * user's tree as a side effect of a query, and an editor overlay makes that worse
 * still — the emitted output would correspond to unsaved buffers. Emitting is
 * `ProjectCompiler`'s job and stays there.
 *
 * ## Paths
 *
 * Every path that crosses this API — the project path, the argument of
 * [updateFile] / [deleteFile] / [diagnostics] — is normalized and made absolute
 * through the backing [Vfs] before it is used as a key, because the crawl works in
 * absolute paths and a relative key would silently never match anything. That
 * failure mode has bitten this repo before at a different layer: a client guessing
 * which of its arguments were paths and resolving them against the wrong directory
 * (CLAUDE.md, round 873). The keys are the absolute paths, and the conversion
 * happens once, here.
 *
 * This class does NOT touch `SystemVfs.workingDirectory`: that variable is
 * process-global and install-and-restore, owned by the compile server for the
 * duration of one served request. A host that needs relative paths resolved
 * against something other than its own working directory should pass a [Vfs] whose
 * [Vfs.resolveAbsolute] says so.
 *
 * Not thread-safe: one [Project] belongs to one thread at a time. Builds are
 * driven synchronously on the calling thread (the compiler runs its pipeline on
 * its own deep-stack thread internally and joins it before returning).
 */
/**
 * Buffer-sized capture entries [Project.captures] retains — the split-editor pair.
 * See that field's KDoc for what two BUYS and why it did not go down to one.
 */
private const val CAPTURE_MEMO_BUFFERS = 2

/**
 * (INC.32) A capture request naming at most this many spans is CARET-scoped rather
 * than buffer-sized.
 *
 * The three caret channels — member completion, free-name completion and signature
 * help — each name exactly ONE span, where a file-wide request names one per
 * occurrence node in its file (125,289 of them on tsc's own `checker.ts`). So the
 * two populations are four orders of magnitude apart and any number in between
 * separates them; four rather than one because a caller may hand [Project] a handful
 * of nodes the file-wide request does not carry, and that is still a caret. Anything
 * above it is classed as a buffer, which is the conservative direction: a
 * misclassified entry costs a slot in the lane that is bounded at two, never memory.
 */
private const val CAPTURE_MEMO_CARET_SPANS = 4

/**
 * (INC.32) Caret-scoped entries [Project.captures] retains BESIDE the buffer-sized
 * ones — the caret channels of one buffer, so that none of them evicts its hover.
 */
private const val CAPTURE_MEMO_CARET_ENTRIES = 4

/**
 * (INC.44) The name TypeScript gives a module's default export, and the one spelling
 * a reference search may never close over.
 *
 * `export { foo as default }` links `foo` to it, and the far side of that edge is an
 * `import d from …` whose local `d` is written nowhere near either — so a closure
 * that reaches this name has left the region a syntactic scan can bound. See
 * [Project.narrowedSweep].
 */
private const val DEFAULT_EXPORT_NAME = "default"

/**
 * (INC.44) How many times [Project.narrowedSweep] may re-select before giving up and
 * sweeping the whole program.
 *
 * Each pass either terminates or adds at least one spelling, so the loop is finite
 * without this; the cap is here because the alternative to a wrong bound is an
 * editor query that never returns, and a chain of aliases four deep is already
 * pathological. Exceeding it answers "sweep everything", which is correct and slow
 * rather than fast and wrong.
 */
private const val MAX_ALIAS_CLOSURE_PASSES = 4

public class Project private constructor(
    /** The project path as given to [open], normalized and absolute. */
    private val projectPath: String,
    /** The overlay wrapping the caller's [Vfs] — the compiler only ever sees this. */
    private val overlay: OverlayVfs,
) {

    public companion object {

        /**
         * Opens the project at [projectPath], reading through [vfs].
         *
         * [projectPath] is either a directory containing `tsconfig.json` or a path
         * to a config file — the same argument `ProjectCompiler.build` and the `xtsc`
         * command line take.
         *
         * NOTHING IS COMPILED HERE. Opening only resolves and validates the path;
         * the first query compiles. That is deliberate and is what lets a caller
         * stage its editor buffers with [updateFile] BEFORE the first build, so a
         * host that already has unsaved state does not pay for a build of the
         * on-disk truth it is about to discard.
         *
         * @throws IllegalArgumentException if [projectPath] does not exist. This is a
         *   guard, not politeness: a project path that does not exist used to make
         *   the crawl walk upwards from `/` (CLAUDE.md, round 873 — `dirname` of a
         *   config nobody confirmed is `/`), and inside a long-lived host that is
         *   not a slow query but a wedged process.
         */
        public fun open(projectPath: String, vfs: Vfs = SystemVfs): Project {
            val absolute = vfs.resolveAbsolute(PathUtil.normalize(projectPath))
            require(vfs.exists(absolute)) {
                "project path does not exist: $absolute"
            }
            return Project(absolute, OverlayVfs(vfs))
        }
    }

    /**
     * The `tsconfig.json` this project is checked against, as an absolute path.
     *
     * Resolved exactly as `ProjectCompiler` resolves it — a directory argument gets
     * `/tsconfig.json` appended, any other argument is the config itself — and
     * derived without compiling, so reading it is free. It is reported even when the
     * file is absent, in which case the build runs on default options and includes
     * nothing; a caller that wants to distinguish the two cases can stage a config
     * through [updateFile] before its first query.
     */
    public val configPath: String =
        if (overlay.isDirectory(projectPath)) "$projectPath/tsconfig.json" else projectPath

    /**
     * The most recent build, or null when this project is dirty (never built, or
     * edited since the last build).
     */
    private var cached: ProjectCompiler.Result? = null

    /**
     * The answers [diagnosticsOf] has already computed for this project STATE,
     * keyed by the normalized file set it was asked about.
     *
     * Separate from [cached] on purpose, and the separation is the whole soundness
     * argument: a narrowed build's result is NOT a whole-program result — its
     * checker walked a partition — so storing one in [cached] would make a later
     * [diagnostics] silently report a subset of the program's errors. What is
     * cacheable is the ANSWER to the exact question that was asked, which is what
     * this map holds. Dropped wherever [cached] is, since both describe the same
     * project state.
     *
     * ## (INC.14) The key is the PARTITION, and a subset is served from a superset
     *
     * The value is every row that build reported for ANY file of its partition, not
     * only for the files the caller happened to name — so a later question about a
     * SUBSET of that partition is answered by filtering, with no build. That is the
     * whole of the error-reporting case: a host that asks `diagnosticsOf(openBuffers)`
     * once on idle then answers its per-buffer annotator for free, `N` builds for `N`
     * buffers becoming ONE.
     *
     * It is sound because a partition build's rows for a file are the rows a build of
     * that file ALONE reports — which is not assumed but swept, at two granularities:
     * `scripts/partition-equivalence.sh` compares a partition against the whole
     * program, and `scripts/checker-reuse-differential.sh` compares a group of `k`
     * against one build per file, over 1.07 M rows in an editor-ordered query
     * sequence with revisits.
     */
    private val narrowed = LinkedHashMap<Set<String>, List<Diagnostic>>()

    /**
     * (INC.40) The LIVE PROGRAM a previous [diagnosticsOf] build handed back — the
     * re-entrant checker of `Recheck.kt`, wrapped so that it can answer **nothing
     * but diagnostics**.
     *
     * ## Why this exists
     *
     * Every [diagnosticsOf] on a dirty project is a fresh narrowed build, and a
     * fresh narrowed build pays two things over and over: the incremental FLOOR
     * (crawl, parse, bind and the ~110 program-wide `init` passes it does not
     * replay) and (INC.37)'s **1.39x re-derivation tax** — the shared lib and
     * foreign-declaration resolutions a whole-program build performs once and each
     * per-file query re-derives inside its own `Checker`. A live program pays
     * neither. Re-priced at HEAD on tsc's own 78 sources, diagnostics only, six
     * warm-ups, three rotations, **replicated in two independent JVMs** whose
     * medians are quoted as a band (`scripts/inc40-replay-cost.sh`): the per-query
     * MEDIAN is **104-108 ms fresh against 25 ms replayed (4.2x)** against a floor
     * of **54-61 ms**, and the whole 77-query sweep is **10,656-10,783 ms against
     * 4,685-4,728 ms (2.25-2.30x)** — the replayed total landing on the
     * whole-program CHECK cost (~4,935 ms), which is the re-derivation tax being
     * collected rather than re-paid. ARMING is free within the band (an armed
     * 77-query sweep reads 10,546 ms against 10,783 plain) and changed no
     * diagnostic row in 231 group comparisons.
     *
     * ## Why it may serve THIS member and nothing else
     *
     * `scripts/replay-differential.sh` grades a replayed answer against a fresh
     * narrowed build, per file, over three channels. At HEAD, over both arms:
     *
     * * **DIAGNOSTICS — 0 divergences**, on the realism arm (tsc's own sources, 46
     *   rows) and on the sensitivity arm (`test-fixtures/partition-gate`, 178 rows
     *   over 71 files carrying rows from 78 distinct netting passes);
     * * **DEFINITIONS — 0 divergences**;
     * * **CAPTURED TYPES — 43 of 75 files diverge** on the realism arm. Most are
     *   the union-alias display family (INC.26)/(INC.27) — `ModuleExportName` for
     *   `StringLiteral | Identifier` — and the residue is lost generic INFERENCE
     *   (`Connection[][]` read as `any[][]`). Both are silent in the dangerous
     *   direction: a plausible type, never an error.
     *
     * So the replay is wired to the channel that agrees and is UNREACHABLE from the
     * ones that do not. That is enforced structurally rather than by documentation:
     * [DiagnosticsOnlyRecheck] takes the `ProgramRecheck` private and exposes ONE
     * method whose return type is `List<Diagnostic>`, so no `TypeCaptureRequest`
     * can be handed to it and no `CapturedType` can escape it. [quickInfoAt],
     * [definitionsAt], [completionsAt], [signatureHelpAt], [semanticsAt] and
     * [prepare] cannot reach this field's contents even by mistake.
     *
     * ## What invalidates it
     *
     * The same thing that invalidates [cached] and [narrowed], and for the same
     * reason: `ProgramRecheck` has no invalidation protocol and deliberately does
     * not want one (its program's text is fixed at the build that produced it), so
     * this handle is dropped at every one of the three sites that drops [cached] —
     * [updateFile], [deleteFile] and [close].
     *
     * ## What it costs
     *
     * It RETAINS the whole checker — every `Type`, every `Symbol`, every side table
     * — until the next edit. That is the price of the 4.2x, it is paid only by a
     * project someone has asked [diagnosticsOf], and it is bounded by the same
     * edit-scoped lifetime as [prepared].
     */
    private var recheck: DiagnosticsOnlyRecheck? = null

    /**
     * (INC.12) The capture builds [captureIn] has already performed for this project
     * STATE, keyed by the REQUEST that produced them.
     *
     * ## What it is for, and why the key is the request
     *
     * Every caret-scoped query — hover, go-to-definition, completion, signature help,
     * document highlights — is one capture build, and until this existed there was no
     * reuse of any kind between them: measured on tsc's own 78 sources, asking for
     * the SAME hover twice in a row cost 2,205 ms and then 1,933 ms. Two of the
     * editor's commonest sequences are literally the same question asked twice:
     *
     * * [quickInfoAt] and [definitionsAt] at ONE caret build an IDENTICAL request
     *   (both name the caret's node as a single span, and read different channels of
     *   the one answer), so hovering and then navigating is now one build;
     * * [documentHighlightsAt]'s request is derived from the FILE's occurrence nodes
     *   and not from the caret at all — the caret only picks the seed AFTERWARDS —
     *   so highlights at every later caret in an unchanged buffer is now free.
     *
     * Keying on the request rather than on the caret is what makes both of those fall
     * out instead of being special-cased, and it cannot serve a wrong answer to a
     * question it was not asked: a different request is a different key.
     *
     * ## Why serving one is sound
     *
     * A build is a function of (project state, request). This map holds answers for
     * ONE project state — it is dropped wherever [cached] and [narrowed] are, and by
     * the same edit-driven invalidation, which is the whole of this class's staleness
     * contract (a host tells this project about its edits; nothing here watches the
     * disk, exactly as [cached] has always assumed). So an entry can only ever be
     * returned to the identical question about the identical state.
     *
     * ## Why it is BOUNDED, and by WEIGHT
     *
     * A [ProjectCompiler.Result] holds values only — no AST, no `Symbol`, no `Type` —
     * but a file-wide capture over a large file holds one answer per identifier in it,
     * which is tens of MB on a file the size of tsc's own `checker.ts`. It is a bound
     * rather than a heuristic: a long-lived project cannot grow this map, whatever a
     * host asks.
     *
     * **(INC.13) sharpened what two entries BUY, and made the worst case dearer.**
     * Before it, the pair was "one caret-scoped request plus one file-wide one"; now
     * hover, go-to-definition, document highlights and [fileSemantics] all ask ONE
     * file-wide question per file (see [captureAround]), so the pair is TWO BUFFERS —
     * which is what a host with a split editor holds, and the reason the number did
     * not go down to one.
     *
     * **(INC.32) …and the bound is on WEIGHT, not on entry COUNT.** Counting entries
     * let a request that costs nothing to retain evict one that cost a rebuild to
     * earn: [completionsAt]'s two branches and [signatureHelpAt] each name exactly ONE
     * span, so the ordinary editor sequence hover → completion → signature help →
     * hover, with NO edit anywhere in it, evicted the hover's file-wide entry and paid
     * a whole narrowed rebuild for the last step — measured 267 ms and 275 ms in two
     * independent JVMs against 4 ms when nothing evicted it (`scripts/inc31-ls-cost.sh`,
     * row `quickInfo.mid.afterTwoOtherChannels`). The fix is NOT a larger
     * [CAPTURE_MEMO_BUFFERS]: that would double the worst case below to buy a case
     * that needs no extra memory at all. A request naming at most
     * [CAPTURE_MEMO_CARET_SPANS] spans is retained in a lane of its own and can
     * therefore only ever evict another caret-scoped entry.
     *
     * The cost of that is honest and worth stating, and it is the (INC.13) statement
     * plus a rounding error: at most TWO buffer-sized captures — UNCHANGED — beside at
     * most [CAPTURE_MEMO_CARET_ENTRIES] entries of at most [CAPTURE_MEMO_CARET_SPANS]
     * answers each. Sixteen answers, against 125,289 for ONE file-wide capture of
     * `checker.ts`: 0.013%. What is NOT a rounding error and is the reason the caret
     * lane is bounded in COUNT as well as in spans: every entry also holds its build's
     * program-shaped fields — `programFiles`, `importEdges`, the partition's
     * diagnostics — which are a property of the PROGRAM and not of the request, so
     * they do not shrink with the span count.
     *
     * Retaining more makes a stale serve strictly more likely, so the invalidation was
     * re-audited rather than assumed: `cached = null` occurs at exactly three sites in
     * this class ([updateFile], [deleteFile], [close]) and every one of them clears
     * this map in the same breath. There is no fourth path that changes program text
     * or options.
     */
    private val captures = LinkedHashMap<TypeCaptureRequest, ProjectCompiler.Result>()

    /**
     * (INC.14) The one capture build [prepare] has performed for this project STATE,
     * or null — ONE `Checker`'s answers to every caret-scoped question about every
     * file of a working set.
     *
     * ## Why it is a slot of its own and not another [captures] entry
     *
     * [captures] is an access-ordered LRU of two, sized for "the buffer in front of
     * the user plus the one in the other split". A prepared check is the opposite
     * shape — deliberately wide, deliberately expensive to earn, and worth keeping
     * precisely while the user moves AWAY from the buffer that earned it — so an
     * ordinary hover in an unprepared file must not evict it. Keeping it here means a
     * caret query can only ever ADD to what is resident, never replace this.
     *
     * ## What it may serve, and what it may not
     *
     * It may serve every question [captureAround] asks about a file it covers, and
     * nothing else. In particular it may NOT serve [diagnostics] or [diagnosticsOf]:
     * a capture build types nodes the checker had no reason to type, so its
     * diagnostics are not interchangeable with a plain build's — the rule
     * `docs/language-service.md` § 3 has always stated, and the reason [prepare] is
     * documented as preparing SEMANTIC queries rather than "the files".
     *
     * ## The bound is ONE, and the reason is memory
     *
     * A file-wide capture holds one answer per identifier per file; over a working
     * set it is the largest value this class ever retains. One entry, replaced
     * wholesale by the next [prepare] and dropped by any edit, is a bound a host
     * cannot grow past however it calls.
     */
    private var prepared: PreparedCheck? = null

    /**
     * One [prepare] build: the partition its checker walked, the span set it was
     * asked about per file, and its answers.
     *
     * [covered] is the REQUEST's own spans grouped by file, not something re-derived
     * from the parse — so "does this prepared check carry the answer to that
     * question" is decided against what was actually asked, and a future change to
     * how a request is built cannot silently make the containment test optimistic.
     */
    private class PreparedCheck(
        val files: Set<String>,
        val covered: Map<String, Set<Long>>,
        val result: ProjectCompiler.Result,
    )

    /**
     * (INC.46) What the last WHOLE-PROGRAM build established, kept across edits so that
     * the next [diagnostics] can decide whether it must rebuild.
     *
     * Dropped only when it can no longer describe this project — a config edit, a
     * change to the program's file set, or a build that could not be summarised.
     */
    private class ExportSurface(
        /** Every program file's export fingerprint, from a whole-program build. */
        val signatures: Map<String, Long>,
        /** Files that may not be proved stable however they are edited. */
        val escapes: Set<String>,
        /** That build's whole-program diagnostics, kept row for row and in order. */
        val diagnostics: List<Diagnostic>,
        /** That build's program, so a crawl that finds a different one falls back. */
        val programFiles: List<String>,
    )

    private var surface: ExportSurface? = null

    /**
     * (INC.48) True while [surface] came from a SNAPSHOT and no build of this process
     * has re-crawled the project yet.
     *
     * A snapshot's content hashes can tell which files CHANGED and cannot tell that one
     * was ADDED — a new file matching the config's globs is in no hash and in no stored
     * list, and it changes what every importer resolves. So a restored surface is not
     * trusted until one build has produced the same program: until then the gate runs
     * even when nothing changed, with an EMPTY partition, which is the ~110 ms floor
     * rather than the ~5 s rebuild it replaces.
     */
    private var restoredUnverified: Boolean = false

    /**
     * (INC.46) The files edited since [surface] was established, or null when the
     * project is clean.
     *
     * A `LinkedHashSet` rather than a single path: an editor saves several buffers at
     * once, and the gate is no weaker for a batch — every edited file must clear it.
     */
    private var dirtyFiles: LinkedHashSet<String>? = null

    /**
     * (INC.46) How many [diagnostics] calls this project has answered INCREMENTALLY.
     *
     * Exposed for pinning and measurement, not for policy — and it is what keeps the
     * differential in `scripts/inc46-incremental-differential.sh` from being vacuous:
     * an implementation that always fell back would agree with a rebuild on every case
     * and prove nothing (round 790 — a verifier reads 0 both when the skip is sound and
     * when the instrument is dead).
     */
    internal var incrementalAnswers: Int = 0
        private set

    /**
     * (INC.55) The host's cancellation signal, polled by every build this project
     * drives — or null, which is what a batch caller wants.
     *
     * ## Why an editor needs this and latency work cannot replace it
     *
     * A build runs on the compiler's own deep-stack thread and this class JOINS it,
     * so the calling thread is blocked for the whole build and cannot abandon it
     * from outside. On the IntelliJ platform `DaemonCodeAnalyzer` restarts analysis
     * on every write action, so without this an editor has only bad options: block a
     * pooled thread producing an answer that is already stale, and delay the next
     * (wanted) answer behind it. That is a capability gap rather than a latency one —
     * no amount of narrowing the check fixes it.
     *
     * ## What cancelling costs, and why the state is safe
     *
     * A cancelled build throws [CompilationCancelledError] out of the call that asked
     * for it and produces NO result. Nothing in this class is left half-updated,
     * BY CONSTRUCTION rather than by care: every cache assignment here happens AFTER
     * `ProjectCompiler.build` returns, so a throw skips all of them and the project
     * is exactly as it was. The work done by the abandoned build is lost — there is
     * no partial result to keep — so a host should cancel because the answer is
     * unwanted, not to poll.
     *
     * ## Where it is polled
     *
     * At every `pass("…")` boundary (~480 per compile) and every
     * [Cancellation.SPINE_POLL_INTERVAL] spine nodes, which is what keeps a single
     * large buffer's walk — 1.65 s on tsc's own `checker.ts` — interruptible.
     *
     * The signal is read on the COMPILE thread while the caller is blocked, so an
     * implementation must be safe to call from another thread. On the IntelliJ
     * platform that is `{ indicator.isCanceled }`.
     */
    public var cancellation: CancellationSignal? = null

    private var closed: Boolean = false

    /**
     * Every file in the current program: the config's root files plus everything
     * reachable from them through imports, plus the declaration files, in crawl
     * order.
     *
     * Reading this BUILDS if the project is dirty — it is a question about a
     * program, and the program is what a build computes.
     */
    public val files: List<String> get() = build().programFiles

    /**
     * Every diagnostic the whole program produces — errors, warnings and
     * suggestions alike, in the compiler's own order.
     *
     * Builds only when dirty: two calls with no intervening edit return the same
     * result and perform no filesystem reads and no checking the second time. That
     * is not an optimization detail a caller may ignore — a query is a full compile,
     * so a host that re-asks per keystroke without editing must not pay for it.
     */
    public fun diagnostics(): List<Diagnostic> {
        checkOpen()
        cached?.let { return it.diagnostics }
        // (INC.46) An incremental answer BECOMES this project's standing answer, so a
        // host that asks twice pays once — `cached` cannot hold it (that field is a
        // whole-program `Result`, and a narrowed build's is not one), so the retention
        // lives on the surface, which the accepted answer already updated.
        if (dirtyFiles == null) surface?.let { return it.diagnostics }
        // (INC.46) An edit that moved no exported signature cannot change any other
        // file's diagnostics, so the answer is the previous build's rows with the
        // edited files' replaced — one narrowed build (108-113 ms on tsc's own 78
        // sources) instead of a rebuild (4.9 s). Answers null, and falls through to a
        // rebuild, whenever that cannot be justified; see [incrementalDiagnostics] for
        // the five conditions and why each is checked rather than argued.
        incrementalDiagnostics()?.let { return it }
        return build().diagnostics
    }

    /**
     * The diagnostics whose [Diagnostic.fileName] is [fileName].
     *
     * [fileName] is normalized and absolutized exactly as [updateFile]'s argument
     * is, so a caller may pass it in whichever of the two forms it holds. A file
     * that is not part of the program — or has no diagnostics — yields an empty
     * list rather than an error: "which errors are in this buffer" has an answer for
     * any buffer, and an exception there would force every caller to pre-check
     * membership.
     */
    public fun diagnostics(fileName: String): List<Diagnostic> {
        val key = keyOf(fileName)
        // `?.let` rather than a null check and a smart cast: `Diagnostic.fileName` is
        // a public property of another module, where Kotlin refuses to smart-cast.
        // (INC.46) through [diagnostics], so the per-file question inherits the
        // incremental answer instead of forcing the rebuild it exists to avoid.
        return diagnostics().filter { d ->
            d.fileName?.let { PathUtil.normalize(it) } == key
        }
    }

    /**
     * The diagnostics of [fileNames] alone, computed by checking only those files.
     *
     * This is [diagnostics] narrowed at the SOURCE rather than at the filter: where
     * `diagnostics(fileName)` builds the whole program and then keeps the rows that
     * name one file, this hands the file set to the compiler as its check partition
     * (INV.6), so the per-file check passes walk only those files. The program-wide
     * passes still walk everything and the program is still crawled, parsed and
     * bound in full — the narrowing is of CHECKING, not of the program — which is
     * why the answer is the same one the whole-program build gives for those files.
     * Measured on the 78 sources of tsc's own compiler: 1.2 s against 4.6 s warm,
     * and for every one of those files the partition reports exactly the rows the
     * full build reports for it.
     *
     * Each name is normalized and absolutized exactly as [updateFile]'s argument is,
     * so a caller may pass whichever form it holds, and a name that is not part of
     * the program contributes nothing rather than raising — same reasoning as
     * `diagnostics(fileName)`. An EMPTY collection answers empty and does not build:
     * "the diagnostics of no files" needs no compile to be answered.
     *
     * Five cost properties a host may rely on. A query on a CLEAN project performs
     * NO build — the whole-program result is already in hand and filtering it is
     * strictly better than a narrowed compile. A query on a dirty project performs
     * exactly ONE build, however many files were asked about. A repeated identical
     * query on an unchanged project performs none, because the answer is memoized per
     * file set. **(INC.14)** a query about any SUBSET of a set already asked about
     * performs none either: the memo is keyed by the PARTITION the build walked and
     * holds every row it reported for it, so `N` per-file questions after one
     * `N`-file question are `N` filters and no build at all. That is the whole of the
     * error-reporting case — a host asks `diagnosticsOf(openBuffers)` once on idle
     * and its per-buffer annotator is free afterwards.
     *
     * And **(INC.40)**: a query about a file NOT in any set already asked about
     * performs no build EITHER. The first narrowed query of a project state keeps its
     * live program (see [recheck]); every later one re-enters that checker over the
     * new files instead of building a fresh one, which costs neither the incremental
     * floor nor (INC.37)'s 1.39x re-derivation tax. Measured at HEAD over tsc's own
     * 78 sources, diagnostics only: the median query **104 ms -> 25 ms**, the whole
     * 77-file sweep **10,656 ms -> 4,728 ms**. So an editor that opens buffers one at
     * a time pays one build for the first and a re-entry for each of the rest, rather
     * than a build each — which is what makes a per-buffer error annotator viable
     * without the host having to batch its questions.
     *
     * The narrowed build's result is deliberately NOT retained as this project's
     * build: its checker walked a partition, so adopting it would make a subsequent
     * [diagnostics] answer with a SUBSET of the program's errors and report it as
     * the whole. A whole-program query after a narrowed one therefore still costs a
     * build — the price of the narrow query being narrow.
     */
    public fun diagnosticsOf(fileNames: Collection<String>): List<Diagnostic> {
        checkOpen()
        if (fileNames.isEmpty()) return emptyList()
        val keys = fileNames.mapTo(LinkedHashSet()) { keyOf(it) }
        // A clean project already holds the answer to a STRICTLY WIDER question, and
        // filtering it beats compiling: no narrowed build can be cheaper than not
        // building at all.
        cached?.let { result ->
            return result.diagnostics.filter { d ->
                d.fileName?.let { PathUtil.normalize(it) } in keys
            }
        }
        // (INC.14) A build already performed for this state whose PARTITION contains
        // every file asked about answers this question by filtering — so `N` per-file
        // questions after one `N`-file question are `N` filters and no build.
        for ((partition, rows) in narrowed) {
            if (!keys.all { it in partition }) continue
            return if (partition.size == keys.size) rows
            else rows.filter { d -> d.fileName?.let { PathUtil.normalize(it) } in keys }
        }
        // (INC.40) A LIVE PROGRAM from an earlier query for this same state answers a
        // file its checker never walked by re-entering only the ~305 of 417 `init`
        // rows that depend on the check partition — no crawl, no parse, no bind, and
        // none of the ~110 program-wide rows that carry the floor. Measured at HEAD
        // over tsc's own 78 sources: a median query 104 -> 25 ms. Sound HERE and
        // nowhere else: the diagnostics channel is graded equivalent on both arms of
        // `scripts/replay-differential.sh` and the captured-types channel is not,
        // which is why [DiagnosticsOnlyRecheck] can express no other question.
        recheck?.let { program ->
            val replayed = program.diagnosticsOf(keys).filter { d ->
                d.fileName?.let { PathUtil.normalize(it) } in keys
            }
            narrowed[keys] = replayed
            return replayed
        }
        // The FIRST narrowed query of a project state arms a holder, so the SECOND
        // and every later one takes the branch above. Arming is behaviour-free for
        // this build's own answer — `ProjectRecheckTest` pins that, armed against
        // unarmed, narrowed and whole-program — so nothing here depends on whether a
        // holder was passed.
        val holder = RecheckHolder()
        val result = ProjectCompiler(overlay)
            .build(
                projectPath, noEmit = true, recheckOnly = keys, recheckHolder = holder,
                cancellation = cancellation,
            )
        // The partition already filters its checker's diagnostics to the assigned
        // files; the filter here is what makes that a PROPERTY of this member rather
        // than something inherited from the core's current partition rules — a
        // program-wide row carrying another file's name must not leak into an answer
        // about these files.
        val answer = result.diagnostics.filter { d ->
            d.fileName?.let { PathUtil.normalize(it) } in keys
        }
        narrowed[keys] = answer
        holder.recheck?.let { recheck = DiagnosticsOnlyRecheck(it) }
        return answer
    }

    /**
     * (INC.14) Checks [fileNames] NOW, so that every later SEMANTIC query about any
     * of them is answered without compiling.
     *
     * ## What it is
     *
     * One build, one `Checker`, one walk, over the file set a host declares as its
     * working set — an editor's open buffers — capturing every one of those files'
     * whole occurrence population in that single walk. [quickInfoAt],
     * [definitionsAt], [semanticsAt], [fileSemantics] and [documentHighlightsAt] then
     * answer about any prepared file for FREE, at any caret, until something is
     * edited. Where (INC.13) made the unit of a capture the BUFFER, this makes it the
     * WORKING SET: `N` buffers cost ONE build between them instead of `N`.
     *
     * Measured on the 78 sources of tsc's own compiler, one build answering `k`
     * queries against `k` builds answering one each: **1.79x at k = 2, 3.19x at
     * k = 8, 3.82x at k = 26**, because the floor every narrowed build pays — crawl,
     * bind, and the program-wide checker passes, ~345 ms there — is paid once
     * instead of `k` times.
     *
     * ## That it tells the truth is SWEPT, not argued
     *
     * A `Checker` carries caches that record which question reached a type FIRST, so
     * sharing one across queries could in principle make the ORDER of a host's
     * queries observable. `scripts/checker-reuse-differential.sh` compares this
     * arrangement against one fresh build per query, span for span and row for row:
     * over 1.07 M compared rows in an editor-ordered query sequence WITH REVISITS
     * (101 queries over 76 files, 25 of them asked again later by a DIFFERENT
     * checker), **no row differs**, and a file asked twice is answered identically
     * both times. In program order the same sweep finds ONE row of 741,864 — a
     * redundant self-intersection that the shared arm renders correctly and the
     * per-query arm does not.
     *
     * ## What it does NOT do
     *
     * It does not answer [diagnostics] or [diagnosticsOf], and that is a rule rather
     * than an omission: a capture build types nodes the checker had no reason to
     * type, so its diagnostics are not interchangeable with a plain build's
     * (`docs/language-service.md` § 3). A host that wants both prepares for hover and
     * calls `diagnosticsOf(theSameFiles)` for errors — itself ONE build for the whole
     * set, and since (INC.14) reused by every later per-file question about a file in
     * it.
     *
     * It also does not survive an EDIT, and nothing here does. Preparing again on
     * idle after an edit is the intended rhythm, exactly as a host debounces
     * [diagnostics].
     *
     * ## The cost, stated plainly
     *
     * It is a real build and it is not free: the first query is made DEARER so every
     * later one is free — the same trade (INC.13) made one granularity down. The work
     * is proportional to the identifiers in the prepared files, and the ANSWERS are
     * RETAINED until the next edit: one type and one definition per identifier per
     * file, which is the largest value this class ever holds. Prepare the buffers a
     * user is looking at, not the program.
     *
     * A file that is not part of the program contributes nothing rather than raising,
     * and an EMPTY collection prepares nothing and does not build — same reasoning as
     * [diagnosticsOf]. Preparing a set already covered by the current prepared check
     * does not build either, so a host may call this on every idle tick.
     */
    public fun prepare(fileNames: Collection<String>) {
        checkOpen()
        if (fileNames.isEmpty()) return
        val keys = fileNames.mapTo(LinkedHashSet()) { keyOf(it) }
        prepared?.let { if (keys.all { file -> file in it.files }) return }
        val spans = ArrayList<TypeCaptureSpan>()
        val covered = HashMap<String, Set<Long>>(keys.size * 2)
        for (file in keys) {
            val index = sourceIndexOf(file) ?: continue
            // The SAME list [captureAround] would ask for, from the same cached
            // parse — which is what makes a prepared answer serve that question
            // rather than merely resemble it.
            val fileWide = occurrenceSpansOf(file, index)
            if (fileWide.isEmpty()) continue
            spans.addAll(fileWide)
            covered[file] = fileWide.mapTo(HashSet(fileWide.size * 2)) { packSpan(it.start, it.end) }
        }
        // Every named file was unreadable or held no occurrence: there is no question
        // to prepare an answer to, and building would capture nothing.
        if (spans.isEmpty()) return
        val result = ProjectCompiler(overlay).build(
            projectPath,
            cancellation = cancellation,
            noEmit = true,
            recheckOnly = keys,
            typeCapture = TypeCaptureRequest(spans),
        )
        prepared = PreparedCheck(keys, covered, result)
    }


    // --- positions -------------------------------------------------------------

    /**
     * Line indexes, one per file, built on demand and dropped when that file is
     * edited.
     *
     * Cached because an editor asks many positions per keystroke and each
     * conversion would otherwise re-scan the whole file; keyed by the same
     * absolute path [updateFile] keys, so a query and an edit cannot miss each
     * other by spelling.
     */
    private val lineMaps = HashMap<String, LineMap>()

    /**
     * The 1-based (line, character) coordinate of the 0-based [offset] in
     * [fileName], or null when the file has no text to index.
     *
     * The text comes from the OVERLAY, so this answers about the caller's unsaved
     * buffer — the same bytes [diagnostics] type-checks. Reading through the
     * backing store instead would be silently wrong in the one situation the class
     * exists for: a position query and a diagnostic query would describe different
     * text, and every offset an editor mapped would be off by whatever the user
     * had typed.
     *
     * Null means "no such file, in the overlay or below it". A file that is
     * readable but not part of the program still answers, because "where is offset
     * N in this text" is a question about text and does not need a program — and
     * requiring membership would make the answer depend on a build.
     *
     * @throws IllegalArgumentException if [offset] is outside `0 .. <file length>`.
     */
    public fun positionAt(fileName: String, offset: Int): TextPosition? =
        lineMapOf(fileName)?.positionAt(offset)

    /**
     * The 0-based offset of the 1-based ([line], [character]) coordinate in
     * [fileName], or null when the file has no text to index.
     *
     * Reads the overlay, exactly as [positionAt] does. [character] is clamped into
     * the line and [line] is not — see [LineMap.offsetAt].
     *
     * @throws IllegalArgumentException if [line] is outside the file's line range.
     */
    public fun offsetAt(fileName: String, line: Int, character: Int): Int? =
        lineMapOf(fileName)?.offsetAt(line, character)

    /**
     * The line index of [fileName], built from the overlay's text and cached.
     *
     * INTERNAL on purpose. Handing the [LineMap] itself to a caller would hand out
     * an object that goes stale at the next [updateFile] with nothing to say so —
     * a host would cache it beside its own buffer and quietly convert against the
     * previous keystroke. The two conversions above consult the cache per call, so
     * an edit is always seen.
     */
    internal fun lineMapOf(fileName: String): LineMap? {
        checkOpen()
        val key = keyOf(fileName)
        lineMaps[key]?.let { return it }
        val text = overlay.readText(key) ?: return null
        return LineMap.of(text).also { lineMaps[key] = it }
    }

    // --- syntax ----------------------------------------------------------------

    /**
     * Parses, one per file, built on demand and dropped when that file is edited.
     *
     * Kept beside [lineMaps] and invalidated by the same paths, so a host cannot
     * observe a coordinate and a node that describe different text.
     */
    private val sourceIndexes = HashMap<String, SourceIndex>()

    /**
     * The resolved `tsconfig.json` options, loaded once and dropped when any JSON
     * file is edited.
     *
     * They are needed only because a parse is option-dependent (INV.1(e)), and
     * loading them is a file read plus a JSON parse — cheap, but not free per
     * keystroke.
     */
    private var parseOptions: CompilerOptions? = null

    /**
     * What the syntax tree says is at [offset] in [fileName], or null when there is
     * nothing there.
     *
     * The narrowest node whose real span contains [offset], described as a value.
     * Null means one of four things, all of them legitimate answers rather than
     * errors: the file has no text in the overlay or below it, [offset] is negative,
     * [offset] is at or past the end of the file (the caret after the last character
     * is inside no node — the spans are half-open), or the parse placed no node
     * there at all.
     *
     * Reads the OVERLAY, so it describes the caller's unsaved buffer, exactly as
     * [positionAt] does — and PARSES rather than builds, so a host may ask on a
     * dirty project without paying for a compile. It follows that the answer is
     * SYNTACTIC: it knows what the text is, never what it means. No type, no symbol,
     * no resolution.
     *
     * Trivia belongs to no node: an offset inside a comment, or in the whitespace
     * between two statements, resolves to the innermost node that ENCLOSES that
     * trivia — the source file for a comment between top-level statements, the block
     * for one inside a function body — because a node's span stops at its own last
     * token and leading comments are carried beside the following node rather than
     * inside it ([SourceIndex], and `NodeSpanSemanticsTest`'s finding 1).
     */
    public fun nodeInfoAt(fileName: String, offset: Int): NodeInfo? {
        val index = sourceIndexOf(fileName) ?: return null
        val path = index.pathAt(offset)
        val node = path.lastOrNull() ?: return null
        return NodeInfo(
            kind = node.kind.name,
            start = node.pos,
            end = index.realEndOf(node),
            // Outwards from the node: parent first, source file last. Taken from the
            // descent path rather than from `NodeBase.parent`, which is stamped by
            // the indexer and absent on anything synthesized.
            ancestorKinds = path.subList(0, path.size - 1).asReversed().map { it.kind.name },
        )
    }

    /**
     * The narrowest node containing [offset] in [fileName], or null.
     *
     * INTERNAL, and the deliberate counterpart of the public [nodeInfoAt]: whether
     * this API publishes `Node` at all is an open question that the next queue item
     * decides, and answering it here by accident would be the one direction that
     * cannot be walked back ([NodeInfo] carries the argument). Everything inside
     * this module that needs the node itself — and the tests that have to reach past
     * the descriptor to check the lookup — goes through this.
     */
    internal fun nodeAt(fileName: String, offset: Int): Node? =
        sourceIndexOf(fileName)?.pathAt(offset)?.lastOrNull()

    /**
     * (INC.36) The tree this project answers syntax questions about, BY IDENTITY.
     *
     * INTERNAL and for the pins only. The claim (INC.36) rests on cannot be
     * expressed as a value — two equal trees and one shared tree answer every
     * public query identically, and the difference is 103 MB — so the only
     * instrument that can see it is `===` against the parse the compiler itself
     * made. A heap assertion cannot do it (a sized assertion is a coin flip);
     * this can, deterministically.
     */
    internal fun parsedFileOf(fileName: String): SourceFile? =
        sourceIndexOf(fileName)?.sourceFile

    /**
     * The parse of [fileName], built from the overlay's text and cached.
     *
     * The flags come from the compiler's own [computeParserFlags] over the project's
     * resolved options, which is what makes this parse the parse a compile would
     * perform (INV.1(e)). Hand-rolling them would be silent drift: no assertion
     * about a node's position can see that a differently-flagged parser produced a
     * different tree, and `topLevelAwait` alone decides whether `await x` at the top
     * level of a file is an await expression or an identifier.
     */
    private fun sourceIndexOf(fileName: String): SourceIndex? {
        checkOpen()
        val key = keyOf(fileName)
        sourceIndexes[key]?.let { return upgradeIfShareable(key, it) }
        val text = overlay.readText(key) ?: return null
        val options = parseOptions
            ?: TsConfigLoader(overlay).load(configPath).options.also { parseOptions = it }
        val flags = computeParserFlags(key, text, options)
        val shared = parsedSourceOrNull(key, text, flags)
        if (shared == null) ownParses[key] = flags
        val index =
            if (shared != null) SourceIndex.around(text, shared)
            else SourceIndex.of(text, key, flags)
        return index.also { sourceIndexes[key] = it }
    }

    /**
     * (INC.36) The flags each entry of [sourceIndexes] whose tree is OUR OWN was
     * parsed with — i.e. the ones that missed [parsedSourceOrNull].
     *
     * An entry is in here exactly while its tree is a duplicate waiting to happen:
     * the compiler has not parsed those bytes, so a build over them later will,
     * and this project would then be holding the second copy. Keeping the FLAGS
     * rather than recomputing them is what makes [upgradeIfShareable] cheap enough
     * to run on every hit — `computeParserFlags` scans the content.
     *
     * Invalidated wherever [sourceIndexes] is, and by the same rules.
     */
    private val ownParses = HashMap<String, ParserFlags>()

    /**
     * (INC.36) Re-points a privately-parsed [index] at the compiler's own tree once
     * the compiler has one, dropping our copy.
     *
     * A file asked about while its buffer is dirty MUST parse privately — the
     * compiler has never seen those bytes, so the lookup misses, and that miss is
     * the correct answer rather than a shortfall. But the next build reads the same
     * overlay, so the compiler then holds an equal tree and this project's copy
     * becomes pure duplication — measured 103 MB over tsc's own 78 sources. This is
     * where that is collected, LAZILY: the cost is one token scan (no parse) and it
     * is paid only by a file that is asked about again after a build.
     *
     * Cheap on the common path by construction: an entry not in [ownParses] is
     * already shared and returns immediately, and [ownParses] holds only files
     * queried while dirty.
     *
     * Two residues this deliberately does not chase, recorded so they are not found
     * as surprises. A file queried while dirty and never queried again keeps its own
     * parse until it is re-edited or the project closes — sweeping it would need a
     * hook on every build site for a copy the host has stopped looking at. And
     * [parsedSourceOrNull] ends in a full content `String` compare, ~3.1 MB on
     * `checker.ts`: that is memcmp-speed and bounded TWICE — only a buffer whose
     * bytes the compiler has never seen is in [ownParses] at all, and the entry
     * leaves permanently the first time a build sees them, so every other file
     * returns on the map miss above before any compare happens.
     */
    private fun upgradeIfShareable(key: String, index: SourceIndex): SourceIndex {
        val flags = ownParses[key] ?: return index
        val shared = parsedSourceOrNull(key, index.text, flags) ?: return index
        ownParses.remove(key)
        return SourceIndex.around(index.text, shared).also { sourceIndexes[key] = it }
    }

    // --- semantics ---------------------------------------------------------------

    /**
     * What the TYPE CHECKER computed for the expression at [offset] in [fileName],
     * or null when there is nothing typed there.
     *
     * The narrowest node at [offset] is resolved exactly as [nodeInfoAt] resolves it
     * — same parse, same overlay, same half-open span rules — and then the compiler
     * is asked to record the type AT THAT NODE while it checks the program.
     *
     * ## This BUILDS, and it builds a SECOND time
     *
     * Unlike [nodeInfoAt] and [positionAt], this is a semantic question and only a
     * compile can answer it. It also does not reuse — and does not populate — the
     * build [diagnostics] caches: a build carrying a capture request types
     * expressions the checker had no reason to type, so it is not guaranteed to
     * produce the same diagnostics as a plain build, and quietly substituting one
     * for the other would make "what are my errors" depend on where the user last
     * hovered. Every unedited file still hits the compiler's content-keyed parse
     * cache, so the second build re-parses nothing.
     *
     * ## Why the position goes IN rather than the answer coming out
     *
     * The compiler's answer to "what is the type here" is a function of state that
     * exists only WHILE the checker walks — `currentLocalTypes` is built as it goes
     * and torn down again per statement anchor. Measured, asking a finished checker
     * instead answers a function-body local with a same-named GLOBAL's type and a
     * parameter with `any` (`TypeCaptureMeasurementTest` in the compiler core is the
     * measurement). So the position is handed to the build and the answer is
     * recorded in place; see `TypeCaptureRequest`.
     *
     * Null means: no such file, [offset] is outside every node, the node there is
     * not an expression (a caret on the `=` of `const a = 1` is inside no child of
     * the declaration — the `=` belongs to no node at all), or the checker never
     * typed it.
     *
     * ## (INC.13) The build it performs is the FILE's, and it is shared
     *
     * The caret's node is resolved here, but the question put to the compiler is the
     * whole file's — so the FIRST hover in a buffer is a whole-file capture and every
     * later caret in it, [definitionsAt] at any of them, [documentHighlightsAt] and
     * [fileSemantics] are answered from it without building. A caret that lands on a
     * node which is no occurrence — a call expression, a literal, a `this` — is asked
     * about alone instead; [captureAround] states the rule and the cost.
     */
    public fun quickInfoAt(fileName: String, offset: Int): QuickInfo? {
        val index = sourceIndexOf(fileName) ?: return null
        val node = index.pathAt(offset).lastOrNull() ?: return null
        val key = keyOf(fileName)
        // The RAW `Node.end` is the capture's IDENTITY — the compiler matches its own
        // nodes on the same pair, and INV.1(e) makes its parse of this text with these
        // flags the same parse. The REAL end, snapped back to the token stream, is
        // what the caller is told.
        val captured = captureAround(key, index, node)
            .capturedTypes
            .firstOrNull { it.fileName == key && it.start == node.pos && it.end == node.end }
            ?: return null
        return QuickInfo(
            kind = captured.kind,
            displayString = captured.typeText,
            start = node.pos,
            end = index.realEndOf(node),
        )
    }

    /**
     * Where the name at [offset] in [fileName] is DECLARED — the go-to-definition
     * answer — or an empty list when there is nothing to navigate to.
     *
     * The caret is resolved to a node exactly as [quickInfoAt] resolves it, and the
     * compiler is then asked to resolve the SYMBOL at that node while it walks. It
     * BUILDS, with the same caveats [quickInfoAt] documents: a separate build that
     * neither reads nor fills the [diagnostics] cache — and, since (INC.13), the same
     * FILE-WIDE build that member performs, so navigating after hovering anywhere in
     * the buffer builds nothing at all.
     *
     * ## More than one location is normal
     *
     * Declaration merging is a language feature: an `interface` declared twice, a
     * function and a namespace of the same name, a class and an interface. Every
     * contributing declaration is returned, in the compiler's own (deterministic)
     * order, and a host that wants one picks the first rather than assuming there
     * is only one.
     *
     * ## An imported name answers about the ORIGINAL
     *
     * `import { foo } from "./m"` and then a use of `foo` navigates to `foo` in
     * `./m`, not to the import statement — the import line is one keystroke away
     * anyway. When the module cannot be resolved the import binding itself is
     * returned, which is truthful and less useful.
     *
     * ## A MEMBER name answers about the MEMBER
     *
     * (API.3d) The `p` of `o.p` is resolved through the RECEIVER — `o`'s type is
     * computed and `p`'s property symbol on it is the answer — never through the
     * scope chain, which would find whatever unrelated `p` shares the spelling. So
     * an INHERITED member answers with the BASE's declaration, a member of a union
     * receiver answers with one location per constituent that declares it, and a
     * member of an imported interface answers in the file that declares it. A
     * qualified `N.x` or `N.T` where `N` is a namespace, a module alias or an enum
     * answers from that symbol's exports.
     *
     * ## What answers EMPTY, deliberately
     *
     * A member's own DECLARATION name — it already is the declaration. A chained
     * namespace segment (`A.B.x`). Labels, keywords, literals and any offset outside
     * every node: they name nothing, through either mechanism.
     *
     * Two more, and (API.7) SHARPENED THE REASON FOR BOTH — they were ranked with the
     * keyword and read/write refusals as wanting one missing "grammar position"
     * mechanism, and they do not. Recognising either shape is ONE test on the node's
     * own parent, which needed no classifier and never did; what each lacks is
     * SEMANTIC.
     *
     * - **an element access** (`o["p"]`) — the member is named by a string literal, so
     *   answering it needs the capture to accept a non-identifier node and to look a
     *   member up BY TEXT on the receiver's type. The receiver resolution is (API.3d)'s
     *   and is already here; the missing part is the channel, not the position.
     * - **an object-literal key being declared** (`{ p: v }`) — the useful target is
     *   the CONTEXTUAL type's property, and a contextual type is walk-scoped state this
     *   capture does not read (it is also absent outright in positions such as a
     *   ternary branch). That is a third resolution mechanism beside the scope chain
     *   and the receiver, and no syntactic classification supplies it.
     */
    public fun definitionsAt(fileName: String, offset: Int): List<DefinitionLocation> {
        val index = sourceIndexOf(fileName) ?: return emptyList()
        val node = index.pathAt(offset).lastOrNull() ?: return emptyList()
        val key = keyOf(fileName)
        // The RAW `Node.end` is the capture's IDENTITY, exactly as in `quickInfoAt`.
        return captureAround(key, index, node)
            .capturedDefinitions
            .firstOrNull { it.fileName == key && it.start == node.pos && it.end == node.end }
            // (API.10) …PLUS what a SHORTHAND's one token also names. `{ p }` under a
            // contextual type navigates to the local AND to the member, which is what
            // tsc answers; `related` stays out, because a heritage edge is a grouping
            // fact and not a navigation one.
            ?.let { it.locations + it.shorthand }
            ?.map { DefinitionLocation(it.fileName, it.start, it.length, it.kind) }
            ?: emptyList()
    }

    /**
     * (API.4a, API.4b) What may be written at [offset] in [fileName] — the
     * completion answer.
     *
     * ## What is answered
     *
     * MEMBER completions — the caret follows a `.` or a `?.` — are answered from the
     * receiver's TYPE: every member it has, its bases' included, comes back.
     *
     * FREE-NAME completions are answered from the LEXICAL SCOPE CHAIN in force at
     * the caret, enumerated during the build for the reason nothing else here can
     * be asked afterwards — the chain is torn down per file as the checker walks.
     * What comes back is every name the chain binds, innermost first and each
     * spelling ONCE (so a local shadowing an import appears as the local, not
     * twice), then the file's own declarations and imports, then the enclosing
     * namespaces', then the merged and lib GLOBALS filtered by what is actually
     * visible in this file (INV.3(c) — one module's exported name is not offered
     * inside another). A free-name item carries a name and a kind and no type; see
     * [CompletionItem] for the measurement behind that.
     *
     * ## What is deliberately NOT answered, each with its reason
     *
     * **A TYPE per free-name item** — see [CompletionItem].
     *
     * **FILTERING by the typed prefix**, at either kind. The prefix is reported and
     * the full set comes back; ranking is host policy and a cut list cannot be
     * re-ranked ([CompletionList]).
     *
     * ## Two known imprecisions, stated rather than hidden
     *
     * A name declared LATER in the same block is offered (`co|` above a `const
     * count`), because a block's bindings are a set and not a sequence. That is what
     * tsc does too — the binding exists, it is merely in its temporal dead zone.
     * And a function's body locals are visible from inside its own PARAMETER
     * DEFAULTS, because the binder's function scope is flat; writing one there is an
     * error the checker reports separately.
     *
     * ## The anchor is a token question, not a node question
     *
     * Every other semantic member of this class starts from a node that exists at
     * the caret. A completion request has none — the user is mid-identifier, or
     * sitting right after a `.` with nothing typed — so the position is resolved
     * against the TOKEN STREAM instead, and the receiver is then recovered from the
     * parse. `CompletionAnchor` and `SourceIndex.completionAnchorAt` carry the full
     * rule, including what happens to an incomplete `o.`: this parser always builds
     * a property access for a `.`, synthesizing an empty name and reporting TS1003,
     * so the receiver is a real node even at end of file. A `.` the parse did not
     * turn into a member access answers an empty list rather than guessing a
     * receiver from raw text.
     *
     * ## ACCESSIBILITY IS ENFORCED since (API.7), and this changed an answer
     *
     * A `private` member (including a `#name` field) is offered only inside its
     * declaring class, and a `protected` one only inside that class or a class that
     * derives from it — statics included, and a caret in a nested arrow inside a
     * method counts as inside the method's class. Round 917 REPORTED accessibility and
     * refused to act on it, for a stated reason: filtering needs to know where the
     * caret sits relative to the declaring class. `SyntaxRoles`' sibling ascent inside
     * the checker is that mechanism.
     *
     * The filter is biased and the bias is the whole safety argument: IT HIDES ONLY
     * WHAT IT CAN PROVE INACCESSIBLE. A member whose declaring class cannot be found,
     * a base class named by an expression the checker does not resolve, a heritage
     * chain past its depth cap — every unknown leaves the item OFFERED, because a list
     * that has silently lost a real candidate is indistinguishable from a complete
     * one. [CompletionItem.accessibility] still reports what survives, so a host that
     * wants to grey an item rather than hide it has what it needs.
     *
     * ## KEYWORDS ARE OFFERED since (API.7), and only where they compile
     *
     * Round 918 refused them and named the reason: a useful list is context-sensitive
     * and the anchor was a token-level device with no grammar position to key one on.
     * `SyntaxRoles.keywordsFor` is that grammar position. A STATEMENT caret gets the
     * statement and declaration starters plus the expression starters; an EXPRESSION
     * caret gets the expression starters ONLY, which is what keeps `interface` out of
     * `f(|)`; a TYPE caret gets the primitive type names plus `keyof` and `typeof`; a
     * class body, a heritage clause and an import clause get NOTHING. `await` needs an
     * enclosing async function, `yield` a generator, `super` a class, `return` a
     * function, `break` a loop or a `switch`, `continue` a loop, and `import` /
     * `export` / `declare` / `namespace` / `interface` / `type` / `enum` a module or
     * namespace body.
     *
     * The list is deliberately SHORT rather than complete: continuation keywords
     * (`else`, `case`, `extends`, `implements`, `as`, `satisfies`, `infer`, `readonly`,
     * the accessibility modifiers) are not offered anywhere, because their positions
     * are ones this classifier declines to name. Merge your own list for those; every
     * item in THIS one compiles where it is offered, which is the property the member
     * half already had. A keyword item's [CompletionItem.kind] is `"Keyword"`, and a
     * spelling the scope chain also binds is reported as the BINDING, once.
     *
     * Keywords are offered at FREE-NAME carets only; after a `.` no keyword may be
     * written at all.
     *
     * ## Cost
     *
     * ONE build, with the same caveats [quickInfoAt] documents: it does not read or
     * fill the [diagnostics] cache. A caret that admits no completion — inside a
     * string, a comment or a numeric literal, or outside the file — DOES NOT BUILD.
     * Only one caret is described per build; a host describing several is asking
     * [semanticsAt]'s question, not this one.
     */
    public fun completionsAt(fileName: String, offset: Int): CompletionList {
        val index = sourceIndexOf(fileName)
            ?: return CompletionList(
                CompletionKind.NONE,
                "",
                offset,
                offset,
                emptyList(),
                CompletionRefusal.NO_COMPLETION_CONTEXT,
            )
        val anchor = index.completionAnchorAt(offset)
        val key = keyOf(fileName)
        if (anchor.kind == CompletionKind.FREE_NAME) {
            // (API.7) The keywords are a purely syntactic answer and cost no build, so
            // they are merged in whether or not the scope enumeration produces one.
            val keywords = anchor.keywords.map {
                CompletionItem(
                    name = it,
                    kind = "Keyword",
                    typeText = "",
                    optional = false,
                    readonly = false,
                    accessibility = "public",
                )
            }
            // The RAW `Node.end` is the capture's IDENTITY, exactly as in `quickInfoAt`.
            val node = anchor.scopeAnchor
                ?: return CompletionList(
                    anchor.kind,
                    anchor.prefix,
                    anchor.replacementStart,
                    anchor.replacementEnd,
                    keywords.sortedBy { it.name },
                    null,
                )
            val span = TypeCaptureSpan(key, node.pos, node.end)
            val captured = captureIn(
                TypeCaptureRequest(spans = emptyList(), scopeSpans = listOf(span)),
            )
                .capturedScopes
                .firstOrNull { it.fileName == key && it.start == node.pos && it.end == node.end }
            return CompletionList(
                CompletionKind.FREE_NAME,
                anchor.prefix,
                anchor.replacementStart,
                anchor.replacementEnd,
                mergeKeywords(
                    captured?.names.orEmpty().map {
                        CompletionItem(
                            name = it.name,
                            kind = it.kind,
                            // Empty by decision, not by omission — see [CompletionItem].
                            typeText = "",
                            optional = false,
                            readonly = false,
                            accessibility = "public",
                        )
                    },
                    keywords,
                ),
                null,
            )
        }
        val refusal = when (anchor.kind) {
            CompletionKind.NONE -> CompletionRefusal.NO_COMPLETION_CONTEXT
            else -> null
        }
        val receiver = anchor.receiver
        // A refused kind and a `.` with no receiver both answer without building: a
        // query that cannot use a compile must not pay for one.
        if (refusal != null || receiver == null) {
            return CompletionList(
                anchor.kind,
                anchor.prefix,
                anchor.replacementStart,
                anchor.replacementEnd,
                emptyList(),
                refusal,
            )
        }
        // The RAW `Node.end` is the capture's IDENTITY, exactly as in `quickInfoAt`.
        val span = TypeCaptureSpan(key, receiver.pos, receiver.end)
        val captured = captureIn(
            TypeCaptureRequest(spans = emptyList(), memberSpans = listOf(span)),
        )
            .capturedMembers
            .firstOrNull {
                it.fileName == key && it.start == receiver.pos && it.end == receiver.end
            }
        return CompletionList(
            CompletionKind.MEMBER,
            anchor.prefix,
            anchor.replacementStart,
            anchor.replacementEnd,
            captured?.members.orEmpty().map {
                CompletionItem(
                    name = it.name,
                    kind = it.kind,
                    typeText = it.typeText,
                    optional = it.optional,
                    readonly = it.readonly,
                    accessibility = it.accessibility,
                )
            },
            null,
        )
    }

    /**
     * (API.7) The scope's names and the position's keywords as ONE sorted list.
     *
     * A keyword whose spelling the scope chain also binds — a variable literally named
     * `type`, which is legal — is kept as the BINDING and the keyword is dropped: the
     * scope's answer is the one a host can navigate to, and offering the spelling twice
     * would render two identical rows.
     */
    private fun mergeKeywords(
        names: List<CompletionItem>,
        keywords: List<CompletionItem>,
    ): List<CompletionItem> {
        val bound = names.mapTo(HashSet(names.size)) { it.name }
        val merged = ArrayList<CompletionItem>(names.size + keywords.size)
        merged.addAll(names)
        for (keyword in keywords) if (keyword.name !in bound) merged.add(keyword)
        merged.sortBy { it.name }
        return merged
    }

    /**
     * (API.6) What may be passed at [offset] in [fileName] — the signature-help
     * answer — or null when the caret is in no argument list.
     *
     * ## Two different negatives
     *
     * NULL means "the caret is not inside a call's argument list": on a callee, past
     * a closing paren, in a comment, in an unknown file. A non-null answer with an
     * EMPTY [SignatureHelp.signatures] means "the caret IS in an argument list and
     * the callee has no signatures" — it is `any`, unresolvable, or not callable. A
     * host draws nothing in either case and only the second is worth a log line.
     *
     * ## Every overload, in declaration order
     *
     * The callee's whole signature list comes back, which is the feature: an editor
     * shows "2 of 3" and lets the user page through. The callee is resolved by the
     * compiler's own callee resolution — the (API.3d) receiver path — so a method
     * through a receiver, a namespace member, an imported function and a callee that
     * is itself a call all answer without a rule of their own.
     *
     * ## Which one is ACTIVE
     *
     * The first signature that could still become this call: one that has room for
     * the argument the caret is on (its index is within the parameter list, or the
     * signature ends in a rest parameter, or it takes no parameters and none have
     * been passed) AND that accepts every argument the user has already FINISHED,
     * judged by the same predicate the compiler selects an overload with. The
     * argument the caret is IN is deliberately not judged — it is half-typed by
     * construction, so testing it would flip the highlighted overload back and forth
     * under the user's hands. When nothing qualifies the answer is 0, reported rather
     * than hidden.
     *
     * ## A GENERIC callee renders UNINSTANTIATED
     *
     * `pick<T>(xs: T[], i: number): T`, not a substitution. Inferring `T` would mean
     * inferring it from arguments that are not finished, and the declared form is
     * what tells the reader that `T` is inferred at all.
     *
     * ## The anchor is a token question, like a completion's and unlike everything else
     *
     * There is no node at a caret in `f(a, |)`, and for `f(` at end of file or
     * `f(a,` before a `}` the call node's own real end lies BEFORE the caret — so no
     * descent reaches it. The parser does build the call in all of those cases (it
     * creates a `CallExpression` the moment it sees a `(` and then reports the
     * missing `)`), so the region is recovered by BRACKET MATCHING over the token
     * stream and the argument index by COUNTING COMMAS, which is what an argument
     * index physically is. `SourceIndex.signatureAnchorAt` carries the full rule.
     *
     * ## What is refused, each with a reason
     *
     * - a TAGGED TEMPLATE (`` tag`a${b}` ``) — it has no parenthesized argument list,
     *   and counting template substitutions as arguments is a second mechanism;
     * - TYPE ARGUMENTS (`f<|>(x)`) — not an argument list;
     * - `super(...)` — `super` is an ordinary identifier in this parser and binds to
     *   nothing, so its callee type resolves to nothing; the answer is an empty
     *   signature list rather than the enclosing class's constructor;
     * - a JSX attribute list — not a call;
     * - READING a spread's arity: `f(...xs, |)` reports argument 1, because the
     *   commas say so and how many arguments `xs` contributes is not a syntactic
     *   question.
     *
     * DECORATOR factories (`@dec(|)`) and a callee that is itself a call (`f()(|)`)
     * are NOT refused — they are ordinary calls and fall out of the general rule.
     *
     * ## Cost
     *
     * ONE build, with the same caveats [quickInfoAt] documents: it does not read or
     * fill the [diagnostics] cache. A caret in no argument list DOES NOT BUILD.
     */
    public fun signatureHelpAt(fileName: String, offset: Int): SignatureHelp? {
        val index = sourceIndexOf(fileName) ?: return null
        val anchor = index.signatureAnchorAt(offset) ?: return null
        val key = keyOf(fileName)
        val call = anchor.call
        // The RAW `Node.end` is the capture's IDENTITY, exactly as in `quickInfoAt`.
        val captured = captureIn(
            TypeCaptureRequest(
                spans = emptyList(),
                signatureSpans = listOf(
                    SignatureCaptureSpan(key, call.pos, call.end, anchor.activeArgument),
                ),
            ),
        )
            .capturedSignatures
            .firstOrNull { it.fileName == key && it.start == call.pos && it.end == call.end }
        return SignatureHelp(
            signatures = captured?.signatures.orEmpty().map { signature ->
                SignatureInfo(
                    label = signature.label,
                    parameters = signature.parameters.map {
                        ParameterInfo(
                            name = it.name,
                            typeText = it.typeText,
                            optional = it.optional,
                            isRest = it.isRest,
                            labelStart = it.labelStart,
                            labelEnd = it.labelEnd,
                        )
                    },
                    returnTypeText = signature.returnTypeText,
                    activeParameter = signature.activeParameter,
                )
            },
            activeSignature = captured?.activeSignature ?: 0,
            activeArgument = anchor.activeArgument,
        )
    }

    /**
     * (API.3c) Everything the compiler knows about the nodes at [offsets] in
     * [fileName] — every hover answer and every go-to-definition answer — in ONE
     * build.
     *
     * THIS IS THE MEMBER AN EDITOR SHOULD USE. [quickInfoAt] and [definitionsAt]
     * each build, so describing one caret both ways costs two compiles and
     * describing twenty carets costs forty; this costs one, whatever the count,
     * because the compiler takes a SET of spans and records at all of them during
     * the single walk it was going to perform anyway. `ProjectSemanticsTest` pins
     * that as a COUNT of builds rather than as a duration.
     *
     * ## What comes back, and in what order
     *
     * One [SemanticInfo] per DISTINCT span, sorted by `(start, end)` ascending.
     * Distinct span, not distinct offset: several carets inside one identifier name
     * the same node and collapse to one entry, and an offset that lands in no node
     * at all contributes none. So the result is neither indexed by nor the same
     * length as [offsets], and a caller maps back by containment — `start <= offset
     * < end` — which is the same half-open rule [nodeInfoAt] answers under.
     *
     * The order is imposed here rather than inherited: the compiler returns its
     * answers in the order its walk happened to reach the nodes, which is an
     * implementation property and would silently reorder under any change to the
     * check spine.
     *
     * An EMPTY [offsets] — or one where nothing resolves — answers an empty list
     * and DOES NOT BUILD. A caller that asks about nothing must not pay for a
     * compile.
     *
     * ## The caveats are [quickInfoAt]'s, unchanged
     *
     * It builds, that build is not the [diagnostics] build and does not become it,
     * and it reads the overlay. Batching changes the count of compiles, nothing
     * about what one compile is.
     */
    public fun semanticsAt(fileName: String, offsets: List<Int>): List<SemanticInfo> {
        val index = sourceIndexOf(fileName) ?: return emptyList()
        val nodes = ArrayList<Node>(offsets.size)
        for (offset in offsets) index.pathAt(offset).lastOrNull()?.let { nodes.add(it) }
        return semanticsOf(fileName, index, nodes)
    }

    /**
     * (API.3c) Everything the compiler knows about every IDENTIFIER in [fileName],
     * in ONE build.
     *
     * The whole-file form of [semanticsAt], expressed in terms of it — same value,
     * same ordering, same single build — for the two host features that want a file
     * rather than a caret: semantic highlighting, which has to draw every name at
     * once, and hover prefetch, which wants the answers before the user asks.
     *
     * ## The candidate set is exactly the identifiers
     *
     * Every `Identifier` node, member names included; no keywords, no punctuation,
     * no literals, no larger expressions. [SourceIndex.identifiers] carries the
     * argument for that boundary. A file with none of them — an empty buffer, a file
     * of comments — answers an empty list without building.
     *
     * ## Cost, stated plainly
     *
     * ONE compile, plus the checker typing each of those identifiers. That is
     * linear in the file and it is not free: this is the member to call when a file
     * is opened or saved, not the one to call per keystroke. The compile itself
     * dominates, and it is the compile this API cannot avoid ([Project]'s own KDoc
     * says why there is no incremental reuse to lean on).
     *
     * (INC.13) …and it is the SAME compile [quickInfoAt], [definitionsAt] and
     * [documentHighlightsAt] perform, so whichever of them a host asks first pays for
     * all of them until the buffer changes. The build types a few spans this member
     * does not report — the member-NAME literals [documentHighlightsAt] sweeps — which
     * is what makes the four one question; the ANSWER is still every `Identifier` and
     * nothing else.
     */
    public fun fileSemantics(fileName: String): List<SemanticInfo> {
        val index = sourceIndexOf(fileName) ?: return emptyList()
        return semanticsOf(fileName, index, index.identifiers())
    }

    /**
     * (API.5) Every place in the PROGRAM that refers to the same thing as the caret
     * at [offset] in [fileName] — the find-references answer.
     *
     * ## How "the same thing" is decided, and why it is not a name match
     *
     * A text search is not an answer: two bindings spelled alike are two things, and
     * offering the wrong one is the failure this whole API is built to avoid. What
     * decides it here is the **set of declarations** each occurrence resolves to.
     * Every identifier in every program file is handed to ONE build, the checker
     * resolves each of them while it walks past it — through the lexical scope chain
     * for a free name and through the receiver's type for a member, exactly as
     * [definitionsAt] does — and two occurrences are the same thing when their
     * declaration sets INTERSECT.
     *
     * Intersection rather than equality, for one measured reason: a member of a UNION
     * receiver resolves to one declaration per constituent (`u.p` where
     * `u: {p: string} | {p: number}` names both), so equality would put `u.p` in a
     * different group from the single-constituent `a.p` that is plainly the same
     * declaration. Equality is the degenerate case and is what every single-symbol
     * position gets.
     *
     * What that buys, none of it special-cased:
     *
     * - an **import** and the name it imports are one group, because the capture hops
     *   the alias to the original — so `import { foo }`, every use of `foo` here, and
     *   `export const foo` over there all answer together;
     * - **merged declarations** (an `interface` written twice, a function and a
     *   namespace of one name) do not split: every occurrence names the same
     *   multi-declaration set;
     * - a **shadowed** name does not leak: the body local, the file-level binding it
     *   shadows and a third of the same spelling in another file are three groups,
     *   because the walk's own chain resolved each of them;
     * - an **inherited** or **generically instantiated** member answers with the base
     *   or the uninstantiated declaration, so uses through a derived type and through
     *   the base are one group.
     *
     * ## The declaration is included, flagged
     *
     * [ReferenceLocation.isDeclaration] marks the spans that ARE declarations of the
     * caret's symbol rather than uses of it, the way tsc's own `isDefinition` does. A
     * host that wants uses only filters on it. The flag is exact: it is membership in
     * the declaration set the compiler produced, not a guess about which parent kinds
     * declare a name.
     *
     * ## READ versus WRITE — refused by round 919, ANSWERED since (API.7)
     *
     * [ReferenceLocation.use] reports it. Round 919's refusal named the exact reason
     * it could not: `x = 1` and `x++` are trivially writes while `[x] = pair`,
     * `({ x } = o)` and `for (x of xs)` are writes whose identifier sits under an
     * array literal, an object literal or a `for` head, so a rule built from the easy
     * positions calls the destructuring ones READS. `SyntaxRoles` is the mechanism
     * that was missing — a pull-based ascent of the parent chain in which a
     * destructuring pattern of any depth is a run of pass-through steps ending at ONE
     * assignment test — and [ReferenceUse] states the whole write set. An occurrence
     * the classifier does not place is [ReferenceUse.UNCLASSIFIED] rather than a read,
     * so the gap the refusal was protecting stays visible.
     *
     * ## What is refused, and why each is a refusal rather than a gap
     *
     * **A caret on a MEMBER's own declaration name is answered only when that member
     * is referenced somewhere.** `p` in `interface I { p: string }` is bound by no
     * scope and has no receiver, so the capture resolves it to nothing — exactly why
     * [definitionsAt] answers empty there. The reference search recovers it anyway,
     * because the evidence is in the sweep: if any occurrence resolved TO that span,
     * the caret is a declaration of that occurrence's symbol. A member declared and
     * never used therefore answers an EMPTY list rather than a list of one. Free names
     * are unaffected — a `const`, a parameter, a function, a class, an interface, an
     * import all resolve from their own declaration name, so a caret there is the
     * ordinary case.
     *
     * **Only identifiers.** A caret on a keyword, a literal, punctuation or trivia
     * answers empty AND DOES NOT BUILD. An element access (`o["p"]`) names its member
     * with a string literal, so it is neither found nor searchable — the same boundary
     * [definitionsAt] draws.
     *
     * **The program, not the libraries.** The sweep covers the program's own files.
     * A declaration in a `lib.*.d.ts` still comes back (flagged
     * [ReferenceLocation.isDeclaration]) because the caret resolved to it, but no lib
     * file is swept for uses — `"abc".length` finds the lib's `length` and the uses in
     * your own code, never the lib's internal ones.
     *
     * ## Order, and duplicates
     *
     * Sorted by `(fileName, start)` ascending. The ordering is imposed here rather
     * than inherited from the compiler, whose answer order is the order its walk
     * happened to reach the nodes. One entry per distinct span.
     *
     * ## Cost — read this before wiring it to a keybinding
     *
     * TWO builds on a dirty project and ONE on a clean one: the file list is a
     * question about the program, so [files]' build runs first (cached when nothing
     * has been edited), and the sweep itself is a single capture build.
     *
     * **(INC.44) That build is no longer whole-program.** The sweep carries only the
     * occurrences that SPELL a name this symbol can be reached by ([narrowedSweep]),
     * and [captureIn] derives the check partition from the request — so a search for a
     * name written in three files checks three files, not seventy-eight. The claim is
     * unchanged and so is the answer; what changed is that the evidence is selected
     * before it is typed rather than after. A symbol whose spellings cannot be bounded
     * syntactically — anything reached through a default export, an `export =`, an
     * `import x = require(…)` or a namespace binding — falls back to the
     * whole-program sweep this member always did.
     *
     * So the cost is linear in the part of the program that MENTIONS the name, plus
     * the floor. `docs/language-service.md` carries the measured figures on this
     * repo's own 78-file compiler profile. [documentHighlightsAt] is still the one to
     * wire to caret movement; this is the one a user asks for explicitly.
     */
    public fun referencesAt(fileName: String, offset: Int): List<ReferenceLocation> {
        val index = sourceIndexOf(fileName) ?: return emptyList()
        // Only an identifier names anything, and a caret that cannot be answered must
        // not pay for a compile — the rule `completionsAt` and `semanticsAt` already
        // follow.
        val caret = occurrenceCaret(index, offset) ?: return emptyList()
        // The program's files are what a build computes, so this asks for them the
        // only way there is; `build()` is cached whenever nothing has been edited.
        val swept = build().programFiles.map { keyOf(it) }
        // (INC.44) The CLAIM is still about every file; what narrows is the EVIDENCE.
        // An occurrence of this symbol must SPELL one of its names, so a file with no
        // such token can hold none — which turns the sweep from "type every identifier
        // in the program" into "type the few that could possibly be an answer", and
        // (through [captureIn]'s derived partition) the check with it. Null means the
        // closure could not be bounded and today's whole-program sweep stands.
        val narrowed = if (narrowReferenceSweeps) narrowedSweep(caret, swept) else null
        return referencesOf(
            keyOf(fileName), caret,
            sweptFiles = narrowed?.keys?.toList() ?: swept,
            restrictToQueryFile = false,
            narrow = narrowed != null,
            sweptNodes = narrowed,
        )
    }

    /**
     * (INC.44) THE SPELLING-NARROWED SWEEP: every occurrence in the program that could
     * possibly be an occurrence of [caret]'s symbol, by file — or null when it cannot
     * be bounded and the caller must sweep everything.
     *
     * ## Why this is sound, and where the soundness actually lives
     *
     * An occurrence is an answer only if [definitionMeets] holds, i.e. only if its
     * declaration set intersects the seed's. The population of nodes that can satisfy
     * that is not "every identifier": it is every identifier SPELLED one of the names
     * the symbol can be reached by. That set is normally one name, and it grows only
     * through `import { p as q }` / `export { p as q }` — the two forms in which one
     * symbol carries two written spellings ([SyntaxRoles.aliasLink]).
     *
     * **The closure is anchored by a fact about those two forms**: both names are
     * tokens OF THE FILE THAT WRITES THE ALIAS, so a file that introduces an alias of
     * a name already in the set necessarily contains that name — which is why
     * iterating "select the files containing a name I am looking for, read the aliases
     * they declare, repeat" reaches a fixed point without ever scanning a file the
     * search had no other reason to open. Every form for which that is NOT true is
     * refused outright by [SyntaxRoles.isAliasEscape] rather than approximated.
     *
     * `default` is the third refusal and it is not an escape SHAPE but a NAME:
     * `export { foo as default }` links `foo` to a spelling whose importing side is an
     * `import d from …`, and `d` is reachable from neither end. Any closure that
     * reaches `default` therefore gives up.
     *
     * ## What a mistake here costs, and hence which way it is biased
     *
     * A name wrongly INCLUDED costs a file in the partition and some spans in the
     * request — time, and nothing else, because the answer is still decided by
     * resolution. A name wrongly EXCLUDED costs a MISSING REFERENCE, which is silent
     * and which a rename would then leave stranded. So every uncertainty answers
     * "sweep everything", and the file filter is a plain SUBSTRING test rather than a
     * token one: it may only over-select.
     */
    /**
     * (INC.44) THE IN-BINARY OFF ARM for [narrowedSweep] — `false` restores the
     * whole-program reference sweep this API shipped before it.
     *
     * It exists because the narrowing's correctness is not an argument but a
     * DIFFERENTIAL: the two arms answer the same question, so they must return the
     * same list, element for element, at every caret — and a differential needs both
     * arms in one binary or it is comparing two builds instead of two policies. The
     * same shape `SourceIndex.of(…, useParseAsLexerOracle = false)` has (API.6).
     *
     * Internal, defaulting to the shipped behaviour, and read at exactly one site.
     * `ReferenceNarrowingDifferentialMain` sweeps it over a real project;
     * `ProjectReferenceNarrowingTest` pins the agreement per shape.
     */
    internal var narrowReferenceSweeps: Boolean = true

    /**
     * (INC.44) THE CONTROL for [narrowReferenceSweeps]: how many files a reference
     * search at this caret would check, or **-1** when its spellings cannot be bounded
     * and the whole-program sweep stands.
     *
     * It exists because a fallback agrees with itself. A differential over a project
     * where every caret refused would print EQUIVALENT having compared the
     * whole-program arm with itself at every row — round 790's "a verifier reads 0
     * both when the skip is sound and when the instrument is dead" — so the number of
     * carets that actually took the narrow path has to be observable from outside, and
     * an over-selected partition has to be countable rather than inferred from a wall
     * clock this box cannot hold still.
     *
     * Internal: a host is told the COST model in `docs/language-service.md`, not the
     * partition, because the partition is an implementation detail that a later round
     * may shrink further without changing any answer.
     */
    internal fun narrowedSweepFiles(fileName: String, offset: Int): Int {
        val index = sourceIndexOf(fileName) ?: return -1
        val caret = occurrenceCaret(index, offset) ?: return -1
        val swept = build().programFiles.map { keyOf(it) }
        return narrowedSweep(caret, swept)?.size ?: -1
    }

    private fun narrowedSweep(caret: Node, programFiles: List<String>): Map<String, List<Node>>? {
        val caretText = SyntaxRoles.occurrenceText(caret)
        if (caretText.isEmpty() || caretText == DEFAULT_EXPORT_NAME) return null
        var names = setOf(caretText)
        val texts = HashMap<String, String>(programFiles.size)
        val occurrences = HashMap<String, List<Node>>(programFiles.size)
        // Bounded by construction — each pass either stops or adds a name, and the
        // program has finitely many — but the bound is stated anyway, because a
        // non-terminating language-service query wedges an editor with no diagnosis.
        repeat(MAX_ALIAS_CLOSURE_PASSES) {
            val selected = LinkedHashMap<String, List<Node>>()
            val grown = LinkedHashSet(names)
            for (file in programFiles) {
                val text = texts.getOrPut(file) { overlay.readText(file) ?: "" }
                if (!mayHideAnEscapedName(text) && names.none { text.contains(it) }) continue
                val index = sourceIndexOf(file) ?: continue
                val found = occurrences.getOrPut(file) { index.occurrenceNodes() }
                    .filter { SyntaxRoles.occurrenceText(it) in names }
                if (found.isEmpty()) continue
                for (node in found) {
                    if (SyntaxRoles.isAliasEscape(node)) return null
                    val linked = SyntaxRoles.aliasLink(node) ?: continue
                    if (linked == DEFAULT_EXPORT_NAME) return null
                    grown.add(linked)
                }
                selected[file] = found
            }
            if (grown.size == names.size) return selected
            names = grown
        }
        return null
    }

    /**
     * (INC.44) True when [text] may contain an occurrence whose NAME is not written in
     * it — the one thing that stops a substring test from being an exact file filter.
     *
     * An occurrence's name is the COOKED value: `StringLiteralNode.text` is what the
     * scanner built out of the escape sequences (`rawText` is the source), so
     * `o["pl\ain"]` names the member `plain` while the file spells `pl\ain` — and an
     * identity escape means ANY backslash inside a literal can do this, not only
     * `\u`. An identifier written with a unicode escape is the same hazard one token
     * class over.
     *
     * So the exact test is `occurrenceText(node) in names`, which needs the file's
     * index, and the substring test is only allowed to SKIP a file it can prove has no
     * such spelling — a file with no backslash in it at all. Measured on tsc's own 78
     * compiler sources, that skips 49 of them (21.8% of the characters are in the
     * other 29); the rest are opened and then contribute nothing unless a real
     * occurrence survives the exact filter, so the PARTITION stays exact either way
     * and only the indexing cost moves.
     *
     * Getting this wrong in the permissive direction would cost a missing reference,
     * silently — which is why the rule is "no backslash anywhere" and not a smarter
     * scan for `\u` alone.
     */
    private fun mayHideAnEscapedName(text: String): Boolean = text.contains('\\')

    /**
     * (API.5) Every place in THIS FILE that refers to the same thing as the caret —
     * the document-highlight answer.
     *
     * [referencesAt] restricted to one file, and it exists as its own member because
     * the cost differs by the size of the program: an editor asks this on every caret
     * move, and sweeping the whole program for an answer it will draw in one buffer
     * would be paying for the other files' identifiers to be typed and resolved.
     * Here the sweep is one file's identifiers, which is [fileSemantics]' population,
     * and there is no [files] build in front of it because no file list is needed.
     *
     * Everything else is [referencesAt]'s and unchanged: the same declaration-set
     * identity, the same [ReferenceLocation.isDeclaration] flag, the same
     * [ReferenceLocation.use] classification, the same ordering.
     *
     * ## The one behaviour that is NOT just a filter
     *
     * The caret's own resolution is taken from this file's sweep, so the fallback that
     * recovers a caret on a MEMBER's declaration name ([referencesAt]) can only see
     * evidence in THIS file: `p` in `interface I { p: string }` highlights only when
     * some `o.p` in the same file resolves to it. Filtering [referencesAt]'s answer
     * would differ there — and would cost a whole-program build to produce a
     * one-file answer, which is the trade this member exists to refuse.
     */
    public fun documentHighlightsAt(fileName: String, offset: Int): List<ReferenceLocation> {
        val index = sourceIndexOf(fileName) ?: return emptyList()
        val caret = occurrenceCaret(index, offset) ?: return emptyList()
        val key = keyOf(fileName)
        // (INC.2b) One file swept, one file checked — the narrowing [captureIn]
        // describes, and the reason this member's cost differs from [referencesAt]'s
        // by more than the sweep's size.
        return referencesOf(
            key, caret, listOf(key), restrictToQueryFile = true, narrow = true,
        )
    }

    /**
     * The one build both reference queries perform, and the grouping of its answers.
     *
     * [sweptFiles] is the population whose identifiers become capture spans — the
     * files mentioning the name for [referencesAt] (INC.44), the one queried file for
     * [documentHighlightsAt] — and [restrictToQueryFile] additionally drops answers
     * outside [queryFile], which matters only for a DECLARATION the caret resolves to
     * in a file that was never swept. [sweptNodes], when given, is the already-selected
     * occurrence list per file; without it every occurrence node in each swept file is
     * asked about. [narrow] routes the build through [captureIn], which derives the
     * check partition from the request's own spans.
     *
     * Note what is not done: no `Symbol` is asked for and none crosses the boundary.
     * The grouping key is a set of declaration SPANS, which is a value, which is what
     * lets the whole feature sit above the compiler rather than inside it.
     */
    private fun referencesOf(
        queryFile: String,
        caret: Node,
        sweptFiles: List<String>,
        restrictToQueryFile: Boolean,
        narrow: Boolean,
        sweptNodes: Map<String, List<Node>>? = null,
    ): List<ReferenceLocation> {
        val spans = ArrayList<TypeCaptureSpan>()
        // Every swept occurrence's REAL span and syntactic ROLE, by (file, pos). The
        // capture speaks the RAW `(pos, end)` identity and a caller must be told the
        // real extent, so the translation is recorded here rather than re-derived per
        // answer — and since (API.9) the extent is a start as well as an end, because
        // an `o["p"]`'s span is the text BETWEEN the quotes. The role is classified
        // here, from the parse this module already owns, because read-versus-write is a
        // question about the OCCURRENCE and not about the symbol.
        val extents = HashMap<String, HashMap<Int, SweptSpan>>(sweptFiles.size)
        for (file in sweptFiles) {
            val index = sourceIndexOf(file) ?: continue
            val found = HashMap<Int, SweptSpan>()
            // (INC.13) The span list comes from [occurrenceSpansOf] rather than being
            // rebuilt here, because a one-file sweep's request must be EQUAL — as a
            // value, element for element — to the one [captureAround] builds, or the
            // hover that preceded this highlight does not share its build.
            // (INC.44) …unless the caller has already SELECTED this file's
            // occurrences by spelling, in which case the request is that selection and
            // the memo it keys is a different (smaller) one on purpose. The one-file
            // caller passes null and so keeps sharing [captureAround]'s request.
            val occurrences = sweptNodes?.get(file) ?: index.occurrenceNodes()
            spans.addAll(occurrenceSpansOf(file, occurrences))
            for (id in occurrences) {
                val span = index.occurrenceSpanOf(id)
                found[id.pos] = SweptSpan(span[0], span[1], SyntaxRoles.referenceUse(id))
            }
            extents[file] = found
        }
        if (spans.isEmpty()) return emptyList()
        val request = TypeCaptureRequest(spans)
        // (INC.2b)/(INC.44) [narrow] says the request's own spans bound what this
        // answer can contain — which is true of the one-file sweep by construction and
        // true of the spelling-narrowed one because an occurrence must spell a name in
        // the closure. The un-narrowed branch below survives for the fallback, where
        // the closure could not be bounded and the request really is every identifier
        // in the program.
        val definitions = (
            // (INC.14) A prepared check carrying every swept span answers this
            // without building — which is what makes document highlights free in a
            // prepared buffer. It is consulted only on the NARROW branch: the
            // whole-program sweep's claim is about files a prepared check's partition
            // need not contain, and [preparedAnswerFor] must not be the only thing
            // standing between that claim and a subset of it.
            if (narrow) preparedAnswerFor(spans) ?: captureIn(request)
            else ProjectCompiler(overlay)
                .build(
                    projectPath, noEmit = true, typeCapture = request,
                    cancellation = cancellation,
                )
            )
            .capturedDefinitions
        val seed = referenceSeed(definitions, queryFile, caret) ?: return emptyList()
        val hits = LinkedHashMap<Pair<String, Int>, ReferenceLocation>()
        for (definition in definitions) {
            if (restrictToQueryFile && definition.fileName != queryFile) continue
            if (!definitionMeets(definition, seed)) continue
            val extent = extents[definition.fileName]?.get(definition.start) ?: continue
            hits[definition.fileName to definition.start] = ReferenceLocation(
                fileName = definition.fileName,
                start = extent.start,
                end = extent.end,
                // A declaration is a span the seed names, or — since (API.9) — one that
                // RESOLVES TO ITSELF, which is what an implementor's own member does.
                // The second half keeps the flag a property of the occurrence rather
                // than of which caret the search happened to start from.
                isDeclaration = seed.any {
                    it.fileName == definition.fileName && it.start == definition.start
                } || definition.locations.any {
                    it.fileName == definition.fileName && it.start == definition.start
                },
                use = extent.use,
            )
        }
        // The declarations themselves. Most are already above — a free name's
        // declaration name resolves to its own symbol — but a MEMBER's declaration
        // name resolves to nothing, and a declaration in a file the sweep never
        // covered (a lib) cannot be there at all.
        for (location in seed) {
            if (restrictToQueryFile && location.fileName != queryFile) continue
            // (API.17) …through the SWEPT extent where there is one, so a declaration
            // named by a LITERAL reports the same span an occurrence of it reports —
            // the text, delimiters excluded. The capture speaks raw `(pos, end)` and
            // has no [SourceIndex] to narrow with; this does.
            val extent = extents[location.fileName]?.get(location.start)
            hits.getOrPut(location.fileName to location.start) {
                ReferenceLocation(
                    fileName = location.fileName,
                    start = extent?.start ?: location.start,
                    end = extent?.end ?: (location.start + location.length),
                    isDeclaration = true,
                    // A declaration in a file the sweep never covered — a `lib.*.d.ts`
                    // — has no node here to classify, and an unplaced occurrence is
                    // reported as unplaced rather than defaulted to a read.
                    use = extent?.use ?: ReferenceUse.UNCLASSIFIED,
                )
            }
        }
        return hits.values.sortedWith(compareBy({ it.fileName }, { it.start }))
    }

    /** One swept occurrence's real extent and role — see [referencesOf]. */
    private class SweptSpan(val start: Int, val end: Int, val use: ReferenceUse)

    /**
     * True when [definition] belongs to the group [seed] names.
     *
     * Identity is intersection of declaration sets (§ 10b), and the set an occurrence
     * offers is ALL THREE of its fields — which is exactly why membership and the seed
     * are computed by different functions. [referenceSeed] takes [CapturedDefinition]'s
     * first two; this takes all three, and [CapturedDefinition.shorthand] is the
     * difference: measured on tsc 7.0.2, a caret on `{ p }` answers the LOCAL's group
     * (two spans) while the MEMBER's group CONTAINS that token. A relation that finds
     * without identifying is what one span carrying two symbols looks like from here.
     *
     * Note that this cannot merge two unrelated groups. A class implementing two
     * interfaces that both declare `p` puts its OWN member in both groups, which is
     * correct — it is both — while every other occurrence still carries only its own
     * interface's declaration and no edge runs between them. The same holds one
     * mechanism over: a shorthand belongs to the local's group and to the member's, and
     * because it is never in a SEED the two groups never meet through it.
     */
    private fun definitionMeets(
        definition: CapturedDefinition,
        seed: Set<CapturedDeclaration>,
    ): Boolean =
        definition.locations.any { it in seed } ||
            definition.related.any { it in seed } ||
            definition.shorthand.any { it in seed }

    /**
     * The node a reference or rename caret names, or null when it names nothing.
     *
     * An identifier, or a member-naming LITERAL: the `"p"` of an `o["p"]` (API.9), of a
     * ``o[`p`]`` (API.16) and, since (API.17), of every other member-NAME position —
     * `{ "p": v }`, `{ ["p"]: v }`, a class's `["p"]`. tsc answers a caret in any of
     * them with the member's whole group, measured, and so does this wherever the
     * literal can be placed; where it cannot, the answer is empty rather than a guess. Everything else (a keyword, punctuation, trivia, any other literal)
     * answers null, and a caret that cannot be answered must not pay for a compile: the
     * rule `completionsAt` and `semanticsAt` already follow.
     */
    private fun occurrenceCaret(index: SourceIndex, offset: Int): Node? {
        val node = index.pathAt(offset).lastOrNull() ?: return null
        if (node is Identifier) return node
        return if (SyntaxRoles.isMemberPosition(node) && SyntaxRoles.isMemberNameLiteral(node)) {
            node
        } else {
            null
        }
    }

    /**
     * The declaration set the caret names — the identity every other occurrence is
     * tested against — or null when the caret names nothing.
     *
     * Two legs, in this order.
     *
     * 1. The caret's OWN captured resolution. This is the ordinary case and covers
     *    every free name (including a free name's own declaration, which resolves to
     *    the symbol it declares) and every member USE.
     *
     * 2. The caret IS a declaration. A member's declaration name is bound by no scope
     *    and has no receiver, so the capture resolves it to nothing; but the sweep
     *    already holds the evidence, because an occurrence that resolved to this exact
     *    span declares the caret to be one of that symbol's declarations.
     *
     * The second leg seeds with the ONE matching declaration rather than with the
     * whole set the occurrence carried, and that is not a shortcut. An occurrence on
     * a UNION receiver names one declaration per constituent, so adopting its whole
     * set would make `p` of `interface A` group with `p` of the unrelated
     * `interface B` merely because some `u.p` may refer to either. The cost is that a
     * caret on ONE declaration of an OVERLOADED member reports the other overload's
     * declaration only when some use points at both — coarser, never wrong.
     */
    private fun referenceSeed(
        definitions: List<CapturedDefinition>,
        queryFile: String,
        caret: Node,
    ): Set<CapturedDeclaration>? {
        for (definition in definitions) {
            if (definition.fileName == queryFile &&
                definition.start == caret.pos &&
                definition.end == caret.end
            ) {
                // (API.9) …plus what a heritage edge TIES it to. A caret on an
                // implementor's own `p` must find the interface's whole group, which is
                // what tsc answers (measured, thirteen spans); seeding with the
                // implementor's own declaration alone would find only the classes below
                // it. `related` is empty for every other occurrence, so this is the
                // identity leg unchanged everywhere else.
                return definition.locations.toSet() + definition.related
            }
        }
        for (definition in definitions) {
            for (location in definition.locations) {
                // By (file, start): two declarations cannot begin at one offset, and
                // the length is computed on two sides of the module boundary.
                if (location.fileName == queryFile && location.start == caret.pos) {
                    return setOf(location)
                }
            }
        }
        return null
    }


    // --- (API.8) rename ------------------------------------------------------------

    /**
     * (API.8) THE EDIT PLAN that renames whatever the caret names to [newName] — or the
     * reason there is none.
     *
     * ```kotlin
     * val plan = project.renameAt("/proj/src/a.ts", 142, "betterName")
     * if (plan.refusal != null) reportToUser(plan.refusal, plan.conflicts)
     * else for (file in plan.files) applyBackToFront(file.fileName, file.edits)
     * ```
     *
     * NOTHING IS APPLIED HERE. A host owns its buffers, so the answer is a value; what
     * is promised is that it is *directly* applicable — one file's edits are
     * non-overlapping and sorted ascending, so `edits.asReversed()` needs no offset
     * arithmetic.
     *
     * ## The occurrence set is [referencesAt]'s, and the EDIT PLAN is the work
     *
     * Identity is the declaration set, never the spelling — so a shadowed binding, an
     * import hop, a merged symbol and a member through its receiver all behave exactly
     * as [referencesAt] documents. What rename adds is that **an occurrence is not
     * always replaced by the new name**: `{ p }`, `const { p } = o` and `export { p }`
     * each spell two things with one identifier, and [RenameEdit] states what each
     * becomes and why. A rename built as "find references, swap the text" compiles and
     * silently renames an object's KEY.
     *
     * ## Then it is CHECKED, by applying it and re-compiling
     *
     * The plan is applied to a scratch copy of the program — the overlay wrapped in a
     * second overlay, so this project's own buffers are untouched — and that program is
     * built again. Two things are then true or the plan is withdrawn:
     *
     * - it introduces **no diagnostic** the original did not have. That is what catches
     *   a COLLISION: renaming to a name already declared in the same scope is TS2451,
     *   and tsc's own language server, measured, does not check this at all and will
     *   happily write two `const useZ` into your file.
     * - **nothing resolves anywhere else.** Every renamed occurrence must still name
     *   the symbol it named, and every identifier that ALREADY spelled [newName] must
     *   still name what it named. That is what catches a CAPTURE — a rename that
     *   compiles and means something different, which no diagnostic count can see:
     *   renaming a file-level `a` to `b` where some function body holds its own `b`
     *   moves that body's reads onto the local, with types that agree and no error.
     *
     * The verification is the reason the safety claims here are claims about a compiler
     * run rather than about a reading of the code, and it is why this costs a second
     * build (see the cost note below).
     *
     * ## What is refused
     *
     * [RenameRefusal] enumerates it with a reason each. The ones worth knowing before
     * you wire this up:
     *
     * - a symbol declared in a **library** — the declaration cannot be edited, so
     *   renaming the uses alone produces a program that does not compile. tsc refuses
     *   the same thing;
     * - an **aliased** import or export (`import { a as b }`) — our identity crosses the
     *   alias hop, so `a` and `b` are ONE symbol here and one new name cannot spell
     *   both. tsc has two symbols and picks by which name the caret is on; with one, a
     *   pick would be a guess;
     * - a **member whose occurrence set cannot be shown to be complete** — an
     *   implementor's member, a second declaration of the same member name, an
     *   `o["p"]`, an object-literal key supplied contextually. Every obstacle is listed
     *   in [RenamePlan.conflicts]. **This is where most member renames land**, and it is
     *   the honest answer: a member rename that misses an implementor produces a class
     *   that no longer implements its interface;
     * - a **new name** that is not an identifier or is a reserved word. tsc checks
     *   neither and will write `const class = 1`.
     *
     * ## Cost
     *
     * A whole-program identifier sweep, exactly [referencesAt]'s, and then a **second
     * build** to verify — so roughly twice [referencesAt], plus [files]' build when the
     * project is dirty. A caret that refuses on syntax alone (not an identifier, a bad
     * new name, the same name) costs NOTHING: no build happens. `docs/language-service.md`
     * carries the measured figures. This is a query a user asks for explicitly; do not
     * wire it to a keystroke.
     */
    public fun renameAt(fileName: String, offset: Int, newName: String): RenamePlan {
        val index = sourceIndexOf(fileName)
            ?: return refusedRename("", newName, RenameRefusal.NOT_AN_IDENTIFIER)
        val caret = occurrenceCaret(index, offset)
            ?: return refusedRename("", newName, RenameRefusal.NOT_AN_IDENTIFIER)
        val oldName = SyntaxRoles.occurrenceText(caret)
        // Reserved BEFORE well-formed: `class` scans as a keyword, so the general test
        // would report it as "not an identifier" and hide the real reason.
        if (newName in SyntaxRoles.RESERVED_WORDS) {
            return refusedRename(oldName, newName, RenameRefusal.NEW_NAME_IS_RESERVED)
        }
        if (!SyntaxRoles.isIdentifierName(newName)) {
            return refusedRename(oldName, newName, RenameRefusal.NEW_NAME_IS_NOT_AN_IDENTIFIER)
        }
        if (newName == oldName) {
            return refusedRename(oldName, newName, RenameRefusal.NEW_NAME_UNCHANGED)
        }
        val sweep = renameSweep(caret, newName)
        val seed = referenceSeed(sweep.definitions, keyOf(fileName), caret)
            ?: return refusedRename(oldName, newName, RenameRefusal.NO_SYMBOL)
        return planRename(oldName, newName, sweep, seed)
    }

    /** A refusal decided without a build — [RenamePlan]'s contract with no plan. */
    private fun refusedRename(
        oldName: String,
        newName: String,
        refusal: RenameRefusal,
    ): RenamePlan = RenamePlan(oldName, newName, emptyList(), refusal, emptyList())

    /**
     * The whole-program identifier sweep a rename runs on, in ONE build.
     *
     * Deliberately not expressed on [referencesOf], which performs the same build:
     * that member answers with finished [ReferenceLocation]s, and a rename needs the
     * NODES (to decide each occurrence's replacement), the per-file parses (to shift
     * offsets and re-parse the result) and the whole definition table (to tell an
     * identifier that resolved ELSEWHERE from one that resolved nowhere). Rebuilding
     * those from a list of spans would be re-deriving what this already has — and the
     * duplication buys the same independent oracle `semanticsOf` documents: the
     * reference tests and the rename tests can disagree.
     */
    private fun renameSweep(caret: Node, newName: String): RenameSweep {
        val files = build().programFiles.map { keyOf(it) }
        // (INC.45) The same spelling closure [referencesAt] uses, widened by the NEW
        // name — see [narrowedRenameSweep] for why the widening is not optional.
        val narrowed =
            if (narrowReferenceSweeps) narrowedRenameSweep(caret, newName, files) else null
        val spans = ArrayList<TypeCaptureSpan>()
        val indexes = LinkedHashMap<String, SourceIndex>(files.size)
        val identifiers = LinkedHashMap<String, List<Node>>(files.size)
        for (file in narrowed?.keys ?: files) {
            val index = sourceIndexOf(file) ?: continue
            // (API.9) [SourceIndex.occurrenceNodes], not `identifiers()`: an `o["p"]`
            // is an occurrence a plan must EDIT, and one it cannot see is exactly what
            // used to refuse the whole rename.
            val found = narrowed?.get(file) ?: index.occurrenceNodes()
            indexes[file] = index
            identifiers[file] = found
            for (id in found) spans.add(TypeCaptureSpan(file, id.pos, id.end))
        }
        // (INC.45) The partition, carried on the SWEEP so [verifyRename]'s second build
        // takes the same one: its diagnostic comparison is a MULTISET over both, and a
        // narrowed "before" against a whole-program "after" would report every unswept
        // file's rows as removed.
        val partition = if (narrowed == null) null else indexes.keys.toSet()
        val result = ProjectCompiler(overlay).build(
            projectPath,
            cancellation = cancellation,
            noEmit = true,
            recheckOnly = partition,
            typeCapture = TypeCaptureRequest(spans),
        )
        return RenameSweep(
            indexes, identifiers, result.capturedDefinitions, result.diagnostics, partition,
        )
    }

    /**
     * (INC.45) [narrowedSweep] widened by the NEW name — the population a RENAME needs,
     * or null when the old name's closure could not be bounded.
     *
     * ## Why the new name has to be in it, and why it is not in the CLOSURE
     *
     * [verifyRename]'s third check scans for occurrences that ALREADY spell the new
     * name and asserts each still resolves where it did — the only one of the three
     * that can see a rename which compiles and means something else. Selected on the
     * old name's closure alone, that scan finds nothing and the check passes
     * vacuously, which would make this narrowing a way of switching the safety net off
     * rather than of paying less for it.
     *
     * It is added to the SELECTION and not to the closure because it is not a spelling
     * of the symbol being renamed: letting it contribute alias links or escapes would
     * make the closure — and so the partition — a function of a name that names
     * something else entirely.
     *
     * ## Why this is enough to keep the partition sound for the DIAGNOSTIC comparison
     *
     * A rename edits only files the plan names, all of which are here. An unedited
     * file's meaning can change only through a name it imports, which it must then
     * SPELL — as the old name (in the closure) or as the new one (added here) — so it
     * is in the partition too. That argument is the load-bearing one for narrowing
     * `renameAt` at all, and it is stated here rather than left implicit.
     */
    /**
     * (INC.45) THE CONTROL for the rename narrowing: how many files a rename at this
     * caret would check, or **-1** when it falls back.
     *
     * [narrowedSweepFiles]' twin, and it exists for one thing that member cannot show:
     * the rename population is the reference closure WIDENED by the new name, so a
     * file that spells only the new name belongs to this partition and to no reference
     * one. A count is the only observable that says the widening happened — the plans
     * agree with or without it on any fixture whose new name is genuinely fresh, which
     * is what would make a pin written on the plan alone blind.
     */
    internal fun narrowedRenameFiles(fileName: String, offset: Int, newName: String): Int {
        val index = sourceIndexOf(fileName) ?: return -1
        val caret = occurrenceCaret(index, offset) ?: return -1
        val files = build().programFiles.map { keyOf(it) }
        return narrowedRenameSweep(caret, newName, files)?.size ?: -1
    }

    private fun narrowedRenameSweep(
        caret: Node,
        newName: String,
        programFiles: List<String>,
    ): Map<String, List<Node>>? {
        val closure = narrowedSweep(caret, programFiles) ?: return null
        val selected = LinkedHashMap<String, MutableList<Node>>(closure.size)
        for ((file, nodes) in closure) selected[file] = ArrayList(nodes)
        for (file in programFiles) {
            val text = overlay.readText(file) ?: continue
            if (!mayHideAnEscapedName(text) && !text.contains(newName)) continue
            val index = sourceIndexOf(file) ?: continue
            val already = index.occurrenceNodes()
                .filter { SyntaxRoles.occurrenceText(it) == newName }
            if (already.isEmpty()) continue
            val into = selected.getOrPut(file) { ArrayList() }
            // The new name may ALSO be a spelling the closure carries — renaming `p` to
            // `q` where some file writes `import { p as q }` — so this is a union and
            // not an append.
            for (node in already) if (into.none { it === node }) into.add(node)
        }
        // [SourceIndex.occurrenceNodes]' own (sorted, total) order, which every
        // consumer of a span list in this class assumes.
        return selected.mapValues { (_, nodes) ->
            nodes.sortedWith(compareBy({ it.pos }, { it.end }))
        }
    }

    /** [renameSweep]'s answer — see it for why a rename needs all five. */
    private class RenameSweep(
        val indexes: Map<String, SourceIndex>,
        val identifiers: Map<String, List<Node>>,
        val definitions: List<CapturedDefinition>,
        val diagnostics: List<Diagnostic>,
        /** (INC.45) The check partition both of a rename's builds must share, or null. */
        val partition: Set<String>?,
    )

    /**
     * The plan proper: group the occurrences, refuse what cannot be done correctly,
     * write the edits, then verify them by applying and re-compiling.
     */
    private fun planRename(
        oldName: String,
        newName: String,
        sweep: RenameSweep,
        seed: Set<CapturedDeclaration>,
    ): RenamePlan {
        fun refuse(refusal: RenameRefusal, conflicts: List<RenameConflict> = emptyList()) =
            RenamePlan(oldName, newName, emptyList(), refusal, conflicts)

        // THE SAFETY REFUSAL. A declaration outside the swept program is a `lib.*.d.ts`,
        // which has no path on disk and which this rename may not edit — so renaming
        // the uses alone would leave the program not compiling.
        if (seed.any { it.fileName !in sweep.indexes }) {
            return refuse(RenameRefusal.DECLARED_IN_A_LIBRARY)
        }
        // Every occurrence of the symbol, as (file, start) — the grouping `referencesOf`
        // performs, kept here as raw keys because an EDIT needs the node.
        val group = LinkedHashSet<Pair<String, Int>>()
        for (definition in sweep.definitions) {
            if (!definitionMeets(definition, seed)) continue
            group.add(definition.fileName to definition.start)
        }
        for (location in seed) group.add(location.fileName to location.start)

        val nodes = HashMap<Pair<String, Int>, Node>()
        for ((file, found) in sweep.identifiers) for (id in found) nodes[file to id.pos] = id
        // (API.10) …and WHICH WAY each occurrence was reached. A SHORTHAND is in the
        // group through `locations` when the LOCAL is being renamed and through
        // `shorthand` when the MEMBER is, and the two expand the one token in opposite
        // directions. Every other occurrence answers false and is a plain replacement.
        val reachedAsMember = HashMap<Pair<String, Int>, Boolean>()
        for (definition in sweep.definitions) {
            if (definition.shorthand.isEmpty()) continue
            if (definition.locations.any { it in seed }) continue
            if (!definition.shorthand.any { it in seed }) continue
            reachedAsMember[definition.fileName to definition.start] = true
        }
        val occurrences = ArrayList<RenameOccurrence>(group.size)
        for (key in group) {
            val node = nodes[key] ?: return refuse(
                RenameRefusal.OCCURRENCES_INCOMPLETE,
                listOf(
                    RenameConflict(
                        RenameConflictKind.UNRESOLVED_OCCURRENCE, key.first, key.second,
                        key.second, "no identifier node at this occurrence",
                    ),
                ),
            )
            occurrences.add(RenameOccurrence(key.first, node, reachedAsMember[key] == true))
        }
        // ONE new name cannot spell two things. An `import { a as b }` makes the alias
        // and the original one symbol here, so the group carries both spellings.
        if (occurrences.any { SyntaxRoles.occurrenceText(it.node) != oldName }) {
            return refuse(RenameRefusal.ALIASED_SYMBOL)
        }
        // A declaration that IS the import binding means the module never resolved, so
        // renaming the local would leave the export it names untouched.
        if (seed.any { isImportBindingName(nodes[it.fileName to it.start]) }) {
            return refuse(RenameRefusal.UNRESOLVED_IMPORT)
        }

        val symbolIsMember = seed.any {
            val node = nodes[it.fileName to it.start]
            node != null && SyntaxRoles.isMemberPosition(node)
        }
        val conflicts = completenessConflicts(
            oldName, sweep, group, symbolIsMember,
            reachedThroughQualifier = symbolIsMember ||
                occurrences.any { SyntaxRoles.isMemberPosition(it.node) },
        )
        if (conflicts.isNotEmpty()) {
            return refuse(RenameRefusal.OCCURRENCES_INCOMPLETE, conflicts)
        }

        val planned = HashMap<String, MutableList<PlannedEdit>>()
        for (occurrence in occurrences) {
            val index = sweep.indexes[occurrence.fileName] ?: return refuse(
                RenameRefusal.OCCURRENCES_INCOMPLETE,
            )
            val rewrite =
                SyntaxRoles.renameRewrite(occurrence.node, oldName, newName, occurrence.asMember)
            // (API.9) [SourceIndex.occurrenceSpanOf], not `pos`/`realEndOf`: an
            // `o["p"]`'s edit replaces the text BETWEEN the quotes, and writing over
            // the quotes too would produce `o[newName]`.
            val span = index.occurrenceSpanOf(occurrence.node)
            planned.getOrPut(occurrence.fileName) { ArrayList() }.add(
                PlannedEdit(
                    start = span[0],
                    end = span[1],
                    newText = rewrite.text,
                    nameOffset = rewrite.nameOffset,
                    nodePos = occurrence.node.pos,
                    asMember = occurrence.asMember,
                ),
            )
        }
        for (edits in planned.values) edits.sortBy { it.start }
        return verifyRename(oldName, newName, sweep, seed, group, planned)
    }

    /**
     * One place the plan will edit: the file it is in, the identifier node there, and
     * (API.10) whether the group reached it as a MEMBER — which for a SHORTHAND decides
     * which of the token's two meanings moves.
     */
    private class RenameOccurrence(
        val fileName: String,
        val node: Node,
        val asMember: Boolean,
    )

    /**
     * An edit before it is published, carrying where the NEW NAME lands inside its own
     * replacement — which a shorthand expansion (`p` -> `p: newName`) moves, and which
     * the verification pass needs in order to ask about the right span.
     *
     * (API.9) [nodePos] is the occurrence NODE's raw `pos`, which is the key every
     * capture answer is filed under and is NOT [start] for an `o["p"]` — there the edit
     * begins one character in, past the opening quote. Carried rather than re-derived
     * because the verification looks up what this occurrence resolved to BEFORE, and a
     * lookup by the edit's start silently misses and reads as "it now means something
     * else".
     */
    private class PlannedEdit(
        val start: Int,
        val end: Int,
        val newText: String,
        val nameOffset: Int,
        val nodePos: Int,
        val asMember: Boolean,
    )

    /**
     * True when [node] is the name an import CLAUSE binds — the shape a seed
     * declaration takes when the module could not be resolved.
     */
    private fun isImportBindingName(node: Node?): Boolean {
        if (node == null) return false
        val parent = (node as NodeBase).parent ?: return false
        return when (parent) {
            is ImportSpecifier -> parent.name === node
            is ImportClause -> parent.name === node
            is NamespaceImport -> parent.name === node
            else -> false
        }
    }

    /**
     * THE COMPLETENESS NET: every place that could be an occurrence of this symbol and
     * could not be shown to be one.
     *
     * A spelling scan used as a SAFETY NET and never as the answer — the occurrence set
     * stays resolution-based, and this only decides whether to trust it. An identifier
     * spelling the old name is fine when it is in the group (it IS an occurrence) or
     * when it RESOLVED to something else (the compiler proved it is a different
     * symbol). What is left is *unresolved*, and unresolved is not unrelated.
     *
     * The position split is what keeps this from refusing every ordinary rename. A
     * member declaration name resolves to nothing (it is bound by no scope and has no
     * receiver — `Project.definitionsAt` says so), so without the split an
     * `interface I { p: string }` anywhere in the program would block renaming a local
     * `p`. So:
     *
     * - renaming something reached through a qualifier — a member, an enum's member, a
     *   namespace's export — the MEMBER positions are the risk;
     * - renaming a plain binding, the FREE positions are.
     *
     * Two obstacles have no resolution to consult at all and are checked only for a
     * member: a member named by a LITERAL the search could not place — an `o["p"]` on
     * an `any`, a computed member DECLARATION, a string-named method — and a
     * SHORTHAND, whose property comes from a contextual type or from the binding
     * pattern's own token.
     */
    private fun completenessConflicts(
        oldName: String,
        sweep: RenameSweep,
        group: Set<Pair<String, Int>>,
        symbolIsMember: Boolean,
        reachedThroughQualifier: Boolean,
    ): List<RenameConflict> {
        val resolved = HashSet<Pair<String, Int>>(sweep.definitions.size)
        for (definition in sweep.definitions) {
            if (definition.locations.isNotEmpty()) resolved.add(definition.fileName to definition.start)
        }
        val conflicts = ArrayList<RenameConflict>()
        for ((file, found) in sweep.identifiers) {
            val index = sweep.indexes[file] ?: continue
            for (id in found) {
                if (SyntaxRoles.occurrenceText(id) != oldName) continue
                val key = file to id.pos
                val span = index.occurrenceSpanOf(id)
                val memberPosition = SyntaxRoles.isMemberPosition(id)
                if (key !in group && key !in resolved &&
                    memberPosition == reachedThroughQualifier
                ) {
                    // (API.9) An element access is no longer a separate obstacle: it is
                    // swept, so one the search RESOLVED is in the group and one it did
                    // not is unresolved like any other member position. The kind is kept
                    // because the two failures are different things to a user — a
                    // literal that could not be placed names a member of an `any`, which
                    // is not the same report as an identifier that could not be.
                    // (API.17) …and the literal is no longer only an element access's:
                    // a computed key, a string key and a class's computed member name
                    // all arrive here, which is what turns the last silent miss in this
                    // API into a stated one.
                    val literalName = SyntaxRoles.isMemberNameLiteral(id)
                    val kind =
                        if (literalName) RenameConflictKind.ELEMENT_ACCESS
                        else RenameConflictKind.UNRESOLVED_OCCURRENCE
                    val detail =
                        if (literalName) {
                            "a literal naming the member '$oldName' the search could not place"
                        } else {
                            "an identifier spelled '$oldName' that the search could not resolve"
                        }
                    conflicts.add(RenameConflict(kind, file, span[0], span[1], detail))
                }
                if (symbolIsMember && key !in group && SyntaxRoles.isPropertyHidingShorthand(id)) {
                    conflicts.add(
                        RenameConflict(
                            RenameConflictKind.CONTEXTUAL_SHORTHAND, file, span[0], span[1],
                            "a shorthand spelled '$oldName' whose property this API cannot resolve",
                        ),
                    )
                }
            }
        }
        return conflicts.sortedWith(compareBy({ it.fileName }, { it.start }))
    }

    /**
     * APPLY THE PLAN TO A SCRATCH COPY OF THE PROGRAM AND COMPILE IT AGAIN — the step
     * that turns this feature's safety from an argument into a measurement.
     *
     * The scratch copy is [OverlayVfs] wrapped around this project's own overlay, so
     * nothing here is observable through [updateFile], [diagnostics] or the parse
     * caches — the renamed texts exist for exactly one build.
     *
     * Three things are checked, and each catches a failure the others cannot:
     *
     * 1. **the plan re-reads.** Each edited file is re-parsed and the new name must be
     *    the identifier at every position the plan says it put one. An expansion that
     *    got its own arithmetic wrong fails here rather than in the user's buffer.
     * 2. **no new diagnostic.** A COLLISION — the new name already declared in a scope
     *    the rename reaches — is a redeclaration error, and this is what sees it.
     * 3. **nothing moved.** Every renamed occurrence, and every identifier that ALREADY
     *    spelled the new name, must resolve after the rename to exactly what it resolved
     *    to before — its OWN old answer, mapped through the edits, never "the symbol's
     *    declarations". That distinction is load-bearing: a member's declaration NAME
     *    resolves to nothing here, so the stronger-looking expectation would report this
     *    API's own blind spot as a change of meaning. This is the CAPTURE check, and it
     *    is the only one of the three that can see a rename which compiles and means
     *    something else.
     *
     * Declarations are compared by `(fileName, start)` alone. Two declarations cannot
     * begin at one offset — the same fact [referenceSeed] leans on — and a LENGTH would
     * additionally have to model the edits inside a coarse whole-declaration span.
     */
    private fun verifyRename(
        oldName: String,
        newName: String,
        sweep: RenameSweep,
        seed: Set<CapturedDeclaration>,
        group: Set<Pair<String, Int>>,
        planned: Map<String, MutableList<PlannedEdit>>,
    ): RenamePlan {
        val conflicts = ArrayList<RenameConflict>()
        fun refuse(refusal: RenameRefusal) =
            RenamePlan(oldName, newName, emptyList(), refusal, conflicts.sortedWith(compareBy({ it.fileName }, { it.start })))

        // Where an offset in the OLD text lands in the new one: every edit that ends at
        // or before it has already changed the length in front of it.
        fun shift(file: String, offset: Int): Int {
            var delta = 0
            for (edit in planned[file].orEmpty()) {
                if (edit.end <= offset) delta += edit.newText.length - (edit.end - edit.start)
            }
            return offset + delta
        }
        // A declaration's place after the rename. A renamed one moves to wherever its
        // own replacement put the new name; anything else only shifts.
        fun placeOf(fileName: String, start: Int): Pair<String, Int> {
            val edit = planned[fileName].orEmpty().firstOrNull { it.start == start }
            return fileName to (shift(fileName, start) + (edit?.nameOffset ?: 0))
        }

        val scratch = OverlayVfs(overlay)
        val newIndexes = HashMap<String, SourceIndex>(planned.size)
        val options = parseOptions ?: TsConfigLoader(overlay).load(configPath).options
            .also { parseOptions = it }
        for ((file, edits) in planned) {
            var text = overlay.readText(file) ?: return refuse(RenameRefusal.OCCURRENCES_INCOMPLETE)
            for (edit in edits.asReversed()) {
                text = text.substring(0, edit.start) + edit.newText + text.substring(edit.end)
            }
            scratch.put(file, text)
            newIndexes[file] = SourceIndex.of(text, file, computeParserFlags(file, text, options))
        }

        // (1) the plan re-reads, and (3)'s subject set: the renamed occurrences in the
        // new text, plus every identifier that already spelled the new name.
        // What each span resolved to BEFORE, by (file, start). The expectation for every
        // span asked about below is its own old answer, mapped — never the seed. A
        // MEMBER's declaration name resolves to nothing (it is bound by no scope and has
        // no receiver), so demanding that it name the seed would refuse every member
        // rename with a "meaning changed" that is this API's own blind spot rather than
        // a fact about the program.
        val resolvedBefore = HashMap<Pair<String, Int>, Set<Pair<String, Int>>>()
        // (API.10) …and, for a SHORTHAND expanded because the MEMBER moved, the OTHER
        // of the token's two answers. `{ p }` resolves to the local before the rename
        // and the `renamed` of `{ renamed: p }` resolves to the MEMBER after it, so
        // asking for the local's declaration there reports a correct expansion as a
        // change of meaning — the same silent, conservative failure round 926 found
        // when an edit span and an identity key stopped coinciding.
        val resolvedBeforeAsMember = HashMap<Pair<String, Int>, Set<Pair<String, Int>>>()
        for (definition in sweep.definitions) {
            resolvedBefore[definition.fileName to definition.start] =
                definition.locations.map { placeOf(it.fileName, it.start) }.toSet()
            if (definition.shorthand.isNotEmpty()) {
                resolvedBeforeAsMember[definition.fileName to definition.start] =
                    definition.shorthand.map { placeOf(it.fileName, it.start) }.toSet()
            }
        }
        val asked = ArrayList<TypeCaptureSpan>()
        val expectedPlaces = LinkedHashMap<Pair<String, Int>, Set<Pair<String, Int>>>()
        for ((file, edits) in planned) {
            val index = newIndexes[file] ?: continue
            for (edit in edits) {
                val at = shift(file, edit.start) + edit.nameOffset
                val node = index.pathAt(at).lastOrNull()
                // (API.9) …and the span the edit produced must START where the plan said
                // it would. For an `o["p"]` that is the literal's TEXT, so the node's own
                // `pos` is one character earlier — which is precisely the arithmetic this
                // check exists to catch, hence the span rather than the node's `pos`.
                val produced = node != null && index.occurrenceSpanOf(node)[0] == at &&
                    SyntaxRoles.occurrenceText(node) == newName
                if (!produced) {
                    conflicts.add(
                        RenameConflict(
                            RenameConflictKind.RESOLUTION_CHANGED, file, at, at + newName.length,
                            "the applied edit did not produce '$newName' here",
                        ),
                    )
                    return refuse(RenameRefusal.WOULD_CHANGE_MEANING)
                }
                asked.add(TypeCaptureSpan(file, node.pos, node.end))
                val before =
                    if (edit.asMember) resolvedBeforeAsMember[file to edit.nodePos]
                    else resolvedBefore[file to edit.nodePos]
                expectedPlaces[file to node.pos] = before ?: emptySet()
            }
        }
        for ((file, found) in sweep.identifiers) {
            for (id in found) {
                if (SyntaxRoles.occurrenceText(id) != newName) continue
                val expected = resolvedBefore[file to id.pos] ?: continue
                val index = newIndexes[file]
                val node = if (index == null) id else {
                    index.pathAt(shift(file, id.pos)).lastOrNull()
                        ?.takeIf { SyntaxRoles.occurrenceText(it) == newName } ?: continue
                }
                asked.add(TypeCaptureSpan(file, node.pos, node.end))
                expectedPlaces[file to node.pos] = expected
            }
        }

        val after = ProjectCompiler(scratch).build(
            projectPath,
            cancellation = cancellation,
            noEmit = true,
            // (INC.45) The SWEEP's partition, not one derived from `asked`: check (2)
            // below compares diagnostics as a multiset against the before-build's, so
            // the two must have walked the same files or every unswept row reads as
            // removed. Null on the fallback path, which is a whole-program build.
            recheckOnly = sweep.partition,
            typeCapture = TypeCaptureRequest(asked),
        )

        // (2) no new diagnostic. Compared by (file, code) as a MULTISET: a rename
        // rewrites the names inside messages, so the message text moves for reasons
        // that are not regressions, while a count per code does not.
        val bag = HashMap<Pair<String, Int>, Int>()
        for (diagnostic in sweep.diagnostics) {
            val key = (diagnostic.fileName ?: "") to diagnostic.code
            bag[key] = (bag[key] ?: 0) + 1
        }
        for (diagnostic in after.diagnostics) {
            val file = diagnostic.fileName ?: ""
            val key = file to diagnostic.code
            val left = bag[key] ?: 0
            if (left > 0) {
                bag[key] = left - 1
            } else {
                val start = diagnostic.start ?: 0
                conflicts.add(
                    RenameConflict(
                        RenameConflictKind.NEW_DIAGNOSTIC, file,
                        start, start + (diagnostic.length ?: 0),
                        "TS${diagnostic.code}: ${diagnostic.message}",
                    ),
                )
            }
        }
        if (conflicts.isNotEmpty()) return refuse(RenameRefusal.WOULD_NOT_COMPILE)

        // (3) nothing moved.
        val resolvedAfter = HashMap<Pair<String, Int>, Set<Pair<String, Int>>>()
        for (definition in after.capturedDefinitions) {
            resolvedAfter[definition.fileName to definition.start] =
                definition.locations.map { it.fileName to it.start }.toSet()
        }
        for ((where, expected) in expectedPlaces) {
            val got = resolvedAfter[where] ?: emptySet()
            if (got != expected) {
                conflicts.add(
                    RenameConflict(
                        RenameConflictKind.RESOLUTION_CHANGED, where.first, where.second,
                        where.second + newName.length,
                        "after the rename this names a different declaration set",
                    ),
                )
            }
        }
        if (conflicts.isNotEmpty()) return refuse(RenameRefusal.WOULD_CHANGE_MEANING)

        val files = planned.entries
            .map { (file, edits) ->
                FileRename(file, edits.map { RenameEdit(it.start, it.end, it.newText) })
            }
            .sortedBy { it.fileName }
        return RenamePlan(oldName, newName, files, null, emptyList())
    }

    /**
     * The one build both semantic sweeps perform, and the assembly of its answers.
     *
     * [nodes] is deduplicated HERE, by raw span and first-sighting, so neither
     * caller has to: two carets inside one identifier are one question, and the raw
     * `(pos, end)` pair is the identity the capture speaks (`TypeCaptureSpan` —
     * `Node.end` is the end of the FOLLOWING token, so it is an identity and never
     * an extent).
     *
     * Note what is NOT done here: the single-caret [quickInfoAt] and [definitionsAt]
     * are deliberately not re-expressed on top of this. The ~10 lines they duplicate
     * buy an INDEPENDENT oracle — `ProjectSemanticsTest` asserts the batch agrees
     * with them span for span, and that assertion would be a tautology if both sides
     * ran this function. Drift between the two paths is exactly what it fails on.
     */
    private fun semanticsOf(
        fileName: String,
        index: SourceIndex,
        nodes: Collection<Node>,
    ): List<SemanticInfo> {
        val distinct = LinkedHashMap<Long, Node>(nodes.size)
        for (node in nodes) distinct.getOrPut(spanKeyOf(node)) { node }
        if (distinct.isEmpty()) return emptyList()
        val key = keyOf(fileName)
        val result = captureAround(key, index, distinct.values)
        val types = HashMap<Long, String>(result.capturedTypes.size)
        for (captured in result.capturedTypes) {
            if (captured.fileName == key) types[packSpan(captured.start, captured.end)] = captured.typeText
        }
        val definitions = HashMap<Long, List<DefinitionLocation>>(result.capturedDefinitions.size)
        for (captured in result.capturedDefinitions) {
            if (captured.fileName != key) continue
            definitions[packSpan(captured.start, captured.end)] =
                (captured.locations + captured.shorthand).map {
                    DefinitionLocation(it.fileName, it.start, it.length, it.kind)
                }
        }
        return distinct.values.map { node ->
            val spanKey = spanKeyOf(node)
            val end = index.realEndOf(node)
            SemanticInfo(
                start = node.pos,
                end = end,
                kind = node.kind.name,
                quickInfo = types[spanKey]?.let { typeText ->
                    QuickInfo(node.kind.name, typeText, node.pos, end)
                },
                definitions = definitions[spanKey] ?: emptyList(),
            )
        }.sortedWith(compareBy({ it.start }, { it.end }))
    }

    /** [node]'s RAW `(pos, end)` identity as one key — see [semanticsOf]. */
    private fun spanKeyOf(node: Node): Long = packSpan(node.pos, node.end)

    /**
     * `(start, end)` as one `Long`, for matching a captured answer back to the node
     * it was asked about.
     *
     * FINALIZED by an odd multiply, for round 889's reason and not as a ritual: a
     * plain `(start shl 32) or end` hashes to `start xor end` under
     * `Long.hashCode`, and a node's `end` is its `start` plus a token or two, so a
     * whole file's spans would pile onto a few dozen buckets of the maps below. The
     * compiler's own copy of this key is finalized with the same constant
     * (`packIdPair`), which is `internal` to that module and therefore restated
     * rather than shared. Sound for the same two reasons: nothing unpacks the key
     * and nothing iterates the maps.
     */
    private fun packSpan(start: Int, end: Int): Long =
        ((start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)) * -0x61c8864680b583ebL

    /**
     * Drops whatever [key]'s new content invalidates.
     *
     * Per-path for the text-derived indexes: an edit to one buffer cannot change
     * another file's text, and an editor edits one file per keystroke while holding
     * indexes for every file it has open.
     *
     * A JSON edit additionally drops EVERY parse and the options themselves, because
     * a parse is option-dependent and a config resolves an `extends` chain through
     * files this class never learns the names of — watching only [configPath] would
     * serve stale flags for an edit one level up, silently. The bluntness costs
     * nothing: a `.json` file is not what a host edits per keystroke.
     */
    private fun invalidate(key: String) {
        lineMaps.remove(key)
        sourceIndexes.remove(key)
        ownParses.remove(key)
        if (key.endsWith(".json")) {
            parseOptions = null
            sourceIndexes.clear()
            ownParses.clear()
        }
    }

    /**
     * Overlays [text] as the content of [path], as an unsaved editor buffer.
     *
     * Nothing is written to disk — this is the whole point of the class — and the
     * path need not exist there: overlaying a new file makes the next build discover
     * it through the glob and resolve imports to it, including in a directory that
     * exists nowhere but in the overlay (see [OverlayVfs] for why those are three
     * separate mechanisms).
     *
     * Marks the project dirty unconditionally, even when [text] equals what the
     * previous build saw. Skipping the invalidation would be a cheap optimization
     * and a bad contract: it would make "did my edit take effect" depend on a string
     * comparison the caller cannot see.
     */
    public fun updateFile(path: String, text: String) {
        checkOpen()
        val key = keyOf(path)
        overlay.put(key, text)
        cached = null
        narrowed.clear()
        captures.clear()
        prepared = null
        // (INC.40) The live program is a claim about the text this project was built
        // from; `ProgramRecheck` has no invalidation protocol and deliberately wants
        // none, so the handle goes wherever [cached] goes.
        recheck = null
        // (INC.46) The export SURFACE deliberately does NOT go with them: it is a claim
        // about the files this edit did not touch, which is exactly what survives. The
        // edited file joins the set the next [diagnostics] must clear.
        (dirtyFiles ?: LinkedHashSet<String>().also { dirtyFiles = it }).add(key)
        invalidate(key)
    }

    /**
     * Overlays [path] as ABSENT, as a file closed-and-deleted in an editor.
     *
     * Again nothing touches disk: the file stays where it is and the next build
     * behaves as though it were gone — an import of it becomes unresolved, and it
     * drops out of the program. Undo by [updateFile]-ing its content back.
     */
    public fun deleteFile(path: String) {
        checkOpen()
        val key = keyOf(path)
        overlay.remove(key)
        cached = null
        narrowed.clear()
        captures.clear()
        prepared = null
        // (INC.40) The live program is a claim about the text this project was built
        // from; `ProgramRecheck` has no invalidation protocol and deliberately wants
        // none, so the handle goes wherever [cached] goes.
        recheck = null
        // (INC.46) The export SURFACE deliberately does NOT go with them: it is a claim
        // about the files this edit did not touch, which is exactly what survives. The
        // edited file joins the set the next [diagnostics] must clear.
        (dirtyFiles ?: LinkedHashSet<String>().also { dirtyFiles = it }).add(key)
        invalidate(key)
    }

    /**
     * (INC.48) This project's incremental state, as text a host can store and hand to
     * [restoreState] in a LATER PROCESS — or null when there is nothing to save.
     *
     * ## What it buys
     *
     * (INC.46) made project-wide diagnostics incremental within a process; all of that
     * state is in memory, so an IDE restart, a plugin reload or a daemon recycle throws
     * it away and the first query pays a whole-program build again. With a snapshot the
     * next process instead pays the (INC.46) gate: one build narrowed to whatever
     * changed while it was gone — and, when nothing changed, a build narrowed to
     * NOTHING, which is the floor.
     *
     * ## What it does not do
     *
     * It writes no file. The host decides where its caches live, and an embedding API
     * that dropped a file into somebody's source tree unasked would be making that
     * decision for it; the CLI's `--incremental` (`tsconfig.xtsbuildinfo`, INV.7(d3))
     * remains the convention for callers who want the other behaviour.
     *
     * ## When it answers null
     *
     * When no whole-program build has established a surface yet — call [diagnostics]
     * first — and when this compiler build may not be reused across processes at all
     * (`ProjectStateSnapshot.isReusableBuildId`: a `.dirty` or `unknown` build id names
     * a tree with local changes, and two such trees share the id without sharing the
     * behaviour). Refusing to WRITE such a snapshot is deliberate belt-and-braces: the
     * restore refuses it too, and a snapshot that can never be adopted is a file a host
     * would otherwise keep writing and never use.
     */
    public fun saveState(): String? {
        checkOpen()
        val base = surface ?: return null
        if (!ProjectStateSnapshot.isReusableBuildId(ProjectStateSnapshot.compilerBuildId)) {
            return null
        }
        val hashes = LinkedHashMap<String, String>()
        // The program's own sources AND the `.json` inputs that decided what the program
        // IS — see `ProjectStateSnapshot`'s validation contract for why the second half
        // is not optional. Read through the overlay, so an unsaved buffer is hashed as
        // what this project actually checked; a later process reading the on-disk text
        // sees a different hash and re-checks that file, which is the right answer.
        for (path in base.programFiles + overlay.jsonReads.filter { it !in base.programFiles }) {
            val text = overlay.readText(path) ?: continue
            hashes[path] = ProjectStateSnapshot.contentHash(text)
        }
        return ProjectStateSnapshot.of(
            configPath = configPath,
            fileHashes = hashes,
            programFiles = base.programFiles,
            exportSignatures = base.signatures,
            exportEscapes = base.escapes,
            diagnostics = base.diagnostics,
        ).encode()
    }

    /**
     * (INC.48) Adopts the state [text] encodes, so the first query narrows instead of
     * rebuilding. True when it was adopted, false when it was refused — and a refusal
     * is never an error, because the caller's fallback is the build it would have done
     * anyway.
     *
     * ## Every reason it refuses, and why each one has to be checked
     *
     * Each of these produces a STALE ANSWER if skipped, which is the one failure this
     * arc exists to prevent:
     *
     *  - **This project has already built or already restored.** A snapshot is a claim
     *    about a project's starting point; adopting one over live state would replace
     *    answers this process computed with answers it did not.
     *  - **Unreadable, or another format version.** Nothing to adopt.
     *  - **A different compiler build**, or one whose id may not be reused at all.
     *  - **A different `tsconfig.json`.** The state describes one project.
     *  - **Any recorded `.json` input changed or vanished.** A changed config does not
     *    date one file's rows — it changes what the program is and which options apply,
     *    so every stored row is suspect and narrowing cannot repair it.
     *  - **A program file vanished.** Its removal changes what every importer resolves.
     *
     * A program file whose CONTENT changed is not a refusal: it is exactly what the
     * (INC.46) gate is for, and it becomes this project's dirty set. Nor is an ADDED
     * file — one cannot be seen in a content hash — which is why an adopted state stays
     * unverified until a build has re-crawled the project and found the same program;
     * until then even a clean project runs the gate with an empty partition rather than
     * answering from the snapshot directly.
     */
    public fun restoreState(text: String): Boolean {
        checkOpen()
        if (cached != null || surface != null) return false
        val snapshot = ProjectStateSnapshot.decode(text) ?: return false
        if (!ProjectStateSnapshot.isReusableBuildId(snapshot.buildId)) return false
        if (snapshot.buildId != ProjectStateSnapshot.compilerBuildId) return false
        if (snapshot.configPath != configPath) return false
        if (snapshot.programFiles.isEmpty()) return false
        val programFiles = snapshot.programFiles.toHashSet()
        val dirty = LinkedHashSet<String>()
        for ((path, hash) in snapshot.fileHashes) {
            val now = overlay.readText(path)
            val isProgramFile = path in programFiles
            if (now == null) {
                // A vanished program file changes what every importer resolves; a
                // vanished config input changes what the program IS. Neither narrows.
                return false
            }
            if (ProjectStateSnapshot.contentHash(now) == hash) continue
            if (!isProgramFile) return false
            dirty.add(path)
        }
        // A program file the snapshot never hashed cannot be compared, so it cannot be
        // proved unchanged.
        if (snapshot.programFiles.any { it !in snapshot.fileHashes }) return false
        surface = ExportSurface(
            signatures = snapshot.exportSignatures,
            escapes = snapshot.exportEscapes,
            diagnostics = snapshot.diagnostics,
            programFiles = snapshot.programFiles,
        )
        dirtyFiles = dirty
        restoredUnverified = true
        return true
    }

    /**
     * Releases the overlay and the cached build.
     *
     * Idempotent, so a host may close on every teardown path without tracking
     * whether it already has. Any query or edit afterwards throws
     * [IllegalStateException]: a closed project has no state to answer from, and
     * silently reopening it would hand back the on-disk truth as though the caller's
     * edits were still applied.
     *
     * The compiler's process-global parse cache is deliberately NOT cleared —
     * it is shared by every project in the process and keyed by content, so
     * dropping it here would slow down unrelated work to free memory the next build
     * would immediately re-earn.
     */
    public fun close() {
        if (closed) return
        closed = true
        overlay.clear()
        cached = null
        narrowed.clear()
        captures.clear()
        prepared = null
        // (INC.40) The live program is a claim about the text this project was built
        // from; `ProgramRecheck` has no invalidation protocol and deliberately wants
        // none, so the handle goes wherever [cached] goes.
        recheck = null
        // (INC.46) the export surface is a claim about a live project.
        surface = null
        dirtyFiles = null
        lineMaps.clear()
        sourceIndexes.clear()
        ownParses.clear()
        parseOptions = null
    }

    /**
     * (INC.13) The capture that answers about [nodes] — asking about the WHOLE FILE
     * whenever the whole file's answer contains theirs.
     *
     * ## What this changes, and why it is the shape an editor wants
     *
     * A caret-scoped request names ONE span, so the memo [captureIn] keeps hits only
     * when the user asks the identical question again: hover-then-navigate at one
     * caret was free from (INC.12), and the caret NEXT DOOR was a full build. Since
     * every one of those queries is about a name, and a file's names are known
     * without asking the compiler anything, the question can be widened to the file
     * — and then the first hover in a buffer pays for every later caret in it.
     *
     * The population is [SourceIndex.occurrenceNodes] — every identifier plus every
     * literal in a member-NAME position — which is *deliberately* the population
     * [documentHighlightsAt] sweeps, so those two members and [fileSemantics] all ask
     * ONE question per file and share ONE memo entry. That is not a coincidence to be
     * preserved by accident: [referencesOf] builds its spans through
     * [occurrenceSpansOf] for exactly this reason, and a change to either side that
     * does not change the other silently un-shares them (the only symptom is a build
     * count, which is what `ProjectCaptureMemoTest` pins).
     *
     * ## When it does NOT widen
     *
     * A caret can land on a node that is no occurrence at all — a call expression, a
     * numeric literal, a `this`, a parenthesized expression — and a file-wide request
     * would simply not carry it, which is the silent failure ([captureIn]'s partition
     * argument, one level up: an absent answer renders nothing and reports no error).
     * So the widening is conditional on every asked node being IN the file's set, and
     * anything else falls back to naming exactly what was asked. [nodes] is EMPTY only
     * for a caller that asked about nothing, which cannot happen here.
     *
     * ## The cost, stated
     *
     * The first query in a buffer becomes a whole-file capture — more spans typed, so
     * more work — and every later caret in it becomes free. `scripts/warm-program-cost.sh`
     * carries both halves. That the two answers AGREE is not assumed: it is swept span
     * for span over a real project by `scripts/caret-vs-file-capture.sh`, which needs
     * no baseline because a caret-scoped and a file-wide build answer the same
     * question.
     */
    private fun captureAround(
        key: String,
        index: SourceIndex,
        node: Node,
    ): ProjectCompiler.Result = captureAround(key, index, listOf(node))

    private fun captureAround(
        key: String,
        index: SourceIndex,
        nodes: Collection<Node>,
    ): ProjectCompiler.Result {
        val fileWide = occurrenceSpansOf(key, index)
        if (fileWide.isNotEmpty()) {
            val covered = HashSet<Long>(fileWide.size * 2)
            for (span in fileWide) covered.add(packSpan(span.start, span.end))
            if (nodes.all { packSpan(it.pos, it.end) in covered }) {
                return preparedAnswerFor(fileWide) ?: captureIn(TypeCaptureRequest(fileWide))
            }
        }
        val asked = nodes.map { TypeCaptureSpan(key, it.pos, it.end) }.distinct()
        // A caret on a node the file-wide request would not carry — a call
        // expression, a literal, a `this`. A prepared check does not carry it either,
        // UNLESS it happens to: the containment test is over what was ASKED, so it
        // decides that rather than assuming it.
        return preparedAnswerFor(asked) ?: captureIn(TypeCaptureRequest(asked))
    }

    /**
     * (INC.14) [prepared]'s answer to [wanted], when the prepared build was asked
     * about every one of those spans — otherwise null, and the caller builds.
     *
     * The test is CONTAINMENT of the asked spans, not membership of a file, because
     * an answer that was never asked for is ABSENT rather than wrong: a hover served
     * from a check that did not carry its span would render nothing, silently — which
     * is [captureIn]'s own partition hazard one layer up. Deciding it against the
     * prepared REQUEST's spans is what makes it a property of what actually happened
     * rather than of two call sites deriving their span lists the same way today.
     *
     * It also decides the whole-program cases without their callers stating anything:
     * [referencesAt] sweeps every file, so a prepared check over a working set does
     * not contain its spans and this answers null.
     */
    private fun preparedAnswerFor(wanted: List<TypeCaptureSpan>): ProjectCompiler.Result? {
        val check = prepared ?: return null
        for (span in wanted) {
            val covered = check.covered[span.fileName] ?: return null
            if (packSpan(span.start, span.end) !in covered) return null
        }
        return check.result
    }

    /**
     * [fileName]'s whole occurrence span set, in [SourceIndex.occurrenceNodes]' own
     * (sorted, total) order — the ONE file-wide capture request this class asks.
     *
     * Its callers are [captureAround] and [referencesOf], and they must agree element
     * for element or the memo stops being shared; see [captureAround].
     */
    private fun occurrenceSpansOf(fileName: String, index: SourceIndex): List<TypeCaptureSpan> =
        occurrenceSpansOf(fileName, index.occurrenceNodes())

    private fun occurrenceSpansOf(fileName: String, nodes: List<Node>): List<TypeCaptureSpan> =
        nodes.map { TypeCaptureSpan(fileName, it.pos, it.end) }

    /**
     * (INC.2b) ONE capture build, with the CHECK narrowed to the files the request
     * asks about — `diagnosticsOf`'s partition (INV.6), pointed at a capture.
     *
     * The whole program is still crawled, parsed and bound; what narrows is the
     * per-file CHECKING, so a query about one buffer stops paying for the other 77
     * files' statements to be walked. Measured on tsc's own 78 compiler sources,
     * a capture build falls from ~4.6 s to ~1.1 s — the reason every caret-scoped
     * member here routes through this.
     *
     * ## The partition is DERIVED, and that is the whole safety argument
     *
     * A span in a file the checker never walks is never walked PAST, so its answer
     * is not wrong — it is simply ABSENT, and a hover would render nothing with no
     * error anywhere. That failure is silent by construction, so the partition is
     * computed HERE from the request's own spans rather than passed in beside them:
     * a call site cannot forget a file it asked about, because it never states the
     * set at all. Adding a fifth span list to [TypeCaptureRequest] means adding it
     * to [captureFiles] in the same commit; `ProjectCaptureNarrowingTest` is what
     * notices if it is not.
     *
     * ## What may NOT come through here
     *
     * A query that reads the build's DIAGNOSTICS may not come through here — the
     * rename sweep and its verification do, and a partition filters diagnostics to its
     * own files by design, so a narrowed "before" bag would be compared against a
     * whole-program "after" one.
     *
     * **(INC.44) [referencesAt] now DOES come through here.** It reads captures only,
     * and its request is no longer every identifier in the program: an occurrence must
     * spell a name the symbol can be reached by, so the derived partition is the files
     * that mention one. Where that closure cannot be bounded it falls back to the
     * whole-program build, which is why the other branch still exists.
     *
     * Equivalence of the narrowed answer to the whole-program one is not assumed: it
     * is swept span for span over a real project by `scripts/capture-equivalence.sh`
     * (types and definitions) and `scripts/capture-channel-equivalence.sh` (members,
     * scopes and signatures).
     *
     * ## (INC.12) The build is MEMOIZED on the request
     *
     * See [captures]. An identical request against an unchanged project is answered
     * without building — which is not a micro-optimization but the whole of the
     * repeat-query case: hover-then-navigate at one caret is one request asked twice,
     * and document highlights ask the same file-wide request at every caret.
     *
     * ## (INC.13) …and the request is the FILE's, not the caret's
     *
     * Which is what makes the memo hit for a caret nobody has visited yet. The rule,
     * and the case it deliberately declines to widen, are [captureAround]'s.
     */
    private fun captureIn(request: TypeCaptureRequest): ProjectCompiler.Result {
        captures.remove(request)?.let {
            // Re-inserted so the LRU order below is ACCESS order, not insertion order.
            // With two entries that is not a detail: it is what keeps the file-wide
            // request resident while the caret-scoped one is replaced on every move.
            captures[request] = it
            return it
        }
        val result = ProjectCompiler(overlay).build(
            projectPath,
            cancellation = cancellation,
            noEmit = true,
            recheckOnly = captureFiles(request),
            typeCapture = request,
        )
        rememberCapture(request, result)
        return result
    }

    /**
     * (INC.32) Files [result] under [request], then evicts down to the two lanes
     * [captures] documents — buffer-sized entries bounded at [CAPTURE_MEMO_BUFFERS],
     * caret-scoped ones at [CAPTURE_MEMO_CARET_ENTRIES], neither able to evict the
     * other's.
     */
    private fun rememberCapture(request: TypeCaptureRequest, result: ProjectCompiler.Result) {
        captures[request] = result
        while (true) {
            var buffers = 0
            var carets = 0
            for (key in captures.keys) if (isBufferSized(key)) buffers++ else carets++
            // The lane that is OVER decides which lane the victim comes from. Taking
            // the map's least recently used entry regardless would not bring an
            // over-full lane under its bound, and this loop would spin.
            val evictBuffer = buffers > CAPTURE_MEMO_BUFFERS
            if (!evictBuffer && carets <= CAPTURE_MEMO_CARET_ENTRIES) return
            // [captureIn] re-inserts on a hit, so this map is in ACCESS order and the
            // FIRST key of a lane is that lane's least recently used entry.
            val victim = captures.keys.firstOrNull { isBufferSized(it) == evictBuffer } ?: return
            captures.remove(victim)
        }
    }

    /**
     * Whether [request] is a BUFFER-sized capture rather than a caret-scoped one —
     * see [CAPTURE_MEMO_CARET_SPANS] for where the line is and why anywhere between
     * one and a hundred would do.
     *
     * Weighed on the REQUEST rather than on the answer because the request is the key
     * and is therefore in hand before the build as well as after it, and because the
     * two agree by construction: the checker records at most one answer per span it
     * was asked about.
     */
    private fun isBufferSized(request: TypeCaptureRequest): Boolean =
        request.spans.size + request.memberSpans.size +
            request.scopeSpans.size + request.signatureSpans.size > CAPTURE_MEMO_CARET_SPANS

    /**
     * Every file [request] names — see [captureIn] for why this is derived and not
     * given. All four span lists, because a member the checker never reached is as
     * absent as a type it never reached.
     */
    private fun captureFiles(request: TypeCaptureRequest): Set<String> {
        val files = LinkedHashSet<String>()
        for (span in request.spans) files.add(span.fileName)
        for (span in request.memberSpans) files.add(span.fileName)
        for (span in request.scopeSpans) files.add(span.fileName)
        for (span in request.signatureSpans) files.add(span.fileName)
        return files
    }

    /**
     * The current build, computing it if this project is dirty.
     *
     * `noEmit = true` and `outDir = null`: see the class KDoc. `recheckOnly` is
     * likewise null, and must stay so: this build's result is what [cached] holds
     * and what [diagnostics] answers with, and those are WHOLE-PROGRAM contracts —
     * a partition's diagnostics are a subset and would be reported as the whole.
     * The narrowing exists, but it belongs to [diagnosticsOf], which asks a narrow
     * question and keeps its answer out of [cached] for exactly this reason.
     */
    private fun build(): ProjectCompiler.Result {
        checkOpen()
        cached?.let { return it }
        // (INC.46) A whole-program build also establishes the export surface, so the
        // NEXT edit has a baseline to be compared against. ~136 ms on tsc's own 78
        // sources against a 5.2 s rebuild — paid here, on the build the user already
        // waited for, and never on the incremental path.
        // (INC.48) The build's own `.json` inputs, recorded for the snapshot: which of
        // them a build reads is not a function of the project path (`extends`, and a
        // `package.json` under `nodenext`).
        overlay.clearJsonReads()
        val result = ProjectCompiler(overlay).build(
            projectPath, noEmit = true, exportSignatures = true,
            cancellation = cancellation,
        )
        cached = result
        restoredUnverified = false
        surface = ExportSurface(
            signatures = result.exportSignatures,
            escapes = result.exportSignatureEscapes,
            diagnostics = result.diagnostics,
            programFiles = result.programFiles,
        )
        dirtyFiles = null
        return result
    }

    /**
     * (INC.46) The whole program's diagnostics after an edit, WITHOUT rebuilding the
     * whole program — or null when that cannot be justified.
     *
     * ## The idea, and why it is not a dependency closure
     *
     * The standing plan for making project-wide diagnostics incremental was a
     * reverse-dependency closure, and it is owner-closed as (INC.35) because a closure
     * only pays on LAYERED code: measured, both a file-level and a SYMBOL-level graph
     * re-check 100% of tsc's characters at the median edit. This asks a different
     * question — not WHICH symbols a file's dependents use, but whether the symbols
     * this file EXPORTS have moved. An edit inside a function body leaves every
     * exported signature intact, so no dependent can observe it however dense the graph
     * is, and the answer is the previous build's rows with the edited files' rows
     * replaced. Measured over 40 real commits to tsc's own `src/compiler`, **67%** of
     * them move no touched file's fingerprint.
     *
     * ## What must hold, and each one is checked rather than argued
     *
     *  - **A baseline exists** — some whole-program build established [surface].
     *  - **Every edited file was in that program**, so a new or removed file falls back
     *    (its arrival changes what every importer resolves).
     *  - **No edited file ESCAPES.** A script file, a global augmentation, an export the
     *    walk cannot enumerate or a walk that ran out of budget cannot be proved stable,
     *    and an escape must never be read as "no exports".
     *  - **The narrowed build finds the SAME program.** The crawl still runs in full, so
     *    an edit that adds or removes an import shows up here as a different file list —
     *    and that is a program change, not a signature one.
     *  - **No edited file's fingerprint moved**, compared against the baseline. Sound
     *    because a narrowed build's fingerprint for a file equals the whole-program
     *    build's: swept 24 of 24 on the compiler profile, which is what makes the
     *    mechanism CONVERGE rather than fall back on every first edit.
     *
     * Any of them failing answers null, and the caller rebuilds. Every failure costs a
     * rebuild the caller was going to pay for anyway; the one thing that must never
     * happen — answering with stale rows — is what all five exist to prevent.
     */
    private fun incrementalDiagnostics(): List<Diagnostic>? {
        val base = surface ?: return null
        val dirty = dirtyFiles ?: return null
        // (INC.48) An EMPTY dirty set is normally nothing to do — `diagnostics` answers
        // from the surface without reaching here. It reaches here only for a RESTORED
        // surface, where "no file changed" is precisely the case that still has to be
        // verified: a file ADDED while the process was down is in no content hash, and
        // the check that sees it is this build's own re-crawl.
        if (dirty.isEmpty() && !restoredUnverified) return null
        // A config edit changes what the program IS, so nothing about the previous one
        // survives it.
        if (dirty.any { it.endsWith(".json") }) return null
        val programFiles = base.programFiles.toHashSet()
        if (dirty.any { it !in programFiles }) return null
        if (dirty.any { it in base.escapes }) return null

        val narrowed = ProjectCompiler(overlay).build(
            projectPath, noEmit = true, recheckOnly = dirty, exportSignatures = true,
            cancellation = cancellation,
        )
        if (narrowed.programFiles != base.programFiles) return null
        for (file in dirty) {
            if (file in narrowed.exportSignatureEscapes) return null
            val before = base.signatures[file] ?: return null
            val after = narrowed.exportSignatures[file] ?: return null
            if (before != after) return null
        }

        // Every edited file's export surface is unchanged, so no file outside `dirty`
        // can have changed its own diagnostics: keep their rows verbatim and splice the
        // edited files' fresh rows in.
        //
        // SPLICED IN PLACE, not appended: [diagnostics] is documented as answering in
        // the compiler's own order, and a host that renders a project-wide list would
        // otherwise see every edited file's rows jump to the bottom after an edit. Each
        // edited file's replacement rows go where its FIRST old row was; a file that had
        // none and has some now appends, which is the only case with no position to
        // preserve.
        val fresh = LinkedHashMap<String, MutableList<Diagnostic>>()
        for (d in narrowed.diagnostics) {
            val f = d.fileName?.let { PathUtil.normalize(it) } ?: continue
            if (f in dirty) fresh.getOrPut(f) { ArrayList() }.add(d)
        }
        val answer = ArrayList<Diagnostic>(base.diagnostics.size)
        val spliced = HashSet<String>()
        for (d in base.diagnostics) {
            val f = d.fileName?.let { PathUtil.normalize(it) }
            if (f == null || f !in dirty) { answer.add(d); continue }
            if (spliced.add(f)) fresh[f]?.let { answer.addAll(it) }
        }
        for ((f, rows) in fresh) if (f !in spliced) answer.addAll(rows)
        // The surface is unchanged BY THE TEST ABOVE, so it carries forward — which is
        // what makes a SEQUENCE of body-only edits each cost one narrowed build rather
        // than the first one cheap and the rest full.
        surface = ExportSurface(base.signatures, base.escapes, answer, base.programFiles)
        dirtyFiles = null
        // (INC.48) The re-crawl above agreed with the snapshot's program, so the restored
        // state is now verified and a later query with nothing dirty may answer from it
        // directly.
        restoredUnverified = false
        incrementalAnswers++
        return answer
    }

    /** [path] as this project keys it: normalized and absolute. */
    private fun keyOf(path: String): String =
        overlay.resolveAbsolute(PathUtil.normalize(path))

    private fun checkOpen() {
        check(!closed) { "this Project is closed: $projectPath" }
    }
}

/**
 * (INC.40) The ONE-WAY VALVE between [Project] and the re-entrant checker.
 *
 * `Recheck.kt`'s [ProgramRecheck] answers five channels and is graded EQUIVALENT
 * on exactly one of them (diagnostics; definitions agree too, types do not — see
 * `Project.recheck`'s KDoc for the counts). A comment saying "do not ask it for a
 * type" is not a guard: the next caller to reach for a fast hover would find
 * `recheck(files, capture)` sitting there with a `TypeCaptureRequest` parameter and
 * a `RecheckAnswer` full of `CapturedType`s, and nothing would stop them.
 *
 * So the valve is the TYPE. [program] is `private`, the single member takes a
 * `Set<String>` and returns a `List<Diagnostic>`, and no `TypeCaptureRequest` is
 * expressible at this boundary and no `CapturedType` can leave it. Widening this
 * class is what a future round would have to do deliberately, in a commit that
 * says so — which is the whole difference between a refusal and a comment.
 *
 * `ProjectRecheckWiringTest` pins both halves: that the diagnostics path DOES reach
 * a re-entry (a count of builds, not a time), and that the capture-serving members
 * do not.
 */
private class DiagnosticsOnlyRecheck(private val program: ProgramRecheck) {

    /** Every file the live program has walked — the seed partition plus everything
     *  [diagnosticsOf] has since added. Exposed for pinning, not for policy. */
    val walkedFiles: Set<String> get() = program.walkedFiles

    /**
     * The program's diagnostics with [files] made answerable, re-entering only the
     * partition-dependent `init` passes for the ones it has not walked.
     *
     * Deliberately calls the DEFAULT-ARGUMENT form: `capture` stays null and there
     * is no overload here that could pass one.
     */
    fun diagnosticsOf(files: Set<String>): List<Diagnostic> = program.recheck(files).diagnostics
}
