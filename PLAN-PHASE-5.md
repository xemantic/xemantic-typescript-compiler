# PLAN-PHASE-5 — Self-compile the TypeScript compiler, then performance

Owner directive (2026-07-03, re-scoping the 2026-07-02 *"fully compile any TypeScript
project"*): **fully compile the TypeScript compiler itself, then optimize
performance.** "Any TypeScript project" is the post-v1 horizon.

**v1 definition of done:** all 8 tsc-source profiles (compiler / tsc-cli / jsTyping /
deprecatedCompat / typingsInstallerCore / services / server / harness) at **zero false
positives**, all files emitted, zero crashes/hangs/OOMs — verifiable fully offline.
Byte-correct emit diffing against real tsc is the network-gated follow-up (needs
node + typescript installed). Then M5 (performance) completes the directive. Items
that do not block v1 (M2.4, M3.0, M3.5, all of M4) are parked in § "Post-v1 backlog"
near the bottom of this file — the top-to-bottom loop skips them until v1 lands.

This file is the **live queue** for Phase 17. `docs/history/PLAN-PHASE-4.md` (Phase 16 and earlier)
is archived state — its "Known architectural blockers" section remains the reference
material for the M3 items below; do not work its queue.

## Phase 17 — Self-compile the TypeScript compiler (M0–M5)

(Live session notes accumulate here, most recent first — same convention as Phase 16.)

**Round 719 (2026-07-26) — second attempt to gate round 718's fix; the build again did
not converge, so the loop is STOPPING on (BUILD.1) rather than burning a third round
on it. Main is at the last gated commit and clean; the fix stays on
`wip/round718-required-minus-optional`, pushed.**

Method was deliberately minimal after round 718's thrash: quiet box confirmed (no JVMs,
6.0 GB available), merge the branch locally, run ONE `jvmTest` with
`-Dkotlin.daemon.jvmargs=-Xmx4g`, touch nothing until it returns. `compileKotlinJvm`
was still running ~40 minutes later, having produced zero class files, and I stopped
it — it did not fail on its own, and might eventually have finished; what it would not
do is finish inside a round.

**What the instrumentation says, and it is NOT simply "out of heap".** Sampling the
Kotlin daemon twice, 2.5 minutes apart: same PID, `utime` 210.7 s → 277.2 s, RSS
2.38 GB against a 4 GB ceiling, `stime` ~1.5 s. That is the signature of real work —
NOT the round-717 death spiral, which sat pinned exactly at the ceiling. Yet round
717's cold compile of the same module at 3 g finished in **2m 33s**. A compile that
takes 2.5 minutes on a quiet box and >40 minutes here is a CONTENTION story, not a
heap story.

**And there is a contender.** `chore(bench): 3-way run @ …` commits landed on origin
DURING this session (three of them across rounds 717–719), which this session did not
make — so something else periodically builds this project and commits to this repo.
That is very likely what killed the round-718 compiles too, and it means the
round-718 note's "cold compile does not fit -Xmx2g" is **over-confident**: the 2 g run
DID show the pinned-at-ceiling thrash signature, but the 3 g and 4 g failures look
like contention instead. Corrected on (BUILD.1) rather than left standing.

**What the owner needs to decide** — the queue item carries both options: raise
`kotlin.daemon.jvmargs`, and/or give the agent loop a box it is not sharing (or tell
it when the bench loop runs, so the two can interleave instead of colliding).
(PERF.HW) already wants ≥8 real cores for the parallel-scaling question, so one
machine settles both.

**Judgement recorded, because a future round will face the same choice:** parking beat
grinding. Two rounds produced a correct, well-understood, fully-diagnosed fix with a
live repro; a third round of the same failing cycle would have produced nothing and
risked landing something ungated. The branch plus the diagnosis is worth more than a
red main.

---

**Round 718 (2026-07-26) — a complete fix for 11 of (LIB.1)'s 35 false positives,
diagnosed, written, and NOT LANDED: four cold compiles in one session and none of
them would gate it. The fix is on `wip/round718-required-minus-optional`; main is
clean and unchanged. (BUILD.1) is escalated from nuisance to binding constraint.**

**What the bug is.** `Required<T>` is `{ [P in keyof T]-?: T[P] }`, and `Parser.kt`'s
mapped-type modifier scan records `-?` as a plain `?` — so `Required<T>` behaves
exactly like `Partial<T>`, inverted. `-readonly` got its own flag back in M1.10; the
`?` analogue was never written. It costs 11 of the 35: tsc declares
`tracker: Required<Pick<SymbolTracker, "reportInferenceFallback">>`, and we called
every `context.tracker.reportInferenceFallback(...)` possibly-undefined.

**The mechanism is not the one it looks like, and that matters for the fix.** TS2722
reads like a type-level question, so the obvious fix is "strip `undefined` from the
member type". Wrong: the emitter gates on `isOptionalProperty(propSym)` — the
SYMBOL's optionality — and a comment right there records that the codebase never adds
`| undefined` for optional-member access at all. What actually happens is the M1.10
trap repeating: a homomorphic mapped member CARRIES ITS SOURCE PROPERTY'S DECLARATION
(for "declared here" related info), so `isOptionalProperty`'s declaration scan sees
the source's `?`. The fix is therefore M1.10's mirror — a `mappedRequiredMemberIds`
side-channel, probed only when the declaration says optional, which keeps the
documented hot-path property (a declared-required property pays no set lookup).

**Two dead ends, both caught by CONTROLS, which is the transferable part.** The first
probe hand-rolled the mapped types (`type MyRequired<T> = { [P in keyof T]-?: T[P] }`)
to stay off the lib: its two controls came back EMPTY — we emit nothing whatsoever for
user-defined mapped types — so its three target assertions were passing vacuously and
would have "confirmed" any fix at all, including no fix. The second cut asserted
assignability through `Partial`, which measures an axis we do not model. Only
`@useRealLibs` + TS2722 assertions reproduce it; verified against unmodified HEAD with
the target FAILING and the control PASSING. **Rounds 700–704 lost four "fixes" to
inertness; this round would have made it five without the controls, twice.**

**Why it did not land.** No compile would finish. `-Xmx2g` hung (the round-717 trap),
`-Xmx3g` ran 16 minutes before the daemon died and restarted from scratch, and only
`-Xmx4g` got the main compile through — after which the test compile was still
running when I stopped. Four cold compiles, ~an hour, no gate. Parking beat grinding:
the branch carries the fix and the live repro, main stays clean and green, and the
next round merges and gates it in one pass. (BUILD.1)'s proposal is revised 3g → 4g
on this evidence, with a bigger box offered as the alternative the owner may prefer —
(PERF.HW) already wants ≥8 real cores for a different reason.

**Also recorded on (LIB.1):** the embedded lib declares NO utility types at all — no
`Required`, `Pick`, `Partial`, `Omit`. That is why this whole family is invisible on
the default path: the name is unresolved, degrades to `any`, and `any` is silent. It
is the LIB.1 defect in miniature, on the dashboard profile, today.

---

**Round 717 (2026-07-26) — the two GATE items landed. Logical parity stopped being a
rule and became a mechanism with two build-failing controls; cost stopped being an
intention and became a counter diff. The determinism check on that gate found a racy
counter on its first use — and it is exactly the counter (DISPATCH.1) was told to
build its table from. Corpus 12,765 / 0 / 3, unchanged; the generated corpus is
byte-identical.**

**(PARITY.1) — a policy is only as good as its controls.** The owner's directive says
a form-only divergence may be switched off. Written down as prose, that is an
invitation to wave through a diff nobody read, so it landed as a mechanism instead.
`docs/logical-parity.md` carries the decision procedure as two ALLOWLIST tables —
seven meaning axes (a diagnostic present on one side only, a different span, a
different code, a type denoting a different SET of values, different runtime
semantics, a `.d.ts` a consumer would check differently, a different count of
distinct diagnostics) and six form axes, each with the equivalence obligation it
imposes — plus the rule that anything in neither table is MEANING by default. The
mechanism is `logicalParityDivergences` in build.gradle.kts, keyed by baseline FILE
name because that is exactly one generated subtest. Three properties are the point:
the switched-off test is emitted `@Ignore`d rather than dropped, so it stays VISIBLE
as skipped (a vanished test hides behind an unchanged total); the ledger is
REGENERATED into the doc, so the table cannot drift from the build; and the build
FAILS on either rot mode — a baseline matching no generated test, or a `pinnedBy`
class that does not exist under src/commonTest. That last check is what makes
"replace it with a test pinning the logic" mechanical rather than aspirational.
Self-tested all three paths, then reverted to the empty list; **with no entries the
generated tree is byte-identical** (`diff -r` before/after), so it costs nothing
until used.

**The judgement worth keeping from writing it:** a form-only diff is a *candidate*,
not an entitlement. The directive's own cost clause — byte parity is secondary *if it
can be achieved without extra cost* — means byte parity is still preferred where it
is free, so a divergence needs a reason it is WORTH having (it unblocks a general
rule, removes measurable work, deletes a special case). "Our output happens to differ
and matching would be fiddly" is not one.

**(COST.1) — `scripts/cost_gate.py`.** Twenty deterministic counters from a
`--passTiming` run of the compiler profile, diffed against
`docs/perf/cost-counters.txt`, failing above ±2%. Counters only, never wall time.
Baseline at 41bedb73: 46 errors, 856,962 spine nodes, 696,933 getTypeOfExpression
calls over 250,057 distinct nodes, 69,903 narrowing walks (40,546 memo-served),
89,883 bypassed type-node resolutions, 1,377,511 globals lookups at 98.9% miss.
Two design notes: the gate also pins **the compiler's ANSWER** (error count, program
file count), because a cost drop that changes the output is not a win and the gate
has to be able to see that; and four counters baseline at ZERO
(`ctxFingerprint.builds`, `globals.conflated`, `narrow.walksOutsideInit`,
`preparse.fresh`), which makes them tripwires rather than dead rows.

**THE FINDING, which is why the second run was worth 90 seconds.** The premise of the
gate is that these counters are reproducible, so I ran it twice on the same binary
rather than asserting it. Nineteen of twenty came back bit-identical — and the AST
census came back 857,350 vs 854,550, −0.33%. `indexSourceFile` runs on the crawl's
CONCURRENT parse threads (`readAndScanBatch`, Dispatchers.Default,
FRONTEND_CONCURRENCY in flight) while `PassTiming.nodeKindHistogram` is a plain
HashMap, so increments are lost to a data race and the census always undercounts.
Instrumentation-only, no production impact — but that census is documented as "the
dispatch-order / kind-table design input", i.e. **precisely what (DISPATCH.1) is
instructed to derive its per-kind handler table from**, and there a dropped rare kind
is a WRONG TABLE, not a rounding error. Excluded from the gate, warned about at the
source, and written onto the DISPATCH.1 item as its second trap.

**The build trap, and the most reusable thing in this round.** CLAUDE.md's
memory-freeing ritual before a self-compile is `./gradlew --stop && pkill -9 -f
KotlinCompileDaemon`. Doing that leaves the next `compileKotlinJvm` a COLD,
non-incremental compile, and the Kotlin daemon inherits `org.gradle.jvmargs`'
`-Xmx2g`, which a cold build of this module does not fit in. It does not present as
an out-of-memory error — it presents as a hang: 14 minutes, 350% CPU, RSS pinned
exactly at the heap ceiling, `stime` ~5 s against 3,000 s of user time, and **zero
class files written**, because Kotlin's backend only writes output at the end so
there is no partial progress to read. The same build with
`-Dkotlin.daemon.jvmargs=-Xmx3g` finished in **2m 33s**. Raising it permanently in
gradle.properties is a build-system change and therefore owner-gated — queued below
as (BUILD.1) rather than done.

**(LIB.1)(a) measured, not started.** With the two gates in, I took the next queue
item's decision step — "decide what a real project build uses for libs at all" — and
found it answerable with zero code: every `compilerOptions` key flows through
`applyDirective`, so `"useRealLibs": true` in the bench tsconfig flips the entire
real-lib path on. Four arms (table on the item): the real-lib switch costs **exactly
35 checker FPs and no measurable wall time**, and today's 46 "env-legit" FPs decompose
as 33 node globals + 13 stub residue. The decision is therefore *yes, real builds
should use real libs*, with the 35 burned down BEFORE the default flips so the
dashboard never goes red — TS2722 ×11 is over a third of them and looks like one
narrowing shape. **The reusable part:** the item read like a design fork needing an
argument, and it was a measurement needing one tsconfig line. Its own text even said
the decisive control is a MEMBER probe rather than name resolution — the same lesson
one level up.

**Also landed:** three compiler warnings had accumulated in Checker.kt's
(M3.0-gap-4) helpers (two `else` arms in a `when` the smart cast already makes
exhaustive, one redundant cast). They were invisible because Gradle does not re-emit
warnings on an up-to-date compile — the same failure mode COST.1 exists for, in a
different dimension: **a gate that is not mechanical does not happen.** Verified
clean with a full `--rerun-tasks` recompile.

---

**Round 716 (2026-07-26) — the performance diagnosis was WRONG, and now it is
measured. The type system is 28% of the compile; the dispatch machinery is 42%; the
entire prize INV.5(c) exists for is 68 ms. Three cache hypotheses died in one
session. One decisive probe found the real lever.**

Owner directive: "do anything needed, all the necessary experiments, new
architectural decisions, new tasks in the queue, to increase the performance. We are
free to completely redesign this project." So this round measured instead of
building, and the corrections are worth more than any patch would have been.
Full write-up: `docs/ARCHITECTURE-RETHINK.md` § 0 (new, supersedes § 1's diagnosis).

**What I set out to do was widen the INV.5(c) cache gate.** The reasoning looked
strong: the round-548 gate refuses to cache any resolution whose node lives outside
the file being checked, INV.3(c) had since made node-read names key by the node's
OWN file, and the gate was written *after* that landed — so it looked stale.
Instrumented: the gate rejects **65,000 of 88,829 bypassed resolutions (73.1%)**.
The premise was right.

**Then every version of the fix lost.** Widening it lifted hits 5,575 → 32,104
(23% → 46%) and ran **28% slower** over 6 interleaved pairs. The composite-key hash
probe costs more than the resolution it avoids. Memoizing the fingerprint against
the installed context maps (builds 53,765 → 13,293, a 75% cut) still measured
**+11.9%**. Pure identity keying — tsc's actual mapper-object shape — collapsed to
**4.1%** hits, because our context maps are re-allocated per install rather than
reused per region.

**Then I measured the thing I should have measured first, and it ended the whole
direction: the bypassed-resolution population is 68 ms.** 31,571 outermost calls at
2.2 µs — 0.35% of the compile. Every version of that cache was competing for a third
of one percent. This is the **third independent confirmation of one law**, after
round 665's 30 ms expression memo and round 659's 75%-reappears migration: *the
cacheable population is the cheap tail.* Recorded as a closed item so it is not
re-opened a fourth time.

**A real bug fell out of the failed experiment.** The widened key produced 3 profile
FPs, and a verify mode (recompute every hit, compare) split them: **1,269
shape-different serves**, every one a lib generic signature — `(value: T, …)` served
where `(value: Declaration, …)` was correct, under an IDENTICAL fingerprint. So the
context fingerprint is incomplete: the substitution input is ambient state captured
by none of nsStack/tpScope/aliasArgs. Not fixed (its prize is 68 ms), but named, with
the diagnostic that found it.

**Where the time actually goes** — full attribution, new INV.4(g) counters:
`checkSpine` is 83% of checker-init; inside it `spineEnterNode` 7,166 ms +
`spineLeaveNode` 5,478 ms, while the WHOLE type system (narrowing 2,437 +
getTypeOfExpression 1,804 + relations 468 + type-nodes 311 + members 36) is
**5,056 ms**. That leaves **~7,600 ms of dispatch and handler machinery** — 857k
nodes at 14.8 µs each for enter+leave, of which 8.9 µs is not type-system work.
`spineEnterNode` reaches ~118 handler entry points and `spineLeaveNode` 14
sub-dispatchers, and every handler is consulted about every node.

**The lever, with a decisive probe rather than an argument.** Per-kind timing:
`IDENTIFIER` is 44.5% of nodes at **2,746 ns each = 1,048 ms**. Skipping
`spineEnterNode` entirely for bare Identifiers left the profile's 46 diagnostics
**byte-identical**. That second is provably unnecessary. Queued as **(DISPATCH.1)**:
a per-kind handler table, with the table DERIVED by instrumentation over the corpus
(not read off the guards, and not derived from the profiles — they never exercise
some kinds). Sized 1.0–2.5 s.

**What landed:** the instrumentation only (opt-in, behaviour-free) — INV.5(c)
cache attribution, the INV.5(c5) prize timer, INV.4(g) spine-phase and per-kind
counters. Every experimental change was reverted: the widened gate, the memoized
fingerprint, the identity key, the Identifier probe. Corpus green, profile back to
46. **The box matters for anyone repeating this:** an M1 with Chrome running gives
±13% wall variance, which swamps a 1 s effect — so the trustworthy numbers here are
the in-process counters, and the wall claims are the 6-pair interleaved ones.

---

**Round 715 (2026-07-26) — measuring `expressions/asOperator` for adoption turned up a
SILENT WRONG-OUTPUT bug, which landed; the category itself did not. Corpus 12,761 →
12,765 / 0 / 3.**

`(x + 1 as number) * 3` emitted `x + 1 * 3`. Erasing the cast dropped the parentheses
with it and the `*` re-associated into the sum — the emitted program means something
else. `typeAssertionResultNeedsParens` listed class/arrow/unary/typeof/void/delete/
await/yield but not BinaryExpression or ConditionalExpression, which are precisely the
kinds with a precedence to lose. The conformance case says it in a comment: "Must emit
as (x + 1) * 3".

**The category is not adopted, for the reason round 695's own table warned about.** Its
remaining blocker (`asOperatorASI`) is a JS-EMIT test, and `conformanceDeferredError
Baselines` only defers `.errors.txt` — so failure KIND, not count, decides adoptability,
and this one needs the documented `hasPrecedingLineBreak` ASI divergence fixed in the
parser first (`var x = 10 \n as \`Hello\`` is two statements in tsc, one cast to us).
The three `.errors.txt` failures would have been deferrable; the emit one is not.

**Worth noting about the measurement table generally:** it ranks categories by failure
count, and this round shows count alone can mislead — a 5-failure category can be
unadoptable while a 9-failure one whose failures are all error baselines is fine. When
picking the next category, check the KINDS first.

---

**Round 714 (2026-07-26) — (M3.0-gap-2) PARKED with its reasoning, closing an
ambiguity in the queue rather than leaving it open-ended. Corpus 12,761 / 0 / 3.**

The item still read as work-in-progress while the decision had effectively been made
last round, and an item that reads "in progress" is an instruction to the next session.
Everything worth having from this case has shipped — the over-emitted TS7019/TS7006,
the contextual typing of an IIFE's parameters from the call arguments, all three
TS18048 including the pure-`undefined` reference and its literal-vs-reference boundary
against TS18050, and (round 713) the argument-context TS7006 hole it exposed.

What remains cannot be had cheaply: the case's TS7006 ×2 sit in PURE-DEFAULT mode,
where the full implicit-any walker is deliberately off and the narrow one covers a
single shape on purpose. Closing it means broadening that walker — the change on record
as having regressed ~19 tests — for one conformance case. Both the queue item and the
deferral comment in build.gradle.kts now say PARKED and why, so nobody re-derives the
attempt.

**The general point:** a deferral entry that explains a MISSING capability invites
someone to go build it; one that records a DECISION and its cost does not. These two
had drifted into the first kind while the answer was already the second.

---

**Round 713 (2026-07-26) — the TS7006 argument-context fix LANDED. Corpus 12,756 →
12,761 / 0 / 3, all 8 profiles byte-identical.**

Round 712 had the design right and the lookup wrong: a lib METHOD's signature
declaration lives in `builtinLibMemberDecls`, not `builtinLibDecls`, and I had tested
the parameter against the member set and the signature against the statement set —
each the other's. Correcting that put the compiler profile back to 46 with all three
target shapes still firing, and the full gates came back clean.

What landed is the edge asking the question it actually needs — the callee's PARAMETER
at the argument's index — with two measured narrowings that are now pinned as controls:
the test is SYNTACTIC because our resolved `anyType` is not tsc's `any`, and the
embedded lib is excluded because its simplified callback signatures are placeholders
rather than statements about the type.

**Worth keeping in mind for anything that reads a parameter's type to decide whether a
callback is contextually typed:** both narrowings are about the same confusion — our
`any` has three quite different origins (tsc really said `any`; we failed to resolve a
generic; our lib simplified a signature) and only the first licenses a diagnostic.
The corpus caught the second and the profiles caught the third, which is a neat
demonstration of why both gates are kept.

**gap-2 status:** the case's TS7006 ×2 are on argument arrows in a file with only
`@strictNullChecks`, i.e. pure-default mode, where the full walker is deliberately off —
so `contextuallyTypedIifeStrict` still cannot un-defer. Closing it would need the narrow
default-mode walker to cover the argument-arrow shape, which is the broadening that
regressed ~19 tests; not worth it for one case.

---

**Round 712 (2026-07-26) — implemented the TS7006 argument-edge fix, spent two
narrowings on it, and reverted at the profile gate. No production change; corpus stays
12,756 / 0 / 3.**

The edge change works: with the argument index taken at the consumer, all three target
shapes fire under noImplicitAny (including gap-2's `(f => f(12))(k => k)`) while the
contextually-typed control stays silent. What it cost was two lessons about what "no
contextual type" may be inferred FROM.

**Our `anyType` is not tsc's `any`.** Deciding on the RESOLVED parameter type red-lined
three corpus baselines: a parameter annotated with a generic or mapped type we cannot
resolve lands on `anyType` too, and those genuinely have contextual types. The test has
to be syntactic — the annotation is literally the `any` keyword, or absent. That turned
the corpus green.

**The embedded lib's `any`s are placeholders, not statements about the type.** With the
syntactic rule the profiles gained FPs on `.replace(/\./g, s => …)` and
`JSON.stringify(f, (_, v) => …)`, because our lib simplifies those callback signatures
where tsc states them precisely. Excluding the builtin-lib decl sets is the right move
and well-precedented, but my exclusion missed the `.replace` site, so the next attempt
starts by finding which set actually holds a resolved lib METHOD's parameter for a
PropertyAccess callee.

Reverted rather than landed because profile FPs violate the v1 invariant — and the
corpus was GREEN at that point, so the profiles are what caught it. Both narrowings are
on the item so the next attempt begins two steps in.

---

**Round 711 (2026-07-26) — the TS7006 coverage hole located exactly; it is a contract
mismatch, not a missing case. Probe-only, no production change; corpus 12,756 / 0 / 3.**

The argument edge is built as `typed = isCalleeResolvable(callee)` — "can I resolve the
callee name" standing in for "does this argument have a contextual type". The two
missing shapes are exactly where those come apart: `anyCb(j => j)` resolves its callee
(so it suppresses) while the parameter is `any` and supplies no contextual signature;
`(f => f(12))(k => k)` has a parenthesized ARROW callee, which falls to the function's
default `true` and suppresses too.

So the fix is to ask the question the edge actually needs — the callee's PARAMETER type
at that argument position, with no contextual signature when it is `any`, unresolved, or
not function-shaped. Encouragingly, `isCalleeResolvable` already contains one instance of
this reasoning: its B182 arm returns false when a LIB_MIN_TARGET-dropped method leaves the
callback with no contextual signature. The change generalises that arm rather than
inventing a rule.

I stopped at the diagnosis on purpose. Broadening this particular walker is the change
CLAUDE.md records as having regressed ~19 tests, so it needs a full corpus arbitration
plus `--listAll` ×8 — callback parameters are everywhere in tsc's own source — and that
is a poor fit for the tail of a long session.

---

**Round 710 (2026-07-26) — correcting round 709's own finding before anyone acts on
it. Probe-only, no production change; corpus stays 12,756 / 0 / 3.**

Round 709 concluded that our two TS7006 emitters have "inverted option gates" and told
the next round to unify them. Reading the dispatch shows that is wrong, and acting on
it would have undone deliberate work: `checkImplicitAnyDefaultVarFunctions` runs only
in pure-default mode and deliberately covers ONE shape, because broadening the full
`checkImplicitAnyParameters` walker was MEASURED to regress ~19 tests. The two are
mutually exclusive so they cannot double-emit. The "swap" my matrix showed is exactly
that design: different modes, different walkers, different coverage.

**What survives is a smaller, genuine defect:** `anyCb(j => j)` is reported in
pure-default mode but NOT under noImplicitAny. Turning the stricter option ON should
never lose a diagnostic, whatever the walker split is. And gap-2's
`(f => f(12))(k => k)` is uncovered in both modes. So the target is a coverage hole in
the full walker — argument arrows whose callee parameter provides no contextual type —
not the gates.

**The lesson is the one I wrote down last round and then broke immediately.** I said a
wrong characterisation in the queue is worse than none, because the next round starts by
trusting it; then I wrote "inverted gates / plain bug" from a black-box matrix without
reading the dispatch that explains it. Two greps would have prevented it. A behavioural
matrix tells you WHAT differs; it does not license a claim about WHY, and "this looks
like a bug" about deliberate, documented, measurement-backed code deserves more
suspicion of my own reading than of the code.

---

**Round 709 (2026-07-26) — the TS7006 gate question is settled, and the answer is a
defect in its own right: our two emitters have INVERTED option gates. Probe-only, no
production change; corpus stays 12,756 / 0 / 3.**

Running round 708's four shapes under two configurations gives a matrix no single
option setting can satisfy: with `strictNullChecks` alone, `anyCb(j => j)` fires and
`function plain(m) {}` does not; add `noImplicitAny` and they swap. Turning the option
ON switches OFF the emitter that was firing. `anyCb(j => j)` going silent under
noImplicitAny is simply wrong — tsc reports it.

**Which convention is right is answerable from the corpus:** 12 of 22 sampled TS7006
baselines have no `@noImplicitAny`/`@strict` directive, so TS7006 fires by DEFAULT in
the reference, and the codebase's `!strictExplicitlyFalse` convention is the one that
matches it. The `noImplicitAny || strict` gate on the other emitter is the outlier.

So the next round's job is to unify the two gates on the default-on convention and
re-gate — not to work on IIFEs. The gap-2 shape may fall out of that, since the
conformance case sets only `@strictNullChecks: true`; if it does not, at least it will
be failing for one reason instead of two.

This is the second round running where the recorded framing of the "last small piece"
turned out to be wrong, and both times a two-configuration probe was enough to replace
a guess with a matrix. Worth preferring that over one-config probes when a diagnostic's
presence could plausibly depend on options.

---

**Round 708 (2026-07-26) — probe-only on gap-2's last piece; it is not the gap the
item described. No production change; corpus stays 12,756 / 0 / 3.**

The item said an arrow passed AS an argument does not get its parameters checked for
implicit any. Four contrasted shapes in one file say otherwise: `anyCb(j => j)` against
an `any` parameter DOES fire TS7006, and `take(i => i)` against an annotated parameter
is correctly silent — so the walker reaches argument arrows and distinguishes
contextually-typed ones. `(f => f(12))(k => k)` is silent, which is the gap; but in the
same file a plain `function plain(m) { return m; }` is silent too, and that has nothing
to do with IIFEs or arguments.

So the next step is not the callee-typing path the item pointed at: it is to settle
which shapes emit TS7006 under which options, since a top-level function declaration's
parameter and a callback's parameter evidently disagree here. Recorded on the item with
the four probe results, so that round starts from evidence rather than from my earlier
framing.

I stopped at the probe deliberately: this is a different subsystem from the last three
rounds' work, my context for the session is long, and a gate question deserves a clean
start rather than the tail of a session.

---

**Round 707 (2026-07-26) — the last TS18048 gap closed; `contextuallyTypedIifeStrict`
is now one diagnostic family from un-deferral. Corpus 12,756 / 0 / 3, all 8 profiles
byte-identical.**

Round 716 handled `T | undefined` operands; a reference typed exactly `undefined` was
still silent. The cause was a strictNullChecks early return in the arithmetic walker
that hands operands carrying the Null/Undefined flag to TS18050 — correct for the
LITERAL `undefined`, wrong for a reference, which tsc reports as TS18048. Offering the
operand to the possibly-undefined check before that return fixes it, and isolating it
took one three-case probe (`a: number | undefined` fired, `c?: number` fired,
`b: undefined` did not).

**The corpus then caught the guard I had missed**, and it is a nice example of why the
literal/reference distinction had to be explicit: `undefined` parses as an Identifier
here, so it HAS a reference path, and the first cut emitted "'undefined' is possibly
'undefined'" — three real baselines (operatorAddNullUndefined, binaryArithmatic2/3)
rejected it immediately. Excluding the nullish keywords by name is exactly the TS18050
boundary the original comment described.

All three of the case's TS18048 ('j', 'k', 'o') now fire at the baseline's positions.
The remainder is the two TS7006 for the INNER function's parameter in
`(f => f(12))(i => i)` — an arrow passed AS an argument does not get its own parameters
checked for implicit any.

---

**Round 716 (2026-07-26) — the possibly-undefined operand rule LANDED, after settling
the pin question with evidence rather than authority. Corpus 12,756 / 0 / 3, all 8
profiles byte-identical.**

Round 705 left this deliberately unlanded: the rule was right by one baseline, but it
flipped nine of our own pins, and rewriting other rounds' pins on a single data point
is not a patch. The cheap way to settle it turned out to be the reference baselines
themselves — `git grep` over `tests/baselines/reference` for TS18048 (418 occurrences)
and for TS2362 co-occurring with "possibly undefined" (two files). The second baseline,
`circularOptionalityRemoval`, reports TS18048 for `x > 0` with `x: number | undefined`
— a different operator from the additive case, so the rule is about the OPERAND, not
one syntax. And the two apparent counter-examples are not: their TS2362 operand is a
`delete` expression, i.e. a boolean.

With the direction established, the nine pins were updated — intent unchanged ("narrowing
did not apply, so it still fires"), only the expected code — and one paired positive
control strengthened to exclude TS18048 as well, so a future regression cannot make the
NARROWED case start reporting it unnoticed.

**The reusable point:** when a change turns your own pins red, the question is which of
the two encodes the reference behaviour, and that is usually answerable from the
baselines in a couple of greps. Deferring it one round cost nothing and produced a
better-supported change than landing it under time pressure would have.

---

**Round 705 (2026-07-26) — the TS18048 rule for a possibly-undefined arithmetic
operand: written, working, and REVERTED because it collides with nine of our own pins.
No production change; corpus stays 12,756 / 0 / 3.**

tsc checks possibly-undefined BEFORE it checks operand kinds, so `j + 1` with
`j: number | undefined` is "'j' is possibly 'undefined'", not a complaint about the
operator. Adding that check ahead of the three arithmetic emitters produces exactly the
TS18048 the reference baseline wants.

**Then nine LOCAL pins went red — and not one corpus test.** They live in four
arithmetic narrowing test classes and assert that a maybe-undefined operand fires
**TS2362**, with names like "negative control - genuinely maybe-undefined operand still
fires TS2362". Their intent is "narrowing did not apply, so it still fires", which
TS18048 satisfies; only the code differs.

**Which is right matters more than which is convenient.** The evidence: the
`contextuallyTypedIifeStrict` reference baseline — real tsc output — reports TS18048 for
exactly this shape, and the corpus is green with or without the change, so it does not
discriminate. That points at the pins encoding OUR old behaviour rather than tsc's. But
rewriting nine pins written by earlier rounds, at the end of a long session, on the
strength of one baseline, is a decision rather than a patch: I reverted, recorded the
rule and the evidence on the item, and left it to a round that can confirm the direction
against another baseline first and re-gate properly.

---

**Round 704 (2026-07-26) — an IIFE's parameters are now contextually typed from the
call arguments. Corpus 12,750 → 12,756 / 0 / 3, all 8 profiles byte-identical.**

Round 694 wrote this in the wrong place and measured it inert; the hook is
`populateParameterLocalTypes`, because the body walkers read `currentLocalTypes` and
nothing else. Written there, the decisive probe flips: `((a) => a.nope)("x")` reports
TS2339 on `string` — widened, as tsc widens the argument's literal type.

**Two pieces of the case remain, and the second is the more interesting.** Only ARROWS
are typed — a function EXPRESSION IIFE is not, and the branch I expected to be
responsible (the blanket `any` for a callback's own parameters) turned out not to be:
deferring there changed nothing. That is now a LIMITATION PIN rather than folklore, so
the next person knows both the gap and one place it is not. And the typed parameters
now produce the right analysis under the wrong code: `((j?) => j + 1)(12)` reports
TS2365 where tsc reports TS18048, which is the documented round-415 hazard — a union
carrying `undefined` fails the arithmetic operand classifier. tsc checks
possibly-undefined first, so a nullish-operand rule ahead of TS2362/2363/2365 is what
closes the case.

**Four times this session a first patch turned out to be inert** (rounds 700, 702, 704
twice), and every one was caught immediately because the probe was built to fail if the
change worked. That is the single most valuable habit this run reinforced.

---

**Round 703 (2026-07-26) — (M3.0-gap-4) CLOSED; `readonlyRestParameters` is
un-deferred. Corpus 12,746 → 12,750 / 0 / 3, all 8 profiles byte-identical.**

The half round 702 wrote and removed as inert needed one correction, and it was not in
the logic: `emitTS2554TooMany` opens with `if (firstExcessIdx >= args.size) return`,
and I had passed the EXPANDED COUNT where it wants an ARGUMENT INDEX — with two
arguments and a count of two, it returned silently. The reference baseline states the
same fact in a way I could have read earlier: the squiggle is on the SPREAD, because
that is the argument the third one lives inside. Found by reading the emitter rather
than by probing, which was quicker than the marker probe I had queued.

**A process catch worth recording.** After un-deferring I expected the suite to gain
four tests (three pins plus the recovered subtest) and it gained one. The pins were
missing: my Python heredoc's `"""` string collided with the Kotlin raw strings inside
it, and the script still printed its success message. The count discrepancy is what
caught it — the same shape as round 690's stale-XML trap, and the reason to state an
expected count before running rather than after. Rewritten with the editing tool.

**Deferral ledger:** two conformance cases were deferred when the category landed;
`arrowFunctionContexts` cleared in round 692 and `readonlyRestParameters` clears here.
One remains — `contextuallyTypedIifeStrict` (M3.0-gap-2) — plus
`commaOperatorOtherInvalidOperation`, whose (A) and (B1) shipped and whose (B2) is the
relation-leniency work.

---

**Round 702 (2026-07-26) — (M3.0-gap-4)'s TS2556 half landed. Corpus 12,740 →
12,746 / 0 / 3, all 8 profiles byte-identical.**

An unbounded array spread into a fixed-arity call cannot be arity-checked, so tsc
rejects it; we reported nothing, because the arg-count pass suppresses a too-FEW
conclusion whenever a spread is present and nothing took over. The rule itself is one
condition — but it took three suite runs to find its four narrowings, and each came
from a test rather than from thinking about it: a TUPLE spread is legal, an ARRAY
LITERAL spread is legal (tsc counts `...[6, 7]` as two arguments), spreading INTO a
rest parameter is legal, and when the fixed arguments already exceed the maximum tsc
reports the COUNT rather than TS2556. The last two came from a local pin and a corpus
baseline going red, which is exactly what those gates are for.

**Inert-change discipline paid again, twice.** The first cut did not fire at all: a
rest PARAMETER's type does not resolve in the arg-count pass, so the operand is now
classified from its ANNOTATION when the resolved type is unavailable — which also
handles `readonly string[]` (a TypeOperator around an ArrayType) for free. And the
TS2554 half of the item, which I also wrote, never fired either; rather than land dead
code I removed it and recorded the design plus the specific next diagnostic step on
the item.

---

**Round 701 (2026-07-26) — (M3.0-gap-3)(B1) LANDED, six rounds after it was first
picked up. Corpus 12,737 → 12,740 / 0 / 3, all 8 profiles byte-identical.**

The recorded recipe re-applied cleanly and the corpus went green immediately; the
question was only the profiles. Round 700's guard-parameter INFERENCE fix removed two
of the three FPs. The survivor was the third shape — `return nodeTest(node) ? node :
undefined` in `getOriginalNode` — which is the same parameter-borne guard used for
NARROWING rather than inference, and `narrowByCallPredicate` bails the moment
`resolveFlowCalleeDecl` returns null. Giving it the same fallback
(`parameterGuardFunctionType`: the parameter's FunctionType annotation supplies exactly
the declaration triple it needs) cleared it, and all eight profiles returned to their
baseline.

**Observability was measured, not assumed.** B1's corpus effect is neutral by
construction — it fixed exactly the regressions it caused — so before committing I
A/B'd a candidate pin against the stashed tree: without the scope the snippet draws
NOTHING (`y` is `any`, so `y.v` is too), with it the TS2322 appears. That is what makes
it a landable fix rather than an unpinnable refactor, and it is the check I would have
skipped three rounds ago.

**One of my own pins failed and the compiler was right.** I asserted the
arbitrary-type chain under `@strict: false`, where a `null` return is assignable to
anything and there is no diagnostic at all. The power-assert diagram showed
`diags == []` immediately. Worth noting because the reflex is to suspect the change.

**What is still open on the item:** the deferred conformance case needs the second
TS2322 (`var result: T1 = (x, y)`), which is TypeParam-vs-TypeParam and gated by the
relation leniency measured back in round 695 at exactly 2 corpus tests. That is (B2)
territory, and the case stays deferred. The engine work landed here — body-variable
type resolution, chain parity, apparent constraints, and both halves of
parameter-borne guard handling — is worth more than the case that surfaced it.

---

**Round 700 (2026-07-26) — the guard-inference gap round 699 named is CLOSED and
landed. Corpus 12,731 → 12,737 / 0 / 3, all 8 profiles byte-identical.**

Round 699 ended with (B1) blocked behind three profile FPs that turned out to be one
family. This round attacked that family directly, which was the right call: it is a
real gap in its own right, and closing it is what unblocks (B1).

**Making it observable came first.** With (B1) reverted the target resolves to `any`,
so the FPs cannot be reproduced — the gap is invisible from the diagnostic side. The
probe that exposes it is to assign the call result to a deliberately wrong concrete
type and read the inferred type out of the MESSAGE. Four contrasted cases in one file
then localised it precisely: a guard with a CONCRETE target inferred correctly
(`Special | undefined`) while the same call with the caller's `T` inferred the element
type — so the machinery worked and only the type-parameter case did not.

**Two independent halves were missing**, and the first fix alone was INERT — caught
immediately, because the probe was built to fail if the change worked:
(1) `predicateTargetTypeOfGuardExpr` resolved a guard only from a function
DECLARATION, but here it arrives as a PARAMETER, whose annotation carries the
predicate; it now walks out to the nearest enclosing signature declaring that name,
innermost-first. (2) The target is then the caller's `T`, which does not resolve
through the ambient scope there, so it is interned from the enclosing signature's own
TypeParameter declaration. With both, `find(tags, predicate)` gives `T | undefined`
and `tags.filter(predicate)` gives `T[]` — what tsc gives.

The six pins keep the halves separable: the concrete-target controls distinguish "the
guard was found" from "its target resolved", so a future regression localises to one
of them rather than to the pair.

---

**Round 699 (2026-07-26) — (M3.0-gap-3)(B1) reached corpus ZERO with every residual
fixed, and then the PROFILES killed it. The blocker is now known, specific, and not
where four rounds of work had been pointing.**

The recipe landed all four residuals: the errorType guard for (a); an
apparent-constraint walk for (b), which also had to go into the ASSIGNMENT path
(`V extends U extends A` is decided there, not in the return path — the failing case
was an assignment all along, which I only noticed by reading the case source rather
than trusting the item's classification); and an identity-keyed end-of-`init` dedup
for (c)/(d), since dedicated pin walkers run AFTER the spine and own some of those
positions with better displays. Corpus: **12,731 / 0 / 3**.

**Then `--listAll` ×8 went 46 → 49 on every profile.** Three new false positives, the
same three everywhere, all in `compiler/utilitiesPublic.ts` — and reading the source
made them one family, not three: **type-guard-driven generic inference**.
`getFirstJSDocTag<T extends JSDocTag>(…, predicate: (tag: JSDocTag) => tag is T)`
returns `find(tags, predicate)`; `getAllJSDocTags` returns `…filter(predicate)`;
the third is `nodeTest(node) ? node : undefined`. tsc binds the callee's parameter to
the CALLER's `T` through the `tag is T` predicate, so those sources ARE `T | undefined`
and `readonly T[]`. We bind the concrete `JSDocTag`. The mismatch was invisible while
the return annotation resolved to `any`; resolving the target is what exposed it.

**So the change cannot land, and that is the correct outcome, not a setback.** v1's
dashboard sits at zero real FPs; trading three of them for an internally-more-correct
type resolution would be a bad deal, and gating them away would need a heuristic that
still lets `function f<T>(): T { return null; }` error — precisely where a heuristic
would quietly lose real errors. The honest next step is the one that also has value on
its own: make guard-driven inference bind the caller's type parameter. Round 430
already built "TP-from-PREDICATE binding" for this exact family, so the question is
why it yields `JSDocTag` here.

**Worth saying plainly after five rounds on this item:** (A) shipped, (B1) is fully
written and corpus-green but blocked behind a named M3.1 gap that is bigger than the
conformance case it came from. The case stays deferred. If the next round does not
close the guard-inference gap quickly, the right move is to re-scope — the conformance
case is not worth a generic-inference project, though the generic-inference project may
well be worth doing on its own merits.

---

**Round 698 (2026-07-26) — (M3.0-gap-3)(B): 4 residuals → 3, and the last two are
now understood to be DOUBLE EMISSIONS rather than false positives. Reverted again
(the corpus must stay green), but the remaining path is short and named.**

Two findings, both of which change what the next round should do.

**Residual (a) fell to a one-word widening.** `declFileGenericType`'s unconstrained
`<T>` was reading as `constraint 'any'` — but that is not the `anyType` singleton, it
is **errorType**, which DISPLAYS as `'any'` (B58.1). Guarding the chain block against
`anyType || errorType` fixes it. Worth remembering generally: "the display says any"
never distinguishes anyType from an unresolved type, so a guard written against the
display's meaning has to test both.

**(c) and (d) are not FPs — I had mis-classified them.** Reading the reference
baselines shows both diagnostics ARE expected; what changes is our error COUNT (5 → 6,
8 → 9). The `Diagnostic`-init probe named (c)'s other emitter: the dedicated pin walker
`checkDeeplyNestedMappedTypes`, which exists *because* the engine could not produce
that diagnostic — and its display is the CORRECT one, while the engine renders the
source as `any[]` (the case's mapped aliases resolve to any). So the engine does not
supersede that walker, and the walker must not be deleted. The ORDER decides the fix:
the engine emits FIRST, the walker later, so an "already reported here?" probe in the
engine cannot see it — the retraction belongs in the walker, for which the codebase
has precedent.

**Reverted rather than landed** because three corpus tests would be red, and the
corpus is the gate every round leans on. The recipe on the item now carries the
errorType guard, so the next round re-applies it and starts at THREE failures, each
with a named fix: a syntactic constraint-text fallback for (b), and walker-side
retraction for (c)/(d).

---

**Round 697 (2026-07-26) — (M3.0-gap-3)'s (A) half LANDED: a comma expression's
return type is now its right operand's. Corpus 12,725 → 12,731 / 0 / 3, all 8
profiles byte-identical.**

After three consecutive rounds that ended in a revert, I deliberately picked the
half of the item that is independent of the risky type-parameter machinery, and
landed it. `combineBinaryTypes` already typed a comma as its right operand;
`inferReturnTypeFromBody` had no Comma case, so `function foo(x: number, y: string)
{ return x, y }` inferred `any` and every call site went unchecked.

**The interesting constraint is WHERE the operand's type may come from.** This
inference runs in the CALLER's scope — it is reached while checking a call site —
so resolving the callee's parameter by name would hit the documented shadowing
hazard. That is exactly why the function's existing plain-Identifier arm resolves
nothing but `true`/`false`, and copying `getTypeOfExpression` in would have been the
easy wrong answer. The operand is typed from the OWNING function's own parameter
annotations instead, reached through the body's parent — scope-independent, and null
whenever it cannot be read, so the change only ever adds precision. Two of the six
pins are that boundary from both sides: an un-annotated operand must infer NOTHING
rather than guess, and a same-named outer binding must not leak in.

This clears the first of the deferred conformance case's two missing TS2322; the
second is (B), which stays as recorded below, so the case remains deferred.

---

**Round 696 (2026-07-26) — (M3.0-gap-3)(B1) diagnosed to a one-line cause, its fix
written and MEASURED, then reverted because it must land together with chain
parity. No production change; sources verified byte-identical to the green tree.**

**The cause is dead state.** The cta frame computes a type-parameter SCOPE at
frame-build time and stores it as `CtaFrame.fnTpScope` — and `grep fnTpScope` returns
exactly two hits: its declaration and its single write. Nothing reads it. The
per-statement dispatch installs `currentTypeParamDecls = frame.fnTpDecls` but not the
scope, so every annotation resolved during that dispatch sees no type parameters.
That is why `var r: T1` resolved its target to `any` while the parameter `y: T2`
resolved fine: parameters are resolved while building the signature, body variables
during this dispatch. Installing the scope in the same save/restore sandwich is one
line, and it works — probe: `T1` and `T1[]` instead of `any` and `any[]`.

**The measurement is the deliverable.** 27 corpus tests fail with it — but of ~32
changed baseline lines, **~29 are REMOVED chain lines** (`'T' could be instantiated
with an arbitrary type which could be unrelated to 'null'/'undefined'`): the
diagnostic still fires, only its second line is gone. With `T` resolving to a real
`Type.TypeParam`, `return null`-in-a-generic stops falling through to the STRING
fallback `emitTS2322(…, typeParams)`, which adds that chain, and is handled by a
type-engine emitter that lacks it. The var-decl and assignment paths already carry a
`tt is Type.TypeParam` chain block; the return path's engine emitter needs the same.
Only 3 lines were additions: one chain-form flip and two genuinely new diagnostics.

**Why I reverted rather than narrowed.** Scoping the install to the var-decl
annotation alone would dodge all 27 — and be INERT, because the emission still needs
(B2) (`canUseTypeEngine` refuses TypeParam-vs-concrete) and the relation leniency.
Landing an inert change is the exact failure mode rounds 693/694 spent themselves on.
The next round does it as one coherent change: chain parity first, then the scope
install, then (B2), gated together.

**Attempt 2, same round: 27 → 4, then reverted at the budget line.** The stack-trace
probe in `Diagnostic`'s `init` (the documented technique) named the emitter in one
run — `Checker.kt:31816`, i.e. real line **97352** after adding 65536, the wrap
CLAUDE.md warns about. It is `checkReturnAssignability`'s engine emitter, which builds
its chain for Object→Object and Union sources and simply has **no TypeParam-target
branch**, unlike the var-decl and assignment paths. Adding that block cleared **23 of
the 27**. The remaining four are each diagnosed on the item: two are chain-FORM
mismatches in opposite directions (an unconstrained `T` arriving with
`constraint == anyType` picks the constraint form where tsc uses the arbitrary one;
an intersection source picks the arbitrary form where tsc uses the constraint one),
and two are genuinely new emissions against targets that only became checkable once
the scope resolved them — a mapped-type return and a DEFERRED conditional type, both
M3-depth. I reverted rather than land four red tests or rush a gate for two FPs that
are also the most likely to show up on the profiles.

**Two rounds of suspects eliminated by measurement, in order:** `typeParams`
threading (arrives correctly), the round-431e foreign-TP gate (never fires), the
relation's unconstrained-TP leniency (changing it leaves the case silent; costs
exactly 2 corpus tests), and now the scope install is *confirmed correct* with a
fully-classified bill. What is left is not diagnosis but implementation.

---

**Round 695 (2026-07-26) — three more conformance categories adopted, chosen by
MEASURING twelve of them in one run rather than guessing; one of their three
failures fixed. Corpus 12,685 → 12,725 / 0 / 3.**

**The method is the point.** Round 690 adopted a category and then discovered how
red it was. Adopting is cheap to try and expensive to guess at, so this round put
TWELVE candidate categories into the allowlist at once (+236 tests), ran the suite
ONCE, and mapped the 91 failures back to their categories — then reverted all but
the tractable ones. That is a ~7-minute run for a table that tells every future
round what each category costs: `es6/defaultParameters` **0**, `es6/restParameters`
**1**, `expressions/commaOperator` **2**, then `asOperator` 5, `types/any` 6,
`conditional` 8, `nonPrimitive` 9, `labeledStatements` 9, `typeAliases` 9,
`contextualTyping` 9, `typeSatisfaction` 12, `optionalChaining` 21. The three
cheapest are adopted; the table lives on the M3.0 item.

**One number in it is a trap worth naming:** `statements/labeledStatements` is 9
failures from only 8 files, and several are **JS-emit** subtests. The deferral
mechanism only covers `.errors.txt`, so that category cannot be adopted at all
until the emit gap is fixed — a category's failure COUNT does not tell you whether
it is adoptable; the failure KIND does.

**The fix: a missing comma operand's TS2695 span.** `(, ANY)` has no left operand.
Our recovery synthesizes an empty-text Identifier at the offending token, while
tsc anchors its missing node at the FULL START — the end of the previous token,
before trivia — with no width. Both halves are load-bearing and only one is
visible in the obvious test shape: the POSITION differs only when trivia separates
`(` from `,` (`( , )` → tsc reports at the `(`'s end, we reported at the `,`), and
the zero LENGTH is what sorts TS2695 before the same-position TS1109, because the
comparator is start → length → code and 2695 > 1109. I kept the fix in the comma
emitter rather than the parser's shared missing-expression recovery, which anchors
many other diagnostics; tsc reaches this position per-site too.

**A gate that was decisive instead of merely reassuring.** The `--listAll` ×8 came
back at the usual 46 ×7 / 94 harness, but the number that actually settles it is
that those profiles emit **zero TS2695** — so the touched emitter contributes
nothing to them and could not have changed their output. When a change is confined
to one emitter, "does that emitter fire on the profiles at all" is a stronger and
cheaper check than diffing two full runs.

**A bounded experiment at the tail, reverted — and the revert is the finding.**
(M3.0-gap-3)'s type-parameter half looked like the relation engine's leniency
("two unconstrained type parameters always relate"), so I tried the correct rule
(relate only when their names match — identity is unusable, interning is
per-AST-position). Cost: exactly **2** corpus tests, both
`Type 'SetOf<B>' is not assignable to type 'SetOf<B>'` — identical display, which
means the leniency is masking an un-substituted class type parameter in a member,
an M3.1 substitution gap. But the decisive probe was the other one: with the strict
rule in place `var direct: T1 = y` was **still silent**, so the relation was never
the blocker. Reverted; the measured cost is on the item so nobody re-runs it. Same
discipline as rounds 693/694: the probe has to be one that FAILS if the change works.

**Then a marker probe found what (B) actually is, and it is two things, neither of
them the relation.** Printing both sides' `typeToString` plus
`canUseTypeEngine`/`checkTypeRelatedTo` at `checkVarDeclAssignability`'s gate, over
four deliberately-contrasted cases: **(B1)** `var r: T1` resolves its target to
**`any`** — a type-parameter annotation on a function-BODY variable never reaches the
type parameter, while the PARAMETER annotation `y: T2` resolves fine, because a
parameter is resolved while building the signature with the TPs in
`currentTypeParamScope` and a body variable is not. That is round 691's generic-arrow
bug one scope level out, and it means no relation could ever have failed here.
**(B2)** for `var s: string = x` the relation ALREADY returns the correct `false`, but
`canUseTypeEngine` refuses a TypeParam-vs-concrete pair, so the correct verdict is
never emitted. The threading the queue item warned about is fine — the probe shows
`tp=[T]` / `tp=[T1, T2]` arriving and the foreign-TP gate not firing. Three plausible
suspects (threading, foreign-TP gate, relation leniency) excluded by measurement, and
the two real ones named, in one probe cycle.

**Deferred with queue items** (the two remaining failures, both error baselines):
(M3.0-gap-3) the comma operator's result type is not the right operand's type, so
`return x, y` and `var r: T1 = (x, y)` miss TS2322 ×2; (M3.0-gap-4) a
`readonly T[]` spread argument is neither rejected with TS2556 nor counted for
TS2554 — the spread rule that is right for a tuple is wrong for an unbounded array
into a fixed-arity signature.

---

**Round 694 (2026-07-26) — attempted the rest of (M3.0-gap-2), found the
implementation UNOBSERVABLE, reverted it, and located the real hook.** No
production code changed; corpus stays 12,685/0/3.

**What I built.** `applyIifeParameterTypes` next to `applyContextualParameterTypes`
in `getTypeOfArrowFunction`: for an immediately-invoked arrow, type each
un-annotated parameter from the corresponding call ARGUMENT (rest parameter → array
of the union of the remaining arguments; optional parameter → `T | undefined`;
missing argument for an optional → `undefined`). It compiled, and the corpus stayed
green.

**Why it was reverted.** It does nothing. The decisive probe was not the
conformance shapes — those merely stayed silent, which is ambiguous — but
`((a) => a.nope)("x")`, which MUST report TS2339 if `a` is typed `string`. It
reported nothing. The reason: the body walkers do not read `symbolTypes` for
parameters. They read `currentLocalTypes`, filled by
`populateParameterLocalTypes`, whose very first condition is
`if (paramType != null && paramName is Identifier)` — it records a parameter ONLY
when it has an ANNOTATION. An un-annotated parameter is invisible to them however
the signature is typed.

**Third time this session.** Rounds 687, 688 and 693 each turned on the same
question, and this is the sharpest instance: a change can compile, keep the corpus
green, and be entirely inert. The discipline that catches it is a probe that must
FAIL if the change works — `a.nope` had to become an error — rather than a probe
that merely observes the target case still being quiet.

**Where the fix belongs**, recorded on the item: `populateParameterLocalTypes`,
using the parent-walk from round 693's `isImmediatelyInvokedFunctionParam`. Expect
a wide blast radius — it hands types to parameters that are currently `any` across
~26 walker call sites — so it wants its own round with the corpus and `--listAll`
×8 gates, not the tail of one.


**Round 693 (2026-07-26) — (M3.0-gap-2)'s false-positive half fixed: an IIFE's
parameters no longer draw implicit-any. Corpus 12,685/0/3.**

tsc contextually types an immediately-invoked function's parameters from the
call's ARGUMENTS, so it reports nothing for them — including when the call passes
none. We emitted TS7019 ×3 + TS7006 ×2 on the conformance case.
`isImmediatelyInvokedFunctionParam` walks the parameter's owner up through
parentheses to a CallExpression whose unwrapped callee is that function; the
unwrapping is not decoration, since the corpus shape is routinely written
`(function (x) { } ("!"))` and `((((function (y) { }))))("-")`.

**The instructive part: my first patch changed NOTHING.** I gated
`checkParamsForImplicitAny`, rebuilt, re-probed — byte-identical output, all three
diagnostics still there. There are TWO TS7019 emitters, and the live one for
these shapes is the dedicated rest-parameter walker, which carries its own TS7019
*and* its own TS7006. Both now carry the rule, and the pins deliberately cover
the rest-parameter shapes because those are the ones a half-fix leaves behind.
This is the same lesson as rounds 687–688 in a new place: when a change produces
no observable effect, find out whether the code you changed is the code that runs.

**A control that was wrong about current behaviour.** My first negative control
asserted a non-invoked arrow `const f = (first, ...rest) => rest` still draws
TS7019. It does not — and did not before this change either: a rest parameter in
a var-initializer arrow never reaches the rest walker. That is an unrelated
pre-existing gap, and the structural argument settles it without a bisect (my
gate requires a CallExpression parent, which that shape has none of). The control
now pins TS7006 there and a plain function declaration carries the TS7019 case.

**Still open in gap-2**, and the reason the case stays deferred: the parameters
are not actually TYPED from the arguments, so the reference's TS18048 ×3 (optional
IIFE parameters under strictNullChecks) and TS7006 ×2 (the INNER function's
parameter in `(f => f(12))(i => i)`) do not fire. That needs real contextual
typing from a call's arguments — `applyContextualParameterTypes` is the machinery
to extend — not another suppression.


**Round 692 (2026-07-26) — (M3.0-gap-1) CLOSED: the conformance case's two
missing diagnostics implemented, the case un-deferred, corpus 12,671/0/3.**

**TS18033 for a function-valued computed enum member.** The emitter existed but
was gated on the initializer being STRING-typed, so `enum E { x = () => 4 }` drew
nothing. Extended to an initializer that is SYNTACTICALLY an arrow or function
expression — FP-safe by construction, since a function can never satisfy a
computed member's numeric domain, so tsc always reports it. The message renders
the resolved signature, which is why it reads `Type '() => number' …` rather than
the string branch's fixed word; that depends on round 691's generic-arrow fix
having landed, and is a small example of one fix making the next one's output
correct for free.

**TS2332 for `this` inside an arrow in an enum initializer.** The walker's own
doc said it "Skips function/class/arrow boundaries (those rebind `this`)" — but
an ARROW DOES NOT REBIND `this`. That is the entire point of arrow functions, and
it is why tsc reports `enum E { y = (() => this).length }`. The descent now
covers arrow expression and block bodies while function expressions and class
bodies stay skipped. One detail the baseline settled rather than intuition: the
arrow-nested form gets TS2332 ONLY, with no companion TS2683, unlike the bare
`this` — so the descent passes `emitTs2683 = false`. I checked the reference
before wiring it rather than assuming symmetry.

**Gates.** Corpus 12,671/0/3 (+6 pins, including a function-expression negative
control that pins the rebinding boundary — the one thing a future refactor of
that walker is most likely to get wrong), `--listAll` ×8 byte-identical.

**An infrastructure blip worth not misreading.** One suite run reported BUILD
FAILED after 10m45s with ZERO result XMLs, between two clean green runs of the
same tree. No compile error, no test failure — nothing to attribute it to, and
`compileTestKotlinJvm` was clean immediately after. Most likely the results
directory being removed while Gradle held it. If a run fails with no XMLs at all,
re-run before investigating: a real failure leaves evidence.

**M3.0 scoreboard so far.** Seven conformance files, adopted in round 690, have
now yielded one whole-program mistyping (every generic arrow) and two missing
diagnostics. One deferral remains: (M3.0-gap-2), contextual typing of IIFE
parameters.


**Round 691 (2026-07-26) — the first bug the new conformance category caught:
EVERY generic arrow was silently mistyped `<T>(n: T) => any`.**

`getTypeOfArrowFunction` interned the arrow's OWN type parameters only when it
constructed the `Signature` — at the bottom of the function, AFTER the return had
been inferred. So while inferring `<T>(n: T) => n`, the parameter annotation `T`
resolved to nothing, `n` never entered `currentLocalTypes` (that loop skips
any/errorType), the body typed as `any`, and the arrow's type came out
`<T>(n: T) => any`. The visible symptom was one TS2403 false positive; the actual
scope was every generic arrow in every program.

**Isolation did the work.** Three two-line cases, run together: a NON-generic
arrow with the same array-literal body (`(n: number) => [n]`) was already
correct, while a generic IDENTITY arrow (`<T>(n: T) => n`) was not. That single
run ruled out return inference in general AND the TS2403 comparator — which the
error message had been pointing at, since it renders a fn-type against a
call-signature object — leaving type-parameter scope as the only candidate. The
fix is the rule CLAUDE.md already states for interface call/construct signatures:
push the owning declaration's type parameters before resolving anything that can
reference them. Constraints and defaults now resolve under that scope too, so a
constraint naming a sibling type parameter resolves.

**Gates.** Corpus 12,663/0/3 (+7 pins, including non-generic controls that
localise a future regression to the scope rather than to inference), `--listAll`
×8 byte-identical on all eight profiles. The dashboard not moving is worth
stating rather than assuming: tsc's own sources use generic arrows heavily, so
byte-identity means the mistyping had been masked there — the `any` return was
being absorbed by paths that accept it — not that the shape is rare.

**What this says about M3.0.** The category cost one round to adopt and found a
whole-program mistyping in the next. Seven files.


**Round 690 (2026-07-26) — M3.0's infrastructure LANDED and the first
conformance category adopted: `expressions/functions`, 12 test functions, corpus
12,651 → 12,663 with zero regressions.**

**Three edits as scoped, plus a fourth I did not foresee.** The scoped three
(sparse paths, recursive collection, per-file case path) went in as planned. The
fourth was found only by running the tests: a JS baseline's first line is a
PROVENANCE HEADER echoing the case's real corpus path
(`//// [tests/cases/conformance/expressions/functions/X.ts] ////`), and
`BaselineFormatter` hardcoded `tests/cases/compiler`. That single mismatch
accounted for **6 of the 9 initial failures** — every one of which looked like a
compiler defect in the failure list and was nothing of the sort. `casesDir` is
now threaded through the four formatter entry points with the compiler path as
default, so existing callers are untouched.

**Triage: 9 → 2.** After the header fix the only survivors are two genuine
checker gaps, now queued as (M3.0-gap-1) and (M3.0-gap-2): an arrow used as a
computed enum member value (missing TS18033/TS2332, over-emitted TS2403), and
IIFE parameters not being contextually typed from the call arguments (missing
TS18048/TS7006, over-emitted TS7019). Their JS-emit subtests pass in both cases,
which localises both to the checker rather than the emitter.

**Why the two are DEFERRED rather than left red.** The item's rule is "never
leave a category half-red without notes", which permits red-with-notes — but the
corpus is a hard zero-failure gate that every round's verification leans on, and
two permanently-red tests would degrade that gate for every future round. So
`conformanceDeferredErrorBaselines` skips just their `.errors.txt` subtests (the
JS ones still run), and the mechanism's KDoc requires a queue item per entry:
triage first, queue it, then defer. It is not a parking space for fresh failures.

**A trap that cost a run.** `rm -rf build/test-results/jvmTest/binary` — the
incantation in CLAUDE.md — clears the BINARY results but NOT the XMLs, so a
tally script globbing `*.xml` happily sums the previous run's files. After
removing two tests I read 12,665/2 twice from stale XMLs before noticing the
generated sources no longer contained the failing tests at all. When the test
COUNT should have changed and did not, suspect the results directory, not the
change; `rm -rf build/test-results/jvmTest` (no `/binary`) is the honest reset.


**Round 689 (2026-07-26) — M3.0's preconditions settled: the conformance corpus
IS reachable offline, and what remains is exactly three edits.** No production
code changed; the item now carries the findings so the next attempt starts at
implementation.

**The one that could have blocked the item outright.** `typescript-repo` is a
**blobless partial clone** (`partialclonefilter = blob:none`, `promisor = true`)
whose sparse checkout lists only `tests/cases/compiler` and
`tests/baselines/reference` — the shape that normally means "the other paths need
the network". It does not here: a `git cat-file -p HEAD:tests/cases/conformance/…`
probe returns content, so the blobs are already local. Worth stating because the
config alone reads as network-gated, and this box is offline.

**Three more preconditions, all favourable.** Baselines need no work (the sparse
checkout already takes the whole flat `tests/baselines/reference`). The
conformance **variant-baseline convention is already implemented** — conformance
writes `name(target=es5).errors.txt` and the generator's `computeVariations` /
`paramBaselineName` produce exactly `name(key=value).ext`. And there are **ZERO
basename collisions** between all of conformance and the 6,537 compiler cases, so
the generated flat backtick function names need no disambiguation — I checked the
whole set, not just the first category, because a collision anywhere would have
forced a naming scheme change rather than a local fix.

**Sizing, which picks the first category for us:** `expressions/functions` is
**7 files**; `types/typeParameters` 46; `types/typeRelationships` 263. Seven is
the right size to validate the generator change end-to-end before any category
large enough to produce a triage wave.

**The remaining work is three edits**, recorded on the item: `sparsePaths` gains
the allowlisted dirs; the `testFiles` collection must walk them RECURSIVELY
(categories have subdirs, unlike the flat compiler dir); and the generated bodies
hardcode `typeScriptCasesDir` = `tests/cases/compiler`, so a conformance case
needs its own path.

**Why this stopped here.** The remaining edits are in `build.gradle.kts`, whose
generator is a long `doLast` with documented editing hazards (the nested-comment
trap that silently kills a region). That is work for a fresh context, not the
ninth iteration of a long one — and the item is now specified precisely enough
that the next round can go straight to it.


**Round 688 (2026-07-26) — attempted M2.4's "small self-contained" follow-up,
found it would be DEAD CODE, and reverted: `useRealLibs` defaults to false and
nothing in the project path turns it on, so every real build — all eight
dashboard profiles included — runs on the EMBEDDED lib and the whole real-lib
subsystem is test-only.** No production code changed.

**What I set out to do.** Round 687 recorded follow-up (i): surface
`Resolution.unavailable` so `"lib": ["dom"]` stops being a silent no-op. It
looked small — one field, one call site, no build change.

**Two things the attempt established before it died.** First, **`unavailable` is
the wrong key**: a `full` default lib (`lib.d.ts`, `lib.es2020.full.d.ts`)
transitively references the DOM/host files, so an ordinary target-default
resolution ALREADY has a non-empty `unavailable` (RealLibResolverTest pins
exactly that), and keying on it would fire on every default build. Only a name
the USER wrote is reportable, which needs a new `unavailableRequested` field —
implemented, and it works. Second, **the corpus blocks the embedded-path
variant**: 259 corpus cases carry `@lib:`, including **23 requesting `dom`**
plus webworker×7, scripthost, esnext.temporal, esnext.intl — all unshipped, all
currently green, because their baselines came from a real tsc that HAS those
libs.

**Then the diagnostic did not fire, and that was the real finding.** Wired into
`bindRealLibs` and gated on an explicit request, it produced nothing on
`"lib": ["es2020", "dom"]`. The reason is not a bug in the wiring:
`CompilerOptions.useRealLibs` defaults to **false**, and its only writer is the
`usereallibs` test directive — `ProjectCompiler` and `TsConfigLoader` never set
it. So `bindRealLibs` never runs for a real build, and the entire real-lib
machinery (RealLibResolver, RealLibSnapshots, `Resolution.unavailable`) is
reachable only from tests that opt in. The change was reverted rather than
landed: dead code that *looks* like the hole is closed is worse than the
documented hole.

**Which means M2.4's root is a design question, not a patch.** Real project
builds type-check against a curated embedded lib while the shipped real libs sit
unreachable; the DOM silence is one symptom of that mismatch. Recorded on the
item, in order: decide what real builds should use for libs at all; then ship the
DOM/webworker/scripthost sets (owner-gated build change); only then is the
original cost question answerable and an unshipped-lib diagnostic both correct
and reachable.

**Method note.** Three rounds in a row here, the deciding evidence was a
NEGATIVE: round 687's member probe (`e.notAMember` must error), and this round's
"the diagnostic did not fire". Both times the tempting reading was that the
measurement was fine and the subject was cheap/absent; both times the subject was
simply not running. When a change to a subsystem produces no observable effect,
establish that the subsystem EXECUTES before concluding anything about it.


**Round 687 (2026-07-26) — queue hygiene, then M2.4 re-scoped by measurement:
the DOM libs are not shipped, and `"lib": ["dom"]` is a SILENT no-op that turns
every DOM-typed expression into an unchecked `any`.** No production code changed.

**Queue hygiene first (the file's own documented failure mode).** Two parent
items still read `- [ ]` while every live child was done or owner-gated: **EP.2**
(2a/2b/2d-f/2g/2h all landed, 2c skipped-by-owner) and **INV.7** (7a/7c1/7d1/7d2/
7d3 landed, only the parked 7b left). Both reconciled to `[x]`. Also stated the
file's **reading convention** at the head of the QUEUE, because it cost me a scan:
a superseded item is kept as `- [ ] ~~Name (original)~~` directly below the `[x]`
that replaced it, so a top-down search for the next `- [ ]` must skip `~~…~~`
titles AND parents whose live children are all done or parked. With those fixed
the live queue is exactly ten items, M2.4 first.

**M2.4 — the measurement falsified the premise.** The item asked for
dom.generated.d.ts's parse/bind cost. It has none, because **`RealLibFiles` does
not ship the DOM set at all** — its only "dom" occurrences are
`/// <reference lib="dom" />` lines inside other libs' text.
`RealLibResolver.resolve` puts the file in `Resolution.unavailable` and filters
it out of `ordered`; **`unavailable` is never consumed outside RealLibs.kt**, so
nothing is reported. Measured on three lines: `HTMLElement` resolves, `document`
resolves, and `e.definitelyNotAMember` on an `HTMLElement` parameter compiles
**clean**. A browser project therefore gets a green build with its DOM code
entirely unchecked — which is a worse defect than any parse cost would have been.
Follow-ups recorded on the item: (i) surface `unavailable` as a diagnostic
(small, no build change); (ii) shipping the DOM set is an **owner-gated
build-system change** (~1 MB of generated source), and only then is the original
cost question answerable.

**The near-miss, which is the transferable part.** My first control — "does
`HTMLElement` resolve when `dom` is in `lib`?" — PASSED. A clean 5-pair
interleaved A/B then put the cost inside the noise band (+38/−68/+34/−22/−23 ms),
and I was one step from reporting "DOM is free". Both measurements were of
nothing. **When an unknown name degrades to `any`, name resolution proves
nothing; the control that decides is a MEMBER probe** — `e.notAMember` must
error. The tell that sent me back was structural, not empirical: 1 MB of
declarations cannot cost 0 ms, so I went looking for what the run was actually
loading.


**Round 686 (2026-07-26) — M4.9 DONE: one gate on `mergeModuleAugmentations`
took the `"types": ["node"]` compiler profile from 30 diagnostics to 13, and
every survivor is env-legit.** Also the queue-state finding that (CATCH.1) was
the last live PERF/EP/INV item.

**The bug.** `mergeModuleAugmentations` published every export of a FILELESS
`declare module "spec"` into `globals`. For an AUGMENTATION that is right —
globals is its only visibility channel (round 510's rationale, preserved). For
the identical syntax in a SCRIPT `.d.ts` it is wrong: that DECLARES the ambient
module, and its members are reachable only through an import of the specifier.
So `@types/node`'s `declare module "fs" { export interface WatchOptions … }` put
`WatchOptions` in globals, where it **outranked tsc's own import alias of the
same name** — sys.ts's `WatchOptions` resolved to node's `fs.WatchOptions` and
every downstream check disagreed with the source. The gate is the declaring file
being an external module, which is exactly tsc's augmentation-vs-declaration
distinction; `moduleFiles` is already populated before this pass runs.

**One gate, eight codes.** TS2353×7, TS2339×3, TS2322×2, TS2345, TS7006, TS1345,
TS2709, TS2558 — all downstream of the same pollution, all gone. What remains is
13 TS2591 (`require`/`process` in files that never import node types), the same
env class the eight dashboard profiles carry by design.

**Found by discrimination, not search.** A four-file repro reproduced it in
under a minute; then one probe settled the mechanism: an interface declared ONLY
inside the ambient module drew TS2304 (so the name is NOT in the TS2304 walker's
scope) while its MEMBERS resolved through the annotation (so the type IS in the
type-position scope). Two scopes disagreeing is a much narrower target than
"resolution is wrong somewhere", and it named the publishing site in one run.
The earlier globals-leak hypothesis had been *falsified* by a no-import probe
file getting a correct TS2304 — worth noting, because that near-miss would have
sent a search-first approach into the per-file scoping machinery instead.

**Queue state.** Working top-down, the PERF arc (M0.1–M0.4, M1, M2), the EP arc
and INV.0–INV.7 are all closed or owner-parked: EP.2c was skipped by the owner,
INV.7b is parked-by-owner, and (M0.3)'s remaining slices are priced below the
±2% drift band. So the live queue is the **Post-v1 backlog** (unparked round
679), and M4.9 was its first unchecked item. Next: M2.4 / M3.0 / M3.5 / M4.1–M4.7,
plus the still-worthwhile ninth dashboard profile for `"types": ["node"]`.


**Round 685 (2026-07-26) — (CATCH.1) batch 1: the 30 defensive catches around
`getApparentType` / `getPropertyOfType` deleted as dead residue, and the audit's
first real find fixed — an indirect circular type-parameter constraint blew the
stack.** Two commits.

**The batch.** 22 `try { getApparentType(x) } catch (_: Exception) { … }` sites,
6 `getPropertyOfType` ones, 2 compound (`getPropertyOfType(getApparentType(…))`),
plus the three `app != null &&` conjuncts the removals made vacuous. Checker.kt
catch count **198 → 168** (commonMain 218 → 188). Both helpers are thin
dispatchers — a `when` over type kinds; a `members[name]` lookup — so the whole
throwing surface is member resolution, which is the same surface a hundred
unguarded call sites already run on. **Ledger: 30 removed, 0 restored, 1 bug
found.** Gate: corpus 12,617/0/3 and `--listAll` ×8 **byte-identical** against a
pre-change baseline (46 errors on seven profiles, 94 on harness).

**The find, and it came from the PINS, not the removal.** Writing the batch's
corner-case tests surfaced that `<T extends U, U extends T>` — two lines of
perfectly ordinary TypeScript — **overflows the stack**: `getApparentType`
recursed type-param → constraint with no cycle guard. Confirmed by a temporary
trace print at the `init` boundary guard: a stack of nothing but
`getApparentType` frames. The general TS2313 walker only catches the DIRECT
`<T extends T>` form (its own doc comment says the indirect case is "not yet
handled"), so nothing stood between this shape and the overflow. The blast
radius was not the crash — the boundary guard absorbs it — but that it ABORTS
THE WHOLE FILE'S CHECKING and reports TS2589 at 0:0. Fixed by walking the
constraint chain iteratively with a seen-set, allocated only when the chain hops
type-param → type-param (so the common `T extends SomeInterface` allocates
nothing); a cycle yields `anyType`, exactly like a missing constraint. Both
commits' gates were byte-identical on all 8 profiles, i.e. no tsc-source shape
reaches either path.

**Both findings were PRE-EXISTING** — verified by running the new pins against a
stashed HEAD, where they fail identically. That is the distinction the item's
method turns on: the removal was byte-neutral (dead residue), while the pins
written to justify it did the actual bug-finding. Worth carrying into batch 2:
**write the corner-case pins first, run them against HEAD, and the deltas tell
you which defaults were reachable.**

**The other pin that failed, and why it stayed.** `switch (n.kind)` over
`(A & { extra: true }) | (B & { extra: false })` does not narrow — the case
bodies draw TS2339. That is the documented intersection-arm discriminant-fold
residue (CLAUDE.md § "A UNION whose MEMBER is an INTERSECTION"), pre-existing and
out of this item's scope; the pin now asserts only the sharp no-TS2589 signal,
with the gap named in a comment.

**Batch 2, same round: `getTypeOfSymbol` (16) + `resolveStructuredTypeMembers`
(6), 22 sites, also byte-neutral — 0 bugs.** These two ARE deep resolvers, so the
prior was different; what made them safe is that each already carries the guard
its call-site catches stood in for — `getTypeOfSymbol`'s per-symbol in-progress
sentinel (B202.1, degrades a re-entrant resolution to `anyType`) and
`resolveStructuredTypeMembersCore`'s heritage cycle guard. The 8 pins drive
exactly those shapes (mutually recursive interface AND class heritage, the
`var x = cond ? y : 0; var y = x` initializer cycle, directly and generically
recursive members, a circular type alias, a recursive-type relation) and all pass
at unmodified HEAD. **Rule of thumb for batches 3+: grep the guarded helper for
its own cycle guard first — where one exists the catch is redundant by
construction.** Checker.kt **198 → 146** over the two batches (commonMain 218 →
166).

**Batches 3 and 4, same round: the two deep resolvers, 79 more sites, both
byte-neutral — 0 bugs.** `getTypeFromTypeNode` (39) is the one that genuinely
differed: its B202.2 in-progress sentinel covers only the CACHEABLE path — a
resolution under `currentTypeParamScope`, a non-empty `inferenceNamespaceStack`,
or `currentTypeAliasArgs` bypasses the cache and the sentinel with it, leaving
the alias-substitution depth bail as the protection — so its pins drive the
BYPASSING contexts specifically, and one pin is a control in the OTHER direction:
an infinitely expanding alias is *supposed* to bail with TS2589, so "no TS2589"
is not a universal sharp signal. `getTypeOfExpression` (40) holds no sentinel and
needs none — it is a `when` over expression kinds on a finite acyclic tree,
delegating to guarded resolvers and to the deliberately iterative walkers, so the
only route to unbounded recursion is a DECLARATION cycle (initializer cycles,
recursive inferred return types, an object literal spreading itself — all pinned,
all passing at HEAD). Its two try/FINALLY blocks are untouched. Removals also
retired one vestigial `tryGetTypeFromTypeNode` wrapper body and ~20 null checks
the non-null returns made vacuous.

**Batch 5 took the single-line tail (34 sites): the relation engine, the type
printer, alias and heritage resolution, widening, `getTypeOfIdentifier` and the
singletons — every one of which carries its own cycle guard
(`relationComparisonStack` + the `isDeeplyNested` occurrence heuristic;
`typeToStringInProgress`; a visited set; cache-before-recurse), which is the
rule of thumb holding for the fifth time.** Its 7 pins drive those cycles
directly (an infinitely expanding generic pair, mutually recursive interfaces
relating, a recursive type printed into a diagnostic, a circular import-equals
chain, circular class heritage, a recursive interface spread-widened).

**ONE site kept by judgement, with a comment naming why:** the `Parser(...)` in
`resolveRequireModuleShape` parses arbitrary external `.json` file content, not
compiler-internal state. The whole method rests on "byte-identical over the
corpus and eight profiles ⇒ the default was unreachable" — sound for internal
paths, weak for an unbounded external input. Keeping it is the honest reading of
the evidence rather than a completeness score.

**Round total: 165 of Checker.kt's 197 catches deleted (198 → 33; commonMain 218
→ 53), 0 restored, 1 kept by judgement, 1 bug found and fixed.** Every batch
byte-identical on corpus + `--listAll` ×8.

**Batch 6 spliced the last 28 by hand** — one exact whole-construct swap per
site with an asserted occurrence count, because a scripted multi-line rewrite is
the mangle hazard CLAUDE.md documents (an inserted arm-close emptying a gate so
the body runs unconditionally). Ten collapsed to something simpler than the
original: four `try { val t = f(x); if (t !== any && t !== error) t else null }`
arms became `f(x).takeIf { … }`, and a `val resolved = …; when (resolved)` became
`when (val resolved = …)`. Checker.kt 33 → **3**.

**CLOSING VERDICT — 193 of Checker.kt's 197 removed, 0 restored, 3 kept, 1 bug
found and fixed.** The three keeps each have a stated reason: the SOE boundary
guard (load-bearing), the `FriBail` control-flow catch (never defensive), the
`Parser(...)` on external `.json` content.

**And the 20 sites outside Checker.kt are a DIFFERENT population — audited this
round and deliberately left alone.** Vfs's 3 are filesystem I/O (a missing or
unreadable file must yield null, not crash); Parser's 2 guard parsing of
externally-sourced JSDoc type text; TsBuildInfo / TsConfigLoader / ModuleResolver
NAME their exception (`SerializationException`, `IllegalArgumentException`) over
external JSON; Transformer's one names `NumberFormatException`; Emitter's and
Flow's grep hits are comments and emit-helper source strings. Every one either
guards an external input or names what it absorbs — precisely what the Checker.kt
residue did not do. So the item's opening premise ("~200 sites in commonMain, all
the same shape") is true of Checker.kt and false of the rest, and finishing the
count there would have been the wrong move.

**Round 684 (2026-07-26) — the convention branch MERGED with rounds 674–681, and
the ten test files those rounds added brought up to the one dialect. Round
numbers had COLLIDED across the two lines of work and are renumbered here.**
Owner-requested integration of branch `fix/build-problems` with `origin/main`,
which had moved 31 commits (EP.2a–h, M4.8, M4.9) in the meantime.

**Renumbering.** The branch and main both used **672** and **673** — main for the
emit-diff gate going live and for EP.2's re-scoping, the branch for the build
repair and the convention sweep. Main's numbers are the ones already published in
`bench-history` and referenced from other notes, so the BRANCH's rounds moved:
672 → **682**, 673 → **683**. Every self-reference moved with them, including the
four CLAUDE.md entries that cite "round 672" for the assertion-idiom and
Kotlin/Native rules; CLAUDE.md's *other* "round 672" (the emit-diff gate, § Known
gotchas) is main's and stayed.

**Conflicts (4).** `ModuleSpecifierExtractionTest` was the only code conflict and
the only one with real content: M4.8 (round 680) had made reference directives
STOP being module specifiers, so main rewrote the two assertions the branch had
just converted. Resolved in main's favour semantically, in the branch's idiom
syntactically — and the resolution needed one extra step, because
`Parser(src).parse().referencedPaths` puts a **SourceFile in a power-assert
subexpression**, the documented SOE/OOM hazard; both files grew `referencedPathsOf`
/ `referencedTypesOf` helpers that return plain string lists. `STATUS.md`,
`PLAN-PHASE-5.md` and `STATUS-HISTORY.md` were ordering-only.

**The sweep (10 new files + 1 both sides touched).** Rounds 674–681 wrote their
tests to the PRE-682 conventions, which is expected — those rounds predate the
sweep on this branch — so: **144 `kotlin.test` call sites** (`assertEquals`,
`assertTrue`, `assertFalse`, `assertNull`, `assertContains`) became power-assert
`assert(...)`, messages dropped and the informative ones re-stated as comments;
**7 test names** carrying `(`, `)`, `,` or an em-dash renamed (the Kotlin/Native
character rule — the very drift round 682 cleared, re-accumulated in seven
rounds, which is the mechanical-gate argument in miniature); **7 source-taking
helpers** annotated `@Language("typescript")`; `SkipLibCheckTest` moved onto the
shared `diagnose` helper and `should { have(…) }`; `ReferenceDirectiveProgramTest`
rewritten so no SourceFile can reach an assert.

**The trap, and it cost a full suite run.** The converter normalised whitespace on
each argument it rewrote — fine for expressions, WRONG inside string literals: a
needle `"// first comment line\n    // second comment line"` (the four spaces are
the emitted indentation, i.e. the entire point of EP.2h's pin) collapsed to one
space, and the test failed. One failure in 12,611, caught in one run, but the
lesson generalises: **a scripted assertion conversion may re-flow argument
EXPRESSIONS and must never re-flow their string literals** — audit by diffing the
multiset of string literals before/after and accounting for every disappearance
(here: 26 dropped message strings, plus the one genuine casualty).

Gates: suite **12,611 / 0 / 3** — identical to round 681's count, because round
683's TriageTest deletion (−1) and its two-control split (+1) cancel;
`compileTestKotlinJvm` warning-clean. Trims per protocol: STATUS 679–677 and PLAN
674–669 moved to `docs/history/`.

**Also queued, at owner request: (CATCH.1) at the TOP of the queue** — the
defensive-`catch` audit. The owner flagged one `catch (_: Exception) { null }` in
`Checker.kt` and asked what else looks like it: **218 sites in `src/commonMain`,
197 of them in Checker.kt**, all the same swallow-and-default shape. Blame shows
the flagged one was born `catch (_: Throwable)` in the inline-SOE-guard era and
was narrowed to `Exception` MECHANICALLY by the 2026-07-04 sweep, so it no longer
catches what it was written for. The sweep's own CLAUDE.md entry reserved the
per-site removal as separate work; CATCH.1 is that work, batched and gated
(corpus + `--listAll` ×8), classifying each site as dead residue (delete) or real
modelling bug (file it, restore that one catch with the exception named).

**Round 683 (2026-07-25) — the test suite speaks ONE dialect.** Owner-requested
follow-through on round 682's idiom unification, which had converted the
`kotlin.test` calls but left three older conventions standing in files that sweep
never had to touch: ~200 camelCase test names in 32 classes became backticked
sentences; `should { have(…) }` replaced raw asserts on diagnostics and emitted
JS (the local `assertNoNNNN` / `tsNNNN(result)` wrappers are DELETED — the
receiver-plus-block form says what the wrapper name said, in the assertion
itself); 41 classes' local helpers gained `@Language("typescript")`; 72
`have(cond, "message")` calls lost the message, and 163 standalone `have(cond)`
calls outside a `should` block became `assert(cond)`. `TriageTest.kt` was DELETED
— not a test but a scratch harness that read `typescript-repo` fixtures, wrote
`/tmp/triage_out.txt`, caught `Throwable` and asserted nothing. The six
AST-centric files stay exempt (power-assert would toString a subtree). Finding
the standalone `have` set needed a real scanner rather than a grep, and caught
three Kotlin lexical traps in a row: a backtick test name pairs only WITHIN a
line, block comments NEST, and `have(` inside a comment must not be rewritten.
Suite 12,520 / 0 / 3. (Full narrative in STATUS.md.)

**Round 682 (2026-07-25) — `./gradlew build` REPAIRED: `commonTest` had drifted
native-incompatible since (INV.7a), and `jvmTest` could never have caught it.**
Owner-requested build fix, branch `fix/build-problems`. (INV.7a) re-enabled
linuxX64 at round 610 and the MAIN compile + link have stayed green since — but
`compileTestKotlinLinuxX64` is also part of `build`, and ~60 rounds of new
`commonTest` files broke it three separate ways. The tell is the asymmetry: the
loop's gate is `./gradlew jvmTest`, which stays 100% green while `build` fails.

**The three drifts** (all in `src/commonTest`, none reachable from the JVM gate):
  - **102 backtick test names** carrying `(`, `)`, `,`, `&`, `@` across 68 files.
    Kotlin/Native rejects these ("Name contains illegal characters"); the JVM
    only rejects `.;[]/<>:\`, so every one of them compiles for `jvmTest`.
    Renamed mechanically — parens dropped inline, a TRAILING parenthetical
    rendered as ` - aside` (the readable form), `,` → ` -`, `&&` → `AND`.
    Verified zero collisions and zero residual illegal chars.
  - **65 `kotlin.assert` calls** (CcetAnchorTest / CpaAnchorTest /
    CtaFnBodyAnchorTest — the three files that imported no `assert`, so they
    bound the stdlib one). It is `@ExperimentalNativeApi`, and `@OptIn` for a
    native-only annotation cannot be written in common code. Switched to the
    project's own `com.xemantic.kotlin.test.assert` — multiplatform AND
    power-assert-enabled (build.gradle.kts already lists it in
    `powerAssert.functions`), so the calls take NO message argument: the failure
    diagram renders every subexpression value, which is why the library's
    `message` parameter is not meant to be used. All 65 message lambdas were
    therefore DROPPED, not converted. This also closes a real hole —
    `kotlin.assert` is a NO-OP when JVM assertions are off, so those 65 pins
    were only ever load-bearing by Gradle's `enableAssertions` default.
  - **`String.format`** (LineAndCharacterMemoTest) — JVM-only; replaced by a
    local interpolating function.

**Then, by owner decision, ALL NATIVE TARGETS WERE SWITCHED OFF** (linuxX64
commented out alongside the Apple ones) to keep the Claude Code loop fast — the
native test compile plus the optimizing link add ~7 min to `build`. The repair
was still worth doing rather than reverting: it leaves the tree in a state where
re-enabling a native target is a one-line uncomment instead of another 169-error
cleanup.

**Process point, now sharper.** The corpus suite is the loop's zero-regression
gate, but it is a JVM-only gate, so it never saw this drift accumulating
underneath it — and with native off, `build` will not see it either. Nothing
mechanically enforces the three rules any more, which makes the documentation
the only defense: the constraints and the way to check them are recorded BOTH in
CLAUDE.md § "Known gotchas" and in a comment at the commented-out targets in
build.gradle.kts (the place someone re-enabling them will actually read). To
check, uncomment `linuxX64` and run `./gradlew compileTestKotlinLinuxX64`.

**Also this round (owner-requested): the test suite moved to ONE assertion
idiom — 1,009 `kotlin.test` call sites converted to power-assert `assert(...)`**
(792 `assertEquals`, 115 `assertTrue`, 102 across
`assertFalse`/`assertNull`/`assertNotNull`/`assertSame`/`assertContains`),
matching the project's existing idiom (BaselineFormatterTest's
`assert(result == expected)`).
Messages dropped throughout — `assert`/`have` are the only power-assert-
transformed functions (build.gradle.kts), and their diagram beats any
hand-written message: a deliberately broken pin renders as
`assert(ds.count { it.code == 1102 } == 99)` with the operand value `1`, the
`false` verdict, AND the full diagnostics list underneath. That injected-failure
check was the point — a green suite alone cannot distinguish a correct
conversion from one that made assertions trivially true.

**65 sites deliberately KEPT on `kotlin.test`** — every one in the six
AST-centric files (Inv2LexicalScopeTest, Inv2NodeIndexTest, Inv3NodeKeyedLookupTest,
Inv3PerFileLookupTest, RealLibSnapshotTest, ForEachChildOracleTest) whose compared
value is an AST node, a `.copy()`, a `preorder(...)` element, a Symbol or a lexical
scope (plus 3 there that need `assertNotNull`'s RETURN value mid-expression).
Power-assert toStrings every subexpression, so those would trade a readable
failure for a subtree dump (the documented SOE/OOM hazard). The empirical line:
`List<Diagnostic>` and `CompilationResult` render usefully (strings +
diagnostics, no AST); `SourceFile`/`NodeBase` do not.

**Four mechanical-pass defects caught before shipping**, each of which a
green-suite check would have missed or mis-attributed: Kotlin TRAILING COMMAS
produced a phantom 4th argument (17 valid 3-arg calls silently skipped); the
AST-hazard test was matching MESSAGE text rather than the compared values (false
skip); collapsing a multi-line `listOf(...)` argument that carried `//` comments
let the comment swallow the closing paren (1 site, hand-repaired with comments
intact); and `assert(x == emptyList())` does not compile at all — `==` gives the
compiler no inference source for the element type, so 28 sites became
`assert(x.isEmpty())`. A fifth defect appeared in the `assertNotNull` pass: the
value-position rewrite used the whole text before the call as the second line's
indent, emitting `val js = assert(js != null)` — a duplicate declaration (13 sites,
caught by the compiler, repaired). A sixth was mine to own too: the AST-hazard test
matched hazard words INSIDE STRING LITERALS (`assertTrue("== node kinds" in text)`),
so string masking was added and the completed `assertEquals` pass re-audited under it
(no site had been wrongly skipped). Plus ~60 now-unused `kotlin.test` imports removed.
Suite 12,520/0/3, unchanged at every step.

**A counting correction worth keeping:** the first survey numbers came from `grep`,
and BSD grep UNDERCOUNTED (`assertTrue` reported 102, actually 115). Every figure
above is from the converter's own Kotlin-aware scanner; re-derive with a Python
lookbehind regex, not grep, if these are ever re-checked.

**Worth deciding later:** documentation alone did not hold last time — a
`jvmTest`-side source scan over `src/commonTest` (assert the backtick names carry
no native-illegal characters) would restore a mechanical gate for a few seconds
of suite time, without any native toolchain. Not built; offered.
**Round 681 (2026-07-25) — M4.9: two gaps that only existed because M4.8 let
`@types` in. Compiler profile with `"types": ["node"]`: 60 → 30 errors. Also the
round where I walked into a trap CLAUDE.md documents by name.**

**`skipLibCheck` was parsed and never consulted.** Nothing noticed for as long as
`.d.ts` files came only from the corpus and the bundled libs — but `@types/node`
is ~70 declaration files, and checking them reported 15 TS7008 + 2 TS7010
against DefinitelyTyped's own code in a project that had explicitly asked not
to. Applied to the CHECKER's output only: tsc's `skipLibCheck` skips type
checking of declaration files, it does not suppress their SYNTAX errors.

**A PARAMETER did not shadow a same-named namespace reaching globals from an
ambient module body.** `checkMemberAccessMissing` bails to the locally-known type
only for names in `currentShadowedNames`, which `applyBodyLocalShadowing` fills
for body-local VAR declarations and deliberately EXCLUDES parameter names (a var
redeclaring a same-function param is a redeclaration, not a shadow). So a
parameter fell through to the symbol-based branches, which resolve through
globals. tsc's own `function formatJSDocLink(link: JSDocLink | …)` hit it
because `fs.d.ts` declares `export namespace link` — 18 diagnostics reading
*"Property 'kind' does not exist on type 'typeof link'"*.

**What made this cheap to find was a discriminator, not a search.** I was
grepping resolution paths and getting nowhere. Four one-line variants settled it
in one run: a global `declare namespace link` is shadowed correctly, an
ambient-module one is not; a LOCAL named `link` works, a PARAMETER does not; and
it is not union-specific. That located the fault in the parameter path exactly,
after which the fix was one gate.

**The trap, worth stating plainly.** My first cut bailed whenever a concrete
local type resolved the member. That is precisely the *"raw declared type
exposes the property → suppress"* shape CLAUDE.md warns against, and it
over-suppressed `instanceofWithStructurallyIdenticalTypes` — where `C1|C2|C3`
narrows to `never` via `!isC1 && !isC2` on structurally-identical types and tsc
DOES report TS2339-on-`never`. The corpus caught it in one run. Keying the bail
on the name ALSO denoting a NAMESPACE in globals repairs only the
mis-resolution and leaves that test untouched. The documented warning was
specific enough to have saved the round had I re-read it before writing the
gate rather than after the failure.

**Gates.** Corpus **12,611 / 0 / 3** (+13 pins, including the sharp case: a
member absent from BOTH the parameter type and the namespace must still
report). `--listAll` ×8 unchanged (46 on seven, 94 on harness) — the dashboard
profiles set `"types": []` and declare no colliding namespaces.

**NEXT (M4.9 continues):** the remaining 30 on that profile are 13 TS2591
(`require`/`process` in files that reference node types without importing
them), 7 TS2353 (`fs.WatchOptions` vs the compiler's own `WatchOptions` in an
object literal), 3 TS2339, and singletons. Worth a ninth dashboard profile so
the number is tracked — but do NOT alter the existing eight.

---

**Round 680 (2026-07-25) — M4.8 DONE: `/// <reference path|types>` now pulls
files into the program. `@types/node` goes from contributing 1 file to 67.**

**The bug was a resolution-KIND confusion, not a missing feature.** The parser
already recorded reference directives, and the crawl already tried to resolve
them — into `SourceFile.moduleSpecifiers`, which the crawl feeds to
`ModuleResolver`. But the three things are different: an import specifier
resolves as a module, a `path=` target is a FILE PATH relative to the
referencing file (tsc `resolveTripleslashReference`), and a `types=` target is a
package name resolved through the type roots. So `path="globals.d.ts"` — no
`./` — was treated as a BARE package, failed, and the file never entered the
program. The `Ast.kt` doc comment asserting that reference directives live in
`moduleSpecifiers` was accurate about the code and wrong about what that
achieved.

**Shape of the fix.** `SourceFile` gains `referencedPaths` / `referencedTypes`;
the parser records the two kinds separately and stops merging them into
`moduleSpecifiers` (whose only consumer was the crawl — checked before
changing). The crawl resolves paths relative to the referencing file and types
through the tsconfig's type roots, adding both to the frontier, so following is
TRANSITIVE — which is the whole point for a package whose entry file is nothing
but reference lines.

**TS6053 needed no work, and that is worth noticing.** It is emitted by the
CHECKER, which asks whether the referenced target is in the program — so it goes
silent exactly when resolution starts succeeding and still fires for a genuinely
missing file. Both directions are pinned. A design that had duplicated the
resolution in the diagnostic would have needed a second fix here.

**Measured.** With `@types/node` and `"types": ["node"]` on the compiler
profile: program **79 → 146** files, TS2591 **43 → 13**. The rest of that
profile's errors are now newly VISIBLE real gaps the unresolved names had been
masking — TS2339×18, TS7008×15, TS2353×7 on `fs.WatchOptions`/`typeof link`
shapes. Queued as M4.9; they do not touch the dashboard, whose tsconfig sets
`"types": []` deliberately and correctly.

**Gates.** Corpus **12,598 / 0 / 3** (+19 pins across two new classes).
`--listAll` ×8 unchanged (46 on seven, 94 on harness) **and program sizes
identical** (81/312/84/78/274/252/80/88) — a pure capability addition, no
dashboard movement. `ModuleSpecifierExtractionTest` pinned the old contract and
was updated: its intent (directives honoured after a leading block comment,
ignored after the first code token) is preserved against the new fields, and
that block-comment case is exactly `@types/node`'s own layout.

**NEXT:** M4.9 — the gaps `@types/node` exposes once it actually loads.

---

**Round 679 (2026-07-25) — v1 RE-VERIFIED at HEAD and the post-v1 backlog
UNPARKED; the verification itself turned up the highest-impact remaining gap.**

**v1, checked rather than assumed.** It was declared at round 481; this round
re-ran the three legs at HEAD, 200 rounds later. All 8 profiles: exit 0, **every
input file emitted** (81/81, 312/312, 84/84, 78/78, 274/274, 252/252, 80/80,
88/88), zero crash frames, `--listAll` steady at 46 on seven and 94 on harness.
And the diagnostics are not merely "offline artifacts" in a vague sense — every
single one is a missing Node ambient (`process`, `Buffer`, `require`, `NodeJS`,
`console`, `BufferEncoding`) under a tsconfig that sets `"types": []`, which
DISABLES type acquisition by design. Our handling of that is correct tsc
semantics. So the FP leg is genuinely clean.

**The backlog was parked on a condition that came true 200 rounds ago.** The
Post-v1 section says the loop skips it "until v1 lands". v1 landed at round 481.
Nothing ever re-read the condition, so ~200 rounds of work went to M5/INV while
the section that now holds the live work sat marked parked. It is unparked. A
queue-hygiene failure mode worth naming: **a parked item's unpark CONDITION
needs an owner, or it never fires.**

**M4.8 — found while proving the FP leg, and it is the big one.** Trying to make
the FP claim decisive rather than caveated, I installed `@types/node` and turned
on `"types": ["node"]`. The diagnostics did not move — 46 before, 46 after. The
program went from 78 files to **79**. That one file is `@types/node/index.d.ts`,
which is 64 `/// <reference path="…" />` lines and little else; `globals.d.ts`,
one of those 64, is what declares `var process` and `namespace NodeJS`. Our
reference-directive handling (`TypeScriptCompiler.kt` ~2168) only ORDERS files
already in the program, and only under `outFile`; tsc's `processReferencedFiles`
ADDS them. So we cannot consume `@types/node` — or any `@types` package built
the same way, which is most of them. Queued as M4.8 at the head of the backlog.

**Worth noting about the method:** the check that produced this was an attempt
to strengthen a claim I already believed. "46 env-legit artifacts" was true, and
re-verifying it was still the highest-value thing available, because the *way*
it failed to reach zero is what exposed the gap.

**Gates.** No production code changed this round. Emit verification, `--listAll`
×8, and the `@types/node` probe are all measurements; the probe's temporary
tsconfig is deleted and the fixture install is gitignored under `build/`.

**NEXT:** M4.8 — make `/// <reference path|types>` pull files into the program.

---

**Round 678 (2026-07-25) — two fixes: the const-enum family is ACTUALLY closed
(true gap 34 → 0) and a printer blank-line defect cleared 66 hunks. Byte-identical
files 31 → 33/78. Round 677's "closed at parity" claim was WRONG — the gate's own
summary metric lied, and I believed it.**

**The correction first, because it is the point.** Round 677 declared the family
closed on the strength of the emit-diff script's family-1 line reading
`18,118 vs 18,118`. That counter is `grep -E '[0-9]+ /\* Name.Member \*/'` — it
requires a **numeric** value before the comment. Every STRING-valued const enum
is therefore invisible to it *on both sides*, so it reads perfect parity while
string-valued reads are missing. Counting **all** `/* X.Y */` comments per file
showed the truth: 34 still missing, every one of them `tracing.Phase.*`. The
honest measure is the per-file comment count, and it is now 15,218 = 15,218.

**What the residual actually was — and it was my ORIGINAL round-676 prediction,
which round 677 then talked itself out of.** tsc's tracing.ts:

    export namespace tracingEnabled { export const enum Phase { Bind = "bind" } }
    export let tracing: typeof tracingEnabled | undefined;

with every call site writing `tracing?.push(tracing.Phase.Bind, …)`. The
receiver is a runtime VARIABLE whose declared TYPE is the namespace. Name
resolution stops at the variable; tsc goes through the type.
`namespaceBehindTypeofVariable` follows a `typeof <identifier>` annotation
(through unions/parens, i.e. the `| undefined` form), resolving in the
DECLARING file and falling back to the star-follower because the namespace name
may itself come through a barrel. Wired into both the import path and the direct
path, so `declare const t: typeof NS` behaves like an imported one. The variable
keeps its runtime identity — only the MEMBER is substituted, the receiver's
access and its import survive; both are negative controls.

**Why round 677 got it wrong is worth naming precisely.** Round 676 predicted
`tracing.Phase` (34 occurrences). Round 677 built a repro for a *namespace
nested behind a barrel*, saw it fail, fixed it (EP.2e — a real bug), and when
the count did not move went looking again and found EP.2f (also real). Two
genuine fixes plus a metric that read parity produced a confident "closed". The
missing step was never re-checking the ORIGINAL prediction against the ORIGINAL
measurement: `tracing.Phase` was in the residual the whole time, and one
per-file count would have shown it. **A metric agreeing with you is not
verification when you have not checked what the metric can see.**

**Gates.** Corpus 12,573 / 0 / 3 (+7 pins). `--listAll` ×8 unchanged (46 on
seven, 94 on harness). True const-enum comment gap 0. Zero live `tracing.Phase`
reads. byte-identical 31 → **32**/78 — the first file-level movement from this
family, since the other 46 differ for family-2 reasons too.

**Then EP.2h — and it was NOT part of EP.2c's subsystem at all.** Classifying
the 368 residual hunks put 32 in a "we emit a line tsc lacks" bucket, which I
took as the smallest separable slice of the formatting family. It turned out to
be an ordinary printer defect, fixed in four lines: `emitInnerComments` writes a
newline after a `//` comment (it terminates its own line) and then the NEXT
comment wrote a second one for its `hasPrecedingNewLine`, so every pair of
consecutive line comments gained a blank between them. tsc keeps a multi-line
comment block before an `else if` adjacent. An `atLineStart` flag suppresses
only the redundant newline; the indent is still written. Verified BYTE-IDENTICAL
against tsc on an 11-line repro — including when the SOURCE has a blank line
between the two comments, which tsc collapses too, so the behaviour is faithful
and not merely convenient (that case is pinned separately, being the boundary
the fix defines). Measured: hunks **368 → 302**, the add-a-line family **32 →
0** (it also cleared 34 entangled CONTENT hunks), byte-identical **32 → 33**/78.
Suite 12,579/0/3 with every JS baseline byte-exact despite touching the printer.

**The reusable point:** a bucket in a classification is a hypothesis about
cause, not a finding. Two of the three "formatting" shapes I sized as a
subsystem in round 676 have now turned out to be plain defects (EP.2a's double
comment, EP.2h's blank line). Before committing to the expensive reading of a
classification, check whether the cheap one explains it.

**EP.2c: asked, and the owner said SKIP.** With the cheap parts gone the fork
was clean enough to put to the owner rather than guess: 302 residual hunks, 266
of them the wrapped-expression structure, against the fact that byte-parity is
not a v1 exit criterion. The answer was to move on. **The emit arc closes here
at 33/78 byte-identical**, families 1 and 3 at full parity.

**NEXT:** with EP closed, every remaining live queue item is either parked
(INV.7b: needs a ≥16 GB builder) or explicitly zero-value on this box ((6e)
parallel emit — the benches are `--noEmit`, and round 666 measured parallel
scaling flat at w4 on 4 cores). That makes the next round's job an assessment,
not a fix: **v1's three exit legs (zero FPs / all files emitted / zero crashes)
all appear to be MET** — the FP leg since round 481, and the current TSVs show
0 crashes with every profile emitting its full file set (78/80/81/84/88/252/
274/312). If that holds under a deliberate check, v1 should be declared and the
post-v1 backlog (M2.4/M3.0/M3.5/M4.x) unparked, which is where the real
remaining work lives.

---

**Round 677 (2026-07-25) — three const-enum gaps fixed (18,048 → 18,118 on the
script's counter). NOTE: the "family CLOSED" claim made here was WRONG — see
round 678; 34 string-valued reads remained and the metric could not see them.**

**EP.2d — parameter DEFAULT VALUES were never transformed at ES2018+.** The
residual classifier pointed at two shapes; the larger was
`function isNonLocalAlias(symbol, excludes = ts_js_1.SymbolFlags.Value | ...)`
where tsc inlines. Root cause is not const-enum-specific at all:
`flattenRestParameters` opened with `if (effectiveTarget >= ES2018) return
Pair(params, body)` — returning the parameters **raw**. That helper owns the
parameters of the plain (non-async) FunctionDeclaration branch, function and
arrow expressions, and constructors, so at any modern target every default value
in those positions skipped `transformExpression` entirely. The sub-ES2018 path
had always applied the per-parameter treatment, which is exactly why a
downlevel-heavy corpus of 12.5k tests never noticed: the bug is invisible below
the very threshold most emit tests sit under. The fix restores the *whole*
transform, so the pins cover an optional chain and a `this`-capturing arrow in
default position, not just enums.

**EP.2e — a const enum nested in a NAMESPACE did not inline through a barrel.**
The barrel's star closure yields the NAMESPACE for the first path segment, and
the binder sets `SymbolFlags.ConstEnum` on a namespace holding only const enums
— so the flag test *passed* on the namespace while the `enumValues` lookup,
keyed by the ENUM's symbol id, silently missed. `descendToConstEnum` walks the
remaining segments through namespace exports, stopping at the first genuine
`EnumDeclaration`.

**EP.2f — computed initializers did not fold in the same-file collector.** This
one only surfaced because EP.2e did *not* move the number. I had predicted the
residual was tsc's `tracing.Phase`; the count stayed at 18,085 after the fix, so
instead of theorising I re-ran the gate with `--keep` and counted per file:
`debug.js` was short 25, and every missing member was a `Connection.*`. The
source is `const enum Connection { Up = 1 << 0, ..., UpDown = Up | Down }` —
computed. The Transformer's collector accepted only a literal (or a negated
numeric) and returned null, "non-constant, don't inline", for everything else,
while the Checker's cross-module evaluator had folded shifts and bitwise
operators all along. Two evaluators, silently divergent, and the drift ends
inlining for every member *after* the first computed one. The operator table now
lives once in `tsFoldNumericBinary` and both call it.

**The lesson repeats, and it is the same one as rounds 669 and 672.** My
namespace-barrel prediction was well-reasoned, reproduced cleanly in a scratch
project, and was *not what tsc's source does*. What corrected it was the gate
plus a per-file count — 20 seconds of measurement against a confident wrong
model. The standing rule holds: when a fix does not move the metric, do not
explain why it should have; go read the residual.

**Gates.** Corpus 12,566 / 0 / 3 (+32 hand-written tests across three new
classes). `--listAll` ×8 unchanged: 46 on seven profiles, 94 on harness, zero
crashes. byte-identical files stay 31/78.

**SUPERSEDED:** this round claimed the const-enum family was closed because the
script's family-1 counter read 18,118 = 18,118. That counter only sees
NUMERIC-valued inlines; 34 string-valued reads (`tracing.Phase.*`) were still
missing. Closed for real in round 678 — see above.

---

**Round 676 (2026-07-25) — EP.2c SIZED: it is a subsystem project, not a
placement rule. 132 hunks in three shapes, and the recommendation is an explicit
go/no-go rather than drifting into it.**

**What the residual formatting actually is.** Classifying the 132
formatting-only hunks: **78** have the SAME line count but a different
continuation INDENT DEPTH — and it differs in BOTH directions (checker.js has us
indenting 4 spaces too many in one wrapped `&&` chain and 4 too few in another),
so no single constant fixes it; **47** are places where tsc has MORE lines
because we COLLAPSE a wrap it keeps — binder.ts's
`const name = isComputedName ? A` / `    : B ? C` / `    : D` is the archetype,
and tsc reproduces the SOURCE's own line structure with `:` at line start;
**7** are the reverse, a wrap we add and tsc does not.

**Why that makes it a project.** All three need the emitter to model tsc's
line-breaking AND indentation decisions for wrapped binary and ternary
expressions — source-structure preservation for expressions, analogous to the
existing `multiLine` flags on object and array literals but considerably
broader. It is not a token-placement tweak one commit lands.

**Honest sizing.** 132 of the 1,307 remaining hunks (~10%); few files would flip
to byte-identical on its own because they carry other differences too; and it is
the highest corpus-regression risk anywhere here, since the printer it touches
is pinned by all 12,534 tests. Moderate payoff, high risk, subsystem scope is
exactly the profile this arc has learned to price before committing, so the
queue item asks for a decision rather than assuming one.

**Where EP stands after five rounds with the gate.** EP.0 live (672), EP.1
completed via the `.js`-aware barrel fix (672), EP.1a the TS2694 false positive
(668), EP.2a the double-comment defect 128 → 1 (674), EP.2b hex const enums with
the gap 675 → 70 (675). Cumulatively: **byte-identical files 9 → 31 of 78** and
**const-enum inlined reads 1,618 → 18,048** against tsc's 18,118, with
logical-assign already at parity. What remains is EP.2c plus a small tail: 70
const-enum reads, `tracing.Phase.Bind` (a const enum behind a namespace), one
import-elision difference, and a single double-comment of a different shape.

No code changed this round (classification only); tree clean; suite untouched at
12,534/0/3.

**Round 675 (2026-07-25) — EP.2b: the `CharacterCodes` mystery
was HEX LITERALS. Const-enum residual 675 → 70 reads.** Round 673 asked why one
enum accounted for 638 of the un-inlined reads while its own file-mates inlined
fine; the answer is that `SymbolFlags` and `Extension` are decimal- and
string-valued and `CharacterCodes` is almost entirely hex.

**The bug is small and completely silent.** All three const-enum evaluators
parsed the member's literal with `text.toDoubleOrNull()`, which is decimal-only:
Kotlin accepts a hex FLOAT (`0x1.8p3`) but not a hex INTEGER (`0x7F`). So the
parse returned null, the member was recorded as non-constant, and the emit kept
a qualified access — with no error, no diagnostic and nothing in any log. Only a
byte-diff against real tsc could surface it, which is exactly what the round-672
gate bought.

**Three evaluators, found one at a time.** A shared
`tsNumericLiteralToDouble` (Types.kt — hex/binary/octal, `_` separators, rejects
BigInt) now serves the Transformer's same-file collector, the Checker's
`literalConstantValue`, and the Checker's `evaluateEnumInitializer` (which
builds the cross-module `enumValues` table). I wired the first two, and the
same-file repro went green while the direct-import repro did not — that split is
what revealed the third site. Worth noting as a pattern: the same value question
is answered independently in three places, so a "fix" verified on one shape can
easily be half-done.

**Measured (the gate):** const-enum inlined reads **17,443 → 18,048** against
tsc's 18,118 — the gap falls from 675 to **70**. Byte-identical files stay
31/78 for the same reason as round 674: these hunks live in files that still
differ for other reasons, and hunk-level progress is not file-level progress.

**Gates:** 8 pins (`HexConstEnumInliningTest`) covering all three evaluator
paths (same-file, cross-module, through a star barrel), binary and octal, a
negative/zero regression guard, the shared parser across bases and separators,
and two negative controls — a BigInt literal and garbage text must yield null
rather than a wrong value, because a wrong value here would silently corrupt
emitted constants rather than fail loudly. Suite 12,526 → **12,534/0** (3
skipped) with every JS baseline byte-exact; `--listAll` ×8 byte-identical on all
eight profiles, which carries more weight this round than usual since the change
alters enum VALUES and a bad one would move diagnostics; warning-clean.

**NEXT:** EP.2c (the original formatting family, ~131 whitespace/wrap hunks —
tsc puts a wrapped ternary's `:` at line start), and the small tail: 70
const-enum reads, `tracing.Phase.Bind` (a const enum behind a namespace), one
import-elision difference, and the single remaining double-comment of a
different shape.

### QUEUE — work top-to-bottom; promote unblockers per protocol

**Reading convention (stated round 687, after it cost a scan):** a superseded
item is kept for its history as `- [ ] ~~Name (original)~~ — …` directly BELOW
the `- [x]` entry that replaced it. Those struck-through lines are INERT — a
top-down scan for the next `- [ ]` must skip anything whose title is `~~…~~`,
and must also skip a parent whose every live child is `[x]` or owner-parked.

(Restored 2026-07-12, round 481 — the queue/backlog/inventory sections had been
swept into PLAN-PHASE-5-HISTORY.md by an over-eager session-note trim; they are
LIVE structure, not history. v1's offline-verifiable legs LANDED at round 481, so
M5 is now the active arc per the owner directive; the Post-v1 backlog below is the
"any TypeScript project" horizon and stays parked until the owner re-scopes. The
M1–M3 campaign items still unchecked in the history file (M2.2/M2.3/M3.1–M3.4/M1.12)
hit their re-scoped v1 acceptance bar — "the shapes tsc's source uses" — when the
burn-down reached zero real FPs; reviving their full-completeness form is a
backlog-horizon decision, not queue debt.)

**TOP OF QUEUE (owner-requested 2026-07-26, round 684) — work this before PERF.**

- [x] **(CATCH.1) Defensive-`catch` audit — DONE round 685, six batches: 193 of
  Checker.kt's 197 removed as dead residue, 3 kept with stated reasons, 1 real
  bug found and fixed, and the 20 sites OUTSIDE Checker.kt audited and found to
  be a different population that should NOT be removed.** Owner flagged
  `Checker.kt`'s `val app = try { getApparentType(localType) } catch (_: Exception)
  { null }` as a code smell and asked what else looks like it. **The census:** 218
  `catch` sites in `src/commonMain`, **197 in Checker.kt**, every one the same
  shape — swallow and return a default: 84 `null`/`return null`, 57
  `return`/`continue`/`return@…`, 26 `false`/`true`, 9 empty or fall-through, ~14
  type-valued (`anyType`, `errorType`, `"any"`, `Ts2403Cmp.UNKNOWN`). **Why they
  are residue rather than design:** git blame on the flagged site shows it was
  born `catch (_: Throwable)` (round 351) in the era of inline
  `StackOverflowError` guards, and the 2026-07-04 sweep (3b950156) narrowed all
  135 such sites to `Exception` **mechanically** — so this guard no longer catches
  the thing it was written for (SOE is an `Error`), and no named exception is
  documented for what it wraps. That sweep's own CLAUDE.md entry says removing the
  catches ENTIRELY is "a separate, per-site root-cause effort — do not do it
  blind"; this item IS that effort, done in gated batches rather than blind.
  **Method** (repeat per batch, one commit each): (a) pick a batch whose guarded
  expression is a small, near-total helper — start with the `getApparentType` /
  `getPropertyOfType` cluster the owner pointed at; (b) DELETE the try/catch,
  keeping the expression; (c) gate with the full corpus suite **plus `--listAll`
  ×8** (a swallowed exception's default can be corpus-invisible but profile-live);
  (d) classify each site by the result — **byte-identical ⇒ dead residue, delete
  it; now crashes ⇒ a real modelling bug**, so file it as its own queue item with
  the stack trace and RESTORE the catch for that site only, with a comment naming
  the exception it actually absorbs. **Record the ledger** (sites removed / bugs
  found per batch) in the session note; a batch that finds a bug has paid for
  itself even if the catch goes back. **Do NOT** blanket-remove, and do NOT
  re-widen any of these to `Throwable` — the `Exception` narrowing is what lets an
  `Error` reach the init boundary guard (→ TS2589) instead of becoming wrong
  output. Expect this to run over several rounds; ~200 sites is the population,
  not the target for one session.
  **Batch ledger.** *(1) round 685 — `getApparentType`/`getPropertyOfType`, 30
  sites removed, 0 restored, byte-identical on corpus + `--listAll` ×8 ⇒ all dead
  residue; 1 bug found and fixed (unguarded type-param constraint recursion →
  stack overflow on `<T extends U, U extends T>`). Checker.kt 198 → 168.
  (2) round 685 — `getTypeOfSymbol` (16) / `resolveStructuredTypeMembers` (6), 22
  removed, 0 restored, 0 bugs; byte-identical the same way. Checker.kt 168 → 146.
  These two are deep resolvers, but each already carries the guard the catches
  stood in for — a per-symbol in-progress sentinel (B202.1) and the heritage cycle
  guard — so the catches were a redundant outer layer.
  (3) round 685 — `getTypeFromTypeNode` (39), 0 restored, 0 bugs. Checker.kt
  146 → 107. Its B202.2 sentinel covers only the CACHEABLE path, so the pins drive
  the cache-BYPASSING contexts (type-param scope / inference namespace / alias
  args), where the alias depth bail is the protection instead.
  (4) round 685 — `getTypeOfExpression` (40), 0 restored, 0 bugs. Checker.kt
  107 → 67. No sentinel and none needed: it is a kind dispatcher over a finite
  acyclic tree, delegating to guarded resolvers and iterative walkers, so only a
  DECLARATION cycle can recurse — which the pins drive.
  (5) round 685 — the SINGLE-LINE tail (34: relation engine, type printer, alias
  and heritage resolution, widening, `getTypeOfIdentifier`, singletons), 0
  restored, 0 bugs. Checker.kt 67 → **33** (commonMain 218 → 53 over five
  batches). **One site KEPT by judgement, with a comment**: the `Parser(...)` in
  `resolveRequireModuleShape`, whose input is arbitrary external `.json` file
  content — "the corpus did not crash" is weaker evidence for an unbounded
  external input than for a compiler-internal path.*
  (6) round 685 — the 28 MULTI-LINE blocks, hand-spliced (one exact
  whole-construct swap per site with an asserted occurrence count, because a
  scripted multi-line rewrite is the documented mangle hazard); ten collapsed to
  something simpler than the original. Checker.kt 33 → **3**.*
  **CLOSING VERDICT.** Checker.kt's 197: **193 removed, 0 restored, 3 kept** —
  the SOE boundary guard (load-bearing per the SOE doctrine), the `FriBail`
  control-flow catch (never defensive), and the `Parser(...)` on external `.json`
  content (kept on the evidence asymmetry). **One bug found and fixed.**
  **The 20 sites outside Checker.kt are NOT the same population and were left
  alone deliberately** — audited this round: Vfs's 3 are filesystem I/O (a missing
  or unreadable file must yield null, not crash); Parser's 2 guard parsing of
  externally-sourced JSDoc type text; TsBuildInfo / TsConfigLoader /
  ModuleResolver name their exception (`SerializationException`,
  `IllegalArgumentException`) over external JSON; Transformer's one names
  `NumberFormatException`; Emitter's and Flow's "catch" greps are comments and
  emit-helper source strings. Every one either guards an external input or names
  what it absorbs — which is exactly what the residue did not do. The item's
  premise ("~200 sites, all the same shape") holds for Checker.kt alone.*
  **Method addendum from batch 1:** write the batch's corner-case pins FIRST and
  run them against unmodified HEAD — the pins, not the removal, are what find
  bugs, and the HEAD run tells you whether a failure is pre-existing or yours.
  **Rule of thumb from batch 2:** grep the guarded helper for its OWN cycle
  guard / in-progress sentinel first; where one exists the call-site catch is
  redundant by construction and the batch is very likely byte-neutral.
  Next up: the two deep resolvers `getTypeFromTypeNode` (39) and
  `getTypeOfExpression` (41) in small slices, plus the ~30-site singleton tail.

**PERF — the post-inversion performance arc (owner-approved 2026-07-20, round 618:
"proceed according to your recommendations"; measurements + rationale in the
round-618 session note and the rewritten docs/ARCHITECTURE-RETHINK.md § 6). Ground
rules: the INV rules unchanged, PLUS wall-clock claims are decided ONLY by
interleaved A/B medians — anything priced below the ±2% drift band folds into a
structural item instead of landing alone.**

**ROUND-716 RE-SCOPE (owner: "do anything needed … we are free to completely
redesign this project, if the performance gain is on the horizon"). The arc's
diagnosis was wrong and is corrected in docs/ARCHITECTURE-RETHINK.md § 0 — READ IT
FIRST. Headline: the type system is 5.0 s of an 18 s compile (28%); the dispatch
and handler machinery is ~7.6 s (42%); the entire context-cache prize INV.5(c)
exists for is 68 ms. Work (DISPATCH.1) before any further cache/identity work.**

**WORK ORDER (round 716, after the owner's four decisions). The protocol says
top-to-bottom, and the order below IS deliberate — read this before picking:**
**(PARITY.1)** and **(COST.1)** first: both are cheap, and they are what make the
rest safe — PARITY.1 removes the byte-gate veto that priced out general-engine work
(and unblocks LIB.1's ~30 baselines), COST.1 stops the campaign silently
re-accumulating the very overhead it exists to remove. Then **(LIB.1)**, the
silent-wrong-answer fix the owner asked for. Then **(DISPATCH.1)**, the measured perf
lever and the prerequisite for reviving the M0.4 tail migration. **(PERF.HW)** is
opportunistic — run it only with spare budget; it must not preempt DISPATCH.1.

- [x] **(PARITY.1) DONE round 717 — the policy is now a MECHANISM, not a habit.**
  (a) `docs/logical-parity.md`: the owner directive, the form-vs-meaning decision
  procedure as two ALLOWLIST tables (7 meaning axes / 6 form axes, each form axis
  carrying its equivalence obligation; anything in neither table is MEANING by
  default), the four-step per-case procedure, and the generated ledger. (b) The
  mechanism: a `LogicalParityDivergence(baseline, round, pinnedBy, reason)` in
  build.gradle.kts's `logicalParityDivergences` is the SINGLE source of truth — the
  generator emits that subtest `@kotlin.test.Ignore`d with the reason inline (so it
  stays VISIBLE as skipped: a silently-dropped test cannot hide behind an unchanged
  total), rewrites the ledger region in the doc, and FAILS the build on either of the
  two rot modes — a baseline matching no generated test, or a `pinnedBy` class that
  does not exist under src/commonTest. Keyed by baseline FILE name because that is
  exactly one generated subtest (bare/parameterized × errors/emit), all four emission
  sites wired. Self-tested all three paths (valid entry → `@Ignore` + ledger row;
  stale baseline → build fails; missing `pinnedBy` → build fails), then reverted to
  the empty list, which is the healthy state. **Gate: with no entries the generated
  corpus is BYTE-IDENTICAL** (diff -r of the whole generated tree, before vs after),
  so the mechanism costs nothing until used; suite 12,765/0/3 unchanged. (c) is a
  STANDING rule rather than a deliverable, and is written into the doc § 1: every
  "DEAD — regressed N tests" entry in CLAUDE.md and the archive is now a LEAD, and
  re-examining one means re-running the change and classifying its N diffs.
  **The judgement worth keeping:** a form-only diff is a *candidate*, not an
  entitlement — the owner's cost clause ("byte parity is secondary *if it can be
  achieved without extra cost*") means byte parity is still preferred where it is
  free, so a divergence needs a reason it is WORTH having.
- [ ] ~~(PARITY.1) Adopt the logical-parity policy in the gate (original)~~ —
  **owner directive 2026-07-26, and the single biggest unblock in this arc.** "Logical parity is
  important even if we don't reach byte-by-byte parity. If there are tests where we
  diverge but the logic stays the same, create a new test case and switch off the old
  one. The logical value of the compiler output at maximal performance should always
  be the deciding factor; byte-by-byte parity is secondary if it can be achieved
  without extra cost." **What this changes:** a corpus baseline that differs only in
  FORM (union member order, an equivalent message, an equivalent elaboration shape)
  stops being a veto — replace it with a test pinning the LOGIC and disable the old
  one, recording the divergence and why it is equivalent. A baseline differing in
  MEANING is still a hard regression. **Do (a) FIRST, it is cheap and it is what
  makes the rest safe:** (a) add `docs/logical-parity.md` — the form-vs-meaning
  decision procedure, the disable mechanism, and a running LEDGER of every
  switched-off baseline with its justification (an unlogged disable is
  indistinguishable from hiding a regression, so the ledger IS the control); (b)
  extend the generator/harness so a case can be marked logically-divergent with a
  reason string rather than commented out; (c) as engine work proceeds, re-examine
  the "DEAD — regressed N tests" entries in CLAUDE.md and the archive — many were
  never checked for whether the N were form or meaning, so each is now a LEAD.
  **Do NOT** use this to wave through a diff you have not read: the burden is
  demonstrating equivalence per case, in the note.

- [x] **(COST.1) DONE round 717 — `scripts/cost_gate.py`, and the determinism check
  caught a racy counter on its first use.** Runs the compiler profile with
  `--passTiming`, extracts 20 deterministic counters, diffs them against the tracked
  `docs/perf/cost-counters.txt`, fails above ±2% (per-counter), and exits nonzero so it
  drops into the round gate next to the suite. `--update` rebaselines, `--from-log`
  re-parses an existing run (free re-scoring, and how the rebaseline below was done),
  `--tolerance` tunes the bar. Coverage: the front end (pre-parse reuse), the spine
  (nodes walked), the type system (getTypeOfExpression calls/distinct/outside-init,
  narrowing walks, memo serves), type-node resolution (cacheable/hits/bypassed, the
  INV.5(c) mapped cache, fingerprint builds), name resolution (globals
  lookups/conflated/misses) — **plus the compiler's ANSWER (error count, program file
  count), because a cost drop that changes the output is not a win and the gate has
  to be able to see that.** Four counters baseline at ZERO
  (`ctxFingerprint.builds`, `globals.conflated`, `narrow.walksOutsideInit`,
  `preparse.fresh`) and are therefore tripwires: any nonzero value is flagged.
  **Baseline at 41bedb73:** errors 46, spine.nodes 856,962, typeOfExpr 696,933 calls
  over 250,057 distinct nodes, narrowWalks 69,903 (40,546 memo-served), typeNode
  210,397 cacheable / 89,883 bypassed, globals 1,377,511 lookups at 98.9% miss.
  **THE FINDING — the AST census is racy, and it is exactly what (DISPATCH.1) was
  told to derive its table from.** Two runs of the same binary: every counter
  bit-identical EXCEPT the `indexSourceFile` node census, 857,350 vs 854,550
  (−0.33%). `indexSourceFile` runs on the crawl's concurrent parse threads
  (`readAndScanBatch`, Dispatchers.Default, FRONTEND_CONCURRENCY in flight) and
  `PassTiming.nodeKindHistogram` is a plain HashMap, so increments are lost and the
  census always undercounts. Instrumentation-only (no production impact), but it
  means the census is sound for "which kinds dominate" and NOT sound as an exact
  per-kind population. Excluded from the gate (a nondeterministic row teaches people
  to ignore the gate) and warned about at the source in PassTiming.kt; DISPATCH.1's
  derivation needs an exact census — see the note on that item.
- [ ] ~~(COST.1) Enforce the cost gate (original)~~ — **owner-approved 2026-07-26
  ("yes, I want to enforce it, to counter performance regressions").** Round 713 added ~72k
  `getTypeOfExpression` calls (+11.5%, ≈70–200 ms) for one conformance diagnostic and
  nothing noticed, because the round gates are the corpus and `--listAll` and neither
  sees cost. Over 200 rounds that is how ~118 handler consultations per node
  accumulate. **Make it mechanical, not a habit:** a script that runs the compiler
  profile with `--passTiming`, extracts the DETERMINISTIC counters
  (`getTypeOfExpression` calls, `narrowWalks`, `spineNodes`, per-kind enter totals,
  `typeNodeCacheable`/`bypassed`), writes them to a tracked file, and DIFFS against
  the committed baseline — failing loudly above a threshold (start ±2%, tune once
  there is history). Counters, not wall time: they are load-independent, which is the
  whole point (a laptop shows ±13% wall). Wire it into the round protocol next to the
  suite run, and record the baseline in the same commit as any accepted increase,
  with the justification.

- [ ] **(BUILD.1) BLOCKED-PENDING-USER: raise the Kotlin daemon heap — a cold compile
  does not fit in the inherited `-Xmx2g` and HANGS instead of failing.** Measured
  round 717, and it cost that round ~30 minutes. `gradle.properties` sets
  `org.gradle.jvmargs=-Xmx2g`, which the Kotlin compile daemon inherits. An
  INCREMENTAL compile fits; a COLD one does not — and the failure mode is not an
  OutOfMemoryError, it is a GC death spiral that looks exactly like a hang: 350% CPU,
  RSS pinned at the ceiling, `stime` ~5 s against 3,000 s of user time, and **zero
  class files** (Kotlin's backend writes output only at the end, so there is no
  partial progress to read). It ran 14 minutes with no progress; the same build with
  `-Dkotlin.daemon.jvmargs=-Xmx3g` took **2m 33s**. **How you get there:** the
  documented memory ritual before a self-compile — `./gradlew --stop && pkill -9 -f
  KotlinCompileDaemon` — is what makes the next build cold, so the trap is reachable
  from the instructions themselves. **ESCALATED round 718 — this is now the binding
  constraint on the edit-test loop, not a nuisance.** That round diagnosed and wrote a
  complete fix and then could NOT LAND IT, because no compile would finish: `-Xmx2g`
  hung (14 min, zero classes), `-Xmx3g` ran 16 minutes and the daemon died and
  restarted from scratch, and only `-Xmx4g` got the main compile through. Four cold
  compiles were burned in one session. The work is parked on
  `wip/round718-required-minus-optional` purely for want of a gate.
  **CORRECTED round 719 — the heap story is only half of it, and the other half
  matters more.** A round-719 retry at 4 g, on a box verified quiet, sat in
  `compileKotlinJvm` for 40+ minutes with the daemon showing REAL WORK (same PID,
  utime 210 s → 277 s over 2.5 min, RSS 2.38 GB against a 4 GB ceiling, stime ~1.5 s)
  — not the round-717 pinned-at-ceiling death spiral. The same cold compile took
  **2m 33s** at 3 g in round 717. That is CONTENTION, not heap. And there is a
  contender: `chore(bench): 3-way run @ …` commits landed on origin three times
  during rounds 717–719, which the agent did not make — something else builds this
  project and commits here on a schedule. So the round-718 claim "a cold compile does
  not fit -Xmx2g" is over-stated: the 2 g run really did thrash, but the 3 g and 4 g
  failures are better explained by sharing the box.
  **PROPOSAL (owner decision, build-system change = Guardrail):** add
  `kotlin.daemon.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=512m` to gradle.properties,
  **AND/OR settle the sharing** — either give the agent loop a box the bench loop is
  not on, or publish when the bench loop runs so the two interleave rather than
  collide. The second is probably the bigger win: a 2.5-minute compile turning into
  40+ minutes costs a whole round, and no heap setting fixes that. Cost: up to
  2 GB more resident during a compile on a 7.7 GB box — which means a compile and a
  4 g self-compile can no longer overlap, and the memory ritual becomes mandatory
  BEFORE a bench run rather than before a build. That trade is worth stating plainly:
  today the ritual is what CAUSES the hang, and a round can lose an hour to it.
  **A second option worth the owner's consideration:** a bigger box. This one is
  7.7 GB / ~4 cores, the corpus suite takes 7 minutes, a cold compile 3–15, and
  (PERF.HW) already wants ≥8 real cores to answer the parallel-scaling question.
  Workaround until then, recorded in CLAUDE.md: pass
  `-Dkotlin.daemon.jvmargs=-Xmx4g` on the command line.

- [ ] **(LIB.1) Ship the DOM/webworker libs and stop real builds silently running
  UNCHECKED — owner-approved 2026-07-26 ("yes, please fix it"), PROMOTED out of the
  post-v1 backlog because it is a silent wrong answer, not a missing feature.**
  THE DEFECT (measured rounds 687–688): `RealLibFiles` ships no
  `dom.generated`/`dom.iterable.generated`/`webworker*`, so `"lib": ["dom"]` records
  the file in `Resolution.unavailable`, which **nothing outside RealLibs.kt ever
  consumes** — no diagnostic, no failure. Consequence on a 3-line program:
  `HTMLElement` resolves, `document` resolves, and `e.definitelyNotAMember` on an
  `HTMLElement` parameter **compiles CLEAN**. A browser project gets a green build
  with its DOM code entirely untyped. **Worse, and the real root:** `useRealLibs`
  defaults FALSE and NOTHING in the project path sets it (`ProjectCompiler` /
  `TsConfigLoader` never do; the only writer is a test directive), so **every real
  build — all 8 dashboard profiles included — runs on the curated embedded
  `BUILTIN_LIB_SOURCE`** and the whole real-lib machinery is test-only. The owner has
  now authorised the generation change the round-688 note left owner-gated.
  **(a) IS NOW MEASURED (round 717) — the answer is affordable, and the number is 35.**
  No code was needed: every `compilerOptions` key flows through `applyDirective`, so
  `"useRealLibs": true` in the bench project's tsconfig flips the whole real-lib path
  on. Four arms of the `compiler` profile, `--noEmit --listAll`:
  | libs | `types` | errors | composition |
  |---|---|---:|---|
  | embedded | `[]` | 46 | the dashboard number — ALL env-legit |
  | real | `[]` | 81 | +33 node globals (`process`/`global`), +35 real |
  | real | `["node"]` | 48 | 13 env + **35 real** |
  | embedded | `["node"]` | **13** | 13 env (TS2591 only), nothing else |
  So the real-lib switch costs **exactly 35 checker FPs** — TS2722 ×11 ("Cannot
  invoke an object which is possibly 'undefined'" — a narrowing gap on lib members
  the curated lib declared non-optional), TS2322 ×8, TS2345 ×4, TS2344 ×4, TS2339 ×4,
  TS2349 ×2, TS2769, TS2739 — and **no measurable wall time** (28.7 s, inside the
  band). Two corollaries worth having: (i) today's "46 FPs, env-legit only" is
  13 stub-residue + 33 node globals, confirmed by arm D collapsing to 13; (ii) the
  embedded lib is quietly MORE PERMISSIVE than the real one, which is what makes the
  silent-unchecked defect possible in the first place.
  **DECISION for (a), on that evidence: a real project build should use the REAL
  libs** — the mismatch is the root defect, the cost is bounded and enumerable rather
  than open-ended, and it buys faithfulness on every future project. Sequencing:
  burn the 35 down FIRST (they are ordinary FP work, TS2722 being over a third of
  them and probably one narrowing shape), THEN flip the default, so the dashboard
  never goes red. Re-measure services/server/harness before flipping — this is the
  `compiler` profile only, and the bigger profiles will have their own deltas.
  Raw logs: the four arms were run at 8100a78e; reproduce by adding
  `"useRealLibs": true` to `build/bench/tsc-project-*/tsconfig.json`.
  **(a1) FIRST FP FAMILY DIAGNOSED AND WRITTEN, round 718 — 11 of the 35, parked
  UNVERIFIED on branch `wip/round718-required-minus-optional` because this box could
  not complete a compile to gate it (see BUILD.1). Pick it up by merging that branch
  and running the suite + cost gate.** THE DEFECT: `Parser.kt`'s mapped-type modifier
  scan records `-?` as a plain `?`, so `Required<T> = { [P in keyof T]-?: T[P] }`
  behaves exactly like `Partial<T>` — inverted. `-readonly` got its own flag in M1.10;
  the `?` analogue was never done. THE MECHANISM IS NOT THE OBVIOUS ONE: TS2722 does
  not look at the member TYPE for `| undefined` (the codebase deliberately never adds
  it — the emitter says so), it gates on `isOptionalProperty(propSym)`, and a
  homomorphic mapped member CARRIES ITS SOURCE DECLARATION for related info, so the
  source's `?` is what it sees. The fix mirrors M1.10 exactly: a
  `mappedRequiredMemberIds` side-channel, probed in `isOptionalProperty` only when the
  declaration says optional (preserving the documented hot-path property).
  **Two dead ends worth not repeating, both caught by CONTROLS:** hand-rolling the
  mapped types locally (`type MyRequired<T> = { [P in keyof T]-?: T[P] }`) does not
  reproduce anything — we emit NOTHING for user-defined mapped types, so the controls
  came back empty and the target assertions passed vacuously; and asserting
  assignability through `Partial` measures an axis we do not model. The live repro
  needs `@useRealLibs` plus TS2722 assertions. Verified against unmodified HEAD: the
  target fails, the control passes.
  **Also learned:** the embedded lib declares NO utility types at all, which is why
  the whole family is invisible on the default path — `Required<…>` is an unresolved
  name degrading to `any`, and `any` is silent. That is the LIB.1 defect in miniature.
  **ORDER — the original (a) framing, now answered above:** (a) decide what a real
  project build uses for libs at all (the embedded lib is a curated subset; the shipped real
  libs are unreachable outside tests — that mismatch is the root, and it is a design
  choice); (b) ship the DOM/webworker/scripthost sets (changes real-lib generation in
  build.gradle.kts, ~1 MB of generated source); (c) report a user-REQUESTED lib that
  is unavailable — **`Resolution.unavailable` is NOT the right key** (a `full` default
  lib transitively references DOM/host files, so an ordinary target-default resolution
  has a non-empty `unavailable` and must stay silent); it needs a new
  `unavailableRequested` field, and a working implementation is in the round-688
  reflog. **TRAP that wasted a round:** the control "does `HTMLElement` resolve?"
  PASSES while everything is broken — when an unknown name degrades to `any`, name
  resolution proves nothing. The decisive control is a MEMBER probe
  (`e.notAMember` must error). **CORPUS IMPACT, now unblocked by (PARITY.1):** 259
  corpus cases carry `@lib:`, of which 23 request `dom` plus webworker×4 and others,
  all currently green because we silently ignore the request; reporting on the
  embedded path moves ~30 baselines. Under the byte gate that blocked this item;
  under logical parity, judge each as form-vs-meaning and re-pin.

- [ ] **(DISPATCH.1) Per-kind handler dispatch table — the measured lever
  (1.0–2.5 s, ~6–14%), and the only structural item currently backed by a
  decisive probe.** THE MEASUREMENT (round 716, `--passTiming` on the compiler
  profile): `spineEnterNode` reaches ~118 handler entry points and
  `spineLeaveNode` 14 sub-dispatchers, and BOTH run for every one of the 857k
  nodes — 14.8 µs/node enter+leave, of which only ~5.9 µs is type-system work.
  Per-kind attribution shows the shape: `IDENTIFIER` is 381,670 nodes at
  **2,746 ns each = 1,048 ms**, `PROPERTY_ACCESS_EXPRESSION` 67,902 at 4,221 ns,
  `BINARY_EXPRESSION` 38,454 at 6,959 ns — leaf kinds that almost no handler
  wants, each paying the full consultation chain. **THE DECISIVE PROBE: skipping
  `spineEnterNode` entirely for bare Identifiers left the compiler profile's 46
  diagnostics BYTE-IDENTICAL** (`--listAll` diff empty), so that ~1 s is
  provably unnecessary on this profile. (The probe itself was reverted — "skip
  identifiers" is not the fix, it is the evidence.)
  **THE FIX:** precompute, per `NodeKind` (138 dense ids, M0.2 already gives the
  stamped `kindId`), the exact list of handlers that can fire for that kind, and
  dispatch only that list. Most handlers apply to 1–5 kinds, so the average node
  should run ~2–5 handlers instead of ~130.
  **METHOD — this is the load-bearing part, because the handler set per kind must
  be DERIVED, not guessed:** (a) build the table by INSTRUMENTATION, not by
  reading guards — add an opt-in mode that records, per handler entry point, the
  set of `kindId`s for which it does anything observable (emits, writes a frame,
  mutates a map), accumulated over BOTH the corpus suite AND all 8 profiles;
  (b) a handler whose observable-kind set cannot be closed (it climbs ancestors,
  or keys on parent kind) stays in the always-run list — start conservative, the
  win comes from the leaf kinds; (c) land per handler-family, corpus + `--listAll`
  ×8 gated each time; (d) re-measure with the INV.4(g) per-kind counters after
  each family, since the profile shifts.
  **TRAP:** the corpus is the only gate that sees kinds the 8 profiles never
  exercise — a table derived from profiles alone WILL be wrong. Derive from the
  suite run and treat the profiles as confirmation.
  **SECOND TRAP, found round 717 while building the COST.1 gate: do NOT derive the
  table from the existing `nodeKindHistogram` census — it is RACY and always low.**
  `indexSourceFile` runs on the crawl's concurrent parse threads
  (`readAndScanBatch`, Dispatchers.Default) and the histogram is a plain HashMap,
  so two runs of the same binary measured 857,350 vs 854,550 nodes (−0.33%) while
  every single-threaded counter was bit-identical. Losing a third of a percent of
  nodes to a race is harmless for "which kinds dominate" and fatal for "which kinds
  can this handler fire on" — a kind that appears rarely could be dropped entirely
  from a thread's map. The new instrumentation this item calls for must accumulate
  per-handler kind sets from the SINGLE-THREADED check spine (or be made
  thread-safe explicitly), never from the parse-time census.
  Blocks nothing; unblocks the honest re-measure of every other lever.
  **TOOLING — everything needed is landed (round 716); no setup decisions:**
  (1) create the bench project once —
  `scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log`
  (it builds `build/bench/tsc-project-<sha>` from `typescript-repo`; on macOS its
  TSV stat columns log 0 per the BSD-grep gotcha, `wall_ms` and the run log are
  real; on Linux/VPS all columns are real);
  (2) attribute with `--passTiming` — the counters this item is built on are
  `SPINE attribution` (per-phase enter/leave/scope/ures/forEachChild),
  `per-kind enter+leave (top 12)`, `INV.5(c5) bypassed-resolution PRIZE`, and the
  existing `time split` line;
  (3) price every candidate with `scripts/ab-interleaved.sh <dirA> <dirB> <pairs>`
  — it alternates within pairs, reports medians + win rate, flags differing error
  counts (B not behaviour-preserving ⇒ timing incomparable) and applies the ±2%
  drift-band verdict.
  **MEASUREMENT DISCIPLINE (round 716, learned the hard way):** the per-kind and
  phase counters are DETERMINISTIC and comparable across runs and boxes; wall time
  on a loaded box is not (±13% observed on an M1 with a browser running, which
  swamps a 1 s effect). When the effect is smaller than the box spread, decide on
  counters and use wall only for confirmation. **Never run any gradle task while
  `jvmTest` is in flight** — the documented trap is recompiling during a
  self-compile A/B; the INVERSE also bites (a `gradlew` classpath resolution
  during a suite run killed it silently, leaving an empty results dir).
  **THE EXPECTED-VALUE STATEMENT, so a future round can falsify this item rather
  than drift:** DISPATCH.1 should remove 1.0–2.5 s of a ~17 s compile (6–14%).
  If a landed slice measures below the drift band on interleaved medians AND the
  per-kind counters do not fall, the premise is wrong — say so and stop, do not
  grind. The premise is that ~130 handler entry points are consulted per node
  while ~2–5 apply; the probe evidence is IDENTIFIER (44.5% of nodes, 2,746 ns,
  byte-identical output when skipped entirely).
  **SECOND-ORDER VALUE (why this is first):** round 659 measured that migrating a
  tail pass onto the spine recovers only 25% of its cost, and STOPPED the M0.4 arc
  on that basis — but that was measured WITHOUT a dispatch table, where "one walk"
  still consults every handler at every node. With the table, a migrated pass costs
  only its own kinds, so DISPATCH.1 changes M0.4's economics and should be
  followed by a re-measure of one migrated pass before the arc is judged again.

- [ ] **(PERF.HW) Settle the VPS core question by MEASUREMENT, not by spec sheet —
  my call per the owner leaving it to me (2026-07-26).** M2 (parallel scaling) is
  parked with an explicit unpark condition — "a host with ≥8 real cores (re-run this
  exact probe first)" — and the owner cannot say whether the Hetzner instance has
  real cores. **Do not guess from the plan name** (Hetzner's shared-vCPU CX/CPX lines
  behave very differently from the dedicated CCX line under sustained load, and this
  workload runs saturated for ~30 s). **The probe is self-answering and costs one
  session:** run the existing `--workers` mode at 1/2/4/8 on the compiler profile,
  2 reps each, and read the shape — the round-666 baseline on 4 cores was seq 27.9 s
  / w2 24.7 s (−11.5%) / w4 27.9 s (FLAT, contention-bound). If w4 and w8 keep
  scaling, the cores are real and M2 Phase 1 (shared frozen collectors) is worth
  reviving; if w4 goes flat again, the box is equivalent to the old one and M2 stays
  parked with the measurement recorded. **Sequencing: this is NOT single-thread work
  and must not preempt (DISPATCH.1)** — M2 measured 77% of the work as non-divisible
  per-worker duplication, and that fraction is largely the dispatch machinery, so
  shrinking it first is also what makes workers pay. Run this only when a session has
  spare budget, and record the numbers either way.

- [ ] ~~(cache/identity work of any shape)~~ — **CLOSED round 716 by measurement,
  do NOT re-open without new evidence.** (1) The context-bypassed resolution
  population is **68 ms** total (31,571 outermost calls @ 2.2 µs) — 0.35% of the
  compile. (2) Widening the round-548 INV.5(c) gate lifts hits 23% → 46% and
  measures **+28% wall** (6 interleaved pairs); memoizing the fingerprint (builds
  53,765 → 13,293) still measures **+11.9%**. (3) Pure identity keying (tsc's
  mapper-object shape) gets **4.1%** hits, because the context maps are
  re-allocated per install rather than reused per region. (4) The widened key also
  exposed that the context fingerprint is INCOMPLETE — 1,269 shape-different
  serves, all lib generic signatures (`(value: T, …)` served where
  `(value: Declaration, …)` was correct), i.e. the substitution input is ambient
  state captured by none of nsStack/tpScope/aliasArgs; that would have to be fixed
  BEFORE any widening, for a prize of 68 ms. Third independent confirmation of the
  round-659/665 law: **the cacheable population is the cheap tail.**

- [x] **(M0.1) Tail triage — CLOSED round 620 with the deletion hypothesis doubly
  dead.** Phases (a)–(c) ran round 619 (PassLab facility, corpus census —
  artifact `docs/perf/pass-census-round619.txt`, now carrying a correction
  header); the (d) consumer trace (round 620) OVERTURNED the "23 census-silent
  → deletion-ready" verdict, which rested on two flaws: (i) the census records
  only net-POSITIVE deltas — wipe-and-pin walkers (removeAll+pinDiag, net 0),
  rewriters, retractors, and collectors are census-silent while load-bearing —
  and (ii) Phase B's suite green was a FALSE GREEN: Inv0PassTimingTest's
  cleanup assigned `PassTiming.disabledPasses = emptySet()`, re-enabling the
  lab's disables for every test class after 'I' (the whole generated corpus);
  fixed to save-and-restore. The honest disable experiment (fixed cleanup,
  `--rerun`) fails 26 tests: 20 of the 23 are corpus-pinned (incl. one LOCAL
  pin — Inv4SpineBatch27Test for checkCrossFileUseBeforeDeclaration —
  invisible to a corpus-only census). DELETED (the real pool, 3 pure adders):
  checkModuleNoneConflict (TS1148) + checkExportAssignmentInSystem (TS1218) —
  module `none`/`system` are tsgo-removed kinds, their corpus tests
  generator-skipped — and checkUnicodeSurrogatePairImportBinding
  (unicodeEscapesInNames02's TS1127/TS2305 now flow from the general
  scanner/module-member paths; its errors subtest stays green without it),
  plus orphaned helpers. Gates: full suite 11,379/0; `--listAll` ×8
  byte-identical pre-vs-post on all 8 profiles; build warning-clean. Net wall
  value ≈ nil — the whole ~6.2 s tail is pinned; (M0.4) migration carries the
  lever. LAB DISCIPLINE addenda: `build/pass-lab.txt` is NOT a Gradle input
  (always `--rerun` a lab experiment), and a lab-run verdict is unverified
  until the disable is proven active in the SAME JVM that ran the tests.
- [x] **(M0.2) kindId table dispatch — DONE round 621 (2026-07-20), three
  commits.** NodeBase.kindId (dense per-CLASS Int, stamped by each class's
  `init` block — survives `copy()`, unlike nodeId/parent) + NodeKind.kt (138
  dense consts + the sealed-exhaustive `nodeKindIdOf` compile gate);
  forEachChild → javap-verified tableswitch 0..137; the 3 hot checkSpine
  dispatchers (spineEnterNode terminal when / spineUResEnter /
  spineUResDispatch) + the 13 remaining per-node walker whens → kindId
  lookupswitch (~5 int compares over sparse arm subsets). ccetSpineEnter
  deliberately SKIPPED (5-arm when with `is Block -> when (parent) {` +
  a union-smart-cast-dependent multi-class arm; cost/benefit). MEASURED:
  interleaved A/B (5 pairs, compiler profile) A 31,747 → D 30,713 ms median =
  **−3.3%, D wins 4/5 pairs** — inside the priced 2–4%. Gates: suite 11,385/0
  (+6 NodeKindIdTest pins), listAll ×8 byte-identical at each commit,
  warning-clean. Lesson: the scripted conversion mis-cut FOUR two-line
  `if`-header arms into empty-if mangles — corpus caught 3, a structural scan
  (line ending `{` + dedented bare `}`) the 4th; see the session note.
- [x] **(M0.3) CLOSED round 670 — the three landed slices were the arc's most
  reliable wins (−3.9%, −2.6%, −2.2%); the three REMAINING ones are priced
  below the drift band or are multi-session structural work, so none may land
  alone under the PERF ground rules.** Pricing, done this round rather than
  assumed: (i)'s cheap half — the globals-miss short-circuit — is worth
  **≲0.2%**. The probe claim is exactly right (measured live: **1,234,034
  globals lookups, 1,219,892 misses = 98.9%**), but 1.22M skipped HashMap
  probes at a realistic 20–40 ns each is only **25–50 ms of a ~28 s compile**
  (0.09–0.17%; even a generous 100 ns gives 0.44%). (v)'s undo-log is the same
  size — JFR put it at 1.1% of samples, and round 623 established that a JFR
  self-% is not a wall price. (ii) NodeLinks/SymbolLinks consolidation and
  (i)'s FULL form (Identifier → Int atom at scan time + int-keyed scope/member
  maps) are the only pieces that could clear ±2%, and both are multi-session
  structural changes touching the binder and every map. NOTE they are the same
  CLASS as the arc's winners — those replaced allocation-heavy per-call
  structures on hot paths (LongKeyMap/IntKeyMap/NarrowSeen), and atomization
  removes String hashing/equality from hot map traffic — so if perf work ever
  resumes, full atomization is the one lever left worth sizing. It must be
  PRICED first (the arc's rule): instrument the actual time in the map
  operations it would replace, do NOT trust the JFR "~15% in HashMap+String
  equality" figure that opened this item. Original item text follows.
  ORIGINAL: Layout campaign** (JFR-evidenced ~15% of wall in HashMap+String
  equality with NO single hot map — structure-class work, one interleaved-A/B'd
  slice per commit): (i) name atomization (Identifier → Int atom at scan time;
  int-keyed scope/member maps; a globals-miss bitset — the 1.48M probes are 99%
  miss); (ii) NodeLinks/SymbolLinks record consolidation over per-file dense
  nodeId arrays (tsc's exact structure; symbol ids need per-worker dense spaces
  under INV.6 — node ids are per-file dense already); (iii)+(iv) **DONE round
  621: −3.9% wall (31,180 → 29,955 ms median, 5/5 pairs)** — `LongKeyMap`
  (open-addressing Long→V, EXACT packed-id keys, 0L sentinel) fast-paths the
  three intern caches' dominant shapes (null/empty/1-arg refs — null/empty
  pack alike, reproducing the old string key's `"id|"` conflation
  byte-exactly; 2-member unions/intersections; bigger shapes keep the string
  maps) + the `normalizePath` memo; (vi) **DONE round 622: −2.2% wall
  (30,364 → 29,697 ms median, post wins 5/5 pairs)** — `IntKeyMap`
  (open-addressing Int→V, `Int.MIN_VALUE` sentinel: symbol ids span the
  positive main space AND the ≤−2 INV.2(c) scope space, so 0/negative are
  legal keys) replaces `HashMap<Int, ·>` for symbolTypes/declaredTypes/
  symbolTargets, and `NarrowFlowMemo` (parallel int-key/int-depth/Type
  arrays, serve/overwrite depth rules byte-exact, pinned both directions in
  IntKeyMapTest) replaces the narrowing walks' per-invocation
  `MutableMap<Int, Pair<Int, Type>>` — a fresh map per depth-0 walk
  (~111k/compile) allocating a boxed key + `Pair` + map node per store on
  the hottest checker path; (vii) **DONE round 622: −2.6% wall (30,124 →
  29,351 ms median, wins 4/5 pairs)** — int-specialized `NarrowSeen`
  (open-addressing IntArray slots + tombstone removal — popToMark removes
  in reverse insertion order, which linear probing cannot slot-shift;
  EMPTY slots only from rehash, so present-id probes never meet EMPTY
  early — + IntArray add-log; was a double-boxing HashSet+ArrayList on
  every flow-node visit), pinned by a 60k-op randomized oracle vs the old
  form; (v) undo-log
  (the proven NarrowSeen mark/pop pattern) replacing HashMap(other) scope
  copies (putMapEntries 1.1%) — also reduces M1's epoch churn. Do NOT reach
  for a JVM-only map library (build-change guardrail + multiplatform);
  `LongKeyMap`/`IntKeyMap` are the in-repo reusable pieces for later slices
  (IntKeyMap values are non-null and never iterated — the compiler flags
  both constraints at any unsuitable conversion site); (viii) **DONE round
  623, measured NEUTRAL (−0.30% median over 10 interleaved pairs, post wins
  6/10 — below the drift band, NO wall claim)** — lazy/unboxed Parser line
  starts (the eager per-parse table was 5.3% of JFR self samples, only ever
  consumed by diagnostic line/col formatting), the
  `fileDeclaresNonGenericType` fileResults-index + `file|name` memo (was an
  un-memoized per-type-reference top-level statement scan — quadratic
  insurance for bigger projects), and ccetSpineEnter's kindId dispatch (the
  one dispatcher M0.2 skipped, now hand-converted). Landed as structural
  slices on the corpus + listAll ×8 byte-identity gates; the JFR lesson
  (counted-loop self-% is safepoint-bias-inflated + parallel-crawl savings
  don't move serial-dominated wall — A/B before believing any self entry)
  is in the round-623 session note.
- [x] **(M0.4) CLOSED at 35 passes by the round-659 arc measurement — see
  (M0.4-AB) for the number and the verdict. NOT a wall-clock lever: 75% of a
  migrated pass's cost reappears inside checkSpine (the 35 deleted rows summed
  3,146 ms; checkSpine grew +2,358 ms), the interleaved arc A/B is +0.24% on
  compiler / −1.6% on harness = inside the drift band, and finishing the
  remaining ~90 rows would buy ~1.1 s (~4%) for ~90 rounds. Do NOT migrate
  another tail pass for performance; migrate one only when it is on the path of
  another change, and keep this item's migration-pattern zoo as the reference
  for HOW (it is complete and each shape is documented below).** The original
  item text, and the per-round record of all 35 migrations, follows —
  Migrate the surviving pinned tail into the spine (the documented
  migration-pattern zoo), cost-descending; retire dead migration scaffolding as
  it goes (emit-twice arms whose legacy side is gone, the dead m3
  truncation-mark blocks). Post-round-619 this carries the WHOLE tail lever
  (~6.2 s, all corpus-pinned — the deletion pool measured 59 ms): the worklist
  is the `--passTiming` cost table intersected with
  `docs/perf/pass-census-round619.txt` (top by cost at the round-624 HEAD
  table: checkObjectSpreadInvalidTypes 165.6 ms — **MIGRATED round 624**,
  checkArrayPushDiscriminatedUnionElements 138 ms — **MIGRATED round 624**,
  checkImplicitThis 127 ms — **MIGRATED round 625** (the frameless variant:
  a pass threading ONLY downward context — no statement-ordered state —
  migrates as a pure pull-based per-anchor ancestor fold, no frames, no
  leave hook, no memo when anchors are rare),
  checkFnTypedParamCalls 119 ms — **MIGRATED round 626** (the downward-MAP
  variant: FnParamCtx rebuilt-at-boundaries/accumulated-through-boundaries
  reproduces as the pull-based fold WITH a per-boundary-child ctx memo —
  anchors are every Identifier-callee call, too frequent for the round-625
  memo-free form — plus a memoized BINARY reach classifier: no multi-state
  statuses needed when every (parent kind, child slot) pair decides descent
  unambiguously),
  checkAbstractClassInstantiation 113 ms — **MIGRATED round 627** (the
  collector-prepass variant: four FILE-scoped collectors reproduce as
  per-file spine-setup state, not frames; the statement-LIST overlay
  (add-abstract-then-remove-shadowed, a pure function of the ancestor
  list-owner chain SourceFile/Block/ModuleBlock/CaseClause/DefaultClause)
  rebuilds pull-based per anchor with a per-owner memo; the
  `[A].map(cls => …)` callback-param typeof extension recovers on the
  anchor climb folded OUTERMOST-first — node coverage is identical
  between the legacy handled/unhandled branches, so the reach classifier
  needs no special case; no ambient sandwich — the emission reads no
  checker ambient),
  checkSymbolToStringConversions 108 ms — **MIGRATED round 628** (the
  downward-SETS variant: accumulate-only (symbolNames, tpNames) sets
  rebuild pull-based per anchor; the per-body whole-list locals PREPASS
  reproduces as per-boundary LEVELS with only fn bodies and ModuleBlocks
  as collection boundaries — inner Block/clause re-collects were always
  subsets; two reach edges differ from the fp/ai classifiers: case-clause
  and bare for-initializer EXPRESSIONS are reached),
  checkDefiniteAssignmentViaFlowGraph 105 ms — **MIGRATED round 629** (the
  FILE-END variant: a pass whose per-file body is a positional dedup scan
  over prior diagnostics + whole-file flow walks migrates as a dispatch in
  checkSpine's per-file loop AFTER spineWalkFile returns — never
  per-anchor — so the dedup scan sees the file's spine-emitted TS2454s;
  the walker family stays verbatim, the only ambient install is
  currentFlowGraph save/restore, and the B223 sibling stays at its own
  pass slot since it scans no prior diagnostics),
  checkSameTargetReferenceCastOverlap ~123 ms — **MIGRATED round 630** (the
  SHARED-WALKER variant: only the pass's whole-file driver is deleted — the
  walkTypeAssertionsInStmt/-InExpr recursion SURVIVES for the cast-overlap
  sibling passes, so the reach classifier mirrors the shared walker's arms
  and must stay IN SYNC with any future walker-arm change; the first
  TYPE-RESOLVING tail migration — per-anchor getTypeOfExpression/
  getTypeFromTypeNode/relation calls interleave into the spine walk, gated
  clean by corpus + listAll ×8; ambient sandwich = currentCheckFileName +
  a nulled currentFlowGraph around the emission pair),
  checkBindingPatternComputedIndexSig ~120 ms — **MIGRATED round 631** (the
  MULTI-ANCHOR-KIND variant: three emission families dispatch from one
  enter hook over seven anchor kinds, member-parameter emissions gated on
  the member's PARENT kind — objlit/class-EXPRESSION members emit,
  FunctionDeclaration/class-DECLARATION members never do; the reach
  classifier is a FROZEN copy of the deleted walker's arms, deliberately
  NOT shared with the surviving cast walker's spineCoEdge, which it
  matches except FunctionDeclaration parameter defaults; the TS2537
  emitters install the spine-entry RESTING currentFileLocals per emission
  — the legacy pass never installed it),
  checkConstEnumDiagnostics ~123 ms — **MIGRATED round 632** (the
  FILE-GATED variant: the legacy whole-file collectConstEnumDecls gate
  reproduces as per-file setup state — anchors inert in files without
  their own const enum; the TS2567 top-level merge scan rides setup; a
  resolution-CONDITIONAL walker descent (property/element-access bases
  skipped when the base IS a const enum) reproduces as an unconditional
  edge + an anchor-side parent pre-filter, exactly equivalent because
  neither branch can emit at a base — keeping the classifier purely
  structural), then
  checkNullTypeAssertionOverlap ~104 ms — **MIGRATED round 633** (the
  FLAG-ARM-LIFT variant: the `inNullCastOverlapPass`-gated emitters
  lift out of the SHARED walker onto the round-630 anchors —
  spineCoStatus/spineCoEdge reused verbatim; binderResults-iterating
  driver → the spine's partition view, gated `--partitionCheck 2`
  EQUIVALENT ×8), then
  SKIP checkCrossFileModuleAugmentationDuplicates (114 ms — CROSS-FILE
  aggregation, not per-file spine material), then
  checkProtectedMemberReadAccess ~103 ms — **MIGRATED round 635** (the
  PUSH-BASED ORDER-DEPENDENT variant, the round-531 arith pattern's first
  M0.4 application: a pass whose downward map is statement-order MUTATED
  (per-declaration `vars[nm] = …` recordings that LEAK through
  block/if/loop/arrow descents and COPY at nested-fn boundaries)
  reproduces as LIFO frames at fn-like boundaries + per-declaration
  recordings at VariableDeclaration LEAVES (the legacy walk-then-record
  order), with a 5-STATE reach classifier — CONTAINER_FILE/CONTAINER_NS
  split because only FILE-level ExpressionStatements are walked with the
  per-file topVars map, installed by INSTANCE so IIFE-body recordings
  persist across top-level statements; the `=`-LHS write skip is an edge
  (LHS subtree never read-walked, the write check fires at the
  BinaryExpression anchor under the frame-maintained pmrInClassMethod
  gate)), then
  checkPropertyInitialization ~99 ms — **MIGRATED round 636** (the
  MULTIPLICITY variant: the legacy ClassDeclaration statement arm
  double-walks member bodies — checkClassPropertyInit's nested recursion
  PLUS the arm's own member loop — so nested classes emit 2^depth
  duplicate TS2564s, reproduced by an INT-valued reach classifier
  returning a VISIT COUNT (spinePiMult: a bottom-up climb multiplying
  per-edge factors {0,1,2}; every factor local to one edge, no
  multi-state fold — the arrow/fn-expr partial-body restriction resolves
  by peeking at the Block's parent); the anchors repeat the split-out
  checkClassPropertyInitEmit that many times; the recursion walkers
  SURVIVE for the B439 declarationOnly dispatch — the round-630
  shared-walker rule, spinePiEdge mirrors them);
  checkGenericIndexWrite 117.3 ms — **MIGRATED round 637** (the
  DOWNWARD-MAP variant's third application: the (tparams, tpProps,
  refs) triple rebuilds pull-based per anchor with a per-boundary-child
  memo — tparams ACCUMULATE through class/fn boundaries, refs REBUILD
  per fn-like boundary from params + the body-WIDE collectTpLocalsMap
  prepass (whose descent is NARROWER than the scan's — switch/try
  locals uncollected, frozen + pinned), tpProps from the nearest
  enclosing class member (RESET by a nested FunctionDeclaration,
  cleared for property initializers); anchors are `=` binaries with a
  paren-unwrapped ElementAccess LHS; zero TS2862 on all 8 profiles →
  the listAll gate pins pure non-perturbation);
  checkArgumentsCollision 116.8 ms — **MIGRATED round 638** (the
  CONSTANT-CONTEXT variant, the simplest yet: the only downward value is
  the per-file isModule boolean, so no frames, no ctx memo — the
  per-construct declare/body gates re-derive at the anchor from the
  construct node + its parent kind (class-DECLARATION members need
  body + !class-declare and its set-accessors never param-check, while
  class-EXPRESSION/objlit members param-check unconditionally — frozen
  asymmetries, pinned); a WIDER reach than gIdx (arrows/fn-exprs/
  class-expr members/objlit members/template spans/typeof operands
  descend; if/ternary conditions, loop/switch heads, class-decl property
  initializers, declare-namespace bodies stay silent) = a fresh edge
  set; the run-level dispatch gate (target < ES2015 || any non-dts
  module file) becomes the run-active flag);
  checkEvolvingEmptyArrayImplicitAny 103.2 ms — **MIGRATED round 639** (the
  PER-LIST-OWNER variant: a per-STATEMENT-LIST scope pass dispatches each
  scope's list ONCE at its owning SourceFile/Block/ModuleBlock enter, gated
  by a multi-state reach classifier carrying the deleted evRecurseScopes'
  level-skipping quirks — try/catch/finally clause statements and
  case-clause statements recurse WITHOUT forming a scope list (a candidate
  declared directly there never fires) while a Block statement inside them
  IS a scope; arrow/fn-expr bodies and class EXPRESSIONS are never scopes;
  a dotted `namespace A.B` IS one (the parser keeps a direct ModuleBlock
  body — the scope map's "never" guess was wrong, caught by the pins);
  Part 2 is TYPE-RESOLVING → per-dispatch ambient sandwich of resting
  currentFileLocals + per-file currentCheckFileName + a nulled
  currentFlowGraph);
  checkUndefinedClassInterfaceName 123.9 ms — **MIGRATED round 640** (the
  TWO-INTERLEAVED-WALKS variant: a pass running two recursions with
  disjoint node sets — the statement-only name-check walk (never descends
  fn/class-member bodies) + the yield walk started at name-reached
  FunctionDeclarations — reproduces as ONE multi-state classifier whose
  statuses carry the walk identity AND the downward generator flag
  (UY_NAME / UY_YGEN / UY_YNON, plus UY_MEMBER bridging a yield-walked
  container's member to its body/initializer); the frozen member filters
  ride the container edges — class DECLARATIONS walk accessor bodies +
  prop initializers, class EXPRESSIONS method/ctor only, objlit members
  methods only, accessors never; the legacy left-spine BinaryExpression
  fold reduces to plain left/right edges, reach-equivalent; zero
  emissions on all 8 profiles → the listAll gate pins pure
  non-perturbation);
  checkSuperRefInRebindingScope 113.1 ms — **MIGRATED round 641** (the
  rebound-boolean-as-status variant: the walk's one downward boolean
  rides the classifier status — fn-decl/fn-expr bodies reset to rebound,
  arrows/ModuleBlocks preserve, class-member bodies/prop initializers
  reset to clear via a member-carrier status; the frozen `super(...)`
  CALLEE skip is the anchor's direct-parent gate so a parenthesized
  super callee still fires; object literals skipped entirely — the
  sibling checkSuperInObjectLiterals is position-disjoint);
  checkInvalidAssignmentTargets 105.8 ms — **MIGRATED round 642** (the
  INT-depth classifier's second application: the shared `checkDepth`
  frame counter reproduced per node with +1 on every expression parent's
  outgoing edge — statement lists nested inside expressions inherit the
  elevated ambient — and NO right-spine absorption, so deep chains prune
  at the 200 cap, pinned at the exact boundary; the orphaned checkDepth
  counter deleted from Checker + CheckerState);
  checkTypeParameterDefaults 150 ms — **MIGRATED round 643** (the
  SPLIT-PRODUCER variant + the first PARSE-RECORDED candidate set: a
  pass whose side-set write cannot ride the spine — cross-file/
  earlier-in-file display consumption — SPLITS: the TS2368/TS2744
  emissions anchor at the ten TP-list-bearing construct kinds over a
  binary reach classifier, and the pre-spine producer consumes
  SourceFile.typeAliasesWithTpDefaults (recorded at the parse site,
  moduleSpecifiers-style — no tree walk; 0.4 ms vs the legacy 150 ms
  row) FILTERED through the SAME classifier — one frozen edge set
  serves both halves, and a speculative-parse discard classifies
  unreached via its detached parent chain. Producer-scan lesson: a
  forEachChild worklist re-scan of the tree costs MORE than the legacy
  walk it replaces (264 ms raw, 218 ms TypeNode-pruned) — parse-time
  recording is the shape for future split producers);
  checkExpandoFunctionNestedReads 99 ms — **MIGRATED round 644** (the
  file-gated + pull-based-shadow combination: the write collector runs
  at per-file SETUP — it never descends function-likes, so the
  double-walk of top-level expression code is bounded and the anchors
  emit inline against the COMPLETE declared map, no buffering; the
  ChainedNameSet shadow chain rebuilds pull-based per anchor — every
  fn-like ancestor of a reached anchor was entered through its walked
  interior, so each contributes its layer; anchors pre-gate on the
  candidate-receiver TEXT, so the memo-free rare-anchor rule applies);
  checkStrictModeIdentifiers 96 ms — **MIGRATED round 645** (the
  MODE-ROUTED variant: the first pass whose SourceFile root edges
  route by a per-file MODE decided at setup — module/strict/fn-local —
  and whose statuses carry the walk IDENTITY across two interleaved
  families: the strict emission walk and the fn-local SEARCHING walk,
  with prologue-tested flips at fn-body edges; the module top-level
  specials continue INTO the strict walk at initializer/body edges;
  class subtrees unreached by construction — the legacy class-element
  walk ran with an EMPTIED restricted set, so it could never emit; the
  `var eval` TS2300/TS6203 pair rides the VariableStatement anchor);
  checkConstLiteralComparisons 95 ms — **MIGRATED round 646** (the
  SINGLE-ADDING-ARM variant: a downward-MAP pass where only ONE arm
  ADDS entries — the for-init const-literal transform; the whole-list
  shadow prepass and fn-param boundaries only REMOVE — needs no
  per-boundary memo: the map is empty at any anchor without a
  ForStatement ancestor whose const init adds one of the anchor's
  operand names, so a cheap parent-climb pre-filter guards the precise
  memo-free reach+scope fold; the legacy left-spine binary iteration
  dissolves into plain left/right edges);
  checkSuperInObjectLiterals 91 ms — **MIGRATED round 647** (the
  boolean-as-status shape's second application with OBJLIT anchors: the
  legacy ObjectLiteralExpression arm SPLITS — its per-property EMISSION
  half becomes the anchor-called emitObjLitSuperProperties running the
  bounded findObjLitSuperRefs leaves, while its walk-continuation half
  dissolves into classifier edges (objlit method/accessor bodies →
  SU_VALID via the SU_OMEMBER carrier; a PropertyAssignment initializer
  is a plain PRESERVE edge — the legacy fn-expr/arrow initializer
  dispatch reproduces exactly on the general FunctionExpression-resets/
  ArrowFunction-preserves arms); the classHasExtends boolean rides the
  CARRIER CHOICE (SU_CMEMBER_EXT/SU_CMEMBER_NOEXT), not a separate
  channel; anchors pre-gate on the emission shape before the memoized
  climb);
  checkTypeParamStrictSubtypeCast 93.7 ms — **MIGRATED round 648** (the
  FOLD-THROUGH variant: the first classifier reusing ANOTHER pass's edge
  set — TC_SHARED hands off to spineCoEdge; pull-based TP-scope layering
  rebuild with method-param typing; the B402 empty-objlit local set as a
  per-list-memoized union over enclosing TPC lists);
  checkDeleteOperator 86.8 ms — **MIGRATED round 649** (a straight
  template application: binary reach classifier over the deleted walker
  arms, one per-file isStrict setup boolean, resting-currentFileLocals +
  null-flow sandwich with currentCheckFileName deliberately untouched);
  checkConstructorParamInInitializers 85.5 ms — **MIGRATED round 650** (the
  multi-state class-anchored reach classifier: CP_STMT/CP_EXPR reproduce
  the two deleted routing walks, CP_ABODY the restricted arrow/fn-expr body
  — its three permitted statement kinds handed straight to CP_STMT, which
  descends them to CP_EXPR identically to the legacy inline loop, so no
  extra restricted-body statuses — and CP_MEMBER the class-member conduit
  carrying the DECLARATION-vs-EXPRESSION descent asymmetry, member bodies +
  property initializers for a class DECL, property initializers only for a
  class EXPR; fully syntactic, no ambient sandwich);
  checkAbstractMemberContext 81.6 ms — **MIGRATED round 651** (the
  AMBIENT-CLIMB variant: a downward BOOLEAN that is a pure function of the
  ancestor chain need not ride the classifier status (round 641) NOR a
  frame stack — it is re-derived by a SEPARATE cheaper ancestor climb
  (spineAbInAmbient), halving the status space to AB_STMT/AB_EXPR/AB_MEMBER;
  sound because `inAmbient` is monotone (`|| Declare in modifiers` at
  ClassDeclaration/ModuleDeclaration, pass-through everywhere else) and the
  ONLY walked edges out of those two kinds are into member BODIES / the
  MODULE BLOCK, so for a REACHED node "some `declare` class/module ancestor
  exists" IS the threaded OR — the climb must therefore run only AFTER the
  reach check passes; one AB_MEMBER conduit serves both class DECLARATIONS
  and class EXPRESSIONS since Ab recurses member BODIES only, never property
  initializers, so there is no DECL/EXPR asymmetry to encode; four
  deliberate divergences from the same-shaped round-650 CP fold, each pinned
  both directions: NO declare-skip anywhere (the flag suppresses only the
  EMISSION), arrow/fn-expr Block bodies are the FULL statement walk (a class
  DECLARATION in an arrow body IS reached), and the switch SUBJECT and
  ternary CONDITION ARE walked);
  checkImplicitAnyYieldExpressions 107.2 ms — **MIGRATED round 652** (the
  ANCHOR-SIDE-GATE variant of the round-641 boolean-as-status shape: the
  downward `inGen` boolean rides the status — it is RESET by every nested
  function-like, so it is NOT monotone and round 651's ambient climb does
  NOT apply — while a frozen EMISSION SKIP whose condition is decidable
  from the ANCHOR's OWN parent chain (the round-479 discarded-result rule:
  a statement-position `yield x;`, parens transparent, draws nothing) is
  re-expressed as a four-line paren-climb AT THE ANCHOR instead of a reach
  state, which would have doubled the status space; ONE arm set serves both
  deleted walks since statement and expression node classes are disjoint —
  no walk-identity channel; IY_MEMBER carries class member bodies AND
  property initializers, both → IY_NON, so no DECL/EXPR asymmetry and class
  EXPRESSIONS are never walked);
  checkAbstractMemberAccessInConstructor 68.4 ms — **MIGRATED round 653**
  (the SPLIT-AT-THE-RE-ENTRY-BOUNDARY variant: a pass whose per-anchor
  leaf can RE-ENTER the pass on a nested anchor splits at that boundary
  and KEEPS the routing walkers alive — the spine reproduces the
  ROOT-driven reach, the surviving recursion the LEAF-driven reach, and
  the two compose to the legacy multiplicity (a class expression in a
  PROCESSED constructor is processed TWICE) with no INT-valued round-636
  classifier; the round-630 sync rule applies to the survivors. Second
  move: the legacy VariableStatement NAME OVERRIDE is recovered
  ANCHOR-side from the parent declaration's classifier status — round
  652's anchor-side gate applied to a NAME, since that arm's reach is
  identical to the plain initializer edge. Reach is PURELY STRUCTURAL —
  the routing walk threads no downward value and the emission walk's
  inDeferredFn lives inside the surviving leaf, so neither a status
  channel nor an ancestor climb is needed; the file-scoped classMap
  prepass rides setup);
  checkIncDecTypeParamOperands 68.3 ms — **MIGRATED round 654** (the
  STRUCTURAL-TWIN variant, the cheapest migration class: when the next
  tail pass is a structural twin of an already-migrated one — here round
  637's checkGenericIndexWrite, whose own source comment says it mirrors
  THIS pass's scope threading — the migration is a TRANSCRIPTION of the
  twin's shape (same boundary-child set: fn-decl/method/ctor/accessor
  BODIES + class-property INITIALIZERS; same pull-based per-anchor ctx
  memoized per boundary child; same memoized binary reach classifier),
  and the whole cost is (a) diffing the two legacy walkers' arm sets and
  (b) pinning the differences — here exactly TWO expression arms
  (TypeAssertion + satisfies casts are transparent to this walk, absent
  from gx's). The downward triple is gx's with SETS instead of maps:
  tparams accumulate, tpProps rebuild from the nearest enclosing class
  DECLARATION (reset by a nested FunctionDeclaration), tpLocals rebuild
  per fn-like BODY from the body-wide prepass);
  checkConflictMarkers 67.8 ms — **NOT SPINE MATERIAL, OPTIMIZED IN PLACE
  round 654 tail** (a pure per-file SOURCE-TEXT scan with no AST walk at
  all: nothing to fold into the spine, cost INTRINSIC, lever ALGORITHMIC.
  A marker is meaningful only at a LINE START, so the scan now hops line
  starts via `indexOf('\n')` instead of testing every character —
  67.8 → 26.5 ms, 2.6×; the intermediate four-`indexOf(marker)` form was
  REJECTED at 45.1 ms because `=`/`<`/`>` false-start on nearly every
  line. The pass keeps its own slot; gated by 9 new pins + the ACTIVE
  generated conflictMarker* `.errors.txt` subtests + listAll ×8);
  checkImplicitAnyNewExpressions 66.9 ms — **MIGRATED round 655** (the
  NO-DOWNWARD-VALUE variant, the simplest class: when the deleted
  recursion's parameter list is CONSTANT — every recursive call passes
  the arguments it received — there is no ctx rebuild, no frames, no
  leave hook and no status channel; the whole migration is the round-649
  spineDelStatus shape with a different edge set. Two per-migrator
  notes: the ambient install is the FILE's own binder locals because the
  legacy DRIVER installed them itself (unlike the resting-locals
  captures of rounds 624/625/631/649), and the arm diff against the
  same-shaped `del` classifier is real — objlit method/accessor bodies,
  `for`-head DECL-LIST initializers and switch case EXPRESSIONS are
  walked here and not there);
  checkArgumentsInClassFieldInitializers 82.9 ms — **MIGRATED round 656**
  (the round-640 TWO-INTERLEAVED-WALKS variant's second application, with
  one template refinement: when two interleaved walks share MOST of their
  arms — here ~30 of ~45, every shared arm having an identical child set
  whose child simply KEEPS the parent's status — write the fold keyed on
  the node KIND and branch on `pStatus` only inside the differing arms,
  so the walk identity "rides along" a pass-through arm (`-> pStatus`)
  instead of duplicating 30 arms under an outer `when (pStatus)`; round
  640's outer-status form is right only when the two walks' node sets are
  near-DISJOINT. Three statuses: AF_ROUTE (class-finding), AF_EMIT
  (inside a property initializer / static block, where the `arguments`
  Identifier anchor fires), AF_MEMBER (the class/objlit member conduit
  whose member KIND picks the resuming walk); the five reach asymmetries
  — EMISSION-only if/loop heads + switch subject + case expressions,
  EMISSION-only objlit method/accessor bodies, EMISSION-only arrow
  parameter defaults, a ClassExpression `declare`-gated in ROUTING and
  UNGATED in EMISSION, ROUTING-only namespaces and `export =` — are the
  whole risk surface and are pinned both directions; multiplicity 1
  everywhere, fully syntactic, NO ambient install at all);
  checkArrayToClassCastOverlap 72.5 ms — **MIGRATED round 657** (the FOLD-IN
  class, the cheapest there is: the pass OWNED NO WALK — it only DROVE the
  SHARED walkTypeAssertionsInStmt/-InExpr recursion with its emitter as the
  callback, and a sibling driving the SAME walker was already on the spine
  (round 630), so CO_REACHED IS its reach by construction and the whole
  migration is one leaf call added to that arm + the driver deleted. No
  classifier, no edge diff, no frames/ctx/status/memo. Two placement details
  carry the correctness: the leaf goes LAST in the arm because its legacy slot
  ran after the round-630/632 passes (insertion order at a shared position),
  and the legacy ambient needs no new install — checkSpine's per-file loop
  already sets the file's binder locals and the shared arm installs
  currentCheckFileName. BEFORE picking any next tail pass, grep its driver for
  a shared-walker call: a fold-in is orders of magnitude less work);
  checkTypeParamTypedOps 71.0 ms — **MIGRATED round 658** (the round-635
  PUSH-BASED ORDER-DEPENDENT variant, and the first whose downward context
  includes a TYPE-SYSTEM AMBIENT rather than only plain data: `tpVars` is
  MUTATED in statement order, LEAKS through block/if/loop/try/namespace
  descents and REBUILDS at every fn-like body from its own parameters, so it
  rides a LIFO of frames at exactly the legacy new-map/new-scope boundaries —
  while the legacy `withInternedTpScope` REGION, which a spine migration
  cannot hold open across nodes, is reproduced by CAPTURING its result: run
  it at the boundary for its interning + constraint-materialization side
  effects, read currentTypeParamScope/currentTypeParamAstForOps from inside
  the block, carry the pair on the frame and install it around each dispatch
  only. The VariableStatement two-loop order — record all declarations, then
  emit on the initializers — reproduces as a recording dispatch at that
  statement's ENTER. Reach is unusually NARROW: `for` heads, `switch`, object
  and array literals, templates, all four cast forms, await/yield,
  typeof/void/delete operands, spreads, comma chains and — the big one —
  ARROW and function-EXPRESSION bodies have NO arm, and on the class side only
  method/ctor/accessor BODIES are reached);
  next per-file candidates by cost (round-656 table, the migrated rows
  gone): **checkVarHoistRedeclaration 68.9 ms**,
  checkCallTypeArgCount 66.2 ms,
  checkIllegalSuperCallsInNestedFunctions 62.7 ms,
  checkTypeArgumentConstraints 62.7 ms, checkSpreadPropertyOverrides
  62.5 ms
  (checkCrossFileModuleAugmentationDuplicates, now 109.7 ms, stays
  SKIP — cross-file aggregation, not per-file spine material; the tail is
  now VERY FLAT — no per-file row above 73 ms, so per-pass wall value is
  small and the remaining ~90 passes >20 ms carry the residual ~4.3 s). Migration protocol per
  pass (the round-624 template): slot-move pre-gate commit (intact pass to the
  post-spine slot, corpus + listAll ×8), then the migration commit (frames at
  the legacy copy edges, memoized reach classifier, per-dispatch ambient
  sandwich + pull-based TP rebuild, local pins, corpus + listAll ×8). A
  single-pass wall delta (~0.5%) is BELOW the drift band — the per-item
  evidence is the `--passTiming` table (the pass's row gone, checkSpine's row
  not inflated), not an interleaved A/B; A/B the ARC once several passes land.
- [x] **(M0.4-AB) ARC MEASUREMENT PAID — round 659. VERDICT: STOP the arc at 35
  passes; (M1) is next.** The number the arc owed since round 624 is in, and it
  says the migration is NOT a wall-clock lever. Method as queued: pre-arc binary
  `4b0dfcc7` (round-623 HEAD) vs HEAD `e9d8279d`, both class dirs kept, NO
  recompile between measurements, alternating within-pair order.
  **compiler profile, 6 interleaved pairs: pre median 28,945 ms → post 29,015 ms
  = +0.24%, post wins 3/6** (per-pair deltas −667…+1,190 ms — the noise spread
  is ~4% of total, an order of magnitude above the effect). **harness profile,
  2 pairs: 40,256 → 39,605 = −1.6%, post wins 2/2.** So the true effect is a
  SMALL gain somewhere in 0–2%, entirely inside the ±2% drift band the ground
  rules refuse to land on.
  **THE MECHANISM, measured (this is the transferable part): 75% of a migrated
  pass's cost REAPPEARS INSIDE checkSpine.** Same-run `--passTiming` both sides:
  the 35 deleted rows summed **3,146 ms**, while checkSpine grew
  **18,896 → 21,253 = +2,358 ms**. The tail was NOT redundant traversal that a
  single walk eliminates — it is per-node work that a single walk still has to
  do, now as ~35 `when (kindId)` dispatches plus memoized ancestor-climb reach
  classifiers on EVERY node of EVERY file. The multiplication moved from "N
  walks over the tree" to "N dispatches per node", which is the same order.
  **THE RATE ARITHMETIC that closes the arc:** the residual is ~25% of migrated
  cost. The remaining tail is ~90 rows >20 ms ≈ 4.3 s, so finishing it buys
  ~25% × 4.3 s ≈ **1.1 s ≈ 4% of wall — for ~90 single-pass rounds.** (M1)
  targets ≤15–20 s from ~29 s = **30–45%**. The arc stops here; the 35 landed
  migrations keep their real value (they are behaviour-preserving, they deleted
  ~8 k lines of walker recursion, and the spine is now the single place per-node
  checks live), but no further pass is migrated FOR PERFORMANCE. Migrate one
  only when it is on the path of another change. Bench TSV rows carry both
  medians locally (`bench/` is gitignored — the round-659 session note is the
  durable record and carries every per-pair number).
- [x] **(M1) COMPLETE (rounds 660–665) — banked 0.83 s (−2.93%), which is the
  arc's only live win; the rest of the advertised prize was never there.**
  Ledger: an original "≤15–20 s path" (30–45%), retired at round 660 for a
  measured ~3.3 s, corrected to ~2.5 s at round 662 when a key collision was
  found in the instrument, of which round 664's live dependency-keyed flow-walk
  memo banked 0.83 s and round 665 showed the remaining ~1.1 s expression half
  was a 35× over-estimate (real value 30 ms). What survives as reusable
  machinery: the tagged epoch (`bumpExprEpoch`), the dependency-keyed live walk
  memo, and three shadow classifiers that made every one of those corrections
  cheap. Original framing, retained for context. Realistic prize ~3.3 s of ~29 s = 11–13% — NOT the
  "≤15–20 s path" this item used to claim (that figure was never measured; it
  is retired).** Ceiling arithmetic, from the round-660 `--passTiming` run:
  narrowWalks cost 3,942 ms over 111,248 walks ≈ 35 µs/walk, so a PERFECT walk
  memo saves the 1,000 ms of already-identical repeats plus ~34.2k × 35 µs
  ≈ 1.2 s → ~2.2 s; the getTypeOfExpression shadow memo could serve 149,742 of
  484,628 calls (31%) ≈ 1.1 s. Both together ≈ 3.3 s. Still the biggest single
  lever left (3× the whole remaining M0.4 tail), but size the work to it.
  - [x] **(a) DONE round 660 — attribution instrumented, and the item's premise
    was WRONG.** Every fence bump is now tagged (`bumpExprEpoch(src)` →
    `epochBumps`) and the walk probe's `walkMiss` is split cold vs
    epoch-invalidated with a result comparison + blame tag. (1) Of 80,034
    misses, **45,476 (57%) are COLD** — a first sighting of that reference, so
    no fence design recovers them; the old "80k walks run at fresh epochs"
    framing conflated cold with churn. (2) But the fence IS far too coarse: of
    the 34,558 invalidated repeats **99.6% recompute to an IDENTICAL result**
    (only 133 differ). (3) The coarseness is NOT noise, so **"fence per map"
    will not fix it**: meanEpochDelta is 218 (the fence moves ~218× between two
    walks of one reference) and blame concentrates in currentLocalTypes swaps
    (67%) + currentFlowGraph swaps (29%) = 96% — the spine's per-scope and
    per-file installs, i.e. GENUINE state changes. ALSO LANDED: no-op guards on
    all 13 fenced setters (`if (field !== v)`), which remove 1.46 M pure no-op
    bumps (28% of fence traffic) and drop meanEpochDelta to 154 — but recover
    only ~200 of the 34.5k invalidated repeats (0.6%), which is finding (3)
    measured from the other side. Kept: correct, sharpens the blame table, and
    the live memo will need it. (The epoch is PROBE-ONLY today — read only under
    `--passTiming` — so none of this can change compiler behaviour.)
  - [x] **(b) DEPENDENCY-KEYED validity — SOUND, gate MET (rounds 661–662).**
    Each memo entry records the FlowGraph identity plus the Type INSTANCE bound
    to the reference's ROOT NAME in currentLocalTypes / narrowedDeclaredTypes,
    and a repeat is served while those match — so a swap to a DIFFERENT scope map
    that still binds the root to the SAME instance is not an invalidation, which
    is the population the global fence discarded. Shadow numbers with the
    CORRECTED key (round 662): **serve 41,389, all identical, `depServeWrong` =
    0**, cold 69,790, invalidated 69 (localType 68, localType+narrowed 1 — the
    graph identity never invalidates alone). Round 661's 65,575/165 is
    SUPERSEDED: those 165 were a KEY COLLISION between three walk functions over
    11 call sites with different starting types and paths, not a dependency gap,
    so `flowWalkWithTripCheck` now takes a `kind` tag plus an `inputId` folding
    the starting type id with the path hash. **(b1) is therefore closed WITHOUT
    the read-set recorder** — the walk's dependencies ARE name-enumerable, and
    the recorder would have been solving a problem that did not exist. Prize
    correction: ~34 µs/walk × 41,389 = **~1.4 s** for the walk half (not the
    ~2.2 s rounds 660/661 reported off the coarse key), so M1's total lands at
    **~2.5 s ≈ 8–9%** with typeOfExpr's ~1.1 s.
    - [x] **(b2) DROPPED round 663 — measured, and the reachable prize is
      ~0.1 s.** The re-measure-before-investing instruction paid off. The
      expression memo's whitelist (Intrinsic/Interface/Reference) silently
      excludes ~134 k of ~618 k getTypeOfExpression calls (22%) — obj 102,102,
      unions ~29 k, Intersection 1,719 — precisely because those kinds are
      "freshly minted per call", which IS the non-canonical-output problem. Of
      those excluded calls, **62,949 are same-epoch STRUCTURAL repeats** (only
      347 genuinely differ), so interning would make them servable. But the
      by-kind split is the deciding number: **obj = 47,629 (76%)**, unions
      ≈ 12.7 k, Intersection 495. Object-type freshness is DELIBERATELY
      load-bearing (the round-435 freshObjLitRange relation machinery — the
      whitelist comment says so explicitly), so the 47.6 k / ~0.34 s half is
      not available without reopening relation semantics; the safely internable
      union+intersection part is ~13.2 k ≈ **~0.1 s**. Against the ~1.4 s live
      walk memo that is already sound at zero wrong serves, that is not worth
      the risk — so (b2) is dropped and (c) goes straight to the live memo.
      Revisit only if union interning becomes desirable for another reason
      (INV.5 canonical types would subsume it).
  - [x] **(c) LIVE — landed round 664 at −2.93% wall (−833 ms), the arc's
    first measured win.** `flowWalkWithTripCheck` serves from a memo keyed
    `(reference nodeId, fileHash, walkKind, inputId)` carrying the dependencies
    the walk read (FlowGraph instance + the Type instances bound to the
    reference's ROOT NAME in currentLocalTypes / narrowedDeclaredTypes).
    Interleaved A/B, 6 pairs, alternating order, no recompile between
    measurements: pre median 28,433 ms → post 27,600 ms, **−833 ms = −2.93%,
    post wins 5/6**. Instrumented: walks executed 111,248 → 69,859 (40,542
    served), narrowWalks 3,791 → 2,756 ms. The net is SMALLER than the ~1.4 s
    the shadow predicted because the memo pays key+dependency lookup on ALL
    walks to skip 37% — worth remembering when sizing the next memo: shadow
    "servable time" is an upper bound, not a forecast. All three round-663
    hazards handled (never store a tripped walk; `Any?` + cast sound because
    walkKind is in the key; shadow classification retained under
    `--passTiming`). Gates: suite 12,507/0; `--listAll` ×8 byte-identical on all
    eight profiles — no diagnostic moves anywhere, including no TS2563 drift;
    `--partitionCheck 2` EQUIVALENT ×8; warning-clean.
  - [x] **(d) DEAD before it was built — round 665 measured the would-save at
    30 ms, not ~1.1 s.** The measure-before-building instruction round 664 wrote
    into this item is what caught it. Instrument: decide with EXACTLY the live
    test (a confirmed shadow entry at the current epoch), decide BEFORE the core
    runs, and accumulate the core time of the OUTERMOST servable call only.
    Result: **30 ms over 71,310 outermost served calls ≈ 0.42 µs each = 0.12%**
    of a ~24 s compile — and a live memo would pay per-call overhead on ~618 k
    calls to collect it, so it must LOSE. WHY the round-660 estimate was 35×
    off: it multiplied the shadow's 149,742 hits by a MEAN call cost, but the
    servable population is the CHEAP TAIL (trivial identifiers/literals whose
    resolution is already cached) while the expensive calls — fresh minting,
    narrowing, relation work — are precisely the non-instance-stable ones the
    whitelist excludes. Applying an aggregate mean to a non-uniform population
    is the same error class as round 662's key collision. It also EXPLAINS the
    documented round-596 dead-end (a live per-node expression memo measured 1–3%
    SLOWER interleaved): that was observed but unexplained, and 30 ms is the
    explanation. Do not revive without a NEW mechanism that makes the expensive
    calls servable — canonical types (INV.5) would be that mechanism, not a
    better fence.
- [x] **(M2) SIZED round 666 and PARKED as not-locally-demonstrable — the box,
  not the design, is the binding constraint.** Probed BEFORE writing code (the
  discipline round 665 asked for), using the `--workers N` share-nothing mode
  that already exists (INV.6(6c1)). Compiler profile, 2 reps each:
  **seq 27,873 ms | w2 24,669 (−11.5%) | w4 27,905 (+0.1%)** — w2 helps, w4 is
  flat, exactly the "w4 flat" the item recorded, and STILL flat after M1's memo.
  Solving seq-vs-w2 as `seq = R + P`, `w2 = R + P/2`: only **P ≈ 6.4 s (23%)
  divides**, with **R ≈ 21.5 s (77%) non-divisible**, so the infinite-worker
  floor is ~21.5 s = a 23% best case even before contention. WHY, from the code:
  each worker does `sourceList.map { workerBinder.bind(it) }` — a FULL re-bind of
  EVERY file — and then builds a full `Checker` whose ~318 program-wide
  collectors all run; only the per-file spine is narrowed by
  `assignedFileNames`. So the duplicated-per-worker term is the whole of R, and
  Phase 1 (compute the collectors once, freeze, share) attacks at most the
  non-spine part of checker-init, measured at **~3.3 s** (checker-init 24.2 s −
  checkSpine 20.9 s + outside-pass). On 4 saturated cores that is ~2.5 s of CPU
  reclaimed but no wall win — w4 is already contention-bound, which is why it
  regresses against w2. VERDICT: the work is sound and would matter on a bigger
  machine, but on 4 cores / 7.7 GB it cannot be demonstrated, and this arc's rule
  is not to land unmeasurable perf work. What would change the verdict: a host
  with ≥8 real cores (re-run this exact probe first), or a redesign that shrinks
  R rather than dividing P — the full per-worker re-bind is the single biggest
  identified duplication and is the honest first target if M2 is revived.

**EP — Emit parity (owner-authorized 2026-07-12: "output parity, including reported errors").**
The offline v1 DoD checked emit COMPLETENESS (all files emitted, exit 0) but not
emit-BYTE parity with tsc. The round-483 emit diff (`scripts/emit-diff-tsc.sh`, xtsc
vs npm `tsc@6.0.3` on the `compiler` profile) found 8/78 byte-identical, 70/78
differing — but **none are miscompiles**; xtsc's output is semantically correct and
runnable. Three systematic families explain nearly all changed lines (sequenced
cheap-first to shrink the diff before tackling the hard cross-file one):

- [x] **EP.3 Logical/nullish-assignment downleveling** (`||=`/`&&=`/`??=` below
  ES2021). DONE round 484 (2026-07-12): `Transformer.downlevelLogicalAssignment` —
  `a ||= b` → `a || (a = b)` etc., with side-effecting property/element receivers
  captured into temps (`(_a = obj())[_b = key()] || (_a[_b] = 6)`, tsc-faithful temp
  naming). ~284 sites in the compiler profile. Gated `effectiveTarget < ES2021`;
  corpus has ZERO files exercising these operators so it's pinned by
  `LogicalAssignmentDownlevelTest` only. KNOWN RESIDUAL: a `??=` target BELOW ES2020
  keeps a native `??` (not further downleveled — ES2020 is the tested/dashboard
  target); close when a sub-ES2020 `??=` case appears.
- [x] **EP.2 CLOSED — every live sub-item landed (2a, 2b, 2d/e/f, 2g, 2h) and
  2c was SKIPPED-BY-OWNER; checkbox reconciled round 687. RE-SCOPED round 673
  by classifying the residual: it is NOT mostly formatting.** Every differing hunk in the 47 remaining files was
  classified (1,335 hunks total): **482 residual qualified access**, 173 other,
  **128 whitespace/wrap only**. So formatting is under 10% of the residual and
  the const-enum family — supposedly 96% closed — still dominates. Three
  distinct sub-targets, in value order:
  - [x] **EP.2a DONE round 674 — 128 → 1.** `emitArrayLiteral` re-emits each
    element's same-line trailing comments after `emitExpression(element)`,
    guarded by `element !is NumericLiteralNode` because a numeric literal
    already emits its own; `StringLiteralNode` does the same and was NOT
    excluded, so string-valued const enums printed their label twice (hence only
    `Extension.*` showed it). BOTH array branches carry the guard — I patched
    the MULTILINE one first and the repro did not change, which is what pointed
    at the single-line branch where the real trigger was; both fixed. An
    emitter probe proved the NODE held exactly ONE comment, localising the fault
    away from the transformer in one run. Measured: double-comments 128 → 1,
    total differing hunks 1,335 → 1,307, byte-identical files unchanged at 31/78
    (those hunks live in files that still differ for other reasons — hunk-level
    and file-level progress are different measurements). Gates: 6 pins
    (ArrayLiteralConstEnumCommentTest — they COUNT occurrences, since a
    substring check passes on doubled output, plus a negative control that a
    genuine source comment still survives); suite 12,526/0 with every JS
    baseline byte-exact despite touching the printer. RESIDUAL: 1 occurrence of
    a different shape, worth a look when convenient.
  - [ ] ~~EP.2a (original)~~ — **THE DOUBLE-COMMENT DEFECT (128 occurrences) — do this first,
    it is malformed output, not a cosmetic.** We emit
    `".jsx" /* Extension.Jsx */ /* Extension.Jsx */` where tsc emits one
    comment. REPRO IS THREE LINES (saved at `scratchpad/dblcomment`): a const
    enum imported cross-module, then `export const arr = [Ext.Cts, Ext.Cjs]`
    emits each element's label TWICE while a plain `const one = Ext.Cts` is
    correct — so the ARRAY-LITERAL element path transforms the element twice.
    Real source shape: checker.ts:2550
    `fileExtensionIsOneOf(fileName, [Extension.Cts, Extension.Cjs])`.
  - [x] **EP.2b DONE round 675 — it was HEX literals; gap 675 → 70 reads.**
    `CharacterCodes` resisted while `SymbolFlags`/`Extension` inlined from the
    same types.ts because those are decimal/string-valued and CharacterCodes is
    almost entirely hex. All THREE const-enum evaluators parsed with
    `text.toDoubleOrNull()` — decimal-only (Kotlin takes a hex FLOAT `0x1.8p3`
    but not a hex INTEGER `0x7F`), so the member became silently un-inlinable
    with no error at all. Fixed with one shared `tsNumericLiteralToDouble`
    (Types.kt: hex/binary/octal + `_` separators, rejects BigInt) wired into the
    Transformer's same-file collector, the Checker's `literalConstantValue`, and
    the Checker's `evaluateEnumInitializer` (the cross-module `enumValues`
    table). Fixing the first two left cross-module broken — the same-file repro
    went green while the direct-import one did not, which is how the third site
    surfaced. Measured: const-enum reads 17,443 → **18,048** (tsc 18,118),
    byte-identical files unchanged at 31/78. Gates: 8 pins
    (HexConstEnumInliningTest — all three paths, binary/octal, negative+zero
    guard, the parser across bases, plus BigInt/garbage negative controls since
    a wrong value would silently corrupt emitted constants); suite 12,534/0;
    `--listAll` ×8 byte-identical, which matters here because the change alters
    enum VALUES. RESIDUAL: 70 reads, plus `tracing.Phase.Bind` (const enum
    behind a namespace) and one import-elision difference.
  - [ ] ~~EP.2b (original)~~ — **The 675-read const-enum residual, dominated by
    `CharacterCodes` (638 of the qualified-access occurrences).** Why that one
    enum resists while SymbolFlags/Extension inline is the question to answer
    first — it is declared in types.ts like the others, so the difference is
    likely in how its members are reached or valued (it is large and
    char-code-valued). Also visible: `tracing.Phase.Bind` (a const enum behind
    a namespace) and one import-elision difference (`ts_js_1.version` vs
    `version` in builder.js).
  - [x] **EP.2d/e/f DONE round 677 — the const-enum family is CLOSED at 18,118
    inlined reads vs tsc's 18,118.** Three unrelated causes, each found by the
    gate: (2d) parameter DEFAULT VALUES were never transformed at ES2018+ —
    `flattenRestParameters` returned the parameters raw from its early return,
    and it owns the plain FunctionDeclaration branch, function/arrow
    expressions and constructors, so every default there skipped
    `transformExpression` wholesale (invisible to the corpus, whose emit tests
    sit mostly BELOW that threshold); (2e) a const enum nested in a NAMESPACE
    did not inline through a barrel — the star closure yields the namespace and
    the binder flags a const-enum-only namespace `ConstEnum`, so the flag test
    passed while the id-keyed `enumValues` lookup missed (`descendToConstEnum`);
    (2f) COMPUTED initializers did not fold in the same-file collector —
    `const enum Connection { Up = 1 << 0, …, UpDown = Up | Down }` in tsc's
    debug.ts, worth 25 of that file's 121 reads. 2f surfaced only because 2e did
    NOT move the count: re-running the gate with `--keep` and counting per file
    beat a confident wrong model, the third time this arc (cf. 669, 672). The
    numeric operator table now lives once in `tsFoldNumericBinary`. Gates: suite
    12,566/0/3 (+32 pins), `--listAll` ×8 unchanged (46×7, harness 94).
  - [x] **EP.2g DONE round 678 — the const-enum family is closed FOR REAL
    (true gap 34 → 0; byte-identical 31 → 32).** A const enum reached through a
    VARIABLE whose declared type is the namespace (`export let tracing: typeof
    tracingEnabled | undefined`, used as `tracing.Phase.Bind`) never inlined:
    resolution stops at the variable, tsc goes through the type.
    `namespaceBehindTypeofVariable` follows the `typeof` annotation, wired into
    both the import and direct paths; the variable keeps its runtime identity.
    **This also CORRECTS round 677's "closed at parity" claim** — the script's
    family-1 counter requires a NUMERIC value, so string-valued enums are
    invisible to it on both sides. Measure with a per-file count of all
    `/* X.Y */` comments. Suite 12,573/0/3, `--listAll` ×8 unchanged.
  - [x] **EP.2h DONE round 678 — the 32 "extra blank line" hunks were an
    ordinary printer defect, not part of the formatting subsystem.**
    `emitInnerComments` wrote a newline after a `//` comment and then the next
    comment wrote a second for its `hasPrecedingNewLine`, so consecutive line
    comments gained a blank between them (tsc keeps a comment block before an
    `else if` adjacent). Four lines, byte-identical to tsc on the repro
    including the source-has-a-blank case. Hunks **368 → 302**, add-a-line
    family **32 → 0** (also cleared 34 entangled CONTENT hunks), byte-identical
    **32 → 33**/78. Suite 12,579/0/3, all JS baselines byte-exact.
  - [x] **EP.2c SKIPPED-BY-OWNER (round 678, 2026-07-25).** Asked explicitly and
    the answer was "skip — move on": byte-parity is NOT a v1 exit criterion
    (v1 = zero FPs + all files emitted + zero crashes, all three already met),
    so a multi-round printer subsystem with no v1 impact is not where rounds
    should go. The emit arc therefore CLOSES at **33/78 byte-identical**, with
    families 1 (const enums) and 3 (logical-assign) at full parity and the two
    genuine defects found along the way (EP.2a double comment, EP.2h blank
    line) fixed. If ever revived, the residual and its shape are recorded
    below — do not re-derive it. After rounds 677–678 the residual is
    **302 hunks: 266 CONTENT** (the ternary/binary wrap-and-indent structure —
    the genuine subsystem), **35 indent-only**, **1 collapsed wrap**. Nothing
    cheap remains: the two shapes that looked separable (EP.2a's double comment,
    EP.2h's blank line) were both ordinary defects and are fixed. Classified:
    **78 same line count but different continuation INDENT DEPTH**, differing in
    BOTH directions (checker.js has us indenting 4 too many in one wrapped `&&`
    chain and 4 too few in another — no single constant fixes it); **47 where
    tsc has MORE lines** because we COLLAPSE a wrap it keeps (binder.ts's
    `const name = isComputedName ? A` / `    : B ? C` / `    : D` — tsc
    reproduces the SOURCE's line structure with `:` at line start); **7 where we
    ADD a wrap** tsc does not. All three need the emitter to model tsc's
    line-breaking AND indent decisions for wrapped binary/ternary expressions —
    source-structure preservation for expressions, analogous to the existing
    `multiLine` flags on object/array literals but much broader. SIZING: 132 of
    1,307 residual hunks (~10%), few files would flip to byte-identical on its
    own (they carry other diffs), and it is the highest corpus-regression risk
    in the codebase — this printer is pinned by all 12,534 tests. If it goes
    ahead: one rule per commit, full suite after each, gate re-run to confirm
    the diff SHRINKS.
  - [ ] ~~EP.2c (original)~~ — **Multi-line expression formatting** — the original item, now
    known to be ~128 hunks: tsc puts a wrapped ternary's `:` at LINE START
    (`? [...]` / newline / `: [`) where xtsc trails it. Highest
    corpus-regression risk (this is the printer the 12,520-test corpus pins),
    lowest count — so it goes LAST, one placement rule per commit, full suite
    after each, and re-run the gate to confirm the diff SHRINKS.
  SUPERSEDED NOTE: — **WAS UNBLOCKED round 672**
  (the emit-diff gate is live, which its own text required), and it is now the
  LARGEST remaining emit-parity family: 47/78 files still differ while the
  const-enum family is 96% closed, so most of that residual is formatting. The
  shape is visible in the utilities.js diff — tsc puts a wrapped ternary's `:`
  at LINE START (`? [...]` / newline / `: [`) where xtsc trails it at line end.
  Corpus-regression risk is real (this is the printer the 12,520-test corpus
  pins), so: one placement rule per commit, full suite after each, and re-run
  the gate to confirm the diff SHRINKS rather than merely changes.
  SUPERSEDED NOTE: — **WAS BLOCKED OFFLINE
  (round 667).** Its own text requires "the emit-diff gate in place", and that
  gate needs a reference tsc: this box has **no `node`, no `npx`, no `tsc`, and
  no `tsc.js` anywhere** (the bench-history tsc/tsgo columns come from CI, not
  locally). Do not start EP.2 here — without the gate there is no way to know
  whether a printer change moves the diff toward or away from tsc, and the
  printer is exactly what the green corpus pins. Revive when a reference tsc is
  available (see EP.0).
- [x] **EP.1 DONE round 669 — and its dashboard premise is falsified too.** The
  barrel hop now inlines: `1 /* Kind.B */`, `"x" /* Names.X */`,
  `0 /* B.Kind.A */`, with the import elided and no `__importStar` helper —
  tsc's exact shape. Cause was the same as EP.1a's: both const-enum entry points
  (`resolveConstEnumMemberAccess`, `isConstEnumAlias`) reach the enum through
  `resolveAlias`/`resolveNamePath`, which walk `symbol.exports`, and a star
  re-export never populates the barrel's own export table.
  `constEnumSymbolThroughStars` follows the target module's star closure and
  returns a symbol ONLY when it carries `SymbolFlags.ConstEnum` —
  const-enum-only by construction, so it can never feed a general type
  resolution, which is what keeps it clear of the `resolveAlias` star dead-end
  (TS2315 ×466). **MEASURED, not assumed: ZERO effect on the tsc profiles.**
  Before AND after, the emitted `compiler` dist has 1,663 numeric + 18 string
  inlines and **0 residual `ts_N.X.Y`** — identical (verified by stash/rebuild
  precisely so the 1,681 inlines would not be mis-attributed to this change). So
  EP.1's "highest impact, ~93% of the changed lines" sizing is stale exactly like
  its premise was: the tsc profile already inlined everything, and the barrel gap
  is a shape those profiles never hit. Kept because the gap was real (repro +
  pins) — general-correctness value for the post-v1 "any project" horizon, NOT a
  dashboard win. Gates: 7 pins (BarrelConstEnumInliningTest, incl. a two-barrel
  chain and two negative controls for regular enums and
  preserveConstEnums/isolatedModules); suite 12,520/0 with every JS baseline
  byte-exact; `--listAll` ×8 byte-identical.
- [x] **EP.0 DONE round 672 — the gate is LIVE (owner authorised the network
  install).** Node v24.18.0 + `typescript@6.0.3` under `build/tools`
  (gitignored; tarball, not apt — no system mutation). Run it with
  `scripts/emit-diff-tsc.sh --ref-tsc build/tools/tsc-ref/node_modules/.bin/tsc`
  (put `build/tools/node/bin` on PATH first). The reference is npm tsc 6.0.3
  against a pinned repo whose package.json says 6.0.0, so the three FAMILY
  counts are trustworthy (version-stable behaviours) while the small residual
  tail carries version noise — building tsc at the pinned commit remains the
  ideal and is still open. Its FIRST RUN earned its keep by falsifying round
  669 (see EP.1). Baseline at round 672: **31/78 byte-identical**, const-enum
  reads 17,443 vs tsc 18,118, logical-assign 15 vs 15.
  SUPERSEDED NOTE: — **WAS BLOCKED OFFLINE
  (round 667): there is no reference tsc on this box** (no node/npx/tsc/tsc.js;
  `scripts/emit-diff-tsc.sh` exists but cannot run). Unblocking needs either a
  network install of node + `typescript`, or building tsc at the pinned commit —
  both outside the offline envelope, so this is a user-gated decision, not
  agent work. Until then EP progress is limited to what the CORPUS and local
  pins can gate (EP.1/EP.1a qualify; EP.2 does not).

Session note (round 484) has the full family breakdown + methodology.

**INV — the M5 architecture-inversion arc (re-scoped 2026-07-13, owner; supersedes
M5.1–M5.7 — mapping and full design in `docs/ARCHITECTURE-RETHINK.md`, READ IT FIRST).**
Ground rules for every INV item: corpus suite green + 8-profile FP floors unchanged +
`--listAll` byte-diff empty for behavior-preserving steps + a bench TSV row per landed
item; decompose into the smallest standalone suite-gated commits; micro-opt rounds
against the flat profile are CLOSED (only an INV.0-evidenced ≥5% single lever may
interrupt the arc).

- [x] **(cta-m3e) Lift the anchor-SIMPLE restriction — reproduce the legacy
  nested-dispatch localTypes recordings spine-side (queued round 570c with the
  design from the BarrelCheckDefinedReturnTest root-cause).** The blocker: legacy
  nested-scope dispatches RECORD into the shared `currentLocalTypes` and the spine
  frames have no reproduction, so an anchored statement after a switch/if/loop
  reads an incomplete map. Design notes (verified in-code round 570): (a) the leak
  is PER-ARM — switch clauses LEAK (clause dispatch shares the map), a NARROWING-
  wrapped if-then (extractNullNarrowing non-null — a pure function of the
  condition, callable at spine time) DISCARDS its recordings on restore, a
  non-narrowed if-then Block LEAKS (the Block arm copies varTypes but NOT
  currentLocalTypes), loop/try bodies leak via the same Block arm; (b) the
  mechanism: a RECORDING-ONLY sandwich at nested VariableStatement enters within
  an active fn frame — install the frame maps, run the real
  checkVarDeclAssignability under a diagnostics mark, truncate ALL its
  diagnostics (nested statements stay legacy-owned for emission), keep the map
  writes; skip inside narrowing-discarded regions; (c) spine statement-position
  Block/clause frames already model the map SHARING — the narrowed-if discard
  needs a COPIED-map frame rule keyed on extractNullNarrowing; (d) gates: the
  barrel repro shape as a local pin (switch-clause recording feeding a later
  anchored statement's member reduction), corpus + listAll ×8. Alternative if the
  recording-only sandwich disturbs first-touch caches: migrate the nested
  dispatchers' arms themselves (bigger). DONE round 571 — the recording-only
  sandwich landed clean (one extra invariant found: TS2563 trip-state suppression
  during recordOnly, CfaTooLargeBailTest); see the session note.
- [x] **INV.0 Instrument the multiplier.** DONE round 491 (2026-07-13):
  `PassTiming.kt` + non-inline `pass(name) {}` around all 514 init dispatch calls +
  the three counters (`getTypeOfExpression` calls/distinct with per-pass attribution,
  `nodeTypes` cacheable/bypassed/hit, depth-0 flow walks at `flowWalkWithTripCheck`),
  behind the `--passTiming` CLI flag; off-mode byte-identical (listAll A/B + wall
  parity) + suite green (+7 local). The table (round-491 session note): checker-init
  = 83% of wall; top-3 passes 38.6% (property-access / assignability / call-types,
  458k of 595k getTypeOfExpression calls, 84k flow walks — 68% from
  checkPropertyAccess); 474 sub-100 ms passes sum 36.5% = the multiplication tail.
  That note's cost-ordered worklist IS the INV.4 migration order.
- [x] **INV.1 Concurrent front-end — the owner's Flow beachhead (owner-approved
  kotlinx-coroutines-core dependency, 2026-07-13).** Sub-steps: (a) DONE round 492 —
  the dep was already in commonMain; landed the `runCompilerPipeline` expect/actual
  seam (JVM `runBlocking`) + the import-graph crawl as a cold sequential Flow
  (`crawlImportGraph`, ProjectCompiler) with the load-bearing emission-order
  contract documented at the seam (suite +3, listAll A/B byte-identical); (b) DONE
  round 493 — read+decode on `Dispatchers.IO` (`pipelineIoDispatcher`
  expect/actual), extraction parse on `Dispatchers.Default`, bounded
  `flatMapMerge(16)` per frontier (`readAndScanBatch`); resolution + emission stay
  sequential per frontier so emission stays first-discovery order (the binder stays
  sequential; parser audited — no shared mutable state); (c) DONE round 493 —
  corpus green (+6 local) + 3× `--listAll` byte-identical vs the (a) binary; (d)
  DONE round 493 — interleaved A/B −0.8 s (~3%) on the compiler profile + bench
  TSV row.
- [x] **INV.1(e) Kill the double parse — reuse the crawl's parses in the core.**
  DONE round 494 (2026-07-13): `computeParserFlags` (the shared single source of
  truth for the option-derived `Parser` flags, used by the core's parse sites AND
  the crawl), `ParsedSource.preParsed` carrying `PreParsedFile(content, flags,
  sourceFile, diagnostics)`, and the core's multi-file site reusing an entry ONLY
  on an exact content+flags match (else re-parse — reuse is a pure optimization).
  Verified: suite +6 (Inv1PreParseReuseTest — sentinel-tree reuse proof + both
  mismatch gates + driver-path counters), `--listAll` byte-identical on compiler
  AND services, reuse fires 78/78 (`--passTiming` counters), interleaved wall A/B
  neutral within noise on both profiles (the parse leg is small next to the
  checker; the point is one canonical tree per file — the INV.2 enabler).
  CLAUDE.md gotcha: a new option-derived Parser argument must extend
  `ParserFlags`, never a parse site inline, or the match reuses a wrong tree.
- [x] **INV.2 Bind the world** — COMPLETE round 499 (all four sub-items landed;
  the tables' mass consumption is INV.4's migration). Decomposed round 494
  (facts verified in-code:
  `Node` is a sealed interface + ~138 data classes with single-interface supertypes
  `) : Expression/Node/TypeNode/Statement/Declaration/ClassElement`; there is NO
  generic child-walk anywhere; nodes have no parent/id fields; `Symbol.id` is a
  GLOBAL companion `nextId++` (Types.kt:116–127, the ~350-test reshuffle anchor);
  `nodeKey` is the cross-file-colliding `(pos<<32)|end`). Work the sub-items in
  order, one commit each:
  - [x] **INV.2(a) AST identity foundations.** DONE round 495 (2026-07-13):
    `NodeBase` (nodeId/parent, NOT implementing Node — preserves sealed-`when`
    exhaustiveness) + 138 supertype edits + `SourceFile.nodeCount`; canonical
    `forEachChild` (exhaustive sealed `when`) + iterative preorder
    `indexSourceFile` hooked into `Parser.parse()`. Pinned by the jvmTest
    reflection oracle (`ForEachChildOracleTest` — componentN diff over fixtures +
    all 78 real tsc sources) + `Inv2NodeIndexTest` (dense preorder / parent
    chains / copy-unindexed / 30k-chain-on-plain-thread). Suite +10 (10,228),
    `--listAll` byte-identical, wall neutral. Gotchas: NodeBase LUB trap +
    power-assert node-toString trap.
  - [x] **INV.2(b) Pilot consumer.** DONE round 496 (2026-07-13):
    `FlowGraph.flowAt` — nodeId arrays pre-computed from the finished map
    (preserves the nodeKey extent-ALIASING) + identity ownership check
    (synthesized/foreign nodes take the legacy path); 5 checker sites migrated;
    suite +3, listAll byte-identical, wall neutral. JFR verdict: getNode ≈6.7%
    of samples but nodeToFlow only ~4% of that slice (~0.3% wall) — mechanism
    validated; the mass-migration targets are the HOT maps (walk memos, INV.4
    per-node type cache), not more cold tables. `nodeTypes` rejected as pilot
    (structural cross-file keying — INV.5 territory).
  - [x] **INV.2(c) Full lexical binding, additive.** Scope symbols from a SEPARATE
    id space (never the global `nextId` sequence — the reshuffle hazard); existing
    `locals`/`globals` byte-unchanged; new tables unconsumed until INV.4.
    - (i) DONE round 497 (2026-07-13): function-like containers —
      `bindLexicalScopes` (Binder.kt) walks the whole tree iteratively after
      conventional binding, building per-nodeId `LexicalScope`s
      (`BinderResult.lexicalScopes`): SourceFile root aliases file locals,
      ModuleDeclaration aliases the merged exports (chained per dotted segment,
      the B512 rule), the 7 function-like kinds + static blocks get fresh tables
      (type params, params minus `this`, fn-expr self-name, body-top-level
      decls, `var`s hoisted from any block depth). `Symbol.scopeSymbol` mints
      ids ≤ −2; a delta-probe test pins zero global-id consumption. Suite +14
      (Inv2LexicalScopeTest), listAll byte-identical, interleaved wall
      position-balanced +0.8% (noise band).
    - (ii) DONE round 498 (2026-07-13, same session): block-scope containers —
      every Block that is not a function-like's immediate body, for/for-in/for-of
      headers, CatchClause (binds the catch variable, destructuring included),
      SwitchStatement standing in for tsc's CaseBlock (our AST has none — the
      switch EXPRESSION routes to the OUTER scope by hand) — plus class scopes
      (type params; named class-expression self-name; class decorators outer),
      interface/type-alias scopes (type params), and enum scopes (aliasing
      main-bound exports; nested enums bind scope-space members also published
      on the scope symbol's exports, gated `id ≤ −2` so main symbols stay
      untouched). Design dividend: the phase-(i) `isDirectBodyChild` gates for
      block-scoped declarations DISSOLVE into `scope.existing == null` (every
      fresh scope IS the correct nearest block-scope container); `var` gains the
      real `varHoistTarget` walk-up. Block-nested function declarations use
      strict/module semantics (bind to the block). Suite +6 (20 total in
      Inv2LexicalScopeTest — the phase-(i) negative controls flipped to
      positive location asserts), listAll byte-identical, interleaved wall ×6
      both orders neutral.
  - [x] **INV.2(d) B83.5 dissolution pilots.** DONE round 499 (2026-07-13): the
    canonical site — `checkPropertyAccessInStatement`'s ClassDeclaration branch —
    now resolves a block-scoped class via `lexicalScopeSymbol` (parent-chain walk
    over `currentLexicalScopes`, set per file in `checkPropertyAccess`; legacy
    transient synthesis kept as the unindexed-tree fallback). Fidelity proven:
    suite green, listAll byte-identical on compiler AND services; and the pilot
    FIXES a real FP — a block-level `interface B` + `class B` merge now
    contributes interface members to `this` (the transient class-only symbol
    could not see them; measured: the pre-pilot checker emitted a false TS2339).
    Candidate analysis: the other two `Symbol(SymbolFlags.Class, …)` syntheses
    are NOT B83.5 scope-binding shapes and stay — the B511 clodule recovery
    (the class symbol is main-bound then OVERWRITTEN by last-wins, so it is in
    neither table) and the classExpressionAssignment display synthesis (a
    ClassExpression is never a scope binding). Mass consumption of the tables
    (the ~59 synthesis sites, `buildNestedFunctionMap`, the per-pass scope
    machinery) is INV.4's migration proper.
- [x] **INV.3 Per-file scoping — ARC COMPLETE round 513** ((a)-(d) all landed; checkbox reconciled round 612) — decomposed round 500 (facts verified in-code:
  `perFileScope` EXISTS and is already consumed at 4 sites — the 17.32b–e flips
  (TS2663-vs-TS2301, TS2552 candidate pool, resolveExpressionToSymbol, file-root
  TS2304) — so the earlier "never consumed" note was stale; the remaining
  migration surface is ~400 keyed `globals` consults; import aliases free-ride on
  the conflation because the general `resolveAlias` cannot follow ESM-`.js`
  specifiers / `export *` barrels / NamespaceImports — the FLOW-ONLY resolvers
  can, and the general-fallback variant measured a TS2315×466 flood at round
  409). End state: module files resolve own-locals + imports + true globals;
  the `mergeSymbolTable` conflation is retired for module files; the conflation
  ecology is deleted. Also lays the cross-file value-resolution groundwork EP.1
  needs. Work the sub-items in order, one commit each:
  - [x] **INV.3(a) Instrument the conflation dependency.** DONE round 500
    (2026-07-13): `globals` constructed as `InstrumentedSymbolTable` under
    `--passTiming` (plain map otherwise — zero added code on the hottest map);
    every keyed lookup classified against the per-file visibility model
    (TRUE_GLOBAL / SHARED / OWN_LOCAL / CONFLATED / UNSCOPED — see
    `GlobalsLookupClass`) by a classifier installed after init step 1b, with
    per-name + per-pass conflated/unscoped tables in the dump. Measured
    (compiler / services profiles): 2.71M / 4.92M keyed lookups — 71% / 79%
    MISSES (globals probed as a maybe-fallback everywhere), ownLocal
    530k/703k (flips to per-file trivially), CONFLATED 157k/217k concentrated
    in 608/845 names (almost all `types.ts` type names reached through barrel
    imports; services adds the round-442 value-space leaks `parent`/`error`)
    and 14–15 passes with the top 3 = 95–96% of conflated traffic = INV.0's
    top-3 wall passes (checkPropertyAccess / checkCallExpressionTypes /
    checkTypeAssignability), SHARED only 2.9k/4.0k (the chimera ecology's
    cost is per-lookup bail checks, not hit volume), unscoped 71.8k/97.1k
    (checkUnresolvedNames + outside-dispatch). Worklist: (b)'s primitive must
    resolve barrel-imported TYPE names; (c) starts at the three hot passes.
    Suite +5 (Inv3GlobalsLookupTest), `--listAll` byte-identical (off-mode),
    bench row in band.
  - [x] **INV.3(b) Per-file resolution primitive.** COMPLETE round 502:
    - (i) DONE round 501 (2026-07-13): `lookupPerFile(fileName, name)`
      (internal, unconsumed by checker paths) — perFileScope lookup with an
      ImportSpecifier-alias local resolved onward through
      `resolveImportedSymbolGeneral` (the kind-AGNOSTIC generalization of the
      flow-only resolver skeleton: ESM-`.js` strip + `export *` barrels +
      renamed re-exports via the star walk's NamedExports arm + re-import
      hops; memoized `importedSymbolGeneralCache`; ADDITIVE — the three
      kind-specific legacy variants stay untouched, their per-decl
      kind-filter-then-continue semantics differ; never wired into
      `resolveAlias` per the round-409 flood gotcha). KEY TRAP hit and
      pinned: mergeSymbolTable FLAG pollution means an Alias flag cannot
      identify an import alias — a barrel-imported name's TARGET symbol
      acquires the Alias bit from the importing file's merge, so the hop
      test must be declaration-based (`isImportBindingDecl` — the
      isValueExport gotcha applied to alias hopping). Degradations
      documented in the KDoc: unresolvable import / default-import /
      `import * as ns` / `import =` aliases return the alias symbol itself
      (callers keep their existing handling — extend when a (c) flip needs
      them); null strictly means "no per-file meaning" (the conflation
      leak). Pinned by Inv3PerFileLookupTest (direct
      `Checker(options, binderResults)` construction — a first for local
      tests — asserting symbol IDENTITY with the declaring file's binder
      locals across direct-`.js`/barrel/renamed-re-export/own-local/
      script-global/lib shapes + the foreign-module-local null and
      alias-degradation negative controls).
    - (ii) DONE round 502 (2026-07-13): pilot consumer — the TS2315/TS2346
      heritage-base "not generic" gate (`checkTypeArgumentConstraints`, the
      smallest nonzero pass in the (a) conflated-by-pass table with DIRECT
      pass-local consults) resolves through the NEW
      `globalsForFile(fileName, name)`, THE (c) flip shape: return the
      merged-globals INSTANCE whenever the name has a per-file meaning (a
      non-module-only name, or a module-only name the file declares/imports
      — probed via `lookupPerFile`; substituting the primitive's return
      directly would change symbol identity for lib/script names), null
      exactly where the legacy consult leaked a foreign module file's local
      (suppression-only at this site: real tsc never emits TS2315 for an
      unresolvable base). Supporting infra always-on: init 1b2 became
      `computePerFileVisibility` — publishes `moduleOnlyGlobalNames`
      (module-file local names minus lib/script/augmentation-visible), the
      INV.3(a) classifier installs on top of the same sets. Both mirrored
      consult sites flipped together (kept-in-sync contract); the conflated
      branch never touches `globals`, so the `--passTiming` conflated
      tables keep measuring only UN-migrated traffic. MEASUREMENT LESSON
      for (c): the post-flip instrumented run shows the pass STILL at 11
      conflated with the total lookup count EXACTLY unchanged (2,711,601)
      — the pass's conflated traffic comes from DEEPER shared machinery
      (`checkConstraintsForTypeArgs` → `getTypeFromTypeNode`), not the
      direct pass-local consults, which measured ZERO conflated hits on
      the compiler profile. Per-PASS attribution ≠ per-SITE: a hot-pass
      (c) flip needs per-site reasoning about which consults inside the
      pass actually carry the conflated traffic. Suite +7
      (Inv3GlobalsForFileTest — both leak-kill tests FAIL on the pre-flip
      checker, verified via stash; five preservation controls pass on
      both); `--listAll` byte-identical on compiler AND services.
  - [x] **INV.3(c) Flip resolution families onto the primitive** — COMPLETE
    round 509 (all four sub-items landed; conflated 157k → 917, the residue
    being the INV.3(d)-scoped shadow ecology). Decomposed
    round 503 from a MEASURED per-site attribution (a temporary 1:200
    stack-sampling probe on the classifier's CONFLATED branch, ~790 samples,
    probe reverted — evidence in the round-503 session note). The guessed
    site list above was WRONG: `getTypeFromTypeReference`'s globals fallback
    measured ZERO conflated hits and `resolveTypeNameToSymbol`'s Identifier
    entry only ~1.2% — the actual distribution:
    **~82% is ONE family, the enum-discriminant/kind-domain narrowing
    machinery** (`kindDomainKeysFromTypeNode` → `enumSwitchKeysFromTypeNode` /
    `enumMemberKeysOfTypeNode` / `kindDomainTypeDeclSymbol` /
    `resolveEnumSymbolForDiscriminant`, reached from `narrowByCallPredicate`
    via `applyConditionNarrowing`, plus smaller entries from
    `filterUnionByEnumDiscriminant`/`resolveCallOverload`), which resolves
    type names read from FOREIGN AST nodes — types.ts's union-member `.kind`
    annotations — while `currentFileLocals` points at the CHECKING file
    (exactly the top conflated names: JSDocFunctionType / FunctionTypeNode /
    ConstructorTypeNode / MappedTypeNode / ConditionalTypeNode). The
    per-file-correct key there is the NODE'S OWNING FILE (tsc semantics: a
    types.ts annotation resolves in types.ts's scope), NOT
    `currentCheckFileName` — a naive `globalsForFile(currentCheckFileName,…)`
    flip would silently kill narrowing in files that don't import the name.
    The rest: `identifier.fallback` ~3.8k + `propAccess.objExpr` ~3k (tagged
    counts), `checkPrivateMemberAccess`, `getTypeOfIdentifier ←
    isCalleeResolvable`, `resolveFlowCalleeDecl ←
    flowCalleeMayHaveAssertEffects`, `computeRawTypeOfPropertyAccess ←
    getCalleeType`, `typeNodeDefinitelyNonNullish`, `pmrCheckAccess`,
    `mam.objectExpr`/`mam.recvSym` (~63 each). Sub-items, one commit each,
    every flip suite+listAll-gated on compiler AND services:
    - (i) DONE round 504 (2026-07-13): the node-keyed resolution primitive —
      `owningSourceFile(node)` (NodeWalk.kt: parent-chain walk to the
      SourceFile, null for unindexed `copy()`/synthesized/detached nodes,
      defensive hop bound) + `lookupPerFileForNode(node, name)` =
      `globalsForFile(owner.fileName, name)` with legacy-merged-consult
      degradation for ownerless nodes. Additive/unconsumed; pinned by
      Inv3NodeKeyedLookupTest (direct construction — a foreign-node
      annotation resolves under its OWNING file's visibility to the same
      merged instance; an owner without the name yields null (the leak);
      an importing owner keeps resolving; an unindexed copy degrades to
      legacy; lib names never nulled).
    - (ii) DONE round 505 (2026-07-13): the kind-domain/enum-discriminant
      family (~82% of conflated traffic) flipped onto the node-keyed
      primitive — `resolveEnumSymbolForDiscriminant`/`kindDomainTypeDeclSymbol`
      thread a `keyNode` (all 5 call sites), and the alias fallbacks in
      `enumSwitchKeysFromTypeNode`/`enumMemberKeysOfTypeNode` (incl. the
      round-477 import-alias fallback) consult `lookupPerFileForNode(node,
      name)`; `currentFileLocals` stays the first consult everywhere.
      Companion: `globalsForFile`'s proven-visible branch reads UNCLASSIFIED
      (`InstrumentedSymbolTable.getUnclassified`) under `--passTiming`, so a
      legitimate foreign-node hit — CONFLATED against the CHECKING file's
      locals — no longer pollutes the migration tables. Suite +5
      (Inv3KindDomainNodeKeyTest — leak-kill FAILS pre-flip via stash;
      4 preservation controls pass both sides); listAll byte-identical on
      compiler AND services.
    - (iii) **Flip the current-file-keyed value/callee sites** — these read
      names from the CURRENT file's own AST; node-keying by the name's
      IDENTIFIER node is the uniform shape (equals current-file keying for
      own nodes); suppression-only where the name classifies conflated.
      Phase 1 DONE round 506 (2026-07-13): the protected-member cluster
      (pw/pmr/pm, TS2445/TS2446 — `pmrCheckAccess`'s static consult, the
      ctor-init consult, and the `pwResolveClass`/`pmrResolveClass` funnels
      every heritage walker feeds) keys by the name Identifier via
      `lookupPerFileForNode` — the heritage walkers wrap a REAL indexed
      Identifier in a synthesized TypeReference, so keying by `typeName`
      (never the wrapper) needs zero signature changes; a fully-synthesized
      identifier (pmrLocalClass's from-text one) degrades to the legacy
      consult inside the primitive. Suite +5 (Inv3ProtectedNodeKeyTest —
      both leak-kill tests FAIL pre-flip via stash: the leaked resolution
      manufactured bogus TS2445 about a class the file never imports);
      listAll byte-identical on compiler AND services. Phase 2 DONE round
      507 (2026-07-13): the bare-Identifier VALUE/receiver/callee cluster —
      checkPrivateMemberAccess, getCalleeType's Identifier branch,
      resolveFlowCalleeDecl (+ the extracted currentFileNestedPredicateDecl
      preserving round-471 narrowing from the direct==null fallback too),
      resolveNamespaceMemberFnDecl, the three ns-fallback receiver
      resolvers (computeRawTypeOfPropertyAccess /
      resolvePropertyAccessToSymbol / propertyAccessChainIsNamespaceQualified),
      isCalleeResolvable, checkPropertyAccessAssignment's ns base, the two
      mam receiver consults, and the protected-ctor heritage walks
      (findEffectiveConstructorVisibility/classExtendsOrIs) — all keyed by
      the name's own Identifier node. Conflated 20,941 → 10,034 (−52%);
      suite +9 (Inv3ValueCalleeNodeKeyTest — 4 leak-kills FAIL pre-flip via
      stash); listAll byte-identical on compiler AND services; bench in
      band. Phase 3 DONE round 507b (2026-07-13) — (iii) COMPLETE:
      `getTypeOfIdentifier`'s globals fallback node-keyed (the round-442
      by-NAME dead-end does NOT reproduce per-FILE — imports resolve
      through the visibility probe to the same merged instance; pinned by
      Inv3IdentifierTypingNodeKeyTest incl. the import-driven
      initializer-inference control from the round-442 regression family),
      plus a fast path in `lookupPerFileForNode` (non-module-only names
      skip the parent walk — the fallback is ~2M calls/compile). Conflated
      10,034 → 6,165 (cumulative 20,941 → 6,165, −71%); `factory` gone;
      checkImplicitAnyParameters 2,608 → 171, checkUncalledFunctions 968 →
      189. Suite green 10,298 → 10,302 (+4); listAll byte-identical on
      compiler AND services; bench +4.0% single-run = the documented
      box-drift band (~126k parent walks ≈ negligible by construction).
      Residue ~6.2k = the (iv) type-position tail (types.ts type names
      reached via typeNodeDefinitelyNonNullish / resolveTypeNameToSymbol /
      getTypeFromBaseTypeExpression) + ~500 value-name lookups in the
      shadow-detection ecology (registerNestedGlobalShadow*/
      applyBodyLocalShadowing/shadowNestedFunctionNames ask "does a merged
      global collide" — they die with INV.3(d), do not flip them) + tiny
      tail sites (emitTs2345ForBareTpArgToConstrainedTpParam,
      getOverloadImplementationRelated, calleeReturnAnnotationForImplicitAny
      — fold into (iv)'s re-measure).
    - (iv) **Flip the type-position tail**. Leg 1 DONE round 508 (2026-07-13):
      `resolveTypeNameToSymbol`'s Identifier branch + `typeNodeDefinitelyNonNullish`'s
      two fallbacks flipped JOINTLY per the round-507c order constraint, with
      the two call-site trailing `?: globals[name]` fallbacks
      (`getTypeFromTypeReference`, `checkConstraintsInTypeNode`'s TS2315
      emitter) gated to QualifiedName — for Identifier names they were
      byte-redundant pre-flip and would silently RE-LEAK the node-keyed null
      post-flip (the trap now in the CLAUDE.md INV.3(c) entry). The full
      suite caught a REAL visibility gap the flip exposed:
      `lookupPerFileForNode` now grants a node inside a `declare module
      "<relative-spec>"` AUGMENTATION block the augmented module's direct
      named exports (the round-443 rule; the innermost string-named
      ModuleDeclaration is captured during the parent walk, unclassified
      under --passTiming) — without it the flip nulled `UnionType` inside
      services-style `declare module "./types.js"` blocks and this-predicate
      narrowing died (ThisPredicateNarrowingTest's augmentation pin).
      Test-design lesson: the ADDITIVE leak-kill direction is SHADOWED by
      any-degradation (an unresolvable callee annotation degrades the
      assigned reference to `any` — proven with a never-declared `Zorp`
      control — masking the TS18048/TS2322 consumers), so the flow
      observable uses the SUPPRESSION direction: a foreign UNIMPORTED
      NULLABLE alias return-annotation pre-flip types the reference as the
      leaked union and manufactures TS18048 on a closure-captured read;
      post-flip it degrades to any and the leaked TS18048 dies (tsc-faithful).
      Suite +9 (Inv3TypePositionNodeKeyTest — 3 leak-kills FAIL pre-flip via
      stash: the flow TS18048, annotation-position TS2322, TS2315; 6
      preservation controls pass both sides); `--listAll` byte-identical on
      compiler AND services. Leg 2 DONE round 509 (2026-07-13) — **(iv) and
      the whole (c) migration COMPLETE**: getTypeFromBaseTypeExpression's
      Identifier fallback (PropertyAccess last-segment fallback kept legacy —
      the QualifiedName convention), emitTs2345ForBareTpArgToConstrainedTpParam,
      getOverloadImplementationRelated (keyed by the overload DECL's own name
      node — a nested/foreign collision no longer hands TS2793 a wrong-file
      impl pointer), calleeReturnAnnotationForImplicitAny (the
      uniqueFunctionDeclByName fallback still covers program-wide-unique
      names). Suite +5 (Inv3TypePositionLeg2NodeKeyTest — 2 leak-kills FAIL
      pre-flip via stash: a leaked foreign heritage base grafting members
      manufactured TS2741 on `const d: D = {}`, a leaked foreign
      constrained-TP callee manufactured TS2345; 3 preservation controls);
      listAll byte-identical on compiler AND services. RE-MEASURE (compiler
      profile): CONFLATED 6,165 → **917** (−85%; from the pre-migration 157k
      → −99.4%), 97 names / 9 passes, top 318/284/273 — the residue is the
      deliberately-legacy shadow-detection ecology (`diag`/`clone`/`map`/
      `factory` collision questions) + tiny tails, i.e. INV.3(d)'s scope.
      INV.3(d) is UNLOCKED.
  - [x] **INV.3(d) Retire the merge + delete the ecology — COMPLETE round 513** (checkbox reconciled round 612; the body below records the full campaign). Stop merging
    module-file locals into `globals`; delete `moduleFileLocalVarNames`,
    `conflatedTypeAliasFiles`, `conflatedInterfaceFiles`,
    `conflatedEnumFileSubsets`, the per-file interface views, and the chimera
    bails — walker-by-walker, each deletion suite- and listAll-gated (each
    removes hot-path work from `checkMemberAccessMissing`).
    **THE RETIRE IS MERGED TO MAIN (round 512): sub-items (i)–(iv) all DONE —
    suite fully green (10,346/0/3) and ALL 8 profiles byte-identical to the
    pre-retire baselines. Remaining: (v) the ecology deletions (the round-473
    Identifier dispatch is already deleted as the (iv) residual fix — its
    removal is what restored the server/harness baselines).** What the branch
    proved (measured round 510): the retire
    must be STAGED BY NAME CLASS — retire only MODULE-ONLY names; SHARED names
    (module local colliding with a lib/script global: `Symbol`/`Node`/
    `Performance` riding the lib names) must KEEP merging until every lib-name
    consumer resolves per-file (the naive full retire measured 861 compiler
    FPs, the module-only cut 34, each traced to an unflipped consult by the
    classifier-MISS stack-probe technique). Sub-items to finish it, in order:
    - (i) DONE round 511 (2026-07-14): the ambiguous-constrained→foreign leg
      REVERTED (declaration-IDENTITY leg kept) — flipped the whole TP family
      (17 tests: the 8 corpus TP pins + 3 local negative controls +
      tsxTypeArgumentPartialDefinitionStillErrors ×2 + WhileTrueDefiniteAssignTest
      ×4, the last two collateral of the over-aggressive classification);
      checker.ts:7358 re-solved at the INFERENCE side —
      `tryInferSingleTypeParamFromArgs` soft-skips a CallExpression arg whose
      type still carries a TypeParam at forReturnType sites (tpSawAnyArg →
      anyType, the pre-retire any-degradation behavior; round-468
      CallExpression gate keeps own-TP identifier args anchoring). Pinned by
      ForeignTpInferenceSoftSkipTest (6); compiler+services listAll
      byte-identical.
    - (ii) DONE round 512 — all 14 corpus multi-file failures fixed (the last 6:
      union-discriminant objlit drill node-keyed; ns-import static TS2339 +
      the dir-relative resolveAlias legs; TS2749 file-keyed with the
      typeSideImportFallback gate; the B585 contextual-display hops; the JSDoc
      ImportType own-specifier resolution; the TS2415 imported-base flip).
      Round-511 record follows:
      heritage/implements walkers node-keyed (interfaceDeclaration3,
      interfaceImplementation6 — incl. the B563 ownership-gate mirror that
      killed the double TS2420), checkConstraintsForTypeArgs keyNode +
      ImportType presetSymbol (divergentAccessorsTypes6,
      unmetTypeConstraintInImportCall), checkTypeNameResolved's leftSym →
      globalsForFile (augmentExportEquals1/2 + decoratorMetadataWithImport…7),
      the mam type-only-winner + namespace-import value-side bail
      (noCrashOnImportShadowing), **and the session's critical find: the
      import hop (`resolveImportedSymbolGeneral`) lacked the DIR-RELATIVE
      resolver leg, so path-shaped extensionless imports (`/proj/src/f1.ts` →
      `./lib`) never hopped and EVERY import-mediated type died on real
      on-disk projects — masked pre-retire by the merge, invisible to the
      `.js`-specifier tsc profiles; found via the EnclosingImportIndexTest
      pins + a MainKt scratch-repro matrix.** REMAINING 6 (per-test roots,
      each needs a probe dig): exportStarFromEmptyModule (X.A.r static
      TS2339 through a local-shadowed star chain),
      allowImportClausesToMergeWithTypes (TS2749 default-import-of-value used
      as type), allowJscheckJsTypeParameterNoCrash (display regression:
      `WatchHandler<any>` unfolds to the fn-type — alias display lost),
      checkJsdocTypeTagOnExportAssignment2 (JS `@type import("./a").Foo`
      excess-prop TS2353 — the JSDoc path's cross-file resolution),
      declarationEmitPrivateSymbolCausesVarDeclarationEmit2 (TS2415 with
      cross-file computed `[x]` private members),
      indirectDiscriminantAndExcessProperty (single-file module: TS2322
      member-vs-discriminant `"foo" | "bar"` — the objlit-member drill's
      resolution; NOT tryEmitObjectVsNamedUnionArg, whose anonymous
      constituents defer to the discriminant walker).
    - (iii) DONE round 512 — the last 4 were 2 real resolver gaps (the
      `export * as` arm in namespaceAliasMemberSymbol; the ns-member objlit ctx
      flips) + 2 pre-retire ACCIDENTAL PASSES fixed tsc-faithfully (all-missing
      all-anonymous union TS2339; primitive-vs-plain-object-bag TS2345).
      Round-511 record follows:
      (Inv3NodeKeyedLookupTest's unindexed-copy degradation → null for
      module-only names; Inv3GlobalsLookupTest's leak assertions inverted to
      the emptied-worklist victory condition); 3 more of the original 9
      flipped as REAL code fixes (EnclosingImportIndexTest ×2 +
      Inv3NodeKeyedLookupTest imports-keep-resolving via the dir-relative hop
      leg; ExtendsImplementsSameClassTest + NamespaceImportQualifiedTypeTest
      via the (ii) walker flips). REMAINING 4, all look like REAL
      suppressions to dig (scratch repros r7/r8 reproduce two):
      ConflatedTypeAliasLeakTest ×2 (own-file `type X` union TS2339 /
      own-file TS2345 both silent — receiver/param resolution in the alias's
      own file returns something unexpected post-retire),
      NamespaceQualifiedBaseInheritanceTest (export-star-as barrel base →
      TS2339 FP returned), BuilderChainAndNsMemberCtxTest (ns-member objlit
      contextual params → TS7006 FP returned).
    - (iv) DONE round 512 — all three residual families closed: deprecate.ts
      `compareTo` (an anyType shadow now BAILS mam instead of falling through
      to the outer import); session.ts protocol.Diagnostic (the round-473
      Identifier DISPATCH into conflatedPerFileInterfaceType REMOVED — the
      first (v) deletion, see the session note); fourslashImpl `'array'`
      (namedUnionMemberCouldAcceptArray hops import aliases). **Full 8-profile
      listAll A/B vs pre-retire main: ALL BYTE-IDENTICAL**; suite fully green;
      branch merged to main.
    - (v) DONE round 513 — ALL FOUR deletion groups landed (each suite- and
      8-profile-listAll-gated byte-identical): `moduleFileLocalVarNames` (+2
      masked narrowing gaps fixed), `conflatedTypeAliasFiles` (2 helpers
      re-keyed onto non-conflation conditions), `conflatedInterfaceFiles`
      objlit/relation chimera bails + TS2430/heritage view arms, and the
      per-file-view core (`conflatedPerFileInterfaceType`/`perFileInterfaceType`/
      owner-context threading) + `conflatedEnumFileSubsets`. SURVIVORS
      (deliberate): `moduleInterfaceNames`+`isLibPhantomMemberOfModuleInterface`
      (lib+module SHARED merges persist), `interfaceDeclsForCurrentFileView`
      discriminant reading, the re-keyed augmentation/alias-union bridges, the
      `A && objlit` falsy-remainder emitter, and the `nodeTypes` bypass re-keyed
      as `isPerFileDependentRefNode` on `multiFileModuleTypeNames` (the
      structural cache's cross-file position collisions are NOT
      conflation-specific — see the session note). **INV.3(d) is COMPLETE; the
      INV.3 arc is COMPLETE. NEXT: INV.4.**
- [x] **INV.4 Single-pass check spine — CLOSED round 599** (see the round-599 note: migration + retirements banked −13% wall + ONE authoritative walk; the (f) memo/fold designs are measured dead-ends until INV.5 canonical types). `checkSourceFileOnce` per-node dispatch;
  migrate walker families in INV.0's cost order — every migration deletes a full-tree
  pass and its private scope machinery. Once ONE authoritative walk state exists, land
  the two things that are unsound today: a per-node expression-type cache, and flow
  narrowing folded into reference typing once (collapsing the rounds-408–479
  per-consumer wiring). Decomposed round 514. Cross-cutting rules for every
  sub-item: (1) the spine is dispatched as ONE `pass("checkSpine")` at a FIXED
  init position (the earliest migrated pass's slot); passes migrating in from
  LATER positions move their emissions earlier in insertion order — the stable
  diagnostic sort (start→length→code→message) hides all but exact 4-tuple ties,
  and the per-migration corpus + listAll gates decide each case. (2) A spine
  handler sees ALL nodes: a hand-walk's accidental under-visits (arrow bodies,
  class/function expressions, initializers) become visits — per migrated pass,
  decide widen-vs-gate by the CLAUDE.md emission-direction rule (a
  position-independent tsc grammar rule widens faithfully; an FP-firewalled
  heuristic walker must reproduce its descent gates via parent-chain checks).
  (3) Every migrated pass with no local pins gets them BEFORE migration (the
  corpus pins emit bytes, not checker diagnostics — `.errors.txt` is disabled,
  so local tests are the primary under-emission gate). (4) Suite green +
  8-profile listAll + bench row per landed commit.
  - [x] **INV.4(a) Spine skeleton + pilot migration.** DONE round 514
    (2026-07-14): `checkSpine()` at the old checkAccessorModifierTarget slot —
    iterative enter/leave preorder walk per file (explicit parallel stacks;
    10k-chain pinned), per-file spine context fields declared BEFORE `init`,
    per-node `when` dispatch in `spineEnterNode`/`spineLeaveNode` (tsc
    checkSourceElement-style; plain private handler funs), active-handler
    gate skips the walk when every migrated handler is off (the profiles
    target ES2020 → pilot handler off → bench-neutral by construction).
    Pilot: TS18045 migrated — threaded `inAmbient` became an INV.2
    parent-chain ancestry check ([spineInAmbientContext]); the 78-line
    private walk deleted; coverage widened faithfully to class expressions /
    arrow bodies (position-independent grammar rule; both directions pinned).
    Suite +9 (Inv4SpineAccessorModifierTest), listAll byte-identical on
    compiler AND services (46/46; header-only argv difference), bench row in
    band. The leave hook is the scope-pop extension point — its pairing gets
    its first real pin when the first stateful migration lands.
  - [x] **INV.4(b) Tail-pass batches.** Migrate the 474-pass sub-100 ms tail
    (7.3 s = 36.5% of checker-init, round-491 table) in batches of ~5–15 per
    commit, most-mechanical first (zero-typing grammar/AST-shape walkers with
    per-file prepasses moving to a file-enter hook); each batch deletes its
    walks. Re-measure `--passTiming` every few batches; stop batching a shape
    that resists (stateful scope machinery) and queue it for (c)/(d) instead.
    Batch 1 DONE round 514 (2026-07-14): checkInvalidGlobalAugmentations
    (TS2669/TS2670) + checkReservedWordInterfaceParams (TS7051/TS7006) —
    both old walks descended ONLY through module bodies, so reachability is
    reproduced as a module-chain parent-walk gate (the template for
    module-scope-only walkers); the reserved-params handler deliberately does
    NOT widen to function/class-nested interfaces (a behavior change to make
    on a signal, not as a migration side effect); currentFileLocals is now
    set per file in checkSpine's loop (isTypeLikeParamName consults it); the
    spine walk is ALWAYS-ON from this batch (the TS2669 handler is
    unconditional and covers .d.ts — the .d.ts fast-skip lifted into
    per-handler gates). Suite +10 (Inv4SpineBatch1Test), listAll
    byte-identical on compiler AND services. WALK-COST measurement
    (interleaved 3-pair A/B vs the pre-batch binary — the round-493 rule): the
    first-cut enter/leave walk cost a REAL +1.0 s median on the compiler
    profile (boxing ArrayList<Boolean> phase stack + a leave frame per LEAF);
    fixed same commit — primitive BooleanArray phase stack + leaf shortcut
    (leave fires inline for childless nodes, no re-push) → re-interleaved
    NEUTRAL within noise (pair deltas +861/−1063/+574 ms, mean +124 ms).
    Per-frame costs are the whole game in a walk that visits every node —
    the walk KDoc carries the warning. Batch 2 DONE round 515 (2026-07-14):
    checkNonArrayRestParameters (TS2370 — the two differently-shaped walks
    became ONE Parameter-enter handler dispatching on the parameter's PARENT
    kind: value-position parents get the keyword rule, type-position parents
    the optional-rest rule; both widened faithfully — position-independent
    per-signature grammar) + checkIteratorMethodExtraParameters
    (TS2488/TS2504) + checkAsyncYieldStarThenable (TS1320) — the prepass
    pair became spine COLLECTION (VariableDeclaration enter, VariableStatement
    parent gate) plus BUFFERED iteration positions/yield* candidates resolved
    at file END (spineResolveDeferredIterationChecks — preserves the old
    prepasses' use-before-decl semantics with NO extra walk; the template for
    collect-then-scan walkers). TS1320's statement-level-only reachability
    widened to a nearest-function-ancestor async-generator gate. 16 walker
    funs deleted (~460 lines), 3 init slots removed. Suite +21
    (Inv4SpineBatch2Test), listAll error lines identical on ALL 8 profiles,
    wall in band. Batch 3 DONE same round: checkForOfNonIterable (TS2495 —
    the per-run lib-exclusion gate became spineForOfNonIterableActive; the
    verdict helper checkForOfExprNonIterable retained unchanged) +
    checkAbstractAccessorReturnTypes (TS7033 — GetAccessor-enter handler;
    the ClassDeclaration-parent gate keeps class-EXPRESSION members
    unchecked; the `.js`/`.jsx` skip is deliberately NOT spineIsJsLike —
    the old pass ran on .mjs/.cjs); 6 more walker funs + the round-514
    orphaned TS18045 KDoc deleted. Suite +9 (Inv4SpineBatch3Test), listAll
    identical on ALL 8 profiles. Batch 4 DONE round 516 (2026-07-14):
    checkSetterParameterCount (TS1054/TS1049/TS1095 as Get/SetAccessor-enter
    handlers — TS1054/TS1049 widened faithfully to class expressions +
    interface/type-literal accessors, TS1095 widened exactly to class
    expressions (the objlit/interface parses never store a setter return
    annotation); TS2808 as a ClassDeclaration-enter pair check KEPT at the
    old ClassDeclaration-only gate) + checkRestParameterLast (TS1014 — a
    second Parameter-enter handler; widened to FunctionType/ConstructorType/
    type-literal methods per tsc checkGrammarParameterList; GetAccessor
    parents stay excluded) + checkMultipleDefaults (TS1113 —
    SwitchStatement-enter, one-per-switch latch preserved) +
    checkInterfacePropertyInitializers (TS1246 — InterfaceDeclaration-enter;
    the parser owns the common shape). 17 walker funs (~733 lines) deleted,
    4 init slots removed. Suite +22 (Inv4SpineBatch4Test), listAll identical
    on ALL 8 profiles, bench in band. Batch 5 DONE round 516 (same session):
    checkConstWithoutInitializer (TS1155) + checkDestructuringWithoutInitializer
    (TS1182/TS7031) as VariableDeclaration-enter handlers — shared owner gate
    (VariableStatement non-declare/non-ambient via spineInDeclareModuleChain,
    the parent-walk equivalent of the old isAmbient threading which reset at
    every non-module descent; or a for(;;) initializer; for-in/for-of
    excluded); emitTs1182IfMissingInit retained; for-of/for-in BODIES are a
    faithful widening (the old walks had no ForOf/ForIn case). Plus
    checkComputedPropertyNameLiteral (TS1166/TS1169 by PropertyDeclaration
    parent kind; TypeLiteral stays unchecked) + spineCheckClassExprComputedProps
    (the TS1206 legacy-decorator short-circuit, position-GATED to the old
    expression-statement-only reach — pinned negative). 7 walker funs
    (~318 lines) deleted, 3 init slots removed. Suite +16
    (Inv4SpineBatch5Test), listAll identical on ALL 8 profiles, bench in
    band. Batch 6 DONE round 517 (2026-07-14): checkDuplicateModifiers
    (TS1030/TS1029/TS1044 — statement-kind handlers over 10 node kinds; the
    threaded inAmbientContext + atTopLevel pair became ONE parent-chain walk,
    `spineDupModContext`, where the INNERMOST flag-deciding ancestor wins per
    flag — fn/member bodies reset ambient, Block decides atTopLevel=false,
    ModuleBlock resets it true — and any non-descended ancestor kind returns
    null = the old no-visit; checkModifiers/checkInvalidImportEqualsModifiers
    retained as FP-firewalled text heuristics, reach NOT widened per B69.6) +
    checkAmbientInitializers (TS1039/TS1254/TS1066/TS1031 — Enum/
    VariableStatement/ClassDeclaration enter handlers over
    `spineAmbientInitContext`; .d.ts top-level-ambient preserved at the
    SourceFile terminal; class-member/arrow bodies stay unreached — pinned
    negative, a signal-driven widening candidate; the B162 same-enum sibling
    scan reproduced via `spineSiblingStatements`) + checkSwitchCaseComparable
    (TS2678 — the per-statement-LIST const/annotated binding maps reproduced
    as a preceding-sibling scan at the SWITCH node,
    `spineSwitchSubjectBinding`; single-statement positions degrade to
    `listOf(stmt)` = the old fresh-map wraps). 9 walker funs (~453 lines)
    deleted, 3 init slots removed. Suite +27 (Inv4SpineBatch6Test, pins run
    against the OLD walkers first), listAll error lines identical on ALL 8
    profiles, bench in band. Batch 7 DONE same round: checkRestElementPropertyNames
    (TS2566 — pure-syntax, ObjectBindingPattern-enter handler; widened
    faithfully to catch-clause patterns, each nested pattern gets its own
    enter) + checkRestBindingPatternElements (TS1186/TS2493/TS2322 —
    `checkRestBindingParam` retained as the Parameter-dispatch core; widened
    to object-literal-method/class-expression params) +
    checkAmbientImplementation (TS1183 — the most intricate reach walk so
    far, `spineAmbientImplContext`: ambient fn/class-member bodies were never
    descended (own-declare → null + the [passedDeclBody] declare-module-above
    rule), while arrow/fn-expr/class-EXPRESSION-member/objlit-method bodies
    RESET ambient unconditionally (passedDeclBody cleared — the expression
    walk descended them with false even under ambient); statement containers
    position-checked (conditions/for-headers/switch-subjects/case-exprs
    unreached), expressions pass generically; interface arm is de-facto
    dormant — the parse drops interface method bodies, cf. the TS1246 note) +
    checkAmbientRelativeModuleNames (TS2436 — top-level-of-script-file gate =
    a SourceFile parent check). 15 walker funs (~551 lines) deleted, 4 init
    slots removed. Suite +21 (Inv4SpineBatch7Test — 19 pre-verified against
    the OLD walkers, 2 widening pins fail pre-migration as expected). Batch 8
    DONE round 518 (2026-07-14): the parameter-initializer family — SIX
    passes as three Parameter-enter handlers + one SetAccessor-enter handler:
    checkOptionalParamWithInitializer (TS1015 — the corpus-tuned requireType
    gate preserved: declarations need a type annotation or param-property
    modifier, arrow/fn-expr params fire regardless; interface/type-literal
    signatures and objlit/class-expr GET accessors stay excluded per the old
    reach) + checkOptionalBindingPatternParams (TS2463 — uniform
    owner-has-body gate per parent kind) + checkParamInitializerForbidden
    (TS2523/TS2524/TS2372/TS2502/TS18048 — walkParamInitForbidden + the
    binding-name walk + collectParamSelfRefs retained as the per-parameter
    core; the per-file code@pos dedup set became spineParamForbiddenEmitted;
    the walkParamForbiddenExprForFns nested-fn descent dissolves into
    per-Parameter enters; findParamSelfRef deleted as already-dead) +
    checkParameterInitializerInNonImpl (TS2371 — widened faithfully to EVERY
    FunctionType/ConstructorType position per tsc checkParameter (initializer
    + missing containing body); old reach was var annotations/aliases/casts
    only; accessors stay excluded) + checkSetAccessorInitializer/
    checkSetAccessorRestParameter (TS1052/TS1053 — parent gate widened from
    class declarations to class expressions + object literals per tsc
    checkGrammarAccessor; interface/type-literal setters excluded, a
    signal-driven candidate). 24 walker funs (~902 lines) deleted, 6 init
    dispatches removed. Suite +29 (Inv4SpineBatch8Test — 23 pre-verified
    against the OLD walkers, 6 widening pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (518a vs 517b).
    Re-measured --passTiming (pre-batch): checker-init 21.6 s, spine 529 ms
    carrying 24 passes; this batch's six summed ~292 ms of old pass time.
    Batch 9 DONE same round: checkForInLhsTypeAnnotation (TS2404 —
    ForInStatement-enter; widened faithfully to arrow/fn-expr bodies the old
    statement walk never descended) + checkEmptyTypeArguments (TS1099 on
    calls/new — CallExpression/NewExpression-enter; the type-POSITION TS1099
    emitter sharing emitTS1099 is untouched; reportEmptyTypeArgs deleted as
    orphaned) + checkSetterReturns (TS2408 — SetAccessor-enter;
    checkSetterBodyReturns retained as the per-setter body scan, fn-boundary
    semantics unchanged; widened to await operands etc.) + checkWithStatements
    (TS1101/TS1300/TS2410 — WithStatement-enter; the threaded isInWith/isInAsync
    pair became ONE parent-chain walk: first WithStatement ancestor before any
    function-like boundary → inner-with suppression of TS1300/TS2410; nearest
    fn boundary's Async modifier decides TS1300, ARROWS still reset async to
    false (old behavior, tsc's AwaitContext would fire — signal-driven
    candidate, pinned negative); TS2410's balanced-paren span scan preserved;
    TS1101 gated on alwaysStrict != false via spineWithStrictActive). 16
    walker funs (~606 lines) deleted, 4 init slots removed. Suite +18
    (Inv4SpineBatch9Test — 14 pre-verified against the OLD walkers, 4 widening
    pins fail pre-migration as expected); listAll error lines IDENTICAL on ALL
    8 profiles (518b vs 518a). Batch 10 DONE round 519 (2026-07-14):
    checkParamInitForwardRef (TS2373 + the ES5 hoisted-body-var TS2454
    companion) — checkForwardRefsInParams (+ findForwardParamRefs /
    findForwardParamRefsInBlock / collectHoistedVarNamesFromStmts) retained
    as the per-function core, dispatched from spineCheckParamForwardRefs at
    every BODIED function-like's enter; widened faithfully to arrows /
    fn-exprs / objlit methods / class-EXPRESSION members
    (position-independent per-signature tsc grammar); bodyless signatures
    keep the old no-check (TS2371 territory), GetAccessor params stay
    unchecked (TS1054 territory). 2 walker funs (~70 lines) deleted, 1 init
    dispatch removed. Suite +14 (Inv4SpineBatch10Test — 10 pre-verified
    against the OLD walker, 4 widening pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (519a vs 518b). Batch 11
    DONE same round: the checkJumpTargets family (TS1104/TS1105/TS1107/
    TS1115/TS1116 + TS1344) — the threaded inIteration/inSwitch/labelNames/
    crossedFunctionBoundary flags became ONE parent-chain walk
    (spineCheckJumpTarget) mirroring tsc
    checkGrammarBreakOrContinueStatement's `while (current)` loop: first
    function-like ancestor → TS1107 (class static blocks now count — a
    faithful widening); a matching LabeledStatement resolves the jump, with
    tsc's isIterationStatement(lookInLabeledStatements=true) nested-label
    unwrap for labeled `continue` — a faithfulness FIX over the old
    immediate-child test (`L1: L2: for(;;){continue L1}` no longer
    false-fires TS1115); an iteration ancestor legalizes unlabeled jumps, a
    SwitchStatement legalizes unlabeled `break`, a ModuleBlock ancestor
    suppresses unlabeled `break` (the old inSwitch=true namespace rule);
    TS1344 label-on-declaration became a LabeledStatement-enter handler
    (widened to arrow-in-condition positions). 4 walker funs (~306 lines)
    deleted, 1 init dispatch removed; emitJumpDiagnostic /
    isDeclarationStatement retained as the per-jump core. Suite +18
    (Inv4SpineBatch11Test — 14 pre-verified against the OLD walker, 3
    widening + 1 faithfulness-fix pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (519b vs 519a). Batch 12
    DONE same round: checkObjectLiteralModifiers (TS1042/TS1184) — the
    near-full-tree explicit-stack expression walk became a pure
    ObjectLiteralExpression-enter handler (spineCheckObjLitModifiers;
    OBJLIT_ACCESS_MODIFIERS companion-hosted per the init-order gotcha);
    nested literals get their own enters; parameter-default and
    spread-operand positions are faithful widenings. 3 walker funs
    (~206 lines) deleted, 1 init dispatch removed. Suite +10
    (Inv4SpineBatch12Test — 2 widening pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (519c vs 519b). Batch 13
    DONE round 520 (2026-07-14): checkDuplicateObjectLiteralProperties
    (TS1117/TS1118/TS2300 — [checkObjectLiteralDuplicates] retained as the
    per-literal core dispatched from the ObjectLiteralExpression enter; the
    destructuring-assignment-LHS skip became the came-from-child parent walk
    `spineObjLitInDestructuringLhs`: climb through pattern-position parents
    — object/array literals, a PropertyAssignment when the child is its
    INITIALIZER, spread positions — and skip iff a `=` BinaryExpression is
    reached with the climbed child as its LEFT; a ShorthandPropertyAssignment
    default VALUE terminates the climb, so `({q = {a,a}} = o)` is now checked
    — a tsc-faithful widening alongside ternary conditions, parameter
    defaults, and object-literal METHOD bodies) + checkReservedWordIdentifiers
    (TS1359 — checkAwaitParams retained, dispatched from every async
    function-like's enter; the enum void/await/yield name rule as an
    EnumDeclaration-enter handler; widenings: class property-initializer
    arrows, new-expression var initializers, var-init arrow expression
    bodies) — 6 walker funs (~370 lines incl. the already-dead reservedWords
    val) deleted, 2 init dispatches removed. Suite +23 (Inv4SpineBatch13Test
    — 16 pre-verified against the OLD walkers, 7 widening pins fail
    pre-migration as expected); listAll error lines IDENTICAL on ALL 8
    profiles (520a vs 519c). Batch 14 DONE same round:
    checkStrictModeReservedWords (TS1212/TS1213/TS1214/TS2480/TS18006 — the
    most stateful zero-typing walker yet): the threaded isStrict/
    isExpressionStrict/inClass/realStrict flags became ONE shared
    ancestor-chain context (`spineStrictReservedCtx`: collect the parent
    chain, walk it DOWN applying the old descent arms —
    Block/If/ForIn/ForOf/ModuleBlock/ModuleDeclaration transparent, a
    FunctionDeclaration entered ONLY under the strictness at ITS position
    with a "use strict" prologue upgrading realStrict for its subtree, a
    ClassDeclaration entered only through METHOD/CONSTRUCTOR members
    (auto-strict: inClass + both strictness flags forced), any other
    ancestor kind → null = the old no-visit); ten per-statement-kind
    handlers (var-statement incl. fn-expr-name/type-annot/class-expr-init
    legs, for-in/of header decls, fn decl, class decl incl. TS18006 +
    member params, interface, enum, import-equals, import bindings,
    namespace name, expression statement); per-file flags
    (spineStrictFile* — binding strictness by effectiveTarget, EXPRESSION
    strictness by RAW target, the explicitNonStrict suppression) computed
    in checkSpine's loop; the two strictReserved* instance flags moved to
    the pre-init spine block, assigned per position from the ctx. Reach
    deliberately NOT widened (corpus-tuned family — interfaceNaming1 /
    commonMissingSemicolons / constructorStaticParamName): while/do/for/
    switch/try bodies, accessor bodies, arrow/fn-expr bodies, and
    class-expression members stay unvisited, pinned negative as
    signal-driven widening candidates; the load-bearing reach QUIRK — fn
    bodies UNVISITED in non-strict files (no TS2480 for `let let` there) —
    is reproduced by the ctx walk and pinned. 3 walker funs (~250 lines)
    deleted, 1 init dispatch removed. Suite +25 (Inv4SpineBatch14Test —
    ALL 25 pre-verified against the OLD walker; a pure reach-preserving
    migration, no widenings); listAll error lines IDENTICAL on ALL 8
    profiles (520b vs 520a). --passTiming RE-MEASURE (round 520, post
    batch 14): checker-init 20.0 s (21.6 s pre-batch-8); spine 718 ms
    carrying ~34 migrated passes; 459 passes recorded (~55 dispatches
    removed since INV.0's 514); top-3 giants unchanged
    (checkPropertyAccess 3.53 s / checkTypeAssignability 2.33 s /
    checkCallExpressionTypes 2.06 s = 7.9 s); the next-biggest non-giant
    passes are EXACTLY the INV.4(c) pair — checkUnresolvedNames 744 ms +
    checkTypeUsedAsValue 739 ms — then the (d) cohort
    (checkUncalledFunctionsInConditions 454 ms, checkArithmeticOperandTypes
    335 ms, checkImplicitAnyParameters 279 ms); the remaining zero-typing
    tail is mostly sub-100 ms each (checkAwaitContext 93 ms — stateful
    isAsync threading + the TS1262 top-level prepass + the batch-8 TS2524
    param-default ownership boundary; decompose when reached, low yield).
    Batch 15 DONE round 521 (2026-07-14) — **(b) COMPLETE**: checkAwaitContext
    (TS1308/TS1103/TS2311/TS1262 — the threaded isAsync/enclosingFunc pair)
    became THREE rare-node enter handlers (spineCheckAwaitExpr /
    spineCheckForAwait / spineCheckAwaitCall) driven by ONE full parent-chain
    walk (`spineAwaitCtx`): the FIRST function-like boundary decides the flags
    (async modifier; the TS1356 related-info FuncRef — ctor/accessor/prop-init
    boundaries force sync), and EVERY chain step up to the SourceFile must be
    an old-walked position (parameter defaults are TS2524's, enum member
    initializers / computed names / static blocks / heritage / shorthand
    destructuring defaults / objlit ACCESSOR bodies stay unreached — pinned
    negative); ModuleDeclaration bodies are TRANSPARENT, preserving the
    namespace-inherits-module-asyncness quirk (pinned); the TS1262 top-level
    `await`-binding scan (checkTopLevelAwaitNames, retained) runs per module
    file from checkSpine's loop and sets the TS2311 suppression flag. 4 walker
    funs (~310 lines) deleted, 1 init dispatch removed. Suite +27
    (Inv4SpineBatch15Test — ALL pre-verified against the OLD walker; a pure
    reach-preserving migration); listAll error lines IDENTICAL on ALL 8
    profiles (521a vs 520b). Closure decisions: checkConflictMarkers STAYS an
    init pass (a per-file TEXT scan — the spine walks nodes; there is no walk
    to delete); checkMixinClassConstructor is TP-scope-stateful → (d). The
    remaining stateful walkers are (c)/(d) territory.
  - [x] **INV.4(c) The name-resolution pair** — COMPLETE round 529 (all four
    sub-items landed; both families' recursive walkers deleted).
    checkUnresolvedNames (846 ms) +
    checkTypeUsedAsValue (734 ms): fold their private NameScope chains into
    spine-maintained authoritative lexical state backed by the INV.2(c)
    `lexicalScopes` tables (their planned mass consumption). Decomposed round
    522 (facts verified in-code: the checkUnresolvedNames family is ~3,000
    lines — statement/class-element/expression/type/JSX walkers threading a
    `NameScope` chain whose content closely mirrors `lexicalScopes` (params,
    hoisted vars, block bindings, type params + constraints) plus per-file
    root extras (KNOWN_GLOBALS seeding, DOM/host @lib filtering, ambient-
    module-name exclusion, `declare global` handling, JS @typedef regex
    types) and walk-threaded flags (classContext / inFunction / hasArguments);
    checkTypeUsedAsValue is ~700 lines threading THREE ScopeNameSet chains
    (typeOnly/value/namespaceOnly) built from AST surveys — NOT symbol-shaped,
    and its reach is corpus-tuned per the round-42 over-emission gotcha (no
    loop/switch/try descent)). Sub-items, one commit each, every step suite-
    and 8-profile-listAll-gated:
    - [x] **(c)(i) Spine-maintained lexical scope state (infrastructure,
      always-on).** DONE round 522 (2026-07-15 — the checkbox was missed in
      that round's commit; see the round-522 session note for the full
      landing record). The walk maintains `spineCurrentScope` — push at a scope
      owner's enter (BEFORE its own handlers dispatch), pop after its leave —
      via a per-file nodeId→LexicalScope ARRAY built from
      `result.lexicalScopes` (the INV.2(b) boxing-avoidance trick; cleared by
      re-nulling only written ids); a SwitchStatement's scope is re-keyed
      onto its CLAUSE nodeIds at fill so the switch EXPRESSION stays in the
      outer scope (the binder's routing); function-body Blocks share the fn
      scope automatically (no map entry); decorator outer-scope routing is a
      documented deferred divergence (both the walk and the binder tables
      currently agree). `spineScopeLookup(name)` resolves symbols → existing
      → parent. Pinned by a test-only AUDIT mode (companion statics — tests
      cannot reach the Checker instance): every spine enter verifies the
      incremental scope against a parent-chain derivation, and identifier
      enters record `spineScopeLookup` resolutions into a trace the tests
      assert on (shadowing id splits, scope-space ids ≤ −2, switch-expression
      isolation, catch/enum/self-name/var-hoist shapes). Bench row (the walk
      gains one array probe per enter+leave).
    - [x] **(c)(ii) checkUnresolvedNames STATE swap.** DONE round 523
      (2026-07-15): the NameScope content queries (`has` / `isTypeParam` /
      `hasType` / `typeParamConstraintOf` / `hasLocalShadow` / the TS2552
      candidate pool) are hybrid — each NameScope carries `lex` (the binder
      [LexicalScope] a TRUSTED scope-owner site links; population SKIPPED
      when linked) and queries interleave the threaded sets with the lex
      levels each NameScope level introduced (`lex` down to `parent.lex`,
      preserving shadowing order). Trusted links: statement lists via a new
      `checkUnresolvedInStatements(owner)` param (Block / SourceFile / the
      FUNCTION node for fn bodies — body Blocks have no binder entry),
      for/for-in/for-of headers, catch, switch (binder keys the case scope
      by the switch nodeId — the expression is checked before linking, so
      no re-keying needed), class/class-expr/interface/type-alias TP scopes.
      Function SIGNATURE positions stay threaded (params/TPs) — the binder's
      flat fn table would leak body decls into param defaults (sub-ES2015
      pre-collect is the only path that may see them; pinned both ways).
      Untrusted levels skipped in queries: ModuleDeclaration (the walk's
      buildNamespaceScope is EXPORT-filtered; binder aliases ALL merged
      members), EnumDeclaration (EnumMember-filtered), SourceFile existing
      filtered by a per-file exclusion set (ambient external module names +
      the declare-global quirk); type-level scopes (mapped TP / infer /
      fn-TYPE params) stay threaded. Unindexed trees: every probe misses →
      legacy behavior by construction. Equivalence-gated: corpus green +
      8-profile listAll error-line-identical; walk-threaded flags stay
      threaded until (c)(iii).
    - [x] **(c)(iii) checkUnresolvedNames WALK swap.** Move the emission
      positions onto the spine (delete the ~15 recursive walkers); reach
      reproduced per the emission-direction rule (this family is (b)-class —
      direct emitters — so under-visits are reproduced via parent-chain
      gates, widenings only on a signal). Batch 1 DONE round 524 (2026-07-15):
      the spine maintains the family's NameScope chain (`spineUResStack` —
      lazy signature population / deferred-activation regions / decorator
      pre-population views reproduce the legacy walk's sequential-mutation
      order on the spine's fixed preorder; per-file ROOT shared via
      `unresolvedFileRootFor`, enabled by the `computeTypeLibResolution`
      split), audited per-Identifier against the legacy walk's scope
      fingerprints (Inv4UnresolvedSpineScopeTest, 2 deliberate-breakage
      sharpness probes). classContext / inFunction / hasArguments ride the
      maintained NameScope levels (no parent-chain re-derivation needed).
      Batch 2 DONE round 525 (2026-07-15): the STATEMENT-LEVEL walk swap —
      checkUnresolvedInStatements/InStatement(Core) DELETED; per-statement
      dispatch in spineUResDispatch against the maintained levels;
      FunctionDeclaration signature positions at child enters
      (lazy-population staging); the with-body / skipped-return /
      declare-fn+class under-visits as suppressed-region levels and the
      declare-module post-filter as the filter2304 level flag, both enforced
      by the spineUResEmit wrapper (which also nulls currentFileLocals — the
      legacy pass ran unscoped); the 10 statement descents in the
      expr/class-element walkers cut; checkUnresolvedNames retained only as
      the declarationOnly minimal driver (spineUResOnly). listAll gate:
      error-line SETS identical on all 8 profiles; within-file PRINT order
      shifts (emission order — the corpus suite gates the sorted output
      byte-identical). Batch 3 DONE round 526 (2026-07-15):
      checkUnresolvedInClassElement DELETED — class-member decorators/
      computed-names at member enter (the pre-population moment = the legacy
      B98.r111 view), TP/param/return positions via the shared
      spineUResFnSigDispatch with per-member-kind coverage flags, index
      signatures in the class scope; gated to class decl/expr parents
      (interface members stay with the batch-2 handler). Batch 4 DONE round
      527 (2026-07-16): the EXPRESSION walk swap — expression positions
      self-emit at their own enters, gated by `spineUResExprChecked` (a
      per-file nodeId-memoized ancestor walk over `spineUResExprEdge`
      ROOT/DESCEND/NONE verdicts reproducing the recursive walker's exact
      reach); NaN/shorthand/embedded-type/class-expr-heritage/JSX handlers
      dispatch per node kind; spineUResFnSigDispatch reduced to TYPE
      positions (checkTps flag = the legacy fn-expr/objlit-method
      no-constraint-check asymmetry); the TS2422 skip became the
      spineUResHeritageSkip nodeId set; arrow/fn-expr/objlit-method levels
      carry exprOwned so recursion-owned regions keep the retained walker.
      checkUnresolvedInExpr(Core) retained SOLELY for the type walker's
      TypeLiteral computed-name positions. Batch 5 DONE round 528
      (2026-07-16) — **(c)(iii) COMPLETE, all the family's recursive walkers
      are DELETED** (checkUnresolvedInType(Core), the retained
      checkUnresolvedInExpr(Core), the JSX attribute/child helpers — ~660
      lines): type positions self-emit at their own enters. Unlike batch 4's
      static classifier, the type ROOTs are MARKED — every dispatch site that
      called the walker now calls `spineUResMarkTypeRoot` (strictly before
      the marked subtree walks; the sites stay the single source of truth),
      and `spineUResTypeChecked` (per-file nodeId memo) walks ancestors over
      `spineUResTypeDescends` edges = the deleted walker's recursion arms
      (mapped-TP constraint / conditional-infer / fn-type / type-literal
      member staging comes from the batch-1 maintained levels). Self-emitting
      kinds: TypeReference (names + TS2314 + utility TS2344 + TS1099),
      IndexedAccessType, TypeQuery, FunctionType/ConstructorType (TS2842),
      TypeLiteral (member computed-name TS2690/TS2693/TS2464 in one batch at
      the literal's enter). The last recursion-owned expression region — a
      TL member's computed NAME — became an expression ROOT gated on
      `spineUResTypeChecked(typeLiteral)`, flipping `exprOwned` true there so
      the fn-sig dispatch covers what the retained walker's arms did.
      Verified: suite 10,804 → 10,832 (+28 Inv4SpineBatch19Test, ALL
      verified identical on the OLD walker via stash — pure
      reach-preserving; 0 regressions); listAll error lines IDENTICAL on
      ALL 8 profiles (528a vs 527a; header-only timing diffs); bench row
      recorded.
    - [x] **(c)(iv) checkTypeUsedAsValue.** DONE round 529 (2026-07-16): the
      recursive checkTypeAsValueInStatement(s)/checkTypeAsValueInExpr walkers
      + ScopeNameSet DELETED (~700 lines). Identifiers self-emit
      TS2693/TS2708 (+ the TS2585 forward-lib routing) at their enters, gated
      by `spineTavStatus` — a memoized 3-state ancestor-chain classifier over
      `spineTavEdge` (the deleted walker's exact dispatch arms, incl. the
      corpus-tuned NON-descent into for/while/do/switch/try bodies, class
      accessors/EXPRESSIONS, shorthand properties, and objlit-method param
      defaults; the plain-`=`-LHS TS2708 suppression is the REACHED_NONS
      status minted on the Equals-left edge — checkConstAssignment owns the
      assignment-target TS2708). The set chains stayed set-based as planned
      but became PULL-BASED memoized levels (`tavLevelAt`/`tavLevelFor` —
      the family's surveys are position-independent, so no batch-1-style
      lazy staging; the one order-sensitive spot, an objlit method's
      computed NAME seeing the OUTER scope, is a came-from-child owner
      skip). The file survey (TS18042 emission + currentForwardLibTypeNames
      included, verbatim) builds eagerly per file in checkSpine's loop
      (`tavBuildFileRoot`); TS2689 classifies at the CLASS enter and marks
      `spineTavHeritageSkip` before the heritage subtree walks (the deleted
      either/or: TS2689 OR the generic walk, never both). Suite
      10,832 → 10,872 (+40 Inv4SpineBatch20Test, ALL verified against the
      OLD walker first; 0 regressions); listAll error lines IDENTICAL on
      ALL 8 profiles (529a vs 528a); bench row recorded.
  - [x] **INV.4(d) Mid-weight stateful walkers.** COMPLETE round 541 (walkers
    1–13; the round-529 cost-ordered list is fully migrated — a fresh
    --passTiming table at round 542 shows the remaining non-giant tail is a
    flat sea of sub-160 ms mostly-stateless passes, none of them the
    scope-machinery shape this item targeted; they get absorbed
    opportunistically or superseded by (e)/(f)). Each walker moved its scope
    machinery onto the shared spine state; decompose per walker when reached.
    MEASURED cost order (round-529 --passTiming, post-(c): checker-init
    20.6 s; spine 2,247 ms carrying both name-resolution families + ~40 tail
    passes; giants unchanged 3.92/2.34/2.17 s):
    checkUncalledFunctionsInConditions 435 ms (38,986 getTypeOfExpression
    calls — a typing pass, not zero-typing), checkArithmeticOperandTypes
    309 ms (68,946 calls), checkImplicitAnyParameters 272 ms,
    checkDuplicateIdentifiers 260 ms (zero-typing), checkDefiniteAssignment
    241 ms, checkArgumentCounts 230 ms, checkUseBeforeDeclaration 205 ms,
    checkImplicitReturns 199 ms, checkConstAssignment 170 ms, then a long
    ~100–165 ms tail (checkAlwaysTruthy, checkNullUndefinedUsage, …).
    - (w1) DONE round 530 (2026-07-16): checkUncalledFunctionsInConditions
      (TS2774/TS2801) — the first (d)-class TYPING-pass migration; template
      extends (c)(iv): boolean reach classifier + PULL-BASED per-emission
      stack rebuild with per-owner memoized LAZY levels (functions with no
      conditions never pay the collection's typing calls), ambient state
      (currentFlowGraph/currentCheckFileName) save-set-restored around EACH
      dispatch never walk-wide. 36 pins (Inv4SpineBatch21Test) pre-verified
      on the OLD walker; suite 10,872 → 10,908; listAll error-line identical
      on ALL 8 profiles; ~270 walker lines deleted. See the round-530
      session note for the quirks pinned.
    - (w3) DONE round 532 (2026-07-16): checkImplicitAnyParameters
      (TS7005/TS7006/TS7008/TS7013/TS7019/TS7031/TS7032/TS7051) — the first
      DOWNWARD-CONTEXT-THREADING migration: the checkImplicitAnyInExpr
      recursion's five explicit context parameters (contextuallyTyped /
      contextualType / viaUnionWithPrimitive / ctxAnnotation / ctxViaAssignment)
      become ONE push-maintained SpineIanyCtx value with frames defined at
      EXACTLY the edges the legacy recursion passed arguments over (a missed
      edge silently LEAKS the parent context — every reached expression-position
      edge must define, even to null); the binary left-spine loop dissolves into
      per-edge rules (right operand by operator; left inherits for `||`/`??`
      only); returnCtxAnnotation + inAmbientContext pull-derive from parent
      chains; the three implicit-any scope stacks stay the same checker fields,
      pushed at body edges + recorded at declarator enters. No ambient install
      needed (slot-move A/B ×8 error-identical + corpus green pre-gated the
      move past the 4 sibling TS7xxx passes). 56 pins (Inv4SpineBatch23Test)
      ALL pre-verified on the OLD walker — incl. the reach quirks (while/do/
      switch/try/for-in/for-of bodies, call CALLEES, conditional CONDITIONS,
      as-casts, objlit accessors, static blocks all unreached) and the
      class-expression setter TS7032-with-sibling-getter bug-compat fire.
      The recursive walkers (checkImplicitAnyInStatements/-InClassElement(Core)/
      -InExpr) + the pass driver are DELETED (~770 lines); suite 10,948 →
      11,004; listAll error lines identical on ALL 8 profiles. See the
      round-532 session note.
    - (w2) DONE round 531 (2026-07-16): checkArithmeticOperandTypes — the
      first ORDER-DEPENDENT stateful migration (statement-ordered recordings
      that leak across blocks → PUSH-maintained frames on the spine, not the
      pull-based rebuild) and the first pass from AFTER the three giants
      (slot-move pre-gate found the currentParamBindingNames leak as the ONLY
      order coupling — kept pass-private now). Left-spine flatten = chain-root
      LEAVE emission; ambient install per emission/recording. The CORPUS caught
      a second, subtler coupling the profiles could not: the pass CONSUMED the
      TS2322 walk's namespace-level recording residue (qualify.ts) — reproduced
      as the pass's own ModuleBlock-gated identifier-init chain recording. 39
      pins (Inv4SpineBatch22Test); suite 10,908 → 10,948; listAll error-line
      identical on ALL 8 profiles; the pass driver deleted (the recursive
      walkers stay as checkComputedDestructKey's utility). See the round-531
      session note.
    - (w12+w13) DONE round 541 (2026-07-17): the ORDER-COUPLED pair
      checkCommaOperatorUnused (TS2695) + checkNullishPredicates (TS2871/
      TS2869 + while/do truthiness) migrated TOGETHER — the ordering
      contracts dissolve structurally (comma pre-order → ENTER anchors; np
      post-order → LEAVE anchors; while/do truthiness at the CONDITION's
      leave; same-position comma-first BY CONSTRUCTION since enters precede
      leaves — the legacy slot contract retired). Separate verbatim
      classifiers (their reach differs: objlit method bodies np-only;
      tagged-templates/yield/delete/typeof/comma-lists comma-only). 10 pins
      (Inv4SpineBatch32Test) pre-verified; suite 11,233 → 11,243; listAll
      ×8 identical; ~470 walker lines deleted. See the round-541 session
      note.
    - (w11) DONE round 540 (2026-07-17): checkNullUndefinedUsage (TS18050 +
      the for-of empty-[] TS2488 shape) — pure anchors, no ambient; the
      classifier carries the legacy checkDepth ≤ 200 STATEMENT-frame cap as
      a depth-encoded ShortArray status, with legacy frameless body Blocks
      as CARRIER blocks at the parent's depth. 12 pins (Inv4SpineBatch31Test)
      pre-verified; suite 11,221 → 11,233; listAll ×8 identical; ~230 walker
      lines deleted. See the round-540 session note.
    - (w10) DONE round 539 (2026-07-17): checkAlwaysTruthy (TS2872/TS2873 +
      TS1345/TS2845 + the `!`-operand falsy check) — frameless: both walk
      states pull-derive (the never-reset B69.11 inArrowExprBody flag; the
      if-else-chain prevTruthy via elseStatement ancestor links); per-chain-
      node dispatch at IfStatement enters. Condition-reach asymmetry pinned:
      if/while/do/ternary condition sub-exprs never walked, FOR conditions
      fully walked. 13 pins (Inv4SpineBatch30Test) pre-verified; suite
      11,208 → 11,221; listAll ×8 identical; ~230 walker lines + the
      threading field deleted. See the round-539 session note.
    - (w9) DONE round 538 (2026-07-17) — checkConstAssignment (TS2588/TS2628/TS2629/TS2630/TS2708 +
      TS2540 readonly writes + TS2357 inc/dec targets + scanRegExpFull's
      TS1538/regex-grammar family riding the same walker). SCOUTED
      (2026-07-17, in-code): the most stateful (d) walker yet — a w2+w5
      hybrid. (1) constNames is a statement-ordered LIVE MutableMap per
      activated list (collect const/class/enum/fn/ns THEN check, let/var
      REMOVES an inherited name) → DA-style core frames with per-statement
      collect steps at direct-child enters; spawn rules are ASYMMETRIC:
      Block/switch-clause/try-blocks/ModuleBlock/class-member bodies COPY
      the top frame's live map, FunctionDeclaration/fn-expr/arrow-Block/
      IIFE-arrow-Block bodies get a FRESH EMPTY map (an outer const is NOT
      flagged inside a fn body — bug-compat), SourceFile seeds from the
      program-wide sharedConsts overlay (script files only; module files
      empty). (2) The For header is an EDGE overlay: condition/incrementor/
      body see outer+header consts, the INIT EXPRESSION sees outer only.
      (3) currentClassForThis/currentThisMemberIsCtorDirect pull-derive from
      the ancestor chain: per-member staticness, Constructor→ctorDirect,
      property-initializer→ctorDirect=false, fn-expr NULLS the class, arrow
      keeps it with ctorDirect=false, and an IIFE-ARROW is TRANSPARENT to
      ctorDirect (the CallExpression arm's immediatelyInvokedArrowCallee).
      (4) FunctionDeclaration bodies install currentLocalTypes/
      currentParamBindingNames copies + populateParameterLocalTypes (B116 —
      fn DECLS only, not methods/fn-exprs/arrows) — cumulative through
      nested fn decls; per-anchor pull-rebuild with per-owner memo (w1
      template). (5) This is a TYPING pass (checkReadonlyAssignmentTarget
      resolves receiver types) — slot-move pre-gate with the CORPUS
      mandatory; check for diagnostics-list probes before choosing
      enter-vs-leave dispatch (the round-537 lesson). Anchors: assignment-op
      BinaryExpressions (left-spine loop — emissions are per-spine-node, at
      each binary's own reach), ++/-- Prefix/Postfix, RegularExpressionLiteralNode.
      LANDED as scouted (enter-dispatch — no diagnostics probes); 19 pins
      (Inv4SpineBatch29Test) pre-verified on the OLD walker; suite 11,189 →
      11,208; listAll ×8 identical; ~330 walker lines deleted. See the
      round-538 session note.
    - (w8) DONE round 537 (2026-07-17): checkImplicitReturns
      (TS7030/TS2355/TS2366/TS2378/TS7023 + arrow concise-body TS2322).
      SLOT-MOVE PRE-GATE LANDED AND VERIFIED (intact pass at the spine slot;
      corpus 11,170/0 + listAll ×8 error-line identical) — the ambient
      residue at the spine slot is proven equivalent, and the pass stays
      BEFORE checkTypeAssignability, whose end-of-pass filter suppresses
      TS7030 at its own TS2322 positions (it EXPECTS this pass's TS7030s to
      exist — do not move it past the giants). SCOUTED migration design
      (w1-template): 4-state reach classifier (STMT/EXPR/MEMBER/NONE) over
      walkStmtForImplicitReturns/walkExprForImplicitReturns arms; anchors at
      FunctionDeclaration/MethodDeclaration/GetAccessor/FunctionExpression/
      ArrowFunction enters (the retained check*ForImplicitReturn bodies
      minus their trailing walkForImplicitReturns recursion); per-dispatch
      ambient install of implicitReturnFlowGraph + currentCheckFileName +
      the PRE-SPINE resting currentFileLocals/currentFunctionParams
      (checkGetAccessorForImplicitReturn reads currentFunctionParams'
      RESTING value — it never sets it; capture both at checkSpine entry
      like spineArithBase). Per-file gate: !isDts && (checkJs || !(.js|.jsx))
      — NOTE .mjs/.cjs are NOT skipped by the legacy gate (spineIsJsLike is
      the wrong predicate). Sharp reach quirks to pin (verified in-code):
      GENERATOR bodies never descend (the anchors early-return before their
      trailing recursion); class-DECL Constructor/SetAccessor bodies and
      class-DECL PropertyDeclaration initializers unreached while class-EXPR
      prop inits ARE reached; objlit SetAccessor bodies unreached; arrow
      CONCISE (expression) bodies never descend (both annotated and not);
      return/throw/export= EXPRESSIONS and if/while conditions and for
      headers unreached in statement position; GetAccessor sentinel body
      (pos == -1) skips. LANDED: anchors dispatch at LEAVE (the 17.135
      TS2304/TS2314 diagnostics-list probes must see the annotation's own
      spine emissions — enter-dispatch over-emitted TS2355 on exactly 2
      corpus tests); 19 pins (Inv4SpineBatch28Test); suite 11,170 → 11,189;
      listAll ×8 identical; ~140 walker lines deleted. See the round-537
      session note.
    - (w7) DONE round 536 (2026-07-17): checkUseBeforeDeclaration (TS2448/
      TS2449/TS2450 + TS2454 co-emit + static-init TS2729) — 5-state reach
      classifier + per-list-owner memoized blockScopedDecls; the retained
      BOUNDED checkUBDForwardRefs walk anchors at DIRECT statements of
      activated lists (it recurses if/labeled itself — nested statements
      never re-anchor); loop-header self-ref checks re-host at For/ForIn/
      ForOf enters. TWO order couplings resolved by slot placement:
      populateAmbientCyclicBaseClasses (the TS2449 suppression-set producer)
      moved BEFORE the spine, and the TS2454 co-emits becoming visible to
      checkDefiniteAssignmentViaFlowGraph's dedup scan measured INERT
      (slot-move pre-gate: corpus green + listAll ×8 identical). Cross-file
      leg stays a separate pass at the spine slot. 33 pins
      (Inv4SpineBatch27Test) ALL pre-verified on the OLD walker first run;
      suite 11,137 → 11,170; listAll error-line identical on ALL 8 profiles;
      ~195 walker lines deleted. See the round-536 session note.
    - (w6) DONE round 535 (2026-07-17): checkArgumentCounts (TS2554/TS2555/
      TS2575) — the first DEPTH-valued reach classifier (the legacy
      argCountDepth recursion counter reproduced per edge, ≤200 cap; binary
      right-spine absorption = no depth) and the first MAP-valued pull-based
      downward context (funcParams/ctorParams/fnDepth/superCtor rebuilt at
      each emission from per-list-owner memoized levels — sound because every
      list overlay reads its WHOLE statement list). TRAP: a pull rebuild that
      RE-ENTERS itself through its own memoized levels must reuse its shared
      ascent buffer MARK-based, never clear()-based (the for-of loop-shadow
      edge silently dropped; one pin caught it). Producer sibling
      checkSpreadNonIterableIntoFixedArity moved BEFORE the spine. 46 pins
      (Inv4SpineBatch26Test) ALL pre-verified on the OLD walker; suite
      11,091 → 11,137; listAll error-line identical on ALL 8 profiles;
      ~650 walker lines + 3 threading fields deleted. See the round-535
      session note.
    - (w5) DONE round 534 (2026-07-16): checkDefiniteAssignment (the SET-based
      TS2454 pass) — the first per-statement-LIST ordered walker with a
      DOWNWARD leak context: legacy list activations become CORE FRAMES
      (pushed at SourceFile/fn-body/Block/ModuleBlock owners, per-statement
      steps at direct-child enters — the collect/checkUses/mark/nestedLeak
      loop body retained verbatim), the recursion walkers become a memoized
      10-state ancestor classifier (spineDaStatus/spineDaEdge), and the
      downward leak set is READ from the top frame's per-statement
      currentLeak via LEAK-flavored statuses (sound: leak-preserving paths
      never cross a core spawn). The flow-graph siblings (ViaFlowGraph
      dedups one-directionally against this pass) moved to right after the
      spine, preserving set-pass-first order; slot-move pre-gate ×8
      identical. 39 pins (Inv4SpineBatch25Test) pre-verified on the OLD
      walker; suite 11,052 → 11,091; listAll error-line identical on ALL 8
      profiles; ~370 walker lines deleted. See the round-534 session note.
    - (w4) DONE round 533 (2026-07-16): checkDuplicateIdentifiers (TS2300
      family) — the lightest (d) shape: STATELESS (the two
      checkDuplicateDeclarations flags derive at the anchor) and ZERO-TYPING,
      so the migration is a pure boolean reach classifier
      ([spineDupIdReached] over [spineDupIdEdge], the deleted
      checkDuplicatesInStatement(s)/InExpr/InClassElement arms verbatim) +
      anchor dispatch at node enters running the RETAINED bounded leaf
      utilities; class/objlit MEMBER emissions dispatch uniformly at the
      member's own enter (objlit edges never admit accessors, so a reached
      SetAccessor/Constructor is class-only). Per-file top-level scans ride
      checkSpine's loop in the legacy within-file order, each wrapped in a
      currentFileLocals=null install (the legacy pass ran with it null —
      checkClassNamespacePrototypeConflict's `?: globals` consult makes it
      load-bearing). Slot-move pre-gate: error-line-identical ×8 (no residue
      coupling). 48 pins (Inv4SpineBatch24Test) ALL pre-verified on the OLD
      walker first run; suite 11,004 → 11,052; listAll error-line identical
      on ALL 8 profiles; ~215 walker lines deleted. See the round-533
      session note.
  - [x] **INV.4(e) The top-3 giants — COMPLETE round 592** (cta 586 / cpa 585 / ccet 592 all retired; checkbox reconciled round 612). checkPropertyAccess (3.66 s @ round-542
    table) → checkTypeAssignability (2.62 s) → checkCallExpressionTypes
    (2.13 s) — one at a time (together ~38% of checker-init; 458k of 595k
    getTypeOfExpression calls). **g1 SUB-PLAN (scouted round 542, in-code):
    checkPropertyAccess's walker core is compact (checkPropertyAccessInStatement
    293 lines / 22 arms + checkPropertyAccessInExpr 414 lines / 26 arms —
    the mass is in the called emission machinery, retained as leaf
    utilities). State model per the (d) templates: (1) statement-ordered
    currentLocalTypes recordings (w2 arith shape — PUSH-maintained frames,
    PASS-PRIVATE on the spine per the w2 currentParamBindingNames lesson;
    the pass also does applyBodyLocalShadowing at fn-decl/arrow/fn-expr
    boundaries per the round-447 gotcha — those calls stay in the frame
    installs); (2) contextualType downward threading with clear-before-body
    edges (w3 iany shape — push ctx with frames at exactly the legacy
    assignment edges); (3) enclosingClassType threaded param + inStaticClassMethod
    (pull-derivable from the member chain); (4) propertyAccessEnclosingNamespaces
    (its OWN stack, deliberately separate from inferenceNamespaceStack per
    the two-stacks gotcha — push at ModuleDeclaration edges); (5) per-file
    ambient currentFileLocals/currentCheckFileName/currentFlowGraph/
    currentLexicalScopes (per-dispatch install, w1 discipline — NOTE
    currentFlowGraph walk-wide is the 78-test hazard, so install around
    emissions only). SUB-STEPS, one commit each: (g1a) slot-move pre-gate —
    move the intact pass from its slot to the spine slot; this REORDERS it
    before the other two giants, so expect residue coupling (the w2
    corpus-only lesson): listAll ×8 + FULL corpus mandatory; if the
    pre-gate diffs, bisect the coupling with restore-after-pass probes
    before any migration. (g1b) pins (~50, the largest batch yet — reach
    quirks per arm; pre-verify on OLD). (g1c) the migration. (g1d) after
    g1 lands, re-measure; g2/g3 decompose the same way when reached.**
    **g1a MEASURED (round 542, both experiment directions run and REVERTED —
    the working tree keeps the legacy giant order): the giants are
    order-entangled in BOTH directions, and the couplings are CORPUS-ONLY
    (all 8 profiles sorted-error-line-identical in both experiments).
    (1) checkPropertyAccess moved before checkTypeAssignability →
    noImplicitAnyForIn loses a TS7053: the element-access receiver's type
    (`var k1 = x[i]` → `{}`) comes from the assignability walk's
    currentLocalTypes RESIDUE — the w2 residue class; fix = the pass records
    its own receiver types (w2's own-recording template).
    (2) checkTypeAssignability moved to the spine slot →
    typeArgumentDefaultUsesConstraintOnCircularDefault's TS2353 display
    flips `Test<any>` → `Test` (aliasDisplayMap/declaredTypes first-touch)
    AND relationComplexityError gains 2 FP TS2322 (relation-cache/
    complexity-budget state) — CACHE first-touch couplings against the small
    passes between the spine and slot 64, each needing a root-cause before
    the giant can move. NEXT STEP for g1: bisect WHICH intermediate pass's
    first-touch the two failures depend on (binary-search the slot
    position), then either neutralize the dependency (pass-own state /
    explicit cache warm) or migrate the giant IN PLACE (dispatch from the
    spine but buffer emissions to the legacy slot — a new template).**
    **g1a BISECT COMPLETE (round 543) — STRATEGIC FINDING, the (e) tier is
    BLOCKED ON INV.5: three targeted probes pinned both g1a' couplings to
    exactly TWO small producer passes (checkTypeParameterDefaults — its
    first-touch of the circular-default alias caches the `Test<any>`
    display; checkTemplateUnionIntersectionComplexity — its TS2859
    complexity verdicts make the giant's relation SKIP the failing
    comparison), but applying the established producer-move pattern (both
    before the spine + the giant at the spine slot) dragged a coupling
    CHAIN: 5 NEW generic-family corpus failures
    (genericsWithoutTypeParameters1, genericRecursiveImplicitConstructor-
    Errors3, noTypeArgumentOnReturnType1, conflictingTypeParameterSymbol-
    Transfer, returnTypeTypeArguments) + a harness listAll diff — the moved
    producers have their OWN upstream first-touch dependencies. Buffered
    emission does not help either: the COMPUTATION (type resolution into
    shared caches) is what is order-sensitive, not the emission. CONCLUSION:
    the giants cannot migrate by slot manipulation while nodeTypes/
    declaredTypes/aliasDisplayMap/relation caches are first-touch-order-
    sensitive. The (e) tier's prerequisite is INV.5's cache re-keying
    (`nodeTypes` keyed (node, mapper) — always valid; canonical type
    identity), which makes resolution order-INSENSITIVE. RE-SEQUENCED:
    work INV.5 next; return to (e) when the caches are order-free. All
    probe edits REVERTED — the tree keeps the legacy giant order.**
    **SUPERSEDED (rounds 555/556): the 542/543 conclusions above are STALE —
    the probe/slot-move scripts matched a COMMENT containing
    `pass("checkSpine")` and inserted the giant ~100 passes early (see the
    round-555 CLAUDE.md gotcha), so the "coupling chain" / "blocked on
    INV.5" findings were position artifacts (possibly compounded — the
    INV.5 (a)/(c)/(d1)/(e) landings since may also have genuinely
    order-freed some caches). At the CORRECT position, with exactly the two
    round-543 producers hoisted (landed round 555), ALL THREE giants
    slot-moved to the spine block corpus-green + listAll-×8-identical
    (landed round 556; legacy relative order g-cta → g-cpa → g-ccet
    preserved). g1a/slot-move pre-gates: DONE for all three. (g1b) DONE
    rounds 557/558 — 33 reach pins (Inv4SpineG1PinsTest statement arms,
    Inv4SpineG1PinsExprTest expression arms), all verified on the current
    walker.**
    **(g1c) DESIGN (round 559, from the g1b arm reads): the migration ORDER
    must be cta FIRST — the giants share a CROSS-PASS residue channel:
    checkPropertyAccess's driver does NOT reset currentLocalTypes per file,
    so it consumes checkTypeAssignability's recordings (round 542's
    noImplicitAnyForIn TS7053 finding: the `var k1 = x[i]` receiver type is
    cta residue). Migrating cpa into the spine FIRST would run its per-node
    work BEFORE the still-slot-resident cta → the residue disappears.
    Migrating cta first preserves cta-before-cpa; note per-node
    interleaving ≠ pass-after-pass for BACKWARD residue reads (a node
    consuming a LATER node's recording) — the pass-after-pass semantics let
    cpa see cta's COMPLETE final state incl. later files; audit any
    backward consumption during the cta migration (candidate remedy: the
    w2 own-recording template — each pass records what it consumes).
    Frame model per the INV.4(d) playbook: (1) per-dispatch ambient install
    of currentFlowGraph/currentLexicalScopes (NEVER walk-wide on the spine
    — the 78-test hazard; the legacy walk-wide set is reproduced by
    installing around every g1 emission); (2) fn-like scope copies
    (fn-decl/method/ctor/set-accessor/arrow/fn-expr) as push-frames at
    body enters (save map refs, install copies + populateParameterLocalTypes
    + applyBodyLocalShadowing/applyAmbiguousBlockScopedLocals), popped at
    leaves — GetAccessor bodies deliberately have NO scope copy (chunk-1
    pin); (3) contextualType as a kinded downward carrier at call-arg /
    objlit-property / arrow-body edges (the w3 template; cleared at
    fn-expr body and spread edges); (4) propertyAccessEnclosingNamespaces
    pushed at non-declare ModuleDeclaration enters; (5) enclosingClassType
    as a pull-derived member-chain context (null across fn-decl/fn-expr
    boundaries, KEPT through arrows — chunk-2 pins), with the this-param
    override at method enters; (6) inStaticClassMethod save/set/restore at
    class-member enters; (7) currentEnclosingEnum at EnumDeclaration
    enters; (8) reach quirks as classifier edges: for-INIT unreached,
    tagged-template spans unreached, interface bodies unreached,
    shorthand-property initializers unreached.**
    **(g2 = cpa DECOMPOSITION, queued round 576 — the cta migration (rounds
    560–576, m1..m3m) is COMPLETE for the emission surface; work these
    top-to-bottom, one commit each, mirroring the proven cta sequence):**
    - [x] **(cpa-m1) Legacy-side audit instrumentation** — DONE round 577. (the cta-m2a
      pattern): a test-only `cpaAuditRecord` at the top of
      checkPropertyAccessInStatement fingerprinting the threaded+ambient
      context per DIRECT statement — enclosingClassType (threaded param),
      currentLocalTypes/currentParamBindingNames/currentEnumConstrainedParams/
      currentShadowedNames (fn-boundary copies), inStaticClassMethod,
      propertyAccessEnclosingNamespaces depth, contextualType. FINGERPRINT
      HAZARD (scouted): cpa's currentLocalTypes maps name→Type, not strings
      like cta's varTypes — Type.id is resolution-order-sensitive between
      legacy-time and spine-time, so fingerprint by sorted name set +
      per-name typeToString (test-only cost), never by id.
    - [x] **(cpa-m2-prep) Close the residue channel legacy-side** — DONE
      round 578: per-file `currentLocalTypes` reset in the cpa driver + the
      element-access own-recording; corpus green + listAll ×8 byte-identical.
    - [x] **(cpa-m2) Spine-side frame skeleton** — COMPLETE round 580 (tier 2:
      unified edge-reach walker, arrow/fn-expr/ClassExpression frames,
      cpaCtxAt/cpaEctAt; full bidirectional audit equality).
      tier 1 (statements) DONE round 579 ((cpa-m2a): fn-decl/method/ctor/
      accessor frames, ns frames, loop-var overrides, per-decl-leave
      recordings, the immediate-position fingerprint gate); REMAINING
      (cpa-m2b): tier 2 — DESIGN COMPLETE (scouted round 579b, in-code):
      (i) arrow Block-body frames: 3-map copy + populate + shadowing +
      ambiguous + contextual param registration from ctx-at-arrow;
      ect/inStatic PRESERVED through arrows; (ii) fn-expr body frames:
      3-map copy + the fn-expr's OWN param semantics (annotated -> set,
      UN-annotated -> REMOVE from localTypes — not populate!) +
      destructured-name collection + contextual registration + shadowing +
      ambiguous; body walks with ect = NULL; (iii) ClassExpression member
      bodies: the tier-1 class-member frames extended to ClassExpression
      owners with a per-visit synthetic anon-class type (display
      "(Anonymous class)" — fingerprint-equal across fresh synthetics);
      (iv) ctx PULL-derivation cpaCtxAt(node): STOP-null at any statement
      edge; DEFINE at call-arg (the argCtxTypes computation: single-sig +
      B86.1b inference mapper + literal mapper; multi-sig strictSelect /
      every-overload-callable), objlit PropertyAssignment initializer
      (propCtx from ctx(O).members, non-any/error else null), SpreadAssignment
      (null), arrow EXPRESSION body (bodyCtx = single-sig return); INHERIT
      through paren/conditional/binary/array-literal/template-span/as/
      nonnull/prefix/postfix/await/spread AND NewExpression args (a legacy
      quirk: new's args inherit the OUTER ctx — no clearing); ctx is
      provably NULL at every statement dispatch (arrow Block bodies get
      bodyCtx=null; fn-exprs null explicitly); (v) the tier-2 chain test
      needs an expression-edge REACH classifier (the spineUResExprEdge
      pattern) — legacy expr-walk quirks: TaggedTemplate walks the TAG only
      (spans unreached), ForStatement INITIALIZER unreached (condition +
      incrementor reached), ForIn/ForOf initializer AND iterable expression
      unreached (ForOf's getTypeOfExpression is not a walk), decorators
      unreached, objlit METHOD bodies unreached (else -> {}),
      ShorthandPropertyAssignment unreached, CommaList unreached,
      arrow/fn-expr PARAM DEFAULTS unreached; statement-edge expression
      roots: Var initializers / ExprStmt / Return / If condition / While-Do
      condition / Switch subject + case exprs / Throw / With /
      ExportAssignment / Enum member inits / Class heritage + members.
      (the cta-m2b/m2c pattern — expect quirk-extraction cycles; the known
      quirks from the g1c design: GetAccessor bodies have NO scope copy,
      enclosingClassType is KEPT through arrows / nulled at fn-decl+fn-expr
      boundaries, contextualType clears before bodies, the pass is
      PASS-PRIVATE for currentParamBindingNames per the w2 lesson, and the
      driver does NOT reset currentLocalTypes per file — cpa consumes cta
      RESIDUE cross-file (round-542 noImplicitAnyForIn TS7053), which the
      frames must reproduce or own-record).
    - [x] **(cpa-m3…) Emission moves** — COMPLETE rounds 581-583; **(cpa-retire)
      LANDED round 585: the checkPropertyAccess legacy pass is DELETED** (the
      first giant off emit-twice; audit scaffolding removed with it).
    - [x] **(cta-retire) LANDED round 586: the checkTypeAssignability legacy
      pass is DELETED** (both migrated giants off emit-twice; audit
      scaffolding removed).
    **(g3 = ccet DECOMPOSITION, queued round 588 from the in-code scout —
    the LAST giant; mirror the twice-proven cpa sequence, one commit each):**
    - [x] **(ccet-m1) State-model scout — COMPLETE round 588b.** Additional
      facts: the expr walker has NO contextualType channel (plain recursion);
      arrow/fn-expr arms copy 2 maps (localTypes+paramBindings) + register
      own params anyType + Block-body shadowing; the ObjectLiteral arm does
      a SCOPED localTypes copy around member walks; EMISSIONS ARE
      PER-CALL-NODE (checkSingleCallExpressionTypes at CallExpressions,
      checkSingleNewExpressionTypes at NewExpressions) — so the m3 anchor is
      per-Call/New-node at ITS OWN LEAVE (the probe discipline), with frames
      supplying ambient; no emit-via-containing-walk ownership complication
      (nested-fn-body calls anchor at their own nodes under spine-maintained
      frames). DECISION: pins-first — NO fingerprint audit (CcetAnchorTest
      exactly-once pins + corpus/listAll gates; the audit pattern's quirk
      extraction is replaced by the gates, which caught all three cpa-m3a
      quirks anyway).
      ORIGINAL ITEM: **(ccet-m1) State-model scout completion + audit-or-pins decision.**
      Scouted so far (in-code, round 588): the driver resets currentLocalTypes
      per file since round 584 (residue-free); FunctionDeclaration arm copies
      currentLocalTypes + currentParamBindingNames AND pushes the fn's OWN
      TPs onto currentTypeParamScope (constraint materialization included),
      then populateParameterLocalTypes + applyCallTypesBodyLocalShadowing +
      shadowNestedFunctionNames (the M1.11 ecology — presence-only consults,
      the first-touch cache-poisoning hazard is documented in the helpers);
      ClassDeclaration arm pushes class TPs + resolves the class symbol via
      globals ?: inferenceNamespaceStack.last().exports; ModuleDeclaration
      pushes inferenceNamespaceStack via resolveModuleDeclNamespaceSymbol
      (DOTTED namespaces handled — unlike cpa's arm); the IfStatement arm
      does a SCOPED single-name union-narrowing override (save/write/restore
      around the then-walk); the VariableStatement arm ORDER-RECORDS
      annotated-callable + B98.r126 + callable-shadow entries. REMAINING to
      scout: the expr walker's arms (contextual channels?), the class-member
      dispatch, funcParams/currentFunctionParams overlay production, and
      currentEnclosingEnum/classForThis usage. DECISION POINT: rounds
      585/586 showed the audits end as deleted scaffolding — consider going
      pins-first (CcetAnchorTest exactly-once) + frame-skeleton-with-
      corpus-gates instead of the full fingerprint audit; the audit earned
      its keep on cta/cpa quirk EXTRACTION, so keep it only if the frame
      skeleton's first corpus gates diff untraceably.
    - [x] **(ccet-m2) LANDED round 589 — box checked round 671 after verifying
      in code** (`ccetSpineEnter` / `ccetSpineFileReset` are called
      unconditionally from spineEnterNode and the per-file loop, so the frames
      are always-on; its dependent (ccet-m3) landed round 591 and
      (ccet-retire) round 592, which could not have happened otherwise). The
      two in-code "inert until the anchors land" comments were stale and are
      corrected. Spec retained below for reference. FULL SPEC (round 588c
      in-code read of every arm):** CcetFrame fields: localTypes(HashMap) +
      paramBindings(HashSet) [copied at fn-decl/method/ctor/contextual-fn
      boundaries + arrow/fn-expr expr-arms], tpScope+tpAst [fn-decl pushes
      OWN TPs with interning + constraint materialization; class arm pushes
      the DECLARED class type's TPs resolved via
      globals ?: inferenceNamespaceStack.last().exports; STATIC methods POP
      the class scope but mint FRESH TPs for their own typeParameters],
      superBaseSig/superBaseType [ctor gets both, method gets Type only —
      from the per-class baseResolution computed under the class TP scope],
      nsSymbol [ModuleDeclaration arm, NON-declare only, dotted-aware via
      resolveModuleDeclNamespaceSymbol], classSym [callWalkerClassStack
      push], the method-body `this` registration [instance methods:
      currentLocalTypes["this"] = getDeclaredTypeOfSymbol(classSym)],
      GetAccessor/SetAccessor bodies walk with NO copies. Var-arm ORDERED
      recordings (interleaved with initializer walks — the cta interleave
      lesson): callable-annotated + union-of-callables + literal-union +
      callable-shadow anyType; the B246 CONTEXTUAL fn-expr channel
      (FunctionType-annotated var + fn-expr/arrow init → params typed from
      the annotation with ?-undefined unions — a frame VARIANT, replaces
      the plain initializer walk); the If-arm SCOPED type-guard narrowing
      override (resolveUserTypeGuardNarrowing at the If enter, save/write/
      restore around the then — the cta-m3i narrowing-frame precedent);
      ForIn/ForOf withForLoopVarShadow around bodies. REACH QUIRKS (differ
      from BOTH prior giants): For-INITIALIZER expressions ARE walked
      (decl initializers + expression form); param DEFAULT initializers ARE
      walked at fn-decl/method/ctor arms (BEFORE the body frame — under the
      OUTER ambient); DoStatement walks body BEFORE condition;
      declare-module bodies are SKIPPED entirely (Declare gate — unlike
      cpa); DOTTED namespace bodies are RECURSED (unlike cpa);
      heritage expressions walk UNDER the class TP scope + class stack;
      objlit arm does a scoped localTypes copy. There is also a
      maxCheckDepth recursion guard (callTypeCheckDepth) at the statement
      dispatcher — reproduce as an int-valued reach cap if fidelity
      requires (the round-535 spineArgDepth precedent). LAST FACTS (588d):
      withForLoopVarShadow copies BOTH maps but ONLY when a loop-header
      binding name COLLIDES (in globals or currentLocalTypes, not already
      in paramBindings) — colliding names are REMOVED from localTypes +
      added to paramBindings; no collision → NO copy (share). Declare-module
      subtrees need a frame `dead` flag (anchors skip; children inherit).
      The If-arm narrowing + ForIn/ForOf shadows reproduce as scoped
      override frames with restore records at the body node's leave (the
      cpa loop-var-restore mechanism). ARROW/FN-EXPR frames push at the FN
      node's enter (the copies wrap BOTH body kinds — expression-body calls
      see the registered params too). Class frames push at ClassDeclaration
      enters (tpScope + classSym + the baseResolution pair computed under
      the class scope), maps SHARED; member-body frames derive from them.
      Implementation staging: (ccet-m2) frames always-on, gates must stay
      IDENTICAL (no emissions move yet — any diff is a first-touch
      coupling to bisect); then (ccet-m3) per-call anchors + marks + pins.
    - [x] **(ccet-m3) LANDED round 591** (merged; the gap-signature gate made
      the interleave FP order-free) + **(ccet-retire) LANDED round 592 — ALL
      THREE GIANTS OFF EMIT-TWICE.** (history: round 590 blocked state:) per-Call/New/TaggedTemplate anchors at
      leaves + the full per-edge reach classifier + legacy marks +
      CcetAnchorTest (8/8, incl. the static class-TP skip-gate pin) + the
      re-enabled decl recordings (the round-589 flip is MOOT under anchors:
      the legacy verdict truncates). Corpus GREEN (11,347/0). BLOCKER: ONE
      interleave FP — the cta return anchor at services.ts:1327 (the
      objectAllocator objlit vs ObjectAllocator) sees CCET-WARMED caches
      (per-node interleaving ≠ pass-after-pass, the round-559 warning) and
      resolves TP-carrying member types (`() => NodeObject<TKind>`) → a
      TS2322 the legacy order never produced (services/server/harness +1).
      A typeContainsForeignTypeParam construct-sig extension did NOT
      suppress (on the branch; possibly resolvedReturnType null at gate
      time, or a non-gate emitter). NEXT WINDOW: (1) identify the emitter
      with the round-472 Diagnostic-init probe keyed (2322, the 1327 start
      offset) on the services profile; (2) fix the gate's REACH or gate
      that emitter (order-free-verdict discipline, both cache states
      silent); (3) structural fallback: defer ccet anchors to a per-file
      second walk. Then merge the branch + gates + (ccet-retire).
      ORIGINAL: **(ccet-m3…) Emission moves** with the leave-dispatch discipline
      (cpa's probe lesson: anchor at statement/expression LEAVES) + the
      recorded-set truncation, then **(ccet-retire)** via the round-585
      experiment template (no-op the dispatch → gates → delete).
  - [x] **INV.4(f) CLOSED round 599 — both wins are measured dead-ends at
    the current cost structure** (f1 memo: the servable calls are cheap;
    f2 fold: confirm-once tax + epoch churn → noise); the real INV.4 win
    was the retirements (−13% wall) + ONE authoritative walk. Revive the
    memo designs after INV.5's canonical types. ORIGINAL: **The two unlocked soundness wins.** Once one authoritative
    walk state exists: the per-node expression-type cache (594,779 calls over
    ~221,844 distinct nodes = ×2.6 recompute), and flow narrowing folded into
    reference typing once (84,469 depth-0 walks, 68% from property access).
    Re-measure against the ≤10 s single-threaded compiler-profile target.
- [x] **INV.5 Canonical types + explicit instantiation — SUBSTANCE COMPLETE round 604** (interning (a), mapper flip (b2), context-keyed nodeTypes (c), budget (d1), generic gate + pin sweep (e) all landed; residuals are deferred/demoted/blocked: (bN) behind the frame redesign, (c2) cosmetic, (d2) hygiene — checkbox reconciled round 612) (absorbs M5.2/M5.3;
  NOW THE ACTIVE ARC ITEM — the round-543 g1a bisect proved the INV.4(e)
  giants are blocked on exactly this: first-touch-order-sensitive shared
  caches). Decomposed round 544, one commit each, every step suite +
  listAll-×8 gated:
  - [x] **INV.5(a) Union/intersection interning.** DONE round 545 (see the session note — landed with the ternaryOfArrayLiterals gate extension after the round-544 near-miss). `getUnionType` (Checker.kt
    ~103k, "mints a fresh Type.Union(sorted) with a new id — does NOT
    intern") + `getIntersectionType` intern by sorted member-id key (the
    `referenceCache` pattern; preserves display member order by keeping the
    FIRST-built instance). Directly serves order-insensitivity: an interned
    union has the same id regardless of which pass builds it first. KNOWN
    HAZARDS (from the gotcha corpus): (1) aliasDisplayMap is id-keyed — an
    interned union SHARED across contexts must not receive one context's
    alias name (the singleton-intrinsic display-corruption hazard
    generalized; union alias display already has the structural
    `unionAliasStructural` map — union registrations in aliasDisplayMap may
    need to move there entirely); (2) the id-only dedup gotcha (duplicate
    structurally-identical members) is UNCHANGED by interning — do not
    conflate the two; (3) the round-424 structural wash-gate workaround
    stays correct (it stops RELYING on fresh ids but never assumed them);
    (4) relation-cache/cycle-stack behavior only gains hits (same-id
    identical pairs). Verify: suite + listAll ×8 + re-run the round-542/543
    probe experiments to measure how much of the giant entanglement
    dissolves.
    **FIRST ATTEMPT (round 544, REVERTED): a minimal interning of both
    canonical constructors (CheckerState caches by member-id key; unions by
    sorted order, intersections in-order) measured CORPUS 100% GREEN
    (11,243/0) with EXACTLY ONE new FP, identical on all 8 profiles —
    watch.ts:533:19 TS2322 `(string | DiagnosticMessage)[]` ⊄
    `DiagnosticAndArguments` (the round-446 VARIADIC-TUPLE alias family).
    Remarkably contained for a change canonicalizing every union in the
    program — the hazard list's display fears did NOT materialize; the one
    regression is a relation/suppression path keyed on union identity
    (candidates: a relation-cache FALSE shared across contexts, an id-keyed
    side channel hitting a shared instance, or the
    arrayLiteralSatisfiesTupleTarget suppression's engine fallback). NEXT:
    root-cause with a targeted probe (temporary Diagnostic-init stack-trace
    probe keyed on code=2322 + the watch.ts:533 start per the round-472
    recipe), fix the one path, re-land.**
    **PROBE RE-RUN (round 546, post-(a)): the g1a' couplings PERSIST under
    canonical union identity (both typeArgumentDefaultUsesConstraintOn-
    CircularDefault and relationComplexityError still fail with the giant at
    the spine slot; probe reverted). The residual first-touch sensitivity is
    NOT union-identity — it lives in declaredTypes/aliasDisplayMap
    resolution TIMING (the Test<any> display) and the relation/complexity
    verdict state — i.e. exactly the (b)/(c) territory (explicit mappers +
    keyed nodeTypes). The INV.5 sequencing holds; continue with (b).**
    **PROBE RE-RUN 2 (round 548b, post-(c)): both g1a' couplings STILL
    persist — the residual first-touch state is specifically (1)
    `declaredTypes` (SYMBOL-keyed alias resolutions — the Test<any>
    display; a different cache from nodeTypes) and (2) the TS2859
    relation/complexity verdict state. The giant unblock therefore needs a
    declaredTypes context-keying sibling of (c) plus a
    complexity-verdict-state audit — queue them as (c2)/(c3) when
    returning to the giants; the two probe tests
    (typeArgumentDefaultUsesConstraintOnCircularDefault,
    relationComplexityError) are the standing acceptance gate for any such
    step. Probe reverted.**
    **(c2) SCOUTED (round 549): the Test<any> coupling is a
    LAZY-MATERIALIZATION first-touch, not a cache-keying one —
    `Type.TypeParam.constraint`/`.default` are MUTABLE fields set at 8+
    scattered sites by whichever pass resolves the TP first (the
    typeParamInternCache shares the instance program-wide), so a no-args
    generic reference instantiates with defaults ONLY IF some earlier pass
    already materialized `.default`. DESIGN: EAGER TP materialization — one
    fixed init step (after globals merge, before any check pass) resolving
    every TypeParameter's constraint/default under its declaration's
    sibling-TP scope (the checkTpListDefaults scope-building pattern),
    making the fields order-free; the 8 lazy setters become no-ops
    (already-set guards) and eventually delete. Acceptance: the two probe
    tests + full gates.**
    **(c2) HYPOTHESIS FALSIFIED (round 549b, attempt REVERTED): a minimal
    eager top-level TP materialization (constraint+default fields filled at
    a fixed init point) did NOT dissolve the probe failure — the coupling's
    mechanism is the EFFECTIVE-default-via-constraint computation inside
    reference instantiation (the probe test's own name:
    typeArgumentDefaultUsesConstraintOnCircularDefault — tsc substitutes
    the CONSTRAINT when the default is circular), i.e. resolution-path
    state beyond the raw fields. Next root-cause step: instrument WHAT
    the legacy checkTpListDefaults slot changes that the later TS2353
    display consumes (candidate: the referenceCache entry for Test<any>
    minted during its constraint-relation checks, which the annotation
    resolution then reuses vs mints bare). Deferred behind (b2+)/other
    INV.5 work — the display-only coupling is cosmetic, not semantic.**
  - [x] **INV.5(b) Explicit mapper objects — installer flip COMPLETE round
    604 (b2a-b2d4): 87 write sites → 4; the survivors are the spine frame
    LIFO writers (restore-at-leave — not region-formable; the designed
    residual until frames carry mappers). (bN) ambient-field REMOVAL
    stays open behind that frame redesign.** Replace the ambient
    `currentTypeAliasArgs`/`currentTypeParamScope` instantiation contexts
    with an explicit mapper threaded through the resolution entry points —
    the enabler for (c). MEASURED SURFACE (round 546): 87 write sites in 34
    functions (top installers: checkCallTypesInStatement ×7,
    walkStmtsForTypeParamCasts ×6, checkReturnAssignability /
    resolveGenericPropertyTypeWorker / getTypeFromTypeReference /
    resolveInterfaceMembersCore / checkConstraintsInStatements ×4 each) +
    ~90 read sites inside the resolution family. DECOMPOSITION (bridge
    pattern — each step suite + listAll-×8 gated): (b1) a `TypeMapper`
    value (aliasArgs + tpScope + a stable fingerprint for cache keying) +
    an optional `mapper` param on `getTypeFromTypeNode`/
    `getTypeFromTypeReference` DEFAULTING to the ambient (behavior-
    identical bridge; the `cacheable` gate reads the param); (b2+) flip
    installer families to pass explicitly — (b2a) DONE round 549c: all 6
    simple aliasArgs installers flipped via aliasMapper/layeredAliasMapper
    (b2b) DONE round 549d: the remaining 3
    aliasArgs installers flipped too — alias substitution ~93.8k,
    constraint-retry ~89.6k, mapped-type per-key ~140.4k; the aliasArgs
    ambient is now single-writer (the bridge); tpScope families next);
    (b2c/b2c'-''', rounds 550a-550d) DONE: ALL resolution-internal tpScope
    installers flipped to the REGION form (`withInstantiationContext(
    scopeMapper(...)) { ... }` — inline, non-local returns preserved):
    resolveGenericPropertyTypeWorker (outer + inner method scope),
    resolveBaseTypesLazy, resolveInterfaceMembersCore (sig + index), the
    getTypeOf* lazies, buildBaseConstructorSignatureForSuper,
    buildSignatureForFunctionLikeTypeNode, reresolveSigParamsUnderClassScope,
    getTypeFromTypeLiteral's method branch, checkConstraintsForTypeArgs.
    REMAINING (deliberately deferred): the walker-level installers (die
    with INV.4(e)), the dual-ambient-field installers
    (checkConstraintsInStatements + currentTypeParamDecls;
    checkMixinClassInStatements + mixinValueScope), the 84067 interleaved
    implicit-any site, and the paired pushFunctionTypeParamsScope; (bN)
    remove the ambient fields (blocked on those). NOTE (c) only needs the mapper AT THE CACHE CONSULT — it can
    start right after (b1) with ambient-bridged installers still in place
    (key = (nodeId, mapper.fingerprint); the context-bypass `cacheable`
    rule dies there).
  - [x] **INV.5(c) `nodeTypes` keyed (node, mapper) — LANDED round 548
    (option iii — the conservative pinned-checking-file gate; see the
    session note; widen the gate as INV.3(d) retires checking-file-dependent
    resolution, and cache the fingerprint per-install if the +5.4%
    single-run wall cost proves real).** Kills
    the context-bypass rule and the first-touch hazard class outright (the
    round-543 blocker). DESIGN (scouted round 547b — the surface is TINY,
    exactly 2 use sites inside getTypeFromTypeNode): a SECOND cache
    (`mappedNodeTypes`) for context-bearing resolutions keyed by an
    IDENTITY node key (=== equality with nodeId-based hashCode — cross-file
    nodeId collisions only share buckets, never results; unindexed nodes
    skip) + a context fingerprint (ns-stack symbol ids + sorted tpScope
    name:id pairs + sorted aliasArgs name:id pairs). The existing
    empty-context cache and its isPerFileDependentRefNode bypass stay
    untouched (identity keys make that hazard structurally impossible in
    the NEW cache). **SOUNDNESS CONSTRAINT (the reason this is not yet
    implemented): context-bearing resolutions ALSO depend on the CHECKING
    file — `currentFileLocals?.get ?: globals` consults are
    checking-file-keyed (the conflation ecology), so a fingerprint that
    excludes that dimension re-creates the first-touch disease inside the
    cache. Either (i) include a reliable checking-file identity in the
    fingerprint (currentCheckFileName is a stale-prone proxy — audit the
    setters first), or (ii) wait for INV.3(d)'s completion to eliminate
    checking-file-dependent resolution, or (iii) start with a
    CONSERVATIVE fingerprint that additionally requires
    currentFileLocals === the node's owning file's locals (node-keyed
    consult, cheap via owningSourceFile with a per-file memo) and skips
    caching otherwise.** Option (iii) is self-validating and incremental —
    preferred.
  - [x] **INV.5(d) — (d1) budget DONE round 552; (d2) DEMOTED to hygiene round 611 (checkbox reconciled round 612).**
    **(d2) DEMOTED round 611 (evidence-based): the round-598 depth-0
    attribution puts the ENTIRE relation family at ~927ms — the (d2)
    allocation redesign is no longer a perf lever (the levers are the
    walks + typeOfExpr, both blocked on canonical types). Remaining (d2)
    value is hygiene only: `resolvedPropertyTypes` caches under the
    first-touch ambient scope (a context-keying hole like the pre-548
    nodeTypes) and never caches null results. Re-open only if a
    correctness drift traces here.**
    Delete `resolveGenericPropertyType` fresh-minting + its depth-4 OOM cap
    (the per-recursion-level cache-miss gotcha). **(d1) DONE round 552: the
    depth-4 cap is DELETED — replaced by the per-top-level-relation
    instantiation budget + the param-side foreign-TP gate in
    tryEmitObjectVsNamedUnionArg (see the session note). Remaining: the
    member-table-on-reference allocation redesign ((d2), optional now that
    the budget bounds allocation) and the fresh-minting deletion.**
    **CAP-LIFT PROBE FALSIFIED (round 551, reverted): removing
    `relationDepth < 4` with (a)-interning + the (ref.id, prop.id) memo in
    place still KILLS performanceComparisonOfStructurallyIdentical-
    InterfacesWithGenericSignatures — the deep-stack thread dies after ~20 s
    (OOM → NPE at runWithDeepStack's result unwrap). The blowup is BREADTH,
    not depth: each comparison level mints genuinely NEW (target, args)
    references (growing arg shapes), so the memo never hits and the
    deeply-nested 5-occurrence heuristic (which fires at relation ENTRY)
    doesn't bound the per-level member/signature instantiation between
    bails. The real (d) fix is tsc-shaped: an instantiation-count budget
    (tsc's instantiationDepth/instantiationCount → TS2589) plus member
    tables cached ON the reference, NOT a cap lift. Keep the depth-4 cap
    until then.**
    **BUDGETED-LIFT PROBE (round 551b, also reverted): a per-top-level-
    relation budget of 2,000 fresh worker computations (reset at depth-0
    relation entry, consumed on memo miss, raw fallback on trip) TAMES the
    perf-bomb — corpus fully green 11,252/0 — but exposes exactly ONE new
    FP on all 8 profiles: program.ts:2924 TS2345 `(readonly Diagnostic[] |
    undefined)[]` ⊄ `T[][] | readonly (T | …)[]` (tsc's flatten<T> — the
    documented M3.1 masked gap: tsc infers T, we don't, and the old
    depth-≥4 trivial-pass masked it). A TP-free gate on DEEP substitution
    results does NOT kill it — the outcome flips inside the relation
    (target side), not at the substitution result. VERDICT: the cap
    deletion is blocked on generic inference (M3.1) / the (e)-era
    engine-opening work, not on allocation strategy — sequence (d) with
    (e), and consider a param-side foreign-TP bail at the call-arg
    emission as the enabling slice (corpus-gated; the round-431 gate
    family's rationale applies verbatim to un-inferred PARAM types).**
  - [x] **INV.5(e) Open `canUseTypeEngine`'s generic gate; delete superseded
    pin walkers** (suite-gated per deletion). DONE round 600: sweep verdict
    15/16 load-bearing, checkGenericFnTypeBipartition deleted. Then RETURN to INV.4(e).
    **FIRST HALF DONE round 553: the hasUnresolvedTypeParams skip is
    DELETED (corpus + listAll ×8 identical; the Box<T>-vs-Box<string>
    false negative now fires — Inv5GenericGateTest). Remaining: the
    pin-walker deletion sweep.**

- [x] **INV.6 Parallelism — Phase 0 CLOSED round 609** (6a-6d1: --workers 2 = −17% wall, output sorted-identical, all-8-profile partition equivalence; w4 flat at the per-worker redundancy ceiling — Phase 1 shared frozen collectors is the reopener, gated on an immutability audit; (6e) parallel emit deferred: emit workers would race the shared checker's lazy caches, and benches are --noEmit). Share-nothing checker workers per
  `docs/parallel-caching.md` (trivially partitionable once INV.4 gives a per-file
  check entry); parallel emit on Default + IO write sink; deterministic partition +
  merge via the existing diagnostic sort. Structured concurrency from INV.1.
  - [x] **(6a) The spine partition seam** — DONE round 605: `assignedFileNames`
    gates both spine per-file loops; sequential-equivalence contract pinned by
    SpinePartitionEquivalenceTest.
  - [x] **(6b) Profile-scale equivalence A/B** — DONE round 606:
    `--partitionCheck N` harness; EQUIVALENT on all 8 profiles (w=2) + the
    two stress profiles (w=4). Zero divergences — (6c) unblocked.
  - [x] **(6c) The parallel driver** — DONE rounds 607-608 (6c0 thread-local
    id sequences + deep-stack handoff; 6c1 runInDeepStackWorkers +
    `--workers N`). Measured: w2 −14% wall, w4 flat (per-worker redundant
    fixed cost — see the round-608 note); output sorted-identical to
    sequential.
  - [x] **(6d1) Widen the partitioned region** — DONE round 609: 193
    emission-pass loops on `checkedResults` (318 pure collectors stay
    program-wide); all-8-profile equivalent; w2 −17%, w4 flat. Deeper
    widening = Phase-1 shared frozen collectors (immutability audit) —
    queue that only after INV.5 canonical types or on a >4-core box.
  - [ ] **(6e) Parallel emit** on Default + IO write sink (INV.1's Flow
    foundation; no dashboard delta expected — benches are --noEmit).
- [x] **INV.7 Productization — CLOSED for queue purposes (checkbox reconciled
  round 687): 7a/7c1/7d1/7d2/7d3 all landed and the only remaining child, (7b)
  release binary + native bench row, is PARKED-BY-OWNER.** (absorbs M5.5/M5.6). Native re-enable (the big-input
  GC inversion should largely dissolve post INV.4/5); watch mode driven by a
  file-event Flow; `.tsbuildinfo`-style incremental reuse.
  - [x] **(INV.7c1) `--watch` minimal watch mode** — DONE round 613 (full
    rebuild per debounced change batch; fileEvents Flow expect/actual;
    end-to-end verified, 46ms warm rebuild). Incremental reuse is (7d).
  - [x] **(INV.7d1) Watch-mode incremental recheck** — DONE round 614
    (reverse-dependency closure over the INV.6 partition seam; full-rebuild
    bails for non-local changes; --watchVerify field gate; equivalence
    pinned by WatchIncrementalTest).
  - [x] **(INV.7d2) The shared-name residual bail** — DONE round 615
    (sharedNameFiles: lib-global KNOWN_GLOBALS ∪ script top-level names;
    bidirectional bail via eligibility + outcome validation; +2 pins).
    Real-lib names outside the curation stay on the --watchVerify net.
  - [x] **(INV.7d3) Cross-process `.tsbuildinfo` persistence** — DONE round
    617 (owner approved the generateBuildInfo build change 2026-07-19):
    `XTSC_BUILD_ID` (git sha, `.dirty`/`unknown` never persist nor reuse)
    stamps `tsconfig.xtsbuildinfo`; cold start hash-validates inputs (incl.
    every `.json` config read via RecordingVfs) and runs the (7d1) closure
    protocol for the changed set under `--incremental --noEmit`; new files
    caught by the outcome shape check. TsBuildInfoTest (+11).
  - [x] **(INV.7a) linuxX64 re-enabled** — DONE round 610: compiles/links/runs
    byte-correct (compiler profile = the exact 46-error floor, 196s debug
    binary; smoke 82ms). EpochMap/Set now composition (K/N HashMap is final).
  - [ ] **(INV.7b) Release binary + native bench row.** PARKED-BY-OWNER
    (round 617, 2026-07-19: "we can switch it off for now"). History:
    BLOCKED-ON-RESOURCES at round 610b — the optimizing link OOM-kills the
    daemon on the 7.7GB box (twice, incl. -Xmx5g + daemons stopped). If ever
    revived: re-attempt on a ≥16GB builder; the debug binary carries
    correctness meanwhile.

Numeric targets (proposed, doc § 6): post INV.4/5 single-threaded compiler profile
≤ 10 s (≈ JS tsc) + harness RSS ≤ 1 GB; post INV.6 compiler ≤ 5 s on 4 cores;
INV.7 stretch: native cold ≤ 2× tsgo.

### Post-v1 backlog — the "any TypeScript project" horizon (UNPARKED round 679)

**UNPARKED 2026-07-25 (round 679).** v1 was declared at round 481 and was
RE-VERIFIED at HEAD this round, 200 rounds later: all 8 profiles exit 0, emit
EVERY input file (81/81, 312/312, 84/84, 78/78, 274/274, 252/252, 80/80,
88/88), zero crash frames, and every one of the 140 diagnostics is a missing
Node ambient (`process`/`Buffer`/`require`/`NodeJS`/`console`) under a
`"types": []` tsconfig — i.e. config/env artifacts, not compiler faults. With
EP.2c skipped by the owner and the remaining M5/INV items parked or
zero-value on this box, **this section is now the live queue**.

**SUPERSEDED 2026-07-26 (round 716) — THIS SECTION IS NO LONGER FIRST.** Owner
directive: "do anything needed … to increase the performance", followed by "how
should we proceed to match the tsc performance on a single thread". The PERF
section above is the live queue again, and **(DISPATCH.1) is the top unchecked
item**; work it before anything here. This section stays OPEN and unparked — it
is not cancelled, and it holds the only known SILENT-WRONG-ANSWER defect in the
codebase (M2.4: with `"lib": ["dom"]` a browser project's DOM code compiles
CLEAN and entirely unchecked) plus the "real project" gaps (declaration emit,
sourcemaps, JSX, nodenext). **The trade being made is explicit: matching tsc's
speed is being prioritised over making the compiler usable on non-tsc projects.**
Revisit when the perf arc reaches its staged target or stalls.

(Historical note: the loop was to skip this section until v1 landed. It landed
at 481; the section stayed parked ~200 rounds because nothing re-read the
condition. Worth remembering as a queue-hygiene failure mode in its own right.)

- [x] **M4.8 DONE round 680 — `/// <reference path|types>` pulls files into the
  program.** Resolution-KIND confusion: the parser recorded directives into
  `moduleSpecifiers`, which the crawl resolves as MODULE specifiers, but a
  `path=` target is a file path relative to the referencing file and a `types=`
  target is a type-root package. Split onto `SourceFile.referencedPaths` /
  `referencedTypes`; the crawl resolves each correctly and TRANSITIVELY. TS6053
  needed no change (the checker asks whether the target is in the program, so it
  goes silent exactly when resolution succeeds — pinned both ways). Measured with
  `@types/node`: program 79 → 146, TS2591 43 → 13. Dashboard untouched (all 8
  profiles identical in errors AND program size); suite 12,598/0/3 (+19 pins).
- [x] **M4.9 DONE round 686 — 30 → 13 on the `"types": ["node"]` profile, and
  every survivor is the env-legit TS2591 class** (a file using
  `require`/`process` without importing node types — the same class the eight
  dashboard profiles carry by design). ONE cause behind the whole residual:
  `mergeModuleAugmentations` published every export of a FILELESS `declare module
  "spec"` into `globals`. Right for an AUGMENTATION (globals is its only
  visibility channel); wrong for the identical syntax in a SCRIPT `.d.ts`, which
  DECLARES the ambient module — those members are reachable only through an
  import of the specifier. The damage was not a stray name but a WRONG WINNER:
  the published member outranked a file's own import alias, so tsc's sys.ts
  resolved its own `WatchOptions` to `@types/node`'s `fs.WatchOptions` and every
  downstream check disagreed with the source. Gating on the declaring file being
  an external module (tsc's own augmentation-vs-declaration distinction;
  `moduleFiles` is already populated before this pass) cleared TS2353×7,
  TS2339×3, TS2322×2, TS2345, TS7006, TS1345, TS2709 and TS2558 at a stroke.
  **Found by discrimination, not search:** a four-file repro, then a probe type
  declared ONLY inside the ambient module — it drew TS2304 (not in the TS2304
  walker's scope) while its MEMBERS resolved (in the type-position scope), which
  located the split in one run. Gates: suite 12,651/0/3 (+4 pins), `--listAll` ×8
  byte-identical (the dashboard's `"types": []` keeps it off this path).
  Round-681 part 1 (below) landed `skipLibCheck` and the parameter-shadows-
  namespace bail. **A NINTH dashboard profile for `"types": ["node"]` is still
  worth adding** — do NOT alter the existing eight.
- [ ] ~~M4.9 (part 1, round 681)~~ — Landed:
  `skipLibCheck` is now honoured (it was parsed and never consulted — TS7008×15
  + TS7010×2 were being reported against DefinitelyTyped's own declaration
  files), and a PARAMETER now shadows a same-named namespace that reached
  globals from an ambient module body (TS2339×18 → 3; tsc's
  `formatJSDocLink(link: …)` vs `fs.d.ts`'s `export namespace link`). REMAINING
  on that profile: 13 TS2591 (`require`/`process` where the file references node
  types without importing them), **7 TS2353** (`fs.WatchOptions` vs the
  compiler's own `WatchOptions` in an object literal — the next-largest
  cluster), 3 TS2339, plus TS2322×2/TS7006/TS2709/TS2558/TS2345/TS1345
  singletons. Repro: copy the profile tsconfig with `"types": ["node"]`
  (fixture gitignored at `build/bench/tsc-project-637d5746/node_modules/@types/node`).
  Consider a NINTH dashboard profile to track it — do NOT alter the existing
  eight, whose `"types": []` is deliberate.
- [ ] ~~M4.9 (original)~~ — **The gaps `@types/node` exposes once it loads** (found round 680,
  directly downstream of M4.8). With `"types": ["node"]` on the compiler profile
  the missing-ambient errors mostly clear (TS2591 43 → 13) and what remains is
  REAL, previously masked by the unresolved names: **TS2339×18** (e.g.
  `Property 'kind' does not exist on type 'typeof link'`), **TS7008×15**
  (implicitly-any members), **TS2353×7** (`'watchFile' does not exist in type
  'WatchOptions'` — our `fs.WatchOptions` vs the compiler's own `WatchOptions`),
  TS2322×2, TS7010×2, TS7006, TS2709. Reproduce by copying the profile tsconfig
  with `"types": ["node"]` (fixture already at
  `build/bench/tsc-project-637d5746/node_modules/@types/node`, gitignored).
  Consider adding it as a NINTH dashboard profile so the numbers are tracked —
  but do NOT change the existing eight, whose `"types": []` is deliberate.
- [ ] ~~M4.8 (original)~~ — **`/// <reference path|types="…" />` must ADD files to the program**
  (found round 679; the single highest-impact gap for "any TypeScript project").
  Our handling — `TypeScriptCompiler.kt` ~2168, gated on
  `includeReferencePathDeps`, i.e. `outFile` only — merely ORDERS files ALREADY
  in `allTsFileNames`. tsc's `processReferencedFiles` **pulls the referenced
  file into the program**. Consequence, measured: `@types/node`'s `index.d.ts`
  is 64 `/// <reference path>` lines and little else, with `globals.d.ts`
  declaring `var process` and `namespace NodeJS` — so enabling
  `"types": ["node"]` on the compiler profile took the program from 78 to just
  **79** files and left all 46 diagnostics standing. Every real Node project is
  affected the same way. Fixture already installed (gitignored) at
  `build/bench/tsc-project-637d5746/node_modules/@types/node`; the probe config
  was a temporary `tsconfig.node.json` (deleted — recreate by copying the
  profile tsconfig with `"types": ["node"]`). The dashboard tsconfig
  deliberately keeps `"types": []` and our handling of THAT is correct per tsc
  semantics — do not "fix" the baseline; add a separate profile if one is wanted.
- [ ] ~~M2.4 DOM libs~~ — **SUPERSEDED round 716 by (LIB.1) at the top of the queue** (owner: "yes, please fix it"; the owner-gated lib-shipping decision it was blocked on is now granted). Body kept for its measurements.
- [ ] ~~M2.4 (original)~~ — RE-SCOPED round 687 by measurement: the premise is wrong
  and there is a SILENT-WRONG-ANSWER bug underneath it.** The item asked to
  measure dom.generated.d.ts's parse/bind cost. That cost is **not measurable
  because the DOM libs are NOT SHIPPED**: `RealLibFiles` contains no
  `dom.generated` / `dom.iterable.generated` / `webworker*` entry (its only "dom"
  occurrences are `/// <reference lib="dom" />` lines inside OTHER libs' text).
  **What `"lib": ["dom"]` does today:** `RealLibResolver.resolve` records the file
  in `Resolution.unavailable` and the final `ordered` list filters it out —
  and `Resolution.unavailable` is **never consumed outside RealLibs.kt**, so
  nothing is reported. Measured consequence on a 3-line program: `HTMLElement`
  resolves, `document` resolves, and `e.definitelyNotAMember` on an `HTMLElement`
  parameter compiles **CLEAN** — i.e. a browser project gets a green build with
  its DOM code entirely unchecked. (Without `dom` in `lib` the same name draws
  TS2552 "Did you mean 'HTMLLIElement'?", because DOM names are in KNOWN_GLOBALS
  for the TS2304 walker — which is why adding `dom` LOOKS like it worked.)
  **Round 688 CORRECTION — follow-up (i) was attempted and REVERTED as dead
  code, which uncovered the bigger fact: `useRealLibs` defaults to FALSE and
  NOTHING in the project path turns it on** (`ProjectCompiler`/`TsConfigLoader`
  never set it; the only writer is the `usereallibs` test directive). So the
  entire real-lib machinery — `RealLibResolver`, `RealLibSnapshots`,
  `Checker.bindRealLibs`, and `Resolution.unavailable` with it — is exercised
  ONLY by tests that opt in. **Every real project build, including all eight
  dashboard profiles, runs on the EMBEDDED `BUILTIN_LIB_SOURCE`.** A diagnostic
  wired into `bindRealLibs` therefore never executes; it was implemented, seen
  not to fire, and reverted rather than landed. Two further facts the attempt
  established, both needed by whoever picks this up: **(a) `unavailable` must not
  be the key** — a `full` default lib (`lib.d.ts`, `lib.es2020.full.d.ts`)
  transitively references the DOM/host files, so an ordinary target-default
  resolution has a non-empty `unavailable` and must stay silent; only a name the
  USER wrote is reportable, which needs a new field, not the existing one (a
  working `unavailableRequested` implementation is in the round-688 reflog if
  wanted). **(b) the corpus blocks the embedded-path fix**: 259 corpus cases
  carry `@lib:`, of which **23 request `dom`** plus `webworker`×4,
  `webworker.iterable`×2, `webworker.asynciterable`, `scripthost`,
  `esnext.temporal`, `esnext.intl` — all unshipped, all currently GREEN, so
  reporting on the embedded path breaks ~30 baselines that were generated by a
  real tsc which HAS those libs.
  **So the real follow-ups are, in order:** (i) **decide what real project builds
  should use for libs at all** — the embedded lib is a curated subset while the
  shipped real libs are unreachable outside tests; that mismatch is the root, and
  it is a design decision, not a patch; (ii) **ship the DOM/webworker/scripthost
  sets** — changes the real-lib GENERATION in build.gradle.kts and adds ~1 MB of
  generated source, so **owner-gated**; (iii) only then is the original
  parse/bind cost question answerable, and only then can an unshipped-lib
  diagnostic be both correct and reachable.
  **Method note worth keeping:** the first control I ran — "does `HTMLElement`
  resolve with `dom` in lib?" — PASSED, and a clean 5-pair interleaved A/B then
  showed the cost inside the noise band. Both were measuring nothing. When an
  unknown name degrades to `any`, name resolution proves nothing; the control
  that decides is a **MEMBER probe** (`e.notAMember` must error).
- [ ] **M3.0 Conformance generator extension — INFRASTRUCTURE DONE round 690; FOUR
  categories adopted (round 695); the remaining categories are measured, not guessed.**
  **Round-695 redness table** — twelve candidate categories added to the allowlist in ONE
  suite run (+236 tests, 91 failures), then all but the tractable ones reverted. Failures
  per category, so a future round can pick by cost instead of re-measuring:
  `es6/defaultParameters` **0** · `es6/restParameters` **1** · `expressions/commaOperator`
  **2** · `expressions/asOperator` 5 · `types/any` 6 · `types/conditional` 8 ·
  `types/nonPrimitive` 9 · `statements/labeledStatements` 9 · `types/typeAliases` 9 ·
  `expressions/contextualTyping` 9 · `expressions/typeSatisfaction` 12 ·
  `expressions/optionalChaining` 21. The first three were adopted; the rest are each a
  round's worth of gap work. Two caveats worth carrying: `statements/labeledStatements`
  is 9 failures from only 8 files (proportionally the reddest), and its failures include
  **JS-emit** subtests, which `conformanceDeferredErrorBaselines` cannot defer — an emit
  gap must be FIXED before that category can land. Measuring a batch this way costs one
  ~7-minute run and is much cheaper than adopting a category and discovering it is red.
  Extend `generateTypeScriptTests` with a
  per-category allowlist for `tests/cases/conformance/` (keep all tsgo set-B
  filters). Each category lands only when its failures are triaged into queue
  items — never leave a category half-red without notes. Owner approval
  (2026-07-02) stands.
  **Verified round 689 (do NOT re-derive):**
  1. **The sources ARE readable offline.** `typescript-repo` is a BLOBLESS partial
     clone (`remote.origin.partialclonefilter = blob:none`, `promisor = true`) and
     its sparse checkout lists only `tests/cases/compiler` +
     `tests/baselines/reference` — so this looked network-gated. It is not: a
     `git cat-file -p HEAD:tests/cases/conformance/…` probe returns content, so
     the needed blobs are already local.
  2. **Baselines need no work** — the sparse checkout already takes the WHOLE
     `tests/baselines/reference`, which is flat and holds the conformance ones.
  3. **The variant-baseline convention is ALREADY implemented.** Conformance uses
     `name(target=es5).errors.txt`; the generator's `computeVariations` /
     `paramBaselineName` produce exactly `name(key=value).ext`.
  4. **ZERO basename collisions** between ALL of conformance and the 6,537
     compiler cases, so the generated flat backtick function names need no
     disambiguation.
  5. **Sizing for the M3.1-matching categories:** `expressions/functions` **7
     files** (the right first category), `types/typeParameters` 46,
     `types/typeRelationships` 263.
  **The three edits:** (a) `sparsePaths` (build.gradle.kts ~271) += the
  allowlisted category dirs; (b) the `testFiles` collection (~547) currently
  `testsDir.listFiles { flat }` must also walk the allowlisted conformance dirs
  RECURSIVELY (categories have subdirs); (c) the generated bodies hardcode
  `Path("${'$'}typeScriptCasesDir/<name>.ts")` where `typeScriptCasesDir` is
  `tests/cases/compiler` (TypeScriptTestSupport.kt:38) — a conformance case needs
  its own path, so emit a per-file relative path or add a second constant.
- [x] **(M3.0-gap-1) DONE round 692 — `arrowFunctionContexts` passes and is
  un-deferred.** Three defects, one per round-690 triage line. The TS2403 ×2 FALSE
  POSITIVE was a generic arrow mistyped `<T>(n: T) => any` (round 691: the arrow's
  own type parameters were interned only when the `Signature` was built, i.e.
  after the return had been inferred). The two MISSING codes landed this round:
  TS18033 fired only for a STRING-typed computed member, so a function-valued one
  (`enum E { x = () => 4 }`) drew nothing — extended to a syntactically
  arrow/function-expression initializer, FP-safe by construction since such a
  member can never satisfy the numeric domain; and the TS2332 walker skipped arrow
  bodies alongside function bodies, but an ARROW DOES NOT REBIND `this`, so
  `(() => this).length` in an enum initializer is just as illegal — the descent
  emits TS2332 only, because the reference baseline has no companion TS2683 for
  the arrow-nested form.
- [ ] **(M3.0-gap-2) PARKED round 714 — everything worth having from this case has
  shipped; the case itself stays deferred by DECISION, not by omission.** Fixed across
  rounds 693/704/706/707: the over-emitted TS7019/TS7006 (IIFE parameters are
  contextually typed, so tsc reports nothing for them), the contextual TYPING itself
  (from the call's arguments, in `populateParameterLocalTypes`), and all three TS18048 —
  including the pure-`undefined` reference case, which also fixed the literal-vs-reference
  boundary against TS18050. Round 713 additionally closed the argument-context TS7006
  hole the case exposed, under noImplicitAny.
  **Why it will not un-defer:** its remaining TS7006 ×2 are on argument arrows in a file
  whose only directive is `@strictNullChecks` — pure-default mode, where the full
  implicit-any walker is deliberately OFF and the narrow default-mode walker covers one
  shape on purpose. Closing it requires broadening that walker, which is the change
  recorded as having regressed ~19 tests. Not worth it for one conformance case; revisit
  only if the default-mode walker is broadened for its own reasons.
  ORIGINAL: **the FALSE-POSITIVE half is FIXED (round 693); the missing codes remain.** tsc
  contextually types an IIFE's parameters from the call ARGUMENTS, so it reports
  no implicit-any for them even when the call passes none; we emitted TS7019 ×3 +
  TS7006 ×2. `isImmediatelyInvokedFunctionParam` (owner walked up through
  parentheses to a CallExpression whose unwrapped callee is that function)
  suppresses both, in BOTH emitters — the general parameter walker AND the
  dedicated rest-parameter walker, which carries its own TS7019 and TS7006 and is
  the one live for these shapes.
  **ROUND 704: the parameters ARE now typed from the arguments** (in
  `populateParameterLocalTypes`, per the round-694 finding — `((a) => a.nope)("x")`
  reports TS2339 on `string`), with two pieces still open. **(i)** Only ARROWS are
  typed; a function EXPRESSION IIFE is not, and the site responsible is NOT the
  no-contextual-annotation branch that blanket-registers `any` for a callback's own
  parameters (deferring there was measured inert). A limitation pin records this.
  **(ii)** The typed parameters now produce the RIGHT analysis with the WRONG code:
  `((j?) => j + 1)(12)` reports **TS2365** ("Operator '+' cannot be applied to types
  'number | undefined' and '1'") where tsc reports **TS18048** ('j' is possibly
  'undefined') — the documented round-415 hazard, where a union carrying `undefined`
  fails the arithmetic operand classifier. tsc checks possibly-undefined FIRST, so the
  fix is a nullish-operand rule ahead of TS2362/2363/2365 in the arithmetic pass.
  That is what still keeps the case deferred.
  **ROUND 706: the rule LANDED — direction confirmed against a SECOND reference baseline
  (`circularOptionalityRemoval` reports TS18048 for `x > 0` with `x: number | undefined`,
  as `contextuallyTypedIifeStrict` does for `j + 1`), and the two TS2362 baselines that
  also mention "possibly undefined" were checked and are unrelated (their operand is a
  `delete` expression, a boolean). The nine local pins were updated to expect TS18048,
  their intent unchanged, and one paired positive control strengthened to exclude TS18048
  too.** **ROUND 707 closed the `k`/`o` half too** — a REFERENCE typed exactly `undefined`
  now reports TS18048 like a union does (the arithmetic walker's strictNullChecks early
  return deferred those to TS18050, which is right only for the LITERAL operand). All
  three TS18048 of the case now fire at the baseline's positions. **What remains is only
  the two TS7006** for the INNER function's parameter in `(f => f(12))(i => i)`.
  **ROUND 708 probed it and the framing "argument arrows are not reached" is WRONG** —
  four contrasted shapes under `@strictNullChecks: true`: `take(i => i)` against an
  annotated `(x: number) => number` parameter is correctly SILENT; `anyCb(j => j)`
  against an `any` parameter correctly FIRES, so the walker does reach an argument
  arrow and does emit there; `(f => f(12))(k => k)` is silent (the gap); and — the
  surprise — a plain `function plain(m) { return m; }` is ALSO silent in the same file.
  That last one is not about IIFEs or arguments at all, so the next round should start
  by settling the GATE question (which shapes emit TS7006 under which options, and why
  a top-level function declaration's parameter differs from a callback's here) before
  touching the IIFE case. Do not assume the callee-typing path is at fault.
  **ROUND 710 CORRECTS ROUND 709: the two-walker split is DELIBERATE and documented, so
  "unify the gates" is the wrong instruction — do not follow it.** `checkImplicitAny
  DefaultVarFunctions` runs ONLY in pure-default mode and covers ONE shape
  (`var v = <arrow|fn-expr>` with an untyped parameter), because the full
  `checkImplicitAnyParameters` walker is gated on noImplicitAny/strict for a MEASURED
  reason: broadening it regressed ~19 tests (FunctionDeclaration params, type-annotation
  walking, ambient TS7005/7008, JS files, object-literal contextual-typing gaps). The two
  are mutually exclusive by construction so they never double-emit.
  **What survives from round 709 as a real finding** is narrower and still worth fixing:
  `anyCb(j => j)` (an arrow argument against an `any` parameter) is reported in
  pure-default mode but NOT under noImplicitAny — turning the stricter option ON loses a
  diagnostic, which cannot be right whatever the walker split is. And gap-2's
  `(f => f(12))(k => k)` is uncovered in BOTH modes. So the target is a COVERAGE hole in
  `checkImplicitAnyParameters` (argument arrows whose callee parameter provides no
  contextual type), not the gates.
  **ROUND 711 located it exactly: a CONTRACT MISMATCH.** The argument edge is built as
  `SpineIanyCtx(kind = 1, typed = isCalleeResolvable(node.expression))` (~53248), i.e. it
  uses "can I resolve the callee NAME" as a proxy for "does this argument have a
  contextual type". Those come apart in precisely the two shapes that are missing:
  `anyCb(j => j)` — the callee resolves, so `typed = true` suppresses, but its parameter
  is `any` and therefore supplies NO contextual signature, which is why tsc reports it;
  and `(f => f(12))(k => k)` — the callee is a parenthesized ARROW, so
  `isCalleeResolvable` returns its default `true` and suppresses again.
  **The fix is to consult the callee's PARAMETER TYPE at the argument's position** (no
  contextual signature when it is `any`, unresolved, or not function-shaped) rather than
  the callee's resolvability. Note `isCalleeResolvable` also has a deliberate B182 arm
  (a LIB_MIN_TARGET-dropped method has no contextual signature) — the same idea, applied
  to one case; this generalises it. **Gate carefully:** broadening this walker is the
  change documented as having regressed ~19 tests, so expect the corpus to arbitrate,
  and run `--listAll` ×8 as well since callback parameters are everywhere in tsc's own
  source. Round 709's framing below is kept only to mark the
  **ROUND 712 IMPLEMENTED IT AND REVERTED — two narrowings are already spent, start
  from them.** The edge change is small and works: at the argument consumer (~53370) the
  index IS available (`p.arguments.indexOfFirst { it === node }`), so `typed` becomes
  `callCtx.typed && !calleeParamIsPositivelyAny(p, idx)`; with it all three target shapes
  fire under noImplicitAny — including gap-2's `(f => f(12))(k => k)` — and
  `take(i => i)` stays silent. **(1) Our resolved `anyType` is NOT tsc's `any`:** deciding
  on the RESOLVED parameter type red-lined three corpus baselines
  (contextualPropertyOfGenericFilteringMappedType,
  contextualTypeFunctionObjectPropertyIntersection, normalizedIntersectionTooComplex),
  because a generic or mapped annotation we cannot resolve lands on `anyType` too and
  those DO have contextual types — the test must be SYNTACTIC (the annotation is literally
  the `any` keyword, or absent), which makes the corpus green. **(2) The EMBEDDED LIB's
  `any`s are placeholders:** with the syntactic rule the PROFILES gain FPs (46 → 47,
  harness 94 → 98) on `.replace(/\./g, s => s.substring(1))` and
  `JSON.stringify(f, (_, v) => …)`, since our lib simplifies those callback signatures
  where tsc states them precisely. Excluding the builtin-lib decl sets is the right
  direction and is precedented (the TS2554 lib gate), but the exclusion I wrote did NOT
  catch the `.replace` site — establish first which set holds a resolved lib METHOD's
  parameter for a PropertyAccess callee, then it should land.
  correction. ORIGINAL (WRONG): **the two TS7006 emitters have INVERTED option gates.** Same four shapes, two configs:
  | shape | strictNullChecks only | + noImplicitAny |
  | `take(i => i)` (annotated context) | silent (right) | silent (right) |
  | `anyCb(j => j)` (`any` parameter) | **FIRES** | **SILENT** |
  | `(f => f(12))(k => k)` | silent | silent |
  | `function plain(m) {}` | **SILENT** | **FIRES** |
  Turning `noImplicitAny` ON switches OFF the emitter that was firing, and vice versa —
  so no single configuration reports both shapes, and `anyCb(j => j)` going silent under
  noImplicitAny is a plain bug (tsc reports it). Relevant context for whoever fixes this:
  TS7006 fires BY DEFAULT in the corpus — 12 of 22 sampled TS7006 baselines have no
  `@noImplicitAny`/`@strict` directive at all — so the default-on convention
  (`!strictExplicitlyFalse`) is the one that matches the reference, and the
  `noImplicitAny || strict` gate is the odd one out. Unify the two gates on the
  default-on convention FIRST, re-gate, and only then look at the IIFE shape; it may
  well fall out, since the conformance case sets only `@strictNullChecks: true`.
  ROUND 705's framing, kept for the reasoning: **the rule works — but it collides with
  NINE LOCAL PINS, and resolving that collision is a decision, not a patch.** The rule (a possibly-undefined
  check ahead of TS2362/TS2363/TS2365 in the three arithmetic emitters, strictNullChecks
  only, plain references only, `any`/`unknown` excluded) turns `((j?) => j + 1)(12)` into
  the TS18048 the reference baseline wants. The CORPUS stays green — but nine hand-written
  pins in ArithmeticAmpAmpNarrowingTest, ArithmeticReassignmentNarrowingTest,
  Inv4SpineBatch22Test and NonNullArithmeticOperandTest assert that a maybe-undefined
  operand fires **TS2362**, e.g. `negative control - genuinely maybe-undefined operand
  still fires TS2362`. **The evidence says those pins encode OUR old behaviour rather than
  tsc's:** the `contextuallyTypedIifeStrict` reference baseline reports TS18048 for exactly
  this shape (`j: number | undefined`, `j + 1`), and the corpus is green either way, so it
  does not discriminate. Their INTENT — "narrowing did not apply, so it still fires" — is
  preserved by TS18048; only the code changes. So the next round should update those nine
  to expect TS18048, having first confirmed the direction against one more real baseline,
  and then re-gate. The rule was reverted rather than landed with nine red pins.
  **Still missing after it, for the record:** `k`/`o` (an optional parameter with NO
  corresponding argument types as `undefined`, and nothing fires for it yet) and the two
  TS7006 for the INNER function's parameter in `(f => f(12))(i => i)`.
  **ORIGINAL REMAINING:** the reference's **TS18048 ×3** ('j'/'k'/'o' possibly undefined, from optional IIFE
  parameters under strictNullChecks) and **TS7006 ×2** (lines 28–29 — the INNER
  function's parameter in `(f => f(12))(i => i)`, which tsc genuinely reports)
  do not fire.
  **Round 694 established WHERE the hook must go, by writing it in the wrong place
  first.** Typing the parameters in `getTypeOfArrowFunction` (next to
  `applyContextualParameterTypes`, writing `symbolTypes[param.id]`) is
  UNOBSERVABLE: `((a) => a.nope)("x")` still reports nothing, because the BODY
  walkers do not read `symbolTypes` for parameters — they read `currentLocalTypes`,
  filled by **`populateParameterLocalTypes`**, which records a parameter ONLY when
  it carries an ANNOTATION (`if (paramType != null && paramName is Identifier)`).
  So an un-annotated parameter is invisible to them no matter what the signature
  says. That implementation was written, measured, and REVERTED rather than landed.
  **The real change is therefore in `populateParameterLocalTypes`** (or wherever
  else a walker derives parameter locals): record an argument-derived type for an
  un-annotated parameter whose owner is an IIFE callee — reusing
  `immediatelyInvokingCall`-style parent-walking, which round 693 already proved
  out. Expect a WIDE blast radius: it gives types to parameters that were `any`
  everywhere, in ~26 call sites' worth of walkers, so it needs the corpus and the
  `--listAll` ×8 gate and probably its own round.
- [ ] **(M3.0-gap-3) `commaOperatorOtherInvalidOperation` — (A) and (B1) are DONE
  (rounds 697/700/701); only (B2) remains, so the case stays deferred.** What is left is
  the second TS2322, `var result: T1 = (x, y)` — TypeParam-vs-TypeParam, blocked by the
  relation's "two unconstrained type parameters always relate" leniency, whose correct
  form was measured in round 695 at exactly 2 corpus tests (both masking an
  un-substituted class type parameter in a member) — plus `canUseTypeEngine` refusing a
  TypeParam-vs-concrete pair, which is what keeps `var s: string = x` silent even though
  the relation already answers correctly. ORIGINAL TEXT follows.
  Two missing TS2322, both from the same root: `function foo(x: number, y: string)
  { return x, y; }` must infer the return type `string` (so `var r: number = foo(...)`
  errors), and `var result: T1 = (x, y)` — with `x: T1`, `y: T2` — must report
  `Type 'T2' is not assignable to type 'T1'` plus the "could be instantiated with an
  arbitrary type" chain line and a TS2208 related info at the `T2` declaration.
  We already emit the case's other two diagnostics (TS2454 ×2), so this is additive.
  **(A) IS DONE (round 697)** — `inferReturnTypeFromBody` gained a Comma arm typing the
  right operand from the OWNING function's parameter annotations (`commaReturnOperandType`);
  corpus green, all 8 profiles byte-identical, +6 pins. Only (B) remains, so the case
  stays deferred.
  **Round 695 isolated both halves — read this before starting, two of the obvious
  routes are already excluded.** A five-line probe (`function baz(...): string` beside
  the inferred `foo`, and a `var direct: T1 = y` beside the comma one) splits the case:
  **(A)** the comma itself is only half the story — `combineBinaryTypes` ALREADY types
  a comma as its right operand (`SyntaxKind.Comma -> getTypeOfExpression(right)`), and
  the annotated `baz` errors correctly, so what is missing is
  `inferReturnTypeFromBody`, whose `BinaryExpression` arm has no Comma case. Note its
  deliberate conservatism: its `Identifier` arm returns null for anything but
  `true`/`false`, because it runs in the CALLER's scope, where resolving a callee's
  parameter by name would hit the documented shadowing hazard. So a Comma arm cannot
  just call `getTypeOfExpression(right)` — the honest fix types the right operand
  against the OWNING function's parameter annotations (reachable via the body's
  `parent`), which also fixes the more general `return <param>` gap.
  **(B)** is NOT a comma problem at all — `var direct: T1 = y` (no comma) is equally
  silent — and, measured, it is **not the TypeParam-vs-TypeParam relation either**:
  making two unconstrained type parameters relate only when their names match left the
  case silent, so the emission is suppressed UPSTREAM (the round-431e foreign-TP source
  gate on the var-decl path is the prime suspect — `T2` is a TypeParam in the source).
  Start there, not in the relation engine.
  **Measured cost of the correct relation rule, recorded so nobody re-runs it:** exactly
  **2** corpus tests (`inferFromGenericFunctionReturnTypes1`/`2`), both the same
  `Type 'SetOf<B>' is not assignable to type 'SetOf<B>'` shape — identical display, so
  the leniency is masking an UN-SUBSTITUTED class type parameter in a member (`_store:
  A[]` substituted on one side only). Restricting the strict rule to top-level
  comparisons (`relationComparisonStack.size <= 1`) dodges both regressions, but buys
  nothing while (B)'s real blockers stand.
  **(B)'s real blockers, found by marker probe (round 695 tail) — TWO of them, and
  neither is the relation.** A four-case probe (`f1<T>(x: T) { var s: string = x }`,
  `f2<T1,T2>(y: T2) { var r: T1 = y }`, an array variant, and a fully concrete control)
  printing `typeToString` of both sides plus `canUseTypeEngine`/`checkTypeRelatedTo`
  at `checkVarDeclAssignability`'s gate reports:
  **(B1) a type-parameter annotation on a function-BODY variable resolves to `any`** —
  `var r: T1` gives `tgt=any` (and `var r2: T1[]` gives `any[]`) while the PARAMETER
  annotation `y: T2` resolves correctly, because a parameter is resolved while building
  the signature with the type parameters in `currentTypeParamScope` and a body variable
  annotation is not. So no relation could ever fail here — the same class of bug as
  round 691's generic arrow, one scope level out.
  **(B2) `canUseTypeEngine` refuses a TypeParam-vs-concrete pair** — for `var s: string
  = x` the relation ALREADY returns the correct `false` (`foreign=false`, `rel=false`),
  but `canUse=false` means the emission never runs. tsc reports TS2322 there.
  Fix (B1) first (it is the one that makes `T1` a real type at all); (B2) then decides
  whether the correct verdict is allowed to be emitted. Both have M3.1-flavoured blast
  radius — body variables annotated with type parameters stop being `any` — so each
  wants the corpus and `--listAll` ×8, and the round-431e foreign-TP gate is what should
  keep un-inferred callee TPs out of the new emissions.
  `typeParams` threading is NOT a suspect: the probe shows it arriving correctly
  (`tp=[T]`, `tp=[T1, T2]`) and the foreign-TP gate not firing.
  **(B1)'s ONE-LINE fix is known and was measured (round 696, attempt 1, reverted) —
  do this as ONE change with the chain-parity work below, never alone.** The cta frame
  ALREADY computes the type-parameter scope (`CtaFrame.fnTpScope`, built beside
  `fnTpDecls` at frame-build time) and **never reads it** — `grep fnTpScope` returns its
  declaration and its single write. The per-statement dispatch installs
  `currentTypeParamDecls = frame.fnTpDecls` but not the scope, so annotations resolved
  during that dispatch see no type parameters. Adding
  `currentTypeParamScope = frame.fnTpScope ?: <saved>` to the same save/install/restore
  sandwich works — probe: `var r: T1` goes `any` → `T1`, `var r2: T1[]` → `T1[]`.
  **Its measured cost: 27 corpus tests, and the classification is the useful part** —
  of ~32 changed baseline lines, **~29 are REMOVED `'T' could be instantiated with an
  arbitrary type which could be unrelated to 'null'/'undefined'` chain lines**, i.e. the
  emission survives and only its chain is lost. Mechanism: with `T` resolving to a real
  `Type.TypeParam`, these `return null`-in-a-generic shapes stop falling through to the
  STRING fallback `emitTS2322(..., typeParams)`, which adds that chain when
  `targetBaseName in typeParams` (Checker.kt ~149892), and are handled by a type-engine
  emitter that does not. The var-decl (~95363) and assignment (~98644) paths already
  have the `tt is Type.TypeParam` chain block; the return path's engine emitter is the
  one to give parity. Only **3** lines were additions: one chain-FORM flip
  (constraint-form → arbitrary-form, `errorMessagesIntersectionTypes03`) and two genuinely
  NEW diagnostics (`Type 'Q' is not assignable to type 'InferBecauseWhyNot<Q>'`,
  `Type 'any[]' is not assignable to type 'T'`) that need their own verdict.
  **Attempt 2 (round 696, also reverted) took it from 27 failures to FOUR — the recipe
  below is ~5 minutes of re-typing, so start there rather than re-deriving.**
  *Edit 1 — the scope install:* in the cta per-statement dispatch sandwich (beside
  `currentTypeParamDecls = frame.fnTpDecls ?: emptyMap()`), save `currentTypeParamScope`,
  set it to `frame.fnTpScope ?: <saved>`, restore it in the same `finally` as
  `currentTypeParamDecls`.
  *Edit 2 — chain parity* in `checkReturnAssignability`'s engine emitter, inserted
  immediately BEFORE its "B60.6f (mirror): TS2208 related info" block: when
  `chain.isEmpty() && targetType is Type.TypeParam`, add the constraint form
  (`'<src>' is assignable to the constraint of type '<T>', but …`) when the constraint
  is non-null AND `checkTypeRelatedTo(sourceType, constraint)` AND
  `!anonymousObjectHasExcessVsConstraint(...)`, else the arbitrary form — exactly the
  block the var-decl (~95363) and assignment (~98644) paths carry. This alone clears
  **23 of the 27**.
  **Attempt 3 (round 698) took it to THREE, and named the mechanism behind the last
  two. Add to the recipe:** in the new chain block, the constraint must be treated as
  absent when it is `anyType` **OR `errorType`** — an unconstrained `<T>` arrives here
  with an UNRESOLVED constraint, and errorType DISPLAYS as `'any'` (B58.1), which is
  what made `declFileGenericType` read as `constraint 'any'`. That one guard fixes
  residual (a). Remaining: (b), (c), (d) below.
  **(c) and (d) are DOUBLE EMISSIONS, not false positives — the baselines contain both
  diagnostics, our error COUNT grows by one.** The `Diagnostic`-init stack-trace probe
  named the other emitter for (c): the dedicated pin walker
  **`checkDeeplyNestedMappedTypes`**, which exists precisely because the engine could
  not produce that diagnostic — and its display is the CORRECT one
  (`{ level1: { level2: { foo: string; }; }; }[]`) while the engine renders the source
  as `any[]`, because the case's `Input`/`Output` mapped aliases resolve to any. So the
  engine does NOT supersede the walker here and the walker must not be deleted. Note
  the ORDER, which decides the fix: the engine (cta anchor) emits FIRST and the walker
  later, so a "has anything already reported here?" probe in the engine cannot see it —
  the retraction has to live in the WALKER (documented precedent: a later pass that
  retracts/edits an earlier pass's diagnostics, cf. checkCloduleTest2 removing TS2554 at
  NewExpression positions). (d) was not probed but shows the identical signature
  (baseline has the diagnostic; our count goes 8 → 9), so expect another dedicated
  walker and the same disposition.
  **Attempt 4 (round 699) got the corpus to ZERO with the whole change — and then the
  PROFILES killed it. This is the real blocker; read it before touching (B1) again.**
  Corpus 12,731 / 0 / 3 with all four residuals fixed (see the completed recipe below),
  but `--listAll` ×8 went 46 → **49 on every profile** (harness 94 → 97): three NEW
  false positives, the same three everywhere, all in `compiler/utilitiesPublic.ts`:
  `Type 'Node | undefined' is not assignable to type 'T | undefined'` (777),
  `Type 'JSDocTag | undefined' …` (1280), `Type 'JSDocTag[]' is not assignable to type
  'readonly T[]'` (1285). **All three are TYPE-GUARD-DRIVEN GENERIC INFERENCE:**
  `getFirstJSDocTag<T extends JSDocTag>(…, predicate: (tag: JSDocTag) => tag is T)`
  returns `find(tags, predicate)`, `getAllJSDocTags` returns
  `getJSDocTags(node).filter(predicate)`, and `tryCast`-shaped code returns
  `nodeTest(node) ? node : undefined`. tsc binds the callee's own type parameter to the
  CALLER's `T` through the `tag is T` predicate, so the sources are `T | undefined` /
  `readonly T[]`; we bind the concrete `JSDocTag` and therefore see a mismatch. These
  were invisible while the return annotation resolved to `any` — resolving the target is
  what exposes them. **v1's dashboard is at ZERO real FPs, so this cannot land until
  they are gone.** Two ways forward: make guard-driven inference bind the caller's type
  parameter (independently valuable — round 430 already built "TP-from-PREDICATE
  binding" for exactly this family, so start by finding why it yields `JSDocTag` here),
  or add a TARGET-side companion to the round-431e foreign-TP gate. Prefer the first:
  a target-side gate must still let `function f<T>(): T { return null; }` error, which
  the corpus pins, so it would be a heuristic in the place heuristics are most likely to
  silently lose real errors.
  **THE COMPLETED RECIPE (corpus-green; all four residuals fixed):** edits 1 and 2 as
  described, plus — (a) treat `anyType` OR `errorType` as "no constraint"; (b) report
  the APPARENT constraint by following the interned chain to its first non-TypeParam
  link and, when that yields nothing usable, following the DECLARATION's constraint
  chain by name and resolving its first concrete link (factored as
  `apparentConstraintOfTypeParam`, needed by the ASSIGNMENT path too — that is where
  `errorMessagesIntersectionTypes03`'s `V extends U extends A` is decided, not the
  return path); (c)+(d) register every return-path engine TS2322 in a pre-`init` list
  and, at the end of `init`, drop it by IDENTITY if another TS2322 shares its position —
  dedicated pin walkers run after the spine and own some of these positions with better
  displays, so the engine cannot probe for them at emission time.
  **The FOUR residuals, each already diagnosed:**
  (a) `declFileGenericType` — `export function F5<T>(): T { return null; }` is
  UNCONSTRAINED, yet the interned TypeParam arrives with `constraint == anyType`, so the
  new block picks the constraint form where tsc uses the arbitrary one. Fix: treat an
  `any` constraint as unconstrained (the sibling TS2208 block right below already has an
  "effectively unconstrained" notion, for the self-circular case).
  (b) `errorMessagesIntersectionTypes03` — the reverse: tsc wants the CONSTRAINT form
  (`'A & B' is assignable to the constraint of type 'V'…`) and we produce the arbitrary
  one. Round 698 narrowed the cause: the constraint does not RESOLVE (same errorType
  situation as (a)), so no relation can be run and the engine has no `'A'` to print —
  which is exactly why the old string fallback got it right, reading the constraint
  TEXT out of `currentTypeParamDecls` (`emitTS2322`'s B60.6c path). The fix is to give
  the engine block the same syntactic fallback: when the RESOLVED constraint is
  unusable, take the declaration's constraint node text and decide the form the way
  B60.6c does, rather than dropping to the arbitrary form.
  (c) `deeplyNestedMappedTypes` and (d) `conditionalTypeAssignabilityWhenDeferred` are
  genuinely NEW emissions, not chain problems — `Type 'any[]' is not assignable to type
  'T'` and `Type 'Q' is not assignable to type 'InferBecauseWhyNot<Q>'`. Both are targets
  that only became checkable once the scope resolved them, and both are M3-depth (a
  mapped-type return and a DEFERRED conditional type, which tsc relates under rules we
  do not model). Expect these two to need a gate of their own — the round-431e foreign-TP
  gate is the family precedent — and note they are also the two most likely to appear on
  the profiles, so `--listAll` ×8 is mandatory before landing.
- [x] **(M3.0-gap-4) DONE (rounds 702/703) — `readonlyRestParameters` passes and is
  un-deferred.** Two rules, both narrower than they first look. **TS2556:** an unbounded
  array spread into a fixed-arity call cannot be arity-checked, so tsc rejects it — with
  four narrowings that each came from a red test rather than from reasoning (a TUPLE
  spread is legal; an ARRAY LITERAL spread is legal, tsc counting `...[6, 7]` as two
  arguments; spreading INTO a rest parameter is legal; and an already-too-many call
  reports the COUNT instead). A rest parameter's type does not resolve in the arg-count
  pass, so the operand is classified from its ANNOTATION when the resolved type is
  unavailable, which also handles `readonly string[]` for free. **TS2554:** a rest
  parameter annotated with a fixed TUPLE has fixed arity, and a tuple-typed spread
  argument contributes its element count. The trap that made round 702's first attempt
  inert: the excess anchor is an ARGUMENT INDEX, not the expanded count —
  `emitTS2554TooMany` opens with `if (firstExcessIdx >= args.size) return`, so passing a
  count of 2 with 2 arguments returned silently.
- [ ] **M3.5 Per-file scopes** (Blocker #3: stop merging all file locals into
  `globals`; per-file scope construction with explicit import visibility). Revisit
  before v1 ONLY if dashboard FPs trace to cross-file scope conflation on tsc sources.
- [ ] **M4.1 Full nodenext resolution**: package.json `exports`/`imports` maps,
  symlink/realpath (pnpm layouts), `typesVersions`, package self-references. (The tsc
  repo itself uses relative imports + @types — unused for v1.)
- [ ] **M4.2 Real declaration emitter.** `.d.ts` output for arbitrary code (the corpus
  strips most `.d.ts` sections, so almost none exists today; `declaration: true` is
  table stakes for "any project"). Test bed: conformance decl baselines + self-compile
  d.ts diffing. Pull into v1 only if the owner defines "fully compile tsc" to include
  declaration output.
- [ ] **M4.3 JSX end-to-end** (`jsx: react-jsx`/`react`/`preserve` transforms on real
  React-shaped code).
- [ ] **M4.4 Sourcemaps — the parenthetical "inline maps exist" is STALE (checked
  round 695): NOTHING generates map content.** `grep sourceMappingURL` over
  `src/commonMain` hits only `TypeScriptCompiler.kt`'s option-conflict validation
  (TS5053 for `mapRoot`/`sourceMap` with `inlineSourceMap`), and `Emitter.kt` has
  no mappings emitter at all. `BaselineFormatter` takes `sourceMap`/
  `inlineSourceMap`/`sourceRoot`/`mapRoot` parameters, which is presumably where
  the belief came from — those shape the BASELINE layout, not the output. So this
  is a full implementation (segment tracking through the transformer, VLQ
  encoding, the `//# sourceMappingURL=` trailer, sidecar `.js.map` writing), not
  the small "also write the file" task the entry implied.
- [ ] **M4.5 Decision point**: project references / composite / incremental scope
  (tsgo supports them; needed for large monorepos — decide build vs defer with owner).
- [ ] **M4.6 `package.json "type": "module"` module-format detection in
  `ProjectCompiler`** (found compiling zod, 2026-07-07): under `module: NodeNext`
  with a `"type": "module"` package.json, real tsc emits ESM but we emit CJS — the
  `collectPackageJsonTypes` machinery exists only for the multi-file TEST-source path
  and is not wired into the on-disk project pipeline. Repro: zod (see M4.7); the
  emitted CJS only runs in a `"type": "commonjs"` context. Unused for v1 (the
  tsc-source bench project has no package.json → CJS default is correct there).
- [ ] **M4.7 zod as a second dashboard profile** (validated 2026-07-07, round 432
  session note): shallow-clone `github.com/colinhacks/zod`, compile
  `packages/zod/src` (107 files, ~31k LOC) via a `tsconfig.xtsc.json` extending zod's
  real `.configs/tsconfig.base.json` (strict, exactOptionalPropertyTypes,
  noUnusedLocals, NodeNext), include `src/**/*.ts`, exclude tests/benchmarks — real
  tsc 6.0.3 reports 0 errors on it, so every xtsc diagnostic is an FP. Baseline
  2026-07-07: 1,665 FPs (top: TS7006×447 contextual params, TS2694×284 namespace
  members via `export *` barrels, TS7029×211 switch-fallthrough, TS2344×182), 0
  crashes, all 107 files emit, output passes a runtime smoke test. Complements the
  tsc-source profiles: stresses generic method chaining + noFallthroughCasesInSwitch,
  which tsc's own source doesn't.

### Offline asset inventory (verified 2026-07-02)

- `typescript-repo` object DB is complete (sparse checkout, full objects): any
  `src/**` path extractable via `git archive HEAD <path>`; `src/lib/` holds the 110
  real lib `.d.ts` files; `tests/cases/conformance/` holds 5,907 `.ts`/`.tsx` cases.
- Node/tsc/tsgo are NOT currently installed — differential testing (M0 optional) and
  real `@types/node` (M1.3) wait for network.
- The benchmark project cache lives under `build/bench/` (cheap to rebuild); results
  TSVs under `bench/` (gitignored, machine-specific).
