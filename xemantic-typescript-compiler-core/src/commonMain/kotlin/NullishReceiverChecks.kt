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
 * (INV.0) (P18.230) — the (CHK.173) NULLISH-RECEIVER family: tsgo's `checkNonNullExpression`
 * on a member / element access receiver (TS18047/18048/18049, TS2531/2532/2533), with the
 * B6 body-local declared-type reader and its unresolved-RHS veto. Extracted VERBATIM from
 * `Checker.kt` (one contiguous span, 155313-155893, less B81.1c's `ReceiverInfo` carrier,
 * which stayed with its one user); every Checker member it reads is reached through
 * [checker]. Owns the B6 per-file declaration memo and the destructured-leaf marker; the
 * veto's pending receiver / declared type stay on [Checker] (see the note there). Ambient
 * reads / writes: `docs/inversion-ambient-ledger.md`.
 */
internal class NullishReceiverChecks(
    private val checker: Checker,
) {

    /** (CHK.173) B6: the receiver whose declared type [bodyLocalReceiverDeclaredType] took from a destructured leaf. */
    private var bodyLocalLeafTyped: Identifier? = null
    /** (CHK.173) B6: [bodyLocalReceiverDeclaredType]'s per-declaration memo (nodeId -> type, [errorType] = refused), per file. */
    private var bodyLocalDeclMemo: IntKeyMap<Type>? = null
    private var bodyLocalDeclMemoFile: SourceFile? = null

    /**
     * (CHK.173) Round B3 — tsgo's `reportObjectPossiblyNullOrUndefinedError`: an entity
     * name expression (an identifier, or a property access of one — never `this`, an
     * element access or a parenthesis) shorter than 100 characters is named in
     * TS18047/18048/18049; every other receiver is TS2531/2532/2533 "Object is possibly …".
     */
    fun reportNullishReceiver(
        recv: Expression, hasNull: Boolean, hasUndef: Boolean, start: Int, length: Int,
        source: String, fileName: String,
    ) {
        val (code, kind) = nullishReceiverCode(hasNull, hasUndef)
        val entity = entityNameExpressionText(recv)?.takeIf { it.length < 100 }
        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
        checker.diagnostics.add(Diagnostic(
            message = if (entity != null) "'$entity' is possibly $kind." else "Object is possibly $kind.",
            category = DiagnosticCategory.Error,
            code = if (entity != null) code else code - 18047 + 2531,
            fileName = fileName, line = line, character = character,
            start = start, length = length,
        ))
    }

    /**
     * tsgo `IsEntityNameExpression` + `entityNameToString`: `a`, `a.b.c`, and `a?.b` as
     * `a.b` (the question dot is not part of an entity name's text); else null.
     */
    private fun entityNameExpressionText(e: Expression): String? = when (e) {
        is Identifier -> if (e.text == "this" || e.text == "super") null else e.text
        is PropertyAccessExpression ->
            if (e.name.text.startsWith("#")) null
            else entityNameExpressionText(e.expression)?.let { "$it.${e.name.text}" }
        else -> null
    }

    /**
     * (CHK.173) Round B3 (G5 + G4p): TS1804x / TS2531-2533 for an access whose receiver
     * is a MEMBER (`o.p.length`, `this.x.length`, `o['p'].length`, `a[0].p.length`,
     * `this.#x.length`) or a PARENTHESIZED expression (`(x).length`, `(y as T | null).s`)
     * whose type carries `null` / `undefined` that survives narrowing — tsgo's
     * `checkNonNullExpression` on the receiver. A bare identifier is Round A's; an
     * OPTIONAL member B81.1c resolved is B81.1c's (the caller skips this then). A receiver
     * inside an optional chain (`o?.p.length`, `o.a?.p.q.length`) is reported like any
     * other — the chain's own `undefined` never reaches the member's type here. Skipped
     * for a call / `new` core (`host.getX?.().y` — the census's one real false positive
     * was a call core, unmeasured otherwise), and where
     * a guard on the receiver-of-receiver narrows it to a type whose member is non-nullish
     * (the round-412 rule B81.1c carries). `this` is typed through [currentClassForThis],
     * installed from [CaptureRecorder.typeCaptureThisClass] for this ask only (Round B2's
     * carrier). Does NOT return anything: tsgo continues on the non-null type.
     */

    fun emitTs1804xForNullableCompoundReceiver(recv: Expression, source: String, fileName: String) {
        if (!checker.strictNullChecks) return
        var core = recv
        var paren = false
        while (core is ParenthesizedExpression) {
            if (core.instantiationEnd != null) return
            paren = true; core = core.expression
        }
        when (core) {
            is PropertyAccessExpression, is ElementAccessExpression -> {}
            is Identifier, is AsExpression, is TypeAssertionExpression, is NonNullExpression -> if (!paren) return
            else -> return
        }
        val savedThis = checker.currentClassForThis
        if (savedThis == null) checker.currentClassForThis = checker.captureRecorder.typeCaptureThisClass(core)
        try {
            val declared = compoundReceiverDeclaredType(core)?.let { typeParamsToBaseConstraints(it) } ?: return
            if (!checker.typeIncludesNull(declared) && !checker.typeIncludesExplicitUndefined(declared)) return
            val path = checker.getReferencePath(core)
            val narrowed = if (path != null) checker.getNarrowedTypeForReferenceFollowLoopEntry(declared, core) else declared
            if (narrowed === anyType || narrowed === unknownType || narrowed === errorType) return
            val hasNull = checker.typeIncludesNull(narrowed)
            val hasUndef = checker.typeIncludesExplicitUndefined(narrowed)
            if (!hasNull && !hasUndef) return
            // (Round A's binding guard is NOT applied to the path root: measured redundant
            // here — 0 of the pins and 0 of seven block / catch / case / nested-function
            // shadow shapes move without it, as the Round B census's arm found.)
            if (path != null && checker.optionalChainGuardsRef(core, path)) return
            if (core is Identifier && bodyLocalVetoes(core)) return
            if (path != null && path.startsWith("this.") && thisMemberAssignedInFunction(core, path)) return
            if (core is PropertyAccessExpression || core is ElementAccessExpression) {
                if (receiverOfReceiverGuardClears(core)) return
            }
            val start = recv.pos
            val length = checker.expressionTrueEnd(recv) - start
            if (length <= 0) return
            reportNullishReceiver(if (paren) recv else core, hasNull, hasUndef, start, length, source, fileName)
        } finally {
            checker.currentClassForThis = savedThis
        }
    }

    /**
     * (CHK.173) Round B3 — an interim guard for a `this.x` receiver: true when the
     * function that binds this `this` (arrows are transparent) ASSIGNS the same member
     * path anywhere. The narrowing then rests on the assigned value's type, and this
     * checker still reads many such values as `any` where tsgo types them (a destructured
     * body local — G1 — as in rxjs WebSocketSubject's `const { WebSocketCtor } =
     * this._config; socket = new WebSocketCtor!(url); this._socket = socket;
     * this._socket.binaryType = …`, the one false row a real library showed). Asked only
     * at a site about to fire; an explicit stack, so a deep binary chain cannot recurse.
     *
     * (CHK.173) B5f measured it NOT retirable yet: `new WebSocketCtor!(…)` now types, but
     * the rxjs row survives the guard's removal because `this._socket = socket` reads the
     * BODY LOCAL `socket` un-narrowed (`let socket: T | null = null; socket = s;
     * this._socket = socket; this._socket.p` is a false TS2531 with no `new` at all) — the
     * G1 gap, B6's round.
     */
    private fun thisMemberAssignedInFunction(core: Expression, path: String): Boolean {
        var fn: Node? = (core as NodeBase).parent
        while (fn != null && fn !is SourceFile) {
            if (fn is FunctionDeclaration || fn is FunctionExpression || fn is MethodDeclaration ||
                fn is Constructor || fn is GetAccessor || fn is SetAccessor ||
                fn is PropertyDeclaration || fn is ClassStaticBlockDeclaration) break
            fn = (fn as NodeBase).parent
        }
        if (fn == null || fn is SourceFile) return false
        val stack = ArrayDeque<Node>()
        stack.addLast(fn)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n is BinaryExpression && isAssignmentOperator(n.operator) &&
                checker.getReferencePath(checker.unwrapParensExpr(n.left)) == path) return true
            forEachChild(n) { stack.addLast(it) }
        }
        return false
    }

    /**
     * The declared type of a Round B3 receiver core: an identifier through Round A /
     * B2's reader, anything else through [getTypeOfExpression] (whose property path
     * already narrows a UNION receiver-of-receiver and adds `| undefined` for an optional
     * member). Null to stay silent.
     */
    private fun compoundReceiverDeclaredType(core: Expression): Type? {
        if (core is Identifier) {
            val name = core.text
            if (name == "this" || name == "super" || name == "arguments" || name == "undefined" || name == "null") return null
            return nullableIdentifierReceiverType(core, name, checker.getTypeOfIdentifier(core))
        }
        val saved = checker.nonNullChainReceiverReads
        checker.nonNullChainReceiverReads = true
        val t = try { checker.getTypeOfExpression(core) } finally { checker.nonNullChainReceiverReads = saved }
        return if (t === anyType || t === errorType || t === unknownType) null else t
    }

    /**
     * B81.1c's round-412 rule for a Round B3 member receiver: a guard on the
     * receiver-of-receiver PATH (`if (isDefined(state)) state.p.x`) narrows it to a type
     * whose member is present and non-nullish — our reference-path walk of `state.p` does
     * not see a guard written on `state`. True = stay silent.
     */
    private fun receiverOfReceiverGuardClears(core: Expression): Boolean {
        val (inner, name) = when (core) {
            is PropertyAccessExpression -> core.expression to core.name.text
            is ElementAccessExpression -> core.expression to ((core.argumentExpression as? StringLiteralNode)?.text ?: return false)
            else -> return false
        }
        val innerPath = checker.getReferencePath(inner) ?: return false
        val innerType = checker.thisReceiverCarrierType(inner) ?: checker.getTypeOfExpression(inner)
        if (innerType === anyType || innerType === errorType) return false
        val innerNarrowed = checker.getNarrowedTypeForReferenceFollowLoopEntry(innerType, inner)
        if (innerNarrowed === innerType) return false
        val parts = (innerNarrowed as? Type.Union)?.types ?: listOf(innerNarrowed)
        for (c in parts) {
            if (checker.isNullishConstituent(c)) continue
            val t = checker.resolveMemberPropertyType(c, name) ?: return false
            if (t === anyType || t === errorType || checker.typeIncludesNull(t) || checker.typeIncludesExplicitUndefined(t)) return false
        }
        return innerPath.isNotEmpty()
    }

    /**
     * B98.r124 (Blocker #1 substep): TS18047/18048/18049 for an element access `x[...]`
     * whose BARE-IDENTIFIER receiver has a declared nullish-union type that SURVIVES flow
     * narrowing at that position. Pairs with the for-in body NonNullable narrowing (Flow.kt)
     * so `for (k in x) { x[k] }` does NOT fire (x narrowed to non-null inside the body) while
     * `x[...]` outside the loop DOES. Consults `getNarrowedTypeForReferenceFollowLoopEntry`
     * (loop-aware), so every guard we model (`if (x)`, `x != null`, truthy `&&`, for-in body,
     * …) suppresses it. Gated to strictNullChecks + a non-`?.` access + a bare Identifier
     * receiver whose declared type is a Union actually carrying a nullish constituent. The
     * code/message follow which nullish members survive (both → 18049, null → 18047,
     * undefined → 18048).
     */
    fun emitTs1804xForNullishElementAccessReceiver(
        expr: ElementAccessExpression, source: String, fileName: String,
    ) {
        if (!checker.strictNullChecks) return
        if (expr.questionDotToken) return
        val recv = expr.expression as? Identifier ?: return
        val declared = typeParamsToBaseConstraints(checker.getTypeOfIdentifier(recv)) ?: return
        if (declared === anyType || declared === errorType) return
        if (declared !is Type.Union) return
        if (!checker.typeIncludesNull(declared) && !checker.typeIncludesExplicitUndefined(declared)) return
        val narrowed = checker.getNarrowedTypeForReferenceFollowLoopEntry(declared, recv)
        val hasNull = checker.typeIncludesNull(narrowed)
        val hasUndef = checker.typeIncludesExplicitUndefined(narrowed)
        if (!hasNull && !hasUndef) return
        // (CHK.173) S-G1c: `t?.[t['length'] - 1]`, `d?.m(d['p'])` — a later part of an
        // optional chain guarded by the receiver (tsgo's optional-chain condition).
        if (checker.optionalChainGuardsRef(recv, recv.text)) return
        val (code, kind) = nullishReceiverCode(hasNull, hasUndef)
        val (line, character) = checker.getLineAndCharacterOfPosition(source, recv.pos)
        checker.diagnostics.add(Diagnostic(
            message = "'${recv.text}' is possibly $kind.",
            category = DiagnosticCategory.Error,
            code = code,
            fileName = fileName,
            line = line,
            character = character,
            start = recv.pos,
            length = recv.text.length,
        ))
    }

    /**
     * (CHK.173) The one TS1804x code/message chooser, shared by the element arm, B464 and
     * Round A's identifier arm: which nullish constituents SURVIVE narrowing decide it.
     */
    fun nullishReceiverCode(hasNull: Boolean, hasUndef: Boolean): Pair<Int, String> = when {
        hasNull && hasUndef -> 18049 to "'null' or 'undefined'"
        hasNull -> 18047 to "'null'"
        else -> 18048 to "'undefined'"
    }

    /**
     * (CHK.173) Round A: TS18047/18048/18049 for `x.p` (read, write, call) whose BARE
     * identifier receiver is declared a union carrying `null`/`undefined` that SURVIVES
     * flow narrowing — the property twin of [emitTs1804xForNullishElementAccessReceiver],
     * reading the same two types. Gates: strictNullChecks (our declared types keep
     * `| null` without it); no `?.`; no paren unwrap (`(x).p` is tsgo's TS2531); not
     * `this`/`super`/`arguments`/`undefined`; not a later part of an optional chain
     * guarded by `x`; and [LocalShadowGuard.nullableReceiverBindingRefused] until G5 S2
     * makes a block-scoped shadow's type right. Runs after B464, which returns when it
     * fires, so a closure-captured `undefined` is reported once. Does NOT return.
     */
    fun emitTs1804xForNullableIdentifierReceiver(
        expr: PropertyAccessExpression, source: String, fileName: String,
    ) {
        if (!checker.strictNullChecks) return
        if (expr.questionDotToken) return
        val recv = expr.expression as? Identifier ?: return
        val name = recv.text
        if (name == "this" || name == "super" || name == "arguments" || name == "undefined") return
        val declared = nullableIdentifierReceiverType(recv, name, checker.getTypeOfIdentifier(recv)) ?: return
        if (!checker.typeIncludesNull(declared) && !checker.typeIncludesExplicitUndefined(declared)) return
        // (CHK.173) Round B2: the cpa walk does not thread `this`, so a guarded
        // reassignment from `this.m()` / `this.p` ([resolvePropertyMethodDecl]'s
        // carrier) proved nothing and the reference kept its `| undefined`
        // (`if (!sf) sf = this.getSourceFile(); sf.text`, tsc services.ts).
        val savedThis = checker.currentClassForThis
        if (savedThis == null) checker.currentClassForThis = checker.captureRecorder.typeCaptureThisClass(recv)
        val narrowed = try {
            checker.getNarrowedTypeForReferenceFollowLoopEntry(declared, recv)
        } finally {
            checker.currentClassForThis = savedThis
        }
        if (narrowed === anyType || narrowed === unknownType || narrowed === errorType) return
        val hasNull = checker.typeIncludesNull(narrowed)
        val hasUndef = checker.typeIncludesExplicitUndefined(narrowed)
        if (!hasNull && !hasUndef) return
        if (checker.optionalChainGuardsRef(recv, name)) return
        if (bodyLocalVetoes(recv)) return
        // (CHK.173) B6: a destructured body-local leaf typed by [bodyLocalReceiverDeclaredType]
        // is the innermost binding the lexical symbol names — the guard's R3 existed only
        // because the cpa frame could not type one.
        if (bodyLocalLeafTyped !== recv && LocalShadowGuard.nullableReceiverBindingRefused(recv, name)) return
        val (code, kind) = nullishReceiverCode(hasNull, hasUndef)
        val (line, character) = checker.getLineAndCharacterOfPosition(source, recv.pos)
        checker.diagnostics.add(Diagnostic(
            message = "'$name' is possibly $kind.",
            category = DiagnosticCategory.Error,
            code = code,
            fileName = fileName,
            line = line,
            character = character,
            start = recv.pos,
            length = name.length,
        ))
    }

    /**
     * (CHK.173) Round B2 — the declared type Round A's identifier arm reads, or null to
     * stay silent: a nullable UNION (Round A); an OPTIONAL parameter `x?: T` not shadowed
     * by a local, as `T | undefined` (G2 — [populateParameterLocalTypes] records the bare
     * `T`, tsgo's declared type carries `undefined`; the lexical symbol RETURNS the
     * parameter itself, so the shadow test is declaration identity, never null); and a
     * non-union `null` / `undefined` (G3b) unless the receiver is the `null` keyword,
     * which parses as an Identifier and is tsgo's TS18050.
     */
    private fun nullableIdentifierReceiverType(recv: Identifier, name: String, toi00: Type): Type? {
        // (CHK.173) B6: a body local reads `any` in the cpa frame — resolve its declared type here.
        val toi0 = if (toi00 === anyType) bodyLocalReceiverDeclaredType(recv, name) ?: return null else toi00
        if (toi0 === anyType || toi0 === errorType || toi0 === unknownType) return null
        val toi = typeParamsToBaseConstraints(toi0) ?: return null
        val p = LocalShadowGuard.optionalParameterBinding(recv, name)
        if (p != null && checker.lexicalScopeSymbol(recv, name).let { it == null || it.valueDeclaration === p }) {
            return if (checker.typeIncludesExplicitUndefined(toi)) toi else checker.getUnionType(listOf(toi, undefinedType))
        }
        if (toi is Type.Union) return toi
        if ((toi === nullType || toi === undefinedType) && name != "null") return toi
        return null
    }

    /**
     * (CHK.173) B6 — the declared type of a BODY LOCAL receiver the cpa frame reads as
     * `any` (it records only call / element initializers), or null to stay silent. The
     * lexical symbol must be a single-declaration `VariableDeclaration` / `BindingElement`
     * (never a parameter, never a name a walk table already holds); the type is the
     * annotation, else the initializer — an identifier / property path narrowed at the
     * initializer, an `&&` / `||` / `??` with its left operand narrowed there (N11,
     * checker.ts 43917: `const r = p && fi(p)`), anything else [getTypeOfExpression] —
     * or a destructured leaf's [bindingElementDeclaredType] (B5c narrows it). Refused
     * per read: a read inside an object-literal / class-expression method (tsgo extends
     * the flow container there, this flow graph does not), an initializer naming a
     * binding the read's scope shadows, a leaf after an element-access guard (B5c's
     * residue r4 / r5). The UNRESOLVED-RHS VETO ([bodyLocalAssignmentsVeto]) runs only in the emitters, for a read whose narrowed type kept a nullish member ([bodyLocalVetoes]) — it can only suppress.
     */
    private fun bodyLocalReceiverDeclaredType(recv: Identifier, name: String): Type? {
        bodyLocalLeafTyped = null
        checker.bodyLocalVetoPending = null
        checker.bodyLocalVetoDeclared = null
        if (name in checker.currentLocalTypes || name in checker.currentShadowedNames || name in checker.currentParamBindingNames) return null
        val sym = checker.lexicalScopeSymbol(recv, name) ?: return null
        if (sym.declarations.size != 1) return null
        val vd = sym.valueDeclaration ?: return null
        if (vd !is VariableDeclaration && vd !is BindingElement) return null
        // Cheapest first: the per-declaration memo answers every repeat read, and most
        // body locals are not nullable at all.
        val declared = bodyLocalDeclaredTypeMemo(recv, vd) ?: return null
        if (BodyLocalAssignments.readCrossesUnmodeledContainer(recv, vd)) return null
        if (vd is BindingElement) {
            val root = bindingElementInitializerRoot(vd)
            val flow = checker.getFlowAt(recv)
            if (root != null && flow != null && BodyLocalAssignments.conditionTestsElementOf(flow, root)) return null
            bodyLocalLeafTyped = recv
        }
        checker.bodyLocalVetoPending = recv
        checker.bodyLocalVetoDeclared = declared
        return declared
    }

    /** B6: the veto, asked by an emitter only for a read about to fire. True = stay silent. */
    private fun bodyLocalVetoes(recv: Identifier): Boolean {
        if (checker.bodyLocalVetoPending !== recv) return false
        val declared = checker.bodyLocalVetoDeclared ?: return false
        return bodyLocalAssignmentsVeto(recv, recv.text, declared)
    }

    /** B6: the `VariableDeclaration` a body-local declaration (or a destructured leaf of one) belongs to. */
    private fun bodyLocalOwnerDeclaration(vd: Node): VariableDeclaration? {
        var n: Node? = vd
        var hops = 0
        while (n != null && n !is VariableDeclaration && hops++ < 32) n = (n as NodeBase).parent
        return n as? VariableDeclaration
    }

    /**
     * B6: [vd]'s declared type for the receiver arm — nullish, not `any` — or null. A
     * function of the declaration alone (an initializer is narrowed at ITS position,
     * never the read's), so memoized per file by node id: a body local is read many times.
     * The FIRST computation types the initializer in the read's ambient, so it is refused
     * (and not memoized) where an identifier of the initializer names a different binding
     * there (a nested function's parameter shadowing it).
     */
    private fun bodyLocalDeclaredTypeMemo(recv: Identifier, vd: Node): Type? {
        var sf: Node? = recv
        var hops = 0
        while (sf != null && sf !is SourceFile && hops++ < 100_000) sf = (sf as NodeBase).parent
        val id = (vd as NodeBase).nodeId
        val memo = if (sf is SourceFile && id >= 0) {
            if (bodyLocalDeclMemoFile !== sf) { bodyLocalDeclMemoFile = sf; bodyLocalDeclMemo = IntKeyMap(64) }
            bodyLocalDeclMemo
        } else null
        memo?.get(id)?.let { return if (it === errorType) null else it }
        val owner = bodyLocalOwnerDeclaration(vd) ?: return null
        val init = owner.initializer
        if (owner.type == null && init != null && rhsBindingsDiverge(init, recv)) return null
        val t = bodyLocalDeclaredTypeCompute(vd)
        memo?.set(id, t ?: errorType)
        return t
    }

    private fun bodyLocalDeclaredTypeCompute(vd: Node): Type? {
        val declared: Type = when (vd) {
            is VariableDeclaration -> {
                val ann = vd.type
                if (ann != null) checker.getTypeFromTypeNode(ann)
                else bodyLocalInitializerType(vd.initializer ?: return null)
            }
            is BindingElement -> checker.bindingElementDeclaredType(vd, 0) ?: return null
            else -> return null
        }
        if (declared === anyType || declared === errorType || declared === unknownType) return null
        // Nullishness through a type parameter's base constraint, as the caller reads it.
        val probe = typeParamsToBaseConstraints(declared) ?: return null
        if (!checker.typeIncludesNull(probe) && !checker.typeIncludesExplicitUndefined(probe)) return null
        // (No N6 type-parameter refusal: the caller reads a type parameter through its BASE
        // constraint, so a failed inference's unconstrained `U` is silent there already, and
        // the census's refusal measured 0 on every pin, cell set, profile and library while
        // costing an in-scope constrained `T`'s true row.)
        return declared
    }

    /** B6's initializer type: a reference narrowed at its own position; N11's logical operators. */
    private fun bodyLocalInitializerType(init: Expression, depth: Int = 0): Type {
        if (depth > 8) return checker.getTypeOfExpression(init)
        if (init is Identifier || init is PropertyAccessExpression) {
            val t = checker.getTypeOfExpression(init)
            return if (t === anyType || t === errorType) t else checker.getNarrowedTypeForReference(t, init)
        }
        if (init is ParenthesizedExpression) return bodyLocalInitializerType(init.expression, depth + 1)
        if (init is ConditionalExpression) {
            val a = bodyLocalInitializerType(init.whenTrue, depth + 1)
            val b = bodyLocalInitializerType(init.whenFalse, depth + 1)
            if (a === anyType || a === errorType || a === unknownType) return a
            if (b === anyType || b === errorType || b === unknownType) return b
            return checker.getUnionType(listOf(a, b))
        }
        if (init is BinaryExpression && (init.operator == SyntaxKind.AmpersandAmpersand ||
                init.operator == SyntaxKind.BarBar || init.operator == SyntaxKind.QuestionQuestion)) {
            val lt = bodyLocalInitializerType(init.left, depth + 1)
            if (lt === anyType || lt === errorType || lt === unknownType) return lt
            val parts = (lt as? Type.Union)?.types ?: listOf(lt)
            val nullish = parts.filter { checker.isNullishConstituent(it) }
            val rest = parts.filterNot { checker.isNullishConstituent(it) }
            // tsgo (checker.ts 43917 `const r = p && fi(p)`, N11): an all-nullish left
            // short-circuits; a nullish-free `??` left never reads the right.
            if (rest.isEmpty() && init.operator == SyntaxKind.AmpersandAmpersand) return lt
            if (nullish.isEmpty() && init.operator == SyntaxKind.QuestionQuestion) return lt
            val rt = bodyLocalInitializerType(init.right, depth + 1)
            if (rt === anyType || rt === errorType || rt === unknownType) return rt
            return when (init.operator) {
                // the definitely-falsy part of the left, or the right — only its NULLISH part
                // matters to the receiver arm.
                SyntaxKind.AmpersandAmpersand -> checker.getUnionType(nullish + listOf(rt))
                // `??` and `||` drop the nullish part (`||`'s other falsy parts are never nullish).
                else -> if (rest.isEmpty()) rt else checker.getUnionType(rest + listOf(rt))
            }
        }
        return checker.getTypeOfExpression(init)
    }

    /**
     * B6's UNRESOLVED-RHS VETO, true to stay silent: some assignment of [name] reaching
     * [recv] ([BodyLocalAssignments], crossing into the enclosing function for a closure
     * read) has a right-hand side typing `any` / `error` / `unknown` — the narrowing then
     * rests on a value this checker cannot type (rxjs `inner = source.subscribe(…)`
     * through a generic contextual parameter, N17; `new X!()` results, `this.x`) — or
     * EVERY reaching assignment is non-nullish (a missing initializer counts as
     * `undefined`), so the variable cannot hold null / undefined at the read (marked
     * `Instance.ts`, N3). The right-hand sides are typed with [name]'s own
     * [declared] type installed, or `s &&= s.trim()` reads `s` as `any` and vetoes a
     * true row.
     */
    internal fun bodyLocalAssignmentsVeto(recv: Identifier, name: String, declared: Type): Boolean {
        val flow = checker.getFlowAt(recv) ?: return false
        val reaching = BodyLocalAssignments.reaching(flow, name)
        val comparands = BodyLocalAssignments.optionalChainComparands(flow, name)
        if (reaching.isEmpty() && comparands.isEmpty()) return false
        val installed = name !in checker.currentLocalTypes
        if (installed) checker.currentLocalTypes[name] = declared
        val savedThis = checker.currentClassForThis
        if (savedThis == null) checker.currentClassForThis = checker.captureRecorder.typeCaptureThisClass(recv)
        try {
            // N10: `x?.p === y` narrows `x` by `y`'s type, which must be typeable here.
            for (c in comparands) {
                val t = checker.getTypeOfExpression(c)
                if (t === anyType || t === errorType || t === unknownType) return true
            }
            if (reaching.isEmpty()) return false
            var allNonNullish = true
            for (r in reaching) {
                val rhs = r.rhs
                if (rhs == null) { allNonNullish = false; continue }
                // Typed in the READ's ambient: an identifier the right-hand side binds
                // differently (`{ const s = 'x'; t = s }`) would be typed as the outer one.
                if (rhsBindingsDiverge(rhs, recv)) return true
                val t0 = checker.getTypeOfExpression(rhs)
                if (t0 === anyType || t0 === errorType || t0 === unknownType) return true
                // A type parameter through its base constraint; an unconstrained one may be nullish.
                val t = typeParamsToBaseConstraints(t0)
                if (t == null || checker.typeIncludesNull(t) || checker.typeIncludesExplicitUndefined(t) ||
                    ((t as? Type.Union)?.types ?: listOf(t)).any { checker.isNullishConstituent(it) }) allNonNullish = false
            }
            return allNonNullish
        } finally {
            checker.currentClassForThis = savedThis
            if (installed) checker.currentLocalTypes.remove(name)
        }
    }

    /** B6: the identifier a destructured leaf's pattern is initialized from (through property / element paths), or null. */
    private fun bindingElementInitializerRoot(elem: BindingElement): String? {
        var n: Node? = (elem as NodeBase).parent
        var hops = 0
        while (n != null && n !is VariableDeclaration && hops++ < 32) n = (n as NodeBase).parent
        var r: Expression = (n as? VariableDeclaration)?.initializer ?: return null
        while (true) r = when (r) {
            is PropertyAccessExpression -> r.expression
            is ElementAccessExpression -> r.expression
            is ParenthesizedExpression -> r.expression
            is NonNullExpression -> r.expression
            else -> break
        }
        return (r as? Identifier)?.text
    }

    /**
     * B6: true when a value identifier in [rhs] (not inside a nested function or class)
     * names a different lexical binding at [rhs] than at [read] — the veto types the
     * right-hand side in the read's ambient, which would then read the wrong binding.
     */
    private fun rhsBindingsDiverge(rhs: Expression, read: Identifier): Boolean {
        val stack = ArrayDeque<Node>()
        stack.addLast(rhs)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            when (n) {
                is ArrowFunction, is FunctionExpression, is ClassExpression -> continue
                is Identifier -> {
                    val p = (n as NodeBase).parent
                    val isName = (p is PropertyAccessExpression && p.name === n) || (p is PropertyAssignment && p.name === n)
                    if (!isName && checker.lexicalScopeSymbol(n, n.text) !== checker.lexicalScopeSymbol(read, n.text)) return true
                    continue
                }
                else -> {}
            }
            forEachChild(n) { stack.addLast(it) }
        }
        return false
    }

    /**
     * (CHK.173) Round B4 (G3a) — [t] with every type parameter (bare, or a union member)
     * replaced by its BASE constraint, the type whose null / undefined tsgo's
     * `checkNonNullType` reads through `getTypeFacts` (an instantiable type's facts are its
     * base constraint's). [t] itself when it names no type parameter; null when one has no
     * constraint (an unconstrained `T` is `unknown`-faceted and tsgo reports no TS1804x for
     * it) or the chain is circular.
     */
    fun typeParamsToBaseConstraints(t: Type, depth: Int = 0): Type? {
        // A constraint may name a type parameter again, directly or inside a union
        // (`<T extends U | null, U extends T>` is a TS2313 cycle): bounded, never recursive
        // without limit.
        if (depth > 8) return null
        fun base(tp: Type.TypeParam): Type? {
            var cur: Type = tp
            var hops = 0
            while (cur is Type.TypeParam) {
                cur = cur.constraint ?: return null
                if (++hops > 32) return null
            }
            return typeParamsToBaseConstraints(cur, depth + 1)
        }
        return when (t) {
            is Type.TypeParam -> base(t)
            is Type.Union -> {
                if (t.types.none { it is Type.TypeParam }) return t
                val parts = ArrayList<Type>(t.types.size)
                for (m in t.types) {
                    if (m is Type.TypeParam) {
                        val b = base(m) ?: return null
                        if (b is Type.Union) parts.addAll(b.types) else parts.add(b)
                    } else parts.add(m)
                }
                checker.getUnionType(parts)
            }
            else -> t
        }
    }
}
