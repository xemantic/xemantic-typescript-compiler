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

package com.xemantic.typescript.compiler.kir.front

import com.xemantic.typescript.compiler.kir.ast.*
import com.xemantic.typescript.tsgo.ast.asInterfaceDeclaration
import com.xemantic.typescript.tsgo.ast.asVariableStatement
import com.xemantic.typescript.tsgo.ast.asVariableDeclarationList
import com.xemantic.typescript.tsgo.ast.asSwitchStatement
import com.xemantic.typescript.tsgo.ast.asCaseBlock
import com.xemantic.typescript.tsgo.ast.asCatchClause
import com.xemantic.typescript.tsgo.ast.asConditionalExpression
import com.xemantic.typescript.tsgo.ast.asParameterDeclaration
import com.xemantic.typescript.tsgo.ast.asExpressionWithTypeArguments
import com.xemantic.typescript.tsgo.ast.asTypeReferenceNode
import com.xemantic.typescript.tsgo.ast.asTypeQueryNode
import com.xemantic.typescript.tsgo.ast.asArrayTypeNode
import com.xemantic.typescript.tsgo.ast.asUnionTypeNode
import com.xemantic.typescript.tsgo.ast.asIntersectionTypeNode
import com.xemantic.typescript.tsgo.ast.asLiteralTypeNode
import com.xemantic.typescript.tsgo.ast.asInferTypeNode
import com.xemantic.typescript.tsgo.ast.asJsxClosingElement
import com.xemantic.typescript.tsgo.ast.asJsxAttributes
import com.xemantic.typescript.tsgo.ast.getFunctionFlags
import com.xemantic.typescript.tsgo.ast.FunctionFlagsGenerator
import com.xemantic.typescript.tsgo.ast.arguments
import com.xemantic.typescript.tsgo.ast.asArrowFunction
import com.xemantic.typescript.tsgo.ast.asBinaryExpression
import com.xemantic.typescript.tsgo.ast.asBindingElement
import com.xemantic.typescript.tsgo.ast.asBlock
import com.xemantic.typescript.tsgo.ast.asCallExpression
import com.xemantic.typescript.tsgo.ast.asConditionalTypeNode
import com.xemantic.typescript.tsgo.ast.asElementAccessExpression
import com.xemantic.typescript.tsgo.ast.asExportAssignment
import com.xemantic.typescript.tsgo.ast.asExportDeclaration
import com.xemantic.typescript.tsgo.ast.asForInOrOfStatement
import com.xemantic.typescript.tsgo.ast.asForStatement
import com.xemantic.typescript.tsgo.ast.asHeritageClause
import com.xemantic.typescript.tsgo.ast.asIfStatement
import com.xemantic.typescript.tsgo.ast.asImportClause
import com.xemantic.typescript.tsgo.ast.asImportDeclaration
import com.xemantic.typescript.tsgo.ast.asImportEqualsDeclaration
import com.xemantic.typescript.tsgo.ast.asImportTypeNode
import com.xemantic.typescript.tsgo.ast.asIndexedAccessTypeNode
import com.xemantic.typescript.tsgo.ast.asJsxAttribute
import com.xemantic.typescript.tsgo.ast.asJsxElement
import com.xemantic.typescript.tsgo.ast.asJsxFragment
import com.xemantic.typescript.tsgo.ast.asJsxOpeningElement
import com.xemantic.typescript.tsgo.ast.asJsxSelfClosingElement
import com.xemantic.typescript.tsgo.ast.asMappedTypeNode
import com.xemantic.typescript.tsgo.ast.asMetaProperty
import com.xemantic.typescript.tsgo.ast.asNamedTupleMember
import com.xemantic.typescript.tsgo.ast.asNewExpression
import com.xemantic.typescript.tsgo.ast.asPostfixUnaryExpression
import com.xemantic.typescript.tsgo.ast.asPrefixUnaryExpression
import com.xemantic.typescript.tsgo.ast.asPropertyAccessExpression
import com.xemantic.typescript.tsgo.ast.asQualifiedName
import com.xemantic.typescript.tsgo.ast.asShorthandPropertyAssignment
import com.xemantic.typescript.tsgo.ast.asTaggedTemplateExpression
import com.xemantic.typescript.tsgo.ast.asTemplateExpression
import com.xemantic.typescript.tsgo.ast.asTemplateLiteralTypeNode
import com.xemantic.typescript.tsgo.ast.asTemplateSpan
import com.xemantic.typescript.tsgo.ast.asTemplateLiteralTypeSpan
import com.xemantic.typescript.tsgo.ast.asTryStatement
import com.xemantic.typescript.tsgo.ast.asTypeOperatorNode
import com.xemantic.typescript.tsgo.ast.asTypePredicateNode
import com.xemantic.typescript.tsgo.ast.asTypeParameterDeclaration
import com.xemantic.typescript.tsgo.ast.asVariableDeclaration
import com.xemantic.typescript.tsgo.ast.asYieldExpression
import com.xemantic.typescript.tsgo.ast.body
import com.xemantic.typescript.tsgo.ast.elements
import com.xemantic.typescript.tsgo.ast.expression
import com.xemantic.typescript.tsgo.ast.initializer
import com.xemantic.typescript.tsgo.ast.label
import com.xemantic.typescript.tsgo.ast.members
import com.xemantic.typescript.tsgo.ast.modifiers
import com.xemantic.typescript.tsgo.ast.name
import com.xemantic.typescript.tsgo.ast.parameters
import com.xemantic.typescript.tsgo.ast.properties
import com.xemantic.typescript.tsgo.ast.propertyName
import com.xemantic.typescript.tsgo.ast.questionDotToken
import com.xemantic.typescript.tsgo.ast.questionToken
import com.xemantic.typescript.tsgo.ast.statement
import com.xemantic.typescript.tsgo.ast.statements
import com.xemantic.typescript.tsgo.ast.text
import com.xemantic.typescript.tsgo.ast.type
import com.xemantic.typescript.tsgo.ast.typeArguments
import com.xemantic.typescript.tsgo.ast.typeParameterList
import com.xemantic.typescript.tsgo.ast.isTypeOnly
import com.xemantic.typescript.tsgo.ast.classLikeData
import com.xemantic.typescript.tsgo.ast.literalLikeData
import com.xemantic.typescript.tsgo.ast.NodeFlagsAwaitUsing
import com.xemantic.typescript.tsgo.ast.NodeFlagsConst
import com.xemantic.typescript.tsgo.ast.NodeFlagsLet
import com.xemantic.typescript.tsgo.ast.NodeFlagsUsing
import com.xemantic.typescript.tsgo.ast.NodeList as TsgoNodeList
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoString
import java.util.IdentityHashMap
import com.xemantic.typescript.tsgo.ast.Kind as TsgoKind
import com.xemantic.typescript.tsgo.ast.Node as TsgoNode
import com.xemantic.typescript.tsgo.ast.SourceFile as TsgoSourceFile

// (TSGO.7-e) THE SYNTAX THE LOWERING WALKS, BUILT FROM tsgo's TREE.
//
// The lowering walks the syntax classes of `kir.ast` (`-core`'s AST, moved here in (TSGO.7-f)). Until (TSGO.7-e)
// every program file was parsed a SECOND time by `-core`'s parser for that, and the two trees were paired by
// (kind, position). This builds the same classes from the tree tsgo already parsed, node for node, recording the
// pairing as it goes: one parse, and no pairing to miss. It reproduces the shapes `-core`'s parser gave the
// lowering — `this` / `super` / `true` / `false` / `null` as identifiers, a statement-level declaration starting
// AFTER its modifiers, an interface's call signature as a method named "" and its construct signature as a
// method named `new`, positions in UTF-16. Until (TSGO.7-f) a differential held it to `-core`'s parser on every
// KIR corpus program: equal but for one `-core` bug, raw template text (`KirTemplateEscapeTest`).

/** One program file's tsgo tree as the lowering's syntax, and the pairing between the two. */
internal class TsgoSyntax(private val file: TsgoSourceFile, private val fileName: String) {

    /** tsgo's tree of the file. */
    val tsgo: TsgoSourceFile get() = file

    private val text = GoString.toUtf16(file.text)
    private val offsets = Utf8Offsets(text)
    private val coreToTsgo = IdentityHashMap<Node, TsgoNode>()
    private val tsgoToCore = IdentityHashMap<TsgoNode, Node>()
    private val kindNames = HashMap<TsgoKind, String>()

    /** The converted file; parents stamped as `-core`'s parser stamps them (`indexSourceFile`). */
    val sourceFile: SourceFile = run {
        val statements = list(file.statements!!.nodes) { st(it) }
        SourceFile(fileName = fileName, statements = statements, text = text, pos = 0, end = text.length)
            .also { track(it, file.asNode()!!); indexSourceFile(it) }
    }

    /** The converted file — the syntax the lowering walks. */
    val core: SourceFile get() = sourceFile

    fun tsgoOf(node: Node): TsgoNode? = coreToTsgo[node]

    fun coreOf(node: TsgoNode): Node? = tsgoToCore[node]

    // ---- positions and text ----

    /** tsc's `getStart()`: the first token, in UTF-16. */
    private fun start(n: TsgoNode): Int =
        offsets.toUtf16(com.xemantic.typescript.tsgo.scanner.getTokenPosOfNode(n, file, false))

    /** A declaration starts AFTER its modifiers and decorators in `-core`'s tree. */
    private fun declStart(n: TsgoNode): Int {
        val modifiers = n.modifiers()?.nodeList ?: return start(n)
        if (modifiers.nodes.len == 0) return start(n)
        return offsets.toUtf16(com.xemantic.typescript.tsgo.scanner.skipTrivia(file.text, modifiers.loc.end()))
    }

    /** The first token after the leading [keyword] of [n] (`import type { … }`'s clause starts at `{`). */
    private fun afterKeyword(n: TsgoNode, keyword: String): Int {
        val at = com.xemantic.typescript.tsgo.scanner.getTokenPosOfNode(n, file, false)
        return offsets.toUtf16(com.xemantic.typescript.tsgo.scanner.skipTrivia(file.text, at + keyword.length))
    }

    private fun end(n: TsgoNode): Int = offsets.toUtf16(n.loc.end())

    private fun utf16(s: String): String = GoString.toUtf16(s)

    private fun source(n: TsgoNode): String = text.substring(start(n), end(n))

    private fun <T : Node> track(core: T, tsgo: TsgoNode): T {
        coreToTsgo[core] = tsgo
        tsgoToCore.putIfAbsent(tsgo, core)
        return core
    }

    private fun kind(n: TsgoNode): String = kindNames.getOrPut(n.kind) { utf16(n.kind.string()).removePrefix("Kind") }

    private fun <R> list(nodes: GoSlice<TsgoNode?>, convert: (TsgoNode) -> R): List<R> =
        (0 until nodes.len).mapNotNull { i -> nodes[i]?.let(convert) }

    private fun <R> optList(nodes: TsgoNodeList?, convert: (TsgoNode) -> R): List<R>? =
        nodes?.let { list(it.nodes, convert) }

    // ---- modifiers ----

    private fun modifiers(n: TsgoNode): Set<ModifierFlag> {
        val nodes = n.modifiers()?.nodeList?.nodes ?: return emptySet()
        val out = LinkedHashSet<ModifierFlag>()
        for (i in 0 until nodes.len) {
            when (kind(nodes[i] ?: continue)) {
                "ExportKeyword" -> out += ModifierFlag.Export
                "DefaultKeyword" -> out += ModifierFlag.Default
                "DeclareKeyword" -> out += ModifierFlag.Declare
                "AbstractKeyword" -> out += ModifierFlag.Abstract
                "PublicKeyword" -> out += ModifierFlag.Public
                "PrivateKeyword" -> out += ModifierFlag.Private
                "ProtectedKeyword" -> out += ModifierFlag.Protected
                "StaticKeyword" -> out += ModifierFlag.Static
                "ReadonlyKeyword" -> out += ModifierFlag.Readonly
                "OverrideKeyword" -> out += ModifierFlag.Override
                "AsyncKeyword" -> out += ModifierFlag.Async
                "ConstKeyword" -> out += ModifierFlag.Const
                "InKeyword" -> out += ModifierFlag.In
                "OutKeyword" -> out += ModifierFlag.Out
                "AccessorKeyword" -> out += ModifierFlag.Accessor
            }
        }
        return out
    }

    private fun decorators(n: TsgoNode): List<Decorator>? {
        val nodes = n.modifiers()?.nodeList?.nodes ?: return null
        val out = (0 until nodes.len).mapNotNull { i ->
            nodes[i]?.takeIf { kind(it) == "Decorator" }?.let { d ->
                track(Decorator(expression = ex(d.expression()!!), pos = start(d), end = end(d)), d)
            }
        }
        return out.ifEmpty { null }
    }

    /** `-core`'s token kind for a tsgo token kind: `PlusToken` is `Plus`, a keyword keeps its name. */
    private fun token(k: TsgoKind): SyntaxKind {
        val name = utf16(k.string()).removePrefix("Kind")
        return runCatching { SyntaxKind.valueOf(name.removeSuffix("Token")) }.getOrElse { SyntaxKind.valueOf(name) }
    }

    // ---- typed entry points ----

    private fun st(n: TsgoNode): Statement = node(n) as Statement
    private fun ex(n: TsgoNode): Expression = node(n) as Expression
    private fun ty(n: TsgoNode): TypeNode = node(n) as TypeNode
    private fun id(n: TsgoNode): Identifier = node(n) as Identifier
    private fun block(n: TsgoNode): Block = node(n) as Block
    private fun member(n: TsgoNode): ClassElement = node(n) as ClassElement
    private fun parameter(n: TsgoNode): Parameter = node(n) as Parameter
    private fun typeParameter(n: TsgoNode): TypeParameter = node(n) as TypeParameter

    private fun typeParameters(n: TsgoNode): List<TypeParameter>? = optList(n.typeParameterList()) { typeParameter(it) }
    private fun parameters(n: TsgoNode): List<Parameter> = list(n.parameters()) { parameter(it) }
    private fun typeArguments(list: TsgoNodeList?): List<TypeNode>? = optList(list) { ty(it) }
    private fun heritage(n: TsgoNode): List<HeritageClause>? =
        optList(n.classLikeData()?.heritageClauses ?: n.asInterfaceHeritage()) { node(it) as HeritageClause }

    private fun TsgoNode.asInterfaceHeritage(): TsgoNodeList? =
        if (kind(this) == "InterfaceDeclaration") asInterfaceDeclaration()!!.heritageClauses else null

    private fun asterisk(n: TsgoNode): Boolean =
        getFunctionFlags(n).value and FunctionFlagsGenerator.value != 0u

    // ---- the conversion ----

    private fun node(n: TsgoNode): Node {
        val core: Node = when (val k = kind(n)) {
            // statements
            "Block" -> Block(statements = list(n.statements()) { st(it) }, multiLine = n.asBlock()!!.multiLine, pos = start(n), end = end(n))
            "EmptyStatement" -> EmptyStatement(pos = start(n), end = end(n))
            "VariableStatement" -> VariableStatement(
                declarationList = node(run { n.asVariableStatement()!!.declarationList!! }) as VariableDeclarationList,
                modifiers = modifiers(n), pos = declStart(n), end = end(n),
            )
            "VariableDeclarationList" -> {
                val f = n.flags.value
                val flags = when {
                    f and NodeFlagsBlockScopedMask == NodeFlagsAwaitUsing.value -> SyntaxKind.AwaitUsingKeyword
                    f and NodeFlagsBlockScopedMask == NodeFlagsUsing.value -> SyntaxKind.UsingKeyword
                    f and NodeFlagsBlockScopedMask == NodeFlagsConst.value -> SyntaxKind.ConstKeyword
                    f and NodeFlagsBlockScopedMask == NodeFlagsLet.value -> SyntaxKind.LetKeyword
                    else -> SyntaxKind.VarKeyword
                }
                VariableDeclarationList(
                    declarations = list(run { n.asVariableDeclarationList()!!.declarations!!.nodes }) { node(it) as VariableDeclaration },
                    flags = flags, pos = start(n), end = end(n),
                )
            }
            "VariableDeclaration" -> n.asVariableDeclaration()!!.let { d ->
                VariableDeclaration(
                    name = ex(d.name!!), type = d.type?.let { ty(it) }, initializer = d.initializer?.let { ex(it) },
                    exclamationToken = d.exclamationToken != null, pos = start(n), end = end(n),
                )
            }
            "ExpressionStatement" -> ExpressionStatement(expression = ex(n.expression()!!), pos = start(n), end = end(n))
            "IfStatement" -> n.asIfStatement()!!.let { s ->
                IfStatement(expression = ex(s.expression!!), thenStatement = st(s.thenStatement!!), elseStatement = s.elseStatement?.let { st(it) }, pos = start(n), end = end(n))
            }
            "DoStatement" -> DoStatement(statement = st(n.statement()!!), expression = ex(n.expression()!!), pos = start(n), end = end(n))
            "WhileStatement" -> WhileStatement(expression = ex(n.expression()!!), statement = st(n.statement()!!), pos = start(n), end = end(n))
            "ForStatement" -> n.asForStatement()!!.let { s ->
                ForStatement(
                    initializer = s.initializer?.let { node(it) }, condition = s.condition?.let { ex(it) },
                    incrementor = s.incrementor?.let { ex(it) }, statement = st(n.statement()!!), pos = start(n), end = end(n),
                )
            }
            "ForInStatement" -> n.asForInOrOfStatement()!!.let { s ->
                ForInStatement(initializer = node(s.initializer!!), expression = ex(s.expression!!), statement = st(s.statement!!), pos = start(n), end = end(n))
            }
            "ForOfStatement" -> n.asForInOrOfStatement()!!.let { s ->
                ForOfStatement(
                    awaitModifier = s.awaitModifier != null, initializer = node(s.initializer!!), expression = ex(s.expression!!),
                    statement = st(s.statement!!), pos = start(n), end = end(n),
                )
            }
            "ContinueStatement" -> ContinueStatement(label = n.label()?.let { id(it) }, pos = start(n), end = end(n))
            "BreakStatement" -> BreakStatement(label = n.label()?.let { id(it) }, pos = start(n), end = end(n))
            "ReturnStatement" -> ReturnStatement(expression = n.expression()?.let { ex(it) }, pos = start(n), end = end(n))
            "WithStatement" -> WithStatement(expression = ex(n.expression()!!), statement = st(n.statement()!!), pos = start(n), end = end(n))
            "SwitchStatement" -> run {
                val caseBlock = n.asSwitchStatement()!!.caseBlock!!
                SwitchStatement(
                    expression = ex(n.expression()!!), caseBlock = list(caseBlock.asCaseBlock()!!.clauses!!.nodes) { node(it) },
                    pos = start(n), end = end(n),
                )
            }
            "CaseClause" -> CaseClause(expression = ex(n.expression()!!), statements = list(n.statements()) { st(it) }, pos = start(n), end = end(n))
            "DefaultClause" -> DefaultClause(statements = list(n.statements()) { st(it) }, pos = start(n), end = end(n))
            "LabeledStatement" -> LabeledStatement(label = id(n.label()!!), statement = st(n.statement()!!), pos = start(n), end = end(n))
            "ThrowStatement" -> ThrowStatement(expression = n.expression()?.let { ex(it) }, pos = start(n), end = end(n))
            "TryStatement" -> n.asTryStatement()!!.let { s ->
                TryStatement(
                    tryBlock = block(s.tryBlock!!), catchClause = s.catchClause?.let { node(it) as CatchClause },
                    finallyBlock = s.finallyBlock?.let { block(it) }, pos = start(n), end = end(n),
                )
            }
            "CatchClause" -> run {
                val clause = n.asCatchClause()!!
                CatchClause(
                    variableDeclaration = clause.variableDeclaration?.let { node(it) as VariableDeclaration },
                    block = block(clause.block!!), pos = start(n), end = end(n),
                )
            }
            "DebuggerStatement" -> DebuggerStatement(pos = start(n), end = end(n))
            "FunctionDeclaration" -> FunctionDeclaration(
                name = n.name()?.let { id(it) }, typeParameters = typeParameters(n), parameters = parameters(n),
                type = n.type()?.let { ty(it) }, body = n.body()?.let { block(it) }, modifiers = modifiers(n),
                asteriskToken = asterisk(n), pos = declStart(n), end = end(n),
            )
            "ClassDeclaration" -> ClassDeclaration(
                name = n.name()?.let { id(it) }, typeParameters = typeParameters(n), heritageClauses = heritage(n),
                members = list(n.members()) { member(it) }, modifiers = modifiers(n), decorators = decorators(n),
                pos = declStart(n), end = end(n),
            )
            "ClassExpression" -> ClassExpression(
                name = n.name()?.let { id(it) }, typeParameters = typeParameters(n), heritageClauses = heritage(n),
                members = list(n.members()) { member(it) }, modifiers = modifiers(n), decorators = decorators(n),
                pos = declStart(n), end = end(n),
            )
            "InterfaceDeclaration" -> InterfaceDeclaration(
                name = id(n.name()!!), typeParameters = typeParameters(n), heritageClauses = heritage(n),
                members = list(n.members()) { member(it) }, modifiers = modifiers(n), pos = declStart(n), end = end(n),
            )
            "TypeAliasDeclaration" -> TypeAliasDeclaration(
                name = id(n.name()!!), typeParameters = typeParameters(n), type = ty(n.type()!!), modifiers = modifiers(n),
                pos = declStart(n), end = end(n),
            )
            "EnumDeclaration" -> EnumDeclaration(
                name = id(n.name()!!), members = list(n.members()) { node(it) as EnumMember }, modifiers = modifiers(n),
                pos = declStart(n), end = end(n),
            )
            "EnumMember" -> EnumMember(name = ex(n.name()!!), initializer = n.initializer()?.let { ex(it) }, pos = start(n), end = end(n))
            "ModuleDeclaration" -> ModuleDeclaration(
                name = ex(n.name()!!), body = n.body()?.let { node(it) }, modifiers = modifiers(n), pos = declStart(n), end = end(n),
            )
            "ModuleBlock" -> ModuleBlock(statements = list(n.statements()) { st(it) }, pos = start(n), end = end(n))
            "ImportDeclaration" -> n.asImportDeclaration()!!.let { d ->
                ImportDeclaration(
                    importClause = d.importClause?.let { node(it) as ImportClause }, moduleSpecifier = ex(d.moduleSpecifier!!),
                    modifiers = modifiers(n), pos = declStart(n), end = end(n),
                )
            }
            "ImportClause" -> n.asImportClause()!!.let { c ->
                ImportClause(
                    name = c.name?.let { id(it) }, namedBindings = c.namedBindings?.let { node(it) }, isTypeOnly = n.isTypeOnly(),
                    pos = if (n.isTypeOnly()) afterKeyword(n, "type") else start(n), end = end(n),
                )
            }
            "NamespaceImport" -> NamespaceImport(name = id(n.name()!!), pos = start(n), end = end(n))
            "NamedImports" -> NamedImports(elements = list(n.elements()) { node(it) as ImportSpecifier }, pos = start(n), end = end(n))
            "ImportSpecifier" -> ImportSpecifier(
                propertyName = n.propertyName()?.let { id(it) }, name = id(n.name()!!), isTypeOnly = n.isTypeOnly(),
                pos = start(n), end = end(n),
            )
            "ImportEqualsDeclaration" -> n.asImportEqualsDeclaration()!!.let { d ->
                ImportEqualsDeclaration(
                    name = id(d.name!!), moduleReference = node(d.moduleReference!!), isTypeOnly = d.isTypeOnly,
                    modifiers = modifiers(n), pos = declStart(n), end = end(n),
                )
            }
            "ExternalModuleReference" -> ExternalModuleReference(expression = ex(n.expression()!!), pos = start(n), end = end(n))
            "ExportDeclaration" -> n.asExportDeclaration()!!.let { d ->
                ExportDeclaration(
                    exportClause = d.exportClause?.let { node(it) }, moduleSpecifier = d.moduleSpecifier?.let { ex(it) },
                    isTypeOnly = d.isTypeOnly, modifiers = modifiers(n), pos = declStart(n), end = end(n),
                )
            }
            "NamespaceExport" -> NamespaceExport(name = id(n.name()!!), pos = start(n), end = end(n))
            "NamedExports" -> NamedExports(elements = list(n.elements()) { node(it) as ExportSpecifier }, pos = start(n), end = end(n))
            "ExportSpecifier" -> ExportSpecifier(
                propertyName = n.propertyName()?.let { id(it) }, name = id(n.name()!!), isTypeOnly = n.isTypeOnly(),
                pos = start(n), end = end(n),
            )
            "ExportAssignment" -> n.asExportAssignment()!!.let { a ->
                ExportAssignment(
                    expression = ex(a.expression!!), isExportEquals = a.isExportEquals, modifiers = modifiers(n),
                    pos = declStart(n), end = end(n),
                )
            }
            // names and literals
            "Identifier", "PrivateIdentifier" -> Identifier(text = utf16(n.text()), pos = start(n), end = end(n))
            "ThisKeyword" -> Identifier(text = "this", pos = start(n), end = end(n))
            "SuperKeyword" -> Identifier(text = "super", pos = start(n), end = end(n))
            "TrueKeyword" -> Identifier(text = "true", pos = start(n), end = end(n))
            "FalseKeyword" -> Identifier(text = "false", pos = start(n), end = end(n))
            "NullKeyword" -> Identifier(text = "null", pos = start(n), end = end(n))
            "ImportKeyword" -> Identifier(text = "import", pos = start(n), end = end(n))
            "StringLiteral" -> StringLiteralNode(text = utf16(n.text()), pos = start(n), end = end(n))
            "NumericLiteral" -> NumericLiteralNode(text = source(n), pos = start(n), end = end(n))
            "BigIntLiteral" -> BigIntLiteralNode(text = source(n), pos = start(n), end = end(n))
            "RegularExpressionLiteral" -> RegularExpressionLiteralNode(text = source(n), pos = start(n), end = end(n))
            "NoSubstitutionTemplateLiteral" -> NoSubstitutionTemplateLiteralNode(text = utf16(n.text()), pos = start(n), end = end(n))
            "TemplateHead", "TemplateMiddle", "TemplateTail" -> StringLiteralNode(text = utf16(n.text()), pos = start(n), end = end(n))
            "TemplateExpression" -> n.asTemplateExpression()!!.let { t ->
                TemplateExpression(
                    head = node(t.head!!) as StringLiteralNode, templateSpans = list(t.templateSpans!!.nodes) { node(it) as TemplateSpan },
                    pos = start(n), end = end(n),
                )
            }
            "TemplateSpan" -> n.asTemplateSpan()!!.let { s ->
                TemplateSpan(expression = ex(s.expression!!), literal = node(s.literal!!), pos = start(n), end = end(n))
            }
            "ComputedPropertyName" -> ComputedPropertyName(expression = ex(n.expression()!!), pos = start(n), end = end(n))
            "QualifiedName" -> n.asQualifiedName()!!.let { q ->
                QualifiedName(left = node(q.left!!), right = id(q.right!!), pos = start(n), end = end(n))
            }
            // expressions
            "ArrayLiteralExpression" -> ArrayLiteralExpression(elements = list(n.elements()) { ex(it) }, pos = start(n), end = end(n))
            "ObjectLiteralExpression" -> ObjectLiteralExpression(properties = list(n.properties()) { node(it) }, pos = start(n), end = end(n))
            "PropertyAssignment" -> PropertyAssignment(
                name = ex(n.name()!!), initializer = ex(n.initializer()!!), modifiers = modifiers(n), pos = declStart(n), end = end(n),
            )
            "ShorthandPropertyAssignment" -> n.asShorthandPropertyAssignment()!!.let { s ->
                ShorthandPropertyAssignment(
                    name = id(n.name()!!), objectAssignmentInitializer = s.objectAssignmentInitializer?.let { ex(it) },
                    pos = start(n), end = end(n),
                )
            }
            "SpreadAssignment" -> SpreadAssignment(expression = ex(n.expression()!!), pos = start(n), end = end(n))
            "PropertyAccessExpression" -> n.asPropertyAccessExpression()!!.let { a ->
                PropertyAccessExpression(
                    expression = ex(a.expression!!), name = id(a.name!!), questionDotToken = a.questionDotToken != null,
                    pos = start(n), end = end(n),
                )
            }
            "ElementAccessExpression" -> n.asElementAccessExpression()!!.let { a ->
                ElementAccessExpression(
                    expression = ex(a.expression!!), argumentExpression = ex(a.argumentExpression!!),
                    questionDotToken = a.questionDotToken != null, pos = start(n), end = end(n),
                )
            }
            "CallExpression" -> n.asCallExpression()!!.let { c ->
                CallExpression(
                    expression = ex(c.expression!!), typeArguments = typeArguments(c.typeArguments),
                    arguments = list(c.arguments!!.nodes) { ex(it) }, questionDotToken = c.questionDotToken != null,
                    pos = start(n), end = end(n),
                )
            }
            "NewExpression" -> n.asNewExpression()!!.let { c ->
                NewExpression(
                    expression = ex(c.expression!!), typeArguments = typeArguments(c.typeArguments),
                    arguments = optList(c.arguments) { ex(it) }, pos = start(n), end = end(n),
                )
            }
            "TaggedTemplateExpression" -> n.asTaggedTemplateExpression()!!.let { t ->
                TaggedTemplateExpression(
                    tag = ex(t.tag!!), typeArguments = typeArguments(t.typeArguments), template = node(t.template!!),
                    pos = start(n), end = end(n),
                )
            }
            "TypeAssertionExpression" -> TypeAssertionExpression(type = ty(n.type()!!), expression = ex(n.expression()!!), pos = start(n), end = end(n))
            "ParenthesizedExpression" -> ParenthesizedExpression(expression = ex(n.expression()!!), pos = start(n), end = end(n))
            "FunctionExpression" -> FunctionExpression(
                name = n.name()?.let { id(it) }, typeParameters = typeParameters(n), parameters = parameters(n),
                type = n.type()?.let { ty(it) }, body = block(n.body()!!), modifiers = modifiers(n),
                asteriskToken = asterisk(n), pos = declStart(n), end = end(n),
            )
            "ArrowFunction" -> ArrowFunction(
                typeParameters = typeParameters(n), parameters = parameters(n), type = n.type()?.let { ty(it) },
                body = node(n.body()!!), modifiers = modifiers(n), pos = declStart(n), end = end(n),
            )
            "DeleteExpression" -> DeleteExpression(expression = ex(n.expression()!!), pos = start(n), end = end(n))
            "TypeOfExpression" -> TypeOfExpression(expression = ex(n.expression()!!), pos = start(n), end = end(n))
            "VoidExpression" -> VoidExpression(expression = ex(n.expression()!!), pos = start(n), end = end(n))
            "AwaitExpression" -> AwaitExpression(expression = ex(n.expression()!!), pos = start(n), end = end(n))
            "PrefixUnaryExpression" -> n.asPrefixUnaryExpression()!!.let { u ->
                PrefixUnaryExpression(operator = token(u.operator), operand = ex(u.operand!!), pos = start(n), end = end(n))
            }
            "PostfixUnaryExpression" -> n.asPostfixUnaryExpression()!!.let { u ->
                PostfixUnaryExpression(operand = ex(u.operand!!), operator = token(u.operator), pos = start(n), end = end(n))
            }
            "BinaryExpression" -> n.asBinaryExpression()!!.let { b ->
                BinaryExpression(left = ex(b.left!!), operator = token(b.operatorToken!!.kind), right = ex(b.right!!), pos = start(n), end = end(n))
            }
            "ConditionalExpression" -> run {
                val cond = n.asConditionalExpression()!!
                ConditionalExpression(condition = ex(cond.condition!!), whenTrue = ex(cond.whenTrue!!), whenFalse = ex(cond.whenFalse!!), pos = start(n), end = end(n))
            }
            "YieldExpression" -> n.asYieldExpression()!!.let { y ->
                YieldExpression(expression = y.expression?.let { ex(it) }, asteriskToken = y.asteriskToken != null, pos = start(n), end = end(n))
            }
            "SpreadElement" -> SpreadElement(expression = ex(n.expression()!!), pos = start(n), end = end(n))
            "AsExpression" -> AsExpression(expression = ex(n.expression()!!), type = ty(n.type()!!), pos = start(n), end = end(n))
            "SatisfiesExpression" -> SatisfiesExpression(expression = ex(n.expression()!!), type = ty(n.type()!!), pos = start(n), end = end(n))
            "NonNullExpression" -> NonNullExpression(expression = ex(n.expression()!!), pos = start(n), end = end(n))
            "MetaProperty" -> n.asMetaProperty()!!.let { m ->
                MetaProperty(keywordToken = token(m.keywordToken), name = id(m.name!!), pos = start(n), end = end(n))
            }
            "OmittedExpression" -> OmittedExpression(pos = start(n), end = end(n))
            "ObjectBindingPattern" -> ObjectBindingPattern(elements = list(n.elements()) { node(it) as BindingElement }, pos = start(n), end = end(n))
            "ArrayBindingPattern" -> ArrayBindingPattern(elements = list(n.elements()) { node(it) }, pos = start(n), end = end(n))
            "BindingElement" -> n.asBindingElement()!!.let { b ->
                // tsgo's elision in an array pattern (`[, b]`) is a nameless binding element.
                if (b.name == null) OmittedExpression(pos = start(n), end = end(n)) else BindingElement(
                    propertyName = b.propertyName?.let { ex(it) }, name = ex(b.name!!), initializer = b.initializer?.let { ex(it) },
                    dotDotDotToken = b.dotDotDotToken != null, pos = start(n), end = end(n),
                )
            }
            // class and interface members
            "PropertyDeclaration", "PropertySignature" -> PropertyDeclaration(
                name = ex(n.name()!!), type = n.type()?.let { ty(it) }, initializer = n.initializer()?.let { ex(it) },
                modifiers = modifiers(n), questionToken = n.questionToken()?.let { kind(it) == "QuestionToken" } ?: false,
                exclamationToken = n.questionToken()?.let { kind(it) == "ExclamationToken" } ?: false,
                decorators = decorators(n), pos = start(n), end = end(n),
            )
            "MethodDeclaration", "MethodSignature" -> MethodDeclaration(
                name = ex(n.name()!!), typeParameters = typeParameters(n), parameters = parameters(n),
                type = n.type()?.let { ty(it) }, body = n.body()?.let { block(it) }, modifiers = modifiers(n),
                asteriskToken = asterisk(n), questionToken = n.questionToken() != null, decorators = decorators(n),
                pos = start(n), end = end(n),
            )
            "CallSignature" -> MethodDeclaration(
                name = Identifier(""), typeParameters = typeParameters(n), parameters = parameters(n),
                type = n.type()?.let { ty(it) }, modifiers = modifiers(n), pos = start(n), end = end(n),
            )
            "ConstructSignature" -> {
                val newAt = declStart(n)
                MethodDeclaration(
                    name = Identifier("new", pos = newAt, end = newAt + 3), typeParameters = typeParameters(n),
                    parameters = parameters(n), type = n.type()?.let { ty(it) }, modifiers = modifiers(n), pos = start(n), end = end(n),
                )
            }
            "Constructor" -> Constructor(
                parameters = parameters(n), body = n.body()?.let { block(it) }, modifiers = modifiers(n), decorators = decorators(n),
                pos = start(n), end = end(n),
            )
            "GetAccessor" -> GetAccessor(
                name = ex(n.name()!!), parameters = parameters(n), type = n.type()?.let { ty(it) }, body = n.body()?.let { block(it) },
                modifiers = modifiers(n), decorators = decorators(n), pos = start(n), end = end(n),
            )
            "SetAccessor" -> SetAccessor(
                name = ex(n.name()!!), parameters = parameters(n), type = n.type()?.let { ty(it) }, body = n.body()?.let { block(it) },
                modifiers = modifiers(n), decorators = decorators(n), pos = start(n), end = end(n),
            )
            "IndexSignature" -> IndexSignature(
                parameters = parameters(n), type = n.type()?.let { ty(it) }, modifiers = modifiers(n), pos = start(n), end = end(n),
            )
            "SemicolonClassElement" -> SemicolonClassElement(pos = start(n), end = end(n))
            "ClassStaticBlockDeclaration" -> ClassStaticBlockDeclaration(body = block(n.body()!!), pos = start(n), end = end(n))
            "Parameter" -> run {
                val decl = n.asParameterDeclaration()!!
                Parameter(
                    name = ex(decl.name!!), type = decl.type?.let { ty(it) }, initializer = decl.initializer?.let { ex(it) },
                    dotDotDotToken = decl.dotDotDotToken != null, questionToken = decl.questionToken != null,
                    modifiers = modifiers(n), decorators = decorators(n), pos = start(n), end = end(n),
                )
            }
            "Decorator" -> Decorator(expression = ex(n.expression()!!), pos = start(n), end = end(n))
            "HeritageClause" -> n.asHeritageClause()!!.let { h ->
                HeritageClause(
                    token = token(h.token), types = list(h.types!!.nodes) { node(it) as ExpressionWithTypeArguments },
                    pos = start(n), end = end(n),
                )
            }
            "ExpressionWithTypeArguments" -> run {
                val ewta = n.asExpressionWithTypeArguments()!!
                ExpressionWithTypeArguments(expression = ex(ewta.expression!!), typeArguments = typeArguments(ewta.typeArguments), pos = start(n), end = end(n))
            }
            "TypeParameter" -> n.asTypeParameterDeclaration()!!.let { t ->
                TypeParameter(
                    name = id(t.name!!), constraint = t.constraint?.let { ty(it) }, default = t.defaultType?.let { ty(it) },
                    modifiers = modifiers(n), pos = start(n), end = end(n),
                )
            }
            // type nodes
            "TypeReference" -> run {
                val r = n.asTypeReferenceNode()!!
                TypeReference(typeName = node(r.typeName!!), typeArguments = typeArguments(r.typeArguments), pos = start(n), end = end(n))
            }
            "FunctionType" -> FunctionType(
                typeParameters = typeParameters(n), parameters = parameters(n), type = ty(n.type()!!), pos = start(n), end = end(n),
            )
            "ConstructorType" -> ConstructorType(
                typeParameters = typeParameters(n), parameters = parameters(n), type = ty(n.type()!!), modifiers = modifiers(n),
                pos = start(n), end = end(n),
            )
            "TypeQuery" -> run {
                val q = n.asTypeQueryNode()!!
                TypeQuery(exprName = node(q.exprName!!), typeArguments = typeArguments(q.typeArguments), pos = start(n), end = end(n))
            }
            "TypeLiteral" -> TypeLiteral(members = list(n.members()) { member(it) }, pos = start(n), end = end(n))
            "ArrayType" -> run {
                ArrayType(elementType = ty(n.asArrayTypeNode()!!.elementType!!), pos = start(n), end = end(n))
            }
            "TupleType" -> TupleType(elements = list(n.elements()) { ty(it) }, pos = start(n), end = end(n))
            "NamedTupleMember" -> n.asNamedTupleMember()!!.let { m ->
                NamedTupleMember(
                    name = id(m.name!!), type = ty(m.type!!), dotDotDotToken = m.dotDotDotToken != null,
                    questionToken = m.questionToken != null, pos = start(n), end = end(n),
                )
            }
            "OptionalType" -> OptionalType(type = ty(n.type()!!), pos = start(n), end = end(n))
            "RestType" -> RestType(type = ty(n.type()!!), pos = start(n), end = end(n))
            "UnionType" -> UnionType(types = list(run { n.asUnionTypeNode()!!.unionOrIntersectionTypeNodeBase.types!!.nodes }) { ty(it) }, pos = start(n), end = end(n))
            "IntersectionType" -> IntersectionType(types = list(run { n.asIntersectionTypeNode()!!.unionOrIntersectionTypeNodeBase.types!!.nodes }) { ty(it) }, pos = start(n), end = end(n))
            "ConditionalType" -> n.asConditionalTypeNode()!!.let { c ->
                ConditionalType(
                    checkType = ty(c.checkType!!), extendsType = ty(c.extendsType!!), trueType = ty(c.trueType!!),
                    falseType = ty(c.falseType!!), pos = start(n), end = end(n),
                )
            }
            "IndexedAccessType" -> n.asIndexedAccessTypeNode()!!.let { a ->
                IndexedAccessType(objectType = ty(a.objectType!!), indexType = ty(a.indexType!!), pos = start(n), end = end(n))
            }
            "MappedType" -> n.asMappedTypeNode()!!.let { m ->
                MappedType(
                    typeParameter = typeParameter(m.typeParameter!!), nameType = m.nameType?.let { ty(it) },
                    type = m.type?.let { ty(it) }, questionToken = m.questionToken != null && kind(m.questionToken!!) != "MinusToken",
                    readonlyToken = m.readonlyToken != null && kind(m.readonlyToken!!) != "MinusToken",
                    readonlyMinus = m.readonlyToken?.let { kind(it) == "MinusToken" } ?: false,
                    questionMinus = m.questionToken?.let { kind(it) == "MinusToken" } ?: false,
                    pos = start(n), end = end(n),
                )
            }
            "LiteralType" -> run {
                val literal = n.asLiteralTypeNode()!!.literal!!
                // `-core` parses the `null` type as a keyword type, as tsc's older trees did.
                if (kind(literal) == "NullKeyword") KeywordTypeNode(kind = SyntaxKind.NullKeyword, pos = start(n), end = end(n))
                else LiteralType(literal = ex(literal), pos = start(n), end = end(n))
            }
            "TemplateLiteralType" -> n.asTemplateLiteralTypeNode()!!.let { t ->
                TemplateLiteralType(
                    head = node(t.head!!) as StringLiteralNode,
                    templateSpans = list(t.templateSpans!!.nodes) { node(it) as TemplateLiteralTypeSpan }, pos = start(n), end = end(n),
                )
            }
            "TemplateLiteralTypeSpan" -> n.asTemplateLiteralTypeSpan()!!.let { s ->
                TemplateLiteralTypeSpan(type = ty(s.type!!), literal = node(s.literal!!), pos = start(n), end = end(n))
            }
            "ParenthesizedType" -> ParenthesizedType(type = ty(n.type()!!), pos = start(n), end = end(n))
            "TypePredicate" -> n.asTypePredicateNode()!!.let { p ->
                TypePredicate(
                    parameterName = node(p.parameterName!!), type = p.type?.let { ty(it) }, assertsModifier = p.assertsModifier != null,
                    pos = start(n), end = end(n),
                )
            }
            "TypeOperator" -> n.asTypeOperatorNode()!!.let { o ->
                TypeOperator(operator = token(o.operator), type = ty(o.type!!), pos = start(n), end = end(n))
            }
            "ImportType" -> n.asImportTypeNode()!!.let { i ->
                ImportType(
                    argument = ty(i.argument!!), qualifier = i.qualifier?.let { node(it) },
                    typeArguments = typeArguments(i.typeArguments), isTypeOf = i.isTypeOf,
                    pos = start(n), end = end(n),
                )
            }
            "ThisType" -> ThisType(pos = start(n), end = end(n))
            "InferType" -> run {
                InferType(typeParameter = typeParameter(n.asInferTypeNode()!!.typeParameter!!), pos = start(n), end = end(n))
            }
            "AnyKeyword", "UnknownKeyword", "NumberKeyword", "BigIntKeyword", "ObjectKeyword", "BooleanKeyword",
            "StringKeyword", "SymbolKeyword", "VoidKeyword", "UndefinedKeyword", "NeverKeyword", "IntrinsicKeyword" ->
                KeywordTypeNode(kind = SyntaxKind.valueOf(k), pos = start(n), end = end(n))
            // JSX
            "JsxElement" -> n.asJsxElement()!!.let { j ->
                JsxElement(
                    openingElement = node(j.openingElement!!) as JsxOpeningElement, children = list(j.children!!.nodes) { node(it) },
                    closingElement = node(j.closingElement!!) as JsxClosingElement, pos = start(n), end = end(n),
                )
            }
            "JsxOpeningElement" -> n.asJsxOpeningElement()!!.let { j ->
                JsxOpeningElement(tagName = ex(j.tagName!!), attributes = jsxAttributes(j.attributes), pos = start(n), end = end(n))
            }
            "JsxSelfClosingElement" -> n.asJsxSelfClosingElement()!!.let { j ->
                JsxSelfClosingElement(tagName = ex(j.tagName!!), attributes = jsxAttributes(j.attributes), pos = start(n), end = end(n))
            }
            "JsxClosingElement" -> run {
                JsxClosingElement(tagName = ex(n.asJsxClosingElement()!!.tagName!!), pos = start(n), end = end(n))
            }
            "JsxFragment" -> n.asJsxFragment()!!.let { j ->
                JsxFragment(children = list(j.children!!.nodes) { node(it) }, pos = start(n), end = end(n))
            }
            "JsxAttribute" -> n.asJsxAttribute()!!.let { a ->
                JsxAttribute(name = source(a.name!!), value = a.initializer?.let { node(it) }, pos = start(n), end = end(n))
            }
            "JsxSpreadAttribute" -> JsxSpreadAttribute(expression = ex(n.expression()!!), pos = start(n), end = end(n))
            "JsxText" -> JsxText(text = utf16(n.text()), pos = start(n), end = end(n))
            "JsxExpression" -> JsxExpressionContainer(expression = n.expression()?.let { ex(it) }, pos = start(n), end = end(n))
            else -> throw UnsupportedSyntax(k, fileName, start(n))
        }
        return track(core, n)
    }

    private fun jsxAttributes(attributes: TsgoNode?): List<Node> =
        attributes?.let { list(run { it.asJsxAttributes()!!.properties!!.nodes }) { a -> node(a) } } ?: emptyList()

    private companion object {
        /** tsgo's `NodeFlags.BlockScoped`: `Let | Const | Using`. */
        val NodeFlagsBlockScopedMask: UInt = NodeFlagsLet.value or NodeFlagsConst.value or NodeFlagsUsing.value
    }
}

/** A tsgo syntax kind the lowering's syntax has no class for. */
internal class UnsupportedSyntax(val kind: String, val fileName: String, val pos: Int) :
    RuntimeException("$fileName@$pos: no lowering syntax for tsgo kind $kind")
