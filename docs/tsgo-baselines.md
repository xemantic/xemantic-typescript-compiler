# (LEGACY.0) — re-pinning the corpus to what tsgo 7.0.2 emits

Read-only design study, 2026-09-12, after the owner's "green light to tsgo regenerated
baseline" and the follow-up decision "both, in order". Two pins, in order:

- **(0a)** `typeScriptCommit` `637d5746` → `4d4f005c` (tsgo's `_submodules/TypeScript`, the
  `tsgo-port` branch). Measured: that branch is pristine `main` plus ONE source change
  (`stableTypeOrdering` defaulting ON, two lines), a 791-baseline delta (658 `.types`, 72
  `.errors.txt`, 35 `.js`), ZERO deleted baselines, ZERO changed cases. Crossed with our
  8,837 active subtests: **29 max red** — 22 files one mechanical display-order family, 2
  reorder-plus-text, 2 chain-length, 2 single-line text, and ONE new subtest
  (`coAndContraVariantInferences5`, a genuinely new TS2322). `stripDtsSection` absorbs 100%
  of the `.js` churn; no parser change. It moves exactly one round-938 family (union
  member order) and none of the others.
- **(0b)** tsgo's ACTUAL output. Everything below is about (0b).

## 1. How tsgo stores its baselines (`internal/testutil/baseline/baseline.go`)

`Run` (lines 30-95) compares tsgo's actual output against a FULL file at
`testdata/baselines/reference/submodule/<suite>/<file>` (`writeComparison`, :181-241). The
`.diff` files are a RECORD of tsgo-vs-tsc, produced by `getBaselineDiff` (:139-176) with
`--- old.<file>` / `+++ new.<file>` headers where `old` is the `4d4f005c` tsc baseline —
and every `@@ -a,b +c,d @@` hunk header is deliberately rewritten to
`@@= skipped -N, +M lines =@@`, so **neither `patch` nor `git apply` accepts them**
(measured: "Only garbage was found" / "No valid patches"). The `_submodules/TypeScript`
checkout is EMPTY in this clone, which proves the full files are self-sufficient.

The three directories are METADATA about a full file, never alternatives to it, and a
`.diff` lives in exactly one (`t.Fatalf` on accepted∧triaged, :68; verified zero
duplicates in both suites):

| directory | meaning |
|---|---|
| `submodule/` | diff in neither list = UNTRIAGED open delta |
| `submoduleAccepted/` | listed in `testdata/submoduleAccepted.txt` (1,426 lines, grouped by cause) = INTENDED divergence |
| `submoduleTriaged/` | listed in `testdata/submoduleTriaged.txt` (87 lines): "known diffs that we intend to fix" = a tsgo BUG |

One trap: `compiler_runner.go:367-390`'s `DiffFixupOld` rewrites `==== ./` → `==== ` on
the OLD side only, so a `./`-prefix difference is invisible in the `.diff` and real in the
full file (20 tsc baselines carry it, zero tsgo ones).

`local/` does not exist in the clone and `go` is not installed; irrelevant, since
`reference/submodule/` IS the accepted local.

## 2. Inventory

`submodule/compiler/` holds 5,761 `.js` + 3,191 `.errors.txt` + 6,147 `.types` + 6,147
`.symbols` + 320 `.diff`. Diff counts (compiler suite): `.errors.txt.diff` 29 / 225 / 31
and `.js.diff` 9 / 132 / 0 across submodule / Accepted / Triaged. **The four adopted
conformance categories contribute zero** (their 37 baselines exist under
`submodule/conformance/` and none differs) — (0b) is a compiler-suite-only change.

Crossed with the corpus (the generator's filters re-implemented and reproducing 8,837
exactly): **378 active subtests carry a tsgo diff** (244 `.errors.txt`, 134 `.js`; layers
Accepted 331 / submodule 28 / Triaged 19; 21 with a variation suffix). Of the 134 `.js`,
**100 are declaration-emit-only and vanish under `stripDtsSection`** — 34 survive. Nine
`.errors.txt` subtests are DELETED (tsgo emits no errors), 24 are NEW (tsgo reports where
tsc was silent; 6 of them TS-1 bugs), 13 change only in the hidden `==== ./` prefix, and
**87 active subtests have no tsgo counterpart at all** (tsgo skips them: `baseUrl`/`paths`
monorepo cases and `skippedEmitTests`) and must keep the tsc baseline. Projected corpus
after (0b): 8,837 − 9 + 24 = **8,852**, with **≈315 red** on the first run (≈2 overlap 0a).

## 3. Divergence families (active `.errors.txt` counts)

| family | count | layer | form/meaning | follow tsgo? |
|---|---|---|---|---|
| F10 elaboration chain SHORTENED (intermediate links dropped, leaf kept) | 45 | Acc 44 / Tri 2 | FORM | yes |
| F7 diagnostic COUNT changed (mostly report-at-both-declarations, e.g. TS2303 `circularModuleImports`) | 43 | sub 10 / Acc 35 | MEANING | yes |
| F6 chain leaf PROMOTED to the top code (TS2345→TS2741, TS2352→TS2353) | 42 | Acc 40 | MEANING | yes |
| F4 TS6133 → TS6196 for unused TYPE entities | 26 | Acc | MEANING (one site) | yes |
| F3 `The last overload gave the following error.` + span to the first failing argument + `related TS2771` | 25 | Acc | MEANING | yes |
| **FBUG `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`** | 15 (+6 new) | **Tri** | tsgo BUG ("checker order dependence") | **NO** |
| F8 span/column only | 13 | Acc 12 | MEANING | yes |
| F9 message wording, same code and span | 11 | Acc 10 | FORM | yes |
| F2 TS2717/partial → TS2300 at BOTH duplicate declarations | 11 | Acc | MEANING | yes |
| F0 tsgo drops the errors file | 9 | sub 3 / Acc 5 / Tri 1 | MEANING | yes except `augmentExportEquals2` (Triaged, issue 3481) |
| F5 removed-option wording: TS5101/5102/5107/5108 → **TS5023 `Unknown compiler option`**, `target: ES3` → TS6046, `downlevelIteration` → TS5102 | 4 | Acc | MEANING | yes — **this answers (LEGACY.1)'s wording question: tsgo has DELETED the options from its table** |
| F1 tsgo ADDS an errors file (new subtests) | +24 | sub 10 / Acc 8 / Tri 6 | MEANING | yes for 18, NO for the 6 TS-1 |
| F-path `==== ./x.ts` → `==== x.ts` (masked by `DiffFixupOld`) | 13 | — | FORM | yes, one formatter line |

No union-order family exists inside the diff layers — that is exactly what (0a) absorbs.

JS emit, the 34 visible after `stripDtsSection`: error-recovery emit for invalid programs
(~10), destructuring/export lowering NOT applied at ESNext (6, one transform gate),
redundant parentheses dropped or needed ones added (5), `_jsxFileName` as tsgo's VFS root
`"/.src/two.tsx"` (5 — a HARNESS ARTIFACT, unreachable by construction: ledger, never
chase), trailing comma preserved in recovered literals (5), temp-var elision (3),
`export {}`/`__esModule` placement (4), BOM (1), `"use strict"` (2).

## 4. Harness equivalence (tsgo's own runner)

`harnessutil.go:95-103` sets exactly three defaults — newline CRLF, `skipDefaultLibCheck`
true, `noErrorTruncation` true — all absorbed (CRLF normalisation; we never check libs; we
have no truncation). No `strict`/`module`/`noImplicitAny` default; the target default is
`ScriptTargetLatestStandard = ES2025` (`compileroptions.go:195-199, :526`), same as ours.
Variations: `GetFileBasedTestConfigurations` (:1007-1043) is a Cartesian product capped at
25, one subtest per value named `name(k=v,…).ext` — identical to `computeVariations` +
`paramBaselineName`. Our port additionally drops `target: es3` (tsgo's skip checks only
ES5 because ES3 no longer parses) — harmless.

## 5. The generator change

1. **`cloneTypeScriptGoRepo`**: today `typescript-go-repo` is a MANUAL `--filter=blob:none`
   clone at tag `typescript/v7.0.2` (HEAD `2bd066d87`), gitignored, referenced by no Gradle
   task. Add a task modelled on `cloneTypeScriptRepo` (:359-410), pinned to the tag's sha as
   a task input, sparse to `testdata/baselines/reference/submodule/{compiler,conformance}/*.js`
   and `*.errors.txt` (≈29 MB), with the submodule sha `4d4f005c` recorded in the KDoc as
   the semantic "old side". Rewrite `cloneTypeScriptRepo`'s KDoc (:240-256), which forbids
   this.
2. **Baseline root**: `val tsgoBaselinesDir = typescript-go-repo/testdata/baselines/reference/submodule`;
   replace the four `baselinesDir.resolve(...)` lookups (`:1039, :1059, :1086, :1101`) with a
   per-subtest choice and a **three-way fallback**: tsgo file present → use it; absent AND a
   `.diff` exists → tsgo emits nothing → DELETE the subtest (the 9); absent AND no diff →
   tsgo never ran the config → KEEP the tsc baseline (the 87). `typeScriptBaselineDir`
   (`TypeScriptTestSupport.kt:47`) becomes two constants and each generated call site
   picks one. **Log and ASSERT the three bucket counts (9 / 87 / 24) as expected constants
   for the pin** — a future tsgo bump that changes them must fail loudly, because a silent
   shift between roots is the exact failure this corpus exists to prevent.
3. **Layer metadata**: the generator emits the layer name (`submodule` / `submoduleAccepted`
   / `submoduleTriaged`) into each affected generated test as a comment and into the
   ledger; **no round may target a `submoduleTriaged` family**, and `submodule/` (untriaged,
   28 active) is treated as unclassified.
4. `stripDtsSection` and `errorsMatchBaseline` need nothing (5,514 `.js` and 2,818
   `.errors.txt` active baselines are byte-identical between tsc and tsgo after
   normalisation); the one formatter change is dropping the `==== ./` prefix.
5. Guard: grep every adopted baseline for `/.src/` at generation time and fail the build
   if one lands outside the ledger.
6. **Pending rows**: the ≈315 first-run reds are tsgo-target rows this compiler does not
   produce yet — not pristine-vs-us divergences. They are generated `@Ignore`d through a
   dedicated `tsgoPendingBaselines` list in `build.gradle.kts` (visible as skipped, counted
   in STATUS.md, the build failing on a stale entry exactly as `LogicalParityDivergence`
   does), so the corpus stays a green gate while each family round removes its entries.
   `LogicalParityDivergence` (with its `pinnedBy` class) is reserved for rows we DECIDE not
   to follow: the 21 TS-1 baselines and the 5 `/.src/` ones.

## 6. Ordered plan

1. **Generator change + regenerate + run, no compiler change** — the measurement instrument;
   expected 8,837 → 8,852, ≈315 red captured with `scripts/fail_set.py` and moved to the
   pending list in the same commit.
2. **Free wins, same day**: `==== ./` prefix (13), F9 wording (11), F5 removed-option wording
   (4 — also unblocks (LEGACY.1)), F4 TS6133→TS6196 (26): ≈54 subtests, four small edits.
3. **Quarantine the bugs**: 21 TS-1 + 5 `/.src/` → `LogicalParityDivergence` with the
   `submoduleTriaged.txt` tracking-issue header as the reason.
4. **Per-family compiler rounds, by count, each measured against `tools/tsgo-7.0.2/lib/tsc`**:
   F10 chain shortening (45, one elaborator owner, highest blast radius) → F7 report-at-both
   with F2 (43 + 11, one mechanism) → F6 leaf promotion (42) → F3 overload text/span/TS2771
   (25) → F8 spans (13 independent) → F0/F1 real checker gaps (9 + 18, triage each) → JS
   emit (34 minus 5 ledgered: ~10 emitter tweaks plus one ESNext-destructuring transform gate).

Per-family fix sizes: F4 one emitter site; F2 one emitter (flips `classWithDuplicateIdentifier`,
`duplicateIdentifierComputedName`, `methodSignatureHandledDeclarationKindForSymbol`); F3
one branch in the no-overload-matches reporter; F10 one change in the relation-error
elaborator; F5 `simulatedVersion = 7.0` meaning "option absent ⇒ TS5023/TS6046".

## 7. Risks

1. Following a tsgo bug: 21 TS-1 baselines and 19 Triaged subtests are recorded
   regressions; the layer name in the generated test is the guard.
2. Harness artifacts (`/.src/`) leaking into expectations; the generation-time grep is the
   guard, and the class may extend to `.js.map`/`sourcemap.txt` if ever adopted.
3. Silent corpus shrinkage from a wrong three-way fallback; the asserted bucket constants
   are the guard.
