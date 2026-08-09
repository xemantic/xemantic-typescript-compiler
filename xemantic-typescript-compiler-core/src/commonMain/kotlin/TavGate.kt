/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 */

package com.xemantic.typescript.compiler

/**
 * (WARM.21) round 874 — the in-binary OFF arm of the TAV name-candidate gate.
 *
 * It exists for two reasons, both of them methodological:
 *
 * - **The capture is then a CONTROLLED row on ONE binary** (round 795: build the
 *   verify flag so it doubles as the instrument). The gate returns early INSIDE
 *   the `FrontEnd.TAV` span, so the span's call count — and therefore its
 *   boundary count — is IDENTICAL on both arms, and their difference is
 *   boundary-free by construction (round 793's law). A two-build comparison of
 *   the same row could not say that.
 * - **The ablation gets a switch that is not a source edit**, so the arm cannot
 *   destroy uncommitted work in the file it ablates (round 789).
 *
 * Production is `false` — i.e. the gate is ON. Like every other process-global
 * mode this must be written through `ModeLedger` in `parseCliArgs`, never by a
 * bare assignment: `runCli` restores the ledger in a `finally`, and a bare write
 * silently reconfigures every later request of a `--serve` daemon (round 848).
 */
internal object TavGate {

    /** `true` restores the pre-874 path: every reached identifier pays the pass. */
    var off: Boolean = false

    fun reset() {
        off = false
    }
}
