# Status

**THE PROGRAM WAS PARSED *TWICE* AND BOTH COPIES WERE KEPT — LANGUAGE-SERVICE RETENTION
**264 -> 177 MB (-33%)** (2026-08-25, (INC.36)).** A ten-step subtraction ladder over
`liveAfterGc` attributed the 264 MB a whole-program `referencesAt` sweep holds:
`Project.sourceIndexes` **114.7 MB**, the process-global `CrawlParseCache` **103.0**,
`RealLibSnapshots` 2.6, JVM baseline + lib text + the 9,827 answers 43.7 — and
**`cached`/`captures`/`prepared`/`narrowed`/`recheck`/`lineMaps` 0.0 MB COMBINED**, so
every memo (INC.12)/(INC.14)/(INC.32)/(INC.40) added is free and **`close()` frees
nothing**. The two big rows are ONE program parsed twice at the same bytes under the same
`computeParserFlags`; the class histogram says it independently — **770,460 `Identifier`s**
against 856,962 nodes in one copy, i.e. CLAUDE.md's 44.5%, DOUBLED. **The fix deletes one
copy**: `Project.sourceIndexOf` indexes tokens around the compiler's own crawl tree
(`parsedSourceOrNull` -> `SourceIndex.around`), nothing writes to the process-global cache
so round 825's threading discipline is untouched, and a dirty buffer still parses privately
— which is the CORRECT answer, collected lazily once a build sees those bytes. Measured
after arm (2 processes): peak **177.0 / 176.4** vs before's **264.0 / 264.6 / 264.5 /
264.1**, `sourceIndexes` **-27.5 / -27.6** vs **-115.3 / -116.4 / -115.8 / -115.3**, every
other row unmoved, `Identifier` HALVED to 388,790, **9,827 hits unchanged**. **The
remaining 27.5 MB is NOT a tree** — ~18 MB of `SourceIndex`'s own token arrays (byte-identical
before and after) and ~10 MB of a second copy of the source TEXT, a named next lever
(`SourceFile.text`) left unlanded rather than taken after the gates had run. **FOUR of the
five gates are CONTROLS and only the ladder is evidence**, because the compiler path never
calls the new function. Suite **15,835 / 0 / 3** (+4: 3 pins, 1 control), zero corpus
baselines moved; `cost_gate.py` PASSES with the counter vector identical to last round
(`mapped.hits` at the standing +1.63%, not moved, not rebaselined); `huge_methods.py
--fail-over 0` exit 0 with over-limit **0** and **782** classes scanned (781 last round, so
not blind); `partition-equivalence` **EQUIVALENT 78/78**; `capture-equivalence` **1,003 / 43
/ moreAny 0** with **BOTH DIGESTS UNMOVED**. Also this round: **(INC.35) DECIDED BY THE
OWNER — option (b), per-buffer only**, closed as a decision, not an implementation.
`docs/perf/language-service-retention.md`.

**(INC.38) CLOSED, DOC-ONLY (2026-08-25): THE HOST-FACING RECOMMENDATION FOR THE 1.39x
RE-DERIVATION TAX IS NOW WRITTEN DOWN.** The code half shipped already as (INC.40) (a
retained checker collects the floor across `diagnosticsOf` queries, 2.25-2.30x). What
remained — the recommendation to ask for the whole open set in ONE call rather than one
file at a time — is now `docs/language-service.md` § 3a. Numbers traced to their actual
source (§ 14's six-buffer table, `2fa8a39f`, 2026-08-24, not the (INC.14) session note,
which does not carry them verbatim): the same 6-file set costs **321-342 ms** asked once
against **748-771 ms** asked per file. No Kotlin source touched; `jvmTest`/`cost_gate.py`/
`huge_methods.py` not run (nothing to gate).

**A BARE TYPE PARAMETER WAS READ AS A *FAILED* CONSTRAINT WHERE THE HONEST ANSWER IS *UNDECIDED*,
AND A WHOLE ALIAS BODY RENDERED `any` ON EVERY ORDINARY BUILD (2026-08-24, (INC.42)).** Three
lines reproduce it, with no partition and nothing to do with `Visitor`:
`export type R1<T extends Nd> = T | readonly Nd[];` then
`export type A1<X extends Nd> = (n: number) => R1<X>;` renders **`(n: number) => any`** where tsc
7.0.2 over its own LSP (`--lsp -stdio`, round 924's oracle — read out, never hand-written)
renders `(n: number) => R1<X>`. **A CONSTRAINT MATRIX ISOLATED THE PREDICATE, AND THAT IS THE
DURABLE HALF:** an **unconstrained** inner parameter is **always** correct, and **every** row
whose inner alias's parameter carries a constraint read `any` — **including where the two
constraints are IDENTICAL**. So the shape is not "a wrong constraint"; it is "a constraint at
all". **Cause:** B57.1b skips an alias substitution when an argument fails its parameter's
constraint and judged that with `checkTypeRelatedTo`, which has **no "TypeParam source via its
constraint" rule** ((INC.30), deliberately) — so the reference answered `errorType` and rendered
`any`, and where the body is a function type the `any` lands in the RETURN position, which is how
tsc's own `type Visitor<…> = (node: TIn) => VisitResult<TOut>` rendered `(node: TIn) => any`.
**Nothing here could see it**: the capture sweeps are DIFFERENTIALS, blind by construction to a
defect both arms share ((INC.28)'s law), and a wrong-but-plausible type attaches no diagnostic,
so no corpus baseline moves. Every one of the seven pins therefore asserts the rendered STRING.
**THE FIX IS LOCAL AND BOTH ITS GATES WERE FORCED BY MEASUREMENT, NEITHER GUESSED.** The argument
is judged against its **own** already-resolved constraint — **no new rule enters
`checkTypeRelatedToCore`, so (INC.30)'s termination argument is untouched**. `aliasBodyDisplayDepth`
confines it to `resolveTypeAliasBody`: **unconfined it reads `output.errors` 46 -> 48** on the
compiler profile (an overload-resolution defect at `checker.ts:2503` that a no-longer-`any`
`VisitResult<T>` exposes, plus a TS2322 at `watchPublic.ts:576`) — **two dashboard false positives
for 213 hovers is not a trade**. `aliasGuardIsRecursionBrake` keeps today's answer where this
guard is the recursion brake rather than a constraint check, and **the brake lives in the
ENCLOSING declaration, not the referenced one**: the first gate written asked about the referenced
alias alone and left the corpus **RED**, and a **flip census** named the mechanism — the only four
decisions the relaxation flips in `excessPropertyCheckIntersectionWithRecursiveType` are
`Length<I>` and `Prepend<any, I>`, two **non-recursive** aliases referenced from inside the
self-referential `BuildTree`. Ablations, one mistake at a time: a1 (relaxation disarmed) reddens
the three value pins and nothing else; a2 (the enclosing leg deleted) reddens the recursion pin
**and** the corpus baseline, so that leg is load-bearing with a failure uniquely its own. **One
pin is recorded NON-DISCRIMINATING** — it stayed green under a2, and the prediction behind it
(that `diagnose` never reaches `resolveTypeAliasBody`) is FALSE; named rather than claimed.
**GATES.** Suite **15,831 / 0 / 3** (+7 pins), **zero corpus baselines moved**; `cost_gate.py`
PASSES with `output.errors` **46** and `mapped.hits` at the standing +1.63% (not moved by this
round, still not rebaselined); `huge_methods.py --fail-over 0` exit 0. `capture-equivalence`
**1,003 / 43 / moreAny 0** and `capture-channel` **1,273 / 64 / moreAny 168** — both exactly
(INC.28)'s baselines, unmoved. **Both capture digests moved BY DESIGN** (full
`8385940838610938556 -> -7005799195003297838`, narrow `-7423700524621287041 ->
-1948231081793666447`): the signature of a fix that corrects an ORDINARY build, and the **third**
time this arc has had to re-record rather than read a moved digest as a regression.
**SAY IT PLAINLY: THE 213 ROWS THIS ROUND WAS AIMED AT ARE *NOT* CLOSED.** `Inc41ClassifyMain`
re-run reads **796 rows / 37 pairs / 213 GAINED-INFERENCE — UNCHANGED**. Read out of the
classifier's dump rather than assumed, those rows are **not hovers on `Visitor`**: they are carets
on `visitEachChild` / `visitFunctionBody` / `discardVisitor`, function names whose rendered
**OVERLOAD SET** carries a `Visitor` parameter — the **CHECKING** path, where both arms render an
unbound parameter. It is blocked three times over, each cost measured ((INC.28)'s two corpus FPs;
this round's two dashboard FPs; and B50.5's deliberate refusal to name a pure function type, so
even with both closed we would render `(node: TIn) => VisitResult<TOut>` where tsc renders
`Visitor`). **A relation-engine item ((INC.30)) plus an alias-NAMING one, not a display bug** —
re-queued as **(INC.43)**; `docs/inc41-replay-capture-classification.md` § 6a is the authority.

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
