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
 * (INV.0) (P18.312) — the type-used-as-namespace family (TS2702 / TS2713 / TS2339 / TS2749): the pass `checkTypeUsedAsNamespaceRefs`
 * with its private walkers and helpers. Extracted VERBATIM from `Checker.kt` (one span: 187473-187715);
 * every Checker member it reads is reached through [checker]. No walk-scoped or spine ambient is read.
 * Ambient reads: `docs/inversion-ambient-ledger.md` row 27.
 */
internal class TypeAsNamespaceChecks(
    private val checker: Checker,
) {

    /**
     * B408: TS2702/TS2713 — a TYPE (class/interface/type-alias) used as a NAMESPACE
     * qualifier in a type position (`Foo.bar` where `Foo` is a type, not a namespace).
     * The file-scoped `checkTypeNameResolved` walker only emits TS2702 for a TOP-LEVEL
     * (global/file-local) leftmost type and never distinguishes TS2713; it cannot see a
     * NAMESPACE-LOCAL type (`namespace N { interface Foo{}; var x: Foo.bar }`) because its
     * leftmost lookup consults only globals/file-locals (the symbol is namespace-local).
     * This dedicated walker resolves each qualified segment through the enclosing namespace
     * chain and applies tsc's `checkAndReportErrorForUsingTypeAsNamespace` rule:
     *   - LEFTMOST segment is the type (`Foo.bar`): TS2713 if `bar` is a property of `Foo`'s
     *     declared type (Did you mean `Foo["bar"]`?), else TS2702.
     *   - A NON-leftmost segment is the type (`N.Foo.bar`, reached via namespace `N`):
     *     always TS2713 at the member.
     * FP-safe by construction: a `Type.member` reference in TYPE position is ALWAYS a tsc
     * error (a valid `A.B` has A a namespace); the type-vs-namespace classification excludes
     * Module/NamespaceModule/ValueModule/Enum (clodules + enums stay legal as qualifiers).
     * The leftmost emission is gated to NAMESPACE-LOCAL leftmosts (resolved via nsChain) so it
     * never double-emits with the existing top-level TS2702 path in checkTypeNameResolved.
     */
    fun checkTypeUsedAsNamespaceRefs() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            if (fileName.endsWith(".js") || fileName.endsWith(".jsx")) continue
            val source = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                scanTypeAsNs(stmt, emptyList(), result, source, fileName)
            }
        }
    }

    private fun scanTypeAsNs(
        stmt: Statement, nsChain: List<Symbol>, result: BinderResult, source: String, fileName: String,
    ) {
        when (stmt) {
            is VariableStatement -> for (d in stmt.declarationList.declarations) {
                typeAsNsTypeNode(d.type, nsChain, result, source, fileName)
            }
            is TypeAliasDeclaration -> typeAsNsTypeNode(stmt.type, nsChain, result, source, fileName)
            is FunctionDeclaration -> {
                for (p in stmt.parameters) typeAsNsTypeNode(p.type, nsChain, result, source, fileName)
                typeAsNsTypeNode(stmt.type, nsChain, result, source, fileName)
                stmt.body?.statements?.forEach { scanTypeAsNs(it, nsChain, result, source, fileName) }
            }
            is ClassDeclaration -> for (m in stmt.members) when (m) {
                is PropertyDeclaration -> typeAsNsTypeNode(m.type, nsChain, result, source, fileName)
                is MethodDeclaration -> {
                    for (p in m.parameters) typeAsNsTypeNode(p.type, nsChain, result, source, fileName)
                    typeAsNsTypeNode(m.type, nsChain, result, source, fileName)
                    m.body?.statements?.forEach { scanTypeAsNs(it, nsChain, result, source, fileName) }
                }
                is Constructor -> {
                    for (p in m.parameters) typeAsNsTypeNode(p.type, nsChain, result, source, fileName)
                    m.body?.statements?.forEach { scanTypeAsNs(it, nsChain, result, source, fileName) }
                }
                is GetAccessor -> typeAsNsTypeNode(m.type, nsChain, result, source, fileName)
                is SetAccessor -> for (p in m.parameters) typeAsNsTypeNode(p.type, nsChain, result, source, fileName)
                else -> {}
            }
            is InterfaceDeclaration -> for (m in stmt.members) when (m) {
                is PropertyDeclaration -> typeAsNsTypeNode(m.type, nsChain, result, source, fileName)
                is MethodDeclaration -> {
                    for (p in m.parameters) typeAsNsTypeNode(p.type, nsChain, result, source, fileName)
                    typeAsNsTypeNode(m.type, nsChain, result, source, fileName)
                }
                else -> {}
            }
            is ImportEqualsDeclaration -> (stmt.moduleReference as? QualifiedName)?.let {
                emitTypeAsNs(it, nsChain, result, source, fileName)
            }
            is ModuleDeclaration -> {
                val nsSym = result.nodeToSymbol[nodeKey(stmt)]
                val childChain = if (nsSym != null) listOf(nsSym) + nsChain else nsChain
                when (val body = stmt.body) {
                    is ModuleBlock -> body.statements.forEach { scanTypeAsNs(it, childChain, result, source, fileName) }
                    is ModuleDeclaration -> scanTypeAsNs(body, childChain, result, source, fileName)
                    else -> {}
                }
            }
            is Block -> stmt.statements.forEach { scanTypeAsNs(it, nsChain, result, source, fileName) }
            else -> {}
        }
    }

    private fun typeAsNsTypeNode(
        t: TypeNode?, nsChain: List<Symbol>, result: BinderResult, source: String, fileName: String,
    ) {
        when (t) {
            null -> {}
            is TypeReference -> {
                (t.typeName as? QualifiedName)?.let { emitTypeAsNs(it, nsChain, result, source, fileName) }
                t.typeArguments?.forEach { typeAsNsTypeNode(it, nsChain, result, source, fileName) }
            }
            is UnionType -> t.types.forEach { typeAsNsTypeNode(it, nsChain, result, source, fileName) }
            is IntersectionType -> t.types.forEach { typeAsNsTypeNode(it, nsChain, result, source, fileName) }
            is ArrayType -> typeAsNsTypeNode(t.elementType, nsChain, result, source, fileName)
            is ParenthesizedType -> typeAsNsTypeNode(t.type, nsChain, result, source, fileName)
            is IndexedAccessType -> {
                // `Enum["Member"]` / `AliasToEnum["Member"]` in TYPE position → TS2339: an
                // enum member NAME is never a property of the enum VALUE type (members live on
                // the `typeof Enum` object side). FP-safe: a member-name index of an enum is
                // always a tsc error; `type C = typeof Enum` aliases are excluded (their body is
                // a TypeQuery, not a TypeReference, so they keep the member properties).
                val objName = ((t.objectType as? TypeReference)?.typeName as? Identifier)?.text
                val idxLit = (t.indexType as? LiteralType)?.literal as? StringLiteralNode
                if (objName != null && idxLit != null) {
                    val enumSym = resolveTypeRefToEnum(objName, nsChain, result)
                    if (enumSym != null && enumSym.exports?.containsKey(idxLit.text) == true) {
                        val (line, ch) = checker.getLineAndCharacterOfPosition(source, idxLit.pos)
                        checker.diagnostics.add(Diagnostic(
                            message = "Property '${idxLit.text}' does not exist on type '${enumSym.name}'.",
                            category = DiagnosticCategory.Error, code = 2339, fileName = fileName,
                            line = line, character = ch, start = idxLit.pos, length = idxLit.text.length + 2,
                        ))
                    }
                }
                typeAsNsTypeNode(t.objectType, nsChain, result, source, fileName)
                typeAsNsTypeNode(t.indexType, nsChain, result, source, fileName)
            }
            else -> {}
        }
    }

    /** Resolve a bare name through the enclosing-namespace chain, then globals/file-locals. */
    private fun lookupTypeAsNsName(name: String, nsChain: List<Symbol>, result: BinderResult): Symbol? {
        for (ns in nsChain) ns.exports?.get(name)?.let { return it }
        return checker.globals[name] ?: result.locals[name]
    }

    /** Resolve a type-name to an enum symbol, following one level of `type X = Enum` alias. */
    private fun resolveTypeRefToEnum(name: String, nsChain: List<Symbol>, result: BinderResult): Symbol? {
        fun lookup(n: String): Symbol? = lookupTypeAsNsName(n, nsChain, result)
        val s = lookup(name) ?: return null
        if (s.declarations.any { it is EnumDeclaration }) return s
        if (s.flags.hasAny(SymbolFlags.TypeAlias)) {
            // Only a plain `type C = Enum` (TypeReference body) aliases the enum value type;
            // `type C = typeof Enum` (TypeQuery body) is the object side and keeps the members.
            val decl = s.declarations.firstOrNull { it is TypeAliasDeclaration } as? TypeAliasDeclaration
            val bn = ((decl?.type as? TypeReference)?.typeName as? Identifier)?.text
            if (bn != null) {
                val t = lookup(bn)
                if (t != null && t.declarations.any { it is EnumDeclaration }) return t
            }
        }
        return null
    }

    private fun isPureTypeSymbol(sym: Symbol): Boolean =
        sym.flags.hasAny(SymbolFlags.Class or SymbolFlags.Interface or SymbolFlags.TypeAlias) &&
            !sym.flags.hasAny(
                SymbolFlags.Module or SymbolFlags.NamespaceModule or SymbolFlags.ValueModule or SymbolFlags.Enum,
            )

    private fun emitTypeAsNs(
        qn: QualifiedName, nsChain: List<Symbol>, result: BinderResult, source: String, fileName: String,
    ) {
        // Flatten the qualified name into left-to-right Identifier segments.
        val segs = ArrayDeque<Identifier>()
        var cur: Node = qn
        while (cur is QualifiedName) { segs.addFirst(cur.right); cur = cur.left }
        (cur as? Identifier)?.let { segs.addFirst(it) } ?: return
        if (segs.size < 2) return
        // Resolve the leftmost segment; track whether it came from an enclosing namespace.
        var sym: Symbol? = null
        var fromNsChain = false
        for (ns in nsChain) { ns.exports?.get(segs[0].text)?.let { sym = it; fromNsChain = true } ?: continue; break }
        if (sym == null) sym = checker.globals[segs[0].text] ?: result.locals[segs[0].text]
        var s = sym ?: return
        // `Enum.Member.X` (3+ segments) in TYPE position → TS2749: `Enum.Member` is a VALUE
        // (the enum member literal), so accessing a further `.X` on it and using the result as a
        // type is a value-used-as-type. `Enum.Member` alone (2 segs) is a legal literal type.
        if (s.declarations.any { it is EnumDeclaration } && segs.size >= 3 &&
            s.exports?.containsKey(segs[1].text) == true
        ) {
            val dotted = segs.joinToString(".") { it.text }
            val start = segs[0].pos
            val end = segs.last().let { it.pos + it.text.length }
            val (line, ch) = checker.getLineAndCharacterOfPosition(source, start)
            checker.diagnostics.add(Diagnostic(
                message = "'$dotted' refers to a value, but is being used as a type here. Did you mean 'typeof $dotted'?",
                category = DiagnosticCategory.Error, code = 2749, fileName = fileName,
                line = line, character = ch, start = start, length = (end - start).coerceAtLeast(1),
            ))
            return
        }
        for (i in 1 until segs.size) {
            if (isPureTypeSymbol(s)) {
                val typeName = segs[i - 1].text
                val member = segs[i].text
                if (i == 1) {
                    // Leftmost is the type — gated to namespace-local leftmosts so we never
                    // double-emit with checkTypeNameResolved's top-level TS2702 path.
                    if (!fromNsChain) return
                    val hasMember: Boolean = run {
                        // `type C = typeof Enum` — the enum's members ARE properties of the object
                        // side (the typeof resolver returns anyType for a namespace-local enum, so
                        // getPropertyOfType would wrongly miss them and emit TS2702 not TS2713).
                        if (s.flags.hasAny(SymbolFlags.TypeAlias)) {
                            val aliasBody = (s.declarations.firstOrNull { it is TypeAliasDeclaration }
                                as? TypeAliasDeclaration)?.type
                            if (aliasBody is TypeQuery) {
                                val en = (aliasBody.exprName as? Identifier)?.text
                                val enumSym = en?.let { lookupTypeAsNsName(it, nsChain, result) }
                                if (enumSym != null && enumSym.declarations.any { it is EnumDeclaration }) {
                                    return@run enumSym.exports?.containsKey(member) == true
                                }
                            }
                        }
                        val declType = checker.getDeclaredTypeOfSymbol(s)
                        if (declType === errorType) return
                        checker.getPropertyOfType(declType, member) != null
                    }
                    if (hasMember) {
                        emitTs2713(segs[0].pos, segs[i].pos + member.length, typeName, member, source, fileName)
                    } else {
                        val (line, ch) = checker.getLineAndCharacterOfPosition(source, segs[i - 1].pos)
                        checker.diagnostics.add(Diagnostic(
                            message = "'$typeName' only refers to a type, but is being used as a namespace here.",
                            category = DiagnosticCategory.Error, code = 2702, fileName = fileName,
                            line = line, character = ch, start = segs[i - 1].pos, length = typeName.length,
                        ))
                    }
                } else {
                    // Non-leftmost type reached via a namespace chain: always TS2713 at the member.
                    emitTs2713(segs[i].pos, segs[i].pos + member.length, typeName, member, source, fileName)
                }
                return
            }
            s = s.exports?.get(segs[i].text) ?: return
        }
    }

    private fun emitTs2713(
        start: Int, end: Int, typeName: String, member: String, source: String, fileName: String,
    ) {
        val (line, ch) = checker.getLineAndCharacterOfPosition(source, start)
        checker.diagnostics.add(Diagnostic(
            message = "Cannot access '$typeName.$member' because '$typeName' is a type, but not a namespace. " +
                "Did you mean to retrieve the type of the property '$member' in '$typeName' with '$typeName[\"$member\"]'?",
            category = DiagnosticCategory.Error, code = 2713, fileName = fileName,
            line = line, character = ch, start = start, length = (end - start).coerceAtLeast(0),
        ))
    }
}
