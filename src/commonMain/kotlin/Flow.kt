/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

// ---------------------------------------------------------------------------
// Flow nodes — control-flow graph for narrowing
//
// Each AST reference position is associated with a FlowNode that represents
// the program point just before that reference is evaluated. The checker
// (in Phase 17 step 2 — not yet wired) walks back through antecedents to
// determine the narrowed type at that point.
//
// Modeled on TypeScript's `FlowNode` types in src/compiler/types.ts and
// tsgo's internal/checker/flow.go.
// ---------------------------------------------------------------------------

sealed interface FlowNode {
    val id: Int
}

/** The start of a function's flow (or the file's top-level flow). */
class FlowStart(override val id: Int, val container: Node?) : FlowNode

/** Code after return/throw/break/continue — unreachable. */
class FlowUnreachable(override val id: Int) : FlowNode

/**
 * A point reachable from multiple predecessors (e.g. after if/else,
 * end of switch, after try/catch). Antecedents are appended as branches
 * converge during graph construction.
 */
class FlowBranchLabel(
    override val id: Int,
    val antecedents: MutableList<FlowNode> = mutableListOf(),
) : FlowNode

/**
 * A loop's join point (top-of-loop). Has antecedents from the entry and
 * from each `continue` / back-edge after the loop body completes.
 */
class FlowLoopLabel(
    override val id: Int,
    val antecedents: MutableList<FlowNode> = mutableListOf(),
) : FlowNode

/**
 * Flow node after assigning to a variable, property, element, or destructured
 * binding. The `node` is the assignment target (LHS of `=`, or the
 * VariableDeclaration / BindingElement / Parameter introducing the binding).
 */
class FlowAssignment(
    override val id: Int,
    val node: Node,
    val antecedent: FlowNode,
) : FlowNode

/**
 * Flow node within a branch where a condition is known to be true or false.
 * Used for `if`/`while`/`do-while`/`for`/`?:` and short-circuit operators
 * (`&&`/`||`/`??`).
 */
class FlowCondition(
    override val id: Int,
    val isTrue: Boolean,
    val expression: Expression,
    val antecedent: FlowNode,
) : FlowNode

/**
 * Flow node within a switch case clause range where the switch expression is
 * known to equal one of the case values in [clauseStart, clauseEnd).
 */
class FlowSwitchClause(
    override val id: Int,
    val switchStatement: SwitchStatement,
    val clauseStart: Int,
    val clauseEnd: Int,
    val antecedent: FlowNode,
) : FlowNode

/**
 * Flow node after a call expression. Used by the checker for assertion
 * functions (e.g. `assert(x)` narrows `x` to non-null in code after the call).
 */
class FlowCall(
    override val id: Int,
    val node: CallExpression,
    val antecedent: FlowNode,
) : FlowNode

/**
 * Flow node after an array mutation (push/pop/shift/etc.). The checker may
 * widen the array's element type in code after the mutation.
 */
class FlowArrayMutation(
    override val id: Int,
    val node: Node,
    val antecedent: FlowNode,
) : FlowNode

/**
 * The control-flow graph for a single source file. Maps AST node keys
 * (pos|end packed Long, see [nodeKey]) to the [FlowNode] that represents the
 * flow just before that node is evaluated.
 *
 * Built by [FlowGraphBuilder] during [Binder.bind]. Stored on [BinderResult].
 */
class FlowGraph(
    val nodeToFlow: Map<Long, FlowNode>,
)

// ---------------------------------------------------------------------------
// FlowGraphBuilder — walks the AST and builds the flow graph
// ---------------------------------------------------------------------------

/**
 * Walks an AST and produces a [FlowGraph]. One instance per source file —
 * it is NOT thread-safe.
 *
 * Strategy:
 *   - Maintain a `currentFlow` mutable variable representing the flow at
 *     the walker's current position.
 *   - For each statement, update `currentFlow` to reflect the post-statement
 *     flow.
 *   - For each reference position (Identifier in expression context, etc.),
 *     record `currentFlow` in `nodeToFlow` BEFORE recursing into the
 *     reference's children (which may themselves change `currentFlow`).
 *   - For control-flow constructs (if/else, loops, try/catch, switch),
 *     create [FlowBranchLabel] / [FlowLoopLabel] join points and route
 *     branches through [FlowCondition] / [FlowSwitchClause] nodes.
 *   - For `return`/`throw`/`break`/`continue`, mark `currentFlow` as
 *     unreachable; `break`/`continue` route their pre-jump flow into the
 *     enclosing loop / switch / labeled-statement target.
 *
 * Each function-body container (FunctionDeclaration, FunctionExpression,
 * ArrowFunction, MethodDeclaration, Constructor, GetAccessor, SetAccessor,
 * ClassStaticBlockDeclaration) gets its own isolated subgraph with a fresh
 * [FlowStart].
 *
 * NOT YET CONSUMED by the checker — Phase 17 step 1 builds this
 * infrastructure with no behavior change. Step 2 will wire the graph into
 * narrowing for TS2454/TS2339/TS2774.
 */
class FlowGraphBuilder {

    private val nodeToFlow: MutableMap<Long, FlowNode> = mutableMapOf()
    private var nextId = 0

    private var currentFlow: FlowNode = FlowStart(nextId++, null)

    /**
     * Stack of break-target labels for unlabeled `break` statements.
     * Pushed when entering a loop or switch, popped when leaving.
     */
    private val breakTargetStack: ArrayDeque<FlowBranchLabel> = ArrayDeque()

    /**
     * Stack of continue-target labels for unlabeled `continue` statements.
     * Pushed when entering a loop, popped when leaving.
     */
    private val continueTargetStack: ArrayDeque<FlowLoopLabel> = ArrayDeque()

    /**
     * Map from label name to its (break, continue) target pair. Continue
     * target is null for non-loop labeled statements.
     */
    private val labeledTargets: MutableMap<String, Pair<FlowBranchLabel, FlowLoopLabel?>> = mutableMapOf()

    fun build(sourceFile: SourceFile): FlowGraph {
        currentFlow = newStart(sourceFile)
        bindEachStatement(sourceFile.statements)
        return FlowGraph(nodeToFlow)
    }

    // ---- factories -------------------------------------------------------

    private fun newStart(container: Node?): FlowStart = FlowStart(nextId++, container)
    private fun newUnreachable(): FlowUnreachable = FlowUnreachable(nextId++)
    private fun newBranchLabel(): FlowBranchLabel = FlowBranchLabel(nextId++)
    private fun newLoopLabel(): FlowLoopLabel = FlowLoopLabel(nextId++)
    private fun newAssignment(node: Node, antecedent: FlowNode): FlowAssignment =
        FlowAssignment(nextId++, node, antecedent)
    private fun newCondition(isTrue: Boolean, expr: Expression, antecedent: FlowNode): FlowCondition =
        FlowCondition(nextId++, isTrue, expr, antecedent)
    private fun newSwitchClause(
        switchStmt: SwitchStatement,
        clauseStart: Int,
        clauseEnd: Int,
        antecedent: FlowNode,
    ): FlowSwitchClause = FlowSwitchClause(nextId++, switchStmt, clauseStart, clauseEnd, antecedent)
    private fun newCall(call: CallExpression, antecedent: FlowNode): FlowCall =
        FlowCall(nextId++, call, antecedent)

    // ---- helpers ---------------------------------------------------------

    private fun isReachable(): Boolean = currentFlow !is FlowUnreachable

    private fun setUnreachable() {
        currentFlow = newUnreachable()
    }

    private fun joinAntecedent(label: FlowBranchLabel, current: FlowNode) {
        if (current !is FlowUnreachable) {
            label.antecedents.add(current)
        }
    }

    private fun joinAntecedent(label: FlowLoopLabel, current: FlowNode) {
        if (current !is FlowUnreachable) {
            label.antecedents.add(current)
        }
    }

    /**
     * Resolve a [FlowBranchLabel] to a single flow node. If it has no
     * antecedents, the branch is unreachable. If it has one, return that
     * node directly. Otherwise, return the label as-is.
     */
    private fun finishBranchLabel(label: FlowBranchLabel): FlowNode = when {
        label.antecedents.isEmpty() -> newUnreachable()
        label.antecedents.size == 1 -> label.antecedents[0]
        else -> label
    }

    /** Record the flow node at a given AST node position. */
    private fun recordFlow(node: Node) {
        // Skip synthetic / sentinel nodes (pos == -1).
        if (node.pos < 0) return
        nodeToFlow[nodeKey(node)] = currentFlow
    }

    // ---- statement bindings ---------------------------------------------

    private fun bindEachStatement(statements: List<Statement>) {
        for (stmt in statements) {
            bindStatement(stmt)
        }
    }

    private fun bindStatement(stmt: Statement) {
        when (stmt) {
            is Block -> bindEachStatement(stmt.statements)
            is VariableStatement -> bindVariableStatement(stmt)
            is ExpressionStatement -> bindExpression(stmt.expression)
            is IfStatement -> bindIfStatement(stmt)
            is DoStatement -> bindDoStatement(stmt)
            is WhileStatement -> bindWhileStatement(stmt)
            is ForStatement -> bindForStatement(stmt)
            is ForInStatement -> bindForInStatement(stmt)
            is ForOfStatement -> bindForOfStatement(stmt)
            is ReturnStatement -> bindReturnStatement(stmt)
            is ThrowStatement -> bindThrowStatement(stmt)
            is BreakStatement -> bindBreakStatement(stmt)
            is ContinueStatement -> bindContinueStatement(stmt)
            is SwitchStatement -> bindSwitchStatement(stmt)
            is TryStatement -> bindTryStatement(stmt)
            is LabeledStatement -> bindLabeledStatement(stmt)
            is WithStatement -> bindWithStatement(stmt)
            is FunctionDeclaration -> bindFunctionDeclaration(stmt)
            is ClassDeclaration -> bindClassDeclaration(stmt)
            is ModuleDeclaration -> bindModuleDeclaration(stmt)
            is ExportAssignment -> bindExpression(stmt.expression)
            // B1.3: record the current flow at the TypeAlias position so a
            // checker pass that wants to know "what's the flow context at
            // `type X = typeof a.b.c;`" can look it up via `nodeToFlow`.
            // TypeAlias bodies themselves don't change flow.
            is TypeAliasDeclaration -> recordFlow(stmt)
            // Type-only statements / no flow effect:
            is InterfaceDeclaration,
            is EnumDeclaration,
            is ImportDeclaration,
            is ImportEqualsDeclaration,
            is ExportDeclaration,
            is EmptyStatement,
            is DebuggerStatement,
            is NotEmittedStatement,
            is RawStatement -> { /* no flow change */ }
            else -> { /* fall-through; no-op */ }
        }
    }

    private fun bindVariableStatement(stmt: VariableStatement) {
        for (decl in stmt.declarationList.declarations) {
            // Walk the initializer first (its references see flow before assignment).
            decl.initializer?.let { bindExpression(it) }
            // The declaration introduces a binding — model as an assignment.
            if (isReachable()) {
                bindAssignmentTarget(decl.name, decl)
            }
        }
    }

    private fun bindIfStatement(stmt: IfStatement) {
        // Walk the condition first; references in it see the pre-if flow.
        bindCondition(stmt.expression)
        val preIf = currentFlow
        val postIf = newBranchLabel()

        // then-branch: condition assumed true
        currentFlow = newCondition(true, stmt.expression, preIf)
        bindStatement(stmt.thenStatement)
        joinAntecedent(postIf, currentFlow)

        // else-branch: condition assumed false (or skipped if no else)
        currentFlow = newCondition(false, stmt.expression, preIf)
        if (stmt.elseStatement != null) {
            bindStatement(stmt.elseStatement)
            joinAntecedent(postIf, currentFlow)
        } else {
            joinAntecedent(postIf, currentFlow)
        }

        currentFlow = finishBranchLabel(postIf)
    }

    private fun bindDoStatement(stmt: DoStatement) {
        val loopLabel = newLoopLabel()
        val postLoop = newBranchLabel()

        joinAntecedent(loopLabel, currentFlow)
        currentFlow = loopLabel

        breakTargetStack.addLast(postLoop)
        continueTargetStack.addLast(loopLabel)
        bindStatement(stmt.statement)
        continueTargetStack.removeLast()
        breakTargetStack.removeLast()

        // After the body, evaluate the condition.
        bindCondition(stmt.expression)
        val condFlow = currentFlow

        // True: back-edge to loop label.
        joinAntecedent(loopLabel, newCondition(true, stmt.expression, condFlow))
        // False: exit to postLoop.
        joinAntecedent(postLoop, newCondition(false, stmt.expression, condFlow))

        currentFlow = finishBranchLabel(postLoop)
    }

    private fun bindWhileStatement(stmt: WhileStatement) {
        val loopLabel = newLoopLabel()
        val postLoop = newBranchLabel()

        joinAntecedent(loopLabel, currentFlow)
        currentFlow = loopLabel

        // Condition is evaluated each iteration.
        bindCondition(stmt.expression)
        val condFlow = currentFlow

        // False: exit
        joinAntecedent(postLoop, newCondition(false, stmt.expression, condFlow))

        // True: enter body
        currentFlow = newCondition(true, stmt.expression, condFlow)

        breakTargetStack.addLast(postLoop)
        continueTargetStack.addLast(loopLabel)
        bindStatement(stmt.statement)
        continueTargetStack.removeLast()
        breakTargetStack.removeLast()

        // Back-edge from end-of-body to loop label
        joinAntecedent(loopLabel, currentFlow)

        currentFlow = finishBranchLabel(postLoop)
    }

    private fun bindForStatement(stmt: ForStatement) {
        // Initializer runs once before the loop.
        when (val init = stmt.initializer) {
            is VariableDeclarationList -> {
                for (decl in init.declarations) {
                    decl.initializer?.let { bindExpression(it) }
                    if (isReachable()) bindAssignmentTarget(decl.name, decl)
                }
            }
            is Expression -> bindExpression(init)
            else -> { /* no init */ }
        }

        val loopLabel = newLoopLabel()
        val postLoop = newBranchLabel()
        joinAntecedent(loopLabel, currentFlow)
        currentFlow = loopLabel

        val cond = stmt.condition
        if (cond != null) {
            bindCondition(cond)
            val condFlow = currentFlow
            joinAntecedent(postLoop, newCondition(false, cond, condFlow))
            currentFlow = newCondition(true, cond, condFlow)
        }

        breakTargetStack.addLast(postLoop)
        continueTargetStack.addLast(loopLabel)
        bindStatement(stmt.statement)
        continueTargetStack.removeLast()
        breakTargetStack.removeLast()

        stmt.incrementor?.let { bindExpression(it) }
        joinAntecedent(loopLabel, currentFlow)

        currentFlow = finishBranchLabel(postLoop)
    }

    private fun bindForInStatement(stmt: ForInStatement) {
        bindExpression(stmt.expression)

        val loopLabel = newLoopLabel()
        val postLoop = newBranchLabel()
        joinAntecedent(loopLabel, currentFlow)
        // Empty-iteration path leads directly to postLoop.
        joinAntecedent(postLoop, currentFlow)
        currentFlow = loopLabel

        // B98.r124 (Blocker #1 substep): entering the for-in body implies the
        // iterated object is non-null/undefined (a nullish value yields no
        // iterations), so narrow the iterated expression to truthy within the body.
        // FP-safe by construction — a FlowCondition only ever SUPPRESSES diagnostics
        // (removes nullish constituents), never adds one. Scoped to the body: after
        // the loop `currentFlow` is the postLoop branch label whose antecedents are
        // the pre-loop flow + breaks, where the object keeps its declared nullish type.
        currentFlow = newCondition(isTrue = true, expr = stmt.expression, antecedent = currentFlow)

        // The initializer is assigned each iteration.
        when (val init = stmt.initializer) {
            is VariableDeclarationList -> {
                for (decl in init.declarations) {
                    if (isReachable()) bindAssignmentTarget(decl.name, decl)
                }
            }
            is Expression -> bindAssignmentTarget(init, init)
            else -> { /* */ }
        }

        breakTargetStack.addLast(postLoop)
        continueTargetStack.addLast(loopLabel)
        bindStatement(stmt.statement)
        continueTargetStack.removeLast()
        breakTargetStack.removeLast()

        joinAntecedent(loopLabel, currentFlow)

        currentFlow = finishBranchLabel(postLoop)
    }

    private fun bindForOfStatement(stmt: ForOfStatement) {
        bindExpression(stmt.expression)

        val loopLabel = newLoopLabel()
        val postLoop = newBranchLabel()
        joinAntecedent(loopLabel, currentFlow)
        joinAntecedent(postLoop, currentFlow)
        currentFlow = loopLabel

        when (val init = stmt.initializer) {
            is VariableDeclarationList -> {
                for (decl in init.declarations) {
                    if (isReachable()) bindAssignmentTarget(decl.name, decl)
                }
            }
            is Expression -> bindAssignmentTarget(init, init)
            else -> { /* */ }
        }

        breakTargetStack.addLast(postLoop)
        continueTargetStack.addLast(loopLabel)
        bindStatement(stmt.statement)
        continueTargetStack.removeLast()
        breakTargetStack.removeLast()

        joinAntecedent(loopLabel, currentFlow)

        currentFlow = finishBranchLabel(postLoop)
    }

    private fun bindReturnStatement(stmt: ReturnStatement) {
        stmt.expression?.let { bindExpression(it) }
        setUnreachable()
    }

    private fun bindThrowStatement(stmt: ThrowStatement) {
        stmt.expression?.let { bindExpression(it) }
        setUnreachable()
    }

    private fun bindBreakStatement(stmt: BreakStatement) {
        val target = if (stmt.label != null) {
            labeledTargets[stmt.label.text]?.first
        } else {
            breakTargetStack.lastOrNull()
        }
        target?.let { joinAntecedent(it, currentFlow) }
        setUnreachable()
    }

    private fun bindContinueStatement(stmt: ContinueStatement) {
        val target = if (stmt.label != null) {
            labeledTargets[stmt.label.text]?.second
        } else {
            continueTargetStack.lastOrNull()
        }
        target?.let { joinAntecedent(it, currentFlow) }
        setUnreachable()
    }

    private fun bindSwitchStatement(stmt: SwitchStatement) {
        bindExpression(stmt.expression)
        val preSwitch = currentFlow
        val postSwitch = newBranchLabel()

        breakTargetStack.addLast(postSwitch)

        var hasDefault = false
        var fallthroughFlow: FlowNode? = null
        val clauses = stmt.caseBlock
        for ((i, clause) in clauses.withIndex()) {
            when (clause) {
                is CaseClause -> {
                    // A switch's case EXPRESSIONS are all evaluated at the switch head
                    // (before any body runs), so bind the expression at `preSwitch` —
                    // NOT at `currentFlow`, which after a prior clause body that ended in
                    // return/break is unreachable (`never`). Binding a discriminant read
                    // (`switch (true) { case x.kind === "b": }`) at that unreachable flow
                    // FP-emits TS2339-on-never on the case expression.
                    currentFlow = preSwitch
                    bindExpression(clause.expression)
                    // Entry flow into this clause: previous fallthrough OR
                    // a switch-clause flow predicated on the case expression
                    // matching.
                    val clauseEntry = newSwitchClause(stmt, i, i + 1, preSwitch)
                    val mergedEntry = if (fallthroughFlow != null) {
                        val merge = newBranchLabel()
                        joinAntecedent(merge, fallthroughFlow!!)
                        joinAntecedent(merge, clauseEntry)
                        finishBranchLabel(merge)
                    } else clauseEntry
                    currentFlow = mergedEntry
                    bindEachStatement(clause.statements)
                    fallthroughFlow = currentFlow
                }
                is DefaultClause -> {
                    hasDefault = true
                    val clauseEntry = newSwitchClause(stmt, i, i + 1, preSwitch)
                    val mergedEntry = if (fallthroughFlow != null) {
                        val merge = newBranchLabel()
                        joinAntecedent(merge, fallthroughFlow!!)
                        joinAntecedent(merge, clauseEntry)
                        finishBranchLabel(merge)
                    } else clauseEntry
                    currentFlow = mergedEntry
                    bindEachStatement(clause.statements)
                    fallthroughFlow = currentFlow
                }
                else -> { /* unexpected clause kind — skip */ }
            }
        }

        // End of last clause falls through to postSwitch.
        fallthroughFlow?.let { joinAntecedent(postSwitch, it) }

        // If no default clause, the switch may exit without matching anything — and
        // reaching postSwitch that way means EVERY case condition was false. Narrow the
        // no-match flow by each case expression being false (chained FlowConditions over
        // preSwitch) so post-switch narrowing reflects the un-matched scrutinee. For a
        // `switch (true) { case shape.kind === "circle": return … }` this leaves shape as
        // the non-circle members past the switch (tsc's post-switch exhaustiveness). For
        // a non-discriminant scrutinee each FlowCondition narrows nothing (harmless).
        if (!hasDefault) {
            var noMatch = preSwitch
            for (clause in clauses) {
                if (clause is CaseClause) noMatch = newCondition(false, clause.expression, noMatch)
            }
            joinAntecedent(postSwitch, noMatch)
        }

        breakTargetStack.removeLast()
        currentFlow = finishBranchLabel(postSwitch)
    }

    private fun bindTryStatement(stmt: TryStatement) {
        // Conservative: any point inside try may throw, so the catch clause's
        // antecedent is the try-entry flow. Final flow after try-catch-finally
        // is the join of the try's normal completion + catch's normal completion.
        val preTry = currentFlow
        bindStatement(stmt.tryBlock)
        val tryEnd = currentFlow

        val finallyJoin = newBranchLabel()
        joinAntecedent(finallyJoin, tryEnd)

        if (stmt.catchClause != null) {
            // Catch entry: pre-try flow (any throw point during try)
            currentFlow = preTry
            stmt.catchClause.variableDeclaration?.let { catchVar ->
                bindAssignmentTarget(catchVar.name, catchVar)
            }
            bindStatement(stmt.catchClause.block)
            joinAntecedent(finallyJoin, currentFlow)
        }

        currentFlow = finishBranchLabel(finallyJoin)

        if (stmt.finallyBlock != null) {
            bindStatement(stmt.finallyBlock)
        }
    }

    private fun bindLabeledStatement(stmt: LabeledStatement) {
        // For labeled statements, register the label so labeled break/continue
        // can target the right place. For non-loop labeled statements, the
        // continue target is null.
        val name = stmt.label.text
        val breakLabel = newBranchLabel()
        val isLoop = stmt.statement is DoStatement
                || stmt.statement is WhileStatement
                || stmt.statement is ForStatement
                || stmt.statement is ForInStatement
                || stmt.statement is ForOfStatement
        val continueLabel = if (isLoop) newLoopLabel() else null
        val previous = labeledTargets[name]
        labeledTargets[name] = breakLabel to continueLabel
        // For loops, the labeled `continue X` should hit the loop's continue
        // label. Our loop builders create their own loop labels internally —
        // we'd need a richer mechanism to share. For step 1, accept that
        // labeled `continue` to a loop falls back to unreachable join (loss
        // of precision, no incorrect narrowing).
        bindStatement(stmt.statement)
        joinAntecedent(breakLabel, currentFlow)
        currentFlow = finishBranchLabel(breakLabel)
        if (previous != null) labeledTargets[name] = previous else labeledTargets.remove(name)
    }

    private fun bindWithStatement(stmt: WithStatement) {
        bindExpression(stmt.expression)
        bindStatement(stmt.statement)
    }

    private fun bindFunctionDeclaration(decl: FunctionDeclaration) {
        // Functions are assignments (hoisted) — but the body has its own flow.
        bindFunctionLikeBody(decl, decl.parameters, decl.body)
    }

    private fun bindClassDeclaration(decl: ClassDeclaration) {
        for (member in decl.members) {
            when (member) {
                is MethodDeclaration -> bindFunctionLikeBody(member, member.parameters, member.body)
                is Constructor -> bindFunctionLikeBody(member, member.parameters, member.body)
                is GetAccessor -> bindFunctionLikeBody(member, member.parameters, member.body)
                is SetAccessor -> bindFunctionLikeBody(member, member.parameters, member.body)
                is ClassStaticBlockDeclaration -> bindFunctionLikeBody(member, emptyList(), member.body)
                is PropertyDeclaration -> {
                    // Property initializers are evaluated in a fresh flow scope
                    // (the constructor's flow) — but for static properties,
                    // they're evaluated at class declaration time. Conservative:
                    // walk the initializer in the current flow.
                    member.initializer?.let { bindExpression(it) }
                }
                else -> { /* signatures, index sigs — no flow */ }
            }
        }
    }

    private fun bindModuleDeclaration(decl: ModuleDeclaration) {
        when (val body = decl.body) {
            is ModuleBlock -> bindEachStatement(body.statements)
            is ModuleDeclaration -> bindModuleDeclaration(body)
            else -> { /* */ }
        }
    }

    /**
     * Walk a function-like body in an isolated flow subgraph. Saves and
     * restores `currentFlow` and the break/continue/label stacks.
     */
    private fun bindFunctionLikeBody(container: Node, parameters: List<Parameter>, body: Node?) {
        val savedFlow = currentFlow
        val savedBreaks = breakTargetStack.toList()
        val savedContinues = continueTargetStack.toList()
        breakTargetStack.clear()
        continueTargetStack.clear()

        currentFlow = newStart(container)

        // Parameters introduce bindings — model as assignments.
        for (param in parameters) {
            param.initializer?.let { bindExpression(it) }
            bindAssignmentTarget(param.name, param)
        }

        when (body) {
            is Block -> bindEachStatement(body.statements)
            is Expression -> bindExpression(body) // ArrowFunction with expression body
            null -> { /* overload signature, declare function — no body */ }
            else -> { /* shouldn't happen */ }
        }

        currentFlow = savedFlow
        breakTargetStack.clear(); breakTargetStack.addAll(savedBreaks)
        continueTargetStack.clear(); continueTargetStack.addAll(savedContinues)
    }

    /**
     * Bind an assignment target. For simple identifiers, creates a
     * [FlowAssignment]. For destructuring patterns, recurses into elements.
     * The `declarationNode` is what the FlowAssignment will reference (the
     * VariableDeclaration / Parameter / BindingElement / LHS expression).
     */
    private fun bindAssignmentTarget(target: Node, declarationNode: Node) {
        when (target) {
            is Identifier -> {
                if (isReachable()) {
                    currentFlow = newAssignment(declarationNode, currentFlow)
                }
            }
            // Destructuring default-value form `{a: x = 1}` or `[x = 1]` — the
            // PropertyAssignment.initializer / ArrayLiteralExpression element is
            // a BinaryExpression(=) where left is the actual target and right
            // is the default. Bind the default as a read, then recurse on the
            // target. Pure-pattern targets like `[a, b]` never reach this branch
            // (existing callers only pass BinaryExpression here when our
            // 17.46c destructuring recursion bottoms out on a default-value
            // shape inside a PropertyAssignment.initializer).
            is BinaryExpression -> if (target.operator == SyntaxKind.Equals) {
                bindExpression(target.right)
                bindAssignmentTarget(target.left, target.left)
            }
            is ObjectBindingPattern -> {
                for (element in target.elements) {
                    bindAssignmentTarget(element.name, element)
                }
            }
            is ArrayBindingPattern -> {
                for (element in target.elements) {
                    if (element is BindingElement) {
                        bindAssignmentTarget(element.name, element)
                    }
                }
            }
            is PropertyAccessExpression -> {
                bindExpression(target)
                if (isReachable()) {
                    currentFlow = newAssignment(declarationNode, currentFlow)
                }
            }
            is ElementAccessExpression -> {
                bindExpression(target)
                if (isReachable()) {
                    currentFlow = newAssignment(declarationNode, currentFlow)
                }
            }
            // 17.46c: array/object literal as assignment target. Used for destructuring
            // assignment (`[, x] = arr` / `({a, b} = obj)`) — distinct from
            // Array/ObjectBindingPattern which appear only in declaration positions.
            // Without this, the LHS falls into the else branch and no FlowAssignment
            // is registered for the binding, so the future top-level TS2454 walker
            // would FP-emit on subsequent reads.
            //
            // For each leaf, we recurse with the leaf's underlying target as BOTH
            // arguments — i.e. the `declarationNode` we ultimately attach to the
            // FlowAssignment is the leaf Identifier (or nested destructuring root),
            // not a wrapper like SpreadElement / PropertyAssignment /
            // ShorthandPropertyAssignment / SpreadAssignment. The walker's
            // `flowAssignmentTargetsName` only recognizes a small set of node
            // kinds (Identifier / VariableDeclaration / Parameter / BindingElement /
            // BinaryExpression); routing wrappers through it would silently miss
            // every destructuring assignment.
            is ArrayLiteralExpression -> {
                for (element in target.elements) {
                    when (element) {
                        is OmittedExpression -> { /* `[, x]` — skip elision */ }
                        is SpreadElement -> bindAssignmentTarget(element.expression, element.expression)
                        is BinaryExpression -> if (element.operator == SyntaxKind.Equals) {
                            // `[a = 1, ...]` — default value reads (RHS), then `a` is the target
                            bindExpression(element.right)
                            bindAssignmentTarget(element.left, element)
                        } else {
                            bindAssignmentTarget(element, element)
                        }
                        else -> bindAssignmentTarget(element, element)
                    }
                }
            }
            is ObjectLiteralExpression -> {
                for (prop in target.properties) {
                    when (prop) {
                        is PropertyAssignment -> bindAssignmentTarget(prop.initializer, prop.initializer)
                        is ShorthandPropertyAssignment -> {
                            prop.objectAssignmentInitializer?.let { bindExpression(it) }
                            bindAssignmentTarget(prop.name, prop.name)
                        }
                        is SpreadAssignment -> bindAssignmentTarget(prop.expression, prop.expression)
                        else -> { /* computed names, methods, accessors — not assignment targets */ }
                    }
                }
            }
            else -> { /* computed / complex — skip */ }
        }
    }

    // ---- expression bindings --------------------------------------------

    /**
     * Walk an expression that's used in a condition position (if/while/for
     * cond, etc.). For step 1 this is the same as [bindExpression] — proper
     * predicate handling (short-circuit `&&`/`||`/`??`) lives inside
     * [bindExpression] for binary operators.
     */
    private fun bindCondition(expr: Expression) {
        bindExpression(expr)
    }

    private fun bindExpression(expr: Expression) {
        when (expr) {
            is Identifier -> recordFlow(expr)
            is StringLiteralNode,
            is NumericLiteralNode,
            is BigIntLiteralNode,
            is RegularExpressionLiteralNode,
            is NoSubstitutionTemplateLiteralNode -> { /* literal — no flow change */ }
            is TemplateExpression -> {
                for (span in expr.templateSpans) {
                    bindExpression(span.expression)
                }
            }
            is ParenthesizedExpression -> bindExpression(expr.expression)
            is PropertyAccessExpression -> {
                bindExpression(expr.expression)
                recordFlow(expr)
            }
            is ElementAccessExpression -> {
                bindExpression(expr.expression)
                bindExpression(expr.argumentExpression)
                recordFlow(expr)
            }
            is CallExpression -> {
                bindExpression(expr.expression)
                for (arg in expr.arguments) bindExpression(arg)
                // Mark a FlowCall for potential assertion-function narrowing.
                if (isReachable()) {
                    currentFlow = newCall(expr, currentFlow)
                }
            }
            is NewExpression -> {
                bindExpression(expr.expression)
                expr.arguments?.let { for (arg in it) bindExpression(arg) }
            }
            is TaggedTemplateExpression -> {
                bindExpression(expr.tag)
                (expr.template as? Expression)?.let { bindExpression(it) }
            }
            is TypeAssertionExpression -> bindExpression(expr.expression)
            is AsExpression -> bindExpression(expr.expression)
            is SatisfiesExpression -> bindExpression(expr.expression)
            is NonNullExpression -> bindExpression(expr.expression)
            is FunctionExpression -> bindFunctionLikeBody(expr, expr.parameters, expr.body)
            is ArrowFunction -> bindFunctionLikeBody(expr, expr.parameters, expr.body)
            is ClassExpression -> {
                // Class expression body: bind methods like ClassDeclaration.
                for (member in expr.members) {
                    when (member) {
                        is MethodDeclaration -> bindFunctionLikeBody(member, member.parameters, member.body)
                        is Constructor -> bindFunctionLikeBody(member, member.parameters, member.body)
                        is GetAccessor -> bindFunctionLikeBody(member, member.parameters, member.body)
                        is SetAccessor -> bindFunctionLikeBody(member, member.parameters, member.body)
                        is ClassStaticBlockDeclaration -> bindFunctionLikeBody(member, emptyList(), member.body)
                        is PropertyDeclaration -> member.initializer?.let { bindExpression(it) }
                        else -> { /* */ }
                    }
                }
            }
            is DeleteExpression -> bindExpression(expr.expression)
            is TypeOfExpression -> bindExpression(expr.expression)
            is VoidExpression -> bindExpression(expr.expression)
            is AwaitExpression -> bindExpression(expr.expression)
            is YieldExpression -> expr.expression?.let { bindExpression(it) }
            is PrefixUnaryExpression -> {
                bindExpression(expr.operand)
                // ++/--/! at the prefix position — for ++ and --, the operand
                // is reassigned. Conservative: model as assignment if the
                // operand is a simple identifier.
                if (expr.operator == SyntaxKind.PlusPlus || expr.operator == SyntaxKind.MinusMinus) {
                    if (expr.operand is Identifier && isReachable()) {
                        currentFlow = newAssignment(expr.operand, currentFlow)
                    }
                }
            }
            is PostfixUnaryExpression -> {
                bindExpression(expr.operand)
                if (expr.operand is Identifier && isReachable()) {
                    currentFlow = newAssignment(expr.operand, currentFlow)
                }
            }
            is BinaryExpression -> bindBinaryExpression(expr)
            is ConditionalExpression -> bindConditionalExpression(expr)
            is ArrayLiteralExpression -> {
                for (el in expr.elements) bindExpression(el)
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is PropertyAssignment -> bindExpression(prop.initializer)
                        is ShorthandPropertyAssignment -> {
                            recordFlow(prop.name)
                            prop.objectAssignmentInitializer?.let { bindExpression(it) }
                        }
                        is SpreadAssignment -> bindExpression(prop.expression)
                        is MethodDeclaration -> bindFunctionLikeBody(prop, prop.parameters, prop.body)
                        is GetAccessor -> bindFunctionLikeBody(prop, prop.parameters, prop.body)
                        is SetAccessor -> bindFunctionLikeBody(prop, prop.parameters, prop.body)
                        else -> { /* computed names etc. */ }
                    }
                }
            }
            is SpreadElement -> bindExpression(expr.expression)
            is CommaListExpression -> {
                for (e in expr.elements) bindExpression(e)
            }
            is OmittedExpression -> { /* */ }
            is MetaProperty -> { /* new.target / import.meta — no flow */ }
            else -> { /* unhandled — leave currentFlow unchanged */ }
        }
    }

    private fun bindBinaryExpression(expr: BinaryExpression) {
        // Iteratively flatten the left-spine for "no flow change" operators
        // (arithmetic / comparison / bitwise / instanceof / in / comma) to avoid
        // StackOverflow on deeply nested left-associative chains like
        // `0 + 1 + 2 + ... + 1499`. Same semantic effect as recursing through
        // bindExpression(left) + bindExpression(right). Stops at any operator
        // that affects flow (&&, ||, ??, =, +=, etc.) and falls into the
        // recursive handler below for that node.
        if (isSimpleBinaryOp(expr.operator) && expr.left is BinaryExpression) {
            val rightStack = ArrayDeque<Expression>()
            var node: Expression = expr
            while (node is BinaryExpression && isSimpleBinaryOp(node.operator)) {
                rightStack.addLast(node.right)
                node = node.left
            }
            bindExpression(node)
            while (rightStack.isNotEmpty()) bindExpression(rightStack.removeLast())
            return
        }
        when (expr.operator) {
            // Short-circuit operators: && / || / ??
            SyntaxKind.AmpersandAmpersand -> {
                bindCondition(expr.left)
                val preRight = currentFlow
                val postExpr = newBranchLabel()
                joinAntecedent(postExpr, newCondition(false, expr.left, preRight))
                currentFlow = newCondition(true, expr.left, preRight)
                bindExpression(expr.right)
                joinAntecedent(postExpr, currentFlow)
                currentFlow = finishBranchLabel(postExpr)
            }
            SyntaxKind.BarBar -> {
                bindCondition(expr.left)
                val preRight = currentFlow
                val postExpr = newBranchLabel()
                joinAntecedent(postExpr, newCondition(true, expr.left, preRight))
                currentFlow = newCondition(false, expr.left, preRight)
                bindExpression(expr.right)
                joinAntecedent(postExpr, currentFlow)
                currentFlow = finishBranchLabel(postExpr)
            }
            SyntaxKind.QuestionQuestion -> {
                bindExpression(expr.left)
                val preRight = currentFlow
                val postExpr = newBranchLabel()
                joinAntecedent(postExpr, preRight)
                bindExpression(expr.right)
                joinAntecedent(postExpr, currentFlow)
                currentFlow = finishBranchLabel(postExpr)
            }
            // Assignment-flavored operators
            SyntaxKind.Equals -> {
                bindExpression(expr.right)
                bindAssignmentTarget(expr.left, expr)
            }
            SyntaxKind.PlusEquals,
            SyntaxKind.MinusEquals,
            SyntaxKind.AsteriskEquals,
            SyntaxKind.AsteriskAsteriskEquals,
            SyntaxKind.SlashEquals,
            SyntaxKind.PercentEquals,
            SyntaxKind.LessThanLessThanEquals,
            SyntaxKind.GreaterThanGreaterThanEquals,
            SyntaxKind.GreaterThanGreaterThanGreaterThanEquals,
            SyntaxKind.AmpersandEquals,
            SyntaxKind.BarEquals,
            SyntaxKind.CaretEquals,
            SyntaxKind.AmpersandAmpersandEquals,
            SyntaxKind.BarBarEquals,
            SyntaxKind.QuestionQuestionEquals -> {
                bindExpression(expr.left)
                bindExpression(expr.right)
                if (expr.left is Identifier && isReachable()) {
                    currentFlow = newAssignment(expr, currentFlow)
                }
            }
            // Comma: left first then right; result type is right.
            SyntaxKind.Comma -> {
                bindExpression(expr.left)
                bindExpression(expr.right)
            }
            // All other binary operators: left then right; no flow change.
            else -> {
                bindExpression(expr.left)
                bindExpression(expr.right)
            }
        }
    }

    /** True for binary operators with no flow-graph effect — safe to flatten
     *  the left-spine iteratively in [bindBinaryExpression]. Excludes
     *  short-circuit ops (&&, ||, ??), Equals, compound assignments, and the
     *  short-circuit-assignment forms (&&=, ||=, ??=). Comma is included since
     *  it has no flow effect (just left-then-right binding). */
    private fun isSimpleBinaryOp(op: SyntaxKind): Boolean = when (op) {
        SyntaxKind.AmpersandAmpersand,
        SyntaxKind.BarBar,
        SyntaxKind.QuestionQuestion,
        SyntaxKind.Equals,
        SyntaxKind.PlusEquals,
        SyntaxKind.MinusEquals,
        SyntaxKind.AsteriskEquals,
        SyntaxKind.AsteriskAsteriskEquals,
        SyntaxKind.SlashEquals,
        SyntaxKind.PercentEquals,
        SyntaxKind.LessThanLessThanEquals,
        SyntaxKind.GreaterThanGreaterThanEquals,
        SyntaxKind.GreaterThanGreaterThanGreaterThanEquals,
        SyntaxKind.AmpersandEquals,
        SyntaxKind.BarEquals,
        SyntaxKind.CaretEquals,
        SyntaxKind.AmpersandAmpersandEquals,
        SyntaxKind.BarBarEquals,
        SyntaxKind.QuestionQuestionEquals -> false
        else -> true
    }

    private fun bindConditionalExpression(expr: ConditionalExpression) {
        bindCondition(expr.condition)
        val preBranch = currentFlow
        val postExpr = newBranchLabel()

        currentFlow = newCondition(true, expr.condition, preBranch)
        bindExpression(expr.whenTrue)
        joinAntecedent(postExpr, currentFlow)

        currentFlow = newCondition(false, expr.condition, preBranch)
        bindExpression(expr.whenFalse)
        joinAntecedent(postExpr, currentFlow)

        currentFlow = finishBranchLabel(postExpr)
    }
}
