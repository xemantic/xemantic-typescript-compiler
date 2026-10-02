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
 * (INV.0) (P18.255) — the NAMED / DEFAULT IMPORT EXISTENCE family: the default-import
 * legality pass ([checkDefaultImports]: TS1192 / TS2613 / TS2614 / TS2616 / TS2595 / TS2597,
 * with tsgo's `canHaveSyntheticDefault` and its `__esModule` marker test
 * [declaresEsModuleMarker]), the named-import / `export { … } from` existence pass
 * ([checkNamedImportExistence]), tsgo's `errorNoModuleMemberSymbol` order
 * ([emitAbsentNamedMember]: TS2724 / TS2614 / TS2460 / TS2459 / TS2305,
 * [emitMissingMemberSuggestion]), the TS2305 emitter [emitTs2305], and the local-declaration
 * readers the TS2459 / TS2460 / TS2728 rows need. Extracted VERBATIM from `Checker.kt` (four
 * spans: 53063-53423, 53684-54012, 54344-54468, 54977-54988); the program inputs come in through
 * the constructor and every other Checker member it reads is reached through [checker]. Ambient
 * reads: `docs/inversion-ambient-ledger.md` row 17.
 */
/** tsgo's `extensionsToRemove` (tspath/extension.go:43), in its order. */
/** tsgo's `SymbolFlagsModuleMember`: the export kinds a misspelt named import is matched against. */
private val MODULE_MEMBER = SymbolFlags.Variable or SymbolFlags.Function or SymbolFlags.Class or
    SymbolFlags.Interface or SymbolFlags.Enum or SymbolFlags.Module or SymbolFlags.TypeAlias or SymbolFlags.Alias

private val TS_MODULE_EXTENSIONS = listOf(
    ".d.ts", ".d.mts", ".d.cts", ".mjs", ".mts", ".cjs", ".cts", ".ts", ".js", ".tsx", ".jsx", ".json",
)

internal class NamedImportExistence(
    private val checker: Checker,
    private val options: CompilerOptions,
    private val binderResults: List<BinderResult>,
    private val isMultiFileSource: Boolean,
    private val fileResults: Map<String, BinderResult>,
) {

    fun checkDefaultImports() {
        // (LEGACY.1)(d2) Whether a default import is legal against a module with no
        // `export default` is tsgo's `canHaveSyntheticDefault` (checker.go:14744), decided
        // per TARGET and reading NO option: TypeScript 7 removed the `false` values of
        // `esModuleInterop` and `allowSyntheticDefaultImports` and consults neither field
        // anywhere but the TS5108 row (`program.go:862-868`). The tsc-6 model this replaced
        // honoured an explicit `allowSyntheticDefaultImports: true` as a BLANKET skip of
        // every CommonJS-format target (so `import d from "./esm"` against a `.ts` module
        // with no default went silent — tsgo reports TS1192 there in every cell of the
        // 3×3 matrix) and an explicit `false` as TS1259 on an `export =` target (tsgo:
        // legal, `__importDefault`). The rule, per target, is in the `syntheticDefault`
        // `when` below; an ESM-format target never has one.

        val isMultiFile = binderResults.size > 1 || isMultiFileSource
        if (!isMultiFile) return

        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            // (CHK.198) a declaration file and a type-only clause skip the default-import
            // and TS2614 rules, never the `export =` named-import rule below (tsgo reports
            // it in both).
            val isDts = checker.isDtsFile(fileName)
            val source = result.sourceFile.text

            for (stmt in result.sourceFile.statements) {
                if (stmt !is ImportDeclaration) continue
                val importClause = stmt.importClause ?: continue
                // Type-only imports don't need a runtime default export
                val defaultRulesApply = !isDts && !importClause.isTypeOnly

                // Resolve the module specifier
                val specifier = stmt.moduleSpecifier
                val moduleName = (specifier as? StringLiteralNode)?.text ?: continue
                // Fall back to extension-mapping relative resolution so a runtime `.mjs`/`.cjs`
                // specifier (nodenext) resolves to its `.mts`/`.d.mts`/`.cts` declaration sibling.
                // Round 479: a BARE specifier under nodenext NEVER resolves relative —
                // node module kinds use node_modules lookup only (the B235 block below),
                // while classic/node10 keep the permissive relative fallback. Without the
                // gate, harnessIO.ts's `import pathModule from "path"` (the node builtin)
                // "resolved" to src/compiler/path.ts and FP'd TS1192.
                val bareSpec = !moduleName.startsWith(".") && !moduleName.startsWith("/")
                var resolvedFile = if (bareSpec && options.effectiveModule.isNodeNext) null
                else checker.resolveModuleSpecifier(moduleName)
                    ?: checker.resolveRelativeIncludingIndex(moduleName, fileName)
                // B235: a BARE specifier resolving into node_modules whose nearest
                // package.json declares "type": "module" is an ESM-format target — no
                // synthetic default is ever produced for it, so a default import against
                // a no-`export default` package is TS1192 even under esModuleInterop
                // (esmNoSynthesizedDefault). Gated to bundler/node modes AND the
                // type:module package — a CJS-format node_modules target keeps the
                // pre-existing skip (synthetic default legal → never resolved here).
                var nmEsmTarget = false
                // (LEGACY.1)(e) the former `isNodeNext || bundler` RESOLUTION gate was a proxy,
                // and wrong in both directions — measured on tsgo 7.0.2: `import d from "esm"`
                // against a `"type": "module"` package is TS1192 under `module: esnext` with
                // NO `moduleResolution` at all (the gate said silent) and SILENT under
                // `module: commonjs` with an explicit `bundler` (the gate said TS1192). What
                // decides it is the IMPORTER's emit format (tsgo's usage mode): an ESM importer
                // gets no synthetic default from an ESM target, a CommonJS one does.
                if (resolvedFile == null && !moduleName.startsWith(".") && !moduleName.startsWith("/") &&
                    isESModuleFormat(options, fileName)) {
                    val nm = checker.resolveBareNodeModulesAnyPrefix(moduleName, fileName)
                    if (nm != null && checker.nodeModulesPackageTypeIsModule(nm)) {
                        resolvedFile = nm
                        nmEsmTarget = true
                    }
                }
                // (CHK.198)(d) a default import a bare specifier names, from a CommonJS
                // importer: an ambient `declare module "<spec>"` (tsgo resolves it first) or a
                // `node_modules` declaration file. Both have a synthetic default unless they
                // declare the `__esModule` marker (`canHaveSyntheticDefault`'s declaration-file
                // arm); only the default-import rule is judged for them here.
                var nmCjsTarget = false
                if (resolvedFile == null && bareSpec && defaultRulesApply && importClause.name != null) {
                    val ambient = ambientModuleBlocks()
                    if (moduleName in ambient) {
                        ambient[moduleName]?.let { checkAmbientDefaultImport(source, fileName, moduleName, importClause.name, it) }
                        continue
                    }
                    if (!isESModuleFormat(options, fileName)) {
                        val nm = checker.resolveBareNodeModulesAnyPrefix(moduleName, fileName)
                        if (nm != null && checker.isDtsFile(nm)) {
                            resolvedFile = nm
                            nmCjsTarget = true
                        }
                    }
                }
                if (resolvedFile == null) continue
                val targetResult = fileResults[resolvedFile] ?: continue
                val targetFile = targetResult.sourceFile
                // Empty fixture files (admitted to fileResults so B11.2's resolver can find
                // them) have no statements at all. TypeScript treats such files as untyped
                // modules and skips default-export checking — the user-facing diagnostic in
                // that case is TS6142 from `checkJsxImportResolutions`, and TS1192 would be
                // redundant noise.
                if (targetFile.statements.isEmpty()) continue

                // Under node module kinds, a .mts/.mjs/.d.mts target is an ES module
                // (impliedNodeFormat): no synthetic default is ever produced for it, so all the
                // CJS-synthetic-default suppression below (allowSyntheticDefaultImports / System /
                // esModuleInterop / export=→TS1259) is bypassed and a default import against a
                // no-`export default` module is plain TS1192. Restricted to node modes so amd/
                // system/umd/es2015 behavior is unchanged.
                val targetIsEsm = nmEsmTarget ||
                    ((resolvedFile.endsWith(".mts") || resolvedFile.endsWith(".mjs")) &&
                        options.effectiveModule.isNodeNext)
                val hasDefaultExport = checker.moduleHasDefaultExport(targetFile)
                val hasExportEquals = !targetIsEsm && targetFile.statements.any { it is ExportAssignment && it.isExportEquals }
                val targetIsJs = resolvedFile.endsWith(".js") || resolvedFile.endsWith(".jsx") ||
                    resolvedFile.endsWith(".cjs") || resolvedFile.endsWith(".mjs")
                // A JS CommonJS file (no ESM exports): module.exports IS the synthetic default.
                val targetIsJsCjs = !targetIsEsm && targetIsJs &&
                    targetFile.statements.none { it is ExportAssignment || it is ExportDeclaration ||
                        (it is FunctionDeclaration && ModifierFlag.Export in it.modifiers) ||
                        (it is ClassDeclaration && ModifierFlag.Export in it.modifiers) ||
                        (it is VariableStatement && ModifierFlag.Export in it.modifiers) }
                // tsgo's `canHaveSyntheticDefault`, arm for arm: two ESM files never have one;
                // under node16+ a CommonJS target imported into ESM always has one; a
                // declaration file has one unless it declares the `__esModule` marker (its
                // syntactic default, when present, is a real default — `hasDefaultExport`);
                // a TypeScript file has one exactly when it is an `export =` module; a JS file
                // when it carries no ES-module syntax and no `__esModule`.
                val syntheticDefault = when {
                    targetIsEsm -> false
                    options.effectiveModule.isNodeNext && isESModuleFormat(options, fileName) ->
                        !isESModuleFormat(options, resolvedFile)
                    checker.isDtsFile(resolvedFile) -> !declaresEsModuleMarker(targetFile)
                    !targetIsJs -> hasExportEquals
                    else -> targetIsJsCjs
                }
                val defaultBinding = importClause.name
                if (defaultRulesApply && defaultBinding != null && !hasDefaultExport && !syntheticDefault) {
                    // (CHK.197) tsgo names the module by its symbol — the RESOLVED file name
                    // with a known extension removed (`tspath.RemoveFileExtension`), quoted:
                    // an absolute path in a project build, the flat name in a corpus fixture.
                    val displayName = TS_MODULE_EXTENSIONS.firstOrNull { resolvedFile.endsWith(it) }
                        ?.let { resolvedFile.removeSuffix(it) } ?: resolvedFile
                    val nameStart = defaultBinding.pos
                    val nameLength = defaultBinding.text.length
                    val (line, character) = checker.getLineAndCharacterOfPosition(source, nameStart)
                    // ((LEGACY.1)(d2) TS1259 *can only be default-imported using the
                    // 'esModuleInterop' flag* is unreachable in TypeScript 7 — an `export =`
                    // target always has the synthetic default — and its emitter is gone;
                    // tsgo's checker never references the message.)
                    // TS2613: Module has no default export. Did you mean to use named import?
                    // Fires when the default binding name matches a named export of the module
                    // (star-following, M1.1 — a name provided via `export *` upgrades the
                    // message; an unknowable set falls back to direct exports, keeping TS1192).
                    val importName = defaultBinding.text
                    val moduleNamedExports = checker.getModuleExportsFollowingStars(targetFile)
                        ?: checker.getModuleNamedExports(targetFile)
                    if (importName in moduleNamedExports) {
                        checker.diagnostics.add(Diagnostic(
                            message = "Module '\"$displayName\"' has no default export. Did you mean to use 'import { $importName } from \"$displayName\"' instead?",
                            category = DiagnosticCategory.Error,
                            code = 2613,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = nameStart,
                            length = nameLength,
                        ))
                    } else {
                        checker.diagnostics.add(Diagnostic(
                            message = "Module '\"$displayName\"' has no default export.",
                            category = DiagnosticCategory.Error,
                            code = 1192,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = nameStart,
                            length = nameLength,
                        ))
                    }
                }

                // TS2614: named import specifier not found in module exports, but
                // module has a default export — suggest using default import instead.
                // Only fires when module HAS a default export (otherwise TS1192 is enough).
                if (defaultRulesApply && !nmCjsTarget && hasDefaultExport) {
                    val namedBindings = importClause.namedBindings
                    // TS2614 is an ABSENCE diagnostic — star-following applies, and an
                    // unknowable export set (null) suppresses it entirely (M1.1 FN-safe).
                    val moduleNamedExports = checker.getModuleExportsFollowingStars(targetFile)
                    if (namedBindings is NamedImports && moduleNamedExports != null) {
                        for (importSpecifier in namedBindings.elements) {
                            if (importSpecifier.isTypeOnly) continue
                            // The "property name" is what's imported; the "name" is the local binding
                            val importedName = (importSpecifier.propertyName ?: importSpecifier.name).text
                            // import { default as X } is a valid way to import the default export — skip
                            if (importedName == "default") continue
                            if (importedName !in moduleNamedExports) {
                                // Squiggle on the propertyName if present, else on the name
                                val nameNode = importSpecifier.propertyName ?: importSpecifier.name
                                // (CHK.190) a spelling suggestion among the exports wins over the
                                // default-import hint (tsgo's `errorNoModuleMemberSymbol` order).
                                val suggestion = checker.getSpellingSuggestionFromNames(importedName, moduleNamedExports + "default")
                                if (suggestion != null) {
                                    emitMissingMemberSuggestion(source, fileName, moduleName, importedName, nameNode, suggestion, targetFile, resolvedFile)
                                    continue
                                }
                                val nameStart = nameNode.pos
                                val nameLength = nameNode.text.length
                                val (line, character) = checker.getLineAndCharacterOfPosition(source, nameStart)
                                checker.diagnostics.add(Diagnostic(
                                    message = "Module '\"$moduleName\"' has no exported member '$importedName'. Did you mean to use 'import $importedName from \"$moduleName\"' instead?",
                                    category = DiagnosticCategory.Error,
                                    code = 2614,
                                    fileName = fileName,
                                    line = line,
                                    character = character,
                                    start = nameStart,
                                    length = nameLength,
                                ))
                            }
                        }
                    }
                }

                // (CHK.197) a named import of an `export =` module — tsgo's
                // `getExternalModuleMember` (checker.go:14593), per specifier
                // ([reportExportEqualsSpecifier]); (CHK.198) type-only specifiers and clauses
                // and declaration-file importers included.
                val namedBindingsEq = importClause.namedBindings
                if (hasExportEquals && !nmCjsTarget && namedBindingsEq is NamedImports) {
                    val target = exportEqualsTarget(targetResult, resolvedFile)
                    for (importSpecifier in namedBindingsEq.elements) {
                        reportExportEqualsSpecifier(
                            source, fileName, moduleName,
                            importSpecifier.propertyName ?: importSpecifier.name, target,
                        )
                    }
                }

                // (LEGACY.0b step 3) TypeScript 7 does not have TS2497 at all: the two
                // options its two wordings name (`esModuleInterop`,
                // `allowSyntheticDefaultImports`) may no longer be set to `false`, so the
                // rule is unreachable — `This_module_can_only_be_referenced_with_ECMAScript_
                // imports...` is in tsgo's message table and referenced by NO tsgo code.
                // A namespace import of an `export =` module is simply accepted.
            }
        }
    }

    /**
     * (CHK.198) What [reportExportEqualsSpecifier] needs to know about an `export =` module:
     * the target's own local name ([selfName], null for a non-identifier target), the
     * names a specifier may legally take ([members], null when they cannot be enumerated
     * soundly) and the module-member exports a misspelt name is matched against
     * ([suggestions], tsgo's `getSuggestedSymbolForNonexistentModule`).
     */
    private class ExportEqualsTarget(
        val targetFile: SourceFile,
        val resolvedFile: String,
        val selfName: String?,
        val members: Set<String>?,
        val suggestions: Map<String, Symbol>,
    )

    private fun exportEqualsTarget(targetResult: BinderResult, resolvedFile: String): ExportEqualsTarget {
        val targetFile = targetResult.sourceFile
        val expr = (targetFile.statements.firstOrNull {
            it is ExportAssignment && it.isExportEquals
        } as? ExportAssignment)?.expression
        val selfName = (expr as? Identifier)?.text?.takeIf { targetResult.locals[it] != null }
        val sym = selfName?.let { targetResult.locals[it] }
        val members = when {
            sym != null -> exportEqualsMemberNames(targetFile, targetResult, sym, resolvedFile)
            selfName == null && expr != null -> objectLiteralNames(expr)
            else -> null
        }
        val suggestions = HashMap<String, Symbol>()
        sym?.exports?.forEach { (name, export) ->
            if (export.flags.hasAny(MODULE_MEMBER) && name.isNotEmpty()) suggestions[name] = export
        }
        return ExportEqualsTarget(targetFile, resolvedFile, selfName, members, suggestions)
    }

    /**
     * (CHK.197) (CHK.198) tsgo's `getExternalModuleMember` (checker.go:14593) for one
     * specifier of a named import or re-export of an `export =` module: a property of the
     * target's type (`getPropertyOfTypeEx(..., skipObjectFunctionPropertyAugment)` — an
     * apparent type for a primitive, the static side for a class, no Object/Function
     * members) or an export of the target symbol is legal; otherwise
     * `errorNoModuleMemberSymbol` — a spelling match among the target's module-member
     * exports reads TS2724, the target's own local name reads TS2595 under `module` >=
     * ES2015, TS2597 from a JS importer, TS2616 elsewhere (one of the three, never two),
     * every other name TS2305. A type-only specifier follows the same rule. When the member
     * set is unknown ([ExportEqualsTarget.members] null) only the self-name row is reported.
     */
    private fun reportExportEqualsSpecifier(
        source: String, fileName: String, moduleName: String, nameNode: Identifier, target: ExportEqualsTarget,
    ) {
        val importedName = nameNode.text
        if (importedName.isEmpty() || importedName == "default") return
        // An unchecked JavaScript importer (`allowJs` without `checkJs`) reports nothing.
        if (checker.isJsLikeFileName(fileName) && !options.checkJs) return
        val members = target.members
        if (members != null && importedName in members) return
        if (members != null) {
            val suggestion = checker.getSpellingSuggestionFromNames(importedName, target.suggestions.keys)
            if (suggestion != null) {
                val decl = target.suggestions[suggestion]?.valueDeclaration?.let { declarationNameSpan(it) }
                emitMissingMemberSuggestion(
                    source, fileName, moduleName, importedName, nameNode, suggestion,
                    target.targetFile, target.resolvedFile, decl,
                )
                return
            }
        }
        if (importedName == target.selfName) {
            val importerIsJs = fileName.endsWith(".js") || fileName.endsWith(".jsx") ||
                fileName.endsWith(".mjs") || fileName.endsWith(".cjs")
            val (code, message) = when {
                options.effectiveModule.isEs2015OrHigher -> 2595 to
                    "'$importedName' can only be imported by using a default import."
                importerIsJs -> 2597 to
                    "'$importedName' can only be imported by using a 'require' call or by using a default import."
                else -> 2616 to
                    "'$importedName' can only be imported by using 'import $importedName = require(\"$moduleName\")' or a default import."
            }
            val nameStart = nameNode.pos
            val (line, character) = checker.getLineAndCharacterOfPosition(source, nameStart)
            checker.diagnostics.add(Diagnostic(
                message = message,
                category = DiagnosticCategory.Error,
                code = code,
                fileName = fileName,
                line = line,
                character = character,
                start = nameStart,
                length = importedName.length,
            ))
        } else if (members != null) {
            emitTs2305(source, fileName, moduleName, importedName, nameNode)
        }
    }

    /**
     * (CHK.197) (CHK.198) The names a specifier may take from an `export =` module whose
     * target is the local [sym]: every export of the symbol (a namespace's values AND types,
     * an enum's members, a class's merged-namespace members — tsgo's `getExportOfModule` on
     * the module, whose exports ARE the target's), plus the properties of its type: a
     * class's static side including statics inherited along a chain of class declarations
     * (plus the base classes' merged-namespace VALUES — an inherited namespace TYPE is not
     * a property), an enum's members, a function's expando assignments in its own scope,
     * an annotated variable's object members or a primitive's wrapper members, an
     * object-literal initializer's own property names. A cross-file `declare module`
     * augmentation adds to a namespace target. Null when the set cannot be enumerated
     * soundly — a base that is not a class declaration, a computed or spread literal member,
     * an expando through a computed key, an initializer of any other form, an unresolvable
     * type — and the caller then reports only the self-name, never a TS2305 it cannot prove.
     */
    private fun exportEqualsMemberNames(
        targetFile: SourceFile,
        targetResult: BinderResult,
        sym: Symbol,
        resolvedFile: String,
    ): Set<String>? {
        val names = HashSet<String>()
        sym.exports?.keys?.forEach { if (it.isNotEmpty()) names += it }
        val flags = sym.flags
        if (flags.hasAny(SymbolFlags.Class)) names += classStaticNames(sym) ?: return null
        if (flags.hasAny(SymbolFlags.Enum)) names += enumMemberNames(sym) ?: return null
        if (flags.hasAny(SymbolFlags.Function)) names += expandoNames(targetFile, sym.name) ?: return null
        if (flags.hasAny(SymbolFlags.Variable)) names += variableMemberNames(sym) ?: return null
        if (flags.hasAny(SymbolFlags.Module)) {
            names += checker.augmentationDeclaredExportNames(resolvedFile)
            names += checker.augmentationInterfaceMemberNames(resolvedFile)
        } else if (!flags.hasAny(SymbolFlags.Class or SymbolFlags.Enum or SymbolFlags.Function or SymbolFlags.Variable)) {
            return null
        }
        return names
    }

    /** A class's static side — own and inherited statics, `prototype`, merged-namespace
     *  values — when every base along the chain is a class declaration; else null. */
    private fun classStaticNames(sym: Symbol): Set<String>? {
        val ctor = checker.classConstructorTypes.constructorTypeOfClass(sym) ?: return null
        val names = HashSet<String>()
        ctor.members?.keys?.forEach { if (it.isNotEmpty()) names += it }
        var cur = sym
        val seen = HashSet<Symbol>()
        while (seen.add(cur)) {
            if (cur.declarations.any { d -> d !is ClassDeclaration && d !is InterfaceDeclaration && d !is ModuleDeclaration }) return null
            if (cur.declarations.any { d -> d is ClassDeclaration && d.members.any(::isComputedStatic) }) return null
            val extends = cur.declarations.filterIsInstance<ClassDeclaration>().mapNotNull { d ->
                d.heritageClauses?.firstOrNull { it.token == SyntaxKind.ExtendsKeyword }
            }
            if (extends.isEmpty()) return names
            // A merged interface's heritage adds base types that are not the class's base.
            if (cur.declarations.any { d -> d is InterfaceDeclaration && !d.heritageClauses.isNullOrEmpty() }) return null
            val iface = checker.getDeclaredTypeOfSymbol(cur) as? Type.Interface ?: return null
            checker.resolveStructuredTypeMembers(iface)
            val base = iface.baseTypes?.singleOrNull() ?: return null
            val baseSym = ((base as? Type.Interface) ?: (base as? Type.Reference)?.target)?.symbol ?: return null
            if (baseSym.declarations.none { it is ClassDeclaration }) return null
            baseSym.exports?.forEach { (name, export) ->
                if (export.flags.hasAny(SymbolFlags.Value) && name.isNotEmpty()) names += name
            }
            cur = baseSym
        }
        return null
    }

    private fun isComputedStatic(m: ClassElement): Boolean = when (m) {
        is PropertyDeclaration -> ModifierFlag.Static in m.modifiers && m.name is ComputedPropertyName
        is MethodDeclaration -> ModifierFlag.Static in m.modifiers && m.name is ComputedPropertyName
        is GetAccessor -> ModifierFlag.Static in m.modifiers && m.name is ComputedPropertyName
        is SetAccessor -> ModifierFlag.Static in m.modifiers && m.name is ComputedPropertyName
        else -> false
    }

    /** Every member name of every declaration of an enum; null for a computed member name. */
    private fun enumMemberNames(sym: Symbol): Set<String>? {
        val names = HashSet<String>()
        for (d in sym.declarations) {
            if (d !is EnumDeclaration) continue
            for (m in d.members) names += literalName(m.name) ?: return null
        }
        return names
    }

    /**
     * The expando properties of the function [name]: `name.p = …` / `name["p"] = …`
     * assignments in the target file's own scope (nested blocks included, function and
     * class bodies not — tsgo binds an expando in the declaring container only). Null on
     * an assignment through a non-literal key.
     */
    private fun expandoNames(targetFile: SourceFile, name: String): Set<String>? {
        val names = HashSet<String>()
        var unknown = false
        fun visit(node: Node) {
            if (unknown) return
            when (node) {
                is FunctionDeclaration, is FunctionExpression, is ArrowFunction, is MethodDeclaration,
                is ClassDeclaration, is ClassExpression, is GetAccessor, is SetAccessor, is Constructor -> return
                is BinaryExpression -> if (node.operator == SyntaxKind.Equals) {
                    when (val left = node.left) {
                        is PropertyAccessExpression ->
                            if ((left.expression as? Identifier)?.text == name) names += left.name.text
                        is ElementAccessExpression -> if ((left.expression as? Identifier)?.text == name) {
                            val key = left.argumentExpression as? StringLiteralNode
                            if (key == null) unknown = true else names += key.text
                        }
                        else -> {}
                    }
                }
                else -> {}
            }
            forEachChild(node, ::visit)
        }
        for (st in targetFile.statements) visit(st)
        return if (unknown) null else names
    }

    /** An annotated variable's object members or a primitive's wrapper members; an
     *  unannotated variable's object-literal initializer's own names; else null. */
    private fun variableMemberNames(sym: Symbol): Set<String>? {
        val decl = sym.declarations.filterIsInstance<VariableDeclaration>().singleOrNull() ?: return null
        val typeNode = decl.type
        if (typeNode != null) {
            val type = checker.getTypeFromTypeNode(typeNode)
            if (type.flags.hasAny(TypeFlags.StringLike or TypeFlags.NumberLike or TypeFlags.BooleanLike)) {
                val wrapper = checker.getApparentType(type) as? Type.Object ?: return null
                checker.resolveStructuredTypeMembers(wrapper)
                return wrapper.members?.keys?.filter { it.isNotEmpty() }?.toSet()
            }
            return checker.collectCommonObjectMemberNames(type)
        }
        return objectLiteralNames(decl.initializer ?: return null)
    }

    /** An object literal's own property names (through parentheses, `satisfies` and `as const`);
     *  null for any other expression and for a spread or computed member. */
    private fun objectLiteralNames(expr: Expression): Set<String>? {
        var e = expr
        while (true) {
            e = when (e) {
                is ParenthesizedExpression -> e.expression
                is SatisfiesExpression -> e.expression
                is AsExpression -> {
                    val t = e.type
                    if (t is TypeReference && (t.typeName as? Identifier)?.text == "const" && t.typeArguments == null) e.expression
                    else return null
                }
                else -> break
            }
        }
        val literal = e as? ObjectLiteralExpression ?: return null
        val names = HashSet<String>()
        for (p in literal.properties) {
            names += when (p) {
                is PropertyAssignment -> literalName(p.name)
                is ShorthandPropertyAssignment -> p.name.text
                is MethodDeclaration -> literalName(p.name)
                is GetAccessor -> literalName(p.name)
                is SetAccessor -> literalName(p.name)
                else -> null
            } ?: return null
        }
        return names
    }

    private fun literalName(name: Node): String? = when (name) {
        is Identifier -> name.text
        is StringLiteralNode -> name.text
        is NumericLiteralNode -> name.text
        else -> null
    }

    /** The (start, length) of a declaration's name, for the TS2728 related row. */
    private fun declarationNameSpan(decl: Node): Pair<Int, Int>? {
        val name: Node? = when (decl) {
            is VariableDeclaration -> decl.name
            is FunctionDeclaration -> decl.name
            is ClassDeclaration -> decl.name
            is EnumDeclaration -> decl.name
            is ModuleDeclaration -> decl.name
            else -> null
        }
        val id = name as? Identifier ?: return null
        return id.pos to id.text.length
    }

    fun checkNamedImportExistence() {
        val isMultiFile = binderResults.size > 1 || isMultiFileSource
        if (!isMultiFile) return

        // Skip when moduleSuffixes is set — module resolution is suffix-aware and we can't
        // resolve to the correct suffixed file (e.g. ./foo → ./foo.ios.ts with suffix ".ios")
        if (!options.moduleSuffixes.isNullOrEmpty()) return

        // (LEGACY.1)(f) the System-only suppression of a `default` re-export's TS2305 is
        // gone: tsgo 7.0.2 reports the row under `system` as under `commonjs` (measured
        // 2026-09-15); the (d2) `allowSyntheticDefaultImports: false` exception went before it.

        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            // Check both .ts and .d.ts files (d.ts files can import from other modules)
            val source = result.sourceFile.text

            for (stmt in result.sourceFile.statements) {
                when (stmt) {
                    is ImportDeclaration -> {
                        val clause = stmt.importClause ?: continue
                        if (clause.isTypeOnly) continue
                        val namedBindings = clause.namedBindings as? NamedImports ?: continue

                        val specifier = stmt.moduleSpecifier
                        val moduleName = (specifier as? StringLiteralNode)?.text ?: continue
                        // Only check relative imports — non-relative imports might resolve incorrectly
                        // (e.g. npm packages with complex node_modules resolution)
                        if (!moduleName.startsWith("./") && !moduleName.startsWith("../")) continue
                        // Scoped `.js`/`.jsx` → `.ts`/`.tsx` fallback: a relative import written with
                        // an explicit `.js` extension (`import { X } from "./utils.js"`) resolves to
                        // the `.ts` source. resolveModuleSpecifierRelative deliberately avoids `.js`
                        // globally (FP-prone), so retry with the extension stripped here only.
                        val resolvedFile = checker.resolveModuleSpecifierRelative(moduleName, fileName)
                            ?: ((if (moduleName.endsWith(".js")) checker.resolveModuleSpecifierRelative(moduleName.removeSuffix(".js"), fileName) else null)
                                ?: (if (moduleName.endsWith(".jsx")) checker.resolveModuleSpecifierRelative(moduleName.removeSuffix(".jsx"), fileName) else null))
                            ?: continue
                        val targetResult = fileResults[resolvedFile] ?: continue
                        val targetFile = targetResult.sourceFile

                        // If target has export=, it cannot be named-imported
                        val hasExportEquals = targetFile.statements.any { it is ExportAssignment && it.isExportEquals }
                        // Get all available exports (named + default), following `export *`
                        // chains (M1.1). An UNKNOWABLE set (null — some star target didn't
                        // resolve) means non-default absence can't be proven → those specs
                        // are skipped below. The `default` spec stays decidable from the
                        // DIRECT file: `export *` never forwards a default export.
                        val starExports = checker.getModuleExportsFollowingStars(targetFile)
                        val hasDefaultExport = checker.moduleHasDefaultExport(targetFile)
                        // (CHK.82)(2) A cross-file `declare module "<spec>"` AUGMENTATION
                        // adds its own top-level declarations to the module's exports, and
                        // [getModuleExportsFollowingStars] is AST-derived from the TARGET
                        // file alone — so `import { Brand } from "./types.js"` beside
                        // `declare module "./types.js" { export interface Brand { … } }`
                        // read a false TS2305, while the TYPE resolved correctly, which is
                        // what made it a pure absence-check defect. Additive: the extra
                        // names can only enlarge the known set, i.e. only SUPPRESS.
                        val augNames = checker.augmentationDeclaredExportNames(resolvedFile) +
                            checker.jsDocTypedefExportNames(targetFile)
                        val allExports = when {
                            starExports == null -> null
                            hasDefaultExport -> starExports + "default" + augNames
                            else -> starExports + augNames
                        }

                        for (specEl in namedBindings.elements) {
                            if (specEl.isTypeOnly) continue
                            val importedName = (specEl.propertyName ?: specEl.name).text
                            // Skip parse-error artifacts (e.g. `*`, `from` as import names)
                            if (!importedName[0].isLetter() && importedName[0] != '_' && importedName[0] != '$') continue
                            // 'default' as name: checked by checkDefaultImports (TS1192) or TS2305
                            // Skip 'default' here only if checked already via hasDefaultExport
                            if (importedName == "default") {
                                // 'import { default as X }' — check if module has default
                                // This is not covered by checkDefaultImports (which only checks importClause.name)
                                // TS2305 fires if no default export and allowSyntheticDefaultImports is false
                                if (!hasDefaultExport && !hasExportEquals) {
                                    val nameNode = specEl.propertyName ?: specEl.name
                                    emitTs2305(source, fileName, moduleName, importedName, nameNode)
                                }
                                continue
                            }
                            // For non-default names: check against module exports
                            // Skip if we already emit TS2614 for this case (module has default export)
                            // TS2614 is emitted in checkDefaultImports for this pattern
                            if (hasDefaultExport) continue  // TS2614 handled in checkDefaultImports
                            // Skip TS2305 for export= modules — we'd need to check namespace members
                            // and type members of the exported value, which requires full type resolution
                            if (hasExportEquals) continue
                            // Unknowable export set (unresolvable `export *` target) — absence
                            // of a non-default name can't be proven (M1.1 FN-safe).
                            val knownExports = allExports ?: continue
                            if (importedName !in knownExports) {
                                emitAbsentNamedMember(
                                    source = source, fileName = fileName, moduleName = moduleName,
                                    importedName = importedName, nameNode = specEl.propertyName ?: specEl.name,
                                    targetResult = targetResult, resolvedFile = resolvedFile,
                                    knownExports = knownExports, hasDefaultExport = false,
                                )
                            }
                        }
                    }
                    is ExportDeclaration -> {
                        // export { X } from "./module" — check X against module exports
                        val moduleName = (stmt.moduleSpecifier as? StringLiteralNode)?.text ?: continue
                        // Only check relative imports — non-relative imports might resolve incorrectly
                        if (!moduleName.startsWith("./") && !moduleName.startsWith("../")) continue
                        val clause = stmt.exportClause as? NamedExports ?: continue

                        // Same `.js`/`.jsx`→`.ts` fallback as the import branch: nodenext
                        // sources (tsc's own) write re-exports with the emitted extension.
                        val resolvedFile = checker.resolveModuleSpecifierRelative(moduleName, fileName)
                            ?: ((if (moduleName.endsWith(".js")) checker.resolveModuleSpecifierRelative(moduleName.removeSuffix(".js"), fileName) else null)
                                ?: (if (moduleName.endsWith(".jsx")) checker.resolveModuleSpecifierRelative(moduleName.removeSuffix(".jsx"), fileName) else null))
                            ?: continue
                        val targetResult = fileResults[resolvedFile] ?: continue
                        val targetFile = targetResult.sourceFile
                        // (CHK.198) a re-export of an `export =` module follows the import's
                        // per-specifier rule, type-only specifiers and clauses included.
                        if (targetFile.statements.any { it is ExportAssignment && it.isExportEquals }) {
                            val target = exportEqualsTarget(targetResult, resolvedFile)
                            for (specEl in clause.elements) {
                                reportExportEqualsSpecifier(source, fileName, moduleName, specEl.propertyName ?: specEl.name, target)
                            }
                            continue
                        }
                        if (stmt.isTypeOnly) continue

                        // Same star-following as the import branch (M1.1): the `default`
                        // re-export stays decidable from the DIRECT file; non-default specs
                        // are skipped when the star set is unknowable.
                        val starExports = checker.getModuleExportsFollowingStars(targetFile)
                        val hasDefaultExport = checker.moduleHasDefaultExport(targetFile)
                        // (CHK.82)(2) A cross-file `declare module "<spec>"` AUGMENTATION
                        // adds its own top-level declarations to the module's exports, and
                        // [getModuleExportsFollowingStars] is AST-derived from the TARGET
                        // file alone — so `import { Brand } from "./types.js"` beside
                        // `declare module "./types.js" { export interface Brand { … } }`
                        // read a false TS2305, while the TYPE resolved correctly, which is
                        // what made it a pure absence-check defect. Additive: the extra
                        // names can only enlarge the known set, i.e. only SUPPRESS.
                        val augNames = checker.augmentationDeclaredExportNames(resolvedFile) +
                            checker.jsDocTypedefExportNames(targetFile)
                        val allExports = when {
                            starExports == null -> null
                            hasDefaultExport -> starExports + "default" + augNames
                            else -> starExports + augNames
                        }
                        for (specEl in clause.elements) {
                            if (specEl.isTypeOnly) continue
                            // For re-exports: the "source name" in the source module
                            // is either propertyName (for `export { X as Y } from ...`) or name
                            val sourceName = (specEl.propertyName ?: specEl.name).text
                            if (sourceName == "default") {
                                if (!hasDefaultExport) {
                                    val nameNode = specEl.propertyName ?: specEl.name
                                    emitTs2305(source, fileName, moduleName, "default", nameNode)
                                }
                                continue
                            }
                            val knownExports = allExports ?: continue
                            if (sourceName !in knownExports) {
                                // (CHK.190) the same order as an import (TS2724 / TS2614 /
                                // TS2460 / TS2459 / TS2305) — tsgo resolves both through
                                // `errorNoModuleMemberSymbol`; a re-export has no separate
                                // TS2614 owner, so it is decided here.
                                emitAbsentNamedMember(
                                    source = source, fileName = fileName, moduleName = moduleName,
                                    importedName = sourceName, nameNode = specEl.propertyName ?: specEl.name,
                                    targetResult = targetResult, resolvedFile = resolvedFile,
                                    knownExports = knownExports, hasDefaultExport = hasDefaultExport,
                                )
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * The row for a named import / `export { … } from` specifier naming [importedName],
     * which [knownExports] (the target's export set, stars followed) does not hold —
     * tsgo's `errorNoModuleMemberSymbol` order (checker.go:14810): a spelling suggestion
     * among the exports -> TS2724; else, when the target has a default export
     * ([hasDefaultExport]), TS2614 "Did you mean to use 'import x from …'"; else
     * `reportNonExportedMember` — declared locally and exported under another name ->
     * TS2460, declared locally and not exported -> TS2459, otherwise TS2305. The import
     * branch passes `hasDefaultExport = false`: its TS2614 is [checkDefaultImports]'s.
     */
    private fun emitAbsentNamedMember(
        source: String, fileName: String, moduleName: String, importedName: String, nameNode: Identifier,
        targetResult: BinderResult, resolvedFile: String, knownExports: Set<String>, hasDefaultExport: Boolean,
    ) {
        val targetFile = targetResult.sourceFile
        val suggestion = checker.getSpellingSuggestionFromNames(importedName, knownExports)
        if (suggestion != null) {
            emitMissingMemberSuggestion(source, fileName, moduleName, importedName, nameNode, suggestion, targetFile, resolvedFile)
            return
        }
        if (hasDefaultExport) {
            val (line, character) = checker.getLineAndCharacterOfPosition(source, nameNode.pos)
            checker.diagnostics.add(Diagnostic(
                message = "Module '\"$moduleName\"' has no exported member '$importedName'. Did you mean to use 'import $importedName from \"$moduleName\"' instead?",
                category = DiagnosticCategory.Error,
                code = 2614,
                fileName = fileName,
                line = line,
                character = character,
                start = nameNode.pos,
                length = nameNode.text.length,
            ))
            return
        }
        // Check if name is declared locally but not exported → TS2459
        // or declared locally but exported under a different name → TS2460
        val localNames = getModuleLocalNames(targetFile)
        val exportAlias = getModuleExportAlias(targetFile, importedName)
        when {
            exportAlias != null -> {
                // TS2460: name declared locally, exported as 'exportAlias'
                val (line, character) = checker.getLineAndCharacterOfPosition(source, nameNode.pos)
                // Add TS2728 related info pointing to the declaration in the target file
                val declPos2460 = getLocalDeclarationPos(targetFile, importedName)
                val targetSource2460 = targetResult.sourceFile.text
                val relatedInfo2460 = if (declPos2460 != null) {
                    val (declLine, declChar) = checker.getLineAndCharacterOfPosition(targetSource2460, declPos2460.first)
                    listOf(Diagnostic(
                        message = "'$importedName' is declared here.",
                        category = DiagnosticCategory.Message,
                        code = 2728,
                        fileName = resolvedFile,
                        line = declLine,
                        character = declChar,
                        start = declPos2460.first,
                        length = declPos2460.second,
                    ))
                } else emptyList()
                checker.diagnostics.add(Diagnostic(
                    message = "Module '\"$moduleName\"' declares '$importedName' locally, but it is exported as '$exportAlias'.",
                    category = DiagnosticCategory.Error,
                    code = 2460,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = nameNode.pos,
                    length = nameNode.text.length,
                    relatedInformation = relatedInfo2460,
                ))
            }
            importedName in localNames -> {
                // TS2459: name declared locally but not exported
                val (line, character) = checker.getLineAndCharacterOfPosition(source, nameNode.pos)
                // Add TS2728 related info for first declaration + TS6204 "and here" for additional
                val allDeclPositions = getAllLocalDeclarationPositions(targetFile, importedName)
                val targetSource = targetResult.sourceFile.text
                val relatedInfo = buildList {
                    allDeclPositions.forEachIndexed { idx, declPos ->
                        val (declLine, declChar) = checker.getLineAndCharacterOfPosition(targetSource, declPos.first)
                        if (idx == 0) {
                            add(Diagnostic(
                                message = "'$importedName' is declared here.",
                                category = DiagnosticCategory.Message,
                                code = 2728,
                                fileName = resolvedFile,
                                line = declLine,
                                character = declChar,
                                start = declPos.first,
                                length = declPos.second,
                            ))
                        } else {
                            add(Diagnostic(
                                message = "and here.",
                                category = DiagnosticCategory.Message,
                                code = 6204,
                                fileName = resolvedFile,
                                line = declLine,
                                character = declChar,
                                start = declPos.first,
                                length = declPos.second,
                            ))
                        }
                    }
                }
                checker.diagnostics.add(Diagnostic(
                    message = "Module '\"$moduleName\"' declares '$importedName' locally, but it is not exported.",
                    category = DiagnosticCategory.Error,
                    code = 2459,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = nameNode.pos,
                    length = nameNode.text.length,
                    relatedInformation = relatedInfo,
                ))
            }
            else -> emitTs2305(source, fileName, moduleName, importedName, nameNode)
        }
    }

    /** TS2724 `'"m"' has no exported member named 'x'. Did you mean 'y'?` with the related
     *  TS2728 at the suggested member's declaration in [targetFile]. */
    private fun emitMissingMemberSuggestion(
        source: String, fileName: String, moduleName: String, importedName: String, nameNode: Identifier,
        suggestion: String, targetFile: SourceFile, resolvedFile: String,
        suggestionDecl: Pair<Int, Int>? = getLocalDeclarationPos(targetFile, suggestion),
    ) {
        val (line, character) = checker.getLineAndCharacterOfPosition(source, nameNode.pos)
        val declPos = suggestionDecl
        val relatedInfo = if (declPos != null) {
            val (dLine, dChar) = checker.getLineAndCharacterOfPosition(targetFile.text, declPos.first)
            listOf(Diagnostic(
                message = "'$suggestion' is declared here.",
                category = DiagnosticCategory.Message,
                code = 2728,
                fileName = resolvedFile,
                line = dLine,
                character = dChar,
                start = declPos.first,
                length = declPos.second,
            ))
        } else emptyList()
        checker.diagnostics.add(Diagnostic(
            message = "'\"$moduleName\"' has no exported member named '$importedName'. Did you mean '$suggestion'?",
            category = DiagnosticCategory.Error,
            code = 2724,
            fileName = fileName,
            line = line,
            character = character,
            start = nameNode.pos,
            length = nameNode.text.length,
            relatedInformation = relatedInfo,
        ))
    }

    fun emitTs2305(
        source: String, fileName: String, moduleName: String,
        importedName: String, nameNode: Identifier
    ) {
        val displayName = when {
            moduleName.startsWith("./") || moduleName.startsWith("../") -> moduleName
            else -> {
                val resolved = checker.resolveModuleSpecifier(moduleName)
                    ?.removeSuffix(".d.ts")?.removeSuffix(".ts")?.removeSuffix(".tsx")
                    ?: moduleName
                resolved
            }
        }
        val nameStart = nameNode.pos
        val nameLength = nameNode.text.length
        val (line, character) = checker.getLineAndCharacterOfPosition(source, nameStart)
        checker.diagnostics.add(Diagnostic(
            message = "Module '\"$displayName\"' has no exported member '$importedName'.",
            category = DiagnosticCategory.Error,
            code = 2305,
            fileName = fileName,
            line = line,
            character = character,
            start = nameStart,
            length = nameLength,
        ))
    }

    /**
     * Returns all locally-declared names in the source file (exported or not).
     * Used to distinguish TS2459 (declared but not exported) from TS2305 (not declared).
     */
    private fun getModuleLocalNames(file: SourceFile): Set<String> = getModuleLocalNames(file.statements)

    private fun getModuleLocalNames(statements: List<Statement>): Set<String> {
        val names = mutableSetOf<String>()
        for (stmt in statements) {
            when (stmt) {
                is VariableStatement -> {
                    for (decl in stmt.declarationList.declarations) {
                        val name = decl.name
                        if (name is Identifier) names.add(name.text)
                    }
                }
                is FunctionDeclaration -> stmt.name?.let { names.add(it.text) }
                is ClassDeclaration -> stmt.name?.let { names.add(it.text) }
                is InterfaceDeclaration -> names.add(stmt.name.text)
                is TypeAliasDeclaration -> names.add(stmt.name.text)
                is EnumDeclaration -> names.add(stmt.name.text)
                is ModuleDeclaration -> {
                    val name = stmt.name
                    if (name is Identifier) names.add(name.text)
                }
                else -> {}
            }
        }
        return names
    }

    /**
     * Returns the exported name for a local name that is exported under a different name.
     * E.g. for `export { bar as baz }`, getModuleExportAlias(file, "bar") returns "baz".
     * Returns null if bar is not locally declared and re-exported under a different alias.
     */
    private fun getModuleExportAlias(file: SourceFile, localName: String): String? {
        for (stmt in file.statements) {
            if (stmt !is ExportDeclaration) continue
            if (stmt.moduleSpecifier != null) continue  // skip re-exports from other modules
            val clause = stmt.exportClause as? NamedExports ?: continue
            for (specifier in clause.elements) {
                val propName = specifier.propertyName?.text
                val exportedName = specifier.name.text
                // `export { localName as exportedName }` — propName = localName, name = exportedName
                if (propName == localName && exportedName != localName) {
                    return exportedName
                }
            }
        }
        return null
    }

    /**
     * Returns the position (start, length) of the declaration of [name] in [file],
     * for use as TS2728 related info in TS2459 diagnostics.
     */
    private fun getLocalDeclarationPos(file: SourceFile, name: String): Pair<Int, Int>? =
        getAllLocalDeclarationPositions(file, name).firstOrNull()

    /**
     * Returns ALL positions where [name] is declared in [file], for use as TS2728/TS6204 related info.
     */
    private fun getAllLocalDeclarationPositions(file: SourceFile, name: String): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        for (stmt in file.statements) {
            when (stmt) {
                is VariableStatement -> {
                    for (decl in stmt.declarationList.declarations) {
                        val ident = decl.name as? Identifier ?: continue
                        if (ident.text == name) result.add(Pair(ident.pos, ident.text.length))
                    }
                }
                is FunctionDeclaration -> {
                    val ident = stmt.name ?: continue
                    if (ident.text == name) result.add(Pair(ident.pos, ident.text.length))
                }
                is ClassDeclaration -> {
                    val ident = stmt.name ?: continue
                    if (ident.text == name) result.add(Pair(ident.pos, ident.text.length))
                }
                is InterfaceDeclaration -> {
                    if (stmt.name.text == name) result.add(Pair(stmt.name.pos, stmt.name.text.length))
                }
                is TypeAliasDeclaration -> {
                    if (stmt.name.text == name) result.add(Pair(stmt.name.pos, stmt.name.text.length))
                }
                is EnumDeclaration -> {
                    if (stmt.name.text == name) result.add(Pair(stmt.name.pos, stmt.name.text.length))
                }
                is ModuleDeclaration -> {
                    val ident = stmt.name as? Identifier ?: continue
                    if (ident.text == name) result.add(Pair(ident.pos, ident.text.length))
                }
                else -> {}
            }
        }
        return result
    }

    private var ambientBlocks: Map<String, ModuleBlock?>? = null

    /** (CHK.198)(d) The ambient `declare module "<name>"` bodies of the program's script
     *  files, by name; null for a name declared more than once or without a body (not
     *  judged). Built on first ask. */
    private fun ambientModuleBlocks(): Map<String, ModuleBlock?> = ambientBlocks ?: HashMap<String, ModuleBlock?>().also { map ->
        for (r in binderResults) {
            val stmts = r.sourceFile.statements
            if (stmts.none { it is ModuleDeclaration } || checker.isModuleFile(stmts)) continue
            for (st in stmts) {
                val md = st as? ModuleDeclaration ?: continue
                val name = (md.name as? StringLiteralNode)?.text ?: continue
                map[name] = if (name in map) null else md.body as? ModuleBlock
            }
        }
        ambientBlocks = map
    }

    /**
     * (CHK.198)(d) TS1192 / TS2613 for a default import of an ambient module that declares
     * the `__esModule` marker and has no default export. A body with an explicit export
     * declaration exports only what it marks; otherwise every declaration is exported.
     */
    private fun checkAmbientDefaultImport(
        source: String, fileName: String, moduleName: String, binding: Identifier, body: ModuleBlock,
    ) {
        val stmts = body.statements
        if (stmts.any { it is ExportAssignment }) return
        val explicit = stmts.any { it is ExportDeclaration }
        val exported = HashSet<String>()
        var hasDefault = false
        for (st in stmts) {
            val modifiers: Set<ModifierFlag> = when (st) {
                is VariableStatement -> st.modifiers
                is FunctionDeclaration -> st.modifiers
                is ClassDeclaration -> st.modifiers
                is EnumDeclaration -> st.modifiers
                is InterfaceDeclaration -> st.modifiers
                is TypeAliasDeclaration -> st.modifiers
                is ModuleDeclaration -> st.modifiers
                is ExportDeclaration -> {
                    if (st.moduleSpecifier != null) return
                    (st.exportClause as? NamedExports)?.elements?.forEach {
                        if (it.name.text == "default") hasDefault = true else exported += it.name.text
                    }
                    continue
                }
                else -> continue
            }
            if (ModifierFlag.Default in modifiers) hasDefault = true
            if (explicit && ModifierFlag.Export !in modifiers) continue
            when (st) {
                is VariableStatement -> st.declarationList.declarations.forEach { d -> (d.name as? Identifier)?.let { exported += it.text } }
                else -> getModuleLocalNames(listOf(st)).let { exported += it }
            }
        }
        if (hasDefault || "__esModule" !in exported) return
        val importName = binding.text
        val (line, character) = checker.getLineAndCharacterOfPosition(source, binding.pos)
        val (code, message) = if (importName in exported) 2613 to
            "Module '\"$moduleName\"' has no default export. Did you mean to use 'import { $importName } from \"$moduleName\"' instead?"
        else 1192 to "Module '\"$moduleName\"' has no default export."
        checker.diagnostics.add(Diagnostic(
            message = message,
            category = DiagnosticCategory.Error,
            code = code,
            fileName = fileName,
            line = line,
            character = character,
            start = binding.pos,
            length = importName.length,
        ))
    }

    /**
     * tsgo's `resolveExportByName(moduleSymbol, "__esModule")` half of `canHaveSyntheticDefault`:
     * a declaration file that itself declares an exported `__esModule` member is a compiled
     * ES module and has no synthetic default (`export declare const __esModule: true` or
     * `export { __esModule }`).
     */
    private fun declaresEsModuleMarker(file: SourceFile): Boolean = file.statements.any { stmt ->
        (stmt is VariableStatement && ModifierFlag.Export in stmt.modifiers &&
            stmt.declarationList.declarations.any { (it.name as? Identifier)?.text == "__esModule" }) ||
            (stmt is ExportDeclaration &&
                (stmt.exportClause as? NamedExports)?.elements?.any { it.name.text == "__esModule" } == true)
    }
}
