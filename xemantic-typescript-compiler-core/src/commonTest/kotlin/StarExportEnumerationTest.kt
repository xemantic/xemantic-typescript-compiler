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
 * (LIB.8) [Checker.exportedSymbolsThroughStars] — the ENUMERATION companion to
 * [Checker.resolveExportedSymbolThroughStars].
 *
 * ## Why this is pinned HERE and not through a diagnostic
 *
 * `diagnose()` cannot resolve `import * as X from './mid'` where `mid.ts` is
 * `export * from './lib'` ((CHK.100)), which is exactly the shape every case
 * below has — so a pin written through a diagnostic would be measuring the
 * harness. Direct `Checker(options, binderResults)` construction is what
 * `Inv3PerFileLookupTest` uses for the same reason, and it lets the SYMBOL
 * identity be asserted against the declaring file's own binder table, which is
 * the half a name-set assertion cannot see.
 *
 * ## What the module symbol's own table answers instead, measured
 *
 * `Checker.createModuleSymbol` sets `moduleSymbol.exports = targetResult.locals`,
 * so that table is the target file's LOCALS. For `/proj/barrel.ts` below it is
 * `[own, ownFn]` — the star's names absent — and for a file with
 * `export { inner as outer }` it is keyed `inner`. Both are silently WRONG for
 * an enumeration and perfectly fine for the by-NAME lookup that built it, which
 * is why this is a separate question rather than a widening of that one.
 *
 * Every expectation is tsgo 7.0.2's, taken by running the same program under
 * `tools/tsgo-7.0.2/lib/tsc` and `node` — a namespace object's contents being
 * an enumeration question that no diagnostic can adjudicate. ORDER is the one
 * thing that deliberately differs: a real ES module namespace object SORTS its
 * keys, and this backend reports the file's declaration order, a (P18.124)
 * divergence this round does not touch.
 */
class StarExportEnumerationTest {

    private fun buildChecker(vararg files: Pair<String, String>): Pair<Checker, Map<String, BinderResult>> {
        val options = CompilerOptions()
        val results = files.map { (name, src) -> Binder(options).bind(Parser(src.trimIndent(), name).parse()) }
        val byName = results.associateBy { it.sourceFile.fileName }
        return Checker(options, results, isMultiFileSource = true) to byName
    }

    private fun exportsOf(
        checker: Checker,
        results: Map<String, BinderResult>,
        file: String,
    ): Map<String, Symbol>? =
        checker.exportedSymbolsThroughStars(results.getValue(file).sourceFile)

    // ---- the six characterised shapes --------------------------------------

    /** A BARREL over two files: both targets' names, which the locals table has none of. */
    @Test
    fun `a barrel over two files enumerates both targets`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export const alpha: string = "A";
                export function fa(): string { return "fa"; }
            """,
            "/proj/b.ts" to """
                export const beta: string = "B";
                export class Cb { tag: string = "cb"; }
            """,
            "/proj/barrel.ts" to """
                export * from './a'
                export * from './b'
            """,
        )
        val names = exportsOf(checker, results, "/proj/barrel.ts")?.keys?.toList()
        assert(names == listOf("alpha", "fa", "beta", "Cb"))
        // the locals table the old consumer read is EMPTY for this file
        val locals = results.getValue("/proj/barrel.ts").locals.keys.toList()
        assert(locals == emptyList<String>())
    }

    /** Each entry is the DECLARING file's own symbol, not a re-export stand-in. */
    @Test
    fun `a barrel's entries are the declaring file's symbols`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export const alpha: string = "A";
            """,
            "/proj/barrel.ts" to """
                export * from './a'
            """,
        )
        val exports = exportsOf(checker, results, "/proj/barrel.ts")
        val declared = results.getValue("/proj/a.ts").locals["alpha"]
        val same = exports?.get("alpha") === declared
        assert(declared != null)
        assert(same)
    }

    /** A barrel over a BARREL — the chain is followed, not just one hop. */
    @Test
    fun `a nested barrel enumerates through both hops`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export const alpha: string = "A";
            """,
            "/proj/inner.ts" to """
                export * from './a'
                export const midv: string = "M";
            """,
            "/proj/barrel.ts" to """
                export * from './inner'
            """,
        )
        val names = exportsOf(checker, results, "/proj/barrel.ts")?.keys?.toList()
        assert(names == listOf("alpha", "midv"))
    }

    /**
     * A CYCLE terminates and reports both files' own names.
     *
     * tsgo answers `av,bv` for this program; the ORDER differs here for the
     * reason the class KDoc states, and the SET is what is being pinned.
     */
    @Test
    fun `a star-export cycle terminates and enumerates both files`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export * from './b'
                export const av: string = "A";
            """,
            "/proj/b.ts" to """
                export * from './a'
                export const bv: string = "B";
            """,
        )
        val names = exportsOf(checker, results, "/proj/a.ts")?.keys?.toSet()
        assert(names == setOf("av", "bv"))
    }

    /**
     * A barrel that ALSO declares its own exports — the shape whose locals table
     * is NON-empty, so the old consumer answered it rather than refusing and the
     * starred names came back `null` at run time.
     */
    @Test
    fun `a barrel with its own exports enumerates both halves`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export const alpha: string = "A";
            """,
            "/proj/barrel.ts" to """
                export * from './a'
                export const own: string = "OWN";
                export function ownFn(): string { return "ownFn"; }
            """,
        )
        val names = exportsOf(checker, results, "/proj/barrel.ts")?.keys?.toList()
        assert(names == listOf("alpha", "own", "ownFn"))
        // what the old consumer saw, and why it answered rather than refusing
        val locals = results.getValue("/proj/barrel.ts").locals.keys.toList()
        assert(locals == listOf("own", "ownFn"))
    }

    /** A star re-export BESIDE a renaming named re-export of the same symbol. */
    @Test
    fun `a renaming re-export beside a star keys by the name an importer sees`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export const alpha: string = "A";
                export const inner: string = "I";
            """,
            "/proj/barrel.ts" to """
                export * from './a'
                export { inner as outer } from './a'
            """,
        )
        val exports = exportsOf(checker, results, "/proj/barrel.ts")
        assert(exports?.keys?.toList() == listOf("alpha", "inner", "outer"))
        // `outer` and `inner` are ONE symbol, the one `/proj/a.ts` declares
        val declared = results.getValue("/proj/a.ts").locals["inner"]
        val outerIsDeclared = exports.get("outer") === declared
        assert(declared != null)
        assert(outerIsDeclared)
    }

    /** `export { x as default }` — the name `default`, and NOT the local `x`. */
    @Test
    fun `an as-default export is keyed default and hides its local`() {
        val (checker, results) = buildChecker(
            "/proj/m.ts" to """
                const x: string = "X";
                export { x as default };
                export const plain: string = "P";
            """,
        )
        val exports = exportsOf(checker, results, "/proj/m.ts")
        assert(exports?.keys?.toList() == listOf("default", "plain"))
        val declared = results.getValue("/proj/m.ts").locals["x"]
        val defaultIsX = exports.get("default") === declared
        assert(declared != null)
        assert(defaultIsX)
    }

    // ---- the rules the six shapes do not state -----------------------------

    /** A LOCAL `export { a as b }` — the M2 half with no `from` clause. */
    @Test
    fun `a local renaming export clause is keyed by the exported name`() {
        val (checker, results) = buildChecker(
            "/proj/m.ts" to """
                const inner: string = "I";
                function innerFn(): string { return "IF"; }
                export { inner as outer, innerFn as outerFn };
                export const plain: string = "P";
            """,
        )
        val exports = exportsOf(checker, results, "/proj/m.ts")
        assert(exports?.keys?.toList() == listOf("outer", "outerFn", "plain"))
        val declared = results.getValue("/proj/m.ts").locals["innerFn"]
        val outerFnIsDeclared = exports.get("outerFn") === declared
        assert(declared != null)
        assert(outerFnIsDeclared)
    }

    /**
     * A module-private local is NOT an export.
     *
     * The distinction the locals table cannot make, and the third way it is
     * wrong for an enumeration: `secret` is in it and is not exported.
     */
    @Test
    fun `a module-private local is not enumerated`() {
        val (checker, results) = buildChecker(
            "/proj/m.ts" to """
                const secret: string = "S";
                export const shown: string = "V" + secret;
            """,
        )
        val names = exportsOf(checker, results, "/proj/m.ts")?.keys?.toList()
        assert(names == listOf("shown"))
        val locals = results.getValue("/proj/m.ts").locals.keys.toSet()
        assert(locals == setOf("secret", "shown"))
    }

    /** An OWN export of a starred name shadows it, with the own symbol. */
    @Test
    fun `an own export shadows a starred name of the same spelling`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export const shared: string = "A";
            """,
            "/proj/barrel.ts" to """
                export * from './a'
                export const shared: string = "OWN";
            """,
        )
        val exports = exportsOf(checker, results, "/proj/barrel.ts")
        assert(exports?.keys?.toList() == listOf("shared"))
        val own = results.getValue("/proj/barrel.ts").locals["shared"]
        val shadowed = exports.get("shared") === own
        assert(own != null)
        assert(shadowed)
    }

    /**
     * A star re-export does NOT carry `default` — ES semantics, and what makes
     * `export { x as default }` a property of the OWN file only.
     */
    @Test
    fun `a star re-export does not carry default`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                const x: string = "X";
                export { x as default };
                export const plain: string = "P";
            """,
            "/proj/barrel.ts" to """
                export * from './a'
            """,
        )
        val names = exportsOf(checker, results, "/proj/barrel.ts")?.keys?.toList()
        assert(names == listOf("plain"))
    }

    /**
     * UNKNOWABLE is NULL, and a caller must not read it as "no exports".
     *
     * A bare package specifier is the case a barrel of a real library hits, and
     * an empty map there would be a silently short namespace object.
     */
    @Test
    fun `a bare-specifier star target makes the whole set unknowable`() {
        val (checker, results) = buildChecker(
            "/proj/barrel.ts" to """
                export * from 'some-package'
                export const own: string = "OWN";
            """,
        )
        val exports = exportsOf(checker, results, "/proj/barrel.ts")
        assert(exports == null)
    }

    /** NEGATIVE CONTROL: the same barrel with a RESOLVABLE target is not null. */
    @Test
    fun `negative control - a resolvable star target is knowable`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export const alpha: string = "A";
            """,
            "/proj/barrel.ts" to """
                export * from './a'
                export const own: string = "OWN";
            """,
        )
        val names = exportsOf(checker, results, "/proj/barrel.ts")?.keys?.toList()
        assert(names == listOf("alpha", "own"))
    }

    /** A star target OUTSIDE the program is unknowable too, not empty. */
    @Test
    fun `a star target that is not in the program is unknowable`() {
        val (checker, results) = buildChecker(
            "/proj/barrel.ts" to """
                export * from './missing'
                export const own: string = "OWN";
            """,
        )
        val exports = exportsOf(checker, results, "/proj/barrel.ts")
        assert(exports == null)
    }

    /** An `export =` star target is unknowable — it has no named export set. */
    @Test
    fun `an export-equals star target is unknowable`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                const thing: string = "T";
                export = thing;
            """,
            "/proj/barrel.ts" to """
                export * from './a'
                export const own: string = "OWN";
            """,
        )
        val exports = exportsOf(checker, results, "/proj/barrel.ts")
        assert(exports == null)
    }

    /** Asking twice answers the SAME instance — the memo, which the consumer keys by. */
    @Test
    fun `the answer is memoized per file`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export const alpha: string = "A";
            """,
            "/proj/barrel.ts" to """
                export * from './a'
            """,
        )
        val first = exportsOf(checker, results, "/proj/barrel.ts")
        val second = exportsOf(checker, results, "/proj/barrel.ts")
        assert(first != null)
        assert(first === second)
    }

    /**
     * A NAMED re-export whose target re-exports back terminates.
     *
     * The bare-star walk's own visited set cannot answer a named re-export —
     * it asks a different question about the target — so that arm goes back
     * through the memoized entry, and `exportedSymbolsInProgress` is the only
     * thing that stops it looping. A hang here is the failure, not a value.
     */
    @Test
    fun `a named re-export cycle terminates`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export { bv as av } from './b'
                export const aown: string = "A";
            """,
            "/proj/b.ts" to """
                export { aown as bv } from './a'
                export const bown: string = "B";
            """,
        )
        val names = exportsOf(checker, results, "/proj/a.ts")?.keys?.toSet()
        assert(names != null)
        assert("aown" in names)
    }

    /** Type-only exports ARE names an importer sees; the backend filters them, not this. */
    @Test
    fun `type-only exports are enumerated as names`() {
        val (checker, results) = buildChecker(
            "/proj/m.ts" to """
                export interface Shape { w: number }
                export type Alias = string;
                export enum Flavour { A, B }
                export const only: string = "only";
            """,
        )
        val names = exportsOf(checker, results, "/proj/m.ts")?.keys?.toList()
        assert(names == listOf("Shape", "Alias", "Flavour", "only"))
    }

}
