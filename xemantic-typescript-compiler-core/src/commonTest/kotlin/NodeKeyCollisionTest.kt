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
 * (BIND.1) Two declarations at COINCIDENT `(pos, end)` in DIFFERENT files are two
 * declarations, not one.
 *
 * `Binder.nodeToSymbol` and `Binder.moduleInstanceStates` are keyed by
 * [nodeKey], which packs `(pos, end)` and carries NO file identity — and positions
 * restart at 0 in every file. They used to be ONE map shared by every
 * [BinderResult] the binder produced, so two files that happened to declare
 * something at the same offsets shared a slot, last-wins in bind order.
 *
 * The observable was a diagnostic that appeared and disappeared with the BYTE LENGTH
 * OF AN UNRELATED FILE: `Checker.buildNamespaceScope` looked its merged namespace's
 * symbol up in that table and got the OTHER file's, so a second `namespace` block
 * stopped seeing its own file's exports (a false TS2304) and started seeing the
 * foreign file's (a missing one). Adding one character to `b.ts` moved the key and
 * the error vanished; removing it brought the error back. Measured on an ordinary
 * 223-file program (one source file plus `zod` and `@types/node`), **109 keys were
 * written by two or more declaration nodes in different files**.
 *
 * ## Why this fixture and not a corpus baseline
 *
 * Nothing in the ~13k-baseline corpus can see this. It needs TWO files whose
 * declarations land on identical offsets, which no hand-written fixture produces by
 * accident and which the `// @Filename:` harness would not preserve — so the two
 * texts are handed to the pipeline verbatim, and [`the fixture really is a
 * collision`] asserts the precondition rather than trusting it. Without that
 * assertion this whole class would go quietly vacuous the day someone renames a
 * binding, which is the failure mode CLAUDE.md calls a blind pin.
 */
class NodeKeyCollisionTest {

    /** Two merged `namespace` blocks; the second reads the first's export. */
    private val fileA =
        "namespace Zed { export const alpha = 1; }\n" +
            "namespace Zed { export const beta = alpha; }\n"

    /** The SAME SHAPE and the same byte length, so every statement of the two files
     *  shares a `(pos, end)` — and therefore, before (BIND.1), a binder table slot. */
    private val fileB =
        "namespace Qed { export const gamma = 1; }\n" +
            "namespace Qed { export const delta = 1234; }\n"

    /** `alpha` replaced by `gamma`, which ONLY [fileB]'s namespace exports. Same
     *  length as [fileA], so the fixture keeps colliding. */
    private val fileAReferencingTheOtherFile =
        fileA.replace("beta = alpha", "beta = gamma")

    private fun compile(a: String, b: String): List<Diagnostic> {
        val options = CompilerOptions()
        return TypeScriptCompiler().compileParsed(
            ParsedSource(
                options,
                listOf(SourceFileEntry("/p/a.ts", a), SourceFileEntry("/p/b.ts", b)),
            ),
            options,
            "/p/a.ts",
        ).diagnostics
    }

    @Test
    fun `the fixture really is a collision - the two files' statements share node keys`() {
        assert(fileA.length == fileB.length)
        assert(fileAReferencingTheOtherFile.length == fileB.length)
        val a = Parser(fileA, "/p/a.ts").parse()
        val b = Parser(fileB, "/p/b.ts").parse()
        assert(a.statements.size == 2)
        assert(b.statements.size == 2)
        assert(nodeKey(a.statements[0]) == nodeKey(b.statements[0]))
        assert(nodeKey(a.statements[1]) == nodeKey(b.statements[1]))
    }

    @Test
    fun `a merged namespace block sees its OWN file's exports`() {
        compile(fileA, fileB) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `a merged namespace block does NOT see the colliding file's exports`() {
        // tsc reports exactly this: `a.ts(2,37): error TS2304: Cannot find name 'gamma'.`
        compile(fileAReferencingTheOtherFile, fileB) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `negative control - the same two files with no collision behave identically`() {
        // One character longer, so no statement of the two files shares a key. Both
        // assertions above must already hold WITHOUT the collision, or they are
        // measuring the fixture rather than the fix.
        val longerB = fileB.replace("delta = 1234", "delta = 12345")
        assert(longerB.length != fileA.length)
        compile(fileA, longerB) should {
            have(none { it.code == 2304 })
        }
        compile(fileAReferencingTheOtherFile, longerB) should {
            have(any { it.code == 2304 })
        }
    }
}
