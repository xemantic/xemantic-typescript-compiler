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
 * (CHK.195) Does a NAMESPACE body's `export { … }` clause give the namespace a VALUE?
 *
 * tsgo's `getModuleInstanceStateWorker` (`ast/utilities.go`) answers a local clause by
 * looking each specifier's property name up SYNTACTICALLY — the statements of every
 * enclosing `Block` / `ModuleBlock` / `SourceFile`, innermost first — and taking the
 * instance state of the declaration found: an interface or type alias is not a value, a
 * namespace is a value only when instantiated, anything else (and a name declared nowhere,
 * e.g. an import, whose statement has no `Name()`) is. A clause that names only types
 * leaves a `declare namespace N { export { T } }` without a value meaning, so `N.x` is
 * still TS2708; one that names a value is TS2708-free (`declare const x: number;
 * declare namespace N { export { x } }` — `N.x` is `number` in tsgo).
 *
 * Used by the TS2708 namespace-only surveys, whose own value test is syntactic too; an
 * exported `import =` alias is answered here as well.
 */
internal fun namespaceExportClauseCarriesValue(stmt: Statement): Boolean = when (stmt) {
    is ExportDeclaration -> namespaceExportClauseCarriesValueVisiting(stmt, HashSet())
    // `export import O2 = O` — instantiated in tsgo whatever `O` is (`utilities.go`'s
    // ImportEquals arm), and as invisible to the syntactic surveys as a clause was.
    is ImportEqualsDeclaration -> ModifierFlag.Export in stmt.modifiers
    else -> false
}

/**
 * (CHK.195) Does an AMBIENT namespace declaration have a value meaning — tsgo's instance
 * state, which ignores `declare` (this binder types every `declare namespace` as
 * `NamespaceModule`)? `declare namespace N { const z: number }` does: `N.nope` is TS2339 in
 * tsgo, not the TS2708 a type-only namespace gets.
 */
internal fun ambientNamespaceHasValue(m: ModuleDeclaration): Boolean =
    // `declare global { … }` is an augmentation, not a namespace named `global`: a bare
    // `global` read is TS2304 and must not grow a TS2339 beside it (tsc's harness profile).
    (m.name as? Identifier)?.text != "global" && moduleState(m, HashSet()) == NsState.INSTANTIATED

private enum class NsState { NON_INSTANTIATED, INSTANTIATED }

private fun clauseNameState(from: Node, name: String, visiting: MutableSet<Node>): NsState {
    var cur: Node? = (from as NodeBase).parent
    var hops = 0
    while (cur != null && hops++ < 4096) {
        val statements = when (cur) {
            is ModuleBlock -> cur.statements
            is Block -> cur.statements
            is SourceFile -> cur.statements
            else -> null
        }
        if (statements != null) {
            var found = false
            for (s in statements) {
                if (!statementHasName(s, name)) continue
                found = true
                if (s is ImportEqualsDeclaration) return NsState.INSTANTIATED
                if (statementState(s, visiting) == NsState.INSTANTIATED) return NsState.INSTANTIATED
            }
            if (found) return NsState.NON_INSTANTIATED
        }
        if (cur is SourceFile) break
        cur = (cur as NodeBase).parent
    }
    return NsState.INSTANTIATED
}

private fun statementHasName(s: Statement, name: String): Boolean = when (s) {
    is VariableStatement -> s.declarationList.declarations.any { (it.name as? Identifier)?.text == name }
    is FunctionDeclaration -> s.name?.text == name
    is ClassDeclaration -> s.name?.text == name
    is InterfaceDeclaration -> s.name.text == name
    is TypeAliasDeclaration -> s.name.text == name
    is EnumDeclaration -> s.name.text == name
    is ModuleDeclaration -> (s.name as? Identifier)?.text == name
    is ImportEqualsDeclaration -> s.name.text == name
    else -> false
}

private fun statementState(s: Statement, visiting: MutableSet<Node>): NsState = when (s) {
    is InterfaceDeclaration, is TypeAliasDeclaration -> NsState.NON_INSTANTIATED
    is ImportEqualsDeclaration -> if (ModifierFlag.Export in s.modifiers) NsState.INSTANTIATED else NsState.NON_INSTANTIATED
    is ModuleDeclaration -> moduleState(s, visiting)
    else -> NsState.INSTANTIATED
}

private fun moduleState(m: ModuleDeclaration, visiting: MutableSet<Node>): NsState {
    if (!visiting.add(m)) return NsState.NON_INSTANTIATED
    return when (val body = m.body) {
        is ModuleDeclaration -> moduleState(body, visiting)
        is ModuleBlock -> if (body.statements.any { blockStatementState(it, visiting) == NsState.INSTANTIATED })
            NsState.INSTANTIATED else NsState.NON_INSTANTIATED
        else -> NsState.INSTANTIATED
    }
}

private fun blockStatementState(s: Statement, visiting: MutableSet<Node>): NsState = when (s) {
    is InterfaceDeclaration, is TypeAliasDeclaration -> NsState.NON_INSTANTIATED
    is ImportDeclaration -> NsState.NON_INSTANTIATED
    is ImportEqualsDeclaration -> if (ModifierFlag.Export in s.modifiers) NsState.INSTANTIATED else NsState.NON_INSTANTIATED
    is ExportDeclaration -> if (namespaceExportClauseCarriesValueVisiting(s, visiting)) NsState.INSTANTIATED else NsState.NON_INSTANTIATED
    is ModuleDeclaration -> moduleState(s, visiting)
    else -> NsState.INSTANTIATED
}

private fun namespaceExportClauseCarriesValueVisiting(stmt: ExportDeclaration, visiting: MutableSet<Node>): Boolean {
    if (stmt.isTypeOnly) return false
    val clause = stmt.exportClause as? NamedExports ?: return true
    if (stmt.moduleSpecifier != null) return true
    for (spec in clause.elements) {
        if (spec.isTypeOnly) continue
        val name = spec.propertyName?.text ?: spec.name.text
        if (clauseNameState(stmt, name, visiting) != NsState.NON_INSTANTIATED) return true
    }
    return false
}
