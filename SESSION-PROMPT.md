# Session starter

Copy the block below into a new Claude Code session to work Phase 17
(real-world compilation, then performance). The block is self-contained —
it points the agent at `CLAUDE.md` for the execution protocol and at
`PLAN-PHASE-5.md` for the live queue and dashboard.

---

```
Work the Phase 17 queue in PLAN-PHASE-5.md.

(This session may be running as one iteration of `scripts/run-loop.sh`.
Commit + push every completed sub-step individually. Do NOT leave
uncommitted changes at session end — the loop driver aborts if it sees
them, so partial state would freeze the loop until a human investigates.)

Before picking any work:
- read STATUS.md for the current corpus count + dashboard state
- read CLAUDE.md's "AI agent mission" and "Execution protocol" sections
- open PLAN-PHASE-5.md and read, in order: "Mission & strategy",
  "Ground rules", the recent session notes at the top of the Phase 17
  section, and the QUEUE
- PLAN-PHASE-4.md is ARCHIVED state (Phase 16 and earlier). Consult its
  "Known architectural blockers" section before starting any M3 item —
  the accumulated per-blocker analysis lives there — but do not work its
  queue.

Your loop (per CLAUDE.md § Execution protocol):

1. Pick the first unchecked `- [ ]` item in the PLAN-PHASE-5.md QUEUE
   (or the next unfinished sub-step of an IN PROGRESS item). If it is
   blocked on missing infrastructure, promote the smallest unblocker to
   the top of the queue and work that instead — never skip silently.

2. Implement it. Every fix ships hand-written LOCAL corner-case tests
   (src/commonTest, kotlin.test) pinning the fix's INVARIANT beyond the
   corpus shapes, asserting the sharp signal.

3. Gate every commit on the full corpus suite staying green:

       rm -rf build/test-results/jvmTest/binary && ./gradlew jvmTest 2>&1 | grep -a "tests completed"

   (~4-6 min — run it in the background and prepare the next step while
   it runs. Zero regressions, no +1/-1 swaps.)

4. When the item plausibly moves a dashboard metric (FP counts,
   throughput, crash count), re-run the relevant benchmark and record
   the delta:

       scripts/bench-compile-tsc.sh --label "<what changed>"

   Results append to bench/self-compile-tsc.tsv with a vs-previous
   delta. Diagnostics-by-code shrinking (never growing) is the Phase 17
   headline metric.

5. After each item: check it off, add a session note at the top of the
   Phase 17 section in PLAN-PHASE-5.md (what changed, dashboard delta,
   surprises), add a CLAUDE.md gotcha only for non-obvious invariants a
   future agent would otherwise break, bump STATUS.md (trim-on-write),
   commit + push. One commit per sub-step — keep history bisectable.

6. Loop back to step 1. Stop cleanly (commit first) when the context
   budget runs low, or when every remaining item is user-gated — never
   end with analysis-only output while unchecked items remain workable.

Phase 17 specifics worth remembering:
- Deleting superseded pin walkers is part of the job: when an engine
  feature lands, remove the corpus-unique walkers it replaces in the
  same commit (suite-gated) and note the net walker count.
- Any crash/hang/OOM on any input is a P0: insert a repro item at the
  top of the queue and fix it before resuming.
- The tsc sources, real lib .d.ts files, and the conformance corpus are
  all available OFFLINE in typescript-repo's object DB (see
  PLAN-PHASE-5.md § "Offline asset inventory").
```
