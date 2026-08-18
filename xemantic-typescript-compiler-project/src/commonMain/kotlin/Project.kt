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

import com.xemantic.typescript.compiler.CapturedDeclaration
import com.xemantic.typescript.compiler.CapturedDefinition
import com.xemantic.typescript.compiler.CompilerOptions
import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.PathUtil
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.TsConfigLoader
import com.xemantic.typescript.compiler.TypeCaptureRequest
import com.xemantic.typescript.compiler.TypeCaptureSpan
import com.xemantic.typescript.compiler.Vfs
import com.xemantic.typescript.compiler.computeParserFlags

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
 * ## What this class is NOT
 *
 * It is not a full language service: there is no rename, no keyword completion, no
 * signature help, and no incremental reuse of a previous build's internal state.
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
    public fun diagnostics(): List<Diagnostic> = build().diagnostics

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
        return build().diagnostics.filter { d ->
            d.fileName?.let { PathUtil.normalize(it) } == key
        }
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
        sourceIndexes[key]?.let { return it }
        val text = overlay.readText(key) ?: return null
        val options = parseOptions
            ?: TsConfigLoader(overlay).load(configPath).options.also { parseOptions = it }
        val flags = computeParserFlags(key, text, options)
        return SourceIndex.of(text, key, flags).also { sourceIndexes[key] = it }
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
     */
    public fun quickInfoAt(fileName: String, offset: Int): QuickInfo? {
        val index = sourceIndexOf(fileName) ?: return null
        val node = index.pathAt(offset).lastOrNull() ?: return null
        val key = keyOf(fileName)
        // The RAW `Node.end` is the capture's IDENTITY — the compiler matches its own
        // nodes on the same pair, and INV.1(e) makes its parse of this text with these
        // flags the same parse. The REAL end, snapped back to the token stream, is
        // what the caller is told.
        val span = TypeCaptureSpan(key, node.pos, node.end)
        val captured = ProjectCompiler(overlay)
            .build(projectPath, noEmit = true, typeCapture = TypeCaptureRequest(listOf(span)))
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
     * neither reads nor fills the [diagnostics] cache.
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
     * An element access (`o["p"]`) — the argument is a literal, and only identifiers
     * are offered a definition. An object-literal property name (`{ p: v }`) — the
     * answer would be a contextual type's property, which is a third mechanism. A
     * member's own DECLARATION name — it already is the declaration. A chained
     * namespace segment (`A.B.x`). Labels, keywords, literals and any offset outside
     * every node: they name nothing, through either mechanism.
     */
    public fun definitionsAt(fileName: String, offset: Int): List<DefinitionLocation> {
        val index = sourceIndexOf(fileName) ?: return emptyList()
        val node = index.pathAt(offset).lastOrNull() ?: return emptyList()
        val key = keyOf(fileName)
        // The RAW `Node.end` is the capture's IDENTITY, exactly as in `quickInfoAt`.
        val span = TypeCaptureSpan(key, node.pos, node.end)
        return ProjectCompiler(overlay)
            .build(projectPath, noEmit = true, typeCapture = TypeCaptureRequest(listOf(span)))
            .capturedDefinitions
            .firstOrNull { it.fileName == key && it.start == node.pos && it.end == node.end }
            ?.locations
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
     * **KEYWORDS.** A useful keyword list is context-sensitive — `interface` may be
     * written where a statement may start and not inside an expression, `await` only
     * inside an async function, `extends` only in a heritage clause — and the anchor
     * is a TOKEN-level device that knows what precedes the caret and not what
     * grammar production it sits in. An unconditional list would offer items that do
     * not compile, which is the one thing the member half already refuses to do (a
     * union receiver offers only members present on every constituent for exactly
     * that reason). So keywords are a host's own concern until there is a
     * grammar-position mechanism to key them on, and this offers none.
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
     * ## What this offers that tsc would not, deliberately
     *
     * PRIVATE and PROTECTED members are OFFERED, with
     * [CompletionItem.accessibility] saying which they are. Filtering them correctly
     * depends on where the caret is relative to the declaring class — inside the
     * class, inside a subclass, or outside — and that is a second mechanism this
     * round did not build; reporting the fact lets a host apply its own rule, where
     * hiding them on a half-implemented test would silently lose real candidates.
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
            // The RAW `Node.end` is the capture's IDENTITY, exactly as in `quickInfoAt`.
            val node = anchor.scopeAnchor
                ?: return CompletionList(
                    anchor.kind,
                    anchor.prefix,
                    anchor.replacementStart,
                    anchor.replacementEnd,
                    emptyList(),
                    null,
                )
            val span = TypeCaptureSpan(key, node.pos, node.end)
            val captured = ProjectCompiler(overlay)
                .build(
                    projectPath,
                    noEmit = true,
                    typeCapture = TypeCaptureRequest(
                        spans = emptyList(),
                        scopeSpans = listOf(span),
                    ),
                )
                .capturedScopes
                .firstOrNull { it.fileName == key && it.start == node.pos && it.end == node.end }
            return CompletionList(
                CompletionKind.FREE_NAME,
                anchor.prefix,
                anchor.replacementStart,
                anchor.replacementEnd,
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
        val captured = ProjectCompiler(overlay)
            .build(
                projectPath,
                noEmit = true,
                typeCapture = TypeCaptureRequest(spans = emptyList(), memberSpans = listOf(span)),
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
     * ## What is refused, and why each is a refusal rather than a gap
     *
     * **READ versus WRITE is not reported.** An editor colours the two differently
     * and this deliberately offers no field for it. The reason is that a partial
     * answer is worse than none here: `x = 1` and `x++` are trivially writes, and
     * `[x] = pair`, `({ x } = o)` and `for (x of xs)` are writes whose identifier sits
     * under an array literal, an object literal or a `for` head — so a rule built from
     * the easy positions reports the destructuring ones as READS, and a host cannot
     * tell a complete answer from an incomplete one. Deciding it properly is a
     * grammar-position question, which is the same mechanism keyword completions are
     * refused for (§ [completionsAt]).
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
     * has been edited), and the sweep itself is a single capture build carrying every
     * identifier in every program file. That is the same trick [fileSemantics] plays,
     * widened from one file to the program — a count of compiles, so it does not grow
     * with the number of carets, only with the program.
     *
     * It is linear in the program and it is not cheap. `docs/language-service.md`
     * carries the measured figure on this repo's own 78-file compiler profile. Use
     * [documentHighlightsAt] for the per-caret case; this is the one a user asks for
     * explicitly.
     */
    public fun referencesAt(fileName: String, offset: Int): List<ReferenceLocation> {
        val index = sourceIndexOf(fileName) ?: return emptyList()
        // Only an identifier names anything, and a caret that cannot be answered must
        // not pay for a compile — the rule `completionsAt` and `semanticsAt` already
        // follow.
        val caret = index.pathAt(offset).lastOrNull() as? Identifier ?: return emptyList()
        // The program's files are what a build computes, so this asks for them the
        // only way there is; `build()` is cached whenever nothing has been edited.
        val swept = build().programFiles.map { keyOf(it) }
        return referencesOf(keyOf(fileName), caret, swept, restrictToQueryFile = false)
    }

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
     * identity, the same [ReferenceLocation.isDeclaration] flag, the same refusal of
     * a read/write distinction, the same ordering.
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
        val caret = index.pathAt(offset).lastOrNull() as? Identifier ?: return emptyList()
        val key = keyOf(fileName)
        return referencesOf(key, caret, listOf(key), restrictToQueryFile = true)
    }

    /**
     * The one build both reference queries perform, and the grouping of its answers.
     *
     * [sweptFiles] is the population whose identifiers become capture spans — the
     * whole program for [referencesAt], the one queried file for
     * [documentHighlightsAt] — and [restrictToQueryFile] additionally drops answers
     * outside [queryFile], which matters only for a DECLARATION the caret resolves to
     * in a file that was never swept.
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
    ): List<ReferenceLocation> {
        val spans = ArrayList<TypeCaptureSpan>()
        // Every swept identifier's REAL end, by (file, pos). The capture speaks the
        // RAW `(pos, end)` identity and a caller must be told the real extent, so the
        // translation is recorded here rather than re-derived per answer.
        val realEnds = HashMap<String, HashMap<Int, Int>>(sweptFiles.size)
        for (file in sweptFiles) {
            val index = sourceIndexOf(file) ?: continue
            val ends = HashMap<Int, Int>()
            for (id in index.identifiers()) {
                spans.add(TypeCaptureSpan(file, id.pos, id.end))
                ends[id.pos] = index.realEndOf(id)
            }
            realEnds[file] = ends
        }
        if (spans.isEmpty()) return emptyList()
        val definitions = ProjectCompiler(overlay)
            .build(projectPath, noEmit = true, typeCapture = TypeCaptureRequest(spans))
            .capturedDefinitions
        val seed = referenceSeed(definitions, queryFile, caret) ?: return emptyList()
        val hits = LinkedHashMap<Pair<String, Int>, ReferenceLocation>()
        for (definition in definitions) {
            if (restrictToQueryFile && definition.fileName != queryFile) continue
            if (definition.locations.none { it in seed }) continue
            val end = realEnds[definition.fileName]?.get(definition.start) ?: continue
            hits[definition.fileName to definition.start] = ReferenceLocation(
                fileName = definition.fileName,
                start = definition.start,
                end = end,
                isDeclaration = seed.any {
                    it.fileName == definition.fileName && it.start == definition.start
                },
            )
        }
        // The declarations themselves. Most are already above — a free name's
        // declaration name resolves to its own symbol — but a MEMBER's declaration
        // name resolves to nothing, and a declaration in a file the sweep never
        // covered (a lib) cannot be there at all.
        for (location in seed) {
            if (restrictToQueryFile && location.fileName != queryFile) continue
            hits.getOrPut(location.fileName to location.start) {
                ReferenceLocation(
                    fileName = location.fileName,
                    start = location.start,
                    end = location.start + location.length,
                    isDeclaration = true,
                )
            }
        }
        return hits.values.sortedWith(compareBy({ it.fileName }, { it.start }))
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
                return definition.locations.toSet()
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
        val result = ProjectCompiler(overlay).build(
            projectPath,
            noEmit = true,
            typeCapture = TypeCaptureRequest(
                distinct.values.map { TypeCaptureSpan(key, it.pos, it.end) },
            ),
        )
        val types = HashMap<Long, String>(result.capturedTypes.size)
        for (captured in result.capturedTypes) {
            if (captured.fileName == key) types[packSpan(captured.start, captured.end)] = captured.typeText
        }
        val definitions = HashMap<Long, List<DefinitionLocation>>(result.capturedDefinitions.size)
        for (captured in result.capturedDefinitions) {
            if (captured.fileName != key) continue
            definitions[packSpan(captured.start, captured.end)] = captured.locations.map {
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
        if (key.endsWith(".json")) {
            parseOptions = null
            sourceIndexes.clear()
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
        invalidate(key)
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
        lineMaps.clear()
        sourceIndexes.clear()
        parseOptions = null
    }

    /**
     * The current build, computing it if this project is dirty.
     *
     * `noEmit = true` and `outDir = null`: see the class KDoc. `recheckOnly` is
     * likewise null — it is the watch mode's per-file narrowing, and narrowing a
     * rebuild to a file set is only sound with a dependency closure this class does
     * not maintain.
     */
    private fun build(): ProjectCompiler.Result {
        checkOpen()
        cached?.let { return it }
        val result = ProjectCompiler(overlay).build(projectPath, noEmit = true)
        cached = result
        return result
    }

    /** [path] as this project keys it: normalized and absolute. */
    private fun keyOf(path: String): String =
        overlay.resolveAbsolute(PathUtil.normalize(path))

    private fun checkOpen() {
        check(!closed) { "this Project is closed: $projectPath" }
    }
}
