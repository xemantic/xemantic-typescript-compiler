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
import com.xemantic.typescript.compiler.Diagnostic
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
 * ## What this class is NOT
 *
 * It is not a language service: there are no completions, no find-references, and
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
     * ## What answers EMPTY, deliberately
     *
     * A MEMBER name — the `p` of `o.p`, a property signature's name, an enum member
     * behind its enum — is not answered. Resolving a member needs the receiver's
     * type and its property symbol, which is a different mechanism from the scope
     * lookup this uses; answering it with a scope lookup would find whatever
     * unrelated binding shares the spelling, and an editor that jumps somewhere
     * confidently wrong is worse than one that does not jump. Labels, keywords,
     * literals and any offset outside every node answer empty for the same reason:
     * they name nothing the scope chain binds.
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
