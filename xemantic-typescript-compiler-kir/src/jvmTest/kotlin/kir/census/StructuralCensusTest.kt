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

package com.xemantic.typescript.compiler.kir.census

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.DiagnosticCategory
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SystemVfs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * Pins that the census DISCRIMINATES — that its five edge classes are five
 * different answers about the same shape, not one answer wearing five labels.
 *
 * The fixture is deliberately built so that each class is reachable by exactly
 * one construct, because a measurement whose classifier collapses two classes
 * reports a distribution that is arithmetically fine and means nothing.
 */
class StructuralCensusTest {

    private fun census(files: Map<String, String>): CensusReport {
        val dir: Path = Files.createTempDirectory("kir-census")
        dir.resolve("tsconfig.json").writeText(
            """{ "compilerOptions": { "strict": true, "module": "esnext", "target": "es2020" } }""",
        )
        for ((name, content) in files) dir.resolve(name).writeText(content.trimIndent())
        val sink = StructuralCensus()
        val result = ProjectCompiler(SystemVfs)
            .build(dir.toString(), noEmit = true, checkedSink = sink)
        assert(result.diagnostics.none { it.category == DiagnosticCategory.Error })
        return sink.report()
    }

    private val fixture = mapOf(
        "main.ts" to """
            export interface Shape { readonly width: number }

            export class Declared implements Shape {
                readonly width: number = 1;
            }

            export class Undeclared {
                readonly width: number = 2;
            }

            export function take(shape: Shape): number { return shape.width; }

            export const viaImplements: number = take(new Declared());
            export const viaStructure: number = take(new Undeclared());
            export const viaLiteral: number = take({ width: 3 });
            export const viaIdentity: number = take(new Declared() as Shape);
        """,
    )

    @Test
    fun `an implements clause classifies as a nominal edge`() {
        val report = census(fixture)
        assert(report.countOf(edge = EdgeClass.NOMINAL_BASE) > 0)
    }

    @Test
    fun `a same-shaped class with no heritage classifies as structural`() {
        val report = census(fixture)
        assert(report.countOf(edge = EdgeClass.STRUCTURAL) > 0)
        // …and lands in the ONE cell the design question turns on: an object-ish
        // value reaching an object-ish target with no open type parameter.
        assert(report.designObligations > 0)
        assert(report.designPairs > 0)
    }

    @Test
    fun `a contextually typed object literal classifies as a fresh literal`() {
        val report = census(fixture)
        assert(report.countOf(edge = EdgeClass.FRESH_LITERAL) > 0)
        assert(report.objectLiteralsWithTarget > 0)
    }

    /**
     * The control this whole measurement rests on: a clean program forms NO
     * unassignable pair. A non-zero count here does not mean TypeScript was
     * violated — it means the census derived the wrong TARGET somewhere, and the
     * STRUCTURAL population it reports is contaminated in an unknown direction.
     */
    @Test
    fun `a clean program forms no unassignable obligation`() {
        val report = census(fixture)
        assert(report.countOf(edge = EdgeClass.NOT_ASSIGNABLE) == 0L)
        assert(report.lensFailures == 0L)
    }

    /**
     * A census whose classifier ran on a program it could not see would report all
     * zeros and look like an answer, so the population is asserted non-empty and
     * the fan maps are asserted to have been built at all.
     */
    @Test
    fun `the census records obligations and builds the fan maps`() {
        val report = census(fixture)
        assert(report.obligations > 0)
        assert(report.expressionsSeen > 0)
        assert(report.structuralFanOut.isNotEmpty())
        assert(report.structuralFanIn.isNotEmpty())
        assert(report.distinctPairs > 0)
        assert(report.filesSeen == 1)
    }
}
