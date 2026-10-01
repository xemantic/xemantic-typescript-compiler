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
 * (CHK.190) Two diagnostics read off the [ImportExportAliasChain] of a module's
 * import / export alias declarations, measured against tsgo 7.0.2:
 *
 *  - [checkReExportCycles] — TS2303 `Circular definition of import alias 'x'.` at EVERY
 *    alias declaration ON a named cycle (`export { x } from "./b"` beside `export { x }
 *    from "./a"`, renames, import-then-export, a default clause, type-only clauses),
 *    spanning the whole specifier and named by its LOCAL / EXPORTED name. An alias that
 *    only leads into the cycle reports nothing, and neither does its importer.
 *  - [checkTypeOnlyValueUses] — TS1361 / TS1362 at a VALUE use of an import binding
 *    whose alias chain carries a type-only declaration (tsgo's
 *    `onSuccessfullyResolvedSymbol`): the FIRST such declaration names the code (an
 *    export clause -> 1362, an import -> 1361) and carries the related
 *    "'x' was exported here." / "'x' was imported here." note. Only a binding whose
 *    final target HAS a value meaning qualifies — a type alias used as a value is
 *    tsgo's TS2693 instead. A use inside a namespace is not examined (its own
 *    declarations may shadow the import and are bound in no lexical scope) — a
 *    deliberate false negative; so is a name a type-only STAR provides.
 */
internal class ImportExportAliasDiagnostics(
    private val checker: Checker,
    private val chain: ImportExportAliasChain,
    private val lexicalResolver: LexicalScopeResolver,
    /** `Checker.resolveAliasTarget`: the final symbol an alias resolves to, or null. */
    private val resolveAliasTarget: (Symbol) -> Symbol?,
) {

    fun checkReExportCycles(files: List<BinderResult>) {
        for (result in files) {
            val sf = result.sourceFile
            if (checker.isJsLikeFileName(sf.fileName)) continue
            for (st in sf.statements) {
                for (alias in moduleAliasDeclarations(st)) {
                    if (!chain.isOnCycle(alias)) continue
                    val (name, start, end) = aliasSpan(alias, sf.text) ?: continue
                    add(sf.text, sf.fileName, start, end, 2303, "Circular definition of import alias '$name'.")
                }
            }
        }
    }

    /** The import / export alias declarations of one top-level statement. */
    private fun moduleAliasDeclarations(st: Statement): List<Node> = when (st) {
        is ImportDeclaration -> {
            val clause = st.importClause
            if (clause == null) emptyList() else buildList {
                if (clause.name != null) add(clause)
                (clause.namedBindings as? NamedImports)?.elements?.let { addAll(it) }
            }
        }
        is ExportDeclaration -> (st.exportClause as? NamedExports)?.elements ?: emptyList()
        else -> emptyList()
    }

    /** (name, start, end) of the span tsgo reports an alias declaration at. */
    private fun aliasSpan(alias: Node, source: String): Triple<String, Int, Int>? = when (alias) {
        is ImportSpecifier -> Triple(alias.name.text, (alias.propertyName ?: alias.name).pos, idEnd(alias.name))
        is ExportSpecifier -> Triple(alias.name.text, (alias.propertyName ?: alias.name).pos, idEnd(alias.name))
        is ImportClause -> alias.name?.let { Triple(it.text, it.pos, idEnd(it)) }
        else -> null
    }

    fun checkTypeOnlyValueUses(files: List<BinderResult>) {
        for (result in files) {
            val sf = result.sourceFile
            if (checker.isDtsFile(sf.fileName) || checker.isJsLikeFileName(sf.fileName)) continue
            val bindings = typeOnlyBindings(result)
            if (bindings.isEmpty()) continue
            val stack = ArrayList<Node>()
            forEachChild(sf) { stack.add(it) }
            while (stack.isNotEmpty()) {
                val node = stack.removeAt(stack.size - 1)
                if (node is ModuleDeclaration) continue
                if (node is Identifier) {
                    val typeOnly = bindings[node.text]
                    if (typeOnly != null && isValueUse(node) && !isShadowed(node)) emit(sf, node, typeOnly)
                    continue
                }
                forEachChild(node) { stack.add(it) }
            }
        }
    }

    /** Local binding name -> the first type-only declaration on its chain, for every
     *  top-level import binding of [result] that is type-only somewhere AND whose final
     *  target is a value. */
    private fun typeOnlyBindings(result: BinderResult): Map<String, Node> {
        var out: HashMap<String, Node>? = null
        for (st in result.sourceFile.statements) {
            val clause = (st as? ImportDeclaration)?.importClause ?: continue
            val bound = buildList {
                clause.name?.let { add(it.text to (clause as Node)) }
                when (val nb = clause.namedBindings) {
                    is NamedImports -> nb.elements.forEach { add(it.name.text to (it as Node)) }
                    is NamespaceImport -> add(nb.name.text to (nb as Node))
                    else -> {}
                }
            }
            for ((local, binding) in bound) {
                val typeOnly = chain.firstTypeOnly(binding) ?: continue
                if (!isValueTarget(result, local, binding, typeOnly)) continue
                (out ?: HashMap<String, Node>().also { out = it })[local] = typeOnly
            }
        }
        return out ?: emptyMap()
    }

    private fun isValueTarget(result: BinderResult, local: String, binding: Node, typeOnly: Node): Boolean {
        // A module namespace object is a value whatever the module declares.
        if (binding is NamespaceImport || typeOnly is NamespaceExport) return true
        var cur: Node? = binding
        var hops = 0
        while (cur != null && hops++ < 64) {
            if (cur is NamespaceExport) return true
            cur = chain.nextHop(cur)
        }
        var target = result.locals[local] ?: return false
        // An alias reached through a LOCAL clause resolves to the declaring file's own
        // import alias first; keep going until a non-alias answers.
        var guard = 0
        while (target.flags.hasAny(SymbolFlags.Alias) && guard++ < 16) {
            val next = resolveAliasTarget(target) ?: return false
            if (next === target) return false
            target = next
        }
        return target.flags.hasAny(SymbolFlags.Value)
    }

    /**
     * tsgo's `!IsValidTypeOnlyAliasUseSite` for an identifier: an EXPRESSION position
     * (or a shorthand property), not inside a type query, not in an `implements` /
     * interface heritage clause. Positions are whitelisted, so an unlisted one is a
     * false negative and never a false positive.
     */
    private fun isValueUse(id: Identifier): Boolean {
        var n: Node = id
        var p = (n as NodeBase).parent
        while (p is PropertyAccessExpression && p.expression === n) {
            n = p
            p = (p as NodeBase).parent
        }
        return when (p) {
            is ExpressionWithTypeArguments -> {
                val hc = (p as NodeBase).parent as? HeritageClause ?: return false
                p.expression === n && hc.token == SyntaxKind.ExtendsKeyword &&
                    (hc as NodeBase).parent.let { it is ClassDeclaration || it is ClassExpression }
            }
            is CallExpression -> p.expression === n || p.arguments.any { it === n }
            is NewExpression -> p.expression === n || p.arguments?.any { it === n } == true
            is ElementAccessExpression -> p.expression === n || p.argumentExpression === n
            is BinaryExpression -> p.right === n || (p.left === n && !isAssignmentOperator(p.operator))
            is PrefixUnaryExpression -> p.operand === n &&
                p.operator != SyntaxKind.PlusPlus && p.operator != SyntaxKind.MinusMinus
            is ParenthesizedExpression -> p.expression === n
            is ConditionalExpression -> p.condition === n || p.whenTrue === n || p.whenFalse === n
            is TemplateSpan -> p.expression === n
            is SpreadElement -> p.expression === n
            is SpreadAssignment -> p.expression === n
            is ArrayLiteralExpression -> p.elements.any { it === n }
            is ReturnStatement -> p.expression === n
            is ThrowStatement -> p.expression === n
            is ExpressionStatement -> p.expression === n
            is IfStatement -> p.expression === n
            is WhileStatement -> p.expression === n
            is SwitchStatement -> p.expression === n
            is CaseClause -> p.expression === n
            is VariableDeclaration -> p.initializer === n
            is Parameter -> p.initializer === n
            is PropertyAssignment -> p.initializer === n
            is ShorthandPropertyAssignment -> p.name === n
            is TypeOfExpression -> p.expression === n
            is VoidExpression -> p.expression === n
            is AwaitExpression -> p.expression === n
            is AsExpression -> p.expression === n
            is SatisfiesExpression -> p.expression === n
            is NonNullExpression -> p.expression === n
            is ArrowFunction -> p.body === n
            is Decorator -> p.expression === n
            else -> false
        }
    }

    /** A VALUE-space binding of the name between [id] and the file's top level (a
     *  parameter, a body-local, a nested function) shadows the import. */
    private fun isShadowed(id: Identifier): Boolean {
        val scopes = lexicalResolver.scopesOfOwningFile(id) ?: return false
        return lexicalResolver.symbolAt(id, id.text, scopes, flags = SymbolFlags.Value, hopCap = 0) != null
    }

    private fun emit(sf: SourceFile, id: Identifier, typeOnly: Node) {
        val isExport = typeOnly is ExportSpecifier || typeOnly is NamespaceExport
        val name = id.text
        val related = relatedSpan(typeOnly)?.let { (file, start, end) ->
            val (line, ch) = checker.getLineAndCharacterOfPosition(file.text, start)
            Diagnostic(
                message = if (isExport) "'$name' was exported here." else "'$name' was imported here.",
                category = DiagnosticCategory.Message, code = if (isExport) 1377 else 1376,
                fileName = file.fileName, line = line, character = ch,
                start = start, length = (end - start).coerceAtLeast(1),
            )
        }
        val message = if (isExport) "'$name' cannot be used as a value because it was exported using 'export type'."
        else "'$name' cannot be used as a value because it was imported using 'import type'."
        add(sf.text, sf.fileName, id.pos, idEnd(id), if (isExport) 1362 else 1361, message, listOfNotNull(related))
    }

    /** The span tsgo's related note covers: a specifier whole, an import clause from its
     *  `type` keyword to its last token, a namespace import's name, `* as M`. */
    private fun relatedSpan(typeOnly: Node): Triple<SourceFile, Int, Int>? {
        val file = chain.statementOf(typeOnly)?.let { (it as NodeBase).parent as? SourceFile } ?: return null
        val text = file.text
        return when (typeOnly) {
            is ImportSpecifier -> Triple(file, (typeOnly.propertyName ?: typeOnly.name).pos, idEnd(typeOnly.name))
            is ExportSpecifier -> Triple(file, (typeOnly.propertyName ?: typeOnly.name).pos, idEnd(typeOnly.name))
            is NamespaceImport -> Triple(file, typeOnly.name.pos, idEnd(typeOnly.name))
            is NamespaceExport -> {
                val star = text.lastIndexOf('*', typeOnly.name.pos)
                Triple(file, if (star >= 0) star else typeOnly.name.pos, idEnd(typeOnly.name))
            }
            is ImportClause -> {
                // The clause node starts at its first binding; tsgo's span starts at `type`.
                val first = typeOnly.name?.pos ?: typeOnly.namedBindings?.pos ?: typeOnly.pos
                val stmtPos = chain.statementOf(typeOnly)?.pos ?: first
                val start = text.indexOf("type", stmtPos).takeIf { it in stmtPos until first } ?: first
                val end = when (val nb = typeOnly.namedBindings) {
                    is NamedImports -> {
                        val last = nb.elements.lastOrNull()?.let { idEnd(it.name) } ?: nb.pos
                        text.indexOf('}', last).let { if (it >= 0) it + 1 else last }
                    }
                    is NamespaceImport -> idEnd(nb.name)
                    else -> typeOnly.name?.let { idEnd(it) } ?: typeOnly.pos
                }
                Triple(file, start, end)
            }
            else -> null
        }
    }

    private fun add(
        source: String, fileName: String, start: Int, end: Int, code: Int, message: String,
        related: List<Diagnostic> = emptyList(),
    ) {
        val (line, ch) = checker.getLineAndCharacterOfPosition(source, start)
        checker.diagnostics.add(Diagnostic(
            message = message, category = DiagnosticCategory.Error, code = code,
            fileName = fileName, line = line, character = ch,
            start = start, length = (end - start).coerceAtLeast(1),
            relatedInformation = related,
        ))
    }

    private fun idEnd(id: Identifier): Int = id.pos + (id.rawText?.length ?: id.text.length)
}
