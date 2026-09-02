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
 * (INV.2) Stage 2 of `docs/INVERSION-DESIGN.md`: the POST-HOC TYPE ORACLE — a
 * node-addressed query surface over ONE finished check, shaped after tsgo's
 * `tsc/internal/api/proto.go` and answered from three places:
 *
 *  - **the (INV.1) store** (`NodeAnswerStore`, one per walked file) for every
 *    question whose answer is a function of walk-scoped state — the type at a
 *    node, what a name resolved to, which overload a call picked, what
 *    contextually typed an expression. Those were recorded AS THE WALK PASSED
 *    the node, under the ambient the walk had there, and are the answers a
 *    post-hoc query cannot compute: asked at rest, a body local answers the
 *    same-named global's type and a parameter answers `any`
 *    (`NodeAnswerStoreTest`'s positive control, round 911's shape);
 *  - **the retained graph plus the live checker** (an [OracleLens]) for every
 *    question about a [Symbol], a [Type] or a [Signature] once one is in hand —
 *    the design's bin A, answerable post-hoc because the instantiation context
 *    is EMPTY at rest, which is exactly the round-778 cacheable case;
 *  - **a refusal, with the reason**, for the two questions neither can answer
 *    until Stage 3 dissolves B83.5: [resolveName] and [symbolsInScope] name no
 *    existing node, so nothing the walk recorded answers them, and the retained
 *    tables leave block-scoped declarations unbound.
 *
 * ## Validity
 *
 * An oracle is valid for ONE build of ONE program text. Every [Type] and
 * [Symbol] it hands out belongs to that build's checker (ids are per-build and
 * per-thread, INV.6(6c0)); an edit to any file makes every answer stale with
 * no way to tell from the answer, so the owner of the program CLOSES the oracle
 * on edit ([close]) and every later question is refused. There is deliberately
 * no invalidation protocol finer than that — (INC.46)'s rule that an id-keyed
 * anything must never cross builds.
 *
 * ## Per-row fidelity (the A° divergences, stated rather than hidden)
 *
 * `docs/type-oracle.md` carries the full table; the ones a consumer will meet
 * first: alias display is FIRST-WINS per interned type ((INC.27)), so
 * [typeToString] can name a union after whichever alias was resolved first;
 * there are NO fresh literal types ((CHK.59)) and NO subtype reduction
 * ((CHK.66)); an intersection is stored UN-distributed (round 777), so
 * [typesOfType] answers its written constituents where tsc would answer the
 * distributed union; [parametersOfSignature] DROPS binding-pattern parameters
 * (round 921) and [parameterDeclarationsOfSignature] is the declaration-read
 * answer; a member of a MERGED interface carries the last block's declaration
 * only (round 928).
 *
 * ## Cost
 *
 * Nothing here runs in a production compile: the store is recorded only for a
 * build that asked for an oracle ([OracleHolder] / [typeOracleOf]), and
 * `NodeAnswerStoreTest` pins the computation count at zero without it. The
 * recording cost is measured and recorded in the design (§ 9a for Stage 1's
 * type-only store, § 9b for this store).
 */
class TypeOracle internal constructor(
    stores: Map<String, NodeAnswerStore>,
    private val lens: OracleLens,
) {

    /** Every program file the build walked, in check order. */
    val files: List<SourceFile> = stores.values.map { it.sourceFile }

    private val storesByFile: Map<String, NodeAnswerStore> = stores

    /**
     * The BUILD GENERATION this oracle belongs to — what a handle from another
     * oracle is refused against. Monotonic per process.
     */
    val generation: Int = nextGeneration++

    /** The handle table: wire-shaped ids for the objects this oracle hands out. */
    val handles: OracleHandles = OracleHandles(generation)

    /** Whether [close] has run. Every query on a closed oracle is refused. */
    var isClosed: Boolean = false
        private set

    /**
     * Ends this oracle: every later question throws [OracleRefusal] and every
     * handle is released. The owner of the program calls this on ANY edit —
     * the oracle cannot tell a stale answer from a fresh one, so it must not
     * be asked.
     */
    fun close() {
        isClosed = true
        handles.close()
    }

    private fun open() {
        if (isClosed) throw OracleRefusal("this oracle is closed: the program it answered about was edited or released")
    }

    // ---------------------------------------------------------------------
    // Bin B/R — recorded during the walk, served from the store.
    // ---------------------------------------------------------------------

    /**
     * `getTypeAtLocation`: the type of [node] AT THAT POSITION — flow-narrowed,
     * body-local-correct, because the walk recorded it there. Null when the
     * node was never walked: a file outside the build, a synthesized or
     * `copy()`-ed node (INV.2(a)), or a node of a program this oracle is not
     * about — three facts that are all "no answer", never a wrong one.
     *
     * For a member NAME the answer is the type of its ACCESS (BUG.4, tsc's own
     * `getTypeOfSymbolAtLocation` rule) and for a member DECLARATION name the
     * declared member type ((API.11)); for everything else the expression's
     * own type.
     */
    fun typeAt(node: Expression): Type? {
        open()
        return storeOf(node)?.typeAt(node)
    }

    /**
     * `getSymbolAtLocation`: what the name [node] refers to. For a free name
     * the binding the walk's own scope chain answered; for a member name
     * (after a dot, a qualified name's right side, `o["p"]`, a binding
     * element's `propertyName`) the property symbol(s) through the receiver's
     * type; for a declaration NAME the declared symbol itself. An import is
     * answered as its ALIAS symbol — ask [aliasedSymbol] for the declaration,
     * exactly as tsgo separates `getSymbolAtLocation` from `getAliasedSymbol`.
     *
     * Several symbols where a member is supplied by several union constituents
     * (the collection rule of round 916 — never `getPropertyOfType`'s
     * first-wins); empty where nothing resolved or [node] is not a name.
     */
    fun symbolsAt(node: Node): List<Symbol> {
        open()
        return storeOf(node)?.symbolsAt(node) ?: emptyList()
    }

    /** [symbolsAt]'s first answer, or null — the single-symbol shape of the API. */
    fun symbolAt(node: Node): Symbol? = symbolsAt(node).firstOrNull()

    /**
     * `getResolvedSignature`: what overload resolution picked at the call-like
     * [node] (a [CallExpression] or a [NewExpression]), with the candidate
     * count that tells its null apart — see [ResolvedCall]. Null when [node] is
     * not call-like or was never walked.
     */
    fun resolvedCallAt(node: Node): ResolvedCall? {
        open()
        return storeOf(node)?.callAt(node)
    }

    /** [resolvedCallAt]'s signature, or null. */
    fun resolvedSignatureAt(node: Node): Signature? = resolvedCallAt(node)?.signature

    /**
     * `getContextualType`: the type that contextually types [node], where the
     * checker computes one — an annotated declaration's initializer, a call or
     * `new` argument through the selected callee signature, a `return`, an
     * arrow's expression body, an assertion's type, an enclosing literal's
     * member, an array element. Null where nothing supplies one, and null
     * (rather than a naked type parameter) for an inferred generic argument,
     * which is tsc's answer too. The (CHK.30)/(CHK.39) checker gap survives:
     * where the checker itself types a position by ARITY rather than by type,
     * so does this.
     */
    fun contextualTypeAt(node: Expression): Type? {
        open()
        return storeOf(node)?.contextualAt(node)
    }

    /**
     * `getTypeOfSymbolAtLocation`: the type [symbol] has at the reference
     * [node] — the recorded, flow-narrowed type when [node] is a name the walk
     * resolved to [symbol], and the symbol's declared-and-widened type
     * otherwise (which is what tsc answers for a location that does not
     * narrow it).
     */
    fun typeOfSymbolAt(symbol: Symbol, node: Node): Type {
        open()
        val store = storeOf(node)
        if (store != null && node is Expression && store.symbolsAt(node).any { it === symbol }) {
            store.typeAt(node)?.let { return it }
        }
        return lens.typeOfSymbol(symbol)
    }

    // ---------------------------------------------------------------------
    // Bin B/L — refused until Stage 3, with the reason.
    // ---------------------------------------------------------------------

    /**
     * `resolveName`: REFUSED. An arbitrary `(name, location)` lookup names no
     * existing node, so nothing the walk recorded answers it, and the retained
     * tables cannot: B83.5 leaves block-scoped declarations unbound, so an
     * answer from them would silently resolve a shadowed name to the OUTER
     * binding. Stage 3 (tree-derived scope resolution) opens this row.
     */
    fun resolveName(name: String, location: Node): Symbol =
        throw OracleRefusal(
            "resolveName('$name') is not answerable until Stage 3 of the inversion: " +
                "the retained scope tables leave block-scoped declarations unbound (B83.5), " +
                "so a post-hoc lookup at ${describe(location)} could answer a shadowed outer binding",
        )

    /**
     * `getSymbolsInScope`: REFUSED, for [resolveName]'s reason — an enumeration
     * from the retained tables would omit every block-scoped declaration and
     * offer a shadowed outer one in its place.
     */
    fun symbolsInScope(location: Node): List<Symbol> =
        throw OracleRefusal(
            "getSymbolsInScope is not answerable until Stage 3 of the inversion: " +
                "the retained scope tables leave block-scoped declarations unbound (B83.5), " +
                "so an enumeration at ${describe(location)} would be incomplete and misleading",
        )

    // ---------------------------------------------------------------------
    // Bin A — the retained graph, through the live checker.
    // ---------------------------------------------------------------------

    /** `getTypeOfSymbol`: the symbol's type, resolved now if never asked. */
    fun typeOfSymbol(symbol: Symbol): Type {
        open()
        return lens.typeOfSymbol(symbol)
    }

    /** `getDeclaredTypeOfSymbol`: what a type reference to [symbol] denotes. */
    fun declaredTypeOfSymbol(symbol: Symbol): Type {
        open()
        return lens.declaredTypeOfSymbol(symbol)
    }

    /**
     * `getAliasedSymbol`: what an import alias names, or null when [symbol] is
     * not an alias — carries the (CHK.30)/(CHK.73) fallback-leg gaps for some
     * import forms (an ambient-module namespace import answers nothing).
     */
    fun aliasedSymbol(symbol: Symbol): Symbol? {
        open()
        return lens.aliasTarget(symbol)
    }

    /**
     * `getPropertyOfType`, as the COLLECTION question and not the assignability
     * one: every member named [name] on [type], distributed over unions and
     * intersections and through the apparent type of a primitive. The
     * checker's own `getPropertyOfType` answers "is the property on EVERY
     * constituent" and then returns one constituent's symbol (round 916), which
     * is the wrong helper for where-is-this-member in both directions.
     */
    fun propertyOfType(type: Type, name: String): List<Symbol> {
        open()
        return lens.membersOf(type, name)
    }

    /**
     * `getPropertiesOfType`: the members of an object type; for an intersection
     * the members of every constituent (tsc's rule); for a union the names
     * common to every constituent, each answered by [propertyOfType]'s first
     * symbol for it — tsc synthesizes a union property there and this checker
     * does not, so the symbol is the FIRST constituent's (a stated divergence).
     */
    fun propertiesOfType(type: Type): List<Symbol> {
        open()
        return when (type) {
            is Type.Intersection -> {
                val seen = HashSet<String>()
                val out = ArrayList<Symbol>()
                for (c in type.types) for (p in propertiesOfType(c)) if (seen.add(p.name)) out.add(p)
                out
            }
            is Type.Union -> {
                if (type.types.isEmpty()) return emptyList()
                var common: LinkedHashSet<String>? = null
                for (c in type.types) {
                    val names = LinkedHashSet(propertiesOfType(c).map { it.name })
                    common = if (common == null) names else LinkedHashSet(common.filter { it in names })
                }
                (common ?: emptySet<String>()).mapNotNull { lens.membersOf(type, it).firstOrNull() }
            }
            else -> lens.propertiesOf(type)
        }
    }

    /** `getApparentType`: the wrapper interface of a primitive, a type parameter's constraint. */
    fun apparentType(type: Type): Type {
        open()
        return lens.apparentType(type)
    }

    /** `getBaseTypes`: the declared heritage of an interface or class type, resolved. */
    fun baseTypes(type: Type): List<Type> {
        open()
        return lens.baseTypesOf(type)
    }

    /** `getTypeArguments`: a generic instantiation's arguments, or empty. */
    fun typeArguments(type: Type): List<Type> {
        open()
        return (type as? Type.Reference)?.resolvedTypeArguments ?: emptyList()
    }

    /**
     * `getTypesOfType`: a union's or intersection's constituents AS STORED — an
     * intersection over a union is kept un-distributed here where tsc rewrites
     * `X & (A | B)` into `X & A | X & B` at construction (round 777).
     */
    fun typesOfType(type: Type): List<Type> {
        open()
        return when (type) {
            is Type.Union -> type.types
            is Type.Intersection -> type.types
            else -> emptyList()
        }
    }

    /** `getSignaturesOfType(Call)`: the call signatures of [type], unions and intersections flattened. */
    fun callSignaturesOfType(type: Type): List<Signature> {
        open()
        return lens.callSignaturesOf(type)
    }

    /** `getSignaturesOfType(Construct)`: the construct signatures of [type]. */
    fun constructSignaturesOfType(type: Type): List<Signature> {
        open()
        return lens.constructSignaturesOf(type)
    }

    /** `getReturnTypeOfSignature`: what [signature] returns, `any` where unresolved. */
    fun returnTypeOfSignature(signature: Signature): Type {
        open()
        return signature.resolvedReturnType ?: anyType
    }

    /**
     * `getParametersOfSignature`, as the checker models it: the parameter
     * SYMBOLS, which DROP every binding-pattern parameter and are zipped onto
     * the declaration's annotations positionally (round 921) — so for
     * `f({a}: O, b: string)` this is one symbol, named `b` and typed `O`.
     * [parameterDeclarationsOfSignature] is the declaration-read answer.
     */
    fun parametersOfSignature(signature: Signature): List<Symbol> {
        open()
        return signature.parameters
    }

    /**
     * The declaration's OWN parameter list for [signature], binding patterns
     * included, or null when the signature carries no function-like
     * declaration (a synthesized one). The sound source for arity, names and
     * annotations; a `this` parameter is included and is the consumer's to
     * drop.
     */
    fun parameterDeclarationsOfSignature(signature: Signature): List<Parameter>? {
        open()
        return lens.declaredParameters(signature)
    }

    /** `isTypeAssignableTo`: the relation engine's own verdict. */
    fun isAssignableTo(source: Type, target: Type): Boolean {
        open()
        return lens.isAssignable(source, target)
    }

    /**
     * `typeToString`: the checker's own rendering — the one every diagnostic
     * uses, with its first-wins alias naming ((INC.27)) and `errorType`
     * rendered as `any` (B58.1).
     */
    fun typeToString(type: Type): String {
        open()
        return lens.render(type)
    }

    /**
     * `getTypeFromTypeNode`: what a written type annotation denotes. Sound for
     * an annotation whose names resolve at rest (the INV.5(c) cacheable
     * population); inside a generic body a bare type parameter has no scope to
     * resolve in and answers `any` — prefer [typeAt] on the expression the
     * annotation types, which the walk recorded with the scope in force.
     */
    fun typeFromTypeNode(node: TypeNode): Type {
        open()
        return lens.typeOfTypeNode(node)
    }

    /**
     * `getConstantValue`: an enum member's constant. Note this answers for an
     * AMBIENT non-const enum member with no initializer, where tsc has no
     * value — we auto-number those for the JavaScript emitter.
     */
    fun constantValue(enumMember: Node): ConstantValue? {
        open()
        return lens.enumMemberValue(enumMember)
    }

    /**
     * The intrinsic types by name — `getAnyType`, `getStringType` and the rest
     * of the 11-row family in one lookup; null for a name that is not one.
     */
    fun intrinsicType(name: String): Type? = when (name) {
        "any" -> anyType
        "unknown" -> unknownType
        "string" -> stringType
        "number" -> numberType
        "boolean" -> booleanType
        "void" -> voidType
        "undefined" -> undefinedType
        "null" -> nullType
        "never" -> neverType
        "bigint" -> bigintType
        "symbol" -> esSymbolType
        "object" -> nonPrimitiveType
        else -> null
    }

    // ---------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------

    /**
     * The store OF THE NODE'S FILE, found by ascending `parent` to the
     * [SourceFile] — never by id alone, since `nodeId` restarts per file
     * (round 787) and a node of another file can carry an in-range id.
     */
    private fun storeOf(node: Node): NodeAnswerStore? {
        var current: Node? = node
        while (current != null) {
            if (current is SourceFile) return storesByFile[current.fileName]
            current = (current as NodeBase).parent
        }
        return null
    }

    private fun describe(node: Node): String {
        val file = storeOf(node)?.sourceFile?.fileName ?: "<unwalked file>"
        return "$file@${node.pos}"
    }

    private companion object {
        /** Process-wide, not thread-local: a generation is compared for equality only. */
        var nextGeneration: Int = 1
    }
}

/**
 * (INV.2) A question the oracle will not answer, and why. Thrown rather than
 * answered by a null, because a null reads as "nothing there" — a legitimate
 * answer for [TypeOracle.typeAt] — and a refusal is a different fact: the
 * question was understood and cannot be answered soundly.
 */
class OracleRefusal(reason: String) : UnsupportedOperationException(reason)

/** (INV.2) A wire-shaped handle to a [Type] pinned in one oracle's [OracleHandles]. */
data class TypeHandle(val generation: Int, val id: Int)

/** (INV.2) A wire-shaped handle to a [Symbol] pinned in one oracle's [OracleHandles]. */
data class SymbolHandle(val generation: Int, val id: Int)

/** (INV.2) A wire-shaped handle to a [Signature] pinned in one oracle's [OracleHandles]. */
data class SignatureHandle(val generation: Int, val id: Int)

/**
 * (INV.2) The per-build HANDLE TABLE of the design's § 4: an in-process
 * consumer holds the [Type]/[Symbol]/[Signature] objects directly, but a
 * consumer on the far side of a wire needs an id, and an id here is only
 * meaningful against the build that minted it — `Type.id` and `Symbol.id`
 * are per-build AND per-thread sequences (INV.6(6c0)), so they cannot be the
 * wire id. This table maps its OWN ids to the retained objects of ONE
 * generation, refuses a handle from another generation or one already
 * released, and dies with the oracle.
 */
class OracleHandles internal constructor(val generation: Int) {

    private val types = HashMap<Int, Type>()
    private val symbols = HashMap<Int, Symbol>()
    private val signatures = HashMap<Int, Signature>()
    private var next = 1
    private var closed = false

    /** How many handles are currently pinned, across the three kinds. */
    val pinned: Int get() = types.size + symbols.size + signatures.size

    fun pin(type: Type): TypeHandle {
        open()
        val id = next++
        types[id] = type
        return TypeHandle(generation, id)
    }

    fun pin(symbol: Symbol): SymbolHandle {
        open()
        val id = next++
        symbols[id] = symbol
        return SymbolHandle(generation, id)
    }

    fun pin(signature: Signature): SignatureHandle {
        open()
        val id = next++
        signatures[id] = signature
        return SignatureHandle(generation, id)
    }

    fun type(handle: TypeHandle): Type =
        resolve(handle.generation, handle.id, types, "type")

    fun symbol(handle: SymbolHandle): Symbol =
        resolve(handle.generation, handle.id, symbols, "symbol")

    fun signature(handle: SignatureHandle): Signature =
        resolve(handle.generation, handle.id, signatures, "signature")

    fun release(handle: TypeHandle) { open(); types.remove(handle.id) }
    fun release(handle: SymbolHandle) { open(); symbols.remove(handle.id) }
    fun release(handle: SignatureHandle) { open(); signatures.remove(handle.id) }

    internal fun close() {
        closed = true
        types.clear()
        symbols.clear()
        signatures.clear()
    }

    private fun open() {
        if (closed) throw OracleRefusal("the oracle that owned this handle table is closed")
    }

    private fun <V : Any> resolve(gen: Int, id: Int, table: Map<Int, V>, kind: String): V {
        open()
        if (gen != generation) {
            throw OracleRefusal("a $kind handle of build generation $gen was asked of generation $generation")
        }
        return table[id]
            ?: throw OracleRefusal("$kind handle $id of generation $gen was released, or never pinned")
    }
}

/**
 * (INV.2) The out-parameter by which a caller asks a build to hand back a
 * [TypeOracle] over the program it checked — the same shape as [RecheckHolder].
 * A build that receives one records the (INV.1) store for every walked file
 * (the only thing that makes the oracle's walk-scoped rows answerable) and
 * runs the SEQUENTIAL checker, for the reason a [CheckedNodeSink] does: a
 * partition worker rebases ids into its own slice, so answers from two
 * workers would silently conflate.
 */
class OracleHolder {
    var oracle: TypeOracle? = null
}

/**
 * (INV.2) The checker's own queries, forwarded, for the oracle's bin-A rows —
 * the post-hoc twin of [CheckedLens]. Where that one is valid only inside a
 * walk callback, this one is valid AT REST: every method here is a function of
 * the retained graph and of an instantiation context that is empty once the
 * check is over (round 778's cacheable case). Nothing walk-scoped may be
 * forwarded through it; that is what the store is for.
 */
internal interface OracleLens {
    fun typeOfSymbol(symbol: Symbol): Type
    fun declaredTypeOfSymbol(symbol: Symbol): Type
    fun aliasTarget(symbol: Symbol): Symbol?
    fun membersOf(type: Type, name: String): List<Symbol>
    fun propertiesOf(type: Type): List<Symbol>
    fun apparentType(type: Type): Type
    fun baseTypesOf(type: Type): List<Type>
    fun callSignaturesOf(type: Type): List<Signature>
    fun constructSignaturesOf(type: Type): List<Signature>
    fun declaredParameters(signature: Signature): List<Parameter>?
    fun isAssignable(source: Type, target: Type): Boolean
    fun render(type: Type): String
    fun typeOfTypeNode(node: TypeNode): Type
    fun enumMemberValue(node: Node): ConstantValue?
}

/** (INV.2) One in-memory program checked for an oracle: the oracle and the check's diagnostics. */
class TypeOracleBuild(
    val oracle: TypeOracle,
    val diagnostics: List<Diagnostic>,
)

/**
 * (INV.2) Checks the in-memory program [files] (file name → content, PATH-shaped
 * names such as `/proj/main.ts` so relative imports resolve against the
 * importer's directory) as ONE program — one [Binder], one [Checker], the
 * store recorded for every file — and hands back the oracle over it. The
 * embedding shape for a host that has the sources in hand; a project on disk
 * goes through `ProjectCompiler.build(..., oracleHolder = …)`, which adds the
 * crawl, the tsconfig and module resolution.
 */
fun typeOracleOf(
    files: Map<String, String>,
    options: CompilerOptions = CompilerOptions(useRealLibs = true),
): TypeOracleBuild {
    val parseDiagnostics = mutableListOf<Diagnostic>()
    val sourceFiles = files.map { (fileName, content) ->
        val flags = computeParserFlags(fileName, content, options)
        val parser = Parser(
            content,
            fileName,
            forceJsx = flags.forceJsx,
            topLevelAwait = flags.topLevelAwait,
            needsJsxFlag = flags.needsJsxFlag,
            noImplicitAny = flags.noImplicitAny,
        )
        val sourceFile = parser.parse()
        parseDiagnostics += parser.getDiagnostics()
        sourceFile
    }
    return runWithDeepStack {
        val binder = Binder(options)
        val binderResults = sourceFiles.map { binder.bind(it) }
        val checker = Checker(
            options,
            binderResults,
            isMultiFileSource = true,
            recordNodeAnswers = true,
        )
        val diagnostics = parseDiagnostics + checker.getDiagnostics()
        TypeOracleBuild(checker.typeOracle(), diagnostics)
    }
}
