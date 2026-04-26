# Session starter

Copy the block below into a new Claude Code session to continue Phase 4 of
the TypeScript compiler port. The block is self-contained — it points the
agent at `CLAUDE.md` and `PLAN-PHASE-4.md` for state and at the "Known
architectural blockers" + "Candidate-picking workflow" sections for the
approach.

---

```
Continue Phase 4 of the TypeScript compiler port.

(This session may be running as one iteration of `scripts/run-loop.sh`.
Commit + push every fix individually. Do NOT leave uncommitted changes
at session end — the loop driver aborts if it sees them, so partial
state would freeze the loop until a human investigates.)

Before picking any work:
- read STATUS.md for the current test count
- read CLAUDE.md's "AI agent mission" and "Execution protocol" sections
- open PLAN-PHASE-4.md and read THESE three sections near the bottom,
  in order:
    1. "Explored-but-skipped tests" — every test already examined and
       classified by root cause. If a test you're considering is listed
       here, read its skip reason BEFORE re-investigating — the failure
       mode is already characterized. Do not repeat the analysis.
    2. "What's left in the surgical fix pool" — concrete guidance on
       whether the surgical pool still has wins or whether the session
       should instead attempt an architectural blocker.
    3. "Known architectural blockers" — multi-session investigations
       with yield/risk ratings. Read the retry plan for blocker #1 if
       you intend to tackle one.
- `PLAN-PHASE-4-HISTORY.md` holds archived session notes from completed
  items. Only read it if you need to understand why a past fix was made.

Your loop (per CLAUDE.md § Execution protocol):

1. Run the full suite once to produce fresh test-results XMLs. It is slow
   (4-6 minutes) — kick it off in the background, then do steps 2-3 while
   it runs:

       rm -rf build/test-results/jvmTest/binary && ./gradlew jvmTest 2>&1 | grep -a "tests completed"

2. While the suite runs, skim the most recent session notes under item
   16.4 in PLAN-PHASE-4.md (search for "Session 2026-04") to see what has
   been tried lately and avoid re-treading.

3. Once XMLs are fresh, run the candidate finder:

       python3 scripts/find_candidates.py --fresh

   `--fresh` hides tests already in the "Explored-but-skipped" log. Drop
   the flag to see all buckets with `[SKIP]` markers — useful when
   spot-checking whether a previous skip decision still applies. The
   script auto-parses the skipped-log from PLAN-PHASE-4.md, so once a
   test lands in that section future sessions skip it automatically.

   The three output buckets are:
     - EXTRA — we emit N extra diagnostic lines (add a guard)
     - MISSING — expected has N extra lines (add a new check)
     - SWAP — same position, different TS code (change emission site)

4. For each candidate you plan to attempt, READ BOTH the source in
   `typescript-repo/tests/cases/compiler/<name>.ts` AND the expected
   baseline in `typescript-repo/tests/baselines/reference/<name>.errors.txt`
   before making any change. The full source often explains why TypeScript
   chose a particular diagnostic and at which position.

5. If you investigate a candidate and decide to skip it without a fix,
   LOG IT under "Explored-but-skipped tests" in PLAN-PHASE-4.md with a
   one-line skip reason (root cause or which blocker). This is how we
   stop the next session from re-treading the same path.

6. If `--fresh` returns mostly empty or the remaining candidates are all
   "needs new diagnostic / feature" items, it's time to either:
     (a) implement one of the small new-diagnostic items grouped under
         "Needs a new diagnostic / feature" (each is 1-3 tests, but they
         add up), or
     (b) take on an architectural blocker — read blocker #1's "Retry
         plan" first and size the work honestly against remaining context
         budget. Blocker #1 is ~30+ tests but consumes most of a session.

7. Commit each fix as its own `feat(16.4X): ...` commit and push. Re-run
   the full suite between fixes to catch regressions immediately. A
   reasonable cadence is 2-4 commits per session when items yield +1 to
   +5 tests each.

8. After each commit:
     - append a session note under item 16.4 in PLAN-PHASE-4.md
       describing what changed, the test-count delta, and any surprising
       constraint the fix revealed;
     - add a CLAUDE.md gotcha if the fix exposed a non-obvious invariant
       that a future agent would otherwise re-discover the hard way.

9. If you get stuck — a fix regresses repeatedly, or every remaining
   low-hanging candidate turns out to be an architectural-blocker case —
   STOP cleanly after committing any in-progress work. Before ending:
     - log any newly investigated tests under "Explored-but-skipped" so
       the next session benefits from your analysis;
     - update the "What's left in the surgical fix pool" recommendation
       if the situation has shifted (e.g. new diagnostic implemented,
       blocker partially unblocked).

---

**Status (2026-04-26, 8345 passing):** Surgical pool is exhausted (7+
consecutive sessions confirmed; `find_candidates.py --fresh` returns
0/0/0). Phase 17 / Blocker #1 (full control flow narrowing) is in
progress with infrastructure largely in place:

- 17.1a: Flow-graph infra in binder (no behavior change)
- 17.1b: var-decl `never` target narrowing wired (+1)
- 17.1c: `typeof` narrowing op + widened var-decl gate (net-zero infra)
- 17.1d: `instanceof` narrowing op (net-zero infra)
- 17.1e: TS2339 narrowed-to-never wiring + instanceof contradiction
  fix (net-zero infra)
- 17.1f: `in` operator narrowing (net-zero infra)
- 17.2a: TS2774 "uncalled function in conditional" Identifier-only (+1)
- 17.3a: type-predicate fn narrowing + symbol-identity instanceof +
  flow-graph in checkPropertyAccess (+1)
- 17.4a: TS2774 PropertyAccessExpression + parameter/local-fn typed
  scope + `this` tracking + path-aware body suppression (+2)
- 17.4b: TS2774 `&&`-chain walking + ExpressionStatement-level +
  arrow-body-level (net-zero infra; test2 reaches 34/35)
- 17.5a: `x.constructor === Class` narrowing wired into
  `narrowByEquality` (+1; flips `typeGuardConstructorDerivedClass_ts`)
- 17.5b: ElementAccessExpression `["constructor"]` form +
  negative-direction `!==` correctness fix (net-zero infra)
- 17.6a: union-receiver TS2339 multi-member elaboration with
  all-primitives gate (+1; flips `typeGuardConstructorClassAndNumber_ts`)
- 17.7a: discriminant-property narrowing in `narrowByEquality` —
  `name.propX === literal` filters union members by literal-match
  (net-zero infra)
- 17.7b: lift 17.6a's all-primitives gate on multi-member TS2339
  (+1; flips `nonexistentPropertyOnUnion_ts`)
- 17.7c: narrowed-to-single-Object TS2339 emission for anonymous
  `Type.Object` receivers (net-zero infra)
- 17.7d: 17.7c gate also accepts `Type.Interface` with no base types
  (net-zero infra)

**Do NOT re-attempt** Blocker #4 step (b) (TypeParam-vs-TypeParam) —
read 16.4df session note in PLAN-PHASE-4.md first if tempted. The
remaining sub-cases of Blocker #4 are demoted to LOW yield; pursue
them only opportunistically. Default workflow (steps 1-9 above)
applies, with these candidates as the next concrete moves:

- **TS2774 + `&&`-chain narrowing**: `uncalledFunctionChecksInConditional2_ts`
  needs `perf && perf.measure && perf.clearMarks && perf.clearMeasures`
  to narrow `perf` from `Performance | undefined` to defined by the time
  the last operand fires. ALSO blocked on `window` resolving to `any`
  (lib `Window & typeof globalThis` intersection unresolved); same gap
  as the missing 35th emission in `truthinessCallExpressionCoercion2_ts`.
- **TS2454 via flow-graph definite-assignment**: replace the ad-hoc
  walker (does NOT recurse into IfStatement bodies, blocking
  `nestedLoopTypeGuards_ts` etc.). Note 17.1c session warned a
  snapshot/restore approach regressed -7 tests; tread carefully.
- **FlowAssignment-RHS narrowing**: medium risk — could over-narrow
  legitimate union-source TS2322 cases.
- **ElementAccessExpression `var1["constructor"]`**: 17.5a only
  handles PropertyAccessExpression form. Adding the bracket form would
  unlock half the variants in `typeGuardConstructorClassAndNumber_ts`,
  but that test ALSO needs multi-member union TS2339 elaboration —
  not a single-step win.

These leverage the installed flow-graph (`Flow.kt` / `FlowGraphBuilder`,
`currentFlowGraph`, `nodeToFlow`, `applyConditionNarrowing`,
`narrowByEquality` / `narrowByInstanceOf` / `narrowByInOperator` /
`tryNarrowByTypeOf`) and the structural-comparison infra
(`relationSourceTargets` / `relationTargetTargets` stacks,
`isDeeplyNested` 5+ heuristic, TypeParam apparent-type comparison,
`resolvedPropertyTypes` cache).
```

---

## Tips for using this file

- The block above is intentionally self-contained so it keeps working as
  the codebase drifts. It points at `CLAUDE.md` and `PLAN-PHASE-4.md`
  rather than re-stating their contents, so it won't go stale.

- If you want to start the session focused on a specific feature (e.g.
  "work on 16.3 control flow narrowing" instead of "find surgical wins"),
  just append a line or two at the end of the block telling the agent
  what to prioritize. The framework (read the docs, find candidates,
  skip blockers, commit per fix) stays the same.

- When the architectural blockers are eventually unblocked (e.g. someone
  fixes the cross-file global scope issue), edit both this file and
  `PLAN-PHASE-4.md`'s "Known architectural blockers" section to remove
  the stale entries.
