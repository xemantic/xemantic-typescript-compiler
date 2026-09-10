# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **191,591** lines (**−8,372 across (P18.53)-(P18.62)**; step 10a is a SEMANTIC change and ADDS 85, not an extraction; 191,070 when
the metric was created, and the (P18.9)-(P18.37) checker-parity arc ADDED ~5,200 in between, which
are fixes and pins rather than extractions — so the file is now BELOW where the metric started
WITH that work still in it). TEN collaborators extracted: `TypeInterner`, `Relation`+`Ternary`
and **`LexicalScopeResolver`** — ambient surface NONE for all three — `TypeInstantiator`
(4 reads, 1 write), `NameResolver` (2,284 lines, 26 reads), `Relater` (1,446 lines),
`MemberResolver` (834 lines, 22 reads), `MemberNames` (771 lines, 4 reads / ZERO writes),
`EnumSemantics` (1,138 lines, 13 reads / ZERO writes) and `CaptureRecorder` (3,214 lines,
61 reads / 9 writes — the arc's largest MOVE and its largest ambient ROW, which is
`docs/INVERSION-DESIGN.md` § 2's "the answers are functions of walk-scoped state" as a number
rather than an argument), all stated in the ledger. The ambient TOTAL first fell at (P18.59),
which wired `Relater` and `MemberNames` to `EnumSemantics` DIRECTLY. **STAGE 0 IS NOW
DECIDED-DOWN rather than exhausted**: (P18.61) sized what is left — 50.6% of the file is check
passes, whose candidate collaborators census at 59-97 ambient reads (`cae*`: 97 reads for EIGHT
declarations) — and turned the arc toward Stage 3. Reference points: tsc ≈ 50k lines (one file),
tsgo 60,479 across 25 files. Contract: `docs/INVERSION-DESIGN.md` § 10; ledger:
`docs/inversion-ambient-ledger.md`.

**(P18.62) — (INV.0) STEP 10a: THE B83.5 *TYPE* SPACE, AND THE ARC IS FUNNEL-SHAPED RATHER THAN RADIUS-SHAPED, 18,528 / 0 / 3 (2026-09-10).**
The round-748 scope-space consult widened from `enum`-only to the whole TYPE space —
`class`/`interface`/`type`/`enum` — at BOTH sites that resolve a bare type name.
**THE ITEM SIZED THE ARC BY THE WRONG QUANTITY**: "~357 `globals[` readers downstream" is a
real count of AD-HOC per-walker name probes, not of the resolution LADDER, which has two
funnels plus a small third — so step 10 decomposes 10a/10b/10c/10d and none of them sweeps 357
sites. **THE PRIZE, MEASURED OVER A 129-CELL MATRIX AGAINST BOTH REFERENCES**: at the 86 B83.5
cells, **106 lost true rows and 43 ours-only rows** (TYPE 24/60, VALUE 19/46), file-level
control 15/15 clean. **The variant split is the finding** — a UNIQUE scope-space name is 0
ours-only / 36 missing (it degrades to `any` and goes quiet), a SHADOWING one is 43/70,
resolving the OUTER declaration in 40 of 42 cells; nesting depth is irrelevant, so a
function-body-TOP declaration is as unbound as one three blocks deep. **TWO OF THE ITEM'S OWN
CLAIMS WERE WRONG** (its "1 ours-only TS2353" is a MISSING row, and round 748 closed the enum
half only in TYPE position) — the second round running that a queue item's facts needed one
command. **THE STAMP WIDENING IS FREE** (NodeKind 23..26 are contiguous, so two int compares
stayed two), and `SymbolFlags.ScopeTypeDeclaration` excludes `TypeParameter` deliberately —
folding TPs in would flood a gate whose whole job is to be empty. **A CONTROL STOPPED BEING
INERT**: the (INC.16) verify walk also fed the gate, which mattered the moment `class` was
admitted, because a named `ClassExpression` would then have entered it ONLY for a file that
also declares a scope-space enum. **THE ONE REGRESSION WAS A PRE-EXISTING DEFECT THIS EXPOSED**
— `keyof errorType` answered the CLOSED domain `string` under a comment saying its result "is
never displayed/checked meaningfully", which was measurably false; it was invisible only
because B83.5 kept such an alias at `any`, and `keyof any` IS the correct open domain, so
making the type real narrowed a correct superset into a wrong subset. Found by a marker
DIAGNOSTIC (`println` is swallowed by `runCli`), after delta-debugging showed the row needs
three ingredients at once. **A COUNTDOWN PIN FIRED AS DESIGNED** (round 748 wrote it saying "a
future widening has to change this pin on purpose") and **a vacuity guard caught its second
blind pin**, revealing that `arrayElementUnionAlias`' B83.5 workaround is now unreachable for
the shape it was written for. Grid 8×`added=0 removed=0`, cost_gate exit 0 (`globals.lookups`
−0.23%, `globals.misses` −0.24% — the consult answering before the miss), huge_methods exit 0
(842 classes), warning-clean.

**(P18.61) — (INV.0) STEP 9: THE DECISION, TAKEN ON MEASUREMENTS, AND THE SCOPE-SPACE ASCENT GETS ONE HOME, 18,520 / 0 / 3 (2026-09-10).**
`Checker.kt` **191,540 → 191,506**; `LexicalScopeResolver.kt` 124, **ambient NONE — the first such
row since step 3**; ledger row 12. **BOTH OPTIONS SIZED RATHER THAN ARGUED**: the check passes are
where the LINES are (96,830 of 191,499 attributed, 50.6%) and where the SEAMS are not — `cmam*` 83
ambient reads, `caas*` 59, type-node builders 68, **`cae*` 97 reads for EIGHT declarations**,
against rows 1-11's 0/0/4/26/22/4/13/45/21/5/61. **AND THE ALTERNATIVE HAD TO BE CORRECTED BEFORE
IT COULD BE TAKEN**: the queue item offered "open Stage 1" and Stages 1 AND 2 landed on 2026-09-02,
so the open stage is 3 — a queue item's own factual claims are worth one command to check, this one
would have sent a round at work that already exists. **WHAT B83.5 IS, MEASURED, AND NOT WHAT ITS
NAME SAYS**: `Binder` recurses into statements from exactly two places, so a `class` at the very TOP
of a function body — no block nesting at all — is as unbound as one inside an `if`; against
tsgo 7.0.2 that shape is 1 ours-only TS2353 and 0 of 4 true rows. **The sub-step is the INERT one
and it is about DUPLICATION**: the INV.2(c) ascent had been hand-copied FIVE times and the copies had
drifted on four axes, all deliberately, so they became parameters. **THE GATE IS THE 8-PROFILE GRID,
NOT THE CORPUS** — three of the five callers are name-GATED, so a wrong axis resolves a name to an
OUTER binding, which is silent; all eight read `added=0 removed=0`. **TWO TEXTS CORRECTED BECAUSE
THEY WERE FALSE**: `LexicalScope`'s "UNCONSUMED until INV.4" KDoc (five consults read those tables,
since round 748) which also proposed the very `existing` read round 748 refused; and both
`TypeOracle` refusals, which blamed the binder for what is a COMPOSITION problem — a refusal that
misstates its own blocker is worse than no refusal, because it sizes the next round wrong. Four of
five ablation arms discriminate; **arm 1 reddens ALL FIVE and is recorded as not being a single-pin
arm**. cost_gate exit 0, huge_methods exit 0 (842 classes), receipt now SEVEN binaries,
warning-clean. **The one question Stage 3 was unsized on is also ANSWERED**: `getTypeOfSymbol`/`getDeclaredTypeOfSymbol` answer correctly for a scope-space `Interface`, `Class`, `Function` and `Variable` symbol, so that arc does NOT have to begin with a transient-symbol route (6th pin, arm in `Checker.getDeclaredTypeOfSymbol`).

**(P18.60) — (INV.0) STEP 8: THE TYPE-CAPTURE FAMILY IS `CaptureRecorder.kt`, AND ITS AMBIENT ROW IS THE DESIGN'S OWN CLAIM AS A NUMBER, 18,514 / 0 / 3 (2026-09-10).**
`Checker.kt` **194,631 → 191,540** (−3,091, the arc's largest single move); `CaptureRecorder.kt`
3,214; ledger row 11. **Fifth extraction of the session.** **THE QUEUE ITEM'S OWN "OBVIOUS
CANDIDATE" WAS A SCATTER AND THE CENSUS SAID SO IN ONE COMMAND** — the member-ACCESS family is
eight neighbourhoods with no span — **and the same census found TYPE CAPTURE instead**: 103
`typeCapture*`/`captured*` declarations of which 94 sit in one 3,120-line block. Three rounds
running, the census has overturned the queue's guess. **61 AMBIENT READS AND 9 WRITES, THE ARC'S
LARGEST ROW, AND IT IS THE FINDING RATHER THAN A DEBT**: fourteen of the reads and all nine writes
are the WALK (`ctaFrames`, `currentFlowGraph`, `currentClassForThis`, `currentCheckFileName`,
`spineCurrentScope`, `inAsyncFunctionBody`, `currentTypeParamScope`), and the writes are a
save-and-restore sandwich reconstructing the ambient a node was reached under — moving the family
does not make that explicit, it COUNTS it. **The OUT surface is the arc's cleanest by the opposite
measure: 98 declarations move and 15 keep a caller.** **THE RECEIPT MUST BE THE CAPTURE CHANNEL,
NOT THE PASS TABLE** ((INC.2): they are different resolvers) — **381,666 captured types and 360,917
captured definitions with a per-arm DIGEST byte-identical across the move**, and the full-vs-narrow
divergence census identical row for row; a trap that cost one 10-minute run is that the digest line
sits ABOVE the driver's summary, so a `| tail -4` keeps the summary and throws the receipt away.
The 488 deterministic `--passTiming` lines are byte-identical too, so that receipt now spans SIX
binaries. **ALL EIGHT ABLATION ARMS REDDEN EXACTLY THEIR OWN PIN — the arc's first perfect
8-for-8**; two of them are a PAIR over one dangling-`.`-at-EOF span, where the type table stays
FIRST-wins while the member table takes its descendant exception. **Two candidates were dropped
for a reason worth more than a ninth pin**: neither `activeParameter`'s rest-clamp nor a scope
name's KIND can be given ground truth by any instrument here, and a pin whose expected value can
only be read off the function it tests is not a pin. **PrintInlining says something real for the
first time since step 4b-ii**: `typeCaptureVisit`, called per node from `spineEnterNode`, was a
925-byte body refused six times as `too large` and its hop reads `inline ×6`. ab-interleaved
+52 ms (+0.20%) B-wins-3/6 NOISE-DOMINATED; cost_gate exit 0, huge_methods exit 0 (841 classes,
`Checker.<init>` 5,697 → **5,621**), warning-clean.

**(P18.59) — (INV.0) STEP 7: THE ENUM FAMILY IS `EnumSemantics.kt`, AND THE ARC'S AMBIENT TOTAL FALLS FOR THE FIRST TIME, 18,506 / 0 / 3 (2026-09-09).**
`Checker.kt` **195,606 → 194,631**; `EnumSemantics.kt` 1,137; ledger row 10. **Fourth extraction
of the session.** **THE CENSUS THE QUEUE ITEM DEMANDED DECIDED THE ROUND, AND TWO OF ITS THREE
CANDIDATES ARE NOT SEAMS**: SIGNATURES is a scatter over six unrelated neighbourhoods, FLOW is 64
declarations of which 22 are singletons or pairs spanning lines 1091 to 125482, and only ENUM is a
family — one contiguous 1,013-line span, 40 declarations, **13 ambient reads and ZERO writes**.
**AND THE STAGE-0-EXIT DECISION ROW 9 ASKED BE TAKEN DELIBERATELY IS TAKEN, FOR ONE LINE**:
`Checker`'s construction block already IS the "explicit construction graph in dependency order"
the ledger called for, so wiring `Relater` and `MemberNames` to the new collaborator DIRECTLY was
placing it before them — **`Relater` 49 → 38 checker reads (−11 over 20 sites), `MemberNames`
5 → 4**, the first fall in the arc's ambient total. **A MASKING DEFECT EVERY EARLIER ROUND OF THIS
ARC SHARED, found by the compiler**: `spanmask`/`strip` blank whole string literals, so a `${…}`
INTERPOLATION — which is code — is invisible to the ambient census and unrewritten by the
transform; it failed loudly here only because a `Checker` member is unreachable from a
collaborator, so the compiler is a complete detector for the ambient case and NOT for the census.
`codemask.py` fixes it and both verbatim proofs are taken with it. **THE RECEIPT NOW SPANS FIVE
BINARIES** — the same 488 deterministic `--passTiming` lines byte-identical for pre-step-5
pristine, steps 5, 6a, 6b and this: one receipt over 3,522 moved lines. **PrintInlining is FLAT
and that is the honest reading** (182 → 191 `too large` over 20 sites): this family is 40 small
functions, not one monolith, so there was nothing to remove — the one real gain is
`isEnumFlavoredObjectType`, zero inlines at 21 call sites before and 37 after. **A trap that
manufactured a fake +137 first**: the twelve members that were `internal` on `Checker` are
JVM-name-MANGLED there and plain members of an `internal class` after, so a matcher requiring a
space after the name reads ZERO rows in the pristine arm. **SEVEN OF EIGHT PINS DISCRIMINATE
EXACTLY AND THE EIGHTH WAS BLIND** — its fixture resolved both sides through ONE import, so the
two member symbols were identical and the ablation could not bite; two further shapes were probed
ON THE ABLATED BINARY and the pin is now the one that reads 1 row vs 2. Arm 3 reddens TWO pins and
that is recorded, not smoothed. ab-interleaved −120 ms (−0.46%) B-wins-3/6 NOISE-DOMINATED;
cost_gate exit 0, huge_methods exit 0 (839 classes, `Checker.<init>` 5,721 → **5,697**),
warning-clean.

**(P18.58) — (INV.0) STEP 6b: THE MEMBER-NAME / LATE-BINDING FAMILY IS `MemberNames.kt`, THE ARC'S CLEANEST SEAM, 18,498 / 0 / 3 (2026-09-09).**
`Checker.kt` **196,176 → 195,606**; `MemberNames.kt` 765; ledger row 9. **Third extraction of the
session.** **FIVE ambient reads, ZERO writes** — against the relater's 45/5 and member
resolution's 21/1 — **and the reason generalises: this family owns NO STATE and answers a
SYNTACTIC question.** Four of the five reads belong to seams of their own (three enum-value
helpers, two AST helpers); rows 7 and 8 read the type system because they ARE the type system.
`fileResults` is a CONSTRUCTOR INPUT rather than a read, which is what takes the row from 6 to 5 —
rows 5/6's above-line-666 rule paying off. **`MemberResolver` was deliberately NOT rewired**: it
keeps calling `checker.getMemberName` / `declaredMemberName`, which the delegations serve anyway,
so the two collaborators carry no construction-ORDER dependency. **The split removed 61 `too
large` inlining refusals and added none** — the fifth row running (`getMemberName` `16 inline +
16 too large` → a hop reading `7 inline`, zero refusals; `computedLiteralKey` `20+20` → `6`).
**THE RECEIPT NOW SPANS FOUR BINARIES** — the same 488 deterministic `--passTiming` lines are
byte-identical for pre-step-5 pristine, step 5, step 6a and this: **one receipt over 2,509 moved
lines**, at one extra build for the whole session, because each round's capture is the next
round's pristine arm. **THE PINS ARE *AGREEMENT* PINS**, which is what this family needs: a
member's name is asked at REGISTRATION and again at RESOLUTION, and both known failures (round
935, (CHK.40)(c)) emit a CORRECT diagnostic beside a false one, so a pin asserting "it compiles"
passes on a broken binary — each pin instead reads the member back through a wrong target type and
asserts the TS2322 that names the resolved type AND the absence of TS2339 beside it. **Every one
of the 5 pins discriminates, the session's first round where that is true**; the hop-limit PAIR
brackets `LATE_BIND_ALIAS_HOPS` (a 2-hop alias chain late-binds, an 11-hop one does not) and
neither pin alone is evidence. **A naming trap worth carrying: `Checker.kt` already declares
EIGHT locals named `memberNames`**, so the collaborator field is `memberNamer` — a field of the
shadowed name compiles and broke the round's own structural check. ab-interleaved −182 ms
(−0.70%) B-wins-3/6 NOISE-DOMINATED; cost_gate exit 0, huge_methods exit 0 (838 classes),
warning-clean.

