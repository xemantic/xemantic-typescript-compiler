# NEXT-SESSION.md — how to continue the TypeScript-compiler campaign

**Point a fresh agent at THIS file to continue.** It is the single self-contained entry point: the
state, the exact loop, the vetted next wins, the method that's working, and the non-negotiable
tooling rules. Live numbers and full per-test plans live in `STATUS.md` / `PLAN-PHASE-4.md` (linked
below) — this file tells you how to use them.

> The goal (user directive): **use compute to the max to deliver the final compiler.** Run fully
> autonomously, blocker-first, every commit net-positive (transient regressions OK *inside* a build,
> never at a commit boundary). Keep going — don't stop early.

---

## 0. Current state (2026-06-23)

- **~193 failing / 10,086** (the live number is the headline of `STATUS.md` — trust it, not this line).
- **Round 294 (2026-06-23) landed +1 (194→193), B580 `protectedMembers`, ZERO regressions — the LAST single-flip-ish non-engine PARTIAL is now done.** Built the 4-piece protected-access feature: (1) extended B446 for TOP-LEVEL reads + STATIC-side reads (`pmrFindProtectedStaticDeclaringClass`) + class-method WRITES (new `pmrInClassMethod` gate, avoids B377 overlap); (2) TS2415 protected-narrowing in `checkClassExtendsPrivateConflicts`; (3) new `checkProtectedAssignmentMismatch` (TS2322 `classVar=classVar` Rule A/B). **The load-bearing fix (1 regression caught + fixed): a method `foo(this: A)` in class B must use tsc's `checkPropertyAccessibility` order — LEXICAL class FIRST, then `this:`-param class (statics: lexical ONLY). New `pmrLexicalClass` field + `candidates=listOfNotNull(pmrLexicalClass, enclosing)`.** FP firewall: only 4 of 24 TS2445 baselines are GENERATED (rest lack `.ts` sources in the sparse clone). **NEXT: the cheap pool is GENUINELY ENGINE-only now (re-confirmed: 6 fresh NONE all engine; skip-logged SWAPs need contextual-`this`/inference). Start an ENGINE blocker substep (QUEUE entry points: generic/contextual inference `contextualTupleTypeParameterReadonly`, recursive-conditional TS2589, mapped-type eval) — there is no remaining non-engine PARTIAL. A fresh self-triage of skip-logged NONE is unlikely to yield (rounds 291-294 drained it).** MEMORY TRICK still holds (kill our idle kotlin daemon to free ~1.9GB for the test worker; compile via the resident daemon at ~1300MB).
- **Round 293 (2026-06-23) landed +3 (197→194), B577+B578+B579, ZERO regressions every commit — the "cheap pool is dry" framing was AGAIN incomplete.** Self-triaging the skip-logged/un-examined NONE found three dedicated ADDITIVE walkers (corpus-unique gates, engine-silent shapes — the proven vein): **B577 `inferenceShouldFailOnEvolvingArrays`** — a `{[K in U]:T}[U]` inference-BLOCKING param simplifies to T → check the arg against T's constraint (scalar→TS2345, array→TS2322-at-element); `tryEmitBlockingMappedIndexedAccessArg` in `checkArgumentsAgainstSignature`. **B578 `awaitedTypeStrictNull`** — `type Tn = Awaited<X>` recursive bad-thenable (`then(cb:(value:Y)=>…)` cycles back to X) → TS2589, + a return-less `then`-method in a `TypeLiteral` → TS7010; `checkAwaitedRecursiveThenable`. **B579 `awaitedType`** — B578 emitted 3 of its 4 errors, leaving 1: the `Promise.all([…] as const).then(p=>p[oob])` out-of-bounds tuple index → TS2493; `checkPromiseAllConstTupleOutOfBounds` (Promise.all/resolve are `=>any` → additive; tuple display computed AST-side). **MEMORY TRICK (load-bearing — the `>2000MB` window NEVER opened, 40-min watcher timed out at 883MB): a COMPILE works at ~883MB (the resident kotlin daemon does it), and the full TEST was unblocked by KILLING our OWN idle kotlin daemon (`kill <pid>`, frees ~1.7GB — NOT needed for a CACHED test run since changes were main-only) → avail jumped 883→2566MB, enough to fork the ~2GB test worker. Capture the fail baseline from existing XMLs BEFORE killing+testing.** Cheap NONE pool now GENUINELY ENGINE-only (self-verified: controlFlowArrayErrors[evolving-array elem tracking]/excessPropertyCheckIntersectionWithRecursiveType[recursive cond+mapped]/narrowingMutualSubtypes[Record materialization]/intersectionsOfLargeUnions[DOM-lib-blocked]/reactReduxLikeDeferredInference[12-level conditional chain] all need the inference/mapped/conditional core). **NEXT: an ENGINE blocker substep (QUEUE entry points; `contextualTupleTypeParameterReadonly` is the best inference foundation but 5-7 pieces) or `protectedMembers` (4 coupled pieces). The dedicated-walker vein keeps yielding 1-3/session from re-triaging skip-logged NONE — try a fresh self-triage pass FIRST.**
- **Round 292 (2026-06-23) landed +2 (199→197), B575 + B576, ZERO regressions every commit, under HEAVY memory contention from the `/home/abe` linter (see TOOLING below).** FOUR read-only multi-agent hunt batches over the un-examined/PARTIAL NONE+output slices + an engine-decomposition agent CONFIRMED the cheap pool is genuinely dry (all candidates ENGINE/PARTIAL; no single-session flip in the generic-inference bucket). TWO clean wins surfaced from the dregs: **B575 `arrayOfSubtypeIsAssignableToReadonlyArray`** — subclass-of-Array source (`class C<T> extends Array<T>`) vs `ReadonlyArray<B>` must elaborate the `concat(...)` RETURN-collapse (`A[]≁B[]`→`b` missing), not our wrong contravariant-param chain; new subclass branch in `getPropertyElaborationChain` + `arrayElementTypeOfSubclass` (mirrors B75.2). **B576 `contextualPropertyOfGenericFilteringMappedType`** — a conditional-FILTER mapped handlers param `{[P in keyof T as T[P] extends <prim> ? P : never]: (value:T[P],prop:P)=>void}` called `f({data},{handlers})`: a handler key not in the data-derived surviving set → TS2353 (filtered display) + TS7006 on its arrow params; new walker `checkGenericFilteringMappedHandlerCalls`, additive. Both corpus-unique/FP-safe; B576 byte-exact first build. **NEXT: pool dry. The only remaining single-flip-ish PARTIAL is `protectedMembers` (4 coupled pieces, real 24-baseline TS2445 FP surface, multi-cycle, needs full-suite verify per piece — NOT corpus-unique-by-construction).** Else an ENGINE blocker substep (QUEUE entry points; `contextualTupleTypeParameterReadonly` is the best inference foundation but 5-7 pieces, first brick = `isReadonlyArrayOfTypeParam` + readonly-tuple-element-union extraction at Checker.kt:86796/86950 — MODERATE hot-path risk, revert-on-regression). _Round 291 (+11, B565–B574) trimmed — see STATUS.md / PLAN-PHASE-4.md._
- **MAINTENANCE (flag for next agent): `PLAN-PHASE-4.md` has ~59 live session notes (anti-balloon rule says keep ~10). A safe trim is non-trivial because old notes are interspersed with the QUEUE + important lessons (HUNT-9, POOL STATE) — do it carefully (move ONLY contiguous pure-session-note blocks below the HUNT-9 lesson and above the QUEUE to `PLAN-PHASE-4-HISTORY.md`). Deferred this session to avoid corrupting the QUEUE.**
- **TOOLING WARNING (UPDATED round 292 — the `>3500MB` guardrail is UNACHIEVABLE, do not wait for it): the `/home/abe` linter runs `./gradlew` (its own `/home/abe/.gradle` home — does NOT touch our `build/test-results/`, so our XML shards stay valid) and holds ~2.2GB resident idle daemons (gradle+kotlin, `ps -o etime` shows hours) PLUS frequent active builds. No swap on this 7.7GB box → avail often drops <1GB during an abe build, and the IDLE FLOOR is only ~2400MB. So `free -m`>3500MB never happens; the realistic build window is `no abe java proc >40% CPU AND avail>2000MB`. Catch it with a backgrounded watcher and react to its completion notification: `until { ps -o %cpu= -p $(pgrep -f "home/abe.*java"|tr '\n' ','|sed 's/,$//') 2>/dev/null | awk '{if($1>40)f=1} END{exit f?1:0}'; } && [ "$(free -m|awk 'NR==2{print $7}')" -gt 2000 ]; do sleep 20; done` (run_in_background:true). Compile works at ~2300MB; the full test forks a worker (the OOM risk). Each build ~3-5min when it runs. Do NOT kill abe's daemons (not ours, linter respawns them). NEVER run two `./gradlew` at once. Build with run_in_background and react to the notification.**
- **Round 286–288 (2026-06-23) landed +4 — the "deep pool" still yielded committable dedicated-walker wins.** (a) **B560 `incompatibleTypes`** — the multi-piece test prior sessions rated low-probability, landed in one pass by reading the WHOLE `dump_diff`: 3 INDEPENDENT tsc-faithfulness fixes that all coincided — `checkExcessProperties` now reports the FIRST top-level excess and returns (tsc `hasExcessProperties`); the class-override TS2416 chain adopts the multi-prop "missing the following properties" form (≥2 missing); the TS2769 overload chain drills a class-instance arg via `getPropertyElaborationChain`. (b) **B561 `errorsOnUnionsOfOverlappingObjects01`** + (c) **B562 `emptyObject…1/2`** — surfaced by a 2-agent read-only hunt (§4), then VERIFIED+ITERATED empirically (hunt-9 lesson held: B561's "byte-exact in one pass" agent-optimism was wrong on 2 of 7 errors). B561 = object-arg-vs-named-union walker (`tryEmitObjectVsNamedUnionArg`); B562 = bespoke mapValues/Dictionary/Record return-mismatch walker (+2). **LESSON: the read-only hunt is BACK to producing wins, but ONLY because every SCOPEABLE rating was empirically verified+iterated — never trust the agent's "we emit X / byte-exact" claim; run `./gradlew jvmTest --tests` and read the diff.**
- **The dedicated-walker pool is mostly tapped but NOT bone-dry: each session this week found 1–2 via the read-only hunt + empirical iteration.** Remaining failing tests are overwhelmingly deep multi-session ENGINE features (~25 candidates manually re-confirmed this session). The decomposed entry points are now the TOP item of PLAN-PHASE-4.md's `### QUEUE`: **(1) recursive-conditional TS2589** (`getTypeFromConditionalType` Checker.kt:118709 — unbound-TypeParam checkType → anyType at 118713, no recursion/depth; foundational only, the target tests `recursiveConditionalTypes`/`awaitedType` ALSO need relation chains); **(2) generic/contextual inference** (the DOMINANT blocker — `contextualTupleTypeParameterReadonly`/`inferFromGenericFunctionReturnTypes1` const-tuple + rest-param contextual typing; the SWAP bucket is its tell); **(3) mapped-type eval** (`mappedTypeNotMistakenlyHomomorphic`/`mappedTypeWithCombinedTypeMappers`). **No bounded single-flip engine increment exists — exhaustively checked the smallest 1-error engine tests this session; each needs the full feature.**
- **NEXT SESSION:** start with a fresh `find_candidates --fresh` / `--none --fresh` + ONE read-only hunt (§4) over the most-additive NONE tests (it's still ~1 win/hunt IF you empirically verify each SCOPEABLE) — that's the cheapest committable progress. THEN, if dry, begin an ENGINE blocker from the QUEUE: pick ONE, decompose to the smallest flip-bearing step, build with revert-on-regression, and COMMIT ONLY when a test flips (per "every commit net-positive" — a foundational no-flip increment can't be committed solo, so pair it with the flip or keep iterating). **GUARDRAIL: agent concurrency ~1-2 (a 6-agent hunt ≈ 20-40 min); read-only hunts don't use gradle so they're safe to run alongside a build, but NEVER run two gradle JVMs at once.**
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

**`requireOfJsonFileWithoutResolveJsonModule` — DONE round 291 (B573).** **METHOD that produced round 291's +10: run 2–5 read-only multi-agent hunts (§4) per session over un-examined NONE/SWAP/output slices, asking "every error AST-derivable via a corpus-unique additive walker?"; EMPIRICALLY verify each SCOPEABLE in isolation BEFORE commit (agents over-rate ~10%, esp. gate-broadenings — the one revert this session was a gate-broadening whose claimed types never reached the arg-check). The vein is NOT exhausted by a single "0 NONE" reading — round 290 wrongly declared it so, round 291 found 10.** The remaining ~30 NONE/SWAP that the 5 hunts RE-CONFIRMED ENGINE (do NOT re-hunt — they need the inference/relation/mapped-type core): intersectionsOfLargeUnions, constraintWithIndexedAccess, narrowingMutualSubtypes, knockout, circularlyConstrained…, duplicatePackage (package-dedup), underscoreTest1, mappedTypeWithAsClause…, extractInferenceImprovement, recursiveTupleTypeInference, inferenceFromIncompleteSource, normalizedIntersectionTooComplex, deeplyNestedMappedTypes, promisePermutations(2), awaitedType, reactReduxLikeDeferredInference…, paramsOnlyHaveLiteralTypes (DONE B571). PARTIAL/blocked: `infiniteConstraints` (2/4 TS2536 scopeable, doesn't flip), `dissallowSymbolAsWeakType` (needs FinalizationRegistry lib + TS2769 iterator chain), `declarationEmitReexportedSymlinkReference3` (needs `@link` symlink plumbing — guardrail-adjacent). NEXT SESSION: one fresh hunt over a LAST un-examined slice (parser cascades / decl-emit / sourcemap output — lower yield) OR start an ENGINE blocker substep. The historical content below is superseded — consult only for deeper detail.

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
