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

package com.xemantic.typescript.compiler.kir.ast

/**
 * Calls [action] on each child of [node], in source order — `-core`'s `forEachChild` ((TSGO.7-f)), dispatched on
 * the node's class.
 */
public fun forEachChild(node: Node, action: (Node) -> Unit) {
    when (node) {
        is Identifier -> {}
        is PropertyAccessExpression -> { action(node.expression); action(node.name) }
        is CallExpression -> {
            action(node.expression)
            walkList(node.typeArguments, action)
            walkList(node.arguments, action)
        }
        is StringLiteralNode -> {}
        is NumericLiteralNode -> {}
        is BinaryExpression -> { action(node.left); action(node.right) }
        is ExpressionStatement -> { action(node.expression) }
        is Block -> { walkList(node.statements, action) }
        is VariableDeclaration -> {
            action(node.name)
            node.type?.let(action)
            node.initializer?.let(action)
        }
        is VariableDeclarationList -> { walkList(node.declarations, action) }
        is VariableStatement -> { action(node.declarationList) }
        is EmptyStatement -> {}
        is IfStatement -> {
            action(node.expression)
            action(node.thenStatement)
            node.elseStatement?.let(action)
        }
        is DoStatement -> { action(node.statement); action(node.expression) }
        is WhileStatement -> { action(node.expression); action(node.statement) }
        is ForStatement -> {
            node.initializer?.let(action)
            node.condition?.let(action)
            node.incrementor?.let(action)
            action(node.statement)
        }
        is ForInStatement -> {
            action(node.initializer)
            action(node.expression)
            action(node.statement)
        }
        is ForOfStatement -> {
            action(node.initializer)
            action(node.expression)
            action(node.statement)
        }
        is ContinueStatement -> { node.label?.let(action) }
        is BreakStatement -> { node.label?.let(action) }
        is ReturnStatement -> { node.expression?.let(action) }
        is WithStatement -> { action(node.expression); action(node.statement) }
        is SwitchStatement -> {
            action(node.expression)
            walkList(node.caseBlock, action)
        }
        is LabeledStatement -> { action(node.label); action(node.statement) }
        is ThrowStatement -> { node.expression?.let(action) }
        is TryStatement -> {
            action(node.tryBlock)
            node.catchClause?.let(action)
            node.finallyBlock?.let(action)
        }
        is DebuggerStatement -> {}
        is NotEmittedStatement -> {}
        is RawStatement -> {}
        is FunctionDeclaration -> {
            node.name?.let(action)
            walkList(node.typeParameters, action)
            walkList(node.parameters, action)
            node.type?.let(action)
            node.body?.let(action)
        }
        is ClassDeclaration -> {
            node.name?.let(action)
            walkList(node.typeParameters, action)
            walkList(node.heritageClauses, action)
            walkList(node.members, action)
            walkList(node.decorators, action)
        }
        is InterfaceDeclaration -> {
            action(node.name)
            walkList(node.typeParameters, action)
            walkList(node.heritageClauses, action)
            walkList(node.members, action)
        }
        is TypeAliasDeclaration -> {
            action(node.name)
            walkList(node.typeParameters, action)
            action(node.type)
        }
        is EnumDeclaration -> {
            action(node.name)
            walkList(node.members, action)
        }
        is ModuleDeclaration -> {
            action(node.name)
            node.body?.let(action)
        }
        is ImportDeclaration -> {
            node.importClause?.let(action)
            action(node.moduleSpecifier)
        }
        is ImportEqualsDeclaration -> { action(node.name); action(node.moduleReference) }
        is ExportDeclaration -> {
            node.exportClause?.let(action)
            node.moduleSpecifier?.let(action)
        }
        is ExportAssignment -> {
            action(node.expression)
            node.type?.let(action)
        }
        is BigIntLiteralNode -> {}
        is RegularExpressionLiteralNode -> {}
        is NoSubstitutionTemplateLiteralNode -> {}
        is TemplateExpression -> {
            action(node.head)
            walkList(node.templateSpans, action)
        }
        is ArrayLiteralExpression -> { walkList(node.elements, action) }
        is ObjectLiteralExpression -> { walkList(node.properties, action) }
        is ElementAccessExpression -> { action(node.expression); action(node.argumentExpression) }
        is NewExpression -> {
            action(node.expression)
            walkList(node.typeArguments, action)
            walkList(node.arguments, action)
            walkList(node.leadingTypeArguments, action)
        }
        is TaggedTemplateExpression -> {
            action(node.tag)
            walkList(node.typeArguments, action)
            action(node.template)
        }
        is TypeAssertionExpression -> { action(node.type); action(node.expression) }
        is ParenthesizedExpression -> {
            action(node.expression)
            node.jsdocCastType?.let(action)
        }
        is FunctionExpression -> {
            node.name?.let(action)
            walkList(node.typeParameters, action)
            walkList(node.parameters, action)
            node.type?.let(action)
            action(node.body)
        }
        is ArrowFunction -> {
            walkList(node.typeParameters, action)
            walkList(node.parameters, action)
            node.type?.let(action)
            action(node.body)
        }
        is DeleteExpression -> { action(node.expression) }
        is TypeOfExpression -> { action(node.expression) }
        is VoidExpression -> { action(node.expression) }
        is AwaitExpression -> { action(node.expression) }
        is PrefixUnaryExpression -> { action(node.operand) }
        is PostfixUnaryExpression -> { action(node.operand) }
        is ConditionalExpression -> {
            action(node.condition)
            action(node.whenTrue)
            action(node.whenFalse)
        }
        is YieldExpression -> { node.expression?.let(action) }
        is SpreadElement -> { action(node.expression) }
        is ClassExpression -> {
            node.name?.let(action)
            walkList(node.typeParameters, action)
            walkList(node.heritageClauses, action)
            walkList(node.members, action)
            walkList(node.decorators, action)
        }
        is AsExpression -> { action(node.expression); action(node.type) }
        is NonNullExpression -> { action(node.expression) }
        is SatisfiesExpression -> { action(node.expression); action(node.type) }
        is MetaProperty -> { action(node.name) }
        is OmittedExpression -> {}
        is CommaListExpression -> { walkList(node.elements, action) }
        is SourceFile -> { walkList(node.statements, action) }
        is TemplateSpan -> { action(node.expression); action(node.literal) }
        is KeywordTypeNode -> {}
        is TypeReference -> {
            action(node.typeName)
            walkList(node.typeArguments, action)
        }
        is PropertyDeclaration -> {
            action(node.name)
            node.type?.let(action)
            node.initializer?.let(action)
            walkList(node.decorators, action)
        }
        is MethodDeclaration -> {
            action(node.name)
            walkList(node.typeParameters, action)
            walkList(node.parameters, action)
            node.type?.let(action)
            node.body?.let(action)
            walkList(node.decorators, action)
        }
        is Constructor -> {
            walkList(node.parameters, action)
            node.body?.let(action)
            walkList(node.decorators, action)
        }
        is GetAccessor -> {
            action(node.name)
            walkList(node.parameters, action)
            node.type?.let(action)
            node.body?.let(action)
            walkList(node.decorators, action)
        }
        is SetAccessor -> {
            action(node.name)
            walkList(node.parameters, action)
            node.type?.let(action)
            node.body?.let(action)
            walkList(node.decorators, action)
        }
        is IndexSignature -> {
            walkList(node.parameters, action)
            node.type?.let(action)
        }
        is SemicolonClassElement -> {}
        is ClassStaticBlockDeclaration -> { action(node.body) }
        is FunctionType -> {
            walkList(node.typeParameters, action)
            walkList(node.parameters, action)
            action(node.type)
        }
        is ConstructorType -> {
            walkList(node.typeParameters, action)
            walkList(node.parameters, action)
            action(node.type)
        }
        is TypeQuery -> {
            action(node.exprName)
            walkList(node.typeArguments, action)
        }
        is TypeLiteral -> { walkList(node.members, action) }
        is ArrayType -> { action(node.elementType) }
        is TupleType -> { walkList(node.elements, action) }
        is UnionType -> { walkList(node.types, action) }
        is IntersectionType -> { walkList(node.types, action) }
        is ConditionalType -> {
            action(node.checkType)
            action(node.extendsType)
            action(node.trueType)
            action(node.falseType)
        }
        is IndexedAccessType -> { action(node.objectType); action(node.indexType) }
        is MappedType -> {
            action(node.typeParameter)
            node.nameType?.let(action)
            node.type?.let(action)
        }
        is LiteralType -> { action(node.literal) }
        is TemplateLiteralType -> {
            action(node.head)
            walkList(node.templateSpans, action)
        }
        is TemplateLiteralTypeSpan -> { action(node.type); action(node.literal) }
        is ParenthesizedType -> { action(node.type) }
        is TypePredicate -> {
            action(node.parameterName)
            node.type?.let(action)
        }
        is TypeOperator -> { action(node.type) }
        is RestType -> { action(node.type) }
        is NamedTupleMember -> { action(node.name); action(node.type) }
        is OptionalType -> { action(node.type) }
        is ImportType -> {
            action(node.argument)
            node.qualifier?.let(action)
            walkList(node.typeArguments, action)
        }
        is ThisType -> {}
        is InferType -> { action(node.typeParameter) }
        is Parameter -> {
            action(node.name)
            node.type?.let(action)
            node.initializer?.let(action)
            walkList(node.decorators, action)
        }
        is Decorator -> { action(node.expression) }
        is HeritageClause -> { walkList(node.types, action) }
        is ExpressionWithTypeArguments -> {
            action(node.expression)
            walkList(node.typeArguments, action)
        }
        is EnumMember -> {
            action(node.name)
            node.initializer?.let(action)
        }
        is TypeParameter -> {
            action(node.name)
            node.constraint?.let(action)
            node.default?.let(action)
        }
        is QualifiedName -> { action(node.left); action(node.right) }
        is PropertyAssignment -> { action(node.name); action(node.initializer) }
        is ShorthandPropertyAssignment -> {
            action(node.name)
            node.objectAssignmentInitializer?.let(action)
        }
        is SpreadAssignment -> { action(node.expression) }
        is ComputedPropertyName -> { action(node.expression) }
        is ObjectBindingPattern -> { walkList(node.elements, action) }
        is ArrayBindingPattern -> { walkList(node.elements, action) }
        is BindingElement -> {
            node.propertyName?.let(action)
            action(node.name)
            node.initializer?.let(action)
        }
        is CaseClause -> {
            action(node.expression)
            walkList(node.statements, action)
        }
        is DefaultClause -> { walkList(node.statements, action) }
        is CatchClause -> {
            node.variableDeclaration?.let(action)
            action(node.block)
        }
        is ModuleBlock -> { walkList(node.statements, action) }
        is NamespaceImport -> { action(node.name) }
        is NamedImports -> { walkList(node.elements, action) }
        is ImportSpecifier -> {
            node.propertyName?.let(action)
            action(node.name)
        }
        is NamespaceExport -> { action(node.name) }
        is NamedExports -> { walkList(node.elements, action) }
        is ExportSpecifier -> {
            node.propertyName?.let(action)
            action(node.name)
        }
        is ImportClause -> {
            node.name?.let(action)
            node.namedBindings?.let(action)
        }
        is ExternalModuleReference -> { action(node.expression) }
        is JsxAttribute -> { node.value?.let(action) }
        is JsxSpreadAttribute -> { action(node.expression) }
        is JsxOpeningElement -> {
            action(node.tagName)
            walkList(node.attributes, action)
        }
        is JsxClosingElement -> { action(node.tagName) }
        is JsxElement -> {
            action(node.openingElement)
            walkList(node.children, action)
            action(node.closingElement)
        }
        is JsxSelfClosingElement -> {
            action(node.tagName)
            walkList(node.attributes, action)
        }
        is JsxText -> {}
        is JsxExpressionContainer -> { node.expression?.let(action) }
        is JsxFragment -> { walkList(node.children, action) }
    }
}

private fun walkList(xs: List<Node>?, action: (Node) -> Unit) {
    if (xs == null) return
    for (x in xs) action(x)
}

/**
 * Stamps every node's [NodeBase.parent], as `-core`'s parser did at the end of a parse. Iterative: a 10k-term
 * binary chain is ordinary in real code, and a recursive walk would overflow.
 */
public fun indexSourceFile(sourceFile: SourceFile) {
    sourceFile.parent = null
    val stack = ArrayList<Node>(64)
    stack.add(sourceFile)
    while (stack.isNotEmpty()) {
        val node = stack.removeAt(stack.size - 1)
        forEachChild(node) { child ->
            (child as NodeBase).parent = node
            stack.add(child)
        }
    }
}
