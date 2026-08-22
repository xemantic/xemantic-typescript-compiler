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

package com.xemantic.typescript.compiler.kir.api

import com.xemantic.typescript.compiler.ClassDeclaration
import com.xemantic.typescript.compiler.Constructor
import com.xemantic.typescript.compiler.EnumDeclaration
import com.xemantic.typescript.compiler.ExportAssignment
import com.xemantic.typescript.compiler.ExportDeclaration
import com.xemantic.typescript.compiler.FunctionDeclaration
import com.xemantic.typescript.compiler.GetAccessor
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.ImportDeclaration
import com.xemantic.typescript.compiler.InterfaceDeclaration
import com.xemantic.typescript.compiler.MethodDeclaration
import com.xemantic.typescript.compiler.ModifierFlag
import com.xemantic.typescript.compiler.NamedExports
import com.xemantic.typescript.compiler.NamedImports
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NumericLiteralNode
import com.xemantic.typescript.compiler.ObjectLiteralExpression
import com.xemantic.typescript.compiler.Parameter
import com.xemantic.typescript.compiler.PrefixUnaryExpression
import com.xemantic.typescript.compiler.PropertyDeclaration
import com.xemantic.typescript.compiler.SetAccessor
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.StringLiteralNode
import com.xemantic.typescript.compiler.SyntaxKind
import com.xemantic.typescript.compiler.TypeAliasDeclaration
import com.xemantic.typescript.compiler.TypeLiteral
import com.xemantic.typescript.compiler.VariableDeclaration
import com.xemantic.typescript.compiler.VariableDeclarationList
import com.xemantic.typescript.compiler.VariableStatement
import com.xemantic.typescript.compiler.kir.KirDiagnostic
import com.xemantic.typescript.compiler.kir.front.CheckedFacts
import com.xemantic.typescript.compiler.kir.lineAndColumnAt

/** A module's exported surface, plus what could not be put on it. */
internal class ExtractedApi(
    val module: KotlinApiModule,
    val refusals: List<KirDiagnostic>,
)

/**
 * One TypeScript module's PUBLIC API, as Kotlin declarations.
 *
 * "Public" is the module's own export surface, followed through re-exports —
 * `export { x } from "./x"` and `export * from "./x"` — and not the union of
 * everything every file in the project marks `export`. A library's `index.ts`
 * is the statement of what it offers, and the difference is not cosmetic: the
 * union exposes names the package deliberately keeps internal.
 *
 * REFUSALS ARE PER DECLARATION, which is the one place this differs in spirit
 * from the IR lowering. There, one unlowerable construct aborts the program,
 * because a program missing a statement is not that program. Here a declaration
 * that cannot be expressed is OMITTED and reported: an absent declaration is a
 * compile error at the consumer's use site, i.e. loud, where a wrongly-typed
 * one would be silent. `docs/kir-kotlin-metadata.md` §4.
 */
internal class TypeScriptApiExtractor(
    private val files: List<SourceFile>,
    private val facts: CheckedFacts,
    private val packageName: String,
    /**
     * Whether the runtime's metadata klib is on the export's classpath, which
     * is what decides between `JsObject`/`JsArray` and `Any?` — see
     * [ApiErasure].
     */
    private val runtimeTypes: Boolean = false,
) {

    private val refusals = mutableListOf<KirDiagnostic>()

    /** TypeScript class declarations reachable on the surface, by node. */
    private val exportedClasses = HashMap<Node, String>()

    private val enumValueTypes = HashMap<Node, KotlinType>()

    private val erasure = ApiErasure(
        classNameOf = { node -> exportedClasses[node] },
        enumValueTypeOf = { node -> enumValueTypes[node] },
        isOwnStructuralDeclaration = ::isOwnStructuralDeclaration,
        runtimeTypes = runtimeTypes,
    )

    /**
     * `KirFileLowering`'s gate of the same name, over the same population.
     *
     * The kinds are the structural ones — an interface, a type alias, a type
     * literal, an object literal — and the file test is the lowering's
     * `isProgramNode`, minus the declaration files: `KirProgramLowering` drops
     * every `.d.ts` before it builds its tables, so a type declared in one is
     * not a shape this program builds and must not be typed as a bag.
     */
    private fun isOwnStructuralDeclaration(declaration: Node): Boolean {
        val structural = declaration is InterfaceDeclaration ||
            declaration is TypeAliasDeclaration ||
            declaration is TypeLiteral ||
            declaration is ObjectLiteralExpression
        return structural && isProgramNode(declaration)
    }

    private fun isProgramNode(node: Node): Boolean {
        var current: Node? = node
        while (current != null) {
            if (current is SourceFile) {
                return files.any { it === current && !it.fileName.endsWith(".d.ts") }
            }
            current = (current as? NodeBase)?.parent
        }
        return false
    }

    fun extract(entry: SourceFile): ExtractedApi {
        val surface = surfaceOf(entry, mutableSetOf())
        // Class and enum names must be known BEFORE any signature is mapped:
        // a function returning an exported class has to erase to that class's
        // name, and a mapper cannot ask a table that is still being filled.
        surface.forEach { (name, export) ->
            when (val declaration = export.declaration) {
                is ClassDeclaration ->
                    exportedClasses[declaration] = "$packageName.${kotlinName(name)}"
                is EnumDeclaration -> enumValueType(declaration)?.let {
                    enumValueTypes[declaration] = it
                }
                else -> {}
            }
        }
        val declarations = surface.mapNotNull { (name, export) -> declare(name, export) }
        return ExtractedApi(KotlinApiModule(packageName, declarations), refusals.toList())
    }

    // -----------------------------------------------------------------------
    // The export surface
    // -----------------------------------------------------------------------

    private class Export(val declaration: Node, val file: SourceFile)

    /**
     * Every name [file] exports, in source order, following re-exports.
     *
     * [visiting] breaks a re-export cycle — two modules re-exporting each other
     * is legal TypeScript and would otherwise not terminate. FIRST WINS on a
     * duplicate name, which is what a `export *` shadowed by a local export
     * means.
     */
    private fun surfaceOf(
        file: SourceFile,
        visiting: MutableSet<String>,
    ): LinkedHashMap<String, Export> {
        val surface = LinkedHashMap<String, Export>()
        if (!visiting.add(file.fileName)) return surface
        file.statements.forEach { statement ->
            when (statement) {
                is FunctionDeclaration ->
                    if (ModifierFlag.Export in statement.modifiers) {
                        exportNamed(surface, statement.name, statement, file, statement)
                    }
                is ClassDeclaration ->
                    if (ModifierFlag.Export in statement.modifiers) {
                        exportNamed(surface, statement.name, statement, file, statement)
                    }
                is EnumDeclaration ->
                    if (ModifierFlag.Export in statement.modifiers) {
                        exportNamed(surface, statement.name, statement, file, statement)
                    }
                is VariableStatement ->
                    if (ModifierFlag.Export in statement.modifiers) {
                        statement.declarationList.declarations.forEach { declaration ->
                            exportNamed(
                                surface,
                                declaration.name as? Identifier,
                                declaration,
                                file,
                                statement,
                            )
                        }
                    }
                is ExportDeclaration -> exportDeclaration(surface, statement, file, visiting)
                is ExportAssignment -> exportAssignment(surface, statement, file)
                else -> {}
            }
        }
        visiting.remove(file.fileName)
        return surface
    }

    private fun exportNamed(
        surface: MutableMap<String, Export>,
        name: Identifier?,
        declaration: Node,
        file: SourceFile,
        at: Node,
    ) {
        if (name == null) {
            refuse(file, at, "cannot export an anonymous declaration; give it a name")
            return
        }
        surface.putIfAbsent(name.text, Export(declaration, file))
    }

    private fun exportDeclaration(
        surface: MutableMap<String, Export>,
        statement: ExportDeclaration,
        file: SourceFile,
        visiting: MutableSet<String>,
    ) {
        if (statement.isTypeOnly) return
        val specifier = (statement.moduleSpecifier as? StringLiteralNode)?.text
        val clause = statement.exportClause
        if (clause == null) {
            // `export * from "./x"` — everything that module exports.
            val target = specifier?.let { resolveModule(it, file) } ?: run {
                refuse(file, statement, "cannot resolve the re-exported module")
                return
            }
            surfaceOf(target, visiting).forEach { (name, export) ->
                surface.putIfAbsent(name, export)
            }
            return
        }
        if (clause !is NamedExports) {
            refuse(file, statement, "cannot export a namespace re-export yet")
            return
        }
        val source = if (specifier == null) null else resolveModule(specifier, file)
        if (specifier != null && source == null) {
            refuse(file, statement, "cannot resolve module '$specifier'")
            return
        }
        clause.elements.forEach { element ->
            if (element.isTypeOnly) return@forEach
            val original = (element.propertyName ?: element.name).text
            val export = if (source != null) {
                surfaceOf(source, visiting)[original]
            } else {
                localDeclaration(file, original, visiting)
            }
            if (export == null) {
                refuse(file, element, "cannot resolve exported name '$original'")
            } else {
                surface.putIfAbsent(element.name.text, export)
            }
        }
    }

    /**
     * `export default <name>`, exported under the NAME it already has.
     *
     * Kotlin has no anonymous top-level declaration, so a default export must
     * be something with a name to reuse — which every real library's default
     * export is, because its own sources have to refer to it.
     */
    private fun exportAssignment(
        surface: MutableMap<String, Export>,
        statement: ExportAssignment,
        file: SourceFile,
    ) {
        val name = statement.expression as? Identifier ?: run {
            refuse(file, statement, "cannot export a default that is not a declared name")
            return
        }
        val export = localDeclaration(file, name.text, mutableSetOf())
        if (export == null) {
            refuse(file, statement, "cannot resolve default export '${name.text}'")
        } else {
            surface.putIfAbsent(name.text, export)
        }
    }

    /**
     * A name declared in [file], or IMPORTED into it — both are what
     * `export { x }` may be naming.
     */
    private fun localDeclaration(
        file: SourceFile,
        name: String,
        visiting: MutableSet<String>,
    ): Export? {
        file.statements.forEach { statement ->
            when (statement) {
                is FunctionDeclaration ->
                    if (statement.name?.text == name) return Export(statement, file)
                is ClassDeclaration ->
                    if (statement.name?.text == name) return Export(statement, file)
                is EnumDeclaration ->
                    if (statement.name.text == name) return Export(statement, file)
                is VariableStatement -> statement.declarationList.declarations.forEach {
                    if ((it.name as? Identifier)?.text == name) return Export(it, file)
                }
                is ImportDeclaration -> importedDeclaration(statement, file, name, visiting)
                    ?.let { return it }
                else -> {}
            }
        }
        return null
    }

    private fun importedDeclaration(
        statement: ImportDeclaration,
        file: SourceFile,
        name: String,
        visiting: MutableSet<String>,
    ): Export? {
        val clause = statement.importClause ?: return null
        val specifier = (statement.moduleSpecifier as? StringLiteralNode)?.text ?: return null
        val bindings = clause.namedBindings as? NamedImports ?: return null
        val element = bindings.elements.firstOrNull { it.name.text == name } ?: return null
        val target = resolveModule(specifier, file) ?: return null
        val original = (element.propertyName ?: element.name).text
        return surfaceOf(target, visiting)[original]
            ?: localDeclaration(target, original, visiting)
    }

    /**
     * A relative module specifier → the program file it names.
     *
     * Deliberately RELATIVE-only and deliberately not a second module resolver:
     * `-core`'s `ModuleResolver` is the compiler's, and re-deriving its search
     * order here is exactly the kind of second copy that drifts. What this
     * needs is narrower — the file is already in the checked program, so the
     * question is which of a known list it is, and a path match answers it.
     * A bare specifier (a node_modules package) answers null and is refused.
     */
    private fun resolveModule(specifier: String, from: SourceFile): SourceFile? {
        if (!specifier.startsWith(".")) return null
        val directory = from.fileName.substringBeforeLast('/', "")
        val joined = normalize("$directory/$specifier")
        val base = joined.removeSuffix(".js").removeSuffix(".ts")
        val candidates = listOf("$base.ts", "$base.tsx", "$base/index.ts", "$base/index.tsx", base)
        return candidates.firstNotNullOfOrNull { candidate ->
            files.firstOrNull { it.fileName == candidate }
        }
    }

    /** Resolves `.` and `..` segments; the paths here are always `/`-separated. */
    private fun normalize(path: String): String {
        val segments = mutableListOf<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments.add(segment)
            }
        }
        return (if (path.startsWith("/")) "/" else "") + segments.joinToString("/")
    }

    // -----------------------------------------------------------------------
    // Declaration mapping
    // -----------------------------------------------------------------------

    private fun declare(name: String, export: Export): KotlinDeclaration? {
        val file = export.file
        return when (val declaration = export.declaration) {
            is FunctionDeclaration -> function(name, declaration, file)
            is VariableDeclaration -> variable(name, declaration, file)
            is ClassDeclaration -> classDeclaration(name, declaration, file)
            is EnumDeclaration -> enumDeclaration(name, declaration, file)
            else -> {
                refuse(file, declaration, "cannot export a ${declaration.kind} yet")
                null
            }
        }
    }

    private fun function(
        name: String,
        declaration: FunctionDeclaration,
        file: SourceFile,
    ): KotlinFunction? {
        val returnType = facts.signatureOf(declaration)?.resolvedReturnType ?: run {
            refuse(file, declaration, "the checker gave no return type for '$name'")
            return null
        }
        val parameters = parameters(declaration.parameters, file) ?: return null
        return KotlinFunction(
            kotlinName(name),
            parameters,
            erasure.map(returnType),
            origin(file, declaration),
        )
    }

    private fun variable(
        name: String,
        declaration: VariableDeclaration,
        file: SourceFile,
    ): KotlinProperty? {
        val nameNode = declaration.name as? Identifier ?: run {
            refuse(file, declaration, "cannot export a destructuring declaration")
            return null
        }
        val type = facts.typeOf(nameNode) ?: run {
            refuse(file, declaration, "the checker gave no type for '$name'")
            return null
        }
        val list = declaration.parent as? VariableDeclarationList
        val mutable = list != null && list.flags != SyntaxKind.ConstKeyword
        return KotlinProperty(kotlinName(name), erasure.map(type), mutable, origin(file, declaration))
    }

    private fun classDeclaration(
        name: String,
        declaration: ClassDeclaration,
        file: SourceFile,
    ): KotlinClass? {
        val constructor = declaration.members.filterIsInstance<Constructor>().firstOrNull()
        val constructorParameters = when {
            constructor == null -> emptyList()
            ModifierFlag.Private in constructor.modifiers -> null
            else -> parameters(constructor.parameters, file) ?: return null
        }
        val members = declaration.members.mapNotNull { member ->
            when (member) {
                is MethodDeclaration -> method(member, file)
                is PropertyDeclaration -> property(member, file)
                is GetAccessor -> accessor(member, declaration, file)
                else -> null
            }
        }
        return KotlinClass(kotlinName(name), constructorParameters, members, origin(file, declaration))
    }

    private fun method(member: MethodDeclaration, file: SourceFile): KotlinFunction? {
        if (!isPublicInstanceMember(member.modifiers)) return null
        val name = (member.name as? Identifier)?.text ?: return null
        val returnType = facts.signatureOf(member)?.resolvedReturnType ?: run {
            refuse(file, member, "the checker gave no return type for method '$name'")
            return null
        }
        val parameters = parameters(member.parameters, file) ?: return null
        return KotlinFunction(
            kotlinName(name),
            parameters,
            erasure.map(returnType),
            origin(file, member),
        )
    }

    private fun property(member: PropertyDeclaration, file: SourceFile): KotlinProperty? {
        if (!isPublicInstanceMember(member.modifiers)) return null
        val name = (member.name as? Identifier)?.text ?: return null
        val type = facts.memberTypeOf(member) ?: run {
            refuse(file, member, "the checker gave no type for property '$name'")
            return null
        }
        val mutable = ModifierFlag.Readonly !in member.modifiers
        return KotlinProperty(kotlinName(name), erasure.map(type), mutable, origin(file, member))
    }

    private fun accessor(
        member: GetAccessor,
        owner: ClassDeclaration,
        file: SourceFile,
    ): KotlinProperty? {
        if (!isPublicInstanceMember(member.modifiers)) return null
        val name = (member.name as? Identifier)?.text ?: return null
        val type = facts.memberTypeOf(member) ?: run {
            refuse(file, member, "the checker gave no type for accessor '$name'")
            return null
        }
        // A `var` only where the pair is complete: a getter alone is a `val`,
        // and Kotlin has no set-only property to mirror the other half with.
        val mutable = owner.members.any {
            it is SetAccessor && (it.name as? Identifier)?.text == name
        }
        return KotlinProperty(kotlinName(name), erasure.map(type), mutable, origin(file, member))
    }

    private fun isPublicInstanceMember(modifiers: Set<ModifierFlag>): Boolean =
        ModifierFlag.Private !in modifiers &&
            ModifierFlag.Protected !in modifiers &&
            // A static member would need a companion object, and the JVM half
            // it must agree with does not generate one yet.
            ModifierFlag.Static !in modifiers

    private fun parameters(
        parameters: List<Parameter>,
        file: SourceFile,
    ): List<KotlinParameter>? {
        val mapped = mutableListOf<KotlinParameter>()
        parameters.forEachIndexed { index, parameter ->
            val name = (parameter.name as? Identifier)?.text
            // A `this` parameter is TypeScript's way of typing the receiver; it
            // is not an argument and no call ever passes one.
            if (name == "this") return@forEachIndexed
            if (parameter.dotDotDotToken) {
                refuse(file, parameter, "cannot export a rest parameter yet")
                return null
            }
            val type = facts.typeOf(parameter) ?: run {
                refuse(file, parameter, "the checker gave no type for parameter ${index + 1}")
                return null
            }
            val optional = parameter.questionToken || parameter.initializer != null
            val erased = erasure.map(type).let { if (optional) it.asNullable() else it }
            mapped.add(KotlinParameter(kotlinName(name ?: "arg${index + 1}"), erased))
        }
        return mapped
    }

    private fun enumDeclaration(
        name: String,
        declaration: EnumDeclaration,
        file: SourceFile,
    ): KotlinConstantObject? {
        val valueType = enumValueTypes[declaration] ?: run {
            refuse(file, declaration, "cannot export enum '$name': its members' values disagree")
            return null
        }
        val members = declaration.members.mapNotNull { member ->
            (member.name as? Identifier)?.text?.let {
                KotlinProperty(kotlinName(it), valueType, mutable = false, origin(file, member))
            }
        }
        return KotlinConstantObject(kotlinName(name), members, origin(file, declaration))
    }

    /**
     * What an enum's members are typed as, or null when they disagree.
     *
     * Read off the AST rather than off a [Type], because an enum member's type
     * is the enum member itself — CLAUDE.md records that a member access is
     * replaced by its constant here — so the question "what does a slot of this
     * enum type hold" is answered by the initializers and nowhere else.
     */
    private fun enumValueType(declaration: EnumDeclaration): KotlinType? {
        var strings = 0
        var numbers = 0
        declaration.members.forEach { member ->
            when (val initializer = member.initializer) {
                null -> numbers++
                is StringLiteralNode -> strings++
                is NumericLiteralNode -> numbers++
                is PrefixUnaryExpression ->
                    if (initializer.operand is NumericLiteralNode) numbers++ else return null
                else -> return null
            }
        }
        return when {
            strings > 0 && numbers > 0 -> null
            strings > 0 -> KotlinType.STRING
            else -> KotlinType.DOUBLE
        }
    }

    /**
     * A TypeScript name that Kotlin cannot spell, made spellable.
     *
     * Only the keyword collision is handled, with a backtick quotation, because
     * a TypeScript identifier is otherwise a legal Kotlin one — and `is`, `in`,
     * `object`, `when` and `val` are all ordinary TypeScript names, so this is
     * not a hypothetical. Applied to every name that becomes Kotlin, because
     * the alternative is a generated source that does not parse, i.e. a whole
     * library refused for one member.
     */
    private fun kotlinName(name: String): String =
        if (name in KOTLIN_KEYWORDS) "`$name`" else name

    private fun origin(file: SourceFile, node: Node): String {
        val (line, column) = lineAndColumnAt(file.text, node.pos)
        return "${file.fileName}:$line:$column"
    }

    private fun refuse(file: SourceFile, node: Node, message: String) {
        val (line, column) = lineAndColumnAt(file.text, node.pos)
        refusals.add(KirDiagnostic(message, file.fileName, line, column))
    }

    private companion object {
        val KOTLIN_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
            "in", "interface", "is", "null", "object", "package", "return", "super", "this",
            "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
        )
    }

}
