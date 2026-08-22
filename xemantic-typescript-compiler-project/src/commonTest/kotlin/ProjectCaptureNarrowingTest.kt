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
 * (INC.2b) THE CAPTURE QUERIES UNDER A CHECK PARTITION OF ONE FILE.
 *
 * Every caret-scoped member of [Project] — [Project.quickInfoAt],
 * [Project.definitionsAt], [Project.completionsAt], [Project.signatureHelpAt],
 * [Project.semanticsAt] / [Project.fileSemantics] and
 * [Project.documentHighlightsAt] — hands the compiler the queried buffer as its
 * check partition. The whole program is still crawled, parsed and bound; only the
 * per-file CHECKING narrows.
 *
 * ## What makes these pins discriminate, and what they do NOT
 *
 * An EQUIVALENCE pin cannot see the wiring: a correctly derived partition returns
 * the whole-program answer, so an ablation that simply stops narrowing leaves it
 * green. `ProjectNarrowDiagnosticsTest` records the same limit for
 * [Project.diagnosticsOf] and this class inherits it — the latency is held by
 * `scripts/capture-equivalence.sh`'s own rotated timing arm, not here.
 *
 * What these pins DO discriminate is the failure mode narrowing actually has, and
 * it is silent: a span in a file the partition omits is never walked past, so its
 * answer comes back ABSENT rather than wrong — an empty tooltip, an empty
 * completion, no error anywhere. The partition is therefore DERIVED from the
 * request's own spans (`Project.captureIn`), and every span list is exercised by a
 * pin below: dropping `spans`, `memberSpans`, `scopeSpans` or `signatureSpans` from
 * that derivation reddens the hover/definition/semantics pins, the member-completion
 * pin, the free-name-completion pin and the signature-help pin respectively. All
 * four ablations were run.
 *
 * The last pin is the mirror: [Project.referencesAt] is deliberately NOT narrowed,
 * because its claim is about every file, and it goes red if it ever is.
 *
 * ## The fixture is cross-file on purpose, and the control says so
 *
 * Everything the queried file asks about — the type, the members, the signature,
 * the declaration site — lives in the OTHER file, which the partition does not
 * check. A partition that had lost the program (rather than merely narrowing the
 * check) would answer `any`, or nothing. `module` is an ES kind and the program has
 * two files, without which every import-related assertion here would be vacuous
 * (`ProjectDefinitionTest`'s rule), and the first pin's control is exactly that: the
 * import resolved, so there is no TS2307.
 */
class ProjectCaptureNarrowingTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"
    private val otherFile = "/proj/src/b.ts"

    /** Declares everything `a.ts` asks about — see the class KDoc. */
    private val other = """
        export interface Shape {
            readonly kind: string;
            readonly size: number;
        }
        export function make(size: number): Shape {
            return { kind: "square", size: size };
        }
    """.trimIndent() + "\n"

    private val main = """
        import { Shape, make } from "./b";
        export const shape: Shape = make(1);
        export const kind = shape.kind;
    """.trimIndent() + "\n"

    private fun sources() = mapOf(
        "/proj/tsconfig.json" to config,
        mainFile to main,
        otherFile to other,
    )

    private fun project(): Project = Project.open("/proj", InMemoryVfs(sources()))

    /** The offset just past [prefix], which must occur exactly once. */
    private fun caretAfter(prefix: String, text: String = main): Int {
        val at = text.indexOf(prefix)
        assert(at >= 0)
        assert(text.indexOf(prefix, at + 1) < 0)
        return at + prefix.length
    }

    // --- the four capture channels, each narrowed and each answering across files --

    @Test
    fun `a hover renders a type whose declaration the partition never checks`() {
        val project = project()
        // The control: the import resolved, so the program really was crawled and
        // bound in full. Without it, an answer of `any` and an answer of `Shape`
        // would be indistinguishable from a lost program.
        assert(project.diagnostics(mainFile).none { it.code == 2307 })
        val info = project.quickInfoAt(mainFile, caretAfter("export const kind = "))
        assert(info != null)
        assert(info.displayString == "Shape")
    }

    @Test
    fun `go to definition lands in the file the partition does not check`() {
        val project = project()
        val definitions = project.definitionsAt(mainFile, caretAfter("export const shape: "))
        assert(definitions.size == 1)
        assert(definitions[0].fileName == otherFile)
        assert(definitions[0].start == other.indexOf("Shape"))
    }

    @Test
    fun `member completion enumerates a foreign type and renders its member types`() {
        val project = project()
        val items = project.completionsAt(mainFile, caretAfter("export const kind = shape.")).items
        // Names AND type text: the rendered type is the channel that carries the
        // first-touch identity risk `scripts/capture-channel-equivalence.sh` sweeps.
        assert(items.map { it.name }.sorted() == listOf("kind", "size"))
        assert(items.single { it.name == "kind" }.typeText == "string")
        assert(items.single { it.name == "size" }.typeText == "number")
    }

    @Test
    fun `free name completion offers the bindings the scope chain holds`() {
        val project = project()
        // End of file, which is a free position at file level; the imported names are
        // what a partition that had lost the program could not offer.
        val names = project.completionsAt(mainFile, main.length).items.map { it.name }
        assert("shape" in names)
        assert("kind" in names)
        assert("make" in names)
        assert("Shape" in names)
    }

    @Test
    fun `signature help renders a signature declared in the unchecked file`() {
        val project = project()
        val help = project.signatureHelpAt(mainFile, caretAfter("export const shape: Shape = make("))
        assert(help != null)
        assert(help.signatures.size == 1)
        val signature = help.signatures[0]
        assert(signature.parameters.map { it.name } == listOf("size"))
        assert(signature.parameters[0].typeText == "number")
        assert(signature.returnTypeText == "Shape")
    }

    // --- the batch form, and the two reference queries ---------------------------

    @Test
    fun `the whole-file semantic sweep agrees with the single-caret hover`() {
        val project = project()
        val at = caretAfter("export const kind = ")
        val single = project.quickInfoAt(mainFile, at)
        assert(single != null)
        // `semanticsOf`'s own narrowing, checked against the member that does not go
        // through it — the independent-oracle rule `Project.semanticsOf` documents.
        val batch = project.fileSemantics(mainFile).firstOrNull { it.start == at }
        assert(batch != null)
        assert(batch.quickInfo?.displayString == single.displayString)
    }

    @Test
    fun `document highlights agree with the whole-program references in this file`() {
        val project = project()
        val at = caretAfter("export const kind = shape.") - "shape.".length
        val narrow = project.documentHighlightsAt(mainFile, at)
        val whole = project.referencesAt(mainFile, at).filter { it.fileName == mainFile }
        // The control: the narrowed sweep really found something, so the agreement is
        // not two empty lists agreeing.
        assert(narrow.isNotEmpty())
        assert(narrow.map { "${it.fileName}|${it.start}|${it.end}" } ==
            whole.map { "${it.fileName}|${it.start}|${it.end}" })
    }

    @Test
    fun `find references still answers about the whole program`() {
        // The mirror pin: [Project.referencesAt] is the one capture query that is NOT
        // narrowed, because its CLAIM is program-wide. Narrowing it to the queried
        // file would silently drop every occurrence in `b.ts` — which is exactly what
        // this asserts is present.
        val project = project()
        val references = project.referencesAt(mainFile, caretAfter("export const shape: "))
        assert(references.any { it.fileName == otherFile })
        assert(references.any { it.fileName == mainFile })
        assert(references.any { it.fileName == otherFile && it.isDeclaration })
    }
}
