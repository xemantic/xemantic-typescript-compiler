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

package com.xemantic.typescript.compiler.externals

import com.xemantic.typescript.compiler.Binder
import com.xemantic.typescript.compiler.CheckedLens
import com.xemantic.typescript.compiler.CheckedNodeSink
import com.xemantic.typescript.compiler.Checker
import com.xemantic.typescript.compiler.ClassDeclaration
import com.xemantic.typescript.compiler.ClassStaticBlockDeclaration
import com.xemantic.typescript.compiler.CompilerOptions
import com.xemantic.typescript.compiler.Constructor
import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.DiagnosticCategory
import com.xemantic.typescript.compiler.EnumDeclaration
import com.xemantic.typescript.compiler.Expression
import com.xemantic.typescript.compiler.FunctionDeclaration
import com.xemantic.typescript.compiler.FunctionType
import com.xemantic.typescript.compiler.GetAccessor
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.InterfaceDeclaration
import com.xemantic.typescript.compiler.MethodDeclaration
import com.xemantic.typescript.compiler.ModifierFlag
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.Parser
import com.xemantic.typescript.compiler.PropertyDeclaration
import com.xemantic.typescript.compiler.SemicolonClassElement
import com.xemantic.typescript.compiler.SetAccessor
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.StringLiteralNode
import com.xemantic.typescript.compiler.Type
import com.xemantic.typescript.compiler.TypeAliasDeclaration
import com.xemantic.typescript.compiler.TypeNode
import com.xemantic.typescript.compiler.TypeReference
import com.xemantic.typescript.compiler.anyType
import com.xemantic.typescript.compiler.computeParserFlags
import com.xemantic.typescript.compiler.runWithDeepStack

/**
 * Kotlin `external` declarations generated from a CHECKED TypeScript program.
 *
 * This is the (EXT.1) first cut: every EXPORTED, non-generic
 * [InterfaceDeclaration] of one source file, rendered as a Kotlin
 * `external interface` whose member types are the CHECKER's answers — resolved
 * types, never re-read annotation syntax. That is the gap Dukat and Karakum
 * never closed: both translate `.d.ts` SYNTAX, so a member typed by an alias
 * (`p: Species` where `type Species = string`) comes out as an opaque
 * `Species` there and as `String` here, because the type was resolved by the
 * same engine that type-checked the program.
 *
 * ## Error policy
 *
 * A program with checker ERRORS is still rendered — refusing would make the
 * generator unusable on any program this checker is not yet perfect on — and
 * the diagnostics are exposed on [diagnostics]/[errors] so a caller can decide
 * for itself whether to trust the output.
 *
 * ## Collection model
 *
 * Facts are collected DURING the [CheckedNodeSink] callback into immutable
 * model values carrying only strings — no AST node, [com.xemantic.typescript.compiler.Type]
 * or [com.xemantic.typescript.compiler.Symbol] is retained, and nothing is
 * keyed by a node — which is what keeps this module entirely in `commonMain`
 * (the kir `CheckedFacts` alternative keys by node identity via
 * `IdentityHashMap`, which is `java.*` and forces `jvmMain`). The one identity
 * question left — "was this node visited before?" (the spine may walk a tree
 * more than once) — is answered by a linear `===` scan, the same pattern
 * `CheckedFacts.file` uses, which is safe where a `HashMap` keyed by a data-class
 * node would deep-recurse `hashCode()` over the whole subtree.
 */
public class KotlinExternals internal constructor(
    /** The generated Kotlin source: `public external interface` declarations. */
    public val kotlin: String,
    /**
     * The SAME rendering with the `external` modifier omitted — produced by the
     * renderer's own flag, never by text surgery.
     *
     * Exists solely for the compile gate: `KotlinMetadataCompiler` refuses the
     * `external` modifier on an interface (`modifier 'external' is not
     * applicable to 'interface'` — it is a Kotlin/JS platform notion, and a
     * metadata compilation has no platform), so what the gate grades is the
     * TYPE MAPPING, and the `external` modifier is outside it.
     */
    public val compileCheckSource: String,
    /** Everything the parser and checker reported, in that order. */
    public val diagnostics: List<Diagnostic>,
) {

    /** The diagnostics that are errors — the caller's reason to distrust [kotlin]. */
    public val errors: List<Diagnostic>
        get() = diagnostics.filter { it.category == DiagnosticCategory.Error }

}

/**
 * Parses, binds and checks [source], and renders Kotlin `external` declarations
 * for every exported interface it declares.
 *
 * The shape of the front half is `kir`'s `checkTypeScript`, and both of its
 * non-obvious choices are inherited deliberately: `useRealLibs` because an
 * unknown name degrades to `any` SILENTLY (so the failure would be a wrong
 * declaration rather than a diagnostic), and [runWithDeepStack] because the
 * checker recurses deeply on ordinary input and `Checker`'s `init` block IS the
 * check — the sink has already fired by the time the constructor returns.
 */
public fun generateKotlinExternals(
    fileName: String,
    source: String,
    options: CompilerOptions = CompilerOptions(useRealLibs = true),
): KotlinExternals {
    val flags = computeParserFlags(fileName, source, options)
    val parser = Parser(
        source,
        fileName,
        forceJsx = flags.forceJsx,
        topLevelAwait = flags.topLevelAwait,
        needsJsxFlag = flags.needsJsxFlag,
        noImplicitAny = flags.noImplicitAny,
    )
    val sourceFile = parser.parse()
    val parseDiagnostics = parser.getDiagnostics()
    val binderResult = Binder(options).bind(sourceFile)
    val collector = ExternalsCollector(
        exportedInterfaces = exportedInterfaceDeclarations(sourceFile),
        exportedClasses = exportedClassDeclarations(sourceFile),
        exportedEnums = exportedEnumDeclarations(sourceFile),
        exportedAliases = exportedAliasDeclarations(sourceFile),
        exportedFunctions = exportedFunctionDeclarations(sourceFile),
    )
    val checker = runWithDeepStack {
        Checker(
            options,
            listOf(binderResult),
            isMultiFileSource = true,
            checkedSink = collector,
        )
    }
    return KotlinExternals(
        kotlin = renderKotlinExternals(collector.declarations, external = true),
        compileCheckSource = renderKotlinExternals(collector.declarations, external = false),
        diagnostics = parseDiagnostics + checker.getDiagnostics(),
    )
}

/**
 * (EXT.2) The file's exported [InterfaceDeclaration]s, pre-scanned from the
 * SYNTAX before the check runs — the membership set behind "render a named
 * type by its name". Syntax suffices for the SET (exported-ness and the name
 * are written); what still needs the checker is the IDENTITY test at each use,
 * which compares the resolved type's own declaration against these nodes by
 * `===`, so a lib type or a non-exported neighbour that shares the spelling is
 * positive-evidence excluded.
 */
private fun exportedInterfaceDeclarations(sourceFile: SourceFile): List<InterfaceDeclaration> =
    sourceFile.statements
        .filterIsInstance<InterfaceDeclaration>()
        .filter { ModifierFlag.Export in it.modifiers }

/**
 * (EXT.4) The file's exported TOP-LEVEL classes. A DEFAULT-exported class is
 * deliberately excluded: `export default class` binds the module's `default`
 * export, not the written name, and the default-exports rung owns that mapping
 * — rendering it as a named class here would be silently half-right.
 */
private fun exportedClassDeclarations(sourceFile: SourceFile): List<ClassDeclaration> =
    sourceFile.statements
        .filterIsInstance<ClassDeclaration>()
        .filter { ModifierFlag.Export in it.modifiers && ModifierFlag.Default !in it.modifiers }

/** (EXT.4) The file's exported TOP-LEVEL enums, `const` ones included — the
 *  collector refuses those loudly, so they must reach it. */
private fun exportedEnumDeclarations(sourceFile: SourceFile): List<EnumDeclaration> =
    sourceFile.statements
        .filterIsInstance<EnumDeclaration>()
        .filter { ModifierFlag.Export in it.modifiers && ModifierFlag.Default !in it.modifiers }

/** (EXT.4) The file's exported TOP-LEVEL type aliases — membership for the
 *  alias arm, so a namespace-nested alias never renders at top level. */
private fun exportedAliasDeclarations(sourceFile: SourceFile): List<TypeAliasDeclaration> =
    sourceFile.statements
        .filterIsInstance<TypeAliasDeclaration>()
        .filter { ModifierFlag.Export in it.modifiers }

/**
 * (EXT.3) The file's exported TOP-LEVEL function declarations — the membership
 * test behind the [FunctionDeclaration] sink arm, because the sink fires for
 * NESTED function declarations too and only the module surface is generated.
 */
private fun exportedFunctionDeclarations(sourceFile: SourceFile): List<FunctionDeclaration> =
    sourceFile.statements
        .filterIsInstance<FunctionDeclaration>()
        .filter { ModifierFlag.Export in it.modifiers && it.name != null }

/**
 * Collects exported interfaces as the checker walks past their declarations.
 *
 * Everything the lens is asked happens INSIDE [declaration] — a [CheckedLens]
 * is valid only for the duration of the callback that received it, so member
 * types are resolved and mapped to Kotlin TEXT on the spot, and the model
 * retains no checker object at all.
 */
private class ExternalsCollector(
    private val exportedInterfaces: List<InterfaceDeclaration>,
    private val exportedClasses: List<ClassDeclaration>,
    private val exportedEnums: List<EnumDeclaration>,
    private val exportedAliases: List<TypeAliasDeclaration>,
    private val exportedFunctions: List<FunctionDeclaration>,
) : CheckedNodeSink {

    val declarations = mutableListOf<ExternalDeclaration>()

    /**
     * (EXT.4) The NAMING set — the declarations a resolved type may be
     * rendered by NAME against. Interfaces, classes (an instance type is a
     * [Type.Interface] whose symbol declares the class) and enums (a
     * member-less [Type.Object] carrying the enum symbol). A `const` enum is
     * excluded: its declaration is refused (no runtime object), so a name
     * pointing at it would reference a type the generated module does not
     * declare.
     */
    private val nameableDeclarations: List<Node> =
        exportedInterfaces + exportedClasses +
            exportedEnums.filterNot { ModifierFlag.Const in it.modifiers }

    /**
     * (EXT.2) The generated-name predicate for [TypeScope]: a [Type.Interface]
     * whose declaration IS one of this file's exported interfaces — reached
     * directly or as a [Type.Reference]'s target — answers its written name.
     * The declaration is consulted on the TARGET for a reference (`kir/api`'s
     * lesson: a Reference's own symbol carries no declaration where its
     * target's does).
     */
    private fun generatedNameOf(type: Type): String? {
        val symbol = when (type) {
            is Type.Reference -> type.target.symbol
            // Type.Interface (interfaces AND class instance types) and the
            // member-less Object an enum resolves to both land here; the
            // identity test below is what keeps the widening sound.
            is Type.Object -> type.symbol
            else -> null
        } ?: return null
        val declared = symbol.declarations.any { declaration ->
            nameableDeclarations.any { it === declaration }
        }
        return if (declared) symbol.name else null
    }

    private fun scopeOf(typeParamNames: Set<String>): TypeScope =
        TypeScope(typeParamNames, ::generatedNameOf)

    /**
     * (EXT.2) The one place a member ANNOTATION becomes Kotlin type text.
     *
     * A bare reference to the enclosing declaration's own type parameter is
     * answered SYNTACTICALLY, before the lens is asked — measured, the lens at
     * an interface-declaration callback resolves `T` to `any` (an interface's
     * type parameters are not part of the reconstructed ambient, which carries
     * a FUNCTION's), and `any` is the silent direction. Everything else goes
     * through the checker as before; `optional` and `returnPosition` behave as
     * in [kotlinTypeText].
     */
    private fun annotationText(
        annotation: TypeNode?,
        optional: Boolean,
        returnPosition: Boolean,
        lens: CheckedLens,
        scope: TypeScope,
    ): String {
        val mapped = annotationTextOrNull(annotation, returnPosition, lens, scope)
        return when {
            mapped == null -> {
                val type = annotation?.let { lens.typeOfTypeNode(it) } ?: anyType
                "Any? /* xtsc: unmapped ${commentSafe(lens.render(type))} */"
            }
            !optional -> mapped
            // A nullable FUNCTION type needs the parentheses; a name does not.
            " -> " in mapped -> "($mapped)?"
            else -> "$mapped?"
        }
    }

    /**
     * (EXT.3) [annotationText] without the fallback — null where the shape
     * does not map, so a COMPOSITE (a function type, a generic argument)
     * refuses as a WHOLE rather than shipping a half-translated signature.
     *
     * The function-type arm is SYNTACTIC for the same measured reason the
     * own-type-parameter arm is: the pieces are annotations, and recursing
     * over them keeps alias resolution (each piece still goes through the
     * lens) while never asking the checker a question whose ambient it does
     * not have. Refused inside a function type, deliberately: a generic
     * function type, an OPTIONAL parameter (`(x?: s) => v` changes ARITY,
     * which `(String?) -> Unit` does not express — a caller may omit the
     * argument, not pass null), and a REST parameter.
     */
    private fun annotationTextOrNull(
        annotation: TypeNode?,
        returnPosition: Boolean,
        lens: CheckedLens,
        scope: TypeScope,
    ): String? {
        if (annotation == null) return null
        val ownParam = ((annotation as? TypeReference)?.typeName as? Identifier)
            ?.text
            ?.takeIf { annotation.typeArguments == null && it in scope.ownTypeParams }
        if (ownParam != null) return kotlinIdentifier(ownParam)
        if (annotation is FunctionType) {
            if (annotation.typeParameters != null) return null
            val parameters = annotation.parameters
                .filterNot { it.isCommentPlaceholder }
                .map { parameter ->
                    if (parameter.questionToken || parameter.dotDotDotToken) return null
                    annotationTextOrNull(
                        parameter.type ?: return null,
                        returnPosition = false,
                        lens = lens,
                        scope = scope,
                    ) ?: return null
                }
            val returnType = annotationTextOrNull(
                annotation.type,
                returnPosition = true,
                lens = lens,
                scope = scope,
            ) ?: return null
            return "(${parameters.joinToString(", ")}) -> $returnType"
        }
        return kotlinTypeTextOrNull(
            lens.typeOfTypeNode(annotation),
            returnPosition = returnPosition,
            scope = scope,
        )
    }

    /**
     * (EXT.3) An exported top-level function. Overloads are (EXT.4): a name
     * declared by MORE than one exported top-level declaration is a loud skip
     * — emitting each signature would also emit the implementation signature
     * beside its overloads, which is not the surface TypeScript consumers see.
     */
    private fun collectFunction(
        node: FunctionDeclaration,
        lens: CheckedLens,
    ): ExternalDeclaration {
        val name = node.name?.text
            ?: return SkippedDeclaration("top-level function without a name")
        if (exportedFunctions.count { it.name?.text == name } > 1) {
            return SkippedDeclaration("overloaded function $name")
        }
        val typeParameters = node.typeParameters.orEmpty().map { it.name.text }
        val scope = scopeOf(typeParameters.toSet())
        val markers = mutableListOf<String>()
        for (parameter in node.typeParameters.orEmpty()) {
            parameter.constraint?.let {
                markers.add(
                    "constraint on ${parameter.name.text}: " +
                        commentSafe(lens.render(lens.typeOfTypeNode(it))) +
                        " not carried"
                )
            }
            parameter.default?.let {
                markers.add(
                    "default for ${parameter.name.text}: " +
                        commentSafe(lens.render(lens.typeOfTypeNode(it))) +
                        " not carried"
                )
            }
        }
        val parameters = node.parameters
            .filterNot { it.isCommentPlaceholder }
            .mapIndexed { index, parameter ->
                val parameterName =
                    (parameter.name as? Identifier)?.text ?: "p$index"
                ExternalParameter(
                    name = parameterName,
                    type = annotationText(
                        parameter.type,
                        optional = parameter.questionToken,
                        returnPosition = false,
                        lens = lens,
                        scope = scope,
                    ),
                )
            }
        return ExternalTopLevelFunction(
            name = name,
            typeParameters = typeParameters,
            markers = markers,
            parameters = parameters,
            returnType = annotationText(
                node.type,
                optional = false,
                returnPosition = true,
                lens = lens,
                scope = scope,
            ),
        )
    }

    /**
     * Interfaces already collected, compared by IDENTITY: the spine may visit
     * the same tree more than once (a `declarationOnly` pre-pass walks the same
     * nodes), and first wins — the first visit is the one under the tightest
     * ambient the node ever has.
     */
    private val seen = mutableListOf<Node>()

    override fun expression(node: Expression, lens: CheckedLens) {}

    override fun declaration(node: Node, lens: CheckedLens) {
        // (EXT.4) Membership, not modifiers, for EVERY kind: the sink fires
        // for NESTED declarations too (a namespace body, a function body), and
        // only the pre-scanned TOP-LEVEL exported sets are the module surface.
        // A non-exported declaration is deliberately silent — nothing
        // consuming the module could have named it.
        when (node) {
            is InterfaceDeclaration -> {
                if (exportedInterfaces.none { it === node }) return
                if (seen.any { it === node }) return
                seen.add(node)
                declarations.add(collectInterface(node, lens))
            }
            is ClassDeclaration -> {
                if (exportedClasses.none { it === node }) return
                if (seen.any { it === node }) return
                seen.add(node)
                declarations.add(collectClass(node, lens))
            }
            is EnumDeclaration -> {
                if (exportedEnums.none { it === node }) return
                if (seen.any { it === node }) return
                seen.add(node)
                declarations.add(collectEnum(node))
            }
            is TypeAliasDeclaration -> {
                if (exportedAliases.none { it === node }) return
                if (seen.any { it === node }) return
                seen.add(node)
                declarations.add(collectTypeAlias(node, lens))
            }
            is FunctionDeclaration -> {
                if (exportedFunctions.none { it === node }) return
                if (seen.any { it === node }) return
                seen.add(node)
                declarations.add(collectFunction(node, lens))
            }
            else -> return
        }
    }

    /**
     * (EXT.4) An exported class. The instance side reuses the interface
     * member machinery; the STATIC side becomes the companion object, and a
     * static member refuses the syntactic own-type-parameter answer (a Kotlin
     * companion object cannot see the class's type parameters — and TypeScript
     * refuses `static x: T` too, so nothing correct is lost). `private` and
     * `protected` members are omitted WITHOUT a marker: they are not part of
     * the consumable surface, the same policy as a non-exported declaration.
     */
    private fun collectClass(
        node: ClassDeclaration,
        lens: CheckedLens,
    ): ExternalDeclaration {
        val name = node.name?.text
            ?: return SkippedDeclaration("class without a name")
        val typeParameters = node.typeParameters.orEmpty().map { it.name.text }
        val scope = scopeOf(typeParameters.toSet())
        val headerMarkers = mutableListOf<String>()
        for (parameter in node.typeParameters.orEmpty()) {
            parameter.constraint?.let {
                headerMarkers.add(
                    "constraint on ${parameter.name.text}: " +
                        commentSafe(lens.render(lens.typeOfTypeNode(it))) +
                        " not carried"
                )
            }
            parameter.default?.let {
                headerMarkers.add(
                    "default for ${parameter.name.text}: " +
                        commentSafe(lens.render(lens.typeOfTypeNode(it))) +
                        " not carried"
                )
            }
        }
        val members = mutableListOf<ExternalMember>()
        val staticMembers = mutableListOf<ExternalMember>()
        if (node.heritageClauses != null) {
            members.add(SkippedMember("heritage clause"))
        }
        // One declared constructor becomes the primary constructor; overloads
        // (two signatures plus the implementation is THREE Constructor nodes)
        // are a loud marker with no primary constructor at all — picking one
        // would invite calls the others refuse.
        val constructors = node.members.filterIsInstance<Constructor>()
        var constructorParameters: List<ExternalParameter>? = null
        when {
            constructors.size > 1 ->
                members.add(SkippedMember("multiple constructors"))
            constructors.size == 1 -> {
                constructorParameters = constructors.single().parameters
                    .filterNot { it.isCommentPlaceholder }
                    .mapIndexed { index, parameter ->
                        val parameterName =
                            (parameter.name as? Identifier)?.text ?: "p$index"
                        if (parameter.modifiers.isNotEmpty()) {
                            // `constructor(public x: number)` DECLARES a
                            // member; the expansion is a later rung, so the
                            // member's absence is loud.
                            members.add(
                                SkippedMember("parameter property $parameterName")
                            )
                        }
                        ExternalParameter(
                            name = parameterName,
                            type = annotationText(
                                parameter.type,
                                optional = parameter.questionToken,
                                returnPosition = false,
                                lens = lens,
                                scope = scope,
                            ),
                        )
                    }
            }
        }
        for (member in node.members) {
            val modifiers = when (member) {
                is PropertyDeclaration -> member.modifiers
                is MethodDeclaration -> member.modifiers
                is GetAccessor -> member.modifiers
                is SetAccessor -> member.modifiers
                else -> emptySet()
            }
            if (ModifierFlag.Private in modifiers || ModifierFlag.Protected in modifiers) {
                continue
            }
            val isStatic = ModifierFlag.Static in modifiers
            val target = if (isStatic) staticMembers else members
            val memberScope = if (isStatic) scopeOf(emptySet()) else scope
            when (member) {
                is PropertyDeclaration -> collectProperty(member, lens, target, memberScope)
                is MethodDeclaration -> collectMethod(member, lens, target, memberScope)
                is Constructor -> {}
                is SemicolonClassElement -> {}
                // A static initialization block declares nothing a consumer
                // could name — pure runtime, silently outside the surface.
                is ClassStaticBlockDeclaration -> {}
                else -> target.add(
                    SkippedMember(member::class.simpleName ?: "member")
                )
            }
        }
        return ExternalClass(
            name = name,
            typeParameters = typeParameters,
            headerMarkers = headerMarkers,
            isAbstract = ModifierFlag.Abstract in node.modifiers,
            constructorParameters = constructorParameters,
            members = members,
            staticMembers = staticMembers,
        )
    }

    /**
     * (EXT.4) An exported enum — Kotlin cannot declare an `external enum
     * class`, so the shape is a sealed interface whose companion object
     * carries one `val` per entry, typed by the interface (the runtime enum
     * object's own members). A `const` enum is INLINED at every use site —
     * there is no runtime object for those vals to describe — so the whole
     * declaration refuses loudly, and [nameableDeclarations] excludes it.
     */
    private fun collectEnum(node: EnumDeclaration): ExternalDeclaration {
        if (ModifierFlag.Const in node.modifiers) {
            return SkippedDeclaration("const enum ${node.name.text} - no runtime object")
        }
        val entries = mutableListOf<String>()
        val markers = mutableListOf<String>()
        for (member in node.members) {
            when (val memberName = member.name) {
                is Identifier -> entries.add(memberName.text)
                // Cooked text — `"up-hill" = 1` names the member `up-hill`.
                is StringLiteralNode -> entries.add(memberName.text)
                else -> markers.add("enum member with a non-literal name")
            }
        }
        return ExternalEnum(node.name.text, entries, markers)
    }

    /**
     * (EXT.2) An exported alias whose body MAPS becomes a `public typealias`;
     * one whose body does not is a loud skip — emitting `typealias U = Any?`
     * would flatten every consumer signature that names it, silently.
     */
    private fun collectTypeAlias(
        node: TypeAliasDeclaration,
        lens: CheckedLens,
    ): ExternalDeclaration {
        if (node.typeParameters != null) {
            return SkippedDeclaration("generic type alias ${node.name.text}")
        }
        val body = lens.typeOfTypeNode(node.type)
        val mapped = kotlinTypeTextOrNull(
            body,
            returnPosition = false,
            scope = scopeOf(emptySet()),
        ) ?: return SkippedDeclaration(
            "type alias ${node.name.text} with unmappable body " +
                commentSafe(lens.render(body))
        )
        return ExternalTypeAlias(node.name.text, mapped)
    }

    private fun collectInterface(
        node: InterfaceDeclaration,
        lens: CheckedLens,
    ): ExternalDeclaration {
        // (EXT.2) A generic interface renders its type-parameter NAMES; what
        // Kotlin externals cannot carry — a constraint, a default — becomes a
        // loud header marker, never a silent widening.
        val typeParameters = node.typeParameters.orEmpty().map { it.name.text }
        val scope = scopeOf(typeParameters.toSet())
        val headerMarkers = mutableListOf<String>()
        for (parameter in node.typeParameters.orEmpty()) {
            parameter.constraint?.let {
                headerMarkers.add(
                    "constraint on ${parameter.name.text}: " +
                        commentSafe(lens.render(lens.typeOfTypeNode(it))) +
                        " not carried"
                )
            }
            parameter.default?.let {
                headerMarkers.add(
                    "default for ${parameter.name.text}: " +
                        commentSafe(lens.render(lens.typeOfTypeNode(it))) +
                        " not carried"
                )
            }
        }
        val members = mutableListOf<ExternalMember>()
        if (node.heritageClauses != null) {
            // Heritage is (EXT.2+) too: the supertype may be un-generated (a
            // lib type, a non-exported interface), so the clause is marked
            // rather than rendered or silently dropped.
            members.add(SkippedMember("heritage clause"))
        }
        for (member in node.members) {
            when (member) {
                is PropertyDeclaration -> collectProperty(member, lens, members, scope)
                is MethodDeclaration -> collectMethod(member, lens, members, scope)
                // A stray `;` between members is pure syntax — there is
                // nothing to generate and nothing to mark.
                is SemicolonClassElement -> {}
                else -> members.add(
                    SkippedMember(member::class.simpleName ?: "member")
                )
            }
        }
        return ExternalInterface(node.name.text, typeParameters, headerMarkers, members)
    }

    private fun collectProperty(
        member: PropertyDeclaration,
        lens: CheckedLens,
        members: MutableList<ExternalMember>,
        scope: TypeScope,
    ) {
        val name = (member.name as? Identifier)?.text
        if (name == null) {
            members.add(SkippedMember("member with a non-identifier name"))
            return
        }
        // The annotation is resolved by the CHECKER — `typeOfTypeNode` is
        // `getTypeFromTypeNode` under the walk's own ambient — so an alias
        // arrives as what it denotes; an absent annotation IS implicit `any`.
        // The one syntactic exception is [annotationText]'s own-type-parameter
        // rule.
        members.add(
            ExternalProperty(
                name = name,
                type = annotationText(
                    member.type,
                    optional = member.questionToken,
                    returnPosition = false,
                    lens = lens,
                    scope = scope,
                ),
                readOnly = ModifierFlag.Readonly in member.modifiers,
            )
        )
    }

    private fun collectMethod(
        member: MethodDeclaration,
        lens: CheckedLens,
        members: MutableList<ExternalMember>,
        scope: TypeScope,
    ) {
        val name = (member.name as? Identifier)?.text
        if (name == null) {
            members.add(SkippedMember("member with a non-identifier name"))
            return
        }
        if (member.questionToken) {
            // (EXT.3) An optional METHOD is a nullable function-typed
            // property: `m?(x: string): void` -> `var m: ((String) -> Unit)?`.
            // Refused to the marker when any piece does not map — a
            // half-translated signature is the silent direction.
            val parameterTypes = member.parameters
                .filterNot { it.isCommentPlaceholder }
                .map { parameter ->
                    if (parameter.questionToken || parameter.dotDotDotToken) null
                    else annotationTextOrNull(
                        parameter.type,
                        returnPosition = false,
                        lens = lens,
                        scope = scope,
                    )
                }
            val returnType = annotationTextOrNull(
                member.type,
                returnPosition = true,
                lens = lens,
                scope = scope,
            )
            if (parameterTypes.any { it == null } || returnType == null) {
                members.add(SkippedMember("optional method $name"))
                return
            }
            members.add(
                ExternalProperty(
                    name = name,
                    type = "((${parameterTypes.joinToString(", ")}) -> $returnType)?",
                    readOnly = false,
                )
            )
            return
        }
        val parameters = member.parameters
            .filterNot { it.isCommentPlaceholder }
            .mapIndexed { index, parameter ->
                // A destructuring parameter has no name of its own; a
                // positional one keeps the declaration readable.
                val parameterName =
                    (parameter.name as? Identifier)?.text ?: "p$index"
                ExternalParameter(
                    name = parameterName,
                    type = annotationText(
                        parameter.type,
                        optional = parameter.questionToken,
                        returnPosition = false,
                        lens = lens,
                        scope = scope,
                    ),
                )
            }
        members.add(
            ExternalFunction(
                name = name,
                parameters = parameters,
                returnType = annotationText(
                    member.type,
                    optional = false,
                    returnPosition = true,
                    lens = lens,
                    scope = scope,
                ),
            )
        )
    }

}
