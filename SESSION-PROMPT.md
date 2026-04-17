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
- open PLAN-PHASE-4.md and read the "Known architectural blockers" and
  "Candidate-picking workflow" sections near the bottom. These tell you
  which candidates are reachable with surgical fixes and which are
  multi-session investigations you should skip.
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

3. Once XMLs are fresh, use the Python-parse-plus-filter approach from the
   "Candidate-picking workflow" section to find tests with:
     - 1-2 EXTRA diagnostic lines (we're too aggressive — look for a guard)
     - 1 MISSING diagnostic at a specific position (simple new check)
     - Code-swap patterns: expected TS####A at position P vs actual
       TS####B at the same P (wrong code choice at one call site)

4. For each candidate you plan to attempt, READ BOTH the source in
   `typescript-repo/tests/cases/compiler/<name>.ts` AND the expected
   baseline in `typescript-repo/tests/baselines/reference/<name>.errors.txt`
   before making any change. The full source often explains why TypeScript
   chose a particular diagnostic and at which position.

5. SKIP candidates whose root cause is listed under "Known architectural
   blockers" in PLAN-PHASE-4.md:
     - cross-file global scope conflation (module-file locals leaking
       into other files' scopes)
     - structural comparison of generic type references
     - TS7006 over-suppression for callback parameters
     - JSDoc @type / @this / @typedef handling
     - parser error-recovery asymmetry for `declare class foo();`-style
       inputs

   Those are multi-session investigations with broad regression risk, not
   surgical wins.

6. Commit each fix as its own `feat(16.4X): ...` commit and push. Re-run
   the full suite between fixes to catch regressions immediately. A
   reasonable cadence is 2-4 commits per session when items yield +1 to
   +5 tests each.

7. After each commit:
     - append a session note under item 16.4 in PLAN-PHASE-4.md
       describing what changed, the test-count delta, and any surprising
       constraint the fix revealed;
     - add a CLAUDE.md gotcha if the fix exposed a non-obvious invariant
       that a future agent would otherwise re-discover the hard way.

8. If you get stuck — a fix regresses repeatedly, or every remaining
   low-hanging candidate turns out to be an architectural-blocker case —
   STOP cleanly after committing any in-progress work. Don't burn context
   chasing a fix that keeps regressing; document what you tried in the
   session note and end the session.

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
