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
 * (JIT.1)(e) round 815 — `CompilerOptionsKt.applyDirective` was **13,694
 * bytecodes**, so HotSpot never JIT-compiled it; it is now a four-line entry
 * that chains four `applyDirectiveArms1..4` helpers, each holding one
 * contiguous, in-order run of the original `when (key)` arms verbatim.
 *
 * **Why it was that big has nothing to do with how much it does**: every arm is
 * an `options.copy(...)` on a ~150-field data class, which compiles to a
 * `copy$default` call site carrying the full argument vector plus the default
 * bitmasks — ~160 bytecodes per arm, times 85 arms. The size is the arm count
 * times the data class's field count.
 *
 * **This target has no ORDER seam, and that is provable rather than untested**:
 * the 85 arm keys are pairwise DISTINCT (`applydirective_split_analyze.py`
 * asserts it), so a single `when` over all of them and a `?:`-chain over a
 * partition of them select the same arm regardless of the order the runs are
 * consulted in. What a split here CAN get wrong is (1) dropping a run from the
 * chain, (2) writing a run's fallthrough as `options` instead of `null` — which
 * silently swallows every LATER run — and (3) recomputing `boolValue` per run
 * instead of passing the entry's. This class pins all three:
 *
 *  * the COVERAGE pin walks all 85 keys and fails naming any key the chain no
 *    longer recognises — that is the sharp instrument for (1) and (2);
 *  * ARM pins name two or three keys per run, so a single dropped call site is
 *    attributable to its run rather than just "something broke";
 *  * the `else` seam is pinned by an unrecognised key returning the SAME
 *    instance, plus by the coverage pin (a run returning `options` instead of
 *    `null` makes every later key unhandled);
 *  * the `boolValue` seam is pinned by `"TRUE"` — the entry lowercases, so a
 *    helper that recomputed `value == "true"` fails here and nowhere else.
 *
 * `HugeMethodLimitTest` guards the SIZE; this class guards what a size check
 * cannot see.
 */
class ApplyDirectiveSplitTest {

    private val base = CompilerOptions()

    /**
     * Every directive key `applyDirective` names, in source order. The split is
     * a partition of exactly this set into four contiguous runs.
     */
    private val allKeys = listOf(
        // run 1 — "target" .. "noimplicitreturns"
        "target", "module", "strict", "noemit", "emitbom", "noemithelpers", "usereallibs",
        "declaration", "declarationdir", "declarationmap", "removecomments",
        "preserveconstenums", "sourcemap", "noimplicitany", "noimplicitreturns",
        // run 2 — "noimplicitthis" .. "esmoduleinterop"
        "noimplicitthis", "strictnullchecks", "useunknownincatchvariables",
        "exactoptionalpropertytypes", "nouncheckedindexedaccess",
        "strictpropertyinitialization", "nounusedlocals", "nounusedparameters",
        "experimentaldecorators", "emitdecoratormetadata", "jsx", "jsxfactory",
        "jsxfragmentfactory", "reactnamespace", "lib", "outdir", "rootdir", "typeroots",
        "types", "baseurl", "moduleresolution", "esmoduleinterop",
        // run 3 — "allowjs" .. "nofallthroughcasesinswitch"
        "allowjs", "checkjs", "isolatedmodules", "skiplibcheck",
        "forceconsistentcasinginfilenames", "noemitonerror", "downleveliteration",
        "importhelpers", "allowsyntheticdefaultimports", "usedefineforclassfields",
        "verbatimmodulesyntax", "nocheck", "emitdeclarationonly", "maproot", "outfile",
        "out", "alwaysstrict", "newline", "fullemitpaths", "allowunreachablecode",
        "allowunusedlabels", "nofallthroughcasesinswitch",
        // run 4 — "noresolve" .. "capturesuggestions"
        "noresolve", "noimplicitreferences", "moduledetection", "charset",
        "keyofstringsonly", "noimplicitusestrict", "nostrictgenericchecks",
        "suppressexcesspropertyerrors", "suppressimplicitanyindexerrors",
        "importsnotusedasvalues", "preservevalueimports", "resolvejsonmodule", "nolib",
        "inlinesourcemap", "inlinesources", "sourceroot", "composite", "pretty",
        "incremental", "isolateddeclarations", "erasablesyntaxonly", "ignoredeprecations",
        "typescriptversion", "allowimportingtsextensions",
        "rewriterelativeimportextensions", "capturesuggestions",
    )

    // ── the COVERAGE pin — the instrument for a dropped run ──────────────────

    @Test
    fun `every directive key is still recognised by the chain`() {
        // A key is "recognised" when SOME value moves the options off the
        // defaults. Boolean keys that default to true are moved by "false";
        // "target"/"module" need a value their `fromString` accepts.
        val probes = listOf("true", "false", "es2015", "commonjs", "xtsc-probe")
        val unhandled = allKeys.filter { key ->
            probes.none { applyDirective(base, key, it) != base }
        }
        assert(unhandled.isEmpty())
        // Positive control: the walk really did run over all 85 keys, so an
        // empty `unhandled` cannot be the empty-input kind of green.
        assert(allKeys.size == 85)
        assert(allKeys.toSet().size == 85)
    }

    @Test
    fun `an unrecognised key returns the very same options instance`() {
        // This is the `?: options` tail of the chain. It also pins that no run
        // answers for a key it does not name.
        assert(applyDirective(base, "nosuchdirectiveatall", "true") === base)
        assert(applyDirective(base, "strict", "true") !== base)
    }

    // ── run 1 arm pins ───────────────────────────────────────────────────────

    @Test
    fun `run 1 - target sets the target and marks it explicit`() {
        val o = applyDirective(base, "target", "es2015")
        assert(o.target == ScriptTarget.ES2015)
        assert(o.targetExplicitlySet)
    }

    @Test
    fun `run 1 - an unparseable target leaves the options untouched`() {
        assert(applyDirective(base, "target", "es-nosuch") === base)
    }

    @Test
    fun `run 1 - removeComments and preserveConstEnums`() {
        assert(applyDirective(base, "removecomments", "true").removeComments)
        val o = applyDirective(base, "preserveconstenums", "false")
        assert(!o.preserveConstEnums)
        assert(o.preserveConstEnumsExplicitlyFalse)
    }

    // ── run 2 arm pins ───────────────────────────────────────────────────────

    @Test
    fun `run 2 - strictNullChecks false records the explicit false`() {
        val o = applyDirective(base, "strictnullchecks", "false")
        assert(!o.strictNullChecks)
        assert(o.strictNullChecksExplicitlyFalse)
    }

    @Test
    fun `run 2 - lib splits on commas and trims`() {
        assert(applyDirective(base, "lib", "es5, dom ").lib == listOf("es5", "dom"))
    }

    @Test
    fun `run 2 - noUnusedLocals and jsxFactory`() {
        assert(applyDirective(base, "nounusedlocals", "true").noUnusedLocals)
        assert(applyDirective(base, "jsxfactory", " h ").jsxFactory == "h")
    }

    // ── run 3 arm pins ───────────────────────────────────────────────────────

    @Test
    fun `run 3 - checkJs and isolatedModules`() {
        assert(applyDirective(base, "checkjs", "true").checkJs)
        assert(applyDirective(base, "isolatedmodules", "true").isolatedModules)
    }

    @Test
    fun `run 3 - out is kept apart from outFile`() {
        // 'out' is a removed option (TS5102) and deliberately does NOT set outFile.
        val o = applyDirective(base, "out", "bundle.js")
        assert(o.out == "bundle.js")
        assert(o.outFile == null)
    }

    @Test
    fun `run 3 - the last arm of run 3 still answers`() {
        assert(applyDirective(base, "nofallthroughcasesinswitch", "true").noFallthroughCasesInSwitch)
    }

    // ── run 4 arm pins ───────────────────────────────────────────────────────

    @Test
    fun `run 4 - the first arm of run 4 still answers`() {
        assert(applyDirective(base, "noresolve", "true").noResolve)
    }

    @Test
    fun `run 4 - simulatedTypeScriptVersion and composite`() {
        assert(applyDirective(base, "typescriptversion", " 5.0 ").simulatedTypeScriptVersion == "5.0")
        assert(applyDirective(base, "composite", "true").composite)
    }

    @Test
    fun `run 4 - the very last arm still answers`() {
        assert(applyDirective(base, "capturesuggestions", "true").captureSuggestions)
    }

    // ── the boolValue seam ───────────────────────────────────────────────────

    @Test
    fun `boolValue seam - the entry lowercases the value once for every run`() {
        // The entry computes `value.lowercase() == "true"` and passes it down. A
        // run that recomputed `value == "true"` for itself would fail here.
        assert(applyDirective(base, "strict", "TRUE").strict)
        assert(applyDirective(base, "nounusedlocals", "True").noUnusedLocals)
        assert(applyDirective(base, "checkjs", "TRUE").checkJs)
        assert(applyDirective(base, "composite", "True").composite)
        // ... and anything that is not "true" is false, per HEAD.
        assert(!applyDirective(base, "strict", "yes").strict)
        assert(applyDirective(base, "strict", "yes").strictExplicitlyFalse)
    }

    // ── the wiring, end to end ───────────────────────────────────────────────

    @Test
    fun `a directive comment still reaches the checker through applyDirective`() {
        val d = diagnose(
            """
            export function f(): number {
                const neverRead = 1;
                return 2;
            }
            """,
            directives = "// @strict: true\n// @noUnusedLocals: true",
        )
        assert(d.count { it.code == 6133 } == 1)
    }
}
