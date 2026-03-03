/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

/**
 * Type alias for nodes that can appear as the name of a declaration:
 * [Identifier], [StringLiteralNode], or [ComputedPropertyName].
 */
typealias NameNode = Expression

// ---------------------------------------------------------------------------
// Base interface
// ---------------------------------------------------------------------------

sealed interface Node {
    val kind: SyntaxKind
    val pos: Int
    val end: Int
    val leadingComments: List<Comment>?
    val trailingComments: List<Comment>?
}

data class Comment(
    val kind: SyntaxKind,
    val text: String,
    val pos: Int,
    val end: Int,
    val hasTrailingNewLine: Boolean = false,
)

// ---------------------------------------------------------------------------
// Modifier flags
// ---------------------------------------------------------------------------

enum class ModifierFlag {
    Export, Default, Declare, Abstract, Public, Private, Protected,
    Static, Readonly, Override, Async, Const, In, Out, Accessor,
}

// ---------------------------------------------------------------------------
// Sealed interface hierarchy
// ---------------------------------------------------------------------------

sealed interface Statement : Node
sealed interface Expression : Node
sealed interface Declaration : Statement
sealed interface TypeNode : Node
sealed interface ClassElement : Node

// ---------------------------------------------------------------------------
// Source file
// ---------------------------------------------------------------------------

data class SourceFile(
    val fileName: String,
    val statements: List<Statement>,
    val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.SourceFile
}

// ===========================================================================
// Statement nodes
// ===========================================================================

data class Block(
    val statements: List<Statement>,
    val multiLine: Boolean = true,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.Block
}

data class EmptyStatement(
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.EmptyStatement
}

data class VariableStatement(
    val declarationList: VariableDeclarationList,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.VariableStatement
}

data class ExpressionStatement(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.ExpressionStatement
}

data class IfStatement(
    val expression: Expression,
    val thenStatement: Statement,
    val elseStatement: Statement? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.IfStatement
}

data class DoStatement(
    val statement: Statement,
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.DoStatement
}

data class WhileStatement(
    val expression: Expression,
    val statement: Statement,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.WhileStatement
}

data class ForStatement(
    val initializer: Node? = null,
    val condition: Expression? = null,
    val incrementor: Expression? = null,
    val statement: Statement,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.ForStatement
}

data class ForInStatement(
    val initializer: Node,
    val expression: Expression,
    val statement: Statement,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.ForInStatement
}

data class ForOfStatement(
    val awaitModifier: Boolean = false,
    val initializer: Node,
    val expression: Expression,
    val statement: Statement,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.ForOfStatement
}

data class ContinueStatement(
    val label: Identifier? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.ContinueStatement
}

data class BreakStatement(
    val label: Identifier? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.BreakStatement
}

data class ReturnStatement(
    val expression: Expression? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.ReturnStatement
}

data class WithStatement(
    val expression: Expression,
    val statement: Statement,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.WithStatement
}

data class SwitchStatement(
    val expression: Expression,
    val caseBlock: List<Node>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.SwitchStatement
}

data class LabeledStatement(
    val label: Identifier,
    val statement: Statement,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.LabeledStatement
}

data class ThrowStatement(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.ThrowStatement
}

data class TryStatement(
    val tryBlock: Block,
    val catchClause: CatchClause? = null,
    val finallyBlock: Block? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.TryStatement
}

data class DebuggerStatement(
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.DebuggerStatement
}

data class NotEmittedStatement(
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.NotEmittedStatement
}

// ===========================================================================
// Declaration nodes
// ===========================================================================

data class FunctionDeclaration(
    val name: Identifier? = null,
    val typeParameters: List<TypeParameter>? = null,
    val parameters: List<Parameter>,
    val type: TypeNode? = null,
    val body: Block? = null,
    val modifiers: Set<ModifierFlag> = emptySet(),
    val asteriskToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Declaration {
    override val kind: SyntaxKind = SyntaxKind.FunctionDeclaration
}

data class ClassDeclaration(
    val name: Identifier? = null,
    val typeParameters: List<TypeParameter>? = null,
    val heritageClauses: List<HeritageClause>? = null,
    val members: List<ClassElement>,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Declaration {
    override val kind: SyntaxKind = SyntaxKind.ClassDeclaration
}

data class InterfaceDeclaration(
    val name: Identifier,
    val typeParameters: List<TypeParameter>? = null,
    val heritageClauses: List<HeritageClause>? = null,
    val members: List<ClassElement>,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Declaration {
    override val kind: SyntaxKind = SyntaxKind.InterfaceDeclaration
}

data class TypeAliasDeclaration(
    val name: Identifier,
    val typeParameters: List<TypeParameter>? = null,
    val type: TypeNode,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Declaration {
    override val kind: SyntaxKind = SyntaxKind.TypeAliasDeclaration
}

data class EnumDeclaration(
    val name: Identifier,
    val members: List<EnumMember>,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Declaration {
    override val kind: SyntaxKind = SyntaxKind.EnumDeclaration
}

data class ModuleDeclaration(
    val name: Expression,
    val body: Node? = null,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Declaration {
    override val kind: SyntaxKind = SyntaxKind.ModuleDeclaration
}

data class ImportDeclaration(
    val importClause: ImportClause? = null,
    val moduleSpecifier: Expression,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Declaration {
    override val kind: SyntaxKind = SyntaxKind.ImportDeclaration
}

data class ImportEqualsDeclaration(
    val name: Identifier,
    val moduleReference: Node,
    val isTypeOnly: Boolean = false,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Declaration {
    override val kind: SyntaxKind = SyntaxKind.ImportEqualsDeclaration
}

data class ExportDeclaration(
    val exportClause: Node? = null,
    val moduleSpecifier: Expression? = null,
    val isTypeOnly: Boolean = false,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Declaration {
    override val kind: SyntaxKind = SyntaxKind.ExportDeclaration
}

data class ExportAssignment(
    val expression: Expression,
    val isExportEquals: Boolean = false,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Declaration {
    override val kind: SyntaxKind = SyntaxKind.ExportAssignment
}

data class VariableDeclaration(
    val name: Expression,
    val type: TypeNode? = null,
    val initializer: Expression? = null,
    val exclamationToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Declaration {
    override val kind: SyntaxKind = SyntaxKind.VariableDeclaration
}

data class VariableDeclarationList(
    val declarations: List<VariableDeclaration>,
    val flags: SyntaxKind,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.VariableDeclarationList
}

// ===========================================================================
// Expression nodes
// ===========================================================================

data class Identifier(
    val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.Identifier
}

data class StringLiteralNode(
    val text: String,
    val singleQuote: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.StringLiteral
}

data class NumericLiteralNode(
    val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.NumericLiteral
}

data class BigIntLiteralNode(
    val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.BigIntLiteral
}

data class RegularExpressionLiteralNode(
    val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.RegularExpressionLiteral
}

data class NoSubstitutionTemplateLiteralNode(
    val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.NoSubstitutionTemplateLiteral
}

data class TemplateExpression(
    val head: StringLiteralNode,
    val templateSpans: List<TemplateSpan>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.TemplateExpression
}

data class TemplateSpan(
    val expression: Expression,
    val literal: Node,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.TemplateSpan
}

data class ArrayLiteralExpression(
    val elements: List<Expression>,
    val multiLine: Boolean = false,
    val hasTrailingComma: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.ArrayLiteralExpression
}

data class ObjectLiteralExpression(
    val properties: List<Node>,
    val multiLine: Boolean = false,
    val hasTrailingComma: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.ObjectLiteralExpression
}

data class PropertyAccessExpression(
    val expression: Expression,
    val name: Identifier,
    val questionDotToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.PropertyAccessExpression
}

data class ElementAccessExpression(
    val expression: Expression,
    val argumentExpression: Expression,
    val questionDotToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.ElementAccessExpression
}

data class CallExpression(
    val expression: Expression,
    val typeArguments: List<TypeNode>? = null,
    val arguments: List<Expression>,
    val questionDotToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.CallExpression
}

data class NewExpression(
    val expression: Expression,
    val typeArguments: List<TypeNode>? = null,
    val arguments: List<Expression>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.NewExpression
}

data class TaggedTemplateExpression(
    val tag: Expression,
    val typeArguments: List<TypeNode>? = null,
    val template: Node,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.TaggedTemplateExpression
}

data class TypeAssertionExpression(
    val type: TypeNode,
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.TypeAssertionExpression
}

data class ParenthesizedExpression(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.ParenthesizedExpression
}

data class FunctionExpression(
    val name: Identifier? = null,
    val typeParameters: List<TypeParameter>? = null,
    val parameters: List<Parameter>,
    val type: TypeNode? = null,
    val body: Block,
    val modifiers: Set<ModifierFlag> = emptySet(),
    val asteriskToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.FunctionExpression
}

data class ArrowFunction(
    val typeParameters: List<TypeParameter>? = null,
    val parameters: List<Parameter>,
    val type: TypeNode? = null,
    val body: Node,
    val modifiers: Set<ModifierFlag> = emptySet(),
    val asteriskToken: Boolean = false,
    val hasParenthesizedParameters: Boolean = true,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.ArrowFunction
}

data class DeleteExpression(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.DeleteExpression
}

data class TypeOfExpression(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.TypeOfExpression
}

data class VoidExpression(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.VoidExpression
}

data class AwaitExpression(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.AwaitExpression
}

data class PrefixUnaryExpression(
    val operator: SyntaxKind,
    val operand: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.PrefixUnaryExpression
}

data class PostfixUnaryExpression(
    val operand: Expression,
    val operator: SyntaxKind,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.PostfixUnaryExpression
}

data class BinaryExpression(
    val left: Expression,
    val operator: SyntaxKind,
    val right: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.BinaryExpression
}

data class ConditionalExpression(
    val condition: Expression,
    val whenTrue: Expression,
    val whenFalse: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.ConditionalExpression
}

data class YieldExpression(
    val expression: Expression? = null,
    val asteriskToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.YieldExpression
}

data class SpreadElement(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.SpreadElement
}

data class ClassExpression(
    val name: Identifier? = null,
    val typeParameters: List<TypeParameter>? = null,
    val heritageClauses: List<HeritageClause>? = null,
    val members: List<ClassElement>,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.ClassExpression
}

data class AsExpression(
    val expression: Expression,
    val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.AsExpression
}

data class NonNullExpression(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.NonNullExpression
}

data class SatisfiesExpression(
    val expression: Expression,
    val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.SatisfiesExpression
}

data class MetaProperty(
    val keywordToken: SyntaxKind,
    val name: Identifier,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.MetaProperty
}

data class OmittedExpression(
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.OmittedExpression
}

// ===========================================================================
// Class element nodes
// ===========================================================================

data class PropertyDeclaration(
    val name: NameNode,
    val type: TypeNode? = null,
    val initializer: Expression? = null,
    val modifiers: Set<ModifierFlag> = emptySet(),
    val questionToken: Boolean = false,
    val exclamationToken: Boolean = false,
    val decorators: List<Decorator>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : ClassElement {
    override val kind: SyntaxKind = SyntaxKind.PropertyDeclaration
}

data class MethodDeclaration(
    val name: NameNode,
    val typeParameters: List<TypeParameter>? = null,
    val parameters: List<Parameter>,
    val type: TypeNode? = null,
    val body: Block? = null,
    val modifiers: Set<ModifierFlag> = emptySet(),
    val asteriskToken: Boolean = false,
    val questionToken: Boolean = false,
    val decorators: List<Decorator>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : ClassElement {
    override val kind: SyntaxKind = SyntaxKind.MethodDeclaration
}

data class Constructor(
    val parameters: List<Parameter>,
    val body: Block? = null,
    val modifiers: Set<ModifierFlag> = emptySet(),
    val decorators: List<Decorator>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : ClassElement {
    override val kind: SyntaxKind = SyntaxKind.ConstructorDeclaration
}

data class GetAccessor(
    val name: NameNode,
    val parameters: List<Parameter>,
    val type: TypeNode? = null,
    val body: Block? = null,
    val modifiers: Set<ModifierFlag> = emptySet(),
    val decorators: List<Decorator>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : ClassElement {
    override val kind: SyntaxKind = SyntaxKind.GetAccessor
}

data class SetAccessor(
    val name: NameNode,
    val parameters: List<Parameter>,
    val body: Block? = null,
    val modifiers: Set<ModifierFlag> = emptySet(),
    val decorators: List<Decorator>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : ClassElement {
    override val kind: SyntaxKind = SyntaxKind.SetAccessor
}

data class IndexSignature(
    val parameters: List<Parameter>,
    val type: TypeNode? = null,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : ClassElement {
    override val kind: SyntaxKind = SyntaxKind.IndexSignature
}

data class SemicolonClassElement(
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : ClassElement {
    override val kind: SyntaxKind = SyntaxKind.SemicolonClassElement
}

data class ClassStaticBlockDeclaration(
    val body: Block,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : ClassElement {
    override val kind: SyntaxKind = SyntaxKind.ClassStaticBlockDeclaration
}

// ===========================================================================
// Type nodes (parsed to be discarded during emit)
// ===========================================================================

data class TypeReference(
    val typeName: Node,
    val typeArguments: List<TypeNode>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TypeReference
}

data class FunctionType(
    val typeParameters: List<TypeParameter>? = null,
    val parameters: List<Parameter>,
    val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.FunctionType
}

data class ConstructorType(
    val typeParameters: List<TypeParameter>? = null,
    val parameters: List<Parameter>,
    val type: TypeNode,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ConstructorType
}

data class TypeQuery(
    val exprName: Node,
    val typeArguments: List<TypeNode>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TypeQuery
}

data class TypeLiteral(
    val members: List<ClassElement>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TypeLiteral
}

data class ArrayType(
    val elementType: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ArrayType
}

data class TupleType(
    val elements: List<TypeNode>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TupleType
}

data class UnionType(
    val types: List<TypeNode>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.UnionType
}

data class IntersectionType(
    val types: List<TypeNode>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.IntersectionType
}

data class ConditionalType(
    val checkType: TypeNode,
    val extendsType: TypeNode,
    val trueType: TypeNode,
    val falseType: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ConditionalType
}

data class IndexedAccessType(
    val objectType: TypeNode,
    val indexType: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.IndexedAccessType
}

data class MappedType(
    val typeParameter: TypeParameter,
    val nameType: TypeNode? = null,
    val type: TypeNode? = null,
    val questionToken: Boolean = false,
    val readonlyToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.MappedType
}

data class LiteralType(
    val literal: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.LiteralType
}

data class TemplateLiteralType(
    val head: StringLiteralNode,
    val templateSpans: List<TemplateLiteralTypeSpan>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TemplateLiteralType
}

data class TemplateLiteralTypeSpan(
    val type: TypeNode,
    val literal: Node,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TemplateLiteralTypeSpan
}

data class ParenthesizedType(
    val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ParenthesizedType
}

data class TypePredicate(
    val parameterName: Node,
    val type: TypeNode? = null,
    val assertsModifier: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TypePredicate
}

data class TypeOperator(
    val operator: SyntaxKind,
    val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TypeOperator
}

data class RestType(
    val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.RestType
}

data class NamedTupleMember(
    val name: Identifier,
    val type: TypeNode,
    val dotDotDotToken: Boolean = false,
    val questionToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.NamedTupleMember
}

data class OptionalType(
    val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.OptionalType
}

data class ImportType(
    val argument: TypeNode,
    val qualifier: Node? = null,
    val typeArguments: List<TypeNode>? = null,
    val isTypeOf: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ImportType
}

data class ThisType(
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ThisType
}

data class InferType(
    val typeParameter: TypeParameter,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode {
    override val kind: SyntaxKind = SyntaxKind.InferType
}

data class KeywordTypeNode(
    override val kind: SyntaxKind,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : TypeNode

// ===========================================================================
// Supporting types
// ===========================================================================

data class Parameter(
    val name: Expression,
    val type: TypeNode? = null,
    val initializer: Expression? = null,
    val dotDotDotToken: Boolean = false,
    val questionToken: Boolean = false,
    val modifiers: Set<ModifierFlag> = emptySet(),
    val decorators: List<Decorator>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.Parameter
}

data class Decorator(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.Decorator
}

data class HeritageClause(
    val token: SyntaxKind,
    val types: List<ExpressionWithTypeArguments>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.HeritageClause
}

data class ExpressionWithTypeArguments(
    val expression: Expression,
    val typeArguments: List<TypeNode>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.ExpressionWithTypeArguments
}

data class EnumMember(
    val name: NameNode,
    val initializer: Expression? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.EnumMember
}

data class TypeParameter(
    val name: Identifier,
    val constraint: TypeNode? = null,
    val default: TypeNode? = null,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.TypeParameter
}

data class QualifiedName(
    val left: Node,
    val right: Identifier,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.QualifiedName
}

data class PropertyAssignment(
    val name: NameNode,
    val initializer: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.PropertyAssignment
}

data class ShorthandPropertyAssignment(
    val name: Identifier,
    val objectAssignmentInitializer: Expression? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.ShorthandPropertyAssignment
}

data class SpreadAssignment(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.SpreadAssignment
}

data class ComputedPropertyName(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.ComputedPropertyName
}

data class ObjectBindingPattern(
    val elements: List<BindingElement>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.ObjectBindingPattern
}

data class ArrayBindingPattern(
    val elements: List<Node>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.ArrayBindingPattern
}

data class BindingElement(
    val propertyName: NameNode? = null,
    val name: Expression,
    val initializer: Expression? = null,
    val dotDotDotToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.BindingElement
}

data class CaseClause(
    val expression: Expression,
    val statements: List<Statement>,
    val singleLine: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.CaseClause
}

data class DefaultClause(
    val statements: List<Statement>,
    val singleLine: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.DefaultClause
}

data class CatchClause(
    val variableDeclaration: VariableDeclaration? = null,
    val block: Block,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.CatchClause
}

data class ModuleBlock(
    val statements: List<Statement>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.ModuleBlock
}

data class NamespaceImport(
    val name: Identifier,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.NamespaceImport
}

data class NamedImports(
    val elements: List<ImportSpecifier>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.NamedImports
}

data class ImportSpecifier(
    val propertyName: Identifier? = null,
    val name: Identifier,
    val isTypeOnly: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.ImportSpecifier
}

data class NamespaceExport(
    val name: Identifier,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.NamespaceExport
}

data class NamedExports(
    val elements: List<ExportSpecifier>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.NamedExports
}

data class ExportSpecifier(
    val propertyName: Identifier? = null,
    val name: Identifier,
    val isTypeOnly: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.ExportSpecifier
}

data class ImportClause(
    val name: Identifier? = null,
    val namedBindings: Node? = null,
    val isTypeOnly: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.ImportClause
}

data class ExternalModuleReference(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.ExternalModuleReference
}
