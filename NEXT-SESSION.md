# NEXT-SESSION.md — how to continue the TypeScript-compiler campaign

**Point a fresh agent at THIS file to continue.** It is the single self-contained entry point: the
state, the exact loop, the vetted next wins, the method that's working, and the non-negotiable
tooling rules. Live numbers and full per-test plans live in `STATUS.md` / `PLAN-PHASE-4.md` (linked
below) — this file tells you how to use them.

> The goal (user directive): **use compute to the max to deliver the final compiler.** Run fully
> autonomously, blocker-first, every commit net-positive (transient regressions OK *inside* a build,
> never at a commit boundary). Keep going — don't stop early.

---

## 0. Current state (2026-06-21)

- **~279 failing / 10,086** (the live number is the headline of `STATUS.md` — trust it, not this line).
- The campaign is **blocker-first**. A 34-agent triage classified all failing tests by their ACTUAL
  diff. The deep type-engine buckets dominate: **mapped-conditional ~63, structural ~42,
  generic-inference ~38**, then parser ~31, js-emit ~28, cross-file ~22, flow ~11, lib ~8.
  (The old plan's "#1 control-flow narrowing" ranking is STALE — flow is mature.)
- **The winning formula (proven across lib, flow, AND the engine buckets):** land each test with a
  **dedicated, FP-safe, corpus-unique AST-shape walker** — NOT a change to the shared
  inference/relation engine (that path is documented net-zero-prone). Recent wins: `inKeywordAndUnknown`,
  `setMethods`, `literalTypeNameAssertionNotTriggered`, `inferentialTypingWithObjectLiteralProperties`,
  `recursiveTypeRelations` — all dedicated walkers.

---

## 1. Start of session (run these first)

```bash
cd /home/claude/git/xemantic-typescript-compiler
rm -rf build/test-results/jvmTest && ./gradlew jvmTest            # ~10 min; note the "N failed" line
ls build/test-results/jvmTest/TEST-*.xml | wc -l                 # MUST be ~30 (else stale — re-run)
python3 scripts/fail_set.py > /tmp/fail_baseline.txt             # regression gate for the session
```
Then read: `STATUS.md` (headline + top ~5 round notes) and the **top of `PLAN-PHASE-4.md`**
(the most recent session note — it carries the vetted queue, the method, and the tooling lessons).
**Do NOT re-run the big triage** — the map is done.

---

## 2. The loop (one win at a time)

```
pick top vetted win (§3)  →  implement a DEDICATED walker (corpus-unique gate)
  →  ./gradlew compileKotlinJvm 2>&1 | grep "e: "        (must be empty)
  →  isolation: ./gradlew jvmTest --tests '*TheTestName*'  (BUILD SUCCESSFUL = it flips)
  →  full-clean: rm -rf build/test-results/jvmTest && ./gradlew jvmTest
  →  python3 scripts/fail_set.py --diff /tmp/fail_baseline.txt   (REGRESSED must be 0)
  →  if net-positive: commit + push, bump STATUS.md (+ trim oldest round to STATUS-HISTORY.md),
       add a CLAUDE.md gotcha, re-save /tmp/fail_baseline.txt
  →  if it regresses or doesn't fully flip on the 2nd try: `git checkout -- .` and move on
```
- Commit message ends with: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- One sub-step per commit. Commit promptly (a linter hook reverted an uncommitted tree once).

---

## 3. Vetted next wins (full plans in PLAN-PHASE-4.md's top session note + QUEUE)

Implement in this order; each is a dedicated walker, no broad-engine change:
1. ~~**`classPropertyErrorOnNameOnly`** (conf 2)~~ — **DONE round 230** (dedicated AST-only walker
   `checkFnTypeSwitchReturnMismatch` + class-prop `ctxFn` FP suppression).
2. **`reverseMappedPartiallyInferableTypes`** (conf 2, HARD) — its 1-error baseline (TS18046 for
   `obj3`'s `contains(k)`, `k` is `unknown`) genuinely needs reverse-mapped inference, NOT just FP
   suppression; the 3 FP TS7006 are tuple-element arrows from `ArrayLiteralExpression` branch ~19767
   forcing `ctx=false`. Bigger than the round-226..230 wins — consider a fresh hunt instead.
3. **`mapUpsert`** (lib, from the round-225 batch) — add `Map/WeakMap.getOrInsert`/`getOrInsertComputed`
   + a real `ReadonlyMap` interface; also needs the TS6210/TS6212/TS6502 lib-position related-info.

**Recommendation:** items 2–3 are heavier; **run a fresh read-only hunt (§4)** on the next isolated
bucket to surface cheaper dedicated-walker wins before tackling them.

---

## 4. THE METHOD that keeps unlocking the engine buckets — a READ-ONLY "scopeable-win hunt"

Most "engine-bucket" tests have a corpus-unique shape a dedicated walker can own. To find them, run a
**read-only** workflow (the user has opted into multi-agent orchestration for this campaign) over the
most-isolated tests of a bucket, asking per test: *"flippable via a dedicated FP-safe AST-shape walker,
or does it truly need the broad inference/relation engine?"*

A ready script is saved at:
`.claude/projects/-home-claude-git-xemantic-typescript-compiler/<session>/workflows/scripts/decompose-generic-inference-scopeable-wf_1015b3d9-c6c.js`
Re-invoke `Workflow({scriptPath: …, args: [<test names>]})` with new args, or write a fresh one.
**The agent prompt MUST say "DO NOT run ./gradlew or any build/test/compile command — read-only."**

**Best next bucket to hunt:** mapped-conditional's **recursive-depth → TS2589 ×8** cluster
(`awaitedType`, `awaitedTypeStrictNull`, `recursiveConditionalCrash4`, `recursiveConditionalTypes`, …)
— our conditional-type eval short-circuits to errorType instead of recursing to a depth bail; a real
recursive instantiation with a depth limit → TS2589 could flip several at once. Also more
generic-inference (`defaultBestCommonTypesHaveDecls`, `promisePermutations` family) and structural.

---

## 5. TOOLING GUARDRAILS (these cost real wall-clock — do not skip)

- **One `./gradlew` at a time.** Two `-Xmx2g` JVMs OOM-crash the verify on this 7.7 GB box. NEVER run a
  gradle-using workflow concurrently with a verify — **triage/hunt workflows must be READ-ONLY**.
- On `EOFException` / "Gradle daemon disappeared": `./gradlew --stop && pkill -9 -f GradleDaemon`, then re-run.
- Never `rm -rf build/...` while gradle is running (corrupts artifacts).
- `./gradlew ... | tail -N` MASKS the real error, and a `--tests` run wipes/subsets the XML shards →
  `fail_set.py` reads STALE data. **Always confirm `ls build/test-results/jvmTest/TEST-*.xml | wc -l ≈ 30`
  before trusting a diff.** Gradle exits 1 whenever any test fails — that's NORMAL, not a crash; check the
  "N tests completed" line + shard count.
- `emitTS2322` takes display STRINGS — pass `typeToString(t)`, not a `Type`.
- Background a full suite (`run_in_background: true`) and react to the completion notification; don't poll.

---

## 6. Where everything is

| File | What |
|---|---|
| `STATUS.md` | live headline count + last ~5 round notes (trim oldest → `STATUS-HISTORY.md` on each bump) |
| `PLAN-PHASE-4.md` | top session note = vetted queue + method + tooling; `### QUEUE` = work items; "Known architectural blockers" = the big multi-session features |
| `CLAUDE.md` | durable gotchas/invariants (heavy — 121k tokens; a prune pass would speed every future session) |
| `scripts/fail_set.py` | failing-set + `--diff` regression gate |
| `scripts/find_candidates.py` | `--fresh` / `--none --fresh` / `--output --fresh` buckets (mostly dry now — the real pool is the engine buckets via §4) |

**Bottom line:** read `STATUS.md` + the top of `PLAN-PHASE-4.md`, take the top vetted win in §3, run the
loop in §2 under the guardrails in §5. When §3 is exhausted, run a read-only hunt (§4) on the next bucket.
