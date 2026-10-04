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
 * (LIBS.3) TS2536 "Type 'Idx<V>' cannot be used to index type 'Vals<V>'." for an element access
 * `vals[i]` whose receiver AND index are both declared as a GENERIC CONDITIONAL ALIAS instantiated
 * with one enclosing type parameter `V` — date-fns `buildLocalizeFn` (`LocalizeValues<Value>`
 * indexed by `LocalizeUnitIndex<Value>`), the library's last ours-only row.
 *
 * This checker has no deferred conditional type (a type-parameter check type answers `any`), so
 * the verdict is computed here, from the declarations, by replaying the three routes tsgo's
 * `checkIndexedAccessIndexType` (checker.go:8197) can take to accept `Idx<V>` against `keyof
 * Vals<V>` (relater.go `structuredTypeRelatedToWorker`):
 *
 *  - target side: `S` is assignable to `keyof Vals<V>` when it is assignable to `keyof C`, `C` the
 *    receiver's constraint — for a distributive conditional, `Vals<constraint of V>`, so
 *    `keyof C` is the INTERSECTION of the keys of every branch the constraint reaches (`KC`);
 *  - source side, DEFAULT constraint: every branch of `Idx`'s conditional relates to `keyof
 *    Vals<V>` — `never` and the identical `keyof Vals<V>` do, a concrete branch must be in `KC`;
 *  - source side, DISTRIBUTIVE constraint: `Idx<constraint of V>`, evaluated per constituent of
 *    the constraint, must be in `KC`;
 *  - and the number-index escape: the receiver's apparent type carries a number index (every
 *    branch is an array / tuple / numerically indexed object).
 *
 * TS2536 is reported only when every route PROVABLY fails. Each membership test is three-valued
 * and anything undecided is silent: a false positive on legal code outranks this missing row.
 * The constraint of `V` must be a finite union of literals (so both constraints evaluate
 * concretely), each alias must take that one parameter and be distributive over it, and a key is
 * proven absent only from a tuple (whose `Array` base members resolve) or an anonymous object type.
 * Measured against tsgo 7.0.2 in `build/bench/p18291-agent/m`.
 */
internal class DeferredConditionalIndexAccess(private val checker: Checker) {

    fun check(expr: ElementAccessExpression, arg: Expression, source: String, fileName: String) {
        val receiver = checker.unwrapParensExpr(expr.expression) as? Identifier ?: return
        if (arg !is Identifier) return
        val indexRef = declaredTypeNode(arg) as? TypeReference ?: return
        val receiverRef = declaredTypeNode(receiver) as? TypeReference ?: return
        val index = conditionalAlias(indexRef) ?: return
        val recv = conditionalAlias(receiverRef) ?: return
        if (index.argName != recv.argName) return
        val tp = typeParameterDeclaration(expr, index.argName) ?: return
        val constraintNode = tp.constraint ?: return
        val literals = literalConstituents(checker.getTypeFromTypeNode(constraintNode)) ?: return
        // The receiver's constraint, evaluated per constituent (distribution).
        val branches = ArrayList<Type>()
        for (c in literals) {
            val t = checker.typeOfNodeBinding(receiverRef, mapOf(recv.argName to c))
            flatten(t).forEach { if (it !== neverType) branches.add(it) }
        }
        if (branches.isEmpty() || branches.any { it === anyType || it === errorType || it === unknownType }) return
        // The number-index escape must provably be closed.
        if (!branches.any { lacksNumberIndex(it) == true }) return
        if (defaultConstraintRoute(index, recv, branches) != false) return
        if (distributiveRoute(indexRef, index.argName, literals, branches) != false) return
        report(expr, arg, source, fileName, display(indexRef), display(receiverRef))
    }

    private fun report(expr: ElementAccessExpression, arg: Expression, source: String, fileName: String, i: String, o: String) =
        GenericIndexAccess.report(checker, expr, arg, source, fileName, i, o)

    /** A reference `Alias<P>` to a one-parameter alias whose body is a conditional distributive over it. */
    private class ConditionalAlias(val decl: TypeAliasDeclaration, val body: ConditionalType, val paramName: String, val argName: String)

    private fun conditionalAlias(ref: TypeReference): ConditionalAlias? {
        val name = ref.typeName as? Identifier ?: return null
        val arg = ref.typeArguments?.singleOrNull() as? TypeReference ?: return null
        if (arg.typeArguments != null) return null
        val argName = (arg.typeName as? Identifier)?.text ?: return null
        var sym = checker.resolveTypeNameToSymbol(name) ?: return null
        if (sym.flags.hasAny(SymbolFlags.Alias)) sym = checker.resolveAlias(sym)
        val decl = sym.declarations.filterIsInstance<TypeAliasDeclaration>().singleOrNull() ?: return null
        val param = decl.typeParameters?.singleOrNull() ?: return null
        val body = checker.intersectionOps.unparenthesized(decl.type) as? ConditionalType ?: return null
        val paramName = param.name.text
        if (nakedName(body.checkType) != paramName) return null
        if (containsInfer(body.extendsType)) return null
        // getSimplifiedConditionalType: `P extends X ? P : never` and its mirror simplify away.
        if (nakedName(body.trueType) == paramName || nakedName(body.falseType) == paramName) return null
        return ConditionalAlias(decl, body, paramName, argName)
    }

    /** tsgo's default-constraint route: every branch of the index conditional relates to `keyof Vals<V>`. */
    private fun defaultConstraintRoute(index: ConditionalAlias, recv: ConditionalAlias, kc: List<Type>): Boolean? {
        var verdict: Boolean? = true
        for (branch in listOf(index.body.trueType, index.body.falseType)) {
            val b = checker.intersectionOps.unparenthesized(branch)
            val ok: Boolean? = when {
                b is KeywordTypeNode && b.kind == SyntaxKind.NeverKeyword -> true
                isKeyofOfReceiver(b, index.paramName, recv) -> true
                mentions(b, index.paramName) -> null
                else -> {
                    val t = checker.getTypeFromTypeNode(b)
                    if (t === anyType || t === errorType) null else allInKc(flatten(t), kc)
                }
            }
            when (ok) {
                false -> return false
                null -> verdict = null
                true -> {}
            }
        }
        return verdict
    }

    /** tsgo's distributive-constraint route: `Idx<constraint of V>` must be in `KC`. */
    private fun distributiveRoute(indexRef: TypeReference, argName: String, literals: List<Type>, kc: List<Type>): Boolean? {
        val keys = ArrayList<Type>()
        for (c in literals) flatten(checker.typeOfNodeBinding(indexRef, mapOf(argName to c))).forEach { keys.add(it) }
        val live = keys.filter { it !== neverType }
        // An all-`never` instantiation is no constraint at all: the route does not exist.
        if (live.isEmpty()) return false
        return allInKc(live, kc)
    }

    /** `keyof Alias<P>` naming the receiver's alias with the index alias's own parameter. */
    private fun isKeyofOfReceiver(b: TypeNode, paramName: String, recv: ConditionalAlias): Boolean {
        if (b !is TypeOperator || b.operator != SyntaxKind.KeyOfKeyword) return false
        val ref = checker.intersectionOps.unparenthesized(b.type) as? TypeReference ?: return false
        val name = ref.typeName as? Identifier ?: return false
        val arg = ref.typeArguments?.singleOrNull() as? TypeReference ?: return false
        if (arg.typeArguments != null || (arg.typeName as? Identifier)?.text != paramName) return false
        var sym = checker.resolveTypeNameToSymbol(name) ?: return false
        if (sym.flags.hasAny(SymbolFlags.Alias)) sym = checker.resolveAlias(sym)
        return sym.declarations.any { it === recv.decl }
    }

    /** Every key in [keys] is in `KC` (true), some literal key provably is not (false), else null. */
    private fun allInKc(keys: List<Type>, kc: List<Type>): Boolean? {
        var verdict: Boolean? = true
        for (k in keys) {
            if (k === neverType) continue
            var inAll: Boolean? = true
            for (t in kc) {
                when (hasKey(t, k)) {
                    false -> { inAll = false; break }
                    null -> inAll = null
                    true -> {}
                }
            }
            when (inAll) {
                false -> return false
                null -> verdict = null
                true -> {}
            }
        }
        return verdict
    }

    /** Whether [key] is in `keyof t` — true, provably not (false), or undecided (null). */
    private fun hasKey(t: Type, key: Type): Boolean? {
        val o = trustedObject(t) ?: return null
        if (o.tupleElementTypes != null) {
            return when {
                key is Type.NumberLiteral || key === numberType -> true
                key is Type.StringLiteral -> when {
                    isNumericLike(key.value) -> null
                    // `length` and the methods are read through the tuple's `Array` /
                    // `ReadonlyArray` base ([trustedObject] proved it resolves).
                    else -> checker.tupleArrayBaseOf(o)?.let { checker.getPropertyOfType(it, key.value) != null }
                }
                key === stringType -> false
                else -> null
            }
        }
        val members = o.members ?: return null
        val si = o.stringIndexInfo != null
        val ni = o.numberIndexInfo != null
        return when {
            key is Type.StringLiteral -> when {
                members[key.value] != null || si -> true
                ni && isNumericLike(key.value) -> null
                else -> false
            }
            key is Type.NumberLiteral -> if (members[key.toString()] != null || si || ni) true else false
            key === numberType -> si || ni
            key === stringType -> si
            else -> null
        }
    }

    /** True when the type provably has no number index info (tsgo's union index infos need it in every constituent). */
    private fun lacksNumberIndex(t: Type): Boolean? {
        val o = trustedObject(t) ?: return null
        if (o.tupleElementTypes != null) return false
        return o.numberIndexInfo == null && o.stringIndexInfo == null
    }

    /**
     * A type whose key set this class may reason about: a fixed tuple whose `Array` base resolves
     * (the positive control: `length` and `slice` are found on it), or an anonymous object type with no
     * signatures. A named interface, a class or a generic reference is never trusted.
     */
    private fun trustedObject(t: Type): Type.Object? {
        if (t !is Type.Object || t is Type.Interface || t is Type.Reference || t.jsLiteral) return null
        checker.resolveStructuredTypeMembers(t)
        if (t.tupleElementTypes != null) {
            if (t.tupleHasRest) return null
            val base = checker.tupleArrayBaseOf(t) ?: return null
            if (checker.getPropertyOfType(base, "length") == null || checker.getPropertyOfType(base, "slice") == null) return null
            return t
        }
        if (!t.callSignatures.isNullOrEmpty() || !t.constructSignatures.isNullOrEmpty()) return null
        return t
    }

    private fun isNumericLike(s: String) = s.isNotEmpty() && (s.toDoubleOrNull() != null || s == "Infinity" || s == "NaN")

    private fun flatten(t: Type): List<Type> = if (t is Type.Union) t.types.flatMap { flatten(it) } else listOf(t)

    /** The literal constituents of a finite constraint (at most 64), or null. */
    private fun literalConstituents(t: Type): List<Type>? {
        val parts = flatten(t)
        if (parts.isEmpty() || parts.size > 64) return null
        return parts.takeIf { all -> all.all { it is Type.StringLiteral || it is Type.NumberLiteral } }
    }

    private fun display(ref: TypeReference): String =
        "${(ref.typeName as Identifier).text}<${((ref.typeArguments!!.single() as TypeReference).typeName as Identifier).text}>"

    private fun nakedName(node: TypeNode): String? {
        val n = checker.intersectionOps.unparenthesized(node) as? TypeReference ?: return null
        if (n.typeArguments != null) return null
        return (n.typeName as? Identifier)?.text
    }

    private fun mentions(node: Node, name: String): Boolean {
        if (node is TypeReference && (node.typeName as? Identifier)?.text == name) return true
        if (node is TypeQuery) return true
        var found = false
        forEachChild(node) { if (!found && mentions(it, name)) found = true }
        return found
    }

    private fun containsInfer(node: Node): Boolean {
        if (node is InferType) return true
        var found = false
        forEachChild(node) { if (!found && containsInfer(it)) found = true }
        return found
    }

    /**
     * The type node the innermost binding of [ref] was declared with — a parameter's or a variable's
     * annotation, or the type of an `as` / `<T>` assertion initializing an unannotated variable — or
     * null for any binding this cannot read (a function, class, import, destructuring, a loop or
     * catch variable), which makes the whole check silent.
     */
    private fun declaredTypeNode(ref: Identifier): TypeNode? {
        val name = ref.text
        var cur: Node? = ref.parent
        while (cur != null) {
            when (cur) {
                is Block -> scan(cur.statements, name)?.let { return it.node }
                is ModuleBlock -> scan(cur.statements, name)?.let { return it.node }
                is CaseClause -> scan(cur.statements, name)?.let { return it.node }
                is DefaultClause -> scan(cur.statements, name)?.let { return it.node }
                is SourceFile -> return scan(cur.statements, name)?.node
                is FunctionDeclaration -> param(cur.parameters, name)?.let { return it.node }
                is FunctionExpression -> {
                    if (cur.name?.text == name) return null
                    param(cur.parameters, name)?.let { return it.node }
                }
                is ArrowFunction -> param(cur.parameters, name)?.let { return it.node }
                is MethodDeclaration -> param(cur.parameters, name)?.let { return it.node }
                is Constructor -> param(cur.parameters, name)?.let { return it.node }
                is GetAccessor -> param(cur.parameters, name)?.let { return it.node }
                is SetAccessor -> param(cur.parameters, name)?.let { return it.node }
                is ForStatement -> if (declaresName(cur.initializer, name)) return null
                is ForInStatement -> if (declaresName(cur.initializer, name)) return null
                is ForOfStatement -> if (declaresName(cur.initializer, name)) return null
                is CatchClause -> if ((cur.variableDeclaration?.name as? Identifier)?.text == name ||
                    (cur.variableDeclaration != null && cur.variableDeclaration.name !is Identifier)) return null
                else -> {}
            }
            cur = (cur as? NodeBase)?.parent
        }
        return null
    }

    /** A binding found in a scope: [node] is its declared type node, or null when unreadable. */
    private class Found(val node: TypeNode?)

    private fun param(params: List<Parameter>, name: String): Found? {
        for (p in params) {
            val n = p.name
            if (n is Identifier) { if (n.text == name) return Found(if (p.dotDotDotToken) null else p.type) }
            else return Found(null) // a destructured parameter may bind the name
        }
        return null
    }

    private fun scan(statements: List<Statement>, name: String): Found? {
        for (s in statements) {
            when (s) {
                is VariableStatement -> for (d in s.declarationList.declarations) {
                    val n = d.name
                    if (n !is Identifier) return Found(null)
                    if (n.text == name) return Found(d.type ?: assertedType(d.initializer))
                }
                is FunctionDeclaration -> if (s.name?.text == name) return Found(null)
                is ClassDeclaration -> if (s.name?.text == name) return Found(null)
                is EnumDeclaration -> if (s.name.text == name) return Found(null)
                is ModuleDeclaration, is ImportDeclaration, is ImportEqualsDeclaration -> return Found(null)
                else -> {}
            }
        }
        return null
    }

    private fun assertedType(init: Expression?): TypeNode? = when (val e = init?.let { checker.unwrapParensExpr(it) }) {
        is AsExpression -> e.type
        is TypeAssertionExpression -> e.type
        else -> null
    }

    private fun declaresName(init: Node?, name: String): Boolean =
        init is VariableDeclarationList && init.declarations.any { (it.name as? Identifier)?.text == name || it.name !is Identifier }

    /** The innermost enclosing declaration of a type parameter named [name], or null. */
    private fun typeParameterDeclaration(from: Node, name: String): TypeParameter? {
        var node: Node? = (from as? NodeBase)?.parent
        while (node != null) {
            val list = when (node) {
                is FunctionDeclaration -> node.typeParameters
                is FunctionExpression -> node.typeParameters
                is ArrowFunction -> node.typeParameters
                is MethodDeclaration -> node.typeParameters
                is ClassDeclaration -> node.typeParameters
                is ClassExpression -> node.typeParameters
                else -> null
            }
            list?.firstOrNull { it.name.text == name }?.let { return it }
            node = (node as? NodeBase)?.parent
        }
        return null
    }
}
