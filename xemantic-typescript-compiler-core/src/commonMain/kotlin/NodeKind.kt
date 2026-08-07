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
 * M0.2: dense per-CLASS dispatch ids for the AST node hierarchy, stamped onto
 * [NodeBase.kindId] by each node class's `init` block (so `copy()` and
 * Transformer-synthesized instances are stamped too — unlike `nodeId`/`parent`,
 * which stay unindexed on synthesized nodes). Hot dispatchers (`forEachChild`,
 * the checkSpine `when(node)` chains) switch on `(node as NodeBase).kindId` —
 * a dense `when(Int)` over these consts compiles to a tableswitch, replacing
 * the average 8.3-deep sequential instanceof chain (round-618 histogram).
 *
 * MAINTENANCE (compile-enforced chain): a NEW node class breaks [nodeKindIdOf]'s
 * sealed-exhaustive `when` -> adding its arm forces a new const here -> the
 * class's `init { kindId = ... }` stamp is pinned by `NodeKindIdTest` (every
 * fixture node's stamped kindId must equal [nodeKindIdOf]) and by
 * [forEachChild]'s loud `else -> error(...)` (an unstamped class crashes every
 * parse). Ids are per-CLASS (an `is`-test discriminator), NOT [SyntaxKind]
 * (KeywordTypeNode spans many kinds); keep them DENSE 0..N-1 — density is what
 * buys the tableswitch.
 */
object NodeKind {
    const val SOURCE_FILE = 0
    const val BLOCK = 1
    const val EMPTY_STATEMENT = 2
    const val VARIABLE_STATEMENT = 3
    const val EXPRESSION_STATEMENT = 4
    const val IF_STATEMENT = 5
    const val DO_STATEMENT = 6
    const val WHILE_STATEMENT = 7
    const val FOR_STATEMENT = 8
    const val FOR_IN_STATEMENT = 9
    const val FOR_OF_STATEMENT = 10
    const val CONTINUE_STATEMENT = 11
    const val BREAK_STATEMENT = 12
    const val RETURN_STATEMENT = 13
    const val WITH_STATEMENT = 14
    const val SWITCH_STATEMENT = 15
    const val LABELED_STATEMENT = 16
    const val THROW_STATEMENT = 17
    const val TRY_STATEMENT = 18
    const val DEBUGGER_STATEMENT = 19
    const val NOT_EMITTED_STATEMENT = 20
    const val RAW_STATEMENT = 21
    const val FUNCTION_DECLARATION = 22
    const val CLASS_DECLARATION = 23
    const val INTERFACE_DECLARATION = 24
    const val TYPE_ALIAS_DECLARATION = 25
    const val ENUM_DECLARATION = 26
    const val MODULE_DECLARATION = 27
    const val IMPORT_DECLARATION = 28
    const val IMPORT_EQUALS_DECLARATION = 29
    const val EXPORT_DECLARATION = 30
    const val EXPORT_ASSIGNMENT = 31
    const val VARIABLE_DECLARATION = 32
    const val VARIABLE_DECLARATION_LIST = 33
    const val IDENTIFIER = 34
    const val STRING_LITERAL_NODE = 35
    const val NUMERIC_LITERAL_NODE = 36
    const val BIG_INT_LITERAL_NODE = 37
    const val REGULAR_EXPRESSION_LITERAL_NODE = 38
    const val NO_SUBSTITUTION_TEMPLATE_LITERAL_NODE = 39
    const val TEMPLATE_EXPRESSION = 40
    const val TEMPLATE_SPAN = 41
    const val ARRAY_LITERAL_EXPRESSION = 42
    const val OBJECT_LITERAL_EXPRESSION = 43
    const val PROPERTY_ACCESS_EXPRESSION = 44
    const val ELEMENT_ACCESS_EXPRESSION = 45
    const val CALL_EXPRESSION = 46
    const val NEW_EXPRESSION = 47
    const val TAGGED_TEMPLATE_EXPRESSION = 48
    const val TYPE_ASSERTION_EXPRESSION = 49
    const val PARENTHESIZED_EXPRESSION = 50
    const val FUNCTION_EXPRESSION = 51
    const val ARROW_FUNCTION = 52
    const val DELETE_EXPRESSION = 53
    const val TYPE_OF_EXPRESSION = 54
    const val VOID_EXPRESSION = 55
    const val AWAIT_EXPRESSION = 56
    const val PREFIX_UNARY_EXPRESSION = 57
    const val POSTFIX_UNARY_EXPRESSION = 58
    const val BINARY_EXPRESSION = 59
    const val CONDITIONAL_EXPRESSION = 60
    const val YIELD_EXPRESSION = 61
    const val SPREAD_ELEMENT = 62
    const val CLASS_EXPRESSION = 63
    const val AS_EXPRESSION = 64
    const val NON_NULL_EXPRESSION = 65
    const val SATISFIES_EXPRESSION = 66
    const val META_PROPERTY = 67
    const val OMITTED_EXPRESSION = 68
    const val COMMA_LIST_EXPRESSION = 69
    const val PROPERTY_DECLARATION = 70
    const val METHOD_DECLARATION = 71
    const val CONSTRUCTOR = 72
    const val GET_ACCESSOR = 73
    const val SET_ACCESSOR = 74
    const val INDEX_SIGNATURE = 75
    const val SEMICOLON_CLASS_ELEMENT = 76
    const val CLASS_STATIC_BLOCK_DECLARATION = 77
    const val TYPE_REFERENCE = 78
    const val FUNCTION_TYPE = 79
    const val CONSTRUCTOR_TYPE = 80
    const val TYPE_QUERY = 81
    const val TYPE_LITERAL = 82
    const val ARRAY_TYPE = 83
    const val TUPLE_TYPE = 84
    const val UNION_TYPE = 85
    const val INTERSECTION_TYPE = 86
    const val CONDITIONAL_TYPE = 87
    const val INDEXED_ACCESS_TYPE = 88
    const val MAPPED_TYPE = 89
    const val LITERAL_TYPE = 90
    const val TEMPLATE_LITERAL_TYPE = 91
    const val TEMPLATE_LITERAL_TYPE_SPAN = 92
    const val PARENTHESIZED_TYPE = 93
    const val TYPE_PREDICATE = 94
    const val TYPE_OPERATOR = 95
    const val REST_TYPE = 96
    const val NAMED_TUPLE_MEMBER = 97
    const val OPTIONAL_TYPE = 98
    const val IMPORT_TYPE = 99
    const val THIS_TYPE = 100
    const val INFER_TYPE = 101
    const val KEYWORD_TYPE_NODE = 102
    const val PARAMETER = 103
    const val DECORATOR = 104
    const val HERITAGE_CLAUSE = 105
    const val EXPRESSION_WITH_TYPE_ARGUMENTS = 106
    const val ENUM_MEMBER = 107
    const val TYPE_PARAMETER = 108
    const val QUALIFIED_NAME = 109
    const val PROPERTY_ASSIGNMENT = 110
    const val SHORTHAND_PROPERTY_ASSIGNMENT = 111
    const val SPREAD_ASSIGNMENT = 112
    const val COMPUTED_PROPERTY_NAME = 113
    const val OBJECT_BINDING_PATTERN = 114
    const val ARRAY_BINDING_PATTERN = 115
    const val BINDING_ELEMENT = 116
    const val CASE_CLAUSE = 117
    const val DEFAULT_CLAUSE = 118
    const val CATCH_CLAUSE = 119
    const val MODULE_BLOCK = 120
    const val NAMESPACE_IMPORT = 121
    const val NAMED_IMPORTS = 122
    const val IMPORT_SPECIFIER = 123
    const val NAMESPACE_EXPORT = 124
    const val NAMED_EXPORTS = 125
    const val EXPORT_SPECIFIER = 126
    const val IMPORT_CLAUSE = 127
    const val EXTERNAL_MODULE_REFERENCE = 128
    const val JSX_ATTRIBUTE = 129
    const val JSX_SPREAD_ATTRIBUTE = 130
    const val JSX_OPENING_ELEMENT = 131
    const val JSX_CLOSING_ELEMENT = 132
    const val JSX_ELEMENT = 133
    const val JSX_SELF_CLOSING_ELEMENT = 134
    const val JSX_TEXT = 135
    const val JSX_EXPRESSION_CONTAINER = 136
    const val JSX_FRAGMENT = 137
}

/**
 * The compile-enforced class->kindId bijection: exhaustive over the sealed
 * [Node] hierarchy (NO else), so a new node class fails compilation here until
 * it gets a [NodeKind] id. Runtime consumers read the stamped
 * [NodeBase.kindId] field instead (a direct field load); this mapper exists as
 * the compile gate + the test oracle's ground truth.
 */
fun nodeKindIdOf(node: Node): Int = when (node) {
    is SourceFile -> NodeKind.SOURCE_FILE
    is Block -> NodeKind.BLOCK
    is EmptyStatement -> NodeKind.EMPTY_STATEMENT
    is VariableStatement -> NodeKind.VARIABLE_STATEMENT
    is ExpressionStatement -> NodeKind.EXPRESSION_STATEMENT
    is IfStatement -> NodeKind.IF_STATEMENT
    is DoStatement -> NodeKind.DO_STATEMENT
    is WhileStatement -> NodeKind.WHILE_STATEMENT
    is ForStatement -> NodeKind.FOR_STATEMENT
    is ForInStatement -> NodeKind.FOR_IN_STATEMENT
    is ForOfStatement -> NodeKind.FOR_OF_STATEMENT
    is ContinueStatement -> NodeKind.CONTINUE_STATEMENT
    is BreakStatement -> NodeKind.BREAK_STATEMENT
    is ReturnStatement -> NodeKind.RETURN_STATEMENT
    is WithStatement -> NodeKind.WITH_STATEMENT
    is SwitchStatement -> NodeKind.SWITCH_STATEMENT
    is LabeledStatement -> NodeKind.LABELED_STATEMENT
    is ThrowStatement -> NodeKind.THROW_STATEMENT
    is TryStatement -> NodeKind.TRY_STATEMENT
    is DebuggerStatement -> NodeKind.DEBUGGER_STATEMENT
    is NotEmittedStatement -> NodeKind.NOT_EMITTED_STATEMENT
    is RawStatement -> NodeKind.RAW_STATEMENT
    is FunctionDeclaration -> NodeKind.FUNCTION_DECLARATION
    is ClassDeclaration -> NodeKind.CLASS_DECLARATION
    is InterfaceDeclaration -> NodeKind.INTERFACE_DECLARATION
    is TypeAliasDeclaration -> NodeKind.TYPE_ALIAS_DECLARATION
    is EnumDeclaration -> NodeKind.ENUM_DECLARATION
    is ModuleDeclaration -> NodeKind.MODULE_DECLARATION
    is ImportDeclaration -> NodeKind.IMPORT_DECLARATION
    is ImportEqualsDeclaration -> NodeKind.IMPORT_EQUALS_DECLARATION
    is ExportDeclaration -> NodeKind.EXPORT_DECLARATION
    is ExportAssignment -> NodeKind.EXPORT_ASSIGNMENT
    is VariableDeclaration -> NodeKind.VARIABLE_DECLARATION
    is VariableDeclarationList -> NodeKind.VARIABLE_DECLARATION_LIST
    is Identifier -> NodeKind.IDENTIFIER
    is StringLiteralNode -> NodeKind.STRING_LITERAL_NODE
    is NumericLiteralNode -> NodeKind.NUMERIC_LITERAL_NODE
    is BigIntLiteralNode -> NodeKind.BIG_INT_LITERAL_NODE
    is RegularExpressionLiteralNode -> NodeKind.REGULAR_EXPRESSION_LITERAL_NODE
    is NoSubstitutionTemplateLiteralNode -> NodeKind.NO_SUBSTITUTION_TEMPLATE_LITERAL_NODE
    is TemplateExpression -> NodeKind.TEMPLATE_EXPRESSION
    is TemplateSpan -> NodeKind.TEMPLATE_SPAN
    is ArrayLiteralExpression -> NodeKind.ARRAY_LITERAL_EXPRESSION
    is ObjectLiteralExpression -> NodeKind.OBJECT_LITERAL_EXPRESSION
    is PropertyAccessExpression -> NodeKind.PROPERTY_ACCESS_EXPRESSION
    is ElementAccessExpression -> NodeKind.ELEMENT_ACCESS_EXPRESSION
    is CallExpression -> NodeKind.CALL_EXPRESSION
    is NewExpression -> NodeKind.NEW_EXPRESSION
    is TaggedTemplateExpression -> NodeKind.TAGGED_TEMPLATE_EXPRESSION
    is TypeAssertionExpression -> NodeKind.TYPE_ASSERTION_EXPRESSION
    is ParenthesizedExpression -> NodeKind.PARENTHESIZED_EXPRESSION
    is FunctionExpression -> NodeKind.FUNCTION_EXPRESSION
    is ArrowFunction -> NodeKind.ARROW_FUNCTION
    is DeleteExpression -> NodeKind.DELETE_EXPRESSION
    is TypeOfExpression -> NodeKind.TYPE_OF_EXPRESSION
    is VoidExpression -> NodeKind.VOID_EXPRESSION
    is AwaitExpression -> NodeKind.AWAIT_EXPRESSION
    is PrefixUnaryExpression -> NodeKind.PREFIX_UNARY_EXPRESSION
    is PostfixUnaryExpression -> NodeKind.POSTFIX_UNARY_EXPRESSION
    is BinaryExpression -> NodeKind.BINARY_EXPRESSION
    is ConditionalExpression -> NodeKind.CONDITIONAL_EXPRESSION
    is YieldExpression -> NodeKind.YIELD_EXPRESSION
    is SpreadElement -> NodeKind.SPREAD_ELEMENT
    is ClassExpression -> NodeKind.CLASS_EXPRESSION
    is AsExpression -> NodeKind.AS_EXPRESSION
    is NonNullExpression -> NodeKind.NON_NULL_EXPRESSION
    is SatisfiesExpression -> NodeKind.SATISFIES_EXPRESSION
    is MetaProperty -> NodeKind.META_PROPERTY
    is OmittedExpression -> NodeKind.OMITTED_EXPRESSION
    is CommaListExpression -> NodeKind.COMMA_LIST_EXPRESSION
    is PropertyDeclaration -> NodeKind.PROPERTY_DECLARATION
    is MethodDeclaration -> NodeKind.METHOD_DECLARATION
    is Constructor -> NodeKind.CONSTRUCTOR
    is GetAccessor -> NodeKind.GET_ACCESSOR
    is SetAccessor -> NodeKind.SET_ACCESSOR
    is IndexSignature -> NodeKind.INDEX_SIGNATURE
    is SemicolonClassElement -> NodeKind.SEMICOLON_CLASS_ELEMENT
    is ClassStaticBlockDeclaration -> NodeKind.CLASS_STATIC_BLOCK_DECLARATION
    is TypeReference -> NodeKind.TYPE_REFERENCE
    is FunctionType -> NodeKind.FUNCTION_TYPE
    is ConstructorType -> NodeKind.CONSTRUCTOR_TYPE
    is TypeQuery -> NodeKind.TYPE_QUERY
    is TypeLiteral -> NodeKind.TYPE_LITERAL
    is ArrayType -> NodeKind.ARRAY_TYPE
    is TupleType -> NodeKind.TUPLE_TYPE
    is UnionType -> NodeKind.UNION_TYPE
    is IntersectionType -> NodeKind.INTERSECTION_TYPE
    is ConditionalType -> NodeKind.CONDITIONAL_TYPE
    is IndexedAccessType -> NodeKind.INDEXED_ACCESS_TYPE
    is MappedType -> NodeKind.MAPPED_TYPE
    is LiteralType -> NodeKind.LITERAL_TYPE
    is TemplateLiteralType -> NodeKind.TEMPLATE_LITERAL_TYPE
    is TemplateLiteralTypeSpan -> NodeKind.TEMPLATE_LITERAL_TYPE_SPAN
    is ParenthesizedType -> NodeKind.PARENTHESIZED_TYPE
    is TypePredicate -> NodeKind.TYPE_PREDICATE
    is TypeOperator -> NodeKind.TYPE_OPERATOR
    is RestType -> NodeKind.REST_TYPE
    is NamedTupleMember -> NodeKind.NAMED_TUPLE_MEMBER
    is OptionalType -> NodeKind.OPTIONAL_TYPE
    is ImportType -> NodeKind.IMPORT_TYPE
    is ThisType -> NodeKind.THIS_TYPE
    is InferType -> NodeKind.INFER_TYPE
    is KeywordTypeNode -> NodeKind.KEYWORD_TYPE_NODE
    is Parameter -> NodeKind.PARAMETER
    is Decorator -> NodeKind.DECORATOR
    is HeritageClause -> NodeKind.HERITAGE_CLAUSE
    is ExpressionWithTypeArguments -> NodeKind.EXPRESSION_WITH_TYPE_ARGUMENTS
    is EnumMember -> NodeKind.ENUM_MEMBER
    is TypeParameter -> NodeKind.TYPE_PARAMETER
    is QualifiedName -> NodeKind.QUALIFIED_NAME
    is PropertyAssignment -> NodeKind.PROPERTY_ASSIGNMENT
    is ShorthandPropertyAssignment -> NodeKind.SHORTHAND_PROPERTY_ASSIGNMENT
    is SpreadAssignment -> NodeKind.SPREAD_ASSIGNMENT
    is ComputedPropertyName -> NodeKind.COMPUTED_PROPERTY_NAME
    is ObjectBindingPattern -> NodeKind.OBJECT_BINDING_PATTERN
    is ArrayBindingPattern -> NodeKind.ARRAY_BINDING_PATTERN
    is BindingElement -> NodeKind.BINDING_ELEMENT
    is CaseClause -> NodeKind.CASE_CLAUSE
    is DefaultClause -> NodeKind.DEFAULT_CLAUSE
    is CatchClause -> NodeKind.CATCH_CLAUSE
    is ModuleBlock -> NodeKind.MODULE_BLOCK
    is NamespaceImport -> NodeKind.NAMESPACE_IMPORT
    is NamedImports -> NodeKind.NAMED_IMPORTS
    is ImportSpecifier -> NodeKind.IMPORT_SPECIFIER
    is NamespaceExport -> NodeKind.NAMESPACE_EXPORT
    is NamedExports -> NodeKind.NAMED_EXPORTS
    is ExportSpecifier -> NodeKind.EXPORT_SPECIFIER
    is ImportClause -> NodeKind.IMPORT_CLAUSE
    is ExternalModuleReference -> NodeKind.EXTERNAL_MODULE_REFERENCE
    is JsxAttribute -> NodeKind.JSX_ATTRIBUTE
    is JsxSpreadAttribute -> NodeKind.JSX_SPREAD_ATTRIBUTE
    is JsxOpeningElement -> NodeKind.JSX_OPENING_ELEMENT
    is JsxClosingElement -> NodeKind.JSX_CLOSING_ELEMENT
    is JsxElement -> NodeKind.JSX_ELEMENT
    is JsxSelfClosingElement -> NodeKind.JSX_SELF_CLOSING_ELEMENT
    is JsxText -> NodeKind.JSX_TEXT
    is JsxExpressionContainer -> NodeKind.JSX_EXPRESSION_CONTAINER
    is JsxFragment -> NodeKind.JSX_FRAGMENT
}
