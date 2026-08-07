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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 472: a return annotation that is a generic REFERENCE whose type args
 * include the enclosing fn's OWN TPs (`ResolutionLoader<T, …>`) compares those
 * args covariantly and can fail even when every legal instantiation relates
 * (the TP is used CONTRAVARIANTLY inside the interface — tsc's variance
 * analysis accepts program.ts:1088's createTypeReferenceResolutionLoader
 * returning a getter typed with the constraint itself). The return path
 * retries with each own TP bound to its declared CONSTRAINT and bails when
 * that instantiation relates (FN-not-FP).
 */
class ReturnOwnTpConstraintRetryTest {

    private val prelude = """
        interface SourceFile { fileName: string; }
        interface FileReference { pos: number; }
        interface ResolvedThing { resolved: boolean; }
        interface ResolutionNameAndModeGetter<Entry, SourceFile> {
            getName(entry: Entry): string;
        }
        interface ResolutionLoader<Entry, Resolution, SourceFile> {
            nameAndMode: ResolutionNameAndModeGetter<Entry, SourceFile>;
            resolve(name: string): Resolution;
        }
        declare const typeReferenceGetter: ResolutionNameAndModeGetter<FileReference | string, SourceFile | undefined>;
        declare function resolveIt(name: string): ResolvedThing;
    """.trimIndent()

    @Test
    fun `a constraint-typed member relates to an own-TP reference target`() {
        diagnose(
            prelude + "\n" + """
            export function createLoader<T extends FileReference | string>(
                containingFile: string,
            ): ResolutionLoader<T, ResolvedThing, SourceFile | undefined> {
                return {
                    nameAndMode: typeReferenceGetter,
                    resolve: name => resolveIt(name),
                };
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2322 })
        }
    }

    // NOTE: a bare own-TP target (`(): T { return "x" }`) draws no TS2322 in our
    // checker with OR without the retry (a pre-existing M3 false negative, verified
    // by clean-HEAD A/B) — the retry's head-name/no-args gate excludes it anyway,
    // so the sharp negative pin is the wrong-member variant below.

    @Test
    fun `negative control - an unrelated source still fails against the reference target`() {
        diagnose(
            prelude + "\n" + """
            export function createLoaderBad<T extends FileReference | string>(
                containingFile: string,
            ): ResolutionLoader<T, ResolvedThing, SourceFile | undefined> {
                return {
                    nameAndMode: 42,
                    resolve: name => resolveIt(name),
                } as any as { nameAndMode: number; resolve(name: string): ResolvedThing; };
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
