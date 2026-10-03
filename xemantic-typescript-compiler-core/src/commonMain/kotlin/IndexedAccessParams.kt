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
 * (CHK.218) M1 — the parameters of a GENERIC signature whose type is an INDEXED ACCESS by one of
 * the signature's own type parameters: mitt's `on<Key extends keyof Events>(type: Key, handler:
 * Handler<Events[Key]>)` and `emit<Key extends keyof Events>(type: Key, event: Events[Key])`.
 *
 * tsgo defers `Events[Key]` to an indexed-access type and re-resolves it once `Key` is inferred
 * from the call (`emit('foo', 1)` checks `1` against `Events["foo"]`). This checker has no deferred
 * indexed-access type — `getTypeFromIndexedAccess` answers `any` for a type-parameter index — so the
 * parameter was `any` and every argument was accepted. This class instantiates such a signature for
 * ONE call by re-resolving the declaration's parameter type nodes with every type parameter BOUND
 * (the outer ones from [Signature.outerBindings], the signature's own from the call's arguments),
 * which yields the concrete types tsgo checks against.
 *
 * Binding follows tsgo `getInferredType` for the shape it handles: a type parameter whose
 * constraint is a union of literal types (what `keyof X` resolves to) keeps the argument's LITERAL
 * type when that relates to the constraint, and falls back to the CONSTRAINT when it does not
 * (`on('*', …)` against `Key extends keyof Events` checks `'*'` against `"bar" | "foo" | …`). The
 * argument positions inferred from are a parameter typed exactly `Key`, and a conditional whose
 * branch is exactly `Key` (tsgo infers to both branches of a conditional target).
 *
 * Everything else answers null and the caller keeps its old behaviour: a type parameter with no
 * such position or a non-literal constraint, a binding or a result that still mentions a type
 * parameter, an unresolvable node, an enclosing type parameter with no binding.
 */
internal class IndexedAccessParams(private val checker: Checker) {

    /**
     * An overload set ([sigs]) as judged for ONE call: each generic candidate of this shape is
     * replaced by its per-call instantiation, and — only when one was — each NON-generic candidate
     * whose parameters name an outer type parameter is re-resolved under [Signature.outerBindings]
     * too. Those are built with the outer parameter still free (`WildcardHandler<Events>` read
     * through `Emitter<Ev>` is `(type: string, event: any) => void`, because `keyof Events` and
     * `Events[keyof Events]` were answered from the constraint before substitution), which was
     * invisible while the generic sibling accepted every call and becomes a false rejection the
     * moment it does not. Answers [sigs] itself when no candidate is of this shape.
     */
    fun instantiateOverloads(sigs: List<Signature>, args: List<Expression>): List<Signature> {
        var any = false
        val out = sigs.map { s -> instantiate(s, args)?.also { any = true } ?: s }
        if (!any) return sigs
        return out.map { s -> if (s.typeParameters.isNullOrEmpty()) reresolveOuter(s) ?: s else s }
    }

    /** [sig] (non-generic) with every parameter re-resolved under its outer bindings, or null. */
    private fun reresolveOuter(sig: Signature): Signature? {
        val outer = sig.outerBindings ?: return null
        val decl = sig.declaration as? MethodDeclaration ?: return null
        if (!decl.typeParameters.isNullOrEmpty()) return null
        val params = decl.parameters.filter { (it.name as? Identifier)?.text != "this" && !it.isCommentPlaceholder }
        if (params.size != sig.parameters.size || params.any { it.dotDotDotToken || it.type == null }) return null
        if (params.none { p -> referencesAny(p.type!!, outer.keys) }) return null
        if (outer.values.any { mentionsTypeParam(it, 0) }) return null
        if (!enclosingTypeParamsBound(decl, outer, params, emptyList())) return null
        return rebuild(sig, decl, params, outer)
    }

    /** A per-call instantiation of [sig], or null when [sig] is not this shape or is undecidable. */
    fun instantiate(sig: Signature, args: List<Expression>): Signature? {
        val tps = sig.typeParameters
        if (tps.isNullOrEmpty()) return null
        val decl = sig.declaration
        val tpNodes: List<TypeParameter>
        val paramNodes: List<Parameter>
        when (decl) {
            is MethodDeclaration -> { tpNodes = decl.typeParameters ?: return null; paramNodes = decl.parameters }
            is FunctionDeclaration -> { tpNodes = decl.typeParameters ?: return null; paramNodes = decl.parameters }
            else -> return null
        }
        if (tpNodes.size != tps.size) return null
        val ownNames = tpNodes.map { it.name.text }.toSet()
        val params = paramNodes.filter { (it.name as? Identifier)?.text != "this" && !it.isCommentPlaceholder }
        if (params.size != sig.parameters.size) return null
        if (params.none { p -> p.type?.let { hasIndexedAccessByOwnTypeParam(it, ownNames) } == true }) return null
        if (params.any { it.dotDotDotToken || it.type == null }) return null
        val outer = sig.outerBindings.orEmpty()
        if (outer.values.any { mentionsTypeParam(it, 0) }) return null
        if (!enclosingTypeParamsBound(decl, outer, params, tpNodes)) return null
        val bindings = HashMap<String, Type>(outer)
        for (tpNode in tpNodes) {
            val name = tpNode.name.text
            val constraintNode = tpNode.constraint ?: return null
            val constraint = resolve(constraintNode, outer) ?: return null
            if (!isLiteralUnion(constraint)) return null
            val argType = inferFromArgs(name, params, args) ?: return null
            bindings[name] = if (checker.isTypeAssignableTo(argType, constraint)) argType else constraint
        }
        return rebuild(sig, decl, params, bindings)
    }

    private fun rebuild(sig: Signature, decl: Node, params: List<Parameter>, bindings: Map<String, Type>): Signature? {
        val newParams = ArrayList<Symbol>(params.size)
        for ((i, p) in params.withIndex()) {
            val old = sig.parameters[i]
            val t = resolve(p.type!!, bindings) ?: return null
            val sym = Symbol(old.flags, old.name)
            sym.declarations.addAll(old.declarations)
            sym.valueDeclaration = old.valueDeclaration
            checker.symbolTypes[sym.id] = t
            newParams.add(sym)
        }
        return Signature(
            declaration = decl,
            typeParameters = null,
            parameters = newParams,
            resolvedReturnType = sig.resolvedReturnType,
            minArgumentCount = sig.minArgumentCount,
            isAbstract = sig.isAbstract,
            thisType = sig.thisType,
        )
    }

    /** [node] resolved with [bindings] as the only type-parameter scope, or null when it does not resolve concretely. */
    private fun resolve(node: TypeNode, bindings: Map<String, Type>): Type? {
        val t = checker.withInstantiationContext(Checker.InstantiationMapper(bindings, null)) {
            checker.getTypeFromTypeNode(node)
        }
        if (t === anyType || t === errorType || t === unresolvedType) return null
        if (mentionsTypeParam(t, 0)) return null
        return t
    }

    /** The argument type bound to the type parameter [name], read from a position typed exactly by it. */
    private fun inferFromArgs(name: String, params: List<Parameter>, args: List<Expression>): Type? {
        for ((i, p) in params.withIndex()) {
            if (i >= args.size) break
            val node = p.type ?: continue
            if (!namesTypeParam(node, name) && !conditionalBranchNames(node, name)) continue
            val arg = args[i]
            if (arg is SpreadElement) return null
            val t = checker.literalTypeOfExpression(arg) ?: checker.getTypeOfExpression(arg)
            if (t === anyType || t === errorType || mentionsTypeParam(t, 0)) return null
            return t
        }
        return null
    }

    private fun namesTypeParam(node: TypeNode, name: String): Boolean {
        val n = if (node is ParenthesizedType) node.type else node
        return n is TypeReference && n.typeArguments.isNullOrEmpty() && (n.typeName as? Identifier)?.text == name
    }

    private fun conditionalBranchNames(node: TypeNode, name: String): Boolean {
        val n = if (node is ParenthesizedType) node.type else node
        return n is ConditionalType && (namesTypeParam(n.trueType, name) || namesTypeParam(n.falseType, name))
    }

    private fun hasIndexedAccessByOwnTypeParam(node: Node, ownNames: Set<String>): Boolean {
        if (node is IndexedAccessType && referencesAny(node.indexType, ownNames)) return true
        var found = false
        forEachChild(node) { if (!found && hasIndexedAccessByOwnTypeParam(it, ownNames)) found = true }
        return found
    }

    private fun referencesAny(node: Node, names: Set<String>): Boolean {
        if (node is TypeReference && (node.typeName as? Identifier)?.text in names) return true
        var found = false
        forEachChild(node) { if (!found && referencesAny(it, names)) found = true }
        return found
    }

    /** Every type parameter of an enclosing declaration that the signature's nodes name has a binding. */
    private fun enclosingTypeParamsBound(
        decl: Node, outer: Map<String, Type>, params: List<Parameter>, tpNodes: List<TypeParameter>,
    ): Boolean {
        val enclosing = HashSet<String>()
        var n = (decl as NodeBase).parent
        while (n != null) {
            val tps = when (n) {
                is ClassDeclaration -> n.typeParameters
                is InterfaceDeclaration -> n.typeParameters
                is TypeAliasDeclaration -> n.typeParameters
                is FunctionDeclaration -> n.typeParameters
                is MethodDeclaration -> n.typeParameters
                is FunctionExpression -> n.typeParameters
                is ArrowFunction -> n.typeParameters
                is ClassExpression -> n.typeParameters
                else -> null
            }
            tps?.forEach { enclosing.add(it.name.text) }
            n = (n as? NodeBase)?.parent
        }
        val own = tpNodes.map { it.name.text }.toSet()
        val unbound = enclosing.filter { it !in outer && it !in own }.toSet()
        if (unbound.isEmpty()) return true
        return params.none { p -> p.type?.let { referencesAny(it, unbound) } == true } &&
            tpNodes.none { t -> t.constraint?.let { referencesAny(it, unbound) } == true }
    }

    private fun isLiteralUnion(t: Type): Boolean = when (t) {
        is Type.StringLiteral, is Type.NumberLiteral -> true
        is Type.Union -> t.types.isNotEmpty() && t.types.all { it is Type.StringLiteral || it is Type.NumberLiteral }
        else -> false
    }

    /** Conservative: true when [t] may mention a type parameter (or is too deep to tell). */
    private fun mentionsTypeParam(t: Type, depth: Int): Boolean {
        if (depth > 6) return true
        return when (t) {
            is Type.TypeParam -> true
            is Type.Union -> t.types.any { mentionsTypeParam(it, depth + 1) }
            is Type.Intersection -> t.types.any { mentionsTypeParam(it, depth + 1) }
            is Type.Reference -> t.resolvedTypeArguments?.any { mentionsTypeParam(it, depth + 1) } == true
            is Type.Interface -> false
            is Type.Object -> {
                t.tupleElementTypes?.let { el -> return el.any { mentionsTypeParam(it, depth + 1) } }
                val sigs = t.callSignatures.orEmpty() + t.constructSignatures.orEmpty()
                sigs.any { s ->
                    !s.typeParameters.isNullOrEmpty() ||
                        s.parameters.any { mentionsTypeParam(checker.getTypeOfSymbol(it), depth + 1) } ||
                        s.resolvedReturnType?.let { mentionsTypeParam(it, depth + 1) } == true
                } || (t.symbol == null && t.members.orEmpty().values.any {
                    mentionsTypeParam(checker.getTypeOfSymbol(it), depth + 1)
                })
            }
            else -> false
        }
    }
}

/** (CHK.218) [Signature.outerBindings] for a member of `target<typeArgs>`: name -> argument. */
internal fun outerBindingsOf(typeParams: List<Type.TypeParam>, typeArgs: List<Type>): Map<String, Type>? {
    if (typeParams.size != typeArgs.size || typeParams.isEmpty()) return null
    val out = HashMap<String, Type>(typeParams.size)
    for ((i, tp) in typeParams.withIndex()) out[tp.symbol?.name ?: return null] = typeArgs[i]
    return out
}
