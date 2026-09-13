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
 * (LEGACY.0a) — tsc's `stableTypeOrdering` comparator over THIS checker's type model.
 *
 * TypeScript 7 / tsgo 7.0.2 orders the constituents of a union by a STABLE key
 * (`compareTypes`, checker.ts:53856 at the pinned `4d4f005c`) instead of by type id,
 * and the corpus is pinned to the baselines that ordering produced. The corpus is the
 * gate: this comparator is what makes `"bar" | "foo"`, `Cover | Cover[]`, `A | Common`
 * and `T | Top | U` come out in the reference's order everywhere a union is built.
 *
 * ## Where it applies — the INTERNING order, not only the display
 *
 * `Checker.getUnionType` sorts the deduplicated member list with [comparator] before
 * interning (INV.5(a) interns a union by its member-id LIST, so the order IS the
 * identity). That is tsc's own placement — `addTypeToUnion` inserts by binary search
 * on `compareTypes` — and it is load-bearing beyond the rendering: a relation-error
 * chain names the FIRST failing constituent, a `Pick<A | B, K>` builds its
 * intersection in member order, and a spelling suggestion over a literal union walks
 * the members in order. A display-only sort would have left all of those at the old
 * order (measured on the first run after the re-pin: `circularlyConstrainedMappedType…`,
 * `complicatedIndexedAccessKeyof…` and `unionPropertyExistence` all diverge on a line
 * that is NOT the union's own rendering). One consequence worth stating: `Foo | Bar`
 * and `Bar | Foo` now intern to ONE `Type.Union` where the old stable-by-flags sort
 * kept them apart — the comparator is a total order up to the id fallback.
 *
 * ## The keys, in tsc's order (checker.ts:53856-54170)
 *
 *  1. [sortOrderFlags] — ascending `TypeFlags` value, with tsc's two remaps: an
 *     enum-like UNIT type sorts as `TypeFlags.Enum`, and (an artefact of the model,
 *     not of tsc) our intrinsic `boolean` sorts where tsc's `false | true` pair does
 *     (`BooleanLiteral`), our whole-enum object where tsc's `Union | EnumLiteral` does.
 *  2. [compareTypeNames] — a NAMED type (alias, type parameter, class/interface,
 *     generic reference) before an unnamed one, named types by name; the same alias
 *     by its alias type arguments.
 *  3. per-kind data — objects by symbol (declaration position: file rank, then `pos`),
 *     references before other objects, references by their type-argument lists,
 *     tuples by shape then element lists; unions and intersections by their member
 *     lists; enum members and type parameters by symbol; string literals and number
 *     literals by VALUE; `false` before `true`.
 *  4. type id.
 *
 * ## What is approximated, and why it is safe
 *
 *  - tsc reads a type's `aliasSymbol`; ours is `Checker.aliasDisplayMap` (B50.2,
 *    id-keyed, first-wins). Two aliases with the same NAME compare as the same alias
 *    (tsc compares symbol identity first) — a name collision falls through to the
 *    per-kind data, which is where tsc also lands for two different symbols of one name.
 *  - tsc's `compareNodes` ranks files by their index in the PROGRAM, default-lib files
 *    first in `libs`-table order (`getDefaultLibFilePriority`). [fileRank] reproduces
 *    that: a lib file by its distributed name's position in [LIB_ORDER], then the program
 *    files in binder order. Only two symbols from DIFFERENT files ever reach it.
 *  - a conditional, an indexed access, a `keyof` and a template literal have no type
 *    of their own in this model (they resolve to their result or to `any`), so tsc's
 *    arms for `Conditional` / `IndexedAccess` / `Index` / `TemplateLiteral` /
 *    `Substitution` have nothing to read here and fall to the id.
 *  - deferred type references and type mappers do not exist here; the `mapper`
 *    comparisons fall to the id, exactly as tsc's own comment says reverse-mapped
 *    types do.
 *
 * Pure in the type graph plus the alias map; consults no walk-scoped ambient.
 */
internal class StableTypeOrdering(
    private val checker: Checker,
    private val binderResults: List<BinderResult>,
) {

    /** The comparator [Checker.getUnionType] sorts with. A total order up to the id. */
    val comparator: Comparator<Type> = Comparator { a, b -> compareTypes(a, b, 0) }

    /** tsc `compareSymbols` — what a mapped type's property list is ordered by. */
    val symbolComparator: Comparator<Symbol> = Comparator { a, b -> compareSymbols(a, b) }

    /** The same order over type NODES, for the node-based display path; see [compareTypeNodes]. */
    val nodeComparator: Comparator<TypeNode> = Comparator { a, b -> compareTypeNodes(a, b, 0) }

    /**
     * File rank memo, keyed by file NAME (a `SourceFile` is a data class — never a map
     * key, round 471). Built on first ask from [binderResults]; lib files rank first.
     */
    private var fileRanks: HashMap<String, Int>? = null

    private fun fileRankOf(fileName: String): Int {
        val ranks = fileRanks ?: HashMap<String, Int>().also { m ->
            binderResults.forEachIndexed { i, r -> m[r.sourceFile.fileName] = LIB_ORDER.size + 2 + i }
            fileRanks = m
        }
        ranks[fileName]?.let { return it }
        // Not a program file: a lib file, ranked as tsc's `getDefaultLibFilePriority`.
        val base = fileName.substringAfterLast('/')
        val rank = when {
            base == "lib.d.ts" || base == "lib.es6.d.ts" -> 0
            base.startsWith("lib.") && base.endsWith(".d.ts") -> {
                val i = LIB_ORDER.indexOf(base.removePrefix("lib.").removeSuffix(".d.ts"))
                if (i >= 0) i + 1 else LIB_ORDER.size + 1
            }
            else -> LIB_ORDER.size + 1
        }
        ranks[fileName] = rank
        return rank
    }

    private fun sourceFileNameOf(node: Node): String? {
        var cur: Node? = node
        var hops = 0
        while (cur != null && hops < 100_000) {
            if (cur is SourceFile) return cur.fileName
            cur = (cur as? NodeBase)?.parent
            hops++
        }
        return null
    }

    /** tsc `compareNodes`: same file → by position; different files → by file rank. */
    private fun compareNodes(n1: Node?, n2: Node?): Int {
        if (n1 === n2) return 0
        if (n1 == null) return 1
        if (n2 == null) return -1
        val f1 = sourceFileNameOf(n1)
        val f2 = sourceFileNameOf(n2)
        if (f1 != f2) {
            val r1 = if (f1 == null) Int.MAX_VALUE else fileRankOf(f1)
            val r2 = if (f2 == null) Int.MAX_VALUE else fileRankOf(f2)
            if (r1 != r2) return r1.compareTo(r2)
            // Two distinct files of equal rank (an unlisted lib pair): by name, stably.
            return (f1 ?: "").compareTo(f2 ?: "")
        }
        return n1.pos.compareTo(n2.pos)
    }

    /** tsc `compareSymbols`: first declaration's position, then name, then symbol id. */
    private fun compareSymbols(s1: Symbol?, s2: Symbol?): Int {
        if (s1 === s2) return 0
        if (s1 == null) return 1
        if (s2 == null) return -1
        val d1 = s1.declarations.firstOrNull()
        val d2 = s2.declarations.firstOrNull()
        if (d1 != null && d2 != null) {
            val r = compareNodes(d1, d2)
            if (r != 0) return r
        } else if (d1 != null) {
            return -1
        } else if (d2 != null) {
            return 1
        }
        val r = s1.name.compareTo(s2.name)
        if (r != 0) return r
        return s1.id.compareTo(s2.id)
    }

    /**
     * tsc `getSortOrderFlags` — ascending `TypeFlags` — over tsc's CURRENT bit order.
     *
     * TypeScript reordered `TypeFlags` to match tsgo's (`types.ts` at `4d4f005c`:
     * `Undefined = 1 << 2, Null, Void, String, Number, BigInt, Boolean, ESSymbol,
     * StringLiteral, …, Enum = 1 << 16, NonPrimitive, Never, TypeParameter, Object,
     * Index, TemplateLiteral, StringMapping, Substitution, IndexedAccess, Conditional,
     * Union, Intersection`), while [TypeFlags] here still carries the PRE-7 order
     * (`String = 1 << 2 … Void = 1 << 14, Undefined, Null, … NonPrimitive = 1 << 26`).
     * The two are different permutations and the rendered order shows it: `Zeta | void`
     * prints `void | Zeta` in both references (measured, pristine 6.0.3 under
     * `--stableTypeOrdering` and tsgo 7.0.2 agree on 21 of 21 fixture rows). So the key
     * is computed through [NEW_BIT], a per-bit remap, and never read off the raw value.
     *
     * Two model remaps on top of tsc's own enum-unit one: our intrinsic `boolean` sorts
     * where tsc's `false | true` pair does (`BooleanLiteral`), and our whole-enum object
     * where tsc's `Union | EnumLiteral` does.
     */
    private fun sortOrderFlags(t: Type): Int {
        val f = t.flags
        if (f.hasAny(TypeFlags.EnumLiteral)) return 1 shl NEW_ENUM
        if (f.hasAny(TypeFlags.Enum)) return (1 shl NEW_UNION) or (1 shl NEW_ENUM_LITERAL)
        if (t is Type.Intrinsic && f.hasAny(TypeFlags.Boolean)) return 1 shl NEW_BOOLEAN_LITERAL
        var v = f.value
        var out = 0
        var bit = 0
        while (v != 0) {
            if (v and 1 != 0) out = out or (1 shl NEW_BIT[bit])
            v = v ushr 1
            bit++
        }
        return out
    }

    /** The name tsc's `getTypeNameSymbol` would answer, plus the alias arguments it compares. */
    private class NameKey(val name: String, val symbol: Symbol?, val aliasArgs: List<Type>?)

    private fun nameKeyOf(t: Type): NameKey? {
        checker.aliasDisplayMap[t.id]?.let { (name, args) -> return NameKey(name, null, args) }
        return when (t) {
            is Type.TypeParam -> t.symbol?.let { NameKey(it.name, it, null) }
            is Type.Reference -> t.target.symbol?.let { NameKey(it.name, it, null) }
            is Type.Interface -> t.symbol?.let { NameKey(it.name, it, null) }
            else -> null
        }
    }

    /** tsc `compareTypeNames`. */
    private fun compareTypeNames(t1: Type, t2: Type, depth: Int): Int {
        val n1 = nameKeyOf(t1)
        val n2 = nameKeyOf(t2)
        if (n1 == null && n2 == null) return 0
        if (n1 == null) return 1
        if (n2 == null) return -1
        val sameSymbol = n1.symbol != null && n1.symbol === n2.symbol
        val sameAlias = n1.aliasArgs != null && n2.aliasArgs != null && n1.name == n2.name
        if (sameSymbol || sameAlias) {
            if (n1.aliasArgs != null) return compareTypeLists(n1.aliasArgs, n2.aliasArgs, depth)
            return 0
        }
        return n1.name.compareTo(n2.name)
    }

    private fun compareTypeLists(l1: List<Type>?, l2: List<Type>?, depth: Int): Int {
        val s1 = l1 ?: emptyList()
        val s2 = l2 ?: emptyList()
        if (s1.size != s2.size) return s1.size.compareTo(s2.size)
        for (i in s1.indices) {
            val c = compareTypes(s1[i], s2[i], depth + 1)
            if (c != 0) return c
        }
        return 0
    }

    /** tsc's tuple `ElementFlags` for one slot: Required 1, Optional 2, Rest 4. */
    private fun tupleSlotFlags(t: Type.Object, i: Int): Int = when {
        i == t.tupleRestIndex -> 4
        checker.tupleSlotIsOptional(t, i) -> 2
        else -> 1
    }

    /** tsc `compareTupleTypes` (shape only; the element lists are compared by the caller). */
    private fun compareTupleTypes(t1: Type.Object, t2: Type.Object): Int {
        if (t1 === t2) return 0
        if (t1.readonlyTuple != t2.readonlyTuple) return if (t1.readonlyTuple) 1 else -1
        val e1 = t1.tupleElementTypes!!
        val e2 = t2.tupleElementTypes!!
        if (e1.size != e2.size) return e1.size.compareTo(e2.size)
        for (i in e1.indices) {
            val c = tupleSlotFlags(t1, i).compareTo(tupleSlotFlags(t2, i))
            if (c != 0) return c
        }
        val names1 = t1.tupleElementNames
        val names2 = t2.tupleElementNames
        if (names1 != null || names2 != null) {
            for (i in e1.indices) {
                val a = names1?.getOrNull(i)
                val b = names2?.getOrNull(i)
                if (a == b) continue
                if (a == null) return -1
                if (b == null) return 1
                val c = a.compareTo(b)
                if (c != 0) return c
            }
        }
        return 0
    }

    /** A tuple or a generic instantiation — what tsc orders as a `TypeReference`. */
    private fun isReferenceLike(t: Type.Object): Boolean =
        t is Type.Reference || t.tupleElementTypes != null

    /** tsc `compareTypes`. */
    private fun compareTypes(t1: Type, t2: Type, depth: Int): Int {
        if (t1 === t2) return 0
        if (depth > MAX_DEPTH) return t1.id.compareTo(t2.id)

        var c = sortOrderFlags(t1).compareTo(sortOrderFlags(t2))
        if (c != 0) return c

        c = compareTypeNames(t1, t2, depth)
        if (c != 0) return c

        when {
            t1 is Type.Intrinsic && t2 is Type.Intrinsic -> {
                if (t1.flags.hasAny(TypeFlags.BooleanLiteral) && t2.flags.hasAny(TypeFlags.BooleanLiteral)) {
                    val b1 = t1.intrinsicName == "true"
                    val b2 = t2.intrinsicName == "true"
                    if (b1 != b2) return if (b1) 1 else -1
                }
                // Every other intrinsic is distinguished by its id alone.
            }
            t1 is Type.StringLiteral && t2 is Type.StringLiteral -> {
                c = t1.value.compareTo(t2.value)
                if (c != 0) return c
            }
            t1 is Type.NumberLiteral && t2 is Type.NumberLiteral -> {
                c = t1.value.compareTo(t2.value)
                if (c != 0) return c
            }
            t1 is Type.Object && t2 is Type.Object -> {
                // Enum-flavoured objects are tsc's `Enum | EnumLiteral` unit types:
                // ordered by their symbol, i.e. by declaration order.
                c = compareSymbols(t1.symbol, t2.symbol)
                if (c != 0) return c
                // A tuple has NO symbol in tsc either, while a type literal, a function
                // type and an object literal carry one there and none here — so two
                // symbol-less objects are ordered by the node each was DECLARED at
                // (`Type.Object.declaredAt`, the declaration tsc's symbol would carry),
                // and a symbol-less non-tuple object stands for a symbol-bearing tsc type
                // and sorts BEFORE a tuple, exactly as `compareSymbols` decides it there.
                if (t1.symbol == null && t2.symbol == null) {
                    c = compareNodes(t1.declaredAt, t2.declaredAt)
                    if (c != 0) return c
                    val tup1 = t1.tupleElementTypes != null
                    val tup2 = t2.tupleElementTypes != null
                    if (tup1 != tup2) return if (tup1) 1 else -1
                }
                val r1 = isReferenceLike(t1)
                val r2 = isReferenceLike(t2)
                if (r1 && r2) {
                    val tuple1 = t1.tupleElementTypes
                    val tuple2 = t2.tupleElementTypes
                    if (tuple1 != null && tuple2 != null) {
                        c = compareTupleTypes(t1, t2)
                        if (c != 0) return c
                        c = compareTypeLists(tuple1, tuple2, depth)
                        if (c != 0) return c
                    } else if (t1 is Type.Reference && t2 is Type.Reference) {
                        c = compareTypeLists(t1.resolvedTypeArguments, t2.resolvedTypeArguments, depth)
                        if (c != 0) return c
                    }
                } else if (r1) {
                    return -1
                } else if (r2) {
                    return 1
                }
                // Unnamed non-reference objects: tsc orders by object kind and mapper,
                // neither of which this model carries — the id decides, as it does for
                // tsc's own reverse-mapped types.
            }
            t1 is Type.Union && t2 is Type.Union -> {
                c = compareTypeLists(t1.types, t2.types, depth)
                if (c != 0) return c
            }
            t1 is Type.Intersection && t2 is Type.Intersection -> {
                c = compareTypeLists(t1.types, t2.types, depth)
                if (c != 0) return c
            }
            t1 is Type.TypeParam && t2 is Type.TypeParam -> {
                c = compareSymbols(t1.symbol, t2.symbol)
                if (c != 0) return c
            }
        }
        return t1.id.compareTo(t2.id)
    }

    // -----------------------------------------------------------------------------
    // The NODE view — for `Checker.formatTypeForDisplay`, the legacy display path
    // that renders an annotation's UnionType node without resolving it.
    // -----------------------------------------------------------------------------

    /** What [nodeKeyOf] can read off a type node: tsc's sort-order flags, the name
     *  tsc's `getTypeNameSymbol` would answer, the symbol behind it, whether it is a
     *  tuple, and the type-argument nodes a same-named pair is compared by. */
    private class NodeKey(
        val rank: Int,
        val name: String?,
        val symbol: Symbol?,
        val tuple: Boolean,
        val args: List<TypeNode>?,
    )

    private val unnamedObjectKey = NodeKey(1 shl 20, null, null, false, null)

    private fun keywordRank(kind: SyntaxKind): Int = when (kind) {
        SyntaxKind.AnyKeyword -> 0
        SyntaxKind.UnknownKeyword -> 1
        SyntaxKind.UndefinedKeyword -> 2
        SyntaxKind.NullKeyword -> 3
        SyntaxKind.VoidKeyword -> 4
        SyntaxKind.StringKeyword -> 5
        SyntaxKind.NumberKeyword -> 6
        SyntaxKind.BigIntKeyword -> 7
        SyntaxKind.BooleanKeyword -> NEW_BOOLEAN_LITERAL
        SyntaxKind.SymbolKeyword -> 9
        SyntaxKind.ObjectKeyword -> 17
        SyntaxKind.NeverKeyword -> 18
        else -> 20
    }

    private fun literalRank(lit: Expression): Int = when (lit) {
        is StringLiteralNode -> 10
        is NumericLiteralNode -> 11
        is PrefixUnaryExpression -> 11
        is Identifier -> if (lit.text == "true" || lit.text == "false") NEW_BOOLEAN_LITERAL else 20
        else -> if (lit is BigIntLiteralNode) 12 else 10
    }

    /** Is a type alias whose BODY is [body] a NAMED type in tsc, i.e. does the alias
     *  symbol attach to the type it declares? It does for every type the alias CREATES
     *  (an anonymous object, a union, a conditional, …) and not for a type that already
     *  exists — a keyword, a literal, a reference to a class or interface. */
    private fun aliasNames(body: TypeNode): Boolean = when (body) {
        is UnionType, is IntersectionType, is ConditionalType, is TypeLiteral, is FunctionType,
        is ConstructorType, is MappedType, is IndexedAccessType, is TemplateLiteralType, is TupleType,
        is ArrayType -> true
        is TypeOperator -> body.operator == SyntaxKind.KeyOfKeyword
        is ParenthesizedType -> aliasNames(body.type)
        else -> false
    }

    private fun nodeKeyOf(n: TypeNode, depth: Int): NodeKey {
        if (depth > 8) return unnamedObjectKey
        return when (n) {
            is ParenthesizedType -> nodeKeyOf(n.type, depth + 1)
            is KeywordTypeNode -> NodeKey(1 shl keywordRank(n.kind), null, null, false, null)
            is LiteralType -> NodeKey(1 shl literalRank(n.literal), null, null, false, null)
            is TypeOperator -> when (n.operator) {
                SyntaxKind.KeyOfKeyword -> NodeKey(1 shl 21, null, null, false, null)
                SyntaxKind.UniqueKeyword -> NodeKey(1 shl 14, null, null, false, null)
                else -> nodeKeyOf(n.type, depth + 1)
            }
            is ArrayType -> NodeKey(1 shl 20, "Array", null, false, listOf(n.elementType))
            is TupleType -> NodeKey(1 shl 20, null, null, true, null)
            is IndexedAccessType -> NodeKey(1 shl 25, null, null, false, null)
            is ConditionalType -> NodeKey(1 shl 26, null, null, false, null)
            is TemplateLiteralType -> NodeKey(1 shl 22, null, null, false, null)
            is UnionType -> NodeKey(1 shl 27, null, null, false, null)
            is IntersectionType -> NodeKey(1 shl 28, null, null, false, null)
            is ThisType -> NodeKey(1 shl 19, null, null, false, null)
            is TypeReference -> referenceKey(n, depth)
            else -> unnamedObjectKey
        }
    }

    /** Does an enclosing declaration of [node] declare a type parameter named [name]?
     *  Purely syntactic — a type parameter lives in no file table, so the per-file
     *  resolver answers it unevenly (an OUTER function's parameter resolved where the
     *  inner one did not, which put `Top` ahead of `T` in `typeParameterDiamond4`). */
    private fun enclosingTypeParameterNamed(node: Node, name: String): Boolean {
        var cur: Node? = (node as? NodeBase)?.parent
        var hops = 0
        while (cur != null && hops < 10_000) {
            val tps: List<TypeParameter>? = when (cur) {
                is FunctionDeclaration -> cur.typeParameters
                is ClassDeclaration -> cur.typeParameters
                is InterfaceDeclaration -> cur.typeParameters
                is TypeAliasDeclaration -> cur.typeParameters
                is FunctionExpression -> cur.typeParameters
                is ArrowFunction -> cur.typeParameters
                is ClassExpression -> cur.typeParameters
                is MethodDeclaration -> cur.typeParameters
                is FunctionType -> cur.typeParameters
                is ConstructorType -> cur.typeParameters
                is SourceFile -> return false
                else -> null
            }
            if (tps != null && tps.any { it.name.text == name }) return true
            cur = (cur as? NodeBase)?.parent
            hops++
        }
        return false
    }

    private fun referenceKey(n: TypeReference, depth: Int): NodeKey {
        val written = when (val tn = n.typeName) {
            is Identifier -> tn.text
            is QualifiedName -> tn.right.text
            else -> null
        }
        val tn = n.typeName
        if (tn is Identifier && enclosingTypeParameterNamed(n, tn.text)) {
            return NodeKey(1 shl 19, tn.text, null, false, null)
        }
        val sym = checker.resolveTypeNameToSymbol(n.typeName)
            ?: return NodeKey(1 shl 20, written, null, false, n.typeArguments)
        val f = sym.flags
        return when {
            f.hasAny(SymbolFlags.TypeParameter) -> NodeKey(1 shl 19, sym.name, sym, false, null)
            f.hasAny(SymbolFlags.EnumMember) -> NodeKey(1 shl NEW_ENUM, null, sym, false, null)
            f.hasAny(SymbolFlags.Class or SymbolFlags.Interface) ->
                NodeKey(1 shl 20, sym.name, sym, false, n.typeArguments)
            f.hasAny(SymbolFlags.TypeAlias) -> {
                val body = (sym.declarations.firstOrNull { it is TypeAliasDeclaration } as? TypeAliasDeclaration)?.type
                    ?: return NodeKey(1 shl 20, sym.name, sym, false, n.typeArguments)
                val inner = nodeKeyOf(body, depth + 1)
                if (aliasNames(body)) NodeKey(inner.rank, sym.name, sym, inner.tuple, n.typeArguments ?: emptyList())
                else inner
            }
            f.hasAny(SymbolFlags.Enum) -> NodeKey((1 shl NEW_UNION) or (1 shl NEW_ENUM_LITERAL), null, sym, false, null)
            else -> NodeKey(1 shl 20, sym.name, sym, false, n.typeArguments)
        }
    }

    private fun literalValueCompare(a: TypeNode, b: TypeNode): Int {
        val la = (a as? LiteralType)?.literal
        val lb = (b as? LiteralType)?.literal
        if (la is StringLiteralNode && lb is StringLiteralNode) return la.text.compareTo(lb.text)
        val na = numericValue(la)
        val nb = numericValue(lb)
        if (na != null && nb != null) return na.compareTo(nb)
        return 0
    }

    private fun numericValue(e: Expression?): Double? = when (e) {
        is NumericLiteralNode -> e.text.toDoubleOrNull()
        is PrefixUnaryExpression -> (e.operand as? NumericLiteralNode)?.text?.toDoubleOrNull()
            ?.let { if (e.operator == SyntaxKind.Minus) -it else it }
        else -> null
    }

    private fun compareNodeLists(l1: List<TypeNode>?, l2: List<TypeNode>?, depth: Int): Int {
        val s1 = l1 ?: emptyList()
        val s2 = l2 ?: emptyList()
        if (s1.size != s2.size) return s1.size.compareTo(s2.size)
        for (i in s1.indices) {
            val c = compareTypeNodes(s1[i], s2[i], depth + 1)
            if (c != 0) return c
        }
        return 0
    }

    /**
     * [compareTypes] over the NODES of a written union, for the display path that
     * renders an annotation without resolving it. The keys are tsc's, read off the
     * syntax: a reference's kind and name through its SYMBOL (an alias ranks as its
     * body, a type parameter as one, an enum member as one), a literal by its value,
     * same-named references by their type-argument lists, anonymous shapes by
     * position with a tuple last. What the node cannot tell — an instantiation's
     * actual arguments, a conditional's resolution — stays in written order.
     */
    private fun compareTypeNodes(a: TypeNode, b: TypeNode, depth: Int): Int {
        if (a === b) return 0
        if (depth > MAX_DEPTH) return a.pos.compareTo(b.pos)
        val ka = nodeKeyOf(a, depth)
        val kb = nodeKeyOf(b, depth)
        var c = ka.rank.compareTo(kb.rank)
        if (c != 0) return c
        if (ka.name == null && kb.name != null) return 1
        if (ka.name != null && kb.name == null) return -1
        if (ka.name != null && kb.name != null) {
            val same = (ka.symbol != null && ka.symbol === kb.symbol) || ka.name == kb.name
            if (same) {
                c = compareNodeLists(ka.args, kb.args, depth)
                if (c != 0) return c
            } else {
                c = ka.name.compareTo(kb.name)
                if (c != 0) return c
            }
        }
        c = literalValueCompare(a, b)
        if (c != 0) return c
        if (ka.symbol != null && kb.symbol != null && ka.symbol !== kb.symbol) {
            c = compareSymbols(ka.symbol, kb.symbol)
            if (c != 0) return c
        }
        if (ka.tuple != kb.tuple) return if (ka.tuple) 1 else -1
        return a.pos.compareTo(b.pos)
    }

    companion object {
        /** A guard for pathological nesting only; tsc's comparator has none. */
        private const val MAX_DEPTH = 64

        /** tsc's current bit positions (types.ts at `4d4f005c`) for the three flags
         *  [sortOrderFlags] names directly. */
        private const val NEW_BOOLEAN_LITERAL = 13
        private const val NEW_ENUM_LITERAL = 15
        private const val NEW_ENUM = 16
        private const val NEW_UNION = 27

        /**
         * Our [TypeFlags] bit index → tsc's current bit index. Indexed by OUR bit:
         * Any 0, Unknown 1, String 2, Number 3, Boolean 4, Enum 5, BigInt 6,
         * StringLiteral 7, NumberLiteral 8, BooleanLiteral 9, EnumLiteral 10,
         * BigIntLiteral 11, ESSymbol 12, UniqueESSymbol 13, Void 14, Undefined 15,
         * Null 16, Never 17, TypeParameter 18, Object 19, Union 20, Intersection 21,
         * Index 22, IndexedAccess 23, Conditional 24, Substitution 25, NonPrimitive 26,
         * TemplateLiteral 27, StringMapping 28.
         */
        private val NEW_BIT = intArrayOf(
            0, 1, 5, 6, 8, 16, 7, 10, 11, 13, 15, 12, 9, 14, 4, 2, 3, 18, 19, 20, 27, 28,
            21, 25, 26, 24, 17, 22, 23,
        )

        /**
         * tsc's `libs` table order (commandLineParser.ts `libEntries` at `4d4f005c`),
         * which `getDefaultLibFilePriority` ranks default-lib files by. `lib.d.ts` and
         * `lib.es6.d.ts` rank 0, a listed name `index + 1`, anything else after.
         */
        private val LIB_ORDER: List<String> = listOf(
            "es5", "es6", "es2015", "es7", "es2016", "es2017", "es2018", "es2019", "es2020",
            "es2021", "es2022", "es2023", "es2024", "es2025", "esnext", "dom", "dom.iterable",
            "dom.asynciterable", "webworker", "webworker.importscripts", "webworker.iterable",
            "webworker.asynciterable", "scripthost", "es2015.core", "es2015.collection",
            "es2015.generator", "es2015.iterable", "es2015.promise", "es2015.proxy",
            "es2015.reflect", "es2015.symbol", "es2015.symbol.wellknown", "es2016.array.include",
            "es2016.intl", "es2017.arraybuffer", "es2017.date", "es2017.object",
            "es2017.sharedmemory", "es2017.string", "es2017.intl", "es2017.typedarrays",
            "es2018.asyncgenerator", "es2018.asynciterable", "es2018.intl", "es2018.promise",
            "es2018.regexp", "es2019.array", "es2019.object", "es2019.string", "es2019.symbol",
            "es2019.intl", "es2020.bigint", "es2020.date", "es2020.promise", "es2020.sharedmemory",
            "es2020.string", "es2020.symbol.wellknown", "es2020.intl", "es2020.number",
            "es2021.promise", "es2021.string", "es2021.weakref", "es2021.intl", "es2022.array",
            "es2022.error", "es2022.intl", "es2022.object", "es2022.string", "es2022.regexp",
            "es2023.array", "es2023.collection", "es2023.intl", "es2024.arraybuffer",
            "es2024.collection", "es2024.object", "es2024.promise", "es2024.regexp",
            "es2024.sharedmemory", "es2024.string", "es2025.collection", "es2025.float16",
            "es2025.intl", "es2025.iterator", "es2025.promise", "es2025.regexp",
            "esnext.asynciterable", "esnext.symbol", "esnext.bigint", "esnext.weakref",
            "esnext.object", "esnext.regexp", "esnext.string", "esnext.float16", "esnext.iterator",
            "esnext.promise", "esnext.array", "esnext.collection", "esnext.date",
            "esnext.decorators", "esnext.disposable", "esnext.error", "esnext.intl",
            "esnext.sharedmemory", "esnext.temporal", "esnext.typedarrays", "decorators",
            "decorators.legacy",
        )
    }
}
