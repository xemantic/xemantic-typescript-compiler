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
 * (INV.0) (P18.312) — the unconstrained-type-parameter `in` / `Object.keys` / `= undefined` family (TS2322 / TS2208 / TS2769): the pass `checkInRhsPrimitiveTypeParams`
 * with its private walkers and helpers. Extracted VERBATIM from `Checker.kt` (one span: 180160-180650);
 * every Checker member it reads is reached through [checker]. No walk-scoped or spine ambient is read.
 * Ambient reads: `docs/inversion-ambient-ledger.md` row 27.
 */
internal class InRhsPrimitiveTypeParamChecks(
    private val checker: Checker,
) {

    fun checkInRhsPrimitiveTypeParams() {
        val primOrder = listOf(
            SyntaxKind.StringKeyword, SyntaxKind.NumberKeyword, SyntaxKind.BigIntKeyword,
            SyntaxKind.BooleanKeyword, SyntaxKind.SymbolKeyword, SyntaxKind.ObjectKeyword,
        )
        val primName = mapOf(
            SyntaxKind.StringKeyword to "string", SyntaxKind.NumberKeyword to "number",
            SyntaxKind.BigIntKeyword to "bigint", SyntaxKind.BooleanKeyword to "boolean",
            SyntaxKind.SymbolKeyword to "symbol", SyntaxKind.ObjectKeyword to "object",
        )
        // member of a union/constraint: prim keyword | literal | TP ref
        class InMember(
            val kind: Int,            // 0=prim keyword, 1=literal, 2=TP
            val display: String,
            val widened: String,      // literal -> widened primitive name; else == display
            val primIdx: Int,         // for kind 0: index in primOrder
            val tpName: String = "",
            val tpConstraint: List<InMember>? = null,  // null = unconstrained (kind 2 only)
            val tpDeclPos: Int = -1,
        )
        // (LEGACY.0b step 9) tsc's stable union order (`StableTypeOrdering`) is ascending
        // TypeScript-7 `TypeFlags`, and the pre-existing kind-buckets here are NOT that
        // order: `object` is `NonPrimitive` (bit 17) and sorts AFTER a string literal
        // (bit 10), where a keyword bucket put every keyword first
        // (`inDoesNotOperateOnPrimitiveTypes`: tsgo renders `"hello" | object`).
        // primOrder is String, Number, BigInt, Boolean, Symbol, Object.
        val primNewBit = intArrayOf(5, 6, 7, 8, 9, 17)
        fun memberRank(m: InMember): Int = when (m.kind) {
            0 -> primNewBit[m.primIdx]
            1 -> if (m.widened == "string") 10 else 11   // StringLiteral / NumberLiteral
            else -> 19                                   // TypeParameter
        }
        /** Within one rank: a literal by VALUE, a type parameter by NAME — tsc's
         *  `compareTypes` per-kind data and `compareTypeNames` respectively. */
        fun memberTie(m: InMember): String = when (m.kind) {
            1 -> m.display.removeSurrounding("\"")
            2 -> m.tpName
            else -> ""
        }
        fun sortMembers(ms: List<InMember>): List<InMember> =
            ms.withIndex().sortedWith(
                compareBy({ memberRank(it.value) }, { memberTie(it.value) }, { it.index }),
            ).map { it.value }
        fun memberFails(m: InMember): Boolean = when (m.kind) {
            0 -> m.primIdx < 5  // object keyword passes
            1 -> true
            else -> m.tpConstraint == null || m.tpConstraint.any { memberFails(it) }
        }

        fun parseMember(t: TypeNode, tps: Map<String, TypeParameter>, allowTp: Boolean): InMember? {
            when (t) {
                is KeywordTypeNode -> {
                    val idx = primOrder.indexOf(t.kind)
                    if (idx < 0) return null
                    return InMember(0, primName[t.kind]!!, primName[t.kind]!!, idx)
                }
                is LiteralType -> when (val lit = t.literal) {
                    is StringLiteralNode -> return InMember(1, "\"${lit.text}\"", "string", -1)
                    is NumericLiteralNode -> return InMember(1, lit.text, "number", -1)
                    else -> return null
                }
                is TypeReference -> {
                    if (!allowTp) return null
                    if (t.typeArguments != null) return null
                    val name = (t.typeName as? Identifier)?.text ?: return null
                    val tp = tps[name] ?: return null
                    val consMembers: List<InMember>? = tp.constraint?.let { c ->
                        val parts = if (c is UnionType) c.types else listOf(c)
                        val parsed = parts.map { parseMember(it, tps, allowTp = false) ?: return null }
                        parsed
                    }
                    return InMember(2, name, name, -1, name, consMembers, tp.name.pos)
                }
                else -> return null
            }
        }

        fun emit(
            operand: Identifier, mainDisplay: String, chain: List<String>,
            ts2208Pos: Int, source: String, fileName: String,
        ) {
            val related = mutableListOf<Diagnostic>()
            if (ts2208Pos >= 0) {
                val (dl, dc) = checker.getLineAndCharacterOfPosition(source, ts2208Pos)
                related.add(Diagnostic(
                    message = "This type parameter might need an `extends object` constraint.",
                    category = DiagnosticCategory.Message, code = 2208,
                    fileName = fileName, line = dl, character = dc,
                    start = ts2208Pos, length = 1,
                ))
            }
            val (line, ch) = checker.getLineAndCharacterOfPosition(source, operand.pos)
            checker.diagnostics.add(Diagnostic(
                message = "Type '$mainDisplay' is not assignable to type 'object'.",
                category = DiagnosticCategory.Error, code = 2322,
                fileName = fileName, line = line, character = ch,
                start = operand.pos, length = operand.text.length,
                messageChain = chain,
                relatedInformation = related,
            ))
        }

        // evaluate + emit for a UNION/SINGLE operand given its (possibly narrowed) members
        fun checkUnionOperand(operand: Identifier, members: List<InMember>, source: String, fileName: String) {
            if (members.isEmpty()) return
            val sorted = sortMembers(members)
            if (sorted.none { memberFails(it) }) return
            if (sorted.size == 1) {
                val m = sorted[0]
                when {
                    m.kind == 2 && m.tpConstraint == null ->
                        emit(operand, m.display, emptyList(), m.tpDeclPos, source, fileName)
                    m.kind == 2 -> {
                        val consSorted = sortMembers(m.tpConstraint!!)
                        val firstFail = consSorted.firstOrNull { memberFails(it) } ?: return
                        emit(operand, m.display, listOf(
                            "  Type '${consSorted.joinToString(" | ") { it.display }}' is not assignable to type 'object'.",
                            "    Type '${firstFail.widened}' is not assignable to type 'object'.",
                        ), -1, source, fileName)
                    }
                    else -> emit(operand, m.display, emptyList(), -1, source, fileName)
                }
                return
            }
            val mainDisplay = sorted.joinToString(" | ") { it.display }
            val firstFail = sorted.first { memberFails(it) }
            val chain = mutableListOf<String>()
            when {
                firstFail.kind == 2 && firstFail.tpConstraint != null -> {
                    val consSorted = sortMembers(firstFail.tpConstraint)
                    val consFail = consSorted.firstOrNull { memberFails(it) } ?: return
                    chain.add("  Type '${firstFail.display}' is not assignable to type 'object'.")
                    chain.add("    Type '${consSorted.joinToString(" | ") { it.display }}' is not assignable to type 'object'.")
                    chain.add("      Type '${consFail.widened}' is not assignable to type 'object'.")
                }
                else -> chain.add("  Type '${firstFail.widened}' is not assignable to type 'object'.")
            }
            emit(operand, mainDisplay, chain, -1, source, fileName)
        }

        // operand annotation shape
        class OperandShape(
            val unionMembers: List<InMember>?,        // union/single shape
            val intersection: List<Pair<String, List<InMember>?>>?,  // (renderedPart, unionLits-or-null)
            val intersectionFails: Boolean,
        )

        fun parseOperand(t: TypeNode, tps: Map<String, TypeParameter>): OperandShape? {
            if (t is IntersectionType) {
                val parts = mutableListOf<Pair<String, List<InMember>?>>()
                var fails = false
                for (p in t.types) {
                    if (p is ParenthesizedType && p.type is UnionType) {
                        val lits = (p.type).types.map {
                            parseMember(it, tps, allowTp = false) ?: return null
                        }
                        if (lits.any { it.kind != 1 && !(it.kind == 0 && it.primIdx < 5) }) return null
                        parts.add("(${lits.joinToString(" | ") { it.display }})" to lits)
                        fails = true
                    } else {
                        val m = parseMember(p, tps, allowTp = true) ?: return null
                        if (memberFails(m)) fails = true
                        parts.add(m.display to null)
                    }
                }
                return OperandShape(null, parts, fails)
            }
            val parts = if (t is UnionType) t.types else listOf(t)
            val members = parts.map { parseMember(it, tps, allowTp = true) ?: return null }
            return OperandShape(members, null, false)
        }

        fun checkOperandUse(
            operand: Identifier, shape: OperandShape, excluded: Set<String>,
            source: String, fileName: String,
        ) {
            if (shape.intersection != null) {
                if (!shape.intersectionFails) return
                val mainDisplay = shape.intersection.joinToString(" & ") { it.first }
                val unionPart = shape.intersection.firstOrNull { it.second != null }
                val chain = if (unionPart != null) {
                    val tpParts = shape.intersection.filter { it.second == null }.map { it.first }
                    val firstLit = unionPart.second!!.first()
                    listOf("  Type '${(tpParts + firstLit.display).joinToString(" & ")}' is not assignable to type 'object'.")
                } else emptyList()
                emit(operand, mainDisplay, chain, -1, source, fileName)
                return
            }
            val members = shape.unionMembers!!.filter {
                !(it.kind == 0 && it.display in excluded)
            }
            checkUnionOperand(operand, members, source, fileName)
        }

        // --- narrowing-aware statement walk ---
        fun condNames(e: Expression, acc: MutableSet<String>) {
            when (e) {
                is Identifier -> acc.add(e.text)
                is BinaryExpression -> {
                    var cur: Expression = e
                    while (cur is BinaryExpression) { condNames(cur.right, acc); cur = cur.left }
                    condNames(cur, acc)
                }
                is PrefixUnaryExpression -> condNames(e.operand, acc)
                is ParenthesizedExpression -> condNames(e.expression, acc)
                is TypeOfExpression -> condNames(e.expression, acc)
                is CallExpression -> { condNames(e.expression, acc); e.arguments.forEach { condNames(it, acc) } }
                is PropertyAccessExpression -> condNames(e.expression, acc)
                is ElementAccessExpression -> { condNames(e.expression, acc); condNames(e.argumentExpression, acc) }
                else -> {}
            }
        }
        // `typeof X === "object"` -> X ; null otherwise
        fun typeofObjectGuard(e: Expression): String? {
            val b = e as? BinaryExpression ?: return null
            if (b.operator != SyntaxKind.EqualsEqualsEquals && b.operator != SyntaxKind.EqualsEquals) return null
            val to = b.left as? TypeOfExpression ?: return null
            val name = (to.expression as? Identifier)?.text ?: return null
            if ((b.right as? StringLiteralNode)?.text != "object") return null
            return name
        }
        // conjunction of `typeof X !== "<prim>"` over the SAME X -> (X, prims); null otherwise
        fun typeofNotPrimGuard(e: Expression): Pair<String, Set<String>>? {
            val terms = mutableListOf<Expression>()
            var cur: Expression = e
            while (cur is BinaryExpression && cur.operator == SyntaxKind.AmpersandAmpersand) {
                terms.add(cur.right); cur = cur.left
            }
            terms.add(cur)
            var name: String? = null
            val prims = mutableSetOf<String>()
            for (t in terms) {
                val b = t as? BinaryExpression ?: return null
                if (b.operator != SyntaxKind.ExclamationEqualsEquals && b.operator != SyntaxKind.ExclamationEquals) return null
                val to = b.left as? TypeOfExpression ?: return null
                val n = (to.expression as? Identifier)?.text ?: return null
                if (name == null) name = n else if (name != n) return null
                val p = (b.right as? StringLiteralNode)?.text ?: return null
                if (p !in setOf("string", "number", "bigint", "boolean", "symbol")) return null
                prims.add(p)
            }
            return name!! to prims
        }

        fun walkStmts(
            stmts: List<Statement>, shapes: Map<String, OperandShape>,
            suppressed: Set<String>, excluded: Map<String, Set<String>>,
            source: String, fileName: String,
        ) {
            fun checkExpr(e: Expression) {
                val b = e as? BinaryExpression ?: return
                if (b.operator != SyntaxKind.InKeyword) return
                val rhs = b.right as? Identifier ?: return
                if (rhs.text in suppressed) return
                val shape = shapes[rhs.text] ?: return
                checkOperandUse(rhs, shape, excluded[rhs.text] ?: emptySet(), source, fileName)
            }
            for (s in stmts) {
                when (s) {
                    is ExpressionStatement -> checkExpr(s.expression)
                    is ReturnStatement -> s.expression?.let { checkExpr(it) }
                    is Block -> walkStmts(s.statements, shapes, suppressed, excluded, source, fileName)
                    is IfStatement -> {
                        val objGuard = typeofObjectGuard(s.expression)
                        val notPrim = if (objGuard == null) typeofNotPrimGuard(s.expression) else null
                        when {
                            objGuard != null -> {
                                walkStmts(listOf(s.thenStatement), shapes, suppressed + objGuard, excluded, source, fileName)
                                s.elseStatement?.let { walkStmts(listOf(it), shapes, suppressed, excluded, source, fileName) }
                            }
                            notPrim != null -> {
                                val newExcluded = excluded + (notPrim.first to
                                    ((excluded[notPrim.first] ?: emptySet()) + notPrim.second))
                                walkStmts(listOf(s.thenStatement), shapes, suppressed, newExcluded, source, fileName)
                                s.elseStatement?.let { walkStmts(listOf(it), shapes, suppressed, excluded, source, fileName) }
                            }
                            else -> {
                                // unknown guard mentioning a candidate -> suppress it everywhere inside
                                val names = mutableSetOf<String>()
                                condNames(s.expression, names)
                                val sup = suppressed + names.filter { it in shapes }
                                walkStmts(listOf(s.thenStatement), shapes, sup, excluded, source, fileName)
                                s.elseStatement?.let { walkStmts(listOf(it), shapes, sup, excluded, source, fileName) }
                            }
                        }
                    }
                    is WhileStatement -> {
                        val names = mutableSetOf<String>()
                        condNames(s.expression, names)
                        walkStmts(listOf(s.statement), shapes, suppressed + names.filter { it in shapes }, excluded, source, fileName)
                    }
                    is ForStatement -> walkStmts(listOf(s.statement), shapes, suppressed, excluded, source, fileName)
                    is SwitchStatement -> for (clause in s.caseBlock) when (clause) {
                        is CaseClause -> walkStmts(clause.statements, shapes, suppressed, excluded, source, fileName)
                        is DefaultClause -> walkStmts(clause.statements, shapes, suppressed, excluded, source, fileName)
                        else -> {}
                    }
                    else -> {}
                }
            }
        }

        // --- B539: expression-body extension (in-RHS inside ternary/&&/||, `: T = undefined`
        //     defaults, and Object.keys(<unconstrained-TP>)) for arrow functions with an
        //     EXPRESSION body. The Block-bodied FunctionDeclaration path above is unchanged.
        //     All three fire ONLY for an UNCONSTRAINED type parameter of the enclosing
        //     generic function -> FP-safe by construction (tsc errors on the exact shapes,
        //     so no passing test can carry them un-errored). ---
        // operands GUARANTEED narrowed-to-object when `cond` is true (positive &&-chain of `in`)
        fun guaranteedIn(cond: Expression): Set<String> = when (cond) {
            is BinaryExpression -> when (cond.operator) {
                SyntaxKind.InKeyword -> (cond.right as? Identifier)?.text?.let { setOf(it) } ?: emptySet()
                SyntaxKind.AmpersandAmpersand -> guaranteedIn(cond.left) + guaranteedIn(cond.right)
                else -> emptySet()
            }
            is ParenthesizedExpression -> guaranteedIn(cond.expression)
            else -> emptySet()
        }
        fun unconstrainedTpNames(tps: List<TypeParameter>?): Set<String> =
            tps?.filter { it.constraint == null }?.map { it.name.text }?.toSet() ?: emptySet()
        // Part 2: `param: <own unconstrained TP> = undefined` -> TS2322 + could-be-instantiated
        fun checkFnParamsUndefined(tps: List<TypeParameter>?, params: List<Parameter>, source: String, fileName: String) {
            val u = unconstrainedTpNames(tps)
            if (u.isEmpty()) return
            for (p in params) {
                val ref = p.type as? TypeReference ?: continue
                if (ref.typeArguments != null) continue
                val tpName = (ref.typeName as? Identifier)?.text ?: continue
                if (tpName !in u) continue
                val init = p.initializer ?: continue
                if ((init as? Identifier)?.text != "undefined") continue
                val len = (init.pos + init.text.length) - p.pos
                if (len <= 0) continue
                val (line, ch) = checker.getLineAndCharacterOfPosition(source, p.pos)
                checker.diagnostics.add(Diagnostic(
                    message = "Type 'undefined' is not assignable to type '$tpName'.",
                    category = DiagnosticCategory.Error, code = 2322,
                    fileName = fileName, line = line, character = ch,
                    start = p.pos, length = len,
                    messageChain = listOf("  '$tpName' could be instantiated with an arbitrary type which could be unrelated to 'undefined'."),
                ))
            }
        }
        // Part 3: Object.keys(<single unconstrained TP operand>) -> hardcoded 2-overload TS2769
        fun singleUnconstrainedTp(shape: OperandShape): InMember? {
            val ms = shape.unionMembers ?: return null
            if (ms.size != 1) return null
            val m = ms[0]
            return if (m.kind == 2 && m.tpConstraint == null) m else null
        }
        fun emitObjectKeysTs2769(arg: Identifier, m: InMember, source: String, fileName: String) {
            val (line, ch) = checker.getLineAndCharacterOfPosition(source, arg.pos)
            // (LEGACY.0b) F3: TypeScript 7 reports the LAST failing overload of `Object.keys`
            // only — so the `{}` candidate's chain entry AND its `extends {}` TS2208 are both
            // gone — plus TS2771 at the declaring lib file.
            val related = mutableListOf<Diagnostic>()
            if (m.tpDeclPos >= 0) {
                val (dl, dc) = checker.getLineAndCharacterOfPosition(source, m.tpDeclPos)
                related.add(Diagnostic(
                    message = "This type parameter might need an `extends object` constraint.",
                    category = DiagnosticCategory.Message, code = 2208,
                    fileName = fileName, line = dl, character = dc,
                    start = m.tpDeclPos, length = 1,
                ))
            }
            related.add(checker.pinRel(source, "lib.es5.d.ts", null, null, 2771, "The last overload is declared here."))
            checker.diagnostics.add(Diagnostic(
                message = "No overload matches this call.",
                category = DiagnosticCategory.Error, code = 2769,
                fileName = fileName, line = line, character = ch,
                start = arg.pos, length = arg.text.length,
                messageChain = listOf(
                    "  The last overload gave the following error.",
                    "    Argument of type '${m.tpName}' is not assignable to parameter of type 'object'.",
                ),
                relatedInformation = related,
            ))
        }
        // Part 1+3: recursive expression walk (in-RHS with &&/||/ternary narrowing, Object.keys,
        //   plus Part-2 checks on every nested function-like reached).
        fun walkExpr(e: Expression?, shapes: Map<String, OperandShape>, narrowed: Set<String>, source: String, fileName: String) {
            when (e) {
                null -> {}
                is BinaryExpression -> when (e.operator) {
                    SyntaxKind.InKeyword -> {
                        val rhs = e.right as? Identifier
                        val shape = rhs?.let { shapes[it.text] }
                        if (rhs != null && shape != null && rhs.text !in narrowed)
                            checkOperandUse(rhs, shape, emptySet(), source, fileName)
                        walkExpr(e.left, shapes, narrowed, source, fileName)
                    }
                    SyntaxKind.AmpersandAmpersand -> {
                        walkExpr(e.left, shapes, narrowed, source, fileName)
                        walkExpr(e.right, shapes, narrowed + guaranteedIn(e.left), source, fileName)
                    }
                    else -> {
                        walkExpr(e.left, shapes, narrowed, source, fileName)
                        walkExpr(e.right, shapes, narrowed, source, fileName)
                    }
                }
                is PrefixUnaryExpression -> walkExpr(e.operand, shapes, narrowed, source, fileName)
                is ParenthesizedExpression -> walkExpr(e.expression, shapes, narrowed, source, fileName)
                is ConditionalExpression -> {
                    walkExpr(e.condition, shapes, narrowed, source, fileName)
                    val cn = narrowed + guaranteedIn(e.condition)
                    walkExpr(e.whenTrue, shapes, cn, source, fileName)
                    walkExpr(e.whenFalse, shapes, narrowed, source, fileName)
                }
                is ObjectLiteralExpression -> for (prop in e.properties) when (prop) {
                    is PropertyAssignment -> walkExpr(prop.initializer, shapes, narrowed, source, fileName)
                    is SpreadAssignment -> walkExpr(prop.expression, shapes, narrowed, source, fileName)
                    else -> {}
                }
                is ArrowFunction -> {
                    checkFnParamsUndefined(e.typeParameters, e.parameters, source, fileName)
                    val shadow = narrowed + e.parameters.mapNotNull { (it.name as? Identifier)?.text }.filter { it in shapes }
                    (e.body as? Expression)?.let { walkExpr(it, shapes, shadow, source, fileName) }
                }
                is FunctionExpression -> checkFnParamsUndefined(e.typeParameters, e.parameters, source, fileName)
                is CallExpression -> {
                    val callee = e.expression
                    if (callee is PropertyAccessExpression && callee.name.text == "keys" &&
                        (callee.expression as? Identifier)?.text == "Object") {
                        val arg = e.arguments.firstOrNull() as? Identifier
                        val sh = arg?.let { shapes[it.text] }
                        if (arg != null && sh != null && arg.text !in narrowed) {
                            val tpm = singleUnconstrainedTp(sh)
                            if (tpm != null) emitObjectKeysTs2769(arg, tpm, source, fileName)
                        }
                    }
                    walkExpr(e.expression, shapes, narrowed, source, fileName)
                    e.arguments.forEach { walkExpr(it, shapes, narrowed, source, fileName) }
                }
                is NewExpression -> {
                    walkExpr(e.expression, shapes, narrowed, source, fileName)
                    e.arguments?.forEach { walkExpr(it, shapes, narrowed, source, fileName) }
                }
                is AsExpression -> walkExpr(e.expression, shapes, narrowed, source, fileName)
                is NonNullExpression -> walkExpr(e.expression, shapes, narrowed, source, fileName)
                is PropertyAccessExpression -> walkExpr(e.expression, shapes, narrowed, source, fileName)
                is ElementAccessExpression -> {
                    walkExpr(e.expression, shapes, narrowed, source, fileName)
                    walkExpr(e.argumentExpression, shapes, narrowed, source, fileName)
                }
                else -> {}
            }
        }

        fun checkFn(
            typeParameters: List<TypeParameter>?, parameters: List<Parameter>,
            bodyNode: Node?, source: String, fileName: String,
        ) {
            if (typeParameters.isNullOrEmpty() || bodyNode == null) return
            val tps = typeParameters.associateBy { it.name.text }
            val shapes = mutableMapOf<String, OperandShape>()
            for (p in parameters) {
                val n = (p.name as? Identifier)?.text ?: continue
                val t = p.type ?: continue
                // only track annotations that actually involve an in-scope TP
                val shape = parseOperand(t, tps) ?: continue
                val involvesTp = (shape.unionMembers?.any { it.kind == 2 } == true) ||
                    (shape.intersection != null)
                if (involvesTp) shapes[n] = shape
            }
            // Part 2 for this fn's own params (covers a top-level `function f<T>(x: T = undefined)`)
            checkFnParamsUndefined(typeParameters, parameters, source, fileName)
            when (bodyNode) {
                is Block -> if (shapes.isNotEmpty())
                    walkStmts(bodyNode.statements, shapes, emptySet(), emptyMap(), source, fileName)
                is Expression -> walkExpr(bodyNode, shapes, emptySet(), source, fileName)
                else -> {}
            }
        }

        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName) || checker.isJsLikeFileName(fileName)) continue
            val source = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                when (stmt) {
                    is FunctionDeclaration ->
                        checkFn(stmt.typeParameters, stmt.parameters, stmt.body, source, fileName)
                    is VariableStatement -> for (d in stmt.declarationList.declarations) {
                        val arrow = d.initializer as? ArrowFunction ?: continue
                        checkFn(arrow.typeParameters, arrow.parameters, arrow.body, source, fileName)
                    }
                    else -> {}
                }
            }
        }
    }
}
