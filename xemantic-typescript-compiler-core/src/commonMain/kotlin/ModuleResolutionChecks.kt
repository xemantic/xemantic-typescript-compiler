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
 * (INV.0) (P18.294) — the MODULE-RESOLUTION family: `checkUnresolvedModules` (TS2307 / TS2792 / TS2882 /
 * TS2732 / TS5097 / TS2834 / TS2846 / TS7016 and the `paths` / `moduleSuffixes` / `node_modules` probes),
 * `checkRelativeImportsInAmbientModules` (TS2439 / TS2666), `checkNestedAmbientModules` (TS2435 / TS2668),
 * `checkImportEqualsRequireOfNonModule`, `checkNamespaceImportOfNonModule` and
 * `checkJsxImportResolutions` (TS6142), with their helpers and the per-file memo
 * `fileAmbientModuleInfoCache`. Extracted VERBATIM from `Checker.kt` (two spans: 6484-6486, the memo
 * field; 47767-49218); every Checker member it reads is reached through [checker], the two companion
 * sets through `Checker.`, and the remaining inputs are the checker's own constructor parameters,
 * passed in. Ambient reads: `docs/inversion-ambient-ledger.md` row 23.
 */
internal class ModuleResolutionChecks(
    private val checker: Checker,
    private val options: CompilerOptions,
    private val binderResults: List<BinderResult>,
    private val isMultiFileSource: Boolean,
    private val allInputFileNames: Set<String>,
    private val jsonModuleContents: Map<String, String>,
    private val untypedModuleResolutions: Map<String, Map<String, UntypedModuleResolution>>,
) {

    // B281: per-file cache of (top-level ambient module names, file declares ANY
    // string-named module anywhere). MUST be declared before init {} (init-order).
    private val fileAmbientModuleInfoCache = HashMap<String, Triple<Set<String>, Boolean, Set<String>>>()

    fun checkUnresolvedModules() {
        val isMultiFile = binderResults.size > 1 || isMultiFileSource

        // Collect ambient module names (declare module "X") from all files including .d.ts
        // so that side-effect imports like `import "Map"` don't get TS2882 when "Map" is
        // declared as an ambient module in a referenced .d.ts file.
        val ambientModuleNames = mutableSetOf<String>()
        // Also collect .d.ts filenames (without extension) as module names — e.g., "foo" for foo.d.ts.
        // A bare specifier import of "foo" is valid if foo.d.ts is in the compilation.
        val dtsFileBaseNames = mutableSetOf<String>()
        if (isMultiFile) {
            for (result in checker.checkedResults) {
                val fn = result.sourceFile.fileName
                if (checker.isDtsFile(fn)) {
                    // Extract base name without .d.ts extension
                    val base = fn.substringAfterLast("/").substringAfterLast("\\")
                        .removeSuffix(".d.ts")
                    dtsFileBaseNames.add(base)
                }
                for (stmt in result.sourceFile.statements) {
                    if (stmt is ModuleDeclaration) {
                        val name = stmt.name
                        if (name is StringLiteralNode && !checker.nameResolver.isUntargetedAugmentation(name.text)) { // (CHK.193)(b)
                            ambientModuleNames.add(name.text)
                        }
                    }
                }
            }
        }

        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text

            val ambientBodyImportPositions = mutableSetOf<Int>()
            for (stmt in flattenImportLikeStatements(result.sourceFile.statements, ambientBodyImportPositions)) {
                val isSideEffectImport = stmt is ImportDeclaration && stmt.importClause == null
                val specifier = when (stmt) {
                    is ImportDeclaration -> stmt.moduleSpecifier
                    is ExportDeclaration -> stmt.moduleSpecifier
                    is ImportEqualsDeclaration -> {
                        val ref = stmt.moduleReference
                        if (ref is ExternalModuleReference) ref.expression else null
                    }
                    else -> null
                }
                if (specifier == null) continue
                val moduleName = when (specifier) {
                    is StringLiteralNode -> specifier.text
                    else -> continue
                }
                // (CHK.202) resolved, by the project crawl, to a `node_modules` JavaScript
                // file the program does not contain: an implicit-`any` module (TS7016 under
                // noImplicitAny, never for a side-effect import), and no other row here.
                // An ambient `declare module` of the same name wins, as tsgo's
                // `tryFindAmbientModule` runs first.
                val untyped = untypedModuleResolutions[fileName]?.get(moduleName)
                if (untyped != null) {
                    if (!isSideEffectImport && moduleName !in ambientModuleNames &&
                        (options.noImplicitAny || (!options.noImplicitAnyExplicitlyFalse && !options.strictExplicitlyFalse))
                    ) {
                        val (line, character) = checker.getLineAndCharacterOfPosition(source, specifier.pos)
                        checker.diagnostics.add(UntypedModuleImports.diagnostic(
                            specifier, moduleName, untyped, fileName, line, character, checker.fileResults.keys,
                        ))
                    }
                    continue
                }
                // B472: the bare `.`/`..` package-index specifiers resolve to the importing
                // file's directory index — never TS2307 when they resolve (corpus-FP-safe:
                // only importWithTrailingSlash imports from exactly `.`/`..`).
                if ((moduleName == "." || moduleName == "..") &&
                    checker.resolveSpecifierAnywhere(moduleName, fileName) != null) {
                    continue
                }
                if (isMultiFile) {
                    val isRelative = moduleName.startsWith("./") || moduleName.startsWith("../")
                    // (LEGACY.1)(e) the ONE derivation, tsgo's `GetModuleResolutionKind()`:
                    // Node16 / NodeNext / Bundler — there is no classic and no node10
                    // resolution in TypeScript 7, so no arm below distinguishes one.
                    val effectiveModuleRes = options.effectiveModuleResolution
                    // B98.r105: TS5097 / TS2846 — a relative import specifier carrying a
                    // TS-only file extension. A `.ts`/`.tsx` extension needs
                    // `allowImportingTsExtensions` (or `rewriteRelativeImportExtensions`, which
                    // rewrites it at emit) → otherwise TS5097. A `.d.ts` specifier imported as a
                    // VALUE (not `import type`) is TS2846; the suggested implementation path gets
                    // a `.js` suffix only for ES-module output. Span = the quoted specifier.
                    if (isRelative) {
                        val isTypeOnlyImport = when (stmt) {
                            is ImportDeclaration -> stmt.importClause?.isTypeOnly == true
                            is ImportEqualsDeclaration -> stmt.isTypeOnly
                            is ExportDeclaration -> stmt.isTypeOnly
                            else -> false
                        }
                        if (moduleName.endsWith(".d.ts")) {
                            if (!isTypeOnlyImport) {
                                val suggested = moduleName.removeSuffix(".d.ts") +
                                    (if (options.module in Checker.ES_MODULE_KINDS) ".js" else "")
                                val (line, character) = checker.getLineAndCharacterOfPosition(source, specifier.pos)
                                checker.diagnostics.add(Diagnostic(
                                    message = "A declaration file cannot be imported without 'import type'. Did you mean to import an implementation file '$suggested' instead?",
                                    category = DiagnosticCategory.Error,
                                    code = 2846,
                                    fileName = fileName,
                                    line = line,
                                    character = character,
                                    start = specifier.pos,
                                    length = moduleName.length + 2,
                                ))
                                continue // TS reports only TS2846 for this specifier
                            }
                        } else if ((moduleName.endsWith(".ts") || moduleName.endsWith(".tsx")) &&
                            !options.allowImportingTsExtensions && !options.rewriteRelativeImportExtensions) {
                            val ext = if (moduleName.endsWith(".tsx")) ".tsx" else ".ts"
                            val (line, character) = checker.getLineAndCharacterOfPosition(source, specifier.pos)
                            checker.diagnostics.add(Diagnostic(
                                message = "An import path can only end with a '$ext' extension when 'allowImportingTsExtensions' is enabled.",
                                category = DiagnosticCategory.Error,
                                code = 5097,
                                fileName = fileName,
                                line = line,
                                character = character,
                                start = specifier.pos,
                                length = moduleName.length + 2,
                            ))
                            continue // TS reports only TS5097 for this specifier
                        }
                    }
                    // B141: TS5097 for a BARE specifier carrying a TS-only extension that
                    // resolves via a `paths` wildcard mapping to a real `.ts`/`.tsx` FILE.
                    // `foo/bar.ts` matching `"foo/*": ["./dist/*"]` → `./dist/bar.ts` (a file)
                    // fires (the `.ts` is a literal extension the user wrote). It does NOT fire
                    // when: (a) the pattern KEY itself ends in the extension (`"baz/*.ts"`
                    // absorbs the `.ts`, mirroring TS's resolvedUsingTsExtension semantics), or
                    // (b) the substituted target is a DIRECTORY name rather than a TS file
                    // (`zone.tsx` matching `"*": ["foo/*"]` → `foo/zone.tsx/index.d.ts`, a
                    // directory whose `.tsx` is not a file extension). FP firewall: only fires
                    // when the substituted target ends in `.ts`/`.tsx` AND directly names an
                    // existing source file — so a bare `.ts` with no paths file resolution
                    // (TS2307 territory) and directory-named-`.tsx` cases are untouched.
                    if (!isRelative && !options.allowImportingTsExtensions &&
                        !options.rewriteRelativeImportExtensions && options.paths.isNotEmpty() &&
                        (moduleName.endsWith(".ts") || moduleName.endsWith(".tsx")) &&
                        !moduleName.endsWith(".d.ts")) {
                        val ext = if (moduleName.endsWith(".tsx")) ".tsx" else ".ts"
                        fun pathsStar(pattern: String): Int = pattern.indexOf('*')
                        fun pathsPatternMatches(pattern: String): Boolean {
                            val star = pathsStar(pattern)
                            if (star < 0) return pattern == moduleName
                            val prefix = pattern.substring(0, star)
                            val suffix = pattern.substring(star + 1)
                            return moduleName.length >= prefix.length + suffix.length &&
                                moduleName.startsWith(prefix) && moduleName.endsWith(suffix)
                        }
                        fun targetNamesDirectFile(target: String): Boolean {
                            val norm = target.removePrefix("./")
                            return target in checker.fileResults || norm in checker.fileResults ||
                                "/$norm" in checker.fileResults || "./$norm" in checker.fileResults
                        }
                        val matchedKey = options.paths.keys
                            .filter { pathsPatternMatches(it) }
                            .maxByOrNull { pathsStar(it).let { s -> if (s < 0) it.length else s } }
                        if (matchedKey != null && !matchedKey.endsWith(ext)) {
                            val star = pathsStar(matchedKey)
                            val captured = if (star < 0) "" else moduleName.substring(
                                matchedKey.substring(0, star).length,
                                moduleName.length - matchedKey.substring(star + 1).length)
                            val resolvesToTsFile = options.paths[matchedKey]!!.any { tgt ->
                                val sub = if (tgt.contains('*')) tgt.replaceFirst("*", captured) else tgt
                                (sub.endsWith(".ts") || sub.endsWith(".tsx")) &&
                                    !sub.endsWith(".d.ts") && targetNamesDirectFile(sub)
                            }
                            if (resolvesToTsFile) {
                                val (line, character) = checker.getLineAndCharacterOfPosition(source, specifier.pos)
                                checker.diagnostics.add(Diagnostic(
                                    message = "An import path can only end with a '$ext' extension when 'allowImportingTsExtensions' is enabled.",
                                    category = DiagnosticCategory.Error,
                                    code = 5097,
                                    fileName = fileName,
                                    line = line,
                                    character = character,
                                    start = specifier.pos,
                                    length = moduleName.length + 2,
                                ))
                                continue // TS reports only TS5097 for this specifier
                            }
                        }
                    }
                    // B98.r61: TS2834 — under node16/nodenext in ESM format, a relative
                    // import MUST carry an explicit file extension (`./foo.js`); an
                    // extensionless relative specifier (`./pkg`, `./node_modules/pkg`)
                    // does not implicitly resolve to `index.js`. Non-relative (bare)
                    // specifiers are exempt. ESM detection via isESModuleFormat consults
                    // the nearest package.json `"type"` (so a `"type":"module"` root makes
                    // a plain `.ts` ESM); a CJS-mode nodenext `.ts` allows extensionless.
                    if (isRelative && effectiveModuleRes.isNode16OrNodeNext &&
                        !moduleName.endsWith(".json") && isESModuleFormat(options, fileName)) {
                        val lastSeg = moduleName.substringAfterLast('/')
                        if (lastSeg.isNotEmpty() && !lastSeg.contains('.')) {
                            val (line, character) = checker.getLineAndCharacterOfPosition(source, specifier.pos)
                            checker.diagnostics.add(Diagnostic(
                                message = "Relative import paths need explicit file extensions in ECMAScript imports when '--moduleResolution' is 'node16' or 'nodenext'. Consider adding an extension to the import path.",
                                category = DiagnosticCategory.Error,
                                code = 2834,
                                fileName = fileName,
                                line = line,
                                character = character,
                                start = specifier.pos,
                                length = moduleName.length + 2,
                            ))
                        }
                    }
                    if (isSideEffectImport) {
                        // Side-effect imports: TS2882
                        if (isRelative) {
                            // (CHK.78)(a) [resolveModuleSpecifier] matches the specifier against
                            // [fileResults] KEYS and is NOT directory-aware, so on a REAL project —
                            // whose keys are absolute paths — a relative side-effect import NEVER
                            // resolved and EVERY one of them read TS2882: `import "./types"` beside
                            // `types.ts` was a false positive, extensionless and ESM-`.js` alike,
                            // with or without an augmentation beside it. The corpus cannot see it
                            // (its file names are FLAT, so `./types` matches `types.ts` directly)
                            // and tsc's own 78 sources carry no relative side-effect import, so no
                            // gate here moved. The crawl's own `(importer, specifier)` answer is
                            // exact and empty off the project path (CHK.30), so appending it can
                            // only SUPPRESS a false row and never invent one.
                            if (checker.resolveModuleSpecifier(moduleName) == null &&
                                checker.resolveImportTargetFallback(moduleName, fileName) == null
                            ) {
                                emitTS2882(specifier, moduleName, source, fileName)
                            }
                        } else if (moduleName !in ambientModuleNames
                            && moduleName !in dtsFileBaseNames
                            && !hasNodeModulesPackage(moduleName)
                        ) {
                            // (LEGACY.1)(f) the `amd`/`umd`/`system` exemption is gone: tsgo
                            // 7.0.2 reports TS2882 under every kind (measured 2026-09-15).
                            emitTS2882(specifier, moduleName, source, fileName)
                        }
                    } else if (!options.moduleSuffixes.isNullOrEmpty() && isRelative && !moduleName.endsWith(".json")) {
                        // Node resolution with moduleSuffixes: relative imports only resolve to a
                        // file whose name matches one of the configured suffixes. If none match,
                        // emit TS2307 even though an unsuffixed file with the same base name exists.
                        if (resolveWithModuleSuffixes(moduleName, fileName, options.moduleSuffixes) == null) {
                            emitTS2307(specifier, moduleName, source, fileName)
                        }
                    } else if (isRelative && !moduleName.endsWith(".json")
                        && options.module in Checker.ES_MODULE_KINDS
                        && effectiveModuleRes == ModuleResolutionKind.Bundler
                        && options.paths.isNullOrEmpty()
                        && options.rootDirs.isNullOrEmpty()
                        && options.rootDir == null
                        && options.moduleSuffixes.isNullOrEmpty()
                    ) {
                        // 17.214: ES module kinds (module: ES6 / ES2015 / ES2020 /
                        // ESNext / Preserve) under Bundler resolution ((LEGACY.1)(e): the
                        // TypeScript 7 default — unset, or the removed `classic`/`node10`) and no
                        // path/root configuration: relative specifiers must resolve
                        // strictly within the compilation. When no file matches and
                        // no ambient module / .d.ts shadows it, emit TS2307. Narrow
                        // gate intentionally — node16/nodenext use richer resolution
                        // rules our resolver doesn't model. (LEGACY.1)(e): the probe is
                        // the index-aware one B98's commonjs arm uses — Bundler resolves
                        // `./dir` to `dir/index.ts` (measured in every ES cell of tsgo's
                        // matrix), and the strict probe read a false TS2307 for it.
                        if (checker.resolveRelativeIncludingIndex(moduleName, fileName) == null
                            && moduleName !in ambientModuleNames
                            && moduleName !in dtsFileBaseNames
                        ) {
                            emitTS2307(specifier, moduleName, source, fileName)
                        }
                    } else if (isRelative && !moduleName.endsWith(".json")
                        && options.module?.foldsToCommonJS == true
                        && effectiveModuleRes == ModuleResolutionKind.Bundler
                        && options.paths.isNullOrEmpty()
                        && options.rootDirs.isNullOrEmpty()
                        && options.rootDir == null
                        && options.moduleSuffixes.isNullOrEmpty()
                    ) {
                        // B98: relative specifier under Bundler resolution ((LEGACY.1)(e): the
                        // TypeScript 7 default) with an explicit `@module: commonjs` and no
                        // path/root config. The target
                        // must resolve within the compilation — a direct file OR a directory
                        // index (`./foo/index.ts`). When nothing matches and no ambient module
                        // / .d.ts shadows it, emit TS2307. Index-aware resolution
                        // (resolveRelativeIncludingIndex) keeps directory imports FP-safe.
                        // Gated to explicit commonjs (not the default null module) to bound the
                        // FP surface; node16/nodenext/bundler use richer rules we don't model.
                        if (checker.resolveRelativeIncludingIndex(moduleName, fileName) == null
                            && moduleName !in ambientModuleNames
                            && moduleName !in dtsFileBaseNames
                        ) {
                            // B98.r21: a relative specifier resolving to an untyped `.js` sibling
                            // (present in raw input but not bound — no `allowJs`) is NOT a missing
                            // module: TypeScript resolves it as an implicit-`any` module. Under
                            // noImplicitAny it reports TS7016; otherwise it is silently `any` (NO
                            // diagnostic). Either way TS2307 is wrong — suppress it when a JS sibling
                            // exists. The TS2307 path is untouched when no JS sibling exists (FP-safe).
                            val jsSibling = checker.resolveRelativeJsSibling(moduleName, fileName)
                            if (jsSibling != null) {
                                if (options.noImplicitAny || options.strict) {
                                    val (line, character) = checker.getLineAndCharacterOfPosition(source, specifier.pos)
                                    checker.diagnostics.add(Diagnostic(
                                        message = "Could not find a declaration file for module '$moduleName'. '$jsSibling' implicitly has an 'any' type.",
                                        category = DiagnosticCategory.Error,
                                        code = 7016,
                                        fileName = fileName,
                                        line = line,
                                        character = character,
                                        start = specifier.pos,
                                        length = moduleName.length + 2, // +2 for quotes (matches emitTS2307)
                                    ))
                                }
                                // else: untyped `any` module, no diagnostic.
                            } else {
                                emitTS2307(specifier, moduleName, source, fileName)
                            }
                        }
                    } else if (isRelative && effectiveModuleRes == ModuleResolutionKind.Bundler && moduleName.endsWith("/")
                        && !moduleName.endsWith(".json")
                        && options.paths.isNullOrEmpty()
                        && options.rootDirs.isNullOrEmpty()
                        && options.rootDir == null
                        && options.moduleSuffixes.isNullOrEmpty()
                    ) {
                        // B98: bundler resolution, a relative TRAILING-SLASH specifier
                        // (`"./"`, `"../"`, `"./foo/"`). Under bundler these resolve to the
                        // directory's index file; when the directory doesn't exist (no
                        // `<dir>/index.<ext>` and no matching file) TypeScript emits TS2307.
                        // Index-aware resolution keeps the legit `"./"`/`"../"`→`index.ts`
                        // cases FP-safe (they resolve non-null → suppressed), so only a
                        // genuinely-missing directory like `./foo/` fires. Narrow to the
                        // trailing-slash shape under bundler only (no path/root config).
                        if (checker.resolveRelativeIncludingIndex(moduleName, fileName) == null
                            && moduleName !in ambientModuleNames
                            && moduleName !in dtsFileBaseNames
                        ) {
                            emitTS2307(specifier, moduleName, source, fileName)
                        }
                    } else if (moduleName.endsWith(".json") && !options.resolveJsonModule && !isRelative) {
                        // Non-relative .json imports (resolved via node_modules or path mapping)
                        // require `resolveJsonModule: true`; without it, TypeScript emits TS2732.
                        // Relative .json imports still fall back to direct-file parsing in the test
                        // layout, so we leave their handling to downstream type checking.
                        emitTS2732(specifier, moduleName, source, fileName)
                    } else if (!isRelative && options.paths.isNotEmpty() && moduleName in options.paths) {
                        // Paths mapping exact-key with explicit-extension targets: if every mapped
                        // target has an explicit .ts/.tsx/.d.ts extension (ruling out directory →
                        // index resolution) and NONE of them resolve to an existing file, emit
                        // TS2307. Narrow to avoid FPs where a bare target like "./lib/p1" resolves
                        // via `./lib/p1/index.ts` — our resolver doesn't model index-file lookup.
                        val pathTargets = options.paths[moduleName]!!
                        val allExplicitExt = pathTargets.isNotEmpty() && pathTargets.all { t ->
                            !t.contains('*') && (
                                t.endsWith(".ts") || t.endsWith(".tsx") || t.endsWith(".d.ts")
                            )
                        }
                        if (allExplicitExt) {
                            val anyResolves = pathTargets.any { target ->
                                target in checker.fileResults
                                    || "/$target" in checker.fileResults
                                    || "./$target" in checker.fileResults
                                    || checker.resolveModuleSpecifier(target) != null
                            }
                            if (!anyResolves && moduleName !in ambientModuleNames
                                && moduleName !in dtsFileBaseNames
                                && !hasNodeModulesPackage(moduleName)
                            ) {
                                emitTS2307(specifier, moduleName, source, fileName)
                            }
                        }
                    } else if (!isRelative && effectiveModuleRes == ModuleResolutionKind.NodeNext
                        && moduleName !in ambientModuleNames
                        && !hasNodeModulesPackage(moduleName)
                        && moduleName in dtsFileBaseNames
                    ) {
                        // 17.225: NodeNext rejects a bare `node_modules/<name>.d.ts` as a
                        // resolvable module — under nodenext rules the resolver requires
                        // a `node_modules/<name>/` DIRECTORY with package.json (exports/main)
                        // or `index.{d.ts,ts}`. A `.d.ts` file directly under `node_modules/`
                        // is a stray declaration to be picked up some other way (e.g.
                        // `typeRoots`), not a target for a bare specifier. Our generic
                        // `dtsFileBaseNames` check would otherwise treat every `.d.ts`
                        // basename as resolvable; add a nodenext-specific guard so the
                        // `foo` import in `nodeNextModuleResolution1_ts` still emits TS2307.
                        // Gated on EVERY matching `.d.ts` for the bare name living directly
                        // under `node_modules/` (not in a subdirectory) — a sibling
                        // project-level `<name>.d.ts` is still a valid declaration and
                        // must keep suppressing the diagnostic.
                        val matchingDts = checker.fileResults.keys.filter { fn ->
                            checker.isDtsFile(fn) && fn.substringAfterLast("/").removeSuffix(".d.ts") == moduleName
                        }
                        val allBareInNodeModules = matchingDts.isNotEmpty() && matchingDts.all { fn ->
                            val idx = fn.lastIndexOf("/node_modules/")
                            idx >= 0 && !fn.substring(idx + "/node_modules/".length).contains('/')
                        }
                        if (allBareInNodeModules) {
                            emitTS2307(specifier, moduleName, source, fileName)
                        }
                    }
                    // B165: nodenext + a node_modules package whose package.json carries a PRESENT,
                    // NON-NULL, STRING-form `exports` that names no existing file. Node's `exports`
                    // field is EXHAUSTIVE: when present it disables both `main` and the index.d.ts
                    // fallback, so a broken string target makes the package unresolvable → TS2307
                    // (nodeNextImportModeImplicitIndexResolution2's dedent4; dedent/dedent2 have NO
                    // exports and dedent3 has `"exports": null` — both keep the index fallback and
                    // resolve). Object-form exports / wildcard / directory targets are NOT modeled —
                    // suppressed for FP-safety.
                    else if (!isRelative && effectiveModuleRes == ModuleResolutionKind.NodeNext
                        && !moduleName.contains("/")
                        && !moduleName.contains(":")
                        && moduleName !in ambientModuleNames
                        && moduleName !in Checker.NODE_BUILTIN_MODULES
                        && brokenStringExportsPackage(moduleName)
                    ) {
                        emitTS2307(specifier, moduleName, source, fileName)
                    }
                    // B166: SCOPED specifier (`@s/p`) under EXPLICIT `typeRoots`
                    // (moduleResolutionAsTypeReferenceDirectiveScoped). Per tsc's trace, the
                    // lookup dirs are: each typeRoot (LITERAL scoped name — unless the root
                    // itself is an `@types` dir, where the MANGLED `s__p` form is required),
                    // plain `node_modules` dirs (literal), and `node_modules/@types` (mangled
                    // only — a literal `@s/p` directory under `@types` does NOT count).
                    // Gated to explicit typeRoots config so ordinary scoped-package corpus
                    // tests never reach this branch.
                    else if (!isRelative
                        && moduleName.startsWith("@")
                        && moduleName.count { it == '/' } == 1
                        && options.typeRoots != null
                        && options.paths.isNullOrEmpty()
                        && moduleName !in ambientModuleNames
                        && !scopedResolvableForImport(moduleName)
                    ) {
                        emitTS2307(specifier, moduleName, source, fileName)
                    }
                    // Node-builtin bare specifier (`"module"`, `"fs"`, etc.) under node resolution
                    // that's NOT an ambient module / .d.ts / node_modules package: TypeScript emits
                    // TS2591 with the @types/node hint. Narrow to known node-builtin names so we
                    // don't FP on arbitrary unresolved bare specifiers under multi-file node mode.
                    else if (!isRelative
                        && moduleName !in ambientModuleNames
                        && moduleName !in dtsFileBaseNames
                        && !hasNodeModulesPackage(moduleName)
                        && (moduleName in Checker.NODE_BUILTIN_MODULES || moduleName.startsWith("node:"))
                    ) {
                        emitTS2307(specifier, moduleName, source, fileName)
                    }
                    // B98.r107 + B98.r168, ONE arm since (LEGACY.1)(e): a SINGLE-SEGMENT bare
                    // specifier (e.g. `"server"`, `"a"`) under Bundler resolution — TypeScript 7's
                    // default (unset, or the removed `classic`/`node10`) and an explicit `bundler`
                    // (cachedModuleResolution6/7) are the SAME kind there — with NO
                    // path/rootDirs/moduleSuffixes config CANNOT resolve to a plain sibling
                    // source file: a bare specifier resolves only via node_modules / an ambient
                    // `declare module "X"`. Our `resolveModuleSpecifier` wrongly matches such a
                    // name to a sibling `.ts` by basename, so nothing fires; TypeScript emits
                    // TS2307. The narrow gating keeps the FP surface tiny relative to the reverted
                    // blanket B98.r2 attempt: single-segment-only (no `/` → excludes scoped
                    // `@scope/pkg` and path-mapped specifiers), no `:` (excludes `node:` and other
                    // protocol forms), no node_modules package, no ambient module, not a node
                    // builtin. The `.d.ts` rule is NODE_MODULES-AWARE: a plain SIBLING `.d.ts`
                    // (e.g. `a.d.ts` next to `b.ts`) is NOT bare-resolvable — only a `.d.ts` under
                    // `node_modules/`/`@types/` is — so `bareDtsResolvableInNodeModules` (not the
                    // over-broad `dtsFileBaseNames` set) gates the suppression; this lets the
                    // sibling-`.d.ts` shape (`es6ExportAssignment3`) fire while keeping real
                    // node_modules typings suppressed. The module-kind gate is r107's verbatim; the
                    // two CommonJS suppressions now also cover an explicit `bundler` + `commonjs`
                    // program, where r168 had none — conservative, and what tsgo resolves anyway.
                    else if (!isRelative && (effectiveModuleRes == ModuleResolutionKind.Bundler ||
                            // (P18.274) M2: Node16/NodeNext resolve a bare specifier through
                            // node_modules alone as well (date-fns' `vitest`), with the
                            // CommonJS arm's two untyped-package suppressions.
                            (effectiveModuleRes.isNode16OrNodeNext &&
                                !bareModulePackageInAnyInput(moduleName) &&
                                !bareModuleSymlinkTargetDir(moduleName)))
                        && !moduleName.startsWith("/")
                        && !moduleName.contains("/")
                        && !moduleName.contains(":")
                        && (options.module == null || options.module in Checker.ES_MODULE_KINDS ||
                            effectiveModuleRes.isNode16OrNodeNext
                            // B524: also EXPLICIT commonjs. The extra suppressions below cover
                            // the B98.r2 FP cases that previously made this case intractable:
                            // untyped node_modules `.js` (bareModulePackageInAnyInput) + symlinked
                            // packages whose target is a real `<pkg>/index.{ts,tsx,d.ts}` dir
                            // (bareModuleSymlinkTargetDir).
                            || (options.module.foldsToCommonJS
                                && !bareModulePackageInAnyInput(moduleName)
                                && !bareModuleSymlinkTargetDir(moduleName)))
                        && options.paths.isNullOrEmpty()
                        && options.rootDirs.isNullOrEmpty()
                        && options.rootDir == null
                        && options.moduleSuffixes.isNullOrEmpty()
                        && moduleName !in ambientModuleNames
                        && !bareDtsResolvableInNodeModules(moduleName)
                        && !hasNodeModulesPackage(moduleName)
                        && moduleName !in Checker.NODE_BUILTIN_MODULES
                    ) {
                        emitTS2307(specifier, moduleName, source, fileName)
                    }
                    // B164: bare single-segment specifiers under `resolveJsonModule`
                    // (requireOfJsonFileNonRelativeWithoutExtension). Two FP-safe sub-shapes,
                    // both pre-gated by the same no-config guards as r107/r168 plus the
                    // .ts/.d.ts/package/ambient/builtin suppressions:
                    //  (a) a specifier literally ENDING in `.json` resolves only via a
                    //      `<dir>/node_modules/<spec>` JSON file — when none exists anywhere
                    //      (JSON files live in jsonModuleContents, NOT fileResults) → TS2307;
                    //  (b) an EXTENSIONLESS specifier whose ONLY node_modules candidate is
                    //      `<spec>.json` → TS2307: TypeScript never appends `.json` to an
                    //      extensionless specifier (resolveJsonModule applies to literal
                    //      `.json` specifiers only), so the present JSON file cannot satisfy it.
                    else if (!isRelative && options.resolveJsonModule
                        && !moduleName.startsWith("/")
                        && !moduleName.contains("/")
                        && !moduleName.contains(":")
                        && options.paths.isNullOrEmpty()
                        && options.rootDirs.isNullOrEmpty()
                        && options.rootDir == null
                        && options.moduleSuffixes.isNullOrEmpty()
                        && moduleName !in ambientModuleNames
                        && !bareDtsResolvableInNodeModules(moduleName)
                        && !hasNodeModulesPackage(moduleName)
                        && moduleName !in Checker.NODE_BUILTIN_MODULES
                    ) {
                        val jsonName = if (moduleName.endsWith(".json")) moduleName else "$moduleName.json"
                        val jsonInNodeModules = jsonModuleContents.keys.any {
                            it.endsWith("/node_modules/$jsonName") || it == "node_modules/$jsonName"
                        }
                        if (moduleName.endsWith(".json")) {
                            if (!jsonInNodeModules) emitTS2307(specifier, moduleName, source, fileName)
                        } else if (jsonInNodeModules) {
                            emitTS2307(specifier, moduleName, source, fileName)
                        }
                    }
                    // Skip TS2307 in multi-file with node resolution — resolveModuleSpecifier is too
                    // simplified for paths, symlinks, json, index resolution; causes FPs. The
                    // bare-specifier-missing case (B98.r2 attempt, reverted round 83) is NOT
                    // surgically tractable: even with @types/node_modules/builtin guards it FP'd on
                    // symlinked deps (moduleResolutionWithSymlinks), untyped modules
                    // (extendsUntypedModule), absolute-path specifiers (checkJsxNotSetError '/foo'),
                    // and some scoped packages — all rooted in the resolver's inability to model
                    // node_modules layout / untyped .js / symlinks.
                } else {
                    // B281: in single-file mode the flatten walker reaches imports INSIDE
                    // ambient module bodies — a bare specifier naming a TOP-LEVEL ambient
                    // module of the same file resolves (privacyGloImportParseErrors line
                    // 112: `require("glo_M2_public")` inside `declare module "use_…"`).
                    // In a MODULE file, top-level string modules are AUGMENTATIONS
                    // (unregistered) so TOP-LEVEL imports of them fail TS2307 — but an
                    // import inside an ambient BODY still resolves via the merged symbol.
                    val amInfo = fileAmbientModuleInfo(fileName)
                    if (moduleName in amInfo.first) continue
                    if (isSideEffectImport) {
                        emitTS2882(specifier, moduleName, source, fileName)
                    } else {
                        emitTS2307(specifier, moduleName, source, fileName)
                    }
                }
            }
            // B98.r3: relative dynamic-import `import('./x')` specifiers that resolve to nothing.
            // Walks all statements collecting StringLiteralNode args of `import(...)` calls; for each
            // RELATIVE specifier the index-aware resolver can't find, emits TS2307. Bare specifiers
            // are deliberately NOT checked (the B98.r2 dead-end — node_modules/untyped-.js/symlink
            // FPs). Gated to no path/rootDirs config + !noResolve to bound the FP surface;
            // applies to single-file too (where a relative target can never resolve).
            if (!options.noResolve
                && options.paths.isNullOrEmpty()
                && options.rootDirs.isNullOrEmpty()
                && options.rootDir == null
                && options.moduleSuffixes.isNullOrEmpty()
            ) {
                val dynSpecs = mutableListOf<StringLiteralNode>()
                // In JS-like files (.js/.jsx/.cjs/.mjs) TypeScript also treats `require("…")` calls
                // as CommonJS module references (even when `require` is a local parameter, as in
                // `noCrashOnParameterNamedRequire`). For .ts files `require` is an ordinary call, so
                // we only collect it for JS files to avoid FPs on `.ts` with a `declare function require`.
                collectDynamicImportSpecifiers(result.sourceFile.statements, dynSpecs, includeRequire = checker.isJsLikeFileName(fileName))
                for (spec in dynSpecs) {
                    val mod = spec.text
                    if (mod.endsWith(".json")) continue
                    val isRel = mod.startsWith("./") || mod.startsWith("../")
                    if (isRel) {
                        if (checker.resolveRelativeIncludingIndex(mod, fileName) == null
                            && mod !in ambientModuleNames
                            && mod !in dtsFileBaseNames
                        ) {
                            emitTS2307(spec, mod, source, fileName)
                        }
                    } else if (checker.isJsLikeFileName(fileName)
                        && !mod.startsWith("/")
                        && !mod.contains("/")
                        && !mod.contains(":")
                        && mod !in ambientModuleNames
                        && mod !in dtsFileBaseNames
                        && !bareDtsResolvableInNodeModules(mod)
                        && !hasNodeModulesPackage(mod)
                        && mod !in Checker.NODE_BUILTIN_MODULES
                        && checker.resolveModuleSpecifier(mod, null) == null
                    ) {
                        // B98.r169: a bare `require("X")` in a JS file whose single-segment
                        // specifier resolves to nothing (no node_modules package / ambient
                        // module / `.d.ts` / node builtin) — TypeScript emits TS2307 (it treats
                        // CommonJS `require` in JS files as a module reference). Mirrors the
                        // r107/r168 bare-import gate's FP-safe guards (single-segment-only, no
                        // `/`/`:`, every resolution path checked). `tslibInJs`.
                        emitTS2307(spec, mod, source, fileName)
                    }
                }
            }
        }
    }

    /**
     * Collect the string-literal specifiers of every dynamic `import("...")` call reachable from
     * [statements] into [out]. Mirrors the Emitter's `expressionContainsDynamicImport` traversal
     * (incl. the iterative BinaryExpression right-spine walk required to survive deeply-nested
     * `a+b+c+…` chains) but ACCUMULATES the specifier nodes instead of returning a boolean. Used by
     * the B98.r3 relative-dynamic-import TS2307 check. Non-string-literal args (`import(someVar)`)
     * are ignored.
     */
    private fun collectDynamicImportSpecifiers(
        statements: List<Statement>, out: MutableList<StringLiteralNode>, includeRequire: Boolean = false,
    ) {
        for (s in statements) collectDynImportInStatement(s, out, includeRequire)
    }

    private fun collectDynImportInStatement(stmt: Statement, out: MutableList<StringLiteralNode>, includeRequire: Boolean) {
        when (stmt) {
            is ExpressionStatement -> collectDynImportInExpr(stmt.expression, out, includeRequire)
            is VariableStatement -> stmt.declarationList.declarations.forEach {
                it.initializer?.let { init -> collectDynImportInExpr(init, out, includeRequire) }
            }
            is ReturnStatement -> stmt.expression?.let { collectDynImportInExpr(it, out, includeRequire) }
            is ThrowStatement -> stmt.expression?.let { collectDynImportInExpr(it, out, includeRequire) }
            is ExportAssignment -> collectDynImportInExpr(stmt.expression, out, includeRequire)
            is Block -> collectDynamicImportSpecifiers(stmt.statements, out, includeRequire)
            is IfStatement -> {
                collectDynImportInExpr(stmt.expression, out, includeRequire)
                collectDynImportInStatement(stmt.thenStatement, out, includeRequire)
                stmt.elseStatement?.let { collectDynImportInStatement(it, out, includeRequire) }
            }
            is WhileStatement -> { collectDynImportInExpr(stmt.expression, out, includeRequire); collectDynImportInStatement(stmt.statement, out, includeRequire) }
            is DoStatement -> { collectDynImportInExpr(stmt.expression, out, includeRequire); collectDynImportInStatement(stmt.statement, out, includeRequire) }
            is ForStatement -> collectDynImportInStatement(stmt.statement, out, includeRequire)
            is ForOfStatement -> collectDynImportInStatement(stmt.statement, out, includeRequire)
            is ForInStatement -> collectDynImportInStatement(stmt.statement, out, includeRequire)
            is LabeledStatement -> collectDynImportInStatement(stmt.statement, out, includeRequire)
            is TryStatement -> {
                collectDynamicImportSpecifiers(stmt.tryBlock.statements, out, includeRequire)
                stmt.catchClause?.block?.statements?.let { collectDynamicImportSpecifiers(it, out, includeRequire) }
                stmt.finallyBlock?.statements?.let { collectDynamicImportSpecifiers(it, out, includeRequire) }
            }
            is SwitchStatement -> stmt.caseBlock.forEach { clause ->
                when (clause) {
                    is CaseClause -> collectDynamicImportSpecifiers(clause.statements, out, includeRequire)
                    is DefaultClause -> collectDynamicImportSpecifiers(clause.statements, out, includeRequire)
                    else -> {}
                }
            }
            is FunctionDeclaration -> stmt.body?.statements?.let { collectDynamicImportSpecifiers(it, out, includeRequire) }
            else -> {}
        }
    }

    private fun collectDynImportInExpr(expr: Expression, out: MutableList<StringLiteralNode>, includeRequire: Boolean) {
        if (expr is CallExpression && expr.arguments.size == 1) {
            val callee = (expr.expression as? Identifier)?.text
            if (callee == "import" || (includeRequire && callee == "require")) {
                (expr.arguments[0] as? StringLiteralNode)?.let { out.add(it) }
                return
            }
        }
        when (expr) {
            is CallExpression -> { collectDynImportInExpr(expr.expression, out, includeRequire); expr.arguments.forEach { collectDynImportInExpr(it, out, includeRequire) } }
            is PropertyAccessExpression -> collectDynImportInExpr(expr.expression, out, includeRequire)
            is ElementAccessExpression -> { collectDynImportInExpr(expr.expression, out, includeRequire); collectDynImportInExpr(expr.argumentExpression, out, includeRequire) }
            is ArrowFunction -> when (val body = expr.body) {
                is Expression -> collectDynImportInExpr(body, out, includeRequire)
                is Block -> collectDynamicImportSpecifiers(body.statements, out, includeRequire)
                else -> {}
            }
            is FunctionExpression -> collectDynamicImportSpecifiers(expr.body.statements, out, includeRequire)
            is AwaitExpression -> collectDynImportInExpr(expr.expression, out, includeRequire)
            is YieldExpression -> expr.expression?.let { collectDynImportInExpr(it, out, includeRequire) }
            is ParenthesizedExpression -> collectDynImportInExpr(expr.expression, out, includeRequire)
            is ConditionalExpression -> { collectDynImportInExpr(expr.condition, out, includeRequire); collectDynImportInExpr(expr.whenTrue, out, includeRequire); collectDynImportInExpr(expr.whenFalse, out, includeRequire) }
            is NewExpression -> { collectDynImportInExpr(expr.expression, out, includeRequire); expr.arguments?.forEach { collectDynImportInExpr(it, out, includeRequire) } }
            is SpreadElement -> collectDynImportInExpr(expr.expression, out, includeRequire)
            is ArrayLiteralExpression -> expr.elements.forEach { collectDynImportInExpr(it, out, includeRequire) }
            is PrefixUnaryExpression -> collectDynImportInExpr(expr.operand, out, includeRequire)
            is PostfixUnaryExpression -> collectDynImportInExpr(expr.operand, out, includeRequire)
            is BinaryExpression -> {
                // Iterative right-spine walk — a naive recursive descent StackOverflows on the
                // deeply-nested `a+b+c+…` chain in binderBinaryExpressionStress (see walker gotcha).
                var current: Expression = expr
                while (current is BinaryExpression) {
                    collectDynImportInExpr(current.right, out, includeRequire)
                    current = current.left
                }
                collectDynImportInExpr(current, out, includeRequire)
            }
            is ObjectLiteralExpression -> expr.properties.forEach { prop ->
                when (prop) {
                    is PropertyAssignment -> collectDynImportInExpr(prop.initializer, out, includeRequire)
                    is MethodDeclaration -> prop.body?.statements?.let { collectDynamicImportSpecifiers(it, out, includeRequire) }
                    is GetAccessor -> prop.body?.statements?.let { collectDynamicImportSpecifiers(it, out, includeRequire) }
                    is SetAccessor -> prop.body?.statements?.let { collectDynamicImportSpecifiers(it, out, includeRequire) }
                    else -> {}
                }
            }
            else -> {}
        }
    }

    /**
     * Resolve a relative specifier using `moduleSuffixes`: for each suffix, look for a file
     * matching `{path}{suffix}.ts|.tsx|.d.ts|.json`. Suffixes of "" fall back to the plain name.
     * Returns the first match or null.
     */
    private fun resolveWithModuleSuffixes(specifier: String, contextFileName: String, suffixes: List<String>): String? {
        if (!specifier.startsWith("./") && !specifier.startsWith("../")) return null
        val dir = contextFileName.substringBeforeLast('/', "")
        val basePath = if (dir.isEmpty()) specifier else "$dir/${specifier.removePrefix("./")}"
        val normalized = checker.normalizePath(basePath)
        // Strip explicit .js/.jsx extension — TypeScript resolves these to .ts/.tsx/.d.ts
        // (or .js/.jsx under allowJs), so the base used for suffix-matching is without ext.
        val withoutJs = when {
            normalized.endsWith(".js") -> normalized.removeSuffix(".js")
            normalized.endsWith(".jsx") -> normalized.removeSuffix(".jsx")
            else -> normalized
        }
        // Try with and without a leading "/" prefix to match how fileResults keys are stored
        // (root-file layouts like "@filename: /foo.ts" produce keys starting with "/").
        val bases = buildList {
            add(withoutJs)
            add(withoutJs.removePrefix("./"))
            if (!withoutJs.startsWith("/")) add("/$withoutJs")
        }
        val allowJs = options.allowJs == true || options.checkJs == true
        for (suffix in suffixes) {
            for (base in bases) {
                val candidates = buildList {
                    add("$base$suffix.ts")
                    add("$base$suffix.tsx")
                    add("$base$suffix.d.ts")
                    add("$base$suffix.json")
                    add("$base/index$suffix.ts")
                    add("$base/index$suffix.tsx")
                    add("$base/index$suffix.d.ts")
                    if (allowJs) {
                        add("$base$suffix.js")
                        add("$base$suffix.jsx")
                        add("$base/index$suffix.js")
                        add("$base/index$suffix.jsx")
                    }
                }
                for (c in candidates) {
                    if (c in checker.fileResults) return c
                }
            }
        }
        return null
    }

    /**
     * TS2439: "Import or export declaration in an ambient module declaration cannot reference
     * module through relative module name." — fires for `import Y = require("./Z")` or
     * `import ... from "./Z"` / `export ... from "./Z"` nested inside `declare module "X" { }`.
     */
    fun checkRelativeImportsInAmbientModules() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            val source = result.sourceFile.text
            val hostIsModule = checker.isModuleFile(result.sourceFile.statements)
            for (stmt in result.sourceFile.statements) {
                if (stmt is ModuleDeclaration && stmt.name is StringLiteralNode) {
                    // A `declare module "X"` with a RELATIVE specifier inside a module file
                    // is always a module augmentation — any external-module import/export
                    // inside it gets TS2667. Non-relative names are ambient module
                    // definitions (or bare-name augmentations of imported modules, which
                    // we don't currently distinguish); imports inside those are allowed
                    // at this level — TS2307/TS2664 handle those separately.
                    val outerName = (stmt.name).text
                    val isRelativeAugmentation = hostIsModule &&
                        (outerName.startsWith("./") || outerName.startsWith("../"))
                    val body = stmt.body
                    if (body is ModuleBlock) {
                        for (innerStmt in body.statements) {
                            // ExportAssignment (`export = X` / `export default X`) — no module specifier,
                            // but disallowed in relative augmentations with TS2666 at the `export` keyword.
                            if (innerStmt is ExportAssignment && isRelativeAugmentation) {
                                var kwStart = innerStmt.pos
                                while (kwStart < source.length && source[kwStart].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) kwStart++
                                val (kwLine, kwChar) = checker.getLineAndCharacterOfPosition(source, kwStart)
                                checker.diagnostics.add(Diagnostic(
                                    message = "Exports and export assignments are not permitted in module augmentations.",
                                    category = DiagnosticCategory.Error,
                                    code = 2666,
                                    fileName = fileName,
                                    line = kwLine,
                                    character = kwChar,
                                    start = kwStart,
                                    length = 6,
                                ))
                                continue
                            }
                            val specifier: Expression? = when (innerStmt) {
                                is ImportDeclaration -> innerStmt.moduleSpecifier
                                is ExportDeclaration -> innerStmt.moduleSpecifier
                                is ImportEqualsDeclaration -> {
                                    val ref = innerStmt.moduleReference
                                    if (ref is ExternalModuleReference) ref.expression else null
                                }
                                else -> null
                            }
                            val moduleName = (specifier as? StringLiteralNode)?.text ?: continue
                            if (isRelativeAugmentation) {
                                // Squiggle only the `import` / `export` keyword (6 chars).
                                var kwStart = innerStmt.pos
                                while (kwStart < source.length && source[kwStart].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) kwStart++
                                val kwLength = 6
                                // TS2666 for `export ... from "..."`, TS2667 for `import ... from "..."`.
                                // Both forms are disallowed in module augmentations but use different codes.
                                val isExport = innerStmt is ExportDeclaration
                                val (kwLine, kwChar) = checker.getLineAndCharacterOfPosition(source, kwStart)
                                checker.diagnostics.add(Diagnostic(
                                    message = if (isExport)
                                        "Exports and export assignments are not permitted in module augmentations."
                                    else
                                        "Imports are not permitted in module augmentations. Consider moving them to the enclosing external module.",
                                    category = DiagnosticCategory.Error,
                                    code = if (isExport) 2666 else 2667,
                                    fileName = fileName,
                                    line = kwLine,
                                    character = kwChar,
                                    start = kwStart,
                                    length = kwLength,
                                ))
                                // TypeScript ALSO emits TS2307 for relative specifiers inside
                                // augmentations — the augmented module's scope doesn't offer
                                // normal relative resolution. checkUnresolvedModules won't flag
                                // this (the file exists on disk in the multi-file layout), so
                                // emit it here on the specifier.
                                if (moduleName.startsWith("./") || moduleName.startsWith("../")) {
                                    val specStart = specifier.pos
                                    val specLen = moduleName.length + 2 // +2 for quotes
                                    val (specLine, specChar) = checker.getLineAndCharacterOfPosition(source, specStart)
                                    checker.diagnostics.add(Diagnostic(
                                        message = "Cannot find module '$moduleName' or its corresponding type declarations.",
                                        category = DiagnosticCategory.Error,
                                        code = 2307,
                                        fileName = fileName,
                                        line = specLine,
                                        character = specChar,
                                        start = specStart,
                                        length = specLen,
                                    ))
                                }
                            } else if (moduleName.startsWith("./") || moduleName.startsWith("../")) {
                                checker.emitStatementLineDiagnostic(
                                    innerStmt, source, fileName, 2439,
                                    "Import or export declaration in an ambient module declaration cannot reference module through relative module name.",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Flatten top-level import/export/import-equals statements plus those nested inside
     * `declare module "X" { ... }` augmentations (ModuleDeclaration with StringLiteralNode name).
     * Identifier-named namespaces (`namespace N { ... }`, `declare global { ... }`) are NOT
     * recursed into because imports there use different diagnostics (TS1147/TS2667/TS1194).
     */
    /** B281: (REGISTERED top-level ambient module names — empty for external-module
     *  files where top-level `declare module "X"` is an augmentation; file declares ANY
     *  string-named module; ALL top-level string-module names regardless of file kind). */
    fun fileAmbientModuleInfo(fileName: String): Triple<Set<String>, Boolean, Set<String>> =
        fileAmbientModuleInfoCache.getOrPut(fileName) {
            val result = binderResults.firstOrNull { it.sourceFile.fileName == fileName }
                ?: return@getOrPut Triple(emptySet(), false, emptySet())
            val topLevel = mutableSetOf<String>()
            var hasAny = false
            // In an EXTERNAL MODULE file, top-level `declare module "X"` is an
            // AUGMENTATION (registers nothing) — only script files define ambient
            // modules (tsc collectModuleReferences).
            val isScript = !checker.isModuleFile(result.sourceFile.statements)
            fun walk(stmts: List<Statement>, top: Boolean) {
                for (s in stmts) {
                    if (s !is ModuleDeclaration) continue
                    val n = s.name
                    if (n is StringLiteralNode) {
                        hasAny = true
                        if (top) topLevel.add(n.text)
                    }
                    (s.body as? ModuleBlock)?.let { walk(it.statements, false) }
                }
            }
            walk(result.sourceFile.statements, true)
            Triple(if (isScript) topLevel else emptySet(), hasAny, topLevel)
        }

    /**
     * B281: TS2435 "Ambient modules cannot be nested in other modules or namespaces."
     * (at the module's string-literal name, for every string-named ModuleDeclaration
     * nested inside ANY module/namespace body) + TS2668 "'export' modifier cannot be
     * applied to ambient modules and module augmentations since they are always
     * visible." (at the `export` keyword, for every exported string-named module —
     * top-level or nested). Purely syntactic (privacyImportParseErrors family).
     */
    fun checkNestedAmbientModules() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            val isScript = !checker.isModuleFile(result.sourceFile.statements)
        for (stmt in result.sourceFile.statements) namWalk(stmt, 0, isScript, source, fileName)
        }
    }

    /** [container]: 0 = file top level, 1 = immediate child of a TOP-LEVEL string-named
     *  module in a SCRIPT file (an augmentation position — legal, no TS2435; in a
     *  MODULE file only top-level string modules are augmentations), 2 = other. */
    private fun namWalk(stmt: Statement, container: Int, isScript: Boolean, source: String, fileName: String) {
        if (stmt !is ModuleDeclaration) return
        val name = stmt.name
        if (name is StringLiteralNode) {
            if (ModifierFlag.Export in stmt.modifiers) {
                // tsc anchors TS2668 at the FIRST modifier keyword with its own length
                // (`export declare` → at `export` len 6; `declare export` → at `declare`
                // len 7). The node's pos may sit AFTER the modifiers — scan the line.
                val lineStart = source.lastIndexOf('\n', name.pos).let { if (it < 0) 0 else it + 1 }
                val exportAt = checker.srcIndexOf(source, "export", lineStart)
                val declareAt = checker.srcIndexOf(source, "declare", lineStart)
                val (kwStart, kwLen) = when {
                    declareAt in lineStart until name.pos && (exportAt < lineStart || declareAt < exportAt) ->
                        declareAt to 7
                    exportAt in lineStart until name.pos -> exportAt to 6
                    else -> -1 to 0
                }
                if (kwStart >= 0) {
                    val (line, ch) = checker.getLineAndCharacterOfPosition(source, kwStart)
                    checker.diagnostics.add(Diagnostic(
                        message = "'export' modifier cannot be applied to ambient modules and module augmentations since they are always visible.",
                        category = DiagnosticCategory.Error, code = 2668,
                        fileName = fileName, line = line, character = ch,
                        start = kwStart, length = kwLen,
                    ))
                }
            }
            if (container == 2) {
                val len = (name.rawText?.length ?: name.text.length) + 2
                val (line, ch) = checker.getLineAndCharacterOfPosition(source, name.pos)
                checker.diagnostics.add(Diagnostic(
                    message = "Ambient modules cannot be nested in other modules or namespaces.",
                    category = DiagnosticCategory.Error, code = 2435,
                    fileName = fileName, line = line, character = ch,
                    start = name.pos, length = len,
                ))
            }
            val childContainer = if (container == 0 && isScript) 1 else 2
            (stmt.body as? ModuleBlock)?.statements?.forEach { namWalk(it, childContainer, isScript, source, fileName) }
        } else {
            (stmt.body as? ModuleBlock)?.statements?.forEach { namWalk(it, 2, isScript, source, fileName) }
        }
    }

    private fun flattenImportLikeStatements(
        statements: List<Statement>,
        ambientBodyPositions: MutableSet<Int>? = null,
    ): List<Statement> {
        val out = mutableListOf<Statement>()
        for (s in statements) {
            when (s) {
                is ImportDeclaration, is ExportDeclaration, is ImportEqualsDeclaration -> out.add(s)
                is ModuleDeclaration -> {
                    if (s.name is StringLiteralNode) {
                        val body = s.body
                        if (body is ModuleBlock) {
                            val inner = flattenImportLikeStatements(body.statements, ambientBodyPositions)
                            if (ambientBodyPositions != null) inner.forEach { ambientBodyPositions.add(it.pos) }
                            out.addAll(inner)
                        }
                    }
                }
                else -> {}
            }
        }
        return out
    }

    /**
     * TS2306: "File 'X' is not a module." for `import X = require("./Y")` where Y has no
     * imports/exports/exported declarations (i.e. is a script file, not a module).
     */
    fun checkImportEqualsRequireOfNonModule() {
        if (binderResults.size <= 1 && !isMultiFileSource) return
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            for (stmt in flattenImportLikeStatements(result.sourceFile.statements)) {
                val specifier: StringLiteralNode = when (stmt) {
                    is ImportEqualsDeclaration -> {
                        val ref = stmt.moduleReference as? ExternalModuleReference ?: continue
                        ref.expression as? StringLiteralNode ?: continue
                    }
                    is ExportDeclaration -> {
                        // Only bare `export * from "..."` — named re-exports already surface
                        // per-name errors via checkUnresolvedModules / named-binding paths.
                        if (stmt.exportClause != null) continue
                        stmt.moduleSpecifier as? StringLiteralNode ?: continue
                    }
                    else -> continue
                }
                val moduleName = specifier.text
                val isRelative = moduleName.startsWith("./") || moduleName.startsWith("../")
                if (!isRelative) continue  // Skip non-relative — likely resolves via node_modules etc.
                val resolved = checker.resolveModuleSpecifierStrictRelative(moduleName, fileName) ?: continue
                val targetResult = binderResults.firstOrNull { it.sourceFile.fileName == resolved } ?: continue
                if (checker.isModuleFile(targetResult.sourceFile.statements)) continue
                // Compute display filename — basename of resolved path
                val displayName = resolved.substringAfterLast('/')
                val start = specifier.pos
                val length = moduleName.length + 2 // +2 for quotes
                val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                checker.diagnostics.add(Diagnostic(
                    message = "File '$displayName' is not a module.",
                    category = DiagnosticCategory.Error,
                    code = 2306,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = start,
                    length = length,
                ))
            }
        }
    }

    /**
     * TS2306: "File 'X' is not a module." for `import * as Y from 'pkg'` where the
     * bare specifier resolves via node_modules walk-up to a file in `fileResults` that
     * has no imports/exports (script-shaped). Mirror of [checkImportEqualsRequireOfNonModule]
     * but for the namespace-import-of-bare-specifier path that requires node_modules
     * resolution rather than relative resolution. Display name is the absolute resolved
     * path (matching TypeScript's baseline format for this diagnostic).
     */
    fun checkNamespaceImportOfNonModule() {
        if (binderResults.size <= 1 && !isMultiFileSource) return
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            for (stmt in flattenImportLikeStatements(result.sourceFile.statements)) {
                if (stmt !is ImportDeclaration) continue
                val clause = stmt.importClause ?: continue
                if (clause.namedBindings !is NamespaceImport) continue
                val specifier = stmt.moduleSpecifier as? StringLiteralNode ?: continue
                val moduleName = specifier.text
                if (moduleName.startsWith("./") || moduleName.startsWith("../")) continue
                val resolved = resolveBareSpecifierViaNodeModules(moduleName, fileName) ?: continue
                val targetResult = binderResults.firstOrNull { it.sourceFile.fileName == resolved } ?: continue
                if (checker.isModuleFile(targetResult.sourceFile.statements)) continue
                val start = specifier.pos
                val length = moduleName.length + 2
                val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                checker.diagnostics.add(Diagnostic(
                    message = "File '$resolved' is not a module.",
                    category = DiagnosticCategory.Error,
                    code = 2306,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = start,
                    length = length,
                ))
            }
        }
    }

    /**
     * Walk up from [contextFileName]'s directory looking for `node_modules/<specifier>/index.d.ts`
     * or `node_modules/@types/<specifier>/index.d.ts` (or `.ts` variants). Returns the first
     * matching entry in [fileResults], or null. Closer (more-deeply-nested) node_modules
     * directories win, matching Node-style resolution.
     */
    fun resolveBareSpecifierViaNodeModules(specifier: String, contextFileName: String): String? {
        var dir = contextFileName.substringBeforeLast('/', "")
        while (true) {
            val prefix = dir
            val candidates = listOf(
                "$prefix/node_modules/$specifier/index.d.ts",
                "$prefix/node_modules/$specifier/index.ts",
                "$prefix/node_modules/@types/$specifier/index.d.ts",
                "$prefix/node_modules/@types/$specifier/index.ts",
            )
            for (c in candidates) {
                if (c in checker.fileResults) return c
            }
            if (dir.isEmpty()) break
            dir = dir.substringBeforeLast('/', "")
        }
        return null
    }

    fun emitTS2307(specifier: Expression, moduleName: String, source: String, fileName: String) {
        // (CHK.202) a specifier the crawl resolved to an untyped `node_modules` module.
        if (untypedModuleResolutions[fileName]?.containsKey(moduleName) == true) return
        val start = specifier.pos
        val length = moduleName.length + 2 // +2 for quotes
        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)

        // (LEGACY.1)(e) TS2792 (*Did you mean to set the 'moduleResolution' option to
        // 'nodenext'*) was the classic-resolution wording; TypeScript 7 has no classic
        // resolution and no emitter for it — every resolution is node-based there.
        val code: Int
        val message: String
        // 17.210: When the unresolved module specifier names a Node.js
        // built-in (`fs`, `path`, `http`, …, also `node:`-prefixed forms),
        // emit TS2591 with an @types/node hint instead of the generic
        // TS2307 — TypeScript's standard guidance for "you probably forgot
        // @types/node". The diagnostic uses "name" not "module" to match
        // baseline format.
        val barenameForNodeCheck = moduleName.removePrefix("node:")
        val isNodeBuiltin = moduleName.startsWith("node:") || barenameForNodeCheck in Checker.NODE_BUILTIN_MODULES
        if (isNodeBuiltin) {
            code = 2591
            message = "Cannot find name '$moduleName'. Do you need to install type definitions for node? Try `npm i --save-dev @types/node` and then add 'node' to the types field in your tsconfig."
        } else {
            code = 2307
            message = "Cannot find module '$moduleName' or its corresponding type declarations."
        }
        checker.diagnostics.add(Diagnostic(
            message = message,
            category = DiagnosticCategory.Error,
            code = code,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    /**
     * Checks whether the test-file layout contains a package matching [pkgName]
     * under a node_modules directory — i.e. any file in `fileResults.keys` whose
     * path contains `/node_modules/<pkgName>/` (with any depth of nesting before
     * `node_modules`). Used to suppress TS2307/TS2882 for bare specifiers that
     * TypeScript would resolve via node_modules.
     */
    /**
     * B165: true when the bare package [pkgName] has a `node_modules/<pkg>/package.json` (or
     * `@types/<pkg>/package.json`) whose `exports` field is PRESENT, NON-NULL, and a STRING
     * that names no existing file in the package directory. Node's `exports` is exhaustive —
     * when present it disables `main` and the `index.d.ts` fallback, so such a package is
     * unresolvable under nodenext. Absent / `null` / object-form / wildcard / directory
     * targets all return false (FP-safe: only the provably-broken string form fires).
     */
    private fun brokenStringExportsPackage(pkgName: String): Boolean {
        for ((fn, content) in jsonModuleContents) {
            val isPkgJson = fn.endsWith("/node_modules/@types/$pkgName/package.json") ||
                fn.endsWith("/node_modules/$pkgName/package.json") ||
                fn == "node_modules/@types/$pkgName/package.json" ||
                fn == "node_modules/$pkgName/package.json"
            if (!isPkgJson) continue
            val m = Regex("\"exports\"\\s*:\\s*").find(content) ?: continue
            val rest = content.substring(m.range.last + 1).trimStart()
            if (!rest.startsWith("\"")) continue // null / object form — not modeled
            val target = rest.drop(1).substringBefore("\"")
            if (target.contains('*') || target.endsWith("/")) continue
            val pkgDir = fn.removeSuffix("package.json")
            val base = pkgDir + target.removePrefix("./")
            // A `.js`/`.mjs`/`.cjs` exports target resolves TYPES via the declaration-file
            // substitution (`entrypoint.js` -> `entrypoint.d.ts`), per node16/nodenext rules.
            val candidates = buildList {
                add(base); add("$base.ts"); add("$base.tsx"); add("$base.d.ts")
                add("$base.js"); add("$base.json")
                add("$base/index.ts"); add("$base/index.d.ts")
                if (base.endsWith(".js")) add(base.removeSuffix(".js") + ".d.ts")
                if (base.endsWith(".mjs")) add(base.removeSuffix(".mjs") + ".d.mts")
                if (base.endsWith(".cjs")) add(base.removeSuffix(".cjs") + ".d.cts")
            }
            val exists = candidates.any { it in checker.fileResults || it in jsonModuleContents }
            if (!exists) return true
        }
        return false
    }

    /**
     * B166: can a SCOPED specifier `@s/p` resolve under the typeRoots-aware lookup model?
     * Mechanisms (mirroring tsc's trace for moduleResolutionAsTypeReferenceDirectiveScoped):
     * each explicit typeRoot resolves the LITERAL scoped name — except a root that is itself
     * an `@types` directory, which resolves only the MANGLED `s__p` form; plain `node_modules`
     * dirs resolve the literal name (the needle inherently can't match inside
     * `node_modules/@types/...` — the extra segment breaks it); `node_modules/@types` resolves
     * only the mangled form.
     */
    private fun scopedResolvableForImport(moduleName: String): Boolean {
        val mangled = moduleName.removePrefix("@").replace("/", "__")
        val roots = options.typeRoots.orEmpty().map { it.trimEnd('/') }
        for (fn in checker.fileResults.keys) {
            for (root in roots) {
                val name = if (root.endsWith("/@types") || root == "@types") mangled else moduleName
                if (fn.startsWith("$root/$name/") || fn == "$root/$name.d.ts") return true
            }
            if (fn.contains("/node_modules/$moduleName/") || fn.startsWith("node_modules/$moduleName/") ||
                fn.endsWith("/node_modules/$moduleName.d.ts")
            ) return true
            if (fn.contains("/node_modules/@types/$mangled/") || fn.startsWith("node_modules/@types/$mangled/") ||
                fn.endsWith("/node_modules/@types/$mangled.d.ts")
            ) return true
        }
        return false
    }

    fun hasNodeModulesPackage(pkgName: String): Boolean {
        val needle = "/node_modules/$pkgName/"
        for (fn in checker.fileResults.keys) {
            if (needle in fn || fn.startsWith("node_modules/$pkgName/")) return true
        }
        return false
    }

    /**
     * B524: like [hasNodeModulesPackage] but ALSO consults the RAW input file set
     * ([allInputFileNames]) — which includes untyped `.js` files that are NOT bound into
     * [fileResults]. Used by the commonjs bare-specifier TS2307 gate so an
     * `import Foo from "foo"` where `/node_modules/foo/index.js` exists as an untyped
     * module (extendsUntypedModule) does NOT FP TS2307.
     */
    private fun bareModulePackageInAnyInput(pkgName: String): Boolean {
        if (hasNodeModulesPackage(pkgName)) return true
        val needle = "/node_modules/$pkgName/"
        for (fn in allInputFileNames) {
            if (needle in fn || fn.startsWith("node_modules/$pkgName/")) return true
        }
        return false
    }

    /**
     * B524: a bare specifier `<pkg>` may resolve via a node_modules SYMLINK whose target is
     * a real source package directory `<dir>/<pkg>/index.{ts,tsx,d.ts}` — the symlink path
     * is NOT materialized in [fileResults]/[allInputFileNames] (only its target is), so the
     * node_modules-package checks miss it (moduleResolutionWithSymlinks). Suppress TS2307
     * when such a target directory exists anywhere. UNDER-firing (a coincidental
     * `<pkg>/index.ts` dir with no symlink) is the SAFE direction — we never fire TS2307 for
     * bare commonjs specifiers today, so an FN here is harmless.
     */
    private fun bareModuleSymlinkTargetDir(pkgName: String): Boolean {
        val exts = listOf("/$pkgName/index.ts", "/$pkgName/index.tsx", "/$pkgName/index.d.ts")
        for (fn in checker.fileResults.keys) {
            if (exts.any { fn.endsWith(it) }) return true
        }
        return false
    }

    /**
     * True when a bare specifier [moduleName] could legitimately resolve to a `.d.ts` declaration
     * file under `node_modules/` (incl. `@types/`). Used by the B98.r107 single-segment bare-specifier
     * TS2307 gate to suppress the diagnostic ONLY for real package typings — a plain SIBLING `.d.ts`
     * (`a.d.ts` next to `b.ts`) is NOT bare-resolvable under node10 and must NOT suppress. Matches
     * either `node_modules/.../<name>.d.ts` (a flat `.d.ts`) or `node_modules/<name>/...` /
     * `node_modules/@types/<name>/...index.d.ts` (a package directory of typings).
     */
    private fun bareDtsResolvableInNodeModules(moduleName: String): Boolean {
        for (fn in checker.fileResults.keys) {
            if (!checker.isDtsFile(fn)) continue
            val inNodeModules = "/node_modules/" in fn || fn.startsWith("node_modules/")
            if (!inNodeModules) continue
            val base = fn.substringAfterLast("/").removeSuffix(".d.ts")
            if (base == moduleName) return true
            if ("/node_modules/$moduleName/" in fn || fn.startsWith("node_modules/$moduleName/")) return true
            if ("/node_modules/@types/$moduleName/" in fn || fn.startsWith("node_modules/@types/$moduleName/")) return true
        }
        return false
    }

    private fun emitTS2882(specifier: Expression, moduleName: String, source: String, fileName: String) {
        // (CHK.202) a side-effect import of an untyped `node_modules` module resolves.
        if (untypedModuleResolutions[fileName]?.containsKey(moduleName) == true) return
        val start = specifier.pos
        val length = moduleName.length + 2 // +2 for quotes
        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
        checker.diagnostics.add(Diagnostic(
            message = "Cannot find module or type declarations for side-effect import of '$moduleName'.",
            category = DiagnosticCategory.Error,
            code = 2882,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    /**
     * TS2732: "Cannot find module 'X.json'. Consider using '--resolveJsonModule' to import
     * module with '.json' extension." — fires when a `.json` specifier is imported while
     * `resolveJsonModule` is disabled, regardless of whether the file exists.
     */
    private fun emitTS2732(specifier: Expression, moduleName: String, source: String, fileName: String) {
        val start = specifier.pos
        val length = moduleName.length + 2 // +2 for quotes
        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
        checker.diagnostics.add(Diagnostic(
            message = "Cannot find module '$moduleName'. Consider using '--resolveJsonModule' to import module with '.json' extension.",
            category = DiagnosticCategory.Error,
            code = 2732,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // -----------------------------------------------------------------------
    // JSX-not-set import check (TS6142)
    // -----------------------------------------------------------------------

    /**
     * TS6142: "Module 'X' was resolved to 'Y', but '--jsx' is not set." — fires for
     * cross-file imports that resolve to a `.jsx` or `.tsx` target while `options.jsx`
     * is unset. Multi-file mode only.
     */
    fun checkJsxImportResolutions() {
        if (binderResults.size <= 1 && !isMultiFileSource) return
        val jsxUnset = options.jsx.let { it.isNullOrBlank() || it.equals("none", ignoreCase = true) }
        if (!jsxUnset) return
        // (INC.58) The ONLY files this pass can ever resolve to.
        //
        // Every non-null return of [resolveJsxTsxCandidate] is a member of
        // `fileResults` whose name ends in `.jsx` or `.tsx` — the direct probes
        // build their candidate as `"…$ext"` and test `in fileResults`, and the
        // suffix scan returns a key matching `"/$base$ext"`. So a program with no
        // such file cannot produce TS6142 at all, and this whole pass is a no-op
        // for it: the common case, since the pass runs precisely when `--jsx` is
        // UNSET.
        //
        // Built in `fileResults.keys` order and consulted in that order below, so
        // the FIRST-MATCH the suffix scan returns is unchanged — the scan is
        // order-sensitive and this filter is not allowed to reorder it.
        //
        // It is a local rather than a field on purpose: (INC.53) measured
        // `Checker`'s ~494 property initializers at 16-30 ms on EVERY build, and a
        // whole-program index added there is invisible to `--passTiming`,
        // `cost_gate.py` and every diagnostic. Here it costs one O(files) scan
        // inside the pass that needs it, and only when `--jsx` is unset.
        val jsxTsxFileNames = checker.fileResults.keys.filter {
            it.endsWith(".jsx") || it.endsWith(".tsx")
        }
        if (jsxTsxFileNames.isEmpty()) return
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            for (stmt in flattenImportLikeStatements(result.sourceFile.statements)) {
                val specifier = when (stmt) {
                    is ImportDeclaration -> stmt.moduleSpecifier
                    is ExportDeclaration -> stmt.moduleSpecifier
                    is ImportEqualsDeclaration -> {
                        val ref = stmt.moduleReference
                        if (ref is ExternalModuleReference) ref.expression else null
                    }
                    else -> null
                } ?: continue
                val moduleName = (specifier as? StringLiteralNode)?.text ?: continue
                // Skip specifiers that already carry the extension — TS6142 is only
                // emitted when the extension was inferred by resolution, not stated.
                if (moduleName.endsWith(".jsx") || moduleName.endsWith(".tsx")) continue
                val resolved = resolveJsxTsxCandidate(moduleName, fileName, jsxTsxFileNames) ?: continue
                emitTS6142(specifier, moduleName, resolved, source, fileName)
            }
        }
    }

    /**
     * Scoped resolver for [checkJsxImportResolutions] that tries `.jsx` / `.tsx`
     * suffixes. Distinct from [resolveModuleSpecifier] which intentionally skips
     * these to avoid TS2459 FPs across the rest of the checker — here the result
     * is only ever consumed by TS6142 emission, so the narrower scope is safe.
     */
    private fun resolveJsxTsxCandidate(
        moduleName: String,
        contextFileName: String,
        jsxTsxFileNames: List<String>,
    ): String? {
        val isRelative = moduleName.startsWith("./") || moduleName.startsWith("../")
        // Relative: resolve against the importing file's directory first.
        if (isRelative) {
            val dir = contextFileName.substringBeforeLast('/', "")
            val base = moduleName.removePrefix("./")
            val resolvedBase = if (dir.isEmpty()) base else "$dir/$base"
            val normalized = checker.normalizePath(resolvedBase)
            for (ext in listOf(".jsx", ".tsx")) {
                val candidate = "$normalized$ext"
                if (candidate in checker.fileResults) return candidate
                if (candidate.removePrefix("./") in checker.fileResults) return candidate.removePrefix("./")
            }
        }
        // Fallback: try absolute / non-relative match against fileResults keys.
        // Test fixtures often use absolute-style paths like `/foo` → `/foo.jsx`.
        val base = moduleName.removePrefix("./").removePrefix("../")
        for (ext in listOf(".jsx", ".tsx")) {
            val candidates = listOf("$moduleName$ext", "./$base$ext", "$base$ext", "/$base$ext")
            for (candidate in candidates) {
                if (candidate in checker.fileResults) return candidate
            }
            // Suffix-match: any file ending with /base + ext.
            //
            // (INC.58) Scanned over the `.jsx`/`.tsx` files ONLY, not over every
            // file in the program. Both arms of the test can match only a name
            // ending in `$ext`, so the filtered scan returns exactly what the full
            // one did — in the same order, hence the same FIRST match — while the
            // population it walks is normally empty. Over `fileResults.keys` this
            // ran once per import specifier per extension, i.e. O(files x
            // specifiers): measured 709.7 ms of a 774.7 ms floor pass table on a
            // 2,401-file project with NO JSX in it, growing 14.6x for 4x the files.
            val suffix = "/$base$ext"
            for (fn in jsxTsxFileNames) {
                EagerIndexCensus.jsxSuffixScanSteps++
                if (fn.endsWith(suffix) || fn == "$base$ext") return fn
            }
        }
        return null
    }

    private fun emitTS6142(
        specifier: Expression,
        moduleName: String,
        resolvedFile: String,
        source: String,
        fileName: String,
    ) {
        val start = specifier.pos
        val length = moduleName.length + 2 // +2 for quotes
        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
        checker.diagnostics.add(Diagnostic(
            message = "Module '$moduleName' was resolved to '$resolvedFile', but '--jsx' is not set.",
            category = DiagnosticCategory.Error,
            code = 6142,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }
}
