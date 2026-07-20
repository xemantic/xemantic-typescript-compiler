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
 * non-node properties are not children. M0.2: dispatch is a dense tableswitch on
 * the stamped [NodeBase.kindId] (NOT a compile-exhaustive `when(node)` anymore) —
 * the compile gate for a NEW node class is [nodeKindIdOf]'s sealed-exhaustive
 * `when` (which forces a [NodeKind] const, whose arm must then be added HERE —
 * pinned by the jvmTest reflection oracle below and the loud `else`); a new
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
    when ((node as NodeBase).kindId) {
        // ── grouping is cosmetic: a dense when(Int) compiles to one tableswitch ──
        NodeKind.IDENTIFIER -> {}
        NodeKind.PROPERTY_ACCESS_EXPRESSION -> { node as PropertyAccessExpression; action(node.expression); action(node.name) }
        NodeKind.CALL_EXPRESSION -> {
            node as CallExpression
            action(node.expression)
            node.typeArguments?.forEach(action)
            node.arguments.forEach(action)
        }
        NodeKind.STRING_LITERAL_NODE -> {}
        NodeKind.NUMERIC_LITERAL_NODE -> {}
        NodeKind.BINARY_EXPRESSION -> { node as BinaryExpression; action(node.left); action(node.right) }
        NodeKind.KEYWORD_TYPE_NODE -> {}
        NodeKind.TYPE_REFERENCE -> {
            node as TypeReference
            action(node.typeName)
            node.typeArguments?.forEach(action)
        }
        NodeKind.PARAMETER -> {
            node as Parameter
            action(node.name)
            node.type?.let(action)
            node.initializer?.let(action)
            node.decorators?.forEach(action)
        }
        NodeKind.EXPRESSION_STATEMENT -> { node as ExpressionStatement; action(node.expression) }
        NodeKind.BLOCK -> { node as Block; node.statements.forEach(action) }
        NodeKind.VARIABLE_DECLARATION -> {
            node as VariableDeclaration
            action(node.name)
            node.type?.let(action)
            node.initializer?.let(action)
        }
        NodeKind.VARIABLE_DECLARATION_LIST -> { node as VariableDeclarationList; node.declarations.forEach(action) }
        NodeKind.VARIABLE_STATEMENT -> { node as VariableStatement; action(node.declarationList) }

        // ── statements ──
        NodeKind.EMPTY_STATEMENT -> {}
        NodeKind.IF_STATEMENT -> {
            node as IfStatement
            action(node.expression)
            action(node.thenStatement)
            node.elseStatement?.let(action)
        }
        NodeKind.DO_STATEMENT -> { node as DoStatement; action(node.statement); action(node.expression) }
        NodeKind.WHILE_STATEMENT -> { node as WhileStatement; action(node.expression); action(node.statement) }
        NodeKind.FOR_STATEMENT -> {
            node as ForStatement
            node.initializer?.let(action)
            node.condition?.let(action)
            node.incrementor?.let(action)
            action(node.statement)
        }
        NodeKind.FOR_IN_STATEMENT -> {
            node as ForInStatement
            action(node.initializer)
            action(node.expression)
            action(node.statement)
        }
        NodeKind.FOR_OF_STATEMENT -> {
            node as ForOfStatement
            action(node.initializer)
            action(node.expression)
            action(node.statement)
        }
        NodeKind.CONTINUE_STATEMENT -> { node as ContinueStatement; node.label?.let(action) }
        NodeKind.BREAK_STATEMENT -> { node as BreakStatement; node.label?.let(action) }
        NodeKind.RETURN_STATEMENT -> { node as ReturnStatement; node.expression?.let(action) }
        NodeKind.WITH_STATEMENT -> { node as WithStatement; action(node.expression); action(node.statement) }
        NodeKind.SWITCH_STATEMENT -> {
            node as SwitchStatement
            action(node.expression)
            node.caseBlock.forEach(action)
        }
        NodeKind.LABELED_STATEMENT -> { node as LabeledStatement; action(node.label); action(node.statement) }
        NodeKind.THROW_STATEMENT -> { node as ThrowStatement; node.expression?.let(action) }
        NodeKind.TRY_STATEMENT -> {
            node as TryStatement
            action(node.tryBlock)
            node.catchClause?.let(action)
            node.finallyBlock?.let(action)
        }
        NodeKind.DEBUGGER_STATEMENT -> {}
        NodeKind.NOT_EMITTED_STATEMENT -> {}
        NodeKind.RAW_STATEMENT -> {}

        // ── declarations ──
        NodeKind.FUNCTION_DECLARATION -> {
            node as FunctionDeclaration
            node.name?.let(action)
            node.typeParameters?.forEach(action)
            node.parameters.forEach(action)
            node.type?.let(action)
            node.body?.let(action)
        }
        NodeKind.CLASS_DECLARATION -> {
            node as ClassDeclaration
            node.name?.let(action)
            node.typeParameters?.forEach(action)
            node.heritageClauses?.forEach(action)
            node.members.forEach(action)
            node.decorators?.forEach(action)
        }
        NodeKind.INTERFACE_DECLARATION -> {
            node as InterfaceDeclaration
            action(node.name)
            node.typeParameters?.forEach(action)
            node.heritageClauses?.forEach(action)
            node.members.forEach(action)
        }
        NodeKind.TYPE_ALIAS_DECLARATION -> {
            node as TypeAliasDeclaration
            action(node.name)
            node.typeParameters?.forEach(action)
            action(node.type)
        }
        NodeKind.ENUM_DECLARATION -> {
            node as EnumDeclaration
            action(node.name)
            node.members.forEach(action)
        }
        NodeKind.MODULE_DECLARATION -> {
            node as ModuleDeclaration
            action(node.name)
            node.body?.let(action)
        }
        NodeKind.IMPORT_DECLARATION -> {
            node as ImportDeclaration
            node.importClause?.let(action)
            action(node.moduleSpecifier)
        }
        NodeKind.IMPORT_EQUALS_DECLARATION -> { node as ImportEqualsDeclaration; action(node.name); action(node.moduleReference) }
        NodeKind.EXPORT_DECLARATION -> {
            node as ExportDeclaration
            node.exportClause?.let(action)
            node.moduleSpecifier?.let(action)
        }
        NodeKind.EXPORT_ASSIGNMENT -> {
            node as ExportAssignment
            action(node.expression)
            node.type?.let(action)
        }

        // ── expressions ──
        NodeKind.BIG_INT_LITERAL_NODE -> {}
        NodeKind.REGULAR_EXPRESSION_LITERAL_NODE -> {}
        NodeKind.NO_SUBSTITUTION_TEMPLATE_LITERAL_NODE -> {}
        NodeKind.TEMPLATE_EXPRESSION -> {
            node as TemplateExpression
            action(node.head)
            node.templateSpans.forEach(action)
        }
        NodeKind.ARRAY_LITERAL_EXPRESSION -> { node as ArrayLiteralExpression; node.elements.forEach(action) }
        NodeKind.OBJECT_LITERAL_EXPRESSION -> { node as ObjectLiteralExpression; node.properties.forEach(action) }
        NodeKind.ELEMENT_ACCESS_EXPRESSION -> { node as ElementAccessExpression; action(node.expression); action(node.argumentExpression) }
        NodeKind.NEW_EXPRESSION -> {
            node as NewExpression
            action(node.expression)
            node.typeArguments?.forEach(action)
            node.arguments?.forEach(action)
            node.leadingTypeArguments?.forEach(action)
        }
        NodeKind.TAGGED_TEMPLATE_EXPRESSION -> {
            node as TaggedTemplateExpression
            action(node.tag)
            node.typeArguments?.forEach(action)
            action(node.template)
        }
        NodeKind.TYPE_ASSERTION_EXPRESSION -> { node as TypeAssertionExpression; action(node.type); action(node.expression) }
        NodeKind.PARENTHESIZED_EXPRESSION -> {
            node as ParenthesizedExpression
            action(node.expression)
            node.jsdocCastType?.let(action)
        }
        NodeKind.FUNCTION_EXPRESSION -> {
            node as FunctionExpression
            node.name?.let(action)
            node.typeParameters?.forEach(action)
            node.parameters.forEach(action)
            node.type?.let(action)
            action(node.body)
        }
        NodeKind.ARROW_FUNCTION -> {
            node as ArrowFunction
            node.typeParameters?.forEach(action)
            node.parameters.forEach(action)
            node.type?.let(action)
            action(node.body)
        }
        NodeKind.DELETE_EXPRESSION -> { node as DeleteExpression; action(node.expression) }
        NodeKind.TYPE_OF_EXPRESSION -> { node as TypeOfExpression; action(node.expression) }
        NodeKind.VOID_EXPRESSION -> { node as VoidExpression; action(node.expression) }
        NodeKind.AWAIT_EXPRESSION -> { node as AwaitExpression; action(node.expression) }
        NodeKind.PREFIX_UNARY_EXPRESSION -> { node as PrefixUnaryExpression; action(node.operand) }
        NodeKind.POSTFIX_UNARY_EXPRESSION -> { node as PostfixUnaryExpression; action(node.operand) }
        NodeKind.CONDITIONAL_EXPRESSION -> {
            node as ConditionalExpression
            action(node.condition)
            action(node.whenTrue)
            action(node.whenFalse)
        }
        NodeKind.YIELD_EXPRESSION -> { node as YieldExpression; node.expression?.let(action) }
        NodeKind.SPREAD_ELEMENT -> { node as SpreadElement; action(node.expression) }
        NodeKind.CLASS_EXPRESSION -> {
            node as ClassExpression
            node.name?.let(action)
            node.typeParameters?.forEach(action)
            node.heritageClauses?.forEach(action)
            node.members.forEach(action)
            node.decorators?.forEach(action)
        }
        NodeKind.AS_EXPRESSION -> { node as AsExpression; action(node.expression); action(node.type) }
        NodeKind.NON_NULL_EXPRESSION -> { node as NonNullExpression; action(node.expression) }
        NodeKind.SATISFIES_EXPRESSION -> { node as SatisfiesExpression; action(node.expression); action(node.type) }
        NodeKind.META_PROPERTY -> { node as MetaProperty; action(node.name) }
        NodeKind.OMITTED_EXPRESSION -> {}
        NodeKind.COMMA_LIST_EXPRESSION -> { node as CommaListExpression; node.elements.forEach(action) }

        // ── class elements ──
        NodeKind.PROPERTY_DECLARATION -> {
            node as PropertyDeclaration
            action(node.name)
            node.type?.let(action)
            node.initializer?.let(action)
            node.decorators?.forEach(action)
        }
        NodeKind.METHOD_DECLARATION -> {
            node as MethodDeclaration
            action(node.name)
            node.typeParameters?.forEach(action)
            node.parameters.forEach(action)
            node.type?.let(action)
            node.body?.let(action)
            node.decorators?.forEach(action)
        }
        NodeKind.CONSTRUCTOR -> {
            node as Constructor
            node.parameters.forEach(action)
            node.body?.let(action)
            node.decorators?.forEach(action)
        }
        NodeKind.GET_ACCESSOR -> {
            node as GetAccessor
            action(node.name)
            node.parameters.forEach(action)
            node.type?.let(action)
            node.body?.let(action)
            node.decorators?.forEach(action)
        }
        NodeKind.SET_ACCESSOR -> {
            node as SetAccessor
            action(node.name)
            node.parameters.forEach(action)
            node.type?.let(action)
            node.body?.let(action)
            node.decorators?.forEach(action)
        }
        NodeKind.INDEX_SIGNATURE -> {
            node as IndexSignature
            node.parameters.forEach(action)
            node.type?.let(action)
        }
        NodeKind.SEMICOLON_CLASS_ELEMENT -> {}
        NodeKind.CLASS_STATIC_BLOCK_DECLARATION -> { node as ClassStaticBlockDeclaration; action(node.body) }

        // ── type nodes ──
        NodeKind.FUNCTION_TYPE -> {
            node as FunctionType
            node.typeParameters?.forEach(action)
            node.parameters.forEach(action)
            action(node.type)
        }
        NodeKind.CONSTRUCTOR_TYPE -> {
            node as ConstructorType
            node.typeParameters?.forEach(action)
            node.parameters.forEach(action)
            action(node.type)
        }
        NodeKind.TYPE_QUERY -> {
            node as TypeQuery
            action(node.exprName)
            node.typeArguments?.forEach(action)
        }
        NodeKind.TYPE_LITERAL -> { node as TypeLiteral; node.members.forEach(action) }
        NodeKind.ARRAY_TYPE -> { node as ArrayType; action(node.elementType) }
        NodeKind.TUPLE_TYPE -> { node as TupleType; node.elements.forEach(action) }
        NodeKind.UNION_TYPE -> { node as UnionType; node.types.forEach(action) }
        NodeKind.INTERSECTION_TYPE -> { node as IntersectionType; node.types.forEach(action) }
        NodeKind.CONDITIONAL_TYPE -> {
            node as ConditionalType
            action(node.checkType)
            action(node.extendsType)
            action(node.trueType)
            action(node.falseType)
        }
        NodeKind.INDEXED_ACCESS_TYPE -> { node as IndexedAccessType; action(node.objectType); action(node.indexType) }
        NodeKind.MAPPED_TYPE -> {
            node as MappedType
            action(node.typeParameter)
            node.nameType?.let(action)
            node.type?.let(action)
        }
        NodeKind.LITERAL_TYPE -> { node as LiteralType; action(node.literal) }
        NodeKind.TEMPLATE_LITERAL_TYPE -> {
            node as TemplateLiteralType
            action(node.head)
            node.templateSpans.forEach(action)
        }
        NodeKind.TEMPLATE_LITERAL_TYPE_SPAN -> { node as TemplateLiteralTypeSpan; action(node.type); action(node.literal) }
        NodeKind.PARENTHESIZED_TYPE -> { node as ParenthesizedType; action(node.type) }
        NodeKind.TYPE_PREDICATE -> {
            node as TypePredicate
            action(node.parameterName)
            node.type?.let(action)
        }
        NodeKind.TYPE_OPERATOR -> { node as TypeOperator; action(node.type) }
        NodeKind.REST_TYPE -> { node as RestType; action(node.type) }
        NodeKind.NAMED_TUPLE_MEMBER -> { node as NamedTupleMember; action(node.name); action(node.type) }
        NodeKind.OPTIONAL_TYPE -> { node as OptionalType; action(node.type) }
        NodeKind.IMPORT_TYPE -> {
            node as ImportType
            action(node.argument)
            node.qualifier?.let(action)
            node.typeArguments?.forEach(action)
        }
        NodeKind.THIS_TYPE -> {}
        NodeKind.INFER_TYPE -> { node as InferType; action(node.typeParameter) }

        // ── supporting nodes ──
        NodeKind.SOURCE_FILE -> { node as SourceFile; node.statements.forEach(action) }
        NodeKind.TEMPLATE_SPAN -> { node as TemplateSpan; action(node.expression); action(node.literal) }
        NodeKind.DECORATOR -> { node as Decorator; action(node.expression) }
        NodeKind.HERITAGE_CLAUSE -> { node as HeritageClause; node.types.forEach(action) }
        NodeKind.EXPRESSION_WITH_TYPE_ARGUMENTS -> {
            node as ExpressionWithTypeArguments
            action(node.expression)
            node.typeArguments?.forEach(action)
        }
        NodeKind.ENUM_MEMBER -> {
            node as EnumMember
            action(node.name)
            node.initializer?.let(action)
        }
        NodeKind.TYPE_PARAMETER -> {
            node as TypeParameter
            action(node.name)
            node.constraint?.let(action)
            node.default?.let(action)
        }
        NodeKind.QUALIFIED_NAME -> { node as QualifiedName; action(node.left); action(node.right) }
        NodeKind.PROPERTY_ASSIGNMENT -> { node as PropertyAssignment; action(node.name); action(node.initializer) }
        NodeKind.SHORTHAND_PROPERTY_ASSIGNMENT -> {
            node as ShorthandPropertyAssignment
            action(node.name)
            node.objectAssignmentInitializer?.let(action)
        }
        NodeKind.SPREAD_ASSIGNMENT -> { node as SpreadAssignment; action(node.expression) }
        NodeKind.COMPUTED_PROPERTY_NAME -> { node as ComputedPropertyName; action(node.expression) }
        NodeKind.OBJECT_BINDING_PATTERN -> { node as ObjectBindingPattern; node.elements.forEach(action) }
        NodeKind.ARRAY_BINDING_PATTERN -> { node as ArrayBindingPattern; node.elements.forEach(action) }
        NodeKind.BINDING_ELEMENT -> {
            node as BindingElement
            node.propertyName?.let(action)
            action(node.name)
            node.initializer?.let(action)
        }
        NodeKind.CASE_CLAUSE -> {
            node as CaseClause
            action(node.expression)
            node.statements.forEach(action)
        }
        NodeKind.DEFAULT_CLAUSE -> { node as DefaultClause; node.statements.forEach(action) }
        NodeKind.CATCH_CLAUSE -> {
            node as CatchClause
            node.variableDeclaration?.let(action)
            action(node.block)
        }
        NodeKind.MODULE_BLOCK -> { node as ModuleBlock; node.statements.forEach(action) }
        NodeKind.NAMESPACE_IMPORT -> { node as NamespaceImport; action(node.name) }
        NodeKind.NAMED_IMPORTS -> { node as NamedImports; node.elements.forEach(action) }
        NodeKind.IMPORT_SPECIFIER -> {
            node as ImportSpecifier
            node.propertyName?.let(action)
            action(node.name)
        }
        NodeKind.NAMESPACE_EXPORT -> { node as NamespaceExport; action(node.name) }
        NodeKind.NAMED_EXPORTS -> { node as NamedExports; node.elements.forEach(action) }
        NodeKind.EXPORT_SPECIFIER -> {
            node as ExportSpecifier
            node.propertyName?.let(action)
            action(node.name)
        }
        NodeKind.IMPORT_CLAUSE -> {
            node as ImportClause
            node.name?.let(action)
            node.namedBindings?.let(action)
        }
        NodeKind.EXTERNAL_MODULE_REFERENCE -> { node as ExternalModuleReference; action(node.expression) }

        // ── JSX ──
        NodeKind.JSX_ATTRIBUTE -> { node as JsxAttribute; node.value?.let(action) }
        NodeKind.JSX_SPREAD_ATTRIBUTE -> { node as JsxSpreadAttribute; action(node.expression) }
        NodeKind.JSX_OPENING_ELEMENT -> {
            node as JsxOpeningElement
            action(node.tagName)
            node.attributes.forEach(action)
        }
        NodeKind.JSX_CLOSING_ELEMENT -> { node as JsxClosingElement; action(node.tagName) }
        NodeKind.JSX_ELEMENT -> {
            node as JsxElement
            action(node.openingElement)
            node.children.forEach(action)
            action(node.closingElement)
        }
        NodeKind.JSX_SELF_CLOSING_ELEMENT -> {
            node as JsxSelfClosingElement
            action(node.tagName)
            node.attributes.forEach(action)
        }
        NodeKind.JSX_TEXT -> {}
        NodeKind.JSX_EXPRESSION_CONTAINER -> { node as JsxExpressionContainer; node.expression?.let(action) }
        NodeKind.JSX_FRAGMENT -> { node as JsxFragment; node.children.forEach(action) }
        else -> error(
            "forEachChild: unstamped kindId on ${node::class.simpleName} — the class is missing its init { kindId = ... } stamp"
        )
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
