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
 * [Checker.umdExportAsNamespaceOccurrences], so the text is scanned once — and
 * since (WARM.7)(b) that scan is hand-written and this pattern is no longer in
 * the compile path at all. It stays LIVE as the oracle
 * `UmdExportAsNamespaceScanTest` differentially compares the scanner against;
 * it is the specification, so it must not be deleted or edited without editing
 * [scanUmdExportAsNamespace] to match.
 */
internal val umdExportAsNamespaceRegex =
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
 *
 * ## (WARM.7)(b): why this is hand-written, and why it is not a *gate*
 *
 * Round 859 proposed recovering the second half of the 1.30% with a cheap
 * pre-filter, and named a `.d.ts` one. That filter is a claim about where the
 * construct may legally appear, and round 792's law is that the dashboard
 * profiles cannot falsify such a claim — the compiler profile has **0 `.d.ts`
 * files among its 78**, so a `.d.ts` gate would read as free on every profile
 * whether or not it is sound. The substring filter that IS sound (the pattern
 * requires the literal `namespace`) buys nothing here: tsc's sources are full of
 * `namespace` declarations, so it would fire and then pay for the scan anyway.
 *
 * So there is no gate. The matcher itself is replaced by an EXACT equivalent,
 * which makes no claim about legality at all and is differentially pinned
 * against [umdExportAsNamespaceRegex]. It is anchored on the literal
 * `namespace`, found with [String.indexOf]: the profile carries 494 of those in
 * 9,977,097 characters, so the whole scan is one linear sweep plus 494
 * constant-time rejections. It also removes a multiplatform hazard — the
 * regex dialect behind `kotlin.text.Regex` is `java.util.regex` on the JVM and a
 * different engine on Native, while this scan is the same code everywhere.
 *
 * The equivalence, term by term:
 *
 *  - `(?m)^` matches at offset 0 and after any line terminator. The default
 *    (no `UNIX_LINES`) terminator set is LF, CR, CRLF, NEL (U+0085), LS
 *    (U+2028) and PS (U+2029) — hence [isUmdLineTerminator]. The CRLF subtlety
 *    costs nothing: the offset BETWEEN the two holds LF, which satisfies
 *    neither `[ \t]` nor `export`, so it can never start a match either way.
 *  - each `[ \t]+` is a run of at least one space or tab, each `[ \t]*` a run
 *    of zero or more; both are scanned backwards from the anchor token.
 *  - the identifier is greedy `[A-Za-z_$][A-Za-z0-9_$]*`, ASCII only.
 *  - matches cannot overlap: every match is anchored at a line start and ends
 *    before that line does, so there is at most one per line, and a successful
 *    match resumes the sweep at its own end exactly as `findAll` does. `namespace`
 *    has no proper border, so it cannot overlap itself either.
 */
internal fun scanUmdExportAsNamespace(text: String): List<UmdExportAsNamespaceOccurrence> {
    var out: MutableList<UmdExportAsNamespaceOccurrence>? = null
    var from = 0
    while (true) {
        val ns = text.indexOf(UMD_NAMESPACE, from)
        if (ns < 0) break
        from = ns + UMD_NAMESPACE.length
        // Backwards from `namespace`: `[ \t]+` `as` `[ \t]+` `export` `[ \t]*` `^`.
        var i = ns
        while (i > 0 && isUmdSpaceOrTab(text[i - 1])) i--
        if (i == ns) continue                            // `[ \t]+` needs at least one
        if (i < 2 || text[i - 1] != 's' || text[i - 2] != 'a') continue
        i -= 2
        val beforeAs = i
        while (i > 0 && isUmdSpaceOrTab(text[i - 1])) i--
        if (i == beforeAs) continue                      // `[ \t]+` needs at least one
        if (i < UMD_EXPORT.length ||
            !text.regionMatches(i - UMD_EXPORT.length, UMD_EXPORT, 0, UMD_EXPORT.length)
        ) continue
        i -= UMD_EXPORT.length
        while (i > 0 && isUmdSpaceOrTab(text[i - 1])) i--
        if (i != 0 && !isUmdLineTerminator(text[i - 1])) continue
        // Forwards from `namespace`: `[ \t]+` then the identifier.
        var j = from
        while (j < text.length && isUmdSpaceOrTab(text[j])) j++
        if (j == from) continue                          // `[ \t]+` needs at least one
        if (j >= text.length || !isUmdIdentifierStart(text[j])) continue
        val start = j
        j++
        while (j < text.length && isUmdIdentifierPart(text[j])) j++
        val list = out ?: mutableListOf<UmdExportAsNamespaceOccurrence>().also { out = it }
        list.add(UmdExportAsNamespaceOccurrence(text.substring(start, j), start))
        from = j
    }
    return out ?: emptyList()
}

private const val UMD_NAMESPACE = "namespace"
private const val UMD_EXPORT = "export"

private fun isUmdSpaceOrTab(c: Char): Boolean = c == ' ' || c == '\t'

/**
 * The line terminators a `(?m)^` recognises when `UNIX_LINES` is not set. CRLF
 * needs no arm of its own — see [scanUmdExportAsNamespace].
 */
private fun isUmdLineTerminator(c: Char): Boolean =
    c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029'

private fun isUmdIdentifierStart(c: Char): Boolean =
    (c in 'A'..'Z') || (c in 'a'..'z') || c == '_' || c == '$'

private fun isUmdIdentifierPart(c: Char): Boolean =
    isUmdIdentifierStart(c) || (c in '0'..'9')
