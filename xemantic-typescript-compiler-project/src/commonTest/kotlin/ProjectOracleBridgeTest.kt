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
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.SourceFile
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * (INV.2b) commit 2 — the POSITION→NODE BRIDGE: [Project.nodeAt] made public, and
 * anchored on the tree the live [Project.typeOracle] answers about.
 *
 * ## What this commit is for
 *
 * Commit 1 shipped an oracle nobody outside this module could address. Every
 * `TypeOracle` row takes a `Node`, `Project.nodeAt` was `internal`, and
 * [NodeInfo] is a descriptor by design — so a host holding an oracle had exactly
 * one supported move, which was to walk `TypeOracle.files` itself. This class
 * pins that it no longer has to, and the two properties that make the result
 * trustworthy: the node is a node of the ORACLE'S OWN tree, and the descent is
 * the one that gets `Node.end` right.
 *
 * ## The measurement that says a host must not hand-roll the descent
 *
 * Taken before any of this was written, over **669,350 offsets** spanning twelve
 * of tsc's own compiler sources (`Inv2bBridgeProbeMain`):
 *
 *  * the obvious descent — recurse into the child whose `[pos, end)` contains the
 *    offset — names a DIFFERENT node at **190,820 of them (28.5 %)**, because
 *    `Node.end` is the end of the token AFTER the node (round 910) and sibling
 *    spans therefore overlap;
 *  * at those offsets the ORACLE'S ANSWER differs — a different type, or one
 *    where the other has none — at **42,507 (6.4 % of all offsets sampled)**;
 *  * restricted to offsets that BEGIN an identifier, i.e. the realistic caret, it
 *    is 591 of 25,533 (2.3 %), of which 27 change the type. Rarer there, and
 *    silent when it happens.
 *
 * [`round 910 is honoured at the bridge`] is that finding as a value pin.
 *
 * ## What the anchoring does NOT fix, stated so it is not mistaken for a bug fix
 *
 * The same probe measured tree identity on the SHIPPED path: over all 669,350
 * offsets the node [Project.nodeAt] answered was a node of the oracle's own tree
 * **every single time** (0 from another tree, 0 nulls). The anchor is therefore
 * not a repair — it turns a property that held by the cooperation of three caches
 * into two lines of code. That is worth having because its violation is SILENT:
 * `TypeOracle.storeOf` finds a store by the node's file NAME and reads
 * `types[node.nodeId]` behind a bounds check alone, so a node from any
 * equally-named tree is ANSWERED, correctly while the trees agree and
 * confidently wrongly once they do not.
 *
 * ## What the ablation says (one mistake at a time, 21 tests per arm)
 *
 * | arm | RED |
 * |---|---|
 * | drop `Project.oracleTreeOf`'s preference in `sourceIndexOf` (the MISS half) | 1 — `the bridge is anchored even when it was asked before the oracle existed` |
 * | drop `Project.buildOracle`'s index clear (the HIT half) | 1 — the same pin |
 * | drop the `isClosed` test in `oracleTreeOf` | **0 — undiscriminated** |
 * | descend by `Node.end` instead of `SourceIndex.realEndOf` | 1 — `round 910 is honoured at the bridge` |
 *
 * The first two are round 927's pair: two guards on two layers, covering
 * different halves of one path, each load-bearing and neither separable by any
 * pin — recorded as ONE observable rather than claimed as two. The third is a
 * measured redundant guard and `Project.oracleTreeOf` says why it stays. The
 * fourth reddens exactly the pin written for it and NO other, which is the
 * honest reading of the 28.5 %: on a fixture whose caret sits at the start of an
 * identifier the two descents agree, so only a pin that puts the caret where
 * they differ can see the difference at all.
 */
class ProjectOracleBridgeTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    /**
     * The same round-911 positive control [ProjectTypeOracleTest] uses: a body
     * local SHADOWING a file-level declaration of the same name, so an answer of
     * `number` can only have come from the walk and never from a post-hoc ask.
     */
    private val source = """
        declare const zzzCollide: string;
        export function probe(): void {
          const zzzCollide: number = 1;
          const useLocal = zzzCollide;
        }
    """.trimIndent() + "\n"

    private fun vfsAt(dir: String, src: String = source): InMemoryVfs = InMemoryVfs(
        mapOf(
            "$dir/tsconfig.json" to config,
            "$dir/src/a.ts" to src,
        ),
    )

    /** The n-th occurrence of [needle] in [text], plus [skip] characters. */
    private fun offsetOf(needle: String, occurrence: Int = 0, skip: Int = 0, text: String = source): Int {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at + skip
    }

    /** The [SourceFile] a node belongs to, by ascending `parent`. */
    private fun rootOf(node: Node): SourceFile? {
        var current: Node? = node
        while (current != null) {
            if (current is SourceFile) return current
            current = (current as NodeBase).parent
        }
        return null
    }

    // -------------------------------------------------------------------------
    // 1. The bridge is usable — the whole point of the commit.
    // -------------------------------------------------------------------------

    /**
     * The four lines a host writes, through PUBLIC members only. It is a
     * non-vacuity pin as much as a usage one: the answer is the walk's `number`,
     * which no post-hoc ask of the same checker produces (round 911), so a bridge
     * that handed back a plausible-but-unwalked node could not satisfy it.
     */
    @Test
    fun `a host reaches the walk's own type through nodeAt and the oracle`() {
        val dir = "/inv2b-bridge-usable"
        val file = "$dir/src/a.ts"
        val project = Project.open(dir, vfsAt(dir))

        val oracle = assertNotNull(project.typeOracle())
        val node = project.nodeAt(file, offsetOf("zzzCollide", occurrence = 2))
        val type = (node as? Expression)?.let { oracle.typeAt(it) }?.let { oracle.typeToString(it) }
        assert(type == "number")

        project.close()
    }

    // -------------------------------------------------------------------------
    // 2. Tree identity, in both orderings.
    // -------------------------------------------------------------------------

    /**
     * Oracle first: the node the bridge answers is a node of the oracle's own tree.
     *
     * `===`, never equality — two equal trees answer every public query the same
     * way and differ by 103 MB ((INC.36)), so identity is the only instrument that
     * can see this at all. Never inside a power-assert diagram either: a node
     * renders its whole subtree (CLAUDE.md's AST-subexpression trap).
     */
    @Test
    fun `a node from the bridge belongs to the oracle's own tree`() {
        val dir = "/inv2b-bridge-identity"
        val file = "$dir/src/a.ts"
        val project = Project.open(dir, vfsAt(dir))

        val oracle = assertNotNull(project.typeOracle())
        val node = assertNotNull(project.nodeAt(file, offsetOf("zzzCollide", occurrence = 2)))
        val oracleTree = oracle.files.firstOrNull { it.fileName == file }
        val bothPresent = oracleTree != null
        assert(bothPresent)
        val sameTree = rootOf(node) === oracleTree
        assert(sameTree)

        project.close()
    }

    /**
     * …and the ordering that needs [Project]'s own collaboration: the bridge asked
     * BEFORE the oracle existed, with a SECOND project over the same absolute paths
     * replacing the process-wide parse cache's entry in between.
     *
     * ## Why the second project is in the fixture and not decoration
     *
     * `CrawlParseCache` is process-global and keyed by PATH, with the content
     * inside the value, and `store` REPLACES. So the sequence below is the one
     * configuration in which the pre-oracle index cannot heal itself: the first
     * project's index is a private parse (the compiler had never seen those bytes
     * when it was asked), `upgradeIfShareable` re-points it only by finding the
     * same bytes in the cache, and the second project's build has just overwritten
     * that entry with different ones. Without the drop in `Project.buildOracle`
     * this reads a tree that is NOT the oracle's.
     *
     * ## What it does and does not prove
     *
     * The stale tree is a parse of the SAME text under the SAME flags, so its
     * `nodeId`s coincide and the oracle would still answer CORRECTLY through it.
     * This pin is therefore about IDENTITY and says so: what the drop buys is that
     * "a node from this project is a node of this project's oracle" is true by
     * construction for every ordering, rather than true because the two trees
     * happen to agree. A pin that claimed a wrong VALUE here would be claiming
     * something the configuration does not produce.
     */
    @Test
    fun `the bridge is anchored even when it was asked before the oracle existed`() {
        val dir = "/inv2b-bridge-ordering"
        val file = "$dir/src/a.ts"
        val project = Project.open(dir, vfsAt(dir))

        // (a) Asked first, with nothing built: this parses PRIVATELY.
        val early = assertNotNull(project.nodeAt(file, offsetOf("zzzCollide", occurrence = 2)))
        val earlyTreeExists = rootOf(early) != null
        assert(earlyTreeExists)

        val oracle = assertNotNull(project.typeOracle())

        // (b) A second project over the SAME paths, different bytes, built after
        //     the oracle: `CrawlParseCache`'s entry for this path now holds ITS
        //     text, so the first project's private index can no longer be healed
        //     by a cache lookup.
        val other = Project.open(dir, vfsAt(dir, source.replace("= 1;", "= 2;")))
        other.diagnostics()
        other.close()

        // (c) …and the bridge still answers a node of the ORACLE's tree.
        val node = assertNotNull(project.nodeAt(file, offsetOf("zzzCollide", occurrence = 2)))
        val oracleTree = oracle.files.firstOrNull { it.fileName == file }
        val bothPresent = oracleTree != null
        assert(bothPresent)
        val sameTree = rootOf(node) === oracleTree
        assert(sameTree)

        project.close()
    }

    /**
     * The anchor is dropped when the oracle is, so a bridge query after an edit is
     * not served from a tree the closed oracle owns.
     *
     * The observable is that the node's tree is a NEW one: the edit re-parses, and
     * `Project.oracleTreeOf` consults `isClosed` rather than merely the field.
     */
    @Test
    fun `an edit re-anchors the bridge on a fresh tree`() {
        val dir = "/inv2b-bridge-reanchor"
        val file = "$dir/src/a.ts"
        val project = Project.open(dir, vfsAt(dir))

        val oracle = assertNotNull(project.typeOracle())
        val oracleTree = assertNotNull(oracle.files.firstOrNull { it.fileName == file })

        val edited = source.replace("const zzzCollide: number = 1;", "const zzzCollide: number = 11;")
        project.updateFile(file, edited)

        val node = assertNotNull(project.nodeAt(file, offsetOf("zzzCollide", occurrence = 2, text = edited)))
        val freshTree = rootOf(node) !== oracleTree
        assert(freshTree)

        project.close()
    }

    /**
     * A host may hold the oracle's reference and close it itself, and the bridge
     * must keep answering — a closed oracle is a statement about what may be
     * ASKED, never about what the text is.
     *
     * **A CONTROL, not a pin for the `isClosed` test in `Project.oracleTreeOf`**,
     * and the ablation says so: dropping that test reads 0 RED, including here.
     * It cannot be otherwise — nothing but an edit closes an oracle from the
     * project's side and an edit also nulls the field, so a host-closed oracle's
     * trees are still the parse of the current text and serving them is harmless.
     * What this pin does say is that the bridge does not BREAK in that state,
     * which an implementation routing through the oracle less carefully would.
     */
    @Test
    fun `the bridge still answers after the host closes the oracle itself`() {
        val dir = "/inv2b-bridge-hostclose"
        val file = "$dir/src/a.ts"
        val project = Project.open(dir, vfsAt(dir))

        val oracle = assertNotNull(project.typeOracle())
        oracle.close()

        val node = project.nodeAt(file, offsetOf("zzzCollide", occurrence = 2))
        val stillAnswers = node != null
        assert(stillAnswers)

        project.close()
    }

    // -------------------------------------------------------------------------
    // 3. Round 910 at the bridge — the reason a host must not hand-roll it.
    // -------------------------------------------------------------------------

    /**
     * `Node.end` is the end of the token AFTER the node, so an identifier's raw
     * span reaches past its own last character and a naive `pos <= off < end`
     * descent claims offsets that belong to the PARENT.
     *
     * The fixture is the shape the probe's first six examples all were: the caret
     * on the comma of an import clause. `Node.pos`/`Node.end` of the first
     * specifier's name say the comma is inside it; [SourceIndex.realEndOf] bounds
     * it at its own last token, so the correct answer is the enclosing clause.
     *
     * The assertion is on the answer being the ENCLOSING node rather than on a
     * specific kind name, so a parser refactor that renames a node does not fail a
     * pin about spans.
     */
    @Test
    fun `round 910 is honoured at the bridge`() {
        val dir = "/inv2b-bridge-span"
        val file = "$dir/src/a.ts"
        val text = """
            import { alpha, beta } from "./m";
            export const use = alpha + beta;
        """.trimIndent() + "\n"
        val vfs = InMemoryVfs(
            mapOf(
                "$dir/tsconfig.json" to config,
                "$dir/src/a.ts" to text,
                "$dir/src/m.ts" to "export const alpha = 1;\nexport const beta = 2;\n",
            ),
        )
        val project = Project.open(dir, vfs)

        val commaAt = offsetOf(",", text = text)
        // The NAIVE test: is the comma inside the first specifier's raw span?
        val alphaNode = assertNotNull(project.nodeAt(file, offsetOf("alpha", text = text)))
        val naiveWouldDescend = commaAt >= alphaNode.pos && commaAt < alphaNode.end
        // Without this the pin passes on a parser whose spans do not overshoot, i.e.
        // it would stop testing what it names (round 902 — assert the mistake is
        // REACHED).
        assert(naiveWouldDescend)

        // …and the bridge answers the ENCLOSING node instead.
        val atComma = assertNotNull(project.nodeAt(file, commaAt))
        val enclosing = atComma !== alphaNode
        assert(enclosing)
        val alphaIsInside = run {
            var current: Node? = alphaNode
            var found = false
            while (current != null) {
                if (current === atComma) found = true
                current = (current as NodeBase).parent
            }
            found
        }
        assert(alphaIsInside)

        project.close()
    }

    // -------------------------------------------------------------------------
    // 4. The boundaries a host will meet.
    // -------------------------------------------------------------------------

    /**
     * A file the oracle never walked still answers a NODE — the bridge parses and
     * does not build — and the oracle answers NULL about it rather than reading
     * some other file's store.
     *
     * The second half is the one worth pinning: `TypeOracle.storeOf` is keyed by
     * file name, so "no store for this name" is the whole of what keeps an
     * out-of-program node from being answered.
     */
    @Test
    fun `a file outside the program answers a node and no type`() {
        val dir = "/inv2b-bridge-outside"
        val outside = "$dir/scratch/b.ts"
        val vfs = vfsAt(dir)
        vfs.writeText(outside, "export const zzzOutside: number = 1;\n")
        val project = Project.open(dir, vfs)

        val oracle = assertNotNull(project.typeOracle())
        val walked = oracle.files.none { it.fileName == outside }
        assert(walked)

        val node = assertNotNull(project.nodeAt(outside, "export const ".length))
        // Computed into a Boolean first: a power-assert diagram toStrings every
        // subexpression, and a `Type` renders its structure (CLAUDE.md's
        // AST-subexpression trap, which applies to anything deep).
        val noType = (node as? Expression)?.let { oracle.typeAt(it) } == null
        assert(noType)

        project.close()
    }

    /** The three offsets that are outside every node, and all answer null. */
    @Test
    fun `offsets outside the text answer null`() {
        val dir = "/inv2b-bridge-bounds"
        val file = "$dir/src/a.ts"
        val project = Project.open(dir, vfsAt(dir))
        project.typeOracle()

        // Booleans first, never the node: on a failure a power-assert diagram would
        // otherwise toString the node and dump its whole subtree.
        val negative = project.nodeAt(file, -1) == null
        assert(negative)
        // Half-open spans: the caret one past the last character is inside no node.
        val atEnd = project.nodeAt(file, source.length) == null
        assert(atEnd)
        val pastEnd = project.nodeAt(file, source.length + 10) == null
        assert(pastEnd)
        val unknownFile = project.nodeAt("$dir/src/missing.ts", 0) == null
        assert(unknownFile)

        project.close()
    }

    /**
     * The bridge PARSES and does not BUILD, exactly as [Project.nodeInfoAt] does —
     * so a host may address a dirty buffer without paying for a compile, and gets
     * a node of the buffer rather than of the file on disk.
     *
     * Counted on `tsconfig.json`, which no test here edits ((INC.12): an overlaid
     * file is served from the overlay and never reaches the backing `Vfs`, so a
     * count keyed on the edited file silently stops moving).
     */
    @Test
    fun `the bridge does not build`() {
        val dir = "/inv2b-bridge-nobuild"
        val file = "$dir/src/a.ts"
        val counting = CountingVfs(vfsAt(dir))
        val project = Project.open(dir, counting)

        project.updateFile(file, source.replace("= 1;", "= 123;"))
        val before = counting.readsOf("$dir/tsconfig.json")
        val node = assertNotNull(project.nodeAt(file, offsetOf("zzzCollide", occurrence = 2)))
        val after = counting.readsOf("$dir/tsconfig.json")
        // A parse needs the options, so a FIRST ask may read the config; what it may
        // not do is build. The control below is what makes that distinction real.
        val parsedNotBuilt = after - before <= 1
        assert(parsedNotBuilt)
        val answered = node.pos >= 0
        assert(answered)

        // The control: a build reads more than that, so the bound above is a claim
        // about this member and not about a dead counter.
        project.diagnostics()
        val afterBuild = counting.readsOf("$dir/tsconfig.json")
        val buildReadsMore = afterBuild > after
        assert(buildReadsMore)

        project.close()
    }
}
