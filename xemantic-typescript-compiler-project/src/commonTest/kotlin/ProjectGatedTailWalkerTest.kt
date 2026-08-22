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

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.ProjectCompiler
import kotlin.test.Test

/**
 * (INC.7) A TAIL WALKER GATED ONTO THE PARTITION VIEW MUST STILL EMIT FOR THE FILE
 * THE PARTITION WAS ASKED ABOUT.
 *
 * 376 of the ~400 tail walkers iterate `binderResults` and so drive a whole-program
 * AST walk however few files a `recheckOnly` build was asked to check — 66% of the
 * incremental floor. Moving such a walker onto `checkedResults` is a strict no-op for
 * a FULL build (`Checker.checkedResults` IS `binderResults` when nothing is
 * partitioned), so neither the corpus nor any whole-program measurement can see the
 * change at all; the only thing it can move is what a PARTITION reports.
 *
 * It can move it in TWO directions and they need different instruments. A gated
 * COLLECTOR starves the partition of program-wide context and INVENTS diagnostics —
 * round 609's 1,174 TS2339 false positives — and `scripts/partition-equivalence.sh`
 * is the detector for that, because an invented row is an ADDED row. This pin is the
 * other direction: a walker that no longer walks the asked file's own subtree LOSES
 * the diagnostic, and it loses it silently.
 *
 * The fixture is therefore built around diagnostics whose OWNERSHIP is established
 * rather than assumed: with `disable checkClassNameInOwnComputedMemberNames` and
 * `disable checkCallTypeArgCount` in `build/pass-lab.txt` both rows disappear and
 * nothing else changes, so TS2449 here is that walker's and TS2558 is that one's.
 * Each test states what the whole-program build reports FIRST — the lesson of
 * `ProjectNarrowFalseNegativeTest`, whose first fixture was vacuous because both arms
 * agreed on an empty list.
 *
 * Batch 2 (fifteen more walkers) adds TS2456 / `checkCircularTypeAlias` on the same
 * terms — ownership established by the lab, then the same two controls.
 */
class ProjectGatedTailWalkerTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val computedFile = "/proj/src/computed.ts"
    private val typeArgsFile = "/proj/src/typeargs.ts"
    private val cyclicFile = "/proj/src/cyclic.ts"
    private val bystanderFile = "/proj/src/bystander.ts"

    private val labelFile = "/proj/src/labels.ts"
    private val baseFile = "/proj/src/bases.ts"
    private val ctorFile = "/proj/src/ctor.ts"
    private val implFile = "/proj/src/impl.ts"


    /**
     * TS2449, owned by `checkClassNameInOwnComputedMemberNames`: a class's own name in
     * one of its own computed member names is a temporal-dead-zone use.
     */
    private val computedText = """
        const KEY = "k";
        class C {
            [C.KEY]: string = "x";
            static KEY = KEY;
        }
        export { C };
    """.trimIndent() + "\n"

    /**
     * TS2456, owned by `checkCircularTypeAlias` — batch 2's walker. Ownership is
     * established the same way batch 1 established its two: with
     * `disable checkCircularTypeAlias` in `build/pass-lab.txt` this row disappears
     * and no other row changes, so the diagnostic is that walker's and a partition
     * that loses it has lost it to the gate.
     */
    private val cyclicText = """
        type Cyc = Cyc;
        export type Alias = Cyc;
    """.trimIndent() + "\n"

    /** TS2558, owned by `checkCallTypeArgCount`. */
    private val typeArgsText = """
        declare function g<T>(x: T): T;
        const r = g<string, number>("a");
        export { r };
    """.trimIndent() + "\n"

    /**
     * Carries no diagnostic of its own. Its job is to make the program bigger than the
     * partition, so a gated walker genuinely skips something — with a single-file
     * program `checkedResults` and `binderResults` are the same list and every arm
     * agrees vacuously.
     */
    private val bystanderText = """
        export function untouched(x: number): number {
            return x + 1;
        }
    """.trimIndent() + "\n"


    /**
     * TS1114, owned by `checkDuplicateLabels` — batch 3a. A label may not be
     * redeclared inside the statement it already labels.
     */
    private val labelText = """
        export function dup(): void {
            lbl: for (let i = 0; i < 1; i++) {
                lbl: for (let j = 0; j < 1; j++) {
                    break lbl;
                }
            }
        }
    """.trimIndent() + "\n"

    /** TS2310, owned by `checkCircularInterfaceBases` — batch 3a. */
    private val baseText = """
        export interface Circ extends Circ {
            member: string;
        }
    """.trimIndent() + "\n"

    /** TS2507, owned by `checkNonConstructorExtends` — batch 3b. */
    private val ctorText = """
        declare const notACtor: number;
        export class Bad extends notACtor {
        }
    """.trimIndent() + "\n"

    /**
     * TS2391, owned by `checkMissingImplementations` — batch 3c, and the pin for a
     * walker cleared on a PRIVACY argument rather than on "it does nothing but
     * emit". That walker WRITES a checker field (`currentMissingImplUniqueSymNames`),
     * so the letter of the rule refuses it; it qualifies because the field is
     * mentioned by exactly two functions in the whole file, both inside the walker's
     * own private closure, no other registered pass reaches either, and the reset
     * sits AFTER the loop so gating the loop cannot leave it armed. This pin is what
     * notices if that stops being true.
     *
     * `checkMixinClassConstructor` (the batch's other privacy clearance, and its
     * biggest row at 9.71 ms) is deliberately NOT pinned here: its TS2545 needs a
     * type parameter whose constraint is a constructor type with an OPTIONAL rest
     * parameter (`emitTs2545IfBrokenMixin` tests `dotDotDotToken && questionToken`),
     * which is a corpus-unique B72.1 shape rather than something a fixture states
     * naturally. A fixture written for it produced NOTHING in either arm, and the
     * whole-program control below is what caught that.
     */
    private val implText = """
        export function missingImpl(x: string): void;
        export const other: number = 1;
    """.trimIndent() + "\n"

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            computedFile to computedText,
            typeArgsFile to typeArgsText,
            cyclicFile to cyclicText,
            bystanderFile to bystanderText,
            labelFile to labelText,
            baseFile to baseText,
            ctorFile to ctorText,
            implFile to implText,
        ),
    )

    private fun rowsIn(diagnostics: List<Diagnostic>, file: String) =
        diagnostics.filter { it.fileName == file }.map { "${it.code}@${it.start}" }.sorted()

    @Test
    fun `the whole-program build reports both rows - the control this rests on`() {
        val whole = ProjectCompiler(vfs()).build("/proj", noEmit = true)
        assert(rowsIn(whole.diagnostics, computedFile).isNotEmpty())
        assert(rowsIn(whole.diagnostics, typeArgsFile).isNotEmpty())
        assert(rowsIn(whole.diagnostics, bystanderFile).isEmpty())
    }

    @Test
    fun `a partition of the computed-name file alone keeps its own walker's row`() {
        val vfs = vfs()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val narrowed = ProjectCompiler(vfs)
            .build("/proj", noEmit = true, recheckOnly = setOf(computedFile))
        assert(rowsIn(whole.diagnostics, computedFile).isNotEmpty())
        assert(rowsIn(narrowed.diagnostics, computedFile) == rowsIn(whole.diagnostics, computedFile))
    }

    @Test
    fun `a partition of the type-argument file alone keeps its own walker's row`() {
        val vfs = vfs()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val narrowed = ProjectCompiler(vfs)
            .build("/proj", noEmit = true, recheckOnly = setOf(typeArgsFile))
        assert(rowsIn(whole.diagnostics, typeArgsFile).isNotEmpty())
        assert(rowsIn(narrowed.diagnostics, typeArgsFile) == rowsIn(whole.diagnostics, typeArgsFile))
    }

    /**
     * The round-609 direction, at fixture scale: a partition asked about a file that
     * carries nothing must not INVENT a row for it because a gated walker no longer
     * sees the rest of the program. Weak on its own — the sweep over 78 real files is
     * the real instrument — but it is the assertion that fails first and cheapest.
     */
    @Test
    fun `a partition of the clean file alone invents nothing`() {
        val narrowed = ProjectCompiler(vfs())
            .build("/proj", noEmit = true, recheckOnly = setOf(bystanderFile))
        assert(rowsIn(narrowed.diagnostics, bystanderFile).isEmpty())
    }

    /**
     * BATCH 2's control. `checkCircularTypeAlias` is one of the fifteen walkers batch
     * 2 moved onto `checkedResults`; this states what the whole-program build reports
     * before any partition is taken, so the pin below cannot pass by both arms being
     * empty.
     */
    @Test
    fun `the whole-program build reports the circular-alias row`() {
        val whole = ProjectCompiler(vfs()).build("/proj", noEmit = true)
        assert(whole.diagnostics.any { it.fileName == cyclicFile && it.code == 2456 })
    }

    @Test
    fun `a partition of the circular-alias file alone keeps its own walker's row`() {
        val vfs = vfs()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val narrowed = ProjectCompiler(vfs)
            .build("/proj", noEmit = true, recheckOnly = setOf(cyclicFile))
        assert(rowsIn(whole.diagnostics, cyclicFile).isNotEmpty())
        assert(rowsIn(narrowed.diagnostics, cyclicFile) == rowsIn(whole.diagnostics, cyclicFile))
    }

    @Test
    fun `the narrowed query through the public API keeps the circular-alias row`() {
        val project = Project.open("/proj", vfs())
        val whole = project.diagnostics(cyclicFile).map { it.code }.sorted()
        assert(whole.contains(2456))
        project.updateFile(cyclicFile, cyclicText)
        assert(project.diagnosticsOf(listOf(cyclicFile)).map { it.code }.sorted() == whole)
    }

    @Test
    fun `the narrowed query through the public API keeps both rows`() {
        val project = Project.open("/proj", vfs())
        val wholeComputed = project.diagnostics(computedFile).map { it.code }.sorted()
        val wholeTypeArgs = project.diagnostics(typeArgsFile).map { it.code }.sorted()
        assert(wholeComputed.isNotEmpty())
        assert(wholeTypeArgs.isNotEmpty())
        project.updateFile(computedFile, computedText)
        assert(project.diagnosticsOf(listOf(computedFile)).map { it.code }.sorted() == wholeComputed)
        project.updateFile(typeArgsFile, typeArgsText)
        assert(project.diagnosticsOf(listOf(typeArgsFile)).map { it.code }.sorted() == wholeTypeArgs)
    }

    // -----------------------------------------------------------------------
    // BATCH 3 — 45 more walkers. Four pins, one per sub-batch plus the privacy
    // clearance, each with the control that makes it non-vacuous: the
    // whole-program build is asserted to report the row FIRST, so the pin cannot
    // pass by both arms agreeing on an empty list (the lesson of
    // `ProjectNarrowFalseNegativeTest`).
    // -----------------------------------------------------------------------

    @Test
    fun `the whole-program build reports all four batch-3 rows - the control`() {
        val whole = ProjectCompiler(vfs()).build("/proj", noEmit = true)
        assert(whole.diagnostics.any { it.fileName == labelFile && it.code == 1114 })
        assert(whole.diagnostics.any { it.fileName == baseFile && it.code == 2310 })
        assert(whole.diagnostics.any { it.fileName == ctorFile && it.code == 2507 })
        assert(whole.diagnostics.any { it.fileName == implFile && it.code == 2391 })
    }

    /** batch 3a — `checkDuplicateLabels`. */
    @Test
    fun `a partition of the label file alone keeps its own walker's row`() {
        val vfs = vfs()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val narrowed = ProjectCompiler(vfs)
            .build("/proj", noEmit = true, recheckOnly = setOf(labelFile))
        assert(rowsIn(whole.diagnostics, labelFile).isNotEmpty())
        assert(rowsIn(narrowed.diagnostics, labelFile) == rowsIn(whole.diagnostics, labelFile))
    }

    /** batch 3a — `checkCircularInterfaceBases`. */
    @Test
    fun `a partition of the circular-base file alone keeps its own walker's row`() {
        val vfs = vfs()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val narrowed = ProjectCompiler(vfs)
            .build("/proj", noEmit = true, recheckOnly = setOf(baseFile))
        assert(rowsIn(whole.diagnostics, baseFile).isNotEmpty())
        assert(rowsIn(narrowed.diagnostics, baseFile) == rowsIn(whole.diagnostics, baseFile))
    }

    /** batch 3b — `checkNonConstructorExtends`. */
    @Test
    fun `a partition of the non-constructor-extends file alone keeps its own walker's row`() {
        val vfs = vfs()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val narrowed = ProjectCompiler(vfs)
            .build("/proj", noEmit = true, recheckOnly = setOf(ctorFile))
        assert(rowsIn(whole.diagnostics, ctorFile).isNotEmpty())
        assert(rowsIn(narrowed.diagnostics, ctorFile) == rowsIn(whole.diagnostics, ctorFile))
    }

    /**
     * batch 3c — `checkMissingImplementations`, the privacy clearance. Its field
     * write is walker-private and its reset sits after the loop, so gating the loop
     * leaves the install intact for every file the partition DOES walk.
     */
    @Test
    fun `a partition of the missing-implementation file alone keeps its own walker's row`() {
        val vfs = vfs()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val narrowed = ProjectCompiler(vfs)
            .build("/proj", noEmit = true, recheckOnly = setOf(implFile))
        assert(rowsIn(whole.diagnostics, implFile).isNotEmpty())
        assert(rowsIn(narrowed.diagnostics, implFile) == rowsIn(whole.diagnostics, implFile))
    }

    /**
     * The round-609 direction for batch 3: a partition asked about ONE of the four
     * new fixtures must not acquire the OTHER three's rows because a gated walker
     * lost the program-wide context it used to suppress with. An invented row is an
     * ADDED row, which is what the 78-file sweep watches for at scale.
     */
    @Test
    fun `a partition of one batch-3 file invents nothing for the others`() {
        val narrowed = ProjectCompiler(vfs())
            .build("/proj", noEmit = true, recheckOnly = setOf(labelFile))
        assert(rowsIn(narrowed.diagnostics, baseFile).isEmpty())
        assert(rowsIn(narrowed.diagnostics, ctorFile).isEmpty())
        assert(rowsIn(narrowed.diagnostics, implFile).isEmpty())
        assert(rowsIn(narrowed.diagnostics, bystanderFile).isEmpty())
    }

    /** The public narrowed-query path, which is what an editor actually drives. */
    @Test
    fun `the narrowed query through the public API keeps all four batch-3 rows`() {
        val project = Project.open("/proj", vfs())
        for ((file, text) in listOf(
            labelFile to labelText, baseFile to baseText,
            ctorFile to ctorText, implFile to implText,
        )) {
            val whole = project.diagnostics(file).map { it.code }.sorted()
            assert(whole.isNotEmpty())
            project.updateFile(file, text)
            assert(project.diagnosticsOf(listOf(file)).map { it.code }.sorted() == whole)
        }
    }
}
