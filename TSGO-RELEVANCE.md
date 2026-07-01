# tsgo-relevance policy

**Target shift (2026-06-05, set by the project owner):** the goal is an
*equivalent* TypeScript compiler, but the compatibility target is the **future
tsgo** — the native Go rewrite shipping as **TypeScript 7.0 (project "Corsa")** —
**not** the legacy `tsc` 5.x test corpus we currently diff against. Where tsgo
removes a feature, a test whose whole point is that feature is **not worth
chasing**. Where tsgo's behavior *diverges* from the tsc baseline, the tsc
baseline is the *wrong* target.

This file is the **authoritative, human-curated** half of the relevance layer.
`scripts/tsgo_relevance.py` reads it (plus a few auto-applied signal rules) and
classifies the current failing subtests so surgical effort focuses only on
**tsgo-relevant** failures. `find_candidates.py --tsgo` hides irrelevant ones.

> **Scope note (updated 2026-07-01 — owner-directed: mirror tsgo's EXACT harness):**
> The effective test suite is **C = A − B**, replicating tsgo's own test harness
> rather than the earlier heuristic.
>
> - **Set A (corpus) is PINNED** — `cloneTypeScriptRepo` pins `typescript-repo` to
>   `typeScriptCommit`, the **pristine mainline tsc commit tsgo tracks** (the
>   `main`-side parent of the "Merge branch 'main' into tsgo-port" commit that
>   tsgo's `_submodules/TypeScript` submodule points at). **We pin the pristine
>   `main` commit, NOT the tsgo-port tip** — the tsgo-port branch *regenerates* its
>   reference baselines to the Go compiler's **divergent** output (e.g. type-id-based
>   union-member ordering: tsgo `'boolean' | 'number'` / `Cover[] | Cover` vs pristine
>   tsc — and us — `'number' | 'boolean'` / `Cover | Cover[]`). We diff char-by-char
>   against **original tsc**, so the tsgo-port baselines are the wrong target. This
>   gives tsgo's exact test-CASE set with real tsc baselines. Bump `typeScriptCommit`
>   to follow tsgo (take the 2nd parent of tsgo's submodule sha).
> - **Set B (removed features) is skipped at generation time** in
>   `generateTypeScriptTests`, a **verbatim port of tsgo's two skip mechanisms**:
>   1. `tsgoSkippedTests` — tsgo's hardcoded whole-file `skippedTests`
>      (`compiler_runner.go`): files using options tsgo removed so completely they no
>      longer parse (`verbatimModuleSyntaxCompat*`, `preserveValueImports*`,
>      `importsNotUsedAsValues*`, `keyofStringsOnly`, `noStrictGenericChecks`,
>      `moduleNone*`, `noImplicitUseStrict_*`, …) + the `typescript.d.ts`-dependent
>      `APISample_*` self-hosting tests.
>   2. `usesUnsupportedOption` — tsgo's `SkipUnsupportedCompilerOptions`
>      (`harnessutil.go`): a **whole-CONFIG** skip (both the `.errors.txt` AND the
>      `.js`/`.d.ts` subtest) when a config's directives include a removed feature:
>      target es3/es5, module amd/umd/system, moduleResolution node/node10/classic,
>      `outFile`, `baseUrl`, `esModuleInterop:false`, `allowSyntheticDefaultImports:false`,
>      `alwaysStrict:false`. Faithful because tsgo runs the filter on the harness-PARSED
>      options (explicit directives only; unset never matches).
>
> tsgo's `skippedEmitTests` (8 files skipped only for Go's parallel-emit
> nondeterminism) is deliberately **NOT** mirrored — a Go-runtime artifact, not a
> removed feature, and our harness is deterministic.
>
> This **supersedes** the earlier heuristic (which dropped only the
> es3/es5/amd/system/umd **JS-emit** subtest and kept the error baseline; whole-file
> for keyofStringsOnly/noStrictGenericChecks). The new filter is stricter
> (whole-config, plus outFile/baseUrl/node-classic-resolution/esModuleInterop:false/…),
> so the total is lower and more honestly tsgo-scoped. `scripts/tsgo_relevance.py`
> stays as a **safety-net reporter** (should report ~0 irrelevant — set B is gone from
> the suite) that flags any NEW tsgo-irrelevant test a future corpus bump slips in.

## What tsgo removes / changes (grounded research, 2026-06)

From the TypeScript 7 progress notes and migration guides
([Progress on TypeScript 7 — Dec 2025](https://devblogs.microsoft.com/typescript/progress-on-typescript-7-december-2025/),
[TS6.0 breaking changes](https://byteiota.com/typescript-6-0-breaking-changes-and-migration-guide/)),
tsgo (TS 7.0):

- **Enables strictness by default** (the *language* default in tsgo). **BUT do
  NOT flip our `compile()` `noImplicitAny` default to match** — the tsc test
  baselines we diff against were NOT generated with noImplicitAny on: an
  empirical sample (2026-06-05) found **26 of 29 currently-passing untyped-param,
  no-directive tests have baselines WITHOUT `TS7006`**, so flipping the default
  would regress hundreds of passing tests. (`arrowFunctionWithObjectLiteralBody1`
  carries `TS7006` with no directive, but it is a per-test outlier — ~3/29 — not
  the harness rule.) The implicit-any `TS7006`/`TS7009`/`TS7019`/`TS7026` family
  is therefore **architectural** (would need per-test harness-default replication
  in the test generator, a Guardrail), NOT a one-line default flip. Settled — do
  not re-attempt the flip.
- **Removes legacy emit targets** ES3 (removed in 5.5) and ES5 (dropping) — the
  modern baseline is ES2015+.
- **Removes the legacy module emitters / deprecates** AMD / System / UMD
  (deprecated in 5.5/6.0 via `TS5107`).
- **Removes deprecated module *resolution*** — classic `"node"` (use
  `node16`/`nodenext`/`bundler`); **baseUrl-alone** resolution removed.
- **Removes deprecated compiler options** (the `TS5102` "removed" set): `charset`,
  `keyofStringsOnly`, `noImplicitUseStrict`, `out`, `noStrictGenericChecks`,
  `prepend`, `importsNotUsedAsValues`, `preserveValueImports`; `--no-default-lib`.
- **Removes some JSDoc constructs** in JS checking (`@enum`, `@constructor`).
- **Keeps the type system** — tsgo targets behavioral parity with the tsc
  *checker*. So core type-checking, declaration emit, and modern-target JS emit
  are **all relevant**; tsgo only diverges on the removed features above plus a
  small set of intentional behavioral fixes.

## Empirical finding (2026-06-05, 808 failing subtests)

**The "are we chasing soon-to-be-removed features?" worry is mostly unfounded.**
Of 808 failing subtests, only **~6 are tsgo-irrelevant** (4 AMD/System/UMD
JS-emit subtests caught by the signal rule + 2 removed-option-behavior tests
curated below). The remaining **~802 are tsgo-relevant core type-checking /
decl-emit / modern-target emit** that tsgo keeps. ES3/ES5 *JS-emit* subtests are
not even generated (only their error-baseline / es2015 variants), so there is no
es5-downlevel-emit noise to trim. **Conclusion: the path to "finishing" is the
core type-engine Blockers (#1 control-flow narrowing, #2 generic-argument
inference, #3 per-file scope), not deprecated-feature pruning.**

A tempting-but-dead alignment lever is **strict-by-default**: the implicit-any
`TS7006` family. It was investigated and **ruled out** (2026-06-05) — see the
strict-by-default note above: the tsc baselines were NOT generated with
noImplicitAny on (26/29 passing untyped-param tests have no `TS7006`), so a
global default flip would regress hundreds. Do not re-attempt it.

## Signal rules (auto-applied by tsgo_relevance.py)

1. A **JS-emit or source-map** subtest whose effective `target` is ES3/ES5 →
   IRRELEVANT (tsgo removed those downlevel emit targets).
2. A **JS-emit or source-map** subtest whose `module` is AMD/System/UMD →
   IRRELEVANT (tsgo removed those module emitters).

(Error-baseline and declaration-emit subtests are **relevant** regardless of
target/module — the diagnostics and `.d.ts` shapes are not downlevel-specific.)

## Curated IRRELEVANT

Tests whose whole point is a **removed feature** (not caught by a signal rule).
Add entries as ` - \`name_ts\` — reason `.

- `keyofDoesntContainSymbols_ts` — tests the removed `keyofStringsOnly` keyof behaviour (tsgo: `keyof` always includes symbol/number keys).
- `noStrictGenericChecks_ts` — tests the removed `noStrictGenericChecks` (bivariant generic parameter) option.
- `systemModule17_ts` — explicit `@module: system` JS-emit test (System module emit removed in TS7/tsgo).
- `dynamicImportWithNestedThis_es2015_ts` — explicit `@module: umd` JS-emit test (UMD module emit removed in TS7/tsgo).
- `dynamicImportWithNestedThis_es5_ts` — explicit `@module: umd` JS-emit test (UMD module emit removed in TS7/tsgo).
- `importedAliasesInTypePositions_ts` — explicit `@module: amd` JS-emit test (AMD `define(...)` emit removed in TS7/tsgo); the errors subtest already passes (only the AMD JS-emit dimension diffs).

## Curated DIVERGES

Tests where tsgo's behavior differs from the tsc baseline we diff against, so the
**tsc baseline is the wrong target** (don't chase it as-is). Add entries as
` - \`name_ts\` — reason `.

 - `defaultBestCommonTypesHaveDecls_ts` — its `concat<T>(x:T, y:T): T` called
   `concat(1, "")` expects **TS2345 `'""' is not assignable to '1'`** (the old
   "best common type" inference, per the test's own name). MODERN tsc/tsgo infers
   `T = 1 | ""` (union) and reports **NO error** — exactly what the still-passing
   `fixTypeParameterInSignatureWithRestParameters_ts` (`bar<T>(item1:T,item2:T)`,
   `bar(1,"")` → OK) and `genericRestArgs_ts` (`makeArrayG<T>(...items:T[])`,
   `makeArrayG(1,"")` → OK) encode. Round-200 verified: a `tryEmitFixedConflictBareTpTs2345`
   that produces the stale error flips this test but REGRESSES those two → net -1.
   The test's OTHER two errors (TS2339 `.length` on `{}` / `Object`) ARE modern and
   were emittable FP-safe via a dedicated `{}`/`Object`-annotated-var walker (verified
   net-zero), but the stale TS2345 makes the whole test unflippable. Do NOT re-attempt.

## How to use

```bash
# After a full suite run (fresh XMLs):
python3 scripts/tsgo_relevance.py                 # relevance breakdown
python3 scripts/tsgo_relevance.py --list-relevant # the tsgo-relevant failing subtests
python3 scripts/find_candidates.py --fresh --tsgo # candidate buckets, irrelevant hidden
```

When you discover a failing test that targets a removed feature (or whose tsc
baseline tsgo would not reproduce), add it to the curated list above with a
one-line reason rather than spending a fix on it.
