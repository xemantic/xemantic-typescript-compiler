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

// (TSGO.7-f) THE SYNTAX THE LOWERING WALKS: `-core`'s AST classes, moved into KIR with their comment-carrying and
// parser-bookkeeping fields dropped. `front/TsgoSyntax.kt` builds them from tsgo's tree.

package com.xemantic.typescript.compiler.kir.ast
/** A name position: an identifier, a string or numeric literal, a computed or private name. */
public typealias NameNode = Expression
public sealed interface Node {
    public val kind: SyntaxKind
    public val pos: Int
    public val end: Int
}
public abstract class NodeBase {
    public var parent: Node? = null
}
public enum class ModifierFlag {
    Export, Default, Declare, Abstract, Public, Private, Protected,
    Static, Readonly, Override, Async, Const, In, Out, Accessor,
}
public sealed interface Statement : Node
public sealed interface Expression : Node
public sealed interface Declaration : Statement
public sealed interface TypeNode : Node
public sealed interface ClassElement : Node
public data class SourceFile(
    public val fileName: String,
    public val statements: List<Statement>,
    public val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
    public val moduleSpecifiers: List<String> = emptyList(),
    public val referencedPaths: List<String> = emptyList(),
    public val referencedTypes: List<String> = emptyList(),
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.SourceFile
}
public data class Block(
    public val statements: List<Statement>,
    public val multiLine: Boolean = true,
    override val pos: Int = 0,
    override val end: Int = 0,
    public val closeBracePos: Int = -1,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.Block
}
public data class EmptyStatement(
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.EmptyStatement
}
public data class VariableStatement(
    public val declarationList: VariableDeclarationList,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.VariableStatement
}
public data class ExpressionStatement(
    public val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.ExpressionStatement
}
public data class IfStatement(
    public val expression: Expression,
    public val thenStatement: Statement,
    public val elseStatement: Statement? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.IfStatement
}
public data class DoStatement(
    public val statement: Statement,
    public val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.DoStatement
}
public data class WhileStatement(
    public val expression: Expression,
    public val statement: Statement,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.WhileStatement
}
public data class ForStatement(
    public val initializer: Node? = null,
    public val condition: Expression? = null,
    public val incrementor: Expression? = null,
    public val statement: Statement,
    public val syntheticSemicolons: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.ForStatement
}
public data class ForInStatement(
    public val initializer: Node,
    public val expression: Expression,
    public val statement: Statement,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.ForInStatement
}
public data class ForOfStatement(
    public val awaitModifier: Boolean = false,
    public val initializer: Node,
    public val expression: Expression,
    public val statement: Statement,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.ForOfStatement
}
public data class ContinueStatement(
    public val label: Identifier? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.ContinueStatement
}
public data class BreakStatement(
    public val label: Identifier? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.BreakStatement
}
public data class ReturnStatement(
    public val expression: Expression? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.ReturnStatement
}
public data class WithStatement(
    public val expression: Expression,
    public val statement: Statement,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.WithStatement
}
public data class SwitchStatement(
    public val expression: Expression,
    public val caseBlock: List<Node>,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.SwitchStatement
}
public data class LabeledStatement(
    public val label: Identifier,
    public val statement: Statement,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.LabeledStatement
}
public data class ThrowStatement(
    public val expression: Expression?,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.ThrowStatement
}
public data class TryStatement(
    public val tryBlock: Block,
    public val catchClause: CatchClause? = null,
    public val finallyBlock: Block? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.TryStatement
}
public data class DebuggerStatement(
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.DebuggerStatement
}
public data class NotEmittedStatement(
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.NotEmittedStatement
}
public data class RawStatement(
    public val code: String,
    override val pos: Int = -1,
    override val end: Int = -1,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.NotEmittedStatement
}
public data class FunctionDeclaration(
    public val name: Identifier? = null,
    public val typeParameters: List<TypeParameter>? = null,
    public val parameters: List<Parameter>,
    public val type: TypeNode? = null,
    public val body: Block? = null,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    public val asteriskToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    public val signatureAborted: Boolean = false,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.FunctionDeclaration
}
public data class ClassDeclaration(
    public val name: Identifier? = null,
    public val typeParameters: List<TypeParameter>? = null,
    public val heritageClauses: List<HeritageClause>? = null,
    public val members: List<ClassElement>,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    public val decorators: List<Decorator>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.ClassDeclaration
}
public data class InterfaceDeclaration(
    public val name: Identifier,
    public val typeParameters: List<TypeParameter>? = null,
    public val heritageClauses: List<HeritageClause>? = null,
    public val members: List<ClassElement>,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.InterfaceDeclaration
}
public data class TypeAliasDeclaration(
    public val name: Identifier,
    public val typeParameters: List<TypeParameter>? = null,
    public val type: TypeNode,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.TypeAliasDeclaration
}
public data class EnumDeclaration(
    public val name: Identifier,
    public val members: List<EnumMember>,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.EnumDeclaration
}
public data class ModuleDeclaration(
    public val name: Expression,
    public val body: Node? = null,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.ModuleDeclaration
}
public data class ImportDeclaration(
    public val importClause: ImportClause? = null,
    public val moduleSpecifier: Expression,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    public val assertClause: String? = null,
    public val assertClausePos: Int = -1,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.ImportDeclaration
}
public data class ImportEqualsDeclaration(
    public val name: Identifier,
    public val moduleReference: Node,
    public val isTypeOnly: Boolean = false,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.ImportEqualsDeclaration
}
public data class ExportDeclaration(
    public val exportClause: Node? = null,
    public val moduleSpecifier: Expression? = null,
    public val isTypeOnly: Boolean = false,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    public val assertClause: String? = null,
    public val assertClausePos: Int = -1,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.ExportDeclaration
}
public data class ExportAssignment(
    public val expression: Expression,
    public val isExportEquals: Boolean = false,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    public val type: TypeNode? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.ExportAssignment
}
public data class VariableDeclaration(
    public val name: Expression,
    public val type: TypeNode? = null,
    public val initializer: Expression? = null,
    public val exclamationToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    public val typeFromJSDoc: Boolean = false,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.VariableDeclaration
}
public data class VariableDeclarationList(
    public val declarations: List<VariableDeclaration>,
    public val flags: SyntaxKind,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.VariableDeclarationList
}
public data class Identifier(
    public val text: String,
    public val rawText: String? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.Identifier
    public val emitText: String get() = rawText ?: text
}
public data class StringLiteralNode(
    public val text: String,
    public val singleQuote: Boolean = false,
    public val rawText: String? = null,
    public val isUnterminated: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.StringLiteral
}
public data class NumericLiteralNode(
    public val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.NumericLiteral
}
public data class BigIntLiteralNode(
    public val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.BigIntLiteral
}
public data class RegularExpressionLiteralNode(
    public val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.RegularExpressionLiteral
}
public data class NoSubstitutionTemplateLiteralNode(
    public val text: String,
    public val isUnterminated: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.NoSubstitutionTemplateLiteral
}
public data class TemplateExpression(
    public val head: StringLiteralNode,
    public val templateSpans: List<TemplateSpan>,
    public val isUnterminated: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.TemplateExpression
}
public data class TemplateSpan(
    public val expression: Expression,
    public val literal: Node,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.TemplateSpan
}
public data class ArrayLiteralExpression(
    public val elements: List<Expression>,
    public val multiLine: Boolean = false,
    public val hasTrailingComma: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    public val closeBracketPos: Int = -1,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ArrayLiteralExpression
}
public data class ObjectLiteralExpression(
    public val properties: List<Node>,
    public val multiLine: Boolean = false,
    public val hasTrailingComma: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    public val closeBracePos: Int = -1,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ObjectLiteralExpression
}
public data class PropertyAccessExpression(
    public val expression: Expression,
    public val name: Identifier,
    public val questionDotToken: Boolean = false,
    public val newLineBefore: Boolean = false,
    public val newLineAfterDot: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.PropertyAccessExpression
}
public data class ElementAccessExpression(
    public val expression: Expression,
    public val argumentExpression: Expression,
    public val questionDotToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ElementAccessExpression
}
public data class CallExpression(
    public val expression: Expression,
    public val typeArguments: List<TypeNode>? = null,
    public val arguments: List<Expression>,
    public val questionDotToken: Boolean = false,
    public val multiLine: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.CallExpression
}
public data class NewExpression(
    public val expression: Expression,
    public val typeArguments: List<TypeNode>? = null,
    public val arguments: List<Expression>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    public val leadingTypeArguments: List<TypeNode>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.NewExpression
}
public data class TaggedTemplateExpression(
    public val tag: Expression,
    public val typeArguments: List<TypeNode>? = null,
    public val template: Node,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.TaggedTemplateExpression
}
public data class TypeAssertionExpression(
    public val type: TypeNode,
    public val expression: Expression,
    public val headerEnd: Int = 0,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.TypeAssertionExpression
}
public data class ParenthesizedExpression(
    public val expression: Expression,
    public val instantiationEnd: Int? = null,
    public val instantiationTerminatesChain: Boolean = false,
    public val jsdocCastType: TypeNode? = null,
    public val closeParenMissing: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ParenthesizedExpression
}
public data class FunctionExpression(
    public val name: Identifier? = null,
    public val typeParameters: List<TypeParameter>? = null,
    public val parameters: List<Parameter>,
    public val type: TypeNode? = null,
    public val body: Block,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    public val asteriskToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.FunctionExpression
}
public data class ArrowFunction(
    public val typeParameters: List<TypeParameter>? = null,
    public val parameters: List<Parameter>,
    public val type: TypeNode? = null,
    public val body: Node,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    public val asteriskToken: Boolean = false,
    public val hasParenthesizedParameters: Boolean = true,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ArrowFunction
}
public data class DeleteExpression(
    public val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.DeleteExpression
}
public data class TypeOfExpression(
    public val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.TypeOfExpression
}
public data class VoidExpression(
    public val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.VoidExpression
}
public data class AwaitExpression(
    public val expression: Expression,
    public val inAsyncContext: Boolean = true,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.AwaitExpression
}
public data class PrefixUnaryExpression(
    public val operator: SyntaxKind,
    public val operand: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.PrefixUnaryExpression
}
public data class PostfixUnaryExpression(
    public val operand: Expression,
    public val operator: SyntaxKind,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.PostfixUnaryExpression
}
public data class BinaryExpression(
    public val left: Expression,
    public val operator: SyntaxKind,
    public val right: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    public val operatorHasPrecedingLineBreak: Boolean = false,
    public val multiLineComma: Boolean = false,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.BinaryExpression
}
public data class ConditionalExpression(
    public val condition: Expression,
    public val whenTrue: Expression,
    public val whenFalse: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ConditionalExpression
}
public data class YieldExpression(
    public val expression: Expression? = null,
    public val asteriskToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.YieldExpression
}
public data class SpreadElement(
    public val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.SpreadElement
}
public data class ClassExpression(
    public val name: Identifier? = null,
    public val typeParameters: List<TypeParameter>? = null,
    public val heritageClauses: List<HeritageClause>? = null,
    public val members: List<ClassElement>,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    public val decorators: List<Decorator>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ClassExpression
}
public data class AsExpression(
    public val expression: Expression,
    public val type: TypeNode,
    public val tightEnd: Int = 0,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.AsExpression
}
public data class NonNullExpression(
    public val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.NonNullExpression
}
public data class SatisfiesExpression(
    public val expression: Expression,
    public val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.SatisfiesExpression
}
public data class MetaProperty(
    public val keywordToken: SyntaxKind,
    public val name: Identifier,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.MetaProperty
}
public data class OmittedExpression(
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.OmittedExpression
}
public data class CommaListExpression(
    public val elements: List<Expression>,
    override val pos: Int = -1,
    override val end: Int = -1,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.Unknown
}
public data class PropertyDeclaration(
    public val name: NameNode,
    public val type: TypeNode? = null,
    public val initializer: Expression? = null,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    public val questionToken: Boolean = false,
    public val exclamationToken: Boolean = false,
    public val decorators: List<Decorator>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    public val typeFromJSDoc: Boolean = false,
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.PropertyDeclaration
}
public data class MethodDeclaration(
    public val name: NameNode,
    public val typeParameters: List<TypeParameter>? = null,
    public val parameters: List<Parameter>,
    public val type: TypeNode? = null,
    public val body: Block? = null,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    public val asteriskToken: Boolean = false,
    public val questionToken: Boolean = false,
    public val decorators: List<Decorator>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.MethodDeclaration
}
public data class Constructor(
    public val parameters: List<Parameter>,
    public val body: Block? = null,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    public val decorators: List<Decorator>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.ConstructorDeclaration
}
public data class GetAccessor(
    public val name: NameNode,
    public val parameters: List<Parameter>,
    public val type: TypeNode? = null,
    public val body: Block? = null,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    public val decorators: List<Decorator>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    public val bodylessEnd: Int = -1,
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.GetAccessor
}
public data class SetAccessor(
    public val name: NameNode,
    public val parameters: List<Parameter>,
    public val type: TypeNode? = null,
    public val body: Block? = null,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    public val decorators: List<Decorator>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    public val bodylessEnd: Int = -1,
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.SetAccessor
}
public data class IndexSignature(
    public val parameters: List<Parameter>,
    public val type: TypeNode? = null,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.IndexSignature
}
public data class SemicolonClassElement(
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.SemicolonClassElement
}
public data class ClassStaticBlockDeclaration(
    public val body: Block,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.ClassStaticBlockDeclaration
}
public data class TypeReference(
    public val typeName: Node,
    public val typeArguments: List<TypeNode>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TypeReference
}
public data class FunctionType(
    public val typeParameters: List<TypeParameter>? = null,
    public val parameters: List<Parameter>,
    public val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.FunctionType
}
public data class ConstructorType(
    public val typeParameters: List<TypeParameter>? = null,
    public val parameters: List<Parameter>,
    public val type: TypeNode,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ConstructorType
}
public data class TypeQuery(
    public val exprName: Node,
    public val typeArguments: List<TypeNode>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TypeQuery
}
public data class TypeLiteral(
    public val members: List<ClassElement>,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TypeLiteral
}
public data class ArrayType(
    public val elementType: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ArrayType
}
public data class TupleType(
    public val elements: List<TypeNode>,
    public val elementOptional: List<Boolean>? = null,
    public val elementNames: List<String?>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TupleType
}
public data class UnionType(
    public val types: List<TypeNode>,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.UnionType
}
public data class IntersectionType(
    public val types: List<TypeNode>,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.IntersectionType
}
public data class ConditionalType(
    public val checkType: TypeNode,
    public val extendsType: TypeNode,
    public val trueType: TypeNode,
    public val falseType: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ConditionalType
}
public data class IndexedAccessType(
    public val objectType: TypeNode,
    public val indexType: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.IndexedAccessType
}
public data class MappedType(
    public val typeParameter: TypeParameter,
    public val nameType: TypeNode? = null,
    public val type: TypeNode? = null,
    public val questionToken: Boolean = false,
    public val readonlyToken: Boolean = false,
    public val readonlyMinus: Boolean = false,
    public val questionMinus: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.MappedType
}
public data class LiteralType(
    public val literal: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.LiteralType
}
public data class TemplateLiteralType(
    public val head: StringLiteralNode,
    public val templateSpans: List<TemplateLiteralTypeSpan>,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TemplateLiteralType
}
public data class TemplateLiteralTypeSpan(
    public val type: TypeNode,
    public val literal: Node,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TemplateLiteralTypeSpan
}
public data class ParenthesizedType(
    public val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ParenthesizedType
}
public data class TypePredicate(
    public val parameterName: Node,
    public val type: TypeNode? = null,
    public val assertsModifier: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TypePredicate
}
public data class TypeOperator(
    public val operator: SyntaxKind,
    public val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TypeOperator
}
public data class RestType(
    public val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.RestType
}
public data class NamedTupleMember(
    public val name: Identifier,
    public val type: TypeNode,
    public val dotDotDotToken: Boolean = false,
    public val questionToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.NamedTupleMember
}
public data class OptionalType(
    public val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.OptionalType
}
public data class ImportType(
    public val argument: TypeNode,
    public val qualifier: Node? = null,
    public val typeArguments: List<TypeNode>? = null,
    public val isTypeOf: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ImportType
}
public data class ThisType(
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ThisType
}
public data class InferType(
    public val typeParameter: TypeParameter,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.InferType
}
public data class KeywordTypeNode(
    override val kind: SyntaxKind,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), TypeNode {
}
public data class Parameter(
    public val name: Expression,
    public val type: TypeNode? = null,
    public val initializer: Expression? = null,
    public val dotDotDotToken: Boolean = false,
    public val questionToken: Boolean = false,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    public val decorators: List<Decorator>? = null,
    public val typeFromJSDoc: Boolean = false,
    public val commaRecovered: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.Parameter
}
public data class Decorator(
    public val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.Decorator
}
public data class HeritageClause(
    public val token: SyntaxKind,
    public val types: List<ExpressionWithTypeArguments>,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.HeritageClause
}
public data class ExpressionWithTypeArguments(
    public val expression: Expression,
    public val typeArguments: List<TypeNode>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.ExpressionWithTypeArguments
}
public data class EnumMember(
    public val name: NameNode,
    public val initializer: Expression? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.EnumMember
}
public data class TypeParameter(
    public val name: Identifier,
    public val constraint: TypeNode? = null,
    public val default: TypeNode? = null,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    public val fromJSDoc: Boolean = false,
    public val jsDocTagPos: Int = -1,
    public val jsDocTagEnd: Int = -1,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.TypeParameter
}
public data class QualifiedName(
    public val left: Node,
    public val right: Identifier,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.QualifiedName
}
public data class PropertyAssignment(
    public val name: NameNode,
    public val initializer: Expression,
    public val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.PropertyAssignment
}
public data class ShorthandPropertyAssignment(
    public val name: Identifier,
    public val objectAssignmentInitializer: Expression? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.ShorthandPropertyAssignment
}
public data class SpreadAssignment(
    public val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.SpreadAssignment
}
public data class ComputedPropertyName(
    public val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ComputedPropertyName
}
public data class ObjectBindingPattern(
    public val elements: List<BindingElement>,
    public val hasTrailingComma: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ObjectBindingPattern
}
public data class ArrayBindingPattern(
    public val elements: List<Node>,
    public val hasTrailingComma: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ArrayBindingPattern
}
public data class BindingElement(
    public val propertyName: NameNode? = null,
    public val name: Expression,
    public val initializer: Expression? = null,
    public val dotDotDotToken: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.BindingElement
}
public data class CaseClause(
    public val expression: Expression,
    public val statements: List<Statement>,
    public val singleLine: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.CaseClause
}
public data class DefaultClause(
    public val statements: List<Statement>,
    public val singleLine: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.DefaultClause
}
public data class CatchClause(
    public val variableDeclaration: VariableDeclaration? = null,
    public val block: Block,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.CatchClause
}
public data class ModuleBlock(
    public val statements: List<Statement>,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.ModuleBlock
}
public data class NamespaceImport(
    public val name: Identifier,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.NamespaceImport
}
public data class NamedImports(
    public val elements: List<ImportSpecifier>,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.NamedImports
}
public data class ImportSpecifier(
    public val propertyName: Identifier? = null,
    public val name: Identifier,
    public val isTypeOnly: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.ImportSpecifier
}
public data class NamespaceExport(
    public val name: Identifier,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.NamespaceExport
}
public data class NamedExports(
    public val elements: List<ExportSpecifier>,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.NamedExports
}
public data class ExportSpecifier(
    public val propertyName: Identifier? = null,
    public val name: Identifier,
    public val isTypeOnly: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.ExportSpecifier
}
public data class ImportClause(
    public val name: Identifier? = null,
    public val namedBindings: Node? = null,
    public val isTypeOnly: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.ImportClause
}
public data class ExternalModuleReference(
    public val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.ExternalModuleReference
}
public data class JsxAttribute(
    public val name: String,
    public val value: Node?,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.JsxAttribute
}
public data class JsxSpreadAttribute(
    public val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.JsxSpreadAttribute
}
public data class JsxOpeningElement(
    public val tagName: Expression,
    public val attributes: List<Node>,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.JsxOpeningElement
}
public data class JsxClosingElement(
    public val tagName: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.JsxClosingElement
}
public data class JsxElement(
    public val openingElement: JsxOpeningElement,
    public val children: List<Node>,
    public val closingElement: JsxClosingElement,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.JsxElement
}
public data class JsxSelfClosingElement(
    public val tagName: Expression,
    public val attributes: List<Node>,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.JsxSelfClosingElement
}
public data class JsxText(
    public val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.JsxText
}
public data class JsxExpressionContainer(
    public val expression: Expression?,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.JsxExpression
}
public data class JsxFragment(
    public val children: List<Node>,
    override val pos: Int = 0,
    override val end: Int = 0,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.JsxFragment
}
