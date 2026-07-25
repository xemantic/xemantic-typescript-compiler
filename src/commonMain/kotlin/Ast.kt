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

/**
 * INV.2(a): identity/navigation base class extended by every AST node class.
 *
 * [nodeId] is a dense per-file preorder index and [parent] the tree parent, both
 * stamped by [indexSourceFile] (invoked at the end of [Parser.parse]); −1/null =
 * unindexed. Base-class `var`s are excluded from data-class `equals`/`hashCode`/
 * `copy`/`toString`, so structural node keys behave exactly as before and a
 * Transformer `copy()` correctly yields an UNINDEXED node (synthesized trees are
 * never implicitly indexed). Deliberately does NOT implement [Node]: that would
 * add a non-sealed direct subtype to the sealed hierarchy and break exhaustive
 * `when` over [Node]. Access from a [Node]-typed reference via `(node as NodeBase)`.
 */
abstract class NodeBase {
    var nodeId: Int = -1
    var parent: Node? = null

    /**
     * M0.2: the dense per-CLASS dispatch id (see [NodeKind]), assigned by each
     * concrete class's `init` block — so it IS present on `copy()`d and
     * Transformer-synthesized instances (their constructors run the init
     * block), unlike [nodeId]/[parent]. −1 only on a class missing its stamp,
     * which [forEachChild]'s `else -> error(...)` surfaces loudly.
     */
    var kindId: Int = -1
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
    /**
     * Every module specifier referenced by this file, recorded by the parser as it
     * parses (tsc's `SourceFile.imports` equivalent): static `import`/`export ... from`,
     * `import x = require(...)`, dynamic `import(...)` and `require(...)` calls with a
     * string-literal argument at any nesting depth, `import("...")` type positions, and
     * and `import("...")` type positions. Lexically exact — string literals,
     * comments, and regex literals can never contribute (unlike a text scan).
     *
     * `/// <reference path|types>` directives are NOT here — they resolve
     * differently (see [referencedPaths] / [referencedTypes]) and lumping them in
     * made the crawl resolve `path="globals.d.ts"` as a BARE module specifier,
     * which fails.
     */
    val moduleSpecifiers: List<String> = emptyList(),
    /**
     * `/// <reference path="…" />` targets, in source order. These are file paths
     * relative to THIS file's directory (tsc `resolveTripleslashReference`), not
     * module specifiers — and tsc's `processReferencedFiles` pulls each into the
     * program, which is why they are tracked separately (M4.8).
     */
    val referencedPaths: List<String> = emptyList(),
    /**
     * `/// <reference types="…" />` targets: PACKAGE names resolved through the
     * type roots exactly like a tsconfig `types` entry, not relative paths.
     */
    val referencedTypes: List<String> = emptyList(),
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.SourceFile
    init { kindId = NodeKind.SOURCE_FILE }

    /** INV.2(a): number of nodes stamped by [indexSourceFile] (= max nodeId + 1);
     *  sizes array-indexed per-file side tables. Body property — excluded from
     *  data-class `equals`/`hashCode`/`copy` like [TypeParameter.internSalt]. */
    var nodeCount: Int = 0

    /** (M0.4 round 643) Every TypeAliasDeclaration the parser produced for this
     *  file whose TP list carries a default — the pre-spine circular-TP-defaults
     *  producer's candidate set (populateCircularTpDefaults), recorded at the
     *  parse site so the producer needs no tree walk. Lexically exact but
     *  OVER-approximate: a speculative (lookAhead) parse's discarded node may be
     *  recorded — the producer filters through the reach classifier, whose
     *  parent-chain climb classifies a detached node unreached. Body property —
     *  excluded from equals/copy like [nodeCount]. */
    var typeAliasesWithTpDefaults: List<TypeAliasDeclaration> = emptyList()
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
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.Block
    init { kindId = NodeKind.BLOCK }
}

data class EmptyStatement(
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.EmptyStatement
    init { kindId = NodeKind.EMPTY_STATEMENT }
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
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.VariableStatement
    init { kindId = NodeKind.VARIABLE_STATEMENT }
}

data class ExpressionStatement(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
    /** Inline comments between the expression and `;` (e.g. `/*3*/` in `Array /*3*/;`). */
    val preSemicolonComments: List<Comment>? = null,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.ExpressionStatement
    init { kindId = NodeKind.EXPRESSION_STATEMENT }
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
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.IfStatement
    init { kindId = NodeKind.IF_STATEMENT }
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
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.DoStatement
    init { kindId = NodeKind.DO_STATEMENT }
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
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.WhileStatement
    init { kindId = NodeKind.WHILE_STATEMENT }
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
    /** True when both semicolons in the for header were inserted by error recovery (e.g. `for () {}`). */
    val syntheticSemicolons: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.ForStatement
    init { kindId = NodeKind.FOR_STATEMENT }
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
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.ForInStatement
    init { kindId = NodeKind.FOR_IN_STATEMENT }
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
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.ForOfStatement
    init { kindId = NodeKind.FOR_OF_STATEMENT }
}

data class ContinueStatement(
    val label: Identifier? = null,
    val keywordTrailingComments: List<Comment>? = null,
    val labelTrailingComments: List<Comment>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.ContinueStatement
    init { kindId = NodeKind.CONTINUE_STATEMENT }
}

data class BreakStatement(
    val label: Identifier? = null,
    val keywordTrailingComments: List<Comment>? = null,
    val labelTrailingComments: List<Comment>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.BreakStatement
    init { kindId = NodeKind.BREAK_STATEMENT }
}

data class ReturnStatement(
    val expression: Expression? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.ReturnStatement
    init { kindId = NodeKind.RETURN_STATEMENT }
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
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.WithStatement
    init { kindId = NodeKind.WITH_STATEMENT }
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
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.SwitchStatement
    init { kindId = NodeKind.SWITCH_STATEMENT }
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
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.LabeledStatement
    init { kindId = NodeKind.LABELED_STATEMENT }
}

data class ThrowStatement(
    val expression: Expression?,
    val afterKeywordComments: List<Comment>? = null,
    val preSemicolonComments: List<Comment>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.ThrowStatement
    init { kindId = NodeKind.THROW_STATEMENT }
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
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.TryStatement
    init { kindId = NodeKind.TRY_STATEMENT }
}

data class DebuggerStatement(
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.DebuggerStatement
    init { kindId = NodeKind.DEBUGGER_STATEMENT }
}

data class NotEmittedStatement(
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.NotEmittedStatement
    init { kindId = NodeKind.NOT_EMITTED_STATEMENT }
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
) : NodeBase(), Statement {
    override val kind: SyntaxKind = SyntaxKind.NotEmittedStatement // reuse kind for simplicity
    init { kindId = NodeKind.RAW_STATEMENT }
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
    /** True when the parameter list ABORTED (tsc abortParsingList) — the ts-transform elides the whole declaration (reachabilityChecksNoCrash1); other zero-width-missing-body recoveries still print `{ }` (reservedWords3). */
    val signatureAborted: Boolean = false,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.FunctionDeclaration
    init { kindId = NodeKind.FUNCTION_DECLARATION }
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
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.ClassDeclaration
    init { kindId = NodeKind.CLASS_DECLARATION }
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
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.InterfaceDeclaration
    init { kindId = NodeKind.INTERFACE_DECLARATION }
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
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.TypeAliasDeclaration
    init { kindId = NodeKind.TYPE_ALIAS_DECLARATION }
}

data class EnumDeclaration(
    val name: Identifier,
    val members: List<EnumMember>,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.EnumDeclaration
    init { kindId = NodeKind.ENUM_DECLARATION }
}

data class ModuleDeclaration(
    val name: Expression,
    val body: Node? = null,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.ModuleDeclaration
    init { kindId = NodeKind.MODULE_DECLARATION }
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
    /** Source position of the `assert`/`with` keyword starting the attributes clause, or -1 if absent. */
    val assertClausePos: Int = -1,
    /** Comments INTERNAL to the import (between `import`/clause/`from`/specifier tokens), one entry
     *  per inter-token boundary in source/emit order; null entries for empty boundaries. Captured by
     *  the parser, replayed by the emitter (importExportInternalComments). Null when no internal comments. */
    val internalComments: List<List<Comment>?>? = null,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.ImportDeclaration
    init { kindId = NodeKind.IMPORT_DECLARATION }
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
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.ImportEqualsDeclaration
    init { kindId = NodeKind.IMPORT_EQUALS_DECLARATION }
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
    /** Source position of the `assert`/`with` keyword starting the attributes clause, or -1 if absent. */
    val assertClausePos: Int = -1,
    /** Comments INTERNAL to the export (between `export`/clause/`from`/specifier tokens), one entry per
     *  inter-token boundary in source/emit order; see [ImportDeclaration.internalComments]. */
    val internalComments: List<List<Comment>?>? = null,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.ExportDeclaration
    init { kindId = NodeKind.EXPORT_DECLARATION }
}

data class ExportAssignment(
    val expression: Expression,
    val isExportEquals: Boolean = false,
    val modifiers: Set<ModifierFlag> = emptySet(),
    /** B148: JSDoc `@type {T}` annotation on `export default {obj}` in a JS file.
     *  Positions point into the JSDoc text (sub-Parser), so consult it only for
     *  type RESOLUTION (excess-property check), never for position-bearing
     *  diagnostics on the type node itself. */
    val type: TypeNode? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
    /** Comments INTERNAL to `export default <expr>` (after `export`, after `default`, before `;`),
     *  one entry per inter-token boundary; see [ImportDeclaration.internalComments]. */
    val internalComments: List<List<Comment>?>? = null,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.ExportAssignment
    init { kindId = NodeKind.EXPORT_ASSIGNMENT }
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
    /** Same-line comments AFTER the `=` token but BEFORE the initializer value
     *  (e.g. `= // should error\n  [1,2,3]` — the `// should error` comment).
     *  Typically `//` line comments on the same line as `=`. */
    val initializerLeadingTrailingComments: List<Comment>? = null,
    /** True when [type] was synthesized from a leading primitive JSDoc `@type {...}` comment in a JS file. */
    val typeFromJSDoc: Boolean = false,
) : NodeBase(), Declaration {
    override val kind: SyntaxKind = SyntaxKind.VariableDeclaration
    init { kindId = NodeKind.VARIABLE_DECLARATION }
}

data class VariableDeclarationList(
    val declarations: List<VariableDeclaration>,
    val flags: SyntaxKind,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.VariableDeclarationList
    init { kindId = NodeKind.VARIABLE_DECLARATION_LIST }
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
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.Identifier
    init { kindId = NodeKind.IDENTIFIER }
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
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.StringLiteral
    init { kindId = NodeKind.STRING_LITERAL_NODE }
}

data class NumericLiteralNode(
    val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.NumericLiteral
    init { kindId = NodeKind.NUMERIC_LITERAL_NODE }
}

data class BigIntLiteralNode(
    val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.BigIntLiteral
    init { kindId = NodeKind.BIG_INT_LITERAL_NODE }
}

data class RegularExpressionLiteralNode(
    val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.RegularExpressionLiteral
    init { kindId = NodeKind.REGULAR_EXPRESSION_LITERAL_NODE }
}

data class NoSubstitutionTemplateLiteralNode(
    val text: String,
    val isUnterminated: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.NoSubstitutionTemplateLiteral
    init { kindId = NodeKind.NO_SUBSTITUTION_TEMPLATE_LITERAL_NODE }
}

data class TemplateExpression(
    val head: StringLiteralNode,
    val templateSpans: List<TemplateSpan>,
    val isUnterminated: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.TemplateExpression
    init { kindId = NodeKind.TEMPLATE_EXPRESSION }
}

data class TemplateSpan(
    val expression: Expression,
    val literal: Node,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.TemplateSpan
    init { kindId = NodeKind.TEMPLATE_SPAN }
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
    /**
     * Per-element post-comma comments: `postCommaComments[i]` holds same-line comments that
     * appeared after element `i`'s comma (e.g. `elem, // comment\n nextElem`). These are
     * emitted after the comma, not before it.
     */
    val postCommaComments: List<List<Comment>?>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ArrayLiteralExpression
    init { kindId = NodeKind.ARRAY_LITERAL_EXPRESSION }
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
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ObjectLiteralExpression
    init { kindId = NodeKind.OBJECT_LITERAL_EXPRESSION }
}

data class PropertyAccessExpression(
    val expression: Expression,
    val name: Identifier,
    val questionDotToken: Boolean = false,
    /** True when the `.` appears on a new line relative to the preceding expression (chained calls). */
    val newLineBefore: Boolean = false,
    /** True when the property name appears on a new line after the `.` (dot at end of previous line). */
    val newLineAfterDot: Boolean = false,
    /** Same-line `// ...` line comments that appeared between [expression] and the newline
     *  preceding the dot (e.g. `arr // should error\n  .filter(...)`). These terminate
     *  the expression line before the dot drops to a new line. */
    val expressionTrailingLineComments: List<Comment>? = null,
    /** Newline-preceded comments between the receiver and the `.`/`?.` (the `/*2*/`-on-its-own-line
     *  case, e.g. `Array\n /*2*/. toString`). Kept SEPARATE from [name].leadingComments (which holds
     *  the after-dot `/*3*/`). Same-line `/*2*/` (e.g. `Array /*2*/.`) rides the receiver's
     *  trailingComments instead, so the `?.` desugar duplicates it for free.
     *  propertyAccessExpressionInnerComments. */
    val preDotComments: List<Comment>? = null,
    /** When true, the emitter writes newline-preceded internal comments / name at COLUMN 0
     *  (no indentLevel bump). Set only by the `?.` desugar — the synthetic conditional loses indent. */
    val flatComments: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.PropertyAccessExpression
    init { kindId = NodeKind.PROPERTY_ACCESS_EXPRESSION }
}

data class ElementAccessExpression(
    val expression: Expression,
    val argumentExpression: Expression,
    val questionDotToken: Boolean = false,
    /** Comments between the object expression and `[` (e.g. `a /*c*/[`). */
    val preBracketComments: List<Comment>? = null,
    /** Comments between `[` and the argument (e.g. `[ /*c*/ arg`). */
    val argLeadingComments: List<Comment>? = null,
    /** Comments between the argument and `]` (e.g. `arg /*c*/ ]`). */
    val preCloseBracketComments: List<Comment>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ElementAccessExpression
    init { kindId = NodeKind.ELEMENT_ACCESS_EXPRESSION }
}

data class CallExpression(
    val expression: Expression,
    val typeArguments: List<TypeNode>? = null,
    val arguments: List<Expression>,
    val questionDotToken: Boolean = false,
    /** Comments inside the `()` when the argument list is empty, e.g. `a(/*1*/)`. */
    val innerComments: List<Comment>? = null,
    /** When true, emit arguments on separate lines (used for multiline JSX createElement calls). */
    val multiLine: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.CallExpression
    init { kindId = NodeKind.CALL_EXPRESSION }
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
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.NewExpression
    init { kindId = NodeKind.NEW_EXPRESSION }
}

data class TaggedTemplateExpression(
    val tag: Expression,
    val typeArguments: List<TypeNode>? = null,
    val template: Node,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.TaggedTemplateExpression
    init { kindId = NodeKind.TAGGED_TEMPLATE_EXPRESSION }
}

data class TypeAssertionExpression(
    val type: TypeNode,
    val expression: Expression,
    /** Position right after the closing `>` (or right after the type text if `>` was missing). */
    val headerEnd: Int = 0,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.TypeAssertionExpression
    init { kindId = NodeKind.TYPE_ASSERTION_EXPRESSION }
}

data class ParenthesizedExpression(
    val expression: Expression,
    /** Comments on new lines before `)` (e.g. `//close`, `/*3*/` in `(\n  "foo"\n  //close\n  /*3*/ )`). */
    val beforeCloseParenComments: List<Comment>? = null,
    /** Comments immediately after `)` on the same line (e.g. `/*4*/` in `(expr)/*4*/`).
     *  Stored separately from [trailingComments] to avoid double-emit by outer context emitters. */
    val afterCloseParenComments: List<Comment>? = null,
    /** When non-null, this is a synthetic paren wrap around `expr<T>` produced by the
     *  parser when an instantiation expression is followed by `.` or `?.` and the inner
     *  expression contains an optional chain. The value is the source position right
     *  after the closing `>` of the type-argument list. Consumed by the checker for
     *  TS2532 squiggle position computation (covers `expr<T>` from `expr.pos` to
     *  `instantiationEnd`). */
    val instantiationEnd: Int? = null,
    /** expressionWithJSDocTypeArguments: when non-null, this synthetic instantiation paren had
     *  JSDoc-`?` (nullable) type arguments (`foo<?string>`/`foo<string?>`), which tsc preserves
     *  in value-position JS emit (re-printed with the `?` normalized to a prefix). Holds the
     *  normalized `<...>` text; the emitter prints `expr<...>` instead of `(expr)`. */
    val instantiationJsDocTypeArgsText: String? = null,
    /** 17.140b: when non-null, this paren is a JSDoc type cast `/** @type {T} */ (expr)`.
     *  The checker uses this to override the inner expression's type. JS-like files only. */
    val jsdocCastType: TypeNode? = null,
    /** True when the closing `)` was MISSING (parse recovery) — true-end math must not +1. */
    val closeParenMissing: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ParenthesizedExpression
    init { kindId = NodeKind.PARENTHESIZED_EXPRESSION }
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
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.FunctionExpression
    init { kindId = NodeKind.FUNCTION_EXPRESSION }
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
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ArrowFunction
    init { kindId = NodeKind.ARROW_FUNCTION }
}

data class DeleteExpression(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.DeleteExpression
    init { kindId = NodeKind.DELETE_EXPRESSION }
}

data class TypeOfExpression(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.TypeOfExpression
    init { kindId = NodeKind.TYPE_OF_EXPRESSION }
}

data class VoidExpression(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.VoidExpression
    init { kindId = NodeKind.VOID_EXPRESSION }
}

data class AwaitExpression(
    val expression: Expression,
    val inAsyncContext: Boolean = true,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.AwaitExpression
    init { kindId = NodeKind.AWAIT_EXPRESSION }
}

data class PrefixUnaryExpression(
    val operator: SyntaxKind,
    val operand: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.PrefixUnaryExpression
    init { kindId = NodeKind.PREFIX_UNARY_EXPRESSION }
}

data class PostfixUnaryExpression(
    val operand: Expression,
    val operator: SyntaxKind,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.PostfixUnaryExpression
    init { kindId = NodeKind.POSTFIX_UNARY_EXPRESSION }
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
    /** B346 (synthetic only, comma operator): emit each comma-chain operand on its own line at
     *  one extra indent level (tsc's ES-decorators `return _a = class {…}, (() => {…})(), _a;` form).
     *  Set only on the OUTERMOST comma node of the chain. */
    val multiLineComma: Boolean = false,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.BinaryExpression
    init { kindId = NodeKind.BINARY_EXPRESSION }
}

data class ConditionalExpression(
    val condition: Expression,
    val whenTrue: Expression,
    val whenFalse: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ConditionalExpression
    init { kindId = NodeKind.CONDITIONAL_EXPRESSION }
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
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.YieldExpression
    init { kindId = NodeKind.YIELD_EXPRESSION }
}

data class SpreadElement(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.SpreadElement
    init { kindId = NodeKind.SPREAD_ELEMENT }
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
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ClassExpression
    init { kindId = NodeKind.CLASS_EXPRESSION }
}

data class AsExpression(
    val expression: Expression,
    val type: TypeNode,
    /** Tight position right after the type's last character (no trivia overshoot).
     *  Defaults to 0 — callers that need a precise end use [tightEnd] when set. */
    val tightEnd: Int = 0,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.AsExpression
    init { kindId = NodeKind.AS_EXPRESSION }
}

data class NonNullExpression(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.NonNullExpression
    init { kindId = NodeKind.NON_NULL_EXPRESSION }
}

data class SatisfiesExpression(
    val expression: Expression,
    val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.SatisfiesExpression
    init { kindId = NodeKind.SATISFIES_EXPRESSION }
}

data class MetaProperty(
    val keywordToken: SyntaxKind,
    val name: Identifier,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.MetaProperty
    init { kindId = NodeKind.META_PROPERTY }
}

data class OmittedExpression(
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.OmittedExpression
    init { kindId = NodeKind.OMITTED_EXPRESSION }
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
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.Unknown
    init { kindId = NodeKind.COMMA_LIST_EXPRESSION }
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
    /** True when [type] was synthesized from a leading JSDoc `@type {...}` comment in a JS file. */
    val typeFromJSDoc: Boolean = false,
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.PropertyDeclaration
    init { kindId = NodeKind.PROPERTY_DECLARATION }
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
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.MethodDeclaration
    init { kindId = NodeKind.METHOD_DECLARATION }
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
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.ConstructorDeclaration
    init { kindId = NodeKind.CONSTRUCTOR }
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
    /** True end of a bodyless accessor (right after `)` / return type / `;`) — tsc's accessor.end for checkGrammarAccessor's TS1005 at end-1. -1 when a body is present. */
    val bodylessEnd: Int = -1,
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.GetAccessor
    init { kindId = NodeKind.GET_ACCESSOR }
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
    /** True end of a bodyless accessor (right after `)` / return type / `;`) — tsc's accessor.end for checkGrammarAccessor's TS1005 at end-1. -1 when a body is present. */
    val bodylessEnd: Int = -1,
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.SetAccessor
    init { kindId = NodeKind.SET_ACCESSOR }
}

data class IndexSignature(
    val parameters: List<Parameter>,
    val type: TypeNode? = null,
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.IndexSignature
    init { kindId = NodeKind.INDEX_SIGNATURE }
}

data class SemicolonClassElement(
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.SemicolonClassElement
    init { kindId = NodeKind.SEMICOLON_CLASS_ELEMENT }
}

data class ClassStaticBlockDeclaration(
    val body: Block,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), ClassElement {
    override val kind: SyntaxKind = SyntaxKind.ClassStaticBlockDeclaration
    init { kindId = NodeKind.CLASS_STATIC_BLOCK_DECLARATION }
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
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TypeReference
    init { kindId = NodeKind.TYPE_REFERENCE }
}

data class FunctionType(
    val typeParameters: List<TypeParameter>? = null,
    val parameters: List<Parameter>,
    val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.FunctionType
    init { kindId = NodeKind.FUNCTION_TYPE }
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
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ConstructorType
    init { kindId = NodeKind.CONSTRUCTOR_TYPE }
}

data class TypeQuery(
    val exprName: Node,
    val typeArguments: List<TypeNode>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TypeQuery
    init { kindId = NodeKind.TYPE_QUERY }
}

data class TypeLiteral(
    val members: List<ClassElement>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TypeLiteral
    init { kindId = NodeKind.TYPE_LITERAL }
}

data class ArrayType(
    val elementType: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ArrayType
    init { kindId = NodeKind.ARRAY_TYPE }
}

data class TupleType(
    val elements: List<TypeNode>,
    /** Per-element optionality (`[a?, b?]` / `[a?: T, b?: T]`). The parser discards the
     *  `?` tokens (and any labels) from `elements`, so it records optionality here instead;
     *  `getTupleType` consults it so an all-optional tuple target does not count its
     *  elements as required (TS2739). Same length as [elements]; null = all-required. */
    val elementOptional: List<Boolean>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TupleType
    init { kindId = NodeKind.TUPLE_TYPE }
}

data class UnionType(
    val types: List<TypeNode>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.UnionType
    init { kindId = NodeKind.UNION_TYPE }
}

data class IntersectionType(
    val types: List<TypeNode>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.IntersectionType
    init { kindId = NodeKind.INTERSECTION_TYPE }
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
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ConditionalType
    init { kindId = NodeKind.CONDITIONAL_TYPE }
}

data class IndexedAccessType(
    val objectType: TypeNode,
    val indexType: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.IndexedAccessType
    init { kindId = NodeKind.INDEXED_ACCESS_TYPE }
}

data class MappedType(
    val typeParameter: TypeParameter,
    val nameType: TypeNode? = null,
    val type: TypeNode? = null,
    val questionToken: Boolean = false,
    val readonlyToken: Boolean = false,
    /** M1.10: true for the `-readonly` modifier form (`{ -readonly [K in keyof T]: … }`,
     *  the `Mutable<T>` idiom) — the mapped type STRIPS readonly from the source
     *  members. [readonlyToken] is also true in that case (the keyword is present). */
    val readonlyMinus: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.MappedType
    init { kindId = NodeKind.MAPPED_TYPE }
}

data class LiteralType(
    val literal: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.LiteralType
    init { kindId = NodeKind.LITERAL_TYPE }
}

data class TemplateLiteralType(
    val head: StringLiteralNode,
    val templateSpans: List<TemplateLiteralTypeSpan>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TemplateLiteralType
    init { kindId = NodeKind.TEMPLATE_LITERAL_TYPE }
}

data class TemplateLiteralTypeSpan(
    val type: TypeNode,
    val literal: Node,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TemplateLiteralTypeSpan
    init { kindId = NodeKind.TEMPLATE_LITERAL_TYPE_SPAN }
}

data class ParenthesizedType(
    val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ParenthesizedType
    init { kindId = NodeKind.PARENTHESIZED_TYPE }
}

data class TypePredicate(
    val parameterName: Node,
    val type: TypeNode? = null,
    val assertsModifier: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TypePredicate
    init { kindId = NodeKind.TYPE_PREDICATE }
}

data class TypeOperator(
    val operator: SyntaxKind,
    val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.TypeOperator
    init { kindId = NodeKind.TYPE_OPERATOR }
}

data class RestType(
    val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.RestType
    init { kindId = NodeKind.REST_TYPE }
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
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.NamedTupleMember
    init { kindId = NodeKind.NAMED_TUPLE_MEMBER }
}

data class OptionalType(
    val type: TypeNode,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.OptionalType
    init { kindId = NodeKind.OPTIONAL_TYPE }
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
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ImportType
    init { kindId = NodeKind.IMPORT_TYPE }
}

data class ThisType(
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.ThisType
    init { kindId = NodeKind.THIS_TYPE }
}

data class InferType(
    val typeParameter: TypeParameter,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    override val kind: SyntaxKind = SyntaxKind.InferType
    init { kindId = NodeKind.INFER_TYPE }
}

data class KeywordTypeNode(
    override val kind: SyntaxKind,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), TypeNode {
    init { kindId = NodeKind.KEYWORD_TYPE_NODE }
}

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
    /** 17.140: when true, [type] was synthesized from a JSDoc `@param {T} name` tag
     *  (sub-Parser positions point into the JSDoc text rather than the original source).
     *  Walkers that emit position-bearing diagnostics on the type node should skip when set. */
    val typeFromJSDoc: Boolean = false,
    /** B18.2: when true, this parameter was created via B17.7's comma-recovery path
     *  (the parser emitted TS1005 `,` expected and continued the loop instead of
     *  bailing). Walkers (TS1014 rest-must-be-last; TS1213 strict-reserved on names)
     *  use this to suppress secondary diagnostics on this parameter and on the rest
     *  parameter immediately preceding it — matches TypeScript's behavior of only
     *  emitting the parser-recovery TS1005 for these malformed shapes. */
    val commaRecovered: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.Parameter
    init { kindId = NodeKind.PARAMETER }
}

data class Decorator(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.Decorator
    init { kindId = NodeKind.DECORATOR }
}

data class HeritageClause(
    val token: SyntaxKind,
    val types: List<ExpressionWithTypeArguments>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.HeritageClause
    init { kindId = NodeKind.HERITAGE_CLAUSE }
}

data class ExpressionWithTypeArguments(
    val expression: Expression,
    val typeArguments: List<TypeNode>? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.ExpressionWithTypeArguments
    init { kindId = NodeKind.EXPRESSION_WITH_TYPE_ARGUMENTS }
}

data class EnumMember(
    val name: NameNode,
    val initializer: Expression? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.EnumMember
    init { kindId = NodeKind.ENUM_MEMBER }
}

data class TypeParameter(
    val name: Identifier,
    val constraint: TypeNode? = null,
    val default: TypeNode? = null,
    val modifiers: Set<ModifierFlag> = emptySet(),
    /** Synthesized from a `/** @template T */` JSDoc tag (B5.3) — TS8004
     *  should be skipped for these. */
    val fromJSDoc: Boolean = false,
    /** Absolute source position of the `@template` keyword's `@`. -1 when not from JSDoc. */
    val jsDocTagPos: Int = -1,
    /** Absolute source position of the `@template` tag end (next tag or comment-close). -1 when not from JSDoc. */
    val jsDocTagEnd: Int = -1,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.TypeParameter
    init { kindId = NodeKind.TYPE_PARAMETER }

    /**
     * M1.13: a per-FILE discriminator (the parse's `fileName.hashCode()`, stamped by the
     * parser) used ONLY as part of `Checker.typeParamInternCache`'s key. The intern cache
     * was keyed by the absolute AST `pos` alone, which COLLIDES across files in a
     * multi-file program (each file's positions start at 0) — so two unrelated type
     * parameters in different files shared ONE `Type.TypeParam` instance and stomped its
     * mutable `.constraint`/`.default` (a spurious TS2344 in tsc's own sources). Combining
     * the salt with `pos` in the key eliminates the cross-file collision. Body property
     * (NOT a constructor param) so it stays out of data-class `equals`/`hashCode`/`copy`;
     * a single-file compile stamps every param with the SAME salt, so the key reduces to a
     * bijection with `pos` and interning behaves exactly as before (corpus byte-identical).
     */
    var internSalt: Int = 0
}

data class QualifiedName(
    val left: Node,
    val right: Identifier,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.QualifiedName
    init { kindId = NodeKind.QUALIFIED_NAME }
}

data class PropertyAssignment(
    val name: NameNode,
    val initializer: Expression,
    /** Illegal modifiers the parser recovered before the property name (e.g.
     *  `public foo: 1` in an object literal). Always empty for valid syntax;
     *  the checker emits TS1042 for each. */
    val modifiers: Set<ModifierFlag> = emptySet(),
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.PropertyAssignment
    init { kindId = NodeKind.PROPERTY_ASSIGNMENT }
}

data class ShorthandPropertyAssignment(
    val name: Identifier,
    val objectAssignmentInitializer: Expression? = null,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.ShorthandPropertyAssignment
    init { kindId = NodeKind.SHORTHAND_PROPERTY_ASSIGNMENT }
}

data class SpreadAssignment(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.SpreadAssignment
    init { kindId = NodeKind.SPREAD_ASSIGNMENT }
}

data class ComputedPropertyName(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ComputedPropertyName
    init { kindId = NodeKind.COMPUTED_PROPERTY_NAME }
}

data class ObjectBindingPattern(
    val elements: List<BindingElement>,
    val hasTrailingComma: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ObjectBindingPattern
    init { kindId = NodeKind.OBJECT_BINDING_PATTERN }
}

data class ArrayBindingPattern(
    val elements: List<Node>,
    val hasTrailingComma: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.ArrayBindingPattern
    init { kindId = NodeKind.ARRAY_BINDING_PATTERN }
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
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.BindingElement
    init { kindId = NodeKind.BINDING_ELEMENT }
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
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.CaseClause
    init { kindId = NodeKind.CASE_CLAUSE }
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
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.DefaultClause
    init { kindId = NodeKind.DEFAULT_CLAUSE }
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
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.CatchClause
    init { kindId = NodeKind.CATCH_CLAUSE }
}

data class ModuleBlock(
    val statements: List<Statement>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.ModuleBlock
    init { kindId = NodeKind.MODULE_BLOCK }
}

data class NamespaceImport(
    val name: Identifier,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.NamespaceImport
    init { kindId = NodeKind.NAMESPACE_IMPORT }
}

data class NamedImports(
    val elements: List<ImportSpecifier>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.NamedImports
    init { kindId = NodeKind.NAMED_IMPORTS }
}

data class ImportSpecifier(
    val propertyName: Identifier? = null,
    val name: Identifier,
    val isTypeOnly: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.ImportSpecifier
    init { kindId = NodeKind.IMPORT_SPECIFIER }
}

data class NamespaceExport(
    val name: Identifier,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.NamespaceExport
    init { kindId = NodeKind.NAMESPACE_EXPORT }
}

data class NamedExports(
    val elements: List<ExportSpecifier>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.NamedExports
    init { kindId = NodeKind.NAMED_EXPORTS }
}

data class ExportSpecifier(
    val propertyName: Identifier? = null,
    val name: Identifier,
    val isTypeOnly: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.ExportSpecifier
    init { kindId = NodeKind.EXPORT_SPECIFIER }
}

data class ImportClause(
    val name: Identifier? = null,
    val namedBindings: Node? = null,
    val isTypeOnly: Boolean = false,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.ImportClause
    init { kindId = NodeKind.IMPORT_CLAUSE }
}

data class ExternalModuleReference(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.ExternalModuleReference
    init { kindId = NodeKind.EXTERNAL_MODULE_REFERENCE }
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
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.JsxAttribute
    init { kindId = NodeKind.JSX_ATTRIBUTE }
}

data class JsxSpreadAttribute(
    val expression: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.JsxSpreadAttribute
    init { kindId = NodeKind.JSX_SPREAD_ATTRIBUTE }
}

data class JsxOpeningElement(
    val tagName: Expression,
    val attributes: List<Node>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.JsxOpeningElement
    init { kindId = NodeKind.JSX_OPENING_ELEMENT }
}

data class JsxClosingElement(
    val tagName: Expression,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.JsxClosingElement
    init { kindId = NodeKind.JSX_CLOSING_ELEMENT }
}

data class JsxElement(
    val openingElement: JsxOpeningElement,
    val children: List<Node>,
    val closingElement: JsxClosingElement,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.JsxElement
    init { kindId = NodeKind.JSX_ELEMENT }
}

data class JsxSelfClosingElement(
    val tagName: Expression,
    val attributes: List<Node>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.JsxSelfClosingElement
    init { kindId = NodeKind.JSX_SELF_CLOSING_ELEMENT }
}

data class JsxText(
    val text: String,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.JsxText
    init { kindId = NodeKind.JSX_TEXT }
}

data class JsxExpressionContainer(
    val expression: Expression?,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Node {
    override val kind: SyntaxKind = SyntaxKind.JsxExpression
    init { kindId = NodeKind.JSX_EXPRESSION_CONTAINER }
}

data class JsxFragment(
    val children: List<Node>,
    override val pos: Int = 0,
    override val end: Int = 0,
    override val leadingComments: List<Comment>? = null,
    override val trailingComments: List<Comment>? = null,
) : NodeBase(), Expression {
    override val kind: SyntaxKind = SyntaxKind.JsxFragment
    init { kindId = NodeKind.JSX_FRAGMENT }
}
