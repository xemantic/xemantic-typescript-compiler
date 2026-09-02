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
import com.xemantic.typescript.compiler.ExportAssignment
import com.xemantic.typescript.compiler.ExportDeclaration
import com.xemantic.typescript.compiler.Expression
import com.xemantic.typescript.compiler.FunctionDeclaration
import com.xemantic.typescript.compiler.FunctionType
import com.xemantic.typescript.compiler.GetAccessor
import com.xemantic.typescript.compiler.HeritageClause
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.InterfaceDeclaration
import com.xemantic.typescript.compiler.MethodDeclaration
import com.xemantic.typescript.compiler.ModifierFlag
import com.xemantic.typescript.compiler.NamedExports
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.Parameter
import com.xemantic.typescript.compiler.Parser
import com.xemantic.typescript.compiler.PropertyDeclaration
import com.xemantic.typescript.compiler.QualifiedName
import com.xemantic.typescript.compiler.SemicolonClassElement
import com.xemantic.typescript.compiler.SetAccessor
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.SourceFileEntry
import com.xemantic.typescript.compiler.StringLiteralNode
import com.xemantic.typescript.compiler.SyntaxKind
import com.xemantic.typescript.compiler.Type
import com.xemantic.typescript.compiler.TypeAliasDeclaration
import com.xemantic.typescript.compiler.TypeNode
import com.xemantic.typescript.compiler.TypeParameter
import com.xemantic.typescript.compiler.TypeFlags
import com.xemantic.typescript.compiler.TypeQuery
import com.xemantic.typescript.compiler.TypeReference
import com.xemantic.typescript.compiler.VariableDeclaration
import com.xemantic.typescript.compiler.VariableStatement
import com.xemantic.typescript.compiler.anyType
import com.xemantic.typescript.compiler.booleanType
import com.xemantic.typescript.compiler.numberType
import com.xemantic.typescript.compiler.stringType
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
): KotlinExternals = generateKotlinExternals(files = listOf(SourceFileEntry(fileName, source)), options = options)

/**
 * (EXT.7) The MULTI-FILE entry point: one program over every [files] entry —
 * a package's whole `dist` declaration set, say — bound by ONE [Binder] (a program's
 * binder results must share one binder's tables, exactly as
 * `TypeScriptCompiler`'s multi-file site does) and checked by ONE [Checker],
 * so an import between the files resolves and a member typed by ANOTHER
 * file's exported interface renders by NAME under the same positive-identity
 * evidence a same-file reference gets.
 *
 * Give the files PATH-shaped names (`/pkg/dist/index.d.ts`), the shape every
 * published package has: the checker resolves a relative specifier against
 * the importer's directory (measured, a flat name happens to resolve too, but
 * that is not a contract to lean on). A `.js` specifier resolves to its
 * `.d.ts`/`.ts` sibling, the checker's own rule.
 *
 * The output is ONE Kotlin source in walk order — the files' order, then
 * declaration order within each — which is what a consumer of the package
 * wants: its surface, not its file layout. Declarations from several files
 * share one Kotlin package, so a TYPE name exported by two files is a loud
 * skip for the second (Kotlin refuses the redeclaration); functions share a
 * signature space instead and go through the overload collapse.
 */
public fun generateKotlinExternals(
    files: List<SourceFileEntry>,
    options: CompilerOptions = CompilerOptions(useRealLibs = true),
): KotlinExternals {
    val parseDiagnostics = mutableListOf<Diagnostic>()
    val sourceFiles = files.map { file ->
        val flags = computeParserFlags(file.fileName, file.content, options)
        val parser = Parser(
            file.content,
            file.fileName,
            forceJsx = flags.forceJsx,
            topLevelAwait = flags.topLevelAwait,
            needsJsxFlag = flags.needsJsxFlag,
            noImplicitAny = flags.noImplicitAny,
        )
        val sourceFile = parser.parse()
        parseDiagnostics += parser.getDiagnostics()
        sourceFile
    }
    val binder = Binder(options)
    val binderResults = sourceFiles.map { binder.bind(it) }
    val collector = ExternalsCollector(
        exportedInterfaces = sourceFiles.flatMap(::exportedInterfaceDeclarations),
        exportedClasses = sourceFiles.flatMap(::exportedClassDeclarations),
        exportedEnums = sourceFiles.flatMap(::exportedEnumDeclarations),
        exportedAliases = sourceFiles.flatMap(::exportedAliasDeclarations),
        exportedFunctions = sourceFiles.flatMap(::exportedFunctionDeclarations),
        exportedValues = sourceFiles.flatMap(::exportedValueDeclarations),
        topLevelExportWiring = sourceFiles.flatMap(::topLevelExportWiring),
    )
    val checker = runWithDeepStack {
        Checker(
            options,
            binderResults,
            isMultiFileSource = true,
            checkedSink = collector,
        )
    }
    val declarations = collector.finish()
    return KotlinExternals(
        kotlin = renderKotlinExternals(declarations, external = true),
        compileCheckSource = renderKotlinExternals(declarations, external = false),
        diagnostics = parseDiagnostics + checker.getDiagnostics(),
    )
}

/**
 * (EXT.9) The file's exported TOP-LEVEL value declarations — every
 * [VariableDeclaration] of an `export [declare] const|let|var` statement,
 * paired with whether its list is `const` (→ `val`). Membership for the
 * [VariableDeclaration] sink arm, which fires for every local too.
 */
private fun exportedValueDeclarations(sourceFile: SourceFile): List<ExportedValue> =
    sourceFile.statements
        .filterIsInstance<VariableStatement>()
        .filter { ModifierFlag.Export in it.modifiers }
        .flatMap { statement ->
            val readOnly = statement.declarationList.flags == SyntaxKind.ConstKeyword
            statement.declarationList.declarations.map { ExportedValue(it, readOnly) }
        }

/** (EXT.9) One exported value declaration and its list's `const`-ness. */
private class ExportedValue(val node: VariableDeclaration, val readOnly: Boolean)

/**
 * (EXT.7) The file's top-level EXPORT WIRING statements — `export { a } from
 * './x.js'`, `export * from`, `export default <expression>`, `export = x` —
 * which declare nothing of their own and re-route names a JavaScript consumer
 * binds. Module wiring (`@JsModule`/`@JsName`) is a later rung, so each one is
 * a LOUD marker; the membership set keeps a `declare module "x" { export … }`
 * body's wiring out, exactly as the other collections keep nested
 * declarations out. A bare `export {}` (the module-marker idiom) is excluded
 * here: it re-routes nothing, so there is nothing to be loud about.
 */
private fun topLevelExportWiring(sourceFile: SourceFile): List<Node> =
    sourceFile.statements.filter { statement ->
        when (statement) {
            is ExportAssignment -> true
            is ExportDeclaration ->
                statement.moduleSpecifier != null ||
                    (statement.exportClause as? NamedExports)?.elements?.isNotEmpty() ?: true
            else -> false
        }
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
 * (EXT.4) The file's exported TOP-LEVEL classes. (EXT.6): a DEFAULT-exported
 * class is collected too — it renders under its written name with a loud
 * `default export` marker, because the module's consumers bind `default`, not
 * the name, and module wiring (`@JsModule`/`@JsName`) is a later rung.
 */
private fun exportedClassDeclarations(sourceFile: SourceFile): List<ClassDeclaration> =
    sourceFile.statements
        .filterIsInstance<ClassDeclaration>()
        .filter { ModifierFlag.Export in it.modifiers }

/** (EXT.4) The file's exported TOP-LEVEL enums, `const` ones included — the
 *  collector refuses those loudly, so they must reach it. */
private fun exportedEnumDeclarations(sourceFile: SourceFile): List<EnumDeclaration> =
    sourceFile.statements
        .filterIsInstance<EnumDeclaration>()
        .filter { ModifierFlag.Export in it.modifiers }

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
        .filter { ModifierFlag.Export in it.modifiers }

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
    /** (EXT.9) Exported top-level value declarations. */
    private val exportedValues: List<ExportedValue>,
    /** (EXT.7) Top-level export-wiring statements, each a loud marker. */
    private val topLevelExportWiring: List<Node>,
) : CheckedNodeSink {

    private val declarations = mutableListOf<ExternalDeclaration>()

    /**
     * (EXT.7) The collected declarations after the two whole-program passes
     * that can only run once every file has been walked: the top-level
     * function OVERLOAD collapse (the (EXT.5) rule, applied to the module
     * surface — overloads of one name are consecutive in walk order, and a
     * second one mapping to the same Kotlin signature becomes a marker), and
     * the cross-file TYPE-NAME collision rule: every file's declarations share
     * one Kotlin package, so a second interface/class/enum/alias spelling a
     * name an earlier one already took is a loud skip rather than a
     * redeclaration the consumer's compiler refuses.
     */
    fun finish(): List<ExternalDeclaration> {
        val seenSignatures = HashSet<String>()
        val seenTypeNames = HashSet<String>()
        return declarations.map { declaration ->
            when (declaration) {
                is ExternalTopLevelFunction -> {
                    val signature = overloadSignature(
                        declaration.name,
                        declaration.typeParameters,
                        declaration.parameters,
                    )
                    if (seenSignatures.add(signature)) declaration
                    else SkippedDeclaration(
                        "overload of ${declaration.name} collapsing to a duplicate signature"
                    )
                }
                is ExternalInterface -> typeNameOnce(declaration, declaration.name, seenTypeNames)
                is ExternalClass -> typeNameOnce(declaration, declaration.name, seenTypeNames)
                is ExternalEnum -> typeNameOnce(declaration, declaration.name, seenTypeNames)
                is ExternalTypeAlias -> typeNameOnce(declaration, declaration.name, seenTypeNames)
                is ExternalTopLevelValue -> declaration
                is SkippedDeclaration -> declaration
            }
        }
    }

    private fun typeNameOnce(
        declaration: ExternalDeclaration,
        name: String,
        seenTypeNames: MutableSet<String>,
    ): ExternalDeclaration =
        if (seenTypeNames.add(name)) declaration
        else SkippedDeclaration("$name declared again by another file - one Kotlin package cannot hold both")

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
     * (EXT.10) The exported alias a written type reference NAMES, by the
     * checker's identity evidence — the reference's symbol carries one of this
     * program's exported alias declarations — or null. Positive evidence, as
     * for [generatedNameOf]: a lib alias (`Record<K, V>`) and a non-exported
     * neighbour sharing the spelling both answer null and keep the fallback.
     */
    private fun exportedAliasOf(annotation: TypeReference, lens: CheckedLens): TypeAliasDeclaration? {
        val symbol = lens.typeReferenceSymbol(annotation) ?: return null
        for (declaration in symbol.declarations) {
            val alias = exportedAliases.firstOrNull { it === declaration } ?: continue
            return alias
        }
        return null
    }

    /**
     * (EXT.10) Whether [alias] is EMITTED as a `typealias` by this generation —
     * decided by running its own collection ([collectTypeAlias]), which is a
     * function of the declaration and the lens and so can be asked at any
     * callback, and memoised by the alias's position in [exportedAliases]: the
     * reference may be walked before the declaration is. A name may be rendered
     * only against an alias the generated module declares; a skipped alias
     * (unmappable body) keeps every use on the fallback.
     */
    private val aliasRenderable = HashMap<Int, Boolean>()

    private fun aliasRenderable(alias: TypeAliasDeclaration, lens: CheckedLens): Boolean {
        val index = exportedAliases.indexOfFirst { it === alias }
        if (index < 0) return false
        return aliasRenderable.getOrPut(index) { collectTypeAlias(alias, lens) is ExternalTypeAlias }
    }

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
            // (EXT.11a) A `typeof X` query is marked by what was WRITTEN, not
            // by what it resolved to: this checker types a class value as its
            // INSTANCE type (CHK.73 — a class value has no static-side type
            // here), so `lens.render` would print `Action` for `typeof
            // Action` and the marker would read as a plain, mappable
            // reference that somehow fell back. `typeof Action` says what the
            // consumer actually lacks: the constructor side.
            mapped == null && annotation is TypeQuery ->
                "Any? /* xtsc: unmapped typeof ${commentSafe(entityNameText(annotation.exprName))} */"
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
     *
     * (EXT.11a) A `typeof X` query refuses OUTRIGHT, before the lens is
     * asked: the lens answers the INSTANCE type for a class value (CHK.73),
     * which is a plausible, mappable, WRONG type — `constructor(ctor: typeof
     * Action)` rendered `ctor: Action`, un-instantiated, and only Kotlin's
     * own arity check made it loud. The marker names the query ([annotationText]).
     */
    private fun annotationTextOrNull(
        annotation: TypeNode?,
        returnPosition: Boolean,
        lens: CheckedLens,
        scope: TypeScope,
    ): String? {
        if (annotation == null) return null
        if (annotation is TypeQuery) return null
        val ownParam = ((annotation as? TypeReference)?.typeName as? Identifier)
            ?.text
            ?.takeIf { annotation.typeArguments == null && it in scope.ownTypeParams }
        if (ownParam != null) return kotlinIdentifier(ownParam)
        // (EXT.10) A reference to an exported ALIAS this generation emits renders
        // by NAME wherever the resolved body would not map: a generic
        // instantiation (`Handler<string>` resolves to an anonymous function type
        // the type mapper cannot express, and the instantiation has no name once
        // resolved) and a non-generic alias with a function-typed body. The
        // Dukat pin — a use renders what the checker KNOWS — is kept: a
        // non-generic alias whose resolved body maps still renders that body
        // (`Species` → `String`), and the name is the second chance, never the
        // first. Arity must match exactly: a use relying on a defaulted alias
        // parameter has no Kotlin spelling and falls back.
        if (annotation is TypeReference) {
            val alias = exportedAliasOf(annotation, lens)
            if (alias != null && aliasRenderable(alias, lens)) {
                val aliasParameters = alias.typeParameters.orEmpty()
                val arguments = annotation.typeArguments.orEmpty()
                if (aliasParameters.isEmpty()) {
                    kotlinTypeTextOrNull(
                        lens.typeOfTypeNode(annotation),
                        returnPosition = returnPosition,
                        scope = scope,
                    )?.let { return it }
                    if (arguments.isEmpty()) return kotlinIdentifier(alias.name.text)
                } else if (arguments.size == aliasParameters.size) {
                    val mappedArguments = arguments.map { argument ->
                        annotationTextOrNull(
                            argument,
                            returnPosition = false,
                            lens = lens,
                            scope = scope,
                        ) ?: return null
                    }
                    return "${kotlinIdentifier(alias.name.text)}<${mappedArguments.joinToString(", ")}>"
                }
            }
        }
        val referenceArguments = (annotation as? TypeReference)?.typeArguments
        if (referenceArguments != null) {
            // (EXT.6) A generic reference names its TARGET by the CHECKER's
            // positive identity (the resolved reference's target declaration is
            // in the generated surface) while its ARGUMENTS render from their
            // own annotations — the (EXT.2) own-TP mechanism one level up,
            // measured on mitt: the lens ambient substitutes a declaration's
            // own type parameters to `any` silently, so `Emitter<Events>`
            // resolved as `Emitter<any>` and fell back. One unmappable
            // argument still refuses the WHOLE reference.
            val targetName = scope.generatedNameOf(lens.typeOfTypeNode(annotation))
            if (targetName != null) {
                val arguments = referenceArguments.map { argument ->
                    annotationTextOrNull(
                        argument,
                        returnPosition = false,
                        lens = lens,
                        scope = scope,
                    ) ?: return null
                }
                return "${kotlinIdentifier(targetName)}<${arguments.joinToString(", ")}>"
            }
        }
        if (annotation is FunctionType) {
            if (annotation.typeParameters != null) return null
            return functionTypeText(annotation.parameters, annotation.type, lens, scope)
        }
        return kotlinTypeTextOrNull(
            lens.typeOfTypeNode(annotation),
            returnPosition = returnPosition,
            scope = scope,
        )
    }

    /**
     * (EXT.11a) The SYNTACTIC function-type rendering shared by a written
     * function type and a callable interface's one call signature: every
     * parameter and the return type through [annotationTextOrNull] under the
     * enclosing scope, null where any piece refuses (the (EXT.3) rules: an
     * optional or rest parameter, an unannotated one, an unmappable type).
     *
     * A `this` parameter is a Kotlin RECEIVER, never a positional parameter:
     * `(this: SchedulerAction<T>, state: T) => void` is
     * `SchedulerAction<T>.(T) -> Unit`. Rendered positionally — the pre-(EXT.11a)
     * behaviour — it compiled, and a Kotlin lambda written against it would
     * have received the action where JavaScript passes the state: the SILENT
     * direction, which is why this is a rule and not a marker. The receiver
     * is the FIRST parameter named `this` (TypeScript allows no other
     * position; one found elsewhere refuses the whole type), goes through the
     * same mapping, and a function-typed receiver is parenthesised so
     * `(() -> Unit).(T) -> Unit` parses.
     */
    private fun functionTypeText(
        declaredParameters: List<Parameter>,
        returnAnnotation: TypeNode?,
        lens: CheckedLens,
        scope: TypeScope,
    ): String? {
        val declared = declaredParameters.filterNot { it.isCommentPlaceholder }
        val receiver = declared.firstOrNull()?.takeIf(::isThisParameter)
        val ordinary = if (receiver == null) declared else declared.drop(1)
        if (ordinary.any(::isThisParameter)) return null
        val receiverText = receiver?.let { parameter ->
            val text = annotationTextOrNull(
                parameter.type ?: return null,
                returnPosition = false,
                lens = lens,
                scope = scope,
            ) ?: return null
            if (" -> " in text) "($text)." else "$text."
        } ?: ""
        val parameters = ordinary.map { parameter ->
            if (parameter.questionToken || parameter.dotDotDotToken) return null
            annotationTextOrNull(
                parameter.type ?: return null,
                returnPosition = false,
                lens = lens,
                scope = scope,
            ) ?: return null
        }
        val returnType = annotationTextOrNull(
            returnAnnotation ?: return null,
            returnPosition = true,
            lens = lens,
            scope = scope,
        ) ?: return null
        return "$receiverText(${parameters.joinToString(", ")}) -> $returnType"
    }

    /** (EXT.11a) The `this` parameter of a signature — its name is the keyword, parsed as an [Identifier]. */
    private fun isThisParameter(parameter: Parameter): Boolean =
        (parameter.name as? Identifier)?.text == "this"

    /**
     * (EXT.11a) A DECLARATION's parameter list with its `this` parameter set
     * aside: `function f(this: Window, x: string)` declares ONE runtime
     * parameter, and `this` only constrains the call's receiver — a Kotlin
     * parameter list cannot say that, so the parameter is dropped and the
     * constraint becomes a loud marker ([thisMarker]) rather than a phantom
     * first argument. The marker spells the type as the mapping would render
     * it where it maps, and as the checker renders it otherwise — the same
     * split [annotationText] makes, minus the `Any?` a marker has no use for.
     */
    private class DeclaredParameters(
        /** The marker text for the `this` parameter, or null when there is none. */
        val thisMarker: String?,
        /** The parameters that remain, comment placeholders excluded. */
        val parameters: List<Parameter>,
    )

    private fun declaredParameters(
        parameters: List<Parameter>,
        lens: CheckedLens,
        scope: TypeScope,
    ): DeclaredParameters {
        val declared = parameters.filterNot { it.isCommentPlaceholder }
        val thisParameter = declared.firstOrNull(::isThisParameter)
            ?: return DeclaredParameters(null, declared)
        val type = thisParameter.type
        val rendered = when {
            type == null -> "any"
            else -> annotationTextOrNull(type, returnPosition = false, lens = lens, scope = scope)
                ?: commentSafe(lens.render(lens.typeOfTypeNode(type)))
        }
        return DeclaredParameters(
            thisMarker = "this parameter $rendered not carried",
            parameters = declared.filterNot { it === thisParameter },
        )
    }

    /**
     * (EXT.11a) The written spelling of a `typeof` query's entity name —
     * `Action`, `NS.Action` — for the marker. Syntax, deliberately: the
     * resolved type is the one thing the marker must NOT show (CHK.73).
     */
    private fun entityNameText(name: Node): String = when (name) {
        is Identifier -> name.text
        is QualifiedName -> "${entityNameText(name.left)}.${name.right.text}"
        else -> "an expression"
    }

    /**
     * (EXT.3) An exported top-level function. (EXT.7) Overloads render as
     * Kotlin overloads — the (EXT.5) method rule brought to the module surface
     * — with ONE deliberate omission: the IMPLEMENTATION signature. Where a
     * name is declared more than once in a file and one declaration carries
     * a body, that body's signature is not part of the surface TypeScript
     * consumers see (it is not callable), so it produces NOTHING — not a
     * marker, because nothing consumable was lost. A `.d.ts` has no
     * implementation, so every one of its overloads renders, and [finish]
     * collapses the ones that map to one Kotlin signature.
     */
    private fun collectFunction(
        node: FunctionDeclaration,
        lens: CheckedLens,
    ): ExternalDeclaration? {
        val name = node.name?.text
            ?: return SkippedDeclaration("top-level function without a name")
        val overloadGroup = exportedFunctions.filter {
            it.name?.text == name && it.parent === node.parent
        }
        if (node.body != null && overloadGroup.size > 1) return null
        val typeParameters = node.typeParameters.orEmpty().map { it.name.text }
        val scope = scopeOf(typeParameters.toSet())
        val markers = typeParameterMarkers(node.typeParameters, lens)
        defaultExportMarker(node.modifiers, markers)
        val declared = declaredParameters(node.parameters, lens, scope)
        declared.thisMarker?.let { markers.add(it) }
        val parameters = declared.parameters
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
     * (EXT.6) The loud record every DEFAULT-exported declaration carries: the
     * rendered name is the written one, but a JavaScript consumer binds the
     * module's `default` — the wiring (`@JsModule`/`@JsName`) is a later rung,
     * and until it lands the divergence must be readable in the output.
     */
    private fun defaultExportMarker(
        modifiers: Set<ModifierFlag>,
        markers: MutableList<String>,
    ) {
        if (ModifierFlag.Default in modifiers) {
            markers.add(0, "default export - consumers bind the module's default")
        }
    }

    /**
     * (EXT.5) The constraint/default markers for one type-parameter list — the
     * same loud records interfaces and functions have always rendered, shared
     * so a METHOD's own list gets them too.
     */
    private fun typeParameterMarkers(
        parameters: List<TypeParameter>?,
        lens: CheckedLens,
    ): MutableList<String> {
        val markers = mutableListOf<String>()
        for (parameter in parameters.orEmpty()) {
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
        return markers
    }

    /**
     * (EXT.5) Collapses overloads that MAP to one Kotlin signature: TypeScript
     * distinguishes them (a literal-typed parameter, say) where the mapping's
     * fallback does not, and Kotlin refuses conflicting overloads — so the
     * later duplicates become loud markers instead of a compile error.
     */
    private fun dedupeOverloads(members: MutableList<ExternalMember>) {
        val seenSignatures = HashSet<String>()
        for (index in members.indices) {
            val member = members[index] as? ExternalFunction ?: continue
            // The marker comment is NOT part of the signature Kotlin sees —
            // two different literal types both falling to `Any? /* xtsc: … */`
            // conflict however different their markers read — so the key is
            // the type text with the marker stripped.
            val signature = overloadSignature(member.name, member.typeParameters, member.parameters)
            if (!seenSignatures.add(signature)) {
                members[index] =
                    SkippedMember("overload of ${member.name} collapsing to a duplicate signature")
            }
        }
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
                collectFunction(node, lens)?.let { declarations.add(it) }
            }
            is VariableDeclaration -> {
                val exported = exportedValues.firstOrNull { it.node === node } ?: return
                if (seen.any { it === node }) return
                seen.add(node)
                declarations.add(collectValue(exported, lens))
            }
            is ExportAssignment, is ExportDeclaration -> {
                if (topLevelExportWiring.none { it === node }) return
                if (seen.any { it === node }) return
                seen.add(node)
                declarations.add(collectExportWiring(node))
            }
            else -> return
        }
    }

    /**
     * (EXT.9) `export [declare] const|let|var x: T` → `public external val|var
     * x: T`. The type is the annotation resolved by the checker where one is
     * written, and the checker's own answer for the name where none is
     * (`lens.typeOf` on the declared identifier — an un-annotated
     * `export const VERSION = "1.0"` is typed by its initializer, and only the
     * checker knows what that widened to). A destructuring declaration
     * (`export const { a, b } = …`) binds names no single `val` can carry and
     * is a loud skip.
     */
    private fun collectValue(exported: ExportedValue, lens: CheckedLens): ExternalDeclaration {
        val node = exported.node
        val name = (node.name as? Identifier)?.text
            ?: return SkippedDeclaration("destructuring export - no single name to declare")
        val scope = scopeOf(emptySet())
        val type =
            if (node.type != null) {
                annotationText(node.type, optional = false, returnPosition = false, lens = lens, scope = scope)
            } else {
                // A `const`'s literal type (`export const RETRIES = 3` is typed
                // `3`, the (WIDEN.1) const rule) widens to its base primitive
                // here: the declaration is what a consumer BINDS, and `val
                // RETRIES: 3` has no Kotlin shape — the same widening TypeScript
                // itself performs at every mutable position.
                kotlinTypeText(widenLiteral(lens.typeOf(node.name)), optional = false, returnPosition = false, lens = lens, scope = scope)
            }
        return ExternalTopLevelValue(name, type, exported.readOnly)
    }

    private fun widenLiteral(type: Type): Type = when (type) {
        is Type.StringLiteral -> stringType
        is Type.NumberLiteral -> numberType
        is Type.Intrinsic ->
            if (TypeFlags.BooleanLiteral in type.flags) booleanType else type
        else -> type
    }

    /**
     * (EXT.9) An accessor PAIR is one Kotlin property: `get x(): T` (and its
     * `set x(v: T)`) → `var x: T`, a getter alone → `val x: T`, a setter
     * alone → `var x: T` typed by the setter's parameter. The property is
     * emitted at the FIRST accessor's position and the partner is consumed,
     * so member order is preserved and nothing is rendered twice. Static
     * accessors reach the companion the same way.
     */
    private fun collectAccessor(
        member: Node,
        siblings: List<Node>,
        lens: CheckedLens,
        members: MutableList<ExternalMember>,
        scope: TypeScope,
    ) {
        val (name, _) = when (member) {
            is GetAccessor -> (member.name as? Identifier)?.text to true
            is SetAccessor -> (member.name as? Identifier)?.text to false
            else -> return
        }
        if (name == null) {
            members.add(SkippedMember("accessor with a non-identifier name"))
            return
        }
        val getter = siblings.filterIsInstance<GetAccessor>().firstOrNull { (it.name as? Identifier)?.text == name }
        val setter = siblings.filterIsInstance<SetAccessor>().firstOrNull { (it.name as? Identifier)?.text == name }
        // Emit at the first accessor of the pair only.
        val first: Node? = siblings.firstOrNull { it === getter || it === setter }
        if (first !== member) return
        val annotation = getter?.type
            ?: setter?.parameters?.firstOrNull { !it.isCommentPlaceholder }?.type
        members.add(
            ExternalProperty(
                name = name,
                type = annotationText(annotation, optional = false, returnPosition = false, lens = lens, scope = scope),
                readOnly = setter == null,
            )
        )
    }

    /**
     * (EXT.7) `export default <expression>` / `export = x` / `export { … }
     * [from '…']` / `export * from '…'`: nothing is declared, names are
     * re-routed for a JavaScript consumer, and the wiring that would express
     * that (`@JsModule`/`@JsName`) is a later rung — so each is a loud
     * marker spelling what it wires, never a silent drop.
     */
    private fun collectExportWiring(node: Node): ExternalDeclaration = when (node) {
        is ExportAssignment -> {
            val expression = (node.expression as? Identifier)?.text ?: "an expression"
            val form = if (node.isExportEquals) "export = " else "default export of "
            SkippedDeclaration("$form$expression - module wiring is a later rung")
        }
        is ExportDeclaration -> {
            val clause = when (val exportClause = node.exportClause) {
                null -> "*"
                is NamedExports -> exportClause.elements.joinToString(", ", "{ ", " }") {
                    val local = it.propertyName?.text
                    if (local == null || local == it.name.text) it.name.text
                    else "$local as ${it.name.text}"
                }
                else -> exportClause::class.simpleName ?: "clause"
            }
            val from = (node.moduleSpecifier as? StringLiteralNode)?.text
                ?.let { " from '$it'" } ?: ""
            val typeOnly = if (node.isTypeOnly) "type " else ""
            SkippedDeclaration(
                "re-export $typeOnly$clause$from - module wiring is a later rung"
            )
        }
        else -> SkippedDeclaration("export wiring")
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
        val headerMarkers = typeParameterMarkers(node.typeParameters, lens)
        defaultExportMarker(node.modifiers, headerMarkers)
        val members = mutableListOf<ExternalMember>()
        val staticMembers = mutableListOf<ExternalMember>()
        val heritage = collectHeritage(node.heritageClauses, lens, scope, members) { base, isExtends ->
            if (isExtends) base is ClassDeclaration else base is InterfaceDeclaration
        }
        if (heritage.extends.size > 1) {
            // Unreachable from well-formed TypeScript (one `extends` per
            // class); kept loud rather than picking one.
            members.add(SkippedMember("class with more than one extends base"))
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
                // (EXT.11a) A `this` parameter on a constructor is dropped
                // like a function's, with the marker among the members —
                // the constructor line has no marker list of its own.
                val declared = declaredParameters(constructors.single().parameters, lens, scope)
                declared.thisMarker?.let { members.add(SkippedMember("constructor $it")) }
                constructorParameters = declared.parameters
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
            val memberName = when (member) {
                is PropertyDeclaration -> member.name
                is MethodDeclaration -> member.name
                is GetAccessor -> member.name
                is SetAccessor -> member.name
                else -> null
            }
            if (memberName != null && isPrivateName(memberName)) continue
            val isStatic = ModifierFlag.Static in modifiers
            val target = if (isStatic) staticMembers else members
            val memberScope = if (isStatic) scopeOf(emptySet()) else scope
            when (member) {
                is PropertyDeclaration -> collectProperty(member, lens, target, memberScope)
                is MethodDeclaration -> collectMethod(member, lens, target, memberScope)
                is GetAccessor, is SetAccessor ->
                    collectAccessor(member, accessorSiblings(node.members, isStatic), lens, target, memberScope)
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
        dedupeOverloads(members)
        dedupeOverloads(staticMembers)
        return ExternalClass(
            name = name,
            typeParameters = typeParameters,
            headerMarkers = headerMarkers,
            isAbstract = ModifierFlag.Abstract in node.modifiers,
            superClass = heritage.extends.singleOrNull(),
            interfaces = heritage.implements,
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
        defaultExportMarker(node.modifiers, markers)
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
     *
     * (EXT.5) A GENERIC alias renders too — `type Handler<T> = (event: T) =>
     * void` is mitt's spine — with its body answered SYNTACTICALLY under the
     * alias's own type-parameter scope, for (EXT.2)'s measured reason: the
     * lens ambient carries a FUNCTION's type parameters, never a declaration's
     * own, so asking it about a bare `T` answers `any` silently. The
     * non-generic path keeps the RESOLVED body (the Dukat pin: uses render
     * what the checker knows).
     */
    private fun collectTypeAlias(
        node: TypeAliasDeclaration,
        lens: CheckedLens,
    ): ExternalDeclaration {
        val name = node.name.text
        val typeParameters = node.typeParameters.orEmpty().map { it.name.text }
        if (typeParameters.isNotEmpty()) {
            val markers = typeParameterMarkers(node.typeParameters, lens)
            val body = annotationTextOrNull(
                node.type,
                returnPosition = false,
                lens = lens,
                scope = scopeOf(typeParameters.toSet()),
            ) ?: return SkippedDeclaration("generic type alias $name with unmappable body")
            return ExternalTypeAlias(name, typeParameters, markers, body)
        }
        // (EXT.10) The body through the annotation path — the resolved type
        // where it maps (unchanged: `string` → `String`), and a FUNCTION type
        // syntactically where the resolved one has no Kotlin spelling, so
        // `type Cb = () => void` is a `typealias` its uses can name.
        val mapped = annotationTextOrNull(
            node.type,
            returnPosition = false,
            lens = lens,
            scope = scopeOf(emptySet()),
        ) ?: return SkippedDeclaration(
            "type alias $name with unmappable body " +
                commentSafe(lens.render(lens.typeOfTypeNode(node.type)))
        )
        return ExternalTypeAlias(name, emptyList(), emptyList(), mapped)
    }

    /**
     * (EXT.11a) The parser's spelling of an interface CALL SIGNATURE —
     * `(source: T): R;` is a [MethodDeclaration] whose name is the EMPTY
     * identifier (`Parser.kt`'s type-member arm) — and of a CONSTRUCT
     * SIGNATURE, `new (x: T): R;`, a [MethodDeclaration] named `new`. Both
     * spellings are unambiguous INSIDE an interface: an interface member
     * cannot be nameless otherwise, and `new` followed by `(` or `<` is a
     * construct signature by the parser's own lookahead (a PROPERTY named
     * `new` is a [PropertyDeclaration]). Neither test is applied to a CLASS
     * member, where `new(): void` is an ordinary method named `new`.
     */
    private fun isCallSignature(member: Node): Boolean =
        member is MethodDeclaration && (member.name as? Identifier)?.text == ""

    private fun isConstructSignature(member: Node): Boolean =
        member is MethodDeclaration && (member.name as? Identifier)?.text == "new"

    /**
     * (EXT.11a) The one call signature of a CALLABLE interface — an exported
     * interface whose members are EXACTLY one call signature (no other member,
     * no type parameters on the signature itself, no heritage) — or null.
     * `interface UnaryFunction<T, R> { (source: T): R }` is RxJS's spine, and
     * its Kotlin shape is a function TYPE, not an interface: the whole
     * `pipe(...)` surface is typed by it and a consumer passes lambdas.
     * Decided from SYNTAX, once, up front ([callableInterfaces]).
     */
    private fun soleCallSignatureOf(node: InterfaceDeclaration): MethodDeclaration? {
        if (!node.heritageClauses.isNullOrEmpty()) return null
        val member = node.members.singleOrNull() as? MethodDeclaration ?: return null
        if (!isCallSignature(member)) return null
        if (member.typeParameters != null) return null
        return member
    }

    /**
     * (EXT.11a) The base an EMPTY-bodied interface with exactly one `extends`
     * base spells, resolved BY NAME over the exported interfaces (first wins,
     * the same rule [finish] applies to a name two files export) — the
     * syntactic half of the chain test; the identity half is asked of the
     * lens at the callback ([collectCallableChain]).
     */
    private fun soleExtendsBaseOf(node: InterfaceDeclaration): InterfaceDeclaration? {
        if (node.members.isNotEmpty()) return null
        val clause = node.heritageClauses?.singleOrNull() ?: return null
        if (clause.token != SyntaxKind.ExtendsKeyword) return null
        val base = clause.types.singleOrNull() ?: return null
        val baseName = (base.expression as? Identifier)?.text ?: return null
        return exportedInterfaces.firstOrNull { it.name.text == baseName }
    }

    /**
     * (EXT.11a) The exported interfaces that render as a `typealias` to a
     * FUNCTION type rather than as an interface: those with exactly one call
     * signature ([soleCallSignatureOf]) and, transitively, every EMPTY-bodied
     * interface whose only `extends` base is one of them —
     * `MonoTypeOperatorFunction<T> extends OperatorFunction<T, T> {}` over
     * `OperatorFunction<T, R> extends UnaryFunction<Observable<T>,
     * Observable<R>> {}` over `UnaryFunction`. An empty interface over one
     * base declares nothing of its own; Kotlin lets a `typealias` name a
     * parameterised `typealias`, which is exactly the chain.
     *
     * Membership is decided from SYNTAX because the set is consulted at every
     * callback (a use may be walked before its target's declaration is) and
     * because a callable interface must NEVER be offered as a Kotlin
     * SUPERTYPE — a function type has no subtypes to declare — so
     * [collectHeritage] refuses a base in this set with the usual marker,
     * whatever the class or the non-empty interface naming it does.
     *
     * The transitive step resolves the base by NAME ([soleExtendsBaseOf]); a
     * cross-file name collision makes that guess wrong in the direction the
     * callback catches — the lens's identity test there falls the interface
     * back to the ordinary path, where its heritage is marked, never to a
     * silently wrong alias.
     */
    private val callableInterfaces: List<InterfaceDeclaration> = run {
        val callable = exportedInterfaces.filter { soleCallSignatureOf(it) != null }.toMutableList()
        while (true) {
            val added = exportedInterfaces.filter { candidate ->
                callable.none { it === candidate } &&
                    soleExtendsBaseOf(candidate)?.let { base -> callable.any { it === base } } == true
            }
            if (added.isEmpty()) break
            callable += added
        }
        callable
    }

    private fun isCallableInterface(declaration: Node): Boolean =
        callableInterfaces.any { it === declaration }

    /**
     * (EXT.11a) A callable interface as `public typealias Name<TPs> =
     * (P1, P2) -> R`, its signature rendered SYNTACTICALLY under the
     * interface's own type-parameter scope (the generic-alias path, for
     * (EXT.5)'s measured reason: the lens ambient carries no declaration's
     * own parameters). An unmappable piece refuses the WHOLE declaration,
     * loudly — `typealias U<T, R> = Any?` would flatten every `pipe`
     * signature that names it, silently.
     */
    private fun collectCallableInterface(
        node: InterfaceDeclaration,
        signature: MethodDeclaration,
        lens: CheckedLens,
    ): ExternalDeclaration {
        val name = node.name.text
        val typeParameters = node.typeParameters.orEmpty().map { it.name.text }
        val markers = typeParameterMarkers(node.typeParameters, lens)
        defaultExportMarker(node.modifiers, markers)
        val body = functionTypeText(
            signature.parameters,
            signature.type,
            lens,
            scopeOf(typeParameters.toSet()),
        ) ?: return SkippedDeclaration("callable interface $name with unmappable signature")
        return ExternalTypeAlias(name, typeParameters, markers, body)
    }

    /**
     * (EXT.11a) An empty-bodied link of a callable chain as `public typealias
     * Name<TPs> = Base<args>`, the arguments by the heritage rule (their own
     * annotations under the interface's scope). Null where the lens does not
     * confirm the syntactic guess — the base resolves, import alias followed,
     * to a declaration OTHER than the exported interface [soleExtendsBaseOf]
     * picked by name — so the caller takes the ordinary interface path and
     * the heritage is marked there.
     */
    private fun collectCallableChain(
        node: InterfaceDeclaration,
        expectedBase: InterfaceDeclaration,
        lens: CheckedLens,
    ): ExternalDeclaration? {
        val base = node.heritageClauses!!.single().types.single()
        val symbol = lens.heritageBaseSymbol(base.expression)
            ?.let { lens.aliasTarget(it) ?: it }
            ?: return null
        if (symbol.declarations.none { it === expectedBase }) return null
        val name = node.name.text
        val typeParameters = node.typeParameters.orEmpty().map { it.name.text }
        val scope = scopeOf(typeParameters.toSet())
        val markers = typeParameterMarkers(node.typeParameters, lens)
        defaultExportMarker(node.modifiers, markers)
        val arguments = base.typeArguments.orEmpty().map { argument ->
            annotationTextOrNull(argument, returnPosition = false, lens = lens, scope = scope)
                ?: return SkippedDeclaration(
                    "callable interface $name with unmappable base ${expectedBase.name.text}"
                )
        }
        val body =
            if (arguments.isEmpty()) kotlinIdentifier(expectedBase.name.text)
            else "${kotlinIdentifier(expectedBase.name.text)}<${arguments.joinToString(", ")}>"
        return ExternalTypeAlias(name, typeParameters, markers, body)
    }

    private fun collectInterface(
        node: InterfaceDeclaration,
        lens: CheckedLens,
    ): ExternalDeclaration {
        // (EXT.11a) A callable interface is a function type; a link of a
        // callable chain is an alias to one — unless the lens refutes the
        // syntactic chain guess, in which case the interface path below marks
        // the heritage as it always did.
        if (isCallableInterface(node)) {
            soleCallSignatureOf(node)?.let { return collectCallableInterface(node, it, lens) }
            soleExtendsBaseOf(node)?.let { base ->
                collectCallableChain(node, base, lens)?.let { return it }
            }
        }
        // (EXT.2) A generic interface renders its type-parameter NAMES; what
        // Kotlin externals cannot carry — a constraint, a default — becomes a
        // loud header marker, never a silent widening.
        val typeParameters = node.typeParameters.orEmpty().map { it.name.text }
        val scope = scopeOf(typeParameters.toSet())
        val headerMarkers = typeParameterMarkers(node.typeParameters, lens)
        defaultExportMarker(node.modifiers, headerMarkers)
        val members = mutableListOf<ExternalMember>()
        // An interface may `extends` a generated interface; a generated CLASS
        // as an interface's base has no Kotlin shape (an interface cannot
        // extend a class) and stays a marker.
        val heritage = collectHeritage(node.heritageClauses, lens, scope, members) { base, _ ->
            base is InterfaceDeclaration
        }
        for (member in node.members) {
            when (member) {
                is PropertyDeclaration -> collectProperty(member, lens, members, scope)
                // (EXT.11a) A call or construct signature among OTHER members
                // has no Kotlin member shape (an interface cannot be invoked
                // or constructed) and is a loud skip — before this it rendered
                // `fun ``(...)`, a compile error, or a method named `new`.
                is MethodDeclaration -> when {
                    isCallSignature(member) -> members.add(SkippedMember("call signature"))
                    isConstructSignature(member) -> members.add(SkippedMember("construct signature"))
                    else -> collectMethod(member, lens, members, scope)
                }
                is GetAccessor, is SetAccessor ->
                    collectAccessor(member, node.members, lens, members, scope)
                // A stray `;` between members is pure syntax — there is
                // nothing to generate and nothing to mark.
                is SemicolonClassElement -> {}
                else -> members.add(
                    SkippedMember(member::class.simpleName ?: "member")
                )
            }
        }
        dedupeOverloads(members)
        return ExternalInterface(node.name.text, typeParameters, headerMarkers, heritage.extends, members)
    }

    /** (EXT.8) The bases a declaration's heritage clauses resolve to. */
    private class Heritage(
        /** `extends` bases that are GENERATED classes/interfaces, as Kotlin type text. */
        val extends: List<String>,
        /** `implements` bases that are GENERATED interfaces, as Kotlin type text. */
        val implements: List<String>,
    )

    /**
     * (EXT.8) A heritage base renders as a Kotlin supertype when it is a
     * GENERATED target — the base name resolves (`lens.resolveName`, the
     * walk-scoped scope at the declaration) to a symbol whose declaration IS
     * one of the pre-scanned exported interfaces/classes, by `===` — and, for
     * a generic base, when EVERY type argument maps (the (EXT.6) reference
     * rule: arguments from their own annotations). Anything else is what it
     * always was: a loud marker naming the base a consumer's Kotlin will not
     * see — a lib type (`extends Date`, `extends Error`), a non-exported
     * neighbour, an enum, an unmappable argument. [kindOk] refuses a base of
     * the wrong KIND for the Kotlin shape (an interface cannot extend a
     * generated CLASS, a class cannot `extends` an interface — TypeScript
     * refuses the latter too), so the marker is per BASE, never per clause.
     */
    private fun collectHeritage(
        clauses: List<HeritageClause>?,
        lens: CheckedLens,
        scope: TypeScope,
        members: MutableList<ExternalMember>,
        kindOk: (base: Node, isExtends: Boolean) -> Boolean,
    ): Heritage {
        val extends = mutableListOf<String>()
        val implements = mutableListOf<String>()
        for (clause in clauses.orEmpty()) {
            val isExtends = clause.token == SyntaxKind.ExtendsKeyword
            val keyword = clause.token.name.removeSuffix("Keyword").lowercase()
            for (base in clause.types) {
                val baseName = (base.expression as? Identifier)?.text
                val rendered = baseName?.let { _ ->
                    // Resolved as the checker resolves the clause itself (the
                    // lexical `resolveName` offers no import); an imported base
                    // is its import ALIAS, and the identity test needs the
                    // declaration it names.
                    val symbol = lens.heritageBaseSymbol(base.expression)
                        ?.let { lens.aliasTarget(it) ?: it }
                        ?: return@let null
                    val declaration = symbol.declarations.firstOrNull { declared ->
                        nameableDeclarations.any { it === declared }
                    } ?: return@let null
                    if (!kindOk(declaration, isExtends)) return@let null
                    // (EXT.11a) A callable interface renders as a function
                    // TYPE alias, which nothing can extend or implement.
                    if (isCallableInterface(declaration)) return@let null
                    val arguments = base.typeArguments.orEmpty().map { argument ->
                        annotationTextOrNull(
                            argument,
                            returnPosition = false,
                            lens = lens,
                            scope = scope,
                        ) ?: return@let null
                    }
                    if (arguments.isEmpty()) kotlinIdentifier(symbol.name)
                    else "${kotlinIdentifier(symbol.name)}<${arguments.joinToString(", ")}>"
                }
                when {
                    rendered == null ->
                        members.add(
                            SkippedMember("heritage clause $keyword ${baseName ?: "a base expression"}")
                        )
                    isExtends -> extends.add(rendered)
                    else -> implements.add(rendered)
                }
            }
        }
        return Heritage(extends, implements)
    }

    /**
     * (EXT.7) An ECMAScript PRIVATE name (`#private`) — a class-private
     * member, which is not part of any consumable surface, exactly like a
     * `private`-modified one: omitted without a marker.
     */
    private fun isPrivateName(name: Node): Boolean =
        name is Identifier && name.text.startsWith("#")

    /**
     * (EXT.9) The accessors a class accessor may pair with: the same static
     * side, and neither `private`/`protected` nor `#`-named (those were
     * skipped above and must not become a silent partner).
     */
    private fun accessorSiblings(members: List<Node>, isStatic: Boolean): List<Node> =
        members.filter { member ->
            val modifiers = when (member) {
                is GetAccessor -> member.modifiers
                is SetAccessor -> member.modifiers
                else -> return@filter false
            }
            val name = when (member) {
                is GetAccessor -> member.name
                is SetAccessor -> member.name
            }
            (ModifierFlag.Static in modifiers) == isStatic &&
                ModifierFlag.Private !in modifiers && ModifierFlag.Protected !in modifiers &&
                !isPrivateName(name)
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
        // (EXT.5) A generic METHOD renders its own type-parameter names
        // syntactically, exactly as a top-level function does, with the same
        // constraint/default markers; the member scope is the enclosing
        // declaration's parameters PLUS the method's own.
        val methodTypeParameters = member.typeParameters.orEmpty().map { it.name.text }
        val methodScope =
            if (methodTypeParameters.isEmpty()) scope
            else TypeScope(scope.ownTypeParams + methodTypeParameters, scope.generatedNameOf)
        // (EXT.11a) A method's `this` parameter is dropped with a marker, as
        // a function's is — for a method the receiver is the declaring
        // object anyway, and the constraint is a TypeScript-only fact.
        val declared = declaredParameters(member.parameters, lens, methodScope)
        if (member.questionToken) {
            if (methodTypeParameters.isNotEmpty()) {
                // A nullable function-typed PROPERTY cannot carry type
                // parameters, so an optional GENERIC method has no shape here.
                members.add(SkippedMember("optional generic method $name"))
                return
            }
            declared.thisMarker?.let { members.add(SkippedMember("$it - optional method $name")) }
            // (EXT.3) An optional METHOD is a nullable function-typed
            // property: `m?(x: string): void` -> `var m: ((String) -> Unit)?`.
            // Refused to the marker when any piece does not map — a
            // half-translated signature is the silent direction.
            val parameterTypes = declared.parameters
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
        val parameters = declared.parameters
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
                        scope = methodScope,
                    ),
                )
            }
        val markers = typeParameterMarkers(member.typeParameters, lens)
        declared.thisMarker?.let { markers.add(it) }
        members.add(
            ExternalFunction(
                name = name,
                typeParameters = methodTypeParameters,
                markers = markers,
                parameters = parameters,
                returnType = annotationText(
                    member.type,
                    optional = false,
                    returnPosition = true,
                    lens = lens,
                    scope = methodScope,
                ),
            )
        )
    }

}
