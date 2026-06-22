# NEXT-SESSION.md — how to continue the TypeScript-compiler campaign

**Point a fresh agent at THIS file to continue.** It is the single self-contained entry point: the
state, the exact loop, the vetted next wins, the method that's working, and the non-negotiable
tooling rules. Live numbers and full per-test plans live in `STATUS.md` / `PLAN-PHASE-4.md` (linked
below) — this file tells you how to use them.

> The goal (user directive): **use compute to the max to deliver the final compiler.** Run fully
> autonomously, blocker-first, every commit net-positive (transient regressions OK *inside* a build,
> never at a commit boundary). Keep going — don't stop early.

---

## 0. Current state (2026-06-22)

- **~220 failing / 10,086** (the live number is the headline of `STATUS.md` — trust it, not this line).
- **The cheap dedicated-walker/surgical pool is EXHAUSTED** (rounds 276–281: 4 read-only hunts × ~25 candidates + manual triage of ~10 = ~110 of 220 classified, all remaining ENGINE/multi-piece-PARTIAL; hit-rate down to ~1-clean-per-24). The next session should attack a Blocker substep (§3.1 / §4) with revert-on-regression discipline, not hunt for more surgical walkers (low yield now). See the top PLAN-PHASE-4.md note for the vetted-PARTIAL list and the engine roadmap.
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

**CORRECTION (rounds 233–238): the dedicated-walker pool is NOT exhausted.** After I prematurely
declared it empty (the round 230–232 single-error hunts found little), rounds 233–238 landed **+11 more
via dedicated walkers** by mining two sources the single-error hunts missed: (a) the `find_candidates`
**SWAP bucket** (right position, WRONG diagnostic CODE — e.g. `noImplicitAnyLoopCrash` we emitted TS2554
where tsc wants TS2556), and (b) **multi-error / skip-logged tests where EVERY line is AST-shape-derivable**
(`incorrectRecursiveMappedTypeConstraint` circular-constraint, `downlevelLetConst16` empty-array-destructure,
the `longObjectInstantiationChain1/2/3` FAMILY where the engine degrades to `any` but the `merge<…>`
display + property set are AST-rebuildable). **THE METHOD: read the `.types` baseline** (not just
`.errors.txt`) — it gives the EXACT type tsc computed, resolving "why is this `undefined`/`never`" and the
exact display strings to hardcode. Run a read-only hunt (§4) over the bounded multi-error NONE tests asking
"is EVERY line AST-derivable (computable from the syntax tree + hardcoded type displays)?". So: **DO mine
for dedicated walkers** — start with the SWAP bucket and bounded-NONE multi-error tests. Then the harder
options below.

0. **TWO open hunt-3 SCOPEABLE wins (do these FIRST):** (i) **`excessPropertyCheckIntersectionWithIndexSignature`** (c4) — a dedicated walker for `let x: {[k:string]:{a:0}} & {[k:string]:{b:0}}; x = {y:{a:0}}` → TS2322 (value missing intersection member) / TS2353 (excess); gate to an IntersectionType-of-pure-string-index-sig-TypeLiterals annotation + object-literal RHS (corpus-unique); full plan in hunt `wf_79e9fb6b-b3e`. (ii) **`staticMemberExportAccess`** (hunt-3 SCOPEABLE c3, NOT yet done) — 3 additive branches in
   `checkMemberAccessMissing`'s non-Identifier path for a `$.sammy`-chained receiver (TS2576 static-access
   + TS2351 not-constructable are LOW-risk/structurally-FP-impossible; TS2339 namespace-member is MEDIUM-risk,
   touches the B153 shared-path — gate tightly per the hunt-3 plan in `wf_79e9fb6b-b3e`). Deferred this
   session only for budget. The other harder engine options:

1. **Engine feature: recursive type-instantiation depth → TS2589** (§4's pick). `recursiveConditionalCrash4`
   (we already emit its 5 TS2503/2304; MISS only the 2 TS2589), `awaitedType` (also needs TS7010+TS2493),
   `recursiveConditionalTypes`, etc. **Round-232 investigation (precise starting point):** the depth-bail
   mechanism ALREADY EXISTS — `getTypeFromTypeReference`'s generic-alias substitution path sets
   `deepInstantiationBailed=true` at `typeAliasResolutionDepth >= 10` (Checker.kt ~79774), and
   `buildFileLocalTypeMaps` (~4541) emits TS2589 from it (currently gated to NON-generic aliases). The
   blocker: the bail NEVER FIRES for `Foo<T>`/`LengthDown` because `getTypeFromTypeNode(ConditionalType)`
   returns errorType IMMEDIATELY for an infer-bearing conditional (both have `${infer $Rest}`) — it never
   recurses into the branches, so there's no depth to count. **The real unblocker is making conditional-type
   evaluation attempt branch recursion AND reduce the type args toward a base case** (so it can tell
   `Foo<unknown>` — arg never changes → non-converging → TS2589 — from `Tail<[a,...r]> = …Tail<r>` — arg
   shrinks → converges → no error). A side-effect-only "probe" recursion can't distinguish these without
   evaluating the conditions/args; a pure-syntactic heuristic FPs. The capture-the-bail-node piece is easy
   once the bail fires: the recursion re-resolves the SAME source self-ref node, so the node at the depth-10
   bail IS the baseline squiggle position (`Foo<unknown>` at 16,7 / `LengthDown<…Prev<It>>` at 10,7); FP-gate
   to a generic alias whose body is a top-level `ConditionalType` and whose bail node is a same-named
   self-ref (excludes the B57.2-revert FP `type T1<T> = [number, T1<{x:T}>]`, a TUPLE body).
2. **A fresh read-only hunt over a DIFFERENT slice** not yet examined: the parser bucket (~31) and
   js-emit/output-diff bucket weren't hunted; or sample the 460-entry skip-log for tests that may have
   become tractable as machinery grew (lower yield — they were rejected before).
3. **`mapUpsert`** (lib) is BLOCKED on infra: its TS6210/TS6212/TS6502 related-info points at
   `lib.esnext.collection.d.ts:--:--`, but our embedded lib is ONE file parsed as `lib.es5.d.ts`, so the
   filename won't match. Needs a separate embedded lib-file-name mechanism first.
4. **`reverseMappedPartiallyInferableTypes`** (HARD) — needs genuine reverse-mapped inference for its lone
   TS18046 (`obj3`'s `contains(k)`, `k`=`unknown`) PLUS suppressing 3 FP TS7006 (tuple-element arrows,
   `ArrayLiteralExpression` branch ~19767 forcing `ctx=false`).

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
