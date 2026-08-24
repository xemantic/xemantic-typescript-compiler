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
 * (INC.17) **A SHIPPED PATH FOR *DIAGNOSTICS ONLY*, AND KNOWN TO ANSWER A WRONG
 * TYPE.**
 *
 * A LIVE, ALREADY-BUILT PROGRAM that can be asked about a file its check partition
 * did not cover — without crawling, parsing, binding or re-running the 211
 * program-wide checker passes that carry **350.89 ms of a 366.47 ms** narrowed
 * build's floor. Measured on tsc's own 78 sources: **3.06x** (replay 12,572 ms
 * against 38,498 ms of fresh narrowed builds, over 75 questions).
 *
 * ## READ THIS BEFORE USING IT — WHAT IS ALLOWED AND WHAT IS STILL FORBIDDEN
 *
 * **(INC.40) wired this to `Project.diagnosticsOf` and TO NOTHING ELSE.** The
 * split is not a convention, it is a TYPE: `Project` holds this handle only
 * through a private one-way valve (`DiagnosticsOnlyRecheck`) whose single member
 * takes a `Set<String>` and returns a `List<Diagnostic>`, so no
 * [TypeCaptureRequest] is expressible at that boundary and no [CapturedType] can
 * leave it. `Project.quickInfoAt` / `definitionsAt` / `completionsAt` /
 * `signatureHelpAt` / `semanticsAt` / `prepare` cannot reach it even by mistake,
 * and `ProjectRecheckWiringTest` pins both halves.
 *
 * **What is still FORBIDDEN, and why:** hover, quick-info, go-to-definition,
 * completions and signature help. A wrong hover is worse than a slow one — the
 * same judgement (INC.2) made when it refused capture narrowing over 45 divergent
 * spans. Widening the valve is a deliberate act for a future round that has first
 * closed the capture divergence below; it is not something to do in passing
 * because a capture happened to be convenient.
 *
 * `scripts/replay-differential.sh` grades this against a fresh narrowed build, row
 * for row and span for span. At HEAD (2026-08-24) it reads, on tsc's own sources:
 *
 * ```
 * compared: files=75 diagnosticRows=46 filesCarryingDiagnostics=5
 *           typeSpans=373879 definitionSpans=352713
 * DIVERGED: 43 of 75 file(s)      — 0 DIVERGE-DIAG, 0 DIVERGE-DEF, 43 DIVERGE-TYPE
 * ```
 *
 * and on `test-fixtures/partition-gate`, the arm with the DIAGNOSTIC resolution
 * (178 rows over 71 files carrying them, from 78 distinct netting passes, against
 * the profile's ONE):
 *
 * ```
 * compared: files=75 diagnosticRows=178 filesCarryingDiagnostics=71
 * EQUIVALENT: all 75 files agree (diagnostics, types, definitions)
 * ```
 *
 * * **the DIAGNOSTIC channel is UNTOUCHED** — every row agrees, on both arms, and
 *   that is the channel (INC.40) wired;
 * * **the DEFINITION channel is untouched too** — 0 of 352,713 spans;
 * * **the CAPTURED-TYPE channel DIVERGES in 43 of 75 files.**
 *
 * (INC.19) closed the LOST TYPE-PARAMETER CONSTRAINT — the replay used to render
 * `<T extends Node, U>` where a fresh build renders `<T extends Node, U extends
 * T>`, because three walkers in the constraints/defaults region resolved a
 * constraint BEFORE installing the type-parameter scope and `Type.TypeParam`
 * freezes that answer. What is left is TWO classes, neither a constraint. Most
 * rows are the UNION-ALIAS DISPLAY family (INC.26)/(INC.27) — the replay renders
 * `ModuleExportName` where a fresh build renders `StringLiteral | Identifier`,
 * `IsFunctionExpression` for `FunctionExpression | ArrowFunction` — which is
 * first-wins alias naming over an interned union and which (INC.27) proved is an
 * INTERNING-KEY question no policy change here can reach, and in which the FRESH
 * arm is not automatically the correct one ((INC.26)). The residue is lost generic
 * INFERENCE (`Connection[][]` read as `any[][]`, `Map<string, SeenPackageName>` as
 * `Map<any, any>`, a `(key: K, valueInNewMap: U) => T` return read as `any`).
 *
 * Both are silent in the dangerous direction: a plausible-looking type, never an
 * error, and the diagnostics sweep is completely blind to them. That asymmetry —
 * one channel graded equivalent by two arms, another known wrong — is the entire
 * reason the valve exists.
 *
 * ## Why the divergence is not simply a starved pass
 *
 * Two hypotheses are live and (INC.19) names the instrument that separates them:
 * the replay SET is too small (a partition-dependent pass classified invariant),
 * or replaying at all is non-idempotent. The evidence for the second is that an
 * attribution arm re-entering ALL passes over 7 targets burned **53 minutes of CPU
 * without finishing**, against ~50 s for the 205-pass replay over 75 targets —
 * ~100x, the signature of a pass that appends to a side table or re-emits per
 * replay. Do not restart that arm; (INC.19)'s instrument is a BISECTION over the
 * replay set.
 *
 * ## Retention
 *
 * Handed out ONLY to a caller that asked for one ([RecheckHolder]), because it
 * retains the whole checker: every `Type`, every `Symbol` and every side table the
 * build produced. An ordinary compile drops all of that the moment it has its
 * diagnostics, and must go on doing so.
 *
 * ## The contract it is REACHING FOR (and does not yet meet)
 *
 * [recheck] answers about the UNION of every file this program has walked, and
 * that union only grows. The answer for a file is meant to be the answer a build
 * narrowed to that file gives — the same contract `recheckOnly` itself has (INV.6
 * sequential equivalence). That holds for diagnostics and does NOT hold for
 * captures; see above.
 *
 * ## What invalidates it
 *
 * Any edit to any file in the program. There is no invalidation protocol here and
 * there deliberately is not one: the program's text is fixed at the build that
 * produced this handle, and a handle used after an edit would answer about the
 * previous text with no way for a caller to tell.
 */
interface ProgramRecheck {

    /** Every file this program has walked so far — the constructor's partition
     *  plus every set [recheck] has since added. */
    val walkedFiles: Set<String>

    /**
     * The `init` passes a [recheck] re-enters — the RECEIPT of this whole
     * mechanism, and a COUNT rather than a ms.
     *
     * Two witnessed classes, unioned: the passes that READ the check partition,
     * and the passes that depend on rows already in the diagnostics list (they
     * retract, rewrite or decide from them, so a replay's new rows must pass under
     * them too). Both are recorded by the run itself rather than listed, so neither
     * can drift out of step with the checker.
     *
     * It GROWS: a pass whose partition read sits behind a branch the first build
     * did not enter joins the set the moment it is first seen.
     */
    val replayedPasses: Set<String>

    /**
     * Make [files] answerable and return the program's diagnostics for everything
     * walked so far.
     *
     * Files already in [walkedFiles] cost nothing — the answer is already held —
     * so a caller may pass its whole working set every time rather than tracking
     * what it has asked about.
     *
     * **The DIAGNOSTICS in the answer are graded equivalent to a fresh narrowed
     * build's — 0 divergent rows on both arms of the differential, which is what
     * (INC.40) wired to `Project.diagnosticsOf`. The CAPTURED TYPES are NOT: they
     * diverge in 43 of 75 files of the compiler profile (union-alias display, plus
     * lost generic inference; the lost type-parameter constraint that used to
     * dominate is closed). Do not serve a hover from them.** See [ProgramRecheck].
     *
     * @param capture (API.3) a capture request whose spans the re-entry records as
     *   it walks. Only spans in the FRESH files are visited: the spine walks the
     *   files being added, not the ones already walked.
     */
    fun recheck(files: Set<String>, capture: TypeCaptureRequest? = null): RecheckAnswer
}

/**
 * (INC.17) What a [ProgramRecheck.recheck] produced: the same five channels a
 * [CompilationResult] carries, so a caller can substitute one for the other.
 */
class RecheckAnswer(
    val diagnostics: List<Diagnostic>,
    val capturedTypes: List<CapturedType> = emptyList(),
    val capturedDefinitions: List<CapturedDefinition> = emptyList(),
    val capturedMembers: List<CapturedMembers> = emptyList(),
    val capturedScopes: List<CapturedScope> = emptyList(),
    val capturedSignatures: List<CapturedSignatures> = emptyList(),
)

/**
 * (INC.17) The out-parameter by which a caller asks a compile to hand back its
 * live program. Passing one is the ONLY way to reach [ProgramRecheck], and since
 * (INC.40) exactly one shipped path does: `Project.diagnosticsOf`, which puts the
 * handle straight behind a private one-way valve that can express no question but
 * "the diagnostics of these files". Read [ProgramRecheck]'s banner before adding
 * any other caller — the CAPTURED-TYPE channel is known wrong, and a caller that
 * reaches `recheck(files, capture)` directly has walked round the valve.
 *
 * A HOLDER rather than a field on [CompilationResult] / `ProjectCompiler.Result`
 * for two reasons. Both are `data class`es, so a `Checker` reference in one would
 * put identity semantics into their `equals`; and both are produced by every
 * compile in the 13k-baseline corpus, where retaining a checker per result would
 * be a memory cost paid by callers that never asked for it. Passing a holder makes
 * the retention the CALLER's explicit act.
 */
class RecheckHolder {
    var recheck: ProgramRecheck? = null
}

/**
 * (INC.17) A `MutableList` view that reports every dependency on rows ALREADY in
 * it, so a re-entrant recheck can classify a pass by what it did rather than by
 * what a source analyzer thinks it does.
 *
 * ## What counts as a dependency, and what deliberately does not
 *
 * APPENDING is not one: a pass that only calls `add`/`addAll` produces rows, it
 * does not consume them, and whether those rows are a function of the partition is
 * what the partition-read classification already answers.
 *
 * Everything else is: a read (`get`, `iterator`, `contains`, `indexOf`, `size`, …)
 * because the pass may be deciding from a row the spine emitted for a file the
 * partition happened to contain, and every non-appending mutation (`removeAll`,
 * `removeAt`, `set`, `clear`, `retainAll`, an indexed `add`) because those RETRACT
 * or REWRITE rows and a replay's new rows would escape them.
 *
 * `size` is included on purpose — `ctaDiagnosticsBefore = diagnostics.size` is
 * exactly how the largest such consumer takes its cutoff — which is why the
 * `PassTiming` census probe reads the BACKING list instead of this one.
 *
 * ## The delegation is the point
 *
 * Only the recorded operations are overridden; every other member is forwarded to
 * [backing] by Kotlin's delegation, so this cannot drift out of step with
 * `MutableList` the way a hand-written forwarder would. The two lists share ONE
 * backing store, so a holder of either sees the same rows.
 */
internal class RecheckWitnessList(
    private val backing: MutableList<Diagnostic>,
    private val note: () -> Unit,
) : MutableList<Diagnostic> by backing {

    override val size: Int get() { note(); return backing.size }
    override fun get(index: Int): Diagnostic { note(); return backing[index] }
    override fun isEmpty(): Boolean { note(); return backing.isEmpty() }
    override fun contains(element: Diagnostic): Boolean { note(); return backing.contains(element) }
    override fun containsAll(elements: Collection<Diagnostic>): Boolean {
        note(); return backing.containsAll(elements)
    }
    override fun indexOf(element: Diagnostic): Int { note(); return backing.indexOf(element) }
    override fun lastIndexOf(element: Diagnostic): Int { note(); return backing.lastIndexOf(element) }
    override fun iterator(): MutableIterator<Diagnostic> { note(); return backing.iterator() }
    override fun listIterator(): MutableListIterator<Diagnostic> { note(); return backing.listIterator() }
    override fun listIterator(index: Int): MutableListIterator<Diagnostic> {
        note(); return backing.listIterator(index)
    }
    override fun subList(fromIndex: Int, toIndex: Int): MutableList<Diagnostic> {
        note(); return backing.subList(fromIndex, toIndex)
    }
    override fun set(index: Int, element: Diagnostic): Diagnostic {
        note(); return backing.set(index, element)
    }
    override fun removeAt(index: Int): Diagnostic { note(); return backing.removeAt(index) }
    override fun remove(element: Diagnostic): Boolean { note(); return backing.remove(element) }
    override fun removeAll(elements: Collection<Diagnostic>): Boolean {
        note(); return backing.removeAll(elements)
    }
    override fun retainAll(elements: Collection<Diagnostic>): Boolean {
        note(); return backing.retainAll(elements)
    }
    override fun clear() { note(); backing.clear() }
    override fun add(index: Int, element: Diagnostic) { note(); backing.add(index, element) }
    override fun addAll(index: Int, elements: Collection<Diagnostic>): Boolean {
        note(); return backing.addAll(index, elements)
    }

    // APPENDING is not a dependency — deliberately NOT overridden, so `add` and the
    // appending `addAll` go straight to [backing] with no call at all.
}
