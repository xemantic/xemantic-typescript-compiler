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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (SETUP.1), round 802 — the checker-init SETUP phase is partitioned, and the
 * partition is EXHAUSTIVE BY CONSTRUCTION.
 *
 * Until round 802 `--passTiming` printed a row called `outside-pass` — the
 * checker-init time inside no [pass] wrapper — and nobody had ever named what
 * was in it (975 ms, 3.4% of a compiler-profile compile). It is the ~15 setup
 * statements between `checkLibOption` and the first `if (!declarationOnly)`
 * dispatch, plus two diagnostic retractions at the very end of `init`. Each is
 * now wrapped in its own `init:*` pass, which turns the residue into a
 * MEASUREMENT rather than a remainder: on the compiler profile the row fell
 * **975 → 144 ms** and `init:buildFileLocalTypeMaps` alone is **636 ms**.
 *
 * These pins state the three properties the partition depends on. The one that
 * would otherwise be rediscovered the hard way is [partition is not nested]:
 * `passNanos` is a flat sum, so a `pass()` placed INSIDE another `pass()` is
 * counted twice and the `outside-pass` residue goes NEGATIVE — which reads as
 * "the setup got faster" rather than as an instrumentation bug.
 */
class SetupPhasePartitionTest {

    /** Exercises the setup phase end to end: a file-level interface, type alias,
     *  enum, function and annotated const all reach `buildFileLocalTypeMaps`,
     *  and the TS2322 makes the parity check non-vacuous. */
    private val probeSource = """
        interface Box { value: number; }
        type Alias = Box | undefined;
        enum Kind { A, B }
        function take(b: Box): number { return b.value; }
        const annotated: Alias = { value: 1 };
        const k: Kind = Kind.A;
        const wrong: string = 42;
        export const keep = take({ value: 2 });
    """

    /** Every setup statement the round-802 partition wraps. A wrapper deleted or
     *  renamed fails here BEFORE the `outside-pass` row silently grows again. */
    private val setupPasses = listOf(
        "init:mergeLibGlobals",
        "init:wireGlobalArrayTypes",
        "init:collectUmdGlobalsAndModuleFiles",
        "init:mergeSharedKeepNames",
        "init:mergeFileLocalsIntoGlobals",
        "init:moduleTypeNameIndex",
        "init:snapshotPreAugGlobalKeys",
        "init:mergeModuleAugmentations",
        "init:computePerFileVisibility",
        "init:buildPerFileScopes",
        "init:computeAllEnumValues",
        // (INC.10) `init:trackAllImportReferences` USED to stand here and no
        // longer exists: its product is read only by the emit path, so it runs
        // on `isReferencedAliasDeclaration`'s first ask. Its ABSENCE is pinned
        // by `DeferredSetupPassTest`, which is where the row belongs now.
        "init:buildFileLocalTypeMaps",
        "init:evolvingArrayUseSiteWalks",
        "init:flowDisabledTs2454Retraction",
        "init:tpTargetReturnDedup",
    )

    private fun recordSetup(): Map<String, Long> {
        PassTiming.reset()
        PassTiming.enabled = true
        try {
            diagnose(probeSource)
        } finally {
            PassTiming.enabled = false
        }
        return LinkedHashMap(PassTiming.passNanos)
    }

    @Test
    fun `every setup statement is a named pass`() {
        val recorded = recordSetup()
        val missing = setupPasses.filter { it !in recorded }
        assert(missing.isEmpty())
        PassTiming.reset()
    }

    @Test
    fun `each setup pass runs exactly once per compile`() {
        recordSetup()
        val calls = LinkedHashMap(PassTiming.passCalls)
        // A wrapper accidentally placed inside a per-file loop would show >1 here
        // and would make its row a per-file total rather than a phase total.
        val notOnce = setupPasses.filter { it in calls && calls[it] != 1 }
        assert(notOnce.isEmpty())
        PassTiming.reset()
    }

    @Test
    fun `the partition is not nested - the pass sum never exceeds checker-init`() {
        recordSetup()
        // `outside-pass` is printed as checkerInitNanos - sum(passNanos). Nesting a
        // pass inside another double-counts the inner one, so the residue turns
        // negative; a flat partition can only leave a non-negative remainder.
        val sum = PassTiming.passNanos.values.sum()
        assert(sum <= PassTiming.checkerInitNanos)
        PassTiming.reset()
    }

    @Test
    fun `the setup wrappers are behaviour-free - instrumented and plain runs agree`() {
        PassTiming.enabled = false
        PassTiming.reset()
        val off = diagnose(probeSource)
        PassTiming.enabled = true
        val on = try {
            diagnose(probeSource)
        } finally {
            PassTiming.enabled = false
        }
        assert(on == off)
        assert(off.any { it.code == 2322 })
        PassTiming.reset()
    }

    @Test
    fun `buildFileLocalTypeMaps is the setup pass that does type-system work`() {
        recordSetup()
        // Round 802's finding, pinned as a SHAPE rather than as a millisecond
        // figure: of the sixteen setup passes exactly one resolves declaration
        // types, which is why it is 65% of the phase. The purely structural ones
        // must stay at zero — a new eager resolution added to any of them shows
        // up here before it shows up in the cost gate.
        val structural = listOf(
            "init:mergeLibGlobals",
            "init:collectUmdGlobalsAndModuleFiles",
            "init:mergeSharedKeepNames",
            "init:mergeFileLocalsIntoGlobals",
            "init:moduleTypeNameIndex",
            "init:snapshotPreAugGlobalKeys",
            "init:buildPerFileScopes",
        )
        val typing = PassTiming.getTypeOfExpressionByPass
        val offenders = structural.filter { (typing[it] ?: 0L) > 0L }
        assert(offenders.isEmpty())
        PassTiming.reset()
    }
}
