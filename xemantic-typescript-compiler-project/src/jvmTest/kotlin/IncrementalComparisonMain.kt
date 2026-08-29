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

import java.io.File

/**
 * Our side of the tsgo-vs-us incremental comparison: the SAME four cells
 * `scripts/tsgo-incremental-bench.sh` measures, over the SAME tree and the SAME two
 * edits, so the two tables can be read against each other.
 *
 * **The asymmetry is the point and must not be hidden.** tsgo's incremental state is
 * `.tsbuildinfo` ON DISK, so every one of its cells is a fresh process that re-reads
 * that state; ours is a live `Project`, so ours are in-session and pay neither a
 * process start nor a state read. Neither number is "the" number — a batch tool wants
 * tsgo's model and an editor wants ours, and the comparison is only honest when each
 * is labelled with the model it belongs to.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir>" }
    val dir = args[0]
    val binder = File(dir, "src/compiler/binder.ts")
    val orig = File("/tmp/claude-1000/binder.orig.ts").readText()
    val body = File("/tmp/claude-1000/binder.body.ts").readText()
    val sig = File("/tmp/claude-1000/binder.sig.ts").readText()
    binder.writeText(orig)

    fun ms(block: () -> Unit): Long {
        val at = System.nanoTime(); block(); return (System.nanoTime() - at) / 1_000_000
    }
    fun median(xs: List<Long>) = xs.sorted()[xs.size / 2]

    // ---- COLD: a brand-new Project, nothing warmed. The JVM is cold too, which is
    //      why this cell is reported separately from every other one.
    val coldProject = Project.open(dir)
    val cold0 = ms { coldProject.diagnostics() }
    coldProject.close()
    println("cold, cold JVM (first build ever) : $cold0 ms")

    // ---- warm the JVM the way a long-lived host would be warm
    repeat(4) {
        val p = Project.open(dir)
        p.diagnostics(); p.close()
    }

    val full = ArrayList<Long>(); val noop = ArrayList<Long>()
    val bodyMs = ArrayList<Long>(); val sigMs = ArrayList<Long>()
    repeat(3) {
        binder.writeText(orig)
        val p = Project.open(dir)
        full.add(ms { p.diagnostics() })
        noop.add(ms { p.diagnostics() })
        p.updateFile(binder.path, body)
        bodyMs.add(ms { p.diagnostics() })
        p.updateFile(binder.path, orig)
        p.diagnostics()
        p.updateFile(binder.path, sig)
        sigMs.add(ms { p.diagnostics() })
        p.close()
    }
    println("full build, warm JVM             : $full  median=${median(full)} ms")
    println("no-op (nothing changed)          : $noop  median=${median(noop)} ms")
    println("after BODY-ONLY edit             : $bodyMs  median=${median(bodyMs)} ms")
    println("after SIGNATURE edit             : $sigMs  median=${median(sigMs)} ms")
    binder.writeText(orig)
}
