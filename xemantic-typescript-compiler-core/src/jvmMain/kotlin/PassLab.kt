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

import java.nio.file.Files
import java.nio.file.Path

/** Parsed `build/pass-lab.txt` content — see [PassLab]. */
internal data class PassLabConfig(
    val census: Boolean,
    val disabled: Set<String>,
)

/**
 * M0.1 tail-triage lab (JVM-only, round 619): the file-triggered debug facility
 * driving the census batch-disable protocol — see PLAN-PHASE-5.md § QUEUE
 * "(M0.1)" and the round-618/619 session notes.
 *
 * Trigger: a file `build/pass-lab.txt` in the process working directory (the
 * repo root for both the CLI and Gradle test workers). ABSENT — the default and
 * the only committed state — this facility changes NOTHING: one volatile read
 * per [runWithDeepStack] entry. PRESENT, the process is an EXPERIMENT, never a
 * gate: the runner script must create and remove the file around each run.
 *
 * Line-oriented format (`#` comments and blank lines ignored):
 *
 *  - `census` — sets [PassTiming.censusMode] (light per-pass emitted-diagnostic
 *    census accumulating across every compile in the process) and registers a
 *    shutdown hook dumping the reset-immune `censusByPass` accumulator as
 *    `count<TAB>passName` lines to `build/pass-census-out-<pid>.txt`
 *    (pid-suffixed so parallel/recycled test JVMs never clobber each other —
 *    merge the files when analyzing);
 *  - `disable <passName>` — adds an init-dispatch pass name to
 *    [PassTiming.disabledPasses]; `pass()` skips that pass's body.
 *
 * Loaded ONCE per JVM (first compile wins; later file edits are invisible to a
 * live process). Native/wasm builds have no hook — the lab is a JVM triage
 * instrument only.
 */
internal object PassLab {

    @Volatile private var loaded = false

    private val labFile: Path = Path.of("build", "pass-lab.txt")

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            if (!Files.exists(labFile)) return
            val config = parsePassLabLines(Files.readAllLines(labFile))
            if (config.disabled.isNotEmpty()) PassTiming.disabledPasses = config.disabled
            if (config.census) {
                PassTiming.censusMode = true
                Runtime.getRuntime().addShutdownHook(
                    Thread {
                        val out = Path.of(
                            "build",
                            "pass-census-out-${ProcessHandle.current().pid()}.txt",
                        )
                        Files.write(
                            out,
                            PassTiming.censusByPass.entries
                                .sortedByDescending { it.value }
                                .map { "${it.value}\t${it.key}" },
                        )
                    },
                )
            }
            System.err.println(
                "XTSC PASS-LAB ACTIVE (build/pass-lab.txt): " +
                    "censusMode=${config.census}, disabled=${config.disabled.size} passes " +
                    "— this run is an experiment, not a gate",
            )
        }
    }
}

/** Pure parser for the lab file — pinned by PassLabParseTest. */
internal fun parsePassLabLines(lines: List<String>): PassLabConfig {
    var census = false
    val disabled = HashSet<String>()
    for (raw in lines) {
        val line = raw.trim()
        when {
            line.isEmpty() || line.startsWith("#") -> {}
            line == "census" -> census = true
            line.startsWith("disable ") -> {
                val name = line.removePrefix("disable ").trim()
                if (name.isNotEmpty()) disabled.add(name)
            }
        }
    }
    return PassLabConfig(census, disabled)
}
