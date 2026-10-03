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
 * (INV.0) (P18.279) — the MODULE-SYNTAX family: the ten `initCheckPasses6` passes that judge
 * import / export declarations against `isolatedModules`, `verbatimModuleSyntax` and the
 * locality rules — `checkExportSpecifierLocality` (TS2661), `checkImportConflictsWithLocal`
 * (TS2440, with the barrel type-only firewall and its memo `barrelTypeOnlyMemo`),
 * `checkNamespaceImportVarConflict` (TS2440 across merged namespace blocks),
 * `checkImportNotAtTopLevel` (TS1473), the four `checkIsolatedModules*` passes
 * (TS1292 / TS1269 / TS1205 / TS1448 / TS1280) and `checkIsolatedModulesGlobalValueShadow`
 * (TS2866), and `checkVerbatimModuleSyntax` (TS1287 / TS1295 / TS1484 / TS2865), with their helpers.
 * Extracted VERBATIM from `Checker.kt` (three spans: 7534-7537, the memo field; 195115-195377;
 * 195394-196621 — `resolveBarrelStarTarget` (195379-195392, three outside callers) STAYED);
 * every Checker member it reads is reached through [checker]. Ambient reads:
 * `docs/inversion-ambient-ledger.md` row 21.
 */
internal class ModuleSyntaxChecks(
    private val checker: Checker,
    private val options: CompilerOptions,
) {

    /** TS2440 barrel firewall memo (keyed "<barrelTargetFile> <name>" → is-type-only).
     *  Declared before `init` for the same reason as [moduleStarExportsCache] —
     *  `checkImportConflictsWithLocal` runs during init and would otherwise see it null. */
    private val barrelTypeOnlyMemo = HashMap<String, Boolean>()

    /** B287 TS2866: under isolatedModules, a NON-type-only named import whose target
     *  export is type-only, where the importing file USES a same-named GLOBAL VALUE
     *  (`import { Date } from './types'` + `new Date(...)`) — a single-file transpiler
     *  cannot tell whether to elide the import. Value-use detection is conservative:
     *  `new Name(...)` / `Name(...)` callee positions in the file's raw statements. */
    fun checkIsolatedModulesGlobalValueShadow() {
        if (!options.isolatedModules) return
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            var globalValueUses: Set<String>? = null
            for (stmt in result.sourceFile.statements) {
                if (stmt !is ImportDeclaration) continue
                val clause = stmt.importClause ?: continue
                if (clause.isTypeOnly) continue
                val spec = (stmt.moduleSpecifier as? StringLiteralNode)?.text ?: continue
                val nb = clause.namedBindings as? NamedImports ?: continue
                for (element in nb.elements) {
                    if (element.isTypeOnly) continue
                    val localName = element.name.text
                    val sourceName = (element.propertyName ?: element.name).text
                    if (localName !in Checker.KNOWN_GLOBALS) continue
                    if (!isExportedNameTypeOnly(sourceName, spec)) continue
                    if (globalValueUses == null) globalValueUses = collectNewCallCalleeNames(result.sourceFile.statements)
                    if (localName !in globalValueUses) continue
                    val nameNode = element.propertyName ?: element.name
                    val (line, character) = checker.getLineAndCharacterOfPosition(source, nameNode.pos)
                    checker.diagnostics.add(Diagnostic(
                        message = "Import '$localName' conflicts with global value used in this file, so must be declared with a type-only import when 'isolatedModules' is enabled.",
                        category = DiagnosticCategory.Error, code = 2866,
                        fileName = fileName, line = line, character = character,
                        start = nameNode.pos, length = nameNode.text.length,
                    ))
                }
            }
        }
    }

    /** Bare-identifier callee names of `new X(...)` / `X(...)` anywhere in [stmts]. */
    private fun collectNewCallCalleeNames(stmts: List<Statement>): Set<String> {
        val out = mutableSetOf<String>()
        // local funs can't forward-reference — route the expr→stmt recursion through a ref
        var walkStmts: (List<Statement>) -> Unit = { }
        fun walkExpr(e: Expression?) {
            when (e) {
                null -> {}
                is NewExpression -> {
                    (e.expression as? Identifier)?.let { out.add(it.text) }
                    walkExpr(e.expression); e.arguments?.forEach { walkExpr(it) }
                }
                is CallExpression -> {
                    (e.expression as? Identifier)?.let { out.add(it.text) }
                    walkExpr(e.expression); e.arguments.forEach { walkExpr(it) }
                }
                is BinaryExpression -> {
                    // iterative right-spine for deep chains
                    var cur: Expression = e
                    while (cur is BinaryExpression) { walkExpr(cur.left); cur = cur.right }
                    walkExpr(cur)
                }
                is ParenthesizedExpression -> walkExpr(e.expression)
                is PropertyAccessExpression -> walkExpr(e.expression)
                is ElementAccessExpression -> { walkExpr(e.expression); walkExpr(e.argumentExpression) }
                is AsExpression -> walkExpr(e.expression)
                is SatisfiesExpression -> walkExpr(e.expression)
                is NonNullExpression -> walkExpr(e.expression)
                is TypeAssertionExpression -> walkExpr(e.expression)
                is PrefixUnaryExpression -> walkExpr(e.operand)
                is PostfixUnaryExpression -> walkExpr(e.operand)
                is ConditionalExpression -> { walkExpr(e.condition); walkExpr(e.whenTrue); walkExpr(e.whenFalse) }
                is ArrayLiteralExpression -> e.elements.forEach { walkExpr(it) }
                is ObjectLiteralExpression -> e.properties.forEach { p ->
                    when (p) {
                        is PropertyAssignment -> walkExpr(p.initializer)
                        is SpreadAssignment -> walkExpr(p.expression)
                        else -> {}
                    }
                }
                is SpreadElement -> walkExpr(e.expression)
                is AwaitExpression -> walkExpr(e.expression)
                is ArrowFunction -> when (val b = e.body) {
                    is Block -> walkStmts(b.statements)
                    is Expression -> walkExpr(b)
                    else -> {}
                }
                is FunctionExpression -> walkStmts(e.body.statements)
                else -> {}
            }
        }
        fun walkStmt(s: Statement) {
            when (s) {
                is ExpressionStatement -> walkExpr(s.expression)
                is VariableStatement -> s.declarationList.declarations.forEach { walkExpr(it.initializer) }
                is ReturnStatement -> walkExpr(s.expression)
                is IfStatement -> { walkExpr(s.expression); walkStmt(s.thenStatement); s.elseStatement?.let { walkStmt(it) } }
                is Block -> walkStmts(s.statements)
                is FunctionDeclaration -> s.body?.let { walkStmts(it.statements) }
                is ClassDeclaration -> s.members.forEach { m ->
                    when (m) {
                        is MethodDeclaration -> m.body?.let { walkStmts(it.statements) }
                        is Constructor -> m.body?.let { walkStmts(it.statements) }
                        is PropertyDeclaration -> walkExpr(m.initializer)
                        is GetAccessor -> m.body?.let { walkStmts(it.statements) }
                        is SetAccessor -> m.body?.let { walkStmts(it.statements) }
                        else -> {}
                    }
                }
                is ForStatement -> { (s.initializer as? Expression)?.let { walkExpr(it) }; s.condition?.let { walkExpr(it) }; s.incrementor?.let { walkExpr(it) }; walkStmt(s.statement) }
                is ForOfStatement -> { walkExpr(s.expression); walkStmt(s.statement) }
                is ForInStatement -> { walkExpr(s.expression); walkStmt(s.statement) }
                is WhileStatement -> { walkExpr(s.expression); walkStmt(s.statement) }
                is DoStatement -> { walkStmt(s.statement); walkExpr(s.expression) }
                is ThrowStatement -> walkExpr(s.expression)
                is TryStatement -> { walkStmts(s.tryBlock.statements); s.catchClause?.block?.let { walkStmts(it.statements) }; s.finallyBlock?.let { walkStmts(it.statements) } }
                is SwitchStatement -> { walkExpr(s.expression); s.caseBlock.forEach { c ->
                    when (c) {
                        is CaseClause -> { walkExpr(c.expression); walkStmts(c.statements) }
                        is DefaultClause -> walkStmts(c.statements)
                        else -> {}
                    }
                } }
                is LabeledStatement -> walkStmt(s.statement)
                is ModuleDeclaration -> (s.body as? ModuleBlock)?.let { walkStmts(it.statements) }
                else -> {}
            }
        }
        walkStmts = { list -> list.forEach { walkStmt(it) } }
        stmts.forEach { walkStmt(it) }
        return out
    }

    private fun isExportedNameTypeOnly(name: String, moduleSpecifier: String): Boolean {
        val targetFile = checker.resolveModuleSpecifier(moduleSpecifier, null) ?: return false
        val targetResult = checker.fileResults[targetFile] ?: return false
        var hasTypeOnly = false
        var hasValue = false
        for (stmt in targetResult.sourceFile.statements) {
            when (stmt) {
                is TypeAliasDeclaration ->
                    if (stmt.name.text == name && ModifierFlag.Export in stmt.modifiers) hasTypeOnly = true
                is InterfaceDeclaration ->
                    if (stmt.name.text == name && ModifierFlag.Export in stmt.modifiers) hasTypeOnly = true
                is ClassDeclaration ->
                    if (stmt.name?.text == name && ModifierFlag.Export in stmt.modifiers) hasValue = true
                is FunctionDeclaration ->
                    if (stmt.name?.text == name && ModifierFlag.Export in stmt.modifiers) hasValue = true
                is EnumDeclaration ->
                    if (stmt.name.text == name && ModifierFlag.Export in stmt.modifiers) hasValue = true
                is VariableStatement ->
                    if (ModifierFlag.Export in stmt.modifiers &&
                        stmt.declarationList.declarations.any { (it.name as? Identifier)?.text == name }) hasValue = true
                // B287: an exported VALUE-FREE namespace (only types/interfaces inside)
                // is a type-only export — `export namespace Event { export type T }`
                // erases entirely, so importing it without `import type` is TS1484.
                is ModuleDeclaration ->
                    if ((stmt.name as? Identifier)?.text == name && ModifierFlag.Export in stmt.modifiers) {
                        if (checker.isNamespaceInstantiated(stmt)) hasValue = true else hasTypeOnly = true
                    }
                is ExportDeclaration -> {
                    val clause = stmt.exportClause as? NamedExports ?: continue
                    for (spec in clause.elements) {
                        if (spec.name.text != name) continue
                        if (stmt.isTypeOnly || spec.isTypeOnly) hasTypeOnly = true else hasValue = true
                    }
                }
                else -> {}
            }
        }
        return hasTypeOnly && !hasValue
    }

    /**
     * TS2440 barrel firewall: is [name] exported from [moduleSpecifier] ONLY as a TYPE
     * (interface / type-alias / uninstantiated namespace / type-only re-export), following
     * `export *` barrels — unlike [isExportedNameTypeOnly], which inspects only the DIRECT
     * exports of the resolved file? A type-only import declaration-merges with a local
     * VALUE-only declaration (function / value const) of the same name (the import provides
     * the type, the local provides the value), so no TS2440 — tsc's own `src/compiler` does
     * exactly this (`import { Node } from "./_namespaces/ts.js"` where the barrel re-exports
     * `export interface Node` from types.ts, plus a local `function Node`).
     *
     * FN-safe (conservative): returns true ONLY when the closure resolves fully and the name
     * is found solely as a type; ANY uncertainty (unresolvable star target, `export =`, a
     * local `export { X }` re-export whose kind we can't resolve, depth blow-out, or the name
     * having a value meaning anywhere) yields false → the caller keeps its existing behavior.
     */
    private fun importedNameIsTypeOnlyThroughBarrel(name: String, moduleSpecifier: String, fromFile: String): Boolean {
        // The import specifier is typically `./_namespaces/ts.js` — resolveModuleSpecifier does
        // NOT strip `.js` (documented), so use the barrel-star resolver's `.js`/`.jsx` fallback.
        val target = if (moduleSpecifier.startsWith("./") || moduleSpecifier.startsWith("../")) {
            checker.resolveBarrelStarTarget(moduleSpecifier, fromFile)
        } else {
            checker.resolveModuleSpecifier(moduleSpecifier, null)?.let { checker.fileResults[it]?.sourceFile }
        } ?: return false
        // Memoize per (barrel target, name): the export-kind closure of a barrel is huge
        // (`export * from` the whole compiler), so an un-memoized per-specifier walk times out.
        val key = "${target.fileName} $name"
        barrelTypeOnlyMemo[key]?.let { return it }
        val acc = BarrelExportKind()
        accumulateBarrelExportKind(name, target, acc, mutableSetOf(), 0, forceType = false)
        val result = acc.resolvable && acc.hasType && !acc.hasValue
        barrelTypeOnlyMemo[key] = result
        return result
    }

    private class BarrelExportKind {
        var hasValue = false
        var hasType = false
        var resolvable = true
    }

    private fun accumulateBarrelExportKind(
        name: String, file: SourceFile, acc: BarrelExportKind,
        visited: MutableSet<String>, depth: Int, forceType: Boolean,
    ) {
        if (depth > 64) { acc.resolvable = false; return }
        if (!visited.add(file.fileName)) return // cycle back-edge: its exports were already counted
        for (stmt in file.statements) {
            when (stmt) {
                is InterfaceDeclaration ->
                    if (stmt.name.text == name && ModifierFlag.Export in stmt.modifiers) acc.hasType = true
                is TypeAliasDeclaration ->
                    if (stmt.name.text == name && ModifierFlag.Export in stmt.modifiers) acc.hasType = true
                is ClassDeclaration ->
                    if (stmt.name?.text == name && ModifierFlag.Export in stmt.modifiers) { if (forceType) acc.hasType = true else acc.hasValue = true }
                is FunctionDeclaration ->
                    if (stmt.name?.text == name && ModifierFlag.Export in stmt.modifiers) { if (forceType) acc.hasType = true else acc.hasValue = true }
                is EnumDeclaration ->
                    if (stmt.name.text == name && ModifierFlag.Export in stmt.modifiers) { if (forceType) acc.hasType = true else acc.hasValue = true }
                is VariableStatement ->
                    if (ModifierFlag.Export in stmt.modifiers &&
                        stmt.declarationList.declarations.any { (it.name as? Identifier)?.text == name }) { if (forceType) acc.hasType = true else acc.hasValue = true }
                is ModuleDeclaration ->
                    if ((stmt.name as? Identifier)?.text == name && ModifierFlag.Export in stmt.modifiers) {
                        if (checker.isNamespaceInstantiated(stmt) && !forceType) acc.hasValue = true else acc.hasType = true
                    }
                is ExportDeclaration -> {
                    val clause = stmt.exportClause
                    val fromSpec = (stmt.moduleSpecifier as? StringLiteralNode)?.text
                    if (clause == null) {
                        // `export * from "sub"` (or `export type * from "sub"`) — recurse with the
                        // SHARED visited set (a re-visited file's exports were already counted) so
                        // the closure is walked once, not re-walked per re-export path.
                        if (fromSpec == null) { acc.resolvable = false; continue }
                        val sub = checker.resolveBarrelStarTarget(fromSpec, file.fileName)
                        if (sub == null) { acc.resolvable = false; continue }
                        accumulateBarrelExportKind(name, sub, acc, visited, depth + 1, forceType || stmt.isTypeOnly)
                    } else if (clause is NamedExports) {
                        for (spec in clause.elements) {
                            if (spec.name.text != name) continue
                            // A type-only named re-export contributes only a type; any other
                            // named re-export (`export { X } from sub` / local `export { X }`)
                            // has an uncertain source kind → be conservative (don't suppress).
                            if (forceType || stmt.isTypeOnly || spec.isTypeOnly) acc.hasType = true
                            else acc.resolvable = false
                        }
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Is the module referenced by `require("specifier")` type-only — i.e. its `export = X`
     * target `X` is declared in the module ONLY as a type (alias/interface) and not as a value?
     * Used by the isolatedModules TS1269 check for the `export import T = require(...)` form.
     */
    private fun isRequireModuleTypeOnly(specifier: String): Boolean {
        val targetFile = checker.resolveModuleSpecifier(specifier, null) ?: return false
        val targetResult = checker.fileResults[targetFile] ?: return false
        val exportAssign = targetResult.sourceFile.statements.firstNotNullOfOrNull {
            if (it is ExportAssignment && it.isExportEquals) it else null
        } ?: return false
        val target = exportAssign.expression as? Identifier ?: return false
        var hasType = false
        var hasValue = false
        for (s in targetResult.sourceFile.statements) {
            when (s) {
                is TypeAliasDeclaration -> if (s.name.text == target.text) hasType = true
                is InterfaceDeclaration -> if (s.name.text == target.text) hasType = true
                is ClassDeclaration -> if (s.name?.text == target.text) hasValue = true
                is FunctionDeclaration -> if (s.name?.text == target.text) hasValue = true
                is EnumDeclaration -> if (s.name.text == target.text) hasValue = true
                is VariableStatement -> if (s.declarationList.declarations.any { (it.name as? Identifier)?.text == target.text }) hasValue = true
                is ModuleDeclaration -> if ((s.name as? Identifier)?.text == target.text) hasValue = true
                else -> {}
            }
        }
        return hasType && !hasValue
    }

    /**
     * TS1205 / TS1448: under isolatedModules, a re-export of a type-only name must use
     * `export type`. `export { T } from "m"` (or local `export { T }` of a value-imported
     * type) is ambiguous to a single-file transpiler. TS1205 when the name is a DIRECT type
     * in the source; TS1448 when its type-only-ness comes from a type-only re-export
     * (`export type { X } from ...`) in another file. FP-safe: gated on the EXISTING
     * `isExportedNameTypeOnly` helper (true only for genuinely type-only exports), skips
     * `export type` / type-only specifiers / type-only imports / namespace imports / .d.ts.
     */
    /**
     * B162: TS1280 — under isolatedModules, a non-ambient INSTANTIATED namespace declared at the
     * top level of a global SCRIPT file (non-module, non-.d.ts) cannot be transpiled per-file
     * (its IIFE would need to merge with same-named namespaces in other files via a shared
     * global). Uninstantiated (type-only) and `declare` namespaces are erased, so they're fine.
     */
    fun checkIsolatedModulesScriptNamespaces() {
        if (!options.isolatedModules) return
        if (options.moduleDetection == "force") return // every file is a module
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            if (checker.isModuleFile(result.sourceFile.statements)) continue
            val source = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                if (stmt !is ModuleDeclaration) continue
                if (ModifierFlag.Declare in stmt.modifiers) continue
                val name = stmt.name as? Identifier ?: continue
                if (checker.getModuleInstanceState(stmt) != ModuleInstanceState.Instantiated) continue
                val (line, character) = checker.getLineAndCharacterOfPosition(source, name.pos)
                checker.diagnostics.add(Diagnostic(
                    message = "Namespaces are not allowed in global script files when 'isolatedModules' is enabled. If this file is not intended to be a global script, set 'moduleDetection' to 'force' or add an empty 'export {}' statement.",
                    category = DiagnosticCategory.Error, code = 1280,
                    fileName = fileName, line = line, character = character,
                    start = name.pos, length = name.text.length,
                ))
            }
        }
    }

    fun checkIsolatedModulesReExportType() {
        if (!options.isolatedModules || options.verbatimModuleSyntax) return
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            val stmts = result.sourceFile.statements
            for (stmt in stmts) {
                if (stmt !is ExportDeclaration || stmt.isTypeOnly) continue
                val clause = stmt.exportClause as? NamedExports ?: continue
                val moduleSpec = (stmt.moduleSpecifier as? StringLiteralNode)?.text
                for (spec in clause.elements) {
                    if (spec.isTypeOnly) continue
                    val srcName: String
                    val srcSpec: String
                    if (moduleSpec != null) {
                        // `export { X } from "spec"`
                        srcName = (spec.propertyName ?: spec.name).text
                        srcSpec = moduleSpec
                    } else {
                        // local `export { X (as Y) }` — X must be a NON-type-only named import
                        val imp = findNonTypeOnlyImport((spec.propertyName ?: spec.name).text, stmts) ?: continue
                        srcName = imp.first
                        srcSpec = imp.second
                    }
                    if (!isExportedNameTypeOnly(srcName, srcSpec)) continue
                    val nameNode = spec.propertyName ?: spec.name
                    // Span covers the WHOLE specifier (`T as T4`), which for a no-rename
                    // specifier collapses to just the name.
                    val spanStart = nameNode.pos
                    val spanEnd = spec.name.pos + spec.name.text.length
                    val isTs1448 = checker.isTypeOnlyExportName(srcName, srcSpec, fileName)
                    val code = if (isTs1448) 1448 else 1205
                    val msg = if (isTs1448)
                        "'${nameNode.text}' resolves to a type-only declaration and must be re-exported using a type-only re-export when 'isolatedModules' is enabled."
                    else
                        "Re-exporting a type when 'isolatedModules' is enabled requires using 'export type'."
                    // TS1448 carries a related TS1377 pointing at the type-only re-export in the source file.
                    val related: List<Diagnostic> = if (isTs1448) {
                        findTypeOnlyReExportSpecifier(srcName, srcSpec)?.let { (declFile, declPos) ->
                            val declSource = checker.fileResults[declFile]?.sourceFile?.text
                            if (declSource != null) {
                                val (rl, rc) = checker.getLineAndCharacterOfPosition(declSource, declPos)
                                listOf(Diagnostic(
                                    message = "'${nameNode.text}' was exported here.",
                                    category = DiagnosticCategory.Message, code = 1377,
                                    fileName = declFile, line = rl, character = rc,
                                    start = declPos, length = srcName.length,
                                ))
                            } else emptyList()
                        } ?: emptyList()
                    } else emptyList()
                    val (line, character) = checker.getLineAndCharacterOfPosition(source, spanStart)
                    checker.diagnostics.add(Diagnostic(
                        message = msg, category = DiagnosticCategory.Error, code = code,
                        fileName = fileName, line = line, character = character,
                        start = spanStart, length = spanEnd - spanStart,
                        relatedInformation = related,
                    ))
                }
            }
        }
    }

    /** Find the `export type { name }` specifier in the module [moduleSpec]; returns (declFile, specPos) for TS1377. */
    private fun findTypeOnlyReExportSpecifier(name: String, moduleSpec: String): Pair<String, Int>? {
        val targetFile = checker.resolveModuleSpecifier(moduleSpec, null) ?: return null
        val targetResult = checker.fileResults[targetFile] ?: return null
        for (stmt in targetResult.sourceFile.statements) {
            if (stmt !is ExportDeclaration) continue
            val clause = stmt.exportClause as? NamedExports ?: continue
            for (spec in clause.elements) {
                if (spec.name.text == name && (stmt.isTypeOnly || spec.isTypeOnly)) {
                    return targetFile to (spec.propertyName ?: spec.name).pos
                }
            }
        }
        return null
    }

    /** Find a NON-type-only named import of [localName] in [stmts]; returns (sourceName, moduleSpec) or null. */
    private fun findNonTypeOnlyImport(localName: String, stmts: List<Statement>): Pair<String, String>? {
        for (stmt in stmts) {
            if (stmt !is ImportDeclaration) continue
            val clause = stmt.importClause ?: continue
            if (clause.isTypeOnly) continue
            val spec = (stmt.moduleSpecifier as? StringLiteralNode)?.text ?: continue
            val nb = clause.namedBindings as? NamedImports ?: continue
            for (el in nb.elements) {
                if (el.name.text != localName || el.isTypeOnly) continue
                return (el.propertyName ?: el.name).text to spec
            }
        }
        return null
    }

    /**
     * B61.5f helper: returns true when the source text leading up to an
     * ImportEqualsDeclaration's pos contains a class-only modifier keyword
     * (public/private/protected/static). Used to suppress TS2440 FP when
     * TS1044 already fires for the invalid modifier.
     */
    private fun sourceHasClassOnlyModifierBeforeImportEquals(
        stmt: ImportEqualsDeclaration,
        source: String,
    ): Boolean {
        val classOnlyKws = listOf("public", "private", "protected", "static")
        // Find start of line containing stmt.pos
        var lineStart = stmt.pos
        while (lineStart > 0 && source[lineStart - 1] != '\n') lineStart--
        val prefix = source.substring(lineStart, stmt.pos)
        for (kw in classOnlyKws) {
            // Word-boundary match: must be surrounded by non-letter-digit chars
            var idx = 0
            while (true) {
                val found = prefix.indexOf(kw, idx)
                if (found < 0) break
                val before = if (found == 0) ' ' else prefix[found - 1]
                val after = if (found + kw.length >= prefix.length) ' ' else prefix[found + kw.length]
                if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
                idx = found + 1
            }
        }
        return false
    }

    /**
     * B61.5h: TS1473 "An import declaration can only be used at the top level of a module."
     * Walks each file's statements; for nested function bodies / blocks etc., emits TS1473
     * at any ImportDeclaration found. Top-level imports remain valid.
     */
    fun checkImportNotAtTopLevel() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            // B76.2: TS1473 fires only for .js/.jsx files. For .ts/.tsx,
            // checkNamespaceTopLevelOnly emits TS1232 (correct code for
            // nested-block imports) at the same positions.
            if (fileName.endsWith(".ts") || fileName.endsWith(".tsx") ||
                fileName.endsWith(".mts") || fileName.endsWith(".cts")) continue
            val source = result.sourceFile.text
            // Walk each top-level statement, recursing into non-module-level constructs.
            for (stmt in result.sourceFile.statements) {
                walkForNestedImports(stmt, source, fileName, topLevel = true)
            }
        }
    }

    private fun walkForNestedImports(stmt: Statement, source: String, fileName: String, topLevel: Boolean) {
        if (!topLevel && stmt is ImportDeclaration) {
            val start = stmt.pos
            val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
            checker.diagnostics.add(Diagnostic(
                message = "An import declaration can only be used at the top level of a module.",
                category = DiagnosticCategory.Error,
                code = 1473,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = 6, // "import"
            ))
        }
        when (stmt) {
            is FunctionDeclaration -> stmt.body?.statements?.forEach {
                walkForNestedImports(it, source, fileName, topLevel = false)
            }
            is ClassDeclaration -> {
                for (m in stmt.members) {
                    when (m) {
                        is MethodDeclaration -> m.body?.statements?.forEach {
                            walkForNestedImports(it, source, fileName, topLevel = false)
                        }
                        is Constructor -> m.body?.statements?.forEach {
                            walkForNestedImports(it, source, fileName, topLevel = false)
                        }
                        is GetAccessor -> m.body?.statements?.forEach {
                            walkForNestedImports(it, source, fileName, topLevel = false)
                        }
                        is SetAccessor -> m.body?.statements?.forEach {
                            walkForNestedImports(it, source, fileName, topLevel = false)
                        }
                        else -> {}
                    }
                }
            }
            is ModuleDeclaration -> {
                // Module body — top-level inside namespace counts as top-level too
                // (`namespace M { import X from "y"; }` is allowed).
                (stmt.body as? ModuleBlock)?.statements?.forEach {
                    walkForNestedImports(it, source, fileName, topLevel = true)
                }
            }
            is Block -> stmt.statements.forEach { walkForNestedImports(it, source, fileName, topLevel = false) }
            is IfStatement -> {
                walkForNestedImports(stmt.thenStatement, source, fileName, topLevel = false)
                stmt.elseStatement?.let { walkForNestedImports(it, source, fileName, topLevel = false) }
            }
            is WhileStatement -> walkForNestedImports(stmt.statement, source, fileName, topLevel = false)
            is DoStatement -> walkForNestedImports(stmt.statement, source, fileName, topLevel = false)
            is ForStatement -> walkForNestedImports(stmt.statement, source, fileName, topLevel = false)
            is ForInStatement -> walkForNestedImports(stmt.statement, source, fileName, topLevel = false)
            is ForOfStatement -> walkForNestedImports(stmt.statement, source, fileName, topLevel = false)
            is TryStatement -> {
                stmt.tryBlock.statements.forEach { walkForNestedImports(it, source, fileName, topLevel = false) }
                stmt.catchClause?.block?.statements?.forEach { walkForNestedImports(it, source, fileName, topLevel = false) }
                stmt.finallyBlock?.statements?.forEach { walkForNestedImports(it, source, fileName, topLevel = false) }
            }
            is SwitchStatement -> {
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> clause.statements.forEach { walkForNestedImports(it, source, fileName, topLevel = false) }
                        is DefaultClause -> clause.statements.forEach { walkForNestedImports(it, source, fileName, topLevel = false) }
                        else -> {}
                    }
                }
            }
            is LabeledStatement -> walkForNestedImports(stmt.statement, source, fileName, topLevel = false)
            else -> {}
        }
    }

    /**
     * B98.r17: TS2440 "Import declaration conflicts with local declaration of 'X'." for an
     * `import X = <internal-ns-ref>` (NOT `require(...)`) declared inside a namespace block whose
     * name X collides with a `var/let/const X` declared in ANOTHER block of the SAME merged
     * namespace. The file-level `checkImportConflictsWithLocal` never recurses into namespace
     * bodies, so cross-block conflicts inside a merged namespace were missed. Only conflicts with
     * VARIABLES (internal aliases declaration-merge with class/function/enum, so those don't count).
     */
    fun checkNamespaceImportVarConflict() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkNsImportVarConflictInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkNsImportVarConflictInStatements(stmts: List<Statement>, source: String, fileName: String) {
        val nsGroups = mutableMapOf<String, MutableList<ModuleDeclaration>>()
        for (stmt in stmts) {
            if (stmt is ModuleDeclaration) {
                (stmt.name as? Identifier)?.let { nsGroups.getOrPut(it.text) { mutableListOf() }.add(stmt) }
                (stmt.body as? ModuleBlock)?.let { checkNsImportVarConflictInStatements(it.statements, source, fileName) }
            }
        }
        for ((_, blocks) in nsGroups) {
            // (CHK.224) The alias and the variable conflict only when they land in ONE symbol
            // table, as tsgo's binder (`declareModuleMember`) puts them: an EXPORTED import goes
            // into the namespace's exports only, so it meets an exported variable of ANY block;
            // a LOCAL import goes into its own block's locals, where every variable of that
            // block also lives (an exported one as its local export-value twin). A local import
            // never meets a variable of another block, and an exported import never meets a
            // non-exported variable — tsgo is silent on both.
            val exportedVarNames = mutableSetOf<String>()
            // Aligned with [blocks] (a data-class AST key would deep-hash the whole body).
            val blockVarNames = List(blocks.size) { mutableSetOf<String>() }
            for ((bi, block) in blocks.withIndex()) {
                val body = block.body as? ModuleBlock ?: continue
                val own = blockVarNames[bi]
                for (s in body.statements) if (s is VariableStatement) {
                    val exported = ModifierFlag.Export in s.modifiers
                    for (decl in s.declarationList.declarations) (decl.name as? Identifier)?.let {
                        own.add(it.text)
                        if (exported) exportedVarNames.add(it.text)
                    }
                }
            }
            if (blockVarNames.all { it.isEmpty() }) continue
            for ((bi, block) in blocks.withIndex()) {
                val body = block.body as? ModuleBlock ?: continue
                val own = blockVarNames[bi]
                for (s in body.statements) {
                    if (s !is ImportEqualsDeclaration) continue
                    if (s.isTypeOnly) continue
                    if (s.moduleReference is ExternalModuleReference) continue   // `require(...)` is a different conflict
                    val name = s.name.text
                    val meets = if (ModifierFlag.Export in s.modifiers) exportedVarNames else own
                    if (name !in meets) continue
                    // ImportEqualsDeclaration.pos points at `import`; back up over the
                    // `export` modifier (parser drops it from pos) so the squiggle covers
                    // the whole `export import X = …;` statement.
                    var stmtStart = s.pos
                    if (ModifierFlag.Export in s.modifiers) {
                        var p = s.pos
                        while (p > 0 && source[p - 1].isWhitespace()) p--
                        if (p >= 6 && source.substring(p - 6, p) == "export") stmtStart = p - 6
                    }
                    val endIdx = source.indexOf(';', stmtStart)
                    val stmtLen = if (endIdx >= 0) endIdx - stmtStart + 1 else (s.end - stmtStart).coerceAtLeast(name.length)
                    val (line, character) = checker.getLineAndCharacterOfPosition(source, stmtStart)
                    checker.diagnostics.add(Diagnostic(
                        message = "Import declaration conflicts with local declaration of '$name'.",
                        category = DiagnosticCategory.Error,
                        code = 2440,
                        fileName = fileName,
                        line = line,
                        character = character,
                        start = stmtStart,
                        length = stmtLen,
                    ))
                }
            }
        }
    }

    fun checkImportConflictsWithLocal() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            val stmts = result.sourceFile.statements

            // Collect all non-import declared names in this file, tracking declaration kind
            // Variables conflict with import aliases; classes/functions/interfaces can merge
            val varNames = mutableSetOf<String>()     // variable declarations
            val mergeableNames = mutableSetOf<String>() // class/function/enum — can merge with internal aliases
            val typeOnlyNames = mutableSetOf<String>() // type alias / interface — can conflict with type-only imports
            // Type aliases specifically — these CANNOT merge with any other declaration kind
            // (unlike interfaces which can merge with classes/namespaces/values via declaration merging).
            // `import { X } from "..."` (binding a value) + local `type X = ...` is therefore a hard
            // conflict regardless of the imported symbol's kind, distinct from the type-only-vs-type-only
            // case handled by `isExportedNameTypeOnly`.
            val typeAliasNames = mutableSetOf<String>()
            // Local value-declaration names that a NAMED import cannot share (function /
            // class / enum / namespace). A named `import { X }` does not declaration-merge
            // with a local function/class/enum/namespace of the same name → TS2440. (This is
            // distinct from `mergeableNames`, which exists to AVOID conflicting with an
            // import-equals INTERNAL alias.)
            val namedImportConflictNames = mutableSetOf<String>()
            // VALUE-only local declaration names (functions + value vars) and names carrying a
            // TYPE side (class/interface/enum/typealias/namespace). A type-only import merges
            // with a value-only local of the same name (no TS2440) — see
            // importedNameIsTypeOnlyThroughBarrel.
            val functionNames = mutableSetOf<String>()
            val typeSideNames = mutableSetOf<String>()
            for (stmt in stmts) {
                when (stmt) {
                    is VariableStatement -> {
                        for (decl in stmt.declarationList.declarations) {
                            val name = (decl.name as? Identifier)?.text ?: continue
                            varNames.add(name)
                        }
                    }
                    is FunctionDeclaration -> stmt.name?.let { mergeableNames.add(it.text); namedImportConflictNames.add(it.text); functionNames.add(it.text) }
                    is ClassDeclaration -> stmt.name?.let { mergeableNames.add(it.text); namedImportConflictNames.add(it.text); typeSideNames.add(it.text) }
                    is EnumDeclaration -> { mergeableNames.add(stmt.name.text); namedImportConflictNames.add(stmt.name.text); typeSideNames.add(stmt.name.text) }
                    is ModuleDeclaration -> (stmt.name as? Identifier)?.let { namedImportConflictNames.add(it.text); typeSideNames.add(it.text) }
                    is TypeAliasDeclaration -> {
                        typeOnlyNames.add(stmt.name.text)
                        typeAliasNames.add(stmt.name.text)
                        typeSideNames.add(stmt.name.text)
                    }
                    is InterfaceDeclaration -> { typeOnlyNames.add(stmt.name.text); typeSideNames.add(stmt.name.text) }
                    else -> {}
                }
            }
            val localNames = varNames + mergeableNames
            // A local declared solely as a value (function or value var), never with a type side.
            val valueOnlyLocalNames = (functionNames + varNames) - typeSideNames

            // B129: TS2395 — a name declared by BOTH an internal import-equals alias AND
            // a type-alias (a merged declaration) with MIXED export status (one exported,
            // one not) → "must be all exported or all local." Emit at each declaration's
            // name. Narrowly gated to this specific merge (FP-safe: mixed-export of an
            // import-equals + type-alias is always TS2395). Pairs with the TS2440 below.
            run {
                val mergeDecls = HashMap<String, MutableList<Pair<Int, Boolean>>>()
                val sawImportEq = HashSet<String>()
                for (s in stmts) when (s) {
                    is ImportEqualsDeclaration -> if (!s.isTypeOnly && s.moduleReference !is ExternalModuleReference) {
                        mergeDecls.getOrPut(s.name.text) { mutableListOf() }.add(s.name.pos to (ModifierFlag.Export in s.modifiers))
                        sawImportEq.add(s.name.text)
                    }
                    is TypeAliasDeclaration -> mergeDecls.getOrPut(s.name.text) { mutableListOf() }.add(s.name.pos to (ModifierFlag.Export in s.modifiers))
                    else -> {}
                }
                for ((nm, ds) in mergeDecls) {
                    if (nm !in sawImportEq || ds.size < 2) continue
                    if (ds.any { it.second } && ds.any { !it.second }) {
                        for ((pos, _) in ds) {
                            val (line, character) = checker.getLineAndCharacterOfPosition(source, pos)
                            checker.diagnostics.add(Diagnostic(
                                message = "Individual declarations in merged declaration '$nm' must be all exported or all local.",
                                category = DiagnosticCategory.Error, code = 2395,
                                fileName = fileName, line = line, character = character, start = pos, length = nm.length,
                            ))
                        }
                    }
                }
            }

            // Check imports against local names
            for (stmt in stmts) {
                when (stmt) {
                    is ImportEqualsDeclaration -> {
                        if (stmt.isTypeOnly) continue
                        // B61.5f: Skip TS2440 when import-equals has class-only modifier keywords
                        // in source text (parser drops them from stmt.modifiers, so source-scan).
                        // TS1044 fires for these and the binding shouldn't also conflict.
                        if (sourceHasClassOnlyModifierBeforeImportEquals(stmt, source)) continue
                        // Internal namespace aliases (import foo = m1) can merge with class/function/interface
                        // but NOT with variable declarations
                        // Internal namespace aliases (import foo = m1) can merge with
                        // class/function/interface but NOT with variable declarations OR
                        // type-aliases. B129: a local internal value-side alias colliding
                        // with a local `type X` is TS2440 — type-aliases never
                        // declaration-merge with an import alias.
                        val isInternalAlias = stmt.moduleReference !is ExternalModuleReference
                        val name = stmt.name.text
                        if (isInternalAlias && name !in varNames && name !in typeAliasNames) continue
                        if (name in localNames || (isInternalAlias && name in typeAliasNames)) {
                            // Error on entire import statement
                            val stmtStart = stmt.pos
                            // Find end of statement including semicolon
                            val stmtEnd = stmt.end
                            // Approximate length: scan source for semicolon or newline after pos
                            val endIdx = source.indexOf(';', stmtStart)
                            val stmtLen = if (endIdx >= 0) endIdx - stmtStart + 1
                                else (stmtEnd - stmtStart).coerceAtLeast(name.length)
                            val (line, character) = checker.getLineAndCharacterOfPosition(source, stmtStart)
                            checker.diagnostics.add(Diagnostic(
                                message = "Import declaration conflicts with local declaration of '$name'.",
                                category = DiagnosticCategory.Error,
                                code = 2440,
                                fileName = fileName,
                                line = line,
                                character = character,
                                start = stmtStart,
                                length = stmtLen,
                            ))
                        }
                    }
                    is ImportDeclaration -> {
                        // TS2440 fires for bindings that conflict with local var declarations.
                        // Rule: fires when there's a var with the same name AFTER the import AND
                        // NO var with the same name BEFORE the import (first occurrence determines the slot).
                        val clause = stmt.importClause ?: continue
                        // `import type { X }` / `import type X` create no runtime binding, so no conflict.
                        if (clause.isTypeOnly) continue
                        val importPos = stmt.pos
                        val moduleSpecifierText = (stmt.moduleSpecifier as? StringLiteralNode)?.text

                        // Helper: check if name conflicts (var after import, no var before import)
                        fun hasVarConflict(name: String): Boolean {
                            val varBeforeImport = stmts.any { s ->
                                s is VariableStatement && s.pos < importPos &&
                                    s.declarationList.declarations.any { d ->
                                        (d.name as? Identifier)?.text == name
                                    }
                            }
                            if (varBeforeImport) return false
                            return stmts.any { s ->
                                s is VariableStatement && s.pos > importPos &&
                                    s.declarationList.declarations.any { d ->
                                        (d.name as? Identifier)?.text == name
                                    }
                            }
                        }

                        // Default binding
                        val defaultName = clause.name?.text
                        if (defaultName != null && defaultName in varNames) {
                            if (hasVarConflict(defaultName)) {
                                val (line, character) = checker.getLineAndCharacterOfPosition(source, clause.name.pos)
                                checker.diagnostics.add(Diagnostic(
                                    message = "Import declaration conflicts with local declaration of '$defaultName'.",
                                    category = DiagnosticCategory.Error,
                                    code = 2440,
                                    fileName = fileName,
                                    line = line,
                                    character = character,
                                    start = clause.name.pos,
                                    length = defaultName.length,
                                ))
                            }
                        }
                        // Namespace binding: import * as NS
                        val nb = clause.namedBindings
                        if (nb is NamespaceImport) {
                            val nsName = nb.name.text
                            // A namespace import does NOT declaration-merge with a local
                            // `namespace NS {}` declared LATER in the file (namespaceMergedWith-
                            // ImportAliasNoCrash) → TS2440. Gated to a non-declare local namespace
                            // declared after the import (mirrors the var-after-import rule; avoids
                            // ambient-augmentation FPs).
                            val nsAfterImport = stmts.any { s ->
                                s is ModuleDeclaration && s.pos > importPos &&
                                    ModifierFlag.Declare !in s.modifiers &&
                                    (s.name as? Identifier)?.text == nsName
                            }
                            if (nsName in varNames && hasVarConflict(nsName) || nsAfterImport) {
                                val (line, character) = checker.getLineAndCharacterOfPosition(source, nb.name.pos)
                                checker.diagnostics.add(Diagnostic(
                                    message = "Import declaration conflicts with local declaration of '$nsName'.",
                                    category = DiagnosticCategory.Error,
                                    code = 2440,
                                    fileName = fileName,
                                    line = line,
                                    character = character,
                                    start = nb.name.pos,
                                    length = nsName.length,
                                ))
                            }
                        }
                        // Named specifiers: import { x } or { x as localAlias }
                        if (nb is NamedImports) {
                            for (element in nb.elements) {
                                // Per-specifier `import { type X }` creates no runtime binding.
                                if (element.isTypeOnly) continue
                                val localAlias = element.name.text
                                val sourceNameForElt = (element.propertyName ?: element.name).text
                                // A type-only import (the source exports the name ONLY as a type,
                                // possibly through an `export *` barrel) declaration-merges with a
                                // local VALUE-only declaration (function / value const) of the same
                                // name — the import provides the type, the local provides the value —
                                // so NO TS2440 (tsc's own src/compiler `import { Node }` + local
                                // `function Node`). Gated to value-only locals (a class/enum/
                                // interface/namespace local has a type side that DOES conflict) and
                                // to a definitively type-only barrel resolution (FN-safe). ONLY under
                                // NEITHER isolatedModules NOR verbatimModuleSyntax — those modes DO
                                // error on the merge (TS2865 / TS1484 / TS2440), just via the paths
                                // below (isolatedModulesSketchyAliasLocalMerge / …ExportDeclarationType).
                                if (!options.isolatedModules && !options.verbatimModuleSyntax &&
                                    localAlias in valueOnlyLocalNames &&
                                    moduleSpecifierText != null &&
                                    importedNameIsTypeOnlyThroughBarrel(sourceNameForElt, moduleSpecifierText, fileName)) {
                                    continue
                                }
                                // Type-only-vs-type-only conflict: `import { T }` (where T is a type-only
                                // export in source) + local `type T` / `interface T` declaration. Both
                                // declare T as a type, so they collide. Emit TS2440 (NOT TS2865 — local
                                // is a type, not a value). The narrow gate (isExportedNameTypeOnly) avoids
                                // FPs against valid `import { Class }` + `interface Class` augmentation
                                // patterns (where Class is a value+type in source).
                                if (localAlias in typeOnlyNames &&
                                    moduleSpecifierText != null &&
                                    isExportedNameTypeOnly(sourceNameForElt, moduleSpecifierText)) {
                                    val startNode = element.propertyName ?: element.name
                                    val startPos = startNode.pos
                                    val spanLen = if (element.propertyName != null) {
                                        element.name.pos + element.name.text.length - startPos
                                    } else {
                                        localAlias.length
                                    }
                                    val (line, character) = checker.getLineAndCharacterOfPosition(source, startPos)
                                    checker.diagnostics.add(Diagnostic(
                                        message = "Import declaration conflicts with local declaration of '$localAlias'.",
                                        category = DiagnosticCategory.Error,
                                        code = 2440,
                                        fileName = fileName,
                                        line = line,
                                        character = character,
                                        start = startPos,
                                        length = spanLen,
                                    ))
                                    continue
                                }
                                // TypeAlias-vs-value-import conflict: `type X = ...` cannot share a name
                                // with `import { X } from "..."` since TypeAlias doesn't participate in
                                // declaration merging. Mirrors TS2440 fired in this exact shape (e.g.
                                // `import {E} from "./f1"; type E = E;` where f1 re-exports a value).
                                if (localAlias in typeAliasNames) {
                                    val startNode = element.propertyName ?: element.name
                                    val startPos = startNode.pos
                                    val spanLen = if (element.propertyName != null) {
                                        element.name.pos + element.name.text.length - startPos
                                    } else {
                                        localAlias.length
                                    }
                                    val (line, character) = checker.getLineAndCharacterOfPosition(source, startPos)
                                    checker.diagnostics.add(Diagnostic(
                                        message = "Import declaration conflicts with local declaration of '$localAlias'.",
                                        category = DiagnosticCategory.Error,
                                        code = 2440,
                                        fileName = fileName,
                                        line = line,
                                        character = character,
                                        start = startPos,
                                        length = spanLen,
                                    ))
                                    continue
                                }
                                // Named import of a value name conflicting with a local
                                // function / class / enum / namespace declaration → TS2440
                                // (these do not declaration-merge with a named import).
                                if (localAlias in namedImportConflictNames) {
                                    val startNode = element.propertyName ?: element.name
                                    val startPos = startNode.pos
                                    val spanLen = if (element.propertyName != null) {
                                        element.name.pos + element.name.text.length - startPos
                                    } else {
                                        localAlias.length
                                    }
                                    val (line, character) = checker.getLineAndCharacterOfPosition(source, startPos)
                                    checker.diagnostics.add(Diagnostic(
                                        message = "Import declaration conflicts with local declaration of '$localAlias'.",
                                        category = DiagnosticCategory.Error,
                                        code = 2440,
                                        fileName = fileName,
                                        line = line,
                                        character = character,
                                        start = startPos,
                                        length = spanLen,
                                    ))
                                    continue
                                }
                                if (localAlias in varNames) {
                                    if (hasVarConflict(localAlias)) {
                                        // Position at the start of the element (propertyName if aliased, otherwise name)
                                        // Span covers the full element including "as" keyword: "x as y"
                                        val startNode = element.propertyName ?: element.name
                                        val startPos = startNode.pos
                                        val spanLen = if (element.propertyName != null) {
                                            // "x as y" — from property name start to alias name end
                                            element.name.pos + element.name.text.length - startPos
                                        } else {
                                            localAlias.length
                                        }
                                        val (line, character) = checker.getLineAndCharacterOfPosition(source, startPos)
                                        // TS2865: under isolatedModules, when the imported name resolves to a
                                        // type-only export in the source module, the import should have been
                                        // `import type { X }`. Emit TS2865 in place of TS2440 to direct the
                                        // user to the right fix. verbatimModuleSyntax routes through TS1484.
                                        val sourceName = (element.propertyName ?: element.name).text
                                        val sourceIsTypeOnly = moduleSpecifierText != null &&
                                            isExportedNameTypeOnly(sourceName, moduleSpecifierText)
                                        // 17.131: Under verbatimModuleSyntax + type-only-in-source, the new
                                        // checkVerbatimModuleSyntax walker emits TS1484 covering the same
                                        // diagnostic surface — suppress TS2440 here. TS2865 still fires
                                        // alongside TS1484 when isolatedModules is also enabled.
                                        val verbatimTypeOnlyInSource = options.verbatimModuleSyntax && sourceIsTypeOnly
                                        val isolatedTypeOnlyInSource = options.isolatedModules && sourceIsTypeOnly
                                        if (isolatedTypeOnlyInSource) {
                                            checker.diagnostics.add(Diagnostic(
                                                message = "Import '$localAlias' conflicts with local value, so must be declared with a type-only import when 'isolatedModules' is enabled.",
                                                category = DiagnosticCategory.Error,
                                                code = 2865,
                                                fileName = fileName,
                                                line = line,
                                                character = character,
                                                start = startPos,
                                                length = spanLen,
                                            ))
                                        }
                                        if (!verbatimTypeOnlyInSource && !isolatedTypeOnlyInSource) {
                                            checker.diagnostics.add(Diagnostic(
                                                message = "Import declaration conflicts with local declaration of '$localAlias'.",
                                                category = DiagnosticCategory.Error,
                                                code = 2440,
                                                fileName = fileName,
                                                line = line,
                                                character = character,
                                                start = startPos,
                                                length = spanLen,
                                            ))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    // TS1292: 'X' resolves to a type and must be marked type-only when re-exporting under isolatedModules
    // -----------------------------------------------------------------------

    /**
     * Under `isolatedModules`, `export default X` where X resolves to a type-only import
     * (and there's no local value declaration named X) requires `export type { X as default }`
     * because the transpiler can't know whether to elide the export at runtime. Emits TS1292
     * at the expression's position. Skipped when `verbatimModuleSyntax` is enabled (different
     * diagnostic family fires there).
     */
    /**
     * TS1269: "Cannot use 'export import' on a type or type-only namespace when
     * 'isolatedModules' is enabled." Fires for `export import X = Y` (or `export import X = A.B`)
     * where Y resolves to a type alias, interface, or type-only namespace under
     * `@isolatedModules`. The transpiler cannot keep the runtime binding if Y has no value side.
     */
    fun checkIsolatedModulesExportImportIsType() {
        if (!options.isolatedModules || options.verbatimModuleSyntax) return
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            val stmts = result.sourceFile.statements

            // Detect: is the local binding `name` an import alias of a type-only export
            // from some other file in this program?
            fun importedFromTypeOnlyExport(name: String): Boolean {
                for (stmt in stmts) {
                    if (stmt !is ImportDeclaration) continue
                    val clause = stmt.importClause ?: continue
                    val moduleSpec = (stmt.moduleSpecifier as? StringLiteralNode)?.text ?: continue
                    val nb = clause.namedBindings
                    if (nb is NamedImports) {
                        val element = nb.elements.firstOrNull { it.name.text == name } ?: continue
                        if (clause.isTypeOnly || element.isTypeOnly) return true
                        val sourceName = (element.propertyName ?: element.name).text
                        if (isExportedNameTypeOnly(sourceName, moduleSpec)) return true
                        // Also handle exported namespace whose members are all types — the
                        // existing helper doesn't yet walk ModuleDeclaration.
                        val targetFile = checker.resolveModuleSpecifier(moduleSpec, null) ?: continue
                        val targetResult = checker.fileResults[targetFile] ?: continue
                        for (s in targetResult.sourceFile.statements) {
                            if (s !is ModuleDeclaration) continue
                            val moduleName = s.name
                            val n = if (moduleName is Identifier) moduleName.text
                                else if (moduleName is StringLiteralNode) moduleName.text
                                else null
                            if (n != sourceName) continue
                            if (ModifierFlag.Export !in s.modifiers) continue
                            // Check via binder's moduleInstanceState — a non-instantiated namespace
                            // (only type members) qualifies.
                            val state = checker.moduleInstanceStateOf(s)
                            if (state == ModuleInstanceState.NonInstantiated) return true
                        }
                    }
                    if (nb is NamespaceImport && nb.name.text == name) {
                        // `import * as X from "./m"` — X is a namespace import. Treat as
                        // type-only if the target file exports ONLY types (no value
                        // declarations). Skip for now to avoid over-firing; this is the
                        // narrower namespaced-from-import path.
                    }
                }
                return false
            }

            for (stmt in stmts) {
                if (stmt !is ImportEqualsDeclaration) continue
                if (ModifierFlag.Export !in stmt.modifiers) continue
                // Fires for `export import X = SomeIdent` where SomeIdent resolves to type-only,
                // AND `export import X = require("spec")` where the required module is type-only
                // (its `export = T` target is a type alias/interface).
                val ref = stmt.moduleReference
                val isTypeOnlyRef = if (ref is ExternalModuleReference) {
                    val spec = (ref.expression as? StringLiteralNode)?.text
                    spec != null && isRequireModuleTypeOnly(spec)
                } else {
                    val rootName = when (ref) {
                        is Identifier -> ref.text
                        is QualifiedName -> {
                            var leftmost: Node = ref
                            while (leftmost is QualifiedName) leftmost = leftmost.left
                            (leftmost as? Identifier)?.text
                        }
                        else -> null
                    }
                    rootName != null && importedFromTypeOnlyExport(rootName)
                }
                if (!isTypeOnlyRef) continue
                // The parser's stmt.pos points to `import` (after the `export` modifier was
                // consumed). Walk backward through whitespace to find the start of `export`.
                var startPos = stmt.pos
                var p = startPos - 1
                while (p >= 0 && source[p] in " \t\r\n") p--
                if (p >= 5 && source.substring(p - 5, p + 1) == "export") {
                    startPos = p - 5
                }
                // End at the semicolon (stmt.end overshoots per CLAUDE.md gotcha).
                val semi = source.indexOf(';', stmt.pos)
                val endPos = if (semi >= 0) semi + 1 else stmt.end
                val length = endPos - startPos
                val (line, character) = checker.getLineAndCharacterOfPosition(source, startPos)
                checker.diagnostics.add(Diagnostic(
                    message = "Cannot use 'export import' on a type or type-only namespace when 'isolatedModules' is enabled.",
                    category = DiagnosticCategory.Error,
                    code = 1269,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = startPos,
                    length = length,
                ))
            }
        }
    }

    fun checkIsolatedModulesExportDefaultIsType() {
        if (!options.isolatedModules || options.verbatimModuleSyntax) return
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            val stmts = result.sourceFile.statements

            // Collect names declared as values in this file (used to short-circuit when the
            // exported identifier is shadowed by a same-name local value declaration).
            val localValueNames = mutableSetOf<String>()
            for (stmt in stmts) {
                when (stmt) {
                    is VariableStatement ->
                        for (decl in stmt.declarationList.declarations) {
                            (decl.name as? Identifier)?.text?.let { localValueNames.add(it) }
                        }
                    is FunctionDeclaration -> stmt.name?.let { localValueNames.add(it.text) }
                    is ClassDeclaration -> stmt.name?.let { localValueNames.add(it.text) }
                    is EnumDeclaration -> localValueNames.add(stmt.name.text)
                    else -> {}
                }
            }

            // Returns true when [name] is imported and resolves to a type-only export in the source
            // module — covers both syntactic `import type { X }` / `import { type X }` AND
            // `import { X }` where X happens to be a type-only export in the source module.
            fun isImportedAsTypeOnly(name: String): Boolean {
                for (stmt in stmts) {
                    if (stmt !is ImportDeclaration) continue
                    val clause = stmt.importClause ?: continue
                    val nb = clause.namedBindings as? NamedImports ?: continue
                    val element = nb.elements.firstOrNull { it.name.text == name } ?: continue
                    if (clause.isTypeOnly || element.isTypeOnly) return true
                    val moduleSpec = (stmt.moduleSpecifier as? StringLiteralNode)?.text ?: continue
                    val sourceName = (element.propertyName ?: element.name).text
                    if (isExportedNameTypeOnly(sourceName, moduleSpec)) return true
                }
                return false
            }

            for (stmt in stmts) {
                if (stmt !is ExportAssignment) continue
                if (stmt.isExportEquals) continue // `export = X` is a separate diagnostic family
                val expr = stmt.expression as? Identifier ?: continue
                val name = expr.text
                if (name in localValueNames) continue
                if (!isImportedAsTypeOnly(name)) continue
                val (line, character) = checker.getLineAndCharacterOfPosition(source, expr.pos)
                checker.diagnostics.add(Diagnostic(
                    message = "'$name' resolves to a type and must be marked type-only in this file before re-exporting when 'isolatedModules' is enabled. Consider using 'export type { $name as default }'.",
                    category = DiagnosticCategory.Error,
                    code = 1292,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = expr.pos,
                    length = name.length,
                ))
            }
        }
    }

    // 17.131: TS1295 / TS1484 — verbatimModuleSyntax restrictions on imports/exports.
    // -----------------------------------------------------------------------

    /**
     * Under `verbatimModuleSyntax`, two related diagnostics fire for non-type-only imports:
     *  - TS1295: in a CommonJS file, ECMAScript imports/exports cannot be written
     *    (the runtime bindings would need to be transformed to require()/exports.X — but
     *    verbatim mode preserves syntax as-written, so the file would be invalid CJS).
     *  - TS1484: when the imported name is a type-only export in the source module, it
     *    must be imported using a type-only form (`import type { X }` or `import { type X }`).
     *
     * Both fire at the imported-name position. Type-only imports (`import type {}`) are
     * exempt from both — they're erased at compile time and have no runtime emission.
     *
     * Scope: named imports + default imports + namespace imports. Side-effect imports
     * (`import "./x"`), import-equals, and export declarations are not yet handled here
     * (deferred — additional surface that may yield more failing-test flips when added).
     */
    fun checkVerbatimModuleSyntax() {
        if (!options.verbatimModuleSyntax) return
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            val isCjs = !isESModuleFormat(options, fileName)

            fun emitTs1295(pos: Int, length: Int) {
                val (line, character) = checker.getLineAndCharacterOfPosition(source, pos)
                checker.diagnostics.add(Diagnostic(
                    message = "ECMAScript imports and exports cannot be written in a CommonJS file under 'verbatimModuleSyntax'. Adjust the 'type' field in the nearest 'package.json' to make this file an ECMAScript module, or adjust your 'verbatimModuleSyntax', 'module', and 'moduleResolution' settings in TypeScript.",
                    category = DiagnosticCategory.Error,
                    code = 1295,
                    fileName = fileName, line = line, character = character,
                    start = pos, length = length,
                ))
            }

            fun emitTs1484(pos: Int, length: Int, displayName: String) {
                val (line, character) = checker.getLineAndCharacterOfPosition(source, pos)
                checker.diagnostics.add(Diagnostic(
                    message = "'$displayName' is a type and must be imported using a type-only import when 'verbatimModuleSyntax' is enabled.",
                    category = DiagnosticCategory.Error,
                    code = 1484,
                    fileName = fileName, line = line, character = character,
                    start = pos, length = length,
                ))
            }

            fun emitTs1287(pos: Int) {
                val (line, character) = checker.getLineAndCharacterOfPosition(source, pos)
                checker.diagnostics.add(Diagnostic(
                    message = "A top-level 'export' modifier cannot be used on value declarations in a CommonJS module when 'verbatimModuleSyntax' is enabled.",
                    category = DiagnosticCategory.Error,
                    code = 1287,
                    fileName = fileName, line = line, character = character,
                    start = pos, length = 6,  // "export"
                ))
            }

            for (stmt in result.sourceFile.statements) {
                when (stmt) {
                    is ImportDeclaration -> {
                        val clause = stmt.importClause ?: continue
                        // `import type { ... }` — entirely type-only, no diagnostics fire here.
                        if (clause.isTypeOnly) continue
                        val moduleSpecifierText = (stmt.moduleSpecifier as? StringLiteralNode)?.text

                        // Default binding: `import X from "./y"`
                        clause.name?.let { defaultName ->
                            if (isCjs) emitTs1295(defaultName.pos, defaultName.text.length)
                            // TS1484 for default imports of type-only-default exports is not yet handled
                            // (default-as-type is rare and detection requires checking the source's
                            // `export default X` for type-only X — out of scope for the named-imports
                            // path we're focused on).
                        }

                        when (val nb = clause.namedBindings) {
                            is NamespaceImport -> {
                                // `import * as ns from "./y"` — namespace import is non-type-only at the binding,
                                // so TS1295 fires in CJS+verbatim. (TS1484 doesn't apply: a namespace import
                                // brings in both types and values, so type-only-import isn't a meaningful
                                // alternative even when the source is mostly types.)
                                if (isCjs) emitTs1295(nb.name.pos, nb.name.text.length)
                            }
                            is NamedImports -> {
                                for (element in nb.elements) {
                                    if (element.isTypeOnly) continue  // per-element `type` qualifier
                                    val nameNode = element.propertyName ?: element.name
                                    val pos = nameNode.pos
                                    val len = nameNode.text.length
                                    val sourceName = (element.propertyName ?: element.name).text
                                    if (isCjs) emitTs1295(pos, len)
                                    if (moduleSpecifierText != null &&
                                        isExportedNameTypeOnly(sourceName, moduleSpecifierText)) {
                                        emitTs1484(pos, len, sourceName)
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                    // TS1287: top-level `export` modifier on value declaration in CJS+verbatim.
                    // Value-shape declarations: class / function / var-statement (let/const/var) / enum.
                    // Type-shape declarations (interface, type alias) are exempt — type-only erases at compile time.
                    // `declare`-prefixed declarations are ambient (type-only at runtime) — exempt.
                    is ClassDeclaration -> {
                        if (isCjs && ModifierFlag.Export in stmt.modifiers &&
                            ModifierFlag.Declare !in stmt.modifiers) {
                            val exportPos = checker.srcLastIndexOf(source, "export", stmt.pos)
                            if (exportPos >= 0) emitTs1287(exportPos)
                        }
                    }
                    is FunctionDeclaration -> {
                        if (isCjs && ModifierFlag.Export in stmt.modifiers &&
                            ModifierFlag.Declare !in stmt.modifiers) {
                            val exportPos = checker.srcLastIndexOf(source, "export", stmt.pos)
                            if (exportPos >= 0) emitTs1287(exportPos)
                        }
                    }
                    is VariableStatement -> {
                        if (isCjs && ModifierFlag.Export in stmt.modifiers &&
                            ModifierFlag.Declare !in stmt.modifiers) {
                            val exportPos = checker.srcLastIndexOf(source, "export", stmt.pos)
                            if (exportPos >= 0) emitTs1287(exportPos)
                        }
                    }
                    is EnumDeclaration -> {
                        if (isCjs && ModifierFlag.Export in stmt.modifiers &&
                            ModifierFlag.Declare !in stmt.modifiers) {
                            val exportPos = checker.srcLastIndexOf(source, "export", stmt.pos)
                            if (exportPos >= 0) emitTs1287(exportPos)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    // TS2661: Cannot export non-local declaration
    // -----------------------------------------------------------------------

    /**
     * (CHK.224) tsgo's TS2661 test (`checkExportSpecifier`): the symbol's FIRST declaration has a
     * global source file as its declaration container. A declaration inside `declare global { }`
     * has the augmentation's module block as its container, so `declare global { var gv: number }
     * export { gv }` is legal — tsgo is silent for every declaration kind there. An unparented
     * declaration (no index stamp) keeps the historical "any real global declaration" answer.
     */
    private fun firstDeclarationIsGlobalFileTopLevel(sym: Symbol): Boolean {
        val decl = sym.declarations.firstOrNull { it !is ExportSpecifier } ?: return false
        var n: Node = decl
        while (n is BindingElement || n is ObjectBindingPattern || n is ArrayBindingPattern ||
            n is VariableDeclaration || n is VariableDeclarationList) {
            n = (n as? NodeBase)?.parent ?: return true
        }
        val container = (n as? NodeBase)?.parent ?: return true
        return container is SourceFile
    }

    fun checkExportSpecifierLocality() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            val locals = result.locals

            for (stmt in result.sourceFile.statements) {
                if (stmt !is ExportDeclaration) continue
                // Only check `export { X }` without `from "..."` (re-exports from modules are fine)
                if (stmt.moduleSpecifier != null) continue
                val stmtTypeOnly = stmt.isTypeOnly
                val namedExports = stmt.exportClause as? NamedExports ?: continue

                for (spec in namedExports.elements) {
                    val specTypeOnly = stmtTypeOnly || spec.isTypeOnly
                    val exportedName = (spec.propertyName ?: spec.name).text
                    // Check if locally declared (not just aliased by the export itself)
                    val localSymbol = locals[exportedName]
                    if (localSymbol != null) {
                        // If the symbol has non-ExportSpecifier declarations, it's a real local
                        val hasRealDecl = localSymbol.declarations.any { it !is ExportSpecifier }
                        if (hasRealDecl) continue
                    }
                    val nameNode = spec.propertyName ?: spec.name
                    // `globals` is over-merged and the binder also binds the export specifier's own
                    // name into it, so a bare `name in globals` is fooled by the export itself.
                    // Require a REAL (non-ExportSpecifier) global declaration, or a KNOWN_GLOBALS hit.
                    val globalSym = checker.globals[exportedName]
                    val isGlobal = exportedName == "undefined" ||
                        exportedName in Checker.KNOWN_GLOBALS ||
                        (globalSym != null && firstDeclarationIsGlobalFileTopLevel(globalSym))
                    // (CHK.224) A real global declared inside `declare global { }` resolves and is
                    // exportable: neither TS2661 nor "Cannot find name".
                    if (!isGlobal && globalSym != null && globalSym.declarations.any { it !is ExportSpecifier }) continue
                    if (isGlobal) {
                        // export { Global } (value re-export of a global) → TS2661. TypeScript does
                        // NOT emit this for a type-only re-export, so skip those.
                        if (specTypeOnly) continue
                        val (line, character) = checker.getLineAndCharacterOfPosition(source, nameNode.pos)
                        checker.diagnostics.add(Diagnostic(
                            message = "Cannot export '$exportedName'. Only local declarations can be exported from a module.",
                            category = DiagnosticCategory.Error,
                            code = 2661,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = nameNode.pos,
                            length = exportedName.length,
                        ))
                    } else {
                        // The exported name resolves to NOTHING (not a real local, not a global) →
                        // TS2304 "Cannot find name", or TS2552 with a spelling suggestion. Candidates
                        // are this file's real local declarations (type-eligible only for a type-only
                        // export). Conservative: a cross-file/over-merged name lands in `globals` and
                        // is handled above, so this branch only fires for genuinely-undeclared names
                        // (e.g. `export type { RoomInterface }` when the type is `RoomInterfae`).
                        val candidates = LinkedHashSet<String>()
                        for ((symName, sym) in locals) {
                            if (sym.declarations.none { it !is ExportSpecifier }) continue
                            if (specTypeOnly && !sym.flags.hasAny(SymbolFlags.Type)) continue
                            candidates.add(symName)
                        }
                        val suggestion = checker.getSpellingSuggestionFromNames(exportedName, candidates)
                        val (line, character) = checker.getLineAndCharacterOfPosition(source, nameNode.pos)
                        val msg = if (suggestion != null)
                            "Cannot find name '$exportedName'. Did you mean '$suggestion'?"
                        else "Cannot find name '$exportedName'."
                        checker.diagnostics.add(Diagnostic(
                            message = msg,
                            category = DiagnosticCategory.Error,
                            code = if (suggestion != null) 2552 else 2304,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = nameNode.pos,
                            length = exportedName.length,
                        ))
                    }
                }
            }
            for (stmt in result.sourceFile.statements) {
                if (stmt is ModuleDeclaration && stmt.name is Identifier) checkNamespaceClauseLocality(stmt, source, fileName)
            }
            // B98.r56: `export { X }` INSIDE an ambient `declare module "m" { ... }`
            // block where X is NOT declared in the block but IS a real outer/global
            // declaration → TS2661. (X declared inside the block, e.g.
            // `namespace X {} export { X }`, is a legal local export → skipped.)
            for (stmt in result.sourceFile.statements) {
                if (stmt !is ModuleDeclaration) continue
                if (stmt.name !is StringLiteralNode) continue
                val body = stmt.body as? ModuleBlock ?: continue
                val blockLocals = collectModuleBlockLocalNames(body)
                for (s in body.statements) {
                    if (s !is ExportDeclaration) continue
                    if (s.moduleSpecifier != null) continue
                    val named = s.exportClause as? NamedExports ?: continue
                    val stmtTypeOnly = s.isTypeOnly
                    for (spec in named.elements) {
                        if (stmtTypeOnly || spec.isTypeOnly) continue
                        val exportedName = (spec.propertyName ?: spec.name).text
                        if (exportedName in blockLocals) continue
                        val isOuterReal = exportedName in Checker.KNOWN_GLOBALS ||
                            result.locals[exportedName]?.declarations?.any { it !is ExportSpecifier } == true ||
                            checker.globals[exportedName]?.declarations?.any { it !is ExportSpecifier } == true
                        if (!isOuterReal) continue
                        val nameNode = spec.propertyName ?: spec.name
                        val (line, character) = checker.getLineAndCharacterOfPosition(source, nameNode.pos)
                        checker.diagnostics.add(Diagnostic(
                            message = "Cannot export '$exportedName'. Only local declarations can be exported from a module.",
                            category = DiagnosticCategory.Error,
                            code = 2661,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = nameNode.pos,
                            length = exportedName.length,
                        ))
                    }
                }
            }
        }
    }

    /**
     * (CHK.195) TS2661 for a NAMESPACE body's `export { x }` whose `x` resolves to a
     * declaration of a SCRIPT file's top level (tsgo `checkExportSpecifier`:
     * `IsGlobalSourceFile(GetDeclarationContainer(decl))`) — a lib global included. A name
     * declared in a module file, in an enclosing namespace or nowhere is not reported here.
     * Type-only specifiers are skipped, as the module-level arm above skips them.
     */
    private fun checkNamespaceClauseLocality(ns: ModuleDeclaration, source: String, fileName: String) {
        val body = when (val b = ns.body) {
            is ModuleBlock -> b
            is ModuleDeclaration -> { checkNamespaceClauseLocality(b, source, fileName); return }
            else -> return
        }
        for (s in body.statements) {
            if (s is ModuleDeclaration && s.name is Identifier) { checkNamespaceClauseLocality(s, source, fileName); continue }
            if (s !is ExportDeclaration || s.moduleSpecifier != null || s.isTypeOnly) continue
            val named = s.exportClause as? NamedExports ?: continue
            for (spec in named.elements) {
                if (spec.isTypeOnly) continue
                val decl = checker.nameResolver.namespaceClauseTarget(spec, s)?.declarations?.firstOrNull() ?: continue
                val stmtNode: Node? = if (decl is VariableDeclaration) (decl as NodeBase).parent?.let { (it as NodeBase).parent } else decl
                val container = (stmtNode as? NodeBase)?.parent as? SourceFile ?: continue
                if (container.fileName in checker.moduleFiles) continue
                val nameNode = spec.propertyName ?: spec.name
                val (line, character) = checker.getLineAndCharacterOfPosition(source, nameNode.pos)
                checker.diagnostics.add(Diagnostic(
                    message = "Cannot export '${nameNode.text}'. Only local declarations can be exported from a module.",
                    category = DiagnosticCategory.Error, code = 2661, fileName = fileName,
                    line = line, character = character, start = nameNode.pos, length = nameNode.text.length,
                ))
            }
        }
    }

    /** Names declared directly inside an ambient module block via a genuine
     *  declaration (not an ExportSpecifier re-export). Used to distinguish a legal
     *  local `export { X }` from a non-local one (TS2661). */
    private fun collectModuleBlockLocalNames(body: ModuleBlock): Set<String> {
        val names = mutableSetOf<String>()
        for (stmt in body.statements) {
            when (stmt) {
                is VariableStatement -> for (d in stmt.declarationList.declarations) {
                    (d.name as? Identifier)?.text?.let { names.add(it) }
                }
                is FunctionDeclaration -> stmt.name?.text?.let { names.add(it) }
                is ClassDeclaration -> stmt.name?.text?.let { names.add(it) }
                is InterfaceDeclaration -> names.add(stmt.name.text)
                is TypeAliasDeclaration -> names.add(stmt.name.text)
                is EnumDeclaration -> names.add(stmt.name.text)
                is ModuleDeclaration -> (stmt.name as? Identifier)?.text?.let { names.add(it) }
                is ImportEqualsDeclaration -> names.add(stmt.name.text)
                is ImportDeclaration -> {
                    val clause = stmt.importClause
                    clause?.name?.text?.let { names.add(it) }
                    when (val nb = clause?.namedBindings) {
                        is NamespaceImport -> nb.name.text.let { names.add(it) }
                        is NamedImports -> for (e in nb.elements) names.add(e.name.text)
                        else -> {}
                    }
                }
                else -> {}
            }
        }
        return names
    }
}
