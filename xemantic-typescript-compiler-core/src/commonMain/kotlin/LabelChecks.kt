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
 * (INV.0) (P18.289) — the LABEL family: `checkDuplicateLabels` (TS1114, a label re-declared inside
 * its own labelled statement) and `checkUnusedLabels` (TS7028 under `allowUnusedLabels: false`),
 * each a pure statement / expression walk over the checked files. Extracted VERBATIM from
 * `Checker.kt` (two spans: 91300-91381 and 92296-92485 — the spine-called emitter `emitTS1099`
 * between them STAYED); every Checker member it reads is reached through [checker]. Ambient
 * reads: `docs/inversion-ambient-ledger.md` row 22.
 */
internal class LabelChecks(
    private val checker: Checker,
) {

    // -----------------------------------------------------------------------
    // TS1114: Duplicate label
    // -----------------------------------------------------------------------

    fun checkDuplicateLabels() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            walkForDuplicateLabels(result.sourceFile.statements, source, fileName, mutableSetOf())
        }
    }

    private fun walkForDuplicateLabels(stmts: List<Statement>, source: String, fileName: String, activeLabels: MutableSet<String>) {
        for (stmt in stmts) walkStmtForDupLabels(stmt, source, fileName, activeLabels)
    }

    private fun walkStmtForDupLabels(stmt: Statement, source: String, fileName: String, activeLabels: MutableSet<String>) {
        when (stmt) {
            is LabeledStatement -> {
                val label = stmt.label.text
                if (label in activeLabels) {
                    val start = stmt.label.pos
                    val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                    checker.diagnostics.add(Diagnostic(
                        message = "Duplicate label '$label'.",
                        category = DiagnosticCategory.Error,
                        code = 1114,
                        fileName = fileName,
                        line = line,
                        character = character,
                        start = start,
                        length = label.length,
                    ))
                }
                activeLabels.add(label)
                walkStmtForDupLabels(stmt.statement, source, fileName, activeLabels)
                activeLabels.remove(label)
            }
            is FunctionDeclaration -> {
                // New label scope inside functions
                stmt.body?.let { walkForDuplicateLabels(it.statements, source, fileName, mutableSetOf()) }
            }
            is ClassDeclaration -> {
                for (m in stmt.members) {
                    when (m) {
                        is MethodDeclaration -> m.body?.let { walkForDuplicateLabels(it.statements, source, fileName, mutableSetOf()) }
                        is Constructor -> m.body?.let { walkForDuplicateLabels(it.statements, source, fileName, mutableSetOf()) }
                        is GetAccessor -> m.body?.let { walkForDuplicateLabels(it.statements, source, fileName, mutableSetOf()) }
                        is SetAccessor -> m.body?.let { walkForDuplicateLabels(it.statements, source, fileName, mutableSetOf()) }
                        else -> {}
                    }
                }
            }
            is Block -> walkForDuplicateLabels(stmt.statements, source, fileName, activeLabels)
            is IfStatement -> {
                walkStmtForDupLabels(stmt.thenStatement, source, fileName, activeLabels)
                stmt.elseStatement?.let { walkStmtForDupLabels(it, source, fileName, activeLabels) }
            }
            is ForStatement -> walkStmtForDupLabels(stmt.statement, source, fileName, activeLabels)
            is ForInStatement -> walkStmtForDupLabels(stmt.statement, source, fileName, activeLabels)
            is ForOfStatement -> walkStmtForDupLabels(stmt.statement, source, fileName, activeLabels)
            is WhileStatement -> walkStmtForDupLabels(stmt.statement, source, fileName, activeLabels)
            is DoStatement -> walkStmtForDupLabels(stmt.statement, source, fileName, activeLabels)
            is SwitchStatement -> {
                for (c in stmt.caseBlock) {
                    when (c) {
                        is CaseClause -> walkForDuplicateLabels(c.statements, source, fileName, activeLabels)
                        is DefaultClause -> walkForDuplicateLabels(c.statements, source, fileName, activeLabels)
                        else -> {}
                    }
                }
            }
            is TryStatement -> {
                walkForDuplicateLabels(stmt.tryBlock.statements, source, fileName, activeLabels)
                stmt.catchClause?.let { walkForDuplicateLabels(it.block.statements, source, fileName, activeLabels) }
                stmt.finallyBlock?.let { walkForDuplicateLabels(it.statements, source, fileName, activeLabels) }
            }
            is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let { walkForDuplicateLabels(it.statements, source, fileName, activeLabels) }
            else -> {}
        }
    }

    // -----------------------------------------------------------------------
    // TS7028: Unused label
    // -----------------------------------------------------------------------

    fun checkUnusedLabels() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            walkForUnusedLabels(result.sourceFile.statements, source, fileName)
        }
    }

    private fun walkForUnusedLabels(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) walkStmtForUnusedLabels(stmt, source, fileName)
    }

    private fun walkStmtForUnusedLabels(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is LabeledStatement -> {
                val labelName = stmt.label.text
                // Check if this label is used by any break/continue within the labeled statement
                if (!isLabelUsed(labelName, stmt.statement)) {
                    val start = stmt.label.pos
                    val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                    checker.diagnostics.add(Diagnostic(
                        message = "Unused label.",
                        category = DiagnosticCategory.Error,
                        code = 7028,
                        fileName = fileName,
                        line = line,
                        character = character,
                        start = start,
                        length = labelName.length,
                    ))
                }
                // Recurse into the labeled statement
                walkStmtForUnusedLabels(stmt.statement, source, fileName)
            }
            is Block -> walkForUnusedLabels(stmt.statements, source, fileName)
            is IfStatement -> {
                walkStmtForUnusedLabels(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { walkStmtForUnusedLabels(it, source, fileName) }
            }
            is ForStatement -> walkStmtForUnusedLabels(stmt.statement, source, fileName)
            is ForInStatement -> walkStmtForUnusedLabels(stmt.statement, source, fileName)
            is ForOfStatement -> walkStmtForUnusedLabels(stmt.statement, source, fileName)
            is WhileStatement -> walkStmtForUnusedLabels(stmt.statement, source, fileName)
            is DoStatement -> walkStmtForUnusedLabels(stmt.statement, source, fileName)
            is SwitchStatement -> {
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> walkForUnusedLabels(clause.statements, source, fileName)
                        is DefaultClause -> walkForUnusedLabels(clause.statements, source, fileName)
                        else -> {}
                    }
                }
            }
            is TryStatement -> {
                walkForUnusedLabels(stmt.tryBlock.statements, source, fileName)
                stmt.catchClause?.let { walkForUnusedLabels(it.block.statements, source, fileName) }
                stmt.finallyBlock?.let { walkForUnusedLabels(it.statements, source, fileName) }
            }
            is FunctionDeclaration -> {
                // New label scope inside function body
                stmt.body?.let { walkForUnusedLabels(it.statements, source, fileName) }
            }
            is ClassDeclaration -> {
                for (m in stmt.members) {
                    when (m) {
                        is MethodDeclaration -> m.body?.let { walkForUnusedLabels(it.statements, source, fileName) }
                        is Constructor -> m.body?.let { walkForUnusedLabels(it.statements, source, fileName) }
                        is GetAccessor -> m.body?.let { walkForUnusedLabels(it.statements, source, fileName) }
                        is SetAccessor -> m.body?.let { walkForUnusedLabels(it.statements, source, fileName) }
                        is PropertyDeclaration -> m.initializer?.let { walkExprForUnusedLabels(it, source, fileName) }
                        is ClassStaticBlockDeclaration -> walkForUnusedLabels(m.body.statements, source, fileName)
                        else -> {}
                    }
                }
            }
            is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let { walkForUnusedLabels(it.statements, source, fileName) }
            is ExpressionStatement -> walkExprForUnusedLabels(stmt.expression, source, fileName)
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { walkExprForUnusedLabels(it, source, fileName) }
                }
            }
            is ReturnStatement -> stmt.expression?.let { walkExprForUnusedLabels(it, source, fileName) }
            is ThrowStatement -> stmt.expression?.let { walkExprForUnusedLabels(it, source, fileName) }
            is ExportAssignment -> walkExprForUnusedLabels(stmt.expression, source, fileName)
            else -> {}
        }
    }

    private fun walkExprForUnusedLabels(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is FunctionExpression -> expr.body.let { walkForUnusedLabels(it.statements, source, fileName) }
            is ArrowFunction -> {
                val body = expr.body
                if (body is Block) walkForUnusedLabels(body.statements, source, fileName)
                else if (body is Expression) walkExprForUnusedLabels(body, source, fileName)
            }
            is ClassExpression -> {
                for (m in expr.members) {
                    when (m) {
                        is MethodDeclaration -> m.body?.let { walkForUnusedLabels(it.statements, source, fileName) }
                        is Constructor -> m.body?.let { walkForUnusedLabels(it.statements, source, fileName) }
                        else -> {}
                    }
                }
            }
            is ParenthesizedExpression -> walkExprForUnusedLabels(expr.expression, source, fileName)
            is AsExpression -> walkExprForUnusedLabels(expr.expression, source, fileName)
            is TypeAssertionExpression -> walkExprForUnusedLabels(expr.expression, source, fileName)
            is SatisfiesExpression -> walkExprForUnusedLabels(expr.expression, source, fileName)
            is NonNullExpression -> walkExprForUnusedLabels(expr.expression, source, fileName)
            is CallExpression -> {
                walkExprForUnusedLabels(expr.expression, source, fileName)
                expr.arguments.forEach { walkExprForUnusedLabels(it, source, fileName) }
            }
            is NewExpression -> {
                walkExprForUnusedLabels(expr.expression, source, fileName)
                expr.arguments?.forEach { walkExprForUnusedLabels(it, source, fileName) }
            }
            is BinaryExpression -> {
                var current: Expression = expr
                while (current is BinaryExpression) {
                    walkExprForUnusedLabels(current.right, source, fileName)
                    current = current.left
                }
                walkExprForUnusedLabels(current, source, fileName)
            }
            is ConditionalExpression -> {
                walkExprForUnusedLabels(expr.condition, source, fileName)
                walkExprForUnusedLabels(expr.whenTrue, source, fileName)
                walkExprForUnusedLabels(expr.whenFalse, source, fileName)
            }
            is ArrayLiteralExpression -> expr.elements.forEach { walkExprForUnusedLabels(it, source, fileName) }
            is ObjectLiteralExpression -> for (prop in expr.properties) when (prop) {
                is PropertyAssignment -> walkExprForUnusedLabels(prop.initializer, source, fileName)
                is SpreadAssignment -> walkExprForUnusedLabels(prop.expression, source, fileName)
                is MethodDeclaration -> prop.body?.let { walkForUnusedLabels(it.statements, source, fileName) }
                else -> {}
            }
            is TemplateExpression -> expr.templateSpans.forEach { walkExprForUnusedLabels(it.expression, source, fileName) }
            is SpreadElement -> walkExprForUnusedLabels(expr.expression, source, fileName)
            is PropertyAccessExpression -> walkExprForUnusedLabels(expr.expression, source, fileName)
            is ElementAccessExpression -> {
                walkExprForUnusedLabels(expr.expression, source, fileName)
                walkExprForUnusedLabels(expr.argumentExpression, source, fileName)
            }
            is PrefixUnaryExpression -> walkExprForUnusedLabels(expr.operand, source, fileName)
            is PostfixUnaryExpression -> walkExprForUnusedLabels(expr.operand, source, fileName)
            is AwaitExpression -> walkExprForUnusedLabels(expr.expression, source, fileName)
            is YieldExpression -> expr.expression?.let { walkExprForUnusedLabels(it, source, fileName) }
            else -> {}
        }
    }

    /**
     * Returns true if a break or continue referencing [labelName] exists in [stmt].
     * Does NOT recurse into nested function boundaries (those create new label scopes).
     */
    private fun isLabelUsed(labelName: String, stmt: Statement): Boolean {
        return when (stmt) {
            is BreakStatement -> stmt.label?.text == labelName
            is ContinueStatement -> stmt.label?.text == labelName
            is Block -> stmt.statements.any { isLabelUsed(labelName, it) }
            is IfStatement -> isLabelUsed(labelName, stmt.thenStatement) ||
                (stmt.elseStatement?.let { isLabelUsed(labelName, it) } ?: false)
            is LabeledStatement -> isLabelUsed(labelName, stmt.statement)
            is ForStatement -> isLabelUsed(labelName, stmt.statement)
            is ForInStatement -> isLabelUsed(labelName, stmt.statement)
            is ForOfStatement -> isLabelUsed(labelName, stmt.statement)
            is WhileStatement -> isLabelUsed(labelName, stmt.statement)
            is DoStatement -> isLabelUsed(labelName, stmt.statement)
            is SwitchStatement -> stmt.caseBlock.any { clause ->
                when (clause) {
                    is CaseClause -> clause.statements.any { isLabelUsed(labelName, it) }
                    is DefaultClause -> clause.statements.any { isLabelUsed(labelName, it) }
                    else -> false
                }
            }
            is TryStatement -> stmt.tryBlock.statements.any { isLabelUsed(labelName, it) } ||
                (stmt.catchClause?.block?.statements?.any { isLabelUsed(labelName, it) } ?: false) ||
                (stmt.finallyBlock?.statements?.any { isLabelUsed(labelName, it) } ?: false)
            // Do NOT recurse into function declarations/expressions — new label scope
            else -> false
        }
    }
}
