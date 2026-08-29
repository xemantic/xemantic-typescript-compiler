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
import kotlin.test.Test

/**
 * (INC.46) The invariants of the exported-signature fingerprint.
 *
 * Every pin here is a VALUE pin and not an absence pin: the mechanism's failure
 * modes are "the hash moved when nothing did" (which costs the whole prize) and
 * "the hash did not move when the surface did" (which costs a stale diagnostic),
 * and neither is visible in any diagnostic, any corpus baseline, any `cost_gate.py`
 * counter or the 8-profile grid — the walk is OFF in the shipped compiler and
 * changes no output when it is on.
 *
 * The mode is installed and RESTORED per call, which is why [fingerprintsOf] is the
 * only door: `ExportSignatures.enabled` is a process-global, so a test that left it
 * armed would make every alphabetically-later test in the JVM pay for a fingerprint
 * walk (round 874's shape).
 */
class ExportSignatureFingerprintTest {

    private fun fingerprintsOf(source: String): Map<String, Long> {
        val was = ExportSignatures.enabled
        ExportSignatures.enabled = true
        ExportSignatures.reset()
        try {
            TypeScriptCompiler().compile(source.trimIndent(), "t.ts")
            return LinkedHashMap(ExportSignatures.fingerprints)
        } finally {
            ExportSignatures.enabled = was
        }
    }

    private fun escapesOf(source: String): Set<String> {
        val was = ExportSignatures.enabled
        ExportSignatures.enabled = true
        ExportSignatures.reset()
        try {
            TypeScriptCompiler().compile(source.trimIndent(), "t.ts")
            return LinkedHashSet(ExportSignatures.whole)
        } finally {
            ExportSignatures.enabled = was
        }
    }

    private val twoFiles = """
        // @strict: true
        // @Filename: a.ts
        export interface Shape { readonly kind: string }
        export function area(s: Shape): number { return s.kind.length }
        // @Filename: b.ts
        import { area, Shape } from "./a"
        export const answer: number = area({ kind: "sq" })
    """

    /**
     * THE CLAIM THE WHOLE MECHANISM RESTS ON: the hash is a function of the TEXT,
     * not of the build. `Type.id` and `Symbol.id` are per-build sequences, so a
     * fingerprint carrying one passes every structural test and then invalidates
     * every file on every edit — which is indistinguishable from the mechanism
     * simply not working.
     */
    @Test
    fun `two builds of identical text produce identical fingerprints`() {
        val first = fingerprintsOf(twoFiles)
        val second = fingerprintsOf(twoFiles)
        assert(first.keys.any { it.endsWith("a.ts") })
        assert(second == first)
    }

    /**
     * The PRIZE: a change confined to a function BODY leaves the export surface
     * intact, so no importer needs re-checking. (INC.46)'s own census says 91.6% of
     * a real program's characters sit inside brace-delimited bodies.
     */
    @Test
    fun `a body-only edit does not move the exporting file's fingerprint`() {
        val before = fingerprintsOf(twoFiles)
        val after = fingerprintsOf(
            twoFiles.replace(
                "{ return s.kind.length }",
                "{ const n = s.kind.length; return n + 0 }",
            ),
        )
        val a = before.keys.first { it.endsWith("a.ts") }
        assert(after[a] == before[a])
    }

    /**
     * The SOUNDNESS half, and the one a body-only pin cannot see: a moved return
     * type must move the hash. Written as a pair with the pin above deliberately —
     * a fingerprint that never moves passes that one and is useless.
     */
    @Test
    fun `a changed exported return type moves the fingerprint`() {
        val before = fingerprintsOf(twoFiles)
        val after = fingerprintsOf(
            twoFiles.replace("export function area(s: Shape): number", "export function area(s: Shape): string")
                .replace("{ return s.kind.length }", "{ return s.kind }")
                .replace("export const answer: number =", "export const answer: string ="),
        )
        val a = before.keys.first { it.endsWith("a.ts") }
        assert(after[a] != before[a])
    }

    /** A member added to an exported interface is a surface change. */
    @Test
    fun `a member added to an exported interface moves the fingerprint`() {
        val before = fingerprintsOf(twoFiles)
        val after = fingerprintsOf(
            twoFiles.replace(
                "export interface Shape { readonly kind: string }",
                "export interface Shape { readonly kind: string; readonly size: number }",
            ),
        )
        val a = before.keys.first { it.endsWith("a.ts") }
        assert(after[a] != before[a])
    }

    /**
     * An ADDED export changes what every importer resolves, with no existing type
     * moving at all — which is why the NAME SET is in the hash and not only the
     * types.
     */
    @Test
    fun `an added export moves the fingerprint`() {
        val before = fingerprintsOf(twoFiles)
        val after = fingerprintsOf(
            twoFiles.replace(
                "export function area",
                "export const version: number = 1\nexport function area",
            ),
        )
        val a = before.keys.first { it.endsWith("a.ts") }
        assert(after[a] != before[a])
    }

    /**
     * A re-export is an EDGE: this file's own hash carries the specifier and the
     * forwarded names, and the TARGET's hash carries the types — so changing the
     * specifier must move it even though no type here changed.
     */
    @Test
    fun `a changed re-export specifier moves the fingerprint`() {
        val source = """
            // @strict: true
            // @Filename: a.ts
            export const one: number = 1
            // @Filename: c.ts
            export const one: number = 1
            // @Filename: b.ts
            export { one } from "./a"
        """
        val before = fingerprintsOf(source)
        val after = fingerprintsOf(source.replace("""export { one } from "./a"""", """export { one } from "./c""""))
        val b = before.keys.first { it.endsWith("b.ts") }
        assert(after[b] != before[b])
    }

    /**
     * A SCRIPT file's top-level names are program-wide, so there is no export
     * surface to summarise and an edit to it can reach a file importing nothing
     * from it. Recorded as an escape rather than fingerprinted.
     */
    @Test
    fun `a script file escapes to whole-program`() {
        val escapes = escapesOf(
            """
            // @strict: true
            // @Filename: g.ts
            declare const globalThing: number
            // @Filename: b.ts
            export const x: number = 1
            """,
        )
        assert(escapes.any { it.endsWith("g.ts") })
        assert(escapes.none { it.endsWith("b.ts") })
    }

    /** A module that AUGMENTS the global scope escapes for the same reason. */
    @Test
    fun `a global augmentation escapes to whole-program`() {
        val escapes = escapesOf(
            """
            // @strict: true
            // @Filename: aug.ts
            export const marker: number = 1
            declare global { interface ZzzAugmented { p: number } }
            // @Filename: b.ts
            export const x: number = 1
            """,
        )
        assert(escapes.any { it.endsWith("aug.ts") })
        assert(escapes.none { it.endsWith("b.ts") })
    }

    /**
     * A binding-pattern export names things this walk does not enumerate, so the
     * file may not be proved stable — the conservative direction, and the one a
     * silent omission would get wrong.
     */
    @Test
    fun `a binding-pattern export escapes to whole-program`() {
        val escapes = escapesOf(
            """
            // @strict: true
            // @Filename: p.ts
            export const { a, b } = { a: 1, b: 2 }
            // @Filename: b.ts
            export const x: number = 1
            """,
        )
        assert(escapes.any { it.endsWith("p.ts") })
        assert(escapes.none { it.endsWith("b.ts") })
    }

    /**
     * A MUTUALLY recursive pair terminates and is stable — the memo's closedness
     * rule under test. The naive path-only walk is exponential in DAG width and did
     * not finish on tsc's own sources in 159 s; a wrong memo would instead answer a
     * hash that depends on which export was walked first, which shows up here as two
     * builds disagreeing.
     */
    @Test
    fun `mutually recursive exported types terminate and are stable`() {
        val source = """
            // @strict: true
            // @Filename: r.ts
            export interface NodeA { child: NodeB | null; self: NodeA | null }
            export interface NodeB { parent: NodeA; peers: NodeB[] }
            export function walk(n: NodeA): NodeB | null { return n.child }
        """
        val first = fingerprintsOf(source)
        val second = fingerprintsOf(source)
        val r = first.keys.first { it.endsWith("r.ts") }
        assert(first[r] != null)
        assert(second[r] == first[r])
    }

    /**
     * A hash that DEGRADES must not equal the healthy one. `typeToString` renders
     * `errorType` as `"any"` (B58.1), so a display-string hash would read a failed
     * resolution as a genuine `any` and MISS the invalidation — the walk reads
     * `Type.Intrinsic.intrinsicName`, which separates them.
     */
    @Test
    fun `an export typed any differs from one whose type is unresolvable`() {
        val healthy = fingerprintsOf(
            """
            // @strict: true
            // @Filename: a.ts
            export declare const value: any
            """,
        )
        val broken = fingerprintsOf(
            """
            // @strict: true
            // @Filename: a.ts
            export declare const value: ZzzNotDeclaredAnywhere
            """,
        )
        val a = healthy.keys.first { it.endsWith("a.ts") }
        assert(broken[a] != healthy[a])
    }

    /**
     * A file that only MENTIONS `export as namespace` — in a comment, a string, a
     * message — does NOT declare a global surface and must not escape.
     *
     * Found by the (INC.46) edit corpus, not by a fixture: `checker.ts` says those
     * words twice in `//` comments, so a bare substring scan escaped the whole file —
     * and since it is the file tsc's own history edits most, that ONE false positive
     * took the measured stability rate from 67% down to 32%. The construct is a
     * top-level STATEMENT, so the match must begin its line.
     */
    @Test
    fun `mentioning export as namespace in a comment does not escape`() {
        val escapes = escapesOf(
            """
            // @strict: true
            // @Filename: m.ts
            // export as namespace foo
            export const marker: number = 1
            const message = "  export as namespace bar"
            // @Filename: b.ts
            export const x: number = 1
            """,
        )
        assert(escapes.none { it.endsWith("m.ts") })
        assert(escapes.none { it.endsWith("b.ts") })
    }

    /**
     * (INC.47) A DENSE CYCLIC IN-FILE TYPE GRAPH — the shape that made `types.ts` the
     * one file of tsc's 78 that could not be fingerprinted at all.
     *
     * Each level refers to the next THREE times and back to the ROOT, so every subtree
     * is OPEN (it refers to a type strictly above it) and the (INC.46) closed-subtree
     * memo can cache none of it: the path-recursive walk then re-walks a level once
     * per path that reaches it, which is exponential in fan-out. Measured on the real
     * file that was **122.5 ms for ONE export** and still a node-budget STOP at
     * 2,000,000 nodes and at 12,000,000 — so the file ESCAPED, and an escaping file
     * invalidates the whole program on every edit.
     *
     * The (INC.47) walk discovers each type ONCE and names it by its discovery index,
     * so the same graph is a few dozen nodes. Pinned on the COUNTER rather than on a
     * time (round 868: a timed assertion over a small region is a coin flip), and the
     * counter is what separates the two implementations by three orders of magnitude.
     */
    @Test
    fun `a dense cyclic in-file type graph is fingerprinted exactly`() {
        val source = buildString {
            appendLine("// @strict: true")
            appendLine("// @Filename: g.ts")
            appendLine("export interface Root { deep: L0 }")
            for (i in 0 until 30) {
                appendLine(
                    "interface L$i { a: L${i + 1}; b: L${i + 1}; c: L${i + 1}; up: Root }",
                )
            }
            appendLine("interface L30 { end: string; up: Root }")
        }
        val was = ExportSignatures.enabled
        ExportSignatures.enabled = true
        ExportSignatures.reset()
        val nodes: Long
        val escaped: Boolean
        try {
            TypeScriptCompiler().compile(source, "t.ts")
            nodes = ExportSignatures.typeNodes
            escaped = ExportSignatures.whole.any { it.endsWith("g.ts") }
        } finally {
            ExportSignatures.enabled = was
        }
        // The file's surface is hashed in full: no budget stop, so an edit to it can
        // be PROVED stable instead of falling back to a whole-program build.
        assert(!escaped)
        assert(ExportSignatures.budgetStops == 0L)
        // Linear, not exponential: the graph has ~32 declarations, so a walk that
        // discovers each type once cannot visit thousands of nodes. The path-recursive
        // walk visits the 2,000,000-node ceiling on exactly this shape.
        assert(nodes < 10_000L)
    }

    /**
     * (INC.47) The SOUNDNESS half of the pin above, and the one it cannot see: a
     * change 30 levels down inside that cyclic graph must move the fingerprint.
     *
     * The path-recursive walk bounded its own recursion by a DEPTH CAP of 24 and hashed
     * everything past it as one constant, so a moved type below the cap read as no
     * change at all — a MISSED invalidation, i.e. a stale diagnostic, which is the only
     * direction that costs correctness. Discovery indices need no depth cap, so there
     * is nothing left to truncate.
     */
    @Test
    fun `a change deep inside a cyclic type graph moves the fingerprint`() {
        fun graph(leaf: String) = buildString {
            appendLine("// @strict: true")
            appendLine("// @Filename: g.ts")
            appendLine("export interface Root { deep: L0 }")
            for (i in 0 until 30) {
                appendLine("interface L$i { a: L${i + 1}; up: Root }")
            }
            appendLine("interface L30 { end: $leaf; up: Root }")
        }
        val before = fingerprintsOf(graph("string"))
        val after = fingerprintsOf(graph("number"))
        val g = before.keys.first { it.endsWith("g.ts") }
        assert(before[g] != null)
        assert(after[g] != before[g])
    }

    /** The shipped default is OFF — nothing in an ordinary compile pays for this. */
    @Test
    fun `the fingerprint walk is off by default`() {
        assert(!ExportSignatures.enabled)
    }
}
