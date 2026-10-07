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

package com.xemantic.typescript.goport.report

import com.xemantic.typescript.goport.ir.IrPackage
import com.xemantic.typescript.goport.lower.Refusal

/** The porter's accounting: what was lowered mechanically, stubbed, overridden — and why. */
class Report {

    class PkgStats {
        var lowered = 0
        var stubbed = 0
        var omitted = 0
        var overridden = 0
        var decls = 0
    }

    val packages = LinkedHashMap<String, PkgStats>()
    val reasons = HashMap<String, Int>()
    val reasonLines = HashMap<String, Int>()
    val details = HashMap<String, MutableMap<String, Int>>()
    val refusedDecls = ArrayList<String>()
    val stale = ArrayList<String>()
    val collisions = ArrayList<String>()
    val overridesUsed = ArrayList<String>()
    val loweredDecls = ArrayList<String>()

    private fun stats(p: IrPackage) = packages.getOrPut(p.shortPath) { PkgStats() }

    fun lowered(p: IrPackage, qname: String, lines: Int) {
        stats(p).apply { lowered += lines; decls++ }
        loweredDecls += qname
    }

    /** Functions lowered `inline` (docs/goport-lowering.md § 3). */
    val inlinedDecls = java.util.TreeSet<String>()

    fun inlined(qname: String) {
        inlinedDecls += qname
    }

    val refusedSet = HashSet<String>()

    fun refused(p: IrPackage, qname: String, lines: Int, r: Refusal, stub: Boolean) {
        refusedSet += qname
        stats(p).apply {
            if (stub) stubbed += lines else omitted += lines
            decls++
        }
        reasons.merge(r.reason, 1, Int::plus)
        reasonLines.merge(r.reason, lines, Int::plus)
        if (r.detail.isNotEmpty()) details.getOrPut(r.reason) { HashMap() }.merge(r.detail, 1, Int::plus)
        refusedDecls += "${qname.substringAfter("/internal/")}\t$lines\t${r.reason}\t${r.detail}"
    }

    fun overridden(p: IrPackage, qname: String, lines: Int) {
        stats(p).apply { overridden += lines; decls++ }
        overridesUsed += qname
    }

    fun stale(qname: String, have: String, want: String, file: String) {
        stale += "$file: override for $qname was written against $have, the Go source is now $want"
    }

    /** Functions the switch-splitting rule split (qname → parts). */
    val switchSplits = LinkedHashMap<String, Int>()

    fun switchSplit(qname: String, parts: Int) {
        switchSplits[qname] = parts
    }

    fun collision(msg: String) {
        collisions += msg
    }

    fun render(): String = buildString {
        appendLine("goport report")
        appendLine()
        appendLine(String.format("%-16s %8s %8s %8s %8s %8s %7s", "package", "lowered", "stubbed", "omitted", "override", "total", "mech%"))
        var tl = 0; var ts = 0; var to = 0; var tv = 0
        for ((p, s) in packages) {
            val total = s.lowered + s.stubbed + s.omitted + s.overridden
            appendLine(String.format("%-16s %8d %8d %8d %8d %8d %6.1f%%", p, s.lowered, s.stubbed, s.omitted, s.overridden, total, if (total == 0) 0.0 else 100.0 * s.lowered / total))
            tl += s.lowered; ts += s.stubbed; to += s.omitted; tv += s.overridden
        }
        val tt = tl + ts + to + tv
        appendLine(String.format("%-16s %8d %8d %8d %8d %8d %6.1f%%", "TOTAL", tl, ts, to, tv, tt, if (tt == 0) 0.0 else 100.0 * tl / tt))
        appendLine("(Go lines of top-level declarations; a struct's lines include its methods.)")
        appendLine()
        appendLine("inline functions (${inlinedDecls.size}):")
        inlinedDecls.forEach { appendLine("  ${it.removePrefix("github.com/microsoft/typescript-go/internal/")}") }
        appendLine()
        appendLine("refusals by reason (declarations / Go lines):")
        for ((r, n) in reasons.entries.sortedByDescending { reasonLines[it.key] }) {
            appendLine(String.format("  %-34s %5d %7d", r, n, reasonLines[r] ?: 0))
            details[r]?.entries?.sortedByDescending { it.value }?.take(12)?.forEach { (d, c) ->
                appendLine(String.format("      %-60s %4d", d.take(60), c))
            }
        }
        appendLine()
        appendLine("overrides used: ${overridesUsed.size}")
        overridesUsed.forEach { appendLine("  $it") }
        appendLine()
        appendLine("switch-split (JIT.1): ${switchSplits.size}")
        switchSplits.forEach { (q, n) -> appendLine("  $q -> $n parts") }
        if (collisions.isNotEmpty()) {
            appendLine()
            appendLine("NAME COLLISIONS (${collisions.size}):")
            collisions.forEach { appendLine("  $it") }
        }
        if (stale.isNotEmpty()) {
            appendLine()
            appendLine("STALE OVERRIDES (${stale.size}):")
            stale.forEach { appendLine("  $it") }
        }
    }
}
