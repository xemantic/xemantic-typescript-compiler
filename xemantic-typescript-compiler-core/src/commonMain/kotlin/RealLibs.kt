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
 * M2.1(b): the lib reference-DAG resolver over the real TypeScript lib sources
 * shipped in [RealLibFiles].
 *
 * Mirrors tsc exactly (all read from the pinned offline sources):
 * - `libMap` / `libs` (commandLineParser.ts `libEntries`): user-facing lib name
 *   (lowercased) -> distributed file name (`"es6"` -> `"lib.es2015.d.ts"`,
 *   `"esnext.bigint"` -> `"lib.es2020.bigint.d.ts"`, ...). The entry ORDER is
 *   load-bearing: it drives the final inclusion order (below).
 * - `targetToLibMap` + `getDefaultLibFileName` (utilitiesPublic.ts): the default
 *   lib per `target` when no `lib` option is given — the `.full` variants;
 *   ES2015 -> `lib.es6.d.ts` and ES5/ES3 -> `lib.d.ts` for back-compat.
 * - Closure (program.ts `processLibReferenceDirectives`): every included lib
 *   file's `/// <reference lib="x" />` directives pull in more lib files,
 *   recursively.
 * - Final order (program.ts `getDefaultLibFilePriority`): NOT the DFS order —
 *   `lib.d.ts` / `lib.es6.d.ts` sort first (priority 0), every other file by
 *   the index of its bare name in `libs` (+1), unknown names last. This is what
 *   makes overload resolution across lib layers (an es5 interface extended by
 *   es2015.core) deterministic.
 *
 * (LIB.1)(b), round 731: the DOM / webworker / scripthost host libs — referenced by
 * every `.full` variant, so pulled in by any target-default lib set — ARE shipped now.
 * Until then they landed in [Resolution.unavailable], which nothing outside this file
 * ever consumed, so a browser project's `HTMLElement` degraded to `any` and its DOM
 * code compiled clean while being entirely unchecked. [Resolution.unavailable] stays
 * for the shape's sake but is expected to be EMPTY at this pin.
 */
object RealLibResolver {

    /**
     * tsc commandLineParser.ts `libEntries`, verbatim (order = tsc's `libs` array).
     * Keys are the user-facing (lowercase) lib names; values the distributed file names.
     */
    val libMap: Map<String, String> = linkedMapOf(
        // JavaScript only
        "es5" to "lib.es5.d.ts",
        "es6" to "lib.es2015.d.ts",
        "es2015" to "lib.es2015.d.ts",
        "es7" to "lib.es2016.d.ts",
        "es2016" to "lib.es2016.d.ts",
        "es2017" to "lib.es2017.d.ts",
        "es2018" to "lib.es2018.d.ts",
        "es2019" to "lib.es2019.d.ts",
        "es2020" to "lib.es2020.d.ts",
        "es2021" to "lib.es2021.d.ts",
        "es2022" to "lib.es2022.d.ts",
        "es2023" to "lib.es2023.d.ts",
        "es2024" to "lib.es2024.d.ts",
        "es2025" to "lib.es2025.d.ts",
        "esnext" to "lib.esnext.d.ts",
        // Host only
        "dom" to "lib.dom.d.ts",
        "dom.iterable" to "lib.dom.iterable.d.ts",
        "dom.asynciterable" to "lib.dom.asynciterable.d.ts",
        "webworker" to "lib.webworker.d.ts",
        "webworker.importscripts" to "lib.webworker.importscripts.d.ts",
        "webworker.iterable" to "lib.webworker.iterable.d.ts",
        "webworker.asynciterable" to "lib.webworker.asynciterable.d.ts",
        "scripthost" to "lib.scripthost.d.ts",
        // ES2015 and later by-feature options
        "es2015.core" to "lib.es2015.core.d.ts",
        "es2015.collection" to "lib.es2015.collection.d.ts",
        "es2015.generator" to "lib.es2015.generator.d.ts",
        "es2015.iterable" to "lib.es2015.iterable.d.ts",
        "es2015.promise" to "lib.es2015.promise.d.ts",
        "es2015.proxy" to "lib.es2015.proxy.d.ts",
        "es2015.reflect" to "lib.es2015.reflect.d.ts",
        "es2015.symbol" to "lib.es2015.symbol.d.ts",
        "es2015.symbol.wellknown" to "lib.es2015.symbol.wellknown.d.ts",
        "es2016.array.include" to "lib.es2016.array.include.d.ts",
        "es2016.intl" to "lib.es2016.intl.d.ts",
        "es2017.arraybuffer" to "lib.es2017.arraybuffer.d.ts",
        "es2017.date" to "lib.es2017.date.d.ts",
        "es2017.object" to "lib.es2017.object.d.ts",
        "es2017.sharedmemory" to "lib.es2017.sharedmemory.d.ts",
        "es2017.string" to "lib.es2017.string.d.ts",
        "es2017.intl" to "lib.es2017.intl.d.ts",
        "es2017.typedarrays" to "lib.es2017.typedarrays.d.ts",
        "es2018.asyncgenerator" to "lib.es2018.asyncgenerator.d.ts",
        "es2018.asynciterable" to "lib.es2018.asynciterable.d.ts",
        "es2018.intl" to "lib.es2018.intl.d.ts",
        "es2018.promise" to "lib.es2018.promise.d.ts",
        "es2018.regexp" to "lib.es2018.regexp.d.ts",
        "es2019.array" to "lib.es2019.array.d.ts",
        "es2019.object" to "lib.es2019.object.d.ts",
        "es2019.string" to "lib.es2019.string.d.ts",
        "es2019.symbol" to "lib.es2019.symbol.d.ts",
        "es2019.intl" to "lib.es2019.intl.d.ts",
        "es2020.bigint" to "lib.es2020.bigint.d.ts",
        "es2020.date" to "lib.es2020.date.d.ts",
        "es2020.promise" to "lib.es2020.promise.d.ts",
        "es2020.sharedmemory" to "lib.es2020.sharedmemory.d.ts",
        "es2020.string" to "lib.es2020.string.d.ts",
        "es2020.symbol.wellknown" to "lib.es2020.symbol.wellknown.d.ts",
        "es2020.intl" to "lib.es2020.intl.d.ts",
        "es2020.number" to "lib.es2020.number.d.ts",
        "es2021.promise" to "lib.es2021.promise.d.ts",
        "es2021.string" to "lib.es2021.string.d.ts",
        "es2021.weakref" to "lib.es2021.weakref.d.ts",
        "es2021.intl" to "lib.es2021.intl.d.ts",
        "es2022.array" to "lib.es2022.array.d.ts",
        "es2022.error" to "lib.es2022.error.d.ts",
        "es2022.intl" to "lib.es2022.intl.d.ts",
        "es2022.object" to "lib.es2022.object.d.ts",
        "es2022.string" to "lib.es2022.string.d.ts",
        "es2022.regexp" to "lib.es2022.regexp.d.ts",
        "es2023.array" to "lib.es2023.array.d.ts",
        "es2023.collection" to "lib.es2023.collection.d.ts",
        "es2023.intl" to "lib.es2023.intl.d.ts",
        "es2024.arraybuffer" to "lib.es2024.arraybuffer.d.ts",
        "es2024.collection" to "lib.es2024.collection.d.ts",
        "es2024.object" to "lib.es2024.object.d.ts",
        "es2024.promise" to "lib.es2024.promise.d.ts",
        "es2024.regexp" to "lib.es2024.regexp.d.ts",
        "es2024.sharedmemory" to "lib.es2024.sharedmemory.d.ts",
        "es2024.string" to "lib.es2024.string.d.ts",
        "es2025.collection" to "lib.es2025.collection.d.ts",
        "es2025.float16" to "lib.es2025.float16.d.ts",
        "es2025.intl" to "lib.es2025.intl.d.ts",
        "es2025.iterator" to "lib.es2025.iterator.d.ts",
        "es2025.promise" to "lib.es2025.promise.d.ts",
        "es2025.regexp" to "lib.es2025.regexp.d.ts",
        // Fallback for backward compatibility
        "esnext.asynciterable" to "lib.es2018.asynciterable.d.ts",
        "esnext.symbol" to "lib.es2019.symbol.d.ts",
        "esnext.bigint" to "lib.es2020.bigint.d.ts",
        "esnext.weakref" to "lib.es2021.weakref.d.ts",
        "esnext.object" to "lib.es2024.object.d.ts",
        "esnext.regexp" to "lib.es2024.regexp.d.ts",
        "esnext.string" to "lib.es2024.string.d.ts",
        "esnext.float16" to "lib.es2025.float16.d.ts",
        "esnext.iterator" to "lib.es2025.iterator.d.ts",
        "esnext.promise" to "lib.es2025.promise.d.ts",
        // ESNext by-feature options
        "esnext.array" to "lib.esnext.array.d.ts",
        "esnext.collection" to "lib.esnext.collection.d.ts",
        "esnext.date" to "lib.esnext.date.d.ts",
        "esnext.decorators" to "lib.esnext.decorators.d.ts",
        "esnext.disposable" to "lib.esnext.disposable.d.ts",
        "esnext.error" to "lib.esnext.error.d.ts",
        "esnext.intl" to "lib.esnext.intl.d.ts",
        "esnext.sharedmemory" to "lib.esnext.sharedmemory.d.ts",
        "esnext.temporal" to "lib.esnext.temporal.d.ts",
        "esnext.typedarrays" to "lib.esnext.typedarrays.d.ts",
        // Decorators
        "decorators" to "lib.decorators.d.ts",
        "decorators.legacy" to "lib.decorators.legacy.d.ts",
    )

    /** tsc's `libs` array: the lib names in `libEntries` order (drives inclusion priority). */
    val libs: List<String> = libMap.keys.toList()

    /**
     * tsc utilitiesPublic.ts `targetToLibMap` + `getDefaultLibFileName`: the default
     * lib file when no `lib` option is given. ES2015 keeps `lib.es6.d.ts` and
     * everything below ES2015 keeps `lib.d.ts` (tsc back-compat).
     */
    fun defaultLibFileName(target: ScriptTarget): String = when (target) {
        ScriptTarget.ESNext -> "lib.esnext.full.d.ts"
        ScriptTarget.ES2024 -> "lib.es2024.full.d.ts"
        ScriptTarget.ES2023 -> "lib.es2023.full.d.ts"
        ScriptTarget.ES2022 -> "lib.es2022.full.d.ts"
        ScriptTarget.ES2021 -> "lib.es2021.full.d.ts"
        ScriptTarget.ES2020 -> "lib.es2020.full.d.ts"
        ScriptTarget.ES2019 -> "lib.es2019.full.d.ts"
        ScriptTarget.ES2018 -> "lib.es2018.full.d.ts"
        ScriptTarget.ES2017 -> "lib.es2017.full.d.ts"
        ScriptTarget.ES2016 -> "lib.es2016.full.d.ts"
        ScriptTarget.ES2015 -> "lib.es6.d.ts" // tsc: not lib.es2015.full.d.ts (breaking change)
        ScriptTarget.ES5, ScriptTarget.ES3 -> "lib.d.ts"
    }

    /**
     * The result of a lib-set resolution.
     *
     * @property orderedKeys [RealLibFiles.files] keys of every included-and-shipped
     *   lib file, deduped, in tsc's inclusion order.
     * @property unknownNames `lib` option entries / reference-directive names that are
     *   not in [libMap] (tsc reports a diagnostic per occurrence; the caller owns that).
     * @property unavailable resolved-but-not-shipped file names. EMPTY since (LIB.1)(b)
     *   shipped the whole `src/lib` set; a non-empty value now means the pin moved and
     *   introduced a lib file the generator did not pick up.
     */
    data class Resolution(
        val orderedKeys: List<String>,
        val unknownNames: List<String>,
        val unavailable: List<String>,
    )

    /**
     * Resolves the lib set for a program: roots = [libNames] (the `lib` option,
     * entries matched case-insensitively against [libMap]) when non-null, else the
     * [target]'s default lib; expands `/// <reference lib="…" />` closures; dedupes;
     * orders by tsc's default-lib priority.
     */
    fun resolve(libNames: List<String>?, target: ScriptTarget): Resolution =
        ResolutionKey(libNames, target).let { key ->
            resolutionCache[key] ?: resolveCore(libNames, target).also {
                resolutionCache = resolutionCache + (key to it)
            }
        }

    /**
     * (INC.63): `resolve` is a pure function of two arguments over COMPILE-TIME CONSTANT
     * content, and its `/// <reference lib=…/>` closure runs [libReferenceRegex] over
     * every included file — ~3.7 MB of lib text for a `dom` set, which (INC.53) measured
     * at 3.1-5.3 ms and which every [Checker] construction paid again. Memoized here
     * rather than at the call sites because there are several and they must agree
     * (CLAUDE.md (CHK.17): the lib set must stay in step with `prewarmParsedLibFiles`).
     *
     * Not thread-safe, like [RealLibSnapshots.parsedLibFile] and for the same reason —
     * `prewarmParsedLibFiles` resolves on the calling thread before any worker spawns.
     */
    private data class ResolutionKey(val libNames: List<String>?, val target: ScriptTarget)

    /**
     * (INC.67) Published COPY-ON-WRITE rather than mutated in place, because this map
     * is PROCESS-GLOBAL and an IntelliJ-class host reaches it from several threads at
     * once: `XtscService` keeps one `XtscSession` per `tsconfig.json`, each with its
     * own single-thread executor, so a monorepo with N configs runs N compiler threads
     * in one JVM. A racing `HashMap.put` can lose entries or corrupt the table; a
     * reference swap of a map that is never mutated after publication cannot — a
     * reader either sees the old complete map or the new complete one. A lost race
     * costs a recomputation, which is the benign direction.
     */
    @Volatile
    private var resolutionCache: Map<ResolutionKey, Resolution> = emptyMap()

    /**
     * (INC.63) census — lib-set resolutions this process has COMPUTED, as opposed to
     * served from [resolutionCache]. Read as a DELTA by its pin: the cache is
     * process-global and outlives any one test.
     */
    var resolutions: Int = 0
        private set

    /**
     * (INC.67) The published map itself, for the one pin that can state copy-on-write
     * DETERMINISTICALLY: a snapshot taken before a miss must NOT contain the key the
     * miss then adds. A thread-safety claim is otherwise only testable by a stress
     * test, which passes on a broken implementation most of the time.
     */
    internal val publishedResolutions: Map<*, Resolution> get() = resolutionCache

    private fun resolveCore(libNames: List<String>?, target: ScriptTarget): Resolution {
        val includedFiles = LinkedHashSet<String>() // dist file names, discovery order
        val unknown = mutableListOf<String>()
        val unavailable = mutableListOf<String>()

        fun includeFile(distFileName: String) {
            if (!includedFiles.add(distFileName)) return
            val key = distFileNameToKey(distFileName)
            val content = RealLibFiles.files[key]
            if (content == null) {
                unavailable.add(distFileName)
                return
            }
            for (refName in referencedLibNames(content)) {
                val refFile = libMap[refName.lowercase()]
                if (refFile == null) unknown.add(refName) else includeFile(refFile)
            }
        }

        if (libNames != null) {
            for (name in libNames) {
                val file = libMap[name.lowercase()]
                if (file == null) unknown.add(name) else includeFile(file)
            }
        } else {
            includeFile(defaultLibFileName(target))
        }

        val ordered = includedFiles
            .filter { distFileNameToKey(it) in RealLibFiles.files }
            .sortedBy { libFilePriority(it) } // stable: ties keep discovery order
            .map { distFileNameToKey(it) }
        resolutions++
        return Resolution(ordered, unknown, unavailable)
    }

    /** Distributed file name -> [RealLibFiles.files] key (src/lib bare name; libs.json `paths`). */
    fun distFileNameToKey(distFileName: String): String = when (distFileName) {
        "lib.d.ts" -> "es5.full"
        "lib.es6.d.ts" -> "es2015.full"
        "lib.dom.d.ts" -> "dom.generated"
        "lib.dom.iterable.d.ts" -> "dom.iterable.generated"
        "lib.dom.asynciterable.d.ts" -> "dom.asynciterable.generated"
        "lib.webworker.d.ts" -> "webworker.generated"
        "lib.webworker.iterable.d.ts" -> "webworker.iterable.generated"
        "lib.webworker.asynciterable.d.ts" -> "webworker.asynciterable.generated"
        else -> distFileName.removePrefix("lib.").removeSuffix(".d.ts")
    }

    /**
     * tsc program.ts `getDefaultLibFilePriority`: `lib.d.ts`/`lib.es6.d.ts` first,
     * then by bare-name index in [libs]; names not in [libs] (the `.full` variants,
     * which carry only reference directives) last.
     */
    private fun libFilePriority(distFileName: String): Int {
        if (distFileName == "lib.d.ts" || distFileName == "lib.es6.d.ts") return 0
        val name = distFileName.removePrefix("lib.").removeSuffix(".d.ts")
        val index = libs.indexOf(name)
        return if (index != -1) index + 1 else libs.size + 2
    }

    private val libReferenceRegex = Regex("""///\s*<reference\s+lib\s*=\s*"([^"]+)"\s*/>""")

    /** The `/// <reference lib="…" />` directive names in [content], in source order. */
    fun referencedLibNames(content: String): List<String> =
        libReferenceRegex.findAll(content).map { it.groupValues[1] }.toList()

    /**
     * Inverse of [distFileNameToKey]: [RealLibFiles.files] key -> distributed file name.
     *
     * The `*.generated` source names MUST be mapped back (round 731): tsc distributes
     * `src/lib/dom.generated.d.ts` as `lib.dom.d.ts`, and this name is what a
     * lib-declaration diagnostic renders (TS2728's "declared here") — leaving the else
     * branch to build `lib.dom.generated.d.ts` would print a file name that does not
     * exist in any TypeScript distribution. The `.full` variants (`es2016.full`, …) are
     * NOT special-cased: their source and distributed names already agree.
     */
    fun keyToDistFileName(key: String): String = when (key) {
        "es5.full" -> "lib.d.ts"
        "es2015.full" -> "lib.es6.d.ts"
        "dom.generated" -> "lib.dom.d.ts"
        "dom.iterable.generated" -> "lib.dom.iterable.d.ts"
        "dom.asynciterable.generated" -> "lib.dom.asynciterable.d.ts"
        "webworker.generated" -> "lib.webworker.d.ts"
        "webworker.iterable.generated" -> "lib.webworker.iterable.d.ts"
        "webworker.asynciterable.generated" -> "lib.webworker.asynciterable.d.ts"
        else -> "lib.$key.d.ts"
    }
}

/**
 * M2.1(c): process-wide cache of PARSED real lib files.
 *
 * Parsing is the shareable half of "parse + bind once": a [SourceFile] AST is
 * immutable under checking (Binder/Checker state lives in side tables — the
 * LinkStore pattern), so one parse per lib file serves every program forever.
 * BINDING is deliberately per-consumer: [bindLibFiles] returns FRESH
 * [BinderResult]s because the checker's `mergeSymbolTable(globals, locals)`
 * MUTATES the merged-in symbols (flags |= / declarations.addAll — the documented
 * merge-pollution gotcha), so a shared bound symbol table would leak one
 * program's user-declaration merges into the next program's lib. Revisit
 * bind-sharing with M5.4/M5.5 once symbol merging stops mutating its inputs.
 *
 * Not thread-safe (plain cache map). Under `--workers N` every partition checker
 * builds its lib set from its OWN thread, so the parallel driver must call
 * [prewarmParsedLibFiles] before spawning workers — after which the map is
 * read-only for the rest of the compile and the workers never touch a bucket.
 */
object RealLibSnapshots {

    /** (INC.67) Copy-on-write for [RealLibResolver.resolutionCache]'s reason — this
     *  object is process-global and an IDE host reaches it from one thread per open
     *  `tsconfig.json`. [prewarmParsedLibFiles] remains the way a `--workers` compile
     *  avoids the duplicate PARSES a lost race would cost; this is about the map. */
    @Volatile
    private var parseCache: Map<String, SourceFile> = emptyMap()

    /**
     * The shared, immutable parse of one lib file, keyed by [RealLibFiles.files]
     * key. The [SourceFile.fileName] is the DISTRIBUTED name (`lib.es5.d.ts`,
     * `lib.d.ts`) — what tsc baselines render in lib-file positions.
     */
    fun parsedLibFile(key: String): SourceFile = parseCache[key] ?: run {
        val content = RealLibFiles.files[key]
            ?: error("Unknown real-lib key '$key' (not shipped in RealLibFiles)")
        val parsed = Parser(content, keyToDistFileName(key)).parse()
        parseCache = parseCache + (key to parsed)
        parsed
    }

    private fun keyToDistFileName(key: String) = RealLibResolver.keyToDistFileName(key)

    /**
     * The ordered parsed lib set for a program: `libNames` (null = unset -> the
     * [target] default lib) resolved through [RealLibResolver.resolve], each file
     * parsed once process-wide.
     */
    fun parsedLibFiles(libNames: List<String>?, target: ScriptTarget): List<SourceFile> =
        RealLibResolver.resolve(libNames, target).orderedKeys.map { parsedLibFile(it) }

    /**
     * Fresh per-program binder results for the selected lib set (see the class
     * KDoc for why binding is not shared). Bound in inclusion order — the caller
     * merges `locals` in this order so later libs' interface declarations merge
     * onto earlier ones exactly like tsc's concatenated default libs.
     */
    fun bindLibFiles(libNames: List<String>?, target: ScriptTarget, options: CompilerOptions): List<BinderResult> =
        parsedLibFiles(libNames, target).map { Binder(options).bind(it) }

    /**
     * (PERF.HW.a) round 825: materialise [parseCache] for [options]'s lib set on the
     * CALLING thread, so the `--workers N` partition checkers that follow only ever
     * READ it. Resolves through exactly the inputs [Checker.bindRealLibs] uses, so a
     * warmed key set is by construction the key set the workers will ask for.
     *
     * Two things it buys: the plain-HashMap data race N concurrent [parsedLibFile]
     * calls would otherwise be, and the N-1 duplicate parses of every lib file.
     */
    fun prewarmParsedLibFiles(options: CompilerOptions) {
        if (!options.useRealLibs) return
        parsedLibFiles(options.lib.ifEmpty { null }, options.defaultedTarget)
        libDeclIndex(options.lib.ifEmpty { null }, options.defaultedTarget)
    }

    /**
     * (INC.63): the three lib DECLARATION tables [Checker] used to rebuild per
     * construction — a pure function of the SHARED parses, and therefore exactly as
     * shareable as they are.
     *
     * [decls] / [memberDecls] are the B85.2 identity sets (top-level lib statements,
     * and the members of lib interfaces/classes); [declFile] is the M2.2 map from a
     * real-lib declaration node to its DISTRIBUTED file name, which TS2728's
     * "declared here" renders. Nothing writes to them after construction — the
     * read-only types are what keeps that true — and they key on nodes of the
     * process-wide [parseCache], so one index serves every program with that lib set.
     *
     * NOT the bind: [bindLibFiles] stays per-consumer because `mergeSymbolTable`
     * MUTATES the merged-in symbols (see this object's KDoc). The walk does not
     * mutate anything, which is the whole reason this half separates.
     */
    class LibDeclIndex(
        val decls: Set<Node>,
        val memberDecls: Set<Node>,
        val declFile: Map<Node, String>,
    )

    /** (INC.67) Copy-on-write, for the reason [parseCache] is. */
    @Volatile
    private var declIndexCache: Map<String, LibDeclIndex> = emptyMap()

    /**
     * (INC.63) census — how many indexes this process has BUILT (as opposed to
     * served). A pin reads it as a DELTA across two builds rather than as an
     * absolute, because the cache is process-global and outlives any one test.
     *
     * Plain, unsynchronized counters: they are CENSUS ONLY and their pins are
     * single-threaded. Under a multi-threaded host ((INC.67)) an increment can be lost —
     * read them as a lower bound there, never as a claim.
     */
    var declIndexBuilds: Int = 0
        private set

    /** (INC.63) census — total entries written by the last index BUILD, so a
     *  measurement can divide the row by its own population. */
    var declIndexEntries: Int = 0
        private set

    /**
     * The [LibDeclIndex] for a lib set, built once per process and then served.
     *
     * Keyed by the RESOLVED ordered key list, not by the requested names: two
     * projects spelling the same lib set differently (an explicit `"es2020"`
     * against an unset `lib` on an es2020 target) resolve to one key list and
     * share the index.
     */
    fun libDeclIndex(libNames: List<String>?, target: ScriptTarget): LibDeclIndex {
        val keys = RealLibResolver.resolve(libNames, target).orderedKeys
        val key = keys.joinToString("\u0000")
        return declIndexCache[key] ?: buildLibDeclIndex(keys.map { parsedLibFile(it) }).also {
            declIndexCache = declIndexCache + (key to it)
        }
    }

    /**
     * The walk itself, verbatim from [Checker.bindRealLibs] where it used to run once
     * per checker. Order and containers are unchanged on purpose: a `Set<Node>` over
     * data-class nodes dedupes STRUCTURALLY, so a different container would silently
     * change which declarations the sets contain.
     */
    /** (INC.67) See [RealLibResolver.publishedResolutions] — the same pin, one object over. */
    internal val publishedParses: Map<String, SourceFile> get() = parseCache

    private fun buildLibDeclIndex(files: List<SourceFile>): LibDeclIndex {
        val decls = HashSet<Node>()
        val memberDecls = HashSet<Node>()
        val declFile = HashMap<Node, String>()
        var entries = 0
        for (ast in files) {
            val distFile = ast.fileName
            ast.statements.forEach { stmt ->
                decls.add(stmt)
                declFile[stmt] = distFile
                entries += 2
                when (stmt) {
                    is InterfaceDeclaration -> stmt.members.forEach {
                        memberDecls.add(it); declFile[it] = distFile; entries += 2
                    }
                    is ClassDeclaration -> stmt.members.forEach {
                        memberDecls.add(it); declFile[it] = distFile; entries += 2
                    }
                    is VariableStatement -> stmt.declarationList.declarations.forEach {
                        decls.add(it); declFile[it] = distFile; entries += 2
                    }
                    else -> {}
                }
            }
        }
        declIndexBuilds++
        declIndexEntries = entries
        return LibDeclIndex(decls, memberDecls, declFile)
    }
}
