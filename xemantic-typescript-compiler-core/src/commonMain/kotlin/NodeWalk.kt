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
 * (JIT.1), round 803: SPLIT INTO THREE FUNCTIONS BY [NodeKind] RANGE, AND THE
 * SPLIT IS THE POINT. At 9,750 bytecodes this single `when` was above HotSpot's
 * `HugeMethodLimit` (8,000; `DontCompileHugeMethods` is a product flag that
 * defaults to true), so the traversal primitive of the entire compiler was NEVER
 * JIT-COMPILED — it ran interpreted for the whole process, invisibly:
 * `-XX:+PrintCompilation` prints no "too large" line, because the compile is
 * never *proposed*. The three parts cover DISJOINT CONTIGUOUS kind ranges, so
 * each is still one dense tableswitch, and the ranges are chosen so every HOT
 * kind (IDENTIFIER — 44.5% of all nodes — the literals, PROPERTY_ACCESS / CALL /
 * BINARY, the statement anchors) stays in THIS function and pays no extra call;
 * only the member / type / supporting / JSX kinds pay one static call, and none
 * pays two. ENUMERATION ORDER IS UNCHANGED — every arm was moved verbatim.
 * [HugeMethodLimitTest] pins the sizes; `scripts/huge_methods.py` is the
 * whole-program census.
 *
 * Note [MappedType.typeParameter] is visited as a child and [TypeParameter]'s own
 * children include its `constraint` — a generic walk reaches mapped-type
 * constraints without the per-walker special case.
 */
fun forEachChild(node: Node, action: (Node) -> Unit) {
    val kind = (node as NodeBase).kindId
    when (kind) {
        NodeKind.IDENTIFIER -> {}
        NodeKind.PROPERTY_ACCESS_EXPRESSION -> { node as PropertyAccessExpression; action(node.expression); action(node.name) }
        NodeKind.CALL_EXPRESSION -> {
            node as CallExpression
            action(node.expression)
            walkList(node.typeArguments, action)
            walkList(node.arguments, action)
        }
        NodeKind.STRING_LITERAL_NODE -> {}
        NodeKind.NUMERIC_LITERAL_NODE -> {}
        NodeKind.BINARY_EXPRESSION -> { node as BinaryExpression; action(node.left); action(node.right) }
        NodeKind.EXPRESSION_STATEMENT -> { node as ExpressionStatement; action(node.expression) }
        NodeKind.BLOCK -> { node as Block; walkList(node.statements, action) }
        NodeKind.VARIABLE_DECLARATION -> {
            node as VariableDeclaration
            action(node.name)
            node.type?.let(action)
            node.initializer?.let(action)
        }
        NodeKind.VARIABLE_DECLARATION_LIST -> { node as VariableDeclarationList; walkList(node.declarations, action) }
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
            walkList(node.caseBlock, action)
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
            walkList(node.typeParameters, action)
            walkList(node.parameters, action)
            node.type?.let(action)
            node.body?.let(action)
        }
        NodeKind.CLASS_DECLARATION -> {
            node as ClassDeclaration
            node.name?.let(action)
            walkList(node.typeParameters, action)
            walkList(node.heritageClauses, action)
            walkList(node.members, action)
            walkList(node.decorators, action)
        }
        NodeKind.INTERFACE_DECLARATION -> {
            node as InterfaceDeclaration
            action(node.name)
            walkList(node.typeParameters, action)
            walkList(node.heritageClauses, action)
            walkList(node.members, action)
        }
        NodeKind.TYPE_ALIAS_DECLARATION -> {
            node as TypeAliasDeclaration
            action(node.name)
            walkList(node.typeParameters, action)
            action(node.type)
        }
        NodeKind.ENUM_DECLARATION -> {
            node as EnumDeclaration
            action(node.name)
            walkList(node.members, action)
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
            walkList(node.templateSpans, action)
        }
        NodeKind.ARRAY_LITERAL_EXPRESSION -> { node as ArrayLiteralExpression; walkList(node.elements, action) }
        NodeKind.OBJECT_LITERAL_EXPRESSION -> { node as ObjectLiteralExpression; walkList(node.properties, action) }
        NodeKind.ELEMENT_ACCESS_EXPRESSION -> { node as ElementAccessExpression; action(node.expression); action(node.argumentExpression) }
        NodeKind.NEW_EXPRESSION -> {
            node as NewExpression
            action(node.expression)
            walkList(node.typeArguments, action)
            walkList(node.arguments, action)
            walkList(node.leadingTypeArguments, action)
        }
        NodeKind.TAGGED_TEMPLATE_EXPRESSION -> {
            node as TaggedTemplateExpression
            action(node.tag)
            walkList(node.typeArguments, action)
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
            walkList(node.typeParameters, action)
            walkList(node.parameters, action)
            node.type?.let(action)
            action(node.body)
        }
        NodeKind.ARROW_FUNCTION -> {
            node as ArrowFunction
            walkList(node.typeParameters, action)
            walkList(node.parameters, action)
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
            walkList(node.typeParameters, action)
            walkList(node.heritageClauses, action)
            walkList(node.members, action)
            walkList(node.decorators, action)
        }
        NodeKind.AS_EXPRESSION -> { node as AsExpression; action(node.expression); action(node.type) }
        NodeKind.NON_NULL_EXPRESSION -> { node as NonNullExpression; action(node.expression) }
        NodeKind.SATISFIES_EXPRESSION -> { node as SatisfiesExpression; action(node.expression); action(node.type) }
        NodeKind.META_PROPERTY -> { node as MetaProperty; action(node.name) }
        NodeKind.OMITTED_EXPRESSION -> {}
        NodeKind.COMMA_LIST_EXPRESSION -> { node as CommaListExpression; walkList(node.elements, action) }

        // ── supporting kinds that happen to carry a low id ──
        NodeKind.SOURCE_FILE -> { node as SourceFile; walkList(node.statements, action) }
        NodeKind.TEMPLATE_SPAN -> { node as TemplateSpan; action(node.expression); action(node.literal) }
        else ->
            // Disjoint continuation ranges — NOT a fall-through chain, so no kind
            // pays more than one extra static call.
            if (kind < NodeKind.PARAMETER) forEachChildOfMemberOrType(node, kind, action)
            else forEachChildOfSupportingNode(node, kind, action)
    }
}

/**
 * (JIT.1) [forEachChild] part 2 of 3 — the CLASS ELEMENT and TYPE NODE kinds
 * ([NodeKind.PROPERTY_DECLARATION] .. [NodeKind.KEYWORD_TYPE_NODE]). Arms moved
 * verbatim from the single pre-803 `when`; [kind] is the caller's already-read
 * [NodeBase.kindId], so this is one further tableswitch and nothing else.
 */
private fun forEachChildOfMemberOrType(node: Node, kind: Int, action: (Node) -> Unit) {
    when (kind) {
        NodeKind.KEYWORD_TYPE_NODE -> {}
        NodeKind.TYPE_REFERENCE -> {
            node as TypeReference
            action(node.typeName)
            walkList(node.typeArguments, action)
        }

        // ── class elements ──
        NodeKind.PROPERTY_DECLARATION -> {
            node as PropertyDeclaration
            action(node.name)
            node.type?.let(action)
            node.initializer?.let(action)
            walkList(node.decorators, action)
        }
        NodeKind.METHOD_DECLARATION -> {
            node as MethodDeclaration
            action(node.name)
            walkList(node.typeParameters, action)
            walkList(node.parameters, action)
            node.type?.let(action)
            node.body?.let(action)
            walkList(node.decorators, action)
        }
        NodeKind.CONSTRUCTOR -> {
            node as Constructor
            walkList(node.parameters, action)
            node.body?.let(action)
            walkList(node.decorators, action)
        }
        NodeKind.GET_ACCESSOR -> {
            node as GetAccessor
            action(node.name)
            walkList(node.parameters, action)
            node.type?.let(action)
            node.body?.let(action)
            walkList(node.decorators, action)
        }
        NodeKind.SET_ACCESSOR -> {
            node as SetAccessor
            action(node.name)
            walkList(node.parameters, action)
            node.type?.let(action)
            node.body?.let(action)
            walkList(node.decorators, action)
        }
        NodeKind.INDEX_SIGNATURE -> {
            node as IndexSignature
            walkList(node.parameters, action)
            node.type?.let(action)
        }
        NodeKind.SEMICOLON_CLASS_ELEMENT -> {}
        NodeKind.CLASS_STATIC_BLOCK_DECLARATION -> { node as ClassStaticBlockDeclaration; action(node.body) }

        // ── type nodes ──
        NodeKind.FUNCTION_TYPE -> {
            node as FunctionType
            walkList(node.typeParameters, action)
            walkList(node.parameters, action)
            action(node.type)
        }
        NodeKind.CONSTRUCTOR_TYPE -> {
            node as ConstructorType
            walkList(node.typeParameters, action)
            walkList(node.parameters, action)
            action(node.type)
        }
        NodeKind.TYPE_QUERY -> {
            node as TypeQuery
            action(node.exprName)
            walkList(node.typeArguments, action)
        }
        NodeKind.TYPE_LITERAL -> { node as TypeLiteral; walkList(node.members, action) }
        NodeKind.ARRAY_TYPE -> { node as ArrayType; action(node.elementType) }
        NodeKind.TUPLE_TYPE -> { node as TupleType; walkList(node.elements, action) }
        NodeKind.UNION_TYPE -> { node as UnionType; walkList(node.types, action) }
        NodeKind.INTERSECTION_TYPE -> { node as IntersectionType; walkList(node.types, action) }
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
            walkList(node.templateSpans, action)
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
            walkList(node.typeArguments, action)
        }
        NodeKind.THIS_TYPE -> {}
        NodeKind.INFER_TYPE -> { node as InferType; action(node.typeParameter) }
        else -> unstampedKindError(node)
    }
}

/**
 * (JIT.1) [forEachChild] part 3 of 3 — [NodeKind.PARAMETER] and above: the
 * supporting nodes (parameters, decorators, heritage clauses, members, binding
 * patterns, clauses, import/export specifiers) and the JSX kinds. Arms moved
 * verbatim from the single pre-803 `when`.
 */
private fun forEachChildOfSupportingNode(node: Node, kind: Int, action: (Node) -> Unit) {
    when (kind) {
        NodeKind.PARAMETER -> {
            node as Parameter
            action(node.name)
            node.type?.let(action)
            node.initializer?.let(action)
            walkList(node.decorators, action)
        }

        // ── supporting nodes ──
        NodeKind.DECORATOR -> { node as Decorator; action(node.expression) }
        NodeKind.HERITAGE_CLAUSE -> { node as HeritageClause; walkList(node.types, action) }
        NodeKind.EXPRESSION_WITH_TYPE_ARGUMENTS -> {
            node as ExpressionWithTypeArguments
            action(node.expression)
            walkList(node.typeArguments, action)
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
        NodeKind.OBJECT_BINDING_PATTERN -> { node as ObjectBindingPattern; walkList(node.elements, action) }
        NodeKind.ARRAY_BINDING_PATTERN -> { node as ArrayBindingPattern; walkList(node.elements, action) }
        NodeKind.BINDING_ELEMENT -> {
            node as BindingElement
            node.propertyName?.let(action)
            action(node.name)
            node.initializer?.let(action)
        }
        NodeKind.CASE_CLAUSE -> {
            node as CaseClause
            action(node.expression)
            walkList(node.statements, action)
        }
        NodeKind.DEFAULT_CLAUSE -> { node as DefaultClause; walkList(node.statements, action) }
        NodeKind.CATCH_CLAUSE -> {
            node as CatchClause
            node.variableDeclaration?.let(action)
            action(node.block)
        }
        NodeKind.MODULE_BLOCK -> { node as ModuleBlock; walkList(node.statements, action) }
        NodeKind.NAMESPACE_IMPORT -> { node as NamespaceImport; action(node.name) }
        NodeKind.NAMED_IMPORTS -> { node as NamedImports; walkList(node.elements, action) }
        NodeKind.IMPORT_SPECIFIER -> {
            node as ImportSpecifier
            node.propertyName?.let(action)
            action(node.name)
        }
        NodeKind.NAMESPACE_EXPORT -> { node as NamespaceExport; action(node.name) }
        NodeKind.NAMED_EXPORTS -> { node as NamedExports; walkList(node.elements, action) }
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
            walkList(node.attributes, action)
        }
        NodeKind.JSX_CLOSING_ELEMENT -> { node as JsxClosingElement; action(node.tagName) }
        NodeKind.JSX_ELEMENT -> {
            node as JsxElement
            action(node.openingElement)
            walkList(node.children, action)
            action(node.closingElement)
        }
        NodeKind.JSX_SELF_CLOSING_ELEMENT -> {
            node as JsxSelfClosingElement
            action(node.tagName)
            walkList(node.attributes, action)
        }
        NodeKind.JSX_TEXT -> {}
        NodeKind.JSX_EXPRESSION_CONTAINER -> { node as JsxExpressionContainer; node.expression?.let(action) }
        NodeKind.JSX_FRAGMENT -> { node as JsxFragment; walkList(node.children, action) }
        else -> unstampedKindError(node)
    }
}

/**
 * (WARM.32) The ONE place [forEachChild]'s 70 list child positions iterate, and
 * the ONE place the family's shape can be changed or measured.
 *
 * Nullable by design: a `xs?.forEach(action)` position and a `xs.forEach(action)`
 * one become the SAME call here, and a null list returns before the hook — which
 * is what today's `?.` does and what keeps the census's `calls` a count of real
 * iterations.
 *
 * NOT `inline`: 70 inlined copies of the loop are what put [forEachChild] over
 * HotSpot's 8,000-byte `HugeMethodLimit` in the first place (round 803), so a
 * static call here SHRINKS all three partitions. The gate is one static
 * `Boolean` read; round 900's law is obeyed by passing the LIST and deriving
 * `size` inside [IterCensus.noteList].
 *
 * The body is deliberately `for (e in xs)` — the exact lowering of
 * `xs.forEach(action)`, so this refactor is shape-preserving and the
 * [IterCensus] amplifier's arm A is the code that actually runs here.
 */
private fun walkList(xs: List<Node>?, action: (Node) -> Unit) {
    if (xs == null) return
    if (IterCensus.on) IterCensus.noteList(xs)
    for (e in xs) action(e)
}

/**
 * (WARM.32) The identity membership test the INV.4 edge classifiers ask ~145
 * times over — `xs.any { it === x }`, which cannot be `xs.contains(x)` because
 * AST nodes are data classes and `equals` would deep-recurse the subtree
 * (round 471).
 *
 * Extracted so the family has one home; the body is the exact lowering of the
 * `any {}` it replaces, so the extraction is shape-preserving. Being a static
 * call rather than 145 inlined loops also takes bytecode OUT of the edge
 * classifiers, which live in `Checker.kt`'s largest functions.
 */
internal fun List<Node>.anyIdentical(x: Node): Boolean {
    if (IterCensus.on) IterCensus.noteAny(this, x)
    for (e in this) if (e === x) return true
    return false
}

/**
 * The loud `else` the M0.2 stamp contract relies on — a class missing its
 * `init { kindId = ... }` stamp carries [NodeBase.kindId] = −1 and crashes every
 * parse here rather than silently exempting its subtree from every walk.
 */
private fun unstampedKindError(node: Node): Nothing = error(
    "forEachChild: unstamped kindId on ${node::class.simpleName} — the class is missing its init { kindId = ... } stamp"
)

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
    var nested: ArrayList<Node>? = null
    while (stack.isNotEmpty()) {
        val node = stack.removeAt(stack.size - 1)
        (node as NodeBase).nodeId = nextId++
        // (INC.16) two int compares per node, on the walk that already stamps the
        // parent link this test needs. A decl whose parent IS the SourceFile lands
        // in the root lexical scope, which ALIASES file locals, so `declareLexical`
        // can never bind it — anything else might.
        val k = node.kindId
        if ((k == NodeKind.TYPE_ALIAS_DECLARATION || k == NodeKind.ENUM_DECLARATION) &&
            node.parent !== sourceFile
        ) {
            val list = nested ?: ArrayList<Node>(4).also { nested = it }
            list.add(node)
        }
        // M0 census (inert off --passTiming): node-kind histogram for the
        // dispatch-order / kind-table design.
        if (PassTiming.detailed) PassTiming.noteNodeKind(node)
        buf.clear()
        forEachChild(node, collect)
        for (i in buf.indices.reversed()) {
            (buf[i] as NodeBase).parent = node
            stack.add(buf[i])
        }
    }
    sourceFile.nodeCount = nextId
    sourceFile.nestedEnumOrTypeAliasDecls = nested ?: emptyList()
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
