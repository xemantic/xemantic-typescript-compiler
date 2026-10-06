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
 * (INV.0) (P18.308) — the property-used-before-initialization family (TS2729): the pass `checkPropertyUseBeforeInit`
 * with its private walkers and helpers. Extracted VERBATIM from `Checker.kt` (one span: 140130-140451);
 * every Checker member it reads is reached through [checker]. No walk-scoped or spine ambient is read.
 * Ambient reads: `docs/inversion-ambient-ledger.md` row 26.
 */
internal class PropertyInitOrderChecks(
    private val checker: Checker,
) {

    // -----------------------------------------------------------------------
    // TS2729: Property used before its initialization
    // -----------------------------------------------------------------------

    fun checkPropertyUseBeforeInit() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                checkPropertyUseBeforeInitInStatement(stmt, source, fileName)
            }
        }
    }

    private fun checkPropertyUseBeforeInitInStatement(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is ClassDeclaration -> checkClassPropertyUseBeforeInit(stmt, source, fileName)
            is ModuleDeclaration -> {
                val body = stmt.body
                if (body is ModuleBlock) {
                    for (s in body.statements) checkPropertyUseBeforeInitInStatement(s, source, fileName)
                }
            }
            is FunctionDeclaration -> {
                stmt.body?.let {
                    for (s in it.statements) checkPropertyUseBeforeInitInStatement(s, source, fileName)
                }
            }
            is IfStatement -> {
                checkPropertyUseBeforeInitInStatement(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { checkPropertyUseBeforeInitInStatement(it, source, fileName) }
            }
            is ForStatement -> checkPropertyUseBeforeInitInStatement(stmt.statement, source, fileName)
            is ForInStatement -> checkPropertyUseBeforeInitInStatement(stmt.statement, source, fileName)
            is ForOfStatement -> checkPropertyUseBeforeInitInStatement(stmt.statement, source, fileName)
            is WhileStatement -> checkPropertyUseBeforeInitInStatement(stmt.statement, source, fileName)
            is DoStatement -> checkPropertyUseBeforeInitInStatement(stmt.statement, source, fileName)
            is SwitchStatement -> {
                for (c in stmt.caseBlock) when (c) {
                    is CaseClause -> for (s in c.statements) checkPropertyUseBeforeInitInStatement(s, source, fileName)
                    is DefaultClause -> for (s in c.statements) checkPropertyUseBeforeInitInStatement(s, source, fileName)
                    else -> {}
                }
            }
            is TryStatement -> {
                for (s in stmt.tryBlock.statements) checkPropertyUseBeforeInitInStatement(s, source, fileName)
                stmt.catchClause?.block?.statements?.forEach { checkPropertyUseBeforeInitInStatement(it, source, fileName) }
                stmt.finallyBlock?.statements?.forEach { checkPropertyUseBeforeInitInStatement(it, source, fileName) }
            }
            is LabeledStatement -> checkPropertyUseBeforeInitInStatement(stmt.statement, source, fileName)
            is Block -> {
                for (s in stmt.statements) checkPropertyUseBeforeInitInStatement(s, source, fileName)
            }
            else -> {}
        }
    }

    private fun checkClassPropertyUseBeforeInit(classDecl: ClassDeclaration, source: String, fileName: String) {
        // Determine which properties are inherited from base classes (extends, not implements)
        val inheritedNames = mutableSetOf<String>()
        classDecl.heritageClauses?.forEach { clause ->
            if (clause.token != SyntaxKind.ExtendsKeyword) return@forEach
            for (typeExpr in clause.types) {
                val baseName = when (val tn = typeExpr.expression) {
                    is Identifier -> tn.text
                    is PropertyAccessExpression -> (tn.name).text
                    else -> null
                } ?: continue
                val baseSymbol = checker.globals[baseName] ?: continue
                collectInheritedPropertyNames(baseSymbol, inheritedNames)
            }
        }

        // Collect property declarations in order, tracking which are "initialized"
        data class PropInfo(val name: String, val pos: Int, val hasInit: Boolean, val hasExcl: Boolean, val hasQuestion: Boolean, val isStatic: Boolean)
        val props = mutableListOf<PropInfo>()
        for (member in classDecl.members) {
            when (member) {
                is PropertyDeclaration -> {
                    val name = (member.name as? Identifier)?.text ?: continue
                    val isStatic = ModifierFlag.Static in member.modifiers
                    props.add(PropInfo(
                        name = name,
                        pos = (member.name).pos,
                        hasInit = member.initializer != null,
                        hasExcl = member.exclamationToken,
                        hasQuestion = member.questionToken,
                        isStatic = isStatic,
                    ))
                }
                else -> {}
            }
        }

        // For each property with an initializer, check for this.X references
        val className = classDecl.name?.text
        for ((idx, prop) in props.withIndex()) {
            if (prop.hasInit) {
                // Find the initializer node
                val initExpr = classDecl.members.filterIsInstance<PropertyDeclaration>()
                    .find { (it.name as? Identifier)?.text == prop.name && (ModifierFlag.Static in it.modifiers) == prop.isStatic }
                    ?.initializer ?: continue

                // Collect this.X references (not inside arrow/function)
                val refs = mutableListOf<Pair<String, Int>>() // (propName, pos of propName)
                collectThisPropertyRefs(initExpr, refs, prop.isStatic, className)

                for ((refName, refPos) in refs) {
                    // Check if refName is inherited from base class
                    if (refName in inheritedNames) continue

                    // Find the property declaration for refName
                    val refProp = props.find { it.name == refName && it.isStatic == prop.isStatic }
                    if (refProp == null) continue // not a class property (might be inherited or doesn't exist)

                    // Check if refProp is initialized before the current prop
                    val refIdx = props.indexOf(refProp)
                    val isBeforeInit = if (refIdx < idx) {
                        // Declared ABOVE: error only if it has no initializer of its own,
                        // no `!`, AND is not OPTIONAL. A non-optional, un-initialized
                        // property (e.g. `abstract prop: string`) read by an earlier-running
                        // field IS use-before-init; an OPTIONAL `p5?: number` is legitimately
                        // `undefined` so reading it is fine.
                        !refProp.hasInit && !refProp.hasExcl && !refProp.hasQuestion
                    } else {
                        // Declared below or self-reference — an error (unless has `!`)
                        !refProp.hasExcl
                    }

                    if (isBeforeInit) {
                        val (line, character) = checker.getLineAndCharacterOfPosition(source, refPos)
                        val length = refName.length
                        checker.diagnostics.add(Diagnostic(
                            message = "Property '$refName' is used before its initialization.",
                            category = DiagnosticCategory.Error,
                            code = 2729,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = refPos,
                            length = length,
                            relatedInformation = listOf(Diagnostic(
                                message = "'$refName' is declared here.",
                                category = DiagnosticCategory.Message,
                                code = 2728,
                                fileName = fileName,
                                line = checker.getLineAndCharacterOfPosition(source, refProp.pos).first,
                                character = checker.getLineAndCharacterOfPosition(source, refProp.pos).second,
                                start = refProp.pos,
                                length = refName.length,
                            )),
                        ))
                    }
                }
            }
        }
    }

    /** Collect inherited property names from base class chain (extends only). */
    private fun collectInheritedPropertyNames(
        symbol: Symbol, names: MutableSet<String>,
        // Cycle guard (keyed on symbol id): a cyclic `extends` chain
        // (`class A extends B`, `class B extends A`, or `class A extends A`)
        // would otherwise recurse until a StackOverflowError. Re-entry on an
        // already-seen symbol simply stops — its members were already collected.
        // Default-valued so the external caller is unchanged; recursion threads
        // the same set down.
        visited: MutableSet<Int> = HashSet(),
    ) {
        if (!visited.add(symbol.id)) return
        for (decl in symbol.declarations) {
            if (decl !is ClassDeclaration) continue
            for (member in decl.members) {
                if (member is PropertyDeclaration) {
                    val name = (member.name as? Identifier)?.text ?: continue
                    names.add(name)
                }
                if (member is MethodDeclaration) {
                    val name = (member.name as? Identifier)?.text ?: continue
                    names.add(name)
                }
            }
            // Recurse into base classes
            decl.heritageClauses?.forEach { clause ->
                if (clause.token != SyntaxKind.ExtendsKeyword) return@forEach
                for (typeExpr in clause.types) {
                    val baseName = when (val tn = typeExpr.expression) {
                        is Identifier -> tn.text
                        else -> null
                    } ?: continue
                    val baseSymbol = checker.globals[baseName] ?: continue
                    collectInheritedPropertyNames(baseSymbol, names, visited)
                }
            }
        }
    }

    /** Collect this.X (or ClassName.X for statics) property references in an expression.
     *  Skips inside arrow functions and function expressions (deferred evaluation). */
    private fun collectThisPropertyRefs(
        expr: Node, refs: MutableList<Pair<String, Int>>,
        isStatic: Boolean, className: String?,
        // True once recursion has descended through an object-spread (`...{...}`).
        // TypeScript does NOT report use-before-init for a getter/setter/method
        // computed name nested inside a spread object (`...{get [D.D]() {}}`), only
        // for a DIRECT object-literal accessor/method. A direct PropertyAssignment
        // computed name still fires regardless.
        viaSpread: Boolean = false,
    ) {
        when (expr) {
            is PropertyAccessExpression -> {
                val obj = expr.expression
                val isRelevant = if (isStatic) {
                    // For static props: ClassName.X
                    obj is Identifier && className != null && obj.text == className
                } else {
                    // For instance props: this.X
                    obj is Identifier && obj.text == "this"
                }
                if (isRelevant) {
                    refs.add(expr.name.text to expr.name.pos)
                }
                // Also recurse into the expression and name
                collectThisPropertyRefs(expr.expression, refs, isStatic, className)
            }
            // Skip arrow functions and function expressions (deferred evaluation)
            is ArrowFunction -> return
            is FunctionExpression -> return
            // Recurse into subexpressions
            is CallExpression -> {
                collectThisPropertyRefs(expr.expression, refs, isStatic, className)
                expr.arguments.forEach { collectThisPropertyRefs(it, refs, isStatic, className) }
            }
            is BinaryExpression -> {
                // A simple assignment `this.X = ...` WRITES X — that is not a "use" of
                // X (TypeScript doesn't flag it). Skip the direct ref of the LHS name but
                // still recurse into the receiver (so `this.a.b = ...` still counts a use
                // of `this.a`) and the RHS.
                val left = expr.left
                val isWriteTargetRef = expr.operator == SyntaxKind.Equals &&
                    left is PropertyAccessExpression &&
                    (if (isStatic) (left.expression as? Identifier)?.text == className
                     else (left.expression as? Identifier)?.text == "this")
                if (isWriteTargetRef) {
                    collectThisPropertyRefs((left).expression, refs, isStatic, className)
                } else {
                    collectThisPropertyRefs(expr.left, refs, isStatic, className)
                }
                collectThisPropertyRefs(expr.right, refs, isStatic, className)
            }
            is ConditionalExpression -> {
                collectThisPropertyRefs(expr.condition, refs, isStatic, className)
                collectThisPropertyRefs(expr.whenTrue, refs, isStatic, className)
                collectThisPropertyRefs(expr.whenFalse, refs, isStatic, className)
            }
            is ParenthesizedExpression -> collectThisPropertyRefs(expr.expression, refs, isStatic, className)
            is PrefixUnaryExpression -> collectThisPropertyRefs(expr.operand, refs, isStatic, className)
            is PostfixUnaryExpression -> collectThisPropertyRefs(expr.operand, refs, isStatic, className)
            is NewExpression -> {
                collectThisPropertyRefs(expr.expression, refs, isStatic, className)
                expr.arguments?.forEach { collectThisPropertyRefs(it, refs, isStatic, className) }
            }
            is ElementAccessExpression -> {
                collectThisPropertyRefs(expr.expression, refs, isStatic, className)
                collectThisPropertyRefs(expr.argumentExpression, refs, isStatic, className)
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is PropertyAssignment -> {
                            prop.name.let { if (it is ComputedPropertyName) collectThisPropertyRefs(it.expression, refs, isStatic, className) }
                            collectThisPropertyRefs(prop.initializer, refs, isStatic, className)
                        }
                        is SpreadAssignment -> collectThisPropertyRefs(prop.expression, refs, isStatic, className, viaSpread = true)
                        is ShorthandPropertyAssignment -> {}
                        // A computed name on a DIRECT object-literal method/accessor is
                        // evaluated eagerly (`{ get [this.X]() {} }`), so `this.X` there is a
                        // use. Inside a spread (`...{get [D.D]() {}}`) TypeScript does NOT
                        // report it — gate on !viaSpread. The accessor/method BODY is
                        // deferred and intentionally not recursed.
                        is MethodDeclaration -> if (!viaSpread) (prop.name as? ComputedPropertyName)?.let { collectThisPropertyRefs(it.expression, refs, isStatic, className) }
                        is GetAccessor -> if (!viaSpread) (prop.name as? ComputedPropertyName)?.let { collectThisPropertyRefs(it.expression, refs, isStatic, className) }
                        is SetAccessor -> if (!viaSpread) (prop.name as? ComputedPropertyName)?.let { collectThisPropertyRefs(it.expression, refs, isStatic, className) }
                        else -> {}
                    }
                }
            }
            is ArrayLiteralExpression -> {
                expr.elements.forEach { collectThisPropertyRefs(it, refs, isStatic, className) }
            }
            is TemplateExpression -> {
                expr.templateSpans.forEach { span ->
                    collectThisPropertyRefs(span.expression, refs, isStatic, className)
                }
            }
            is AsExpression -> collectThisPropertyRefs(expr.expression, refs, isStatic, className)
            is NonNullExpression -> collectThisPropertyRefs(expr.expression, refs, isStatic, className)
            is SpreadElement -> collectThisPropertyRefs(expr.expression, refs, isStatic, className)
            is TypeAssertionExpression -> collectThisPropertyRefs(expr.expression, refs, isStatic, className)
            is TaggedTemplateExpression -> {
                collectThisPropertyRefs(expr.tag, refs, isStatic, className)
            }
            is AwaitExpression -> expr.expression.let { collectThisPropertyRefs(it, refs, isStatic, className) }
            is VoidExpression -> collectThisPropertyRefs(expr.expression, refs, isStatic, className)
            is TypeOfExpression -> collectThisPropertyRefs(expr.expression, refs, isStatic, className)
            is DeleteExpression -> collectThisPropertyRefs(expr.expression, refs, isStatic, className)
            is YieldExpression -> expr.expression?.let { collectThisPropertyRefs(it, refs, isStatic, className) }
            is ClassExpression -> {
                // Heritage-clause expressions (`class extends this.X`) are evaluated
                // eagerly when the class expression is created, so `this.X` / `ClassName.X`
                // there is a use — for instance props too (not just static). The class
                // BODY is deferred and intentionally not recursed.
                expr.heritageClauses?.forEach { clause ->
                    for (typeExpr in clause.types) {
                        collectThisPropertyRefs(typeExpr.expression, refs, isStatic, className)
                    }
                }
            }
            else -> {}
        }
    }
}
