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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (INC.36) ONE PARSE, NOT TWO — that [Project]'s syntax layer answers about the
 * tree the COMPILER parsed rather than about a private copy of it.
 *
 * ## Why the assertions are all `===` and none of them is a megabyte
 *
 * The defect this pins was worth **103 MB** on tsc's own 78 sources and was
 * INVISIBLE to every value a query returns: `Project.sourceIndexOf` re-parsed
 * text the crawl had already parsed under the same flags, so the two trees were
 * EQUAL and every hover, definition, completion and reference answered
 * identically from either (`docs/perf/language-service-retention.md`, the
 * ladder's `sourceIndexes` 114.7 MB / `CrawlParseCache` 103.0 MB rows). A heap
 * assertion cannot pin it — a sized assertion over a collector's decision is a
 * coin flip (CLAUDE.md round 868) — so the instrument is object IDENTITY, which
 * is deterministic and which no equal-but-distinct tree can satisfy.
 *
 * ## Why every fixture has its own path and its own content
 *
 * The compiler's parse cache is PROCESS-GLOBAL and keyed by `(path, content,
 * flags)`, so a fixture sharing a path AND its bytes with another test would be
 * served that test's tree — correctly, but making "this file has not been parsed
 * yet" depend on suite order. Each test below owns a directory and writes a
 * distinct marker into its source.
 *
 * ## The three claims, and which arm each one names
 *
 *  1. after a build, two projects over one program hold the SAME tree — the fix;
 *  2. a file asked about before any build parses privately and UPGRADES once the
 *     compiler has a tree of its own — the lazy collection of the dirty case;
 *  3. an unsaved buffer is answered from the BUFFER — the correctness side, which
 *     an over-eager reuse would break silently, since a stale tree is a plausible
 *     tree.
 */
class ProjectSharedParseTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private fun vfsAt(dir: String, source: String): InMemoryVfs = InMemoryVfs(
        mapOf(
            "$dir/tsconfig.json" to config,
            "$dir/src/a.ts" to source,
        ),
    )

    @Test
    fun `two projects over one program share ONE parse`() {
        val dir = "/inc36-share"
        val source = "export const inc36Share = 1;\nexport function f(): number { return inc36Share; }\n"
        val vfs = vfsAt(dir, source)

        val first = Project.open(dir, vfs)
        first.diagnostics()
        val firstTree = first.parsedFileOf("$dir/src/a.ts")

        val second = Project.open(dir, vfs)
        second.diagnostics()
        val secondTree = second.parsedFileOf("$dir/src/a.ts")

        // Never `assert(firstTree === secondTree)`: a power-assert diagram
        // toStrings every subexpression, and an AST node renders its whole
        // subtree (CLAUDE.md's AST-subexpression trap).
        val present = firstTree != null && secondTree != null
        assert(present)
        val shared = firstTree === secondTree
        assert(shared)

        first.close()
        second.close()
    }

    @Test
    fun `a file asked about before any build parses privately and upgrades after one`() {
        val dir = "/inc36-upgrade"
        val source = "export const inc36Upgrade = 2;\nexport const g = inc36Upgrade + 1;\n"
        val project = Project.open(dir, vfsAt(dir, source))

        // Nothing has compiled, so the compiler has never seen these bytes: this
        // MUST parse, and that is the right answer rather than a shortfall.
        val own = project.parsedFileOf("$dir/src/a.ts")
        assert(own != null)

        project.diagnostics()

        val upgraded = project.parsedFileOf("$dir/src/a.ts")
        val replaced = upgraded != null && upgraded !== own
        assert(replaced)

        // ...and the upgrade happens once: the second ask is the same object, so
        // this is not re-indexing on every query.
        val stable = project.parsedFileOf("$dir/src/a.ts") === upgraded
        assert(stable)

        project.close()
    }

    @Test
    fun `an unsaved buffer is answered from the buffer and not from the compiler's tree`() {
        val dir = "/inc36-dirty"
        val onDisk = "export const inc36Dirty = 3;\n"
        val project = Project.open(dir, vfsAt(dir, onDisk))
        project.diagnostics()
        val built = project.parsedFileOf("$dir/src/a.ts")
        assert(built != null)

        // Same path, different bytes: the compiler's tree describes text that is
        // no longer what the host is looking at.
        val edited = "export const inc36Dirty = 3;\nfunction inc36Fresh(): void {}\n"
        project.updateFile("$dir/src/a.ts", edited)

        val dirtyTree = project.parsedFileOf("$dir/src/a.ts")
        val reparsed = dirtyTree != null && dirtyTree !== built
        assert(reparsed)

        // The value side of the same claim: the node the syntax layer finds is a
        // node of the EDITED text. `inc36Fresh` exists only in the buffer, so a
        // stale tree cannot produce it — and its own span is what says the token
        // stream was indexed against the same bytes rather than merely that some
        // node was found.
        val at = edited.indexOf("inc36Fresh")
        val info = project.nodeInfoAt("$dir/src/a.ts", at)
        assert(info != null)
        val kind = info.kind
        assert(kind == "Identifier")
        val start = info.start
        assert(start == at)

        project.close()
    }

    @Test
    fun `the shared tree answers a position exactly as a private parse did`() {
        // A CONTROL, not a discriminating pin: it says the tree swap is not
        // observable through the public API, which is the property that lets the
        // other three be about identity alone.
        val dir = "/inc36-control"
        val source = "export const inc36Control = 4;\nexport const h = inc36Control;\n"
        val project = Project.open(dir, vfsAt(dir, source))
        val at = source.lastIndexOf("inc36Control")

        val before = project.nodeInfoAt("$dir/src/a.ts", at)
        project.diagnostics()
        val after = project.nodeInfoAt("$dir/src/a.ts", at)

        assert(before != null)
        assert(after != null)
        val kind = before.kind
        assert(kind == "Identifier")
        val sameKind = after.kind == before.kind
        assert(sameKind)
        val sameStart = after.start == before.start
        assert(sameStart)
        val sameEnd = after.end == before.end
        assert(sameEnd)
        val sameAncestors = after.ancestorKinds == before.ancestorKinds
        assert(sameAncestors)

        project.close()
    }
}
