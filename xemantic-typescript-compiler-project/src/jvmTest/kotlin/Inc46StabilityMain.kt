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

package com.xemantic.typescript.compiler.project

import com.xemantic.typescript.compiler.ExportSignatures
import java.io.File

/**
 * (INC.46) STEP 2 — the STABILITY RATE against a REAL EDIT CORPUS.
 *
 * The queue's refusal threshold, quoted: *"Measure the stability rate against a real
 * edit corpus … Under ~70% the 45x is diluted to nothing and the round should
 * refuse."* Step (1) established that the fingerprint is cheap, stable across
 * rebuilds, and equal under a partition; none of that says how OFTEN a real edit
 * leaves it unmoved, and that is the number the whole mechanism's value is.
 *
 * ## What a "commit" means here, and why the tree is materialised whole
 *
 * The driver (`scripts/inc46-stability.sh`) materialises, per sampled commit that
 * touches `src/compiler`, the FULL `src/compiler` tree at the commit's PARENT and at
 * the commit itself. Whole trees rather than just the changed files: a file taken
 * from another era beside a tree from this one resolves against symbols that may not
 * exist, which degrades its exports to `any` in a way that is neither the before nor
 * the after. The rest of the profile — `tsconfig.json`, `node_modules/@types/node` —
 * is the one `bench-compile-tsc.sh` already materialised, because those are what the
 * compiler is configured by and not what is being varied.
 *
 * ## What is measured
 *
 * Per commit: build the PARENT tree with fingerprints on, build the COMMIT tree the
 * same way, and ask whether any file the commit TOUCHED changed its fingerprint (a
 * file it did not touch cannot, and a touched file that ESCAPES counts as moved,
 * because an escape is exactly "this file may not be proved stable"). A commit is
 * STABLE when no touched file moved — which is when the mechanism would answer
 * project-wide diagnostics from a narrowed build instead of a rebuild.
 *
 * Both arms are whole-program builds ON PURPOSE: step (1) measured the narrowed and
 * whole-program fingerprints equal 24/24, so this arm may use whichever is simpler,
 * and the whole-program one keeps the two trees comparable file for file.
 *
 * Not a gate and not a pin — it asserts only its own non-vacuity.
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: <corpusDir> <projectDir> [srcSubdir]" }
    val corpus = File(args[0])
    val projectDir = File(args[1])
    // (INC.50) The source tree the corpus swaps. Defaults to tsc's own flat
    // `src/compiler`; a library's is nested, which is why [materialize] recurses.
    val srcRel = if (args.size > 2) args[2] else "src/compiler"
    val srcCompiler = File(projectDir, srcRel)
    require(srcCompiler.isDirectory) { "no $srcRel under $projectDir" }

    val cases = corpus.listFiles { f: File -> f.isDirectory }!!.sortedBy { it.name }
    require(cases.isNotEmpty()) { "REFUSED: no cases in $corpus" }

    /**
     * Replaces the source tree wholesale with [tree]'s contents.
     *
     * (INC.50) RECURSIVE, because a library's sources are nested where tsc's
     * `src/compiler` is flat — the flat case is unchanged by this, since a tree with
     * no subdirectories walks the same way.
     */
    fun materialize(tree: File) {
        for (f in srcCompiler.walkBottomUp()) {
            if (f.isFile) f.delete() else if (f != srcCompiler) f.delete()
        }
        var n = 0
        for (f in tree.walkTopDown()) {
            if (!f.isFile) continue
            val rel = f.relativeTo(tree).path
            val target = File(srcCompiler, rel)
            target.parentFile?.mkdirs()
            f.copyTo(target, overwrite = true)
            n++
        }
        require(n > 0) { "REFUSED: empty tree $tree" }
    }

    /** Whole-program fingerprints plus the escape set, for the tree on disk now. */
    fun fingerprints(): Pair<Map<String, Long>, Set<String>> {
        ExportSignatures.enabled = true
        ExportSignatures.reset()
        val project = Project.open(projectDir.path)
        try {
            project.diagnostics()
            return LinkedHashMap(ExportSignatures.fingerprints) to
                LinkedHashSet(ExportSignatures.whole)
        } finally {
            project.close()
            ExportSignatures.enabled = false
        }
    }

    var stable = 0
    var moved = 0
    var skipped = 0
    val movedBecauseEscaped = ArrayList<String>()
    val touchedAnEscape = ArrayList<String>()
    println("cases=${cases.size}")
    for (case in cases) {
        val touched = File(case, "touched.txt").takeIf { it.isFile }?.readLines()
            ?.filter { it.isNotBlank() } ?: emptyList()
        val before = File(case, "before")
        val after = File(case, "after")
        if (!before.isDirectory || !after.isDirectory || touched.isEmpty()) {
            skipped++
            println("${case.name} SKIP (incomplete case)")
            continue
        }
        materialize(before)
        val (fpBefore, escBefore) = fingerprints()
        materialize(after)
        val (fpAfter, escAfter) = fingerprints()
        // (INC.50) NON-VACUITY, checked on the first case rather than assumed. A project
        // the crawl does not find compiles to NO files, every touched file then reads
        // `(absent)`, and the run prints a 0% rate — which looks like a refusal of the
        // mechanism and is a dead instrument (round 790: a verifier reads 0 both when the
        // skip is sound and when it is broken). Both halves matter: the program must have
        // been fingerprinted at all, and the corpus's own paths must key into it.
        if (stable + moved == 0) {
            require(fpBefore.isNotEmpty()) {
                "REFUSED: the build fingerprinted NO files — is $srcRel under $projectDir " +
                    "actually in the program the tsconfig describes?"
            }
            require(touched.any { fpBefore.containsKey(File(srcCompiler, it).canonicalPath) }) {
                "REFUSED: none of ${case.name}'s touched paths $touched key into the " +
                    "fingerprint map (${fpBefore.size} files) — the corpus's paths and the " +
                    "project's do not line up"
            }
        }

        // Only a file the commit TOUCHED can have moved; a file it did not touch is
        // byte-identical in both trees.
        val movedFiles = ArrayList<String>()
        var escaped = false
        for (name in touched) {
            val key = File(srcCompiler, name).canonicalPath
            val b = fpBefore[key]
            val a = fpAfter[key]
            val wasEscape = key in escBefore || key in escAfter
            if (wasEscape) { escaped = true; movedFiles.add("$name(escape)") }
            else if (b == null || a == null) movedFiles.add("$name(absent)")
            else if (b != a) movedFiles.add(name)
        }
        if (movedFiles.isEmpty()) {
            stable++
            println("${case.name} STABLE  touched=${touched.size}")
        } else {
            moved++
            // (INC.47) TWO COUNTS, BECAUSE ONE OF THEM WAS MIS-READ AND COST A ROUND.
            // `escaped` says only that SOME touched file could not be fingerprinted;
            // the case may have moved for a reason of its own beside it. The number
            // that bounds what removing the escape is WORTH is the second one — cases
            // whose EVERY moved file is an escape — and (INC.46)(2) quoted the first
            // under the second's name, reporting an 87.5% ceiling where the real one
            // was 70%. (INC.47) then removed every escape and measured the rate
            // UNCHANGED at 67%, with all 40 verdicts identical.
            if (escaped) touchedAnEscape.add(case.name)
            if (escaped && movedFiles.all { it.endsWith("(escape)") }) {
                movedBecauseEscaped.add(case.name)
            }
            println("${case.name} MOVED   touched=${touched.size} ${movedFiles.take(6)}")
        }
    }
    val decided = stable + moved
    println("---")
    println("STABILITY RATE: $stable stable / $decided decided (${if (decided == 0) 0 else stable * 100 / decided}%), skipped=$skipped")
    println("of the MOVED, ${touchedAnEscape.size} TOUCHED an escaping file: $touchedAnEscape")
    println(
        "of the MOVED, ${movedBecauseEscaped.size} moved ONLY because of an escape — " +
            "i.e. what removing every escape could buy: $movedBecauseEscaped",
    )
}
