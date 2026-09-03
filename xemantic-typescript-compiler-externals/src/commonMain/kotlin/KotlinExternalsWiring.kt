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

import com.xemantic.typescript.compiler.ClassDeclaration
import com.xemantic.typescript.compiler.EnumDeclaration
import com.xemantic.typescript.compiler.ExportAssignment
import com.xemantic.typescript.compiler.ExportDeclaration
import com.xemantic.typescript.compiler.Expression
import com.xemantic.typescript.compiler.ExternalModuleReference
import com.xemantic.typescript.compiler.FunctionDeclaration
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.ImportClause
import com.xemantic.typescript.compiler.ImportDeclaration
import com.xemantic.typescript.compiler.ImportEqualsDeclaration
import com.xemantic.typescript.compiler.InterfaceDeclaration
import com.xemantic.typescript.compiler.ModifierFlag
import com.xemantic.typescript.compiler.ModuleDeclaration
import com.xemantic.typescript.compiler.NamedExports
import com.xemantic.typescript.compiler.NamedImports
import com.xemantic.typescript.compiler.NamespaceExport
import com.xemantic.typescript.compiler.NamespaceImport
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.PropertyAccessExpression
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.Statement
import com.xemantic.typescript.compiler.StringLiteralNode
import com.xemantic.typescript.compiler.TypeAliasDeclaration
import com.xemantic.typescript.compiler.VariableStatement

/**
 * (EXT.16) The npm MODULE a generation is wired to: the package's name (what a
 * consumer's `import … from "rxjs"` spells, and so what `@file:JsModule`
 * names) and the ENTRY declaration file — the package's `types` entry, the
 * file whose export graph defines the PUBLIC SURFACE. `null` in place of a
 * wiring is the global-script mode every earlier rung rendered: no file
 * header, no JS annotations, every re-export a loud marker.
 *
 * ## What Kotlin/JS needs, and what this generation renders
 *
 * The facts are the Kotlin reference's ("Calling JavaScript from Kotlin"):
 *
 *  - `@file:JsModule("name")` applies to every top-level `external`
 *    declaration of the file — each binds the module member of ITS OWN
 *    Kotlin name — so the header is one line and a declaration reachable
 *    under its own name needs nothing more;
 *  - `@JsName("x")` renames ONE declaration's JavaScript binding — a
 *    declaration reachable under a DIFFERENT name than its Kotlin one
 *    (`export { impl as api }`, an `export default`, which binds the member
 *    `default`) carries it on the line above, and so does a Kotlin name
 *    that had to be backticked, spelled as JavaScript spells it;
 *  - `@file:JsNonModule` marks a module also usable as a global — the UMD
 *    shape an `export as namespace X` in the entry declares — and is
 *    rendered after the `JsModule` line;
 *  - `@file:JsQualifier("a.b")` qualifies a whole FILE's declarations, so a
 *    NESTED export (`export * as ns from`, a namespace exported as a
 *    member) cannot be expressed on a module member beside the unqualified
 *    ones: such a declaration is a loud marker, never a wrong binding;
 *  - an `export =` CommonJS module IS the exported object: a consumer binds
 *    it as `@JsModule("m") external val X` / `external fun X` / `external
 *    class X` with NO `@JsName` — the whole module, not a member of it. So
 *    the `export =` target renders with no annotation and a marker saying
 *    exactly that; `@JsName("default")` there would be WRONG for CommonJS
 *    (there is no `default` member), which is why the two forms differ. The
 *    one `export =` shape that binds cleanly under the file header is a
 *    NAMESPACE (`declare namespace ts { … } export = ts`, the
 *    `typescript.d.ts` shape): the module object's members ARE the
 *    flattened root's, each under its own name.
 *
 * ## Which declarations are wired
 *
 * VALUE-bearing ones — a function, a class, an enum object, a value, a
 * nested namespace object — because only those have a runtime binding.
 * A type (interface, alias, callable-interface alias) has none and needs
 * no wiring; an unreachable type is fine. A value-bearing declaration NOT
 * reachable from the entry is a loud marker (the declaration stays, so
 * types referencing it still compile): that marker count is the honest
 * measure of what a consumer cannot bind.
 */
public class ModuleWiring(
    /** The npm package name — `@file:JsModule`'s argument. */
    public val moduleName: String,
    /** The entry file's name, exactly as given in the generation's file list. */
    public val entryFileName: String,
    /**
     * (EXT.21) The Kotlin package the generation is emitted into, DERIVED
     * from [moduleName] when null — see [kotlinPackageNameFor]. A generation
     * needs one because two npm modules may declare the same name (measured:
     * `@types/node` declares 112 names in more than one module, `Socket` in
     * both `dgram` and `net`), and two same-named Kotlin declarations can
     * only coexist in different packages.
     *
     * [packageRoot] namespaces a multi-module package under one prefix — the
     * `kotlin-wrappers` convention, where node's `fs` module is `node.fs`
     * rather than a top-level `fs`. It is a prefix, not a replacement: the
     * module's own derived segments follow it.
     */
    public val packageRoot: String? = null,
)

/**
 * (EXT.21) The Kotlin package a module SPECIFIER maps to, or a refusal.
 *
 * `Derived` carries the package as it is SPELLED in a `package` declaration
 * and in a qualified reference — the same text, measured equivalent in
 * `KotlinPackageNameCompileTest`, backticked segments included.
 */
public sealed interface KotlinPackageName {

    /** The mapping succeeded; [spelling] is the `package` line's argument. */
    public class Derived(public val spelling: String) : KotlinPackageName

    /** The specifier carries something no Kotlin package can spell. */
    public class Refused(public val reason: String) : KotlinPackageName

}

/**
 * (EXT.21) Kotlin's HARD keywords — the ones that cannot be an identifier
 * without a backtick. Soft and modifier keywords are ordinary identifiers in
 * a package position and are deliberately NOT listed: backticking them would
 * be noise.
 */
private val KOTLIN_HARD_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
    "if", "in", "interface", "is", "null", "object", "package", "return",
    "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
    "var", "when", "while",
)

/**
 * (EXT.21) The MEASURED mapping from an npm module specifier to a Kotlin
 * package name. Every rule below is a row of `KotlinPackageNameCompileTest`,
 * which asked the metadata compiler AND `K2JSCompiler` (they agree on every
 * case measured):
 *
 *  - `/`, `:` and `.` are SEPARATORS — `fs/promises` is `fs.promises`,
 *    `node:net` is `node.net`, `lodash.merge` is `lodash.merge`. All three
 *    are refused INSIDE a segment even when backticked, so mapping them to
 *    the Kotlin separator is the only thing that can be done with them.
 *  - a leading `@` on the first segment is npm's SCOPE marker and is dropped
 *    (`@types/node` is `types.node`); `@` is refused inside a backtick too,
 *    so it cannot be kept.
 *  - a segment that is a valid Kotlin identifier and not a hard keyword is
 *    emitted as written.
 *  - a segment that is a hard keyword, starts with a digit, or contains `-`
 *    is BACKTICKED — measured: a backtick rescues exactly these.
 *  - anything else is REFUSED by name. **The queued proposal said "any
 *    segment that is not a Kotlin identifier backticked"; that is measurably
 *    wrong** — a backtick does not rescue `.`, `~`, `@`, `:` or `/`
 *    (`Name contains illegal characters`), so an unmappable character must
 *    be refused, never escaped and emitted.
 *
 * The specifier's CASE is preserved. Kotlin packages are conventionally
 * lowercase, but lowercasing loses information (two specifiers differing
 * only in case would collapse onto one package) and npm names are already
 * lowercase, so the convention costs nothing to honour and everything to
 * enforce.
 */
public fun kotlinPackageNameFor(
    moduleName: String,
    packageRoot: String? = null,
): KotlinPackageName {
    val segments = moduleName
        .split('/', ':', '.')
        .filter { it.isNotEmpty() }
    if (segments.isEmpty()) {
        return KotlinPackageName.Refused("the specifier '$moduleName' has no package segment")
    }
    val spelled = mutableListOf<String>()
    segments.forEachIndexed { index, raw ->
        // npm's scope marker, and only in the leading position: `@` cannot be
        // carried into a package name in any form.
        val segment = if (index == 0 && raw.startsWith("@")) raw.substring(1) else raw
        if (segment.isEmpty()) {
            return KotlinPackageName.Refused("the specifier '$moduleName' has an empty package segment")
        }
        val illegal = segment.firstOrNull { !it.isLetterOrDigit() && it != '_' && it != '-' }
        if (illegal != null) {
            return KotlinPackageName.Refused(
                "the specifier '$moduleName' carries '$illegal' in the segment '$segment', " +
                    "which no Kotlin package can spell - a backtick does not rescue it"
            )
        }
        val needsBacktick = segment in KOTLIN_HARD_KEYWORDS ||
            segment.first().isDigit() ||
            segment.any { !it.isLetterOrDigit() && it != '_' }
        spelled += if (needsBacktick) "`$segment`" else segment
    }
    val root = packageRoot?.takeIf { it.isNotEmpty() }
    return KotlinPackageName.Derived(
        if (root == null) spelled.joinToString(".") else root + "." + spelled.joinToString(".")
    )
}

/**
 * (EXT.16) How one value-bearing ROOT declaration is reachable from the
 * package entry — the wiring decision the collector renders.
 */
internal sealed interface Reach {
    /** Exported at the module surface under these names, entry order, distinct. */
    class Names(val names: List<String>) : Reach
    /** Exported only under QUALIFIED paths (`ns.x`) — dotted, entry order. */
    class Qualified(val paths: List<String>) : Reach
    /** The `export =` target: the module object itself. */
    object ModuleObject : Reach
    /** A member of a flattened root whose header already states the reach. */
    object ViaHeader : Reach
    object Unreachable : Reach
}

/**
 * (EXT.16) The public SURFACE of a package, computed from the SYNTAX of the
 * given files' export and import statements — before the check runs, as
 * [Surface]'s membership sets are — and read by the collector when it
 * decides each root declaration's [Reach].
 *
 * ## The graph
 *
 * Each file has an EXPORT TABLE, exported name → target, built lazily and
 * memoised: an `export`-modified declaration is `Local(file, name)`
 * (`export default` declares it under `default`); `export { a as b }`
 * resolves `a` through the file's IMPORT bindings first (`import { p as a }
 * from`, `import a from` — the `default` of the target, `import * as a` —
 * the target's module object) and to the file's own top-level declaration
 * otherwise; `export { a } from './x'` and `export * as ns from './x'` read
 * the resolved file's table; `export * from './x'` merges it, `default`
 * excluded, an explicit export of the same name winning and a name two
 * stars disagree on dropped (TypeScript's own rule); a type-only export
 * (`export type { A }`) binds no runtime name and is not in the table;
 * `export = X` records the file's MODULE OBJECT. A relative specifier resolves against the
 * importer's directory over the generation's own file names (`.js` mapped
 * to its `.ts`/`.d.ts` sibling, `index` files tried — the checker's own
 * ladder); a bare specifier, or a relative one naming no given file, is
 * OUTSIDE the generation and stays a loud marker at its statement. A cycle
 * of `export *` reads an empty table on re-entry.
 *
 * ## The walk
 *
 * From the entry's table every target is recorded with the PATH it is
 * reached under: a top-level name at the surface, `ns.x` through a
 * namespace export (recursively, each namespace one segment deeper). A
 * declaration's [Reach] is then the paths recorded for its `(file, name)`:
 * surface names first, qualified paths otherwise, and [Reach.ModuleObject]
 * for the entry's `export =` target. A declaration INSIDE a flattened root
 * namespace inherits the root's: members of the module object bind under
 * their own names, members of a root reachable any other way (or not at
 * all) are [Reach.ViaHeader] — the root's header marker states it once,
 * rather than every member repeating it.
 */
internal class ExportPlan(
    sourceFiles: List<SourceFile>,
    private val wiring: ModuleWiring,
) {

    /** A top-level declaration GROUP: every declaration of one name in one file. */
    private data class Binding(val fileName: String, val name: String)

    private sealed interface Target {
        class Declared(val binding: Binding) : Target
        /** A whole file's module object — a namespace import/export of it. */
        class Module(val fileName: String) : Target
        class Unresolved(val reason: String) : Target
    }

    private val files: Map<String, SourceFile> = sourceFiles.associateBy { it.fileName }

    val entry: SourceFile = files[wiring.entryFileName]
        ?: throw IllegalArgumentException(
            "ModuleWiring entry '${wiring.entryFileName}' is not among the generated files: " +
                sourceFiles.joinToString { it.fileName }
        )

    val moduleName: String get() = wiring.moduleName

    /**
     * Whether the entry declares the UMD global — `export as namespace X`,
     * which this parser keeps no node for (a documented misparse: the `as`
     * becomes an expression statement and `namespace X` a bodiless
     * namespace), so the ENTRY TEXT is scanned, anchored at a line start.
     */
    val umd: Boolean = umdRegex.containsMatchIn(entry.text)

    /** The names the UMD lines declare — the phantom namespaces the misparse leaves behind. */
    val umdNames: Set<String> = umdRegex.findAll(entry.text).mapTo(HashSet()) { it.groupValues[1] }

    private val exportTables = HashMap<String, Map<String, Target>>()
    private val moduleObjects = HashMap<String, Target?>()
    private val inProgress = HashSet<String>()

    /** Every path a binding is reached under, entry order. */
    private val reaches = HashMap<Binding, MutableList<List<String>>>()

    private val moduleObject: Binding?

    init {
        val visited = HashSet<Pair<String, List<String>>>()
        fun visit(fileName: String, prefix: List<String>) {
            if (!visited.add(fileName to prefix)) return
            for ((name, target) in exportsOf(fileName)) {
                when (target) {
                    is Target.Declared -> reaches.getOrPut(target.binding) { mutableListOf() }.add(prefix + name)
                    is Target.Module -> visit(target.fileName, prefix + name)
                    is Target.Unresolved -> {}
                }
            }
        }
        visit(entry.fileName, emptyList())
        moduleObject = (moduleObjectOf(entry.fileName) as? Target.Declared)?.binding
    }

    /** The reach of the top-level declaration group `name` in `fileName`. */
    private fun declaredReach(fileName: String, name: String): Reach {
        val binding = Binding(fileName, name)
        if (binding == moduleObject) return Reach.ModuleObject
        val paths = reaches[binding] ?: return Reach.Unreachable
        val names = paths.filter { it.size == 1 }.map { it[0] }.distinct()
        if (names.isNotEmpty()) return Reach.Names(names)
        return Reach.Qualified(paths.map { it.joinToString(".") }.distinct())
    }

    /**
     * Whether a top-level declaration named [name] in [fileName] that carries
     * no `export` modifier still joins the surface: it is reached from the
     * entry (`declare const _default: …; export default _default;`, `declare
     * class Foo {}; export = Foo;`).
     */
    fun reachesLocal(fileName: String, name: String): Boolean =
        declaredReach(fileName, name) !is Reach.Unreachable

    /**
     * The [Reach] of a collected ROOT-scope declaration: [node] is the
     * declaration (a function, class, enum, variable declaration or nested
     * namespace) and [name] its TypeScript name. A declaration whose
     * top-level statement is a flattened root namespace takes the root's
     * reach as [ExportPlan]'s KDoc describes.
     */
    fun reachOf(node: Node, name: String): Reach {
        var top: Node = node
        var parent: Node? = (node as? NodeBase)?.parent
        while (parent != null && parent !is SourceFile) {
            top = parent
            parent = (parent as? NodeBase)?.parent
        }
        val file = parent ?: return Reach.Unreachable
        if (top is ModuleDeclaration) {
            val root = rootIdentifierOf(top.name) ?: return Reach.ViaHeader
            return when (declaredReach(file.fileName, root)) {
                is Reach.ModuleObject -> Reach.Names(listOf(name))
                else -> Reach.ViaHeader
            }
        }
        return declaredReach(file.fileName, name)
    }

    /** The reach of the flattened root [namespace] itself — what its header states. */
    fun rootReach(namespace: ModuleDeclaration): Reach {
        val file = (namespace as NodeBase).parent as? SourceFile ?: return Reach.Unreachable
        val root = rootIdentifierOf(namespace.name) ?: return Reach.Unreachable
        return declaredReach(file.fileName, root)
    }

    /**
     * The loud marker a top-level export statement KEEPS under the wiring —
     * a specifier outside the generation, a name resolving to nothing, a
     * nested export Kotlin cannot bind, a statement inside a flattened
     * module body — or null where the statement BECAME the wiring
     * (annotations on the declarations it reaches) and renders nothing.
     */
    fun statementMarker(node: Node): ExternalDeclaration? {
        val file = (node as? NodeBase)?.parent as? SourceFile
        if (file == null) return insideModuleMarker(node)
        return when (node) {
            is ExportAssignment -> exportAssignmentMarker(node, file)
            is ExportDeclaration -> exportDeclarationMarker(node, file)
            else -> SkippedDeclaration("export wiring")
        }
    }

    private fun exportAssignmentMarker(node: ExportAssignment, file: SourceFile): ExternalDeclaration? {
        val form = if (node.isExportEquals) "export = " else "default export of "
        val expression = node.expression as? Identifier
            ?: return SkippedDeclaration("${form}an expression - nothing a consumer can bind by name")
        return when (val target = resolveLocal(file, expression.text)) {
            is Target.Unresolved -> SkippedDeclaration("$form${expression.text} - ${target.reason}")
            else -> null
        }
    }

    private fun exportDeclarationMarker(node: ExportDeclaration, file: SourceFile): ExternalDeclaration? {
        val clauseText = exportClauseText(node)
        val specifier = (node.moduleSpecifier as? StringLiteralNode)?.text
        val from = specifier?.let { " from '$it'" } ?: ""
        val typeOnly = if (node.isTypeOnly) "type " else ""
        val prefix = "re-export $typeOnly$clauseText$from"
        val target: Target.Module? = specifier?.let { spec ->
            val resolved = resolveSpecifier(spec, file.fileName)
                ?: return SkippedDeclaration("$prefix - '$spec' resolves to no file in this generation")
            Target.Module(resolved)
        }
        return when (val clause = node.exportClause) {
            null -> null
            is NamespaceExport -> SkippedDeclaration(
                "namespace export ${clause.name.text}$from - binds as ${clause.name.text}.<name>, " +
                    "which needs @file:JsQualifier(\"${clause.name.text}\") in a file of its own"
            )
            is NamedExports -> {
                if (node.isTypeOnly) return null
                val unresolved = clause.elements.mapNotNull { element ->
                    if (element.isTypeOnly) return@mapNotNull null
                    val local = (element.propertyName ?: element.name).text
                    val resolved = if (target == null) resolveLocal(file, local) else memberOf(target, local)
                    (resolved as? Target.Unresolved)?.let { "$local ${it.reason}" }
                }
                if (unresolved.isEmpty()) null
                else SkippedDeclaration("$prefix - ${unresolved.joinToString("; ")}")
            }
            else -> SkippedDeclaration("$prefix - unsupported export clause")
        }
    }

    /** The old marker's shape for a statement inside a flattened module body, its tail naming the module. */
    private fun insideModuleMarker(node: Node): ExternalDeclaration {
        var current: Node? = (node as? NodeBase)?.parent
        while (current != null && current !is ModuleDeclaration) current = (current as? NodeBase)?.parent
        val container = current?.name?.let { name ->
            when (name) {
                is StringLiteralNode -> "module \"${name.text}\""
                else -> "namespace ${rootIdentifierOf(name) ?: "?"}"
            }
        } ?: "a namespace"
        val text = when (node) {
            is ExportAssignment -> {
                val expression = (node.expression as? Identifier)?.text ?: "an expression"
                val form = if (node.isExportEquals) "export = " else "default export of "
                "$form$expression"
            }
            is ExportDeclaration -> {
                val specifier = (node.moduleSpecifier as? StringLiteralNode)?.text
                val from = specifier?.let { " from '$it'" } ?: ""
                val typeOnly = if (node.isTypeOnly) "type " else ""
                "re-export $typeOnly${exportClauseText(node)}$from"
            }
            else -> "export wiring"
        }
        return SkippedDeclaration("$text inside $container - outside the package entry's surface")
    }

    /**
     * The header a flattened root namespace renders under: what its members
     * bind as, decided by the root's own reach — the module object (their
     * own names), a module MEMBER (qualified, `@file:JsQualifier` in a file
     * of its own), or nothing. A `declare module "m"` body is bound by ITS
     * name: the package's own module when the names agree, another module
     * otherwise.
     */
    fun namespaceHeader(namespace: ModuleDeclaration): String {
        val name = namespace.name
        if (name is StringLiteralNode) {
            return if (name.text == moduleName) {
                "module \"${name.text}\" - the package's own module; members rendered at top level"
            } else {
                "module \"${name.text}\" - members rendered at top level; bound by @file:JsModule(\"${name.text}\"), " +
                    "not \"$moduleName\" - a file of its own"
            }
        }
        val root = rootIdentifierOf(name) ?: "?"
        return when (val reach = rootReach(namespace)) {
            is Reach.ModuleObject ->
                "namespace $root - the module object (export = $root); members rendered at top level"
            is Reach.Names ->
                "namespace $root - the module member ${reach.names.first()}; members rendered at top level " +
                    "and bind as ${reach.names.first()}.<name>, which needs @file:JsQualifier(\"${reach.names.first()}\") " +
                    "in a file of its own"
            is Reach.Qualified ->
                "namespace $root - exported only as ${reach.paths.first()}; members rendered at top level " +
                    "and need @file:JsQualifier(\"${reach.paths.first()}\") in a file of its own"
            is Reach.ViaHeader, is Reach.Unreachable ->
                "namespace $root - not exported by the package entry; members rendered at top level"
        }
    }

    // --- the graph -----------------------------------------------------------

    private fun exportsOf(fileName: String): Map<String, Target> {
        exportTables[fileName]?.let { return it }
        if (!inProgress.add(fileName)) return emptyMap()
        val file = files[fileName]
        val table = if (file == null) emptyMap() else buildExports(file)
        inProgress.remove(fileName)
        exportTables[fileName] = table
        return table
    }

    private fun buildExports(file: SourceFile): Map<String, Target> {
        val explicit = LinkedHashMap<String, Target>()
        val stars = mutableListOf<String>()
        for (statement in file.statements) {
            when (statement) {
                is ExportAssignment -> if (!statement.isExportEquals) {
                    explicit["default"] = (statement.expression as? Identifier)
                        ?.let { resolveLocal(file, it.text) }
                        ?: Target.Unresolved("is an expression")
                }
                is ExportDeclaration -> {
                    // A type-only re-export binds no runtime name.
                    if (statement.isTypeOnly) continue
                    val specifier = (statement.moduleSpecifier as? StringLiteralNode)?.text
                    val target: Target.Module? = specifier?.let { spec ->
                        val resolved = resolveSpecifier(spec, file.fileName) ?: return@let null
                        Target.Module(resolved)
                    }
                    if (specifier != null && target == null) continue
                    when (val clause = statement.exportClause) {
                        null -> if (target != null) stars += target.fileName
                        is NamespaceExport -> if (target != null) explicit[clause.name.text] = target
                        is NamedExports -> for (element in clause.elements) {
                            if (element.isTypeOnly) continue
                            val local = (element.propertyName ?: element.name).text
                            explicit[element.name.text] =
                                if (target == null) resolveLocal(file, local) else memberOf(target, local)
                        }
                        else -> {}
                    }
                }
                is ImportEqualsDeclaration -> if (ModifierFlag.Export in statement.modifiers) {
                    explicit[statement.name.text] = importEqualsTarget(file, statement)
                }
                else -> {
                    val modifiers = modifiersOf(statement) ?: continue
                    if (ModifierFlag.Export !in modifiers) continue
                    val exportedAs = if (ModifierFlag.Default in modifiers) "default" else null
                    for (name in declaredNames(statement)) {
                        explicit[exportedAs ?: name] = Target.Declared(Binding(file.fileName, name))
                    }
                }
            }
        }
        if (stars.isEmpty()) return explicit
        val fromStars = LinkedHashMap<String, Target>()
        val ambiguous = HashSet<String>()
        for (star in stars) {
            for ((name, target) in exportsOf(star)) {
                if (name == "default" || name in explicit) continue
                val earlier = fromStars[name]
                if (earlier == null) fromStars[name] = target
                else if (!sameTarget(earlier, target)) ambiguous += name
            }
        }
        for (name in ambiguous) fromStars.remove(name)
        return explicit + fromStars
    }

    private fun sameTarget(a: Target, b: Target): Boolean = when {
        a is Target.Declared && b is Target.Declared -> a.binding == b.binding
        a is Target.Module && b is Target.Module -> a.fileName == b.fileName
        else -> false
    }

    /** The module OBJECT of a file: its `export =` target, else the file's namespace. */
    private fun moduleObjectOf(fileName: String): Target? {
        if (moduleObjects.containsKey(fileName)) return moduleObjects[fileName]
        moduleObjects[fileName] = null
        val file = files[fileName]
        val assignment = file?.statements
            ?.filterIsInstance<ExportAssignment>()
            ?.firstOrNull { it.isExportEquals }
        val target = when {
            file == null -> null
            assignment == null -> Target.Module(fileName)
            else -> (assignment.expression as? Identifier)
                ?.let { resolveLocal(file, it.text) }
                ?: Target.Unresolved("is an expression")
        }
        moduleObjects[fileName] = target
        return target
    }

    /** The export [name] of a module target, or the reason there is none. */
    private fun memberOf(target: Target, name: String): Target = when (target) {
        is Target.Module -> exportsOf(target.fileName)[name]
            ?: Target.Unresolved("is not exported by '${target.fileName}'")
        is Target.Declared -> Target.Unresolved("is a member of a declaration, not of a module")
        is Target.Unresolved -> target
    }

    /**
     * What the local name [name] denotes in [file]: an import binding of that
     * name, else the file's own top-level declaration group, else nothing.
     */
    private fun resolveLocal(file: SourceFile, name: String): Target {
        for (statement in file.statements) {
            when (statement) {
                is ImportDeclaration -> {
                    val clause = statement.importClause ?: continue
                    val specifier = (statement.moduleSpecifier as? StringLiteralNode)?.text ?: continue
                    val bound = importBindings(clause).firstOrNull { it.first == name } ?: continue
                    val resolved = resolveSpecifier(specifier, file.fileName)
                        ?: return Target.Unresolved("is imported from '$specifier', outside this generation")
                    val module = Target.Module(resolved)
                    return when (val member = bound.second) {
                        null -> moduleObjectOf(resolved) ?: module
                        else -> memberOf(module, member)
                    }
                }
                is ImportEqualsDeclaration ->
                    if (statement.name.text == name && ModifierFlag.Export !in statement.modifiers) {
                        return importEqualsTarget(file, statement)
                    }
                else -> {}
            }
        }
        if (file.statements.any { name in declaredNames(it) }) return Target.Declared(Binding(file.fileName, name))
        return Target.Unresolved("resolves to no declaration")
    }

    private fun importEqualsTarget(file: SourceFile, statement: ImportEqualsDeclaration): Target =
        when (val reference = statement.moduleReference) {
            is ExternalModuleReference -> {
                val specifier = (reference.expression as? StringLiteralNode)?.text
                val resolved = specifier?.let { resolveSpecifier(it, file.fileName) }
                if (resolved == null) Target.Unresolved("is imported from '${specifier ?: "?"}', outside this generation")
                else moduleObjectOf(resolved) ?: Target.Module(resolved)
            }
            is Identifier -> resolveLocal(file, reference.text)
            else -> Target.Unresolved("is a qualified import alias")
        }

    /** The `(local name, imported member or null for the module object)` pairs of an import clause. */
    private fun importBindings(clause: ImportClause): List<Pair<String, String?>> {
        val bindings = mutableListOf<Pair<String, String?>>()
        clause.name?.let { bindings += it.text to "default" }
        when (val named = clause.namedBindings) {
            is NamespaceImport -> bindings += named.name.text to null
            is NamedImports -> for (element in named.elements) {
                bindings += element.name.text to (element.propertyName ?: element.name).text
            }
            else -> {}
        }
        return bindings
    }

    private fun modifiersOf(statement: Statement): Set<ModifierFlag>? = when (statement) {
        is FunctionDeclaration -> statement.modifiers
        is ClassDeclaration -> statement.modifiers
        is EnumDeclaration -> statement.modifiers
        is InterfaceDeclaration -> statement.modifiers
        is TypeAliasDeclaration -> statement.modifiers
        is VariableStatement -> statement.modifiers
        is ModuleDeclaration -> statement.modifiers
        else -> null
    }

    /** The top-level names a statement declares — several for a variable statement, the head for a dotted namespace. */
    private fun declaredNames(statement: Statement): List<String> = when (statement) {
        is FunctionDeclaration -> listOfNotNull(statement.name?.text)
        is ClassDeclaration -> listOfNotNull(statement.name?.text)
        is EnumDeclaration -> listOf(statement.name.text)
        is InterfaceDeclaration -> listOf(statement.name.text)
        is TypeAliasDeclaration -> listOf(statement.name.text)
        is VariableStatement -> statement.declarationList.declarations.mapNotNull { (it.name as? Identifier)?.text }
        is ModuleDeclaration -> listOfNotNull(rootIdentifierOf(statement.name))
        else -> emptyList()
    }

    /** The head identifier of a namespace name — `A` for `A` and for `A.B.C`; null for a string name. */
    private fun rootIdentifierOf(name: Expression): String? = when (name) {
        is Identifier -> name.text
        is PropertyAccessExpression -> rootIdentifierOf(name.expression)
        else -> null
    }

    /**
     * A relative specifier resolved against the importer's directory over the
     * given file names: the exact path, `.ts`/`.tsx`/`.d.ts`, an `index`
     * file, and each of those again with a `.js`/`.jsx`/`.mjs`/`.cjs`
     * extension stripped (the ESM `.js` idiom). A bare specifier is outside.
     */
    private fun resolveSpecifier(specifier: String, importer: String): String? {
        if (!specifier.startsWith("./") && !specifier.startsWith("../")) return null
        val dir = importer.substringBeforeLast('/', "")
        // An importer at the ROOT (`/index.d.ts`, the probe's root-stripped
        // shape) has an empty directory text and an absolute path all the
        // same: its siblings are `/x`, not `x`.
        val joined = when {
            dir.isNotEmpty() -> "$dir/$specifier"
            importer.startsWith("/") -> "/$specifier"
            else -> specifier
        }
        val normalized = normalizePath(joined)
        val bases = buildList {
            add(normalized)
            for (extension in listOf(".js", ".jsx", ".mjs", ".cjs")) {
                if (normalized.endsWith(extension)) add(normalized.removeSuffix(extension))
            }
        }
        for (base in bases) {
            for (candidate in listOf(base, "$base.ts", "$base.tsx", "$base.d.ts", "$base/index.ts", "$base/index.d.ts")) {
                if (candidate in files) return candidate
            }
        }
        return null
    }

    private fun normalizePath(path: String): String {
        val absolute = path.startsWith("/")
        val segments = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty() && segments.last() != "..") segments.removeLast() else if (!absolute) segments.addLast("..")
                else -> segments.addLast(segment)
            }
        }
        return (if (absolute) "/" else "") + segments.joinToString("/")
    }

    private companion object {
        val umdRegex = Regex("""^[ \t]*export[ \t]+as[ \t]+namespace[ \t]+([A-Za-z_$][A-Za-z0-9_$]*)[ \t]*;?[ \t]*$""", RegexOption.MULTILINE)
    }

}

/** (EXT.16) The written spelling of an export clause: `*`, `* as ns`, `{ a, b as c }`. */
internal fun exportClauseText(node: ExportDeclaration): String = when (val clause = node.exportClause) {
    null -> "*"
    is NamespaceExport -> "* as ${clause.name.text}"
    is NamedExports -> clause.elements.joinToString(", ", "{ ", " }") {
        val local = it.propertyName?.text
        if (local == null || local == it.name.text) it.name.text
        else "$local as ${it.name.text}"
    }
    else -> clause::class.simpleName ?: "clause"
}
