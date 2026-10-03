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
 * (LIBS.2) GSIG — a conditional type whose check and extends types are both GENERIC FUNCTION TYPES
 * returning a conditional over their own type parameter: the `IsEqual` idiom of every type-test
 * library, `(<G>() => G extends A ? 1 : 2) extends (<G>() => G extends B ? 1 : 2) ? true : false`.
 *
 * This checker has no deferred conditional type: `G extends A ? 1 : 2` with `G` free resolves to `any`
 * ([Checker.getTypeFromConditionalType]'s type-parameter bail), so both signatures read
 * `<G>() => any` and the outer conditional was TRUE for every pair. tsgo decides it by unifying the
 * two signatures' type parameters (`instantiateSignatureInContextOf`) and relating the two deferred
 * return conditionals in `structuredTypeRelatedToWorker` (relater.go): source-vs-target conditional
 * relates when the extends types are IDENTICAL, the check types relate and the branches relate; failing
 * that, the source's default constraint (`trueType | falseType`) is related to the target conditional,
 * which skips a branch only when it is provably always / never taken.
 *
 * [decide] answers that verdict, or null for every other shape (the caller keeps its old evaluation).
 * Identity is tsgo's `isTypeIdenticalTo` over the shapes modelled here; an object shape it cannot
 * decide (call signatures, two different generic targets) falls back to MUTUAL ASSIGNABILITY, which
 * leans toward "identical" — i.e. toward the old answer.
 */
internal class GenericSignatureConditionals(private val checker: Checker) {

    /** One shared type parameter per position per nesting depth — the unified `G`. */
    private val markers = ArrayList<List<Type.TypeParam>>()
    private var depth = 0
    /** Set by [identical] on a pair it cannot decide here: a free type parameter (a generic alias body
     *  evaluated before instantiation), or `any` against a structured type — which in this checker is
     *  as often a type it failed to resolve (`Record<string, number>` reads `any`) as a written `any`. */
    private var undecided = false

    fun decide(node: ConditionalType): Boolean? {
        val s = checker.intersectionOps.unparenthesized(node.checkType) as? FunctionType ?: return null
        val t = checker.intersectionOps.unparenthesized(node.extendsType) as? FunctionType ?: return null
        val sTps = s.typeParameters ?: return null
        val tTps = t.typeParameters ?: return null
        if (sTps.isEmpty() || sTps.size != tTps.size) return null
        if (s.parameters.isNotEmpty() || t.parameters.isNotEmpty()) return null
        val sRet = checker.intersectionOps.unparenthesized(s.type) as? ConditionalType ?: return null
        val tRet = checker.intersectionOps.unparenthesized(t.type) as? ConditionalType ?: return null
        val sNames = sTps.map { it.name.text }
        val tNames = tTps.map { it.name.text }
        val i = sNames.indexOf(nakedName(sRet.checkType) ?: return null)
        if (i < 0 || tNames.indexOf(nakedName(tRet.checkType)) != i) return null
        if (hasInfer(sRet.extendsType) || hasInfer(tRet.extendsType)) return null
        if (depth >= MAX_DEPTH) return null
        while (markers.size <= depth) markers.add(List(MAX_PARAMS) { Type.TypeParam() })
        if (sTps.size > MAX_PARAMS) return null
        val pool = markers[depth]
        depth++
        try {
            val sMap = sNames.indices.associate { sNames[it] to (pool[it] as Type) }
            val tMap = tNames.indices.associate { tNames[it] to (pool[it] as Type) }
            val g = pool[i]
            val sExt = checker.typeOfNodeBinding(sRet.extendsType, sMap)
            val tExt = checker.typeOfNodeBinding(tRet.extendsType, tMap)
            val sTrue = checker.typeOfNodeBinding(sRet.trueType, sMap)
            val sFalse = checker.typeOfNodeBinding(sRet.falseType, sMap)
            val tTrue = checker.typeOfNodeBinding(tRet.trueType, tMap)
            val tFalse = checker.typeOfNodeBinding(tRet.falseType, tMap)
            if (listOf(sExt, tExt, sTrue, sFalse, tTrue, tFalse).any { it === errorType }) return null
            // The unified parameters must carry identical constraints (tsgo's signatures otherwise do
            // not unify); the restrictive / permissive instantiations below ignore them, as tsgo's do.
            undecided = false
            for (k in sTps.indices) {
                val sc = sTps[k].constraint
                val tc = tTps[k].constraint
                if ((sc == null) != (tc == null)) return null
                if (sc != null && tc != null && !identical(checker.typeOfNodeBinding(sc, sMap), checker.typeOfNodeBinding(tc, tMap), 0)) return null
            }
            if (undecided) return null
            // Two conditionals: identical extends types, related check types (both the unified `G`).
            undecided = false
            val same = identical(sExt, tExt, 0)
            if (undecided) return null
            if (same && related(sTrue, tTrue) && related(sFalse, tFalse)) return true
            // The source's default constraint against the target conditional. A target whose branches
            // mention its check type is distribution-dependent and never relates this way.
            if (mentions(tRet.trueType, tNames[i]) || mentions(tRet.falseType, tNames[i])) return false
            val sDefault = checker.getUnionType(listOf(sTrue, sFalse))
            // permissive `G` is the wildcard: assignable to everything but `never`.
            val skipTrue = tExt === neverType
            // restrictive `G` is an unconstrained parameter: assignable to `unknown`, `any`, itself,
            // or a union holding one of those.
            val skipFalse = !skipTrue && restrictiveAssignable(g, tExt)
            return (skipTrue || related(sDefault, tTrue)) && (skipFalse || related(sDefault, tFalse))
        } finally {
            depth--
        }
    }

    private fun restrictiveAssignable(g: Type, ext: Type): Boolean = when {
        ext === g || isTop(ext) -> true
        ext is Type.Union -> ext.types.any { it === g || isTop(it) }
        else -> false
    }

    private fun isTop(t: Type) = t === unknownType || t === anyType

    private fun related(a: Type, b: Type) = a === b || checker.isTypeAssignableTo(a, b)

    /** tsgo's `isTypeIdenticalTo`, for the shapes this checker models. */
    fun identical(a: Type, b: Type, level: Int): Boolean {
        if (a === b) return true
        if (level > MAX_IDENTITY_DEPTH) return true
        val markerSet = markers.take(depth).flatten()
        if (a is Type.TypeParam || b is Type.TypeParam) {
            if ((a is Type.TypeParam && !markerSet.contains(a)) || (b is Type.TypeParam && !markerSet.contains(b)))
                undecided = true
            return false
        }
        if ((a === anyType && isStructured(b)) || (b === anyType && isStructured(a))) {
            undecided = true
            return false
        }
        return when {
            isBoolean(a) && isBoolean(b) -> true
            a is Type.Intrinsic || b is Type.Intrinsic -> a is Type.Intrinsic && b is Type.Intrinsic &&
                a.flags == b.flags && a.intrinsicName == b.intrinsicName && !(isError(a) xor isError(b))
            a is Type.StringLiteral && b is Type.StringLiteral -> a.value == b.value
            a is Type.NumberLiteral && b is Type.NumberLiteral -> a.value == b.value
            a is Type.BigIntLiteral && b is Type.BigIntLiteral -> a.value == b.value
            a is Type.Union || b is Type.Union || hasUnionMember(a) || hasUnionMember(b) -> {
                // tsgo's unions are normalized (`(X | Y) & G` is `X & G | Y & G`, duplicates folded),
                // this checker's need not be — compare the DISJUNCTS, each a list of conjuncts.
                val ad = disjuncts(a) ?: return true
                val bd = disjuncts(b) ?: return true
                ad.all { x -> bd.any { y -> conjunctsIdentical(x, y, level) } } &&
                    bd.all { y -> ad.any { x -> conjunctsIdentical(x, y, level) } }
            }
            a is Type.Intersection || b is Type.Intersection -> conjunctsIdentical(conjuncts(a), conjuncts(b), level)
            a is Type.Object && b is Type.Object -> objectsIdentical(a, b, level + 1)
            else -> false
        }
    }

    private fun isBoolean(t: Type): Boolean = t === booleanType ||
        (t is Type.Union && t.types.size == 2 && t.types.all { it is Type.Intrinsic && it.flags == TypeFlags.BooleanLiteral } &&
            t.types.map { (it as Type.Intrinsic).intrinsicName }.toSet() == setOf("true", "false"))

    private fun isStructured(t: Type): Boolean = when (t) {
        is Type.Object -> true
        is Type.Union -> t.types.any { isStructured(it) }
        is Type.Intersection -> t.types.any { isStructured(it) }
        else -> false
    }

    private fun isError(t: Type) = t === errorType || t === unresolvedType

    private fun hasUnionMember(t: Type) = t is Type.Intersection && t.types.any { it is Type.Union }

    private fun conjuncts(t: Type): List<Type> = if (t is Type.Intersection) t.types.flatMap { conjuncts(it) } else listOf(t)

    /** [t] as a union of intersections, or null past [MAX_DISJUNCTS]. */
    private fun disjuncts(t: Type): List<List<Type>>? = when (t) {
        is Type.Union -> t.types.flatMap { disjuncts(it) ?: return null }
        is Type.Intersection -> {
            var acc: List<List<Type>> = listOf(emptyList())
            for (m in t.types) {
                val ds = disjuncts(m) ?: return null
                acc = acc.flatMap { prefix -> ds.map { prefix + it } }
                if (acc.size > MAX_DISJUNCTS) return null
            }
            acc
        }
        else -> listOf(listOf(t))
    }.takeIf { it.size <= MAX_DISJUNCTS }

    private fun conjunctsIdentical(x: List<Type>, y: List<Type>, level: Int) =
        if (x.size == 1 && y.size == 1) identical(x[0], y[0], level + 1)
        else eachInSome(x, y, level) && eachInSome(y, x, level)

    private fun eachInSome(xs: List<Type>, ys: List<Type>, level: Int) =
        xs.all { x -> ys.any { y -> identical(x, y, level + 1) } }

    private fun objectsIdentical(a: Type.Object, b: Type.Object, level: Int): Boolean {
        val at = a.tupleElementTypes
        val bt = b.tupleElementTypes
        if (at != null || bt != null) {
            if (at == null || bt == null) return false
            if (at.size != bt.size || a.readonlyTuple != b.readonlyTuple || a.tupleRestIndex != b.tupleRestIndex) return false
            if (!at.indices.all { identical(at[it], bt[it], level) }) return false
            return mutuallyAssignable(a, b)
        }
        if (a is Type.Reference && b is Type.Reference) {
            if (a.target === b.target) {
                val aa = a.resolvedTypeArguments ?: return mutuallyAssignable(a, b)
                val ba = b.resolvedTypeArguments ?: return mutuallyAssignable(a, b)
                return aa.size == ba.size && aa.indices.all { identical(aa[it], ba[it], level) }
            }
            return mutuallyAssignable(a, b)
        }
        if (a is Type.Reference || b is Type.Reference) return mutuallyAssignable(a, b)
        checker.resolveStructuredTypeMembers(a)
        checker.resolveStructuredTypeMembers(b)
        if (!a.callSignatures.isNullOrEmpty() || !b.callSignatures.isNullOrEmpty() ||
            !a.constructSignatures.isNullOrEmpty() || !b.constructSignatures.isNullOrEmpty()
        ) return mutuallyAssignable(a, b)
        if (!indexIdentical(a.stringIndexInfo, b.stringIndexInfo, level) ||
            !indexIdentical(a.numberIndexInfo, b.numberIndexInfo, level)
        ) return false
        val ap = checker.getPropertiesOfType(a)
        val bp = checker.getPropertiesOfType(b)
        if (ap.size != bp.size) return false
        val byName = bp.associateBy { it.name }
        for (p in ap) {
            val q = byName[p.name] ?: return false
            if (checker.isOptionalProperty(p) != checker.isOptionalProperty(q)) return false
            if (checker.isReadonlySymbol(p) != checker.isReadonlySymbol(q)) return false
            if (!identical(checker.propertyTypeOnCarrier(a, p), checker.propertyTypeOnCarrier(b, q), level)) return false
        }
        return true
    }

    private fun indexIdentical(x: IndexInfo?, y: IndexInfo?, level: Int): Boolean = when {
        x == null || y == null -> x == null && y == null
        else -> x.isReadonly == y.isReadonly && identical(x.type, y.type, level)
    }

    private fun mutuallyAssignable(a: Type, b: Type) = checker.isTypeAssignableTo(a, b) && checker.isTypeAssignableTo(b, a)

    private fun nakedName(n: TypeNode): String? {
        val ref = checker.intersectionOps.unparenthesized(n) as? TypeReference ?: return null
        if (!ref.typeArguments.isNullOrEmpty()) return null
        return (ref.typeName as? Identifier)?.text
    }

    private fun hasInfer(n: Node): Boolean {
        if (n is InferType) return true
        var found = false
        forEachChild(n) { if (!found && hasInfer(it)) found = true }
        return found
    }

    private fun mentions(n: Node, name: String): Boolean {
        if (n is TypeReference && (n.typeName as? Identifier)?.text == name) return true
        var found = false
        forEachChild(n) { if (!found && mentions(it, name)) found = true }
        return found
    }


    private companion object {
        const val MAX_DEPTH = 8
        const val MAX_PARAMS = 4
        const val MAX_IDENTITY_DEPTH = 16
        const val MAX_DISJUNCTS = 64
    }
}

/**
 * (LIBS.2) tsgo's `getModifiersTypeFromMappedType` for a mapped type whose constraint is a bare type
 * parameter `K` DECLARED `K extends keyof X` (`Pick`, type-fest's `IsReadonlyKeyOf`): the modifiers
 * type is `X`, so each member keeps its source property's `readonly` / `?`. Returns that `keyof`
 * operand node, found through the declaration scoping `K` (the innermost one naming it), or null.
 */
internal fun declaredKeyofOperandOfMappedConstraint(node: MappedType): TypeNode? {
    var c = node.typeParameter.constraint ?: return null
    while (c is ParenthesizedType) c = c.type
    val ref = c as? TypeReference ?: return null
    if (!ref.typeArguments.isNullOrEmpty()) return null
    val name = (ref.typeName as? Identifier)?.text ?: return null
    var cur: Node? = (node as NodeBase).parent
    while (cur != null) {
        val tps: List<TypeParameter>? = when (cur) {
            is TypeAliasDeclaration -> cur.typeParameters
            is InterfaceDeclaration -> cur.typeParameters
            is ClassDeclaration -> cur.typeParameters
            is ClassExpression -> cur.typeParameters
            is FunctionDeclaration -> cur.typeParameters
            is FunctionExpression -> cur.typeParameters
            is ArrowFunction -> cur.typeParameters
            is MethodDeclaration -> cur.typeParameters
            is FunctionType -> cur.typeParameters
            is ConstructorType -> cur.typeParameters
            is MappedType -> listOf(cur.typeParameter)
            else -> null
        }
        val tp = tps?.firstOrNull { it.name.text == name }
        if (tp != null) {
            var k = tp.constraint ?: return null
            while (k is ParenthesizedType) k = k.type
            return (k as? TypeOperator)?.takeIf { it.operator == SyntaxKind.KeyOfKeyword }?.type
        }
        cur = (cur as? NodeBase)?.parent
    }
    return null
}
