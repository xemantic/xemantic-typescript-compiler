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

/**
 * The `target` VALUES tsgo 7.0.2's `targetOptionMap` (`commandlineparser.go`) still has an
 * entry for. **`ES3` IS DELIBERATELY ABSENT** ((LEGACY.1)(j4), 2026-09-16): TypeScript 7
 * deleted it from that map, so `"target": "ES3"` is an INVALID ARGUMENT — TS6046 at the
 * VALUE, after which the option is UNSET and the program checks and emits at the latest
 * standard (measured on tsgo: `lib.es2025.full.d.ts`, no checker row, native ESM emit).
 * Being unknown to [fromString] is what PRODUCES that row, so do not re-add the member.
 *
 * `ES5` by contrast IS still in tsgo's map, flagged deprecated — a KNOWN-but-removed value,
 * reported TS5107/TS5108 at the value and then honoured by the checker's `languageVersion`
 * — so it stays, and (LEGACY.1)(j1)-(j3)'s pins depend on it.
 */
enum class ScriptTarget {
    ES5, ES2015, ES2016, ES2017, ES2018, ES2019, ES2020, ES2021, ES2022, ES2023, ES2024, ESNext;

    companion object {
        fun fromString(value: String): ScriptTarget? = when (value.lowercase()) {
            "es5" -> ES5
            "es6", "es2015" -> ES2015
            "es2016" -> ES2016
            "es2017" -> ES2017
            "es2018" -> ES2018
            "es2019" -> ES2019
            "es2020" -> ES2020
            "es2021" -> ES2021
            "es2022" -> ES2022
            "es2023" -> ES2023
            "es2024" -> ES2024
            "esnext" -> ESNext
            else -> null
        }
    }
}

enum class ModuleKind {
    None, CommonJS, AMD, UMD, System, ES2015, ES2020, ES2022, ESNext, Node16, Node18, Node20, NodeNext, Preserve;

    /** True for Node16, Node18, Node20, NodeNext — all node-resolution module kinds. */
    val isNodeNext: Boolean get() = this == Node16 || this == Node18 || this == Node20 || this == NodeNext

    /**
     * (LEGACY.1)(f) `amd` / `umd` / `system` are REMOVED values in TypeScript 7: tsgo 7.0.2's
     * `program.go:844-852` reports each (TS5108 `module=AMD` / `module=System` / `module=UMD`,
     * value-anchored — [CompilerOptions.module] keeps the WRITTEN kind so the option row and
     * TS2725's `with module AMD` wording can name it) and then IGNORES it: `GetEmitModuleKind()`
     * (`compileroptions.go:202`) answers the option as written and `getModuleTransformer`
     * (`emitter.go:82-101`) sends every kind it does not list — the three removed ones and
     * `none` — to the CommonJS module transform. There is no AMD, UMD or System emit in
     * TypeScript 7.
     */
    val isRemoved: Boolean get() = this == AMD || this == UMD || this == System

    /**
     * (LEGACY.1)(f) tsgo 7.0.2 checks and emits a removed kind exactly as `commonjs` — the
     * fold this compiler mirrors at the CommonJS transform ([Transformer]), at the tslib
     * helper checks, at the explicit-`commonjs` unresolved-import arms and at TS2441.
     *
     * Measured 2026-09-15 (20 scratch programs × `module` ∈ {amd, umd, system, commonjs,
     * esnext, unset} × `target` ∈ {es2020, esnext}, tsgo CLI + `--outDir` + the LSP's
     * `textDocument/diagnostic`, since the CLI stops at the option rows): the `amd` and `umd`
     * cells are byte-identical to the `commonjs` cell of the same program on every emitted
     * file and every checker row — TS1343, TS1378/TS1432, TS2305, TS2441, TS2725 (naming the
     * written kind), TS2882 — except `importHelpers`, where tsgo emits an UNBOUND
     * `__exportStar(…)` beside `const tslib_1 = require("tslib")` and reports no TS2354, a
     * tsgo defect this compiler does not copy (it reports TS2354 as under `commonjs`). The
     * `system` cell is the `commonjs` cell too, bar three checker arms tsgo keys on the
     * WRITTEN kind — TS1218 on `export =`, top-level `await` allowed ([allowsTopLevelAwait]),
     * `import.meta` allowed — and the enum/namespace leading comments its `runtimesyntax.go`
     * keeps under System.
     *
     * `none` is deliberately NOT here: it takes tsgo's CommonJS transform too, but its checker
     * rows (TS1148) are its own and it is outside (LEGACY.1)'s scope ((k)).
     */
    val foldsToCommonJS: Boolean get() = this == CommonJS || isRemoved

    /**
     * The module kinds under which a top-level `await` / `for await` / `await using` is
     * legal at `target >= es2017` — tsgo 7.0.2's `grammarchecks.go:1219-1228` /
     * `:1705-1730` case list: the node kinds, ES2022, ESNext, Preserve and (a live arm
     * keyed on the WRITTEN kind, measured 2026-09-15 — no TS1378 under `system`, TS1378
     * under `amd`/`umd` as under `commonjs`) the removed System. The one home for the list
     * the parser's `topLevelAwait` flag and the checker's TS1378/TS1432 gate both read.
     */
    val allowsTopLevelAwait: Boolean
        get() = this == ES2022 || this == ESNext || isNodeNext || this == Preserve || this == System

    /**
     * tsc's `moduleKind >= ModuleKind.ES2015` — ES2015…ESNext, Node16…NodeNext and Preserve,
     * in the same order tsc numbers them. It decides which wording a named import of an
     * `export =` module gets (`reportInvalidImportEqualsExportMember`, checker.go:14867):
     * TS2595 *can only be imported by using a default import* at or above ES2015, TS2616 /
     * TS2597 below — a property of the `module` OPTION, not of the importer's file format
     * (a CommonJS-scoped `.ts` under `nodenext` still reads TS2595).
     */
    val isEs2015OrHigher: Boolean get() = ordinal >= ES2015.ordinal

    companion object {
        fun fromString(value: String): ModuleKind? = when (value.lowercase()) {
            "none" -> None
            "commonjs" -> CommonJS
            "amd" -> AMD
            "umd" -> UMD
            "system" -> System
            "es6", "es2015" -> ES2015
            "es2020" -> ES2020
            "es2022" -> ES2022
            "esnext" -> ESNext
            "node16" -> Node16
            "node18" -> Node18
            "node20" -> Node20
            "nodenext" -> NodeNext
            "preserve" -> Preserve
            else -> null
        }
    }
}

/**
 * (LEGACY.1)(e) TypeScript 7's module resolutions — the only three that EXIST there.
 * tsgo's `core.ModuleResolutionKind` still carries `Classic` and `Node10` as PARSE values
 * (so `"classic"`, `"node"` and `"node10"` are accepted and reported TS5108 *has been
 * removed*), but no algorithm answers to either: `GetModuleResolutionKind()`
 * (`compileroptions.go:223-237`) folds both, together with an unset value, into the
 * kind DERIVED from the emit module kind. See [CompilerOptions.effectiveModuleResolution].
 */
enum class ModuleResolutionKind {
    Node16, NodeNext, Bundler;

    /** tsgo's `ModuleResolutionKindNode16 <= kind && kind <= ModuleResolutionKindNodeNext`. */
    val isNode16OrNodeNext: Boolean get() = this == Node16 || this == NodeNext
}

/**
 * Tracks the position of a compiler option in a tsconfig.json file,
 * used for emitting positioned deprecation diagnostics.
 * Stores both KEY position (for TS5101/TS5102) and VALUE position (for TS5107).
 */
data class TsconfigOptionPosition(
    val fileName: String,
    // Key position (for TS5101/TS5102 diagnostics that point to the option name)
    val keyLine: Int,        // 1-based
    val keyCharacter: Int,   // 1-based
    val keyStart: Int,       // 0-based byte offset
    val keyLength: Int,      // length of key including quotes
    // Value position (for TS5107 diagnostics that point to the option value)
    val valueLine: Int,      // 1-based
    val valueCharacter: Int, // 1-based
    val valueStart: Int,     // 0-based byte offset
    val valueLength: Int,    // length of value including quotes for strings
)

data class CompilerOptions(
    val target: ScriptTarget = ScriptTarget.ES5,
    val targetExplicitlySet: Boolean = false,
    /**
     * (LEGACY.1)(j4) The written `target` names no value in tsgo's option map (`es3`, or
     * any other spelling [ScriptTarget.fromString] does not know). tsgo reports TS6046 at
     * the VALUE and leaves the option UNSET, so [targetExplicitlySet] stays false and every
     * target question answers the default — which is why this is a marker beside the option
     * rather than a [ScriptTarget] member. `TypeScriptCompiler.cpcCheckDeprecatedOptions`
     * is its one reader.
     */
    val targetValueInvalid: Boolean = false,
    val module: ModuleKind? = null,
    val strict: Boolean = false,
    /** True when `// @strict: false` was explicitly set (not just defaulting to false). */
    val strictExplicitlyFalse: Boolean = false,
    val noEmit: Boolean = false,
    /**
     * (FRONT.1) round 738: the CALLER does not want the JS outputs at all, so
     * the compile core must not produce them.
     *
     * Distinct from [noEmit] on purpose. `noEmit` is a corpus DIRECTIVE (440
     * tests set `@noEmit: true`) whose meaning to the harness is "do not WRITE",
     * and those tests' baselines are produced by a core that still transforms
     * and emits; gating the emit loop on it would change 440 behaviours at once.
     * This flag is set only by [ProjectCompiler] from its own `noEmit`
     * parameter — i.e. by `xtsc --noEmit`, the type-check-only CI mode — so no
     * existing caller's behaviour moves.
     *
     * Measured: on the tsc compiler profile the transform+emit the core ran and
     * then discarded under `--noEmit` was **2,623 ms of a 31,235 ms compile
     * (8.4%)** — `Transformer.transform` 2,211 ms and `Emitter.emit` 412 ms
     * across 78 files. Real `tsc --noEmit` does not run its emitter either, so
     * every published xtsc-vs-tsc `--no-emit` ratio before this flag compared
     * our check+emit against tsc's check-only.
     */
    val skipEmitOutputs: Boolean = false,
    /** Emit a UTF-8 byte order mark at the start of js/d.ts outputs. */
    val emitBOM: Boolean = false,
    val noEmitHelpers: Boolean = false,
    val declaration: Boolean = false,
    val declarationMap: Boolean = false,
    val removeComments: Boolean = false,
    val preserveConstEnums: Boolean = false,
    val preserveConstEnumsExplicitlyFalse: Boolean = false,
    val sourceMap: Boolean = false,
    val noImplicitAny: Boolean = false,
    val noImplicitAnyExplicitlyFalse: Boolean = false,
    val noImplicitReturns: Boolean = false,
    val noImplicitThis: Boolean = false,
    /** True when `// @noImplicitThis: false` was explicitly set. */
    val noImplicitThisExplicitlyFalse: Boolean = false,
    val strictNullChecks: Boolean = false,
    /** True when `// @strictNullChecks: false` was explicitly set. */
    val strictNullChecksExplicitlyFalse: Boolean = false,
    /** `useUnknownInCatchVariables`: when effective, an un-annotated catch variable is typed
     *  `unknown` instead of `any`. Effective value = this flag if explicitly set, else `strict`. */
    val useUnknownInCatchVariables: Boolean = false,
    val useUnknownInCatchVariablesExplicitlySet: Boolean = false,
    /** (CHK.134) `strictBindCallApply`: when effective, a function value's `call`/`apply`
     *  are the lib's `CallableFunction` members — typed from the receiver's own signature —
     *  instead of `Function`'s loose `any`-typed ones. Effective value = this flag if
     *  explicitly set, else `strict` (tsc's `getStrictOptionValue`). tsc's own sources set
     *  it `false` explicitly in an otherwise-`strict` project, which is why the profiles
     *  exercise the loose half. */
    val strictBindCallApply: Boolean = false,
    val strictBindCallApplyExplicitlySet: Boolean = false,
    /** True when `// @strictPropertyInitialization: false` was explicitly set. */
    val strictPropertyInitializationExplicitlyFalse: Boolean = false,
    val noUnusedLocals: Boolean = false,
    val noUnusedParameters: Boolean = false,
    val experimentalDecorators: Boolean = false,
    val emitDecoratorMetadata: Boolean = false,
    val jsx: String? = null,
    val jsxFactory: String? = null,
    val jsxFragmentFactory: String? = null,
    val reactNamespace: String? = null,
    val lib: List<String> = emptyList(),
    /** M2.1(c): resolve the default library from the REAL TypeScript lib .d.ts set
     *  ([RealLibFiles] via [RealLibSnapshots]) instead of the embedded simplified
     *  BUILTIN_LIB_SOURCE.
     *
     *  The DEFAULT stays `false` because the generated corpus suite builds its
     *  options from this constructor plus `@directives`, and its ~13k baselines were
     *  produced against the embedded lib. A REAL PROJECT BUILD flips it on —
     *  see [projectDefaults], used by `TsConfigLoader`/`ProjectCompiler`; an
     *  explicit `"useRealLibs": false` in a tsconfig still turns it back off,
     *  because [applyDirective] runs after. */
    val useRealLibs: Boolean = false,
    val outDir: String? = null,
    val rootDir: String? = null,
    val rootDirs: List<String>? = null,
    val typeRoots: List<String>? = null,
    /** The `types` option — auto-included type-library entry points (B263). */
    val types: List<String>? = null,
    val baseUrl: String? = null,
    val paths: Map<String, List<String>> = emptyMap(),
    val moduleResolution: String? = null,
    /**
     * (LEGACY.1)(d2) `esModuleInterop` and `allowSyntheticDefaultImports` are not options in
     * TypeScript 7: tsgo 7.0.2's `core.CompilerOptions` carries both fields and reads them in
     * exactly one place — `program.go:862-868`, the TS5108 *has been removed* row for an
     * explicit `false` — so ES-module interop is ALWAYS on (`checker.go:14478`: "With
     * `esModuleInterop` (always enabled)") and a synthetic default is decided by the TARGET
     * alone (`canHaveSyntheticDefault`, no option gate). Measured over 45 projects
     * (5 module kinds × the 3×3 unset/false/true matrix): every cell's checker rows and
     * emitted helpers are byte-identical, the explicit-`false` cells differing only in the
     * TS5108 row. So the two boolean options are gone; what survives is the pair of
     * "written as `false`" markers below, read by [addDeprecation]'s TS5107/TS5108 row and
     * by nothing else.
     */
    val esModuleInteropExplicitlyFalse: Boolean = false,
    val allowSyntheticDefaultImportsExplicitlyFalse: Boolean = false,
    val allowJs: Boolean = false,
    val allowJsExplicitlyFalse: Boolean = false,
    val checkJs: Boolean = false,
    val isolatedModules: Boolean = false,
    val skipLibCheck: Boolean = false,
    val forceConsistentCasingInFileNames: Boolean = false,
    val noEmitOnError: Boolean = false,
    /** (LEGACY.1)(i) `downlevelIteration` is a REMOVED option in TypeScript 7: only the "was
     *  written" marker survives, for the TS5101/TS5102 row — nothing reads a value. */
    val downlevelIterationExplicitlySet: Boolean = false,
    val importHelpers: Boolean = false,
    val useDefineForClassFields: Boolean? = null,
    val verbatimModuleSyntax: Boolean = false,
    val noCheck: Boolean = false,
    val emitDeclarationOnly: Boolean = false,
    val mapRoot: String? = null,
    val outFile: String? = null,
    val alwaysStrict: Boolean? = null,
    val newLine: String? = null,
    val fullEmitPaths: Boolean = false,
    val allowUnreachableCode: Boolean? = null,
    val allowUnusedLabels: Boolean? = null,
    val noFallthroughCasesInSwitch: Boolean = false,
    val noResolve: Boolean = false,
    val noImplicitReferences: Boolean = false,
    val moduleDetection: String? = null,
    val moduleSuffixes: List<String>? = null,
    // Deprecated options (tracked for TS5101 diagnostics)
    val charset: String? = null,
    val keyofStringsOnly: Boolean = false,
    val noImplicitUseStrict: Boolean = false,
    val noStrictGenericChecks: Boolean = false,
    val suppressExcessPropertyErrors: Boolean = false,
    val suppressImplicitAnyIndexErrors: Boolean = false,
    val out: String? = null, // distinct from outFile for diagnostic purposes
    val importsNotUsedAsValues: String? = null, // removed in TS 5.5
    val preserveValueImports: Boolean = false, // removed in TS 5.5
    val resolveJsonModule: Boolean = false,
    val noLib: Boolean = false,
    val inlineSourceMap: Boolean = false,
    val inlineSources: Boolean = false,
    val sourceRoot: String? = null,
    val composite: Boolean = false,
    val declarationDir: String? = null,
    val exactOptionalPropertyTypes: Boolean = false,
    val noUncheckedIndexedAccess: Boolean = false,
    val pretty: Boolean = false,
    val incremental: Boolean? = null,
    val isolatedDeclarations: Boolean = false,
    val erasableSyntaxOnly: Boolean = false,
    val ignoreDeprecations: String? = null,
    val allowImportingTsExtensions: Boolean = false,
    val rewriteRelativeImportExtensions: Boolean = false,
    /** Test-harness directive `// @captureSuggestions`: include Suggestion-category
     *  diagnostics (e.g. TS6807 shift simplification) in the error baseline. */
    val captureSuggestions: Boolean = false,
    /**
     * Simulated TypeScript version for version-gated diagnostics (from `// @typeScriptVersion` test directive).
     * When set, options deprecated at version X emit TS5102/TS5108 ("removed") instead of TS5101/TS5107
     * ("deprecated") if this version >= their `stopFunctioningVersion`.
     */
    val simulatedTypeScriptVersion: String? = null,
    /** Maps lowercase option names to their positions in tsconfig.json (for positioned diagnostics). */
    val tsconfigOptionPositions: Map<String, TsconfigOptionPosition> = emptyMap(),
    /** Diagnostics from paths validation in tsconfig.json (TS5061/5062/5063/5064/5066/5090). */
    val pathsDiagnostics: List<Diagnostic> = emptyList(),
    /**
     * (CHK.29) The package SCOPES of the program: every directory carrying a
     * `package.json`, mapped to whether that manifest says `"type": "module"`.
     * A directory with a `package.json` that names no `"type"` is present with
     * `false` — an ABSENT key means "no `package.json` here", which is a different
     * fact and is what lets the walk continue upward (see [packageScopeIsModule]).
     *
     * Consulted under Node16/Node18/Node20/NodeNext only; under an ES module kind
     * every file is ESM regardless, which is why tsc's own sources and all eight
     * dashboard profiles are structurally unable to observe any of this.
     *
     * Two producers, deliberately: [ProjectCompiler] walks the [Vfs] up from each
     * program file's directory (a real project has no `package.json` among its
     * INPUTS), and the multi-file corpus path reads the `package.json` entries out
     * of the parsed source set.
     */
    val packageJsonTypes: Map<String, Boolean> = emptyMap(),
) {

    /**
     * The syntax level the **EMITTER** lowers to. An UNSET target answers ES2024 (our top
     * standard; tsc's `getEmitScriptTarget` answers its LatestStandard), so emit keeps
     * native class fields / async / spread; an EXPLICIT `es5` answers **ES2015**.
     *
     * **WHY THE ES5→ES2015 MAP IS THE MODEL OF TSGO'S MISSING ES5 TRANSFORMER, not a tsc-6
     * residue** ((LEGACY.1)(j4), 2026-09-16). TypeScript 7 ships no ES5 lowering at all:
     * measured over 12 shapes (class fields + a private field, array/object spread, object
     * and array rest destructuring, async + async generators, generators, `for…of`,
     * tagged templates, optional chaining + `??`, `**`, arrows, `let`/`const`, a derived
     * class), tsgo's emit at a written `es5` is **BYTE-IDENTICAL to its emit at `es2015`**,
     * 12 files of 12, and differs from an unset target. That is exactly what this map makes
     * our 63 emit-side readers produce — `Transformer.kt`'s lowest comparison is
     * `< ES2016`, and there is no `< ES2015` gate anywhere in the emitter.
     *
     * **THE COLLAPSE ONTO [defaultedTarget] IS REFUSED, BY MEASUREMENT.** The queue item
     * asks for one notion ("their whole reason was the explicit-ES5 split"); the split is
     * now the model above, and both directions of the collapse were built and run:
     *  - ONE notion valued the WRITTEN target (this property deleted): the strict-reserved
     *    binding rows go silent at a written es5 — 2 → 0 TS12xx on a script declaring
     *    `var public` / `var yield`, where tsgo reports them at every target (it binds
     *    every file strict). See `Checker`'s `spineStrictFileIsStrict`.
     *  - ONE notion valued the EMIT target ([defaultedTarget] deleted): a written es5 opens
     *    the TS2318 rest-only-binding-pattern gate that tsgo keeps SHUT there
     *    (`checker.go:17879` reads `languageVersion`, the written target — (LEGACY.1)(j2)
     *    pinned it both ways), and [effectiveModule] stops defaulting to CommonJS at a
     *    written es5, which tsgo does.
     * So the two notions have disjoint, non-empty, measured constituencies. `TargetOptionSurfaceTest`
     * pins that they differ at an explicit es5 and agree everywhere else.
     *
     * **NOT the lib dimension either**: which lib FILES load is a third question, answered by
     * [RealLibResolver.defaultLibEsLevel] of [defaultedTarget]. It happens to be the same
     * function as this one today — an arithmetic coincidence of one shared `<= ES5` step —
     * and it lives beside the file-name table it is derived from so the two cannot drift.
     */
    val effectiveTarget: ScriptTarget
        get() = when {
            !targetExplicitlySet -> ScriptTarget.ES2024
            target <= ScriptTarget.ES5 -> ScriptTarget.ES2015
            else -> target
        }

    /**
     * The **WRITTEN language version** — tsgo's `GetEmitScriptTarget`, the single notion
     * its whole checker reads (`languageVersion`). An unset target answers our top
     * standard, ES2024 (tsgo's LatestStandard); an explicit `es5` answers **ES5**.
     *
     * **WHAT STILL READS IT FOR A `< ES2015` DECISION, after (LEGACY.1)(j1)-(j3) swept the
     * family gate by gate against tsgo at a written es5: exactly ONE site** — the TS2318
     * rest-only binding pattern (`checker.go:17879`, `checkGlobalIterableRestOnlyBindingPattern`),
     * which tsgo keeps SHUT at a written es5 and which is pinned both ways by
     * `TargetGatesRemovedTest`. Every other `defaultedTarget` comparison in the checker is
     * `< ES2017` / `< ES2018` / `< ES2020` / `>= ES2022`, i.e. a bound BOTH notions fall the
     * same side of, so this property and [effectiveTarget] are interchangeable there and the
     * label is the only thing that carries the reason.
     *
     * Its other two consumers are structural rather than comparative: the **lib SET**
     * ([RealLibResolver.defaultLibFileName] / `bindLibFiles` / `libDeclIndex`, (CHK.17)
     * round 944 — reading the raw target there was a false-positive family, 3 rows over the
     * pristine sweep), and the **module default** ([effectiveModule], whose `>= ES2015` test
     * tsgo also takes on the written target). The numeric LIB-AVAILABILITY model is a step
     * further out: it compares against [RealLibResolver.defaultLibEsLevel] of this value,
     * because below ES2015 the default lib FILE reaches es2015 through `dom`.
     *
     * (Round 944 introduced this as `libTarget`; the name was renamed in round 945 when
     * the second family joined, because it no longer names its only consumer.)
     *
     * NOT every raw-`options.target` read is one of these. A gate whose shape is
     * `target >= ES2015 || <other disjuncts>` — the strict-mode determinations
     * `spineDelIsStrict` / `spineStrictFileIsExprStrict` — is a MIS-TRANSCRIPTION of
     * tsc's nested rule that is CORRECT only while the raw target reads ES3 at an unset
     * target: flipping those makes every file strict. They keep the raw target
     * deliberately. (`checkOperationsAvailableOnPromisedType`, a per-fixture baseline pin,
     * used to be the third raw reader; (LEGACY.1)(j2) deleted its `target < ES2015` arm —
     * the `(target=es5)` variation is skipped by the generator and tsgo has no baseline
     * for it — so the two strict-mode determinations are the only raw readers left.)
     *
     * tsgo's definition (`compileroptions.go`): `options.Target`, else `LatestStandard`.
     * Our top standard target is ES2024, so an unset target answers ES2024 here — the same
     * value [effectiveTarget] gives the emitter, which is what makes the two dimensions
     * agree for a project that names no target. `ES3` is no longer expressible at all:
     * (LEGACY.1)(j4) took it out of [ScriptTarget], because in TypeScript 7 `"ES3"` is an
     * invalid ARGUMENT (TS6046) after which the option is UNSET.
     *
     * **Not [effectiveTarget], and the two may NOT be collapsed** — that maps an explicit
     * `es5` UP to ES2015, which would open TS2318 where tsgo keeps it shut and would stop
     * [effectiveModule] defaulting to CommonJS there. The refusal is measured in both
     * directions; the numbers are in [effectiveTarget]'s KDoc and pinned by
     * `TargetOptionSurfaceTest`.
     *
     * **Not the raw [target]** either: before the enum lost `ES3` its zero value was
     * indistinguishable from "the user said nothing", which is what made `Cannot find name
     * 'AsyncIterableIterator'. Do you need to change your target library?` fire on a
     * tsconfig with no `target` at all. The zero value is `ES5` now and [targetExplicitlySet]
     * is still what separates the two cases — the two surviving raw readers
     * (`spineStrictFileIsExprStrict`, `spineDelIsStrict`, both `>= ES2015` strict-mode
     * determinations that are CORRECT only while an unset target reads below ES2015) are
     * unaffected by that move.
     */
    /** (CHK.134) tsc's `getStrictOptionValue(options, "strictBindCallApply")`. */
    val effectiveStrictBindCallApply: Boolean
        get() = if (strictBindCallApplyExplicitlySet) strictBindCallApply else strict

    val defaultedTarget: ScriptTarget
        get() = if (targetExplicitlySet) target else ScriptTarget.ES2024

    /**
     * tsgo's `GetEmitModuleKind` (`compileroptions.go`): the written `module`, else
     * `ES2015` at `GetEmitScriptTarget() >= ES2015` and `CommonJS` below it.
     *
     * **The `else` arm is NOT dead and the notion here is [defaultedTarget], not
     * [effectiveTarget]** ((LEGACY.1)(j4), 2026-09-16 — the queue item said the opposite
     * and was measured wrong). `GetEmitScriptTarget` answers the WRITTEN target, so at a
     * written `es5` with no `module` tsgo defaults to **CommonJS**: measured over a
     * two-file ESM program, tsgo emits `Object.defineProperty(exports, "__esModule", …)`
     * + `require("./b")` at es5 and native `import`/`export` at es2015 and at an unset
     * target, while reading `effectiveTarget` here emitted ESM in all three. It is the one
     * place in this file where the ES5→ES2015 emit map must NOT be applied: the module
     * FORMAT is chosen from what the user wrote, the syntax LEVEL inside it is not.
     */
    val effectiveModule: ModuleKind
        get() = module ?: when {
            defaultedTarget >= ScriptTarget.ES2015 -> ModuleKind.ES2015
            else -> ModuleKind.CommonJS
        }

    /**
     * (LEGACY.1)(e) tsgo 7.0.2's `GetModuleResolutionKind()` (`compileroptions.go:223-237`),
     * the ONE derivation every consumer of the module resolution reads — the checker's
     * import walkers, the `tslib` lookups, the `import()`-type walker and the
     * `TypeScriptCompiler` option checks (TS5095 / TS5109 / TS5110) alike.
     *
     * An explicit `node16` / `nodenext` / `bundler` answers itself. Everything else —
     * UNSET, the removed `classic` / `node` / `node10` (which `cpcCheckDeprecatedOptions`
     * reports TS5108 and which tsgo then IGNORES), and any spelling tsgo's option map does
     * not have — derives from the emit module kind: `Node16` for the `node16`/`node18`/
     * `node20` module kinds, `NodeNext` for `nodenext`, and **`Bundler`** for every other,
     * `commonjs` and an unset `module` included. There is no classic and no node10
     * resolution in TypeScript 7.
     *
     * Measured (2026-09-15, 42 scratch projects × 8 specifier shapes, `--traceResolution`
     * plus the LSP's rows): the `classic` / `node` / `node10` cells are byte-identical to
     * the `unset` cell of the same `module` on every resolved file and every checker row,
     * differing only by the TS5108 row (`node` prints `node10`, tsgo's enum-map alias).
     */
    val effectiveModuleResolution: ModuleResolutionKind
        get() = when (moduleResolution?.lowercase()) {
            "node16" -> ModuleResolutionKind.Node16
            "nodenext" -> ModuleResolutionKind.NodeNext
            "bundler" -> ModuleResolutionKind.Bundler
            else -> when (effectiveModule) {
                ModuleKind.Node16, ModuleKind.Node18, ModuleKind.Node20 -> ModuleResolutionKind.Node16
                ModuleKind.NodeNext -> ModuleResolutionKind.NodeNext
                else -> ModuleResolutionKind.Bundler
            }
        }
}

/**
 * Returns true if the given module kind and file name indicate ES module format.
 * For Node16/NodeNext, `.cts` files are CJS; all others (`.ts`, `.mts`) default to ESM.
 *
 * **Note**: this overload has NO `package.json "type"` context. For correct behavior under
 * Node16/Node18/Node20/NodeNext, prefer the `isESModuleFormat(options, fileName)` overload
 * which consults `options.packageJsonTypes`.
 */
fun isESModuleFormat(module: ModuleKind, fileName: String): Boolean {
    // module: preserve passes through all file formats as-is (ESM syntax)
    if (module == ModuleKind.Preserve) return true
    // .cjs/.cts files are always CJS regardless of module setting
    if (fileName.endsWith(".cjs") || fileName.endsWith(".cts")) return false
    // .mjs/.mts files are always ESM regardless of module setting
    if (fileName.endsWith(".mjs") || fileName.endsWith(".mts")) return true
    return when (module) {
        ModuleKind.ES2015, ModuleKind.ES2020, ModuleKind.ES2022, ModuleKind.ESNext -> true
        ModuleKind.Node16, ModuleKind.Node18, ModuleKind.Node20, ModuleKind.NodeNext -> {
            // In node resolution modes, only .mts/.mjs files are ESM by default.
            // Plain .ts files are CJS (we don't have package.json "type" context).
            // .mts/.mjs already handled above, so only those reach here as true.
            false
        }
        else -> false
    }
}

/**
 * CompilerOptions-aware overload: consults [CompilerOptions.packageJsonTypes] for plain
 * `.ts`/`.js` files under Node16/Node18/Node20/NodeNext to determine ESM vs CJS based on
 * the nearest enclosing `package.json`'s `"type"` field.
 *
 * Lookup walks up the file's directory tree, stopping at the closest ancestor directory
 * that HAS a `package.json` (see [packageScopeIsModule]): `"type": "module"` → ESM,
 * anything else — including no `"type"` field at all — → CJS. With no enclosing
 * `package.json` anywhere, a plain `.ts` under nodenext is CJS, which is tsc's answer too.
 */
fun isESModuleFormat(options: CompilerOptions, fileName: String): Boolean {
    val module = options.effectiveModule
    if (module == ModuleKind.Preserve) return true
    if (fileName.endsWith(".cjs") || fileName.endsWith(".cts")) return false
    if (fileName.endsWith(".mjs") || fileName.endsWith(".mts")) return true
    return when (module) {
        ModuleKind.ES2015, ModuleKind.ES2020, ModuleKind.ES2022, ModuleKind.ESNext -> true
        ModuleKind.Node16, ModuleKind.Node18, ModuleKind.Node20, ModuleKind.NodeNext ->
            packageScopeIsModule(options.packageJsonTypes, fileName) ?: false
        else -> false
    }
}

/**
 * The nearest enclosing package scope's answer for [fileName], or `null` when no
 * ancestor directory of it carries a `package.json` at all.
 *
 * [scopes] is keyed by DIRECTORY and holds an entry for every directory that has a
 * `package.json` — including one that names no `"type"`, whose value is `false`.
 * That is not a detail: tsc's walk stops at the first `package.json` it meets, so a
 * scope with no `"type"` is CommonJS and must NOT fall through to a `"type":
 * "module"` ancestor (verified against tsgo 7.0.2; pinned by
 * `ProjectPackageJsonTypeTest.an inner package json without a type field stops the
 * walk`). An implementation that files an entry only when a `"type"` is present
 * gets that case silently wrong.
 *
 * Two key conventions are accepted for the root because the two producers differ:
 * [ProjectCompiler] files [PathUtil.dirname]-shaped keys (`"/"` at the root) and
 * the multi-file corpus path files `substringBeforeLast('/')`-shaped ones (`""`).
 */
internal fun packageScopeIsModule(scopes: Map<String, Boolean>, fileName: String): Boolean? {
    if (scopes.isEmpty()) return null
    var dir = PathUtil.dirname(fileName)
    while (true) {
        scopes[dir]?.let { return it }
        if (dir == "/" || dir.isEmpty()) {
            // The two root spellings mean the same directory; probe the other one.
            return scopes[if (dir == "/") "" else "/"]
        }
        val parent = PathUtil.dirname(dir)
        if (parent == dir) return null
        dir = parent
    }
}

/**
 * Whether a `package.json`'s TEXT puts its directory in an ECMAScript-module scope,
 * i.e. whether its `"type"` field is exactly `"module"`.
 *
 * Read through [LENIENT_JSON] rather than by a `"type"\s*:\s*"..."` regex, which
 * matches a nested `"type"` (a `contributors` entry, a `peerDependenciesMeta` block)
 * anywhere in the manifest. A manifest that does not parse answers `false` — the same
 * answer as one with no `"type"`, and the same one tsc gives, so a broken dependency
 * manifest degrades to CommonJS rather than aborting a build.
 */
internal fun packageJsonDeclaresModule(text: String): Boolean =
    try {
        LENIENT_JSON.parseToJsonElement(text).member("type")?.stringValue == "module"
    } catch (_: Exception) {
        false
    }

data class SourceFileEntry(
    val fileName: String,
    val content: String,
)

/**
 * Result of parsing compiler options and splitting multi-file sources.
 *
 * @property hasExplicitFilenames true when one or more `// @Filename:` directives were
 *   present in the source, even if only a single file was declared. When true, the
 *   multi-file baseline format must be used (filenames come from the directives, not
 *   from the overall test-file name).
 */
data class ParsedSource(
    val options: CompilerOptions,
    val files: List<SourceFileEntry>,
    val hasExplicitFilenames: Boolean = false,
    // `// @link: <realDir> -> <node_modules/pkgPath>` symlink map: bare package specifier
    // (extracted from the node_modules side) -> real source dir. Used only to add cross-file
    // dependency EDGES for multi-file emit ordering (symbolLinkDeclarationEmitModuleNames).
    val symlinkMap: Map<String, String> = emptyMap(),
    // Secondary non-node_modules `@symlink` instances (all but the first of each real file's
    // symlink group). These are COMPILED/emitted as distinct files but skipped in the source
    // echo (tsc dedupes the echo by realpath — moduleResolutionWithSymlinks_notInNodeModules).
    val symlinkSkipEcho: Set<String> = emptySet(),
    // INV.1(e): parses carried over from the project crawl (fileName -> pre-parse).
    // The core's multi-file parse site reuses an entry ONLY when its own computed
    // [ParserFlags] and the entry content match the recorded ones — reuse is a pure
    // optimization; any mismatch re-parses. Empty for the string-based [compile] path.
    val preParsed: Map<String, PreParsedFile> = emptyMap(),
    /**
     * (CHK.30): the project crawl's OWN module resolutions — importer file name ->
     * (module specifier as written -> resolved program file). Empty for the
     * string-based [compile] path and for every corpus fixture, which have no
     * [ModuleResolver] and no directory layout to resolve against.
     *
     * WHY IT HAS TO BE CARRIED. The checker re-derives "which file does this
     * specifier name" from the program's file NAMES ([Checker.resolveModuleSpecifier]
     * and its relative siblings). That matcher is a corpus-era simplification and
     * cannot express a bare package specifier — a `node_modules` package's `types` /
     * `main` / `exports` entry is not a string transformation of the specifier — so
     * an import alias into a package resolved to nothing and every type it named
     * degraded to `any`. Silently: `any` is legal everywhere, so what shows up is
     * the false-positive shadow (a TS7006 on each un-annotated callback parameter),
     * never a missing error at the import itself.
     */
    val moduleResolutions: Map<String, Map<String, String>> = emptyMap(),
)

/**
 * The option-derived per-file parser configuration (the [Parser] constructor
 * flags that change the produced tree). Computed by `computeParserFlags` from a
 * file's name/content and the resolved [CompilerOptions]; recorded alongside a
 * crawl-time parse so the compilation core can prove the parse matches the one
 * it would produce itself (INV.1(e)).
 */
data class ParserFlags(
    val forceJsx: Boolean,
    val topLevelAwait: Boolean,
    val needsJsxFlag: Boolean,
    val noImplicitAny: Boolean,
)

/**
 * A file's parse carried from the project crawl into the compilation core
 * (INV.1(e) — the crawl full-parses every file for import specifiers; without
 * this channel the core parses everything a second time).
 *
 * CONTRACT: [sourceFile]/[diagnostics] MUST be the result of parsing [content]
 * with exactly [flags] — the core verifies content and flags equality before
 * reusing, but cannot verify tree fidelity.
 */
class PreParsedFile(
    val content: String,
    val flags: ParserFlags,
    val sourceFile: SourceFile,
    val diagnostics: List<Diagnostic>,
)

/**
 * Parses `// @key: value` directives from the source header, returning the
 * [CompilerOptions] and the cleaned source (with directives and BOM stripped).
 */
fun parseCompilerOptions(source: String): Pair<CompilerOptions, String> {
    // Normalize line endings to LF to ensure consistent positions across platforms.
    // This matches parseMultiFileSource behavior and prevents \r\n mismatch in
    // diagnostic spans vs LF-normalized sourceLines in the error baseline formatter.
    val cleaned = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
    val lines = cleaned.split('\n')
    val directiveLines = mutableListOf<Int>()
    val directives = mutableMapOf<String, String>()

    for ((index, line) in lines.withIndex()) {
        val trimmed = line.trim().trimEnd('\r')
        if (trimmed.startsWith("// @") || trimmed.startsWith("//@")) {
            val content = if (trimmed.startsWith("// @")) {
                trimmed.removePrefix("// @")
            } else {
                trimmed.removePrefix("//@")
            }
            val colonIndex = content.indexOf(':')
            if (colonIndex >= 0) {
                val key = content.substring(0, colonIndex).trim().lowercase()
                val value = content.substring(colonIndex + 1).trim()
                directives[key] = value
                directiveLines.add(index)
            }
        }
    }

    val sourceLines = lines.filterIndexed { index, _ -> index !in directiveLines }
    // Drop leading truly-empty lines after directive removal, but preserve lines
    // that contain whitespace characters (they appear in baseline source echoes).
    val trimmedLines = sourceLines.dropWhile { it.trimEnd('\r').isEmpty() }
    val strippedSource = trimmedLines.joinToString("\n")

    var options = CompilerOptions()
    for ((key, value) in directives) {
        options = applyDirective(options, key, value)
    }

    options = applyImpliedAllowJs(options)

    return options to strippedSource
}

/**
 * Parses compiler options AND `// @Filename:` directives to split multi-file sources.
 * Returns [ParsedSource] with options and a list of source files.
 * If no `// @Filename:` directives are found, returns a single file with the test name.
 */
fun parseMultiFileSource(source: String, testFileName: String): ParsedSource {
    val cleaned = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
    val lines = cleaned.split('\n')
    val directives = mutableMapOf<String, String>()
    val symlinkMap = mutableMapOf<String, String>()
    // Per-file `// @symlink: pathA,pathB` targets (the file is ALSO present at those paths).
    // When the targets are NOT under node_modules, realpath is not used, so each symlink
    // acts like a distinct file (GH#10364, moduleResolutionWithSymlinks_notInNodeModules).
    val symlinkFileTargets = mutableMapOf<String, MutableList<String>>()
    val fileEntries = mutableListOf<SourceFileEntry>()
    var currentFileName: String? = null
    val currentLines = mutableListOf<String>()
    val globalDirectiveLines = mutableListOf<String>()
    var inGlobalDirectives = true

    for (line in lines) {
        val trimmed = line.trim().trimEnd('\r')
        if (trimmed.startsWith("// @") || trimmed.startsWith("//@")) {
            val content = if (trimmed.startsWith("// @")) {
                trimmed.removePrefix("// @")
            } else {
                trimmed.removePrefix("//@")
            }
            val colonIndex = content.indexOf(':')
            if (colonIndex >= 0) {
                val key = content.substring(0, colonIndex).trim().lowercase()
                val value = content.substring(colonIndex + 1).trim()
                if (key == "filename") {
                    // Start a new file
                    if (currentFileName != null) {
                        // Strip leading blank lines (artifacts of whitespace after the @filename directive)
                        val fileContent = currentLines.joinToString("\n").trimStart('\n', '\r')
                        // Skip empty file entries when the same filename immediately follows
                        // (duplicate @filename directives, e.g. in augmentExportEquals2.ts)
                        if (fileContent.isNotEmpty() || value != currentFileName) {
                            fileEntries.add(SourceFileEntry(currentFileName, fileContent))
                        }
                    }
                    // Clear any preamble lines collected before the first @Filename marker
                    currentLines.clear()
                    currentFileName = value
                    inGlobalDirectives = false
                } else if (key == "ts-ignore" || key == "ts-expect-error") {
                    // `// @ts-ignore: <text>` is a CODE comment-directive (suppression),
                    // not a harness option — keep it as source content
                    // (checkJsFiles_skipDiagnostics relies on the line surviving).
                    if (!inGlobalDirectives) currentLines.add(line)
                } else if (key == "link") {
                    // `// @link: <realDir> -> <.../node_modules/<pkg>>`: a package symlink.
                    // Record <pkg> -> <realDir> so a bare import of <pkg> resolves to the real
                    // source dir for dependency-ordering (symbolLinkDeclarationEmitModuleNames).
                    val parts = value.split("->").map { it.trim() }
                    if (parts.size == 2) {
                        val realDir = parts[0]
                        val pkg = parts[1].substringAfterLast("node_modules/")
                        if (pkg.isNotEmpty() && realDir.isNotEmpty()) symlinkMap[pkg] = realDir
                    }
                    if (inGlobalDirectives) globalDirectiveLines.add(line)
                } else if (key == "symlink" && currentFileName != null) {
                    // `// @symlink: pathA,pathB` (per-file): the current file is ALSO present
                    // at those symlink paths. Recorded here; when the targets are NOT under
                    // node_modules the file is re-registered at each target (see post-flush).
                    val targets = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (targets.isNotEmpty()) {
                        symlinkFileTargets.getOrPut(currentFileName) { mutableListOf() }.addAll(targets)
                    }
                } else {
                    directives[key] = value
                    if (inGlobalDirectives) {
                        globalDirectiveLines.add(line)
                    }
                }
            } else if (!inGlobalDirectives) {
                // No colon — not a key:value directive (e.g. // @ts-ignore, // @ts-expect-error)
                // Treat as regular source content
                currentLines.add(line)
            }
        } else {
            if (inGlobalDirectives && currentFileName == null) {
                // Non-directive line before any @Filename — part of first file only if non-empty
                if (trimmed.isNotEmpty()) {
                    inGlobalDirectives = false
                    currentLines.add(line)
                }
                // Skip blank lines that appear between global directives (before first @Filename)
            } else {
                currentLines.add(line)
            }
        }
    }

    // Flush the last file
    if (currentFileName != null) {
        val fileContent = currentLines.joinToString("\n").trimStart('\n', '\r')
        fileEntries.add(SourceFileEntry(currentFileName, fileContent))
    }

    // Non-node_modules `@symlink` targets: realpath is NOT used, so each symlink path acts
    // like a distinct file (GH#10364). Re-register the real file at each symlink target and
    // drop the real entry (it is only reachable via the symlinks in these fixtures). Gated to
    // targets outside node_modules — node_modules symlinks keep realpath dedup (handled by the
    // resolver) and are left untouched, so the passing moduleResolutionWithSymlinks* tests
    // (all node_modules targets) are unaffected.
    val symlinkSkipEcho = mutableSetOf<String>()
    if (symlinkFileTargets.isNotEmpty()) {
        val rebuilt = mutableListOf<SourceFileEntry>()
        for (entry in fileEntries) {
            val targets = symlinkFileTargets[entry.fileName]
            if (targets != null && targets.isNotEmpty() && targets.none { it.contains("node_modules/") }) {
                for ((i, t) in targets.withIndex()) {
                    rebuilt.add(SourceFileEntry(t, entry.content))
                    // The echo dedupes by realpath: keep the first instance, skip the rest.
                    if (i > 0) symlinkSkipEcho.add(t)
                }
            } else {
                rebuilt.add(entry)
            }
        }
        fileEntries.clear()
        fileEntries.addAll(rebuilt)
    }

    var options = CompilerOptions()

    // Apply options from tsconfig.json FIRST (if present in the file entries).
    // Test directives (// @target: etc.) are applied AFTER and take precedence.
    val tsconfigEntry = fileEntries.find { it.fileName.substringAfterLast('/') == "tsconfig.json" }
    if (tsconfigEntry != null) {
        // Resolve and apply `extends` chain (string or array of strings) before applying the
        // main tsconfig. Extended configs are loaded by path relative to the current tsconfig's
        // directory and are matched against file entries in the test's virtual filesystem.
        // Only direct (non-recursive) extends is handled; applying in declaration order so later
        // entries override earlier ones, then the main tsconfig overrides everything.
        val extendedContents = collectExtendedTsconfigs(tsconfigEntry, fileEntries, mutableSetOf())
        for ((extContent, extFileName) in extendedContents) {
            options = applyTsconfigOptions(options, extContent, extFileName)
        }
        // (LEGACY.1)(d) Option POSITIONS come from the ROOT tsconfig alone, as tsgo's
        // `createDiagnosticForOption` consults only the root's object literal: an option
        // inherited from an extended file anchors at the root's `"compilerOptions"` key
        // (`tsconfigAnchorFor`), never inside the extended file. The VALUES above stay
        // merged; only the extended files' positions are dropped — which is exactly what
        // [TsConfigLoader] records on the project path, so the two cannot drift.
        options = options.copy(tsconfigOptionPositions = emptyMap())
        options = applyTsconfigOptions(options, tsconfigEntry.content, tsconfigEntry.fileName)
    }

    for ((key, value) in directives) {
        options = applyDirective(options, key, value)
    }
    options = applyImpliedAllowJs(options)

    if (fileEntries.isEmpty()) {
        // Single-file test: use the original parseCompilerOptions for source cleanup
        val (_, cleanedSource) = parseCompilerOptions(source)
        return ParsedSource(options, listOf(SourceFileEntry(testFileName, cleanedSource)))
    }

    return ParsedSource(options, fileEntries, hasExplicitFilenames = true, symlinkMap = symlinkMap, symlinkSkipEcho = symlinkSkipEcho)
}

/**
 * TypeScript: `checkJs` implies `allowJs` when `allowJs` is not explicitly set
 * (`getAllowJSCompilerOption` returns `allowJs ?? !!checkJs`). Without this, the
 * `.js` files in a `@checkJs`-only program are never loaded into the program and
 * so never type-checked.
 */
internal fun applyImpliedAllowJs(options: CompilerOptions): CompilerOptions =
    if (options.checkJs && !options.allowJs && !options.allowJsExplicitlyFalse)
        options.copy(allowJs = true)
    else options

/**
 * The starting options for a REAL PROJECT BUILD (`TsConfigLoader.load`,
 * `ProjectCompiler.build`'s bare-source-file path) — as opposed to the generated
 * corpus suite, which starts from a bare [CompilerOptions] plus `@directives`.
 *
 * Round 730 (owner-approved 2026-07-26): a project build resolves the default
 * library from the REAL TypeScript lib `.d.ts` set. Before this, `useRealLibs`
 * defaulted false and NOTHING in the project path ever set it, so every real build
 * ran on the curated embedded `BUILTIN_LIB_SOURCE` — which declares no utility
 * types at all, so `Required<…>`/`Exclude<…>` silently degraded to `any`, and the
 * whole real-lib machinery was reachable only from a test directive.
 *
 * MEASURED before flipping (all 8 tsc-source profiles, `--noEmit --listAll`): the
 * embedded and real arms are IDENTICAL code-for-code — 46/46/46/46/46/46/46/94 —
 * and under `types: ["node"]` the real arm is strictly better (server 18 → 13,
 * harness 48 → 43). A tsconfig may still opt out with `"useRealLibs": false`,
 * since [applyDirective] runs after this.
 */
internal fun projectDefaults(): CompilerOptions = CompilerOptions(useRealLibs = true)

internal fun applyDirective(options: CompilerOptions, key: String, value: String): CompilerOptions {
    val boolValue = value.lowercase() == "true"
    return applyDirectiveArms1(options, key, value, boolValue)
        ?: applyDirectiveArms2(options, key, value, boolValue)
        ?: applyDirectiveArms3(options, key, value, boolValue)
        ?: applyDirectiveArms4(options, key, value, boolValue)
        ?: options
}

/**
 * (JIT.1)(e) round 815 — one contiguous run of [applyDirective]'s `when (key)`
 * arms, verbatim. Returns `null` for a key this run does not name, which is what
 * lets [applyDirective] chain the runs with `?:`; no arm ever evaluates to
 * `null` itself, and the arm keys are pairwise distinct, so the chain selects
 * exactly the arm the single `when` selected.
 */
private fun applyDirectiveArms1(
    options: CompilerOptions,
    key: String,
    value: String,
    boolValue: Boolean,
): CompilerOptions? {
    return when (key) {
        "target" -> {
            val written = value.split(",")[0].trim()
            val target = ScriptTarget.fromString(written)
            when {
                target != null -> options.copy(target = target, targetExplicitlySet = true)
                // (LEGACY.1)(j4) tsgo: an argument its `targetOptionMap` lacks (`es3`, a
                // typo) is TS6046 at the value and the option stays UNSET.
                written.isNotEmpty() -> options.copy(targetValueInvalid = true)
                else -> options
            }
        }

        "module" -> {
            val module = ModuleKind.fromString(value.trim())
            if (module != null) options.copy(module = module) else options
        }

        "strict" -> options.copy(strict = boolValue, strictExplicitlyFalse = !boolValue)
        "noemit" -> options.copy(noEmit = boolValue)
        "emitbom" -> options.copy(emitBOM = boolValue)
        "noemithelpers" -> options.copy(noEmitHelpers = boolValue)
        // M2.1(d): opt into the real TypeScript lib set (RealLibSnapshots) instead of
        // the embedded simplified lib. Test/bench-only until the M2.2 default flip.
        "usereallibs" -> options.copy(useRealLibs = boolValue)
        "declaration" -> options.copy(declaration = boolValue)
        "declarationdir" -> options.copy(declarationDir = value.trim())
        "declarationmap" -> options.copy(declarationMap = boolValue)
        "removecomments" -> options.copy(removeComments = boolValue)
        "preserveconstenums" -> options.copy(
            preserveConstEnums = boolValue,
            preserveConstEnumsExplicitlyFalse = !boolValue
        )
        "sourcemap" -> options.copy(sourceMap = boolValue)
        "noimplicitany" -> options.copy(noImplicitAny = boolValue, noImplicitAnyExplicitlyFalse = !boolValue)
        "noimplicitreturns" -> options.copy(noImplicitReturns = boolValue)
        else -> null
    }
}

/**
 * (JIT.1)(e) round 815 — one contiguous run of [applyDirective]'s `when (key)`
 * arms, verbatim. Returns `null` for a key this run does not name, which is what
 * lets [applyDirective] chain the runs with `?:`; no arm ever evaluates to
 * `null` itself, and the arm keys are pairwise distinct, so the chain selects
 * exactly the arm the single `when` selected.
 */
private fun applyDirectiveArms2(
    options: CompilerOptions,
    key: String,
    value: String,
    boolValue: Boolean,
): CompilerOptions? {
    return when (key) {
        "noimplicitthis" -> options.copy(noImplicitThis = boolValue, noImplicitThisExplicitlyFalse = !boolValue)
        "strictnullchecks" -> options.copy(strictNullChecks = boolValue, strictNullChecksExplicitlyFalse = !boolValue)
        "useunknownincatchvariables" -> options.copy(
            useUnknownInCatchVariables = boolValue,
            useUnknownInCatchVariablesExplicitlySet = true,
        )
        "strictbindcallapply" -> options.copy(
            strictBindCallApply = boolValue,
            strictBindCallApplyExplicitlySet = true,
        )
        "exactoptionalpropertytypes" -> options.copy(exactOptionalPropertyTypes = boolValue)
        "nouncheckedindexedaccess" -> options.copy(noUncheckedIndexedAccess = boolValue)
        "strictpropertyinitialization" -> options.copy(strictPropertyInitializationExplicitlyFalse = !boolValue)
        "nounusedlocals" -> options.copy(noUnusedLocals = boolValue)
        "nounusedparameters" -> options.copy(noUnusedParameters = boolValue)
        "experimentaldecorators" -> options.copy(experimentalDecorators = boolValue)
        "emitdecoratormetadata" -> options.copy(emitDecoratorMetadata = boolValue)
        "jsx" -> options.copy(jsx = value.trim())
        "jsxfactory" -> options.copy(jsxFactory = value.trim())
        "jsxfragmentfactory" -> options.copy(jsxFragmentFactory = value.trim())
        "reactnamespace" -> options.copy(reactNamespace = value.trim())
        "lib" -> options.copy(lib = value.split(",").map { it.trim() })
        "outdir" -> options.copy(outDir = value.trim())
        "rootdir" -> options.copy(rootDir = value.trim())
        "typeroots" -> options.copy(typeRoots = value.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        "types" -> options.copy(types = value.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        "baseurl" -> options.copy(baseUrl = value.trim())
        "moduleresolution" -> options.copy(moduleResolution = value.trim())
        // (LEGACY.1)(d2) a removed value: only the "written as false" marker is recorded.
        "esmoduleinterop" -> options.copy(esModuleInteropExplicitlyFalse = !boolValue)
        else -> null
    }
}

/**
 * (JIT.1)(e) round 815 — one contiguous run of [applyDirective]'s `when (key)`
 * arms, verbatim. Returns `null` for a key this run does not name, which is what
 * lets [applyDirective] chain the runs with `?:`; no arm ever evaluates to
 * `null` itself, and the arm keys are pairwise distinct, so the chain selects
 * exactly the arm the single `when` selected.
 */
private fun applyDirectiveArms3(
    options: CompilerOptions,
    key: String,
    value: String,
    boolValue: Boolean,
): CompilerOptions? {
    return when (key) {
        "allowjs" -> options.copy(
            allowJs = boolValue,
            allowJsExplicitlyFalse = !boolValue
        )
        "checkjs" -> options.copy(checkJs = boolValue)
        "isolatedmodules" -> options.copy(isolatedModules = boolValue)
        "skiplibcheck" -> options.copy(skipLibCheck = boolValue)
        "forceconsistentcasinginfilenames" -> options.copy(forceConsistentCasingInFileNames = boolValue)
        "noemitonerror" -> options.copy(noEmitOnError = boolValue)
        "downleveliteration" -> options.copy(downlevelIterationExplicitlySet = true) // (LEGACY.1)(i) removed option: the value is not recorded
        "importhelpers" -> options.copy(importHelpers = boolValue)
        // (LEGACY.1)(d2) a removed value: only the "written as false" marker is recorded.
        "allowsyntheticdefaultimports" -> options.copy(allowSyntheticDefaultImportsExplicitlyFalse = !boolValue)
        "usedefineforclassfields" -> options.copy(useDefineForClassFields = boolValue)
        "verbatimmodulesyntax" -> options.copy(verbatimModuleSyntax = boolValue)
        "nocheck" -> options.copy(noCheck = boolValue)
        "emitdeclarationonly" -> options.copy(emitDeclarationOnly = boolValue)
        "maproot" -> options.copy(mapRoot = value.trim())
        "outfile" -> options.copy(outFile = value.trim())
        "out" -> options.copy(out = value.trim()) // 'out' is removed (TS5102), don't set outFile
        "alwaysstrict" -> options.copy(alwaysStrict = boolValue)
        "newline" -> options.copy(newLine = value.trim())
        "fullemitpaths" -> options.copy(fullEmitPaths = boolValue)
        "allowunreachablecode" -> options.copy(allowUnreachableCode = boolValue)
        "allowunusedlabels" -> options.copy(allowUnusedLabels = boolValue)
        "nofallthroughcasesinswitch" -> options.copy(noFallthroughCasesInSwitch = boolValue)
        else -> null
    }
}

/**
 * (JIT.1)(e) round 815 — one contiguous run of [applyDirective]'s `when (key)`
 * arms, verbatim. Returns `null` for a key this run does not name, which is what
 * lets [applyDirective] chain the runs with `?:`; no arm ever evaluates to
 * `null` itself, and the arm keys are pairwise distinct, so the chain selects
 * exactly the arm the single `when` selected.
 */
private fun applyDirectiveArms4(
    options: CompilerOptions,
    key: String,
    value: String,
    boolValue: Boolean,
): CompilerOptions? {
    return when (key) {
        "noresolve" -> options.copy(noResolve = boolValue)
        "noimplicitreferences" -> options.copy(noImplicitReferences = boolValue)
        "moduledetection" -> options.copy(moduleDetection = value.trim())
        "charset" -> options.copy(charset = value.trim())
        "keyofstringsonly" -> options.copy(keyofStringsOnly = boolValue)
        "noimplicitusestrict" -> options.copy(noImplicitUseStrict = boolValue)
        "nostrictgenericchecks" -> options.copy(noStrictGenericChecks = boolValue)
        "suppressexcesspropertyerrors" -> options.copy(suppressExcessPropertyErrors = boolValue)
        "suppressimplicitanyindexerrors" -> options.copy(suppressImplicitAnyIndexErrors = boolValue)
        "importsnotusedasvalues" -> options.copy(importsNotUsedAsValues = value.trim())
        "preservevalueimports" -> options.copy(preserveValueImports = boolValue)
        "resolvejsonmodule" -> options.copy(resolveJsonModule = boolValue)
        "nolib" -> options.copy(noLib = boolValue)
        "inlinesourcemap" -> options.copy(inlineSourceMap = boolValue)
        "inlinesources" -> options.copy(inlineSources = boolValue)
        "sourceroot" -> options.copy(sourceRoot = value.trim())
        "composite" -> options.copy(composite = boolValue)
        "pretty" -> options.copy(pretty = boolValue)
        "incremental" -> options.copy(incremental = boolValue)
        "isolateddeclarations" -> options.copy(isolatedDeclarations = boolValue)
        "erasablesyntaxonly" -> options.copy(erasableSyntaxOnly = boolValue)
        "ignoredeprecations" -> options.copy(ignoreDeprecations = value.trim())
        "typescriptversion" -> options.copy(simulatedTypeScriptVersion = value.trim())
        "allowimportingtsextensions" -> options.copy(allowImportingTsExtensions = boolValue)
        "rewriterelativeimportextensions" -> options.copy(rewriteRelativeImportExtensions = boolValue)
        "capturesuggestions" -> options.copy(captureSuggestions = boolValue)
        else -> null
    }
}

/**
 * Resolves the `extends` chain of a tsconfig.json by walking referenced files in the test's
 * virtual filesystem. Supports both string form (`"extends": "./base"`) and array form
 * (`"extends": ["./a.json", "./b.json"]`). Paths are resolved relative to the current
 * tsconfig's directory. Returns `(content, fileName)` pairs in APPLICATION order — later
 * entries override earlier ones. Recursion is bounded by `visited` to avoid cycles.
 */
private fun collectExtendedTsconfigs(
    tsconfigEntry: SourceFileEntry,
    fileEntries: List<SourceFileEntry>,
    visited: MutableSet<String>,
): List<Pair<String, String>> {
    if (!visited.add(tsconfigEntry.fileName)) return emptyList()
    val json = tsconfigEntry.content
    val extendsIdx = json.indexOf("\"extends\"")
    if (extendsIdx < 0) return emptyList()
    // Find the value after `"extends"` — skip `:` and whitespace.
    var valueStart = extendsIdx + "\"extends\"".length
    while (valueStart < json.length && (json[valueStart].isWhitespace() || json[valueStart] == ':')) valueStart++
    if (valueStart >= json.length) return emptyList()
    val specifiers = mutableListOf<String>()
    when (json[valueStart]) {
        '"' -> {
            val end = json.indexOf('"', valueStart + 1)
            if (end > valueStart) specifiers.add(json.substring(valueStart + 1, end))
        }
        '[' -> {
            val end = json.indexOf(']', valueStart)
            if (end > valueStart) {
                val arrayContent = json.substring(valueStart + 1, end)
                val itemPattern = Regex(""""([^"]*)"""")
                for (m in itemPattern.findAll(arrayContent)) specifiers.add(m.groupValues[1])
            }
        }
    }
    if (specifiers.isEmpty()) return emptyList()
    val currentDir = tsconfigEntry.fileName.substringBeforeLast('/', "")
    val result = mutableListOf<Pair<String, String>>()
    for (spec in specifiers) {
        val normalized = resolveTsconfigPath(currentDir, spec)
        // Try to find the extended tsconfig in fileEntries. Match by exact name, then by basename.
        val extEntry = fileEntries.firstOrNull { it.fileName == normalized }
            ?: fileEntries.firstOrNull { it.fileName.substringAfterLast('/') == normalized.substringAfterLast('/') }
            ?: continue
        // Recursive extends: apply the grand-parent's options first.
        result.addAll(collectExtendedTsconfigs(extEntry, fileEntries, visited))
        result.add(extEntry.content to extEntry.fileName)
    }
    return result
}

private fun resolveTsconfigPath(baseDir: String, spec: String): String {
    // Strip leading `./`; collapse `../` against baseDir. Non-relative specifiers (package-style)
    // are returned as-is — they won't match any file entry and will be silently skipped.
    val specWithJson = if (spec.endsWith(".json")) spec else "$spec.json"
    if (!specWithJson.startsWith("./") && !specWithJson.startsWith("../")) return specWithJson
    val parts = mutableListOf<String>()
    if (baseDir.isNotEmpty()) parts.addAll(baseDir.split('/').filter { it.isNotEmpty() })
    val trimmed = specWithJson.removePrefix("./")
    for (segment in trimmed.split('/')) {
        when (segment) {
            "" -> {}
            "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(segment)
        }
    }
    return "/" + parts.joinToString("/")
}

/**
 * Parses a tsconfig.json content and applies its `compilerOptions` to the given options.
 * Uses simple string matching rather than a full JSON parser.
 * Tracks option value positions for emitting positioned deprecation diagnostics.
 */
/**
 * One `"key": value` pair of a tsconfig's `compilerOptions` block, with the KEY's and the
 * VALUE's positions in the WHOLE file's text: `keyStart`/`keyLength` cover the key
 * including its quotes, `valueStart`/`valueLength` the value including the quotes of a
 * string one. Produced by [scanCompilerOptionsBlock], the one scanner both tsconfig
 * paths share.
 */
private class TsconfigKvMatch(
    val key: String, val value: String,
    val keyStart: Int, val keyLength: Int,
    val valueStart: Int, val valueLength: Int,
)

/** [scanCompilerOptionsBlock]'s answer: where the block sits, its text, and its pairs. */
private class CompilerOptionsBlockScan(
    val compilerOptionsKeyStart: Int,
    val blockStart: Int,
    val block: String,
    val kvMatches: List<TsconfigKvMatch>,
)

/**
 * Finds the `"compilerOptions": { … }` block of a tsconfig text and scans its scalar
 * `"key": value` pairs (string, boolean, number), recording each key's and value's
 * position in [json]. Null when the text has no `compilerOptions` block.
 *
 * (LEGACY.1)(d) THIS IS THE ONE SCANNER BEHIND EVERY TSCONFIG-ANCHORED DIAGNOSTIC — the
 * corpus harness's embedded `@Filename: tsconfig.json` fixtures ([applyTsconfigOptions])
 * and a real project's `tsconfig.json` ([TsConfigLoader], through
 * [tsconfigOptionPositionsOf]) both read positions from here, so the two paths cannot
 * drift: a removed-option row anchors at the same token whichever way the config arrived.
 * It is a TEXT scan, not a JSON parse, so a pair inside a `//` comment is recorded too
 * (last write wins); tsgo parses the JSONC and would ignore it.
 */
private fun scanCompilerOptionsBlock(json: String): CompilerOptionsBlockScan? {
    // Extract the compilerOptions block
    val compilerOptionsStart = json.indexOf("\"compilerOptions\"")
    if (compilerOptionsStart < 0) return null

    val braceStart = json.indexOf('{', compilerOptionsStart + "\"compilerOptions\"".length)
    if (braceStart < 0) return null

    // Find matching closing brace
    var depth = 1
    var pos = braceStart + 1
    while (pos < json.length && depth > 0) {
        when (json[pos]) {
            '{' -> depth++
            '}' -> depth--
        }
        pos++
    }
    val blockStart = braceStart + 1
    val compilerOptionsBlock = json.substring(blockStart, pos - 1)

    // Parse key-value pairs from the block, tracking positions relative to the full JSON
    // keyStart/keyLength point to the option KEY (e.g., "baseUrl"), valueStart/valueLength to the VALUE
    val kvPattern = Regex(""""(\w+)"\s*:\s*("([^"]*)"|(true|false)|(\d+))""")
    val kvMatches = mutableListOf<TsconfigKvMatch>()
    for (match in kvPattern.findAll(compilerOptionsBlock)) {
        val key = match.groupValues[1].lowercase()
        val value = match.groupValues[3].ifEmpty {
            match.groupValues[4].ifEmpty {
                match.groupValues[5]
            }
        }
        // Key position: full match starts at the opening quote of the key
        val keyStartInJson = blockStart + match.range.first
        val keyLength = match.groupValues[1].length + 2 // +2 for quotes
        // Value position: group 2 is the full value (including quotes for strings)
        val valueGroup = match.groups[2]!!
        val valueStartInJson = blockStart + valueGroup.range.first
        val valueLength = valueGroup.range.last - valueGroup.range.first + 1
        kvMatches.add(TsconfigKvMatch(key, value, keyStartInJson, keyLength, valueStartInJson, valueLength))
    }
    return CompilerOptionsBlockScan(compilerOptionsStart, blockStart, compilerOptionsBlock, kvMatches)
}

/**
 * tsgo's `tspath.PathIsRelative` (`internal/tspath/path.go:904`): a bare `.` or `..`, or a
 * path starting `./`, `../`, `.\` or `..\`.
 *
 * (LEGACY.1)(g) The `startsWith("./") || startsWith("../")` test this replaces was a false
 * POSITIVE on all four of the shapes it omits — measured one per row through
 * `tools/tsgo-7.0.2/lib/tsc`, which reports NO TS5090 for any of them.
 */
internal fun pathSubstitutionIsRelative(sub: String): Boolean =
    sub == "." || sub == ".." ||
        sub.startsWith("./") || sub.startsWith(".\\") ||
        sub.startsWith("../") || sub.startsWith("..\\")

/**
 * tsgo's `tspath.PathIsAbsolute` — `GetEncodedRootLength(path) != 0`
 * (`internal/tspath/path.go:66, 168`): a POSIX or UNC root, a DOS drive (`c:`, `c:/`), an
 * untitled `^/` path, or a URL with a scheme.
 *
 * (LEGACY.1)(g) Ours had NO absolute arm at all, so a POSIX-rooted, a DOS-drive and a
 * `file:` URL substitution (each ending in a star segment) all read as non-relative and
 * reported TS5090 where tsgo is silent — measured one per row, and spelled in prose here
 * only because a slash-star opens a NESTED Kotlin block comment (see CLAUDE.md);
 * `BaseUrlRemovedTest` carries the literals. It is load-bearing precisely BECAUSE the
 * `baseUrl` gate on the TS5090 block went: that gate used to hide the gap on every project
 * that set `baseUrl`, which is most of the `paths`-using corpus.
 */
internal fun pathSubstitutionIsRooted(sub: String): Boolean {
    if (sub.isEmpty()) return false
    val c0 = sub[0]
    // POSIX or UNC: "/", "\", "//server", "\\server"
    if (c0 == '/' || c0 == '\\') return true
    // DOS: "c:" (but not "c:d"), "c:/", "c:\"
    if (sub.length >= 2 && sub[1] == ':' &&
        ((c0 in 'a'..'z') || (c0 in 'A'..'Z'))
    ) {
        if (sub.length == 2) return true
        val c2 = sub[2]
        if (c2 == '/' || c2 == '\\') return true
    }
    // Untitled: "^/"
    if (c0 == '^' && sub.length > 1 && sub[1] == '/') return true
    // URL: a scheme separator followed by an authority that is itself terminated.
    val schemeEnd = sub.indexOf("://")
    if (schemeEnd != -1 && sub.indexOf('/', schemeEnd + 3) != -1) return true
    return false
}

/**
 * The `(key, value)` positions of every option in [json]'s `compilerOptions` block that
 * the compiler models (the [allowedTsconfigOptions] subset), keyed by the LOWERCASED
 * option name, plus the synthetic `compileroptionskey` entry pointing at the
 * `"compilerOptions"` key itself — the anchor tsgo's `createCompilerOptionsDiagnostic`
 * uses for an option that is set but not written in THIS file (inherited through
 * `extends`, or given on the command line). Empty when the text has no
 * `compilerOptions` block, which is when tsgo reports such a row file-less.
 *
 * [tsconfigFileName] is the name every recorded position carries: the harness passes
 * the fixture's `@Filename`, [TsConfigLoader] the normalized absolute path it read.
 */
internal fun tsconfigOptionPositionsOf(json: String, tsconfigFileName: String): Map<String, TsconfigOptionPosition> {
    val scan = scanCompilerOptionsBlock(json) ?: return emptyMap()
    // Compute line/column positions for option keys and values in the tsconfig JSON
    val optionPositions = mutableMapOf<String, TsconfigOptionPosition>()
    // Add a synthetic "compileroptionskey" entry pointing to the "compilerOptions" key itself.
    // This is used as a fallback position for TS5107 deprecation diagnostics when the deprecated
    // option is set via CLI/test directive (not in tsconfig), but a tsconfig is present.
    // TypeScript attributes such CLI-level deprecated options to the "compilerOptions" key position.
    val compilerOptionsKeyStart = scan.compilerOptionsKeyStart
    val keyLength = "\"compilerOptions\"".length
    val keyLineCol = computeLineAndColumn(json, compilerOptionsKeyStart)
    optionPositions["compileroptionskey"] = TsconfigOptionPosition(
        fileName = tsconfigFileName,
        keyLine = keyLineCol.first,
        keyCharacter = keyLineCol.second,
        keyStart = compilerOptionsKeyStart,
        keyLength = keyLength,
        valueLine = keyLineCol.first,
        valueCharacter = keyLineCol.second,
        valueStart = compilerOptionsKeyStart,
        valueLength = keyLength,
    )
    for (kv in scan.kvMatches) {
        if (kv.key in allowedTsconfigOptions) {
            val keyLineCol = computeLineAndColumn(json, kv.keyStart)
            val valueLineCol = computeLineAndColumn(json, kv.valueStart)
            optionPositions[kv.key] = TsconfigOptionPosition(
                fileName = tsconfigFileName,
                keyLine = keyLineCol.first,
                keyCharacter = keyLineCol.second,
                keyStart = kv.keyStart,
                keyLength = kv.keyLength,
                valueLine = valueLineCol.first,
                valueCharacter = valueLineCol.second,
                valueStart = kv.valueStart,
                valueLength = kv.valueLength,
            )
        }
    }
    return optionPositions
}

// Only apply a safe subset of tsconfig options that our transpiler handles correctly.
private val allowedTsconfigOptions = setOf(
    "target", "module", "strict", "noemit", "noemithelpers",
    "declaration", "declarationmap", "removecomments", "preserveconstenums", "sourcemap",
    "experimentaldecorators", "emitdecoratormetadata", "jsx", "jsxfactory", "jsxfragmentfactory", "reactnamespace",
    "esmoduleinterop", "isolatedmodules", "downleveliteration",
    "importhelpers", "allowsyntheticdefaultimports", "usedefineforclassfields",
    "verbatimmodulesyntax", "emitdeclarationonly", "outfile",
    "alwaysstrict", "newline", "noresolve", "moduledetection",
    "outdir", "rootdir", "allowjs", "ignoredeprecations", "moduleresolution",
    // Deprecated/removed options needed for diagnostics
    "charset", "keyofstringsonly", "noimplicitusestrict", "nostrictgenericchecks",
    "suppressexcesspropertyerrors", "suppressimplicitanyindexerrors",
    "out", "importsnotusedasvalues", "preservevalueimports",
    "noimplicitany", "noimplicitreturns", "strictnullchecks",
    "nounusedlocals", "nounusedparameters", "baseurl",
    "resolvejsonmodule", "inlinesourcemap", "sourcemap", "maproot",
    "declarationdir",
)

private fun applyTsconfigOptions(options: CompilerOptions, json: String, tsconfigFileName: String = "tsconfig.json"): CompilerOptions {
    val scan = scanCompilerOptionsBlock(json) ?: return options
    val blockStart = scan.blockStart
    val compilerOptionsBlock = scan.block
    val kvMatches = scan.kvMatches

    // Parse array-valued options (e.g. moduleSuffixes: [".ios", ""])
    val arrayPattern = Regex(""""(\w+)"\s*:\s*\[([^\]]*)]""")
    val arrayPairs = mutableListOf<Pair<String, List<String>>>()
    for (match in arrayPattern.findAll(compilerOptionsBlock)) {
        val key = match.groupValues[1].lowercase()
        val items = Regex(""""([^"]*)"""").findAll(match.groupValues[2])
            .map { it.groupValues[1] }.toList()
        arrayPairs.add(key to items)
    }

    // Parse and validate "paths" object from compilerOptions (nested object with pattern→substitutions)
    val pathsDiagnostics = mutableListOf<Diagnostic>()
    val parsedPaths = mutableMapOf<String, List<String>>()
    val pathsKeyIdx = compilerOptionsBlock.indexOf("\"paths\"")
    if (pathsKeyIdx >= 0) {
        val pathsBraceStart = compilerOptionsBlock.indexOf('{', pathsKeyIdx + "\"paths\"".length)
        if (pathsBraceStart >= 0) {
            // Find matching closing brace for the paths object
            var d = 1
            var p = pathsBraceStart + 1
            while (p < compilerOptionsBlock.length && d > 0) {
                when (compilerOptionsBlock[p]) {
                    '{' -> d++
                    '}' -> d--
                }
                p++
            }
            val pathsBlock = compilerOptionsBlock.substring(pathsBraceStart + 1, p - 1)
            val pathsBlockOffset = blockStart + pathsBraceStart + 1

            // Find each pattern entry: "pattern": value
            val entryPattern = Regex(""""([^"]*)"(\s*:\s*)""")
            for (entryMatch in entryPattern.findAll(pathsBlock)) {
                val pattern = entryMatch.groupValues[1]
                val afterColon = entryMatch.range.last + 1

                // Determine value type and position
                var valueStart = afterColon
                while (valueStart < pathsBlock.length && pathsBlock[valueStart].isWhitespace()) valueStart++
                if (valueStart >= pathsBlock.length) continue

                when (pathsBlock[valueStart]) {
                    '[' -> {
                        // Array value — find closing bracket
                        val bracketEnd = pathsBlock.indexOf(']', valueStart)
                        if (bracketEnd < 0) continue
                        val arrayContent = pathsBlock.substring(valueStart + 1, bracketEnd)

                        // Check for empty array (TS5066)
                        val items = mutableListOf<String>()
                        val itemPattern = Regex(""""([^"]*)"""")

                        for (itemMatch in itemPattern.findAll(arrayContent)) {
                            items.add(itemMatch.groupValues[1])
                        }

                        // Check for non-string elements (TS5064)
                        // Look for bare numbers in array
                        val allTokens = Regex("""[^\s,\[\]]+|"[^"]*"""").findAll(arrayContent)
                        for (token in allTokens) {
                            val t = token.value.trim()
                            if (t.isEmpty()) continue
                            if (t.startsWith("\"")) continue // string — ok
                            // Non-string element
                            val elemAbsPos = pathsBlockOffset + valueStart + 1 + token.range.first
                            val elemLineCol = computeLineAndColumn(json, elemAbsPos)
                            pathsDiagnostics.add(Diagnostic(
                                message = "Substitution '$t' for pattern '$pattern' has incorrect type, expected 'string', got 'number'.",
                                category = DiagnosticCategory.Error,
                                code = 5064,
                                fileName = tsconfigFileName,
                                line = elemLineCol.first,
                                character = elemLineCol.second,
                                start = elemAbsPos,
                                length = t.length,
                            ))
                        }

                        if (items.isEmpty() && pathsDiagnostics.none { it.code == 5064 }) {
                            // TS5066: empty array
                            val absPos = pathsBlockOffset + valueStart
                            val lineCol = computeLineAndColumn(json, absPos)
                            pathsDiagnostics.add(Diagnostic(
                                message = "Substitutions for pattern '$pattern' shouldn't be an empty array.",
                                category = DiagnosticCategory.Error,
                                code = 5066,
                                fileName = tsconfigFileName,
                                line = lineCol.first,
                                character = lineCol.second,
                                start = absPos,
                                length = bracketEnd - valueStart + 1,
                            ))
                        }

                        parsedPaths[pattern] = items

                        // TS5061/5062: pattern or substitution has more than one '*'
                        if (pattern.count { it == '*' } > 1) {
                            val patKeyAbsPos = pathsBlockOffset + entryMatch.range.first
                            val patKeyLineCol = computeLineAndColumn(json, patKeyAbsPos)
                            pathsDiagnostics.add(Diagnostic(
                                message = "Pattern '$pattern' can have at most one '*' character.",
                                category = DiagnosticCategory.Error,
                                code = 5061,
                                fileName = tsconfigFileName,
                                line = patKeyLineCol.first,
                                character = patKeyLineCol.second,
                                start = patKeyAbsPos,
                                length = pattern.length + 2, // +2 for quotes
                            ))
                        }
                        for (itemMatch in itemPattern.findAll(arrayContent)) {
                            val sub = itemMatch.groupValues[1]
                            if (sub.count { it == '*' } > 1) {
                                val subAbsPos = pathsBlockOffset + valueStart + 1 + itemMatch.range.first
                                val subLineCol = computeLineAndColumn(json, subAbsPos)
                                pathsDiagnostics.add(Diagnostic(
                                    message = "Substitution '$sub' in pattern '$pattern' can have at most one '*' character.",
                                    category = DiagnosticCategory.Error,
                                    code = 5062,
                                    fileName = tsconfigFileName,
                                    line = subLineCol.first,
                                    character = subLineCol.second,
                                    start = subAbsPos,
                                    length = sub.length + 2, // +2 for quotes
                                ))
                            }
                        }
                    }
                    '"' -> {
                        // String value (not array) — TS5063
                        val strEnd = pathsBlock.indexOf('"', valueStart + 1)
                        if (strEnd < 0) continue
                        val absPos = pathsBlockOffset + valueStart
                        val lineCol = computeLineAndColumn(json, absPos)
                        pathsDiagnostics.add(Diagnostic(
                            message = "Substitutions for pattern '$pattern' should be an array.",
                            category = DiagnosticCategory.Error,
                            code = 5063,
                            fileName = tsconfigFileName,
                            line = lineCol.first,
                            character = lineCol.second,
                            start = absPos,
                            length = strEnd - valueStart + 1,
                        ))
                    }
                }
            }
        }
    }

    // The option positions, from the scanner both tsconfig paths share.
    val optionPositions = tsconfigOptionPositionsOf(json, tsconfigFileName)

    var result = options
    for (kv in kvMatches) {
        if (kv.key !in allowedTsconfigOptions) continue
        result = applyDirective(result, kv.key, kv.value)
    }
    // Apply array options
    for ((key, values) in arrayPairs) {
        result = when (key) {
            "modulesuffixes" -> result.copy(moduleSuffixes = values)
            "rootdirs" -> result.copy(rootDirs = values)
            else -> result
        }
    }
    // Apply parsed paths
    if (parsedPaths.isNotEmpty()) {
        result = result.copy(paths = parsedPaths)
    }

    // TS5090. tsgo emits it from `program.go:995`, inside the loop over `options.Paths`,
    // and it is NOT gated on `baseUrl` — measured: a project setting BOTH `baseUrl` and a
    // non-relative substitution gets the TS5102 baseUrl row AND this one. (LEGACY.1)(g)
    // dropped the `result.baseUrl == null` conjunct that used to stand here: with `baseUrl`
    // honoured nowhere it could only suppress a true positive.
    if (parsedPaths.isNotEmpty() && pathsKeyIdx >= 0) {
        val pathsBraceStart = compilerOptionsBlock.indexOf('{', pathsKeyIdx + "\"paths\"".length)
        if (pathsBraceStart >= 0) {
            var d = 1
            var p = pathsBraceStart + 1
            while (p < compilerOptionsBlock.length && d > 0) {
                when (compilerOptionsBlock[p]) { '{' -> d++; '}' -> d-- }
                p++
            }
            val pathsBlock = compilerOptionsBlock.substring(pathsBraceStart + 1, p - 1)
            val pathsBlockOffset = blockStart + pathsBraceStart + 1
            // Find substitution strings and check if they're non-relative
            val subPattern = Regex(""""([^"]*)"(\s*:\s*)\[([^\]]*)]""")
            for (entryMatch in subPattern.findAll(pathsBlock)) {
                val arrayContent = entryMatch.groupValues[3]
                val arrayStartInBlock = entryMatch.range.first + entryMatch.groupValues[1].length + 2 + entryMatch.groupValues[2].length + 1
                val itemPattern = Regex(""""([^"]*)"""")
                for (itemMatch in itemPattern.findAll(arrayContent)) {
                    val sub = itemMatch.groupValues[1]
                    if (!pathSubstitutionIsRelative(sub) && !pathSubstitutionIsRooted(sub)) {
                        val absPos = pathsBlockOffset + arrayStartInBlock + itemMatch.range.first
                        val lineCol = computeLineAndColumn(json, absPos)
                        pathsDiagnostics.add(Diagnostic(
                            message = "Non-relative paths are not allowed. Did you forget a leading './'?",
                            category = DiagnosticCategory.Error,
                            code = 5090,
                            fileName = tsconfigFileName,
                            line = lineCol.first,
                            character = lineCol.second,
                            start = absPos,
                            length = sub.length + 2, // +2 for quotes
                        ))
                    }
                }
            }
        }
    }

    // Store paths diagnostics
    if (pathsDiagnostics.isNotEmpty()) {
        result = result.copy(pathsDiagnostics = pathsDiagnostics)
    }

    // Merge tsconfig option positions into the result
    if (optionPositions.isNotEmpty()) {
        result = result.copy(tsconfigOptionPositions = result.tsconfigOptionPositions + optionPositions)
    }
    return result
}

/** Compute 1-based line and 1-based column for a 0-based offset in text.
 *  The compiler's ONE offset-to-line conversion ([lineAndCharacterAt]); this used to
 *  be a fifth private copy of it, which broke a line at `\n` only and treated a `\r`
 *  as zero-width (round 915). Identical for every token position in `\n` and `\r\n`
 *  text — a `\r` only ever precedes the `\n` that closes the same line, so it is
 *  never inside the span whose column is being counted. */
private fun computeLineAndColumn(text: String, offset: Int): Pair<Int, Int> =
    lineAndCharacterAt(text, offset)
