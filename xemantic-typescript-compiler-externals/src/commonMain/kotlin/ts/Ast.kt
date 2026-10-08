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

package com.xemantic.typescript.compiler.externals.ts

import com.xemantic.typescript.tsgo.ast.Node as RawNode

/*
 * (TSGO.4-b) THE DECLARATION-SYNTAX VIEW the externals generator walks.
 *
 * The generator reads ONE engine — tsgo, ported (`xemantic-typescript-compiler-tsgo`)
 * — but walks a typed view of that engine's AST rather than `ast.Node` itself:
 * tsgo's tree is one `Node` class carrying a `Kind` and a per-kind data
 * record, and the generator's ~5,000 lines of syntax rules read naturally only
 * over a class per kind. Every view node WRAPS the tsgo node it was built from
 * ([NodeBase.raw]) and is created at most once per tsgo node ([Tree.node]), so
 * the two identities agree: a declaration a checker answer names (a symbol's
 * `declarations`) is `===` the declaration the generator's scan collected.
 * Children are built lazily, which is what lets a symbol declared in a
 * 40,000-line lib file be asked for its file name without wrapping the file.
 *
 * The view keeps the SHAPE the generator was written against, including four
 * spellings that are not tsgo's and are normalised here, once:
 *
 *  - an interface's call signature is a [MethodDeclaration] named `""` and its
 *    construct signature one named `new`; a property signature and a method
 *    signature are a [PropertyDeclaration] and a [MethodDeclaration];
 *  - a dotted namespace `namespace A.B.C { }` — nested `ModuleDeclaration`s in
 *    tsgo — is ONE [ModuleDeclaration] whose name is the dotted
 *    [PropertyAccessExpression] and whose body is the innermost block; every
 *    tsgo declaration of the chain maps to that one view node;
 *  - a `null` type is a [KeywordTypeNode], and a `true`/`false` literal type's
 *    literal an [Identifier];
 *  - a `#private` name is an [Identifier] whose text starts with `#`.
 *
 * Text crosses from Go's UTF-8 byte strings to UTF-16 here, once per node.
 */

/** Every view node. */
internal sealed interface Node

/** A statement position. */
internal sealed interface Statement : Node

/** An expression position. */
internal sealed interface Expression : Node

/** A declaration statement. */
internal sealed interface Declaration : Statement

/** A type position. */
internal sealed interface TypeNode : Node

/** A class or interface member. */
internal sealed interface ClassElement : Node

/** The modifiers a declaration carries, tsgo's `ModifierFlags` as a set. */
internal enum class ModifierFlag {
    Export, Default, Declare, Abstract, Public, Private, Protected,
    Static, Readonly, Override, Async, Const, In, Out, Accessor,
}

/** The keyword and operator kinds the generator distinguishes; [Other] is every other kind. */
internal enum class SyntaxKind {
    AnyKeyword, UnknownKeyword, NullKeyword, UndefinedKeyword, StringKeyword, NumberKeyword,
    BooleanKeyword, BigIntKeyword, SymbolKeyword, ObjectKeyword, NeverKeyword, VoidKeyword,
    IntrinsicKeyword, ThisType,
    ExtendsKeyword, ImplementsKeyword,
    ReadonlyKeyword, KeyOfKeyword, UniqueKeyword,
    VarKeyword, LetKeyword, ConstKeyword, UsingKeyword, AwaitUsingKeyword,
    MinusToken, PlusToken, TildeToken, ExclamationToken,
    Other,
}

/**
 * The identity and navigation half every view node shares. [raw] is null for
 * a node the view SYNTHESISES (a dotted namespace's name, a `true` literal's
 * identifier); such a node has the parent it was given.
 */
internal abstract class NodeBase(internal val tree: Tree, internal val raw: RawNode?) {

    private var synthesizedParent: Node? = null

    /** The parent node, or null for a source file (and for a synthesised node built without one). */
    val parent: Node?
        get() = if (raw == null) synthesizedParent else raw.parent?.let { tree.node(it) }

    internal fun synthesizedUnder(parent: Node?): NodeBase {
        synthesizedParent = parent
        return this
    }

    /** The node's text range in the file's UTF-8 bytes (tsgo positions), -1 for a synthesised node. */
    val pos: Int get() = raw?.pos() ?: -1
}

// --- files and statements ----------------------------------------------------

internal class SourceFile(tree: Tree, raw: RawNode, val fileName: String) : NodeBase(tree, raw), Node {
    val text: String by lazy(LazyThreadSafetyMode.NONE) { tree.sourceText(raw) }
    val statements: List<Statement> by lazy(LazyThreadSafetyMode.NONE) { tree.statements(raw) }
}

internal class VariableStatement(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Statement {
    val declarationList: VariableDeclarationList by lazy(LazyThreadSafetyMode.NONE) {
        tree.node(tree.variableDeclarationListOf(raw)) as VariableDeclarationList
    }
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class VariableDeclarationList(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    val declarations: List<VariableDeclaration> by lazy(LazyThreadSafetyMode.NONE) { tree.declarationsOfList(raw) }
    /** `var` / `let` / `const` / `using`, as the keyword kind. */
    val flags: SyntaxKind by lazy(LazyThreadSafetyMode.NONE) { tree.declarationListKind(raw) }
}

internal class VariableDeclaration(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Declaration {
    val name: Expression by lazy(LazyThreadSafetyMode.NONE) { tree.expression(tree.nameOf(raw)) }
    val type: TypeNode? by lazy(LazyThreadSafetyMode.NONE) { tree.typeNodeOrNull(tree.typeOf(raw)) }
    val initializer: Expression? by lazy(LazyThreadSafetyMode.NONE) { tree.expressionOrNull(tree.initializerOf(raw)) }
}

internal class FunctionDeclaration(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Declaration {
    val name: Identifier? by lazy(LazyThreadSafetyMode.NONE) { tree.identifierOrNull(tree.nameOf(raw)) }
    val typeParameters: List<TypeParameter>? by lazy(LazyThreadSafetyMode.NONE) { tree.typeParameters(raw) }
    val parameters: List<Parameter> by lazy(LazyThreadSafetyMode.NONE) { tree.parameters(raw) }
    val type: TypeNode? by lazy(LazyThreadSafetyMode.NONE) { tree.typeNodeOrNull(tree.typeOf(raw)) }
    val body: Node? by lazy(LazyThreadSafetyMode.NONE) { tree.bodyOf(raw)?.let { tree.node(it) } }
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class ClassDeclaration(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Declaration {
    val name: Identifier? by lazy(LazyThreadSafetyMode.NONE) { tree.identifierOrNull(tree.nameOf(raw)) }
    val typeParameters: List<TypeParameter>? by lazy(LazyThreadSafetyMode.NONE) { tree.typeParameters(raw) }
    val heritageClauses: List<HeritageClause>? by lazy(LazyThreadSafetyMode.NONE) { tree.heritageClauses(raw) }
    val members: List<ClassElement> by lazy(LazyThreadSafetyMode.NONE) { tree.members(raw) }
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class InterfaceDeclaration(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Declaration {
    val name: Identifier by lazy(LazyThreadSafetyMode.NONE) { tree.identifier(tree.nameOf(raw)) }
    val typeParameters: List<TypeParameter>? by lazy(LazyThreadSafetyMode.NONE) { tree.typeParameters(raw) }
    val heritageClauses: List<HeritageClause>? by lazy(LazyThreadSafetyMode.NONE) { tree.heritageClauses(raw) }
    val members: List<ClassElement> by lazy(LazyThreadSafetyMode.NONE) { tree.members(raw) }
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class TypeAliasDeclaration(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Declaration {
    val name: Identifier by lazy(LazyThreadSafetyMode.NONE) { tree.identifier(tree.nameOf(raw)) }
    val typeParameters: List<TypeParameter>? by lazy(LazyThreadSafetyMode.NONE) { tree.typeParameters(raw) }
    val type: TypeNode by lazy(LazyThreadSafetyMode.NONE) { tree.typeNode(tree.typeOf(raw)) }
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class EnumDeclaration(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Declaration {
    val name: Identifier by lazy(LazyThreadSafetyMode.NONE) { tree.identifier(tree.nameOf(raw)) }
    val members: List<EnumMember> by lazy(LazyThreadSafetyMode.NONE) { tree.enumMembers(raw) }
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class EnumMember(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    val name: Expression by lazy(LazyThreadSafetyMode.NONE) { tree.expression(tree.nameOf(raw)) }
    val initializer: Expression? by lazy(LazyThreadSafetyMode.NONE) { tree.expressionOrNull(tree.initializerOf(raw)) }
}

/**
 * A namespace or ambient module. A dotted `namespace A.B { }` is ONE node: [name]
 * is the dotted [PropertyAccessExpression] and [body] the innermost block.
 */
internal class ModuleDeclaration(
    tree: Tree,
    raw: RawNode,
    /** The innermost declaration of a dotted chain (`raw` itself for an undotted one). */
    private val innermost: RawNode,
) : NodeBase(tree, raw), Declaration {
    val name: Expression by lazy(LazyThreadSafetyMode.NONE) { tree.moduleName(this, raw, innermost) }
    val body: Node? by lazy(LazyThreadSafetyMode.NONE) { tree.bodyOf(innermost)?.let { tree.node(it) } }
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class ModuleBlock(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    val statements: List<Statement> by lazy(LazyThreadSafetyMode.NONE) { tree.statements(raw) }
}

internal class ImportDeclaration(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Declaration {
    val importClause: ImportClause? by lazy(LazyThreadSafetyMode.NONE) { tree.importClauseOf(raw)?.let { tree.node(it) as? ImportClause } }
    val moduleSpecifier: Expression by lazy(LazyThreadSafetyMode.NONE) { tree.expression(tree.moduleSpecifierOf(raw)) }
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class ImportClause(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    val name: Identifier? by lazy(LazyThreadSafetyMode.NONE) { tree.identifierOrNull(tree.nameOf(raw)) }
    val namedBindings: Node? by lazy(LazyThreadSafetyMode.NONE) { tree.namedBindingsOf(raw)?.let { tree.node(it) } }
    val isTypeOnly: Boolean get() = tree.isTypeOnly(raw!!)
}

internal class NamespaceImport(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    val name: Identifier by lazy(LazyThreadSafetyMode.NONE) { tree.identifier(tree.nameOf(raw)) }
}

internal class NamedImports(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    val elements: List<ImportSpecifier> by lazy(LazyThreadSafetyMode.NONE) { tree.elementsOf(raw).map { it as ImportSpecifier } }
}

internal class ImportSpecifier(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    val propertyName: Identifier? by lazy(LazyThreadSafetyMode.NONE) { tree.identifierOrNull(tree.propertyNameOf(raw)) }
    val name: Identifier by lazy(LazyThreadSafetyMode.NONE) { tree.identifier(tree.nameOf(raw)) }
    val isTypeOnly: Boolean get() = tree.isTypeOnly(raw!!)
}

internal class ImportEqualsDeclaration(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Declaration {
    val name: Identifier by lazy(LazyThreadSafetyMode.NONE) { tree.identifier(tree.nameOf(raw)) }
    /** An [Identifier], a [QualifiedName] or an [ExternalModuleReference]. */
    val moduleReference: Node by lazy(LazyThreadSafetyMode.NONE) { tree.node(tree.moduleReferenceOf(raw)) }
    val isTypeOnly: Boolean get() = tree.isTypeOnly(raw!!)
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class ExternalModuleReference(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    val expression: Expression by lazy(LazyThreadSafetyMode.NONE) { tree.expression(tree.expressionOf(raw)) }
}

internal class ExportDeclaration(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Declaration {
    /** A [NamedExports], a [NamespaceExport], or null for `export *`. */
    val exportClause: Node? by lazy(LazyThreadSafetyMode.NONE) { tree.exportClauseOf(raw)?.let { tree.node(it) } }
    val moduleSpecifier: Expression? by lazy(LazyThreadSafetyMode.NONE) { tree.expressionOrNull(tree.moduleSpecifierOf(raw)) }
    val isTypeOnly: Boolean get() = tree.isTypeOnly(raw!!)
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class ExportAssignment(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Declaration {
    val expression: Expression by lazy(LazyThreadSafetyMode.NONE) { tree.expression(tree.expressionOf(raw)) }
    val isExportEquals: Boolean get() = tree.isExportEquals(raw!!)
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class NamedExports(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    val elements: List<ExportSpecifier> by lazy(LazyThreadSafetyMode.NONE) { tree.elementsOf(raw).map { it as ExportSpecifier } }
}

internal class NamespaceExport(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    val name: Identifier by lazy(LazyThreadSafetyMode.NONE) { tree.identifier(tree.nameOf(raw)) }
}

internal class ExportSpecifier(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    val propertyName: Identifier? by lazy(LazyThreadSafetyMode.NONE) { tree.identifierOrNull(tree.propertyNameOf(raw)) }
    val name: Identifier by lazy(LazyThreadSafetyMode.NONE) { tree.identifier(tree.nameOf(raw)) }
    val isTypeOnly: Boolean get() = tree.isTypeOnly(raw!!)
}

// --- expressions -------------------------------------------------------------

/** An identifier — or (synthesised) a `#private` name, or a `true`/`false` literal of a literal type. */
internal class Identifier(tree: Tree, raw: RawNode?, val text: String) : NodeBase(tree, raw), Expression

internal class StringLiteralNode(tree: Tree, raw: RawNode, val text: String) : NodeBase(tree, raw), Expression

internal class NumericLiteralNode(tree: Tree, raw: RawNode, val text: String) : NodeBase(tree, raw), Expression

internal class BigIntLiteralNode(tree: Tree, raw: RawNode, val text: String) : NodeBase(tree, raw), Expression

internal class PrefixUnaryExpression(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Expression {
    val operator: SyntaxKind by lazy(LazyThreadSafetyMode.NONE) { tree.prefixOperator(raw) }
    val operand: Expression by lazy(LazyThreadSafetyMode.NONE) { tree.expression(tree.prefixOperand(raw)) }
}

/** `a.b` — or (synthesised, [raw] null) one link of a dotted namespace name. */
internal class PropertyAccessExpression(
    tree: Tree,
    raw: RawNode?,
    expressionBuilder: () -> Expression,
    nameBuilder: () -> Identifier,
) : NodeBase(tree, raw), Expression {
    val expression: Expression by lazy(LazyThreadSafetyMode.NONE, expressionBuilder)
    val name: Identifier by lazy(LazyThreadSafetyMode.NONE, nameBuilder)
}

internal class ClassExpression(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Expression

// --- class and interface members ---------------------------------------------

internal class PropertyDeclaration(tree: Tree, raw: RawNode) : NodeBase(tree, raw), ClassElement {
    val name: Expression by lazy(LazyThreadSafetyMode.NONE) { tree.expression(tree.nameOf(raw)) }
    val type: TypeNode? by lazy(LazyThreadSafetyMode.NONE) { tree.typeNodeOrNull(tree.typeOf(raw)) }
    val questionToken: Boolean get() = tree.hasQuestionToken(raw!!)
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

/**
 * A method — and, inside an interface, a method signature, a call signature
 * (named `""`) and a construct signature (named `new`).
 */
internal class MethodDeclaration(tree: Tree, raw: RawNode, syntheticName: String?) : NodeBase(tree, raw), ClassElement {
    val name: Expression by lazy(LazyThreadSafetyMode.NONE) {
        if (syntheticName != null) Identifier(tree, null, syntheticName).synthesizedUnder(this) as Identifier
        else tree.expression(tree.nameOf(raw))
    }
    val typeParameters: List<TypeParameter>? by lazy(LazyThreadSafetyMode.NONE) { tree.typeParameters(raw) }
    val parameters: List<Parameter> by lazy(LazyThreadSafetyMode.NONE) { tree.parameters(raw) }
    val type: TypeNode? by lazy(LazyThreadSafetyMode.NONE) { tree.typeNodeOrNull(tree.typeOf(raw)) }
    val questionToken: Boolean get() = tree.hasQuestionToken(raw!!)
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class Constructor(tree: Tree, raw: RawNode) : NodeBase(tree, raw), ClassElement {
    val parameters: List<Parameter> by lazy(LazyThreadSafetyMode.NONE) { tree.parameters(raw) }
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class GetAccessor(tree: Tree, raw: RawNode) : NodeBase(tree, raw), ClassElement {
    val name: Expression by lazy(LazyThreadSafetyMode.NONE) { tree.expression(tree.nameOf(raw)) }
    val parameters: List<Parameter> by lazy(LazyThreadSafetyMode.NONE) { tree.parameters(raw) }
    val type: TypeNode? by lazy(LazyThreadSafetyMode.NONE) { tree.typeNodeOrNull(tree.typeOf(raw)) }
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class SetAccessor(tree: Tree, raw: RawNode) : NodeBase(tree, raw), ClassElement {
    val name: Expression by lazy(LazyThreadSafetyMode.NONE) { tree.expression(tree.nameOf(raw)) }
    val parameters: List<Parameter> by lazy(LazyThreadSafetyMode.NONE) { tree.parameters(raw) }
    val type: TypeNode? by lazy(LazyThreadSafetyMode.NONE) { tree.typeNodeOrNull(tree.typeOf(raw)) }
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class IndexSignature(tree: Tree, raw: RawNode) : NodeBase(tree, raw), ClassElement {
    val parameters: List<Parameter> by lazy(LazyThreadSafetyMode.NONE) { tree.parameters(raw) }
    val type: TypeNode? by lazy(LazyThreadSafetyMode.NONE) { tree.typeNodeOrNull(tree.typeOf(raw)) }
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }
}

internal class SemicolonClassElement(tree: Tree, raw: RawNode) : NodeBase(tree, raw), ClassElement

internal class ClassStaticBlockDeclaration(tree: Tree, raw: RawNode) : NodeBase(tree, raw), ClassElement

// --- supporting nodes ----------------------------------------------------------

internal class Parameter(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    val name: Expression by lazy(LazyThreadSafetyMode.NONE) { tree.expression(tree.nameOf(raw)) }
    val type: TypeNode? by lazy(LazyThreadSafetyMode.NONE) { tree.typeNodeOrNull(tree.typeOf(raw)) }
    val initializer: Expression? by lazy(LazyThreadSafetyMode.NONE) { tree.expressionOrNull(tree.initializerOf(raw)) }
    val dotDotDotToken: Boolean get() = tree.hasDotDotDotToken(raw!!)
    val questionToken: Boolean get() = tree.hasQuestionToken(raw!!)
    val modifiers: Set<ModifierFlag> by lazy(LazyThreadSafetyMode.NONE) { tree.modifiers(raw) }

    /** tsgo's parser keeps no comment-only placeholder parameter; kept for the generator's filters. */
    val isCommentPlaceholder: Boolean get() = false
}

internal class HeritageClause(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    /** [SyntaxKind.ExtendsKeyword] or [SyntaxKind.ImplementsKeyword]. */
    val token: SyntaxKind by lazy(LazyThreadSafetyMode.NONE) { tree.heritageToken(raw) }
    val types: List<ExpressionWithTypeArguments> by lazy(LazyThreadSafetyMode.NONE) { tree.heritageTypes(raw) }
}

internal class ExpressionWithTypeArguments(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    val expression: Expression by lazy(LazyThreadSafetyMode.NONE) { tree.expression(tree.expressionOf(raw)) }
    val typeArguments: List<TypeNode>? by lazy(LazyThreadSafetyMode.NONE) { tree.typeArguments(raw) }
}

internal class TypeParameter(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    val name: Identifier by lazy(LazyThreadSafetyMode.NONE) { tree.identifier(tree.nameOf(raw)) }
    val constraint: TypeNode? by lazy(LazyThreadSafetyMode.NONE) { tree.typeNodeOrNull(tree.constraintOf(raw)) }
    val default: TypeNode? by lazy(LazyThreadSafetyMode.NONE) { tree.typeNodeOrNull(tree.defaultOf(raw)) }
}

internal class QualifiedName(tree: Tree, raw: RawNode) : NodeBase(tree, raw), Node {
    /** An [Identifier] or a [QualifiedName]. */
    val left: Node by lazy(LazyThreadSafetyMode.NONE) { tree.node(tree.qualifiedLeft(raw)) }
    val right: Identifier by lazy(LazyThreadSafetyMode.NONE) { tree.identifier(tree.qualifiedRight(raw)) }
}

/**
 * A node of a kind the generator does not distinguish — a block, a call, a
 * binding pattern, a computed name, a tuple or mapped type, `export as
 * namespace X`, … — in whatever position it occurs; [kindName] is tsgo's kind
 * name, for a marker that has to say what it skipped.
 */
internal class OtherNode(tree: Tree, raw: RawNode, val kindName: String) :
    NodeBase(tree, raw), Statement, Expression, TypeNode, ClassElement

// --- type nodes ----------------------------------------------------------------

internal class TypeReference(tree: Tree, raw: RawNode) : NodeBase(tree, raw), TypeNode {
    /** An [Identifier] or a [QualifiedName]. */
    val typeName: Node by lazy(LazyThreadSafetyMode.NONE) { tree.node(tree.typeNameOf(raw)) }
    val typeArguments: List<TypeNode>? by lazy(LazyThreadSafetyMode.NONE) { tree.typeArguments(raw) }
}

internal class FunctionType(tree: Tree, raw: RawNode) : NodeBase(tree, raw), TypeNode {
    val typeParameters: List<TypeParameter>? by lazy(LazyThreadSafetyMode.NONE) { tree.typeParameters(raw) }
    val parameters: List<Parameter> by lazy(LazyThreadSafetyMode.NONE) { tree.parameters(raw) }
    val type: TypeNode by lazy(LazyThreadSafetyMode.NONE) { tree.typeNode(tree.typeOf(raw)) }
}

internal class TypeQuery(tree: Tree, raw: RawNode) : NodeBase(tree, raw), TypeNode {
    /** An [Identifier] or a [QualifiedName] (an `import(...)` query is a different node). */
    val exprName: Node by lazy(LazyThreadSafetyMode.NONE) { tree.node(tree.exprNameOf(raw)) }
}

internal class ArrayType(tree: Tree, raw: RawNode) : NodeBase(tree, raw), TypeNode {
    val elementType: TypeNode by lazy(LazyThreadSafetyMode.NONE) { tree.typeNode(tree.elementTypeOf(raw)) }
}

internal class UnionType(tree: Tree, raw: RawNode) : NodeBase(tree, raw), TypeNode {
    val types: List<TypeNode> by lazy(LazyThreadSafetyMode.NONE) { tree.unionTypes(raw) }
}

internal class LiteralType(tree: Tree, raw: RawNode) : NodeBase(tree, raw), TypeNode {
    val literal: Expression by lazy(LazyThreadSafetyMode.NONE) { tree.literalOf(this, raw) }
}

internal class ParenthesizedType(tree: Tree, raw: RawNode) : NodeBase(tree, raw), TypeNode {
    val type: TypeNode by lazy(LazyThreadSafetyMode.NONE) { tree.typeNode(tree.typeOf(raw)) }
}

internal class TypeOperator(tree: Tree, raw: RawNode) : NodeBase(tree, raw), TypeNode {
    val operator: SyntaxKind by lazy(LazyThreadSafetyMode.NONE) { tree.typeOperatorKind(raw) }
    val type: TypeNode by lazy(LazyThreadSafetyMode.NONE) { tree.typeNode(tree.typeOf(raw)) }
}

/** A keyword in type position — `string`, `any`, `undefined`, `void`, … and (normalised) `null`. */
internal class KeywordTypeNode(tree: Tree, raw: RawNode, val kind: SyntaxKind) : NodeBase(tree, raw), TypeNode
