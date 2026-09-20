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
import com.xemantic.typescript.compiler.Expression
import com.xemantic.typescript.compiler.OracleRefusal
import com.xemantic.typescript.compiler.TypeOracle
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * (INV.2b) `Project.typeOracle()` — the first consumer the Stage-2 facade has.
 *
 * ## What these pins are for
 *
 * The oracle is a CAPABILITY this commit adds and a DECISION it does not take: no
 * shipped query is served from it, so nothing here may be satisfied by a query
 * that was already working. Each pin therefore names one property of the handle
 * itself — that it is live, that it is retained, that it is CLOSED by every edit,
 * that it shares this project's trees, and that it stays out of `cached`.
 *
 * ## The hazard the close pins exist for, measured before they were written
 *
 * **A stale store answers a WRONG TYPE, not null.** `TypeOracle.storeOf` finds a
 * store by the node's file NAME and `NodeAnswerStore.typeAt` reads
 * `types[node.nodeId]` behind a bounds check alone — node identity is never
 * checked — so after an edit a node of the RE-PARSED file indexes the PREVIOUS
 * build's array. Measured on the four-line program below, with the close removed,
 * three edit shapes and three different wrong behaviours:
 *
 *  * text re-set UNCHANGED — the store answers CORRECTLY, every row. The parse
 *    cache is content-keyed, so the "fresh" tree is literally the same tree;
 *  * an annotation retyped IN PLACE (`number` -> `string`, ids unmoved) — **every
 *    one of the file's nine identifiers is answered and the three that moved read
 *    `number`** for a file that now declares `string`: a 100 % answer rate and no
 *    signal at all;
 *  * a statement INSERTED above the caret (ids moved) — of the new tree's ten
 *    identifiers, **two carry a confidently WRONG type** (`zzzInserted`, declared
 *    `string`, reads `number`; `useLocal`, a `number`, reads `string`), **three
 *    answer null** past the old array's end, and the rest are right by coincidence:
 *    a MIXTURE, with nothing in any answer to tell a host which kind it holds.
 *
 * The fixture below uses the third shape, per the brief: an edit that moves
 * `nodeId`s is the one that cannot be satisfied by an unchanged tree, so the
 * ablation arms cannot read green for the wrong reason. (Arm a1, which drops only
 * the `updateFile` close, reddens exactly the `updateFile` pin and the stale-store
 * one — i.e. the ids really did move.)
 *
 * ## Why the cost pins count `tsconfig.json`
 *
 * A build is not observable from its result — two builds of one state return equal
 * values — so the instrument is the layer BELOW ([CountingVfs], whose counters are
 * atomic because the crawl reads from sixteen workers, (TEST.1)). The counted file
 * is one no test here EDITS: (INC.12)'s trap is that an overlaid file is served
 * from the overlay and never reaches the backing `Vfs`, so a count keyed on the
 * edited file silently stops moving. `tsconfig.json` is additionally never retained
 * under `trustFilesystem` (`OverlayVfs.readText` sends every `.json` to the
 * delegate), so its count is a build count whatever a host has promised.
 *
 * The assertions are DIRECTIONAL (`did not move` / `moved`) rather than exact: the
 * number of config reads per build is a property of the crawl, and pinning it would
 * make this class fail for a reason it does not name.
 */
class ProjectTypeOracleTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    /**
     * A body local SHADOWING a file-level declaration of the same name, and a
     * parameter NARROWED by a type guard — round 911's positive control twice over.
     * A post-hoc ask of the finished checker answers the file-level `string` for the
     * first and the un-narrowed union for the second.
     */
    private val source = """
        declare const zzzCollide: string;
        export function probe(u: string | number): void {
          const zzzCollide: number = 1;
          const useLocal = zzzCollide;
          if (typeof u === "string") {
            const useNarrow = u;
          }
        }
    """.trimIndent() + "\n"

    private fun vfsAt(dir: String, src: String = source): InMemoryVfs = InMemoryVfs(
        mapOf(
            "$dir/tsconfig.json" to config,
            "$dir/src/a.ts" to src,
        ),
    )

    /** The n-th occurrence of [needle] in [source], plus [skip] characters. */
    private fun offsetOf(needle: String, occurrence: Int = 0, skip: Int = 0, text: String = source): Int {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at + skip
    }

    private fun Project.expressionAt(file: String, offset: Int): Expression? =
        nodeAt(file, offset) as? Expression

    // -------------------------------------------------------------------------
    // 1. The oracle is LIVE, not post-hoc.
    // -------------------------------------------------------------------------

    /**
     * The whole reason the store exists: asked at rest, the checker answers a body
     * local with the same-named GLOBAL's type and an un-narrowed union for a guarded
     * parameter (round 911). Both arms are asserted against the SAME oracle, so the
     * second is not merely a different question — `typeOfSymbol` is the post-hoc
     * route through the retained graph and `typeAt` is the recorded one, and the pin
     * is that they DISAGREE in the direction the walk is right about.
     */
    @Test
    fun `typeAt answers the walk's own type where a post-hoc ask of the same oracle does not`() {
        val dir = "/inv2b-live"
        val file = "$dir/src/a.ts"
        val project = Project.open(dir, vfsAt(dir))
        val oracle = project.typeOracle()
        assert(oracle != null)

        // `const useLocal = zzzCollide` — the THIRD `zzzCollide`: the body's
        // `number`, not the file-level `declare const zzzCollide: string`.
        // `assertNotNull`, not `assert`, for every AST- or `Symbol`-valued check here:
        // a power-assert diagram toStrings every subexpression, and a node renders its
        // whole subtree (CLAUDE.md's AST-subexpression trap, whose sanctioned escape
        // this is). It also keeps the non-null typing the code below needs.
        val shadowRead = assertNotNull(project.expressionAt(file, offsetOf("zzzCollide", occurrence = 2)))
        val shadowType = oracle.typeAt(shadowRead)?.let { oracle.typeToString(it) }
        assert(shadowType == "number")

        // …and the POST-HOC route through the same oracle answers the global.
        val globalName = assertNotNull(project.nodeAt(file, offsetOf("zzzCollide", occurrence = 0)))
        val globalSymbol = assertNotNull(oracle.symbolAt(globalName))
        val globalType = oracle.typeToString(oracle.typeOfSymbol(globalSymbol))
        assert(globalType == "string")

        // `const useNarrow = u` inside the guard: the flow-narrowed `string`.
        val narrowRead = assertNotNull(project.expressionAt(file, offsetOf("useNarrow = u", skip = "useNarrow = ".length)))
        val narrowType = oracle.typeAt(narrowRead)?.let { oracle.typeToString(it) }
        assert(narrowType == "string")

        // …while the parameter's SYMBOL, asked post-hoc, still carries the union.
        val parameterSymbol = assertNotNull(oracle.symbolAt(narrowRead))
        val declaredType = oracle.typeToString(oracle.typeOfSymbol(parameterSymbol))
        val stillTheUnion = declaredType.contains("string") && declaredType.contains("number")
        assert(stillTheUnion)

        project.close()
    }

    // -------------------------------------------------------------------------
    // 2. The four close sites. `cached = null` happens at exactly these four.
    // -------------------------------------------------------------------------

    /** The edit shape every close pin uses: ONE statement inserted ABOVE the caret,
     *  so every later `nodeId` moves and a stale store cannot be right by accident. */
    private val edited = """
        declare const zzzCollide: string;
        export function probe(u: string | number): void {
          const zzzInserted: string = "x";
          const zzzCollide: number = 1;
          const useLocal = zzzCollide;
          if (typeof u === "string") {
            const useNarrow = u;
          }
        }
    """.trimIndent() + "\n"

    /** Asks [oracle] about a node it answered before, and reports whether it REFUSED. */
    private fun refusesAfter(oracle: TypeOracle, project: Project, file: String): Boolean {
        val node = project.expressionAt(file, offsetOf("zzzCollide", occurrence = 2, text = edited))
        return try {
            oracle.typeAt(node ?: return false)
            false
        } catch (_: OracleRefusal) {
            true
        }
    }

    private fun closedBy(dir: String, edit: (Project, String) -> Unit): Boolean {
        val file = "$dir/src/a.ts"
        val project = Project.open(dir, vfsAt(dir))
        val oracle = project.typeOracle()
        assert(oracle != null)
        val answeredBefore = oracle.typeAt(
            assertNotNull(project.expressionAt(file, offsetOf("zzzCollide", occurrence = 2))),
        ) != null
        // Without this the pin could not fail: a query that never answered cannot
        // start refusing (round 902 — assert the injected mistake is REACHED).
        assert(answeredBefore)
        edit(project, file)
        val refused = refusesAfter(oracle, project, file)
        project.close()
        return refused
    }

    @Test
    fun `updateFile closes the oracle`() {
        assert(closedBy("/inv2b-close-update") { project, file -> project.updateFile(file, edited) })
    }

    /**
     * (INC.56)'s member, and the reason this pin exists at all: the `captures` KDoc
     * stated for ~40 rounds that `cached = null` happens at "exactly three sites …
     * There is no fourth path", written before [Project.reloadFile] was added. That
     * is the sentence an implementer reads when deciding where to drop a new
     * edit-scoped handle, so an oracle closed at the other three would have survived
     * a VCS checkout and answered about the previous text.
     */
    @Test
    fun `reloadFile closes the oracle`() {
        val dir = "/inv2b-close-reload"
        val vfs = vfsAt(dir)
        val file = "$dir/src/a.ts"
        val project = Project.open(dir, vfs)
        val oracle = project.typeOracle()
        assert(oracle != null)
        val answeredBefore = oracle.typeAt(
            assertNotNull(project.expressionAt(file, offsetOf("zzzCollide", occurrence = 2))),
        ) != null
        assert(answeredBefore)
        // The file changed BEHIND the project's back — the case `updateFile` cannot
        // express, because the host does not have the new text.
        vfs.writeText(file, edited)
        project.reloadFile(file)
        val refused = refusesAfter(oracle, project, file)
        assert(refused)
        project.close()
    }

    @Test
    fun `deleteFile closes the oracle`() {
        val dir = "/inv2b-close-delete"
        val file = "$dir/src/a.ts"
        val project = Project.open(dir, vfsAt(dir))
        val oracle = project.typeOracle()
        assert(oracle != null)
        val before = assertNotNull(project.expressionAt(file, offsetOf("zzzCollide", occurrence = 2)))
        val answeredBefore = oracle.typeAt(before) != null
        assert(answeredBefore)
        project.deleteFile(file)
        // The node is the one the oracle itself answered about a moment ago, so the
        // refusal is about the oracle's state and not about a node it never saw.
        val refused = try {
            oracle.typeAt(before)
            false
        } catch (_: OracleRefusal) {
            true
        }
        assert(refused)
        project.close()
    }

    @Test
    fun `close closes the oracle`() {
        val dir = "/inv2b-close-close"
        val file = "$dir/src/a.ts"
        val project = Project.open(dir, vfsAt(dir))
        val oracle = project.typeOracle()
        assert(oracle != null)
        val before = assertNotNull(project.expressionAt(file, offsetOf("zzzCollide", occurrence = 2)))
        val answeredBefore = oracle.typeAt(before) != null
        assert(answeredBefore)
        project.close()
        val refused = try {
            oracle.typeAt(before)
            false
        } catch (_: OracleRefusal) {
            true
        }
        assert(refused)
    }

    // -------------------------------------------------------------------------
    // 6. The stale-store negative control.
    // -------------------------------------------------------------------------

    /**
     * The half the close pins above cannot state: that the refusal arrives in place
     * of an ANSWER THAT WOULD OTHERWISE BE WRONG, on a node the host reaches exactly
     * as it would in production — through this project's own re-parsed tree after
     * the edit, which is a DIFFERENT tree whose `nodeId`s have moved.
     *
     * With the close removed, `zzzInserted` (declared `string`) reads `number` here.
     */
    @Test
    fun `an edit between the handout and the query makes the query refuse rather than answer wrongly`() {
        val dir = "/inv2b-stale"
        val file = "$dir/src/a.ts"
        val project = Project.open(dir, vfsAt(dir))
        val oracle = project.typeOracle()
        assert(oracle != null)

        project.updateFile(file, edited)

        // The freshly parsed tree: `zzzInserted`, a name the ORACLE'S build never saw,
        // whose `nodeId` in the new tree is one the old store holds an answer for.
        val fresh = assertNotNull(project.expressionAt(file, offsetOf("zzzInserted", text = edited)))
        val freshTreeIsNew = project.parsedFileOf(file) !== oracle.files.firstOrNull()
        assert(freshTreeIsNew)

        val refused = try {
            oracle.typeAt(fresh)
            false
        } catch (_: OracleRefusal) {
            true
        }
        assert(refused)
        project.close()
    }

    // -------------------------------------------------------------------------
    // 3, 4, 5. Retention, tree identity, separation from `cached`.
    // -------------------------------------------------------------------------

    @Test
    fun `a second call with no edit returns the same oracle and builds nothing`() {
        val dir = "/inv2b-retained"
        val counting = CountingVfs(vfsAt(dir))
        val project = Project.open(dir, counting)

        val first = project.typeOracle()
        assert(first != null)
        val afterFirst = counting.readsOf("$dir/tsconfig.json")
        // The calibration: a build reads the config. Without this the pin below is
        // satisfied by an instrument that counts nothing.
        val firstBuilt = afterFirst > 0
        assert(firstBuilt)

        val second = project.typeOracle()
        val sameInstance = second === first
        assert(sameInstance)
        val afterSecond = counting.readsOf("$dir/tsconfig.json")
        assert(afterSecond == afterFirst)

        // …and the control: an edit makes the NEXT call build again, so the count
        // above is a statement about retention rather than about a dead counter.
        project.updateFile("$dir/src/a.ts", edited)
        val third = project.typeOracle()
        assert(third != null)
        val differentInstance = third !== first
        assert(differentInstance)
        val afterThird = counting.readsOf("$dir/tsconfig.json")
        val rebuilt = afterThird > afterSecond
        assert(rebuilt)

        project.close()
    }

    /**
     * (INC.36) The oracle answers about THIS project's trees, so a host wires
     * `nodeAt` straight into `typeAt` with no translation and no second parse. The
     * instrument is identity: two equal trees answer every query the same way and
     * differ by 103 MB, so only `===` can see it (`ProjectSharedParseTest`).
     */
    @Test
    fun `the oracle's files are this project's own parses`() {
        val dir = "/inv2b-shared"
        val file = "$dir/src/a.ts"
        val project = Project.open(dir, vfsAt(dir))
        val oracle = project.typeOracle()
        assert(oracle != null)

        val projectTree = project.parsedFileOf(file)
        val oracleTree = oracle.files.firstOrNull { it.fileName == file }
        // Never inside a power-assert diagram: a node renders its whole subtree.
        val bothPresent = projectTree != null && oracleTree != null
        assert(bothPresent)
        val shared = projectTree === oracleTree
        assert(shared)

        project.close()
    }

    /**
     * The separation (INC.14) demands: a store build types nodes an ordinary build
     * never types, so its result may not become this project's `cached` and cannot
     * answer [Project.diagnostics]. Asserted as a BUILD COUNT, because the two
     * builds return equal diagnostics and no value can tell them apart.
     */
    @Test
    fun `typeOracle does not fill cached so diagnostics still builds`() {
        val dir = "/inv2b-separate"
        val counting = CountingVfs(vfsAt(dir))
        val project = Project.open(dir, counting)

        project.typeOracle()
        val afterOracle = counting.readsOf("$dir/tsconfig.json")
        val oracleBuilt = afterOracle > 0
        assert(oracleBuilt)

        project.diagnostics()
        val afterDiagnostics = counting.readsOf("$dir/tsconfig.json")
        val diagnosticsBuiltToo = afterDiagnostics > afterOracle
        assert(diagnosticsBuiltToo)

        // …and the control that makes the row above mean "the oracle build was not
        // adopted" rather than "diagnostics always builds": a SECOND `diagnostics()`
        // is served from `cached` and reads nothing.
        project.diagnostics()
        val afterSecondDiagnostics = counting.readsOf("$dir/tsconfig.json")
        assert(afterSecondDiagnostics == afterDiagnostics)

        project.close()
    }

    /**
     * The mirror of the pin above: a `diagnostics()` build does not hand this project
     * an oracle either, so asking for one after it is still a build. Together they
     * pin that the two lanes are disjoint in BOTH directions, which is what keeps a
     * future round from "saving a build" by adopting one for the other.
     */
    @Test
    fun `a diagnostics build does not supply an oracle`() {
        val dir = "/inv2b-separate-mirror"
        val counting = CountingVfs(vfsAt(dir))
        val project = Project.open(dir, counting)

        project.diagnostics()
        val afterDiagnostics = counting.readsOf("$dir/tsconfig.json")
        val diagnosticsBuilt = afterDiagnostics > 0
        assert(diagnosticsBuilt)

        project.typeOracle()
        val afterOracle = counting.readsOf("$dir/tsconfig.json")
        val oracleBuiltToo = afterOracle > afterDiagnostics
        assert(oracleBuiltToo)

        project.close()
    }

    /**
     * A DEGENERATE project still hands out a usable oracle — measured rather than
     * assumed, because the member's return type is nullable and a host has to know
     * what a null would mean.
     *
     * An editor opens a project before the user has written a `tsconfig.json`, and
     * a config is malformed for one keystroke out of every edit to it. All four of
     * these answer a NON-null oracle (over 0 or 1 files): an empty program, a
     * directory with no config at all, a config that is not JSON, and one setting a
     * TypeScript-7-removed option. So a null is not produced by any configuration
     * measured here — it would mean a build that never reached a `Checker`, and a
     * host meeting one has met a defect rather than a project it should tolerate.
     */
    @Test
    fun `a degenerate project still hands out an oracle`() {
        val cases = listOf(
            "no source files" to InMemoryVfs(mapOf("/inv2b-empty/tsconfig.json" to config)),
            "no config at all" to InMemoryVfs(mapOf("/inv2b-empty/src/a.ts" to "export const q = 1;\n")),
            "a config that is not JSON" to InMemoryVfs(
                mapOf(
                    "/inv2b-empty/tsconfig.json" to "{ this is not json",
                    "/inv2b-empty/src/a.ts" to "export const q = 1;\n",
                ),
            ),
            "a TypeScript-7-removed option" to InMemoryVfs(
                mapOf(
                    "/inv2b-empty/tsconfig.json" to
                        """{ "compilerOptions": { "baseUrl": "." }, "include": ["src/**/*.ts"] }""",
                    "/inv2b-empty/src/a.ts" to "export const q = 1;\n",
                ),
            ),
        )
        for ((_, vfs) in cases) {
            val project = Project.open("/inv2b-empty", vfs)
            val oracle = project.typeOracle()
            assert(oracle != null)
            // …and it is a usable one: closed and refusing, not a stub.
            val closedByEdit = run {
                project.updateFile("/inv2b-empty/src/a.ts", "export const q = 2;\n")
                oracle.isClosed
            }
            assert(closedByEdit)
            project.close()
        }
    }

    /** A closed project answers nothing, including this. */
    @Test
    fun `typeOracle on a closed project throws`() {
        val dir = "/inv2b-closed"
        val project = Project.open(dir, vfsAt(dir))
        project.close()
        var threw = false
        try {
            project.typeOracle()
        } catch (_: IllegalStateException) {
            threw = true
        }
        assert(threw)
    }
}
