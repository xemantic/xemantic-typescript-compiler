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

import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.TsConfigLoader
import com.xemantic.typescript.compiler.computeParserFlags
import java.io.File

/**
 * (API.9) Measure what the widened MEMBER OCCURRENCE SET costs the two sweeps — the
 * harness behind the cost note in `docs/language-service.md` § 10b.
 *
 * Two quantities, deliberately, because only one of them is stable. The POPULATION is a
 * counter: how many spans a reference sweep carries now against how many it carried
 * when it swept identifiers alone. That is deterministic, immune to this box's ±13%
 * swing, and it is the thing the round actually changed. The MILLISECONDS are printed
 * beside it and are only comparable within the run that took them.
 *
 * ```
 * java -Xmx6g -cp <classes:deps> \
 *   com.xemantic.typescript.compiler.project.OccurrenceCostMainKt \
 *   build/bench/tsc-project-<sha> src/compiler/types.ts 'SyntaxKind {'
 * ```
 *
 * Not a gate and not a pin — it asserts nothing.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "usage: <projectDir> <fileSuffix> <needle naming an identifier>" }
    val project = Project.open(args[0])
    val files = project.files
    val options = TsConfigLoader(SystemVfs).load(project.configPath).options
    var identifiers = 0L
    var occurrences = 0L
    for (file in files) {
        val text = File(file).readText()
        val index = SourceIndex.of(text, file, computeParserFlags(file, text, options))
        identifiers += index.identifiers().size
        occurrences += index.occurrenceNodes().size
    }
    println("files=${files.size} identifiers=$identifiers occurrences=$occurrences")
    val target = files.first { it.endsWith(args[1]) }
    val offset = File(target).readText().indexOf(args[2])
    require(offset >= 0) { "needle not found in $target" }
    repeat(3) {
        var at = System.nanoTime()
        val sweep = project.fileSemantics(target)
        val semanticsMs = (System.nanoTime() - at) / 1_000_000
        at = System.nanoTime()
        val references = project.referencesAt(target, offset)
        val referenceMs = (System.nanoTime() - at) / 1_000_000
        println(
            "fileSemantics=${sweep.size} semanticsMs=$semanticsMs | " +
                "references=${references.size} referenceMs=$referenceMs",
        )
    }
    project.close()
}
