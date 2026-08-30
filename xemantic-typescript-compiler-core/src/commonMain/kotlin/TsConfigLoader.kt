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

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A fully-resolved `tsconfig.json`: the merged [CompilerOptions], the raw
 * `include`/`exclude`/`files` glob/path lists (resolved against [configDir]),
 * and the `customConditions` used by the [ModuleResolver] for `exports` matching.
 */
data class LoadedTsConfig(
    val options: CompilerOptions,
    val configDir: String,
    val include: List<String>,
    val exclude: List<String>,
    val files: List<String>,
    val customConditions: List<String>,
    /** Errors encountered loading the config chain (unreadable file TS5083, malformed JSON TS5014). */
    val diagnostics: List<Diagnostic> = emptyList(),
)

/**
 * Loads a `tsconfig.json` from disk through a [Vfs], resolving the `extends` chain
 * and mapping `compilerOptions` onto [CompilerOptions] via [applyDirective] (the same
 * option-string mapping the test directives use).
 *
 * Each config file is read into the typed [TsConfigFile] via kotlinx-serialization
 * (JSONC-tolerant). Supported: `extends` (string or array; relative paths and
 * `node_modules` package configs), `compilerOptions`, `include`, `exclude`, `files`,
 * `customConditions`. `compilerOptions.paths` and other object-valued options that
 * [applyDirective] doesn't model are skipped.
 */
class TsConfigLoader(private val vfs: Vfs) {

    /**
     * Default TypeScript excludes when `exclude` is unspecified.
     *
     * (CFG.1) The package folders are what tsc prunes from every WILDCARD match
     * regardless of `exclude` (`commonPackageFolders`, and
     * [ProjectCompiler]'s own walk prunes the same three by basename), so listing
     * them here is belt-and-braces rather than the rule. **The rule tsc actually
     * has for an ABSENT `exclude` is `[outDir, declarationDir]`**
     * (`commandLineParser.ts`: `excludeOfRaw === "no-prop"` =>
     * `excludeSpecs = filter([outDir, declarationDir], d => !!d)`), and see
     * [defaultExcludeFor] for why that half is not optional.
     */
    private val defaultExclude = listOf("node_modules", "bower_components", "jspm_packages")

    /**
     * (CFG.1) The excludes for a config that specifies none: the package folders,
     * plus **the directories this project EMITS INTO**.
     *
     * Without the second half a project that has ever run a declaration-emitting
     * build reads its own output back in as ROOT FILES — `dist/**` matches the
     * default `**/*` include, and a `.d.ts` is a root extension — which costs
     * duplicate declarations tsc does not report and, on every keystroke, the whole
     * emitted tree crawled, read, parsed, bound and checked. Measured on a
     * two-file project: tsgo 7.0.2's program is 1 file and ours was 2.
     *
     * An EXPLICIT `exclude` REPLACES this, exactly as in tsc — it is not additive,
     * so a project that names its own excludes and then emits into an included
     * directory gets tsc's behaviour, warts and all.
     */
    private fun defaultExcludeFor(options: CompilerOptions, configDir: String): List<String> =
        defaultExclude + listOfNotNull(
            options.outDir,
            options.declarationDir?.let { PathUtil.join(configDir, it) },
        )

    /** Default include when neither `include` nor `files` is present: everything. */
    private val defaultInclude = listOf("**/*")

    fun load(tsconfigPath: String): LoadedTsConfig {
        val configDir = PathUtil.dirname(tsconfigPath)
        val diags = mutableListOf<Diagnostic>()
        val merged = loadMerged(tsconfigPath, mutableSetOf(), diags)
            ?: return LoadedTsConfig(
                projectDefaults(),
                configDir,
                // A config file that does not EXIST must not fall back to `**/*`
                // (round 873). `configDir` is then `dirname` of a path nobody
                // ever confirmed, so for `xtsc /nonexistent-project` it is `/`
                // and the crawl walks the WHOLE FILESYSTEM — measured at over 30
                // minutes of CPU before it was killed, having emitted TS5083
                // first and then gone looking for `**/*` under the root. Under
                // `--serve` the same request wedges the single compile thread
                // for good: every later request on that daemon blocks forever,
                // and the client has no timeout to notice. tsc reports the path
                // and compiles nothing, which is what an empty include does; a
                // config that exists but does not PARSE keeps the default,
                // because there `configDir` is a directory the user did name.
                if (vfs.exists(tsconfigPath)) defaultInclude else emptyList(),
                defaultExclude, emptyList(), emptyList(), diags,
            )

        val co = merged.compilerOptions ?: JsonObject(emptyMap())
        var options = projectDefaults()
        for ((rawKey, value) in co) {
            val directiveValue = jsonToDirectiveValue(value) ?: continue
            options = applyDirective(options, rawKey.lowercase(), directiveValue)
        }
        options = applyImpliedAllowJs(options)

        // outDir / rootDir are resolved to absolute paths relative to the config dir.
        options = options.copy(
            outDir = options.outDir?.let { PathUtil.join(configDir, it) },
            rootDir = options.rootDir?.let { PathUtil.join(configDir, it) },
        )

        val include = (merged.include ?: emptyList()).ifEmpty {
            if (merged.files != null) emptyList() else defaultInclude
        }
        val exclude = merged.exclude ?: defaultExcludeFor(options, configDir)
        val files = (merged.files ?: emptyList()).map { PathUtil.join(configDir, it) }
        val customConditions = co["customConditions"]?.asStringList ?: emptyList()

        return LoadedTsConfig(options, configDir, include, exclude, files, customConditions, diags)
    }

    /**
     * Reads [tsconfigPath], recursively merges its `extends` parents (parent first,
     * child overrides), and returns the merged config. `compilerOptions` is shallow-
     * merged; `include`/`exclude`/`files` are child-wins (a child that specifies them
     * replaces the parent's, per TS semantics).
     */
    private fun loadMerged(
        tsconfigPath: String,
        seen: MutableSet<String>,
        diags: MutableList<Diagnostic>,
    ): TsConfigFile? {
        val norm = PathUtil.normalize(tsconfigPath)
        if (!seen.add(norm)) return null // cycle guard
        val text = vfs.readText(norm)
        if (text == null) {
            diags.add(configError("Cannot read file '$norm'.", 5083, norm))
            return null
        }
        val self = try {
            LENIENT_JSON.decodeFromString<TsConfigFile>(text)
        } catch (e: SerializationException) {
            val detail = e.message?.substringBefore('\n')?.trim().orEmpty().ifEmpty { "invalid JSON" }
            diags.add(configError("Failed to parse file '$norm': $detail", 5014, norm))
            return null
        }
        val configDir = PathUtil.dirname(norm)

        var base: TsConfigFile? = null
        for (ext in (self.extends?.asStringList ?: emptyList())) {
            val parentPath = resolveExtends(ext, configDir)
            if (parentPath == null) {
                diags.add(configError("File '$ext' not found.", 6053, norm))
                continue
            }
            val parent = loadMerged(parentPath, seen, diags) ?: continue
            base = if (base == null) parent else merge(base, parent)
        }
        return if (base == null) self else merge(base, self)
    }

    private fun configError(message: String, code: Int, fileName: String) = Diagnostic(
        message = message,
        category = DiagnosticCategory.Error,
        code = code,
        fileName = fileName,
    )

    /** child overrides base; `compilerOptions` is shallow-merged. */
    private fun merge(base: TsConfigFile, child: TsConfigFile): TsConfigFile = TsConfigFile(
        extends = child.extends,
        compilerOptions = mergeObjects(base.compilerOptions, child.compilerOptions),
        include = child.include ?: base.include,
        exclude = child.exclude ?: base.exclude,
        files = child.files ?: base.files,
    )

    private fun mergeObjects(base: JsonObject?, child: JsonObject?): JsonObject? = when {
        base == null -> child
        child == null -> base
        else -> JsonObject(base + child) // child keys override
    }

    /** Resolves an `extends` target to a tsconfig path (relative file or node_modules package). */
    private fun resolveExtends(ext: String, fromDir: String): String? {
        if (PathUtil.isRelative(ext) || PathUtil.isAbsolute(ext)) {
            val p = PathUtil.join(fromDir, ext)
            return when {
                p.endsWith(".json") && vfs.exists(p) -> p
                vfs.exists("$p.json") -> "$p.json"
                vfs.isDirectory(p) && vfs.exists("$p/tsconfig.json") -> "$p/tsconfig.json"
                vfs.exists(p) -> p
                else -> null
            }
        }
        // Bare package config: walk node_modules looking for <pkg> / <pkg>.json / <pkg>/tsconfig.json.
        var dir = fromDir
        while (true) {
            val nm = "$dir/node_modules/$ext"
            when {
                nm.endsWith(".json") && vfs.exists(nm) -> return nm
                vfs.exists("$nm.json") -> return "$nm.json"
                vfs.isDirectory(nm) && vfs.exists("$nm/tsconfig.json") -> return "$nm/tsconfig.json"
                vfs.exists(nm) -> return nm
            }
            val parent = PathUtil.dirname(dir)
            if (parent == dir || parent.isEmpty()) return null
            dir = parent
        }
    }

    /** Converts a compilerOptions value to the string form [applyDirective] expects. */
    private fun jsonToDirectiveValue(value: JsonElement): String? = when (value) {
        is JsonNull -> null
        is JsonPrimitive -> value.content // string content, or "true"/"false"/"5" for bool/number
        is JsonArray -> value.mapNotNull { it.stringValue }.joinToString(",")
        else -> null // objects (paths, etc.) are not modeled by applyDirective
    }
}
