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
 * Round 460: a destructuring ASSIGNMENT `({ pos, end } = refs(i))` OVERWRITES
 * each pattern name — tsc program.ts getReferencedFileLocation's switch cases
 * assign `({ pos, end } = file.referencedFiles[index])` and the final
 * `return { file, pos, end, packageId }` needs `pos: number`, not the declared
 * `number | undefined`. Flow.kt now attaches the WHOLE assignment expression
 * to each leaf's FlowAssignment (so the walker can see the RHS), and
 * narrowByAssignmentRhs strips nullish from the declared type when the
 * destructured member's type resolves nullish-free. Companion: a DIRECT
 * enum-typed switch subject exhausted by enum-member cases narrows to never
 * in the default clause (`default: return assertNever(kind)`).
 */
class DestructuringAssignmentNarrowingTest {

    private val prelude = """
        interface FileRef { pos: number; end: number; name: string; }
        interface Loc { file: string; pos: number; end: number; }
        interface SynthLoc { file: string; text: string; }
        enum Kind { A, B }
        declare function refs(i: number): FileRef;
        declare function assertNever(x: never): never;

    """.trimIndent()

    @Test
    fun `switch cases assigning via destructuring narrow pos and end for the final return`() {
        diagnose(prelude + """
            function f(kind: Kind, index: number, file: string): Loc | SynthLoc {
                let pos: number | undefined, end: number | undefined;
                switch (kind) {
                    case Kind.A:
                        ({ pos, end } = refs(index));
                        break;
                    case Kind.B:
                        ({ pos, end } = refs(index + 1));
                        break;
                    default:
                        return assertNever(kind);
                }
                return { file, pos, end };
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 || it.code == 2345 })
        }
    }

    @Test
    fun `straight-line destructuring assignment narrows for a following return`() {
        diagnose(prelude + """
            function g(index: number, file: string): Loc {
                let pos: number | undefined, end: number | undefined;
                ({ pos, end } = refs(index));
                return { file, pos, end };
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a nullable destructured member does not strip undefined`() {
        diagnose(prelude + """
            interface MaybeRef { pos: number | undefined; end: number | undefined; }
            declare function maybeRefs(i: number): MaybeRef;
            function h(index: number, file: string): Loc {
                let pos: number | undefined, end: number | undefined;
                ({ pos, end } = maybeRefs(index));
                return { file, pos, end };
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `direct enum subject exhausted by all members - assertNever in default is legal`() {
        diagnose(prelude + """
            function k(kind: Kind): number {
                switch (kind) {
                    case Kind.A: return 1;
                    case Kind.B: return 2;
                    default: return assertNever(kind);
                }
            }
        """.trimIndent()) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a NON-exhaustive direct enum switch keeps the assertNever error`() {
        diagnose(prelude + """
            function k(kind: Kind): number {
                switch (kind) {
                    case Kind.A: return 1;
                    default: return assertNever(kind);
                }
            }
        """.trimIndent()) should {
            have(any { it.code == 2345 })
        }
    }
}
