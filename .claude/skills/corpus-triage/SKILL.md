---
name: corpus-triage
description: Corpus-regression triage for the TypeScript-compiler corpus suite — find_candidates.py buckets and flags, the skip-log filtering caveat, pin-position rules, tsgo-relevance policy, and the full anti-loop (no recon-only sessions) protocol. Load when diagnosing a corpus-test regression, when deciding whether the surgical candidate pool is genuinely dry, or when a session is at risk of ending without landing code.
---

# Corpus triage

This is CORPUS-era machinery, extracted from CLAUDE.md so it does not load into every
session. In Phase 17 the live work pool is simply the PLAN-PHASE-5.md QUEUE plus any
dashboard regression — so **"the pool is dry" is never true while unchecked queue items
remain**. What follows is still the reference for diagnosing a corpus regression, and the
anti-loop principle applies unchanged at all times.

## Candidate finder

```bash
# Candidate finder (must have fresh full-suite XMLs first):
python3 scripts/find_candidates.py --fresh              # EXTRA/MISSING/SWAP, hide skip-logged
python3 scripts/find_candidates.py                      # all buckets, with [SKIP] markers
python3 scripts/find_candidates.py --none --fresh       # tests where we emit NOTHING (the real pool)
python3 scripts/find_candidates.py --none --fresh --code TS2307   # focus one diagnostic code
python3 scripts/find_candidates.py --output --fresh     # JS/decl/sourcemap output diffs
python3 scripts/find_candidates.py --none --fresh --tsgo # hide tsgo-IRRELEVANT failures (see below)
```

**Skip-log filtering caveat (round 137):** `--fresh` extracts skip-set names as backticked
`name_ts` tokens — entries logged WITHOUT the `_ts` suffix (most of the round-133..136
one-liners) are NOT filtered, so previously-triaged tests keep appearing in the NONE
listing. The skip log lives in docs/history/PLAN-PHASE-4-HISTORY.md since 2026-06-10 (the script reads
both files and unions the tokens); add new entries there, above the `### End of skip log`
terminator. Cross-check a candidate against both files
(`grep <name> docs/history/PLAN-PHASE-4.md docs/history/PLAN-PHASE-4-HISTORY.md`)
before re-investigating; suffix new entries with `_ts`.
The plain (no-flag) buckets only see tests that already emit some `error TSxxxx`
— ~33 of >1000 failures. **`--none` is where ~60% of the remaining work lives**
(see docs/history/PLAN-PHASE-4.md § "STRATEGIC MAP"). Do not call the pool exhausted until
`--fresh`, `--none --fresh`, and `--output --fresh` are all dry.
**The `--none` list IS reliable (it reads our ACTUAL emitted diagnostics from the
XMLs), but an OFF-LIST "we emit nothing → additive" pick is NOT — round 348 a
read-only engine-decomposition agent rated `genericFunctionInference1` "additive
NONE"; it actually OVER-emits ~33 FP diagnostics (generic-source-fn-type
`(...args:A)=>B` vs generic-target-fn-type `<T>(…)=>…` over-rejections in the pipe/
compose family), so the one correct TS2345 a dedicated walker adds CANNOT flip it.
Before treating any test as an additive pin, confirm it appears in `--none` (or run
`dump_diff.py` and verify we emit nothing) — never trust an agent's "we emit X"
claim about checker output the agent could not actually run.**
**Pin positions are directive-offset-free: the checker's `result.sourceFile.text`
is the directive-STRIPPED source (leading `// @directive`/blank lines removed), and
AST `.pos`/`source.indexOf` index into it, so `getLineAndCharacterOfPosition(source,
pos)` yields the baseline line numbers automatically — never hardcode line/col,
derive from AST/indexOf (proven: `intersectionsOfLargeUnions` raw line 24 → baseline
line 21). Same-position diagnostics auto-order by the formatter's `diagnosticComparator`
(start→length→code→message) — give each the right span and they sort correctly.**

**tsgo-relevance target (2026-06-05):** the compatibility target is the FUTURE
**tsgo** (TypeScript 7.0 / "Corsa"), NOT the legacy tsc 5.x corpus. Tests whose
whole point is a feature tsgo removed (legacy ES3/ES5 emit, AMD/System/UMD module
emit, removed options like `keyofStringsOnly`/`noStrictGenericChecks`/`charset`,
classic `node` resolution, JSDoc `@enum`/`@constructor`) are NOT worth chasing.
The policy + curated denylist live in **`TSGO-RELEVANCE.md`**; `scripts/tsgo_relevance.py`
classifies the current failures (RELEVANT / IRRELEVANT / DIVERGES) and the
`--tsgo` flag on `find_candidates.py` hides irrelevant ones. **Empirically (2026-06-05)
only ~6 of 808 failures are tsgo-irrelevant** — the bulk is tsgo-relevant core
type-checking, so the path to "finishing" is the type-engine Blockers, not
deprecated-feature pruning. When you investigate a failing test that targets a
removed feature, ADD it to TSGO-RELEVANCE.md's curated list (one-line reason)
instead of fixing it.

**Note:** All failures are deterministic (confirmed via 5-run study). Count variance between runs is caused entirely by dirty binary cache from interrupted runs, not JVM instability. **Gradle wipes XMLs when run with `--tests '*Name*'`** — the full suite must be re-run before `find_candidates.py` can report accurate results; the script warns if the XML count is below the expected ~27.

## Anti-loop rule — recon-only sessions are a protocol failure

A "recon-only session" is one that runs `find_candidates.py --fresh`, gets `0/0/0` (or similarly empty), spot-checks a handful of candidates, confirms they are all architectural, commits a `chore(status): refresh ... empty pool` note, and ends without landing any code change. **This is not a legitimate session outcome when the queue contains unchecked Blocker items.**

- **The `0/0/0` "exhausted" reading is almost always FALSE (fixed 2026-05-30).** The plain `find_candidates.py` EXTRA/MISSING/SWAP buckets only scan tests that ALREADY emit a parseable `error TSxxxx` line — historically ~33 of >1000 failures. The other ~70% (tests where we emit NOTHING, plus pure JS/decl/sourcemap output diffs) were structurally invisible, which is what manufactured the recurring "pool exhausted" illusion across ~20 rounds. The tool now has a **`--none`** bucket (baseline expects diagnostics, we emit none — recovered by reading the `*.errors.txt` baseline, grouped by code, fewest-error-lines-first = most tractable) and an **`--output`** bucket. **The bounded NONE tail IS the live surgical pool** (TS2307 module-resolution, TS2403, TS2367, TS2540, TS2554, TS2688, TS2344, TS2305, TS2551/2552, decl-emit TS4023/4025 …). See docs/history/PLAN-PHASE-4.md § "STRATEGIC MAP of the remaining failures" for the current breakdown.
- **Hard rule**: the pool is "exhausted" ONLY when `find_candidates.py --fresh` (EXTRA/MISSING/SWAP) **and** `find_candidates.py --none --fresh` (the bounded-code NONE tail) **and** `find_candidates.py --output --fresh` are ALL dry. If any has fresh bounded candidates, work them — do NOT jump to an architectural blocker or commit a recon note. Only when all three are genuinely dry AND the queue has an unchecked `- [ ]` Blocker substep do you start that substep in the same session.
- **Diagnostic signal**: before starting a session, run `git log --oneline -10`. If three or more of the last 10 commits match `chore(status): refresh ... empty pool` (or equivalent recon-only commits), the queue is mis-structured — the agent in earlier sessions failed to promote a blocker to a checkbox. **Your first action is to fix the queue**: open docs/history/PLAN-PHASE-4.md's "Known architectural blockers" section, pick the highest-yield blocker that has no corresponding `- [ ]` queue item, decompose it into the smallest standalone substep, insert it at the top of the QUEUE, commit that restructure (`chore(queue): promote Blocker #N to queue item`), and only THEN start work on it.
- **Recon is allowed as part of step 1**, not as a session deliverable. If a recon turns up surgical candidates, fine — work them. If it doesn't, that's the trigger to attack the next Blocker substep, not to wrap up.
- **Forbidden commit message patterns** when the queue has unchecked Blocker items: `chore(status): refresh post-N.N recon`, `... empty surgical pool`, `... N+ consecutive empty pool`. These are signals that the protocol has failed. The only acceptable session-end commits are (a) a feature commit landing real code, (b) a `chore(queue): promote ...` restructure that prepares the next session, (c) a `chore(maint)`-class action like a stale-skip-log audit when one is genuinely warranted (≤ once per ~5 sessions), or (d) a revert of a regressing attempt.
- **STATUS.md "consecutive empty pool" counters are a code smell, not a metric**: bumping `19+ → 20+ → 21+ → 22+` across consecutive sessions means each of those sessions failed the anti-loop rule. If you find such a counter on entry, treat it as the diagnostic signal above and fix the queue first.
