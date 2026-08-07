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
 * (M0.4, round 654): behavioural pins for checkConflictMarkers (TS1185 "Merge
 * conflict marker encountered"), written against the LEGACY per-character
 * line-start scan before it was replaced by the indexOf-per-marker scan.
 *
 * The pass is the compiler's ONLY TS1185 emitter, so every count here is
 * exact. The invariants the rewrite must preserve: a marker counts only at a
 * LINE START (offset 0 or right after a `\n`), all four marker kinds count,
 * each hit spans exactly 7 characters, a run longer than 7 marker characters
 * still counts ONCE (only the line-start offset qualifies), a run SHORTER
 * than 7 never counts (including a truncated one at end-of-file), and the
 * emissions land in ascending source order regardless of which marker kinds
 * are present. `.d.ts` files are skipped entirely.
 *
 * The fixtures are deliberately un-parseable — conflict-marker text is not
 * TypeScript — so only code 1185 is counted; the parser's own recovery
 * diagnostics are irrelevant to this pass.
 */
class ConflictMarkerScanTest {

    private fun markers(ds: List<Diagnostic>) = ds.filter { it.code == 1185 }

    @Test
    fun `a start marker at a line start draws one TS1185 spanning seven chars`() {
        val source = "let a = 1;\n<<<<<<< HEAD\nlet b = 2;\n"
        val ds = markers(diagnose(source))
        assert(ds.size == 1)
        assert(ds[0].start == source.indexOf("<<<<<<<"))
        assert(ds[0].length == 7)
        assert(ds[0].message == "Merge conflict marker encountered.")
    }

    @Test
    fun `all four marker kinds count - in ascending source order`() {
        val source = "<<<<<<< HEAD\nlet a = 1;\n||||||| base\nlet b = 2;\n" +
            "=======\nlet c = 3;\n>>>>>>> other\n"
        val ds = markers(diagnose(source))
        assert(ds.size == 4)
        val starts = ds.map { it.start ?: -1 }
        assert(starts == starts.sorted())
        assert(
            starts == listOf( source.indexOf("<<<<<<<"), source.indexOf("|||||||"), source.indexOf("======="), source.indexOf(">>>>>>>"), )
        )
    }

    @Test
    fun `a marker at offset zero counts`() {
        val ds = markers(diagnose("=======\n"))
        assert(ds.size == 1)
        assert(ds[0].start == 0)
    }

    @Test
    fun `negative control - a marker that is not at a line start never counts`() {
        // indented, and embedded mid-line inside a string
        val ds = markers(diagnose("  <<<<<<< HEAD\nlet s = \"======= x\";\n"))
        assert(ds.size == 0)
    }

    @Test
    fun `a run longer than seven marker chars counts exactly once`() {
        val source = "<<<<<<<<<<<< HEAD\n"
        val ds = markers(diagnose(source))
        assert(ds.size == 1)
        assert(ds[0].start == 0)
    }

    @Test
    fun `negative control - a run of only six marker chars never counts`() {
        assert(markers(diagnose("<<<<<<\n======\n>>>>>>\n||||||\n")).size == 0)
    }

    @Test
    fun `a marker ending exactly at end-of-file counts - a truncated one does not`() {
        assert(markers(diagnose("let a = 1;\n>>>>>>>")).size == 1)
        assert(markers(diagnose("let a = 1;\n>>>>>>")).size == 0)
    }

    @Test
    fun `consecutive marker lines each count`() {
        val source = "<<<<<<<\n=======\n>>>>>>>\n"
        val ds = markers(diagnose(source))
        assert(ds.size == 3)
        assert(ds.map { it.start ?: -1 } == listOf(0, 8, 16))
    }

    @Test
    fun `negative control - a d_ts file is skipped`() {
        assert(markers(diagnose("<<<<<<< HEAD\ndeclare const a: number;\n", fileName = "t.d.ts")).size == 0)
    }
}
