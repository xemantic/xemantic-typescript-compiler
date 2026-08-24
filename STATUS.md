# Status

**THE CAPTURE VALVE STAYS DIAGNOSTICS-ONLY: THE REPLAY IS NOT A *DIFFERENT* DEFECT, IT IS **MORE
OF** THE ALIAS-DISPLAY RACE, AND IT GETS WORSE THE LONGER THE SESSION RUNS (2026-08-24,
(INC.41)).** The standing reason (`docs/language-service.md` § 4a) said the 43 `DIVERGE-TYPE`
files were the union-alias family "in which the fresh arm is not automatically the correct one" —
inferred from (INC.26), never tested. **Tested against tsc 7.0.2's own LSP, it is FALSE for this
population.** `compared 373,879` captured type spans over 75 files -> **796 divergent (0.213%) in
43 FILES** (41 basenames — tsc has THREE `utilities.ts`), classified per ELEMENT and
nesting-aware per (INC.23) into **37 distinct `(fresh, replay)` pairs**, with **192 rows carrying
more than one differing element** (a row count over-reports, exactly as (INC.23) found):
**REPLAY WORSE 413 rows / 36 files, BOTH WRONG 375 / 17, REPLAY BETTER 8 / 4, EQUIVALENT 0.** All
37 causes were sampled through `--lsp -stdio` — **100% coverage BY CAUSE**, ground truth read out
of tsc rather than hand-written (round 924).
**THE MECHANISM IS THE DURABLE HALF.** `aliasDisplayMap` is **id-keyed FIRST-WINS** over INV.5(a),
which interns a union by its member-id list ALONE, so a registered alias renames that interned
union *everywhere* whatever the reference site spelled. A fresh narrowed build resolved
essentially only the queried file; **the replay carries the seed build plus every earlier
recheck**, so more aliases are registered and more unions get renamed. **393 of the 413 are that
one shape** — tsc and the fresh arm render `Identifier | PrivateIdentifier`, which
`utilitiesPublic.ts:857` literally writes, and the replay renders `MemberName`. So the replay's
answer would depend on what the user looked at EARLIER, and **a differential taken after one
query understates a first-wins display defect**. (INC.27) already refused the mitigation with a
proof. The remaining **20** are genuine lost resolutions (`Connection[][]`, `Map<string,
SeenPackageName>`, a bare `T` -> `any`) and are the only bug in the replay itself.
**THE PRIZE WAS MEASURED BEFORE THE RECOMMENDATION** (`Inc41HoverPriceMain`, both arms asked the
SAME caret, 40 targets x 4 ABBA rotations, 6 warm-ups, vacuity control 160/160): arming 188 ms,
ONE hover fresh **121 ms** (p90 234), ONE hover replayed **33 ms** (p90 143) — **3.67x, 88 ms**.
**But name the row**: `quickInfoAt` memoises per BUFFER (~2-4 ms for a second caret) and any edit
drops the handle, so it is bought once per *(file, program state)* pair — **the first hover in a
file at a program state some earlier query already built for, with no edit since** — and
`completionsAt`/`signatureHelpAt` get nothing at all ((INC.32) defect 1). **REFUSED against
(INC.2)'s bar**, which turned capture narrowing down over **45** divergent spans of 381,666
(0.012%): 413 of 373,879 is **0.11%, nine times that**, in the same silent direction. Two things
would change it and both are worth more on their own merits — wiring completions/signature-help
to `prepared`, which is NOT free ((INC.33) refused the widening it needs at +25.1 s and 54.4 M
records, so the prepare-amortised case still needs measuring), and closing the 20 lost
resolutions, after which what remains is an owner-level logical-parity conversation.
**A SEPARATE, PRE-EXISTING, ORDINARY-BUILD DEFECT FELL OUT AND IS QUEUED AS (INC.42)**: the 375
BOTH-WRONG rows are on **every build**, 213 of them `Visitor`/`VisitResult<T>` rendering
`(node: TIn) => any` where tsc renders `Visitor`. The capture sweeps are DIFFERENTIALS and are
blind to it by construction ((INC.28)'s law), so its pin must assert the VALUE, never that two
arms agree. **No `.kt` file and no compiler behaviour touched — suite unchanged at 15,824 / 0 /
3, and every sweep and gate is a CONTROL this round, deliberately not re-run.** Every figure here
is WALL TIME on one box, pinned by NO test; `docs/inc41-replay-capture-classification.md` is the
authority and carries the re-take instructions.

**A CAPTURE REQUEST IS PRICED PER *ANCHOR* WHERE AN EDITOR NEEDS A PRICE PER *ANSWER* — SO WIDENING
THE HOVER TO SERVE COMPLETION IS A **LOSS**, MEASURED AND REFUSED (2026-08-24, (INC.33)).** After
(INC.32) a completion in an already-hovered buffer still BUILDS (~201-228 ms), because a hover's
file-wide request carries `spans` and a member completion asks `memberSpans`. That is CORRECT
((INC.14): an answer that was never asked for is ABSENT), so the only fix on offer was to widen the
file-wide capture with member/scope/signature anchors — exactly as (INC.13) widened the TYPE channel
from a caret to a file for **+9-17 ms**. **It does not transfer.** Cold narrowed builds through
`ProjectCompiler` with the memo bypassed, two batches (batch 2 replicating batch 1 on every sign),
every population biased IN FAVOUR of the widening: the widened hover costs **+286 ms on `binder.ts`**
(300 -> 586, the two arms' ranges DISJOINT in both batches) and **+25.1 s on `checker.ts`**
(3,624 -> 28,751) to save a completion build of **204 ms / 2,078 ms** — **break-even 1.40 and 12.1
completions per hover IN A BUFFER WITH NO EDIT SINCE**, where the dominant completion path types a
`.` first, which is an edit, which clears the memo. Even the cheapest shippable variant
(occurrences + members, no scopes) is +96 ms on `binder.ts` for 0.47 but **+3,326 ms on `checker.ts`
for 1.60**, and makes EVERY hover ~32% dearer to serve a case reachable only when nothing has been
typed.
**THE SECOND REFUSAL IS INDEPENDENT AND HARDER: RETENTION.** One widened entry holds **798,531**
records for `binder.ts` and **54.4 M** for `checker.ts` — **48x and 205x** today's file-wide hover
entry, of which **49,879,917** are `CapturedName`s — and (INC.32) keeps `CAPTURE_MEMO_BUFFERS` of
them. That is structural, not incidental, and `CapturedScope`'s own KDoc already recorded it: a
free-name caret sees hundreds of names, almost all lib globals, and a widened request repeats that
set at **every one of 13,601 anchors — O(anchors x globals)**.
**WHAT WOULD FLIP IT IS NOT A WIDER REQUEST**, and that is the transferable half: only a re-entrant
capture against a RETAINED checker ((INC.17)'s `ProgramRecheck`) can answer a span nobody asked for
up front without a new build. It is behind (INC.40)'s diagnostics-only valve because its
captured-TYPE channel diverges from a fresh build in **43 of 75 files**, so **(INC.41) is now the
named unblocker for the whole caret-channel latency story** — with the rider that free-name
completion additionally needs the `CapturedScope` per-anchor globals fix whichever mechanism serves
it. **No compiler code changed and the suite is unchanged at 15,824 / 0 / 3**; the instrument is
kept so the refusal is re-takeable (`scripts/inc33-widen-cost.sh` + `Inc33WidenMain`'s KDoc, which
is the authority for the table), REFUSES rather than skips when its profile or runner is absent, and
carries an ablated positive control — empty output, a zero population and a sub-50 ms base arm each
refuse, because exit 0 says the JVM finished, not that anything was measured. Every figure here is
WALL TIME on one box and is pinned by NO test; re-take it rather than quoting it.

**AN ERROR-REPORTING QUERY IS 104-108 ms -> 25 ms — THE RE-ENTRANT REPLAY IS **2.25-2.30x**, NOT
THE "DECAYING 1.68x" FIVE ROUNDS RECORDED, AND IT IS NOW WIRED FOR DIAGNOSTICS BEHIND A TYPE-LEVEL
VALVE (2026-08-24, (INC.40)).** The lineage was not wrong about the floor shrinking; it was
measuring the wrong thing. **Every figure in it carried a whole-file `TypeCaptureRequest` in BOTH
arms** — the request `replay-differential.sh` needs in order to GRADE the mechanism — and that is
+9-17 ms per query of cost common to both arms, which **dilutes a ratio and leaves no trace in
it**. Measured that way at HEAD the same run still reads 1.34x; the diagnostics channel asks for no
capture at all, and it is exactly the channel the differential grades as EQUIVALENT. Re-priced in
**two independent JVMs**, warm, six warm-ups, leading draw discarded, ABBA-rotated, tsc's own 78
sources: at `k = 1` **10,656 / 10,783 ms fresh against 4,728 / 4,685 replay = 2.25 / 2.30x**, per
query **104 / 108 ms -> 25 / 25**; at `k = 2` 1.72 / 1.81x; at `k = 8` 1.26 / 1.25x. **The ratio
falls with the working set exactly as it must** — the thing the replay deletes is the floor, paid
once per QUERY and not per file — and **the replay arm's TOTAL lands on the whole-program CHECK
cost** (4,728 against ~4,935 ms), i.e. (INC.37)'s **1.39x re-derivation tax being COLLECTED rather
than re-paid**: 77 fresh checkers each re-derive the shared lib and foreign-declaration
resolutions, one live checker does not. Floor **54 ms**, cross-checked against
`partition-equivalence.sh`'s 61 ms at the same commit.
**IT SERVES DIAGNOSTICS AND NOTHING ELSE, AND THE REFUSAL IS A *TYPE* RATHER THAN A COMMENT.**
`replay-differential` at HEAD: **0 `DIVERGE-DIAG`, 0 `DIVERGE-DEF`** on both arms (46 rows over
tsc's sources, 178 over the 71 `partition-gate` files carrying them, 352,713 definition spans),
against **43 `DIVERGE-TYPE` of 75 files** — the pre-existing HEAD state, overwhelmingly the
union-alias display family (INC.26)/(INC.27) in which the FRESH arm is not automatically the right
one. So `Project.diagnosticsOf` holds the live program through `DiagnosticsOnlyRecheck`, a private
one-way valve whose single member takes a `Set<String>` and returns a `List<Diagnostic>`: **no
`TypeCaptureRequest` is expressible at that boundary and no `CapturedType` can leave it**, so the
caret channels cannot reach it even by mistake. The handle is dropped by `updateFile`,
`deleteFile` and `close`. Queued as **(INC.41)**: closing those 43 is what would let captures
through the same valve, and the prize for it is NOT measured.
**THE ABLATION'S ONE TRANSFERABLE ARM.** Four arms, one mistake each, each verified as a real diff
against the committed file — and in a3 (**the handle serves without widening, so it answers an
empty list**) **the build-COUNT pin stays GREEN**. A count-only suite would have shipped a
language service reporting no errors at all, at full speed, with every cost pin green; the value
pins are what redden. One pin is recorded as UNDISCRIMINATED rather than claimed as coverage.
**TWO INSTRUMENT TRAPS, BOTH FAILING TOWARD A PLAUSIBLE TABLE**: a **floor build is its own code
path**, so floor draws taken after whole-program warm-ups read 129/89/96 ms against a true 52-56 —
a 1.7x over-read that would have understated every derived ratio, now drawn at the END and
cross-checked against the other instrument; and **arming is priced, not assumed free** (an armed
77-query sweep reads 10,546 ms against 10,783 plain, changing no diagnostic row in 231 group
comparisons). Suite **15,824 / 0 / 3** (+9 pins), 0 warnings, `partition-equivalence` EQUIVALENT
78/78, `partition-gate` sensitivity 75/75, `cost_gate.py` all counters in band (`mapped.hits`
+1.63%, the standing drift, NOT rebaselined), `huge_methods --fail-over 0` 0 over limit.
`docs/language-service.md` § 4a.

**A MEDIAN NARROWED QUERY IS 108 ms — INDEPENDENTLY REPRODUCED — AND ITS OTHER HALF IS NOW
DECOMPOSED: `checkSpine` IS 89-92% OF A FILE'S OWN CHECK, SCALING IS **LINEAR** WITH
`checker.ts` AT THE *p10* PER NODE, AND Σ`own(F)` IS **1.39x** THE WHOLE-PROGRAM CHECK
(2026-08-24, (INC.37)).** (INC.3) decomposed the incremental FLOOR; nothing here had ever
split the other half — the queried file's own checking, which after (INC.1)-(INC.32) is the
dominant term. `own(F) = build(recheckOnly={F}) − build(recheckOnly={a name not in the
program})`, per wall and per pass, over all 78 of tsc's own sources, with a control built
in: every floor-resident pass row cancels to ~0 in the subtraction and the per-pass sum
reconstructs the wall to 2-4%. **Floor 56 ms; `own(F)` min 4 / median 52 / p90 138 / max
1,726; query median 108 ms** — which reproduces (INC.31)'s 108-113 ms from a completely
different measurement. **THERE ARE TWO LATENCY PROBLEMS AND THEY NEED DIFFERENT LEVERS**: at
the median file the floor is still the LARGER half (52%), and at the tail — the file a user
is most likely to be in — it is **3.1%** and everything is `checkSpine` on one file.
**THE SCALING ANSWER IS A NEGATIVE RESULT AND IT RETIRES A STANDING SUSPICION.** `own(F)` is
LINEAR in the file's NODE count, and `checker.ts` is at the **p10** of per-node cost —
**6.27 µs/node against a population median of 9.71** over the 51 files with >2,000 nodes
(`parser.ts`, 5.8x smaller, reads 6.08). Its 1,726 ms is 275,478 nodes at a below-median
price: **there is no quadratic to find and no structural lever inside the big file, only the
constant factor per node.** Confirmed independently — Σ per-file `spineNodes` over the 78
narrowed builds is **856,962**, the whole-program figure to the node, so the walk partitions
exactly. **AND THE PROXY THAT WOULD HAVE INVENTED THE OPPOSITE ANSWER IS BYTES**: 76 -> 739
µs/KB among files >100 KB, and every byte-based regression in this round's first pass
predicted `checker.ts` LOW by 1.2-4.3x — i.e. would have been read as a super-linearity that
is not there.
**WHAT IS INSIDE `own(F)`.** `checkSpine` is **89-92%** of it above ~15,000 nodes (1,576 of
1,771 ms on `checker.ts`). The ~400 partition-scoped tail walkers are 10.5% there and 5-7%
elsewhere, and they are FLAT — 78 rows above 0.5 ms, largest **11.45 ms = 0.65% of the
query** — so round 830's arithmetic closes them: there is nobody to make cheaper. Inside
`checkSpine` the four disjoint type-system rows (relation 39, type-node 43, member 7, flow
narrowing 167) are **255 ms = 16.2%**, so **84% is the walk and the handler bodies** —
round 758's whole-program result, sharper under a partition.
**ROUND 847's SIX-HANDLER SET IS CONFIRMED (65.7% vs 63.0% whole-program-warm) AND ITS ORDER
IS REFUTED.** `cpaSpineLeave` is **22.9%** here against round 847's third place;
`ccetSpineLeave`, its first at 18.2%, is **10.9%**; and the top-three permutation differs on
all three of `binder.ts` / `parser.ts` / `checker.ts`, with `binder.ts` putting a handler in
nobody's top six second. Round 847 explained its own swap by differing warm-up RATES — every
arm here is warm, so what is left is the POPULATION. **A per-handler ranking is a claim about
a codebase's shape, not about the compiler: quote the file with it.**
**THE SURPRISE — A 1.39x RE-DERIVATION TAX, AND IT IS THE LARGEST MEDIAN-CASE TERM NAMED.**
Σ`own(F)` over 78 files is **6,841 ms** against a whole-program check of **~4,935**. The walk
partitions to the node, so the extra **1,906 ms** is shared type resolution (lib types,
foreign declarations, instantiations) a full build resolves once and every per-file query
re-derives inside its own fresh `Checker` — averaged, **~24 ms per query, roughly HALF the
median file's entire own check.** Same object (INC.14) measured from the other side. Queued
as **(INC.38)**, whose actionable form is NOT automatic working-set growth ((INC.14) refused
that on `k·floor + k(k+1)/2·perFile` arithmetic) but a host asking for its whole open set in
one call: 342 ms once against 771 asked per file. **(INC.39)** carries the other lever — the
three big handlers, 645 ms the object on `checker.ts`, prize NOT measured.
**THREE INSTRUMENT TRAPS, ALL OF WHICH FAILED TOWARD A PLAUSIBLE TABLE**, which is the most
reusable part of the round: the `dispatch` tier needs a warm-up OF ITS OWN (its by-id
dispatcher is not the production walk — run 1 read a handler at **82 µs per consultation**,
a warm-up artifact wearing a handler's name); **a six-point size ladder cannot answer the
scaling question at all**, since `corePublic.ts` (1,337 B) costs 7.5 ms and `es2019.ts`
(1,533 B) costs 26.5 — 3.5x at the same size, so the fitted intercept is whichever small
file you drew, and it was replaced by the 78-file sweep; and round 846's `full`-tier
inflation of `checkSpine` reconfirmed here at **1.08-1.37x**, so every per-pass ms comes from
the `rows` tier and the probe's timestamp pair is measured in situ (34-36 ns) rather than
inherited. Instrument only — no compiler behaviour touched, no checker code, no compiled core
method — so `cost_gate.py` and `huge_methods.py` are CONTROLS and were deliberately not run.
Suite **15,815 / 0 / 3** (unchanged). `docs/perf/file-check-decomposition.md`.

**THE LANGUAGE SERVICE IS 10-24x FASTER THAN ITS OWN DOCUMENTED COST TABLE, AND THE ONE DEFECT
THE NEW TABLE EXPOSED IS FIXED: A MEDIAN NARROWED DIAGNOSTICS QUERY IS 108-113 ms — 43-47x A FULL
REBUILD — AND AN ORDINARY HOVER AFTER TWO OTHER CHANNELS WENT 324 ms -> 4 ms (2026-08-24,
(INC.31)+(INC.32)).** Every wall figure on `docs/language-service.md` was round-930, i.e. taken
before (INC.2b) narrowed the capture path and before (INC.1)-(INC.30) took the incremental floor
from 1,092 ms to 58 ms. Re-taken on tsc's own 78 sources (9,977,097 chars), warm, six warm-up
cycles, medians with their draw lists, **every row reproduced in two independent JVMs**:
`diagnosticsOf(f)` median **1.1-1.2 s -> 108-113 ms** (p90 202-219), `completionsAt`
**~4.7-5.1 s -> 194-202 ms**, `signatureHelpAt` **190-214 ms**, `documentHighlightsAt(binder.ts)`
cold **~15x**, first hover on `binder.ts` **610 -> 290-306 ms**, against a **4,864-5,096 ms** full
rebuild that is unchanged and is the anchor. **A REAL KEYSTROKE COSTS THE NARROWED PATH
NOTHING EXTRA** — identical bytes 212 ms, an appended comment 247, an inserted statement 218,
a statement introducing a TS2322 215 — which no earlier harness here could say, because they
all dirty a buffer by writing its own bytes back.
**THE HALF THAT DID NOT MOVE IS THE HALF A PLUGIN AUTHOR HAS TO DESIGN AROUND, AND IT CANNOT
MOVE.** `referencesAt` (8.8-9.6 / 13.2-13.9 s), `renameAt` (20.0-21.3 / 25.0-26.0 s) and a
project-wide `diagnostics()` (4,864-5,096 ms) never enter `captureIn`'s partition, because
their claim is about every file; that column is now marked on the page rather than left to
be inferred. The heap claim is corrected in the direction an IDE budgets: not "~1.9 GB peak, 512 MB not enough" but
**1,077-1,125 MB peak in G1 old gen with 264 MB RETAINED** after a full GC — green at `-Xmx2g`,
OOM at `-Xmx1g`.
**TWO LEVERS WERE REFUSED BY MEASUREMENT BEFORE BEING BUILT, WHICH IS THE ROUND'S CHEAPEST
PRODUCT.** (a) Memoizing `SourceIndex`'s derived populations — on a memo hit `captureAround`
still re-derives the file's occurrence set — decomposes to **1.21 ms on a 17.9 KB file, 2.27 ms
at 194 KB and 82.7 ms at 3.15 MB**, closing the arithmetic to 0.4% of the measured 83 ms
second-caret hover on `checker.ts`; at the MEDIAN file the whole prize is **1-2 ms**, and it is
not a `referencesAt` lever either (~140 ms of a 9.3 s sweep = 1.5%). (b) "Completions over-capture the
file" is simply **FALSE** — the three call sites already pass a single caret span, so their
194-202 ms IS the narrowed build, and since a completion is by definition asked on a just-edited
buffer, **no cross-edit memo can ever serve it**.
**THE DEFECT: A MEMO BOUNDED BY ENTRY *COUNT* LET A ONE-SPAN ENTRY EVICT A 125,289-SPAN ONE.**
`Project.captures` was an access-ordered LRU bounded at two ENTRIES. Hover, go-to-definition,
highlights and `fileSemantics` ask ONE file-wide question per buffer (125,289 spans on
`checker.ts`); `completionsAt`'s two branches and `signatureHelpAt` each name exactly ONE span.
So **hover -> completion -> signature help -> hover, with no edit anywhere in it**, threw the
hover's file-wide entry out and paid a whole narrowed rebuild for the last step. **The fix is not
a larger limit** — that would double the worst case to buy a case needing no extra memory at all
— but a bound on WEIGHT, in two lanes that cannot evict each other: at most 4 spans is
caret-scoped and bounded at 4 entries, everything above it is buffer-sized and bounded at 2,
unchanged since (INC.13). **Worst-case retention is two buffer captures (UNCHANGED) beside 16
answers = 0.013% of ONE file-wide capture**, and invalidation was re-audited rather than assumed
(`cached = null` at exactly three sites, every one clearing `captures` in the same breath).
**THE PIN LESSON IS THE TRANSFERABLE ONE, AND IT NEEDED A SECOND ARM TO FIND.** Ablating the
count-based eviction put **3 of 13 RED**; the fourth new pin — *"the caret lane is BOUNDED too"* —
stayed **GREEN, correctly, because a stricter bound cannot fail a bound pin**. Only a second arm
(caret lane unbounded, 4 -> 4096) turned it red. So all four pins do fail against the mistake each
actually names, but *"I wrote four pins and three went red"* would have been the wrong summary.
Suite **15,815 / 0 / 3** (+4 pins); `cost_gate.py` and `huge_methods.py` were CONTROLS and
deliberately not run — no checker code and no compiled core method is touched, the change being
confined to the `-project` module's memo policy. **FOUR FOLLOW-UPS QUEUED**: (INC.33) the caret
channels are cold per channel per buffer (a hover's request carries `spans`, not `memberSpans`, so
a completion in an already-hovered buffer still builds at 201-228 ms — correct, and the widening
is unpriced); (INC.34) the `SourceIndex` refusal, recorded with the instrument that can re-open
it; (INC.35) project-wide `diagnostics()` at ~4.9-5.1 s is now the biggest number in the service by
3x and is **BLOCKED-PENDING-USER**, because round 772 measured reverse-dependency closure DEAD on
*this* corpus (tsc's own sources are `export *` barrels) — it would pay off on a layered
application and buy the benchmark nothing; (INC.36) the `-Xmx2g` floor, where the 264 MB retained
is the number to attack rather than the seconds.

