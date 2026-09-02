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
import com.xemantic.typescript.compiler.ClassExpression
import com.xemantic.typescript.compiler.ClassStaticBlockDeclaration
import com.xemantic.typescript.compiler.CompilerOptions
import com.xemantic.typescript.compiler.Constructor
import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.DiagnosticCategory
import com.xemantic.typescript.compiler.EnumDeclaration
import com.xemantic.typescript.compiler.ExportAssignment
import com.xemantic.typescript.compiler.ExportDeclaration
import com.xemantic.typescript.compiler.ArrayType
import com.xemantic.typescript.compiler.BigIntLiteralNode
import com.xemantic.typescript.compiler.Expression
import com.xemantic.typescript.compiler.FunctionDeclaration
import com.xemantic.typescript.compiler.FunctionType
import com.xemantic.typescript.compiler.GetAccessor
import com.xemantic.typescript.compiler.HeritageClause
import com.xemantic.typescript.compiler.ExternalModuleReference
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.ImportDeclaration
import com.xemantic.typescript.compiler.ImportEqualsDeclaration
import com.xemantic.typescript.compiler.IndexSignature
import com.xemantic.typescript.compiler.InterfaceDeclaration
import com.xemantic.typescript.compiler.KeywordTypeNode
import com.xemantic.typescript.compiler.LiteralType
import com.xemantic.typescript.compiler.MethodDeclaration
import com.xemantic.typescript.compiler.ModifierFlag
import com.xemantic.typescript.compiler.ModuleBlock
import com.xemantic.typescript.compiler.ModuleDeclaration
import com.xemantic.typescript.compiler.NamedExports
import com.xemantic.typescript.compiler.NamespaceImport
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.NumericLiteralNode
import com.xemantic.typescript.compiler.Parameter
import com.xemantic.typescript.compiler.ParenthesizedType
import com.xemantic.typescript.compiler.Parser
import com.xemantic.typescript.compiler.PrefixUnaryExpression
import com.xemantic.typescript.compiler.PropertyAccessExpression
import com.xemantic.typescript.compiler.PropertyDeclaration
import com.xemantic.typescript.compiler.QualifiedName
import com.xemantic.typescript.compiler.SemicolonClassElement
import com.xemantic.typescript.compiler.SetAccessor
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.SourceFileEntry
import com.xemantic.typescript.compiler.Statement
import com.xemantic.typescript.compiler.StringLiteralNode
import com.xemantic.typescript.compiler.SyntaxKind
import com.xemantic.typescript.compiler.Type
import com.xemantic.typescript.compiler.TypeAliasDeclaration
import com.xemantic.typescript.compiler.TypeNode
import com.xemantic.typescript.compiler.TypeOperator
import com.xemantic.typescript.compiler.TypeFlags
import com.xemantic.typescript.compiler.TypeParameter
import com.xemantic.typescript.compiler.TypeQuery
import com.xemantic.typescript.compiler.TypeReference
import com.xemantic.typescript.compiler.UnionType
import com.xemantic.typescript.compiler.VariableDeclaration
import com.xemantic.typescript.compiler.VariableStatement
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
    module: ModuleWiring? = null,
): KotlinExternals =
    generateKotlinExternals(files = listOf(SourceFileEntry(fileName, source)), options = options, module = module)

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
 *
 * (EXT.16) [module] wires the generation to an npm package: the real output
 * opens with `@file:JsModule("<name>")`, the package's public surface is
 * computed through the re-export graph from the entry file ([ExportPlan])
 * and every value-bearing declaration renders its binding — nothing for
 * one reachable under its own name, `@JsName("<exported>")` for one
 * reachable under another, a loud marker for one the entry does not
 * export. `null` keeps the global-script output of every earlier rung
 * exactly. The entry must be one of [files]; naming another is a caller
 * error, not a marker.
 */
public fun generateKotlinExternals(
    files: List<SourceFileEntry>,
    options: CompilerOptions = CompilerOptions(useRealLibs = true),
    module: ModuleWiring? = null,
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
    val plan = module?.let { ExportPlan(sourceFiles, it) }
    val collector = ExternalsCollector(Surface(sourceFiles, plan), plan)
    val checker = runWithDeepStack {
        Checker(
            options,
            binderResults,
            isMultiFileSource = true,
            checkedSink = collector,
        )
    }
    val declarations = collector.finish()
    val header = plan?.let { ModuleHeader(it.moduleName, it.umd) }
    return KotlinExternals(
        kotlin = renderKotlinExternals(declarations, external = true, header = header),
        compileCheckSource = renderKotlinExternals(declarations, external = false),
        diagnostics = parseDiagnostics + checker.getDiagnostics(),
    )
}

/**
 * (EXT.13) One declaration of the generated SURFACE together with the
 * namespace PATH it is declared under: empty at the module's top level and
 * inside a FLATTENED root namespace, `[server, protocol]` inside the nested
 * ones. The membership sets behind every sink arm are lists of these — the
 * sink fires for nested declarations too (a namespace body, a function
 * body), and only the pre-scanned surface is generated.
 */
private class Declared<N : Node>(
    val node: N,
    /** (EXT.19) Where the declaration sits — what its type texts resolve and spell against. */
    val site: Site,
    /**
     * (EXT.19) The declaration's position in SCAN order — file order, then
     * source order, a namespace before its members — which is the order the
     * checker's walk collects in and so the order [ExternalsCollector.finish]'s
     * first-wins name rule decides by. What lets a REFERENCE know, before
     * `finish` has run, whether the declaration it names is the one that
     * keeps its name in its scope ([ExternalsCollector.ownsName]).
     */
    val ordinal: Int,
) {
    val path: List<String> get() = site.path
    /** (EXT.14) Inside a `declare module "m"` body (at any depth) — the retained fallback's condition. */
    val inAmbientModule: Boolean get() = site.inAmbientModule
}

/**
 * (EXT.19) The SITE of a declaration: its namespace path, whether it sits in
 * a `declare module "m"` body, which module's, and the namespace imports
 * visible to it (alias to specifier — `import * as net from "node:net"`,
 * `import net = require("net")` — of the enclosing module block, over the
 * file's own). One value per declaration, threaded from the scan to every
 * [TypeScope] built for the declaration's members.
 */
private class Site(
    val path: List<String>,
    val inAmbientModule: Boolean,
    val moduleSpecifier: String?,
    val namespaceImports: Map<String, String>,
)

/** (EXT.9) One exported value declaration, its list's `const`-ness (→ `val`) and its site. */
private class ExportedValue(
    val node: VariableDeclaration,
    val readOnly: Boolean,
    val site: Site,
    val ordinal: Int,
) {
    val path: List<String> get() = site.path
    val inAmbientModule: Boolean get() = site.inAmbientModule
}

/**
 * (EXT.13) How one [ModuleDeclaration] renders, decided from SYNTAX up front:
 *
 *  - a ROOT ambient namespace — `declare namespace ts { … }` at the top
 *    level, or any top-level namespace of a `.d.ts` file, exported or not,
 *    whether or not the file ends in `export = ts` — FLATTENS: its members
 *    render exactly as top-level declarations do, under one loud [header].
 *    Rationale: a Kotlin `typealias` is top-level only, and `export = ts`
 *    makes the namespace body the module's surface, which is what a
 *    consumer's `@file:JsModule("typescript")` binds. A namespace beside
 *    ordinary top-level exports flattens the same way (its members join the
 *    surface; a name collision falls to the "declared again" loud skip in
 *    [ExternalsCollector.finish]);
 *  - a `declare module "name" { … }` (an ambient module, string-named)
 *    flattens the same way, the header naming the module; the body-less
 *    shorthand form declares nothing and is a loud skip;
 *  - a NESTED namespace (`ts.server`, `ts.server.protocol`) is a Kotlin
 *    nested OBJECT ([ExternalObject]) at [bodyPath]; a dotted root
 *    `declare namespace A.B { }` is `A` flattened with `B` nested;
 *  - a NON-ambient namespace — one with a runtime body, in a `.ts` file
 *    without `declare` — is out of scope: a loud [skip] of the whole
 *    namespace naming it; so is `declare global { }` (a global
 *    augmentation, a later rung).
 */
private class NamespaceEntry(
    val node: ModuleDeclaration,
    /** The path the ENTRY itself is rendered at — its header or skip marker. */
    val ownerPath: List<String>,
    /** The loud header a flattened ROOT renders, or null for a nested namespace. */
    val header: String?,
    /** The path of the namespace's BODY declarations — empty for a flattened root. */
    val bodyPath: List<String>,
    /** The loud skip of a namespace that is not generated at all, or null. */
    val skip: String?,
    /** The identifier a flattened root is written as (`ts`), or null. */
    val rootName: String?,
    /** (EXT.19) Scan position, before the body's members — see [Declared.ordinal]. */
    val ordinal: Int,
)

/**
 * (EXT.19) What a `declare module "m"` block's SURFACE is made of beyond its
 * own declarations — the statements a written `net.X` through a namespace
 * import walks: `export * from "x"` (the `node:net` → `net` idiom), `export
 * = X` (the block's namespace `X`, or an `import X = require("x")` alias
 * naming another module — `node:stream` → `stream` → its namespace `Stream`),
 * and the `require` aliases themselves. Several blocks naming one module
 * (an augmentation beside the declaration) merge into one record.
 */
private class AmbientModule {
    val starExports = mutableListOf<String>()
    val exportEquals = mutableListOf<String>()
    val requireAliases = HashMap<String, String>()
}

/**
 * (EXT.13) The generated SURFACE of a program, pre-scanned from the SYNTAX
 * before the check runs — the membership sets behind "render a named type
 * by its name" and behind every sink arm. Syntax suffices for the SET
 * (exported-ness, the name and the namespace path are written); what still
 * needs the checker is the IDENTITY test at each use, which compares the
 * resolved type's own declaration against these nodes by `===`, so a lib
 * type or a non-exported neighbour that shares the spelling is
 * positive-evidence excluded.
 *
 * ## Which declarations are exported
 *
 * At a file's top level, the ones carrying `export` (unchanged since
 * (EXT.1)). Inside an AMBIENT namespace or module the rule is tsc's
 * `setExportContextFlag`, measured against tsgo 7.0.2: every member is
 * IMPLICITLY exported, an explicit `export` modifier elsewhere in the body
 * does NOT switch that off (`declare namespace ns { export interface A {}
 * interface B {} const v: number }` exports all three), nested namespaces
 * inherit it, and the ONE thing that switches it off is an export
 * DECLARATION in the body — `export { A }` / `export =` — which a namespace
 * body cannot hold (TS1194) and an ambient `declare module "m" { … }` can:
 * there, only the `export`-modified members are exported (`B` is TS2459,
 * "declares 'B' locally, but it is not exported"). An `import X = …` alias
 * is the one declaration kind an export context does NOT export — tsc's
 * `declareModuleMember` exports an import-equals only with the explicit
 * modifier — so `export import X = ts.X` is collected and a bare `import
 * X = ts.X` is a local. Ambient-ness is the `declare` modifier, the
 * enclosing container's, or the whole file's (`.d.ts`).
 *
 * (EXT.20) An `export = X` makes `X` — and everything of that name it
 * MERGES with: the class, the interface and the namespace `EventEmitter` of
 * `events.d.ts`, the class and the two namespace blocks `Stream` of
 * `stream.d.ts` — the module's whole surface, whether or not any of those
 * declarations carries `export`, in a `declare module "m"` body and at a
 * file's top level alike. Measured against tsgo 7.0.2 on the `events.d.ts`
 * shape: `import EE = require("events")`, `import { … } from "events"`,
 * `import * as ee from "events"` and the default import all bind the class,
 * the merged interface's members and the namespace's `export`-modified
 * members (types, functions, values, classes), and every OTHER un-modified
 * member of the block — a module-level `interface Hidden`, a namespace
 * member without `export` — is TS2694/TS2305 under every form. So the
 * target's name is exported by [exportEqualsTargets] and nothing else
 * changes: the other un-modified members stay silent, the policy every
 * private thing has, while the target itself can never vanish. An
 * `export =` naming an import alias (`import events = require("events");
 * export = events`) or an expression names no declaration of the block and
 * exports nothing here (the wiring marker records it).
 *
 * (EXT.16) Under a module wiring a top-level declaration WITHOUT the
 * modifier joins the surface too when the entry REACHES it — `declare
 * const _default: …; export default _default;` (smol-toml's entry),
 * `declare class Foo {} export = Foo;` — because a consumer binds it, and
 * the bodiless `namespace X` the parser leaves behind for the UMD `export
 * as namespace X` line ([ExportPlan.umdNames]) is not a namespace at all
 * and is scanned as nothing.
 */
private class Surface(sourceFiles: List<SourceFile>, private val plan: ExportPlan?) {

    val interfaces = mutableListOf<Declared<InterfaceDeclaration>>()
    val classes = mutableListOf<Declared<ClassDeclaration>>()
    /** (EXT.4) `const` enums included — the collector refuses those loudly, so they must reach it. */
    val enums = mutableListOf<Declared<EnumDeclaration>>()
    val aliases = mutableListOf<Declared<TypeAliasDeclaration>>()
    val functions = mutableListOf<Declared<FunctionDeclaration>>()
    val values = mutableListOf<ExportedValue>()
    /**
     * (EXT.7) The EXPORT WIRING statements — `export { a } from './x.js'`,
     * `export * from`, `export default <expression>`, `export = x` — at a
     * file's top level and (EXT.13) in a flattened ambient module's body,
     * which declare nothing of their own and re-route names a JavaScript
     * consumer binds. Module wiring (`@JsModule`/`@JsName`) is a later
     * rung, so each one is a LOUD marker. A bare `export {}` (the
     * module-marker idiom) is excluded: it re-routes nothing, so there is
     * nothing to be loud about.
     */
    val exportWiring = mutableListOf<Node>()
    val namespaces = mutableListOf<NamespaceEntry>()
    /** (EXT.13) `export import X = ts.X` lines — each a loud marker at its path. */
    val importAliases = mutableListOf<Declared<ImportEqualsDeclaration>>()
    /** (EXT.19) The ambient modules by specifier — what their surfaces re-route. */
    val modules = HashMap<String, AmbientModule>()

    private var nextOrdinal = 0

    /** The next scan position ([Declared.ordinal]). */
    private fun ordinal(): Int = nextOrdinal++

    init {
        for (sourceFile in sourceFiles) {
            scan(
                sourceFile.statements,
                path = emptyList(),
                topLevel = true,
                exportContext = false,
                ambient = sourceFile.fileName.endsWith(".d.ts"),
                inModule = false,
                fileName = sourceFile.fileName,
                moduleSpecifier = null,
                imports = namespaceImportsOf(sourceFile.statements, HashMap(), null),
                exportEqualsTargets = exportEqualsTargetsOf(sourceFile.statements),
            )
        }
    }

    /** (EXT.20) The identifiers the `export =` statements among [statements] name. */
    private fun exportEqualsTargetsOf(statements: List<Statement>): Set<String> =
        statements.mapNotNullTo(HashSet()) { statement ->
            (statement as? ExportAssignment)?.takeIf { it.isExportEquals }?.expression?.let { it as? Identifier }?.text
        }

    /**
     * (EXT.19) The namespace imports [statements] declare — `import * as X
     * from "m"` and `import X = require("m")` — added to [imports] (a block's
     * over the file's); a `require` alias is also recorded on [module], the
     * block's own record, because an `export = X` naming it re-routes the
     * whole module.
     */
    private fun namespaceImportsOf(
        statements: List<Statement>,
        imports: HashMap<String, String>,
        module: AmbientModule?,
    ): Map<String, String> {
        for (statement in statements) {
            when (statement) {
                is ImportDeclaration -> {
                    val alias = (statement.importClause?.namedBindings as? NamespaceImport)?.name?.text ?: continue
                    val specifier = (statement.moduleSpecifier as? StringLiteralNode)?.text ?: continue
                    imports[alias] = specifier
                }
                is ImportEqualsDeclaration -> {
                    val reference = statement.moduleReference as? ExternalModuleReference ?: continue
                    val specifier = (reference.expression as? StringLiteralNode)?.text ?: continue
                    imports[statement.name.text] = specifier
                    module?.requireAliases?.put(statement.name.text, specifier)
                }
                else -> {}
            }
        }
        return imports
    }

    private fun scan(
        statements: List<Statement>,
        path: List<String>,
        topLevel: Boolean,
        exportContext: Boolean,
        ambient: Boolean,
        inModule: Boolean,
        fileName: String,
        moduleSpecifier: String?,
        imports: Map<String, String>,
        /** (EXT.20) The names the scope's own `export =` statements name — exported whatever their modifiers. */
        exportEqualsTargets: Set<String>,
    ) {
        fun exported(modifiers: Set<ModifierFlag>, name: String?): Boolean =
            ModifierFlag.Export in modifiers ||
                (!topLevel && exportContext) ||
                (name != null && name in exportEqualsTargets) ||
                (topLevel && plan != null && name != null && plan.reachesLocal(fileName, name))
        val site = Site(path, inModule, moduleSpecifier, imports)
        for (statement in statements) {
            when (statement) {
                is InterfaceDeclaration ->
                    if (exported(statement.modifiers, statement.name.text)) interfaces += Declared(statement, site, ordinal())
                is ClassDeclaration ->
                    if (exported(statement.modifiers, statement.name?.text)) classes += Declared(statement, site, ordinal())
                is EnumDeclaration ->
                    if (exported(statement.modifiers, statement.name.text)) enums += Declared(statement, site, ordinal())
                is TypeAliasDeclaration ->
                    if (exported(statement.modifiers, statement.name.text)) aliases += Declared(statement, site, ordinal())
                is FunctionDeclaration ->
                    if (exported(statement.modifiers, statement.name?.text)) functions += Declared(statement, site, ordinal())
                is VariableStatement -> {
                    val readOnly = statement.declarationList.flags == SyntaxKind.ConstKeyword
                    for (declaration in statement.declarationList.declarations) {
                        if (exported(statement.modifiers, (declaration.name as? Identifier)?.text)) {
                            values += ExportedValue(declaration, readOnly, site, ordinal())
                        }
                    }
                }
                is ImportEqualsDeclaration ->
                    if (ModifierFlag.Export in statement.modifiers) {
                        importAliases += Declared(statement, site, ordinal())
                    }
                is ExportAssignment ->
                    if (path.isEmpty()) exportWiring += statement
                is ExportDeclaration ->
                    if (path.isEmpty() &&
                        (statement.moduleSpecifier != null ||
                            (statement.exportClause as? NamedExports)?.elements?.isNotEmpty() ?: true)
                    ) exportWiring += statement
                is ModuleDeclaration ->
                    scanNamespace(statement, path, topLevel, ambient, inModule, fileName, moduleSpecifier, imports)
                else -> {}
            }
        }
    }

    private fun scanNamespace(
        node: ModuleDeclaration,
        path: List<String>,
        topLevel: Boolean,
        ambient: Boolean,
        inModule: Boolean,
        fileName: String,
        moduleSpecifier: String?,
        imports: Map<String, String>,
    ) {
        val ambientHere = ambient || ModifierFlag.Declare in node.modifiers
        val block = node.body as? ModuleBlock
        val name = node.name
        // (EXT.16) The UMD line's phantom: `export as namespace X` parses as
        // a bodiless `namespace X`; the plan read the line, so this is nothing.
        if (plan != null && topLevel && block == null && name is Identifier &&
            name.text in plan.umdNames && fileName == plan.entry.fileName
        ) return
        val segments = namespaceSegments(name)
        var header: String? = null
        var bodyPath: List<String> = path
        var skip: String? = null
        var rootName: String? = null
        when {
            name is StringLiteralNode -> when {
                block == null -> skip = "shorthand ambient module \"${name.text}\" - it declares nothing"
                !topLevel -> skip = "module \"${name.text}\" nested in a namespace"
                else -> {
                    header = "module \"${name.text}\" - members rendered at top level; " +
                        "@JsModule/@JsQualifier wiring is a later rung"
                    bodyPath = emptyList()
                }
            }
            segments == null -> skip = "namespace with a name that is not an identifier"
            segments.size == 1 && segments[0] == "global" && ModifierFlag.Declare in node.modifiers ->
                skip = "declare global - a global augmentation is a later rung"
            !ambientHere ->
                skip = "namespace ${segments.joinToString(".")} has a runtime body - only an ambient namespace is generated"
            topLevel -> {
                header = "namespace ${segments[0]} - members rendered at top level; " +
                    "@JsModule/@JsQualifier wiring is a later rung"
                bodyPath = segments.drop(1)
                rootName = segments[0]
            }
            else -> bodyPath = path + segments
        }
        namespaces += NamespaceEntry(
            node,
            ownerPath = path,
            header = header,
            bodyPath = bodyPath,
            skip = skip,
            rootName = rootName,
            ordinal = ordinal(),
        )
        if (skip == null && block != null) {
            val exportContext = block.statements.none { it is ExportDeclaration || it is ExportAssignment }
            // (EXT.19) A string-named block is a MODULE: its own imports
            // join the visible ones, and what it re-routes is recorded.
            var bodySpecifier = moduleSpecifier
            var bodyImports = imports
            if (name is StringLiteralNode) {
                val module = modules.getOrPut(name.text) { AmbientModule() }
                bodySpecifier = name.text
                bodyImports = namespaceImportsOf(block.statements, HashMap(imports), module)
                for (statement in block.statements) {
                    when (statement) {
                        is ExportDeclaration ->
                            if (statement.exportClause == null) {
                                (statement.moduleSpecifier as? StringLiteralNode)?.text?.let { module.starExports += it }
                            }
                        is ExportAssignment ->
                            if (statement.isExportEquals) {
                                (statement.expression as? Identifier)?.text?.let { module.exportEquals += it }
                            }
                        else -> {}
                    }
                }
            }
            scan(
                block.statements,
                bodyPath,
                topLevel = false,
                exportContext = exportContext,
                ambient = ambientHere,
                inModule = inModule || name is StringLiteralNode,
                fileName = fileName,
                moduleSpecifier = bodySpecifier,
                imports = bodyImports,
                // (EXT.20) Only a string-named block can hold an `export =`
                // (TS1194 in a namespace body); its targets are its surface.
                exportEqualsTargets = if (name is StringLiteralNode) exportEqualsTargetsOf(block.statements) else emptySet(),
            )
        }
    }

    /** The identifier segments of a namespace name — `A` or the dotted `A.B.C` — or null. */
    private fun namespaceSegments(name: Expression): List<String>? = when (name) {
        is Identifier -> listOf(name.text)
        is PropertyAccessExpression -> namespaceSegments(name.expression)?.plus(name.name.text)
        else -> null
    }

}

/**
 * Collects exported interfaces as the checker walks past their declarations.
 *
 * Everything the lens is asked happens INSIDE [declaration] — a [CheckedLens]
 * is valid only for the duration of the callback that received it, so member
 * types are resolved and mapped to Kotlin TEXT on the spot, and the model
 * retains no checker object at all.
 *
 * (EXT.13) Declarations are collected into a tree of SCOPES ([ScopeBuilder]):
 * the module surface at the root, one builder per nested namespace, created
 * on first sight — at the namespace's own callback, or at its first member's
 * — and frozen into an [ExternalObject] by [finish]. A flattened root
 * namespace's members land in the root scope beside the file's own
 * top-level declarations, in walk order.
 */
private class ExternalsCollector(
    private val surface: Surface,
    /** (EXT.16) The module wiring's surface plan, or null in global-script mode. */
    private val plan: ExportPlan?,
) : CheckedNodeSink {

    /**
     * One collected entry: the declaration, the file it came from (the
     * same-scope collision wording) and (EXT.16) the declaration NODE, what
     * the plan decides a root declaration's [Reach] from.
     */
    private class Collected(
        val declaration: ExternalDeclaration,
        val fileName: String?,
        val node: Node?,
        /** (EXT.20) The declaration's scan position ([Declared.ordinal]) — the merge rule's key; -1 for a statement or marker. */
        val ordinal: Int,
    ) : Entry

    private sealed interface Entry

    /**
     * (EXT.13) One namespace scope under construction; [path] is its own
     * qualified path, [fileName] the file of the declaration that created it
     * ((EXT.19) — what the same-scope collision wording reads, so a class
     * merging with its namespace in one file is named as the merge it is).
     */
    private class ScopeBuilder(val name: String?, val path: List<String>, val fileName: String?) : Entry {
        val entries = mutableListOf<Entry>()
        val children = HashMap<String, ScopeBuilder>()
    }

    private val root = ScopeBuilder(null, emptyList(), null)

    /** The scope at [path], every object on the way created and appended to its parent on first sight. */
    private fun scope(path: List<String>, fileName: String?): ScopeBuilder {
        if (path.isEmpty()) return root
        val parent = scope(path.dropLast(1), fileName)
        return parent.children.getOrPut(path.last()) {
            ScopeBuilder(path.last(), path, fileName).also { parent.entries.add(it) }
        }
    }

    private fun add(path: List<String>, node: Node?, declaration: ExternalDeclaration, ordinal: Int = -1) {
        val fileName = node?.let(::fileNameOf)
        scope(path, fileName).entries.add(Collected(declaration, fileName, node, ordinal))
    }

    /**
     * (EXT.20) The scan position of a namespace scope — the FIRST entry
     * declaring the namespace at [path] or a namespace below it (a dotted
     * `A.B` declares `A` too; a namespace declared in two blocks is one
     * scope, and the first block is the one [mergeGroupFirst] recorded).
     */
    private fun namespaceOrdinal(path: List<String>): Int =
        surface.namespaces
            .filter { it.skip == null && it.bodyPath.size >= path.size && it.bodyPath.subList(0, path.size) == path }
            .minOfOrNull { it.ordinal } ?: -1

    /** (EXT.20) The scan position of a scope entry; -1 where it has none. */
    private fun ordinalOf(entry: Entry): Int = when (entry) {
        is Collected -> entry.ordinal
        is ScopeBuilder -> namespaceOrdinal(entry.path)
    }

    /** The file a node was parsed from, by [NodeBase.parent] up to the [SourceFile]. */
    private fun fileNameOf(node: Node): String? {
        var current: Node? = node
        while (current != null && current !is SourceFile) current = (current as? NodeBase)?.parent
        return current?.fileName
    }

    /**
     * (EXT.7) The collected declarations after the whole-program passes
     * that can only run once every file has been walked: the top-level
     * function OVERLOAD collapse (the (EXT.5) rule, applied to the module
     * surface — the declarations mapping to one Kotlin signature are one
     * class, wherever in the walk they sit, and all but its survivor become
     * markers naming it; the signature is the measured [overloadSignature],
     * (EXT.11c), the survivor [overloadWinners]' least-marked pick,
     * (EXT.12)), and the
     * cross-file NAME rules: every file's declarations share one Kotlin
     * package, so a second interface/class/enum/alias spelling a name an
     * earlier one already took is a loud skip rather than a redeclaration
     * the consumer's compiler refuses, and so is a second VALUE of one name.
     *
     * (EXT.13) Every rule here is PER SCOPE — Kotlin's overload-conflict,
     * redeclaration and value-vs-type rules are each about one scope, so a
     * nested object's declarations are reduced among themselves and the
     * root's among themselves; [Inheritance] alone is built over the whole
     * tree, because a supertype may name a type in another scope. A
     * namespace OBJECT takes a type name in its scope (Kotlin cannot hold an
     * `object` and an `interface` of one name; TypeScript merges them), and
     * a value colliding with it is skipped like one colliding with a type.
     * A name declared twice in one scope by ONE file is TypeScript's
     * declaration MERGING (`interface Node` appears twice in `typescript.d.ts`'s
     * root namespace); the loud skip says so, where a second file's is
     * the cross-file wording.
     *
     * (EXT.11c) The VALUE-vs-TYPE rule, measured against the metadata
     * compiler: Kotlin cannot hold a top-level `val`/`var` and a type
     * (interface, class, the enum's sealed interface, a typealias) of one
     * name in one package — `Conflicting declarations` — where TypeScript
     * pairs them routinely (`export interface AjaxError …` beside `export
     * declare const AjaxError: AjaxErrorCtor`, the companion-value idiom
     * behind every RxJS error class). The TYPE wins whatever the walk order
     * (the type names are collected first), and the value is a loud skip
     * naming the type it collides with; the `@JsName` wiring that could
     * rename it is the module-wiring rung. A FUNCTION is different: `fun
     * Foo()` beside `interface Foo` is legal Kotlin, and beside a CLASS (or
     * an alias to one) it is legal too UNLESS its signature is the class's
     * constructor's — `fun Foo(x: String)` beside `class Foo(y: String)` is
     * `Conflicting overloads` with the constructor, with the class's type
     * parameters counting as the constructor's own (`fun <T> Foo(x: T)`
     * conflicts with `class Foo<T>(x: T)`, `fun Foo(x: Any?)` does not),
     * and a class declaring no constructor exposing the implicit `Foo()`.
     * So a function is skipped only on that measured collision, by the same
     * [overloadSignature] the collapse uses, against the class's EFFECTIVE
     * constructor ([Inheritance.effectiveConstructor], the inherited one
     * counts — it is what the class header renders). A `typealias` exposes
     * the aliased class's constructors the same way: `typealias Foo = String`
     * beside `fun Foo()` conflicts (`String()` exists; `Double`/`Boolean`
     * measured alike, a nullable alias body too), an alias to a generated
     * class beside a function spelling that class's constructor conflicts,
     * an alias to a function type or to an interface conflicts with nothing.
     *
     * (EXT.20) DECLARATION MERGING — the one exception to "declared again
     * in the same scope": the declarations of one name ONE FILE declares
     * that TypeScript merges into one symbol and Kotlin can hold as one
     * declaration are rendered as that declaration ([mergedDeclaration]),
     * the lead at the FIRST declaration's position, the others absorbed
     * into it and the class/interface/enum header saying so loudly. Which
     * declarations group is decided ONCE, from the scan, by
     * [mergeGroupFirst] — the same table [ownsName] reads, so a reference
     * to any declaration of the group spells the merged one. A group
     * member that was REFUSED (a callable interface with an unmappable
     * signature) stays a marker at its own position and is not merged.
     * Across files nothing merges (a module file is its own scope); a
     * second interface block of one name is still the loud skip.
     *
     * (EXT.18) THE RENAME — under a [ModuleWiring], the two (EXT.11c)
     * collisions render instead of skipping: a VALUE sharing a type's or a
     * namespace object's name is `<Name>Value`, a FUNCTION whose signature
     * is a same-named class's constructor is `<Name>Fn`, each bound to its
     * JavaScript name by `@JsName` ([Rename] states the scheme). Measured
     * against Kotlin/JS 2.4.10 (`KotlinExternalsJsGateTest`): `@JsName
     * ("AjaxError") external val AjaxErrorValue` beside `external interface
     * AjaxError` compiles, and so do the alias, enum, class-constructor
     * (`@JsName("Foo") fun FooFn` beside `class Foo` and its other
     * overloads) and object (`@JsName("path") val pathValue` beside `object
     * path`) forms, at the top level and inside an `external object`; the
     * gate variant carries the new name and no annotation. A value and a
     * type of one name are NOT a TypeScript merge (a `const` merges with
     * nothing — the two live in different meaning spaces), so the pair
     * never enters [mergeGroupFirst]; the value is renamed and the type is
     * untouched. The suffixed name must be FREE in the scope — every name
     * the scope declares, of any kind, is checked — or the loud skip stays,
     * naming the taken suffix. Without a wiring the skips stay as they were:
     * a rename with no `@JsName` would silently change what the consumer
     * binds. A root declaration's binding is what its [Reach] says (a
     * re-export under another name is that name; the `export =` target is
     * the module object, no `@JsName`); a nested object's member binds its
     * own TypeScript name.
     */
    fun finish(): List<ExternalDeclaration> {
        val inheritance = Inheritance(freeze(root))
        return reduce(root, inheritance)
    }

    /**
     * (EXT.20) The MERGE GROUPS of one scope: entry index → the indices of
     * the entries rendered as one declaration, the lead (the first) among
     * them. A group needs at least two of: a class, an interface, an enum
     * ([Collected] entries whose declaration is that kind — a refused one
     * is a marker and stays out) and a namespace scope, all sharing one
     * [mergeGroupFirst] answer.
     */
    private fun scopeGroups(scope: ScopeBuilder): Map<Int, List<Int>> {
        val byFirst = HashMap<Int, MutableList<Int>>()
        scope.entries.forEachIndexed { index, entry ->
            val mergeable = when (entry) {
                is ScopeBuilder -> true
                is Collected -> entry.declaration is ExternalClass ||
                    entry.declaration is ExternalInterface ||
                    entry.declaration is ExternalEnum
            }
            if (!mergeable) return@forEachIndexed
            val first = mergeGroupFirst[ordinalOf(entry)] ?: return@forEachIndexed
            byFirst.getOrPut(first) { mutableListOf() }.add(index)
        }
        val groups = HashMap<Int, List<Int>>()
        for (indices in byFirst.values) {
            if (indices.size < 2) continue
            for (index in indices) groups[index] = indices
        }
        return groups
    }

    /** The scope tree as collected, nested objects included, before any per-scope rule. */
    private fun freeze(scope: ScopeBuilder): List<ExternalDeclaration> {
        val groups = scopeGroups(scope)
        return scope.entries.mapIndexedNotNull { index, entry ->
            val group = groups[index]
            when {
                group != null && group.first() != index -> null
                group != null -> mergedDeclaration(group.map { scope.entries[it] }) { freeze(it) }
                entry is Collected -> entry.declaration
                entry is ScopeBuilder -> ExternalObject(entry.name!!, entry.path.dropLast(1), freeze(entry))
                else -> null
            }
        }
    }

    private fun reduce(scope: ScopeBuilder, inheritance: Inheritance): List<ExternalDeclaration> {
        val entries = scope.entries
        // (EXT.12) The whole scope's functions, so an equivalence class
        // that spans files (rxjs's two `zip`s) or interleaves with another
        // is collected before its survivor is picked.
        val functions = entries.map { ((it as? Collected)?.declaration as? ExternalTopLevelFunction) }
        val winners = overloadWinners(functions)
        // (EXT.20) The merged declarations, each under its lead's index.
        val groups = scopeGroups(scope)
        val merged = HashMap<Int, ExternalDeclaration>()
        for ((index, group) in groups) {
            if (group.first() == index) merged[index] = mergedDeclaration(group.map { entries[it] }) { reduce(it, inheritance) }
        }
        /** Type names taken in this scope, each with the file that took it. */
        val seenTypeNames = HashMap<String, String?>()
        val seenValueNames = HashMap<String, String?>()
        val typeDeclarations = HashMap<String, ExternalDeclaration>()
        // (EXT.18) Every name the scope declares, of any kind — what a
        // renamed declaration's suffixed name must not collide with.
        val takenNames = HashSet<String>()
        entries.forEachIndexed { index, entry ->
            if (index in groups && index !in merged) return@forEachIndexed
            val (name, declaration) = when {
                index in merged -> typeNameOf(merged.getValue(index)) to merged.getValue(index)
                entry is ScopeBuilder -> entry.name to ExternalObject(entry.name!!, entry.path.dropLast(1), emptyList())
                entry is Collected -> typeNameOf(entry.declaration) to entry.declaration
                else -> null to entry
            }
            if (name != null && declaration is ExternalDeclaration) typeDeclarations.putIfAbsent(name, declaration)
            name?.let(takenNames::add)
            when (declaration) {
                is ExternalTopLevelValue -> takenNames += declaration.name
                is ExternalTopLevelFunction -> takenNames += declaration.name
                else -> {}
            }
        }
        return entries.mapIndexedNotNull { index, entry ->
            val group = groups[index]
            val reduced = when {
                group != null && group.first() != index -> return@mapIndexedNotNull null
                group != null -> reduceMerged(merged.getValue(index), group.map { entries[it] }, inheritance, seenTypeNames)
                else -> reduceEntry(
                    entry, index, scope, inheritance, functions, winners, seenTypeNames, seenValueNames,
                    typeDeclarations, takenNames,
                )
            }
            when {
                plan == null -> reduced
                scope.path.isEmpty() -> wired(group?.let { g -> bindingEntry(g.map { entries[it] }, reduced) } ?: entry, reduced, plan)
                else -> nestedBinding(reduced)
            }
        }
    }

    /**
     * (EXT.18) The binding of a NESTED object's member: nothing, as (EXT.16)
     * decided — the object's binding is the member's — except for a renamed
     * one, which spells its TypeScript name through `@JsName` and says why.
     */
    private fun nestedBinding(reduced: ExternalDeclaration): ExternalDeclaration {
        val rename = renameOf(reduced) ?: return reduced
        val kind = if (reduced is ExternalTopLevelFunction) "function" else "value"
        return withBinding(reduced, JsBinding(rename.from, listOf(renameMarker(kind, rename, kotlinNameOf(reduced), bound = true))))
    }

    /** (EXT.18) The rename a reduced declaration carries, or null. */
    private fun renameOf(declaration: ExternalDeclaration): Rename? = when (declaration) {
        is ExternalTopLevelValue -> declaration.rename
        is ExternalTopLevelFunction -> declaration.rename
        else -> null
    }

    private fun kotlinNameOf(declaration: ExternalDeclaration): String = when (declaration) {
        is ExternalTopLevelValue -> declaration.name
        is ExternalTopLevelFunction -> declaration.name
        else -> ""
    }

    /** (EXT.18) The loud record above a renamed declaration; [bound] says whether a `@JsName` follows it. */
    private fun renameMarker(kind: String, rename: Rename, name: String, bound: Boolean): String =
        "$kind ${rename.from} renamed $name - ${rename.reason}" + if (bound) "; bound by @JsName" else ""

    /**
     * (EXT.18) The Kotlin-legal name a colliding declaration renders under
     * — [original] with [suffix] — when a wiring is present and the name is
     * free in the scope; null keeps the loud skip. [seen] is the value names
     * taken so far (a second value of one name), [taken] every name the
     * scope declares.
     */
    private fun renamedName(
        original: String,
        suffix: String,
        taken: Set<String>,
        seen: Map<String, String?>,
    ): String? {
        if (plan == null) return null
        val candidate = original + suffix
        return candidate.takeIf { it !in taken && !seen.containsKey(it) }
    }

    /**
     * (EXT.20) A merged declaration under the scope's first-wins rule: it
     * takes the type name as its lead would (a name an EARLIER, non-merging
     * declaration already took — a second interface block ahead of the
     * group — still wins), and its supertypes are pruned as any class's or
     * interface's are.
     */
    private fun reduceMerged(
        merged: ExternalDeclaration,
        members: List<Entry>,
        inheritance: Inheritance,
        seenTypeNames: MutableMap<String, String?>,
    ): ExternalDeclaration {
        val name = typeNameOf(merged) ?: return merged
        if (seenTypeNames.containsKey(name)) {
            return SkippedDeclaration(
                "$name declared again in the same scope - TypeScript merges the declarations, " +
                    "one Kotlin scope cannot hold both"
            )
        }
        seenTypeNames[name] = members.firstNotNullOfOrNull { (it as? Collected)?.fileName }
        return when (merged) {
            is ExternalClass -> prunedHeritage(merged, inheritance)
            is ExternalInterface -> prunedHeritage(merged, inheritance)
            else -> merged
        }
    }

    /** (EXT.20) The group entry whose NODE the wiring reads for the merged declaration — the class's, the interface's, the enum's. */
    private fun bindingEntry(members: List<Entry>, merged: ExternalDeclaration): Entry =
        members.firstOrNull { entry ->
            val declaration = (entry as? Collected)?.declaration
            when (merged) {
                is ExternalClass -> declaration is ExternalClass
                is ExternalInterface -> declaration is ExternalInterface
                is ExternalEnum -> declaration is ExternalEnum
                else -> false
            }
        } ?: members.first()

    /**
     * (EXT.20) ONE Kotlin declaration for the TypeScript declarations of
     * one name in one file that merge — measured against the Kotlin/JS
     * compiler (`KotlinExternalsJsGateTest`, the metadata compiler in
     * `KotlinOverloadEquivalenceTest`):
     *
     *  - a CLASS with an INTERFACE: the class, the interface's members
     *    joined to the instance side (TypeScript's merged instance type)
     *    and its `extends` bases as the class's `implements` list; a
     *    property the class already declares is a loud skip (TypeScript
     *    requires identical types there), a method joins the overload
     *    collapse. TypeScript requires identical type-parameter lists
     *    (TS2428), so a differing one is a loud skip of the interface's
     *    members, never a wrong substitution;
     *  - a CLASS with a NAMESPACE: the namespace's VALUES and FUNCTIONS in
     *    the class's `companion object` beside the `static` members (the
     *    overload collapse over both), its TYPES — interfaces, classes,
     *    enums, nested namespace objects, the loud skips of its aliases —
     *    NESTED in the class body, at the namespace's own path;
     *  - an INTERFACE with a NAMESPACE: the interface with the namespace's
     *    values and functions in its companion (accepted on an `external
     *    interface`) and its INTERFACES nested; a class or an object is
     *    refused there (`Interface cannot contain nested classes and
     *    objects`) and is a loud skip inside the interface;
     *  - an ENUM with a NAMESPACE: the sealed interface, the namespace's
     *    values and functions after the entries in its companion, its
     *    interfaces nested, a class or object the same loud skip.
     *
     * A FUNCTION beside a namespace is NOT a merge here: `fun assert(…)`
     * beside `object assert { … }` compiles (measured, both compilers), so
     * both render as they are. The header of the merged declaration
     * records what was merged.
     */
    private fun mergedDeclaration(
        members: List<Entry>,
        declarationsOf: (ScopeBuilder) -> List<ExternalDeclaration>,
    ): ExternalDeclaration {
        val klass = members.firstNotNullOfOrNull { (it as? Collected)?.declaration as? ExternalClass }
        val iface = members.firstNotNullOfOrNull { (it as? Collected)?.declaration as? ExternalInterface }
        val enum = members.firstNotNullOfOrNull { (it as? Collected)?.declaration as? ExternalEnum }
        val namespace = members.firstNotNullOfOrNull { it as? ScopeBuilder }
        val name = klass?.name ?: iface?.name ?: enum?.name ?: namespace!!.name!!
        val statics = mutableListOf<ExternalMember>()
        val nested = mutableListOf<ExternalDeclaration>()
        for (declaration in namespace?.let(declarationsOf).orEmpty()) {
            // (EXT.18) A member the namespace scope RENAMED binds its
            // TypeScript name through `@JsName`, which a companion member
            // does not carry here — the collision it was renamed for is
            // between the namespace's own declarations; loud, never a
            // silently rebound companion member.
            val rename = renameOf(declaration)
            when {
                rename != null -> statics += SkippedMember(
                    "${if (declaration is ExternalTopLevelFunction) "function" else "value"} ${rename.from} of the merged " +
                        "namespace $name - ${rename.reason}, and a companion member carries no @JsName"
                )
                declaration is ExternalTopLevelFunction -> statics += ExternalFunction(
                    declaration.name, declaration.typeParameters, declaration.markers, declaration.parameters, declaration.returnType,
                )
                declaration is ExternalTopLevelValue -> statics += ExternalProperty(declaration.name, declaration.type, declaration.readOnly)
                else -> nested += declaration
            }
        }
        val mergedWith = buildList {
            if (klass != null && iface != null) add("the interface $name")
            if (namespace != null) add("the namespace $name")
        }
        val marker = "merged with ${mergedWith.joinToString(" and ")} of this scope - TypeScript declaration merging"
        if (klass != null) {
            val instanceMembers = klass.members.toMutableList()
            val interfaces = klass.interfaces.toMutableList()
            if (iface != null) {
                if (iface.typeParameters != klass.typeParameters) {
                    instanceMembers += SkippedMember(
                        "interface $name merged with the class declares other type parameters " +
                            "<${iface.typeParameters.joinToString(", ")}> - its members not merged"
                    )
                } else {
                    interfaces += iface.supertypes
                    // The interface's CLASS bases: the class's own superclass
                    // is the same fact and is dropped; any other is a loud
                    // skip — a Kotlin class extends one class, and adopting
                    // it would hand the merged class a constructor and a
                    // prototype the runtime class does not have.
                    for (base in iface.classBases) {
                        if (base == klass.superClass) continue
                        instanceMembers += SkippedMember(
                            "heritage clause extends $base of the merged interface $name - " +
                                "the class extends ${klass.superClass ?: "nothing"}, a Kotlin class extends one class"
                        )
                    }
                    val declared = instanceMembers.filterIsInstance<ExternalProperty>().mapTo(HashSet()) { it.name }
                    for (member in iface.members) {
                        instanceMembers +=
                            if (member is ExternalProperty && member.name in declared) {
                                SkippedMember("property ${member.name} declared again by the merged interface $name")
                            } else {
                                member
                            }
                    }
                }
            }
            val staticMembers = (klass.staticMembers + statics).toMutableList()
            dedupeOverloads(instanceMembers)
            dedupeOverloads(staticMembers)
            return ExternalClass(
                name = name,
                typeParameters = klass.typeParameters,
                headerMarkers = klass.headerMarkers + marker,
                isAbstract = klass.isAbstract,
                superClass = klass.superClass,
                interfaces = interfaces,
                constructorParameters = klass.constructorParameters,
                members = instanceMembers,
                staticMembers = staticMembers,
                path = klass.path,
                binding = klass.binding,
                nested = nested,
            )
        }
        // An interface (a sealed one for an enum) nests interfaces only.
        val nestable = nested.map { declaration ->
            when (declaration) {
                is ExternalClass -> SkippedDeclaration(
                    "class ${declaration.name} of the merged namespace $name - an external interface cannot nest a class or object"
                )
                is ExternalObject -> SkippedDeclaration(
                    "namespace ${declaration.name} of the merged namespace $name - an external interface cannot nest a class or object"
                )
                else -> declaration
            }
        }
        if (iface != null) {
            val staticMembers = statics.toMutableList()
            dedupeOverloads(staticMembers)
            return ExternalInterface(
                name = name,
                typeParameters = iface.typeParameters,
                headerMarkers = iface.headerMarkers + marker,
                supertypes = iface.supertypes,
                members = iface.members,
                path = iface.path,
                staticMembers = staticMembers,
                nested = nestable,
            )
        }
        val staticMembers = statics.toMutableList()
        dedupeOverloads(staticMembers)
        return ExternalEnum(
            name = name,
            entries = enum!!.entries,
            markers = enum.markers + marker,
            binding = enum.binding,
            staticMembers = staticMembers,
            nested = nestable,
        )
    }

    /** One entry of [reduce]'s fold — the per-scope rules its KDoc lists, applied to [entry]. */
    private fun reduceEntry(
        entry: Entry,
        index: Int,
        scope: ScopeBuilder,
        inheritance: Inheritance,
        functions: List<ExternalTopLevelFunction?>,
        winners: IntArray,
        seenTypeNames: MutableMap<String, String?>,
        seenValueNames: MutableMap<String, String?>,
        typeDeclarations: Map<String, ExternalDeclaration>,
        /** (EXT.18) Every name the scope declares — a rename's suffixed name must be free. */
        takenNames: Set<String>,
    ): ExternalDeclaration =
        when (entry) {
            is ScopeBuilder -> {
                val name = entry.name!!
                if (seenTypeNames.containsKey(name)) {
                    SkippedDeclaration("namespace $name declared again in the same scope - one Kotlin scope cannot hold both")
                } else {
                    seenTypeNames[name] = entry.fileName
                    ExternalObject(name, entry.path.dropLast(1), reduce(entry, inheritance))
                }
            }
            is Collected -> when (val declaration = entry.declaration) {
                is ExternalTopLevelFunction -> {
                    val signature = overloadSignature(
                        declaration.name,
                        declaration.typeParameters,
                        declaration.parameters,
                    )
                    val collidingType = typeDeclarations[declaration.name]
                    val winner = winners[index]
                    when {
                        winner != index -> SkippedDeclaration(overloadCollapseDescription(functions[winner]!!))
                        collidingType != null &&
                            signature in constructorSignatures(collidingType, inheritance, scope.path) -> {
                            // (EXT.18) `<Name>Fn`, bound to `<Name>` — the
                            // class keeps the name its constructor spells.
                            val renamed = renamedName(declaration.name, "Fn", takenNames, emptyMap())
                            when {
                                renamed != null -> ExternalTopLevelFunction(
                                    name = renamed,
                                    typeParameters = declaration.typeParameters,
                                    markers = declaration.markers,
                                    parameters = declaration.parameters,
                                    returnType = declaration.returnType,
                                    rename = Rename(
                                        declaration.name,
                                        "its signature is the constructor of ${declaration.name}",
                                    ),
                                )
                                plan == null -> SkippedDeclaration(
                                    "function ${declaration.name} shares its signature with the constructor of " +
                                        "${declaration.name} - module wiring is a later rung"
                                )
                                else -> SkippedDeclaration(
                                    "function ${declaration.name} shares its signature with the constructor of " +
                                        "${declaration.name} and ${declaration.name}Fn is taken too - no Kotlin name to rename it to"
                                )
                            }
                        }
                        else -> declaration
                    }
                }
                is ExternalInterface ->
                    typeNameOnce(entry, declaration.name, seenTypeNames).let { kept ->
                        if (kept !== declaration) kept else prunedHeritage(declaration, inheritance)
                    }
                is ExternalClass ->
                    typeNameOnce(entry, declaration.name, seenTypeNames).let { kept ->
                        if (kept !== declaration) kept else prunedHeritage(declaration, inheritance)
                    }
                is ExternalEnum -> typeNameOnce(entry, declaration.name, seenTypeNames)
                is ExternalTypeAlias -> typeNameOnce(entry, declaration.name, seenTypeNames)
                is ExternalTopLevelValue -> when (val colliding = typeDeclarations[declaration.name]) {
                    null -> typeNameOnce(entry, declaration.name, seenValueNames)
                    else -> {
                        // (EXT.18) `<Name>Value`, bound to `<Name>` — the type
                        // or object keeps the name every other declaration spells.
                        val kind = if (colliding is ExternalObject) "namespace object" else "type"
                        val renamed = renamedName(declaration.name, "Value", takenNames, seenValueNames)
                        when {
                            renamed != null -> {
                                seenValueNames[renamed] = entry.fileName
                                ExternalTopLevelValue(
                                    name = renamed,
                                    type = declaration.type,
                                    readOnly = declaration.readOnly,
                                    rename = Rename(
                                        declaration.name,
                                        "Kotlin cannot hold a value and " +
                                            (if (colliding is ExternalObject) "an object" else "a type") + " of one name",
                                    ),
                                )
                            }
                            plan == null -> SkippedDeclaration(
                                "value ${declaration.name} shares its name with the $kind ${declaration.name}" +
                                    " - module wiring is a later rung"
                            )
                            else -> SkippedDeclaration(
                                "value ${declaration.name} shares its name with the $kind ${declaration.name}" +
                                    " and ${declaration.name}Value is taken too - no Kotlin name to rename it to"
                            )
                        }
                    }
                }
                is SkippedDeclaration, is ExternalMarker, is ExternalObject -> declaration
            }
        }

    /**
     * (EXT.16) The ROOT-scope [reduced] declaration with its [JsBinding]
     * attached — the [ExportPlan]'s [Reach] for the declaration's node
     * rendered as the rules [ModuleWiring]'s KDoc states:
     *
     *  - reachable under its OWN name, spelled as Kotlin spells it: nothing;
     *  - under its own name but backticked in Kotlin (`$`, a hard keyword):
     *    `@JsName` with the JavaScript spelling;
     *  - under ANOTHER name: `@JsName("<exported>")` — `default` for an
     *    `export default`; several names: the FIRST in entry order, a loud
     *    marker listing the others (one `@JsName` per declaration);
     *  - only QUALIFIED (`ns.x`): a marker — `@file:JsQualifier` is a
     *    file-level fact, inexpressible on one module member;
     *  - the `export =` target: no `@JsName`, a marker saying the module
     *    object itself is what a consumer binds (`@JsModule("m") external
     *    <kind> X` in a file of its own);
     *  - a member of a flattened root whose header states the reach: nothing;
     *  - NOT reachable: the marker naming an internal path — the
     *    declaration stays, so types naming it still compile.
     *
     * A skipped or collapsed declaration, a type and a marker carry no
     * binding; a nested object's members inherit the object's.
     *
     * (EXT.18) A RENAMED declaration's reach is asked under its TypeScript
     * name, and its binding is what that reach says with the Kotlin name
     * out of the picture: the first exported name (its own, or another
     * under a re-export), the TypeScript name where the reach is qualified,
     * via a header or unreachable (the honest JavaScript spelling, whatever
     * the file a consumer ends up binding it in), and nothing for the
     * `export =` module object. The rename marker comes first.
     */
    private fun wired(entry: Entry, reduced: ExternalDeclaration, plan: ExportPlan): ExternalDeclaration {
        val (node, name, kind) = when (reduced) {
            is ExternalTopLevelFunction -> Triple((entry as? Collected)?.node, reduced.name, "function")
            is ExternalClass -> Triple((entry as? Collected)?.node, reduced.name, "class")
            is ExternalEnum -> Triple((entry as? Collected)?.node, reduced.name, "enum")
            is ExternalTopLevelValue -> Triple((entry as? Collected)?.node, reduced.name, "value")
            is ExternalObject -> Triple(
                surface.namespaces.firstOrNull { it.skip == null && it.bodyPath == listOf(reduced.name) }?.node,
                reduced.name,
                "namespace object",
            )
            else -> return reduced
        }
        if (node == null) return reduced
        val rename = renameOf(reduced)
        val tsName = rename?.from ?: name
        val markers = mutableListOf<String>()
        var jsName: String? = null
        when (val reach = plan.reachOf(node, tsName)) {
            is Reach.Names -> {
                val first = reach.names.first()
                if (rename != null || first != tsName || kotlinIdentifier(tsName) != tsName) jsName = first
                if (reach.names.size > 1) {
                    markers += "$kind $tsName is also exported as ${reach.names.drop(1).joinToString(", ")} - " +
                        "one @JsName per declaration, bound as $first"
                }
            }
            is Reach.Qualified -> {
                if (rename != null) jsName = tsName
                markers += "$kind $tsName is exported only as ${reach.paths.joinToString(", ")} - " +
                    "a nested export needs @file:JsQualifier in a file of its own"
            }
            is Reach.ModuleObject ->
                markers += "export = $tsName - $tsName is the module object itself: bind it as " +
                    "@JsModule(\"${plan.moduleName}\") external $kind $name in a file of its own, no @JsName"
            is Reach.ViaHeader -> if (rename != null) jsName = tsName
            is Reach.Unreachable -> {
                if (rename != null) jsName = tsName
                markers += "$kind $tsName is not exported by the package entry - an internal path a consumer cannot bind"
            }
        }
        if (rename != null) markers.add(0, renameMarker(kind, rename, name, bound = jsName != null))
        if (jsName == null && markers.isEmpty()) return reduced
        return withBinding(reduced, JsBinding(jsName, markers))
    }

    /**
     * (EXT.13) [declaration] with the supertypes [Inheritance.prunedSupertypes]
     * drops removed and their loud markers appended to the members — the
     * model the renderer then reads, so its own [Inheritance] finds nothing
     * left to prune.
     */
    private fun prunedHeritage(declaration: ExternalInterface, inheritance: Inheritance): ExternalDeclaration {
        val pruned = inheritance.prunedSupertypes(declaration)
        if (pruned.markers.isEmpty()) return declaration
        return ExternalInterface(
            name = declaration.name,
            typeParameters = declaration.typeParameters,
            headerMarkers = declaration.headerMarkers,
            supertypes = pruned.interfaces,
            members = declaration.members + pruned.markers,
            path = declaration.path,
            staticMembers = declaration.staticMembers,
            nested = declaration.nested,
            classBases = declaration.classBases,
        )
    }

    private fun prunedHeritage(declaration: ExternalClass, inheritance: Inheritance): ExternalDeclaration {
        val pruned = inheritance.prunedSupertypes(declaration)
        if (pruned.markers.isEmpty()) return declaration
        return ExternalClass(
            name = declaration.name,
            typeParameters = declaration.typeParameters,
            headerMarkers = declaration.headerMarkers,
            isAbstract = declaration.isAbstract,
            superClass = pruned.superClass,
            interfaces = pruned.interfaces,
            constructorParameters = declaration.constructorParameters,
            members = declaration.members + pruned.markers,
            staticMembers = declaration.staticMembers,
            path = declaration.path,
            binding = declaration.binding,
            nested = declaration.nested,
        )
    }

    /** The name a declaration takes in its scope's TYPE namespace, or null. */
    private fun typeNameOf(declaration: ExternalDeclaration): String? = when (declaration) {
        is ExternalInterface -> declaration.name
        is ExternalClass -> declaration.name
        is ExternalEnum -> declaration.name
        is ExternalTypeAlias -> declaration.name
        is ExternalObject -> declaration.name
        else -> null
    }

    private fun typeNameOnce(
        entry: Collected,
        name: String,
        seen: MutableMap<String, String?>,
    ): ExternalDeclaration {
        if (!seen.containsKey(name)) {
            seen[name] = entry.fileName
            return entry.declaration
        }
        val first = seen[name]
        return if (first != null && first == entry.fileName) {
            SkippedDeclaration(
                "$name declared again in the same scope - TypeScript merges the declarations, " +
                    "one Kotlin scope cannot hold both"
            )
        } else {
            SkippedDeclaration("$name declared again by another file - one Kotlin package cannot hold both")
        }
    }

    /**
     * (EXT.11c) The [overloadSignature]s of the constructors a same-named
     * FUNCTION would conflict with, under the function's name: a generated
     * class's effective constructor (its type parameters as the constructor's
     * own; the implicit `()` when nothing up the chain declares one), an
     * alias to a Kotlin class with a default constructor (`String`, `Double`,
     * `Boolean` — the primitives an alias body maps to) or to a generated
     * class (that class's constructor read through the alias body's
     * arguments, the alias's own type parameters as the constructor's).
     * Empty for an interface, an enum, an alias to a function type, an object.
     */
    private fun constructorSignatures(
        type: ExternalDeclaration,
        inheritance: Inheritance,
        fromPath: List<String>,
    ): Set<String> =
        when (type) {
            is ExternalClass -> setOf(
                overloadSignature(
                    type.name,
                    type.typeParameters,
                    inheritance.effectiveConstructor(type).orEmpty(),
                )
            )
            is ExternalTypeAlias -> {
                val body = parseKotlinTypeText(type.body) as? NamedTypeText
                when {
                    body == null -> emptySet()
                    body.arguments.isEmpty() && body.name in kotlinClassesWithDefaultConstructor ->
                        setOf(overloadSignature(type.name, type.typeParameters, emptyList()))
                    else -> {
                        val target = inheritance.declarationNamed(body.name, fromPath) as? ExternalClass
                        if (target == null) emptySet()
                        else {
                            val arguments = body.arguments.map { it.toKotlinText() }
                            val substitution =
                                if (arguments.size == target.typeParameters.size) target.typeParameters.zip(arguments).toMap()
                                else emptyMap()
                            val parameters = inheritance.effectiveConstructor(target).orEmpty().map {
                                ExternalParameter(it.name, substituteTypeParameters(it.type, substitution), it.vararg)
                            }
                            setOf(overloadSignature(type.name, type.typeParameters, parameters))
                        }
                    }
                }
            }
            else -> emptySet()
        }

    /** (EXT.11c) The Kotlin built-ins an alias body maps to that expose a default constructor. */
    private val kotlinClassesWithDefaultConstructor = setOf("String", "Double", "Boolean")

    /** (EXT.13) One generated type the naming set may spell: its declaration node, site, name and scan position. */
    private class Nameable(val node: Node, val site: Site, val name: String, val ordinal: Int) {
        val path: List<String> get() = site.path
    }

    /**
     * (EXT.4) The NAMING set — the declarations a resolved type may be
     * rendered by NAME against. Interfaces, classes (an instance type is a
     * [Type.Interface] whose symbol declares the class) and enums (a
     * member-less [Type.Object] carrying the enum symbol). A `const` enum is
     * excluded: its declaration is refused (no runtime object), so a name
     * pointing at it would reference a type the generated module does not
     * declare. (EXT.13) Each carries its namespace path, the spelling's input.
     *
     * (EXT.14) The namespace LADDER of (EXT.13) — a third syntactic arm that
     * resolved a qualified name, or ANY name written inside a namespace
     * body, over the surface's own per-file tree BEFORE the checker was
     * asked, because the lens was measured wrong there — is retired as the
     * resolver: (CHK.76) made every non-walk resolver position-derived
     * (`lookupInEnclosingNamespaces`, tsc's `resolveName` `ModuleDeclaration`
     * arm). Measured on `typescript.d.ts` (11,448 lines): with the lens
     * consulted FIRST and the ladder only on a miss, the output is byte for
     * byte HEAD's (9,791 lines, 1,750 markers, 847 unmapped, 9 heritage
     * skips, 0 compile errors); with the ladder REMOVED it differs by ONE
     * hunk — `interface InstallTypingHost extends JsTyping.TypingResolutionHost`
     * (line 2679) loses its supertype — and every hermetic namespace pin
     * still holds. What survives is [writtenTarget], a program-wide
     * fallback consulted only after every lens leg answered null, and only
     * for the shapes the lens still cannot answer.
     */
    private val nameableDeclarations: List<Nameable> =
        surface.interfaces.map { Nameable(it.node, it.site, it.node.name.text, it.ordinal) } +
            surface.classes.mapNotNull { d -> d.node.name?.let { Nameable(d.node, d.site, it.text, d.ordinal) } } +
            surface.enums.filterNot { ModifierFlag.Const in it.node.modifiers }
                .map { Nameable(it.node, it.site, it.node.name.text, it.ordinal) }

    /** (EXT.20) What KIND of declaration a name candidate is — the merge rule's input. */
    private enum class NameKind { CLASS, INTERFACE, ENUM, NAMESPACE, ALIAS }

    /**
     * (EXT.19) One candidate for a qualified NAME in its Kotlin scope — a
     * generated type, a namespace object, or a root alias — with the scan
     * position [ExternalsCollector.finish]'s first-wins rule orders by,
     * (EXT.20) its kind and the file declaring it.
     */
    private class NameCandidate(
        val ordinal: Int,
        val nameable: Nameable?,
        val alias: TypeAliasDeclaration?,
        val kind: NameKind,
        val fileName: String?,
    )

    /** (EXT.19) Every name candidate by qualified name, scan order. */
    private val nameCandidates: Map<String, List<NameCandidate>> = HashMap<String, MutableList<NameCandidate>>().apply {
        fun add(qualified: String, candidate: NameCandidate) {
            getOrPut(qualified) { mutableListOf() }.add(candidate)
        }
        for (nameable in nameableDeclarations) {
            val kind = when (nameable.node) {
                is ClassDeclaration -> NameKind.CLASS
                is EnumDeclaration -> NameKind.ENUM
                else -> NameKind.INTERFACE
            }
            add(
                qualifiedName(nameable.path, nameable.name),
                NameCandidate(nameable.ordinal, nameable, null, kind, fileNameOf(nameable.node)),
            )
        }
        for (entry in surface.namespaces) {
            if (entry.skip != null) continue
            for (depth in 1..entry.bodyPath.size) {
                add(
                    qualifiedName(entry.bodyPath.take(depth - 1), entry.bodyPath[depth - 1]),
                    NameCandidate(entry.ordinal, null, null, NameKind.NAMESPACE, fileNameOf(entry.node)),
                )
            }
        }
        for (alias in surface.aliases) {
            if (alias.path.isEmpty()) {
                add(alias.node.name.text, NameCandidate(alias.ordinal, null, alias.node, NameKind.ALIAS, fileNameOf(alias.node)))
            }
        }
        for (candidates in values) candidates.sortBy { it.ordinal }
    }

    /**
     * (EXT.20) Scan ordinal → the FIRST ordinal of its MERGE GROUP, for
     * every declaration that merges with another; absent for one that
     * merges with nothing. Decided once, from the scan, by TypeScript's
     * declaration-merging rule restricted to what Kotlin can render as one
     * declaration ([mergedDeclaration]): among the candidates of one
     * qualified name, in scan order, the first opens the group and a later
     * one joins it when it sits in the SAME FILE and the group has no
     * declaration of its kind yet — at most one class, one interface, one
     * enum and one namespace, a class and an enum never together, an
     * interface and an enum never together (Duplicate identifier in
     * TypeScript), and an alias never (TypeScript merges no alias). A
     * candidate that does not join is what it was: the later declaration
     * of a taken name. Read by [ownsName] and by [scopeGroups], so the
     * reference side and the rendering side cannot disagree about which
     * declarations are one.
     */
    private val mergeGroupFirst: Map<Int, Int> = HashMap<Int, Int>().apply {
        for (candidates in nameCandidates.values) {
            val first = candidates.first()
            if (first.kind == NameKind.ALIAS) continue
            val kinds = HashSet<NameKind>()
            kinds += first.kind
            val members = mutableListOf(first.ordinal)
            for (candidate in candidates.drop(1)) {
                if (candidate.fileName != first.fileName || candidate.kind in kinds) continue
                val joins = when (candidate.kind) {
                    NameKind.CLASS -> NameKind.ENUM !in kinds
                    NameKind.INTERFACE -> NameKind.ENUM !in kinds
                    NameKind.ENUM -> NameKind.CLASS !in kinds && NameKind.INTERFACE !in kinds
                    NameKind.NAMESPACE -> true
                    NameKind.ALIAS -> false
                }
                if (!joins) continue
                kinds += candidate.kind
                members += candidate.ordinal
            }
            if (members.size < 2) continue
            for (ordinal in members) put(ordinal, first.ordinal)
        }
    }

    /** (EXT.20) The merge group an ordinal belongs to, named by its first ordinal — itself when it merges with nothing. */
    private fun mergeGroupFirstOf(ordinal: Int): Int = mergeGroupFirst[ordinal] ?: ordinal

    /** (EXT.20) Another nameable of [nameable]'s merge group satisfying [accept] — the class beside the interface — or null. */
    private fun mergedSibling(nameable: Nameable, accept: (Nameable) -> Boolean): Nameable? {
        val group = mergeGroupFirst[nameable.ordinal] ?: return null
        return nameableDeclarations.firstOrNull {
            it !== nameable && it.path == nameable.path && it.name == nameable.name &&
                mergeGroupFirst[it.ordinal] == group && accept(it)
        }
    }

    private val nameOwnerCache = HashMap<String, Int?>()
    private val nameOwnerInProgress = HashSet<String>()

    /**
     * (EXT.19) The scan position of the declaration that KEEPS the qualified
     * name in its Kotlin scope — [finish]'s first-wins rule ([typeNameOnce],
     * a namespace object taking the name) applied ahead of `finish`, over
     * the candidates that will take a name at all: a generated type, an
     * object, an alias that RENDERS (a refused alias or callable interface
     * is a marker and takes nothing). Memoised; a candidate whose
     * renderability asks this very name back (a callable interface whose
     * signature names itself) is counted as taking it.
     */
    private fun nameOwnerOrdinal(qualified: String, lens: CheckedLens): Int? {
        nameOwnerCache[qualified]?.let { return it }
        if (qualified in nameOwnerCache) return null
        val candidates = nameCandidates[qualified] ?: return null
        if (!nameOwnerInProgress.add(qualified)) return candidates.firstOrNull()?.ordinal
        try {
            val owner = candidates.firstOrNull { candidate ->
                val nameable = candidate.nameable
                val alias = candidate.alias
                when {
                    nameable != null -> !isCallableInterface(nameable.node) || interfaceRenderable(nameable.node, lens)
                    alias != null -> aliasRenderable(alias, lens)
                    else -> true
                }
            }?.ordinal
            nameOwnerCache[qualified] = owner
            return owner
        } finally {
            nameOwnerInProgress.remove(qualified)
        }
    }

    /**
     * (EXT.19) Whether [nameable] is the declaration its qualified name
     * denotes in the generated Kotlin — the one [finish] keeps, where a
     * second declaration of the name in one scope (another file's
     * `ReadableStream`, `vm`'s `Module` beside `module`'s namespace object)
     * becomes a loud skip. A reference to the OTHER one must refuse: its
     * spelling would compile against the wrong declaration (measured on
     * `@types/node`: `readable: ReadableStream<R>` against a non-generic
     * `ReadableStream`, `class SourceTextModule : Module` against an object).
     */
    private fun ownsName(nameable: Nameable, lens: CheckedLens): Boolean {
        // (EXT.20) The declarations of one merge group are ONE Kotlin
        // declaration, so each of them owns the name the group's lead does.
        val owner = nameOwnerOrdinal(qualifiedName(nameable.path, nameable.name), lens) ?: return false
        return mergeGroupFirstOf(owner) == mergeGroupFirstOf(nameable.ordinal)
    }

    private val interfaceRenderable = HashMap<Int, Boolean>()
    private val interfaceRenderableInProgress = HashSet<Int>()

    /**
     * (EXT.19) Whether a CALLABLE interface renders — as the function-type
     * alias, or as an interface where the lens refutes the chain guess —
     * rather than refusing (an unmappable signature): decided by running its
     * own collection, memoised by its position in [callableInterfaces]. A
     * reference to a refused one has no Kotlin name to render (measured:
     * nine `Unresolved reference` errors on `stream/web`'s callback
     * interfaces). Re-entered for the same interface, it answers true — the
     * signature names the interface itself, and the name is what renders.
     */
    private fun interfaceRenderable(node: Node, lens: CheckedLens): Boolean {
        val index = callableInterfaces.indexOfFirst { it === node }
        if (index < 0) return true
        interfaceRenderable[index]?.let { return it }
        if (!interfaceRenderableInProgress.add(index)) return true
        try {
            val declared = surface.interfaces.first { it.node === node }
            val renderable = collectInterface(declared.node, lens, declared.site) !is SkippedDeclaration
            interfaceRenderable[index] = renderable
            return renderable
        } finally {
            interfaceRenderableInProgress.remove(index)
        }
    }

    /** The nested aliases whose bodies are being inlined — a self-referential alias refuses. */
    private val aliasesInProgress = mutableListOf<TypeAliasDeclaration>()

    /**
     * (EXT.17) The loud records set aside while a type text was being built
     * — the `this` parameters [functionTypeText] drops, (EXT.19) the defaulted
     * type arguments [filledTypeArguments] supplies as `Any?` — drained into
     * ONE top-level marker by the producer of the finished text
     * ([annotationText]; the alias declarations put it among their markers),
     * and cleared by the same producers before they start, so a marker is
     * attributed to the text it was found in and to no other. Top-level,
     * because a marker INSIDE a type text (`Array<(T) -> Unit` followed by a
     * comment`>`) is what [typeTextWithoutMarker] cannot strip.
     */
    private val deferredMarkers = mutableListOf<String>()

    private fun drainedMarkers(): List<String> {
        if (deferredMarkers.isEmpty()) return emptyList()
        val markers = deferredMarkers.toList()
        deferredMarkers.clear()
        return markers
    }

    /** [text] with the drained receiver markers appended as one top-level marker. */
    private fun withDeferredMarkers(text: String, markers: List<String>): String =
        if (markers.isEmpty()) text else "$text /* xtsc: ${markers.joinToString("; ")} */"

    /**
     * (EXT.13) A USE of a nested alias renders what the alias DENOTES — the
     * Dukat rule, kept by syntax where the RESOLVED body has no Kotlin
     * spelling (a function type, a generic instantiation the mapper cannot
     * express — the (EXT.10) shape one scope down, and a nested alias has no
     * emitted name to fall back on): the alias's body through
     * [annotationTextOrNull], its names resolved by the lens AT THE BODY's
     * own position ((CHK.76): resolution is position-derived, so a name in
     * the body means what it means where the alias was written) and spelled
     * from the USE's scope ([TypeScope.path]), the alias's own type parameters
     * answered as bare names and then substituted by the use's arguments
     * ([substituteTypeParameters]). Exact arity; a body that does not map, or
     * does not parse for the substitution, or names the alias itself, refuses
     * to the marker.
     */
    private fun nestedAliasUse(
        alias: Declared<TypeAliasDeclaration>,
        arguments: List<TypeNode>,
        lens: CheckedLens,
        scope: TypeScope,
    ): String? {
        if (aliasesInProgress.any { it === alias.node }) return null
        val typeParameters = alias.node.typeParameters.orEmpty().map { it.name.text }
        if (arguments.size != typeParameters.size) return null
        aliasesInProgress.add(alias.node)
        try {
            val bodyScope = scope.withTypeParams(typeParameters.toSet())
            val body = annotationTextOrNull(alias.node.type, returnPosition = false, lens = lens, scope = bodyScope)
                ?: return null
            if (typeParameters.isEmpty()) return body
            if (parseKotlinTypeText(body) == null) return null
            val mapped = arguments.map { argument ->
                annotationTextOrNull(argument, returnPosition = false, lens = lens, scope = scope) ?: return null
            }
            return substituteTypeParameters(body, typeParameters.zip(mapped).toMap())
        } finally {
            aliasesInProgress.removeAt(aliasesInProgress.lastIndex)
        }
    }

    /**
     * (EXT.14) The NESTED alias a written type reference names, by the
     * lens's identity evidence ([CheckedLens.typeReferenceSymbol], as
     * [exportedAliasOf] for a root one) — or null: a root alias, a lib
     * alias, a non-exported neighbour and a qualified name all answer null
     * and keep the later legs.
     */
    private fun nestedAliasOf(annotation: TypeReference, lens: CheckedLens): Declared<TypeAliasDeclaration>? {
        val symbol = lens.typeReferenceSymbol(annotation) ?: return null
        for (declaration in symbol.declarations) {
            surface.aliases.firstOrNull { it.path.isNotEmpty() && it.node === declaration }?.let { return it }
        }
        return null
    }

    /**
     * (EXT.13) Every QUALIFIED name this generation declares — the nameable
     * types and the nested namespace objects (each prefix of a nested body
     * path is an object) — the set a Kotlin spelling resolves through
     * ([shortestSpelling]).
     */
    private val declaredQualified: Set<String> = HashSet<String>().apply {
        for (nameable in nameableDeclarations) add(qualifiedName(nameable.path, nameable.name))
        for (entry in surface.namespaces) {
            if (entry.skip != null) continue
            for (depth in 1..entry.bodyPath.size) {
                add(qualifiedName(entry.bodyPath.take(depth - 1), entry.bodyPath[depth - 1]))
            }
        }
    }

    /** (EXT.14) An alias the written-name fallback resolved to: root (emitted) or nested (inlined). */
    private class WrittenAlias(val alias: Declared<TypeAliasDeclaration>)

    /** (EXT.14) The identifiers of the flattened root namespaces — what a qualified `ts.X` drops. */
    private val flattenedRootNames: Set<String> = surface.namespaces.mapNotNullTo(HashSet()) { it.rootName }

    /**
     * (EXT.14) Every qualified name a WRITTEN name may resolve through
     * ([resolveSpelling] over the surface — TypeScript's innermost-first
     * rule is Kotlin's): the generated types and nested objects
     * ([declaredQualified]) plus the aliases, root and nested, which have a
     * TypeScript name whether or not they have a Kotlin one.
     */
    private val writtenNames: Set<String> = HashSet<String>(declaredQualified).apply {
        for (alias in surface.aliases) add(qualifiedName(alias.path, alias.node.name.text))
    }

    /** (EXT.14) Qualified name → what the fallback answers for it, first declared wins (as [ExternalsCollector.finish]). */
    private val writtenTargets: Map<String, Any> = HashMap<String, Any>().apply {
        for (nameable in nameableDeclarations) putIfAbsent(qualifiedName(nameable.path, nameable.name), nameable)
        for (alias in surface.aliases) putIfAbsent(qualifiedName(alias.path, alias.node.name.text), WrittenAlias(alias))
    }

    /**
     * (EXT.14) Whether the written-name fallback is consulted at all for a
     * name at [scope] — the two shapes the lens cannot answer, measured:
     *
     *  - ANY name written inside a `declare module "m"` body: (CHK.76)'s
     *    resolver skips a string-named module on purpose (its block may be
     *    an AUGMENTATION), so the lens types a bare `Widget` declared beside
     *    its use as `any` and answers null for a bare heritage base there
     *    (probe 1 of this round's scratch; the (EXT.13) pins for a module
     *    body). The checker's half: consult the block's own exports where
     *    the specifier resolves to NO program file, i.e. a genuine ambient
     *    module;
     *  - a QUALIFIED name anywhere: [CheckedLens.typeReferenceSymbol]
     *    refuses one by contract, so a qualified reference to an ALIAS whose
     *    resolved body has no Kotlin spelling — `ts.Cb` for `type Cb = () =>
     *    void` written from a nested scope, `server.Gen<number>` from the
     *    root — would keep its marker; and `heritageBaseSymbol`'s Identifier
     *    arm asks `Type | Value` of a dotted base's HEAD where the head is a
     *    NAMESPACE (`JsTyping.TypingResolutionHost` inside
     *    `ts.server.typingsInstaller`, `typescript.d.ts:2679` — the one hunk
     *    the removed arm cost; `JsTyping` declares only interfaces, so it is a
     *    type-only `NamespaceModule` that neither meaning matches), and its
     *    implicit-export rule reads only the OUTERMOST `declare`d namespace
     *    (`extends ts.server.A` across files, probe 8). The checker's half:
     *    `typeReferenceSymbol` through `resolveQualifiedName`, and the
     *    heritage resolver's head meaning and export rule.
     *
     * A BARE name inside a `declare namespace` body is the lens's alone —
     * that is what (CHK.76) closed, and what the (EXT.13) pins and the
     * `typescript.d.ts` census now measure through it.
     */
    private fun writtenApplies(segments: List<String>, scope: TypeScope): Boolean =
        segments.size > 1 || scope.inAmbientModule

    /**
     * (EXT.14) The surface declaration a written name denotes, by
     * TypeScript's lexical rule over the generation's own qualified names:
     * a flattened root's identifier at the head of a qualified name is
     * dropped (the root's members ARE the top level, so the walk restarts
     * at the root scope — a `ts.Node` inside `a` must not find `a.Node`),
     * then [resolveSpelling] from [fromPath]. Program-wide, not per file:
     * TypeScript merges a namespace declared in several files, so a
     * qualified `ts.server.A` written in another file names the same
     * declaration — the (EXT.13) ladder was per-file and answered null
     * there. A [Nameable], a [WrittenAlias], or null.
     */
    private fun writtenTarget(segments: List<String>, scope: TypeScope): Any? {
        // (EXT.19) A namespace import's alias at the head: the module's surface.
        if (segments.size > 1) {
            scope.namespaceImports[segments[0]]?.let { specifier ->
                return moduleMember(specifier, segments.drop(1), HashSet())
            }
        }
        var rest = segments
        var from = scope.path
        if (rest.size > 1 && rest[0] in flattenedRootNames) {
            rest = rest.drop(1)
            from = emptyList()
        }
        val full = resolveSpelling(rest, from, writtenNames) ?: return null
        return writtenTargets[qualifiedName(full.dropLast(1), full.last())]
    }

    /**
     * (EXT.19) The declaration a MODULE'S SURFACE exposes under [segments]
     * (`Socket`, or `promises.X` through a nested object): the block's own
     * exported types and root aliases at that path; then, in this order,
     * what its `export = X` re-routes — the block's namespace `X` (the
     * members at path `X`, and `X` itself for the bare name), or another
     * module through an `import X = require("m")` alias — and what its
     * `export * from "m"` re-exports. First declared wins, cycles are cut by
     * [visited]. Null for a module the generation holds nothing of, which
     * keeps the reference's marker. A [Nameable] or a [WrittenAlias].
     */
    private fun moduleMember(specifier: String, segments: List<String>, visited: MutableSet<String>): Any? {
        if (segments.isEmpty() || !visited.add(specifier)) return null
        fun own(path: List<String>, name: String): Any? {
            nameableDeclarations.firstOrNull {
                it.site.moduleSpecifier == specifier && it.path == path && it.name == name
            }?.let { return it }
            surface.aliases.firstOrNull {
                it.site.moduleSpecifier == specifier && it.path == path && it.node.name.text == name
            }?.let { return WrittenAlias(it) }
            return null
        }
        own(segments.dropLast(1), segments.last())?.let { return it }
        val module = surface.modules[specifier] ?: return null
        for (target in module.exportEquals) {
            own(listOf(target) + segments.dropLast(1), segments.last())?.let { return it }
            if (segments.size == 1 && segments[0] == target) own(emptyList(), target)?.let { return it }
            module.requireAliases[target]?.let { moduleMember(it, segments, visited) }?.let { return it }
        }
        for (reExported in module.starExports) {
            moduleMember(reExported, segments, visited)?.let { return it }
        }
        return null
    }

    /**
     * (EXT.14) The written-name fallback for an ANNOTATION — the last leg
     * of [annotationTextOrNull]: a generated type renders by its shortest
     * spelling from the use's scope with its arguments from their own
     * annotations (the (EXT.6) rule), exact arity; a shadowed one refuses
     * to the marker; a root alias is the (EXT.10) rule's subject; a nested
     * alias inlines its body. Null where the fallback does not apply or the
     * surface holds nothing.
     */
    private fun writtenFallback(
        annotation: TypeNode?,
        returnPosition: Boolean,
        lens: CheckedLens,
        scope: TypeScope,
    ): String? {
        if (annotation !is TypeReference) return null
        val segments = entityNameText(annotation.typeName).split('.')
        if (!writtenApplies(segments, scope)) return null
        return when (val target = writtenTarget(segments, scope)) {
            is Nameable -> {
                val spelling = spellingOf(target, scope.path, lens) ?: return null
                val filled = filledTypeArguments(target, annotation.typeArguments.orEmpty(), lens, scope) ?: return null
                deferredMarkers += filled.markers
                if (filled.texts.isEmpty()) spelling else "$spelling<${filled.texts.joinToString(", ")}>"
            }
            is WrittenAlias ->
                if (target.alias.path.isEmpty()) rootAliasUse(target.alias.node, annotation, returnPosition, lens, scope)
                else nestedAliasUse(target.alias, annotation.typeArguments.orEmpty(), lens, scope)
            else -> null
        }
    }

    /** The declared type parameters of a nameable's declaration. */
    private fun typeParameterNodesOf(node: Node): List<TypeParameter> = when (node) {
        is InterfaceDeclaration -> node.typeParameters.orEmpty()
        is ClassDeclaration -> node.typeParameters.orEmpty()
        else -> emptyList()
    }

    /** The declared type-parameter count of a nameable's declaration. */
    private fun typeParameterCountOf(node: Node): Int = typeParameterNodesOf(node).size

    /** (EXT.19) The type arguments of one reference, filled, with the loud records of the ones supplied as `Any?`. */
    private class FilledTypeArguments(val texts: List<String>, val markers: List<String>)

    /**
     * (EXT.19) The type ARGUMENTS a reference to the generated [target]
     * renders with — exactly one per declared type parameter, because a
     * Kotlin reference must spell every argument its target declares (and
     * the target's declaration dropped its defaults loudly): the WRITTEN
     * ones through their own annotations (the (EXT.6) rule, one unmappable
     * argument refusing the whole reference), and every missing one from
     * the parameter's declared DEFAULT — rendered as a nested alias body is,
     * its names resolved by the lens at the default's own position and
     * spelled from the use site, the earlier parameters it names
     * substituted by the arguments already rendered. A default that does
     * not map — `typeof IncomingMessage`, a tuple alias — is supplied as
     * `Any?` with a loud record naming it: the target renders that
     * parameter unconstrained, so `Any?` is the one argument every consumer
     * could write, and refusing would lose the supertype (`ReadableStream
     * extends EventEmitter`, whose bare spelling was `One type argument
     * expected` and cascaded into 45 `overrides nothing` on `@types/node`).
     * Null where the reference has no spelling at all: more arguments than
     * parameters, an unmappable written argument, a missing argument with
     * no default (the `val ctor: Box` shape, (EXT.11a)).
     */
    private fun filledTypeArguments(
        target: Nameable,
        written: List<TypeNode>,
        lens: CheckedLens,
        scope: TypeScope,
    ): FilledTypeArguments? {
        val parameters = typeParameterNodesOf(target.node)
        if (written.size > parameters.size) return null
        val texts = mutableListOf<String>()
        val markers = mutableListOf<String>()
        for (argument in written) {
            texts += annotationTextOrNull(argument, returnPosition = false, lens = lens, scope = scope) ?: return null
        }
        for (index in written.size until parameters.size) {
            val parameter = parameters[index]
            val default = parameter.default ?: return null
            val earlier = parameters.take(index).map { it.name.text }
            val defaultScope = TypeScope(
                ownTypeParams = earlier.toSet(),
                generatedNameOf = scope.generatedNameOf,
                path = scope.path,
                inAmbientModule = target.site.inAmbientModule,
                namespaceImports = target.site.namespaceImports,
            )
            val rendered = annotationTextOrNull(default, returnPosition = false, lens = lens, scope = defaultScope)
                ?.let { text ->
                    when {
                        earlier.isEmpty() -> text
                        parseKotlinTypeText(text) == null -> null
                        else -> substituteTypeParameters(text, earlier.zip(texts).toMap())
                    }
                }
            if (rendered != null) {
                texts += rendered
            } else {
                texts += "Any?"
                val written = if (default is TypeQuery) "typeof ${entityNameText(default.exprName)}"
                    else lens.render(lens.typeOfTypeNode(default))
                markers += "default for ${parameter.name.text} of ${target.name}: ${commentSafe(written)} not carried - Any? supplied"
            }
        }
        return FilledTypeArguments(texts, markers)
    }

    /**
     * (EXT.2) The generated declaration a resolved type IS, by the checker's
     * identity evidence: a [Type.Interface] whose declaration is one of the
     * surface's exported interfaces — reached directly or as a
     * [Type.Reference]'s target — or null. The declaration is consulted on
     * the TARGET for a reference (`kir/api`'s lesson: a Reference's own
     * symbol carries no declaration where its target's does).
     */
    private fun generatedDeclarationOf(type: Type, lens: CheckedLens): Nameable? {
        val symbol = when (type) {
            is Type.Reference -> type.target.symbol
            // Type.Interface (interfaces AND class instance types) and the
            // member-less Object an enum resolves to both land here; the
            // identity test below is what keeps the widening sound.
            is Type.Object -> type.symbol
            else -> null
        } ?: return null
        // (EXT.19) A MERGED symbol (an interface and a class of one name in
        // one file) carries several nameable declarations; the one that keeps
        // the name is the one a reference denotes, the first otherwise — for
        // the marker that names why it cannot render.
        var first: Nameable? = null
        for (declaration in symbol.declarations) {
            val nameable = nameableDeclarations.firstOrNull { it.node === declaration } ?: continue
            if (ownsName(nameable, lens)) return nameable
            if (first == null) first = nameable
        }
        return first
    }

    /**
     * (EXT.2) The generated-name predicate for [TypeScope]: a generated
     * declaration ([generatedDeclarationOf]) answers its name — (EXT.13) the
     * SHORTEST spelling that resolves from [fromPath] ([shortestSpelling]):
     * bare in its own or an enclosing scope, `protocol.Foo` from `server`,
     * `server.protocol.Foo` from the root — or null where none does (a
     * shadowed root type, which [referenceMarkerText] names).
     */
    private fun generatedNameOf(type: Type, fromPath: List<String>, lens: CheckedLens): String? {
        val nameable = generatedDeclarationOf(type, lens) ?: return null
        return spellingOf(nameable, fromPath, lens)
    }

    /**
     * (EXT.13) The spelling of a generated declaration from inside
     * [fromPath], or null where it has none: shadowed, or (EXT.19) not the
     * declaration that keeps its name ([ownsName] — a refused callable
     * interface among them, which takes no name).
     */
    private fun spellingOf(nameable: Nameable, fromPath: List<String>, lens: CheckedLens): String? {
        if (!ownsName(nameable, lens)) return null
        return shortestSpelling(fromPath, nameable.path, nameable.name, declaredQualified)
    }

    private fun scopeOf(typeParamNames: Set<String>, site: Site, lens: CheckedLens): TypeScope =
        TypeScope(
            typeParamNames,
            { generatedNameOf(it, site.path, lens) },
            site.path,
            site.inAmbientModule,
            site.namespaceImports,
        )

    /** (EXT.19) The site of a surface interface (the scan's answer). */
    private fun siteOf(node: InterfaceDeclaration): Site =
        surface.interfaces.first { it.node === node }.site

    /** (EXT.13) The ROOT-scope aliases — the only ones emitted; a nested alias has no Kotlin shape. */
    private val rootAliases: List<TypeAliasDeclaration> =
        surface.aliases.filter { it.path.isEmpty() }.map { it.node }

    /** (EXT.13) The ROOT-scope interfaces — the callable-interface rules are top-level ones. */
    private val rootInterfaces: List<InterfaceDeclaration> =
        surface.interfaces.filter { it.path.isEmpty() }.map { it.node }

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
            val alias = rootAliases.firstOrNull { it === declaration } ?: continue
            return alias
        }
        return null
    }

    /**
     * (EXT.10) The text a USE of the root alias [alias] renders — the rule the
     * leg in [annotationTextOrNull] documents — or null where the rule says
     * nothing (an alias not emitted, an arity mismatch), which leaves the
     * later legs to answer.
     */
    private fun rootAliasUse(
        alias: TypeAliasDeclaration,
        annotation: TypeReference,
        returnPosition: Boolean,
        lens: CheckedLens,
        scope: TypeScope,
    ): String? {
        if (!aliasRenderable(alias, lens)) return null
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
        return null
    }

    /**
     * (EXT.10) Whether [alias] is EMITTED as a `typealias` by this generation —
     * decided by running its own collection ([collectTypeAlias]), which is a
     * function of the declaration and the lens and so can be asked at any
     * callback, and memoised by the alias's position in [rootAliases]: the
     * reference may be walked before the declaration is. A name may be rendered
     * only against an alias the generated module declares; a skipped alias
     * (unmappable body) keeps every use on the fallback.
     */
    private val aliasRenderable = HashMap<Int, Boolean>()

    private fun aliasRenderable(alias: TypeAliasDeclaration, lens: CheckedLens): Boolean {
        val index = rootAliases.indexOfFirst { it === alias }
        if (index < 0) return false
        return aliasRenderable.getOrPut(index) {
            val declared = surface.aliases.first { it.node === alias }
            collectTypeAlias(alias, lens, declared.site) is ExternalTypeAlias
        }
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
        deferredMarkers.clear()
        val mapped = annotationTextOrNull(annotation, returnPosition, lens, scope)
        // (EXT.17) The receivers set aside while [mapped] was built, as one
        // marker after the whole text — after the nullable wrap, so the
        // marker is never inside a parenthesis. An unmapped text carries the
        // whole written type in its own marker already.
        val receivers = drainedMarkers()
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
                val text = annotation?.let { referenceMarkerText(it, type, lens, scope) }
                "Any? /* xtsc: unmapped ${text ?: commentSafe(lens.render(type))} */"
            }
            !optional -> withDeferredMarkers(mapped, receivers)
            // (EXT.11b) One nullable-wrapping rule for every producer.
            else -> withDeferredMarkers(nullableTypeText(mapped), receivers)
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
     * argument, not pass null), and a REST parameter (a Kotlin function type
     * has no `vararg`; a DECLARATION's rest parameter maps, see
     * [externalParameter]). Both refusals survive (EXT.11b) unchanged: the
     * nullable-union and `any` rules make the PIECES map more often, never a
     * parameter's optionality.
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
        // (EXT.11b) The SYNTACTIC arms of the type table, each the twin of a
        // resolved-type rule in `mappedText` (the renderer's KDoc carries the
        // rationale per row) — syntactic for the reason every arm here is:
        // the pieces are annotations, and only recursing over them keeps a
        // declaration's own type parameters answerable inside a composite
        // (`Array<T>`, `(err: any) => void`, `((value: T) => void) | null`).
        when (annotation) {
            // `any` / `unknown` written: `Any?`, no marker.
            is KeywordTypeNode -> when (annotation.kind) {
                SyntaxKind.AnyKeyword, SyntaxKind.UnknownKeyword -> return "Any?"
                else -> {}
            }
            // A literal type widens to its base; a bigint literal refuses.
            is LiteralType -> return literalBaseText(annotation.literal)
            // `(…)` around a type is pure syntax — `(() => void) | null`
            // parenthesises the function type for the union.
            is ParenthesizedType ->
                return annotationTextOrNull(annotation.type, returnPosition, lens, scope)
            // `T[]` is `Array<T>` — the syntax IS the array, no evidence needed.
            is ArrayType ->
                return arrayTextOrNull(annotation.elementType, lens, scope)
            // `readonly T[]` maps to the same `Array<T>`: Kotlin externals
            // have no read-only array, and `List<T>` would lie about the
            // runtime object. Any other type operator (`keyof T`, `unique
            // symbol`) refuses.
            is TypeOperator ->
                return if (annotation.operator == SyntaxKind.ReadonlyKeyword) {
                    annotationTextOrNull(annotation.type, returnPosition, lens, scope)
                } else {
                    null
                }
            // A union collapses to the ONE text its non-nullish members map
            // to, nullable when a `null`/`undefined` member was dropped —
            // `X | null` -> `X?`, `'N' | 'E' | 'C'` -> `String`, `'a' | 'b' |
            // null` -> `String?`; distinct texts refuse (`string | number`).
            is UnionType -> {
                val members = annotation.types.filterNot(::isNullishTypeNode)
                val texts = members.mapTo(LinkedHashSet()) { member ->
                    annotationTextOrNull(member, returnPosition = false, lens = lens, scope = scope)
                        ?: return null
                }
                val text = texts.singleOrNull() ?: return null
                return if (members.size < annotation.types.size) nullableTypeText(text) else text
            }
            else -> {}
        }
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
            if (alias != null) rootAliasUse(alias, annotation, returnPosition, lens, scope)?.let { return it }
            // (EXT.13, reached through the lens since (EXT.14)) A NESTED alias
            // — never emitted — renders its RESOLVED body where that maps
            // (the checker knows what the alias denotes) and its body
            // INLINED syntactically where it does not; a degraded `any`
            // resolution is not a body.
            val nested = nestedAliasOf(annotation, lens)
            if (nested != null) {
                val resolved = lens.typeOfTypeNode(annotation)
                if (!isAnyIntrinsic(resolved)) {
                    kotlinTypeTextOrNull(resolved, returnPosition = returnPosition, scope = scope)?.let { return it }
                }
                return nestedAliasUse(nested, annotation.typeArguments.orEmpty(), lens, scope)
            }
        }
        val referenceArguments = (annotation as? TypeReference)?.typeArguments
        if (annotation is TypeReference) {
            // (EXT.6) A generic reference names its TARGET by the CHECKER's
            // positive identity (the resolved reference's target declaration is
            // in the generated surface) while its ARGUMENTS render from their
            // own annotations — the (EXT.2) own-TP mechanism one level up,
            // measured on mitt: the lens ambient substitutes a declaration's
            // own type parameters to `any` silently, so `Emitter<Events>`
            // resolved as `Emitter<any>` and fell back. One unmappable
            // argument still refuses the WHOLE reference. (EXT.19) A
            // reference to a generated GENERIC target takes this leg whether
            // or not arguments were written: the missing ones are the
            // target's declared defaults ([filledTypeArguments]), and a
            // target that lost its name or a shadowed one refuses HERE — the
            // marker names why ([referenceMarkerText]).
            val resolved = lens.typeOfTypeNode(annotation)
            val target = generatedDeclarationOf(resolved, lens)
            if (target != null && (referenceArguments != null || typeParameterCountOf(target.node) > 0)) {
                // (EXT.14) `targetName` is a rendered spelling — not wrapped again.
                val targetName = scope.generatedNameOf(resolved) ?: return null
                val filled = filledTypeArguments(target, referenceArguments.orEmpty(), lens, scope) ?: return null
                deferredMarkers += filled.markers
                return if (filled.texts.isEmpty()) targetName
                else "$targetName<${filled.texts.joinToString(", ")}>"
            }
        }
        // (EXT.11b) `Array<T>` / `ReadonlyArray<T>` written as a reference —
        // AFTER the generated-name leg, so a program's own exported
        // `Array`-named interface is named as itself, and on POSITIVE lib
        // evidence: the reference's symbol (import alias followed) has every
        // declaration in a `lib.*.d.ts` file. A reference SPELLING one of
        // the two names whose symbol is anything else refuses HERE, before
        // the resolved path — the checker resolves a one-argument `Array<X>`
        // to the lib array BY NAME (`getTypeFromTypeReference`), so the
        // resolved type of a reference to a program's own `Array` interface
        // is the lib's, and only the written reference's symbol can tell them
        // apart (measured: without this leg a non-exported local `interface
        // Array<T>` rendered `Array<String>`). A two-argument `Array<A, B>`
        // is not the lib's shape and refuses.
        if (annotation is TypeReference && (annotation.typeName as? Identifier)?.text in libArrayNames) {
            val symbol = lens.typeReferenceSymbol(annotation) ?: return null
            if (!isLibArraySymbol(symbol)) return null
            val element = referenceArguments?.singleOrNull() ?: return null
            return arrayTextOrNull(element, lens, scope)
        }
        if (annotation is FunctionType) {
            if (annotation.typeParameters != null) return null
            return functionTypeText(annotation.parameters, annotation.type, lens, scope)
        }
        val resolved = lens.typeOfTypeNode(annotation)
        // (EXT.11b) An `any` the checker ANSWERED for something the source
        // did not spell `any` is a degraded resolution — `Record<string,
        // number>` resolves to the bare `any` intrinsic here (measured), not
        // to `errorType`, and so would map to a clean `Any?` by the resolved
        // rule. The written keyword is the only evidence that `any` was
        // meant; every other annotation reaching this leg keeps its marker,
        // spelled by what was written ([annotationText]).
        // (EXT.14) The written-name fallback, after every lens leg.
        if (isAnyIntrinsic(resolved)) return writtenFallback(annotation, returnPosition, lens, scope)
        return kotlinTypeTextOrNull(
            resolved,
            returnPosition = returnPosition,
            scope = scope,
        ) ?: writtenFallback(annotation, returnPosition, lens, scope)
    }

    /** (EXT.11b) Any intrinsic carrying the `any` flag — `any`, `error`, `unresolved`. */
    private fun isAnyIntrinsic(type: Type): Boolean =
        type is Type.Intrinsic && TypeFlags.Any in type.flags

    /**
     * (EXT.11b) The marker text of a written REFERENCE whose resolved type
     * would mislead: one the checker DEGRADED to `any` — `Record<string,
     * number> resolved to any`, because `lens.render` alone prints `any`
     * (B58.1 renders `errorType` as `any` too) and a marker reading
     * `unmapped any` beside a rule that MAPS `any` would say the opposite of
     * what it means — and one spelling `Array`/`ReadonlyArray` that names
     * something other than the lib array, which the checker resolves to the
     * lib array BY NAME (so its render, `string[]`, would claim the very
     * mapping that was refused). The reference is spelled as written, its
     * arguments rendered by the checker (which does resolve those). (EXT.13)
     * A reference to a GENERATED type that has no Kotlin spelling from the
     * current scope — a root `Node` read from inside a namespace declaring
     * its own `Node`; Kotlin resolves the bare name innermost-first and the
     * generated file has no package to qualify by — names the shadowing
     * scope, decided on the RESOLVED type's identity ([generatedDeclarationOf],
     * (EXT.14)). Null for every other annotation, which keeps the checker's
     * own rendering.
     */
    private fun referenceMarkerText(
        annotation: TypeNode,
        resolved: Type,
        lens: CheckedLens,
        scope: TypeScope,
    ): String? {
        if (annotation !is TypeReference) return null
        val target = generatedDeclarationOf(resolved, lens)
        val reason = when {
            target != null && isCallableInterface(target.node) && !interfaceRenderable(target.node, lens) ->
                "callable interface ${target.name} has no Kotlin function type"
            target != null && !ownsName(target, lens) ->
                "the name ${target.name} is taken by an earlier declaration in ${scopeText(target.path)}"
            target != null && spellingOf(target, scope.path, lens) == null ->
                "shadowed inside ${scope.path.joinToString(".")}, no Kotlin spelling reaches it"
            isAnyIntrinsic(resolved) -> "resolved to any"
            (annotation.typeName as? Identifier)?.text in libArrayNames &&
                !isLibArraySymbol(lens.typeReferenceSymbol(annotation)) -> "not the lib Array"
            else -> return null
        }
        val written = entityNameText(annotation.typeName) +
            (annotation.typeArguments?.joinToString(", ", "<", ">") { lens.render(lens.typeOfTypeNode(it)) } ?: "")
        return "${commentSafe(written)} - $reason"
    }

    /** (EXT.19) A scope path for a marker: the objects' dotted path, or the top level. */
    private fun scopeText(path: List<String>): String =
        if (path.isEmpty()) "the top level" else path.joinToString(".")

    /**
     * (EXT.11a) The SYNTACTIC function-type rendering shared by a written
     * function type and a callable interface's one call signature: every
     * parameter and the return type through [annotationTextOrNull] under the
     * enclosing scope, null where any piece refuses (the (EXT.3) rules: an
     * optional or rest parameter, an unannotated one, an unmappable type).
     *
     * A `this` parameter is NOT a positional parameter, and (EXT.17) it is
     * not a Kotlin RECEIVER either: `(this: SchedulerAction<T>, state: T) =>
     * void` is `(T) -> Unit` with a loud marker. Rendered positionally — the
     * pre-(EXT.11a) behaviour — it compiled, and a Kotlin lambda written
     * against it would have received the action where JavaScript passes the
     * state: the SILENT direction. (EXT.11a) rendered it as the receiver
     * `SchedulerAction<T>.(T) -> Unit`, which the Kotlin/JS compiler REFUSES
     * in every external declaration (`Function types with receivers are
     * prohibited in external declarations`, measured 2.4.10, through a
     * typealias too, lifted only by `-Xextension-functions-in-externals`) —
     * and which was the silent direction in a second costume: a Kotlin/JS
     * lambda with a receiver is a JavaScript function taking the receiver as
     * its FIRST ARGUMENT, so JavaScript's `work.call(action, state)` would
     * have bound the state to the receiver and `undefined` to the state.
     * Receiver-less, the lambda is called exactly as JavaScript calls it; what
     * a consumer loses is access to `this`, which no Kotlin lambda has, and
     * the marker says so. The receiver is the FIRST parameter named `this`
     * (TypeScript allows no other position; one found elsewhere refuses the
     * whole type) and goes through the same mapping — an unmappable one still
     * refuses the whole type. The marker is set aside in [deferredMarkers]
     * for the producer of the finished text to attach at the top level.
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
        // A refusal below takes the markers this call set aside with it: a
        // refused type carries no marker, and the pieces of a refused
        // composite must not be attributed to its neighbour.
        val mark = deferredMarkers.size
        fun refuse(): String? {
            deferredMarkers.subList(mark, deferredMarkers.size).clear()
            return null
        }
        receiver?.let { parameter ->
            val text = annotationTextOrNull(
                parameter.type ?: return refuse(),
                returnPosition = false,
                lens = lens,
                scope = scope,
            ) ?: return refuse()
            deferredMarkers += "this parameter $text not carried"
        }
        val parameters = ordinary.map { parameter ->
            if (parameter.questionToken || parameter.dotDotDotToken) return refuse()
            annotationTextOrNull(
                parameter.type ?: return refuse(),
                returnPosition = false,
                lens = lens,
                scope = scope,
            ) ?: return refuse()
        }
        val returnType = annotationTextOrNull(
            returnAnnotation ?: return refuse(),
            returnPosition = true,
            lens = lens,
            scope = scope,
        ) ?: return refuse()
        return "(${parameters.joinToString(", ")}) -> $returnType"
    }

    /** (EXT.11a) The `this` parameter of a signature — its name is the keyword, parsed as an [Identifier]. */
    private fun isThisParameter(parameter: Parameter): Boolean =
        (parameter.name as? Identifier)?.text == "this"

    /**
     * (EXT.11b) `null` and `undefined` in type position — the parser's
     * [KeywordTypeNode]s for both (a `null` type is NOT a [LiteralType] here),
     * the members a nullable union drops.
     */
    private fun isNullishTypeNode(node: TypeNode): Boolean =
        node is KeywordTypeNode &&
            (node.kind == SyntaxKind.NullKeyword || node.kind == SyntaxKind.UndefinedKeyword)

    /**
     * (EXT.11b) The base of a written literal type — the syntactic twin of
     * [widenLiteral]: a string literal is `String`, a numeric one (negated or
     * not) `Double`, `true`/`false` (parsed as [Identifier]s) `Boolean`. A
     * bigint literal has no mapping to widen to and refuses, as the resolved
     * `Type.BigIntLiteral` does.
     */
    private fun literalBaseText(literal: Expression): String? = when (literal) {
        is StringLiteralNode -> "String"
        is NumericLiteralNode -> "Double"
        is PrefixUnaryExpression -> if (literal.operand is NumericLiteralNode) "Double" else null
        is Identifier -> if (literal.text == "true" || literal.text == "false") "Boolean" else null
        is BigIntLiteralNode -> null
        else -> null
    }

    /**
     * (EXT.11b) `Array<element>` from the element ANNOTATION, null where the
     * element refuses — one unmappable element refuses the whole array, as
     * one unmappable argument refuses a generated generic reference.
     */
    private fun arrayTextOrNull(element: TypeNode, lens: CheckedLens, scope: TypeScope): String? {
        val text = annotationTextOrNull(element, returnPosition = false, lens = lens, scope = scope)
            ?: return null
        return "Array<$text>"
    }

    /**
     * (EXT.11b) The element annotation of an ARRAY-shaped type node — `T[]`,
     * `readonly T[]`, a lib `Array<T>`/`ReadonlyArray<T>` reference — or null
     * for anything else: what a rest parameter's `vararg` needs.
     */
    private fun arrayElementAnnotation(annotation: TypeNode, lens: CheckedLens): TypeNode? = when {
        annotation is ArrayType -> annotation.elementType
        annotation is ParenthesizedType -> arrayElementAnnotation(annotation.type, lens)
        annotation is TypeOperator && annotation.operator == SyntaxKind.ReadonlyKeyword ->
            arrayElementAnnotation(annotation.type, lens)
        annotation is TypeReference ->
            annotation.typeArguments?.singleOrNull()
                ?.takeIf { isLibArraySymbol(lens.typeReferenceSymbol(annotation)) }
        else -> null
    }

    /**
     * (EXT.11b) One parameter of a DECLARATION — a function, a method, a
     * constructor — as the model carries it. A REST parameter `...xs: T[]`
     * (or `...xs: Array<T>`, `...xs: readonly T[]`) is `vararg xs: T`: the
     * one Kotlin shape that keeps the call site's arity open. Where the
     * element does not map the whole annotation goes through the ordinary
     * marker path (`Any? /* xtsc: unmapped Foo[] */` — loud, and the arity
     * loss is readable in the marker); where the rest type is not an array
     * shape at all (a tuple, a bare type parameter constrained to an array)
     * the marker names it as a REST type, because its element-wise mapping
     * would otherwise read as a plausible single parameter. Inside a function
     * TYPE a rest parameter stays refused ([functionTypeText]): a Kotlin
     * function type has no `vararg`.
     *
     * A destructuring parameter has no name of its own; a positional one
     * keeps the declaration readable.
     */
    private fun externalParameter(
        index: Int,
        parameter: Parameter,
        lens: CheckedLens,
        scope: TypeScope,
    ): ExternalParameter {
        val name = (parameter.name as? Identifier)?.text ?: "p$index"
        val annotation = parameter.type
        if (parameter.dotDotDotToken && annotation != null) {
            val element = arrayElementAnnotation(annotation, lens)
            if (element != null) {
                annotationTextOrNull(element, returnPosition = false, lens = lens, scope = scope)
                    ?.let { return ExternalParameter(name, it, vararg = true) }
            } else {
                val rendered = commentSafe(lens.render(lens.typeOfTypeNode(annotation)))
                return ExternalParameter(name, "Any? /* xtsc: unmapped rest $rendered */")
            }
        }
        return ExternalParameter(
            name = name,
            type = annotationText(
                annotation,
                optional = parameter.questionToken,
                returnPosition = false,
                lens = lens,
                scope = scope,
            ),
        )
    }

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
        site: Site,
    ): ExternalDeclaration? {
        val name = node.name?.text
            ?: return SkippedDeclaration("top-level function without a name")
        val overloadGroup = surface.functions.filter {
            it.node.name?.text == name && it.node.parent === node.parent
        }
        if (node.body != null && overloadGroup.size > 1) return null
        val typeParameters = node.typeParameters.orEmpty().map { it.name.text }
        val scope = scopeOf(typeParameters.toSet(), site, lens)
        val markers = typeParameterMarkers(node.typeParameters, lens)
        defaultExportMarker(node.modifiers, markers)
        val declared = declaredParameters(node.parameters, lens, scope)
        declared.thisMarker?.let { markers.add(it) }
        val parameters = declared.parameters
            .mapIndexed { index, parameter -> externalParameter(index, parameter, lens, scope) }
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
        // (EXT.16) Under a wiring the default export IS wired — `@JsName("default")`.
        if (plan != null) return
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
     * duplicates become loud markers instead of a compile error. The marker
     * comment is NOT part of the signature Kotlin sees — two different
     * literal types both falling to a marked `Any?` conflict however
     * different their markers read — so the key ([overloadSignature]) is the
     * type text with the marker stripped. (EXT.12) Which duplicate survives
     * is [overloadWinners]' decision — the least-marked one, ties to the
     * first — at each member's own position; the same helper decides the
     * module surface in [finish].
     */
    private fun dedupeOverloads(members: MutableList<ExternalMember>) {
        val functions = members.map { it as? ExternalFunction }
        val winners = overloadWinners(functions)
        for (index in members.indices) {
            val winner = winners[index]
            if (winner == index) continue
            members[index] = SkippedMember(overloadCollapseDescription(functions[winner]!!))
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
        // only the pre-scanned surface is generated — (EXT.13) each entry at
        // the namespace path the scan recorded for it. A non-exported
        // declaration is deliberately silent — nothing consuming the module
        // could have named it.
        when (node) {
            is InterfaceDeclaration -> {
                val declared = surface.interfaces.firstOrNull { it.node === node } ?: return
                if (!firstVisit(node)) return
                add(declared.path, node, collectInterface(node, lens, declared.site), declared.ordinal)
            }
            is ClassDeclaration -> {
                val declared = surface.classes.firstOrNull { it.node === node } ?: return
                if (!firstVisit(node)) return
                add(declared.path, node, collectClass(node, lens, declared.site), declared.ordinal)
            }
            is EnumDeclaration -> {
                val declared = surface.enums.firstOrNull { it.node === node } ?: return
                if (!firstVisit(node)) return
                add(declared.path, node, collectEnum(node), declared.ordinal)
            }
            is TypeAliasDeclaration -> {
                val declared = surface.aliases.firstOrNull { it.node === node } ?: return
                if (!firstVisit(node)) return
                add(declared.path, node, collectTypeAlias(node, lens, declared.site), declared.ordinal)
            }
            is FunctionDeclaration -> {
                val declared = surface.functions.firstOrNull { it.node === node } ?: return
                if (!firstVisit(node)) return
                collectFunction(node, lens, declared.site)
                    ?.let { add(declared.path, node, it, declared.ordinal) }
            }
            is VariableDeclaration -> {
                val exported = surface.values.firstOrNull { it.node === node } ?: return
                if (!firstVisit(node)) return
                add(exported.path, node, collectValue(exported, lens), exported.ordinal)
            }
            is ExportAssignment, is ExportDeclaration -> {
                if (surface.exportWiring.none { it === node }) return
                if (!firstVisit(node)) return
                // (EXT.16) Under a wiring the statement IS the wiring: it
                // renders only what could NOT be expressed.
                val declaration = if (plan == null) collectExportWiring(node) else plan.statementMarker(node) ?: return
                add(emptyList(), node, declaration)
            }
            // (EXT.13) A namespace: the flattened root's loud header, the
            // nested object's scope (created here so an EMPTY namespace still
            // renders as an object), or the loud skip of one not generated.
            is ModuleDeclaration -> {
                val entry = surface.namespaces.firstOrNull { it.node === node } ?: return
                if (!firstVisit(node)) return
                when {
                    entry.skip != null -> add(entry.ownerPath, node, SkippedDeclaration(entry.skip))
                    else -> {
                        // (EXT.16) A wired header states what the root's members bind as.
                        entry.header?.let { add(entry.ownerPath, node, ExternalMarker(plan?.namespaceHeader(node) ?: it)) }
                        scope(entry.bodyPath, fileNameOf(node))
                    }
                }
            }
            // (EXT.13) `export import X = ts.X` — an import-alias declaration
            // re-exporting a name under another (or the same) spelling. Not
            // a `typealias`: it is nested, and a same-name alias to the
            // flattened root's own `X` would be redundant. A loud marker
            // naming the target, whatever the target is — the wiring that
            // could express it is the module-wiring rung.
            is ImportEqualsDeclaration -> {
                val declared = surface.importAliases.firstOrNull { it.node === node } ?: return
                if (!firstVisit(node)) return
                add(
                    declared.path,
                    node,
                    ExternalMarker(
                        "alias ${node.name.text} = ${commentSafe(moduleReferenceText(node.moduleReference))}" +
                            if (plan == null) " - re-exported name, wiring is a later rung"
                            else " - re-exported name, a nested object member cannot carry @JsName"
                    ),
                )
            }
            else -> return
        }
    }

    /** Records [node] as visited; false when it was already (the spine may walk a tree twice). */
    private fun firstVisit(node: Node): Boolean {
        if (seen.any { it === node }) return false
        seen.add(node)
        return true
    }

    /** (EXT.13) The written spelling of an import-equals target: `ts.X`, or `require("m")`. */
    private fun moduleReferenceText(reference: Node): String = when (reference) {
        is Identifier, is QualifiedName -> entityNameText(reference)
        is ExternalModuleReference ->
            "require(\"${(reference.expression as? StringLiteralNode)?.text ?: "…"}\")"
        else -> "a module reference"
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
     *
     * (CHK.73b) An UN-ANNOTATED value whose initializer names a class, an
     * enum or a namespace is refused BEFORE the checker is asked
     * ([constructorValueRefusal]): the checker's answer there is a plausible,
     * mappable, WRONG type. An annotated value renders its annotation as
     * before — a `typeof Plain` annotation is the (EXT.11a) marker, and an
     * instance-typed annotation is what the author wrote.
     */
    private fun collectValue(exported: ExportedValue, lens: CheckedLens): ExternalDeclaration {
        val node = exported.node
        val name = (node.name as? Identifier)?.text
            ?: return SkippedDeclaration("destructuring export - no single name to declare")
        val scope = scopeOf(emptySet(), exported.site, lens)
        val type =
            if (node.type != null) {
                annotationText(node.type, optional = false, returnPosition = false, lens = lens, scope = scope)
            } else {
                constructorValueRefusal(name, node.initializer, lens)?.let { return it }
                // A `const`'s literal type (`export const RETRIES = 3` is typed
                // `3`, the (WIDEN.1) const rule) widens to its base primitive
                // here: the declaration is what a consumer BINDS, and `val
                // RETRIES: 3` has no Kotlin shape — the same widening TypeScript
                // itself performs at every mutable position.
                kotlinTypeText(widenLiteral(lens.typeOf(node.name)), optional = false, returnPosition = false, lens = lens, scope = scope)
            }
        return ExternalTopLevelValue(name, type, exported.readOnly)
    }

    /**
     * (CHK.73b) The loud skip for an un-annotated value whose INITIALIZER is a
     * class, an enum object or a namespace object — or null when the
     * initializer is anything else and the checker's answer may be used.
     *
     * This checker types a class VALUE as its INSTANCE type (CHK.73: there is
     * no static-side type for a class value here), so `export const plain =
     * Plain` was typed `Plain` and rendered `val plain: Plain` — which
     * compiles and is wrong: a consumer would read `plain.x` where the
     * runtime value is the constructor. Only the GENERIC case was refused,
     * by (EXT.11a)'s arity guard, and only because a bare `Box<T>` has no
     * Kotlin spelling. The same silence covered an enum object (`export
     * const K = Kind` rendered `val K: Kind`, the entries' sealed interface,
     * so a consumer would read `K` as an ENTRY) and a namespace object
     * (`val N: Any?` with NO marker, the one shape this generator otherwise
     * reserves for a written `any`). So the value's shape is decided from
     * what the initializer NAMES, before the checker is asked what it is
     * typed:
     *
     *  - an [Identifier] or a dotted [PropertyAccessExpression] (`Plain`,
     *    `NS.Inner`, an imported `Plain`) is resolved by
     *    [CheckedLens.heritageBaseSymbol] with the import alias followed —
     *    measured at the value's own callback: `lens.resolveName` DOES see a
     *    same-file top-level class, but for an IMPORTED class it answers the
     *    import specifier's lexical-chain symbol, on which
     *    [CheckedLens.aliasTarget] answers null (it carries no `Alias` flag),
     *    and a dotted name it cannot answer at all; the heritage resolver
     *    answered the declaration in every measured shape, so it is the one
     *    resolver for all three spellings. A symbol declaring a
     *    [ClassDeclaration] refuses as a CLASS (a class merged with a
     *    namespace counts as the class), one declaring an [EnumDeclaration]
     *    as an ENUM, one declaring a [ModuleDeclaration] as a NAMESPACE —
     *    any file, the lib included; the description spells the written
     *    initializer;
     *  - a [ClassExpression] (`export const C = class { … }`) is a
     *    constructor value with no name to spell — refused as such (it
     *    rendered `val C: Any?`, marker-less);
     *  - everything else keeps the checker's answer: a `new` expression IS
     *    an instance, a namespace MEMBER (`NS.x`) or another value resolves
     *    to a [VariableDeclaration], a function value already falls to the
     *    `unmapped () => void` marker, and a lib "class" is not a class
     *    declaration at all — `lib.*.d.ts` spells `Error` as `interface
     *    Error` plus `declare var Error: ErrorConstructor`, so the checker
     *    types `export const E = Error` CORRECTLY as `ErrorConstructor` and
     *    the existing marker carries it.
     */
    private fun constructorValueRefusal(
        name: String,
        initializer: Expression?,
        lens: CheckedLens,
    ): SkippedDeclaration? {
        if (initializer == null) return null
        if (initializer is ClassExpression) {
            return SkippedDeclaration(
                "value $name initialized by a class expression - a constructor value has no externals shape yet"
            )
        }
        val written = expressionNameText(initializer) ?: return null
        val symbol = lens.heritageBaseSymbol(initializer)
            ?.let { lens.aliasTarget(it) ?: it }
            ?: return null
        val declarations = symbol.declarations
        val (kind, shape) = when {
            declarations.any { it is ClassDeclaration } -> "class" to "a constructor value"
            declarations.any { it is EnumDeclaration } -> "enum" to "an enum object value"
            declarations.any { it is ModuleDeclaration } -> "namespace" to "a namespace object value"
            else -> return null
        }
        return SkippedDeclaration(
            "value $name initialized by the $kind ${commentSafe(written)} - $shape has no externals shape yet"
        )
    }

    /**
     * (CHK.73b) The written spelling of an entity-name-shaped EXPRESSION — an
     * [Identifier] or a dotted chain of them (`NS.Inner`) — or null for any
     * other expression, which [constructorValueRefusal] then leaves to the
     * checker. The expression twin of [entityNameText].
     */
    private fun expressionNameText(expression: Expression): String? = when (expression) {
        is Identifier -> expression.text
        is PropertyAccessExpression ->
            expressionNameText(expression.expression)?.let { "$it.${expression.name.text}" }
        else -> null
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
        site: Site,
    ): ExternalDeclaration {
        val name = node.name?.text
            ?: return SkippedDeclaration("class without a name")
        val path = site.path
        val typeParameters = node.typeParameters.orEmpty().map { it.name.text }
        val scope = scopeOf(typeParameters.toSet(), site, lens)
        val headerMarkers = typeParameterMarkers(node.typeParameters, lens)
        defaultExportMarker(node.modifiers, headerMarkers)
        val members = mutableListOf<ExternalMember>()
        val staticMembers = mutableListOf<ExternalMember>()
        val heritage = collectHeritage(node.heritageClauses, lens, scope, members) { base, isExtends ->
            if (isExtends) base is ClassDeclaration else base is InterfaceDeclaration
        }
        headerMarkers += heritage.markers
        if (heritage.extendsClasses.size > 1) {
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
                        val external = externalParameter(index, parameter, lens, scope)
                        // (EXT.15) `constructor(public x: number)` DECLARES a
                        // member, rendered at the start of the body in
                        // parameter order.
                        parameterPropertyMember(parameter, external)?.let { members.add(it) }
                        external
                    }
            }
        }
        for (member in node.members) {
            val modifiers = when (member) {
                is PropertyDeclaration -> member.modifiers
                is MethodDeclaration -> member.modifiers
                is GetAccessor -> member.modifiers
                is SetAccessor -> member.modifiers
                is IndexSignature -> member.modifiers
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
            val memberScope = if (isStatic) scopeOf(emptySet(), site, lens) else scope
            when (member) {
                is PropertyDeclaration -> collectProperty(member, lens, target, memberScope)
                is MethodDeclaration -> collectMethod(member, lens, target, memberScope)
                is GetAccessor, is SetAccessor ->
                    collectAccessor(member, accessorSiblings(node.members, isStatic), lens, target, memberScope)
                is IndexSignature -> collectIndexSignature(member, lens, target, memberScope)
                is Constructor -> {}
                is SemicolonClassElement -> {}
                // A static initialization block declares nothing a consumer
                // could name — pure runtime, silently outside the surface.
                // (EXT.15) With the index signature rendered the `when` is
                // exhaustive over every class element kind.
                is ClassStaticBlockDeclaration -> {}
            }
        }
        dedupeOverloads(members)
        dedupeOverloads(staticMembers)
        return ExternalClass(
            name = name,
            typeParameters = typeParameters,
            headerMarkers = headerMarkers,
            isAbstract = ModifierFlag.Abstract in node.modifiers,
            superClass = heritage.extendsClasses.singleOrNull(),
            interfaces = heritage.implements,
            constructorParameters = constructorParameters,
            members = members,
            staticMembers = staticMembers,
            path = path,
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
        site: Site,
    ): ExternalDeclaration {
        val name = node.name.text
        val path = site.path
        // (EXT.13) A Kotlin `typealias` is top-level only: inside a nested
        // namespace object the alias has no shape and is a loud skip. Its
        // USES still resolve through the checker to what the alias denotes
        // (the Dukat rule), so what is lost is the NAME.
        if (path.isNotEmpty()) {
            return SkippedDeclaration(
                "type alias $name inside namespace ${path.joinToString(".")} - Kotlin aliases are top-level only"
            )
        }
        val typeParameters = node.typeParameters.orEmpty().map { it.name.text }
        if (typeParameters.isNotEmpty()) {
            val markers = typeParameterMarkers(node.typeParameters, lens)
            deferredMarkers.clear()
            val body = annotationTextOrNull(
                node.type,
                returnPosition = false,
                lens = lens,
                scope = scopeOf(typeParameters.toSet(), site, lens),
            ) ?: return SkippedDeclaration("generic type alias $name with unmappable body")
            // (EXT.17) A `this` parameter in the body is a marker on the alias.
            markers += drainedMarkers()
            return ExternalTypeAlias(name, typeParameters, markers, body)
        }
        // (EXT.10) The body through the annotation path — the resolved type
        // where it maps (unchanged: `string` → `String`), and a FUNCTION type
        // syntactically where the resolved one has no Kotlin spelling, so
        // `type Cb = () => void` is a `typealias` its uses can name.
        deferredMarkers.clear()
        val mapped = annotationTextOrNull(
            node.type,
            returnPosition = false,
            lens = lens,
            scope = scopeOf(emptySet(), site, lens),
        ) ?: return SkippedDeclaration(
            "type alias $name with unmappable body " +
                commentSafe(lens.render(lens.typeOfTypeNode(node.type)))
        )
        return ExternalTypeAlias(name, emptyList(), drainedMarkers(), mapped)
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
        return rootInterfaces.firstOrNull { it.name.text == baseName }
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
     *
     * (EXT.13) ROOT-scope interfaces only: the shape is a `typealias`, which
     * a nested namespace object cannot hold, so a callable interface inside
     * one renders as an interface with its call signature a loud skip.
     */
    private val callableInterfaces: List<InterfaceDeclaration> = run {
        val callable = rootInterfaces.filter { soleCallSignatureOf(it) != null }.toMutableList()
        while (true) {
            val added = rootInterfaces.filter { candidate ->
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
        deferredMarkers.clear()
        val body = functionTypeText(
            signature.parameters,
            signature.type,
            lens,
            scopeOf(typeParameters.toSet(), siteOf(node), lens),
        ) ?: return SkippedDeclaration("callable interface $name with unmappable signature")
        // (EXT.17) A `this` parameter of the call signature is a marker on the alias.
        markers += drainedMarkers()
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
        val scope = scopeOf(typeParameters.toSet(), siteOf(node), lens)
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
        site: Site,
    ): ExternalDeclaration {
        val path = site.path
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
        val scope = scopeOf(typeParameters.toSet(), site, lens)
        val headerMarkers = typeParameterMarkers(node.typeParameters, lens)
        defaultExportMarker(node.modifiers, headerMarkers)
        val members = mutableListOf<ExternalMember>()
        // An interface may `extends` a generated interface; a generated CLASS
        // as an interface's base has no Kotlin shape (an interface cannot
        // extend a class) and stays a marker — (EXT.20) unless the interface
        // MERGES with a class of its name, whose merged declaration takes
        // the class bases ([mergedDeclaration]).
        val mergesWithClass = nameableDeclarations.firstOrNull { it.node === node }
            ?.let { mergedSibling(it) { sibling -> sibling.node is ClassDeclaration } } != null
        val heritage = collectHeritage(node.heritageClauses, lens, scope, members) { base, _ ->
            base is InterfaceDeclaration || (mergesWithClass && base is ClassDeclaration)
        }
        headerMarkers += heritage.markers
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
                is IndexSignature -> collectIndexSignature(member, lens, members, scope)
                // A stray `;` between members is pure syntax — there is
                // nothing to generate and nothing to mark.
                is SemicolonClassElement -> {}
                else -> members.add(
                    SkippedMember(member::class.simpleName ?: "member")
                )
            }
        }
        dedupeOverloads(members)
        return ExternalInterface(
            node.name.text, typeParameters, headerMarkers, heritage.extends, members, path,
            classBases = heritage.extendsClasses,
        )
    }

    /** (EXT.8) The bases a declaration's heritage clauses resolve to. */
    private class Heritage(
        /** `extends` bases that are GENERATED interfaces, as Kotlin type text. */
        val extends: List<String>,
        /** (EXT.20) `extends` bases that are GENERATED classes (by the merged kind), as Kotlin type text. */
        val extendsClasses: List<String>,
        /** `implements` bases that are GENERATED interfaces, as Kotlin type text. */
        val implements: List<String>,
        /** (EXT.19) Loud header records — a defaulted type argument supplied as `Any?`. */
        val markers: List<String>,
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
     *
     * (EXT.13) A base may be DOTTED (`extends server.Project`); the resolver
     * takes the expression as it takes a value initializer's, and the
     * rendered supertype is the target's shortest spelling from the deriving
     * declaration's own scope ([spellingOf]) — never the written text, which
     * spells the TypeScript route (`ts.server.Project` names a root that has
     * no Kotlin name). A target shadowed from that scope is a marker. A bare
     * base inside a namespace body is that namespace's own declaration
     * before the root's or a lib's ((CHK.76); 509 `extends Node`-shaped
     * clauses of `typescript.d.ts` answered null or the DOM's before it).
     *
     * (EXT.19) Two refusals the marker NAMES, and one fill: a target that
     * is not the declaration keeping its name in its Kotlin scope
     * ([ownsName] — `vm`'s `Module` beside `module`'s namespace object
     * rendered `Cannot extend an object`), a base written through a
     * namespace import the lens answers nothing for and the module surface
     * holds nothing of, and — the fill — the type arguments the base's
     * generic target declares but the clause does not write, supplied from
     * the declared defaults ([filledTypeArguments]): `interface
     * ReadableStream extends EventEmitter` over `EventEmitter<T = …>` was
     * `One type argument expected` and, because a Kotlin supertype in error
     * contributes no members, 45 `overrides nothing` on `@types/node`.
     */
    private fun collectHeritage(
        clauses: List<HeritageClause>?,
        lens: CheckedLens,
        scope: TypeScope,
        members: MutableList<ExternalMember>,
        kindOk: (base: Node, isExtends: Boolean) -> Boolean,
    ): Heritage {
        val extends = mutableListOf<String>()
        val extendsClasses = mutableListOf<String>()
        val implements = mutableListOf<String>()
        val markers = mutableListOf<String>()
        for (clause in clauses.orEmpty()) {
            val isExtends = clause.token == SyntaxKind.ExtendsKeyword
            val keyword = clause.token.name.removeSuffix("Keyword").lowercase()
            for (base in clause.types) {
                val baseName = expressionNameText(base.expression)
                var reason: String? = null
                var renderedIsClass = false
                val rendered = baseName?.let { written ->
                    // Resolved as the checker resolves the clause itself (the
                    // lexical `resolveName` offers no import): an imported
                    // base is its import ALIAS, and the identity test needs
                    // the declaration it names. (EXT.14) The written-name
                    // fallback on a lens miss, where it applies.
                    val resolved = lens.heritageBaseSymbol(base.expression)
                        ?.let { lens.aliasTarget(it) ?: it }
                        ?.declarations?.firstNotNullOfOrNull { declared ->
                            nameableDeclarations.firstOrNull { it.node === declared }
                        }
                        ?: written.split('.').let { segments ->
                            if (writtenApplies(segments, scope)) writtenTarget(segments, scope) as? Nameable else null
                        }
                        ?: return@let null
                    // (EXT.20) A MERGED name denotes ONE Kotlin declaration,
                    // a class when any declaration of the group is a class:
                    // the kind test reads the group's representative, not
                    // whichever declaration the resolver answered (an
                    // `interface EventEmitter` before the `class`), so an
                    // interface's `extends EventEmitter` refuses as the
                    // class base it is — with the reason.
                    val nameable = mergedSibling(resolved) { it.node is ClassDeclaration } ?: resolved
                    if (!kindOk(nameable.node, isExtends)) {
                        if (nameable.node is ClassDeclaration && nameable.ordinal in mergeGroupFirst) {
                            reason = "${nameable.name} is the merged class ${nameable.name} - " +
                                if (isExtends) "an interface cannot extend a class" else "a class cannot be implemented"
                        }
                        return@let null
                    }
                    // (EXT.11a) A callable interface renders as a function
                    // TYPE alias, which nothing can extend or implement.
                    if (isCallableInterface(nameable.node)) return@let null
                    if (!ownsName(nameable, lens)) {
                        reason = "the name ${nameable.name} is taken by an earlier declaration in ${scopeText(nameable.path)}"
                        return@let null
                    }
                    val spelling = spellingOf(nameable, scope.path, lens) ?: run {
                        reason = "shadowed inside ${scope.path.joinToString(".")}, no Kotlin spelling reaches it"
                        return@let null
                    }
                    renderedIsClass = nameable.node is ClassDeclaration
                    val filled = filledTypeArguments(nameable, base.typeArguments.orEmpty(), lens, scope)
                        ?: return@let null
                    markers += filled.markers
                    if (filled.texts.isEmpty()) spelling
                    else "$spelling<${filled.texts.joinToString(", ")}>"
                }
                when {
                    rendered == null ->
                        members.add(
                            SkippedMember(
                                "heritage clause $keyword ${baseName ?: "a base expression"}" +
                                    (reason?.let { " - $it" } ?: "")
                            )
                        )
                    isExtends && renderedIsClass -> extendsClasses.add(rendered)
                    isExtends -> extends.add(rendered)
                    else -> implements.add(rendered)
                }
            }
        }
        return Heritage(extends, extendsClasses, implements, markers)
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

    /**
     * (EXT.15) An INDEX SIGNATURE — `[key: string]: T`, `[i: number]: T` —
     * as the pair Kotlin reads and writes an indexed member through, the
     * kotlin-wrappers/Dukat convention: `operator fun get(key: String): T?`
     * and, unless the signature is `readonly`, `operator fun set(key:
     * String, value: T)`, so a consumer's `o["k"]` and `o["k"] = v` are the
     * JavaScript accesses. Measured through the metadata compile
     * (`KotlinIndexSignatureCompileTest`): an interface holds a `String`
     * pair and a `Double` pair side by side (two `get` overloads told apart
     * by the key type alone), a subinterface redeclares them as `override
     * operator`, and the gate variant's class carries `= null!!` bodies and a
     * companion pair — a `static` index signature lands in the companion
     * like every static member.
     *
     * The READ is nullable and the WRITE is not, deliberately: a read of an
     * absent key is `undefined` in JavaScript whatever the signature's value
     * type says, so `T?` is the honest return type, while a write of `null`
     * where the signature says `T` would be this generator inventing a
     * value; the nullability goes through [annotationText]'s one wrapping
     * rule, so a value type that is already nullable (`T | undefined`, a
     * marked `Any?`) is not wrapped twice. The value type is the checker's
     * answer as for every property, the marker carried on both members
     * where it does not map. The key parameter keeps its WRITTEN name.
     *
     * The key TYPE is decided from the syntax: a `string` keyword renders the
     * `String` pair, a `number` keyword the `Double` pair, and anything else
     * — `symbol`, a template-literal pattern, a union of literals, an alias
     * to `string` — is a loud skip naming it, because a Kotlin `get` keyed
     * by `Any?` would accept every key and say nothing about which ones the
     * object answers. To the override, overload and heritage keys the two
     * operators are ordinary functions named `get`/`set`
     * ([ExternalFunction.operator]) — a subinterface redeclaring the
     * signature renders `override`, a class member overridden below renders
     * `open`, and an interface declaring BOTH an index signature and a
     * method `get(key: string)` collapses the pair by the overload rule,
     * loudly, exactly as two methods of one Kotlin signature would.
     */
    private fun collectIndexSignature(
        member: IndexSignature,
        lens: CheckedLens,
        members: MutableList<ExternalMember>,
        scope: TypeScope,
    ) {
        val parameter = member.parameters.singleOrNull { !it.isCommentPlaceholder }
        val keyAnnotation = parameter?.type
        if (parameter == null || keyAnnotation == null) {
            members.add(SkippedMember("index signature without a key"))
            return
        }
        val keyType = when ((keyAnnotation as? KeywordTypeNode)?.kind) {
            SyntaxKind.StringKeyword -> "String"
            SyntaxKind.NumberKeyword -> "Double"
            else -> {
                val rendered = commentSafe(lens.render(lens.typeOfTypeNode(keyAnnotation)))
                members.add(
                    SkippedMember(
                        "index signature keyed by $rendered - only a string or number key has a Kotlin get/set pair"
                    )
                )
                return
            }
        }
        val keyName = (parameter.name as? Identifier)?.text ?: "key"
        val key = ExternalParameter(keyName, keyType)
        members.add(
            ExternalFunction(
                name = "get",
                typeParameters = emptyList(),
                markers = emptyList(),
                parameters = listOf(key),
                returnType = annotationText(member.type, optional = true, returnPosition = false, lens = lens, scope = scope),
                operator = true,
            )
        )
        if (ModifierFlag.Readonly in member.modifiers) return
        members.add(
            ExternalFunction(
                name = "set",
                typeParameters = emptyList(),
                markers = emptyList(),
                parameters = listOf(
                    key,
                    ExternalParameter(
                        "value",
                        annotationText(member.type, optional = false, returnPosition = false, lens = lens, scope = scope),
                    ),
                ),
                returnType = "Unit",
                operator = true,
            )
        )
    }

    /**
     * (EXT.15) The member a PARAMETER PROPERTY declares — `constructor(public
     * x: number, private y: string, readonly z: boolean, protected w: T)`
     * declares `x` and `z` on the instance (and `y`, `w`, which are not part
     * of the consumable surface and are omitted silently, the policy every
     * private/protected member gets) — or null for an ordinary parameter.
     * Any modifier on a constructor parameter makes it a parameter property
     * (`override` and `readonly` alone included); `readonly` renders `val`.
     *
     * The property is an explicit member typed by the SAME text the
     * parameter renders with (an optional parameter property is an optional
     * member, so the `?` carries over), placed at the start of the class
     * body in parameter order, and the constructor keeps the parameter as
     * a plain parameter. Kotlin's primary-constructor `val x: Double` would
     * express both at once and is deliberately NOT used: the constructor
     * line is rendered by ONE path for an own constructor, an inherited one
     * ([Inheritance.effectiveConstructor] passes a base's parameters
     * through BY NAME) and the gate variant's superclass call, and a
     * property declared in the parameter list would have to be re-derived
     * at each of those sites. As an explicit member the property reaches
     * the `override`/`open` machinery like any other (a subclass may
     * redeclare `x`), and an unmappable type carries its marker like any
     * property's. A REST parameter property is refused by TypeScript itself
     * (TS1317) and stays a loud skip here.
     */
    private fun parameterPropertyMember(parameter: Parameter, external: ExternalParameter): ExternalMember? {
        val modifiers = parameter.modifiers
        if (modifiers.isEmpty()) return null
        if (ModifierFlag.Private in modifiers || ModifierFlag.Protected in modifiers) return null
        if (parameter.dotDotDotToken) return SkippedMember("rest parameter property ${external.name}")
        return ExternalProperty(
            name = external.name,
            type = external.type,
            readOnly = ModifierFlag.Readonly in modifiers,
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
            else scope.withTypeParams(scope.ownTypeParams + methodTypeParameters)
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
                    type = nullableTypeText("(${parameterTypes.joinToString(", ")}) -> $returnType"),
                    readOnly = false,
                )
            )
            return
        }
        val parameters = declared.parameters
            .mapIndexed { index, parameter -> externalParameter(index, parameter, lens, methodScope) }
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
