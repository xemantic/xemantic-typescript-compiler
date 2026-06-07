/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

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
)

/**
 * Loads a `tsconfig.json` from disk through a [Vfs], resolving the `extends`
 * chain and mapping `compilerOptions` onto [CompilerOptions] via [applyDirective]
 * (the same option-string mapping the test directives use).
 *
 * Supported: `extends` (string or array; relative paths and `node_modules` package
 * configs), `compilerOptions`, `include`, `exclude`, `files`, `customConditions`.
 * `compilerOptions.paths` and other object-valued options that [applyDirective]
 * doesn't model are skipped (logged by the caller, not fatal).
 */
class TsConfigLoader(private val vfs: Vfs) {

    /** Default TypeScript excludes when `exclude` is unspecified. */
    private val defaultExclude = listOf("node_modules", "bower_components", "jspm_packages")

    /** Default include when neither `include` nor `files` is present: everything. */
    private val defaultInclude = listOf("**/*")

    fun load(tsconfigPath: String): LoadedTsConfig {
        val configDir = PathUtil.dirname(tsconfigPath)
        val merged = loadMerged(tsconfigPath, mutableSetOf())
            ?: return LoadedTsConfig(CompilerOptions(), configDir, defaultInclude, defaultExclude, emptyList(), emptyList())

        val coObj = (merged["compilerOptions"] as? JsonValue.Obj)?.entries ?: emptyMap()
        var options = CompilerOptions()
        for ((rawKey, value) in coObj) {
            val key = rawKey.lowercase()
            val directiveValue = jsonToDirectiveValue(value) ?: continue
            options = applyDirective(options, key, directiveValue)
        }
        options = applyImpliedAllowJs(options)

        // outDir / rootDir are resolved to absolute paths relative to the config dir.
        options = options.copy(
            outDir = options.outDir?.let { PathUtil.join(configDir, it) },
            rootDir = options.rootDir?.let { PathUtil.join(configDir, it) },
        )

        val include = (merged["include"]?.asStringList ?: emptyList()).ifEmpty {
            if (merged["files"] != null) emptyList() else defaultInclude
        }
        val exclude = merged["exclude"]?.asStringList ?: defaultExclude
        val files = (merged["files"]?.asStringList ?: emptyList()).map { PathUtil.join(configDir, it) }
        val customConditions = coObj["customConditions"]?.asStringList ?: emptyList()

        return LoadedTsConfig(options, configDir, include, exclude, files, customConditions)
    }

    /**
     * Reads [tsconfigPath], recursively merges its `extends` parents (parent first,
     * child overrides), and returns the merged top-level object. `compilerOptions`
     * is deep-merged; `include`/`exclude`/`files` are child-wins (TS semantics:
     * a child that specifies them replaces the parent's).
     */
    private fun loadMerged(tsconfigPath: String, seen: MutableSet<String>): JsonValue.Obj? {
        val norm = PathUtil.normalize(tsconfigPath)
        if (!seen.add(norm)) return null // cycle guard
        val text = vfs.readText(norm) ?: return null
        val obj = parseJson(text) as? JsonValue.Obj ?: return null
        val configDir = PathUtil.dirname(norm)

        val parents = obj["extends"]?.asStringList ?: emptyList()
        var base: JsonValue.Obj? = null
        for (ext in parents) {
            val parentPath = resolveExtends(ext, configDir) ?: continue
            val parent = loadMerged(parentPath, seen) ?: continue
            base = if (base == null) parent else mergeConfig(base, parent)
        }
        return if (base == null) obj else mergeConfig(base, obj)
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

    /** child overrides base; `compilerOptions` objects are shallow-merged. */
    private fun mergeConfig(base: JsonValue.Obj, child: JsonValue.Obj): JsonValue.Obj {
        val out = LinkedHashMap(base.entries)
        for ((k, v) in child.entries) {
            out[k] = if (k == "compilerOptions" && v is JsonValue.Obj) {
                val baseCo = (base.entries[k] as? JsonValue.Obj)?.entries ?: emptyMap()
                JsonValue.Obj(LinkedHashMap(baseCo).apply { putAll(v.entries) })
            } else v
        }
        return JsonValue.Obj(out)
    }

    /** Converts a compilerOptions value to the string form [applyDirective] expects. */
    private fun jsonToDirectiveValue(value: JsonValue): String? = when (value) {
        is JsonValue.Str -> value.value
        is JsonValue.Bool -> value.value.toString()
        is JsonValue.Num -> {
            val d = value.value
            if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
        }
        is JsonValue.Arr -> value.items.mapNotNull { it.string }.joinToString(",")
        else -> null // objects (paths, etc.) are not modeled by applyDirective
    }
}
