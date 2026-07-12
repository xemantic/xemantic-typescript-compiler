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
 * Round 480 (tsc subtype vs assignability): the negative type-guard filter
 * uses tsc's SUBTYPE relation, in which a MISSING source property FAILS an
 * OPTIONAL target property — vfsUtil's FileInode is assignable to
 * DirectoryInode (every difference optional) but NOT a subtype (no `links`),
 * so `!isDirectory(node)` keeps File/Symlink members and the later
 * `isSymlink(node)` branch reads `node.symlink` legally.
 */
class NegativeGuardOptionalDistinguisherTest {

    private val prelude = """
        interface FileInode { dev: number; mode: number; size?: number; shadowRoot?: FileInode; }
        interface DirectoryInode { dev: number; mode: number; links?: string[]; shadowRoot?: DirectoryInode; }
        interface SymlinkInode { dev: number; mode: number; symlink: string; shadowRoot?: SymlinkInode; }
        type Inode = FileInode | DirectoryInode | SymlinkInode;
        declare function isDirectory(node: Inode | undefined): node is DirectoryInode;
        declare function isSymlink(node: Inode | undefined): node is SymlinkInode;
    """.trimIndent()

    @Test
    fun `negative guard keeps members distinguished only by optional props`() {
        diagnose(prelude + """

            export function track(node: Inode): string {
                if (isDirectory(node)) {
                    return "dir";
                }
                else if (isSymlink(node)) {
                    return node.symlink;
                }
                return "file";
            }
        """.trimIndent()) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - structurally identical members still exhaust to never`() {
        diagnose(
            """
            class C1 { item = 1; }
            class C2 { item = 1; }
            class C3 { item = 1; }
            declare function isC1(x: C1 | C2 | C3): x is C1;
            declare function isC2(x: C1 | C2 | C3): x is C2;
            declare function isC3(x: C1 | C2 | C3): x is C3;
            export function f(x: C1 | C2 | C3): number {
                if (isC1(x)) return 1;
                if (isC2(x)) return 2;
                if (isC3(x)) {
                    return x.item;
                }
                return 0;
            }
            """,
        ) should {
            have(none { it.code == 9999 })
        }
    }
}
