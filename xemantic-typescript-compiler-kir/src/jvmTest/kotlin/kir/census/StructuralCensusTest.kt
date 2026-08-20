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

            export enum Kind { Alpha, Beta }

            export function seeKind(kind: Kind): number { return kind; }

            export const viaEnumMember: number = seeKind(Kind.Alpha);

            export function take(shape: Shape): number { return shape.width; }

            export interface AlsoWide { readonly width: number; readonly extra?: number }
            export class Left { readonly width: number = 4; }
            export class Right { readonly width: number = 5; }
            export function takeEither(v: Left | Right): number { return take(v); }

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
    /**
     * An enum member reaching its own enum must NOT count as a structural edge.
     *
     * In this compiler an enum's type is a member-LESS `Type.Object` and a
     * member's is another one, so without a dedicated class every member read as
     * an object-to-object STRUCTURAL edge onto the enum — which on tsc's own
     * sources put `SyntaxKind` at the top of the fan-in table with one edge per
     * member and inflated the design population by a third.
     */
    @Test
    fun `an enum member reaching its own enum is not an object edge`() {
        val report = census(fixture)
        assert(report.countOf(targetClass = TargetClass.ENUM) > 0)
        assert(
            report.countOf(
                edge = EdgeClass.STRUCTURAL,
                targetClass = TargetClass.OBJECT_WITH_MEMBERS,
                sourceClass = TargetClass.ENUM,
            ) == 0L,
        )
    }

    /**
     * A UNION source contributes one closure edge PER OBJECT CONSTITUENT.
     *
     * `Left | Right` reaching `Shape` is not one `implements` edge: at runtime the
     * value is a Left or a Right, and it is those two classes that must carry the
     * interface. Counting the union as one source under-counts the closure;
     * skipping it — a union is not object-ish — under-counts it to zero.
     */
    @Test
    fun `a union source contributes one closure edge per object constituent`() {
        val report = census(fixture)
        val shape = report.designFanIn.entries
            .firstOrNull { report.types[it.key].text == "Shape" }
        assert(shape != null)
        assert(shape.value >= 3)
    }

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
