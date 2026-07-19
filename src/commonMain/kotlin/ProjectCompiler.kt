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
        /** Output files written to disk as (path, byteLength). Empty when noEmit. */
        val written: List<Pair<String, Int>>,
    ) {
        val errorCount: Int get() = diagnostics.count { it.category == DiagnosticCategory.Error }
    }

    /** Source extensions considered "root" inputs (declarations included; emit skips them). */
    private val rootExtensions = listOf(".ts", ".tsx", ".mts", ".cts")

    /**
     * @param projectPath a directory containing `tsconfig.json`, or a path to a tsconfig file.
     * @param noEmit when true, type-check only — do not write outputs.
     */
    fun build(projectPath: String, noEmit: Boolean = false, recheckOnly: Set<String>? = null): Result {
        // Absolutize first: glob regexes, module resolution, and output mapping all
        // assume absolute paths (a relative `.` would produce `./src/**` patterns that
        // never match the absolute paths the Vfs walk yields).
        val absPath = vfs.resolveAbsolute(projectPath)
        // A bare SOURCE-file argument (`xtsc foo.ts`) is compiled as a single-file program
        // with default options — like `tsc foo.ts`. It must NOT be loaded as a tsconfig:
        // parsing a `.ts` as JSON yields a garbage config, and downstream a corrupt lib
        // binderResult (its `sourceFile.text` no longer matching its statement positions)
        // that crashes the checker with a StringIndexOutOfBounds.
        val isBareSourceFile = !vfs.isDirectory(absPath) &&
            !absPath.endsWith(".json") && vfs.exists(absPath)
        val configPath = if (isBareSourceFile) absPath else resolveConfigPath(absPath)
        val config = if (isBareSourceFile) {
            LoadedTsConfig(
                options = CompilerOptions(),
                configDir = PathUtil.dirname(absPath),
                include = emptyList(),
                exclude = emptyList(),
                files = listOf(PathUtil.normalize(absPath)),
                customConditions = emptyList(),
            )
        } else {
            TsConfigLoader(vfs).load(configPath)
        }
        val resolver = ModuleResolver(vfs, config.customConditions)

        val allowJs = config.options.allowJs
        val supportedExt = if (allowJs) rootExtensions + listOf(".js", ".jsx", ".mjs", ".cjs") else rootExtensions

        val rootFiles = collectRootFiles(config, supportedExt)

        // Automatic type-library inclusion (tsconfig `types` / `typeRoots`): the
        // resolved entries join the graph walk as additional seeds so their own
        // imports and `/// <reference types>` directives are followed too.
        val typeDiagnostics = mutableListOf<Diagnostic>()
        val typeEntries = collectTypeRootEntries(config, resolver, typeDiagnostics)

        // The options the compilation core receives. The core's output naming serves
        // baseline comparison: by default it strips names to basenames (which collide
        // across subdirectories), and its own outDir remap is keyed on the inputs'
        // common ancestor rather than rootDir. Withhold outDir and request full
        // input-derived paths instead (`fullEmitPaths` affects naming only); all
        // outDir/rootDir mapping happens in [writeOutputs] from the input paths.
        // Hoisted above the crawl (INV.1(e)): the crawl parses each file with the
        // parser flags computed from THESE options, so the core's pre-parse reuse
        // gate (content + flags equality) matches.
        val emitOptions = config.options.copy(outDir = null, fullEmitPaths = true)

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
        val seeds = rootFiles + typeEntries.filter { it !in rootFiles }
        runCompilerPipeline {
            crawlImportGraph(seeds, resolver, emitOptions, unresolved, importEdges).collect { f ->
                program[f.path] = f.content ?: ""
                f.preParsed?.let { preParsed[f.path] = it }
            }
        }

        // Feed the gathered file set through the shared compilation core, handing it
        // the crawl's parses so program files are not parsed a second time.
        val files = program.map { (name, content) -> SourceFileEntry(name, content) }
        val parsed = ParsedSource(emitOptions, files, hasExplicitFilenames = true, preParsed = preParsed)
        val result = TypeScriptCompiler().compileParsed(
            parsed, emitOptions, rootFiles.firstOrNull() ?: "input.ts", recheckOnly = recheckOnly,
        )
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
            written = written,
        )
    }

    /**
     * A crawled file: [content] null when unreadable; [preParsed] the crawl-time
     * parse (null for unreadable and `.json` files), whose tree supplies the
     * [specifiers] the graph walk follows and rides into the compilation core
     * via [ParsedSource.preParsed] (INV.1(e)).
     */
    private class CrawledFile(val path: String, val content: String?, val preParsed: PreParsedFile?) {
        val specifiers: Set<String> = preParsed?.sourceFile?.moduleSpecifiers?.toSet() ?: emptySet()
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
            for (f in frontier) {
                for (spec in f.specifiers) {
                    val resolved = resolver.resolve(spec, f.path)
                    if (resolved == null) {
                        if (PathUtil.isBare(spec) || PathUtil.isRelative(spec)) unresolved.add(f.path to spec)
                        continue
                    }
                    importEdges.add(f.path to resolved)
                    if (resolved !in loaded && pending.add(resolved)) discovered.add(resolved)
                }
            }
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
                    val content = withContext(pipelineIoDispatcher) { vfs.readText(path) }
                    val preParsed =
                        if (content == null) null
                        else withContext(Dispatchers.Default) { parseForCrawl(path, content, options) }
                    emit(CrawledFile(path, content, preParsed))
                }
            }
            .toList()
            .associateBy { it.path }
        return paths.map { byPath.getValue(it) }
    }

    private fun resolveConfigPath(projectPath: String): String {
        val p = PathUtil.normalize(projectPath)
        return if (vfs.isDirectory(p)) "$p/tsconfig.json" else p
    }

    // --- file discovery (glob) --------------------------------------------------

    private fun collectRootFiles(config: LoadedTsConfig, supportedExt: List<String>): List<String> {
        val result = LinkedHashSet<String>()
        // Explicit `files` are always included verbatim.
        for (f in config.files) if (vfs.exists(f)) result.add(PathUtil.normalize(f))

        if (config.include.isNotEmpty()) {
            val includeRegexes = config.include.map { globToRegex(PathUtil.join(config.configDir, it), supportedExt) }
            val excludeRegexes = config.exclude.map { globToRegex(PathUtil.join(config.configDir, it), supportedExt) }
            walk(config.configDir) { path ->
                if (matchedExtension(path, supportedExt) == null) return@walk
                if (excludeRegexes.any { it.matches(path) }) return@walk
                if (includeRegexes.any { it.matches(path) }) result.add(path)
            }
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
                for (child in vfs.list(root).sorted()) { // sorted: deterministic program order
                    if (!vfs.isDirectory(child)) continue
                    val base = PathUtil.basename(child)
                    if (base.startsWith(".")) continue
                    if (base.startsWith("@")) {
                        // A scope directory inside a type root contributes its subdirectories.
                        for (scoped in vfs.list(child).sorted()) {
                            if (!vfs.isDirectory(scoped) || PathUtil.basename(scoped).startsWith(".")) continue
                            resolver.resolveTypeRootPackage(scoped)?.let { entries.add(it) }
                        }
                    } else {
                        resolver.resolveTypeRootPackage(child)?.let { entries.add(it) }
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

    /** Recursively walks [dir], invoking [onFile] for each file; prunes obvious heavy dirs. */
    private fun walk(dir: String, onFile: (String) -> Unit) {
        val pruned = setOf("node_modules", ".git", "bower_components", "jspm_packages")
        val stack = ArrayDeque(listOf(dir))
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            for (entry in vfs.list(d)) {
                if (vfs.isDirectory(entry)) {
                    if (PathUtil.basename(entry) !in pruned) stack.addLast(entry)
                } else {
                    onFile(entry)
                }
            }
        }
    }

    /**
     * Converts a glob [pattern] (already absolute) to a [Regex] over absolute paths.
     * Supports `**` (any depth), `*` (within a segment), `?`. If the final segment has
     * no extension, the supported extensions are appended (TS `include: ["src"]` semantics).
     */
    private fun globToRegex(pattern: String, supportedExt: List<String>): Regex {
        var p = PathUtil.normalize(pattern)
        val lastSeg = PathUtil.basename(p)
        val extlessDir = !lastSeg.contains('.') && !lastSeg.contains('*') && !lastSeg.contains('?')
        if (extlessDir) p = "$p/**/*"
        val sb = StringBuilder()
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when (c) {
                '*' -> if (i + 1 < p.length && p[i + 1] == '*') {
                    // `**/` => any number of dirs; bare `**` => anything
                    if (i + 2 < p.length && p[i + 2] == '/') { sb.append("(?:[^/]+/)*"); i += 2 } else sb.append(".*")
                    i++
                } else { sb.append("[^/]*"); i++ }
                '?' -> { sb.append("[^/]"); i++ }
                '.', '(', ')', '+', '{', '}', '[', ']', '$', '^', '|', '\\' -> { sb.append('\\').append(c); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        // No extension in the pattern? accept any supported extension on the matched leaf.
        if (!lastSeg.contains('.') ) {
            val alt = supportedExt.joinToString("|") { Regex.escape(it) }
            sb.append("(?:$alt)")
        }
        return Regex("^$sb$")
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
    private fun parseForCrawl(fileName: String, source: String, options: CompilerOptions): PreParsedFile? {
        if (fileName.endsWith(".json")) return null
        val flags = computeParserFlags(fileName, source, options)
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
