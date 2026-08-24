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
 * (INC.24) THE INSTRUMENT THAT MAKES "the map was rebuilt" INTO "the map was rebuilt
 * WITH THE SAME CONTENT" — the distinction every attempt on
 * `init:buildFileLocalTypeMaps` turns on.
 *
 * (INC.10), (INC.11) and (INC.22) each tried to stop building some of this pass's
 * entries eagerly, and each was refused not because an answer went MISSING but
 * because an answer went WRONG: (INC.11)'s fully deferred arm lost **321
 * resolutions to `any`**, which is a plausible type and therefore invisible to the
 * corpus, to `cost_gate.py` and to all eight dashboard profiles. The one instrument
 * that can see it is the map itself, which is what
 * [Checker.fileLocalTypeMapSnapshot] renders.
 *
 * The pins below are deliberately modest: the snapshot reports what a build LEFT
 * BEHIND (it never builds), so what they establish is that it is non-vacuous, that
 * it discriminates between two files, and that the one arm which does arm the lazy
 * path today ([FltmDefer.eager]) rebuilds an EQUAL map. A pin comparing two
 * different SCOPES belongs with a scope axis, and there is none shipped.
 */
class FileLocalTypeMapSnapshotTest {

    // The file-level DECLARATIONS are what the map stores — an un-annotated `const`
    // is in neither phase, so a fixture of bare constants gives two EMPTY maps and
    // every comparison here would pass vacuously.
    private val twoFiles = arrayOf(
        "/proj/a.ts" to """
            export interface Shape { a: number }
            export type Alias = Shape;
            export declare const shared: Alias;
            export function helper(n: number): number { return n; }
            // A READ of this file's own file-level names, so the map's one reader is
            // asked about `/proj/a.ts` at all. Without it the lazy path never fires
            // here and the arm pin below compares a completed map to an unbuilt one.
            export const used: number = helper(shared.a);
        """,
        "/proj/b.ts" to """
            import { Alias, shared } from "./a.js";
            export class Keeper { }
            export declare const limit: number;
            export const local: Alias = shared;
        """,
    )

    private fun checker(): Checker {
        val options = CompilerOptions()
        val results = twoFiles.map { (name, src) ->
            Binder(options).bind(Parser(src.trimIndent(), name).parse())
        }
        val checker = Checker(options, results, isMultiFileSource = true)
        checker.getDiagnostics()
        return checker
    }

    /**
     * The snapshot is NON-EMPTY for a file that declares things — the assertion
     * that stands between every comparison below and vacuity.
     */
    @Test
    fun `a built map snapshots its file-level declarations`() {
        val map = checker().fileLocalTypeMapSnapshot("/proj/a.ts")
        assert(map != null)
        assert(!map.isNullOrEmpty())
        assert(map.containsKey("Shape"))
        assert(map.containsKey("helper"))
    }

    /**
     * NEGATIVE CONTROL: the map is not a constant, so an equality that holds
     * between two builds of one file is a statement about those builds rather than
     * about two maps that agree whatever happens.
     */
    @Test
    fun `negative control - the two files' maps are not the same map`() {
        val checker = checker()
        val a = checker.fileLocalTypeMapSnapshot("/proj/a.ts")
        val b = checker.fileLocalTypeMapSnapshot("/proj/b.ts")
        assert(a != null)
        assert(b != null)
        assert(a != b)
    }

    /** A file the program does not contain has no map, rather than an empty one. */
    @Test
    fun `negative control - an unknown file has no snapshot at all`() {
        assert(checker().fileLocalTypeMapSnapshot("/proj/absent.ts") == null)
    }

    /**
     * THE ONE ARMED PATH THAT EXISTS TODAY REBUILDS AN **EQUAL** MAP.
     *
     * With [FltmDefer.eager] cut to the `TYPEALIAS` phase, the `init` pass resolves
     * only type aliases and the map's one reader completes the rest on demand. The
     * resulting map must equal what the shipped eager pass writes — "it rebuilds"
     * is not the soundness claim, "it rebuilds the same map" is.
     *
     * The global is SAVED AND RESTORED: `FltmDefer` is fork-global state and a test
     * that assigns the default back would re-arm it for every alphabetically later
     * class (CLAUDE.md's PassLab rule).
     */
    @Test
    fun `the lazily completed map equals the eagerly built one`() {
        val eager = checker().fileLocalTypeMapSnapshot("/proj/a.ts")
        val saved = FltmDefer.eager
        val lazy = try {
            FltmDefer.eager = FltmDefer.Phases.TYPEALIAS_ONLY
            checker().fileLocalTypeMapSnapshot("/proj/a.ts")
        } finally {
            FltmDefer.eager = saved
        }
        assert(!eager.isNullOrEmpty())
        assert(lazy == eager)
        assert(FltmDefer.eager == FltmDefer.Phases.ALL)
    }
}
