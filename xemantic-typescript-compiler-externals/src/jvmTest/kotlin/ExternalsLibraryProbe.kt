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

import com.xemantic.typescript.compiler.DiagnosticCategory
import com.xemantic.typescript.compiler.SourceFileEntry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * A reusable LIBRARY PROBE for the externals generator — the externals twin of
 * the kir `LibraryProbe`: points the generator at a REAL package's declaration
 * files and writes what it produced, what the metadata compile refused, what
 * the checker reported and a census of every loud marker.
 *
 * It does NOTHING unless `XTSC_EXTERNALS_PROBE_FILES` is set (environment,
 * never a system property — Gradle does not forward `-D` to the test JVM):
 *
 * - `XTSC_EXTERNALS_PROBE_FILES` — `:`-separated absolute `.d.ts` paths;
 * - `XTSC_EXTERNALS_PROBE_ROOT` — optional directory prefix stripped from each
 *   path so the [SourceFileEntry] names stay PATH-shaped (`/rxjs/internal/x.d.ts`),
 *   the shape the multi-file entry point's import resolution wants;
 * - `XTSC_EXTERNALS_PROBE_OUT` — the directory receiving `generated.kt`,
 *   `compile-check.kt`, `compile-errors.txt`, `diagnostics.txt` and `census.txt`;
 * - `XTSC_EXTERNALS_PROBE_MODULE` — optional, `<name>:<entry>` ((EXT.16)): the
 *   npm module name and its `types` entry file, the entry as the absolute
 *   path or as its root-stripped name (`rxjs:/index.d.ts`); with it the
 *   generation is WIRED (`@file:JsModule`, `@JsName`, the surface graph) and
 *   the census reports the internal paths — the declarations the entry does
 *   not export, i.e. what a consumer cannot bind — with examples;
 * - (EXT.18) when the Kotlin/JS stdlib klib is located ([JsStdlib]) the REAL
 *   output is compiled as Kotlin/JS too — the `@JsName`/`@file:JsModule`
 *   wiring the metadata compile cannot see — into `js-compile-errors.txt`
 *   and the census's `js compile` rows; absent, the rows say so.
 *
 * The census groups each `/* xtsc: … */` marker by its MECHANISM (the concrete
 * declaration name and rendered type replaced by a placeholder), so two runs
 * over different packages are comparable, and lists the unmapped types
 * separately because THOSE are the actionable half.
 */
class ExternalsLibraryProbe {

    @Test
    fun `probe a library's declarations when XTSC_EXTERNALS_PROBE_FILES is set`() {
        val fileList = System.getenv("XTSC_EXTERNALS_PROBE_FILES") ?: return
        val root = System.getenv("XTSC_EXTERNALS_PROBE_ROOT")?.trimEnd('/')
        val out = Path.of(System.getenv("XTSC_EXTERNALS_PROBE_OUT") ?: "build/externals-probe")
        Files.createDirectories(out)

        fun entryName(path: String): String {
            val name = if (root != null && path.startsWith(root)) path.removePrefix(root) else path
            return if (name.startsWith("/")) name else "/$name"
        }
        val files = fileList.split(':').filter { it.isNotBlank() }.map { path ->
            SourceFileEntry(entryName(path), Path.of(path).readText())
        }
        val wiring = System.getenv("XTSC_EXTERNALS_PROBE_MODULE")?.let { spec ->
            ModuleWiring(spec.substringBefore(':'), entryName(spec.substringAfter(':')))
        }
        val result = generateKotlinExternals(files, module = wiring)
        val check = compileCheck(result.compileCheckSource)
        val jsCheck = JsStdlib.locate()?.let { jsCompileCheck(result.kotlin, it) }

        out.resolve("generated.kt").writeText(result.kotlin)
        out.resolve("compile-check.kt").writeText(result.compileCheckSource)
        out.resolve("compile-errors.txt").writeText(check.errors.joinToString("") { "$it\n" })
        out.resolve("diagnostics.txt").writeText(
            result.diagnostics.joinToString("") { d ->
                "${d.fileName ?: "-"}:${d.line ?: 0}:${d.character ?: 0} ${d.category} TS${d.code} ${d.message}\n"
            }
        )
        out.resolve("js-compile-errors.txt").writeText(
            jsCheck?.errors?.joinToString("") { "$it\n" } ?: "SKIPPED: no Kotlin/JS stdlib klib\n"
        )
        out.resolve("census.txt").writeText(census(result, check, jsCheck, files))
    }

    /**
     * (EXT.21b) THE PER-MODULE PROBE: one generation per DECLARING `declare
     * module "m"` block, each wired to that module and emitted into its own
     * Kotlin package, then ALL of them compiled TOGETHER — as metadata and as
     * Kotlin/JS — because a cross-module reference (`node.net.Socket` named
     * from the `dgram` generation) resolves against another FILE of the same
     * compilation and can be graded no other way.
     *
     * Gated on `XTSC_EXTERNALS_PROBE_MODULES`, the Kotlin package ROOT the
     * set is emitted under (`node` for `@types/node`; `-` for none), over the
     * same `XTSC_EXTERNALS_PROBE_FILES`/`_ROOT`/`_OUT` the flattened probe
     * reads. `XTSC_EXTERNALS_PROBE_MODULE_FILTER` narrows the set to a
     * `:`-separated list of specifiers, for a quick pass over a few modules.
     */
    @Test
    fun `probe one generation per declaring module when XTSC_EXTERNALS_PROBE_MODULES is set`() {
        val packageRoot = System.getenv("XTSC_EXTERNALS_PROBE_MODULES") ?: return
        val fileList = System.getenv("XTSC_EXTERNALS_PROBE_FILES") ?: return
        val root = System.getenv("XTSC_EXTERNALS_PROBE_ROOT")?.trimEnd('/')
        val out = Path.of(System.getenv("XTSC_EXTERNALS_PROBE_OUT") ?: "build/externals-probe-modules")
        val only = System.getenv("XTSC_EXTERNALS_PROBE_MODULE_FILTER")
            ?.split(':')?.filter { it.isNotBlank() }?.toSet()
        Files.createDirectories(out)

        fun entryName(path: String): String {
            val name = if (root != null && path.startsWith(root)) path.removePrefix(root) else path
            return if (name.startsWith("/")) name else "/$name"
        }
        val files = fileList.split(':').filter { it.isNotBlank() }.map { path ->
            SourceFileEntry(entryName(path), Path.of(path).readText())
        }
        val blocks = declaringBlocks(files).filter { only == null || it.first in only }
        val prefix = packageRoot.takeUnless { it == "-" }

        val rows = mutableListOf<String>()
        val sources = mutableListOf<Pair<String, String>>()
        val jsSources = mutableListOf<Pair<String, String>>()
        // (EXT.24) ONE call for the whole set: each generation reads the
        // others, which is what makes a cross-module supertype possible.
        val startedAll = System.nanoTime()
        val set = generateKotlinExternalsPerModule(
            files,
            blocks.map { (specifier, fileName) -> ModuleWiring(specifier, fileName, prefix) },
        )
        val elapsedAll = (System.nanoTime() - startedAll) / 1_000_000
        for ((specifier, _) in blocks) {
            val generated = set.getValue(specifier)
            val elapsed = elapsedAll / blocks.size
            val safe = specifier.replace(Regex("[^A-Za-z0-9]"), "_")
            out.resolve("$safe.kt").writeText(generated.kotlin)
            // (EXT.24) The gate variant too — it is what the compile errors
            // name, and a line number in it is unreadable otherwise.
            out.resolve("$safe.check.kt").writeText(generated.compileCheckSource)
            jsSources += safe to generated.kotlin
            sources += safe to generated.compileCheckSource
            val markers = markerRegex.findAll(generated.kotlin).map { it.groupValues[1] }.toList()
            val declaredAgain = markers.count { it.contains("declared again by another file") }
            val declarations = generated.kotlin.lineSequence()
                .count { declarationRegex.matchEntire(it) != null }
            // (EXT.21b) The three cross-module rows: references SPELLED into
            // another module's package, heritage bases REFUSED because a
            // supertype cannot cross one, and references refused because no
            // package could be named.
            val foreignSpelled = prefix?.let { root ->
                Regex("(?<![A-Za-z0-9_.`])" + Regex.escape(root) + "\\.[A-Za-z_`]").findAll(generated.kotlin).count()
            } ?: 0
            val foreignHeritage = markers.count { it.contains(" - a supertype in another generated package") }
            val foreignRefused = markers.count {
                it.contains("maps to no Kotlin package") || it.contains("has no package of its own") ||
                    it.contains("is shadowed by the declaration")
            }
            rows += listOf(
                specifier,
                generated.kotlin.lines().size.toString(),
                declarations.toString(),
                markers.size.toString(),
                declaredAgain.toString(),
                foreignSpelled.toString(),
                foreignHeritage.toString(),
                foreignRefused.toString(),
                generated.kotlin.lineSequence().firstOrNull { it.startsWith("package ") } ?: "-",
                "${elapsed}ms",
            ).joinToString("\t")
        }

        val check = compileCheckAll(sources)
        val jsCheck = JsStdlib.locate()?.let { jsCompileCheckAll(jsSources, it) }
        out.resolve("modules-compile-errors.txt").writeText(check.errors.joinToString("") { "$it\n" })
        out.resolve("modules-js-compile-errors.txt").writeText(
            jsCheck?.errors?.joinToString("") { "$it\n" } ?: "SKIPPED: no Kotlin/JS stdlib klib\n"
        )
        out.resolve("modules-census.txt").writeText(
            buildString {
                appendLine("== per-module generations (${blocks.size}) ==")
                appendLine(
                    "specifier\tlines\tdeclarations\tmarkers\tdeclaredAgain\t" +
                        "foreignSpelled\tforeignHeritage\tforeignRefused\tpackage\telapsed"
                )
                for (row in rows) appendLine(row)
                appendLine()
                appendLine("total generation: ${elapsedAll}ms")
                appendLine()
                appendLine("== compiled together ==")
                appendLine("metadata errors: ${check.errors.size}, successful: ${check.successful}")
                appendLine(
                    "js errors: ${jsCheck?.errors?.size?.toString() ?: "SKIPPED"}, " +
                        "successful: ${jsCheck?.successful?.toString() ?: "SKIPPED"}"
                )
            }
        )
    }

    /**
     * (EXT.21b) Every top-level `declare module "m" { … }` block that
     * DECLARES something, as (specifier, file) — the 55 `node:x` twins whose
     * body is one `export * from` re-export declare nothing and get no
     * generation of their own (they are reached through the selected
     * module's own closure). A brace-depth scan over the text, which is what
     * a probe needs; the generator itself reads the parsed tree.
     */
    private fun declaringBlocks(files: List<SourceFileEntry>): List<Pair<String, String>> {
        val blocks = mutableListOf<Pair<String, String>>()
        for (file in files) {
            var depth = 0
            var specifier: String? = null
            var declares = false
            for (line in file.content.lineSequence()) {
                if (specifier == null) {
                    val match = moduleBlockRegex.find(line) ?: continue
                    specifier = match.groupValues[1]
                    depth = 1
                    declares = false
                    continue
                }
                depth += line.count { it == '{' } - line.count { it == '}' }
                if (depth > 0) {
                    if (blockDeclarationRegex.containsMatchIn(line)) declares = true
                } else {
                    if (declares) blocks += specifier to file.fileName
                    specifier = null
                }
            }
        }
        return blocks.distinctBy { it.first }
    }

    private fun census(result: KotlinExternals, check: CompileCheck, jsCheck: JsCompileCheck?, files: List<SourceFileEntry>): String {
        val markerTexts = markerRegex.findAll(result.kotlin).map { it.groupValues[1] }.toList()
        val byCategory = markerTexts.groupingBy(::categorize).eachCount()
        val unmapped = markerTexts
            .mapNotNull { unmappedRegex.matchEntire(it)?.groupValues?.get(1) }
            .groupingBy { it }.eachCount()
        val byKind = result.kotlin.lineSequence()
            .mapNotNull { line -> declarationRegex.matchEntire(line)?.groupValues?.get(1) }
            .map { it.replace("open ", "").replace("abstract ", "").replace("external ", "") }
            .groupingBy { it }.eachCount()
        val errorCount = result.diagnostics.count { it.category == DiagnosticCategory.Error }
        val diagnosticsByCode = result.diagnostics.groupingBy { it.code }.eachCount()
        // (EXT.16) The wiring's own census: internal paths, bindings, the
        // statements that stayed loud.
        val internalPaths = markerTexts.filter { internalPathRegex.matches(it) }
        val jsNames = result.kotlin.lineSequence().count { it.trimStart().startsWith("@JsName(") }
        val reExportMarkers = markerTexts.count { it.startsWith("skipped re-export ") }
        return buildString {
            appendLine("== input files (${files.size}) ==")
            for (file in files) appendLine(file.fileName)
            appendLine()
            appendLine("== markers by category (${markerTexts.size} markers, ${byCategory.size} categories) ==")
            appendCounts(byCategory)
            appendLine()
            appendLine("== unmapped types (${unmapped.values.sum()} occurrences, ${unmapped.size} distinct) ==")
            appendCounts(unmapped)
            appendLine()
            appendLine("== declarations emitted by kind (${byKind.values.sum()}) ==")
            appendCounts(byKind)
            appendLine()
            appendLine("== checker diagnostics by code (${result.diagnostics.size}, $errorCount errors) ==")
            appendCounts(diagnosticsByCode.mapKeys { "TS${it.key}" })
            appendLine()
            appendLine("== export = targets ((EXT.20)) ==")
            val targets = exportEqualsTargets(files)
            val rendered = targets.filter { (_, name) -> declaresTopLevel(result.kotlin, name) }
            val vanished = targets.filter { (_, name) -> !declaresTopLevel(result.kotlin, name) }
            appendLine("declared in their block: ${targets.size}, rendered: ${rendered.size}, vanished: ${vanished.size}")
            for ((file, name) in vanished) appendLine("        $file: export = $name")
            appendLine()
            appendLine("== module wiring ==")
            appendLine("header: ${result.kotlin.lineSequence().firstOrNull { it.startsWith("@file:") } ?: "-"}")
            appendLine("@JsName annotations: $jsNames")
            appendLine("re-export markers: $reExportMarkers")
            appendLine("not exported by the package entry: ${internalPaths.size}")
            for (example in internalPaths.take(5)) appendLine("        $example")
            appendLine()
            appendLine("== totals ==")
            appendLine("generated lines: ${result.kotlin.lines().size}")
            appendLine("markers: ${markerTexts.size}")
            appendLine("compile errors: ${check.errors.size}")
            appendLine("compile successful: ${check.successful}")
            appendLine("js compile errors: ${jsCheck?.errors?.size?.toString() ?: "SKIPPED (no Kotlin/JS stdlib klib)"}")
            appendLine("js compile successful: ${jsCheck?.successful?.toString() ?: "SKIPPED"}")
            appendLine("checker diagnostics: ${result.diagnostics.size} ($errorCount errors)")
        }
    }

    /**
     * (EXT.20) Every `export = X` of a top-level `declare module` block whose
     * `X` the SAME block declares (a class, interface, namespace, function,
     * value or enum — not an `import X = require(…)` alias, which re-routes
     * another module and declares nothing), as (file, name) pairs.
     */
    private fun exportEqualsTargets(files: List<SourceFileEntry>): List<Pair<String, String>> =
        files.flatMap { file ->
            val declared = declaredNameRegex.findAll(file.content).mapTo(HashSet()) { it.groupValues[1] }
            exportEqualsRegex.findAll(file.content)
                .map { it.groupValues[1] }
                .filter { it in declared }
                .distinct()
                .map { file.fileName to it }
                .toList()
        }

    /** Whether the generated Kotlin declares [name] at the top level — a class, interface, object, function, value or alias. */
    private fun declaresTopLevel(kotlin: String, name: String): Boolean {
        val regex = Regex("""^public (?:(?:open|abstract|sealed|external) )*(?:interface|class|typealias|fun|val|var|object) (?:<[^>]*> )?`?${Regex.escape(name)}`?\b""", RegexOption.MULTILINE)
        return regex.containsMatchIn(kotlin)
    }

    private fun StringBuilder.appendCounts(counts: Map<String, Int>) {
        for ((key, count) in counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })) {
            appendLine("${count.toString().padStart(6)}  $key")
        }
    }

    /**
     * Replaces the concrete name / rendered type in a marker with a placeholder so
     * markers of one mechanism count together. Spellings are those of
     * `KotlinExternals.kt` and `KotlinExternalsRenderer.kt`; an unrecognised
     * marker keeps its own text.
     */
    private fun categorize(marker: String): String {
        for ((regex, category) in categories) {
            if (regex.matches(marker)) return category
        }
        return marker
    }

    private companion object {
        val markerRegex = Regex("""/\* xtsc: (.*?) \*/""")
        // (EXT.21b) A top-level ambient module block and a declaration inside one.
        val moduleBlockRegex = Regex("""^declare module "([^"]+)"\s*\{""")
        val blockDeclarationRegex = Regex(
            """^\s+(?:export\s+)?(?:declare\s+)?(?:abstract\s+)?(?:class|interface|type|namespace|function|const|var|let|enum)\s+[A-Za-z_${'$'}]"""
        )
        // (EXT.20) A block-level `export = X;` (four-space indent: the body of a top-level module block).
        val exportEqualsRegex = Regex("""^    export = ([A-Za-z_$][A-Za-z0-9_$]*);""", RegexOption.MULTILINE)
        val declaredNameRegex = Regex(
            """^    (?:abstract )?(?:class|interface|namespace|function|const|var|let|enum|type) ([A-Za-z_$][A-Za-z0-9_$]*)""",
            RegexOption.MULTILINE,
        )
        val unmappedRegex = Regex("""unmapped (.*)""")
        val internalPathRegex = Regex(""".* is not exported by the package entry - an internal path a consumer cannot bind""")
        // (EXT.13) Declarations at any depth (a nested object's members are
        // indented) and the `object` kind.
        val declarationRegex =
            // (EXT.24) `override` too — a cross-module supertype turns
            // redeclarations into overrides, and a census that cannot count
            // one reads a rung that ADDS supertypes as a loss of 221
            // declarations.
            Regex(""" *public ((?:(?:open|abstract|sealed|external|override) )*(?:interface|class|typealias|fun|val|var|object))\b.*""")
        val categories: List<Pair<Regex, String>> = listOf(
            // (EXT.20) Declaration merging, before the generic heritage rows.
            Regex("""merged with .* of this scope - TypeScript declaration merging""") to
                "merged with <DECLARATIONS> of this scope - TypeScript declaration merging",
            Regex("""skipped heritage clause extends \S+ of the merged interface \S+ - the class extends .*""") to
                "skipped heritage clause extends <BASE> of the merged interface <NAME> - a Kotlin class extends one class",
            Regex("""skipped heritage clause (?:extends|implements) \S+ - \S+ is the merged class \S+ - .*""") to
                "skipped heritage clause <BASE> - <BASE> is the merged class - an interface cannot extend a class",
            Regex("""skipped property \S+ declared again by the merged interface \S+""") to
                "skipped property <NAME> declared again by the merged interface <NAME>",
            Regex("""skipped interface \S+ merged with the class declares other type parameters .*""") to
                "skipped interface <NAME> merged with the class declares other type parameters",
            Regex("""skipped (?:class|namespace) \S+ of the merged namespace \S+ - an external interface cannot nest a class or object""") to
                "skipped <KIND> <NAME> of the merged namespace <NAME> - an external interface cannot nest a class or object",
            // (EXT.13) Before the generic `unmapped`, which would absorb it.
            Regex("""unmapped .* - shadowed inside .*""") to "unmapped <REF> - shadowed inside <PATH>",
            Regex("""unmapped .*""") to "unmapped <TYPE>",
            Regex("""constraint on \S+: .* not carried""") to "constraint on <T>: <TYPE> not carried",
            Regex("""default for \S+: .* not carried""") to "default for <T>: <TYPE> not carried",
            // (EXT.12) The marker names the kept signature; the category does
            // not, so a census stays comparable across the policy change.
            Regex("""skipped overload of \S+ collapsing to a duplicate signature - kept .*""") to
                "skipped overload of <NAME> collapsing to a duplicate signature",
            Regex("""skipped \S+ declared again by another file - .*""") to
                "skipped <NAME> declared again by another file",
            Regex("""skipped export = .* - module wiring is a later rung""") to
                "skipped export = <EXPR> - module wiring",
            Regex("""skipped default export of .* - module wiring is a later rung""") to
                "skipped default export of <EXPR> - module wiring",
            Regex("""skipped re-export .* - module wiring is a later rung""") to
                "skipped re-export <CLAUSE> - module wiring",
            Regex("""skipped const enum \S+ - no runtime object""") to "skipped const enum <NAME>",
            Regex("""skipped generic type alias \S+ with unmappable body""") to
                "skipped generic type alias <NAME> with unmappable body",
            Regex("""skipped type alias \S+ with unmappable body .*""") to
                "skipped type alias <NAME> with unmappable body <TYPE>",
            // (EXT.15) A parameter property renders; only the rest form stays a skip.
            Regex("""skipped rest parameter property \S+""") to "skipped rest parameter property <NAME>",
            Regex("""skipped index signature keyed by .* - only a string or number key has a Kotlin get/set pair""") to
                "skipped index signature keyed by <TYPE>",
            Regex("""skipped heritage clause extends .*""") to "skipped heritage clause extends <BASE>",
            Regex("""skipped heritage clause implements .*""") to "skipped heritage clause implements <BASE>",
            Regex("""skipped optional generic method \S+""") to "skipped optional generic method <NAME>",
            Regex("""skipped optional method \S+""") to "skipped optional method <NAME>",
            // (EXT.11a)
            Regex("""skipped callable interface \S+ with unmappable signature""") to
                "skipped callable interface <NAME> with unmappable signature",
            Regex("""skipped callable interface \S+ with unmappable base \S+""") to
                "skipped callable interface <NAME> with unmappable base <BASE>",
            Regex("""this parameter .* not carried""") to "this parameter <TYPE> not carried",
            Regex("""skipped constructor this parameter .* not carried""") to
                "skipped constructor this parameter <TYPE> not carried",
            Regex("""skipped this parameter .* not carried - optional method \S+""") to
                "skipped this parameter <TYPE> not carried - optional method <NAME>",
            // (EXT.18) The renames, and the skips a taken suffix keeps.
            Regex("""value \S+ renamed \S+ - Kotlin cannot hold a value and a type of one name(?:; bound by @JsName)?""") to
                "value <NAME> renamed <NAME>Value - a value and a type of one name",
            Regex("""value \S+ renamed \S+ - Kotlin cannot hold a value and an object of one name(?:; bound by @JsName)?""") to
                "value <NAME> renamed <NAME>Value - a value and an object of one name",
            Regex("""function \S+ renamed \S+ - its signature is the constructor of \S+(?:; bound by @JsName)?""") to
                "function <NAME> renamed <NAME>Fn - its signature is the constructor of <NAME>",
            Regex("""skipped value \S+ shares its name with the (?:type|namespace object) \S+ and \S+ is taken too - .*""") to
                "skipped value <NAME> shares its name with the type <NAME> and <NAME>Value is taken too",
            Regex("""skipped function \S+ shares its signature with the constructor of \S+ and \S+ is taken too - .*""") to
                "skipped function <NAME> shares its signature with the constructor of <NAME> and <NAME>Fn is taken too",
            Regex("""skipped (?:value|function) \S+ of the merged namespace \S+ - .*, and a companion member carries no @JsName""") to
                "skipped <KIND> <NAME> of the merged namespace <NAME> - a companion member carries no @JsName",
            // (EXT.11c)
            Regex("""skipped value \S+ shares its name with the type \S+ - module wiring is a later rung""") to
                "skipped value <NAME> shares its name with the type <NAME> - module wiring",
            Regex("""skipped function \S+ shares its signature with the constructor of \S+ - module wiring is a later rung""") to
                "skipped function <NAME> shares its signature with the constructor of <NAME> - module wiring",
            Regex("""narrowed to .* in TypeScript - rendered as the inherited .*""") to
                "narrowed to <TYPE> in TypeScript - rendered as the inherited <TYPE>",
            // (EXT.13)
            Regex("""namespace \S+ - members rendered at top level; .*""") to
                "namespace <NAME> - members rendered at top level",
            Regex("""module "[^"]*" - members rendered at top level; .*""") to
                "module <NAME> - members rendered at top level",
            Regex("""alias \S+ = .* - re-exported name, wiring is a later rung""") to
                "alias <NAME> = <TARGET> - re-exported name",
            Regex("""skipped type alias \S+ inside namespace \S+ - Kotlin aliases are top-level only""") to
                "skipped type alias <NAME> inside namespace <PATH>",
            Regex("""skipped namespace \S+ has a runtime body - .*""") to
                "skipped namespace <NAME> has a runtime body",
            Regex("""skipped namespace \S+ declared again in the same scope - .*""") to
                "skipped namespace <NAME> declared again in the same scope",
            Regex("""skipped \S+ declared again in the same scope - TypeScript merges .*""") to
                "skipped <NAME> declared again in the same scope - TypeScript merges the declarations",
            Regex("""skipped heritage clause (?:extends|implements) \S+ - its \S+ clashes with the one inherited from \S+""") to
                "skipped heritage clause <BASE> - its <MEMBER> clashes with the one inherited from <BASE>",
            Regex("""skipped value \S+ shares its name with the namespace object \S+ - module wiring is a later rung""") to
                "skipped value <NAME> shares its name with the namespace object <NAME> - module wiring",
            // (EXT.16) The wired generation's markers.
            Regex("""\S+ \S+ is not exported by the package entry - .*""") to
                "<KIND> <NAME> is not exported by the package entry - an internal path",
            Regex("""\S+ \S+ is also exported as .* - one @JsName per declaration, bound as \S+""") to
                "<KIND> <NAME> is also exported as <NAMES> - one @JsName per declaration",
            Regex("""\S+ \S+ is exported only as .* - a nested export needs @file:JsQualifier in a file of its own""") to
                "<KIND> <NAME> is exported only as <PATHS> - a nested export needs @file:JsQualifier",
            Regex("""export = \S+ - \S+ is the module object itself: .*""") to
                "export = <NAME> - the module object itself",
            Regex("""skipped re-export .* - '.*' resolves to no file in this generation""") to
                "skipped re-export <CLAUSE> - <SPEC> resolves to no file in this generation",
            Regex("""skipped re-export .* - .* resolves to no declaration""") to
                "skipped re-export <CLAUSE> - <NAME> resolves to no declaration",
            Regex("""skipped re-export .* - .* is not exported by '.*'""") to
                "skipped re-export <CLAUSE> - <NAME> is not exported by <FILE>",
            Regex("""skipped namespace export \S+ from '.*' - .*""") to
                "skipped namespace export <NS> from <SPEC> - needs @file:JsQualifier",
            Regex("""skipped (?:export = |default export of |re-export ).* inside .* - outside the package entry's surface""") to
                "skipped <EXPORT> inside <MODULE> - outside the package entry's surface",
            Regex("""namespace \S+ - the module object \(export = \S+\); members rendered at top level""") to
                "namespace <NAME> - the module object; members rendered at top level",
            Regex("""namespace \S+ - the module member \S+; members rendered at top level .*""") to
                "namespace <NAME> - the module member; members rendered at top level",
            Regex("""namespace \S+ - not exported by the package entry; members rendered at top level""") to
                "namespace <NAME> - not exported by the package entry; members rendered at top level",
            Regex("""module "[^"]*" - the package's own module; members rendered at top level""") to
                "module <NAME> - the package's own module; members rendered at top level",
            Regex("""alias \S+ = .* - re-exported name, a typealias is top-level only and a @JsName value re-export is not built""") to
                "alias <NAME> = <TARGET> - re-exported name, a typealias is top-level only and a @JsName value re-export is not built",
        )
    }

}
