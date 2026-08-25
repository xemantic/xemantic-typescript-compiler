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

enum class ScriptTarget {
    ES3, ES5, ES2015, ES2016, ES2017, ES2018, ES2019, ES2020, ES2021, ES2022, ES2023, ES2024, ESNext;

    companion object {
        fun fromString(value: String): ScriptTarget? = when (value.lowercase()) {
            "es3" -> ES3
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
    val target: ScriptTarget = ScriptTarget.ES3,
    val targetExplicitlySet: Boolean = false,
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
    val esModuleInterop: Boolean = true,
    val esModuleInteropExplicitlyFalse: Boolean = false,
    val allowSyntheticDefaultImportsExplicitlyFalse: Boolean = false,
    val allowJs: Boolean = false,
    val allowJsExplicitlyFalse: Boolean = false,
    val checkJs: Boolean = false,
    val isolatedModules: Boolean = false,
    val skipLibCheck: Boolean = false,
    val forceConsistentCasingInFileNames: Boolean = false,
    val noEmitOnError: Boolean = false,
    val downlevelIteration: Boolean = false,
    val downlevelIterationExplicitlySet: Boolean = false,
    val importHelpers: Boolean = false,
    val allowSyntheticDefaultImports: Boolean = false,
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

    val effectiveTarget: ScriptTarget
        // tsc getEmitScriptTarget: an UNSET target (or ES3) maps to LatestStandard (ES2025).
        // We use ES2024 (our top standard target) for an unset target — so emit keeps native
        // class fields / async / spread (useDefineForClassFields ≥ ES2022 → true). An EXPLICIT
        // ES3/ES5 still maps to ES2015 (legacy downlevel). **This is the EMIT dimension ONLY**:
        // every CHECKER question — lib availability (round 944) and the `target < ES2015`
        // downlevel gates (round 945) — is [defaultedTarget], which differs from this one at
        // an explicit es3/es5 and agrees with it everywhere else.
        get() = when {
            !targetExplicitlySet -> ScriptTarget.ES2024
            target <= ScriptTarget.ES5 -> ScriptTarget.ES2015
            else -> target
        }

    /**
     * The target every **checker** question is decided against — tsc's own
     * `getEmitScriptTarget`, which is the single notion its whole checker reads
     * (`var languageVersion = getEmitScriptTarget(compilerOptions)`).
     *
     * Two consumer families, landed one round apart and measured separately because
     * their divergences have OPPOSITE signs:
     *  - **lib availability** ((CHK.17), round 944): which default lib set is loaded,
     *    whether a later-lib global resolves (TS2583/TS2585), whether a later-lib
     *    interface member is filtered out of its interface (TS2550). Reading the raw
     *    target there was a FALSE-POSITIVE family — 3 rows over the pristine sweep.
     *  - **the `target < ES2015` DOWNLEVEL gates** ((CHK.21), round 945; 23 lines): TS1250, TS2802,
     *    TS2737, TS1501/TS1503, TS2659/TS2660, TS2340/TS2855, TS2396, TS18045, TS2318,
     *    the TS2488/TS2461 message fork and the tslib-helper checks. Reading the raw
     *    target there was a FALSE-NEGATIVE family — 4 pristine-only TS2488 rows, because
     *    an unset target read as ES3 SUPPRESSED checks tsc runs at its default.
     *
     * (Round 944 introduced this as `libTarget`; the name was renamed in round 945 when
     * the second family joined, because it no longer names its only consumer.)
     *
     * NOT every raw-`options.target` read is one of these. A gate whose shape is
     * `target >= ES2015 || <other disjuncts>` — the strict-mode determinations
     * `spineDelIsStrict` / `spineStrictFileIsExprStrict` — is a MIS-TRANSCRIPTION of
     * tsc's nested rule that is CORRECT only while the raw target reads ES3 at an unset
     * target: flipping those makes every file strict. They keep the raw target
     * deliberately, and so does `checkOperationsAvailableOnPromisedType`, a per-fixture
     * baseline pin rather than a semantic gate.
     *
     * tsc's definition, read off the pinned sources (`utilities.ts` `_computedOptions`):
     * `const target = options.target === ES3 ? undefined : options.target;
     * return target ?? ScriptTarget.LatestStandard`. It picks the default lib from THAT
     * (`getDefaultLibFileName`: unset -> `lib.es2025.full.d.ts`) and it is also the
     * `languageVersion` its checker compares against ES2015 everywhere. Our top standard
     * target is ES2024, so an unset target answers ES2024 here — the same value
     * [effectiveTarget] already gives the emitter, which is what makes the two
     * dimensions agree for a project that names no target.
     *
     * **Not [effectiveTarget]**: that maps an EXPLICIT `es3`/`es5` UP to ES2015, which
     * would hand an `@target: es5` program the ES2015 lib and delete every genuine
     * TS2550/TS2583 it is supposed to get. Round 941 met the identical fork at TS18028
     * and refused `effectiveTarget` for the same reason. The downlevel gates refuse it
     * for the MIRROR reason: an explicit `es5` mapped up to ES2015 would OPEN every
     * `target < ES2015` gate that tsc keeps shut for that program — manufacturing false
     * positives (and flipping TS2461 to TS2488) on exactly the projects the gates exist
     * for. **Not the raw [target]**
     * either: its `ES3` zero value is indistinguishable from "the user said nothing",
     * which is what made `Cannot find name 'AsyncIterableIterator'. Do you need to
     * change your target library?` fire on a tsconfig with no `target` at all.
     *
     * An EXPLICIT `es3` stays ES3 here where tsc 6 answers LatestStandard for it too
     * (it dropped ES3 as a target). Neither instrument can observe that — the corpus
     * skips every explicit es3/es5 config (`usesUnsupportedOption`) and no pristine
     * fixture sets `@target: es3` — and keeping it raw is consistent with the
     * `target <= ES5` gates beside it.
     */
    val defaultedTarget: ScriptTarget
        get() = if (targetExplicitlySet) target else ScriptTarget.ES2024

    val effectiveModule: ModuleKind
        get() = module ?: when {
            effectiveTarget >= ScriptTarget.ES2015 -> ModuleKind.ES2015
            else -> ModuleKind.CommonJS
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
            val target = ScriptTarget.fromString(value.split(",")[0].trim())
            if (target != null) options.copy(target = target, targetExplicitlySet = true) else options
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
        "esmoduleinterop" -> options.copy(
            esModuleInterop = boolValue,
            esModuleInteropExplicitlyFalse = !boolValue,
        )
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
        "downleveliteration" -> options.copy(downlevelIteration = boolValue, downlevelIterationExplicitlySet = true)
        "importhelpers" -> options.copy(importHelpers = boolValue)
        "allowsyntheticdefaultimports" -> options.copy(
            allowSyntheticDefaultImports = boolValue,
            allowSyntheticDefaultImportsExplicitlyFalse = !boolValue,
        )
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
private fun applyTsconfigOptions(options: CompilerOptions, json: String, tsconfigFileName: String = "tsconfig.json"): CompilerOptions {
    // Extract the compilerOptions block
    val compilerOptionsStart = json.indexOf("\"compilerOptions\"")
    if (compilerOptionsStart < 0) return options

    val braceStart = json.indexOf('{', compilerOptionsStart + "\"compilerOptions\"".length)
    if (braceStart < 0) return options

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
    data class KvMatch(
        val key: String, val value: String,
        val keyStart: Int, val keyLength: Int,
        val valueStart: Int, val valueLength: Int,
    )

    val kvPattern = Regex(""""(\w+)"\s*:\s*("([^"]*)"|(true|false)|(\d+))""")
    val kvMatches = mutableListOf<KvMatch>()
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
        kvMatches.add(KvMatch(key, value, keyStartInJson, keyLength, valueStartInJson, valueLength))
    }

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

    // Only apply a safe subset of tsconfig options that our transpiler handles correctly.
    val allowedTsconfigOptions = setOf(
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

    // Compute line/column positions for option keys and values in the tsconfig JSON
    val optionPositions = mutableMapOf<String, TsconfigOptionPosition>()
    // Add a synthetic "compileroptionskey" entry pointing to the "compilerOptions" key itself.
    // This is used as a fallback position for TS5107 deprecation diagnostics when the deprecated
    // option is set via CLI/test directive (not in tsconfig), but a tsconfig is present.
    // TypeScript attributes such CLI-level deprecated options to the "compilerOptions" key position.
    val compilerOptionsKeyStart = json.indexOf("\"compilerOptions\"")
    if (compilerOptionsKeyStart >= 0) {
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
    }
    for (kv in kvMatches) {
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

    // TS5090: Non-relative paths without baseUrl
    if (result.baseUrl == null && parsedPaths.isNotEmpty() && pathsKeyIdx >= 0) {
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
                    if (!sub.startsWith("./") && !sub.startsWith("../")) {
                        val absPos = pathsBlockOffset + arrayStartInBlock + itemMatch.range.first
                        val lineCol = computeLineAndColumn(json, absPos)
                        pathsDiagnostics.add(Diagnostic(
                            message = "Non-relative paths are not allowed when 'baseUrl' is not set. Did you forget a leading './'?",
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
