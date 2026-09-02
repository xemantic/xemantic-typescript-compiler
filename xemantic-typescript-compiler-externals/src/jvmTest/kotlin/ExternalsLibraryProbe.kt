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
 *   `compile-check.kt`, `compile-errors.txt`, `diagnostics.txt` and `census.txt`.
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

        val files = fileList.split(':').filter { it.isNotBlank() }.map { path ->
            val name = if (root != null && path.startsWith(root)) path.removePrefix(root) else path
            SourceFileEntry(if (name.startsWith("/")) name else "/$name", Path.of(path).readText())
        }
        val result = generateKotlinExternals(files)
        val check = compileCheck(result.compileCheckSource)

        out.resolve("generated.kt").writeText(result.kotlin)
        out.resolve("compile-check.kt").writeText(result.compileCheckSource)
        out.resolve("compile-errors.txt").writeText(check.errors.joinToString("") { "$it\n" })
        out.resolve("diagnostics.txt").writeText(
            result.diagnostics.joinToString("") { d ->
                "${d.fileName ?: "-"}:${d.line ?: 0}:${d.character ?: 0} ${d.category} TS${d.code} ${d.message}\n"
            }
        )
        out.resolve("census.txt").writeText(census(result, check, files))
    }

    private fun census(result: KotlinExternals, check: CompileCheck, files: List<SourceFileEntry>): String {
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
            appendLine("== totals ==")
            appendLine("generated lines: ${result.kotlin.lines().size}")
            appendLine("markers: ${markerTexts.size}")
            appendLine("compile errors: ${check.errors.size}")
            appendLine("compile successful: ${check.successful}")
            appendLine("checker diagnostics: ${result.diagnostics.size} ($errorCount errors)")
        }
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
        val unmappedRegex = Regex("""unmapped (.*)""")
        // (EXT.13) Declarations at any depth (a nested object's members are
        // indented) and the `object` kind.
        val declarationRegex =
            Regex(""" *public ((?:(?:open|abstract|sealed|external) )*(?:interface|class|typealias|fun|val|var|object))\b.*""")
        val categories: List<Pair<Regex, String>> = listOf(
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
            Regex("""skipped parameter property \S+""") to "skipped parameter property <NAME>",
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
        )
    }

}
