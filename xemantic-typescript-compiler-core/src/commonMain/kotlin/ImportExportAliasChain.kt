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
 * (CHK.190) The SYNTACTIC hop structure of a module's import / export alias
 * declarations, shared by the two diagnostics that need to walk it declaration by
 * declaration rather than ask for the final symbol:
 *
 *  - TS2303 on a named re-export CYCLE (`a.ts: export { x } from "./b"` /
 *    `b.ts: export { x } from "./a"`) — tsgo reports every alias declaration ON the
 *    cycle, and none that merely leads into it;
 *  - TS1361 / TS1362, which name the FIRST type-only declaration on the chain from
 *    the binding a value use resolves through.
 *
 * An alias node is one of: a top-level [ImportSpecifier], the default name of an
 * [ImportClause], a [NamespaceImport], a top-level [ExportSpecifier] (from-clause or
 * local clause) or a [NamespaceExport] (`export * as M from`). [nextHop] answers the
 * alias declaration the given one resolves THROUGH, or null at a real declaration, a
 * namespace (module) target, an unresolvable specifier, or a name only a STAR
 * provides — stars are deliberately not followed: a star cycle is not a TS2303 in
 * tsgo, and a type-only star (`export type *`) is a residue of (CHK.190).
 *
 * Only MODULE-level clauses are alias nodes here: a namespace's `export { … }` names
 * the namespace's members, and an ambient `declare module "x" { … }` block is not a
 * file this resolves into.
 */
internal class ImportExportAliasChain(
    private val fileResults: Map<String, BinderResult>,
    /** The file a module-level clause's specifier names, from the declaring file —
     *  `NameResolver.clauseModuleTarget`, the ladder every import→export resolver uses. */
    private val resolveClauseModule: (specifier: String, decl: Node, declaringFile: String) -> String?,
) {

    /** The top-level import / export statement declaring [alias], or null when [alias]
     *  is not a module-level alias declaration. */
    fun statementOf(alias: Node): Node? {
        val stmt: Node? = when (alias) {
            is ImportSpecifier -> ((alias as NodeBase).parent as? NodeBase)?.parent?.let { (it as NodeBase).parent }
            is ImportClause -> (alias as NodeBase).parent
            is NamespaceImport -> ((alias as NodeBase).parent as? NodeBase)?.parent
            is ExportSpecifier -> ((alias as NodeBase).parent as? NodeBase)?.parent
            is NamespaceExport -> (alias as NodeBase).parent
            else -> null
        }
        if (stmt !is ImportDeclaration && stmt !is ExportDeclaration) return null
        return stmt.takeIf { (it as NodeBase).parent is SourceFile }
    }

    /** True when [alias] is declared type-only: `import type …`, `import { type x }`,
     *  `export type { … }`, `export { type x }`, `export type * as M`. */
    fun isTypeOnly(alias: Node): Boolean = when (alias) {
        is ImportSpecifier -> alias.isTypeOnly ||
            ((((alias as NodeBase).parent as? NodeBase)?.parent) as? ImportClause)?.isTypeOnly == true
        is ImportClause -> alias.isTypeOnly
        is NamespaceImport -> ((alias as NodeBase).parent as? ImportClause)?.isTypeOnly == true
        is ExportSpecifier -> alias.isTypeOnly ||
            ((((alias as NodeBase).parent as? NodeBase)?.parent) as? ExportDeclaration)?.isTypeOnly == true
        is NamespaceExport -> ((alias as NodeBase).parent as? ExportDeclaration)?.isTypeOnly == true
        else -> false
    }

    /** The alias declaration [alias] resolves through, or null (see the class KDoc). */
    fun nextHop(alias: Node): Node? {
        val stmt = statementOf(alias) ?: return null
        val file = (stmt as NodeBase).parent as SourceFile
        return when (alias) {
            is ImportSpecifier -> target(stmt, file)?.let { exportEntry(it, (alias.propertyName ?: alias.name).text) }
            is ImportClause -> if (alias.name == null) null else target(stmt, file)?.let { exportEntry(it, "default") }
            is ExportSpecifier -> {
                val declared = (alias.propertyName ?: alias.name).text
                if ((stmt as ExportDeclaration).moduleSpecifier == null) importBinding(file, declared)
                else target(stmt, file)?.let { exportEntry(it, declared) }
            }
            else -> null
        }
    }

    private fun target(stmt: Node, file: SourceFile): SourceFile? {
        val spec = when (stmt) {
            is ImportDeclaration -> stmt.moduleSpecifier
            is ExportDeclaration -> stmt.moduleSpecifier
            else -> null
        } as? StringLiteralNode ?: return null
        val targetName = resolveClauseModule(spec.text, stmt, file.fileName) ?: return null
        return fileResults[targetName]?.sourceFile
    }

    /** The module-level clause of [file] exporting [name] under that name — a named
     *  [ExportSpecifier] or a [NamespaceExport] — or null (a declaration, a star, absent). */
    fun exportEntry(file: SourceFile, name: String): Node? {
        for (st in file.statements) {
            if (st !is ExportDeclaration) continue
            when (val clause = st.exportClause) {
                is NamedExports -> clause.elements.firstOrNull { it.name.text == name }?.let { return it }
                is NamespaceExport -> if (clause.name.text == name) return clause
                else -> {}
            }
        }
        return null
    }

    /** The top-level import binding of [file] with local name [localName], or null. */
    fun importBinding(file: SourceFile, localName: String): Node? {
        for (st in file.statements) {
            val clause = (st as? ImportDeclaration)?.importClause ?: continue
            if (clause.name?.text == localName) return clause
            when (val nb = clause.namedBindings) {
                is NamedImports -> nb.elements.firstOrNull { it.name.text == localName }?.let { return it }
                is NamespaceImport -> if (nb.name.text == localName) return nb
                else -> {}
            }
        }
        return null
    }

    /** True when following [nextHop] from [alias] comes back to [alias] itself — i.e.
     *  [alias] is ON a cycle (an alias that only leads into one answers false). */
    fun isOnCycle(alias: Node): Boolean {
        var cur = nextHop(alias)
        var hops = 0
        while (cur != null && hops++ < MAX_HOPS) {
            if (cur === alias) return true
            cur = nextHop(cur)
        }
        return false
    }

    /** The first type-only alias declaration on the chain starting at [binding] (itself
     *  included), or null. */
    fun firstTypeOnly(binding: Node): Node? {
        var cur: Node? = binding
        var hops = 0
        while (cur != null && hops++ < MAX_HOPS) {
            if (isTypeOnly(cur)) return cur
            cur = nextHop(cur)
        }
        return null
    }

    private companion object {
        /** A fail-safe on a pathological chain; a cycle is detected by identity long before. */
        const val MAX_HOPS = 64
    }
}
