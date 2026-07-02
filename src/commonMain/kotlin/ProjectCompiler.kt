/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

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
    fun build(projectPath: String, noEmit: Boolean = false): Result {
        // Absolutize first: glob regexes, module resolution, and output mapping all
        // assume absolute paths (a relative `.` would produce `./src/**` patterns that
        // never match the absolute paths the Vfs walk yields).
        val configPath = resolveConfigPath(vfs.resolveAbsolute(projectPath))
        val config = TsConfigLoader(vfs).load(configPath)
        val resolver = ModuleResolver(vfs, config.customConditions)

        val allowJs = config.options.allowJs
        val supportedExt = if (allowJs) rootExtensions + listOf(".js", ".jsx", ".mjs", ".cjs") else rootExtensions

        val rootFiles = collectRootFiles(config, supportedExt)

        // Walk the import graph from the roots, reading and resolving as we go.
        val program = LinkedHashMap<String, String>() // path -> content
        val unresolved = mutableListOf<Pair<String, String>>()
        val queue = ArrayDeque(rootFiles)
        for (f in rootFiles) program[f] = vfs.readText(f) ?: ""
        while (queue.isNotEmpty()) {
            val file = queue.removeFirst()
            val content = program[file] ?: continue
            for (spec in extractSpecifiers(content)) {
                val resolved = resolver.resolve(spec, file)
                if (resolved == null) {
                    if (PathUtil.isBare(spec) || PathUtil.isRelative(spec)) unresolved.add(file to spec)
                    continue
                }
                if (resolved !in program) {
                    val rc = vfs.readText(resolved) ?: continue
                    program[resolved] = rc
                    queue.addLast(resolved)
                }
            }
        }

        // Feed the gathered file set through the shared compilation core. The core's
        // output naming serves baseline comparison: by default it strips names to
        // basenames (which collide across subdirectories), and its own outDir remap is
        // keyed on the inputs' common ancestor rather than rootDir. Withhold outDir and
        // request full input-derived paths instead (`fullEmitPaths` affects naming only);
        // all outDir/rootDir mapping happens in [writeOutputs] from the input paths.
        val emitOptions = config.options.copy(outDir = null, fullEmitPaths = true)
        val files = program.map { (name, content) -> SourceFileEntry(name, content) }
        val parsed = ParsedSource(emitOptions, files, hasExplicitFilenames = true)
        val result = TypeScriptCompiler().compileParsed(
            parsed, emitOptions, rootFiles.firstOrNull() ?: "input.ts",
        )

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
            // then the compiler's own diagnostics.
            diagnostics = config.diagnostics + compilerDiagnostics,
            unresolved = unresolved.distinct(),
            written = written,
        )
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

    // --- import-specifier extraction --------------------------------------------

    private val specifierRegexes = listOf(
        Regex("""\bfrom\s*["']([^"']+)["']"""),
        Regex("""\bimport\s*["']([^"']+)["']"""),
        Regex("""\bimport\s*\(\s*["']([^"']+)["']\s*\)"""),
        Regex("""\brequire\s*\(\s*["']([^"']+)["']\s*\)"""),
        Regex("""<reference\s+(?:path|types)\s*=\s*["']([^"']+)["']"""),
    )

    /** Extracts every module specifier referenced by [source] (over-collection is harmless). */
    private fun extractSpecifiers(source: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (re in specifierRegexes) {
            for (m in re.findAll(source)) {
                m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            }
        }
        return out
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
