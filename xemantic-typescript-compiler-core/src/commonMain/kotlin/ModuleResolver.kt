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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A pragmatic node / nodenext / bundler module resolver operating over a [Vfs].
 *
 * Given an import [specifier] and the importing file, returns the absolute path of
 * the resolved module (`null` if unresolvable). Implements the parts that matter for
 * building a real project's file graph for type-checking + emit:
 *
 * - **relative / absolute** specifiers with TypeScript extension probing
 *   (`.ts`/`.tsx`/`.d.ts`/`.mts`/`.cts`, then `.js`/`.jsx`/`.json`) and `index.*`;
 * - **`.js` → `.ts` rewriting** (a `./foo.js` specifier resolving to `./foo.ts`),
 *   the nodenext/bundler convention zod and modern ESM projects rely on;
 * - **bare specifiers** via `node_modules` directory walk, honoring package.json
 *   `exports` (subpath maps, condition maps incl. `customConditions`, and `*`
 *   wildcards), then `types`/`typings`, then `main`, then `index.*`;
 * - **`@types/<name>`** fallback (with `@scope/name` → `scope__name` mangling).
 *
 * Type ("types"/custom) conditions are prioritized over runtime ones so the resolver
 * prefers `.d.ts` / source over emitted `.js` — which is what a type-checker wants.
 */
class ModuleResolver(
    private val vfs: Vfs,
    customConditions: List<String> = emptyList(),
) {

    /** Export/import condition priority. Type + custom conditions first (source/decls win). */
    private val conditions: List<String> =
        customConditions + listOf("types", "import", "module", "node", "require", "default")

    /** TypeScript source/decl extensions, in probe order (source preferred over decls). */
    private val tsExtensions = listOf(".ts", ".tsx", ".d.ts", ".mts", ".cts", ".d.mts", ".d.cts")

    /** All extensions probed for an extensionless specifier. */
    private val allExtensions = tsExtensions + listOf(".js", ".jsx", ".json", ".mjs", ".cjs")

    /** Index basenames probed for a directory specifier. */
    private val indexBases = listOf("index.ts", "index.tsx", "index.d.ts", "index.mts", "index.cts", "index.js")

    /** A JS-ish specifier extension → the TS extensions it may actually denote. */
    private val jsToTs: Map<String, List<String>> = mapOf(
        ".js" to listOf(".ts", ".tsx", ".d.ts"),
        ".jsx" to listOf(".tsx", ".d.ts"),
        ".mjs" to listOf(".mts", ".d.mts"),
        ".cjs" to listOf(".cts", ".d.cts"),
    )

    private val pkgJsonCache = HashMap<String, JsonObject?>()

    /**
     * (INC.65) The answers already computed, keyed by `(importerDir, specifier)`.
     *
     * **The key is the importer's DIRECTORY, not its path, and that is exact rather
     * than approximate**: [resolve] reads `importerPath` once, to take its `dirname`,
     * and never again — every branch below that line is a function of `importerDir`
     * and `spec` alone. So two files in one directory importing the same specifier
     * ask the same question, and on the generated 2,401-file application fixture that
     * is a duplication factor of exactly **2.0** (4,701 resolutions over 2,351
     * distinct pairs).
     *
     * Why it needs no invalidation: a [ModuleResolver] is constructed once per
     * `ProjectCompiler.build`, so the memo's lifetime IS one build — and the crawl
     * already documents that it assumes a `Vfs` static for its duration (its answers
     * would otherwise not be deterministic either). It is deliberately NOT
     * process-global for that reason; a cross-build cache cannot see an ADDED file,
     * which is (INC.48)'s hazard.
     *
     * `null` is a real answer (an unresolved specifier, which the caller reports), so
     * membership is tested rather than nullability — a `getOrPut` would re-probe the
     * filesystem for every unresolved import, which is the population a broken project
     * has most of.
     */
    private val resolutionCache = HashMap<String, String?>()

    /** (INC.65) census — resolutions COMPUTED, as opposed to served from [resolutionCache]. */
    var computedResolutions: Int = 0
        private set

    /** (INC.65) census — calls to [resolve], whether served or computed. */
    var resolveCalls: Int = 0
        private set

    /** Resolves [specifier] imported from [importerPath]; returns an absolute file path or null. */
    fun resolve(specifier: String, importerPath: String): String? {
        // Strip query/hash a bundler might tolerate.
        val spec = specifier.substringBefore('?').substringBefore('#')
        if (spec.isEmpty()) return null
        resolveCalls++
        val importerDir = PathUtil.dirname(importerPath)
        // '\u0000' cannot occur in a path, so the two halves cannot be confused.
        val key = "$importerDir\u0000$spec"
        if (key in resolutionCache) return resolutionCache[key]
        computedResolutions++
        val answer = if (PathUtil.isBare(spec)) {
            resolveBare(spec, importerDir)
        } else {
            val base = if (PathUtil.isAbsolute(spec)) PathUtil.normalize(spec) else PathUtil.join(importerDir, spec)
            resolveAsFileOrDirectory(base)
        }
        resolutionCache[key] = answer
        return answer
    }

    // --- relative / absolute ----------------------------------------------------

    /** Resolves [p] as a file (with extension/`.js`→`.ts` probing) or directory (index/package). */
    private fun resolveAsFileOrDirectory(p: String): String? {
        resolveAsFile(p)?.let { return it }
        if (vfs.isDirectory(p)) resolveAsDirectory(p)?.let { return it }
        return null
    }

    /** Resolves [p] to a concrete file via exact match, `.js`→`.ts` rewrite, then extension probe. */
    private fun resolveAsFile(p: String): String? {
        val ext = PathUtil.extname(p)
        // A JS-ish specifier most likely denotes a sibling TS file.
        jsToTs[ext]?.let { tsExts ->
            val stem = p.removeSuffix(ext)
            for (te in tsExts) if (vfs.exists(stem + te)) return stem + te
            if (vfs.exists(p)) return p // a real .js (allowJs) as a last resort
            return null
        }
        // Already has a known TS/JS/json extension.
        if (ext.isNotEmpty() && (ext in setOf(".ts", ".tsx", ".json") || p.endsWith(".d.ts"))) {
            if (vfs.exists(p)) return p
        }
        // Extensionless: probe.
        for (e in allExtensions) if (vfs.exists(p + e)) return p + e
        if (ext.isNotEmpty() && vfs.exists(p)) return p
        return null
    }

    /** Resolves directory [dir] via its package.json (types/main) then `index.*`. */
    private fun resolveAsDirectory(dir: String): String? {
        readPackageJson("$dir/package.json")?.let { pkg ->
            (pkg["types"]?.stringValue ?: pkg["typings"]?.stringValue)?.let { t ->
                resolveAsFile(PathUtil.join(dir, t))?.let { return it }
            }
            pkg["main"]?.stringValue?.let { m ->
                resolveAsFile(PathUtil.join(dir, m))?.let { return it }
                if (vfs.isDirectory(PathUtil.join(dir, m))) resolveIndex(PathUtil.join(dir, m))?.let { return it }
            }
        }
        return resolveIndex(dir)
    }

    private fun resolveIndex(dir: String): String? {
        for (base in indexBases) {
            val p = "$dir/$base"
            if (vfs.exists(p)) return p
        }
        return null
    }

    // --- bare specifiers --------------------------------------------------------

    private fun resolveBare(spec: String, fromDir: String): String? {
        val (pkg, sub) = splitBare(spec)
        var dir = fromDir
        while (true) {
            val nm = "$dir/node_modules"
            val pkgDir = "$nm/$pkg"
            if (vfs.isDirectory(pkgDir)) {
                resolveInPackage(pkgDir, sub)?.let { return it }
            }
            // @types fallback: @types/<name> or @types/<scope>__<name>.
            val typesPkg = if (pkg.startsWith("@")) "@types/" + pkg.substring(1).replace("/", "__") else "@types/$pkg"
            val typesDir = "$nm/$typesPkg"
            if (vfs.isDirectory(typesDir)) {
                resolveInPackage(typesDir, sub)?.let { return it }
            }
            val parent = PathUtil.dirname(dir)
            if (parent == dir || parent.isEmpty()) return null
            dir = parent
        }
    }

    /** Splits a bare specifier into (packageName, subpath); handles `@scope/name`. */
    private fun splitBare(spec: String): Pair<String, String> {
        val parts = spec.split('/')
        return if (spec.startsWith("@") && parts.size >= 2) {
            parts.take(2).joinToString("/") to parts.drop(2).joinToString("/")
        } else {
            parts[0] to parts.drop(1).joinToString("/")
        }
    }

    private fun resolveInPackage(pkgDir: String, sub: String): String? {
        val pkg = readPackageJson("$pkgDir/package.json")
        val exportsVal = pkg?.get("exports")
        if (exportsVal != null) {
            val key = if (sub.isEmpty()) "." else "./$sub"
            resolveExports(exportsVal, key, pkgDir)?.let { return it }
            // `exports` is authoritative in spec; we fall through leniently to help
            // type-checking even when a package's exports map omits a `types`-y target.
        }
        return if (sub.isEmpty()) {
            (pkg?.get("types")?.stringValue ?: pkg?.get("typings")?.stringValue)?.let { t ->
                resolveAsFile(PathUtil.join(pkgDir, t))?.let { return it }
            }
            pkg?.get("main")?.stringValue?.let { m ->
                resolveAsFileOrDirectory(PathUtil.join(pkgDir, m))?.let { return it }
            }
            resolveAsDirectory(pkgDir)
        } else {
            resolveAsFileOrDirectory(PathUtil.join(pkgDir, sub))
        }
    }

    // --- type-root packages (tsconfig `types` / `typeRoots`) ---------------------

    /**
     * Resolves a type-library package DIRECTORY (a `typeRoots` / `node_modules/@types`
     * entry) to its declaration entry file: package.json `types`/`typings`, else
     * `index.d.ts` — tsc's type-reference-directive resolution over one candidate
     * directory. Deliberately narrower than [resolveAsDirectory]: a type package's
     * entry is always a declaration file, so `main` and runtime `index.*` never apply.
     */
    fun resolveTypeRootPackage(pkgDir: String): String? {
        if (!vfs.isDirectory(pkgDir)) return null
        readPackageJson("$pkgDir/package.json")?.let { pkg ->
            (pkg["types"]?.stringValue ?: pkg["typings"]?.stringValue)?.let { t ->
                resolveAsFile(PathUtil.join(pkgDir, t))?.let { return it }
            }
        }
        val index = "$pkgDir/index.d.ts"
        return if (vfs.exists(index)) index else null
    }

    // --- package.json "exports" -------------------------------------------------

    /** Resolves an export [key] (".", "./sub") against the package's `exports` [value]. */
    private fun resolveExports(value: JsonElement, key: String, pkgDir: String): String? {
        // String or array sugar applies only to the package root ".".
        if (value is JsonPrimitive || value is JsonArray) {
            return if (key == ".") resolveExportTarget(value, pkgDir, null) else null
        }
        val obj = (value as? JsonObject) ?: return null
        val isSubpathMap = obj.keys.all { it == "." || it.startsWith("./") }
        if (!isSubpathMap) {
            // A pure condition map applies to "." only.
            return if (key == ".") resolveExportTarget(value, pkgDir, null) else null
        }
        obj[key]?.let { return resolveExportTarget(it, pkgDir, null) }
        // Wildcard subpath: "./*" → "./dist/*.js".
        for ((pat, target) in obj) {
            if (!pat.contains('*')) continue
            val star = matchWildcard(pat, key) ?: continue
            resolveExportTarget(target, pkgDir, star)?.let { return it }
        }
        return null
    }

    /** Resolves a (possibly condition-nested, possibly `*`-templated) export target. */
    private fun resolveExportTarget(value: JsonElement, pkgDir: String, star: String?): String? = when (value) {
        is JsonObject -> {
            var result: String? = null
            for (cond in conditions) {
                val branch = value[cond] ?: continue
                resolveExportTarget(branch, pkgDir, star)?.let { result = it }
                if (result != null) break
            }
            result
        }
        is JsonArray -> value.firstNotNullOfOrNull { resolveExportTarget(it, pkgDir, star) }
        else -> value.stringValue?.let { s ->
            val t = if (star != null) s.replace("*", star) else s
            resolveAsFile(PathUtil.join(pkgDir, t))
        }
    }

    /** Matches subpath [key] against a single-`*` [pattern]; returns the `*` capture or null. */
    private fun matchWildcard(pattern: String, key: String): String? {
        val star = pattern.indexOf('*')
        if (star < 0) return null
        val prefix = pattern.substring(0, star)
        val suffix = pattern.substring(star + 1)
        if (!key.startsWith(prefix) || !key.endsWith(suffix)) return null
        if (key.length < prefix.length + suffix.length) return null
        return key.substring(prefix.length, key.length - suffix.length)
    }

    /**
     * Reads and caches a `package.json` as a [JsonObject]. Returns `null` when the file
     * is absent OR malformed: a broken *dependency* manifest must not abort resolution —
     * the effect already surfaces upstream as an unresolved import. (The project's own
     * `tsconfig.json` is read by [TsConfigLoader], which DOES report parse failures.)
     */
    private fun readPackageJson(path: String): JsonObject? =
        pkgJsonCache.getOrPut(PathUtil.normalize(path)) {
            val text = vfs.readText(path) ?: return@getOrPut null
            try {
                LENIENT_JSON.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
        }
}
