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

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.98b) The POSITIVE type-guard filter is round 480's twin, and it was
 * missing: tsc's `getNarrowedType`(assumeTrue) keeps a union member `m` when
 * `isTypeSubtypeOf(m, candidate)`, and this checker asked ASSIGNABILITY — in
 * which a source MISSING a target's OPTIONAL property still relates. So a
 * guard whose target declares nothing but optional members (a WEAK type) kept
 * EVERY constituent and narrowed nothing, while a target with one REQUIRED
 * member has always narrowed correctly. Measured against tsgo 7.0.2 and
 * pristine `typescript@6.0.3`, which agree on every row here.
 *
 * The item that queued this called it a NESTED-TERNARY defect (knip's
 * graphql-codegen plugin, `isX(c) ? [c.extensions?.codegen] : [c]`). It is
 * not: the same union, the same guard and the same reader fail identically
 * under a plain `if` statement, a single ternary and an `&&` — the ternary was
 * incidental to the ONE library site the recon happened to read. The axis is
 * the guard TARGET's optionality, which is why the `weak` / `required` pair
 * below is the pin that names the mechanism.
 *
 * Round 480 ([NegativeGuardOptionalDistinguisherTest]) added exactly this veto
 * to the NEGATIVE filter; a member vetoed here falls through to the narrow-DOWN
 * arm (tsc's `isTypeSubtypeOf(candidate, m) ? candidate`), so the two arms
 * together are the whole of tsc's `mapType` over the candidate.
 */
class PositiveGuardWeakTargetNarrowingTest {

    private val prelude = """
        interface Cod { generates: string; }
        interface CfgW { extensions?: string; }
        interface CfgR { extensions: string; }
        interface Prj { projects: string; }
        declare function isW(c: Cod | CfgW): c is CfgW;
        declare function isR(c: Cod | CfgR): c is CfgR;
        declare function isPrj3(c: Cod | CfgW | Prj): c is Prj;
        declare function isCfg3(c: Cod | CfgW | Prj): c is CfgW;
    """.trimIndent()

    // ---------------------------------------------------------------- value

    @Test
    fun `an if-guard onto a weak target narrows the union to that target`() {
        val d = diagnose(prelude + """

            export function f(c: Cod | CfgW): void {
                if (isW(c)) { const w: number = c; }
            }
        """.trimIndent()).filter { it.code == 2322 }
        assert(d.size == 1)
        assert(d[0].message == "Type 'CfgW' is not assignable to type 'number'.")
    }

    @Test
    fun `a ternary then-operand guarded onto a weak target narrows`() {
        val d = diagnose(prelude + """

            export function f(c: Cod | CfgW): number {
                return isW(c) ? ((): number => { const w: number = c; return 0; })() : 0;
            }
        """.trimIndent()).filter { it.code == 2322 }
        assert(d.size == 1)
        assert(d[0].message == "Type 'CfgW' is not assignable to type 'number'.")
    }

    @Test
    fun `a weak-target guard narrows the member read under an if`() {
        diagnose(prelude + """

            export function f(c: Cod | CfgW): string | undefined {
                if (isW(c)) { return c.extensions; }
                return undefined;
            }
        """.trimIndent()) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a weak-target guard narrows the member read in a ternary then-operand`() {
        diagnose(prelude + """

            export function f(c: Cod | CfgW): string | undefined {
                return isW(c) ? c.extensions : undefined;
            }
        """.trimIndent()) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a weak-target guard narrows the right operand of an and-chain`() {
        diagnose(prelude + """

            export function f(c: Cod | CfgW): string | undefined | false {
                return isW(c) && c.extensions;
            }
        """.trimIndent()) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `the knip nested-ternary shape narrows in both inner branches`() {
        diagnose(prelude + """

            export function f(c: Cod | CfgW | Prj): string[] {
                return isPrj3(c) ? [c.projects] : isCfg3(c) ? [c.extensions ?? ""] : [c.generates];
            }
        """.trimIndent()) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a weak-target guard nested inside a negative guard narrows`() {
        diagnose(prelude + """

            export function f(c: Cod | CfgW | Prj): string {
                if (!isPrj3(c)) {
                    if (isCfg3(c)) { return c.extensions ?? ""; }
                    return c.generates;
                }
                return c.projects;
            }
        """.trimIndent()) should {
            have(none { it.code == 2339 })
        }
    }

    // ------------------------------------------------------------- controls

    @Test
    fun `a required-member guard target still narrows - the working path is unchanged`() {
        val d = diagnose(prelude + """

            export function f(c: Cod | CfgR): void {
                if (isR(c)) { const w: number = c; }
            }
        """.trimIndent()).filter { it.code == 2322 }
        assert(d.size == 1)
        assert(d[0].message == "Type 'CfgR' is not assignable to type 'number'.")
    }

    @Test
    fun `the negative branch of a weak-target guard is unchanged`() {
        val d = diagnose(prelude + """

            export function f(c: Cod | CfgW): void {
                if (!isW(c)) { const w: number = c; }
            }
        """.trimIndent()).filter { it.code == 2322 }
        assert(d.size == 1)
        assert(d[0].message == "Type 'Cod' is not assignable to type 'number'.")
    }

    @Test
    fun `negative control - a member that DOES declare the target's optional property is kept`() {
        // `Both` carries `extensions`, so it IS a subtype of the weak target and tsc
        // keeps it: the veto must not fire, and the branch must stay a two-member
        // union. A veto that dropped it would print `Type 'CfgW'` here.
        val d = diagnose("""
            interface CfgW { extensions?: string; }
            interface Both { extensions: string; other: number; }
            declare function isW2(c: Both | CfgW): c is CfgW;
            export function f(c: Both | CfgW): void {
                if (isW2(c)) { const w: number = c; }
            }
        """.trimIndent()).filter { it.code == 2322 }
        assert(d.size == 1)
        assert(d[0].message == "Type 'Both | CfgW' is not assignable to type 'number'.")
    }

    @Test
    fun `negative control - an unguarded read of the union still reports`() {
        diagnose(prelude + """

            export function f(c: Cod | CfgW): string | undefined {
                return c.extensions;
            }
        """.trimIndent()) should {
            have(any { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a member on no constituent still reports under the guard`() {
        diagnose(prelude + """

            export function f(c: Cod | CfgW): void {
                if (isW(c)) { c.nopeNope; }
            }
        """.trimIndent()) should {
            have(any { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - round 480's negative optional-distinguisher filter still keeps members`() {
        diagnose("""
            interface FileInode { dev: number; mode: number; size?: number; }
            interface DirectoryInode { dev: number; mode: number; links?: string[]; }
            interface SymlinkInode { dev: number; mode: number; symlink: string; }
            type Inode = FileInode | DirectoryInode | SymlinkInode;
            declare function isDirectory(node: Inode): node is DirectoryInode;
            declare function isSymlink(node: Inode): node is SymlinkInode;
            export function track(node: Inode): string {
                if (isDirectory(node)) { return "dir"; }
                else if (isSymlink(node)) { return node.symlink; }
                return "file";
            }
        """.trimIndent()) should {
            have(none { it.code == 2339 })
        }
    }
}
