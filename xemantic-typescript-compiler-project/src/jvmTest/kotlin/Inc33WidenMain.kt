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

import com.xemantic.typescript.compiler.CallExpression
import com.xemantic.typescript.compiler.ElementAccessExpression
import com.xemantic.typescript.compiler.NewExpression
import com.xemantic.typescript.compiler.NoSubstitutionTemplateLiteralNode
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.PropertyAccessExpression
import com.xemantic.typescript.compiler.QualifiedName
import com.xemantic.typescript.compiler.SignatureCaptureSpan
import com.xemantic.typescript.compiler.StringLiteralNode
import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.TsConfigLoader
import com.xemantic.typescript.compiler.TypeCaptureRequest
import com.xemantic.typescript.compiler.TypeCaptureSpan
import com.xemantic.typescript.compiler.computeParserFlags
import com.xemantic.typescript.compiler.forEachChild
import java.io.File

/**
 * (INC.33) step 1 — PRICE the widening of the caret channels.
 *
 * `completionsAt` and `signatureHelpAt` call `Project.captureIn` with a SINGLE caret
 * span, so a completion in an already-hovered buffer still builds (~200 ms). The
 * tempting fix is to widen the file-wide capture to carry member / scope / signature
 * anchors too, exactly as (INC.13) widened the TYPE channel from a caret to a file
 * for +9..+17 ms. This measures whether that trade holds for the other three
 * channels, on a small / mid / large real file.
 *
 * It measures, it asserts nothing, and it is not a gate. Two things it deliberately
 * does NOT do: it does not go through `Project`'s memo (every arm is a genuine cold
 * narrowed build through `ProjectCompiler`, so no row is a memo hit that looks like
 * a cheap build), and it biases every population IN FAVOUR of the widening — the
 * scope arm names only SCOPE-OWNING nodes rather than the every-node population a
 * naive widening would need, so a refusal read off these numbers is a refusal of the
 * cheapest variant that could work.
 *
 * ## What it measured, and the REFUSAL that followed
 *
 * These are wall figures on a local artifact (tsc's own 78 compiler sources under
 * `build/bench`) and on one box, so NOTHING here is pinnable by the suite and none of
 * it should be read as a claim the tests defend — re-take it with
 * `scripts/inc33-widen-cost.sh` rather than quoting it. Two batches, 2026-08-24, at
 * `dbbb900e`; batch 2 is the replication and the two agree on every sign.
 *
 * ```
 * binder.ts (194 KB)          batch1   batch2      checker.ts (3.1 MB)   1 draw
 *   base, no capture             248      201        base, no capture       2,407
 *   spans.file  (hover TODAY)    275      300        spans.file             3,624
 *   member.caret (compl. TODAY)  270      204        member.caret           2,078
 *   members.file                 380      371        members.file           6,950
 *   scopes.file                  365      366        scopes.file           23,011
 *   sigs.file                    261      228        sigs.file              2,033
 *   spansMembers.file             --      396        --
 *   all.file  (widened hover)    607      586        all.file              28,751
 * ```
 *
 * The widened hover costs **+286 ms** on binder.ts (300 -> 586; the two batches'
 * ranges do not overlap in either batch) and **+25.1 s** on checker.ts, to save a
 * completion build of 204 ms / 2,078 ms. Break-even is therefore **1.40** and
 * **12.1** completions per hover IN A BUFFER WITH NO EDIT SINCE — and the dominant
 * completion path types a `.` first, which is an edit, which clears the memo. The
 * cheapest shippable variant (occurrence spans + members only, `spansMembers.file`)
 * is +96 ms on binder for a break-even of 0.47, but +3,326 ms on checker.ts for
 * 1.60, and it makes EVERY hover ~32% dearer to serve a case that is reachable only
 * when nothing has been typed.
 *
 * The independent second refusal is RETENTION, and it is the harder of the two. One
 * widened entry holds, per file:
 *
 * ```
 *                     types+defs   memberItems   scopeNames   sigItems     total
 *   binder.ts             16,488       243,178      538,354        511   798,531
 *   checker.ts           265,550     4,274,434   49,879,917      9,540    54.4 M
 * ```
 *
 * i.e. 48x and 205x what today's file-wide hover entry holds, and (INC.32) keeps
 * `CAPTURE_MEMO_BUFFERS` of them. The scope channel is the whole of it and the reason
 * is structural rather than incidental: `CapturedScope`'s own KDoc records that a
 * real caret sees hundreds of names, almost all lib globals, and a widened request
 * repeats that set at every one of 13,601 anchors — O(anchors x globals). The arm
 * above used the FAVOURABLE snapped population; `scopeAnchorAt` really answers the
 * innermost enclosing node, of which checker.ts has 275,478.
 *
 * ## What would have to change for the answer to flip
 *
 * Not a wider request — a request is priced per ANCHOR and an editor needs a price
 * per ANSWER. That is a re-entrant capture against a retained checker ((INC.17)'s
 * `ProgramRecheck`), which can answer a span nobody asked for up front without a new
 * build; it is behind (INC.40)'s diagnostics-only valve precisely because its
 * captured-TYPE channel diverges from a fresh build in 43 of 75 files, so the thing
 * that unblocks this is closing that divergence. A widening of the free-name channel
 * additionally needs `CapturedScope` to stop repeating the global set per anchor.
 */
private fun ms(block: () -> Unit): Long {
    val at = System.nanoTime()
    block()
    return (System.nanoTime() - at) / 1_000_000
}

private fun median(v: MutableList<Long>): Long {
    v.sort()
    return v[v.size / 2]
}

private fun walk(root: Node, action: (Node) -> Unit) {
    val stack = ArrayList<Node>()
    stack.add(root)
    while (stack.isNotEmpty()) {
        val node = stack.removeAt(stack.size - 1)
        action(node)
        forEachChild(node) { stack.add(it) }
    }
}

/** Every node a `.`-caret could name as its RECEIVER, in a total order. */
private fun receiversOf(root: Node): List<Node> {
    val found = ArrayList<Node>()
    walk(root) { node ->
        when (node) {
            is PropertyAccessExpression -> found.add(node.expression)
            is QualifiedName -> found.add(node.left)
            is ElementAccessExpression -> {
                val arg = node.argumentExpression
                if (arg is StringLiteralNode || arg is NoSubstitutionTemplateLiteralNode) {
                    found.add(node.expression)
                }
            }
            else -> {}
        }
    }
    found.sortWith(compareBy({ it.pos }, { it.end }))
    return found.distinctBy { (it.pos.toLong() shl 32) or it.end.toLong() }
}

/** Every call a signature-help caret could name. */
private fun callsOf(root: Node): List<Node> {
    val found = ArrayList<Node>()
    walk(root) { node ->
        if (node is CallExpression || node is NewExpression) found.add(node)
    }
    found.sortWith(compareBy({ it.pos }, { it.end }))
    return found.distinctBy { (it.pos.toLong() shl 32) or it.end.toLong() }
}

/**
 * The SCOPE-OWNING nodes — the population the CHEAPEST widening of the free-name
 * channel could name. `SourceIndex.scopeAnchorAt` answers the innermost ENCLOSING
 * node, which is very nearly every node in the file (see the `nodes=` census); a
 * widening that named those is a non-starter on population alone, so the arm here
 * prices the snapped variant instead, which is the only one worth a build.
 */
private fun scopeOwnersOf(root: Node): List<Node> {
    val found = ArrayList<Node>()
    walk(root) { node ->
        val name = node::class.simpleName ?: ""
        val owner = name == "SourceFile" || name == "Block" || name == "ModuleBlock" ||
            name == "CaseBlock" || name == "CatchClause" || name.startsWith("For") ||
            name.contains("Function") || name.contains("Arrow") ||
            name.contains("Method") || name.contains("Class") ||
            name.contains("Constructor") || name.contains("Interface")
        if (owner) found.add(node)
    }
    found.sortWith(compareBy({ it.pos }, { it.end }))
    return found.distinctBy { (it.pos.toLong() shl 32) or it.end.toLong() }
}

private fun countNodes(root: Node): Int {
    var n = 0
    walk(root) { n++ }
    return n
}

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [rotations]" }
    val dir = args[0]
    val rotations = if (args.size > 1) args[1].toInt() else 3

    val project = Project.open(dir)
    val files = project.files
    val options = TsConfigLoader(SystemVfs).load(project.configPath).options
    val texts = HashMap<String, String>()
    for (f in files) texts[f] = File(f).readText()

    val big = files.first { it.endsWith("src/compiler/checker.ts") }
    val mid = files.first { it.endsWith("src/compiler/binder.ts") }
    // "small" = the smallest file that still carries all three anchor kinds, so no
    // arm is measuring an empty population.
    fun indexOf(file: String): SourceIndex {
        val text = texts.getValue(file)
        return SourceIndex.of(text, file, computeParserFlags(file, text, options))
    }
    val small = files
        .filter { it != big && it != mid }
        .sortedBy { texts.getValue(it).length }
        .first { f ->
            val i = indexOf(f)
            receiversOf(i.sourceFile).isNotEmpty() && callsOf(i.sourceFile).isNotEmpty()
        }
    val projectPath = project.configPath
    project.close()

    println("commit-independent census; project=$dir files=${files.size}")

    // ---- CENSUS ---------------------------------------------------------------
    class Pop(
        val file: String,
        val chars: Int,
        val nodes: Int,
        val occ: List<TypeCaptureSpan>,
        val receivers: List<Node>,
        val calls: List<Node>,
        val scopes: List<Node>,
    )

    val selected = when (if (args.size > 2) args[2] else "all") {
        "smallmid" -> listOf(small, mid)
        "big" -> listOf(big)
        else -> listOf(small, mid, big)
    }
    val pops = selected.map { f ->
        val index = indexOf(f)
        val root = index.sourceFile
        val p = Pop(
            f,
            texts.getValue(f).length,
            countNodes(root),
            index.occurrenceNodes().map { TypeCaptureSpan(f, it.pos, it.end) },
            receiversOf(root),
            callsOf(root),
            scopeOwnersOf(root),
        )
        println(
            "CENSUS ${f.substringAfterLast('/')} chars=${p.chars} nodes=${p.nodes} " +
                "occurrenceSpans=${p.occ.size} receivers=${p.receivers.size} " +
                "calls=${p.calls.size} scopeOwners=${p.scopes.size}",
        )
        p
    }

    if (args.size > 2 && args[2] == "census") {
        println("census-only run; no build performed")
        return
    }

    // ---- COST -----------------------------------------------------------------
    val rows = LinkedHashMap<String, MutableList<Long>>()
    fun record(arm: String, v: Long) = rows.getOrPut(arm) { ArrayList() }.add(v)

    fun build(file: String, request: TypeCaptureRequest?): ProjectCompiler.Result =
        ProjectCompiler(SystemVfs).build(
            projectPath,
            noEmit = true,
            recheckOnly = setOf(file),
            typeCapture = request,
        )

    fun spansOf(file: String, nodes: List<Node>) =
        nodes.map { TypeCaptureSpan(file, it.pos, it.end) }

    fun sigSpansOf(file: String, nodes: List<Node>) =
        nodes.map { SignatureCaptureSpan(file, it.pos, it.end, 0) }

    // Six warm-up builds — CLAUDE.md 2026-08-10: two identical arms sit 3.3% apart at
    // WARMUP=2 and 0.8% at 6, and the spread BETWEEN arms is what a verdict gates on.
    repeat(6) { build(mid, null) }
    println("warmup done")

    for (p in pops) {
        val f = p.file
        val short = f.substringAfterLast('/')
        val caretReceiver = p.receivers[p.receivers.size / 2]
        val caretCall = p.calls[p.calls.size / 2]
        val caretScope = p.scopes[p.scopes.size / 2]
        val members = spansOf(f, p.receivers)
        val scopes = spansOf(f, p.scopes)
        val sigs = sigSpansOf(f, p.calls)
        val all = TypeCaptureRequest(p.occ, members, scopes, sigs)

        // Answer SIZES, taken once — the retention question the (INC.32) memo bound
        // has to state, and the served-population question the verdict needs.
        run {
            val r = build(f, all)
            val memberItems = r.capturedMembers.sumOf { it.members.size }
            val scopeItems = r.capturedScopes.sumOf { it.names.size }
            val sigItems = r.capturedSignatures.sumOf { it.signatures.size }
            println(
                "ANSWER $short types=${r.capturedTypes.size} defs=${r.capturedDefinitions.size} " +
                    "memberEntries=${r.capturedMembers.size} memberItems=$memberItems " +
                    "scopeEntries=${r.capturedScopes.size} scopeNames=$scopeItems " +
                    "sigEntries=${r.capturedSignatures.size} sigItems=$sigItems",
            )
        }

        repeat(rotations) { rot ->
            record("$short.base.noCapture", ms { build(f, null) })
            record("$short.spans.file", ms { build(f, TypeCaptureRequest(p.occ)) })
            record(
                "$short.member.caret",
                ms {
                    build(
                        f,
                        TypeCaptureRequest(
                            emptyList(),
                            memberSpans = listOf(
                                TypeCaptureSpan(f, caretReceiver.pos, caretReceiver.end),
                            ),
                        ),
                    )
                },
            )
            record(
                "$short.members.file",
                ms { build(f, TypeCaptureRequest(emptyList(), memberSpans = members)) },
            )
            record(
                "$short.scope.caret",
                ms {
                    build(
                        f,
                        TypeCaptureRequest(
                            emptyList(),
                            scopeSpans = listOf(
                                TypeCaptureSpan(f, caretScope.pos, caretScope.end),
                            ),
                        ),
                    )
                },
            )
            record(
                "$short.scopes.file",
                ms { build(f, TypeCaptureRequest(emptyList(), scopeSpans = scopes)) },
            )
            record(
                "$short.sig.caret",
                ms {
                    build(
                        f,
                        TypeCaptureRequest(
                            emptyList(),
                            signatureSpans = listOf(
                                SignatureCaptureSpan(f, caretCall.pos, caretCall.end, 0),
                            ),
                        ),
                    )
                },
            )
            record(
                "$short.sigs.file",
                ms { build(f, TypeCaptureRequest(emptyList(), signatureSpans = sigs)) },
            )
            // The cheapest SHIPPABLE widening: today's file-wide hover request with the
            // member channel added, which is what a `.`-completion would ride. Measured
            // as one request because that is what the memo would hold.
            record(
                "$short.spansMembers.file",
                ms { build(f, TypeCaptureRequest(p.occ, memberSpans = members)) },
            )
            record("$short.all.file", ms { build(f, all) })
            println("cost $short rotation=$rot done")
        }
    }

    println("== medians (ms) ==")
    for ((arm, vs) in rows) println("MED $arm ${median(vs)}  $vs")
}
