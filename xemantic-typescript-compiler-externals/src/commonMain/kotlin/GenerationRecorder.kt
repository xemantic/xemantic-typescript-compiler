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

package com.xemantic.typescript.compiler.externals

/**
 * (TSGO.4-b) The generation RECORDER — an instrument for comparing two
 * engines' outputs over the same inputs, never part of what a caller sees.
 *
 * Every public entry point hands its finished output here, keyed by a
 * descriptor of its INPUTS ([generationDescriptor]: file names and contents,
 * the module wiring, the generation's own module). The JVM actual writes it
 * to `$XTSC_EXTERNALS_DUMP/<sha-256 of the descriptor>.kt` when that
 * environment variable is set and does nothing otherwise, so a whole test
 * run over one engine is a directory of outputs a run over another engine
 * can be diffed against file by file (the diagnostics beside it, `<key>.diag`) — the A/B the tsgo re-base was graded
 * with.
 */
internal expect fun recordGeneration(descriptor: String, kotlin: String, diagnostics: List<ExternalsDiagnostic>)

/** The descriptor [recordGeneration] keys by: every input that decides an output. */
internal fun generationDescriptor(files: List<Pair<String, String>>, wiring: ModuleWiring?, extra: String = ""): String =
    buildString {
        for ((name, content) in files) append(name).append('\u0000').append(content).append('\u0001')
        if (wiring != null) {
            append("wiring:").append(wiring.moduleName).append('|').append(wiring.entryFileName)
                .append('|').append(wiring.packageRoot)
        }
        append('\u0002').append(extra)
    }
