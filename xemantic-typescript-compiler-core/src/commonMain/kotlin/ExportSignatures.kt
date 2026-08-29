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
 * (INC.46) THE EXPORTED-SIGNATURE FINGERPRINT — one `Long` per program file,
 * summarising everything an IMPORTER of that file can observe about it.
 *
 * ## What it is for
 *
 * Project-wide diagnostics are the last interactive operation that is
 * whole-program in every case (4.9 s per edit on tsc's own 78 sources, against
 * 108-113 ms for a narrowed build of one file). The standing plan for making it
 * incremental was a reverse-dependency CLOSURE, and it is owner-closed as
 * (INC.35) because a closure only pays on LAYERED code: measured, a file-level
 * graph re-checks 100% of tsc's characters at the median edit, and — this
 * session — so does a SYMBOL-level one, which refutes the `export *` barrel
 * explanation the queue had been carrying.
 *
 * The mechanism that does crack it asks a different question: not WHICH symbols
 * a file uses, but whether the symbols it uses have MOVED. An edit inside a
 * function BODY leaves every exported signature intact, so nothing downstream
 * needs re-checking however dense the use graph is; transitivity fires only when
 * an edit actually moves an exported TYPE.
 *
 * ## Why a fingerprint and not a display string
 *
 * The tempting source is `typeToString(getTypeOfSymbol(exported))` — a RESOLVED
 * type rather than syntax, which is the right soundness instinct (a syntactic
 * hash misses an inferred return type). It is nevertheless the wrong source, in
 * both directions:
 *
 *  - `typeToString` is **not a pure function of the type**. `aliasDisplayMap` is
 *    a FIRST-WINS global keyed by `Type.id` ((INC.11)/(INC.26)/(INC.41)), so the
 *    same type renders differently depending on what was resolved first. That is
 *    spurious invalidation — safe, but potentially frequent enough to eat the
 *    whole prize.
 *  - B58.1 renders `errorType` as `"any"`, so a type that DEGRADES to a
 *    resolution failure would hash identically to a genuine `any`. That is a
 *    MISSED invalidation, i.e. a stale diagnostic, silently — the only direction
 *    that matters.
 *
 * So [Checker.exportedSignatureFingerprints] walks the resolved type
 * STRUCTURALLY and is deliberately ID-FREE: `Type.id` and `Symbol.id` are
 * per-build sequences (INV.6(6c0) makes them per-THREAD as well), so a hash
 * carrying one would differ across two builds of identical text. It reads
 * `Type.Intrinsic.intrinsicName`, which separates `error` from `any` — closing
 * the second hazard by construction rather than by care.
 *
 * ## Status
 *
 * MEASUREMENT-ONLY as of this round, per (INC.46)'s measure-first order of work:
 * step (1) is to read the cost of computing it on a full build, and only a cost
 * that is small licenses steps (2) and (3). [enabled] is therefore false in the
 * shipped compiler and nothing consults [fingerprints].
 *
 * Copied in shape from [LexDefer.fingerprints] — the (INC.16) census — which is
 * why the mixing is a plain `h * 31 + v` fold over deterministically ORDERED
 * inputs rather than anything cleverer.
 */
object ExportSignatures {

    /**
     * When true, a whole-program build computes [fingerprints] once, at a fixed
     * point right after its diagnostics are read.
     *
     * AFTER the diagnostics deliberately: the walk forces type resolutions the
     * check may not have needed, and a resolution performed BEFORE
     * `getDiagnostics` could be a diagnostic this compiler does not otherwise
     * emit. Doing it afterwards makes the arm additive by construction, which is
     * INV.0's rule for a probe.
     */
    var enabled: Boolean = false

    /** `fileName -> fingerprint of everything an importer can observe`. */
    val fingerprints: MutableMap<String, Long> = LinkedHashMap()

    /**
     * `fileName -> true` when the file's export surface could NOT be fingerprinted
     * exactly and every edit to it must therefore invalidate the whole program.
     *
     * The escapes are the shapes whose effect is not confined to importers:
     * a global augmentation (`declare global`, `declare module "…"`,
     * `export as namespace`), a SCRIPT file (no module syntax at all — its
     * top-level names are program-wide), and any export whose name this walk
     * cannot enumerate exactly (a binding pattern in an `export const`).
     * Recorded rather than hidden: a consumer that ignored it would be unsound in
     * the silent direction.
     */
    val whole: MutableSet<String> = LinkedHashSet()

    /** Total exported names fingerprinted, over every file. */
    var exports: Long = 0

    /** `fileName -> nanoseconds` its own fingerprint cost, for the (INC.46) step-(1)
     *  refusal threshold, which is stated per FILE (`types.ts`'s 874 exports). */
    val fileNanos: MutableMap<String, Long> = LinkedHashMap()

    /** `fileName -> exported names fingerprinted`. */
    val fileExports: MutableMap<String, Int> = LinkedHashMap()

    /** Structural nodes the type walk visited — the cost driver, censused. */
    var typeNodes: Long = 0

    /** Files whose walk ran out of node budget — see the ceiling in `Checker`. */
    var budgetStops: Long = 0

    /** Nanoseconds spent computing [fingerprints], measured by the caller. */
    var nanos: Long = 0

    fun reset() {
        fingerprints.clear()
        whole.clear()
        fileNanos.clear()
        fileExports.clear()
        exports = 0
        typeNodes = 0
        budgetStops = 0
        nanos = 0
    }
}
