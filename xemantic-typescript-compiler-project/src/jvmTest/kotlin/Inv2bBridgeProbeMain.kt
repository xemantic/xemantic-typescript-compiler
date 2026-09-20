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

import com.xemantic.typescript.compiler.Expression
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.SyntaxKind
import com.xemantic.typescript.compiler.forEachChild

/**
 * (INV.2b) commit 2's recon, kept so its numbers can be re-taken rather than
 * quoted — `docs/type-oracle.md` § 5 and `ProjectOracleBridgeTest` both cite it.
 *
 * ```
 * ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm
 * java -Xmx6g -cp <test:main:core:deps> \
 *     com.xemantic.typescript.compiler.project.Inv2bBridgeProbeMainKt <projectDir> [files]
 * ```
 *
 * It answers three questions over a REAL project, none of them a wall time:
 *
 *  * **Q1, tree identity** — is the node `Project.nodeAt` answers a node of the
 *    ORACLE's own tree? This is the property the anchor makes structural, and the
 *    probe exists partly to record that it ALREADY HELD: a round that cannot say
 *    what its change fixed is a round that has not measured it.
 *  * **Q2, naming** — do the oracle's file names match the project's own keys?
 *    `TypeOracle.storeOf` is keyed by name, so a mismatch is a silent "no answer".
 *  * **Q3, the prize** — how far wrong does a host go by hand-rolling the descent
 *    (`pos <= off < end`, the obvious reading of a node's span) instead of
 *    bounding each span at its own last token? Reported as a node disagreement, as
 *    a change in the ORACLE'S ANSWER (the number that matters), and separately
 *    over offsets that BEGIN an identifier, i.e. the realistic caret.
 *
 * Measured 2026-09-20 on `build/bench/tsc-project-*`, 12 files, every 7th offset:
 * Q1 669,350 same tree / 0 different / 0 null; Q2 78 of 78; Q3 190,820 of 669,350
 * disagree (28.5 %), of which the oracle's answer differs at 42,507 (23,303 a
 * different type, 6,939 where only ours answers, 12,265 where only the naive one
 * does) — 6.4 % of all offsets sampled; at identifier starts 591 of 25,533 (2.3 %),
 * 27 changing the type.
 */
fun main(args: Array<String>) {
    val dir = args.getOrNull(0) ?: error("usage: <projectDir> [fileCount]")
    val fileCount = args.getOrNull(1)?.toIntOrNull() ?: 12
    val project = Project.open(dir)
    val oracle = project.typeOracle() ?: error("this project produced no oracle")
    println("project: $dir")
    println("oracle files: ${oracle.files.size}, sampling $fileCount of them every 7th offset")

    // ---- Q2: naming -------------------------------------------------------
    val oracleNames = oracle.files.map { it.fileName }.toSet()
    val matched = project.files.count { it in oracleNames }
    println("Q2 project.files present in the oracle's names: $matched of ${project.files.size}")

    // ---- Q1 + Q3 ----------------------------------------------------------
    var sameTree = 0
    var otherTree = 0
    var noNode = 0
    var agree = 0
    var disagree = 0
    var naiveNull = 0
    var answerSame = 0
    var answerDiffers = 0
    var answerOnlyOurs = 0
    var answerOnlyNaive = 0
    var idOffsets = 0
    var idDisagree = 0
    var idAnswerDiffers = 0

    for (sourceFile in oracle.files.take(fileCount)) {
        val identifierStarts = identifierStartsOf(sourceFile)
        var offset = 0
        while (offset < sourceFile.text.length) {
            val ours = project.nodeAt(sourceFile.fileName, offset)
            when {
                ours == null -> noNode++
                rootOf(ours) === sourceFile -> sameTree++
                else -> otherTree++
            }
            val naive = naiveDescent(sourceFile, offset)
            val atIdentifier = offset in identifierStarts
            if (atIdentifier) idOffsets++
            when {
                ours == null && naive == null -> Unit
                naive == null -> naiveNull++
                ours != null && naive === ours -> agree++
                else -> {
                    disagree++
                    if (atIdentifier) idDisagree++
                    val a = (naive as? Expression)?.let { oracle.typeAt(it) }
                    val b = (ours as? Expression)?.let { oracle.typeAt(it) }
                    when {
                        a == null && b == null -> answerSame++
                        a == null -> answerOnlyOurs++
                        b == null -> answerOnlyNaive++
                        oracle.typeToString(a) == oracle.typeToString(b) -> answerSame++
                        else -> {
                            answerDiffers++
                            if (atIdentifier) idAnswerDiffers++
                        }
                    }
                }
            }
            offset += 7
        }
    }

    val sampled = sameTree + otherTree + noNode
    println("Q1 tree identity over $sampled offsets — same: $sameTree  other: $otherTree  no node: $noNode")
    println("Q3 naive descent — agree: $agree  disagree: $disagree  naive found nothing: $naiveNull")
    println(
        "Q3 at a disagreement, the ORACLE's answer — same: $answerSame  differs: $answerDiffers  " +
            "only ours: $answerOnlyOurs  only naive: $answerOnlyNaive",
    )
    println(
        "Q3 at identifier starts — sampled: $idOffsets  disagree: $idDisagree  " +
            "answer differs: $idAnswerDiffers",
    )
    project.close()
}

/** The [SourceFile] [node] belongs to, by ascending `parent`. */
private fun rootOf(node: Node): SourceFile? {
    var current: Node? = node
    while (current != null) {
        if (current is SourceFile) return current
        current = (current as NodeBase).parent
    }
    return null
}

/** Every offset at which an identifier begins. Iterative — real chains crash a recursion. */
private fun identifierStartsOf(root: SourceFile): Set<Int> {
    val starts = HashSet<Int>()
    val stack = ArrayList<Node>()
    stack.add(root)
    while (stack.isNotEmpty()) {
        val node = stack.removeAt(stack.size - 1)
        if (node.kind == SyntaxKind.Identifier) starts.add(node.pos)
        forEachChild(node) { stack.add(it) }
    }
    return starts
}

/**
 * The descent a host writes when it reads `Node.pos`/`Node.end` as the node's own
 * span — which they are not: `end` runs to the end of the FOLLOWING token (round
 * 910), so sibling spans overlap and this claims offsets belonging to the parent.
 */
private fun naiveDescent(root: SourceFile, offset: Int): Node? {
    var best: Node? = null
    var current: Node = root
    while (true) {
        var found: Node? = null
        forEachChild(current) { child ->
            if (found == null && offset >= child.pos && offset < child.end) found = child
        }
        val next = found ?: return best
        best = next
        current = next
    }
}
