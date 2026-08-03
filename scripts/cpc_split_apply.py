#!/usr/bin/env python3
"""(JIT.1)(e) round 816 — apply the split of `TypeScriptCompiler.compileParsedCore`.

Mechanical: every moved region is a CONTIGUOUS run of the HEAD body, emitted
verbatim into its helper modulo a uniform dedent; the entry keeps the option
head (the ONE place `options` is reassigned), the leading explanatory comment of
each moved block, and the single-vs-multi dispatch.

Two properties this target has and that decide the shape:

  * the two arms of the dispatch move WHOLE, so all four whole-function
    `return`s go with them and **no region needs a return signal** (round 813);
  * the only values crossing a boundary are RETURNED (`Checker` from
    `cpcBindAndCheck`, `Set<String>` from `cpcRequireOnlyOrphans`) — never
    stashed in a field.

**Every call site uses NAMED arguments.** `cpcScanFiles` takes 18 parameters, of
which four are `MutableSet<String>` and three `MutableList<…>`: a positional
permutation of two same-typed containers is type-correct and silently wrong, and
no test would name it. Named arguments make the mapping compiler-checked.

Run:  python3 scripts/cpc_split_apply.py            # writes TypeScriptCompiler.kt
      python3 scripts/cpc_split_apply.py --dry      # reconstruct only, no write
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import sys

PATH = "src/commonMain/kotlin/TypeScriptCompiler.kt"
FN_START, FN_END = 167, 1946

DIAG = "diagnostics: MutableList<Diagnostic>"
TSPOS = "tsconfigPos: Map<String, TsconfigOptionPosition>"

# helper name -> (first, last, dedent, KDoc, signature, trailing lines)
HELPERS = [
    ("cpcCheckDeprecatedOptions", 196, 386, 0,
     """    /**
     * (JIT.1)(e) round 816 — the TS5101/TS5102/TS5107/TS5108 deprecated- and
     * removed-option diagnostics, moved verbatim out of [compileParsedCore]. Holds
     * the `ignoreDeprecations` validation (TS5103) and the three local emitters that
     * only this run uses.
     */""",
     "    private fun cpcCheckDeprecatedOptions(\n"
     "        options: CompilerOptions,\n"
     "        fileName: String,\n"
     f"        {TSPOS},\n"
     "        simulatedVersion: String,\n"
     f"        {DIAG},\n"
     "    ) {", []),
    ("cpcCheckEmitOptionConflicts", 388, 577, 0,
     """    /**
     * (JIT.1)(e) round 816 — the emit-option conflict diagnostics (TS5069, TS5066,
     * TS5052, TS5058/TS5059 outDir-vs-rootDir, TS6059, TS5009), moved verbatim out of
     * [compileParsedCore].
     */""",
     "    private fun cpcCheckEmitOptionConflicts(\n"
     "        parsed: ParsedSource,\n"
     "        options: CompilerOptions,\n"
     "        fileName: String,\n"
     f"        {TSPOS},\n"
     f"        {DIAG},\n"
     "    ) {", []),
    ("cpcCheckModuleAndLibOptions", 579, 799, 0,
     """    /**
     * (JIT.1)(e) round 816 — the module/lib option diagnostics (TS5070/TS5071 for
     * `resolveJsonModule`, TS5104, TS5055-adjacent `checkJs`/`allowJs` pairs, the
     * `isolatedDeclarations`/`isolatedModules`/`composite`/`incremental` conflicts,
     * TS5053 `inlineSourceMap`, TS5061 `noLib`, the bundler and node16/nodenext
     * option checks), moved verbatim out of [compileParsedCore].
     */""",
     "    private fun cpcCheckModuleAndLibOptions(\n"
     "        parsed: ParsedSource,\n"
     "        options: CompilerOptions,\n"
     f"        {DIAG},\n"
     "    ) {", []),
    ("cpcCheckProjectShapeOptions", 801, 915, 0,
     """    /**
     * (JIT.1)(e) round 816 — the whole-PROGRAM shape diagnostics (TS6054 unsupported
     * extension, TS5055 output-overwrites-input, TS5056 two inputs one output), moved
     * verbatim out of [compileParsedCore]. Every block is gated on
     * `parsed.hasExplicitFilenames`.
     */""",
     "    private fun cpcCheckProjectShapeOptions(\n"
     "        parsed: ParsedSource,\n"
     "        options: CompilerOptions,\n"
     f"        {DIAG},\n"
     "    ) {", []),
    ("cpcCompileSingleFile", 921, 1049, 4,
     """    /**
     * (JIT.1)(e) round 816 — the SINGLE-FILE arm of [compileParsedCore], moved
     * verbatim. Both of the arm's whole-function `return`s came with it, so the entry
     * needs no signal: it returns whatever this returns.
     */""",
     "    private fun cpcCompileSingleFile(\n"
     "        parsed: ParsedSource,\n"
     "        options: CompilerOptions,\n"
     "        fileName: String,\n"
     f"        {DIAG},\n"
     "    ): CompilationResult {", []),
    ("cpcCompileMultiFile", 1051, 1944, 4,
     """    /**
     * (JIT.1)(e) round 816 — the MULTI-FILE arm of [compileParsedCore], moved
     * verbatim, minus four contiguous runs of its own that are helpers below
     * ([cpcScanFiles], [cpcBindAndCheck], [cpcTransformAndEmit],
     * [cpcRequireOnlyOrphans]). Both of the arm's whole-function `return`s came with
     * it.
     */""",
     "    private fun cpcCompileMultiFile(\n"
     "        parsed: ParsedSource,\n"
     "        options: CompilerOptions,\n"
     "        fileName: String,\n"
     f"        {DIAG},\n"
     "        recheckOnly: Set<String>?,\n"
     "    ): CompilationResult {", []),
    ("cpcScanFiles", 1208, 1477, 4,
     """    /**
     * (JIT.1)(e) round 816 — phase 1 of the multi-file pipeline: the per-file scan
     * that parses every input and populates the program-wide tables, moved verbatim
     * out of [cpcCompileMultiFile]. The 18 parameters ARE the region's free-variable
     * set (`scripts/cpc_split_analyze.py` computes it scope-aware and the compiler
     * enforces it); the call site passes them by NAME, because a positional swap of
     * two same-typed containers would be type-correct and silently wrong.
     */""",
     "    private fun cpcScanFiles(\n"
     "        parsed: ParsedSource,\n"
     "        options: CompilerOptions,\n"
     f"        {DIAG},\n"
     "        computedTsconfigDir: String?,\n"
     "        resolvedOutDir: String?,\n"
     "        sourceEchoes: MutableList<Pair<String, String>>,\n"
     "        jsonOutputs: MutableList<Pair<String, String>>,\n"
     "        importDeps: MutableMap<String, List<String>>,\n"
     "        importDepsNoRefPath: MutableMap<String, List<String>>,\n"
     "        filesWithImportEquals: MutableSet<String>,\n"
     "        tsFileNames: MutableList<String>,\n"
     "        parsedSourceFiles: MutableMap<String, SourceFile>,\n"
     "        filesWithParseDiagnostics: MutableSet<String>,\n"
     "        filesWithRealParseDiagnostics: MutableSet<String>,\n"
     "        allParserDiagsForPins: MutableList<Diagnostic>,\n"
     "        emptyJsxTsxFixtures: MutableSet<String>,\n"
     "        dtsFileNamesInProjectDir: MutableList<String>,\n"
     "        importedJsonBaseNames: Set<String>,\n"
     "    ) {", []),
    ("cpcBindAndCheck", 1479, 1529, 4,
     """    /**
     * (JIT.1)(e) round 816 — phase 2 of the multi-file pipeline: bind every parsed
     * file and run the checker (or, under `--workers`, the INV.6 partition checkers),
     * moved verbatim out of [cpcCompileMultiFile]. The one value that crosses the
     * boundary — the `Checker` the downstream Transformer queries — is RETURNED, never
     * stashed in a field.
     */""",
     "    private fun cpcBindAndCheck(\n"
     "        parsed: ParsedSource,\n"
     "        options: CompilerOptions,\n"
     "        recheckOnly: Set<String>?,\n"
     "        parsedSourceFiles: Map<String, SourceFile>,\n"
     f"        {DIAG},\n"
     "    ): Checker {", ["        return checker"]),
    ("cpcTransformAndEmit", 1640, 1728, 4,
     """    /**
     * (JIT.1)(e) round 816 — phase 3 of the multi-file pipeline: the transform+emit
     * loop, moved verbatim out of [cpcCompileMultiFile]. It produces NO diagnostics
     * (FRONT.1 relies on the same property) — its whole output is `jsOutputMap`.
     */""",
     "    private fun cpcTransformAndEmit(\n"
     "        options: CompilerOptions,\n"
     "        checker: Checker,\n"
     "        orderedParsedSourceFiles: List<Pair<String, SourceFile>>,\n"
     "        emptyJsxTsxFixtures: Set<String>,\n"
     "        crossFileNamespaceExports: Map<String, Set<String>>,\n"
     "        commonSourceDir: String?,\n"
     "        resolvedOutDir: String?,\n"
     "        jsOutputMap: MutableMap<String, Pair<String, String>>,\n"
     "    ) {", []),
    ("cpcRequireOnlyOrphans", 1764, 1868, 4,
     """    /**
     * (JIT.1)(e) round 816 — the `require`-only orphan census (inputs reached ONLY by
     * a bare `require('./x')` CallExpression, which tsc never makes a program file),
     * moved verbatim out of [cpcCompileMultiFile] together with its three local
     * resolvers. Its result is RETURNED.
     */""",
     "    private fun cpcRequireOnlyOrphans(\n"
     "        parsed: ParsedSource,\n"
     "        tsFileNames: List<String>,\n"
     "        importDeps: Map<String, List<String>>,\n"
     "        parsedSourceFiles: Map<String, SourceFile>,\n"
     "    ): Set<String> {", ["        return requireOnlyOrphans"]),
]

# call sites, keyed by the region they replace (indent is the region's own)
CALLS = {
    "cpcCheckDeprecatedOptions": [
        "        cpcCheckDeprecatedOptions(",
        "            options = options,",
        "            fileName = fileName,",
        "            tsconfigPos = tsconfigPos,",
        "            simulatedVersion = simulatedVersion,",
        "            diagnostics = diagnostics,",
        "        )",
    ],
    "cpcCheckEmitOptionConflicts": [
        "        cpcCheckEmitOptionConflicts(",
        "            parsed = parsed,",
        "            options = options,",
        "            fileName = fileName,",
        "            tsconfigPos = tsconfigPos,",
        "            diagnostics = diagnostics,",
        "        )",
    ],
    "cpcCheckModuleAndLibOptions": [
        "        cpcCheckModuleAndLibOptions(parsed = parsed, options = options, diagnostics = diagnostics)",
    ],
    "cpcCheckProjectShapeOptions": [
        "        cpcCheckProjectShapeOptions(parsed = parsed, options = options, diagnostics = diagnostics)",
    ],
    "cpcScanFiles": [
        "            cpcScanFiles(",
        "                parsed = parsed,",
        "                options = options,",
        "                diagnostics = diagnostics,",
        "                computedTsconfigDir = computedTsconfigDir,",
        "                resolvedOutDir = resolvedOutDir,",
        "                sourceEchoes = sourceEchoes,",
        "                jsonOutputs = jsonOutputs,",
        "                importDeps = importDeps,",
        "                importDepsNoRefPath = importDepsNoRefPath,",
        "                filesWithImportEquals = filesWithImportEquals,",
        "                tsFileNames = tsFileNames,",
        "                parsedSourceFiles = parsedSourceFiles,",
        "                filesWithParseDiagnostics = filesWithParseDiagnostics,",
        "                filesWithRealParseDiagnostics = filesWithRealParseDiagnostics,",
        "                allParserDiagsForPins = allParserDiagsForPins,",
        "                emptyJsxTsxFixtures = emptyJsxTsxFixtures,",
        "                dtsFileNamesInProjectDir = dtsFileNamesInProjectDir,",
        "                importedJsonBaseNames = importedJsonBaseNames,",
        "            )",
    ],
    "cpcBindAndCheck": [
        "            val checker = cpcBindAndCheck(",
        "                parsed = parsed,",
        "                options = options,",
        "                recheckOnly = recheckOnly,",
        "                parsedSourceFiles = parsedSourceFiles,",
        "                diagnostics = diagnostics,",
        "            )",
    ],
    "cpcTransformAndEmit": [
        "            cpcTransformAndEmit(",
        "                options = options,",
        "                checker = checker,",
        "                orderedParsedSourceFiles = orderedParsedSourceFiles,",
        "                emptyJsxTsxFixtures = emptyJsxTsxFixtures,",
        "                crossFileNamespaceExports = crossFileNamespaceExports,",
        "                commonSourceDir = commonSourceDir,",
        "                resolvedOutDir = resolvedOutDir,",
        "                jsOutputMap = jsOutputMap,",
        "            )",
    ],
    "cpcRequireOnlyOrphans": [
        "            val requireOnlyOrphans = cpcRequireOnlyOrphans(",
        "                parsed = parsed,",
        "                tsFileNames = tsFileNames,",
        "                importDeps = importDeps,",
        "                parsedSourceFiles = parsedSourceFiles,",
        "            )",
    ],
}

# regions extracted OUT of the multi-file arm (its own sub-helpers)
IN_MULTI = ["cpcScanFiles", "cpcBindAndCheck", "cpcTransformAndEmit",
            "cpcRequireOnlyOrphans"]
TOC = [
    "        // (JIT.1)(e) round 816: the option-validation runs. Each is a CONTIGUOUS",
    "        // region of the pre-split body, moved verbatim with its own explanatory",
    "        // comments; they emit into `diagnostics` and read nothing back, so the",
    "        // order below is HEAD's source order and nothing else depends on it.",
]
DISPATCH = [
    "        if (parsed.files.size == 1 && !parsed.hasExplicitFilenames) {",
    "            // Single-file compilation",
    "            return cpcCompileSingleFile(",
    "                parsed = parsed,",
    "                options = options,",
    "                fileName = fileName,",
    "                diagnostics = diagnostics,",
    "            )",
    "        } else {",
    "            return cpcCompileMultiFile(",
    "                parsed = parsed,",
    "                options = options,",
    "                fileName = fileName,",
    "                diagnostics = diagnostics,",
    "                recheckOnly = recheckOnly,",
    "            )",
    "        }",
    "    }",
]


def dedent(lines, n):
    out = []
    for l in lines:
        if not l.strip():
            out.append("")
        else:
            assert l[:n].strip() == "", repr(l)
            out.append(l[n:])
    return out


def build(src):
    L = src.split("\n")           # L[i] is line i+1

    def rng(a, b):
        return L[a - 1:b]

    spec = {h[0]: h for h in HELPERS}
    # ---- the entry -------------------------------------------------------
    entry = []
    entry += rng(FN_START, 195)                      # signature .. simulatedVersion
    entry += TOC
    entry += CALLS["cpcCheckDeprecatedOptions"]
    entry += CALLS["cpcCheckEmitOptionConflicts"]
    entry += CALLS["cpcCheckModuleAndLibOptions"]
    entry += CALLS["cpcCheckProjectShapeOptions"]
    entry += rng(916, 919)                           # paths diagnostics + blank
    entry += DISPATCH

    # ---- the multi-file arm, with its four sub-regions replaced ----------
    multi = []
    ln = 1051
    for name in IN_MULTI:
        _, a, b, _, _, _, _ = (name,) + spec[name][1:]
        multi += dedent(rng(ln, a - 1), 4)
        multi += dedent(CALLS[name], 4)
        ln = b + 1
    multi += dedent(rng(ln, 1944), 4)

    # ---- every helper ----------------------------------------------------
    helpers = []
    for name, a, b, ded, kdoc, sig, tail in HELPERS:
        helpers.append("")
        helpers += kdoc.split("\n")
        helpers += sig.split("\n")
        if name == "cpcCompileMultiFile":
            helpers += multi
        else:
            helpers += dedent(rng(a, b), ded)
        helpers += tail
        helpers.append("    }")

    return "\n".join(L[:FN_START - 1] + entry + helpers + L[FN_END:])


def main():
    src = open(PATH).read()
    out = build(src)
    if "--dry" in sys.argv:
        print(f"reconstructed {len(out)} chars (HEAD {len(src)})")
        return 0
    open(PATH, "w").write(out)
    print(f"wrote {PATH}: {len(src)} -> {len(out)} chars")
    return 0


if __name__ == "__main__":
    sys.exit(main())
