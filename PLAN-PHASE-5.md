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

This file is the **live queue** for Phase 17. `PLAN-PHASE-4.md` (Phase 16 and earlier)
is archived state — its "Known architectural blockers" section remains the reference
material for the M3 items below; do not work its queue.

## Phase 17 — Self-compile the TypeScript compiler (M0–M5)

(Live session notes accumulate here, most recent first — same convention as Phase 16.)

**Round 671 (2026-07-25) — the queue boundary VERIFIED, and the last live
non-backlog item turned out to be a stale checkbox. Phase 17's offline work is
complete; what remains needs an owner decision.** Round 670 suggested the queue
had reached a boundary; this round tested that claim item by item rather than
asserting it.

**(ccet-m2) was the one candidate that looked live, and it is not.** It is the
frame skeleton for the THIRD giant (checkCallExpressionTypes), and two in-code
comments still read "inert until the anchors land". Both are stale: verified in
code that `ccetSpineEnter` and `ccetSpineFileReset` are called unconditionally
from `spineEnterNode` and the per-file loop, so the frames are ALWAYS-ON — and
its dependents `(ccet-m3)` (round 591) and `(ccet-retire)` (round 592, "ALL
THREE GIANTS OFF EMIT-TWICE") could not have landed otherwise. Box checked,
comments corrected. Worth noting as a process point: a stale `- [ ]` next to
landed work is exactly what makes a queue look like it still has runway, and
this one survived ~80 rounds.

**The boundary, item by item.** 15 unchecked items remain and every one is
unavailable to an offline agent:
  - **EP.0, EP.2** — blocked offline; verified round 667 that this box has no
    `node`, no `npx`, no `tsc`, no `tsc.js`, and `emit-diff-tsc.sh` needs a
    reference tsc.
  - **INV.7 / INV.7b / (6e)** — INV.7b is explicitly PARKED-BY-OWNER; INV.7 is
    native re-enable + release productization, and (6e) parallel emit sits on
    the M2 finding that this 4-core box cannot demonstrate scaling.
  - **M2.4, M3.0, M3.5, M4.1–M4.7** — the Post-v1 backlog, which CLAUDE.md
    instructs the loop to SKIP until v1 lands.

**And v1's status is itself the decision.** Its offline definition of done was
met at round 481 (all 8 profiles zero real FPs, all files emitted, exit 0, no
crashes). The remaining leg — byte-correct emit diffing against real tsc — was
always documented as network-gated. So "has v1 landed?" is a scope question only
the owner can answer, and it is the same question that unparks the backlog.

**Options for the owner** (recorded here so the next session does not
re-derive them):
  1. **Authorise a network install** (node + `typescript`, or build tsc at the
     pinned commit) → unblocks EP.0/EP.2 and completes v1's byte-parity leg.
  2. **Declare v1 landed on its offline definition** → unparks the post-v1
     backlog (M3.5 per-file scopes is the highest-value item there: it is
     Blocker #3, the root of several documented FP families).
  3. **Take the one remaining perf lever** — full name atomization (M0.3(i)),
     multi-session and PRICED first per round 670.
  4. **Stop Phase 17.** The corpus is green at 12,520/0/3, the 8 profiles are at
     their FP floor, and both the PERF and EP arcs are closed with ledgers.

Gates: comment-only code change (two stale notes corrected); suite 12,520/0
(3 skipped); tree clean.

**Round 670 (2026-07-25) — M0.3 CLOSED, and a
correction to my own round-666 record: the PERF arc was NOT fully closed then —
M0.3 still had three unchecked slices, and this round prices them out properly.**
Surveying what remained workable offline after PERF and EP both "closed" turned
up (M0.3) still unchecked with real sub-items, which makes round 666's "THE PERF
ARC, CLOSED" an overstatement. Correcting it is the point of this round.

**The measurement.** (M0.3)(i)'s cheap half is a globals-miss short-circuit, and
its stated evidence is "the 1.48M probes are 99% miss". Measured live, that is
exactly right: **1,234,034 globals lookups, 1,219,892 misses = 98.9%**. But the
item never priced it, and the arithmetic is decisive: skipping 1.22M HashMap
probes at a realistic 20–40 ns each saves **25–50 ms of a ~28 s compile =
0.09–0.17%**; even a generous 100 ns per probe gives 0.44%. That is an order of
magnitude inside the ±2% band the PERF ground rules refuse to land on. (v)'s
undo-log is the same size (JFR: 1.1% of samples — and round 623 established that
a JFR self-% is not a wall price).

**So M0.3's remaining slices cannot land alone**, by the arc's own rule that
anything below the drift band folds into a structural item rather than landing
on its own. What is left worth anything is (ii) NodeLinks/SymbolLinks
consolidation and (i)'s FULL form — Identifier → Int atom at scan time plus
int-keyed scope/member maps — both multi-session structural changes touching the
binder and every map.

**One forward-looking note, because it is the only lever left.** Full atomization
is the same CLASS as the arc's three winners: those replaced allocation-heavy
per-call structures on hot paths (LongKeyMap, IntKeyMap, NarrowSeen), and
atomization removes String hashing/equality from hot map traffic. So it is
plausibly real — but the item's opening evidence ("JFR-evidenced ~15% of wall in
HashMap+String equality") is exactly the kind of figure this arc has repeatedly
found unreliable, so it must be PRICED by instrumenting the actual time in the
operations it would replace before a line is written. That instruction is now in
the queue item.

**This makes the arc genuinely closed, with the ledger unchanged**: M0.1 (tail
deletion: ~6.2 s advertised → 59 ms), M0.2/M0.3 (dispatch + layout: −3.3%,
−3.9%, −2.2%, −2.6% landed; remainder priced out here), M0.4 (35 spine
migrations: neutral), M1 (−2.93%, the only live win), M2 (23% divisible, w4
flat, parked). Five families, one win, and the recurring cause of the gap
between advertised and measured was always the same: an aggregate ratio applied
to a non-uniform population — which is precisely the error this round avoided by
multiplying 1.22M by a per-probe cost before building anything.

Gates: no code changed (pricing + bookkeeping correction); tree clean; suite
untouched at 12,520/0/3.

**Round 669 (2026-07-25) — EP.1 DONE: barrel-reached const enums now inline —
and measuring it falsified EP.1's dashboard premise as well, the way round 667
falsified its technical one.** This completes the barrel-hop pair begun in round
668 (the checker-side TS2694 FP); same root cause, same suppression-safe shape,
and the same lesson about checking a stale claim before believing its size.

**The fix.** Through a barrel the emit kept `barrel_1.Kind.B`, retained a real
`require("./barrel")` and dragged in the entire `__importStar` helper tsc
elides. It now emits `1 /* Kind.B */`, `0 /* B.Kind.A */`,
`"x" /* Names.X */` with the import fully elided. Cause: both const-enum entry
points (`resolveConstEnumMemberAccess`, `isConstEnumAlias`) reach the enum via
`resolveAlias`/`resolveNamePath`, which walk `symbol.exports` — and a star
re-export never populates the barrel's own export table.
`constEnumSymbolThroughStars` resolves the importing statement (named OR
namespace form), follows the target module's star closure, and returns a symbol
ONLY when it carries `SymbolFlags.ConstEnum`. **Const-enum-only by
construction** — it can never feed a general type resolution, which is precisely
what keeps it clear of the documented dead-end where star-following inside
`resolveAlias` flooded TS2315 ×466.

**The honest headline: ZERO effect on the tsc profiles.** Before AND after, the
emitted `compiler` dist contains 1,663 numeric + 18 string const-enum inlines
and **0 residual `ts_N.X.Y`** accesses — identical. I measured that with a
stash/rebuild specifically because attributing those 1,681 inlines to this
change would have been wrong, and this arc has already produced three
mis-attributions from exactly that reflex. So EP.1's "highest impact, ~93% of
the changed lines" sizing is stale in the same way its technical premise was:
the tsc profile already inlines everything, and the barrel gap is a shape those
profiles never hit. The fix is kept because the gap is real — the repro and pins
prove it — but it is **general-correctness value for the post-v1 "any TypeScript
project" horizon, not a dashboard win**, and the queue now says so.

**Where that leaves EP.** EP.3 done (round 484), EP.1a done (668), EP.1 done
(669) — and all three of the "systematic families" round 483 identified are now
either fixed or found already-fixed. EP.2 (multi-line printer formatting) and
EP.0 (the diff gate) remain blocked offline: no `node`, no `tsc`, no `tsc.js` on
this box. So **the EP family is finished to the extent it can be, offline**, and
what remains needs either a user-provided reference tsc or a decision that
byte-parity is not worth the network dependency.

Gates: 7 pins (`BarrelConstEnumInliningTest` — named/namespace/string-valued
inlining, import elision, a two-barrel chain, plus two negative controls proving
a REGULAR enum and `preserveConstEnums`/`isolatedModules` keep their runtime
access); suite 12,513 → **12,520/0** (3 skipped) with every JS baseline still
byte-exact — the corpus is the real emit gate here; `--listAll` ×8
byte-identical on all eight profiles; warning-clean.

Re-learned the hard way, and worth repeating because CLAUDE.md documents it: a
literal `/*` inside a KDoc opens a NESTED block comment and breaks the file. The
doc comment now describes tsc's inlined form in words instead.

**Round 668 (2026-07-25) — EP.1a DONE: the `export *` barrel namespace-import
false positive (TS2694) is fixed, gated offline, and inert on all eight
profiles.** Round 667's triage found this while checking EP.1's premise; it was
sequenced ahead of the emit-byte half because FPs are the v1 metric, and it
turned out to be a two-line resolution gap rather than anything structural.

**The bug.** `import * as B from "./barrel"` where `barrel.ts` is
`export * from "./enums"`, then `B.Kind` in TYPE position, reported *"Namespace
'"x".B' has no exported member 'Kind'"* on valid TypeScript — exactly tsc's own
`_namespaces/ts.js` layout. `checkQualifiedNameExports` looks the final segment
up in `symbol.exports`, and **a star re-export never populates the barrel's own
export table**. The emitter already carried two narrow fallbacks for this class
(ambient `export =`, and local `export { X }` clauses); the star chain was the
missing third.

**Finding it cost one run.** Rather than reading the four candidate TS2694
emitters, I used the technique CLAUDE.md documents for exactly this — a
temporary env-gated `init` block on the `Diagnostic` data class printing a stack
trace for `code == 2694` — and it named `checkQualifiedNameExports` immediately.
The note in CLAUDE.md is accurate and worth reaching for sooner.

**The fix and why it is safe.** `namespaceImportMemberViaStarExports` consults
`getModuleExportsFollowingStars` (M1.1) for the imported module and withholds
the emission when the member is reachable through the star chain — or when that
returns NULL, which per the M1.1 discipline means UNKNOWABLE (bare/unresolvable/
`export =` star target) and callers must skip absence emission rather than
guess. Resolution uses the bare resolver with the relative leg as fallback (the
round-511 lesson: the bare one only knows flat corpus-style keys, so a
path-shaped project needs both). Crucially this is **SUPPRESSION-ONLY by
construction** — it can only withhold an emission, never produce one, and it
resolves no types. That is precisely the distinction that makes it safe where
the same idea inside the general `resolveAlias` is a measured dead-end (it
flooded TS2315 ×466 on the self-compile by resolving barrel-imported TYPES and
arity-checking them). A suppression that resolves nothing cannot reach that
failure mode.

**Gates:** 6 new pins (`BarrelStarExportNamespaceMemberTest`) — three positive
shapes including a two-barrel chain, and **three negative controls** proving a
genuinely absent member still reports, which is the assertion that actually
matters for a suppression; suite 12,507 → **12,513/0** (3 skipped); `--listAll`
×8 **byte-identical on all eight profiles** vs the round-664 capture (46×7/94),
so the change is inert everywhere it should be; warning-clean.

**Still open on EP.1** (the emit half): through a barrel, const-enum members
still emit `barrel_1.Kind.B` / `B.Kind.A` instead of `1 /* Kind.B */`, and drag
in a real `require` plus the `__importStar` helper tsc elides. Now that the
CHECKER resolves the barrel hop, the remaining work is connecting
`Transformer.collectConstEnumValues` — which walks statements directly — to the
same star-following resolution. Gateable offline the same way (local pin +
corpus), so it is the next EP item; EP.2 and EP.0 remain blocked on a reference
tsc that does not exist on this box.

**Round 667 (2026-07-25) — EP triage: two of the four items are BLOCKED OFFLINE,
EP.1's premise is partly FALSIFIED, and the residual turns out to be one shape —
`export *` barrels — which also emits a FALSE POSITIVE TS2694 that matters more
than the emit bytes.** No code this round; the PERF arc's habit of checking a
premise before building it transferred straight to EP and paid immediately.

**Blocked offline (recorded so nobody re-attempts it).** This box has **no
`node`, no `npx`, no `tsc`, and no `tsc.js` anywhere** — the tsc/tsgo columns in
`bench-history/README.md` come from CI. So **EP.0** (wire the emit-diff gate)
cannot run, and **EP.2** cannot start either, because its own text requires "the
emit-diff gate in place" — without it there is no way to tell whether a printer
change moves the diff toward or away from tsc, and the printer is precisely what
the green corpus pins. Unblocking needs a network install of node + `typescript`
or a tsc built at the pinned commit; both are outside the offline envelope, so
that is a user-gated decision rather than agent work.

**EP.1's premise is stale.** The round-483 claim was that xtsc "keeps
`mod.Enum.Member` for const enums imported across modules". Cross-module
inlining in fact already works, for both import forms and both value kinds,
verified two independent ways: (1) the corpus test
`constEnumNamespaceReferenceCausesNoImport` is an ACTIVE JS-emit subtest whose
tsc baseline is `case 0 /* Foo.ConstFooEnum.Some */` — and it passes in a
12,507/0 suite; (2) a scratch project emits `1 /* Kind.B */` for a named import,
`"x" /* Names.X */` for a string-valued one, and `1 /* E.Kind.B */` for
`import * as`. Somewhere in the ~180 rounds since round 483 this was fixed, and
the item was never re-checked.

**What actually fails is the barrel hop** — and that is exactly tsc's own
`_namespaces/ts.js` layout, which is why round 483 saw the symptom in
`utilities.js`. With `barrel.ts = export * from "./enums"`:
`import { Kind } from "./barrel"` emits `barrel_1.Kind.B`, `import * as B` emits
`B.Kind.A`, and both drag in a real `require("./barrel")` plus the entire
`__importStar` helper that tsc elides. So EP.1 is not "teach the checker
whole-program const-enum resolution" (that machinery exists) but "follow
`export *` when resolving a const-enum member". The likely lever is visible:
`Transformer.collectConstEnumValues` walks statements directly, while the
barrel-following resolvers (`resolveExportedSymbolThroughStars` /
`getModuleExportsFollowingStars`, M1.1 round 413) live in the Checker — the two
are not connected.

**The finding worth more than the emit bytes.** The same two-file barrel shape
also produces a FALSE POSITIVE: `import * as B from "./barrel"` then `B.Kind`
reports *"Namespace '"viaBarrel".B' has no exported member 'Kind'"* (TS2694) on
valid TypeScript. **FPs are the v1 metric**, so EP.1a is sequenced ahead of the
byte-fidelity half; presumably the same missing star-hop fixes both. Note it does
NOT show on the 8 tsc-source profiles (still 46×7/94), so it is a shape those
profiles never reach — it belongs in a local pin, not a dashboard expectation,
and it is a reminder that "zero FPs on the profiles" is not "zero FPs".

**Both EP.1 and EP.1a are gateable OFFLINE** (local pin + corpus, no reference
tsc), which makes them the only workable EP items here. The repro is saved at
`scratchpad/eptest` (enums / named / star / barrel / viaBarrel + tsconfig).

Gates: no code changed (triage only); tree clean; suite untouched at 12,507/0/3.

**Round 666 (2026-07-25) — (M2) SIZED BEFORE ANY CODE AND PARKED: only 23% of
the run divides, w4 is flat, and the 4-core box — not the design — is the binding
constraint. The PERF arc closes here.** Round 665 ended with "size (M2) with a
probe before writing code, the same way (d) was killed for 30 ms". Done, and the
probe was cheap because the `--workers N` share-nothing mode already exists
(INV.6(6c1)) — no new machinery was needed to measure the thing.

**Measured** (compiler profile, 2 reps each, same JVM settings):

    seq 27,873 ms  |  w2 24,669 (−11.5%)  |  w4 27,905 (+0.1%)

w2 helps, w4 is flat — the "w4 flat" the item recorded, and still flat after
M1's memo landed, so the M1-shrinks-warmup hypothesis in the item's own text is
falsified. Solving seq-vs-w2 as `seq = R + P` and `w2 = R + P/2` gives
**P ≈ 6.4 s (23%) divisible** against **R ≈ 21.5 s (77%) non-divisible**, i.e. an
infinite-worker floor of ~21.5 s — a 23% best case before contention is even
considered.

**Why, from the code rather than from theory.** Each worker runs
`sourceList.map { workerBinder.bind(it) }` — a FULL re-bind of EVERY file — and
then constructs a full `Checker` in which all ~318 program-wide collectors run;
only the per-file spine is narrowed by `assignedFileNames`. So the entire
duplicated term IS R. Phase 1 as specified (compute the collectors once, freeze,
share read-only) attacks at most the non-spine part of checker-init, which the
pass table puts at **~3.3 s** (checker-init 24.2 s − checkSpine 20.9 s, plus
outside-pass). On four already-saturated cores that reclaims CPU but buys no
wall time — and w4 regressing against w2 is that saturation showing.

**Verdict: park it.** The design is sound and would matter on a bigger host, but
on 4 cores / 7.7 GB it cannot be demonstrated, and this arc's standing rule is
not to land unmeasurable perf work. Two things would change the verdict, both
recorded in the queue item: a host with ≥8 real cores (re-run this exact probe
first), or a redesign that shrinks R instead of dividing P — and the full
per-worker re-bind is the single largest identified duplication, so it is the
honest first target if M2 is ever revived.

**THE PERF ARC, CLOSED — the whole ledger in one place.** Every item was
measured; almost every advertised number shrank on contact:

| item | advertised | measured outcome |
|---|---|---|
| M0.1 tail deletion | ~6.2 s tail | 59 ms deletable — the tail is corpus-pinned |
| M0.2/M0.3 dispatch+layout | — | −3.3%, −3.9%, −2.2%, −2.6% (landed) |
| M0.4 spine migration (35 passes) | ~6.2 s | **+0.24% / −1.6% = neutral**; 75% of each pass's cost reappears in checkSpine |
| M1 identity stability | "≤15–20 s path" (30–45%) | **−2.93% (0.83 s)** from the live walk memo; the expression half was 30 ms, not 1.1 s |
| M2 parallel Phase 1 | scaling | 23% divisible, w4 flat — not demonstrable on this box |

The durable lesson across all five: **an aggregate ratio applied to a
non-uniform population is the arc's characteristic error** — it produced M0.4's
"the tail is redundant traversal", M1's 1.1 s expression estimate (35× off), and
round 662's 165 phantom wrong serves. Every correction came from measuring the
specific population, and the shadow-classifier pattern made each correction cost
one probe run instead of a build-and-revert cycle. That pattern, the tagged
epoch, and the live dependency-keyed walk memo are what the arc leaves behind.

**NEXT:** with PERF complete, the remaining unchecked queue items are the EP
(emit-parity) family — EP.2 multi-line expression formatting, EP.1 cross-module
const-enum inlining, EP.0 wiring the emit-diff gate into the dashboard.

Gates: no code changed this round (probe only); tree clean; suite untouched at
12,507/0/3.

**Round 665 (2026-07-25) — (M1)(d) DEAD BEFORE IT WAS BUILT: an expression memo
would save 30 ms, not the ~1.1 s on the books. M1 CLOSES with 0.83 s banked.**
Round 664 wrote "measure the mean served-call cost BEFORE building" into this
item precisely because the walk memo had just netted 60% of its shadow estimate.
The measurement went much further than expected.

**The instrument.** For each `getTypeOfExpression` call: decide with EXACTLY the
live test (a CONFIRMED shadow entry at the current epoch), decide BEFORE the core
runs so the timed region is precisely what serving would skip, and accumulate the
core time of the OUTERMOST servable call only — serving an outer call skips its
whole subtree, so counting nested servable calls would double-count.

**The result: 30 ms over 71,310 outermost served calls** — ~0.42 µs each, 0.12%
of a ~24 s compile. A live memo would pay per-call overhead on ~618 k calls to
collect that, so it cannot break even. (d) is dead.

**Why the round-660 estimate was 35× off, and it is a repeat offence.** That
estimate multiplied the shadow's 149,742 hits by a MEAN call cost. But the
servable population is not average — it is the **cheap tail**: trivial
identifiers and literals whose underlying resolution is already cached. The
expensive calls (fresh minting, narrowing, relation work) are exactly the ones
whose results are not instance-stable, so the whitelist excludes them by
construction. Applying an aggregate mean to a non-uniform population is the same
error class as round 662's key collision — twice in this arc now, and both times
the fix was to measure the specific population rather than scale a ratio.

**It also explains a documented dead-end.** CLAUDE.md already records that a LIVE
per-node `getTypeOfExpression` memo measured **1–3% SLOWER** interleaved (round
596). That was observed but never explained; 30 ms is the explanation. Worth
saying plainly: my round-660 estimate contradicted an existing MEASURED result,
and I should have weighted the measurement above a fresh multiplication. The
CLAUDE.md dead-end entry now has its mechanism.

**M1's ledger, closed.** Original claim "≤15–20 s path" (30–45%) → retired at
round 660 for a measured ~3.3 s → corrected to ~2.5 s at round 662 when a key
collision turned up in my own instrument → round 664 banked **0.83 s (−2.93%)**
with the live dependency-keyed flow-walk memo, the arc's only live win → round
665 shows the remaining ~1.1 s was never there. What survives as reusable
machinery: the tagged epoch (`bumpExprEpoch`), the live walk memo, and three
shadow classifiers that made every one of those corrections cheap — each
correction cost one probe run rather than a build-and-revert cycle.

Gates: suite 12,507/0 (3 skipped, unchanged); probe-only — the measurement is
behind `--passTiming` and adds no live code path; warning-clean.

**NEXT: (M2) parallel scaling Phase 1** (shared frozen collectors), the last
unchecked PERF item. Honest note for whoever takes it: the box is 4-core /
7.7 GB, the queue itself says that caps what can be demonstrated locally, and
this arc's record is that every advertised number shrank on measurement — so
size (M2) with a probe before writing code, the same way (d) was killed for
30 ms.

**Round 664 (2026-07-25) — (M1)(c) THE FLOW-WALK MEMO IS LIVE: −2.93% wall
(−833 ms), 40,542 walks skipped, and every diagnostic on all eight profiles
byte-identical. The first measured WIN of the whole M5 performance arc.** Rounds
660–663 were measurement; this one is the payoff, and it landed on the first
attempt because the correctness argument was already finished.

**What landed.** `flowWalkWithTripCheck` serves from a memo keyed
`(reference nodeId, fileHash, walkKind, inputId)` whose value carries the
dependencies the walk actually read — the FlowGraph instance plus the Type
instances bound to the reference's ROOT NAME in `currentLocalTypes` and
`narrowedDeclaredTypes` — returning the cached result while all three still
match. A swap to a DIFFERENT scope map that still binds the root to the SAME
instance is deliberately not an invalidation; that tolerance IS the design, and
it is exactly the population the global epoch fence discarded (round 660).

**Measured, interleaved, 6 pairs, alternating within-pair order, both class dirs
kept with no recompile between measurements:**

    pre  median 28,433 ms      post median 27,600 ms
    delta −833 ms = −2.93%,    post wins 5/6
    per-pair: −1124 −1540 −720 −1434 −810 +524

Instrumented on the same profile: walks executed **111,248 → 69,859** (40,542
served), narrowWalks **3,791 → 2,756 ms**, checker-init ~25.4 → 23.6 s.

**The honest gap, and the lesson for the next memo.** The net (−0.83 s) is
SMALLER than the ~1.4 s the shadow predicted, because the memo pays key +
dependency lookup on ALL 111 k walks in order to skip 37% of them. Shadow
"servable time" is an upper bound, not a forecast — the overhead side never
appears in a shadow, by construction. That matters directly for (d): the
expression path has ~6× the call count and a much cheaper mean call (~7 µs vs
~34 µs), so its overhead fraction is far worse and the memo may not pay at all;
the queue item now says to measure the mean served-call cost BEFORE building.

**Correctness was finished before the first line of live code** — rounds 661–663
ran this exact key as a shadow classifier and drove `depServeWrong` to 0 over
41,389 serves — and all three round-663 hazards are handled: a walk that TRIPPED
is never stored (its result is truncated and its TS2563 side effect must keep
firing; a served hit runs no walk at all, so it consumes no visit budget and
trips can only become RARER); the cache stores `Any?` and casts, sound because
`walkKind` is part of the key so the Boolean-returning `isAssignedAtFlow` walks
never share a key with Type-returning ones; and the shadow classification stays
available under `--passTiming` so a lossy-`inputId` collision would surface as a
wrong serve rather than silently.

**Gates:** suite 12,507/0 (3 skipped, unchanged); `--listAll` ×8
**byte-identical on all eight profiles** vs the round-658 capture (only `time:`
differs) — so the memo moves no diagnostic anywhere, TS2563 included;
`--partitionCheck 2` EQUIVALENT ×8; warning-clean.

Arc position after five rounds of M1: the queue's original "≤15–20 s path"
(30–45%) was retired at round 660 in favour of a measured ~3.3 s, corrected to
~2.5 s at round 662, of which this round banks 0.83 s. The remaining (d) is the
expression memo, and it is now the last M1 item.

**Round 663 (2026-07-25) — (M1)(b2) MEASURED AND DROPPED: the canonical-output
prize is ~0.1 s reachable, because 76% of it sits behind object-type freshness
the relation engine deliberately depends on. (c) goes straight to the live walk
memo.** Round 662 queued (b2) with an explicit "re-measure before investing"
instruction; this round paid that and the instruction earned its keep.

**Where the prize was hiding.** The expression shadow admits only
instance-stable result kinds (Intrinsic / Interface / Reference) *because* unions
and literal types are freshly minted per call — which IS the non-canonical-output
problem, and it means those calls were never in the memo's denominator at all.
Counting every `getTypeOfExpression` call by result kind: intrinsic 331,636,
iface 125,683, **obj 102,102**, ref 27,309, union2 24,498, union3 3,773,
Intersection 1,719, union7 1,165 … So the whitelist admits 484,628 (exactly the
shadowMemo denominator, a nice self-check) and EXCLUDES ~134 k calls (22%).

**The prize, and then the deciding split.** Of the excluded calls, **62,949 are
same-epoch STRUCTURAL repeats** — freshly minted but structurally equal, so
interning the output would make them servable — against only 347 that genuinely
differ. That looks like ~0.45 s at the shadow's ~7.2 µs/hit. But split by kind it
collapses: **obj = 47,629 (76%)**, unions ≈ 12.7 k, Intersection 495. Object-type
freshness is DELIBERATELY load-bearing — the round-435 `freshObjLitRange`
relation machinery depends on it, and the whitelist's own comment says so — so
that 47.6 k / ~0.34 s half is unavailable without reopening relation semantics.
The safely internable union+intersection remainder is ~13.2 k ≈ **~0.1 s**.

**Verdict: drop (b2).** ~0.1 s of reachable gain, with its larger half gated
behind semantics we depend on, against a ~1.4 s live walk memo that is already
sound at `depServeWrong` = 0. Revisit only if union interning becomes desirable
for another reason (INV.5 canonical types would subsume it anyway). This is the
fourth consecutive round where measuring first changed the plan — and the second
where it *cancelled* work rather than resizing it.

**(c) is now an implementation task, not a research one**, and round 663 also
banked the three hazards it must handle, all found while reasoning about going
live: (i) **the walk is not pure** — it can trip `flowDepthTripped` → TS2563 via
`reportFlowControlError`, and it consumes the global visit BUDGET, so a served
hit changes both; the conservative rule is do not memoize a walk that tripped,
and note a served hit consumes no budget so trips should become RARER, never more
frequent (`--listAll` ×8 is the gate); (ii) the wrapper is generic and
`isAssignedAtFlow` returns Boolean while the others return Type, so the cache
stores `Any?` and casts — sound ONLY because `walkKind` is part of the key, which
must be documented at the cast; (iii) the `inputId` fold is lossy in principle
(32-bit path hash), so keep the shadow classification available under
`--passTiming` so a collision surfaces as a wrong serve instead of silently. And
unlike every M0.4 round, ~1.4 s ≈ 5% is ABOVE the drift band, so (c) owes a real
interleaved A/B.

Gates: suite 12,507/0 (3 skipped, unchanged); probe-only — every counter added
this round is behind `--passTiming`; warning-clean.


### QUEUE — work top-to-bottom; promote unblockers per protocol

(Restored 2026-07-12, round 481 — the queue/backlog/inventory sections had been
swept into PLAN-PHASE-5-HISTORY.md by an over-eager session-note trim; they are
LIVE structure, not history. v1's offline-verifiable legs LANDED at round 481, so
M5 is now the active arc per the owner directive; the Post-v1 backlog below is the
"any TypeScript project" horizon and stays parked until the owner re-scopes. The
M1–M3 campaign items still unchecked in the history file (M2.2/M2.3/M3.1–M3.4/M1.12)
hit their re-scoped v1 acceptance bar — "the shapes tsc's source uses" — when the
burn-down reached zero real FPs; reviving their full-completeness form is a
backlog-horizon decision, not queue debt.)

**PERF — the post-inversion performance arc (owner-approved 2026-07-20, round 618:
"proceed according to your recommendations"; measurements + rationale in the
round-618 session note and the rewritten docs/ARCHITECTURE-RETHINK.md § 6). Ground
rules: the INV rules unchanged, PLUS wall-clock claims are decided ONLY by
interleaved A/B medians — anything priced below the ±2% drift band folds into a
structural item instead of landing alone.**

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
- [ ] **EP.2 Multi-line expression printer formatting** — **BLOCKED OFFLINE
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
- [ ] **EP.0 Wire the emit-diff gate into the dashboard** — **BLOCKED OFFLINE
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
- [ ] **INV.7 Productization** (absorbs M5.5/M5.6). Native re-enable (the big-input
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

### Post-v1 backlog — the "any TypeScript project" horizon (parked 2026-07-03)

The top-to-bottom loop SKIPS this section until v1 (the 8 tsc-source profiles at zero
FPs) lands. None of these block self-compiling tsc. Each returns to the live queue
when v1 lands — or earlier if a live item genuinely needs one (promote per protocol,
with a session note saying why). Item IDs are stable; session notes reference them.

- [ ] **M2.4 DOM libs as an opt-in set** (dom.generated.d.ts is 1 MB+ — measure the
  parse/bind cost; ties into the shared-snapshot design). tsc's sources don't
  reference DOM — post-v1.
- [ ] **M3.0 Conformance generator extension.** Extend `generateTypeScriptTests` with
  a per-category allowlist for `tests/cases/conformance/` (5,907 files; keep all tsgo
  set-B filters). Start with the categories matching M3.1 (types/typeParameters,
  types/typeRelationships, expressions/functions). Each category lands only when its
  failures are triaged into queue items — never leave a category half-red without
  notes. Owner approval (2026-07-02) stands; optionally pull in early as an extra
  regression net if an M3 campaign wants the coverage.
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
- [ ] **M4.4 External sourcemaps** (`.js.map` files; inline maps exist).
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
