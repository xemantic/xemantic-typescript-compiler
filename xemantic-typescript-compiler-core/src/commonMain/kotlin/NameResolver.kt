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
 * (INV.0) step 4a — the NAME / MODULE RESOLUTION seam, tsgo's `resolveAlias` /
 * `resolveModuleSpecifier` family of `internal/checker`: turning an import alias
 * into the symbol it names, and a module specifier string into the program file
 * it resolves to. Extracted VERBATIM from `Checker.kt` (design § 6 Stage 0), as a
 * final class constructed once per `Checker` — no semantic change, and every call
 * site that survives in `Checker.kt` is a one-line delegation.
 *
 * ## What is here
 *
 * The alias ladder ([resolveAlias], [resolveAliasTarget], [resolveImportTargetFallback],
 * [resolveAliasJsModuleSpecifier], [resolveImportedSymbolGeneral] and its computer),
 * the specifier ladder ([resolveModuleSpecifier] and its computer, the two relative
 * resolvers, [augmentationTargetFile]), the two small scope probes [resolveNamePath] /
 * [findSymbolInExports], and the checker-local symbol-target LINK STORE
 * ([getSymbolTarget] / [setSymbolTarget]) — whose only readers were the alias ladder,
 * so the store moved wholesale and `Checker` no longer names it.
 *
 * ## Ambient surface (the ledger row, `docs/inversion-ambient-ledger.md` § 4)
 *
 * NOT empty, and stated rather than hidden: fourteen `Checker` members are still
 * READ — `ambientModuleSurfaceMember`, `ambientRequireAliasTarget`,
 * `blockLevelImportOf`, `createModuleSymbol`, `enclosingImportsOf`,
 * `findEnclosingImport`, `findSymbolInAllNamespaceScopes`, `isImportBindingDecl`,
 * `normalizePath`, `resolveAmbientModuleExportEquals`,
 * `resolveExportedSymbolThroughStars`, `resolveExpressionToSymbol`,
 * `resolveModuleExportAssignment` and `resolveQualifiedName` — each reached through
 * the FINAL [Checker] class, a direct call with no interface and no captured lambda
 * (contract § 10). There are **no ambient WRITES**: every container this family
 * mutates ([symbolTargets], and its own two memos) is handed in or owned here.
 *
 * ## What is here — step 4b-i (the PER-FILE LOOKUP core)
 *
 * The per-file scope tables and their two build passes ([buildPerFileScopes] /
 * [buildPerFileScopeFor]), the INV.3(b)(ii) visibility sets and their deferral
 * ([computePerFileVisibility] / [ensurePerFileVisibility] / [moduleOnlyGlobals] /
 * [libValueShadows]), the probe funnel ([perFileScopeOf] / [perFileScopeProbe]),
 * the three consults built on them ([lookupPerFile] / [lookupInFileScope] /
 * [globalsForFile] / [lookupPerFileForNode]), the (CHK.49) lib-value recovery
 * ([libValueBehindTypeOnlyShadow]), the (BIND.1) owning-file probes
 * ([owningBinderResult] / [nodeSymbolOf] / [moduleInstanceStateOf]) and the four
 * first-hit program scans ([resolveIdentifierInFile] / [findTypeAliasByName] /
 * [findTypeParamDeclByName] / [findNamespaceLocalInterface]).
 *
 * Step 4b-i adds SEVEN ambient reads — `augmentationContextSymbol`,
 * `findTypeParamInStatements`, `globalAugmentationAddedSymbols`,
 * `installGlobalsLookupClassifier`, `isModuleFile`, `libGlobals` and
 * `moduleLocalContributesGlobally` — and no ambient WRITE: the eleven fields this
 * family owns moved with it. `libGlobals` and `globalAugmentationAddedSymbols`
 * are deliberately READS rather than constructor inputs: both are declared BELOW
 * this class's construction site in `Checker.kt`, and `libGlobals`' initializer
 * runs `parseBuiltinLib()`, whose side effect fills `realLibUnknownNames` — a
 * field whose own KDoc records that it is declared before `libGlobals` on purpose.
 * Hoisting that chain above the construction would reorder lib parsing against
 * ~9,300 lines of field initialization; every reader here runs during or after the
 * `init` passes, long after that order has settled.
 *
 * One coupling is BIDIRECTIONAL and intended:
 * `Checker.installGlobalsLookupClassifier` stays in `Checker` (it reads the
 * walk-scoped `currentFileLocals`) while the two taxonomies it classifies against
 * live here, so it reads [classifierModuleLocalNames] / [classifierNonModuleVisible]
 * off this class and calls [ensurePerFileVisibility], and
 * [computePerFileVisibility] calls it back.
 *
 * ## What is here — step 4b-ii (NAMESPACE / HERITAGE / TYPE-NAME resolution)
 *
 * The (CHK.76) enclosing-namespace consult [lookupInEnclosingNamespaces] and the
 * (CHK.77) merged-level machinery under it ([mergedNamespaceLevels],
 * [globalAugmentationLevelSymbol], [ambientModuleBlockIsFileless]), the (CHK.78)
 * augmentation-context pair ([augmentationContextSymbol] /
 * [augmentationContextSymbolForNode]), the INV.3(d) global-contribution predicate
 * [moduleLocalContributesGlobally], round 748's [lexicalTypeSymbolForNode],
 * [resolveQualifiedName], the heritage pair ([resolveHeritageBaseHead] /
 * [resolveHeritageBaseSymbol]) with [isInAmbientContext], the (CHK.79) surface walk
 * [ambientModuleSurfaceMember], [resolveTypeNameToSymbol] with
 * [symbolHasTypeSideDeclaration] and [typeSideImportFallback], and the INV.3(d)
 * namespace-qualified family ([namespaceAliasMemberSymbol] /
 * [resolveNamespaceQualifiedSymbol] / [resolveNsQualifiedFromQualifiedName]).
 *
 * It ABSORBS four of the reads the two rows above recorded — `resolveQualifiedName`
 * and `ambientModuleSurfaceMember` (row 4), `augmentationContextSymbol` and
 * `moduleLocalContributesGlobally` (row 5) — because those functions now live here,
 * and adds NINE: `QUALIFIED_LEFT_MEANING`, `ambientModuleOfImportAlias`,
 * `enclosingAmbientBlockMember`, `isDtsFile`, `lexicalBlockScopedEnumNames`,
 * `mergeSharedKeepNames`, `moduleFiles`, `moduleNamedExportsOf` and
 * `umdGlobalNames`. Net for the whole class: **26** ambient reads (21 - 4 + 9), and
 * still **no ambient WRITES** — the two memos it brings
 * ([ambientModuleFilelessCache], [typeSideImportFallbackCache]) moved with it, and
 * the four sets it consults (`moduleFiles`, `umdGlobalNames`,
 * `mergeSharedKeepNames`, `lexicalBlockScopedEnumNames`) are read with `in` only.
 *
 * `QUALIFIED_LEFT_MEANING` is a READ rather than a moved constant because two
 * readers stay in `Checker.kt`; the four sets are reads rather than constructor
 * inputs because each is declared BELOW this class's construction site (the
 * init-order trap row 5's note records — a field declared after line 666 is still
 * null when the constructor runs).
 *
 * ## What a later stage must make explicit
 *
 * The fourteen step-4a reads split cleanly in two: an AMBIENT-MODULE group (the
 * `declare module "spec"` surface — six of them) and a SCOPE/EXPRESSION group
 * (name lookup and qualified-name resolution). A resolver that took an
 * `AmbientModuleIndex` and a `ScopeResolver` as constructor inputs would have an
 * empty ambient row; that is the shape the next row of this family reaches for.
 * Step 4b-i's seven are a third group: the LIB/GLOBAL SURFACE (`libGlobals`,
 * `globalAugmentationAddedSymbols`, `isModuleFile`,
 * `moduleLocalContributesGlobally`) plus two callbacks into machinery this half
 * does not own yet (the classifier install, the augmentation-context consult) —
 * and step 4b-ii dissolved that second callback by moving the consult itself.
 * Step 4b-ii's nine are a fourth group and the sharpest statement of the debt: two
 * are pure DATA the binder passes computed (`moduleFiles`, `umdGlobalNames`) and
 * two more are name SETS of the same kind (`mergeSharedKeepNames`,
 * `lexicalBlockScopedEnumNames`), so a `ProgramFacts` input would absorb four at
 * once; `moduleNamedExportsOf` / `resolveExportedSymbolThroughStars` /
 * `enclosingAmbientBlockMember` / `ambientModuleOfImportAlias` belong with row 4's
 * AMBIENT-MODULE group, and `isDtsFile` is a pure function of a file name.
 */
internal class NameResolver(
    private val checker: Checker,
    /** (INV.0) step 9 — the INV.2(c) scope-space ascent; see `LexicalScopeResolver.kt`. */
    private val lexicalResolver: LexicalScopeResolver,
    private val options: CompilerOptions,
    private val binderResults: List<BinderResult>,
    private val fileResults: Map<String, BinderResult>,
    private val globals: SymbolTable,
    private val moduleResolutions: Map<String, Map<String, String>>,
    /**
     * (CHK.80)(d) `Checker.moduleImportAliasNames` — handed in as the OBJECT, so
     * the entries `init:mergeFileLocalsIntoGlobals` adds after this class is
     * constructed are visible here exactly as they were when the field was read
     * off `Checker` directly. Read-only from this side.
     */
    private val moduleImportAliasNames: Set<String>,
    private val symbolTargets: IntKeyMap<Symbol>,
) {

    /** INV.3(b): memo for [resolveImportedSymbolGeneral] (alias symbol id → target
     *  Symbol, or null) — the kind-AGNOSTIC sibling of [importedGuardDeclCache] /
     *  [importedNamespaceSymCache], serving [lookupPerFile]. Declared before `init`
     *  per the init-order trap. */
    private val importedSymbolGeneralCache = HashMap<Int, Symbol?>()

    /** Perf (round 432): memo for [resolveModuleSpecifier] — the function is a pure
     *  function of the specifier string ([fileResults]/[options] are fixed before `init`;
     *  the contextNode param is unused), but its fallback path iterates EVERY program
     *  file with per-call string stripping. Negative results (null) are the hot case
     *  (ESM `.js` barrel specifiers, deliberately unresolvable) so they are cached via
     *  containsKey, not getOrPut. Declared before `init` per the init-order trap. */
    // Perf: values are non-null; [UNRESOLVED_MODULE_SPEC] sentinel encodes a computed
    // null result so every lookup (incl. the hot unresolvable case) is a SINGLE map get
    // instead of containsKey + get. Never a real filename (no `\u0000` in resolutions).
    private val UNRESOLVED_MODULE_SPEC = "\u0000<unresolved-module-specifier>"
    private val moduleSpecifierCache = HashMap<String, String>()

    // -----------------------------------------------------------------------
    // LinkStore helpers — checker-local side map for symbol targets.
    // Keeps binder output immutable; each parallel checker resolves independently.
    // -----------------------------------------------------------------------

    /** Get the resolved alias target for a symbol (checker-local, then binder fallback). */
    private fun getSymbolTarget(symbol: Symbol): Symbol? =
        symbolTargets[symbol.id] ?: symbol.target

    /** Set the resolved alias target for a symbol in the checker-local side map. */
    private fun setSymbolTarget(symbol: Symbol, target: Symbol) {
        symbolTargets[symbol.id] = target
    }

    /**
     * (CHK.82)(3) The file a `declare module "<spec>"` AUGMENTATION targets — ONE home
     * for the resolver ladder every augmentation consumer needs, so the merge
     * ([collectModuleAugmentations]), the TS2664 legality walker
     * ([checkAmbientModuleAugmentations]) and the TS2305 suppression
     * ([augmentationDeclaredExportNames]) cannot disagree about what a specifier names.
     *
     * The legs, in order: the base resolver; the round-443 `.js`-aware wrapper (an ESM
     * `declare module "../compiler/types.js"`, whose extension the base resolver
     * deliberately will not strip); the crawl's own `(importer, specifier)` answer for
     * THIS file ((CHK.30)/(CHK.78)); and — for a BARE specifier only — the crawl's answer
     * as recorded for any OTHER file.
     *
     * WHY THE LAST LEG EXISTS AND WHY IT IS GUARDED. A bare package specifier's target is
     * named by the package's `package.json` `types`/`main`/`exports` entry, so no string
     * transformation of the specifier can find it and only the crawl knows; but the crawl
     * records a resolution per (importer, specifier) pair and only for specifiers it saw
     * as IMPORTS, so the file that merely AUGMENTS `"some-pkg"` without importing it has
     * no entry of its own. Node's lookup walks up from the importer's directory, so two
     * files can legitimately resolve one bare specifier to two packages (a nested
     * `node_modules`); the leg therefore requires every recording file to AGREE and
     * answers null otherwise — a wrong target here would merge an augmentation into
     * another package's module, which (CFG.1) says nothing in this repo would print.
     * Relative specifiers are excluded from it outright: theirs is a per-directory
     * meaning, and the earlier legs already resolve them exactly.
     */
    fun augmentationTargetFile(spec: String, declaringFileName: String): String? {
        resolveModuleSpecifierRelativeJsAware(spec, declaringFileName)?.let { return it }
        resolveImportTargetFallback(spec, declaringFileName)?.let { return it }
        if (spec.startsWith("./") || spec.startsWith("../") || moduleResolutions.isEmpty()) return null
        var agreed: String? = null
        for ((_, perFile) in moduleResolutions) {
            val target = perFile[spec] ?: continue
            if (target !in fileResults) continue
            if (agreed == null) agreed = target else if (agreed != target) return null
        }
        return agreed
    }
    /**
     * M3.4 (round 409): ESM `.js`-tolerant module resolution for the FLOW-ONLY
     * [resolveImportedFunctionLikeDecl]. tsc's own sources (and any nodenext
     * project) import via `.js`/`.jsx`/`.mjs`/`.cjs` specifiers that point at
     * `.ts`/`.tsx` sources; [resolveModuleSpecifier] deliberately will NOT strip
     * those (CLAUDE.md — TS2459 FP-avoidance), so a barrel-imported guard's module
     * could not be resolved at all (the reason round 408's guard-narrowing wire was
     * inert). Retry with the extension stripped — directory-relative to
     * [contextFile] first (nested layouts), then globally (flat corpus layouts).
     */
    fun resolveAliasJsModuleSpecifier(specifier: String, contextFile: String?): String? {
        for (ext in listOf(".js", ".jsx", ".mjs", ".cjs")) {
            if (!specifier.endsWith(ext)) continue
            val stripped = specifier.removeSuffix(ext)
            if (contextFile != null) resolveModuleSpecifierRelative(stripped, contextFile)?.let { return it }
            resolveModuleSpecifier(stripped)?.let { return it }
        }
        return null
    }
    /**
     * (CHK.30) round 949 — the LAST-RESORT leg of every import-alias target
     * resolution, and the reason a type imported from a `node_modules` package used
     * to be `any`.
     *
     * [resolveModuleSpecifier] and its relative siblings are the corpus-era string
     * matchers: they look a specifier up among the [fileResults] KEYS, and the
     * non-relative form deliberately refuses `.d.ts` (so `foo` cannot capture an
     * ambient `foo.d.ts`). That is right for a flat fixture layout and leaves a real
     * project with NO resolution at all for `import type { V } from 'pkg'` — a
     * package's `types` / `main` / `exports` entry is not a string transformation of
     * the specifier. The alias then resolved to nothing and every type it named
     * degraded to `any`.
     *
     * **Nothing in this repo could see it.** `any` is legal everywhere, so no
     * diagnostic MOVES at the import; what surfaces is the false-positive SHADOW —
     * a TS7006 on every un-annotated callback parameter whose contextual type lived
     * in that package (89 of knip's 156 residual rows, which is what (CHK.30) was
     * opened about and mis-attributed to contextual typing).
     *
     * So this answers from [moduleResolutions] — what the project crawl's real
     * `ModuleResolver` already decided for this exact `(importer, specifier)` pair —
     * and from nothing else. It is APPENDED after every existing leg at each of the
     * ten alias ladders, so it can only make MORE specifiers resolve and never
     * redirect one that already resolved, and a corpus fixture cannot reach it at all
     * (the map is empty off the project path).
     *
     * **AND IT IS DELIBERATELY THE ONLY SOURCE.** The first cut also consulted the
     * `node_modules` walkers ~15 other checker sites use ([resolveBareNodeModulesAnyPrefix],
     * [resolveBareViaPackageExportsRoot]); ablating those legs away is **0 RED across
     * the whole 15,883-test suite**, because wherever they could answer the crawl has
     * already answered better. Re-adding them would be shipping a leg no gate here
     * can fail.
     *
     * The [fileResults] guard is not cosmetic: the crawl also pulls in `.json` and
     * un-bound `.js` files, which have no binder result for a caller to read an
     * export out of.
     */
    fun resolveImportTargetFallback(spec: String, contextFile: String?): String? {
        if (contextFile == null || spec.isEmpty() || moduleResolutions.isEmpty()) return null
        val target = moduleResolutions[contextFile]?.get(spec) ?: return null
        return if (target in fileResults) target else null
    }
    fun resolveAlias(symbol: Symbol, visited: MutableSet<Int> = mutableSetOf()): Symbol {
        if (!visited.add(symbol.id)) return symbol // cycle detected
        getSymbolTarget(symbol)?.let { return resolveAlias(it, visited) }
        // For import aliases, try to resolve the target
        if (symbol.flags.hasAny(SymbolFlags.Alias)) {
            for (decl in symbol.declarations) {
                when (decl) {
                    is ImportEqualsDeclaration -> {
                        val ref = decl.moduleReference
                        when (ref) {
                            is QualifiedName -> {
                                val target = resolveQualifiedName(ref) ?: continue
                                setSymbolTarget(symbol, target)
                                return resolveAlias(target, visited)
                            }
                            is Identifier -> {
                                val target = globals[ref.text] ?: continue
                                setSymbolTarget(symbol, target)
                                return resolveAlias(target, visited)
                            }
                            is ExternalModuleReference -> {
                                // import A = require("mod") — resolve module then its export
                                val specifier = (ref.expression as? StringLiteralNode)?.text ?: continue
                                val targetFile = resolveModuleSpecifier(specifier, decl)
                                if (targetFile == null) {
                                    // B113: AMBIENT module — `declare module "mod1" { ... }` is
                                    // bound (Binder) under a symbol whose name == the specifier
                                    // string, merged into globals at checker init. It is NOT a
                                    // file, so resolveModuleSpecifier returns null. Resolve the
                                    // alias to that ambient module symbol so `import m1 =
                                    // require("mod1"); var x: m1.Foo` finds mod1's exported Foo.
                                    val ambient = globals[specifier]
                                    if (ambient != null && ambient.flags.hasAny(SymbolFlags.Module) &&
                                        ambient.exports != null) {
                                        // (CHK.81) …unless the block's surface is `export = <value>`,
                                        // in which case the alias names that value, as in tsc.
                                        val target = checker.ambientRequireAliasTarget(ambient, visited) ?: ambient
                                        setSymbolTarget(symbol, target)
                                        return resolveAlias(target, visited)
                                    }
                                    continue
                                }
                                val targetResult = fileResults[targetFile] ?: continue
                                // Look for export = X in the target module
                                val exportTarget = checker.resolveModuleExportAssignment(targetResult, visited)
                                if (exportTarget != null) {
                                    setSymbolTarget(symbol, exportTarget)
                                    return resolveAlias(exportTarget, visited)
                                }
                                // No export = found — create module symbol
                                val moduleSymbol = checker.createModuleSymbol(symbol.name, targetResult)
                                setSymbolTarget(symbol, moduleSymbol)
                                return moduleSymbol
                            }
                            else -> {}
                        }
                    }
                    is ImportDeclaration -> {
                        val specifier = (decl.moduleSpecifier as? StringLiteralNode)?.text ?: continue
                        // Round 512 (the round-511 dir-relative lesson): the bare
                        // resolver only knows flat corpus-style keys — a path-shaped
                        // layout (`/proj/src/a.ts` importing `./b`) needs the
                        // directory-relative leg or the alias never resolves on
                        // real on-disk projects.
                        val targetFile = resolveModuleSpecifier(specifier, decl)
                            ?: owningSourceFile(decl)?.fileName?.let {
                                resolveModuleSpecifierRelative(specifier, it)
                            } ?: continue
                        val targetResult = fileResults[targetFile] ?: continue

                        // Namespace import: import * as Foo from "mod"
                        val namedBindings = decl.importClause?.namedBindings
                        if (namedBindings is NamespaceImport) {
                            val moduleSymbol = checker.createModuleSymbol(symbol.name, targetResult)
                            setSymbolTarget(symbol, moduleSymbol)
                            return moduleSymbol
                        }

                        // Default import: import Foo from "mod"
                        if (decl.importClause?.name != null &&
                            symbol.name == decl.importClause.name.text) {
                            // Look for "default" export in target — first check locals["default"]
                            val defaultSymbol = targetResult.locals["default"]
                            if (defaultSymbol != null) {
                                setSymbolTarget(symbol, defaultSymbol)
                                return resolveAlias(defaultSymbol, visited)
                            }
                            // `export default function f() {}` / `export default class C {}`.
                            // A DECLARATION carrying both modifiers is neither an
                            // ExportAssignment nor bound under the name "default" — it is
                            // bound under its OWN — so every leg around this one misses it
                            // and the alias used to resolve to nothing, i.e. to `any`.
                            // SILENTLY: `any` is assignable to everything, so a program
                            // importing a default-exported function type-checked and every
                            // misuse of it went unreported (measured against tsgo 7.0.2,
                            // which reports TS2322 for the same two files).
                            for (stmt in targetResult.sourceFile.statements) {
                                val defaultName = when {
                                    stmt is FunctionDeclaration &&
                                        ModifierFlag.Export in stmt.modifiers &&
                                        ModifierFlag.Default in stmt.modifiers -> stmt.name?.text
                                    stmt is ClassDeclaration &&
                                        ModifierFlag.Export in stmt.modifiers &&
                                        ModifierFlag.Default in stmt.modifiers -> stmt.name?.text
                                    else -> null
                                } ?: continue
                                val exported = targetResult.locals[defaultName] ?: continue
                                setSymbolTarget(symbol, exported)
                                return resolveAlias(exported, visited)
                            }
                            // Scan for `export default X` (ExportAssignment without isExportEquals)
                            for (stmt in targetResult.sourceFile.statements) {
                                if (stmt is ExportAssignment && !stmt.isExportEquals) {
                                    val resolved = checker.resolveExpressionToSymbol(stmt.expression, targetResult, visited)
                                    if (resolved != null) {
                                        setSymbolTarget(symbol, resolved)
                                        return resolveAlias(resolved, visited)
                                    }
                                }
                            }
                            // Scan for `export { X as default } from "mod"` re-exports
                            for (stmt in targetResult.sourceFile.statements) {
                                if (stmt is ExportDeclaration) {
                                    val clause = stmt.exportClause
                                    if (clause is NamedExports) {
                                        val defaultSpec = clause.elements.find { it.name.text == "default" }
                                        if (defaultSpec != null) {
                                            val originalName = defaultSpec.propertyName?.text ?: "default"
                                            val fromSpec = (stmt.moduleSpecifier as? StringLiteralNode)?.text
                                            val resolvedTarget: Symbol? = if (fromSpec != null) {
                                                val fromFile = resolveModuleSpecifier(fromSpec, stmt)
                                                val fromResult = fromFile?.let { fileResults[it] }
                                                fromResult?.locals?.get(originalName)
                                            } else {
                                                targetResult.locals[originalName]
                                            }
                                            if (resolvedTarget != null) {
                                                setSymbolTarget(symbol, resolvedTarget)
                                                return resolveAlias(resolvedTarget, visited)
                                            }
                                        }
                                    }
                                }
                            }
                            // Fallback: `export = X` is consumed as the DEFAULT import under
                            // esModuleInterop (TypeScript's synthetic-default rule — a CommonJS
                            // `export =` module is default-imported as its export-equals value).
                            // e.g. exp.ts `export = x` + imp.ts `import foo from "./exp"` → foo is x.
                            // Gated to esModuleInterop (default true) since without it the default
                            // import of an `export =` module is a TS1259 error, not a value.
                            if (options.esModuleInterop) {
                                val exportEqualsTarget = checker.resolveModuleExportAssignment(targetResult, visited)
                                if (exportEqualsTarget != null) {
                                    setSymbolTarget(symbol, exportEqualsTarget)
                                    return resolveAlias(exportEqualsTarget, visited)
                                }
                            }
                            continue
                        }

                        // Named import: import { X } from "mod"
                        val target = targetResult.locals[symbol.name] ?: continue
                        setSymbolTarget(symbol, target)
                        return resolveAlias(target, visited)
                    }
                    is ImportSpecifier -> {
                        // Named import — the original name to look up
                        val originalName = decl.propertyName?.text ?: decl.name.text
                        // Find the ImportDeclaration parent for this specifier via the
                        // prebuilt index (no parent pointers; round 432 — was a
                        // program-wide structural scan re-run on every call for
                        // unresolvable barrel aliases).
                        // (CHK.80)(c) The index holds TOP-LEVEL imports only; a specifier
                        // written inside a `declare module` block (`import { EventEmitter }
                        // from "node:events"` — 29 bare heritage bases of `@types/node`)
                        // reaches its ImportDeclaration through the INV.2(a) parent chain,
                        // and was left unresolved (`any`) here before.
                        val enclosingImports = checker.enclosingImportsOf(decl).ifEmpty {
                            checker.blockLevelImportOf(decl)?.let { listOf(it) } ?: emptyList()
                        }
                        for ((_, stmt) in enclosingImports) {
                            val specifier2 = (stmt.moduleSpecifier as? StringLiteralNode)?.text
                                ?: continue
                            // Round 512 (the round-511 dir-relative lesson): the bare
                            // resolver only knows flat corpus-style keys — path-shaped
                            // on-disk layouts need the directory-relative leg.
                            val targetFile2 = resolveModuleSpecifier(specifier2, stmt)
                                ?: owningSourceFile(stmt)?.fileName?.let {
                                    resolveModuleSpecifierRelative(specifier2, it)
                                }
                            if (targetFile2 == null) {
                                // Ambient module fallback — `declare module "X"` in a .d.ts file.
                                val ambient = globals[specifier2]
                                if (ambient != null && ambient.flags.hasAny(SymbolFlags.Module)) {
                                    ambient.exports?.get(originalName)?.let { target ->
                                        setSymbolTarget(symbol, target)
                                        return resolveAlias(target, visited)
                                    }
                                    // Fallback: ambient module body has `export = X`
                                    // (e.g. `import alias = demoNS; export = alias`) —
                                    // resolve X then look up `originalName` in X's exports.
                                    val exportEqualsTarget = checker.resolveAmbientModuleExportEquals(ambient, visited)
                                    if (exportEqualsTarget != null) {
                                        exportEqualsTarget.exports?.get(originalName)
                                            // (CHK.81) …unless that entry is a LOCAL re-export clause's
                                            // specifier and nothing else (`namespace Stream { export
                                            // { Stream } }`, `@types/node`'s own shape): it names the
                                            // ENCLOSING block's class, which only the surface walk below
                                            // resolves. Since (CHK.81) made a `require` alias name the
                                            // `export =` CLASS, this leg started landing on that entry
                                            // and answering `any` for `import { Stream } from
                                            // "node:stream"` — a LOST TS2322 on real `@types/node`.
                                            ?.takeIf { t -> !(t.declarations.isNotEmpty() && t.declarations.all { it is ExportSpecifier }) }
                                            ?.let { target ->
                                                setSymbolTarget(symbol, target)
                                                return resolveAlias(target, visited)
                                            }
                                    }
                                    // (CHK.80)(c) the (CHK.79) surface walk: an `export * from
                                    // "m"` block (`node:net`), and an `export = <alias>` whose
                                    // target block itself ends in `export = Stream` — the
                                    // NAMESPACE's members (`import { Readable } from
                                    // "node:stream"`), which the leg above cannot see.
                                    ambientModuleSurfaceMember(ambient, originalName, HashSet())?.let { target ->
                                        setSymbolTarget(symbol, target)
                                        return resolveAlias(target, visited)
                                    }
                                }
                                continue
                            }
                            val targetResult2 = fileResults[targetFile2] ?: continue
                            val target = targetResult2.locals[originalName] ?: continue
                            setSymbolTarget(symbol, target)
                            return resolveAlias(target, visited)
                        }
                    }
                    else -> {}
                }
            }
        }
        return symbol
    }
    /**
     * Resolve a dotted name path (e.g., "A.B.C.E") to a symbol by walking the namespace chain.
     */
    fun resolveNamePath(path: String, result: BinderResult): Symbol? {
        val parts = path.split(".")
        // First look in file-level locals, then globals
        var current = result.locals[parts[0]] ?: globals[parts[0]]
        // If not found at file level, search all namespace scopes across all files
        if (current == null && parts.size > 1) {
            current = checker.findSymbolInAllNamespaceScopes(parts[0]) ?: return null
        }
        if (current == null) return null
        for (i in 1 until parts.size) {
            current = resolveAlias(current!!)
            current = current.exports?.get(parts[i]) ?: return null
        }
        return current
    }
    fun findSymbolInExports(name: String, scope: SymbolTable): Symbol? {
        // Check this scope directly
        val direct = scope[name]
        if (direct != null) return direct
        // Recurse into namespace exports
        for ((_, sym) in scope) {
            if (sym.flags.hasAny(SymbolFlags.NamespaceModule or SymbolFlags.ValueModule)) {
                val found = sym.exports?.let { findSymbolInExports(name, it) }
                if (found != null) return found
            }
        }
        return null
    }
    /**
     * Simple module specifier resolution: strip leading `./` and append `.ts` / try `.ts`.
     * This is a simplified version for the test suite where module specifiers
     * are relative paths within the same test compilation unit.
     * Also supports baseUrl-relative non-relative specifiers (e.g., "defs/cc" with baseUrl "/proj").
     */
    fun resolveModuleSpecifier(specifier: String, contextNode: Node? = null): String? {
        // Perf (round 432): pure function of the specifier ([fileResults]/[options] are
        // fixed; contextNode is unused) — memoized incl. null results (round 432 note:
        // getOrPut would recompute the hot unresolvable-specifier case every call).
        // Single-lookup via the sentinel (round 483): null is the hot result.
        moduleSpecifierCache[specifier]?.let { return if (it === UNRESOLVED_MODULE_SPEC) null else it }
        val result = computeModuleSpecifier(specifier)
        moduleSpecifierCache[specifier] = result ?: UNRESOLVED_MODULE_SPEC
        return result
    }
    private fun computeModuleSpecifier(specifier: String): String? {
        val isRelative = specifier.startsWith("./") || specifier.startsWith("../")
        val baseName = specifier.removePrefix("./").removePrefix("../")
        // Try exact match first, then with extensions
        val candidates = mutableListOf(
            baseName,
            "$baseName.ts",
            "$baseName.tsx",
            "./$baseName",
            "./$baseName.ts",
            "./$baseName.tsx",
        )
        // Only try .d.ts for relative specifiers (./X or ../X) to avoid matching ambient module
        // declarations in non-relative imports like "foo" → "foo.d.ts" (which may have augmentations
        // from other files that we can't account for).
        if (isRelative) {
            candidates.add("$baseName.d.ts")
            candidates.add("./$baseName.d.ts")
        }
        // For non-relative specifiers, also try baseUrl-prefixed paths
        if (!isRelative && options.baseUrl != null) {
            val baseUrl = options.baseUrl.trimEnd('/')
            candidates.add("$baseUrl/$baseName")
            candidates.add("$baseUrl/$baseName.ts")
            candidates.add("$baseUrl/$baseName.tsx")
        }
        for (candidate in candidates) {
            if (candidate in fileResults) return candidate
        }
        // Try matching by base filename (strips common path prefix)
        for (fileName in fileResults.keys) {
            // Only strip .d.ts for relative specifiers (./X); skip .d.ts files for non-relative
            val fileBase = if (isRelative) {
                fileName.removePrefix("./").removeSuffix(".d.ts").removeSuffix(".ts").removeSuffix(".tsx")
            } else {
                fileName.removePrefix("./").removeSuffix(".ts").removeSuffix(".tsx")
            }
            if (fileBase == baseName) return fileName
            // For non-relative specifiers: check if file ends with /baseName
            if (!isRelative && (fileBase.endsWith("/$baseName") || fileBase == baseName)) return fileName
            // For relative specifiers in flat-directory absolute-path test layouts (e.g. @Filename: /foo.ts):
            // fileBase = "/foo", baseName = "foo" → fileBase == "/$baseName"
            if (isRelative && fileBase == "/$baseName") return fileName
        }
        return null
    }
    /**
     * Resolve a module specifier relative to the given context file's directory.
     * For `folder/bar.ts` importing `./foo`, resolves to `folder/foo.ts`.
     * Falls back to [resolveModuleSpecifier] if directory-relative resolution fails.
     */
    fun resolveModuleSpecifierRelative(specifier: String, contextFileName: String): String? {
        if (!specifier.startsWith("./") && !specifier.startsWith("../")) {
            // Non-relative: use normal resolution
            return resolveModuleSpecifier(specifier)
        }
        // Get directory of the context file
        val dir = contextFileName.substringBeforeLast('/', "")
        val resolved = if (dir.isEmpty()) specifier else "$dir/${specifier.removePrefix("./")}"
        // Normalize `..` segments
        val normalized = checker.normalizePath(resolved)
        val candidates = listOf(
            normalized,
            "$normalized.ts",
            "$normalized.tsx",
            "$normalized.d.ts",
        )
        for (candidate in candidates) {
            if (candidate in fileResults) return candidate
        }
        // Also strip leading "./" if present for lookup
        val candidatesNoPrefix = candidates.map { it.removePrefix("./") }
        for (candidate in candidatesNoPrefix) {
            if (candidate in fileResults) return candidate
        }
        return resolveModuleSpecifier(specifier)
    }
    /**
     * [resolveModuleSpecifierRelative] that ALSO retries with a trailing ESM `.js`/`.jsx`
     * extension stripped (nodenext resolves `../compiler/types.js` → `../compiler/types.ts`).
     * The base resolver deliberately avoids `.js` globally (FP-prone per the TS2459 gotcha), so
     * callers that must be `.js`-tolerant use this. Purely additive — can only make MORE
     * specifiers resolve, never fewer — so it only ever SUPPRESSES a false-positive
     * "cannot be found" diagnostic. Consolidates the strip-and-retry pattern inlined at the
     * TS2694/TS2305/TS2307 augmentation-target sites.
     */
    fun resolveModuleSpecifierRelativeJsAware(specifier: String, contextFileName: String): String? =
        resolveModuleSpecifierRelative(specifier, contextFileName)
            ?: (if (specifier.endsWith(".js")) resolveModuleSpecifierRelative(specifier.removeSuffix(".js"), contextFileName) else null)
            ?: (if (specifier.endsWith(".jsx")) resolveModuleSpecifierRelative(specifier.removeSuffix(".jsx"), contextFileName) else null)
            ?: (if (specifier.endsWith(".mjs")) resolveModuleSpecifierRelative(specifier.removeSuffix(".mjs"), contextFileName) else null)
            ?: (if (specifier.endsWith(".cjs")) resolveModuleSpecifierRelative(specifier.removeSuffix(".cjs"), contextFileName) else null)

    /** Resolve an import alias to its target symbol, with cycle detection. */
    fun resolveAliasTarget(symbol: Symbol): Symbol? {
        // Use the checker-local LinkStore target if available
        getSymbolTarget(symbol)?.let { return it }
        // Trigger full cross-file resolution if no cached target
        if (symbol.flags.hasAny(SymbolFlags.Alias)) {
            val resolved = resolveAlias(symbol)
            return if (resolved !== symbol) resolved else null
        }
        return null
    }
    /**
     * INV.3(b): the kind-AGNOSTIC generalization of the flow-only import-resolver
     * skeleton ([resolveImportedFunctionLikeDecl] / [resolveImportedNamespaceSymbol] /
     * [resolveImportedEnumSymbol]): an ImportSpecifier-declared alias resolves to the
     * target module's exported SYMBOL whatever its kind, following ESM `.js`
     * specifiers (which the general [resolveModuleSpecifier] deliberately won't
     * strip) and `export *` barrels ([resolveExportedSymbolThroughStars]), hopping
     * re-import/re-export aliases ([visited]-guarded).
     *
     * ADDITIVE: the three kind-specific legacy variants stay untouched — their
     * per-declaration kind-filter-then-continue semantics differ subtly from
     * first-resolved-symbol (a multi-declaration alias whose first decl resolves to
     * the "wrong" kind), so delegating them here would not be behavior-preserving.
     * They become deletion candidates when their consumers migrate to the per-file
     * primitive (INV.3(c)/(d)). Like them, this must NEVER be wired into the general
     * [resolveAlias] (the round-409 TS2315-flood gotcha) — its only consumer is
     * [lookupPerFile].
     */
    fun resolveImportedSymbolGeneral(aliasSymbol: Symbol, visited: MutableSet<Int> = mutableSetOf()): Symbol? {
        val topLevel = visited.isEmpty()
        if (MapCensus.boxedKeyCensus && topLevel) MapCensus.bk(MapCensus.BK_RISG, aliasSymbol.id.toLong())
        val cached = topLevel && importedSymbolGeneralCache.containsKey(aliasSymbol.id)
        if (MapCensus.on) MapCensus.risgEnter(topLevel, cached)
        if (cached) {
            if (MapCensus.boxedKeyCensus) MapCensus.bk(MapCensus.BK_RISG, aliasSymbol.id.toLong())
            return importedSymbolGeneralCache[aliasSymbol.id]
        }
        val result = computeImportedSymbolGeneral(aliasSymbol, visited)
        if (topLevel) {
            if (MapCensus.boxedKeyCensus) MapCensus.bk(MapCensus.BK_RISG, aliasSymbol.id.toLong())
            importedSymbolGeneralCache[aliasSymbol.id] = result
        }
        return result
    }
    private fun computeImportedSymbolGeneral(aliasSymbol: Symbol, visited: MutableSet<Int>): Symbol? {
        if (!visited.add(aliasSymbol.id)) return null
        for (decl in aliasSymbol.declarations) {
            if (decl !is ImportSpecifier) continue
            val originalName = decl.propertyName?.text ?: decl.name.text
            val (contextFile, importDecl) = checker.findEnclosingImport(decl) ?: continue
            val spec = (importDecl.moduleSpecifier as? StringLiteralNode)?.text ?: continue
            // INV.3(d)(ii): the DIR-RELATIVE resolver is load-bearing for path-shaped
            // layouts (`/proj/src/f1.ts` importing `./lib`) — the plain resolver only
            // knows flat corpus-style keys, and pre-retire the merged globals masked
            // the miss (importers free-rode on the merge); a failed hop here returns
            // the bare alias and every import-mediated type silently dies. The
            // `.js`-aware leg is unaffected (a `.js` spec fails the relative
            // candidates and still strips via resolveAliasJsModuleSpecifier).
            val targetFile = resolveModuleSpecifier(spec, importDecl)
                ?: resolveModuleSpecifierRelative(spec, contextFile)
                ?: resolveAliasJsModuleSpecifier(spec, contextFile)
                ?: resolveImportTargetFallback(spec, contextFile)
                ?: continue
            val tr = fileResults[targetFile] ?: continue
            val sym = tr.locals[originalName]
                ?: checker.resolveExportedSymbolThroughStars(tr.sourceFile, originalName)
                ?: continue
            // [mergeSymbolTable] pollutes same-named symbols' FLAGS (and
            // declarations) across files — an Alias flag alone cannot identify
            // an import alias (the isValueExport gotcha: scan declarations,
            // never flags). A symbol with any non-import-binding declaration
            // IS the resolution target.
            if (sym.declarations.isEmpty() || sym.declarations.any { !checker.isImportBindingDecl(it) }) return sym
            // A pure import-binding alias — a re-import + re-export hop.
            computeImportedSymbolGeneral(sym, visited)?.let { return it }
        }
        return null
    }

    // -----------------------------------------------------------------------
    // (INV.0) step 4b-i — PER-FILE NAME LOOKUP.
    //
    // The per-file scope tables (INV.3(b)) and the INV.3(b)(ii) visibility sets,
    // the two consults built on them ([globalsForFile] / [lookupPerFileForNode]),
    // the (BIND.1) owning-file probes, and the four first-hit program scans.
    // Taken VERBATIM from `Checker.kt`; the only edits are the `checker.` prefix
    // on the seven ambient reads and the visibility of the declarations
    // themselves. Ledger row 4, `docs/inversion-ambient-ledger.md`.
    //
    // THE FOUR FIRST-HIT PROGRAM SCANS ([resolveIdentifierInFile],
    // [findTypeAliasByName], [findTypeParamDeclByName], [findNamespaceLocalInterface])
    // ANSWER FROM WHICHEVER FILE HAPPENS TO MATCH FIRST — a (BIND.1)-class
    // cross-file answer, moved verbatim and flagged rather than "fixed": the
    // scan order is `binderResults` order, which round 776 makes a property of
    // the crawl, so changing one is a program change and not a refactor.
    // -----------------------------------------------------------------------

    /**
     * 17.32a: per-file scope tables — "true visible scope" for each file =
     * lib + script-file locals (across all files) + this file's own locals.
     * Built but NOT YET CONSUMED — keyed by `sourceFile.fileName`. Future
     * substeps will flip individual identifier-resolution call sites to
     * consult this map instead of the merged [globals] (which conflates
     * module-file exports across files). KNOWN_GLOBALS is companion-level
     * data and stays consulted directly at lookup sites; storing it per-file
     * would just duplicate ~400 strings without any visibility refinement.
     */
    private val perFileScope: MutableMap<String, SymbolTable> = mutableMapOf()

    /** (WARM.23) round 896 — [perFileScopeOf]'s one-entry reference-compared memo.
     *  Declared HERE, beside the map it fronts and far above the `init` block that
     *  runs the whole check: a checker field declared below `init` is still at its
     *  JVM default while every pass executes. */
    private var perFileScopeMemoKey: String? = null
    private var perFileScopeMemoValue: SymbolTable? = null

    /** (INC.70) The program-wide half of every per-file scope — lib globals, script-file
     *  locals and `declare global` additions — captured by [buildPerFileScopes] and read
     *  by [buildPerFileScopeFor]. Null until that pass has stood, which is what makes
     *  [perFileScopeOf] answer null for "scopes unbuilt" exactly as before.
     *  Declared before `init` per the init-order trap. */
    private var perFileScopeSharedBase: SymbolTable? = null

    /** (INC.70) fileName -> that file's own top-level locals, the other input of
     *  [buildPerFileScopeFor]. One map put per program file, against the two
     *  allocations the eager form paid per file. Declared before `init` per the
     *  init-order trap. */
    private val perFileScopeOwnLocals: MutableMap<String, SymbolTable> = HashMap()

    /**
     * INV.3(b)(ii): names whose ONLY declarations are module-file top-level
     * locals — the conflation candidates. A `globals` hit on such a name is a
     * legitimate resolution ONLY when the consulting file itself declares or
     * imports it; anywhere else it is a foreign module file's local leaking
     * through the init-block `mergeSymbolTable` conflation (the CONFLATED
     * class of the INV.3(a) instrumentation). Computed once per program in
     * [computePerFileVisibility] (init step 1b2): module-file local names
     * MINUS every name with a module-independent global meaning (lib globals,
     * script-file locals, names ADDED to [globals] by module augmentations).
     * Consulted by [globalsForFile]. Declared before `init` (the Kotlin
     * init-order gotcha) and empty until 1b2 runs, so earlier consults — and
     * programs where the set is never computed — degrade to legacy behavior.
     */
    private var moduleOnlyGlobalNames: Set<String> = emptySet()

    /** (INC.71) `init:snapshotPreAugGlobalKeys`' answer, captured by
     *  [computePerFileVisibility] and consumed by [ensurePerFileVisibility]. Null until
     *  that pass has stood, which is what keeps the sets EMPTY before init step 1b2 —
     *  the same degradation to the legacy merged consult the eager form had.
     *  Declared before `init` per the init-order trap. */
    private var perFileVisibilityPreAugKeys: Set<String>? = null

    /** (INC.71) True once [ensurePerFileVisibility] has built the sets. */
    private var perFileVisibilityComputed: Boolean = false

    /** (INC.71) The INV.3(a) classifier's two taxonomies, built beside the sets and read
     *  from inside the installed lambda. Empty on every build that never classifies a
     *  lookup, i.e. everything but tier-3 `--passTiming`. */
    var classifierModuleLocalNames: Set<String> = emptySet()
    var classifierNonModuleVisible: Set<String> = emptySet()

    /**
     * (CHK.49) lib global names a MODULE file shadows with a top-level
     * declaration AND whose lib symbol carries a VALUE declaration
     * (`declare var Text: { new (…): Text }` beside `interface Text`).
     *
     * The shadow is TYPE-space: tsc resolves a name per MEANING, so a module's
     * `interface Map<K, V>` hides the lib TYPE and leaves the lib VALUE alone.
     * This checker has one symbol per name per file, so the value half is
     * restored as a SECOND CHANCE on the rejecting path in [getTypeOfIdentifier]
     * — it can only turn "not constructable / not callable" back into the lib's
     * answer, never change a name that already resolved to a value.
     *
     * Normally EMPTY (nothing shadows a lib global), which is what makes the
     * guard affordable on the identifier path. Computed at init step 1b2 by
     * [computePerFileVisibility]; declared before `init` (the Kotlin init-order
     * gotcha).
     */
    private var libValueShadowNames: Set<String> = emptySet()

    /**
     * (BIND.1) The [BinderResult] of the file [node] was parsed from, or null when the
     * node is not reachable to a `SourceFile` — a `copy()`/Transformer-synthesized node,
     * or a file that is not in this program.
     *
     * The two `nodeKey(pos, end)`-keyed binder tables are PER FILE (see
     * `Binder.nodeToSymbol`), so a reader that holds only a [Node] has to name the file
     * before it may probe them: `nodeKey` carries no file identity and positions restart
     * at 0 in every file, so "search every result and take the first hit" answers with
     * whichever file happens to have a node at those coincident offsets.
     */
    private fun owningBinderResult(node: Node): BinderResult? =
        owningSourceFile(node)?.fileName?.let { fileResults[it] }


    /**
     * (BIND.1) The symbol the binder recorded for the declaration [node], asked of the
     * file that OWNS it.
     *
     * An owner that recorded nothing answers null and the scan is NOT reattempted — an
     * absent entry in the owning file is the correct answer, and falling through to the
     * other files is precisely the collision this exists to remove. The scan survives for
     * an UNINDEXED node only, where there is no file to ask and the pre-(BIND.1)
     * behaviour is the conservative one.
     */
    fun nodeSymbolOf(node: Node): Symbol? {
        owningBinderResult(node)?.let { return it.nodeToSymbol[nodeKey(node)] }
        val key = nodeKey(node)
        for (result in binderResults) result.nodeToSymbol[key]?.let { return it }
        return null
    }

    /** (BIND.1) [nodeSymbolOf]'s twin for `moduleInstanceStates`, with the same rule
     *  about an owner that recorded nothing. */
    fun moduleInstanceStateOf(node: Node): ModuleInstanceState? {
        owningBinderResult(node)?.let { return it.moduleInstanceStates[nodeKey(node)] }
        val key = nodeKey(node)
        for (result in binderResults) result.moduleInstanceStates[key]?.let { return it }
        return null
    }

    /** (INC.71) [moduleOnlyGlobalNames], built on first ask. */
    private fun moduleOnlyGlobals(): Set<String> {
        ensurePerFileVisibility()
        return moduleOnlyGlobalNames
    }

    /** (INC.71) [libValueShadowNames], built on first ask. */
    fun libValueShadows(): Set<String> {
        ensurePerFileVisibility()
        return libValueShadowNames
    }

    /**
     * 17.32a: Build per-file scope tables (Blocker #3 step 1, infrastructure-only).
     *
     * For each file, compute "true visible scope" = lib (built-in stubs) +
     * script-file locals from ALL files + this file's own locals. Module-file
     * locals from OTHER files are deliberately excluded — those should require
     * an explicit import to be visible. Result is stored in [perFileScope]
     * keyed by `sourceFile.fileName`.
     *
     * NOT YET CONSUMED. Future substeps (17.32b+) will flip individual
     * identifier-resolution call sites to consult this map instead of the
     * over-merged [globals]. KNOWN_GLOBALS stays at the call site (companion
     * data, no visibility refinement to add per file).
     *
     * IMPORTANT: this MUST NOT call [mergeSymbolTable] across binderResults —
     * that helper mutates the existing target symbol's `declarations` list via
     * `addAll`, and the existing init-block merge into [globals] has already
     * done that mutation once. Re-merging here would duplicate declarations on
     * the shared symbol object (e.g. `class C<T>` in a.ts + `interface C<T>` in
     * b.ts ends up with the interface declaration appearing twice on `a.ts.C`,
     * which masks unused-type-parameter checks). Use direct map assignment
     * with first-write-wins semantics so the underlying symbols stay clean.
     */
    fun buildPerFileScopes() {
        // Collect first-occurrence symbol per name across all script files.
        val scriptFileNames: MutableMap<String, Symbol> = mutableMapOf()
        for (result in binderResults) {
            if (!checker.isModuleFile(result.sourceFile.statements)) {
                for ((name, sym) in result.locals) {
                    if (name !in scriptFileNames) scriptFileNames[name] = sym
                }
            }
        }
        // (INC.61) The part that is the SAME for every file, built ONCE. It used to
        // be copied into a fresh table per file, i.e. `files x libGlobals`
        // insertions — 13.5 ms on a 2,401-file project whose `lib` is `es2020` and
        // **175.6 ms on the same program with `dom` added**, which is what an
        // ordinary project gets by default. See [LayeredSymbolTable] for why the
        // overlay reproduces the copy's iteration ORDER entry for entry.
        val sharedBase: SymbolTable = symbolTable()
        // Lib globals — visible from every file.
        for ((name, sym) in checker.libGlobals) sharedBase[name] = sym
        // Script-file locals — visible across all files (TypeScript treats
        // any file without imports/exports as contributing to the global
        // namespace).
        for ((name, sym) in scriptFileNames) {
            if (name !in sharedBase) sharedBase[name] = sym
        }
        // (CHK.50) `declare global { … }` additions — global by construction,
        // and therefore visible from every file exactly as a script-file local is.
        for ((name, sym) in checker.globalAugmentationAddedSymbols) {
            if (name !in sharedBase) sharedBase[name] = sym
        }
        // (INC.70) The per-file half is built ON FIRST ASK, by [buildPerFileScopeFor].
        // What stays eager is everything PROGRAM-WIDE — `sharedBase` above, and the
        // fileName -> own-locals table below, which is one map put per file against
        // the two allocations, a `putAll` and a `LayeredSymbolTable` shadow-list the
        // eager form paid per file whether or not anything ever asked.
        perFileScopeSharedBase = sharedBase
        perFileScopeOwnLocals.clear()
        perFileScope.clear()
        for (result in binderResults) {
            perFileScopeOwnLocals[result.sourceFile.fileName] = result.locals
        }
        // (WARM.23) round 896 — [perFileScopeOf]'s memo could go stale here and at
        // [buildPerFileScopeFor]'s store; both clear it. A new write site must too.
        perFileScopeMemoKey = null
        perFileScopeMemoValue = null
    }

    /**
     * (INC.70) Build ONE file's [perFileScope] entry, the first time it is asked for.
     *
     * ## Why this is deferrable and what makes it exact
     *
     * The eager loop allocated two maps, copied the file's own locals into one of
     * them and precomputed a `LayeredSymbolTable`'s shadow list — **per program
     * file, on every build, whether or not any name was ever resolved in that
     * file**. On the incremental FLOOR (a build whose check partition is empty)
     * that is the whole pass: measured on the 2,401-file `many-small-2400-dom`
     * fixture, `init:buildPerFileScopes` was 3.3 ms of a ~120 ms floor.
     *
     * The inputs are frozen by the time the pass stands. `sharedBase` is captured
     * eagerly, so the lib/script/`declare global` half is exactly what it was; the
     * only remaining input is `result.locals`, and **the checker's ONE writer of a
     * `BinderResult.locals` is `collectModuleAugmentations`, dispatched by
     * `init:mergeModuleAugmentations`, which runs at an EARLIER init step**. So the
     * snapshot the eager form took and the snapshot this takes are the same table.
     * That ordering is the whole soundness argument and it is not self-evident from
     * either function — a new writer of `locals` scheduled after this pass would
     * make the two disagree silently, and nothing here would print it.
     *
     * ## Why the pin is a COUNT
     *
     * (INC.16)'s law: whether deferring a per-file table pays is decided by WHO
     * FORCES it, which is a population to be measured rather than read.
     * [EagerIndexCensus.perFileScopeBuilds] is that population — `binderResults.size`
     * before, and on a narrowed build only the files something actually resolved a
     * name in.
     */
    private fun buildPerFileScopeFor(fileName: String): SymbolTable? {
        val base = perFileScopeSharedBase ?: return null
        val own = perFileScopeOwnLocals[fileName] ?: return null
        val scope: SymbolTable = LayeredSymbolTable(base, symbolTable().also { it.putAll(own) })
        perFileScope[fileName] = scope
        EagerIndexCensus.perFileScopeBuilds++
        perFileScopeMemoKey = null
        perFileScopeMemoValue = null
        return scope
    }

    /**
     * INV.3(b)(ii): compute the per-file visibility model's sets once per
     * program (init step 1b2, after the [globals] membership settles):
     *  - `moduleLocalNames` — every top-level local of every MODULE file: the
     *    conflation candidates (only these names can be module-file leaks);
     *  - `nonModuleVisible` — names with a legitimate global meaning without
     *    the conflation: [libGlobals] keys (embedded or real libs), script-file
     *    locals, and names ADDED to [globals] by [mergeModuleAugmentations]
     *    (captured as the key-set delta around step 1b).
     *
     * Publishes their difference as [moduleOnlyGlobalNames] (the
     * [globalsForFile] gate) and hands both sets to the INV.3(a) classifier
     * install (a no-op unless `--passTiming` constructed [globals]
     * instrumented).
     */
    fun computePerFileVisibility(preAugmentationGlobalsKeys: Set<String>) {
        // (INC.71) The pass now CAPTURES its one input and installs the classifier;
        // the sets themselves are built by [ensurePerFileVisibility] on first ask.
        perFileVisibilityPreAugKeys = preAugmentationGlobalsKeys
        checker.installGlobalsLookupClassifier()
    }

    /**
     * (INC.71) Build the INV.3(b)(ii) visibility sets, the first time one is read.
     *
     * ## Why it is deferrable at all
     *
     * Everything it reads is frozen by the time [computePerFileVisibility] stands:
     * each file's `isModuleFile` shape and `locals`, `libGlobals`, and
     * `globals.keys` against the snapshot taken at `init:snapshotPreAugGlobalKeys`.
     * **The last one is the ordering claim and it was checked rather than assumed:
     * the only writers of [globals] are `init:mergeLibGlobals`,
     * `init:mergeFileLocalsIntoGlobals` and `collectModuleAugmentations` (dispatched
     * by `init:mergeModuleAugmentations`), and all three run at EARLIER init steps.**
     * A writer added after this pass would make the eager and deferred answers
     * disagree silently, exactly as for [buildPerFileScopeFor].
     *
     * ## Why it pays
     *
     * The sets have three readers — [globalsForFile], [globalsForFileNode] and
     * [libValueBehindTypeOnlyShadow] — and all three are NAME RESOLUTION. A build
     * whose check partition is empty resolves no name, so it reads none of them:
     * measured on the 2,401-file `many-small-2400-dom` fixture, **0 asks on a floor
     * build against 335,881 on a full one**, which is why the pass was ~5.6-7.2 ms of
     * a ~136 ms incremental floor that could not be spent.
     *
     * ## The one place this is NOT lazy
     *
     * The INV.3(a) classifier is installed EAGERLY, at this pass's own moment, and
     * forces the sets from inside its body — so under tier-3 `--passTiming` every
     * classified lookup is classified exactly as before and `globals.lookups` /
     * `globals.conflated` do not move. The visible consequence is that under FULL
     * `--passTiming` the cost lands on whichever pass performs the first `globals`
     * lookup rather than on this row; at the `rows` tier (which is what the floor
     * decomposition uses) [globals] is not instrumented at all and the row is honest.
     */
    fun ensurePerFileVisibility() {
        if (perFileVisibilityComputed) return
        val preAugmentationGlobalsKeys = perFileVisibilityPreAugKeys ?: return
        perFileVisibilityComputed = true
        EagerIndexCensus.perFileVisibilityBuilds++
        val moduleLocalNames = HashSet<String>()
        // (CHK.49) the LIB key set is deliberately NOT seeded here — see
        // [mergeSharedKeepNames]'s KDoc. A lib name a MODULE file declares now
        // falls into [moduleOnlyGlobalNames] and is resolved through
        // [perFileScope], which already seeds every file with the lib symbol and
        // lets the declaring file's own local override it. The INV.3(a)
        // classifier below is still handed the OLD taxonomy (lib keys included)
        // so its SHARED/CONFLATED counts stay comparable across rounds.
        val nonModuleVisible = HashSet<String>()
        for (result in binderResults) {
            if (checker.isModuleFile(result.sourceFile.statements)) {
                // INV.3(d): entries the retired merge deliberately KEEPS global
                // (`declare global` / UMD / ambient-module carriers) classify as
                // non-module-visible — same predicate as the step-1 merge.
                for ((name, sym) in result.locals) {
                    if (moduleLocalContributesGlobally(name, sym)) nonModuleVisible.add(name)
                    else moduleLocalNames.add(name)
                }
            } else {
                nonModuleVisible.addAll(result.locals.keys)
            }
        }
        for (key in globals.keys) {
            if (key !in preAugmentationGlobalsKeys) nonModuleVisible.add(key)
        }
        moduleOnlyGlobalNames = HashSet(moduleLocalNames).apply { removeAll(nonModuleVisible) }
        // (CHK.49) the VALUE meaning of a shadowed lib name SURVIVES the shadow:
        // `interface Map<K, V>` declared in a module file shadows the lib TYPE
        // and leaves `declare var Map: MapConstructor` reachable, which is what
        // keeps `new Map()` constructable there. Precomputed (and normally
        // EMPTY) so [libValueBehindTypeOnlyShadow]'s guard is one set probe on
        // the ~2 M-identifier path.
        libValueShadowNames =
            if (moduleOnlyGlobalNames.isEmpty()) emptySet()
            else moduleOnlyGlobalNames.filterTo(HashSet()) { n ->
                checker.libGlobals[n]?.valueDeclaration != null
            }
        classifierModuleLocalNames = moduleLocalNames
        classifierNonModuleVisible = HashSet(nonModuleVisible).apply { addAll(checker.libGlobals.keys) }
    }

    /**
     * INV.3(b): THE per-file resolution primitive. An INV.3(c) family flip
     * consumes it through [globalsForFile] (which preserves the legacy merged
     * symbol INSTANCE for legitimately visible names — substituting THIS
     * function's return directly changes symbol identity for lib/script names,
     * because [perFileScope] holds the first-occurrence script symbol while
     * [globals] holds the first-declarer merged instance). Resolution:
     * [perFileScope]'s table for [fileName] (own top-level locals shadowing
     * script-file globals shadowing lib — built by [buildPerFileScopes]), with an
     * own-local IMPORT alias resolved onward through [resolveImportedSymbolGeneral]
     * to the target module's symbol — the ESM-`.js`/`export *`-barrel following the
     * merged `globals` used to provide for free (the round-500 measurement: the
     * conflated traffic is almost entirely barrel-imported `types.ts` names).
     *
     * Returns:
     *  - the resolved target symbol for an ImportSpecifier-declared alias local
     *    (for a name the file imports, this is the SAME symbol instance the
     *    conflated `globals` consult returned — which is what makes (c) flips
     *    byte-identical for imported names);
     *  - the alias symbol ITSELF when the import cannot be resolved (missing
     *    module / unsupported alias kind: default imports, `import * as ns`,
     *    `import =` — extend when a (c) flip needs them) — callers keep their
     *    existing alias handling;
     *  - the plain symbol for non-alias names (own declaration / script-file
     *    global / lib);
     *  - null when the name has NO per-file meaning — exactly the case where the
     *    conflated lookup would have leaked a foreign module file's local.
     *
     * `internal` for direct construction tests (Inv3PerFileLookupTest); unconsumed
     * by checker paths until INV.3(c).
     */
    /**
     * (WARM.23) THE funnel for every `perFileScope` read — round 894 candidate
     * (2a). Two things ride on it and neither is expressible at the raw `[]`:
     * the census counts how many full file-PATH hashes a rebuild pays (the map
     * is keyed by a 60-100 character path and probed per NAME lookup), and
     * `--perFileScopeAmp N` prices ONE probe by round 759's amplification,
     * because a single probe is well under a timestamp pair.
     *
     * Off (both modes at their defaults) this is `perFileScope[fileName]` and
     * two not-taken branches.
     */
    fun perFileScopeOf(fileName: String): SymbolTable? {
        // (WARM.23) round 896 — a ONE-ENTRY memo compared by REFERENCE, the shape
        // round 895(D) landed for `SrcScanCache`: **a miss is never wrong, only
        // slower.** A hit is sound because the same `String` INSTANCE cannot get
        // a different answer out of a map that is not mutated in between — and
        // the one mutation site ([buildPerFileScopes]' store) clears it, so
        // staleness is not expressible either.
        //
        // Identity is the right test rather than a weakness: `perFileScope`'s keys
        // ARE `sourceFile.fileName`, and every hot caller reaches here with that
        // very instance ([lookupPerFileForNode] via `owner.fileName`), so the hit
        // rate is a property of the traversal, not of string equality. Keying the
        // MAP by identity would be the unsound version — an equal-but-distinct
        // path would answer null and silently resolve a name to a foreign
        // module's local, which is why the map probe stays authoritative.
        if (fileName === perFileScopeMemoKey) {
            if (MapCensus.on) MapCensus.perFileMemoHits++
            return perFileScopeMemoValue
        }
        val scope = perFileScopeProbe(fileName)
        perFileScopeMemoKey = fileName
        perFileScopeMemoValue = scope
        return scope
    }

    private fun perFileScopeProbe(fileName: String): SymbolTable? {
        if (MapCensus.on) MapCensus.perFileProbes++
        val r = MapCensus.perFileScopeReads
        if (r == 0) return perFileScope[fileName] ?: buildPerFileScopeFor(fileName)
        if (fileName !in perFileScope) buildPerFileScopeFor(fileName)
        if (r < 0) {
            // In-situ EMPTY bracket at the same site and frequency, so `cold` can
            // be separated from the pair. The real read still happens, outside
            // the pair, so the compile is unchanged.
            val e0 = PassTiming.nowNanos()
            MapCensus.perFileAmpNanos += PassTiming.nowNanos() - e0
            MapCensus.perFileAmpCalls++
            return perFileScope[fileName]
        }
        val t0 = PassTiming.nowNanos()
        var result: SymbolTable? = null
        var seen = 0L
        var i = 0
        while (i < r) {
            val v = perFileScope[fileName]
            if (v != null) seen++
            result = v
            i++
        }
        MapCensus.perFileAmpNanos += PassTiming.nowNanos() - t0
        MapCensus.perFileAmpCalls++
        MapCensus.sink += seen
        return result
    }

    fun lookupPerFile(fileName: String, name: String): Symbol? =
        lookupInFileScope(perFileScopeOf(fileName) ?: return null, name)


    /**
     * [lookupPerFile] once the file's table is already in hand — the whole of its
     * body below the `perFileScope` probe.
     *
     * (WARM.23) round 896, candidate (2a): [globalsForFile] used to ask
     * `perFileScope.containsKey(fileName)` and then call [lookupPerFile], which
     * asked `perFileScope[fileName]` again — the file PATH hashed TWICE per name
     * lookup on the hottest resolution path in the checker. Splitting the body out
     * lets the caller pass the table it already resolved. Same answers by
     * construction: the map's value type is non-nullable, so `containsKey` and
     * `get() != null` are the same predicate.
     */
    private fun lookupInFileScope(scope: SymbolTable, name: String): Symbol? {
        val sym = scope[name] ?: return null
        if (sym.flags.hasAny(SymbolFlags.Alias) && sym.declarations.any { it is ImportSpecifier }) {
            resolveImportedSymbolGeneral(sym)?.let { return it }
        }
        return sym
    }

    /**
     * INV.3(b)(ii): a `globals[name]` consult made per-file-correct — the flip
     * shape for the INV.3(c) migration. Returns the merged-globals symbol
     * (INSTANCE-identical to the legacy consult, which is what keeps a flip
     * byte-identical for every legitimately visible name) whenever [name] has a
     * per-file meaning in [fileName]:
     *  - a name outside [moduleOnlyGlobalNames] (lib / script-file /
     *    augmentation-added global — no conflation possible), or
     *  - a module-only name the file itself declares or imports, probed through
     *    [lookupPerFile] (the INV.3(b) primitive; an import alias resolves
     *    non-null there whether or not its target module resolves).
     *
     * Returns null exactly when the legacy consult would have LEAKED a foreign
     * module file's local (the CONFLATED class of the INV.3(a)
     * instrumentation): the caller behaves as if the name did not resolve,
     * which is what real tsc sees in that file. A [fileName] without a
     * [perFileScope] entry (scopes unbuilt, or a file outside the program)
     * degrades to the legacy consult.
     *
     * The conflated branch never touches [globals], so under `--passTiming`
     * the per-pass conflated tables keep measuring only UN-migrated traffic.
     */
    fun globalsForFile(fileName: String, name: String): Symbol? {
        if (NameCensus.on) {
            NameCensus.publish(moduleOnlyGlobals(), globals.keys)
            NameCensus.nameProbe(name, name in moduleOnlyGlobals(), node = false)
        }
        if (name in moduleOnlyGlobals()) {
            // (WARM.23) round 896 — ONE probe. This used to be
            // `perFileScope.containsKey(fileName)` here and `perFileScope[fileName]`
            // again inside [lookupPerFile]: the file PATH hashed twice per name.
            val scope = perFileScopeOf(fileName)
            if (scope != null) {
                // INV.3(d): the retired merge no longer puts module-only names into
                // [globals] at all — the per-file resolution IS the symbol (the
                // declaring file's own clean instance; an import alias resolves
                // onward through [resolveImportedSymbolGeneral] inside
                // [lookupInFileScope]). Null exactly where the legacy merged
                // consult would have LEAKED a foreign module file's local.
                return lookupInFileScope(scope, name)
            }
        }
        return globals[name]
    }

    /**
     * INV.3(c)(i): the NODE-keyed per-file consult — a `globals[name]` consult
     * made per-file-correct for a name READ FROM AN AST NODE. The round-503
     * measurement: ~82% of conflated traffic resolves names from FOREIGN nodes
     * (types.ts's union-member `.kind` annotations, read by the discriminant/
     * kind-domain narrowing machinery) while checking a DIFFERENT file — tsc
     * resolves such an annotation in its OWNING file's scope, so the owning
     * file is the correct key there, never `currentCheckFileName` (keying by
     * the checking file would silently kill the narrowing wherever that file
     * does not import the name). Resolution: [owningSourceFile] via the
     * INV.2(a) parent chain, then [globalsForFile] under that file's
     * visibility — so a name visible in the owning file returns the legacy
     * merged-globals INSTANCE (byte-identical flips), and null means the name
     * has no meaning even where the node lives. An UNINDEXED node (data-class
     * `copy()` / Transformer-synthesized / detached subtree) has no owner —
     * degrade to the legacy merged consult, mirroring [globalsForFile]'s
     * unknown-file degradation. `internal` for direct-construction tests.
     * Consumers (INV.3(c)(ii)): the enum-discriminant readers —
     * [resolveEnumSymbolForDiscriminant] and the alias fallbacks in
     * [enumSwitchKeysFromTypeNode]/[enumMemberKeysOfTypeNode].
     */
    fun lookupPerFileForNode(node: Node, name: String): Symbol? {
        // Fast path (round 507b): a non-module-only name resolves identically
        // under EVERY file's visibility ([globalsForFile] ignores the file for
        // it), so skip the parent-chain walk — this sits on getTypeOfIdentifier's
        // fallback, which is consulted for ~2M identifiers per self-compile.
        // Before init step 1b2 the set is empty → everything degrades to the
        // legacy merged consult (same as the ownerless degradation below).
        if (NameCensus.on) {
            NameCensus.publish(moduleOnlyGlobals(), globals.keys)
            NameCensus.nameProbe(name, name in moduleOnlyGlobals(), node = true)
        }
        // (CHK.78)(b) a THIRD clause naming the augmentation-visible names was
        // built here — so that a name SHARED with a lib global could still reach
        // the INV.3(c)(iv) leg below — and MEASURED REDUNDANT: (CHK.49) keeps the
        // LIB key set out of `nonModuleVisible`, so a lib name a MODULE file also
        // declares IS module-only and never took this path to begin with, and the
        // only other way to be shared is a SCRIPT-file collision, which
        // `mergeSharedKeepNames` merges so that `globals[name]` already carries
        // both declarations. Ablated: the clause plus its index moved neither the
        // `Node`-collision project fixture nor any pin. The fast path stays one probe.
        val moduleOnly = name in moduleOnlyGlobals()
        if (!moduleOnly && (moduleImportAliasNames.isEmpty() || name !in moduleImportAliasNames)) return globals[name]
        // Walk to the owning SourceFile, capturing the INNERMOST enclosing
        // `declare module "<spec>"` block on the way (the INV.3(c)(iv)
        // augmentation-visibility rule below needs it). Mirrors
        // [owningSourceFile]'s hop-bounded chain walk.
        var cur: Node? = node
        var hops = 0
        var ambientBlock: ModuleDeclaration? = null
        var owner: SourceFile? = null
        while (cur != null && hops++ < 4096) {
            if (cur is SourceFile) { owner = cur; break }
            if (ambientBlock == null && cur is ModuleDeclaration && cur.name is StringLiteralNode) {
                ambientBlock = cur
            }
            cur = (cur as NodeBase).parent
        }
        if (owner == null) return globals[name]
        // INV.3(c)(iv): a node inside a `declare module "<relative-spec>"`
        // AUGMENTATION block sees the AUGMENTED module's exports by bare name —
        // tsc checks the body in that module's context (the round-443 rule;
        // mirrors buildNamespaceScope's StringLiteralNode branch, which grants
        // the same visibility to the TS2304 scope). A services/types.ts
        // augmentation of "../compiler/types.js" references
        // Node/SymbolFlags/UnionType without importing them; the per-file
        // consult alone would null those and silently kill e.g. this-predicate
        // narrowing whose target type lives in the augmented module.
        //
        // (CHK.78)(b) asked FIRST, where it used to be the last leg below the
        // per-file consult. Two things that ordering got wrong, both measured
        // against tsgo 7.0.2 on the (CHK.77) fixture: a SHARED name never
        // reached it at all (the fast path answered `globals["Node"]`), and for
        // a module-only name the augmenting file's OWN import won where tsc
        // gives the augmented module's export — `import { Zzz } from
        // "./other.js"` beside `declare module "./types.js" { … pZ: Zzz }`
        // reads `./types.js`'s `Zzz` under tsc and read `./other.js`'s here.
        // The augmented module's scope is the INNER one; the augmenting file's
        // is the enclosing one, so it may only answer on a miss.
        augmentationContextSymbol(ambientBlock, owner, name)?.let { return it }
        // (CHK.80)(d) a script-global name some MODULE file imports under the same
        // name: that file's own alias shadows the global (the per-file scope layers
        // the file's locals over the script ones); any other file keeps the global.
        if (!moduleOnly) {
            val scope = perFileScopeOf(owner.fileName) ?: return globals[name]
            return lookupInFileScope(scope, name) ?: globals[name]
        }
        return globalsForFile(owner.fileName, name)
    }

    fun resolveIdentifierInFile(name: String, contextNode: Node): Symbol? {
        for (result in binderResults) {
            val symbol = result.locals[name]
            if (symbol != null) return symbol
        }
        return globals[name]
    }

    /** Find a TypeParameter AST node by name — searches the current file's
     *  binder results' AST for any FunctionDeclaration whose typeParameters
     *  contains a match. Returns null if not found. */
    fun findTypeParamDeclByName(name: String): TypeParameter? {
        for (result in binderResults) {
            val found = checker.findTypeParamInStatements(result.sourceFile.statements, name)
            if (found != null) return found
        }
        return null
    }

    fun findTypeAliasByName(name: String): TypeAliasDeclaration? {
        for (result in binderResults) {
            for (stmt in result.sourceFile.statements) {
                if (stmt is TypeAliasDeclaration && stmt.name.text == name) return stmt
            }
        }
        return null
    }

    /**
     * (CHK.49) the lib symbol whose VALUE meaning [local] does not in fact
     * shadow, or null.
     *
     * A module file's `interface Text` / `type Date = …` / an `import { Date }`
     * of one occupies the TYPE meaning only; tsc keeps resolving the VALUE
     * meaning of that name to the lib's `declare var`. We carry one symbol per
     * name per file, so the value half is recovered HERE and only on the
     * rejecting path: the caller has already found a per-file symbol and this
     * answers non-null only when that symbol has NO value meaning at all while
     * the lib one does. An import ALIAS is resolved onward first — its meanings
     * are its target's.
     *
     * [libValueShadowNames] is empty for every program that shadows no lib
     * global, which is the guard that keeps this off the hot identifier path.
     *
     * There is deliberately NO `lib === local` early exit. It was written and
     * ablated (arm a8) and is provably unobservable: at the identifier site
     * [local] comes from a [BinderResult]'s own locals, which never holds the lib
     * symbol, and at the callee site the two coincide only for a file that does
     * NOT declare the name — where both branches go on to call
     * `getTypeOfSymbol(lib)`.
     */
    fun libValueBehindTypeOnlyShadow(name: String, local: Symbol): Symbol? {
        if (name !in libValueShadows()) return null
        val lib = checker.libGlobals[name] ?: return null
        var resolved = local
        if (resolved.flags.hasAny(SymbolFlags.Alias)) {
            resolved = resolveImportedSymbolGeneral(resolved) ?: resolved
        }
        if (resolved.valueDeclaration != null) return null
        if (resolved.flags.hasAny(SymbolFlags.Value)) return null
        return lib
    }

    /** B199 helper: scan [fileName]'s namespace bodies for an InterfaceDeclaration
     *  named [name] with exactly 2 type parameters. */
    fun findNamespaceLocalInterface(fileName: String, name: String): InterfaceDeclaration? {
        val sf = binderResults.firstOrNull { it.sourceFile.fileName == fileName }?.sourceFile ?: return null
        fun scan(stmts: List<Statement>): InterfaceDeclaration? {
            for (st in stmts) {
                when (st) {
                    is InterfaceDeclaration -> if (st.name.text == name && st.typeParameters?.size == 2) return st
                    is ModuleDeclaration -> (st.body as? ModuleBlock)?.let { scan(it.statements)?.let { r -> return r } }
                    else -> {}
                }
            }
            return null
        }
        return scan(sf.statements)
    }

    // -----------------------------------------------------------------------
    // (INV.0) step 4b-ii — NAMESPACE / HERITAGE / TYPE-NAME RESOLUTION.
    //
    // The (CHK.76) enclosing-namespace consult and the (CHK.77) merged-level
    // machinery under it, the (CHK.78) augmentation-context consult, the
    // (CHK.79) ambient-module surface walk, the heritage-base pair,
    // `resolveQualifiedName`, `resolveTypeNameToSymbol` and the INV.3(d)
    // namespace-qualified family — moved VERBATIM from `Checker.kt`, so the
    // five invariants they carry are unchanged: (CHK.30)'s alias-target ladder
    // order, (CHK.76)'s innermost-first namespace walk that SKIPS a string-named
    // `declare module "spec"` block and `declare global`, the heritage meanings
    // ([QUALIFIED_LEFT_MEANING] for the head, `Type or Value` for a bare
    // identifier), round 748's `scope.symbols`-ONLY rule in
    // [lexicalTypeSymbolForNode], and B83.5's lexical-FIRST (never a
    // miss-fallback) order in [resolveTypeNameToSymbol].
    // -----------------------------------------------------------------------

    /** (CHK.77) [ambientModuleBlockIsFileless]'s memo, keyed `"<file>\u0000<specifier>"`. */
    private val ambientModuleFilelessCache = HashMap<String, Boolean>()

    /** INV.3(d): memo for [typeSideImportFallback] — the import-shadowed-by-
     *  value-local TYPE-space recovery. Keyed `fileName|name`; stored null =
     *  no shadowed type import. Declared before `init`. */
    private val typeSideImportFallbackCache = HashMap<String, Symbol?>()

    /**
     * (CHK.76) tsc's `resolveName` `ModuleDeclaration` arm: [name] looked up in the
     * `exports` of every NAMESPACE enclosing [node], innermost first — the `M` of
     * `namespace N { namespace M { <node> } }`, then `N` — each read from the owning
     * file's binder table ([nodeSymbolOf]; for a dotted `namespace A.B.C` the binder
     * records the innermost segment and its `parent` chain recovers `B` and `A`).
     * The binder binds EVERY member of a namespace body into the namespace symbol's
     * `exports` (exported or not), so that one table is what tsc's `locals` +
     * `exports` pair would answer.
     *
     * A parent-chain walk, deliberately NOT an ambient stack: the checker's three
     * frame families resolved a nested namespace's NAME through the file's locals
     * and `globals` (where a nested namespace never is) and so pushed only the
     * OUTERMOST one, which is how a bare `Node` inside `ts.server.protocol` read
     * as the root's `Node` and a bare `Project` beside its own declaration as
     * `any` — and a post-hoc reader (the [CheckedLens], the TypeOracle) has no
     * stack at all. tsc walks `location.parent` for exactly this.
     *
     * Only an IDENTIFIER-named declaration is a scope here. A `declare module
     * "<spec>"` body is skipped on purpose: when the specifier names a program
     * file the block is an AUGMENTATION whose partial `interface Node` is a
     * SEPARATE symbol in this checker (the merge is modelled by
     * [lookupPerFileForNode]'s INV.3(c)(iv) rule, which resolves the bare name in
     * the AUGMENTED module's own scope) — consulting the block's own exports
     * answered the partial interface and cost 43 rows on the services profile
     * (`'../compiler/types.js.SourceFile' is not assignable to 'SourceFile'`).
     * `declare global` is skipped for the same reason (its members merge into
     * `globals`, (CHK.50)). A genuine ambient module's bare names keep resolving
     * exactly as before this consult existed.
     *
     * [meaning] is the tsc meaning mask a hit must satisfy — [SymbolFlags.Type]
     * for a type reference, [SymbolFlags.Value] for an expression identifier,
     * [QUALIFIED_LEFT_MEANING] for the left of a dotted name; an ALIAS declared in
     * the body (`import X = …`) is answered for every meaning, as tsc answers it
     * and lets the caller resolve it. Null where no enclosing namespace declares
     * the name — callers keep their per-file / global consult below, so a name
     * declared in NO namespace resolves exactly as before, and a name declared in
     * an enclosing one now SHADOWS the outer declaration (the B83.5 family's
     * second failure mode, which is a wrong answer and not a miss).
     */
    fun lookupInEnclosingNamespaces(node: Node, name: String, meaning: SymbolFlags): Symbol? {
        var cur: Node? = (node as NodeBase).parent
        var hops = 0
        var levels: ArrayList<ModuleDeclaration>? = null
        var owner: SourceFile? = null
        while (cur != null && hops++ < 4096) {
            if (cur is SourceFile) { owner = cur; break }
            if (cur is ModuleDeclaration) {
                (levels ?: ArrayList<ModuleDeclaration>(4).also { levels = it }).add(cur)
            }
            cur = (cur as NodeBase).parent
        }
        val enclosing = levels ?: return null
        val merged = mergedNamespaceLevels(enclosing, owner)
        // (CHK.80)(c) the index of the innermost `declare global` level, if any: every
        // level INSIDE it is a GLOBAL namespace whose merged instance lives in
        // `globals` (`init:mergeGlobalAugmentations` folds each file's `NodeJS` into
        // one), where the per-file symbol and the (CHK.77) merged view hold this
        // file's members only — `interface ProcessEnv extends Dict<string>` inside
        // `declare module "process" { global { namespace NodeJS { … } } }` read the
        // `Dict` of `globals.d.ts`'s `declare namespace NodeJS` as `any` (a false
        // TS2339 on every member the base declares).
        var globalIdx = -1
        for ((i, decl) in enclosing.withIndex()) {
            if ((decl.name as? Identifier)?.text == "global") { globalIdx = i; break }
        }
        for ((i, decl) in enclosing.withIndex()) {
            fun accept(hit: Symbol?): Symbol? =
                if (hit != null && (hit.flags.hasAny(meaning) || hit.flags.hasAny(SymbolFlags.Alias))) hit else null
            // (CHK.77) the MERGED view of this level, where one exists: its exports are
            // a superset of the per-file symbol's (the merge folds every declaring
            // file's table into it), so the per-file walk below is not needed.
            val mergedLevel = merged?.get(i)
            if (mergedLevel != null) {
                var k = mergedLevel.size - 1
                while (k >= 0) {
                    accept(mergedLevel[k].exports?.get(name))?.let { return it }
                    k--
                }
            }
            if (globalIdx > i) {
                accept(globalAugmentationLevelSymbol(enclosing, globalIdx, i)?.exports?.get(name))?.let { return it }
            }
            if (mergedLevel != null) continue
            var segments = 0
            var nameExpr: Expression = decl.name
            while (nameExpr is PropertyAccessExpression) {
                segments++
                nameExpr = nameExpr.expression
            }
            val consult = when (nameExpr) {
                is Identifier -> nameExpr.text != "global"
                // (CHK.77) a genuine ambient module's own block (no merged view, e.g. a
                // nested `module "a"` whose outer block resolves to no root): the file's
                // own carrier symbol. An AUGMENTATION block stays skipped (INV.3(c)(iv)).
                is StringLiteralNode -> owner != null && ambientModuleBlockIsFileless(nameExpr.text, owner.fileName)
                else -> false
            }
            if (!consult) continue
            var sym = nodeSymbolOf(decl)
            var seg = 0
            while (sym != null && seg++ <= segments && sym.flags.hasAny(SymbolFlags.Module)) {
                accept(sym.exports?.get(name))?.let { return it }
                sym = sym.parent
            }
        }
        return null
    }

    /**
     * (CHK.80)(c) The MERGED global symbol of level [i] of [enclosing], which sits
     * inside the `declare global` block at index [globalIdx]: descend from
     * `globals[<outermost segment inside global>]` along every segment down to
     * level [i] (a dotted `namespace A.B` contributes two). Null where a segment
     * is not a merged namespace.
     */
    private fun globalAugmentationLevelSymbol(enclosing: List<ModuleDeclaration>, globalIdx: Int, i: Int): Symbol? {
        var sym: Symbol? = null
        var j = globalIdx - 1
        while (j >= i) {
            val segments = ArrayList<String>(2)
            var nameExpr: Expression = enclosing[j].name
            while (nameExpr is PropertyAccessExpression) {
                segments.add(0, nameExpr.name.text)
                nameExpr = nameExpr.expression
            }
            when (nameExpr) {
                is Identifier -> segments.add(0, nameExpr.text)
                else -> return null
            }
            for (seg in segments) {
                val next = if (sym == null) globals[seg] else sym.exports?.get(seg)
                if (next == null || !next.flags.hasAny(SymbolFlags.Module)) return null
                sym = next
            }
            j--
        }
        return sym
    }

    /**
     * (CHK.77) The MERGED symbols of the namespace levels [enclosing] (innermost
     * first, as [lookupInEnclosingNamespaces] collects them), or null where the
     * per-file symbols are the whole story.
     *
     * Same-named namespaces across SCRIPT files — `declare namespace ts { … }` in
     * a.d.ts and again in b.d.ts, `@types/node`'s whole shape — are ONE symbol to
     * tsc; here `init:mergeFileLocalsIntoGlobals` folds every script file's locals
     * into `globals`, and [mergeSingleSymbol] ADOPTS the first file's symbol and
     * appends the others' declarations and exports into it (recursively, so a
     * nested `server` is merged too). So `globals[root]` IS the merged instance and
     * every other file's `nodeToSymbol` entry is an UN-merged twin whose exports
     * hold that file's members only — which is why a bare `A` in b.d.ts's `ts`
     * block typed `any` and its heritage was skipped. The merged view is reached by
     * descending from the root along the segment path (`ts` → `server` → …); a
     * dotted `namespace A.B.C` contributes three segments.
     *
     * Roots: an identifier-named outermost level in a SCRIPT program file whose
     * `globals` entry carries this declaration (a MODULE file's namespace never
     * merges — INV.3(d) — and keeps the per-file answer; a lib file is not in
     * [fileResults] and keeps it too); or a string-named `declare module "m"`
     * block whose specifier resolves to NO program file — a genuine ambient module,
     * whose definition and every augmentation carrier merge into `globals["m"]`
     * ([moduleLocalContributesGlobally]) — where a FILE-backed block is an
     * augmentation whose partial interface is a separate symbol here (INV.3(c)(iv),
     * +43 rows on three profiles when consulted blindly in (CHK.76)) and yields no
     * root at all. Where the descent breaks (a level the merge did not reach) the
     * remaining levels answer null and the caller falls back per level.
     */
    private fun mergedNamespaceLevels(enclosing: List<ModuleDeclaration>, owner: SourceFile?): Array<List<Symbol>?>? {
        if (owner == null || fileResults[owner.fileName] == null) return null
        val outermost = enclosing[enclosing.size - 1]
        var rootExpr: Expression = outermost.name
        while (rootExpr is PropertyAccessExpression) rootExpr = rootExpr.expression
        val root: Symbol = when (rootExpr) {
            is Identifier -> {
                if (rootExpr.text == "global" || owner.fileName in checker.moduleFiles) return null
                globals[rootExpr.text] ?: return null
            }
            is StringLiteralNode -> {
                if (!ambientModuleBlockIsFileless(rootExpr.text, owner.fileName)) return null
                globals[rootExpr.text] ?: return null
            }
            else -> return null
        }
        if (!root.flags.hasAny(SymbolFlags.Module)) return null
        var carries = false
        for (d in root.declarations) if (d === outermost) { carries = true; break }
        if (!carries) return null
        val result = arrayOfNulls<List<Symbol>>(enclosing.size)
        var sym: Symbol? = null
        var i = enclosing.size - 1
        while (i >= 0) {
            val decl = enclosing[i]
            val segments = ArrayList<String>(2)
            var nameExpr: Expression = decl.name
            while (nameExpr is PropertyAccessExpression) {
                segments.add(0, nameExpr.name.text)
                nameExpr = nameExpr.expression
            }
            when (nameExpr) {
                is Identifier -> segments.add(0, nameExpr.text)
                is StringLiteralNode -> segments.add(0, nameExpr.text)
                else -> return result
            }
            val level = ArrayList<Symbol>(segments.size)
            for ((k, seg) in segments.withIndex()) {
                val next: Symbol? = if (sym == null) {
                    if (k == 0) root else null
                } else sym.exports?.get(seg)
                if (next == null || !next.flags.hasAny(SymbolFlags.Module)) return result
                level.add(next)
                sym = next
            }
            result[i] = level
            i--
        }
        return result
    }

    /**
     * (CHK.77) Does the `declare module "[spec]"` block written in [ownerFileName]
     * declare a GENUINE ambient module — one whose specifier resolves to no program
     * file — rather than augment a file the program has? Mirrors
     * [collectModuleAugmentations]' target resolution (the plain resolver, the
     * ESM-`.js`-aware relative one, the crawl's package answer — (CHK.30)) and its
     * B387 rule: a BARE specifier that merely shares a basename with a plain `.ts`
     * source names a standalone ambient module. Memoized per (file, specifier): the
     * answer is a property of the program, and the resolvers cache by specifier
     * alone where the relative one depends on the asking file.
     */
    private fun ambientModuleBlockIsFileless(spec: String, ownerFileName: String): Boolean {
        val key = ownerFileName + "\u0000" + spec
        ambientModuleFilelessCache[key]?.let { return it }
        val target = resolveModuleSpecifier(spec)
            ?: resolveModuleSpecifierRelativeJsAware(spec, ownerFileName)
            ?: resolveImportTargetFallback(spec, ownerFileName)
        val fileless = if (target == null) true else {
            val specifierIsRelative = spec.startsWith("./") || spec.startsWith("../")
            val targetIsPlainSource = (target.endsWith(".ts") || target.endsWith(".tsx")) &&
                !target.endsWith(".d.ts") && "node_modules" !in target
            !specifierIsRelative && targetIsPlainSource
        }
        ambientModuleFilelessCache[key] = fileless
        return fileless
    }

    /**
     * INV.3(d): does a MODULE file's top-level local deliberately contribute to
     * the GLOBAL namespace (and therefore keep merging into [globals] after the
     * Blocker-#3 conflation retirement)? True for:
     *  - the `declare global { … }` namespace binding (named `global` — its
     *    exports are genuine global augmentations in tsc);
     *  - an `export as namespace X` UMD namespace (the parser misparses the
     *    construct into a `namespace X` ModuleDeclaration; [umdGlobalNames] is
     *    regex-collected BEFORE the merge);
     *  - any symbol declaring an ambient `declare module "spec"` (StringLiteral
     *    name) — both standalone ambient-module definitions and augmentation
     *    carriers; [mergeModuleAugmentations] resolves them via
     *    `globals[specifier]`.
     * Everything else is module-scoped in real tsc: visible only to the file
     * itself and its importers, served by [lookupPerFile]/[globalsForFile].
     */
    fun moduleLocalContributesGlobally(name: String, symbol: Symbol): Boolean {
        if (name == "global") return true
        if (name in checker.umdGlobalNames) return true
        if (name in checker.mergeSharedKeepNames) return true
        return symbol.declarations.any { it is ModuleDeclaration && it.name is StringLiteralNode }
    }

    /**
     * (REL.1)(c) step 4, WIDENED to the whole TYPE space by (INV.0) step 10a: the
     * position-aware consult for a declaration the main binder never bound (B83.5).
     * Walks the node's ancestor chain outward over the INV.2(c) `lexicalScopes` table
     * of the node's OWNING file and returns the first SCOPE-SPACE `class` /
     * `interface` / `type` / `enum` symbol named [name].
     *
     * **This is a resolution-ORDER change, not a fallback, and it cannot be written
     * as one** — of the two B83.5 failure modes only the first is a miss: a UNIQUE
     * name resolved to nothing (annotation → `any`, every check silent, and no TS2304
     * because the INV.4(c)(iii) family finds the name through these same lexical
     * scopes, which this resolver did not consult), while a name SHADOWING an outer
     * one resolved to the OUTER symbol, which is a wrong answer with nothing to
     * detect.
     *
     * SCOPE-SPACE ONLY (`scope.symbols`, never [LexicalScope.existing]) is what makes
     * it containable: `declareLexical` skips any name the main binder already bound in
     * that container, so a conventionally-bound name is absent from `symbols`
     * everywhere and keeps resolving exactly as before. That also gives the shadowing
     * rule for free — a scope-space binding exists only where the main binder had
     * none, so the innermost-first walk reaches the inner declaration before the file
     * root's aliased locals ever offer the module-scoped one.
     *
     * The widening is a STRICT SUPERSET of round 748's enum-only answer — the gate set
     * and the flag mask both grew — so every enum this answered before it answers still,
     * which is what keeps [lexicalEnumSymbolForNode]'s readers agreeing with this one
     * about a shadowing enum (the round-425 SPLIT-key-space failure).
     */
    fun lexicalTypeSymbolForNode(node: Node, name: String): Symbol? {
        if (name !in checker.lexicalBlockScopedTypeNames) return null
        val scopes = lexicalResolver.scopesOfOwningFile(node) ?: return null
        return lexicalResolver.symbolAt(node, name, scopes, flags = SymbolFlags.ScopeTypeDeclaration)
    }

    /**
     * (INV.0) step 10b — the VALUE-space twin of [lexicalTypeSymbolForNode]: the
     * position-aware consult for a `function` / `class` / `enum` / `namespace` the main
     * binder never bound (B83.5). Walks the node's ancestor chain outward over the
     * INV.2(c) `lexicalScopes` table of the node's OWNING file and returns the first
     * SCOPE-SPACE value symbol named [name].
     *
     * **The soundness argument is 10a's, unchanged**: `declareLexical` refuses any name
     * the main binder already bound in that container, so a hit here can only ever be a
     * declaration the conventional tables do not have — the consult cannot change how a
     * conventionally-bound name resolves, and the innermost-first walk gives the
     * shadowing rule for free.
     *
     * **What is NOT 10a's, and is the reason this is its own method rather than a
     * widened flag mask**: the VALUE gate is a large set (a nested helper `function` is
     * ordinary style), so its hit rate is real where the type one's is near zero. The
     * flag mask is [SymbolFlags.ScopeValueDeclaration] and NOT [SymbolFlags.Value] — a
     * scope-space VARIABLE is deliberately not answered here, because
     * `Checker.currentLocalTypes` is consulted ahead of this and carries exactly that
     * population with the type the walk INFERRED, which this could only replace with a
     * declared one.
     *
     * A consequence of that mask, stated because `symbolAt` does not stop at a
     * non-matching hit: an inner `const foo` shadowing an outer scope-space
     * `function foo` is answered by `currentLocalTypes` above, never by this ascent
     * skipping past it.
     */
    private companion object {
        /**
         * (INV.0) step 10b — what ENDS [lexicalValueSymbolForNode]'s ascent: any
         * scope-space binding that occupies the name in VALUE space, whether or not it
         * is one of the four kinds the consult answers. [SymbolFlags.Alias] is in it
         * because a nested import (TS1232) still binds, and no TYPE-space flag is,
         * because the two spaces do not compete for a name.
         */
        val VALUE_SPACE_BINDING = SymbolFlags.Value or SymbolFlags.Alias
    }

    fun lexicalValueSymbolForNode(node: Node, name: String): Symbol? {
        if (name !in checker.lexicalBlockScopedValueNames) return null
        // TWO gates, and the second one is what keeps (INC.16): the program-wide set is a
        // UNION, so a name declared in file A hits for an identifier in file B — and
        // `scopesOfOwningFile` BUILDS the tables it reads. Testing the owning file's own
        // projection first costs one map lookup and leaves a file that declares no
        // scope-space value name with its tables UNBUILT, which is exactly the property
        // the type consult gets for free from a near-empty gate.
        val result = lexicalResolver.resultOfOwningFile(node) ?: return null
        if (name !in result.scopeValueNames) return null
        return lexicalResolver.symbolAt(
            node, name, result.lexicalScopes,
            flags = SymbolFlags.ScopeValueDeclaration,
            stopFlags = VALUE_SPACE_BINDING,
        )
    }

    /**
     * (INV.0) step 10d — the SCOPE-SPACE ascent for the POST-HOC oracle, meaning-split
     * and deliberately UN-GATED.
     *
     * Every other consult here probes a program-wide NAME GATE first, because it sits on
     * the hot resolution path and the gate is what keeps a file's INV.2(c) tables unbuilt
     * ((INC.16)). The oracle has neither constraint — nothing here runs in a production
     * compile ([TypeOracle]'s cost note) and the build that owns an oracle has already
     * recorded every file — and the gate is an OVER-approximation of one projection and an
     * UNDER-approximation of another: a named `ClassExpression` / `FunctionExpression`
     * declares itself into its OWN scope and is deliberately not stamped, so a gated
     * ascent would answer null for it. Reading the scopes directly makes the answer a
     * function of the SCOPES rather than of the gate's completeness.
     *
     * `stopFlags` is applied for a VALUE meaning and not for a TYPE one, which is
     * (INV.0) step 10b's measured rule: the two spaces are disjoint, so a wrong-KIND hit
     * in type space is not a binding of the name, while in value space a `const` and a
     * nested `function` compete for it and the INNER one wins whichever kind it is.
     */
    fun lexicalSymbolForOracle(node: Node, name: String, meaning: SymbolFlags): Symbol? {
        val scopes = lexicalResolver.scopesOfOwningFile(node) ?: return null
        return lexicalResolver.symbolAt(
            node, name, scopes,
            flags = meaning,
            stopFlags = if (meaning.hasAny(SymbolFlags.Value)) VALUE_SPACE_BINDING else null,
        )
    }

    /**
     * (INV.0) step 10c — the HERITAGE twin of [lexicalTypeSymbolForNode].
     *
     * [resolveHeritageBaseSymbol] is a THIRD name resolver
     * (`lookupInEnclosingNamespaces ?: lookupPerFileForNode`) that reaches neither 10a's
     * consult nor 10b's, so `class D extends ZzzBase` / `implements ZzzI` where the base
     * is scope-space answered nothing (a UNIQUE name) or the OUTER declaration (a
     * SHADOWING one) — measured over a 25-cell matrix against tsgo 7.0.2 and pristine
     * 6.0.3 as 4 ours-only and 16 lost rows across `extends`, `implements` and
     * `interface … extends`, with the file-level control 5/5 clean.
     *
     * The mask is [SymbolFlags.ScopeTypeDeclaration] rather than the arm's own
     * `Type or Value`: a heritage base names a `class`, an `interface`, a `type` alias or
     * an `enum`, and `declareLexical` mints ONE scope symbol per name carrying every flag
     * its declarations gave it — so the type-space mask reaches a scope-space class in
     * `extends` position too, and the value-only kinds (`function`, `namespace`) are not
     * heritage bases at all.
     */
    fun lexicalHeritageSymbolForNode(node: Node, name: String): Symbol? {
        if (name !in checker.lexicalBlockScopedTypeNames) return null
        val scopes = lexicalResolver.scopesOfOwningFile(node) ?: return null
        return lexicalResolver.symbolAt(node, name, scopes, flags = SymbolFlags.ScopeTypeDeclaration)
    }

    /**
     * (INV.0) step 10c — the QUALIFIED-NAME-ROOT twin, and the FOURTH type-name path.
     *
     * `ZzzE.ZA` where `ZzzE` is a scope-space `enum` resolved its root through
     * [resolveQualifiedName]'s own `lookupInEnclosingNamespaces ?: lookupPerFileForNode`
     * ladder, i.e. past both earlier consults — measured as 4 ours-only and 6 lost rows
     * on the step-10c matrix, an exact mirror image (ours reports
     * `has no exported member 'ZInner'` where pristine reports `'ZOuter'`).
     *
     * **The answer is adopted only when the scope symbol CAN answer a member**, i.e. when
     * it has an `exports` table. `declareLexical`'s enum arm publishes the members onto
     * the scope symbol; its `ModuleDeclaration` arm does NOT — a scope-space namespace's
     * members live in the module's own [LexicalScope], not in an `exports` map — so
     * adopting one unconditionally would turn a wrong answer into NO answer, which
     * degrades the annotation to `any` and loses the members that DID resolve. That kind
     * stays exactly as it was and is (INV.0) step 10c's stated residue.
     */
    fun lexicalQualifiedRootSymbolForNode(node: Node, name: String): Symbol? {
        if (name !in checker.lexicalBlockScopedTypeNames &&
            name !in checker.lexicalBlockScopedValueNames
        ) {
            return null
        }
        val result = lexicalResolver.resultOfOwningFile(node) ?: return null
        if (name !in result.scopeTypeNames && name !in result.scopeValueNames) return null
        val sym = lexicalResolver.symbolAt(
            node, name, result.lexicalScopes,
            flags = SymbolFlags.ScopeTypeDeclaration or SymbolFlags.ScopeValueDeclaration,
        ) ?: return null
        return sym.takeIf { it.exports != null }
    }

    /**
     * Round 748's ENUM-ONLY twin of [lexicalTypeSymbolForNode], kept separate because
     * its reader ([Checker.lexicalEnumSymbolForDiscriminant]) hands the answer to
     * `canonicalEnumSymbol` and to the enum-value tables, where a `class` or an
     * `interface` of the same name is not an answer at all.
     *
     * This is `LexicalScopeResolver`'s own rule one layer up (round 918): a consumer
     * whose axes differ gets its own entry point rather than a widened existing one.
     */
    fun lexicalEnumSymbolForNode(node: Node, name: String): Symbol? {
        if (name !in checker.lexicalBlockScopedEnumNames) return null
        val scopes = lexicalResolver.scopesOfOwningFile(node) ?: return null
        return lexicalResolver.symbolAt(node, name, scopes, flags = SymbolFlags.Enum)
    }

    /**
     * (CHK.78)(b) INV.3(c)(iv) as a standalone consult, so the rule has ONE home
     * and both its consumers — [lookupPerFileForNode] and the lens's
     * `typeReferenceSymbol` — ask the same question.
     *
     * [ambientBlock] is the innermost enclosing string-named `declare module
     * "<spec>"` block (null when the node is in none), [owner] the file the node
     * lives in. Answers the AUGMENTED module's own symbol for [name] when the
     * specifier names a program file that EXPORTS it (INV.3(d): the merged
     * instance no longer exists — the target file's local is the symbol tsc sees
     * there), and null everywhere else: a fileless AMBIENT target and a
     * non-relative one keep the resolution they had.
     *
     * (CHK.82)(1) SECOND LEG — a name the target does NOT export, declared by the
     * BLOCK ITSELF (`declare module "./types" { interface ZzzLocal { … };
     * interface SourceFile { p: ZzzLocal } }`). tsc checks the body in the
     * augmented module's context, where the block's own declarations are in
     * scope; here the per-file consult below cannot see them (they live in the
     * augmentation symbol's `exports`, which the binder filled in
     * `bindModuleDeclaration`), so `p` typed `any` — silently, since `any` is
     * legal everywhere.
     *
     * THE NARROWING IS LOAD-BEARING AND IS WHY THIS IS TWO LEGS RATHER THAN ONE
     * TABLE: (CHK.76) measured that consulting a string-named block's body
     * WHOLESALE costs +43 rows on three profiles, because the block's own
     * PARTIAL `interface SourceFile` is a separate symbol here (INV.3(c)(iv)) and
     * would shadow the merged one at every reference. Asking the block only for
     * a name the target does not export excludes that entire class by
     * construction — a partial re-declaration is, by definition, of a name the
     * target exports.
     */
    private fun augmentationContextSymbol(
        ambientBlock: ModuleDeclaration?,
        owner: SourceFile,
        name: String,
    ): Symbol? {
        val block = ambientBlock ?: return null
        val spec = (block.name as? StringLiteralNode)?.text ?: return null
        val target = resolveModuleSpecifierRelativeJsAware(spec, owner.fileName) ?: return null
        val targetFile = fileResults[target]?.sourceFile ?: return null
        if (name in checker.moduleNamedExportsOf(targetFile)) return lookupPerFile(target, name)
        return nodeSymbolOf(block)?.exports?.get(name)
    }

    /**
     * (CHK.78)(c) [augmentationContextSymbol] for a caller holding only the node —
     * the innermost enclosing string-named `ModuleDeclaration` and the owning
     * file are recovered by the same bounded parent walk
     * [lookupPerFileForNode] does. Off the hot path by construction: its one
     * caller is the lens, which exists only while a [CheckedNodeSink] is attached.
     */
    fun augmentationContextSymbolForNode(node: Node, name: String): Symbol? {
        var cur: Node? = node
        var hops = 0
        var ambientBlock: ModuleDeclaration? = null
        while (cur != null && hops++ < 4096) {
            if (cur is SourceFile) return augmentationContextSymbol(ambientBlock, cur, name)
            if (ambientBlock == null && cur is ModuleDeclaration && cur.name is StringLiteralNode) {
                ambientBlock = cur
            }
            cur = (cur as NodeBase).parent
        }
        return null
    }

    fun resolveQualifiedName(qn: QualifiedName): Symbol? {
        val left = when (val l = qn.left) {
            // INV.3(d): node-keyed root (was the merged `globals`) — the retired
            // merge no longer holds module-file namespaces; an own/imported root
            // resolves per-file to the same declaring-file instance.
            // (CHK.76) `M.D` written inside `namespace N { namespace M {…} }`: the
            // root is a member of an enclosing namespace before it is a file-level name.
            // (INV.0) step 10c: the scope-space root, FIRST and evidence-gated — see
            // [lexicalQualifiedRootSymbolForNode] for why a root that cannot answer a
            // member is deliberately not adopted.
            is Identifier -> lexicalQualifiedRootSymbolForNode(l, l.text)
                ?: lookupInEnclosingNamespaces(l, l.text, checker.QUALIFIED_LEFT_MEANING)
                ?: lookupPerFileForNode(l, l.text)
            is QualifiedName -> resolveQualifiedName(l)
            else -> null
        }
        val resolved = left?.let { resolveAlias(it) }
        resolved?.exports?.get(qn.right.text)?.let { return it }
        // INV.3(d): a namespace-IMPORT-rooted chain (`ts.DocumentRegistry`,
        // `ts.server.Session`) — resolveAlias cannot follow NamespaceImports
        // (round 444) and the retired merge no longer leaks the member name;
        // resolve through the target modules' exports directly.
        return resolveNsQualifiedFromQualifiedName(qn)
            // (CHK.80)(a) LAST: the head is a namespace import / `require` alias of a
            // FILELESS ambient module (`import * as net from "node:net"` inside a
            // `declare module` block), which `resolveAlias` leaves unresolved, or an
            // ambient carrier whose own exports lack the name because its body is
            // wiring (`export = X`, `export * from "m"`) — the (CHK.79) surface walk,
            // which the heritage arm already takes. Consulted only after every
            // other leg missed, so a resolvable name costs nothing here; without it
            // `x: net.Socket` written in such a block typed `any` while `class X
            // extends net.Socket` beside it inherited (measured: 14 of 15 probes
            // silent on a (CHK.79)-shaped fixture, tsgo reporting all 15).
            ?: resolved?.let { ambientModuleSurfaceMember(it, qn.right.text, HashSet()) }
    }

    /**
     * Resolve a heritage-clause base expression (Identifier or PropertyAccessExpression)
     * to a Symbol. For `Foo.I1`, walks `Foo`'s `exports` to find `I1` but only when
     * `I1` is actually exported (i.e., would be accessible from outside `Foo`). For
     * multi-segment `A.B.C`, recursively resolves the parent then walks exports.
     * Returns null when not exported (caller should also emit TS2694).
     */
    /**
     * (CHK.77) The HEAD of a dotted heritage base — the `JsTyping` of `extends
     * JsTyping.TypingResolutionHost`, the `ts` of `ts.server.A` — asked for the
     * meaning tsc's `resolveEntityName` gives the left of a qualified name
     * ([QUALIFIED_LEFT_MEANING]: a namespace or an enum, an alias answered for
     * every meaning). The Identifier arm of [resolveHeritageBaseSymbol] asks
     * `Type | Value`, which a namespace holding only interfaces — a
     * `NamespaceModule`, neither a type nor a value — never satisfies, so
     * `typescript.d.ts:2679`'s `InstallTypingHost` lost its supertype.
     */
    fun resolveHeritageBaseHead(expr: Expression): Symbol? = when (expr) {
        is Identifier -> lookupInEnclosingNamespaces(expr, expr.text, checker.QUALIFIED_LEFT_MEANING)
            ?: lookupPerFileForNode(expr, expr.text)
        is PropertyAccessExpression -> resolveHeritageBaseSymbol(expr)
        else -> null
    }

    /**
     * (CHK.77) tsc's inherited `NodeFlags.Ambient`: [node] sits under a `declare`d
     * declaration, or in a declaration file. Bounded parent walk.
     */
    private fun isInAmbientContext(node: Node): Boolean {
        var cur: Node? = (node as NodeBase).parent
        var hops = 0
        while (cur != null && hops++ < 4096) {
            if (cur is SourceFile) return checker.isDtsFile(cur.fileName)
            if (cur is ModuleDeclaration && ModifierFlag.Declare in cur.modifiers) return true
            cur = (cur as NodeBase).parent
        }
        return false
    }

    fun resolveHeritageBaseSymbol(expr: Expression): Symbol? {
        return when (expr) {
            // (CHK.49) INV.3(d)(ii), node-keyed — the same treatment the three
            // Identifier-rooted heritage sites that CALL this one already carry.
            // A raw `globals` consult reaches a module file's own namespace only
            // while that namespace's NAME happens to collide with a lib global
            // and the merge fuses the two: `class Promise<R> implements
            // Promise.Thenable<R>` resolved (bluebirdStaticThis) and the identical
            // shape spelled `Zromise` did NOT, on the parent binary. Both work
            // through the per-file probe, which degrades to `globals` for every
            // name with no per-file meaning.
            // (CHK.76) `interface X extends Node` inside `declare namespace ts`: the
            // base is the namespace's own `Node`, never the lib's — 509 of
            // `typescript.d.ts`'s clauses answered null (or the DOM's) here.
            // (INV.0) step 10c: the SCOPE-SPACE consult goes FIRST, for round 748's
            // reason — of the two B83.5 failure modes only one is a miss, so a fallback
            // cannot fix the shadowing half. `declareLexical` refuses any name the main
            // binder already bound in that container, so this cannot change how a bound
            // base resolves.
            is Identifier -> lexicalHeritageSymbolForNode(expr, expr.text)
                ?: lookupInEnclosingNamespaces(expr, expr.text, SymbolFlags.Type or SymbolFlags.Value)
                ?: lookupPerFileForNode(expr, expr.text)
            is PropertyAccessExpression -> {
                val parent = resolveHeritageBaseHead(expr.expression) ?: return null
                val resolvedParent = resolveAlias(parent)
                val propName = (expr.name).text
                val memberSym = resolvedParent.exports?.get(propName)
                    ?: return ambientModuleSurfaceMember(resolvedParent, propName, HashSet())
                // Implicit-export rules:
                //  - Sub-namespaces (Module flag) are always accessible (parent.Sub usage).
                //  - `declare namespace` members are implicitly exported (any decl carries Declare).
                //  - (CHK.77) …and so are the members of a namespace NESTED in an ambient
                //    one, or declared in a `.d.ts`: tsc's `setExportContextFlag` reads the
                //    inherited `NodeFlags.Ambient`, not a `declare` keyword on the block
                //    itself — `extends ts.server.A` inside `declare namespace ts` was
                //    skipped here while the annotation `a: ts.server.A` beside it resolved.
                // Otherwise require explicit `export` modifier on the member declaration.
                if (memberSym.flags.hasAny(SymbolFlags.Module)) return memberSym
                val parentIsAmbient = resolvedParent.declarations.any {
                    it is ModuleDeclaration && (ModifierFlag.Declare in it.modifiers || isInAmbientContext(it))
                }
                if (parentIsAmbient) return memberSym
                val memberIsExported = memberSym.flags.hasAny(SymbolFlags.ExportValue) ||
                    memberSym.declarations.any { d ->
                        when (d) {
                            is FunctionDeclaration -> ModifierFlag.Export in d.modifiers
                            is ClassDeclaration -> ModifierFlag.Export in d.modifiers
                            is InterfaceDeclaration -> ModifierFlag.Export in d.modifiers
                            is TypeAliasDeclaration -> ModifierFlag.Export in d.modifiers
                            is EnumDeclaration -> ModifierFlag.Export in d.modifiers
                            is ModuleDeclaration -> ModifierFlag.Export in d.modifiers
                            else -> false
                        }
                    } ||
                    // VariableStatement export check: scan parent module body
                    resolvedParent.declarations.any { nsDecl ->
                        val body = (nsDecl as? ModuleDeclaration)?.body as? ModuleBlock
                        body?.statements?.any { stmt ->
                            stmt is VariableStatement && ModifierFlag.Export in stmt.modifiers &&
                                stmt.declarationList.declarations.any { vd ->
                                    vd.name is Identifier && (vd.name).text == propName
                                }
                        } == true
                    }
                if (!memberIsExported) return null
                memberSym
            }
            else -> null
        }
    }

    /**
     * (CHK.79) The symbol [name] denotes on the SURFACE of the ambient module
     * [module] stands for — [module] being either the alias of a namespace import
     * (`import * as net from "node:net"` / `import net = require("net")`) that
     * [resolveAlias] could not follow (a fileless target), or an ambient module
     * carrier (`globals["node:net"]`) whose own exports lack the name because its
     * body is WIRING. The walk is the one the externals generator's (EXT.19)
     * `moduleMember` performs syntactically, on the binder's tables: the block's
     * own exports; then, in body order, what an `export = X` re-routes (the block's
     * namespace `X`, or another module through an `import X = require("m")`
     * alias — `@types/node`'s `node:stream` → `stream` → its namespace `Stream`)
     * and what each `export * from "m"` re-exports (the `node:net` → `net` idiom).
     * First declared wins; [visited] cuts a cycle (two blocks re-exporting each
     * other). An import binding of the block is not a member of its surface
     * (tsc's `declareModuleMember` exports an import-equals only with the
     * modifier), so a bare alias declared IN the block is skipped — the `export`
     * -modified form stays visible, as it is in tsc.
     *
     * A FILE-backed target (an ambient block re-exporting a program file) answers
     * that file's own exported binding, `export *` chains followed
     * ([resolveExportedSymbolThroughStars]). Null where nothing declares the name,
     * which is what tsgo reports as TS2339 at the base expression.
     *
     * Measured on `@types/node` 20.19.43 after (CHK.77): every dotted heritage base
     * whose head is such an import answered null here (the lens's
     * `heritageBaseSymbol` — 40 bases the externals generator reaches, 22 of them
     * `stream.Transform`; the queue's "17" undercounted), and a consumer of
     * `tls.TLSSocket` did not inherit `net.Socket`'s members — silently on the class
     * (an unresolved base is `any`) and as a false TS2339 on an interface
     * (`TlsOptions extends net.ServerOpts`). `NamespaceImportHeritageTest` pins both
     * channels; 7 of its 9 pins redden without this.
     */
    fun ambientModuleSurfaceMember(module: Symbol, name: String, visited: MutableSet<Int>): Symbol? {
        if (!visited.add(module.id)) return null
        // An alias that is NOT itself a module: a merged carrier may carry the Alias
        // bit beside its Module one (an `import net = require("net")` alias and the
        // `"net"` carrier merge by name here), and its own surface comes first.
        if (module.flags.hasAny(SymbolFlags.Alias) && !module.flags.hasAny(SymbolFlags.Module)) {
            val target = checker.ambientModuleOfImportAlias(module) ?: return null
            return ambientModuleSurfaceMember(target, name, visited)
        }
        module.exports?.get(name)?.let { hit ->
            val importOnly = hit.flags.hasAny(SymbolFlags.Alias) &&
                hit.declarations.isNotEmpty() &&
                hit.declarations.all { d ->
                    checker.isImportBindingDecl(d) && !(d is ImportEqualsDeclaration && ModifierFlag.Export in d.modifiers)
                }
            if (!importOnly) return hit
        }
        for (decl in module.declarations) {
            val body = ((decl as? ModuleDeclaration)?.body as? ModuleBlock) ?: continue
            for (stmt in body.statements) {
                when (stmt) {
                    is ExportAssignment -> {
                        if (!stmt.isExportEquals) continue
                        val id = stmt.expression as? Identifier ?: continue
                        val target = module.exports?.get(id.text) ?: continue
                        val resolved = if (target.flags.hasAny(SymbolFlags.Module)) target
                        else checker.ambientModuleOfImportAlias(target) ?: resolveAlias(target)
                        if (resolved === target && resolved.exports == null) continue
                        ambientModuleSurfaceMember(resolved, name, visited)?.let { return it }
                    }
                    is ExportDeclaration -> {
                        val clause = stmt.exportClause
                        if (clause != null) {
                            // (CHK.81) A LOCAL re-export clause — `@types/node`'s `namespace
                            // EventEmitter { export { internal as EventEmitter } }`, whose
                            // `internal` is the block's `import internal = require("node:events")`
                            // — names the member under the EXPORTED spelling; a clause with a
                            // specifier (`export { a } from "m"`) is not followed.
                            if (stmt.moduleSpecifier != null || clause !is NamedExports) continue
                            for (el in clause.elements) {
                                if (el.name.text != name) continue
                                val localName = (el.propertyName ?: el.name).text
                                // The local is the namespace's own member, or — `@types/node`'s
                                // shape — the ENCLOSING block's `import internal = require(…)`;
                                // the binder declares the specifier itself under the LOCAL name
                                // as an alias, which names nothing, so that entry is skipped.
                                val local = module.exports?.get(localName)
                                    ?.takeIf { l -> l.declarations.any { it !is ExportSpecifier } }
                                    ?: checker.enclosingAmbientBlockMember(stmt, localName) ?: continue
                                val resolved =
                                    if (local.flags.hasAny(SymbolFlags.Alias) && !local.flags.hasAny(SymbolFlags.Module))
                                        resolveAlias(local).takeIf { it !== local } ?: checker.ambientModuleOfImportAlias(local) ?: local
                                    else local
                                return resolved
                            }
                            continue
                        }
                        val spec = (stmt.moduleSpecifier as? StringLiteralNode)?.text ?: continue
                        val ctx = owningSourceFile(stmt)?.fileName
                        val targetFile = resolveModuleSpecifier(spec, stmt)
                            ?: ctx?.let { resolveModuleSpecifierRelativeJsAware(spec, it) }
                            ?: resolveImportTargetFallback(spec, ctx)
                        if (targetFile != null) {
                            val tr = fileResults[targetFile] ?: continue
                            (tr.locals[name] ?: checker.resolveExportedSymbolThroughStars(tr.sourceFile, name))
                                ?.let { return it }
                            continue
                        }
                        val target = globals[spec] ?: continue
                        if (!target.flags.hasAny(SymbolFlags.Module)) continue
                        ambientModuleSurfaceMember(target, name, visited)?.let { return it }
                    }
                    else -> {}
                }
            }
        }
        return null
    }

    /**
     * Resolve a TypeReference typeName (Identifier or QualifiedName) to a Symbol.
     *
     * INV.3(c)(iv) (round 508): the Identifier branch keys the merged-globals
     * consult by the NAME NODE'S owning file — a type name resolves under the
     * visibility of the file its annotation lives in (tsc semantics; a
     * types.ts annotation resolves in types.ts's scope whatever file is being
     * checked). A module-only name with no meaning in the owning file returns
     * null (the conflation leak, killed — real tsc sees TS2304 there); every
     * visible name keeps resolving to the SAME merged instance, and node-keyed
     * resolution is a fixed property of the node, so the `nodeTypes` cache
     * stays valid. Synthesized/unindexed Identifiers degrade to the legacy
     * merged consult inside [lookupPerFileForNode]. NOTE the two call sites
     * with their own trailing `?: globals[name]` fallback gate it to
     * QualifiedName ([getTypeFromTypeReference], [checkConstraintsInTypeNode])
     * — for an Identifier that fallback was byte-redundant pre-flip and would
     * silently re-leak post-flip.
     */
    fun resolveTypeNameToSymbol(node: Node, enclosingNamespacesDone: Boolean = false): Symbol? {
        return when (node) {
            is Identifier -> {
                // (REL.1)(c) step 4: a function-body-scoped `enum` is invisible to the
                // conventional consult (B83.5) and a name SHADOWING an outer one
                // resolves there to the WRONG (outer) symbol — so the position-aware
                // lexical consult must come FIRST, not as a miss-fallback.
                lexicalTypeSymbolForNode(node, node.text)?.let { return it }
                // (CHK.76) a name declared in an enclosing namespace body — innermost
                // first, BEFORE the per-file consult, which would otherwise hand a
                // nested namespace's `Node` to the root's (or a lib's) `Node`.
                if (!enclosingNamespacesDone) {
                    lookupInEnclosingNamespaces(node, node.text, SymbolFlags.Type)?.let { return it }
                }
                val sym = lookupPerFileForNode(node, node.text)
                // INV.3(d): the import-shadowed-by-value-local TYPE/VALUE split —
                // `import { SourceMapSource }` (a type) + a same-named local
                // `function SourceMapSource` (a value): the binder's last-wins keeps
                // only the value in file locals, and the retired merge no longer
                // provides the interface through `globals`. tsc resolves the TYPE
                // space to the import; recover it from the ImportSpecifier's own
                // recorded alias symbol when the per-file winner lacks a type side.
                if (sym != null && !symbolHasTypeSideDeclaration(sym)) {
                    owningSourceFile(node)?.let { owner ->
                        typeSideImportFallback(owner, node.text)?.let { return it }
                    }
                }
                sym
            }
            is QualifiedName -> resolveQualifiedName(node)
            else -> null
        }
    }

    /** INV.3(d): does [sym] declare anything in TYPE space (or carry an alias hop
     *  that could)? Declaration-based, never flags (the isValueExport gotcha). */
    private fun symbolHasTypeSideDeclaration(sym: Symbol): Boolean = sym.declarations.any {
        it is InterfaceDeclaration || it is ClassDeclaration || it is TypeAliasDeclaration ||
            it is EnumDeclaration || it is ModuleDeclaration || it is TypeParameter ||
            checker.isImportBindingDecl(it)
    }

    /** INV.3(d): resolve [memberName] from a namespace-flavored ALIAS symbol —
     *  hopping NAMED-import re-publications (the round-479 barrel shape:
     *  `import * as NS from …; export { NS };`) to the underlying
     *  namespace-import, then reading the member from its target module
     *  (`export *`-following). Context files derive from the declaration
     *  nodes' owning files, so intermediate hops need no threading. */
    fun namespaceAliasMemberSymbol(alias: Symbol, memberName: String): Symbol? {
        var cur: Symbol? = alias
        var hops = 0
        while (cur != null && hops++ < 5) {
            // A namespace-import alias — resolve its target module and read the member.
            val nsImport = cur.declarations.firstOrNull {
                it is ImportDeclaration && it.importClause?.namedBindings is NamespaceImport
            } as? ImportDeclaration
            if (nsImport != null) {
                val ctx = owningSourceFile(nsImport)?.fileName ?: return null
                val spec = (nsImport.moduleSpecifier as? StringLiteralNode)?.text ?: return null
                // Round 513: the DIR-RELATIVE leg (the round-511 lesson class) — a
                // path-shaped extensionless specifier resolves only relative to the
                // importing file's directory. Purely additive.
                val target = resolveModuleSpecifier(spec, nsImport)
                    ?: resolveAliasJsModuleSpecifier(spec, ctx)
                    ?: resolveModuleSpecifierRelativeJsAware(spec, ctx)
                    ?: resolveImportTargetFallback(spec, ctx) ?: return null
                val tr = fileResults[target] ?: return null
                if (memberName in checker.moduleNamedExportsOf(tr.sourceFile)) {
                    tr.locals[memberName]?.let { return it }
                }
                return checker.resolveExportedSymbolThroughStars(tr.sourceFile, memberName)
            }
            // An `export * as NS from "./x"` re-publication (the tsc `_namespaces`
            // barrel idiom) — the binder binds NS as an Alias declared by the
            // ExportDeclaration itself; resolve its specifier and read the member.
            val nsExport = cur.declarations.firstOrNull {
                it is ExportDeclaration && it.exportClause is NamespaceExport &&
                    it.moduleSpecifier is StringLiteralNode
            } as? ExportDeclaration
            if (nsExport != null) {
                val ctx = owningSourceFile(nsExport)?.fileName ?: return null
                val spec = (nsExport.moduleSpecifier as? StringLiteralNode)?.text ?: return null
                val target = resolveModuleSpecifier(spec, nsExport)
                    ?: resolveAliasJsModuleSpecifier(spec, ctx)
                    ?: resolveModuleSpecifierRelativeJsAware(spec, ctx)
                    ?: resolveImportTargetFallback(spec, ctx) ?: return null
                val tr = fileResults[target] ?: return null
                if (memberName in checker.moduleNamedExportsOf(tr.sourceFile)) {
                    tr.locals[memberName]?.let { return it }
                }
                return checker.resolveExportedSymbolThroughStars(tr.sourceFile, memberName)
            }
            // A NAMED-import alias — hop to the target module's binding for the
            // original name.
            val spec2 = cur.declarations.firstOrNull { it is ImportSpecifier } as? ImportSpecifier
                ?: return null
            val originalName = spec2.propertyName?.text ?: spec2.name.text
            val (ctxFile, importDecl) = checker.findEnclosingImport(spec2) ?: return null
            val modSpec = (importDecl.moduleSpecifier as? StringLiteralNode)?.text ?: return null
            val target = resolveModuleSpecifier(modSpec, importDecl)
                ?: resolveAliasJsModuleSpecifier(modSpec, ctxFile)
                ?: resolveModuleSpecifierRelativeJsAware(modSpec, ctxFile) ?: return null
            val tr = fileResults[target] ?: return null
            cur = if (originalName in checker.moduleNamedExportsOf(tr.sourceFile)) {
                tr.locals[originalName] ?: checker.resolveExportedSymbolThroughStars(tr.sourceFile, originalName)
            } else {
                checker.resolveExportedSymbolThroughStars(tr.sourceFile, originalName)
            }
        }
        return null
    }

    /** INV.3(d): resolve a DOTTED namespace-qualified value chain `A.B.…​.member`
     *  (`ts.server.Logger` — `ts` a namespace-import, `server` a re-published
     *  namespace-import) to the final member's symbol. Heritage bases use this;
     *  null on any unresolvable segment. */
    fun resolveNamespaceQualifiedSymbol(pa: PropertyAccessExpression): Symbol? {
        val prefix: Symbol? = when (val e = pa.expression) {
            is Identifier -> owningSourceFile(e)?.let { o -> fileResults[o.fileName]?.locals?.get(e.text) }
            is PropertyAccessExpression -> resolveNamespaceQualifiedSymbol(e)
            else -> null
        }
        return prefix?.let { namespaceAliasMemberSymbol(it, pa.name.text) }
    }

    /** INV.3(d): the [resolveNamespaceQualifiedSymbol] sibling over TYPE-position
     *  [QualifiedName] chains (`ts.DocumentRegistry`, `ts.server.Session`). */
    private fun resolveNsQualifiedFromQualifiedName(qn: QualifiedName): Symbol? {
        val prefix: Symbol? = when (val l = qn.left) {
            is Identifier -> owningSourceFile(l)?.let { o -> fileResults[o.fileName]?.locals?.get(l.text) }
            is QualifiedName -> resolveNsQualifiedFromQualifiedName(l)
            else -> null
        }
        return prefix?.let { namespaceAliasMemberSymbol(it, qn.right.text) }
    }

    /** INV.3(d): resolve the TYPE side of a name whose per-file VALUE local shadowed
     *  its own file's named import (binder last-wins) — the ImportSpecifier's alias
     *  symbol is still recorded in the binder's nodeToSymbol; resolve it onward and
     *  keep the result only when it genuinely has a type side. Memoized per
     *  (file, name); null (cached) = no shadowed type import. */
    fun typeSideImportFallback(owner: SourceFile, name: String): Symbol? {
        val key = owner.fileName + "|" + name
        if (typeSideImportFallbackCache.containsKey(key)) return typeSideImportFallbackCache[key]
        val result = run {
            val br = fileResults[owner.fileName] ?: return@run null
            for (stmt in owner.statements) {
                if (stmt !is ImportDeclaration) continue
                val named = stmt.importClause?.namedBindings as? NamedImports ?: continue
                val spec = named.elements.firstOrNull { it.name.text == name } ?: continue
                val alias = br.nodeToSymbol[nodeKey(spec)] ?: continue
                return@run resolveImportedSymbolGeneral(alias)
                    ?.takeIf { it.declarations.any { d -> !checker.isImportBindingDecl(d) } && symbolHasTypeSideDeclaration(it) }
            }
            null
        }
        typeSideImportFallbackCache[key] = result
        return result
    }
}
