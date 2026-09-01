# Session starter

Copy the block below into a new Claude Code session. Phase 17's v1 goal
(fully compile the TypeScript compiler) LANDED at round 481 and was re-verified
at round 679. **Since 2026-09-01 (owner directive) the live mission is PHASE 18:
TypeScript for the JVM and Kotlin** — an embeddable whole-program checker, a
Kotlin externals generator with resolved types, the KIR JVM backend, and an LSP.
Single-thread performance and the (INC.\*) latency arc are CLOSED. The block is
self-contained — it points at `CLAUDE.md` for the protocol and `PLAN-PHASE-5.md`
for the queue.

---

```
Work the QUEUE in PLAN-PHASE-5.md, honouring the WORK ORDER note at its top.
The live mission (owner directive 2026-09-01, CLAUDE.md § "AI agent mission") is
PHASE 18: TYPESCRIPT FOR THE JVM AND KOTLIN — no Node and no Go in the
toolchain, a whole-program checker embeddable in Kotlin applications (the
`Project` API), a Kotlin externals generator with resolved types, the KIR JVM
bytecode backend, and an LSP anyone can try in five minutes. It is NOT
"a TypeScript compiler competing with tsgo", and it is NOT single-thread
performance: the (INC.*) latency family is CLOSED at a 94-110 ms incremental
floor — REFUSE latency rounds on Checker.kt unless a plugin-facing query is
measured > 300 ms warm.

(This session may be one iteration of `scripts/run-loop.sh`. Commit + push every
completed sub-step individually. Do NOT leave uncommitted changes at session end
— the loop driver aborts on a dirty tree.)

Before picking any work:
- read STATUS.md (corpus count + latest round notes)
- for any perf or INV work: read docs/ARCHITECTURE-RETHINK.md § 0 and § 0.1
  first (§ 0 is the round-716 measured correction; § 0.1 the single-thread
  budget), and docs/INVERSION-DESIGN.md once (INV.D) has produced it.
- read CLAUDE.md's "AI agent mission" + "Execution protocol"
- open PLAN-PHASE-5.md and read the WORK ORDER note at the top of the QUEUE,
  then the recent session notes

THE THREE THINGS THAT WILL OTHERWISE COST YOU A ROUND:

1. MEASURE THE PRIZE BEFORE BUILDING A FIX. Three cache hypotheses died in
   round 716 alone, each after real implementation work, because nobody had
   first timed the population they intended to serve (it was 68 ms). Every
   over-estimate in this codebase came from `hits x mean-call-cost`. Time the
   outermost calls of the target population FIRST; if it is small, stop.

2. COUNTERS DECIDE, WALL TIME CONFIRMS. The --passTiming counters are
   deterministic and comparable across runs and machines. Wall time is not: a
   loaded box shows +/-13%, which swamps a 1 s effect. Price candidates with
   `scripts/ab-interleaved.sh <dirA> <dirB> <pairs>` (it refuses a verdict when
   the spread dwarfs the effect) and never trust a median without its win rate.

3. NEVER RUN ANY GRADLE TASK WHILE `jvmTest` IS IN FLIGHT. The documented trap
   is recompiling during a self-compile A/B; the inverse also bites — a gradlew
   classpath resolution during a suite run kills it silently, leaving an empty
   results dir and no XMLs.

Your loop (per CLAUDE.md § Execution protocol):

1. Pick the first unchecked `- [ ]` item in the QUEUE, honouring the WORK ORDER
   note. If it is blocked on missing infrastructure, promote the smallest
   unblocker to the top and work that — never skip silently.

2. Implement it. Every fix ships hand-written LOCAL corner-case tests
   (src/commonTest, com.xemantic.kotlin.test `assert`/`have` — power-assert, no
   message argument) pinning the fix's INVARIANT, asserting the sharp signal.
   Build the probe so it FAILS if the change works — four "fixes" in rounds
   700-704 turned out inert and were caught only because of this.

3. Gate every commit on the full corpus suite:

       rm -rf build/test-results/jvmTest && ./gradlew jvmTest

   then count with a real XML parser (NEVER a regex — JUnit self-closes passed
   testcases and a regex mis-attributes failures):

       python3 -c "
       import glob,xml.etree.ElementTree as ET
       t=f=s=0
       for p in glob.glob('build/test-results/jvmTest/*.xml'):
           r=ET.parse(p).getroot(); t+=int(r.get('tests',0))
           f+=int(r.get('failures',0))+int(r.get('errors',0)); s+=int(r.get('skipped',0))
       print(t,f,s)"

   Zero regressions in MEANING. Under the owner's LOGICAL-PARITY directive
   (round 716) a baseline that differs only in FORM is not a blocker: replace it
   with a test pinning the logic, then switch the old one off by adding a
   `LogicalParityDivergence` to `logicalParityDivergences` in build.gradle.kts —
   the ONLY sanctioned way (it keeps the case visible as skipped, regenerates the
   ledger, and fails the build on a stale entry). Read docs/logical-parity.md § 2
   before classifying anything as form: the burden is per case, and an unlogged
   disable is indistinguishable from hiding a regression.

4. For anything touching the checker, run the COST.1 gate next to the suite (but
   NEVER at the same time):

       python3 scripts/cost_gate.py

   It diffs deterministic --passTiming counters against docs/perf/cost-counters.txt
   and fails above +/-2%. An increase is not a veto — justify it in the note and
   rebaseline with --update in the same commit. Round 713 added ~72k
   getTypeOfExpression calls for one diagnostic and nothing noticed.

   For anything that changes COMPILED CODE, also run the JIT ratchet (JIT.1)(f):

       python3 scripts/huge_methods.py --fail-over 0

   A method over 8,000 bytecodes is never JIT-compiled and runs interpreted for
   the whole process; no other gate can see it. **The census is 0 as of round
   821, which closed (JIT.1)** — so this gate's job now is to catch the NEXT
   method that crosses the limit, which is how the family grew unnoticed for 800
   rounds. Never raise the number to make it green: split the method. The suite
   runs the same census (HugeMethodLimitTest) and fails on a stale entry too.

5. For a perf claim, also run the profile and record it:

       scripts/bench-compile-tsc.sh --project compiler --no-emit --label "<what>"

   (creates build/bench/tsc-project-* on first run; on macOS its TSV stat
   columns log 0 per the BSD-grep gotcha — wall_ms and the run log are real.)

6. After each item: check it off, add a session note at the TOP of the Phase 17
   section in PLAN-PHASE-5.md (what changed, deltas, surprises — including what
   did NOT work and why, which is worth more than the fix), add a CLAUDE.md
   gotcha ONLY for a non-obvious invariant a future agent would break, bump
   STATUS.md (trim-on-write, 5 notes max), commit + push. One commit per
   sub-step.

7. Loop to step 1. Stop cleanly (commit first) when context runs low. Never end
   with analysis-only output while workable items remain.

Specifics worth remembering:
- CLAUDE.md was trimmed 425 KB -> 91 KB (round 716). Per-diagnostic and
  per-walker knowledge lives in docs/history/CLAUDE-GOTCHAS-ARCHIVE.md — GREP IT
  before touching a walker, a TS code, or a corpus regression. A missing entry
  means "look in the archive", never "no such constraint exists".
- Any crash/hang/OOM on any input is a P0: repro item at the top of the queue.
- The tsc sources, real lib .d.ts, and the conformance corpus are all available
  OFFLINE in typescript-repo's object DB.
```
