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
 * (INV.0) step 8 — the TYPE-CAPTURE seam: what the language service reads out of a
 * build. Extracted VERBATIM from `Checker.kt` (design § 6 Stage 0), as a final
 * class constructed once per [Checker]; no semantic change, and the fifteen
 * members `Checker.kt` and its callers still reach are one-line delegations.
 *
 * ## Why this is a seam, and why its ambient row is the arc's LARGEST
 *
 * `docs/INVERSION-DESIGN.md` § 2 says this checker cannot serve a post-hoc type
 * oracle because "its answers are functions of walk-scoped state". This row is
 * that claim as a NUMBER: **61 checker members over 170 sites**, of which the
 * expensive half is not the type system at all but the WALK — `ctaFrames`,
 * `currentFlowGraph`, `currentClassForThis`, `currentCheckFileName`,
 * `spineCurrentScope`, `inAsyncFunctionBody`, `currentTypeParamScope`. A
 * recorder is called from `spineEnterNode` and answers under the ambient the
 * walk has AT THAT NODE; nothing here can be answered later, which is what the
 * `postHocTypeAtSpanForTesting` probe left behind in `Checker` exists to keep
 * measuring.
 *
 * What that makes this extraction is a MEASUREMENT, not a repair: the coupling
 * was always there and is now counted. It is also the arc's cleanest OUT surface
 * — 98 declarations move and only 15 keep a caller.
 *
 * ## What is here
 *
 * ONE contiguous span of `Checker.kt` (5990-9109), whole — 3,120 lines,
 * 98 declarations, in four groups:
 *
 *  - the RESULT TABLES and their five `internal` readers ([capturedTypes],
 *    [capturedDefinitions], [capturedMembers], [capturedScopes],
 *    [capturedSignatures]), which the compile driver copies into
 *    `CompilationResult`; every one is FIRST-WINS or deepest-wins by span, for
 *    the reason `typeCaptureRecord`'s KDoc gives;
 *  - the RECORDERS the spine calls ([typeCaptureVisit], `typeCaptureRecordAt`,
 *    `typeCaptureRecordMembers`, `typeCaptureRecordSignatures`,
 *    `typeCaptureRecordScope`), which are where the walk-scoped ambient is read;
 *  - the RESOLVERS under them — the symbol readers, the member collectors, the
 *    import-alias walk, the destructuring reader, the definition finder;
 *  - the RENDERERS — signature rendering and its parameter parts (including the
 *    nested [SignatureParameterPart]), the scope enumeration, the annotation
 *    text.
 *
 * ## What it owns
 *
 * Every capture RESULT table and every per-span node map. The per-file KEY sets
 * ([Checker.typeCaptureKeysCurrentFile] and its three siblings) stay on
 * `Checker`: the spine's per-file setup writes them, so they are the request's
 * plumbing rather than this family's state.
 */
internal class CaptureRecorder(
    private val checker: Checker,
) {

    /**
     * The captured type answers, keyed by the span asked about, FIRST WINS.
     *
     * See [typeCaptureRecord] for why first: the walk reaches a node under the
     * tightest ambient it will ever have there, and a later visit of a node sharing
     * the same raw span would answer from a looser one.
     */
    private val typeCaptureResults = LinkedHashMap<TypeCaptureSpan, CapturedType>()

    /**
     * (API.3b) The captured DEFINITION answers, keyed and ordered exactly as
     * [typeCaptureResults] is, and first-wins for the same reason: the scope chain
     * in force at a node is the tightest one that node ever sees.
     */
    private val typeCaptureDefinitions = LinkedHashMap<TypeCaptureSpan, CapturedDefinition>()

    /**
     * (API.3) What the checker computed at the requested spans.
     *
     * `internal` deliberately: the compile driver reads it into
     * [CompilationResult.capturedTypes], which is the value a caller sees. Empty
     * unless a [TypeCaptureRequest] was passed.
     */
    val capturedTypes: List<CapturedType> get() = typeCaptureResults.values.toList()

    /**
     * (API.3b) Where the symbols at the requested spans are declared.
     *
     * `internal` for the same reason [capturedTypes] is: the compile driver reads it
     * into [CompilationResult.capturedDefinitions]. Empty unless a
     * [TypeCaptureRequest] was passed, and shorter than the request whenever a span
     * named something no free-name lookup resolves.
     */
    val capturedDefinitions: List<CapturedDefinition>
        get() = typeCaptureDefinitions.values.toList()

    /**
     * (API.4a) The captured MEMBER LISTS, keyed and ordered exactly as
     * [typeCaptureResults] is, and first-wins for the same reason.
     */
    private val typeCaptureMemberResults = LinkedHashMap<TypeCaptureSpan, CapturedMembers>()

    /**
     * (API.4a) Which NODE produced each entry of [typeCaptureMemberResults].
     *
     * Kept because a raw span does NOT always identify one node, and the case where
     * it does not is exactly the case a completion is asked in. A dangling `.` at
     * end of file makes the parser synthesize a ZERO-WIDTH name there, so the
     * property access's `end` — read after the one-token lookahead, which sees only
     * end-of-file — equals its RECEIVER's, and `o` and `o.<nothing>` become the same
     * `(pos, end)` pair. Preorder then reaches the property access first and
     * first-wins would answer with the members of `any`.
     *
     * The rule that resolves it: among nodes sharing a span, the DEEPEST one is what
     * was asked about, so a later visit overwrites an earlier one when it is a
     * DESCENDANT of it. That leaves the ordinary case untouched — two visits of the
     * SAME node still keep the first, i.e. the tightest ambient, which is what
     * [typeCaptureRecord] documents.
     */
    private val typeCaptureMemberNodes = HashMap<TypeCaptureSpan, Node>()

    /**
     * (API.4a) What the types at the requested member spans call their own.
     *
     * `internal` for the reason [capturedTypes] is: the compile driver reads it into
     * [CompilationResult.capturedMembers]. Empty unless a [TypeCaptureRequest]
     * carrying [TypeCaptureRequest.memberSpans] was passed.
     */
    val capturedMembers: List<CapturedMembers>
        get() = typeCaptureMemberResults.values.toList()

    /**
     * (API.4b) The captured SCOPE ENUMERATIONS, keyed and ordered exactly as
     * [typeCaptureResults] is, and resolved on a span collision by exactly
     * [typeCaptureMemberNodes]' deepest-wins rule.
     */
    private val typeCaptureScopeResults = LinkedHashMap<TypeCaptureSpan, CapturedScope>()

    /** (API.4b) Which NODE produced each entry of [typeCaptureScopeResults]. */
    private val typeCaptureScopeNodes = HashMap<TypeCaptureSpan, Node>()

    /**
     * (API.4b) What the lexical scope chain binds at the requested scope spans.
     *
     * `internal` for the reason [capturedTypes] is: the compile driver reads it into
     * [CompilationResult.capturedScopes].
     */
    val capturedScopes: List<CapturedScope>
        get() = typeCaptureScopeResults.values.toList()

    /**
     * (API.6) The captured SIGNATURE LISTS, keyed and ordered exactly as
     * [typeCaptureResults] is, and resolved on a span collision by exactly
     * [typeCaptureMemberNodes]' deepest-wins rule.
     */
    private val typeCaptureSignatureResults = LinkedHashMap<TypeCaptureSpan, CapturedSignatures>()

    /** (API.6) Which NODE produced each entry of [typeCaptureSignatureResults]. */
    private val typeCaptureSignatureNodes = HashMap<TypeCaptureSpan, Node>()

    /**
     * (API.6) The signatures of the callees at the requested signature spans.
     *
     * `internal` for the reason [capturedTypes] is: the compile driver reads it into
     * [CompilationResult.capturedSignatures].
     */
    val capturedSignatures: List<CapturedSignatures>
        get() = typeCaptureSignatureResults.values.toList()

    /**
     * (API.3) Records the type at [node] when its RAW span is one the caller asked
     * about.
     *
     * Called from [spineEnterNode] under a null check on
     * [typeCaptureKeysCurrentFile], and the argument is the node itself: round 900's
     * law is that a probe's guard cannot protect its own ARGUMENT, because Kotlin
     * evaluates arguments strictly, so nothing may be derived at the call site.
     *
     * Matching is EQUALITY on the raw `(pos, end)` pair rather than containment,
     * because `Node.end` overshoots by a token (round 910) and there is no token
     * index here to snap it back with; the caller — which does have one — resolves
     * its caret to a node first and asks about that node's span. A span nothing in
     * the program carries simply yields no answer.
     *
     * Only an [Expression] is typed — and, before (API.4b), only an [Expression]
     * was VISITED. A free-name completion anchor is routinely a statement or a
     * Block (the caret sits between nodes), so a span that asked for a scope
     * enumeration passes the kind gate too; nothing else does, so no other capture
     * gained a population.
     *
     * `currentCheckFileName` is installed for the
     * call because the spine's own handlers install-and-restore it per anchor
     * dispatch, so at an arbitrary node it is at rest — and it is the key
     * `getTypeOfIdentifier` reaches `fileLocalTypeMaps` through. Everything else the
     * answer depends on (`currentLocalTypes`, `currentFileLocals`, the frames) is
     * live BECAUSE this runs inside the walk, which is the whole point.
     */
    fun typeCaptureVisit(node: Node) {
        // (API.3) exactly the pre-(KIR) condition, unchanged: a requested span, and
        // an [Expression] unless a scope enumeration was asked for at it.
        val keys = checker.typeCaptureKeysCurrentFile
        val wantsCapture = keys != null && run {
            val key = TypeCaptureRequest.packSpanKey(node.pos, node.end)
            key in keys &&
                (node is Expression || checker.typeCaptureScopeKeysCurrentFile?.contains(key) == true)
        }
        // (KIR) the sink's population is every node a backend must LOWER, so it is
        // span-free: expressions, plus the declaration-shaped nodes whose symbols a
        // lowering needs. Independent of the capture's condition — the two may both
        // be live, and neither narrows the other.
        val wantsSink = checker.checkedSink != null &&
            (node is Expression || node is Declaration || node is ClassElement ||
                node is Parameter)
        // (INV.1) the store's population is every Expression, unconditionally —
        // it is the capture with every span requested. Independent of both.
        val wantsAnswer = checker.nodeAnswerStoreCurrentFile != null && node is Expression
        if (!wantsCapture && !wantsSink && !wantsAnswer) return
        val frame = checker.ctaFrames.lastOrNull()
        if (frame == null) {
            if (wantsCapture) typeCaptureRecordAt(node, checker.spineFileName)
            if (wantsSink) checkedSinkEmit(node)
            if (wantsAnswer) nodeAnswerRecord(node)
            return
        }
        // The ambient a statement anchor installs, reproduced VERBATIM from
        // [ctaM3StmtAnchorCore]'s prologue — because at an arbitrary node the spine
        // holds NONE of it. The anchors install-and-restore per dispatch (round 806),
        // so a plain per-node capture reads the FILE-level tables and answers a body
        // local with the same-named global's type; measured before this install
        // existed, all three of the interesting positions in
        // `TypeCaptureMeasurementTest` came out wrong. The frame is the position's
        // scope: `ctaFrames.last()` is what the anchor itself reads.
        val savedThis = checker.currentClassForThis
        val savedInFn = checker.inNonArrowFunctionBody
        val savedAsync = checker.inAsyncFunctionBody
        val savedGen = checker.inGeneratorFunctionBody
        val savedTpDecls = checker.currentTypeParamDecls
        val savedTpScope = checker.currentTypeParamScope
        val savedFlowGraph = checker.currentFlowGraph
        // (BUG.3) NOT `frame.classForThis`. A cta frame is a TYPE-checking context and
        // `this` is not one of the things it threads: the frame an ARROW body pushes
        // carries null there, so a caret on `this.` inside one answered no members at
        // all, while the same caret directly in the method answered correctly. The
        // checker itself is unaffected and was measured to be — the same shapes
        // compiled through the ordinary diagnostic path are byte-identical to tsc
        // 7.0.2 — because `this` reaches the diagnostics through B101's own
        // walk-scoped installs, which an arrow deliberately preserves. This is the
        // pull-based reconstruction of exactly those installs.
        checker.currentClassForThis = typeCaptureThisClass(node)
        checker.inNonArrowFunctionBody = frame.inFn
        checker.inAsyncFunctionBody = frame.inAsync
        checker.inGeneratorFunctionBody = frame.inGen
        checker.currentTypeParamDecls = frame.fnTpDecls ?: emptyMap()
        checker.currentTypeParamScope = frame.fnTpScope ?: savedTpScope
        checker.currentFlowGraph = checker.ctaM3FlowGraph
        var namespacesPushed = 0
        for (f in checker.ctaFrames) {
            val symbol = f.nsSymbol ?: continue
            checker.inferenceNamespaceStack.addLast(symbol)
            namespacesPushed++
        }
        try {
            checker.withCtaFrameLocals(frame) {
                if (wantsCapture) typeCaptureRecordAt(node, checker.spineFileName)
                if (wantsSink) checkedSinkEmit(node)
                if (wantsAnswer) nodeAnswerRecord(node)
            }
        } finally {
            repeat(namespacesPushed) { checker.inferenceNamespaceStack.removeLast() }
            checker.currentClassForThis = savedThis
            checker.inNonArrowFunctionBody = savedInFn
            checker.inAsyncFunctionBody = savedAsync
            checker.inGeneratorFunctionBody = savedGen
            checker.currentTypeParamDecls = savedTpDecls
            checker.currentTypeParamScope = savedTpScope
            checker.currentFlowGraph = savedFlowGraph
        }
    }

    /**
     * (KIR) Hands [node] to [checkedSink] under the ambient [typeCaptureVisit] has
     * just reconstructed.
     *
     * `currentCheckFileName` is installed for [typeCaptureRecord]'s reason: it is
     * the key into `fileLocalTypeMaps`, which [getTypeOfIdentifier] consults
     * before any declaration table. Everything else the lens reads
     * (`currentLocalTypes`, the frames, `currentFlowGraph`, the lexical chain) is
     * already live because this runs inside the walk.
     */
    private fun checkedSinkEmit(node: Node) {
        val sink = checker.checkedSink ?: return
        val lens = checker.checkedLens ?: return
        val saved = checker.currentCheckFileName
        checker.currentCheckFileName = checker.spineFileName
        try {
            if (node is Expression) sink.expression(node, lens) else sink.declaration(node, lens)
        } finally {
            checker.currentCheckFileName = saved
        }
    }

    /**
     * (INV.1) Records the walk's answer for [node] into the current file's store,
     * FIRST WINS, under the ambient [typeCaptureVisit] has just reconstructed.
     *
     * The refusal comes BEFORE the computation: a node that already holds an
     * answer costs no resolution, which is what keeps [nodeAnswerComputations]
     * equal to the stores' `recorded` sum. What is computed is
     * [typeCaptureReportedType] — the capture's own rule, so a member name
     * answers the type of its ACCESS (BUG.4) and a member declaration name its
     * declared type (API.11), never the free-name collider.
     *
     * `currentCheckFileName` is installed for [typeCaptureRecord]'s reason: it is
     * the key `getTypeOfIdentifier` reaches `fileLocalTypeMaps` through, and the
     * spine's own handlers leave it at rest between anchors.
     */
    private fun nodeAnswerRecord(node: Expression) {
        val store = checker.nodeAnswerStoreCurrentFile ?: return
        if (store.has(node)) return
        checker.nodeAnswerComputations++
        val saved = checker.currentCheckFileName
        checker.currentCheckFileName = checker.spineFileName
        try {
            // (INV.1b) the reconstruction-only arm records a placeholder and resolves nothing.
            val recorded =
                if (checker.nodeAnswerChannels and NodeAnswers.TYPES != 0) typeCaptureReportedType(node) else anyType
            store.record(node, recorded)
            // (INV.2) The three companion channels, from the SAME visit under the
            // SAME ambient, behind the type's own first-wins gate above — so a node
            // holds all four or none. Each is the capture's own rule generalised:
            // the symbol is what the definition channel resolves (alias NOT
            // followed — that is `aliasedSymbol`'s question), the call is what the
            // KIR sink's `CallFact` records, the contextual type is (API.10)'s walk.
            if (checker.nodeAnswerChannels and NodeAnswers.SYMBOLS != 0 &&
                (node is Identifier || typeCaptureIsMemberNameLiteral(node))
            ) {
                store.recordSymbols(node, typeCaptureSymbolsAt(node))
            }
            if (checker.nodeAnswerChannels and NodeAnswers.CALLS != 0) when (node) {
                is CallExpression -> {
                    val signatures = typeCaptureCallSignaturesOf(node.expression)
                    val selected =
                        if (signatures.isEmpty()) null else checker.resolveCallOverload(signatures, node.arguments)
                    store.recordCall(node, ResolvedCall(selected, signatures.size))
                }
                is NewExpression -> {
                    val signatures = typeCaptureConstructSignaturesOf(node)
                    val selected =
                        if (signatures.isEmpty()) null
                        else checker.resolveCallOverload(signatures, node.arguments ?: emptyList())
                    store.recordCall(node, ResolvedCall(selected, signatures.size))
                }
                else -> {}
            }
            if (checker.nodeAnswerChannels and NodeAnswers.CONTEXTUAL != 0) {
                typeCaptureContextualType(node, 0)?.let { store.recordContextual(node, it) }
            }
        } finally {
            checker.currentCheckFileName = saved
        }
    }

    /**
     * (INV.2) What the name [id] resolves to, as the walk's own state answers it
     * RIGHT NOW — the symbol half of [typeCaptureDefinitionAt], with two
     * deliberate differences. An import ALIAS is answered as itself (tsgo's
     * `getSymbolAtLocation` / `getAliasedSymbol` split; the definition channel
     * follows the alias because a user navigating wants the declaration), and a
     * declaration NAME answers its own symbol rather than a location list.
     *
     * The order of the legs is the definition path's and is load-bearing in the
     * same way: a member DECLARATION name and an object-literal KEY must be
     * answered through their OWNER before the free-name path can resolve their
     * SPELLING to whatever unrelated binding shares it (BUG.4's collider).
     */
    private fun typeCaptureSymbolsAt(id: Node): List<Symbol> {
        if (id is Identifier) typeCaptureMemberDeclarationSymbols(id)?.let { return it }
        typeCaptureObjectLiteralKeySymbols(id)?.let { return it }
        if (id is Identifier && typeCaptureIsFreeName(id)) {
            val resolved = checker.spineScopeLookup(id.text) ?: checker.lookupPerFileForNode(id, id.text) ?: return emptyList()
            return listOf(resolved)
        }
        return typeCaptureMemberSymbols(id)
    }

    /**
     * (INV.2) The symbol a member DECLARATION name declares, or null when [id] is
     * not one — [typeCaptureMemberDeclarationType]'s rule, answering the symbol
     * instead of its type. An enum member is the enum symbol's export of that
     * name; an object literal's own member is left to the key leg. Empty (not
     * null) when the owner resolves but declares no such member, so the caller
     * never falls through to the free-name path for a member position.
     */
    private fun typeCaptureMemberDeclarationSymbols(nodeIn: Identifier): List<Symbol>? {
        val member = (nodeIn as NodeBase).parent ?: return null
        val node = typeCaptureMemberNameIdentifier(member)?.takeIf { it === nodeIn } ?: return null
        val owner = (member as NodeBase).parent ?: return null
        if (owner is ObjectLiteralExpression) return null
        if (owner is EnumDeclaration) {
            return typeCaptureOwnerSymbol(owner)?.exports?.get(node.text)?.let { listOf(it) } ?: emptyList()
        }
        val ownerType = when (owner) {
            is TypeLiteral -> checker.getTypeFromTypeNode(owner)
            else -> typeCaptureOwnerSymbol(owner)?.let { checker.getDeclaredTypeOfSymbol(it) }
        } ?: return emptyList()
        val symbols = ArrayList<Symbol>(2)
        typeCaptureCollectMembers(ownerType, node.text, symbols, 0)
        return symbols
    }

    /**
     * (INV.2) The symbol an object-literal KEY declares — the literal's OWN
     * property, which is tsc's `getSymbolAtLocation` answer for a declaration
     * name — or, where the literal's type carries none, the CONTEXTUAL type's
     * member ((API.10)'s leg). Null when [id] is not a key.
     */
    private fun typeCaptureObjectLiteralKeySymbols(id: Node): List<Symbol>? {
        val (assignment, name) = typeCaptureObjectLiteralKey(id) ?: return null
        val literal = (assignment as NodeBase).parent as? ObjectLiteralExpression ?: return emptyList()
        val own = ArrayList<Symbol>(1)
        typeCaptureCollectMembers(nodeAnswerTypeOrCompute(literal), name, own, 0)
        if (own.isNotEmpty()) return own
        return typeCaptureContextualMembers(literal, name)
    }

    /**
     * (INV.2) The type of [expression] as the store ALREADY holds it, else
     * computed now. The spine is preorder, so a literal or a receiver is recorded
     * before its keys and member names are reached — and `getTypeOfExpression`
     * has no per-node memo (round 737): an object literal MINTS its member table
     * on every call, which made the key leg O(keys²) on tsc's message tables
     * (measured: `IntKeyMap.set` under `getTypeOfObjectLiteral` was 11% of the
     * symbols arm's samples, design § 9b). Off the store this is exactly the
     * old computation, so the capture path is unchanged.
     */
    private fun nodeAnswerTypeOrCompute(expression: Expression): Type =
        checker.nodeAnswerStoreCurrentFile?.typeAt(expression) ?: checker.getTypeOfExpression(expression)

    /**
     * (API.3) Records everything a capture records at [node] — its type, and, when
     * it is a free name, where that name is declared.
     *
     * Both under the installed ambient of [typeCaptureVisit], though only the TYPE
     * needs it. The definition's own walk-scoped input is [spineCurrentScope], which
     * the walk maintains per node and pushes BEFORE a node's enter handlers, so it
     * is already correct here and needs no reconstruction. That asymmetry is the
     * whole shape of (API.3b) versus (API.3a) and is worth stating: the cta frames
     * are install-and-restore, the lexical scope chain is not.
     */
    private fun typeCaptureRecordAt(node: Node, fileName: String) {
        val key = TypeCaptureRequest.packSpanKey(node.pos, node.end)
        if (node is Expression) {
            typeCaptureRecord(node, fileName)
            // (API.9) …and a literal that NAMES a member, `o["p"]` or — since
            // (API.16) — ``o[`p`]``. Those are the only non-[Identifier] spans the
            // definition path accepts, and they are accepted at the record site rather
            // than inside so that no other literal in the program pays for the test.
            if (node is Identifier || typeCaptureIsMemberNameLiteral(node)) {
                typeCaptureRecordDefinition(node, fileName)
            }
            // (API.4a) The member enumeration, for the spans that asked for one. The
            // membership test is HERE rather than at the per-node hook because the
            // node has already matched the union key set by the time this runs: a
            // completion costs the spine nothing that a hover does not already cost.
            val memberKeys = checker.typeCaptureMemberKeysCurrentFile
            if (memberKeys != null && key in memberKeys) {
                typeCaptureRecordMembers(node, fileName)
            }
            // (API.6) The signature resolution, for the spans that asked for one.
            // Same placement and same argument as the member enumeration: the node
            // has already matched the union key set by the time this runs.
            val signatureArgs = checker.typeCaptureSignatureArgsCurrentFile
            if (signatureArgs != null) {
                val activeArgument = signatureArgs[key]
                if (activeArgument != null) {
                    typeCaptureRecordSignatures(node, fileName, activeArgument)
                }
            }
        }
        // (API.4b) The scope enumeration. Deliberately OUTSIDE the [Expression]
        // gate: a free-name caret between statements is anchored at a Block or at
        // the source file, which is not an expression and has no type to record.
        val scopeKeys = checker.typeCaptureScopeKeysCurrentFile
        if (scopeKeys != null && key in scopeKeys) {
            typeCaptureRecordScope(node, fileName)
        }
    }

    /**
     * (API.4a) Records what [node]'s type calls its own, FIRST WINS — except that a
     * DESCENDANT overwrites its ancestor, for [typeCaptureMemberNodes]' reason.
     *
     * First-wins for [typeCaptureRecord]'s reason: the innermost ambient reaches the
     * node first, and a later visit of the SAME node under a looser one must not
     * overwrite it.
     *
     * An entry is written even when nothing was found, so a caller can tell "the
     * receiver was reached and has no members" from "the span was never walked" —
     * the two are different facts and only one of them is a bug.
     */
    private fun typeCaptureRecordMembers(node: Expression, fileName: String) {
        val span = TypeCaptureSpan(fileName, node.pos, node.end)
        val previous = typeCaptureMemberNodes[span]
        if (previous != null && !typeCaptureIsDescendantOf(node, previous)) return
        typeCaptureMemberNodes[span] = node
        val savedCheckFileName = checker.currentCheckFileName
        checker.currentCheckFileName = fileName
        try {
            val collected = typeCaptureCompletionMembers(node)
            // (API.7) The class governing `private`/`protected` access AT THE CARET.
            // A pointer walk out of the receiver, so a caret in a nested arrow inside a
            // method reaches the method's class exactly as a caret in the method body
            // does, and a caret in a class nested inside another reaches the INNER one
            // — which is the rule, `private` being per-class rather than per-nesting.
            val enclosingClass = typeCaptureEnclosingClass(node)
            val members = ArrayList<CapturedMember>(collected.size)
            for ((name, symbols) in collected) {
                // An empty name is a member table's own placeholder and `__`-prefixed
                // names are the compiler's internal entries; neither is writable
                // after a dot, so neither is a completion.
                if (name.isEmpty() || name.startsWith("__")) continue
                val item = typeCaptureMemberItem(name, symbols)
                if (typeCaptureMemberInaccessible(item, symbols, enclosingClass)) continue
                members.add(item)
            }
            members.sortBy { it.name }
            typeCaptureMemberResults[span] =
                CapturedMembers(fileName, node.pos, node.end, members)
        } finally {
            checker.currentCheckFileName = savedCheckFileName
        }
    }

    /**
     * (API.4a) True when [node] is strictly below [ancestor] in the parse — INV.2(a)
     * stamps `parent` at the end of every parse, so this is a pointer walk.
     *
     * Bounded by the tree's depth and by nothing else, which is safe here for the
     * one reason it would not be in a general walker: it runs only on a SPAN
     * COLLISION, i.e. between two nodes already known to start and end together, so
     * the walk terminates within a handful of steps or not at all — and a
     * synthesized node with no parent chain simply answers false.
     */
    private fun typeCaptureIsDescendantOf(node: Node, ancestor: Node): Boolean {
        var current: Node? = (node as NodeBase).parent
        while (current != null) {
            if (current === ancestor) return true
            current = (current as NodeBase).parent
        }
        return false
    }

    /**
     * (API.6) Records every signature the callee of the call at [node] has, and
     * which of them is the active one for the argument the caret is in.
     *
     * First-wins with [typeCaptureRecordMembers]' deepest-wins exception, for the
     * same reasons.
     *
     * ## The callee resolution is (API.3d)'s, not a new one
     *
     * [getCalleeType] is what the argument checker itself calls, so a plain name, a
     * METHOD through a receiver, a namespace member, an imported function, an
     * element access and a callee that is ITSELF a call all resolve here with no
     * rule of their own — which is the whole reason this is a small addition rather
     * than a mechanism. A `NewExpression` asks for CONSTRUCT signatures instead, and
     * falls back to [getReturnTypeOfNewExpression]'s own resolution when the callee's
     * value type carries none: a class NAME in value position does not type as its
     * own constructor here, while the INSTANCE type it resolves to does carry the
     * class's construct signatures (round 475).
     *
     * An entry is written even when nothing was found, so a caller can tell "the
     * call was reached and its callee has no signatures" from "the span was never
     * walked" — the two are different facts and only one of them is a bug. A span
     * that turns out not to be a call at all claims nothing, so a collision cannot
     * make a real call's answer disappear.
     */
    private fun typeCaptureRecordSignatures(node: Expression, fileName: String, activeArgument: Int) {
        val callee: Expression
        val arguments: List<Expression>
        // Non-null exactly for a `new`, which is what says CONSTRUCT signatures are
        // wanted — carried as the node rather than as a boolean so the construct leg
        // needs no cast back.
        val construct: NewExpression?
        when (node) {
            is CallExpression -> {
                callee = node.expression
                arguments = node.arguments
                construct = null
            }
            is NewExpression -> {
                callee = node.expression
                arguments = node.arguments ?: emptyList()
                construct = node
            }
            else -> return
        }
        val span = TypeCaptureSpan(fileName, node.pos, node.end)
        val previous = typeCaptureSignatureNodes[span]
        if (previous != null && !typeCaptureIsDescendantOf(node, previous)) return
        typeCaptureSignatureNodes[span] = node
        val savedCheckFileName = checker.currentCheckFileName
        checker.currentCheckFileName = fileName
        try {
            val signatures =
                if (construct != null) typeCaptureConstructSignaturesOf(construct)
                else typeCaptureCallSignaturesOf(callee)
            val name = typeCaptureCalleeName(callee)
            val rendered = signatures.map {
                typeCaptureRenderSignature(it, construct != null, name, activeArgument)
            }
            typeCaptureSignatureResults[span] = CapturedSignatures(
                fileName = fileName,
                start = node.pos,
                end = node.end,
                signatures = rendered,
                activeSignature = typeCaptureActiveSignature(signatures, arguments, activeArgument),
            )
        } finally {
            checker.currentCheckFileName = savedCheckFileName
        }
    }

    /**
     * (API.6) The call signatures `<callee>(…)` may select.
     *
     * Two legs, and the first is the same second mechanism (API.3d) and (API.4a)
     * both needed: a NAMESPACE's, a MODULE's and an ENUM's members are on no TYPE at
     * all — they are entries in an export table — so `ns.fn(` answers nothing
     * through [getCalleeType], which resolves the receiver to `any`. Measured before
     * this leg existed: zero signatures for a namespace-qualified callee.
     *
     * ADDITIVE by construction: the export leg is consulted first and only ADOPTED
     * when it produced signatures, so every shape the type path already answered is
     * untouched. That ordering is what keeps a class's static side, whose members are
     * mirrored into both tables, answering the same as before.
     */
    fun typeCaptureCallSignaturesOf(callee: Expression): List<Signature> {
        if (callee is PropertyAccessExpression) {
            val exported = typeCaptureExportedMember(callee.expression, callee.name.text)
            if (exported != null) {
                val exportedSignatures = checker.getCallSignaturesOfType(checker.getTypeOfSymbol(exported))
                if (exportedSignatures.isNotEmpty()) return exportedSignatures
            }
        }
        return checker.getCallSignaturesOfType(checker.getCalleeType(callee))
    }

    /**
     * (API.6) The construct signatures `new <callee>(…)` may select.
     *
     * Two legs, in this order, and the second is not a fallback for a failure but for
     * a DIFFERENT SHAPE: a `declare var X: XConstructor` types as an interface whose
     * construct signatures the first leg finds, while a `class C` NAME does not type
     * as its own constructor at all — the construct signatures live on the INSTANCE
     * type (round 475: `resolveInterfaceMembers` populates a class instance's own
     * constructor plus its bases', inherited-first). [getReturnTypeOfNewExpression] is
     * what already knows how to reach that instance type through every shape the
     * compiler supports (a namespace-qualified class, a clodule, an explicit type
     * argument list), so it is asked rather than re-derived.
     */
    fun typeCaptureConstructSignaturesOf(node: NewExpression): List<Signature> {
        val direct = checker.getConstructSignaturesOfType(checker.getCalleeType(node.expression))
        if (direct.isNotEmpty()) return direct
        val instance = checker.getReturnTypeOfNewExpression(node)
        if (instance === anyType || instance === errorType) return emptyList()
        return checker.getConstructSignaturesOfType(instance)
    }

    /**
     * (API.6) The name to put in front of a rendered signature, or `""`.
     *
     * Syntactic and deliberately shallow: the callee's own spelling where it has one
     * (`f`, and the `m` of `o.m`), nothing at all for a computed or expression callee
     * (`(fs[i])(…)`). A label is a thing a user READS, so it names what the user
     * wrote; inventing a name for an anonymous callee — the declaration's, say —
     * would put a spelling in the label that is not the one at the caret.
     */
    private fun typeCaptureCalleeName(callee: Expression): String = when (callee) {
        is Identifier -> callee.text
        is PropertyAccessExpression -> callee.name.text
        is ParenthesizedExpression -> typeCaptureCalleeName(callee.expression)
        is NonNullExpression -> typeCaptureCalleeName(callee.expression)
        else -> ""
    }

    /**
     * (API.6) [sig] as one line of text, with each parameter's range inside that line.
     *
     * ## One type-printing convention, on purpose
     *
     * Every type here goes through [typeToString] — the renderer hover uses and the
     * renderer a diagnostic message uses. A signature help label and a hover string
     * describing the same type must not be able to disagree, so no second convention
     * is introduced; the ONE place an AST-derived rendering is used instead is the
     * misaligned-declaration path below, which is exactly where the resolved types
     * are known not to correspond to the parameters.
     *
     * Deliberately NOT [signatureToString]: that renderer serves diagnostics, where a
     * `p?: string` is spelled `p?: string | undefined` because a TS2345 message is
     * about assignability. A signature label is about what a caller may WRITE.
     *
     * ## Generic signatures render UNINSTANTIATED
     *
     * `<T>(xs: T[], i: number): T` rather than a substitution. Instantiating would
     * mean inferring `T` from arguments the user has not finished typing — the value
     * would change under every keystroke and be wrong for the argument still being
     * written — and the declared form is what tells the reader that `T` is inferred
     * at all.
     *
     * ## Where a parameter list is rendered from the AST instead
     *
     * A parameter declared with a BINDING PATTERN (`function f({ a }: O, b: number)`)
     * is dropped from `Signature.parameters` by [getParameterSymbols] unless the
     * signature was built for display, and the surviving symbols keep a POSITIONAL
     * zip of the declaration's type annotations — so rendering such a signature from
     * the symbols alone would print `f(b: O)`: one parameter short, and the survivor
     * wearing its neighbour's type. That is a plausible-looking lie rather than a
     * coarse answer, so when the declaration's own parameter list is longer, THE
     * DECLARATION is what is rendered: the pattern spelled as source and each type
     * resolved from its annotation.
     */
    private fun typeCaptureRenderSignature(
        sig: Signature,
        isConstruct: Boolean,
        name: String,
        activeArgument: Int,
    ): CapturedSignature {
        val label = StringBuilder()
        if (isConstruct) label.append("new ")
        label.append(name)
        val typeParameters = sig.typeParameters
        if (!typeParameters.isNullOrEmpty()) {
            label.append('<')
            label.append(typeParameters.joinToString(", ") { checker.typeParamToString(it) })
            label.append('>')
        }
        label.append('(')
        val parameters = ArrayList<CapturedSignatureParameter>()
        for (part in typeCaptureSignatureParameters(sig)) {
            if (parameters.isNotEmpty()) label.append(", ")
            val start = label.length
            if (part.isRest) label.append("...")
            label.append(part.name)
            if (part.optional) label.append('?')
            label.append(": ").append(part.typeText)
            parameters.add(
                CapturedSignatureParameter(
                    name = part.name,
                    typeText = part.typeText,
                    optional = part.optional,
                    isRest = part.isRest,
                    labelStart = start,
                    labelEnd = label.length,
                ),
            )
        }
        label.append(')')
        val returnTypeText = sig.resolvedReturnType?.let { typeCaptureRenderType(it) } ?: "any"
        label.append(": ").append(returnTypeText)
        return CapturedSignature(
            label = label.toString(),
            parameters = parameters,
            returnTypeText = returnTypeText,
            activeParameter = typeCaptureActiveParameter(parameters, activeArgument),
        )
    }

    /** (API.6) One parameter of a signature, before it is written into a label. */
    private class SignatureParameterPart(
        val name: String,
        val typeText: String,
        val optional: Boolean,
        val isRest: Boolean,
    )

    /**
     * (API.6) [sig]'s parameters as text — from its own symbols, or from its
     * declaration when the two disagree in LENGTH (see [typeCaptureRenderSignature]).
     */
    private fun typeCaptureSignatureParameters(sig: Signature): List<SignatureParameterPart> {
        val declared = typeCaptureDeclaredParameters(sig.declaration)
            ?.filter { (it.name as? Identifier)?.text != "this" }
        if (declared != null && declared.size > sig.parameters.size) {
            return declared.map { parameter ->
                SignatureParameterPart(
                    name = when (val n = parameter.name) {
                        is Identifier -> n.text
                        is ObjectBindingPattern, is ArrayBindingPattern ->
                            checker.formatBindingPatternFromAst(n as Node)
                        else -> "_"
                    },
                    typeText = typeCaptureAnnotationText(parameter),
                    optional = !parameter.dotDotDotToken &&
                        (parameter.questionToken || parameter.initializer != null),
                    isRest = parameter.dotDotDotToken,
                )
            }
        }
        return sig.parameters.mapIndexed { index, symbol ->
            val declaration = symbol.valueDeclaration as? Parameter
            val isRest = declaration?.dotDotDotToken == true
            SignatureParameterPart(
                name = when (val n = declaration?.name) {
                    is ObjectBindingPattern, is ArrayBindingPattern ->
                        checker.formatBindingPatternFromAst(n as Node)
                    else -> symbol.name
                },
                typeText = typeCaptureRenderType(checker.getTypeOfSymbol(symbol)),
                // `minArgumentCount` is the DECLARATION's required count, so this one
                // test covers `p?`, a defaulted parameter and everything after a rest.
                optional = !isRest && index >= sig.minArgumentCount,
                isRest = isRest,
            )
        }
    }

    /** (API.6) The parameter list of a signature's declaration, or null. */
    fun typeCaptureDeclaredParameters(declaration: Node?): List<Parameter>? =
        when (declaration) {
            is FunctionDeclaration -> declaration.parameters
            is FunctionExpression -> declaration.parameters
            is ArrowFunction -> declaration.parameters
            is MethodDeclaration -> declaration.parameters
            is Constructor -> declaration.parameters
            is FunctionType -> declaration.parameters
            is ConstructorType -> declaration.parameters
            else -> null
        }

    /**
     * (API.6) A parameter's annotated type as text, resolved through the compiler and
     * rendered by [typeToString].
     *
     * The AST spelling is preferred only where the resolution FAILED — a signature
     * rendered on this path was built with its type-parameter scope already torn
     * down, so a `T` resolves to `errorType`, and printing `error` where the source
     * says `T` would be worse than either. That is the same fallback
     * [formatParameter] applies for the same reason.
     */
    private fun typeCaptureAnnotationText(parameter: Parameter): String {
        val typeNode = parameter.type ?: return "any"
        val resolved = checker.getTypeFromTypeNode(typeNode)
        if (resolved === errorType || resolved === unresolvedType) {
            return checker.formatTypeForDisplay(typeNode) ?: "any"
        }
        return typeCaptureRenderType(resolved)
    }

    /**
     * (API.6) Which parameter of a rendered signature the caret's argument lands on,
     * or -1.
     *
     * The clamp is the whole content: once the caret is past a signature's fixed
     * parameters, a signature ending in a REST parameter is still receiving arguments
     * — every one of them feeds that parameter — so `push(a, b, c|)` must keep
     * `...items` highlighted rather than fall off the end. A signature with no rest
     * has genuinely run out of parameters, and -1 says so rather than pointing at the
     * last one.
     */
    private fun typeCaptureActiveParameter(
        parameters: List<CapturedSignatureParameter>,
        activeArgument: Int,
    ): Int = when {
        activeArgument < 0 -> -1
        activeArgument < parameters.size -> activeArgument
        parameters.lastOrNull()?.isRest == true -> parameters.size - 1
        else -> -1
    }

    /**
     * (API.6) Which of [signatures] a host should show first.
     *
     * ## The rule, stated so it can be argued with
     *
     * The FIRST signature that could still become this call, which is two conditions
     * and no scoring:
     *
     * 1. it has room for the argument the caret is on — that argument's index is
     *    within its parameter list, or it ends in a REST parameter, or it takes no
     *    parameters and the call so far passes none (a `()` signature IS satisfied by
     *    the empty argument list the caret is sitting in);
     * 2. it accepts every argument the user has already FINISHED — the arguments
     *    strictly before the active one — judged by [signatureAcceptsArgs], which is
     *    the same verdict [resolveCallOverload] selects an overload with, so a host's
     *    highlighted overload and the compiler's chosen one cannot drift apart.
     *
     * The argument the caret is IN is deliberately not judged: it is half-typed by
     * construction, so testing it would flip the highlighted overload back and forth
     * as the user types.
     *
     * When nothing qualifies the answer is 0 rather than -1: a caller that already
     * has a non-empty list wants an index it can show, and "the first one" is the
     * declaration order answer. That is reported rather than hidden — the case is a
     * call that matches no overload, which the diagnostics say anyway.
     */
    private fun typeCaptureActiveSignature(
        signatures: List<Signature>,
        arguments: List<Expression>,
        activeArgument: Int,
    ): Int {
        val completed = arguments.take(activeArgument.coerceAtLeast(0))
        for ((index, sig) in signatures.withIndex()) {
            val hasRest = (sig.parameters.lastOrNull()?.valueDeclaration as? Parameter)
                ?.dotDotDotToken == true
            val hasRoom = activeArgument < sig.parameters.size || hasRest ||
                (sig.parameters.isEmpty() && arguments.isEmpty())
            if (!hasRoom) continue
            if (!checker.signatureAcceptsArgs(sig, completed)) continue
            return index
        }
        return 0
    }

    /**
     * (API.4b) Records what the lexical scope chain binds AT [node], first-wins with
     * [typeCaptureRecordMembers]' deepest-wins exception.
     *
     * The whole answer is a function of [spineCurrentScope], which is why it can
     * only be taken here: the spine nulls that chain when it leaves a file
     * ([spineScopeClear]), so a post-hoc query sees an empty chain and can answer
     * with globals and nothing else — `ScopeCaptureMeasurementTest` measures exactly
     * that, through [postHocScopeNamesForTesting].
     *
     * An entry is written even when nothing was found, so "the anchor was reached
     * and binds nothing" stays distinguishable from "the span was never walked".
     */
    private fun typeCaptureRecordScope(node: Node, fileName: String) {
        val span = TypeCaptureSpan(fileName, node.pos, node.end)
        val previous = typeCaptureScopeNodes[span]
        if (previous != null && !typeCaptureIsDescendantOf(node, previous)) return
        typeCaptureScopeNodes[span] = node
        typeCaptureScopeResults[span] =
            CapturedScope(fileName, node.pos, node.end, typeCaptureScopeNames(fileName))
    }

    /**
     * (API.4b) Every name writable bare at the spine's current position, innermost
     * binding first and each spelling once.
     *
     * ## This is [spineScopeLookup]'s walk, enumerated
     *
     * Level by level from [spineCurrentScope] outwards: the level's own scope-space
     * bindings ([LexicalScope.symbols]) and then the binder table it aliases
     * ([LexicalScope.existing]), first sighting wins, then the parent. That is
     * literally the traversal [spineScopeLookup] performs to answer ONE name, run to
     * exhaustion — which is the correctness argument in one sentence: **a name this
     * offers is a name go-to-definition will resolve, and a name it hides is hidden
     * because something nearer binds the spelling.** Any other traversal would let
     * the two disagree, and a completion list that offers what navigation cannot
     * follow is worse than a shorter one.
     *
     * ## Why `existing` is read here although INV.2(c) tells a RESOLVER not to
     *
     * CLAUDE.md's round-748 rule — *"a checker resolver consulting `lexicalScopes`
     * must read `scope.symbols` ONLY"* — is about a resolver whose soundness
     * argument is that it cannot change how any existing name resolves, which holds
     * precisely because `symbols` excludes everything the main binder bound. An
     * ENUMERATION has no such freedom: the source file's own `symbols` holds only
     * the block-hoisted leftovers (B83.5), so a `symbols`-only sweep would offer no
     * file-level declaration and no import at all. [spineScopeLookup] already reads
     * both, deliberately and since (API.3b); this reads exactly what it reads.
     *
     * ## Why the untrusted-level rule is NOT applied, which is a divergence
     *
     * [lexLevelHasName] SKIPS a ModuleDeclaration or EnumDeclaration level, because
     * there the aliased table carries all merged members while the unresolved-names
     * walk it serves applies its own export filtering — so trusting the level would
     * SUPPRESS a genuine TS2304. That rule belongs to that chain, which has a second,
     * threaded population to fall back on. This chain has none: skipping those levels
     * would offer nothing at all at a caret inside a namespace body, where every one
     * of the namespace's own members is legally writable. The cost of not skipping is
     * that a namespace merged across files can offer a sibling declaration's
     * non-exported member; `ProjectFreeNameCompletionTest` pins the namespace case,
     * and applying the rule reddens it.
     */
    fun typeCaptureScopeNames(fileName: String): List<CapturedName> {
        val collected = LinkedHashMap<String, Symbol>()
        var scope = checker.spineCurrentScope
        while (scope != null) {
            for ((name, symbol) in scope.symbols) if (name !in collected) collected[name] = symbol
            scope.existing?.let { table ->
                for ((name, symbol) in table) if (name !in collected) collected[name] = symbol
            }
            scope = scope.parent
        }
        typeCaptureCollectGlobalNames(collected, fileName)
        val names = ArrayList<CapturedName>(collected.size)
        for ((name, symbol) in collected) {
            if (!typeCaptureIsWritableName(name)) continue
            val declaration = symbol.valueDeclaration ?: symbol.declarations.firstOrNull()
            names.add(CapturedName(name, declaration?.kind?.name ?: "Unknown"))
        }
        names.sortBy { it.name }
        return names
    }

    /**
     * (API.4b) Adds the merged GLOBALS visible in [fileName] to [out], skipping
     * every spelling something nearer already bound.
     *
     * ## The per-file filter is the point, not a precaution
     *
     * A bare `globals` sweep is exactly the INV.3 conflation this repo spent a
     * migration removing: it would offer one module file's exported name inside
     * every other file. [globalsForFile] is the primitive that answers "does this
     * name have a meaning HERE", and it returns null exactly where the legacy
     * consult would have leaked. Under INV.3(d) most module-only names are not in
     * [globals] at all, so the filter is usually a set membership and a return —
     * but it is what makes the sweep correct rather than accidentally correct.
     *
     * The key set is SNAPSHOT before the loop: [globalsForFile] can resolve an
     * import alias on its way to an answer, and iterating a table a callee may touch
     * is a concurrent-modification bug that would fire on a project shape no test
     * here happens to carry.
     */
    private fun typeCaptureCollectGlobalNames(out: MutableMap<String, Symbol>, fileName: String) {
        val names = checker.globals.keys.toList()
        for (name in names) {
            if (name in out) continue
            out[name] = checker.globalsForFile(fileName, name) ?: continue
        }
    }

    /**
     * (API.4b) True when [name] can be typed at a free position as it stands.
     *
     * A symbol table's keys are not all identifiers: the compiler's own entries are
     * `__`-prefixed, an object type's index signature and a quoted member land under
     * spellings with spaces and punctuation in them, and a completion that inserts
     * one of those produces text that does not parse. The test is on the CHARACTERS
     * rather than on a flag, because the property wanted is exactly "this is a
     * writable identifier".
     */
    private fun typeCaptureIsWritableName(name: String): Boolean {
        if (name.isEmpty() || name.startsWith("__")) return false
        val first = name[0]
        if (!(first.isLetter() || first == '_' || first == '$')) return false
        for (i in 1 until name.length) {
            val c = name[i]
            if (!(c.isLetterOrDigit() || c == '_' || c == '$')) return false
        }
        return true
    }

    /**
     * (API.4a) Every member the RECEIVER [node] offers, name to contributing
     * symbols.
     *
     * The legs are (API.3d)'s, in (API.3d)'s order and for (API.3d)'s reasons —
     * this is the same resolution one question wider: ENUMERATE rather than look up
     * one name.
     *
     * 1. `this` is [Identifier]`("this")` in this parser, so it types as `any` and
     *    finds nothing; its real type is `currentClassForThis`, which the capture
     *    hook restores from the cta frame and which that frame deliberately leaves
     *    NULL inside a STATIC member — a static `this` therefore offers nothing
     *    rather than offering instance members.
     * 2. A namespace, a module alias and an ENUM answer from their own EXPORT table
     *    and never from a type (an enum's own type is a member-LESS `Type.Object`
     *    and a namespace identifier types as `any`). A non-empty export table is
     *    the whole answer, exactly as it is for a definition: the same receiver
     *    cannot mean two things at once, and merging a type's members in on top
     *    would mix a class's statics into its instance side.
     * 3. Everything else goes through the type.
     */
    private fun typeCaptureCompletionMembers(node: Expression): Map<String, List<Symbol>> {
        if (node is Identifier && node.text == "this") {
            val cls = checker.currentClassForThis ?: return emptyMap()
            val thisType = checker.resolveUncalledThisType(cls) ?: return emptyMap()
            return typeCaptureMembersOfType(thisType, 0)
        }
        typeCaptureExportedMembers(node)?.let { return it }
        return typeCaptureMembersOfType(checker.getTypeOfExpression(node), 0)
    }

    /**
     * (API.4a) Every EXPORT of whatever [receiver] names, or null when it names
     * nothing with an export table.
     *
     * The enumerating twin of [typeCaptureExportedMember], sharing its scope lookup
     * and its import hop, and — like it — following only a BARE identifier receiver:
     * a longer chain would need its middle segment resolved the same way.
     */
    private fun typeCaptureExportedMembers(receiver: Node): Map<String, List<Symbol>>? {
        val root = receiver as? Identifier ?: return null
        val resolved =
            checker.spineScopeLookup(root.text) ?: checker.lookupPerFileForNode(root, root.text) ?: return null
        val exports = typeCaptureFollowImportAlias(resolved).exports ?: return null
        if (exports.isEmpty()) return null
        val out = LinkedHashMap<String, List<Symbol>>(exports.size)
        for ((name, symbol) in exports) out[name] = listOf(symbol)
        return out
    }

    /**
     * (API.4a) Every member [type] has, name to the symbols that contribute it.
     *
     * A LIST per name because a UNION receiver's member is declared once per
     * constituent and the answer's type text is all of them; for every other shape
     * the list is a singleton.
     *
     * ## The union rule, which is NOT [typeCaptureCollectMembers]'
     *
     * Only a member present on EVERY constituent survives, because only such a
     * member may legally be written after the dot. The definition walk beside this
     * one deliberately COLLECTS instead — "where is `p` declared" is asked about a
     * name that is already in the text, and every declaration of it is a real place
     * to go, while "what may I write here" must not offer something that will not
     * compile.
     *
     * `undefined` / `null` / `void` constituents are SKIPPED rather than emptying
     * the intersection: they contribute no members, so treating them as a veto would
     * make every `strictNullChecks` union and every optional chain answer nothing,
     * and the access itself is separately diagnosed.
     *
     * An INTERSECTION contributes the union of its constituents' members, which is
     * what `A & B` has.
     *
     * A primitive receiver (`"s".`) and a bare type parameter (`t.` where
     * `T extends I`) reach their members through [getApparentType] — the wrapper
     * interface and the constraint respectively. `any` has no apparent type other
     * than itself, so an `any` receiver answers EMPTY, which is the same answer tsc
     * gives and is not a gap.
     *
     * Only the instance member table is read. `staticMembers` is deliberately not
     * merged: it is dual-populated into `members` where it belongs, and reading it
     * here would offer a class's statics through an instance.
     */
    private fun typeCaptureMembersOfType(type: Type, depth: Int): Map<String, List<Symbol>> {
        if (depth > TYPE_CAPTURE_MEMBER_MAX_DEPTH) return emptyMap()
        return when (type) {
            is Type.Union -> {
                var surviving: LinkedHashMap<String, MutableList<Symbol>>? = null
                for (constituent in type.types) {
                    if (constituent === undefinedType ||
                        constituent === nullType ||
                        constituent === voidType
                    ) {
                        continue
                    }
                    val members = typeCaptureMembersOfType(constituent, depth + 1)
                    val accumulated = surviving
                    if (accumulated == null) {
                        val fresh = LinkedHashMap<String, MutableList<Symbol>>(members.size)
                        for ((name, symbols) in members) fresh[name] = ArrayList(symbols)
                        surviving = fresh
                    } else {
                        val entries = accumulated.entries.iterator()
                        while (entries.hasNext()) {
                            val entry = entries.next()
                            val alsoHere = members[entry.key]
                            if (alsoHere == null) entries.remove() else entry.value.addAll(alsoHere)
                        }
                    }
                }
                surviving ?: emptyMap()
            }
            is Type.Intersection -> {
                val out = LinkedHashMap<String, MutableList<Symbol>>()
                for (constituent in type.types) {
                    for ((name, symbols) in typeCaptureMembersOfType(constituent, depth + 1)) {
                        out.getOrPut(name) { ArrayList(1) }.addAll(symbols)
                    }
                }
                out
            }
            is Type.Object -> {
                // A member table is LAZY (round 833) — a reader that does not resolve
                // first answers differently depending on whether an earlier line in
                // the file happened to resolve this type. Resolving is also what makes
                // an INHERITED member appear at all, and appear ONCE: the base's own
                // symbol is copied into the derived table, and an override replaces
                // it under the same key.
                checker.resolveStructuredTypeMembers(type)
                val members = type.members ?: return emptyMap()
                val out = LinkedHashMap<String, List<Symbol>>(members.size)
                for ((name, symbol) in members) out[name] = listOf(symbol)
                out
            }
            else -> {
                val apparent = checker.getApparentType(type)
                if (apparent !== type) typeCaptureMembersOfType(apparent, depth + 1) else emptyMap()
            }
        }
    }

    /** (API.4a) [name] and the symbols declaring it, as one completion candidate. */
    private fun typeCaptureMemberItem(name: String, symbols: List<Symbol>): CapturedMember {
        // A LinkedHashSet: a union whose constituents give the member the SAME type
        // must render that type once, and the order is constituent order.
        val types = LinkedHashSet<String>()
        for (symbol in symbols) types.add(typeCaptureRenderType(checker.getTypeOfSymbol(symbol)))
        val first = symbols[0]
        val declaration = first.valueDeclaration ?: first.declarations.firstOrNull()
        val modifiers = typeCaptureMemberModifiers(first)
        // (INC.23) THE READER HOOK — the rendering a user would see, with the
        // provenance of the resolution behind it. `memo` says the answer came from
        // `symbolTypes` rather than from a fresh resolution, which is the half of the
        // race a caret can observe.
        if (SymTypeOrderCensus.on) {
            SymTypeOrderCensus.noteMember(
                name,
                types.joinToString(" | "),
                first.id,
                checker.symbolTypes[first.id] != null,
                declaration?.kind?.name ?: "Unknown",
                declaration?.let { typeCaptureEnclosingFile(it)?.fileName } ?: "<none>",
                declaration?.pos ?: -1,
            )
        }
        return CapturedMember(
            name = name,
            kind = declaration?.kind?.name ?: "Unknown",
            typeText = types.joinToString(" | "),
            optional = symbols.any { checker.isOptionalProperty(it) },
            readonly = symbols.any { ModifierFlag.Readonly in typeCaptureMemberModifiers(it) },
            accessibility = when {
                // A `#name` field is private by its spelling and carries no modifier.
                name.startsWith("#") || ModifierFlag.Private in modifiers -> "private"
                ModifierFlag.Protected in modifiers -> "protected"
                else -> "public"
            },
        )
    }

    /**
     * (API.7) True when [item] is PROVABLY inaccessible from a caret governed by
     * [enclosingClass] — the accessibility filter round 917 refused to build.
     *
     * ## Prove-to-hide, never guess-to-hide
     *
     * Every unknown answers ACCESSIBLE. A member whose declaring class cannot be
     * found, a base class named by an expression this does not resolve, a heritage
     * chain that runs past its depth cap — each leaves the item in the list, because a
     * completion list that has silently lost a real candidate is indistinguishable
     * from a complete one, which is exactly why round 917 reported accessibility
     * instead of acting on it. Hiding happens only where the rule is decided.
     *
     * ## The rule
     *
     * `private` (including a `#name` field, which carries no modifier and is private
     * by its spelling) is visible only INSIDE its declaring class. `protected` is
     * visible inside the declaring class and inside any class that derives from it.
     * Both hold for `static` members unchanged, because the governing class of the
     * caret and the declaring class of the member are the same two questions either
     * way.
     */
    private fun typeCaptureMemberInaccessible(
        item: CapturedMember,
        symbols: List<Symbol>,
        enclosingClass: Node?,
    ): Boolean {
        if (item.accessibility == "public") return false
        // A member reached through a UNION receiver is declared once per constituent;
        // it is offered when ANY contributing declaration is reachable, which is the
        // permissive direction this filter is deliberately biased in.
        for (symbol in symbols) {
            val declaring = typeCaptureDeclaringClass(symbol) ?: return false
            if (enclosingClass === declaring) return false
            if (item.accessibility == "private") continue
            if (enclosingClass == null) continue
            when (typeCaptureDerivesFrom(enclosingClass, declaring, 0)) {
                true -> return false
                // Unknown: a base this could not resolve. Cannot prove, so does not hide.
                null -> return false
                false -> Unit
            }
        }
        return true
    }

    /**
     * (API.7) The class [symbol]'s declaration belongs to, or null.
     *
     * An ascent rather than a direct `parent` read, because a constructor PARAMETER
     * PROPERTY (`constructor(private x: number)`) declares a member from two levels
     * down.
     */
    private fun typeCaptureDeclaringClass(symbol: Symbol): Node? {
        val declaration = symbol.valueDeclaration ?: symbol.declarations.firstOrNull() ?: return null
        return typeCaptureEnclosingClass(declaration)
    }

    /**
     * (BUG.3) The class whose INSTANCE `this` denotes at [node], or null.
     *
     * ## Why this is not [typeCaptureEnclosingClass]
     *
     * That one answers "which class body is this caret lexically in", which is the
     * accessibility question. This one answers "what does `this` MEAN here", and the
     * two come apart at every non-arrow function: a `function` expression or
     * declaration REBINDS `this` (TypeScript types it `any`, and both tsc and this
     * compiler emit TS2683 for `this.p` there), while an ARROW does not rebind it at
     * all and simply inherits whatever encloses it.
     *
     * ## Why it is not [spineCaClassCtx] either
     *
     * That is the checker's own pull-based reconstruction of `currentClassForThis`
     * and has the right SHAPE — an innermost-boundary-first ascent — but it is
     * deliberately bug-compatible with a narrower legacy walker on one arm: a nested
     * `FunctionDeclaration` is transparent to it. Reusing it verbatim would answer
     * `function inner() { this.| }` inside a method with the enclosing class's
     * members, which is a confident wrong list. The RULE this reproduces is the one
     * the checker's own B101 installs state and enforce — a free function and a
     * function expression clear the class, an arrow preserves it, an object-literal
     * member clears it, and a class member sets it per static/instance.
     *
     * ## The bias: PROVE TO OFFER
     *
     * Every position this cannot place answers null, so `this` offers nothing rather
     * than guessing. That covers a static member (whose `this` is `typeof C`, which
     * `currentClassForThis` does not model), an object literal's method, a CLASS
     * EXPRESSION ([currentClassForThis] is typed `ClassDeclaration?`, so an anonymous
     * class is unrepresentable) and a caret in no class at all. The class-expression
     * arm is load-bearing rather than cosmetic: without it the ascent would walk
     * straight past `const K = class { m() { this.| } }` written inside a method of
     * `C` and answer with `C`'s members.
     *
     * INV.2(a) stamps `parent` on every node, so this is a pointer walk; the cap is
     * against a corrupt chain and nothing else.
     */
    private fun typeCaptureThisClass(node: Node): ClassDeclaration? {
        var current: Node = node
        var steps = 0
        while (steps++ < TYPE_CAPTURE_ENCLOSING_MAX_DEPTH) {
            val parent = (current as NodeBase).parent ?: return null
            when (parent) {
                // Transparent: an arrow binds no `this` of its own, anywhere in it.
                is ArrowFunction -> {}
                // Opaque, and the whole reason this is not [spineCaClassCtx].
                is FunctionExpression, is FunctionDeclaration -> return null
                is MethodDeclaration ->
                    return typeCaptureThisOwner(parent, ModifierFlag.Static in parent.modifiers)
                is GetAccessor ->
                    return typeCaptureThisOwner(parent, ModifierFlag.Static in parent.modifiers)
                is SetAccessor ->
                    return typeCaptureThisOwner(parent, ModifierFlag.Static in parent.modifiers)
                is PropertyDeclaration ->
                    return typeCaptureThisOwner(parent, ModifierFlag.Static in parent.modifiers)
                is Constructor -> return typeCaptureThisOwner(parent, isStatic = false)
                // A static initializer block's `this` is `typeof C`.
                is ClassStaticBlockDeclaration -> return null
                // Reached without passing a member — a heritage clause, a computed
                // member name, a decorator. Nothing above may claim the caret.
                is ClassDeclaration, is ClassExpression -> return null
                is SourceFile -> return null
                else -> {}
            }
            current = parent
        }
        return null
    }

    /** (BUG.3) The [ClassDeclaration] whose INSTANCE side [member] belongs to, or null
     *  — null for a static one, and null for an OBJECT LITERAL's member, whose parent
     *  is an [ObjectLiteralExpression] and whose `this` is that literal. */
    private fun typeCaptureThisOwner(member: Node, isStatic: Boolean): ClassDeclaration? {
        if (isStatic) return null
        return (member as NodeBase).parent as? ClassDeclaration
    }

    /**
     * (API.7) The innermost class declaration or expression enclosing [node], or null.
     *
     * INV.2(a) stamps `parent` at the end of every parse, so this is a pointer walk;
     * the cap is against a corrupt chain and nothing else.
     */
    private fun typeCaptureEnclosingClass(node: Node): Node? {
        var current: Node? = node
        var steps = 0
        while (current != null && steps++ < TYPE_CAPTURE_ENCLOSING_MAX_DEPTH) {
            if (current is ClassDeclaration || current is ClassExpression) return current
            current = (current as NodeBase).parent
        }
        return null
    }

    /**
     * (API.7) Whether [cls] derives from [target]: true, false, or NULL for "a base
     * could not be resolved, so this cannot say".
     *
     * The third state is the whole point — see [typeCaptureMemberInaccessible].
     */
    private fun typeCaptureDerivesFrom(cls: Node, target: Node, depth: Int): Boolean? {
        if (cls === target) return true
        if (depth > TYPE_CAPTURE_HERITAGE_MAX_DEPTH) return null
        val heritage = when (cls) {
            is ClassDeclaration -> cls.heritageClauses
            is ClassExpression -> cls.heritageClauses
            else -> null
        } ?: return false
        var unknown = false
        for (clause in heritage) {
            if (clause.token != SyntaxKind.ExtendsKeyword) continue
            for (type in clause.types) {
                val base = typeCaptureResolveClassDeclaration(type.expression)
                if (base == null) {
                    unknown = true
                    continue
                }
                when (typeCaptureDerivesFrom(base, target, depth + 1)) {
                    true -> return true
                    null -> unknown = true
                    false -> Unit
                }
            }
        }
        return if (unknown) null else false
    }

    /**
     * (API.7) The class [expression] names, through the spine's own scope chain and
     * the import hop [typeCaptureExportedMember] uses, or null when it names none.
     *
     * A BARE identifier only: a qualified base (`extends ns.Base`) would need its
     * middle segment resolved the same way, and answering null there is the
     * conservative direction — an unresolved base makes the whole verdict UNKNOWN,
     * which leaves the member offered.
     */
    private fun typeCaptureResolveClassDeclaration(expression: Node): Node? {
        val root = expression as? Identifier ?: return null
        val resolved =
            checker.spineScopeLookup(root.text) ?: checker.lookupPerFileForNode(root, root.text) ?: return null
        return typeCaptureFollowImportAlias(resolved)
            .declarations
            .firstOrNull { it is ClassDeclaration || it is ClassExpression }
    }

    /**
     * (API.4a) The modifiers on [symbol]'s declaration, or none.
     *
     * The four class-element kinds plus [Parameter], which is where a constructor
     * parameter property's `private readonly` lives. A kind not listed carries no
     * accessibility modifier in the grammar.
     */
    private fun typeCaptureMemberModifiers(symbol: Symbol): Set<ModifierFlag> =
        when (val declaration = symbol.valueDeclaration ?: symbol.declarations.firstOrNull()) {
            is PropertyDeclaration -> declaration.modifiers
            is MethodDeclaration -> declaration.modifiers
            is GetAccessor -> declaration.modifiers
            is SetAccessor -> declaration.modifiers
            is Parameter -> declaration.modifiers
            else -> emptySet()
        }

    /**
     * (API.3b) Records where the symbol [id] names is declared, FIRST WINS.
     *
     * Resolution is the spine's own lexical chain first ([spineScopeLookup]: the
     * INV.2(c) scope-space bindings, then the aliased container tables — a function
     * body's params and locals, then the file's, then the enclosing namespaces'),
     * and only then the node-keyed global lookup [getTypeOfIdentifier] falls through
     * to. That order is what makes a body local answer about ITSELF rather than
     * about a same-named global, and it is available ONLY during the walk:
     * `spineCurrentScope` is nulled per file when the spine leaves it, which is what
     * `DefinitionCaptureMeasurementTest` measures against a post-hoc query.
     *
     * A name that is NOT free ([typeCaptureIsFreeName]) is not looked up in any
     * scope — a scope lookup of the `p` in `o.p` finds any unrelated `p`, and an
     * editor that jumps somewhere confidently wrong is worse than one that does not
     * jump. (API.3d) it goes to [typeCaptureMemberSymbols] instead, which resolves
     * it through its RECEIVER; the positions that have no receiver stay silent.
     */
    private fun typeCaptureRecordDefinition(id: Node, fileName: String) {
        val span = TypeCaptureSpan(fileName, id.pos, id.end)
        if (span in typeCaptureDefinitions) return
        typeCaptureDefinitions[span] = typeCaptureDefinitionAt(id, fileName) ?: return
    }

    /**
     * (API.3b) The definition answer for [id], computed from whatever scope state is
     * in force RIGHT NOW.
     *
     * Separated from [typeCaptureRecordDefinition] so the post-hoc query the design
     * refutes can run the very same resolution with the walk's state gone — see
     * [postHocDefinitionAtSpanForTesting]. It reads state and writes none.
     */
    fun typeCaptureDefinitionAt(id: Node, fileName: String): CapturedDefinition? {
        // (API.9) A class or interface member's own DECLARATION name is neither of the
        // two, and its answer is a third shape: it declares itself, and it may be TIED
        // to a base's declaration by a heritage edge. Kept out of the symbol path below
        // because that path has no way to express "related but not a target".
        if (id is Identifier) typeCaptureMemberDeclarationAt(id, fileName)?.let { return it }
        // (API.10) An object-literal KEY is neither of the two either: its member comes
        // from the literal's CONTEXTUAL type, and its own property is a declaration in
        // its own right. Both, in the two fields whose roles differ. (API.17) A COMPUTED
        // key, `{ ["p"]: v }`, is the same question asked through one more node, so it
        // is the same leg rather than a second one — which is what makes the two agree
        // about the free-key branch, about `related` and about the contextual walk.
        typeCaptureObjectLiteralKeyAt(id, fileName)?.let { return it }
        val symbols =
            if (id is Identifier && typeCaptureIsFreeName(id)) {
                val resolved =
                    checker.spineScopeLookup(id.text) ?: checker.lookupPerFileForNode(id, id.text) ?: return null
                listOf(typeCaptureFollowImportAlias(resolved))
            } else {
                typeCaptureMemberSymbols(id)
            }
        if (symbols.isEmpty()) return null
        // A LinkedHashSet because more than one symbol may carry the same
        // declaration — `A | B` where both members are the same interface reached
        // through two references, or a base member inherited down two paths — while
        // the order the constituents were visited in is the answer's order.
        val locations = LinkedHashSet<CapturedDeclaration>()
        for (symbol in symbols) {
            for (declaration in symbol.declarations) {
                typeCaptureDeclarationLocation(declaration)?.let { locations.add(it) }
            }
        }
        if (locations.isEmpty()) return null
        // (API.9) …and what a DECLARED HERITAGE EDGE ties the resolved member to. This
        // is computed for every occurrence and not only for a declaration name, because
        // that is what tsc does and the difference is measurable: a `this.p` inside a
        // class implementing an interface is in the interface's group (measured, and it
        // is in the group two `extends` levels down as well), and it can only get there
        // by carrying the edge itself.
        val related = typeCaptureRootDeclarations(symbols, locations)
        // (API.10) …and the MEMBER a SHORTHAND's one token also names. Computed here
        // rather than in a leg of its own because a shorthand IS a free name — the
        // local is what the caret means — and this is the second thing the same token
        // says.
        val shorthand =
            if (id is Identifier) typeCaptureShorthandMember(id, locations) else emptyList()
        return CapturedDefinition(
            fileName, id.pos, id.end, symbols[0].name, locations.toList(), related, shorthand,
        )
    }

    /**
     * (API.10) The definition answer for an object-literal KEY, `{ p: v }`, or null
     * when [id] is not one.
     *
     * ## Two symbols, two fields, because tsc gives them different roles
     *
     * The key DECLARES the literal's own property and REFERS to the contextual type's
     * member, and tsc 7.0.2 answers with both in different ways: go-to-definition
     * names the contextual member alone, while find-references from the key answers
     * the UNION of the two symbols' groups (measured — twenty-one spans on a fixture
     * whose contextual group is twenty, the extra one being an `o.p` that reads the
     * literal's OWN property). So [CapturedDefinition.locations] carries the
     * contextual member and [CapturedDefinition.related] the own property, which is
     * exactly the split `related` already means: tied, but not a navigation target.
     *
     * With NO contextual type the key is only a declaration, and then the own property
     * IS the answer — which is also what tsc navigates to. That branch is not a
     * nicety: without it every object-literal key in the program resolves to nothing,
     * and an unresolved identifier spelling the name is what refuses a member rename.
     */
    private fun typeCaptureObjectLiteralKeyAt(
        id: Node,
        fileName: String,
    ): CapturedDefinition? {
        val (assignment, name) = typeCaptureObjectLiteralKey(id) ?: return null
        // The key's OWN declaration is the key NODE ITSELF, which for a computed one is
        // the literal rather than the whole `["p"]: v`: it has to be a span the
        // occurrence sweep also carries, or the rename's group holds a key with no node
        // behind it and refuses its own answer. For a bare `{ p: v }` and a `{ "p": v }`
        // this is what [typeCaptureDeclarationLocation] on the ASSIGNMENT already
        // answered — [typeCaptureDeclarationName] unwraps a [PropertyAssignment] to
        // exactly this node — so the two spellings stay one answer.
        val own = typeCaptureDeclarationLocation(id) ?: return null
        val literal = (assignment as NodeBase).parent as? ObjectLiteralExpression
        val members =
            if (literal == null) emptyList() else typeCaptureContextualMembers(literal, name)
        val locations = LinkedHashSet<CapturedDeclaration>()
        for (symbol in members) {
            for (declaration in symbol.declarations) {
                typeCaptureDeclarationLocation(declaration)?.let { locations.add(it) }
            }
        }
        locations.remove(own)
        if (locations.isEmpty()) {
            return CapturedDefinition(fileName, id.pos, id.end, name, listOf(own))
        }
        return CapturedDefinition(
            fileName, id.pos, id.end, name, locations.toList(), listOf(own),
        )
    }

    /**
     * (API.17) The object-literal key [node] is, as `(its PropertyAssignment, the
     * member name it spells)` — or null when [node] is not one, or spells no fixed
     * name.
     *
     * Three spellings, one answer: `{ p: v }`, `{ "p": v }` and, through a
     * [ComputedPropertyName], `{ ["p"]: v }` and ``{ [`p`]: v }``.
     *
     * THE ASYMMETRY IS LOAD-BEARING. A bare identifier is a key's name when it sits
     * directly under the assignment and is a VALUE when it sits under a computed name:
     * `{ [K]: v }` names the binding `K`, and the member it ends up spelling is decided
     * at run time. tsc 7.0.2 reads it exactly that way (measured — the const's own two
     * spans, and renaming it writes `[renamed]`), so the computed branch admits a
     * LITERAL only and everything else stays on the free-name path.
     */
    private fun typeCaptureObjectLiteralKey(node: Node): Pair<PropertyAssignment, String>? {
        val parent = (node as NodeBase).parent
        if (parent is PropertyAssignment) {
            if (parent.name !== node) return null
            return parent to (typeCaptureKeyName(node) ?: return null)
        }
        if (parent !is ComputedPropertyName || parent.expression !== node) return null
        val name = typeCaptureLiteralMemberName(node) ?: return null
        val assignment = (parent as NodeBase).parent as? PropertyAssignment ?: return null
        return if (assignment.name === parent) assignment to name else null
    }

    /** (API.17) The member name a DIRECT key [node] spells — its text, or the literal's. */
    private fun typeCaptureKeyName(node: Node): String? = when (node) {
        is Identifier -> node.text
        else -> typeCaptureLiteralMemberName(node)
    }

    /**
     * (API.17) The member name [assignment]'s key spells, or null when it spells none.
     *
     * The same three spellings as [typeCaptureObjectLiteralKey], asked from the
     * assignment rather than from the key — which is what the contextual walk needs
     * when it steps OUT of a nested literal and has to say which member of the outer
     * type received it.
     */
    private fun typeCaptureAssignmentKeyName(assignment: PropertyAssignment): String? =
        when (val name = assignment.name) {
            is ComputedPropertyName -> typeCaptureLiteralMemberName(name.expression)
            else -> typeCaptureKeyName(name)
        }

    /**
     * (API.10) The MEMBER declarations a SHORTHAND's single token also names, excluding
     * anything already in [own] — empty for everything that is not a shorthand.
     *
     * Two shapes, one rule. An object literal's `{ p }` names the CONTEXTUAL type's
     * `p`; a binding pattern's `const { p } = o` names the DESTRUCTURED type's `p`.
     * Both were measured on tsc 7.0.2 and behave identically in both directions: the
     * member's group contains the token, a caret on the token answers the local's
     * group alone, and a rename expands the token in whichever direction it was
     * reached from.
     *
     * Empty rather than a guess wherever the source type cannot be decided — a literal
     * with no contextual type, an un-annotated destructured parameter — which leaves
     * the occurrence exactly what it was before this existed: a plain local reference.
     */
    private fun typeCaptureShorthandMember(
        id: Identifier,
        own: Set<CapturedDeclaration>,
    ): List<CapturedDeclaration> {
        val parent = (id as NodeBase).parent ?: return emptyList()
        val source: Type = when {
            parent is ShorthandPropertyAssignment && parent.name === id -> {
                val literal = (parent as NodeBase).parent as? ObjectLiteralExpression
                    ?: return emptyList()
                typeCaptureContextualType(literal, 0) ?: return emptyList()
            }
            parent is BindingElement && parent.propertyName == null && parent.name === id ->
                typeCaptureDestructured(parent, 0) ?: return emptyList()
            else -> return emptyList()
        }
        val symbols = ArrayList<Symbol>(2)
        typeCaptureCollectMembers(source, id.text, symbols, 0)
        if (symbols.isEmpty()) return emptyList()
        val out = LinkedHashSet<CapturedDeclaration>()
        for (symbol in symbols) {
            for (declaration in symbol.declarations) {
                typeCaptureDeclarationLocation(declaration)?.let { if (it !in own) out.add(it) }
            }
        }
        return out.toList()
    }

    /**
     * (API.10) Every symbol the CONTEXTUAL type of [literal] calls [name].
     */
    private fun typeCaptureContextualMembers(
        literal: ObjectLiteralExpression,
        name: String,
    ): List<Symbol> {
        val contextual = typeCaptureContextualType(literal, 0) ?: return emptyList()
        val symbols = ArrayList<Symbol>(2)
        typeCaptureCollectMembers(contextual, name, symbols, 0)
        return symbols
    }

    /**
     * (API.10) The type that CONTEXTUALLY types [node], computed by walking OUT of it,
     * or null when nothing here supplies one.
     *
     * ## Why this is written here rather than read off the checker
     *
     * The checker's own `contextualType` is walk-scoped ambient: it is installed and
     * restored around the expression that needs it, so at an arbitrary captured node it
     * holds whatever the covering statement anchor left there — and in a ternary branch
     * it is null outright, where tsc answers. `cpaCtxAt` pull-derives it over the same
     * legacy edges and stops at every statement edge, so it cannot see an annotation.
     * This walk is SYNTACTIC and reads only type NODES and resolved signatures, so it
     * is a function of the position and of nothing the walk happens to be doing — the
     * same property that makes [typeCaptureDestructured] sound, of which this is the
     * exact dual (that one walks out of a binding pattern to find what is destructured;
     * this walks out of a literal to find what receives it).
     *
     * ## The positions, every one of them measured against tsc 7.0.2
     *
     * A variable declaration's, a parameter's and a class property's ANNOTATION; a
     * `return` statement (the enclosing function's return annotation); an arrow's
     * EXPRESSION body (the arrow's own contextual type's single call signature's
     * return); an `as` / `satisfies` / `<T>` assertion's type; an enclosing literal's
     * key; an array literal's element; a call's or a `new`'s ARGUMENT, through the
     * callee's signatures, instantiated by the call's EXPLICIT type arguments where it
     * has them. Parentheses and a ternary's branches pass through.
     *
     * A GENERIC call with INFERRED type arguments deliberately answers the naked type
     * parameter and therefore nothing — which is tsc's answer too: `takesGeneric({ p })`
     * assigned to `Shape` does NOT put the key in `Shape.p`'s group.
     *
     * Null rather than a guess everywhere else, so every unhandled position degrades to
     * the behaviour that existed before this did.
     */
    private fun typeCaptureContextualType(node: Expression, depth: Int): Type? {
        if (depth > TYPE_CAPTURE_MEMBER_MAX_DEPTH) return null
        val type = when (val parent = (node as NodeBase).parent) {
            is ParenthesizedExpression -> typeCaptureContextualType(parent, depth + 1)
            is ConditionalExpression ->
                if (parent.whenTrue === node || parent.whenFalse === node) {
                    typeCaptureContextualType(parent, depth + 1)
                } else {
                    null
                }
            is AsExpression ->
                if (parent.expression === node) checker.getTypeFromTypeNode(parent.type) else null
            is SatisfiesExpression ->
                if (parent.expression === node) checker.getTypeFromTypeNode(parent.type) else null
            is TypeAssertionExpression ->
                if (parent.expression === node) checker.getTypeFromTypeNode(parent.type) else null
            is VariableDeclaration ->
                if (parent.initializer === node) parent.type?.let { checker.getTypeFromTypeNode(it) } else null
            is Parameter ->
                if (parent.initializer === node) parent.type?.let { checker.getTypeFromTypeNode(it) } else null
            is PropertyDeclaration ->
                if (parent.initializer === node) parent.type?.let { checker.getTypeFromTypeNode(it) } else null
            is ReturnStatement ->
                if (parent.expression === node) typeCaptureReturnAnnotation(parent) else null
            is ArrowFunction ->
                if (parent.body === node) typeCaptureExpressionBodyContext(parent, depth) else null
            is PropertyAssignment -> {
                if (parent.initializer !== node) {
                    null
                } else {
                    val literal = (parent as NodeBase).parent as? ObjectLiteralExpression
                    // (API.17) …and a COMPUTED key names the outer member too, which is
                    // what makes a NESTED `{ ["n"]: { ["inner"]: v } }` resolve: without
                    // it the step out of the inner literal has no name to look up and
                    // the inner key resolves only to itself.
                    val key = typeCaptureAssignmentKeyName(parent)
                    if (literal == null || key == null) {
                        null
                    } else {
                        typeCaptureContextualMembers(literal, key)
                            .firstOrNull()?.let { checker.getTypeOfSymbol(it) }
                    }
                }
            }
            is ArrayLiteralExpression ->
                if (parent.elements.any { it === node }) {
                    (typeCaptureContextualType(parent, depth + 1) as? Type.Reference)
                        ?.takeIf { checker.isArrayLikeReference(it) }
                        ?.resolvedTypeArguments?.firstOrNull()
                } else {
                    null
                }
            is CallExpression ->
                typeCaptureArgumentContext(
                    parent.arguments.indexOfFirst { it === node },
                    typeCaptureCallSignaturesOf(parent.expression),
                    parent.typeArguments,
                )
            is NewExpression ->
                typeCaptureArgumentContext(
                    parent.arguments?.indexOfFirst { it === node } ?: -1,
                    typeCaptureConstructSignaturesOf(parent),
                    parent.typeArguments,
                )
            else -> null
        }
        return if (type === anyType || type === errorType) null else type
    }

    /** (API.10) The return-type annotation of the function-like [statement] returns from. */
    private fun typeCaptureReturnAnnotation(statement: ReturnStatement): Type? {
        var current: Node? = (statement as NodeBase).parent
        while (current != null) {
            val annotation = when (current) {
                is FunctionDeclaration -> current.type
                is FunctionExpression -> current.type
                is ArrowFunction -> current.type
                is MethodDeclaration -> current.type
                is GetAccessor -> current.type
                is Constructor -> return null
                else -> null
            }
            if (annotation != null) return checker.getTypeFromTypeNode(annotation)
            if (current is FunctionDeclaration || current is FunctionExpression ||
                current is ArrowFunction || current is MethodDeclaration ||
                current is GetAccessor || current is SetAccessor
            ) {
                return null
            }
            current = (current as NodeBase).parent
        }
        return null
    }

    /** (API.10) The contextual type of an arrow's EXPRESSION body — the arrow's own
     *  contextual type's single call signature's return type. */
    private fun typeCaptureExpressionBodyContext(arrow: ArrowFunction, depth: Int): Type? {
        arrow.type?.let { return checker.getTypeFromTypeNode(it) }
        val outer = typeCaptureContextualType(arrow, depth + 1) as? Type.Object ?: return null
        checker.resolveStructuredTypeMembers(outer)
        val signatures = outer.callSignatures ?: return null
        if (signatures.size != 1) return null
        return signatures[0].resolvedReturnType
    }

    /**
     * (API.10) The type of parameter [index] across [signatures], as one contextual
     * type — or null when there is no such parameter.
     *
     * Every signature contributes, because an overloaded callee has no ONE answer here
     * and the alternative is to pick arbitrarily: a union of the candidates is the same
     * coarseness a union RECEIVER already gets, and it is coarse in the direction that
     * offers more declarations rather than the wrong one.
     */
    private fun typeCaptureArgumentContext(
        index: Int,
        signatures: List<Signature>,
        typeArguments: List<TypeNode>?,
    ): Type? {
        if (index < 0 || signatures.isEmpty()) return null
        val types = ArrayList<Type>(2)
        for (signature in signatures) {
            val parameters = signature.typeParameters
            val instantiated =
                if (!typeArguments.isNullOrEmpty() && !parameters.isNullOrEmpty()) {
                    checker.instantiateSignature(
                        signature,
                        createTypeMapper(parameters, typeArguments.map { checker.getTypeFromTypeNode(it) }),
                    )
                } else {
                    signature
                }
            val parameter = instantiated.parameters.getOrNull(index) ?: continue
            val type = checker.getTypeOfSymbol(parameter)
            if (type === anyType || type === errorType) continue
            if (types.none { it === type }) types.add(type)
        }
        return when (types.size) {
            0 -> null
            1 -> types[0]
            else -> checker.getUnionType(types)
        }
    }

    /**
     * (API.9) The declarations a DECLARED HERITAGE EDGE ties [symbols] to, excluding
     * anything already in [own].
     *
     * ## Why this is per-OCCURRENCE and not a closure over the answers
     *
     * Measured against tsc 7.0.2 on `interface A { p }`, `interface B { p }`,
     * `class C implements A, B { p }`: a caret on `A`'s `p` answers seven references
     * including `C`'s `p` and every use of `C`, and does NOT include `b.p` or `B`'s `p`.
     * So the relation does not chain from one interface to the other through their
     * shared implementor — which rules out a transitive closure over the group, and
     * leaves exactly this: every occurrence carries its own symbol PLUS the bases that
     * symbol implements, and two occurrences are the same thing when those sets meet.
     * The same measurement is what says a `this.p` inside the implementor belongs to the
     * interface's group, which a declaration-name-only edge cannot express.
     */
    private fun typeCaptureRootDeclarations(
        symbols: List<Symbol>,
        own: Set<CapturedDeclaration>,
    ): List<CapturedDeclaration> {
        var related: LinkedHashSet<CapturedDeclaration>? = null
        for (symbol in symbols) {
            for (declaration in symbol.declarations) {
                val name = typeCaptureMemberName(declaration) ?: continue
                val heritage = typeCaptureOwnHeritage(declaration) ?: continue
                val out = related ?: LinkedHashSet<CapturedDeclaration>().also { related = it }
                typeCaptureCollectInherited(heritage, name, out, HashSet(), 0)
            }
        }
        val found = related ?: return emptyList()
        found.removeAll(own)
        return found.toList()
    }

    /**
     * (API.9) The single-token name of [declaration] when it is a CLASS or INTERFACE
     * member, or null — the test that decides whether a heritage edge can exist at all.
     */
    private fun typeCaptureMemberName(declaration: Node): String? = when (declaration) {
        is PropertyDeclaration -> (declaration.name as? Identifier)?.text
        is MethodDeclaration -> (declaration.name as? Identifier)?.text
        is GetAccessor -> (declaration.name as? Identifier)?.text
        is SetAccessor -> (declaration.name as? Identifier)?.text
        else -> null
    }

    /** (API.9) The heritage clauses of the class or interface [member] is declared in. */
    private fun typeCaptureOwnHeritage(member: Node): List<HeritageClause>? =
        when (val owner = (member as NodeBase).parent) {
            is ClassDeclaration -> owner.heritageClauses
            is ClassExpression -> owner.heritageClauses
            is InterfaceDeclaration -> owner.heritageClauses
            else -> null
        }

    /**
     * (API.9) The answer for a MEMBER'S OWN DECLARATION NAME that a heritage clause
     * ties to a base's declaration — or null when [id] is not one, or when nothing it
     * inherits from declares the spelling.
     *
     * ## Why this is a third shape and not more [CapturedDefinition.locations]
     *
     * Both halves were READ OUT of tsc 7.0.2's own language server rather than
     * reasoned about, and they disagree. `textDocument/definition` on the `p` of
     * `class Impl implements Shape` answers **that member**, not `Shape`'s;
     * `textDocument/references` on it answers **`Shape`'s whole group**, thirteen
     * spans across two files. So the base declaration is an identity edge and not a
     * navigation target, which is exactly what [CapturedDefinition.related] is.
     *
     * ## Why the edge is DECLARED heritage and nothing weaker
     *
     * Also measured: a class with the same members and NO `implements` answers two
     * references — its own declaration and its own `this.p` — so structural
     * compatibility does not relate. That is the discriminator this leg is pinned
     * against, and it is also what makes the leg cheap: it asks the heritage clauses,
     * never the assignability engine.
     *
     * The closure is TRANSITIVE because tsc's is: an `override p` in a class extending
     * an implementor is in the interface's group, and it reaches it two edges away. The
     * walk therefore collects the member on every base type reachable through
     * `extends`/`implements`, bounded by [TYPE_CAPTURE_MEMBER_MAX_DEPTH] and by a
     * visited set on the heritage NODES — a cyclic `extends` is a program the checker
     * reports separately and must not hang this.
     *
     * Nothing is recorded when no base declares the name, which keeps this leg's blast
     * radius to exactly the members that inherit: a class member with no heritage
     * answers what it answered before, which is nothing.
     */
    private fun typeCaptureMemberDeclarationAt(
        id: Identifier,
        fileName: String,
    ): CapturedDefinition? {
        val member = (id as NodeBase).parent ?: return null
        if (typeCaptureMemberNameIdentifier(member) !== id) return null
        val owner = (member as NodeBase).parent ?: return null
        // (API.11) An OBJECT LITERAL's own member is left to (API.10)'s key leg and to
        // what preceded it. A contextually typed literal's member is an occurrence of
        // the CONTEXTUAL type's member, and resolving it to itself would take it out of
        // the rename completeness net WITHOUT putting it in the group — a silent loss
        // where the present refusal is loud.
        if (owner is ObjectLiteralExpression) return null
        val own = typeCaptureDeclarationLocation(member) ?: return null
        // (API.11) EVERY declaration of the member, not the one under the caret. That
        // is the whole point of this leg: a MERGED interface, an OVERLOAD set and an
        // ACCESSOR PAIR are each ONE member with several declaration names, and tsc
        // puts all of them in one group (measured on tsc 7.0.2: `both` declared in two
        // `interface Merged` blocks answers three references and two definitions from
        // EITHER of them). It is also what makes a member declared and NEVER used
        // answer at all.
        val locations = LinkedHashSet<CapturedDeclaration>()
        for (declaration in typeCaptureMemberDeclarations(owner, id.text)) {
            typeCaptureDeclarationLocation(declaration)?.let { locations.add(it) }
        }
        // The declaration under the caret is one of them whatever the owner route
        // managed, so this is a fallback and an invariant at once.
        locations.add(own)
        val related = LinkedHashSet<CapturedDeclaration>()
        typeCaptureOwnHeritage(member)?.let {
            typeCaptureCollectInherited(it, id.text, related, HashSet(), 0)
        }
        related.removeAll(locations)
        return CapturedDefinition(
            fileName, id.pos, id.end, id.text, locations.toList(), related.toList(),
        )
    }

    /**
     * (API.11) The single-token NAME of a member DECLARATION, or null when [member] is
     * not one — the closed set of kinds this leg answers for.
     *
     * Interface and type-literal members ride the class-element arms, because that is
     * what the parser produces for them ([typeCaptureIsFreeName] says the same). A
     * computed name is not an [Identifier] and therefore answers null, which is the
     * same silence every other computed member position gets here.
     */
    private fun typeCaptureMemberNameIdentifier(member: Node): Identifier? =
        when (member) {
            is PropertyDeclaration -> member.name as? Identifier
            is MethodDeclaration -> member.name as? Identifier
            is GetAccessor -> member.name as? Identifier
            is SetAccessor -> member.name as? Identifier
            // (API.11) An enum's member is a declaration name with an owner too, and
            // tsc answers it exactly as it answers an interface's.
            is EnumMember -> member.name as? Identifier
            else -> null
        }

    /**
     * (API.11) Every declaration of the member the OWNER of a member declaration calls
     * [name] — the FOURTH resolution mechanism, and the exact dual of a member USE's
     * receiver.
     *
     * ## Why the owner, and why not the member SYMBOL
     *
     * A member USE resolves through its RECEIVER, an object-literal key through its
     * CONTEXTUAL type, a free name through the SCOPE CHAIN. A member's own DECLARATION
     * name has none of the three; what it does have is the class, interface, type
     * literal or enum it is declared IN, so that is what is asked.
     *
     * The obvious implementation — resolve the name to its `Symbol` and read
     * `Symbol.declarations` — is NOT enough here, and the measurement is worth keeping:
     * this compiler's interface merge is LAST-WINS for a same-named member (round 884's
     * `mergeSingleSymbol` adopts), so the merged `Merged.both` symbol carries only the
     * SECOND block's declaration and a caret on the FIRST would answer about a span it
     * is not. tsc accumulates. So the declaration list is reconstructed one level up,
     * from the OWNER symbol's own declarations — each of which is a container that may
     * hold a member of this name — which is exactly where the information survives.
     *
     * ## The soundness condition
     *
     * A container is trusted only when the owner we are standing in is one of the
     * resolved symbol's own declarations. Without that, a name that resolves to some
     * OTHER same-named declaration (a shadowed interface, an import of the same
     * spelling) would contribute members of a type the caret has nothing to do with —
     * the same failure a scope lookup of a member name produces, one level up. The
     * owner itself is always a container, so an anonymous class expression and a type
     * literal — neither of which has a name to resolve — answer from themselves.
     */
    private fun typeCaptureMemberDeclarations(owner: Node, name: String): List<Node> {
        val containers = LinkedHashSet<Node>()
        val ownerSymbol = typeCaptureOwnerSymbol(owner)
        if (ownerSymbol != null && ownerSymbol.declarations.any { it === owner }) {
            containers.addAll(ownerSymbol.declarations)
        }
        containers.add(owner)
        val out = ArrayList<Node>(2)
        for (container in containers) {
            val members: List<Node> = when (container) {
                is InterfaceDeclaration -> container.members
                is ClassDeclaration -> container.members
                is ClassExpression -> container.members
                is TypeLiteral -> container.members
                is EnumDeclaration -> container.members
                else -> continue
            }
            for (candidate in members) {
                if (typeCaptureMemberNameIdentifier(candidate)?.text == name) out.add(candidate)
            }
        }
        return out
    }

    /**
     * (API.11) The symbol a member declaration's OWNER declares, or null.
     *
     * Resolved exactly as [typeCaptureHeritageDeclaration] resolves a base's name — the
     * spine's own scope chain first, then the node-keyed per-file lookup — which is
     * what makes a merged `interface` answer with the MERGED symbol, i.e. with every
     * block that contributes to it. Null for an owner with no name (a type literal, an
     * anonymous class expression), which is not a gap: such an owner is its own only
     * container.
     */
    private fun typeCaptureOwnerSymbol(owner: Node): Symbol? {
        val ownerName = when (owner) {
            is InterfaceDeclaration -> owner.name
            is ClassDeclaration -> owner.name
            is ClassExpression -> owner.name
            is EnumDeclaration -> owner.name
            else -> null
        } ?: return null
        val resolved = checker.spineScopeLookup(ownerName.text)
            ?: checker.lookupPerFileForNode(ownerName, ownerName.text)
            ?: return null
        return typeCaptureFollowImportAlias(resolved)
    }

    /**
     * (API.9) Every declaration of [name] reachable from [clauses] through declared
     * heritage, transitively, into [out].
     *
     * [seen] is keyed on the heritage ENTRY node rather than on a type, because the
     * type is what this is trying to obtain and a base that does not resolve must still
     * terminate the walk.
     */
    private fun typeCaptureCollectInherited(
        clauses: List<HeritageClause>,
        name: String,
        out: MutableSet<CapturedDeclaration>,
        seen: MutableSet<ExpressionWithTypeArguments>,
        depth: Int,
    ) {
        if (depth > TYPE_CAPTURE_MEMBER_MAX_DEPTH) return
        for (clause in clauses) {
            for (entry in clause.types) {
                if (!seen.add(entry)) continue
                val symbols = ArrayList<Symbol>(2)
                typeCaptureCollectMembers(checker.getTypeFromBaseTypeExpression(entry), name, symbols, 0)
                for (symbol in symbols) {
                    for (declaration in symbol.declarations) {
                        typeCaptureDeclarationLocation(declaration)?.let { out.add(it) }
                    }
                }
                // ...and the base's own bases. `resolveStructuredTypeMembers` already
                // copies an inherited member DOWN, so the type walk above usually
                // answers for the whole chain; this covers the case it cannot — a base
                // that OVERRIDES the member hides the grandparent's declaration behind
                // its own, and tsc puts both in the group.
                val declaration = typeCaptureHeritageDeclaration(entry)
                val above = when (declaration) {
                    is ClassDeclaration -> declaration.heritageClauses
                    is ClassExpression -> declaration.heritageClauses
                    is InterfaceDeclaration -> declaration.heritageClauses
                    else -> null
                } ?: continue
                typeCaptureCollectInherited(above, name, out, seen, depth + 1)
            }
        }
    }

    /** (API.9) The class or interface declaration a heritage entry names, or null. */
    private fun typeCaptureHeritageDeclaration(entry: ExpressionWithTypeArguments): Node? {
        val root = entry.expression as? Identifier ?: return null
        val symbol = checker.spineScopeLookup(root.text)
            ?: checker.lookupPerFileForNode(root, root.text)
            ?: return null
        val resolved = typeCaptureFollowImportAlias(symbol)
        for (declaration in resolved.declarations) {
            if (declaration is ClassDeclaration ||
                declaration is ClassExpression ||
                declaration is InterfaceDeclaration
            ) {
                return declaration
            }
        }
        return null
    }

    /**
     * (API.9) True when [node] is the LITERAL that names the member of an element
     * access — the `"p"` of `o["p"]`, and nothing else that is spelled the same way
     * anywhere in the program.
     *
     * (API.16) A no-substitution TEMPLATE (``o[`p`]``) names a member exactly as a
     * string does, and tsc 7.0.2 puts it in the member's reference set (measured).
     * The two are one population here, which is [typeCaptureLiteralMemberName]'s
     * whole job.
     *
     * (API.17) …and a COMPUTED NAME spells one the same way — `{ ["p"]: v }`, and a
     * class's or an interface's `["p"]`. The literal is admitted for every computed
     * name and not only an object literal's, because the population is what makes a
     * miss VISIBLE: the object-literal key resolves below, and a computed member
     * declaration does not and is then an occurrence a rename must report it cannot
     * place rather than one it never saw. `{ [K]: v }` is not here — it spells no
     * fixed name, and tsc reads that position as a reference to `K` alone (measured).
     */
    private fun typeCaptureIsMemberNameLiteral(node: Node): Boolean {
        if (typeCaptureLiteralMemberName(node) == null) return false
        return when (val parent = (node as NodeBase).parent) {
            is ElementAccessExpression -> parent.argumentExpression === node
            is ComputedPropertyName -> parent.expression === node
            is PropertyAssignment -> parent.name === node
            else -> false
        }
    }

    /**
     * (API.16) The member name a LITERAL element-access argument spells, or null when
     * it spells none.
     *
     * The closed set is a string literal and a NO-SUBSTITUTION template, and the
     * exclusion is the point: a `TemplateExpression` — one WITH substitutions,
     * ``o[`p${x}`]`` — is a different node class with no fixed text, so it cannot be
     * accepted by accident. tsc refuses that position outright (measured: zero
     * references, and `prepareRename` answers `You cannot rename this element`), which
     * is the same answer null produces here.
     */
    private fun typeCaptureLiteralMemberName(node: Node): String? = when (node) {
        is StringLiteralNode -> node.text
        is NoSubstitutionTemplateLiteralNode -> node.text
        else -> null
    }

    /**
     * (API.3d) The property symbol(s) a MEMBER name resolves to, or empty.
     *
     * This is the mechanism (API.3b) deliberately did not build, and the reason it
     * had to be a second one is that a member name is not resolved by any scope: it
     * is resolved by a RECEIVER. So the question asked here is the compiler's own —
     * "what is the receiver's type, and what does that type call [id]'s spelling" —
     * and the answer is a symbol whose declarations are the navigation target.
     *
     * ## Two receivers, two mechanisms, in this order
     *
     * A namespace, a module alias and an ENUM answer from their own EXPORT table and
     * never from a type: an enum's own type is a member-LESS `Type.Object`
     * (CLAUDE.md) and a namespace identifier types as `any`, so the type path below
     * would find nothing for either. That leg is a symbol-table walk with no type
     * resolution in it, so it is also the only leg a TYPE position (`N.T`) can use —
     * which is why it runs first and why a [QualifiedName] is accepted at all.
     *
     * Everything else goes through the type: [getTypeOfExpression] on the receiver,
     * then [typeCaptureCollectMembers]. That is deliberately the compiler's own
     * member resolution rather than a re-derivation — `resolveStructuredTypeMembers`
     * is what makes an INHERITED member answer with the BASE's symbol (it copies the
     * base's own [Symbol] object into the derived table) and what makes a generic
     * instantiation answer with the declaration rather than the substituted type (it
     * copies `declarations` onto the instantiated symbol). A hand-rolled walk of
     * `type.members` would get both wrong, and a member table is LAZY (round 833), so
     * anything reading one without resolving it first answers differently depending
     * on whether an earlier line in the file happened to resolve that type.
     *
     * ## What is REFUSED here, and why that is better than a guess
     *
     * An element access (`o["p"]`) never arrives: its argument is a string literal,
     * not an [Identifier], and only identifiers are offered a definition. A
     * PropertyAssignment name (`{ p: v }`) and a member DECLARATION name (the `p` of
     * `interface I { p: string }`) are refused by [typeCaptureIsFreeName] and stay
     * refused: the first would need the object literal's CONTEXTUAL type, which is
     * not a function of the receiver and is not in hand at an arbitrary node, and the
     * second already IS the declaration. A shorthand `{ p }` is not a member position
     * at all — it is a reference to the local `p`, which the free-name path answers.
     */
    private fun typeCaptureMemberSymbols(id: Node): List<Symbol> {
        // (API.9) The two member positions whose receiver is not the node to the left
        // of a dot. Both were measured against tsc 7.0.2 to be ordinary members of the
        // group — a `const { p: local } = o` and an `o["p"]` are two of the three spans
        // round 925 measured this API to be short by.
        typeCaptureIndirectMemberSymbols(id)?.let { return it }
        if (id !is Identifier) return emptyList()
        val receiver: Node = when (val parent = (id as NodeBase).parent) {
            is PropertyAccessExpression -> if (parent.name === id) parent.expression else return emptyList()
            is QualifiedName -> if (parent.right === id) parent.left else return emptyList()
            else -> return emptyList()
        }
        val name = id.text
        val symbols = ArrayList<Symbol>(2)
        // `this` is `Identifier("this")` in this parser — there is no ThisExpression
        // node — so it reaches [getTypeOfExpression] as a name nothing binds and
        // types as `any`, which finds no members. Its real type is the enclosing
        // class's, and the ambient this hook installs is where that lives:
        // `currentClassForThis` is set from the cta frame and is deliberately NULL
        // inside a STATIC member (the frame's own rule), so a static `this` answers
        // nothing rather than answering with instance members.
        if (receiver is Identifier && (receiver.text == "this" || receiver.text == "super")) {
            val cls = checker.currentClassForThis ?: return emptyList()
            val thisType = checker.resolveUncalledThisType(cls) ?: return emptyList()
            if (receiver.text != "super") {
                typeCaptureCollectMembers(thisType, name, symbols, 0)
                return symbols
            }
            // (API.13) `super` reads the BASE declarations rather than the this-type's,
            // exactly as [typeCaptureThisMemberType] does for the TYPE of the same
            // access: the two answer the same symbol for an inherited member (a base's
            // own [Symbol] is copied into the derived table) and differ precisely where
            // the derived class OVERRIDES it, which is the case `super` is written for.
            // Measured against tsc 7.0.2: `super.pb` navigates to `Base.pb` in both the
            // overridden and the inherited shape.
            val declared = thisType as? Type.Interface ?: return emptyList()
            checker.resolveStructuredTypeMembers(declared)
            val bases = declared.baseTypes ?: return emptyList()
            for (base in bases) {
                typeCaptureCollectMembers(base, name, symbols, 0)
                if (symbols.isNotEmpty()) return symbols
            }
            return symbols
        }
        typeCaptureExportedMember(receiver, name)?.let { return listOf(it) }
        val receiverExpression = receiver as? Expression ?: return emptyList()
        // (INV.2) the recorded receiver type where the store has it — see
        // [nodeAnswerTypeOrCompute]; identical to the old computation otherwise.
        typeCaptureCollectMembers(nodeAnswerTypeOrCompute(receiverExpression), name, symbols, 0)
        return symbols
    }

    /**
     * (API.9) The property symbol(s) for the two member positions that name their
     * member with something other than an identifier after a dot — or null when [node]
     * is neither, which is the "ask the ordinary path" answer.
     *
     * ## An ELEMENT ACCESS, `o["p"]`
     *
     * The receiver is the access's own expression and the member name is the literal's
     * text, so the member lookup is the same one `o.p` performs. Only a literal that
     * spells a fixed name — a string or, since (API.16), a no-substitution TEMPLATE: a
     * computed `o[i]` names no member statically and a template WITH substitutions has
     * no fixed text to name one with. These are the only query spans in the whole API
     * that are not an [Identifier], which is why [typeCaptureDefinitionAt] takes a
     * [Node].
     *
     * ## A BINDING ELEMENT's `propertyName`, `const { p: local } = o`
     *
     * `p` names a member of the type being destructured and `local` names the binding
     * this file introduces — [typeCaptureIsFreeName] has always known that and answered
     * nothing for the `p`. What it needs is a receiver, and the pattern's source is not
     * an expression to the left of a dot: it is the annotation or initializer of
     * whatever the pattern belongs to, one or more levels up. [typeCaptureDestructured]
     * computes it.
     *
     * A SHORTHAND (`const { p } = o`, `propertyName == null`) is deliberately NOT here.
     * Its single token is BOTH the member and the local — tsc holds two symbols for it
     * and expands the rename in whichever direction the caret asks — while a capture
     * records ONE answer per span, so admitting it would make the local's group and the
     * member's group share a span and merge whenever a caret landed on it. It stays a
     * refusal, and rename's completeness net still names it.
     */
    private fun typeCaptureIndirectMemberSymbols(node: Node): List<Symbol>? {
        val parent = (node as NodeBase).parent ?: return null
        if (parent is ElementAccessExpression) {
            if (parent.argumentExpression !== node) return null
            val name = typeCaptureLiteralMemberName(node) ?: return null
            val symbols = ArrayList<Symbol>(2)
            typeCaptureCollectMembers(
                checker.getTypeOfExpression(parent.expression), name, symbols, 0,
            )
            return symbols
        }
        if (parent is BindingElement && parent.propertyName === node) {
            val name = (node as? Identifier)?.text ?: return null
            val source = typeCaptureDestructured(parent, 0) ?: return emptyList()
            val symbols = ArrayList<Symbol>(2)
            typeCaptureCollectMembers(source, name, symbols, 0)
            return symbols
        }
        return null
    }

    /**
     * (API.9) The type the binding pattern containing [element] destructures, or null
     * when it cannot be decided here.
     *
     * Three owners, walked outwards: a [VariableDeclaration] and a [Parameter] each
     * carry the source as an ANNOTATION or (for the declaration) an INITIALIZER, and a
     * nested [BindingElement] carries it as its own member of the level above. The
     * annotation is preferred over the initializer for the reason every annotation is
     * preferred: it is what the program says, where the initializer is what the
     * checker infers.
     *
     * Null rather than a guess whenever the chain runs out — an un-annotated parameter
     * whose type is contextual, an array pattern, a `for (const { p } of xs)` head. A
     * null makes the position resolve to nothing, which is what it did before this
     * existed, so every unhandled owner degrades to the previous behaviour rather than
     * to a wrong one.
     */
    fun typeCaptureDestructured(element: BindingElement, depth: Int): Type? {
        if (depth > TYPE_CAPTURE_MEMBER_MAX_DEPTH) return null
        val pattern = (element as NodeBase).parent as? ObjectBindingPattern ?: return null
        return when (val owner = (pattern as NodeBase).parent) {
            is VariableDeclaration ->
                owner.type?.let { checker.getTypeFromTypeNode(it) }
                    ?: owner.initializer?.let { checker.getTypeOfExpression(it) }
            is Parameter -> owner.type?.let { checker.getTypeFromTypeNode(it) }
            is BindingElement -> {
                val above = typeCaptureDestructured(owner, depth + 1) ?: return null
                val name = (owner.propertyName ?: owner.name) as? Identifier ?: return null
                val symbols = ArrayList<Symbol>(2)
                typeCaptureCollectMembers(above, name.text, symbols, 0)
                symbols.firstOrNull()?.let { checker.getTypeOfSymbol(it) }
            }
            else -> null
        }
    }

    /**
     * (API.3d) [name] as an EXPORT of whatever [receiver] names, or null.
     *
     * One table covers three shapes because the binder puts all three in it: a
     * namespace's and a module's exports, and an ENUM's members (`Binder`'s
     * `bindEnumDeclaration` declares each member into `symbol.exports`). The import
     * hop is the same one the free-name path takes, so `import * as N from "./m"`
     * followed by `N.x` answers about the declaration in `./m` rather than about the
     * import.
     *
     * Only a bare [Identifier] receiver is followed. A longer chain (`A.B.x`) would
     * need the middle segment resolved the same way, which is real work for a case
     * an editor can already reach one caret to the left; it answers nothing rather
     * than guessing.
     */
    private fun typeCaptureExportedMember(receiver: Node, name: String): Symbol? {
        val root = receiver as? Identifier ?: return null
        val resolved =
            checker.spineScopeLookup(root.text) ?: checker.lookupPerFileForNode(root, root.text) ?: return null
        return typeCaptureFollowImportAlias(resolved).exports?.get(name)
    }

    /**
     * (API.3d) Collects every symbol [type] calls [name], across unions,
     * intersections and apparent types, into [out].
     *
     * A UNION or an INTERSECTION legitimately answers with MORE THAN ONE
     * declaration and both are returned, in constituent order — which is why this
     * collects rather than returning the first hit the way `getPropertyOfType` does.
     * A union's rule there ("the property exists only if every constituent has it")
     * is an assignability question and is the wrong one here: a user who asks where
     * `p` is declared on `A | B` wants both places, and a `p` present on only one
     * constituent is still a real declaration to navigate to.
     *
     * A primitive receiver (`"s".length`) and a bare type parameter (`t.p` where
     * `T extends I`) reach their members through [getApparentType] — the wrapper
     * interface and the constraint respectively — so the answer for a lib member is
     * the lib's own declaration, in the lib file the program was built with.
     *
     * [depth] bounds the constituent recursion; nothing here can cycle (the member
     * tables' own cycle guard is in `resolveStructuredTypeMembersCore`) but a
     * pathological nest is not worth walking.
     */
    fun typeCaptureCollectMembers(
        type: Type,
        name: String,
        out: MutableList<Symbol>,
        depth: Int,
    ) {
        if (depth > TYPE_CAPTURE_MEMBER_MAX_DEPTH) return
        when (type) {
            is Type.Union -> for (c in type.types) typeCaptureCollectMembers(c, name, out, depth + 1)
            is Type.Intersection ->
                for (c in type.types) typeCaptureCollectMembers(c, name, out, depth + 1)
            is Type.Object -> {
                checker.resolveStructuredTypeMembers(type)
                val member = type.members?.get(name)
                if (member != null) {
                    out.add(member)
                    return
                }
                // The static side is dual-populated into `members` today, so this is
                // reached only where that mirroring did not happen; it can name no
                // file the instance side would not have named.
                (type as? Type.Interface)?.staticMembers?.get(name)?.let { out.add(it) }
            }
            else -> {
                val apparent = checker.getApparentType(type)
                if (apparent !== type) typeCaptureCollectMembers(apparent, name, out, depth + 1)
            }
        }
    }

    /**
     * (API.3b) True when [id] is a name resolved through the SCOPE CHAIN, rather
     * than a member name resolved through some receiver.
     *
     * A reject-list rather than an accept-list, because the referencing positions
     * are open-ended (every expression, type and heritage position) while the
     * member positions are a closed set of parent kinds — and the failure direction
     * of a missed reject is a wrong answer, where a missed accept is only a missing
     * one. Interface members ride the class-member arms: `InterfaceDeclaration`
     * holds `ClassElement`s.
     *
     * A LABEL is rejected for the same reason as a member name: it is not a symbol
     * at all, so a scope lookup of it finds whatever variable shares the spelling.
     *
     * (API.3d) FALSE STILL MEANS "not the scope chain", NOT "no answer": the two
     * receiver-bearing rejects ([PropertyAccessExpression] and [QualifiedName]) are
     * now answered by [typeCaptureMemberSymbols]. Everything else this rejects is
     * answered by nothing, which is why widening it is still the dangerous
     * direction — a position wrongly admitted here gets a scope lookup, and a scope
     * lookup of a member name is exactly the wrong answer this predicate exists to
     * prevent.
     */
    private fun typeCaptureIsFreeName(id: Identifier): Boolean =
        when (val parent = (id as NodeBase).parent) {
            null -> false
            is PropertyAccessExpression -> parent.name !== id
            is QualifiedName -> parent.right !== id
            is PropertyAssignment -> parent.name !== id
            is EnumMember -> parent.name !== id
            is PropertyDeclaration -> parent.name !== id
            is MethodDeclaration -> parent.name !== id
            is GetAccessor -> parent.name !== id
            is SetAccessor -> parent.name !== id
            is LabeledStatement -> parent.label !== id
            is BreakStatement -> parent.label !== id
            is ContinueStatement -> parent.label !== id
            // `{ p: local }` in a binding pattern: `p` is the SOURCE object's member,
            // `local` is the binding this file introduces.
            is BindingElement -> parent.propertyName !== id
            // `import { p as local }`: `p` names an export of the OTHER module, which
            // no scope here binds. `export { local as p }` is the mirror — there the
            // reference is `propertyName` and `name` is the exported spelling.
            is ImportSpecifier -> parent.propertyName !== id
            is ExportSpecifier -> parent.propertyName == null || parent.propertyName !== id
            else -> true
        }

    /**
     * (API.3b) The symbol an import alias stands for, or [symbol] itself.
     *
     * Go-to-definition on an imported name means the DECLARATION, not the import
     * statement that brought it in — that is what a user is asking for, and the
     * import line is one keystroke away anyway. The hop is attempted only for a
     * symbol whose declarations are ALL import bindings, which is the same test
     * [computeImportedSymbolGeneral] applies one level down, and it degrades to the
     * alias itself when the module does not resolve: an import statement is a
     * truthful location, just a less useful one.
     */
    fun typeCaptureFollowImportAlias(symbol: Symbol): Symbol {
        val declarations = symbol.declarations
        if (declarations.isEmpty()) return symbol
        for (declaration in declarations) if (!checker.isImportBindingDecl(declaration)) return symbol
        return checker.resolveImportedSymbolGeneral(symbol) ?: symbol
    }

    /**
     * (API.3b) [declaration] as a navigable span, or null when it cannot be placed.
     *
     * The span is the declaration's NAME where it has a single-token one — tsc's
     * go-to-definition navigates to the name, and an editor highlighting `foo`
     * rather than a whole class body is the point — and the declaration node itself
     * otherwise (a binding pattern, a computed member name, a `declare module "x"`).
     *
     * Null when the node carries no position (a Transformer-synthesized node, which
     * no binder symbol should hold but which costs one comparison to refuse) or when
     * its parent chain reaches no [SourceFile] — INV.2(a) stamps `parent` at the end
     * of every parse, so that only happens for a hand-constructed tree.
     */
    private fun typeCaptureDeclarationLocation(declaration: Node): CapturedDeclaration? {
        if (declaration.pos < 0) return null
        val sourceFile = typeCaptureEnclosingFile(declaration) ?: return null
        val target = typeCaptureDeclarationName(declaration) ?: declaration
        val end = typeCaptureRealEnd(sourceFile.text, target.pos, target.end)
        return CapturedDeclaration(
            fileName = sourceFile.fileName,
            start = target.pos,
            length = end - target.pos,
            kind = target.kind.name,
        )
    }

    /**
     * (API.3b) The single-token name node of [declaration], or null.
     *
     * Only a single-token name is returned, so a binding pattern (`const { a } = o`
     * as the VariableDeclaration behind a whole-pattern symbol) falls back to the
     * declaration. The kinds listed are the ones the binder and the INV.2(c) lexical
     * pass actually attach to a [Symbol]; a kind not listed is not wrong, only coarser.
     *
     * (API.17) A COMPUTED name unwraps to the literal INSIDE it — `["p"]` answers the
     * `"p"` — and that is not cosmetic. The answer is a rename's SEED, and the group it
     * seeds is looked up by `(file, start)` against the swept occurrence nodes: with the
     * whole `["p"]: v` returned, the seed named an offset (the `[`) that no occurrence
     * begins at, and every such rename refused itself with `no identifier node at this
     * occurrence`. Coarser is not free here.
     */
    private fun typeCaptureDeclarationName(declaration: Node): Node? {
        val name: Node? = when (declaration) {
            is VariableDeclaration -> declaration.name
            is Parameter -> declaration.name
            is BindingElement -> declaration.name
            is FunctionDeclaration -> declaration.name
            is FunctionExpression -> declaration.name
            is ClassDeclaration -> declaration.name
            is ClassExpression -> declaration.name
            is InterfaceDeclaration -> declaration.name
            is TypeAliasDeclaration -> declaration.name
            is EnumDeclaration -> declaration.name
            is EnumMember -> declaration.name
            // (API.3d) An object literal's own member: reached only now that a
            // member name resolves, and without it `o.p` navigates to the whole
            // `p: 1` rather than to `p`.
            is PropertyAssignment -> declaration.name
            is ShorthandPropertyAssignment -> declaration.name
            is ModuleDeclaration -> declaration.name
            is TypeParameter -> declaration.name
            is PropertyDeclaration -> declaration.name
            is MethodDeclaration -> declaration.name
            is GetAccessor -> declaration.name
            is SetAccessor -> declaration.name
            is ImportSpecifier -> declaration.name
            is ExportSpecifier -> declaration.name
            is NamespaceImport -> declaration.name
            is ImportEqualsDeclaration -> declaration.name
            // A default import's binder declaration is the whole statement.
            is ImportDeclaration -> declaration.importClause?.name
            else -> null
        }
        if (name is ComputedPropertyName) {
            val inner = name.expression
            return if (typeCaptureLiteralMemberName(inner) != null) inner else null
        }
        return when (name) {
            is Identifier, is StringLiteralNode, is NumericLiteralNode -> name
            else -> null
        }
    }

    /** (API.3b) The [SourceFile] [node] was parsed from, by INV.2(a)'s parent chain. */
    private fun typeCaptureEnclosingFile(node: Node): SourceFile? {
        var current: Node? = node
        while (current != null) {
            if (current is SourceFile) return current
            current = (current as NodeBase).parent
        }
        return null
    }

    /**
     * (API.3b) The offset one past the last character of the span `[pos, rawEnd)` —
     * the number [Node.end] is commonly mistaken for.
     *
     * `Node.end` is the end of the token FOLLOWING the node (round 910:
     * `Parser.getEnd()` is the scanner position read after the one-token lookahead),
     * so the real end is the greatest TOKEN end strictly below it. The `-project`
     * module answers the same question with a whole-file token index because it
     * asks it about arbitrary carets; here the start is already exact and the span
     * is one declaration, so scanning FORWARD from [pos] answers it in a token or
     * two and needs no index and no cache.
     *
     * Degrades the way `SourceIndex` degrades: a context-free re-scan can only SPLIT
     * a contextual token (a regex literal scans as `/`, `ab`, `/`), which adds ends
     * and leaves every real boundary in place; a MERGE would answer too low, i.e.
     * a short span, never a span reaching into the next declaration. The
     * no-advance exit is a fail-safe against a wedged loop inside a long-lived host
     * (CLAUDE.md, round 873), not a reachable case.
     */
    private fun typeCaptureRealEnd(text: String, pos: Int, rawEnd: Int): Int {
        if (pos < 0 || pos >= text.length) return maxOf(pos, 0)
        val scanner = Scanner(text)
        scanner.resetToPosition(pos)
        var best = pos
        while (true) {
            val token = scanner.scan()
            val at = scanner.getPos()
            if (at >= rawEnd || at <= best) break
            best = at
            if (token == SyntaxKind.EndOfFile) break
        }
        return best
    }

    /**
     * (INC.5) Render [type] for a CAPTURE, having first forced every member table
     * and member TYPE the rendering is about to read.
     *
     * ## The defect this exists for, and why it is a DISPLAY defect and not a typing one
     *
     * [typeToString]'s anonymous-object branch renders a property as
     * `symbolTypes[p.id]` — a RAW CACHE READ — and prints `any` when that entry is
     * absent. Nothing populates it for the two utility materializers:
     * `materializeMemberSetUtility` (`Pick`/`Omit`) hands back the SOURCE interface's
     * own member symbols, and `materializeModifierUtility` (`Readonly`) hands back
     * fresh copies carrying the source declarations — in both cases the member's type
     * is resolvable from its declaration and simply has not been asked for yet. So
     * whether a hover reads `{ fileName: string }` or `{ fileName: any }` depends on
     * whether some OTHER file's check happened to ask [getTypeOfSymbol] about that
     * member first, which a whole-program build usually does and a build narrowed by
     * `recheckOnly` does not.
     *
     * Measured by `scripts/capture-equivalence.sh` over tsc's own sources before this
     * landed: of 381,666 captured spans, **45 in 11 files rendered differently**
     * whole-program vs narrowed — types 45, definitions 0 — and in 5 of the 45 it was
     * the WHOLE-PROGRAM arm showing `any`. Neither arm is canonically right; they are
     * two draws from an order-dependent cache, which is (INC.5)'s whole point.
     *
     * ## Why it is forced HERE and not inside [typeToString]
     *
     * [typeToString] is the DIAGNOSTIC renderer. Forcing there would put ~13k corpus
     * baselines in play — and pay for the walk on every compile — for what is a
     * language-service defect. This runs only on the capture path, which is off unless
     * a caller supplied a `TypeCaptureRequest`, so an ordinary compile executes none of
     * it and not one diagnostic can move.
     *
     * ## The two hazards, and what bounds them
     *
     * A member's type can reach back to the type it was reached from, so the walk
     * carries a `seen` set keyed by `Type.id` plus a depth horizon — the same pair
     * [typeCaptureCollectMembers] uses, and for the same reason: a real hover subject
     * nests a couple of levels, so the horizon is a fail-safe rather than a policy.
     * The member tables carry their own re-entrancy guard
     * ([resolveStructuredTypeMembers]), so this adds no new cycle risk of its own.
     *
     * And the ASKING is plain [getTypeOfSymbol]: it writes `symbolTypes` only under
     * round 778's own empty-context gate, so this can never freeze a context-dependent
     * resolution that an ordinary check would have refused to cache. Where the ambient
     * is not empty nothing is cached and the display degrades to exactly what it was.
     */
    private fun typeCaptureRenderType(type: Type): String {
        typeCaptureResolveForDisplay(type, HashSet(), 0)
        return checker.typeToString(type)
    }

    /**
     * (INC.5) Force what [typeToString] will READ out of [type], recursively.
     *
     * Follows [typeToString]'s own shape rather than every edge of the type graph: a
     * [Type.Interface] and a [Type.Reference] render as a NAME (plus, for the
     * reference, its type arguments), so neither one's member table is ever printed
     * and neither is walked here. Only a plain [Type.Object] can render a member body,
     * which is exactly the population the utility materializers produce.
     *
     * `aliasDisplayMap` is consulted first because it SHORT-CIRCUITS the rendering:
     * a type registered there prints as `Alias<args>`, so the args are what needs
     * forcing — `Required<Pick<SymbolTracker, "reportInferenceFallback">>` is one of
     * the 45 divergent rows and its whole content is an alias argument.
     */
    private fun typeCaptureResolveForDisplay(type: Type, seen: MutableSet<Int>, depth: Int) {
        if (depth > TYPE_CAPTURE_MEMBER_MAX_DEPTH) return
        if (!seen.add(type.id)) return
        checker.aliasDisplayMap[type.id]?.let { (_, aliasArgs) ->
            for (arg in aliasArgs) typeCaptureResolveForDisplay(arg, seen, depth + 1)
        }
        when (type) {
            is Type.Union -> for (member in type.types) {
                typeCaptureResolveForDisplay(member, seen, depth + 1)
            }
            is Type.Intersection -> for (member in type.types) {
                typeCaptureResolveForDisplay(member, seen, depth + 1)
            }
            is Type.Interface -> {} // renders as its own name
            is Type.Reference -> type.resolvedTypeArguments?.let { args ->
                for (arg in args) typeCaptureResolveForDisplay(arg, seen, depth + 1)
            }
            is Type.Object -> {
                checker.resolveStructuredTypeMembers(type)
                type.tupleElementTypes?.let { elements ->
                    for (element in elements) typeCaptureResolveForDisplay(element, seen, depth + 1)
                }
                type.properties?.let { properties ->
                    for (property in properties) {
                        typeCaptureResolveForDisplay(checker.getTypeOfSymbol(property), seen, depth + 1)
                    }
                }
                type.callSignatures?.let { signatures ->
                    for (signature in signatures) typeCaptureResolveSignature(signature, seen, depth)
                }
                type.constructSignatures?.let { signatures ->
                    for (signature in signatures) typeCaptureResolveSignature(signature, seen, depth)
                }
                type.stringIndexInfo?.let { typeCaptureResolveForDisplay(it.type, seen, depth + 1) }
                type.numberIndexInfo?.let { typeCaptureResolveForDisplay(it.type, seen, depth + 1) }
            }
            else -> {}
        }
    }

    /** (INC.5) The parameter and return types a rendered signature will print. */
    private fun typeCaptureResolveSignature(sig: Signature, seen: MutableSet<Int>, depth: Int) {
        for (parameter in sig.parameters) {
            typeCaptureResolveForDisplay(checker.getTypeOfSymbol(parameter), seen, depth + 1)
        }
        sig.resolvedReturnType?.let { typeCaptureResolveForDisplay(it, seen, depth + 1) }
    }

    /**
     * (API.3) Records [node]'s type under [fileName], FIRST WINS.
     *
     * First wins because the two hooks are ordered innermost-ambient-first: a
     * function body is checked from its declaration's spine anchor, i.e. BEFORE the
     * spine walks into the body's own nodes, and a narrowed then-branch is checked
     * inside the narrow's install-and-restore. So the answer taken under the
     * tightest ambient is always the one recorded first, and the outer hooks —
     * which would answer the same node from the file-level ambient — must not
     * overwrite it. That ordering is what [TypeCaptureMeasurementTest] measures.
     *
     * `currentCheckFileName` is installed for the call because the spine's own
     * handlers install-and-restore it per anchor dispatch, so at an arbitrary node
     * it is at rest — and it is the key `getTypeOfIdentifier` reaches
     * `fileLocalTypeMaps` through.
     */
    private fun typeCaptureRecord(node: Expression, fileName: String) {
        val span = TypeCaptureSpan(fileName, node.pos, node.end)
        if (span in typeCaptureResults) return
        val savedCheckFileName = checker.currentCheckFileName
        checker.currentCheckFileName = fileName
        try {
            val text = typeCaptureRenderType(typeCaptureReportedType(node))
            typeCaptureResults[span] = CapturedType(
                fileName = fileName,
                start = node.pos,
                end = node.end,
                kind = node.kind.name,
                typeText = text,
            )
        } finally {
            checker.currentCheckFileName = savedCheckFileName
        }
    }

    /**
     * (BUG.4) The type a capture must report AT [node] — which for a MEMBER NAME is
     * NOT the type of that name.
     *
     * ## What was wrong, and it was worse than a missing answer
     *
     * A capture types the node the caller's caret resolved to, and for `o.p` that is
     * the DEEPEST node: the member identifier `p`. Handing that identifier to
     * [getTypeOfExpression] asks the compiler "what is the free name `p`", because a
     * member name is bound by no scope — so the answer was whatever unrelated `p` the
     * file happened to declare, and `any` only where nothing did. Measured against
     * tsc 7.0.2's own language server on a fixture whose members are deliberately
     * spelled like file-level `const`s of OTHER types, twelve of fifteen member
     * positions answered with the collider's type: `o.k` read `boolean` for a
     * `string` property, `box.value` read `string` for a `number` one, `this.p` read
     * `number` for a `string` field. A confidently wrong hover, not a blank one.
     *
     * ## The rule, which is tsc's own
     *
     * tsc's `getTypeOfSymbolAtLocation` moves off the right-hand side of a property
     * access ONTO THE ACCESS and takes the type of that expression. So does this: the
     * type of the `p` in `o.p` is the type of `o.p`. Everything the checker knows
     * about member access then applies with no rule of its own —
     * [computeRawTypeOfPropertyAccess] instantiates a generic member through
     * [resolveGenericPropertyType], distributes over a union receiver, reaches a type
     * parameter's members through its constraint, reads the static side of a class
     * and narrows the access by the flow graph. All six were measured to agree with
     * tsc 7.0.2 after this change and to disagree before it.
     *
     * ## The ONE receiver that needs a carrier, and why
     *
     * `this` and `super` are `Identifier("this")` / `Identifier("super")` in this
     * parser — there is no `ThisExpression` node — so they reach [getTypeOfExpression]
     * as names nothing binds and type as `any`, which makes the access `any` too.
     * That is the same gap (API.3d) had to fill for go-to-definition, and it is
     * filled the same way: from [currentClassForThis], which [typeCaptureVisit]
     * reconstructs by an ascent (BUG.3) rather than reading off a cta frame. The leg
     * is ADDITIVE — when it cannot decide (no enclosing class, a static member where
     * `currentClassForThis` is deliberately null, an unresolvable base) the access's
     * own type answers, which is `any`: a non-answer, and never a wrong name.
     *
     * ## The two neighbours this also closes, and the one it does not
     *
     * An ELEMENT ACCESS `o["p"]` — the caret lands on the string literal, whose own
     * type is `string` whatever the member is, so the answer was right by coincidence
     * for a `string` member and wrong for every other. Same rule, same reason: the
     * type of the literal in `o["p"]` is the type of `o["p"]`. (The go-to-definition
     * refusal recorded in [CapturedDefinition] is untouched — that one is about
     * offering a DECLARATION for a non-identifier, a different question.)
     *
     * A QUALIFIED TYPE NAME `N.T` — no access expression exists to type, so it goes
     * through (API.3d)'s export table instead and reports the DECLARED type of what
     * it finds, which is the interface. Refused when the left side is not a bare
     * name, exactly as the definition leg refuses `A.B.x`.
     *
     * An OBJECT-LITERAL KEY (`{ p: v }`, `{ "p": v }`, `{ ["p"]: v }`) was recorded
     * here as deliberately NOT closed, on the ground that its useful answer is the
     * CONTEXTUAL type's property and that the contextual type is walk-scoped state a
     * capture cannot read. (API.10) then built [typeCaptureContextualType], which is
     * purely SYNTACTIC and is therefore exactly the mechanism that reason said did not
     * exist — and nobody came back for the type. (API.17) did:
     * [typeCaptureObjectLiteralKeyType] is that leg, and before it a key answered `any`
     * or the COLLIDER's type, which is the same confidently-wrong answer this KDoc
     * opens by describing.
     *
     * STILL not closed, deliberately: a SHORTHAND `{ p }`, which keeps reporting the
     * LOCAL `p` it references — a true statement about a different subject where the
     * span names two. Round 922's reason, narrowed to the one shape it still holds for.
     */
    fun typeCaptureReportedType(node: Expression): Type =
        typeCaptureMemberAccessType(node)
            ?: typeCaptureMemberDeclarationType(node)
            ?: checker.getTypeOfExpression(node)

    /**
     * (API.11) The type of the member [node] DECLARES, or null when it declares none.
     *
     * The same defect (BUG.4) closed for a member USE, one position over: the `p` of
     * `interface Shape { p: string }` is bound by no scope either, so asking the
     * compiler for "the type of the free name `p`" answered `any` where nothing shared
     * the spelling and the COLLIDER's type where something did. Measured against tsc
     * 7.0.2, every one of eighteen member declaration names reported `any` here and a
     * real type there.
     *
     * The rule is (API.11)'s own — a member declaration name is resolved through its
     * OWNER — and the type is then the member symbol's, so a generic member reports its
     * declared form and an inherited one is not consulted at all (it is a different
     * declaration). Null wherever the owner does not resolve or does not declare the
     * name, which leaves the answer exactly what it was: a non-answer, never a wrong
     * name.
     */
    private fun typeCaptureMemberDeclarationType(nodeIn: Expression): Type? {
        val member = (nodeIn as NodeBase).parent ?: return null
        val node = typeCaptureMemberNameIdentifier(member)?.takeIf { it === nodeIn } ?: return null
        val owner = (member as NodeBase).parent ?: return null
        if (owner is ObjectLiteralExpression) return null
        if (owner is EnumDeclaration) return typeCaptureEnumMemberType(owner, node.text)
        val ownerType = when (owner) {
            is TypeLiteral -> checker.getTypeFromTypeNode(owner)
            else -> typeCaptureOwnerSymbol(owner)?.let { checker.getDeclaredTypeOfSymbol(it) }
        } ?: return null
        val symbols = ArrayList<Symbol>(2)
        typeCaptureCollectMembers(ownerType, node.text, symbols, 0)
        return symbols.firstOrNull()?.let { checker.getTypeOfSymbol(it) }
    }

    /**
     * (API.15) The type of the enum member [name] the enum [owner] declares, or null.
     *
     * ## Why this leg exists at all, when the one above it looks general
     *
     * The general leg reads the OWNER's declared type and asks it for the member.
     * That works for every owner but this one: **an enum's own type is a member-LESS
     * `Type.Object`** (CLAUDE.md — tsc models a literal enum as the union of its
     * members and we mint one opaque object for the whole enum), so
     * [typeCaptureCollectMembers] finds nothing there, the leg answers null and the
     * name falls through to the free-name path, which types a name nothing binds as
     * `any`. Measured round 930 on four shapes: `any` at every enum member
     * declaration name, where tsc 7.0.2 answers `(enum member) Plain.Alpha = 0` and
     * where this API's OWN use site (`Plain.Alpha`) already answers `Plain.Alpha`.
     * That made it the one place in the language-service surface where a plausible
     * WRONG answer was given instead of no answer.
     *
     * ## The one mint
     *
     * [getDeclaredTypeOfEnumMember] and nothing else — it interns on the CANONICAL
     * enum symbol, so the type this reports is the very instance the use site
     * reports, and a per-symbol mint here would split one member into two non-equal
     * types (CLAUDE.md's standing rule for this key space). The member symbol comes
     * from the enum symbol's own export table, which is where the canonical member
     * lives; a name absent from it, a symbol that is not an [SymbolFlags.EnumMember]
     * and an `anyType` answer all return null, which leaves the position exactly as
     * it was rather than replacing one wrong answer with another.
     *
     * The obvious alternative is MEASURED and it does not work: ablated to
     * `getTypeOfSymbol(memberSymbol)` — the call every other member leg here makes —
     * all five enum shapes answer `any` again, because an enum member's type exists
     * only through the declared-type route. So this is not a stylistic preference for
     * the interning helper; it is the only call that answers at all.
     *
     * ## The soundness condition, and the honest note about it
     *
     * The owner name is resolved through the scope chain, so it could in principle
     * land on some OTHER same-spelled enum — and unlike the general leg, whose answer
     * would then merely be a differently-shaped type, this one reports a NAME
     * (`Other.Alpha`), i.e. exactly the plausible-wrong-answer failure it exists to
     * remove. So the resolved symbol is trusted only when the enum we are standing in
     * is one of its own declarations, which is [typeCaptureMemberDeclarations]' rule
     * one function over. MEASURED REDUNDANT in round 931 on every shape that could be
     * constructed — a block-scoped shadow, an import collision, a namespace nesting,
     * an import ALIAS shadow and a merged pair all answer identically with the check
     * dropped, because round 748's lexical scope space binds a block-scoped enum and
     * the name therefore finds the enum under the caret. It is kept as the sibling
     * leg's rule rather than claimed as a pin; the control that IS pinned is the
     * import-alias shadow's ANSWER (`Local.Alpha`, never `Kind.Alpha`).
     */
    private fun typeCaptureEnumMemberType(owner: EnumDeclaration, name: String): Type? {
        val enumSymbol = typeCaptureOwnerSymbol(owner)
            ?.takeIf { symbol -> symbol.declarations.any { it === owner } }
            ?: return null
        if (enumSymbol.flags.hasNone(SymbolFlags.Enum)) return null
        val memberSymbol = enumSymbol.exports?.get(name) ?: return null
        if (memberSymbol.flags.hasNone(SymbolFlags.EnumMember)) return null
        return checker.getDeclaredTypeOfEnumMember(memberSymbol).takeIf { it !== anyType }
    }

    /**
     * (BUG.4) The type of the ACCESS [node] is the member name of, or null when
     * [node] is not a member name at all.
     *
     * Null is the "not a member position" answer and nothing else — every leg that
     * accepts the position returns a type, falling back to the access's own type
     * rather than to null, so a member name can never be re-asked as a free name.
     */
    private fun typeCaptureMemberAccessType(node: Expression): Type? =
        when (val parent = (node as NodeBase).parent) {
            is PropertyAccessExpression ->
                if (parent.name !== node) null else typeCapturePropertyAccessType(parent)
            // Only a member-naming literal: a computed `o[i]` names no member, and the
            // caret there is on an ordinary expression whose own type is the right
            // answer. (API.16) added the no-substitution template to that set — with a
            // resolution of its own, see below.
            is ElementAccessExpression ->
                if (parent.argumentExpression !== node) null
                else typeCaptureElementAccessMemberType(parent, node)
            is QualifiedName ->
                if (parent.right !== node) null else typeCaptureQualifiedNameType(parent)
            // (API.17) An OBJECT-LITERAL KEY, `{ p: v }` and `{ ["p"]: v }`. See
            // [typeCaptureObjectLiteralKeyType] for what this cashes in.
            is PropertyAssignment ->
                if (parent.name !== node) null else typeCaptureObjectLiteralKeyType(node)
            is ComputedPropertyName ->
                if (parent.expression !== node) null
                else typeCaptureObjectLiteralKeyType(node)
            else -> null
        }

    /**
     * (API.17) The type of the member an object-literal KEY names, or null when [node]
     * is a computed name that belongs to no object literal.
     *
     * ## A refusal whose stated reason had already been removed
     *
     * [typeCaptureReportedType] recorded this position as deliberately NOT closed, and
     * its reason was that the useful answer is the literal's CONTEXTUAL member and that
     * the contextual type is walk-scoped state a capture cannot read. (API.10) then
     * built [typeCaptureContextualType] — a purely SYNTACTIC walk out of the literal,
     * a function of the position and of nothing the walk happens to be doing — for
     * go-to-definition, and nobody came back for the type. Measured before this round,
     * on the same fixture shape (BUG.4) was written against: a key answered `any` where
     * nothing shared its spelling and the COLLIDER'S type where something did — `{ p: 1
     * }` for a `number` member reported `string`, the type of an unrelated file-level
     * `const p`. That is the confidently-wrong answer *prove to offer* exists to
     * prevent, one position over from where (BUG.4) and (API.11) each found it.
     *
     * ## The two answers, and why the fallback is not a guess
     *
     * The CONTEXTUAL member's type when the literal has one — `(property) Shape.p:
     * number` is what tsc 7.0.2 reports there — and otherwise the key's OWN value,
     * which is what tsc reports for a free key (`(property) z: number`, and `(property)
     * ["z"]: number` for the computed spelling; this API renders types, so both are
     * `number` here). Never the enclosing scope, which is the one answer that can name
     * a different subject.
     */
    private fun typeCaptureObjectLiteralKeyType(node: Node): Type? {
        val (assignment, name) = typeCaptureObjectLiteralKey(node) ?: return null
        val literal = (assignment as NodeBase).parent as? ObjectLiteralExpression
        if (literal != null) {
            val members = typeCaptureContextualMembers(literal, name)
            members.firstOrNull()?.let { return checker.getTypeOfSymbol(it) }
        }
        return checker.getTypeOfExpression(assignment.initializer)
    }

    /**
     * (BUG.4)/(API.16) The type of the member the LITERAL [node] names in [access], or
     * null when it names none.
     *
     * A STRING literal takes the ACCESS's own type, which is (BUG.4)'s rule and the
     * better answer: the compiler's own element-access typing is what applies the flow
     * narrowing, the union distribution and the index signatures, so the type of the
     * `"p"` in `o["p"]` is the type of `o["p"]`.
     *
     * A no-substitution TEMPLATE cannot take that route, and the reason is worth
     * stating because it is not a language rule but this compiler's: element-access
     * typing keys a named member off a STRING literal argument, so ``o[`p`]`` types as
     * `any` — measured. Routing the template through the access would therefore have
     * replaced this position's old answer (`string`, the literal's own type, wrong but
     * harmless) with `any`, i.e. re-created the very *prove to offer* violation
     * (API.15) closed one round earlier. So the member is resolved directly, through
     * the same receiver walk the definition leg uses, and the access's own type is the
     * fallback. tsc 7.0.2 answers `(property) I.p: number` at both carets; this now
     * answers the same type at both, minus the flow narrowing the template form does
     * not carry.
     */
    private fun typeCaptureElementAccessMemberType(
        access: ElementAccessExpression,
        node: Node,
    ): Type? = when (node) {
        is StringLiteralNode -> checker.getTypeOfExpression(access)
        is NoSubstitutionTemplateLiteralNode ->
            typeCaptureIndirectMemberSymbols(node)?.firstOrNull()?.let { checker.getTypeOfSymbol(it) }
                ?: checker.getTypeOfExpression(access)
        else -> null
    }

    /** (BUG.4) The type of [access], with the `this`/`super` carrier leg. */
    private fun typeCapturePropertyAccessType(access: PropertyAccessExpression): Type {
        val receiver = access.expression
        if (receiver is Identifier && (receiver.text == "this" || receiver.text == "super")) {
            typeCaptureThisMemberType(receiver, access.name.text)
                ?.let { return typeCaptureOptionalMemberType(access, it) }
        }
        return typeCaptureOptionalMemberType(access, checker.getTypeOfExpression(access))
    }

    /**
     * (CHK.61)(b), DISPLAY HALF — an OPTIONAL property's access type carries
     * `| undefined`, and then narrows.
     *
     * [computeRawTypeOfPropertyAccess] answers a member's DECLARED type and nothing
     * else, so `o.p` where `p?: number` types `number` everywhere: tsc says
     * `number | undefined`, and a hover is therefore a confident wrong answer rather
     * than a missing one. Adding the constituent at the resolution itself is
     * MEASURED and REFUSED for this round — it is only sound together with opening
     * `canUseTypeEngine`'s nullish-union-vs-primitive gate, whose own price is nine
     * net false positives on the eight profiles (the session note carries the
     * decomposition). This leg is confined to the CAPTURE, which production never
     * computes, so no diagnostic anywhere can move.
     *
     * The constituent is added and the reference is then RE-NARROWED, which is the
     * whole reason the widening is safe to show: inside `if (o.p)` the flow walk
     * subtracts `undefined` again and the hover still reads `number`. Narrowing is
     * the flow walk ([getNarrowedTypeForReference]), not the legacy if-arm machinery
     * that the refused checking half trips over, so an `&&`-guarded read narrows
     * here even where the assignability readers would not.
     *
     * Conservative by construction — it may decline to widen, it can never invent a
     * constituent. A UNION receiver is decided per constituent by
     * [memberIsOptionalOnReceiver], never by asking the union itself (round 916):
     * that arm's own ablation is green with the OPTIONAL constituent written first
     * and reddens only with the REQUIRED one first, so both orders are pinned.
     *
     * The `super` and INTERSECTION refusals are MEASURED REDUNDANT and are recorded
     * as such rather than claimed (last round's arm c1). Both shapes are pinned as
     * RESIDUE with the value we answer — `number` where tsc says `number |
     * undefined` — and BOTH keep answering it with the refusal removed, because the
     * property lookup below already declines: `getPropertyOfType` has no
     * Intersection branch, and a `super` receiver types to nothing it can resolve a
     * member on (a two-mistake mechanism probe that also removed the `any`-receiver
     * guard did not discriminate either). They are kept because each states the
     * question this leg is NOT answering, and because giving `super` a carrier —
     * exactly what (CHK.61)(a) did for `this` — would make the first one
     * load-bearing overnight.
     */
    private fun typeCaptureOptionalMemberType(access: PropertyAccessExpression, captured: Type): Type {
        if (!checker.strictNullChecks) return captured
        if (captured === anyType || captured === errorType || checker.typeIncludesUndefined(captured)) return captured
        val receiver = access.expression
        if (receiver is Identifier && receiver.text == "super") return captured
        val receiverType = checker.thisReceiverCarrierType(receiver) ?: checker.getTypeOfExpression(receiver)
        if (receiverType === anyType || receiverType === errorType) return captured
        val apparent = checker.getApparentType(receiverType)
        if (apparent is Type.Intersection) return captured
        if (!memberIsOptionalOnReceiver(apparent, access.name.text)) return captured
        val widened = checker.getUnionType(listOf(captured, undefinedType))
        return if (checker.getReferencePath(access) != null)
            checker.getNarrowedTypeForReference(widened, access) else widened
    }

    /**
     * (CHK.61)(b) Is [name] declared OPTIONAL on [receiver]?
     *
     * A UNION receiver is decided per CONSTITUENT and the rule is ANY, which is
     * tsc's: `{ p?: number } | { p: string }` types `p` as
     * `string | number | undefined`, and `{ p: number } | { p: string }` as
     * `number | string`. It may NOT be asked of the union as a whole —
     * `getPropertyOfType`'s union arm answers ONE constituent's symbol (round 916),
     * so the verdict would be a function of constituent ORDER, which is not a
     * property of the program.
     *
     * A constituent on which the name resolves to no property symbol at all (it
     * arrived through an index signature, or through an intersection fold)
     * contributes nothing rather than a verdict — this answers "is it optional",
     * never "does it exist", and the caller has a type in hand either way.
     */
    private fun memberIsOptionalOnReceiver(receiver: Type, name: String): Boolean {
        if (receiver is Type.Union) {
            return receiver.types.any { constituent ->
                checker.getPropertyOfType(checker.getApparentType(constituent), name)?.let { checker.isOptionalProperty(it) } == true
            }
        }
        return checker.getPropertyOfType(receiver, name)?.let { checker.isOptionalProperty(it) } == true
    }

    /**
     * (BUG.4) [name] on the type `this` (or `super`) has here, or null.
     *
     * [resolveMemberPropertyType] is the compiler's own per-constituent member
     * typing — the one [computeRawTypeOfPropertyAccess] uses for a union receiver —
     * so the apparent type, a generic instantiation and an intersection are all
     * handled here by not being handled here. It reaches the member table through
     * [getPropertyOfType], which resolves it first, so round 833's laziness rule is
     * satisfied by construction rather than by a call this could forget.
     *
     * `super` reads the BASE types rather than the this-type. Both answer the same
     * thing for an inherited member — [resolveStructuredTypeMembers] copies a base's
     * symbol into the derived table — and they differ exactly where the derived class
     * OVERRIDES the member, which is the case `super` is written for.
     */
    private fun typeCaptureThisMemberType(receiver: Identifier, name: String): Type? {
        val cls = checker.currentClassForThis ?: return null
        val thisType = checker.resolveUncalledThisType(cls) ?: return null
        if (receiver.text != "super") return checker.resolveMemberPropertyType(thisType, name)
        val declared = thisType as? Type.Interface ?: return null
        checker.resolveStructuredTypeMembers(declared)
        val bases = declared.baseTypes ?: return null
        for (base in bases) checker.resolveMemberPropertyType(base, name)?.let { return it }
        return null
    }

    /**
     * (BUG.4) The declared type of the `T` in a qualified TYPE name `N.T`, or null.
     *
     * A type position carries no access expression to type, so this reuses
     * (API.3d)'s export-table leg — which already covers a namespace, a module alias
     * and an enum — and reports what that symbol DECLARES. `any`/`errorType` is
     * refused rather than reported: it means the symbol names no type, and the
     * free-name fallback above says exactly as little with less confidence.
     */
    private fun typeCaptureQualifiedNameType(qualified: QualifiedName): Type? {
        val exported = typeCaptureExportedMember(qualified.left, qualified.right.text)
            ?: return null
        return checker.getDeclaredTypeOfSymbol(exported).takeIf { it !== anyType && it !== errorType }
    }

    private companion object {
        /** (API.3d) Constituent-recursion horizon for [Checker.typeCaptureCollectMembers].
         *  Bounds a union of unions of intersections; nothing there can cycle (the member
         *  tables carry their own guard) and a real receiver nests one or two deep, so
         *  this is a fail-safe, not a policy. A primitive `const`, so it costs the
         *  class's static initializer nothing (round 820). */
        private const val TYPE_CAPTURE_MEMBER_MAX_DEPTH = 8
        /** (API.7) Cap on the parent walk out of a node to its enclosing class. */
        private const val TYPE_CAPTURE_ENCLOSING_MAX_DEPTH = 512
        /** (API.7) Cap on the `extends` chain the accessibility filter follows. */
        private const val TYPE_CAPTURE_HERITAGE_MAX_DEPTH = 12
    }
}
