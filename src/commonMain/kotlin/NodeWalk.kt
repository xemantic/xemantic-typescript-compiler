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
 * INV.2(a): the canonical generic child enumeration — invokes [action] for every
 * DIRECT child node of [node] (tsc's `forEachChild` equivalent).
 *
 * Coverage contract: every `Node`-typed (or `List`-of-`Node`) PRIMARY-CONSTRUCTOR
 * property of every node class, in property declaration order; [Comment] lists and
 * non-node properties are not children. The `when` is exhaustive over the sealed
 * [Node] hierarchy, so a NEW node class fails compilation until added here; a new
 * node-typed PROPERTY on an existing class is pinned by the jvmTest reflection
 * oracle (`ForEachChildOracleTest`), which diffs this enumeration against the
 * data-class properties — a missed child position would otherwise silently exempt
 * a whole subtree from indexing/walking (cf. the MappedType-constraint gotcha).
 *
 * Note [MappedType.typeParameter] is visited as a child and [TypeParameter]'s own
 * children include its `constraint` — a generic walk reaches mapped-type
 * constraints without the per-walker special case.
 */
fun forEachChild(node: Node, action: (Node) -> Unit) {
    when (node) {
        // ── hot kinds first (the when compiles to a sequential instanceof chain) ──
        is Identifier -> {}
        is PropertyAccessExpression -> { action(node.expression); action(node.name) }
        is CallExpression -> {
            action(node.expression)
            node.typeArguments?.forEach(action)
            node.arguments.forEach(action)
        }
        is StringLiteralNode -> {}
        is NumericLiteralNode -> {}
        is BinaryExpression -> { action(node.left); action(node.right) }
        is KeywordTypeNode -> {}
        is TypeReference -> {
            action(node.typeName)
            node.typeArguments?.forEach(action)
        }
        is Parameter -> {
            action(node.name)
            node.type?.let(action)
            node.initializer?.let(action)
            node.decorators?.forEach(action)
        }
        is ExpressionStatement -> action(node.expression)
        is Block -> node.statements.forEach(action)
        is VariableDeclaration -> {
            action(node.name)
            node.type?.let(action)
            node.initializer?.let(action)
        }
        is VariableDeclarationList -> node.declarations.forEach(action)
        is VariableStatement -> action(node.declarationList)

        // ── statements ──
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
        is ContinueStatement -> node.label?.let(action)
        is BreakStatement -> node.label?.let(action)
        is ReturnStatement -> node.expression?.let(action)
        is WithStatement -> { action(node.expression); action(node.statement) }
        is SwitchStatement -> {
            action(node.expression)
            node.caseBlock.forEach(action)
        }
        is LabeledStatement -> { action(node.label); action(node.statement) }
        is ThrowStatement -> node.expression?.let(action)
        is TryStatement -> {
            action(node.tryBlock)
            node.catchClause?.let(action)
            node.finallyBlock?.let(action)
        }
        is DebuggerStatement -> {}
        is NotEmittedStatement -> {}
        is RawStatement -> {}

        // ── declarations ──
        is FunctionDeclaration -> {
            node.name?.let(action)
            node.typeParameters?.forEach(action)
            node.parameters.forEach(action)
            node.type?.let(action)
            node.body?.let(action)
        }
        is ClassDeclaration -> {
            node.name?.let(action)
            node.typeParameters?.forEach(action)
            node.heritageClauses?.forEach(action)
            node.members.forEach(action)
            node.decorators?.forEach(action)
        }
        is InterfaceDeclaration -> {
            action(node.name)
            node.typeParameters?.forEach(action)
            node.heritageClauses?.forEach(action)
            node.members.forEach(action)
        }
        is TypeAliasDeclaration -> {
            action(node.name)
            node.typeParameters?.forEach(action)
            action(node.type)
        }
        is EnumDeclaration -> {
            action(node.name)
            node.members.forEach(action)
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

        // ── expressions ──
        is BigIntLiteralNode -> {}
        is RegularExpressionLiteralNode -> {}
        is NoSubstitutionTemplateLiteralNode -> {}
        is TemplateExpression -> {
            action(node.head)
            node.templateSpans.forEach(action)
        }
        is ArrayLiteralExpression -> node.elements.forEach(action)
        is ObjectLiteralExpression -> node.properties.forEach(action)
        is ElementAccessExpression -> { action(node.expression); action(node.argumentExpression) }
        is NewExpression -> {
            action(node.expression)
            node.typeArguments?.forEach(action)
            node.arguments?.forEach(action)
            node.leadingTypeArguments?.forEach(action)
        }
        is TaggedTemplateExpression -> {
            action(node.tag)
            node.typeArguments?.forEach(action)
            action(node.template)
        }
        is TypeAssertionExpression -> { action(node.type); action(node.expression) }
        is ParenthesizedExpression -> {
            action(node.expression)
            node.jsdocCastType?.let(action)
        }
        is FunctionExpression -> {
            node.name?.let(action)
            node.typeParameters?.forEach(action)
            node.parameters.forEach(action)
            node.type?.let(action)
            action(node.body)
        }
        is ArrowFunction -> {
            node.typeParameters?.forEach(action)
            node.parameters.forEach(action)
            node.type?.let(action)
            action(node.body)
        }
        is DeleteExpression -> action(node.expression)
        is TypeOfExpression -> action(node.expression)
        is VoidExpression -> action(node.expression)
        is AwaitExpression -> action(node.expression)
        is PrefixUnaryExpression -> action(node.operand)
        is PostfixUnaryExpression -> action(node.operand)
        is ConditionalExpression -> {
            action(node.condition)
            action(node.whenTrue)
            action(node.whenFalse)
        }
        is YieldExpression -> node.expression?.let(action)
        is SpreadElement -> action(node.expression)
        is ClassExpression -> {
            node.name?.let(action)
            node.typeParameters?.forEach(action)
            node.heritageClauses?.forEach(action)
            node.members.forEach(action)
            node.decorators?.forEach(action)
        }
        is AsExpression -> { action(node.expression); action(node.type) }
        is NonNullExpression -> action(node.expression)
        is SatisfiesExpression -> { action(node.expression); action(node.type) }
        is MetaProperty -> action(node.name)
        is OmittedExpression -> {}
        is CommaListExpression -> node.elements.forEach(action)

        // ── class elements ──
        is PropertyDeclaration -> {
            action(node.name)
            node.type?.let(action)
            node.initializer?.let(action)
            node.decorators?.forEach(action)
        }
        is MethodDeclaration -> {
            action(node.name)
            node.typeParameters?.forEach(action)
            node.parameters.forEach(action)
            node.type?.let(action)
            node.body?.let(action)
            node.decorators?.forEach(action)
        }
        is Constructor -> {
            node.parameters.forEach(action)
            node.body?.let(action)
            node.decorators?.forEach(action)
        }
        is GetAccessor -> {
            action(node.name)
            node.parameters.forEach(action)
            node.type?.let(action)
            node.body?.let(action)
            node.decorators?.forEach(action)
        }
        is SetAccessor -> {
            action(node.name)
            node.parameters.forEach(action)
            node.type?.let(action)
            node.body?.let(action)
            node.decorators?.forEach(action)
        }
        is IndexSignature -> {
            node.parameters.forEach(action)
            node.type?.let(action)
        }
        is SemicolonClassElement -> {}
        is ClassStaticBlockDeclaration -> action(node.body)

        // ── type nodes ──
        is FunctionType -> {
            node.typeParameters?.forEach(action)
            node.parameters.forEach(action)
            action(node.type)
        }
        is ConstructorType -> {
            node.typeParameters?.forEach(action)
            node.parameters.forEach(action)
            action(node.type)
        }
        is TypeQuery -> {
            action(node.exprName)
            node.typeArguments?.forEach(action)
        }
        is TypeLiteral -> node.members.forEach(action)
        is ArrayType -> action(node.elementType)
        is TupleType -> node.elements.forEach(action)
        is UnionType -> node.types.forEach(action)
        is IntersectionType -> node.types.forEach(action)
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
        is LiteralType -> action(node.literal)
        is TemplateLiteralType -> {
            action(node.head)
            node.templateSpans.forEach(action)
        }
        is TemplateLiteralTypeSpan -> { action(node.type); action(node.literal) }
        is ParenthesizedType -> action(node.type)
        is TypePredicate -> {
            action(node.parameterName)
            node.type?.let(action)
        }
        is TypeOperator -> action(node.type)
        is RestType -> action(node.type)
        is NamedTupleMember -> { action(node.name); action(node.type) }
        is OptionalType -> action(node.type)
        is ImportType -> {
            action(node.argument)
            node.qualifier?.let(action)
            node.typeArguments?.forEach(action)
        }
        is ThisType -> {}
        is InferType -> action(node.typeParameter)

        // ── supporting nodes ──
        is SourceFile -> node.statements.forEach(action)
        is TemplateSpan -> { action(node.expression); action(node.literal) }
        is Decorator -> action(node.expression)
        is HeritageClause -> node.types.forEach(action)
        is ExpressionWithTypeArguments -> {
            action(node.expression)
            node.typeArguments?.forEach(action)
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
        is SpreadAssignment -> action(node.expression)
        is ComputedPropertyName -> action(node.expression)
        is ObjectBindingPattern -> node.elements.forEach(action)
        is ArrayBindingPattern -> node.elements.forEach(action)
        is BindingElement -> {
            node.propertyName?.let(action)
            action(node.name)
            node.initializer?.let(action)
        }
        is CaseClause -> {
            action(node.expression)
            node.statements.forEach(action)
        }
        is DefaultClause -> node.statements.forEach(action)
        is CatchClause -> {
            node.variableDeclaration?.let(action)
            action(node.block)
        }
        is ModuleBlock -> node.statements.forEach(action)
        is NamespaceImport -> action(node.name)
        is NamedImports -> node.elements.forEach(action)
        is ImportSpecifier -> {
            node.propertyName?.let(action)
            action(node.name)
        }
        is NamespaceExport -> action(node.name)
        is NamedExports -> node.elements.forEach(action)
        is ExportSpecifier -> {
            node.propertyName?.let(action)
            action(node.name)
        }
        is ImportClause -> {
            node.name?.let(action)
            node.namedBindings?.let(action)
        }
        is ExternalModuleReference -> action(node.expression)

        // ── JSX ──
        is JsxAttribute -> node.value?.let(action)
        is JsxSpreadAttribute -> action(node.expression)
        is JsxOpeningElement -> {
            action(node.tagName)
            node.attributes.forEach(action)
        }
        is JsxClosingElement -> action(node.tagName)
        is JsxElement -> {
            action(node.openingElement)
            node.children.forEach(action)
            action(node.closingElement)
        }
        is JsxSelfClosingElement -> {
            action(node.tagName)
            node.attributes.forEach(action)
        }
        is JsxText -> {}
        is JsxExpressionContainer -> node.expression?.let(action)
        is JsxFragment -> node.children.forEach(action)
    }
}

/**
 * INV.2(a): stamps dense per-file identity onto a freshly parsed tree — preorder
 * `nodeId`s (SourceFile = 0), `parent` links, and [SourceFile.nodeCount] — via
 * [NodeBase]'s inert `var`s. Invoked at the end of [Parser.parse]; behavior-free
 * until a consumer reads the fields (INV.2(b)+). Per-file confined: no global
 * state, so concurrent crawls indexing different files never interact.
 *
 * ITERATIVE (explicit stack) by project rule: parses can run OFF the deep-stack
 * thread (the INV.1 crawl parses on Dispatchers.Default), and 10k-term binary
 * chains exist (DeepExpressionChainTest) — a recursive walk would overflow.
 * Children are pushed in reverse so pop order is exact preorder (a subtree
 * occupies a contiguous id range).
 */
fun indexSourceFile(sourceFile: SourceFile) {
    sourceFile.nodeId = 0
    sourceFile.parent = null
    var nextId = 1
    val stack = ArrayList<Node>(64)
    val buf = ArrayList<Node>(16)
    val collect: (Node) -> Unit = { buf.add(it) }
    forEachChild(sourceFile, collect)
    for (i in buf.indices.reversed()) {
        (buf[i] as NodeBase).parent = sourceFile
        stack.add(buf[i])
    }
    while (stack.isNotEmpty()) {
        val node = stack.removeAt(stack.size - 1)
        (node as NodeBase).nodeId = nextId++
        // M0 census (inert off --passTiming): node-kind histogram for the
        // dispatch-order / kind-table design.
        if (PassTiming.enabled) PassTiming.noteNodeKind(node)
        buf.clear()
        forEachChild(node, collect)
        for (i in buf.indices.reversed()) {
            (buf[i] as NodeBase).parent = node
            stack.add(buf[i])
        }
    }
    sourceFile.nodeCount = nextId
}

/**
 * INV.3(c)(i): the [SourceFile] owning [node], via the INV.2(a) parent chain
 * stamped by [indexSourceFile]. Null for an UNINDEXED node — a data-class
 * `copy()` / Transformer-synthesized node (their `parent` links are never
 * stamped) or any detached subtree — so callers can degrade to their legacy
 * behavior. The hop bound is a defensive guard: stamped chains are acyclic by
 * construction (preorder over a tree), but a corrupted link must not hang the
 * lookup.
 */
fun owningSourceFile(node: Node): SourceFile? {
    var cur: Node? = node
    var hops = 0
    while (cur != null && hops++ < 4096) {
        if (cur is SourceFile) return cur
        cur = (cur as NodeBase).parent
    }
    return null
}
