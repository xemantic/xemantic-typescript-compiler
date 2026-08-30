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
 * (INC.67): the PROCESS-GLOBAL lib caches are published copy-on-write, because an
 * IntelliJ-class host reaches them from several threads at once.
 *
 * `xtsc-intellij-plugin`'s `XtscService` keeps a `ConcurrentHashMap` of one
 * `XtscSession` per `tsconfig.json`, and each session owns a single-thread executor —
 * so a monorepo with N configs runs **N compiler threads in one JVM**, all of them
 * reaching [RealLibSnapshots] and [RealLibResolver]. A racing `HashMap.put` can lose
 * entries or corrupt the table; a reference swap of a map that is never mutated after
 * publication cannot, because a reader sees either the old complete map or the new
 * complete one.
 *
 * What a lost race costs is a RECOMPUTATION, which is the benign direction and was
 * always possible here — `getOrPut` on a plain `HashMap` is not atomic either, so two
 * threads could already both parse the same lib file. What copy-on-write removes is
 * the CORRUPTION, and the reason the duplicate is harmless is worth stating: the
 * identity sets these feed compare `Node`s STRUCTURALLY (they are data classes), so
 * two parses of the same lib text are interchangeable to every consumer.
 *
 * ## Why this pin and not a stress test
 *
 * A stress test over a racy map passes most of the time, so it states nothing. The
 * property that IS deterministic is the one copy-on-write actually promises: a
 * snapshot taken BEFORE a miss must not contain the key the miss then adds. That is
 * exactly what an in-place `put` breaks, and it is what the ablation flips.
 *
 * The lib name is deliberately one no other test can use, because these caches are
 * process-global and outlive any one test; the precondition is asserted rather than
 * assumed, so a collision fails loudly instead of passing vacuously.
 */
class SharedLibCachePublicationTest {

    private val bogus = listOf("zzz-not-a-real-lib-INC67")

    /**
     * Every assertion below is over a BOOLEAN computed into a local first.
     *
     * That is CLAUDE.md's standing rule and it was learned again here: these caches hold
     * `SourceFile`s, and power-assert renders every captured subexpression on failure —
     * the first draft of this class put the map itself inside `assert(...)` and its
     * failure arrived as an `OutOfMemoryError` in the diagram builder, masking what had
     * actually gone wrong.
     *
     * They are also written to be robust to a NON-QUIESCENT process: the suite publishes
     * into these maps from other tests (and, as the same first draft discovered, not
     * always from this thread), so an identity comparison of two successive reads is
     * flaky by construction. Copy-on-write's real promise survives that — a map already
     * handed out is never written into again — so that is what is asserted.
     */
    private fun Map<*, *>.mentionsBogus(): Boolean =
        keys.any { it.toString().contains("zzz-not-a-real-lib-INC67") }

    @Test
    fun `a lib-set resolution is published as a new map, never written into the old one`() {
        val before = RealLibResolver.publishedResolutions
        // Precondition, asserted rather than assumed: the key must be fresh, or the pin
        // would pass without the miss it exists to observe.
        val absentBefore = !before.mentionsBogus()
        assert(absentBefore)

        RealLibResolver.resolve(bogus, ScriptTarget.ES2020)

        // THE PROPERTY: the map handed out before the miss did not grow. An in-place
        // `put` — the pre-(INC.67) shape, and ablation arm e1 — makes this false.
        val oldMapUntouched = !before.mentionsBogus()
        // ...and the live map did, so the cache is a cache.
        val newMapHasIt = RealLibResolver.publishedResolutions.mentionsBogus()
        assert(oldMapUntouched)
        assert(newMapHasIt)
    }

    /**
     * CONTROL: an unknown lib key is an ERROR rather than a silently empty answer, so a
     * typo cannot enter the cache as a null and be served for the life of the process.
     */
    @Test
    fun `an unknown lib key is refused rather than cached`() {
        var threw = false
        try {
            RealLibSnapshots.parsedLibFile("zzz-not-a-real-lib-INC67")
        } catch (_: IllegalStateException) {
            threw = true
        }
        val cached = RealLibSnapshots.publishedParses.keys.contains("zzz-not-a-real-lib-INC67")
        assert(threw)
        assert(!cached)
    }

    /**
     * A real lib parse is served from the published map rather than re-parsed. Stated as
     * a COUNT of map entries rather than an identity of two reads, for the
     * non-quiescence reason above: a hit adds no entry, whoever else is publishing.
     */
    @Test
    fun `a second ask for a parsed lib file adds no entry`() {
        RealLibSnapshots.parsedLibFile("es5")
        val sizeAfterFirst = RealLibSnapshots.publishedParses.size
        RealLibSnapshots.parsedLibFile("es5")
        val grew = RealLibSnapshots.publishedParses.size > sizeAfterFirst
        val stillThere = RealLibSnapshots.publishedParses.keys.contains("es5")
        assert(!grew)
        assert(stillThere)
    }
}
