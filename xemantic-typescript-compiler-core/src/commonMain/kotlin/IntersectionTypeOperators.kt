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
 * (CHK.215) (CHK.226) The type operators over an INTERSECTION, which this checker used to
 * answer with a placeholder: `keyof (A & B)` was `string`, `(A & B)["p"]` was `any` and
 * [Checker.getPropertyOfType] had no intersection arm (so `Required<A & B>`'s `-?` and `Pick<A & B, K>`'s `?` never saw the source
 * property). tsgo: `getIndexType` (`keyof (A & B)` = `keyof A | keyof B`), `getPropertyOfType`
 * -> `createUnionOrIntersectionProperty`.
 *
 * Every rule answers null — the caller keeps its old answer — whenever a constituent's key set is
 * not a decomposable set of literal / primitive keys, so nothing that was open becomes closed by
 * accident (round 463's partial-key-domain law). A type-parameter constituent never reaches here
 * (`keyof (T & U)` stays the open domain, see [Checker.getKeyofType]).
 */
internal class IntersectionTypeOperators(private val checker: Checker) {

    /** `keyof (A & B)` = the union of the constituents' key sets, or null when one is not a set. */
    fun keyofIntersection(type: Type.Intersection, keyof: (Type) -> Type): Type? {
        if (type.types.any { it is Type.Union }) return keyofDistributed(type, keyof)
        val out = ArrayList<Type>()
        for (c in type.types) {
            if (c !is Type.Object && c !is Type.Intersection) return null
            out.addAll(keySet(keyof(c)) ?: return null)
        }
        return normalize(out)
    }

    /**
     * `keyof (X & (A | B))`: tsgo normalizes the intersection to `(X & A) | (X & B)` and drops a
     * combination that a DISJOINT DISCRIMINANT reduces to `never` (two unit types for one property
     * that cannot be equal — `{ v: E.A } & { v: E.B }`), so `keyof` is taken over the survivors
     * only (`mappedTypeNotMistakenlyHomomorphic`). Without the reduction the answer would be the
     * keys common to EVERY member of the union, which loses a member tsgo keeps.
     */
    private fun keyofDistributed(type: Type.Intersection, keyof: (Type) -> Type): Type? {
        var combos: List<List<Type>> = listOf(emptyList())
        for (c in type.types) {
            val options = (c as? Type.Union)?.types ?: listOf(c)
            combos = combos.flatMap { prefix -> options.map { prefix + it } }
            if (combos.size > MAX_COMBINATIONS) return null
        }
        val sets = ArrayList<List<Type>>()
        for (combo in combos) {
            if (combo.any { it !is Type.Object && it !is Type.Intersection }) return null
            if (neverReduced(combo)) continue
            sets.add(combo.flatMap { keySet(keyof(it)) ?: return null })
        }
        if (sets.isEmpty()) return neverType
        val candidates = sets.flatten().distinctBy { keyId(it) }
        return normalize(candidates.filter { e -> sets.all { covers(it, e) } })
    }

    /**
     * The constituents of [type] after tsgo's normalization, when that is still ONE intersection:
     * a union constituent all but one of whose members a disjoint discriminant reduces to `never`
     * (`{ v: E.A } & ({ v: E.A; a: string } | { v: E.B; b: string })` is `{ v: E.A } & { v: E.A;
     * a: string }`). Otherwise the constituents as written.
     */
    private fun reduced(type: Type.Intersection): List<Type> {
        if (type.types.none { it is Type.Union }) return type.types
        return reducedCache.getOrPut(type.id) {
            var combos: List<List<Type>> = listOf(emptyList())
            for (c in type.types) {
                val options = (c as? Type.Union)?.types ?: listOf(c)
                combos = combos.flatMap { prefix -> options.map { prefix + it } }
                if (combos.size > MAX_COMBINATIONS) return@getOrPut type.types
            }
            combos.filter { !neverReduced(it) }.singleOrNull() ?: type.types
        }
    }

    private val reducedCache = HashMap<Int, List<Type>>()

    private fun neverReduced(combo: List<Type>): Boolean {
        val objects = combo.flatMap { (it as? Type.Intersection)?.types ?: listOf(it) }.filterIsInstance<Type.Object>()
        if (objects.size < 2) return false
        val seen = HashMap<String, Type>()
        for (o in objects) {
            checker.resolveStructuredTypeMembers(o)
            for (p in o.properties ?: continue) {
                val t = checker.propertyTypeOnCarrier(o, p)
                val prior = seen.put(p.name, t) ?: continue
                if (disjointUnits(prior, t)) return true
            }
        }
        return false
    }

    private fun disjointUnits(a: Type, b: Type): Boolean = when {
        checker.isLiteralKindForDiscriminant(a) && checker.isLiteralKindForDiscriminant(b) ->
            !checker.literalsEqualForDiscriminant(a, b)
        isEnumMember(a) && isEnumMember(b) ->
            (a as Type.Object).symbol?.parent === (b as Type.Object).symbol?.parent &&
                !checker.enumSemantics.enumMemberTypesAreSameMember(a, b)
        else -> false
    }

    private fun isEnumMember(t: Type) =
        (t as? Type.Object)?.symbol?.flags?.hasAny(SymbolFlags.EnumMember) == true

    /**
     * The property [name] of an intersection: the constituent symbol when exactly one constituent
     * has it, otherwise a synthesized symbol (cached per intersection) typed by the intersection of
     * the constituent property types and OPTIONAL only when every constituent's is.
     */
    fun propertyOfIntersection(type: Type.Intersection, name: String): Symbol? {
        val perType = cache.getOrPut(type.id) { HashMap() }
        if (perType.containsKey(name)) return perType[name]
        val found = ArrayList<Pair<Type, Symbol>>(2)
        for (c in reduced(type)) checker.getPropertyOfType(c, name)?.let { found.add(c to it) }
        val result = when (found.size) {
            0 -> null
            1 -> found[0].second
            else -> synthesize(name, found)
        }
        perType[name] = result
        return result
    }

    /** `(A & B)["p"]`: the intersection of the constituents that have `p`, or null when none has. */
    fun indexedAccess(type: Type.Intersection, key: Type.StringLiteral, access: (Type, Type) -> Type): Type? {
        val have = reduced(type).filter { checker.getPropertyOfType(it, key.value) != null }
        if (have.isEmpty()) return null
        val parts = have.map { access(it, key) }
        if (parts.any { it === anyType || it === errorType }) return anyType
        return combine(parts)
    }

    /**
     * The members of an intersection of plain, non-generic object types with no index signature,
     * in constituent order — what `Pick` / `Omit` select from — or null when one is anything else.
     */
    fun properties(type: Type.Intersection): List<Symbol>? {
        val names = LinkedHashSet<String>()
        for (c in type.types) {
            val o = c as? Type.Object ?: return null
            if (o is Type.Reference) return null
            checker.resolveStructuredTypeMembers(o)
            if (o.stringIndexInfo != null || o.numberIndexInfo != null) return null
            o.properties?.forEach { names.add(it.name) } ?: return null
        }
        return names.map { propertyOfIntersection(type, it) ?: return null }
    }

    /**
     * (CHK.215) The `infer` binding of `S extends (p: infer I) => void` — UnionToIntersection's
     * second half: each function constituent of [checkType] contributes its first parameter type
     * as a CONTRAVARIANT candidate, and contravariant candidates of a conditional's `infer`
     * combine by INTERSECTION (tsgo `getTypeFromInference`). Only the exact shape — one bare
     * `infer` parameter, a `void` return, every constituent a one-signature function with a
     * parameter — is decided; anything else is null and the caller keeps its old answer.
     */
    fun inferFromFunctionExtends(checkType: Type, ext: FunctionType): Pair<String, Type>? {
        if (!ext.typeParameters.isNullOrEmpty() || ext.parameters.size != 1) return null
        val p = ext.parameters[0]
        if (p.dotDotDotToken || p.questionToken) return null
        val infer = p.type as? InferType ?: return null
        if (infer.typeParameter.constraint != null) return null
        if ((ext.type as? KeywordTypeNode)?.kind != SyntaxKind.VoidKeyword) return null
        val sources = (checkType as? Type.Union)?.types ?: listOf(checkType)
        val candidates = sources.map { src ->
            val sig = checker.getCallSignaturesOfType(src).singleOrNull() ?: return null
            if (!sig.typeParameters.isNullOrEmpty()) return null
            val first = sig.parameters.firstOrNull() ?: return null
            checker.getTypeOfSymbol(first).takeIf { it !== errorType } ?: return null
        }
        val distinct = candidates.distinctBy { it.id }
        return infer.typeParameter.name.text to (distinct.singleOrNull() ?: checker.getIntersectionType(distinct))
    }

    /**
     * (CHK.215) tsgo attaches a generic alias's name only to a type its instantiation CREATED: a
     * body that is a `keyof` or an indexed access answers an EXISTING type, which prints as itself,
     * and so does a resolved CONDITIONAL whose branch is an interned type (a generic reference, an
     * interface, a union). Registering the name there made every holder of that interned type print
     * as the alias — `number[]` program-wide as `LiteralToPrimitiveDeep<[1, 2, 3]>`, and two
     * instantiations resolving to one type share a display (`K1<{ a: 1 }>` printed as
     * `K1<{ a: 1 } | { b: 1 }>`). A conditional branch that is a FRESH anonymous object or
     * intersection keeps the name: the elaboration gates key on it
     * (`excessPropertyCheckIntersectionWithRecursiveType`).
     */
    fun aliasBodyAnswersExistingType(body: TypeNode, result: Type): Boolean = when (unparenthesized(body)) {
        is IndexedAccessType -> true
        is TypeOperator -> (unparenthesized(body) as TypeOperator).operator == SyntaxKind.KeyOfKeyword
        is ConditionalType -> result is Type.Reference || result is Type.Interface || result is Type.Union
        else -> false
    }

    /** A rendered type with a `|` outside every bracket — an anonymous union's display. */
    fun hasTopLevelBar(rendered: String): Boolean {
        var depth = 0
        var quote: Char? = null
        for ((i, ch) in rendered.withIndex()) {
            when {
                quote != null -> if (ch == quote && rendered.getOrNull(i - 1) != '\\') quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '(' || ch == '<' || ch == '{' || ch == '[' -> depth++
                ch == ')' || ch == '>' || ch == '}' || ch == ']' -> if (rendered.getOrNull(i - 1) != '=') depth--
                ch == '|' && depth == 0 -> return true
            }
        }
        return false
    }

    fun unparenthesized(node: TypeNode): TypeNode {
        var b = node
        while (b is ParenthesizedType) b = b.type
        return b
    }

    private val cache = HashMap<Int, HashMap<String, Symbol?>>()

    private companion object { const val MAX_COMBINATIONS = 64 }

    private fun synthesize(name: String, found: List<Pair<Type, Symbol>>): Symbol {
        val optional = found.all { checker.isOptionalProperty(it.second) }
        val chosen = found.firstOrNull { checker.isOptionalProperty(it.second) == optional }?.second ?: found[0].second
        val sym = Symbol(chosen.flags, name)
        sym.declarations.addAll(chosen.declarations)
        sym.valueDeclaration = chosen.valueDeclaration
        sym.parent = chosen.parent
        if (checker.isOptionalProperty(sym) != optional) {
            if (optional) sym.flags = sym.flags or SymbolFlags.MappedOptional
            else checker.mappedRequiredMemberIds.add(sym.id)
        }
        val types = found.map { (owner, p) ->
            (owner as? Type.Object)?.let { checker.propertyTypeOnCarrier(it, p) } ?: checker.getTypeOfSymbol(p)
        }
        checker.symbolTypes[sym.id] = if (types.any { it === anyType || it === errorType }) anyType else combine(types)
        return sym
    }

    /** Intersect property types; `undefined` survives only where every part carries it. */
    private fun combine(parts: List<Type>): Type {
        val stripped = if (parts.all { checker.typeIncludesUndefined(it) }) parts
            else parts.map { checker.nonUndefinedType(it) }
        val distinct = stripped.distinctBy { it.id }
        return distinct.singleOrNull() ?: checker.getIntersectionType(distinct)
    }

    private fun keySet(t: Type): List<Type>? = when {
        t === neverType -> emptyList()
        isKey(t) -> listOf(t)
        t is Type.Union && t.types.all { isKey(it) } -> t.types
        else -> null
    }

    private fun isKey(t: Type) = t is Type.StringLiteral || t is Type.NumberLiteral ||
        t === stringType || t === numberType || t === esSymbolType

    private fun keyId(t: Type): Any = when (t) {
        is Type.StringLiteral -> "s:" + t.value
        is Type.NumberLiteral -> t.value
        else -> t.id
    }

    private fun covers(set: List<Type>, e: Type): Boolean = set.any { keyId(it) == keyId(e) } ||
        (e is Type.StringLiteral && stringType in set) || (e is Type.NumberLiteral && numberType in set)

    private fun normalize(keys: Collection<Type>): Type {
        val hasString = stringType in keys
        val kept = keys.filter { !(hasString && it is Type.StringLiteral) }.distinctBy { keyId(it) }
        return if (kept.isEmpty()) neverType else checker.getUnionType(kept)
    }
}
