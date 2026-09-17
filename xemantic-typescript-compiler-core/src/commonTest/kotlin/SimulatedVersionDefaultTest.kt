/*
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (P18.133) The shipped default of `TypeScriptCompiler.compileParsedCore`'s `simulatedVersion`,
 * which selects between the TypeScript 6 deprecation ladder (TS5101 at an option's KEY, TS5107 at
 * its VALUE) and TypeScript 7's removed-option answer (TS5102 / TS5108). It is `"7.0"`.
 *
 * **Why the default moved.** TypeScript 7 has no "deprecated, will stop functioning" notion at
 * all. Its one emitter is `createRemovedOptionDiagnostic` (`internal/compiler/program.go:803`),
 * which answers TS5102 when the option carries no value and TS5108 when it does;
 * `Option_0_is_deprecated_...` (5101) and `Option_0_1_is_deprecated_...` (5107) survive only in
 * `diagnostics_generated.go` and are constructed NOWHERE — zero references outside that file —
 * and across tsgo's whole baseline corpus the two codes occur only on lines its diffs DELETE
 * (60 and 2 respectively; zero added). The independent argument is that (LEGACY.1)(a)-(k) had
 * already deleted every one of these options' behaviour, so at the old `"6.0"` default this
 * compiler told a user an option "will stop functioning in TypeScript 7.0" and offered
 * `ignoreDeprecations` to silence it, while the option was already inert and silencing restored
 * nothing.
 *
 * **What this class pins.** The default itself (no version directive anywhere below except in the
 * controls), the fact that an explicit `@typeScriptVersion` still overrides it, the two anchor
 * ladders, and `ignoreDeprecations`' reach at each version. Every expected row here was measured
 * on `tools/tsgo-7.0.2/lib/tsc` over a tsconfig whose lines are character-for-character the
 * [everyRemovedOption] fixture below, so the line/column/width assertions are tsgo's own:
 *
 *     tsconfig.json(3,9):  error TS5102: Option 'baseUrl' has been removed. Please remove it from your configuration.
 *       Use '"paths" ... ' instead.
 *     tsconfig.json(4,9):  error TS5102: Option 'outFile' has been removed. Please remove it from your configuration.
 *     tsconfig.json(5,9):  error TS5102: Option 'downlevelIteration' has been removed. Please remove it from your configuration.
 *     tsconfig.json(6,25): error TS5108: Option 'alwaysStrict=false' has been removed. Please remove it from your configuration.
 *     tsconfig.json(7,28): error TS5108: Option 'esModuleInterop=false' has been removed. Please remove it from your configuration.
 *     tsconfig.json(8,41): error TS5108: Option 'allowSyntheticDefaultImports=false' has been removed. Please remove it from your configuration.
 *     tsconfig.json(9,19): error TS5108: Option 'target=ES5' has been removed. Please remove it from your configuration.
 *     tsconfig.json(10,29): error TS5108: Option 'moduleResolution=Classic' has been removed. Please remove it from your configuration.
 *
 * (The `baseUrl` chain's second line is elided above: its literal text contains a comment opener
 * that would nest inside this KDoc. It is asserted verbatim in the pin that owns it.)
 *
 * **Why the controls are not optional.** Without them the new default is pinned by nothing an
 * ablation could separate: restoring `?: "6.0"` would redden the default pins, but nothing would
 * say the explicit ladder still resolves, which is what the seven corpus cases carrying a
 * `@typeScriptVersion` directive (3x `5.0`, 1x `5.5`, 3x `6.0`) depend on. That is (INC.16)'s law
 * — a mode every pin installs is a default pinned by nothing — in the other direction.
 *
 * Ablations, 2026-09-17, each injected alone against a snapshot and restored by `cmp`:
 *
 *  * **a1, restore `?: "6.0"` in `TypeScriptCompiler.kt` — 43 RED**, of which **5 of the 8 here**:
 *    the three default pins, `ignoreDeprecations does not silence …`, and
 *    `control - an explicit typeScriptVersion 7 0 matches the default` (it compares the 7.0 arm
 *    against the default, so the default moving reddens it). The two `control - … 6 0 …` pins
 *    are GREEN on both arms BY DESIGN — that is what they are for, and an arm that reddened
 *    them would mean the explicit ladder had been deleted rather than the default moved. The
 *    other 38 are the re-pointed pins in ten core classes and six `-project` ones.
 *  * **a3, restore the TS5103 emission — exactly 1 RED**, the invalid-`ignoreDeprecations` pin
 *    below, which a1 cannot reach at all: that pin is about the retirement, not the default.
 *
 * (a2 belongs to `CompilerTestSupport.DOWNLEVEL_ES5` and is recorded there: dropping the
 * `@typeScriptVersion: 6.0` this round added to it is 27 RED across the three target-gate
 * classes, i.e. that directive is load-bearing and not tidying.)
 */
class SimulatedVersionDefaultTest {

    /**
     * Eight of the thirteen removed-option families in one config, chosen so that both anchor
     * ladders and both message templates are exercised: three KEY-anchored options with no value
     * in the message, and five VALUE-anchored ones whose value is part of it.
     *
     * `module=AMD` and its two siblings are deliberately absent — with the default module
     * resolution tsgo adds a TS5095 row beside them, which would make this fixture a pin about
     * that rule too. `RemovedModuleKindsTest` owns them.
     */
    private val everyRemovedOption = """
        // @Filename: /foo/tsconfig.json
        {
            "compilerOptions": {
                "baseUrl": "./src",
                "outFile": "./bundle.js",
                "downlevelIteration": true,
                "alwaysStrict": false,
                "esModuleInterop": false,
                "allowSyntheticDefaultImports": false,
                "target": "ES5",
                "moduleResolution": "classic"
            }
        }

        // @filename: /foo/src/a.ts
        export const a = 1;
        """

    private fun rows(directives: String) =
        diagnose(everyRemovedOption, directives = directives)
            .filter { it.code in setOf(5101, 5102, 5107, 5108) }
            .sortedWith(compareBy({ it.line ?: 0 }, { it.character ?: 0 }))

    /** `(code, message, line, character, length)` — everything a baseline line would render. */
    private fun shape(directives: String) = rows(directives).map {
        listOf(it.code.toString(), it.message, "${it.line}", "${it.character}", "${it.length}")
            .joinToString("|")
    }

    // ── the default ────────────────────────────────────────────────────────────────────────

    /** No version directive anywhere: the whole answer is tsgo's, byte for byte. */
    @Test
    fun `the shipped default answers every removed option exactly as tsgo does`() {
        assert(
            shape("// @strict: true") == listOf(
                "5102|Option 'baseUrl' has been removed. Please remove it from your configuration.|3|9|9",
                "5102|Option 'outFile' has been removed. Please remove it from your configuration.|4|9|9",
                "5102|Option 'downlevelIteration' has been removed. Please remove it from your configuration.|5|9|20",
                "5108|Option 'alwaysStrict=false' has been removed. Please remove it from your configuration.|6|25|5",
                "5108|Option 'esModuleInterop=false' has been removed. Please remove it from your configuration.|7|28|5",
                "5108|Option 'allowSyntheticDefaultImports=false' has been removed. Please remove it from your configuration.|8|41|5",
                "5108|Option 'target=ES5' has been removed. Please remove it from your configuration.|9|19|5",
                "5108|Option 'moduleResolution=Classic' has been removed. Please remove it from your configuration.|10|29|9",
            )
        )
    }

    /**
     * The anchor split, stated as its own claim rather than left implicit in the table above:
     * TS5102 squiggles the quoted KEY and TS5108 the VALUE. tsgo decides it with one expression,
     * `createDiagnosticForOption(value == "", ...)` — an option whose removal message names no
     * value is reported on the key, one that names a value on the value.
     */
    @Test
    fun `TS5102 anchors at the key and TS5108 at the value`() {
        val d = rows("// @strict: true")
        val keyAnchored = d.filter { it.code == 5102 }
        val valueAnchored = d.filter { it.code == 5108 }
        assert(keyAnchored.map { it.character } == listOf(9, 9, 9))
        assert(keyAnchored.map { it.length } == listOf(9, 9, 20))
        assert(valueAnchored.map { it.character } == listOf(25, 28, 41, 19, 29))
        assert(valueAnchored.map { it.length } == listOf(5, 5, 5, 5, 9))
        assert(keyAnchored.none { it.message.contains("=") })
        assert(valueAnchored.all { it.message.contains("=") })
    }

    /**
     * `baseUrl` is the one option whose removal carries a chain, and the chain is COMPUTED from
     * the config path rather than fixed ((LEGACY.1)(g), tsgo's `program.go:822-833` rendered
     * through `Use_0_instead`, TS5106). (P18.132) built it; this pin is what makes it the
     * SHIPPED rendering rather than one reachable only under a directive.
     */
    @Test
    fun `at the default baseUrl carries tsgo's computed paths suggestion`() {
        val row = rows("// @strict: true").single { it.message.contains("'baseUrl'") }
        assert(row.code == 5102)
        assert(row.messageChain == listOf("""  Use '"paths": {"*": ["./src/*"]}' instead."""))
        assert(rows("// @strict: true").filter { it.messageChain.isNotEmpty() }.size == 1)
    }

    // ── an explicit @typeScriptVersion still wins ──────────────────────────────────────────

    /**
     * Control. An explicit version below `7.0` selects the TypeScript 6 ladder for every one of
     * the eight, at the SAME anchors — only the code and the sentence move. This is what the
     * corpus cases carrying a `@typeScriptVersion` directive rely on, and what an ablation
     * restoring the old default cannot redden.
     */
    @Test
    fun `control - an explicit typeScriptVersion 6 0 still selects the deprecation ladder`() {
        val d = rows("// @strict: true\n// @typeScriptVersion: 6.0")
        assert(d.map { it.code } == listOf(5101, 5101, 5101, 5107, 5107, 5107, 5107, 5107))
        assert(d.map { it.character } == listOf(9, 9, 9, 25, 28, 41, 19, 29))
        assert(d.map { it.length } == listOf(9, 9, 20, 5, 5, 5, 5, 9))
        assert(d.all { it.message.contains("is deprecated and will stop functioning in TypeScript 7.0") })
        assert(
            d.first().message ==
                "Option 'baseUrl' is deprecated and will stop functioning in TypeScript 7.0. " +
                "Specify compilerOption '\"ignoreDeprecations\": \"6.0\"' to silence this error."
        )
    }

    /** Control: the boundary is `>=`, so an explicit `7.0` is the removed ladder, as the default is. */
    @Test
    fun `control - an explicit typeScriptVersion 7 0 matches the default`() {
        assert(shape("// @strict: true\n// @typeScriptVersion: 7.0") == shape("// @strict: true"))
    }

    // ── ignoreDeprecations' reach ──────────────────────────────────────────────────────────

    /**
     * At the default it silences NOTHING. Both emitters return on the removed branch, which sits
     * above `isDeprecationSuppressed`, and that is tsgo's behaviour rather than an artefact:
     * `ignoreDeprecations` is declared in its option table and parsed into the options struct
     * (`tsoptions/parsinghelpers.go:287`) and read by nothing at all. Measured — tsgo prints the
     * same rows for `"5.0"`, `"6.0"`, `"7.0"` and an invalid value alike.
     */
    @Test
    fun `ignoreDeprecations does not silence a removed option at the default`() {
        for (value in listOf("5.0", "6.0")) {
            assert(shape("// @strict: true\n// @ignoreDeprecations: $value") == shape("// @strict: true"))
        }
    }

    /** Control: on the explicit-6.0 ladder it still silences all eight, exactly as tsc 6 did. */
    @Test
    fun `control - ignoreDeprecations 6 0 still silences the whole explicit 6 0 ladder`() {
        assert(rows("// @strict: true\n// @typeScriptVersion: 6.0\n// @ignoreDeprecations: 6.0").isEmpty())
    }

    /**
     * (P18.133) An INVALID `ignoreDeprecations` value is accepted and silences nothing, and no
     * TS5103 is reported. TypeScript 7 has no emitter for that code — its message exists in
     * `diagnostics_generated.go` and is constructed nowhere, and tsgo's baselines carry it zero
     * times. Measured: a config whose only option is an invalid `ignoreDeprecations` produces no
     * output whatsoever from tsgo.
     *
     * The second half is why the validity FILTER survives the diagnostic's retirement: the
     * suppression test is a lexicographic compare, under which an arbitrary word sorts above
     * `"6.0"`, so an unfiltered invalid value would begin silencing the explicit-6.0 ladder.
     */
    @Test
    fun `an invalid ignoreDeprecations is accepted and silences nothing and is not TS5103`() {
        val atDefault = diagnose(everyRemovedOption, directives = "// @strict: true\n// @ignoreDeprecations: banana")
        assert(atDefault.none { it.code == 5103 })
        assert(shape("// @strict: true\n// @ignoreDeprecations: banana") == shape("// @strict: true"))
        val atSix = diagnose(
            everyRemovedOption,
            directives = "// @strict: true\n// @typeScriptVersion: 6.0\n// @ignoreDeprecations: banana",
        )
        assert(atSix.none { it.code == 5103 })
        assert(atSix.count { it.code == 5101 || it.code == 5107 } == 8)
    }
}
