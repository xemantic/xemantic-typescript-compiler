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

import com.xemantic.typescript.goport.emit.CodeWriter
import com.xemantic.typescript.goport.ir.Node
import com.xemantic.typescript.goport.ir.bool
import com.xemantic.typescript.goport.ir.int
import com.xemantic.typescript.goport.ir.str
import com.xemantic.typescript.goport.naming.Naming

/** What an unlabeled or labeled `break`/`continue` lowers to. */
class Target(
    /** The Go label naming this statement, if any. */
    val goLabel: String?,
    val kind: Kind,
    /** The Kotlin label (loops always carry one; a switch only when something breaks it). */
    val kLabel: String,
) {
    enum class Kind { LOOP, SWITCH, RANGE_FUNC }

    /** A switch wrapped in `run label@{ }` because a `break` targets it. */
    var switchBroken = false
}

/** A string local lowered as a window `base[off:off+len]` (docs/goport-lowering.md § 3). */
class View(val base: String, val off: String, val len: String)

/** Per function-like activation (the top-level function or a func literal). */
class Frame(val results: List<Int>, val namedResults: List<Int>?) {
    var deferFrame: String? = null
    /**
     * A `return` inside a range-over-func body (the yield function): the flag and result locals of
     * the OUTERMOST such loop of this frame (docs/goport-lowering.md § 3); null outside one.
     */
    var rangeReturn: Pair<String, String>? = null
    val targets = ArrayDeque<Target>()
}

/**
 * The lowering context of one top-level declaration (a function or method, with its nested
 * func literals). Local names are unique across the whole declaration — Go's block scoping is
 * flattened — so a declaration can be hoisted anywhere and Kotlin never warns about shadowing.
 */
class FnCtx(val fc: FileCtx, val qname: String, val tm: TypeMapper) {
    val pc: PkgCtx get() = fc.pc
    val types get() = pc.types

    /** The receiver object, lowered as `this`. */
    var recvObj: Int? = null
    /** The receiver is `this` of a nil-safe extension (nullable). */
    var recvNullable = false
    /** Names of the enclosing class's members (bare references to package functions that collide get qualified). */
    var classMembers: Set<String> = emptySet()

    private val used = HashSet<String>()
    private val names = HashMap<Int, String>()
    val boxed = HashSet<Int>()

    /** Func-typed parameters declared non-null (an inline function's, [Program.inlineFuncs]): called without `!!`. */
    val nonNullFnParams = HashSet<Int>()

    /** The string parameter [Lowering.paramDecls] declares as a window `(base, offset, length)` (-1: none). */
    var windowParamIdx: Int = -1

    /** Indices of the func-typed parameters [Lowering.paramDecls] declares non-null. */
    var nonNullParamIdx: Set<Int> = emptySet()
    private var tmp = 0
    private var label = 0

    val frames = ArrayDeque<Frame>()

    /** The switch-splitting rule ([SwitchSplit]): while lowering one PART, the split switch and the clauses it keeps. */
    var splitSwitch: Node? = null
    var splitKeep: Set<Int> = emptySet()

    /** The declaration's body (the view analysis of [CallLowering.viewCandidates] walks it). */
    var root: Node? = null
    /** Locals the substring-elimination rule lowers as string windows, once computed. */
    var viewable: Set<Int>? = null
    /** Struct locals `return x` may hand out without a copy ([Lowering.ownedLocals]), once computed. */
    var ownedLocals: Set<Int>? = null
    /** Declared string windows: Go object id → its (base, offset, length) locals. */
    val views = HashMap<Int, View>()

    /** Top-level private helpers the lowering hoisted out of this declaration (large literal tables). */
    val helpers = ArrayList<String>()
    val frame: Frame get() = frames.last()

    /** The current statement writer (func literal bodies are rendered at its depth). */
    lateinit var w: CodeWriter

    fun fresh(base: String): String {
        while (true) {
            val n = "${base}${tmp++}"
            if (n !in used) {
                used += n
                return n
            }
        }
    }

    fun freshLabel(base: String = "l"): String = "$base${label++}"

    fun reserve(name: String) {
        used += name
    }

    /** Declares local object [id] and answers its Kotlin name. */
    fun declare(id: Int): String {
        names[id]?.let { return it }
        val o = pc.obj(id)
        var base = Naming.escape(o.str("name") ?: "v")
        if (base == "_" || base.isEmpty()) base = "unused"
        if (base.startsWith("`")) base = base.trim('`') + "_"
        var n = base
        var i = 1
        while (n in used || n in pc.topValues || n in classMembers || n in RESERVED_LOCAL) n = "${base}_${i++}"
        used += n
        names[id] = n
        if (o.bool("addr") && !tm.isStructValue(o.int("t")!!)) boxed += id
        return n
    }

    fun nameOf(id: Int): String? = names[id]

    fun isDeclared(id: Int) = id in names

    companion object {
        val RESERVED_LOCAL = setOf("it", "field", "value")
    }
}
