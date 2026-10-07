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

package com.xemantic.typescript.goport.lower

import com.xemantic.typescript.goport.ir.Node
import com.xemantic.typescript.goport.ir.bool
import com.xemantic.typescript.goport.ir.int
import com.xemantic.typescript.goport.ir.k
import com.xemantic.typescript.goport.ir.list
import com.xemantic.typescript.goport.ir.obj
import com.xemantic.typescript.goport.ir.str
import java.util.Base64

/**
 * The switch-splitting rule (docs/goport-lowering.md § 3, CLAUDE.md (JIT.1)): a Go function whose
 * body holds one huge top-level `switch` (tsgo's generated per-kind tables) would lower to one JVM
 * method over HotSpot's 8,000-bytecode `HugeMethodLimit`, which is never JIT-compiled.
 *
 * The function becomes a small DISPATCHER plus N PARTS. Each part is the WHOLE function body
 * lowered again with the switch restricted to a subset of its clauses (the `default` clause, the
 * statements before the switch and the tail after it are in every part), so a part is the original
 * function for every tag value it is handed — including a value no case lists, which reaches the
 * `default`/tail there. The dispatcher only evaluates the tag and calls exactly one part, so every
 * statement still runs once and in its original order. That holds when:
 *
 * - the tag is a parameter (or a field chain on one) the function never reassigns — so evaluating
 *   it in the dispatcher first reads the same value the part's switch reads; a field chain is
 *   only accepted when no statement precedes the switch;
 * - the switch has no init statement, no `fallthrough`, and every case is a constant;
 * - the function has no own `defer` and is not variadic.
 *
 * Dispatch: an `Int`-represented tag is a `when` over the case constants (one table/lookupswitch,
 * clauses grouped into parts in SOURCE order, so the first part holds the first-written cases);
 * a byte-string tag is a binary comparison chain over contiguous ranges of the sorted constants
 * (a `when` over thousands of strings is itself a huge method), which needs one constant per
 * clause. Lexicographic `String.compareTo` is the same on every Kotlin target, so the porter's
 * partition and the generated code's comparisons agree.
 */
class SwitchSplit(
    val sw: Node,
    /** Clause indices of each part (the `default` clause is in none: every part lowers it). */
    val parts: List<Set<Int>>,
    /** `null` for an `Int` tag (membership dispatch); for a string tag, the first constant of parts 1..n. */
    val stringBounds: List<String>?,
) {
    companion object {
        /** Source bytes of a switch above which it is split (calibrated: ~0.17-0.39 bytecodes per byte). */
        const val TRIGGER_BYTES = 20_000
        /** Source bytes of clauses per part. */
        const val PART_BYTES = 8_000

        fun plan(body: Node, paramObjs: Set<Int>, pc: PkgCtx, tm: TypeMapper, variadic: Boolean, hasOwnDefer: Boolean): SwitchSplit? {
            if (variadic || hasOwnDefer) return null
            val stmts = body.list("list")
            val idx = stmts.indices.filter { stmts[it].k == "SwitchStmt" }.maxByOrNull { span(stmts[it]) } ?: return null
            val sw = stmts[idx]
            if (span(sw) <= TRIGGER_BYTES) return null
            if (sw.obj("init") != null) return null
            val tag = sw.obj("tag") ?: return null
            if (!tagIsStable(tag, paramObjs, pc, idx == 0)) return null
            val rep = tm.repOf(tag.int("t")!!)
            val clauses = sw.list("clauses")
            for (c in clauses) {
                if (c.list("body").lastOrNull()?.let { it.k == "BranchStmt" && it.str("tok") == "fallthrough" } == true) return null
                if (!c.bool("default") && c.list("list").any { it.obj("c") == null }) return null
            }
            val cases = clauses.indices.filter { !clauses[it].bool("default") }
            return when (rep) {
                TypeMapper.Rep.INT -> {
                    val parts = chunk(cases) { span(clauses[it]) }
                    if (parts.size < 2) null else SwitchSplit(sw, parts.map { it.toSet() }, null)
                }
                TypeMapper.Rep.STRING -> {
                    if (cases.any { clauses[it].list("list").size != 1 }) return null
                    val keyed = cases.map { it to stringConst(clauses[it].list("list")[0]) }.sortedBy { it.second }
                    val parts = chunk(keyed) { span(clauses[it.first]) }
                    if (parts.size < 2) null
                    else SwitchSplit(sw, parts.map { p -> p.map { it.first }.toSet() }, parts.drop(1).map { it.first().second })
                }
                else -> null
            }
        }

        private fun span(n: Node): Int = (n.int("e") ?: 0) - (n.int("o") ?: 0)

        private fun <T> chunk(items: List<T>, size: (T) -> Int): List<List<T>> {
            val out = ArrayList<List<T>>()
            var cur = ArrayList<T>()
            var bytes = 0
            for (it in items) {
                val s = size(it)
                if (cur.isNotEmpty() && bytes + s > PART_BYTES) {
                    out += cur
                    cur = ArrayList()
                    bytes = 0
                }
                cur += it
                bytes += s
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }

        /** A case constant's value as a byte string (one `Char` per byte, as the port represents Go strings). */
        fun stringConst(e: Node): String {
            val c = e.obj("c")!!
            check(c.str("kind") == "string") { "string switch with a ${c.str("kind")} case" }
            val b = Base64.getDecoder().decode(c.str("b64") ?: "")
            return String(CharArray(b.size) { (b[it].toInt() and 0xFF).toChar() })
        }

        private fun tagIsStable(tag: Node, paramObjs: Set<Int>, pc: PkgCtx, first: Boolean): Boolean = when (tag.k) {
            "Ident" -> tag.int("obj")?.let { id -> id in paramObjs && !pc.obj(id).bool("mut") && !pc.obj(id).bool("addr") } == true
            "SelectorExpr" -> first && tag.str("selk") == "field" && tagIsStable(tag.obj("x")!!, paramObjs, pc, true)
            "ParenExpr" -> tagIsStable(tag.obj("x")!!, paramObjs, pc, first)
            else -> false
        }
    }
}
