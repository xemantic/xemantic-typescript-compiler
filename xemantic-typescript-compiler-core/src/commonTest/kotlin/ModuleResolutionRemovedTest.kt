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
 * (LEGACY.1)(e) `moduleResolution: classic` / `node10` (and the alias `node`) are REMOVED
 * values in TypeScript 7, and there is no classic or node10 RESOLUTION there at all: tsgo
 * 7.0.2's `GetModuleResolutionKind()` (`compileroptions.go:223-237`) folds an unset value
 * and both removed ones into the kind derived from the emit module kind — `Node16` for the
 * node16/18/20 module kinds, `NodeNext` for `nodenext`, `Bundler` for everything else,
 * `commonjs` and an unset `module` included. `program.go:854/870` report the removed values
 * (TS5108 `moduleResolution=Classic` / `moduleResolution=node10`) and then ignore them.
 *
 * **Every expected value here is tsgo 7.0.2's**, measured 2026-09-15: a 42-cell matrix
 * (`moduleResolution` ∈ {unset, classic, node, node10, bundler, node16, nodenext} ×
 * `module` ∈ {unset, commonjs, esnext, amd, system, nodenext}) over eight specifier shapes,
 * read through `--traceResolution` and the LSP's `textDocument/diagnostic` (the CLI stops
 * at the option row for a removed value), plus per-shape probes for the arms below. In every
 * `module` column the classic / node / node10 cells are byte-identical to the unset cell on
 * every resolved file and every checker row, bar the TS5108 row.
 *
 * What this compiler did before: FIVE string-typed copies of a tsc-6 derivation answering
 * `"classic"` for `module: none/amd/umd/system` and `"node10"` otherwise, a classic-only
 * TS2792/TS2307 arm, TS5070, a "root `tslib.d.ts` is found under classic" rule, a
 * classic-excluding gate on the `import("fs")`-type walker, and two option checks reading
 * the RAW value. Now ONE derivation, [CompilerOptions.effectiveModuleResolution].
 *
 * The harness path is the instrument for the deleted CHECKER arms (a `-project` build
 * resolves every specifier through the crawl, so the multi-file walkers' unresolved-import
 * arms never fire there); [ProjectModuleResolutionRemovedTest] in the `-project` module
 * pins the matrix's resolved files and value-anchored option rows.
 */
class ModuleResolutionRemovedTest {

    private fun classicTwoFile(importLine: String, extra: String = "", directives: String = "") = diagnose(
        """
        // @Filename: /a.ts
        $importLine

        // @Filename: /b.ts
        export const q = 1;
        $extra
        """,
        directives = "// @strict: true\n// @module: commonjs\n// @moduleResolution: classic" + (if (directives.isEmpty()) "" else "\n$directives"),
    )

    // ── the classic arm of the multi-file import walker (TS2792 → TS2307, both Bundler) ──

    /** tsgo (unset twin, the licence): `a.ts(1,19): error TS2307: Cannot find module './nope' …`. */
    @Test
    fun `classic - a missing relative import is TS2307 not the classic TS2792`() {
        val d = classicTwoFile("""import { x } from "./nope"; x;""")
        val rows = d.filter { it.code == 2307 }
        assert(rows.size == 1)
        assert(rows[0].message == "Cannot find module './nope' or its corresponding type declarations.")
        assert(d.none { it.code == 2792 })
    }

    /** tsgo resolves `./dir` to `dir/index.ts` in every cell; the classic arm's strict-relative probe could not. */
    @Test
    fun `classic - a directory import resolves to its index file`() {
        val d = diagnose(
            """
            // @Filename: /a.ts
            import { d } from "./dir"; const n: number = d;

            // @Filename: /dir/index.ts
            export const d = 1;
            """,
            directives = "// @strict: true\n// @module: commonjs\n// @moduleResolution: classic",
        )
        assert(d.none { it.code == 2307 || it.code == 2792 })
    }

    /** tsgo resolves `./dir` to `dir/index.ts` in every ES cell too; the ES-kind arm's strict probe read a false TS2307 for it. */
    @Test
    fun `a directory import resolves to its index file under esnext`() {
        for (resolution in listOf("", "\n// @moduleResolution: classic", "\n// @moduleResolution: bundler")) {
            val d = diagnose(
                """
                // @Filename: /a.ts
                import { d } from "./dir"; const n: number = d;

                // @Filename: /dir/index.ts
                export const d = 1;
                """,
                directives = "// @strict: true\n// @module: esnext$resolution",
            )
            assert(d.none { it.code == 2307 || it.code == 2792 })
        }
    }

    /** tsgo (unset twin): `error TS2307: Cannot find module 'server'` — a bare specifier never names a sibling file. */
    @Test
    fun `classic - a bare single-segment specifier naming a sibling file is TS2307`() {
        val d = diagnose(
            """
            // @Filename: /a.ts
            import { x } from "server"; x;

            // @Filename: /server.ts
            export const x = 1;
            """,
            directives = "// @strict: true\n// @module: commonjs\n// @moduleResolution: classic",
        )
        assert(d.count { it.code == 2307 } == 1)
        assert(d.none { it.code == 2792 })
    }

    // ── TS5070 has no emitter in tsgo ─────────────────────────────────────────────────

    @Test
    fun `classic - resolveJsonModule no longer reports TS5070`() {
        val d = diagnose(
            """
            // @Filename: /a.ts
            import j from "./data.json"; j;

            // @Filename: /data.json
            {"k": 1}
            """,
            directives = "// @strict: true\n// @module: commonjs\n// @moduleResolution: classic\n// @resolveJsonModule: true",
        )
        assert(d.none { it.code == 5070 })
    }

    @Test
    fun `a module amd program with no moduleResolution no longer reports TS5070`() {
        val d = diagnose("export const a = 1;", directives = "// @strict: true\n// @module: amd\n// @resolveJsonModule: true")
        assert(d.none { it.code == 5070 })
    }

    // ── the removed-option rows: tsgo's spelling ──────────────────────────────────────

    /**
     * (P18.133) tsgo spells the value `Classic`, not the written `classic`. Measured:
     *
     *     tsconfig.json(1,44): error TS5108: Option 'moduleResolution=Classic' has been removed. Please remove it from your configuration.
     */
    @Test
    fun `classic reports its removed value as tsgo spells it - Classic`() {
        val d = diagnose("export const a = 1;", directives = "// @strict: true\n// @module: commonjs\n// @moduleResolution: classic")
        val row = d.singleOrNull { it.code == 5108 }
        assert(row != null)
        assert(row.message.contains("'moduleResolution=Classic'"))
        assert(d.none { it.code == 5107 })
    }

    /** Control: an EXPLICIT `@typeScriptVersion` below `7.0` still selects the TS5107 ladder. */
    @Test
    fun `control - an explicit typeScriptVersion 6 0 keeps the classic row on the TS5107 ladder`() {
        val d = diagnose("export const a = 1;", directives = "// @strict: true\n// @module: commonjs\n// @moduleResolution: classic\n// @typeScriptVersion: 6.0")
        val row = d.singleOrNull { it.code == 5107 }
        assert(row != null)
        assert(row.message.contains("'moduleResolution=Classic'"))
        assert(d.none { it.code == 5108 })
    }

    /** tsgo: `error TS5108: Option 'moduleResolution=Classic' has been removed. Please remove it from your configuration.` */
    @Test
    fun `under typeScriptVersion 7 0 the classic row is tsgo's TS5108 byte for byte`() {
        val d = diagnose("export const a = 1;", directives = "// @strict: true\n// @module: commonjs\n// @moduleResolution: classic\n// @typeScriptVersion: 7.0")
        val row = d.singleOrNull { it.code == 5108 }
        assert(row != null)
        assert(row.message == "Option 'moduleResolution=Classic' has been removed. Please remove it from your configuration.")
        assert(d.none { it.code == 5107 })
    }

    /** tsgo's enum map aliases `node` to `Node10`, so both spellings print `node10` (`program.go:870`) — pre-existing here, kept and pinned. */
    @Test
    fun `control - node and node10 both report the removed value node10`() {
        for (value in listOf("node", "node10")) {
            val d = diagnose("export const a = 1;", directives = "// @strict: true\n// @module: commonjs\n// @moduleResolution: $value\n// @typeScriptVersion: 7.0")
            val row = d.singleOrNull { it.code == 5108 }
            assert(row != null)
            assert(row.message == "Option 'moduleResolution=node10' has been removed. Please remove it from your configuration.")
        }
    }

    // ── TS5095 / TS5109 / TS5110 read the DERIVED kind (program.go:1146-1175) ─────────

    /** tsgo: `module: amd` with no `moduleResolution` is a Bundler program — TS5095 beside the `module=AMD` row. */
    @Test
    fun `module amd with no moduleResolution is a Bundler program and reports TS5095`() {
        for (kind in listOf("amd", "system")) {
            val d = diagnose("export const a = 1;", directives = "// @strict: true\n// @module: $kind")
            val row = d.singleOrNull { it.code == 5095 }
            assert(row != null)
            assert(row.message == "Option 'bundler' can only be used when 'module' is set to 'preserve', 'commonjs', or 'es2015' or later.")
        }
    }

    @Test
    fun `control - commonjs esnext preserve and an unset module report no TS5095`() {
        for (directives in listOf("// @strict: true\n// @module: commonjs", "// @strict: true\n// @module: esnext", "// @strict: true\n// @module: preserve", "// @strict: true")) {
            val d = diagnose("export const a = 1;", directives = directives)
            assert(d.none { it.code == 5095 })
        }
    }

    /** tsgo: `bundler` + `module: nodenext` is TS5095 AND TS5109 — the mirror of TS5110. */
    @Test
    fun `bundler with module nodenext reports TS5109 and TS5095`() {
        val d = diagnose("export const a = 1;", directives = "// @strict: true\n// @module: nodenext\n// @moduleResolution: bundler")
        val row = d.singleOrNull { it.code == 5109 }
        assert(row != null)
        assert(row.message == "Option 'moduleResolution' must be set to 'NodeNext' (or left unspecified) when option 'module' is set to 'NodeNext'.")
        assert(d.count { it.code == 5095 } == 1)
    }

    /** The message is unchanged by this round (a control); its ANCHORING is [ProjectModuleResolutionRemovedTest]'s. */
    @Test
    fun `control - node16 with module commonjs reports TS5110`() {
        val d = diagnose("export const a = 1;", directives = "// @strict: true\n// @module: commonjs\n// @moduleResolution: node16")
        val row = d.singleOrNull { it.code == 5110 }
        assert(row != null)
        assert(row.message == "Option 'module' must be set to 'Node16' when option 'moduleResolution' is set to 'Node16'.")
    }

    /** tsgo (`node16` × `nodenext` cell): both are node kinds — no row; the raw-value check used to demand `Node16` exactly. */
    @Test
    fun `node16 with module nodenext reports no TS5110`() {
        val d = diagnose("export const a = 1;", directives = "// @strict: true\n// @module: nodenext\n// @moduleResolution: node16")
        assert(d.none { it.code == 5110 || it.code == 5109 })
    }

    // ── the "classic root tslib" rule ─────────────────────────────────────────────────

    /**
     * tsgo: `a.ts(1,1): error TS2354: This syntax requires an imported helper but module
     * 'tslib' cannot be found.` — a root `tslib.d.ts` outside `node_modules` is not the
     * `tslib` package under any TypeScript 7 resolution (measured on the unset twin, and
     * the classic cell is that twin). Before: the classic rule found it (no TS2354) and the
     * missing-helper walker then read its exports (TS2343).
     */
    @Test
    fun `classic - a root tslib dts is not the tslib package - TS2354`() {
        val d = diagnose(
            """
            // @Filename: /tslib.d.ts
            export declare function __importStar(m: any): any;

            // @Filename: /b.ts
            export const q = 1;

            // @Filename: /a.ts
            import * as ns from "./b"; ns;
            """,
            directives = "// @strict: true\n// @module: commonjs\n// @moduleResolution: classic\n// @importHelpers: true",
        )
        assert(d.count { it.code == 2354 } == 1)
        assert(d.none { it.code == 2343 })
    }

    /** Under tsc-6 classic the `node_modules` walk was skipped outright (TS2354 here); tsgo's Bundler twin finds it. */
    @Test
    fun `classic - a node_modules tslib satisfies importHelpers as under every resolution`() {
        val d = diagnose(
            """
            // @Filename: /node_modules/tslib/index.d.ts
            export declare function __importStar(m: any): any;

            // @Filename: /b.ts
            export const q = 1;

            // @Filename: /a.ts
            import * as ns from "./b"; ns;
            """,
            directives = "// @strict: true\n// @module: commonjs\n// @moduleResolution: classic\n// @importHelpers: true",
        )
        assert(d.none { it.code == 2354 })
    }

    // ── the import()-type builtin walker's classic exclusion ─────────────────────────

    /** tsgo (unset twin): `a.ts(1,25): error TS2591: Cannot find name 'fs'. …` — the classic exclusion kept it silent. */
    @Test
    fun `classic - an import type naming a node builtin without types is TS2591`() {
        val d = classicTwoFile("""declare const b: import("fs").Stats; b;""")
        assert(d.count { it.code == 2591 } == 1)
    }

    /** tsgo (unset twin): `error TS2307: Cannot find module './nope'` for a relative import TYPE too — B227 used to demand an UNSET value. */
    @Test
    fun `classic - a relative import type naming a missing file is TS2307`() {
        val d = classicTwoFile("""declare const b: import("./nope").B; b;""")
        assert(d.count { it.code == 2307 } == 1)
        assert(d.none { it.code == 2792 })
    }

    // ── arms that now reach the DEFAULT program because unset derives Bundler ──────────

    /** tsgo: `error TS2307: Cannot find module './nope/'` with neither option set (B98's trailing-slash arm was explicit-bundler only). */
    @Test
    fun `a trailing-slash relative import of a missing directory is TS2307 with no module option at all`() {
        val d = diagnose(
            """
            // @Filename: /a.ts
            import { x } from "./nope/"; x;

            // @Filename: /b.ts
            export const q = 1;
            """,
            directives = "// @strict: true",
        )
        assert(d.count { it.code == 2307 } == 1)
    }

    /** tsgo: `error TS2307: Cannot find module './nope'` under an explicit `bundler` — the 17.214 / B98 arms used to demand an UNSET value. */
    @Test
    fun `an explicit bundler with a missing relative import is TS2307 under esnext and commonjs`() {
        for (kind in listOf("esnext", "commonjs")) {
            val d = diagnose(
                """
                // @Filename: /a.ts
                import { x } from "./nope"; x;

                // @Filename: /b.ts
                export const q = 1;
                """,
                directives = "// @strict: true\n// @module: $kind\n// @moduleResolution: bundler",
            )
            assert(d.count { it.code == 2307 } == 1)
        }
    }

    /** control — `cachedModuleResolution6`'s shape: an explicit `bundler` with no `module` still reports the bare sibling. */
    @Test
    fun `control - an explicit bundler with no module option still reports a bare sibling specifier`() {
        val d = diagnose(
            """
            // @Filename: /a.ts
            import { x } from "server"; x;

            // @Filename: /server.ts
            export const x = 1;
            """,
            directives = "// @strict: true\n// @moduleResolution: bundler",
        )
        assert(d.count { it.code == 2307 } == 1)
    }

    // ── the two importer-format gates (measured: the resolution never decided them) ───

    private fun typeModulePackage(importer: String) = """
        // @Filename: /node_modules/esm/package.json
        { "name": "esm", "version": "1.0.0", "type": "module", "types": "./index.d.ts", "main": "./index.js" }

        // @Filename: /node_modules/esm/index.d.ts
        export declare const e: number;

        // @Filename: /a.ts
        $importer
        """

    /** tsgo: `a.ts(1,8): error TS1192` under `module: esnext` with NO `moduleResolution` — the old resolution gate skipped it. */
    @Test
    fun `a default import of a type-module package from an ESM importer is TS1192 with no moduleResolution set`() {
        val d = diagnose(typeModulePackage("""import d from "esm"; d;"""), directives = "// @strict: true\n// @module: esnext")
        assert(d.count { it.code == 1192 } == 1)
    }

    /** tsgo: silent under `module: commonjs` + explicit `bundler` — a CommonJS importer gets the synthetic default; the old gate reported TS1192. */
    @Test
    fun `a default import of a type-module package from a CommonJS importer is silent even under an explicit bundler`() {
        val d = diagnose(typeModulePackage("""import d from "esm"; d;"""), directives = "// @strict: true\n// @module: commonjs\n// @moduleResolution: bundler")
        assert(d.none { it.code == 1192 })
    }

    /** tsgo: `a.ts(2,4): error TS2339: Property 'default' does not exist on type …` under `module: esnext`, no `moduleResolution` (the walker's shape is a TOP-LEVEL `const ns = await import(…)`). */
    @Test
    fun `a dynamic import of a type-module package has no default member for an ESM importer with no moduleResolution set`() {
        val d = diagnose(
            typeModulePackage("const ns = await import(\"esm\");\n        ns.default;\n        ns.e;\n        export {};"),
            directives = "// @strict: true\n// @target: es2022\n// @module: esnext",
        )
        val rows = d.filter { it.code == 2339 }
        assert(rows.size == 1)
        assert(rows[0].message.startsWith("Property 'default' does not exist on type"))
    }

    /** tsgo: silent under `module: commonjs` + explicit `bundler`; the old whole-program gate ran the check there. */
    @Test
    fun `a dynamic import of a type-module package from a CommonJS importer is silent even under an explicit bundler`() {
        val d = diagnose(
            typeModulePackage("const ns = await import(\"esm\");\n        ns.default;\n        ns.e;\n        export {};"),
            directives = "// @strict: true\n// @target: es2022\n// @module: commonjs\n// @moduleResolution: bundler",
        )
        assert(d.none { it.code == 2339 })
    }

    // ── the derivation itself ─────────────────────────────────────────────────────────

    @Test
    fun `effectiveModuleResolution is tsgo's GetModuleResolutionKind`() {
        fun kind(module: ModuleKind?, resolution: String?) =
            CompilerOptions(module = module, moduleResolution = resolution).effectiveModuleResolution
        for (removed in listOf(null, "classic", "node", "node10", "Classic", "NODE10", "not-a-value")) {
            assert(kind(null, removed) == ModuleResolutionKind.Bundler)
            assert(kind(ModuleKind.CommonJS, removed) == ModuleResolutionKind.Bundler)
            assert(kind(ModuleKind.ESNext, removed) == ModuleResolutionKind.Bundler)
            assert(kind(ModuleKind.AMD, removed) == ModuleResolutionKind.Bundler)
            assert(kind(ModuleKind.System, removed) == ModuleResolutionKind.Bundler)
            assert(kind(ModuleKind.Preserve, removed) == ModuleResolutionKind.Bundler)
            assert(kind(ModuleKind.Node16, removed) == ModuleResolutionKind.Node16)
            assert(kind(ModuleKind.Node18, removed) == ModuleResolutionKind.Node16)
            assert(kind(ModuleKind.Node20, removed) == ModuleResolutionKind.Node16)
            assert(kind(ModuleKind.NodeNext, removed) == ModuleResolutionKind.NodeNext)
        }
        assert(kind(ModuleKind.CommonJS, "bundler") == ModuleResolutionKind.Bundler)
        assert(kind(ModuleKind.NodeNext, "bundler") == ModuleResolutionKind.Bundler)
        assert(kind(ModuleKind.CommonJS, "node16") == ModuleResolutionKind.Node16)
        assert(kind(ModuleKind.CommonJS, "NodeNext") == ModuleResolutionKind.NodeNext)
    }
}
