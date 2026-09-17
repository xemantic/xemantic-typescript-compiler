/*
 * Copyright 2026 Kazik Pogoda
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
 * (LEGACY.1)(g) — `baseUrl` is REMOVED in TypeScript 7, and what that does and does not mean.
 *
 * WHAT TSGO DOES, verified against `typescript-go-repo` at tag `typescript/v7.0.2` and, for
 * every rendered byte, by running `tools/tsgo-7.0.2/lib/tsc` on a scratch project:
 *
 *  * it honours `baseUrl` NOWHERE. The only non-test reads of `options.BaseUrl` left in
 *    `internal/` are the TS5102 diagnostic (`compiler/program.go:822-833`) and the
 *    `${configDir}` template substitution at parse time (`tsoptions/tsconfigparsing.go:1600`);
 *    `module/resolver.go:1231` is the COMMENT `// No more tryLoadModuleUsingBaseUrl.` where
 *    its resolution leg used to be, and `modulespecifiers/specifiers.go`'s `relativeToBaseUrl`
 *    is a LOCAL named after the concept — it is `baseDirectory`-relative, for `paths` matching.
 *    TS6106 (`'baseUrl' option is set to …, using this value to resolve …`) exists in the
 *    generated message table and is emitted by nothing.
 *  * `paths` SURVIVES, and is the migration target `baseUrl` was removed in favour of — the
 *    TS5102 row carries a COMPUTED suggestion naming it.
 *  * TS5090 (`Non-relative paths are not allowed…`) is NOT gated on `baseUrl`
 *    (`program.go:995`) and exempts an ABSOLUTE substitution as well as a relative one.
 *
 * WHAT THIS COMPILER DOES ABOUT THE ROW ITSELF is unchanged at the `"6.0"` default of
 * `simulatedVersion`: TS5101 at the KEY with the TypeScript 6 migration URL. The TS5102 form
 * — code, wording and computed chain — is reachable only through `@typeScriptVersion: 7.0`
 * until a later round moves that default, which is why every 7.0 pin below names it.
 *
 * TWO THINGS THAT ARE NOT PINNED HERE, because they are pre-existing and out of (g)'s scope:
 *  * tsgo's CLI SHORT-CIRCUITS after a removed-option config error — with `baseUrl` (or
 *    `outFile`) set it prints the TS5102 row and NOT ONE semantic diagnostic, even an obvious
 *    `const bad: number = "s"` in an untouched file (measured both ways). Ours reports the
 *    row and carries on. That is the config-error twin of the syntactic short-circuit
 *    CLAUDE.md already records, and it belongs to whoever takes the CLI's diagnostic gating.
 *  * TS5090 fires only through the CORPUS HARNESS here: `applyTsconfigOptions` raises it and
 *    `TsConfigLoader` (the `-project` path) reads positions only, so a real project setting
 *    a non-relative substitution is silent where tsgo reports. Hence every TS5090 pin below
 *    is a `diagnose()` fixture with an `@Filename: tsconfig.json`, which is the only shape
 *    that reaches the code — a `-project` pin would be vacuous by construction.
 *
 * ABLATION, 2026-09-17, one injected mistake per arm, each arm diffed against its OWN
 * snapshot and the tree rebuilt after the last restore:
 *  * a1 restore `result.baseUrl == null` on the TS5090 block — **1 RED of 15**, exactly
 *    `a non-relative substitution is reported even when baseUrl is set`.
 *  * a2 restore `!sub.startsWith("./") && !sub.startsWith("../")` as the whole test —
 *    **4 RED**, exactly the POSIX-root, DOS-drive, bare-dot and backslash exemptions.
 *  * a3 revert the TS5102 chain split (TS5102 takes TS5101's chain again) — **3 RED**,
 *    exactly the three 7.0 chain pins. The 6.0 control is green on both arms BY DESIGN:
 *    that is what makes it evidence this round moves nothing at the shipped default.
 *  * a4 restore `NameResolver`'s deleted `baseUrl` candidate leg — **0 RED**, and it is a
 *    MEASURED REDUNDANT guard rather than a blind pin set, because two further arms settle
 *    which: a4b (drop the `fileBase.endsWith("/${'$'}baseName")` fallback, leg still deleted)
 *    is **1 RED** — so the fixture below really does reach that resolution — and a4c (drop
 *    the fallback AND restore the leg) is **0 RED** — so the restored leg is reached and
 *    resolves the same file. Redundant, not unreachable.
 *  * a5 a comment-only edit — **0 RED**, the both-green control that makes a4's zero
 *    attributable rather than a dead arm.
 */
class BaseUrlRemovedTest {

    // ── TS5090: the `baseUrl` gate is gone and the ABSOLUTE arm arrived ───────────────────
    //
    // Ours gated the whole block on `result.baseUrl == null` and tested a substitution with
    // `!sub.startsWith("./") && !sub.startsWith("../")`. BOTH had to move together: dropping
    // the gate alone ENLARGES the population that reaches a test which is a false positive on
    // four shapes tsgo accepts, so the exemption pins below are what make the first change safe.

    private fun pathsConfig(entry: String, extra: String = "") = diagnose(
        """
        // @Filename: tsconfig.json
        {
          "compilerOptions": {
              $extra"paths": {
                $entry
              }
          }
        }

        // @filename: src/main.ts
        export const x = 1;
        """,
        directives = "// @module: commonjs",
    )

    /**
     * The dropped gate. tsgo reports both rows for this configuration — measured:
     * `tsconfig.json(3,5): error TS5102` for the `baseUrl` key AND
     * `tsconfig.json(4,25): error TS5090` for the substitution.
     */
    @Test
    fun `a non-relative substitution is reported even when baseUrl is set`() {
        val d = pathsConfig(""""@blah": ["blah"]""", extra = """"baseUrl": ".",${"\n              "}""")
        val row = d.single { it.code == 5090 }
        assert(row.message == "Non-relative paths are not allowed. Did you forget a leading './'?")
    }

    /** Control: the same substitution without `baseUrl` was already reported, and still is. */
    @Test
    fun `control - a non-relative substitution is reported without baseUrl`() {
        assert(pathsConfig(""""@blah": ["blah"]""").count { it.code == 5090 } == 1)
    }

    /** tsgo is silent: `PathIsAbsolute` is `GetEncodedRootLength(path) != 0`, and `/` is a POSIX root. */
    @Test
    fun `a POSIX-rooted substitution is not a non-relative path`() {
        assert(pathsConfig(""""@a/x": ["/abs/x/y"]""").none { it.code == 5090 })
    }

    /** tsgo is silent: `GetEncodedRootLength` counts a DOS drive, and `baseUrl - c colon slash root` is a real corpus shape. */
    @Test
    fun `a DOS-drive substitution is not a non-relative path`() {
        assert(pathsConfig(""""@a/x": ["c:/win/x/y"]""").none { it.code == 5090 })
    }

    /** tsgo is silent: `PathIsRelative` accepts a BARE `.` and `..`, which a `startsWith` test misses. */
    @Test
    fun `a bare dot and a bare dot-dot are relative`() {
        assert(pathsConfig(""""@dot": ["."], "@dotdot": [".."]""").none { it.code == 5090 })
    }

    /** tsgo is silent: `PathIsRelative` accepts the backslash spellings too. */
    @Test
    fun `a backslash-relative substitution is relative`() {
        assert(pathsConfig(""""@a/x": [".\\back\\x"]""").none { it.code == 5090 })
    }

    /** Negative control: the rule still discriminates — a plain non-relative name reports. */
    @Test
    fun `negative control - the exemptions did not disarm the rule`() {
        val d = pathsConfig(""""@rel": ["./ok"], "@bad": ["nope"]""")
        assert(d.count { it.code == 5090 } == 1)
    }

    // ── TS5102's COMPUTED chain, and the 6.0 branch that must not move ────────────────────
    //
    // tsgo, `program.go:822-833`, rendered through `Use_0_instead` (TS5106, category Message):
    // the relative path from the config file to the absolutized `baseUrl`, forced to start
    // `./` or `../`, joined with a star segment, JSON-quoted inside a `paths` literal.

    private fun baseUrlRow(baseUrl: String, version: String) = diagnose(
        """
        // @Filename: tsconfig.json
        {
          "compilerOptions": {
              "baseUrl": "$baseUrl"
          }
        }

        // @filename: src/main.ts
        export const x = 1;
        """,
        directives = "// @module: commonjs\n// @typeScriptVersion: $version",
    ).single { it.code == 5101 || it.code == 5102 }

    /** tsgo, measured: a `Use '…' instead.` line under the TS5102 row, whose bytes are asserted below. */
    @Test
    fun `at 7 0 baseUrl is tsgo's TS5102 carrying the computed paths suggestion`() {
        val row = baseUrlRow("./src", "7.0")
        assert(row.code == 5102)
        assert(row.message == "Option 'baseUrl' has been removed. Please remove it from your configuration.")
        assert(row.messageChain == listOf("""  Use '"paths": {"*": ["./src/*"]}' instead."""))
    }

    /**
     * The three values whose rendering is not the written text, measured one per row:
     * `"."` and `""` both answer the config directory (tsgo tests `BaseUrl != ""` AFTER
     * absolutization, so an EMPTY `baseUrl` still reports), and `".."` is not prefixed
     * `../` so it takes the `./` the algorithm prepends.
     */
    @Test
    fun `the suggestion is computed and not the written text`() {
        assert(baseUrlRow(".", "7.0").messageChain == listOf("""  Use '"paths": {"*": ["./*"]}' instead."""))
        assert(baseUrlRow("", "7.0").messageChain == listOf("""  Use '"paths": {"*": ["./*"]}' instead."""))
        assert(baseUrlRow("..", "7.0").messageChain == listOf("""  Use '"paths": {"*": ["./../*"]}' instead."""))
        assert(baseUrlRow("../sibling", "7.0").messageChain == listOf("""  Use '"paths": {"*": ["../sibling/*"]}' instead."""))
        assert(baseUrlRow("src", "7.0").messageChain == listOf("""  Use '"paths": {"*": ["./src/*"]}' instead."""))
        assert(baseUrlRow("./src/", "7.0").messageChain == listOf("""  Use '"paths": {"*": ["./src/*"]}' instead."""))
    }

    /**
     * tsgo guards the chain on `configFilePath() != ""`. Measured with `--baseUrl ./src` and
     * no config file: the TS5102 row prints ALONE, file-less, with no second line.
     */
    @Test
    fun `without a config file the removed row carries no chain`() {
        val row = diagnose(
            "export const x = 1;",
            directives = "// @typeScriptVersion: 7.0\n// @baseUrl: ./src",
        ).single { it.code == 5101 || it.code == 5102 }
        assert(row.code == 5102)
        assert(row.messageChain.isEmpty())
        assert(row.fileName == null)
    }

    /**
     * Control, and the reason `removedMessageChain` defaults to `messageChain`: at the `"6.0"`
     * default this round moves NOTHING. The row stays TS5101 with the TypeScript 6 migration
     * URL — green on both arms of the chain ablation by design.
     */
    @Test
    fun `control - at the 6 0 default baseUrl is still TS5101 with the migration URL`() {
        val row = baseUrlRow("./src", "6.0")
        assert(row.code == 5101)
        assert(row.message == "Option 'baseUrl' is deprecated and will stop functioning in " +
            "TypeScript 7.0. Specify compilerOption '\"ignoreDeprecations\": \"6.0\"' to silence this error.")
        assert(row.messageChain == listOf("  Visit https://aka.ms/ts6 for migration information."))
    }

    // ── the helper itself, over the values a fixture cannot reach ─────────────────────────

    /**
     * An ABSOLUTE `baseUrl` needs the two-sided walk from the config directory, which only a
     * real project has (the harness's config path is a bare `tsconfig.json`). tsgo, measured
     * on a config six directories deep with `"baseUrl": "/abs/path"`, answers
     * `../../../../../../abs/path/` + a star segment — the same walk this asserts.
     */
    @Test
    fun `an absolute baseUrl walks up from the config directory`() {
        assert(baseUrlPathsSuggestion("/abs/path", "/a/b/c/tsconfig.json") ==
            listOf("""  Use '"paths": {"*": ["../../../abs/path/*"]}' instead."""))
        assert(baseUrlPathsSuggestion("/a/b/src", "/a/b/tsconfig.json") ==
            listOf("""  Use '"paths": {"*": ["./src/*"]}' instead."""))
        assert(baseUrlPathsSuggestion("/a/b", "/a/b/tsconfig.json") ==
            listOf("""  Use '"paths": {"*": ["./*"]}' instead."""))
    }

    // ── the deleted RESOLUTION legs, and what is left resolving instead ──────────────────

    /**
     * The shape `NameResolver`'s deleted `baseUrl` candidates were FOR: a bare specifier whose
     * target sits under the `baseUrl` directory. It resolves — `cc` is `number`, so the wrong
     * annotation reports — and it resolved on the pre-change binary too, through the
     * `fileBase.endsWith("/$baseName")` fallback that sits below where those candidates were.
     *
     * This pin exists to make arm a4's zero MEASURED rather than blind: without a fixture that
     * reaches `computeModuleSpecifier` with a `baseUrl` set, "restoring the leg changes
     * nothing" would only mean the pins never went near it.
     */
    @Test
    fun `a bare specifier under the baseUrl directory still resolves without the baseUrl leg`() {
        val d = diagnose(
            """
            // @Filename: root/src/defs/cc.ts
            export const cc: number = 1;

            // @Filename: root/main.ts
            import { cc } from "defs/cc";
            export const v: string = cc;
            """,
            directives = "// @module: commonjs\n// @baseUrl: root/src",
        )
        assert(d.count { it.code == 2322 } == 1)
        assert(d.none { it.code == 2307 })
    }

    /**
     * `paths` SURVIVES: `resolvePathsMapping` lost its `baseUrl` ANCHOR, not its mapping.
     * That is the single most important "do not over-delete" line of (LEGACY.1)(g), and the
     * change is a provable no-op for a project with no `baseUrl` — the old anchor's `when`
     * answered `tsconfigDir` (or `""`) on every branch reachable with `baseUrl` null, which
     * is the only shape left once the embedded-`baseUrl` cases are skipped.
     *
     * `residue -` because the answer asserted here is NOT tsgo's. The emit-ORDER edge a
     * `paths` mapping should contribute does not fire in the corpus harness: a RELATIVE
     * import reorders (`[lib.js, amain.js]`, measured) and the `paths`-mapped spelling of the
     * same edge does not. That is PRE-EXISTING, not this round's — measured on HEAD's own
     * `TypeScriptCompiler.kt` with a fresh results directory, byte-for-byte the same answer —
     * and it is recorded rather than fixed because `resolvePathsMapping`'s one consumer is
     * the emit order, whose gate is the corpus emit channel (5,646 subtests, 0 mismatches
     * across this round). Whoever closes it re-points this pin to `[lib.js, amain.js]`.
     */
    @Test
    fun `residue - a paths-mapped import does not reorder emit where a relative one does`() {
        fun order(spec: String, config: String) = TypeScriptCompiler().compile(
            """
            // @module: commonjs
            $config
            // @Filename: amain.ts
            import { q } from "$spec";
            export const r = q;

            // @Filename: zdep/lib.ts
            export const q = 1;
            """.trimIndent(),
            "amain.ts",
        ).jsOutputs.map { it.first }

        // the control: the emit-order mechanism IS live, so the residue below is about the
        // `paths` edge and not about ordering being absent.
        assert(order("./zdep/lib", "") == listOf("lib.js", "amain.js"))
        val pathsConfigured = """
            // @Filename: tsconfig.json
            {
              "compilerOptions": { "paths": { "@z/*": ["zdep/*"] } }
            }
        """.trimIndent()
        assert(order("@z/lib", pathsConfigured) == listOf("amain.js", "lib.js"))
    }

    /** No config file path, no chain — the branch `configFilePath() != ""` guards in tsgo. */
    @Test
    fun `control - the suggestion helper answers nothing without a config file`() {
        assert(baseUrlPathsSuggestion("./src", null).isEmpty())
    }
}
