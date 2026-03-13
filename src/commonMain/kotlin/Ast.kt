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
    val hasPrecedingNewLine: Boolean = false,
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
    /** Comments on the same line as the opening `{` (e.g., `{ // error`). */
    val openBraceTrailingComments: List<Comment>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
    /** Source position of the closing `}` token. */
    val closeBracePos: Int = -1,
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
    /** Inline comments between the last declaration and `;` (e.g. `/*number*/` in `var z = x.then() /*number*/;`). */
    val preSemicolonComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.VariableStatement
}

data class ExpressionStatement(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
    /** Inline comments between the expression and `;` (e.g. `/*3*/` in `Array /*3*/;`). */
    val preSemicolonComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.ExpressionStatement
}

data class IfStatement(
    val expression: Expression,
    val thenStatement: Statement,
    val elseStatement: Statement? = null,
    val afterKeywordComments: List<Comment>? = null,
    val afterOpenParenComments: List<Comment>? = null,
    val beforeCloseParenComments: List<Comment>? = null,
    val afterCloseParenComments: List<Comment>? = null,
    val beforeElseComments: List<Comment>? = null,
    val afterElseComments: List<Comment>? = null,
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
    val afterDoComments: List<Comment>? = null,
    val beforeWhileComments: List<Comment>? = null,
    val afterWhileComments: List<Comment>? = null,
    val afterOpenParenComments: List<Comment>? = null,
    val beforeCloseParenComments: List<Comment>? = null,
    val afterCloseParenComments: List<Comment>? = null,
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
    val afterKeywordComments: List<Comment>? = null,
    val afterOpenParenComments: List<Comment>? = null,
    val beforeCloseParenComments: List<Comment>? = null,
    val afterCloseParenComments: List<Comment>? = null,
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
    val afterKeywordComments: List<Comment>? = null,
    val afterOpenParenComments: List<Comment>? = null,
    val afterInitComments: List<Comment>? = null,
    val afterSemicolon1Comments: List<Comment>? = null,
    val afterConditionComments: List<Comment>? = null,
    val afterSemicolon2Comments: List<Comment>? = null,
    val beforeCloseParenComments: List<Comment>? = null,
    val afterCloseParenComments: List<Comment>? = null,
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
    val afterKeywordComments: List<Comment>? = null,
    val afterOpenParenComments: List<Comment>? = null,
    val afterInitComments: List<Comment>? = null,
    val afterInComments: List<Comment>? = null,
    val beforeCloseParenComments: List<Comment>? = null,
    val afterCloseParenComments: List<Comment>? = null,
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
    val afterKeywordComments: List<Comment>? = null,
    val afterOpenParenComments: List<Comment>? = null,
    val afterInitComments: List<Comment>? = null,
    val afterOfComments: List<Comment>? = null,
    val beforeCloseParenComments: List<Comment>? = null,
    val afterCloseParenComments: List<Comment>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.ForOfStatement
}

data class ContinueStatement(
    val label: Identifier? = null,
    val keywordTrailingComments: List<Comment>? = null,
    val labelTrailingComments: List<Comment>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.ContinueStatement
}

data class BreakStatement(
    val label: Identifier? = null,
    val keywordTrailingComments: List<Comment>? = null,
    val labelTrailingComments: List<Comment>? = null,
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
    val afterKeywordComments: List<Comment>? = null,
    val afterOpenParenComments: List<Comment>? = null,
    val beforeCloseParenComments: List<Comment>? = null,
    val afterCloseParenComments: List<Comment>? = null,
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
    val afterKeywordComments: List<Comment>? = null,
    val afterOpenParenComments: List<Comment>? = null,
    val beforeCloseParenComments: List<Comment>? = null,
    val afterCloseParenComments: List<Comment>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
    val closingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.SwitchStatement
}

data class LabeledStatement(
    val label: Identifier,
    val statement: Statement,
    /** Comments between the label identifier and `:` (e.g. `foo /*0*/:`) */
    val afterLabelComments: List<Comment>? = null,
    /** Comments between `:` and the labeled statement (e.g. `: /*1*/ switch`) */
    val afterColonComments: List<Comment>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.LabeledStatement
}

data class ThrowStatement(
    val expression: Expression?,
    val afterKeywordComments: List<Comment>? = null,
    val preSemicolonComments: List<Comment>? = null,
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
    val afterTryComments: List<Comment>? = null,
    val afterTryBlockComments: List<Comment>? = null,
    val afterCatchBlockComments: List<Comment>? = null,
    val afterFinallyComments: List<Comment>? = null,
    val afterFinallyBlockComments: List<Comment>? = null,
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

/**
 * A synthetic statement containing raw JavaScript code to be emitted verbatim.
 * Used for emitting TypeScript runtime helpers (e.g. `__importStar`, `__importDefault`).
 */
data class RawStatement(
    val code: String,
    override val pos: Int = -1,
    override val end: Int = -1,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Statement {
    override val kind: SyntaxKind = SyntaxKind.NotEmittedStatement // reuse kind for simplicity
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
    val decorators: List<Decorator>? = null,
    val beforeOpenBraceComments: List<Comment>? = null,
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
    /** Raw source text of the assert/with clause, e.g. ` assert { type: "json" }`. Null if absent. */
    val assertClause: String? = null,
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
    /** Raw source text of the assert/with clause, e.g. ` assert { type: "json" }`. Null if absent. */
    val assertClause: String? = null,
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
    /** Same-line comments between the name (or type annotation) and the `=` initializer. */
    val nameTrailingComments: List<Comment>? = null,
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
    /** Raw source text for identifiers with \uXXXX escape sequences (e.g. `\u0061`); null if same as [text]. */
    val rawText: String? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.Identifier
    /** The text to emit: [rawText] if set (preserves \uXXXX escapes), otherwise [text]. */
    val emitText: String get() = rawText ?: text
}

data class StringLiteralNode(
    val text: String,
    val singleQuote: Boolean = false,
    /** Raw source content between the quotes, preserving escape sequences (e.g. `\u2730`). */
    val rawText: String? = null,
    /** True if this string literal was unterminated (no closing quote found). */
    val isUnterminated: Boolean = false,
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
    val isUnterminated: Boolean = false,
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
    val isUnterminated: Boolean = false,
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
    /** Inline comments that appear right after `[` without a preceding newline. */
    val openBracketComments: List<Comment>? = null,
    /** Source position of the closing `]` token. */
    val closeBracketPos: Int = -1,
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
    /** Source position of the closing `}` token. */
    val closeBracePos: Int = -1,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.ObjectLiteralExpression
}

data class PropertyAccessExpression(
    val expression: Expression,
    val name: Identifier,
    val questionDotToken: Boolean = false,
    /** True when the `.` appears on a new line relative to the preceding expression (chained calls). */
    val newLineBefore: Boolean = false,
    /** True when the property name appears on a new line after the `.` (dot at end of previous line). */
    val newLineAfterDot: Boolean = false,
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
    /** Comments inside the `()` when the argument list is empty, e.g. `a(/*1*/)`. */
    val innerComments: List<Comment>? = null,
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
    val innerComments: List<Comment>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
    /** Type arguments that appear BEFORE the constructor expression (e.g. `new <T>Expr`).
     *  TypeScript keeps these in JS output (unlike trailing type args which are erased). */
    val leadingTypeArguments: List<TypeNode>? = null,
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
    /** Comments on new lines before `)` (e.g. `//close`, `/*3*/` in `(\n  "foo"\n  //close\n  /*3*/ )`). */
    val beforeCloseParenComments: List<Comment>? = null,
    /** Comments immediately after `)` on the same line (e.g. `/*4*/` in `(expr)/*4*/`).
     *  Stored separately from [trailingComments] to avoid double-emit by outer context emitters. */
    val afterCloseParenComments: List<Comment>? = null,
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
    val inAsyncContext: Boolean = true,
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
    /** Comments that appear before the operator (between left operand and operator). */
    val operatorLeadingComments: List<Comment>? = null,
    /** Comments that appear after the operator but before the right operand (inline, no preceding newline). */
    val operatorTrailingComments: List<Comment>? = null,
    /** True if the operator token was preceded by a line break in the source (even if no comments). */
    val operatorHasPrecedingLineBreak: Boolean = false,
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
    /** Comments between `yield` and `*` (e.g. `yield /*c*/* expr`). */
    val yieldAsteriskComments: List<Comment>? = null,
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
    val decorators: List<Decorator>? = null,
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

/**
 * A synthesized comma-list expression wrapped in parentheses, emitted with each
 * element on its own line. Used for class expressions with static properties:
 *   `var v = (_a = class C { }, _a.x = 1, _a)`
 * but formatted as:
 *   `var v = (_a = class C {\n    },\n    _a.x = 1,\n    _a);`
 */
data class CommaListExpression(
    val elements: List<Expression>,
    override val pos: Int = -1,
    override val end: Int = -1,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.Unknown
}

/**
 * Returns a copy of this expression with [comments] as its leading comments.
 * Used to propagate leading trivia comments (e.g., inside array literals).
 * Falls back to `this` for uncommon expression types.
 */
fun Expression.withLeadingComments(comments: List<Comment>?): Expression {
    if (comments.isNullOrEmpty()) return this
    return when (this) {
        is Identifier -> copy(leadingComments = comments)
        is StringLiteralNode -> copy(leadingComments = comments)
        is NumericLiteralNode -> copy(leadingComments = comments)
        is BigIntLiteralNode -> copy(leadingComments = comments)
        is RegularExpressionLiteralNode -> copy(leadingComments = comments)
        is NoSubstitutionTemplateLiteralNode -> copy(leadingComments = comments)
        is TemplateExpression -> copy(leadingComments = comments)
        is ArrayLiteralExpression -> copy(leadingComments = comments)
        is ObjectLiteralExpression -> copy(leadingComments = comments)
        is PropertyAccessExpression -> copy(leadingComments = comments)
        is ElementAccessExpression -> copy(leadingComments = comments)
        is CallExpression -> copy(leadingComments = comments)
        is NewExpression -> copy(leadingComments = comments)
        is TaggedTemplateExpression -> copy(leadingComments = comments)
        is TypeAssertionExpression -> copy(leadingComments = comments)
        is ParenthesizedExpression -> copy(leadingComments = comments)
        is FunctionExpression -> copy(leadingComments = comments)
        is ArrowFunction -> copy(leadingComments = comments)
        is DeleteExpression -> copy(leadingComments = comments)
        is TypeOfExpression -> copy(leadingComments = comments)
        is VoidExpression -> copy(leadingComments = comments)
        is AwaitExpression -> copy(leadingComments = comments)
        is PrefixUnaryExpression -> copy(leadingComments = comments)
        is PostfixUnaryExpression -> copy(leadingComments = comments)
        is BinaryExpression -> copy(leadingComments = comments)
        is ConditionalExpression -> copy(leadingComments = comments)
        is YieldExpression -> copy(leadingComments = comments)
        is SpreadElement -> copy(leadingComments = comments)
        is ClassExpression -> copy(leadingComments = comments)
        is AsExpression -> copy(leadingComments = comments)
        is NonNullExpression -> copy(leadingComments = comments)
        is SatisfiesExpression -> copy(leadingComments = comments)
        is MetaProperty -> copy(leadingComments = comments)
        is OmittedExpression -> copy(leadingComments = comments)
        is CommaListExpression -> copy(leadingComments = comments)
        is ComputedPropertyName -> copy(leadingComments = comments)
        is ObjectBindingPattern -> copy(leadingComments = comments)
        is ArrayBindingPattern -> copy(leadingComments = comments)
        is JsxElement -> copy(leadingComments = comments)
        is JsxSelfClosingElement -> copy(leadingComments = comments)
        is JsxFragment -> copy(leadingComments = comments)
    }
}

fun Expression.withTrailingComments(comments: List<Comment>?): Expression {
    if (comments.isNullOrEmpty()) return this
    return when (this) {
        is Identifier -> copy(trailingComments = comments)
        is StringLiteralNode -> copy(trailingComments = comments)
        is NumericLiteralNode -> copy(trailingComments = comments)
        is BigIntLiteralNode -> copy(trailingComments = comments)
        is RegularExpressionLiteralNode -> copy(trailingComments = comments)
        is NoSubstitutionTemplateLiteralNode -> copy(trailingComments = comments)
        is TemplateExpression -> copy(trailingComments = comments)
        is ArrayLiteralExpression -> copy(trailingComments = comments)
        is ObjectLiteralExpression -> copy(trailingComments = comments)
        is PropertyAccessExpression -> copy(trailingComments = comments)
        is ElementAccessExpression -> copy(trailingComments = comments)
        is CallExpression -> copy(trailingComments = comments)
        is NewExpression -> copy(trailingComments = comments)
        is TaggedTemplateExpression -> copy(trailingComments = comments)
        is TypeAssertionExpression -> copy(trailingComments = comments)
        is ParenthesizedExpression -> copy(trailingComments = comments)
        is FunctionExpression -> copy(trailingComments = comments)
        is ArrowFunction -> copy(trailingComments = comments)
        is DeleteExpression -> copy(trailingComments = comments)
        is TypeOfExpression -> copy(trailingComments = comments)
        is VoidExpression -> copy(trailingComments = comments)
        is AwaitExpression -> copy(trailingComments = comments)
        is PrefixUnaryExpression -> copy(trailingComments = comments)
        is PostfixUnaryExpression -> copy(trailingComments = comments)
        is BinaryExpression -> copy(trailingComments = comments)
        is ConditionalExpression -> copy(trailingComments = comments)
        is YieldExpression -> copy(trailingComments = comments)
        is SpreadElement -> copy(trailingComments = comments)
        is ClassExpression -> copy(trailingComments = comments)
        is AsExpression -> copy(trailingComments = comments)
        is NonNullExpression -> copy(trailingComments = comments)
        is SatisfiesExpression -> copy(trailingComments = comments)
        is MetaProperty -> copy(trailingComments = comments)
        is OmittedExpression -> copy(trailingComments = comments)
        is CommaListExpression -> copy(trailingComments = comments)
        is ComputedPropertyName -> copy(trailingComments = comments)
        is ObjectBindingPattern -> copy(trailingComments = comments)
        is ArrayBindingPattern -> copy(trailingComments = comments)
        is JsxElement -> copy(trailingComments = comments)
        is JsxSelfClosingElement -> copy(trailingComments = comments)
        is JsxFragment -> copy(trailingComments = comments)
    }
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
    val type: TypeNode? = null,
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
    /** When true, this parameter is a comment-only placeholder for an empty parameter list. */
    val isCommentPlaceholder: Boolean = false,
    val dotDotDotTrailingComments: List<Comment>? = null,
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
    val hasTrailingComma: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.ObjectBindingPattern
}

data class ArrayBindingPattern(
    val elements: List<Node>,
    val hasTrailingComma: Boolean = false,
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
    val afterCaseComments: List<Comment>? = null,
    val afterExprComments: List<Comment>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
    val labelTrailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.CaseClause
}

data class DefaultClause(
    val statements: List<Statement>,
    val singleLine: Boolean = false,
    val afterDefaultComments: List<Comment>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
    val labelTrailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.DefaultClause
}

data class CatchClause(
    val variableDeclaration: VariableDeclaration? = null,
    val block: Block,
    val afterCatchComments: List<Comment>? = null,
    val afterOpenParenComments: List<Comment>? = null,
    val beforeCloseParenComments: List<Comment>? = null,
    val afterCloseParenComments: List<Comment>? = null,
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

// ===========================================================================
// JSX nodes
// ===========================================================================

data class JsxAttribute(
    val name: String,
    val value: Node?,  // null = boolean true; StringLiteralNode or JsxExpressionContainer
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.JsxAttribute
}

data class JsxSpreadAttribute(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.JsxSpreadAttribute
}

data class JsxOpeningElement(
    val tagName: Expression,
    val attributes: List<Node>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.JsxOpeningElement
}

data class JsxClosingElement(
    val tagName: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.JsxClosingElement
}

data class JsxElement(
    val openingElement: JsxOpeningElement,
    val children: List<Node>,
    val closingElement: JsxClosingElement,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.JsxElement
}

data class JsxSelfClosingElement(
    val tagName: Expression,
    val attributes: List<Node>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.JsxSelfClosingElement
}

data class JsxText(
    val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.JsxText
}

data class JsxExpressionContainer(
    val expression: Expression?,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Node {
    override val kind: SyntaxKind = SyntaxKind.JsxExpression
}

data class JsxFragment(
    val children: List<Node>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : Expression {
    override val kind: SyntaxKind = SyntaxKind.JsxFragment
}
