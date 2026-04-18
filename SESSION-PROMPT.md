# Session starter

Copy the block below into a new Claude Code session to continue Phase 4 of
the TypeScript compiler port. The block is self-contained — it points the
agent at `CLAUDE.md` and `PLAN-PHASE-4.md` for state and at the "Known
architectural blockers" + "Candidate-picking workflow" sections for the
approach.

---

```
Continue Phase 4 of the TypeScript compiler port.

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

**SESSION OVERRIDE — commit to blocker #1, do not surgical-hunt.**

The surgical pool was confirmed empty as of 2026-04-18 (16.4db, 8218
passing). This session's goal is **blocker #1: structural comparison of
generic type references** — the highest-yield architectural unblock
(~30+ TS2322 tests). Skip steps 1-7 above. Instead:

1. Read PLAN-PHASE-4.md "Known architectural blockers" → blocker #1's
   "Retry plan" carefully. Also re-read these CLAUDE.md gotchas first,
   they are load-bearing for this work:
     - "Type system gotchas" — `Type.Reference` shape, base-type lazy
       resolution, `resolveGenericPropertyType` constraints
     - "Overload resolution gotchas" — Array element-type comparison in
       `structuredTypeRelatedTo`, why other generics aren't there yet
     - "Checker gotchas" — relation cache cycle-break invalidation
2. Read TypeScript's `relater.go` (tsgo) and `checker.ts` `recursiveTypeRelatedTo`
   for the variance / cycle-detection model. The 1M context window can
   accommodate them.
3. Implement the retry plan in three discrete commits, running the full
   suite between each — these are the natural checkpoints:
     (a) generalize Array's per-element ref comparison to all
         `Type.Reference` pairs with matching target + resolved type args
         (start with invariant comparison; widen only for known-readonly
         shapes). Add a cycle-break guard for self-referential generics
         (`List<T> { next: List<T> }`) BEFORE this lands or the suite
         will stack-overflow.
     (b) push `currentTypeParamScope` in every `getTypeOfSymbol` call
         that fires on a class/interface member (not just at
         declaration-site resolution). Attempt 16.4j caused +5
         regressions doing only this — the order matters: (a) must land
         first.
     (c) widen the comparison engine to handle named→named generic refs
         once cycle detection in (a) is proven stable.
4. Acceptable outcomes:
     - **Best case**: full plan lands, +20 to +30 tests net.
     - **Expected case**: (a) lands cleanly, (b) lands with some
       regressions you triage, (c) deferred. Net +10 to +20.
     - **Floor case**: (a) alone lands with cycle-break and array
       generalization. Net +3 to +8. Still a win — commits the
       infrastructure for the next session to build on.
5. **Stop conditions that mean "commit what works, defer the rest":**
     - A regression count > 20 that you cannot triage in-session.
     - Stack-overflow that the cycle-break guard doesn't catch.
     - Context budget below ~30%.
   Whenever you stop, leave the codebase compiling and the test suite
   running — push every committed step. Document what landed, what
   regressed, and what to try next under a new 16.4dc/dd/... session
   note in PLAN-PHASE-4.md.
6. Do NOT pivot to surgical hunting if blocker #1 turns out hard. The
   pool is empty; surgical sessions cost context with no payoff. If
   blocker #1 is genuinely intractable today, write a "what I tried,
   why it failed, what infrastructure is still missing" note under
   blocker #1 in PLAN-PHASE-4.md and stop.

Begin.
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
