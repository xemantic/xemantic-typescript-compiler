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

import com.xemantic.typescript.compiler.externals.DiagnosticCategory
import com.xemantic.typescript.compiler.externals.ExternalsDiagnostic
import com.xemantic.typescript.tsgo.ast.asSourceFile
import com.xemantic.typescript.tsgo.ast.body
import com.xemantic.typescript.tsgo.ast.expression
import com.xemantic.typescript.tsgo.ast.initializer
import com.xemantic.typescript.tsgo.ast.isTypeOnly
import com.xemantic.typescript.tsgo.ast.localize
import com.xemantic.typescript.tsgo.ast.memberList
import com.xemantic.typescript.tsgo.ast.modifierFlags
import com.xemantic.typescript.tsgo.ast.moduleSpecifier
import com.xemantic.typescript.tsgo.ast.name
import com.xemantic.typescript.tsgo.ast.parameterList
import com.xemantic.typescript.tsgo.ast.*
import com.xemantic.typescript.tsgo.ast.propertyName
import com.xemantic.typescript.tsgo.ast.questionToken
import com.xemantic.typescript.tsgo.ast.statementList
import com.xemantic.typescript.tsgo.ast.text
import com.xemantic.typescript.tsgo.ast.type
import com.xemantic.typescript.tsgo.ast.typeArgumentList
import com.xemantic.typescript.tsgo.ast.typeParameterList
import com.xemantic.typescript.tsgo.checker.Checker
import com.xemantic.typescript.tsgo.checker.IntrinsicType
import com.xemantic.typescript.tsgo.checker.getTypeArguments
import com.xemantic.typescript.tsgo.checker.getTypeAtLocation
import com.xemantic.typescript.tsgo.checker.getTypeFromTypeNode
import com.xemantic.typescript.tsgo.checker.isTupleType
import com.xemantic.typescript.tsgo.checker.typeParameters
import com.xemantic.typescript.tsgo.checker.typeToStringEx
import com.xemantic.typescript.tsgo.checker.getDeclaredTypeOfSymbol
import com.xemantic.typescript.tsgo.checker.getMergedSymbol
import com.xemantic.typescript.tsgo.compiler.CompilerHost
import com.xemantic.typescript.tsgo.compiler.Program
import com.xemantic.typescript.tsgo.compiler.ProgramOptions
import com.xemantic.typescript.tsgo.compiler.getTypeChecker
import com.xemantic.typescript.tsgo.core.CompilerOptions
import com.xemantic.typescript.tsgo.core.ParsedOptions
import com.xemantic.typescript.tsgo.go.io.fs.FileInfo
import com.xemantic.typescript.tsgo.go.io.fs.WalkDirFunc
import com.xemantic.typescript.tsgo.go.time.Time
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoMap
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.tsoptions.ParsedCommandLine
import com.xemantic.typescript.tsgo.vfs.Entries
import com.xemantic.typescript.tsgo.vfs.FS
import com.xemantic.typescript.tsgo.ast.Node as RawNode
import com.xemantic.typescript.tsgo.ast.NodeList as RawNodeList
import com.xemantic.typescript.tsgo.ast.Symbol as RawSymbol
import com.xemantic.typescript.tsgo.checker.Type as RawType

/**
 * Runs [block] on a thread whose stack is deep enough for tsgo's checker,
 * which recurses as deep as a 1 GB goroutine stack allows.
 */
internal expect fun <R> onDeepStack(block: () -> R): R

/** Go's package initialisation for what the program reaches (`parser` installs its JSDoc hook), once. */
private val goInit: Unit by lazy { com.xemantic.typescript.tsgo.parser.goInitPackage() }

/**
 * (TSGO.4-b) ONE checked tsgo program over a set of in-memory files, and the
 * typed view ([Node], [Type], [Symbol]) of it the generator walks.
 *
 * The program is built the way tsgo's own `compiler` tests build one: the
 * files on a read-only map file system wrapped with the BUNDLED libraries,
 * default compiler options (TypeScript 7's — `strict` on, the latest target,
 * the default lib), every file a root. It is checked SINGLE-THREADED, so one
 * checker answers both the diagnostics and every later question, in a fixed
 * order: the per-file semantic diagnostics run first and resolve the
 * program's types in tsgo's own order, so what a question asked afterwards
 * creates is the residue, not the bulk — which keeps an answer from depending
 * on the order a generation happens to ask in.
 *
 * A caller's file name that is not absolute (`t.ts`) is checked as `/t.ts`
 * and reported under its own name. Must be created, and used, inside
 * [onDeepStack].
 */
internal class Tree(files: List<Pair<String, String>>) {

    private val originalByPath = HashMap<String, String>()

    /** The program's root files, in the caller's order. */
    val sourceFiles: List<SourceFile>

    /** Everything the parser, binder and checker reported for the root files, then the global diagnostics. */
    val diagnostics: List<ExternalsDiagnostic>

    private val program: Program
    private val checker: Checker
    private val releaseChecker: (() -> Unit)?

    private val nodes = HashMap<RawNode, Node>()
    private val types = HashMap<RawType, Type>()
    private val symbols = HashMap<RawSymbol, Symbol>()

    init {
        goInit
        val contents = LinkedHashMap<String, String>()
        for ((name, content) in files) {
            val path = if (name.startsWith("/")) name else "/$name"
            originalByPath[path] = name
            contents[path] = content
        }
        val fs = com.xemantic.typescript.tsgo.bundled.wrapFS(MapFS(contents))
        val host = BundledLibSharingHost(
            com.xemantic.typescript.tsgo.compiler.newCompilerHost(
                "/", fs, com.xemantic.typescript.tsgo.bundled.libPath(), null, null,
            )!!,
        )
        val config = ParsedCommandLine(
            parsedConfig = ParsedOptions(
                compilerOptions = CompilerOptions(),
                fileNames = GoSlice.of(GoElem.STRING, *contents.keys.map { GoString.fromUtf16(it) }.toTypedArray()),
            ),
        )
        program = com.xemantic.typescript.tsgo.compiler.newProgram(
            ProgramOptions(host = host, config = config, singleThreaded = com.xemantic.typescript.tsgo.core.TSTrue),
        ) ?: error("tsgo built no program")
        val ctx = com.xemantic.typescript.tsgo.go.context.background()
        val (c, done) = program.getTypeChecker(ctx)
        checker = c ?: error("tsgo built no checker")
        releaseChecker = done
        val rawFiles = contents.keys.map { path ->
            program.getSourceFile(GoString.fromUtf16(path)) ?: error("tsgo did not load $path")
        }
        val collected = mutableListOf<ExternalsDiagnostic>()
        for (file in rawFiles) {
            collected += convert(program.getSyntacticDiagnostics(ctx, file))
        }
        for (file in rawFiles) {
            collected += convert(program.getSemanticDiagnostics(ctx, file))
        }
        collected += convert(program.getGlobalDiagnostics(ctx))
        diagnostics = collected
        sourceFiles = rawFiles.map { node(it.asNode()!!) as SourceFile }
    }

    /** Returns the checker to the program's pool. */
    fun release() {
        releaseChecker?.invoke()
    }

    private fun convert(list: GoSlice<com.xemantic.typescript.tsgo.ast.Diagnostic?>): List<ExternalsDiagnostic> =
        (0 until list.len).mapNotNull { index ->
            val diagnostic = list[index] ?: return@mapNotNull null
            val file = diagnostic.file
            val (line, character) = if (file != null) {
                val (l, c) = com.xemantic.typescript.tsgo.scanner.getECMALineAndUTF16CharacterOfPosition(file, diagnostic.loc.pos())
                (l + 1) to (c.value + 1)
            } else {
                null to null
            }
            ExternalsDiagnostic(
                fileName = file?.let { originalName(it.fileName) },
                line = line,
                character = character,
                category = when (diagnostic.category.value) {
                    0 -> DiagnosticCategory.Warning
                    1 -> DiagnosticCategory.Error
                    2 -> DiagnosticCategory.Suggestion
                    else -> DiagnosticCategory.Message
                },
                code = diagnostic.code,
                message = GoString.toUtf16(diagnostic.localize(com.xemantic.typescript.tsgo.locale.Locale())),
            )
        }

    private fun originalName(goPath: String): String {
        val path = GoString.toUtf16(goPath)
        return originalByPath[path] ?: path
    }

    // --- the lens --------------------------------------------------------------

    /** The checker's answers, as the generator asks them. */
    val lens: CheckedLens = object : CheckedLens {

        override fun typeOf(node: Expression): Type =
            type(checker.getTypeAtLocation(rawOf(node)))

        override fun render(type: Type): String = render(type, RENDER_FLAGS)

        override fun renderAliasBody(type: Type): String = render(type, RENDER_FLAGS or IN_TYPE_ALIAS)

        private fun render(type: Type, flags: UInt): String =
            type.raw?.let {
                GoString.toUtf16(checker.typeToStringEx(it, null, com.xemantic.typescript.tsgo.checker.TypeFormatFlags(flags), null))
            } ?: (type as? Type.Intrinsic)?.intrinsicName ?: "?"

        override fun typeOfTypeNode(node: TypeNode): Type =
            type(checker.getTypeFromTypeNode(rawOf(node)))

        override fun heritageBaseSymbol(base: Expression): Symbol? =
            symbolOrNull(checker.getSymbolAtLocation(rawOf(base)))

        override fun aliasTarget(symbol: Symbol): Symbol? {
            if (!symbol.flags.hasAny(SymbolFlags.Alias)) return null
            return symbolOrNull(checker.getAliasedSymbol(symbol.raw))
        }

        override fun typeReferenceSymbol(node: TypeReference): Symbol? {
            val name = when (val typeName = node.typeName) {
                is QualifiedName -> typeName.right
                else -> typeName
            }
            val symbol = symbolOrNull(checker.getSymbolAtLocation(rawOf(name))) ?: return null
            return aliasTarget(symbol) ?: symbol
        }
    }

    private fun rawOf(node: Node): RawNode =
        (node as NodeBase).raw ?: error("a synthesised node has no tsgo counterpart: $node")

    private fun symbolOrNull(raw: RawSymbol?): Symbol? =
        if (raw == null || raw === checker.unknownSymbol) null else symbol(raw)

    fun symbol(raw: RawSymbol): Symbol = symbols.getOrPut(raw) { Symbol(this, raw) }

    fun symbolName(raw: RawSymbol): String = GoString.toUtf16(raw.name)

    fun symbolDeclarations(raw: RawSymbol): List<Node> {
        val declarations = raw.declarations
        return (0 until declarations.len).mapNotNull { declarations[it]?.let(::node) }
    }

    // --- types ---------------------------------------------------------------

    fun type(raw: RawType?): Type {
        if (raw == null) return anyType
        types[raw]?.let { return it }
        val created = classify(raw)
        types[raw] = created
        return created
    }

    private fun classify(raw: RawType): Type {
        val flags = raw.flags.value
        fun has(bit: UInt) = flags and bit != 0u
        val symbol = raw.symbol?.let(::symbol)
        val objectFlags = raw.objectFlags.value
        return when {
            raw.data is IntrinsicType -> Type.Intrinsic(raw, intrinsicFlags(flags), GoString.toUtf16((raw.data as IntrinsicType).intrinsicName))
            // An ENUM type — the union of its members' literals carrying
            // EnumLiteral, a computed enum's Enum type, or the one literal of a
            // single-member enum: whichever it is, it is the enum's DECLARED
            // type. An enum MEMBER's literal (`Kind.A` of a larger enum) is not
            // a plain literal and maps to nothing.
            has(ENUM) || has(ENUM_LITERAL) -> {
                val owner = listOfNotNull(raw.symbol, raw.symbol?.parent?.let { checker.getMergedSymbol(it) })
                    .firstOrNull { it.flags.value and ENUM_SYMBOL != 0u && checker.getDeclaredTypeOfSymbol(it) === raw }
                if (owner != null) Type.Object(raw, symbol(owner)) else Type.Other(raw)
            }
            has(BOOLEAN) -> Type.Intrinsic(raw, setOf(TypeFlags.Boolean), "boolean")
            has(STRING_LITERAL) -> Type.StringLiteral(raw)
            // A template literal type (`${string}-${string}`) and a string
            // mapping (`Uppercase<T>`) are string SUBTYPES with no Kotlin
            // shape of their own: they widen to `String` as a string literal
            // does, the widening every mutable TypeScript position applies.
            has(TEMPLATE_LITERAL) || has(STRING_MAPPING) -> Type.StringLiteral(raw)
            has(NUMBER_LITERAL) -> Type.NumberLiteral(raw)
            has(BIGINT_LITERAL) -> Type.BigIntLiteral(raw)
            has(TYPE_PARAMETER) -> Type.TypeParam(raw, symbol)
            has(UNION) -> {
                val members = rawList(raw.data?.let { (it as com.xemantic.typescript.tsgo.checker.UnionType).unionOrIntersectionType.types })
                Type.Union(raw) { collapseEnums(members).map(::type) }
            }
            has(OBJECT) -> when {
                objectFlags and CLASS_OR_INTERFACE != 0u -> interfaceType(raw, symbol)
                objectFlags and REFERENCE != 0u && !isTupleType(raw) -> {
                    val targetRaw = (raw.data as com.xemantic.typescript.tsgo.checker.TypeReference).objectType.target
                    val target = targetRaw?.let(::type) as? Type.Interface
                    if (target == null) {
                        Type.Other(raw)
                    } else {
                        Type.Reference(raw, target) {
                            val count = target.typeParameters?.size ?: 0
                            rawList(checker.getTypeArguments(raw)).take(count).map(::type)
                        }
                    }
                }
                objectFlags and REFERENCE != 0u -> Type.Other(raw)
                else -> Type.Object(raw, symbol)
            }
            else -> Type.Other(raw)
        }
    }

    /**
     * A union's members with every COMPLETE enum folded back into the enum's
     * declared type: tsgo flattens `K | undefined` into `K.A | K.B | undefined`
     * (as it prints it back as `K | undefined`), and a member literal alone
     * maps to nothing — the enum does.
     */
    private fun collapseEnums(members: List<RawType>): List<RawType> {
        val byEnum = LinkedHashMap<RawType, MutableList<RawType>>()
        for (member in members) {
            if (member.flags.value and ENUM_LITERAL == 0u || member.flags.value and UNION != 0u) continue
            val owner = member.symbol?.parent?.let { checker.getMergedSymbol(it) } ?: continue
            if (owner.flags.value and ENUM_SYMBOL == 0u) continue
            val declared = checker.getDeclaredTypeOfSymbol(owner) ?: continue
            byEnum.getOrPut(declared) { mutableListOf() }.add(member)
        }
        if (byEnum.isEmpty()) return members
        val result = ArrayList<RawType>(members.size)
        val emitted = HashSet<RawType>()
        for (member in members) {
            val declared = byEnum.entries.firstOrNull { (declared, present) ->
                member in present && declared.flags.value and UNION != 0u &&
                    rawList(declared.data?.let { (it as com.xemantic.typescript.tsgo.checker.UnionType).unionOrIntersectionType.types })
                        .all { it in present }
            }?.key
            if (declared == null) result += member
            else if (emitted.add(declared)) result += declared
        }
        return result
    }

    private fun interfaceType(raw: RawType, symbol: Symbol?): Type.Interface {
        val parameters = rawList(
            (raw.data as? com.xemantic.typescript.tsgo.checker.InterfaceType)?.let { it.typeParameters() },
        )
        return Type.Interface(raw, symbol) { parameters.map(::type) }
    }

    private fun intrinsicFlags(flags: UInt): Set<TypeFlags> = buildSet {
        if (flags and 1u != 0u) add(TypeFlags.Any)
        if (flags and 2u != 0u) add(TypeFlags.Unknown)
        if (flags and 4u != 0u) add(TypeFlags.Undefined)
        if (flags and 8u != 0u) add(TypeFlags.Null)
        if (flags and 16u != 0u) add(TypeFlags.Void)
        if (flags and 32u != 0u) add(TypeFlags.String)
        if (flags and 64u != 0u) add(TypeFlags.Number)
        if (flags and 128u != 0u) add(TypeFlags.BigInt)
        if (flags and 256u != 0u) add(TypeFlags.Boolean)
        if (flags and 512u != 0u) add(TypeFlags.ESSymbol)
        if (flags and 8192u != 0u) add(TypeFlags.BooleanLiteral)
        if (flags and 131072u != 0u) add(TypeFlags.NonPrimitive)
        if (flags and 262144u != 0u) add(TypeFlags.Never)
    }

    private fun <T> rawList(slice: GoSlice<T?>?): List<T> =
        if (slice == null) emptyList() else (0 until slice.len).mapNotNull { slice[it] }

    // --- nodes -----------------------------------------------------------------

    /** The view node of [raw], created on first ask; every later ask answers the same object. */
    fun node(raw: RawNode): Node {
        nodes[raw]?.let { return it }
        val created = create(raw)
        nodes[raw] = created
        return created
    }

    private fun create(raw: RawNode): Node {
        val kind = raw.kind.value
        return when (kind) {
            307 -> SourceFile(this, raw, originalName(raw.asSourceFile()!!.fileName))
            244 -> VariableStatement(this, raw)
            262 -> VariableDeclarationList(this, raw)
            261 -> VariableDeclaration(this, raw)
            263 -> FunctionDeclaration(this, raw)
            264 -> ClassDeclaration(this, raw)
            265 -> InterfaceDeclaration(this, raw)
            266 -> TypeAliasDeclaration(this, raw)
            267 -> EnumDeclaration(this, raw)
            306 -> EnumMember(this, raw)
            268 -> moduleDeclaration(raw)
            269 -> ModuleBlock(this, raw)
            273 -> ImportDeclaration(this, raw)
            274 -> ImportClause(this, raw)
            275 -> NamespaceImport(this, raw)
            276 -> NamedImports(this, raw)
            277 -> ImportSpecifier(this, raw)
            272 -> ImportEqualsDeclaration(this, raw)
            284 -> ExternalModuleReference(this, raw)
            279 -> ExportDeclaration(this, raw)
            278 -> ExportAssignment(this, raw)
            280 -> NamedExports(this, raw)
            281 -> NamespaceExport(this, raw)
            282 -> ExportSpecifier(this, raw)
            79, 80 -> Identifier(this, raw, GoString.toUtf16(raw.text()))
            10, 14 -> StringLiteralNode(this, raw, GoString.toUtf16(raw.text()))
            8 -> NumericLiteralNode(this, raw, GoString.toUtf16(raw.text()))
            9 -> BigIntLiteralNode(this, raw, GoString.toUtf16(raw.text()))
            225 -> PrefixUnaryExpression(this, raw)
            212 -> PropertyAccessExpression(
                this, raw,
                { expression(raw.asPropertyAccessExpression()!!.expression) },
                { identifier(raw.asPropertyAccessExpression()!!.name) },
            )
            232 -> ClassExpression(this, raw)
            172, 173 -> PropertyDeclaration(this, raw)
            174, 175 -> MethodDeclaration(this, raw, syntheticName = null)
            180 -> MethodDeclaration(this, raw, syntheticName = "")
            181 -> MethodDeclaration(this, raw, syntheticName = "new")
            177 -> Constructor(this, raw)
            178 -> GetAccessor(this, raw)
            179 -> SetAccessor(this, raw)
            182 -> IndexSignature(this, raw)
            241 -> SemicolonClassElement(this, raw)
            176 -> ClassStaticBlockDeclaration(this, raw)
            170 -> Parameter(this, raw)
            299 -> HeritageClause(this, raw)
            234 -> ExpressionWithTypeArguments(this, raw)
            169 -> TypeParameter(this, raw)
            167 -> QualifiedName(this, raw)
            184 -> TypeReference(this, raw)
            185 -> FunctionType(this, raw)
            187 -> TypeQuery(this, raw)
            189 -> ArrayType(this, raw)
            193 -> UnionType(this, raw)
            197 -> ParenthesizedType(this, raw)
            199 -> TypeOperator(this, raw)
            202 ->
                if (raw.asLiteralTypeNode()!!.literal?.kind?.value == 105) KeywordTypeNode(this, raw, SyntaxKind.NullKeyword)
                else LiteralType(this, raw)
            else -> keywordKind(kind)?.let { KeywordTypeNode(this, raw, it) }
                ?: OtherNode(this, raw, GoString.toUtf16(raw.kind.string()))
        }
    }

    private fun keywordKind(kind: Int): SyntaxKind? = when (kind) {
        132 -> SyntaxKind.AnyKeyword
        159 -> SyntaxKind.UnknownKeyword
        157 -> SyntaxKind.UndefinedKeyword
        154 -> SyntaxKind.StringKeyword
        150 -> SyntaxKind.NumberKeyword
        135 -> SyntaxKind.BooleanKeyword
        163 -> SyntaxKind.BigIntKeyword
        155 -> SyntaxKind.SymbolKeyword
        151 -> SyntaxKind.ObjectKeyword
        146 -> SyntaxKind.NeverKeyword
        115 -> SyntaxKind.VoidKeyword
        141 -> SyntaxKind.IntrinsicKeyword
        198 -> SyntaxKind.ThisType
        else -> null
    }

    /**
     * A namespace — the OUTERMOST declaration of a dotted chain, every inner
     * declaration of which maps to the same view node (an inner one asked
     * first builds the chain from its outermost).
     */
    private fun moduleDeclaration(raw: RawNode): Node {
        var outer = raw
        while (outer.flags.value and NESTED_NAMESPACE != 0u) {
            val parent = outer.parent ?: break
            if (parent.kind.value != 268) break
            outer = parent
        }
        if (outer !== raw) return node(outer)
        var innermost = raw
        val chain = mutableListOf(raw)
        while (true) {
            val body = innermost.body() ?: break
            if (body.kind.value != 268) break
            innermost = body
            chain += body
        }
        val declaration = ModuleDeclaration(this, raw, innermost)
        for (inner in chain.drop(1)) nodes[inner] = declaration
        return declaration
    }

    /** A namespace's name — the dotted [PropertyAccessExpression] of a chain, synthesised. */
    fun moduleName(owner: ModuleDeclaration, raw: RawNode, innermost: RawNode): Expression {
        if (raw === innermost) return expression(raw.name())
        val names = mutableListOf<RawNode>()
        var current: RawNode = raw
        while (true) {
            names += current.name()!!
            if (current === innermost) break
            current = current.body()!!
        }
        var expression: Expression = identifier(names.first())
        for (name in names.drop(1)) {
            val left = expression
            expression = PropertyAccessExpression(this, null, { left }, { identifier(name) }).synthesizedUnder(owner) as Expression
        }
        return expression
    }

    fun literalOf(owner: LiteralType, raw: RawNode): Expression {
        val literal = raw.asLiteralTypeNode()!!.literal!!
        return when (literal.kind.value) {
            111 -> Identifier(this, null, "true").synthesizedUnder(owner) as Expression
            96 -> Identifier(this, null, "false").synthesizedUnder(owner) as Expression
            else -> expression(literal)
        }
    }

    // --- child accessors (raw → raw), read by the view's lazy properties ---------

    fun sourceText(raw: RawNode): String = GoString.toUtf16(raw.asSourceFile()!!.text)

    fun statements(raw: RawNode): List<Statement> = list(raw.statementList()).map { it as Statement }

    fun variableDeclarationListOf(raw: RawNode): RawNode = raw.asVariableStatement()!!.declarationList!!

    fun declarationsOfList(raw: RawNode): List<VariableDeclaration> =
        list(raw.asVariableDeclarationList()!!.declarations).map { it as VariableDeclaration }

    fun declarationListKind(raw: RawNode): SyntaxKind {
        val flags = raw.flags.value
        return when {
            flags and 6u == 6u -> SyntaxKind.AwaitUsingKeyword
            flags and 4u != 0u -> SyntaxKind.UsingKeyword
            flags and 2u != 0u -> SyntaxKind.ConstKeyword
            flags and 1u != 0u -> SyntaxKind.LetKeyword
            else -> SyntaxKind.VarKeyword
        }
    }

    fun modifiers(raw: RawNode): Set<ModifierFlag> {
        val flags = raw.modifierFlags().value
        if (flags == 0u) return emptySet()
        return buildSet {
            if (flags and 1u != 0u) add(ModifierFlag.Public)
            if (flags and 2u != 0u) add(ModifierFlag.Private)
            if (flags and 4u != 0u) add(ModifierFlag.Protected)
            if (flags and 8u != 0u) add(ModifierFlag.Readonly)
            if (flags and 16u != 0u) add(ModifierFlag.Override)
            if (flags and 32u != 0u) add(ModifierFlag.Export)
            if (flags and 64u != 0u) add(ModifierFlag.Abstract)
            if (flags and 128u != 0u) add(ModifierFlag.Declare)
            if (flags and 256u != 0u) add(ModifierFlag.Static)
            if (flags and 512u != 0u) add(ModifierFlag.Accessor)
            if (flags and 1024u != 0u) add(ModifierFlag.Async)
            if (flags and 2048u != 0u) add(ModifierFlag.Default)
            if (flags and 4096u != 0u) add(ModifierFlag.Const)
            if (flags and 8192u != 0u) add(ModifierFlag.In)
            if (flags and 16384u != 0u) add(ModifierFlag.Out)
        }
    }

    fun nameOf(raw: RawNode): RawNode? = raw.name()

    fun typeOf(raw: RawNode): RawNode? = raw.type()

    fun initializerOf(raw: RawNode): RawNode? = raw.initializer()

    fun bodyOf(raw: RawNode): RawNode? = raw.body()

    fun expressionOf(raw: RawNode): RawNode? = raw.expression()

    fun moduleSpecifierOf(raw: RawNode): RawNode? = raw.moduleSpecifier()

    fun importClauseOf(raw: RawNode): RawNode? = raw.asImportDeclaration()!!.importClause

    fun namedBindingsOf(raw: RawNode): RawNode? = raw.asImportClause()!!.namedBindings

    fun propertyNameOf(raw: RawNode): RawNode? = raw.propertyName()

    fun moduleReferenceOf(raw: RawNode): RawNode = raw.asImportEqualsDeclaration()!!.moduleReference!!

    fun exportClauseOf(raw: RawNode): RawNode? = raw.asExportDeclaration()!!.exportClause

    fun isTypeOnly(raw: RawNode): Boolean = raw.isTypeOnly()

    fun isExportEquals(raw: RawNode): Boolean = raw.asExportAssignment()!!.isExportEquals

    fun elementsOf(raw: RawNode): List<Node> = list(
        when (raw.kind.value) {
            276 -> raw.asNamedImports()!!.elements
            else -> raw.asNamedExports()!!.elements
        },
    )

    fun hasQuestionToken(raw: RawNode): Boolean = raw.questionToken() != null

    fun hasDotDotDotToken(raw: RawNode): Boolean = raw.asParameterDeclaration()!!.dotDotDotToken != null

    fun prefixOperator(raw: RawNode): SyntaxKind = when (raw.asPrefixUnaryExpression()!!.operator.value) {
        41 -> SyntaxKind.MinusToken
        40 -> SyntaxKind.PlusToken
        55 -> SyntaxKind.TildeToken
        54 -> SyntaxKind.ExclamationToken
        else -> SyntaxKind.Other
    }

    fun prefixOperand(raw: RawNode): RawNode? = raw.asPrefixUnaryExpression()!!.operand

    fun heritageToken(raw: RawNode): SyntaxKind = when (raw.asHeritageClause()!!.token.value) {
        95 -> SyntaxKind.ExtendsKeyword
        118 -> SyntaxKind.ImplementsKeyword
        else -> SyntaxKind.Other
    }

    fun heritageTypes(raw: RawNode): List<ExpressionWithTypeArguments> =
        list(raw.asHeritageClause()!!.types).map { it as ExpressionWithTypeArguments }

    fun heritageClauses(raw: RawNode): List<HeritageClause>? =
        com.xemantic.typescript.tsgo.ast.getHeritageClauses(raw)?.let { clauses -> list(clauses).map { it as HeritageClause } }

    fun members(raw: RawNode): List<ClassElement> = list(raw.memberList()).map { it as ClassElement }

    fun enumMembers(raw: RawNode): List<EnumMember> = list(raw.memberList()).map { it as EnumMember }

    fun parameters(raw: RawNode): List<Parameter> = list(raw.parameterList()).map { it as Parameter }

    /** A declaration's type parameters, null where it declares no `<…>` list. */
    fun typeParameters(raw: RawNode): List<TypeParameter>? =
        raw.typeParameterList()?.let { parameters -> list(parameters).map { it as TypeParameter } }

    /** A reference's type arguments, null where none are written. */
    fun typeArguments(raw: RawNode): List<TypeNode>? =
        raw.typeArgumentList()?.let { arguments -> list(arguments).map { it as TypeNode } }

    fun constraintOf(raw: RawNode): RawNode? = raw.asTypeParameterDeclaration()!!.constraint

    fun defaultOf(raw: RawNode): RawNode? = raw.asTypeParameterDeclaration()!!.defaultType

    fun qualifiedLeft(raw: RawNode): RawNode = raw.asQualifiedName()!!.left!!

    fun qualifiedRight(raw: RawNode): RawNode = raw.asQualifiedName()!!.right!!

    fun typeNameOf(raw: RawNode): RawNode = raw.asTypeReferenceNode()!!.typeName!!

    fun exprNameOf(raw: RawNode): RawNode = raw.asTypeQueryNode()!!.exprName!!

    fun elementTypeOf(raw: RawNode): RawNode = raw.asArrayTypeNode()!!.elementType!!

    fun unionTypes(raw: RawNode): List<TypeNode> =
        list(raw.asUnionTypeNode()!!.unionOrIntersectionTypeNodeBase.types).map { it as TypeNode }

    fun typeOperatorKind(raw: RawNode): SyntaxKind = when (raw.asTypeOperatorNode()!!.operator.value) {
        148 -> SyntaxKind.ReadonlyKeyword
        143 -> SyntaxKind.KeyOfKeyword
        158 -> SyntaxKind.UniqueKeyword
        else -> SyntaxKind.Other
    }

    // --- typed child helpers -----------------------------------------------------

    fun expression(raw: RawNode?): Expression = node(raw ?: error("missing expression")) as Expression

    fun expressionOrNull(raw: RawNode?): Expression? = raw?.let { node(it) as Expression }

    fun identifier(raw: RawNode?): Identifier = identifierOrNull(raw) ?: error("missing identifier")

    /**
     * An identifier — and, where a name position holds a string literal
     * (`export { "a-b" as c }`), that literal's text as one: the generator
     * reads only the text of such a name.
     */
    fun identifierOrNull(raw: RawNode?): Identifier? = when (val node = raw?.let(::node)) {
        null -> null
        is Identifier -> node
        is StringLiteralNode -> Identifier(this, null, node.text).synthesizedUnder(node.parent) as Identifier
        else -> null
    }

    fun typeNode(raw: RawNode?): TypeNode = node(raw ?: error("missing type")) as TypeNode

    fun typeNodeOrNull(raw: RawNode?): TypeNode? = raw?.let { node(it) as TypeNode }

    private fun list(raw: RawNodeList?): List<Node> {
        if (raw == null) return emptyList()
        val nodes = raw.nodes
        return (0 until nodes.len).mapNotNull { nodes[it]?.let(::node) }
    }

    private companion object {
        const val UNION = 134217728u
        const val ENUM_LITERAL = 32768u
        const val ENUM = 65536u
        const val BOOLEAN = 256u
        const val STRING_LITERAL = 1024u
        const val NUMBER_LITERAL = 2048u
        const val BIGINT_LITERAL = 4096u
        const val TYPE_PARAMETER = 524288u
        const val TEMPLATE_LITERAL = 4194304u
        const val STRING_MAPPING = 8388608u
        const val OBJECT = 1048576u
        const val CLASS_OR_INTERFACE = 3u
        const val REFERENCE = 4u
        const val NESTED_NAMESPACE = 32u
        const val ENUM_SYMBOL = 384u
        /**
         * tsgo's default `typeToString` flags plus `NoTruncation`: a marker
         * says what the mapping lost, so it is never `{ ...; }`.
         */
        const val RENDER_FLAGS = 1064961u
        const val IN_TYPE_ALIAS = 8388608u
    }
}

/** The four primitives the type mapper widens to — synthesised, carrying no tsgo type. */
internal val anyType: Type = Type.Intrinsic(null, setOf(TypeFlags.Any), "any")
internal val stringType: Type = Type.Intrinsic(null, setOf(TypeFlags.String), "string")
internal val numberType: Type = Type.Intrinsic(null, setOf(TypeFlags.Number), "number")
internal val booleanType: Type = Type.Intrinsic(null, setOf(TypeFlags.Boolean), "boolean")

/**
 * A compiler host that parses each BUNDLED library file once per process and
 * hands every later program the same tree. The DOM library alone is ~40,000
 * lines; re-parsing the whole default library set for every generation cost
 * more heap than a test worker has (a per-module set builds one program per
 * call). Sharing a bound library file between programs is tsgo's own design:
 * binding is once per file (`bindOnce`), the checkers of one program already
 * share every file, and the project system shares library files across
 * programs the same way.
 *
 * Published copy-on-write: generations may run on several threads, and a lost
 * race costs one parse, never a wrong tree — a library's text and parse
 * options are the whole key, and two parses of one key are interchangeable.
 */
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
private class BundledLibSharingHost(private val delegate: CompilerHost) : CompilerHost by delegate {

    override fun getSourceFile(p0: SourceFileParseOptions): com.xemantic.typescript.tsgo.ast.SourceFile? {
        if (!p0.fileName.startsWith("bundled:///")) return delegate.getSourceFile(p0)
        val cached = libraries.load()
        cached[p0.fileName]?.firstOrNull { it.first.goEquals(p0) }?.let { return it.second }
        val parsed = delegate.getSourceFile(p0) ?: return null
        val entry = p0.goCopy() to parsed
        while (true) {
            val current = libraries.load()
            val next = HashMap(current)
            next[p0.fileName] = (current[p0.fileName] ?: emptyList()) + entry
            if (libraries.compareAndSet(current, next)) return parsed
        }
    }

    companion object {
        val libraries = kotlin.concurrent.atomics.AtomicReference<Map<String, List<Pair<SourceFileParseOptions, com.xemantic.typescript.tsgo.ast.SourceFile>>>>(emptyMap())
    }
}

/** A read-only `vfs.FS` over an in-memory map of absolute paths (tsgo's `vfstest.FromMap`, case-sensitive). */
private class MapFS(files: Map<String, String>) : FS {
    private val files = files.entries.associate { GoString.fromUtf16(it.key) to GoString.fromUtf16(it.value) }
    private val dirs: Set<String> = this.files.keys.flatMap { file ->
        file.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" }.map { it.ifEmpty { "/" } }
    }.toSet() + "/"

    override fun useCaseSensitiveFileNames(): Boolean = true
    override fun fileExists(p0: String): Boolean = p0 in files
    override fun readFile(p0: String): Tuple2<String, Boolean> = files[p0]?.let { Tuple2(it, true) } ?: Tuple2("", false)
    override fun directoryExists(p0: String): Boolean = p0 in dirs
    override fun getAccessibleEntries(p0: String): Entries {
        val prefix = if (p0.endsWith("/")) p0 else "$p0/"
        val children = (files.keys + dirs).filter {
            it.startsWith(prefix) && it.length > prefix.length && '/' !in it.substring(prefix.length)
        }
        return Entries(
            files = GoSlice.of(GoElem.STRING, *children.filter { it in files }.map { it.substring(prefix.length) }.sorted().toTypedArray()),
            directories = GoSlice.of(GoElem.STRING, *children.filter { it in dirs }.map { it.substring(prefix.length) }.sorted().toTypedArray()),
            symlinks = GoMap.make(com.xemantic.typescript.tsgo.runtime.goUnitElem),
        )
    }
    override fun realpath(p0: String): String = p0
    override fun stat(p0: String): FileInfo? = null
    override fun walkDir(p0: String, p1: WalkDirFunc?): GoError? = error("walkDir is not supported on an in-memory program")
    override fun writeFile(p0: String, p1: String): GoError? = error("read-only")
    override fun appendFile(p0: String, p1: String): GoError? = error("read-only")
    override fun remove(p0: String): GoError? = error("read-only")
    override fun chtimes(p0: String, p1: Time, p2: Time): GoError? = error("read-only")
}
