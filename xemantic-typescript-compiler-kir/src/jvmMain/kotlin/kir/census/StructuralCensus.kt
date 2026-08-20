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

package com.xemantic.typescript.compiler.kir.census

import com.xemantic.typescript.compiler.ArrowFunction
import com.xemantic.typescript.compiler.BinaryExpression
import com.xemantic.typescript.compiler.CallExpression
import com.xemantic.typescript.compiler.CheckedLens
import com.xemantic.typescript.compiler.CheckedNodeSink
import com.xemantic.typescript.compiler.ClassDeclaration
import com.xemantic.typescript.compiler.Constructor
import com.xemantic.typescript.compiler.Expression
import com.xemantic.typescript.compiler.FunctionDeclaration
import com.xemantic.typescript.compiler.FunctionExpression
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.InterfaceDeclaration
import com.xemantic.typescript.compiler.MethodDeclaration
import com.xemantic.typescript.compiler.NewExpression
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.ObjectLiteralExpression
import com.xemantic.typescript.compiler.Parameter
import com.xemantic.typescript.compiler.PropertyAccessExpression
import com.xemantic.typescript.compiler.PropertyDeclaration
import com.xemantic.typescript.compiler.ReturnStatement
import com.xemantic.typescript.compiler.Signature
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.Symbol
import com.xemantic.typescript.compiler.SymbolFlags
import com.xemantic.typescript.compiler.SyntaxKind
import com.xemantic.typescript.compiler.Type
import com.xemantic.typescript.compiler.TypeFlags
import com.xemantic.typescript.compiler.VariableDeclaration
import java.util.IdentityHashMap

/**
 * Where an assignability obligation came from.
 *
 * These are the VALUE positions in which TypeScript requires a source type to be
 * assignable to a target type — i.e. exactly the positions at which a nominal
 * JVM encoding would need the source's class to already `implement` the target's
 * interface. Type positions (a type argument against a constraint, a
 * `satisfies`) are deliberately absent: they are erased and impose no runtime
 * dispatch obligation.
 */
public enum class ObligationKind {
    CALL_ARGUMENT,
    NEW_ARGUMENT,
    VARIABLE_INITIALIZER,
    RETURN_VALUE,
    ASSIGNMENT,
    PROPERTY_ASSIGNMENT,
    PARAMETER_DEFAULT,
    PROPERTY_INITIALIZER,
}

/**
 * What KIND of edge one obligation is — the classification the whole measurement
 * exists to produce.
 *
 * [NOMINAL_SAME_DECL] and [NOMINAL_BASE] are reported together as "nominal" and
 * kept apart in the data because they answer different questions: the first is a
 * generic INSTANTIATION relation (`Box<string>` reaching `Box<T>`), which a JVM
 * encoding gets for free from erasure, while the second is a real declared
 * heritage edge that a generated class already carries.
 */
public enum class EdgeClass {
    /** Same [Type] instance, or same [Type.id]. Nothing to encode. */
    IDENTITY,
    /** Source and target share a DECLARATION — a generic instantiation pair. */
    NOMINAL_SAME_DECL,
    /** Target is among the source's transitive declared bases / `implements`. */
    NOMINAL_BASE,
    /** Source is a fresh object literal — the case a generated class covers. */
    FRESH_LITERAL,
    /** Assignable, and none of the above. THIS is the population that decides. */
    STRUCTURAL,
    /** Not assignable. On a clean program this is the instrument's own control. */
    NOT_ASSIGNABLE,
}

/** What the TARGET of an obligation is, shape-wise. */
public enum class TargetClass {
    PRIMITIVE,
    UNION,
    OBJECT_WITH_MEMBERS,
    FUNCTION_TYPE,
    ARRAY_OR_TUPLE,
    TYPE_PARAMETER,
    ANY_OR_UNKNOWN,
    INTERSECTION,
    /**
     * An `enum`, or one of its members.
     *
     * Split out of [OBJECT_WITH_MEMBERS] because in this compiler an enum's type
     * is a member-LESS `Type.Object` and an enum MEMBER's type is another one
     * (interned on `"<enumSymbol>#<member>"`), so without this arm every
     * `SyntaxKind.Identifier` reaching `SyntaxKind` counted as an object-to-object
     * STRUCTURAL edge — which put `SyntaxKind` at the top of the fan-in table with
     * one edge per member and inflated the design population by a third. An enum
     * member reaching its own enum is not a structural-typing problem in any
     * encoding; both sides lower to the same JVM shape.
     */
    ENUM,
    OTHER,
}

/**
 * The one place the five-dimensional histogram's index arithmetic lives.
 *
 * Shared by the collector and the report deliberately: two copies of an index
 * computation is a defect that shows up as a plausible-looking distribution.
 */
internal fun bucketIndexOf(
    kind: ObligationKind,
    edge: EdgeClass,
    targetClass: TargetClass,
    sourceClass: TargetClass,
    open: Boolean,
): Int =
    ((((kind.ordinal * EdgeClass.entries.size + edge.ordinal) *
        TargetClass.entries.size + targetClass.ordinal) *
        TargetClass.entries.size + sourceClass.ordinal) * 2) + if (open) 1 else 0

/** Bit positions of the "the nominal encoding cannot express this" flags. */
private const val CANNOT_INDEX_SIGNATURE = 1
private const val CANNOT_CALL_SIGNATURE = 2
private const val CANNOT_CONSTRUCT_SIGNATURE = 4
private const val CANNOT_OPTIONAL_PROPERTY = 8
private const val CANNOT_UNION_OF_OBJECTS = 16
private const val CANNOT_GENERIC_INSTANTIATION = 32

/**
 * A distinct type, as the census counts them.
 *
 * The key is the DECLARATION where there is one — a `Type.Reference`'s target
 * symbol, an interface's own symbol — because that, and not `Type.id`, is what
 * an `implements` edge would be written against: `Box<string>` and `Box<number>`
 * are two `Type`s and one generated interface. Types with no symbol (a type
 * literal, a union, an anonymous object type) key on their own id, so two
 * structurally identical anonymous types are one entry exactly when the checker
 * interned them into one `Type`.
 */
public class CensusType internal constructor(
    public val index: Int,
    public val text: String,
    public val targetClass: TargetClass,
    /** Bitset of the [CANNOT_INDEX_SIGNATURE] family. */
    internal var cannotFlags: Int,
    /** True when this key names a declared `interface`/`class`, not an anonymous type. */
    public val isDeclared: Boolean,
    /**
     * True when the type MENTIONS a type parameter anywhere reachable — `T`,
     * `T | undefined`, `readonly T[]`, `(value: T) => boolean`.
     *
     * Load-bearing for reading the census. The sink is handed the signature
     * overload resolution SELECTED, and a selected signature is not an
     * INSTANTIATED one, so an obligation whose target still mentions `T` is one
     * whose target the census got wrong: the checker compared the argument
     * against the parameter type instantiated with the inferred arguments, and
     * this compared it against the open one. Such obligations are counted, kept
     * visible as "open", and excluded from every conclusion.
     */
    public val mentionsTypeParameter: Boolean,
) {
    public val hasIndexSignature: Boolean get() = cannotFlags and CANNOT_INDEX_SIGNATURE != 0
    public val hasCallSignature: Boolean get() = cannotFlags and CANNOT_CALL_SIGNATURE != 0
    public val hasConstructSignature: Boolean get() = cannotFlags and CANNOT_CONSTRUCT_SIGNATURE != 0
    public val hasOptionalProperty: Boolean get() = cannotFlags and CANNOT_OPTIONAL_PROPERTY != 0
    public val isUnionOfObjects: Boolean get() = cannotFlags and CANNOT_UNION_OF_OBJECTS != 0

    /** An object type with members — the only shape a generated interface names. */
    public val isObjectish: Boolean get() = targetClass == TargetClass.OBJECT_WITH_MEMBERS
    public val isGenericInstantiation: Boolean get() = cannotFlags and CANNOT_GENERIC_INSTANTIATION != 0
}

/** One recorded obligation, kept only for the report's worked examples. */
public class CensusExample internal constructor(
    public val kind: ObligationKind,
    public val edge: EdgeClass,
    public val sourceText: String,
    public val targetText: String,
    public val file: String,
    public val pos: Int,
)

/**
 * The measurement: how much of TypeScript's STRUCTURAL assignability a real
 * program actually demands, and in what shape.
 *
 * The design question this exists to answer is stated in `docs/kir-design.md`
 * § 3.3: a JVM encoding of TypeScript's structural types can be nominal — every
 * generated class `implements` every generated interface it structurally
 * satisfies — only if that closure is small enough to build. Nobody knows
 * whether it is, because the theoretical closure is N x M over every
 * (class, interface) pair in the program and nobody had measured the pairs that
 * ACTUALLY OCCUR.
 *
 * They are exactly the pairs the checker forms while checking, which is why this
 * is a [CheckedNodeSink] and not an offline analysis. Two properties follow and
 * both are load-bearing:
 *
 * - Every classification is done EAGERLY, inside the callback. A [CheckedLens]
 *   is valid only for the duration of the call that received it, so a census
 *   that stored `Type` pairs and asked `isAssignableTo` afterwards would be
 *   asking a checker whose walk-scoped ambient is gone.
 * - The obligation is detected by looking UP from the source expression at its
 *   PARENT, not down from the enclosing construct. The spine walks preorder, so
 *   a construct is visited before its own operands; looking up is the only
 *   direction in which the answer is available when it is needed.
 *
 * This is a MEASUREMENT TOOL and shares no code with the lowering. It must never
 * become a code path: what it computes is a distribution, and a distribution is
 * not an oracle.
 */
public class StructuralCensus : CheckedNodeSink {

    // ---- the distinct-type table -------------------------------------------

    private val keyToIndex = HashMap<Long, Int>()
    private val types = ArrayList<CensusType>()

    // ---- aggregate counters ------------------------------------------------

    // kind x edge x targetClass x sourceClass x openGeneric. Five dimensions
    // rather than three because two of them decide how the other three are read:
    // the SOURCE class (a `Double` reaching an interface is not the design's
    // problem; a generated class reaching one is) and whether the obligation's
    // target is still OPEN in a type parameter (see [CensusType.mentionsTypeParameter]).
    private val bucketCounts = LongArray(
        ObligationKind.entries.size * EdgeClass.entries.size * TargetClass.entries.size *
            TargetClass.entries.size * 2,
    )

    /** Distinct (source, target) pairs, keyed by the DECLARATION-level index. */
    private val distinctPairs = HashSet<Long>()

    /** Distinct (source, target) pairs, keyed by raw `Type.id` — the finer count. */
    private val distinctRawPairs = HashSet<Long>()

    /** Distinct STRUCTURAL pairs only: the size of the dispatch problem. */
    private val structuralPairs = HashSet<Long>()

    /**
     * Distinct STRUCTURAL pairs restricted to the population the DESIGN is about:
     * an object-ish source reaching an object-ish, fully-concrete target. This is
     * the number of `implements` edges a whole-program closure would actually have
     * to add, and the number of itabs a Go-style implementation would build.
     */
    private val designPairs = HashSet<Long>()

    /** [designPairs]' fan-in and fan-out, target index -> sources and back. */
    private val designFanIn = HashMap<Int, MutableSet<Int>>()
    private val designFanOut = HashMap<Int, MutableSet<Int>>()

    /** target index -> the distinct source indices that reach it STRUCTURALLY. */
    private val structuralFanIn = HashMap<Int, MutableSet<Int>>()

    /** source index -> the distinct target indices it must satisfy STRUCTURALLY. */
    private val structuralFanOut = HashMap<Int, MutableSet<Int>>()

    /** As above but over EVERY assignable edge, nominal and identity included. */
    private val anyFanIn = HashMap<Int, MutableSet<Int>>()

    private val examples = ArrayList<CensusExample>()
    private val exampleBuckets = HashSet<Int>()

    private var obligationsRecorded = 0L
    private var expressionsSeen = 0L
    private var objectLiteralsSeen = 0L
    private var objectLiteralsWithTarget = 0L

    /** Obligation SITES found whose target type could not be derived. */
    private var targetUnavailable = 0L
    private val targetUnavailableByKind = LongArray(ObligationKind.entries.size)
    private var skippedRestOrMisalignedSignature = 0L
    private var callWithNoSignature = 0L
    private var argumentBeyondParameterList = 0L
    private var lensFailures = 0L

    private var nominalViaBaseTypes = 0L
    private var nominalViaImplements = 0L
    private var heritageNamesUnresolved = 0L

    private val filesSeen = HashSet<String>()
    private val obligationsByFile = HashMap<String, Long>()

    // ---- walk-scoped side tables -------------------------------------------

    /** The overload chosen at a call, recorded at the CALL and read at its arguments. */
    private val callSignature = IdentityHashMap<Node, Signature?>()

    /** A function-like node's DECLARED return type, recorded before its body walks. */
    private val declaredReturnType = IdentityHashMap<Node, Type>()

    /** Memoised nominal verdicts, keyed by the packed (source, target) index pair. */
    private val nominalMemo = HashMap<Long, Boolean>()

    /** Memoised declared-base closures, keyed by type index. */
    private val baseClosure = HashMap<Int, Set<Int>>()

    // ------------------------------------------------------------------------

    override fun expression(node: Expression, lens: CheckedLens) {
        expressionsSeen++
        try {
            when (node) {
                // A call's chosen overload must be taken HERE: overload selection is
                // neither memoised nor pure (it derives argument types through
                // walk-scoped state), and the arguments are visited AFTER the call.
                is CallExpression -> if (node !in callSignature) {
                    val signatures = lens.callSignatures(node.expression)
                    callSignature[node] =
                        lens.selectOverload(signatures, node.arguments) ?: signatures.singleOrNull()
                }
                is NewExpression -> if (node !in callSignature) {
                    val signatures = lens.constructSignatures(node)
                    callSignature[node] =
                        lens.selectOverload(signatures, node.arguments ?: emptyList())
                            ?: signatures.singleOrNull()
                }
                // An arrow / function EXPRESSION is an expression, so its declared
                // return type is recorded here — before its body is walked, which is
                // what makes a `return` inside it find one.
                is ArrowFunction -> if (node.type != null) recordReturnType(node, lens.typeOf(node))
                is FunctionExpression ->
                    if (node.type != null) recordReturnType(node, lens.typeOf(node))
                is ObjectLiteralExpression -> objectLiteralsSeen++
                else -> {}
            }
            recordObligationAt(node, lens)
        } catch (_: Exception) {
            lensFailures++
        }
    }

    override fun declaration(node: Node, lens: CheckedLens) {
        try {
            when (node) {
                is FunctionDeclaration -> if (node.type != null && node.name != null) {
                    lens.callSignatures(node.name!!).firstOrNull()?.resolvedReturnType
                        ?.let { declaredReturnType[node] = it }
                }
                is MethodDeclaration -> if (node.type != null) {
                    methodSignature(node, lens)?.resolvedReturnType
                        ?.let { declaredReturnType[node] = it }
                }
                else -> {}
            }
        } catch (_: Exception) {
            lensFailures++
        }
    }

    private fun recordReturnType(node: Node, functionType: Type) {
        (functionType as? Type.Object)?.callSignatures?.firstOrNull()?.resolvedReturnType
            ?.let { declaredReturnType[node] = it }
    }

    /**
     * The signature of a METHOD, reached through the enclosing class's own type.
     *
     * Not through the method's own name node: a member name is bound by no scope,
     * so a free-name query there resolves the SPELLING and answers about whatever
     * unrelated binding shares it.
     */
    private fun methodSignature(node: MethodDeclaration, lens: CheckedLens): Signature? {
        val name = node.name as? Identifier ?: return null
        val owner = (node as NodeBase).parent as? ClassDeclaration ?: return null
        val ownerName = owner.name ?: return null
        val classSymbol = lens.resolveName(ownerName.text) ?: return null
        val member = lens.membersOf(lens.declaredTypeOfSymbol(classSymbol), name.text).firstOrNull()
            ?: return null
        return (lens.typeOfSymbol(member) as? Type.Object)?.callSignatures?.firstOrNull()
    }

    // ---- obligation detection ----------------------------------------------

    /**
     * If [node] sits in a value position with a declared target type, classify the
     * edge and fold it into the aggregates.
     *
     * Looking UP at the parent rather than down from the construct is not a style
     * choice: the spine walks preorder, so at a `CallExpression` the argument types
     * are not yet known, and at an argument the call's chosen overload IS — because
     * it was recorded one visit earlier.
     */
    private fun recordObligationAt(node: Expression, lens: CheckedLens) {
        val parent = (node as NodeBase).parent ?: return
        val kind: ObligationKind
        val target: Type?
        // Whether the target came from a signature carrying its OWN type parameters.
        // Overload selection returns the signature it CHOSE, not one instantiated
        // with the inferred arguments, so such a target is open in `T` and the
        // comparison below is not the comparison the checker made.
        var signatureGeneric = false
        when (parent) {
            is CallExpression -> {
                val index = parent.arguments.indexOfFirst { it === node }
                if (index < 0) return
                kind = ObligationKind.CALL_ARGUMENT
                val signature = callSignature[parent]
                signatureGeneric = !signature?.typeParameters.isNullOrEmpty()
                target = parameterTypeAt(signature, index, lens)
            }
            is NewExpression -> {
                val index = parent.arguments?.indexOfFirst { it === node } ?: -1
                if (index < 0) return
                kind = ObligationKind.NEW_ARGUMENT
                val signature = callSignature[parent]
                signatureGeneric = !signature?.typeParameters.isNullOrEmpty()
                target = parameterTypeAt(signature, index, lens)
            }
            is VariableDeclaration -> {
                // Only an ANNOTATED declaration imposes an obligation. Without an
                // annotation the declared type IS the initializer's widened type, so
                // the pair is identity by construction and would inflate the census.
                if (parent.initializer !== node || parent.type == null) return
                kind = ObligationKind.VARIABLE_INITIALIZER
                val name = parent.name as? Identifier ?: return
                target = lens.resolveName(name.text)?.let { lens.typeOfSymbol(it) }
            }
            is ReturnStatement -> {
                if (parent.expression !== node) return
                kind = ObligationKind.RETURN_VALUE
                target = enclosingDeclaredReturnType(parent)
            }
            is Parameter -> {
                if (parent.initializer !== node || parent.type == null) return
                kind = ObligationKind.PARAMETER_DEFAULT
                val name = parent.name as? Identifier ?: return
                target = lens.resolveName(name.text)?.let { lens.typeOfSymbol(it) }
            }
            is PropertyDeclaration -> {
                if (parent.initializer !== node || parent.type == null) return
                kind = ObligationKind.PROPERTY_INITIALIZER
                target = propertyDeclaredType(parent, lens)
            }
            is BinaryExpression -> {
                if (parent.operator != SyntaxKind.Equals || parent.right !== node) return
                when (val left = parent.left) {
                    is PropertyAccessExpression -> {
                        kind = ObligationKind.PROPERTY_ASSIGNMENT
                        target = lens.membersOf(lens.typeOf(left.expression), left.name.text)
                            .firstOrNull()?.let { lens.typeOfSymbol(it) }
                    }
                    is Identifier -> {
                        kind = ObligationKind.ASSIGNMENT
                        target = lens.resolveName(left.text)?.let { lens.typeOfSymbol(it) }
                    }
                    else -> return
                }
            }
            else -> return
        }
        if (target == null) {
            targetUnavailable++
            targetUnavailableByKind[kind.ordinal]++
            return
        }
        classify(kind, lens.typeOf(node), target, node, signatureGeneric, lens)
    }

    /**
     * The parameter type at [index] of [signature] — refusing where a positional
     * read is unsound.
     *
     * `Signature.parameters` is NOT the declaration's parameter list: the builder
     * drops every binding-pattern parameter and then zips the declaration's type
     * annotations onto the SURVIVORS positionally, so for `f({a}: O, b: string)`
     * the one surviving symbol is named `b` and typed `O`. A census that indexed
     * into it regardless would record a wrong target with no way to notice, so a
     * signature whose two lists disagree in length is REFUSED and counted.
     */
    private fun parameterTypeAt(signature: Signature?, index: Int, lens: CheckedLens): Type? {
        if (signature == null) {
            callWithNoSignature++
            return null
        }
        val declared = declarationParameters(signature.declaration)
        if (declared != null && declared.size != signature.parameters.size) {
            skippedRestOrMisalignedSignature++
            return null
        }
        if (declared != null && index < declared.size && declared[index].dotDotDotToken) {
            // A rest parameter's declared type is the ARRAY, not the element type the
            // argument is judged against; modelling that is not what this measures.
            skippedRestOrMisalignedSignature++
            return null
        }
        val parameter = signature.parameters.getOrNull(index)
        if (parameter == null) {
            argumentBeyondParameterList++
            return null
        }
        return lens.typeOfSymbol(parameter)
    }

    private fun declarationParameters(declaration: Node?): List<Parameter>? = when (declaration) {
        is FunctionDeclaration -> declaration.parameters
        is FunctionExpression -> declaration.parameters
        is ArrowFunction -> declaration.parameters
        is MethodDeclaration -> declaration.parameters
        is Constructor -> declaration.parameters
        else -> null
    }

    private fun propertyDeclaredType(node: PropertyDeclaration, lens: CheckedLens): Type? {
        val name = node.name as? Identifier ?: return null
        val owner = (node as NodeBase).parent as? ClassDeclaration ?: return null
        val ownerName = owner.name ?: return null
        val classSymbol = lens.resolveName(ownerName.text) ?: return null
        return lens.membersOf(lens.declaredTypeOfSymbol(classSymbol), name.text).firstOrNull()
            ?.let { lens.typeOfSymbol(it) }
    }

    /** The nearest enclosing function-like node's DECLARED return type, if it has one. */
    private fun enclosingDeclaredReturnType(node: Node): Type? {
        var cur: Node? = (node as NodeBase).parent
        while (cur != null) {
            when (cur) {
                is FunctionDeclaration, is FunctionExpression, is ArrowFunction,
                is MethodDeclaration, is Constructor,
                -> return declaredReturnType[cur]
                else -> {}
            }
            cur = (cur as NodeBase).parent
        }
        return null
    }

    // ---- classification ----------------------------------------------------

    private fun classify(
        kind: ObligationKind,
        source: Type,
        target: Type,
        sourceNode: Expression,
        signatureWasGeneric: Boolean,
        lens: CheckedLens,
    ) {
        val sourceIndex = indexOf(source, lens)
        val targetIndex = indexOf(target, lens)
        val edge = when {
            source === target || source.id == target.id -> EdgeClass.IDENTITY
            !lens.isAssignableTo(source, target) -> EdgeClass.NOT_ASSIGNABLE
            sourceIndex == targetIndex -> EdgeClass.NOMINAL_SAME_DECL
            isNominalBase(sourceIndex, source, targetIndex, lens) -> EdgeClass.NOMINAL_BASE
            sourceNode is ObjectLiteralExpression -> EdgeClass.FRESH_LITERAL
            else -> EdgeClass.STRUCTURAL
        }
        val sourceType = types[sourceIndex]
        val targetType = types[targetIndex]
        // "Open" means the census's own target is unreliable: the selected overload
        // is not instantiated, so a target still mentioning `T` was compared against
        // the OPEN parameter type where the checker compared against the closed one.
        val open = signatureWasGeneric || targetType.mentionsTypeParameter ||
            sourceType.mentionsTypeParameter
        obligationsRecorded++
        bucketCounts[bucketIndex(kind, edge, targetType.targetClass, sourceType.targetClass, open)]++
        val file = fileOf(sourceNode)
        filesSeen.add(file)
        obligationsByFile[file] = (obligationsByFile[file] ?: 0L) + 1L
        val pair = packPair(sourceIndex, targetIndex)
        distinctPairs.add(pair)
        distinctRawPairs.add(packPair(source.id, target.id))
        if (edge != EdgeClass.NOT_ASSIGNABLE) {
            anyFanIn.getOrPut(targetIndex) { HashSet() }.add(sourceIndex)
        }
        if (edge == EdgeClass.STRUCTURAL) {
            structuralPairs.add(pair)
            structuralFanIn.getOrPut(targetIndex) { HashSet() }.add(sourceIndex)
            structuralFanOut.getOrPut(sourceIndex) { HashSet() }.add(targetIndex)
            if (!open && targetType.isObjectish) {
                // A UNION source is decomposed. `Cat | Dog` reaching `Shape` is not one
                // `implements` edge, it is one per object constituent: at runtime the
                // value IS a Cat or a Dog and it is that class which must carry the
                // interface. Counting the union as a single source would under-count
                // the closure, and skipping it (a union is not object-ish) would
                // under-count it to zero — on tsc's own sources that is 1,268 closed
                // obligations onto object targets, the second largest source class.
                for (constituent in designSourcesOf(source, lens)) {
                    val index = indexOf(constituent, lens)
                    if (index == targetIndex) continue
                    if (isNominalBase(index, constituent, targetIndex, lens)) continue
                    designPairs.add(packPair(index, targetIndex))
                    designFanIn.getOrPut(targetIndex) { HashSet() }.add(index)
                    designFanOut.getOrPut(index) { HashSet() }.add(targetIndex)
                }
            }
        }
        if (sourceNode is ObjectLiteralExpression) objectLiteralsWithTarget++
        rememberExample(kind, edge, targetType.targetClass, sourceIndex, targetIndex, sourceNode, file)
    }

    /**
     * The object-ish RUNTIME shapes a source value can actually hold.
     *
     * An object type is itself; a union contributes each object-ish constituent
     * (its nullish and primitive members hold no interface and erase away). An
     * `any` source contributes NOTHING — it has no generated class to hang an
     * `implements` on, and is the design's own separate problem (`kir-design.md`
     * § 8.4's inline-cache call site), so folding it in here would report a
     * closure the nominal encoding was never going to build.
     */
    private fun designSourcesOf(source: Type, lens: CheckedLens): List<Type> = when (source) {
        is Type.Union -> source.types.filter { types[indexOf(it, lens)].isObjectish }
        else -> if (types[indexOf(source, lens)].isObjectish) listOf(source) else emptyList()
    }

    private fun rememberExample(
        kind: ObligationKind,
        edge: EdgeClass,
        targetClass: TargetClass,
        sourceIndex: Int,
        targetIndex: Int,
        sourceNode: Expression,
        file: String,
    ) {
        val bucket = (kind.ordinal * EdgeClass.entries.size + edge.ordinal) *
            TargetClass.entries.size + targetClass.ordinal
        if (!exampleBuckets.add(bucket)) return
        examples.add(
            CensusExample(
                kind, edge, types[sourceIndex].text, types[targetIndex].text, file, sourceNode.pos,
            ),
        )
    }

    private fun bucketIndex(
        kind: ObligationKind,
        edge: EdgeClass,
        targetClass: TargetClass,
        sourceClass: TargetClass,
        open: Boolean,
    ): Int = bucketIndexOf(kind, edge, targetClass, sourceClass, open)

    private fun packPair(a: Int, b: Int): Long =
        // Round 889: a packed `(a shl 32) or b` key hashes to `a xor b` under
        // `Long.hashCode`, which collapses onto the DIFFERENCE whenever the halves
        // have correlated magnitude — as sequentially minted type ids do. The
        // finalising multiply by an odd constant is a bijection mod 2^64, so it
        // costs nothing and nothing here unpacks the key.
        ((a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)) * -0x61c8864680b583ebL

    // ---- the nominal question ----------------------------------------------

    /**
     * Is [target] among [source]'s transitive DECLARED bases?
     *
     * Derived from declarations, never from the relation — asking the relation
     * would make every structural edge look nominal and the measurement vacuous.
     * Two legs, counted separately because they have different reliability:
     * `Type.Interface.baseTypes`, which is the checker's own resolution of the
     * `extends` clauses, and the `implements` clauses, which `resolveBaseTypesLazy`
     * deliberately EXCLUDES from `baseTypes` (an `implements` target must not
     * contribute members) and which therefore have to be resolved by name here.
     */
    private fun isNominalBase(
        sourceIndex: Int,
        source: Type,
        targetIndex: Int,
        lens: CheckedLens,
    ): Boolean {
        val memoKey = packPair(sourceIndex, targetIndex)
        nominalMemo[memoKey]?.let { return it }
        val closure = declaredBaseClosure(sourceIndex, source, lens)
        val answer = targetIndex in closure
        nominalMemo[memoKey] = answer
        return answer
    }

    private fun declaredBaseClosure(
        sourceIndex: Int,
        source: Type,
        lens: CheckedLens,
    ): Set<Int> {
        baseClosure[sourceIndex]?.let { return it }
        val out = HashSet<Int>()
        val frontier = ArrayDeque<Type>()
        frontier.addLast(source)
        val visited = HashSet<Int>()
        var steps = 0
        while (frontier.isNotEmpty() && steps++ < 256) {
            val current = frontier.removeFirst()
            if (!visited.add(indexOf(current, lens))) continue
            for (base in declaredBasesOf(current, lens)) {
                out.add(indexOf(base, lens))
                frontier.addLast(base)
            }
        }
        baseClosure[sourceIndex] = out
        return out
    }

    private fun declaredBasesOf(type: Type, lens: CheckedLens): List<Type> {
        val declared = when (type) {
            is Type.Reference -> type.target
            is Type.Interface -> type
            else -> return emptyList()
        }
        // A target type's member table is LAZY, and `baseTypes` with it — asking
        // for any member forces `resolveStructuredTypeMembers`, which is what makes
        // the read below a fact rather than an accident of walk order.
        lens.membersOf(type, "")
        val out = ArrayList<Type>()
        declared.baseTypes?.let { bases ->
            out.addAll(bases)
            nominalViaBaseTypes += bases.size.toLong()
        }
        val symbol = declared.symbol ?: return out
        for (declaration in symbol.declarations) {
            val clauses = when (declaration) {
                is ClassDeclaration -> declaration.heritageClauses
                is InterfaceDeclaration -> declaration.heritageClauses
                else -> null
            } ?: continue
            for (clause in clauses) {
                if (clause.token != SyntaxKind.ImplementsKeyword) continue
                for (heritage in clause.types) {
                    val name = (heritage.expression as? Identifier)?.text ?: continue
                    val resolved = lens.resolveName(name)
                    if (resolved == null) {
                        heritageNamesUnresolved++
                        continue
                    }
                    out.add(lens.declaredTypeOfSymbol(resolved))
                    nominalViaImplements++
                }
            }
        }
        return out
    }

    // ---- the distinct-type table -------------------------------------------

    private fun indexOf(type: Type, lens: CheckedLens): Int {
        val key = declarationKey(type)
        keyToIndex[key]?.let { return it }
        val index = types.size
        keyToIndex[key] = index
        // Force the member table before classifying: it is lazy, so a class read off
        // an unresolved type is a function of what an earlier line in the file
        // happened to touch (round 833).
        lens.membersOf(type, "")
        types.add(
            CensusType(
                index = index,
                text = lens.render(type),
                targetClass = targetClassOf(type),
                cannotFlags = cannotFlagsOf(type),
                isDeclared = declarationSymbolOf(type) != null,
                mentionsTypeParameter = mentionsTypeParameter(type, lens, 0, HashSet()),
            ),
        )
        return index
    }

    /**
     * True for an enum's own type and for an enum MEMBER's type.
     *
     * Both are plain `Type.Object`s here — the enum's is member-less and the
     * member's carries `SymbolFlags.EnumMember` — so neither is distinguishable
     * from an anonymous object type by shape alone. The symbol is.
     */
    private fun isEnumFlavoured(type: Type): Boolean {
        if (type.flags.hasAny(TypeFlags.Enum or TypeFlags.EnumLiteral)) return true
        val symbol = (type as? Type.Object)?.symbol ?: return false
        return symbol.flags.hasAny(ENUM_SYMBOL_FLAGS)
    }

    private fun declarationSymbolOf(type: Type): Symbol? = when (type) {
        is Type.Reference -> type.target.symbol
        is Type.Interface -> type.symbol
        // An enum and an enum member are plain `Type.Object`s with real declaring
        // symbols, so they key on the DECLARATION like an interface does. No other
        // `Type.Object` may: an object literal's type also carries a symbol, but a
        // per-occurrence one, and keying on it would make every literal its own
        // "declaration".
        is Type.Object -> type.symbol?.takeIf { it.flags.hasAny(ENUM_SYMBOL_FLAGS) }
        else -> null
    }

    private fun declarationKey(type: Type): Long {
        val symbol = declarationSymbolOf(type)
        // Masked to 32 bits before the tag is applied: an INV.2(c) lexical-scope
        // symbol carries an id from the SEPARATE NEGATIVE space, and a sign-extended
        // one would set every high bit and collide the two tags.
        return if (symbol != null) (1L shl 40) or (symbol.id.toLong() and 0xFFFFFFFFL)
        else (2L shl 40) or (type.id.toLong() and 0xFFFFFFFFL)
    }

    private fun targetClassOf(type: Type): TargetClass = when {
        type.flags.hasAny(TypeFlags.Any or TypeFlags.Unknown) -> TargetClass.ANY_OR_UNKNOWN
        isEnumFlavoured(type) -> TargetClass.ENUM
        type is Type.TypeParam -> TargetClass.TYPE_PARAMETER
        type is Type.Union -> TargetClass.UNION
        type is Type.Intersection -> TargetClass.INTERSECTION
        type is Type.Reference ->
            if (type.target.symbol?.name in ARRAY_LIKE_NAMES) TargetClass.ARRAY_OR_TUPLE
            else TargetClass.OBJECT_WITH_MEMBERS
        type is Type.Object -> when {
            type.tupleElementTypes != null -> TargetClass.ARRAY_OR_TUPLE
            type.symbol?.name in ARRAY_LIKE_NAMES -> TargetClass.ARRAY_OR_TUPLE
            type.members.isNullOrEmpty() && !type.callSignatures.isNullOrEmpty() ->
                TargetClass.FUNCTION_TYPE
            else -> TargetClass.OBJECT_WITH_MEMBERS
        }
        type is Type.Intrinsic || type is Type.StringLiteral || type is Type.NumberLiteral ||
            type is Type.BigIntLiteral -> TargetClass.PRIMITIVE
        else -> TargetClass.OTHER
    }

    private fun cannotFlagsOf(type: Type): Int {
        var flags = 0
        if (type is Type.Union && type.types.any { it is Type.Object }) {
            flags = flags or CANNOT_UNION_OF_OBJECTS
        }
        if (type is Type.Reference && !type.resolvedTypeArguments.isNullOrEmpty()) {
            flags = flags or CANNOT_GENERIC_INSTANTIATION
        }
        val obj = type as? Type.Object ?: return flags
        if (obj.stringIndexInfo != null || obj.numberIndexInfo != null) {
            flags = flags or CANNOT_INDEX_SIGNATURE
        }
        if (!obj.callSignatures.isNullOrEmpty()) flags = flags or CANNOT_CALL_SIGNATURE
        if (!obj.constructSignatures.isNullOrEmpty()) flags = flags or CANNOT_CONSTRUCT_SIGNATURE
        val optional = obj.members?.values?.any { member ->
            member.declarations.any { it is PropertyDeclaration && it.questionToken }
        } ?: false
        if (optional) flags = flags or CANNOT_OPTIONAL_PROPERTY
        return flags
    }

    /**
     * Does [type] mention a type parameter anywhere a substitution would reach?
     *
     * Bounded in depth and cycle-guarded by type id, because a generic type's
     * constraint can name the type itself. Descends into a function type's own
     * PARAMETERS, which is what catches `(value: T) => boolean` — the single most
     * common open target in real TypeScript and one a shallow test misses.
     */
    private fun mentionsTypeParameter(
        type: Type,
        lens: CheckedLens,
        depth: Int,
        seen: MutableSet<Int>,
    ): Boolean {
        if (depth > 6 || !seen.add(type.id)) return false
        return when (type) {
            is Type.TypeParam -> true
            is Type.Union -> type.types.any { mentionsTypeParameter(it, lens, depth + 1, seen) }
            is Type.Intersection ->
                type.types.any { mentionsTypeParameter(it, lens, depth + 1, seen) }
            is Type.Object -> {
                if (type is Type.Reference &&
                    type.resolvedTypeArguments.orEmpty()
                        .any { mentionsTypeParameter(it, lens, depth + 1, seen) }
                ) {
                    return true
                }
                type.tupleElementTypes.orEmpty()
                    .any { mentionsTypeParameter(it, lens, depth + 1, seen) } ||
                    type.callSignatures.orEmpty().any { signatureMentionsTp(it, lens, depth, seen) }
            }
            else -> false
        }
    }

    private fun signatureMentionsTp(
        signature: Signature,
        lens: CheckedLens,
        depth: Int,
        seen: MutableSet<Int>,
    ): Boolean {
        if (!signature.typeParameters.isNullOrEmpty()) return true
        if (signature.parameters.any {
                mentionsTypeParameter(lens.typeOfSymbol(it), lens, depth + 1, seen)
            }
        ) {
            return true
        }
        val returnType = signature.resolvedReturnType ?: return false
        return mentionsTypeParameter(returnType, lens, depth + 1, seen)
    }

    private fun fileOf(node: Node): String {
        var cur: Node? = node
        while (cur != null) {
            if (cur is SourceFile) return cur.fileName
            cur = (cur as NodeBase).parent
        }
        return "?"
    }

    // ---- the report --------------------------------------------------------

    /** The measurement, as a value — see `renderReport` for the rendered form. */
    public fun report(): CensusReport = CensusReport(
        types = types.toList(),
        bucketCounts = bucketCounts.copyOf(),
        distinctPairs = distinctPairs.size,
        distinctRawPairs = distinctRawPairs.size,
        structuralPairs = structuralPairs.size,
        designPairs = designPairs.size,
        structuralFanIn = structuralFanIn.mapValues { it.value.size },
        structuralFanOut = structuralFanOut.mapValues { it.value.size },
        designFanIn = designFanIn.mapValues { it.value.size },
        designFanOut = designFanOut.mapValues { it.value.size },
        anyFanIn = anyFanIn.mapValues { it.value.size },
        obligations = obligationsRecorded,
        expressionsSeen = expressionsSeen,
        objectLiteralsSeen = objectLiteralsSeen,
        objectLiteralsWithTarget = objectLiteralsWithTarget,
        targetUnavailable = targetUnavailable,
        targetUnavailableByKind = targetUnavailableByKind.copyOf(),
        skippedSignatures = skippedRestOrMisalignedSignature,
        callWithNoSignature = callWithNoSignature,
        argumentBeyondParameterList = argumentBeyondParameterList,
        lensFailures = lensFailures,
        nominalViaBaseTypes = nominalViaBaseTypes,
        nominalViaImplements = nominalViaImplements,
        heritageNamesUnresolved = heritageNamesUnresolved,
        filesSeen = filesSeen.size,
        examples = examples.toList(),
    )

    private companion object {
        val ARRAY_LIKE_NAMES = setOf("Array", "ReadonlyArray")
        val ENUM_SYMBOL_FLAGS =
            SymbolFlags.RegularEnum or SymbolFlags.ConstEnum or SymbolFlags.EnumMember
    }

}
