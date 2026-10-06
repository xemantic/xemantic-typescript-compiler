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
 * (INV.0) (P18.308) — the static-member type-parameter reference family (TS2302): the pass `checkStaticMembersReferenceTypeParams`
 * with its private walkers and helpers. Extracted VERBATIM from `Checker.kt` (one span: 134973-135353);
 * every Checker member it reads is reached through [checker]. No walk-scoped or spine ambient is read.
 * Ambient reads: `docs/inversion-ambient-ledger.md` row 26.
 */
internal class StaticTypeParamRefChecks(
    private val checker: Checker,
) {

    // -----------------------------------------------------------------------
    // TS2302: Static members cannot reference class type parameters
    // -----------------------------------------------------------------------

    fun checkStaticMembersReferenceTypeParams() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                checkTS2302InStatement(stmt, source, fileName)
            }
        }
    }

    private fun checkTS2302InStatement(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is ClassDeclaration -> {
                val typeParams = stmt.typeParameters
                if (typeParams != null && typeParams.isNotEmpty()) {
                    val typeParamNames = typeParams.map { it.name.text }.toSet()
                    for (member in stmt.members) {
                        checkTS2302InClassMember(member, typeParamNames, source, fileName)
                    }
                }
                // Recurse into member bodies for nested class declarations
                for (member in stmt.members) {
                    when (member) {
                        is MethodDeclaration -> member.body?.let { for (s in it.statements) checkTS2302InStatement(s, source, fileName) }
                        is Constructor -> member.body?.let { for (s in it.statements) checkTS2302InStatement(s, source, fileName) }
                        is GetAccessor -> member.body?.let { for (s in it.statements) checkTS2302InStatement(s, source, fileName) }
                        is SetAccessor -> member.body?.let { for (s in it.statements) checkTS2302InStatement(s, source, fileName) }
                        else -> {}
                    }
                }
            }
            is ModuleDeclaration -> {
                (stmt.body as? ModuleBlock)?.let {
                    for (s in it.statements) checkTS2302InStatement(s, source, fileName)
                }
            }
            is FunctionDeclaration -> stmt.body?.let { for (s in it.statements) checkTS2302InStatement(s, source, fileName) }
            is Block -> for (s in stmt.statements) checkTS2302InStatement(s, source, fileName)
            is IfStatement -> {
                checkTS2302InStatement(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { checkTS2302InStatement(it, source, fileName) }
            }
            is ForStatement -> checkTS2302InStatement(stmt.statement, source, fileName)
            is ForInStatement -> checkTS2302InStatement(stmt.statement, source, fileName)
            is ForOfStatement -> checkTS2302InStatement(stmt.statement, source, fileName)
            is WhileStatement -> checkTS2302InStatement(stmt.statement, source, fileName)
            is DoStatement -> checkTS2302InStatement(stmt.statement, source, fileName)
            is SwitchStatement -> {
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> for (s in clause.statements) checkTS2302InStatement(s, source, fileName)
                        is DefaultClause -> for (s in clause.statements) checkTS2302InStatement(s, source, fileName)
                        else -> {}
                    }
                }
            }
            is TryStatement -> {
                for (s in stmt.tryBlock.statements) checkTS2302InStatement(s, source, fileName)
                stmt.catchClause?.block?.statements?.forEach { checkTS2302InStatement(it, source, fileName) }
                stmt.finallyBlock?.statements?.forEach { checkTS2302InStatement(it, source, fileName) }
            }
            is LabeledStatement -> checkTS2302InStatement(stmt.statement, source, fileName)
            else -> {}
        }
    }

    private fun checkTS2302InClassMember(member: ClassElement, typeParamNames: Set<String>, source: String, fileName: String) {
        val isStatic = when (member) {
            is PropertyDeclaration -> ModifierFlag.Static in member.modifiers
            is MethodDeclaration -> ModifierFlag.Static in member.modifiers
            is GetAccessor -> ModifierFlag.Static in member.modifiers
            is SetAccessor -> ModifierFlag.Static in member.modifiers
            else -> false
        }
        if (!isStatic) return
        when (member) {
            is PropertyDeclaration -> {
                member.type?.let { findTypeParamRefsInType(it, typeParamNames, source, fileName) }
                member.initializer?.let { findTypeParamRefsInExpr(it, typeParamNames, source, fileName) }
            }
            is MethodDeclaration -> {
                // Exclude method's own type parameters (they shadow class type params)
                val effectiveNames = member.typeParameters?.let { tps ->
                    typeParamNames - tps.map { it.name.text }.toSet()
                } ?: typeParamNames
                if (effectiveNames.isEmpty()) return
                for (param in member.parameters) {
                    param.type?.let { findTypeParamRefsInType(it, effectiveNames, source, fileName) }
                }
                member.type?.let { findTypeParamRefsInType(it, effectiveNames, source, fileName) }
                // Walk method body — static method bodies cannot reference class type params
                // either (e.g. `var entry: List<T>` or `new List<T>()` inside the body).
                member.body?.statements?.forEach { findTypeParamRefsInStatement(it, effectiveNames, source, fileName) }
            }
            is GetAccessor -> {
                member.type?.let { findTypeParamRefsInType(it, typeParamNames, source, fileName) }
                member.body?.statements?.forEach { findTypeParamRefsInStatement(it, typeParamNames, source, fileName) }
            }
            is SetAccessor -> {
                for (param in member.parameters) {
                    param.type?.let { findTypeParamRefsInType(it, typeParamNames, source, fileName) }
                }
                member.body?.statements?.forEach { findTypeParamRefsInStatement(it, typeParamNames, source, fileName) }
            }
            else -> {}
        }
    }

    /**
     * (LIB.5) G3 — subtract a nested function-like container's OWN type parameters before
     * descending into it, because they SHADOW the enclosing class's.
     *
     * tsgo has no TS2302 walker at all: the code is a name-RESOLUTION outcome in
     * `internal/binder/nameresolver.go:170-186`, where the resolver walks containers
     * OUTWARD and a generic arrow / function expression / function type is itself a
     * container whose own type parameters are found FIRST — so the class arm is never
     * reached and shadowing is excluded STRUCTURALLY rather than by a rule.  This walker
     * carries a flat name set instead, so the exclusion has to be spelled at every
     * boundary: `ArrowFunction`, `FunctionExpression`, `FunctionType`, `ConstructorType`
     * and a `MethodDeclaration` inside a `TypeLiteral`.  A `MethodDeclaration` CLASS
     * MEMBER has always had it, inline, in `checkTS2302InClassMember`.
     *
     * Measured against tsgo on `build/bench/lib-rxjs-7.8.2` (4 ours-only rows, all of them
     * `static create: (...args: any[]) => any = <T>(...) => {...}` inside a `class X<T>`).
     * The decisive fixture is `class B4<T, S> { static create: any = <S>(x: S, y: T) => x }`,
     * which fails in BOTH directions on one line: the arrow's own `S` must NOT report and
     * the class's `T` must.
     *
     * Subtraction only ever REMOVES rows; a non-generic container has a null/empty list and
     * so is handed the caller's set unchanged (`genericClassWithStaticsUsingTypeArguments`'s
     * `static a = (n: T) => {}` and `static e = function (x: T) {...}` are exactly that, and
     * are the corpus's own controls for it).
     */
    private fun shadowedTypeParamNames(own: List<TypeParameter>?, outer: Set<String>): Set<String> =
        if (own.isNullOrEmpty()) outer else outer - own.map { it.name.text }.toSet()

    /** Walk a type node tree to find references to class type parameters. */
    private fun findTypeParamRefsInType(typeNode: TypeNode, typeParamNames: Set<String>, source: String, fileName: String) {
        when (typeNode) {
            is TypeReference -> {
                val name = typeNode.typeName
                if (name is Identifier && name.text in typeParamNames) {
                    emitTS2302(name, source, fileName)
                }
                typeNode.typeArguments?.forEach { findTypeParamRefsInType(it, typeParamNames, source, fileName) }
            }
            is ArrayType -> findTypeParamRefsInType(typeNode.elementType, typeParamNames, source, fileName)
            is UnionType -> typeNode.types.forEach { findTypeParamRefsInType(it, typeParamNames, source, fileName) }
            is IntersectionType -> typeNode.types.forEach { findTypeParamRefsInType(it, typeParamNames, source, fileName) }
            is ParenthesizedType -> findTypeParamRefsInType(typeNode.type, typeParamNames, source, fileName)
            is FunctionType -> {
                val names = shadowedTypeParamNames(typeNode.typeParameters, typeParamNames)
                if (names.isNotEmpty()) {
                    typeNode.parameters.forEach { p -> p.type?.let { findTypeParamRefsInType(it, names, source, fileName) } }
                    findTypeParamRefsInType(typeNode.type, names, source, fileName)
                }
            }
            is ConstructorType -> {
                val names = shadowedTypeParamNames(typeNode.typeParameters, typeParamNames)
                if (names.isNotEmpty()) {
                    typeNode.parameters.forEach { p -> p.type?.let { findTypeParamRefsInType(it, names, source, fileName) } }
                    findTypeParamRefsInType(typeNode.type, names, source, fileName)
                }
            }
            is TypeLiteral -> {
                for (m in typeNode.members) {
                    when (m) {
                        is PropertyDeclaration -> m.type?.let { findTypeParamRefsInType(it, typeParamNames, source, fileName) }
                        is MethodDeclaration -> {
                            val names = shadowedTypeParamNames(m.typeParameters, typeParamNames)
                            if (names.isNotEmpty()) {
                                m.parameters.forEach { p -> p.type?.let { findTypeParamRefsInType(it, names, source, fileName) } }
                                m.type?.let { findTypeParamRefsInType(it, names, source, fileName) }
                            }
                        }
                        is IndexSignature -> m.type?.let { findTypeParamRefsInType(it, typeParamNames, source, fileName) }
                        else -> {}
                    }
                }
            }
            is TupleType -> typeNode.elements.forEach { findTypeParamRefsInType(it, typeParamNames, source, fileName) }
            is OptionalType -> findTypeParamRefsInType(typeNode.type, typeParamNames, source, fileName)
            is RestType -> findTypeParamRefsInType(typeNode.type, typeParamNames, source, fileName)
            is ConditionalType -> {
                findTypeParamRefsInType(typeNode.checkType, typeParamNames, source, fileName)
                findTypeParamRefsInType(typeNode.extendsType, typeParamNames, source, fileName)
                findTypeParamRefsInType(typeNode.trueType, typeParamNames, source, fileName)
                findTypeParamRefsInType(typeNode.falseType, typeParamNames, source, fileName)
            }
            is TypeQuery -> {} // typeof x — no type parameter references
            is IndexedAccessType -> {
                findTypeParamRefsInType(typeNode.objectType, typeParamNames, source, fileName)
                findTypeParamRefsInType(typeNode.indexType, typeParamNames, source, fileName)
            }
            // (P18.309) the arms the walker lacked: `keyof U` / `readonly T[]`, a named tuple
            // member, a template literal type's spans, a type predicate's type, and a mapped
            // type (its own parameter shadows a class type parameter of the same name in the
            // name type and the template, never in the constraint).
            is TypeOperator -> findTypeParamRefsInType(typeNode.type, typeParamNames, source, fileName)
            is NamedTupleMember -> findTypeParamRefsInType(typeNode.type, typeParamNames, source, fileName)
            is TemplateLiteralType -> typeNode.templateSpans.forEach { findTypeParamRefsInType(it.type, typeParamNames, source, fileName) }
            is TypePredicate -> typeNode.type?.let { findTypeParamRefsInType(it, typeParamNames, source, fileName) }
            is MappedType -> {
                typeNode.typeParameter.constraint?.let { findTypeParamRefsInType(it, typeParamNames, source, fileName) }
                val names = typeParamNames - typeNode.typeParameter.name.text
                if (names.isNotEmpty()) {
                    typeNode.nameType?.let { findTypeParamRefsInType(it, names, source, fileName) }
                    typeNode.type?.let { findTypeParamRefsInType(it, names, source, fileName) }
                }
            }
            else -> {} // LiteralType, KeywordType, etc.
        }
    }

    /** Walk a statement (and nested blocks) to find type parameter references in type annotations. */
    private fun findTypeParamRefsInStatement(stmt: Statement, typeParamNames: Set<String>, source: String, fileName: String) {
        when (stmt) {
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.type?.let { findTypeParamRefsInType(it, typeParamNames, source, fileName) }
                    decl.initializer?.let { findTypeParamRefsInExpr(it, typeParamNames, source, fileName) }
                }
            }
            is ExpressionStatement -> findTypeParamRefsInExpr(stmt.expression, typeParamNames, source, fileName)
            is ReturnStatement -> stmt.expression?.let { findTypeParamRefsInExpr(it, typeParamNames, source, fileName) }
            is Block -> stmt.statements.forEach { findTypeParamRefsInStatement(it, typeParamNames, source, fileName) }
            is IfStatement -> {
                findTypeParamRefsInExpr(stmt.expression, typeParamNames, source, fileName)
                findTypeParamRefsInStatement(stmt.thenStatement, typeParamNames, source, fileName)
                stmt.elseStatement?.let { findTypeParamRefsInStatement(it, typeParamNames, source, fileName) }
            }
            is ForStatement -> {
                when (val init = stmt.initializer) {
                    is VariableDeclarationList -> {
                        for (decl in init.declarations) {
                            decl.type?.let { findTypeParamRefsInType(it, typeParamNames, source, fileName) }
                            decl.initializer?.let { findTypeParamRefsInExpr(it, typeParamNames, source, fileName) }
                        }
                    }
                    is Expression -> findTypeParamRefsInExpr(init, typeParamNames, source, fileName)
                    else -> {}
                }
                stmt.condition?.let { findTypeParamRefsInExpr(it, typeParamNames, source, fileName) }
                stmt.incrementor?.let { findTypeParamRefsInExpr(it, typeParamNames, source, fileName) }
                findTypeParamRefsInStatement(stmt.statement, typeParamNames, source, fileName)
            }
            is ForInStatement -> {
                findTypeParamRefsInExpr(stmt.expression, typeParamNames, source, fileName)
                findTypeParamRefsInStatement(stmt.statement, typeParamNames, source, fileName)
            }
            is ForOfStatement -> {
                findTypeParamRefsInExpr(stmt.expression, typeParamNames, source, fileName)
                findTypeParamRefsInStatement(stmt.statement, typeParamNames, source, fileName)
            }
            is WhileStatement -> {
                findTypeParamRefsInExpr(stmt.expression, typeParamNames, source, fileName)
                findTypeParamRefsInStatement(stmt.statement, typeParamNames, source, fileName)
            }
            is DoStatement -> {
                findTypeParamRefsInExpr(stmt.expression, typeParamNames, source, fileName)
                findTypeParamRefsInStatement(stmt.statement, typeParamNames, source, fileName)
            }
            is SwitchStatement -> {
                findTypeParamRefsInExpr(stmt.expression, typeParamNames, source, fileName)
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> {
                            findTypeParamRefsInExpr(clause.expression, typeParamNames, source, fileName)
                            clause.statements.forEach { findTypeParamRefsInStatement(it, typeParamNames, source, fileName) }
                        }
                        is DefaultClause -> clause.statements.forEach { findTypeParamRefsInStatement(it, typeParamNames, source, fileName) }
                        else -> {}
                    }
                }
            }
            is TryStatement -> {
                findTypeParamRefsInStatement(stmt.tryBlock, typeParamNames, source, fileName)
                stmt.catchClause?.block?.let { findTypeParamRefsInStatement(it, typeParamNames, source, fileName) }
                stmt.finallyBlock?.let { findTypeParamRefsInStatement(it, typeParamNames, source, fileName) }
            }
            is ThrowStatement -> stmt.expression?.let { findTypeParamRefsInExpr(it, typeParamNames, source, fileName) }
            is LabeledStatement -> findTypeParamRefsInStatement(stmt.statement, typeParamNames, source, fileName)
            is ExportAssignment -> findTypeParamRefsInExpr(stmt.expression, typeParamNames, source, fileName)
            else -> {}
        }
    }

    /** Walk an expression to find type parameter references in embedded type annotations. */
    private fun findTypeParamRefsInExpr(expr: Expression, typeParamNames: Set<String>, source: String, fileName: String) {
        when (expr) {
            is ArrowFunction -> {
                val names = shadowedTypeParamNames(expr.typeParameters, typeParamNames)
                if (names.isNotEmpty()) {
                    expr.parameters.forEach { p -> p.type?.let { findTypeParamRefsInType(it, names, source, fileName) } }
                    expr.type?.let { findTypeParamRefsInType(it, names, source, fileName) }
                    when (val body = expr.body) {
                        is Expression -> findTypeParamRefsInExpr(body, names, source, fileName)
                        else -> {}
                    }
                }
            }
            is FunctionExpression -> {
                val names = shadowedTypeParamNames(expr.typeParameters, typeParamNames)
                if (names.isNotEmpty()) {
                    expr.parameters.forEach { p -> p.type?.let { findTypeParamRefsInType(it, names, source, fileName) } }
                    expr.type?.let { findTypeParamRefsInType(it, names, source, fileName) }
                }
            }
            is ParenthesizedExpression -> findTypeParamRefsInExpr(expr.expression, typeParamNames, source, fileName)
            is BinaryExpression -> {
                // B64.7: iterative left-spine flatten.
                val rightStack = ArrayDeque<Expression>()
                var cur: Expression = expr
                while (cur is BinaryExpression) {
                    rightStack.addLast(cur.right)
                    cur = cur.left
                }
                findTypeParamRefsInExpr(cur, typeParamNames, source, fileName)
                while (rightStack.isNotEmpty()) findTypeParamRefsInExpr(rightStack.removeLast(), typeParamNames, source, fileName)
            }
            is ConditionalExpression -> {
                findTypeParamRefsInExpr(expr.condition, typeParamNames, source, fileName)
                findTypeParamRefsInExpr(expr.whenTrue, typeParamNames, source, fileName)
                findTypeParamRefsInExpr(expr.whenFalse, typeParamNames, source, fileName)
            }
            is CallExpression -> {
                findTypeParamRefsInExpr(expr.expression, typeParamNames, source, fileName)
                expr.typeArguments?.forEach { findTypeParamRefsInType(it, typeParamNames, source, fileName) }
                expr.arguments.forEach { findTypeParamRefsInExpr(it, typeParamNames, source, fileName) }
            }
            is NewExpression -> {
                findTypeParamRefsInExpr(expr.expression, typeParamNames, source, fileName)
                expr.typeArguments?.forEach { findTypeParamRefsInType(it, typeParamNames, source, fileName) }
                expr.arguments?.forEach { findTypeParamRefsInExpr(it, typeParamNames, source, fileName) }
            }
            is AsExpression -> {
                findTypeParamRefsInExpr(expr.expression, typeParamNames, source, fileName)
                findTypeParamRefsInType(expr.type, typeParamNames, source, fileName)
            }
            is TypeAssertionExpression -> {
                findTypeParamRefsInExpr(expr.expression, typeParamNames, source, fileName)
                findTypeParamRefsInType(expr.type, typeParamNames, source, fileName)
            }
            is SatisfiesExpression -> {
                findTypeParamRefsInExpr(expr.expression, typeParamNames, source, fileName)
                findTypeParamRefsInType(expr.type, typeParamNames, source, fileName)
            }
            is PropertyAccessExpression -> findTypeParamRefsInExpr(expr.expression, typeParamNames, source, fileName)
            is ElementAccessExpression -> {
                findTypeParamRefsInExpr(expr.expression, typeParamNames, source, fileName)
                findTypeParamRefsInExpr(expr.argumentExpression, typeParamNames, source, fileName)
            }
            is ArrayLiteralExpression -> expr.elements.forEach { findTypeParamRefsInExpr(it, typeParamNames, source, fileName) }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is PropertyAssignment -> findTypeParamRefsInExpr(prop.initializer, typeParamNames, source, fileName)
                        is ShorthandPropertyAssignment -> prop.objectAssignmentInitializer?.let {
                            findTypeParamRefsInExpr(it, typeParamNames, source, fileName)
                        }
                        is SpreadAssignment -> findTypeParamRefsInExpr(prop.expression, typeParamNames, source, fileName)
                        else -> {}
                    }
                }
            }
            is PrefixUnaryExpression -> findTypeParamRefsInExpr(expr.operand, typeParamNames, source, fileName)
            is PostfixUnaryExpression -> findTypeParamRefsInExpr(expr.operand, typeParamNames, source, fileName)
            is NonNullExpression -> findTypeParamRefsInExpr(expr.expression, typeParamNames, source, fileName)
            is SpreadElement -> findTypeParamRefsInExpr(expr.expression, typeParamNames, source, fileName)
            is DeleteExpression -> findTypeParamRefsInExpr(expr.expression, typeParamNames, source, fileName)
            is TypeOfExpression -> findTypeParamRefsInExpr(expr.expression, typeParamNames, source, fileName)
            is VoidExpression -> findTypeParamRefsInExpr(expr.expression, typeParamNames, source, fileName)
            is AwaitExpression -> findTypeParamRefsInExpr(expr.expression, typeParamNames, source, fileName)
            else -> {} // literals, identifiers, etc.
        }
    }

    private fun emitTS2302(nameNode: Identifier, source: String, fileName: String) {
        val start = nameNode.pos
        val length = nameNode.text.length
        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
        checker.diagnostics.add(Diagnostic(
            message = "Static members cannot reference class type parameters.",
            category = DiagnosticCategory.Error,
            code = 2302,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }
}
