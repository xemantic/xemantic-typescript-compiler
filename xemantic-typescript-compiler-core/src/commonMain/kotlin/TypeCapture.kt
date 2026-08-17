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
 * One AST node's RAW span, as the thing a [TypeCaptureRequest] names.
 *
 * RAW deliberately: [start] is `Node.pos` and [end] is `Node.end` exactly as the
 * parser wrote them, INCLUDING the fact that `Node.end` is the end of the token
 * FOLLOWING the node (round 910 — `Parser.getEnd()` is the scanner position read
 * after the one-token lookahead, so sibling spans overlap and `[start, end)` is not
 * a containment test). Nothing here interprets the numbers; they are an IDENTITY,
 * compared for equality against the nodes of a parse of the same text with the same
 * [ParserFlags], which INV.1(e) makes the same parse.
 *
 * That is the whole reason a capture is keyed on a span pair rather than on a caret
 * offset: "which node is the caret on" is a question with real subtleties (the
 * overlapping ends above, trivia, the half-open boundary convention), and every one
 * of them is answered ONCE, by whoever owns the caret — `SourceIndex` in the
 * `-project` module — instead of being approximated a second time inside the
 * checker, where there is no token index to answer it with.
 */
data class TypeCaptureSpan(
    val fileName: String,
    val start: Int,
    val end: Int,
)

/**
 * The type the checker computed at a requested [TypeCaptureSpan], as text.
 *
 * VALUE-TYPED on purpose: no `Type`, no `Symbol`, no `Node`. The perf arc keeps
 * rewriting exactly those structures (rounds 889-908 changed packed-key hashing,
 * container types and memo layouts), so publishing them through a capture would
 * freeze them as API.
 *
 * @property kind the `SyntaxKind` name of the node that was typed — the caller
 *   asked about a span, and this says what the compiler found there.
 * @property typeText the checker's own `typeToString` rendering, i.e. the same text
 *   a diagnostic message would name the type by.
 */
data class CapturedType(
    val fileName: String,
    val start: Int,
    val end: Int,
    val kind: String,
    val typeText: String,
)

/**
 * (API.3) A set of positions a compile is asked to record the type AT, handed to
 * the compiler BEFORE the build.
 *
 * ## Why the direction is inwards
 *
 * `Checker` does all its work in its `init` block, so the instance still holds its
 * tables afterwards and "keep the checker and ask it later" looks free. It is not:
 * `getTypeOfIdentifier` consults, IN ORDER, `currentLocalTypes` (its own comment:
 * *"populated during TS2322 checking walk"*), `currentParamBindingNames`,
 * `currentCheckFileName` -> `fileLocalTypeMaps`, `currentFileLocals`, the
 * inference-namespace chain, and only THEN the node-keyed per-file lookup. At rest
 * the first is an empty map and the file fields are null, so a post-hoc query skips
 * five reads and falls through towards globals — for a function-body local that
 * does not merely lose narrowing, it can resolve to an unrelated same-named global.
 * `currentLocalTypes` is STATEMENT-POSITION-scoped and built as the walk proceeds,
 * so it cannot be reconstructed for an arbitrary position without re-walking to
 * that position — which is the whole argument for capturing during the walk.
 * `TypeCaptureMeasurementTest` measures the difference rather than asserting it.
 *
 * ## Cost
 *
 * A capture is a COMPILE. It BATCHES, which is what makes that acceptable: one
 * build can capture every span in a file, so "semantic info for file X" is one
 * compile rather than N.
 *
 * ## Off is free
 *
 * A null request (the default everywhere) leaves the compiler's behaviour and its
 * counters untouched: the checker's per-node hook is one null-valued instance field
 * read and a perfectly-predicted branch — the shape `SpineDispatch.mode` has had
 * since round 732 — and the field is null for every file when no span is requested.
 * Nothing is allocated and no argument is evaluated at the call site (round 900: a
 * probe's guard cannot protect its own ARGUMENT, because Kotlin evaluates arguments
 * strictly).
 *
 * ## What a capture may cost the build it rides on
 *
 * Typing a node the checker had no reason to type is extra WORK and can populate
 * caches, so a captured build is not guaranteed to produce byte-identical
 * diagnostics to an uncaptured one. Callers therefore do not reuse a captured
 * build's diagnostics as the project's diagnostics.
 */
data class TypeCaptureRequest(
    /**
     * The spans to record, in any order. Duplicates are harmless (a span is
     * recorded once, by the DEEPEST node carrying it).
     */
    val spans: List<TypeCaptureSpan>,
) {

    /**
     * The spans indexed by file, as packed `(start, end)` keys — the form the
     * checker's per-node test needs.
     *
     * The packing is round 889's degenerate shape (`Long.hashCode` folds
     * `(a shl 32) or b` onto `a xor b`) and it is left un-finalized DELIBERATELY:
     * these sets hold the handful of spans a host asked about, so no bucket
     * distribution exists to degenerate. Should a caller ever request spans in
     * bulk, finalize the key with an odd multiply as `packIdPair` does.
     */
    internal val keysByFile: Map<String, Set<Long>> =
        spans.groupBy { it.fileName }
            .mapValues { (_, group) -> group.mapTo(HashSet()) { packSpanKey(it.start, it.end) } }

    internal companion object {

        /** `(start, end)` as one key. */
        internal fun packSpanKey(start: Int, end: Int): Long =
            (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)
    }
}
