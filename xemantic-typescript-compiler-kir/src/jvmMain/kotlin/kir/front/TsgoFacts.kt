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

package com.xemantic.typescript.compiler.kir.front

import com.xemantic.typescript.compiler.BigIntLiteralNode
import com.xemantic.typescript.compiler.CallExpression
import com.xemantic.typescript.compiler.ClassDeclaration
import com.xemantic.typescript.compiler.Constructor
import com.xemantic.typescript.compiler.EmptyStatement
import com.xemantic.typescript.compiler.Expression
import com.xemantic.typescript.compiler.FunctionDeclaration
import com.xemantic.typescript.compiler.GetAccessor
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.MethodDeclaration
import com.xemantic.typescript.compiler.NewExpression
import com.xemantic.typescript.compiler.NoSubstitutionTemplateLiteralNode
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NumericLiteralNode
import com.xemantic.typescript.compiler.Parameter
import com.xemantic.typescript.compiler.PropertyAccessExpression
import com.xemantic.typescript.compiler.PropertyDeclaration
import com.xemantic.typescript.compiler.RegularExpressionLiteralNode
import com.xemantic.typescript.compiler.SetAccessor
import com.xemantic.typescript.compiler.Signature
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.StringLiteralNode
import com.xemantic.typescript.compiler.Symbol
import com.xemantic.typescript.compiler.SymbolFlags
import com.xemantic.typescript.compiler.Type
import com.xemantic.typescript.compiler.TypeFlags
import com.xemantic.typescript.compiler.TypeNode
import com.xemantic.typescript.compiler.anyType
import com.xemantic.typescript.compiler.bigintType
import com.xemantic.typescript.compiler.booleanType
import com.xemantic.typescript.compiler.esSymbolType
import com.xemantic.typescript.compiler.falseType
import com.xemantic.typescript.compiler.forEachChild
import com.xemantic.typescript.compiler.neverType
import com.xemantic.typescript.compiler.nonPrimitiveType
import com.xemantic.typescript.compiler.nullType
import com.xemantic.typescript.compiler.numberType
import com.xemantic.typescript.compiler.stringType
import com.xemantic.typescript.compiler.trueType
import com.xemantic.typescript.compiler.undefinedType
import com.xemantic.typescript.compiler.unknownType
import com.xemantic.typescript.compiler.voidType
import com.xemantic.typescript.tsgo.ast.forEachChild as tsgoForEachChild
import com.xemantic.typescript.tsgo.ast.modifiers
import com.xemantic.typescript.tsgo.ast.name
import com.xemantic.typescript.tsgo.ast.symbol
import com.xemantic.typescript.tsgo.checker.symbol
import com.xemantic.typescript.tsgo.checker.SignatureKindCall
import com.xemantic.typescript.tsgo.checker.SignatureKindConstruct
import com.xemantic.typescript.tsgo.checker.asInterfaceType
import com.xemantic.typescript.tsgo.checker.asLiteralType
import com.xemantic.typescript.tsgo.checker.asTupleType
import com.xemantic.typescript.tsgo.checker.asTypeParameter
import com.xemantic.typescript.tsgo.checker.getBaseConstraintOfType
import com.xemantic.typescript.tsgo.checker.getConstraintOfTypeParameter
import com.xemantic.typescript.tsgo.checker.getIndexInfosOfType
import com.xemantic.typescript.tsgo.checker.getPropertiesOfType
import com.xemantic.typescript.tsgo.checker.flags
import com.xemantic.typescript.tsgo.checker.getApparentType
import com.xemantic.typescript.tsgo.checker.getExportsOfModule
import com.xemantic.typescript.tsgo.checker.getResolvedSignature
import com.xemantic.typescript.tsgo.checker.getShorthandAssignmentValueSymbol
import com.xemantic.typescript.tsgo.checker.getReturnTypeOfSignature
import com.xemantic.typescript.tsgo.checker.getSignaturesOfType
import com.xemantic.typescript.tsgo.checker.getTypeArguments
import com.xemantic.typescript.tsgo.checker.getTypeAtLocation
import com.xemantic.typescript.tsgo.checker.getTypeOfSymbol
import com.xemantic.typescript.tsgo.checker.objectFlags
import com.xemantic.typescript.tsgo.checker.resolveAlias
import com.xemantic.typescript.tsgo.checker.target
import com.xemantic.typescript.tsgo.checker.typeToString
import com.xemantic.typescript.tsgo.checker.types
import com.xemantic.typescript.tsgo.runtime.GoPanic
import com.xemantic.typescript.tsgo.runtime.GoString
import java.util.IdentityHashMap
import com.xemantic.typescript.tsgo.ast.Node as TsgoNode
import com.xemantic.typescript.tsgo.ast.SourceFile as TsgoSourceFile
import com.xemantic.typescript.tsgo.ast.Symbol as TsgoSymbol
import com.xemantic.typescript.tsgo.checker.Checker as TsgoChecker
import com.xemantic.typescript.tsgo.checker.Signature as TsgoSignature
import com.xemantic.typescript.tsgo.checker.Type as TsgoType

// (TSGO.4-c) THE KIR FRONT END ON THE PORTED tsgo CHECKER.
//
// The lowering walks `-core`'s AST and reads `-core`'s value classes (`Type`,
// `Symbol`, `Signature`) off [CheckedFacts]. Re-basing it onto tsgo therefore
// has two halves, both here:
//
// 1. NODE CORRESPONDENCE. Every program file is parsed twice — by tsgo, as part
//    of its program, and by `-core`'s parser from the SAME text, as the syntax
//    the lowering walks — and the two trees are paired node for node
//    ([FileNodeMap]). The key is the node's KIND and its first-token position:
//    `-core`'s `Node.pos` is tsc's `getStart()`, i.e. tsgo's
//    `GetTokenPosOfNode`, and nodes of one kind starting at one offset are an
//    ancestor chain (`a.b.c` / `a.b`), paired outermost first.
//
// 2. VALUE TRANSLATION ([TsgoTranslator]). A tsgo type, symbol or signature is
//    rebuilt as the `-core` value with the SHAPE `-core`'s checker gave it —
//    flags by name, a union's members, an instantiation as a `Type.Reference`
//    over its target `Type.Interface` (no symbol of its own, as `-core`
//    reports it), a tuple's element types, call signatures with their
//    declarations, a symbol's declarations mapped back onto the `-core` tree.
//    The answers themselves are tsgo's.
//
// Every fact the `-core` sink recorded during its walk is asked here AFTER the
// check, which is exactly what tsgo's checker supports (it is what its API
// answers) and what `-core`'s could not do.

/** The `-core` tree and the tsgo tree of one program file, paired node for node. */
internal class FileNodeMap(val core: SourceFile, val tsgo: TsgoSourceFile) {

    private val coreToTsgo = IdentityHashMap<Node, TsgoNode>()
    private val tsgoToCore = IdentityHashMap<TsgoNode, Node>()

    /** `-core` nodes the walk could not pair, for the census (`XTSC_KIR_MAP_DEBUG`). */
    var unpairedCore: Int = 0
        private set

    init {
        val offsets = Utf8Offsets(GoString.toUtf16(tsgo.text))
        val tsgoGroups = HashMap<String, MutableList<TsgoNode>>()
        fun visitTsgo(node: TsgoNode) {
            val start = offsets.toUtf16(
                com.xemantic.typescript.tsgo.scanner.getTokenPosOfNode(node, tsgo, false)
            )
            val kind = tsgoKindAlias(GoString.toUtf16(node.kind.string()).removePrefix("Kind"))
            tsgoGroups.getOrPut("$start|$kind") { mutableListOf() }.add(node)
            // `-core` starts a declaration AFTER its modifiers (`export default
            // function f` is at `function`), where tsgo's first token is the
            // first modifier — so a modified node answers to both offsets.
            node.modifiers()?.nodeList?.takeIf { it.nodes.len > 0 }?.let { modifiers ->
                val after = offsets.toUtf16(
                    com.xemantic.typescript.tsgo.scanner.skipTrivia(tsgo.text, modifiers.loc.end())
                )
                if (after != start) tsgoGroups.getOrPut("$after|$kind") { mutableListOf() }.add(node)
            }
            node.tsgoForEachChild { child ->
                if (child != null) visitTsgo(child)
                false
            }
        }
        tsgo.asNode().tsgoForEachChild { child ->
            if (child != null) visitTsgo(child)
            false
        }
        val coreGroups = HashMap<String, MutableList<Node>>()
        fun visitCore(node: Node) {
            coreGroups.getOrPut("${node.pos}|${coreKind(node)}") { mutableListOf() }.add(node)
            forEachChild(node) { visitCore(it) }
        }
        forEachChild(core) { visitCore(it) }
        for ((key, coreNodes) in coreGroups) {
            val tsgoNodes = tsgoGroups[key]
            if (tsgoNodes == null || tsgoNodes.size != coreNodes.size) {
                unpairedCore += coreNodes.size
                if (debug) System.err.println(
                    "xtsc-kir-map: ${core.fileName} $key core=${coreNodes.size} tsgo=${tsgoNodes?.size ?: 0}"
                )
                // Pair what pairs safely: a lone node of each side.
                if (tsgoNodes?.size == 1 && coreNodes.size == 1) pair(coreNodes[0], tsgoNodes[0])
                continue
            }
            coreNodes.indices.forEach { pair(coreNodes[it], tsgoNodes[it]) }
        }
        tsgoToCore[tsgo.asNode()!!] = core
        coreToTsgo[core] = tsgo.asNode()!!
    }

    private fun pair(coreNode: Node, tsgoNode: TsgoNode) {
        coreToTsgo[coreNode] = tsgoNode
        tsgoToCore[tsgoNode] = coreNode
    }

    fun tsgoOf(node: Node): TsgoNode? = coreToTsgo[node]

    fun coreOf(node: TsgoNode): Node? = tsgoToCore[node]

    private companion object {

        val debug = System.getenv("XTSC_KIR_MAP_DEBUG") == "1"

        /** tsgo's kind for an interface MEMBER, which `-core` parses as the class-member node. */
        fun tsgoKindAlias(kind: String): String = when (kind) {
            "PropertySignature" -> "PropertyDeclaration"
            "MethodSignature" -> "MethodDeclaration"
            "JsxExpression" -> "JsxExpressionContainer"
            else -> kind
        }

        /** `-core`'s node class as tsgo's kind name. */
        fun coreKind(node: Node): String = when (node) {
            // `-core` parses the keyword expressions as identifiers.
            is Identifier -> when (node.text) {
                "this" -> "ThisKeyword"
                "super" -> "SuperKeyword"
                "true" -> "TrueKeyword"
                "false" -> "FalseKeyword"
                "null" -> "NullKeyword"
                else -> "Identifier"
            }
            is StringLiteralNode -> "StringLiteral"
            is NumericLiteralNode -> "NumericLiteral"
            is BigIntLiteralNode -> "BigIntLiteral"
            is RegularExpressionLiteralNode -> "RegularExpressionLiteral"
            is NoSubstitutionTemplateLiteralNode -> "NoSubstitutionTemplateLiteral"
            else -> node::class.simpleName ?: "?"
        }
    }

}

/**
 * Rebuilds tsgo's answers as `-core` values.
 *
 * Identity-memoized, so one tsgo type is one `-core` type (a union's member
 * and the same type met elsewhere are the same object), and DEFERRED: a type's
 * call signatures, a reference's type arguments, a signature's return type and
 * a symbol's declarations are filled after the shell is memoized, which is
 * what lets a recursive type (`type F = () => F`) translate at all.
 */
internal class TsgoTranslator(
    private val checker: TsgoChecker,
    private val maps: Map<TsgoSourceFile, FileNodeMap>,
    private val facts: CheckedFacts,
) {

    private val types = IdentityHashMap<TsgoType, Type>()
    private val symbols = IdentityHashMap<TsgoSymbol, Symbol>()
    private val signatures = IdentityHashMap<TsgoSignature, Signature>()
    private val placeholders = IdentityHashMap<TsgoNode, Node>()
    private val pending = ArrayDeque<() -> Unit>()

    /** The `-core` node a tsgo node is: its pair in a program file, or a stand-in outside the program. */
    fun coreNode(node: TsgoNode?): Node? {
        node ?: return null
        val file = com.xemantic.typescript.tsgo.ast.getSourceFileOfNode(node)
        maps[file]?.coreOf(node)?.let { return it }
        // A LIBRARY declaration (or one the pairing missed). `-core` handed out
        // the lib's own node; the lowering only ever asks of it whether it is a
        // PROGRAM node and which program table holds it, and a stand-in that is
        // in no program file answers both exactly as the lib node did.
        return placeholders.getOrPut(node) { EmptyStatement(pos = -1 - placeholders.size, end = -1) }
    }

    fun type(t: TsgoType?): Type? = t?.let { drained { translate(it) } }

    fun symbol(s: TsgoSymbol?): Symbol? = s?.let { drained { translateSymbol(it) } }

    fun signature(s: TsgoSignature?): Signature? = s?.let { drained { translateSignature(it) } }

    private fun <R> drained(block: () -> R): R {
        val result = block()
        while (pending.isNotEmpty()) pending.removeFirst()()
        return result
    }

    private fun translate(t: TsgoType): Type {
        types[t]?.let { return it }
        val core = shell(t)
        types[t] = core
        if (core.id !in rendered) {
            rendered.add(core.id)
            facts.putRendering(core, GoString.toUtf16(checker.typeToString(t)))
        }
        return core
    }

    private val rendered = HashSet<Int>()

    private fun shell(t: TsgoType): Type {
        val f = t.flags().value
        fun has(flag: com.xemantic.typescript.tsgo.checker.TypeFlags) = f and flag.value != 0u
        return when {
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsAny) -> anyType
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsUnknown) -> unknownType
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsString) -> stringType
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsNumber) -> numberType
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsBigInt) -> bigintType
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsESSymbol) ||
                has(com.xemantic.typescript.tsgo.checker.TypeFlagsUniqueESSymbol) -> esSymbolType
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsVoid) -> voidType
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsUndefined) -> undefinedType
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsNull) -> nullType
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsNever) -> neverType
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsNonPrimitive) -> nonPrimitiveType
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsBooleanLiteral) ->
                if (t.asLiteralType()!!.value == true) trueType else falseType
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsStringLiteral) ->
                Type.StringLiteral(GoString.toUtf16(t.asLiteralType()!!.value as String))
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsNumberLiteral) ->
                Type.NumberLiteral((t.asLiteralType()!!.value as com.xemantic.typescript.tsgo.jsnum.Number).value)
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsBigIntLiteral) -> {
                val value = t.asLiteralType()!!.value as com.xemantic.typescript.tsgo.jsnum.PseudoBigInt
                Type.BigIntLiteral((if (value.negative) "-" else "") + GoString.toUtf16(value.base10Value))
            }
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsUnion) -> union(t)
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsIntersection) -> intersection(t)
            // The polymorphic `this` of a class body is a type parameter whose
            // constraint is the class instance type, and the value IS such an
            // instance (or a subclass's, which on the JVM extends it).
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsTypeParameter) &&
                t.asTypeParameter()?.isThisType == true ->
                checker.getConstraintOfTypeParameter(t)?.let { translate(it) } ?: unknownType
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsTypeParameter) ->
                Type.TypeParam().also { tp -> t.symbol()?.let { s -> pending.add { tp.symbol = translateSymbol(s) } } }
            // A GENERIC type the program never instantiates here — `Events[Key]`,
            // a conditional, a `keyof T` — has no shape of its own, and its
            // value is whatever its BASE CONSTRAINT admits: `unknown` (erased to
            // `Any?`) unless the constraint says more. `-core` typed these `any`.
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsIndexedAccess) ||
                has(com.xemantic.typescript.tsgo.checker.TypeFlagsConditional) ||
                has(com.xemantic.typescript.tsgo.checker.TypeFlagsSubstitution) ||
                has(com.xemantic.typescript.tsgo.checker.TypeFlagsIndex) ->
                checker.getBaseConstraintOfType(t)?.takeIf { it !== t }?.let { translate(it) } ?: unknownType
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsObject) -> objectType(t)
            // A string-valued type the backend erases as a `string`: a fresh
            // instance, so the singleton's rendering is never overwritten.
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsTemplateLiteral) ||
                has(com.xemantic.typescript.tsgo.checker.TypeFlagsStringMapping) ->
                Type.Intrinsic(TypeFlags.String, "string")
            // An enum whose members are not all literals: no union to read, so it
            // is the enum's own object type, which is what `-core` answered and
            // what the lowering's enum erasure keys on.
            has(com.xemantic.typescript.tsgo.checker.TypeFlagsEnum) ->
                Type.Object(TypeFlags.Object).also { o -> t.symbol()?.let { s -> pending.add { o.symbol = translateSymbol(s) } } }
            // Index, indexed access, conditional, substitution: generic types the
            // lowering refuses to map, as it refused `-core`'s.
            else -> Type.Intrinsic(coreTypeFlags(f), GoString.toUtf16(checker.typeToString(t)))
        }
    }

    private fun members(t: TsgoType): List<Type> {
        val list = t.types()
        return List(list.len) { translate(list[it]!!) }
    }

    /**
     * An intersection, without its EMPTY object members: `X & {}` is how
     * TypeScript spells `NonNullable<X>` for a generic `X` (`evt!`), and `{}`
     * constrains a value to be non-nullish — it says nothing about its shape.
     * Kept, it would read as a property bag the value is not (a `string` cast
     * to `JsObject` at run time — measured on mitt's `handler(evt!)`).
     */
    private fun intersection(t: TsgoType): Type {
        val list = t.types()
        val kept = (0 until list.len).map { list[it]!! }.filterNot { isEmptyObject(it) }
        if (kept.size == 1) return translate(kept[0])
        return Type.Intersection(kept.ifEmpty { (0 until list.len).map { list[it]!! } }.map { translate(it) })
    }

    private fun isEmptyObject(t: TsgoType): Boolean =
        t.flags().value and com.xemantic.typescript.tsgo.checker.TypeFlagsObject.value != 0u &&
            checker.getPropertiesOfType(t).len == 0 &&
            checker.getSignaturesOfType(t, SignatureKindCall).len == 0 &&
            checker.getSignaturesOfType(t, SignatureKindConstruct).len == 0 &&
            checker.getIndexInfosOfType(t).len == 0

    /** A union, with tsgo's `false | true` pair read back as `-core`'s single `boolean`. */
    private fun union(t: TsgoType): Type {
        val members = members(t)
        val hasTrue = members.any { it === trueType }
        val hasFalse = members.any { it === falseType }
        if (!hasTrue || !hasFalse) return Type.Union(members)
        val collapsed = mutableListOf<Type>()
        for (member in members) {
            if (member === trueType || member === falseType) {
                if (booleanType !in collapsed) collapsed.add(booleanType)
            } else {
                collapsed.add(member)
            }
        }
        return collapsed.singleOrNull() ?: Type.Union(collapsed)
    }

    private fun objectType(t: TsgoType): Type {
        val of = t.objectFlags().value
        val isReference = of and com.xemantic.typescript.tsgo.checker.ObjectFlagsReference.value != 0u
        if (isReference) {
            val target = t.target()
            val targetFlags = target?.objectFlags()?.value ?: 0u
            if (targetFlags and com.xemantic.typescript.tsgo.checker.ObjectFlagsTuple.value != 0u) {
                val tuple = Type.Object()
                pending.add {
                    val arguments = checker.getTypeArguments(t)
                    val count = target.asTupleType()?.elementInfos?.len ?: arguments.len
                    tuple.tupleElementTypes = List(minOf(count, arguments.len)) { translate(arguments[it]!!) }
                }
                return tuple
            }
            if (target != null && target !== t) {
                val reference = Type.Reference(translate(target) as? Type.Interface ?: return opaqueObject(t))
                pending.add {
                    val arguments = checker.getTypeArguments(t)
                    val count = target.asInterfaceType()?.allTypeParameters?.len ?: arguments.len
                    reference.resolvedTypeArguments = List(minOf(count, arguments.len)) { translate(arguments[it]!!) }
                    fillSignatures(t, reference)
                }
                return reference
            }
        }
        if (of and com.xemantic.typescript.tsgo.checker.ObjectFlagsClassOrInterface.value != 0u) {
            val declared = Type.Interface()
            t.symbol()?.let { s -> pending.add { declared.symbol = translateSymbol(s) } }
            pending.add { fillSignatures(t, declared) }
            return declared
        }
        return opaqueObject(t)
    }

    /** An anonymous object type — a literal, a function type, a mapped type — with its symbol. */
    private fun opaqueObject(t: TsgoType): Type.Object {
        val anonymous = Type.Object()
        t.symbol()?.let { s -> pending.add { anonymous.symbol = translateSymbol(s) } }
        pending.add { fillSignatures(t, anonymous) }
        return anonymous
    }

    private fun fillSignatures(t: TsgoType, core: Type.Object) {
        val calls = checker.getSignaturesOfType(t, SignatureKindCall)
        if (calls.len > 0) core.callSignatures = List(calls.len) { translateSignature(calls[it]!!) }
        val constructs = checker.getSignaturesOfType(t, SignatureKindConstruct)
        if (constructs.len > 0) core.constructSignatures = List(constructs.len) { translateSignature(constructs[it]!!) }
    }

    private fun translateSignature(s: TsgoSignature): Signature {
        signatures[s]?.let { return it }
        val core = Signature(
            declaration = coreNode(s.declaration),
            parameters = List(s.parameters.len) { translateSymbol(s.parameters[it]!!) },
            minArgumentCount = s.minArgumentCount,
        )
        signatures[s] = core
        pending.add { core.resolvedReturnType = checker.getReturnTypeOfSignature(s)?.let { translate(it) } }
        return core
    }

    /** A signature like [s] but with NO declaration — see [constructionOf]. */
    fun withoutDeclaration(s: Signature): Signature =
        Signature(null, s.typeParameters, s.parameters, s.resolvedReturnType, s.minArgumentCount)

    private fun translateSymbol(s: TsgoSymbol): Symbol {
        symbols[s]?.let { return it }
        val core = Symbol(coreSymbolFlags(s.flags.value), GoString.toUtf16(s.name))
        symbols[s] = core
        pending.add {
            for (i in 0 until s.declarations.len) coreNode(s.declarations[i])?.let { core.declarations.add(it) }
            core.valueDeclaration = coreNode(s.valueDeclaration)
            // A namespace the PROGRAM declares has its exports read by the
            // lowering (`ns.member`); a library namespace never does, and
            // translating `Intl`'s would be most of the lib.
            val module = s.flags.value and com.xemantic.typescript.tsgo.ast.SymbolFlagsModule.value != 0u
            if (module && core.declarations.any { it !is EmptyStatement }) {
                val exports = LinkedHashMap<String, Symbol>()
                s.exports.entriesSnapshot().forEach { (name, export) ->
                    if (export != null) exports[GoString.toUtf16(name)] = translateSymbol(export)
                }
                core.exports = exports
            }
        }
        return core
    }

    private companion object {

        val typeFlagPairs = listOf(
            com.xemantic.typescript.tsgo.checker.TypeFlagsAny to TypeFlags.Any,
            com.xemantic.typescript.tsgo.checker.TypeFlagsUnknown to TypeFlags.Unknown,
            com.xemantic.typescript.tsgo.checker.TypeFlagsString to TypeFlags.String,
            com.xemantic.typescript.tsgo.checker.TypeFlagsNumber to TypeFlags.Number,
            com.xemantic.typescript.tsgo.checker.TypeFlagsBoolean to TypeFlags.Boolean,
            com.xemantic.typescript.tsgo.checker.TypeFlagsEnum to TypeFlags.Enum,
            com.xemantic.typescript.tsgo.checker.TypeFlagsBigInt to TypeFlags.BigInt,
            com.xemantic.typescript.tsgo.checker.TypeFlagsStringLiteral to TypeFlags.StringLiteral,
            com.xemantic.typescript.tsgo.checker.TypeFlagsNumberLiteral to TypeFlags.NumberLiteral,
            com.xemantic.typescript.tsgo.checker.TypeFlagsBooleanLiteral to TypeFlags.BooleanLiteral,
            com.xemantic.typescript.tsgo.checker.TypeFlagsEnumLiteral to TypeFlags.EnumLiteral,
            com.xemantic.typescript.tsgo.checker.TypeFlagsBigIntLiteral to TypeFlags.BigIntLiteral,
            com.xemantic.typescript.tsgo.checker.TypeFlagsESSymbol to TypeFlags.ESSymbol,
            com.xemantic.typescript.tsgo.checker.TypeFlagsUniqueESSymbol to TypeFlags.UniqueESSymbol,
            com.xemantic.typescript.tsgo.checker.TypeFlagsVoid to TypeFlags.Void,
            com.xemantic.typescript.tsgo.checker.TypeFlagsUndefined to TypeFlags.Undefined,
            com.xemantic.typescript.tsgo.checker.TypeFlagsNull to TypeFlags.Null,
            com.xemantic.typescript.tsgo.checker.TypeFlagsNever to TypeFlags.Never,
            com.xemantic.typescript.tsgo.checker.TypeFlagsTypeParameter to TypeFlags.TypeParameter,
            com.xemantic.typescript.tsgo.checker.TypeFlagsObject to TypeFlags.Object,
            com.xemantic.typescript.tsgo.checker.TypeFlagsUnion to TypeFlags.Union,
            com.xemantic.typescript.tsgo.checker.TypeFlagsIntersection to TypeFlags.Intersection,
            com.xemantic.typescript.tsgo.checker.TypeFlagsIndex to TypeFlags.Index,
            com.xemantic.typescript.tsgo.checker.TypeFlagsIndexedAccess to TypeFlags.IndexedAccess,
            com.xemantic.typescript.tsgo.checker.TypeFlagsConditional to TypeFlags.Conditional,
            com.xemantic.typescript.tsgo.checker.TypeFlagsSubstitution to TypeFlags.Substitution,
            com.xemantic.typescript.tsgo.checker.TypeFlagsNonPrimitive to TypeFlags.NonPrimitive,
            com.xemantic.typescript.tsgo.checker.TypeFlagsTemplateLiteral to TypeFlags.TemplateLiteral,
            com.xemantic.typescript.tsgo.checker.TypeFlagsStringMapping to TypeFlags.StringMapping,
        )

        /** tsgo reordered `TypeFlags` (TypeScript 7), so the bits are matched by NAME. */
        fun coreTypeFlags(value: UInt): TypeFlags {
            var out = TypeFlags.None
            for ((tsgo, core) in typeFlagPairs) if (value and tsgo.value != 0u) out = out or core
            return out
        }

        val symbolFlagPairs = listOf(
            com.xemantic.typescript.tsgo.ast.SymbolFlagsFunctionScopedVariable to SymbolFlags.FunctionScopedVariable,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsBlockScopedVariable to SymbolFlags.BlockScopedVariable,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsProperty to SymbolFlags.Property,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsEnumMember to SymbolFlags.EnumMember,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsFunction to SymbolFlags.Function,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsClass to SymbolFlags.Class,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsInterface to SymbolFlags.Interface,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsConstEnum to SymbolFlags.ConstEnum,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsRegularEnum to SymbolFlags.RegularEnum,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsValueModule to SymbolFlags.ValueModule,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsNamespaceModule to SymbolFlags.NamespaceModule,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsTypeAlias to SymbolFlags.TypeAlias,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsAlias to SymbolFlags.Alias,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsExportValue to SymbolFlags.ExportValue,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsMethod to SymbolFlags.Method,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsGetAccessor to SymbolFlags.GetAccessor,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsSetAccessor to SymbolFlags.SetAccessor,
            com.xemantic.typescript.tsgo.ast.SymbolFlagsTypeParameter to SymbolFlags.TypeParameter,
        )

        /** `-core`'s `SymbolFlags` numbering is its own, so these are matched by NAME too. */
        fun coreSymbolFlags(value: UInt): SymbolFlags {
            var out = SymbolFlags.None
            for ((tsgo, core) in symbolFlagPairs) if (value and tsgo.value != 0u) out = out or core
            return out
        }
    }

}

/**
 * Asks the ported checker every question the `-core` sink answered during its
 * walk, for every program file, and writes the answers into [facts].
 */
internal class TsgoFactsBuilder(
    private val checker: TsgoChecker,
    private val maps: List<FileNodeMap>,
    private val facts: CheckedFacts,
) {

    private val translator = TsgoTranslator(checker, maps.associateBy { it.tsgo }, facts)

    /** Questions tsgo answered with a panic, which the `-core` sink would have left unrecorded too. */
    var panics: Int = 0
        private set

    fun build() {
        for (map in maps) {
            facts.putFile(map.core)
            forEachChild(map.core) { visit(it, map) }
        }
    }

    private fun visit(node: Node, map: FileNodeMap) {
        // A TYPE annotation is syntax the lowering never asks the checker
        // about (a heritage clause's `extends Base` is not a `TypeNode` here).
        if (node is TypeNode) return
        val tsgo = map.tsgoOf(node)
        if (tsgo != null) ask { record(node, tsgo, map) }
        forEachChild(node) { visit(it, map) }
    }

    /**
     * The program's resolved import edges, (importer, imported), in file and
     * statement order — what `-core`'s crawl recorded and what the lowering
     * orders module initialization by. An edge to a file outside the program
     * (a library, an unresolved specifier) is dropped.
     */
    fun importEdges(): List<Pair<String, String>> {
        val byFile = maps.associateBy { it.tsgo }
        val edges = mutableListOf<Pair<String, String>>()
        for (map in maps) {
            for (statement in map.core.statements) {
                val specifier = when (statement) {
                    is com.xemantic.typescript.compiler.ImportDeclaration -> statement.moduleSpecifier
                    is com.xemantic.typescript.compiler.ExportDeclaration -> statement.moduleSpecifier
                    is com.xemantic.typescript.compiler.ImportEqualsDeclaration ->
                        (statement.moduleReference as? com.xemantic.typescript.compiler.ExternalModuleReference)?.expression
                    else -> null
                } ?: continue
                val tsgo = map.tsgoOf(specifier) ?: continue
                ask {
                    val module = checker.getSymbolAtLocation(tsgo) ?: return@ask
                    val file = module.valueDeclaration?.let {
                        com.xemantic.typescript.tsgo.ast.getSourceFileOfNode(it)
                    } ?: return@ask
                    byFile[file]?.let { edges.add(map.core.fileName to it.core.fileName) }
                }
            }
        }
        return edges
    }

    private inline fun ask(block: () -> Unit) {
        try {
            block()
        } catch (_: GoPanic) {
            panics++
        }
    }

    private fun record(node: Node, tsgo: TsgoNode, map: FileNodeMap) {
        if (node is Expression) {
            translator.type(checker.getTypeAtLocation(tsgo))?.let { facts.putType(node, it) }
        }
        when (node) {
            is CallExpression -> ask { facts.putCall(node, callFact(node, tsgo, map)) }
            is NewExpression -> ask { facts.putConstruction(node, constructionOf(node, tsgo, map)) }
            is PropertyAccessExpression -> ask {
                map.tsgoOf(node.name)?.let { name -> checker.getSymbolAtLocation(name) }
                    ?.let { translator.symbol(it) }
                    ?.let { facts.putMember(node, it) }
            }
            is Identifier -> ask { recordName(node, tsgo) }
            is Parameter -> ask {
                val type = tsgo.symbol()?.let { checker.getTypeOfSymbol(it) } ?: checker.getTypeAtLocation(tsgo)
                translator.type(type)?.let { facts.putParameterType(node, it) }
            }
            is FunctionDeclaration -> ask { recordFirstSignature(node, tsgo) }
            is MethodDeclaration -> ask { recordFirstSignature(node, tsgo) }
            is PropertyDeclaration, is GetAccessor, is SetAccessor -> ask {
                val name = tsgo.name() ?: return@ask
                translator.type(checker.getTypeAtLocation(name))?.let { facts.putMemberType(node, it) }
            }
            else -> {}
        }
    }

    /**
     * A function's or method's signature, as `-core` recorded it: the FIRST
     * call signature of the declaration's own type — so an implementation
     * behind overloads reports the first OVERLOAD, which is the return type the
     * lowering has always erased the shared IR function to.
     */
    private fun recordFirstSignature(node: Node, tsgo: TsgoNode) {
        val name = tsgo.name() ?: return
        val type = checker.getTypeAtLocation(name) ?: return
        val calls = checker.getSignaturesOfType(type, SignatureKindCall)
        if (calls.len == 0) return
        translator.signature(calls[0])?.let { facts.putSignature(node, it) }
    }

    private fun recordName(node: Identifier, tsgo: TsgoNode) {
        if (node.text in KEYWORD_EXPRESSIONS) return
        // `{ Cls }` — the name of a SHORTHAND member is the member to
        // `getSymbolAtLocation`; the VALUE it reads, which is the free name
        // `-core` resolved, is the shorthand's value symbol.
        val parent = tsgo.parent
        val symbol = if (parent?.kind == com.xemantic.typescript.tsgo.ast.KindShorthandPropertyAssignment) {
            checker.getShorthandAssignmentValueSymbol(parent)
        } else {
            checker.getSymbolAtLocation(tsgo)
        } ?: return
        val isAlias = symbol.flags.value and com.xemantic.typescript.tsgo.ast.SymbolFlagsAlias.value != 0u
        // An IMPORTED name is recorded as what it NAMES, not as the alias: the
        // backend reaches its generated declaration through this symbol.
        val named = if (isAlias) checker.resolveAlias(symbol).first ?: return else symbol
        val core = translator.symbol(named) ?: return
        facts.putName(node, core)
        val module = named.flags.value and com.xemantic.typescript.tsgo.ast.SymbolFlagsModule.value != 0u
        if (isAlias && module) namespaceExports(named)?.let { facts.putNamespaceExports(node, it) }
    }

    /**
     * A namespace import's export table, keyed by the name an IMPORTER sees —
     * tsgo's `getExportsOfModule` follows stars, re-keys renames and drops a
     * star's `default`, which is exactly what `-core` had to build by hand.
     * Null for an `export =` module, whose "exports" are a value's members.
     */
    /**
     * One export table per MODULE, by identity: the lowering builds one
     * namespace object per table (`namespaceObjectFor` keys an identity map
     * by it), and `ns === ns` holds only if every mention of `ns` hands it
     * the same table — measured, a table per mention printed `false`.
     */
    private val namespaceTables = IdentityHashMap<TsgoSymbol, Map<String, com.xemantic.typescript.compiler.Symbol>?>()

    private fun namespaceExports(module: TsgoSymbol): Map<String, com.xemantic.typescript.compiler.Symbol>? =
        if (module in namespaceTables) namespaceTables[module]
        else buildNamespaceExports(module).also { namespaceTables[module] = it }

    private fun buildNamespaceExports(module: TsgoSymbol): Map<String, com.xemantic.typescript.compiler.Symbol>? {
        val exports = checker.getExportsOfModule(module)
        val byName = LinkedHashMap<String, TsgoSymbol>()
        for (i in 0 until exports.len) {
            val export = exports[i] ?: continue
            val name = GoString.toUtf16(export.name)
            if (name == "export=") return null
            byName[name] = export
        }
        val out = LinkedHashMap<String, com.xemantic.typescript.compiler.Symbol>()
        for (name in exportOrder(module, HashSet()) + byName.keys) {
            if (name in out) continue
            val export = byName[name] ?: continue
            val isAlias = export.flags.value and com.xemantic.typescript.tsgo.ast.SymbolFlagsAlias.value != 0u
            val target = if (isAlias) checker.resolveAlias(export).first ?: continue else export
            translator.symbol(target)?.let { out[name] = it }
        }
        return out
    }

    /**
     * The ORDER a namespace object enumerates its exports in, as `-core`
     * built it: a module's `export * from` targets first (recursively, in
     * statement order, never their `default`), then its own exports in
     * declaration order — the set and the names are tsgo's, only the order is
     * fixed here, because tsgo's export table is a map.
     */
    private fun exportOrder(module: TsgoSymbol, visiting: MutableSet<TsgoSymbol>): List<String> {
        if (!visiting.add(module)) return emptyList()
        val file = module.valueDeclaration?.let { com.xemantic.typescript.tsgo.ast.getSourceFileOfNode(it) }
        val map = maps.firstOrNull { it.tsgo === file } ?: return emptyList()
        val names = mutableListOf<String>()
        for (statement in map.core.statements) {
            val star = statement as? com.xemantic.typescript.compiler.ExportDeclaration ?: continue
            if (star.exportClause != null) continue
            val specifier = star.moduleSpecifier?.let { map.tsgoOf(it) } ?: continue
            val target = checker.getSymbolAtLocation(specifier) ?: continue
            names += exportOrder(target, visiting).filter { it != "default" }
        }
        names += module.exports.entriesSnapshot()
            .mapNotNull { (name, symbol) -> symbol?.let { name to it } }
            .sortedBy { (_, symbol) ->
                (0 until symbol.declarations.len).minOfOrNull { symbol.declarations[it]?.pos() ?: Int.MAX_VALUE }
                    ?: Int.MAX_VALUE
            }
            .map { (name, _) -> GoString.toUtf16(name) }
            .filter { it != "export=" && it != "__export" }
        return names
    }

    private fun callFact(node: CallExpression, tsgo: TsgoNode, map: FileNodeMap): CallFact {
        val callee = node.expression
        val calleeType = map.tsgoOf(callee)?.let { checker.getTypeAtLocation(it) }
        val signatures = calleeType?.let { checker.getSignaturesOfType(checker.getApparentType(it), SignatureKindCall) }
        val count = signatures?.len ?: 0
        val selected = if (count == 0) null else translator.signature(checker.getResolvedSignature(tsgo))
        var receiverTypeText: String? = null
        var memberName: String? = null
        if (callee is PropertyAccessExpression) {
            memberName = callee.name.text
            val receiver = map.tsgoOf(callee.expression)?.let { checker.getTypeAtLocation(it) }
            receiverTypeText = translator.type(receiver)?.let { facts.render(it) }
        }
        return CallFact(selected, count, receiverTypeText, memberName)
    }

    /**
     * The construct signature `new` resolved to — or null where the callee
     * offers none (`any`), which is what sends the lowering to a dynamic `new`.
     *
     * A DERIVED class that declares no constructor inherits its base's
     * signatures, declarations included, so `new B(1)` resolves to `A`'s
     * `constructor`. The lowering reads the class to build off the signature's
     * declaration, so such a signature is handed over WITHOUT one, and the
     * lowering takes the class from the callee instead — the shape `-core`'s
     * implicit constructor signature had.
     */
    private fun constructionOf(node: NewExpression, tsgo: TsgoNode, map: FileNodeMap): Signature? {
        val calleeTsgo = map.tsgoOf(node.expression) ?: return null
        val calleeType = checker.getTypeAtLocation(calleeTsgo) ?: return null
        val constructs = checker.getSignaturesOfType(checker.getApparentType(calleeType), SignatureKindConstruct)
        if (constructs.len == 0) return null
        val signature = translator.signature(checker.getResolvedSignature(tsgo)) ?: return null
        val declaredBy = (signature.declaration as? Constructor)?.parent as? ClassDeclaration
            ?: return signature
        val callee = calleeType.symbol()?.let { translator.symbol(it) }
        val calleeClass = callee?.valueDeclaration ?: callee?.declarations?.firstOrNull()
        return if (calleeClass is ClassDeclaration && calleeClass !== declaredBy) {
            translator.withoutDeclaration(signature)
        } else {
            signature
        }
    }

    private companion object {
        /** `-core` parses these as identifiers; none names a declaration. */
        val KEYWORD_EXPRESSIONS = setOf("this", "super", "true", "false", "null")
    }

}

/** The `-core` parse of [text], which is the syntax the lowering walks. */
internal fun parseForLowering(fileName: String, text: String): SourceFile {
    val options = com.xemantic.typescript.compiler.CompilerOptions()
    val flags = com.xemantic.typescript.compiler.computeParserFlags(fileName, text, options)
    return com.xemantic.typescript.compiler.Parser(
        text,
        fileName,
        forceJsx = flags.forceJsx,
        topLevelAwait = flags.topLevelAwait,
        needsJsxFlag = flags.needsJsxFlag,
        noImplicitAny = flags.noImplicitAny,
    ).parse()
}
