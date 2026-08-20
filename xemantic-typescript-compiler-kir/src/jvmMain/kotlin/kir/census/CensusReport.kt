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

package com.xemantic.typescript.compiler.kir.census

/**
 * The census, as data — every number `docs/kir-structural-typing.md` quotes.
 *
 * A value rather than a printout so a test can assert on it: a measurement whose
 * only form is a rendered table is a measurement nothing can pin.
 */
public class CensusReport internal constructor(
    public val types: List<CensusType>,
    /**
     * The five-dimensional histogram over
     * `(kind, edge, targetClass, sourceClass, open)`; read through [countOf].
     */
    public val bucketCounts: LongArray,
    /** Distinct `(source, target)` pairs at DECLARATION granularity. */
    public val distinctPairs: Int,
    /** Distinct `(source, target)` pairs at raw `Type.id` granularity. */
    public val distinctRawPairs: Int,
    /** Distinct STRUCTURAL pairs, whole population. */
    public val structuralPairs: Int,
    /**
     * Distinct STRUCTURAL pairs restricted to object-ish source AND object-ish,
     * fully-concrete target: the `implements` edges a whole-program closure would
     * actually add, and the itabs a Go-style implementation would build.
     */
    public val designPairs: Int,
    public val structuralFanIn: Map<Int, Int>,
    public val structuralFanOut: Map<Int, Int>,
    public val designFanIn: Map<Int, Int>,
    public val designFanOut: Map<Int, Int>,
    public val anyFanIn: Map<Int, Int>,
    public val obligations: Long,
    public val expressionsSeen: Long,
    public val objectLiteralsSeen: Long,
    public val objectLiteralsWithTarget: Long,
    public val targetUnavailable: Long,
    public val targetUnavailableByKind: LongArray,
    public val skippedSignatures: Long,
    /** Call/`new` argument sites whose callee offered no signature at all. */
    public val callWithNoSignature: Long,
    /** Argument sites past the end of the selected signature's parameter list. */
    public val argumentBeyondParameterList: Long,
    public val lensFailures: Long,
    public val nominalViaBaseTypes: Long,
    public val nominalViaImplements: Long,
    public val heritageNamesUnresolved: Long,
    public val filesSeen: Int,
    public val examples: List<CensusExample>,
) {

    /**
     * Sums the histogram over every dimension left unconstrained.
     *
     * `open = null` means "both", which is the honest default for a headline
     * figure and the wrong default for a conclusion — see [CensusType.mentionsTypeParameter].
     */
    public fun countOf(
        kind: ObligationKind? = null,
        edge: EdgeClass? = null,
        targetClass: TargetClass? = null,
        sourceClass: TargetClass? = null,
        open: Boolean? = null,
    ): Long {
        var total = 0L
        for (k in ObligationKind.entries) {
            if (kind != null && k != kind) continue
            for (e in EdgeClass.entries) {
                if (edge != null && e != edge) continue
                for (t in TargetClass.entries) {
                    if (targetClass != null && t != targetClass) continue
                    for (s in TargetClass.entries) {
                        if (sourceClass != null && s != sourceClass) continue
                        for (o in listOf(false, true)) {
                            if (open != null && o != open) continue
                            total += bucketCounts[bucketIndexOf(k, e, t, s, o)]
                        }
                    }
                }
            }
        }
        return total
    }

    /**
     * The one cell the design question turns on: an object-ish value reaching an
     * object-ish target, structurally, with no open type parameter anywhere.
     */
    public val designObligations: Long
        get() = countOf(
            edge = EdgeClass.STRUCTURAL,
            targetClass = TargetClass.OBJECT_WITH_MEMBERS,
            sourceClass = TargetClass.OBJECT_WITH_MEMBERS,
            open = false,
        )
}

/**
 * The rendered report.
 *
 * Deliberately plain text on one stream: this is read by a person writing a
 * design document, and a format nothing has to parse is a format nothing can
 * silently mis-parse.
 */
public fun CensusReport.renderReport(title: String, elapsedMillis: Long): String = buildString {
    appendLine("# structural-typing census — $title")
    appendLine()
    appendLine("files contributing obligations : $filesSeen")
    appendLine("expressions handed to sink     : $expressionsSeen")
    appendLine("obligations recorded           : $obligations")
    appendLine("distinct types (declaration)   : ${types.size}")
    appendLine("census wall time               : $elapsedMillis ms")
    appendLine()
    appendLine("-- controls (an instrument that cannot see its own misses is not one) --")
    appendLine("obligation sites with no derivable target : $targetUnavailable")
    appendLine("  of which refused as unsound to index    : $skippedSignatures")
    appendLine("  of which callee offered no signature    : $callWithNoSignature")
    appendLine("  of which argument past the param list   : $argumentBeyondParameterList")
    for (kind in ObligationKind.entries) {
        val n = targetUnavailableByKind[kind.ordinal]
        if (n > 0) appendLine("  no target, ${kind.name.padEnd(22)} ${n.toString().padStart(8)}")
    }
    appendLine("lens calls that threw                     : $lensFailures")
    appendLine("declared base edges walked (extends)      : $nominalViaBaseTypes")
    appendLine("declared base edges walked (implements)   : $nominalViaImplements")
    appendLine("implements names that did not resolve     : $heritageNamesUnresolved")
    appendLine()
    val total = obligations.coerceAtLeast(1)
    appendLine("-- 1. edge classes (all obligations) --")
    for (edge in EdgeClass.entries) {
        val n = countOf(edge = edge)
        val closed = countOf(edge = edge, open = false)
        appendLine(
            "  ${edge.name.padEnd(20)} ${n.toString().padStart(9)}  ${pct(n, total)}" +
                "   closed-only ${closed.toString().padStart(9)}",
        )
    }
    appendLine()
    appendLine("-- 1b. target classes --")
    for (targetClass in TargetClass.entries) {
        val n = countOf(targetClass = targetClass)
        appendLine("  ${targetClass.name.padEnd(20)} ${n.toString().padStart(9)}  ${pct(n, total)}")
    }
    appendLine()
    appendLine("-- 1c. obligation kinds --")
    for (kind in ObligationKind.entries) {
        val n = countOf(kind = kind)
        appendLine("  ${kind.name.padEnd(22)} ${n.toString().padStart(9)}  ${pct(n, total)}")
    }
    appendLine()
    appendLine("-- 1d. edge class x target class (all obligations) --")
    appendMatrix { edge, targetClass -> countOf(edge = edge, targetClass = targetClass) }
    appendLine()
    appendLine("-- 1e. the same, restricted to CLOSED obligations (no open type parameter) --")
    appendMatrix { edge, targetClass ->
        countOf(edge = edge, targetClass = targetClass, open = false)
    }
    appendLine()
    appendLine("-- 1f. edge class x SOURCE class, closed obligations onto object targets --")
    appendLine(
        "  " + "source".padEnd(20) +
            EdgeClass.entries.joinToString("") { it.name.take(9).padStart(11) },
    )
    for (sourceClass in TargetClass.entries) {
        val row = EdgeClass.entries.joinToString("") { edge ->
            countOf(
                edge = edge,
                targetClass = TargetClass.OBJECT_WITH_MEMBERS,
                sourceClass = sourceClass,
                open = false,
            ).toString().padStart(11)
        }
        appendLine("  " + sourceClass.name.padEnd(20) + row)
    }
    appendLine()
    appendLine("-- 2. the dispatch problem --")
    appendLine("distinct (source,target) pairs, declaration-keyed : $distinctPairs")
    appendLine("distinct (source,target) pairs, raw Type.id-keyed : $distinctRawPairs")
    appendLine("distinct STRUCTURAL pairs, all shapes            : $structuralPairs")
    appendLine("distinct STRUCTURAL pairs, object->object, closed: $designPairs")
    appendLine("  -> `implements` edges a whole-program closure adds: $designPairs")
    appendLine("  -> obligations in that cell                       : $designObligations")
    appendLine()
    appendLine("-- 3. fan-in per target, object->object closed STRUCTURAL edges --")
    appendHistogram(designFanIn.values)
    appendTop(this@renderReport, designFanIn)
    appendLine()
    appendLine("-- 3b. fan-in per target, ALL structural edges (open ones included) --")
    appendHistogram(structuralFanIn.values)
    appendTop(this@renderReport, structuralFanIn)
    appendLine()
    appendLine("-- 3c. fan-in per target over EVERY assignable edge --")
    appendHistogram(anyFanIn.values)
    appendTop(this@renderReport, anyFanIn)
    appendLine()
    appendLine("-- 4. fan-out per source, object->object closed STRUCTURAL edges --")
    appendHistogram(designFanOut.values)
    appendLine("  max fan-out: ${designFanOut.values.maxOrNull() ?: 0}")
    appendTop(this@renderReport, designFanOut)
    appendLine()
    appendLine("-- 4b. fan-out per source, ALL structural edges --")
    appendHistogram(structuralFanOut.values)
    appendLine("  max fan-out: ${structuralFanOut.values.maxOrNull() ?: 0}")
    appendTop(this@renderReport, structuralFanOut)
    appendLine()
    appendLine("-- 5. object literals --")
    appendLine("object literals visited            : $objectLiteralsSeen")
    appendLine("of which in an obligation position : $objectLiteralsWithTarget")
    appendLine()
    appendLine("-- 6. constructs a nominal encoding cannot express --")
    appendCannotTable("every target reached", anyFanIn.keys.map { types[it] })
    appendCannotTable("STRUCTURAL targets  ", structuralFanIn.keys.map { types[it] })
    appendCannotTable("object->object close", designFanIn.keys.map { types[it] })
    appendLine()
    appendLine("-- worked examples, one per (kind, edge, target class) bucket --")
    examples.sortedWith(compareBy({ it.edge.ordinal }, { it.kind.ordinal })).forEach { e ->
        appendLine("  ${e.edge.name.padEnd(18)} ${e.kind.name.padEnd(22)}")
        appendLine("      source: ${e.sourceText.take(110)}")
        appendLine("      target: ${e.targetText.take(110)}")
        appendLine("      at    : ${e.file}@${e.pos}")
    }
}

private fun StringBuilder.appendCannotTable(label: String, targets: List<CensusType>) {
    appendLine("  [$label]  distinct target types: ${targets.size}")
    appendLine("      index signature      : ${targets.count { it.hasIndexSignature }}")
    appendLine("      call signature       : ${targets.count { it.hasCallSignature }}")
    appendLine("      construct signature  : ${targets.count { it.hasConstructSignature }}")
    appendLine("      optional property    : ${targets.count { it.hasOptionalProperty }}")
    appendLine("      union of object types: ${targets.count { it.isUnionOfObjects }}")
    appendLine("      generic instantiation: ${targets.count { it.isGenericInstantiation }}")
    appendLine("      mentions a type param: ${targets.count { it.mentionsTypeParameter }}")
    appendLine("      anonymous (no decl)  : ${targets.count { !it.isDeclared }}")
}

private fun pct(n: Long, total: Long): String =
    String.format("%6.2f%%", 100.0 * n / total)

private inline fun StringBuilder.appendMatrix(crossinline cell: (EdgeClass, TargetClass) -> Long) {
    appendLine(
        "  " + "target".padEnd(20) +
            EdgeClass.entries.joinToString("") { it.name.take(9).padStart(11) },
    )
    for (targetClass in TargetClass.entries) {
        val row = EdgeClass.entries.joinToString("") { edge ->
            cell(edge, targetClass).toString().padStart(11)
        }
        appendLine("  " + targetClass.name.padEnd(20) + row)
    }
}

private fun StringBuilder.appendTop(report: CensusReport, map: Map<Int, Int>) {
    appendLine("  top 20:")
    map.entries.sortedByDescending { it.value }.take(20).forEach { (index, n) ->
        val type = report.types[index]
        val mark = if (type.mentionsTypeParameter) " [open]" else ""
        appendLine("    ${n.toString().padStart(6)}  ${type.text.take(88)}$mark")
    }
}

private fun StringBuilder.appendHistogram(values: Collection<Int>) {
    if (values.isEmpty()) {
        appendLine("  (empty)")
        return
    }
    val sorted = values.sorted()
    val buckets = listOf(1, 2, 4, 8, 16, 32, 64, 128, 256, Int.MAX_VALUE)
    var lower = 0
    for (upper in buckets) {
        val n = sorted.count { it > lower && it <= upper }
        if (n > 0) {
            val label = if (upper == Int.MAX_VALUE) ">$lower" else "<=$upper"
            appendLine("  ${label.padEnd(8)} ${n.toString().padStart(8)}")
        }
        lower = upper
    }
    appendLine(
        "  n=${sorted.size} median=${sorted[sorted.size / 2]} " +
            "p95=${sorted[(sorted.size * 95) / 100]} max=${sorted.last()} " +
            "mean=${String.format("%.2f", sorted.sum().toDouble() / sorted.size)}",
    )
}
