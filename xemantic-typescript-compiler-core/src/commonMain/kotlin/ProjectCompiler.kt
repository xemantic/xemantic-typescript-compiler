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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * INV.1(b): bounded in-flight files for the concurrent read+parse batches —
 * enough to keep the IO dispatcher busy while extraction parses saturate
 * Default's CPU-sized pool. The bound is backpressure over the DECODE/PARSE
 * stage only (bounded peak transient memory); the resident program is the
 * whole loaded file set regardless.
 */
private const val FRONTEND_CONCURRENCY = 16

/**
 * Drives a real on-disk whole-project build: loads `tsconfig.json`, expands
 * `include`/`exclude` globs to root files, walks the import graph (via
 * [ModuleResolver]) to gather every reachable source/declaration file, feeds the
 * whole set through [TypeScriptCompiler.compileParsed], and (unless `noEmit`)
 * writes the JS/declaration outputs to `outDir`.
 *
 * This is the layer the per-file [compile] string API never had: filesystem IO
 * (workstream 1), tsconfig + glob project discovery (workstream 2), and node /
 * nodenext / bundler module resolution including `node_modules` (workstream 3).
 */
class ProjectCompiler(private val vfs: Vfs) {

    data class Result(
        val configPath: String,
        val rootFiles: List<String>,
        /** Every file in the program (roots + reachable imports + declarations). */
        val programFiles: List<String>,
        val diagnostics: List<Diagnostic>,
        /** Specifiers that could not be resolved, as (importer, specifier). */
        val unresolved: List<Pair<String, String>>,
        /** INV.7(d1): resolved import edges as (importer, imported), crawl order. */
        val importEdges: List<Pair<String, String>> = emptyList(),
        /** INV.7(d1): program files with module syntax (import/export) — a change in a
         *  NON-module (script) file has global effects and forces a full rebuild. */
        val moduleFiles: Set<String> = emptySet(),
        /** INV.7(d2): module files declaring a top-level name SHARED with a lib
         *  global or a script-file global — such declarations MERGE program-wide
         *  (the INV.3(d) shared-name survivor), so a change can reach
         *  non-importers and forces a full rebuild. Approximation: lib names =
         *  the checker's KNOWN_GLOBALS curation (real-lib extras slip through —
         *  the --watchVerify field net covers the gap). */
        val sharedNameFiles: Set<String> = emptySet(),
        /** Output files written to disk as (path, byteLength). Empty when noEmit. */
        val written: List<Pair<String, Int>>,
        /** (API.3) The types the checker recorded at the spans a [TypeCaptureRequest]
         *  named, or empty — which is every build that did not ask for any. */
        val capturedTypes: List<CapturedType> = emptyList(),
        /** (API.3b) Where the symbols at those same spans are declared, or empty. */
        val capturedDefinitions: List<CapturedDefinition> = emptyList(),
        /** (API.4a) What the types at the request's `memberSpans` call their own,
         *  or empty — the member half of a completion list. */
        val capturedMembers: List<CapturedMembers> = emptyList(),
        /** (API.4b) What the lexical scope chain binds at the request's
         *  `scopeSpans`, or empty — the free-name half of a completion list. */
        val capturedScopes: List<CapturedScope> = emptyList(),
        /** (API.6) Every signature the callees at the request's `signatureSpans`
         *  have, or empty — the signature-help answer. */
        val capturedSignatures: List<CapturedSignatures> = emptyList(),
        /**
         * (INC.46) `fileName -> fingerprint of everything an importer of it can
         * observe`, or empty — which is every build that did not ask (`exportSignatures`
         * false, the default).
         *
         * Covers the files this build's checker WALKED, so a narrowed build carries its
         * partition and a whole-program one carries the program. See
         * [ExportSignatures] for what the hash is and why it may not be a display
         * string.
         */
        val exportSignatures: Map<String, Long> = emptyMap(),
        /**
         * (INC.46) The files whose export surface could NOT be summarised exactly and
         * must therefore invalidate the whole program however they are edited: a SCRIPT
         * file, a global augmentation, an export this walk cannot enumerate, or a walk
         * that ran out of node budget.
         *
         * A consumer that ignores this is unsound in the SILENT direction — a stale
         * diagnostic — so it is a separate field rather than an absence from
         * [exportSignatures], which would read as "no exports".
         */
        val exportSignatureEscapes: Set<String> = emptySet(),
    ) {
        val errorCount: Int get() = diagnostics.count { it.category == DiagnosticCategory.Error }
    }

    /** Source extensions considered "root" inputs (declarations included; emit skips them). */
    private val rootExtensions = listOf(".ts", ".tsx", ".mts", ".cts")

    /**
     * @param projectPath a directory containing `tsconfig.json`, or a path to a tsconfig file.
     * @param noEmit when true, type-check only — do not write outputs.
     * @param recheckOnly (INC.1) when non-null, narrows the CHECKER to these files
     *   (the INV.6 partition view) while the program itself stays whole — everything
     *   is crawled, parsed and bound, and the diagnostics reported for a named file
     *   are the ones a whole-program build reports for it (the gate is
     *   `scripts/partition-equivalence.sh`; captured types and definitions are
     *   `scripts/capture-equivalence.sh`). REQUIRES [noEmit]: see the `require` in
     *   the body for why a partition may not emit.
     * @param outDir when non-null, OVERRIDES the config's `outDir` (resolved against the
     *   process CWD, like tsc's `--outDir`), so a caller can send the emitted tree
     *   somewhere throwaway without touching the project. (AOT.4)(c), round 840(c): the
     *   AOT trainer emits — an emit-trained cache is worth ~1.26 s on an emitting compile —
     *   and a training run must never write into the user's project. Inert under `noEmit`.
     * @param typeCapture (API.3) when non-null, the checker records the type and the
     *   definition at each named span while it walks past it, and the answers come
     *   back in [Result.capturedTypes] and [Result.capturedDefinitions].
     *   Null — the default — leaves the whole pipeline
     *   untouched; see [TypeCaptureRequest] for why a capture is directed inwards
     *   rather than answered from a retained checker afterwards.
     * @param checkedSink (KIR) when non-null, the checker hands every checked
     *   expression and declaration-shaped node of every program file to this sink
     *   AS IT WALKS PAST IT — the seam a backend consumes, and the only route by
     *   which a whole PROJECT (imports resolved, cross-file symbols merged) can be
     *   fed to one. Directed inwards for [TypeCaptureRequest]'s reason, and forcing
     *   the sequential checker for its own; see [CheckedNodeSink].
     */
    fun build(
        projectPath: String,
        noEmit: Boolean = false,
        recheckOnly: Set<String>? = null,
        outDir: String? = null,
        typeCapture: TypeCaptureRequest? = null,
        checkedSink: CheckedNodeSink? = null,
        /** (INC.17) When non-null, this build hands its LIVE program back here, so a
         *  later question about a file outside [recheckOnly] can be answered by
         *  re-entering the partition-dependent checker passes instead of building.
         *  Retains the whole checker, and its CAPTURED-TYPE channel is known to
         *  diverge from a fresh build in 43 of 75 files of the compiler profile while
         *  its DIAGNOSTIC channel is graded equivalent on both arms — which is why
         *  (INC.40)'s one shipped caller (`Project.diagnosticsOf`) puts the handle
         *  behind a valve that can ask for nothing else. Read [ProgramRecheck]'s
         *  banner before adding another. */
        recheckHolder: RecheckHolder? = null,
        /**
         * (INC.55) A host's cancellation signal, polled BY THIS BUILD's compile
         * thread. Null (the default) means the build cannot be cancelled, which is
         * every CLI and corpus build.
         *
         * Installed for the duration of this call and restored afterwards, so a
         * cancelled build leaves nothing armed behind it. When it fires, the build
         * throws [CompilationCancelledError] and produces NO result — which is what
         * makes a caller's state safe by construction, since every cache assignment
         * in `Project` happens after `build` returns.
         */
        cancellation: CancellationSignal? = null,
        /**
         * (INC.46) When true this build also summarises each walked file's EXPORT
         * SURFACE into [Result.exportSignatures] / [Result.exportSignatureEscapes], so
         * a caller can decide whether an edit to a file could have changed what an
         * importer of it observes — which is what makes project-wide diagnostics
         * incremental (see `Project.diagnostics`).
         *
         * Off by default and free when off. It costs ~136 ms on a whole-program build
         * of tsc's own 78 sources and ~0 ms on a build narrowed to one file, which is
         * the case that matters: the answer is needed per EDIT, not per program.
         */
        exportSignatures: Boolean = false,
    ): Result {
        // (INC.55) Installed for exactly this build and restored however it ends, so a
        // cancelled build leaves nothing armed for the next one. The work is delegated
        // so that every `return` in the body below is covered by the `finally` without
        // the body itself having to know about cancellation.
        val previousSignal = Cancellation.install(cancellation)
        try {
            return buildCore(
                projectPath = projectPath,
                noEmit = noEmit,
                recheckOnly = recheckOnly,
                outDir = outDir,
                typeCapture = typeCapture,
                checkedSink = checkedSink,
                recheckHolder = recheckHolder,
                exportSignatures = exportSignatures,
            )
        } finally {
            Cancellation.restore(previousSignal)
        }
    }

    private fun buildCore(
        projectPath: String,
        noEmit: Boolean,
        recheckOnly: Set<String>?,
        outDir: String?,
        typeCapture: TypeCaptureRequest?,
        checkedSink: CheckedNodeSink?,
        recheckHolder: RecheckHolder?,
        exportSignatures: Boolean,
    ): Result {
        // (INC.4) A partition may not EMIT. The Transformer queries the checker it is
        // handed (`isReferencedAliasDeclaration` and friends decide import elision),
        // so under `recheckOnly` it would ask a checker that walked a SUBSET of the
        // program and elide an import some unwalked file's use keeps alive — wrong
        // JavaScript, silently, with every diagnostic still agreeing. Every driver in
        // this repo gates incremental work on `--noEmit` and `Project` always passes
        // `noEmit = true`, so nothing today is wrong; the parameter is public and the
        // next caller will not know. Refused here rather than deeper for
        // `compileParsed`'s reason: a message that names the caller's mistake.
        require(noEmit || recheckOnly == null) {
            "recheckOnly walks a partition and emit needs the whole program: " +
                "the Transformer asks the checker which imports are referenced, and a " +
                "partition checker has not seen the files that reference them — " +
                "pass noEmit = true, or drop recheckOnly"
        }
        // Absolutize first: glob regexes, module resolution, and output mapping all
        // assume absolute paths (a relative `.` would produce `./src/**` patterns that
        // never match the absolute paths the Vfs walk yields).
        val absPath = vfs.resolveAbsolute(projectPath)
        // A bare SOURCE-file argument (`xtsc foo.ts`) is compiled as a single-file program
        // with default options — like `tsc foo.ts`. It must NOT be loaded as a tsconfig:
        // parsing a `.ts` as JSON yields a garbage config, and downstream a corrupt lib
        // binderResult (its `sourceFile.text` no longer matching its statement positions)
        // that crashes the checker with a StringIndexOutOfBounds.
        val feConfigT0 = FrontEnd.t()
        val feCfgLoadT0 = FrontEnd.t()
        val isBareSourceFile = !vfs.isDirectory(absPath) &&
            !absPath.endsWith(".json") && vfs.exists(absPath)
        val configPath = if (isBareSourceFile) absPath else resolveConfigPath(absPath)
        val loadedConfig = if (isBareSourceFile) {
            LoadedTsConfig(
                options = projectDefaults(),
                configDir = PathUtil.dirname(absPath),
                include = emptyList(),
                exclude = emptyList(),
                files = listOf(PathUtil.normalize(absPath)),
                customConditions = emptyList(),
            )
        } else {
            TsConfigLoader(vfs).load(configPath)
        }
        FrontEnd.close(FrontEnd.CFG_LOAD, feCfgLoadT0)
        // The `outDir` override is applied HERE, on the loaded config, and nowhere else:
        // [writeOutputs] and the TS5055 filter are the only readers of `options.outDir`
        // (the core is handed `outDir = null` deliberately — see `emitOptions` below), so
        // one substitution moves the whole emitted tree. Absolutized like every other path
        // in this function, and against the CWD rather than the config dir, which is what
        // `tsc --outDir` does for a command-line value.
        val config =
            if (outDir == null) loadedConfig
            else loadedConfig.copy(
                options = loadedConfig.options.copy(outDir = vfs.resolveAbsolute(outDir)),
            )
        val resolver = ModuleResolver(vfs, config.customConditions)

        val allowJs = config.options.allowJs
        val supportedExt = if (allowJs) rootExtensions + listOf(".js", ".jsx", ".mjs", ".cjs") else rootExtensions

        val feCfgRootsT0 = FrontEnd.t()
        val rootFiles = collectRootFiles(config, supportedExt)
        FrontEnd.close(FrontEnd.CFG_ROOTS, feCfgRootsT0)
        FrontEnd.globRoots = rootFiles.size.toLong()

        // Automatic type-library inclusion (tsconfig `types` / `typeRoots`): the
        // resolved entries join the graph walk as additional seeds so their own
        // imports and `/// <reference types>` directives are followed too.
        val typeDiagnostics = mutableListOf<Diagnostic>()
        val feCfgTypesT0 = FrontEnd.t()
        val typeEntries = collectTypeRootEntries(config, resolver, typeDiagnostics)
        FrontEnd.close(FrontEnd.CFG_TYPES, feCfgTypesT0)

        // The options the compilation core receives. The core's output naming serves
        // baseline comparison: by default it strips names to basenames (which collide
        // across subdirectories), and its own outDir remap is keyed on the inputs'
        // common ancestor rather than rootDir. Withhold outDir and request full
        // input-derived paths instead (`fullEmitPaths` affects naming only); all
        // outDir/rootDir mapping happens in [writeOutputs] from the input paths.
        // Hoisted above the crawl (INV.1(e)): the crawl parses each file with the
        // parser flags computed from THESE options, so the core's pre-parse reuse
        // gate (content + flags equality) matches.
        val emitOptions = config.options.copy(
            outDir = null, fullEmitPaths = true,
            // (FRONT.1): a type-check-only build must not transform and emit
            // JavaScript it is about to discard — 8.4% of the compiler profile.
            skipEmitOutputs = noEmit || config.options.noEmit,
        )

        // Walk the import graph from the roots, reading and resolving as we go.
        // INV.1: the crawl is a cold Flow collected through the coroutine pipeline
        // seam ([runCompilerPipeline]); since (b) its per-file read+decode/parse
        // work runs concurrently per frontier, but the collected insertion order
        // stays deterministic — it is load-bearing: it becomes the binder's file
        // order, which fixes symbol-id allocation (docs/ARCHITECTURE-RETHINK.md
        // § 4 determinism hazards).
        val program = LinkedHashMap<String, String>() // path -> content
        val preParsed = HashMap<String, PreParsedFile>() // path -> crawl-time parse (INV.1(e))
        val unresolved = mutableListOf<Pair<String, String>>()
        val importEdges = mutableListOf<Pair<String, String>>()
        // (CHK.30) importer -> specifier -> resolved file, as the crawl's own
        // [ModuleResolver] answered it; carried into the core via [ParsedSource].
        val moduleResolutions = mutableMapOf<String, MutableMap<String, String>>()
        val seeds = rootFiles + typeEntries.filter { it !in rootFiles }
        FrontEnd.close(FrontEnd.CONFIG, feConfigT0)
        val feCrawlT0 = FrontEnd.t()
        runCompilerPipeline {
            val typeRoots = effectiveTypeRoots(config)
            crawlImportGraph(
                seeds, resolver, emitOptions, unresolved, importEdges, moduleResolutions,
                resolveReferenceTypes = { name ->
                    typeRoots.firstNotNullOfOrNull { resolveTypePackageInRoot(it, name, resolver) }
                },
            ).collect { f ->
                program[f.path] = f.content ?: ""
                f.preParsed?.let { preParsed[f.path] = it }
            }
        }
        FrontEnd.close(FrontEnd.CRAWL, feCrawlT0)

        // Feed the gathered file set through the shared compilation core, handing it
        // the crawl's parses so program files are not parsed a second time.
        val files = program.map { (name, content) -> SourceFileEntry(name, content) }
        // (CHK.29) Under node16/nodenext a file's MODULE FORMAT is a property of the
        // nearest enclosing `package.json`, which is not one of the program's inputs —
        // so it has to be read from the Vfs here, where there is one, rather than out of
        // the parsed source set the way the multi-file corpus path does. Zero cost under
        // every other module kind: the crawl is not consulted at all.
        val coreOptions =
            if (!emitOptions.effectiveModule.isNodeNext) emitOptions
            else emitOptions.copy(packageJsonTypes = packageScopesOf(program.keys))
        val parsed = ParsedSource(coreOptions, files, hasExplicitFilenames = true, preParsed = preParsed,
            moduleResolutions = moduleResolutions)
        // (INC.46) The fingerprint walk is armed around THIS compile and disarmed
        // again, and its answer is snapshotted immediately. The arming is a
        // process-global rather than a threaded parameter deliberately: the walk hooks
        // one fixed point deep inside `compileParsed` (right after the checker's
        // diagnostics are read) and threading a flag through four layers to reach it
        // would touch the whole compile path for a probe that is off in every build but
        // this API's. The scope of the compromise is stated rather than hidden: one
        // `build` is a synchronous whole-program compile, so a SECOND compile running
        // concurrently in the same process would also pay the walk. `Project` is a
        // single-threaded embedding API and does not do that.
        val expSigWas = ExportSignatures.enabled
        if (exportSignatures) { ExportSignatures.enabled = true; ExportSignatures.reset() }
        val result = try {
            TypeScriptCompiler().compileParsed(
                parsed, coreOptions, rootFiles.firstOrNull() ?: "input.ts", recheckOnly = recheckOnly,
                typeCapture = typeCapture,
                checkedSink = checkedSink,
                recheckHolder = recheckHolder,
            )
        } finally {
            ExportSignatures.enabled = expSigWas
        }
        val expSigs =
            if (exportSignatures) LinkedHashMap(ExportSignatures.fingerprints) else emptyMap()
        val expEscapes =
            if (exportSignatures) LinkedHashSet(ExportSignatures.whole) else emptySet<String>()
        // INV.7(d2): top-level declaration names per file (from the crawl parses)
        // for the shared-name full-rebuild bail.
        fun topLevelNames(path: String): List<String> =
            preParsed[path]?.sourceFile?.statements?.mapNotNull { st ->
                when (st) {
                    is InterfaceDeclaration -> st.name.text
                    is ClassDeclaration -> st.name?.text
                    is EnumDeclaration -> st.name.text
                    is TypeAliasDeclaration -> st.name.text
                    is FunctionDeclaration -> st.name?.text
                    is ModuleDeclaration -> (st.name as? Identifier)?.text
                    else -> null
                }
            } ?: emptyList()
        // INV.7(d1): module-ness per program file from the crawl parses — a
        // conservative SYNTACTIC approximation of the checker's isModuleFile
        // (top-level import/export forms only; wrapped dynamic imports read as
        // script → the watch loop's full-rebuild bail, safe but slower). A file
        // without a crawl parse (unreadable) is conservatively non-module.
        val moduleFiles = preParsed.keys.filterTo(HashSet()) { p ->
            preParsed[p]?.sourceFile?.statements?.any { st ->
                st is ImportDeclaration || st is ExportDeclaration || st is ExportAssignment ||
                    st is ImportEqualsDeclaration ||
                    (st as? VariableStatement)?.modifiers?.contains(ModifierFlag.Export) == true ||
                    (st as? FunctionDeclaration)?.modifiers?.contains(ModifierFlag.Export) == true ||
                    (st as? ClassDeclaration)?.modifiers?.contains(ModifierFlag.Export) == true ||
                    (st as? InterfaceDeclaration)?.modifiers?.contains(ModifierFlag.Export) == true ||
                    (st as? TypeAliasDeclaration)?.modifiers?.contains(ModifierFlag.Export) == true ||
                    (st as? EnumDeclaration)?.modifiers?.contains(ModifierFlag.Export) == true ||
                    (st as? ModuleDeclaration)?.modifiers?.contains(ModifierFlag.Export) == true
            } == true
        }

        // With outDir withheld above, the core's same-directory overwrite check (TS5055,
        // gated on `outDir == null`) can fire even though outputs actually go to outDir.
        val compilerDiagnostics =
            if (config.options.outDir != null) result.diagnostics.filter { it.code != 5055 }
            else result.diagnostics

        val written = if (noEmit || config.options.noEmit) emptyList()
        else writeOutputs(result, config, program.keys)

        // INV.7(d2): shared-name files — module files whose top-level names collide
        // with lib globals or with script-file top-level names (both merge classes
        // survive the INV.3(d) retire and have program-wide reach).
        val scriptTopNames = program.keys.filter { it !in moduleFiles }
            .flatMapTo(HashSet()) { topLevelNames(it) }
        val sharedNameFiles = moduleFiles.filterTo(HashSet()) { p2 ->
            topLevelNames(p2).any { it in Checker.KNOWN_GLOBALS || it in scriptTopNames }
        }
        // (INC.17) A project-level answer is not the checker's answer: the config's
        // own rows and the type-acquisition rows precede it, and TS5055 is filtered
        // when an outDir was withheld. Wrapping the core handle here is what makes a
        // recheck substitutable for a `build` — an unwrapped one would silently drop
        // a malformed-tsconfig error the moment a host started using it.
        recheckHolder?.recheck?.let { inner ->
            recheckHolder.recheck = ProjectRecheck(
                inner = inner,
                prefix = config.diagnostics + typeDiagnostics,
                dropOverwriteCheck = config.options.outDir != null,
            )
        }
        return Result(
            configPath = configPath,
            rootFiles = rootFiles,
            programFiles = program.keys.toList(),
            // Config-load errors (unreadable/malformed tsconfig, missing `extends`) first,
            // then type-acquisition errors (TS2688), then the compiler's own diagnostics.
            diagnostics = config.diagnostics + typeDiagnostics + compilerDiagnostics,
            unresolved = unresolved.distinct(),
            importEdges = importEdges,
            moduleFiles = moduleFiles,
            sharedNameFiles = sharedNameFiles,
            written = written,
            capturedTypes = result.capturedTypes,
            capturedDefinitions = result.capturedDefinitions,
            capturedMembers = result.capturedMembers,
            capturedScopes = result.capturedScopes,
            capturedSignatures = result.capturedSignatures,
            exportSignatures = expSigs,
            exportSignatureEscapes = expEscapes,
        )
    }

    /**
     * (INC.17) The PROJECT's live program: the core's [ProgramRecheck] plus the two
     * things `build` adds to a compile's diagnostics before returning them — the
     * config's own rows (an unreadable or malformed `tsconfig.json`, a bad
     * `extends`) and the type-acquisition rows, both of which precede the
     * compiler's; and the TS5055 same-directory-overwrite filter that a withheld
     * `outDir` would otherwise make spurious.
     *
     * The prefix is a property of the PROGRAM, not of the partition, so it is
     * captured once at the build that produced the handle and prepended to every
     * later answer — exactly as the build prepends it.
     */
    private class ProjectRecheck(
        private val inner: ProgramRecheck,
        private val prefix: List<Diagnostic>,
        private val dropOverwriteCheck: Boolean,
    ) : ProgramRecheck {

        override val walkedFiles: Set<String> get() = inner.walkedFiles
        override val replayedPasses: Set<String> get() = inner.replayedPasses

        override fun recheck(files: Set<String>, capture: TypeCaptureRequest?): RecheckAnswer {
            val answer = inner.recheck(files, capture)
            val rows =
                if (dropOverwriteCheck) answer.diagnostics.filter { it.code != 5055 }
                else answer.diagnostics
            return RecheckAnswer(
                diagnostics = prefix + rows,
                capturedTypes = answer.capturedTypes,
                capturedDefinitions = answer.capturedDefinitions,
                capturedMembers = answer.capturedMembers,
                capturedScopes = answer.capturedScopes,
                capturedSignatures = answer.capturedSignatures,
            )
        }
    }

    /**
     * A crawled file: [content] null when unreadable; [preParsed] the crawl-time
     * parse (null for unreadable and `.json` files), whose tree supplies the
     * [specifiers] the graph walk follows and rides into the compilation core
     * via [ParsedSource.preParsed] (INV.1(e)).
     */
    private class CrawledFile(
        val path: String, val content: String?, val preParsed: PreParsedFile?,
        /** (FRONT.1): this file's OWN read and pre-parse nanos, carried back so the
         *  SINGLE-THREADED collector can sum them without racing the workers. */
        val readNanos: Long = 0, val parseNanos: Long = 0,
        /** (WARM.19): this file's amplifier receipts, carried back for the same
         *  reason — `FrontEnd.parseAmpSink` is folded single-threaded. */
        val ampBase: Long = 0, val ampSink: Long = 0,
        /** (WARM.19): whether [preParsed] came from [CrawlParseCache] rather than a parse. */
        val cacheHit: Boolean = false,
        /** (INC.64): whether this file was handed to [Dispatchers.Default] for a parse.
         *  Carried back rather than counted in place for [readNanos]'s reason — the
         *  fold is single-threaded and a `++` from the workers would race. */
        val parseDispatched: Boolean = false,
    ) {
        val specifiers: Set<String> = preParsed?.sourceFile?.moduleSpecifiers?.toSet() ?: emptySet()

        /** M4.8: `/// <reference path>` targets — resolved relative to this file. */
        val referencedPaths: List<String> = preParsed?.sourceFile?.referencedPaths ?: emptyList()

        /** M4.8: `/// <reference types>` targets — resolved through the type roots. */
        val referencedTypes: List<String> = preParsed?.sourceFile?.referencedTypes ?: emptyList()
    }

    /**
     * The import-graph crawl as a cold [Flow] of [CrawledFile] in BFS
     * discovery order — seeds first (in seed order, read unconditionally: an
     * unreadable seed becomes ""), then each file's resolved imports
     * breadth-first (an unreadable discovered file is skipped, and stays
     * re-probeable by a later frontier). Unresolvable bare/relative specifiers
     * accumulate into [unresolved] as (importer, specifier).
     *
     * INV.1(b): each frontier's files are read+decoded / specifier-parsed
     * CONCURRENTLY ([readAndScanBatch]), but specifier RESOLUTION and emission
     * stay sequential per frontier (a frontier-level barrier). The EMISSION
     * order is the program file order the binder will see, and binder file
     * order fixes global symbol-id allocation (the documented ~350-test
     * reshuffle on drift) — it must stay deterministic (first-discovery order),
     * never completion-ordered. Observable outputs are identical to the
     * sequential crawl for a Vfs that is static during the crawl; only read
     * COUNTS can differ (a multiply-discovered unreadable path is probed once
     * per frontier here, once per discovery there).
     */
    private fun crawlImportGraph(
        seeds: List<String>,
        resolver: ModuleResolver,
        options: CompilerOptions,
        unresolved: MutableList<Pair<String, String>>,
        importEdges: MutableList<Pair<String, String>> = mutableListOf(),
        // (CHK.30): every specifier this crawl RESOLVED, keyed importer -> specifier
        // -> resolved file. The checker re-derives the same mapping with a string
        // matcher over the program's file names, which cannot express a bare package
        // specifier at all; handing it the answer the real resolver already computed
        // is what stops an imported type from silently degrading to `any`.
        moduleResolutions: MutableMap<String, MutableMap<String, String>> = mutableMapOf(),
        // M4.8: `/// <reference types="pkg">` needs the tsconfig's type roots, which
        // the crawl has no other access to; the caller supplies the lookup.
        resolveReferenceTypes: (String) -> String? = { null },
    ): Flow<CrawledFile> = flow {
        val loaded = HashSet<String>() // paths already emitted (the dedup set)
        // Frontier 0: the seeds — read per occurrence (duplicate-seed re-reads
        // preserved); an unreadable seed still enters the program as "".
        var frontier = readAndScanBatch(seeds, options)
        for (f in frontier) {
            loaded.add(f.path)
            emit(f)
        }
        while (frontier.isNotEmpty()) {
            // Resolve specifiers SEQUENTIALLY in frontier order: the resolver is
            // single-threaded state, and both the (importer, specifier) attribution
            // order in [unresolved] and the first-discovery emission position are
            // observable — this loop is what fixes them deterministically.
            val discovered = ArrayList<String>()
            val pending = HashSet<String>()
            val feResolveT0 = FrontEnd.t()
            for (f in frontier) {
                for (spec in f.specifiers) {
                    val resolved = resolver.resolve(spec, f.path)
                    if (resolved == null) {
                        if (PathUtil.isBare(spec) || PathUtil.isRelative(spec)) unresolved.add(f.path to spec)
                        continue
                    }
                    importEdges.add(f.path to resolved)
                    moduleResolutions.getOrPut(f.path) { mutableMapOf() }[spec] = resolved
                    if (resolved !in loaded && pending.add(resolved)) discovered.add(resolved)
                }
                // M4.8: `/// <reference path|types>` targets join the program too
                // (tsc `processReferencedFiles`). An unresolvable one is left to the
                // checker's TS6053, which asks whether the target is in the program —
                // so it goes silent exactly when this succeeds.
                for (ref in f.referencedPaths) {
                    val resolved = resolveReferencePath(ref, f.path) ?: continue
                    importEdges.add(f.path to resolved)
                    if (resolved !in loaded && pending.add(resolved)) discovered.add(resolved)
                }
                for (ref in f.referencedTypes) {
                    val resolved = resolveReferenceTypes(ref) ?: continue
                    importEdges.add(f.path to resolved)
                    if (resolved !in loaded && pending.add(resolved)) discovered.add(resolved)
                }
            }
            FrontEnd.close(FrontEnd.CRAWL_RESOLVE, feResolveT0)
            // Read+parse the discoveries concurrently, then emit in DISCOVERY
            // order (the emission-order contract above). An unreadable discovered
            // file is dropped here and stays out of `loaded`, so a later frontier
            // may re-probe it — matching the sequential crawl.
            frontier = readAndScanBatch(discovered, options).filter { it.content != null }
            for (f in frontier) {
                loaded.add(f.path)
                emit(f)
            }
        }
    }

    /**
     * INV.1(b): reads and parses [paths] concurrently — read +
     * UTF-8→UTF-16 decode on [pipelineIoDispatcher], the parse on
     * [Dispatchers.Default], at most [FRONTEND_CONCURRENCY] files in flight
     * (bounded [flatMapMerge] = the owner's measured pipeline shape,
     * docs/ARCHITECTURE-RETHINK.md § 4) — and returns results in INPUT order,
     * one entry per occurrence. Parse concurrency is safe: the Parser holds
     * per-instance state only (no top-level/companion mutable state; the
     * TypeParameter `internSalt` stamp is per-file pure — audited for this step).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun readAndScanBatch(paths: List<String>, options: CompilerOptions): List<CrawledFile> {
        if (paths.isEmpty()) return emptyList()
        val byPath = paths.asFlow()
            .flatMapMerge(concurrency = FRONTEND_CONCURRENCY) { path ->
                flow {
                    val t0 = FrontEnd.t()
                    // (INC.56) A read that CANNOT BLOCK is not worth a thread handoff.
                    // The hop below exists so a blocking read cannot starve the
                    // CPU-sized pool the parses run on; content the Vfs already holds
                    // in memory — an unsaved editor buffer, or a file an IntelliJ-class
                    // host has promised is unchanged — costs nothing to hand back, and
                    // on an application-shaped project the handoff is what a per-file
                    // read costs. Null is always a legal answer and is what every
                    // ordinary filesystem gives, so the shipped CLI path is unchanged.
                    val resident = vfs.readTextIfResident(path)
                    val content =
                        resident ?: withContext(pipelineIoDispatcher) { vfs.readText(path) }
                    val t1 = FrontEnd.t()
                    // (WARM.19) round 871 — the amplifier rides INSIDE this span
                    // and on the same dispatcher, so `FrontEnd.CRAWL`'s wall is
                    // `floor + (1 + r) * C` and two values of `r` cancel the
                    // floor. Its receipts are carried back on the element and
                    // folded single-threaded below: a `+=` from here races.
                    var ampBase = 0L
                    var ampSink = 0L
                    var cacheHit = false
                    var parseDispatched = false
                    val preParsed =
                        if (content == null) null
                        else {
                            // (WARM.19): the cross-request parse cache. `lookup`
                            // is READ-ONLY, which is what makes it safe to call
                            // from these concurrent workers; the matching
                            // `store` runs in the single-threaded fold below.
                            //
                            // (INC.64): the flags and the lookup run HERE, on the
                            // thread the read left us on, and only a MISS hops to
                            // [Dispatchers.Default]. A hop is a thread handoff, and
                            // on a warm incremental build EVERY file is a cache hit,
                            // so the unconditional hop was 2 x files handoffs to
                            // schedule ~1 us of map probe: measured over these 2,401
                            // files, the shipped two-hop shape is 32.1 ms against
                            // 18.5 for one hop and 14.4 for a plain sequential read.
                            // The COLD crawl is untouched — a miss still parses off
                            // the IO dispatcher, which is the whole point of the hop
                            // (docs/ARCHITECTURE-RETHINK.md § 4).
                            val flags = computeParserFlags(path, content, options)
                            val cached =
                                if (path.endsWith(".json")) null
                                else CrawlParseCache.lookup(path, content, flags)
                            val pp = if (cached != null) {
                                cacheHit = true
                                cached
                            } else {
                                parseDispatched = true
                                withContext(Dispatchers.Default) {
                                    parseForCrawl(path, content, options, flags)
                                }
                            }
                            if (pp != null) {
                                ampBase = pp.sourceFile.statements.size.toLong()
                                // The amplifier deliberately does NOT consult the
                                // cache: it must price a real parse, on the
                                // cached binary too, which is what makes the
                                // before/after crawl row a controlled comparison.
                                // It stays on [Dispatchers.Default] because it IS a
                                // real parse — and it is off (`parseAmp == 0`) in
                                // every production build, so the hop it needs is not
                                // one the shipped path pays.
                                if (FrontEnd.parseAmp > 0) withContext(Dispatchers.Default) {
                                    repeat(FrontEnd.parseAmp) {
                                        ampSink += parseForCrawl(path, content, options, flags)
                                            ?.sourceFile?.statements?.size?.toLong() ?: 0L
                                    }
                                }
                            }
                            pp
                        }
                    val t2 = FrontEnd.t()
                    emit(
                        CrawledFile(
                            path, content, preParsed, t1 - t0, t2 - t1,
                            ampBase, ampSink, cacheHit, parseDispatched,
                        )
                    )
                }
            }
            .toList()
        // Single-threaded: the concurrent flow is fully drained by now, which is
        // the ONLY point at which [CrawlParseCache] may be written (round 825 —
        // a plain HashMap write from N workers is a race with no exception to
        // find it by). Unconditional, unlike the census below it: the cache is
        // production behaviour, not instrumentation.
        for (f in byPath) {
            val pp = f.preParsed
            if (pp != null) CrawlParseCache.store(f.path, pp)
            // (INC.56) The ONE point at which a Vfs may retain what this batch read,
            // for exactly [CrawlParseCache.store]'s reason: the concurrent flow above
            // is drained by now. Every default implementation ignores it.
            if (f.content != null) vfs.retainRead(f.path, f.content)
            if (f.content != null && !f.path.endsWith(".json")) {
                if (f.cacheHit) CrawlParseCache.hits++ else CrawlParseCache.misses++
            }
            if (f.parseDispatched) CrawlParseCache.parseDispatches++
        }
        if (FrontEnd.mode == FrontEnd.ON) {
            for (f in byPath) {
                FrontEnd.addCrawlFile(f.readNanos, f.parseNanos, f.content?.length ?: 0)
                FrontEnd.addParseAmp(f.ampBase, f.ampSink)
            }
        }
        val indexed = byPath.associateBy { it.path }
        return paths.map { indexed.getValue(it) }
    }

    private fun resolveConfigPath(projectPath: String): String {
        val p = PathUtil.normalize(projectPath)
        return if (vfs.isDirectory(p)) "$p/tsconfig.json" else p
    }

    // --- file discovery (glob) --------------------------------------------------

    /**
     * (CHK.29) The package SCOPES covering [fileNames]: every ancestor directory of a
     * program file that carries a `package.json`, mapped to whether it declares
     * `"type": "module"`. Consumed by `isESModuleFormat` through
     * [CompilerOptions.packageJsonTypes]; see [packageScopeIsModule] for the lookup and
     * for why a manifest with no `"type"` is present with `false` rather than absent.
     *
     * **Read through the [Vfs], never a direct filesystem call** — that is what puts the
     * language service's in-memory overlay on the same path, so an edit that adds or
     * changes a `package.json` is seen by the very next build.
     *
     * The walk is memoized on DIRECTORIES, not files, because the answer is a property of
     * the directory: each ancestor is probed at most once per build, and a directory whose
     * scope has already been located terminates every later walk that reaches it (its own
     * ancestors cannot be nearer to anything below it than it is). So the cost is bounded
     * by the number of distinct directories in the program, not by its file count — and it
     * is exactly zero for every module kind that is not node16/nodenext, where the caller
     * does not run this at all.
     */
    private fun packageScopesOf(fileNames: Collection<String>): Map<String, Boolean> {
        val scopes = HashMap<String, Boolean>()
        val probed = HashSet<String>()
        for (fileName in fileNames) {
            var dir = PathUtil.dirname(fileName)
            while (true) {
                // Already probed => so are all of its ancestors, or a nearer scope was
                // found here and is already recorded. Either way this walk is done.
                if (!probed.add(dir)) break
                val manifest = vfs.readText(if (dir == "/") "/package.json" else "$dir/package.json")
                if (manifest != null) {
                    scopes[dir] = packageJsonDeclaresModule(manifest)
                    break
                }
                val parent = PathUtil.dirname(dir)
                if (parent == dir) break
                dir = parent
            }
        }
        return scopes
    }

    private fun collectRootFiles(config: LoadedTsConfig, supportedExt: List<String>): List<String> {
        val result = LinkedHashSet<String>()
        // Explicit `files` are always included verbatim.
        for (f in config.files) if (vfs.exists(f)) result.add(PathUtil.normalize(f))

        if (config.include.isNotEmpty()) {
            // (INC.78) The matcher, not a bare Regex: this asks every candidate against
            // every pattern on EVERY build, i.e. on every keystroke of a language-service
            // host, and the regex form was 1.9-3.4 us per candidate. [GlobMatcher] keeps
            // that regex as its definition and reaches the same answer without running it
            // for the pattern shapes tsconfigs actually use.
            val includeGlobs = config.include.map { GlobMatcher.compile(PathUtil.join(config.configDir, it), supportedExt) }
            val excludeGlobs = config.exclude.map { GlobMatcher.compile(PathUtil.join(config.configDir, it), supportedExt) }
            val feWalkT0 = FrontEnd.t()
            walk(config.configDir) { path ->
                if (matchedExtension(path, supportedExt) == null) return@walk
                FrontEnd.globCandidates++
                val feMatchT0 = FrontEnd.t()
                if (excludeGlobs.none { it.matches(path) } && includeGlobs.any { it.matches(path) }) {
                    result.add(path)
                }
                FrontEnd.close(FrontEnd.CFG_MATCH, feMatchT0)
            }
            FrontEnd.close(FrontEnd.CFG_WALK, feWalkT0)
        }
        return result.toList()
    }

    private fun matchedExtension(path: String, supportedExt: List<String>): String? =
        supportedExt.firstOrNull { path.endsWith(it) }

    // --- @types acquisition (tsconfig `types` / `typeRoots`) ---------------------

    /**
     * Resolves the automatic type-library inclusions, mirroring tsc: the included
     * package set is `types` when specified (an EMPTY list disables inclusion
     * entirely), else every package discovered in the effective type roots. Each
     * package resolves to its declaration entry via
     * [ModuleResolver.resolveTypeRootPackage]; a name explicitly listed in `types`
     * that resolves in no type root reports TS2688 (auto-discovery includes only
     * what exists, so it never does).
     */
    /**
     * Resolve a `/// <reference path="…" />` target against the referencing file
     * (M4.8, tsc `resolveTripleslashReference`): the value is a PATH relative to
     * that file's directory, never a module specifier — resolving it through
     * [ModuleResolver] treats `path="globals.d.ts"` as a bare package and fails,
     * which is why `@types/node` contributed only its entry file.
     *
     * tsc uses the path as given; the extension probes cover the forms that
     * appear in practice (an extensionless target, and a `.js` target that names
     * its declaration sibling). Returns null when nothing exists — the checker's
     * TS6053 owns the reporting.
     */
    private fun resolveReferencePath(spec: String, fromFile: String): String? {
        val base = if (PathUtil.isAbsolute(spec)) spec
        else PathUtil.normalize(PathUtil.join(PathUtil.dirname(fromFile), spec))
        if (vfs.exists(base) && !vfs.isDirectory(base)) return base
        val probes = when {
            base.endsWith(".js") -> listOf(base.dropLast(3) + ".d.ts", base.dropLast(3) + ".ts")
            PathUtil.extname(base).isEmpty() -> listOf("$base.d.ts", "$base.ts", "$base.tsx")
            else -> emptyList()
        }
        return probes.firstOrNull { vfs.exists(it) && !vfs.isDirectory(it) }
    }

    private fun collectTypeRootEntries(
        config: LoadedTsConfig,
        resolver: ModuleResolver,
        diagnostics: MutableList<Diagnostic>,
    ): List<String> {
        val requested = config.options.types
        if (requested != null && requested.isEmpty()) return emptyList()
        val typeRoots = effectiveTypeRoots(config)
        val entries = LinkedHashSet<String>()
        if (requested != null) {
            for (name in requested) {
                val entry = typeRoots.firstNotNullOfOrNull { resolveTypePackageInRoot(it, name, resolver) }
                if (entry != null) entries.add(entry)
                else diagnostics.add(
                    Diagnostic(
                        message = "Cannot find type definition file for '$name'.",
                        category = DiagnosticCategory.Error,
                        code = 2688,
                    )
                )
            }
        } else {
            for (root in typeRoots) {
                // sorted: deterministic program order. (INC.60) one listing per
                // directory rather than a listing plus a probe per entry.
                for (child in vfs.listEntries(root).sortedBy { it.path }) {
                    if (!child.isDirectory) continue
                    val base = PathUtil.basename(child.path)
                    if (base.startsWith(".")) continue
                    if (base.startsWith("@")) {
                        // A scope directory inside a type root contributes its subdirectories.
                        for (scoped in vfs.listEntries(child.path).sortedBy { it.path }) {
                            if (!scoped.isDirectory || PathUtil.basename(scoped.path).startsWith(".")) continue
                            resolver.resolveTypeRootPackage(scoped.path)?.let { entries.add(it) }
                        }
                    } else {
                        resolver.resolveTypeRootPackage(child.path)?.let { entries.add(it) }
                    }
                }
            }
        }
        return entries.toList()
    }

    /**
     * The directories scanned for type packages: `typeRoots` (resolved against the
     * config dir) when specified, else every `<ancestor>/node_modules/@types`
     * walking up from the config dir (tsc's default).
     */
    private fun effectiveTypeRoots(config: LoadedTsConfig): List<String> {
        config.options.typeRoots?.let { roots ->
            return roots.map {
                if (PathUtil.isAbsolute(it)) PathUtil.normalize(it) else PathUtil.join(config.configDir, it)
            }.filter { vfs.isDirectory(it) }
        }
        val result = mutableListOf<String>()
        var dir = config.configDir
        while (true) {
            val candidate = "$dir/node_modules/@types"
            if (vfs.isDirectory(candidate)) result.add(candidate)
            val parent = PathUtil.dirname(dir)
            if (parent == dir || parent.isEmpty()) break
            dir = parent
        }
        return result
    }

    /** Probes `root/<name>`, plus the DefinitelyTyped `scope__name` mangling for a scoped [name]. */
    private fun resolveTypePackageInRoot(root: String, name: String, resolver: ModuleResolver): String? {
        resolver.resolveTypeRootPackage(PathUtil.join(root, name))?.let { return it }
        if (name.startsWith("@") && name.contains('/')) {
            val mangled = name.substring(1).replace("/", "__")
            resolver.resolveTypeRootPackage(PathUtil.join(root, mangled))?.let { return it }
        }
        return null
    }

    /**
     * Recursively walks [dir], invoking [onFile] for each file; prunes obvious heavy dirs.
     *
     * SORTED, and that is load-bearing (round 776): this walk fixes the ROOT-FILE order of
     * the whole program, and `vfs.list` hands back raw `readdir` order — a hash order that
     * is a property of the FILESYSTEM, not of the project. Program order decides which file
     * first touches a shared type node, hence the INV.5(c) cacheable-vs-bypassed split:
     * measured on the compiler profile, three orders of the SAME 78 files give
     * `typeNode.bypassed` 104,162 / 103,644 / 103,272 and `mapped.keyed` 25,583 / 25,378 /
     * 25,688 while the AST (856,962 nodes) and all 46 diagnostics stay bit-identical. So an
     * unsorted crawl makes the COST.1 counters a property of the box, which is exactly what
     * that gate assumes they are not. Directories are pushed in REVERSE so the LIFO stack
     * pops them alphabetically — a plain depth-first alphabetical walk.
     */
    private fun walk(dir: String, onFile: (String) -> Unit) {
        val pruned = setOf("node_modules", ".git", "bower_components", "jspm_packages")
        val stack = ArrayDeque(listOf(dir))
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val dirs = mutableListOf<String>()
            FrontEnd.globDirs++
            val feListT0 = FrontEnd.t()
            // (INC.60) ONE listing that already answers "directory?" per entry. The
            // sort is round 776's and must stay: it fixes the program's ROOT-FILE
            // order, and `listEntries` promises no order of its own.
            val entries = vfs.listEntries(d).sortedBy { it.path }
            FrontEnd.close(FrontEnd.CFG_LIST, feListT0)
            for (entry in entries) {
                FrontEnd.globEntries++
                if (entry.isDirectory) {
                    if (PathUtil.basename(entry.path) !in pruned) dirs.add(entry.path)
                } else {
                    onFile(entry.path)
                }
            }
            for (i in dirs.indices.reversed()) stack.addLast(dirs[i])
        }
    }

    // --- crawl parse (specifier extraction + INV.1(e) pre-parse) -----------------

    /**
     * Parses [source] for the crawl. The parse serves two purposes: the tree
     * records every module specifier the graph walk follows
     * ([SourceFile.moduleSpecifiers], tsc's `SourceFile.imports`), and the whole
     * parse rides into the compilation core as a [PreParsedFile] so program files
     * are not parsed a second time (INV.1(e)). A text scan is not usable for
     * specifier extraction: real-world sources (e.g. tsc's own) contain
     * `import ... from "..."` shapes inside string literals, comments, and regex
     * literals, which a regex extraction reports as garbage unresolved imports
     * and can even pull junk files into the program.
     *
     * The parse uses the option-derived flags from the SAME shared helper the
     * core's parse sites use ([computeParserFlags] over the resolved tsconfig
     * options), so the core's content+flags reuse gate provably matches — and the
     * graph walk itself sees the option-faithful tree. `.json` files are not
     * parsed (the core never parses them either — JSON is re-emitted verbatim).
     */
    private fun parseForCrawl(
        fileName: String,
        source: String,
        options: CompilerOptions,
        // (WARM.19): the caller has usually computed these already, for the
        // cache lookup. Recomputing them is pure and cheap, but passing them
        // keeps the ONE definition of a file's flags at the call site, so the
        // cache key and the parse can never be computed from different inputs.
        precomputedFlags: ParserFlags? = null,
    ): PreParsedFile? {
        if (fileName.endsWith(".json")) return null
        val flags = precomputedFlags ?: computeParserFlags(fileName, source, options)
        val parser = Parser(
            source, fileName, forceJsx = flags.forceJsx, topLevelAwait = flags.topLevelAwait,
            needsJsxFlag = flags.needsJsxFlag, noImplicitAny = flags.noImplicitAny,
        )
        val sourceFile = parser.parse()
        return PreParsedFile(source, flags, sourceFile, parser.getDiagnostics())
    }

    // --- output emission --------------------------------------------------------

    private fun writeOutputs(
        result: CompilationResult,
        config: LoadedTsConfig,
        programFiles: Set<String>,
    ): List<Pair<String, Int>> {
        val rootDir = config.options.rootDir ?: commonSourceDir(programFiles) ?: config.configDir
        val outDir = config.options.outDir
        // [build] requested full input-derived output names (see `emitOptions` there); the
        // core spells them by swapping extensions with String.replace over the whole path.
        // Reproduce that exact spelling per program input so each output correlates back to
        // its INPUT path, and derive the on-disk target from the input (rootDir-relative,
        // extension swapped on the final segment only). Basenames are NOT a usable key —
        // same-named files in different directories collide.
        val jsxPreserve = config.options.jsx?.lowercase() == "preserve"
        val inputByOutputName = programFiles.associateBy { coreOutputName(it, jsxPreserve) }
        val written = mutableListOf<Pair<String, Int>>()
        for ((name, content) in result.jsOutputs) {
            // Only emit outputs for project files (skip anything under node_modules).
            if (name.contains("/node_modules/")) continue
            val input = inputByOutputName[name]
            var rel =
                if (input != null) swapOutputExtension(PathUtil.relativeTo(rootDir, input), jsxPreserve)
                else PathUtil.relativeTo(rootDir, name) // e.g. an outFile bundle name
            // Not under rootDir (relativeTo fell back to the path itself): never write
            // outside outDir — drop to the basename.
            if (rel.isEmpty() || PathUtil.isAbsolute(rel)) rel = PathUtil.basename(name)
            val target = if (outDir != null) PathUtil.join(outDir, rel) else PathUtil.normalize(name)
            // tsc terminates every emitted file with a newline; the shared emitter (whose
            // output the corpus baselines compare without one) does not, so append it at
            // the disk-write layer only.
            val payload = if (content.isEmpty() || content.endsWith("\n")) content else content + "\n"
            vfs.writeText(target, payload)
            written.add(target to payload.length)
        }
        return written
    }

    /** The output name [TypeScriptCompiler.compileParsed] gives [input] under `fullEmitPaths` (its all-occurrence extension replace). */
    private fun coreOutputName(input: String, jsxPreserve: Boolean): String {
        val jsxExt = if (jsxPreserve) ".jsx" else ".js"
        return input
            .replace(".tsx", jsxExt)
            .replace(".jsx", jsxExt)
            .replace(".mts", ".mjs")
            .replace(".cts", ".cjs")
            .replace(".ts", ".js")
    }

    /** Swaps only a trailing source extension of [path] for its output extension. */
    private fun swapOutputExtension(path: String, jsxPreserve: Boolean): String {
        val jsxExt = if (jsxPreserve) ".jsx" else ".js"
        return when {
            path.endsWith(".tsx") -> path.removeSuffix(".tsx") + jsxExt
            path.endsWith(".jsx") -> path.removeSuffix(".jsx") + jsxExt
            path.endsWith(".mts") -> path.removeSuffix(".mts") + ".mjs"
            path.endsWith(".cts") -> path.removeSuffix(".cts") + ".cjs"
            path.endsWith(".ts") -> path.removeSuffix(".ts") + ".js"
            else -> path
        }
    }

    /**
     * The deepest directory that is a prefix of every emittable program file — the
     * rootDir fallback, mirroring tsc's commonSourceDirectory (computed over emitted
     * files only, so declaration and json inputs don't shift it).
     */
    private fun commonSourceDir(files: Set<String>): String? {
        val dirs = files.filterNot {
            it.contains("/node_modules/") || it.endsWith(".json") ||
                it.endsWith(".d.ts") || it.endsWith(".d.mts") || it.endsWith(".d.cts")
        }.map { PathUtil.dirname(it) }
        if (dirs.isEmpty()) return null
        var common = dirs.first().split('/')
        for (d in dirs.drop(1)) {
            val segs = d.split('/')
            var k = 0
            while (k < common.size && k < segs.size && common[k] == segs[k]) k++
            common = common.subList(0, k)
        }
        return common.joinToString("/").ifEmpty { "/" }
    }
}
