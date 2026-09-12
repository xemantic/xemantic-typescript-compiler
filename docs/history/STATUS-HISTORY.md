**(P18.63) — (INV.0) STEP 10b: THE VALUE SPACE, AND THE HALF THAT HAD TO BE REFUSED, 18,536 / 0 / 3 (2026-09-10).**

**(P18.64) — (INV.0) STEP 10c: HERITAGE AND QUALIFIED NAMES, AND THE RESOLVER THE ITEM NAMED WAS NOT THE ONE THAT MATTERED, 18,541 / 0 / 3 (2026-09-10).**
The item pointed at `NameResolver.resolveHeritageBaseSymbol`; **it was given the consult and
NOTHING MOVED.** A base type's MEMBERS come from `Checker.getTypeFromBaseTypeExpression` — a
FOURTH resolver, the one `resolveBaseTypesLazy` calls — so `interface J extends I` with a
block-scoped `I` had been resolving `J` perfectly since 10a and inheriting nothing; and the
`implements` VERDICT comes from a FIFTH probe of its own, which is why a class with a
block-scoped `implements` target reported the whole-class **TS2420 about the OUTER
interface** where both references report the per-property **TS2416** about the inner one.
Three resolvers where the item named one, each found by landing a patch and measuring it
INERT. **PRIZE over a 25-cell matrix** against tsgo 7.0.2 and pristine 6.0.3, which agree on
all 25, file control 5/5 clean: **10 ours-only / 28 missing → 4 / 20**; `interface extends`
2/6 → 0/2, `implements` 2/4 → 0/4, qualified enum root 4/6 → 2/2. **THE QUALIFIED ROOT IS
ADOPTED ONLY ON EVIDENCE**, and the asymmetry is `declareLexical`'s rather than the
consult's: its enum arm publishes members onto the scope symbol's `exports` and its
`ModuleDeclaration` arm does not, so a memberless root is deliberately left alone — adopting
it turns a wrong answer into NO answer and loses every member that did resolve, the one
place in this arc where resolving correctly is measurably worse. **NOT CLOSED, AND THE BASE
IS NOT THE REASON**: `class D extends B` needs `new D()`, i.e. 10b's VALUE half, because the
DERIVED class is scope-space too. **ABLATION: five arms, union 5 of 5 — every pin
discriminates.** The FALLBACK arm reddens a strict SUBSET of the removal arm's set and the
survivor is the UNIQUE pin, i.e. round 748's ordering law re-measured one resolver over; the
`implements` arm reddens BOTH its pins including the negative control (without the consult a
unique target resolves to nothing and the walker `continue`s, so the row that must fire
disappears too); the evidence-gate arm is 0 RED and recorded as UNDISCRIMINATED, because its
whole population is the `namespace` kind, whose qualified reads are wrong both ways today.
**COST IS EXACTLY ZERO AND THAT IS EXPECTED, NOT A GREEN LIGHT**: all 20 counters identical
to the 10b run to the last digit, because tsc's own sources carry no scope-space heritage
base — the grid is a control here and the reference matrix is the measurement. Grid
8×`added=0 removed=0`, cost_gate exit 0, huge_methods exit 0 (844 classes), warning-clean.

`Checker.getTypeOfIdentifierCore` now OVERRIDES a conventional answer with the scope-space
`function` / `class` / `enum` / `namespace` visible at the node — and never replaces
silence. **THE UNIQUE HALF WAS BUILT, MEASURED AND REFUSED, WHICH IS THE ROUND**: a consult
that answers a unique B83.5 value name is correct and takes the 129-cell matrix to **9
FIXED + 9 IMPROVED**, and adds **19-20 ours-only rows to EVERY ONE of the eight profiles** —
two families, both pre-existing gaps `any` was masking ((CHK.50)'s law at scale): an object
literal of SHORTHAND nested functions against a declared interface (9 sites, and
`utilities.ts:1219` names its own mechanism — an inferred `() => U[]` leaking an
unsubstituted type parameter out of `arrayFrom`), and a `| undefined` read after an
assignment narrowing whose receiver only became real because a nested function did (10
sites). Both are now 10b-ii, whose switch is ONE line. **THE SHIPPED HALF'S RECEIPT** is the
same matrix with the reference arms reused verbatim: **8 cells IMPROVED, 0 REGRESSED, 0 new
rows absent from pristine, 0 pristine rows dropped**, all 8 shadow cells flipping OUTER →
INNER in agreement with tsgo 7.0.2 and pristine 6.0.3 (B83.5 shadow agreement 16/42 →
24/42), the TYPE half numerically untouched. **THE LADDER POSITION IS THE FIX AND THE FIRST
CUT PROVED IT BY BEING WRONG** — written as a rung BELOW `currentLocalTypes` it measured 9
cells fixed and moved no shadow cell at all, because that map is a flat COPY of the
enclosing scope. **AND THE POSITION IS ONLY SOUND BECAUSE OF A NEW ASCENT AXIS**:
`stopFlags` ends the walk at the innermost VALUE-space binding, so an inner `const` refuses
instead of being filtered past — the two spaces differ, and a TYPE consult never needed it.
**THE STAMP WIDENING IS AGAIN FREE** (NodeKind 22..27 are contiguous and are exactly the six
kinds `bindLexicalScopes` declares into a fresh scope). **A SECOND, PER-FILE GATE** keeps
(INC.16) — the value gate is not near-empty (~5,555 nested `function` names in tsc's
sources) and reading `scopesOfOwningFile` BUILDS the tables — **and `LexDefer.census` then
measured that the property is unobservable on a FULL build** (`forcedBy={checkSpine=2}`: the
spine forces every checked file anyway), which is why its ablation arm is **UNDISCRIMINATED
and recorded as such**. Six arms, union 7 of 35 pins; the flag-mask arm also has no unique
pin, because that mask still carries `Class` and `Enum`. **TWO MORE OF THE ITEM'S OWN CLAIMS
WERE WRONG** — `const` in VALUE position resolves only in the UNIQUE variant (the shadowing
one answers the OUTER declaration, now (CHK.118)), and its `typeof` claim was an artefact of
the v1 narrowing probe. Grid 8×`added=0 removed=0`, cost_gate exit 0, huge_methods exit 0
(844 classes), warning-clean.

**(P18.62) — (INV.0) STEP 10a: THE B83.5 *TYPE* SPACE, AND THE ARC IS FUNNEL-SHAPED RATHER THAN RADIUS-SHAPED, 18,529 / 0 / 3 (2026-09-10).**
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
the shape it was written for. **THE BEFORE/AFTER RECEIPT IS THE SAME 129-CELL MATRIX
RE-RUN AGAINST THE LANDED BINARY** (reference arms reused verbatim, snapshot sha256 asserted at
both ends): **9 cells FIXED, 9 IMPROVED, 0 REGRESSED — −9 ours-only rows, −18 missing rows,
every row of it inside the TYPE half**, both bound controls byte-identical and the VALUE half
numerically untouched. The three nesting sites move IDENTICALLY, which is the signature of a
fix at the resolution site rather than a syntactic special case; shadow resolution went 7/42 →
16/42 agreeing with the references. **The one TYPE cell family it did NOT move is a QUALIFIED
reference** (`ZzzE.ZInner`) — `resolveQualifiedName` is a fourth type-name path, now written
into 10c. **ALL EIGHT ABLATION ARMS RUN, union 9 of 28 pins, and three predictions wrong**: the GATE and the FLAG-MASK arms redden IDENTICAL sets (round 927's PAIR — either alone disables the whole consult), round 748's ORDER arm is UNDISCRIMINATED because this step gave `getTypeFromTypeReference` its own hoist, and the `keyof (X & T)` arm read 0 RED until a pin was ADDED for it — without which that guard would have read as redundant and been deletable. Grid 8×`added=0 removed=0`, cost_gate exit 0 (`globals.lookups`
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

**(P18.57) — (INV.0) STEP 6a: MEMBER RESOLUTION IS `MemberResolver.kt`, AND THE ITEM'S OPEN QUESTION IS ANSWERED, 18,493 / 0 / 3 (2026-09-09).**



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

`Checker.kt` **196,797 → 196,172**; `MemberResolver.kt` 814; ledger row 8. **Second extraction of
the session.** The queue item asked whether the seam is the table builders *without*
`getTypeOfSymbol` — **it is**, and the census said so before any code moved: this family reads it
at ONE site, so a seam that took it would have had to take the whole checker, which is exactly why
§ 6 puts it in Stage 3. **A much better-shaped seam than the relater, which makes row 7 the arc's
OUTLIER rather than its trend**: one contiguous span against five, 657 lines against 1,256, **21
ambient reads against 45**, **1 write against 5** — and the columns are DISJOINT here, the single
write being (INC.23)'s truncation flag, a pure OUT channel. **There is NO cheap absorption, the
opposite of row 7 — ZERO of 22 members lack another caller in `Checker.kt`** (row 7 had seven), so
this row shrinks only by extracting its NEIGHBOURS. **THE RECEIPT IS NOW TRANSITIVE ACROSS THREE
BINARIES**: the same 488 deterministic `--passTiming` lines are byte-identical for pre-step-5
pristine, step 5 and step 6a — one receipt over **1,882 moved lines**, at no extra build, because
the step-5 capture taken earlier in the session IS this round's pristine arm. **A sharper form of
the JVM-mangling trap, hit twice in one run: widening a member to `internal` AS PART OF THE SPLIT
mangles a site a PREVIOUS round's receipt was reading** — `getTypeOfExpression` went private →
internal here and the unmangled grep reads 574 rows before and **0** after, which looks exactly
like a site that stopped being compiled. The split improved inlining for the fourth row running
(`resolveStructuredTypeMembers`, 244 call sites: **189 `too large` refusals → ZERO** at the hop);
ab-interleaved +38 ms (+0.15%) B-wins-3/6 NOISE-DOMINATED; `new MemberResolver` at exactly one
bytecode. **4 pins, 3 arms, one DEAD BY CONSTRUCTION and recorded as such** — deleting the
`mrProbeDepth--` changes nothing because that counter only moves under `--passTiming`, which no
test enables; the arm that forces the B202.1 cycle break never to refuse reddens its pin with the
mechanism verbatim in the message (`TS2589 … at (0,0)`, and the real circular-base row gone).
cost_gate exit 0, huge_methods exit 0 (837 classes), warning-clean.
**(P18.47) — THE REST-TUPLE MODEL IS FIXED ((CHK.111)), THE ITEM'S NAMED SEAM WAS *WRONG* RATHER THAN INCOMPLETE, AND THE REGRESSION IT CAUSED WAS IN ANOTHER MODULE, 18,271 → 18,302 / 0 / 3 (2026-09-08).**



**(P18.54) — (INV.0) STEP 4b-i: THE PER-FILE LOOKUP CORE JOINS `NameResolver.kt`, THE ITEM'S OWN HOIST IS UNSAFE, AND THE FILE THE ARC GROWS INTO WAS UNREVIEWABLE BY DIFF, 18,484 / 0 / 3 (2026-09-09).**
`Checker.kt` **199,405 → 198,781**; `NameResolver.kt` 669 → 1,418; ledger row 5. 20 functions and
11 fields moved VERBATIM — the per-file scope tables and their build passes, the INV.3(b)(ii)
visibility sets and their deferral, the probe funnel, the four consults, the (CHK.49) lib-value
recovery, the (BIND.1) owning-file probes and the four first-hit program scans. Ambient row:
**seven reads, no writes**, two of them intended BIDIRECTIONAL pairs. **The full 4b censused at
1,363 lines so it was SPLIT**; 4b-ii (namespace / qualified-name / heritage, ~780 lines) is what
remains of step 4. **THE ITEM'S OWN INSTRUCTION IS UNSAFE**: hoisting `libGlobals`'s declaration
above the construction site — which it asks for — would reorder `parseBuiltinLib()`, whose side
effect fills a field deliberately declared before it for the Kotlin init-order gotcha; that field
and one other became ambient reads instead. **THE SPLIT IMPROVED THE COMPILER'S HOTTEST LOOKUP**:
`lookupPerFileForNode` (~2M calls/self-compile) was `4 inline (hot) + 57 too large` as a monolith
and its 9-byte hop is now `57 inline + 41 inline (hot)` with ZERO refusals — row 1's finding on a
far bigger population — with a receipt trap attached, since Kotlin mangles an `internal` member's
JVM name and a grep for the source name reads zero rows. **AND THE FILE THIS ARC GROWS INTO WAS
RENDERING AS A BINARY BLOB**: `UNRESOLVED_MODULE_SPEC`'s literal NUL sat at byte 7,910, inside
git's 8,000-byte detection window, so 4a and 4b-i have NO line diff for it; fixed in `2db1c14ca`
with the compiled class BYTE-IDENTICAL as the receipt. Receipts: **all 420 per-pass `--passTiming`
rows and the 46 diagnostics byte-identical against PRE-4a pristine** (one receipt covering both
rows), all 17 named gate classes green, cost_gate exit 0, huge_methods exit 0 with
`Checker.<init>` 5,701 → 5,656, ab-interleaved −0.45% B-wins-3/6 NOISE-DOMINATED, warning-clean.

**(P18.53) — (INV.0) STEP 4a: THE NAME/MODULE-RESOLUTION LEAF BECOMES `NameResolver.kt`, AND TWO OF THE FOUR § 10 INSTRUMENTS NEED A SAME-BINARY CONTROL, 18,477 → 18,484 / 0 / 3 (2026-09-09).**
The owner chose **(INV.0)** — the WORK ORDER's tail — so the (CHK.\*) lane is parked and the
shrinkage metric moves the right way for the first time in ~26 rounds: `Checker.kt`
**199,963 → 199,405**, `NameResolver.kt` 669, ledger row 4. Fifteen functions and three fields
moved VERBATIM — the alias ladder, the specifier ladder, two scope probes and the checker-local
symbol-target link store — as a final class built once per `Checker`, every surviving call site a
one-line delegation. **The verbatim claim is proved twice by two methods** (a reverse-transform
`diff` and an independent multiset check) rather than asserted. Ambient row: **fourteen checker
reads, no writes**; the four functions whose only readers moved with them got no hop and were made
`private`, so the collaborator's public surface (11) equals the delegation count by construction.
**THE REUSABLE FINDING IS METHODOLOGICAL.** The counter receipt should be the standard for a split
and is stronger than the gate: **all 420 per-pass `--passTiming` rows and the 46 diagnostics are
byte-identical against a rebuilt pristine HEAD**. The only section that moves is the **node-kind
histogram**, and the SAME BINARY run twice moves it MORE (70 differing lines A-vs-A against 64
A-vs-B) — the documented crawl-worker race, arriving in a channel nobody had diffed. And
**`getTypeOfExpression`'s PrintInlining row is NOT stable across processes**: arm A read
`1 inline (hot) + 372 too large`, arm B `382 too large`, and arm A's SECOND run reproduced arm B
exactly — so ledger rows 1 and 3's "row-for-row identical across arms" was recorded without this
control. Every delegation hop reads `inline`/`inline (hot)` with ZERO refusals; ab-interleaved
−0.57% B-wins-2/6 NOISE-DOMINATED; `NameResolver` is never an allocated type. Three deviations
from the item, each backed by a grep, including one FORCED by the warning-clean rule. 7 pins;
3 arms, all discriminating uniquely, with the remaining four pins recorded as positive controls
rather than claimed as coverage. Next: **step 4b**, the scope side — censused this session at
~1,270 lines, and several of 4a's ambient reads disappear once it lands.

**(P18.52) — STATIC BLOCKS ESCAPE, PARAMETER DEFAULTS AND DECORATORS ARE REACHED ((CHK.115)), AND THE DECORATOR FAMILY IS *TWO OPPOSITE MECHANISMS*, 18,437 → 18,477 / 0 / 3 (2026-09-08).**
43 fixtures, and **pristine 6.0.3 and tsgo 7.0.2 agreed on every one**. **(a) removes an ours-only
FALSE POSITIVE**: a static block's assignments now escape into the enclosing flow — tsc's binder says
so literally (`isImmediatelyInvoked = <IIFE> || node.kind === ClassStaticBlockDeclaration`) — **but a
static PROPERTY INITIALIZER, which also runs at class-evaluation time, does NOT escape**, because tsc
gives an initialized `PropertyDeclaration` its own control-flow container. The item did not state that
boundary. The escape had to be the full `markAssignments` LATTICE, not a scan: a conditional and a
`try` must not escape while a `while (true) { … break }` must. **(b) is NINE rows, not one** — all five
parameter-default spellings plus an object-literal method, a binding-pattern element default, an arrow
nested in a default and a class-expression static block in a default. **(c) is two opposite mechanisms
wearing one syntax**: a MEMBER decorator answers to the LEAK, a CLASS decorator to the LIVE set,
because a `ClassDeclaration` is not a control-flow container in tsc — one rule is wrong for one of
them, and both directions are pinned. **A new ours-only row was manufactured and caught only by the
final full reference sweep**: under STANDARD decorators a parameter decorator is TS1206 and both
references stop there, so an ungated walk added a TS2454 beside it — no profile carries the shape, so
neither the grid nor the corpus could see it. 40 pins; 13 arms — **a6 is the mask/closure pair**
(edits `SpineDispatch.kt` only, so `Checker.class` is unchanged and `spine_closure_audit.py` FAILS
under it), **a10 and a14 were each DEAD ALONE** behind an early return and an `any` gate, so a10b and
the a11+a14 pair are what discriminate, and **11 pins are never RED by construction** — they are
positive controls asserting today's conservatism, reddenable only by an arm that makes the change more
aggressive, which a3/a12/a13 are. Three residues queued as (CHK.116), including that static blocks are
flow-ORDERED and one leak set per class cannot express it. Grid **8 × added=0 removed=0** re-run
independently; corpus 10,344/0, `spine_closure_audit.py` exit 0, `cost_gate.py` exit 0,
`huge_methods.py` exit 0, build warning-clean.

**(P18.51) — THE NULLABLE-TARGET RULE REACHES ALL FIVE HEADS ((CHK.114)), (c)'s STATED AXIS WAS WRONG, AND THE REFERENCES COULD NOT ADJUDICATE THE PIN THAT BROKE, 18,413 → 18,437 / 0 / 3 (2026-09-08).**
All three stages landed **plus a PREREQUISITE the item did not name**: tsc RESTORES the aliased target
before reporting, and three already-wired heads were silently stripping aliases — so wiring (a) alone
would have REGRESSED `function q(): OptAlias` from correct to wrong. The guard is deliberately NOT
applied at the argument and object-literal heads, which render the target from the TYPE, where keeping
the alias buys one wrong string for another (measured). **(c)'s stated axis is wrong**: it calls the
collapse CROSS-FLAVOUR, but both references KEEP the member spelling for `STwo = NTwo.A`,
`NTwo = STwo.A` and even the cross-flavour-AND-cross-arity `SOne = NTwo.A` — **every collapsing row has
a ONE-MEMBER source enum**, i.e. it is (CHK.92)(d)'s own fact on the SOURCE side, not a new rule. The
item's "one wiring each" is true of neither (a) (8 rows, and it needs stage 0 first) nor (b) (two
changes — once the target shows `| undefined` the written source literal must survive). **The failing
pin could not be adjudicated by its own fixture, and that is the reusable lesson**:
`ThisMethodCallAssignmentNarrowTest`'s subject is that a call does NOT narrow, and it broke on the
TARGET's rendering while the row still fired at the same code and span — a full-text pin in an
unrelated family silently depends on every display rule, and only the full suite sees it. Asked about
the target, both references answered a THIRD thing (they drill to the offending MEMBER, because the
source is a FRESH object literal); a sibling fixture with a NON-FRESH source forces them onto the
whole-object form, where all three compilers are byte-identical at `'ZzzRes'` — confirming the strip
and showing the pin had captured our own pre-existing divergence. The sweep found 5 files carrying a
nullish target in a full-text expectation and **all are correct for the right reason** (16 shapes
through both references), and the green-for-the-wrong-reason population is **provably empty** — a pin
asserting a stripped target at a newly-wired head would have been RED before the change. 24 new pins;
7 arms, **a7 REDUNDANT BY MEASUREMENT** over ~120 rows and its pin RENAMED so it no longer claims
ordering coverage. Refused with measurements: a type-side alias test (unsound — id-keyed, first-wins,
(INC.27)) and the (c) collapse inside a union source (needs `typeToString`'s union rendering, which
(P18.48) forbids). Grid **8 × added=0 removed=0** re-run independently; corpus 10,344/0, externals
290/0, `-project` 866/0, `cost_gate.py` exit 0, `huge_methods.py` exit 0, build warning-clean.

**(P18.50) — THE CLASS-MEMBER DEFINITE-ASSIGNMENT PATH EXISTS ((CHK.112)(a)), AND THE MISSING PLUMBING WAS WRONG IN *BOTH* DIRECTIONS, 18,375 → 18,413 / 0 / 3 (2026-09-08).**
**11 missing rows AND 6 ours-only FALSE POSITIVES from the same gap** — a bare identifier, an
expression- and a block-bodied arrow, a function expression, an IIFE, an object literal, a computed
member name and a `static { }` block, in a class DECLARATION and a class EXPRESSION alike, were all
silent; and a method / property initializer / static block / arrow property / accessor /
class-expression property that ASSIGNS the variable did not suppress a sibling closure's read.
(CHK.110)(a) found its defect the same way — **a shape that fails both ways is the cheapest
attribution there is.** **It was TWO mechanisms**: the reach classifier never gave a
`PropertyDeclaration` under a class DECLARATION a status (so (CHK.110)(b)'s handler saw an empty leak
there), and nothing anywhere walked a property initializer that is a plain expression. **The item's
own headline fixture is silent for a SECOND reason and that would have read as an inert fix**:
`SpineDaFrame.enableLeak` is `false` at file level by design, so a file-level `let` never leaks into
ANY nested function, class or not, and both references do not share the conservatism — every fixture
here is function-scoped, now a CLAUDE.md entry. **The FOURTH countdown pin in five rounds, and the
purest yet**: `Inv4SpineBatch25Test` paired `a class-expression property initializer arrow is reached`
with `negative control - a class-DECLARATION property initializer arrow is unreached`, under a header
calling the difference a "reach quirk" — both references report BOTH spellings, so the pair recorded
our own asymmetry and the "negative control" WAS the defect. Inverted with transcripts; the section
header and the class KDoc's quirk list were fixed too, **three places, because a comment naming a
quirk outlives the pin**. The sweep for siblings was run against the ORACLE, not by reading: of 49
TS2454 class-ish `@Test` blocks, the 4 reachable ones were run through both references (3 green for
the right reason, 1 unreachable), plus 2 fixtures BUILT to test the at-risk static-initializer
suppression at function scope — both references TS2448 without TS2454, and we match. 38 pins + 1
inverted; 11 arms all discriminating, with a3/a5 a round-927 pair and a6/a7 the mask/closure pair
(a7 leaves `Checker.class` UNCHANGED and `spine_closure_audit.py` fails under it — a second
independent instrument). Refused on measurement: the frame's live set for the member walk (arm a8 is
the receipt) and a `ClassDeclaration` arm on `collectClosureAssignedNames`. Three residues queued as
(CHK.115). Grid **8 × added=0 removed=0** re-run independently; `spine_closure_audit.py` exit 0,
`cost_gate.py` exit 0, `huge_methods.py` exit 0, build warning-clean.

**(P18.49) — A `number` STOPS BEING SILENTLY ACCEPTED BY A STRING ENUM ((CHK.113)(a)), THE SOURCE LITERAL SURVIVES TO A NULLISH-TARGET DISPLAY ((b)), AND THE ROUND BEFORE IT LEFT THREE COUNTDOWN PINS, 18,338 → 18,375 / 0 / 3 (2026-09-08).**
**(a) is a FALSE NEGATIVE in the most basic position and the item under-counted it** — a `number`
source was silently ACCEPTED against a string enum target; the item named the declaration, and
measured it is **all five positions plus a union target, 8 silent rows**, TS2322/TS2345 in both
references for every one. The fix demands POSITIVE evidence of string-ness through `enumMemberEntries`
(the tsc view), so an opaque ambient member, an unfoldable value, an empty enum and every mixed or
numeric enum keep today's acceptance — and **arm a2 demonstrates CLAUDE.md's own trap #2 directly**:
inverting it to "positive numeric evidence" reddens exactly the two opaque-member controls.
**(b)'s second shape did not exist** — the item says `gN(true)` prints `'boolean'` for `'true'`, but
BOTH references print `'boolean'` there too, so that row was never a divergence — and **the display
predicate alone is completely INERT**, because `getTypeOfExpression` answers the BASE primitive for a
literal NODE: the source is widened at ACQUISITION, so nothing downstream has a literal left to keep
(CLAUDE.md's round-781 entry earned its place again; the recovery re-reads the literal from its AST
node at three display heads). **The inherited risk was re-scoped rather than accepted**:
`ts2322KeepsSourceLiteral`'s two recorded FP incidents are about the ACQUISITION gate, which feeds the
relation VERDICT, not the display predicate — widening acquisition was considered and REFUSED with
that distinction stated. **Three stale pins were inverted, and the IMMEDIATELY PRECEDING round created
them**: (CHK.92) recorded its own residue as pins asserting `'number'`/`'boolean'`, which both
references contradict. That is the third countdown pin in four rounds, and CLAUDE.md's entry is
reinforced with the sharper form — *a round that records its own residue as a pin hands the next round
a failing suite*, and a red pin asserting a known-wrong value is indistinguishable from a regression
until someone re-derives it against pristine. 37 pins + 3 inverted; 6 arms — a1 10 RED, a2 2 uniquely,
a3 1, b1 12, and **b2/b3 REDUNDANT BY MEASUREMENT** (round 813's whole-output diff over a 43-row
family, byte-identical on all three binaries), kept and recorded rather than claimed as coverage.
Three pre-existing residues confirmed on the BEFORE binary are queued as (CHK.114). Grid
**8 × added=0 removed=0** re-run independently; `cost_gate.py` exit 0, `huge_methods.py` exit 0,
corpus 8,837/0, externals 290/0, `-project` 866/0, build warning-clean.

**(P18.48) — THE ENUM AND NULLABLE-TARGET DISPLAY RESIDUES CLOSE ((CHK.92), ALL FOUR PARTS), AND A DISPLAY RULE PUT IN THE GENERAL RENDERER BROKE THE LANGUAGE SERVICE, 18,302 → 18,338 / 0 / 3 (2026-09-08).**
**All four parts LANDED**: (a) neither side of an object-literal member mismatch is widened any more
(`'6'` → `'5'`, not `'number'` → `'number'`), with tsc's per-FLAVOUR literal keep; (b) the four
MEANING rows — an enum-target object-literal member at an ARGUMENT — now report byte-exactly; (c) one
home, `nullableTargetDisplay`, implements tsc's `DefinitelyNonNullable`-gated strip AND the
optional-declaration add, correcting BOTH directions at five call sites; (d) a one-member enum's
relation-error display collapses to the parent at four positions. **The round's lesson is
architectural and cost two round-trips: a display rule specific to RELATION ERRORS must not live in
`typeToString`.** (d) was first put in the general renderer with a TS2367 bypass bolted on, and the
full suite found it had broken `Project.quickInfoAt` — a hover on a one-member enum's member went
from `Valued.Gamma` to `Valued`, destroying the distinction (API.15)'s deliberate negative control
exists to make. **The oracle was asked rather than argued**: `tsgo --lsp -stdio` answers
`(enum member) Valued.Gamma = 5` for all four one-member shapes, i.e. tsgo does NOT collapse in hover
where both references DO collapse in a relation error — one renderer cannot serve both. The rule now
lives in `relationErrorTargetDisplay` (5 relation-error heads), the bypass is DELETED, and **needing
a second per-consumer bypass is recorded as the signal a rule is misplaced**; arm a12 is now the
PLACEMENT arm, graded on the `-project` module at 3 RED. **A stale pin was INVERTED with proof** —
`ConstAssertionTest`'s r24 asserted the known-wrong `'string'` and both references print `'"b"'`
(second such pin in three rounds; a pin whose name says "residue" is a countdown). **Four of the
item's claims were wrong**: (a) names two emitters and there are three, (d)'s exception is literal
FRESHNESS in full, (c)'s "three sites" is five, and the binding constraint for (a) was a corpus PIN
WALKER matching on exact message TEXT — which **also confounded the round's own instrument**, since a
message-text marker reddens the BEFORE arm wherever such a walker exists. REFUSED with its
measurement: (d)'s TS2367 freshness half (this checker mints no fresh enum-member type, so the four
shapes share one `Type`). Two MEANING residues queued as (CHK.113). 35 pins + 1 consumer-side pin;
13 arms, all discriminating. Grid **8 × added=0 removed=0** re-run independently; `cost_gate.py`
exit 0, `huge_methods.py` exit 0, build warning-clean.

**(CHK.111) CLOSED, and the item under-counted its own defect by 9x** — it recorded "exactly 1 false
positive" at the class property; measured, a DECLARED tuple source produced a false TS2741/TS2322 at
**all five** positions (the shielding claim holds only for an ARRAY-LITERAL source), and the gain is
**10 rows, not 5**. **The named seam is WRONG, not merely incomplete**: making rest slots OPTIONAL
fixes only the `[number]` half — `[number, string]` failed because the rest MEMBER was typed
`string[]` (the whole rest array) rather than `string` (its element) — and optionality is the wrong
CHANNEL, since it also injects `| undefined` into every element read and into `tupleArrayBase`'s
union. What works is the member carrying the rest's ELEMENT type plus a SEPARATE non-required mark,
which is also why dropping the numbered member entirely (literal tsc) is refused: it would lose
`[number, number]`'s position-1 element row. **The DISPLAY half landed in full** —
`[number, ...string[]]`, `readonly [...]`, leading and middle rests all render as both references,
with an empty `[]` unchanged as the control that the ellipsis is keyed on `tupleRestIndex`.
**The regression this round caused was in ANOTHER MODULE and only the full suite could see it**:
`typeToString` feeds the externals generator's `xtsc: unmapped <type>` markers, so the ellipsis moved
three RxJS gate expectations while the grid and all ~13k corpus baselines stayed clean. Those pins
encoded the OLD, LESS ACCURATE text and were updated **with proof, not weakened** — rxjs declares
`sources: [...ObservableInputTuple<A>]`, a tuple whose single slot IS a rest, so `[any]` spelled a
FIXED one-element tuple. **Two of the three stale expectations were reported and the third was
hidden**, because a block of `assert(<local>)` calls throws at the FIRST false one — both facts are
now CLAUDE.md entries, and (P18.44)'s rest-tuple entry, which this round makes stale, was REWRITTEN
rather than left standing. 31 pins; 14 arms — a3/a4 a round-927 pair separated only by adding a
presence-only pin, **a14 first blind** (every literal index is served by the numbered member, so
only a non-literal `t[i]` reaches the index signature), **a11 REDUNDANT by whole-output diff** over
six fixtures. The implementation agent self-reported starting a second concurrent Gradle build,
detected by an impossible class sha; both affected arms were re-run alone in the foreground and no
result comes from an overlapped run. Grid **8 × added=0 removed=0** re-run independently;
`cost_gate.py` exit 0 (largest delta **+0.03%**), `huge_methods.py` exit 0, build warning-clean.

**(P18.46) — DEFINITE ASSIGNMENT JOINS A `try`/`catch` AND REACHES AN EXPRESSION-BODIED ARROW ((CHK.110)(a)/(b)), AND THE SUPPRESSOR WAS NEITHER CANDIDATE THE ITEM NAMED, 18,234 → 18,271 / 0 / 3 (2026-09-08).**
**(CHK.110)(a)/(b) CLOSED; (c) and two newly-measured gaps moved to (CHK.112).** The item named
`markAssignments` (which really has no `TryStatement` arm) and B223 (which only sees a `var`
declared INSIDE the try) as the two candidates for (a)'s silence; the suppressor is a THIRD
mechanism — **`checkUsesOfUninitialized`'s own `TryStatement` arm**, which walked the try block
against the CALLER's live frame set, so the try's assignments escaped the try statement
unconditionally. **The evidence needed no instrumentation, because the same line was wrong in the
OPPOSITE direction**: `try {} catch {} finally { d = "c" }` was an ours-only FALSE POSITIVE both
references are silent about, since that arm walks the try block ONLY. One escape, two opposite
defects; a second pre-existing ours-only row closes with it. The merge (`tryDefinitelyAssigns`) is
decided by REACHABILITY through round 450's `daWalkStmt` — neither block reaches the continuation
→ remove, only the catch → the catch's assignments, only the try → the try's, both → the
intersection, `finally` always counts. **The first design was refuted by the fixture matrix while
the GRID stayed clean**: a `tcvHasTerminator` conservatism suppressed 6 rows both references
report, and the 8-profile grid read `added=0 removed=0` on that binary AND on the guard-free one —
**the grid cannot grade conservatism**, only the reference matrix can, now a CLAUDE.md entry. (b)
is a dispatch gap whose two arms (`spineDaEnterNode` and its `SpineDispatch.enterClosure` entry)
are a round-927 PAIR reading the same 7 RED — no pin can separate "not called" from "not written".
**The item's (b) scope was wrong in one place**: a class property initializer is silent for a
BLOCK-bodied arrow, a function expression and a bare identifier alike, so that leak path is absent
entirely and is a different mechanism → (CHK.112). 37 pins, all read from pristine; 10 arms, all
discriminating. Grid **8 × added=0 removed=0** re-run independently; `cost_gate.py` exit 0 (largest
delta **+0.03%**), `huge_methods.py` exit 0, **`spine_closure_audit.py` exit 0**, build
warning-clean under `--rerun-tasks`.

**(P18.45) — AN INLINE LITERAL CALLEE GETS ITS OWN TYPE ((CHK.109)), AND THE CALLEE *EXPRESSION* IS EVIDENCE THE CALLEE *TYPE* CANNOT CARRY, 18,212 → 18,234 / 0 / 3 (2026-09-08).**
**(CHK.109) CLOSED, 1 → 15 of the reference's 16 rows**, byte-identical to pristine on every one
(`({})()`, `({ a: 1 })()`, `[1]()`, string / number / template / boolean / regex, nested
parentheses, `?.()`, explicit type arguments at exactly ONE row, and an object literal carrying a
method); an inline arrow, function expression, async arrow and every literal RECEIVER stay silent.
tsgo 7.0.2 and pristine 6.0.3 agree on all 16, so no oracle conflict arose. **The item's named
seam was right and exactly HALF the fix, and it fails on the item's own first example**:
`getCalleeType`'s `else -> anyType` is the source of the `any`, but after fixing it `({})()` and
`(/x/)()` are still refused by `calleeObjectTableIsComplete` — (CHK.45)'s rule wants positive
evidence a member table is complete, and **an empty anonymous object is exactly what a TYPE cannot
vouch for**, since `{}` from a literal and `{}` from an unfinished resolution are the same type. The
closing rule is SYNTACTIC, and arm a3's RED set is precisely the three empty-`{}` pins and nothing
else — for a non-empty literal the type-only rule already suffices. **A parser fact cost a third
leg**: `true`/`false` are reserved words the Parser renders as an `Identifier`, so `(true)()`
reaches the Identifier arm and resolves to nothing (the leg sits on the miss path, so no ordinary
callee pays for it). **Population 3 → 15**; `(class {})()` (TS2348, needs a
`typeof (Anonymous class)` naming mechanism) and `new ({})()` (TS2351, every `new`-path emitter is
`Identifier`-gated) are a DIFFERENT diagnostic, verified inert under the change and recorded as
stated refusals. **Four display divergences are made visible and are not this item's** — `[]()`
prints `any[]` for `never[]`, `[() => 1]()` loses a parenthesization, an objlit getter prints
`readonly g: any`, a computed key prints `{ ["k"]: number; }` — **all four reproduce on the PARENT
binary at a declaration position**, all FORM, all refused rather than folded in ( fixing `[]` alone
would change the empty-array-literal type program-wide). 22 pins; 4 arms ALL discriminating with
four distinct class shas, none blind or redundant; every other `getCalleeType` caller audited and
shown unable to emit for a literal. Grid **8 × added=0 removed=0** re-run independently after both
agent arms were verified byte-identical to orchestrator-built binaries; `cost_gate.py` exit 0
(largest delta **+0.03%**), `huge_methods.py` exit 0 (834 classes, 0 over), build warning-clean.

**(P18.44) — A TUPLE'S *ARITY* BECOMES EXPRESSIBLE ((CHK.108)), THE ITEM'S SEAM WAS NECESSARY AND NOT SUFFICIENT, AND THE `WORK ORDER` NOTE CLAUDE.md POINTS AT HAD BEEN ARCHIVED OUT OF THE PLAN, 18,188 → 18,212 / 0 / 3 (2026-09-08).**
**(CHK.108) CLOSED, 11 of the reference's 12 rows**, byte-identical to pristine at all five
positions (var-decl, argument TS2345, return, assignment, class property), for a readonly target,
inside a union target, and at the INNER span of a nested literal. **The item's named seam — a
contextual-tuple form of `getTypeOfArrayLiteral` — closed 0 of 12 rows on its own**: the
contextual type was never INSTALLED for a plain array literal at any of the five positions, and
`checkArrayLiteralElementsAgainstTuple` (B407) unconditionally `return true`s. **The half the item
never mentions is the ELABORATION, and it is the bigger one** — with a genuine tuple source the
arity mismatch already reached every emitter and printed the wrong thing, so a DECLARED tuple
source with no array literal anywhere printed TS2741 / TS2739 / `Types of property 'length' are
incompatible` where both references print TS2322 with an arity sub-line; `tupleArityChain`
transcribes tsc's three rungs, which carry three DIFFERENT counts, each verified row-for-row
against pristine. **The one refused row is a measured TRADE whose cause is the rest MODEL, not
this item** — dropping the rest-tuple exclusion gains 5 correct rows and introduces 1 false
positive (`class K { p: [number, ...string[]] = [1] }` → TS2741), reproduced on the PARENT binary
because our rest tuples carry the rest slot as a REQUIRED numbered member; queued as **(CHK.111)**,
which also closes the `[number, string[]]` display. **A stale hand-written pin was asserting an
answer no reference prints** (TS2739 for `[] → [number, string]`) and the corpus structurally could
not say so — its `tupleTypes.ts` baseline is served by a pin walker that WIPES and re-pins it, so
baseline and pin can disagree indefinitely with both green. 19 pins + 1 corrected; 12 arms, of
which **a5 is REDUNDANT by whole-output diff (45 rows, 7 fixtures, byte-identical)** and **a3 first
read 0 RED because the PIN SET was blind — no array literal can reach rung 3 at all**. Grid
**8 × added=0 removed=0**, re-run INDEPENDENTLY by the orchestrator after verifying both agent arms
byte-identical to binaries it built itself; `cost_gate.py` exit 0 (largest delta **+0.03%**),
`huge_methods.py` exit 0, build warning-clean. **PROCESS:** the `WORK ORDER` note CLAUDE.md tells
every agent to read was added by `cc09770a3` and archived out with the four COMPLETED items beneath
it, leaving a pointer to a heading that had not existed for ~25 rounds — restored, with an addendum
recording that the order's tail is (INV.0) while the arc has been (CHK.\*).

**(P18.43) — THE FLOW-JOIN SUBTYPE REDUCTION IS MEMOIZED, AND A 3.2× *WALL* REGRESSION EVERY COUNTER GATE WAS BLIND TO IS CLOSED ((PERF.1)), 18,185 → 18,188 / 0 / 3 (2026-09-07).**
**Warm A/B −57.7% and −58.9%, replicated in two batches** (16,981 → 7,176 ms, 16,584 → 6,817 ms,
`files/errors` 78/46 on every arm); cold CLI 35,893 → 26,740 ms (−25.5%). **The degradation the
bench series has carried since 2026-09-05 is ONE COMMIT, not refactoring drift**: `warm/tsc` is
0.36-0.44× for the rows before `9a49e44c2060` ((CHK.85)(b)) and 1.13-1.56× for all 25 rows since,
with tsc's own time ranging 7.29-13.81 s across the after-rows — and the **native AOT arm regressed
4.8× too**, so it is not a JIT or AOT-cache artifact. **Every deterministic counter is FLAT**
(`spine.nodes` bit-identical, `narrow.walks` +1.2%, and nothing above +4.5% even 25 rounds later),
because the cost is per-ARRIVAL and `cost_gate.py` counts LAUNCHES — the round-735 tail law, with
the (CHK.85)(b) note's own "414 reporting walks" as the count that hid ~90% of narrowing time.
**The mechanism was not the one the diff suggests**: `--narrowSections` reads `narrowByAssignmentRhs`
(the new enum arm's home, and this round's original target) **FLAT at 209.7 → 221.0 ms**, while
`getUnionType at a branch label` goes 368.7 → **20,384.7 ms** on +1.7% calls and `relations(depth0)`
1,235 → **14,512 ms**. (CHK.66)'s subtype reduction runs only when a member is FOREIGN to the
declaration — "free on almost every join" — and (CHK.85)(b)'s enum arm makes a branch answer a
MEMBER (`K.A`) where the declared type is the atomic enum `K`, so a whole class of joins fell onto
the quadratic path. Ablations attribute it exactly: forcing the free path is **−9.9 s with all 46
diagnostics unchanged**, skipping the enum sort is −0.8 s. **Fixed as a MEMO, not a predicate
change, because reading `K.A` as declared would disable the reduction a join genuinely needs**
(`K.A | K` must reduce to `K`); keyed `packIdPair(joined.id, declaredType.id)`, which is exact —
`getUnionType` interns by member-id list and `isTypeAssignableTo` is already id-cached. Post-fix
`relations(depth0)` is 1,226 ms, fully back to the pre-regression 1,235. **Fix (2), bounding the
reporting walk, is REFUSED on this round's own measurement** (the ≥1 ms tail is 306/2,520 ms against
the pre-regression 210/1,360 — the walk was never expensive, only the reduction it triggered was).
3 pins; the poisoned-memo ablation reddens **exactly P2**, the served ask, and one arm is recorded
**BLIND** rather than redundant (`anyForeign`'s early exit returns above the cache probe). Grid
**8 × added=0 removed=0**, `cost_gate.py` exit 0 (all counters within ±0.03%, no rebaseline),
`huge_methods.py` exit 0, build warning-clean.

**(P18.42) — AN INTERSECTION DEDUPES ITS CONSTITUENTS BY TYPE ID ((CHK.106)(b)), AND (a) IS BROADER THAN THE ITEM RECORDED, 18,179 → 18,185 / 0 / 3 (2026-09-07).**
**(CHK.106) CLOSED — one part fixed, three verified against both references.** (b) had MOVED since
the item was written: (CHK.101) closed its `| undefined` half and what remained was `BP & BP` vs
`BP`, an idempotent intersection — `getIntersectionType` flattened, dropped `unknown` and reduced
primitives but never DEDUPED, where tsc's `addTypeToIntersection` keys its set by type ID. **The
dedupe needed an exemption, and the exemption is an INTERNING DIVERGENCE rather than a rule**: an
unrestricted id-dedupe also collapses `{ p: number } & { p: number }`, which both references print
in full, because two separate type-literal NODES are two types in tsc and ONE interned type here on
the project path. That leaves `T1 & T1` (an alias to an anonymous body) unfixed, recorded rather
than bought — the only rule separating it reads `aliasDisplayMap`, which is FIRST-WINS during the
walk and would make the dedupe a function of resolution ORDER (round 776). **(a) is BROADER than
recorded and stays refused**: it names the loss through a CARRIER member, and a DIRECT `Fn<number>`
annotation loses the name too while a direct `Obj<number>` keeps it — still (INC.27)/(INC.29)'s
interning-key question. (c) verified closed by (CHK.100); (d) verified a reference divergence
(pristine `(2 | 1)[]`, tsgo and ours `(1 | 2)[]`). 6 pins; 3 arms of which **one is recorded BLIND
rather than redundant** — the unrestricted-dedupe arm reads 0 RED because `diagnose()` gives the two
anonymous literals distinct ids, so the guard's evidence is the project path and a pin that could
see it belongs in `-project`. Grid **8 × added=0 removed=0**, `cost_gate.py` exit 0,
`huge_methods.py` exit 0, build warning-clean.

**(P18.41) — A CONDITIONAL OF ARRAY LITERALS UNDER AN ARRAY PATTERN TYPES EACH BRANCH AT ITS OWN FLOW POSITION ((CHK.107)), AND THE GRID THE ITEM CALLED THE GATE IS A CONTROL, 18,172 → 18,179 / 0 / 3 (2026-09-07).**
**(CHK.107) CLOSED, 1 → 6 of the reference's 6 rows.** `const [s, e] = typeof por === "number" ?
[por, undefined] : [por.pos, por.end]` read both leaves as `any`. tsc pushes the pattern's implied
contextual type into BOTH branches, so the source is `[number, undefined] | [number, number]` and
`bindingElementType`'s union arm gives `number` / `number | undefined`; the reconstruction is a
UNION of the branch tuples with **each element read AT ITS OWN FLOW POSITION** — the one thing
(CHK.96) stage 2 could not do, since `getTypeOfExpression` never flow-narrows and an un-narrowed
branch tuple reads slot 0 as `number | { pos: number; end: number; }`. The narrowing is a PARAMETER
of `arrayLiteralAsDestructuringTuple`, so the plain array-literal path is untouched. **The item's
gate claim is measured wrong and saying so is the point**: it predicts `removed=1` at
`services.ts:3264` and the grid is `added=0 removed=0` on all eight — the refusal made both leaves
`any`, which is SILENT, and that site's leaves feed a `RefactorContext` whose members are exactly
`number` and `number | undefined`, so neither arm has a wrong-typed USE to report. The grade is the
fixture, whose shape is `getRefactorContext` verbatim. 7 pins plus the stage-2 REFUSAL pin inverted;
3 arms all discriminating, one of them REDESIGNED after reading the same red set as another
(refusing every conditional is refusing the mechanism). `cost_gate.py` exit 0, `huge_methods.py`
exit 0, build warning-clean.

**(P18.40) — TS2454 FOR AN `if` JOIN, AND THE TS2448 CO-EMIT'S RULE WAS THE *TYPE* AND NOT CONST-NESS ((CHK.105)), 18,157 → 18,172 / 0 / 3 (2026-09-07).**
**(CHK.105) CLOSED for (a) and the `if` join; (CHK.110) queued with all three residues attributed.**
B78.1 read the co-emit rule as CONST-NESS off `typeGuardNarrowsIndexedAccessOfKnownProperty10`,
whose const is `any`-typed — what suppresses tsc's TS2454 there is tsc's `assumeInitialized` on
`AnyOrUnknown | Void`, and with an ordinary type BOTH references report TS2448 **and** TS2454. **Two
hand-written pins in this repo were pinning that wrong answer and are repaired here.** The corpus
then found the other half of `assumeInitialized` this population reaches: a CLASS STATIC INITIALIZER
is a different control-flow container from the declaration (tsc's `isOuterVariable`), where both
references report TS2448 alone. For the join, `markAssignments` scanned both branches of an `if`
unconditionally; the lattice it needed was already written — round 450's `daWalkStmt`, built for
`while (true)` — and is now consulted per variable, CONSERVATIVE TO REMOVE, so only what the walk
can prove changes. **The grid found the one guard the naive form needs and it is a tsc BINDER rule**:
the flow is unreachable after a call to a never-returning function, so an unassigned CALL statement
bails (two ours-only rows on three profiles at `fixPropertyOverrideAccessor.ts:83` without it) — as
a `DaState` FLAG, because in round 450's caller a bail means "do NOT remove" and would ADD
diagnostics. **A third deliverable was BUILT AND REVERTED**: requiring a `default` before a switch
removal costs two ours-only rows on ALL EIGHT profiles at `checker.ts:38141`, an exhaustive
default-less switch tsc proves and we cannot. **Three of the item's claims are wrong** — its "3 lost
TS2345" rows are the (CHK.63)-adjacent ARGUMENT-reader gap for a BODY-LOCAL source (the TYPE is
already exact at the declaration position), its population is 4 rows not 7, and the join is lost in
`markAssignments`, not in the set pass it names. 13 pins + 2, 5 arms all discriminating, grid
**8 × added=0 removed=0**, `cost_gate.py` exit 0, `huge_methods.py` exit 0, build warning-clean.

**(P18.39) — CALLING A LITERAL-TYPED OR OBJECT-TYPED VALUE IS TS2349 ((CHK.104)), AND THE OBJECT ARM NEEDED TWO GUARDS THE ITEM DID NOT NAME, 18,136 → 18,157 / 0 / 3 (2026-09-07).**
**(CHK.104) CLOSED, 7 → 19 of the reference's 22 rows; (CHK.109) queued.** The primitive arm read
`calleeType is Type.Intrinsic`, i.e. exactly the WIDENED half of the population: `let s = "a"`
reported and `const s = "a"` did not, nor did a number/bigint literal, an annotated literal const or
parameter, a template literal, an `as const`, an enum MEMBER, a body-local or a `never`; the object
arm fired only for a syntactic `new X()`, so a class instance, an interface-typed value, an array
and an object literal's type were all silent. A literal's callability is decided by the same wrapper
its base primitive's is (a wider gate, no new decision); the object half is (CHK.45)'s rule —
positive evidence the member table is complete. **The item under-counted its own population (15
lost rows, not 12) and named NEITHER guard the object arm needs**: a DUPLICATE IDENTIFIER makes the
callee's TYPE not the whole story (the binder's `canMerge` refuses Variable+Function, so this reader
got the VARIABLE's type while the call was checked against the FUNCTION's signature —
`errorElaboration`), and `tryEmitUncallableTypeArgs` OWNS the same row for an explicit-type-argument
call and runs AFTER the spine (`untypedFunctionCallsWithTypeParameters1` printed it twice; the
`--passTiming` emissions-by-pass census named both emitters in one run and the dedupe went into the
pass that runs SECOND). **A pre-existing FORM divergence closed on the way past**: `getApparentType`
covers String/Number/Boolean and not `bigint`/`symbol`, so `sym()` printed `Type 'symbol'` for both
references' `Type 'Symbol'`. Two open decisions were settled by measurement: the heritage refusal is
keyed on `extends` alone (an `implements` clause adds nothing to an instance type), and `never` is
admitted because both references report it and the grid is what licenses it. 21 pins, all read from
pristine; 7 arms, ALL discriminating, plus one arm recorded as NOT ablated with its reason. Grid
**8 × added=0 removed=0** on the final binary, `cost_gate.py` exit 0 (largest delta **+0.03%**),
`huge_methods.py` exit 0, build warning-clean.

**(P18.38) — AN ARRAY-LIKE *ARGUMENT* IS DECIDABLE AGAINST AN ARRAY-LIKE *PARAMETER* ((CHK.103) STAGE 2), AND FIVE OF THE ITEM'S SIX ROWS CARRY NO SPREAD, 18,110 → 18,136 / 0 / 3 (2026-09-07).**
**(CHK.103) stage 2 CLOSED; (CHK.108) queued with its mechanism named.** The item called its
residue a spread question and named a whole-literal array-to-array fallback as the seam. Measured,
**five of its six rows carry no spread at all** — `takeStrArr(nums)` with `nums: number[]` against
`(x: string[])` is silent here and reported by both references, and so are `Bar[]` → `Foo[]`,
`C2[]` → `C1[]`, `number[][]` → `string[][]` and both tuple shapes — so the gap is the ARGUMENT
reader's FP firewall having no array-vs-array gate, and the item's seam covers one row of six.
The licence is the DECLARATION position (as (CHK.83)'s was), so the decidability question is asked
one level down, of the ELEMENT pair, by `canUseTypeEngine` ITSELF — the rule cannot drift from the
position that licenses it, and `any[]` is refused in both directions with no rule of its own. On
the item's fixture ours goes **6 → 12 of the reference's 12 rows, all twelve byte-identical to
pristine**. **The grid found exactly ONE ours-only row on all eight profiles and it was a NARROWING
gap**: `Debug.assertEachNode(elements, isArrayBindingElement)` narrows by an `asserts nodes is
readonly U[]` signature, and `narrowByAssertCall`'s type-parameter recovery understood only a BARE
`U` — **an array OF a type parameter RESOLVES**, so it is neither `errorType` nor `anyType` and the
recovery's own gate never opened for it (the first attempt put the fix inside that gate and was
inert). **Two traps, both found with a probe rather than by reading**: `ternaryOfArrayLiterals`
SUBSUMES an `init !is ArrayLiteralExpression` test, so relaxing the `!is` alone did nothing through
a whole build cycle; and an EMPTY array literal IS tuple-like (tsc's empty tuple) where
`elements.any { … }` says false, which the full suite caught and no other instrument could. 26 pins,
all read from pristine; 9 arms, 8 discriminating, a3/a4 a round-927 PAIR and a7 recorded
NON-DISCRIMINATED with its reason rather than claimed. Grid **8 × added=0 removed=0** on the final
binary (a1's +1 row is the round's own positive control that the harness is live), `cost_gate.py`
exit 0 (largest delta **+0.03%**, no rebaseline), `huge_methods.py` exit 0, build warning-clean.

**(P18.22) — A LOCAL INITIALIZED FROM AN ENUM MEMBER IS READ AT ITS FLOW TYPE AT EVERY READER, IN BOTH DIRECTIONS, 17,462 → 17,516 / 0 / 3 (2026-09-05).**
**(CHK.85)(b) LANDED and (CHK.85) is CLOSED** ((c) is the staged `as const` item (CHK.93), designed
by read-only recon over 32 measured rows). `let k = K.A; k = K.B; const w: K.B = k` was an ours-only
TS2322 and `if (k === K.B)` a lost TS2367: `narrowByAssignmentRhs` gains tsc's
`getAssignmentReducedType` over enum atoms (symbol lookup only), `isNarrowableTarget` admits an enum
target, the const symbol half keeps the member, the arith and ccet recorders record an
enum-initialized body local, and the TS2367 emitter reads the flow type through a new REPORTING walk
kind — the flow walk's stale-antecedent pass-through for an unclassified overwrite is sound for a
suppression consumer and a false positive for a reporting one (`classifier.ts`'s
`token = scanner.reScanTemplateToken()`). 49/49 recon rows and 60+ probe rows match both
references bar four form residues; a (CHK.91) pin was corrected (both references WIDEN `{ v: k }` for
a `const k = K.A`). 52 pins, 17 arms; core 16,027/0, corpus 8,837/0, `cost_gate.py` rebaselined
(`globals.lookups` +2.30% = 414 reporting walks, attributed), grid 8×`added=0 removed=0` after an
intermediate build's +3/+4 rows were closed by the reporting walk and a `let` widening.

**(P18.21) — AN OBJECT LITERAL'S ENUM MEMBER WIDENS THE WAY tsc WIDENS IT, FREE OF THE DISCRIMINATED-UNION LOSSES THAT REFUSED IT TWICE, 17,424 → 17,462 / 0 / 3 (2026-09-05).**
**(CHK.91) LANDED, closing (CHK.85)(a).** The (P18.18) arm was rebuilt ALONE to NAME its 22 grid
sites (tsbuildPublic returns and a `Map.set`, conditional returns in six services files, two
union-annotated declarations, one assignment), then landed as three pieces: widen only a FRESH
enum-member ACCESS (tsc widens no identifier, `const` local, `as` or shorthand — the arm did, two
false positives it had not reached), tsc's `isConstContext`, and the SOME-rule keep over the push
context ?: a PULL walking every `getContextualType` root. The ARGUMENT root reads the callee's RAW
parameter types: `cpaComputeArgCtxTypes` measured +2.9% `typeOfExpr.calls` (overload selection
typing every argument) and handed back a circular keep for `id({ v: K.A })`. `const w: K.A = o.v`
reports `Type 'K'`, `o.v = K.B` and `o.kind === K.B` lose their ours-only rows, every
discriminated-union position is byte-identical to HEAD. 38 pins, six arms (`as const` recorded as
unobservable); core 15,973/0, corpus 8,837/0, `cost_gate.py` exit 0 (`typeOfExpr.calls` −0.16%),
grid 8×`added=0 removed=0`. Read-only recon rewrote (CHK.85)(b) (MEANING both ways: six false
positives after a reassignment, seven lost `k === K.B` / body-local rows; four seams named) and
(CHK.92) (the optional-parameter display is tsc's `isRelatedTo` nullable strip, wrong here in both
directions; the `gU(1)` claim was false), and found the primitive mis-assignment probe blind on
both sides for an enum-member local.

**(P18.20) — AN ENUM MEMBER STOPS RELATING TO A LITERAL IT DOES NOT EQUAL, A LITERAL ARGUMENT STOPS BEING INVISIBLE TO AN ENUM PARAMETER, AND THE (CHK.85) UNBLOCKER IS DESIGNED, 17,394 → 17,424 / 0 / 3 (2026-09-05).**
An orchestrated round: one implementation subagent owned Gradle; a read-only recon subagent
worked against a frozen snapshot of the HEAD binary. **(CHK.83) CLOSED.** `const l1: 5 = em` was
silent at every position because `isSimpleTypeRelatedTo`'s `EnumLiteral` leg accepted a member
against ANY number literal (`NumberLike ⊇ NumberLiteral`); it now relates by VALUE through the
tsc-value view, so an ambient opaque member relates to no literal. `fEnum(3)` at an argument, a
rest element, a return, an arrow body, the overload chain and object-literal members now reports
as both references do; the enum-union display puts `null`/`undefined` last. The overload-set
unification is STOPPED: `checkRecursiveFunctionTypes` pins four mechanisms, not one. **(CHK.91)
DESIGNED** — the (CHK.85)(a) unblocker: a FRESHNESS gate (tsc widens only an enum-member ACCESS
expression; the built arm widened `{ v: a }`, `{ v: k }` and shorthands too) plus a PULL-derived
contextual keep mirroring tsc's `getContextualType` roots, decided by `isLiteralOfContextualType`'s
SOME rule; the eleven losses are exactly the union-annotated declaration / nested / conditional
return / arrow body / assignment positions, where an emitter fires and the push field never
arrives. 30 pins, twelve arms all discriminating, one redundant guard retired by its own arm;
core 15,935/0, corpus 8,837/0, `cost_gate.py` exit 0, `huge_methods.py` exit 0, grid 8×`added=0
removed=0`. Residues queued as (CHK.92).

**(P18.19) — AN ENUM MEMBER KEEPS ITS ENUM AT A RETURN, THE TS2367 CATEGORY RULE TAKES ITS BASES, AND A STRING ENUM STOPS BEING A NUMBER, 17,369 → 17,394 / 0 / 3 predicted (2026-09-05).**
Two items landed whole and the third partly; every row reproduced against tsgo 7.0.2 AND
pristine 6.0.3 before any code was written, and **one reference DIVERGENCE was found and
decided a design**.
**(CHK.89)** — `function f(): K.A` read `type 'A'` against both references' `'K.A'`: the string
layer's base name is a `QualifiedName`'s LAST name, and it feeds both renderers. The qualifier is
an ENUM-MEMBER rule and nothing else (a namespaced INTERFACE prints `'I'` and a whole namespaced
enum `'Q'` in both references), and its one non-obvious guard came from the CORPUS —
`SymbolFlags.Enum` is `RegularEnum or ConstEnum` and the binder cascades `ConstEnum` onto a
`ConstEnumOnly` NAMESPACE, so a flag test rendered `Const.E` and silently disarmed the
rounds-745-749 same-string retry (`enumAssignmentCompat3`).
**(CHK.90)** — both halves: the category rule now takes tsc's `getBaseTypesIfUnrelated` base (the
widened pair is unrelated by CONSTRUCTION there, which is also why the (CHK.88) value rule beside
it must keep the ORIGINALS), and an ALL-STRING enum's OWN type answers the `"string"` category
where its MEMBER already did — the two halves of one enum disagreeing about their own flavour,
which is the silence at `s2 === 3` / `s2 === true`. The fixture is now byte-identical to both
references on all ten rows.
**(CHK.83)** — the `readonly` array PARAMETER landed (the ARRAY kind already admitted, missed
because `isArrayLikeType`'s `Type.Reference` leg names `"Array"` alone) and the ELABORATION
sub-line landed at all five union-source chain sites. **The PICKER is deliberately untouched: with
`"a" | 1` against `number[]`, tsgo names the FIRST constituent and pristine the LAST** — pristine is
the corpus's oracle, so only the rendering moved. INTERSECTION re-measured and still refused (its
single ours-only row reproduces at `harness/.../vpathUtil.ts:106:30`, and it additionally needs the
source-display allowlist); the generic `Type.Reference` family newly refused because its LICENCE
fails — `const d: ArrayLike<string> = s` and `Iterable<string>` are ours-only TS2322 at the
DECLARATION position.
25 pins, six arms (5/4/2/7/2/2 RED, one nested pair recorded), one provably-dead line removed with
its proof. Gates: core **840 / 15,905 / 0 / 3**, generated corpus **25 classes / 8,837 / 0**, the
seven other modules 0, `cost_gate.py` exit 0 with `output.errors` 46 and this round adding nothing,
`huge_methods.py --fail-over 0` exit 0, and the 8-profile grid **added=0 removed=0 everywhere** —
the real gate here, not a control.

**(P18.18) — AN ENUM STOPS OVERLAPPING A LITERAL IT CANNOT HOLD, AN IMPOSSIBLE COMPARISON'S BRANCH BECOMES `never`, AND A PRIMITIVE ARGUMENT STOPS BEING INVISIBLE TO A COMPOSITE PARAMETER, 17,343 → 17,369 / 0 / 3 predicted (2026-09-05).**
Three of four items landed; every row reproduced against tsgo 7.0.2 AND pristine 6.0.3 before
any code was written, and the two references agree on all of them.
**(CHK.88)** — `ka === 1` with `ka: K.A` is `TS2367 … 'K.A' and '1'` in both references and was
silent here; the verdict needs the member VALUES (round 745's `enumKnownDomainValues`, whose
refusal for a *computed* enum is what keeps `declare enum` and a non-foldable member accepting
every literal), and the literal must be read off the AST because `getTypeOfExpression` answers the
base primitive for a literal node. **(CHK.87)** — the sibling NARROW, decided by (CHK.86)'s own
predicate so the branch it collapses is exactly the branch that reports; it DELETES rows, and
`typeOfExpr.calls` reads −0.22%. **(CHK.83)** — FOUR of five parameter kinds (ARRAY/tuple,
FUNCTION, ENUM, UNION); the licence is the DECLARATION position, which admits the same family and
matches both references row for row. **THREE of its four guards were found by a GATE, not by
reading**: a REST parameter (301-401 added rows per profile — the grid), an ARITY-mismatched call
(`couldNotSelectGenericOverload` — the corpus), and an OVERLOAD-SET parameter already owned by the
dedicated `checkRecursiveFunctionTypes` walker (`recursiveFunctionTypes` — the corpus, attributed
by `--passTiming`'s emissions census). INTERSECTION is refused with its measurement ((CHK.55)'s
law: overload selection keeps a branded-`Path` signature, so opening the gate reported an
ours-only row).
**(CHK.85) STOPPED A SECOND TIME, now with the blast radius its entry asked for — (a) was BUILT
and costs MEANING: +7 rows on every profile and +22 on harness, ALL discriminated-union selection
losses.** The widening RULE is right (its contextual keep is tsc's `isLiteralOfContextualType`);
what is missing is the CONTEXT reaching `getTypeOfObjectLiteral`. Two corrections: (a) is a lost
true positive AND an ADDED false positive (`o.v = K.B` is an ours-only TS2322), and the widening
is missing for ENUMS ONLY because a literal node already types as its base primitive; (c) is not
an enum question at all — `as const` is unmodelled for strings and arrays too, i.e. a FEATURE.
26 pins, **twelve arms** (7 / 1 / 1 / 1 / 2 / 2 / 5 / 1 / 1 / 1 / 1 / 1 RED, one nested and
recorded as such). Gates: whole core module **839 classes / 15,880 / 0** (a superset of the
682-file fixture-selected sweep, all 681 test classes confirmed present — 682 `--tests` patterns
were measured to take >20 min and produce no XML); generated corpus **25 classes / 8,837 / 0**;
externals 290/0; project 848/0; kir 159/0; lsp 58/0; `cost_gate.py` exit 0 (`output.errors` 46
unchanged, largest move `mapped.hits` +1.22%); `huge_methods.py` exit 0; 8-profile grid all
`added=0 removed=0` — the REAL gate here, not a control, since all three items move diagnostics.
Queued: (CHK.89) a return-position enum-member annotation losing its qualifier (`'A'` for
`'K.A'`, pre-existing), (CHK.90) the TS2367 CATEGORY rule missing `getBaseTypesIfUnrelated`, and
(CHK.83)'s own remainder.

**(P18.17) — `never` STOPS BEING UNASSIGNABLE AT ONE POSITION, AN IMPOSSIBLE ENUM COMPARISON STARTS REPORTING, AND A GENERALIZED ENUM PRINTS ITS NAMESPACE, 17,308 → 17,337 / 1 / 3 then 17,343 / 0 / 3 predicted (2026-09-05).**
Three of (P18.16)'s four residues closed, each reproduced against tsgo 7.0.2 AND pristine 6.0.3
before any code was written (the two references agree on every row; no divergence found).
**(CHK.84)** — the string-layer `isAssignableTo` had no BOTTOM-type rule, and only the RETURN
position could show it, because of that function's five call sites only that one adds an
identifier fallback below `inferSimpleExprType` (and the engine cannot fire, since it correctly
ACCEPTS a `never` source and an accepted relation does not early-return). **(CHK.86)**, the
diagnostic half — `comparabilityCategory` maps every numeric enum to `"number"`, so two enums of
one flavour read as the same category; the identity question is now asked separately and above
it, with tsc's `getBaseTypesIfUnrelated` deciding whether the members or their enums are printed.
**(PARITY.3)** — tsc computes the source display TWICE and the difference is the defect: a
GENERALIZED source is re-rendered fully qualified, which is why the same `Ns.Inner.I` prints
qualified at a `string` target and bare at a `never` one; scoped to a NAMESPACE chain, with an
ambient-module guard keeping (P18.14)'s refused `import("<path>")` form out by name and a
global-augmentation guard STOPPING the walk at `declare global` (which is not a container a
consumer can spell) while keeping any real namespace nested inside it.
**(CHK.85) STOPPED with its measurement — it is MEANING, not display**: a mutable object-literal
property must widen (`const o = { v: K.A }; const w: K.A = o.v` errors in both references and is
SILENT here — a lost true positive), a `let`'s read must answer the FLOW type, and an `as const`
property read is missing entirely; the object-literal half lives in `getTypeOfObjectLiteral`, i.e.
every literal's member types program-wide, so it is a design. Requeued: (CHK.85), plus (CHK.87)
the `never` NARROW half of (CHK.86) (a separate mechanism — `narrowByEquality` decides from the
other operand's SYNTAX and bails before any enum question) and (CHK.88) the enum-member-vs-numeric-
literal sibling (needs member VALUES, not enum identity). 35 pins, eight arms
(2 / 3 / 6 / 10 / 2 / 1 / 4 / 1 RED, one nested and recorded as such), one (P18.16) expectation
updated to the references' answer. **The corpus half of the at-risk enumeration was sound and the
HAND-WRITTEN half was not**: it selected classes by NAME, which cannot see a fixture, so
`DeclareGlobalAugmentationTest` — an enum declared inside `declare global` — went RED in the full
suite; censused, 488 core classes mention `enum`/`never` in their SOURCE and the name patterns
reached 149, missing 339. Re-run (PARITY.2)-style by FIXTURE: **485 classes / 5,169 tests / 0**.
Corpus at-risk 360 families / 1,481 subtests / **0 moved**; externals 290/0; project 848/0;
`cost_gate.py` exit 0 (`output.errors` 46 unchanged); `huge_methods.py` exit 0; 8-profile grid all
`added=0 removed=0`.

**(P18.16) — THE ENUM DISPLAY GENERALIZATION LANDS, AND ITS BLINDED PINS BECOME tsc-VERIFIABLE, 17,286 → 17,308 / 0 / 3 predicted (2026-09-04).**
(PARITY.2) closed. tsc's `getBaseTypeOfEnumLikeType` is wired, so an enum-member source now
generalizes to its parent enum at every target that cannot hold a top-level singleton, at all
six emitters plus declaration and assignment — byte-identical to tsgo 7.0.2 AND pristine 6.0.3
on every shape but two pre-existing ones (a namespace prefix, and an interned member union's
alias name). **The round is the conversion, and the population had to be re-derived to find
it**: the queue's grep-derived list said 13 classes, a mechanical run of all 170 core classes
whose fixtures declare an enum says 14 / 45, and the extra one expects a type ALIAS name that
no grep for a member rendering can see. Every converted probe moved to a `never` target — the
one target tsc suppresses the generalization for — which makes 45 (REL.2) narrowing assertions
verifiable against both references for the first time; three pins REFUSED conversion with their
reasons (the round-441 `never`-parameter arm discards a non-enum narrow; a `let`'s flow type
diverges) and two `string`-target twins were re-expected to the generalized enum rather than
moved. Corpus predicted from an enumeration of all 3,145 active baselines (135 declare an enum,
2 name a member in an assignability source, both at suppressing targets): 715 at-risk subtests
run, **0 moved, no `LogicalParityDivergence`**. Five ablation arms (20 / 3 / 73 / 9 / 0 RED),
the last a measured-redundant guard kept with its reason. Four residues queued: (CHK.84)
`never` unassignable at a RETURN position and nowhere else, (CHK.85) a mutable object-literal
property and a `let` not widening an enum member the way tsc does, (CHK.86) no TS2367 for an
impossible enum-vs-enum equality, (PARITY.3) the lost namespace prefix.

**(P18.15) — THE LITERAL-UNION COLLAPSE AT EVERY POSITION, AND AN ENUM GENERALIZATION REFUSED, 17,259 → 17,286 / 0 / 3 (2026-09-04).**
(PARITY.1) closed. The item named three emitters; a trace censused SIX (argument, rest argument,
return and three object-literal paths, one with no keep-guard at all), all now through one
measured helper whose keep-predicate also recurses through a union target — every top-level
source display in that family now matches tsgo 7.0.2 AND pristine 6.0.3 byte for byte. 27 pins,
seven arms with disjoint red sets, and the outcome matched the prediction exactly: no baseline
moved, no `LogicalParityDivergence`, from an enumeration of all 3,145 active baselines rather
than a sample. **The enum-member residue was FORM, not the MEANING the previous round recorded —
and refusing it is the finding**: wired, it left every baseline and profile unchanged and BLINDED
47 assertions in 13 classes, because the (REL.2) enum-narrowing arc reads the narrowed type out
of the message at a primitive probe target; they go blind, not red, and no gate here sees that.
The remedy is measured (a `never` probe target, which tsc suppresses the generalization for, and
which makes those pins tsc-verifiable for the first time) and queued as (PARITY.2). Two
instrument repairs: the grid script's positive-control marker was hard-coded to the previous
round's symbol, and the grid's blindness to display changes was re-confirmed by counting (0 of
417 rows carries an assignability message). New meaning residues queued as (CHK.83).

**(P18.11) — PER-MODULE EXTERNALS GENERATION: 51 MODULES OF `@types/node` COMPILE TOGETHER, → 17,196 / 0 / 3 (2026-09-04).**

**(P18.13) — THE AUGMENTATION RESIDUES: A PACKAGE AUGMENTATION STOPS INVENTING TWO ERRORS, 17,224 → 17,236 / 0 / 3 (2026-09-04; filtered gates only — the full suite was not run this round, so the headline count stands at 17,224 / 0 / 3 plus 12 new pins).**
(CHK.82) three of four residues, each measured against tsgo 7.0.2 on a scratch project.
**(3) is the one a real project meets:** `declare module "some-pkg"` over an installed package
was a false **TS2664** *plus* a false **TS2339 on the package's own member** — ONE cause, since
no resolver leg can name a bare specifier's target (a `package.json` `types` entry, not a string
transformation), so `targetFile` was null and the merge took the FILELESS-AMBIENT branch,
publishing the block's PARTIAL interface into `globals` as a round-510 stub while never merging
into the real target. `augmentationTargetFile` is the one home for the ladder; its bare leg takes
the crawl's answer from any file and requires them to AGREE. **(1)** a name the BLOCK declares
and the target does not export typed `any` — now resolved, narrowed to exactly that case, which
is what keeps (CHK.76)'s +43 rows away. **(2)** `import { Brand } from "./types.js"` beside an
augmentation declaring `Brand` was a false TS2305 while the TYPE always resolved — a pure
absence-check defect, fixed by reading the binder's own block `exports` through the SAME
export-modifier predicate the merge uses (extracted, one home, two consumers). A B86.4 display
defect fell out beside them: the namespace-qualification ascent treated a STRING-named module's
specifier as a name segment, so `@types/node` rendered `events.DefaultEventMap`,
`zlib.CompressCallback` and even `node:test.test.SuiteFn` — with a colon in it.
**(4) REFUSED with the measurement:** the enum display `import("…").E` is NOT an augmentation
residue (it reproduces with no `declare module` in the program, under tsgo AND pristine 6.0.3),
103 corpus baselines use that form and we satisfy them through hard-coded pins rather than a
mechanism — a logical-parity conversation. **(CHK.81)'s two remaining sub-items MEASURED and
REFUSED, both mis-stated in the queue:** the missing diagnostic is TS2503 (not TS2304) and the
axis is `declare` (not `declare module`), suppressed deliberately by **B367**; and the
literal-union display is neither an alias nor an ambient question — the rule is *collapse to the
base primitive exactly when the target holds no literal of that base*, systematic across TS2322
and TS2345, gated only by the full corpus.
12 pins, eight single-mistake ablation arms each with its own red set; filtered
`*Augment*`/`*Module*`/`*Import*`/`*Export*` 1,468/0/0; cost_gate exit 0 (errors 46 unchanged);
huge_methods 0 over; the 8-profile grid all eight `added=0 removed=0`; externals 290/0; and the
`@types/node` per-module receipt with its generated Kotlin CODE **byte-identical** and its marker
text strictly better.

(EXT.21b): a generation is scoped to one `declare module` block plus its re-export closure
(`export * from`, `export = <require alias>`); a global declaration renders in every generation
unless the module shadows it; a reference to another module's declaration is spelled into that
module's Kotlin package under the same identity evidence, with first-wins naming keyed per
module. 51 generations, 51 packages, compiled TOGETHER at 0 metadata and 0 Kotlin/JS errors:
`Socket` in both `node.dgram` and `node.net`, `Module` in both `node.module` and `node.vm`,
`declared again by another file` 57 → 2 (the residue is global-vs-global and a `ts5.6/`
duplicate). rxjs, `typescript.d.ts` and the flattened control are byte-identical. 13 pins, eight
ablation arms with distinct red sets; externals 275/0. Cross-module HERITAGE is refused loudly
and queued as (EXT.24): admitting it measured 184 `hides member of supertype` + 27 `inherits
conflicting members`, because `Inheritance` is built over one generation.

**(P18.12) — CROSS-MODULE HERITAGE: THE 179 REFUSED SUPERTYPES OF `@types/node` BECOME SUPERTYPES, 17,196 → 17,224 / 0 / 3 (2026-09-04).**
**(CHK.78) landed beside it:** three augmentation divergences, the first far broader than the
item stated — `resolveModuleSpecifier` is not directory-aware, so on a REAL project every
relative SIDE-EFFECT import read a false TS2882 (the corpus is blind: flat names; tsc's own
sources have none); the crawl's own answer now suppresses it. A bare name inside an augmentation
block typing `any` was a LIB-collision axis (the INV.3(c)(iv) leg sat below the per-file consult),
which also fixed a precedence divergence against tsgo; the lens no longer answers the block's
partial interface. One guard measured redundant and removed; 13 pins, `@types/node` byte-identical,
grid unchanged; four residues queued as (CHK.82).
(EXT.24): a per-module SET is generated in ONE call (`generateKotlinExternalsPerModule`) in two
passes — pass 1 collects each module's frozen tree and lifts it into that module's Kotlin package,
pass 2 re-runs each generation with the others' lifted models in hand — with the `open`
attribution computed once over the whole lifted set and restated per generation, because a member
a subclass in another package overrides must be `open` and the owning generation cannot see that
for itself. On `@types/node` 20.19.43: heritage refusals **179 → 0**, cross-package references
283 → 468, `Socket extends stream.Duplex` renders, and the 51-module set still compiles TOGETHER
at **0 metadata and 0 Kotlin/JS errors** (the 184 `hides member of supertype` + 27 `inherits
conflicting members` (EXT.21b) measured are gone). Exactly one new, honest heritage marker
(`https.Server` would need two class bases). rxjs and `typescript.d.ts` byte-identical — a
generation produced ALONE keeps the (EXT.21b) refusal by construction. 9 pins + 6 gate cases,
eight single-mistake arms; externals 275 → 290 / 0. The trap it cost an hour to find: a lifted
package is NOT an ordinary scope, and modelling it as one makes `node:console`'s
`node.node.console` shadow the head `node` and silently empty the whole cross-module attribution.

**(P18.10) — THE CI HALF OF THE KOTLIN/JS GATE, THE README REPOSITIONING, AND THE EXTERNALS PACKAGE SCHEME MEASURED RATHER THAN PROPOSED, 17,150 → 17,169 / 0 / 3 (2026-09-03).**
Three owner decisions answered. **(EXT.17)**: the Kotlin/JS stdlib klib is now DECLARED by the
build (`dependencyScope` + `resolvable`, artifact-only `@klib` notation so no Kotlin/JS platform
attributes are needed and no wrong variant can be handed over) and passed to `jvmTest` as the
environment variable the gate reads — nothing enters a published artifact. Its ablation found a
second defect: with the path pointed at nothing the gate read **28 tests / 0 failures having
compiled nothing**, so `JsStdlib.locate` now splits an UNSET locator (a fact about the box —
skip) from a SET-but-missing one (a fact about the build — fail); ablated 28/0 green → **27 of
28 RED**. **(DOC.2)**: the approved README commit cherry-picked onto main unchanged, then a
second commit refreshed only what measurement changed (the ladder table at 0 Kotlin errors, the
`@types/node` flattening named as the open limit, 15,528 → 17,169, and the stale "the language
service is not incremental" bullet replaced by (LSP.3)'s numbers, said in tsgo's favour).
**(EXT.21a)**: the package scheme was MEASURED against both Kotlin compilers and **the queued
proposal was refuted** — a backtick rescues a hyphenated segment, a hard keyword and a
digit-first one and **nothing else** (`.`, `~`, `@`, `:`, `/` are illegal inside one), so `/`
`:` `.` are separators, npm's leading `@` is dropped, and any other character is refused loudly
with no `package` line rather than escaped into a file no compiler accepts;
`ModuleWiring.packageRoot` gives the kotlin-wrappers `node.fs` shape without hard-coding an
ecosystem. Every accepted package name is also QUALIFIED-referencable — the measurement that
makes per-module generation ((EXT.21b), queued) possible at all. Externals 242 → 261/0.

**(P18.9) — THE RxJS CORE RUNG COMPILES, THEN ITS CENSUS HALVES, THEN A PARSER DEFECT, THEN ALL 250 rxjs FILES AND typescript.d.ts COMPILE, 16,867 → 17,182 / 0 / 3 (2026-09-02).** (EXT.11a):
`rxjs@7.8.2`'s 15 `internal/` declaration files generate with zero checker diagnostics and
the generated Kotlin metadata-compiles (`KotlinExternalsRxjsGateTest`, verbatim,
Apache-2.0). Three compile errors, two mechanisms: interface CALL SIGNATURES (rendered as a
nameless method) are now function-type aliases — `public typealias UnaryFunction<T, R> =
(T) -> R`, and an empty interface over one is an alias to it
(`OperatorFunction<T, R> = UnaryFunction<Observable<T>, Observable<R>>`, transitively),
nameable but never a supertype; `typeof Action` (rendered as the un-instantiated instance
type, CHK.73) now refuses with a marker naming the written query, plus an ARITY GUARD in
the type mapper. One silent defect fixed: a function type's `this:` parameter rendered
positionally — now a Kotlin RECEIVER (`SchedulerAction<T>.(T) -> Unit`). New instrument:
`ExternalsLibraryProbe` (env-gated; generated Kotlin, compile errors, diagnostics, a marker
census per mechanism). 10 exact pins, each red by stash-ablation; externals 94/0. Census
after: 97 markers / 0 errors — nullable unions, `any`, arrays and literals are the next
rung; a `val plain: Plain` class-value defect queued as (CHK.73b). **(EXT.11b) LANDED next,
16,881 → 16,889 / 0 / 3:** nullable unions → `X?` (syntactic and resolved, one rule, composing
inside function types), `any`/`unknown` → `Any?` unmarked (keyed on the intrinsic NAME —
`Record<…>` resolves to the bare `anyType` here, so a resolved `any` the source did not spell
stays marked), arrays → `Array<T>` on lib evidence (a program's own `Array` refused: the
checker resolves `Array<X>` by NAME), rest parameters → `vararg`, literals widen; RxJS census
97 → 62 markers, externals 102/0. **(PARSE.1) LANDED, 16,889 → 16,904 / 0 / 3:** the whole-library probe
(all 250 rxjs files) found `export { from } from './x'` reporting TS1005/TS1141/TS1434 — the
export-specifier loop read `from` as the clause keyword where the import loop already asked
tsc's list-element predicate; one line, 15 pins, emitted JS byte-identical to tsgo on 13
shapes, cost_gate +0.00%; no pristine baseline covers a `from` specifier. The same probe's
37 Kotlin compile errors (overload equivalence, value-vs-type name collisions, a narrowed
`var` override) were then closed by **(EXT.11c), 16,904 → 16,920 / 0 / 3: all 250 files compile (37 → 0)** —
Kotlin's overload-equivalence relation MEASURED against the metadata compiler over ~100 pairs
and pinned (a free own type parameter erases to `Any?`, a pinned one keeps identity up to
renaming; the override relation is a DIFFERENT key — positional identity, exact nullability —
so `KotlinSignatureKeys.kt` ships two), value-vs-type name collisions a loud skip, a narrowed
`var` override rendered as the inherited type with a marker, inheritance read through the
supertype's type arguments (a renamed TP silently lost every `override` before); a 21-file
extras gate; externals 118/0. **(CHK.73b), 16,920 → 16,924 / 0 / 3:** a class-, enum- or
namespace-valued export (`export const plain = Plain`, rendered as the INSTANCE type before) is
a loud skip through `heritageBaseSymbol` (`resolveName` cannot follow an import alias — measured);
externals 122/0. **(EXT.12), 16,924 → 16,928 / 0 / 3:** an overload equivalence class keeps its
LEAST-MARKED member (ties first, every member in its declared slot, the marker naming the
survivor); rxjs 250-file collapses 49 → 49 with six cleaner survivors (`<T> of(value: T)`);
externals 126/0. **(EXT.13), 16,928 → 16,947 / 0 / 3 — THE LADDER IS GREEN AT EVERY RUNG:**
`typescript.d.ts` (one `declare namespace ts`, `export = ts`, 11,448 lines) generates 9,792
lines of Kotlin compiling at 0 errors — the root ambient namespace flattens to the surface,
nested namespaces are `external object`s, references by shortest spelling, inheritance by
qualified path; 5,422 declarations, 1,659 markers; it exposed chained-`var`/diamond/`val`-
narrowing override mechanisms (closed) and checker name-resolution defects inside namespace
bodies (worked around syntactically, queued (CHK.76)); externals 145/0. **(CHK.75), 16,947 → 16,995 / 0 / 3:** the ambient-initializer rule is now
tsc's `checkAmbientInitializer` at both emitters (a `readonly` property or unannotated const
with a literal/enum initializer is legal; a non-literal one is TS1254 even on a property) —
73-row matrix byte-identical to tsgo, 48 pins, cost_gate +0.00%, `typescript.d.ts` now 0
diagnostics. **(CHK.76), 16,995 → 17,017 / 0 / 3:** names inside a `declare namespace` body now
resolve as tsc resolves them at every non-walk resolver (a nested namespace was invisible to the
ambient stack, so a sibling class typed `any` and a shadowed `Node` resolved to the root's);
`lookupInEnclosingNamespaces` at seven sites, 22 pins (8 red by ablation), 8-profile grid
unchanged, cost_gate within tolerance; two corpus regressions found by the suite and closed (a
namespace class value on the static-access emitter, a pin walker double-emitting). **(EXT.14), 17,017 → 17,021 / 0 / 3:** the generator's
per-file syntactic resolver is retired now the lens answers; a program-wide fallback survives
for qualified names and `declare module` bodies, `typescript.d.ts` byte-identical, four checker
residues queued as (CHK.77); externals 149/0. **(EXT.15), 17,021 → 17,031 / 0 / 3:** index
signatures as an `operator fun get`/`set` pair (measured against the metadata compiler) and
parameter properties as explicit members; `typescript.d.ts`'s 7 signatures render; externals
159/0. **(EXT.16), 17,031 → 17,051 / 0 / 3 — THE LADDER ITEM IS CHECKED OFF:** module
wiring (`ModuleWiring(name, entry)`; the public surface through the re-export graph;
`@file:JsModule`/`@JsNonModule`/`@JsName`, the `export =` object bound without a rename);
rxjs's 291 re-export markers → 0 with 101 honest "not exported by the package entry" markers;
externals 179/0. Residue: a Kotlin/JS compile gate for the real output ((EXT.17),
build-file route owner-gated), `@JsName` renaming for Kotlin-refused collisions ((EXT.18)), the
four checker namespace residues ((CHK.77)). **(EXT.17), 17,051 → 17,073 / 0 / 3:** the REAL
output compiled as Kotlin/JS for the first time (a local gate driving `K2JSCompiler` with a
fetched stdlib klib; the CI wiring is a build-file change, BLOCKED-PENDING-USER): Kotlin/JS
prohibits receiver function types in externals AND the receiver form was semantically wrong —
now receiver-less with a marker; a class implementing an interface's function-typed property with
a method (or fewer overloads) owes loud `override`s; externals 201/0 with the klib. **(CHK.77), 17,073 → 17,085 / 0 / 3:** the four
namespace-resolution residues closed at the resolver (a fileless `declare module` body, a
type-only namespace as a dotted heritage head + ambient inheritance, a qualified
`typeReferenceSymbol`, cross-file namespace MERGING through the `globals` root) — each matching
tsgo row for row, 12 pins, 8-profile grid unchanged, cost_gate exit 0; `@types/node` now resolves
what was `any` and exposes an externals spelling rule ((EXT.19)) and two augmentation
divergences ((CHK.78)). **(EXT.19), 17,085 → 17,091 / 0 / 3:** `@types/node` (66 files)
metadata-compiles at 0 errors (86 → 0; the queued mechanism was wrong — an arity cascade from
an un-filled generic default, not a spelling): defaulted type arguments filled, name ownership
under first-wins refused loudly, inherited texts respelled per scope, the generic-vs-plain
override lift, namespace imports inside ambient modules resolved; heritage skips 137 → 113;
externals 207/0; (CHK.79) queued. **(EXT.20), 17,091 → 17,103 / 0 / 3:** an `export =`
target never vanishes and declaration merging renders per tsgo's measured surface (class +
interface + namespace → one class with companion and nested types; `@types/node`'s
`EventEmitter`/`Stream`/`Module`/`Stats` are classes now, 0 metadata errors, 0 vanished
targets); externals 219/0; the one-generation-per-module design is (EXT.21). **(EXT.18), 17,103 → 17,113 / 0 / 3:**
collisions Kotlin refuses rename under wiring (`AjaxErrorValue`/`FooFn` bound by `@JsName`,
measured against Kotlin/JS): rxjs 9 → 0 skips, `@types/node` 48 → 0; the probe now JS-compiles
the real output and found 23 pre-existing superclass-call errors ((EXT.22)); externals 229/0. **(EXT.22), 17,113 → 17,117 / 0 / 3:**
the queued mechanism refuted by 70 measured rows — an external class may never spell a
superclass call; a class over a NESTED base with a required parameter renders a SECONDARY
constructor instead; `@types/node`'s real output is at 0 Kotlin/JS errors; externals 233/0;
the `= definedExternally` optionality rung is (EXT.23). **(EXT.23), 17,117 → 17,126 / 0 / 3:**
optional parameters render `= definedExternally` (90 measured rows; an override inherits the
default, a nested class with two direct declarers of one default is refused and handled);
rxjs/`@types/node`/`typescript.d.ts` at 0 errors in both compilers; externals 242/0.
**(CHK.79), 17,126 → 17,135 / 0 / 3:** a dotted heritage base whose head is a namespace import
inside an ambient block resolves through the target module's surface (`export =`/`export *`
chains, fileless carriers only); 9 pins, grid unchanged; the generator's syntactic route for
it retired (40 `@types/node` bases were carried by it); (EXT.21) BLOCKED-PENDING-USER on the package-naming scheme. **(CHK.80), 17,135 → 17,150 / 0 / 3:**
annotations through a block's namespace-import alias, TS2339 at a missing namespace member in
a heritage clause (tsgo's exact `typeof import("node:net")` wording), named-import and
`declare global` heritage heads, and the script-local carrier merge that let an unrelated
file's `import * as net` hijack `require("net")` — all matching tsgo; 19 pins, grid unchanged;
`@types/node` heritage refusals 95 → 82. **(CHK.81) PARTLY, → 17,182 / 0 / 3 combined with (P18.10)'s work:** a
`require` alias of an ambient block whose surface is `export = <value>` names that value, not
the carrier (five `@types/node` `extends EventEmitter` bases), with TS2694's carrier display,
the interface/annotation member reports, the false TS2833 gone and TS2305/TS2616 for a named
import absent from the surface; the implementing agent was rate-limited before any gate and the
verification found a lost diagnostic on real `@types/node` that only the probe's rendered types
could see, fixed in 11 lines; 13 pins all discriminating; three sub-items still open.


**(P18.8) — STAGE 2 OF THE INVERSION LANDS: THE POST-HOC TYPE ORACLE; THEN THE EXTERNALS ALIAS-REFERENCE RUNG, 16,838 → 16,867 / 0 / 3 (2026-09-02).**
**(INV.2)** (owner-approved this session): `TypeOracle` over the (INV.1) store + retained
graph + live checker — `typeAt` / `symbolAt` / `resolvedCallAt` / `contextualTypeAt` /
`typeOfSymbolAt` recorded during the walk, the bin-A rows forwarded at rest, `resolveName` /
`symbolsInScope` refused naming Stage 3, per-build handles, `close()` on edit; entries
`typeOracleOf(files)` and `ProjectCompiler.build(…, oracleHolder)`; the store grew
`symbols` / `calls` / `contextual`; per-row divergences in `docs/type-oracle.md`; 23 pins;
cost_gate +0.00 %. **Flag ON measured: compiler profile +21.5 % (1.90 µs per recorded
expression), many-small-2400-dom +6-7 % (0.95 µs)** — after the first arm read +57-64 % and
a per-channel attribution + JFR found the object-literal KEY leg re-typing its literal per
key (`getTypeOfExpression` has no per-node memo; O(keys²) on tsc's message tables), fixed by
reading the store. (INV.2b) queued: `Project` integration with the invalidation decided.
Design record: `docs/INVERSION-DESIGN.md` § 9b. **(EXT.10)**: references to a generated
alias render by NAME where the resolved body has no Kotlin spelling (`Handler<string>` →
`Handler<String>`; function-typed aliases now emitted and named) under identity evidence
through the new lens member `typeReferenceSymbol`; Dukat pin kept; 7 pins, externals 80/0.
**(INV.1b)** answered: a reconstruction-only arm (`nodeAnswers:reconstruction`) reads the
plain check (5,290 / 5,266 vs 5,270 ms) while types-only reads 6,158 / 6,121 — the whole
1.45 µs per expression is `getTypeOfExpression` re-typing what the walk already typed.

**(P18.4) — SESSION CLOSE: THE PHASE 18 FIRST ARC IS LANDED END-TO-END, 16,764 / 0 / 3
(2026-09-01).** In one session under the re-pointing directive: the directive persisted;
licence strings aligned ((LIC.1), with (LIC.2) POM drift flagged BLOCKED-PENDING-USER); the
tsgo comparison made honest ((DOC.1)) and then MEASURED against the right tsgo ((LSP.3));
README repositioned on `docs/reposition` ((DOC.2), awaiting owner review); the 142-method
census written ((INV.D): A=94 / B=15 / C=33, (INV.1) proposal BLOCKED-PENDING-USER); the
externals generator through THREE rungs ((EXT.1-3): interfaces, generics, references,
typealiases, functions, function types — 29 pins, zero-classpath metadata compile gate);
the LSP server feature-complete for a first release ((LSP.1-2): 58 pins, nativeImage
wired); (API.18) honestly refused twice with the mechanism recorded. `cost_gate.py`: every
counter unchanged all session — the INC-closure directive holding by construction. Next
top items: (EXT.4…n) ladder, (INV.0) split (Stage 0 of the inversion), (API.18)'s
sibling-bound descent.


**(P18.7) — TWO OWNER DECISIONS LAND: THE POM LICENCE AND STAGE 1 OF THE INVERSION, 16,828 → 16,838 / 0 / 3 (2026-09-02).**
(LIC.2) the root POM's `licenses` block now declares `AGPL-3.0-only WITH
LicenseRef-xtsc-output-exception` plus a second entry for the Output Exception (was
Apache-2.0; verified on the generated core JVM POM). **(INV.1) the per-file node-answer
store** (`NodeAnswerStore`, `Type` slots by `nodeId`, filled at the capture/sink hook under
the reconstructed ambient, first-wins, refusal before resolution), OFF by default behind a
`Checker` parameter / `--nodeAnswers`; 10 pins incl. the round-911 positive control (body
local `number` recorded vs `string` post-hoc) and the production-mode computation count at
0; cost_gate +0.00%, huge_methods clean, warm A/B flag-off NOISE-DOMINATED (3 rotated
pairs, sd < 1%); **flag ON measured: +14.9 % warm on the compiler profile (1.34 µs per
recorded expression, 598,455 of them) and +10.3 % on many-small-2400-dom (1.49 µs,
232,106)** — per-node, attributed next by (INV.1b). (INV.2) Stage 2 queued
BLOCKED-PENDING-USER. Design record: `docs/INVERSION-DESIGN.md` § 9a.

**(P18.6) — SESSION CLOSE, FIVE LANDINGS, 16,803 → 16,828 / 0 / 3 (2026-09-02).** (EXT.7) the **smol-toml rung is GREEN**: the
externals generator goes MULTI-FILE (`generateKotlinExternals(List<SourceFileEntry>)`, one
Binder + one Checker, cross-file by-name rendering, cross-file type-name collisions a loud
skip), top-level overloads render (implementation signature omitted, duplicates collapsed),
`#private` omitted, heritage markers name the base, export wiring loud (`export {}` silent);
`KotlinExternalsSmolTomlGateTest` embeds the verbatim seven `smol-toml@1.7.1` files and
metadata-compiles the output with zero checker diagnostics (externals 64/0; full suite
16,815/0/3). (TEST.1) the "order-sensitive" `ProjectTrustedFilesystemTest` control was a
DATA RACE in the test's own `CountingVfs` under the crawl's 16 concurrent readers (old
wrapper: 12,880 of 16,000 threaded reads counted); atomics + a CAS-swapped per-path map,
`CountingVfsConcurrencyTest` reddens the old wrapper (full suite 16,816/0/3). (INV.0) step 3:
`TypeInstantiator` extracted (the instantiation seam, ~290 lines verbatim, `Checker.kt`
191,030 → 190,771; ledger row 3 with the first NON-none ambient surface; suite 16,819/0/3 byte-identical, cost_gate +0.00%, ab −0.81% NOISE-DOMINATED, JFR alloc unchanged, the 10 B hop `inline (hot)`). (EXT.8) heritage to GENERATED targets (supertypes, `override`/`open`,
inherited constructors, `open external class`, cross-file bases via the new lens member
`heritageBaseSymbol`; externals 70/0; full suite 16,825/0/3). (EXT.9) exported values (`val`/`var`, literal consts widened) and
accessor pairs as properties (externals 73/0; full suite 16,828/0/3).

**(P18.5) — DONE (2026-09-02).** Owner additions applied ((INV.0) merged with
receipt protocol, INVERSION-DESIGN § 10 cost-neutrality contract, approvals recorded,
shrinkage dashboard row); (LIC.3) CONTRIBUTING.md; (EXT.4) classes + enums landed
(externals 40/0 — `external class` with primary ctor + companion statics;
`sealed external interface` enums; `const enum` refused loudly; full suite 16,775/0/3);
(INV.0) STEP 1: `TypeInterner` extracted — first Stage-0 collaborator, ambient surface
NONE, suite 16,781/0/3 byte-identical, cost_gate +0.00%, wall NOISE-DOMINATED at +0.26%,
allocation profile unchanged, and the receipt protocol found the split IMPROVED hot
inlining (the 277 B monolith never hot-inlined; the 13 B hop + body both do);
(API.18) file-final token healed by an ownership descent (suite 16,791/0/3, the LSP
recorded-edge pin flipped to healed, punctuation-final files pinned conservative);
(EXT.5) generic aliases + generic methods + method overloads (externals 47/0);
(EXT.6) default exports + generic references to generated targets — **the mitt rung is
GREEN** (verbatim mitt@3.0.1 d.ts generates and metadata-compiles; externals 52/0);
(INV.0) step 2: `Relation`+`Ternary` relocated to `TypeRelationCache.kt` (suite 16,803/0/3).

**(P18.3) — THE LSP IS FEATURE-COMPLETE FOR A FIRST RELEASE, AND THE HONEST tsgo NUMBER IS
30-50x AGAINST US (2026-09-01).** (LSP.2): the full feature map onto `Project` — lifecycle,
navigation, completion, signatureHelp, rename-with-refusals-as-errors, pull diagnostics,
PROJECT-WIDE publishDiagnostics off the narrowed `diagnostics()` — 16 new pins (module 58/0,
warning-clean), nativeImage task wired (build needs a GraalVM host). (LSP.3), both servers
long-lived on tsc's 78 sources: tsgo `--lsp` answers a per-edit hover in **12-18 ms** where we
take **398-630** (their lazy NodeLinks answering vs our narrowed-build-per-question —
`docs/INVERSION-DESIGN.md`'s bin-B gap measured end-to-end); first open **255 ms vs 24.8 s**
(different work: we eagerly publish the whole 46-row project error list, which their LSP
cannot do at all — our wave: **524 ms, 5 files, exactly 46 rows**). Receipts caught the
46-vs-65 gap per-file. Published in `docs/perf/incremental-vs-tsgo.md` (LSP arm) + § 3b.
Suite unchanged **16,734 / 0 / 3** plus the 16 new LSP pins → next count on the full run.

**(P18.1/P18.2) — THE DOC ARC, THE 142-METHOD CENSUS, AND THE FIRST TWO PHASE-18 CONSUMERS
(2026-09-01).** (LIC.1)/(DOC.1)/(DOC.2 on `docs/reposition`)/(INV.D) landed in the main
context; then ONE two-agent worktree wave landed **(EXT.1)** — Kotlin externals from the
CHECKED program, alias-resolution pin `Species`->`String`, metadata-compile gate with a
negative control, 15 pins — and **(LSP.1)** — JSON-RPC/LSP over `Project`, initialize +
didOpen + hover, **LSP UTF-16 = Project offsets CONFIRMED identical modulo the 1-base** at
an astral-char pin, 42 pins. `docs/INVERSION-DESIGN.md` answers the WebStorm question:
of tsgo's 142 API methods, **A=94 answerable post-hoc today, B=15 walk-scoped (13 closable
by a record-during-walk NodeLinks store — (INV.1) proposal BLOCKED-PENDING-USER), C=33 not
checker questions**; on-demand flow is NOT required for the census. **The LSP's first
fixture found a `-project` defect ((API.18): a file-final token is unreachable without a
trailing newline)** — the mission thesis demonstrating itself: a new consumer finds what
the corpus structurally cannot. Also queued: (LIC.2) the root POM says Apache-2.0
(BLOCKED-PENDING-USER, build file). Suite on the
merged tree: **16,734 / 0 failures / 3 skipped** (+57: 15 externals + 42 lsp);
`cost_gate.py` exit 0, every counter +0.00% (the new modules move nothing — the control
passes); `huge_methods.py --fail-over 0` clean (core-only census: a CONTROL for the new
modules, per its own gotcha).

**(P18.0) — THE PROJECT IS RE-POINTED: TYPESCRIPT FOR THE JVM AND KOTLIN (owner directive
2026-09-01).** The WebStorm evaluation paused — their need was a post-hoc TYPE ORACLE (the
query shape of tsgo's `tsc/internal/api/proto.go`, 142 methods) and this checker's answers are
functions of walk-scoped state; tsgo is the free official default, so "a TypeScript compiler"
is not the mission. **The mission: no Node and no Go in the toolchain; an embeddable
whole-program checker (`Project`); a Kotlin externals generator with resolved types (the
Dukat/Karakum gap); the KIR JVM bytecode backend; an LSP anyone can try in five minutes.**
The directive is persisted in CLAUDE.md § "AI agent mission", the WORK ORDER at the top of the
PLAN-PHASE-5.md QUEUE (new items (LIC.1) (DOC.1) (DOC.2) (EXT.1…n) (LSP.1…n) (INV.D) (INV.0)),
and SESSION-PROMPT.md, so run-loop iterations cannot revert to the old mission. **The (INC.\*)
latency family is CLOSED** at a 94-110 ms incremental floor / 93-217 ms plugin query — further
INC rounds are REFUSED unless a plugin-facing query measures > 300 ms warm. Suite unchanged:
**16,677 / 0 failures / 3 skipped** (doc-only commit).

**(INC.91) — THE REOPENED CLOSURE, CENSUSED THE SAME DAY AND REFUSED ON SOUNDNESS
(2026-09-01).** (INC.90) reopened the reverse-dependency closure on a 12.7x measurement; this
census refuses the PROPOSAL without touching that number. Counts, two reproducing runs.
**THREE FRAMINGS REFUTED, INCLUDING TWO OF MY OWN.** The transitive importer closure of a
`layer00` module is **187 of 2,401 files (7.8%)**, not "most of the program" — fan-out is ~4
per hop. The offset-sensitivity worry (foreign types keyed by `(fileName, pos, end)`) is REAL
(`Checker.kt:57089`) and costs exactly **ONE extra hop**: 1 fingerprint moved for an
append-at-END edit, **3** for the identical edit at the TOP, **0 beyond hop 2**.
**THE BLOCKER WAS IN THE FINGERPRINT'S OWN KDoc THE WHOLE TIME** (`:57050`): (INC.47)'s
file-boundary cut gives up TRANSITIVITY, and `incrementalDiagnostics` is sound BECAUSE a moved
signature anywhere falls back. The proposal kept the signal and deleted the fallback.
**Refuting number: on a length-preserving three-file edit the only error is at HOP 2, hop 1 is
silent in every channel, and the walk reports 0 rows where the truth is 1** — a missing
diagnostic. tsgo answers 1/1 and its own hop-1 `.d.ts` is unchanged too, so the feature is
achievable but its soundness cannot come from a signature.
**WHAT SURVIVES IS MOST OF THE WIN:** narrow a signature edit to the transitive importer
CLOSURE (`Result.importEdges`, already computed), splice the rest, use the fingerprint for
nothing — **2,401 -> 187 files (12.8x)**, sound because a superset always is, degrading to
today's behaviour on barrels. The remaining 187 -> 4-8 needs a TRANSITIVE signature, a larger
item than the walk. **The method is the reusable part: one probe runner, no wall clock, and it
killed a design with a real 12.7x measurement behind it — a prize being real is not evidence
that a mechanism for collecting it is sound.**

**(INC.90) — THE tsgo INCREMENTAL COMPARISON RE-TAKEN ON A SECOND ARM THAT IS FINALLY
LIKE-FOR-LIKE, AND THE SIGNATURE CLIFF REOPENS (INC.35) (2026-09-01).** Every tsgo incremental
number this repo had published came from ONE arm — tsc's 78 huge barrel-exporting sources,
where we report **46** rows against tsgo's **65**. New arm: `many-small-2400-dom`, 2,401 files
in 48 layers, edited at `layer00` (deepest-dependency worst case), where **both compilers
report the identical single row**, so the equivalence gate this comparison always lacked
passes exactly.
**ARM A DID NOT MOVE** (ours 5,523 warm / 226 body / 5,578 signature against the recorded
5,352 / 232 / 5,694) — expected, since the ~25 (INC.\*) rounds since removed per-FILE costs
and that profile has 78 files.
**ARM B IS THE FINDING.** (INC.35) closed the reverse-dependency closure on tsc's sources, and
Arm A corroborates it FOR TSGO TOO (signature edit **1,695 ms against its own 1,667 cold** —
its pruning recovers nothing). On LAYERED code the same mechanism is worth almost everything:
tsgo **304 ms against its own 427 cold**, i.e. a signature edit costs it what a body edit costs
(297), where we rebuild at **3,850**. **12.7x wall, ~96x marginal — the largest gap ever
measured here, and the only one with a named mechanism on the other side.** Queued (INC.91).
**BOTH NUMBERS, BECAUSE ONE GETS IT WRONG:** on the wall we answer a body edit in **137 ms
against 297** and a no-op in **0 against 264**; but tsgo's floor is 89% of its own body cell,
so its MARGINAL body cost is ~33 ms against our ~137. We win the wall on the live-session
model; they win the compute on a real invalidation algorithm.
**THE PLUGIN'S OWN CALL IS IMMUNE TO THE CLIFF** — `incrementalDiagnostics()` is reached from
`diagnostics()` and nowhere else, while the plugin asks `diagnosticsOf` exclusively (narrows at
the SOURCE): **93-106 ms on Arm B, 187-217 on Arm A, independent of edit shape**, corroborating
(INC.86)'s 90 ms per-keystroke figure.
**THREE HARNESS DEFECTS, AND THE FIRST IS THE ONE TO REMEMBER:** the inherited fixture's
`orig.ts` was CRLF while both edit variants were LF, so every "one-line edit" was that line plus
a 3,916-line newline normalisation; the tsgo harness read its row count out of a subshell and
printed stale values; both harnesses were hardcoded to `binder.ts` and to scratchpad paths that
survived by luck. All three fixed, plus two receipts the old runner could not print (a per-cell
row count, and served-vs-fell-back from `Project.incrementalAnswers` — both arms read body 3/3,
signature 0/3).
**AND CLAUDE.md's ROUND-938 CLAIM IS FALSE:** pristine `typescript@6.0.3` IS runnable here and
agrees with tsgo on all 65 rows, so the gap is **19 genuine false negatives of ours, 0 tsgo
divergences** — but 18 of 19 are emission-side on work already done, so it is not a 29% work
gap. `docs/perf/tsgo-diagnostic-gap.md` (new), `docs/perf/incremental-vs-tsgo.md` (rewritten).
Suite **16,677 / 0 failures / 3 skipped**.

**(INC.89) — THREE INHERITED REFUSALS RE-DERIVED, ONE PLUGIN-FACING API MEMBER PINNED, ONE
SPLIT LANDED (2026-09-01).** (INC.88) left a standing instruction — "anything larger needs the
refusals re-derived rather than inherited" — and the first half of this round is that, on
reading alone. Fresh baseline, TWO processes ((INC.52)): WALL **102/106 ms**, init block
**40.0/48.2**, head `init:buildFileLocalTypeMaps` **21.4/15.3**, `init:buildPerFileScopes`
**5.7/5.5**, `init:computeAllEnumValues` **4.8/4.5**.
**THE THREE ANSWERS DIFFER FROM EACH OTHER, WHICH IS THE ARGUMENT FOR DOING IT.**
`buildFileLocalTypeMaps` is **CONFIRMED** — it is partition-scoped already, builds ONE file's
map (`eagerBuilds=1`), and the ms is that file's first real type-resolution cascade; do not
re-open it from its size. The two un-Boyer-Moore-able whole-program regexes in
`collectUmdGlobalsAndModuleFiles` are **already censused honestly** ("0 ms — 0 `.d.ts` files.
LATENT on a `@types` tree"). And `isModuleFile`, recomputed **≥5 times per build** across the
init setup block, is **REFUTED as a lever with no build at all**: it early-returns on the FIRST
import/export, which every file of a module-shaped project has. A repetition count is not a
cost — round 801's law one predicate over.
**(a) `Project.reloadFile` AND `OverlayVfs.revert` ARE NOW PINNED** — the API (INC.75) tells the
IntelliJ plugin to adopt, documented as a first-class third change kind and present in the suite
only as a step inside two `trustFilesystem` tests, with its implementation half unpinned
entirely. 17 pins, no production code changed. **TWO ablations, because one cannot grade both
halves**: emptying `reloadFile` reddens 7; emptying `revert` reddens 12 — all 7 revert pins plus
the 5 reload VALUE pins, cross-validating that reload's promises flow through `revert`. A doc
edge was pinned rather than waved through: for a file existing ONLY in the overlay, "what is on
disk is the truth" means **ABSENCE**, so reload removes it.
**(b) THE (INC.20) SPLIT FOR `checkCrossFileUseBeforeDeclaration`** — its emitter walked all
2,401 files to produce rows `getDiagnostics()` then dropped, because the diagnostic is anchored
at the USE file and the partition filter discards the rest. **The ordinal invariant is the whole
risk**: the verdict compares `decl.fileIdx > useFileIdx`, both ordinals of `binderResults`, so
re-heading on `checkedResults.withIndex()` renumbers `useFileIdx` to ~0 and flips the verdict
toward FALSE POSITIVES silently. The head therefore stays `binderResults.withIndex()` and the
partition is a `continue` AFTER the enumeration. Receipt is a COUNT, never a millisecond.
Ablation reddens **pin 6 only**, and the other six are recorded as structural rather than
claimed as coverage.
**(d) THE BIGGEST PLUGIN-FACING LATENCY ITEM ON THE PAGE IS NOT A DEFECT WORTH FIXING — IT IS
THE FLOOR.** `docs/language-service.md` § 13's "one open defect" was that
`completionsAt`/`signatureHelpAt` cannot reach a prepared check (207 ms after `prepare(6)`
against 194 cold). Both refusals standing in front of it were re-derived at HEAD and **both
hold; (INC.33) is FIRMER than when written** — break-even **1.40 -> 1.52** and **12.1 -> 12.9**,
*because* the floor arc cut the base while per-anchor capture did not; retention unchanged to
the digit (**54.4 M** records for one widened `checker.ts` entry). The queue's own named
successor, the PREPARE-AMORTISED case, is **REFUTED BY MECHANISM**: the typed `.` must reach
`updateFile` or the completion anchor is computed from stale text, and `updateFile` does
`captures.clear(); prepared = null`, so the dominant completion is invoked at a state nothing
can have prepared. **And the prize it assumes does not exist** — `member.caret` costs what
`base.noCapture` costs (224 vs 254 ms; 2,035 vs 2,189), so the ~200 ms **is one narrowed
build**: completion latency IS the incremental floor. § 13 now cites BY SYMBOL after its line
numbers rotted a third time in a day.
**GATES.** Suite **16,677 / 0 / 3** (+24, all this round's pins); `cost_gate.py` exit 0, every
counter +0.00%, `output.errors` 46; `huge_methods.py --fail-over 0` clean; **`partition-gate.sh
sensitivity` EQUIVALENT on all 76 files across 78 netting passes, 72 carrying rows** — the arm
that can see a starved partition. `cost_gate` and the corpus are CONTROLS here, not coverage.

**(INC.88) — THE ROOT-FILE GLOB IS REFUSED, AND THE SPLIT IS WHAT EARNS IT (2026-09-01).**
Re-decomposing after (INC.87)(a) put the glob SECOND at **9.95 ms of a 95-100 ms query**, behind
an init block that is 48.8 and largely refused. Closed in both available directions.
**DIRECTION 1, memoizing the glob across builds under `trustFilesystem`, is refused by a promise
this compiler already SHIPS:** that KDoc says "ADDED and REMOVED files are still discovered from
the backing store on every build. **Nothing about the file SET is taken on trust**", two pins
state it, `OverlayVfs` and `docs/language-service.md` repeat it, and (INC.65) refused the same
shape one layer down. (INC.60)'s policy — a no-promise fix outranks a promise-costing one — is
why the other half was measured first.
**DIRECTION 2 WAS A REAL HYPOTHESIS AND IS REFUTED.** (INC.77) priced this row's syscall half at
~1.8 us/entry and called the residue irreducible — measured over `SystemVfs` ALONE, where the
shipped path is `OverlayVfs` wrapping it plus a per-directory sort, and the row reads 3.4-3.8
us/entry. Two new sub-rows closing against the SAME open timestamp:
`listEntries + sort 8.431 ms` = **sort 0.483 (5.7%)** + **OverlayVfs merge 0.752 (8.9%)** +
**the BACKING STORE's listing 7.196 (85.4%)**. That 85% is `File.listFiles()` plus one `stat`
per entry, and Java exposes no `d_type`, so one syscall per entry is a floor. **(INC.77) is
CONFIRMED on the shipped path** and both wrappers together are 1.2 ms of 8.4.
**WHAT LANDS IS THE INSTRUMENT, NOT A FIX** — the rows are inline no-ops when the probe is off,
so the refusal is reproducible instead of a claim in a note, which matters because the refusal
they confirm had been quoted for three rounds without ever being checked on the path it
described.
**GATES.** Suite **16,653 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean.

**(INC.87)(a) — THE POST-CHECKER'S FILTER ROW IS 4.5 ms OF A KEYSTROKE AND 89% OF IT ANSWERS
NOTHING; SPLITTING IT REFUTED ITS OWN SHAPE (2026-09-01).** (INC.86)(a) named
`post-check diagnostic filters` — 4.22 ms of a 90 ms query and a row NO queue item had ever
named. Split into three abutting sub-rows first, per (INC.65): **POST_DIAGS 4.507 -> 0.508 ms**,
of which **TS2688+TS2209+isolatedDecls 3.296 -> 0.492**, the **`modulePreserve4` whole-program
text scan 1.184 -> ABSENT (`calls` 1 -> 0)**, and the parse-cascade `removeAll` chain 0.0022 ->
0.0017 as the untouched control. The three summed to 99.4% of the row.
**THE OBVIOUS CANDIDATE WAS THE SMALLER MEMBER.** Reading the region, the eye lands on one
unconditional whole-program TEXT scan sitting above the guard that is its only consumer — real,
and 26%. The other 73% was `checkMissingTypesReferenceExports`' package.json pass, rooted at an
alternation `(?:^|/)`, so `BnM.optimize` gives it no literal and it is attempted at EVERY
POSITION of every file NAME — on a fixture with no `node_modules` at all, i.e. wholly to answer
NO. Pre-gated on `endsWith("/package.json")`, EXACT because the pattern's own tail anchors
there, regex kept live as the decider (round 792). The text scan is deferred behind a `lazy`
with the cheap basename test moved in front of it — and it is paid TWICE per keystroke in the
shipped design, since the (INC.17) recheck re-runs that very lambda.
**NO WALL IS CLAIMED AND THE SAME RUN SAYS WHY:** WALL read 108 -> 88 ms while `initNanos` read
**51.5 -> 77.9** on untouched code. One `--passTiming` draw is not a measurement ((INC.52)) and
the query wall carries (INC.72)'s ±20 ms term. The receipt is a COUNT — the bracket lives INSIDE
the `lazy`, so `calls == 0` IS the statement that the scan never ran.
**BOTH PIN CLASSES WENT RED FIRST, BOTH INSTRUCTIVELY.** `ProjectCompiler` never puts a
`package.json` into the program at all, so the TS2688 pin had to move to the multi-file harness
— as an absence assertion it would have been green forever; and the count pin's fixture was
named `b.ts`/`c.ts`, two of the twelve `modulePreserve4` basenames, so the scan correctly ran.
That collision is now the POSITIVE control (round 790).
**REFUSED, not shipped:** `init:evolvingArrayUseSiteWalks` (1.835 ms, five throwaway
collections per file) — a rewrite was built and REVERTED, unpriceable and unpinnable locally.

**(INC.81) — A LIST PER KEY FOR 9,401 KEYS THAT NEVER GOT A SECOND ENTRY, AND A REFUTED
ROUND-471 HYPOTHESIS (2026-08-31).** `Checker.enclosingImportIndex` is **4.7 ms** of an 87 ms
per-keystroke query and NO queue item had ever named it — it surfaced only from re-taking the
ranking after (INC.78)/(INC.79)/(INC.80), which is what (INC.57)'s law asks for.
**CENSUSED BEFORE ANYTHING WAS DESIGNED:** the build inserts **9,401 specifiers under 9,401
DISTINCT keys**, so every `getOrPut` misses and every one allocated a `MutableList` and a
`Pair` — and **`multiFileKeys=0`**, i.e. not one key is reached from two files, so the
whole-program structural reach matches nothing on a real project.
**THE OBVIOUS HYPOTHESIS WAS MEASURED AND REFUTED.** The key is an AST *data class*, so
`hashCode` recurses both Identifiers and both comment lists (round 471). Priced in ONE
timestamp pair over a second pass: **76.5 ns each, 0.72 ms — 14% of the row**. The walk is
~1.0 ms and **~3.4 ms is the insert plus the two allocations**. So the key is left alone (its
structural semantics are load-bearing) and only the REPRESENTATION changed: the `Pair` itself
for the one-entry case, promoted to a list on a second claim, map presized.
**MEASURED** with two class dirs differing only in this, rotated across processes: **4.60 ->
3.17 ms**, after winning 3/3 batches in both directions with NON-OVERLAPPING ranges, and the
population census identical in both arms.
**THE PIN EXISTS BECAUSE THE CENSUS SAYS NOTHING REACHES THE PROMOTION** — `multiFileKeys=0`
is precisely that statement — so it takes a fixture, and two BYTE-IDENTICAL importers are one
(`ImportSpecifier`'s components include `pos`/`end`, so the same import at the same offsets in
two files IS one key). Two ablation arms, each reddening a DIFFERENT pin.
**GATES.** Suite **16,624 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean.
**RESIDUE REFUSED WITH REASONS:** the walk IS the index's definition, and the hash cannot move
without changing a key whose structural semantics the replaced scan fixes.

**(INC.80) — JOINING A PATH BY ARITHMETIC, AND THE TWO-DRAW READ THAT NEARLY REFUTED IT
(2026-08-31).** `PathUtil.join(base, part)` built `"$base/$part"` and normalized it — and for
a module specifier that is exactly the case `isNormalized` must refuse (a `..` segment), so
(INC.68)'s fast path could never help it and the general body allocates a `split` list, a
`String` per segment, an `ArrayDeque` and a `joinToString` builder: **3.4-4.1 ms over 4,701
calls** in the crawl's specifier resolution. Counting the leading `..`, dropping that many
segments off the base with `lastIndexOf` and concatenating is **131-136 ns** — priced as a
probe arm and checked against the general body on all 4,701 real pairs BEFORE it was built.
**THE MEASUREMENT IS THE PART WORTH READING.** Two draws of the row said NOTHING (6.26/7.22
before, 6.22/7.45 after) and the refutation was already being written. **Six draws per arm,
ROTATED ACROSS PROCESSES over two class dirs differing only in this file: 6.41 -> 4.95 ms at
the median, the after arm winning in ALL THREE batches and BOTH rotation directions.**
(INC.68)'s law bites in this direction too — an unrotated pair cannot see a 23% change in the
very row it measures.
**AND THE FIRST EXPLANATION WAS REFUTED RATHER THAN ASSUMED:** the natural story (the
allocating arm pays GC the build never pays, round 801) is wrong — with a 2 GB young gen the
allocating arm got SLOWER (873 -> 1,264 ns) and so did the arithmetic one (131 -> 257), 20
young pauses in the whole process.
**RECEIPTS:** `pathNormalizeCalls` **11,935 -> 9,577** and every remaining call takes the
already-normalized path — a floor build performs **ZERO allocating normalizations, down from
2,358**.
**PINS** are a DIFFERENTIAL against the general body over a 12-base x 25-part grid ((CFG.1): a
wrong join names a different FILE and nothing here notices). **It caught its own defect on the
first run** — joining at the ROOT spelled `//dep`, a base the 4,701-pair fixture population
does not contain and the adversarial grid does. Four ablation arms; the no-fast-path arm
reddens ONLY the regime pin.
**GATES.** Suite **16,622 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean.
**SUCCESSOR:** `dirname` + the memo key at **~1.5 ms over 4,701 calls** — the crawl loop knows
the importer's directory once per FILE and re-derives it per SPECIFIER.

**(INC.79) — THE CRAWL ASKED THE FILESYSTEM ABOUT FILES THE GLOB HAD ALREADY LISTED
(2026-08-31).** (INC.73)(a) refused this row's syscall half by arithmetic — "2,351 distinct
resolutions at exactly one `exists` each, so ~2.6 ms is irreducible". **That is true of the
resolver in isolation and false of the BUILD**: the root-file glob has already listed every
directory of the project and proved which files are there, off the same `Vfs`, ~20 ms earlier
in the same build. A per-component refusal can be right about its component and wrong about
the program, and what says so is asking who else already knows the answer.
**DECOMPOSED FIRST** (one binary, ABBA-rotated, population checked against the build's own
4,701 specifiers / 2,351 distinct): `resolve` **9.4-9.8 ms**, of which `existsOnly` **4.4-4.6**
(2,350 probes at ~1.9 us), `joinOnly` **3.7-3.9**, `dirnameOnly` 0.8, `keyOnly` 1.2,
`bookkeeping` 0.5-0.8 — so the syscalls are the largest piece and the path arithmetic the
next, neither of which the row itself could say.
`ModuleResolver` now memoizes `exists`/`isDirectory` for the build and is SEEDED from the
glob. **It adds no assumption**: (INC.65) already memoizes the whole ANSWER per
`(importerDir, specifier)`, strictly stronger, over the same one-build lifetime. **The seed
may only say YES** — a file can exist and be excluded from the program.
**MEASURED:** the row **10.2-12.0 -> 5.8-6.5 ms**, and the receipt is the count the build
prints — **2,351 questions, 0 reached the filesystem**.
**THE ABLATION FOUND THE PIN SET INCOMPLETE, WHICH IS WHAT IT IS FOR:** keying the memo by
BASENAME reddened only the COUNT pins, because every value pin happened to ask about names
existing on both sides — a wrong PROGRAM, silent per (CFG.1). The missing pin was added and
b2 then reddens it. Three arms, three distinct red sets (4 / 3 / 3).
**GATES.** Suite **16,618 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean.
**SUCCESSOR, measured and named:** `PathUtil.join`/`normalize` at ~810 ns x 4,701
(**3.7-3.9 ms**, a `normalize` that must process `..` segments, which (INC.68)'s fast path
cannot help) and `dirname` + the memo key at **~1.5 ms**, which the crawl loop could hoist
per FILE.

**(INC.56) — AN IntelliJ-CLASS HOST CAN SKIP THE RE-READ, AND THE ROW IT WAS AIMED AT WAS A
*LOCATION* (2026-08-31).** Two opt-in halves in the embedding API: `Project.trustFilesystem`
(the host promises the bytes of a file will not change without this project being told —
through `updateFile`, `deleteFile` or the new `reloadFile`) and `Vfs.readTextIfResident` /
`Vfs.retainRead` (the crawl skips its per-file THREAD HANDOFF for content already in memory).
Retention is written ONLY from the crawl's single-threaded fold — round 825, because the crawl
reads from N concurrent workers.
**MEASURED**, 8 instrumented draws per arm, one JVM per arm, arms rotated across processes,
both rotations agreeing, with the untouched sequential specifier-resolution row as the control:
crawl WALL **30.6/37.0 -> 21.7/19.4 ms** at 2,401 small files and **13.7/14.2 -> 9.5/7.8 ms**
on tsc's 78 huge ones; `read+decode` **132.6/176.1 -> 1.52/1.39** and **65.4/63.2 ->
0.076/0.057**.
**AND THE REFUTATION IS WORTH MORE THAN THE ROW: THE QUEUE PRICED THIS FROM `FrontEnd.READ`,
WHICH IS ELAPSED-WITH-SUSPENSION — A LOCATION, NOT A PRICE.** Retaining the content WITHOUT
skipping the hop served **33,350 reads from memory and moved the crawl's wall by NOTHING** on
the 2,401-file project, while halving it on tsc's 78 huge sources. **The read is a BYTE cost;
the row that made it look like a FILE cost was the hop's suspension** — so the fix that works
on both shapes removes the HANDOFF, not the read.
**THE PROMISE IS NARROWER THAN THE ENTRY FEARED, AND IT IS PINNED:** additions and deletions
are still discovered on every build (nothing caches the file SET), and `.json` is never
trusted. 18 pins including the documented LIMIT (an unreported content change IS missed) and a
REGIME pin that the crawl really takes the resident path; 4 of 5 ablation arms discriminate and
the fifth is recorded as a REDUNDANT GUARD rather than claimed.
**GATES.** Suite **16,586 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00% — a CONTROL,
since `SystemVfs` resides nothing and the CLI path is provably unchanged; `huge_methods.py
--fail-over 0` clean.
**SUCCESSOR:** the crawl's remaining halves — sequential specifier resolution ~11-13 ms
(non-syscall remainder; its syscall half is refused by (INC.73)(a)) and a ~7-9 ms concurrent
residue that is the `flatMapMerge` machinery itself, i.e. (INC.64)'s question with the last hop
gone.

**(INC.78) — THE ROOT-FILE GLOB ASKED AN *ACCEPTING* REGEX PER CANDIDATE, AND NO REFUSAL
FILTER COULD HAVE HELPED IT (2026-08-31).** `collectRootFiles` ran
`excludeRegexes.none { } && includeRegexes.any { }` for every candidate of every build — i.e.
on every keystroke of a language-service host — at **4.66-8.08 ms, 1.9-3.4 us per candidate**
on a ~90-110 ms incremental floor at 2,401 files.
**THE ATTRIBUTION INVERTS THE OBVIOUS FIX.** (INC.77) proposed a cheap prefix/extension
pre-filter; measured standalone on one binary, the EXCLUDE half is **191 ns/candidate** (its
literal prefix fails on the first character) and the INCLUDE half is **2,239** — `src/**/*`
compiles to `^…/src/(?:[^/]+/)*[^/]*(?:\.ts|…)$`, which backtracks over every directory
segment and **runs to a MATCH for every file in the project**. A filter can only refuse, so
the lever is an EXACT shortcut and the proposal was aimed at the half that was already cheap.
`GlobMatcher` keeps the regex as its DEFINITION and answers the
`<literal>` + `**` segment + bare `*` leaf + literal tail shape — `src`, `src/**/*`,
`src/**/*.ts`, `dist`, `**/*.spec.ts`, i.e. what tsconfigs contain — from the head and the
tail. Two corrections came from EXTENDING the differential grid rather than reading it: an
EMPTY SEGMENT is the one remainder `(?:[^/]+/)*[^/]*` cannot match (a doubled separator now
falls back to the oracle), and that test is exact only because the head ends at a directory
boundary. The length guard is provably unreachable and is recorded as a REDUNDANT GUARD.
**THE WALL COULD NOT CARRY THE CLAIM: the same one-process ratio read 12x, then 5x, then 3x
over four processes of one binary** (round 867's arm instability). The receipt is
`FrontEnd.globRegexEvals` — decisions that reach the regex — **4,802 -> 0**, pinned at TWO
program sizes with a positive control that a constrained pattern still runs it once per
candidate. In-build `CFG_MATCH` **4.66 -> 0.61 ms**, root-file glob row **14.46 -> 9.16**.
Gate is a DIFFERENTIAL, not a green suite ((CFG.1): a wrong root-file set is silent here);
4 ablation arms, 4 distinct red sets, and the no-fast-path arm reddens ONLY the cost and
regime pins while every value pin stays green.
**GATES.** Suite **16,610 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00% including
`output.programFiles` 78 -> 78; `huge_methods.py --fail-over 0` clean.

**(INC.76) — THE LANGUAGE SERVICE WAS PAYING (INC.60)'s DEFECT IN FULL, THROUGH A WRAPPER THAT
DID NOT OVERRIDE (2026-08-31).** `Vfs.listEntries`'s default body is
`list(path).map { VfsEntry(it, isDirectory(it)) }`, and (INC.60) added that member precisely
because asking the kind per entry is kotlinx-io's `metadataOrNull` — **up to FIVE `stat`s**.
`OverlayVfs` never overrode it, so **every `Project` build handed the whole saving back**,
silently, since the answers are identical either way.
**MEASURED STANDALONE over the build's own 50 directories / 2,451 entries: 6.34 ms taking the
kinds from the delegate's listing against 19.54 ms asking per entry — and 19.5 is what the
build's `vfs.listEntries + sort` row read.** That match turned a 3x probe-vs-row gap into a
diagnosis. **LANDED, and it costs NO promise, so both arms gain**: that row **20.70 -> 9.73
ms**, the whole root-file glob **28.14 -> 18.44**, the per-keystroke query **153/145 ->
123/125 ms** trusted and **156/162 -> 140/138** untrusted.
Pins are a DIFFERENTIAL against the default body — a wrong kind drops a file from the program
or adopts a directory as a root, and (CFG.1) says nothing here notices — including the one
asymmetry an obvious implementation gets wrong (an on-disk FILE the overlay has given children
is a DIRECTORY). The cost pin had to be restated as a COMPLEXITY claim at two program sizes:
`isDirectoryCalls == 0` is false and correctly so, because a build asks about specific PATHS.
`CountingVfs` had the same omission and is fixed with it; an audit found no third case.
**TRANSFERABLE: a defaulted interface member added for speed is a silent regression waiting
for the next wrapper**, and the instrument is a row measured STANDALONE against the same row
measured IN THE BUILD.

**(INC.73) — A 2.5 ms ROW, AND THE TWO REFUTATIONS THAT COST NOTHING TO FIND (2026-08-31).**
`init:moduleTypeNameIndex` — the largest single row left in the floor's per-pass table after
(INC.69)/(INC.70)/(INC.71) — is built on FIRST ASK; GO/NO-GO first, per (INC.16):
`moduleTypeNameIndexBuilds` **0 on a floor build, 1 on a full one**.
**ITS VALUE RECEIPT IS THE 8 PROFILES AND THE CORPUS IS A CONTROL — the ablation that never
builds it reddens ZERO of the ~13k baselines and 3 of the 8 profiles (+2 rows each: harness,
server, services)**, which is exactly where rounds 471 and 513 got their evidence. **A family
can have no corpus coverage at all and still be load-bearing; the way to find out is to ablate
and grid, not to reason about it.**
**AND THE HONEST PART: neither the floor wall (medians 117/124 before against 119/127 after —
no separation) nor a 2-process phase A/B can resolve 2.5 ms.** The receipts are the pass row
from the clean single-binary decomposition plus the deterministic count, and the round is
written up as the 2.5 ms landing it is.
**TWO REFUTATIONS FROM THE SAME RECON, BOTH WORTH MORE THAN THE ROW.**
**(a) `SystemVfs.exists` IS ONE SYSCALL** — 1130.7 ns/call against `java.io.File.exists`'s
1108.8, **1.02x**, ABBA inside one process over the fixture's own 2,401 paths. So (INC.60)'s
five-stat finding is specific to `metadataOrNull` and does NOT generalise; and the resolver
already probes exactly ONCE per resolution (**2,351 `exists` + 10 `isDirectory` for 2,351
distinct pairs**, because `.ts` is first in `allExtensions`), so there is no syscall lever in
the crawl's 11 ms resolution row.
**(b) `init:collectUmdGlobalsAndModuleFiles` (2.32) and `init:mergeFileLocalsIntoGlobals`
(2.06) ARE NOT DEFERRABLE**, and the reason is not their readers but their readers' SCHEDULE:
`umdGlobalNames` is read by the merge itself and `moduleFiles` by `collectModuleAugmentations`,
both LATER INIT PASSES that run unconditionally. Combined prize ~5 ms of a 94 ms floor —
refused on arithmetic.
**GATES.** Suite **16,568 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00% (including
`typeNode.bypassed` 145,723, the direct receipt that `multiFileModuleTypeNames` answers
identically); `huge_methods.py --fail-over 0` clean; 8-profile grid `added=0 removed=0`.
**SUCCESSOR:** the init dispatch has no non-walker row above ~1.4 ms left, so what remains
there is (INC.7)'s partition question one walker at a time; **the floor's largest row is the
CRAWL and its READ half is (INC.56)** — now the only row left with a double-digit prize, and
the one an IntelliJ-class host can simply hand us.

**(INC.72) — THE SURPLUS WAS THE CRAWL, AND BOTH OF THIS SESSION'S WALL FIGURES ARE RETRACTED
(2026-08-31).** (INC.70) and (INC.71) each reported an ABBA-rotated floor wall about **three
times** what their pass row explained, and that gap was queued as a mechanism to hunt. It was
not a mechanism. Running the SAME two binaries with the per-PHASE instrument — two processes
per arm, rotated, second instrumented draw — attributes the change and nothing else:
**init-block pass dispatch 39.87 -> 25.06 ms (-14.81)**, which is what the two pass rows said,
while the UNTOUCHED **import-graph crawl swung +18.01** in the same run, its
elapsed-with-suspension `read+decode` sum moving **147.8 -> 249.9 ms**. Every other phase is
flat to within 0.7 ms.
**So (INC.70)'s "160.0 -> 136.5 (-23.5)" and (INC.71)'s "142.5 -> 120.0 (-22.5)" are each one
batch's reading of a quantity carrying a ±20 ms concurrent term; the same binaries read
128.5 -> 116.5 in this round's batch. What ships is -14.81 ms of init-block dispatch,
phase-attributed, and that is the number to carry.**
**THE LESSON IS NOT "ROTATE MORE" — IT IS "PICK AN INSTRUMENT WHOSE VARIANCE DOES NOT CONTAIN
THE ANSWER".** (INC.68) showed a BLOCKED batch inventing a delta that rotation removed; this is
the next step out — a ROTATED batch of a COMPOSITE quantity still cannot separate two of its
terms, and 4 processes x 8 draws per arm did not help, because the noise is a real, large,
unrelated phase rather than run-to-run jitter. For a checker-side floor change the receipt is
now `FrontEnd`'s phase row plus the deterministic population count; the floor wall is a sanity
check. `FloorAbMain` grows an `fe` mode so that decomposition is a two-BINARY A/B.
**SESSION TOTAL, re-taken on the SAME INSTRUMENT rather than inferred from the A/B arms —
`scripts/floor-decomposition.sh`, same fixture, same warm-ups, same `PLAIN late` slot: the
2,401-file `dom` floor is 122 -> 94 ms (`PLAIN early` 144 -> 105).** Two runs of one recipe
have no arm-rotation problem to get wrong, which is (INC.72)'s lesson applied to the
REPORTING. **The ranking has changed and the next round must start from it: the CRAWL is now
the largest floor row (29 ms, 36%) for the first time in this arc — its READ half is (INC.56),
the one row costing a soundness promise and the one an IntelliJ-class host can hand us — with
the init-block dispatch at 22 (28%), config+glob 12, bind 8, post 5.** The pass table is
**22.43 ms over 418 rows**, headed by three whole-program INDEX builds
(`init:moduleTypeNameIndex` 2.52, `init:collectUmdGlobalsAndModuleFiles` 2.32,
`init:mergeFileLocalsIntoGlobals` 2.06) — none of them a per-file table, so the
(INC.70)/(INC.71) deferral shape does not transfer unchanged, and the GO/NO-GO for each is
(INC.16)'s counter: who forces the index, and is it anyone on a floor build?

**(INC.71) — THE PER-FILE VISIBILITY SETS, AND A FLOOR WALL THAT KEEPS OUTRUNNING THE PASS
TABLE (2026-08-31).** `init:computePerFileVisibility` walks every program file's `locals` to
publish `moduleOnlyGlobalNames` and `libValueShadowNames`, whose only three readers —
`globalsForFile`, `globalsForFileNode`, `libValueBehindTypeOnlyShadow` — are all NAME
RESOLUTION. So a build that checks nothing reads neither.
**THE POPULATION DECIDED IT BEFORE ANY IMPLEMENTATION, for the price of one temporary
counter: 0 asks on a floor build of the 2,401-file fixture against 335,881 on a full one.**
(INC.16)'s law used as a GO/NO-GO rather than as a post-hoc explanation.
**THE ORDERING CLAIM WAS CHECKED**: the pass compares `globals.keys` against
`init:snapshotPreAugGlobalKeys`' snapshot, and all three writers of `globals` run at earlier
init steps. **The one place it is deliberately NOT lazy is the probe** — the INV.3(a)
classifier is still installed at the pass's moment and FORCES the sets from inside its lambda,
so `globals.lookups` reads 783,383, **+0.00%**.
**MEASURED:** row **-> 0.002-0.003 ms** from 5.5-7.2; ABBA-rotated floor
**142.5 -> 120.0 ms (-15.8%)**.
**THE VALUE RECEIPT IS THE CORPUS, AND THAT IS NOW A RULE RATHER THAN AN ACCIDENT:** ablation
c2 (sets stay empty) reddens **492** core tests, while the hand-written `-project` value pin
stays GREEN — the second round running where a `-project` pin cannot discriminate the
mechanism and the corpus discriminates it in the hundreds. For the INV.3 visibility model the
`-project` pins gate the REGIME (which builds do the work) and the corpus gates the ANSWER.
**GATES.** Suite **16,565 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean; 8-profile grid `added=0 removed=0`.
**SUCCESSOR IS A MEASUREMENT QUESTION, NOT A ROW ((INC.72)):** twice in a row the rotated
floor WALL moved about **three times** what the pass table explains (-23.5 against ~4 ms,
-22.5 against ~7). Both changes also removed thousands of RETAINED allocations per build,
which round 801 says is a plausible mechanism and not a measured one. Decompose BOTH arms with
`--frontEnd` before opening another init row: either the surplus is outside the init block, or
the `rows`-tier probe under-reports and every ranking taken from it needs re-reading.

**(INC.68) — 80% OF THE PATHS THIS COMPILER NORMALIZES WERE ALREADY NORMALIZED, AND THE
BLOCKED ARMS INVENTED A REGRESSION THAT ROTATION REMOVED (2026-08-31).** (INC.66) said
"before pricing any row, check it has a SPLIT"; the row it named for re-decomposition —
`config+glob`, the one floor row carrying no soundness promise — had a split already, and the
cost was under it in a function neither row names. `PathUtil.normalize` is called once per
directory entry by `systemListEntries` and once per candidate probe by `PathUtil.join`, and
allocates ~10 objects each time. **THE CENSUS IS THE WHOLE ARGUMENT AND IT COST ONE COUNTER:
11,935 calls per floor build, 9,584 (80.3%) returning the argument UNCHANGED** — not a
property of the fixture, but of the callers (a child path built from an already-normalized
parent; `"<normalized base>/<plain name>"`). So the fix is a one-pass allocation-free
predicate and an early return: no cache, nothing to invalidate. **PRICED BY POPULATION
BEFORE THE FLOOR WAS CONSULTED** ((INC.52)): 1.02-1.22 us/call against <=0.2, i.e. ~9 ms per
floor build, which is what the rows returned. **ABBA-rotated, 4 processes/arm, 32 floor draws
each:** `vfs.listEntries` 10.86 -> 7.76, specifier resolution 14.94 -> 10.66, crawl WALL
39.51 -> 32.18, config+glob 17.96 -> 13.44, **floor median 127 -> 121 ms**.
**THE LESSON OUTRANKS THE MILLISECONDS: the first, BLOCKED, paired run reported +2.70 ms on
`include/exclude regex match` — a region that calls no `normalize` — reproducibly over 12
draws per arm, and read config+glob as +3.39, i.e. it said the glob half was a net loss. Both
signs INVERTED under rotation.** A per-arm draw count does not substitute for rotation, and a
stable delta in a region with no causal path to the edit is the tell that the ORDER is the
variable.
**THE PINS ARE OVER THE ACCEPTANCES, because the directions are asymmetric**: a false
negative costs the old path, a false positive resolves to a DIFFERENT FILE with no diagnostic
anywhere ((CFG.1)). Value pins against a transcribed reference (a second implementation — a
differential whose arms are one function cannot see a fast path), plus idempotence, a
rewrite-count control and a quiescence-independent predicate pin. Ablations a1/a2/a3/a4 redden
5/5/4/3 of 6.
**GATES.** Suite **16,548 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00% including
`output.programFiles` 78; `huge_methods.py --fail-over 0` clean; 8-profile grid
`added=0 removed=0` on all eight — **coverage here rather than a control**, since the corpus
materialises no directory and cannot reach the resolver's path arithmetic.

**(INC.70) — EVERY BUILD ALLOCATED A NAME-RESOLUTION TABLE FOR EVERY FILE, AND A FLOOR BUILD
READS NONE OF THEM (2026-08-31).** `init:buildPerFileScopes` allocated two maps per program
file, copied that file's own top-level locals into one and precomputed a
`LayeredSymbolTable`'s shadow list — for EVERY file, on EVERY build, whether or not a name was
ever resolved there. **THE POPULATION WAS MEASURED BEFORE ANY TIMING, per (INC.16):
`perFileScopeBuilds` is 2,401 -> 0 on a floor build of the 2,401-file fixture and 2,401 ->
2,401 on a full one.** Not "fewer" — none.
**WHAT MAKES THE DEFERRAL EXACT IS AN INIT-ORDER FACT NEITHER FUNCTION STATES**: the eager
loop SNAPSHOTTED `result.locals` precisely to survive a later mutation, and the checker's ONE
writer of a `BinderResult.locals` is `collectModuleAugmentations`, dispatched at an EARLIER
init step — so the two snapshots are the same table. A writer scheduled after this pass would
make the eager and lazy answers disagree silently.
**MEASURED:** row **4.625 -> 0.750 ms** (second instrumented draw), whole init block
39.34 -> 36.38; ABBA-rotated floor **median-of-medians 160.0 -> 136.5 ms (-14.7%)**, four
process medians DISJOINT. **The wall delta is larger than the row explains (~4 of ~23 ms) and
the surplus is recorded as UNATTRIBUTED, not claimed** — the eager form also retained ~4,800
maps per build, which is a plausible mechanism and not a measured one (round 801).
**THE VALUE HALF IS A MEASUREMENT, NOT AN ASSUMPTION:** ablation b2 (never build a scope)
reddens **503** core-suite tests.
**AND THE THIRD ARM IS RECORDED AS BLIND, which is the round's second finding:** b3 (never
STORE the built scope) reads 0 RED even after the fixture was strengthened, because
`perFileScopeOf`'s one-entry IDENTITY memo absorbs every repeated ask for the same file — so
the map's memoization is pinned by nothing here, and the reason is a second cache one layer
up. Likewise the value pins do not discriminate `perFileScope`'s presence at all: under b2 the
module-local leak is STILL TS2304, because `moduleOnlyGlobalNames` decides that upstream.
**GATES.** Suite **16,559 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean; 8-profile grid `added=0 removed=0` — COVERAGE here, since
an absent scope makes `perFileScopeOf` answer null and every consumer falls back to the merged
`globals`, i.e. a name resolving to a FOREIGN module's local.
**HARNESS TRAP WORTH THE LINE:** a cross-binary A/B runner may read no census counter that
does not exist in BOTH arms — the older arm dies with `NoSuchMethodError` and the batch prints
one arm's medians as if they were both.

**(INC.69) — THE INIT-BLOCK DISPATCH IS NOT FLAT, AND A PLATEAU IS A SHARED PER-FILE COST
(2026-08-31).** (INC.66) recorded the ~400-pass table as FLAT, "so there is no row to make
cheaper"; a HISTOGRAM rather than a top-N list refutes it — on `many-small-2400-dom` the
floor table is **418 rows summing to 39.5 ms, 44 of them carrying 37.1 (94%) and 367 carrying
0.82** — and 21 of those 44 sit at an almost identical **0.39-0.55 ms**. A plateau of
near-identical prices across unrelated walkers is not a coincidence of what they do: all 21
are corpus PIN walkers whose whole body is a whole-program loop whose first act is
`fileName.substringAfterLast('/') != "<one literal>"`, i.e. 2,401 iterations and a `String`
allocation each to compare against a name no real project contains.
**ONE BASENAME INDEX, BUILT ON FIRST ASK**, and the 21 loop HEADERS re-pointed at it; the
redundant `!=` guard is kept VERBATIM so every loop body is byte-identical.
**MEASURED — the deterministic half first**: the 21 rows **10.079 -> 0.457 ms** (second
instrumented draw, round 846; 0.438 of the remainder is the FIRST asker paying the one build,
the other twenty are 0.000-0.002), cross-checked against four draws of the unmodified binary
in a separate process at 9.27-12.01. **ABBA-rotated wall, one JVM per arm, 4 processes/arm x
8 draws: floor median-of-medians 157 -> 144.5 ms (-8.0%)**, means 162.5 -> 145.5.
**THE SAME RUN RE-PROVED (INC.68)'s LAW ON ITSELF**: the two unrotated `rows` processes read
whole-table sums of 52.32 -> 54.27 ms — the after arm 4% "worse" — while the 21 rows it
changed fell 22-fold, because that process simply drew slow. An unrotated process compares
rows WITHIN itself, never totals.
**THE PINS ARE NESTED-PATH VALUE PINS BECAUSE THE CORPUS CANNOT REACH THEM**: the harness
materialises no directory, so its names are FLAT and all ~13k baselines exercise the
degenerate key — an index keyed by the full path passes every one and silently stops pinning
a real project's `src/dates/temporal.ts`, a MISSING diagnostic nothing here prints.
**GATES.** Suite **16,553 / 0 / 3** (+5, exactly the new pins); `cost_gate.py` exit 0, every
counter +0.00%; `huge_methods.py --fail-over 0` clean; 8-profile grid `added=0 removed=0`,
labelled a CONTROL in its own header (no profile holds any of the 21 literals). Ablations
a1/a2/a3 redden 2/1/2 of 5; **a4 (widen the index to a suffix match) reddens NOTHING and is
recorded as a round-927 redundant-guard PAIR** — the index buys the speed, the kept guard
keeps the correctness — and only a5, which widens the index AND deletes the guard, reddens
the negative control.

**(INC.67) — READING THE PLUGIN FOUND A DEFECT NO PROFILE COULD, AND IT WAS ONE THIS
SESSION HAD WIDENED (2026-08-31).** The instrument was the CONSUMER'S SOURCE.
`xemantic/xtsc-intellij-plugin` — the first real host of the `Project` API — keeps one
`XtscSession` per `tsconfig.json`, **each owning its own single-thread executor**, so a
monorepo with N configs runs **N compiler threads in one JVM**. That is a shape no fixture,
profile or corpus baseline here produces, and the one every process-global cache implicitly
assumes away. `RealLibSnapshots.parseCache` was a plain `HashMap` mutated in place, and its
KDoc's stated mitigation (`prewarmParsedLibFiles`) covers `--workers` inside ONE compile and
says nothing about two independent sessions — and (INC.63)/(INC.65) had just added two more
such maps. All three now publish **copy-on-write behind `@Volatile`**.
**WHAT IT BUYS, PRECISELY:** a lost race still costs a RECOMPUTATION, and always did, since
`getOrPut` on a `HashMap` is not atomic either; what this removes is the CORRUPTION. **And
the duplicate is harmless for the mirror of round 471's reason** — the identity sets these
feed compare `Node`s STRUCTURALLY, so two parses of the same lib text are interchangeable to
every consumer. `ModuleResolver`'s (INC.65) memo needs none of it: per instance, per build.
**THE FIRST DRAFT OF THE PIN BROKE TWO OF CLAUDE.md's OWN RULES AND ONLY RUNNING IT SAID SO**
— it put a `Map<String, SourceFile>` inside `assert(...)`, so power-assert rendered the AST
and the failure arrived as an **`OutOfMemoryError` in the diagram builder** with the real
cause masked; and it compared two reads by IDENTITY, which assumes a quiescent process, so
it passed in isolation and failed in the full suite. **A pin's ENVIRONMENT is part of its
specification.**
**GATES.** Suite **16,542 / 0 / 3**; `cost_gate.py` exit 0; `huge_methods.py --fail-over 0`
clean; 8-profile grid `added=0 removed=0` on all eight; ablation e1 reddens exactly the
publication pin.
**WHAT ELSE THE PLUGIN REVIEW SHOWED:** it already does what this arc assumed a host would —
`updateFile` for unsaved buffers, `diagnosticsOf` for the file ON SCREEN ONLY, one thread per
project, and (INC.55)'s cancellation wired to `ProcessCanceledException`. Its `configPath`
argument is load-bearing and non-obvious: without it a malformed `tsconfig.json` shows a
clean editor over a program checked with default options. It is also the host that could make
(INC.56)'s promise — but it invalidates on `VFS_CHANGES` rather than owning the read, so the
promise is expressible and not yet made.

**(INC.65) — THE CRAWL RE-ASKED THE FILESYSTEM A QUESTION IT HAD ALREADY ANSWERED, AND THE
SESSION'S FLOOR IS 241 -> 151 / 256 -> 116 ms (2026-08-30).** The previous round named "a
PARTITION question and a HOST PROMISE" as all that was left; that was wrong within the hour,
because **`FrontEnd.CRAWL` had no split below its two elapsed-WITH-SUSPENSION CPU sums** — so
the residue between them and the WALL was unattributed, and on an application-shaped project
that residue is most of the row. Bracketing the crawl's SEQUENTIAL half
(`FrontEnd.CRAWL_RESOLVE`) read **20.6-28.6 ms of a 44-60 ms crawl wall**, ~15% of the whole
floor. **(INC.53)'s "ask what runs OUTSIDE a pass" has a sub-row-shaped twin, and this is the
third time this arc that ADDING an instrument, not reading one, is what found the cost.**
**THE FIX IS EXACT, AND READING THE FUNCTION IS WHAT SAYS SO**: `ModuleResolver.resolve`
reads `importerPath` once, to take its `dirname`, and never again, so `(importerDir,
specifier)` is not a heuristic key but THE key. Censused offline before building anything:
**4,701 resolutions over 2,351 distinct pairs — a duplication factor of exactly 2.0**, and a
codebase with shared barrels has more. Nothing to invalidate — a `ModuleResolver` is
constructed once per `build`, so the memo's lifetime IS one build; deliberately NOT
process-global, since a cross-build cache cannot see an ADDED file ((INC.48)). `null` is a
real answer and is memoized too, or the filesystem is re-probed for every unresolved
specifier — the population a project mid-edit has most of. **CRAWL_RESOLVE 24.0 -> 14.3 ms
mean; crawl wall 44-60 -> 34-44.**
**THE PIN THE DESIGN RESTS ON IS NOT A COUNT**: a memo keyed by the SPECIFIER ALONE passes
every count assertion and silently resolves `./dep` in one directory to another directory's
file — a wrong PROGRAM, which per (CFG.1) this repo has no diagnostic channel to notice.
Ablation d2 makes exactly that mistake and reddens exactly that pin.
**GATES.** Suite **16,539 / 0 / 3**; `cost_gate.py` exit 0 with **`output.programFiles` 78**
(the direct receipt that resolution still finds the same program); `huge_methods.py
--fail-over 0` clean; 8-profile `--noEmit` grid `added=0 removed=0` on all eight; `--outDir`
emit **byte-identical to the PRE-SESSION binary**, 78 files.
**THE SESSION, ONE FIXTURE AND ONE INSTRUMENT: `many-small-2400-dom` floor medians 241 -> 151
(early) and 256 -> 116 (late), -37% / -55%**, across (INC.63), (INC.64)(a)/(b) and (INC.65) —
and **UNDERSTATED**, because the box drifted ~10% slower over the session (`full` median
3,944 -> 4,335), so the floor's SHARE of a full build fell 6.1% -> 3.5%.
**AND THE NUMBER THE HOST ACTUALLY FEELS, measured through the `Project` API itself
(`scripts/incremental-cost.sh`, 2,401 files, 3 rotations):** a body edit re-answers in
**170-248 ms** (warm rotations 159-192), a comment-only edit 164-192, introducing an error
166-193 with the TS2322 correctly found, and a re-query with NO edit is **0 ms** — the memo
serves it. The narrowed build is **149-204 ms against a full build of 4,140-5,897**, i.e.
**~25-30x**, and the partition's answer agrees with the full build's row for row on every
rotation. The floor is the dominant term of that latency, which is what makes this arc the
right one for an editor host.

**SUCCESSOR (INC.66):** checker construct 38-70 (the init pass dispatch, flat — an (INC.7)
partition question), crawl WALL 34-44 (its READ half is (INC.56), the only row costing a
soundness promise), config+glob 13-29 (co-largest on some draws, NO promise attached, and
worth re-decomposing rather than assuming (INC.60) finished it). **And take the lesson
literally: before pricing any row, check it HAS a split.**

**(INC.64) — TWO ROWS PAID ON EVERY KEYSTROKE FOR WORK NOBODY READS, AND THE FLOOR IS
241 -> 146 ms OVER THE SESSION (2026-08-30).** Both found by (INC.62)'s instrument —
divide a row by its own population, refuse an impossible per-op cost.
**(a) THE CRAWL HANDED EVERY FILE TO ANOTHER THREAD TO SCHEDULE A MAP PROBE.**
`readAndScanBatch` read on `Dispatchers.IO` and then hopped to `Dispatchers.Default` for
EVERY file so a parse would never run on an IO thread — but on a warm build every parse is
a `CrawlParseCache` HIT, so the hop scheduled a ~1 us probe onto another thread, `files`
times. Reading all 2,401 files sequentially is **13-21 ms** and the flags over them
1.1-1.8, against a crawl WALL of **51-57**; priced with an ABBA-rotated synthetic arm,
**sequential 14.4 / `flatMapMerge(16)` alone 17.2 / one hop 18.5 / the shipped two hops
32.1 ms**. Only a MISS hops now; the cold crawl is untouched. `pre-parse (CPU sum)` falls
**69-81 ms -> 2.0-2.7**. **The wall could NOT resolve it** (ranges overlap, and that run's
`full` median was itself 9% slower), so the claim rests on the mechanism plus the synthetic
arm and the PIN IS A COUNT — dispatches at two program sizes: cold 5 -> 5 and 20 -> 20,
warm 0, and after one edit exactly ONE.
**(b) A `--noEmit` BUILD COMPUTED A DEPENDENCY ORDER FOR AN EMIT THAT NEVER HAPPENS —
15.0-22.6 ms, ~10% of the floor, AND IT WAS ON NO QUEUE.** `extractRelativeImports` runs
twice per file and every consumer of its product orders EMITTED output. **(INC.59)'s
finding one call deeper.** The obvious edit is wrong — a `continue` also skips
`tsFileNames.add`, which every later phase reads. **AND THE CORPUS IS A CONTROL HERE, NOT
THE GATE**: `skipEmitOutputs` is set only by `ProjectCompiler`, never by the `@noEmit`
corpus directive, so all ~13k baselines run with the branch TAKEN. The 8-profile `--noEmit`
grid (`added=0 removed=0` on all eight) and the new `-project` pins are what see it; the
EMITTING path is verified independently — an `--outDir` build of the compiler profile is
byte-identical across the two binaries, 78 files, `diff -r` clean.
**THE VALUE PIN WAS BLIND ON ITS FIRST FIXTURE AND ONLY THE ABLATION SAID SO**: named the
obvious way round (`dep` imported by `main`), dependency order and ALPHABETICAL order
coincide, so emptying the sort's edges left it green. Renamed `zdep`/`amain` so the two
orders are opposite — a pin over an ORDER needs a fixture whose expected order differs from
every order the system produces by accident.
**MEASURED (many-small-2400-dom, floor median): 241 -> 189 -> 197 -> 146 ms early and
256 -> 166 -> 152 -> 143 late** across this session's three landings, **-39% / -44%**.
**GATES.** Suite **16,535 / 0 / 3** (+7, exactly the new pins); `cost_gate.py` exit 0;
`huge_methods.py --fail-over 0` clean.
**SUCCESSOR (INC.65):** what is left is a PARTITION question (the init-block pass dispatch,
flat across ~400 passes) and a HOST PROMISE ((INC.56), the crawl's read half) — the era of
finding a stray quadratic in the front end may be over, which is itself worth recording.

**(INC.63) — EVERY KEYSTROKE RE-DERIVED THE WHOLE LIB, AND THE HALF THE STANDING REFUSAL
NAMED WAS 3% OF IT (2026-08-30).** (INC.62) asked for the floor on a `dom` fixture before
opening any row; taken, and the largest single addressable row is `parseBuiltinLib` at
**46-50 ms of a 241-256 ms floor**, stable to ~1% across draws where everything else swings
40%, and **O(1) in program size — so it is a BIGGER share the smaller the project**, i.e.
precisely what an IDE-hosted application pays per keystroke. It was invisible at
`"lib": ["es2020"]`, where the same row is 8-11 ms. **(INC.54)(c) had REFUSED it whole,
"BLOCKED on round 884's `mergedSymbols` clone-on-write"** — true of the BIND, which
measures **1.4 ms**. The other 97% is two pure functions of the SHARED parses:
`RealLibResolver.resolve`, whose `/// <reference lib=…/>` closure regexes ~3.7 MB of lib
text and which `bindRealLibs` called **TWICE** per construction (~32 ms), and the
B85.2/M2.2 decl-set walk, ~30k puts into containers keyed by **data-class AST nodes** —
round 471's deep `hashCode` at a scale the es2020 fixtures could not express (~15 ms).
**THE ARITHMETIC NAMED THE MECHANISM BEFORE ANY BUILD**: ~500 ns per `HashMap` put with a
`String` value is 20-40x impossible, which is (INC.62)'s own instrument and the fifth
defect it has found. The recorded split mis-attributed the resolve because it sits INSIDE
the `bindLibFiles` section — `bindLibFiles` **17.4 -> 1.4 ms** is that regex, not a bind.
**A REFUSAL THAT NAMES A BLOCKER MUST CHECK THE BLOCKED HALF IS WHERE THE COST IS.**
**MEASURED (many-small-2400-dom, both arms this session):** `parseBuiltinLib` 47.1 -> 1.65,
50.1 -> 1.69, 46.2 -> 1.46 ms; the decl-set walk 12.0-15.9 -> **0.01**; checker construct
99 -> 55 / 97 -> 44 / 84 -> 43; **PLAIN floor median 241 -> 189 (early) and 256 -> 166
(late)**, the early arm's ranges disjoint. **GATES.** Suite **16,528 / 0 / 3** (+5, exactly
the new pins); `cost_gate.py` exit 0 with all 20 counters +0.00% (the EXPECTED answer — a
CLI compile builds one checker, so a hoist within one construction is a no-op there);
`huge_methods.py --fail-over 0` clean; 8-profile before/after BINARY grid `added=0
removed=0` on all eight, run and LABELLED as a control (the index is a function of the lib
set alone and the eight profiles share one — the corpus, thousands of compiles in one JVM,
is what discriminates the sharing). Ablation: three arms, each reddening exactly the pin it
names, with the embedded-lib negative control green in all three.
**SUCCESSOR (INC.64):** the init-block pass dispatch (40-53 ms, FLAT — an (INC.7)-style
partition question, not a micro-optimisation) and the crawl WALL (51-57 ms, (INC.56), the
only row costing a soundness promise) are now co-largest.




**(INC.61) — THE WHOLE (INC.\*) ARC HAD BEEN MEASURING THE CHEAP `lib`, AND THE FLOOR'S
LARGEST PASS IS NOW 45x SMALLER (2026-08-30).** Re-reading the floor after (INC.60) —
(INC.59)'s own lesson, applied a second time — put **123 of the checker's 137 ms in the
init-block pass dispatch**, whose per-pass table no round had read on the many-small shape
since (INC.58) proved the tsc-profile ranking wrong by 600x. Its largest row was
`init:buildPerFileScopes`, which copies the SHARED half of a file's scope — lib globals,
script-file locals, global augmentations — into a fresh table **per file**, i.e.
`files x libGlobals` insertions. **THEN THE FIXTURE ITSELF TURNED OUT TO BE THE
UNDERSTATEMENT:** it pins `"lib": ["es2020"]` (~185 names) where an ordinary project's
unset `lib` means **`dom`** (~2,242). Copying the fixture and changing **that one line and
nothing else** takes the pass from **13.5 ms to 175.6 ms** on the same 2,401 files — 70%
of the whole floor pass table. So (INC.57)'s law that a profile's FILE SHAPE can make a
cost inexpressible holds equally for its **compilerOptions**, which CLAUDE.md had recorded
once for a library baseline ((CHK.49)) without the general conclusion being drawn.
**THE FIX IS AN OVERLAY, NOT A CACHE** — the base is the same object for every file, so it
is built once and `LayeredSymbolTable` answers `own[k] ?: base[k]`. **Its ORDER is the
load-bearing half**: three consumers iterate a per-file scope, and a `LinkedHashMap` keeps
a shadowed key's ORIGINAL position, so a shadowing local must appear there carrying the
OWN value rather than being appended — the one thing an implementation gets wrong, and
the only pin ablation c1 reddens (**the 16,523-test corpus would not have caught it
either**, since order reaches only cost counters and suggestion ordering). Mutators throw
rather than silently dropping a write. **MEASURED (dom arm, 2,401 files, both arms this
session): the pass 175.64 -> 3.90 ms (45x), init dispatch 334 -> 42, checker construct
393 -> 83, floor phase total 503 -> 200, and the PLAIN floor median 385 -> 202 ms** —
worth its own line, because (INC.60)'s 16 ms sat inside the ±40% single-draw band with the
WRONG SIGN and this one is far outside it, so here the wall corroborates the row instead
of contradicting it. **GATES.** Suite **16,523 / 0 / 3** (+4, exactly the new pins);
`cost_gate.py` exit 0 with every counter unchanged; `huge_methods.py --fail-over 0` clean;
8-profile grid `added=0 removed=0` on all eight, run deliberately because this is the
checker's name-resolution substrate. **SUCCESSOR (INC.62): re-take the floor on a `dom`
fixture before opening any of its rows, and treat that as the default shape from here.**

**(CFG.1) — A PROJECT THAT HAS EVER BEEN BUILT READ ITS OWN OUTPUT BACK IN, AND THE
CORPUS CANNOT CONTAIN A DIRECTORY (2026-08-30, found by (INC.60) on the way past).**
tsc's rule for an ABSENT `exclude` is `excludeSpecs = filter([outDir, declarationDir],
d => !!d)` (`commandLineParser.ts`); the package folders are not `exclude` entries there
at all but are pruned from every wildcard match by the matcher — which is what
`ProjectCompiler`'s own walk already does by basename. **We had the redundant half and
not the load-bearing one.** Measured against tsgo 7.0.2 on a two-file project with
`outDir: "dist"` and the artifacts a previous `--declaration` build leaves behind:
**tsgo's program is 1 file and ours was 2** — `dist` matches the default everything-include
and a `.d.ts` is a root extension — so such a project crawled, read, parsed, bound and
checked its own emitted tree **on every keystroke**, which is the incremental floor the
(INC.\*) arc has been paying down. After the fix the CLI answers `1 root, 1 in program`,
i.e. tsgo's own. An EXPLICIT `exclude` still REPLACES the default, as in tsc — pinned,
because that is the direction a "just add outDir to the defaults" implementation gets
wrong, and it is ablation arm b2. **THE DIAGNOSTIC HALF IS REAL IN tsc AND UNOBSERVABLE
HERE, WHICH IS ITSELF THE FINDING**: forced in, tsgo answers TS2451 twice for a duplicated
`declare const` and TS5011 for the moved common source directory, and **we report
neither** — so a defect that changed the PROGRAM ITSELF was invisible to every diagnostic
channel in this repo and the only observable left was a file COUNT. A value pin asserting
those codes stay absent **stayed green under the ablation that removes the whole fix** and
was deleted rather than kept (round 808). Both gaps filed as **(CHK.74)** and **(CFG.2)**.
**NOTHING HERE COULD SEE THE DEFECT EITHER**: the generated corpus materialises no
directory, and all eight dashboard profiles scope `include` to a `src` subtree under which
`dist` never matched — the grid is a CONTROL and reads `added=0 removed=0` on all eight,
as predicted before it ran. Only a `-project` fixture through `ProjectCompiler` and a
`Vfs` expresses it, the same instrument (CHK.29) needed and for the same reason.
**GATES.** Suite **16,519 / 0 / 3**; `cost_gate.py` exit 0 with every counter unchanged;
`huge_methods.py --fail-over 0` clean; 8-profile grid clean.

**(INC.60) — THE INCREMENTAL FLOOR'S THIRD ROW WAS A QUESTION ASKED TWICE PER ENTRY, AND
THE SECOND ASK COST FIVE SYSCALLS (2026-08-30).** `FrontEnd.CONFIG` — tsconfig load,
`@types` acquisition and the root-file glob — is what an editor pays on every keystroke,
and no round had separated its three pieces. Split five ways it is **~99% the glob, the
glob is ~99% its directory walk, and 60-70% of THAT is one call the walk did not need to
make**: for every entry the directory listing had just returned it went back to the
filesystem to ask "is this a directory?". tsconfig load is **0.43 ms** and `@types`
**0.01 ms** — neither was ever the row. **WHY THAT BOOLEAN COSTS 7.3-8.6 us IS IN THE
DEPENDENCY, NOT IN OUR SOURCE**: kotlinx-io 0.9.1 compiles `metadataOrNull` to
`File.exists()` + `isFile()` + `isDirectory()` + `isFile()` + `length()` — up to five
`stat` syscalls plus an allocation — on a `Path` rebuilt from the string the listing had
just produced; it is visible only by dividing the row by its population and refusing the
implied per-op cost (7.3 us is impossible for one `stat`). `Vfs.listEntries` answers the
kind WITH the listing; **its default body is literally the two calls it replaces**, so
every other `Vfs` is unchanged and correct without touching it, and `SystemVfs` overrides
it through a new `expect fun systemListEntries` (JVM: one `readdir` + one `stat` per
entry; native: the portable pair). **MEASURED, both arms this session with the same
runner: `CONFIG` 29.2-32.6 -> 11.5-16.3 ms at 2,401 files and 52.8/52.9 -> 20.7-27.1 at
4,801; per entry 9.3 -> 3.1-4.4 us, flat across both sizes** — a constant-factor win on a
linear row, with the population census (`50 dirs / 2451 entries / 2401 candidates / 2401
roots`) IDENTICAL across the change, which is the receipt that nothing was skipped to buy
it. **THE UNINSTRUMENTED FLOOR MEDIANS READ 216 BEFORE AND 222 AFTER**, i.e. the saving
sits inside the ±40% single-draw band and a wall-clock reading of this round would have
concluded the opposite of the truth — which is why the split was built before the fix.
Pinned at two layers and ablated separately: `RootGlobListingTest` (the CALL SHAPE; its
counting `Vfs` must OVERRIDE `listEntries`, or the default *is* the pre-fix sequence and
the pin is vacuous) and `SystemVfsListEntriesTest` (the JVM actual's EQUIVALENCE, whose
divergence would be silent — it includes a directory named `looks-like.ts`). a1 reddens
2 of 3 in the first and none in the second; a2 the reverse. **GATES.** Suite **16,514 /
0 / 3** (+6, exactly the new pins); `cost_gate.py` exit 0 with **every counter
unchanged**; `huge_methods.py --fail-over 0` clean. **SUCCESSOR (CFG.1), a DEFECT found
on the way**: tsc's `commandLineParser.ts:3131-3141` defaults `exclude` to
`[outDir, declarationDir]` when absent and **we implement none of it**, so a project that
has ever emitted pulls its own `dist/**/*.d.ts` back in as ROOT FILES.

**(BIND.1) — A DIAGNOSTIC THAT APPEARED AND DISAPPEARED WITH THE BYTE LENGTH OF AN
UNRELATED FILE (2026-08-30, reported from the IntelliJ plugin).** `nodeKey(pos, end)`
carries NO file identity and positions restart at 0 in every file, yet
`Binder.nodeToSymbol` and `moduleInstanceStates` were ONE map shared by every
`BinderResult` a binder produced — so two declarations at coincident offsets in DIFFERENT
files shared a slot, last-wins in bind order. **IT IS NOT A THEORETICAL HAZARD: tsc's OWN
78 SOURCES CARRY 271 KEYS WRITTEN BY TWO OR MORE *DECLARATION* NODES IN DIFFERENT FILES**
(`watchUtilities.ts`/`moduleNameResolver.ts` variable declarations, a dozen
import-specifier pairs), and an ordinary 223-file program (one source file plus `zod` and
`@types/node`) carries 109 of them plus 4,324 shared keys overall. **THE TRIGGER IS
WHITESPACE**, which is why it reads as random: `Node.end` is the end of the FOLLOWING
token, so for a file's LAST statement it is the EOF offset — the one span trailing
newlines move — and **106 of those 223 files have a last statement that appending
newlines ALONE can drive into a collision**. Reduced to four lines: two same-length files
each declaring a merged `namespace` made `buildNamespaceScope` build the scope of the
OTHER file's namespace, so the file's own exports went missing (a false TS2304) and the
foreign file's became visible (a missing one) — **both directions, against tsc 5.9.3** —
and adding ONE character to the sibling file made it vanish. The tables are now per
`bind()`; twelve checker reads holding only a `Node` go through `nodeSymbolOf` /
`moduleInstanceStateOf`, which ask the OWNING file (INV.2(a) parent chain) and treat an
owner that recorded nothing as **null** rather than scanning the others — that scan IS the
collision. **NOTHING HERE COULD HAVE SEEN IT**: it needs two files whose declarations land
on coincident offsets, which no hand-written fixture produces by accident, so
`NodeKeyCollisionTest` hands two exact texts to the pipeline and ASSERTS the collision
precondition; ablated, its two behavioural pins go red while the precondition and the
no-collision control stay green. **GATES.** Suite **16,500 / 0 / 3** (+4, exactly the new
pins); the compiler profile still reports **46 errors on 78 files**; `cost_gate.py` exit 0
with `output.errors` and `spine.nodes` UNCHANGED — `typeOfExpr.calls` +0.54% and
`narrow.memoServed` +1.55% are the 271 collisions on that profile now resolving to the
right file, re-baselined here; `huge_methods.py --fail-over 0` clean; warning-clean.

**(INC.57)+(INC.58)+(INC.59) — THE FRONT END WAS QUADRATIC IN FILE COUNT **THREE TIMES**,
AND NO PROFILE HERE COULD EXPRESS ANY OF THEM; THE PER-KEYSTROKE FLOOR OF A 2,401-FILE
PROJECT GOES **1,653 -> 279 ms (5.9x)** (2026-08-30).** Working (INC.56) — *"skip the re-read, THE LARGEST
REMAINING FRONT-END ROW"* — its own entry demanded the prize first be re-measured on "a
project with MANY SMALL files rather than tsc's 78 huge ones". That measurement **refuted
the premise and found two independent quadratics beside it**, in different subsystems.
**(INC.58), found by (INC.57)'s own successor instrument (divide the floor pass table by
file count at two sizes): `checkJsxImportResolutions` was **709.74 of a 774.65 ms floor
pass table — 92% — on a project containing NO JSX**, growing 14.6x for 4x the files.**
`resolveJsxTsxCandidate`'s path-suffix fallback walked every file of the program once per
import specifier per extension, and the pass is gated on `--jsx` being **UNSET** — maximum
work on precisely the projects that never use JSX, always answering null. Restricting it to
the `.jsx`/`.tsx` subset is EXACTLY equivalent (every non-null return is such a file; order
preserved, so the FIRST match is unchanged) and takes it to **0.30 ms, linear**.
**(INC.54)(a) had ranked that pass at 1.2 ms from the tsc profile — 600x, so a published
RANKING and not merely a price was invalidated.** A pin lesson from it: the first value pin
went RED on a WORKING binary because a relative specifier is served by an O(1) probe and
never reaches the scan — **an assertion about WHICH path served an answer is not implied by
the answer being right**; there are now two value pins, one per path. Suite **16,503 / 0 /
3**; both gates clean with every cost counter unchanged.
**AND (INC.59), THE THIRD — FOUND BY RE-READING THE FLOOR RATHER THAN TRUSTING THE
RANKING, WHICH IS THE REUSABLE HALF OF THE WHOLE SESSION.** After two rounds had
reordered it twice, `post-checker` had become the LARGEST row — 166-189 ms of a 366 ms
floor (~48%) — and **appeared in no queue item at all**. One expression:
`parsedSourceFiles.filter { it.key !in transformOrder.toSet() }`, with `.toSet()` INSIDE
the lambda, so an N-element set was rebuilt once per entry of an N-entry map — **in the
`--noEmit` path**, i.e. a build that emits nothing was spending 175 ms per keystroke
preparing an emit order it would never use. `POST_EMITPREP` **158.5-175.3 -> 1.8-2.8 ms
(~70x)**; floor 366 -> **279 ms**. Suite **16,504 / 0 / 3**, both gates clean, counters
unchanged. **VERIFIED AT MONOREPO SCALE:** a fourth size (4,801 files) reads a **428 ms
floor against 279 at 2,401 — **1.53x for 2x the files, i.e. SUB-linear**, the cleanest
evidence the quadratics are gone rather than reduced. The two rows still above 2.0x are
single-digit-to-teens ms and sit at or below the noise ((INC.52) read one floor row at
13.16 and 8.42 ms in two draws of ONE binary), so **this class is exhausted at these
sizes** — what made the three findable is that they were 14.6x / 21x / 4x-per-doubling,
one to two orders clear of that band. **SUCCESSOR (INC.60):** `config load + @types +
root glob`, 52.8 / 52.9 ms at 4,801 — two draws **0.2% apart**, the one floor row
measurable without fighting the noise, and it carries no soundness promise where
(INC.56) does.

**AND (INC.57), THE ONE THAT STARTED IT:** `extractRelativeImports` opened with
`allFiles.map { it.fileName }.toSet()` — a fresh list AND set of every program file name —
and the emit-order scan calls it **TWICE per file**: `2 x files^2` string hashes per build,
plus two sibling `parsed.files.any { … }` scans in the same loop. On generated
application-shaped projects (`scripts/gen-many-small-project.py`) the `FrontEnd.IMPORTS`
row grew **4x for 2x the files — 18.9 / 76.3 / 331.6 ms at 601 / 1201 / 2401** — which at
2,401 files is 11.5 M hashes and ~92 MB of garbage on every keystroke. **WHY ~950 ROUNDS
MISSED IT, and it is now a CLAUDE.md entry:** all eight dashboard profiles are ONE
codebase, tsc's own sources at **78 files averaging 128 KB**, where `2 x 78^2` vanishes —
**a cost that is per-FILE rather than per-BYTE is structurally inexpressible on that
shape**, and nothing here had ever been pointed at the opposite one ((INC.9)'s regime law
on a new axis: the SHAPE of the corpus). **The fix is a HOIST, not a cache** — `parsed.files`
is a `val List` on a data class, so the set is loop-invariant by construction and there is
no invalidation story; `.toSet()` is kept verbatim so the container and any iteration order
stay bit-for-bit what the per-call expression produced. IMPORTS -> **5.8 / 7.1 / 16.1 ms**,
per-file cost FLAT where it had been doubling; floor medians 165 -> 142, 409 -> 359,
**1653 -> 1035 ms**. **PINNED AS A COUNT** (`programNameSetBuilds == 1` at 10 files AND at
100) because the claim is about COMPLEXITY and only a count can state one, plus a VALUE pin
on dependency-first emit order. **ONE ABLATION ARM, TWO ANSWERS:** the count pins go RED
reading exactly **20** (`2 x files`) while the value pin stays GREEN; and all **20**
`cost_gate.py` counters are IDENTICAL between arm and HEAD, so this round is provably
counter-neutral and the gate's +0.54%/+1.55% is drift from the **60 commits** since the
baseline was recorded at (CHK.63) — deliberately NOT rebaselined, since folding sixty
commits of unattributed drift into this one would make it un-auditable. **(INC.56) is
re-ranked, not refuted as a saving: it is FOURTH** (crawl wall 25-38 ms of a 409 ms floor)
and the only one of the five costing a soundness promise. **SUCCESSOR (INC.58):** the
`Checker` init-block pass dispatch is itself super-linear — 73-91 / 204-217 / 756-810 ms at
601 / 1201 / 2401 files, ~73% of the floor. **GATES.** Suite **16,499 / 0 / 3** (+3, exactly
the new pins); `cost_gate.py` exit 0, `output.errors` 46, `spine.nodes` 856,962;
`huge_methods.py --fail-over 0` clean.

**(INC.55) — A HOST CAN NOW CANCEL A BUILD, WHICH IS THE CAPABILITY AN IntelliJ PLUGIN
NEEDS AND NO LATENCY WORK CAN REPLACE (2026-08-30).** Asked to judge the language service
as "the best support one could get inside an IntelliJ platform IDE" rather than as
"incremental", the top of the list changes: there was **ZERO cancellation** anywhere in
the compiler or the `Project` API. A build runs on the compiler's own deep-stack thread and
`Project` JOINS it, so the caller is blocked for its whole duration and cannot abandon it
from outside — while `DaemonCodeAnalyzer` restarts analysis on every write action. Without
this an editor must either block a pooled thread producing an answer it has already
discarded, delaying the next one behind it, or not run the analysis in a highlighting pass
at all. `Project.cancellation` takes a `CancellationSignal` (on the platform,
`{ indicator.isCanceled }`), polled at every `pass("…")` boundary AND every **1024 spine
nodes** — the second is what keeps a large buffer's walk (1.65 s on tsc's own `checker.ts`)
interruptible, and the hot loop's own comment refuses interleaved work, so the poll sits
behind a counter (837 volatile reads for 856,962 nodes). **IT IS AN `Error` DELIBERATELY**:
the checker, crawl and `Vfs` carry defensive `catch (Exception)` guards, and a cancellation
they could swallow would let the build continue with a missing file — silently wrong, worse
than not cancelling; `Error` is safe because the 2026-07-04 sweep left no `catch (Throwable)`
anywhere, which is pinned rather than trusted to a KDoc. **STATE SAFETY IS BY CONSTRUCTION**
— every cache assignment in `Project` happens after `build` returns, so a throw skips all of
them — pinned both at the first poll and MID-flight. **THE PIN THAT ALMOST DIDN'T
DISCRIMINATE**: the spine-poll test first compared a 3-file fixture with a 1-file one and
FAILED, because the `pass()` poll count is not constant across programs (405 vs 418) and
swamped the spine's ~12; holding file count and shape fixed and varying only SIZE reads ~417
against ~526. **GATES.** Suite **16,496 / 0 / 3** (+7, exactly the new pins); `cost_gate.py`
exit 0 with **`spine.nodes` UNCHANGED at 856,962** and `output.errors` flat at 46 — the poll
is inert when unarmed; `huge_methods.py --fail-over 0` clean. Also documented: the threading
rule is a CONFINEMENT rule (Symbol/Type ids are thread-local, so two threads on one
`Project` corrupt an id space with no diagnostic), and the GraalVM/AOT/CRaC artifact levers
do NOT apply to a plugin running in-process on the IDE's own JVM.


**(INC.53) — THE INCREMENTAL FLOOR'S LARGEST BLOCK WAS NEVER IN A PASS, AND ~950 ROUNDS OF
INSTRUMENTS COULD NOT SEE IT (2026-08-29).** The floor is what an editor pays per keystroke,
and 32-44 ms of its 63-72 ms is "checker construct + getDiagnostics". Split for the first
time: **`getDiagnostics()` is 2-3 MICROSECONDS**, so the whole phase is the CONSTRUCTOR — and
~20 ms of it is the class's **~494 property initializers**, a constant that reads the same on
a 63 ms floor build and a 5.2 s full one. That is 0.4% of a full compile, which is exactly why
no round noticed, and **~30% of every language-service query**. **A FIELD INITIALIZER IS NOT A
`pass("…")`**, so it contributes to no `--passTiming` row, no `cost_gate.py` counter and no
diagnostic: the whole pass-gating arc ((INC.7)/(INC.20)/(INC.21), 189 walkers) swept loop
headers and structurally could not reach it. **FOUR initializers are essentially all of it**
(the other ~490 are 0.2-1.2 ms between them — 494 allocations cannot be 20 ms, which is what
said a handful were doing whole-program work). Three were whole-program indices with exactly
ONE read site each and now build on FIRST ASK: `localTypeAliasIndex` becomes a per-FILE index
over that file's own frozen statements, in the same DFS order and first-wins per name, the
other two `lazy(NONE)`. **Floor field region, four draws each side: 18.6 / 25.4 / 29.6 / 30.2
ms -> 8.1 / 12.6 / 8.4 / 11.2**, with all three rows reading 0.00 ms and 0 files on a floor
build; even a FULL build needs only **69 of 78** files' alias index. Claimed as a WORK
REDUCTION, not a millisecond ((INC.52)'s law — the same binary reads 13.16 and 8.42 ms for one
row in two draws), so `EagerIndexCensus` counts the population. **THE FOURTH IS REFUSED WITH
ITS PRICE**: `parseBuiltinLib` splits three ways with no dominant part (binds 3.2-5.3 ms, decl
walk 1.9-2.8, resolution + 45 `mergeSymbolTable` 3.1-5.3) — and the round-471 hypothesis that
the data-class-keyed node sets dominate is MEASURED WRONG. Its two larger parts are
per-checker by requirement (the checker mutates lib symbols), so round 884's `mergedSymbols`
clone-on-write is the named unblocker. **GATES.** Suite **16,489 / 0 / 3** (+4, exactly the
new pins); `cost_gate.py` exit 0 with `output.errors` flat at 46; `huge_methods.py
--fail-over 0` clean, and `Checker.<init>` shrank **5,538 -> 5,464** bytecodes, buying back
(JIT.1)(d) headroom.


**(INC.52) — THE INCREMENTAL FLOOR'S DEAREST PASS STOPS WALKING EVERY FILE'S SYMBOL
TABLE, AND ITS PRICE IS BELOW WHAT THIS REPO CAN MEASURE (2026-08-29).** With project
diagnostics incremental ((INC.46)) and restart-proof ((INC.48)), what an editor pays per
keystroke is the FLOOR. Decomposed: **68 ms**, of which the checker is **42 ms (67%)** with
nothing to check, and the largest pass in both draws is `init:computeAllEnumValues` — whose
second loop visited EVERY file's `locals` and recursed through every namespace's `exports`
to find the program's enums. `BinderResult.bindsEnum` answers that from the bind that
already happened: an identity, not an approximation, since `bindEnumDeclaration` is the one
site minting a conventional enum symbol and `enumValues` is ID-keyed. **MEASURED AS A
POPULATION, from ONE binary with the verify arm as the "before": 12,871 top-level symbol
visits -> 8,676 (-32.6%)**, plus every namespace recursion beneath the **45 of 78** files
skipped, with `localsSkipViolations = 0` over a non-empty skipped set. **AND THE TIME IS NOT
RESOLVABLE, WHICH IS THE PART WORTH KEEPING**: the row that motivated the round read 13.16
ms in one draw and **8.42 ms in the next draw of the same binary**; after the change, 7.27
and 9.66; the floor wall reads 68 before and 74 after with draws spanning 57-86. So it is
landed as a WORK REDUCTION with a control and no millisecond is claimed — a single-draw
per-pass row on a 68 ms floor is not a measurement, and that is now a CLAUDE.md entry
because the next agent will read the same table and reach for the same row. **GATES.** Suite
**16,485 / 0 / 3** (+2, exactly the new pins); `cost_gate.py` exit 0; `huge_methods.py
--fail-over 0` clean; warning-clean.

**(INC.48) — THE INCREMENTAL STATE OUTLIVES THE PROCESS, AND A RESTART IS **60x**
(2026-08-29).** (INC.46) made project-wide diagnostics incremental within a process and
every bit of that state died with it: an IDE restart, a plugin reload or a daemon recycle
paid a whole-program build for a tree nobody had touched. `Project.saveState()` encodes
what has to survive — export signatures, escapes, the program's file list, that build's
diagnostics and a content hash per input — and `restoreState()` adopts it, so the next
process starts at the (INC.46) gate instead of at a rebuild. **MEASURED on tsc's own 78
sources, every arm asserted to agree ROW FOR ROW**: warm, **5,855 ms -> 94 ms (62x)**
clean and 259 ms (23x) with a file changed on disk; in a **COLD process — which is what a
restart actually is — 9,625-9,844 ms -> 155-175 ms (~60x)**, the snapshot being **47 KB**
for a 78-file project. The cold column is the one that matters and it is nearly as good as
the warm one, which was not obvious: an IDE restart pays the JIT ramp, and (INC.49)
attributed ~18 s of a 23 s first query to exactly that — but the ramp barely touches a path
that never checks the whole program. **IT WRITES NO FILE**: `encode`/`decode` answer and
take a string, so the host decides where its caches live; the CLI's `--incremental`
(`tsconfig.xtsbuildinfo`, INV.7(d3)) remains the convention for callers who want the other
one. **EVERY PART OF THE CLAIM IS CHECKED, because skipping any of it is a stale answer**:
the compiler build id (never a `.dirty`/`unknown` one — two dirty trees share an id without
sharing behaviour), the config path, a CONTENT hash per file (never mtime — round 871), and
the `.json` INPUTS as well as the sources, since a changed tsconfig or a `package.json`
whose `type` decides a module format makes every stored row suspect rather than one file's.
**AND THE STALENESS CASE NO HASH CAN SEE HAS ITS OWN MECHANISM**: a file ADDED while the
process was down is in no stored hash and no stored list, so a restored state is not
trusted until a build has re-crawled and found the same program — even a clean project runs
the gate once, with an EMPTY partition. Ablated, the naive "trust the snapshot" version
reddens exactly two pins and nothing else. **GATES.** Suite **16,483 / 0 / 3** (+13,
exactly the new pins); `cost_gate.py` exit 0; `huge_methods.py --fail-over 0` clean;
warning-clean.

**(INC.50)/(INC.51) — THE STABILITY RATE IS A PROPERTY OF THE CODEBASE, NOT OF LAYERING;
AND ONE LINE OF ORDINARY LIBRARY CODE ESCAPED THE WHOLE FILE (2026-08-29).** (INC.47) left
one question: is 67% a property of the mechanism or of tsc's own sources? Measured on three
corpora of 40 real commits each, whole trees per side: tsc `src/compiler` **67%**,
`cronstrue` **50%**, `marked` **72%** — the two libraries BRACKET tsc, so layered code is
**not materially above** it and (INC.50)'s per-hop closure is refused by its own stated
threshold. `cronstrue` is the CONTROL arm and was chosen as one: it is the only library
outside the corpus where this checker agrees with tsgo 7.0.2 exactly (0 errors both sides)
and has no dependencies, because a library we report errors on has types degraded to `any`
and a degraded type is artificially STABLE. The transferable statement is that the rate
tracks **what a codebase's commits touch** — cronstrue's edits are to the ~44 locale
classes that ARE its exported surface (its MOVED cases are real signature changes such as
`commaOnlyOnX0()` -> `commaOnlyOnX0(s?: string)`), where tsc's are inside function bodies.
AND **(INC.51)**: pointing the mechanism at real code found a defect in ONE run.
`marked.ts` escaped because of `export { useExtension as use }` — the walk collected the
name an IMPORTER sees and looked it up in `locals`, which the file keys by the name it
DECLARES, so every renaming export missed, read as "an exported name with no file-level
symbol", and escaped the WHOLE file: every edit to it rebuilt the whole program forever and
the export's type was never hashed. tsc's own 78 sources never use the shape, so all eight
dashboard profiles are structurally blind to it. Fixed, with three pins — one of which
records a DELIBERATE conservatism: renaming the LOCAL still moves the hash, because
dropping declaration names would make two structurally identical classes hash equal and a
class with a `private` member is nominally typed. **AND THE (INC.47) LAW REPEATED ON A
SECOND CORPUS: removing an escape buys NOTHING** — marked's escapes went 1 -> 0 with its
rate unchanged at 72%, exactly as `types.ts` left tsc's at 67%. On both, the file that
could not be summarised was also one whose surface genuinely moved. **GATES.** Suite
**16,470 / 0 / 3** (+4, exactly the (INC.51) pins); `cost_gate.py` exit 0;
`huge_methods.py --fail-over 0` clean; warning-clean.

**(INC.47) — THE EXPORT FINGERPRINT IS A CANONICAL SERIALIZATION, THE ESCAPE CLASS IS
EMPTY, AND THE 87.5% CEILING IT WAS AIMED AT DID NOT EXIST (2026-08-29).** The walk no
longer recurses: every type reachable from a file's exports is DISCOVERED once, in a
deterministic order, and named by its discovery INDEX, so a reference — forward, back or
self — costs one lookup and cycles need no special case. There is no strongly-connected
component left to hash, which is why this is simpler than the Tarjan machinery the queue
named and strictly stronger. **MEASURED whole-program on tsc's own 78 sources**:
`types.ts` **122.52 ms for ONE export and a node-budget STOP -> 6.21 ms for 871 exports**;
whole-program **131 -> 16 ms**; structural nodes **2,019,605 -> 38,502**; budget stops
1 -> **0**; escapes `[types.ts]` -> **[]**; exports hashed 2,137 -> **3,007**; both
controls held (identical-text stability **78/78**, narrowed-vs-whole agreement **24/24**).
**AND THE PRIZE IS REFUTED ON BOTH ARMS RATHER THAN ARGUED**: the 40-commit stability
corpus reads **27/40 = 67% before AND after, with every one of the 40 per-case verdicts
identical**. (INC.46)(2)'s ceiling came from its runner printing *"N moved only because a
touched file ESCAPES"* over the code `if (escaped)` — which counts every case that
TOUCHED an escaping file — while its own detail lines showed four other movers in the same
case; re-derived, exactly ONE of the 8 qualified, so the ceiling was **70%**, and after
this even that one moves, because `types.ts` is a file of exported declarations and an
edit to it really does move the surface. **IT LANDS ON SOUNDNESS, NOT ON THE RATE**: the
old walk bounded its recursion with a DEPTH CAP of 24 and hashed everything below it as
one constant — a MISSED invalidation, i.e. a stale diagnostic, live since (INC.46)(3)
began answering project-wide diagnostics from the previous build. Both new pins are RED
against the pre-(INC.47) binary and green after, one for the mechanism (pinned on the node
COUNTER, not a time) and one for the soundness half. **The escape class being empty is a
claim about OTHER codebases** — a single-file library with a large cyclic type graph is
ordinary in real TypeScript and would have forced a whole-program rebuild on every
keystroke forever. **GATES.** Suite **16,466 / 0 / 3** (+2, exactly the new pins);
`cost_gate.py` exit 0; `huge_methods.py --fail-over 0` clean; build warning-clean.
**SUCCESSOR: (INC.50)** — the 67% is not improvable on this corpus by any mechanism, so
the live question is the rate on ordinary LAYERED code (`knip`, `jsonrepair`, `cronstrue`).

**(INC.46)(3) — PROJECT-WIDE DIAGNOSTICS ARE INCREMENTAL, AND WITH (INC.44)/(INC.45)
NOTHING AN EDITOR ASKS IS WHOLE-PROGRAM BY DEFAULT ANY MORE (2026-08-29).**
`Project.diagnostics()` no longer rebuilds after every edit: when the edit moved no
exported signature it answers the previous build's rows with the edited files' rows
replaced, from ONE narrowed build — **108-113 ms against 4,864-5,096 ms, a factor of 45**
on a served edit. **GRADED AS A DIFFERENTIAL THAT NEEDS NO BASELINE**: over (INC.46)(2)'s
40 real tsc commits, edited THROUGH THE OVERLAY as an editor's unsaved buffers, the answer
must equal a project opened fresh on the edited text — **EQUIVALENT, 40 agreed of 40**,
with `served=27` as the control that keeps the agreement from being vacuous (a run whose
`served` is 0 is REFUSED — round 790: a verifier reads 0 both when the skip is sound and
when the instrument is dead). The 27 is exactly step (2)'s 67%, two instruments
corroborating. **Five preconditions, each CHECKED rather than argued** and each with its
own pin; the pin set is a PAIR by construction (a body-only edit must be served and a
signature edit must not — an implementation that always serves passes the first, one that
never serves passes the second), and each is pinned twice, on the ANSWER and on the
BUILD COUNT, because without the cost family every pin passes against the old
always-rebuild behaviour. **TWO DEFECTS THE PINS FOUND THAT REVIEW DID NOT**: the
incremental answer was NOT RETAINED (`cached` cannot hold it — that field is a
whole-program `Result` — so a second `diagnostics()` with no intervening edit rebuilt, and
an editor asks twice constantly); and **the build-counting unit every cost pin in this repo
uses is BLIND for an edited config** — an overlaid file is served from the overlay and
never reaches the backing `Vfs`, so the config's read count stops moving and "did this
rebuild" reads 0 for a build that certainly happened. Two pre-existing control pins
legitimately moved 1 -> 2 builds and were updated to state the new cost model rather than
papered over: adding an export IS a signature change, and the gate being wrong costs the
narrowed build plus the rebuild. **GATES.** Suite **16,464 / 0 / 3** (+11, exactly the new
pins); `cost_gate.py` exit 0; `huge_methods.py --fail-over 0` clean; build warning-clean.
**THE SUCCESSOR IS SCC-AWARE HASHING**: `types.ts` still escapes on an in-file
strongly-connected component that no budget closes (measured at 2 M and 12 M nodes) and it
accounts for 8 of the 13 fallbacks, so Tarjan-per-component is the one lever between the
measured **67%** floor and the **87.5%** ceiling.

**(INC.46)(2) — THE STABILITY RATE IS **67%** OVER 40 REAL tsc COMMITS, AND ONE TEXT SCAN
WAS WORTH 35 POINTS OF IT (2026-08-29).** Step (2) is the one the queue said could refuse the
whole mechanism ("under ~70% the 45x is diluted to nothing"). `scripts/inc46-stability.sh`
fetches its OWN blob-filtered depth-3000 clone of microsoft/TypeScript — never
`typescript-repo`, which is a depth-1 shallow clone AND a build-pinned input — and replays
**40 real no-merge commits** touching `src/compiler`, materialising the WHOLE tree at the
parent against the whole tree at the commit (a file from another era beside a tree from this
one resolves against symbols that may not exist). **27 of 40 stable = 67%**, and **8 of the
13 that moved did so ONLY because `types.ts` escapes** — so the band is a **67% floor and an
87.5% ceiling** with one named lever between them. **THE FIRST READING WAS 32% AND WAS AN
ARTIFACT OF MY OWN CODE**: `declaresGlobalSurface` scanned whole source for
`export as namespace` — a construct with NO AST NODE in this parser — and `checker.ts` says
those words **twice, both in `//` comments**; since it is the file tsc's history edits most,
that single false positive cost **35 percentage points** and presented as a plausible
refusal rather than as a defect. **No fixture would have found it** — nobody writes
`// export as namespace foo` into a hand-written test — and the edit corpus found it in one
run. **`types.ts`'s escape is STRUCTURAL and was measured rather than assumed**: it is a
node-budget stop at 2,000,000 nodes (129.6 ms) AND still a stop at **12,000,000 (741 ms,
whole budget burned)**, because the file-boundary cut cannot help INSIDE a file and
`types.ts` declares ~874 mutually recursive interfaces in one. The lever is **SCC-aware
hashing**, deliberately not attempted here; the budget stays bounded and the file is recorded
in `ExportSignatures.whole` — a full rebuild, never a stale diagnostic. **GATES.** Suite
**16,453 / 0 / 3** (+13 over 16,440: the 12 step-(1) pins plus the comment-mention pin this
defect earned); `cost_gate.py` exit 0; `huge_methods.py --fail-over 0` clean. Step (3),
wiring the invalidation into `Project.diagnostics()`, is now the only item left.

**(INC.46)(1) — THE EXPORTED-SIGNATURE FINGERPRINT IS BUILT AND MEASURED, AND ITS WALK
HAD TO BE FOUND BY MEASUREMENT THREE TIMES (2026-08-29).** The queue's step-(1) threshold
("single-digit ms on `types.ts`'s 874 exports, or stop") is met with room: **136 ms
whole-program** on a 5,215 ms rebuild, and **0 ms on 23 of 24 narrowed builds** — a
narrowed build fingerprints only its partition, so the per-EDIT cost of the gate is under
a millisecond against the 108-113 ms build it rides on. **The two controls that decide
feasibility are not cost figures**: two builds of identical text agree **78/78** (the
id-freedom claim — a hash carrying a `Type.id` passes every structural test and then
invalidates everything, always), and a narrowed build's fingerprint equals the
whole-program one **24/24** (the CONVERGENCE claim — the baseline comes from a
whole-program build and the edit's answer from a narrowed one, so a systematic
disagreement means every first edit falls back forever). **THE WALK'S SHAPE WAS THE REAL
QUESTION.** A path-only cycle guard is EXPONENTIAL in DAG width — 159 s inside one build,
found by an external `jcmd Thread.print` — and closed-subtree memoization is still not
enough, because tsc's resolved-type graph is one giant SCC (`Node.parent: Node` plus
hundreds of mutually recursive interfaces): **6 of 78 files unfinished inside a
2,000,000-node budget, among them `checker.ts`, `binder.ts` and `emitter.ts`**. What works
is CUTTING at the file boundary — a type declared elsewhere is unchanged by construction
while only this file is edited, so it is keyed by its declaration's `(fileName, pos, end)`
and not descended into. That took the arm from 719 ms / 6 escapes / **4-of-24** agreement
to **136 ms / 2 escapes / 24-of-24**. **AND THE QUEUE CENSUSED THE WRONG QUANTITY**: cost
tracks the transitive type CLOSURE, not the export COUNT, and the two are near-inversely
related — `utilities.ts`'s 692 exports are 1.6 ms where `types.ts`, which declares the
SCC, is 129.6 ms. Steps (2) (the stability RATE, which needs a deepened TypeScript clone)
and (3) (wiring the invalidation) are deliberately NOT in this commit — the order of work
is measure-first and (2) can still refuse the whole thing. **GATES.** Suite **16,452 / 0 /
3** (+12 over 16,440, exactly the new pins); `cost_gate.py` exit 0 with a largest move of
**+0.08%** (the profile's standing residual — the expected answer, since the walk is off
by default and a strict no-op then); `huge_methods.py --fail-over 0` **0 over limit**.

**(INC.46) QUEUED AND PRICED — AND MEASURING IT REFUTED THE QUEUE'S OWN EXPLANATION OF WHY
PROJECT-WIDE DIAGNOSTICS CANNOT BE INCREMENTAL (2026-08-29, owner's idea).** The standing
story, from round 772 and (INC.35), is that a dependency closure buys nothing on tsc because
its sources are `export *` barrels. **The barrels were never the cause.** A SYMBOL-level use
graph — which is free, since `capturedDefinitions` already records span -> declaration —
re-checks **100% of the program's characters at the median edit, the same as the file-level
graph** (94.9% of imported names placed, so not an under-count): those files genuinely use
symbols from most other files and the relation is transitive. **What collapses it is asking
whether an edit moved any EXPORTED SIGNATURE**, not which symbols a file uses: a body-only
edit moves none, so no dependent re-checks and the cost is one narrowed build — **108-113 ms
against 4,864-5,096 ms, a factor of 45**, already measured by (INC.31)/(INC.37). **91.6% of
the program's characters are inside brace bodies** (a proxy for edit POSITION, optimistic
because an inferred return type leaks, pessimistic because it counts `interface` bodies).
**This needs no corpus and no owner call** — a signature hash pays on DENSE code too, so
unlike (INC.35) it is gradable on the dashboard profile. **THE SHARP HAZARD IS RECORDED**:
`typeToString` is the wrong hash source in BOTH directions — `aliasDisplayMap` is a
first-wins global so it is not a pure function of the type (spurious invalidation), and B58.1
renders `errorType` as `"any"` so a degraded resolution hashes as a genuine `any` (a MISSED
invalidation, silently). The hash must be an id-free structural fingerprint; (INC.16) already
built one to copy. Cost input censused: **3,398 exported declarations, mean 44/file, max 874
in `types.ts`**; its runtime is the first thing to measure, with a stated refusal threshold.
**No code landed — the entry is the deliverable.**

**(INC.45) — `renameAt` IS NARROWED TOO, AND ITS ABLATION FOUND A BLIND PIN SET
(2026-08-29).** The rename sweep took (INC.44)'s spelling closure and hands the resulting
file set to the compiler as a check partition. Two things make it more than a copy.
**Both of a rename's builds must share ONE partition** — `verifyRename` compares
diagnostics as a `(file, code)` MULTISET, which a partition filters, so a narrowed
"before" against a whole-program "after" reports every unswept row as removed; the
soundness argument for narrowing it at all is that a rename edits only files the plan
names and an unedited file's meaning can change only through a name it imports, which it
must then SPELL. **And the population is the closure UNION every occurrence of the NEW
name**, because `verifyRename`'s third check — the only one that can see a rename which
compiles and means something else — scans for occurrences already spelling it and would
otherwise pass VACUOUSLY. **THE ABLATION'S FINDING**: arm b2 (the after-build forgets the
partition) reddened **NOTHING**, because every fixture was a CLEAN program and both bags
were empty whatever either build walked — one file carrying a diagnostic and spelling
neither name takes it to **2 RED**. Arm b3 (never narrow) is **UNDISCRIMINATED and
recorded as such**: the change is equivalence-preserving by construction, so what stands
in its place is one pin on the shipped DEFAULT with no mode install in it ((INC.16)'s
lesson). **MEASURED**: an ordinary rename is **~1.0-1.3 s against ~15 s (12-14.5x)** —
`emitFiles` 2 of 78 files at 1,304 ms, `transformNodes` 3 of 78 at 1,025,
`checkSourceElement` 1 of 78 (but that file is `checker.ts`) at 4,725.
**GATES.** Suite **16,440 / 0 / 3** (+18 over the session's 16,422 baseline, exactly the new pins); rename differential **EQUIVALENT** — 8 carets,
7 narrowed, 6 producing an APPLICABLE plan, 1,691 edits compared plan for plan, 0
diverged, 56.5 s against 114.2 s; three ablation arms b1 **1 RED** / b2 **2 RED** / b3
undiscriminated with a reason.

**(INC.44) — `referencesAt` IS NARROWED BY *SPELLING*, AND THE DOC CLAIM THAT IT "CANNOT
BE" CONFUSED THE CLAIM WITH THE EVIDENCE (2026-08-29).** `docs/language-service.md` said in
three places that find-references and rename "are NOT narrowed and will not be: their claim
is about every file, so there is nothing to narrow to". The claim is program-wide; the
EVIDENCE is not — an occurrence can only be an answer if it SPELLS a name the symbol is
reachable by. `referencesAt` now selects that population before typing it and `captureIn`'s
partition, which has always been DERIVED from the request's spans, narrows the check with
it: **no new mechanism**. On tsc's own 78 sources an ordinary name costs **510–553 ms
against 8.8–11.1 s (17–18x)**, `checker.ts`-only names 1,940 ms (4.8x), and the worst
realistic case (`SyntaxKind`, 9,827 hits in 49 files) still wins at 4,904 ms; a repeat is
free (119–150 ms) because the narrow path reaches a memo the whole-program one never did.
The closure over `import { p as q }` / `export { p as q }` terminates because both spellings
are tokens of the file DECLARING the alias; everything else — a default export, a default
import's local, `export =`, `import x = require(…)`, a namespace binding, the spelling
`default` — REFUSES and runs the old sweep. **The near-miss worth remembering**: the obvious
substring file filter is not exact, because `StringLiteralNode.text` is the COOKED value and
`\a` is an identity escape, so `o["pl\ain"]` names `plain` — a file may be skipped only if
it holds no backslash at all (29 of 78 do, carrying 78.2% of the characters). **The
ablation's honest half**: arm a3 reddens only the REFUSAL pins, so the escape guards are
CONSERVATISM — kept because tsc answers **6** references where we answer **2** on a
`export { renamed as default }` edge, which is now pinned so the day it closes is loud.
**GATES.** Suite **16,434 / 0 / 3** (+12 from a re-verified 16,422 baseline, exactly the new pins); reference differential **EQUIVALENT** — 60 carets drawn by stride over all 381,775 occurrences, **59 of them actually narrowed** (the control), **0 diverged**, 12,248 hits compared element for element; mean partition **17.5 of 78 files**, aggregate 182.0 s narrowed against 561.6 s whole-program (**3.09x** on a draw that lands proportional to occurrence count, i.e. on the hottest names);
four ablation arms, four DISTINCT red sets; `cost_gate.py` / `huge_methods.py` are CONTROLS here (no `-core` source
touched) and both are green: `cost_gate.py` exit 0 with `output.errors` **46** and a largest move of **+0.08%**
(`globals.lookups`/`globals.misses` — the profile is unchanged, this is its standing
run-to-run residual), `huge_methods.py --fail-over 0` clean.


**(INC.85) — A WAVE THAT CANNOT BLOCK IS DRAINED WITHOUT THE 16-WAY MERGE, AND THE GATE IS
DEFAULTED OFF (2026-09-01).** (INC.84) measured the crawl's `flatMapMerge` pipeline at
**0.58/0.60/0.60x effective parallelism** on the arm an IntelliJ-class host runs — 16 workers
producing LESS CPU than their own wall, because every read is served from memory and every
parse from the content cache, so there is nothing to overlap. The same pipeline runs at
**7.5-8.9x** for a host that does not promise the filesystem, which is the control that makes
this a statement about the WAVE rather than about concurrency.
`readAndScanBatch` now classifies per path on the caller's thread — resident content AND a
content-cache hit is built directly, anything else defers to the old pipeline moved verbatim —
with both halves feeding the UNCHANGED single-threaded fold, so `CrawlParseCache.store`,
`retainRead` and the counters still run once each and off the flow (round 825).
**`readAndScanBatch` WALL 8.48/9.82/12.28 -> 5.84/5.32/4.73 ms; pipeline 6.63/8.13/9.61 ->
0.81/0.80/0.67.** The receipt is DETERMINISTIC and no wall number is quoted: a warm trusted
keystroke reads **2400 resident / 1 piped** (the merge is entered for the edited file alone)
against **0 / 2401** cold and untrusting, while four rotated batches of the query wall gave
sign-flipping deltas on the untouched CONTROL arm too — (INC.72)'s +-20 ms concurrent term.
**THE ROUND WAS FIRST REPORTED AS A REFUSAL, AND WHAT CHANGED THE VERDICT WAS REMOVING A COST
RATHER THAN RE-MEASURING.** The first design made every host pay a per-path probe (~0.6-0.9 ms
per wave) to serve a regime only some are in. `Vfs.hasResidentContent()` — a whole-store
question **defaulted `false`**, asked ONCE per wave — means every `Vfs` that has not opted in,
`SystemVfs` and so the entire shipped CLI and daemon path, performs **not one probe**.
**AND THE GATE'S SHAPE IS STRUCTURAL, NOT A THRESHOLD:** `OverlayVfs` answers from `retained`
alone and deliberately NOT from overlaid buffers, because `contents` is O(open editors) while
a wave is O(program files) — that disjunct would spend O(program) probes to fast-drain a
handful and can never pay at any project size. Dropping it took the last non-winning regime
from 0.6-0.9 ms to **99-111 NANOseconds**.
**EIGHT ABLATION ARMS, AND a5 IS THE ONE WORTH READING:** it was **DEAD on its first pass**
because the fixture's edit dropped retention, so the shape that matters — an unsaved buffer,
resident with new bytes over a stale cached tree — did not exist. Rebuilt, it reddens exactly
the staleness pin. **Without it this change could have shipped serving the PREVIOUS
KEYSTROKE'S parse tree, with no counter, order pin or corpus baseline noticing.**
**GATES.** Suite **16,645 / 0 / 3**; `cost_gate.py` exit 0, every counter unchanged;
`huge_methods.py --fail-over 0` clean; compiler profile **46**; `--frontEnd` census lines
byte-identical before and after.

**(INC.82) — THE IMPORTER'S DIRECTORY WAS RE-DERIVED PER SPECIFIER, AND THE ISOLATED PROBE
OVER-READ ITS OWN PRIZE BY 3x (2026-08-31).** `ModuleResolver.resolve` read `importerPath`
for nothing but its `dirname` — the (INC.65) KDoc says so in as many words — then joined it
with the specifier into a fresh `String` and probed the memo with it TWICE. The crawl knows
that directory once per FILE and asked once per SPECIFIER: **4,701 asks over 2,401 files**.
**PRICED BEFORE BUILDING** with the probe that already decomposes the row: of 1,314 ns per
specifier, `dirnameOnly` 96 and `keyOnly` 174 — **1.27 ms of a 6.18 ms row**.
**LANDED:** `resolveFrom(specifier, importerDir)` is the entry point and `resolve` a wrapper,
which makes the contract structural rather than a comment; the memo is nested (`dir -> spec`)
so the outer probe hashes a cached-hash instance the caller already holds and the inner one
only the short specifier; a memoized `null` is an identity sentinel, so a served answer costs
one probe; and the crawl hoists both the `dirname` and the per-file resolution map, the map
staying LAZY so a file whose every import is unresolved still contributes no entry.
**AND THE PART WORTH READING IS THE OVER-READ.** In the BUILD, over two class dirs differing
only in these files and rotated across processes, `FERESOLVE` reads **4771/5143/4102 ->
4677/4707/3954 us** — after wins 3/3 batches in both directions, ranges overlapping, delta
**~0.15-0.44 ms, not 1.27**. `hits x mean-call-cost` one layer in from where it is usually
quoted: 96 and 174 ns are what those operations cost **in a tight loop over 4,701 reps**,
inputs in L1 and the branch perfectly predicted. **An isolated per-operation probe prices an
UPPER BOUND on a removal, never the removal.**
**SO THE RECEIPT IS THE COUNT, EXACT TO THE UNIT:** `path normalize: 9577 -> 7277`, i.e.
precisely `4,701 - 2,401`, with the glob, join and resolution-question censuses IDENTICAL
across the arms — the receipt that the same work is done. The floor wall moved 103 -> 93 ms
3/3 and is **not claimed**: (INC.72)'s +-20 ms concurrent term is ten times the effect.
**ABLATION:** three arms, three distinct red sets. a2's pin needed a whole build —
`moduleResolutions` is not on the `Result` and reaches the checker as (CHK.30)'s
bare-specifier answer, so a map written under the wrong importer is a LOST diagnostic.
**ALSO: `docs/language-service.md` § 14 now EXISTS.** (INC.75)(b) claimed it documented
`cancellation`; the § 0 table's rows for `cancellation`, `saveState()` and `restoreState()`
all pointed at a section that was never written. It now carries the signatures, the poll
points, the cancelled-build contract, the exact `null`/`false` conditions, the added-file
limit, and the JVM edge a host hits: the cancellation is an `Error` by design, so
`Future.get` wraps it in `ExecutionException` and a generic failure branch would log a
warning per cancelled keystroke.
**GATES.** Suite **16,629 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean; compiler profile **46** diagnostics, unchanged.

**(INC.86)(b) ANSWERED:** the init block is 418 rows, `rowsTo50pct=5`, tail of 363 rows worth
**1.03 ms between them** — no plateau left.
**GATES.** Suite **16,653 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%, `output.errors`
46; `huge_methods.py --fail-over 0` clean; **two-binary 8-profile grid added=0 removed=0 on all
eight**, its before-arm control verified non-blind.

**(P18.14) — A REAL FALSE NEGATIVE BEHIND A DISPLAY ITEM, 17,236 → 17,259 / 0 / 3 (2026-09-04).**
(PARITY.1) was queued as two FORM divergences and its most valuable half turned out to be
MEANING: `canUseTypeEngine` refused a `Type.Union` source against an OBJECT target wholesale,
so `const t: { x: number } = u` with `u: "a" | "b"` — and `string | number`, `string | undefined`,
against a named interface, an array, a function type, at declaration, assignment AND return —
reported NOTHING where tsgo 7.0.2 and pristine 6.0.3 both report TS2322 (argument and
object-literal-member positions already reported, so a probe written either way reads as
working). Fixed by lifting the two primitive-vs-object rules the gate already had to a
primitive-only UNION, with the suppression-only narrowing predicates given the matching
anonymous-object arm. The literal-union display collapse landed at declaration and assignment
(`getBaseTypeOfLiteralType` maps over a union where `getWidenedLiteralType` has no union arm,
plus tsc's `never` guard, which also fixed a pre-existing single-literal divergence); argument,
return and object-literal-member displays are separate emitters and stay open. **The qualified
`import("…").Enum` display is REFUSED with a measurement that corrects the item**: the rule is
enum-only, the claim that 103 baselines are served by hard-coded pins is false (they are served
by rounds 745-749's real same-string-retry mechanism), and only 10 of 2,881 active baselines
carry the rendering — while `typeToString` here is a pure `(Type) -> String` with 718 downstream
sites, so the node-builder context tsc uses is a redesign, not a fix. 23 pins, seven arms each
with its own red set (one first read 0 RED and was replaced by a pin probing what the guard
actually protects); **no baseline moved and no `LogicalParityDivergence` was needed**, which the
round predicted by enumerating the 4 literal-union-source and 18 `never`-target baselines. New
CLAUDE.md entry: the 8-profile grid is structurally BLIND to every type-display change (all its
rows are `Cannot find name …`).

**(P18.23) — CONST ASSERTIONS STOP BEING `any` (STAGE 1 OF (CHK.93)), AND THE NAME-RESOLUTION SEAM IS CENSUSED FOR (INV.0) STEP 4, 17,516 → 17,573 / 0 / 3 (2026-09-05).**
**(CHK.93) stage 1 LANDED.** Every object, array and enum-member `as const` was `any` (the `const`
type reference fell off the resolution ladder to `errorType`); now the assertion answers its operand's
const-context type, object members keep their literals with the context computed once, a const array
is a frozen tuple — which needed the relation rule tuples always needed (tuple → `Array<T>` by
elements, removing a pre-existing false TS2740 on every declared tuple against an array) and an
existence-only tuple-inherits-Array answer the grid demanded (`isArray(diag) ? diag.slice(1)` in tsc's
own utilities.ts) — TS1355 is tsc's `isValidConstAssertionArgument` in both spellings, and the
prerequisite literal-property-write false positive (`mo.v = "a"` against `v: "a"` reported `'string'`)
is closed first. 16 of 30 recon rows byte-identical to pristine; the readonly half (TS2540, TS4104,
`push` on a const tuple, `readonly [1, 2]` display) stays queued as stage 2. 57 pins, 12 arms all
RED; core 16,084/0/3, corpus 8,837/0, `cost_gate.py` exit 0 with no rebaseline, grid 8×`added=0
removed=0`. **(INV.0) step 4 censused by read-only recon**: the name-resolution surface partitioned
into a two-commit `NameResolver` extraction (~1,300 code lines), an ambient row of three reads and
no writes, eleven invariants each mapped to its pin classes — and tsgo's closure-struct
`NameResolver` identified as exactly the shape § 10 forbids.

**(P18.24) — CONST ASSERTIONS BECOME READ-ONLY (STAGE 2 OF (CHK.93), THE ITEM CLOSED), AND THE TUPLE-MEMBER AND BODY-LOCAL-ARGUMENT RESIDUES ARE MEASURED INTO (CHK.94)/(CHK.95), 17,573 → 17,611 / 0 / 3 (2026-09-05).**
**(CHK.93) stage 2 LANDED.** A const-context object's members are read-only (TS2540, TS2704 instead of a
pre-existing TS2790 double, `{ readonly v: "a"; }` display), a const array is a readonly tuple unless its
contextual type has a mutable array-like constituent (tsc's `isMutableArrayLikeType`, measured), the
`readonly [T, U]` type operator stops being a no-op, members of a readonly tuple fall to `ReadonlyArray`
(`push` → TS2339), TS4104 replaces TS2740 for readonly→mutable at every position — as pristine's TS2345
chain at an argument, where tsgo prints a bare TS4104 (the arc's third reference divergence) — and the
declared-type twin `declare const rt: readonly [1, 2]` went from 2 of 7 rows to 7 of 7. The grid found
the round's most important fix: B378's guard install put the guard's OWN predicate type into the
then-branch, "FP-safe" only while `readonly T[]` related to `T[]` — tsc's own `core.ts:1685` became a false
TS2345 — so it now installs the declared constituent that relates (tsc's `getNarrowedType`). The pin
walker `checkReadonlyTupleElaboration` is kept (20/22 codes reproduced under PassLab). 38 pins, 15 arms
RED; core 16,122/0/3, corpus 8,837/0, `cost_gate.py` exit 0, grid 8×`added=0 removed=0`. Read-only recon
measured 220 cells into two items: every `Array` member READ on a tuple is `any` (an interned
`Array<union>` base on the miss path is the seam; the corpus has zero baselines that can see it), and the
argument gate is silent for EVERY body-local scalar / `let` / annotated-primitive local, not only strings.

**(P18.25) — A TUPLE'S ARRAY MEMBERS GET TYPES AND ITS CALLS GET CHECKED, AND DESTRUCTURED BINDINGS ARE MEASURED INTO (CHK.96), 17,611 → 17,664 / 0 / 3 (2026-09-05).**
**(CHK.94) LANDED.** Every `Array` member READ on a tuple was `any` and every call through one unchecked;
an interned `Array<union>` / `ReadonlyArray<union>` base (a rest slot INDEXED as tsc does, optional slots
joining `undefined`) is consulted on the miss path only, and the call, overload, argument and callback
paths followed for free — except two PRE-EXISTING array defects the typed members exposed and fixed: an
array-literal argument against a literal-union element was a false TS2769 (`(1 | 2)[].concat([1])` on
HEAD), and a union receiver's `.map` became a false TS2349 because tsc combines union signatures and
refuses only generic-vs-generic non-identical ones — the grid found the same ours-only TS2349 on tsc's own
`fourslashImpl.ts` and the refinement closes it. 128 measured cells went 46 → 83 matching tsgo with 0
regressions; 53 pins, 11 arms (one provably unobservable, recorded); core 16,175/0/3, corpus 8,837/0,
`cost_gate.py` exit 0, grid 7×`added=0 removed=0` + harness `removed=1`. Read-only recon measured
destructured bindings over ~120 cells: an OBJECT pattern's members are already typed at every reader but
the argument and TS2367 ones, every ARRAY pattern / default / nested / rest / contextual pattern parameter
is `any` everywhere — queued as the staged (CHK.96), correcting (CHK.46)'s entry.

**(P18.26) — A BODY-LOCAL SCALAR CONST REACHES THE ARGUMENT GATE, AND UNION CALLEES ARE MEASURED INTO (CHK.97), 17,664 → 17,717 / 0 / 3 (2026-09-06).**
**(CHK.95) LANDED.** In every body context `const s = "a"; takeB(s)` was silent for every non-enum scalar
initializer, every `let` and every annotated primitive local (48 of 72 cells) while file level reported: a
SYNTACTIC scalar predicate in the ccet pre-scan (a const keeps the literal, a let widens — a counter arm
shows why it must be syntactic: admitting the conditional moves `typeOfExpr.calls`) and a primitive-
annotation arm at leave time, with a per-body name set so an annotated duplicate reads `any`. The mutable
boolean is REFUSED after measurement (tsc's `boolean` is `true | false` and narrows by assignment; ours
is an intrinsic, and the file-level twin is a pre-existing false positive). Two post-spine emitters that
double-emitted rows the gate now owns were deduped. 89 of 125 cells match both references, 30 named
residues; 53 pins, 7 arms; core 16,228/0, corpus 8,837/0, `cost_gate.py` exit 0 (`globals.lookups`
−0.31%), grid 8×`added=0 removed=0`. Read-only recon measured union callees (26 rows, five extracted
pristine fixtures): the call RESULT is `any` for EVERY union callee, three wrong-row families beside the
silences, and TS2349 where tsc says TS2722 — queued as (CHK.97) around tsc's two-pass `getUnionSignatures`
plus its array fallback.

**(P18.27) — DESTRUCTURED BINDINGS GET THEIR TYPES (STAGE 1 OF (CHK.96)), AND CONTEXTUAL CALLBACK TYPING IS RE-MEASURED INTO (CHK.98), 17,717 → 17,792 / 0 / 3 (2026-09-06).**
**(CHK.96) stage 1 LANDED.** Every ARRAY / tuple pattern, default, nested pattern and pattern PARAMETER was
`any` at every reader and an object pattern `any` at the argument and TS2367 readers; a pure
`bindingElementType` (tsc's `getBindingElementTypeFromParentType`: slots, optional → `| undefined`, rest as a
sliced MUTABLE tuple, defaults joined with the subtype drop, unions lifted per constituent except tsc's
narrowed-symbol precondition the corpus found) now feeds seven plug points including the symbol half. The
grid forced FOUR root fixes on tsc's own sources: an optional property's `T["k"]` carries `undefined`,
mapped `-?` strips it, an uninferrable type-guard TP narrows to its constraint (a pre-existing false
positive), TP-carrying fn members are refused. 0 ours-only rows over ~120 cells; 75 pins, 9 arms with two
round-927 pairs recorded; core 16,303/0, corpus 8,837/0, `cost_gate.py` rebaselined with attribution
(+2.32% `typeOfExpr.calls`, 80% the ccet leave-time typing of 305 pattern initializers), grid
8×`added=0 removed=0`. Read-only recon measured contextual callback typing over 99 rows: (CHK.39) already
types a callback's parameters for the assignability reader and hover — CLAUDE.md's (CHK.30) entry was
STALE and is corrected — and the residue is the ccet ARGUMENT reader plus three property-access sources,
queued as (CHK.98) with two narrowing gaps (98b/98c) behind its union gate.

**(P18.28) — AN OBJECT REST, ITERABLES, CONTEXTUAL PATTERN PARAMETERS AND THE DISCRIMINANT CARRY (STAGE 2 OF (CHK.96), THE ITEM CLOSED), AND THE THREE DEFECTS THE GATES FOUND, 17,792 → 17,874 / 0 / 3 (2026-09-06).**
**(CHK.96) stage 2 LANDED, and the round INHERITED an interrupted session's tree** — the
implementation was written and compiled with the gates unrun and one pin red, so the round's own
work is the gating and the three defects it turned up. An object REST reads tsc's `getRestType`
(members copied MUTABLE, `private`/`protected`/`#private` dropped always and methods/accessors
only under a CLASS, a generic source refusing); `[Symbol.iterator]` sources read through tsc's
fast and slow legs, which needed the instantiator to rebuild a TUPLE **as** a tuple — without it
`Map<K, V>`'s `MapIterator<[K, V]>` had no readable slots, silently; contextual pattern
parameters reach six readers; the pattern's implied contextual type widens every array-literal
element; and the destructured-discriminant carry implements `getNarrowedTypeOfSymbol` INVERTED
(each sibling's narrowed type filters the parent's constituents, since this checker narrows by
path strings). **The three defects: a blind pin** whose `none { "=>" }` guard contradicted its own
expected message (the compiler was right); **a corpus double-emission** — `destructuringUnspreadableIntoRest`
50 → 72 rows, `--passTiming` naming `checkObjectRestUnspreadableAccess` 44 against `checkSpine`
22, deduped in the walker because it runs SECOND (ablated: 1 of 82 pins RED); and **a grid
regression**, one ours-only TS2322 on the three profiles carrying `services.ts:3264`, where a
CONDITIONAL of array literals gets no implied contextual tuple — both reconstructions measured
wrong (the second needs flow-narrowed branch elements, which `getTypeOfExpression` never gives),
so the shape now refuses, as HEAD does, and is queued as (CHK.107). Corpus 8,837/0, core
16,385/0, `cost_gate.py` exit 0 with no rebaseline (`typeOfExpr.calls` +0.89%), `huge_methods.py`
exit 0, grid 8×`added=0 removed=0`.

**(P18.29) — UNION CALLEES ARE COMBINED, NOT SILENTLY `any` (STAGE 1 OF (CHK.97)), AND FOUR OF THE ITEM'S OWN PREDICTIONS ARE MEASURED WRONG, 17,874 → 17,916 / 0 / 3 (2026-09-06).**
**(CHK.97) stage 1 LANDED.** The call RESULT was `any` for EVERY union callee and the argument
positions were judged off a CONCATENATED signature list; one home,
`combineUnionSignatures` (tsc's `getUnionSignatures`), now does PASS 1 and PASS 2 with the
helpers mirrored 1:1 and is memoized by the union's `Type.id` (exact, because INV.5(a) interns
unions by member-id list), feeding both return readers, a new construct arm (which kills the
false TS2351 on every union `new`) and the ccet hand-off to the ordinary argument gate, with the
nullish check moved ABOVE the union branch. Against pristine 6.0.3 the matrix goes **23 rows with
6 WRONG → 58 of 73 matched with ZERO ours-only**, every one of the 15 misses attributed. **Four of
the item's predictions are refuted by building it**: "the too-few TS2554 comes free" is FALSE
(TS2554 fires only for a function-DECLARATION callee, and a union callee is a variable by
construction — a pre-existing gap this round records), arm a2's predicted victims are answered
upstream by PASS 1, arm a3 as specified is undiscriminated, and arm a8 names stage-2 work. Three
re-homings were forced by measurement, one of which closed a PRE-EXISTING false positive
(`declare function h(...xs: 1[]); h(1)` was TS2345 on HEAD), and four hand-written pins turned
out to be pinning OUR divergences (3 × `TS2349 → TS2722` for a nullish callee, and a silence
assertion where both references report `'never'`) — all corrected to VALUE pins after
re-verifying against tsgo 7.0.2 and pristine directly. No double emission (all rows from
`checkSpine`; the walker ORDER is what buys it). 42 pins, **12 arms, every one discriminating**.
Corpus 8,837/0, `cost_gate.py` exit 0 with no rebaseline — combining every union callee costs
~0.00% on its own — `huge_methods.py` exit 0 (`ccetUnionCalleeChecks` SHRANK ~100 lines), grid
8×`added=0 removed=0`. Stage 2 stays open: the array fallback, `getCallSignaturesOfType`'s union
arm, callback contextual typing, the optional-call result and `this` params/TS2684.

**(P18.30) — THE ARRAY FALLBACK, INTERSECTED CALLBACK SIGNATURES AND THE OPTIONAL-CALL RESULT (STAGE 2 OF (CHK.97)), AND A `noImplicitAny` GATE THAT WAS INERT ON EVERY STRICT PROJECT, 17,916 → 17,932 / 0 / 3 (2026-09-06).**
**(CHK.97) stage 2 LANDED** — three of five deliverables: tsc's ARRAY FALLBACK (checker.ts:15949),
derived from the RECEIVER because a method type here has no parent symbol, which costs one extra
CALLABLE gate a signature-list route would not need; tsc's `getIntersectedSignatures` (:33085),
one `intersection` flag on `combineUnionParameters` wired through `callableSignaturesForCtx` and
`cpaComputeArgCtxTypes`, so a callback ARGUMENT of a union callee is typed and graded with a
wrong-typed USE rather than the TS7006 silence; and the OPTIONAL-call result. Matrix 55 → 61 of 73
pristine rows, ours-only unchanged at 3 (display only). **Three more of the item's claims are
measured wrong**: retiring the `≥2` suppression does NOT make r09 report (a SECOND suppression,
the `differ` branch's non-generic silence, sits above it); the `noImplicitAny` gate written as tsc
spells it is **inert on every `strict` project** — the field is not implied by `strict` here and
the repo-wide spelling is the disjunction (29 sites), caught only because the build measured ZERO
row movement, which a green suite/corpus/grid all look like too; and our `identityRelation` is
LENIENT for a function type nested in a signature parameter, which is what keeps the fallback out
of tuple-union `.filter`. **The round's own instrument hazard**: the implementation subagent ran
its grid in the same `build/bench` directory this session had snapshotted its BEFORE arm into, so
the captures were overwritten with the post-change binary — the grid script's `sha256sum` refusal
fired, and the arm was rebuilt from `git show HEAD:` into a directory the subagent never saw. 16
pins, 11 arms (9 discriminating; a2/a10 recorded as redundant guards on every reachable shape and
kept as tsc's own rules). Corpus 8,837/0, `cost_gate.py` exit 0 with no rebaseline,
`huge_methods.py` exit 0, grid 8×`added=0 removed=0`.

**(P18.31) — CONTEXTUAL PARAMETER TYPES REACH THE ARGUMENT AND PROPERTY-ACCESS READERS ((CHK.98)(a)/(b)/(c)), AND A SCRIPTED SPLICE THAT SILENTLY DELETED THREE PINS, 17,932 → 17,963 / 0 / 3 (2026-09-06).**
**(CHK.98)(a)/(b)/(c) LANDED.** The ccet ARGUMENT reader (TWO `anyType` sites, not the one the
item named — and `ccetObjlitMemberFrame` additionally had to COPY the `localTypes` map it was
SHARING with the enclosing frame), the PROPERTY-ACCESS readers (`cpaAnnotationCtx` at four sites
plus an objlit-METHOD arm, gated to a NON-union contextual parameter type), and the pull's exact
arms (Conditional, As/Satisfies, `=`, `this`, REST, the array-literal edge). Matrix **99 → 125
rows matched against both references, ours-only 7 → 2**. Three defects the item did not name
landed with it, including **(CHK.98c)** as (b)'s prerequisite — which is PRE-EXISTING on HEAD and
fires for a plain function-declaration parameter. **Five of the item's claims are measured wrong**,
the sharpest being its `typeNode.bypassed` **+31%** memo precondition: measured **+0.22%** with no
memo built, so the memo is not one. 31 new pins plus **five flipped from an absence assertion to a
value one** — two the item predicted, and two residues of EARLIER rounds found only because the
full suite ran; every flip re-verified here against tsgo 7.0.2 AND pristine 6.0.3 before being
accepted. **The round's instrument hazard is a THIRD way an arm reads a false zero**: a scripted
splice silently DELETED three pins, so two arms read `0 RED` while `git diff --shortstat` and a
per-arm `cmp` both passed — they test the source under ablation, never the pin POPULATION.
**`cost_gate.py` FAILED and was rebaselined WITH ATTRIBUTION** in the same commit: an arm disabling
only (a) reads exit 0, and (a) owns 83% of the `narrow.memoServed` rise (+2.29%) and 43% of
`mapped.hits` (+2.22%) — both cache-HIT counters rising faster than their own populations, because
a contextually-typed parameter becomes a NARROWABLE REFERENCE where an `any` one was not;
`spine.nodes` +0.00%, `output.errors` 46 → 46. Corpus 8,837/0, `huge_methods.py` exit 0, grid
8×`added=0 removed=0` with the BEFORE arm rebuilt in a directory no subagent wrote to. 19 arms
(a12 recorded as a redundant guard).

**(P18.32) — A WEAK GUARD TARGET NARROWS ((CHK.98b), WHOSE DIAGNOSIS WAS WRONG), AND (CHK.98)(b)'s UNION GATE LIFTS, 17,963 → 17,981 / 0 / 3 (2026-09-06).**
**(CHK.98b) CLOSED — and the round's main finding is that the item misdiagnosed it.** Queued as
"NESTED-TERNARY predicate narrowing", it is neither about ternaries nor about the property-access
family: a plain `if`, a single ternary, `&&` and the nested ternary all fail identically, and the
guard narrows correctly the moment its TARGET declares one REQUIRED member. The axis is the
target's OPTIONALITY, and the mechanism is a round-480 ASYMMETRY — that round gave
`missingVsOptionalProvesNotSubtype` to the NEGATIVE guard filter and never to the POSITIVE one, so
the negative branch was right all along. The positive arm now mirrors tsc's
`getNarrowedType(assumeTrue)`, with a vetoed member falling to the existing narrow-DOWN arm so the
two together are tsc's `mapType`. **(CHK.98)(b)'s union gate LIFTED, with a three-binary receipt
rather than a green grid**: grid 8×`added=0 removed=0` and knip 51 → 51 byte-identical, while a
third binary (gate lifted, 98b reverted) reads **52** — the extra row being exactly the one the
item named, which is what proves the gate's population is live and the green is not vacuous; the 8
profiles carry ~26 such annotations in total and are closer to a control. **The item's knip number
49 is stale** (a REBUILT parent reads 51; 49 was the pre-(CHK.98) recon commit) — a recorded
baseline is a claim about a BUILD, not a commit, now shown for a library baseline too. 18 net pins,
2 arms (8 RED / 3 RED) with two negative controls recorded as non-discriminating BY CONSTRUCTION
rather than counted, and every arm carrying a pin-COUNT assertion after (P18.31)'s deleted-pins
hazard. Corpus 8,837/0, **`cost_gate.py` exit 0 at +0.00% on every counter**, `huge_methods.py`
exit 0. Residue pinned as a KNOWN GAP: a nullish union contextual parameter types correctly but the
property-access reader emits no TS18048 — a false NEGATIVE, which is why the lift is safe.

**(P18.33) — AN EXPORTED DESTRUCTURING IS AN EXPORT ((CHK.99)), AND FOUR OF THE ITEM'S SIX SITES WERE WRONG, 17,981 → 18,021 / 0 / 3 (2026-09-06).**
**(CHK.99) CLOSED.** `bindingPatternNames` — the checker-side mirror of
`Binder.bindVariableDeclarationName`, i.e. tsc's rule that every leaf of an exported pattern is an
export — at four name-set sites. An `Identifier` answers itself, so it is a DROP-IN, which is the
arithmetic reason `cost_gate.py` reads **+0.00% on every counter**. The item's fixture goes 26 → 16
rows against 17 in both references: ten false TS2305 and a false TS2339 on `typeof NS` gone, and a
barrel import GAINED a true TS2345 it had been losing. **Four of the item's six sites were wrong**:
the `typeof NS` line it names is a different walker's set (the real site it never names), the
`nsImportTargets` site is an unrelated decl-emit walker, **`varDecls` must NOT be changed** — its
consumer reads `d.type`, so a registered leaf mistypes an ANNOTATED exported pattern, proven by an
arm and inert on every non-collision fixture (the first guard pin was blind and had to spell the
collision out) — and "`import * as A; A.p` is `any`" is a general namespace-import gap equally true
of a plain `export const`, so it cannot discriminate the fix. **Two lost diagnostics the item did
not mention also close**: TS2308 and TS2484. The harness split is the point: population is **0 on
all eight profiles** (re-derived here, so the grid is a CONTROL rather than coverage) and the class
is structurally invisible to the corpus, so the 40 pins run across THREE harnesses — 11 direct
`Parser`, 12 `diagnose()`, and 17 in `-project` through `ProjectCompiler` + a `Vfs` for the
cross-file half — with every positive pin a VALUE pin. 9 arms all discriminating; 8 controls
recorded as non-discriminating rather than counted. Corpus 8,837/0, `-project` 848 → 865/0,
`huge_methods.py` exit 0, grid 8×`added=0 removed=0`.

**(P18.34) — A NAMESPACE-QUALIFIED ENUM MEMBER NARROWS ((CHK.100)), AND THE GRID'S *ADDED* ROW WAS THE POSITIVE CONTROL, 18,021 → 18,043 / 0 / 3 (2026-09-06).**
**(CHK.100) CLOSED.** `resolveEnumSymbolForQualifiedPath` is a dotted-path container descent
mirroring tsc's `resolveEntityName`, with a SINGLE segment delegated verbatim to the existing
resolver and the answer canonicalized exactly once — so round 425's split-key hazard is discharged
by construction rather than by care; `enumPathDeref` hops an `import * as ns` to the target module
FILE, because a namespace import's members live in the file's locals and behind its `export *`
barrels. 11 CLI fixtures against both references go **45 rows → 10**, all ten reference-agreeing.
**Three of the item's claims are measured wrong**: "both sides fail" (only the RHS readers do — arm
a2 reverting the annotation arm reads **0 RED over 22 pins**; kept for key-space agreement and
recorded as a measured redundant guard), "expect REMOVED rows on the profiles" (0 removed on all
eight — the 23 sites carry no diagnostic, so the class is LOST PRECISION there and the grid is a
control), and the four named readers are incomplete (a fifth owned an ours-only TS2366).
**The grid's ADDED row was the positive control**: with only the enum change in, three profiles
gained a row BECAUSE the discriminant began to narrow — which exposed a PRE-EXISTING root defect,
`checkPropertyAccessAssignment` having no flow narrowing at all where the var-decl, assignment and
return readers have had a suppression-only leg since rounds 410/438/456. Fixed at the root and
verified here against both references (the narrowed-to-`A` write is silent; the `"b"` twin still
reports), taking the grid to `added=0` everywhere. 22 pins, 7 arms, no round-927 pair (two legs of
one descent have DISJOINT red sets); two pins were repaired mid-round after reading 0 RED — BLIND,
not redundant. Corpus 8,837/0, `cost_gate.py` exit 0 at **+0.00% on every counter**,
`huge_methods.py` exit 0, grid 8×`added=0 removed=0`. The (P18.27) unblock is only HALF true: a
mutable `for-of` head now narrows, but a readonly head is still silent — and so is
`readonly string[]` with no enum anywhere, so that gap is independent of the discriminant.

**(P18.35) — THREE SHIPPED NARROWING DEFECTS CLOSE, AND (CHK.101)'s OWN DELIVERABLE IS BUILT, MEASURED CORRECT AND *REFUSED* ON GRID EVIDENCE, 18,043 → 18,076 / 0 / 3 (2026-09-07).**
**The item's deliverable (a) is REFUSED, and that is the round's most valuable output.** The
nullish-constituent emission was built, measured **correct on 20 of 20 reference rows**, and removed
entirely: the grid reads **+19 to +21 ours-only rows on EVERY profile** for its reader half. Correct
on every fixture and unlandable on real code is exactly what the 8-profile grid exists to catch, and
the refusal is verified by VALUE (the item's fixture reads parent = 2, final = 2). **What landed
instead are three OTHER shipped narrowing defects, two of them ours-only FALSE POSITIVES** —
verified here against both references: a loose `==`/`!=` against `null` now tests BOTH nullish
values (tsc's `TypeFacts.EQUndefinedOrNull`), a DEFAULTED parameter no longer sees `undefined` in
its body (`getNonUndefinedType`), and a logical assignment's VALUE is the surviving LHS ∪ RHS rather
than the whole declared LHS. **Four of the item's claims are measured wrong**: "the same pair
reports at a declaration and a return" held only because its fixture used a UNION target, which
`canUseTypeEngine` admits by its own line — for a non-union object target every position is silent
(20 reference rows against 2 of ours), so the stated seam covers under half the defect; its named
grid risk already narrows on the parent; and sub-part (c) is broader than stated. Only the mechanism
was right. **The four narrowing gaps that must close before (a) is re-attempted are now recorded
with reduced fixtures** — they were invisible until now precisely because (a)'s diagnostic is what
would expose them. 33 pins, 7 arms (one a measured redundant guard with a mechanism, two recorded
BLIND because their observing mechanism IS (a)); two of the round's own pins were wrong rather than
the compiler, expecting `'1'`/`'2'` where all three compilers print `'number'`. Corpus 8,837/0,
at-risk coverage asserted from the result XMLs (150 classes, 0 missing), `cost_gate.py` exit 0,
`huge_methods.py` exit 0, grid 8×`added=0 removed=0`.

**(P18.36) — A GENERIC INTERFACE'S FN-TYPED MEMBER STOPS BEING FROZEN AT FIRST TOUCH ((CHK.102)), AND THE FREEZER IS INV.5(c)'s CACHE, 18,076 → 18,098 / 0 / 3 (2026-09-07).**
**(CHK.102) CLOSED.** `interface Box<T> { f: (x: T) => T }` was ONE object shared by every
instantiation and frozen at first touch, so `Box<string>.f` read `(x: number) => number` after a
`Box<number>` was touched first — a false TS2345, a LOST one and the wrong display, **in both
declaration orders**. The two `substituteOuterTypeArgs*` helpers are now NON-MUTATING (round 465's
"mints FRESH objects, never mutates", applied to the one member of the family that kept the
in-place form), with a signature's own type parameters CLONED when the outer mapper moves a
constraint or default. **The item's mechanism is wrong in four places, found with an identity PROBE
rather than by reading**: the freezer is INV.5(c)'s context-keyed `mappedNodeTypes` cache and NOT
the `resolveReferenceMembers` seam the item named (never touched) — two instantiations of one
interface produce an identical fingerprint because the target's own `Type.TypeParam` is in scope
both times, so **17.39's KDoc precondition "rawType is always freshly allocated" has been FALSE
since INV.5(c) added a second cache below the bypass**; there is no `PropertySignature` node kind in
this parser at all; the grid is a control not because the shape is unreached but because a frozen
member never DECIDES a diagnostic there (the compiler profile makes **6,383 minting calls over 46
nodes asked with ≥2 distinct argument vectors**, the most-exposed being `lib.es5.d.ts`'s `Array<T>`
at 30); and two freezes go unnamed — a method's fn-typed PARAMETER, and an inner generic
signature's constraint, which survives a fix to the object half. 22 pins, every claim in BOTH
orders (one order is green on the frozen binary — the item's own trap, cleared); 5 arms, with a2/a3
a round-927 pair and **a5 exposing two BLIND pins** (discarding the mint yields the raw `T`, one
absence replacing another), recorded rather than claimed. Corpus 8,837/0, at-risk set 151/151
classes with coverage asserted from the XMLs, `cost_gate.py` exit 0 — minting ~6,400 fresh objects
per compile moves **no counter**, the repo's own "an allocation count is not a cost" on a fourth
instrument — `huge_methods.py` exit 0, grid 8×`added=0 removed=0`. No ambient read added to
`TypeInstantiator`; ledger row 3 stands at four.

**(P18.37) — AN ARRAY LITERAL WITH A SPREAD GETS A REAL TYPE ((CHK.103) STAGE 1), AND THE ROUND-471 ARM IT WOKE WAS THE REGRESSION, 18,098 → 18,110 / 0 / 3 (2026-09-07, RECOVERED ROUND).**
**(CHK.103) stage 1 landed; stage 2 re-queued with its 6 residual rows named.** Every array literal
carrying a `...spread` was `any`, so `f([...xs])` went unchecked at every position and the var-decl
string layer printed `Type 'array'`. A spread now contributes its ITERATED element type (array-like
→ element type, tuple → element UNION, `string` → `string`, else the iteration type; REFUSED for a
union / intersection / type parameter / `any` — stated false negatives, never a guess), a const
context inlines a fixed tuple's slots, and the elaborator reports a spread at its own node. On the
item's population fixture ours goes **1 → 6 of the reference's 12 rows, all six byte-identical to
pristine `typescript@6.0.3`** — including the `readonly [number, string]` row where **tsgo 7.0.2
diverges** (TS4104) and pristine outranks it.
**THE ROUND WAS RECOVERED FROM AN INTERRUPTED SESSION, AND THAT IS THE FINDING.** The work sat
uncommitted with a `Checker.class` NEWER than its source and a complete-looking `grid-after`, so it
read as finished; the capture was in fact 4 minutes OLDER than the session's last fix, and re-running
the grid on the final binary produced a **byte-identical** result — that last fix was INERT and the
round as left would have shipped **a false TS2322 on 3 of the 8 profiles**. Cause: giving the shape a
type at all made ROUND 471's literal-preserving arm reachable for the first time, and **that arm bails
on any spread of its own** — it was built for tsc's own `invalidOperationsInPartialSemanticMode`
(`services.ts:~1560`, no spread) and its sibling `invalidOperationsInSyntacticMode` (`:1607`) is the
same shape WITH one, so the literal fell back to a path that widens each element to its base
primitive and unioned a bare `string` into `readonly (keyof LanguageService)[]`. The dead session had
patched the wrong layer; the landed fix is the spread contribution inside round 471's arm. Four
20-second probes settled what three readings had not. 12 pins, all read from pristine; grid
**8 × added=0 removed=0**; `cost_gate.py` exit 0 (`typeOfExpr.calls` **+0.07%** = 445 spread
expressions no longer skipped, rebaselined in this commit); `huge_methods.py --fail-over 0` exit 0.

<!-- archived 2026-09-10 by (P18.60) -->
**(P18.55) — (INV.0) STEP 4 IS COMPLETE: `NameResolver.kt` IS 2,284 LINES AND `Checker.kt` LOST 1,941, 18,484 / 0 / 3 (2026-09-09).**
4b-ii moved the namespace / heritage / type-name group (19 functions, 2 fields) VERBATIM, closing
the seam; ledger row 6. `Checker.kt` 198,781 → **198,022**; `Checker.<init>` 5,656 → 5,634.
**THE AMBIENT ROW GETS BETTER AS A FAMILY COMPLETES, AND THIS ROUND MEASURED IT**: 4b-ii ABSORBS
four of the reads rows 4 and 5 recorded, because those functions now live inside the collaborator
— net **26 checker reads, NO writes, for 2,284 lines**. So an intermediate row's ambient count is
the WORST that family will look, and the ledger should be read by FAMILY, not by row.
**The constructor-input trap bit a third time and cost nothing**, because (P18.54) put the rule in
the queue item: six more fields turned out to be declared below the construction site, where an
input captures null, so all became ambient reads. **A third JVM-name-mangling mechanism turned up**
— `SymbolFlags` is a VALUE class, so `lookupInEnclosingNamespaces` compiles as
`lookupInEnclosingNamespaces-bd7vo6s`; like `internal`'s `$<module>` suffix it makes a receipt grep
read zero rows and look like "never compiled". **RECEIPTS COVER THE WHOLE OF STEP 4**: all 420
per-pass `--passTiming` rows and the 46 diagnostics byte-identical between PRE-4a pristine and the
finished seam — one receipt for all 1,941 moved lines; PrintInlining ZERO refusals on every hop with
both STABLE standing hot sites identical to pristine; ab-interleaved +0.15% B-wins-4/6
NOISE-DOMINATED; cost_gate and huge_methods exit 0; warning-clean. Verbatim proved twice by two
methods for the third round running. Next per the design's Stage-0 order: the RELATER out of
`checkTypeRelatedTo` into the `TypeRelationCache.kt` seam row 2 already named.

<!-- archived 2026-09-10 by (P18.61) -->
**(P18.56) — (INV.0) STEP 5: THE RELATER IS `Relater.kt`, AND THE 6,390-LINE REGION IS ONLY 1,256 LINES OF ALGORITHM, 18,489 / 0 / 3 (2026-09-09).**
`Checker.kt` **198,022 → 196,797**; `Relater.kt` 1,446; ledger row 7. **The census the item asked
for changed the shape of the work**: the seven named entry points span a ~6,390-line region and the
relater is **1,256 lines in five contiguous spans** — the rest is ELABORATION
(`getPropertyElaborationChain` 546, `getFunctionMismatchElaborationWorker` 407,
`checkExcessProperties` 231), which answers *what do we SAY about the failure*, a different seam.
**Deliberately NOT split** where 4b was: one mutually-recursive algorithm, so a mid-recursion cut
puts a `Checker` hop inside the hottest recursion for no verification benefit. **Delegation surface
SIX, not 363** — `checkTypeRelatedTo`'s 329 call sites are byte-unchanged behind a one-line hop, and
eight moved functions have no caller left. **THE MOST REUSABLE FINDING IS A RECEIPT FIX: the
`--passTiming` pass table is printed in DESCENDING WALL-TIME order**, so its row ORDER is a timing
artefact and the first comparison read **804 diff lines between two identical binaries**; sorted, and
with every ms-bearing or time-BUCKETED line dropped, **488 deterministic lines are byte-identical
against a REBUILT pristine HEAD** (all 420 per-pass rows, the 46 diagnostics, the emissions census,
the counters, globals lookups) with a same-binary control. **A second: `cost_gate.py`'s ±2% column is
not a statement about the change** — six counters read non-zero and pristine HEAD reads exactly the
same deltas, i.e. the recorded baseline is stale; grade a split against a rebuilt pristine and treat
the gate as the control. **A third: `isTypeAssignableTo` joins `getTypeOfExpression` as a
`PrintInlining` site that is NOT stable across processes** (proved by running arm A twice), leaving
`checkArgumentsAgainstSignature` the only one of § 10's three that can separate arms. The split
IMPROVED inlining for the third row running (`checkTypeRelatedTo`: 344 `too large` refusals → a hop
with ZERO). ab-interleaved **−11 ms (−0.04%) B-wins-3/6 NOISE-DOMINATED**; `new Relater` appears at
exactly ONE bytecode in the module. Verbatim proved twice by two methods for the fourth round
running. **5 pins, 5 arms, and TWO PINS MEASURED UNDISCRIMINATED AND RENAMED rather than claimed**: a
leak detector cannot work, because the `Relation` cache is probed ABOVE the comparison stack and
answers an identical pair before a stale key is consulted; and the `isDeeplyNested` bail is not the
only bound — disabling it entirely still terminates, since `maxRelationDepth` is a second ceiling.
cost_gate exit 0, huge_methods exit 0 (836 classes), warning-clean.

**(P18.65) — (INV.0) STEP 10d: THE ORACLE'S `resolveName` IS ANSWERED, AND THE ROW THAT OPENS LATER STAYS REFUSED, 18,546 / 0 / 3 (2026-09-10).**
**THE REFUSAL WAS CORRECTED BEFORE IT WAS CLOSED, AND THAT IS WHY THIS WAS ONE ROUND AND NOT
A STAGE**: until (P18.61) it blamed the retained tables for lacking the block-scoped
population, and step 9 measured that they HOLD it — so what was missing was a COMPOSITION
plus a `meaning` split, and 10a/10b/10c had already built every leg.
`Checker.oracleResolveName` is three lines — the INV.2(c) ascent, `lookupInEnclosingNamespaces`,
`lookupPerFileForNode`, each meaning-masked — **built out of the checker's OWN functions
rather than beside them**, which is what keeps a post-hoc answer from drifting from what the
walk did. It reaches the whole B83.5 population, a PARAMETER and a body-local `const`
included, and answers the INNER of two same-named declarations. **THE ASCENT IS
DELIBERATELY UN-GATED AND THE ABLATION PROVES IT MUST BE**: gating it on the program-wide
name gate reddens exactly the pins that REMOVING the leg does, because that gate is a
projection of six DECLARATION kinds and a parameter is not one. **A PIN WRITTEN AGAINST THE
RESOLVED SYMBOL'S *TYPE* FAILED, AND THAT IS THE SECOND FINDING**: `const useLocal = collide`
renders the FILE-LEVEL `collide`'s type at rest and an inferred return renders `any`, on a
binary that resolves every symbol correctly — `typeOfSymbol` re-infers with no walk ambient
installed, so **`resolveName` answers the SYMBOL and an un-annotated local's TYPE is
`typeAt`'s answer**. **`symbolsInScope` STAYS REFUSED** because its reason is accurate: an
ENUMERATION must also read `LexicalScope.existing`, the INV.3 question round 748's rule keeps
out; its pin now asserts the refusal still NAMES that, so the two rows cannot be closed
together by accident. **ABLATION: five arms, union 3 of 27, and THREE have no unique pin —
recorded, not smoothed.** The leg-removed and leg-gated arms are a PAIR with an identical red
set; the FALLBACK arm reddens a strict SUBSET whose survivor is the shadowing pin (round
748's ordering law, a fourth time); and **`stopFlags` is 0 RED even after a pin was written
FOR it** — 10b's consult filters to `ScopeValueDeclaration`, which does not accept a
variable, so the ascent had to be told to STOP at one, while the oracle's mask is the SPACE,
which accepts it: a REDUNDANT GUARD for every documented mask, kept because the mask is a
caller's parameter. **TWO TEXTS CORRECTED BECAUSE THEY WERE FALSE** — `TypeOracle`'s class
KDoc and `docs/type-oracle.md` § 3b both still said the retained tables leave block-scoped
declarations unbound. Grid 8×`added=0 removed=0`, cost_gate exit 0, huge_methods exit 0
(844 classes), warning-clean.

**(P18.66) — (INV.0) STEP 10b-iii: THE TS2693 THE ITEM SAYS WE NEVER EMIT, AND TWO RESOLVERS DISAGREEING ABOUT ONE RECEIVER, 18,573 / 0 / 3 (2026-09-10).**
**TWO OF THE ITEM'S OWN FACTUAL CLAIMS WERE WRONG AND ONE OF THEM CHANGED THE SIZE OF THE
WORK**: we DO emit TS2693 — byte-identically to pristine — for a FILE-LEVEL `interface`/
`type` read as a value, so **(c)** was a LEVEL-CONSTRUCTION gap in one function rather than
a missing diagnostic (`tavListLevel`, what a statement `Block` contributes, surveyed
`values` ONLY where `tavModuleLevel` surveys all three); and the namespace half's SHADOWING
variants read the OUTER declaration rather than "nothing at all", with its (CHK.73)
attribution wrong too, since a FILE-LEVEL namespace value member read works. Fifth round
running that an item's facts were worth one command. **A THIRD POPULATION IT NEVER NAMED** —
a UNIQUE block-scoped `enum`/`class`/`namespace` receiver whose member is genuinely absent,
TS2339 in both references and silent here — is what decided **(b)** to replace silence as
well as override. **(b) IS TWO RESOLVERS DISAGREEING ABOUT ONE RECEIVER, WITH THE WORST
SYMPTOM**: a correct TS2322 and a false `Property 'ZInner' does not exist on type
'typeof ZzzE2'` on the SAME line, because the `cmam*` family composed its receiver from the
FILE-keyed `lookupPerFileForNode`. Two functional lines route it through
`nameResolver.lexicalValueSymbolForNode`, **the same consult step 10b uses** — the DRIFT is
the defect, so the two can no longer drift. **RECEIPT, 56-cell matrix against tsgo 7.0.2 AND
pristine 6.0.3 (agreeing on all 56): 18 ours-only / 44 missing → 6 / 35**, AGREE cells
18 → 27, `absentEnum`/`absentClass` closing completely; the (c) matrix's `ifaceAsValue` and
`aliasAsValue` 0/3 → 0/0 with every other kind byte-identical. **THE CONTROL THAT KEEPS THE
NUMBER HONEST**: `absentFn` is missing at FILE LEVEL TOO, so a nested-`function` receiver is
a general gap and NOT B83.5's — 5 rows deliberately unclaimed, now (CHK.119). **THE NAMESPACE
HALF IS A MEASURED TRADE, NOT A GAIN**: 6 confident false rows removed, the true row not
produced (`declareLexical`'s `ModuleDeclaration` arm publishes no `exports` where its `enum`
arm does), and 3 rows LOST that the wrong receiver had been answering correctly by accident
— shipped anyway, because keeping them means deliberately consulting the declaration this
step exists to stop consulting; now (CHK.120). **PER-FILTER VERDICTS FOR (c), MEASURED BY
DROPPING ALL FOUR AT ONCE**: `KNOWN_GLOBALS` is LOAD-BEARING (a block-scoped
`interface Event` shadows the global TYPE and not `declare var Event`, so `new Event(…)`
stays legal) and so is the namespace `hasValues` survey; the two `values` guards are
REDUNDANT (`spineTavIdentifierCore` returns above both probes) and kept, recorded as a
round-927 pair. **A COUNTDOWN PIN FIRED AS DESIGNED, THE FOURTH IN SIX ROUNDS** — an expando
"negative control" asserted silence for a nested `class Foo` shadowing a `function Foo`,
which both references report; split, with the residue renamed `residue - …`. **ABLATION: 5
arms / 13 pins, two brief predictions wrong** — the below-`perFileIdentSymbol` arm is
behaviourally the SAME mistake as the inverse gate (identical red set), and the
namespaces-excluded arm is 0 RED and recorded UNDISCRIMINATED rather than smoothed. Grid
8×`added=0 removed=0`, cost_gate exit 0, huge_methods exit 0 (844 classes), warning-clean.

**(P18.67) — (CHK.118) REFUSED WITH MEASUREMENTS, AND THE RECEIPT MATRIX WAS THE THING THAT WAS WRONG, 18,573 / 0 / 3 (2026-09-11).**
**NO CODE LANDED AND THAT IS THE FINDING.** The defect is real and was reproduced — a
variable in a nested `{ }` / `if` block / `namespace` body SHADOWING a file-level one is read
as the OUTER declaration, 9 ours-only / 19 missing over 36 cells, `file` and `fnTop` both
0/0, both references agreeing on all 36 — and the mechanism is one guard:
`checkVarDeclAssignabilityCore`'s annotated recorder is FIRST-DECL-WINS, so the nested
declaration loses to the file-level entry. **THREE OF THE ITEM'S CLAIMS NEEDED CORRECTING**:
it is `const`, `let` AND `var` alike (not a const-ness question), the UN-ANNOTATED spelling
is already nearly correct (so the axis is the ANNOTATION), and **its probe shape is
load-bearing — a PRIMITIVE-target probe reads all 36 cells CLEAN**, so an implementer using
the obvious probe closes this as already-fixed. **WHY IT IS REFUSED**: relaxing the guard
fixes every IN-BLOCK read and MOVES the wrong answer OUTSIDE the block, where with the
shadowed member present on BOTH types it is a **confident FALSE POSITIVE on legal code** plus
a lost true row. **AND THE RECEIPT IS THE PART THAT WAS WRONG — THE REUSABLE LESSON**: the
36-cell matrix reports 9/19 → 0/10 and is structurally unable to see any of that, because
every one of its probes is INSIDE the block; re-measured with an after-block read in all 18
cells it is **2/22 → 0/20**, i.e. +2/+2, a LATERAL move on `const`/`let` annotated with the
whole gain in `var`. **SCOPING WAS ATTEMPTED AND IS MEASURED INERT, WITH A LIVE POSITIVE
CONTROL** — identical to the parent on both matrices — because `(cta-m3a)`
(`Checker.kt:3127`) splits the WRITER (the legacy statement-list walk) from the EMITTER (the
spine anchor), so a statement-list boundary closes before BOTH reads and there is no
in-between; the unblocker is a boundary in the SPINE's cta traversal, now named in the queue
item. **THE IMPLEMENTER CORRECTED THE COORDINATOR AND WAS RIGHT**: the PARENT also
false-positives on legal code, so the FP class MOVES rather than appearing from a clean
baseline (2 FP + 1 TP → 1 FP + 1 missing) — verdict unchanged, framing fairer. **TWO
INDEPENDENT RESIDUES**, both measured against three compilers: an ANNOTATED function-body
local misses TS2339 with NO shadowing and NO nesting and is **exactly one cell of four** (the
un-annotated body-local and both file-level spellings report correctly) — now (CHK.121); and
the after-block leak ALREADY EXISTS for the un-annotated spelling on the unchanged binary.
Tree clean, binary byte-identical to (P18.66)'s; the refused patch, its 15 pins and its 8-arm
ablation are kept OUT of the tree.

**(P18.68) — (CHK.121): THE AXIS IS THE *INITIALIZER*, AND BOTH SIZINGS OF THE ITEM WERE WRONG, 18,604 / 0 / 3 (2026-09-11).**
An ANNOTATED function-body local now reaches the member-existence check.
**THE ROUND'S FINDING IS A CORRECTION TO ITS OWN ITEM, TWICE**: queued as "one cell of four",
re-sized by the coordinator to "six of seven shapes", and **both wrong** — the second because
it varied the annotation TYPE while holding the INITIALIZER fixed, which is the axis that had
to move. `const v: ZzzCfg = zzzCfgV` in a body ALREADY reported (the flow-recovery helper
serves an identifier initializer), as did `let`/`var`/nested-block/arrow; what was silent is an
OBJECT-LITERAL, `new`, CALL, `as` or scalar-literal initializer. And `number[]`, a heritage
interface, an intersection, a function type, a numeric index signature, an enum and an optional
are silent at **FILE LEVEL too**, i.e. pre-existing firewall refusals that were never this
defect — CLAUDE.md's "run the identical source at all three sites" law on a third axis.
**THE FIX IS THE LEAST POWERFUL SHAPE THAT WORKS**: `cmamAnnotatedLocalReceiverType`, 24
functional lines, wired LAST at the `anyType` bail and BELOW the flow-recovery helper, reusing
the knip-calibrated `cmamAllMissingTrustedMember` and `cmamInGuardMayAddProperty` rather than
re-deriving either — so it can only turn a SILENCE into a report. **RECEIPT**: the original
fixture byte-identical to pristine 6.0.3 and tsgo 7.0.2 (4/4 rows where we reported 1), and a
**207-cell** matrix on which the two references agree on all 207 goes **111 → 107 missing with
ours-only unchanged at 2**. **REFUSED AND PINNED AS REFUSALS** (not as controls): a class
instance, an array, `let`/`var`, plus the shapes the firewall already refused. **THE GRID IS A
REAL GATE HERE AND IS NOT VACUOUS** — a positive-control build counts **78 / 152 / 116
accepts** on compiler/harness/services, so the path fires hundreds of times on tsc's own
sources and the downstream gates absorb all of it. **TWO PRE-EXISTING DIVERGENCES FOUND AND
LEFT ALONE**, now (CHK.122) and (CHK.123): an `in`-guarded read on an identifier-initialized
annotated local is an ours-only FP (the flow route lacks the `in` consult), and a class-typed
local renders `typeof ZzzK` for `ZzzK`. **ABLATION: 10 arms / 31 pins WITH BOTH CONTROLS** — a
comment-only both-green arm and a refuse-everything both-red arm, which is what makes the four
0-RED arms interpretable as redundant-or-unreachable rather than as blind; one pin renamed to
say it is blind, and one the implementer wrote as a refusal was measured WRONG and converted
to a positive. Grid 8×`added=0 removed=0`, cost_gate exit 0, huge_methods exit 0 (844).

**(P18.70) — SIX (CHK.*) ITEMS, AND THE INSTRUMENT THAT WAS DROPPING ROWS, 18,652 / 0 / 3 (2026-09-11).**
Two (CHK.\*) items closed and **both queue items were wrong about their own size**, a
sixth round running. **(CHK.122)**: the item named ONE route for a missing `in`-guard
consult; measured against both references it is FOUR — flow, destructured, FILE-LEVEL
`const`, PARAMETER — and the last two never touch the `any` bail the route helpers
live on, so a per-route fix could not reach them. The consult went to the emission
FUNNEL. **The round's real decision was found by measuring**: with exhaustion
REFUSING, the profiles read `refused=5 exhausted=5` on services/server/harness, i.e.
every refusal on tsc's own sources was BLIND. It now DECLINES on exhaustion, so it
can suppress only on a POSITIVE finding — all eight profiles read `refused=0`, and
the residue is pinned AS a residue. **(CHK.119)**: the item says a `function` receiver
NEVER reports TS2339; it does, and **every row it emitted carried a display neither
reference produces**. Four expando WRITE forms were uncollected (element-access,
no-substitution-template, template-span, tagged) — each an ours-only FALSE POSITIVE —
and the display is now the SIGNATURE for a function with no expando. **The order of
those two fixes is load-bearing and is pinned.** **THE GRID IS A CONTROL FOR (CHK.119),
NOT A GATE, AND THE ROUND SAYS SO**: B431 emits ZERO rows on all eight profiles.
**THE INSTRUMENT FAILED TWICE, BOTH TIMES PLAUSIBLY** — `scripts/ref_matrix.py`
(new: the three-compiler adjudication every round rebuilds and discards) first parsed
only the REFERENCE row format and read our row set as empty (`missing=8` where we
emit all 8), then keyed on `(file, line, code)` so two rows of one code on one line
COLLAPSED. Both now refuse/resolve, plus TEXT-DIFF for a message divergence, which
(PARITY.1) says nothing else in the repo could see. Every conclusion drawn with the
broken key was re-run. **The Phase-18 WORK ORDER note was trimmed away a SECOND time**
by (P18.66) — restored, re-anchored under a `## QUEUE` heading, and
`scripts/check_plan_structure.py` now fails when it is gone. **(CHK.125)**: TS2394's predicate knew only `any`, so an ordinary
`f(string)/f(number)/f(unknown)` overload set was an ours-only FP — tsc's rule is TWO
rules (RETURN assignable in EITHER direction, PARAMETERS requiring the impl be wider),
and `unknown`/`never` are assignable in exactly one direction each, so the positions
disagree about four of eight combinations; ten cells measured, three of them negative
controls that still report. **(CHK.126)**: every silence-asserting pin in
`M04ExpandoSpineMigrationTest` re-measured — **ten countdowns, not the eight queued**,
renamed `residue - ` with their reference rows; **and three were made UNFALSIFIABLE by
this round's own (CHK.119) display change** (they keyed on `typeof Foo`, which an
expando-free function can no longer produce), fixed in the same session that broke them
and verified falsifiable rather than assumed. The audit found **(CHK.127)** — the
collector OVER-declares in the opposite direction from (CHK.119) — **and it was closed
in the same round**: twelve positions measured against both references, an object
literal is a HARD STOP (an array nested in an objlit value and an objlit nested in an
array are both refused, which rules out a rule about the immediate parent), and its own
residue pin from one commit earlier fired as designed. **(CHK.124) PARTLY CLOSED, AND ITS GATE IS THE FINDING**: B431 required a NESTED
read, so the item's headline shape — a FILE-LEVEL read, no nesting — was silent;
EX_TOP is now admitted. **The 8-profile grid is VACUOUS for this family, measured**
— a positive control reads **0 admissions on all eight profiles and 0 on cronstrue,
marked and a 600-file project** — so the CORPUS was the gate, and it caught a real
defect first try (a dangling dot's zero-width identifier grew a `Property ''…` row;
B431 was missing the empty-name guard its sibling has always had). Two residue pins
written earlier in the same session fired as designed and were converted. The
receiver-KIND half of (CHK.124) stays open and pinned as refusals.

**(P18.71) — (CHK.97) STAGE 3 D4: THE NULLISH-UNION CALLEE'S ARGUMENT CHECK, AND A SUPPRESSION WHOSE GATE WAS TWO MECHANISMS, 18,669 / 0 / 3 (2026-09-11).**
One of (CHK.97)'s six stage-3 deliverables closed — **and all six were measured against
tsgo 7.0.2 and pristine 6.0.3 BEFORE one was picked**, with zero REF-SPLIT rows anywhere.
That ranking is most of the round's value: D4 had **11 MISSING rows and a real gate**,
D2/D3 three each with only a control, D5 one, **D1 exactly ZERO** — and D6 is BLOCKED on a
model change (`Signature.thisParameter`), named rather than attempted. **D1 IS REJECTED ON
A MEASUREMENT, NOT DEFERRED**: its only visible readers are `ReturnType<U>`/`Parameters<U>`,
and what the references print there is tsc's conditional-type DISTRIBUTION, not
`getUnionSignatures` — combining would be wrong in a NEW way across 35 readers. **THE ITEM'S
AXIS WAS WRONG AND A FIXTURE BUILT ON IT MEASURES NOTHING**: it says "`f?.(1)`'s argument
check", but `?.` is innocent — `g?.(1)` on a plain `Fn` already reported, while the
`?.`-free `if (zu) { zu(1) }` was silent. The population is a callee **TYPE**. **THE
REUSABLE DEFECT IS ONE NEITHER THE ITEM NOR THE BRIEF NAMED: a suppression's gate was two
mechanisms wearing one `if`** — the round-408 pre-pass conjoined "is this callee narrowable"
with "strip nullish for an optional call", and the second is a property of the CALL, not the
callee expression; for every other callee kind the nullish member survived into the
not-callable verdict as an **OURS-ONLY TS2349 on legal code, four of them, one per callee
kind**. Its sibling: **a `Boolean`-returning pre-pass can only spend a suppression by
CONSUMING the call**, so the narrowed value it had just computed had nowhere to go —
`ccetUnionCalleeChecks` now answers `Type?` (null = consumed), the argument-side mirror of
the RESULT-side strip stage 2 put in `getReturnTypeOfCallExpression`. **RECEIPT: agree
8 → 20, ours-only 4 → 0, missing 19 → 7**, both directions. **THE GRID IS A REAL GATE HERE
AND IT IS GREEN, WHICH IS THE INTERESTING PART** — unlike last round's two items, a
positive control counts **49-101 hand-offs per profile**, so hundreds of arguments never
before checked on tsc's own sources were checked and all are correct; (CHK.50)'s law did not
fire. **A LATENT PATH WAS PROBED RATHER THAN ARGUED**: `allCallable` answers true for
`any`/`errorType`, so an unresolved union member now hands its union back — measured, zero
ours-only rows. **THE SUITE XMLs HAD BEEN WIPED BY A LATER FILTERED `--tests` RUN** (the
results dir held 1,524 tests, which reads exactly like a suite that never ran), so every
gate was re-run or re-derived from the capture files rather than inherited — including a
`javap | grep -v 'line N:'` control proving the grid's AFTER binary is bytecode-identical to
the committed one. Ablation 6 arms / 17 pins with BOTH controls, no 0-RED arm; four residues
recorded in the pin KDoc and **not pinned** (no countdown pins). Two findings queued:
(CHK.129) an `as`-asserted callee loses its argument check entirely, and (CHK.128)
`arr?.[0]` on a nullish array union types as `any`. Grid 8×`added=0 removed=0`, cost_gate
exit 0 (no rebaseline, max `mapped.keyed` +1.18%), huge_methods exit 0 (844), warning-clean.

**(P18.72) — (CHK.97) D2: A BOTH-OVERLOADED UNION CALLEE REPORTS, AND THE SUPPRESSION STILL HIDING A SECOND ROW, 18,679 / 0 / 3 (2026-09-11).**
**THE FIX IS A SPLIT BY *REASON*, NOT A RETIREMENT.** One `if` was answering two different
facts: TWO OR MORE overloaded constituents is exactly where tsc SKIPS pass 2, so
`getUnionSignatures` answers the EMPTY list and TS2349 reports with the "Each member … has
signatures" chain — a DIAGNOSTIC; exactly ONE is the `unionOfArraysFilterCall` shape, where
tsc RUNS pass 2 — SILENT. **The chain sentence now has ONE home** shared with the generic
refusal, because a chain is the whole observable here and (PARITY.1) says the grid is blind
to a display divergence, so two copies would drift with nothing to notice. Receipt
**agree 3 → 11, missing 8 → 0, ours-only 0** — **and that number was CORRECTED within the
round by the sub-step below**: it was taken with a chain-blind instrument, and one of its
eleven AGREE rows is really a TEXT-DIFF, so re-taken chain-aware the seven fixtures read
**agree 10, text-diff 1, missing 0**. The verdict does not move; the receipt does.
**A COUNTDOWN PIN FIRED — THE SEVENTH IN EIGHT ROUNDS — AND ONLY THE *FULL* SUITE SAW IT**:
a pin asserting the silence this round removes, with its own KDoc saying "SILENT where tsc
reports TS2349". It surfaced from an ablation arm run against the full suite; the four
corpus guard letters the round was gating on miss it. **Corollary: a guard-letter subset is
not a substitute for the suite when an arm WIDENS an emission — a widening's victims are
pins, not baselines.** **THE GRID IS A CONTROL AND THE ROUND PROVES IT WITH A COUNT**: a
counting arm reads 0 hits on all eight profiles against 3 on the round's own fixture, so
`added=0` is inertness, not coverage. **THE INSTRUMENT IS BLIND TO WHAT THIS ROUND CHANGES**:
`scripts/ref_matrix.py` matched a diagnostic's FIRST LINE only, so a chain-only divergence
scored AGREE — the third distinct blindness found in that script in two rounds, and it had
passed a real one (`typeToString` parenthesizes a union member with exactly one call
signature, where both references print it bare; pre-existing and unowned, now (CHK.130)).
Closed as its own sub-step, verified in BOTH directions — a fixture on which all three arms
agree on the chain still reads AGREE, so the arms' differing print formats do not
false-positive — and it immediately re-graded one of this round's own fixtures. **THE ROUND'S LOAD-BEARING CLAIM IS TRUE OF THE PROFILES AND FALSE OF THE
LANGUAGE**: the ONE-overloaded suppression D2 KEPT is reachable and hides a true positive,
because PASS 2 also refuses on GENERIC INCOMPATIBILITY. The implementer STOPPED at the scope
line rather than pushing through — now **(D2b)**, measured and ready (0 RED on 1,425
baselines, reddening exactly the countdown pin already inverted) and **deliberately not
taken: an ablation arm is not an implemented fix with pins.** Ablation 5 arms with both
controls; a3 recorded as a REDUNDANT GUARD and structurally so; a2 recorded as a DEAD ARM
for the round's own pin set. Grid 8×`added=0 removed=0`, cost_gate exit 0, huge_methods exit
0 (844), warning-clean.

**(P18.73) — (CHK.97) D2b: THE SILENCE THAT HID A TRUE POSITIVE, AND A DESIGN DECIDED BY *BUILDING* THE ALTERNATIVE, 18,688 / 0 / 3 (2026-09-11).**
**THREE LINES OF CODE**: the `>= 2` emit and the separate `>= 1` silence collapse into one
branch, because tsc's PASS 2 refuses for TWO reasons and only one of them is "more than one
overload set" — it also refuses on GENERIC INCOMPATIBILITY, where both references print the
identical TS2349 + chain and we were silent. **THE DESIGN QUESTION WAS SETTLED BY BUILDING
THE REJECTED ALTERNATIVE.** An instrumented binary that actually threads the refusal reason
out of `computeCombinedUnionSignatures` agrees with the recomputed `count` on **21 of 21**
reachable refusals, and structurally must; the thread is recorded as a refusal WITH ITS
NUMBER rather than as a preference. That census build was behaviour-neutral (18,679/0/3,
exactly the baseline), which is what makes its counts trustworthy. **TWO OF THE ITEM'S
CLAIMS WERE WRONG, BOTH TOWARD THE CHANGE LOOKING RISKIER THAN IT IS**: the surviving
silence's stated justification (`unionOfArraysFilterCall`) is FALSE — that shape never
reaches the branch at all, stage 2's array fallback answers it first — and
`overloadedMembers == 1` is reached **ZERO times** by the whole suite, all eight profiles,
cronstrue AND marked, so the widening cannot move a baseline and the pins are its only gate.
**RECEIPT: missing 8 → 0, ours-only 0, agree 12 → 15 — AND `text-diff` MOVES 1 → 6, WHICH
THE ROUND FLAGS RATHER THAN BURIES.** Five of the eight recovered rows land at the right
file, line, COLUMN and code with the wrong display, every one of them the same pre-existing
(CHK.130) defect (`ZzzA | (ZzzG)` for `ZzzA | ZzzG`); a fixture whose four rows differ only
in whether the member carries a property proves it is not a D2b defect, since the three that
do are byte-identical AGREE. So eight SILENT rows become three exact and five differing only
in parentheses — a meaning gain with a form residue, six instances louder because a
diagnostic that never fired could not display anything wrong. **THE GRID IS A CONTROL AND
THE CENSUS SAYS SO IN THE STRONGEST FORM YET**: zero union-callee combination refusals OF
ANY KIND on all eight profiles, cronstrue and marked. **ABLATION: 5 arms, EACH against the
FULL SUITE** (P18.72's own lesson), both controls; b5 proves last round's `>= 2` threshold
is load-bearing. **b4 is the interesting arm and is a refusal on SCOPE, not evidence**:
collapsing the whole `differ` tail is 0 RED on the full suite — evidence FOR it — and it was
still refused as (CHK.94) territory, with the number written into the branch comment so the
next round starts from a measurement. Grid 8×`added=0 removed=0`, cost_gate exit 0,
huge_methods exit 0 (844), warning-clean.

**(P18.74) — (CHK.130): THE PARENTHESES WERE ASKING ABOUT THE *SHAPE*, NOT ABOUT WHAT IS PRINTED — AND THE INSTRUMENT'S FOURTH BLINDNESS, 18,699 / 0 / 3 (2026-09-11).**
`typeToString` parenthesized a union member whose RESOLVED SHAPE is "one call-or-construct
signature and nothing else" — but `Type.Interface` and `Type.Reference` both EXTEND
`Type.Object`, so an interface with a call signature, an alias to a function type and a
generic instantiation all matched while PRINTING AS THEIR NAME. Parentheses exist so a
rendering can be reparsed inside a `|`; a name never needs them. The predicate now mirrors
`typeToString`'s own dispatch arm by arm and asks *is what we are about to print a bare arrow
form*. **A SECOND HALF WAS NOT IN THE ITEM: the interface case is ORDER-DEPENDENT**, because
a `Type.Interface`'s member tables are LAZY (round 833) — the same interface renders bare in
a plain TS2322 and parenthesized in a union-callee TS2349 whose own resolution has just
filled `callSignatures` in, which is why all six failing rows were TS2349 and why a TS2322
fixture showed the case as already correct. **THE AT-RISK ENUMERATION DECIDED THE ITEM AND
WAS DONE BEFORE ANY CODE**: over all 2,910 ACTIVE `.errors.txt` baselines, **7 carry a
parenthesised group next to a `|` and ZERO of those is a bare NAME** — so the remove direction
cannot move a baseline, and **no `LogicalParityDivergence` was needed or used.** **THE
ABLATION FOUND THE ASYMMETRY THAT MAKES IT SAFE**: parenthesize-nothing is 16 RED including
**4 corpus baselines**, so the corpus gates over-REMOVAL and not over-ADDITION — exactly what
the enumeration predicted from the other side; the two discriminating arms partition perfectly.
**TWO MORE COUNTDOWN PINS, THE EIGHTH AND NINTH IN NINE ROUNDS**, both our own defect
transcribed into an expectation, both now byte-identical to both references — per (CHK.114)
only the expectations changed, never the names. **AND THE INSTRUMENT WAS WRONG AGAIN, A
FOURTH TIME, IN THE WAY THAT MATTERS MOST**: `ref_matrix.py` folded "the references report
the same ROW and disagree about its MESSAGE" into AGREE — not adjudicable is the right
treatment and the wrong LABEL, since it inflates the prize and hides a divergence family. It
made a subagent report CLAUDE.md's (CHK.83) as CONTRADICTED when that law is exactly
reproducible (verified from raw bytes). New REF-SPLIT-MSG verdict; re-deriving this round's
own receipt with it moves `agree 21 → 19`. **Third round running in which re-taking a receipt
moved a number already written down.** (CHK.83) is not contradicted but POPULATION-SPECIFIC —
where the source is not generalized, both references name the FIRST constituent and we alone
name the last, 15 of 15 rows with the outer line byte-identical: now (CHK.132), and the
CLAUDE.md entry gained the clause that stops the next agent repeating the report. **The
(CHK.97) union-callee family is now byte-identical to both references across all 11 fixtures.**
Three outer-line residues remain, each measured and refused with a reason. Grid 8×`added=0
removed=0` and measured to be a control (416 rows, not one names a union). cost_gate exit 0,
huge_methods exit 0 (844), warning-clean.

**(P18.75) — (CHK.97) D3, THE IDENTICAL HALF: A UNION CONTEXTUAL SIGNATURE IS ANSWERED, AND THE BRIEF'S OWN FIXTURE NEVER REACHED THE ARM, 18,718 / 0 / 3 (2026-09-12).**
`callableSignaturesForCtx` refused a union contextual type at its SECOND callable member;
it now runs tsc's union arm of `getContextualSignature` — every member's one signature must
be `compareSignaturesIdentical` to the first with returns ignored, and the answer is the
first's parameters with the RETURNS unioned (`unionContextualSignature`, ~50 lines). Six
fixture families go `missing → agree` and the TWO ours-only TS7006 rows the refusal
manufactured are gone; the DIFFERING half (tsc's TS7006) stays silent and is recorded, as
are an OVERLOADED member (the helper has no arrow, so it cannot arity-filter as tsc does)
and a `Type.Reference` with lazy own signatures (the target fallback would read
`Cb<string> | Cb<number>` as identical). **The obvious fixture, `declare const zf: A | B;
zf((p) => …)`, never reaches the arm** — the argument gets the COMBINED callee signature's
parameter — so the whole matrix was re-cut through `take(cb: A | B)` and annotations. The
arm answers ZERO times on all eight profiles, `marked` and the 2,400-file project (14 on the
fixture), so the 8×`added=0 removed=0` grid is a measured CONTROL. Ablation 12/3/1 RED over 19
pins, a3 discriminated only by a NESTED differing pair. cost_gate exit 0 (all 20 counters
digit-identical to the pristine binary — the +1.18% is baseline staleness), huge_methods exit
0 (844), warning-clean. (CHK.97) stays open: D3-differing, D5, D6.

**(P18.76) — (CHK.97) D3, THE DIFFERING HALF: TS7006 THROUGH A UNION CONTEXTUAL TYPE, AND THE ARITY FILTER THE IDENTICAL HALF HAD LEFT OUT, 18,738 / 0 / 3 (2026-09-12).**
A union contextual type whose member signatures are NOT identical now leaves the arrow's
parameter implicitly `any` and reports TS7006/TS7031 through the EXISTING owner
(`checkParamsForImplicitAny`), as both references do. **The reach census ran BEFORE any
emission and read ZERO on all eight profiles, marked, cronstrue and the 2,400-file project**,
so the grid and library arms are measured CONTROLS — and it also showed (P18.75)'s
overloaded-member refusal firing 6× on one fixture, i.e. the emission was UNSOUND without
tsc's `getContextualCallSignature` arity filter, which was closed in the same sub-step
(`callableSignaturesForCtx(requiredParamCount)`, `signatureArityBelow`, several applicable
overloads folded through `getIntersectedSignatures`). Before → after: differing types/arity/
optionality `missing → agree`, the overloaded family 0/0/3 → 3/0/0, a new 16-file family
2/0/18 → 17/0/3, zero NEW ours-only rows. Five of (P18.75)'s own negative controls were
COUNTDOWNS and now assert the row. Ablation 16/3/15 RED over 39 pins; a `sig!!` on an
unresolvable callee passed every D3 fixture and was caught only by the 63-class neighbour
sweep (24 RED). cost_gate exit 0 (counters digit-identical to the pristine binary),
huge_methods exit 0, warning-clean. (CHK.97) stays open: D5, D6.

**(P18.77) — (CHK.97) D5: INFERENCE THROUGH A UNION-COMBINED SIGNATURE WAS BAILING ON AN INTERSECTION IT COULD NOT SEE, AND THE "ONE ROW" WAS SEVEN FAMILIES, 18,752 / 0 / 3 (2026-09-12).**
PASS 2 combines `(number[] | string[]).map` into `<U>(cb: ((v: number…) => U) & ((v: string…)
=> U)): U[]`, and every callback arm of the single-type-parameter inference demanded an
anonymous `Type.Object` — so the inference bailed whole and the call answered a raw `U[]`
that every reader silently refused as a foreign type parameter. **The first reading of the
mechanism was wrong and a stderr line settled it** (the gate PASSES, because a function
object's signatures are invisible to `typeMentionsTypeParam`; the candidate gatherer is what
finds nothing). Fix is a VIEW, not machinery: `inferenceParamType` presents such an
intersection as one anonymous function type carrying the existing `getIntersectedSignatures`
fold, memoized per intersection id. Four fixture families `missing → agree`, the PASS-2
pair fires (with a (CHK.132) sub-line), zero new ours-only rows. Ablation 12/4/1/0 RED over
67 pins — a4 a recorded redundant guard. **Two `TupleArrayMembersTest` pins were countdowns
on exactly this silence** and a `reduce` control was written wrong; all three found by the
68-class neighbour sweep, none by the fixtures. Grid 8×0/0 and both library arms are
measured CONTROLS (census `bound=0` everywhere real). cost_gate exit 0 (digit-identical to
pristine), huge_methods exit 0, warning-clean. (CHK.97) is open on D6 ALONE; its unblocker
`Signature.thisParameter` is now **(CHK.133)** at the top of the queue.

**(P18.78) — (CHK.133)(a)+(c): `Signature.thisType` AND THE CALL-SITE TS2684 — "A PURE MODEL CHANGE" MOVED FIVE ROWS BEFORE ANY CONSUMER EXISTED, AND (CHK.97) CLOSES, 18,781 / 0 / 3 (2026-09-12).**
`Signature` now carries its `this:` pseudo-parameter (`thisType`), threaded through all 28
`Signature(` constructions, all four instantiators and `MemberResolver`; the model ALONE
closed a false TS2345 on every call of an interface/class method declared with `this` (the
parameter zip) and made `unionTypeCallSignatures5/6` byte-identical to pristine (the union
arm INTERSECTS members' `this` types, deduped by identity because `getIntersectionType`'s
anonymous-object exemption printed `B & B`). The call-site consumer (tsc's
`getSignatureApplicabilityError` `this` leg → TS2684 with a one-level chain) landed in the
same commit: **387-533 declared `this` parameters per profile and ZERO call sites reaching
the check** on every profile and library, so the grid is a GATE for the model and a CONTROL
for the emission. A display defect the sizing missed (`ZzzBox<T>` for `ZzzBox<number>`) is
fixed and gated by the 18 active `this:` baselines. Ablation over 29 pins: 19/1/1/2/15/8/1/2/
1/1 RED across nine arms. cost_gate exit 0 with `typeNode.bypassed` +0.49% (the declared
`this` resolutions, bounded by the census; not rebaselined), huge_methods exit 0 (845),
warning-clean. **(CHK.97) is CHECKED OFF** — every deliverable closed or rejected on a
measurement across (P18.71)-(P18.78). (CHK.133) stays open on (b) the relation's `this`
leg and the `.call/.apply/.bind` consumer.

**(P18.79) — (CHK.133)(b): THE RELATION'S `this` LEG — ONE PREDICATE, THREE ELABORATION SITES, AND A BIVARIANCE RULE THAT WAS AN UNDER-APPROXIMATION, 18,809 / 0 / 3 (2026-09-12).**
`Relater.signatureThisTypesRelated` is tsc's `compareSignaturesRelated` `this` leg (a source
`this` other than `void` must relate to the target's; contravariant, or either direction
where `strictVariance` is off), consulted by the verdict AND by all three elaboration sites
so the chain line cannot contradict it — and `checkPropertyAccessAssignment` had no callable
elaboration at all. **The existing `bivariantParams` ("both sides MethodDeclaration") is an
under-approximation of tsc's TARGET-kind rule and reused verbatim was a false positive** on
a function value into a method-signature member; the `this` leg takes the target's kind, the
parameter leg is untouched. Census: 2,639-3,110 reached per profile, ALL a signature related
to ITSELF (the lib's type-parameter `this`), zero refusals, zero real comparisons of two
different concrete `this` types anywhere — the queue's "likely a REAL gate" refuted, grid a
CONTROL. Fourteen fixture families `missing → agree`, zero REF-SPLIT, every emitting row
byte-identical to pristine including chain nesting; `looseThisTypeInFunctions:21` byte-
identical. Ablation 17/5/1/16 RED over 28 pins. cost_gate exit 0 with all 20 counters
digit-identical to the rebuilt HEAD, huge_methods exit 0, warning-clean. **(CHK.133) is
CHECKED OFF**; `.call/.apply/.bind` sized read-only as a NEW mechanism (member-miss
augmentation with `CallableFunction` + inference through a `this`-typed lib signature;
81/18/12 sites on the compiler profile, so a REAL gate) and queued as **(CHK.134)**.
