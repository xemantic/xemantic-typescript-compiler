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

package com.xemantic.typescript.compiler

/**
 * One `export as namespace X` occurrence in a file's source text: the UMD
 * global [name] and the offset [pos] at which that identifier starts.
 */
internal class UmdExportAsNamespaceOccurrence(val name: String, val pos: Int)

/**
 * The pattern. The parser produces no AST node for `export as namespace X` (a
 * documented misparse), so the construct is found by scanning the source text.
 *
 * Two checker passes read it — [Checker.checkUmdGlobalVsDeclareGlobalConst] and
 * [Checker.checkCrossFileModuleAugmentationDuplicates] — and until (WARM.7)
 * each compiled and ran its own copy over the FULL text of every checked file,
 * i.e. the same ~10 MB scanned twice per compile. Round 859 measured that: the
 * two are the SLOWEST-WARMING passes in the whole ~416-pass tail (0.85x and
 * 1.05x against a 2.90x median, the first genuinely slower warm than cold) and
 * together **1.30% of a warm rebuild against 0.38% of a cold one**, because
 * `java.util.regex` is already-compiled library code driving a data-dependent
 * automaton — a warm process has nothing new to teach C2 about it, while the
 * hand-written scans around it warm 3x. `docs/perf/warm-tail-attribution.md`
 * § 4.
 *
 * Both passes now go through [scanUmdExportAsNamespace], memoized per FILE by
 * [Checker.umdExportAsNamespaceOccurrences], so the text is scanned once.
 */
private val umdExportAsNamespaceRegex =
    Regex("""(?m)^[ \t]*export[ \t]+as[ \t]+namespace[ \t]+([A-Za-z_$][A-Za-z0-9_$]*)""")

/**
 * Every `export as namespace X` occurrence in [text], in source order — the
 * group-1 identifier of every [umdExportAsNamespaceRegex] match together with
 * its offset, and nothing else.
 *
 * That pair is the NARROWEST thing both reader passes need:
 * [Checker.checkUmdGlobalVsDeclareGlobalConst] builds an occurrence record from
 * the identifier's offset and length, and
 * [Checker.checkCrossFileModuleAugmentationDuplicates] keeps a first-wins
 * name-to-file map. Neither reads any other part of the match, so sharing the
 * whole `MatchResult` would share more than either consumes.
 */
internal fun scanUmdExportAsNamespace(text: String): List<UmdExportAsNamespaceOccurrence> {
    var out: MutableList<UmdExportAsNamespaceOccurrence>? = null
    for (m in umdExportAsNamespaceRegex.findAll(text)) {
        val g = m.groups[1] ?: continue
        val list = out ?: mutableListOf<UmdExportAsNamespaceOccurrence>().also { out = it }
        list.add(UmdExportAsNamespaceOccurrence(g.value, g.range.first))
    }
    return out ?: emptyList()
}
