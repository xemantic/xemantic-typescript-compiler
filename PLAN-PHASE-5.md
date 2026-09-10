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

**2026-09-01 — PHASE 18 (owner): the project is RE-POINTED to *TypeScript for the JVM and
Kotlin*.** Read the WORK ORDER at the top of the QUEUE below and CLAUDE.md § "AI agent
mission". The `## Phase 17` heading is kept verbatim for protocol stability; the QUEUE under
it is the live Phase 18 queue.

## Phase 17 — Self-compile the TypeScript compiler (M0–M5)

(Live session notes accumulate here, most recent first — same convention as Phase 16.)

### Round (P18.62) — (INV.0) step 10a: the B83.5 TYPE space, and the arc is FUNNEL-shaped rather than RADIUS-shaped (2026-09-10)

**Suite 18,520 → 18,528 / 0 / 3** (+8 pins). `Checker.kt` 191,506 → 191,591 (+85 — this
step is a semantic change, not an extraction; the shrinkage dashboard is unmoved in kind).
**8-profile grid `added=0 removed=0` on all eight**, `cost_gate.py` exit 0, `huge_methods.py`
exit 0 (842 classes), warning-clean.

**THE ITEM SIZED THE ARC BY ITS BLAST RADIUS AND THAT IS THE WRONG INSTRUMENT FOR ITS FIRST
SUB-STEPS.** "~357 `globals[` readers downstream" is a real count (`Checker.kt` 328,
`NameResolver.kt` 24, rest of core 5, plus 69 `.locals[`) and it is a count of AD-HOC
per-walker name probes — `globals["Record"]`, `globals[callee.text]` — not of the resolution
LADDER. The ladder has exactly TWO funnels: TYPE space is
`NameResolver.resolveTypeNameToSymbol` plus `Checker.getTypeFromTypeReference`, VALUE space is
`Checker.getTypeOfIdentifierCore`. Heritage is a third, smaller one. So the arc decomposes
10a/10b/10c/10d and none of them has to sweep 357 sites. **Read what a count is a count OF
before letting it size a round.**

**THE PRIZE, MEASURED OVER A 129-CELL MATRIX AGAINST BOTH REFERENCES** (7 kinds × 4 nesting
sites × 2 positions × unique/shadowing; `scratchpad/b835/final_matrix.txt`): at the 86 B83.5
cells, **106 lost true rows and 43 ours-only rows**, TYPE 24/60 and VALUE 19/46. The file-level
control is 15/15 clean, so the probes are sound. **The variant split is the finding**: a UNIQUE
scope-space name is **0 ours-only / 36 missing** — it degrades to `any` and every check under it
goes quiet — while a SHADOWING one is **43 ours-only / 70 missing**, resolving the OUTER
declaration in 40 of 42 cells. Nesting depth is irrelevant (34/36/36 across fnTop/block/if), so
CLAUDE.md's B83.5 entry now says a function-body-TOP declaration is as unbound as one three
blocks deep.

**TWO OF THE ITEM'S OWN FACTUAL CLAIMS WERE WRONG, WHICH IS THE SECOND ROUND RUNNING.** It
promised "1 ours-only TS2353 removed"; that shape is a MISSING row, not a false positive —
same fixture, opposite direction. And "round 748 closed the enum half" is exactly half true:
the enum VALUE position is still silent at every B83.5 site.

**WHAT LANDED.** The stamp (`indexSourceFile`), the binder PROJECTION
(`BinderResult.scopeTypeNames`), the name GATE (`Checker.lexicalBlockScopedTypeNames`) and the
consult (`NameResolver.lexicalTypeSymbolForNode`) all widened from `type`/`enum` to the whole
TYPE space. **The stamp widening is free**: `NodeKind` 23..26 are contiguous, so two int
compares stayed two int compares. `SymbolFlags.ScopeTypeDeclaration` is `Type` minus
`TypeParameter`, and that exclusion is the point — folding TPs in would put every `T`/`K`/`V`
into a set whose whole job is to be empty.

**`getTypeFromTypeReference` NEEDED ITS OWN HOIST, AND NOTHING WOULD HAVE SAID SO.** It asks
the enclosing-NAMESPACE chain itself and then calls `resolveTypeNameToSymbol` with
`enclosingNamespacesDone = true`, i.e. past that function's lexical-first arm. Without the
hoist a namespace member displaces a declaration inside its own function — the outer
declaration winning again, silently. Its pin is the only one with a namespace and its arm
reddens nothing else.

**A CONTROL THAT STOPPED BEING INERT, AND ONLY BECAUSE `class` WAS ADMITTED.** The (INC.16)
verify walk also `add`ed to the name gate. Harmless while the projection covered the same
declarations — and the moment `class` joined, a named `ClassExpression` (present in
`scope.symbols`, deliberately NOT stamped) would have entered the gate ONLY for a file that
also declares a scope-space `enum`, which is the one thing that makes that walk run. A type
name's resolution would have depended on an unrelated property of its file. The walk is now a
pure control and the projection is the sole source; its violation count is scoped to the four
DECLARATION kinds for the same reason.

**THE ONE REGRESSION WAS A PRE-EXISTING DEFECT THIS CHANGE EXPOSED, AND THE INSTRUMENT BEAT
BOTH HYPOTHESES.** `keyRemappingKeyofResult` grew two false TS2322. Delta-debugging said the
row needs THREE ingredients at once (the file-level `Oops`/`x` block, the inner `Remapped`
mapped type, the inner `Oops`/`x` block) — remove any one and it is silent — which is the
signature of order-dependent resolution, not a missing rule. A temporary marker DIAGNOSTIC
inside `getKeyofType` (round 947's positive control; `println` is swallowed by `runCli`'s
stdout capture) answered it in one run: **the input is `errorType`**, and
`if (type === errorType) return stringType` sat under a comment saying its result "is never
displayed/checked meaningfully". **That comment was measurably false.** `errorType` means the
resolution did not succeed, so a CLOSED domain of exactly `string` is round 463's
partial-key-domain error, and it emits a real false positive as soon as anything assigns to a
binding annotated with it. **It was invisible because B83.5 kept such an alias at `any` — and
`keyof any` IS the correct open domain, so making the type real narrowed a correct superset
into a wrong subset.** The two arms now agree. A first, separate attempt (an intersection arm
for `keyof (X & T)`) was correct on its own standalone shape, verified, and never fired here;
it is kept with its own pin and its own ablation arm.

**A COUNTDOWN PIN FIRED EXACTLY AS DESIGNED, WHICH IS THE FIRST TIME IN THIS FAMILY.**
`negative control - a block scoped interface stays unresolved in type position` was written by
round 748 as a deliberate marker, and its own KDoc said "it exists so that a future widening
has to change this pin on purpose". Inverted, with both references confirming. It is kept in
`FunctionScopedEnumTypePositionTest` rather than moved, because that is where a future
NARROWING of the slice would be made.

**AND A VACUITY GUARD CAUGHT ITS SECOND BLIND PIN — A B83.5 WORKAROUND WENT DEAD.**
`EagerIndexDeferralTest`'s `an unpartitioned build still builds the indices it needs` read 0
scans on a perfectly working build. Its fixture reached `findLocalTypeAlias` through
`arrayElementUnionAlias`, which consults the index ONLY after `getTypeFromTypeNode(ref)` fails
to produce a union — the very failure 10a fixes. **So the array-literal branch of that
workaround is now unreachable for the shape it was written for.** The fixture moved to the
other reader (`discUnionParamMembers`); the dead branch is recorded as a LEAD, not deleted.

**A PRE-EXISTING WARNING FIXED**: `nodeAnswerComputations`' redundant `internal set` (added by
step 8). Kotlin does not re-emit warnings on an up-to-date compile, which is how "warning-clean"
survived a round — a `--rerun-tasks` is what sees them.

**COST**: `globals.lookups` **−0.23%** and `globals.misses` **−0.24%** — the consult answering
names that used to fall through to a miss — against `mapped.keyed` **+1.18%** and
`typeOfExpr.distinct` +0.06%, which are the real resolutions where there used to be `any`. All
inside ±2%, so no rebaseline.

**RESIDUES STATED**: a named `ClassExpression`'s own name is not in the stamp (kind 63, outside
the contiguous 23..26 range), so that population keeps exactly the resolution it had; heritage
is untouched (10c); VALUE space is untouched (10b) and is where 46 of the 106 missing rows are.

**NEXT**: 10b, the VALUE space — the bigger half of what is left, and its ladder order is
load-bearing, so the consult goes INSIDE `getTypeOfIdentifierCore`'s rungs rather than on top.

### Round (P18.61) — (INV.0) step 9: the decision, taken on measurements, and the scope-space ascent gets one home (2026-09-10)

**Suite 18,519 / 0 / 3** (18,514 + 5 new pins). `Checker.kt` **191,540 → 191,506**;
`LexicalScopeResolver.kt` **124**, ledger row 12. **8-profile grid `added=0 removed=0` on
all eight**, cost_gate exit 0, huge_methods exit 0 (**842** classes), warning-clean,
receipt now SEVEN binaries. Two commits: `0b3d1f089` the consolidation + the two
corrections, `d54f0ad52` the ablation record.

**THE DECISION, WITH BOTH OPTIONS SIZED RATHER THAN ARGUED.** Option (a) — continue
Stage 0 on the check passes — is where the LINES are: check-pass / walker-frame prefixes
are **96,830 of 191,499 attributed lines (50.6%)**, `check*` alone 53,571. It is also
where the SEAMS are not. The ambient census of the four largest candidates reads
`cmam*` **83**, `caas*` **59**, the type-node builders **68**, and `cae*` **97 reads for
EIGHT declarations** — against rows 1-11's 0/0/4/26/22/4/13/45/21/5/61. **A collaborator
with twelve times as many inputs as members is a file move, not a seam**, which is
exactly what the step-9 item predicted "a list of walkers" would be. Option (b) taken.

**AND OPTION (b) HAD TO BE CORRECTED BEFORE IT COULD BE TAKEN: the item as first written
offered "open Stage 1", and Stages 1 AND 2 landed on 2026-09-02** (§§ 9a/9b — the
`NodeAnswerStore` and the `TypeOracle` facade). The open stage is 3. That correction
changes what the alternative IS: not more plumbing behind a flag, but the semantic change
that opens the two methods a Kotlin consumer of the embeddable checker needs. **A queue
item's own factual claims are worth one command to check** — this one would have sent a
round at work that already exists.

**WHAT B83.5 ACTUALLY IS, MEASURED, AND NOT WHAT ITS NAME SAYS.** `Binder` recurses into
statements from exactly two places — a `SourceFile`'s own list (`Binder.kt:283`) and a
`ModuleBlock`'s (`:558`) — so it is not "declarations nested in a `Block`" that go unbound
but *everything that is not a direct statement of one of those two*. **A `class` at the
very TOP of a function body, with no block nesting at all, is equally unbound.** Measured
against tsgo 7.0.2 on that shape: **1 ours-only TS2353 and 0 of 4 true rows** for
interface/class/type/function, plus an ours-only TS2339 at the enum VALUE position (round
748 closed the TYPE half only). CLAUDE.md's entry now says this.

**THE SUB-STEP IS THE INERT ONE, AND IT IS ABOUT DUPLICATION RATHER THAN LINES**: the
ascent over `BinderResult.lexicalScopes` had been **hand-copied five times** and the copies
had drifted on four axes — which table (the owning file's, or the walk-scoped ambient
`currentLexicalScopes`), start at the node or at its parent, which `SymbolFlags`, hop-capped
or not. Every difference is deliberate, so they became PARAMETERS. `LexicalScopeResolver`
has **ambient surface NONE — the first such row since step 3** — because the ascent is a
pure function of the tables and the parent chain.

**THE GATE IS THE GRID, NOT THE CORPUS.** Three of the five callers are name-GATED, so a
wrong axis surfaces as a name resolving to an OUTER binding, which CLAUDE.md records as
silent in every diagnostic channel here. All eight profiles read `added=0 removed=0`, with
the harness profile's 94 rows and no truncated capture.

**TWO TEXTS CORRECTED BECAUSE THEY WERE FALSE, NOT VAGUE.** `LexicalScope`'s KDoc claimed
the tables are "UNCONSUMED until INV.4 — nothing in the checker reads these tables yet"
(five consults have, since round 748) and proposed `symbols` → `existing` → parent as the
future resolution order — **which is exactly the read round 748 REFUSED**. And
`TypeOracle.resolveName` / `symbolsInScope` both refused with "the retained scope tables
leave block-scoped declarations unbound (B83.5)"; **the retained tables HAVE that
population**. What is missing is a COMPOSED resolver (round 918: this ascent's rules do not
transplant onto another chain), a `meaning` parameter, and an `OracleLens` row — and
`symbolsInScope` opens LATER than `resolveName`, because an enumeration must read
`existing`. **A refusal that misstates its own blocker is worse than no refusal**: it makes
a composition problem look like a binder problem, and it is what would have sized the next
round wrong.

**FOUR OF FIVE ARMS DISCRIMINATE; ARM 1 REDDENS ALL FIVE AND IS RECORDED AS SUCH.**
Starting the ascent at the file root destroys the ascent, and every pin depends on it.
Pin 1's discrimination rests on a different fact — none of arms 2-5 reddens it, and it is
the only fixture declaring one name at TWO levels, so the only one that can see a
shadowing-ORDER defect. Three narrower arms were tried and each reddens pin 2 or pin 3,
because those two are positional by construction.

**NEXT**: the inert path is spent — everything further on Stage 3 is the resolution-ORDER
change (`NameResolver.kt:1760-1767` says a fallback is not enough), with ~357 `globals[`
readers downstream, gated by the corpus AND the grid. That is a multi-round arc and should
be opened as one, with its own item, not as a sub-step. **One thing left UNVERIFIED and
worth a single probe first**: whether `getTypeOfSymbol` answers correctly for a scope-space
`Class`/`Interface`/`Function` symbol. Precedent exists only for `Enum` (round 748's
transient-symbol route) and by declaration-read for `TypeAlias`; that answer decides
whether an oracle row can ship cheaply or needs a second sub-step.

### Round (P18.60) — (INV.0) step 8: the TYPE-CAPTURE family becomes `CaptureRecorder.kt`, and its ambient row is the design's own claim as a number (2026-09-10)

**Suite 18,514 / 0 / 3** (18,506 + 8 new pins). `Checker.kt` **194,631 → 191,540**
(−3,091, the arc's largest single move); `CaptureRecorder.kt` **3,214**. cost_gate exit 0,
huge_methods exit 0 (**841** classes, `Checker.<init>` 5,697 → **5,621**), warning-clean,
ledger row 11. Commit `d897942c9`. **Fifth extraction of the session.**

**THE ITEM'S OWN "OBVIOUS CANDIDATE" WAS A SCATTER, AND THE CENSUS SAID SO IN ONE
COMMAND.** Step 8 named the member-ACCESS family; it is `getPropertiesOfType` 116481,
`getPropertyAcrossType` 117056, `getStaticMembersOfType` 117400, `getApparentType`
132856, `collectInheritedPropertyNames` 143137, `getPropertyTypeForRelation` 166353,
`collectTargetPropertyNames` 166608, `isOptionalProperty` 170841 — eight neighbourhoods,
no span. **What the same census found instead was TYPE CAPTURE**: 103 `typeCapture*` /
`captured*` declarations of which 94 sit in ONE block, 3,120 lines. Three rounds running,
the census has overturned the queue's own guess; the instrument is
`scripts/codemask.py` plus a contiguity scan, and it costs one command.

**61 AMBIENT READS AND 9 WRITES — THE ARC'S LARGEST ROW, AND IT IS THE FINDING RATHER
THAN A DEBT.** `docs/INVERSION-DESIGN.md` § 2 says this checker cannot serve a post-hoc
type oracle because "its answers are functions of walk-scoped state". This row is that
claim as a NUMBER: fourteen of the reads and ALL NINE writes are the WALK — `ctaFrames`,
`currentFlowGraph`, `currentClassForThis`, `currentCheckFileName` (10 write sites),
`spineCurrentScope`, `inAsyncFunctionBody`, `currentTypeParamScope` — and the writes are
a save-and-restore sandwich reconstructing the ambient a node was reached under. Moving
the family does not make any of that explicit; it COUNTS it. **The OUT surface is the
arc's cleanest by the opposite measure: 98 declarations move and 15 keep a caller.**

**THE RECEIPT FOR A CAPTURE FAMILY IS THE CAPTURE CHANNEL, AND A ROUND THAT TOOK ONLY
`--passTiming` WOULD HAVE PROVED ALMOST NOTHING.** (INC.2)'s law — "do NOT infer a
capture's correctness from a green diagnostics sweep, they are different resolvers" —
decides which gate is the gate here. `scripts/capture-equivalence.sh` prints a per-arm
DIGEST over every captured answer: **381,666 captured types and 360,917 captured
definitions, `full=-1675305230568277215 narrow=-1216978524918639134` on BOTH arms**, with
the full-vs-narrow divergence census identical row for row (961 spans in 43 of 76 files —
the standing (INC.26) alias figure, not a regression). **A trap that cost one 10-minute
run: the digest line is ABOVE the driver's summary tail, so a `| tail -4` keeps the
summary and throws the receipt away.** Redirect the whole output. The 488 deterministic
`--passTiming` lines are byte-identical too, so that receipt now spans SIX binaries.

**VERBATIM proved twice** (`inverse(moved) == HEAD span` and `forward(HEAD span) ==
moved`), 170 ambient rewrites over 61 members, 15 visibility rewrites.

**FOUR THINGS THE COMPILER FORCED, EACH RECORDED RATHER THAN WORKED AROUND**: `CtaFrame`
becomes `internal` (a widened `ctaFrames` exposes it, and `withCtaFrameLocals` is an
`internal inline` touching its members); `nodeAnswerComputations`' `private set` becomes
`internal set`; the three `TYPE_CAPTURE_*_MAX_DEPTH` constants MOVE into the
collaborator's own companion, having no reader left; and 42 ambient members widen
`private` → `internal`, which is what a 61-read row costs.

**PrintInlining says something real for the first time since step 4b-ii**, because this
family has exactly ONE hot entry point: `typeCaptureVisit` is called per node from
`spineEnterNode`, was a 925-byte body refused six times as `too large`, and its hop reads
**`inline ×6`** — `spineEnterNode`'s own refusals fall 4 → 3. Everything else in the
family is cold by construction (the bench passes no `TypeCaptureRequest`).
`getTypeOfExpression` moved 379 → 390 and is NOT quoted: row 4 proved it unstable across
processes on one binary. ab-interleaved 6 pairs **+52 ms (+0.20%) B-wins-3/6
NOISE-DOMINATED**, both arms 46 errors.

**ALL EIGHT ABLATION ARMS REDDEN EXACTLY THEIR OWN PIN — the arc's first perfect
8-for-8.** The pins assert VALUES throughout, which is what this family needs: an ABSENT
capture renders nothing and reports no error anywhere ((INC.2b)), so a pin asserting "a
capture exists" passes on a badly broken binary. Two of them are a PAIR that earns its
keep — at a dangling-`.`-at-EOF span the type table stays FIRST-wins while the member
table takes its descendant exception, so the same span answers from two different rules
and one fixture pins both. **A fixture property a future reader will otherwise break:
`dangle.ts` must end IMMEDIATELY after the `.`** — no newline, no `;`, no space — or the
span collision does not happen and both pins go vacuous, which is why the file builds
that string by concatenation and asserts `receiver == access` before measuring.

**TWO CANDIDATES DROPPED FOR THE SAME REASON, WHICH IS WORTH MORE THAN A NINTH PIN**:
`activeParameter`'s clamp onto a rest parameter is real and distinct, and **no instrument
available here can give it ground truth** — the LSP maps `SignatureHelp.activeArgument`
onto the protocol's top-level `activeParameter` and never surfaces the per-signature
clamped value, so both servers read `3` where the internal answer is `1`. Likewise a
scope pin asserting each offered name's KIND: for an imported name the symbol is the
ALIAS, so the kind is `ImportSpecifier` rather than the target's, and nothing exposes the
difference. A pin whose expected value can only be obtained by reading the function it
tests is not a pin.

**A MEASURED DIVERGENCE RECORDED AND NOT ASSERTED**: at the dangling-`.` span tsc hovers
the RECEIVER (`const holder: { alpha: number; beta: string; }`) where our first-wins
answers the property access's `any`. Pinning `"any"` would be the countdown CLAUDE.md
forbids, so that pin asserts the winning node's KIND only.

**NEXT**: after this the file is 191,540 and what is left in it is dominated by CHECK
PASSES, which § 6 puts LAST — `spine*` (three blocks over 7,400 lines), `check*`,
`cmam*`, `caas*`, `cae*`, `cvda*`. The remaining non-check families are SMALL. So step 9
is a decision, not a census: either start on the check passes (which needs a rule for
what a "pass" collaborator even IS, since they read the whole checker and write
`diagnostics`) or stop Stage 0 and open **STAGE 3** — *not* Stage 1 or 2, both of which
LANDED on 2026-09-02 (§§ 9a/9b), a correction made to the queue item in this same round
after it was first written wrong. Stage 3 is "dissolve B83.5", which `TypeOracle`'s own
`resolveName` / `symbolsInScope` refusal names in words as its blocker. Say which, and
why, before moving any line.

### Round (P18.59) — (INV.0) step 7: the ENUM family becomes `EnumSemantics.kt`, and the arc's ambient TOTAL falls for the first time (2026-09-09)

**Suite 18,506 / 0 / 3** (18,498 + 8 new pins). `Checker.kt` **195,606 → 194,631** (−975);
`EnumSemantics.kt` **1,137**. cost_gate exit 0, huge_methods exit 0 (**839** classes,
`Checker.<init>` 5,721 → **5,697**), warning-clean, ledger row 10. Two commits: `1c520fc19`
the split, `b62d9d376` the ablation record. **Fourth extraction of the session.**

**THE CENSUS THE QUEUE ITEM DEMANDED DECIDED THE ROUND, AND TWO OF ITS THREE CANDIDATES
ARE NOT SEAMS.** (a) SIGNATURES is a SCATTER — `requiredParameterCount`/`getParameterSymbols`
at 117338, the call/construct readers at 161778, `instantiateSignature` at 172002, ~50 more
over six unrelated neighbourhoods. (b) FLOW is a SCATTER — 64 declarations, 22 of them
singletons or pairs, from line 1091 to 125482. (c) ENUM is ONE contiguous span, 1,013 lines,
40 declarations, 17 ambient references of which 5 are the family's own state. Taken.

**THE STAGE-0-EXIT DECISION ROW 9's CORRECTION ASKED FOR IS TAKEN, AND IT COST ONE LINE.**
That correction predicted this extraction would cut ~790 lines and reduce NO ambient row,
because `Relater`'s enum calls would still route through `Checker` — and that paying row 7
down needs the collaborators wired to EACH OTHER through "an explicit construction graph in
`Checker.<init>` … rather than drifted into". **`Checker`'s construction block already IS
that graph**, so the decision was placing `EnumSemantics` before its two consumers.
Measured with ONE uniform script across both arms: **`Relater` 49 → 38 checker reads (−11
over 20 sites), `MemberNames` 5 → 4**, new row 13 reads / ZERO writes. **First fall in the
arc's ambient total.** (Counting note: row 7 records 45 because it assigns four
read-modify-write members to the WRITES column; both conventions give −11.)

**A MASKING DEFECT EVERY EARLIER ROUND OF THIS ARC SHARED, and only the compiler saw it.**
`spanmask`/`strip` blank whole string literals, so a `${ … }` INTERPOLATION — which is code
— is invisible to the ambient census and unrewritten by the forward transform. One site
existed (`"import(\"${moduleFileBaseNoExt(f)}\")"`). It failed LOUDLY here, because a
`Checker` member is not accessible from a collaborator — but that makes the compiler a
complete detector only for the AMBIENT case: a TOP-LEVEL function called from inside a
template resolves fine and would simply be absent from a ledger row. `codemask.py` keeps
interpolations visible and comments blanked; both verbatim proofs are taken with it.

**VERBATIM PROVED TWICE, over CODE positions only**: `inverse(moved region)` is
byte-identical to `HEAD:Checker.kt[115064..116076]`, and `forward(HEAD span)` is
byte-identical to the moved region. 50 ambient rewrites over 13 members, 29 visibility
rewrites, 24 delegations.

**THE RECEIPT NOW SPANS FIVE BINARIES** — the same 488 deterministic `--passTiming` lines
byte-identical for pre-step-5 pristine, steps 5, 6a, 6b and this: **one receipt over 3,522
moved lines**, at one extra build for the whole session. **A recipe trap cost one bench run:
the first capture was taken with `--listAll` where the pristine arm was not, so the
diagnostics block truncated at 30 in one arm and listed all 46 in the other** — 22 diff
lines that look like a regression and are a `sed`. A capture is a property of (OUTPUT ×
RECIPE); re-run the recipe, do not reconcile the diff.

**PrintInlining is FLAT, and that is the honest reading**: 182 → 191 `too large` over the
20 tracked sites. Rows 4-9 each removed dozens because each moved ONE large body out of a
caller's inline tree; this family is 40 SMALL functions (largest 1,033 bytecodes), so there
was no monolith to remove. The one real gain is `isEnumFlavoredObjectType` — **zero** inlines
at 21 call sites before, 37 after. **A trap that manufactured a fake +137 first**: the twelve
members that were `internal` on `Checker` are JVM-name-MANGLED there and are plain members of
an `internal class` afterwards, so a matcher requiring a space after the name reads ZERO rows
in the PRISTINE arm and every row in the split one. Match `::NAME($suffix|-hash)?\s` on BOTH
arms. ab-interleaved 6 pairs **−120 ms (−0.46%) B-wins-3/6 NOISE-DOMINATED**, both arms 46.

**SEVEN OF EIGHT PINS DISCRIMINATE EXACTLY; THE EIGHTH WAS BLIND AND THE ARM SAID SO.**
Arm 8 read `0 RED`, and the pin — not the guard — was the blind half: its fixture (`export
enum` in one file, imported into another) resolves BOTH the value and the annotation through
ONE import, so the two member symbols are IDENTICAL and reducing
`enumMemberTypesAreSameMember` to `sourceMember === targetMember` changes nothing. Two
further shapes were probed ON THE ABLATED BINARY before concluding — a three-file variant
(no difference) and two same-NAMED enums with equal values (**1 row vs 2**). Repaired to the
latter; arm 8 now reddens it and nothing else. **Arm 3 reddens TWO pins (3 and 8) and that
is recorded rather than smoothed**: pin 8's ACCEPTANCE runs through the very member loop
arm 3 inverts. Neither is redundant — arm 8 separates them — arm 3 simply is not a
single-pin arm.

**A DIVERGENCE THE REPAIR SURFACED, RECORDED AND NOT PINNED AS RIGHT.** At a variable
declaration both references print `Type 'Z.Foo.A' is not assignable to type 'X.Foo.A'.`
where we print `Type 'Foo.A' is not assignable to type 'Foo.A'.` — the (REL.1)(c)
rounds-745-749 same-string retry (`enumCollisionQualifiedDisplays`) is not reached by that
reader, in `diagnose()` and through the project CLI alike, for a namespace-nested AND a
module-scoped collision. **PRE-EXISTING** (the family moved verbatim; HEAD~1 prints the
same) and orthogonal to what the pin gates, which is the VERDICT. Queued below as a lead.

**A PROCESS FINDING FROM RUNNING A SUBAGENT BESIDE THE BUILD**: the pin-design agent was
probing with `java` while this session ran `compileKotlinJvm`; the class dir emptied
mid-probe and **five shapes read as "our compiler is silent where both references report"**
— a convincing false divergence family. `grep 'error TS'` hides `Could not find or load
main class`. CLAUDE.md's "one gradle invocation per BOX, not per agent" has a second half:
a **`java` probe is a victim too**, and any probe script must grep for the dead-classpath
line first.

**NEXT**: the ambient TOTAL can now fall per round, but only where a collaborator's reads
are a FAMILY someone else owns. Census `getPropertiesOfType` / `getPropertyOfType` and the
member-ACCESS family, which `MemberResolver`'s 22 reads and `Relater`'s remaining 38 both
point at; SIGNATURES and FLOW stay Stage-3-shaped until an instrument for a scatter exists.

### Round (P18.58) — (INV.0) step 6b: the MEMBER-NAME / late-binding family becomes `MemberNames.kt`, the arc's cleanest seam (2026-09-09)

**Suite 18,498 / 0 / 3** (18,493 + 5 new pins). `Checker.kt` **196,176 → 195,606** (−570);
`MemberNames.kt` **765**. cost_gate exit 0, huge_methods exit 0 (**838** classes,
`Checker.<init>` 5,705 → 5,721), warning-clean, ledger row 9. Two commits: `ecd6c0491` the
split, `95dff28a1` the ablation record. **Third extraction of the session.**

**FIVE AMBIENT READS, ZERO WRITES — the smallest row of the arc, against the relater's 45/5
and member resolution's 21/1 — AND THE REASON GENERALISES: the family owns NO STATE and
answers a SYNTACTIC question.** It is handed a name node and returns a string; everything it
needs beyond the AST is an enum's constant value (3 reads) plus two AST helpers (2), i.e.
four of the five belong to seams of their own. Rows 7 and 8 read the type system because they
ARE the type system; this one does not. **`fileResults` as a CONSTRUCTOR INPUT is what took
the row from 6 to 5** — it is declared at `Checker.kt:265`, above the 666 boundary — which is
rows 5/6's rule paying off rather than a judgement call.

**`MemberResolver` WAS DELIBERATELY NOT REWIRED.** Step 6a's collaborator calls
`getMemberName` ×4 and `declaredMemberName` ×4 and now does so one hop further, through
`Checker`. That is the right answer and not a shortcut: the delegations exist anyway for the
other 30 call sites, and routing through `Checker` keeps the two collaborators free of a
construction-ORDER dependency on a class whose field initializers run ~9,300 lines deep.

**THE SPLIT REMOVED 61 `too large` REFUSALS AND ADDED NONE — the fifth row running.**
`getMemberName` `16 inline + 16 too large` → a hop reading `7 inline` with zero refusals;
`computedLiteralKey` `20 + 20` → `6`; `lateBoundComputedKeyName` `17 + 17` → `3`;
`computedSymbolKey` `6 + 6` → `2`; `objLitElementMemberName` `2 + 2` → `2`. ab-interleaved
6 pairs **−182 ms (−0.70%) B-wins-3/6 NOISE-DOMINATED**, both arms 46 errors. Recorded
honestly: `checkArgumentsAgainstSignature` — the only standing site (P18.56) proved stable —
moved again (`5 hot-too-big + 6 inline + 1 too large` → `4 + 5 + 1`), which is what widening
members to `internal` does to a caller's inline tree.

**THE RECEIPT NOW SPANS FOUR BINARIES**: the same 488 deterministic `--passTiming` lines are
byte-identical for pre-step-5 pristine, step 5, step 6a and step 6b — **one receipt over
2,509 moved lines**, and it has cost one extra build in the whole session, because each
round's capture is the next round's pristine arm.

**THE PINS ARE *AGREEMENT* PINS, AND THAT IS WHAT THIS FAMILY NEEDS.** A member's name is
asked at REGISTRATION and again at RESOLUTION, and BOTH known failures of this code produce a
correct diagnostic beside a false one — round 935's drift emitted a correct TS2322 and a
false TS2339 for the same member in ONE compile, and (CHK.40)(c) registered a string-named
METHOD correctly and typed it `any`. **A pin asserting "it compiles" passes on both.** So
each pin reads the member back through a deliberately WRONG target type and asserts the
TS2322 that names the resolved type AND the absence of TS2339 beside it.

**EVERY PIN DISCRIMINATES — the first of the session's three extraction rounds where that is
true.** Disabling late binding reddens the three late-binding pins and nothing else; raising
`LATE_BIND_ALIAS_HOPS` 8 → 100 reddens ONLY the past-the-limit pin (and since that arm edits
`Checker.kt`, `MemberNames.kt` is byte-unchanged under it — the arm's own control); dropping
the `StringLiteralNode` arm reddens ONLY the string-named-method pin. **The hop-limit PAIR is
the interesting one**: a 2-hop alias chain late-binds and an 11-hop one does not, so the two
pins bracket the limit and neither alone is evidence.

**Absorption census ZERO again** (row 8's answer, not row 7's): the scarcest ambient member
still has 3 other callers and `expressionTrueEnd` has 274.

**A DEVIATION WORTH CARRYING: the collaborator's FIELD is `memberNamer`, not `memberNames`.**
`Checker.kt` already declares EIGHT locals named `memberNames`, five used in the same scope. A
field of that name compiles — locals shadow it — and it broke the round's own 12-vs-12
structural check (read 18). **Before naming a new collaborator field, grep `Checker.kt` for a
local of that name**; the class is large enough that the collision is likely and silent.

**NEXT**: `getPropertiesOfType` / `getPropertyOfType` and the member-ACCESS family just below
this span — or, per § 6's order, SIGNATURES and FLOW. Row 7's seven absorbable members
(`enumLiteralApparentPrimitive`, `enumMemberValueEqualsLiteral`, `enumTargetAdmitsNumericSource`,
`numericLiteralFitsEnum`, `intersectionMergedSatisfiesTarget`, `intersectionMergedContradictsTarget`,
`targetIsMemberShaped`) remain the arc's one cheap win, taking row 7 from 45 reads to 38.


### Round (P18.57) — (INV.0) step 6a: MEMBER RESOLUTION becomes `MemberResolver.kt`, and the queue item's open question is answered (2026-09-09)

**Suite 18,493 / 0 / 3** (18,489 + 4 new pins). `Checker.kt` **196,797 → 196,172** (−625);
`MemberResolver.kt` **814**. cost_gate exit 0, huge_methods exit 0 (**837** classes,
`Checker.<init>` 5,675 → 5,705), warning-clean, ledger row 8. Two commits: `c26520a8e` the
split, `c8b2349e4` the ablation record. **Second extraction of the session.**

**THE QUEUE ITEM ASKED WHETHER THE SEAM IS THE TABLE BUILDERS *WITHOUT* `getTypeOfSymbol`.
IT IS**, and the census said so before any code moved: this family reads `getTypeOfSymbol`
at ONE site and `getTypeOfExpression` at one, so a seam that took them would have had to
take the whole checker — which is exactly why `docs/INVERSION-DESIGN.md` § 6 puts them in
Stage 3 ("their ambient IS the checker"). The answer is written into the class KDoc rather
than left in a round note.

**AND IT IS A MUCH BETTER-SHAPED SEAM THAN THE RELATER, WHICH MAKES ROW 7 THE OUTLIER OF
THIS ARC RATHER THAN THE TREND**: one contiguous span against five, 657 lines against
1,256, **21 ambient reads against 45**, **1 write against 5**, three surviving entry points
against six. The single write (`memberResolutionTruncated`, (INC.23)'s truncation flag) is
never read back here — a pure OUT channel, so the columns are DISJOINT, which row 7's are
not.

**THERE IS NO CHEAP ABSORPTION HERE, AND THAT IS THE OPPOSITE OF ROW 7 — MEASURED.** Row 7
had seven members with no other caller in `Checker.kt`, so absorbing them was mechanical.
Here it is **ZERO of 22**: the scarcest are `isLibSymbolForCensus` (2 other callers),
`memberResolutionTruncated` (4), `resolveBaseTypesLazy` (5); the densest are
`getTypeOfExpression` (372), `getTypeFromTypeNode` (347), `getTypeOfSymbol` (243). Shrinking
this row means extracting the NEIGHBOURS — the member-NAME family at `Checker.getMemberName`
and Stage 3's symbol typing — not tidying it.

**THE RECEIPT IS NOW TRANSITIVE ACROSS THREE BINARIES.** The same **488 deterministic
`--passTiming` lines** — all 420 per-pass rows, the 46 diagnostics, the emissions census,
the counter block, globals lookups — are byte-identical for pre-step-5 pristine, step 5 AND
step 6a, i.e. **one receipt now covers 1,882 moved lines**. It cost no extra build: the
step-5 capture taken earlier in the session IS this round's pristine arm.

**A NEW AND SHARPER FORM OF THE JVM-NAME-MANGLING TRAP, HIT TWICE IN ONE RUN.** Rows 5/6
record that `internal` adds a `$<module>` suffix so a receipt grep for the SOURCE name reads
zero rows. This round shows the worse case: **widening a member to `internal` AS PART OF THE
SPLIT mangles a site a PREVIOUS round's receipt was reading**. `getTypeOfExpression` — one
of § 10's three standing hot sites — went `private` → `internal` here, so the unmangled grep
reads **574 rows in the before-arm and 0 in the after-arm**, which reads exactly like a site
that stopped being compiled. Grep BOTH forms across any round that widens visibility, and
say which one you used.

**THE SPLIT IMPROVED INLINING AT THE HOT LOOKUP FOR THE FOURTH ROW RUNNING**:
`resolveStructuredTypeMembers` (244 call-site lines) was refused `too large` at **189**
sites as a `Checker` method (`239 inline + 61 inline (hot) + 189 too large`); the hop reads
`233 inline + 55 inline (hot)` with **ZERO refusals**. ab-interleaved 6 pairs **+38 ms
(+0.15%) B-wins-3/6 NOISE-DOMINATED**, both arms 46 errors; `new MemberResolver` at exactly
ONE bytecode in the module. Recorded honestly: **`checkArgumentsAgainstSignature` — the only
standing site (P18.56) proved stable — DID move** (`4 inline + 1 too large + 3 hot-too-big`
→ `6 + 1 + 5`), which is what 19 visibility widenings in one commit do to a caller's inline
tree.

**4 pins, 3 arms, and ONE ARM IS DEAD BY CONSTRUCTION — recorded, not counted as coverage.**
`MemberResolver.resolutionResidue` is row 7's `recursionResidue` one seam over. Arm b1
(delete the `finally`'s `memberResolutionInProgress.remove`) reddens both residue pins; arm
b3 (make the B202.1 break never refuse) reddens the heritage pin and **its failure message
is the mechanism verbatim** — the compile answers ONE diagnostic, `TS2589 … at (0,0)`, i.e.
`reportCheckerStackOverflow`'s signature, and the real circular-base row is gone. **Arm b2
changes nothing**: `mrProbeDepth` only moves under `PassTiming.detailed`, which no test
enables, so that term of the residue is unpinned by any test and is carried by the
`--passTiming` receipt instead.

**NEXT**: step 6b, the member-NAME / late-binding family (`getMemberName`,
`declaredMemberName`, the computed/late-bound key machinery from `Checker.kt` ~118017 to
~118657, ~640 lines) — the seam this round deliberately left whole, and the one that most
improves row 8's DECLARATION-READING group.


### Round (P18.56) — (INV.0) step 5: the RELATER becomes `Relater.kt`, and the region's ~6,390 lines are only 1,256 of algorithm (2026-09-09)

**Suite 18,489 / 0 / 3** (18,484 + 5 new pins). `Checker.kt` **198,022 → 196,797** (−1,225);
`Relater.kt` **1,446**. cost_gate exit 0, huge_methods exit 0 (**836** classes, `Checker.<init>`
5,634 → 5,675), build warning-clean, ledger row 7. Two commits: `579092d93` the split,
`03d455241` the ablation record.

**THE CENSUS THE QUEUE ITEM ASKED FOR CHANGED THE SHAPE OF THE WORK.** The item named seven
entry points spanning 925 lines inside a ~6,390-line region and said the round's first job was
to find out which of the ~5,400 intervening lines are the relater. **They are not**: the
algorithm is **1,256 lines in five contiguous spans**, and the remainder is ELABORATION —
`getPropertyElaborationChain` (546), `getFunctionMismatchElaborationWorker` (407),
`checkExcessProperties` (231), the no-overlap family, the array/object-literal checks. Those
answer *what do we SAY about the failure*, a different seam from *does it relate*.

**AND IT WAS DELIBERATELY NOT SPLIT, WHERE 4b WAS.** 4b had two independent families and a
natural seam; this is ONE mutually-recursive algorithm (`checkTypeRelatedTo` →
`structuredTypeRelatedTo` → `objectTypeRelatedTo` → `signaturesRelatedTo` →
`signatureRelatedTo` → back), so any mid-recursion cut puts a `Checker` hop inside the
compiler's hottest recursion and inflates the ambient row for no verification benefit — the
verification cost is identical at 600 lines and at 1,256.

**THE DELEGATION SURFACE IS SIX, NOT 363.** `checkTypeRelatedTo` has 329 surviving call sites
and every one is byte-unchanged behind a one-line private hop; eight of the fourteen moved
functions have zero callers left and get none. Public surface 6 = `relater.` references 6 by
construction (7 = 7 with the test seam).

**THE RECEIPT PROTOCOL NEEDED A FIX, AND IT IS THE ROUND'S MOST REUSABLE FINDING. The
`--passTiming` pass table is printed in DESCENDING WALL-TIME order, so its row ORDER is a
timing artefact even though every column the receipt keeps is deterministic** — the first
comparison read **804 diff lines between two binaries that are in fact identical**, all of it
equal-count rows shuffled by ms. Sorting the pass rows and dropping every line carrying a
millisecond figure or a TIME-BUCKETED count (`narrowWalk cost distribution`, `huge(>=1ms)`,
the per-kind ns/node table) leaves **488 deterministic lines byte-identical between rebuilt
pristine HEAD and the split** — all 420 per-pass rows, the 46 diagnostics, the emissions
census, the counter block, the globals-lookup line — with a SAME-BINARY control confirming
the normalisation is not merely hiding everything. `scratchpad/pt_norm.py`.

**A SECOND MEASUREMENT LESSON: cost_gate's ±2% column is NOT a statement about the change.**
Six counters read non-zero deltas (`typeOfExpr.calls` +13, `narrow.walks` −4, …) and
**pristine HEAD reads exactly the same deltas** — `docs/perf/cost-counters.txt` is simply
stale by a few rounds. A pure split must be graded against a REBUILT pristine, never against
a recorded baseline; the gate is the control.

**A THIRD STANDING HOT SITE IS UNSTABLE ACROSS PROCESSES.** Row 4 showed
`getTypeOfExpression`'s `PrintInlining` row is not stable; running arm A TWICE this round
shows `isTypeAssignableTo` is not either (`4 inline + 4 inline (hot)` vs `4 inline + 2 inline
(hot)` on ONE binary), so only `checkArgumentsAgainstSignature` — byte-identical across A's
two runs — can separate arms here. It moved by one row in each direction with the refusal
COUNT unchanged. **And the split IMPROVED inlining for the third row running**: as a monolith
`checkTypeRelatedTo` was refused `too large` at **344** sites and its hop now reads `294
inline + 38 inline (hot)` with ZERO refusals; `isSimpleTypeRelatedTo` went from `24 inline +
9 too large + 13 hot-method-too-big` to a hop with no refusal at all. ab-interleaved 6 pairs
**−11 ms (−0.04%) B-wins-3/6 NOISE-DOMINATED**, both arms 46 errors. The § 10 allocation
receipt was taken STATICALLY and is sharper than JFR for the claim it makes: **`new Relater`
appears at exactly ONE bytecode in the whole module**, in `Checker.<init>`.

**ONE PRODUCTION MEMBER WAS ADDED ON PURPOSE — a named test seam.**
`Relater.recursionResidue` sums the three comparison stacks, `relProbeDepth` and
`checker.relationDepth`; `RelaterTest` asserts it is 0 after a whole-program check.
B202.3's finally-hygiene is otherwise unpinnable: a stale `(source.id, target.id)` key does
not fail, it makes every LATER comparison of that pair answer `true` through the cycle break,
i.e. it deletes diagnostics in whatever file is checked next — invisible to the corpus, to
`cost_gate.py` and to a `--listAll` diff alike. **`relationDepth` is in that sum because it
is the ONE counter this class must not own**: `resolveGenericPropertyType` gates INV.5(d1)'s
2,000-computation budget on `relationDepth > 0`, so tidying it into a private `Relater` field
— the obvious refactor, since the other four counters ARE owned — silently re-opens the
deep-generic blowup.

**5 pins; 5 arms; TWO PINS MEASURED UNDISCRIMINATED AND RENAMED RATHER THAN CLAIMED.**
Dropping the comparison-stack pop (a1), the `relationDepth--` (a2) or the two target-stack
pops (a3) from the `finally` each redden the residue pin and nothing else; flipping
`REL2_ENUM_TO_MEMBER` (a5) reddens its own control. **A leak DETECTOR cannot work** — the
`Relation` cache is probed ABOVE the comparison stack, so for an identical pair it answers
before a stale key is consulted, and a1 leaves that pin green. **And the `isDeeplyNested`
bail is not the only bound** — arm a4 disables it entirely and the expanding generic pair
still terminates, because `maxRelationDepth` at 100 is a second sufficient ceiling; the two
are a round-927 pair.

**THE AMBIENT ROW IS THE LARGEST OF THE ARC — 45 reads, 5 writes — AND THAT IS THE DESIGN'S
OWN PREDICTION, not a regression**: § 6 puts `getTypeOfSymbol`/`getTypeOfExpression` in Stage
3 because "their ambient IS the checker", and the relater sits one step below. **The cheap
next step was measured and the measurement REFUTED the plan the round began with**: the brief
asserted the 16-member enum group had no callers outside the moved region, and a caller
census says only **seven** of the 48 members do — absorbing those takes the reads 45 → 38,
while `enumTypesRelation` (3 other callers) and `enumOfMemberTypeSymbol` (8) belong to an
ENUM seam of their own. The three elaboration WRITES are the opposite kind of debt: a RETURN
CHANNEL that paying means giving the relation a result richer than `Boolean` (tsc's
`errorInfo`), which is a semantic change and not Stage 0.

**NEXT**, per `docs/INVERSION-DESIGN.md` § 6 Stage 0's order: **member resolution** — the
8-member group this row names is a seam of its own and is what most improves row 7. Then
signatures and flow; `getTypeOfSymbol`/`getTypeOfExpression` stay Stage-3-shaped.


### Round (P18.55) — (INV.0) step 4 is COMPLETE: `NameResolver.kt` is 2,284 lines and `Checker.kt` lost 1,941 (2026-09-09)

**Suite 18,484 / 0 / 3** (a pure move adds no pins). `Checker.kt` 198,781 → **198,022**;
`NameResolver.kt` 1,418 → **2,284**. **STEP 4 TOTAL: 199,963 → 198,022, −1,941 lines.** cost_gate
exit 0, huge_methods exit 0 (835 classes, `Checker.<init>` 5,656 → **5,634**), build
warning-clean, ledger row 6. Three commits (`da92e5bd3` 4a, `84dd8ad5d` 4b-i, `b353278a1` 4b-ii)
plus `2db1c14ca`, the diffability fix.

**THE AMBIENT ROW GETS *BETTER* AS A FAMILY COMPLETES, AND THIS ROUND MEASURED IT.** 4b-ii
ABSORBS four of the reads the earlier rows recorded — `resolveQualifiedName` and
`ambientModuleSurfaceMember` (row 4), `augmentationContextSymbol` and
`moduleLocalContributesGlobally` (row 5) — because those functions now live inside the
collaborator, so the calls stop crossing the boundary. Net: **26 distinct checker reads, NO
writes, for 2,284 lines.** The reusable form: **an intermediate row's ambient count is the WORST
that family will ever look**, so the ledger should be read by FAMILY, not by row — and splitting
a seam across commits temporarily inflates it, which is a cost of decomposition worth stating
rather than hiding.

**THE CONSTRUCTOR-INPUT TRAP BIT A THIRD TIME AND COST NOTHING, BECAUSE THE PREVIOUS ROUND WROTE
THE RULE DOWN.** Six fields this step reads (`umdGlobalNames`, `moduleFiles`,
`mergeSharedKeepNames`, `lexicalBlockScopedEnumNames`, `QUALIFIED_LEFT_MEANING`, `isDtsFile`) are
declared BELOW the construction site at `Checker.kt:666`, where a constructor input captures
**null**. All became ambient reads. That is the difference between a rule recorded in the queue
item and a rule re-derived per round.

**A THIRD JVM-NAME-MANGLING MECHANISM.** Beside `internal`'s `$<module>` suffix (found last
round), **`SymbolFlags` is a VALUE class**, so `lookupInEnclosingNamespaces` compiles as
`lookupInEnclosingNamespaces-bd7vo6s` and a `PrintInlining`/`javap` grep for the source name reads
ZERO rows. Both mechanisms fail in the direction that reads as "this hop was never compiled";
both are now CLAUDE.md entries.

**RECEIPTS FOR THE WHOLE OF STEP 4, NOT JUST THIS COMMIT.** **All 420 per-pass `--passTiming`
rows and the 46 diagnostics are byte-identical between PRE-4a pristine and the completed step 4** —
one receipt covering all 1,941 moved lines, and a far stronger statement than the gate's 20
aggregates. PrintInlining: **ZERO refusals on every hop** of 4b-ii, and both STABLE standing hot
sites (`checkArgumentsAgainstSignature`, `isTypeAssignableTo`) identical to pristine —
`getTypeOfExpression` is deliberately NOT quoted, since (P18.53) proved it unstable across
processes on one binary. ab-interleaved 6 pairs +39 ms (+0.15%) B-wins-4/6 NOISE-DOMINATED, both
arms at 46 errors. Verbatim proved twice by two methods for the third time running: the agent's
reverse-transform diff, and the orchestrator's independent multiset check whose only unaccounted
lines were 13 signature lines and 75 lines of new class KDoc.

**THE DIFFABILITY FIX PAID OFF IMMEDIATELY**: this commit renders a real text diff (871
insertions / 5 deletions) for `NameResolver.kt` where 4a and 4b-i showed only `Bin … bytes`.

**A STRUCTURAL CHECK WORTH REPEATING FOR ROW 7**: the public surface reconciles EXACTLY — 40
non-private `NameResolver` members against 40 `nameResolver.` references in `Checker.kt` (38
delegations + the two classifier taxonomy reads); the apparent extras in `javap` are the JVM
property accessors and the value-class mangled name.

**NEXT.** Step 4 is done, so the order's tail moves on: `docs/INVERSION-DESIGN.md` § 6 Stage 0
names `getTypeOfSymbol`/`getTypeOfExpression` (Stage-3-shaped — their ambient IS the checker),
the RELATER's algorithm out of `checkTypeRelatedTo` into the `TypeRelationCache.kt` seam row 2
already named, signatures, and flow. The relater is the natural row 7: row 2 put its cache type
in a named file precisely so its extraction would not start from a 198k-line neighbourhood.

### Round (P18.54) — (INV.0) step 4b-i: the PER-FILE LOOKUP core joins `NameResolver.kt`, the item's own hoist is UNSAFE, and the file the arc grows into was UNREVIEWABLE BY DIFF (2026-09-09)

**Suite 18,484 / 0 / 3** (unchanged — a pure move adds no pins). `Checker.kt`
**199,405 → 198,781** (−624; **−1,182 across 4a+4b-i**); `NameResolver.kt` 669 → 1,418.
cost_gate exit 0, huge_methods exit 0 (835 classes, `Checker.<init>` 5,701 → **5,656**),
build warning-clean, ledger row 5. **The full 4b censused at 1,363 lines over 39 functions, so
it was SPLIT** per the protocol's decompose rule; the namespace / qualified-name / heritage
group (~780 lines) is 4b-ii and is what remains of step 4.

**THE ITEM'S OWN INSTRUCTION IS UNSAFE, AND FINDING THAT OUT WAS THE ROUND'S FIRST REAL WORK.**
Step 4 says to hoist `libGlobals`'s declaration above the collaborator's construction site so it
can be a constructor input. `libGlobals` is initialized by `parseBuiltinLib()`, whose SIDE EFFECT
fills `realLibUnknownNames` — a field whose own KDoc records that it is "DECLARED BEFORE
[libGlobals] on purpose — the Kotlin field-init order gotcha". Hoisting that chain reorders lib
parsing against ~9,300 lines of field initialization. `libGlobals` and
`globalAugmentationAddedSymbols` became ambient READS instead (both are declared BELOW line 705,
where a constructor input would have captured **null**). **General rule for the rest of the arc: a
constructor input must be declared above 705, and a field whose initializer has a side effect on
another field cannot be moved at all.**

**THE SPLIT IMPROVED INLINING AT THE COMPILER'S HOTTEST LOOKUP — row 1's finding, on a much
bigger population.** `lookupPerFileForNode` has ~67 callers and ~2M calls per self-compile. As a
monolithic `Checker` method C2 refused it at 57 sites (`4 inline (hot) + 57 too large`); the
9-byte delegation hop now reads **`57 inline + 41 inline (hot)` with ZERO refusals**, with the
body's own rows unchanged. `globalsForFile` is the same shape. **A receipt-reading trap came with
it: Kotlin mangles an `internal` member's JVM name with a `$<module>` suffix**, so a
`PrintInlining` grep for the source name reads ZERO rows for exactly the three hot hops that
matter — which reads as "the hop was never compiled" rather than as a bad grep.

**THE FILE THIS WHOLE ARC GROWS INTO WAS UNREVIEWABLE BY DIFF, AND NOBODY HAD NOTICED.**
`UNRESOLVED_MODULE_SPEC` holds a literal NUL byte (deliberate and load-bearing — no filename can
contain one). After 4a it sat at byte offset **7,910**, inside the 8,000-byte window git's binary
heuristic scans, so `NameResolver.kt` rendered as `Bin 41763 -> 83630 bytes` with **no line diff**
in `git show` / `git diff` — i.e. commits `da92e5bd3` (4a) and `84dd8ad5d` (4b-i) cannot be
reviewed by diff for that file. `Checker.kt` escapes only by accident, its own NULs sitting at
byte 4.6M. Fixed separately in `2db1c14ca` by escaping both to `\u0000`, and **the receipt is
that the compiled `NameResolver.class` is BYTE-IDENTICAL across the edit** (`cmp` clean, md5
`ac116f49…`), with a diffability probe afterwards confirming the file now renders as text. The two
earlier commits are left alone rather than rewritten: their content was verified by other means (a
reverse-transform diff and an independent multiset check of the moved region).

**RECEIPTS.** Suite 18,484/0/3 with all **17** named invariant gate classes confirmed green;
**all 420 per-pass `--passTiming` rows and the 46 diagnostics byte-identical against PRE-4a
pristine**, so one receipt covers rows 4 and 5 together; cost_gate's counter column identical to
pristine (the ±0.03% shown against `cost-counters.txt` is HEAD's own drift, last rebaselined at
`1917f1ca3`); ab-interleaved 6 pairs −120 ms (−0.45%) B-wins-3/6 NOISE-DOMINATED with both arms at
46 errors. Verbatim proved twice by two methods again — the agent's reverse-transform diff and the
orchestrator's independent multiset check, whose only unaccounted lines were the 15 signatures
whose visibility changed and the new class KDoc.

**AMBIENT ROW: SEVEN reads, NO writes.** Two of the seven are BIDIRECTIONAL pairs and are recorded
as intended rather than as defects: `installGlobalsLookupClassifier` stays in `Checker` (it reads
the walk-scoped `currentFileLocals`) while the taxonomies it classifies against live in the
resolver, and `augmentationContextSymbol` calls `lookupPerFile`/`nodeSymbolOf` back.

### Round (P18.53) — (INV.0) step 4a: the NAME/MODULE RESOLUTION leaf becomes `NameResolver.kt`, and TWO OF THE FOUR § 10 INSTRUMENTS NEED A SAME-BINARY CONTROL (2026-09-09)

**Suite 18,477 → 18,484 / 0 / 3** — 7 pins in the new `NameResolverTest`. `Checker.kt`
**199,963 → 199,405** (−558); `NameResolver.kt` 669. cost_gate exit 0, huge_methods exit 0
(0 over limit, 835 classes, `Checker.<init>` 5,701/8,000), build warning-clean, ledger row 4.
**The owner chose (INV.0) for this session**, which is the WORK ORDER's tail; the (CHK.\*) lane
is parked, and the shrinkage dashboard moves in the right direction for the first time in ~26
rounds.

**THE ITEM'S CENSUS HELD AT HEAD, WHICH IS WORTH SAYING BECAUSE IT WAS TAKEN 8 ROUNDS AND ~5,200
ADDED LINES AGO.** All 17 named leaf functions are single-declaration and intact, no cross-file
caller exists (`CompilerOptions.kt` and `MapCensus.kt` only NAME them in comments), and all six
constructor inputs are declared above the `:702` anchor — so the collaborator can be built before
`init`, which Kotlin's declaration-order initialisation requires.

**TWO DEVIATIONS, BOTH MEASURED RATHER THAN ARGUED.** `resolveExportedSymbolThroughStars` and
`moduleNamedExportsOf` stay in `Checker.kt`: they are 7- and 3-line MEMO WRAPPERS whose computers
are large checker-resident star walks with ~20 call sites outside the moving set, so moving the
wrapper alone buys ~31 lines and costs ~20 delegation hops plus 4 ambient entries.
`ambientModuleFilelessCache` belongs to a 4b function. **The brief predicted "nothing in the
moving set calls either" and was HALF WRONG** — `computeImportedSymbolGeneral` calls
`resolveExportedSymbolThroughStars`, so it became a 14th ambient read rather than a free deferral.

**A THIRD DEVIATION IS FORCED BY THE WARNING-CLEAN RULE, NOT CHOSEN, AND IT IMPROVES THE SEAM.**
`getSymbolTarget` / `setSymbolTarget` / `computeModuleSpecifier` / `computeImportedSymbolGeneral`
have ZERO callers left in `Checker.kt` once the family moves (their 2/16/1/2 sites are all inside
the moved spans), so a `private` delegation would be an unused-member warning. They get no hop —
the LinkStore moved wholesale and **`Checker` no longer names `state.symbolTargets` at all** — and
their visibility was then narrowed to `private`, so the collaborator's public surface (11) and the
delegation count (11) are the same number by construction.

**THE VERBATIM CLAIM IS PROVED TWICE, BY TWO METHODS.** The implementation agent reversed the three
documented transformations and `diff`ed byte-identical against the original 593 lines; the
orchestrator ran an independent MULTISET check whose only unaccounted lines are the 15 signatures
whose visibility changed and the new file's header and KDoc. Neither is an argument.

**THE ROUND'S REUSABLE FINDING: TWO OF THE FOUR § 10 INSTRUMENTS CANNOT BE READ AT ONE RUN PER ARM,
AND LEDGER ROWS 1 AND 3 QUOTE ONE OF THEM WITHOUT A CONTROL.**
(i) The counter receipt is *stronger* than `cost_gate.py` and should be the standard for a split:
**all 420 per-pass `--passTiming` rows and the 46 diagnostics are byte-identical against a rebuilt
pristine HEAD** — a claim about every registered pass, where the gate speaks for 20 aggregates.
(ii) The ONLY section of that output which moves is the **node-kind histogram**, and the SAME BINARY
run twice moves it MORE — 70 differing lines A-vs-A against 64 A-vs-B, `Identifier` reading
375,438 / 373,363 on one binary against 378,748 on the other. That is the `+=`-from-the-crawl-workers
race CLAUDE.md already documents, arriving in a channel nobody had diffed before; read as a treatment
effect it is a fabricated regression.
(iii) **`getTypeOfExpression`'s PrintInlining row is NOT stable across processes.** Arm A read
`1 inline (hot) + 372 too large` and arm B `382 too large` — a plausible-looking regression at a
standing hot site — and arm A's SECOND run read `382 too large`, i.e. exactly arm B. Rows 1 and 3
record that site as "row-for-row identical across arms"; that claim was made without this control.
The other two standing sites ARE row-identical here.
(iv) Every delegation hop C2 compiles reads `inline` or `inline (hot)` with **zero** refusals
(`resolveAlias` 28/4, `resolveModuleSpecifier` 20/5, `resolveAliasTarget` 6/4,
`resolveImportedSymbolGeneral` 3/4); ab-interleaved 6 pairs −152 ms (−0.57%) B-wins-2/6
NOISE-DOMINATED with both arms at 46 errors; JFR shows `NameResolver` is never an allocated TYPE
(constructed once per `Checker`, as § 10 requires) with symmetric sampler tails (37 types only-in-A,
43 only-in-B) and counts that do not separate the arms (A 2,527/2,626 vs B 2,737/2,534).

**THE TEST IS NOT THE ONE THE ITEM ASKED FOR, BECAUSE THE FUNCTIONS ARE NOT PURE.** Row 3's
`createTypeMapper` precedent (a file-level pure function pinned without a checker) does not transfer:
the specifier ladder closes over `fileResults` and `options`. What DOES transfer is the intent, so
`NameResolverTest` builds a `NameResolver` with an **EMPTY** `globals`, empty `moduleResolutions` and
an empty symbol-target store — an edit that reaches for checker scope state from the specifier ladder
fails there rather than silently deepening the ambient row. All eleven named invariant gate classes
were additionally confirmed GREEN in the gating run rather than assumed.

**ARMS — 3, all discriminating uniquely.** a1 (drop the `UNRESOLVED_MODULE_SPEC` mapping) 2 RED,
the sentinel pin uniquely plus the js-aware pin as collateral once the memo is poisoned; a2 (drop the
`.js` strip) 1 RED uniquely; a3 (drop `normalizePath`) 1 RED uniquely. **The remaining four pins are
positive controls and are recorded as such, not claimed as coverage** — plain relative resolution, the
two-rung division of labour, memo stability on a HIT, and the absent-file negative control. The
sentinel pin is the one worth keeping: a miss is memoized as a sentinel and mapped back to null on
READ, so "simplifying" it returns the sentinel STRING as a resolved file name **from the second ask
onward only**, which no single-ask pin can see.

**NEXT: step 4b**, the scope side (~1,270 lines). Censused in the same session against HEAD: the
combined 4a+4b move is **1,870 lines**, and several of 4a's fourteen ambient reads DISAPPEAR once 4b
lands, because the two halves call each other — which is why the item's two-commit order is right and
why 4a's row is the worst it will look.

### WORK ORDER (owner directive 2026-09-01) — PHASE 18: TypeScript for the JVM and Kotlin

**RESTORED 2026-09-08 ((P18.44)).** This section was added by `cc09770a3` and was archived out of
the file with the four COMPLETED items that sat under it ((LIC.1), (DOC.1), (EXT.1), (LSP.1)),
leaving CLAUDE.md § "Execution protocol" pointing at a heading that did not exist for ~25 rounds.
Text below is verbatim from `cc09770a3` minus those items; the dated addendum at the end records
where the arc actually stands. **A completed item may not be archived without checking whether a
load-bearing note travels with it.**

**THE PROJECT IS RE-POINTED.** The JetBrains WebStorm evaluation paused: their need was a
post-hoc TYPE ORACLE with the query shape of tsgo's `tsc/internal/api/proto.go` (142 methods),
and this checker cannot serve one — its answers are functions of walk-scoped state, which
`CheckedProgram.kt` and `TypeCapture.kt` already document. tsgo is the free, official default;
competing with it on "a TypeScript compiler" is not the mission. **The mission is TypeScript
for the JVM and Kotlin**: no Node and no Go in the toolchain, an embeddable whole-program
checker, a Kotlin-externals generator with resolved types, a JVM bytecode backend (KIR), and an
LSP anyone can try in five minutes. See CLAUDE.md § "AI agent mission" for the full directive
and the pre-approved Guardrails (two new modules, no new dependencies, `docs/reposition` branch
for README positioning text).

**THE (INC.\*) FAMILY IS CLOSED (closing note).** It ran ~93 rounds and took the incremental
floor from ~1,219 ms ((INC.3)) to **94-110 ms** ((INC.72b)/(INC.89)) at 2,401 files, with the
plugin's own `diagnosticsOf` query at **93-217 ms** independent of edit shape ((INC.90)).
Nothing above — externals generation, the LSP, the inversion — changes outcome at that scale,
and the one real remaining gap ((INC.90)'s signature-edit cliff, 12.8x) was refused on
SOUNDNESS by (INC.91)'s own census. **REFUSE a further (INC.\*) round unless a plugin-facing
query is measured > 300 ms warm**, and do not touch `Checker.kt` for latency without that
measurement in hand. The remaining unchecked (INC.\*) items below stay as a RECORD, except
(INC.92)/(INC.93), which remain live as CORRECTNESS items (process-global state under the
plugin's N-thread shape), not latency ones.

**Work order for this arc, top to bottom:** (LIC.1) → (DOC.1) → (DOC.2, on `docs/reposition`)
→ (EXT.1…n) → (LSP.1…n) → (INV.D) → (INV.0).

**ADDENDUM 2026-09-08 ((P18.44)) — WHERE THE ARC ACTUALLY STANDS, AND THE STANDING QUESTION.**
(LIC.1), (DOC.1), (DOC.2), the whole (EXT.\*) ladder, the whole (LSP.\*) ladder and (INV.D) are
CHECKED OFF; `docs/INVERSION-DESIGN.md` exists. **So the order's tail — (INV.0) — is what it
points at next**, and it is unchecked (steps 1 and 4 both below). What has actually happened for
~25 rounds instead is the **(CHK.\*) checker-parity arc**, which the order never names: it was
surfaced by the externals and library-readiness probes, it serves the "embeddable whole-program
checker" leg, and every round of it is measured against both references — but STATUS.md's own
shrinkage dashboard records that it **ADDED ~5,200 lines to `Checker.kt` and made zero
extractions**, which is the metric (INV.0) exists to move. **The owner was asked on 2026-09-08
and chose to CONTINUE the (CHK.\*) lane**; that decision is scoped to that session. A round that
picks a (CHK.\*) item over (INV.0) is not violating the order, but it SHOULD say so and name its
successor — and the moment the parity arc stops paying in measured reference rows, (INV.0) is
where the order sends you.

- [ ] **(CHK.97) STAGES 1 AND 2 LANDED 2026-09-06 ((P18.29)/(P18.30) notes). STAGE 2 closed THREE of its five
  deliverables — tsc's ARRAY FALLBACK (checker.ts:15949, derived from the RECEIVER because a method type has
  no parent symbol here, which costs one extra CALLABLE gate a signature-list route would not need), tsc's
  `getIntersectedSignatures` (:33085 — `combineUnionParameters` + one `intersection` flag, wired through
  `callableSignaturesForCtx` and `cpaComputeArgCtxTypes`), and the OPTIONAL-call result (`f?.()` answers the
  combined return `| undefined`). Matrix 55 → 61 of 73 pristine rows, ours-only unchanged at 3 (display
  only); 16 pins, 11 arms (9 discriminating, a2/a10 recorded as REDUNDANT GUARDS on every reachable shape
  and kept as tsc's own rules); corpus 8,837/0, grid 8×0/0, `cost_gate.py` exit 0 with no rebaseline.
  **STAGE 3 STAYS OPEN**: (1) `getCallSignaturesOfType`'s own union arm — **it needs a `?: concatenation`
  tail**, since a plain replacement makes every REFUSED union answer `emptyList()`, i.e. "not callable", and
  no measured row moves without it; (2) r09 (a both-overloaded union is silent where tsc reports TS2349) —
  **the item's claim that retiring the `≥2` suppression fixes this is MEASURED FALSE**: the `differ` branch
  below it returns silence for any non-generic differing pair ((CHK.94)'s rule), so there is a SECOND
  suppression, and making r09 report needs `differ` to separate "combinable" from "no combination possible",
  which would also fire wherever OUR combination refuses and tsc's succeeds; (3) TS7006 for a union
  contextual type of DIFFERING signatures (tsc's `getContextualSignature` answers undefined — (CHK.98)
  territory; the arity walker records `typed = true` with no type); (4) `f?.(1)`'s ARGUMENT check (the
  round-408 pre-pass consumes the call); (5) generic INFERENCE through a combined signature
  (`(number[] | string[]).map` → `any`); (6) `this` parameters / TS2684 (`Signature` has no `thisParameter`).
  **FORM residues**: our union member ORDER (`.slice` prints `number[] | string[]` where both references
  print `string[] | number[]`) and an intersection of function types printed without parentheses.
  **A THIRD PRE-EXISTING FINDING**: our `identityRelation` is LENIENT for a function type nested in a
  signature parameter (`(value: 1, …) => unknown` ≡ `(value: 1 | 2, …) => unknown`), which makes PASS 1
  match where tsc's does not and is what keeps the array fallback out of tuple-union `.filter`; a PLAIN
  `1` vs `1 | 2` parameter compares correctly. ORIGINAL STAGE 1 SUMMARY: `combineUnionSignatures` (PASS 1 + PASS 2,
  memoized by the union's `Type.id`) feeding both return readers, a new construct arm, the ccet hand-off to
  the ordinary argument gate, and the nullish check moved above the union branch; the matrix goes 23 rows
  with 6 wrong → 58 of 73 pristine rows matched, ZERO ours-only; 42 pins, 12 arms all discriminating; no
  double emission (all rows from `checkSpine` — the two dedicated rest-TUPLE walkers run BEFORE the hand-off
  and a reordering re-opens it); corpus 8,837/0, grid 8×0/0, `cost_gate.py` exit 0 with no rebaseline.
  **STAGE 2 STAYS OPEN**: (1) tsc's ARRAY FALLBACK (checker.ts:15949) for `(A[] | B[]).filter/map/forEach`
  where both members are overloaded so PASS 2 bails — needs a receiver-level or member-symbol-parent route,
  `unionOfArraysFilterCall` must stay green, r15's four rows are the target, and landing it also retires the
  `≥2` suppression, which is what makes r09 report TS2349; (2) `getCallSignaturesOfType`'s own union arm
  (still the concatenation — only the one call site reads the combined list); (3)
  `cpaComputeArgCtxTypes` / `callableSignaturesForCtx`, so a callback argument to a union callee stops being
  `any` (r23's TS7006, r24), then tsc's `getIntersectedSignatures` (parameters UNIONED) as its own rule,
  graded with a wrong-typed USE inside the arrow per (CHK.30); (4) the optional-call result — `f?.()` on a
  nullish union keeps `any` rather than the combined return `| undefined` (deliberate: too many exits in
  `getReturnTypeOfCallExpression` to wrap without restructuring, and `any` is the conservative direction);
  (5) `this` parameters / TS2684 (`Signature` at `Type.kt` has no `thisParameter`, `code = 2684` has 0
  sites). **TWO PRE-EXISTING GAPS the round MEASURED and did not fix**: TS2554 does not fire for a
  VARIABLE-typed callee at all (`declare const kk: (a, b) => void; kk("x")` is silent on HEAD — which is why
  the item's "the too-few TS2554 comes free" is false and the arity had to be emitted explicitly), and an
  object argument against an INTERSECTION parameter is swallowed by the argument gate's FP firewall (only
  the `Signature.fromUnionCombination` subset is trusted). ORIGINAL ITEM: UNION CALLEES: tsc's `combineSignaturesOfUnionMembers` — MEASURED 2026-09-06 by
  read-only recon against `7dc50053` (fixtures `scratchpad/chk97/p`, 26 rows × 3 compilers, plus
  five pristine fixtures extracted with `pristine_oracle.py --extract` into `chk97/x`; tsgo 7.0.2
  = pristine 6.0.3 on every row bar two divergences below).** The (P18.25) residue was under-
  described: the call RESULT is `any` for EVERY union callee — including B516's own combinable
  rows — because `getReturnTypeOfCallExpression` (~129656) and `getReturnTypeOfNewExpression`
  (~132636) `return anyType` for a non-`Type.Object` callee before any signature is read (30
  missing TS2322 rows); SILENT where tsc reports: different arity (TS2554, `minArgumentCount` =
  max), rest members (`push(1)` on `number[] | string[]` → `'never'`; `push(3)` on `[1] | [1, 2]`
  → `'1'` — tsc's PASS 1, a clone of `Array<1>.push`, so member ORDER is load-bearing), generic
  members, identical-overload pairs, TS7006 through a non-identical union contextual type;
  WRONG where tsc reports differently: two overloaded members combined by their FIRST signatures
  (TS2345 for tsc's TS2349 — the `≥2`-overload silence at ~157647 sits BELOW the combinable
  branch at ~157552), a false TS2554 off the first overload's arity, the CONCATENATED signature
  list read as an overload set (TS2769 for TS2345/TS2554 — `unionTypeCallSignatures.ts:9/36/42/55`),
  a false TS2351 on every union `new` (the construct branch ~158985 has ONLY `differ → TS2351`;
  20 rows on `unionTypeConstructSignatures`), and TS2349 "Not all constituents…" for tsc's TS2722
  on a `Fn | undefined` callee (tsc checks nullability at checker.ts:37038 BEFORE asking for
  signatures at :37054; ours has no general TS2722 emitter). **ONE HOME**
  `combineUnionSignatures(union): List<Signature>?`, tsc's `getUnionSignatures`
  (checker.ts:14313-14363): PASS 1 `findMatchingSignatures` (a generic member needs an exact
  `compareSignaturesIdentical` match, a non-generic one may partial-match with returns ignored)
  keeps a CLONE of the first member's signature with the member returns UNIONED; PASS 2
  `combineSignaturesOfUnionMembers` (:14447-14483) — only when pass 1 produced nothing and at most
  ONE member is overloaded: the LONGEST parameter list, positions INTERSECTED through
  `reduceIntersectionForWriteType` (NEVER `getIntersectionType` — an object pair falls to it
  inside the reducer, which is what printed r25's `{ p: number; } & { q: string; }` byte-
  identically), a missing position contributing `unknown`, optional iff optional in both, rest
  = `Array<∩ of ELEMENT types>` on the interned array plus the extra-rest tail,
  `minArgumentCount = max`, type parameters from the first with the second's mapped onto them
  (`createTypeMapper` + `instantiateType`), parameter symbols minted with `symbolTypes[id]`
  written at mint (the `instantiateSignature` idiom, `TypeInstantiator.kt:231-238`) and a
  `valueDeclaration` whose `dotDotDotToken` readers keep working, return = `getUnionType(returns)`;
  REFUSED → null when a member has NO signatures (today's TS2349 (a)/(b) stays), when more than
  one member is overloaded (tsc's `-1`), or generic-vs-generic non-identical
  (`unionCalleeGenericSignaturesIncompatible`); plus tsc's ARRAY FALLBACK (:15949-15964): when both
  passes are empty and every member is an `Array`/`ReadonlyArray`/tuple instantiation of one
  member name, answer that member on `Array<union of elements>` (`tupleArrayBase` is the
  instrument for the tuple half) — the honest replacement for round 302's `≥2` silence, what makes
  `(number[] | string[]).filter` legal and `(string | number)[]`. Memoized by the union's
  `Type.id` (INV.5(a) interns unions by member-id list, so the key is exact; memoize null too).
  **Five consumers, each a `return any`/null today**: `getCallSignaturesOfType`'s union arm
  (~160366, the concatenation) and a NEW union arm of `getConstructSignaturesOfType` (~160392,
  empty for any union); `getReturnTypeOfCallExpression` / `…NewExpression` for a `Type.Union`
  callee, falling through to the existing single/overload resolution; `ccetUnionCalleeChecks`
  case (c) (~157511-157690: replace `firstOrNull()` + `combinable` + the rest-tuple branch +
  the `≥2` suppression by "combined == null → today's decision, else hand the combined list to
  the ordinary argument gate" — the too-FEW TS2554 comes free; the dedicated walkers
  `checkTest2RestTupleTypeofUnionCall` ~156748 and B538's `checkCombinedRestParamForOfSpreadCall`
  ~13015 may then DOUBLE-EMIT — `--passTiming`'s emissions-by-pass names both); the `new` union
  branch (~158985); and `cpaComputeArgCtxTypes` ~147732 / `callableSignaturesForCtx` ~39614 for
  callback parameters (a callback's OWN parameter is then an intersection of function types —
  tsc's `getIntersectedSignatures` with parameters UNIONED is a second rule, stage it after, and
  grade with a wrong-typed USE inside the arrow per (CHK.30)). Move the nullish-callee check
  ABOVE the union branch (the round-408 pre-pass at ~157457 already computes the non-nullish
  set; TS2722 is one branch there). OUT OF SCOPE, record: `this` parameters (tsc INTERSECTS them
  and reports TS2684; `Signature` at `Type.kt:318` has no `thisParameter` and `code = 2684` has 0
  sites — `unionTypeCallSignatures5/6`). Cost: one synthesized `Signature` per distinct union
  callee, memoized; union-of-array receivers are 9/13/32/32 declarations on project/services/
  server/harness, `?.(` calls 74-131, `Debug.fail(` 110-159 and `Debug.assertNever(` 62-114 per
  profile (`never`-returning callees matter only where they sit IN a union). **Grid risk**: every
  union callee that answered `any` becomes typed — its arguments checked at every position (a
  rest position must be `Array<∩ of ELEMENT types>`, not `∩ of the array types` — the (CHK.83)
  rest lesson), its result reaching every downstream reader (a `never` member's return collapses
  the union; `void | T` results reach declaration/return readers), and `(A[] | B[]).forEach /
  map / filter / some / indexOf` switching from silence to the combined or fallback signature —
  pre-read `fourslashImpl.ts:1218` (the (CHK.94) harness row, must stay `removed=1`),
  `watchPublic.ts:224`, `types.ts:7401/8161`, `builder.ts:140` (`X[] | readonly Y[]` receivers).
  **Guards** (ACTIVE `.errors.txt`): `betterErrorForUnionCall` (3 × TS2349, the generic refusal —
  must stay), `newOperator` (TS2351 at :38/:42/:46 — must stay), `signatureCombiningRestParameters
  1/3/4/5` (combined rows we already match; 1 and 5-part-2 are the dedicated walkers above; 3/4
  print an object INTERSECTION display, i.e. the reducer's `getIntersectionType` fallback and its
  member ORDER), `unionOfArraysFilterCall` (TS18048 only — the round-302 silence must become the
  array fallback without adding a row); INACTIVE but extractable value pins (no
  `tests/cases/conformance/types/union/` in the clone): `unionTypeCallSignatures` (28 pristine
  rows: TS2554 min=max, TS2345 vs combined params, TS2322 on results, TS2555, TS2769 for both-
  overloaded members), `unionTypeConstructSignatures` (28), `unionTypeCallSignatures4` (2 ×
  TS2554); `…5`/`…6` need the `this` model. **Pins**: the 26-row matrix as `diagnose()` value pins
  (`@useRealLibs` for the `[1] | [1, 2]` / `number[] | string[]` rows so `map`/`push` are real)
  plus the extracted pristine row sets; **arms**: a1 combine off; a2 `getIntersectionType` for the
  reducer (`[1] | [1, 2]`.indexOf(1) and r03 red); a3 min instead of max (r02/r22 TS2554 red); a4
  the rest position intersecting the ARRAY types (r04/r15 `push(1)` → `never` red); a5 the
  `≥2`-in-two-members refusal dropped (r09 red; `betterErrorForUnionCall` stays green — a
  redundant-pair note); a6 the nullish check left below the union branch (r14 red); a7 the
  construct arm absent (r17 + 20 `unionTypeConstructSignatures` rows red); a8 the array fallback
  off (r15 `filter` result red, `unionOfArraysFilterCall` must stay green); a9 a memo keyed by
  member COUNT (a wrong-program arm: two same-sized unions in one file share a signature).
  Reference divergences, pristine honoured: tsgo prints `(1 | 2)[]` where pristine prints `(2 |
  1)[]` for `[1] | [1, 2]`.map's result (member order; ours = tsgo); `unionTypeCallSignatures6.ts:39`
  is TS2741 in tsgo and TS2684 in pristine. All 23 of ours on the matrix come from `checkSpine`
  (one owner, no tail-walker double-emit on these shapes).

- [ ] **(CHK.98) (a)/(b)/(c) LANDED 2026-09-06 ((P18.31) note) — the ccet ARGUMENT reader (TWO `anyType`
  sites, not the one the item named: `ccetEnterFunctionLike`'s `else` AND `ccetObjlitMemberFrame`, which
  additionally had to COPY its shared `localTypes` map), the PROPERTY-ACCESS readers (`cpaAnnotationCtx` /
  `cpaWithAnnotationCtx` at four sites + `cpaExprObjlitMethod`, gated to a NON-union contextual parameter
  type), and the pull's exact arms (Conditional, As/Satisfies, `=`, `this`, REST, the array-literal edge).
  Matrix 99 → 125 rows matched against both references, ours-only 7 → 2; 31 pins + FIVE flipped from
  absence to value (two KNOWN-GAP pins the item predicted, and two residues of EARLIER rounds found by the
  full suite); 19 arms (a12 `refuseTpFnTypes` recorded as a REDUNDANT GUARD); corpus 8,837/0, grid 8×0/0,
  `cost_gate.py` REBASELINED with attribution — an arm disabling only (a) reads exit 0, and (a) owns 83% of
  the `narrow.memoServed` rise and 43% of `mapped.hits`, both cache-HIT counters rising faster than their
  populations because a contextually-typed parameter becomes a NARROWABLE REFERENCE where an `any` one was
  not. **THREE defects the item did not name landed with it**: (CHK.98c) as (b)'s prerequisite, a union-`new`
  refusal for CLASS INSTANCE types ((CHK.73) — this checker types a class value as its instance type, so
  "no construct signature" is an artifact), and a SPREAD-argument bail in
  `checkTs2554ForPropertyAccessCall`. **FIVE of the item's claims are measured WRONG**: an explicitly
  ANNOTATED arrow parameter was equally untyped at the ccet reader (that frame never ran the ordinary
  registrar — not a contextual-typing gap at all); the union gate alone does NOT avoid the knip rows;
  opening (b) to every object literal costs `inlineVariable.ts:102` on three profiles, so the item's
  objlit-METHOD scope is the one that works; the REST arm was innocent of the baseline first blamed on it;
  and **the item's `typeNode.bypassed` +31% memo precondition is refuted — measured +0.22% with NO memo
  built**. **STILL OPEN**: the `NewExpression` argument arm (needs construct-signature plumbing plus
  explicit type-argument instantiation — a half-working arm hands an un-substituted `T` to the argument
  relation, the item's own pre-check hazard); the `pullContextualTypeAt` memo (unbuilt and now
  UNMOTIVATED — do not add a cache over an ambient-sensitive answer without a cost measurement);
  `typeContainsUnresolvedTypeParam`'s `Type.Object` arm (refused — no measured instance, ~30 callers);
  (CHK.98b); TS2556 for a non-tuple spread; and the item's own STAGE 2. ORIGINAL ITEM: CONTEXTUAL PARAMETER TYPES REACH THE *ARGUMENT* AND *PROPERTY-ACCESS* READERS,
  AND THE PULL'S EXACT NON-GENERIC ARMS LAND — MEASURED 2026-09-06 by read-only recon against
  `d98a090d` (`scratchpad/chk98/{m,m2,m3,m4,k}`, 99 rows; tsgo 7.0.2 = pristine 6.0.3 on every
  row, union order included).** (CHK.39) ALREADY typed a callback's parameters for the
  assignability reader and hover (`pullContextualTypeAt` ~148912 + `applyPulledContextualParamTypes`
  ~149074 at `checkFunctionBody` ~102970 and `ctaFnBodyFrame` ~3800): `take((node) => { const
  bad: string = node.kind })` REPORTS on HEAD, and so do function expressions, property-
  assignment arrows, objlit methods, var annotations, return positions, array-literal elements,
  overloaded callees and generic callees (44 of 55 ours-rows) — so CLAUDE.md's "CONTEXTUAL TYPING
  SUPPLIES AN ARITY, NOT A TYPE … BOTH silent" is STALE since 2026-08-25 (rewritten this round).
  The residue is per READER and per SOURCE: **(a)** the ccet frame writes EVERY own parameter
  `anyType` (`ccetEnterFunctionLike` ~2148, `ccetObjlitMemberFrame` ~2160) unless the enclosing
  var carries a `FunctionType` annotation (B246 ~2130), so `take(n => { takeS(n) })` is silent
  at the ARGUMENT reader for 5 of 5 sources (r22 / r25 / s03) — a THIRD apply site (CHK.39)
  never wired; wire `applyPulledContextualParamTypes` after the `anyType` pre-pass there, and
  land a per-node MEMO of `pullContextualTypeAt` with it (the pull is then asked three times per
  function expression; the first two sites cost `typeNode.bypassed` +31%). **(b)** the
  PROPERTY-ACCESS family gets no contextual type under a declaration ANNOTATION (the spine
  anchor ~3077 walks the initializer bare; `cpaCtxAt` has no VariableDeclaration arm — the
  (CHK.39) dead leg), a class property initializer, or an objlit METHOD (`cpaExprObjectLiteral`
  ~148225 `else -> {}` — the body is not walked): r28 / r29 / p07 / p20, the two KNOWN-GAP pins
  in `ContextualParameterTypeTest` (:137, :147). Install `contextualType` at those sites **gated
  to a NON-union contextual parameter type** — measured: (CHK.41) CLOSED the assignment /
  `typeof` narrowing mechanism (CHK.39c) refused on (`if (typeof cfg === "function") cfg =
  cfg(); cfg.files2` reports `string` and `cfg.nope` on the narrowed `{ files2: string; }`), and
  re-running the 15 refused knip rows as annotated-parameter probes AVOIDS 12 of them (ava 3,
  eleventy 3, mdxlint 2, remark 2, yarn 2); the 3 that remain are two OTHER narrowing gaps — a
  nested-ternary predicate narrow (`isX(c) ? [c.extensions?.codegen] : [c]`, graphql-codegen,
  TS2339 on the union) and an optional-chain `typeof` (`typeof c.github?.releaseNotes ===
  'string'` then `c.github.releaseNotes`, release-it, TS18048) — queued below as (CHK.98b) /
  (CHK.98c), after which the union gate lifts. knip on HEAD is 49 rows, none in those seven
  plugin files (checkout `…/a3ddeca4-…/scratchpad/libs/knip-dc7aca5…`, `node_modules` +
  `@types/node` present). **(c)** the pull's missing EXACT arms (`else -> null` ~148960):
  ConditionalExpression (r16; tsc :32553 — the cpa family already passes it through, so the
  property-access reader is right and the assignability reader silent), NewExpression argument
  (`new Promise<number>((resolve) => …)`, tsc :32797), the `=` LHS type (r34, tsc :32259
  `getTypeOfExpression(left)`; the pull refuses `=` per (CHK.40)), `as` / `satisfies` (r36, tsc
  :32809, `as const` excluded), a REST parameter (r18 — `dotDotDotToken` skipped at ~149092; tsc
  `getRestTypeAtPosition` :38522), a `this` parameter (r20; tsc `getContextualThisParameterType`
  :31884 — write `currentLocalTypes["this"]`), and the ARITY walker's array-literal edge:
  `many([x => {}])` is an ours-only FALSE TS7006 because the call-arg edge (~63441) records
  `typed = true` with no TYPE and the array edge (~63344) needs `cur.type` for `elemCtx` — while
  the assignability half types `x: number` through (CHK.40)(a)'s pull. **Pre-check before (a)**:
  a union parameter narrowed by `typeof` and passed as an argument in the then-branch
  (`takeU(x => { if (typeof x === "string") takeS(x) })`) must STAY silent — the ccet argument
  reader's flow narrowing was measured only for function-declaration parameters (CLAUDE.md's
  "live only for a PARAMETER source"); and `typeContainsUnresolvedTypeParam` (~149096) has NO
  `Type.Object` arm, so a callback-TYPED contextual parameter carrying an unresolved TP inside
  its signature is written un-substituted — a latent hazard (a) exposes to the argument
  relation; add the arm. **Gates**: 8-profile grid `added=0 removed=0` (1,456 argument-position
  arrows on the compiler profile — 1,012 single bare param, 354 block-bodied — 3,150 on harness,
  every one gaining a typed parameter at the argument reader; the exposure is the argument
  RELATION over types the assignability reader has judged at grid 0 since (CHK.39); the false-
  positive sources to pre-check with the REAL declarations are a narrowed union parameter not
  narrowed at the argument reader, (CHK.83)'s refused `ArrayLike` / `Iterable` apparent-members
  gap (`forEach(s => f(s))` with `f(x: Iterable<string>)`), and a `Record`-initial `reduce`);
  knip 49 → 49 against a REBUILT parent; `cost_gate.py` with `typeNode.bypassed` accounted; the
  59 active TS7006 baselines (`chk98/active-errors.txt`), the 60 active `*contextual*` baselines,
  and the fixture-selected corpus (every case declaring an arrow / function expression in
  argument or initializer position — (CHK.88)'s method); existing pins to keep green:
  `ContextualParameterTypeTest` 36 (its two KNOWN-GAP absence pins FLIP), `ProjectContextualParamHoverTest`,
  `ImplicitAnyContextualTypingTest`, `ContextualFnMemberParamsTest`, `MapCallbackReturnInferenceTest`,
  `ContextualReturnAnnotationTest`, `ImplicitAnyCtxSourcesTest`, `ContextualParamShadowingTest`,
  `UninferredTpCallbackParamTest`, `CallbackReturnTpParamTest`, `NestedArrowLocalShadowingTest`,
  `OverloadedCallbackArityTest`, `ReduceOverloadContextualSelectionTest`. **Pins**: one value pin
  per SOURCE × READER (a wrong-typed `const w: boolean = x`, a `takeS(x)` argument, an `x.nope`
  member), the r22 then-branch silence and the r26 knip shape as negative controls; **arms**: a1
  the ccet site removed (reddens only the argument pins), a2 the cpa annotation source, a3 the
  objlit-method walk, a4 the union gate dropped (must redden the k3a / k5a-shaped pins), a5 each
  new pull arm answering null, a6 the array-edge type dropped (only the r15 TS7006 pin), a7
  `this` / rest dropped. **STAGE 2, a separate item**: union-of-arrays callees
  (`cpaComputeArgCtxTypes` ~147781 `calleeType !is Type.Object → null` — r04 / r41), `Promise.then`
  / `PromiseLike.then` / any method whose TP DEFAULT names the class TP (`q<R = T>` reads `any` —
  q01 / s01), a union-with-null callback parameter of a CLASS TP (`TypeInstantiator.
  instantiateContextualParamType` descends only a bare fn-shaped `Type.Object`; a Union falls to a
  no-op `instantiateType` → unresolved `T` → skipped), predicate-`filter` S inference (p03,
  archive :918), the real `Visitor<NonNullable<TIn>, TVisited>` alias shapes (s02, silent — safe
  direction), `reduce(cb, {} as Record<…>)` — an ours-only FALSE POSITIVE `acc: string` /
  `acc.nope` TS2339, because the mapped alias reads bare `any` ((EXT.11b)), every overload
  matches, `strictSelect` goes ambiguous and the legacy `candidates[0]` wins (q02; `new Map()`
  initial is fine) — destructured parameters ((CHK.96) stage 2), namespace-import callees
  ((CHK.73)). Also recorded, outside this item: tsc's own `filter(x => typeof x === "string")`
  answers `string[]` (5.5 inferred predicates) where ours answers `(string | Nd)[]` — a
  pre-existing MEANING divergence.
- [x] **(CHK.98b) CLOSED 2026-09-06 ((P18.32) note) — AND ITS DIAGNOSIS WAS WRONG. It is not about
  ternaries and not about the property-access family**: measured on the parent binary, the same union,
  guard and reader fail IDENTICALLY under a plain `if`, a single ternary, `&&` and the nested ternary, and
  narrow CORRECTLY as soon as the guard's target declares one REQUIRED member. The axis is the guard
  TARGET's OPTIONALITY — a weak target (every member optional) did not narrow — and the ternary was
  incidental to the one library site the recon read. The mechanism is a round-480 ASYMMETRY: that round
  gave `missingVsOptionalProvesNotSubtype` to the NEGATIVE guard filter and never to the POSITIVE one, so
  the negative branch was right all along. `narrowByCallPredicateWorker`'s direct-relate arm now keeps a
  member only when it relates AND is not veto'd, mirroring tsc's `getNarrowedType(assumeTrue)`; a vetoed
  member falls to the existing narrow-DOWN arm, so the two arms together are tsc's `mapType`.
  **(CHK.98)(b)'s UNION GATE IS LIFTED** with a three-binary receipt: grid 8×0/0 and knip 51 → 51
  byte-identical, while a third binary (gate lifted, 98b reverted) reads **52** — the extra row being
  exactly the one this item named, which is what proves the gate's population is live and not a vacuous
  green. **The item's knip number 49 is STALE** (a rebuilt parent at `abbd34e7e` reads 51; 49 was the
  pre-(CHK.98) recon commit). 18 net pins, 2 arms (8 RED / 3 RED), `cost_gate.py` exit 0 at +0.00% on
  every counter. RESIDUE, pinned as a KNOWN GAP: a NULLISH union contextual parameter now types correctly
  but the property-access reader emits no TS18048 — a false NEGATIVE, which is why the lift is safe.
  ORIGINAL: NESTED-TERNARY PREDICATE NARROWING** — `isX(c) ? [c.extensions?.codegen] :
  [c]` reads `Property 'extensions' does not exist on type 'GraphqlCodegenTypes |
  GraphqlConfigTypes'` here (knip's graphql-codegen plugin, measured 2026-09-06 as an annotated-
  parameter probe, `scratchpad/chk98/k`): a type-guard CALL as a ternary CONDITION narrows the
  then-operand in tsc and not in the property-access family here. One of the two remaining knip
  false positives that keep (CHK.98)(b)'s union gate closed.
- [x] **(CHK.98c) CLOSED 2026-09-06 ((P18.31) note) — `typeofOptionalChainNonNullish`, tsc's
  `optionalChainContainsReference`. It landed as (CHK.98)(b)'s PREREQUISITE, not beside it: the item
  predicted (b)'s union gate would avoid the knip rows, and measured, this shape has a NON-union parameter
  type and came through anyway. It is also PRE-EXISTING on HEAD — it fires for a plain
  function-declaration parameter, measured arm-free — so (b) only widened its reach. ORIGINAL: OPTIONAL-CHAIN `typeof` NARROWING** — `typeof c.github?.releaseNotes ===
  'string'` then `c.github.releaseNotes` reads TS18048 here (knip's release-it plugin, two rows,
  measured 2026-09-06 as an annotated-parameter probe): a `typeof` guard over an optional chain
  narrows the chain's every link non-nullish in tsc (a `"string"` result cannot come from a
  short-circuited chain). The other remaining knip false positive behind (CHK.98)(b)'s union
  gate.

- [x] **(CHK.99) CLOSED 2026-09-06 ((P18.33) note) — `bindingPatternNames` (new `BindingPatternNames.kt`),
  the checker-side mirror of `Binder.bindVariableDeclarationName`; an `Identifier` answers itself, so it is
  a DROP-IN and the change is provably inert on code with no exported pattern (`cost_gate.py` +0.00% on
  every counter). **FOUR of the item's SIX sites were wrong**: the `typeof NS` line named is
  `spineExSetup`'s expando set (the real site, `isNameExportedFromNamespace`'s tail loop, the item does not
  name at all); the `nsImportTargets` site is a TS4118 decl-emit walker and irrelevant; **`varDecls` MUST
  NOT be changed** (its consumer reads `d.type`, so a registered leaf mistypes an ANNOTATED exported
  pattern — proven by arm a9, and inert on every non-collision fixture, so the first guard pin was blind
  and had to spell the collision out); and "`import * as A; A.p` is `any`" is a general namespace-import
  gap, equally true of a plain `export const`, so it cannot discriminate this fix. Also **TWO lost
  diagnostics closed that the item did not mention** — TS2308 (two `export *` barrels over a same-named
  leaf) and TS2484 (a namespace-scoped leaf in the export-conflict rule). 40 pins across THREE harnesses
  (11 direct `Parser`, 12 `diagnose()`, 17 `-project` through `ProjectCompiler` + a `Vfs` — the cross-file
  half is not expressible in `diagnose()`), every positive one a VALUE pin; 9 arms all discriminating,
  8 controls recorded as non-discriminating and one named a genuine redundant guard. Suite 18,021/0/3,
  corpus 8,837/0, `-project` 865/0, grid 8×0/0 — a CONTROL by an independently re-derived population count
  of 0 exported-destructuring sites on all eight profiles, not by the green. RESIDUES, all pre-existing:
  our TS2484 anchors the EXPORTED name where tsc anchors the LOCAL one (FORM); an ours-only TS2484 under
  TS2395 for a merged namespace pair; `import * as A; A.p` still `any`; `export const { [key]: c }` still
  types its leaf `any` (a (CHK.96) computed-key refusal). ORIGINAL: A DESTRUCTURED `export const/let/var { … } = …` / `[ … ] = …` IS NOT AN EXPORT TO
  ANY AST-DERIVED EXPORT SET — MEASURED 2026-09-06 by read-only recon against `52f386e8`
  (`scratchpad/chk99/r1`: 10 false TS2305 on every named import of a pattern name, through
  `export *` barrels too, a false TS2339 on `typeof NS` for a namespace-scoped pattern export,
  and `import * as A; A.p` typed `any`; both references 0 rows).** The TYPE of a direct named
  import already resolves because `Binder.bindVariableDeclarationName` (Binder.kt:398-419)
  declares every pattern leaf; what misses them is FIVE copies of `(decl.name as? Identifier)`
  over a `VariableStatement` — `getModuleNamedExports` (~56368, the TS2305 set),
  `collectExportedNamesInBody` (~46157), `collectFileDirectExportedNames` (~46186), the `typeof
  NS` merged member set (~38412), the `nsImportTargets` builder (~19767) — plus the `varDecls`
  index (~80944) behind `computeExportedVarDeclThroughStars` (~56266). SEAM: one
  `bindingPatternNames(name): List<String>` (object/array, nested, defaults, rest, holes) at
  those six sites; tsc's rule is binder.ts:3648 / :887-888 (every element of an exported pattern
  is an export). PINS: shorthand, renamed, array, hole, default, nested, `let`/`var`, rest,
  barrel, namespace-import member, namespace-scoped, an in-file read control. POPULATION: 0 on
  all 8 profiles (the grid is a control). CORPUS: the `export*BindingPattern` baselines are all
  INACTIVE (amd/system variants); the two ACTIVE exported-destructuring baselines are single-
  file — the class is structurally invisible to the corpus (same axis as (CHK.29)/(CFG.1)), so
  the instrument is a `-project` fixture. MEANING (false positive).

- [x] **(CHK.100) CLOSED 2026-09-06 ((P18.34) note) — `resolveEnumSymbolForQualifiedPath`, a dotted-path
  container descent mirroring tsc's `resolveEntityName`, with a SINGLE segment delegated VERBATIM to
  `resolveEnumSymbolForDiscriminant` and the answer canonicalized exactly once, so round 425's split-key
  hazard is discharged by construction. `enumPathDeref` hops an import alias to its symbol and an
  `import * as ns` to the target module FILE (a namespace import's members live in the file's locals and
  behind its `export *` barrels). 11 CLI fixtures against both references: **45 rows → 10**, all ten
  reference-agreeing. **THREE of the item's claims are measured WRONG**: "both sides fail" (only the RHS
  readers do — the annotation half is already served by `enumDiscriminantKeysOfType`, and arm a2 reads
  **0 RED over 22 pins**; kept for key-space agreement and RECORDED as a measured redundant guard);
  "expect REMOVED rows" (0 removed on all eight — the 23 profile sites carry NO diagnostic, so the class
  is LOST PRECISION there, not a false positive, and the grid is a control); and the four named readers
  are incomplete (a FIFTH, `enumSwitchKeysFromTypeNode`'s whole-enum arm, owned an ours-only TS2366).
  **The grid's ADDED row was the positive control**: with only the enum change in, harness/server/services
  gained `convertParamsToDestructuredObject.ts:238:21` BECAUSE the discriminant began to narrow — which
  exposed a PRE-EXISTING root defect, `checkPropertyAccessAssignment` having no flow narrowing at all
  where the var-decl/assignment/return readers have had a suppression-only leg since rounds 410/438/456.
  Fixed at the root (verified against both references: the narrowed-to-`A` write is silent and the `"b"`
  twin still reports). 22 pins, 7 arms, no round-927 pair (a6/a7 have DISJOINT red sets); two pins were
  repaired mid-round after reading 0 RED — BLIND, not redundant. Suite 18,043/0/3, corpus 8,837/0,
  `cost_gate.py` exit 0 at +0.00%, grid 8×0/0. **The (P18.27) unblock is only HALF true**: a MUTABLE
  `for-of` head now narrows, but a READONLY head is still silent — and so is `readonly string[]` with no
  enum anywhere, so that gap is INDEPENDENT of the discriminant and must be fixed for the non-enum case
  too. LEFT OPEN: `let EK = NS.K` and an annotated `const EK: typeof NS.K` are refused by design where tsc
  narrows through both. ORIGINAL: A NAMESPACE-QUALIFIED ENUM MEMBER (`NS.K.B`, `Outer.Inner.K.B`, imported
  `FAR.EntryKind.Span`) NARROWS NOTHING — not `===`/`!==`/`==`, not a `switch` case, not through
  a `const EK = NS.K` alias — so every discriminated union declared `kind: NS.K.A` reports a
  false TS2339 on the un-narrowed union and renders it in every message — MEASURED 2026-09-06
  (`chk99/r2` + `r2b`: 9 false TS2339 + 2 un-narrowed displays against 0 in both references;
  flat `K2.B` and a single-segment imported `FlatK.B` are the controls that pass).** The axis is
  qualification DEPTH ≥ 2, not imports (the (P18.27) note is corrected: same-file `LocalNS.K.B`
  fails identically). MECHANISM: the RHS readers `enumMemberKeyOfExpr` (~127119),
  `enumMemberTypeOfExprGated` (~127158), `enumMemberTypeOfExpr` (~127179) and the ANNOTATION
  reader `enumMemberKeysOfTypeNode` (~127203) all take `pa.expression as? Identifier` /
  `qn.left as? Identifier`, and `resolveEnumSymbolForDiscriminant` (~126890) resolves a bare
  NAME only — so both sides fail. SEAM: a `resolveEnumSymbolForQualifiedReceiver(receiver,
  keyNode)` walking a dotted chain (`descendToConstEnum` ~14582 is the shape,
  `resolveNamespaceQualifiedTypeAlias` ~127314 the precedent) feeding all four readers — both
  sides in ONE commit (round 425's split-key hazard). UNBLOCKS widening the cpa/ccet readonly
  for-of head (P18.27) kept narrow for exactly this shape (`takeB(e)` over a `readonly X[]` head
  is `any` at the argument reader today, r2b:6/8/10 — the g4 silence was `any` at the head, not
  narrowing). POPULATION: 23 `=== Pascal.Pascal.Pascal` sites (harness 8, server 8, services 5,
  typingsInstallerCore 2 — `FindAllReferences.EntryKind.Span/Node` ×12,
  `JsTyping.NameValidationResult.Ok` ×8), so the grid CAN see it: expect REMOVED rows on those
  profiles. CORPUS: no ACTIVE baseline carries the shape; the enum-discriminant guard set is the
  (REL.2)/(CHK.85) pin classes. MEANING.

- [ ] **(CHK.101) DELIVERABLE (a) BUILT, MEASURED CORRECT AND *REFUSED* 2026-09-07 ((P18.35) note) — correct on
  20 of 20 reference rows and **+19 to +21 ours-only rows on EVERY profile** for its reader half, +4 to +7
  for its argument half; removed entirely, verified by VALUE (the item's own fixture reads parent = 2,
  final = 2). THREE OTHER shipped narrowing defects closed instead, two of them ours-only FALSE POSITIVES:
  A1 `narrowByLooseNullishEquality` (loose `==`/`!=` against `null`/`undefined` tests BOTH nullish values,
  tsc's `TypeFacts.EQUndefinedOrNull`), A2 `defaultStrippedParamType` (a DEFAULTED parameter does not see
  `undefined` in the body, tsc's `getNonUndefinedType`), A3 the logical-assignment VALUE (`x ??= v` is
  `NonNullable<x> | v`, not the whole declared LHS). 33 pins, 7 arms (a7 a measured redundant guard with a
  mechanism; two pins recorded BLIND because their observing mechanism IS (a)); suite 18,076/0/3, corpus
  8,837/0, `cost_gate.py` exit 0, grid 8×0/0.
  **FOUR of the item's claims are measured WRONG.** "The same pair reports at a declaration and a return"
  held only because the fixture used a UNION target, which `canUseTypeEngine` admits by its own line — for
  a NON-union object target the declaration, return AND assignment readers are silent too (20 reference
  rows against 2 of ours), so (a) is a `canUseTypeEngine` hole at least as much as an argument-gate one and
  the stated seam covers under half of it. The named grid risk (`watchUtilities.ts:611/628`) is NOT the
  real one — both shapes already narrow on the parent. Sub-part (c) is broader than stated (`const y = x!`,
  `const y = x as Pr`, even `const y = x` after a narrowing all read `any`). Only the MECHANISM is right:
  the nullish verdict is decidable without structural completeness, and the built emission matched both
  references exactly — it is the BLAST RADIUS that refuses it.
  **BEFORE RE-ATTEMPTING (a), CLOSE THESE FOUR NARROWING GAPS** (each with a reduced fixture, invisible
  until now precisely because (a)'s diagnostic is what would expose them): (i) a `??=` / `||=` whose RHS
  the walk cannot prove non-nullish (`moduleSpecifiers.ts:418`) — A1/A3 close part of this; (ii) an
  optional-chain truthiness guard does not narrow its RECEIVER (`checker.ts:7735`); (iii)
  `getTypeOfExpression` never flow-narrows, so a `??` OPERAND is read un-narrowed
  (`expressionToTypeNode.ts:662`); (iv) the memoization idiom's final read where the assignment RHS is a
  body-local const typing `any` — sub-part (c), and **13 of the 19 reader rows**. (b) the generic-guard
  constraint fallback was not reached. ORIGINAL ITEM: A NULLISH-CARRYING OBJECT UNION ARGUMENT AGAINST AN INTERFACE OR INTERFACE-
  UNION PARAMETER IS SILENT AT THE ARGUMENT POSITION — `useM(x)` with `x: Pr | BP | undefined`
  against `p: Pr | BP` reports nothing, while the same pair reports at a declaration and a
  return, and `string | undefined` → `string` reports as an argument — MEASURED 2026-09-06
  (`chk99/r3b`: 9 lost TS2345, all `Type 'undefined' is not assignable`; `r3`: 8 more through a
  type-guard callee). The (P18.27) note's residue (3) is INVERTED: `isBP(program)` is a LOST
  diagnostic, not an ours-only one (pristine reports TS2345 there; the g2 reduction changed the
  parameter shape — tsc's real `isBuilderProgram<T extends BuilderProgram>(program: Program |
  BuilderProgram): program is T` sits below `if (!program) return false;` on the profile).**
  MECHANISM: `caasNonSimpleParamChecks` (~164897: `!isArgCheckableType(paramType)`, an interface
  or interface-union is not simple per `isSimpleCheckableType` ~165679) exits with
  `CAAS_CONTINUE` (~165075) for a non-primitive argument before the relation (~165196) is
  asked; `canUseTypeEngine` (~101035) admits the union TARGET, which is why the other positions
  report. SEAM: in `caasNonSimpleParamChecks`, when `argType` is a union carrying Null/Undefined,
  the nullish constituent does not relate to `paramType` and the NON-nullish remainder does,
  emit the standard TS2345 with the nullish chain line — decidable without structural
  completeness because the only failing constituent is the nullish one. (b) the generic guard
  keeps `undefined` in `T` (`isBP<T extends BP>(p: Pr | T)` narrows to `BP | undefined` where tsc
  gives `BP`): `tryInferSingleTypeParamFromArgs` / `tispCheckConstraint` (~130841) lack tsc's
  `getInferredType` fallback to the instantiated constraint (checker.ts:27655-27660) — the
  sibling of (P18.27)'s "no inference site → constraint". (c) a body-local `const x = mk()`
  whose initializer is an object/nullish union is `any` at every reader — (CHK.95)'s scalar set,
  stage 2. GRID RISK HIGH by construction: this reads flow narrowing at a parameter, and
  `watchUtilities.ts:611/628` (`if (!program) return false; … isBuilderProgram(program)`) is the
  shape that must stay silent — the truthiness early-return AND the negated `isArray` guard must
  both narrow at the argument reader; run the grid BEFORE pins. CORPUS: the argument-position
  nullish families (`unionArgOkForOptionalParam` ~165179, `isUndefinedArgForOptionalParam`
  ~165175, the M1.7a optional-param rule ~164445) — run every `*OptionalParam*` / `*NullishArg*`
  / `*Undefined*Argument*` class plus the (CHK.63) classes. MEANING.

- [x] **(CHK.102) CLOSED 2026-09-07 ((P18.36) note) — `substituteOuterTypeArgsInGenericFnObject` and
  `substituteOuterTypeArgsInSignature` made NON-MUTATING (round 465's "mints FRESH objects, never mutates",
  applied to the one member of the family that kept the in-place form), with a signature's own
  `Type.TypeParam`s CLONED when the outer mapper moves a constraint/default and the clone map composed
  under the mapper. 22 pins, every claim in BOTH declaration orders; 5 arms; suite 18,098/0/3, corpus
  8,837/0, `cost_gate.py` exit 0, grid 8×0/0. **THE ITEM'S MECHANISM IS WRONG IN FOUR PLACES**, found with
  an identity PROBE rather than by reading: (1) **the freezer is INV.5(c)'s context-keyed cache**
  (`getTypeFromTypeNodeBypassed` → `state.mappedNodeTypes`), NOT `resolveReferenceMembers`' symbol sharing
  — the item's named seam was never touched, and two instantiations of one interface produce an IDENTICAL
  fingerprint because the target's own `Type.TypeParam` is in scope both times, so **17.39's KDoc
  precondition "rawType is always freshly allocated" has been FALSE since INV.5(c)**; (2) **there is no
  `PropertySignature` node kind in this parser** — `parseTypeMember` builds a `PropertyDeclaration` and the
  arm exists and is reached; (3) the grid is a control but NOT because the shape is unreached — the
  compiler profile makes **6,425 calls, 6,383 minting, over 46 nodes asked with ≥2 distinct argument
  vectors in one compile** (the most-exposed is `lib.es5.d.ts`'s `Array<T>`, asked with 30) — it is a
  control because a frozen member never DECIDES a diagnostic there, and the gate is the both-orders pin;
  (4) **two freezes it does not name** — the `MethodDeclaration` call site freezes a method's fn-typed
  PARAMETER (so "methods are correct" holds only for `m(x: T): T`), and the in-place `tp.constraint` write
  freezes an inner generic signature's constraint, which SURVIVES a fix to the object half. a2/a3 are a
  round-927 PAIR (identical red sets and failure text); **a5 exposes TWO BLIND PINS** — the positive
  method-fn-param pins stay green under it because discarding the mint yields the raw un-substituted `T`,
  one absence replaced by another, so site 2 is gated by its negative control alone. No ambient read added
  to `TypeInstantiator` (ledger row 3 stands at four). LEFT, both pre-existing: a `((x: T) => T) | undefined`
  member read at a PRIMITIVE target is silent where both references report (the `canUseTypeEngine` family,
  sibling of (P18.35)'s finding), and a generic-reference relation failure carries no elaboration chain
  (FORM). ORIGINAL: A GENERIC INTERFACE'S FUNCTION-TYPED PROPERTY SIGNATURE (`interface Box<T> { f:
  (x: T) => T }`) IS ONE OBJECT SHARED BY EVERY INSTANTIATION AND FROZEN AT FIRST TOUCH —
  `Box<string>.f` reads `(x: number) => number` after a `Box<number>` was touched first: a FALSE
  TS2345 on `bs.f("a")`, a LOST one on `bs.f(1)`, and the wrong display everywhere; methods
  (`m(x: T): T`), `v: T` and `o: { v: T }` are correct — MEASURED 2026-09-06 (`chk99/r4b`
  lines 18-20 and `r4c` lines 4/12/14, mirror images by declaration order; `r4d` Map is
  unaffected).** MECHANISM: `resolveReferenceMembers` (~118188-118197) shares the target's
  symbol whenever `instantiateType` answers the same object, and `TypeInstantiator.instantiateType`
  (:116-128) deliberately no-ops every function-shaped `Type.Object`;
  `resolveGenericPropertyTypeWorker` (~112044-112227) has no `PropertySignature` arm (`else ->
  null`), so `propertyTypeOnCarrier` (~125951) falls to the shared symbol; the substitution that
  lands later mutates that shared object in place (the 17.39 `substituteOuterTypeArgs*` family
  ~112111/~112200 — find the interface-member writer with a probe printing the identity of
  `bs.f`'s type at the two reads). SEAM: mint per instantiation — `instantiateTypeFnAware`
  (~172028, round 465, "mints FRESH objects, never mutates") in `resolveReferenceMembers` for
  fn-shaped members, and a `PropertySignature` arm mirroring the PropertyDeclaration one; also
  closes the optional-member `T` leak (`b.f` on `Box<number>` rendering `(x: T) => T`). PINS: two
  instantiations in one file in BOTH orders (a pin in one order is green on the frozen binary),
  call + read + mis-assignment probes, a method control, a `Map` control. POPULATION: 108 fn-
  typed members carrying a bare `T` on the profiles (sys.ts:190/:503, resolutionCache.ts:854,
  builder.ts:539, …) — exposure needs two instantiations with differing args reaching one
  member in one compile; the grid's 46 rows show none, so the grid is a control and the both-
  orders pin is the gate. CORPUS: `Inv5GenericGateTest`, the round-465 `Sel<T>` fixture; grep
  the archive for "instantiateType for Type.Object" before touching the no-op. MEANING (false
  positive + lost diagnostic).

- [x] **(CHK.111) CLOSED 2026-09-08 ((P18.47) note): the false-positive population was **9x** the item's
  count (all FIVE positions for a declared tuple source, not the class property alone), the gain was 10
  rows not 5, and the item's named seam — make the rest slots OPTIONAL — is WRONG rather than incomplete
  (optionality injects `| undefined` into element reads and fixes only half the false positives). The
  working model is the rest member carrying the rest's ELEMENT type plus a separate non-required mark.
  The DISPLAY half landed in full. ORIGINAL: A REST TUPLE CARRIES ITS REST SLOT AS A *REQUIRED* NUMBERED MEMBER, SO
  `[number]` FAILS AGAINST `[number, ...string[]]` OUTRIGHT — a false TS2741 neither reference
  prints, PRE-EXISTING and reproduced on the (CHK.108) parent binary with a DECLARED tuple source
  (measured 2026-09-08, (P18.44); `build/bench/chk108-sub/probe4` line 11).** Four of the five
  target positions are shielded by their element-wise owners and the CLASS-PROPERTY one is not —
  an accident, not a design, and it is what forced (CHK.108)'s rest-tuple exclusion. That
  exclusion is a measured TRADE, not a safety guard: dropping it GAINS 5 correct rows (`[]`
  against a rest tuple at declaration / return / assignment / argument / class property, i.e.
  (CHK.108)'s refused row J generalised) and INTRODUCES exactly that 1 false positive. SEAM: make
  slots at index `>= tupleRestIndex` OPTIONAL in the tuple model, then delete the exclusion (arm
  a7 of (P18.44) is the ablation that re-opens it). The same model bug is also the DISPLAY
  divergence beside it — a rest tuple renders `[number, string[]]` where both references print
  `[number, ...string[]]` — so one fix closes a MEANING row set and a FORM one. RISK: MEDIUM-HIGH
  — it changes the member table of every rest tuple in the program, so the 8-profile grid runs
  before any pin and `ArrayLiteralTupleArityTest`'s five rest-tuple negative controls are the
  regression net. MEANING.

- [x] **(CHK.108) CLOSED 2026-09-08 ((P18.44) note): 11 of the reference's 12 rows, plus a family the
  item never named — a DECLARED tuple source printed TS2741/TS2739/`length` where both references print
  TS2322 with an arity sub-line, so the ELABORATION was half the fix and the item does not mention it.
  The named seam was necessary and NOT sufficient (the contextual type was never installed at any of the
  five positions, and B407 `return true`s). The rest-tuple case is REFUSED with its measurement, and the
  cause is the rest MODEL — (CHK.111). ORIGINAL: A PLAIN ARRAY LITERAL WITH THE WRONG ELEMENT *COUNT* AGAINST A TUPLE TARGET IS
  SILENT — `const t: [number, number] = [1]`, `= [1, 2, 3]` and `= []` are all silent here and
  reported by BOTH references, which force-tuple the literal and print `[number]` /
  `[number, number, number]` / `[]` as the SOURCE (measured 2026-09-07, (P18.38); scratch fixtures
  `chk103b/p7` lines 7/9 and `chk103b/p9` line 2).** MECHANISM: tsc's `checkArrayLiteral(node,
  CheckMode.Contextual, forceTuple = true)` types a literal under a TUPLE contextual type as a
  TUPLE of its elements, so the arity mismatch is a tuple-vs-tuple relation failure; this checker
  types every array literal as `Array<union of elements>` (`getTypeOfArrayLiteral`), so the source
  is `number[]` and the ARITY is not expressible. (CHK.103) stage 2's B87.6b opening does not reach
  it, because that emitter is the ARRAY-source-to-tuple one and a plain literal is tuple-like, i.e.
  element-wise-owned. SEAM: a contextual-tuple form of `getTypeOfArrayLiteral` — the const-context
  path (`constContextTupleOfArrayLiteral`) already builds exactly that tuple and is gated on
  `objLitConstContextOf`, so the ask is a second entry gated on a TUPLE contextual type instead;
  `checkArrayLiteralElementsAgainstTuple` (B407) then owns the per-element half and only the COUNT
  row is new. RISK: MEDIUM — it changes the TYPE of every contextually-tuple-typed array literal, so
  the 8-profile grid runs before any pin, and the const-context tuple's own pins
  (`ArrayLiteralSpreadElementTest`) are the control that the two paths do not diverge. MEANING.

- [x] **(CHK.103) stage 2 — CLOSED 2026-09-07 ((P18.38) note): the residue was NOT a spread question —
  FIVE of the six rows carry no spread and the gap was the ARGUMENT reader's FP firewall having no
  array-vs-array gate; the item's named seam covers one row of six. Ours goes 6 → 12 of the reference's
  12 rows on its own fixture, all twelve byte-identical to pristine. ORIGINAL: A LITERAL WHOSE *ONLY* ELEMENTS ARE SPREADS OF NON-TUPLE ARRAY-LIKES
  IS STILL UNCHECKED AT THE ARGUMENT AND TUPLE-TARGET READERS (6 of the reference's 12 rows;
  stage 1 landed (P18.37) and closed the other 6, matching pristine byte for byte).** The
  remaining rows on the stage-1 fixture (`build/bench/chk103-a/f1`): `takeStrArr([...nums])`,
  `takeNumArr([..."abc"])`, `takeStrArr([...st])` and `takeStrArr([...ro])` at an ARGUMENT,
  `const t2: [number, number] = [...nums]` at a TUPLE target, and an INLINE
  `takeNumArr([...tup] as const)` (the same shape through a `const ct = …` VARIABLE already
  reports, and matches pristine). MECHANISM: such a literal normalizes to a plain ARRAY in tsc
  and is therefore NOT tuple-like, so `elaborateArrayLiteral` returns false and tsc reports ONE
  whole-literal row; `checkArrayLiteralElementsAgainstType` here is element-wise only, and stage 1
  deliberately keeps `elaborateSpreadElements` FALSE for that case rather than inventing a row at
  the wrong node (the same gap `const a: [number, number] = [1, 2, 3]` shows, pre-existing). SEAM:
  a whole-literal array-to-array fallback at the argument reader, anchored at the `[` — RISK: it
  is a NEW emitter at a position that currently reports nothing, so the 8-profile grid runs before
  any pin. DISPLAY residue, FORM, do not chase in stage 2: an element union carrying a member the
  spread already contributed renders it TWICE (`("alpha" | "beta" | "beta")[]`) — MEASURED
  IDENTICAL ON THE PRE-(CHK.103) PARENT, i.e. pre-existing and NOT this item's: `literalTypeOfExpression`
  mints a FRESH `Type.StringLiteral` per node and INV.5(a) interns a union by member ID, so two
  equal literals are two members. CORPUS: the 15 ACTIVE `.errors.txt` echoing an array-literal
  spread are all GREEN as of stage 1.

- [x] **(CHK.109) CLOSED 2026-09-08 ((P18.45) note): 1 → 15 of the reference's 16 rows. The item's named
  seam was right and HALF the fix — its own headline shape `({})()` stays silent after it, because
  `calleeObjectTableIsComplete` refuses an empty anonymous object on the TYPE alone and only a SYNTACTIC
  admission can close it; population 3 → 15, and `(class {})()` / `new ({})()` are a different diagnostic
  (TS2348 / TS2351), refused with their mechanism. ORIGINAL: AN *INLINE* LITERAL CALLEE IS TYPED `any`, SO EVERY TS2349 ARM IS UNREACHABLE FOR
  IT — `({})()`, `[1]()` and `({ a: 1 })()` are silent here and reported by BOTH references
  (measured 2026-09-07, (P18.39); scratch `chk104/r2` lines 7-9 and `chk104/r5`).** The type itself
  is fine: the SAME literal reads `{ a: number; }` / `number[]` at a declaration
  (`const z: string = ({ a: 1 })` reports with that display) and through a variable the (CHK.104)
  object arm reports (`const w = ({ a: 1 }); w()`). So the gap is the CALLEE typing path answering
  `any`, and `ccetNoCallSignatureDiagnostics` returns at its `calleeType === anyType ||
  calleeType === errorType` bail (`Checker.kt`, just above the union-callee branch) before any arm
  runs. SEAM: find where a callee expression is typed for `checkSingleCallExpressionTypesCore` —
  `getCalleeType` and the ParenthesizedExpression unwrap around it — and answer the literal's own
  type; the (CHK.104) arms then need no change at all, which is the cheap way to grade it (the
  object arm's evidence predicate already admits a non-empty anonymous object and an array
  reference). RISK: LOW-MEDIUM — it changes what EVERY inline-literal callee resolves to, so the
  8-profile grid runs first; the shape is vanishingly rare in real code, which is also why this is
  small. MEANING.

- [x] **(CHK.104) CLOSED 2026-09-07 ((P18.39) note): 7 → 19 of the reference's 22 rows; the item
  under-counted its population (15 lost rows, not 12) and named NEITHER of the two guards the object
  arm needs — a DUPLICATE IDENTIFIER (`errorElaboration`) and the second-pass dedupe against
  `tryEmitUncallableTypeArgs` (`untypedFunctionCallsWithTypeParameters1`). ORIGINAL: CALLING A LITERAL-TYPED OR OBJECT-TYPED VALUE IS A SILENT TS2349 — `const s =
  "a"; s()`, `const n = 1; n()`, `1n`, `K.A()`, a `"a"`-annotated const or parameter, a template
  literal, `"a" as const`, a body-local, `({})()`, `[1]()` and a `new C()` instance in a const are
  all silent; `let sl = "a"` (`String`), an annotated `string`/`number`, `symbol`, a union and
  `undefined` (TS2722) report — MEASURED 2026-09-06 (`chk99/r6`: 12 lost rows against 19/19 in
  both references).** MECHANISM: `ccetNoCallSignatureDiagnostics` (~158296) has a union arm
  (~158403), an `is Type.Intrinsic` arm (~158452) and a `new X()`-callee arm (~158505); a
  `Type.StringLiteral` / `NumberLiteral` / `BigIntLiteral` / enum-member type and a resolved
  `Type.Object` / `Type.Interface` / array reference without call signatures fall through (the
  "empty Type.Object from incomplete resolution" firewall ~158509). SEAM: extend the intrinsic arm
  to the literal flags (display = tsc's apparent wrapper: `Type 'String' has no call
  signatures.` / `Number` / `BigInt` / `Boolean`; an enum member is `Number` / `String`), and
  admit a resolved object callee only with POSITIVE evidence its table is complete ((CHK.45)'s
  rule: an own-file class instance, an array reference, an object literal with no signatures and
  no index signature — never a lib heritage type or an unresolved member table). GRID RISK: LOW
  for the literal arm, MEDIUM for the object arm (every `x()` on an incompletely-resolved
  receiver is a false positive) — the object half needs the grid before pins. CORPUS (ACTIVE):
  `betterErrorForAccidentalCall` (10 × `String`), `computedPropertiesInDestructuring1{,_ES6}`,
  `methodChainError`, `strictModeReservedWord` (its dedicated walker ~138763 already dedups
  against a spine emission at the same position — keep that), `functionExpressionShadowedByParams`
  / `checkJsFiles_skipDiagnostics` (`Number`), `typeParameterWithInvalidConstraintType` /
  `typeParameterExplicitlyExtendsAny` (`{}`), `callOnInstance` /
  `untypedFunctionCallsWithTypeParameters1` (`C`/`D`) — run by fixture. MEANING.

- [ ] **(CHK.116) THE LAST DEFINITE-ASSIGNMENT RESIDUES, THREE NEWLY MEASURED BY (CHK.115) AND (d)
  CARRIED FORWARD UNCHANGED (2026-09-08, (P18.52); fixtures `build/bench/chk115-sub`).** (a) **STATIC
  BLOCKS ARE FLOW-*ORDERED* AND ONE LEAK SET PER CLASS CANNOT EXPRESS IT** — a READ in one static block
  is wrongly silenced by a LATER static block's assignment (fixtures a3, a10: both references report at
  `4:26`, we are silent). This is a per-block ordering model, not another edge. (b) A static block
  inside a nested `function` does not suppress an outer read (a11: both references report at `5:7`).
  (c) **CARRIED FORWARD FROM (CHK.115)(d), unchanged and still out of scope**: a `switch` with NO
  `default` stays BLOCKED on tsc's `isExhaustiveSwitchStatement` (built and reverted in (P18.40) at two
  ours-only TS2454 on all eight profiles); a catch block with an unassigned CALL stays an ACCEPTED
  PRICE from (CHK.105) (`Debug.fail(…)` and `report(e)` are indistinguishable without the callee's
  RETURN TYPE); and a block round 450's `daWalkStmt` bails on keeps the pre-(CHK.110) removal.
  **EVERY FIXTURE MUST BE FUNCTION-SCOPED** — `SpineDaFrame.enableLeak` is `false` at file level by
  design. RISK: (a) MEDIUM (an ordering model is a new mechanism, not a wiring), (b) LOW, (c) BLOCKED.
  MEANING for (a)/(b).

- [x] **(CHK.115) (a)/(b)/(c) CLOSED 2026-09-08 ((P18.52) note); (d) carried to (CHK.116) unchanged.
  (b) is NOT the parameter-property case — it is ALL FIVE parameter-default spellings plus binding-pattern
  defaults, 9 silent rows not 1; (c) is TWO OPPOSITE mechanisms, since a CLASS decorator answers to the
  LIVE set and a MEMBER decorator to the LEAK; and (a)'s boundary is counter-intuitive — a static BLOCK
  escapes and a static PROPERTY INITIALIZER does not. ORIGINAL: THE DEFINITE-ASSIGNMENT RESIDUES AFTER (CHK.112), THREE OF THEM NEWLY MEASURED
  (2026-09-08, (P18.50); fixtures `build/bench/chk112-sub`).** (a) **A STATIC BLOCK'S *ASSIGNMENTS*
  DO NOT ESCAPE INTO THE ENCLOSING FLOW** — `class C { static { e = "x" } } use(e)` is silent in both
  references and we REPORT (fixture `p1`). (CHK.112) closed the LEAK direction (a sibling closure no
  longer sees a stale unassigned) but the statement read goes through `frame.uninitialized`, not the
  leak, so closing this needs an escape in `markAssignments` — i.e. modelling "a static block runs at
  class-evaluation time". Same class of question as (c) below. (b) A PARAMETER PROPERTY's default,
  `constructor(public p = e)`, is silent — the legacy default-argument drop. (c) A DECORATOR
  expression is silent — an unlisted `spineDaEdge` edge. (d) CARRIED FORWARD FROM (CHK.112),
  unchanged: a `switch` with NO `default` stays **BLOCKED** on tsc's `isExhaustiveSwitchStatement`
  (built and reverted in (P18.40): two ours-only TS2454 on all eight profiles at `checker.ts:38141`);
  a catch block containing an unassigned CALL stays an ACCEPTED PRICE from (CHK.105), because
  `Debug.fail(…)` (returns `never`) and `report(e)` are indistinguishable without the callee's RETURN
  TYPE, and removing the bail makes `catch { fail("boom") }` an ours-only row (measured both ways);
  and a block round 450's `daWalkStmt` bails on keeps the pre-(CHK.110) removal. **EVERY FIXTURE HERE
  MUST BE FUNCTION-SCOPED** — `SpineDaFrame.enableLeak` is `false` at file level by design, so the
  file-level spelling of any of these is silent on a working binary and a broken one alike. RISK: (a)
  MEDIUM (it removes a diagnostic, so the grid is a control and the corpus is the gate), (b)/(c) LOW,
  (d) BLOCKED. MEANING for (a)-(c).

- [x] **(CHK.112) (a) CLOSED 2026-09-08 ((P18.50) note) — 11 missing rows AND **6 ours-only FALSE
  POSITIVES**, the same missing plumbing wrong in both directions. It was TWO mechanisms, not one, and the
  item's own headline fixture is silent for a SECOND reason (a file-level `let` never leaks — every
  fixture must be function-scoped). (b)/(c)/(d) carried forward to (CHK.115) with three newly measured
  residues. ORIGINAL: THE DEFINITE-ASSIGNMENT RESIDUES AFTER (CHK.110), EACH MEASURED AGAINST BOTH
  REFERENCES (2026-09-08, (P18.46); fixtures `build/bench/chk110-sub/f1…f9`).** (a) **THE
  CLASS-PROPERTY-INITIALIZER LEAK PATH IS ABSENT ENTIRELY, AND THE ITEM (CHK.110) NAMED IT AS AN
  EXPRESSION-BODY GAP, WHICH IT IS NOT** — measured pre-change, a BLOCK-bodied arrow, a function
  expression AND a bare identifier in a class property initializer are all silent, so
  `class Inner { g = () => use(e) }` is not fixed by (CHK.110)(b) and needs the leak set carried
  into a class member's initializer. (b) A `switch` with NO `default` — still BLOCKED exactly as
  (CHK.110)(c) recorded: requiring a default costs two ours-only TS2454 on ALL EIGHT profiles at
  `checker.ts:38141`, an exhaustive default-less switch over `node.kind`, so it needs tsc's
  `isExhaustiveSwitchStatement` (a discriminant-union exhaustiveness proof) FIRST and is not a
  `markAssignments` question. (c) A catch block containing an unassigned CALL is suppressed, so
  `try { d = f() } catch (e) { report(e) } use(d)` loses a row both references report — an ACCEPTED
  price inherited from (CHK.105), because `Debug.fail(…)` (returns `never`) and `report(e)` are
  indistinguishable without resolving the callee's RETURN TYPE; removing the bail makes
  `catch { fail("boom") }` an ours-only row (measured both ways). Resolving it needs the callee
  return type at that point, which is the same unblocker (CHK.105)'s own note names. (d) A block
  round 450's `daWalkStmt` bails on (a nested `try`, a labeled statement) keeps the
  pre-(CHK.110) removal. RISK: (a) LOW-MEDIUM, (b) BLOCKED, (c) MEDIUM (it is a callee-resolution
  question, not a flow one). MEANING.

- [x] **(CHK.110) (a) AND (b) CLOSED 2026-09-08 ((P18.46) note); (c) AND TWO NEWLY-MEASURED GAPS MOVED TO
  (CHK.112). NEITHER of (a)'s two named candidates was the suppressor — it is `checkUsesOfUninitialized`'s
  OWN `TryStatement` arm, which walked the try block against the CALLER's live set, and the same line was
  ALSO a pre-existing ours-only FALSE POSITIVE in the other direction. ORIGINAL: THE THREE DEFINITE-ASSIGNMENT SHAPES (CHK.105) LEFT OPEN, EACH WITH ITS MECHANISM
  MEASURED (2026-09-07, (P18.40); scratch `chk105/r5`).** (a) A `try { x = … } catch {}` JOIN —
  `let d: string; try { d = "a"; } catch {} use(d)` is TS2454 in both references and silent here;
  `markAssignments` has NO `TryStatement` arm, so nothing removes `d`, which means the silence comes
  from somewhere ELSE (`checkTryCatchOnlyAssignedVarReads` (B223) and the `spineDa` frame's own
  handling are the two candidates) — attribute before designing. (b) A read inside an
  EXPRESSION-BODIED arrow (`const g = () => use(e)`) is silent while the BLOCK-bodied form
  (`() => { use(e); }`) already reports, so the gap is that a `spineDa` frame is built for statement
  LISTS only and an expression body has none; the B78.2 leak set (`frame.currentLeak`) already
  computes exactly the names to carry in. (c) A `switch` with NO `default` — BUILT AND REVERTED in
  (P18.40): requiring a default costs **two ours-only TS2454 on all eight profiles** at
  `checker.ts:38141`, whose switch over `node.kind` has no default and IS exhaustive, so this one
  needs tsc's `isExhaustiveSwitchStatement` (a discriminant-union exhaustiveness proof) FIRST and is
  not a `markAssignments` question at all. RISK: (a)/(b) LOW-MEDIUM, (c) BLOCKED. MEANING.

- [x] **(CHK.105) CLOSED 2026-09-07 ((P18.40) note) for (a) and the `if` join; its "3 lost TS2345"
  rows are measured NOT to be this item's (the TYPE is already exact at the declaration position —
  the ARGUMENT reader for a BODY-LOCAL source is the (CHK.63)-adjacent gap), and its "the set pass
  has no join" names the wrong function (`markAssignments`, not `collectUninitializedVars`). Two
  hand-written pins in this repo were pinning B78.1's measurably wrong CONST-NESS rule and are
  repaired. Residues re-queued as (CHK.110). ORIGINAL: TS2454 IS MISSING FOR THREE SHAPES BOTH REFERENCES REPORT — MEASURED 2026-09-06
  (`chk99/r7` + `r7b`: 16 of 20 rows already agree; 4 + 3 lost TS2454, 3 lost TS2345): a `const`
  used before its declaration in the same container (`const c = x; const x = 1` — TS2448 alone
  here, TS2448 + TS2454 in tsc), a POSSIBLY-unassigned read after a join (`let x: string; if (c) x
  = "a"; use(x)`, a `try { x = … } catch {}`, a closure reading a never-assigned outer `let`),
  and — a sibling lost TS2345 — a `let x: string | undefined;` (no initializer, or `= undefined`)
  is untyped at the argument reader. The (P18.27) note's "TS2454 absent" is corrected: ours
  emits it for every never-assigned `let`/`var`.** MECHANISM: `emitTS2448` (~87371) gates the
  co-emit on `(!isConst || isUnreachableDecl)` — B78.1's rule was read off
  `typeGuardNarrowsIndexedAccessOfKnownProperty10`, whose const is `any`-typed (tsc's
  `assumeInitialized` on `AnyOrUnknown`, checker.ts:31197-31200), NOT const-ness; the set pass
  `collectUninitializedVars` (~24893) has no join (a set, not a flow lattice) and
  `spineDaFlowFileEnd` (~25882) only dedups. SEAM: (a) tsc's predicate — co-emit unless the
  declared type is any/unknown/void, the use is an outer-variable read of an INITIALIZED
  declaration, or the declaration is ambient / `!`-asserted (checker.ts:31172-31205); (b) the
  join: tsc's `getFlowTypeOfReference(node, type, getOptionalType(type))` + `containsUndefinedType`
  — the flow walk already exists, the ask is a `let`/`var` without initializer read through it
  (also fixes the `let x: T | undefined;` argument rows, the same read); keep "TS2563 OR TS2454,
  never both" (~10891). POPULATION: ~6,500 `let x: T;` lines on the profiles — the grid will see
  a join mistake. CORPUS (ACTIVE): `typeGuardNarrowsIndexedAccessOfKnownProperty10` (must STAY
  TS2448-only — the `any` control), `controlFlowFunctionLikeCircular1` (16 × 2454 / 18 × 2448),
  `decoratorUsedBeforeDeclaration`, `forwardRefInClassProperties`,
  `useBeforeDeclaration_destructuring`, plus the 187 ACTIVE TS2454 baselines by fixture. MEANING.

- [x] **(CHK.106) CLOSED 2026-09-07 ((P18.42) note): (b) FIXED (it had MOVED to `BP & BP` vs `BP`
  after (CHK.101), and the dedupe needs an ANONYMOUS-constituent exemption because our interner gives
  two type-literal nodes ONE id where tsc gives two); (a) stays refused and is BROADER than recorded
  (a DIRECT `Fn<number>` annotation loses the name too, not only a carrier member); (c) verified
  closed by (CHK.100); (d) verified a reference divergence, ours = tsgo. ORIGINAL: FORM — display-only residues from the same probes, grouped (2026-09-06).** (a)
  an ALIAS NAME is lost through a carrier instantiation — `Obj<T>` → `{ v: T; }`, `Fn<T>` → `(x:
  T) => T`, `Fn<number>` → `(x: number) => number`, `CreateProgram<T>` (`chk99/r4`, `chk96-impl/g5`):
  `aliasDisplayMap` is keyed by the interned type id of the alias BODY and an instantiated
  reference through a member table never registers; tsc keys the alias on the REFERENCE
  (`getTypeOfInstantiatedSymbol` checker.ts:12873 carries `aliasSymbol` + `aliasTypeArguments`),
  so this is (INC.27)/(INC.29)'s interning-key question, not a table fix — record, do not
  attempt in `aliasDisplayMap`; (b) the generic-guard narrowing display `BP | undefined` vs `BP`
  is (CHK.101)(b) and closes with it; (c) the un-narrowed sub-line displays on `chk99/r2` are
  (CHK.100)'s and close with it; (d) `(2 | 1)[]` (pristine) vs `(1 | 2)[]` (tsgo) for a tuple
  spread — a reference divergence to record when (CHK.103) lands, pin on pristine. Each is form
  per `docs/logical-parity.md` § 2; only (a) needs its own decision.

- [x] **(CHK.107) CLOSED 2026-09-07 ((P18.41) note) — 1 → 6 of the reference's 6 rows; its GATE claim
  is measured wrong (the grid is `added=0 removed=0` on all eight, a CONTROL, not the `removed=1` it
  predicts), and the stage-2 refusal pin is inverted. ORIGINAL: AN ARRAY PATTERN WHOSE INITIALIZER IS A *CONDITIONAL OF ARRAY LITERALS*
  REFUSES, SO EVERY LEAF READS `any` — `const [s, e] = c ? [n, undefined] : [o.pos, o.end]`
  reads `s` and `e` as `any` where both references read `number` and `number | undefined`
  (MEASURED 2026-09-06, (P18.28) defect (c); `services.ts:3264` is the shipped instance, 1 site
  on 3 of the 8 profiles).** The refusal is DELIBERATE and is the safe direction: it is what
  HEAD and stage 1 do, and the two obvious reconstructions are both measured WRONG. Reading the
  conditional's own type gives each branch's un-contextual `(number | undefined)[]`, whose
  element union makes slot 0 `number | undefined` — an ours-only TS2322 on all three profiles.
  Unioning the branches' `arrayLiteralAsDestructuringTuple` (built, measured, reverted) gives
  slot 0 `number | { pos: number; end: number; }`, because the branch elements are typed by
  `getTypeOfExpression`, which NEVER flow-narrows (CLAUDE.md) — `positionOrRange` is `number`
  only inside its own `typeof` guard. MECHANISM: tsc pushes the pattern's implied contextual
  type (`getTypeFromBindingPattern` checker.ts:12433) into BOTH branches
  (`checkConditionalExpression` types each branch against the same contextual type), so the
  source is `[number, undefined] | [number, number]` and the union arm of `bindingElementType`
  then gives `number` / `number | undefined`. SEAM: `conditionalOfArrayLiterals`
  (`bindingPatternSourceType`'s refusal) is the one gate to lift; what it needs is each element
  expression's type AT ITS OWN FLOW POSITION — i.e. `getNarrowedTypeForReference` for a
  reference element, the ordinary type otherwise — so the branch tuples carry the narrowed
  types. PINS: the `services.ts` shape both ways round, a branch that is not an array literal
  (must keep refusing), a nested conditional, and the existing refusal pin in
  `BindingElementStage2Test` inverted. POPULATION: 1 site on the 8 profiles, so the GRID IS THE
  GATE and it is a `removed=1` there, not an `added`. CORPUS: no ACTIVE baseline carries the
  shape (the round's refusal moved none). MEANING (lost diagnostic).

- [x] **(CHK.86) AN IMPOSSIBLE ENUM-vs-ENUM EQUALITY PRODUCES NEITHER TS2367 NOR A `never`
  NARROW — the DIAGNOSTIC half CLOSED (P18.17); the NARROW half re-queued as (CHK.87).**
  `comparabilityCategory` maps a numeric enum to `"number"` and a string enum to
  `"string"` — deliberately, so `k === num` and `s1 === "p"` stay legal — so two enums of
  one flavour read as the SAME category and the rule fell through. The enum question is
  about IDENTITY and is now asked separately, ABOVE the category rule, which also fixed a
  display the category rule was getting wrong (`k === S.P` read `'K' and 'S.P'` against
  both references' `'K' and 'S'`). The display is tsc's `getBaseTypesIfUnrelated`
  transcribed — widen both operands to their enums and print the WIDENED pair only when it
  is still unrelated — which is the single line accounting for `ka === kb` printing
  `'K.A' and 'K.B'` while `ka === jx` prints `'K' and 'J'`.

- [x] **(CHK.87) CLOSED 2026-09-05 ((P18.18) note) — the narrow is decided by (CHK.86)'s
  OWN predicate, so the branch it collapses is exactly the branch that reports; the
  `getTypeOfExpression` it needs is gated behind a flags-and-symbol test and `cost_gate.py`
  reads `typeOfExpr.calls` −0.22%. ORIGINAL ITEM: THE `never` NARROW OF AN IMPOSSIBLE ENUM COMPARISON — a SEPARATE
  mechanism from (CHK.86)'s diagnostic, measured (P18.17).** Both references narrow the
  true branch of `if (k === j)` to `never` and therefore report the TS2367 and NOTHING
  else; we report the TS2367 and still see the un-narrowed `K` inside the branch. The
  diagnostic is decided from the two RESOLVED TYPES in `checkEqualityComparisonNoOverlap`;
  narrowing is decided in `narrowByEquality` from the SYNTAX of the other operand
  (`literalTypeOfExpression` / `enumMemberTypeOfExpr`), which answers null for a bare
  identifier AND for a whole enum, so the walker bails before any enum question is asked —
  and it would additionally have to collapse a NON-union reference to `never`, which is the
  hazard CLAUDE.md records for every `never`-adopting substitution. `enumAtomListsOverlap`
  is the predicate it would reuse. Corpus exposure is the same as (CHK.86)'s and was
  measured EMPTY: no active baseline can carry an impossible enum comparison, because tsc
  emits TS2367 for one and we were green without it.

- [x] **(CHK.88) CLOSED 2026-09-05 ((P18.18) note) — a sibling helper reusing round 745's
  `enumKnownDomainValues`, whose refusal for a *computed* enum is what keeps `declare enum`
  and a non-foldable member accepting every literal; the literal is read off the AST because
  `getTypeOfExpression` answers the base primitive for a literal node. Two display rules
  earned pins: a FLAVOUR mismatch is left to the category rule, and a union covering every
  member prints as the bare enum. ORIGINAL ITEM: AN ENUM MEMBER COMPARED TO A NUMERIC LITERAL OUT OF ITS RANGE — measured
  (P18.17), deliberately outside (CHK.86)'s scope.** `ka === 1` with `ka: K.A` (`A = 0`)
  is `TS2367: … the types 'K.A' and '1' have no overlap.` in both references and is silent
  here, while `ka === 0` is correctly legal in all three. (CHK.86) is gated to BOTH operands
  being enum-flavored precisely so it cannot reach this: the verdict needs the member
  VALUES (`enumDomainValues` / round 745's `numericLiteralFitsEnum`), not enum identity.

- [x] **(PARITY.3) A GENERALIZED NAMESPACE-SCOPED ENUM LOSES ITS NAMESPACE PREFIX —
  CLOSED (P18.17), and it was NOT the same-string-retry mechanism's job.** tsc's
  `reportRelationError` computes the source display TWICE and the difference is the whole
  defect: `getTypeNamesForErrorDisplay` gives a BARE name, and the generalize branch then
  re-renders the generalized source with `TypeFormatFlags.UseFullyQualifiedType`. So the
  SAME type prints `Ns.Inner.I` at a `string` target and `I` at a `never` one, and the
  qualification cannot be keyed on "the type changed" — a WHOLE enum enters the branch too
  (in tsc a numeric enum IS a union flagged `EnumLiteral`, so `isLiteralType` admits it,
  while `getBaseTypeOfEnumLikeType` returns it unchanged), so the observable effect of
  entering the branch is the RE-RENDER alone. Scope deliberately narrower than tsc's: only
  a NAMESPACE chain is walked, so a top-level enum is untouched and a MODULE-scoped one
  keeps its bare name where tsc prints `import("<path>").E` — (P18.14)'s refused mechanism,
  which an AMBIENT-module guard keeps out by name. A GLOBAL AUGMENTATION is refused too and
  for a different reason: `declare global { … }` is not a container a consumer can SPELL, it
  IS the global scope, so both references print its members bare — but the walk STOPS there
  rather than refusing the chain, because a real namespace nested inside the block does
  qualify (`N.NE`, `Deep.Inner.DE`), and the guard is keyed on the `declare` MODIFIER
  because a plain `namespace global` is an ordinary container both references print as
  `'global.GE'`.

- [x] **(CHK.89) DONE 2026-09-05 ((P18.19) note) — the string layer's type-reference
  renderer keeps an enum member's enum (`typeReferenceDisplayName`), wired into BOTH
  [resolveSimpleTypeName] and [formatTypeForDisplay]; the qualifier is an ENUM-MEMBER rule
  and not a qualified-name one (a namespaced INTERFACE prints bare in both references, and
  so does a WHOLE namespaced enum), and the `SymbolFlags.Enum` cascade onto a
  `ConstEnumOnly` NAMESPACE needed a declaration-kind guard the corpus found
  (`enumAssignmentCompat3`). ORIGINAL ITEM: A RETURN-POSITION ENUM-MEMBER ANNOTATION LOSES ITS ENUM QUALIFIER —
  measured (P18.18), PRE-EXISTING and unrelated to that round's changes.** `function f():
  K.A { return kb }` reads `Type 'K.B' is not assignable to type 'A'.` here against both
  references' `'K.A'`, and `function f3(): N.Q.X { … }` reads `'X'` for their `'Q.X'`; the
  DECLARATION twin (`const w: K.A = kb`) is byte-correct, so the divergence is the RETURN
  path's target rendering reducing a `QualifiedName` to its last name. Found only because
  (CHK.85)(a) would have added a row exhibiting it. FORM, and its at-risk population is
  every baseline with a TS2322 at a `return` whose annotation is qualified — enumerate
  before landing.

- [x] **(CHK.90) DONE 2026-09-05 ((P18.19) note) — BOTH halves: [ts2367CategoryDisplay]
  now takes tsc's `getBaseTypesIfUnrelated` base (the widened pair is unrelated by
  CONSTRUCTION at that rule, since widening preserves an operand's category), and
  [comparabilityCategory] answers `"string"` for an ALL-STRING-valued enum's OWN type,
  which is the silence (`s2 === 3`, `s2 === true`). Ten rows on the fixture, byte-identical
  to both references. ORIGINAL ITEM: THE TS2367 *CATEGORY* RULE DOES NOT APPLY `getBaseTypesIfUnrelated` —
  measured (P18.18).** `ka === "z"` with `ka: K.A` reads `… the types 'K.A' and 'string' …`
  here against both references' `'K' and 'string'`, and `ka === true` the same way: the
  (CHK.86) rule transcribes tsc's base-vs-original display and the category rule beside it
  does not, so the two disagree about one operand. Beside it, a SILENCE in the same rule: a
  string enum against a number (`s2 === 3` with `s2: S2`) is `'S2' and 'number'` in both
  references and nothing here, so `comparabilityCategory` is answering null for a string
  enum where it answers `"number"` for a numeric one. FORM plus one missing row; the at-risk
  population is every baseline carrying a TS2367 with an enum operand (16 carry TS2367 at
  all).

- [x] **(CHK.83) CLOSED 2026-09-05 ((P18.20) note) — `const l1: 5 = em` and `fEnum(3)` LANDED: the
  relation's `EnumLiteral` leg relates an enum member to a string/number LITERAL target by VALUE only
  (`enumMemberValueEqualsLiteral`, read through `enumMemberEntries` — the tsc-value view, where an
  ambient opaque or computed member relates to no literal); round 745's literal-source helper is
  widened to string literals and to union targets carrying an enum (`enumTargetLiteralSource`) and
  wired at every position (declaration, assignment, return in both spellings, argument, rest element,
  the overload chain, object-literal members direct/nested/returned); an enum-carrying union display
  appends `null`/`undefined` last. 30 pins (`EnumLiteralValueRelationTest`), 12 discriminating arms,
  one redundant guard retired by its own arm; corpus 8,837/0, core 15,935/0, cost_gate exit 0, grid
  8×`added=0 removed=0`. OVERLOAD-SET unification STOPPED with its mechanism:
  `checkRecursiveFunctionTypes` pins FOUR mechanisms of the `typeof fn` family, not one. Four display
  residues queued as (CHK.92). PREVIOUS STATE: FURTHER PARTLY DONE 2026-09-05 ((P18.19) note) — the `readonly` array
  parameter LANDED (it is the ARRAY kind the round had already admitted, missed only
  because [isArrayLikeType]'s `Type.Reference` leg names `"Array"` alone) and the
  ELABORATION sub-line LANDED at all five union-source chain sites (the constituent now
  takes the same generalization the outer line takes; the PICKER is deliberately unchanged
  because THE TWO REFERENCES DISAGREE about it — tsgo names the first, pristine the last,
  which is ours). RE-MEASURED AND STILL REFUSED: the INTERSECTION parameter kind, which
  reproduces its single ours-only `TS2345` at `harness/src/harness/vpathUtil.ts:106:30`
  (`string` against `string & { __pathBrand: any }`), project and services clean — and it
  additionally needs `relationErrorSourceDisplayType`'s target-kind allowlist, since with
  the gate open a union argument prints `'"a" | "b"'` where both references print
  `'string'`. NEWLY REFUSED WITH ITS MEASUREMENT: the generic `Type.Reference` parameter
  family (`Promise<T>`, `Map<K,V>`, a user `Named<T>`) — its licence FAILS, because
  `const d: ArrayLike<string> = s` and `const d: Iterable<string> = s` are ours-only
  `TS2322` at the DECLARATION position; the unblocker is a primitive's apparent members,
  not this gate. STILL OPEN: the OVERLOAD-SET kind (measured CORRECT for the ordinary
  shape — `fOvl(s)`/`fOvl(u)` match both references today — so what remains is only the
  duplicate-emitter unification), `fEnum(3)` at an argument, and `const l1: 5 = em` (an
  enum MEMBER against a literal of a different value: measured silent at BOTH the
  declaration and argument positions, while the WHOLE enum reports and `const l2: 1 = em`
  is correctly silent, so the relation accepts an EnumLiteral source against a
  non-matching literal target). PARTLY DONE 2026-09-05 ((P18.18) note) — FOUR of the five parameter kinds
  LANDED (ARRAY/tuple, FUNCTION, ENUM, UNION), with four guards, three of which a GATE found
  rather than a reading: a REST parameter (301-401 added rows per profile, the grid), an
  ARITY-mismatched call (`couldNotSelectGenericOverload`, the corpus), an OVERLOAD-SET
  parameter owned by the dedicated `checkRecursiveFunctionTypes` walker
  (`recursiveFunctionTypes`, the corpus, attributed by `--passTiming`'s emissions census),
  and an un-inferred callee type parameter. WHAT REMAINS: the INTERSECTION kind, refused
  with its measurement — on the harness profile `vpath.parse(path)` resolves to
  `getPathComponents`, an overload set whose FIRST signature takes tsc's branded `Path`, and
  `signatureAcceptsArgs` does not ask the question, so opening the gate reported an ours-only
  TS2345 ((CHK.55)'s law); the OVERLOAD-SET kind, which needs its two emitters unified;
  `fEnum(3)` at an ARGUMENT (the literal is typed as its base primitive there, so round 745's
  domain rule never sees a literal, though the DECLARATION `const d: E = 3` reports); a
  `readonly` array and a generic `Reference` parameter (`Promise<number>`, `Map<…>`); and the
  two rows the item names below (`const l1: 5 = em`, the elaboration sub-line). ORIGINAL ITEM: THE ARGUMENT-SIDE REMAINDER OF (P18.14)(c)'s HOLE, MEASURED ((P18.15)) — a
  primitive-only union argument against an ARRAY, FUNCTION, INTERSECTION, ENUM or UNION parameter
  is SILENT here and reported by both tsgo 7.0.2 and pristine 6.0.3.** (P18.14) lifted the
  declaration/assignment/return side of `canUseTypeEngine`'s refusal; the argument gate has its
  own admissibility. Also measured beside it: `const l1: 5 = em` (an enum member against a
  number-literal target) is silent, and the ELABORATION sub-line picks the LAST failing
  constituent ungeneralized (`Type '"b"'`) where tsc picks the FIRST passed through the same
  generalization (`Type 'string'`) — the comment at that site claims it matches tsc and does not;
  its at-risk population is every union-source chain line, so the corpus is its gate. And the
  target-kind allowlist in `relationErrorSourceDisplayType` declines an INTERSECTION, UNION or
  TYPE-PARAMETER target where tsc's `typeCouldHaveTopLevelSingletonTypes` recurses through
  `UnionOrIntersection` and an `Instantiable`'s constraint — widening it is corpus-gated because
  that allowlist is what protects two baselines.

- [x] **(CHK.114) CLOSED 2026-09-08 ((P18.51) note): all three stages landed, plus a PREREQUISITE the item
  did not name (tsc RESTORES the aliased target before reporting — three already-wired heads were silently
  stripping aliases). **(c)'s framing was WRONG**: cross-flavour is not the axis — every collapsing row has
  a ONE-MEMBER source enum, i.e. it is (CHK.92)(d)'s own fact on the source side, not a new rule. Two
  sub-scopes refused with measurements. ORIGINAL: THREE FORM RESIDUES CONFIRMED ON THE *BEFORE* BINARY BY (CHK.113), i.e. PRE-EXISTING
  AND UNCHANGED BY IT (2026-09-08, (P18.49); fixtures `build/bench/chk113-sub`).** (a) and (b) are
  (CHK.92)(c)'s `nullableTargetDisplay` reaching only three of its five heads. (a) **THE RETURN HEAD
  NEVER CALLS IT**: `function q(): string | undefined { return 1 }` prints the target
  `'string | undefined'` where both references print `'string'` — the strip is implemented and simply
  not wired there. (b) **AN OPTIONAL CLASS PROPERTY DOES NOT GET `optionalDeclaration = true`**, so
  `class C { p?: boolean = … }` prints `'boolean'` where both references print `'boolean | undefined'`
  — the ADD half, missing at that one declaration kind. Both are one wiring each, and (CHK.92)'s own
  16 (c) pins plus (CHK.113)'s 7 are the regression net. (c) **A CROSS-FLAVOUR ENUM MEMBER SOURCE
  DOES NOT COLLAPSE**: `const m: SOne = NOne.A` prints `'NOne.A'` where both references print `'NOne'`
  (and the mirror `SOne.A` → `'SOne'`), while the SAME-flavour case correctly keeps `'STwo.A'` — i.e.
  the collapse is per FLAVOUR-MISMATCH, a different rule from (CHK.92)(d)'s one-member collapse and
  from `relationErrorSourceDisplayType`'s generalization. RISK: (a)/(b) LOW — but they are DISPLAY, so
  per (CHK.92) the grid is a control and the instruments are the corpus, the externals module and the
  `-project` module. FORM.

- [x] **(CHK.113) (a) AND (b) CLOSED 2026-09-08 ((P18.49) note); (c) STAYS BLOCKED and is pinned as a
  recorded refusal. (a) was UNDER-COUNTED (all five positions plus a union target, 8 silent rows, not 2);
  (b)(ii) was WRONG — `gN(true)` prints `'boolean'` in BOTH references too, so that row was never a
  divergence — and (b)'s mechanism needed TWO changes, not one, because the source is widened at
  ACQUISITION. Three residues found beside it are queued as (CHK.114). ORIGINAL: THE TWO MEANING RESIDUES (CHK.92) MEASURED BESIDE ITS OWN PARTS, AND (d)'s REFUSED
  HALF (2026-09-08, (P18.48); fixtures `build/bench/chk92-sub`).** (a) **A `number` SOURCE IS SILENTLY
  ACCEPTED AGAINST A *STRING ENUM* TARGET** — `const m: SOne = <number>` and `const m: STwo = <number>`
  are silent here and TS2322 in BOTH references. A genuine MEANING gap and separate from (CHK.92)(b),
  which was the argument-position OBJECT-LITERAL half; this one is the plain declaration. (b) FORM,
  argument-position source FRESHNESS: `gB(1)` against `b?: boolean` prints `'number'` where both
  references print `'1'`, and `gN(true)` prints `'boolean'` for `'true'` — tsc keeps them because
  `undefined` is a UNIT type in `typeCouldHaveTopLevelSingletonTypes` and our `propTypeContainsLiteral`
  has no nullish arm. **Refused in (CHK.92) as SCOPE, not as wrong**: the fix widens
  `ts2322KeepsSourceLiteral`, whose KDoc records two prior false-positive incidents from exactly that
  widening — so it needs its own round with the 21-baseline literal-target guard set. (c) (CHK.92)(d)'s
  TS2367 half stays REFUSED: `Cmp.X === 5` and `const cx = Cmp.X` keep the member while `let lx = Cmp.X`
  and an annotation-derived `declare const av: Cmp.X` collapse in both references, i.e. the rule is
  literal FRESHNESS, and this checker mints no fresh enum-member type — the four shapes share one
  `Type` and cannot be separated by it. Closing it needs a SYNTACTIC freshness proxy at that emitter,
  which is a second copy of a rule the type system does not carry; pinned as a recorded refusal in
  `EnumAndNullableRelationDisplayTest`. RISK: (a) LOW-MEDIUM and MEANING, (b) MEDIUM, (c) BLOCKED.

- [x] **(CHK.92) CLOSED 2026-09-08 ((P18.48) note): all four parts landed, and (d)'s TS2367 freshness
  half is REFUSED with its mechanism (this checker mints no fresh enum-member type, so the four shapes
  share one `Type`). The item named TWO emitters for (a) and there are THREE, and its (d) exception is
  literal FRESHNESS in full rather than the two syntactic cases it lists. Two MEANING residues found
  beside it are recorded in (CHK.113). ORIGINAL: THE (CHK.83) DISPLAY/ARGUMENT RESIDUES — MEASURED 2026-09-05 by read-only
  recon (`scratchpad/chk92`, 45 rows; tsgo 7.0.2 and pristine 6.0.3 agree on every row;
  Checker.kt lines are commit 7685baf8).** (a) FORM. `emitPerPropertyMismatchesForObjectLiteral`
  (~168702) and `caasObjLitPerPropertyMismatch` (~162327) print `'number' is not assignable to
  type 'number'` for `{ p: 6 }` against `p: 5` — the SOURCE is the widened member type
  ((WIDEN.1): no fresh literal in `getTypeOfObjectLiteral`) and the TARGET is
  `getWidenedLiteralType`'d; tsc widens NEITHER (`reportRelationError` checker.ts:22628
  generalizes the source only when `!typeCouldHaveTopLevelSingletonTypes(target)`, and a
  literal target could). No archive entry records the widening as a decision (blame lands on
  the 2026-08-02 JIT split, a move), so the (P18.20) note's "an earlier round's explicit
  choice" is unsupported. Seam: `typeToString(targetPropType)` at both sites +
  `relationErrorSourceDisplayType(literalTypeOfExpression(value) ?: sourcePropType, target)`
  for the source; guard set = 21 ACTIVE literal-target baselines (`scratchpad/pop_a.txt`), all
  green today. Fold in the (CHK.83) form divergence found beside it: the string-literal arm
  keeps `'"z"'` against a NUMERIC enum target where both references print `'string'` (rows
  a8/b5/c14/c22) — tsc's keep is per FLAVOUR (`isLiteralOfContextualType` 41459+), so the
  literal survives only against a string enum. (b) MEANING (missing row). `fx({ e: 3 })`,
  `fx({ e: "s" })` and `fx({ p: "z" })` against a string enum are all silent at an ARGUMENT:
  `caasObjLitPerPropertyMismatch` lacks the `enumTargetLiteralSource` leg the declaration twin
  has (~168558) and its `bothSimple` gate (~162302) refuses an enum target outright. Seam:
  admit enum-flavoured targets there + the literal-source leg; corpus + grid gate it. (c)
  FORM, ONE rule at three sites, wrong here in BOTH directions. tsc's `isRelatedTo`
  (checker.ts:22802-22812) rewrites a 2-3 member target union with ONE non-nullable remainder
  to that remainder ONLY for a `DefinitelyNonNullable` source (types.ts:6391 — not a union /
  type parameter / `unknown` / `null`) and never when the remainder is itself a union
  (`boolean`, a ≥2-member enum). Ours prints the bare optional-parameter type ALWAYS (the
  Parameter arm ~116507 adds no `| undefined`; head display ~161940/161988) — right for
  `gU(1)` against `s?: string` (the (P18.20) note's "prints `'string | undefined'`" is FALSE —
  ours prints `'string'`), wrong for `e?: E` / `b?: boolean` / a union, type-parameter,
  `unknown` or `null` SOURCE (rows c1/c5/c9/c10/c16/c20/c23/c25 → references print
  `'… | undefined'`) — and prints an EXPLICIT `string | undefined` / `| null` in full where
  tsc strips it (c11-c13/c18/c19/c24). The property twin (`widenOptionalTargetPropType`
  ~169838) has the source-nullish half but not the union-remainder refusal (c22 `e?: E`
  prints `'E'` for `'E | undefined'`). Seam: a display-only `nullableTargetDisplay(target,
  source, isOptionalParam)` at the TS2345 head, the var-decl TS2322 head and the optional-
  property display, `null` then `undefined` last; negatives pinned by 14 ACTIVE baselines
  (`optionalParamTypeComparison`, `intersectionsAndOptionalProperties`,
  `elaboratedErrorsOnNullableTargets01`, … `scratchpad/pop_c.txt`); the positive side is
  invisible to the corpus by construction. (d) FORM with one exception. A one-member enum's
  declared type IS its member's regular type (`getDeclaredTypeOfEnum` checker.ts:13544 +
  `getUnionType`'s single-type return ~18313), so relation errors print `Cmp` where ours prints
  `Cmp.X` (d2/d6/d9/d11/d13/d14, incl. a parameter annotated `x: Cmp.X`) — but the TS2367
  operand display keeps the FRESH `Cmp.X` for a direct member access or a `const` bound to one
  (d10/d15; ours is right on d10 and wrong on d15 through (CHK.85)(b)'s symbol half). Seam: the
  EnumMember arm of `typeToString` (~132756) prints the parent for a one-member enum
  (precedent: `enumMemberWeakSource` ~118886, which is why `nestedExcessPropertyChecking`
  prints `Type 'E'` today), with a syntactic freshness override at the (CHK.86)/(CHK.88)
  emitter (~165973/165993). Population 26 baselines / 4 ACTIVE, 0 member-form lines anywhere.

- [x] **(INV.0) STEP 4 — NAME RESOLUTION: DONE 2026-09-09 over three commits ((P18.53) 4a
  `da92e5bd3`, (P18.54) 4b-i `84dd8ad5d`, (P18.55) 4b-ii `b353278a1`, plus `2db1c14ca` the
  diffability fix). `NameResolver.kt` is **2,284 lines**; `Checker.kt` **199,963 → 198,022
  (−1,941)**; ledger rows 4, 5 and 6. Ambient: **26 checker reads, NO writes** — and the row
  IMPROVED as the family closed, 4b-ii absorbing four of its own earlier reads. The full 4b was
  SPLIT at its natural seam (per-file lookup core / namespace-heritage group) because it censused
  at 1,363 lines. Receipts for the WHOLE step: all 420 per-pass `--passTiming` rows and the 46
  diagnostics byte-identical against PRE-4a pristine; PrintInlining zero refusals on every hop and
  the split IMPROVED `lookupPerFileForNode` (~2M calls/self-compile) from `4 inline (hot) + 57 too
  large` to a hop reading `57 inline + 41 inline (hot)`; ab-interleaved noise-dominated at every
  step. **THREE CONSTRAINTS ESTABLISHED HERE, NOT TO BE RE-DERIVED BY LATER ROWS**: a constructor
  input must be declared ABOVE `Checker.kt:666` (below it the field is still null at construction);
  a field whose initializer has a SIDE EFFECT on another field (`libGlobals` →
  `realLibUnknownNames`) cannot be moved or hoisted at all; and a receipt grep must use the MANGLED
  JVM name — `internal` adds a `$<module>` suffix and a VALUE-class parameter adds a `-<hash>` one,
  both of which read as "this hop was never compiled".**

- [x] **(INV.0) STEP 5 — THE RELATER: DONE 2026-09-09 ((P18.56), commits `579092d93` split +
  `03d455241` ablation record). `Relater.kt` is **1,446 lines**; `Checker.kt` **198,022 →
  196,797 (−1,225)**; ledger row 7. Ambient: **45 reads, 5 writes** — the largest row of the
  arc, and the design's own prediction rather than a regression. The item's recon was
  corrected by the census: the algorithm is **1,256 lines in five contiguous spans**, not
  spread through the ~6,390-line region — the rest is ELABORATION and stays. NOT split
  (one mutually-recursive algorithm; a mid-recursion seam would put a `Checker` hop inside
  the hottest recursion). Delegation surface **six**, not 363. **THREE THINGS ESTABLISHED
  HERE FOR LATER ROWS**: the `--passTiming` pass table is printed in DESCENDING WALL-TIME
  order, so a "byte-identical rows" receipt must SORT the rows and drop every ms-bearing or
  time-bucketed line (804 spurious diff lines otherwise); `cost_gate.py`'s recorded baseline
  can be stale, so a pure split is graded against a REBUILT pristine and the gate is the
  control; and `isTypeAssignableTo` joins `getTypeOfExpression` as a `PrintInlining` site that
  is NOT stable across processes, leaving `checkArgumentsAgainstSignature` the only one of
  the three that can separate arms.**

- [x] **(INV.0) STEP 6a — MEMBER RESOLUTION: DONE 2026-09-09 ((P18.57), commits `c26520a8e`
  split + `c8b2349e4` ablation record). `MemberResolver.kt` is **814 lines**; `Checker.kt`
  **196,797 → 196,172 (−625)**; ledger row 8. **The item's open question is ANSWERED: the seam
  IS the table builders WITHOUT `getTypeOfSymbol`**, which stays an ambient read exactly as
  § 6 predicts, and the answer is recorded in the class KDoc. Ambient: **21 reads, 1 write,
  columns DISJOINT** — a far better-shaped seam than row 7 (one span vs five, 657 lines vs
  1,256, three entry points vs six), which makes the relater the arc's outlier rather than
  its trend. **Measured and worth carrying: there is NO cheap absorption here — ZERO of 22
  members lack another caller in `Checker.kt`**, against row 7's seven, so this row shrinks
  only by extracting its NEIGHBOURS. The receipt is now TRANSITIVE: the same 488 deterministic
  `--passTiming` lines are byte-identical across pre-step-5 pristine, step 5 and step 6a —
  one receipt over 1,882 moved lines, at no extra build. **NEW CONSTRAINT FOR EVERY LATER
  ROW: widening a member to `internal` as part of a split MANGLES its JVM name, so a § 10
  receipt grep that a PREVIOUS round used reads zero rows** — `getTypeOfExpression` went
  private → internal here and its unmangled grep reads 574 rows before and 0 after. Grep both
  forms and say which you used.**

- [x] **(INV.0) STEP 6b — THE MEMBER-NAME / LATE-BINDING FAMILY: DONE 2026-09-09 ((P18.58),
  commits `ecd6c0491` split + `95dff28a1` ablation record). `MemberNames.kt` is **765 lines**;
  `Checker.kt` **196,176 → 195,606 (−570)**; ledger row 9. **The arc's CLEANEST seam: five
  ambient reads, ZERO writes**, because the family owns no state and answers a SYNTACTIC
  question — four of the five reads belong to an enum seam and a syntax seam. Twelve
  delegations; `MemberResolver` deliberately NOT rewired (no construction-order dependency).
  The split removed **61** `too large` inlining refusals and added none. **Every one of the 5
  pins discriminates** — the session's first round where that is true. **A NAMING TRAP FOR
  EVERY LATER ROW: grep `Checker.kt` for a LOCAL of the name before naming a collaborator
  field** — `memberNames` is already eight locals, and the field compiles while being shadowed
  at five of them.**

- [x] **(INV.0) STEP 7 — THE ENUM FAMILY: DONE 2026-09-09 ((P18.59), commits `1c520fc19`
  split + `b62d9d376` ablation record). `EnumSemantics.kt` is **1,137 lines**; `Checker.kt`
  **195,606 → 194,631 (−975)**; ledger row 10. **The census the item demanded decided it: of
  its three candidates (a) SIGNATURES and (b) FLOW are SCATTERS** — signatures over six
  unrelated neighbourhoods, flow 64 declarations of which 22 are singletons or pairs from
  line 1091 to 125482 — **and only (c) ENUM is a family**, one contiguous 1,013-line span of
  40 declarations. **13 ambient reads, ZERO writes.** **AND THE STAGE-0-EXIT DECISION ROW 9
  ASKED FOR IS TAKEN**: `Relater` and `MemberNames` call the collaborator DIRECTLY, which
  cost one line because `Checker`'s construction block already IS an explicit ordered graph
  — `Relater` **49 → 38** checker reads, `MemberNames` **5 → 4**, the arc's first fall in the
  ambient TOTAL. Seven of eight pins discriminate exactly; the eighth was BLIND (its fixture
  resolved both sides through one import, so the two symbols were identical) and was repaired
  to a shape that reads 1 row vs 2.**

- [x] **(INV.0) STEP 8 — THE TYPE-CAPTURE FAMILY: DONE 2026-09-10 ((P18.60), commit
  `d897942c9`). `CaptureRecorder.kt` is **3,214 lines**; `Checker.kt` **194,631 → 191,540
  (−3,091)**, the arc's largest single move; ledger row 11. **The item's own "obvious
  candidate" — the member-ACCESS family — measured a SCATTER over eight neighbourhoods,
  and the same census found TYPE CAPTURE instead**: 103 `typeCapture*`/`captured*`
  declarations of which 94 sit in one 3,120-line block. **61 ambient reads and 9 WRITES,
  the arc's largest row and the finding rather than a debt** — fourteen reads and all nine
  writes are the WALK, i.e. `docs/INVERSION-DESIGN.md` § 2's "answers are functions of
  walk-scoped state" as a number. **The OUT surface is the arc's cleanest: 98 declarations
  move, 15 keep a caller.** Receipt is the CAPTURE CHANNEL, not the pass table: 381,666
  types and 360,917 definitions, per-arm digest byte-identical. All 8 ablation arms redden
  exactly their own pin — the arc's first perfect 8-for-8.**

- [x] **(INV.0) STEP 9 — THE DECISION IS TAKEN AND ITS INERT FIRST SUB-STEP LANDED
  2026-09-10 ((P18.61), commits `0b3d1f089` + `d54f0ad52`).** Both options were SIZED, not
  argued. **(a) the check passes**: 96,830 of 191,499 attributed lines (50.6%) — where the
  lines are — and the ambient census of the four largest candidates reads `cmam*` 83,
  `caas*` 59, type-node builders 68, **`cae*` 97 reads for EIGHT declarations**, against
  rows 1-11's 0/0/4/26/22/4/13/45/21/5/61. A collaborator with twelve times as many inputs
  as members is a file move. **(b) taken**, and its inert sub-step is
  `LexicalScopeResolver` (124 lines, **ambient NONE — the first since row 3**), which gives
  the five-times-hand-copied INV.2(c) ascent one home with its four drifted axes as
  parameters; ledger row 12; 8-profile grid `added=0 removed=0`. Two texts corrected
  because they were FALSE: `LexicalScope`'s "UNCONSUMED until INV.4" KDoc and both
  `TypeOracle` refusals, which blamed the binder for what is a COMPOSITION problem.**

- [x] **(INV.0) STEP 10a — THE B83.5 *TYPE* SPACE. LANDED 2026-09-10 ((P18.62) note).** The
  round-748 scope-space consult went from `enum`-only to the whole TYPE space
  (`class`/`interface`/`type`/`enum`), and `getTypeFromTypeReference` got its OWN hoist
  because it asks the enclosing-namespace chain itself and passes
  `enclosingNamespacesDone = true`, i.e. never reaches `resolveTypeNameToSymbol`'s
  lexical-first arm. Six fixtures byte-identical to tsgo 7.0.2 AND pristine 6.0.3 where
  before they were silent or answered the OUTER declaration; grid 8×`added=0 removed=0`.

- [ ] **(INV.0) STEP 10b — THE B83.5 *VALUE* SPACE, AND IT IS THE BIGGER HALF OF WHAT IS
  LEFT.** `Checker.getTypeOfIdentifierCore` answers `anyType` for a scope-space `class`,
  `function`, `namespace` or `enum` VALUE read. **MEASURED 2026-09-10 over a 129-cell
  matrix** (7 kinds × 4 nesting sites × 2 positions × unique/shadowing, three compilers;
  `scratchpad/b835/final_matrix.txt`): at the 86 B83.5 cells the whole population is **106
  lost true rows and 43 ours-only rows**, split **TYPE 24/60** and **VALUE 19/46** — so
  after 10a the VALUE half is what remains. Its ladder order is load-bearing and the
  consult has to be placed INSIDE it, not on top: (CHK.49)'s lib-value-behind-a-type-only-
  shadow rung and round 429's destructured-param rung both sit above the file-level
  tables for stated reasons. **What already works and must not regress: `const` in VALUE
  position resolves at all three nesting sites today** (the walk's `currentLocalTypes`
  carries value bindings independently of the binder), and `enum` in TYPE position is
  round 748's. **What is squarely in this step: the enum VALUE position** — `const p:
  string = ZzzE.ZA` is silent at every B83.5 site, so round 748's closure is exactly half.
  **And a bound-site defect rides along and should be fixed with it: `typeof <a const>` is
  MISSING everywhere, including at file and namespace level.**

- [ ] **(INV.0) STEP 10c — HERITAGE.** `NameResolver.resolveHeritageBaseSymbol` is a THIRD
  resolver (`lookupInEnclosingNamespaces ?: lookupPerFileForNode`, `NameResolver.kt:1955`)
  and reaches neither 10a's consult nor 10b's, so `class D extends ZzzBase` /
  `implements ZzzI` where the base is scope-space still answers the outer declaration or
  nothing. Small, and it is the last of the three TYPE-name funnels.

- [ ] **(INV.0) STEP 10d — THE TWO ORACLE ROWS**, which (P18.61) corrected and 10a does not
  unblock on its own: `TypeOracle.resolveName` needs a COMPOSED resolver plus a `meaning`
  parameter, and `symbolsInScope` additionally an `existing`-reading ENUMERATION, so it
  opens strictly later. The probe those two were waiting on is answered — a scope-space
  `Interface`/`Class`/`Function`/`Variable` symbol is a first-class symbol to
  `getTypeOfSymbol`/`getDeclaredTypeOfSymbol` (pinned in `LexicalScopeResolverTest`), so
  neither row needs a transient-symbol route.

- [ ] **(CHK.117) THE BOUND-CONTAINER DEFECTS THE STEP-10 MATRIX FOUND — NOT B83.5, AND
  THEREFORE NOT FIXED BY ANY OF 10a-10d (measured 2026-09-10, three compilers).** A
  `ModuleBlock` IS bound by the binder, and **15 of the 28 namespace-body cells still
  diverge**: (1) `typeof <namespace-local const>` and `typeof <namespace-local function>`
  are silent; (2) a nested `namespace`'s VALUE read is silent; (3) **five WRONG-display
  rows** — we render `Type 'ZzzWrap.ZzzAl'` where both references render `'ZzzAl'` for a
  NON-EXPORTED namespace member, which is (PARITY.3)'s qualification rule one container
  over; (4) **a shadowing declaration inside a namespace body resolves the OUTER one in 9
  of 14 cells**, so the shadow defect is strictly wider than B83.5; (5) an internal
  INCONSISTENCY worth its own repro — `cls-VALUE-nsBlock-shadow` emits `TS2322: Type
  'number' …` (the member exists) and `TS2339: Property 'zzzOuter' does not exist` on the
  SAME line, i.e. two walkers disagreeing about one member.

- [ ] **(REL.1)(c) LEAD, measured 2026-09-09 by (P18.59) and NOT fixed: the same-string
  qualified-display retry is not reached by the VARIABLE-DECLARATION assignability reader.**
  With two same-named enums whose members collide, both references print
  `Type 'Z.Foo.A' is not assignable to type 'X.Foo.A'.` and this compiler prints
  `Type 'Foo.A' is not assignable to type 'Foo.A'.` — for a NAMESPACE-nested collision
  (`namespace X/Y/Z { export enum Foo … }`) and for a MODULE-scoped one (three files, where
  the references qualify with `import("…")`), in `diagnose()` and through the project CLI
  alike. `enumCollisionQualifiedDisplays` exists and fires elsewhere (the
  `enumAssignmentCompat*` baselines), so this is a READER that does not call it, not a
  missing rule. PRE-EXISTING — the family moved verbatim in step 7 and HEAD~1 prints the
  same. `EnumSemanticsSeamTest`'s pin 8 records the divergence in its KDoc and pins the
  VERDICT only; that pin's expectation is what changes when this is fixed.

- [ ] **(INV.0) IN PROGRESS — step 1 (`TypeInterner`, canonical type identity, ambient
  surface NONE) DONE 2026-09-02, ledger row 1; step 2 (`Relation`+`Ternary` relocated to
  `TypeRelationCache.kt`) ledger row 2; step 3 (`TypeInstantiator` — the instantiation
  seam, ambient surface `getTypeOfSymbol` + union/intersection normalization reads and a
  `symbolTypes` write, stated) DONE 2026-09-02, ledger row 3, `Checker.kt` 190,771; next
  seams per the order: name resolution, getTypeOfSymbol/getTypeOfExpression (Stage-3-shaped
  — their ambient IS the checker), relations (the relater's algorithm out of
  `checkTypeRelatedTo` into `TypeRelationCache.kt`'s seam), signatures, flow.**
  **SPLIT `Checker.kt` BY RESPONSIBILITY ALONG THE SEAMS
  `docs/INVERSION-DESIGN.md` NAMES (owner additions 2026-09-02 MERGED into the item
  (INV.D)/P18.0 had already queued, per the directive's own merge rule; moved here to sit
  directly after (INV.D) as instructed).** tsgo's `internal/checker` decomposition is the
  reference map (relater, inference, mapper, flow, nodebuilder, grammarchecks, jsx,
  emitresolver, services). Order: the future memoized core first (name resolution,
  getTypeOfSymbol / getTypeOfExpression, relations, instantiation, signatures, flow);
  check passes last. FINAL classes with explicit inputs, constructed ONCE per Checker;
  per-node context travels as parameters, never as a per-visit allocation; no extension
  functions over `internal` state. Every extraction adds a row to
  `docs/inversion-ambient-ledger.md`: ambient fields read / written. No semantic change:
  corpus byte-identical AND cost_gate at 0.00% per commit. **RECEIPT per commit —
  cost_gate reads 0.00% for a pure split BY CONSTRUCTION (counters count calls, not
  nanoseconds), so it is a control, not evidence; the evidence is: (1)
  `scripts/ab-interleaved.sh` wall time with win rate, (2) a JFR allocation profile
  before/after via `scripts/aggregate_jfr.py`, (3) `-XX:+UnlockDiagnosticVMOptions
  -XX:+PrintInlining` on `checkArgumentsAgainstSignature`, `getTypeOfExpression` and
  `isTypeAssignableTo`, confirming every new delegation hop reads `inline (hot)`, and
  (4) core-module compile time before/after.** The full contract (final collaborators,
  interned mappers, forbidden hot-path shapes, the shrinkage metric) is
  `docs/INVERSION-DESIGN.md` § 10.

- [x] **(INV.1b) DONE 2026-09-02 — THE 1.34 µs IS THE RESOLUTION, AND THE RECONSTRUCTION IS FREE.**
  `NodeAnswers.TYPES` clear records a placeholder without resolving (`BenchMain …
  nodeAnswers:reconstruction`); compiler profile rotated: reconstruction 5,290 / 5,266 ms =
  the plain check (5,270), types 6,158 / 6,121 — every microsecond is
  `getTypeOfExpression` re-typing an expression the walk typed once already (no per-node
  memo). Pinned: the placeholder arm records `anyType` at every expression and computes
  nothing else; the TYPES arm records real types. Record: design § 9b. ORIGINAL ITEM: PRICE THE PER-EXPRESSION RECORDING COST OF THE (INV.1) STORE — IS THE
  1.3-1.5 µs THE RESOLUTION OR THE AMBIENT RECONSTRUCTION?** Measured 2026-09-02 (design
  § 9a): flag ON is +14.9 % warm on the compiler profile and +10.3 % on many-small-2400-dom,
  1.34 / 1.49 µs per recorded expression, i.e. per-NODE. Two candidates, both already priced
  in neighbours and neither attributed here: `typeCaptureReportedType` (mostly a memoized
  `getTypeOfExpression` — round 737's factor says most expressions are typed already) and
  `typeCaptureVisit`'s per-node save/restore of eight ambient fields plus
  `withCtaFrameLocals`. Instrument: two `--nodeAnswers` arms in one binary — one recording
  `anyType` without resolving (reconstruction only) — and the difference is the resolution.
  Nothing in Stage 1 depends on the answer (the flag ships off); Stage 2's facade does.
  **HALF-ANSWERED by (INV.2) (design § 9b): the three companion channels cost +6 % on top of
  the type, i.e. the reconstruction is paid once per node and the companions are pure
  resolution; the types-only 1.34 µs is still unsplit, and `NodeAnswers.channels` +
  `BenchMain … nodeAnswers:types` is the arm to split it from.**
- [x] **(INV.2) DONE 2026-09-02 (owner-approved 2026-09-02) — STAGE 2 OF `docs/INVERSION-DESIGN.md`: THE
  ORACLE FACADE OVER THE (INV.1) STORE.** Landed as `TypeOracle` (`TypeOracle.kt`) over the
  store + retained graph + live checker (`OracleLens`), with `typeOracleOf(files)` and
  `ProjectCompiler.build(…, oracleHolder)` as the entries, per-build `OracleHandles`,
  `close()` on edit, `resolveName`/`symbolsInScope` refused naming Stage 3; the store
  grew the `symbols` / `calls` / `contextual` channels; per-row divergences in
  `docs/type-oracle.md`; 23 pins; suite 16,860/0/3; cost_gate +0.00%; flag-on measured
  **+21.5 % compiler profile (1.90 µs/expr), +6-7 % many-small (0.95 µs/expr)** after a
  quadratic re-typing in the key leg was found by JFR and removed (+60 % before). Record:
  design § 9b, the (P18.8) note. ORIGINAL ITEM: STAGE 2 OF `docs/INVERSION-DESIGN.md`: THE ORACLE
  FACADE OVER THE (INV.1) STORE.** Proposal (§ 4 "The facade" / § 6 Stage 2): a `TypeOracle`
  over store + retained graph + live checker, exposing the proto.go-shaped B rows
  (`getTypeAtLocation`, `getTypeOfSymbolAtLocation`, …) with per-build handles, REFUSING
  `resolveName`/`getSymbolsInScope` with a reason until Stage 3; the A°-divergences ship
  documented per row. Consumers (EXT, LSP) migrate only if it beats what they use.
  Implementation does not start without owner approval — the (INV.1) approval covered
  Stage 1 only.
- [ ] **(INV.2b) HAND `Project` AN ORACLE, WITH THE INVALIDATION DECIDED — the Stage-2 facade
  is core-only today.** `Project` runs a NARROWED build per keystroke ((INC.1)) and keeps a
  whole-program `cached` result; an oracle is valid for ONE build of ONE text ((INC.46)) and
  the store may never serve `diagnostics` ((INC.14)'s capture rule). Design: a
  `Project.typeOracle()` that builds whole-program with an `OracleHolder`, retains the oracle
  beside `cached`, and `close()`s it in `updateFile` / `edit` — then decide whether
  `quickInfoAt`/`definitionsAt` may be served from it (only if it beats the ~93-217 ms
  narrowed query and the capture-equivalence sweep agrees row for row). Position→node is
  `SourceIndex` (round 910's span rules). Not a latency item: do not touch `Checker.kt` for it.
- [x] **(INV.1) DONE 2026-09-02 (owner-approved 2026-09-02) — STAGE 1 OF `docs/INVERSION-DESIGN.md`: THE
  PER-FILE NODE-ANSWER STORE, OFF BY DEFAULT, ONE COMMIT.** Landed as `NodeAnswerStore`
  (`Type` slots, not ids — § 9a records why: there is no id→Type lookup to resolve through);
  10 pins in `NodeAnswerStoreTest` incl. the round-911 positive control and the
  production-mode counter at 0; suite 16,838/0/3; cost_gate +0.00%; warm A/B flag-off
  NOISE-DOMINATED; flag-on measured on both shapes (+14.9 % / +10.3 %, 1.34 / 1.49 µs per
  expression) — the full record is design § 9a, the (P18.7) note. ORIGINAL ITEM: Proposal (§ 9 of the design):
  a per-file `IntArray` keyed by nodeId recording the walk's own expression-type answer at
  `checkedSinkEmit`-time, first-wins; pins: the round-911 positive control (a body local's
  recorded type differs from the post-hoc `getTypeOfExpression` answer), a production-mode
  counter at 0 (round 900's law), cost_gate +0.00% flag-off; then MEASURE the flag-on
  recording cost on both program shapes before any further stage is priced. Moves 13 of
  tsgo's 15 walk-scoped API methods to answerable ((INV.D) § 3a); `resolveName`/
  `getSymbolsInScope` stay open until Stage 3 (B83.5). Implementation does not start
  without owner approval.

- [ ] **(INC.91) THE SIGNATURE-EDIT CLIFF — CENSUSED 2026-09-01 AND **REFUSED AS WRITTEN**,
  NOT ON THE PRIZE BUT ON SOUNDNESS. WHAT REPLACES IT IS THE CLOSURE-NARROWED BUILD, WHICH
  NEEDS NO FINGERPRINT AND IS WORTH 12.8x ON ITS OWN.**
  The proposal was a per-hop, signature-keyed walk: on a signature move re-check the direct
  importers, re-fingerprint them, stop where a fingerprint did not move. **The stopping signal
  is unsound and the refuting number is 1 real diagnostic reported as 0.**
  **THE MEASUREMENT** (`Inc91ClosureCensusMain`, counts not milliseconds, reproduced across two
  runs; whole-program arm so (INC.19)'s first-touch order is not a second variable):
  transitive importer closure of `layer00/m0_0.ts` is **187 files of 2,401 (7.8%)** — hop 1 is
  3 files and the fan-out is ~4 per hop, NOT ~50, so "layer00 is the bottom layer therefore most
  of the program" was wrong. Fingerprints moved: **1** for an append-at-END signature edit and
  **3** for the identical edit inserted at the TOP. Controls: 0 moved on an unedited rebuild,
  `escapes = 0`, rows = 1 in every cell.
  **THE OFFSET WORRY IS REFUTED, AND CHEAPLY.** `foreignKey` does mix `decl.pos`/`decl.end`
  (`Checker.kt:57089-57090`), so an importer's hash does carry the byte offsets of what it
  imports — but it **does not cascade**: it costs exactly ONE extra hop (3, not 2,400), because
  an unedited importer's own declarations do not move, so its own consumers' keys are stable.
  A barrel probe confirms the shape: a TOP insert at a leaf moves the leaf and its three real
  consumers and NOT the two barrels, because `foreignKey` names the DECLARING file.
  **THE ACTUAL BLOCKER IS NON-TRANSITIVITY, AND IT IS WRITTEN IN THE FINGERPRINT'S OWN KDoc**
  (`Checker.kt:57050`): *"What the cut gives up is transitivity — F's hash does not move when
  G's type changes — and transitivity is not wanted here: a moved signature ANYWHERE falls back
  to a whole-program build."* `incrementalDiagnostics` is sound BECAUSE it falls back; this item
  proposed to delete the fallback and keep the signal. Measured on a three-file,
  **length-preserving** semantic edit (`count: number` -> `count: string`, 106 bytes both ways,
  zero offset shift): the program's only error is at **hop 2**, hop 1 is silent in EVERY channel
  (0 rows, fingerprint unmoved, `.d.ts` text unchanged), and the census says **MOVED: 1 file**.
  So the walk stops at hop 1 and reports **0 rows where the truth is 1** — a missing diagnostic,
  which is the one outcome the five gates exist to prevent. tsgo answers 1/1 on the same probe
  and its own hop-1 `.d.ts` is textually unchanged too, so **the feature is achievable but its
  soundness cannot come from a signature alone** — it is not a signal we already have.
  **BUILD THIS INSTEAD, AND IT IS MOST OF THE WIN:** on a signature move, replace the FULL
  REBUILD with a build narrowed to the edited files' **transitive importer closure**, splicing
  the previous rows for everything outside it. It prunes with `Result.importEdges`
  (`ProjectCompiler.kt:68`, already computed) and **uses the fingerprint for nothing**, so the
  non-transitivity above cannot bite; it is sound for the reason a superset always is. Worth
  **2,401 -> 187 files (12.8x)** here, and it DEGRADES TO TODAY'S BEHAVIOUR on barrel codebases
  where the closure is ~100% — which is (INC.35)'s finding, now a property of the mechanism
  rather than a reason not to have it. Keep the existing `exportSignatureEscapes` and
  shared-name guards; a file outside the closure must be unable to observe the edit, and an
  escape is exactly the case where that is not provable.
  **TWO SOUNDNESS HAZARDS THE CENSUS DID NOT REACH, FOUND BY READING, AND THE FIRST IS FATAL
  TO A NAIVE `importEdges` CLOSURE.** (i) **Not every cross-file interaction is an import
  edge.** `Checker.kt:990` builds `multiFileModuleTypeNames` as literally *"type names declared
  in >= 2 module files"* — two files that merely SHARE A TYPE NAME affect each other with no
  import between them, and CLAUDE.md records that this family plus `moduleInterfaceNames`
  ablates to **0 RED on the ~13k corpus while reddening 3 of the 8 profiles** ((INC.73)), i.e.
  it is load-bearing and the corpus cannot see it. **A closure that is the transitive
  `importEdges` reachability alone therefore MISSES those files and drops their diagnostics** —
  the same failure this census refused the walk for, arriving by a different door. The closure
  must be UNIONED with the files sharing any type name with an edited file (that set is already
  computed), or an edited file spelling such a name must be treated as an ESCAPE.
  (ii) **`importEdges` completeness is a precondition, not an assumption** — it has exactly
  three `add` sites (`ProjectCompiler.kt:622/634/639`) and the closure is only as sound as their
  coverage of type-only imports, `export *` re-exports, `import type`, `require`, and
  `/// <reference>`. A missing edge kind is a MISSING DIAGNOSTIC and (CFG.1) says nothing here
  prints it; audit the three sites against the specifier kinds the crawl resolves before
  trusting the graph, and pin the audit rather than the graph.
  **THE REMAINING 187 -> 4-8 IS THE PER-HOP PRUNING AND IT IS BLOCKED ON A *TRANSITIVE*
  SIGNATURE**, which is a different and larger item than the walk — do not re-open it as a
  walk. **GRADE ON THE CAPTURE CHANNEL TOO** ((INC.19)): a lost collector surfaces as a wrong
  TYPE and never as a missing error.

- [ ] **(INC.92) `Cancellation.signal` IS A PROCESS-GLOBAL AND THE PLUGIN'S SHAPE IS N
  CONCURRENT BUILDS — THE TWO CONTRACTS CONTRADICT EACH OTHER IN WRITING (2026-09-01).**
  `Cancellation.kt:111` is a `@Volatile private var signal` installed and restored around each
  build (`ProjectCompiler.kt:198/211`), and its own KDoc states the cost: *"two builds running
  CONCURRENTLY in one process share this field, so the second install wins and the first build
  would poll the wrong signal."* But `docs/language-service.md` explicitly blesses "one
  single-thread executor per project" and "a monorepo has many `tsconfig.json`s and therefore
  many `Project` instances" — which is the IntelliJ plugin verbatim ((INC.67)). Install/restore
  is not LIFO across threads, so a concurrent pair can leave the field holding the other
  session's signal or null. **Consequence is a DEGRADED CAPABILITY, not a wrong answer:
  (INC.55) silently fails to cancel for one of two concurrent sessions.** It cannot bite today
  (the plugin sets no signal) and bites the moment the plugin adopts cancellation in a monorepo.
  Fix direction: carry the signal through the `runWithDeepStack` handoff, which exists for
  exactly this purpose (the thread-local `Symbol`/`Type` id counters, INV.6(6c0)), rather than a
  process-global. The pin needs TWO concurrent builds, since a same-thread pin passes either way.

- [ ] **(INC.93) (INC.67) IS INCOMPLETE — `CrawlParseCache` IS A PROCESS-GLOBAL PLAIN
  `HashMap` AND THE N-SESSION SHAPE REACHES IT CONCURRENTLY (2026-09-01).**
  `CrawlParseCache.kt:97` is `private val entries = HashMap<String, PreParsedFile>()`, written by
  `store` and read by `lookup`. Its threading KDoc argues only about the crawl's N
  `Dispatchers.Default` workers WITHIN ONE BUILD and concludes "lookup must stay read-only and
  store must stay off the concurrent path" — sound for one build, **silent about two**, which is
  precisely the shape (INC.67) named and converted only the three `RealLibs` maps for.
  **Severity is LOW for the ANSWER and not for the PROCESS**: a wrong tree cannot be served
  (`lookup` gates on `flags` AND full `content` equality), so the realistic outcome is a lost
  entry, i.e. a redundant parse — the benign class (INC.67) explicitly accepted. What is
  uncovered is structural corruption during a concurrent RESIZE.
  **DO NOT COPY THE `RealLibSnapshots` FIX AS WRITTEN — IT IS O(n^2) HERE.** Those maps are
  written ~45 times per process; `store` is called ONCE PER CRAWLED FILE, so a per-store
  copy-on-write is 2,401 copies of a 2,401-entry map = ~5.7 M entry copies per build on the
  application fixture. The shape that fits is a BATCHED publish: the single-threaded fold
  accumulates into a local map and swaps a `@Volatile` reference ONCE per frontier, which keeps
  the published map immutable while paying O(frontiers x n) rather than O(files x n). Price it
  against `FrontEnd`'s crawl WALL before landing — (INC.64) measured that row at 51-57 ms and a
  regression there is the whole incremental floor.

- [ ] **(API.9) WINDOWS IS UNSUPPORTED, AND IT IS A COMPILER-SIDE GAP THAT TURNS THE INTELLIJ
  PLUGIN OFF FOR A WHOLE PLATFORM (2026-09-01).** `Vfs.kt:460`:
  `fun isAbsolute(specifier: String): Boolean = specifier.startsWith("/")`. A `C:/…` path is
  therefore not absolute and would be joined onto the JVM's working directory, so the plugin
  refuses any non-`/` path outright and logs *"Windows is not supported yet"*
  (`XtscService.kt:106-113`) — TypeScript highlighting is simply OFF on Windows. Not incremental
  work, and **the single largest this-repo item the plugin is genuinely blocked on.** Note
  (CFG.1)'s law before starting: a wrong path resolves to a DIFFERENT FILE and this repo has no
  diagnostic channel that notices, so the gate is a `-project` fixture over a `Vfs` plus
  `output.programFiles`, never a green corpus (whose harness materialises no directory at all).

- [ ] **(KIR.LOWER.3) AN ELEMENT ACCESS `a[i]` LOSES THE ELEMENT TYPE, SO EVERY MEMBER
  ACCESS ON THE RESULT GOES THROUGH THE DYNAMIC BAG — MEASURED **30.7 s -> 0.94 s (33x)** ON
  ONE n-BODY BY ADDING ONE ANNOTATION (2026-08-27, the scriptc head-to-head).** `const bi =
  bodies[i]` where `bodies: Particle[]` gives the local a type the lowering reads as the bag,
  so the hot loop compiles to **20 `jsGet` + 9 `jsSet`** per inner iteration — reflection on
  the JVM — while `const bi: Particle = bodies[i]` compiles to 0 dynamic ops on the SAME
  program with the SAME sink. The class already has real `double` fields; only the RECEIVER's
  type is lost, so this is an oracle/lowering gap and not a representation one. **It is the
  largest single KIR performance lever measured to date and no gate here can see it** — the
  sink is identical, the corpus is untouched, `kir-bench.sh` gates output and not shape.
  Instrument: `javap -p -c -cp <out> program.MainKt | grep -c 'jsGet\|jsSet'`, which must be
  0 for a program whose every receiver has a declared class type. Ask whether
  `ErasedTypes`/the oracle answers `JsArray<T>`'s element type at an `ElementAccessExpression`
  at all, or whether `getTypeOfElementAccess` is the (CHK.30) narrowing gap one layer down.
  Pin it as a SHAPE assertion (count the dynamic ops in the emitted bytecode), never as a
  wall figure.

- [ ] **(KIR.LOWER.4) `this.<member> = e` IN A CONSTRUCTOR LOWERS TO `jsSet`, WHICH IS
  REFLECTION ON THE JVM AND **THROWS** ON KOTLIN/NATIVE — AND PARAMETER PROPERTIES ARE
  REFUSED OUTRIGHT, WHERE `docs/kir-design.md` §7 SAYS THEY EXPAND TO A FIELD-ASSIGNMENT
  PROLOGUE (2026-08-27).** Measured: `class Particle { x: number = 0; constructor(x: number)
  { this.x = x } }` emits `jsSet(this, "x", box(x))` beside a real `public double x`, and the
  native binary dies with `JsTypeError: dynamic member write 'x' is not supported on
  Kotlin/Native` inside `<init>`. `constructor(public x: number)` fails the compile
  (`KIR_SUCCESS=false`). This is design-doc contradiction (1) — "`this` types as `any`" —
  never closed on the WRITE side; §7 fixed reads by taking the property's type on the CLASS
  and the same answer is available here. **A class with a constructor is unrunnable on the
  native arm until this lands**, which is why the n-body fixture needed a factory function.

- [ ] **(KIR.NATIVE.2) A TYPESCRIPT PROGRAM THAT DECLARES ITS OWN `function main()` FAILS THE
  NATIVE BUILD WITH "the lowering produced no entry point" (2026-08-27).**
  `KirNativePlugin.kt:149` picks the generated entry with `singleOrNull { name == "main" }`,
  so a user `main` makes it TWO and the `?: error(...)` reports absence where the truth is
  ambiguity. The lowering already renames every generated top-level declaration
  `f<index>_<name>` to avoid serializer collisions — the entry should be found by that
  identity rather than by spelling, and the error message should name the collision.

- [ ] **(BENCH.2) THE KIR BENCH HAS NO CPU AFFINITY AND NO PRINTED PLACEMENT STRATEGY, AND
  ROUND 824 SAYS WHY THAT IS NOT A ONE-LINE FIX: A "SINGLE-THREADED" xtsc RUN CONSUMES
  ~4.17 OF THIS BOX'S 8 CORES BECAUSE `CICompilerCountPerCPU` IS TRUE.** Perry's harness
  (`benchmarks/README.md`, reviewed 2026-08-28) pins with `taskset -c 0` on Linux /
  `taskpolicy -t 0 -l 0` on macOS and **prints which strategy was applied at the top of each
  invocation** — a positive control on the instrument, which is the part worth copying
  whatever the pinning decision turns out to be. **THE TWO HALVES DIVERGE HERE AND MUST BE
  DECIDED SEPARATELY.** The arms that are single-threaded AOT workloads (`nat`, `wasi`, and
  the node/bun arms' own timed loops) are the shape pinning was designed for. The JVM arms
  are NOT: pinning `java` to one core serialises C1/C2 against the compile thread and
  measures a different program — round 824 measured `-XX:CICompilerCount=2` taking a run
  4.20 -> 2.55 cores, i.e. the JIT threads are a real part of the arm. **So the deliverable
  is an OPTION plus a banner, never a silent default**, and the grading is an A/A at fixed
  arms: pinning is worth taking only if the per-arm spread FALLS, measured per arm, and it
  must be reported per arm because it can plausibly fall for `nat` and rise for `kir`.
  **AND IT RESTARTS THE SERIES**: a pinning change is a recipe change, so every pre-change
  `kir-bench` figure quoted in this file becomes incomparable exactly as `BENCH-ROWS-V2` did
  for the dashboard — bank the decision with a paired before/after in ONE round or not at all.

- [ ] **(BENCH.3) `kir-bench.sh` PRINTS ITS TABLE TO A TERMINAL AND NOTHING COMMITS IT, SO
  EVERY KIR NUMBER IN THIS FILE IS HAND-TYPED PROSE — WHICH IS THE EXACT FAILURE MODE FOUND
  IN THE HARNESS WE ARE COPYING FROM (2026-08-28, measured by reading Perry's own artifacts).**
  Perry's README quotes convolution at **Perry 354 ms / Rust 392 ms** and cites
  `benchmarks/honest_bench/REPORT.md` for that row; that report's PROSE says **268 ms /
  Rust 567 ms** on different hardware (M1 8 GB, not the README's M1 Max), its header names
  `v0.5.81` while its own hardware table says `perry 0.5.1355`, and **its tables are broken
  outright — every wall median in all three workloads reads `0.0`/`-0.0` ms with sigma 0.0-0.1,
  and the ratio lines contradict both the table and themselves** (`rust = 18.48x, zig = 1.00x,
  perry = 40.67x` over a column of zeros; the convolution table prints `bun = 1.00x` under a
  bottom line claiming Perry won). A regeneration zeroed the artifact and the hand-written
  prose survived it, unnoticed. **Their ONE self-consistent table is the one generated between
  `<!-- public-node-bun:start -->` markers from a versioned JSON at a named commit — and it is
  also the only one that publishes the rows they LOSE** (`prime_sieve` 28 ms against node's 6,
  `matrix_multiply` 85 against 33). Round 930's law with someone else paying for it.
  **WHAT TO BUILD:** `kir-bench.sh` writes a versioned JSON (arms, per-process samples,
  median/sigma/min/max, the `sink=` verdict PER ROW, the commit, the box, the arm set it
  actually ran) and a generator emits the markdown between markers in `docs/perf/`, losses
  included and labelled. **THE GENERATOR MUST REFUSE ITSELF**: a table whose medians are zero,
  whose ratio column disagrees with its own medians, or whose row count is below the arm count
  is not printed at all — that is precisely the artifact Perry shipped, and rounds 853/873/895
  say a generator that emits quietly where it cannot see is the thing that keeps being wrong
  here. Cheap and separable from (BENCH.2); do this one first.

- [ ] **(BENCH.4) THE TS-TO-NATIVE CATEGORY HAS EXACTLY ONE ARM THAT COULD TAKE OUR FIXTURES
  UNMODIFIED, AND IT IS PERRY ITSELF — NOT AssemblyScript (2026-08-28).** Perry's own peer
  classification (`benchmarks/README.md`) is worth adopting verbatim in our table header:
  **runtime peers** (same input language, same job — for them node/bun, for us tsgo),
  **TS-to-native peers**, and **calibration** (hand-written compiled code, "NOT peers ... they
  show the floor"), with each row labelled so a reader who is not us can tell which is which.
  It also records that of the three TS-to-native candidates, **porffor 0.61.13 and Static
  Hermes were not bench-ready** and only AssemblyScript-with-`json-as` ran their workload to
  completion. **THE RANKING FOR US IS THE OPPOSITE OF THE ONE I FIRST GAVE, AND THE
  EQUIVALENCE GATE IS WHY**: `mitt` and `smol-toml` are plain TypeScript, Perry is MIT and
  installs with `npm install -g @perryts/perry`, so it can compile OUR fixture bytes and print
  OUR `sink=` — an arm the existing gate can hold. AssemblyScript cannot: it is a TS-SYNTAX
  subset with its own semantics, so the fixture would have to be PORTED, and a ported program
  is a different program that the gate can only wave through. **So: Perry as a real arm behind
  its own opt-in flag (the `KIR_BENCH_NATIVE` shape — REFUSE, never skip, when the toolchain
  is absent); AssemblyScript only as CALIBRATION and only with the port's divergences written
  down; and if the fixtures do not compile under either, the item CLOSES with what refused
  them recorded** — Perry has no type checker (SWC parses, LLVM codegens; no conformance
  claim anywhere in its docs), so a refusal there is a fact about its lowering coverage and is
  worth having next to (LIB.4)'s thirteen rungs. Note the scale that sets: Perry's runtime
  completeness is ~97% of Node's own suite across 53 `node:*` modules plus ~50 npm packages.

- [x] **(DOC.1) DONE 2026-08-24 — `CLAUDE.md` 427 -> 320 KB (-25.1%) by MOVING 107 entries
  to the archive, nothing deleted, conservation PROVEN mechanically** (490+728 = 1,218 ->
  383+845 = 1,228; the +10 are entries distilled in place, full text archived). Moved: ~47
  per-walker, ~29 per-diagnostic, ~28 per-instrument perf narratives, and 6 exact
  duplicates (unique clauses folded into the survivor). Distilled 10, led by the INV.4
  check-spine cookbook **13.3 KB -> 1.7 KB**. Protected sections byte-identical (14,078 B,
  `cmp` clean).

- [ ] **(DOC.2) THE REMAINING `CLAUDE.md` LEVER IS DISTILLATION, NOT MOVING — 383 RESIDENT
  ENTRIES AVERAGE 780 BYTES AGAINST THE FILE'S OWN "1-3 LINES" RULE.** (DOC.1) established
  the arithmetic and it is in the header ladder: header 3.6 KB + protocol 14.1 KB + the
  protected (INC.*)/2026-08-2x set 61.8 KB = a **79.5 KB floor before one process trap is
  kept**, so the ~91 KB target cannot be reached by moving. **Only ~84 KB of the 336 KB
  added since 2026-07-26 was archive-assigned narrative** — the rest is in categories the
  rule KEEPS, but at 5-6 lines each where the rule says 1-3.
  **THE MECHANISM IS (DOC.1)'s OWN, ALREADY EXERCISED TEN TIMES AND SAFE**: archive the
  entry's full text, leave a resident form that states the trap/invariant and where to
  look, and drop the fix story. **Nothing is lost, so this is not a judgement call about
  value** — it is the format rule applied to entries that already passed the residency
  test. Target ~200 KB.
  **START WITH THE FREE 11.5 KB (DOC.1) NAMED**: 15 of the 72 date-protected entries are
  the KIR / Kotlin-native BACKEND arc, not the incremental language-service arc whose
  liveness justified the protection. Confirm with the owner whether that arc is parked; if
  so they are archive candidates outright rather than distillation ones.
  **DO NOT distil**: the measurement-protocol laws, the Gradle/daemon/memory traps, the
  narrowing-probe fixture conventions (their loss silently produces VACUOUS pins), or any
  entry whose invariant IS its detail. **Verify as (DOC.1) did** — conservation by exact
  string match, protected sections byte-identical by `cmp`, and a read-through; `git diff
  --stat` proves an edit landed, never that it is correct.
- [x] **(INC.1) A NARROWED DIAGNOSTICS QUERY — LANDED 2026-08-22.**
  `Project.diagnosticsOf(fileNames)`, 4,818 -> 1,107 ms warm, all 78 files of the compiler
  profile agreeing row for row. See the session note; the gate is
  `scripts/partition-equivalence.sh` and the prize was measured first by
  `scripts/incremental-cost.sh`.

- [x] **(INC.2) NARROWING THE INTERACTIVE CAPTURE QUERIES — REFUSED 2026-08-22, AND THE
  REFUSAL IS A MEASUREMENT.** It would have been **3.73x** (full capture median 4,614 ms
  against a narrowed 1,110; warm rotated on `binder.ts`, 7,787 spans: 4,719 vs 1,264).
  `scripts/capture-equivalence.sh` compared **381,666 spans over 76 files**, both arms,
  span for span: **45 spans in 11 files diverge — types 45, definitions 0.**
  **THE SHAPE:** a type reference INSIDE a foreign file's ANONYMOUS OBJECT TYPE LITERAL
  renders `any` under the partition where the whole-program build renders the declared
  type — `(state: { program?: any | undefined; compilerOptions: any })` for
  `{ program?: Program | undefined; compilerOptions: CompilerOptions }`. The outer
  signature survives; it is the literal's MEMBERS that collapse.
  **THE MECHANISM IS FIRST-TOUCH CACHE ORDER, NOT THE PARTITION, AND THE CENSUS PROVES IT
  RATHER THAN ASSUMING IT: in 5 of the 45 the FULL build is the one rendering `any` where
  the narrowed one renders `T`** (`(key: K, valueInNewMap: U) => any` against `=> T`).
  `symbolTypes` persists the first resolution (round 778's order-dependence), and which
  file touches a foreign type first differs between the arms. So the diff is a DETECTOR
  for a defect that is already there — see (INC.5) — and narrowing merely makes it
  observable.
  **IT DOES NOT REACH DIAGNOSTICS, AND THAT WAS MEASURED TOO, BECAUSE IT IS THE QUESTION
  (INC.1) RESTS ON.** A fixture whose error exists only while the literal's member keeps
  its declared type (`const n: number = make().program`, where `make(): { program: Program }`
  lives in a second file and `Program` in a third) is reported IDENTICALLY by the
  partition — `ProjectNarrowFalseNegativeTest`, and the whole-project sweep on the same
  fixture agrees. **Its FIRST shape was vacuous** — an argument-position error
  (`use({ program: 1 })`) this compiler does not report at all, so both arms agreed on an
  empty list and the pin passed while measuring nothing. Its own control caught that,
  which is the reason to write one.
  **SUPERSEDED BY (INC.2b), WHICH LANDED THE NARROWING ON 2026-08-22 AFTER (INC.5) AND
  (INC.6) TOOK THE 45 DIVERGENT SPANS TO 5 WITH THE WRONG-DIRECTION COUNT AT ZERO.** The
  refusal below stands as the reasoning it was, and its premise — 45 spans where a
  narrowed hover renders a worse type — no longer holds. What the refusal bought is the
  two defects it found on the way, and the two gates that now watch the whole thing.
  ORIGINAL VERDICT: **hover, completion, go-to-definition and signature help stay whole-program builds.**
  A tooltip that says `any` where the type is `Program` is a worse defect than a slow
  tooltip, and 45 wrong spans is 45 too many for a query whose only job is to tell the
  truth about a type. Re-run the sweep after (INC.5) and this lands for free — the harness
  and the script are committed, so the re-test is one command.

- [x] **(INC.6) THE LAST 4 WRONG-DIRECTION SPANS ARE GONE — LANDED 2026-08-22.** The
  capture sweep reads **5 divergent spans in 3 of 76 files** out of 381,666, and
  `narrowRendersMoreAny = 0`: the whole user-visible class is closed. The fix is one line
  plus its KDoc in `materializeModifierUtility` — the member copy's type is populated AT
  MINT TIME, ungated. **The diagnosis in the entry below HELD and was sharpened by the
  trace**: the copies being fresh is only half of it, and the half that explains why
  (INC.5)'s pin was green is that `getTypeOfSymbol` RESOLVES the member correctly every
  time and round 778's write gate refuses to RECORD it whenever the ambient context is
  non-empty — which inside a `namespace` body it always is. So (INC.5)'s force-then-read-
  the-cache is a no-op exactly there. Suite 15,640 / 0 / 3, no corpus baseline moved, cost
  gate's drift measured PRE-EXISTING against the un-fixed binary. The 5 REVERSED rows are
  diagnosed in the session note and are three separate display-only mechanisms, in four of
  which the NARROW arm is the better answer. ORIGINAL ENTRY: **THE LAST 4 DIVERGENT SPANS,
  AND THEY ARE WHAT STANDS BETWEEN (INC.2) AND A 3.68x LANGUAGE SERVICE.** After (INC.5) the capture sweep reads **9 divergent spans in
  4 of 76 files — 4 wrong-direction and 5 reversed**, out of 381,666. All 4 of the
  wrong-direction rows are `Readonly<BuilderState>` in `builderState.ts`, and the cause is
  named: `materializeModifierUtility` mints FRESH copy symbols on every materialization,
  so warming one dies with the instance, where `Pick`/`Omit` cleared precisely because
  `materializeMemberSetUtility` reuses the SOURCE symbols and their ids are stable. The fix
  is to populate `symbolTypes[copy.id]` AT MINT TIME in the materializer — which
  `getTypeFromTypeLiteral` and `getTypeFromMappedType` already do — and that is **not
  capture-scoped**: it would put diagnostic messages in play, so it needs the corpus as its
  gate rather than the sweep alone. (INC.5) deliberately stopped short of it.
  **The 5 REVERSED rows are a different family and may not be a defect at all**: 2 in
  `tsbuildPublic.ts` where the WHOLE-PROGRAM arm renders `(key: K, valueInNewMap: U) => any`
  and the narrowed one the better `=> T`, 2 in `watch.ts` (overload-set content), 1 in
  `watchPublic.ts` rendering a signature twice. None is a lost member resolution. Diagnose
  them before assuming they are one.

- [x] **(INC.2b) LANDED 2026-08-22, owner directive — the caret-scoped capture queries
  are narrowed.** Hover, go-to-definition, completion, signature help, the semantic sweep
  and document highlights hand the compiler the queried BUFFER as its check partition;
  `referencesAt` and the rename sweep do not, because their claim is program-wide.
  Measured `quickInfoAt` **5,004 -> 1,015 ms** end to end with three flat controls, and
  **4,581 -> 979 ms (4.68x)** within one process on `binder.ts`. The partition is DERIVED
  from the request's spans, which is what makes the pins discriminate. See the session
  note for the second gate this needed (`scripts/capture-channel-equivalence.sh`, for the
  three channels the old one never covered) and for the five display mechanisms it found.
  ORIGINAL ENTRY: **OWNER DECISION: LAND THE CAPTURE NARROWING NOW, OR AFTER (INC.6)?** The
  refusal recorded above was written against 45 divergent spans; after (INC.6) it is
  **ZERO** in the user-visible direction — `narrowRendersMoreAny = 0` over 381,666 spans —
  against **5.26x** measured this round on every hover, completion, go-to-definition and
  signature help. **What is left is 5 spans in 3 files, all display-only and all diagnosed in
  (INC.6)'s session note: 2 where the narrow arm renders the ALIAS name (`Intl.LocalesArgument`)
  and the full arm its expanded body, 2 where the FULL arm renders a generic interface
  member's return as `any` where the narrow renders the declared `T`, and 1 where the narrow
  arm renders an intersection member as the redundant `X & X`. In 4 of the 5 the narrow arm
  is the better answer.** So the correctness argument for waiting has inverted: the
  whole-program arm is now the one rendering a worse type more often, and the wiring is a
  one-line change per call site. **Not decided
  autonomously: it trades a measured correctness regression against a measured latency win,
  which is the owner's call.** Everything needed to execute either way is committed — the
  gate, the census and the call sites are named in (INC.2).

- [ ] **(INC.8) THE TWO DISPLAY MECHANISMS (INC.2b)'s SECOND GATE FOUND, AND NEITHER IS A
  PARTITION DEFECT.** `scripts/capture-channel-equivalence.sh` reads 286 divergent rows of
  21,507 in five mechanisms; three are worth closing and none can be closed on the capture
  path, because the renderer is shared with the diagnostics (the (INC.5) rule: never
  `typeToString`, ~13k baselines).
  (a) **x167 — a member's own type parameter renders `<K>` under one arm and
  `<K extends any>` under the other, and NEITHER renders the declared constraint**
  (`shouldAssertFunction<K extends keyof typeof assertionCache>`). That is a defect in BOTH
  arms, like (INC.6)'s `Readonly<T>`: the sweep only made it visible.
  **DIAGNOSED ONE LEVEL DEEPER 2026-08-23 by (INC.19), which also REFUTED the obvious
  guess.** It is NOT (INC.19)'s first-touch freeze: the fix that took the replay's lost
  constraints from 8 files to 5 left these 167 rows **byte-identical** (the whole
  channel unchanged at 286 spans / 49 files). A probe on the shape reads `TPWRITE
  name=K was=any now=any` — the constraint is **already `any` before
  `checkTypeArgumentConstraints` runs**, so nothing downstream can be blamed. It is a
  **namespace-local type alias failing to resolve in constraint position** — a NAME
  RESOLUTION defect, not an ordering one. Start there, not at the renderer.
  (b) **x116 — an alias's expansion carries `| undefined` TWICE**
  (`string | Locale | readonly (string | Locale)[] | undefined | undefined`). Two defects in
  one row: the duplication, and the fact that a first-touch `aliasDisplayMap` registration
  decides whether the alias name or its body is printed. tsc prints the alias.
  (c) **x1 — a signature parameter renders `any` under the narrowed arm.** The ONLY row in
  either channel where narrowing produces the answer a user would call wrong. Same family
  as (b); worth a trace before (a) or (b), because it is the one with a cost today.
  Not worth a round on its own; fold into whichever round next touches the display of a
  signature or an alias.

- [x] **(INC.3) THE FLOOR IS DECOMPOSED — step 1 DONE 2026-08-22, and it inverted its own
  lever order.** 1,219 ms on the compiler profile: **tail walkers 806.7 (66.2%)**, `init:*`
  setup 112.2 (9.2%), **BIND 240.6 (19.7%)**, crawl 27.4 (2.2%), `checkSpine` **0.1 ms**,
  residue 3.1 (the partition closes at 99.7%). `scripts/floor-decomposition.sh` is the
  instrument; the session note carries the four refuted beliefs — bind is not 515 ms (that
  is a per-WORKER contended term), the crawl is not 138 ms (parses are fully cached),
  `init:buildFileLocalTypeMaps` is not 3.56% (1.4%), and the two never-warming
  whole-program regex passes are already gone (0.44 ms). **What it leaves is (INC.7), a
  bigger lever than either of the two this entry used to rank first.**

- [x] **(INC.9) THE FLOOR RE-DECOMPOSED AND ITS LARGEST MECHANISM DEFERRED — LANDED
  2026-08-22.** Re-measured rather than scaled (the (INC.3) table was taken at a 1,219 ms
  floor; 68 gated walkers later it is a different table): of a ~523 ms floor, CHECK — the
  ~190 surviving `init` passes — is **304.2 ms (58.2%)**, BIND **197.8 (37.8%)**, crawl +
  config + imports + post 18.4 (3.5%). Bind is NOT the largest component, but it holds the
  largest single MECHANISM: `FlowGraphBuilder.build` at **126.1 ms = 24.1% of everything a
  narrowed query costs**, against a pass table whose biggest row is 66 ms.
  **`BinderResult.flowGraph` now builds on first ask** — floor **514 -> 378 ms**, narrowed
  query median **542 -> 422**, ratio at the median file **9.70x -> 12.43x**, and
  `partition-equivalence.sh` EQUIVALENT on all 78 files. This is exactly the candidate
  `docs/perf/warm-flow-graph-attribution.md` § 9.3 priced at **0.3%** and refused — a
  correct number about a FULL build, where every checked file's spine setup asks for its
  graph; under a partition the same rule reaches 122 of 123 files. **REFUSED in the same
  round, with the measurement: a cross-query BIND CACHE.** All of bind is now 72 ms of a
  378 ms floor, so the ceiling is 19%, and against it every `BinderResult` from one
  `Binder` SHARES its `(pos, end)`-keyed `nodeToSymbol`/`moduleInstanceStates` maps (they
  are the binder's fields, accumulated across files, and those keys collide across files),
  while `mergeSingleSymbol` adopts binder-owned symbols and `declarations.addAll` is not
  idempotent. Large, silent-failure-shaped, for 72 ms.

- [x] **(INC.10) ONE OF THE TWO PROGRAM-WIDE SETUP PASSES IS GONE; THE OTHER IS
  REFUSED WITH A THREE-POINT MEASUREMENT.** `init:trackAllImportReferences`
  (**29.44 ms**) is EMIT-ONLY work — its product `referencedAliases` has one
  reader, `isReferencedAliasDeclaration`, which has one caller, one line of
  `Transformer` reached only by `import x = require(…)` under `module: preserve`
  — so it now runs on that first ask and a `--noEmit` build performs it **0**
  times (was one per file per checker, i.e. N under `CheckerPool`). Floor pass
  table **305.3 -> 274.8 ms**, narrowed query median **422 -> 402**, ratio
  **12.43x -> 12.61x**, and the banked ms EXCEEDS the row (30.5 vs 29.44) because
  this walk resolves nothing, so the (INC.7) relocation discount has nothing to
  describe. **`init:buildFileLocalTypeMaps` (66 ms) IS REFUSED, and it was built
  before it was refused**: the deferral works and is cheap (78 -> 3 maps built on
  the floor arm, row 66.07 -> 0.01, query median 349, ratio **14.17x**,
  `partition-equivalence` EQUIVALENT, cost gate and corpus unmoved) and it moves
  the CAPTURE channel from **5 divergent spans to 2,722 in 46 of 76 files**. The
  pass's real product is not the 4,161 entries round 829 censused but the
  whole-program FIRST-TOUCH ORDER for type interning and `aliasDisplayMap`; keep
  the `TypeAlias` symbols eager and it is 6.81 ms / 462 spans, keep the whole
  DECLARATION branch eager and it is **64.94 ms / 5 spans** — i.e. the deferrable
  part is **1.13 ms of 66**. Do NOT re-open it from round 829's read-count
  census: read-ness of the ENTRY is the wrong question.

- [x] **(INC.12) THE WARM PROGRAM IS PRICED, AND STAGE 1 LANDED 2026-08-22.**
  **(P1) — a second query with the program UNCHANGED — is worth the WHOLE ~345 ms
  floor** (config+crawl+imports ~12, BIND 73-88, the ~190 program-wide `init` passes
  252-254), against a queried file's own checking of 47 ms at the median file.
  **(P2) — a query after ONE buffer changed — measured IDENTICAL to (P1)**
  (`diagnosticsOf` after editing the queried file 2,001 ms against 1,999 unedited),
  because outside the content-keyed parse cache there was no cross-query reuse at all.
  **LANDED: `Project.captures`** — a capture build memoized on its REQUEST, two entries,
  dropped by every edit: `quickInfoAt` then `definitionsAt` at one caret is ONE build
  (506 -> 0), `documentHighlightsAt` at every later caret in an unchanged buffer is zero
  builds (592 -> 19, the residue being the per-caret grouping), a repeated hover
  1,933 -> 0. Three ablations, each reddening a different pin set.
  `scripts/warm-program-cost.sh` is the instrument; `docs/language-service.md` §§ 13-14
  carry the table. **REFUSED with the measurement**: reusing the BIND (73-88 ms = 20% of
  a median query — not refused by (INC.9)'s per-file argument, but it needs a shape gate
  reusing the checker's own merge predicate plus a full-vs-reused differential sweep,
  see (INC.13)); and reusing the CHECKER (252-254 ms = 63%, the largest thing left, and
  the one that makes WHICH QUERY RAN FIRST observable — see (INC.14)).

- [x] **(INC.13) STAGE 2 LANDED 2026-08-23 — THE QUESTION A HOVER ASKS IS THE
  BUFFER'S, NOT THE CARET'S.** `Project.captureAround` names
  `SourceIndex.occurrenceNodes()` — deliberately `documentHighlightsAt`'s own
  population — so `quickInfoAt`, `definitionsAt`, `semanticsAt`/`fileSemantics` and
  highlights are **ONE build per buffer between them**. A second caret in `checker.ts`
  **2,142 -> 73 ms**, in `binder.ts` **481 -> 2**, `fileSemantics` after a hover
  **575 -> 17**; the FIRST query in a buffer pays for it, **+27% on `binder.ts`,
  +65% on `checker.ts`**, i.e. break-even at the second caret. **The oracle was built
  first and needed no baseline** (`scripts/caret-vs-file-capture.sh`, 904 sampled
  spans in 76 files: **EQUIVALENT**, and the widening prices at **+17 ms at the
  median file**). It does NOT widen for a caret on a node that is no occurrence — a
  call expression, a literal, a `this` — because a file-wide request would not carry
  it and an absent capture renders nothing with no error anywhere. Three ablations;
  A3 was BLIND until the fixture grew a member-name literal. **The 34x batching ratio
  `docs/language-service.md` advertised to hosts is GONE** — batching a buffer is now
  a convenience, not a cost decision.

- [x] **(INC.15) REUSING THE BIND FOR AN UNCHANGED PROGRAM — REFUSED 2026-08-23,
  AND THE REFUSAL IS A RE-PRICING, NOT A SOUNDNESS FINDING.** The mechanism checks
  out: on today's binary `--bindMutationCheck` reads **`binder Symbols checked
  15580, changed 0`** over a population that reaches transitively through
  `locals` + `nodeToSymbol` + every `members`/`exports` table, in the SAME run as
  `mergeSingleSymbol: adopts 406, mutates 175 (164 reaching an adopted symbol)` —
  every one of those 175 mutating merges lands on a LIB symbol, which is in no
  program `BinderResult`. `mergeModuleAugmentations` was read line by line as the
  queue entry asked: its four writes are `globals[name] = augSymbol` (a same-value
  put), `flags or …` (idempotent), `declarations.add` guarded by `if (decl !in …)`,
  and `mergeSymbolTable` into an `exports` table — and only the LAST of those is
  non-idempotent, because `mergeSingleSymbol`'s existing-name branch does a bare
  `merged.declarations.addAll(symbol.declarations)`. On this program it never fires
  against binder-owned state, which is what the zero says.
  **WHAT REFUSES IT IS THE POPULATION, RE-PRICED AGAINST (INC.13)'s FLOOR.** Bind is
  **66–74 ms of a 359–407 ms floor (18.4%)**, and of that **69 of 74 ms is
  `bindLexicalScopes`**. Against a QUERY it is 12.8% of `diagnosticsOf(binder.ts)`
  (547 ms), **10.7%** of a first hover in that buffer (655 ms), **3.1%** of a query
  about `checker.ts` (2,232 ms), and **2.75% of the whole 15-query editor sequence
  `warm-program-cost.sh` drives** (~10.2 s). And the eligible population is
  "the program is UNCHANGED since the previous build", which **excludes the first
  query after an edit — the error-reporting query the owner directive names — where
  it is worth exactly 0**.
  **AND IT IS THE WRONG ORDER: (INC.14) SUBSUMES IT BY CONSTRUCTION.** A reused
  `Checker` carries its own bind, so bind reuse is 20% of a floor that checker reuse
  removes 100% of, and the plumbing (a content-keyed cache threaded `Project` ->
  `ProjectCompiler` -> `compileParsed` -> `compileParsedCore` -> `cpcBindAndCheck`)
  would be thrown away by it. A third fact against doing it first: the checker's own
  merge predicate is `moduleLocalContributesGlobally`, which reads `umdGlobalNames`
  and `mergeSharedKeepNames` — both computed INSIDE `Checker`'s init — so the shape
  gate the queue entry demands can only be evaluated AFTER a build. The design is
  therefore necessarily "build once fresh, reuse only if that build reported clean",
  and the first query of a session never benefits either.
  **WHAT SURVIVES AS A LEAD, and it is bigger and better shaped**: `bindLexicalScopes`
  is **93% of the bind** and the INV.2(c) tables it builds are read per-FILE, so
  (INC.9)'s exact deferral template applies — see (INC.16).

- [x] **(INC.16) LANDED 2026-08-23 — THE INV.2(c) TABLES BUILD ON FIRST ASK AND A
  NARROWED QUERY IS 20.5% FASTER.** `bindLexicalScopes` was 93% of the bind and, after
  (INC.7) batch 4 and (INC.11), the largest single remaining mechanism in the floor.
  **Scope tables built on a floor build 123 -> 3; `FrontEnd` bind 70 -> 6 ms; floor
  median 333 -> 286 ms; narrowed-query median over all 78 files 346 -> 275 ms
  (−20.5%), the SUM 29,378 -> 23,909 ms.** `partition-equivalence.sh`'s own recipe
  reads floor 248 / median 313 / ratio **15.66x**.
  **THE BLOCKER WAS SERVED BY A PROJECTION, NOT BY GATING.** A `forcedBy` census
  confirms `init:computeAllEnumValues` was the SOLE forcer of all 78 program files.
  `declareLexical`'s two mint sites are NOT symmetric — the alias half wants a NAME
  (the binder hands it over), the enum half wants the scope-space SYMBOL (`compute-
  EnumSymbolValues` is id-keyed) — so only an `enum` in a fresh scope forces a build,
  and the projection costs two int compares per node on a walk that already runs and
  is content-cached. Refinement measured: 67 of 78 skipped, then 69, then **75**.
  **HAZARD (a) DID NOT FIRE AND WAS REMOVED ANYWAY.** An ID-FREE FINGERPRINT of every
  file's tables is IDENTICAL on all 78 across three runs — but that bounds frequency,
  not existence, so `Binder.lexOwnerSymbols` (a per-file `nodeId -> Symbol` table)
  replaces both reads of the shared `(pos,end)`-keyed `nodeToSymbol`. Order-independence
  is now structural; arm a4 reddens a pin built from two same-length sources whose
  namespaces collide on a node key.
  **LEFT OPEN (~20 ms)**: 3 files still force on the floor — those with a genuinely
  block-scoped `enum`, where the census needs the SYMBOL and not a name. Serving them
  means minting that symbol outside the scope walk, a larger change than this round's.
  The 45 real-lib `.d.ts` binds are forced by nobody and are worth only ~2 ms.
- [x] **(INC.14) A `Checker` NOW ANSWERS A WHOLE WORKING SET — LANDED 2026-08-23 as
  `Project.prepare(files)`, plus a partition-keyed `diagnosticsOf` memo beside it.**
  252-254 ms of every query's floor is the ~190 program-wide `init` passes, and the
  census said a checker shared by k queries answers all k exactly as k fresh ones do.
  **The refactor the entry called for was not needed, and the census's own model is
  why**: a checker asked a k-th query IS a checker whose partition is those k files,
  and that arrangement is expressible with no checker surgery — hand `recheckOnly` the
  working set once and capture all of it in the one walk. `prepare` is the census's
  SHARED arm made public.
  **THE ORDER GAP THE ENTRY NAMED IS CLOSED FIRST, AND IT CLOSED CLEANER THAN PROGRAM
  ORDER.** `checker-reuse-differential.sh` grew an `editor` arm — a deterministic
  shuffled query SEQUENCE with revisits, chunked into groups, compared POSITION BY
  POSITION, with the COLD arm run over the same sequence so "is the reference itself
  order-dependent?" is a control (`coldSelfDiverged`, which REFUSES the run) and not an
  assumption. 101 queries over 76 files, 25 revisits, **1,070,012 compared rows per
  run**: **0 divergent rows at k=3 (2.16x) and k=8 (3.88x)**, **1 at k=26 (5.18x)** and
  that one is byte for byte the row program order already found (`watchPublic.ts@24148`,
  the COLD arm inventing `X & X`), already inside `capture-equivalence.sh`'s 5-span
  baseline. `coldSelfDiverged = sharedSelfDiverged = 0` in all three — a revisited file
  is answered identically by a fresh checker AND by a reused one.
  **MEASURED, six mid-sized buffers (55-83 KB, 415 KB together; deliberately not
  `checker.ts`, whose 1.65 s of own checking would bury the floor), three rotations,
  replicated in a second run**: 18 semantic queries **5,230 -> 737 ms and 4,997 -> 704
  (7.1x both)**; six per-buffer `diagnosticsOf` **2,338 -> 526 and 2,376 -> 539**, with
  every re-ask **0**. The existing 15-query block is a CONTROL and did not move.
  **What a held prepared check costs, with a control rather than as an absolute: heap
  163 -> 167 MB, identical to the MB in all six rotations — ~4 MB for that working set.**
  Bound: ONE prepared check, replaced by the next `prepare`, dropped by any edit.
  **Three rules, each with its pin**: the prepared slot is SEPARATE from the two-entry
  capture LRU (an ordinary hover cannot evict what a prepare earned); serving is decided
  by CONTAINMENT of the asked spans against the prepared REQUEST's own spans, never by
  file membership (an answer never asked for is ABSENT, and a hover served from a check
  that did not carry its span renders nothing, silently); and a prepared check may NOT
  answer `diagnostics`/`diagnosticsOf`, because a capture build types nodes the checker
  had no reason to type. **Seven ablations, seven discriminating, each with its own RED
  set** — the first round this session with no arm recorded as a control.
  **REFUSED with its arithmetic: making the working set AUTOMATIC.** Growing the
  partition to `{queried} ∪ {recently queried}` on every miss costs `k·floor +
  k(k+1)/2·perFile` against a cold `k·floor + k·perFile`, i.e. a LOSS at every k with
  the floor at 342-365 ms and a median file at 31-47 ms; bounding the growth at B makes
  every miss `(B−1)·perFile` dearer (+42% at B=4 on a median file, far worse on
  `checker.ts`). A host knows its open buffers and this layer does not.
  `docs/language-service.md` §§ 3, 3a, 13, 14.

- [x] **(INC.17) THE RE-ENTRANT CHECKER — BUILT, MEASURED AT 3.06x, AND **REFUSED AS A
  DEFAULT PATH** 2026-08-23. STEP 1 (THE CENSUS) STANDS.** `prepare` collects the floor for
  files a HOST NAMED; a query about a file it did not name still pays the whole
  342-365 ms. Measured with `scripts/partition-census.sh` (a RUNTIME classification —
  `checkedResults` is a getter recording `PassTiming.currentPass`, so it cannot be
  wrong about who read it — six draws, three partition shapes, tsc's own 78 sources):

  | bucket | rows | floor ms | one-file ms |
  |---|---:|---:|---:|
  | partition-INVARIANT | **211** | **350.89** | 375.44 |
  | partition-DEPENDENT | **205** | **15.59** | 55.05 |
  | total | 416 | 366.47 | 430.49 |

  **The prize is 95.7% of the floor and the replay's own fixed cost is 0.69 ms** —
  204 of the 205 dependent passes cost that BETWEEN them, because 201 read the
  partition exactly once (`for (result in checkedResults)` and nothing else). The
  205th, `checkSubsequentVarTypes`, is 14.90 ms with an EMPTY partition: a MIXED pass
  doing program-wide work outside its partition loop, and splitting it is the whole
  difference between 15.6 and 0.7.
  **The model is SMALLER than (INC.14) priced.** No diagnostics prefix has to be
  reset: a program-wide pass iterates `binderResults`, so it ALREADY emitted the newly
  asked file's rows in the first build and `getDiagnostics()` merely filtered them out
  at the end. A replay re-runs the 205 with the new partition and re-filters.
  **WHAT BLOCKS IT IS THE INSTRUMENT.** On the tsc profile the full build's 46
  diagnostics are netted by exactly ONE pass (`checkSpine`; the new signed-delta
  census reads 46 against the build's own 46, its positive control), so
  `partition-equivalence.sh` — the designated detector — compares an essentially EMPTY
  population, and the other seven profiles are the same codebase. A replay that
  produced nothing from 204 of the 205 passes would be invisible to every gate here.
  **And the classification is not yet the one soundness needs**: it measures *reads the
  partition*, where the replay needs *its OUTPUT depends on the partition*, and the two
  come apart at every spine-produces / program-wide-pass-consumes pair.
  **UNBLOCKED 2026-08-23 by (INC.18)**, which re-armed the gate — 78 netting passes and
  72 of 76 files carrying a row, against the profile's 1 and 5 — and PROVED it can
  fail: a partition-dependent walker made silent under a narrow partition reddens the
  sensitivity arm while the realism arm stays green (arms a1/a2). **Two obligations
  survive.** The classification still measures *reads the partition* where soundness
  needs *its OUTPUT depends on the partition*; and (INC.18)'s arm a3 shows the one
  round-609 collector it tried is invisible to a DIAGNOSTICS gate in BOTH arms (it is
  `capture-equivalence.sh`'s to own), so a replay must be graded on both sweeps.

  **STEP 2 IS BUILT AND IT IS REFUSED. THE PRIZE IS REAL: 3.06x** on tsc's own 78
  sources — `replay=12572 ms` against `freshBuilds=38498 ms` over 75 questions.
  The mechanism is in the tree and OPT-IN by construction (`Recheck.kt`,
  `Checker.recheckAdditionalFiles`, `build(recheckHolder = ...)`); nothing in a
  shipped path passes a holder and `Project` does not know the type exists.
  **WHAT REFUSES IT is the second sweep, exactly as (INC.18)'s arm a3 predicted.**
  `scripts/replay-differential.sh` reads
  `compared: files=75 diagnosticRows=46 filesCarryingDiagnostics=5 typeSpans=373879
  definitionSpans=352713` and then **`DIVERGED: 8 of 75 file(s)`** — with the
  DIAGNOSTICS half completely untouched. The shape is a **lost type-parameter
  constraint**: the replay renders `<T extends Node, U>` where a fresh build renders
  `<T extends Node, U extends T>`. A wrong hover is worse than a slow one, and
  (INC.2) set the precedent by refusing capture narrowing over 45 divergent spans;
  8 divergent FILES is far past it.
  **WHAT LANDED ANYWAY**, so (INC.19) starts from an oracle rather than rebuilding
  one: the mechanism marked EXPERIMENTAL at every entry point, `ProjectRecheckTest`
  pinning what it ACTUALLY does (diagnostics equivalence, the build-count receipt,
  the behaviour-free arming — and deliberately NOT capture equivalence, which would
  be a false pin), `scripts/replay-differential.sh` + `ReplayDifferentialMain`, and
  the `checkSubsequentVarTypes` split the census demanded (15.59 -> 0.69 ms of
  replay cost), pinned on both sides by `PartitionCensusHookTest`.
  **THE ATTRIBUTION ARM THAT DID NOT WORK, so nobody re-runs it:** re-entering ALL
  passes over **7** targets burned **53 minutes of CPU without finishing**, against
  ~50 s of total compute for the 205-pass replay over **75** targets — ~100x, the
  signature of a pass that appends to a side table or re-emits per replay. Killed,
  not completed. (INC.19)'s instrument is a BISECTION, not that arm.

- [ ] **(INC.19) THE LOST CONSTRAINT IS FIXED AND IT WAS NEVER A REPLAY DEFECT —
  8 -> 5 DIVERGING FILES, AND THE SURVIVORS ARE A DIFFERENT CLASS (2026-08-23).**
  The queue entry this replaces said "the replay SET is too small — bisect it".
  The instrument was built (`aca8a60f`) and REFUTED that: three causes were
  measured, and the dominant one is reachable by no replay-set change at all.
  **(c), THE DOMINANT ONE — FIXED (`7b1cc323`).** `Type.TypeParam.constraint` is
  interned per node and WRITE-ONCE, and `checkConstraintsInStatements` resolved it
  BEFORE installing the type-parameter scope, so `U extends T` resolved its sibling
  against the outer scope, answered `errorType`, and froze. `checkSpine` (row 28,
  partition-scoped) races `checkTypeArgumentConstraints` (row 261, program-wide) for
  the field; unpartitioned, `checkSpine` always wins, which is why all ~13k corpus
  baselines are blind. Two sites hoisted, and the third — `withDeclTypeParamScope` —
  **must NOT be hoisted**: a self-referential alias (`type Shared<I, D extends
  Shared<I, D>>`) then recurses without bound and the `init` guard reports a
  spurious TS2589. It got the write-once guard instead, which it lacked, so it can
  no longer CLOBBER a correct constraint. Pinned by `ProjectRecheckConstraintTest`,
  verified 2-of-3 RED against HEAD with its control green.
  **(a) REAL BUT SMALL, NOT LANDED.** `init:computeAllEnumValues` is classified
  partition-INVARIANT and yet repairs `program.ts` when added to the replay set
  (replicated) — its row is a block-scoped `const enum`, the B83.5 population. Worth
  landing only once the replay ships.
  **(b) REAL, AND IT BOUNDS THE WHOLE DIRECTION.** `init:wireGlobalArrayTypes` does
  not TERMINATE when replayed; `init:mergeLibGlobals` makes the answer strictly
  WORSE (+1 file). So the replay set is a PER-PASS question, never a superset or
  subset one, and each addition must be measured.
  **WHAT IS LEFT: 5 files, 23 spans of 373,879, and no lost constraint among them.**
  They are lost generic INFERENCE — `Connection[][]` -> `any[][]`, `Map<string,
  SeenPackageName>` -> `Map<any, any>`, `(key: K, valueInNewMap: U) => T` ->
  `… => any`. Diagnose that class before touching the replay set again.
  **THE INSTRUMENT IS COMMITTED AND RESUMABLE**: `scripts/replay-bisect.sh`
  (`dump`/`sweep`/`try`/`narrow`), `PassTiming.replayExtraPasses`, and a RUN-TIME
  pass universe — a source grep of `pass("…"` reads **480** names against the
  dispatch's **417**, so a grep-derived bisection could never have closed. 19 of 210
  candidates are swept; `build/bench/replay-bisect/rest.txt` holds the other 191.
  **THREE SITES STILL RESOLVE A CONSTRAINT OUTSIDE ITS SIBLINGS' SCOPE** and are
  reported, not fixed: `Checker.kt:111069` (fresh non-interned params, so it cannot
  corrupt the cache), `Checker.kt:137404` (**inside `typeParamInternCache.getOrPut`**,
  i.e. a first-touch freeze BY CONSTRUCTION — the hardest, since the factory runs
  before any scope exists), and `Checker.kt:139240`.
  **DO NOT** wire the recheck into `Project` before this closes; `Recheck.kt`'s
  banner says so and `ProjectRecheckTest` pins that nothing reaches it by default.

- [x] **(INC.18) THE PARTITION GATE WAS VACUOUS ON EVERY PROFILE THIS REPO HAS —
  THE FIXTURE THAT RE-ARMS IT LANDED 2026-08-23, AND IT IS PROVEN ABLE TO FAIL.**
  The receipt is a COUNT — how many DISTINCT passes net a diagnostic, off
  `PassTiming.diagNetByPass` — and the contrast is the finding:

  | project | files | diagnostics | files carrying a row | passes netting one |
  |---|---:|---:|---:|---:|
  | `build/bench/tsc-project-*` | 78 | 46 | **5** | **1** (`checkSpine`) |
  | `test-fixtures/partition-gate` | 71 | 175 | **70** | **78** |

  So 73 of 78 per-file comparisons on the arm that has always run are empty against
  empty, and all eight dashboard profiles are that same codebase.
  **`scripts/partition-gate.sh` runs BOTH arms** — realism unchanged, sensitivity
  added — and the sensitivity arm REFUSES below its floors (40 netting passes, 40
  files carrying a row) rather than printing green.
  **`scripts/partition-gate-ablate.sh` is the proof it can fail**, one injected
  mistake at a time, with a both-GREEN control (`checkCloduleTest2`, a pass netting
  on neither project) and a both-RED control (`checkSpine`) that make the other arms
  attributable. See the session note for the table.
  **WHY IT IS HAND-WRITTEN.** `PassDiagMineMain` mined all 6,451 conformance cases
  for per-pass attribution (2,802 netting, **241 distinct passes**) and
  `scripts/partition_fixture_compose.py` greedy-covers that record — but past ~24
  files each case adds **exactly one** new pass, i.e. the tail walkers are one-shape
  walkers, and this repo does not vendor TypeScript source. The miner says WHICH
  shapes to write; the files are written from scratch.
  **IT RETRO-PRICES LANDED WORK**: (INC.7)'s 68 gated walkers and (INC.9)'s deferral
  were profile-green for a reason that says nothing — only the corpus, which has no
  partition, stood behind them. Unmeasured on this axis, not wrong, and re-runnable.
  `docs/partition-gate-sensitivity.md`.

- [x] **(INC.11) THE 66 ms IS REFUSED 2026-08-23, AND ITS PREMISE IS MEASURED FALSE —
  PART OF THAT COST BUYS *RESOLUTIONS*, NOT A FIRST-TOUCH ORDER.** The item said the
  65 ms buys only a program-wide first-touch ORDER for interning and `aliasDisplayMap`.
  A three-phase re-measurable arm (`FltmDefer` / `XTSC_FLTM_EAGER`, default = shipped,
  pinned inert) says otherwise: fully deferred is **1,665 divergent capture spans in 47
  files with `narrowRendersMoreAny = 321`** — 321 resolutions LOST TO `any`, which is
  not a naming question and cannot be fixed by any display change. (Its numbers beat
  (INC.10)'s 2,722 / 46 by 1.6x, and `TYPEALIAS`-only is 137 / 10 against 462 / 18,
  because an ask-triggered whole-file build still builds every file's map in check
  order on a FULL build.) **Do not re-open this as a display problem.**
  **SUB-PROBLEM (b) IS CLASSIFIED AND THE ITEM'S HYPOTHESIS ABOUT IT IS REFUTED**: the
  residual rows are NOT two `Type` instances but **ONE instance carrying two competing
  names**. A `Extract<ClassLikeDeclaration, Pick<T, "kind">>` whose conditional cannot
  decide (free `T`) answers its own CHECK TYPE — the interned union — and the generic
  site then wrote `aliasDisplayMap[union.id] = ("Extract", args)` unconditionally.
  **That was a SHIPPED, whole-program hover defect** (an unbound `T` in a tooltip) and
  is FIXED — an instantiation that returns one of its own arguments unchanged no longer
  registers a name for it. `AliasDisplayIdentityTest` pins it and needs `@useRealLibs`
  to reach the mechanism at all.
  **WHAT REMAINS, AND IT IS A CHANGE OF KEY, NOT OF POLICY**: the (a) half — 302 spans
  in `checker.ts` alone under full deferral — is two SYNONYMOUS non-generic aliases
  resolving to one interned type, decided first-wins. **tsc picks by the REFERENCE's
  declaration site, which an id-keyed global map cannot express**, so closing it means
  re-keying alias display, against round 754's deliberate `Type.Reference` exclusion and
  a union display order pinned byte-for-byte across ~13k baselines. That is a
  logical-parity conversation (`docs/logical-parity.md` § 2) and is NOT worth opening
  for a 66 ms the table above has already refused.
- [x] **(INC.7) DONE 2026-08-23 — 157 WALKERS GATED ACROSS FOUR BATCHES, AND BATCH 4
  CLOSED THE TECHNIQUE RATHER THAN THE FAMILY.** Batches 1-3 gated 68; batch 4 gated
  **89** more in two independently swept sub-batches. **Floor 1,207 -> 340 ms,
  narrowed query median 1,077 -> 367 ms, ratio at the median file 13.30x.** The batch-4
  diff is 89 loop headers and nothing else (`binderResults` 221 -> 132, `checkedResults`
  255 -> 344). The relocation discount now has FOUR points — 79.0 / 85.5 / 92.9 /
  **78.2%** (54.23 ms of rows for 42.41 ms of floor).
  **WHY IT IS DONE: 65% OF WHAT REMAINS IS REFUSED BY SHAPE.** 172 ungated passes /
  251.9 ms remain, and the top TEN rows are **165 ms** of it, every one refused —
  `init:buildFileLocalTypeMaps` 62.06 (writes `deepInstantiationBailed`),
  `checkTypeArgumentConstraints` 21.69, `checkBaseClassImprovedMismatch` 19.51
  (`diagnostics[i] =`), `checkInterfaceMultiBaseConflicts` 12.73,
  `checkSubsequentVarTypesPerFile` 10.70, `checkPropertyOverride` 9.61,
  `checkDerivedConstructorSuper` 9.04, `init:computeAllEnumValues` 8.75,
  `checkCircularClassBaseViaDefaultTypeArg` 6.91, `checkClassImplementsInterface` 5.94.
  Analyzer-CLEAN was only 54 ms in total. Of the 83 refused: **53** write a checker
  field or retract inside the private closure, 4 carry more than one `binderResults`
  reference, 4 hold a cross-file pre-loop accumulator, and **43 retract via
  `diagnostics.removeAll`**. **A successor must change the SHAPE of a retracting or
  field-writing pass — the loop header is exhausted.** See (INC.20).
  **TWO ANALYZER INVARIANTS WORTH MORE THAN THE BATCH** (both now in CLAUDE.md): a
  MULTI-LINE PARAMETER LIST truncates a function's span to its header, hiding the body
  and every field write in it — it wrongly cleared two passes THIS QUEUE HAD ALREADY
  REFUSED, so the refusal list is the oracle that catches the analyzer; and a
  `pass("…")`-REGISTERING helper is not a caller, so without excluding the 12
  `initCheckPasses*` registrars the clean set is **0**.

- [x] **(INC.20) LANDED 2026-08-23 — 13 PASSES, AND THE FLOOR PASS TABLE NEARLY HALVES:
  `PT.total both.floor` 219.98 -> 119.74 ms.** (INC.7) batch 4 refused 53 passes on
  "writes a checker field inside the private closure"; **the verdict was true and the
  inference from it was wrong** — for nine of them the write is a per-FILE AMBIENT
  install (`currentFileLocals` / `currentCheckFileName`), gone before the next file is
  walked, with the same resting value whether the loop ran 78 times or none. Sub-batch B
  used the (INC.17) template properly: two MIXED passes that build a program-wide INDEX
  then emit per file (**only the second loop moved**) and two per-file retractors — one
  of which, `checkPreEmitCountMismatchPins`, is IMPROVED rather than narrowed, since its
  TS-1 marker carries `fileName = null` and so survived the partition filter.
  **Banked 100.23 ms of 116.08 = 86.3%, the fifth discount point.** Floor 248 -> 162 ms,
  narrowed-query median 313 -> 207, ratio **15.66x -> 24.16x**. 19 pins; reverting the
  14 loop headers reddens 5 of 7 census assertions, and gating the two COLLECTION loops
  reddens exactly the three cross-file arms — the evidence the split is load-bearing.
  **THE VICTIM HAS A MECHANISM NOW, NOT A RESIDUE**: `checkReverseMappedIntersection-
  Constraint` 0.067 -> 19.431 ms, the only row outside the batch to move >0.2 ms, because
  round 895's `srcHas` builds its per-file n-gram filter LAZILY and the FIRST caller in
  pass order pays it for all 78 files. See (INC.21).

- [x] **(INC.21) LANDED 2026-08-23/24 — THE SCANNING FAMILY BANKS 99.9%, THE ARC'S FIRST
  ~100% DISCOUNT.** 19 whole-source-scanning passes gated TOGETHER (**19.064 -> 0.024
  ms**), four stragglers, and (INC.20)'s escalated reversal. `PT.total both.floor`
  **123.95 -> 97.12 ms**; floor **162 -> 137**; narrowed-query median **207 -> 166**;
  ratio **24.16x -> 29.86x**. **No row outside the batch rose** — the lazily-built
  n-gram filter had nowhere left to relocate to, and the three whole-program text gates
  that remain use a RAW `String.contains`, never round 895's filtered `srcHas`, so they
  cannot rebuild it. The list was derived by TWO independent instruments that agree.
  **THE STRAGGLERS TAUGHT THE OPPOSITE LESSON**: three keep their cost because a
  whole-program `.contains` gate sits ABOVE the loop — a question about the PROGRAM, so
  it must stay on `binderResults`, and gating the loop banks ~0.02 ms
  (`checkModulePreserve4Pin` is the control: narrowed and unmoved, 1.639 -> 1.699). What
  banks the ms is a **NAME PRE-GATE**, sound because it asks only what the pass can
  already do: 2.509 -> 0.002 and 2.064 -> 0.002.
  **THE REVERSAL'S OBLIGATION WAS DISCHARGED**: `checkSubsequentVarTypesPerFile`
  **11.740 -> 0.004 ms**, and the replay measured on both arms — 284 -> **304 of 417**
  re-entered passes for **+26 ms over 75 questions (+0.2%)**, divergence unchanged at
  5 of 75. **The replay's ADVANTAGE fell 1.91x -> 1.68x because the fresh build got
  cheaper** — every round that shrinks the floor shrinks the replay's reason to exist,
  which strengthens (INC.19)'s refusal of it as a default path.
  **REFUSED**: `checkModuleAugmentationReexportDuplicates` /
  `checkCjsExportAugmentationConflict` (their emitter adds a row on the augmentation's
  TARGET, so a partition holding only the target loses it — rows 0.15 and 0.00 ms, the
  refusal is free); a name pre-gate for `checkModulePreserve4Pin`; and routing the three
  raw `.contains` gates through `srcHas`, which would **COST ~17.8 ms to build 78 filters
  to save three ~2 ms scans** now that no pass builds one.

- [x] **(INC.22) REFUSED 2026-08-24, WITH THE SHARPEST MEASUREMENT THE ARC HAS OF THIS
  ROW — AND THE REFUSAL RE-AIMS THE DIRECTION.** `init:buildFileLocalTypeMaps` is
  **69.16 ms of a 90.15 ms floor pass table (77%)**, and partition-scoping it would take
  the floor **131 -> 57 ms**, the narrowed-query median **166 -> 116**, and the ratio
  **29.86x -> 42.61x**. The axis is new — (INC.10)/(INC.11) deferred PHASES, this varies
  **WHICH FILES** through the INV.6(6d) partition view, so a full build is unchanged BY
  CONSTRUCTION — **and the claim was verified in the BINARY**: a per-arm DIGEST over
  381,666 captured types and 360,152 definitions is IDENTICAL across arms, with
  `FltmDefer.lazyBuilds == 0` on every unpartitioned build as the corroborating count.
  **THE QUEUE'S PREMISE HAD EXPIRED**: (INC.11)'s "137 divergent spans" for the
  `TypeAlias`-only arm re-measures as **5 / 3 of 76** — byte-identical to baseline —
  closed by (INC.11)'s own fix and the (INC.5)/(INC.16)/(INC.19)-(21) work. So no
  `aliasDisplayMap` re-key was needed, and none was attempted.
  **WHAT REFUSES IT IS THE MEMBER CHANNEL, NOT DISPLAY**: `capture-channel`'s `moreAny`
  goes **168 -> 229**, i.e. **+61 member types collapsing to `any`** under a narrowed
  build — a WRONG ANSWER, the same class (INC.11) refused the full deferral over — and
  `partition-gate`'s SENSITIVITY arm diverges on a DIAGNOSTIC. Keeping the cheap
  `TypeAlias` phase program-wide (6.68 ms) solves the NAMING half completely (2,275
  divergent spans -> +1 row) and does nothing for the member half.
  **THE TRANSFERABLE RESULT**: the obstruction is not the pass's COST but that the pass
  IS the program's FIRST-TOUCH ORDER, and that order buys BOTH an alias name (cheap,
  fixable) AND member resolutions (not fixable without the expensive phase). See (INC.23).

- [x] **(INC.23) THE CENSUS IS DONE 2026-08-24, AND IT SHRANK (INC.22)'s REFUSAL BY TWO
  ORDERS OF MAGNITUDE.** "+61 member types collapse to `any`" is, classified per ELEMENT,
  **78 rows carrying exactly ONE member name — `[Symbol.unscopables]`** (the lib's
  `{ [K in keyof any[]]?: boolean }`) in 14 files. Everything else (1,379 rows, 196 names)
  is the (INC.11)(a) alias-display family, which collapses to **+1 row for 6.68 ms**.
  **ROUND 778's WRITE GATE IS REFUTED AS THE MECHANISM**: the writer hook reads
  `ambient=empty persisted=true` in BOTH arms and differs only in `truncated` — under a
  partition the first ask arrives from INSIDE the member-table resolution the mapped
  type's `keyof` needs, `resolveStructuredTypeMembersCore` returns leaving `properties`
  null, and the type degrades. **The whole narrowed compile has ONE truncated resolution
  of 822; a full build has 0 of 21,315.**
  **THE OBVIOUS FIX IS REFUTED WITH A POSITIVE CONTROL**: refusing to persist a truncated
  resolution changes nothing sweep-wide (same 78 rows, byte-identical digest) while the
  control shows the arm is live (`persisted=true resolves=1` -> `persisted=false
  resolves=2`) — the re-resolution re-enters the same guard.
  **AND `narrowRendersMoreAny` IS A SUBSTRING HEURISTIC THAT OVER-REPORTS**: **zero** of
  the shipped baseline's 168 "moreAny" rows loses a member type. A nonzero value is a
  LEAD; a zero still means what it always did.
  **(INC.22)'s THIRD OBSTRUCTION IS RETIRED**: the PURE partition-scoped arm is EQUIVALENT
  on both `partition-gate` arms — the "DIVERGED 1 file" belonged to its MIXED
  `TypeAlias`-program-wide configuration.

- [x] **(INC.24) LANDED 2026-08-24 — both capture runners fold their whole answer set into
  ONE number per arm, ordered by span key so it is a property of the ANSWERS and not of
  `HashMap` iteration.** From a clean tree it reproduces (INC.22)'s recorded
  `full=-3718897727265589316` over 381,666 types + 360,152 definitions exactly — round
  776's rebuild-the-baseline control, satisfied on an instrument. `Checker.fileLocal-
  TypeMapSnapshot` came with it, plus 4 pins.

- [x] **(INC.25) LANDED 2026-08-24 — AND IT WAS NEVER A PARTITION DEFECT. Floor 129 -> 58
  ms, narrowed-query median 173 -> 117, ratio 30.91x -> 43.07x, floor now HALF a median
  query instead of three quarters.** `resolveStructuredTypeMembersCore` returns silently
  on re-entry leaving `properties` null — correct for circular heritage, TRUNCATED for
  anything reading the key set — so `getKeyofType` read null as `string`, the mapped type
  bailed to `any`, and round 778's gate froze it. The fix answers such a `keyof` **from
  the DECLARATIONS**: no resolver call at all, only already-computed tables plus AST,
  under a visited set and a depth cap, REFUSING rather than returning a partial key
  domain (round 463). Terminating by construction; **no TS2589 at (0,0) anywhere**.
  **IT REPRODUCES ON A FULL BUILD WITH NO PARTITION**: three lines
  (`export const strArr: string[] = []` + a `number[]` sibling) render
  `[Symbol.unscopables]: any`, because `interface Array<T>`'s body is never spine-walked
  while a hand-written interface's is. **So this was shipped and always-present**, and
  the 78-file profiles hid it because `init:buildFileLocalTypeMaps` happened to resolve
  that member first — which is why three rounds read it as a partition problem.
  With it fixed, `narrowRendersMoreAny` returns **229 -> 168** (baseline) and the
  partition-scoped pass is now the shipped default, pinned with no mode install.
  **Ablation: counters identical DIGIT FOR DIGIT to the fixed binary** — the fix moves
  zero counters, so all standing drift is pre-existing.

- [x] **(INC.26) LANDED 2026-08-24 — AND THE ROUTE WAS NEITHER A NOR B, BECAUSE THE GATE
  ASSUMED THE FULL BUILD WAS THE REFERENCE AND IT WAS WRONG.** The census inverted the
  entry: the `Intl.LocalesArgument` case it led with is **2 rows of 2,275**, and the
  dominant direction is the reverse — **the FULL build attaches a name, the NARROW one
  renders the honest type**. The mechanism is aliases whose body is a single NAMED
  interface (`type FunctionBody = Block`, `type IsInterface = InterfaceDeclaration`,
  `type HasIllegalExpressionInitializer = PropertySignature` in tsc's own `types.ts`):
  we stamped the alias onto that interface's `Type.id`, and `typeToString` reads
  `aliasDisplayMap` BEFORE the structural fallback, so every occurrence program-wide
  rendered under the alias. **Four lines reproduce it with no partition, in the
  DIAGNOSTICS channel** (`Type 'FunctionBody'` where tsc 7.0.2 says `Type 'Block'`).
  **So both routes were treating a symptom** — Route A would have made narrowed hovers
  as wrong as full ones. The fix is the `symbol == null` test the sibling Intersection
  arm already applied; anonymous bodies still register.
  **ROUND 754 BIT AND WAS HANDLED CORRECTLY**: the first version reddened four `Table`
  rows, and **no logical-parity divergence was taken** — that baseline is pristine tsc's,
  so switching it off would move AWAY from tsc. The rule was narrowed to exclude a
  GENERIC named type instead, and arm (b) pins it: removing that exclusion reddens
  **exactly 2 of 504 tests, the new pin AND the corpus baseline, together.**
  **Gate: 2,275 -> 1,128 spans (-50%), 46 -> 43 files**, `narrowRendersMoreAny=0`.
  **TWO RECORDED DIGESTS MOVED BY DESIGN** — `capture-equivalence` full
  `-3718897727265589316` -> **`3349895618940861366`**, `capture-channel` full
  `4065921979171190360` -> **`-3278907782584108296`**. First time in the arc; a full
  build is what this corrects.

- [x] **(INC.27) REFUSED 2026-08-24 WITH A PROOF — B416's KEY CANNOT NAME A UNION THE WAY
  tsc DOES, AND THE OBVIOUS NARROWING MAKES THE GATE *WORSE*.** Census of the 1,128
  residual spans: **432** where several aliases claim one member set (arbitrary in BOTH
  arms), **~393** where a SOLITARY alias names a union at sites that never spell it
  (measured: `AssignmentPattern` has **0 references** in binder.ts, `MemberName` 0 in
  checker.ts), **~303** the (INC.28) family.
  **tsc gives THREE answers for one member set** (`ModuleName`, `ModuleExportName`,
  `Ident | Str`) because it keys its union cache by `getTypeListId + getAliasId`; **and
  its naming turns out to be IDENTITY PRESERVATION (`filterType`), not structural
  matching** — a join-built `A | B` renders structurally while a no-op narrow of
  `x: MyType` renders `MyType`, both in one pristine baseline.
  **INV.5(a) (round 545) interns our unions by member-id list ALONE**, so all of tsc's
  instances are ONE `Type` here — a proof that no id- or member-set-keyed table can give
  three answers from one key, and that **anything able to name the reconstructed union
  also names a union nobody named.**
  **THE NARROWING WAS BUILT AND MEASURED**: it collapses `full=name/narrow=name` **416 ->
  2** and takes the gate **1,128 -> 1,351 spans, 43 -> 46 files**, because the poison
  TRIGGER is itself coverage-dependent and a new `full=structural/narrow=name` bucket of
  657 appears. Nor can ambiguity be decided syntactically: of 407 collisions per compile
  the largest are aliases whose body is ANOTHER alias (`type FunctionLike =
  SignatureDeclaration`), so deciding it means resolving every union alias up front —
  (INC.22)'s eager `TypeAlias` phase, already refused twice.
  **Landed behaviour-free and PROVEN so**: KDoc, census hooks outside the write, 2 pins,
  and `capture-equivalence` returning BIT-IDENTICAL digests.

- [ ] **(INC.29) PUT THE ALIAS IN A UNION'S IDENTITY — the only route to tsc's union
  display, and it is an INV.5(a) change, not a display one.** (INC.27) proved the bound:
  tsc keys its union cache by `getTypeListId(types) + getAliasId(aliasSymbol, …)` and so
  holds distinct instances for one member set, while round 545's INV.5(a) interns ours by
  **member-id list alone**. **Until that changes, no naming rule can be correct** — every
  mechanism that can name a flow-reconstructed union also names one nobody wrote.
  **AND THE TARGET BEHAVIOUR IS NOT "MATCH THE ANNOTATION" BUT IDENTITY PRESERVATION**:
  tsc renders `MyType` for a narrow that removes nothing and the structural union for a
  join-built one, which is `filterType` returning its input unchanged — so the rule is
  "an operation that did not change the type does not change its name".
  **THE COST IS THE HAZARD.** Union interning is load-bearing for relation caching and for
  union display ORDER, which is pinned byte-for-byte across ~13k baselines; splitting the
  key mints more `Type` ids, and id drift reshuffles ~350 boundary tests (round 881's
  warning about moving id allocation). Price the id churn BEFORE building anything.
  **Do NOT re-open**: naming from the annotation ((INC.27), unstable and coverage-
  dependent), the eager `TypeAlias` phase ((INC.22), 6.68 ms and a diverging diagnostic),
  or closing the gate by making the NARROW arm match the full one ((INC.26): the narrow
  arm is the more correct one in every remaining family).
- [x] **(INC.28) LANDED 2026-08-24 — A GENERIC ALIAS'S OWN PARAMETERS WERE NOT IN SCOPE
  FOR ITS BODY, SO `type Box<T> = { v: T }` RENDERED `{ v: any; }` ON ORDINARY BUILDS.**
  `getDeclaredTypeOfSymbolWorker`'s type-alias arm resolved `decl.type` with NO
  type-parameter scope, the alias's own `T` answered `errorType`, and **`any` ABSORBS A
  UNION**, so a union body collapsed entirely. **Four lines reproduce it with no partition
  and it is not order-dependent**; the partition divergence was a CONSEQUENCE (a narrowed
  build skips `init:buildFileLocalTypeMaps`, so the first toucher is
  `withDeclTypeParamScope`, which DOES install the scope — and `declaredTypes` has no write
  gate, so first touch freezes). A writer hook printing `ambient=empty depth=sym1/node0`
  **refuted BOTH standing suspects** — round 778's write gate and truncation.
  **THE FIX IS A SPLIT FORCED BY MEASUREMENT**: `getTypeOfSymbolWorker`'s alias arm answers
  the parametric form; **`getDeclaredTypeOfSymbol` (what a REFERENCE resolves to) is
  deliberately untouched**, because handing references the parametric form costs two corpus
  false positives, both measured and reverted.
  **Gate 1,128 -> 1,003 spans with ZERO NEW divergent spans** (a strict subset, 125 fixed);
  suite **15,811 / 0 / 3**; ablation 2 of 4 pins RED, **with the two-arms-agree test staying
  GREEN because both arms agreed on the WRONG answer** — the reason a comparison is not a
  pin. Digests moved by design (second time in the arc): full `3349895618940861366` ->
  `8385940838610938556`, narrow `306524840298287433` -> `-7423700524621287041`.
  **173 of the 298 rows REMAIN and need the RELATION, not the display** — see (INC.30).

- [ ] **(INC.30) THE RELATION HAS NO "TYPE PARAMETER VIA ITS CONSTRAINT" RULE, AND THAT
  REFUSAL IS LOAD-BEARING AS A RECURSION BRAKE.** (INC.28) measured it: judging a
  `Type.TypeParam` alias argument by its APPARENT type in the B57.1b guard renders
  `Visitor` exactly as tsc 7.0.2 does and closes **173 of its 298 rows** — and costs a
  corpus false positive, because `checkTypeRelatedToCore` has no general rule relating a
  TypeParam source through its constraint (its `NonPrimitive` leg refuses it DELIBERATELY),
  and **that refusal is what brakes the recursion for
  `BuildTree<T, N extends number = -1, I extends any[] = []>`**. Recorded at the site.
  **So this is a RELATION-ENGINE item, not a display one**, and it belongs with the M3
  engine work rather than the (INC.*) arc: adding the rule needs a termination argument
  that does not rely on the absence of the rule. CLAUDE.md already records the two lenience
  directions a bare `Type.TypeParam` has in this relation (a union SOURCE relates to a bare
  TypeParam TARGET; a bare TypeParam SOURCE relates to most object targets) and that they
  CANCEL for one candidate and COMPOUND for a union — read that before touching it.
  **Do not attempt it as a rendering fix**: (INC.28) established the rows are a relation
  verdict, and (INC.26)/(INC.27) established that `typeToString` is shared with the
  diagnostics and pinned byte-for-byte across ~13k baselines.
  **AMENDED 2026-08-24 — (INC.42) REACHED THIS BOUNDARY FROM A SECOND DIRECTION AND DID NOT
  WEAKEN THE TERMINATION ARGUMENT.** It judges a bare `Type.TypeParam` argument LOCALLY against
  its own already-resolved constraint, inside B57.1b's guard and nowhere else, so **no new rule
  enters `checkTypeRelatedToCore`** and this item's obligation is untouched. Two things it
  measured that a future attempt inherits: the relaxation is confined to the DISPLAY path
  because on the CHECKING path it reads `output.errors` **46 -> 48** on the compiler profile,
  and this guard's role as a recursion brake lives in the **ENCLOSING** declaration rather than
  the referenced one (a flip census of `excessPropertyCheckIntersectionWithRecursiveType` says
  so — the only four decisions it flips there are two NON-recursive aliases referenced from
  inside the self-referential `BuildTree`). See (INC.43) for what the real rule would buy.

- [x] **(INC.31) DONE 2026-08-24 (`2fa8a39f`) — THE DOCUMENTED LANGUAGE-SERVICE COST TABLE
  WAS 10-24x STALE, AND THE ROWS THAT DID NOT MOVE ARE THE LOAD-BEARING ONES.** Every wall
  figure in `docs/language-service.md` §3/§10a/§10b/§10c/§14 was round-930, i.e. before
  (INC.2b) narrowed the capture path and before the floor fell 1,092 -> 58 ms. Re-taken on
  the compiler profile (78 files, 9,977,097 chars), warm, six warm-ups, two independent
  JVMs, every row reproduced: `diagnosticsOf(f)` median **1.1-1.2 s -> 108-113 ms**
  (~10x, p90 202-219), `completionsAt` **~4.7-5.1 s -> 194-202 ms** (~24x),
  `signatureHelpAt` **190-214 ms** (~23x), `documentHighlightsAt(binder.ts)` cold **~15x**,
  first hover on `binder.ts` 610 -> 290-306 ms. **Narrowed:full at the median file =
  43-47x** (108-113 ms against a 4,864-5,096 ms rebuild). **`referencesAt` (8.8-9.6 /
  13.2-13.9 s), `renameAt` (20.0-21.3 / 25.0-26.0 s) and a plain `diagnostics()`
  (4,864-5,096 ms) did NOT move and CANNOT** — their claim is about every file, so
  they never enter `captureIn`'s partition; that column is now marked on the page.
  **A REAL KEYSTROKE COSTS THE NARROWED PATH NOTHING EXTRA** (identical bytes 212 ms,
  appended comment 247, inserted statement 218, a statement introducing a TS2322 215).
  **Corrects the heap claim**: not "~1.9 GB peak, 512 MB not enough" but 1,077-1,125 MB
  peak in G1 old gen with **264 MB RETAINED** after a full GC — green at `-Xmx2g`, OOM at
  `-Xmx1g`. Instruments: `Inc31CostMain`, `Inc31ResidueMain`, `scripts/inc31-ls-cost.sh`
  (refuses on a missing profile, positive control on the rows). Every number on the page is
  now dated, stamped with its commit, and marked WALL TIME AND THEREFORE PINNED BY NO TEST.

- [x] **(INC.32) DONE 2026-08-24 (`689df5bb`) — THE CAPTURE MEMO EVICTED BY ENTRY COUNT, SO
  A ONE-SPAN REQUEST THREW OUT A 125,289-SPAN ONE.** `Project.captures` was an
  access-ordered LRU bounded at `CAPTURE_MEMO_ENTRIES = 2` by COUNT. Hover / definition /
  highlights / `fileSemantics` ask ONE file-wide question per buffer via `captureAround`;
  `completionsAt`'s two branches and `signatureHelpAt` call `captureIn` directly with ONE
  span (`Project.kt:1048/1094/1215`). So **hover -> completion -> signature help -> hover
  with NO edit in it** rebuilt the hover: `quickInfo.mid.afterTwoOtherChannels`
  **324 ms -> 4 ms**, every other row inside the band and the `rebuild.full` anchor at
  +1.7%. **NOT a larger limit** — the bound is now on WEIGHT, in two lanes that cannot evict
  each other: `CAPTURE_MEMO_CARET_SPANS = 4` decides caret-scoped, bounded at
  `CAPTURE_MEMO_CARET_ENTRIES = 4`; buffer-sized stays at `CAPTURE_MEMO_BUFFERS = 2`,
  unchanged since (INC.13). **Worst case: 2 buffer captures (UNCHANGED) + 16 answers =
  0.013% of ONE file-wide capture.** Invalidation re-audited, not assumed (`cached = null`
  at exactly three sites, all clearing `captures`). Ablation a1 (count eviction restored)
  3 of 13 RED; **a2 was needed because a stricter bound cannot fail a BOUND pin** — see the
  session note. Suite 15,811 -> **15,815 / 0 / 3**.

- [x] **(INC.33) REFUSED 2026-08-24 (`cf56bfe8`) — THE WIDENING IS PRICED AND IT LOSES: A
  CAPTURE REQUEST IS PRICED PER *ANCHOR* WHERE AN EDITOR NEEDS A PRICE PER *ANSWER*.** A widened
  file-wide hover costs **+286 ms on `binder.ts`** (300 -> 586, ranges disjoint in both batches)
  and **+25.1 s on `checker.ts`** (3,624 -> 28,751) to save a completion build of **204 / 2,078
  ms** — break-even **1.40** and **12.1 completions per hover IN A BUFFER WITH NO EDIT SINCE**,
  and the dominant completion path types a `.` first, which is an edit, which clears the memo.
  The cheapest shippable variant (occurrences + members, no scopes) is +96 ms on `binder.ts` for
  0.47 but **+3,326 ms on `checker.ts` for 1.60**, and makes EVERY hover ~32% dearer. **The
  second, independent refusal is RETENTION**: one widened entry holds 798,531 records for
  `binder.ts` and **54.4 M** for `checker.ts` — **48x / 205x** today's hover entry — of which
  49,879,917 are `CapturedName`s, because a free-name caret sees the lib globals and a widened
  request repeats that set at every one of 13,601 anchors (**O(anchors x globals)**, structural,
  and `CapturedScope`'s own KDoc already said so). **THE UNBLOCKER IS (INC.41), NOT A WIDER
  REQUEST** — a re-entrant capture against a retained checker ((INC.17)'s `ProgramRecheck`)
  answers a span nobody asked for up front with no new build. Instrument kept and re-takeable:
  `scripts/inc33-widen-cost.sh` + `Inc33WidenMain`'s KDoc (which is the authority for the table;
  the figures are WALL TIME on one box and pinned by no test). ORIGINAL ENTRY, whose reasoning
  stands and whose sub-question (a) is what was measured:
  **THE CARET CHANNELS ARE COLD PER CHANNEL PER BUFFER — a completion in an
  already-hovered buffer still BUILDS, measured 201-228 ms, and the prize is UNMEASURED.**
  (INC.32) stopped the caret channels evicting the hover; it did not make them SERVED. A
  hover's file-wide request carries `spans`, and a member completion asks `memberSpans`, so
  the memo hit cannot answer it and `captureIn` rebuilds. **That is CORRECT as it stands**
  — (INC.14): an answer that was never asked for is ABSENT, and an absent member answer
  renders nothing, silently — so this is not a bug to fix but a WIDENING to price. **The
  widening is the dear half, and it undoes a deliberate decision**: (API.4a) made
  `memberSpans` a SECOND span list precisely **so `fileSemantics` never enumerates
  members** — a file-wide `memberSpans` would ask about every member position in the
  buffer, and nothing here has measured what that costs the hover that would pay for it.
  **Two sub-questions, in order**: (a) what does adding `memberSpans` to the file-wide
  request cost the hover that pays for it (instrument: `scripts/inc31-ls-cost.sh`, rows
  `quickInfo.mid.first` and `completions.mid.afterHover`); (b) whether the three direct `captureIn` sites
  (`Project.kt:1048/1094/1215`) should consult `preparedAnswerFor` at all — today they
  cannot reach a prepared check, measured at 207 ms right after `prepare(6)`, 202 ms right
  after a hover in the same buffer and 194 ms cold, i.e. **the same build three ways**.
  Do NOT expect a win from (b) alone: without (a) there is nothing in the prepared answer
  for a completion to read.

- [ ] **(INC.34) `SourceIndex` DERIVED-POPULATION MEMOIZATION — MEASURED AND REFUSED
  2026-08-24; THIS ENTRY IS THE REFUSAL, NOT A TASK.** On a memo hit `captureAround` still
  re-derives the file's occurrence set — `occurrenceNodes()` (two tree walks, two sorts,
  memoized nowhere), a span per occurrence, a `HashSet` of every span. Decomposed by buffer
  size: **1.21 ms at 17.9 KB, 2.27 ms at 194 KB, 82.7 ms at 3.15 MB**, closing the
  arithmetic to **0.4%** of the measured 83 ms second-caret hover on `checker.ts`. **At the
  median file the whole prize is 1-2 ms — below this repo's floor for a round** — and it is
  not a `referencesAt` lever either (**~140 ms of a 9.3 s sweep = 1.5%**). It survives
  ONLY as a tail fix for buffers over ~1 MB (~78 ms per caret there). **The instrument that
  can re-open it is `Inc31ResidueMain`** (`-project` jvmTest, walk-vs-sort split included) —
  a refusal is only as durable as the instrument that can overturn it, so re-open this with
  a measurement from that runner and never from a leaf profile row (CLAUDE.md: a JFR owner
  total is a LOCATION, not a price).

- [x] **(INC.35) DECIDED AND CLOSED BY THE OWNER 2026-08-25 — OPTION (b), PER-BUFFER ONLY.
  NOT IMPLEMENTED: THE DECISION *IS* THE OUTCOME.** Project-wide `diagnostics()` stays
  WHOLE-PROGRAM at 4,864-5,096 ms per edit, and the editor's error reporting stays
  PER-BUFFER — which is what (INC.1)/(INC.2b) already deliver at **108-113 ms** and what an
  IntelliJ-style annotator actually renders. **Closure-based project diagnostics is REFUSED
  for this corpus**, on round 772's measurement rather than on taste: tsc's own sources are
  `export *` barrels, so touching a LEAF (`semver.ts`, 3 direct dependents) reports
  `incremental recheck of 77/78 file(s)` and costs a full warm rebuild, while `checker.ts`
  and `types.ts` do not qualify as incremental at all. **Option (a)'s reasoning is kept
  visible so no future round re-derives it**: a closure WOULD buy a well-layered application
  a great deal and buys the v1 benchmark nothing, so the two optimisation targets genuinely
  diverge here — which is exactly why the choice was the owner's and not the agent's.
  **RE-OPENABLE ONLY on an owner directive naming a LAYERED corpus to grade it on** (one of
  the (LIB.*) screened libraries); re-opening it against the dashboard profile is a round
  spent optimising for a benchmark that structurally cannot show the win.

- [x] **(INC.36) DONE 2026-08-25 — THE PROGRAM WAS PARSED *TWICE* AND BOTH COPIES WERE
  KEPT; RETENTION **264 -> 177 MB (-33%)**.** Step 1 ATTRIBUTED the 264 MB with a ten-step
  subtraction ladder over `liveAfterGc` (four processes agreeing to 0.6 MB):
  `Project.sourceIndexes` **114.7 MB (43.5%)**, the process-global `CrawlParseCache`
  **103.0 (39.0%)**, `RealLibSnapshots` 2.6, JVM baseline + lib text + the 9,827 answers
  43.7 — and **`cached`/`captures`/`prepared`/`narrowed`/`recheck`/`lineMaps` 0.0 MB
  COMBINED**, so every memo (INC.12)/(INC.14)/(INC.32)/(INC.40) added is free and
  `close()` frees nothing. The two big rows are ONE program parsed twice at the same
  content under the same `computeParserFlags`; the class histogram says it independently
  (**770,460 `Identifier`s** against 856,962 nodes in one copy = CLAUDE.md's 44.5%,
  DOUBLED). Step 2 deleted one copy: `Project.sourceIndexOf` indexes tokens around the
  compiler's own tree (`parsedSourceOrNull` -> `SourceIndex.around`), `sourceIndexes`
  falls **114.7 -> 27.5 MB**, `Identifier` HALVES to 388,790 and `referencesAt` returns
  the **same 9,827 hits**. **The residue is named, not hidden**: ~18 MB is `SourceIndex`'s
  own token arrays (`[I` + `[LSyntaxKind;`, byte-identical before and after — nothing else
  in the process holds one) and ~10 MB is a SECOND COPY OF THE SOURCE TEXT, which
  `SourceFile.text` makes nearly free to remove and which is left as a named next lever
  rather than landed after the gates ran. **REFUSED: option (b), bounding `sourceIndexes`
  by weight** — it costs re-parses (144-171 ms for `checker.ts`) to keep a duplicate that
  can simply not exist. **REFUSED: threading the parses through `ProjectCompiler.Result`**
  — `cached` is nulled on every edit and the hover path goes through `captureIn`, not
  `build()`, so the editor's own loop would keep duplicating the file being edited; it
  also lands trees in the `Result`s `captures` retains and, under `CrawlParseCache`'s OFF
  arm, would newly retain the whole program where the accessor degrades to today.
  `docs/perf/language-service-retention.md`; per-project marginal `103 + 115·N` measured
  BEFORE the fix and not re-drawn after it.

- [x] **(INC.37) DONE 2026-08-24 (`c1c165c6`) — THE OTHER HALF OF A QUERY IS DECOMPOSED, AND
  ITS TWO HEADLINE ANSWERS ARE BOTH NEGATIVE RESULTS.** `own(F) = build(recheckOnly={F}) −
  build(recheckOnly={a name not in the program})`, per wall and per pass, 78 files.
  **(1) `own(F)` IS LINEAR IN NODES AND `checker.ts` IS AT THE p10 OF PER-NODE COST** —
  6.27 µs/node against a population median of 9.71 over the 51 files >2,000 nodes; its
  1,726 ms is 275,478 nodes at a below-median price, so **there is no super-linearity and no
  structural lever inside the big file, only the constant factor per node.** Bytes is a
  **10x-noisy** proxy (76-739 µs/KB) that predicted `checker.ts` low by 1.2-4.3x — census
  per NODE. **(2) Σ`own(F)` = 6,841 ms against a whole-program check of 4,935 — a 1.39x
  RE-DERIVATION TAX**, and the walk partitions EXACTLY (Σ `spineNodes` = 856,962, the
  whole-program figure to the node), so the 1,906 ms is shared type resolution each query
  re-derives; see (INC.38). Query shape: floor 56 ms, `own(F)` median **52**, query median
  **108 ms** (reproducing (INC.31) independently), max 1,782 with the floor at **3.1%**.
  `checkSpine` is **89-92%** of `own(F)`; the ~400 tail walkers are 10.5% on `checker.ts`
  over 78 rows whose largest is 0.65% of the query (**closed** on round 830's arithmetic);
  the four disjoint type-system rows are **16.2%** of `checkSpine`, so 84% is the walk and
  the handler bodies. **Round 847's six-handler SET confirmed (65.7% vs 63.0%), its ORDER
  REFUTED** — see (INC.39). `docs/perf/file-check-decomposition.md`; instrument-only, suite
  unchanged at 15,815 / 0 / 3.

- [x] **(INC.38) DONE 2026-08-25 (doc-only) — THE 1.39x RE-DERIVATION TAX'S HOST-FACING
  RECOMMENDATION IS NOW WRITTEN DOWN, WITH ITS NUMBERS AND ITS LIMIT.** (INC.37): Σ`own(F)`
  over 78 files is 6,841 ms against a 4,935 ms whole-program check while the spine walk
  partitions to the node, so **1,906 ms is shared type resolution a full build amortises and
  every per-file query re-derives in its own fresh `Checker`**. Against a 108 ms median
  query that is ~24 ms = 22%; against `checker.ts` it is 0, because there the file IS the
  program. **THE CODE HALF SHIPPED ALREADY — (INC.40), `8d4e95b0`.** It asked whether a
  HELD plain-build `Checker` can serve `diagnosticsOf` across queries at one program state,
  the way `prepare` serves captures. It can, and it does: `Project.diagnosticsOf` now keeps
  the program its first narrowed build hands back and re-enters it, worth **2.25-2.30x**
  (104-108 ms -> 25 ms at `k = 1`), which is this tax being COLLECTED rather than re-paid,
  and it does not remove the recommendation below — it deletes the FLOOR across queries,
  not the per-file derivation a single build still pays once per file named.
  **THIS ROUND LANDS ONLY THE DOCUMENTATION HALF, NO CODE.** `docs/language-service.md`
  § 3a gained a new subsection, "Ask for the whole open set in one call — this is a rule,
  not a tip", right after the existing `diagnosticsOf` batching example. It states the
  arithmetic (one call pays one floor + one derivation; N calls pay N of each), quotes the
  measured numbers **from § 14's own six-buffer table (`2fa8a39f`, 2026-08-24)**: the same
  6-file set asked as one call costs **321-342 ms**, asked one file at a time it costs
  **748-771 ms** — matching what this item paraphrased as "342 against 771", now traced to
  its actual source rather than to the (INC.14) queue note, which does not carry those two
  numbers verbatim. States plainly this is wall time, pinned by nothing (the page's own
  standing caveat), and restates (INC.14)'s refusal of automatic working-set growth
  (`k·floor + k(k+1)/2·perFile` against a cold `k·floor + k·perFile` — a loss at every k)
  so the "why not just grow the set automatically" question is answered in the same place
  as the recommendation. **GATES: none — no Kotlin source touched, `git diff --stat` shows
  only `.md` files, so `jvmTest`/`cost_gate.py`/`huge_methods.py` were not run.**

- [ ] **(INC.39) (SPINE.1) FOR THE LARGE-BUFFER TAIL — 645 ms IS THE OBJECT ON `checker.ts`,
  AND THE PRIZE IS *NOT* MEASURED.** (INC.37): the three biggest spine handlers
  (`cpaSpineLeave` 22.9%, `spineCtaM3StatementAnchor` 17.4%, `ccetSpineLeave` 10.9%) are
  **645 ms = 51% of handler cost and 37% of `own(checker.ts)`**; a hypothetical 30% cut is
  ~195 ms = **11% of the 1,782 ms query** and ~9 ms of a 108 ms median one. **No cut has
  been priced — 30% is an illustration, not a measurement**, and the whole-program form of
  this item was REFUSED AND CLOSED at round 908 (the passes' own checking work is 91.4% of
  the probed region and every frame pop is at or below one probe boundary). **What is new is
  only the REGIME**: under a single-file partition the tail query is 97% one file's spine,
  so a per-handler lever that was 40% of a rebuild is now ~37% of the worst query.
  **TWO CAVEATS BEFORE ANY WORK.** (a) **The ranking must be re-taken for the target file.**
  It is population-dependent, not a property of the compiler: the top-three permutation
  differs on `binder.ts` / `parser.ts` / `checker.ts`, and `cpaSpineLeave` moves from round
  847's third place to first. (b) **The `dispatch` tier BYPASSES `spineEnterMask`**
  (`spineEnterNode`'s first line routes to `spineEnterNodeProbed` and returns), which is
  round 908's own recorded caveat and is NOT stated on
  `docs/perf/file-check-decomposition.md` § 6 — so that table prices the pre-888 regime for
  the ENTER half and is blind to what the mask already banked. `spineCtaM3StatementAnchor`
  is mask-gated (bit 5); the two LEAVE handlers above it are not. Re-read § 6 with that in
  mind before believing any enter-side share. Graded by the script's own `dispatch` arm
  before/after plus the corpus and `cost_gate.py`.
  **WHERE THIS SITS IN THE CARET-CHANNEL ORDER, STATED EXPLICITLY BECAUSE THE ARC HAS NOW
  REFUSED BOTH OF THE OTHER TWO.** (INC.33) refused WIDENING the prepared request (+286 ms /
  **+25.1 s**, and a 48x / **205x** retention blow-up); (INC.41) refused the RE-ENTRANT VALVE
  for captures (413 rows worse against (INC.2)'s 45-span bar). **The remaining NAMED candidate
  is wiring `completionsAt`/`signatureHelpAt` to `prepared` — (INC.32) defect 1, ~200 ms on
  every keystroke-adjacent query, no correctness question — and the queue must not imply it is
  a cheap win: it is in direct TENSION with (INC.33)**, which measured that `prepare` can only
  serve those channels if its request is widened, which is the thing it refused. So that
  candidate is not free and is not yet priced. **What is missing is the PREPARE-AMORTISED
  case** — pay the widening once for a working set, then answer many carets from it — which
  neither round measured: (INC.33) priced a widened request against ONE hover that pays for it,
  never against a session's worth of queries. Measure that (instrument kept and re-takeable:
  `scripts/inc33-widen-cost.sh` + `Inc33WidenMain`) before anyone builds the wiring. This item
  (per-handler spine cost) is orthogonal to all three and remains unpriced on its own terms.
  **>>> THE PREPARE-AMORTISED CASE IS REFUTED BY MECHANISM, 2026-09-01 ((INC.89)) — IT DOES NOT
  NEED MEASURING, AND THE WHOLE CARET-CHANNEL ORDER ABOVE IS SUPERSEDED. <<<** It survives the
  cost half (a widening charged to `prepare` on idle is not charged to every hover) and dies on
  INVALIDATION: `sourceIndexOf` reads `overlay.readText(key)`, so a host that does NOT report
  the typed `.` through `updateFile` has `completionAnchorAt` computed against the PRE-`.` text,
  answering about the wrong node — while `updateFile`/`deleteFile`/`close` each do
  `captures.clear(); prepared = null` (the `captures` KDoc audits that there is no fourth path).
  **So the dominant completion is invoked at a program state nothing can have prepared.**
  (INC.32) changed the EVICTION bound, never invalidation, so it does not touch this.
  **AND THE PRIZE THE ITEM ASSUMES DOES NOT EXIST.** Re-measured at HEAD: `member.caret` costs
  what `base.noCapture` costs (binder.ts **224 vs 254 ms**, checker.ts **2,035 vs 2,189**), and
  `completions.mid.cold` **215** ≈ `diagnosticsOf.mid.fresh` **219** — the capture work for a
  caret completion is FREE, and the ~200 ms IS one narrowed build. There is no ~200 ms of
  wiring to recover; the lever is the FLOOR, i.e. the live (INC.52)-(INC.89) arc.
  (INC.33) itself was re-run at HEAD and is FIRMER, not weaker: break-even **1.40 -> 1.52** on
  binder.ts and **12.1 -> 12.9** on checker.ts, because the floor arc cut the base (checker.ts
  2,407 -> 2,189) while per-anchor capture did not move; retention unchanged to the digit
  (**54.4 M** records for one widened `checker.ts` entry, `scopeNames` 49,879,917 byte-identical
  across the two runs). Full re-derivation in `docs/language-service.md` § 13 defect 1.

- [x] **(INC.40) DONE 2026-08-24 (`4eff0799`, `8d4e95b0`) — THE "DECAYING" REPLAY IS
  **2.25-2.30x**, AND IT IS NOW SHIPPED FOR DIAGNOSTICS BEHIND A TYPE-LEVEL VALVE.** The
  3.06x -> 1.91x -> 1.68x lineage carried a whole-file `TypeCaptureRequest` in **both** arms —
  the request the correctness differential needs, +9-17 ms per query of cost common to both,
  which dilutes a ratio without trace. Re-priced capture-free in two JVMs: `k = 1` **104-108
  ms -> 25 ms** (2.25/2.30x), `k = 2` 1.72/1.81x, `k = 8` 1.26/1.25x, floor 54 ms cross-checked
  against `partition-equivalence`'s 61; with captures the same HEAD reads 1.34x. The replay's
  TOTAL lands on the whole-program check (4,728 against ~4,935 ms) — (INC.37)'s 1.39x
  re-derivation tax collected. `Project.diagnosticsOf` holds the program through
  `DiagnosticsOnlyRecheck`, a private one-way valve taking `Set<String>` and returning
  `List<Diagnostic>`; dropped by `updateFile`/`deleteFile`/`close`. **0 `DIVERGE-DIAG` and
  0 `DIVERGE-DEF` on both arms** against 43 `DIVERGE-TYPE` — see (INC.41). +9 pins, suite
  15,824 / 0 / 3; `docs/language-service.md` § 4a.

- [x] **(INC.41) REFUSED 2026-08-24 (`6a54f258`) — CLASSIFIED AGAINST tsc's OWN LSP, AND THE
  REPLAY IS THE WRONG ARM: 413 ROWS IN 36 OF 43 FILES GET *WORSE*, 8 GET BETTER, FOR 88 ms ON
  A ROW A USER MEETS OCCASIONALLY.** The clause that kept the valve shut — "the fresh arm is
  not automatically the correct one" — was inferred from (INC.26) and never tested; tested, it
  is FALSE for this population. `compared 373,879` spans over 75 files -> **796 divergent
  (0.213%) in 43 FILES** (41 basenames — tsc has THREE `utilities.ts`), reduced per ELEMENT and
  nesting-aware per (INC.23) to **37 distinct `(fresh, replay)` pairs**, of which **192 rows
  carry more than one differing element**, so a row count over-reports. **REPLAY WORSE 413 / 36
  files; BOTH WRONG 375 / 17; REPLAY BETTER 8 / 4; EQUIVALENT 0.** All 37 causes sampled
  through `tools/tsgo-7.0.2/lib/tsc --lsp -stdio` = **100% coverage BY CAUSE**.
  **THE MECHANISM IS THE TRANSFERABLE HALF: THE REPLAY IS NOT A *DIFFERENT* DEFECT, IT IS
  *MORE OF* (INC.26)'s ALIAS-DISPLAY RACE, AND IT WORSENS WITH SESSION LENGTH.**
  `aliasDisplayMap` is id-keyed FIRST-WINS over INV.5(a)'s member-id-list interning, so a
  registered alias renames that interned union everywhere; the replay carries the seed build
  **plus every earlier recheck**, so more aliases are registered and more unions get renamed.
  393 of the 413 are that shape (tsc and the fresh arm render `Identifier | PrivateIdentifier`,
  which `utilitiesPublic.ts:857` literally writes; the replay renders `MemberName`). **A
  differential taken after ONE query therefore UNDERSTATES a first-wins display defect.**
  (INC.27) already refused the mitigation with a proof. The other **20** are genuine LOST
  RESOLUTIONS (`Connection[][]`, `Map<string, SeenPackageName>`, a bare `T` -> `any`) and are
  the only part that is a bug in the replay itself.
  **THE PRIZE WAS MEASURED FIRST, as this entry demanded** (`Inc41HoverPriceMain`; both arms
  asked the SAME single caret, 40 targets x 4 ABBA rotations, 6 warm-ups, vacuity control
  160/160): arming 188 ms; ONE hover fresh **121 ms** (p90 234); ONE hover replayed **33 ms**
  (p90 143); **3.67x, 88 ms**. **But the row is only "the first hover in a file, at a program
  state some earlier query already built for, with no edit since"** — `quickInfoAt` memoises
  per BUFFER (~2-4 ms for a second caret) and any edit drops the handle, so the keystroke loop
  gets nothing, and `completionsAt`/`signatureHelpAt` get nothing either ((INC.32) defect 1).
  **AGAINST (INC.2)'s BAR — 45 divergent spans of 381,666 (0.012%) — 413 of 373,879 (0.11%) is
  NINE TIMES IT, in the same silent direction.** REFUSED.
  **WHAT WOULD CHANGE IT, IN ORDER, AND NEITHER IS FREE.** (1) Wire
  `completionsAt`/`signatureHelpAt` to `prepared` (~200 ms per keystroke-adjacent query, no
  correctness question) — **but (INC.33) measured that `prepare` can only serve them if its
  request is WIDENED, and refused that at +25.1 s on `checker.ts` and 54.4 M retained records**,
  so it needs its own measurement of the prepare-amortised case (pay once, query many) before
  anyone builds it. (2) Close the 20 lost resolutions; what then remains is purely the naming
  race, which is an owner-level logical-parity conversation, not a round.
  **THE 375 BOTH-WRONG ROWS ARE NOT PART OF THIS ITEM** — they are an ordinary-build defect,
  queued as **(INC.42)**. Authority and re-take instructions:
  `docs/inc41-replay-capture-classification.md`; instruments `Inc41ClassifyMain`,
  `scripts/inc41_classify.py`, `scripts/lsp_hover_project.py`. No compiler behaviour changed;
  suite unchanged at 15,824 / 0 / 3. **ORIGINAL ENTRY:**
  **THE 43 `DIVERGE-TYPE` FILES ARE THE STANDING CAPTURE-CHANNEL STATE, THEY ARE
  THE WHOLE REASON (INC.40)'s VALVE IS DIAGNOSTICS-ONLY, AND SINCE (INC.33) THEY ARE THE **NAMED
  UNBLOCKER FOR THE ENTIRE CARET-CHANNEL LATENCY STORY.***
  `replay-differential.sh` at HEAD: every diagnostic row and all 352,713 definition spans
  agree between a re-entered answer and a fresh narrowed build's, while the CAPTURED TYPE
  channel diverges in **43 of 75 files** (the banner's "5 of 75" was stale, pre-(INC.26)/(INC.28);
  43 is the pre-existing state, verified on a clean tree before (INC.40) touched anything).
  **The rows are overwhelmingly the union-alias display family (INC.26)/(INC.27)** — the replay
  renders `ModuleExportName` where a fresh build renders `StringLiteral | Identifier` — which
  (INC.27) PROVED is an interning-KEY question, and **in which the fresh arm is not
  automatically the right one** ((INC.26)'s law: a full-vs-narrow differential silently assumes
  the full arm is the reference). The residue is lost generic INFERENCE (`Connection[][]` read
  as `any[][]`, `Map<string, SeenPackageName>` as `Map<any, any>`), silent in the dangerous
  direction. **Closing these is what would let `quickInfoAt`/`definitionsAt`/`completionsAt`
  through the same valve** — the caret channels (INC.33) says are cold per channel per buffer.
  What it is worth is UNMEASURED: (INC.40) priced only the diagnostics arm, and the capture
  arm's own with-capture ratio at HEAD is 1.34x, so the prize must be re-priced for the caret
  channels before any work — not inherited from the 2.25x row. Classify per ELEMENT
  ((INC.23): `narrowRendersMoreAny` over-reports and a nonzero is a LEAD, never a finding).
  **WHAT (INC.33) ADDED, AND IT IS WHY THIS ITEM IS NOW THE ONLY ROUTE.** The obvious
  alternative — widen the file-wide request so one build serves every caret channel — was
  PRICED AND REFUSED: **+286 ms on `binder.ts` and +25.1 s on `checker.ts`** against a **204 /
  2,078 ms** completion (break-even **1.40** / **12.1** completions per hover with no edit
  since), plus a retention blow-up of **48x / 205x** (54.4 M records for one `checker.ts`
  entry). **A request is priced per ANCHOR; an editor needs a price per ANSWER**, and a
  re-entrant capture against a retained checker is the only shape with that property — so
  closing these 43 rows is not one option among several, it is the route. **TWO CONSTRAINTS ON
  RIDING IT.** (i) The prize still has to be measured for the caret channels, per the paragraph
  above. (ii) **A re-entrant capture does NOT by itself unblock free-name completion**:
  `CapturedScope` repeats the lib globals at every anchor (**O(anchors x globals)** — 49,879,917
  names for `checker.ts`, and a widened `scopes.file` arm read **+19.4 s** there), so that
  channel needs its own fix whichever mechanism serves it.

- [x] **(INC.42) PARTIALLY DONE 2026-08-24 (`73811153` + `624812c2`) — A REAL ORDINARY-BUILD
  DEFECT IS FIXED, AND IT IS *NOT* THE 213 ROWS THIS ITEM WAS AIMED AT.** What landed: a bare
  `Type.TypeParam` alias argument was judged by `checkTypeRelatedTo`, which has no "TypeParam
  source via its constraint" rule, so it read as a constraint **FAILURE** where the honest
  answer is **UNDECIDED** — the reference answered `errorType` and rendered `any`. Three lines
  reproduce it with no partition (`type R1<T extends Nd> = T | readonly Nd[]; type A1<X extends
  Nd> = (n: number) => R1<X>` renders `(n: number) => any`; tsc 7.0.2's LSP renders
  `(n: number) => R1<X>`), and a constraint matrix isolated the predicate: an UNCONSTRAINED
  inner parameter is always correct, and **every** row whose inner parameter carries a
  constraint failed — including where the two constraints are IDENTICAL. The argument is now
  judged locally against its own already-resolved constraint, behind two measured gates
  (`aliasBodyDisplayDepth`, `aliasGuardIsRecursionBrake`); no new rule enters
  `checkTypeRelatedToCore`. Suite 15,831 / 0 / 3 (+7 pins), zero corpus baselines moved, both
  capture digests re-recorded by design. See the session note.
  **WHAT IS NOT DONE: the 213 rows. `Inc41ClassifyMain` re-run reads 796 rows / 37 pairs /
  213 GAINED-INFERENCE — UNCHANGED.** Do not read this checkbox as the mission closing; the
  residual is re-scoped and re-queued as **(INC.43)** with what those rows actually are.
  ORIGINAL ENTRY: **`Visitor` / `VisitResult<T>` HOVER AS `(node: TIn) => any` ON *EVERY ORDINARY
  BUILD*, AND THE CAPTURE SWEEPS ARE STRUCTURALLY BLIND TO IT — 375 ROWS IN 17 FILES, 213 OF
  THEM THIS ONE CAUSE.** Found as a by-product of (INC.41)'s classification: of the 796
  divergent rows, **375 are BOTH WRONG** — the fresh arm and the replay agree, and tsc 7.0.2
  disagrees with both. **That is not a replay defect and not a partition defect. It is on the
  shipped build, at every caret, today.** The largest cause by far is `Visitor` /
  `VisitResult<T>`: we render `(node: TIn) => any` where tsc renders `Visitor` (213 rows).
  Two smaller causes in the same bucket, both a *widened* rendering where tsc narrowed:
  `ModuleName` -> tsc's `StringLiteral` (74) and `ImportAttributeName` -> `StringLiteral` (62),
  plus 17 rows where a 3-member expansion should be tsc's `JsxOpeningElement`.
  **WHY NOTHING HERE HAS EVER SEEN IT, AND WHAT THAT DICTATES ABOUT THE PIN.**
  `capture-equivalence.sh` and `capture-channel-equivalence.sh` are **DIFFERENTIALS** — they
  compare two arms of our own compiler — so a defect present in BOTH arms is invisible to them
  **by construction**, which is (INC.28)'s law verbatim (its two-arms-agree test and its
  negative control both stayed GREEN against the unfixed binary while two real pins went RED).
  The diagnostics channel is silent too: a wrong-but-plausible type is never an error.
  **So the pin MUST ASSERT THE VALUE, never that two arms agree** — and the ground truth is
  obtainable rather than guessable: `tools/tsgo-7.0.2/lib/tsc --lsp -stdio`, through round 924's
  `scripts/lsp_hover.py` or (INC.41)'s `scripts/lsp_hover_project.py` (which points at an
  EXISTING project; read its sources with `newline=""` — the profile is CRLF).
  **PRIZE: UNMEASURED, AND DELIBERATELY SO — THIS IS A CORRECTNESS ITEM, NOT A LATENCY ONE.**
  It buys no milliseconds; it makes a hover right. **START BY SEPARATING THE CAUSES**: the
  `Visitor` rows are a lost/attached ALIAS on a function type, while the `ModuleName` and
  `ImportAttributeName` rows are the reverse of (INC.41)'s replay defect (we NAME where tsc
  NARROWS), so they are probably not one fix — and note (INC.28) already touched
  `VisitResult<T>`'s neighbourhood (its writer hook printed `name=VisitResult ... type=any`),
  so re-read that session note before starting. `docs/inc41-replay-capture-classification.md`
  § 3 carries the per-cause table; `scripts/inc41_classify.py` re-derives it, and a change is
  an improvement only if the BOTH-WRONG **element-pair** count falls ((INC.23)'s rule: count
  distinct pairs, not rows — 192 of the 796 rows carry more than one differing element).
  Any change to union or alias display touches ~13k pinned corpus baselines.

- [x] **(INC.44) `referencesAt` IS NARROWED — LANDED 2026-08-29, AND THE CLAIM IT REPLACES
  ("its claim is about every file, so there is nothing to narrow to", `docs/language-service.md`
  § 10b and § 14's gap 1, written three times over three rounds) WAS A CATEGORY ERROR: THE
  CLAIM IS PROGRAM-WIDE, THE **EVIDENCE** IS NOT.** Every reference search typed **381,672
  spans** — every identifier plus every member-name literal in every program file — on a
  whole-program check, and then discarded all but the ones whose declaration set met the
  caret's. An occurrence can only be an answer if it SPELLS a name the symbol is reachable by,
  so the population is selectable BEFORE it is typed; `captureIn` already derives the check
  partition from the request's own spans, so narrowing the request narrows the build with no
  new mechanism at all.
  **THE CLOSURE IS ANCHORED BY A FACT ABOUT THE ONLY TWO ALIASING FORMS.** `import { p as q }`
  and `export { p as q }` write BOTH spellings in the file that DECLARES the alias, so
  iterating "select the files containing a name I am looking for, read the aliases they
  declare, repeat" reaches a fixed point without ever opening a file the search had no other
  reason to open. Everything else is REFUSED (`SyntaxRoles.isAliasEscape`) and falls back to
  the whole-program sweep: a default export, the local a default import binds, an `export =`,
  an `import x = require(…)`, a namespace binding, and any closure reaching the spelling
  `default`.
  **THE FILE FILTER MAY NOT BE A PLAIN SUBSTRING TEST, AND FINDING OUT WHY IS THE ROUND'S
  TRANSFERABLE LESSON.** `StringLiteralNode.text` is the COOKED value (`rawText` is the
  source), so `o["pl\ain"]` names the member `plain` while the file spells `pl\ain` — and
  `\a` is an IDENTITY escape, so ANY backslash inside a literal can hide a name, not only
  `\u`. A file may therefore be skipped only when it contains no backslash at all (49 of
  tsc's 78; the other 29 hold 78.2% of the characters), and the exact filter stays
  `occurrenceText(node) in names` — so the PARTITION is exact either way and only the
  indexing cost moves.
  **MEASURED, both arms interleaved in ONE process at the same caret** (`partition` is a
  counter and is the column that transfers; the ms are wall time on one box):
  `createTypeChecker` **2 of 78 files**, 5,359 ms first / 130 ms repeat against **11,112 ms**;
  `emitFiles` 2 of 78, **553 ms** first / 141 repeat against **9,532**; `transformNodes` 3 of
  78, **528 ms** / 135 against **9,320**; `checkSourceElement` **1 of 78** but that file is
  `checker.ts` (31.6% of the program), **1,940 ms** against **9,291**; and the worst realistic
  case, `SyntaxKind` at **49 of 78** and 9,827 hits, **4,904 ms** against **9,078** — so the
  narrowing never loses, because a refusal is the old path exactly.
  **POPULATION CENSUS, which is why this works**: over the 31,455 distinct names in tsc's own
  compiler sources the MEDIAN name is written in **1 file** and occurs **3 times**; p90 is 5
  files / 22 occurrences. Weighted by where a caret LANDS the median is 28 of 78 files —
  `node`/`type`/`kind` dominate the occurrence count — so a search for a very common word
  narrows little and a search for a name a user actually asks about narrows enormously.
  **GRADED BY A DIFFERENTIAL, not by an argument.** `Project.narrowReferenceSweeps` is the
  in-binary OFF arm ((API.6)'s shape) and `scripts/reference-narrowing-differential.sh` runs
  both over a real project, element for element: **EQUIVALENT** — 60 carets drawn by stride over all 381,775 occurrences, **59 of them actually narrowed** (the control), **0 diverged**, 12,248 hits compared element for element; mean partition **17.5 of 78 files**, aggregate 182.0 s narrowed against 561.6 s whole-program (**3.09x** on a draw that lands proportional to occurrence count, i.e. on the hottest names). `Project.narrowedSweepFiles` is the
  CONTROL — a caret that refuses falls back and then agrees with itself, so without a count of
  the carets that actually took the new path a run in which everything refused would print
  EQUIVALENT having tested nothing (round 790's dead verifier).
  **THE ESCAPE GUARDS ARE CONSERVATISM TODAY, AND THE ROUND SAYS SO RATHER THAN CLAIMING A
  FIX.** Ablation a3 (nothing is an escape) reddens only the three REFUSAL pins; the
  equivalence assertions above them pass, i.e. the narrowed answer would still be right on
  every fixtured shape. They are kept because the gap they anticipate is **measured**: tsc
  7.0.2's own language server answers **6** references on a `export { renamed as default }`
  declaration — both `d` occurrences in the importing file included — where this API answers
  **2** (`scripts/lsp_member_refs.py`). The day that divergence closes is the day the guard
  becomes load-bearing, and `ProjectReferenceNarrowingTest` pins it so that day is loud.
  **+12 pins, four-arm ablation, four DISTINCT red sets** (a1 alias closure -> the two alias
  pins; a2 escape-aware file filter -> the escape pin; a3 `isAliasEscape` -> the three refusal
  pins; a4 the `default` spelling -> the export-renamed-to-default pin).
  **NEXT, and it is NOT free**: `renameAt` rides the same sweep and is 20-26 s, but it reads
  the build's DIAGNOSTICS as well as its captures — a partition filters those to its own
  files, so the before/after multiset comparison has to be narrowed on BOTH sides, and
  `verifyRename` additionally scans for occurrences already spelling the NEW name, which the
  selection must therefore carry.

- [x] **(INC.47) LANDED 2026-08-29 AS A CANONICAL SERIALIZATION RATHER THAN SCC HASHING,
  AND ITS PRIZE IS REFUTED — the walk is LINEAR and the escape class is EMPTY, but the
  stability rate is 67% on BOTH arms with all 40 per-case verdicts IDENTICAL.** There was
  no SCC left to hash: discovering each reachable type once and naming it by its discovery
  INDEX makes a reference — forward, back or self — cost one lookup, so cycles need no
  special case. `types.ts` went from **122.52 ms for ONE export and a node-budget STOP** to
  **6.21 ms for 871 exports**; whole-program 131 -> **16 ms**; structural nodes 2,019,605 ->
  **38,502**; escapes `[types.ts]` -> **[]**; both controls held (78/78, 24/24).
  **THE 87.5% CEILING WAS A MIS-READ LABEL** on `Inc46StabilityMain`'s own summary line
  (`if (escaped)` counts every case that TOUCHED an escaping file, not one that moved only
  because of it) — exactly ONE of the 8 had the escape as its only mover, so the real
  ceiling was 70%, and after this even that case moves. **IT LANDS ON SOUNDNESS**: the old
  walk's DEPTH CAP of 24 hashed everything below it as one constant, i.e. a MISSED
  invalidation live since (INC.46)(3) began serving stale-free project diagnostics; both
  new pins are RED on the pre-(INC.47) binary. **DO NOT RE-OPEN SCC HASHING.**
  ORIGINAL ENTRY: SCC-AWARE HASHING — the one lever between the measured 67% and the 87.5%
  ceiling, and (INC.46)'s named successor.** `types.ts` is the only file of the 78 that still
  ESCAPES, and it accounts for **8 of the 13 fallbacks** in the 40-commit corpus. Its walk is a
  node-budget stop that no budget closes: **129.6 ms at 2,000,000 nodes and 741 ms at
  12,000,000, still stopping** — the file-boundary cut cannot help INSIDE a file, and `types.ts`
  declares ~874 mutually recursive interfaces in one. **The mechanism is Tarjan over the in-file
  type graph, hashing each strongly-connected component as a UNIT** rather than trying to
  memoize closed subtrees that never close. **Grade it on the instruments that already exist**:
  `scripts/inc46-fingerprint-cost.sh` (cost, stability 78/78, partition agreement 24/24) and
  `scripts/inc46-stability.sh` (the rate — the number that must move, and the refusal threshold
  is that it does not).

- [x] **(INC.48) LANDED 2026-08-29 — `Project.saveState()`/`restoreState()`, and a restart
  is **60x**: 155-175 ms against 9,625-9,844 ms in a COLD process, 94 ms against 5,855 warm,
  with a 47 KB snapshot and every arm agreeing row for row.** It writes no file (the host
  decides where its caches live); it validates the compiler build id, the config path, a
  CONTENT hash per file AND per `.json` input; and a restored state is not trusted until
  one build has re-crawled the project, because a file ADDED while the process was down is
  in no content hash — ablated, that is the pin the naive implementation fails.
  ORIGINAL ENTRY: THE EXPORT SURFACE DIES WITH THE PROCESS, AND tsgo's DOES NOT — an IDE
  restart pays a FULL build where tsgo pays a `.tsbuildinfo` read.** Ours is `Project.surface`,
  in-memory, dropped at `close()`. Theirs is serialised and re-read, which is what makes their
  **182 ms no-op** possible from a cold process at all. **The prize is bounded and known**: it
  turns a post-restart first query from a full build into the (INC.46) gate, i.e. ~5.2 s ->
  ~230 ms whenever the tree has not moved under the editor. **The hazards are the ones the
  fingerprint already documents** — it must be keyed on CONTENT (the crawl reads every file
  anyway; an mtime/size key is round 871's trap), it must carry the compiler options and the
  program's file list or a config change serves a stale surface, and a version stamp must
  refuse a file written by a different build. **Measure the serialise/deserialise cost against
  the 136 ms it replaces before building the invalidation.**

- [x] **(INC.58) DONE 2026-08-30 — IT WAS ONE PASS, AND IT WAS `checkJsxImportResolutions`:
  **709.74 of a 774.65 ms floor pass table (92%) on a 2,401-file project with NO JSX**,
  growing 14.6x for 4x the files. `resolveJsxTsxCandidate`'s path-suffix fallback walked
  every file of the program once per import specifier per extension, and the pass is gated
  on `--jsx` being UNSET — maximum work on exactly the projects that never use JSX, always
  answering null. Restricted to the `.jsx`/`.tsx` subset (exactly equivalent: every
  non-null return is such a file, and order is preserved so the FIRST match is unchanged),
  with a whole-pass early-out when the program has none. **709.74 -> 0.30 ms, and LINEAR.**
  **(INC.54)(a) had ranked this pass at 1.2 ms from the tsc profile — 600x, so that item's
  whole ORDERING must be re-taken on the many-small shape before any of its rows is
  opened.** Together with (INC.57) the floor goes **1653 -> 366 ms** at 2,401 files.
  Suite 16,503 / 0 / 3; both gates clean and every cost counter unchanged.
  `docs/perf/import-dep-scan-complexity.md` § 6. ORIGINAL ENTRY:** (INC.57)'s successor, and it
  re-frames (INC.54)(a) rather than repeating it. Measured AFTER (INC.57), so the
  quadratic is not in these numbers (`scripts/floor-decomposition.sh`, two draws):
  **73-91 ms at 601 files, 204-217 at 1201, 756-810 at 2401** — 2.4-2.8x then 3.5-4.0x
  for 2x the files, i.e. roughly `N^1.5`-`N^1.9`, on the region an editor pays for on
  every keystroke. `FrontEnd.CHK_INIT` already localises it to the init-block pass
  dispatch (207-209 ms of the 215-218 at 1201 files), so the decomposition is one
  `--passTimingRows` run away.
  **WHY THIS IS NOT JUST (INC.54)(a).** That item ranked the pass table's rows from the
  **tsc profile** — `init:computeAllEnumValues` 6.9 ms, `init:moduleTypeNameIndex` 2.6,
  and so on. (INC.57) is the standing proof that a ranking taken on 78 huge files does
  not transfer to an application shape: a pass whose cost is per-FILE sorts differently
  there, and a SUPER-LINEAR term is a different kind of target from a flat 6.9 ms row.
  **Re-take the ranking on `build/bench/many-small-2400` first** — the flat rows may
  not be the ones worth moving.
  **WHAT TO SUSPECT, stated so it can be refuted rather than assumed:** a super-linear
  term in a per-file pass loop means some pass does work proportional to the PROGRAM
  inside a loop over FILES — exactly (INC.57)'s shape one layer up. The instrument that
  finds it without reading 400 passes is the same one: two program sizes, and a per-pass
  table divided by file count. A pass that is honestly O(program) reads a CONSTANT
  µs/file; the quadratic one reads a doubling.

- [x] **(INC.61) LANDED 2026-08-30 — `buildPerFileScopes` copied the whole SHARED half
  (lib globals + script locals + global augmentations) into a fresh table PER FILE, i.e.
  `files x libGlobals` insertions. **The fixture was hiding it**: with `lib: ["es2020"]`
  the pass is 13.5 ms and with `dom` added — which is what an unset `lib` means — it is
  **175.6 ms on the same 2,401 files**, 70% of the floor pass table. `LayeredSymbolTable`
  overlays the file's own locals on a base built once, reproducing the copy's iteration
  ORDER entry for entry (a `LinkedHashMap` keeps a shadowed key's ORIGINAL position, so a
  shadowing local may not be appended — the one thing an implementation gets wrong, and
  the only pin ablation c1 reddens). Measured: pass **175.6 -> 3.9 ms**, init dispatch
  334 -> 42, checker construct 393 -> 83, floor phase total 503 -> 200, and the PLAIN
  floor median 385 -> 202 ms.**

- [x] **(INC.62) DONE 2026-08-30 — the floor IS re-taken on `dom`, and the row it exposed
  ((INC.63), `parseBuiltinLib` 46-50 -> 1.5-1.7 ms, floor 241 -> 189 / 256 -> 166) had
  been REFUSED WHOLE by (INC.54)(c) on a blocker that owns 3% of it. The successor
  ranking is in (INC.64) below. ORIGINAL ENTRY:** RE-TAKE THE FLOOR ON A `dom` FIXTURE BEFORE OPENING ANY OF ITS ROWS —
  AND TREAT THAT AS THE DEFAULT SHAPE FROM HERE (2026-08-30, (INC.61)).** Every floor
  figure in this arc was taken on a fixture pinning `"lib": ["es2020"]`, and (INC.61)
  measured the SAME program's largest pass at **13x** with `dom` added. The ranking at
  2,401 dom-lib files after (INC.61) is: crawl WALL **55 ms (27%)** ((INC.56), the only
  row costing a soundness promise), checker construct 83 — **of which the pass table 42
  and the FIELD initializers 41**, so (INC.53)'s territory is now HALF of it and was last
  priced at ~10 ms on the tsc profile — config 23, `extractRelativeImports` 16,
  post-checker 14. `build/bench/many-small-2400-dom` exists for this; generate the other
  sizes the same way (copy the tsconfig, add `dom` to `lib`). **The instrument is
  unchanged and it is the one that has now found five defects: divide a row by its own
  population, at two program sizes, and refuse an implied per-op cost that is physically
  impossible.**

- [x] **(INC.68) DONE 2026-08-31 — `PathUtil.normalize` got a one-pass, allocation-free fast
  path. Census: **11,935 calls per floor build, 9,584 (80.3%) already normalized**; measured
  per call **1.02-1.22 us -> <=0.2**. ABBA-rotated, 4 processes per arm: `vfs.listEntries`
  10.86 -> 7.76, specifier resolution 14.94 -> 10.66, crawl WALL 39.51 -> 32.18, config+glob
  17.96 -> 13.44, floor median **127 -> 121 ms**. The BLOCKED arms of the same comparison
  reported a +2.70 ms regression in a region calling no `normalize`, reproducibly over 12
  draws per arm, and rotation inverted it — see the session note. Successor is (INC.66)
  below, whose ranking is unchanged except that config+glob is now ~13 ms.**

- [x] **(INC.71) DONE 2026-08-31 — the INV.3(b)(ii) per-file VISIBILITY sets are built on
  FIRST ASK, and a floor build builds neither.** Their three readers are all name resolution,
  so a build that checks nothing reads none: **0 asks on a floor build against 335,881 on a
  full one**, measured with a temporary counter BEFORE the implementation, as a GO/NO-GO.
  Row -> 0.002-0.003 ms from 5.5-7.2; ABBA-rotated floor **142.5 -> 120.0 ms**. The INV.3(a)
  classifier stays installed at the pass's moment and forces the sets from inside its lambda,
  so `globals.lookups` is +0.00%. Value receipt: ablation c2 reddens **492** corpus tests,
  where the `-project` pins could not see the mechanism at all.

- [x] **(INC.73) DONE 2026-08-31 — the module TYPE-NAME index is built on first ask (2.52 ms),
  and the round's two REFUTATIONS are worth more than the row.** GO/NO-GO first:
  `moduleTypeNameIndexBuilds` 0 on a floor build, 1 on a full one. **Its value receipt is the 8
  PROFILES and the corpus is a CONTROL — the ablation that never builds it reddens ZERO of the
  ~13k baselines and 3 of the 8 profiles (+2 rows each: harness, server, services)**, which is
  where rounds 471 and 513 got their evidence. A family can have no corpus coverage and still
  be load-bearing; ablate and grid rather than reason. **Refuted in the same recon, both
  cheaply: (a) `SystemVfs.exists` is 1.02x `java.io.File.exists` — ONE syscall, so (INC.60)'s
  five-stat finding is specific to `metadataOrNull`, and the resolver already probes exactly
  ONCE per resolution (2,351 `exists` + 10 `isDirectory` for 2,351 distinct pairs), so there is
  no syscall lever in the crawl's 11 ms resolution row; (b) `init:collectUmdGlobalsAndModuleFiles`
  and `init:mergeFileLocalsIntoGlobals` are not deferrable, because their products are consumed
  by LATER INIT PASSES rather than by checking — combined prize ~5 ms, refused on arithmetic.**
  Neither the floor wall nor a 2-process phase A/B can resolve 2.5 ms, and the write-up says so.

- [ ] **(INC.74) WHAT IS LEFT IN THE FLOOR, AFTER (INC.73) CLOSED THE INDEX ROWS
  (2026-08-31).** The init-block dispatch now has no non-walker row above ~1.4 ms, so what
  remains there is (INC.7)'s partition question ONE WALKER AT A TIME —
  `checkCircularGenericCallbackVariables` 1.38, `checkSpreadNonIterableIntoFixedArity` 1.26,
  `checkModulePreserve4Pin` 1.26 ((INC.70b)), `checkCircularClassBaseViaDefaultTypeArg` 0.88,
  `checkCrossFileUseBeforeDeclaration` 0.82 — each of which needs the round-609 collector
  classification read, not guessed. **The floor's largest row is the CRAWL (~29 ms, 36%) and
  its READ half is (INC.56), now the only row left with a double-digit prize** — and it is the
  one an IntelliJ-class host can simply hand us, so it is the one to open now that the plugin
  exists to promise it. Its `specifier resolution` sub-row is REFUSED as a syscall question by
  (INC.73)(a); what is unexamined there is the ~8 ms of non-syscall work per 4,701 calls.

- [x] **(INC.72) DONE 2026-08-31 — ANSWERED, and it retracts two of this session's own wall
  figures.** The surplus was the CRAWL, not a mechanism: the per-PHASE instrument over the same
  two binaries attributes **init-block pass dispatch -14.81 ms** (which is what the pass rows
  said) while the untouched crawl WALL swung **+18.01** in the same run, its
  elapsed-with-suspension `read+decode` sum moving 102 ms. So (INC.70)'s -23.5 and (INC.71)'s
  -22.5 are each one batch's reading of a quantity carrying a ±20 ms concurrent term; the same
  binaries read 128.5 -> 116.5 in this round's batch. **THE TRANSFERABLE LESSON IS NOT "ROTATE
  MORE" BUT "PICK AN INSTRUMENT WHOSE VARIANCE DOES NOT CONTAIN THE ANSWER"** — 4 processes x 8
  draws per arm did not help, because the noise is a real unrelated phase rather than jitter.
  For a checker-side floor change the receipt is now `FrontEnd`'s phase row plus the
  deterministic count; the wall is a sanity check. `FloorAbMain`'s new `fe` mode is the
  two-binary form of that decomposition.

- [x] **(INC.70) DONE 2026-08-31 — per-file name-resolution scopes are built on FIRST ASK,
  and a floor build builds NONE.** `init:buildPerFileScopes` allocated two maps and a
  `LayeredSymbolTable` shadow list per program file on every build; the population measured
  first, per (INC.16), is **2,401 -> 0 on a floor build and 2,401 -> 2,401 on a full one**.
  Row **4.625 -> 0.750 ms**; ABBA-rotated floor **160.0 -> 136.5 ms**, of which only ~4 ms is
  attributed (the surplus is recorded, not claimed). Exactness rests on an INIT-ORDER fact:
  the only writer of a `BinderResult.locals` is `collectModuleAugmentations`, dispatched at an
  earlier step. Value half gated by the CORPUS as a measurement — ablation b2 (never build a
  scope) reddens **503** core tests.

- [ ] **(INC.70b) `checkModulePreserve4Pin`'s RAW whole-source `.contains` — REFUSED, with the
  measured reason (2026-08-31).** It is 1.2 ms of the floor and one of exactly four
  `sourceFile.text.contains(` sites in `Checker.kt` that bypass (WARM.19)'s `srcHas` n-gram
  filter. **Routing the three `binderResults`-scoped ones through `srcHas` REDDENS
  `PartitionSrcScanPassTest`'s cost receipt** — and correctly: a whole-program scan asked
  through `srcHas` FORCES every file's filter, including out-of-partition files, which is
  exactly what that pin's "a narrowed build builds fewer filters than the whole program"
  assertion exists to catch. It would also very likely be a LOSS on the floor, because after
  (INC.20)/(INC.21) the other ~50 `srcHas` callers are partition-gated and build nothing
  there, so this pass would be the FIRST asker and would pay the filter build for all 2,401
  files — CLAUDE.md's "the srcScan family must be gated all at once or not at all". **What is
  left to try is a cheaper PRE-GATE on the program's shape**, not a change of scanner: the
  pass's whole emitting loop is a `when (basename)` over `a.js` / `f.cts` / `main1.ts` /
  `main2.mts` / …, so (INC.69)'s `filesNamed` index can answer "can this pass do anything at
  all" in one probe — but the `diagnostics.removeAll` above that loop is NOT keyed on a
  basename, so such a pre-gate is a behaviour change for a program that carries the needle and
  none of the names, and needs its own corpus receipt. The FOURTH site
  (`retValue != 0 ^=`, inside a `checkedResults` loop) is already partition-scoped and could
  be routed safely; it is 0.001 ms, i.e. not worth a suite run on its own.

- [x] **(INC.69) DONE 2026-08-31 — the init-block dispatch is NOT flat, and 21 corpus-PIN
  walkers were the plateau.** (INC.66) recorded the ~400-pass table as flat "so there is no
  row to make cheaper"; a HISTOGRAM rather than a top-N list says otherwise — 418 rows, 44 of
  them carrying 94% of 39.5 ms, and 21 of those 44 sitting at an almost identical
  0.39-0.55 ms because they share one line: a whole-program loop whose first act is
  `fileName.substringAfterLast('/') != "<one literal>"`. One basename index built on first
  ask took those 21 rows **10.079 -> 0.457 ms** (second instrumented draw; 0.438 of the
  remainder is the first asker paying the build) and the ABBA-rotated floor
  **157 -> 144.5 ms**. **THE TRANSFERABLE HALF: a PLATEAU of near-identical prices across
  unrelated passes is a shared per-file cost, not a coincidence of what they do — and a
  top-N ranking cannot show a plateau, only a distribution can.** Successor is
  `checkModulePreserve4Pin`'s raw whole-source `.contains` (1.2 ms, one of three sites that
  bypass (WARM.19)'s `srcHas` filter), then the five `init:*` heads, of which
  `init:computePerFileVisibility` + `init:buildPerFileScopes` are the (CHK.49) pair and move
  together or not at all.

- [ ] **(INC.66) THE dom FLOOR AFTER (INC.65), AND THE INSTRUMENT LESSON THAT OUTRANKS THE
  ROWS (2026-08-30).** Floor medians **151 ms (early) / 116 (late)** at 2,401 files, from
  241 / 256 at the start of the (INC.63) session. Rows: **checker construct 38-70 ms** —
  the init-block pass dispatch, FLAT across ~400 passes (top `init:computePerFileVisibility`
  5.8, then 3.8 / 2.7 / 2.5 / 2.1), so there is no row to make cheaper and the question is
  (INC.7)'s — which passes can be PARTITION-SCOPED or built on FIRST ASK, with (INC.20)'s
  MIXED-pass split as the shape that has worked; note `init:computePerFileVisibility` and
  `init:buildPerFileScopes` are the (CHK.49) PAIR, one observable, and must move together
  or not at all. **crawl WALL 34-44**, of which `CRAWL_RESOLVE` is 13.5-15.9 after (INC.65)
  and the READ half is (INC.56) — still the only row costing a SOUNDNESS PROMISE, and the
  one an IntelliJ-class host can simply hand us. **config+glob 13-29**, which is co-largest
  with the crawl on some draws, has NO promise attached, and should be re-decomposed rather
  than assumed finished by (INC.60). bind 7-9, post-checker 5.7-7.2.
  **THE LESSON RANKS ABOVE THE ROWS: BEFORE PRICING ANY ROW, CHECK IT HAS A SPLIT.**
  (INC.65)'s entire finding lived in a residue no sub-row named — `FrontEnd.CRAWL` had two
  sub-rows, both elapsed-with-suspension, so the gap between them and the wall was
  unattributed and was half the row. That is (INC.53)'s "ask what runs OUTSIDE a pass" with
  a sub-row-shaped twin, and it is the third time this arc that adding an instrument, not
  reading one, is what found the cost.

- [x] **(INC.64) DONE 2026-08-30 — both halves landed** (the crawl's per-file dispatcher hop,
  and the emit-order prep running under `--noEmit`), and (INC.65) then found a THIRD row in
  the residue the crawl had no sub-row for. Successor ranking is (INC.66) above.
  ORIGINAL ENTRY:** THE dom FLOOR AFTER (INC.63) — TWO CO-LARGEST ROWS, AND ONLY ONE OF THEM
  IS A MICRO-OPTIMISATION QUESTION (2026-08-30).** Measured on
  `build/bench/many-small-2400-dom`, 2,401 files, three arms, PLAIN floor median 166-189 ms:
  **(a) the init-block pass dispatch, 40-53 ms** — now the largest checker row, and it is
  FLAT in exactly (INC.3)'s sense: `init:computePerFileVisibility` 5.8 ms, then
  `init:buildPerFileScopes` 3.8 (already fixed once by (INC.61)),
  `init:moduleTypeNameIndex` 2.7, `init:collectUmdGlobalsAndModuleFiles` 2.5,
  `init:mergeFileLocalsIntoGlobals` 2.1, and a ~400-pass tail carrying the rest. There is
  no row to make cheaper; the question is (INC.7)'s — which of these can be
  PARTITION-SCOPED or built on FIRST ASK — and (INC.20)'s MIXED-pass split is the shape
  that has worked. Note `init:computePerFileVisibility` and `init:buildPerFileScopes` are
  the (CHK.49) PAIR: they are one observable and must move together or not at all.
  **(b) crawl WALL, ~~51-57~~ 43-49 ms after (INC.64)(a) removed the per-file dispatcher
  hop** — (INC.56) for the READ half, which is still untouched and is the only row that costs a
  SOUNDNESS PROMISE, which is why it stays ranked behind anything that does not. It is
  also the row an IntelliJ-class host can simply hand us, so it is the one to open once
  the plugin exists to promise it.
  **(c) config+glob 17-30, `extractRelativeImports` 15-23, post-checker 10-21, bind 7-10.**
  **BEFORE OPENING ANY OF THEM, RE-TAKE THE TABLE**: this arc has now re-ranked itself
  four times ((INC.58), (INC.59), (INC.61), (INC.63)) and each new top row was one no queue
  item had named. And keep dividing by the population — that instrument has found five
  defects and cost nothing.

- [ ] **(CHK.74) A CROSS-FILE DUPLICATE GLOBAL IS SILENT — `declare const VERSION: string;`
  IN TWO SCRIPT FILES IS **TS2451 TWICE** IN tsgo 7.0.2 AND NOTHING HERE (2026-08-30,
  measured by (CFG.1), which needed it as a value pin and could not have one).** Its
  significance is larger than one row: together with (CFG.2) it is why a defect that
  changed the PROGRAM ITSELF — (CFG.1)'s wrongly-adopted `dist` tree — was invisible to
  every diagnostic channel in this repo, so the only observable left was a file COUNT.
  Repro is two files and a tsconfig; grade against
  `tools/tsgo-7.0.2/lib/tsc --noEmit -p <dir>`, and mind that a `.d.ts` gets lighter
  checking than a `.ts` here (three shapes were tried before one reported at all —
  a stale import, a stale type reference and this one — and only tsc reported any of them).

- [ ] **(CFG.2) TS5011 (the common source directory moved, so `rootDir` must be set) IS
  NOT REPORTED (2026-08-30, measured by (CFG.1) against tsgo 7.0.2 on an emitting build
  whose inputs straddle `src` and `dist`).** tsc emits it at the tsconfig; we emit
  nothing and silently pick a different output layout, which is a WRONG EMIT rather than
  a missing warning — the outputs land somewhere the user did not ask for. Note the
  neighbour already modelled: TS5055 ("would overwrite input file") has a filter in
  `writeOutputs`, so the emit-path machinery for this family exists.

- [x] **(CFG.1) LANDED 2026-08-30 — tsc's rule for an ABSENT `exclude` is
  `[outDir, declarationDir]` (`commandLineParser.ts`), and the package folders are pruned
  separately by the wildcard matcher, which our `walk` already does. We had only the
  redundant half. Measured against tsgo 7.0.2: its program is 1 file where ours was 2, so
  any project that has ever run a declaration-emitting build read its own `dist` back in
  as ROOT FILES and re-checked the emitted tree on every keystroke. The corpus
  materialises no directory and all eight profiles scope `include` to `src`, so the grid
  is a CONTROL here (`added=0 removed=0`) — a `-project` fixture is the only instrument.
  The diagnostic half is REAL IN tsc (TS2451 x2, TS5011) and unobservable here, filed as
  (CHK.74)/(CFG.2), and the value pin written for it was deleted for passing on a broken
  binary. ORIGINAL ENTRY:** A tsconfig WITHOUT `exclude` MUST EXCLUDE `outDir` AND `declarationDir`,
  AND WE EXCLUDE NEITHER — SO A PROJECT THAT HAS EVER EMITTED PULLS ITS OWN `dist/**/*.d.ts`
  BACK IN AS ROOT FILES (2026-08-30, (INC.60)'s successor, read out of tsc's own
  `commandLineParser.ts:3131-3141`).** There, `excludeOfRaw === "no-prop"` sets
  `excludeSpecs = filter([outDir, declarationDir], d => !!d)`; the package folders
  (`node_modules`/`bower_components`/`jspm_packages`) are pruned SEPARATELY by the
  wildcard matcher, which is what our `walk`'s own `pruned` set already does — so
  `TsConfigLoader.defaultExclude`'s three package folders are the half we have and the
  outDir half is missing entirely. **Two costs, and the first is a correctness one**:
  duplicate-identifier diagnostics tsc does not report, and the whole emitted tree
  crawled, read, parsed, bound and checked on every keystroke ((INC.60) measured the
  glob alone at ~3.4 us per entry, and every one of those entries then costs a read and
  a parse as well). **NOTHING HERE CAN SEE IT**: the corpus harness materialises no
  directory at all, and the eight dashboard profiles are never emitted into — so the
  instrument is a `-project` fixture through `ProjectCompiler` + a `Vfs`, with a
  `dist/` holding a `.d.ts` whose declarations collide with `src/`'s. Mind the
  interaction with `rootDir`/`composite` before widening it beyond the two specs tsc
  names, and note that an EXPLICIT `exclude` replaces the default in tsc — it is not
  additive.

- [x] **(INC.60) LANDED 2026-08-30 — the row is 99% the root-file glob, the glob is 99%
  its directory walk, and 60-70% of THAT was `vfs.isDirectory` asked once per entry the
  listing had just returned. kotlinx-io answers that one boolean with up to FIVE `stat`
  syscalls (`metadataOrNull` = `exists` + `isFile` + `isDirectory` + `isFile` + `length`,
  read out of the jar), i.e. 7.3-8.6 us per entry. `Vfs.listEntries` answers the kind WITH
  the listing — its default body is exactly the two calls it replaces, so no other `Vfs`
  changes — and `SystemVfs` overrides it via a new `expect fun systemListEntries` whose
  JVM actual is one `readdir` plus one `stat` per entry. MEASURED, both arms this session:
  `CONFIG` **29.2-32.6 -> 11.5-16.3 ms at 2,401 files** and **52.8/52.9 -> 20.7-27.1 at
  4,801**, per entry **9.3 -> 3.1-4.4 us**, with the population census identical across
  the change. tsconfig load (0.43 ms) and `@types` (0.01 ms) were never the row.
  ORIGINAL ENTRY:** THE `config load + @types + root glob` ROW — 29-45 ms OF A 279 ms FLOOR,
  THIRD-LARGEST, AND NOTHING HAS EVER EXAMINED IT ON THIS SHAPE.** (INC.59)'s successor,
  and it is deliberately ranked ABOVE (INC.56) despite being slightly smaller: it carries
  **no soundness promise at all**, where (INC.56) needs an opt-in host contract plus
  (INC.48)'s "a content hash cannot see an ADDED file" hazard in a second costume.
  `FrontEnd.CONFIG` covers tsconfig load, `@types` acquisition and the root-file glob
  walk, and on the many-small shape it reads 12.1-20.2 / 29.3-44.5 ms at 601 / 2401
  files, and **52.8 / 52.9 ms at 4,801 — two draws 0.2% APART**, which makes it the one
  floor row here measurable without fighting the +-40% single-draw noise band. Its ~1.4x
  for 2x the files says a FIXED cost dominates it, and **a fixed cost on the floor is paid
  on every keystroke** — (INC.53)'s own argument for why ~20 ms of property initializers
  mattered at 0.4% of a full compile.
  **The instrument is the one this session used three times**: two program sizes, the row
  divided by file count. **Do not assume it is the glob** — (INC.53) and (INC.59) were
  both found by splitting a row that a plausible story had already explained.

- [x] **(INC.76) DONE 2026-08-31 — the language service was paying (INC.60)'s defect in full.**
  `OverlayVfs` did not override `Vfs.listEntries`, so every `Project` build got the interface
  DEFAULT body back — `list(path).map { VfsEntry(it, isDirectory(it)) }` — and that
  `isDirectory` is kotlinx-io's `metadataOrNull`, up to FIVE `stat`s per entry. Standalone
  over the build's own 50 directories / 2,451 entries: **6.34 ms taking the kinds from the
  delegate's listing against 19.54 ms asking per entry**, and 19.5 is what the build's row
  read. Landed: `vfs.listEntries + sort` **20.70 -> 9.73 ms**, the glob row **28.14 -> 18.44**,
  the per-keystroke query **153/145 -> 123/125 ms** trusted and **156/162 -> 140/138**
  untrusted — it costs NO promise, so both arms gain. Pins are a DIFFERENTIAL against the
  default body (a wrong kind drops a file from the program and (CFG.1) says nothing notices);
  the cost pin had to be a complexity claim at two program sizes, because a build legitimately
  asks `isDirectory` about specific PATHS. `CountingVfs` had the same omission and is fixed
  with it. **TRANSFERABLE: a defaulted interface member added for speed is a silent regression
  waiting for the next wrapper**, and the instrument that finds it is a row measured
  STANDALONE against the same row measured IN THE BUILD.

- [x] **(INC.81)(a) DONE 2026-08-31 — the index's per-key list is gone (4.60 -> 3.17 ms,
  rotated, 3/3 batches), the round-471 hash hypothesis is MEASURED AND REFUTED at 14% of the
  row, and the promotion path no real project reaches is now pinned by a byte-identical-twin
  fixture. The residue is refused with reasons: the walk IS the index's definition and the hash
  cannot move without changing a key whose structural semantics the replaced scan fixes.**
  **(b) IS STILL OPEN** — the crawl's ~9 ms concurrent residue.

- [ ] **(INC.89)(d) ONE ARM, NOT A ROUND — price a `spans + signatureSpans` widening, the
  only caret-channel variant (INC.33) never built (2026-09-01, (INC.89)).** **THIS IS A LEAD,
  NOT A FINDING, AND IT IS LABELLED SO BECAUSE IT IS A RESIDUAL OBTAINED BY SUBTRACTION** —
  this repo forbids reading one as a measurement. In (INC.33)'s own re-run table the SIGNATURE
  channel is nearly free in both currencies: `sigs.file - base` is **+83 ms for 18,594 anchors
  on `checker.ts`** (against `spans.file - base` = **+1,310**) and inside the noise floor on
  `binder.ts`, with **9,520** retained sig items against the 265,688 types+defs that entry
  already holds (**+3.6%**) — nothing like the scope channel's O(anchors x globals) blow-up
  that refused the others. (INC.33) measured that arm but only ever offered
  `spansMembers.file` as "the cheapest shippable", so this combination has never been priced.
  IF additive it is +83 ms on a 3,499 ms hover to save a 2,002 ms signature-help build,
  break-even ~0.04.
  **DO IT AS ONE ARM IN `Inc33WidenMain` (`spansSigs.file`), NOT AS A ROUND** — additivity is
  the assumption under test and it is the only thing being asked. **And know the cap before
  spending anything on it**: signature help is re-triggered by `(` and by EVERY `,`, which are
  edits, and (INC.89) established that an edit must reach `updateFile` or the anchor is stale,
  and `updateFile` does `captures.clear(); prepared = null`. So even a perfect result serves
  only the re-open-without-edit slice. If it measures non-additive, record it and CLOSE the
  caret-channel direction entirely.

- [ ] **(INC.89)(c) `checkSpreadNonIterableIntoFixedArity` (2.01-2.22 ms) AND
  `checkReverseMappedInferableArrows` (0.66 ms) — THE THROWAWAY-COLLECTION SHAPE AGAIN, AND
  NEITHER CARRIES A REFUSAL OF ITS INTERIOR (2026-09-01, (INC.89)).** After (INC.89) re-derived
  the init block's head, these are the only rows above ~0.6 ms with no recorded refusal of the
  work itself. `checkSpreadNonIterableIntoFixedArity` (`Checker.kt` ~66270, registered ~10877)
  allocates THREE `HashSet`s per program file, then `uninitVars.intersect(tildeAssigned)` as a
  fourth, then walks the file — and discards all of it at an early exit, for every file
  declaring none of what it looks for. `checkReverseMappedInferableArrows` (~179524) makes
  THREE separate full statement scans of the SAME list (~179529, ~179537, ~179550) before its
  first early exit at ~179549, plus 3-5 `mutableSetOf`s per file, and is gated
  `if (!options.strict) return` so its whole population is a function of the fixture's `strict`.
  **WHY THE RECORDED REFUSAL DOES NOT COVER THIS.** Both are refused by (INC.7) batch 4's
  reading, whose criterion is the LOOP HEADER — "53 write a checker field or retract inside the
  private closure… the loop header is exhausted". That is a statement about narrowing the loop,
  and it is correct; it says nothing about the per-file allocations INSIDE it. (INC.89) found
  that same header-vs-interior confusion three times in one round.
  **PRICE IT BEFORE BUILDING IT, AND THE FLOOR IS KNOWN.** Round 801: an allocation count is
  not a cost, and round 912's bar is >113,000 allocations/rebuild at a generous 150 ns to clear
  the ~17 ms floor — here the population is ~4 x 2,401 ≈ 9,600 per pass, i.e. **~0.5-1.4 ms**,
  right at the floor and possibly under it. So the honest first step is a deterministic COUNT
  of collections built and of files reaching the early exit, not a rewrite.
  **INSTRUMENT: (INC.80)'s two class dirs + an ABBA rotation across processes** — this is
  exactly why (INC.87)(b)'s one-loop rewrite was BUILT AND REVERTED (the pass-timing table moved
  +51% in the same run that showed the row falling). Do not land either off a single draw, and
  note that like `evolvingArrayUseSiteWalks` these emit into the corpus, so unlike it they DO
  have a value gate.

- [ ] **(INC.87)(b) `init:evolvingArrayUseSiteWalks` — THE ONE UNREFUSED WHOLE-PROGRAM
  `init:*` PASS, 1.835 ms, AND IT NEEDS AN INSTRUMENT BEFORE IT NEEDS A FIX (2026-09-01).**
  Per file it builds FIVE throwaway collections (`filterIsInstance` -> `flatMap` -> `filter`
  -> `mapNotNull` -> `toSet`) and discards all five for every file declaring no top-level
  `x = []`, which is nearly all of them. **A one-loop rewrite was BUILT AND REVERTED in
  (INC.87)(a)**, not because it was wrong but because it could be neither priced nor pinned:
  the pass-timing table moved **+51% in the same run** that showed it 1.835 -> 1.602, and the
  pass EMITS NOTHING (it only sets `flowDepthTripped`), so no local value pin can see it and
  the corpus is the only value gate. Round 801's law bounds the prize: ~12,000 allocations is
  ~0.6-1.8 ms at 50-150 ns, i.e. right at the floor. **What it needs is two class dirs and an
  ABBA rotation across processes** ((INC.80)'s instrument), or a deterministic count of
  collections built. Do not re-land it off a single draw.

- [x] **(INC.86) DONE 2026-09-01 — BOTH CANDIDATES ANSWERED.** (a) LANDED as (INC.87)(a):
  `POST_DIAGS` **4.507 -> 0.508 ms**, its TS2688 member 3.296 -> 0.492 and the
  `modulePreserve4` scan gone entirely (`calls` 1 -> 0). The split refuted the row's own
  shape — the whole-program TEXT scan is the SMALLER member; the larger was an
  alternation-rooted regex over every file NAME. (b) ANSWERED by taking the distribution the
  entry asked for: 418 rows, `rowsTo50pct=5`, `rowsTo90pct=22`, tail of 363 rows worth
  **1.03 ms between them** — no plateau, and the five rows carrying half of it are each
  already refused with a price. `Inc56TrustedFloorMain` now prints `PTALL`/`PTSHAPE`/
  `PTBUCKET` instead of a top-15, per (INC.69).
  ORIGINAL ENTRY BELOW.

- [ ] **(INC.86) THE PER-KEYSTROKE QUERY RE-DECOMPOSED AFTER (INC.82)/(INC.85) — 90 ms, AND
  THE FRONT END IS NO LONGER WHERE THE QUERY IS (2026-09-01, trusted arm, 2,401-file `dom`
  fixture, 8 rotations x 6 draws).** (INC.57)'s law: re-take the ranking rather than inherit
  one. **WALL median 90 ms** [79, 82, 83, 84, 90, 91, 95, 104]. Rows:
  **checker construct + getDiagnostics 45.86 (51%)** — of which the **init-block pass dispatch
  42.93** and `getDiagnostics()` itself **0.011**; **config load + @types + root glob 10.91**;
  **crawl WALL 10.74** (tight: [10.44, 10.86]), of which sequential resolve **4.34** and the
  two CPU sums 1.06 + 0.98; **bind 7.08**; **post-checker 6.43**.
  **THE FRONT-END ARC HAS DONE ITS WORK AND THE RANKING SAYS SO.** Crawl and glob together are
  now 21.6 ms against the init block's 42.9 — where (INC.77) opened with the crawl at 19.6 and
  the glob at 15.3. Do not open another crawl row without re-reading (INC.83): its three named
  members are refused with prices, and what remained after (INC.84)/(INC.85) is a pipeline
  entered for ONE file per keystroke.
  **THE TWO CANDIDATES, in order:**
  **(a) `post-check diagnostic filters` 4.22 ms of the 6.43 post-checker row — a row NO QUEUE
  ITEM HAS EVER NAMED**, which is the (INC.57)/(INC.65)/(INC.81) pattern for the fourth time:
  the finding is in a sub-row that only appears once you print the distribution. It runs in the
  `--noEmit` path, so (INC.59)'s question applies — ask FIRST whether a per-keystroke query
  needs it at all, before making it cheaper. Its neighbours are named and small
  (`collectCrossFileNamespaceExports` 0.85, `topologicalSort` 0.30, output assembly 0.53).
  **(b) the init block's 42.93 ms**, which is 48% of the query and is where everything left
  is. Read (INC.77) BEFORE touching it: its largest row (`init:buildFileLocalTypeMaps`, ~12-15
  ms) is REFUSED as the build's first real type resolution — deferring it MOVES the cost — and
  the ~10 ms whole-program-walker pool it names is really ~1.2 ms once the round-609 collector
  classification is read per walker. So the open part is neither of those and needs a
  distribution, not a top-N list ((INC.69)'s plateau lesson).
  **INSTRUMENT:** `Inc56TrustedFloorMain <dir> trust 8 6` prints WALL, the FEROW rows and the
  PTROW pass rows in the QUERY regime — and read a pass table only against a block from the
  SAME regime ((INC.77)).

- [x] **(INC.85) DONE 2026-09-01 — A WAVE THAT CANNOT BLOCK IS DRAINED WITHOUT THE 16-WAY
  MERGE.** `readAndScanBatch` classifies per path on the caller's thread (resident content
  AND a content-cache hit -> built directly; anything else -> the old pipeline verbatim), both
  halves feeding the unchanged single-threaded fold. **Batch WALL 8.48/9.82/12.28 -> 5.84/
  5.32/4.73 ms, pipeline 6.63/8.13/9.61 -> 0.81/0.80/0.67**, deterministic receipt **2400
  resident / 1 piped** warm-trusted against **0 / 2401** cold and untrusting. No wall figure
  quoted — the control arm's own deltas flip sign across rotated batches ((INC.72)).
  **THE COST IS GATED OFF BY DEFAULT:** `Vfs.hasResidentContent()` defaults `false` and is
  asked once per wave, so `SystemVfs` — the whole shipped CLI/daemon — probes nothing;
  `OverlayVfs` answers from `retained` alone and NOT from overlaid buffers, because `contents`
  is O(open editors) against a wave's O(program) and can never pay at any project size (a
  structural fact, not a threshold — it took the last regime to **99-111 ns**). Eight ablation
  arms; **a5 was DEAD on its first pass** and only became discriminating once a fixture with
  an unsaved buffer over a stale cached tree existed — without it the change could have
  shipped serving the previous keystroke's tree, invisibly.

- [x] **(INC.84) DONE 2026-08-31 — THE CONCURRENT HALF IS NAMED, AND ITS 16-WAY MERGE RUNS AT
  0.6x PARALLELISM.** Six `FrontEnd` rows (`CRAWL_BATCH` / `CRAWL_PIPE` / `CRAWL_FOLD` /
  `CRAWL_INDEX` / `CRAWL_DRAIN` / `CRAWL_MKFILE`), each declaring WALL vs CPU-SUM in its KDoc.
  Batch WALL **5.90-7.64 ms**, of which the pipeline **4.83-6.44**, fold 0.59-0.69, re-index
  0.17-0.28; drain 0.27-0.39; **residue 0.17-0.37**. `CRAWL_MKFILE` (0.89-1.20 ms CPU sum,
  ~400 ns/file) is the row no span could reach — the construction runs after `PREPARSE` closes
  and before `emit` — and its nanos ride the element into the single-threaded fold (round 825).
  **THE VERDICT IS A RATIO, NOT A SUBTRACTION** (16 overlapping workers make the subtraction
  meaningless, and the KDoc says so): worker CPU **3.71/2.89/3.49** against walls
  **6.44/4.83/5.79** = **0.58/0.60/0.60x**, three draws. The same pipeline on the UNTRUSTING
  arm runs at **7.5-8.9x**, which is the control that makes it attributable: the merge earns
  its keep exactly while reads block, and on the shipped arm nothing blocks.
  **SUCCESSOR (OPEN):** an adaptive drain for an all-cache-hit wave, bounded at ~2-3.5 ms of a
  ~110 ms keystroke; `CrawlParseCache.store`/`retainRead` stay single-threaded whatever it
  does, and order is restored by `paths.map { indexed.getValue(it) }` rather than by the flow.

- [x] **(INC.83) DONE 2026-08-31 — THE CRAWL'S CONCURRENT RESIDUE: ALL THREE NAMED MEMBERS
  REFUSED WITH THEIR PRICES.** `moduleSpecifiers.toSet()` + `associateBy` is **~0.8 ms and
  that is a CEILING** (no in-build number exists without a new probe boundary);
  `CrawlParseCache`'s whole-content compare is **~0 ms on the arm that ships**, because
  (INC.56)'s `retainRead` hands back the same `String` instance and `String.equals` takes its
  identity path — its 1.6 ms is a cost of NOT trusting the host, i.e. the opposite of a lever;
  and the per-call `Regex` is **provably never constructed** (the option test short-circuits
  it for every ES module target, and even under `commonjs` it needs a file containing
  `await`, of which the fixture has 0 of 2,401). Ceiling for all three together ~2.4 ms of a
  93 ms query. **THE FINDING IS THE RESIDUE:** crawl WALL 12.2-15.5 ms, sequential resolve
  3.35-5.81, so the concurrent half is 8-11 — and the two instrumented rows account for only
  ~2.8 of it. **~6-8 ms is unattributed** (the `flatMapMerge` machinery, the `CrawledFile`
  construction — which runs at `emit`, AFTER the `pre-parse` span closes, so no row can see
  it — the flow collection and the single-threaded fold). (INC.65)'s law a fourth time.
  `Inc82CandidatePriceMain` is the probe.

- [x] **(INC.82) DONE 2026-08-31 — THE IMPORTER'S DIRECTORY WAS RE-DERIVED PER SPECIFIER,
  AND THE ISOLATED PROBE OVER-READ ITS OWN PRIZE BY 3x.** `resolve` read `importerPath` for
  nothing but its `dirname` and then joined it with the specifier into a fresh `String` it
  probed TWICE; the crawl knows that directory once per FILE and asked once per SPECIFIER
  (4,701 over 2,401 files). `resolveFrom(specifier, importerDir)` is now the entry point,
  the memo is nested (`dir -> spec`) so the outer probe hashes a cached-hash instance and
  the inner one only the short specifier, a memoized `null` is an identity sentinel (one
  probe, not two), and the crawl hoists both the `dirname` and the per-file resolution map —
  the map staying LAZY so a file whose every import is unresolved still contributes no entry.
  **The probe said 1.27 ms (`dirnameOnly` 96 ns + `keyOnly` 174 of 1,314 per specifier); the
  BUILD says ~0.15-0.44** (`FERESOLVE` 4771/5143/4102 -> 4677/4707/3954, after winning 3/3
  batches in both rotation directions, ranges overlapping). **An isolated per-operation probe
  prices an UPPER BOUND on a removal, never the removal.** The receipt is therefore the
  deterministic count and it is exact: `pathNormalizeCalls` **9577 -> 7277**, i.e. exactly
  `4,701 - 2,401`, with glob / join / resolution-question censuses identical across the arms.
  The floor WALL moved 103 -> 93 ms 3/3 and is NOT claimed — (INC.72)'s +-20 ms concurrent
  term is ten times the effect. Three ablation arms, three distinct red sets.
  **STILL OPEN in this row:** `arithOnly` is 2.156 ms with `join`/`normalize`/`extname`/
  `isBare` in it.

- [ ] **(INC.81) THE PER-KEYSTROKE QUERY RE-DECOMPOSED AFTER (INC.78)/(INC.79)/(INC.80) —
  87 ms, AND THE RANKING CHANGED AGAIN (2026-08-31, trusted arm, 2,401-file `dom` fixture,
  8 rotated draws).** (INC.57)'s law: re-decompose after every round that moves the floor
  rather than inheriting an ordering. **WALL median 87 ms** [81, 83, 84, 84, 87, 90, 111, 130]
  against (INC.77)'s 118-125. Rows: **init-block pass dispatch 40.8** (47%, and 12.5 of it is
  `init:buildFileLocalTypeMaps` — refused by (INC.77) as the build's first real type
  resolution, do not re-open it from its size); **crawl WALL 14.2**, of which sequential
  resolve is now **4.5-5.7** and the rest (~9 ms) is the concurrent half with NO IO and NO
  parse left in it; **config+glob 9.2** (root glob 8.6, of which `listEntries` 7.3, ~4.4 ms of
  it irreducible `stat`s per (INC.77)); **bind 7.1**; **post-checker 6.2**.
  **THE TWO NAMED CANDIDATES, in order:**
  **(a) `enclosingImportIndex` (field) 4.7 ms** — a row no queue item has ever named. It is
  already `by lazy`, so this is what it COSTS when asked, not an eager waste: a program-wide
  `Map<ImportSpecifier, List<Pair<String, ImportDeclaration>>>` over ~9,400 specifiers,
  i.e. ~500 ns per entry. **Read round 471 first**: the key is an AST *data class*, so every
  `getOrPut` hashes structurally — and the KDoc says the structural keying is LOAD-BEARING
  (`mergeSymbolTable` hands `resolveAlias` a different instance), so the key cannot simply
  become identity. Measure the split (hash vs put vs the walk) before designing, and check
  whether a NARROWED query needs the whole-program index at all — the entries list is in
  program encounter order and first-match semantics are byte-pinned, which is what makes a
  per-file index a semantics change rather than a refactor.
  **(b) the crawl's ~9 ms concurrent residue** — (INC.64)'s question with BOTH hops now gone
  ((INC.64) removed the parse hop for a cache hit, (INC.56) the read hop for resident
  content). What is left per file is the `flatMapMerge` machinery itself, `computeParserFlags`
  and `CrawlParseCache.lookup`. **Two things already known**: `fileLooksLikeModuleForAwait` is
  short-circuited for an ES module target and would be a whole-content scan per file for a
  `commonjs` project (so this row is a function of the fixture's `module` setting, (INC.61)'s
  law on a third axis); and the cache lookup is keyed by CONTENT, so its hash is O(bytes) and
  irreducible. (INC.64) measured the machinery alone at 17.2 ms against a 14.4 ms sequential
  read at this file count — i.e. ~2.8 ms of it is the flow, and the rest is unattributed.
  **INSTRUMENT for both:** `Inc56TrustedFloorMain <dir> trust 8 6` prints the WALL, the FEROW
  rows and the PTROW pass rows in the QUERY regime — read a pass table from the same regime as
  the block you compare it to ((INC.77)).

- [x] **(INC.80) DONE 2026-08-31 — `PathUtil.join` BY ARITHMETIC, AND A TWO-DRAW READ THAT
  NEARLY REFUTED IT.** A module specifier's `..` is exactly what `isNormalized` must refuse, so
  (INC.68)'s fast path never applied and every join allocated a split list, a deque and a
  builder: **731-880 ns x 4,701**. Counting the leading `..` and dropping that many segments off
  the base is **131-136 ns**, priced as a probe arm and checked against the general body on all
  4,701 real pairs before being built. **Two draws of the row saw nothing; six rotated across
  processes saw 6.41 -> 4.95 ms, 3/3 batches, both directions.** The GC explanation was measured
  and REFUTED (a 2 GB young gen made both arms slower). Receipt: a floor build now performs ZERO
  allocating normalizations, down from 2,358. **STILL OPEN in the row:** `dirname` + the memo key
  at ~1.5 ms, which a `resolveFrom(spec, dir)` overload plus a two-level memo would remove.

- [x] **(INC.79) DONE 2026-08-31 — THE CRAWL'S RESOLVE ROW, AND A REFUSAL THAT WAS RIGHT ABOUT
  ITS COMPONENT AND WRONG ABOUT THE PROGRAM.** (INC.73)(a) called the row's syscall half
  irreducible: one `exists` per distinct resolution. The ROOT-FILE GLOB had already proved
  those files exist, in the same build, off the same `Vfs`. Seeding `ModuleResolver`'s
  per-build probe memo from it takes the build to **2,351 questions / 0 syscalls** and the row
  **10.2-12.0 -> 5.8-6.5 ms**. The seed is POSITIVE-ONLY (a file can exist and be excluded),
  and the ablation that read the seed as an authoritative file list reddens 4 pins.
  **STILL OPEN in the same row:** `PathUtil.join`/`normalize` at ~810 ns x 4,701 (3.7-3.9 ms)
  and `dirname` + the memo key at ~1.5 ms, which the crawl loop could hoist per FILE.

- [x] **(INC.78) DONE 2026-08-31 — THE ROOT-FILE GLOB'S MATCH ROW, AND THE REFUSAL FILTER THAT
  WOULD HAVE BOUGHT ~0.3 ms OF ~5.** (INC.77) ranked this row at ~5-6.8 ms and proposed a
  cheap prefix/extension pre-filter. Measured, the EXCLUDE half was already 191 ns/candidate
  (its literal prefix fails on the first character) and the INCLUDE half was **2,239** — an
  ACCEPTING regex, which no refusal can short-circuit. `GlobMatcher` answers the
  `<literal>` + `**` segment + bare `*` leaf + literal tail shape from the head and the tail,
  keeping the regex as the oracle. **`FrontEnd.globRegexEvals` 4,802 -> 0** on the 2,401-file
  fixture; `CFG_MATCH` **4.66 -> 0.61 ms** in the build. The receipt is the COUNT because the
  same one-process wall ratio read 12x / 5x / 3x across four processes. Four ablation arms,
  four distinct red sets, a4 partitioning the cost pin from the value pins.

- [ ] **(INC.77) WHAT IS LEFT IN THE PER-KEYSTROKE QUERY AFTER (INC.56)/(INC.76), MEASURED —
  AND THE LARGEST ROW IS REFUSED WITH ITS REASON (2026-08-31).**
  **THE 25 ms "UNATTRIBUTED BLOCK" I EXPECTED DOES NOT EXIST**: the passes account for
  **97%** of the init block (`initNanos` 43.1 vs `passSum` 41.7 ms over 418 rows). The ~22 ms
  pass table I had compared against came from a FLOOR build, where most passes are gated; a
  query that CHECKS a file runs more of them. **Read a pass table from the same REGIME as the
  block you are comparing it to** — (INC.9)'s law on a third axis.
  **AND THE LARGEST ROW IS NOT A LEVER.** `init:buildFileLocalTypeMaps` is **14.9 ms, 34% of
  the init block and ~12% of the whole query** — and (INC.16)'s go/no-go counter says
  `eagerBuilds=1, lazyBuilds=0`: it builds exactly ONE file's map, the partition's. So the
  14.9 ms is the build's FIRST REAL TYPE RESOLUTION — on the `dom` fixture that is the lib
  being touched, which (INC.61) priced — attributed to this pass because it is the first
  asker. CLAUDE.md's "a memo hides its miss cost from every section probe": deferring it
  MOVES the cost, which (INC.10)/(INC.11) already measured from the other direction.
  **DO NOT RE-OPEN IT FROM ITS SIZE.** Trusted query ~123-125 ms on the 2,401-file `dom` fixture. Rows:
  **init-block pass dispatch 46.9 ms (still half of it)** — (INC.7)'s partition question one
  walker at a time, and the per-pass table is only ~22 ms of it, so most of that block is
  still unattributed; **crawl 18.9**, of which sequential specifier resolution ~11.1 (its
  syscall half refused by (INC.73)(a), the non-syscall remainder of 4,701 calls unexamined)
  and a ~6 ms concurrent residue that is now the `flatMapMerge` machinery itself with no IO
  left in it — (INC.64)'s question one hop later; **root-file glob 18.4**, of which
  `include/exclude regex match` is **6.8 ms over 2,401 candidates (2.8 us each)** — a
  compiled `Regex.matches` per include pattern per candidate, where the patterns are constant
  and most candidates could be refused by a cheaper prefix/extension test first; **bind 7.0**.
  Measure before building: (INC.76)'s standalone-vs-in-build comparison is the instrument that
  has now worked twice.
  **RE-MEASURED AFTER (INC.76), trusted arm, ~118-124 ms total:** init-block dispatch **37.5**
  (of which fltm 14.9, refused above; the rest is ~417 rows headed by the whole-program
  `check*` walkers (INC.74) lists — `checkSpreadNonIterableIntoFixedArity` 2.22,
  `checkCircularGenericCallbackVariables` 1.53, `checkCrossFileUseBeforeDeclaration` 1.50,
  `checkModulePreserve4Pin` 1.35, `populateAmbientCyclicBaseClasses` 0.98,
  `checkReverseMappedInferableArrows` 0.66 — **~10 ms between them, and that is now the
  largest coherent pool left**); crawl **19.6** (resolve 11.5, flow residue ~6); glob **15.3**
  (`listEntries` 8.0, regex match ~5); bind **8.0**; post ~6.
  **AND THE ~10 ms WALKER POOL IS REFUSED TOO, AFTER READING ALL SIX — IT IS ~1.2 ms.** The
  pass ROW is not the gateable part: **two of them are ALREADY partition-scoped and what is
  left in their row is the whole-program COLLECTOR** that round 609 forbids gating
  (`checkCircularGenericCallbackVariables` and `checkCircularClassBaseViaDefaultTypeArg` both
  collect over `binderResults` and emit over `checkedResults` — (INC.20)'s MIXED split,
  already applied); **one is pure collector**
  (`checkCircularExportEqualsImportAlias`, THREE `binderResults` loops feeding a cycle
  analysis, no per-file emission at all); and **three were already refused by earlier rounds**
  (`checkSpreadNonIterableIntoFixedArity` and `populateAmbientCyclicBaseClasses` by (INC.7)
  batch 4's reading, `checkModulePreserve4Pin` by (INC.70b)). What is genuinely open is
  `checkCrossFileUseBeforeDeclaration`'s SECOND loop (~0.5 ms) and
  `checkReverseMappedInferableArrows` (0.66) — **and the first carries a trap worth more than
  the milliseconds**: its `fileIdx` comes from `binderResults.withIndex()` and is compared
  against the indices the COLLECTOR recorded, so rewriting the header to
  `checkedResults.withIndex()` silently renumbers it and makes a use-before-declaration
  verdict a function of the PARTITION. **Read what an index MEANS before narrowing the loop
  that produces it.**
  **TWO OF THOSE ARE REFUSED ON ARITHMETIC RATHER THAN LEFT OPEN.** The glob's `listEntries`
  residue is **~4.4 ms of irreducible `stat`s** (2,451 entries, one each — Java exposes no
  `d_type`, so 1 syscall per entry is the floor; `GlobListProbeMain` measures the split), and
  `ModuleResolver.resolve`'s 11.5 ms is ~2,351 real resolutions at one `exists` each
  ((INC.73)(a)) plus per-call path arithmetic — its one visible allocation, a `setOf(…)` built
  per call in `resolveAsFile`, is ~0.35 ms at round 912's bar and does not clear it.
  **UPDATE 2026-08-31: THE `include/exclude regex match` ROW IS CLOSED BY (INC.78), AND THIS
  ENTRY'S PROPOSAL FOR IT WAS AIMED AT THE WRONG HALF** — "most candidates could be refused by
  a cheaper prefix/extension test first" is true of the EXCLUDE (already 191 ns) and false of
  the INCLUDE, which matches every candidate and cannot be refused at all. What was left after
  it: the walker pool's genuinely-open ~1.2 ms, the crawl's two halves, and the init block.

- [x] **(INC.75) HANDOFF — CLOSED 2026-09-01 by (INC.90)'s plugin re-read, which CONFIRMED
  the three findings and added a fourth (Windows, (API.9)); the plugin still uses none of the
  five shipped capabilities, and every remaining item is plugin-side. Its own text already said
  "what is left HERE is nothing but the docs, which landed" — leaving it unchecked made a
  handoff read as open work for a whole round. ORIGINAL: THE PLUGIN CAN TAKE (INC.56) AND
  (INC.55) TODAY, AND READING IT SAYS WHAT
  ELSE IS STALE (2026-08-31, from `xemantic/xtsc-intellij-plugin` @ HEAD — (INC.67)'s method,
  which found a defect the queue could not).** Three findings, none of them landable in THIS
  repo, all of them compiler-facing:
  **(a) `XtscSession` says in as many words that "the compiler has no way to revert an overlay
  back to disk", and therefore retains `BufferContent` for EVERY buffer it was ever handed,
  bounded only by evicting the whole session** — that is exactly `Project.reloadFile`, added by
  (INC.56). Wiring it makes the per-path eviction the session's KDoc says is impossible.
  **(b) `XtscSession.onCompilerThread` says "the compiler has no cancellation hook, so poll
  instead of blocking" and polls `future.get(50 ms)`, which abandons the ANSWER while the build
  runs to completion and the next pass queues behind it.** `Project.cancellation` has existed
  since (INC.55) and `docs/language-service.md` § 14 documents it. Note the rough edge a host
  hits: a cancelled build throws `CompilationCancelledError`, an **`Error`** by design, so an
  `executor.submit` wraps it in `ExecutionException` and the plugin's failure branch would log
  a warning per cancelled keystroke — a host must recognise it as "no answer", not a failure.
  **(c) `trustFilesystem` is SAFE for this plugin today and its one gap is named**: `invalidate`
  already closes whole sessions on any relevant external change, which is the promise kept by
  the bluntest available means. What it deliberately SKIPS is the gap — `irrelevant(event)`
  spares excluded/ignored roots, which is safe now only because the next dirty build re-reads
  those files, and is NOT safe under the promise. The wiring is `reloadFile(path)` on a skipped
  event rather than nothing. `docs/language-service.md` § 5a and the IntelliJ hosting section
  carry the recipe.
  **What is left HERE is nothing but the docs, which landed — this item is a HANDOFF**, kept
  open so the next reader of the plugin does not re-derive it.

- [x] **(INC.56) DONE 2026-08-31 — LANDED, AND ITS OWN PRICE WAS A LOCATION.** Two opt-in
  halves: `Project.trustFilesystem` (the host's promise, with `reloadFile` as the third way
  to report a change) and `Vfs.readTextIfResident`/`retainRead` (the crawl skips its per-file
  THREAD HANDOFF for content already in memory). Crawl WALL **30.6/37.0 -> 21.7/19.4 ms** at
  2,401 small files and **13.7/14.2 -> 9.5/7.8** on tsc's 78 huge ones, `read+decode`
  132.6/176.1 -> 1.52/1.39 and 65.4/63.2 -> 0.076/0.057, sequential resolve flat as the
  control. **THE ENTRY'S OWN PRICE WAS WRONG AND THE ROUND SAYS WHY**: `FrontEnd.READ` is
  elapsed-WITH-SUSPENSION, so retaining the content without skipping the hop served 33,350
  reads from memory and moved the wall by NOTHING on the application shape while halving it on
  the byte-heavy one. Additions and deletions never needed a promise (nothing caches the file
  SET). 18 pins, 4 of 5 ablation arms discriminating, a4 recorded as a redundant guard.
  ORIGINAL ENTRY: **LET AN IntelliJ-CLASS HOST SKIP THE RE-READ — ~~THE LARGEST REMAINING
  FRONT-END ROW~~ FOURTH AT THE TIME, AND *FIRST* AGAIN NOW THAT (INC.57)/(INC.58)/(INC.59)
  HAVE CLEARED THE THREE ROWS ABOVE IT — BUT IT IS STILL THE ONLY ONE THAT COSTS A
  SOUNDNESS PROMISE.** Re-measured after (INC.59): crawl WALL is **62-66 ms of a 279 ms
  floor** at 2,401 files, now the largest single row. Its entry's claim is therefore true
  again — but it was false when written and became true only because three other rounds
  landed, which is why (INC.60) above (no promise, 29-45 ms) is ranked first.
  **PRICE RE-TAKEN 2026-08-30 BY (INC.57), ON THE VERY SHAPE THIS ENTRY DEMANDED.** On a
  1,201-file application-shaped project the floor rows are: checker construct **215-218
  ms** (53%), `extractRelativeImports` 76-125 (since fixed — (INC.57)), post-checker
  39-56, **crawl WALL 25-38 (6-9%)**, config+glob 12-20. At 2,401 files the crawl wall is
  51-75 ms. So the saving is real but is fourth, behind two rows that cost no promise at
  all — and (INC.58) above is now ~73% of that floor. **Work it after (INC.58); the
  ordering, not the mechanism, is what changed.** Everything below is the original entry
  and its hazards still stand.
  ORIGINAL ENTRY: Every query re-reads and re-decodes
  every NON-OVERLAID file: **10-12 ms wall and 44-56 ms of CPU** across the crawl's workers
  for tsc's 78 sources, although the PARSE is already fully content-cached (`78 reused /
  0 fresh`) — the bytes are read only to compute the content key. It is O(PROJECT), not
  O(edit), so it is what a monorepo feels on every keystroke, and it is the largest
  front-end row left after (INC.53).
  **WHY THE OBJECTION IS WEAKER THAN IT LOOKS UNDER THE PLATFORM.** The hedge recorded in
  (INC.54)(b) was that skipping a read is a soundness change: a file changed on disk without
  `updateFile` would be missed. On the IntelliJ platform the IDE's VFS is AUTHORITATIVE and
  guarantees change notification, so a host CAN promise it — which makes this an opt-in
  `Project` policy rather than a compiler default. It must stay opt-in: a `Vfs` whose
  backing store changes underneath is exactly what the promise excludes.
  **WHAT TO MEASURE FIRST, per this repo's own law.** The 44-56 ms is CPU across parallel
  crawl workers and only **10-12 ms of WALL** — so the prize is the wall figure, not the CPU
  one, and it must be re-taken on a project with MANY SMALL files rather than tsc's 78 huge
  ones, because that is the shape an application project has and the per-file overhead is
  what would dominate there. `scripts/floor-decomposition.sh` prints both.
  **AND IT INTERACTS WITH (INC.48)**: a content hash cannot see an ADDED file, which is why
  a restored snapshot is untrusted until one build has re-crawled. A "trust the host" mode
  inherits that hazard in a second costume — the host must also promise to report ADDITIONS,
  and the pin is a file added behind the promise being MISSED (i.e. the pin asserts the
  documented limit, not that it magically works).

- [ ] **(INC.54) THE FLOOR AFTER (INC.53), IN PRIORITY ORDER — AND THE FIRST ONE IS THE
  SAME QUESTION ONE LAYER UP.** (INC.53) took the `Checker` constructor's ~494 property
  initializers from ~20 ms to ~10 by moving three whole-program indices onto first ask, and
  refused the fourth with its price. What is left of a 63-72 ms floor, measured
  2026-08-29 (`scripts/floor-decomposition.sh`, and read as a mean of two draws because a
  single row on this floor swings ~40%):
  **(a) THE PASS TABLE, ~19-24 ms — the largest block now.** Top rows:
  `init:computeAllEnumValues` **6.9 ms** (already optimised once by (INC.52) and still
  #1), `init:moduleTypeNameIndex` 2.6, `checkModulePreserve4Pin` 1.7 (a known (INC.21)
  straggler — a whole-program `.contains` ABOVE the loop, so gating the loop banks ~0.02
  and only a NAME PRE-GATE banks the ms), `init:computePerFileVisibility` 1.4,
  `checkJsxImportResolutions` 1.2, `init:buildPerFileScopes` 1.0. These are `pass("…")`
  bodies, so unlike (INC.53)'s they ARE visible to `--passTiming` — the open question is
  whether an `init:` pass that builds a program-wide TABLE can be built on FIRST ASK the
  way a field initializer could.
  **FOUR THINGS ABOUT THE TOP ROW, READ OUT OF THE SOURCE BY (INC.53) SO THE NEXT ROUND DOES
  NOT RE-DERIVE THEM — AND THE SECOND REFUTES THE OBVIOUS PLAN.**
  (i) Wiring a force is CHEAP: `enumValues` already has a SINGLE accessor funnel
  (`Checker.kt:440`, round 904's (WARM.31) getter — "EVERY `enumValues[...]` expression
  evaluates this accessor exactly once"), so all ~25 read sites are served by one hook.
  (ii) **But DEFERRAL ALONE BUYS ONLY THE FLOOR, because the table is ALL-OR-NOTHING**:
  `computeAllEnumValues` fills every enum in the program in one sweep, so the first asker
  pays the whole 6.9 ms — and on tsc's own sources, where `SyntaxKind` is everywhere,
  essentially every real query asks. That is materially weaker than (INC.53)'s case, whose
  win came from `localTypeAliasIndex` becoming PARTIAL (per-file), not merely deferred.
  (iii) **So the lever is PER-ENUM laziness, and it is structurally available**:
  `computeEnumSymbolValues(symbol)` is already per-symbol, id-keyed and idempotent
  (`if (enumValues.containsKey(symbol.id)) return`), so a keyed `enumValuesOf(id)` could
  force one enum. The cost is that the ~25 read sites index the returned MAP, so each would
  have to name its id — a wide change, which is why it is a round and not a patch. And the
  pass is **MIXED** in exactly (INC.20)'s sense: its `blockScoped` / `blockScopedAliases`
  census (feeding `lexicalTypeSymbolForNode` and `lexicalTypeAliasArity`) is program-wide
  and must STAY eager, so only the value half moves.
  (iv) **THE HAZARD IS ORDERING AND IT IS SILENT.** The pass runs at init step 2, AFTER
  `init:mergeFileLocalsIntoGlobals`; forced lazily it runs wherever the first read is, and a
  read from any pass BEFORE step 2 would compute enum values against un-merged globals —
  wrong values, no diagnostic, and they feed `Transformer.transformEnum`, i.e. EMITTED BYTES.
  The instrument exists in the shape (INC.16) used: record `PassTiming.currentPass` at the
  first force (`LexDefer.forcedBy`) and assert on the compiler profile that it is never one
  of the eleven pre-step-2 passes. The corpus is the real gate — enum coverage is heavy and
  emits `.js`, so a wrong value reddens loudly.
  **Check the read sites first**: (INC.53)'s three were affordable precisely because each had
  exactly ONE, and round 609 forbids gating a program-wide COLLECTOR onto the partition.
  **(b) THE CRAWL RE-READS AND RE-DECODES EVERY FILE ON EVERY QUERY — 10-12 ms wall,
  **44-56 ms of CPU** across the crawl's workers, for 9,977,097 chars — although the PARSE
  is already fully content-cached (`78 reused / 0 fresh`).** The bytes are read only to
  compute the content key. For a host that OWNS its VFS (an IDE) that is redundant, but
  skipping a read is a soundness change (a file changed on disk without `updateFile` would
  be missed) — so it is an opt-in `Project` policy, not a compiler default, and it is
  (INC.48)'s "a content hash cannot see an ADDED file" hazard in a second costume. Largest
  remaining FRONT-END row and it scales with project size, which is what an IntelliJ-sized
  project would feel.
  **(c) `parseBuiltinLib` — ~~REFUSED~~ **LANDED (INC.63) 2026-08-30, 46-50 -> 1.5-1.7 ms on
  a `dom` lib set, AND THE SPLIT BELOW IS WHAT KEPT IT SHUT FOR SEVEN ROUNDS.** The bind
  really is blocked by round 884 and really does measure 1.4 ms — 3% of the row. The other
  97% was `RealLibResolver.resolve` (called TWICE per construction, a regex over ~3.7 MB of
  lib text) and the decl-set walk (~30k puts into data-class-node-keyed containers); both
  are pure functions of the SHARED parses and neither is the blocked one. The recorded
  split mis-attributed the resolve because it sits INSIDE the `bindLibFiles` section.
  **A REFUSAL THAT NAMES A BLOCKER MUST CHECK THE BLOCKED HALF IS WHERE THE COST IS.**
  ORIGINAL: ~8-11 ms — REFUSED by (INC.53) with its split measured** (binds
  3.2-5.3, decl-set walk 1.9-2.8, resolution + 45 `mergeSymbolTable` 3.1-5.3) and BLOCKED
  on round 884's `mergedSymbols` clone-on-write: the checker merges into and mutates lib
  symbols, so neither the bind nor the merged table is shareable across checkers today.
  Do not re-open it before that lands, and do NOT re-open the data-class-keyed node sets —
  that hypothesis is measured wrong.

- [ ] **(INC.49) — NARROWED BY (INC.48): THE *RESTART* HALF IS CLOSED, AND WHAT IS LEFT IS
  THE FIRST-EVER OPEN.** With a snapshot restored, a cold process answers its first query in
  **155-175 ms** rather than 9.6 s, because the JIT ramp barely touches a path that never
  checks the whole program — so "cold start" is only the artifact-stack problem below for a
  project this host has NEVER seen. Re-take the cell with that split before spending an
  artifact decision on it. ORIGINAL ENTRY: COLD START IS THE LANGUAGE SERVICE'S WORST NUMBER
  BY FAR — 23,266 ms against tsgo's 1,631 ms, and it is an ARTIFACT-STACK problem rather
  than a compiler one.** Measured
  this round on tsc's own 78 sources: the first `diagnostics()` in a fresh JVM is **23.3 s**,
  the same build warm is **5,352 ms**, so **~18 s is JVM start plus the JIT ramp**. That is the
  first thing an integrator sees and it is 14x tsgo's whole cold check. **Nothing in the
  (INC.\*) arc can move it** — the levers are the ones already priced elsewhere and never
  pointed at this query: the GraalVM PGO image (**-21.2% check-only, and 1.93x FASTER than
  tsc 6.0.3**, `docs/perf/aot-native-image.md` § 10), the JDK 25 AOT cache (1.64x, and its
  fail-safe guard), and CRaC (a warmed checkpoint restoring in ~30 ms with the FIRST compile at
  full warm speed — refused as unshippable only because the restored process keeps the
  checkpoint's working directory, which `SystemVfs.workingDirectory` can now re-install).
  **Decide which artifact the embedding API ships on, then re-take this one cell.**

- [x] **(INC.50) MEASURED AND REFUSED BY ITS OWN THRESHOLD 2026-08-29 — LAYERED CODE IS
  **NOT** MATERIALLY ABOVE 67%: `cronstrue` reads **50%** and `marked` **72%**, bracketing
  tsc's 67%, and the higher arm carries a bias TOWARD stability (18 ours-only rows degrade
  some of its types to `any`).** The rate tracks what a codebase's commits TOUCH rather
  than how layered it is — cronstrue's edits are to the locale classes that ARE its
  surface. `scripts/inc50-stability-lib.sh` is the harness (any repo via LIB/REPO/TSCONFIG/
  PKGJSON) and it found (INC.51) in one run. **The per-hop closure stays refused**: the
  residual third are commits that genuinely move a signature, so only re-checking fewer
  DEPENDENTS can serve them, which is what (INC.35) measured at 100% of tsc's characters.
  ORIGINAL ENTRY, whose question is now answered: THE 67% IS NOT IMPROVABLE ON *THIS* CORPUS BY ANY MECHANISM, SO THE ONLY OPEN
  QUESTION IS WHETHER ORDINARY LAYERED CODE HAS A HIGHER RATE.** (INC.47) removed every
  escape and moved the rate by nothing, with all 40 verdicts identical — so the residual
  33% is 13 commits that each genuinely move an exported signature, and no fingerprint
  refinement can serve them. The `(LIB.*)` screened libraries (`knip`, `jsonrepair`,
  `cronstrue`) are the corpus, `scripts/inc46-stability.sh` is the instrument (it takes a
  corpus dir and a profile dir), and the deliverable is the RATE on code that is not one
  compiler's own sources. ORIGINAL ENTRY: IS THE CLOSURE WORTH BUILDING ON *LAYERED* CODE? tsgo IMPLEMENTS PER-HOP
  PRUNING AND IT BUYS THEM NOTHING HERE — 1,654 ms against a 1,631 ms COLD check.** That is an
  independent corroboration, from another implementation, of the measurement that closed
  (INC.35): on tsc's own sources a file-level AND a symbol-level use graph both re-check ~100%
  of the program at the median edit. **But it is a claim about ONE codebase, and theirs is the
  design that would pay if the claim does not generalise**: on a signature change they walk the
  reverse-reference graph and re-check a dependent only if ITS signature also moved, where we
  fall back to a whole-program build. **The (LIB.\*) screened libraries are the corpus that
  could decide it** (`knip`, `jsonrepair`, `cronstrue` — layered, unlike the dashboard
  profile). **Refuse it unless the measured stability rate on a layered corpus is materially
  above the 67% measured here**; the point of the item is the measurement, not the mechanism.

- [ ] **(BENCH.5) EVERY tsgo COMPARISON IS NON-LIKE-FOR-LIKE UNTIL THE 46-vs-65 DIAGNOSTIC GAP
  IS DECOMPOSED, AND THIS REPO ALREADY HAS THE LAW.** `kir-bench.sh` runs an equivalence gate
  BEFORE any timing, precisely because a wall-clock harness reads a program that does LESS as
  the fastest arm. `docs/perf/incremental-vs-tsgo.md` does not satisfy it: on the compiler
  profile we report **46** rows where tsgo 7.0.2 reports **65**, so every ratio in that page
  flatters us by an undecomposed margin. **The deliverable is the 19-row decomposition** — how
  many are genuine false negatives of ours, how many are tsgo-only divergences from pristine
  tsc (round 938's law: tsgo is NOT pristine, and `scripts/pristine_oracle.py` is the arbiter),
  and how many are a `lib`/options difference. Only then is a timing comparison between the two
  compilers quotable as a compiler comparison rather than as an architecture one.

- [x] **(INC.46) PROJECT-WIDE DIAGNOSTICS BY *EXPORTED-SIGNATURE STABILITY* — ALL THREE
  STEPS LANDED 2026-08-29. Cost 136 ms whole-program / ~0 ms per edit; stability **67%**
  over 40 real commits (floor; ceiling 87.5% once `types.ts`'s in-file SCC is hashed);
  `Project.diagnostics()` graded **EQUIVALENT 40/40 with served=27**. The successor is
  SCC-AWARE HASHING — Tarjan over the in-file type graph, hashing each strongly-connected
  component as a unit — which is the one lever between the measured floor and the ceiling,
  and it is the only thing standing between this and every edit being incremental.
  ORIGINAL ENTRY: PROJECT-WIDE DIAGNOSTICS BY *EXPORTED-SIGNATURE STABILITY*, NOT BY A
  DEPENDENCY CLOSURE — THE OWNER'S IDEA, AND IT DISSOLVES (INC.35)'s BLOCKER RATHER THAN
  WORKING AROUND IT.** Owner, 2026-08-29: *"if we do `import *` in a certain file and then
  recompile this file, don't we have the information of all the resolved imported symbols
  this file is using?"* We do — `capturedDefinitions` is span -> declaration location, a
  by-product of a build we already run. **But a symbol-level use graph was MEASURED THIS
  SESSION AND IT DOES NOT HELP**, and that refutes the queue's own standing explanation as
  well as the hypothesis:

  | graph over tsc's 78 compiler sources | median edit re-checks |
  |---|---|
  | file-level (what round 772 measured) | 99% of files / **100% of chars** |
  | **symbol-level** (94.9% of imported names placed to a declaring file) | 95% of files / **100% of chars** |

  **The `export *` BARREL WAS NEVER THE CAUSE** — (INC.35) and round 772 both say or imply
  it was, and both are wrong about the mechanism. `checker.ts` genuinely uses symbols from
  `types.ts` / `core.ts` / `utilities.ts` / `debug.ts` / `parser.ts`, everyone uses `core.ts`
  and `debug.ts`, and the relation is transitive. Knowing WHICH symbols a file imports buys
  nothing when the answer is "most of them, from most files".

  **WHAT DOES CRACK IT IS THE SECOND HALF OF THE IDEA: ask whether the symbols a file uses
  have CHANGED, not which they are.** An edit to a function BODY leaves every exported
  signature intact, so no dependent needs re-checking and the closure collapses to `{the
  edited file}` however dense the graph is — transitivity fires only when an edit actually
  moves an exported TYPE. **91.6% of the program's characters sit inside brace-delimited
  bodies** (stripper length-preserving, positive control passed), so most edit POSITIONS
  cannot change a signature. Read that as a proxy and not a rate: it is optimistic because an
  INFERRED return type leaks a body change back into the signature, and pessimistic because
  it counts `interface`/`type` bodies, which ARE signature, as body text. **The honest rate
  needs an edit corpus and this checkout cannot supply one** (`typescript-repo` is a depth-1
  shallow clone and is a build-pinned input — do not deepen it; fetch a separate clone).

  **AND THIS IS WHY IT MATTERS MORE THAN (INC.35): A SIGNATURE HASH PAYS ON *DENSE* CODE
  TOO.** (INC.35) is owner-closed because a closure only pays on LAYERED code and the
  dashboard profile is the opposite; this mechanism can be built and graded on tsc's own
  sources, i.e. **it needs no corpus choice and no owner call**.

  **THE PRIZE IS ALREADY MEASURED AND NEEDS NO NEW RUN.** A body-only edit to file F would
  cost a narrowed build of `{F}` plus a merge, against a full rebuild: **108-113 ms median
  (p90 202-219) against 4,864-5,096 ms — a factor of 45** ((INC.31)/(INC.37), 2026-08-24,
  `d018af0a`, § 14). `checker.ts`, the 31.6% file, is 1,744-1,763 ms against the same 4.9 s.

  **THE COST HALF IS THE UNMEASURED ONE, AND ITS INPUT IS CENSUSED: 3,398 exported
  declarations over the 78 files** (mean 44, median **6**, max **874** in `types.ts`). So the
  per-build work is ~3,400 `getTypeOfSymbol` + fingerprint calls, against a rebuild that
  already makes ~800 k `getTypeOfExpression` calls — almost certainly single-digit ms, but
  **that is an argument and not a measurement; hook it and read it before building anything
  downstream of it.**

  **THE HAZARD THAT WOULD SINK IT, AND IT IS NOT THE OBVIOUS ONE.** The tempting hash source
  is `typeToString(getTypeOfSymbol(exported))` — a resolved type rather than syntax, which is
  the right SOUNDNESS instinct (a syntactic hash misses an inferred return type). **It is
  still the wrong source, for two reasons this repo has already documented in another
  context.** (i) `typeToString` is **not a pure function of the type**: `aliasDisplayMap` is a
  FIRST-WINS global keyed by `Type.id` ((INC.11)/(INC.26)/(INC.41)), so the same type renders
  differently depending on what was resolved first — spurious invalidation, which is SAFE but
  may be frequent enough to eat the whole prize. (ii) B58.1 renders `errorType` as **`"any"`**,
  so a type that DEGRADES to a resolution failure hashes identically to a genuine `any` —
  **a missed invalidation, i.e. a stale diagnostic, silently**, which is the only direction
  that matters. **The hash must therefore be an ID-FREE STRUCTURAL FINGERPRINT** (member names
  + modifiers + recursively fingerprinted member types, cycle-guarded), never a display
  string and never anything keyed on `Type.id`, which is a per-build sequence.
  **(INC.16) already built exactly such a fingerprint for the INV.2(c) lexical tables — copy
  its shape rather than inventing one.**

  **WHAT ELSE MUST BE IN THE HASH, or the invalidation is unsound**: the SET of exported names
  (an added or removed export changes resolution in every importer, with no type moving); the
  targets of `export *`; and a whole-program escape for any file declaring GLOBALS or
  augmenting a module — **5 of the 78 carry `declare global` / `declare module "…"` /
  `export as namespace`** (regex-approximate; re-derive it from the binder, not from text).

  **ORDER OF WORK, and it is measure-first by construction.**
  (1) **DONE — the threshold is MET and the walk's SHAPE was the real question.** Built,
  measured and pinned: **136 ms whole-program** on a 5,215 ms rebuild, and **0 ms on 23 of
  24 narrowed builds** (a narrowed build fingerprints only its partition), so the per-EDIT
  cost of the gate is under a millisecond. **Two controls decide feasibility and neither is
  a cost figure**: two builds of identical text agree **78/78** (the id-freedom claim), and
  a narrowed build's fingerprint equals the whole-program one **24/24** (the CONVERGENCE
  claim — without it every first edit falls back forever). **THE COST INPUT CENSUSED ABOVE
  IS THE WRONG QUANTITY**: cost tracks the transitive type CLOSURE, not the export COUNT,
  and the two are near-inversely related — `utilities.ts`'s 692 exports are 1.6 ms and
  `types.ts` is 129.6 ms. **AND THE OBVIOUS WALK DOES NOT TERMINATE**: a path-only cycle
  guard is exponential in DAG width (159 s inside one build), and closed-subtree
  memoization is still not enough because tsc's type graph is one giant SCC (6 of 78 files
  unfinished inside a 2,000,000-node budget). What works is CUTTING at the file boundary —
  a type declared elsewhere is unchanged by construction while only this file is edited, so
  it is keyed by its declaration's `(fileName, pos, end)` and not descended into. See the
  (INC.46)(1) session note and `Checker.ExportFingerprinter`. **Escape set: 2 of 78** —
  `types.ts` (budget stop) and `checker.ts` (an exported name with no file-level symbol,
  UNDIAGNOSED and the first thing to look at, since it is the file an editor edits most).
  (2) **DONE — 67% MEASURED, AND NOT REFUSED.** `scripts/inc46-stability.sh` fetches its own
  blob-filtered depth-3000 clone of microsoft/TypeScript (never `typescript-repo`, which is
  build-pinned) and replays **40 real no-merge commits** touching `src/compiler`, whole tree
  at the parent against whole tree at the commit. **27 of 40 stable = 67%**, right at the
  stated threshold — and **8 of the 13 that moved did so ONLY because `types.ts` ESCAPES**,
  so the achievable band is **67% floor, 87.5% ceiling** with ONE named lever between them.
  **THE FIRST READING WAS 32% AND WAS AN ARTIFACT**: `declaresGlobalSurface` scanned whole
  source for `export as namespace` (a construct with no AST node), `checker.ts` says those
  words twice IN COMMENTS, and since it is the most-edited file that one false positive was
  worth **35 points**. Anchoring the match to the start of a line fixed it. **`types.ts`'s
  escape is STRUCTURAL and measured**: a node-budget stop at 2,000,000 (129.6 ms) AND at
  **12,000,000 (741 ms, still stopping)** — the file-boundary cut cannot help INSIDE a file,
  and `types.ts` declares ~874 mutually recursive interfaces in one file. The lever is
  **SCC-aware hashing** (Tarjan, hash each component as a unit); the budget stays bounded at
  2,000,000 and the file is recorded in `ExportSignatures.whole`, which costs a full rebuild
  and never a stale diagnostic.
  (3) **DONE — `Project.diagnostics()` IS INCREMENTAL.** `Project.surface` +
  `incrementalDiagnostics`, `ProjectCompiler.build(exportSignatures = …)` and two new
  `Result` fields. Five preconditions, each CHECKED rather than argued (a baseline exists;
  every edited file was in that program; none ESCAPES; the narrowed build finds the same
  program; no fingerprint moved), each with its own pin. **GRADED EQUIVALENT — 40 of 40
  real commits agree row for row with a fresh whole-program build, `served=27`**, the
  control that keeps the agreement from being vacuous (a harness whose `served` is 0
  REFUSES). 11 pins in `ProjectIncrementalDiagnosticsTest`, paired ANSWER and COST families
  because a cost-free pin set passes against the old always-rebuild behaviour.
  **GRADE IT AS A DIFFERENTIAL, which needs no baseline**: after an edit, incremental
  project-wide diagnostics must equal a full rebuild's, row for row, over a SEQUENCE of edits
  — and the sequence must contain a signature-CHANGING edit and a body-only one, or the gate
  is vacuous in exactly the way (INC.45)'s arm b2 was (a clean fixture made a
  diagnostic-multiset comparison compare empty against empty and pass).
  **Note what it is NOT**: this is tsc's own `--incremental` design (a per-file signature in
  `tsbuildinfo`, hashed from the declaration emit, with dependents skipped when it has not
  moved). We have no declaration emitter — `declaration`/`emitDeclarationOnly` are parsed
  options with no emitter behind them — which is *why* the fingerprint goes over resolved
  types directly instead.

- [x] **(INC.45) `renameAt` IS NARROWED TOO — LANDED 2026-08-29, AND ITS THREE OBSTACLES
  WERE ALL REAL.** The rename sweep performed the same whole-program capture (INC.44) removed
  from `referencesAt` and paid the same 20-26 s for it. It now takes the same spelling closure
  and hands the resulting file set to the compiler as a check partition. The three things that
  made it not a copy of the reference change were each answered rather than assumed:
  **(1) THE DIAGNOSTIC MULTISET.** `verifyRename` compares `(file, code)` bags before and
  after applying the plan; a partition filters diagnostics to its own files, so the two builds
  must SHARE one. `RenameSweep.partition` carries it and the after-build takes it rather than
  deriving its own from the spans it happens to ask about. The soundness argument is written
  into `narrowedRenameSweep` and is the one a reviewer should attack first: a rename edits only
  files the plan names, all of which are in the partition, and an unedited file's meaning can
  change only through a name it imports — which it must then SPELL.
  **(2) THE NEW NAME HAD TO WIDEN THE SELECTION.** `verifyRename`'s third check — the only one
  that can see a rename which compiles and means something else — scans for occurrences ALREADY
  spelling the new name. Selected on the old name's closure alone it finds nothing and passes
  VACUOUSLY, i.e. the narrowing would have switched the safety net off rather than paying less
  for it. The new name joins the SELECTION and not the CLOSURE: it is not a spelling of the
  symbol being renamed, so letting it contribute alias links or escapes would make the
  partition a function of a name that names something else.
  **(3) AND A PLAN COMPARISON CANNOT SEE (2).** On a fixture whose new name is fresh both arms
  agree with or without the widening, so the pin is a COUNT — `Project.narrowedRenameFiles`
  reaches a file the reference partition at the same caret does not.
  **THE ABLATION FOUND A BLIND PIN SET AND THE FIX IS IN THE FIXTURE, NOT THE ASSERTION.** Arm
  b2 (the after-build forgets the sweep's partition) reddened **NOTHING** on the first run:
  every fixture was a CLEAN program, so both bags were empty whatever either build walked and
  the comparison was empty-against-empty. Adding one file that carries a diagnostic and spells
  none of the renamed names takes b2 to **2 RED**. **Arm b3 — never narrow at all — is
  UNDISCRIMINATED and is recorded as such**: the change is equivalence-preserving by
  construction, so no assertion about an ANSWER can see it; what stands in its place is a
  single pin on the shipped DEFAULT with no mode install in it ((INC.16)'s lesson), and the
  cost measurements, which are wall time and pinned by nothing.
  **GRADED** by `scripts/rename-narrowing-differential.sh`, which compares whole `RenamePlan`s
  — a data class, so equality covers every edit's file, span and text, the refusal and the
  conflict list — and prints `applicable=` beside `narrowed=` because two REFUSALS compare
  equal and a run with no applicable plan in it has compared two empty edit lists.
  **EQUIVALENT** — 8 carets by stride over all 381,775 occurrences, **7 narrowed**, **6
  producing an APPLICABLE plan** (the second control), 1,691 edits compared plan for plan,
  **0 diverged**; 56.5 s narrowed against 114.2 s whole-program (**2.02x** on a draw that
  lands proportional to occurrence count, i.e. on the hottest names). **Draw few carets**:
  a rename holds a whole-program sweep per arm and a 20-caret run at `-Xmx6g` was
  OOM-KILLED — the tell is a harness that stops after its header with no verdict line.
  **+6 pins** (`ProjectRenameNarrowingTest`, plus the shipped-default pin in
  `ProjectReferenceNarrowingTest`); three ablation arms, b1 -> 1 RED, b2 -> 2 RED (after the
  fixture repair), b3 undiscriminated with a reason. **MEASURED per symbol**, both arms
  interleaved in one process: `emitFiles` **2 of 78 files, 1,304 ms against 15,933**;
  `transformNodes` 3 of 78, **1,025 ms against 14,871**; `checkSourceElement` 1 of 78 (but
  that file is `checker.ts`), **4,725 ms against 15,198** — so an ordinary rename is
  **~1.0-1.3 s against ~15 s (12-14.5x)**.
  **SUCCESSOR, per the WORK ORDER note — a round must name one.** With (INC.44)/(INC.45)
  landed, the ONLY interactive operation left that is whole-program in every case is
  project-wide `diagnostics()` at 4,864-5,096 ms per edit, and it is **owner-closed as
  (INC.35)** with a stated re-open condition this session's directive does not meet: "RE-
  OPENABLE ONLY on an owner directive naming a LAYERED corpus to grade it on". Round 772's
  measurement is why — tsc's own sources are `export *` barrels, so a reverse-dependency
  closure reports `77/78` for a LEAF and buys nothing on the very profile every gate here
  uses. **The (LIB.\*) screened libraries (knip, jsonrepair, cronstrue) are the corpora that
  could grade it**, so the decision the owner would be making is which one, not whether the
  mechanism works. **BUT (INC.46) SUPERSEDES THAT CHOICE ENTIRELY, AND ALSO MEASURES THE
  BARREL EXPLANATION ABOVE TO BE WRONG**: a symbol-level use graph re-checks 100% of tsc's
  characters at the median edit, exactly as the file-level one does, so those files' density
  and not their `export *` is what defeats a closure. An exported-SIGNATURE hash pays on
  DENSE code as well as layered, so it is gradable on the dashboard profile and needs no
  corpus and no owner call — take it BEFORE re-opening this. The other named successors are
  (INC.39) (the per-handler spine cost under a single-file partition, still unpriced on its
  own terms) and (INC.33)'s unmeasured half — the PREPARE-AMORTISED case for wiring
  `completionsAt`/`signatureHelpAt` to `prepared`, which neither (INC.32) nor (INC.33)
  measured.
  **Suite 16,440 / 0 / 3** (+18 over the
  session's re-verified 16,422 baseline, exactly the new pins).

- [ ] **(INC.43) THE 213 ROWS (INC.42) DID NOT CLOSE — AND THEY ARE NOT WHAT THE QUEUE HAS
  BEEN CALLING THEM.** Re-measured after (INC.42) landed: `Inc41ClassifyMain` reads **796 rows
  / 37 pairs / 213 GAINED-INFERENCE, UNCHANGED**, and REPLAY-WORSE did not grow. **Read out of
  the classifier's own dump rather than assumed, the p000 rows are NOT hovers on `Visitor`**:
  they are carets on `visitEachChild` / `visitFunctionBody` / `discardVisitor` — **function
  names whose rendered OVERLOAD SET carries a parameter declared `Visitor`**. So the string
  comes from the **CHECKING** path (`getTypeFromTypeReference` on a bare `Visitor`), which
  (INC.42) deliberately does not reach, and **both arms render an unbound parameter**:
  `(node: TIn) => any` fresh, `(node: TIn) => T | readonly Node[]` replayed. tsc renders
  `Visitor`.
  **REACHING IT IS BLOCKED THREE TIMES, EACH COST MEASURED — READ THESE BEFORE PROPOSING
  ANYTHING.**
  (1) **(INC.28)**: handing a reference the alias's PARAMETRIC form costs two corpus false
  positives (`typeArgumentDefaultUsesConstraintOnCircularDefault`,
  `excessPropertyCheckIntersectionWithRecursiveType`).
  (2) **(INC.42)**: relaxing B57.1b's constraint guard on the CHECKING path (i.e. dropping
  `aliasBodyDisplayDepth`) reads `output.errors` **46 -> 48** on the compiler profile — an
  overload-resolution defect at `checker.ts:2503` that a no-longer-`any` `VisitResult<T>`
  exposes, plus a TS2322 at `watchPublic.ts:576`. Two dashboard false positives against 213
  hovers is not a trade.
  (3) **Even with both closed**, we would render `(node: TIn) => VisitResult<TOut>` where tsc
  renders `Visitor` — B50.5 deliberately does not register an alias NAME for a result that is a
  pure function type (`isPureFunctionType`, pinned by `nestedCallbackErrorNotFlattened_ts`).
  **VERDICT: this is a RELATION-ENGINE item ((INC.30)) plus an alias-NAMING one, NOT a display
  bug**, and the honest order is (1) before (2) before (3). Do not attempt it as a rendering
  fix — (INC.26)/(INC.27) established that `typeToString` is shared with the diagnostics and
  pinned byte-for-byte across ~13k baselines.
  **PRIZE: UNMEASURED, AND DELIBERATELY SO — a correctness item, not a latency one.** It buys
  no milliseconds; it makes a hover right. The pin must assert the **VALUE** against
  `tools/tsgo-7.0.2/lib/tsc --lsp -stdio` (round 924's oracle,
  `scripts/lsp_hover.py` / `scripts/lsp_hover_project.py` — read the profile's sources with
  `newline=""`, it is CRLF), never that two arms agree: the capture sweeps are DIFFERENTIALS
  and are blind to anything both arms get wrong ((INC.28)'s law).
  **AUTHORITY: `docs/inc41-replay-capture-classification.md` § 6a**, with § 3's per-cause table
  and § 7's grading rule — a change is an improvement only if the **element-pair** count falls
  ((INC.23): 192 of the 796 rows carry more than one differing element, so a ROW count
  over-reports). The two smaller causes in the same bucket are a different question and are
  probably not one fix: `ModuleName` -> tsc's `StringLiteral` (74 rows) and
  `ImportAttributeName` -> `StringLiteral` (62), where we WIDEN and tsc narrows.

- [x] **(INC.4) LANDED 2026-08-22 — `ProjectCompiler.build` now refuses it, 4 pins
  including the DEFAULT-`noEmit` case and both negative controls. ORIGINAL ENTRY:
  `recheckOnly` + EMIT IS UNSOUND AND `ProjectCompiler.build` DOES NOT REFUSE IT.** The Transformer queries the checker it is handed (`isReferencedAliasDeclaration`
  and friends), so under a partition it asks a checker that walked a SUBSET and elision
  goes wrong. Every driver gates incremental on `--noEmit` and `Project` always passes
  `noEmit = true`, so nothing today is wrong — but the parameter is public and the next
  caller will not know. `require(noEmit || recheckOnly == null)`, with the message naming
  the caller's mistake, exactly as `compileParsed` already does for `checkedSink`.

- [x] **(INC.5) LANDED 2026-08-22 — 45 divergent spans -> 9, and the 40 wrong-direction
  rows -> 4. See the session note; what is left is (INC.6). ORIGINAL ENTRY: WHAT A HOVER REPORTS DEPENDS ON PROGRAM ORDER — A PRE-EXISTING DEFECT
  (INC.2) MADE VISIBLE, AND IT IS NOT ABOUT PARTITIONS.** `symbolTypes` persists the first
  resolution of a symbol's type, and resolving a type reference inside an anonymous object
  type literal answers differently depending on which file asks first: in the same program,
  the whole-program build renders `(key: K, valueInNewMap: U) => any` for a span where a
  narrowed build renders `=> T`, and elsewhere the reverse. **Neither arm is canonically
  right; they are two draws from an order-dependent cache.** Today the order is fixed by
  the crawl (`ProjectCompiler.walk` sorts, and CLAUDE.md records that three orders of the
  same 78 files move `typeNode.bypassed` ~1% with every diagnostic bit-identical), so a
  user sees ONE answer consistently — which is why this has never been reported. It is
  still a wrong answer where the collapse is to `any`.
  **THE INSTRUMENT ALREADY EXISTS**: `scripts/capture-equivalence.sh` reads 45 divergent
  spans out of 381,666 in one run, and the full-vs-narrow pair is a differential ORACLE
  for it — no baseline needed, because the two arms must agree. Start there rather than by
  reading the resolver: the census names the 11 files and the exact spans.
  **THE SEAM IS NAMED BY THE DIVERGENT ROWS THEMSELVES, AND IT IS NOT NAME RESOLUTION.**
  One row loses a KEYWORD type (`{ fileName: string }` -> `{ fileName: any }`) and another
  a mapped-type modifier (`Required<{ reportInferenceFallback(node: Node): void }>` ->
  `Required<{ reportInferenceFallback?: any | undefined }>`). A name resolving in the wrong
  file's scope cannot lose `string` or `-?`; an UNRESOLVED MEMBER TABLE can. So this is
  round 833's hazard one layer up — *a target type's member table is LAZY, so a verdict
  depends on whether an earlier line in the file happened to resolve that type* — with
  `typeToString` as the reader and A DIFFERENT FILE'S CHECK as the "earlier line" that a
  whole-program build always happens to perform.
  **THE FIX IS THEREFORE SMALL AND SURGICAL, AND IT BELONGS IN THE CAPTURE PATH ONLY:**
  force `resolveStructuredTypeMembers` on the type about to be rendered (and on the member
  types it recurses into) before `typeToString`. Doing it inside `typeToString` itself
  would change DIAGNOSTIC MESSAGES program-wide and put ~13k corpus baselines in play for
  a language-service defect; doing it where the capture records its display string cannot
  move a single diagnostic, which is what makes it landable in one round.
  Then re-run `scripts/capture-equivalence.sh`: expect the 40 `any` rows to clear and the
  5 REVERSED rows (where the full build is the one showing `any`) to need their own
  diagnosis — they are the same order-dependence seen from the other side.
  Closing it also unblocks (INC.2)'s 3.73x.


- [x] **(LIB.1) knip MEASURED 2026-08-22 — 2,634 xtsc errors against tsgo's 23, and 94.1%
  of them are ONE missing feature.** `webpro-nl/knip` at `main`, `packages/knip`: **498
  files, 35,663 lines**, `moduleResolution: nodenext`, `"type": "module"`,
  `verbatimModuleSyntax`, every relative import written with an explicit `.ts` extension.
  Front end: xtsc `--noEmit --listAll` reports **2,634 in 7,131 ms**; tsgo 7.0.2 reports
  **23, all environmental** (no `@types/picomatch`, `webpack`, `@jest/types`,
  `codeclimate-types`) — knip itself is clean under the oracle.
  **TWO CODES ARE 2,478 OF THE 2,634 (94.1%): TS1295×1,959 and TS1287×519**, both saying
  the file is CommonJS. **xtsc does not derive a file's module format from the nearest
  `package.json` `"type"`,** so under nodenext every knip file is classified CommonJS and
  every import and export trips the `verbatimModuleSyntax` guard. The attribution was
  CONFIRMED, not inferred: deleting that one option from the tsconfig reads
  **2,634 -> 156**, and tsgo re-run on the same config still reads 23. Queued as (CHK.29).
  **THE RESIDUAL IS 156 = 0.31 FP/file, BETTER THAN THE 0.9/file `docs/kir-library-readiness.md`
  RECORDS FOR `yaml`, AND IT IS THAT PAGE'S TWO KNOWN FAMILIES**: TS7006×89 (57% — an
  object-literal METHOD's parameters are not contextually typed from the annotated return
  type; (CHK.30)), TS2339×23 (union member access where narrowing did not apply), then
  TS2322×16, TS2552×9, TS18048×7, TS2353×3, TS2769/TS2349/TS2304×2, TS2591/TS2345/TS18047×1.
  **THE OVERLAP WITH tsgo's SET IS ZERO IN BOTH DIRECTIONS — so there are also 23 FALSE
  NEGATIVES**, including two genuine TS2322 and a TS2722 in `src/util/glob-core.ts` that
  tsgo reports and we do not. A residual FP count is not a conformance number until the
  misses are counted too.
  **WHAT WORKED AND IS WORTH RECORDING: module resolution.** All **1,921** relative
  specifiers carry an explicit `.ts` extension (`allowImportingTsExtensions` +
  `rewriteRelativeImportExtensions`) and every one resolved — the type errors name real
  imported types (`Configuration`, `TsConfigJson`, `Plugin`), so (KIR.EMIT.1)'s work holds
  on an unfamiliar codebase.
  **BACKEND: the project probe never reaches the lowering** (it will not emit a program the
  checker rejected), so it was measured on ONE self-contained file —
  `src/util/graph-sequencer.ts`, 131 lines, no imports: `typeErrors=0`, then
  `refused: graph-sequencer.ts:22:74 a spread element is out of the spike subset`.
  Censused against the 17 refusal messages in `lower/`: **destructuring parameter 255 files
  (51%), spread 163 (33%), destructuring declaration 121 (24%), `async`/generators 112
  (22%), computed property name 63 (12%), optional element access 29 (5%)** — the union is
  **237 of 498 files (48%)** before counting anything downstream. `async` is decisive on its
  own: knip's entry point IS `export const main = async (options) => …`.
  **BUT knip IS UNREACHABLE FOR REASONS THAT ARE NOT THE LOWERING, AND THAT IS THE FINDING
  THAT MATTERS FOR PLANNING.** It depends on **two native Rust N-API binaries** —
  `oxc-parser` (32 import sites) and `oxc-resolver` — which are not TypeScript and cannot be
  lowered from; on **10 `node:` builtins** (`fs`×21, `fs/promises`×5, `util`, `path`,
  `module`, `crypto`, `url`, `process`, `perf_hooks`, `child_process`) against a
  `KirIntrinsics.libraryClass` table of exactly **six** entries (`Array`, `Map`, `Set`,
  `RegExp`, `Date`, `Error`); and on `createRequire`×9 plus `jiti`, i.e. evaluating config
  files at run time. **A program whose job is to read the filesystem and parse source with a
  native parser needs a Node-API layer on the JVM, which is a bigger project than the
  lowering.** So knip is the right instrument for the FRONT END and the wrong driver for the
  backend ladder — see (LIB.2).
  **REPRODUCTION** (both halves, ~10 s):
  `java -cp <core-classes>:$(bash scripts/lib/dep-classpath.sh --print) com.xemantic.typescript.compiler.MainKt --noEmit --listAll <knip>/packages/knip`
  and `KIR_PROBE_FILE=<knip>/packages/knip/src/util/graph-sequencer.ts ./gradlew :xemantic-typescript-compiler-kir:jvmTest --tests '*LibraryProbe*' --rerun -i`.
  Oracle: `npm i typescript@7` in a side root, then `tsc --noEmit -p <knip>/packages/knip`.

- [x] **(CHK.29) LANDED 2026-08-25 — the lookup exists; `TS1295+TS1287` on knip go
  **2,478 -> 0** and the library goes 2,634 -> 309 (one draw, no `node_modules`, so 147
  of the 309 are environmental `@types/node` rows). The producer was the missing half:
  `packageJsonTypes` had a CONSUMER and one producer that reads the corpus's parsed
  source set, and a real project has no `package.json` among its INPUTS —
  `ProjectCompiler` now walks the `Vfs` up from each program file's directory, memoized
  per directory, gated on `isNodeNext`. Two corrections tsgo forced: a manifest with no
  `"type"` ESTABLISHES the scope at CommonJS (the walk stops at the first one it meets),
  and the manifest is parsed as JSON — knip's own has `repository.type: "git"` FIRST, so
  a regex answers CommonJS for a `"type": "module"` package. Pins:
  `ProjectPackageJsonTypeTest` (11, `-project`). Residue queued as (CHK.36)-(CHK.38).
  ORIGINAL ENTRY: A FILE'S MODULE FORMAT IS NOT DERIVED FROM THE NEAREST `package.json`
  `"type"` — 2,478 FALSE POSITIVES ON ONE LIBRARY, AND NOTHING IN THE CORPUS CAN SEE IT.**
  Under `module`/`moduleResolution: nodenext` (and `node16`), tsc decides whether a `.ts`
  file is an ES module or CommonJS by walking up to the nearest `package.json` and reading
  its `"type"` field. We do not, so a `"type": "module"` package is classified CommonJS and
  every ESM import/export in it trips `verbatimModuleSyntax`: **TS1295×1,959 + TS1287×519**
  on knip, measured, i.e. 94.1% of that library's error count from one absent lookup
  ((LIB.1)). **THE CORPUS IS STRUCTURALLY BLIND**: tsc's own sources are not
  `"type": "module"`, `usesUnsupportedOption` never skipped these fixtures because the
  option is not in the removed list, and the 8 dashboard profiles all inherit tsc's layout —
  so a green corpus, a green `cost_gate.py` and an `added=0 removed=0` grid are the EXPECTED
  answers here and none of them is evidence. **The pin has to be a project fixture with a
  `package.json` beside the sources** (`-project`'s `ProjectCompiler` path, not `diagnose()`,
  which has no package.json and no directory), asserting both directions: `"type": "module"`
  is silent, and its ABSENCE under nodenext still reports TS1295. Check what else reads the
  format while you are there — `impliedNodeFormat` also decides `esModuleInterop` behaviour,
  the `.mts`/`.cts` extension overrides, and whether a `require()` of an ES module is an
  error, so the fix is one lookup with several consumers.

- [x] **(CHK.30) DONE 2026-08-25 — AND ITS DIAGNOSIS WAS WRONG. The 89 TS7006 were NOT a
  contextual-typing defect: a type imported from a `node_modules` PACKAGE resolved to
  `any`.** knip (`webpro-nl/knip@main`, fetched and reduced this round): **156 -> 66
  errors, TS7006 89 -> 1, and NO row appeared that was not there before.** The entry's own
  example was a victim rather than an instance — `PluginVisitorObject = VisitorObject`,
  and `VisitorObject` comes from `'oxc-parser'`. Its literal-method form, written out by
  hand, has always been correct (`interface V { m?: (n: N) => void }` + `{ m(node) {…} }`
  is silent on a pre-fix binary; the fixture that reproduces is 15 lines and its only
  unusual feature is a `node_modules` package). **The mechanism**: the crawl resolves the
  specifier correctly and the package's `.d.ts` really is in the program, but the CHECKER
  re-derives which file a specifier names by string-matching it against the program's file
  NAMES, and that corpus-era matcher cannot express a bare specifier at all. Fixed by
  carrying the crawl's own `(importer, specifier) -> file` answers
  (`ParsedSource.moduleResolutions`) as the last leg of all ten alias ladders.
  **A SECOND, SMALLER DEFECT LANDED WITH IT**: a concise-body arrow's OWN return
  annotation was not a contextual type for its body in either the implicit-any or the
  property-access walker (a BLOCK body always had it, at the return edge — so
  `(): V => { return {…} }` was right and `(): V => ({…})` was not). Worth 4 more knip rows
  and the curried-factory idiom `(dep: D): Handler => (a, b) => …`.
  Pins: `ProjectPackageTypeResolutionTest`, `ContextualReturnAnnotationTest`.

- [x] **(CHK.39) DONE 2026-08-25 — the pull landed: the item's probe went 0/6 -> 6/6 for the
  ASSIGNABILITY family and for every hover, and the residue is ONE WALKER rather than one shape.**
  `pullContextualTypeAt` is tsc's `getContextualType`, PULLED from the parent chain because the
  spine carries no contextual ambient at all (round 911); it writes the contextual parameter types
  at TWO sites and the ablation partitions them exactly — `checkFunctionBody` is the EMITTING half
  (a statement nested in a function body is emission-owned by that legacy walk: the spine's own
  anchor runs `recordOnly` for it and truncates every diagnostic, so the frame alone is correct
  and invisible) and `ctaFnBodyFrame` is the CAPTURE half a hover reads. B85.1a is load-bearing
  there — an OPTIONAL contextual parameter is `T | undefined`, and the bare type was this round's
  one measured false positive, on three profiles. **(CHK.39b) landed with it**: an object-literal
  METHOD's body was not walked by the assignability walker AT ALL in a `.ts` file
  (`walkFunctionBodiesInExpr`'s `if (jsLike)` — a gate about `this` that was deciding whether the
  body is checked). A KIR soundness defect surfaced and was fixed (a call of a function VALUE is
  arity-ADAPTING, never a direct `FunctionN.invoke` — JS assignability accepts a LOWER-arity
  function, which is what mitt's driver does). **(CHK.39c) is REFUSED and re-queued as (CHK.41).**
  `typeNode.bypassed` +31.26% rebaselined (~+21 ms, and the unspent lever is a per-node memo of
  the pull); knip 66 -> 66 with every row identical; 8-profile grid `added=0 removed=0`. Pins:
  `ContextualParameterTypeTest` (18), `ProjectContextualParamHoverTest` (4, expectations read out
  of tsc's own LSP).

- [x] **(CHK.41) DONE 2026-08-26 — the GUARDED REASSIGNMENT now reduces the DECLARED union,
  and the item's own premise was two-fifths right: the +15 knip rows are FIVE mechanisms.**
  `narrowByAssignmentRhs` gained the two right-hand sides no arm of it could type — a CALL
  WHOSE CALLEE IS THE WALKED REFERENCE (`c = c()`, typed from the ANTECEDENT, which the guard
  has already narrowed, because `getTypeOfExpression` never narrows and
  `resolvedCallReturnTypeForFlow` needs a `FunctionDeclaration`) and a type ASSERTION
  (`c = (await c(x)) as T`, whose type is syntactic, (CHK.43)) — reducing the DECLARED union,
  never the antecedent (round 416's rule; arm a4b, 5 RED). knip **66 -> 66 byte-identical**
  with a rebuilt BEFORE arm, grid `added=0 removed=0` on all eight (a CONTROL, not evidence).
  Pins: `GuardedReassignmentNarrowingTest` (9), every positive paired with the negative half.
  **THE TWO CONTEXTUAL SOURCES STAY REVERTED** — recovered from (CHK.39)'s own captures and
  reproduced with ANNOTATED parameters, their +15 rows are ava 3 + eleventy 3 (fixed here),
  release-it 2 (`typeof x.y?.z === 'string'` must narrow `x.y`), mdxlint+remark 4 (the
  `flatMap` callback's return-type inference), graphql-codegen 1 (a nested-ternary predicate)
  and yarn 2 (a `Plugin` NAME collision, not narrowing) — see the round note's table.

- [x] **(CHK.44) DONE 2026-08-26 — the axis was not `local`-vs-`parameter` but **declared in a
  BLOCK**, and a block-scoped union receiver is now typed from the INV.2(c) lexical tables.**
  (CHK.41)'s "3 of 4 shapes, only a parameter is checked" was wrong in both directions: a
  FILE-LEVEL `const`/`let` IS checked (its first probe was named `top`, which collides with the
  DOM global), and what fails is any declaration inside a block — function, method, arrow,
  nested function, nested block and file-level block alike, for `const`/`let`/`var`. B83.5 is
  the cause end to end: nothing binds such a declaration, so `getTypeOfIdentifier` answers
  `anyType` and every gate below it bails. `cmamBlockScopedReceiverType` reads the declaration
  back out of `lexicalScopeSymbol` (`LexicalScope.symbols` only) at the ONE call that asks
  whether a property exists on the receiver. **Two refusals are MEASUREMENTS**: a nullish union
  costs 11 compiler-profile / 16 harness rows tsgo does not report, and a NON-union declared
  type costs 3 services/server/harness rows — while `const`-ness is NOT a guard (dropping it is
  `added=0 removed=0`). Grid `added=0 removed=0` on all eight vs a rebuilt parent, knip 66 -> 66
  byte-identical, suite **15,979/0/3**, `cost_gate` PASSES unrebaselined. Pins:
  `BlockScopedReceiverTypeTest` (20). **FOUR POPULATIONS REMAIN SILENT and are queued as
  (CHK.45)** — see below.

- [x] **(CHK.45) DONE 2026-08-26 — (a) CLOSED, and THREE of the four populations turned out not
  to be block-scoping gaps at all.** (a) was the union elaboration's ALL-MISSING whitelist
  (`allWellResolved` / `allAnonPlainObjects`), not "a different emitter": a parameter and a
  file-level `const` of the identical type were equally silent, and what refused it was the
  FUNCTION type in `A | F`. Fixed by a per-member trust predicate
  (`cmamAllMissingTrustedMember`) admitting function/constructor types, primitives, literals,
  tuples and anonymous type literals, and refusing — each a measured false negative — a
  heritage interface, a class instance, a `Type.Reference`, an intersection, a type parameter,
  an enum-flavoured object and a content-free anonymous object. Calibration: deleting the gate
  ENTIRELY is grid-clean and corpus-clean and still costs **2 knip false positives**, both a
  cross-file heritage interface (B153). (b) SPLITS — its file-level half is (a) and is closed,
  its body-local half is the B83.5 gap. **(b)-body-local, (c) destructuring and (d) nested
  single-object receivers survive as three INDEPENDENT gaps, none of them about block scoping**;
  see the round note's table for the 3x5 measurement. Suite **15,998/0/3**, grid `added=0
  removed=0` on all eight, knip **66 -> 66** byte-identical with a rebuilt before-arm,
  `cost_gate` PASSES unrebaselined. Nine ablation arms; a5/a8 recorded as redundant guards.
  Pins: `AllMissingUnionMemberTest` (19).

- [x] **(CHK.46) DONE 2026-08-26 — ALL THREE CLOSED, and in TWO of them the TYPE was never
  missing: what was absent is a CONSUMER.** (c) a destructured name is typed as a receiver
  nowhere and fails two different ways — `getTypeOfSymbol` has no `BindingElement` arm for a
  BOUND pattern, `getTypeOfIdentifier` answers `anyType` for an UNBOUND one — fixed by finding
  the `BindingElement` syntactically (`cmamDestructuredReceiverType`), with the UNION reading
  routed to the flow-consulting union block and every other type to the two `any` bails.
  (d) a nested access with a single-OBJECT leaf had no emitter (`cmamCheckNestedObjectReceiver`,
  behind (CHK.45)'s trust predicate plus two MEASURED refusals — an array-like, and an `in`
  guard on the path, which is legal and which `narrowByInOperator` answers with the UNCHANGED
  type); `checkMergeTypeMethodChain` now defers to it on the one row they both own.
  (b) an un-annotated body-local `const` had no type at all (B83.5) — `const` only, and a
  WHITELIST of initializer forms, because a `new X(…)` costs three corpus baselines through a
  type-only import shadowing a lib global. Suite **16,050/0/3**, grid `added=0 removed=0` on all
  eight, knip **66 -> 66** byte-identical, both against a parent rebuilt in-session; `cost_gate`
  rebaselined once (+0.13pp of a +2.09% that was +1.96% before this round). 28 ablation arms;
  two pins were VACUOUS and only an arm saw it; the generic refusal is a round-927 PAIR. Three
  gaps stay open and are in the round note, not pinned. Pins:
  `DestructuredReceiverTypeTest` (21), `NestedAccessReceiverTest` (15),
  `UnannotatedLocalReceiverTest` (16).

- [x] **(CHK.47) DONE 2026-08-26 — (i) CLOSED and it was THREE mechanisms, not one; (ii) HALF
  closed; (iii) TRIAGED into five groups, one of them already closed. knip 66 -> 49, seventeen
  false positives, every one confirmed silent in tsgo.** (i)'s fourth shape (an ORDINARY
  ANNOTATED body-local `const` shadowing a file-level one) was not in the item at all, and the
  destructured-parameter shape belongs to `spineExEnterNode` (the B431 expando anchor) rather
  than the property-access family. (ii) the NESTED composition is closed
  (`cmamBlockScopedPathType`); the DESTRUCTURING one stays open at
  `typeCaptureDestructured`'s VariableDeclaration arm, which is shared with the (API.3d)
  capture channel. (iii) the eleven are really TEN in FIVE groups — see the round note; only
  the `let` binding wakes with the CORRECT type, and it is (CHK.44)'s measured
  3-false-positive population. 14 ablation arms; one leg deleted as dead; one arm's only
  uniquely-its-own failure is a knip ROW. Suite **16,067/0/3**, grid `added=0 removed=0` on
  all eight, `cost_gate` unrebaselined. Pins: `ShadowedReceiverTypeTest` (9),
  `BlockScopedPathReceiverTest` (8).

- [ ] **(CHK.48) THE (CHK.47) LEFTOVERS — one composition, five refusal groups, two
  emissions.** (a) the DESTRUCTURING composition `const c = h; const { inner } = c;
  inner.zzznope`: `typeCaptureDestructured`'s VariableDeclaration arm reads
  `getTypeOfExpression(initializer)` and answers `any`; the helper is SHARED with the (API.3d)
  capture channel, so the substitution must be local to `cmamDestructuredReceiverType` and
  needs a depth guard against `cmamDestructuredReceiverType -> cmamBlockScopedPathType ->
  cmamDestructuredReceiverType`. (b) refusal group 4 — a `let` binding is the ONLY one of the
  ten that wakes with the correct type, and it is exactly (CHK.44)'s 3-false-positive
  population, so it needs the reaching-definition question answered rather than a dropped
  guard. (c) group 1 (a union source, a class instance) needs type CONSTRUCTION — lifting the
  guard yields `Inner` for `Holder | Inner` and `typeof Cls` for `Cls`. (d) an ARRAY-pattern
  binding is typed as a receiver nowhere. (e) TS18048 is not emitted beside our TS2339 for an
  optional destructured member. Grade any attempt on the 8-profile grid AND knip; the standing
  calibration is now knip **49** and grid `added=0 removed=0`.

- [x] **(CHK.47-ORIG) SUPERSEDED — kept verbatim because its (i)/(ii)/(iii) framing is what the
  round corrected. THE THREE (CHK.46) LEFTOVERS, all measured, none a block-scoping gap.**
  (i) **an outer-binding COLLISION defeats the shadow** — a body-local `const { inner } = h`
  under a file-level `const inner: Deep` reports `Deep` for an `Inner`, and a destructured
  parameter named like a file-level function reports `typeof alpha` for a `string`. PRE-EXISTING
  (measured on the (CHK.46) parent binary): `getTypeOfExpression` never answers `anyType` for
  such a name, so `fileLocalTypeMapFor` / `lookupPerFileForNode` win before any receiver helper
  is consulted. It is a wrong MESSAGE where tsc also errors, so grade it on the message and not
  on a count. (ii) **the COMPOSITIONS** — `const c = h; c.inner.zzznope` and
  `const c = h; const { inner } = c; inner.zzznope` are silent because the ROOT answers `any`;
  (CHK.46)(b) substitutes at the Identifier bail, which the nested path does not go through.
  (iii) the eleven pinned REFUSALS, each a false negative tsc reports — the cheapest are
  probably the rest element (its type is the source minus the named members) and the array
  pattern (`typeCaptureDestructured` answers null for a non-object pattern). Grade any attempt
  on the 8-profile grid AND knip; the standing calibration is knip **66** and grid
  `added=0 removed=0`. (d) **a NESTED access whose leaf type is a single OBJECT type** —
  `c.inner.zzznope` is silent for a parameter, a file-level `const` and a body-local alike,
  while the same shape with a UNION leaf reports; the missing piece is a single-Object emission
  for a NON-Identifier receiver, and it needs (CHK.45)'s trust predicate PLUS a narrowing
  decision (an `in` guard ADDS a property, so it must consult the flow or refuse). Largest of
  the three and the most valuable for the language service. (c) **a DESTRUCTURED binding** —
  `const { inner } = h; inner.zzznope` is silent everywhere including for a destructured
  PARAMETER, i.e. a binding-element name is typed as a receiver nowhere; note the
  property-access family currently uses `currentParamBindingNames` as a blanket refusal.
  (b) **an un-annotated BODY-LOCAL** — B83.5 leaves it unbound and no initializer is typed for
  it, in all five initializer forms (a declared const, `new C()`, an object literal, a string
  literal, a single interface). Grade any attempt on the 8-profile grid AND knip; the standing
  calibration is knip **66** and grid `added=0 removed=0`.

- [x] **(CHK.45-ORIG) SUPERSEDED 2026-08-26 by the two entries above — kept verbatim because its
  (a)/(b)/(c)/(d) diagnosis is the thing (CHK.45) corrected. THE FOUR BLOCK-SCOPED RECEIVER POPULATIONS (CHK.44) LEFT SILENT, each measured
  against tsgo 7.0.2 and each a distinct mechanism.** (a) **a member on NO constituent** —
  `const c: A | F = u; c.nope` is decided by the general receiver path, not by
  `cmamCheckUnionReceiverNarrowing`, so it never sees (CHK.44)'s type; this is also why every
  pin in `BlockScopedReceiverTypeTest` reads a member present on SOME constituent, and a
  `.nope` fixture pins nothing. (b) **an UN-ANNOTATED local** — `const c = x; c.nope`, and the
  inferred-`new C()` / string-literal / object-literal forms with it; needs the initializer
  typed under the cpa ambient. (c) **a DESTRUCTURED local** — `const { files } = x;
  files.nope`. (d) **a NESTED access on a block-scoped local** — `c.files.nope`, which exits at
  `cmamCheckNonIdentifierReceiver` and is a different gap again. **And the two REFUSALS above
  are the real prize**: both are the same missing mechanism — narrowing of a block-scoped
  REFERENCE (a truthiness/`??=` guard, a discriminated-union ternary, a type-guard call inside
  a `while` condition's `&&`) — so closing THAT is what makes (a)-(d) and the nullish/non-union
  populations safe at once. Grade any attempt on the 8-profile grid AND knip: the 11+16+3 rows
  are the calibration, and the corpus adds two more (`discriminateWithOptionalProperty4`,
  `narrowingPastLastAssignment`) the moment the type reaches `currentLocalTypes`.

- [x] **(CHK.40) DONE 2026-08-26 — all five gaps closed, and (e)'s diagnosis was WRONG in
  a way that made the fix bigger and better: an `async` function-like whose return type is
  INFERRED returns `Promise<T>`, not `T`.** (e)'s parameters were contextually typed all
  along; the RETURN TYPE was not, in eight places, and the defect is symmetric — one
  seven-shape fixture reads **3 false positives and 4 false negatives**, tsgo reporting
  exactly the complement. (c)'s root was one layer below the TS7006 walker
  (`getTypeOfSymbolWorker` typed a STRING-named method `any`, a residue round 937 named and
  left); (a)/(b)/(d) are one new arm, the contextual type of a `return` POSITION.
  Grid `added=0 removed=0` on all 8 against a rebuilt parent, suite **15,928/0/3**, knip
  **66 -> 66** with every row identical, `cost_gate` PASSES with no rebaseline. Nine ablation
  arms, each with uniquely-its-own failures. **(a)/(b)/(d) are pinned as TS7006 SUPPRESSION
  plus a HOVER and not as a diagnostic, because of (CHK.42) below.**

- [x] **(CHK.42) DONE 2026-08-26 — SHIPPED. A FUNCTION BODY NESTED IN A `return` EXPRESSION IS NOT CHECKED AT ALL —
  the ONE expression position that does not reach `walkFunctionBodiesInExpr`, and the fix
  is TWO LINES that are already measured.** Found and measured during (CHK.40) against an
  obviously wrong `const q: number = "s"` nested one level down: a file-level var-decl
  initializer ✓, a var-decl initializer inside a function body ✓, a CALL ARGUMENT ✓, an
  object-literal property value ✓, `return (node) => {…}` ✗, `return { m(node) {…} }` ✗,
  `return (…)` parenthesised ✗. Neither `ReturnStatement` arm calls the walker — the legacy
  statement walk at `checkTypeAssignabilityInStatements` nor the spine anchor's twin — and
  both are needed for (CHK.39)'s reason (the anchor runs `recordOnly` for a nested statement
  and truncates, so the legacy arm is what EMITS). **MEASURED WITH THE ARM IN: both (CHK.40)
  probes reach FULL PARITY with tsgo 7.0.2 (8/8 and 5/5, exact line:column and message), the
  corpus stays 15,928/0/3, and knip stays 66 with every row identical.** The cost, and the
  only reason it is not shipped: the 8-profile grid gains **3 distinct rows** —
  `checker.ts:10950:25` (which is (CHK.43) below, a SHIPPED false positive the walk merely
  exposes) and `importFixes.ts:1281:17` / `1304:13`, an object literal with `any`-typed
  members reported not assignable to a 2-member union (`FixAddNewImport |
  FixAddJsdocTypeImport | undefined`), UNCHARACTERIZED. So this item is: characterize the
  importFixes pair, fix it and (CHK.43), then land the two lines. Reproduction of the walk's
  own value is one `git diff` — the arm and its positive control are in the (CHK.40) session
  note. **OUTCOME: the importFixes pair was ONE defect and it was ours and SHIPPED — an
  un-annotated parameter whose contextual type cannot be determined was registered nowhere, so
  the deliberately-shadowing callback parameter resolved to the ENCLOSING function's binding.
  Fixed with a `anyType` shadow pre-pass; with it and (CHK.43) the grid is `added=0 removed=0`
  on all eight and the walk is shipped.**

- [x] **(CHK.43) DONE 2026-08-26 — A CHAINED `x as unknown as T` IN A `return` KEEPS THE **INNER**
  ASSERTION'S TYPE WHEN THE RETURN ANNOTATION IS A ≥3-MEMBER UNION — a SHIPPED false
  positive, reachable today at top level.** Four lines:
  `interface A { a: number } interface B { b: number }` +
  `function m4(): B | A | (B|A)[] { const r: any = 0; return r as unknown as B[]; }` reports
  `TS2322: Type 'unknown' is not assignable to type 'B | A | (B | A)[]'`; tsgo 7.0.2 is
  silent. The differential is sharp and already taken: a SINGLE `as B[]` is silent, a
  2-member union target (`B | A`) is silent, a non-union array target (`(B|A)[]`) is silent
  — so the checker takes the INNER `as unknown` and the ≥3-member union is what stops
  something downstream from bailing. It is one of the 3 rows blocking (CHK.42) and it is
  independent of it. **It has nothing to do with type parameters** — its first sighting was
  as an "an outer function's `T` does not resolve in a nested function expression" theory,
  which one probe falsified. **OUTCOME: the trigger is NOT ">= 3 members" but "the target union
  carries an ARRAY member" (`A | (B|A)[]` fires). Root cause: `inferSimpleExprType`'s assertion
  arms fell back to the OPERAND's type whenever `resolveSimpleTypeName` could not render the
  asserted one; for `x as unknown as T` that is the type being asserted away. Both assertion
  spellings fixed; grid `added=0 removed=0` on all eight for this change alone.**

- [ ] **(CHK.36) THE "A CommonJS FILE CANNOT IMPORT AN ES MODULE" FAMILY IS NOT
  IMPLEMENTED AT ALL — TS1479 / TS1471 / TS1286 / TS1203 / TS1202.** Audited during
  (CHK.29): `grep 'code = 1479|1471|1286|1203|1202'` over `commonMain` finds NONE of
  them, so the format decision now being correct opens no new false-positive surface
  from this family — and it is also why a nodenext project's genuine interop errors are
  FALSE NEGATIVES here. Cheap to size: point the (LIB.1) loop at a dual CJS/ESM package
  and diff against `tools/tsgo-7.0.2/lib/tsc`. Note the codes are only reachable once
  (CHK.37) exists, because deciding that an IMPORTED file is ESM is what they test.

- [ ] **(CHK.37) `ModuleResolver` DOES NOT CONDITION `exports`/`imports` ON THE
  IMPORTING FILE'S FORMAT — the `"import"` vs `"require"` condition is unmodelled.**
  Measured during (CHK.29): the resolver reads neither `isESModuleFormat` nor
  `effectiveModule` (one grep, zero hits). For a dual-published package that is not a
  cosmetic difference — it decides WHICH FILE a bare specifier resolves to, so an ESM
  importer can be handed the CommonJS build's `.d.ts` and inherit its whole shape. This
  is the (CHK.29) residue with real blast radius; size it on a library with a
  conditional `exports` map before implementing.

- [ ] **(CHK.38) `esModuleInterop` IS GATED ON THE GLOBAL OPTION AND NEVER ON THE TWO
  FILES' FORMATS.** All 56 `Checker.kt` sites read `options.esModuleInterop`; tsc
  additionally makes a synthetic default available to an ESM file importing a CommonJS
  one under node16/nodenext (`allowSyntheticDefaultImports` is implied by the FORMAT,
  not only by the flag). Blast radius UNMEASURED — recorded during (CHK.29)'s scope
  audit rather than guessed at. It can fail in either direction, so the probe must be a
  default import from a CJS package with the flag OFF and the importer ESM.

- [x] **(LIB.2) ANSWERED 2026-08-22 BY (LIB.3)'s SCREEN — and the screen added a second
  criterion the entry did not predict: the library closest to COMPILING and the library best
  for BENCHMARKING are different ones. ORIGINAL ENTRY: THE NEXT LIBRARY MUST BE PICKED BY
  WHAT IT *IMPORTS*, NOT BY ITS SIZE —
  knip cost a session to learn that.** (LIB.1)'s method is right and cheap (two commands,
  ~10 s) but it was pointed at a library the backend can never reach, because the
  disqualifier is not a language construct: **native N-API dependencies and `node:` builtins
  have nothing to lower TO.** Before adopting a candidate, census its non-relative imports
  first — `grep -rhoE "from '[^.'][^']*'"` over `src` answers in one second — and refuse
  anything importing a `.node` binary or a `node:` builtin outside a table we intend to
  write. `yaml` (76 files, no dependencies) is still the right second conformance corpus for
  the FRONT end, and `docs/kir-library-readiness.md` records it moving 80 -> 24 purely from
  defects other libraries exposed. For the BACKEND ladder the candidate wants to be pure
  computation over data — a parser, a formatter, a codec — which is exactly why `mitt` and
  `smol-toml` worked.

- [x] **(LIB.3) SIX CANDIDATE CLI LIBRARIES SCREENED AND THEIR ERRORS ROOT-CAUSED —
  2026-08-22. 126 false positives over four libraries, and FIVE families carry 67 of them.**
  This is (LIB.2)'s screen, executed. All six are TS-source with a CLI; the import census
  disqualified `sql-formatter` (imports `nearley` inside `src`) before any compiler ran.
  Measured with `@types/node` present on both sides, each library's OWN tsconfig (marked's
  minus `verbatimModuleSyntax`, since (CHK.29) already owns that), diffed against tsgo 7.0.2
  per `(file, line, code)`:

  | library | files | lines | deps | tsgo | xtsc | ours-only | refused-construct files |
  |---|---|---|---|---|---|---|---|
  | **cronstrue** | 52 | 8,812 | none | **0** | **0** | **0** | **2 (3%)** |
  | marked | 13 | 3,706 | none | 0 | 15 | 15 | 10 (76%) |
  | jsonrepair | 10 | 2,746 | none | 1 | 16 | 16 | 9 (90%) |
  | fflate | 3 | 3,904 | none | 2 | 17 | 17 | 3 (100%) |
  | yaml | 78 | 10,878 | none | 0 | 78 | 78 | — |

  **THE OURS-ONLY HISTOGRAM (126): TS9008×19, TS2322×14, TS2345×13, TS9023×11, TS2391×9,
  TS2554×8, TS2339×7, TS2591×6, TS2683×4, TS6196×2, TS2366×2, then twelve codes at 1.**
  The five root-caused families are (CHK.31)-(CHK.35) below, in the order their blast radius
  justifies. **THE TAIL IS NOT ROOT-CAUSED AND MUST NOT BE QUOTED AS IF IT WERE**: ~59 rows
  remain, led by TS2322×14 (of which SIX are one shape, `SourceToken | undefined` against
  `SourceToken | null` in `yaml/compose/resolve-props.ts` — an excess `undefined` we add and
  tsgo does not) and TS2339×7. Captures for every row are reproducible in ~10 s per library
  by the (LIB.1) commands.
  **THE RANKING LESSON, WHICH IS NOT THE ONE (LIB.2) PREDICTED: the library closest to
  COMPILING and the library best for BENCHMARKING are different libraries.** `cronstrue` is
  the only one the checker already passes and the only one whose lowering runs — but each of
  its calls is small work, so it benchmarks as a loop over many expressions rather than as one
  heavy invocation. `marked` (markdown -> HTML over a large document) is the workload worth
  publishing a number for, and is 15 checker errors plus a 76%-of-files backend gap away.
  `fflate` would be the best number of all — DEFLATE is tight numeric loops, where a JVM
  should beat Node outright — and is **structurally blocked**: 183 typed-array uses
  (`Uint8Array`×167) against a runtime with none, plus 14 `Worker` references. Do not start
  there; revisit after typed arrays exist.

- [x] **(LIB.4 — the LOWERING half DONE 2026-08-28) `cronstrue` COMPILES TO JVM BYTECODE; WHAT
  STOPS IT RUNNING IS THE NOMINAL HALF.** Its English entry point (11 files, published source
  unmodified) reads `successful=true` with the checker at **0 errors, agreeing with tsgo 7.0.2
  exactly**, and then fails at RUN time on one thing, twice: `Can not set JsObject field
  ExpressionDescriptor.i18n to program.en` — a generated CLASS instance cannot flow into an
  INTERFACE-typed slot. See (LIB.6). **THE QUEUE'S FIVE RUNGS WERE HALF THE LADDER: thirteen
  capabilities were needed** (corpus 17-29), and the reason the list was short is that the
  earlier session peeled it *by patching a throwaway copy*, which walks past whatever the patch
  removed — re-probing the UNMODIFIED library after each fix is what found the other eight.
  `docs/kir-library-readiness.md` § "UPDATE 2026-08-28" has the table and the five defects the
  arc surfaced, four of them silent wrong answers invisible to every gate in this repo.

- [ ] **(LIB.6) THE NOMINAL HALF — A CLASS INSTANCE CANNOT REACH AN INTERFACE-TYPED SLOT, AND IT
  IS THE ONLY THING BETWEEN `cronstrue` AND A RUNNING PROGRAM.** An `interface` erases to the
  property bag and a `class` is a nominal JVM class, so `i18n: Locale = new en()` fails at run
  time with an `IllegalArgumentException` from `reflectiveSet`. `docs/kir-structural-typing.md`
  already MEASURED the plan — candidate (1), each interface a JVM interface and each class
  implementing every interface it is structurally assignable to, **158 closure edges on tsc's own
  sources, max fan-out 9** — and it was never built because § 7 priced the dynamic half at 12x
  and it was taken first. **A cheaper shape exists and should be priced against it before
  starting**: make a generated class EXTEND `JsObject` (it is `open`, has a no-arg constructor,
  and the shape classes already do exactly this) and route a bag-receiver METHOD call through
  `jsInvoke`, whose reflective fallback already finds a real JVM method. That is two changes
  rather than a whole-program closure, and it changes what `instanceof` and the spill machinery
  see — which is why it is a decision rather than a rung.

- [ ] **(LIB.7) A NAMESPACE IMPORT HAS NO RUNTIME OBJECT — `import * as ns from "./m"` refuses
  with `cannot lower the reference 'ns'`.** `cronstrue`'s ALL-LOCALES entry point
  (`cronstrue-i18n.ts`) needs it: `allLocalesLoader.ts` does `for (var property in allLocales)`
  and `new (allLocales as any)[property]()`. The English entry point does not, which is why
  (LIB.4) got past it. Needs a module NAMESPACE object — a `JsObject` whose properties are the
  module's exports — built once per imported module and reachable as a value.

- [ ] **(CHK.69) AN ASSIGNMENT *BEFORE* A `var`'s DECLARATION DOES NOT COUNT TOWARD DEFINITE
  ASSIGNMENT — a two-function repro, ours-only against tsgo 7.0.2.**
  ```ts
  export function assignedBeforeDeclaration(): number {
    probe = 7;
    var probe: number;
    return probe;      // TS2454 here, tsgo silent
  }
  export function assignedAfterDeclaration(): number {
    var other: number; other = 7; return other;   // silent BOTH sides — the control
  }
  ```
  The mirror is silent, so it is that direction specifically. `var` has no TDZ, so an assignment
  above the declaration is ordinary and the binding is definitely assigned at the `return`.
  Found while writing corpus 18, which had to route around it.

- [x] **(CHK.31 — DONE, round (CHK.31)) `// @ts-ignore` AND `// @ts-expect-error` DO NOT SUPPRESS ANYTHING — MEASURED
  IN BOTH DIRECTIONS, AND THIS IS THE HIGHEST-BLAST-RADIUS ITEM IN THE SCREEN.** A four-file
  repro settles it: `// @ts-ignore` above a TS2322 leaves the TS2322 emitted, `// @ts-expect-error`
  likewise, and an `@ts-expect-error` above a line with NO error fails to produce tsgo's
  **TS2578 `Unused '@ts-expect-error' directive`** — so we are wrong in both directions at once.
  On `fflate` this is **all 9 TS2391 rows** (`Function implementation is missing`), and the
  correspondence is exact: `src/index.ts` contains exactly 9 `@ts-ignore` comments, one above
  each declaration-only class member the library deliberately suppresses.
  **THE TRAP IS THAT IT LOOKS ALREADY DONE**: `CompilerOptions.kt:562` parses both spellings as
  comment directives, and `Checker.kt:16167` consults one for a narrow node/commonjs
  suppression, so a grep says the feature exists. It is not a general diagnostic filter.
  **What the fix needs, beyond the filter itself:** the directive attaches to the NEXT line, so
  it wants the leading-comment channel the parser already records (`NodeBase.leadingComments`)
  rather than a source scan; `@ts-expect-error` must additionally RECORD whether it suppressed
  anything and emit TS2578 when it did not; and a file-level `// @ts-nocheck` is a third
  spelling with **zero** hits in `commonMain` today. **Corpus risk is real and must be measured
  before landing**: any baseline whose fixture carries one of these directives currently records
  the UNSUPPRESSED diagnostics, so run the 8-profile grid and the corpus, and expect the
  `logicalParityDivergence` mechanism to be the wrong tool — a suppressed diagnostic is a
  MEANING change, not a form one.

- [x] **(CHK.32) LANDED 2026-08-26 — the ANONYMOUS half. A PRIMITIVE SOURCE IS NOT RELATED TO A STRUCTURAL OBJECT TARGET THROUGH ITS
  APPARENT TYPE — 13 TS2345 ROWS, AND IT GENERALISES BEYOND `string`.** `jsonrepair` types its
  whole scanner against `interface Text { length: number; charAt(i): string; charCodeAt(i): number;
  substring(s, e?): string }` and passes a `string` to it; every one of its 7 TS2345 rows is that
  call. Minimal repro, both halves failing where tsgo is silent:
  ```ts
  declare function isWhitespace(text: Text, index: number): boolean
  export function viaString(s: string) { return isWhitespace(s, 0) }        // TS2345, tsgo silent
  declare function wantsToFixed(x: { toFixed(d?: number): string }): string
  export function viaNumber(n: number) { return wantsToFixed(n) }           // TS2345, tsgo silent
  ```
  The control in the same file — an object source against `{ length: number }` — passes, so the
  defect is specifically the PRIMITIVE side: relating `string`/`number` to an object type must
  go through `getApparentType` (the `String`/`Number` wrapper interface), which the relation is
  not consulting on this path. `getApparentType` already exists and CLAUDE.md records it as the
  way to reach a primitive's members, so this is a missing consult rather than missing
  machinery. Check the mirror direction while you are there (an apparent-typed source in a
  RETURN position, and `boolean`/`symbol`/`bigint`), and note the fix is in the RELATION, so
  the corpus is the gate.
  **OUTCOME.** The NAMED-interface half was already working (a round-B69.8 leg has handled
  `target is Type.Interface` all along); the gap is the ANONYMOUS target, and it is closed in
  every direction the item names — a 14-row matrix over primitive x target-shape x position
  had 8 ours-only rows against tsgo 7.0.2 and now agrees row for row.
  **THE `jsonrepair` ATTRIBUTION IS WITHDRAWN**: measured before and after with rebuilt arms,
  that library reads **11 -> 11 rows, byte-identical**, and its 7 TS2345 are the DOM `Text`
  name collision now queued as (CHK.49). `PrimitiveApparentTypeRelationTest` (20 pins),
  suite 16,087 / 0 / 3, `output.errors` 46, grid `added=0 removed=0` on all eight.

- [x] **(CHK.49) DONE 2026-08-26 — A MODULE-LOCAL DECLARATION OF A LIB GLOBAL NAME WAS
  MERGED *INTO* THE LIB SYMBOL, PROGRAM-WIDE AND IN BOTH DIRECTIONS.** `mergeSingleSymbol`
  ADOPTS, so the merge mutated the LIB symbol and EVERY file saw the fusion — not only the
  declaring one. Fixed by dropping the lib key set from BOTH `init:mergeSharedKeepNames` and
  `computePerFileVisibility`'s `nonModuleVisible` (one observable: the merge retire alone is
  **969** compiler-profile errors, the visibility half alone is inert, together **46**), plus
  a VALUE second chance for the meaning a TYPE-only shadow does not hide, plus node-keying
  `resolveHeritageBaseSymbol`'s Identifier root. **`jsonrepair` 11 -> 4**; suite
  16,101 / 0 / 3, zero corpus baselines moved, grid `added=0 removed=0` on all eight.
  The item's "it is `interface`-SPECIFIC — a `type` alias is correct" was measured WRONG:
  all five declaration forms collide. `LibGlobalNameShadowTest` (14 pins). See the session
  note for the population census and the ten-arm ablation.

- [x] **(CHK.50) DONE 2026-08-26 — THE CARRIER MERGED AND THE CONTENTS DID NOT, AND
  **SEVEN OF EIGHT** DECLARATION FORMS WERE WRONG.** `declare global` parses as a
  ModuleDeclaration named `global`, so step 1 merged the carrier symbol and nothing merged
  its `exports`. The item's "the `var` form works, so the value half is fine" is measured
  WRONG: `var` was correct only in the DECLARING file (cross-file it was silently `any`), and
  `function`/`namespace`/`class` were `any` in both scopes — TS2304-suppressed by
  `globalAugmentationNames` and typed by nothing, which is the dangerous direction. Fixed by
  `init:mergeGlobalAugmentations` (legality mirrors `spineCheckGlobalAugmentation`'s TS2669
  predicate; a global-SCRIPT block contributes nothing, as in tsgo) plus a `buildPerFileScopes`
  seed of the ADOPTED names, an ambient-BY-CONTEXT implicit-export rule for
  `declare global { namespace NodeJS { … } }`, and a `globalThis` refusal ((CHK.53)).
  **(CHK.51)'s named cost is PAID** — `globalAugmentedInterfaceNames` deleted, `el.zzzNotThere`
  on an augmented `HTMLElement` now TS2339 as tsgo says. Both matrices match tsgo row for row;
  `DeclareGlobalAugmentationTest` (11 pins), suite 16,118 / 0 / 3, `output.errors` 46, grid
  `added=0 removed=0` on all eight, **jsonrepair 4 -> 4 byte-identical, knip 49 -> 54** (one
  genuine fix, six pre-existing overload rows that `any` had been hiding — (CHK.54)). See the
  session note for the eight-form census and the ten-arm ablation.

- [x] **(CHK.55) DONE 2026-08-27 — (b) AND THE "THIRD, SEPARATE ROW" ARE **ONE
  MECHANISM** AT TWO CALL SITES; (a) IS DELIBERATELY LEFT OPEN AS (CHK.56).**
  `getTypeOfExpression` widens an object literal's literal-valued properties, so a target
  property with a literal type rejects. At `allArgumentsMatch` (the DIAGNOSTIC path) round
  728's rescue existed but refused an INTERFACE with heritage and a UNION with >1 non-nullish
  constituent — a false TS2769, and `knip`'s last overload row; at `signatureAcceptsArgs`
  (SELECTION) there was **no rescue at all**, so `resolveCallOverload`'s `arityMatches[0]`
  fallback answered — matrix row H, a wrong TYPE with no diagnostic anywhere. One fixture
  (`readFileSync(p, { encoding: 'utf8' })`) shows both at once, which is what identifies
  them as one. The heritage refusal was never necessary: `resolveInterfaceMembersCore`
  folds base members into the derived type's own `members`/`properties`. A THIRD
  interaction was found by trying to falsify an ablation arm — for a union parameter the
  relation SUCCEEDS through a weak constituent, so the rejecting path where the rescue
  lives is never taken and (CHK.54)'s weak rule refuses without ever asking about the other
  constituent; the weak refusal is now guarded by the rescue. `OverloadObjectLiteralParamTest`
  (11 pins), suite **16,144 / 0 / 3**, no baseline moved, `output.errors` 46, grid
  `added=0 removed=0` on all eight, **knip 49 -> 48** (exactly `src/util/git.ts:17:55`),
  jsonrepair 4 -> 4 byte-identical. See the session note for the 10-row matrix and the
  seven-arm ablation, including the arm that reads 0 RED and the KDoc claim it retracts.

- [x] **(CHK.56) DONE 2026-08-27 — THE SUBLINE WAS THE EASY HALF AND THE "WHICH OVERLOAD"
  HALF WAS A **tsgo RENDERING**, NOT tsc's.** `allArgumentsMatch` now asks the weak rule
  (opt-in `applyWeakRule`, so only the overload-MATCH loop does and the four TS2793
  implementation-signature gates are untouched), and all four overload arg-check helpers
  move together or the chain names an overload the match loop thought fine. The item read
  the elaboration as the work: correct that tsc's subline is TS2559's *no properties in
  common* wording rather than an assignability line, and it is minted beside the existing
  walk on the path where the relation SUCCEEDED — but the `The last overload gave the
  following error.` framing it recorded is tsgo's, printed at 2, 3 and 4 candidates alike,
  where PRISTINE tsc prints `Overload N of M, '<sig>', gave the following error.` per
  candidate (42 baselines against 4, and `tsxStatelessFunctionComponentOverload4` carries
  a *no properties in common* subline inside exactly that chain). Our chain has had the
  pristine shape since B418, so **no "which overload" policy was needed** and the item's
  own wrong-overload risk never arose. Two rules measured rather than guessed: a UNION
  parameter names a CONSTITUENT only when exactly one survives dropping `null`/`undefined`
  (two or more take the assignability wording naming the whole union), and an
  OBJECT-LITERAL argument is refused outright because tsc's freshness/excess check
  pre-empts the weak one and squiggles the offending property. `weakParamRefusesArg` was
  indeed the ready-made predicate. **It ADDED no row anywhere**: 8-profile grid capture
  md5 `503774c2…` (byte-identical to (CHK.54)/(CHK.55)), knip 48 -> 48, jsonrepair 4 -> 4.
  `OverloadWeakParamDiagnosticTest` (11 pins, every position and message asserted as tsc's
  own value), suite **16,155 / 0 / 3**, `output.errors` 46. The measured residue — the
  weak rule does not distribute over a UNION target in the B482 walkers — is (CHK.57).

- [x] **(CHK.57) DONE 2026-08-27 — THE WEAK RULE NOW DISTRIBUTES OVER A **UNION** TARGET IN
  BOTH WALKER POSITIONS, AND THE ITEM'S OWN TWO-CONSTITUENT EXAMPLE WAS A **DEAD** ABLATION
  ARM.** [Checker.weakUnionRefusalConstituent] composes the two helpers this entry named and
  is wired into the single-signature CALL argument site and
  [Checker.tryEmitTopLevelWeakVarDecl] as a branch DISJOINT from the bare-target one, so the
  bare path is byte-identical. Both measured shapes now match tsc 7.0.2 exactly — code,
  message, line and column — as do the `| undefined`, interface-, alias- and
  `Partial<…>`-constituent, non-fresh-object-source and REST-parameter variants.
  **Three shapes refuse deliberately, each measured**: two or more non-nullish constituents
  (tsc's TS2345/TS2322 naming the whole union needs the RELATION to reject); an
  object-literal ARGUMENT ((CHK.56)'s boundary — tsc's excess check squiggles the property
  two columns right); and a CALLABLE source, because our TS2559/TS2560 split is wrong at the
  BARE target and distributing would have inherited a wrong-CODE row. **The entry's "it ADDS
  rows … expect it to fire on real code" is measured FALSE**: knip 48 -> 48 and jsonrepair
  4 -> 4 byte-identical, grid md5 `503774c2…` unmoved on all eight, `output.errors` 46 — and
  (CHK.54) is why, since SELECTION already refuses these signatures, so `readFileSync` picks
  the `string` overload and the argument site never asks. Suite **16,169 / 0 / 3**, no corpus
  baseline moved. `WeakUnionTargetDiagnosticTest` (14 pins). Residue queued as (CHK.58); see
  the session note for the seven-arm ablation and the two arms that read 0.

- [x] **(CHK.58) DONE 2026-08-27 — FOUR OF THE SIX CLOSED, AND THE ORACLE OVERRULED THE
  ENTRY ON A FIFTH.** (1) The **RETURN and ASSIGNMENT** positions had no weak walker at all:
  twelve tsc rows that were missing now land byte-exact and the one row the return position
  had (TS2322 naming the whole union) is corrected to TS2559 naming the constituent. The
  anchors were corroborated by PRISTINE, not taken from tsgo — a return squiggles the
  `return` KEYWORD (`~~~~~~`), an assignment the LHS REFERENCE (one `~` under the `c` of
  `c = d` in `assignmentCompatWithObjectMembersOptionality2.errors.txt`). (2) **TS2560 is
  "calling it would have worked", not "the source is callable"** — four of six callable
  shapes carried the wrong code, and **the relation asked must carry the WEAK RULE ITSELF**,
  since tsc's weak check lives inside `isRelatedTo` and ours does not. (4) The **enum
  display** is `E.A` for a multi-member enum and `E` for a one-member one — **one rule, and
  the queue's "our display is wrong" reading was half wrong: at the position the corpus
  tests, the old answer was RIGHT**, because a one-member enum's literal type IS the enum
  type. (5a) A **`new C()` var-decl initializer** is now a source, so the var-decl and
  argument positions refuse the same things. Suite **16,199 / 0 / 3** (+30, four new
  classes), **no corpus baseline moved** — load-bearing, since three of the four fixes
  change an existing row. `output.errors` **46**, cost gate exit 0 unrebaselined (largest
  counter **+1.40%**; the FIRST implementation measured +6.89% `typeOfExpr.calls` for
  byte-identical output — order is a cost decision), grid md5 `503774c2…` unmoved on all
  eight, `partition` EQUIVALENT/78, `capture` 1,005 / 43 of 76 / moreAny 0, **knip 48 -> 48
  and jsonrepair 4 -> 4 byte-identical**. Twelve ablation arms; five read 0 and each is a
  DIFFERENT kind of zero (provably-unobservable, redundant, undiscriminated, DEAD ×2) —
  see the session note. Residue re-queued as (CHK.59).

- [x] **(CHK.59) DONE 2026-08-27 — THREE OF FIVE CLOSED; THE ANCHOR RULE IS "TS2560 MOVES TO
  THE EXPRESSION", AND (CHK.58)'S DIAGNOSIS OF THE ENUM HOLE WAS WRONG IN A WAY THAT MATTERED.**
  (1) The CALLABLE source at the var-decl / return / assignment positions is closed: tsc's
  `elaborateDidYouMeanToCallOrConstruct` re-reports at the EXPRESSION exactly when the call
  result is related to the target, which is the SAME predicate
  [Checker.weakCallResultSatisfiesTarget] already used to pick TS2560 — so the emitter needed
  one extra CALL-ONLY anchor and nothing else. The var-decl position additionally gained a
  fallback to the shared value walker (an IDENTIFIER or ARROW source was silent there and
  reported at the other two). A FUNCTION EXPRESSION stays refused, measured: tsc anchors one
  at its own NAME. (2) The enum member is closed at all four positions — and NOT because
  `getTypeOfExpression` answers `any` (it does not): an enum type is a member-LESS
  [Type.Object], so it enumerated to the EMPTY set and the vacuous-`{}` guard refused it.
  (4) The nested object-literal leaf is closed, as TWO defects: the walker ORDER, and the
  WIDENING of a string/numeric leaf (a boolean leaf and the top-level position do not widen).
  Suite **16,223 / 0 / 3** (+24), **no corpus baseline moved**, `output.errors` 46, grid
  `503774c2…` unmoved, knip 48 -> 48 and jsonrepair 4 -> 4 byte-identical, ten ablation arms
  and not one read 0. Items 3, 5 and 6 are re-queued with three new residues as (CHK.60).

- [x] **(CHK.60) PARTLY DONE 2026-08-27 — ITEM 6 (THE ENUM FALSE POSITIVE) CLOSED AND ITEM 4
  MAPPED; ITEMS 1, 2, 3, 5, 7 RE-QUEUED AS (CHK.61).** An enum MEMBER is a string or number
  LITERAL in tsc, so its apparent type is the `String`/`Number` wrapper; (REL.1)(b)'s
  member-LESS `Type.Object` made `propertiesRelatedTo` reject **every** target declaring a
  property, weak or not. `structuredTypeRelatedTo`'s object/object leg now retries an
  enum-literal source as its apparent PRIMITIVE **after** the structural comparison has
  answered false, which routes it through the legs a `string`/`number` source already takes.
  **13 ours-only rows removed** over a 30-row matrix against tsc 7.0.2; suite 16,234 / 0 / 3,
  no baseline moved, all 20 cost counters digit-identical to the rebuilt parent, grid
  `503774c2…` unmoved, knip 48 -> 48 and jsonrepair 4 -> 4 byte-identical, six ablation arms.
  Item 4 (`this.<member>`) was MEASURED rather than fixed and the queue's own diagnosis
  corrected — see the session note and (CHK.61) below.
  ORIGINAL ENTRY: **THE WEAK-TYPE RESIDUE AFTER (CHK.59) — TWO INHERITED, ONE DELIBERATE,
  THREE NEW, ALL MEASURED.** Fixtures under `build/chk59/ora`, `pin`, `dbg`.
  1. **TWO OR MORE NON-NULLISH CONSTITUENTS** — unchanged since (CHK.56) and still a different
     mechanism: tsc words them as ordinary assignability naming the WHOLE union, which needs
     the RELATION to reject where the weak rule lives in the walkers. **A second, separate hole
     is beside it**: at the ASSIGNMENT position we are silent for that shape altogether
     (`build/chk58/pinora/q16.ts(3,1)` and `q13.ts(3,1)`), which is the ordinary assignability
     walk. Price it before starting.
  2. **A FRESH OBJECT LITERAL AGAINST A BARE WEAK ARGUMENT IS TS2559 HERE AND TS2353 IN tsc**
     ((CHK.56) row r3) — ARGUMENT-ONLY; the return and assignment positions already match tsc
     (TS2353 at the property, both spans pinned). Closing it is what would let the
     object-literal refusals in (CHK.56)/(CHK.57)/(CHK.58)/(CHK.59) be dropped.
  3. **A GENERIC INSTANTIATION SOURCE IS SILENT IN EVERY POSITION** (`build/chk58/ora4/y7.ts`
     `(3,23)` and `(4,7)`, naming `ZzzG7<number>`). [Checker.weakSourcePropertyNames] answers
     null for a [Type.Reference] BY DESIGN — its members are lazy and a missed property is a
     FALSE TS2559 — so this is a deliberate conservatism to be RE-PRICED, and it is SYMMETRIC
     across positions, which is what makes it safe to leave. Do not break the symmetry.
  4. **NEW — A `this.<member>` ASSIGNMENT TARGET IS SILENT FOR THE WEAK RULE AT EVERY SOURCE
     SHAPE**, callable and not (`build/chk59/dbg/d1.ts`: tsc reports `(2,62)` for an arrow and
     `(3,44)` for a plain `number`). Not the anchor change and not the weak rule:
     [Checker.getTypeOfExpression] answers `any` for `this.<optional member>` — the probe
     `const p: string = this.zzzHandler` is SILENT here where tsc says `Type 'ZzzS9 |
     undefined' is not assignable to type 'string'`. That is a receiver-typing hole with a
     surface far wider than TS2559. `WeakCallableSourceAnchorTest`'s refusal pin records it.
  5. **NEW — AN OPTIONAL `any` PROPERTY RENDERS `p?: any | undefined` WHERE tsc RENDERS
     `p?: any`.** `any` ABSORBS `undefined` in tsc's union construction and our
     [Checker.getUnionType] does not reduce that pair, so it is a [Checker.typeToString]
     divergence reachable from every position that renders a target through the TYPE rather
     than the ANNOTATION (a var-decl row is byte-exact only because its walker renders
     `formatTypeForDisplay(ann)`). Union member text is pinned byte-for-byte across ~13k
     baselines, so this is a LOGICAL-PARITY conversation, not a display tweak.
     `WeakEnumSourceDisplayTest`'s residue pin records it.
  6. **NEW — AN ENUM MEMBER AGAINST A WEAK TARGET IT *SHARES* A PROPERTY WITH IS SILENT IN tsc
     AND EMITS TS2345/TS2322 HERE** (`build/chk59/pin/qc.ts`: `zzzQ0Cg(ZzzQ0C.A)` against
     `{ length?: number }`). The weak rule correctly declines both; what fires is the ORDINARY
     relation, which does not relate a string-enum member to an object target through its
     `String` apparent type. This is an FP class, not a missing row — sequence it above 1-3.
  7. **A BIGINT LEAF** (`{ zzzIn: 12n }`) still falls through to TS2322 where tsc reports
     TS2559 `Type 'bigint'` at the key — [Checker.weakSourcePropertyNames]'s `BigIntLike` arm
     does not resolve to an object here. One line, deliberately not taken this round.

- [x] **(CHK.62b) DONE 2026-08-27 — AN ASSIGNMENT WHOSE RHS IS A `this`-METHOD CALL DID
  NOT NARROW THE ASSIGNED REFERENCE, AND IT WAS A **SHIPPED** DEFECT, NOT A PATCH ARTEFACT.**
  `rhsIsDefinitelyNonNullish`'s CALL arm resolves the callee through
  `resolvePropertyMethodDecl`, which TYPES THE RECEIVER and bails at `recvType === anyType`;
  `thisReceiverCarrierType` supplies `currentClassForThis`. The entry's "invisible without
  patch_a" is true only of `build/chk62/g2k`, whose declared unions all come from
  `this.zzzFind()` — `let p = zzzFindFree(); p ??= this.zzzCreate(); return { p }` reproduces
  on the shipped binary. Took (a) from 3 rows to **1**. `ThisMethodCallAssignmentNarrowTest`.
  RESIDUE: a PROPERTY-access RHS (`p ??= o.zzzFld`) still does not narrow — measured NOT
  `this`-shaped (`zzzObj.zzzFld` fails identically), so it is a separate item.

- [x] **(CHK.61)(b) PARTLY DONE 2026-08-27 — THE **DISPLAY** HALF LANDED; THE **CHECKING**
  HALF IS REFUSED WITH ITS PRICE MEASURED, AND WHAT IT UNCOVERED IS RE-QUEUED AS (CHK.63)
  AND (CHK.64).** An optional member's hover now carries `| undefined` and then RE-NARROWS
  (`typeCaptureOptionalMemberType`, `memberIsOptionalOnReceiver`), at tsc 7.0.2's own LSP
  answers, including a UNION receiver decided PER CONSTITUENT. Confined to the CAPTURE, so
  every diagnostic gate is byte-identical and only `capture-equivalence` sees it (DIVERGED
  1,005 -> 968, all 38 moved spans classified as the alias-display first-wins family).
  RESIDUE, pinned with the value we answer: `super.<opt>` and an INTERSECTION receiver both
  read `number` where tsc reads `number | undefined`.
  **THE CHECKING HALF IS REFUSED AND THE "3 rows" IN THE OLD ENTRY WAS THE WRONG ARM.**
  `build/chk61/patch_b.py` alone DELETES a true positive (`const a: string = o.optNum`
  reports `Type 'number' …` on the shipped binary and NOTHING with it), because the source
  becomes a nullish union and `canUseTypeEngine` refuses those against a primitive target.
  Measured this round on the 8 profiles against a parent capture taken in the same session:
  the gate opened ALONE is **11** ours-only rows, patch_b **and** the gate is **15** (of
  which patch_b FIXES two of the gate's own — `emitter.ts:1479`,
  `organizeImports.ts:862`). `armBG` reproduces tsc EXACTLY on the four-line repro. The
  five narrowing gaps are FIVE mechanisms, not one, and every one reproduces on the SHIPPED
  binary with an EXPLICIT `| undefined` member — they are (CHK.64), and the gate is
  (CHK.63). Re-open (b)'s checking half only after those.

- [x] **(CHK.65) DONE 2026-08-28 — A DOMAIN OF EXACTLY ONE LITERAL, MINUS THAT LITERAL,
  IS **EMPTY**; A SECOND `!== undefined` GUARD ON THE SAME PROPERTY PATH DID NOT NARROW,
  AT TWO READERS, AND IT WAS SHIPPED.** [Checker.narrowUnionByLiteral]'s NON-union
  `keep = false` arm answered its input unchanged — right for an INFINITE primitive
  domain, wrong when the input IS the literal being subtracted, which is exactly what a
  preceding guard's ELSE branch leaves on a property path. An IDENTIFIER subject goes
  through the M1.9 if-arm machinery and was always correct, which is what hid it. The
  second reader is the ARITHMETIC/RELATIONAL operand one ([Checker.arithOperandType]'s
  flow consult is gated on a UNION base and refuses a `never` answer);
  [Checker.operandFlowNarrowsToNever] must CLAIM the operand or the TS18048 merely
  becomes a TS2365. `ASecondGuardOnAPropertyPathNarrowsAgainTest` (7 positives naming
  their reader, 6 controls). Grid byte-identical, suite +13, no baseline moved. It also
  removes the gate's `checker.ts:30269` row — see (CHK.63).

- [x] **(CHK.70)(a) DONE 2026-08-28 (`2ed1779b`) — AND IT WAS *NOT* THE GATE'S LAST ROW.**
  Landed as the ORDER-FREE rule (EVERY assignment reachable backward from a back edge is a
  non-nullish compound one), which is what keeps it on tsc's side of the compound arm's own
  antecedent-base-type rule; five shipped false positives, tsgo-confirmed. Rebuilding the
  combined arm on top of it left `harness/tsserverLogger.ts:28:5` UNMOVED — that row was
  (CHK.70)(c), the LITERAL arm of `narrowByAssignmentRhs` (`acb6d92b`). The original text
  is kept below because its DESIGN was right and its ATTRIBUTION was wrong.
- [ ] **(CHK.70)(b) IS STILL OPEN — an IDENTIFIER subject's narrow is loop-blind at the
  DECLARATION reader with a PRIMITIVE target.** Unchanged by this round: the gate opened the
  RETURN and ASSIGNMENT readers, not the declaration one's `currentLocalTypes` path.
- [x] **(CHK.71)(b) DONE 2026-08-28 — THE BLOCKER WAS NOT B83.5 BUT A *FOURTH* SHADOW
  SHAPE, AND IT LANDS ON ITS OWN.** A BLOCK-scoped declaration inside a NESTED function
  shadowing an ENCLOSING FUNCTION's local was covered by none of round 351
  ([Checker.applyBodyLocalShadowing], top-level decls), round 460
  ([Checker.applyAmbiguousBlockScopedLocals], two decls in one body) or round 455
  ([Checker.applyNestedGlobalShadow], a GLOBAL/file-level collision) — whose condition is
  literally `outerBound && !currentLocalTypes.containsKey(nm)`, i.e. the inherited case
  inverted. A shipped ours-only TS2322 at every assignment to the inner name, reported
  against the WRONG declaration's type, with no optional chain anywhere near it; twelve
  lines reproduce it and tsgo 7.0.2 is silent. `added=0 removed=0`, suite 16,422/0/3,
  cost_gate exit 0, knip 49 / jsonrepair 4 unchanged.
- [ ] **(CHK.71)(a) THE OPTIONAL-CHAIN RECEIVER HALF — RE-DERIVED, RE-MEASURED, REFUSED
  AGAIN, ON A *DIFFERENT* ROW.** `a?.b` looks its member up on `a` WITH its nullish
  constituents, so every optional chain over a `T | undefined` receiver answers `any`; the
  fix is an `optionalChainReceiverType` strip in `computeRawTypeOfPropertyAccess` and
  `getTypeOfElementAccess` (four lines plus a helper — scratch only, re-derive it; a copy of
  the measured tree is `build/chk71/Checker-both.kt`). **The two `moduleNameResolver.ts`
  rows that refused it last round are GONE** — they were (CHK.71)(b) — and with both halves
  the 8-profile grid is `added=0 removed=0` at the standing digest. What refuses it now:
  **knip 49 -> 50**, an ours-only FP at `compilers/compilers.ts:60:49` (TS18047
  `'match' is possibly 'null'` in `return match?.[1] ? [\`… ${match[1]} …\`] : []`) — tsc
  narrows a receiver to non-null in the TRUE branch of a truthy test on an optional chain
  and we do not, which was invisible while `match?.[1]` answered `any`. **So the blocker is
  now OPTIONAL-CHAIN TRUTHINESS NARROWING, a nameable and reducible mechanism, not B83.5.**
  Second, smaller cost: the capture channel gains **236 definitions** in both arms and
  exactly one is order-dependent (`resolutionCache.ts @39543..39549`, present in FULL and
  absent in NARROW), taking `capture-equivalence.sh`'s standing `definitions=0` to 1 — the
  (INC.2) first-touch family, in a population that did not exist before. The RESULT half
  (`a?.b` is `typeof a.b | undefined`) is still a separate, much larger change.
- [ ] **(CHK.73) — DIAGNOSED AND PRICED 2026-08-29, AND IT IS NOT WHAT THIS ENTRY SAID.
  THE BLOCKER IS THE STATIC SIDE OF A CLASS, NOT RESOLUTION, AND THE ROUND-409 TS2315
  HAZARD IS NOT IN PLAY.** Built against a probe project with a REAL `@types/node`
  (`npm i @types/node@20` under `tools/node/bin` — the bench profile's `@types` directory
  is EMPTY, so CLAUDE.md's "the compiler profile carries the real @types/node" is stale).
  Measured, tsgo answers 3 rows where we answer 1. **THREE separate defects, in order:**
  **(i)** `resolveAlias`'s `ImportDeclaration` arm has no `resolveImportTargetFallback`
  leg, which (CHK.30) states is mandatory for a BARE package specifier;
  **(ii)** `getTypeOfSymbolWorker` has NO `SymbolFlags.Module` arm, so even a fully
  resolved module symbol with a populated `exports` table falls through to `anyType` —
  the alias resolution the entry blamed has worked for a long time
  (`createModuleSymbol` even digs a `.d.ts`'s single `declare module "…"` out);
  **(iii)** `@types/node` is AMBIENT (`declare module "fs"`), so no file resolves at all
  and the crawl correctly reports `fs` unresolved — `import x = require("fs")` already
  takes a `globals[specifier]` second chance and `import * as` did not.
  **WITH (i)+(ii)+(iii) THE BINDING AND ITS MEMBERS TYPE EXACTLY AS tsgo**
  (`fsStar.statSync` -> `StatSyncFn`, `fsStar.readFileSync('x')` -> `Buffer<ArrayBuffer>`),
  i.e. hover and completion on `fs.` work — **but a general `SymbolFlags.Module` arm moves
  21 corpus baselines** (the internal-module family: `aliasUsageIn*`, `typeValueConflict*`,
  `moduleAndInterfaceWithSameName`, `typeofInternalModules`), and containing it to an
  import alias's TARGET still leaves **4**: `aliasUsageInObjectLiteral`,
  `aliasUsageInFunctionExpression`, `aliasUsageInTypeArgumentOfExtendsClause`,
  `extendingClassFromAliasAndUsageInIndexer`. **All four are ONE cause and it is a
  MEANING regression, so this may not land as it stands**: a module object exposes an
  exported CLASS as its constructor (`new () => Model`), and this checker types a class
  VALUE as its INSTANCE type — a ctor-less class has no construct signature to match.
  **So the prerequisite is the static side of a class value**, and the `statSync` silence
  that remains is a THIRD thing again — calling a value whose type is a callable
  INTERFACE, which fails identically through a NAMED import and is therefore not about
  namespaces at all. ORIGINAL ENTRY: A DEFAULT OR NAMESPACE IMPORT TYPES AS `any` — AND
  THAT, NOT `statSync`, IS THE ONE knip ROW THE GATE ADDED (48 -> 49).** Measured inside knip's own project with a
  probe file, three spellings of the SAME function: `import { statSync } from 'node:fs'`
  answers `Stats | undefined` (correct, whole overload set present), while
  `import fs from 'node:fs'; fs.statSync(…)` and `import * as fs from 'node:fs'` both answer
  **`any`** — and `fs` ITSELF is `any` (`const c = fs; const q: number = c` is silent), so
  it is the BINDING's type, not a member lookup. `path.join(…)` and `fs.readFileSync(…)` go
  the same way. That is what makes `glob-cache.ts:62` unreachable: the ternary
  `stat?.isDirectory() ? stat.mtimeMs : Number.NaN` cannot type as `number` while `stat` is
  `any`, and (CHK.70)(f)'s refusal of an `any` ternary is correct as written — **no
  narrowing or overload work closes that row**. `resolveAlias` deliberately never resolves a
  NamespaceImport alias (round 444) and the flow path has its own resolver
  ([Checker.resolveNamespaceMemberFnDecl], round 477) precisely because the TYPE path has
  none. **The blast radius is the whole question**: tsc's own sources hold 23-147
  `import * as ns from "./_namespaces/…"` sites per profile against only ~5-14 non-relative
  ones, so a general fix re-opens round 409's TS2315-flood hazard. The containment worth
  measuring FIRST is an AMBIENT-module-only second chance (`declare module "node:fs"`),
  which excludes every relative barrel by construction and is the population a real library
  actually uses.
- [x] **(CHK.72)(a) DONE 2026-08-28 — THE FLOW WALK'S CALL SHORTCUT DID NO OVERLOAD
  SELECTION.** [Checker.resolveFlowCalleeDecl] answers `valueDeclaration ?:
  declarations.firstOrNull()`, so its two return-annotation consumers answered about the
  FIRST signature: [Checker.resolvedCallReturnTypeForFlow] installed the wrong overload's
  return (a WRONG TYPE) and [Checker.callRhsHasNonNullishReturnAnnotation] stripped a
  `| undefined` the selected overload genuinely has (a FALSE NEGATIVE). Both now route
  through [Checker.getReturnTypeOfCallExpression], gated on a BODYLESS resolved declaration.
  Universal — an implementation-bearing overload set, an interface method pair and a
  `declare namespace` member all read first-wins on the parent, and ARITY alone did not
  discriminate. `added=0 removed=0`, suite 16,417/0/3, `output.errors` 46, knip 49 /
  jsonrepair 4 unchanged. The first version merely REFUSED the non-nullish claim for an
  overload set and cost one ours-only TS2322 on every profile (`esDecorators.ts:1309`) —
  `factory.getGeneratedNameForNode` is two overloads that both return `Identifier`.
- [ ] **(CHK.70) — THE ORIGINAL TEXT, KEPT FOR ITS DESIGN.**
  **(a) A COMPOUND ASSIGNMENT INSIDE A LOOP HAS NO POST-STATE RULE.**
  `harness/tsserverLogger.ts` is `let result: string | undefined = …; result = "";
  while (true) { … result += source; } return result` — tsc's loop fixpoint unions the
  entry (`string`) with the back edge's `+=` post-state (`string`) and answers `string`;
  ours sees an assignment to the name on a back edge, so [Checker.loopBodyMayAffectName]
  claims it and the label washes to `string | undefined`. (CHK.67) deliberately does not
  unwrap a compound assignment, so [Checker.narrowByAssignmentRhs] has no arm for `+=`.
  **The cheap shape is a PARTIAL fixpoint that still walks no back edge**: collect the
  back-edge assignments the scan already finds (bounded), take each one's post-state from
  the DECLARED type alone — which is what makes the (CHK.66)(b) KDoc's one-pass argument
  true — and union them with the entry answer. That needs a `+=` arm first; without one
  the union is `declaredType` and buys nothing.
  **(b) AN IDENTIFIER SUBJECT'S NARROW IS LOOP-BLIND IN BOTH DIRECTIONS.** Measured on the
  parent AND on `dcaf1594`: `function f(x: string|number) { if (typeof x === "string") {
  while (cond()) { x = 1; } const p: string = x; } }` is SILENT where tsc 7.0.2 emits
  TS2322 — a shipped FALSE NEGATIVE. Round 784's gate sends the DECLARATION/ASSIGNMENT/
  RETURN readers to [Checker.currentLocalTypes] for a primitive target, and that map is
  statement-ordered with no loop notion at all, so neither (CHK.69) nor anything before it
  can see the loop. It is also why an identifier fixture is VACUOUS for every loop-narrowing
  pin — use a PROPERTY PATH (see `ALoopThatCannotAffectAReferenceKeepsItsNarrowTest`).

- [x] **(CHK.63) OPENED 2026-08-28 (`7a488783`) — `added=0 removed=0` ON ALL EIGHT
  PROFILES.** Six edits, six distinct ablation red sets: the source gate, the RETURN and
  ASSIGNMENT readers' flow admission, (CHK.61)(b)'s checking half, a `never` refusal at the
  return reader (an UNREACHABLE `return undefined` suppressed itself — the corpus baseline
  `functionReturn.ts` caught it), a nullish strip at the weak-type assignment target, and
  (CHK.70)(f)'s conditional-RHS arm. Suite 16,411/0/3, no corpus baseline moved, cost_gate
  rebaselined at `narrow.walks` +11.17% / `narrow.memoServed` +6.61%. Two costs are named
  and queued rather than absorbed: knip 48 -> 49 ((CHK.72)) and 611 capture spans to `any`
  ((CHK.71)). ORIGINAL TEXT:
- [x] **(CHK.63) `T | undefined` IS SILENTLY ASSIGNABLE TO `T` AT A DECLARATION, AN
  ASSIGNMENT AND A RETURN WHENEVER THE TARGET IS A PRIMITIVE — A SYSTEMATIC FALSE
  NEGATIVE, AND ITS SINGLE SUPPRESSOR IS ONE `if`.** `canUseTypeEngine`'s
  `if (sourceType is Type.Union && targetIsPrimitive) { … if (!hasNullish) return true }`
  refuses a NULLISH union source against a primitive target, with the comment "narrowing
  we don't implement". On a six-line fixture tsc emits 6 rows and we emit 2.
  **RE-PRICED 2026-08-28 ON TOP OF (CHK.69): THE COMBINED ARM — gate + RETURN/ASSIGNMENT
  readers + (CHK.61)(b)'s checking half + (CHK.67) + (CHK.69) — IS `added=0 removed=0` ON
  SEVEN PROFILES AND `added=1` ON `tsc-harness`.** The single row is
  `harness/tsserverLogger.ts:28:5`; (CHK.66)(b)'s residue `checker.ts:43282:21` and the
  four `moduleNameResolver`/`server/project.ts` rows are all CLOSED. **And it is now
  AFFORDABLE**: `narrow.walks` +11.2%, `narrow.memoServed` +6.6%, every other counter
  <= 1%, wall flat (26.8 s against 26.9 s) — the ~20x blocker is gone.
  **It is NOT opened**, because 1 ours-only row on a dashboard whose v1 exit is zero FPs
  is a decision to take at 0; the remaining row's cause is named and queued as (CHK.70).
  Rebuild the arm with `python3 build/chk69/arm3.py 1234` against
  `build/chk69/snap/Checker.kt.ship`; grid tag `chk69_comb2`, parent `chk69_parent`.

- [x] **(CHK.61)(b)'s CHECKING HALF — MEASURED CORRECT AND COMPLETE 2026-08-28, WAITING
  ONLY ON THE GATE.** Part `4` of `build/chk65/arm.py` gives
  [Checker.computeRawTypeOfPropertyAccess] a `| undefined` constituent for an OPTIONAL
  member at its three return sites. On the five-reader census fixture
  (`build/chk65/f1`) it reproduces tsc 7.0.2 **EXACTLY** — five rows, same codes, same
  messages, same 1-based columns — and it REMOVES the gate's own `emitter.ts:1479` and
  `services/organizeImports.ts:862` (a `var` whose initializer is an optional member, so
  tsc infers `T | undefined` for the variable and we inferred `T`). It CANNOT land alone:
  without the gate it deletes a true positive (`const a: string = o.optNum` becomes a
  nullish union that `canUseTypeEngine` refuses against a primitive target), and it is
  therefore part of (CHK.63)'s single commit, not an item of its own.

- [x] **(CHK.66)(a) DONE 2026-08-28 — A FLOW JOIN REDUCES SUBTYPES; `string | number | "a"`
  WAS A SHIPPED DIVERGENCE AT A PLAIN BRANCH LABEL.** [Checker.getUnionType] performs no
  subtype reduction (INV.5(a) interns by member-id list alone), so
  [Checker.narrowTypeFromFlowCore]'s `FlowBranchLabel` arm kept a narrowed member beside
  the declaration's own — four lines, no loop, no partition, confirmed against tsc 7.0.2.
  `flowJoinUnion` applies tsc's `UnionReduction.Subtype` at the TWO flow joins only, gated
  on a member the DECLARATION does not itself contain and requiring a STRICT subtype
  (`subtypeRelation` is declared here with ZERO readers, so only assignability exists).
  `AFlowJoinReducesANarrowedSubtypeTest` (7 positives naming their reader, 2 controls).
  Grid byte-identical, suite +9, no baseline moved.

- [x] **(CHK.66)(b) THE LOOP JOIN — **REFUSED 2026-08-28 WITH ITS MECHANISM MEASURED**,
  AND SUPERSEDED BY (CHK.69), WHICH DELIVERS ITS ROWS FOR NOTHING.** The ~20x is
  MEMOIZATION being switched off: `narrowLoopCutUsed` forbids storing anything computed
  under the cut and propagates to the walk root, so a loop body's paths are ENUMERATED
  instead of folded. Deleting that term (unsound; the ceiling) returns **89.2%** of the
  added `globals.lookups` and **91.8%** of `typeNode.cacheable`. A SOUND cut-keyed memo —
  a rolling hash of the in-progress label set carried as an extra equality field on every
  `NarrowFlowMemo` entry — recovers **0.003%**, because the cycle almost never closes ON
  the loop label; it closes on the walk's OWN PREFIX, which is path-dependent. And the
  ceiling itself is still +115% `globals.lookups`, +395% `typeNode.cacheable` and 44.1 s
  against 26.7 s cold, so the direction is refused in its BEST case. **(CHK.69)** answers
  the label by FOLLOWING ITS ENTRY whenever no back edge assigns the reference — the same
  fixpoint, no traversal — and closes 4 of the gate's 5 rows plus the `checker.ts:43282`
  residue. Arms kept at `build/chk69/m1.py` / `m2.py`.

- [x] **(CHK.67) DONE 2026-08-28 — A CHAINED ASSIGNMENT NARROWS TO ITS RIGHTMOST OPERAND;
  `x = y = z` WAS A SHIPPED FALSE POSITIVE (`2cbb3847`).** The queue named TWO unclassified
  shapes at `checker.ts:35649`; a six-shape census against tsc 7.0.2 shows
  `index = index! + 1` was ALREADY handled by the (CHK.33) computed-primitive arm, and the
  chained `index = cutoffIndex = result.length` is the whole gap. Every arm of
  [Checker.narrowByAssignmentRhs] classifies the RHS syntactically and `y = z` matches
  none of them — a `BinaryExpression` whose operator IS `=`, which (CHK.33) excludes by
  construction. Reachable with NO gate and NO loop at the UNION-target declaration reader.
  `unwrapAssignmentChainRhs` descends the `=` chain through parens; a COMPOUND assignment
  is deliberately not unwrapped. `AChainedAssignmentNarrowsToItsUltimateRhsTest` (6
  positives naming their reader, all RED on the rebuilt parent; 2 controls green on both).
  Removes `checker.ts:35649:17` from (CHK.63)'s `armBGR`, 6 rows -> 5.

- [x] **(CHK.64)(i)+(ii) DONE 2026-08-28 — THE FIVE "NARROWING GAPS" ARE **TWO GAPS AT ONE
  READER**, AND BOTH ARE CLOSED; (CHK.63)'s PRICE FALLS **11 ROWS -> 6**.** Round 784's gate
  sends the ASSIGNMENT and RETURN readers to [Checker.currentLocalTypes] for a primitive
  target, and the legacy filler [Checker.extractNullNarrowing] could neither read an `&&`
  (i) nor look anywhere but a then-branch (ii). Everything else about those shapes was
  already right — a MEMBER ACCESS, a CALL ARGUMENT and a DECLARATION are correct on the
  parent binary in BOTH families, which is the census that collapsed five mechanisms into
  two. `AndConditionNarrowsEveryOperandTest`, `EarlyExitNarrowsTheRestOfTheBlockTest`.
  Three defects the GATES found and reading did not: a `typeof x === "object"` conjunct
  installed `any` (a WIDENING, 13 captured hovers); recording the declared type into the
  frame's SHARED `narrowedDeclared` leaked across FUNCTIONS (21 ours-only rows per
  profile); and a negated GENERIC type-guard call degraded the element type (20 captured
  spans to `any`). One SHIPPED defect fell out and is fixed: nested narrows on one name
  recorded `narrowedDeclared` LAST-wins, so `if (b) { if (isNs(b)) { b = undefined } }` was
  a false TS2322.
  **RESIDUE — 2 of the 4 are CLOSED by (CHK.63)(a)(c) 2026-08-28, and the other 2 are
  ONE refused change:**
  1. `parser.ts:2642` — (iii) an assignment INSIDE the guarded branch. **CLOSED by
     (CHK.63)(a)** — it was a SHIPPED false positive at the call-argument reader, not a
     gate-only row.
  2. `checker.ts:35649` — filed as "(iv) definite assignment across an if/else"; measured,
     the if/else is CORRECT and the read is inside a `for` whose earlier iteration assigns
     the reference. **It is the LOOP family**, i.e. the same item as 3.
  3. `tsserverLogger.ts:28` — an assignment narrow that must survive a LOOP. **REFUSED
     2026-08-28 with its price: the loop-join union costs 8 ours-only rows per profile**,
     5 of them a `never` from a negated GENERIC type-guard call that the loop label's
     `declaredType` was masking, 3 a join over a TRUNCATED antecedent that is LESS
     reducible than the declaration. See the (CHK.63)(a)(c) session note and
     `build/chk63/snap/Checker.kt.gapB-refused`.
  4. `server/project.ts:746` — a NON-NULL ASSERTION `!`. **CLOSED by (CHK.63)(c)** — the
     operand is read through PARENTHESES and over a LOGICAL operator; the same defect at a
     UNION target was a shipped false positive.
  Plus (v), the optional-METHOD `&&` chains, which are a DIFFERENT mechanism: an `&&`
  whose EARLIER conjunct narrows a LATER one (`isNamedDeclaration(child) &&
  isPropertyName(child.name)`). It has no measured armG row of its own and it IS the cause
  of the round's 3 remaining capture regressions, so it is the next one to take.
  Smaller residues, each pinned with our own answer: a `while`/`do` body and a plain
  nested `{ … }` block share their parent frame's `localTypes` map, so an early exit
  inside one does not narrow; an `if … else` is refused even when the then-branch exits;
  a PARENTHESISED `!` operand is not unwrapped; and the SINGLE-condition
  `typeof x === "object"` path still installs `any`, a shipped false NEGATIVE
  (`build/chk64/c4`).

- [ ] **(CHK.62c) A PROPERTY-ACCESS ASSIGNMENT RHS DOES NOT NARROW THE ASSIGNED REFERENCE
  (2026-08-27, measured while closing (CHK.62b)).** `let p = zzzFindFree(); p ??= zzzObj.zzzFld;
  return { p }` reports `p: ZzzProj | undefined` where tsc 7.0.2 is silent, and `this.zzzFld`
  behaves identically — so this is NOT the `this` axis (CHK.62b) closed.
  `rhsIsDefinitelyNonNullish`'s `PropertyAccessExpression` arm classifies only an ENUM member
  and a literal; everything else falls through to no-narrowing. The obvious generalisation
  (resolve the member and test it for nullishness) is the round-385/(CHK.62) hazard — it types
  the receiver on the flow hot path — so it needs the same three-gate treatment
  `flowCallDiverges` got. Repro `build/chk62b/p4`.

- [ ] **(CHK.61b) THE ENUM RESIDUE AFTER (CHK.60) — FIVE ITEMS, EACH WITH ITS MEASURED ROW.**
  1. **AN UNEVALUATED ENUM MEMBER IS STILL REFUSED**: `enum E { A = zzzNonConst }` against
     `{ toFixed?() }` is silent in tsc and TS2345 here (`build/chk60/ue/u2.ts(6,8)`).
     `enumLiteralApparentPrimitive` demands POSITIVE evidence of the member's computed
     value, and ablation arm a2 measured that defaulting to numeric fixes this row and
     reddens nothing. **It was refused because a neighbouring shape shows the hazard**: a
     TEMPLATE-valued string member (`build/chk60/ue/u3.ts`) does not fold in our evaluator,
     so a numeric default would relate a STRING member to `Number`-shaped targets — a false
     NEGATIVE. **The sound version is to fix the FOLD first**, or to default to numeric only
     when no member of the owning enum evaluated to a string.
  2. **THE WHOLE ENUM TYPE AS A SOURCE IS ACCEPTED VACUOUSLY** — `zzzX(zzzse)` against
     `{ zzzNope?: number }` is TS2559 in tsc (`build/chk60/mx/m1.ts(25,6)`) and silent here,
     as is a MIXED enum against `{ length?: … }` (`m1.ts(29,6)`). Both are (REL.1)(b): a
     member-less source against a member-less comparison passes both ways. tsc models a
     literal enum AS the union of its members, which is the thing this repo does not have.
  3. **AN INDEX-SIGNATURE TARGET ACCEPTS AN ENUM SOURCE** — `zzzI(ZzzSE.A)` against
     `{ [k: string]: any }` is TS2345 in tsc (`m1.ts(19,6)`) and silent here, before and
     after (CHK.60): `objectTypeRelatedTo` answers true for an empty-`properties` target
     before the retry is reached, and arm a5 measured that reordering does not change it.
  4. **`object`, `() => void`, `Promise<T>` AND `T[]` TARGETS** reject an enum source in tsc
     (`m2.ts(20..23)`) and are silent here — a fourth face of the same vacuity.
  5. **A BIGINT LEAF** (`{ zzzIn: 12n }`) still falls through to TS2322 where tsc reports
     TS2559 `Type 'bigint'` at the key — `weakSourcePropertyNames`' `BigIntLike` arm does
     not resolve to an object here. Inherited from (CHK.60) item 7, one line.

- [ ] **(CHK.53) `namespace globalThis { … }` IS NOT A NAMESPACE DECLARATION AND WE MODEL IT
  AS ONE — (CHK.50)'s measured refusal.** tsc treats `declare global { namespace globalThis {
  var test: string } }` as an augmentation of the GLOBAL SCOPE ITSELF: `test` becomes a bare
  global and `globalThis` never becomes an ordinary symbol. (CHK.50) published it as one and
  the corpus case `extendGlobalThis` reddened with a TS2339 on `globalThis.tests` that
  pristine tsc does not report, so the name is now skipped outright — which leaves that shape
  exactly where (CHK.50) found it: `globalThis.<anything>` is unchecked. **Two halves**: the
  block's members should become bare globals, and `globalThis` itself should be a type whose
  members are the global scope. The second half is what pristine's baseline is really about,
  and it is the only instrument that sees any of this (no profile and neither library carries
  the shape). `DeclareGlobalAugmentationTest`'s `a namespace globalThis block is not published
  as a global symbol` pins the refusal; the positive half is deliberately NOT pinned
  (round 765).

- [x] **(CHK.54)+(CHK.54b) DONE 2026-08-26 — THE AXIS IS THE **WEAK-TYPE RULE**, NOT
  OPTIONALITY, AND A SECOND, INDEPENDENT RULE WAS HIDING BESIDE IT.** Measured over a
  14-row overload matrix against tsc 7.0.2: the item's own shape
  `(x, y?: null)` / `(x, y: "u")` called with `("a", "u")` already selected correctly on
  the PARENT binary, and making the parameter non-optional reproduces the defect
  identically. What decides it is that overload 1's parameter is a **weak type**
  (all-optional, signature-free) and our relation says a string literal is assignable to
  one — because the weak rule lives in the B482 *walkers*, not in `checkTypeRelatedTo`.
  `signatureAcceptsArgs` now asks `weakParamRefusesArg`, per union constituent exactly as
  tsc's `typeRelatedToSomeType` does. **(CHK.54b)**: tsc additionally hoists a
  **specialized** signature (a parameter whose type ANNOTATION is a literal type NODE)
  ahead of every plain one — `reorderCandidates` / GH#1133 — which we did not, so
  `f(x: string): A` before `f(x: "a"): B` answered `A` for `f("a")`. Pins:
  `OverloadWeakParamSelectionTest` (8), `OverloadSpecializedOrderTest` (7), every positive
  asserting the selected overload's RETURN TYPE as a value. Suite 16,133 / 0 / 3, no
  baseline moved, all 20 cost counters digit-identical to the parent, grid
  `added=0 removed=0` on all eight, **knip 54 -> 49** (exactly the five
  `Buffer<ArrayBuffer>` rows), jsonrepair 4 -> 4 byte-identical. Residue queued as
  (CHK.55). See the session note for the matrix and the ten-arm ablation.
  ORIGINAL ENTRY: AN OPTIONAL-PARAMETER OVERLOAD IS SELECTED WITHOUT CHECKING THE ARGUMENT
  AGAINST IT — SIX ROWS ON `knip`, AND A SIX-LINE REPRO.** `readFileSync(p, 'utf8')` resolves
  to the `Buffer`-returning overload whose parameter is `options?: { encoding?: null } | null`,
  a type `"utf8"` is not assignable to; tsgo picks the `string` one and is silent. Reproduced
  hand-written, on the PARENT binary and the landed one identically, with NO `declare global`
  in the fixture — so it is pre-existing and independent of (CHK.50), which merely made it
  visible by giving `Buffer` a real type where it had been an unresolved `any`:
  ```ts
  type ZzzEnc = "utf8" | "ascii"
  interface ZzzBuf { zzzB: number }
  declare function zzzRead(p: string, options?: { encoding?: null } | null): ZzzBuf
  declare function zzzRead(p: string, options: { encoding: ZzzEnc } | ZzzEnc): string
  declare function zzzRead(p: string, options?: { encoding?: ZzzEnc | null } | ZzzEnc | null): string | ZzzBuf
  const zzzS: string = zzzRead("f", "utf8")            // ours: TS2322 'ZzzBuf' -> 'string'
  const zzzT: string = zzzRead("f", { encoding: "utf8" }) // ours: TS2769 + TS2322
  ```
  The `{ encoding: "utf8" }` form additionally emits TS2769, so the two are probably one
  defect seen from both ends. **The population is large and silent today**: every `@types/node`
  read/exec API is written this way, and until (CHK.50) the wrong pick was invisible because
  the wrongly-chosen return type was `any`. Sequence it before any further library screening —
  it is the largest remaining knip family.

- [x] **(CHK.51) DONE 2026-08-26 — THE AXIS IS **HERITAGE**, NOT "LIB", AND THE FIREWALL THAT
  HIDES IT IS WORTH **43 ROWS** ON THE COMPILER PROFILE.** The item's own repro (`Date`) already
  reported, as did `Map`, `Set`, `Promise`, `RegExp`, `Error`, `JSON`, `Math`, `Symbol`,
  `Iterable`, `ArrayBuffer`, `EventTarget` and every primitive — all heritage-free — while a
  HAND-WRITTEN `interface D1 extends B1` was as silent as `Text`. What refuses is
  `cmamCheckResolvedObjectType`'s "skip if class/interface has base types", and deleting it
  outright measures **89 against 46** on the compiler profile, every new row a NARROWING gap
  (`canHaveSymbol(e) && e.symbol`). So the relaxation demands POSITIVE evidence: a new predicate
  requires every type in the transitive base closure to be an interface whose declarations are
  ALL lib declarations, none named by a `declare global { interface … }` block, each with a
  resolved member table. `Text`, `Node`, `Element`, `HTMLElement`, `CustomEvent<number>` now
  match tsgo 7.0.2 on code, message and column. Pins: `LibHeritageMissingMemberTest` (6, with
  `@useRealLibs` + `@lib: es2020,dom` — the embedded lib has no DOM and every one of them would
  otherwise pass vacuously). Residue queued as (CHK.52).
  ORIGINAL ENTRY: A MISSING MEMBER ON A *REAL LIB* INTERFACE IS NOT REPORTED — `declare const
  t: Date; t.zzzNope` IS SILENT WHERE tsgo SAYS TS2339 (found 2026-08-26 while writing
  (CHK.49)'s cross-file pin, which had to be re-pointed at an ASSIGNMENT because of it).

- [ ] **(CHK.52) A MISSING MEMBER IS *STILL* UNREPORTED ON FOUR RECEIVER FAMILIES, AND THEY ARE
  FOUR DIFFERENT MECHANISMS — (CHK.51)'s measured residue, tsgo reports all of them.**
  (a) a **PROGRAM interface with heritage** and (b) a **MIXED closure**
  (`interface Mine extends HTMLElement`) are both the heritage firewall still standing, and
  both are blocked on the same thing: the 43 rows a naive removal adds are the checker's
  NARROWING gaps, above all the INTERSECTION narrow tsc performs when a type predicate names a
  SIBLING rather than a subtype (`canHaveSymbol(node: Node): node is Declaration` on an
  `e: Expression`). **Those 43 rows are a free, already-captured map of that gap** — start
  there, not at the firewall. (c) an **ARRAY or any numeric-index receiver** (`number[]`,
  `Array<T>`, `ReadonlyArray<T>`, `Uint8Array`) is `cmamEmitMissingProperty`'s
  `if (numberIndexInfo != null) … return`, which is over-broad: a NUMERIC index signature does
  not cover a non-numeric name, and tsc reports `arr.zzzNope`. (d) a bare **FUNCTION type**
  (`() => void`) has no properties and a non-empty `callSignatures`, so it falls out of the
  `{}` emitter's gate and returns. And a **CLASS instance with a base** is silent even with the
  firewall removed entirely, i.e. a FIFTH mechanism this round did not locate. (c) and (d) look
  independently closable and cheap; (a)/(b) are the expensive half.

- [ ] **(CHK.33) A DESTRUCTURING PARAMETER BREAKS ARITY, AND THE MESSAGE PROVES IT: `Expected
  1-0 arguments, but got 1` — 8 ROWS IN `marked`, ON A LIBRARY tsgo REPORTS ZERO ERRORS FOR.**
  `marked`'s renderer methods are all written `html({ text }: Tokens.HTML | Tokens.Tag):
  RendererOutput`, and every call `renderer.html(token)` is rejected. **This is round 921's
  documented hazard reaching a diagnostic for the first time**: CLAUDE.md already records that
  `getParameterSymbols` DROPS every binding-pattern parameter, so `Signature.parameters` is
  EMPTY while `minArgumentCount` still counts the pattern — which is exactly an inverted range
  of min 1, max 0, printed verbatim. **The inverted range is a free assertion**: no correct
  signature can have `minArgumentCount > parameters.size`, so `require` it where signatures are
  built and this class of defect stops being silent. Fixing arity may not be the whole item —
  the same drop shifts the positional zip of type annotations onto the surviving parameters
  (CLAUDE.md's `f({a}: O, b: string)` example types `b` as `O`), so pin BOTH the arity and the
  parameter TYPES, and prefer `sig.declaration`'s own list as the reference the way
  `typeCaptureSignatureParameters` already does.

- [ ] **(CHK.34) `isolatedDeclarations` OVER-REPORTS — 32 ROWS ON A LIBRARY THAT SHIPS WITH THE
  FLAG ON AND IS CLEAN UNDER tsgo.** `yaml` sets `"isolatedDeclarations": true` and tsgo finds
  **0** errors; we emit TS9008×19, TS9023×11, TS9007×1, TS9009×1. One member is identified:
  `nodes/YAMLMap.ts:232` is the IMPLEMENTATION signature of an overload set, which needs no
  return annotation under `isolatedDeclarations` because the overload signatures above it carry
  one — so the rule is being applied to a signature the flag exempts. TS9023
  (`Assigning properties to functions without declaring them`) fires 11 times at
  `visit.ts:108-109` and is unexamined. **Sequence this AFTER (CHK.31)-(CHK.33)**: it is the
  biggest row count in the screen and the narrowest trigger — it costs nothing on a project that
  does not set the flag, where the other four families cost every project. The 8 profiles do not
  set it either, so `cost_gate.py` and the grid are structurally blind here and `yaml` is the gate.

- [ ] **(CHK.35) A FUNCTION EXPRESSION ASSIGNED THROUGH AN INDEX SIGNATURE GETS NO CONTEXTUAL
  SIGNATURE — 5 ROWS, AND IT IS (CHK.30)'s SIBLING.** In `marked/Instance.ts:118`,
  `extensions.renderers[ext.name] = function(...args) { … ext.renderer.apply(this, args) … }`
  gives **TS7019** for `args` (rest parameter implicitly `any[]`) and **TS2683**×4 for `this`
  (implicitly `any`), where tsgo is silent — because the index signature's value type supplies
  both the parameter list and the `this` type, and we are not reaching it. (CHK.30) is the same
  failure one container over (an object-literal shorthand METHOD's parameters), so **check
  whether one contextual-signature path serves both before writing either** — if it does, the
  two items are one. Same standing trap applies: a contextual parameter type that does not reach
  `populateParameterLocalTypes` is invisible to the body walkers, so a probe must FAIL if the
  change is inert.

- [ ] **(KIR.LOWER.2) THE SAME ABSENT-DECLARATION TRAP MAY BE LIVE IN `ErasedTypes` — a LEAD, not a
  finding.** `ErasedTypes.mapObject` ends `if (declaration == null) return jsObjectType()`, which
  (KAPI.4) measured to be reached by a `Promise<string>` on the API side: a `Type.Reference`'s own
  symbol carries no declaration, so a named library type outside `libraryClass`'s table erases to a
  property BAG rather than being refused. On the lowering that is not a wrong TYPE but wrong CODE —
  a `.then` on it would read a bag slot — and it is untested because neither corpus library uses a
  Promise. Check whether the target-symbol fallback changes any erasure on the two libraries
  (`scripts/kir-bench.sh`'s equivalence gate is the instrument), and if it does not, add the
  refusal: a named type with no reachable declaration is one this backend does not know.

- [ ] **(KAPI.2) THE PLATFORM HALF: pin that the emitted JVM classes match the exported
  metadata.** `(KAPI.1)` declares a library's API as Kotlin metadata for `commonMain`; a
  `jvmMain` compilation links against the CLASSES the KIR backend emits, and nothing asserts
  the two agree on package, name and erased JVM signature. The failure is the worst-shaped
  one available — the consumer's common code type-checks and its platform code does not link
  — so the instrument is a pin that compiles a JVM consumer against the emitted classes and a
  common consumer against the klib FROM ONE EXPORT, and fails when either resolves something
  the other does not. Expect real divergences to fall out: the JVM lowering names a file's
  facade after the file (`MittKt`) where the metadata puts every declaration in one package,
  and module variables are reached through generated `name$get` accessors rather than as
  properties. `docs/kir-kotlin-metadata.md` §6 item 1.

- [x] **(KAPI.3) A RUNTIME METADATA KLIB — LANDED 2026-08-22, same session.** A SECOND metadata
  klib declares `JsObject` and `JsArray` under their real fully qualified names, is written by
  the same machinery and goes on the exported library's compile classpath — opt-in through
  `runtimeKlib =`, so the self-contained artifact stays available. Measured on the two real
  libraries: `mitt(all: JsObject?): JsObject` and **`parse(toml: String, options: JsObject?):
  JsObject`**, both pinned, and a consumer that reads `document.get("title")` compiles against
  the pair. **The gate is the load-bearing part**: a bag needs POSITIVE evidence — the
  lowering's own `isOwnStructuralDeclaration` (a structural kind declared in a program file
  that is not a `.d.ts`), an anonymous object type by construction, and nothing else — because
  a `Date` is a `JsDate` at run time and typing one as a bag offers members the value does not
  have. An INTERSECTION is one bag only when EVERY member is positively one, which is stricter
  than `ErasedTypes.mapIntersection` and forced: with no library-type table, `Date` and an
  unmappable constraint give the same answer, so the permissive reading types `Date & Tag` as a
  bag (a pin holds both directions). The facade is stated by hand — Java reflection cannot see
  nullability and `kotlin-reflect` here is older than the runtime's metadata — so the drift is
  CAUGHT rather than prevented: `KirRuntimeApiTest` reflects over the real classes, with two
  negative controls proving the check can fail. What is left is the library-type table (`Map`,
  `Set`, `Date`, `RegExp`), now (KAPI.4). ORIGINAL ENTRY:**
  Measured today: `smol-toml` exports `parse(toml: String, options: Any?): Any?`, which is the
  difference between "a TOML parser returns something" and "a TOML parser returns something you
  can read". Arrays and object types erase to `Any?` for one reason only — `JsArray`/`JsObject`
  are JVM Kotlin with no COMMON metadata artifact — so the work is to produce one for the
  runtime's public surface and put it on the export's classpath (the parameter already exists,
  `compileMetadataKlib(..., classpath)`). The trap to design against is drift: a hand-written
  common facade of a JVM class is a second copy, so whatever produces it needs a pin that
  reflects over the real class and fails when a member disagrees — `scripts/kir_native_runtime.py`
  is the precedent for deriving one runtime from the other rather than forking it.

- [x] **(KAPI.4) A LIBRARY-TYPE TABLE — LANDED 2026-08-22, same session.** `KirRuntimeApi.libraryType`
  mirrors `KirIntrinsics.libraryClass` entry for entry, so `Map`/`ReadonlyMap`/`WeakMap`,
  `Set`/`ReadonlySet`/`WeakSet`, `RegExp`, `Date` and `Error` name the same runtime class on an
  exported signature as in the compiled program, and the facade declares all five beside
  `JsObject`/`JsArray` (the drift pin covers them, and now checks CONSTRUCTORS as well as members,
  with a third negative control). Measured: `mitt`'s parameter is `JsMap?` — its `EventHandlerMap`
  is an alias of a `Map` — where a bag would have been less precise than what the program holds.
  **It also found the gate's own defect: an ABSENT DECLARATION IS NOT EVIDENCE OF AN ANONYMOUS
  SHAPE.** A `Promise<string>` reached the object mapping with no declaration to walk and read as a
  property bag; two rules fix it and both are pins now — a `Type.Reference`'s own symbol carries no
  declaration where its TARGET's does (which is how `Emitter<Events>` is recognised as the
  program's own interface), and a type with a NAME but no reachable declaration is a library type
  this backend does not know. ORIGINAL ENTRY: `Map`, `Set`, `Date`, `RegExp`
  and `Promise` are runtime classes with no entry on the exported API, so they are `Any?` where
  `JsObject`/`JsArray` are now real — and, worse, they are what makes (KAPI.3)'s intersection
  rule demand positive evidence rather than reading an unmappable member as a constraint.
  `ErasedTypes` already keys such a table BY NAME (`libraryType`), which is the shape to copy;
  the declarations go in `KirRuntimeApi`, where the drift pin already covers whatever is added.


**WORK ORDER NOTE (restored 2026-08-14, round 903).** This section had been ARCHIVED out of the file
during a trim, and nothing noticed for ~15 rounds because rounds 886-902 were self-directing: each
session note named its own successor. **Round 902 ended with a CLOSURE and named none, so round 903
opened with no pool at all** and had to rebuild one by surveying `docs/perf/`. That is the failure
this section exists to prevent. **A round that refuses a candidate must leave at least one named
successor here, with its price and its next instrument** — a refusal is a successful round only if
the arc can continue from it.

**THE LIVE ARC IS (API.\*), ON OWNER DIRECTIVE (2026-08-17, round 909): DELIVER THE PROJECT AND
LANGUAGESERVICE EMBEDDING APIs.** It takes precedence over the (WARM.\*)/(SPINE.\*) perf items below,
which round 908 closed out anyway — the checker-side pool is empty. Shape decided by the owner: a
**Kotlin embedding API first** (LSP / tsserver protocol layered later, not now), in the new
`xemantic-typescript-compiler-project` module. The perf items stay below as the record; (ART.1) /
(ART.2) remain the only open perf work and (ART.1) has been corrected.

**TOP OF QUEUE ON OWNER DIRECTIVE (2026-08-21): (BENCH.1) below runs before the (API.\*) arc
resumes.**

- [x] **(KIR.PERF.2) THE REGULAR-EXPRESSION ENGINE — LANDED 2026-08-21, and it measured
  **−27.5%** of the toml parse rather than the −18% predicted (47.05 -> 34.10 us/parse,
  2.08x Node -> **1.52x**), with mitt flat at 61.25 and both Node arms flat. Per pattern
  against `java.util.regex`: **16.7x / 13.0x / 3.0x / 3.2x**. It beat its own prediction
  because two smaller members came with it — `replace(/_/g,'')` on a LITERAL path, and
  `split` no longer building a fresh `Regex(source)` per call (which also silently ignored
  the expression's flags). **It also found a divergence in the OTHER engine**: Java's `$`
  matches before a final line terminator where JavaScript's does not, so
  `/^\d+$/.test("12\n")` answered `true` here — `jsEndAnchorTranslated` closes it. Carried
  verbatim to Kotlin/Native, where it measured **−22.5%**. `KirRegexEngineTest`, 20 pins.
  ORIGINAL ENTRY:** `java.util.regex` costs **9.5 us per
  document** on `smol-toml` — 20% of the 47.05 us JVM parse, matching § 2's independent
  JFR reading, and **42% of Node's ENTIRE parse budget**. The engine gap alone (9.5 vs
  V8's 3.0 us) is **27% of the whole JVM-vs-Node difference**. It is the pattern SHAPE,
  not the call count: `^\d+$` is 14.7 ns and `^\d(?:_?\d)*$` is 94 ns, because a
  repetition whose body is not a single deterministic character compiles to Java's
  backtracking `Loop` node — and TOML's digit separators are literally `(_?\d)*`. A
  hand-written scan of the same two patterns, gated to agree on the document population
  plus fourteen adversarial inputs, is **9.4 ns and 6.7 ns — 25x and 12x**.

  **TWO CHEAP FIXES ARE ALREADY REFUSED, measured, before being built**: rewriting the
  groups as `(?: )` for `test` (legal, since `test` cannot observe groups) buys **0.6%**,
  and `matches()` in place of `find()` buys nothing.

  **WHAT TO BUILD:** not a per-pattern special case but a matcher for the REGULAR subset
  these patterns live in — no backreferences, no lookaround — compiled once per
  `(source, flags)` beside the existing `Pattern` cache, with `java.util.regex` kept LIVE
  as the differential oracle (the round-792 shape: never a legality gate). Worth
  **−8.6 us = −18%**, taking `smol-toml` from 2.08x Node to **~1.70x**.
  **AND IT COMPOUNDS ON NATIVE**, where `kotlin.text.Regex` is 5.2x `java.util.regex` and
  35x V8, i.e. ~30% of the native parse. `docs/perf/kir-backend-levers.md` § 5.

- [x] **(KIR.NATIVE.1) ALL THREE SUB-ITEMS LANDED 2026-08-21** — (a) the nominal half's
  first slice (see (KIR.PERF.1)), (b) the regex engine, carried to native verbatim and worth
  **−22.5%** there, and (c) the native arm inside `kir-bench.sh`'s own equivalence gate.
  **(a) WAS then verified on Native rather than assumed**: `mitt` compiles, links and runs
  with the shape classes and the right sink — the plugin reports `checked 2 file(s)` and
  konanc accepts the generated classes, so CLAUDE.md's "Native's IR validator REJECTS the
  public fields the JVM backend accepts" does not bite this shape — and it measures **348
  ns/emit against 354.75, i.e. FLAT**. That is the opposite of §6's expectation and the
  mechanism says why: the JVM's −10.7% comes from C2 inlining the override at a monomorphic
  call site and folding the constant name away, and Kotlin/Native has no JIT to do either,
  so the shape's `get` stays a real virtual call. **The nominal half pays on Native only
  once the property access is a direct field read** — the next slice — rather than a
  virtual `get` over fields. ORIGINAL ENTRY: THE NATIVE BACKEND EXISTS AND IS 4-7x THE JVM — AND THE REASON IS
  BOXING, WHICH MAKES (KIR.PERF.1) A CORRECTNESS-OF-DIRECTION QUESTION RATHER THAN A JVM
  OPTIMISATION.** Both libraries now compile to `-opt` Kotlin/Native binaries through the
  same `KirProgramLowering` (`scripts/kir-native.sh`), agreeing with the other three arms
  on the sink: **mitt 353.25 ns/emit against the JVM's 60.75, toml 163.30 us/parse against
  45.50**. Priced primitive by primitive from one source on both backends, every dynamic
  operation is 4-29x: `jsAdd` **0.95 -> 28.05 ns**, `jsCall1` 0.86 -> 12.93, boxing one
  `Double` 0.86 -> 8.61. **On the JVM C2 scalar-replaces most of those boxes; Kotlin/Native
  has no escape analysis, so every `Any?` position is a real allocation.** The open work,
  in order: (a) the nominal half, which is worth far more here than on the JVM; (b) the
  regex engine, (KIR.PERF.2); (c) a native arm in `kir-bench.sh`'s equivalence gate, which
  this round ran by hand — **(b) and (c) are DONE as of 2026-08-21**: the regex engine
  landed and is carried to native verbatim (**−22.5%**, 163.30 -> 126.55 us/parse, 7.26x
  -> **5.70x** Node, with mitt flat at 354.75 as the control), and `kir-bench.sh` now
  carries the native arm itself under `KIR_BENCH_NATIVE=1` — built by the same
  `kirNativeCompile` task, gated on the same `sink=` and timed in the same interleave.
  **(a), the nominal half, is what is left, and the native numbers are its case**:
  §6's per-primitive table says every dynamic position is a real allocation here, and
  the 36.75 us the regex engine removed leaves boxing as the whole remainder.
  Gradle wiring is DONE (owner-approved):
  `:xemantic-typescript-compiler-kir:kirNativeCompile`, with `scripts/kir-native.sh`
  a wrapper over it.
  Traps that cost the session and are recorded so they are not re-derived:
  `docs/perf/kir-backend-levers.md` § 6.

- [x] **(KIR.PERF.1) THE NOMINAL HALF — FIRST SLICE LANDED 2026-08-21, and `mitt` is
  **−10.7%** (61.00 -> **54.50 ns/emit**, 1.35x -> **1.54x FASTER** than Node), with ranges
  DISJOINT ([209..219] against [243..249]) and both Node arms flat. An object LITERAL whose
  property names are statically known now becomes a generated JVM class with one real field
  per property, EXTENDING `JsObject` — so the erasure is untouched, a shape instance IS a
  bag, and structural assignability never enters into it. That is what made the slice
  affordable where `docs/kir-structural-typing.md` §7's 12x price is for changing what an
  object type erases to. `smol-toml` is FLAT: its ten shapes fire, but `JsObject.get` had to
  become virtual and the parser builds its tables dynamically, so the gain on the scanner
  context and the loss on the tables cancel. **What is left is one further slice and one
  hard problem**: a local whose initializer IS a shape construction can keep the shape as
  its IR type and read the field DIRECTLY (the lowering already emits that for a declared
  class), and a shape arriving as a PARAMETER — which is how `smol-toml` passes its context
  — needs the whole-program inference §7 describes. `docs/perf/kir-backend-levers.md` §2b.
  ORIGINAL ENTRY, whose container half is closed by *four* refutations:** A per-owner leaf
  census of the toml JVM arm charges **47-52%** to the property bag — and, censused by
  OPERATION this round, that is **3,333 bag operations per parse** (2,555 `get`, 737 `set`
  of which **63.5% OVERWRITE**, 41 `has`, over 109 bags minted) at **~4.9 ns each**, which
  is exactly what a `String`-keyed `LinkedHashMap` probe on a cached hash costs. The row
  SURVIVES round 896's division test; its neighbour did not — `jsTruthyBooleanOrNull`
  reads 7.2-7.4% of samples over 298 calls per parse, i.e. **8.2 ns for
  `value != null && value`**, impossible by ~20x, so it was refused without a build.

  **THE READ SIDE IS UNIMODAL, WHICH IS WHAT DECIDED IT.** §2 measured the population as
  bimodal; that is true of ALLOCATION and false of READS, and a lookup cost is weighted by
  reads. **93.6% of every property read lands on a bag of exactly THREE keys** (99.1% on
  four or fewer; the 5-18 tail is 0.9%), and the names are the emitted string LITERALS,
  i.e. interned. That is the most favourable population an identity-compared scan could be
  handed — so the cleanest possible scan was built and MEASURED:

  | design | result |
  |---|---|
  | parallel arrays, promoted by SIZE | +21%, refused |
  | parallel arrays, promoted at the first UNDECLARED key | +31%, refused |
  | **identity scan, NO promotion, single-shaped `get`, everything else cold** | **no effect** |
  | **`LinkedHashMap` sized to the censused mean** | **no effect** |

  **THE LAST TWO ARE "NO EFFECT" AND NOT "A REGRESSION", AND THAT DISTINCTION COST A
  REPLICATION TO GET RIGHT**: the array bag read 738 ms against a baseline batch of 692,
  which looked like +6.6% — and a second baseline batch on the SAME BYTES read **735**.
  The baseline drifts 6.2% between batches, so the screen cannot resolve an effect this
  size, and round 858's law arrived on a fourth instrument. What the screen CAN say is
  that neither candidate is a win, which against a **−44%** premise is a refusal whatever
  the sign. `docs/perf/kir-backend-levers.md` §2a.

  **SO THE GUARDED SLOT HINT IS REFUSED TOO, WITHOUT BUILDING IT** — the design this entry
  used to propose. Its whole claim was that an O(1) indexed compare beats the scan the
  first refutation used; measured, that scan is LEVEL with a hash probe on the population
  that matters, so the hint is competing for the difference between level and level. Its
  cost is real — the shaped representation plus the declared member order reaching the
  lowering, which `CheckedFacts` does not expose. And landing that producer with no
  consumer would be round 887's shape exactly, so it is not a half-step worth taking
  either.

  **WHAT IS LEFT IS THE NOMINAL HALF, AND IT IS NOT A CONTAINER CHANGE**: a property read
  that is a `getfield` rather than any kind of lookup, worth **~16.3 us of a 33.65 us
  parse (~48%)**. **THE OBLIGATION TypeScript IMPOSES, unchanged:** assignability is
  STRUCTURAL, so a nominal encoding needs a witness per declared shape plus generated
  implementations, with a bag still reachable for `any`, for an index signature, and for a
  shape the closure cannot name. `docs/kir-structural-typing.md` §7 prices it at 12x the
  dynamic one. It is worth far more on Kotlin/Native, where §6's per-primitive table shows
  every `Any?` position is a real allocation — see (KIR.NATIVE.1)(a).

  **Measure it with `scripts/kir-bench.sh` and refuse it on the same standard as the other
  four: ranges disjoint, both Node arms flat.** The screening harness for a runtime-only
  candidate is five processes of the compiled program with the classes held fixed; its
  band is ~±5%, which is why the +2.3% arm is reported as "not a win" rather than as a
  regression.

- [x] **(KIR.EMIT.1) LANDED 2026-08-21 — `rewriteRelativeImportExtensions` is implemented
  in the emit, at all four specifier positions (ESM import/export declarations via a
  post-pass over the FINAL statement list, every `require` this transformer builds via
  `normalizeModuleSpecifier`, and a dynamic `import()` in the CallExpression arm). The
  post-pass position is load-bearing: the specifier TEXT is also how the transformer ASKS
  the checker about the target module, so rewriting earlier asks about a `.js` file the
  program does not contain. mitt's EXTENSIONLESS `./mitt` stays a benchmark expedient —
  tsgo leaves it alone too, so rewriting it would be a divergence, not a fix.
  `RewriteRelativeImportExtensionsTest`, 10 pins. ORIGINAL ENTRY: OUR ESM OUTPUT IS NOT RUNNABLE ON NODE AS EMITTED — a relative
  specifier keeps the extension it was written with.** tsgo 7.0.2 rewrites `./parse.ts` ->
  `./parse.js` under `rewriteRelativeImportExtensions` and we emit `'./parse.ts'` verbatim;
  Node ESM resolves a specifier LITERALLY and refuses both that and mitt's extensionless
  `'./mitt'`. `scripts/kir-bench.sh` post-processes the emit to run the arm at all, which is
  a benchmark expedient and NOT a fix. **Invisible to every gate we own** — the corpus pins
  emitted BYTES against tsc baselines, and no baseline asks whether Node can load the result.

- [x] **(KIR.EMIT.2) LANDED 2026-08-21.** The decision belongs to the LOWERING, which
  still holds the TypeScript type: `asString` — the one funnel for `+` and for a template
  span — asks whether every nullish member the operand's type admits is `undefined`, and
  picks `jsToStringNullAsUndefined` if so. A type admitting BOTH, and `any`, keep `"null"`,
  so the wrong answer is narrowed to the shapes the §3.1 collapse cannot separate at all
  rather than swapped for the opposite wrong answer. `KirNullishStringTest`, 5 pins.
  ORIGINAL ENTRY: `undefined` RENDERS AS `"null"` IN A STRING CONCATENATION.**
  `a + '|' + b` with `b` undefined prints `x|null` where JavaScript prints `x|undefined` —
  a `string | undefined` erases to `String?` and Kotlin's own `plus` renders the null. Found
  by `KirDynamicCallArityTest`, which was retargeted to avoid pinning it; the fix belongs in
  the concatenation lowering, not in the call path.

- [x] **(BENCH.1) THE THIRD JS ARM — ANSWERED 2026-08-21: the arm lands ON tsgo's (1.01x /
  1.02x), so the front end is performance-neutral and the whole 2.5x is the BACKEND. The
  harness is `scripts/kir-bench.sh` and the arm is now the standing control.** ORIGINAL ENTRY:
  THE THIRD JS ARM — OUR OWN EMITTED JavaScript, ON THE SAME NODE, AS THE CONTROL
  THAT SEPARATES "OUR COMPILER" FROM "OUR BACKEND".** The 2026-08-21 KIR runtime benchmark measured
  two arms — tsgo -> JS -> Node against xtsc `-kir` -> JVM bytecode -> java — and they disagree by
  library and by SIGN: **mitt 86.0 -> 66.5 ns/emit (JVM 1.29x FASTER), smol-toml 22.6 -> 56.4
  us/parse (JVM 2.50x SLOWER)**, medians of 5 interleaved processes, both arms producing identical
  `sink` accumulators and byte-identical acceptance output. **Two candidate causes are tangled in
  that 2.50x and no arm separates them**: the code our FRONT END produces, and the KIR backend's
  object model. The third arm holds the runtime fixed (Node) and varies only the compiler —
  `-core`'s Transformer/Emitter to JavaScript text, against tsgo's JavaScript, same sources, same
  drivers.

  **What each outcome MEANS, stated before the run (a prediction is what makes a refutation
  legible).** Arm 3 landing on arm 1 says the front end is performance-neutral and the whole 2.50x
  belongs to the backend, confirming the leaf profile by a second instrument rather than by
  inference. Arm 3 landing SLOWER than arm 1 is a genuinely new finding about our JS emitter and
  invisible to every gate we own — **the corpus pins emitted BYTES against tsc's baselines, and byte
  parity says nothing about how fast the resulting program runs on a modern JIT.**

  **The harness exists and is reusable** — drivers, projects, timing shape and the interleaved
  5-process protocol are in the 2026-08-21 session note; the only new piece is emitting the two
  bench projects with `-core` instead of tsgo. **Two traps it must carry.** (i) Node ESM needs a
  real extension: tsgo rewrites `./parse.ts` -> `./parse.js` under
  `rewriteRelativeImportExtensions` and leaves mitt's extensionless `./mitt` alone, so whatever our
  emitter does with a specifier has to be checked rather than assumed. (ii) **An arm that fails to
  RUN must fail loudly** — a JS file that throws on import prints nothing and a wall-clock harness
  reads that as a fast arm; assert the acceptance output byte-for-byte in every arm before timing
  anything, which is what caught nothing this round only because it was done first.

- [x] **(API.1) `Project`: open, diagnostics, in-memory edits — LANDED, round 909.** New module
  `xemantic-typescript-compiler-project` (jvm(), `explicitApi()`, `api(project(":…-core"))`);
  `Project.open` / `configPath` / `files` / `diagnostics()` / `diagnostics(file)` / `updateFile` /
  `deleteFile` / `close()` + `internal OverlayVfs`; 30 pins. **A query on a dirty project is a FULL
  rebuild and that is the compiler's property** — `ProjectCompiler.Result` retains no AST/binder/
  checker — so warmth comes from the CONTENT-keyed `CrawlParseCache` alone. Do not build "incremental"
  on it; the seam does not exist yet.

- [x] **(API.2) Position→node lookup — LANDED, round 910**, in two halves: a public `LineMap` /
  `TextPosition` + `Project.positionAt` / `offsetAt` (which read through the overlay and deliberately do
  NOT build, so a host can convert coordinates on a dirty project for free), and
  `Project.nodeInfoAt` (public, value-typed) over an `internal nodeAt` / `SourceIndex`. 53 pins.
  **The queue entry's "cheap and self-contained" was half wrong**: see the two span findings in the
  round-910 note and in CLAUDE.md — `Node.end` is the end of the FOLLOWING token, so `[pos,end)` is not
  a containment test, and the fix is a token snap-back rather than the sibling arithmetic this entry
  originally implied. **Unblocked by ONE word in core**: `computeParserFlags` is now public, because
  INV.1(e) ("the parse a crawl produces is provably the parse the core would produce") is exactly the
  guarantee an out-of-core parse needs, and duplicating it would be drift no test in the consuming
  module could see. Original entry, for the record:

  <details><summary>original (API.2) text</summary>

  **Position→node lookup, the unblocker EVERY editor feature needs.** There is no
  `getTouchingToken` equivalent anywhere in core: `computeLineStarts` is `private` to `Parser.kt:10119`
  and `positionToLineCharacter` is a private top-level fun (`TypeScriptCompiler.kt:6073`), both
  offset→line only, i.e. the direction diagnostics need and not the one an editor does. Needs: a
  public line/offset map, and a node-at-offset walk (`forEachChild`-driven, narrowest-enclosing, with
  the token-boundary rule tsc's `getTouchingPropertyName` uses). **Cheap and self-contained — it needs
  no checker state**, which is why it comes before quick-info.

  </details>

- [x] **(API.3a) QUICK INFO — LANDED, round 911, AND THE DESIGN BELOW IS NOW CONFIRMED BY MEASUREMENT
  RATHER THAN BY READING.** Captured-during-walk vs asked-post-hoc on ONE `Checker` instance: top-level
  annotated `const` **`string` / `string`** (the honest control — post-hoc is not wrong about
  everything), body local shadowing a global **`number` / `string`**, `typeof`-narrowed parameter
  **`string` / `any`**, parameter at its use **`number` / `any`**, arrow-body parameter **`string` /
  `any`**, class-method parameter **`number` / `any`**. **Five of six differ, and the prediction in this
  entry was wrong in the WORSE direction**: the narrowed case does not degrade to `string | number`
  (narrowing merely lost), it degrades to **`any`** — nothing durable binds a parameter at all — which is
  the one answer that is SILENT at every use site, so a post-hoc hover would have looked plausible and
  meant nothing. **THE HOOK'S REAL LESSON, now in CLAUDE.md: a per-node hook on the spine sees NONE of
  the checking ambient**, because the anchors install-and-restore it per dispatch — the position's scope
  is `ctaFrames.last()`, and the capture must reproduce `ctaM3StmtAnchorCore`'s prologue plus
  `withCtaFrameLocals(frame)`. Without that it answered `bodyLocal=string`, `narrowed=any`,
  `parameter=any`. Threaded as an explicit parameter on the `recheckOnly` model (nothing on
  `CompilerOptions`, no process-global mode); node identity is the RAW `(pos, end)` pair, so round 910's
  span semantics stay entirely in `-project`'s `SourceIndex`. **OFF IS FREE and gated as such**:
  `cost_gate.py` +0.00% on all 20 counters, the production cost being one null-valued field read and a
  predicted branch per node, with the NODE as the argument (round 900). Public surface stays value-typed:
  `QuickInfo` + `Project.quickInfoAt`.

- [x] **(API.3b) Go-to-definition — LANDED, round 913.** The entry read: *"the capture mechanism now
  exists and this is the same shape one field over: record the resolved `Symbol`'s `declarations`
  (each a pos/end-bearing node) at the captured position instead of its type, and answer
  `DefinitionLocation(fileName, start, length)`. **Read (API.3a)'s ambient lesson first** — a symbol
  resolved without `withCtaFrameLocals` is the same wrong answer one indirection along."* **The
  premise is WRONG in its most useful sentence, and the correction is the round's product: the
  ambient lesson does NOT transfer, because a definition's walk-scoped input is not the ambient at
  all.** `withCtaFrameLocals` restores `currentLocalTypes`, which holds TYPES and no symbols, so it
  cannot answer "what does this name refer to" for anything. What does is `spineCurrentScope` — the
  INV.2(c) lexical chain — and the spine **maintains that per NODE**, pushing it BEFORE a node's own
  enter handlers, so it is already correct at an arbitrary node and needs no reconstruction. What
  (API.3a) and (API.3b) genuinely share is only that both inputs are gone once the walk is over
  (`spineScopeClear` nulls the chain per file), which is what still makes capture mandatory:
  post-hoc, a body local resolves to a same-named FILE-LEVEL const and a parameter to nothing at all.
  Landed: `CapturedDefinition`/`CapturedDeclaration` in the core (recorded by the SAME hook as the
  type — one request, two facts), `DefinitionLocation` + `Project.definitionsAt` in `-project`,
  import-alias hop through `resolveImportedSymbolGeneral`, and an exact NAME span computed in the
  core by a forward token scan of the declaring file's own text. **19 pins, four-arm ablation, all
  gates green.**

- [x] **(API.3c) Batch a whole file's spans into ONE build.** The core `TypeCaptureRequest` already
  takes a SET of spans and `Project.quickInfoAt` deliberately does not cache its build (a capture build
  types nodes the checker had no reason to type, so its diagnostics are not reusable — pinned). So
  "semantic info for file X" is already one compile away from being one compile; exposing it turns
  hover-per-keystroke from N builds into 1. **This is the item that makes the API practical for an
  editor** and it needs no new mechanism. **LANDED round 914** —
  `Project.semanticsAt(fileName, offsets)` (the primitive) and `Project.fileSemantics(fileName)` (the
  sweep, expressed on it), answering `SemanticInfo(start, end, kind, quickInfo, definitions)`: ONE
  build for any span count, both answers per span, distinct spans sorted `(start, end)`. Measured
  **1 compile / 100 ms against 34 compiles / 3,373 ms and 68 compiles / 6,209 ms** on a
  34-identifier fixture. **THE PREMISE'S ONE ERROR, and it is the round's technical product: "it
  needs no new mechanism" is true of the CAPTURE and false of its KEY.** `TypeCaptureRequest`'s
  packed `(start, end)` key was left un-finalized with a note saying to finalize it "should a caller
  ever request spans in bulk" — and bulk is exactly what this item is: `Long.hashCode` folds
  `(a shl 32) or b` onto `a xor b`, and a node's `end` is its `start` plus a token or two, so a whole
  file's spans collapse onto a few dozen hashes (measured: **>400 spans onto <40 hashes**, round
  889's defect verbatim). It now goes through `packIdPair`, pinned by a measuring test with a raw-pack
  negative control. **26 pins, all gates green.**

  <details><summary>the design decision, recorded round 910 and confirmed round 911</summary>

  **(API.3) Quick info + go-to-definition — THE DESIGN IS NOW DECIDED BY EVIDENCE: *POSITION-DIRECTED
  CAPTURE*, NOT A POST-HOC QUERY, BECAUSE THE CHECKER'S ANSWER TO "WHAT IS THE TYPE HERE" IS A FUNCTION
  OF WALK-SCOPED AMBIENT STATE AND A POST-HOC CALL WOULD BE SILENTLY WRONG FOR EXACTLY THE INTERESTING
  CASES (round 909, by reading `getTypeOfIdentifier`).** `Checker` does all its work in `init`, so the
  instance still HOLDS its tables afterwards and "hand the Checker back and call `getTypeOfExpression`"
  looks free. It is not: `getTypeOfIdentifier` (`Checker.kt:108777`) consults, IN ORDER,
  `currentLocalTypes` (its own comment: *"populated during TS2322 checking walk"*),
  `currentParamBindingNames`, `currentCheckFileName` -> `fileLocalTypeMaps`, `currentFileLocals`, the
  inference-namespace chain, and only THEN the node-keyed `lookupPerFileForNode`. At rest
  `currentLocalTypes` is an empty `HashMap` (`:636`) and the two `current*` file fields are null, so a
  post-hoc query **skips the first five reads** and falls through to globals. **For a
  FUNCTION-BODY LOCAL that does not merely lose narrowing — it can resolve to an unrelated same-named
  global**, which is the `useCaseSensitiveFileNames` failure documented in that very function
  (a destructured param resolving to another file's function, FP TS2345 x9). Two of the ambient reads
  are FILE-scoped and cheaply re-installable from outside; `currentLocalTypes` is
  STATEMENT-POSITION-scoped, built first-wins as the walk proceeds and deliberately leaking across
  blocks in statement order — **it cannot be reconstructed for an arbitrary position without
  re-walking to that position, which is the whole argument for capture.** So: hand the compiler the
  position(s) BEFORE the build and capture type+symbol at those nodes while the real ambient is
  installed. Correct by construction, and it **batches** — one build can capture every identifier in a
  file, so "semantic info for file X" is one compile rather than N. Cost, stated: a query is a compile
  (~5.2 s warm on tsc's own sources, far less on a normal project, repeats warm through
  `CrawlParseCache`); too slow per keystroke, fine for hover-on-demand.
  **IMPLEMENTATION CONSTRAINT A NEW AGENT WILL OTHERWISE LOSE A ROUND TO: a capture handler is a spine
  handler, so it must extend `SpineDispatch.enterClosure` or round 888's `spineEnterMask` means it is
  NEVER CALLED**, and `python3 scripts/spine_closure_audit.py` must be run after touching any
  `spine*EnterNode`. **PUBLIC SURFACE STAYS VALUE-TYPED** (`QuickInfo(kind, displayString, span,
  docs)`, `DefinitionLocation(fileName, start, length)`) — no AST, no `Symbol`, no `Type`.
  **THE FIRST STEP IS STILL A MEASUREMENT, NOT CODE:** pin the above by asking a post-init `Checker`
  for the type at three positions — a top-level `const`, a function-body local, and a guard-narrowed
  reference — and record which answer wrong. That experiment becomes the regression pin for the capture
  path.

  **THE STARTING FACTS** (unchanged, and they are what make capture cheap): everything an editor needs
  is `private` in `Checker.kt` and nothing hands back live state — `getTypeOfExpression` (`:108501`),
  `getTypeOfSymbol` (`:106667`) and `typeToString` (`:120389`) are all `private fun`, and
  `BinderResult.nodeToSymbol` is public but no `BinderResult` ever escapes a compile. Capture needs only
  an `internal` seam plus a handler; it publishes none of them.

  **THE THREE ALTERNATIVES, AND WHY THEY ARE NOT THE NEXT STEP.** (a) **post-hoc query-shaped** —
  narrow `Checker` entry points answering one question after `init`: **superseded by the finding above**,
  because it is silently wrong for body locals and narrowed references (the ONE hover case a user
  notices is `let`/`const` inside a function). Directed capture is (a)'s cheapness without its defect.
  (b) **snapshot-shaped** — return a `ProgramSnapshot` holding ASTs + binder output + the live
  `Checker`: **REJECTED for now, and the reason is this repo's own history** — it freezes as versioned
  API exactly the structures the perf arc keeps rewriting (rounds 889-908 changed packed-key hashing,
  container types and memo layouts, and moved maps onto `LongKeyMap`/`IntKeyMap`, which deliberately
  have NO iterator). Publishing them constrains the work that just delivered -10.5%. It also does not
  even solve the ambient problem: a snapshot hands back the same post-hoc trap. (c) **the full
  inversion** — a lazy, re-entrant checker (`docs/ARCHITECTURE-RETHINK.md:850` names it as the LSP
  prerequisite): **the right end state and the wrong next step**, the largest job in the repo. Do not
  let hover gate on it — and do not let it be "unblocked" by an API that has already published the
  internals it must change.

  </details>

- [x] **(BUG.1) The compiler disagrees with itself about a lone `\r` — DONE, round 915.** The
  convention is now stated ONCE, as `lineBreakWidthAt` in a new `LineStarts.kt`, and every
  offset→line conversion in the compiler goes through it. The sweep the item asked for found **five**
  such converters where the entry named two, four of them wrong: `Checker.lineStartsFor`, its inverse
  `Checker.posOfLineCol`, `TypeScriptCompiler.positionToLineCharacter` (plus its inline TS2688 twin),
  the `Transformer`'s JSX dev-runtime coordinates (EMITTED output, not a diagnostic), and
  `CompilerOptions.computeLineAndColumn` — which implemented a THIRD convention, `\r` as zero-width.
  `-project`'s `LineMap` was already correct and stays a reimplementation, pinned by a differential.
  **The finding that outlives the fix**: `parseMultiFileSource` — the `// @directive` splitter behind
  the whole generated corpus — begins by replacing every `\r\n` and `\r` with `\n`, so the corpus was
  not merely unlucky, it was structurally incapable of carrying a `\r` to the Parser; only the
  project/`Vfs` path can, which is the path the `(API.*)` arc sits on. `LineTerminatorConsistencyTest`
  (core) + `ProjectPositionTest`'s lone-`\r` differential are the gate; 5 pins redden under ablation.

- [x] **(API.3d) Member go-to-definition — LANDED, round 916.** The gap round 913 recorded
  deliberately: *"a scope lookup of a member name finds whatever unrelated binding happens to share
  the spelling, and a confidently wrong navigation target is worse than none. Member definitions need
  the receiver's type resolved and its property symbol found, which is a separate mechanism and not
  this one."* It is now that separate mechanism, in the SAME capture hook and with no new public type:
  `typeCaptureMemberSymbols` resolves a member name through its RECEIVER and hands the resulting
  symbols' declarations to the existing `CapturedDeclaration` path, so a member answer is simply a
  non-empty `definitions` list where one used to be empty. **ANSWERS**: `o.p` / `o.m()` / `this.p` /
  `super.p` / `C.staticP`; a member of an IMPORTED interface (in the declaring file); an INHERITED
  member (the BASE's declaration); a MERGED member (one location per contributing declaration); a
  member of a UNION or INTERSECTION receiver (one per constituent, in constituent order); `N.x` and
  the qualified TYPE `N.T` for a namespace, module alias or enum; a LIB member (in `lib.*.d.ts`, the
  policy `definitionsAt` already documented for a free name). **REFUSED, each with a reason in the
  KDoc**: an element access (`o["p"]` — the argument is a literal, and only identifiers are offered a
  definition); an object-literal key being declared (`{ p: v }` — the useful target is the CONTEXTUAL
  type's property, a third mechanism); a member's own declaration name (it already IS the
  declaration); a chained namespace segment (`A.B.x`); an unresolvable member (silence, never the
  nearest same-named anything). **THE ROUND'S TWO FINDINGS**: the ambient the hook already installs is
  exactly enough — `this` needed `currentClassForThis`, which round 911's install already restores and
  which is deliberately NULL in a static member — and going through the compiler's own
  `resolveStructuredTypeMembers` rather than a hand-rolled table read is what makes the inherited and
  generic cases right for free. **13 pins, five-arm ablation each reddening a DISTINCT set, all gates
  green.**

- [x] **(API.4a) The completion ANCHOR + MEMBER completions — LANDED, round 917.** (API.4) was
  decomposed rather than taken whole; this is the standalone half that needed the genuinely new
  mechanism. **THE ANCHOR** (`SourceIndex.completionAnchorAt` / `CompletionAnchor`, `-project`, where
  round 910's caret already lives) answers a TOKEN-level question, because a completion request has no
  node at the caret by construction: it reports a `CompletionKind` (MEMBER / FREE_NAME / NONE), the
  typed PREFIX, and a replacement span covering the whole word rather than only the prefix. **The
  recovery rule for an incomplete `o.` is that there is nothing to recover**: this parser's `Dot ->`
  arm always builds a `PropertyAccessExpression`, synthesizing a zero-width `Identifier("")` and
  reporting TS1003, so the receiver is a real node at end of file, before a `}` and across a newline
  alike — the anchor descends to the character BEFORE the dot and walks back out to the access whose
  own dot that is (`realEnd(expression) <= dotStart < name.pos`, which at most one node in a path can
  satisfy). A `.` the parse did not turn into an access answers empty rather than guessing a receiver
  from bracket-balanced text. **THE MEMBERS** ride (API.3d)'s resolution one question wider —
  `TypeCaptureRequest.memberSpans` (a SECOND span list, so `fileSemantics` never enumerates) ->
  `CapturedMembers` / `CapturedMember(name, kind, typeText, optional, readonly, accessibility)`.
  **`Project.completionsAt(fileName, offset): CompletionList`.** Free names are an explicit
  `CompletionRefusal.FREE_NAMES_NOT_IMPLEMENTED`, never a silent empty list.

- [x] **(API.4b) FREE-NAME completions — LANDED, round 918; KEYWORDS REFUSED with a reason.** It did
  land by deleting one refusal: `CompletionRefusal.FREE_NAMES_NOT_IMPLEMENTED` is gone and no
  signature moved. **THE MECHANISM** is a THIRD span list (`TypeCaptureRequest.scopeSpans` ->
  `CapturedScope` / `CapturedName(name, kind)`), unioned into `keysByFile` exactly as `memberSpans` is,
  and it is the ONE capture that also admits a NON-`Expression` node — a free caret is anchored at the
  innermost node ENCLOSING it, routinely a Block or the source file. **THE ENUMERATION IS
  `spineScopeLookup`'s OWN WALK, RUN TO EXHAUSTION** — every level's `symbols` then its `existing`,
  innermost first, first sighting wins — then the merged/lib GLOBALS filtered through
  `globalsForFile` (INV.3(c)). That identity is the correctness argument: *a name the list offers is a
  name `definitionsAt` will resolve, and a name it hides is hidden because something nearer binds the
  spelling.* **TWO DIVERGENCES FROM THE ENTRY AS WRITTEN, both deliberate and both ablated.** (i)
  `LexicalScope.existing` IS read: round 748's `symbols`-only rule is about a RESOLVER whose soundness
  is that it cannot change how an existing name resolves, and an enumeration reading `symbols` only
  offers no file-level declaration and no import at all (arm A5, 8 red). (ii) `lexLevelHasName`'s
  UNTRUSTED-level skip is NOT applied: it belongs to a chain with a second, export-filtered threaded
  population to fall back on, and this chain has none — applying it answers nothing inside every
  namespace body (arm A3, 1 red, uniquely its own). **A FREE-NAME ITEM CARRIES NO `typeText`**, decided
  on measurement: at a caret in a real file of the compiler profile the list is **1,628 items**, the
  enumeration itself **0.39-0.64 ms**, adding a type to every item **+2.6-14.3 ms** — and **618 of
  1,629 (37.9%) would render `any`/`error`**, because a free name may name a TYPE. **KEYWORDS ARE
  REFUSED**: a useful list is context-sensitive and the anchor is token-level, so an unconditional one
  offers items that do not compile — the thing the member half already refuses to do. **22 pins**
  (18 `-project`, 4 core `ScopeCaptureMeasurementTest`), **seven-arm ablation, six DISTINCT sets**;
  A7 (drop the writable-name filter) read **0 red** and is recorded in-file as an UNDISCRIMINATED
  guard rather than claimed. All gates green.

  **WHAT IS ALREADY YOURS, do not re-derive it.** The anchor: `completionAnchorAt` already returns
  `FREE_NAME` with the correct prefix and replacement span at every free position, and already answers
  `NONE` inside strings, templates, comments and numeric literals — `CompletionAnchorTest` pins all of
  it, including the caret at the very end of the file. The public value types, the refusal enum, the
  `memberSpans` channel and the "off is free" wiring. The build-free short-circuit (a refused kind does
  not compile) — you will be REMOVING that for FREE_NAME, which makes free-name completion a compile
  where member completion already is one.

  **WHAT MUST BE BUILT, and the one structural fact that decides its shape.** The scope chain is
  **CLEARED PER FILE**: `spineCurrentScope` is nulled by the spine's per-file teardown, which is what
  `DefinitionCaptureMeasurementTest` measures — so the enumeration must happen DURING the walk, at the
  requested position, exactly as `typeCaptureRecordDefinition` does. There is no post-hoc option. The
  natural shape is a third span list (`scopeSpans`) beside `memberSpans`, keyed the same way, recording
  a `CapturedScope` at the node the anchor names — and the anchor must therefore hand in a NODE for a
  free position too, which today it does not (it returns `receiver = null`). Deciding WHICH node a free
  caret names is the first sub-problem: the caret is between nodes, so the honest candidate is the
  nearest enclosing statement or block, and its scope is the scope in force for the position.

  **THE SIZE PROBLEM IS REAL AND IS MEASURED.** CLAUDE.md round 902: `LexicalScope.symbols` holds 1.51
  symbols averaged over SCOPES but **290.94 averaged over a real PROBE**, because the ascent walks
  outwards and 35.5% of probes land on levels holding a mean of **815**. A completion list is that
  whole ascent, flattened — so it is hundreds of items on a real program, every one of which costs a
  `getTypeOfSymbol` + `typeToString` if the item is to carry a type the way a member item does.
  **Decide whether a free-name item carries `typeText` at all before building it**; making it optional
  (null for a free name, present for a member) is a strictly additive change to `CompletionItem` and
  is the cheap escape.

  **SHADOWING AND DEDUP.** Innermost wins: a name bound at two levels must appear ONCE, as the inner
  binding, which is the opposite of the member walk's merge (a member declared twice is one item
  merged from both). `lexLevelHasName`'s ascent is the traversal to copy, with its two live rules —
  `LexicalScope.symbols` only, never `existing` (round 748), and the untrusted Module/Enum levels are
  SKIPPED (INV.4(c)(ii)). Keywords are a separate, purely syntactic list keyed on the anchor's
  position and want their own `CompletionItem.kind`.

  **THE PIN THAT DISCRIMINATES** is (API.4a)'s discriminator inverted: a caret inside a function body
  whose local shadows a same-named binding in ANOTHER FILE must offer the local ONCE and must not
  offer the other file's; and the member pins must stay green, i.e. a free-name enumeration must not
  leak into a member position — the failure round 913 refused and round 916's arm A2 catches.

- [x] **(BUG.2) The `-project` token index de-synchronised at the first `${…}` — LANDED, round 919.**
  Found by (API.5)'s cost measurement, not by a test. `SourceIndex.scanTokens` ran a context-free
  `Scanner.scan()` loop and the parser re-scans the `}` that closes a template substitution
  (`reScanTemplateToken`); without that, the `}` reads as a CloseBrace, whatever follows reads as
  operators, and the CLOSING BACKTICK opens a fresh `NoSubstitutionTemplateLiteral` that runs to the
  next backtick **anywhere in the file**. Unlike a SPLIT (which only adds ends and is why the slash and
  greater-than re-scans are still deliberately absent) a MERGE de-synchronises the stream **for the
  rest of the file**, so every later node's `realEnd` snaps back, `pathAt` cannot descend into it, and
  `nodeInfoAt` / `quickInfoAt` / `definitionsAt` / `completionsAt` all answer about a huge enclosing
  node. Measured on tsc's own `checker.ts`: **50,684 tokens for 3,151,772 characters, the longest
  62,089**, and a caret on a top-level function's name resolving to the whole file's `Block`. The fix
  tracks substitution nesting exactly as `Parser` does (a `TemplateHead` pushes, braces inside are
  counted, the closing `}` is re-scanned into a middle or a tail). `TemplateTokenSyncTest`, 5 pins,
  arm A6.

- [x] **(API.5) FIND REFERENCES + DOCUMENT HIGHLIGHTS — LANDED, round 919.** `ReferenceLocation(
  fileName, start, end, isDeclaration)`; **`Project.referencesAt(fileName, offset)`** (the program)
  and **`Project.documentHighlightsAt(fileName, offset)`** (one file). **ZERO core changes** — the
  whole feature is (API.3c)'s batch turned inside out, above the compiler. **THE IDENTITY QUESTION,
  which the brief said to verify rather than inherit, VERIFIED AND ANSWERED: a DECLARATION-LOCATION SET
  is a sound proxy for "the same symbol", but the relation is INTERSECTION, not equality.** Measured on
  a probe fixture before any code was written: the import alias, its `import { }` clause, every use and
  the export are ONE set (the capture's alias hop already unifies them); two merged `interface I`
  blocks give every occurrence the SAME two-declaration set (equality would not split them); three
  same-spelled `collide` bindings over two files give three DISJOINT sets. Equality FAILS on one shape
  only, and it is a real one: a member of a UNION receiver resolves to one declaration per constituent,
  so `u.p` and a single-constituent `a.p` would be different groups. **THE ONE HOLE, stated and pinned
  rather than papered over:** a MEMBER's own declaration name is bound by no scope and has no receiver,
  so the capture resolves it to nothing (which is exactly why `definitionsAt` answers empty there). It
  is recovered from the sweep's own evidence — an occurrence that resolved TO that span proves the
  caret is a declaration — which leaves exactly one truthful gap: **a member declared and never used
  answers EMPTY rather than a list of one** (tsc answers one). Free names are unaffected. **REFUSED
  with reasons:** read-vs-write (`[x] = pair` / `({x} = o)` / `for (x of xs)` are writes under an array
  literal, an object literal and a `for` head, so a rule built from `x = 1` and `x++` reports them as
  READS and a host cannot tell a complete answer from an incomplete one — the same grammar-position
  mechanism keywords are refused for); lib files are not swept for uses; element access. **MEASURED on
  the compiler profile** (78 files, 9,977,097 chars, **381,670 identifiers**, real libs, warm): plain
  rebuild 5.5-5.9 s; `documentHighlightsAt` **6.0-7.2 s** (1 build); `referencesAt` **8.3-9.9 s** clean
  (1 build) and **13.0-13.5 s** dirty (2 — `files`' build first); the sweep is 2.5-4 s on top of the
  rebuild WHATEVER the caret (168 hits in 1 file and **9,827 hits across 49 files** for `SyntaxKind`
  cost the same); **peak heap ~1.9 GB, so 512 MB is not enough**. Key spread needed nothing: both
  packers were already finalized (round 914's `packIdPair`). **19 pins**, eight-arm ablation, **every
  arm a DISTINCT set**. `docs/language-service.md` § 10b.

- [x] **(GATE.2) A REAL-SOURCE INVARIANT GATE for the language-service position APIs — LANDED, round
  920, and it found FOUR MORE DEFECTS on its first run.** (BUG.2) was live for nine rounds behind a
  green suite because **a hand-written fixture for a lexical API does not contain what real source
  contains**; round 919 fixed the template case and did not build the instrument. This is it.
  **`TokenIndexInvariants`** (commonTest) asserts ten rules true of ANY correct implementation — the
  tokens partition the text and the scan reaches EOF; every gap holds only trivia; a string literal
  never crosses a line break; a non-literal token is short; **every identifier the PARSER found starts
  a token of exactly its length** and `realEndOf` answers that end; a descent to an identifier's own
  position reaches it; a path strictly nests; and offset↔coordinate round-trips against an
  INDEPENDENT restatement of round 915's terminator rule. **The parse is the oracle** — it is the
  context-sensitive lexer this index approximates, so a merge is exactly "an identifier with no token
  starting at it". **THREE CORPORA, and the choice is the point.** Hermetic and permanent
  (`TokenIndexGateTest`): an adversarial shape corpus plus **the real `lib.*.d.ts` sources**
  (`RealLibFiles.files`, 2.39 MB of TypeScript nobody wrote for this test, already embedded, no
  vendored tree and no licensing question). Local-only: `build/bench/tsc-project-*` via
  `scripts/round920-token-gate.sh` + `RealSourceTokenGateMain`, which **REFUSES (exit 2) rather than
  skips** — a gate reading a local artifact that passes quietly where the artifact is absent is round
  853's and round 873's failure mode. **FOUND, all four real, all fixed:** (A) **a backtick inside a
  regular expression** (tsc's own `` /\r\n|[\\`…]/g ``) opened a template literal running to the
  next backtick anywhere in the file — a **25,761-character token** that swallowed the twelve
  identifiers after it, i.e. (BUG.2) in its second costume; (B) a **parenthesis-less arrow parameter**,
  an **index-signature parameter** and a **`catch` variable** were built with the default `[0, 0)`
  span, so no descent could enter them — **328 sites in tsc's 78 sources**, the API's single most
  common wrong answer; (C) `declare global`'s **`global`** name carried an EXACT end where every other
  node carries the following token's; (D) **JSX tag names** did the same, and (E) the synthetic
  **`new`** name of a construct signature was at `[0, 0)`. **THE FIX FOR (A) IS THE MECHANISM WORTH
  KEEPING: ask the parse.** A `RegularExpressionLiteralNode` and a `JsxText` each carry their own RAW
  text, so `pos + text.length` is exact; `SourceIndex` collects them and emits them verbatim, resuming
  the scanner past each. The undecidable "does this `/` divide or quote" is therefore never asked —
  whatever the parser decided, the index reproduces, so the two cannot disagree. **AFTER: 1,327 files,
  101,287,620 characters, 11,299,274 tokens, 3,936,158 identifiers, ZERO violations**, against 50 of
  78 files failing on the compiler profile alone before. **COST**: the oracle is +32 ms on 9,977,097
  chars = **+9.9% of `SourceIndex.of`** (358 vs 326 ms), paid only by a host's position query;
  `cost_gate.py` **+0.00% on all 20 counters** because nothing in the compile path builds an index.
  **POSITIVE CONTROL**: `SourceIndex.of(…, useParseAsLexerOracle = false)` is the in-binary OFF arm —
  the shape `--spineMaskOff` has — and the gate's own control asserts it reddens.

- [x] **(API.7) THE SYNTACTIC-ROLE MECHANISM + THREE OF THE FIVE STANDING REFUSALS — LANDED, round
  922.** The backlog was promoted as ONE item on round 921's premise that all five wanted the same
  missing "where is this caret in the grammar" mechanism. **Three did and two did not, which is the
  round's product.** BUILT: `SyntaxRoles` (`-project`), a PULL-BASED parent-chain ascent —
  `referenceUse(node)` for a node's role, `grammarPositionOf(path)` / `keywordsFor(path)` for a
  caret's — plus a sibling ascent in `Checker.kt` for the half of accessibility that needs symbols and
  heritage (the home is decided PER QUESTION, not forced). Pull rather than push on round 875's
  measurement (a maintained status is 11.1x the work); identity comparisons throughout, because AST
  nodes are `data class`es (round 471). **CASHED: (a) member-completion ACCESSIBILITY** — `private`
  only inside the declaring class, `protected` there or in a derived one, statics alike, the ascent
  reaching out of a nested arrow and the heritage walk following an IMPORT; biased PROVE-TO-HIDE, so
  every unknown leaves the member offered, which is the only answer to round 917's stated objection.
  **(b) KEYWORD completions**, bounded explicitly to STATEMENT / EXPRESSION / TYPE positions with
  `await`, `yield`, `super`, `return`, `break`, `continue` and the module-level declaration starters
  each gated, and every continuation keyword refused outright. **(c) READ-vs-WRITE**
  (`ReferenceLocation.use`), with the write set stated completely and `UNCLASSIFIED` as a fourth state
  rather than a default. **STILL REFUSED, with the reason CORRECTED**: an element access (`o["p"]`)
  and a contextual object-literal key (`{ p: v }`) were never blocked on a grammar position at all —
  recognising either shape is one test on the node's parent — and what each lacks is SEMANTIC (a
  capture channel plus member-lookup-by-text; a contextual type, which is walk-scoped and absent
  outright in a ternary branch). **TWO EXISTING ANSWERS CHANGED** and their round-917 / round-918 pins
  were updated in place: member completions no longer include inaccessible members, and a free-name
  list now carries keyword items (`kind = "Keyword"`). **+45 pins** (32 parse-only), **fourteen-arm
  ablation, all fourteen a DISTINCT set**, all gates green. `docs/language-service.md` §§ 10a, 10b.

- [x] **(API.13) § 14 AUDITED BY EXECUTION AND PINNED — LANDED, round 930; four of its
  claims were false and one of them was a DEFECT.** `docs/language-service.md` § 14 is the
  page a host author and a next agent read instead of twenty session notes, and it was
  three rounds old with a fixed defect still listed as open. Every claim in it was re-run
  — a fixture through the API, `tsc --lsp -stdio` as the oracle where the claim is parity,
  the cost table re-taken on the compiler profile — and the half that a test can defend is
  now `LanguageServiceStateTest` (+15 pins). **THE ONE DEFECT: `definitionsAt` on a
  `super.p` member answered NOTHING** while `quickInfoAt` at the same caret answered
  correctly — § 9's own table and § 14's maturity row both promised the base's declaration
  — because the receiver leg carried a `this` carrier and no `super` one. Fixed (8 lines,
  mirroring `typeCaptureThisMemberType`'s existing super branch) and measured against tsc,
  which navigates to `Base.pb` in the overridden shape and `Base.mb` in the inherited one.
  **THREE CORRECTIONS**: an enum member's declaration name does not "report nothing", it
  reports **`any`** (below, and still open); an object literal's own method
  "refuses a rename loudly" only once a CONTEXTUAL TYPE supplies it — with none it
  **renames completely** from either end, which the correction had in turn to be measured
  to find; a computed key is
  not silently missed, it is **reported in two of its three shapes** and silent only where
  the contextual member is optional. **ONE CLAIM CONFIRMED THE HARD WAY**: a template
  element access really is silent — the rename applies, the template keeps the old name,
  and the resulting program compiles clean. **THE COST TABLE'S BUILD COLUMN IS NOW PINNED
  and its wall column is marked not pinnable**, with `scripts/round930-ls-cost.sh` +
  `LanguageServiceCostMain` as the re-take (one process, one project, three rotations —
  the only comparison CLAUDE.md admits). Re-taken: rebuild 5.0–5.5 s (§ 3 said ~5.2, § 14
  said 5.5–5.9 — both drifted, in opposite directions), highlights 6.3 s on `checker.ts`
  and 5.0–5.5 s on `types.ts` (the row is a statement about a FILE, which is why it looked
  wrong), references 8.3–10.2 clean / 13.2–14.8 dirty, rename 14.3 s (`createTypeChecker`)
  – 21.0 s (`SyntaxKind`). `scripts/lsp_definition.py` is new, the fourth oracle.
  Suite 14,981 → 14,996 / 0 failures / 3 skipped; `cost_gate.py` +0.00% on all 20
  counters; `huge_methods.py --fail-over 0` clean on both modules; the round-920 token
  gate re-run (1,327 files, 101,287,620 chars, zero violations — which is § 14's own
  "101 M characters" claim, verified).

- [ ] **(CHK.5) COMPUTED KEYS — STAGES (a) AND (b) ARE LANDED (rounds 937/938); (c), (d),
  THE INDEX-SIGNATURE AXIS AND FIVE NEWLY MEASURED DUPLICATE GAPS REMAIN.**
  **(a) THE MEMBER-BUILDING SITES — DONE, round 937.** `interface I { [K]: number }`,
  `class C { [K]: number }` and `type T = { [K]: number }` now declare the member, in the
  property, method, get- and set-accessor forms, for every key spelling round 935/936
  resolves. It was NOT one site: six had to be levelled onto one namer, and two of them
  (`checkImplementsClauses`, `classMemberNamesTransitive`) compare a class's AST names to
  a target built from the resolved TYPE, so levelling the type side made a PRE-EXISTING
  Identifier-only drift reachable — two false positives with no computed key in them
  (`interface I { 1: string }` + `class C implements I { 1: string }`, and the same through
  a `static 1`) were closed as part of it. `checkComputedLiteralKeyMembers` now retracts
  before it emits, because the general relation reaches its TS2322 verdict once the key
  binds. Session note has the 40-row table and the 10-arm ablation.
  **(b) A DUPLICATE MEMBER DECLARATION — DONE, round 938, and it corrected its own
  premise.** This compiler ALREADY emitted TS2300 x2 + TS2717 for a plain
  `interface I { p: number; p: string }`, byte-identical to tsc, and for a type literal, a
  class, an enum, two getters, a numeric name and a class property-vs-method. Two things
  were wrong and both are closed: the member map was LAST-WINS where tsc keeps the FIRST
  (eight measured rows, including round 937's spurious TS2322, which was this defect and
  not a computed-key one), and neither duplicate SCAN could name a computed key — the class
  one knew `["a"]`/`[0]`, the interface one had no computed arm at all. Both now ask one
  namer. **The rule that decides the diagnostic came from a PRISTINE baseline, not from
  tsgo**: TS2300/TS2687 are the BINDER's checks and a LATE-BOUND key never reaches them
  (`dynamicNamesErrors` — `interface T0 { [c0]: number; 1: number }` gets NOTHING, `T3` gets
  TS2717 alone), where tsc 7.0.2 emits TS2300 for both; following tsgo reddens that corpus
  test. Same parting on the class `drop(1)` rule. `checkComputedLiteralKeyMembers` now
  retracts before it emits. Session note has the 21-row table and the 9-arm ablation.
  **(b2) NEW — FIVE DUPLICATE GAPS MEASURED IN ROUND 938 WITH tsc's ANSWER, EACH SMALL AND
  EACH SEPARATE.** (i) a MERGED-interface TS2717 — `interface I { p: number }` +
  `interface I { p: string }` is TS2717 at the second in tsc and silent here, because both
  duplicate scans are per-DECLARATION by construction (the first-wins TYPE is already
  right); (ii) an INTERFACE property-vs-METHOD pair is TS2300 x2 in tsc and silent here —
  `checkDuplicateInterfaceMembers` collects `PropertyDeclaration`s only, where its class
  twin collects four kinds; (iii) TS1117 for a late-bound OBJECT-LITERAL key
  (`{ p: 1, [K]: 2 }`) — `getPropertyKeyName`/`evaluateComputedPropertyName` is a THIRD
  namer with its own `__@computed:` scheme and its own numeric normalization, so widening
  it is not the one-line delegation the other two were; (iv) the required-vs-OPTIONAL
  TS2717 (`p: number; p?: number` — tsc says `number | undefined`); (v) **`C.p` reads the
  INSTANCE member's type when a static and an instance member share a name** — that is the
  unfinished `staticMembers` dual-population ("no behavior change yet" in
  `resolveInterfaceMembersCore`), not a duplicate rule, and it is the one of the five that
  is a WRONG TYPE rather than a missing diagnostic.
  **(c) A CONST IMPORTED FROM ANOTHER FILE, AND A CLASS `static readonly` KEY.**
  `import { IK } from "./k"; interface I { [IK]: number }` and `[C.B]` where
  `class C { static readonly B = "p" }`: both bind in tsc, both are still a false positive
  here (measured again round 937, on the DECLARATION side as well as the literal one). The
  syntactic walk cannot cross a file by construction; the route is the frozen binder tables
  (`resolveAlias`), which are deterministic and therefore allowed under round 935's law.
  **(d) THE `unique symbol` TYPE — unchanged, and round 937 CONFIRMED why it cannot land
  alone.** `declare const S: unique symbol` types as plain `symbol` here, so `[S]` and
  `[S2]` are ONE name. Round 936 predicted that naming the key on the literal side alone
  would invert the defect; round 937 measured the SAME inversion already live for a plain
  const (`const x: I = { [K]: 1 }` was TS2353 `'[K]'`, a false positive) and closed it by
  landing both sides together. (d) needs a `unique symbol` type keyed by the DECLARATION
  (tsc's `__@<desc>@<id>`, a name that survives a rename and an import) and both sides in
  ONE commit.
  **(e) NEW — THE INDEX-SIGNATURE AXIS, measured round 937 and belonging to neither (a) nor
  (d).** A computed key whose type is `string` (`let LW = "p"`), a literal UNION, or a
  dotted path through a VALUE (`obj.k`) gives tsc's interface, class and type literal a
  STRING INDEX SIGNATURE rather than a named member — `interface I { [LW]: number }` makes
  `i.p` a `number` in tsc, and `class C { [LW]: number }` likewise, where `c.p` is still
  **TS2339, a false positive** here. Late binding must keep REFUSING these keys; closing
  them is index-signature modelling. Round 936's `{ [L]: number; }`-vs-`{}` display row is
  the same gap seen from the display side.
  **(f) DONE, round 940 — THE TS2741 KEY NAME, the family's ONE measured PRISTINE divergence (round 939).**
  For a missing late-bound member we print `Property 'p' is missing in type '{}' but required
  in type 'I'` where tsc prints `'[K]'`. **Pristine names the key AS WRITTEN wherever it names
  one** — `'[E.A]'` (`assignmentCompatWithEnumIndexer`), `'["a"]'`
  (`duplicateIdentifierComputedName`, an ACTIVE gate), `'[c1]'` (`dynamicNamesErrors`, ACTIVE),
  `'[Symbol.toPrimitive]'` (`symbolProperty21`) — so pristine and tsgo AGREE here and we are
  the outlier. Round 937 recorded it against tsgo; round 939 confirmed the convention against
  pristine and verified our answer live at HEAD. No baseline covers the exact shape
  (`const K = "p"; interface I { [K]: number }; const x: I = {}`), which is why the suite is
  green. **LANDED round 940** at [formatPropertyDisplayName] — the ONE renderer the
  missing-property emitters already route the symbol through, so all twelve of its callers
  moved together — asking round 938's `computedKeyWrittenText`, which answers null for a
  spelling it cannot reproduce exactly. Pinned three ways (`[K]`, `[E.A]`, `["a"]`) with
  the negative controls that a NON-computed member keeps its bare name and a quoted string
  member keeps B291's quoted display; ablation arm A5 reddens exactly the three.
  **WHAT MUST NOT BE UNDONE**: the WELL-KNOWN-symbol route is deliberately not
  `computedSymbolKey` in general (tsc is SILENT for every computed key it cannot late-bind,
  measured over seven of them), and `getMemberName` itself stays unchanged — B451 records
  it as feeding ~20 callers including duplicate detection and abstract tracking, so the
  widening lives in `declaredMemberName` at the member-BUILDING call sites.

- [x] **(CHK.6) THE COMPUTED-KEY FAMILY RE-JUDGED AGAINST *PRISTINE* — DONE, round 939, and
  the verdict is that rounds 933-938 landed NOTHING pristine contradicts.** Rounds 933-937
  established their ground truth by running `tools/tsgo-7.0.2/lib/tsc`, the only reference
  compiler that RUNS on this box; round 938 then found the two references parting on this
  family's own territory, which left every row no corpus baseline covers resting on an oracle
  this project deliberately does not follow. The pristine oracle turned out to be on disk all
  along — `typescript-repo/tests/baselines/reference`, generated by the pinned pristine commit
  — and is now `scripts/pristine_oracle.py` (`--code` / `--pattern` / `--fixture`, every hit
  labelled ACTIVE vs not-generated, plus `--extract DIR`, which writes pristine's own input
  back out so our binary can be run over exactly what pristine saw). **34 landed decisions
  classified: 22 PRISTINE-CONFIRMED, 10 CORPUS-SILENT, 1 tsgo-ONLY, 1 PRISTINE-DIVERGENT** —
  the TS2741 key name, a message FORM round 937 had already recorded, now (CHK.5)(f).
  **The corpus protects much more of this family than the notes claimed**: `dynamicNames`,
  `dynamicNamesErrors`, `duplicateIdentifierComputedName`,
  `destructuredLateBoundNameHasCorrectTypes`, `checkDestructuringShorthandAssigment2`, the
  three `duplicateObjectLiteralProperty_computedName*` and **7 of the 10 TS2717 baselines in
  the whole corpus** are ACTIVE byte-exact gates sitting on these exact decisions.
  **And the strongest evidence is a negative**: `--extract` materialises pristine's own input,
  so our binary was run over **300** ungated pristine fixtures carrying a computed member key
  and differenced (line, code) against pristine's baseline. **277 of 300 emit nothing pristine
  does not**; of the 23 that do, four are (CHK.7) and NOT ONE of the other nineteen is
  attributable to rounds 933-938 — they are unimplemented checks in other families (`using`
  declarations, the private-modifier grammar, index-signature PARAMETER types, super-call
  ordering, a `declare global { interface SymbolConstructor }` that does not merge,
  `Symbol.hasInstance` narrowing, a `never` discriminant, module resolution). The four that
  ARE pristine divergences are older than the family, proved by the diff rather than argued.

- [x] **(CHK.7)(i) AND (iii) — LANDED, round 940, both FALSE POSITIVES, both CLOSED; (ii)
  AND (iv) RE-MEASURED AND RE-QUEUED BELOW, because round 939's entry was wrong about both
  in the direction that decides what to build.** (i) TS1117 was keyed on a computed key's
  SPELLING, so `var s: symbol; ({ [s]: 0, [s]() {}, get [s]() {} })` was TS1117 x2 here and
  silent in `symbolProperty1`/`2`/`3`; the namer now abstains — but ONLY when the key's own
  declaration is IN HAND and late binding still refused it, because a blanket abstain
  regresses `duplicateObjectLiteralProperty_computedName3` (an ACTIVE gate whose keys arrive
  through an `import * as keys`, which pristine binds by TYPE and round 935's syntactic
  resolver cannot follow across a file). (iii) An accessor followed by a PROPERTY is TS2300
  at the property alone — tsc's `PropertyExcludes = None` means a property declared last
  never trips the binder's duplicate check — which reproduces all 83 of
  `privateNameDuplicateField`'s rows and both halves of `duplicateClassElements`.
  **Measured: `privateNameDuplicateField` 3 ours-only rows -> 0; the 630-fixture pristine
  sweep 403 -> 397 ours-only rows with ZERO fixtures regressed; the 8-profile grid
  added=0 removed=0; suite 15,168 -> 15,193 with no baseline moved.**

- [x] **(CHK.8) — THE 630-FIXTURE PRISTINE SWEEP, TRIAGED AND ITS INSTRUMENT REPAIRED;
  TWO FALSE-POSITIVE FAMILIES CLOSED (round 941).** `scripts/pristine_sweep.py` supersedes
  round 940's sweep and **121 of that round's 397 OURS-ONLY rows (30.5%) were the
  instrument's own configuration**: the case-file fallback carried the `// @target:`
  directives tsc STRIPS (a whole-file line shift, 27 fixtures); directives were read from
  the EXTRACTED text, which the `.js` baseline echoes WITHOUT them; and a missing case file
  left no target where the baseline's `(target=…)` suffix still records it. An ALIGNMENT
  ORACLE (each reconstructed input compared line-for-line against pristine's `==== file ====`
  annotation) now makes the first defect impossible to reintroduce silently. **The triage of
  the remaining 334 rows is `docs/pristine-divergences.md` and its cause-class rules are
  `scripts/pristine_triage.py`** — genuine FP 182 (48.8%) / cascade 90 / harness 59 /
  deliberate convention 42. Closed this round: TS2376 (a `super` call need not be FIRST —
  tsc walks the statement list to the first IMMEDIATE `this`/`super` reference, stopping at
  arrows, function declarations/expressions, property declarations and method-like BODIES
  but NOT at their computed NAMES) and TS18028 (the private-identifier gate reads the target
  the user ASKED FOR, not the raw `ES3` default). Sweep **373 -> 334**, zero fixtures
  regressed, pristine-only 777 -> 776 (a true positive GAINED); 8-profile grid added=0
  removed=0 on all eight; suite 15,193 -> 15,214 with no baseline moved.

- [x] **(CHK.9) INDEX-SIGNATURE PARAMETER TYPES — 12 OURS-ONLY TS1268 ROWS -> 0, AND TWO
  TRUE POSITIVES GAINED (`indexSignatures1`, round 945).** tsc's rule, read off the pinned
  sources (`checkGrammarIndexSignatureParameters` + `isValidIndexKeyType`), has three parts we
  had two of. **The intersection arm was missing entirely**, so every BRANDED string
  (`type Id = string & { __tag: 'id' }` — the shape the rule exists for) was TS1268, and an
  `IntersectionType` NODE was not even offered to the type engine, so a syntactic
  `` `${string}xxx${string}` & `${string}yyy${string}` `` never got a verdict either. **And the
  generic test read only a bare `TypeReference`**, which is why `[key: T | number]` and
  `[key: T & string]` were TS1268 where pristine says TS1337 — the cause being that an alias's
  own `T` resolves to `anyType` at that grammar check, so the question has to be asked of the
  AST. Note `someType`/`everyType` distribute over UNIONS only: an intersection is valid when
  SOME constituent is (`string & 'a'` is a legal key), and reading that as `every` is the
  round's B4 arm. Measured: sweep **310 -> 298** ours-only with 0 added, pristine-only
  **775 -> 773**, zero fixtures regressed; 8-profile grid `added=0 removed=0`.

- [ ] **(CHK.10) DEFINITE ASSIGNMENT THROUGH A LATE-BOUND ELEMENT ACCESS — 4 OURS-ONLY
  TS2564 ROWS (`strictPropertyInitialization`, ALIGNED, round 941).** `class C12 { [a]: number;
  [b]: number; ['c']: number; constructor() { this[a] = 1; this[b] = 1; this['c'] = 1 } }`
  with `const a = 'a'; const b = Symbol()`: pristine sees the definite assignment through the
  ELEMENT ACCESS and is silent, we report `Property '…' has no initializer`. Same fixture
  reports `[E.A]` (an enum member key). Small, and squarely in the computed-key arc's own
  family — note that the triage classifier exempts this fixture by name from the
  strict-by-default bucket for exactly this reason. **CONFIRMED GENUINE, round 943**: that
  fixture's case file is not in this clone, so the sweep recovers no directives for it — but
  its own baseline carries **20 TS2564**, i.e. pristine had `strictPropertyInitialization`
  ON, so these four rows are not the convention. (The `--tsc-strict-default` arm deleted them
  until it was guarded on case-file presence; see `docs/pristine-divergences.md` § 0b.)

- [x] **(CHK.11) ELEMENT-ACCESS DISCRIMINANT NARROWING — 11 OURS-ONLY ROWS -> 0
  (`typeGuardNarrowsIndexedAccessOfKnownProperty1`, round 942).** The cause is one sentence:
  **tsc's `isMatchingReference` compares references by SYMBOL and ours compares the path
  STRINGS `getReferencePath` builds**, and every discriminant reader was written against the
  DOTTED spelling alone. FOUR mechanisms, all measured: `singleLevelDiscriminantSegment` (the
  switch reader accepts `name[seg]`); `getTypeOfElementAccess` flow-narrows its UNION
  RECEIVER (B1.1's gate, which its dotted twin has always had); `getReferencePath`
  NORMALISES an identifier-spellable string index onto the dotted segment, because the
  fixture mixes both spellings inside one expression (`s[0]["sub"].under["shape"]`); and
  `requiredEnumSwitchKeys` + `paramMemberChainType` accept an element-access discriminant and
  a multi-segment receiver, which is the two TS2366. **A FIFTH — the 17.34d half, narrowing
  the access's own union RESULT — was written, measured INERT (its ablation arm reddened NONE
  of the 21 pins and no probe could be built where it fires) and REMOVED.** **Measured: 11 -> 0, sweep 334 -> 318 with zero fixtures regressed, 8-profile grid
  added=0 removed=0.** `docs/pristine-divergences.md` § 3.4.

- [x] **(CHK.12) `[Symbol.hasInstance]` NARROWING — 5 OURS-ONLY ROWS -> 0, AND THE ENTRY WAS
  WRONG ABOUT ITS OWN SECOND FIXTURE (round 942).** `instanceof` now asks the RHS type for a
  `[Symbol.hasInstance]` method whose return is a non-`asserts` TYPE PREDICATE over parameter
  0 and uses its target — round 838's `instanceTypeOfConstructorValue` named that leg as its
  one deliberate omission — which answers the three shapes `prototype` and the construct
  signatures cannot: a GENERIC construct signature, SEVERAL construct signatures, and one
  returning `any`. **Two rules read off PRISTINE's baseline and re-read off tsgo 7.0.2: a
  usable predicate DECIDES (a `value is any` target narrows NOTHING and must not fall through
  — pristine's own lines 142/143), and an `instanceof` stays `checkDerived = true` even when
  the candidate came from a predicate, so a UNION candidate is DISTRIBUTED and its
  narrow-down direction is the NOMINAL base-chain test (`C1 | A` narrowed by `C1 | C2` is
  `C1`), scoped to a union candidate so round 425's single-candidate arm is byte-identical.**
  Measured: 5 -> 0 with pristine-only 8 -> 7, i.e. a true positive GAINED.
  **The entry's other fixture is MIS-BUCKETED**: `controlFlowInstanceofWithSymbolHasInstance`
  is 7 rows of which **6 are a PARSER GAP** (`abstract new (...) => infer U`), queued as
  (CHK.14), and 1 is the `instanceof` intersection tail, queued as (CHK.15). Out of scope by
  construction: a `static [Symbol.hasInstance]` on a CLASS declaration, which
  `resolveInstanceOfRhsType` answers from the declared type before the leg is reached.
  `docs/pristine-divergences.md` § 3.5.

- [x] **(CHK.14) `abstract new (…) => T` AND THE CONSTRUCTOR-TYPE `infer` — CLOSED round 947,
  15 ours-only rows (297 -> 282), PRISTINE-ONLY FLAT at 769, zero fixtures regressed.**
  `docs/pristine-divergences.md` § 3f. **This entry's own second half was diagnosed
  backwards and the correction is the round's product**: the defect is NOT "an `infer`
  inside a PARENTHESIZED extends clause does not publish its name" — parentheses are
  irrelevant (`collectInferTypeNames` recurses through `ParenthesizedType` and always has),
  the missing arm was **`ConstructorType`**, and the UNPARENTHESIZED spelling
  `T extends new () => infer U ? U : never` failed identically while the parenthesized
  FUNCTION-type spelling always worked. It is also not a parser item: it is a one-arm gap in
  the INV.4(c)(iii) scope walker, whose sibling `collectInferDecls` carries the arm with a
  comment about keeping parity with it. Landed alongside it: `parsePrimaryType`'s
  `abstract`-then-`new` lookahead (tsc's `isStartOfFunctionTypeOrConstructorType` +
  `parseModifiersForConstructorType`), whose SPAN bound is pinned in `-project` because no
  core diagnostic reads a `ConstructorType`'s `pos`. Held as false NEGATIVES on purpose: the
  `infer` still does not RESOLVE through a constructor type (`D<new () => K>` answers `any`),
  and the recorded `modifiers` set is read by nothing — TS2511 is its named future consumer.

- [x] **(CHK.25) `using` / `await using` DECLARATIONS DID NOT PARSE — 33 OURS-ONLY ROWS OVER
  FOUR FIXTURES, THE LARGEST SINGLE CASCADE IN THE WHOLE PRISTINE POPULATION. LANDED round
  948: ours-only **282 -> 251** over 74 -> 71 fixtures, pristine-only **769 -> 767** (two
  TS2353 GAINED), zero fixtures regressed, zero corpus baselines moved.** `using x = expr;`
  reported TS1434 at the `using` and then TS2304 for every name the failed statement never
  bound. **The representation is tsc's own and needed no new node**: a
  `VariableDeclarationList`'s `flags` field already IS the head token, so `using` is
  `SyntaxKind.UsingKeyword` — no `forEachChild` arm, no `NodeKind`, no binder arm, because the
  binder's `isVar` test already reads any non-`var` head as block-scoped. `await using` is two
  tokens collapsed onto a synthetic `SyntaxKind.AwaitUsingKeyword` the scanner never produces.
  **The whole risk was the CONTEXTUAL KEYWORD and it did NOT materialise anywhere**: the eight
  profiles carry 336 occurrences of `using` as an identifier / property name and zero
  declarations, and the binary grid is byte-identical on all eight. Landed with the grammar
  rules (TS1155 / TS1492 / TS1493 / TS1494 / TS1491 / TS1495), the disposability rule
  (TS2850 / TS2851, positive-evidence-only and switched off unless the lib declares
  `Disposable`), and a VERBATIM emit of the head. `docs/pristine-divergences.md` § 3g.

- [ ] **(CHK.26) `infer U extends T` FOLLOWED BY A CONDITIONAL `?` IS PARSED AS A CONSTRAINED
  INFER WHERE tsc PARSES A CONDITIONAL — 8 OURS-ONLY ROWS, `inferTypesWithExtends1` lines 95 /
  103 / 105 (sub-triaged round 947, § 2.3 P2).** **`infer X extends` itself ALREADY PARSES**
  and has for as long as `parseTypeParameter` has handled a constraint — round 941's label for
  this bucket named the wrong thing. What fails is the DISAMBIGUATION: tsc's
  `tryParseConstraintOfInferType` parses `extends <type>` with conditional types DISALLOWED
  and rolls the whole `extends` back when the next token is `?`, **unless it is already in a
  disallow-conditional context** — so `T extends (infer U extends number ? 1 : 0) ? 1 : 0` is
  a conditional inside the parens (pristine's own comment on the line says *"ok, parsed as
  conditional"*) while `T extends infer U extends string ? U : never` keeps its constraint.
  We take the constraint unconditionally and cascade TS1005 / TS1109 / TS1128. **The rollback
  alone is NOT the fix and would break the second shape**: it needs the
  `disallowConditionalTypes` CONTEXT threaded through `parseType`'s conditional production
  (`extendsType` and a mapped type's `nameType` set it; a parenthesized type clears it) — an
  edit to the production the frozen-subsystem warning is about, which is why round 947 scoped
  it out rather than attempting it beside a landing change. `scanner.tryScan` is already the
  rollback primitive (`tryParseTypeParameters` is the reference shape). Pinned SILENT-side by
  `AbstractConstructorTypeTest.scoped out - an infer constraint is not re-read as the
  enclosing conditional`, which asserts today's TS1005 so the fix has to move it.

- [ ] **(CHK.27) THE `using` FALSE NEGATIVES ROUND 948 LEFT BEHIND — ALL FOUR ARE FEATURES
  THIS COMPILER SIMPLY DOES NOT HAVE, AND NONE COSTS AN OURS-ONLY ROW.** (i) **The DOWNLEVEL
  EMIT.** The head is emitted VERBATIM, which is tsc's own output only at a target with
  explicit resource management (>= ESNext); below it tsc rewrites the block through
  `__addDisposableResource` / `__disposeResources`, and the ~439 `usingDeclarations*` baselines
  upstream are mostly `(module=…,target=…)` variations of exactly that. Verbatim is the SAFE
  half of the choice — rewriting the head to `var` would silently delete the disposal — but a
  low target now emits a `using` a downlevel runtime cannot execute. **This clone carries no
  `using` case file, so the generated corpus still gates none of it**; an emit landing needs
  its own gate (`--outDir` + `diff -r`, since `--noEmit` makes every instrument here blind to
  transform/emit). (ii) **`declare using` — TS1545 `'using' declarations are not allowed in
  ambient contexts.`** (and TS1546); it needs an arm in `parseDeclareDeclaration`, which
  round 948 did not touch, so `declare using x: T;` still cascades. (iii) **The `case` /
  `default`-clause rule, TS1547 / TS1548**, which tsc decides from `declarationList.parent
  .parent` being a clause. (iv) **The `await using` CONTEXT rules — TS2852 / TS2853 / TS2854 and
  TS18054**; a top-level `await using` in a non-module file, or one inside a class static
  block, is silent today. Also unreproduced: TS2850's nested
  `Property '[Symbol.dispose]' is missing …` elaboration and its TS2728 related info.

- [ ] **(CHK.28) A DECORATED CLASS *EXPRESSION* IN AN INITIALIZER IS REFUSED — TS1206
  `Decorators are not valid here.`, 2 OURS-ONLY ROWS
  (`usingDeclarationsNamedEvaluationDecoratorsAndClassFields` lines 14 / 18, round 948).**
  `const C = @dec class { }` and `using C = @dec class { }` both take it; pristine accepts
  both (decorators on class expressions have been legal since TS 5.0). **It is NOT a `using`
  defect** — the `using` parse cascade had merely been masking it, which is why closing
  (CHK.25) took the fixture 10 -> 2 rather than 10 -> 0. Reproduce with
  `const C3 = @dec class { static x = 1; };` at any target; the emitter half (tsc's
  `__esDecorate` for a class expression) is a separate question from the checker's refusal.

- [ ] **(CHK.15) THE `instanceof` POSITIVE BRANCH HAS NO INTERSECTION TAIL — 1 OURS-ONLY ROW,
  BUT A GENERAL RULE (`controlFlowInstanceofWithSymbolHasInstance` line 26, round 942).**
  `s = new Set<number>(); if (s instanceof Promise) {} s.add(42)` reports
  `Property 'add' does not exist on type 'Promise<any> | Set<number>'` where pristine is
  silent: tsc's `getNarrowedType` ends in `maybeTypeOfKind(t, Instantiable) … ?
  getIntersectionType([t, c])`, so the then-branch is `Set<number> & Promise<any>` and the
  JOIN back is `Set<number>`; ours answers the CANDIDATE alone (`narrowByInstanceOf`'s
  `isMatch -> classType`), so the join is a union. `narrowByCallPredicateWorker` already
  carries the equivalent round-425 "positive-empty INTERSECTION fallback" for a PREDICATE
  target — this is the same rule at the `instanceof` site, and its blast radius is every
  `instanceof` in the program, so it needs the 8-profile grid and the 630-fixture sweep, not
  a pin alone.

- [x] **(CHK.16) A DECLARATION'S OWN TYPE PARAMETERS WERE NOT IN SCOPE FOR THE TS2344
  CONSTRAINT WALKER — LANDED, round 943, and it FIXES A FALSE NEGATIVE IN THE SAME MOVE.**
  `checkConstraintsInStatements` pushed them for a `FunctionDeclaration` (round 82, whose
  comment names this exact defect), for a type ALIAS only when the body was an `ImportType`
  (B98a's narrow gate) and for a class or interface never — so a parameter SHADOWED by a
  same-named file-level type was resolved to that type and judged against the callee's
  constraint. `withDeclTypeParamScope` is now the one site, used by the alias, class and
  interface branches, heritage clauses included. Pristine `conditionalTypes1` is two
  ours-only TS2344 from `interface A` (line 309) against `type And<A extends boolean, B
  extends boolean> = If<A, B, false>` (line 171) — **138 lines apart, which is why every
  hand-written reduction was silent and the bisection had to delete the file's TAIL**. The
  other direction was equally wrong, so the fix ADDS diagnostics: `type Loose<Q> = Box<Q>`
  with `interface Box<S extends string>` was silent and now reports TS2344 as pristine does,
  and over 611 fixtures that gained NO ours-only row. **The first cut fixed only the alias
  branch and a "regression guard" pin went RED — that is how the class/interface half was
  found.** Sweep **318 -> 316**, pristine-only 775 -> 775, zero fixtures regressed, 8-profile
  grid added=0 removed=0, suite 15,235 -> 15,248 with no baseline moved.
  `docs/pristine-divergences.md` § 3c.

- [x] **(CHK.17) LIB AVAILABILITY WAS DECIDED FROM THE *RAW* `ES3` TARGET DEFAULT WHERE tsc
  DEFAULTS AN UNSET TARGET TO THE LATEST — LANDED, round 944.** `CompilerOptions.libTarget`
  (unset -> ES2024, explicit -> itself, `es5` included) is now the one input to
  `libFeatureAvailable`, `libProvidesGlobalAt` and the lib-SET resolution in `bindRealLibs` /
  `RealLibSnapshots.prewarmParsedLibFiles`; NOT `effectiveTarget`, which maps an explicit
  `es5` UP to ES2015 and would delete that program's genuine TS2550/TS2583 (round 941's
  TS18028 fork). Sweep **316 -> 313**, pristine-only 775 -> 775, zero fixtures regressed,
  8-profile grid `added=0 removed=0` on all eight (every profile sets BOTH `target: es2020`
  and `lib: ["es2020"]`, so it is a pure control), suite **15,248 -> 15,262 / 0** with NO
  corpus baseline moved. The CLAUDE.md entry that recorded the raw reading as deliberate is
  corrected: it was INVISIBLE, not tested — 0 of 55 case files touching a `LIB_MIN_TARGET`
  member name, 0 of the ~30 referencing a `LIB_GLOBAL_INTRODUCING` global and 0 of the 26
  carrying an `and N more` count omit `@target`/`@lib`.

- [x] **(CHK.21) THE 23 `options.target < ES2015` DOWNLEVEL GATE LINES NOW READ
  `CompilerOptions.defaultedTarget` — AND THE ENTRY'S OWN EVIDENCE WAS MISATTRIBUTED, SO THE
  FAMILY'S SIGN IS THE OPPOSITE OF WHAT IT SAID (round 945).** Round 944 filed this as a
  FALSE-NEGATIVE item on four pristine-only TS2488 rows the gates were assumed to suppress.
  Run at an EXPLICIT `es2015` and `esnext`, where those gates are wide open, we are **still
  silent for all three shapes** — so no gate suppresses them and they are an unimplemented
  iterability check, re-filed as **(CHK.22)**. The real family is a FALSE-POSITIVE one that
  neither instrument could see: the raw target's `ES3` zero value made a tsconfig naming no
  `target` collect **six** diagnostics pristine does not emit (TS1250, TS1501, TS1503,
  TS2659, TS2737, TS18045 — measured on one 14-line file, before vs after, with the explicit
  `es5` and `es2017` columns byte-identical). Oracle: **every** TS1250/TS1501/TS1503/TS2396/
  TS2659/TS2737/TS18045/TS2802 baseline in the pristine corpus comes from a fixture with an
  explicit `@target`. Three raw-target sites are KEPT with reasons in the KDoc (the two
  `target >= ES2015 || …` strict-mode determinations, which a flip makes unconditionally
  strict, and one per-fixture baseline pin). `docs/pristine-divergences.md` § 3d.1.

- [x] **(CHK.22) THE for-of / SPREAD OPERAND'S `[Symbol.iterator]()` RETURN IS NOW CHECKED —
  LANDED, round 946: 4 PRISTINE-ONLY TS2488 ROWS -> 0 WITH OURS-ONLY FLAT, THE FIRST ENTRY IN
  THIS ARC THAT MOVES ONLY THE FALSE-NEGATIVE COLUMN.** `spineCheckIterableOperand` /
  `iterableOperandFailure` reproduce tsc's `getIterationTypesOfIterableSlow` ->
  `getIterationTypesOfMethod("next")` chain for `for...of` and ARRAY-LITERAL spread: an
  OPTIONAL `[Symbol.iterator]?()` is TS2488 (tsc's `method && !(method.flags & Optional)`),
  and a zero-argument `[Symbol.iterator]()` whose RETURN type has no `next` is TS2488 + the
  related **TS2489 `An iterator must have a 'next()' method.`**. **THE CHECK IS
  POSITIVE-EVIDENCE-ONLY AND THAT IS THE WHOLE FP FIREWALL**: it fires only where the member
  is FOUND and provably broken and bails on everything else, so every bail is a false
  negative and no bail is a false positive — which is why a new diagnostic on the commonest
  construct in the language moved **zero** of ~13k corpus baselines. **`this` READS AS `any`
  HERE** (no polymorphic `this` type), so `[Symbol.iterator]() { return this }` — three of
  the four rows — needed `iteratorMethodThisReturn`, a bounded declaration read that answers
  the CARRIER, which is tsc's own answer rather than a widening. Sweep **297 -> 297
  ours-only, pristine-only 773 -> 769**, zero fixtures regressed; 8-profile grid `added=0
  removed=0`; suite **15,294 -> 15,324 / 0 / 3** with no baseline moved; `cost_gate.py`
  `typeOfExpr.calls +0.22%` (the per-operand type read — a reached-ness proof), rebaselined
  in the same commit. 11-arm ablation, every arm at `ran 63`.
  `docs/pristine-divergences.md` § 3e.

- [ ] **(CHK.23) THE MISSING HALF OF THE ITERABILITY CHECK — A TYPE WITH NO
  `[Symbol.iterator]` AT ALL IS STILL ACCEPTED, AND SO ARE FOUR OTHER CONSTRUCTS (round 946,
  scoped out with tsc's answer known for every row).** § 3e.3 of `docs/pristine-divergences.md`
  is the table. The big one is the MISSING-member case, which is where tsc's rule needs a
  complete model of what is iterable — arrays, strings, tuples, `Iterable<T>`, a constrained
  type parameter, every union of them and the built-in iterator families — and one gap in
  such a model is a false positive on `for...of`; note that under the EMBEDDED lib only
  `IterableIterator<T>` declares `[Symbol.iterator]` at all, so the model cannot be built
  from member lookup alone there. The rest, each already pinned SILENT in
  `IterableOperandProtocolTest`: an OPTIONAL `next` (tsc reports it; refused because no
  pristine baseline here measures it), an iterator type with an empty member table or a
  string index signature, `[Symbol.iterator]` requiring an argument on a CLASS (B438e owns
  only the object-literal spelling and its hard-coded TS2322 chain), and the four other
  constructs — CALL-argument spread, array DESTRUCTURING, `yield*` and `for await…of`, whose
  `IterationUse` flags carry different diagnostic families (TS2504 / TS2569 / TS2461).

- [ ] **(CHK.24) THERE IS NO POLYMORPHIC `this` TYPE — `return this` AND `(): this` BOTH
  RESOLVE TO `anyType` (round 946, measured).** `class C { m() { return this } n(): this
  { return this } }` makes `c.m()` and `c.n()` answer `any`, so every `this`-returning
  builder chain in a checked program is untyped and every rule that reads such a return
  bails. Round 946 needed exactly one question answered — "does the carrier have `next`" —
  and got it from `iteratorMethodThisReturn`, a bounded read of the member's DECLARATION;
  that helper is a stopgap and says so. The general fix is tsc's `getThisType` plus the
  `ThisType` type-node arm, and its blast radius is every method-chain return in the
  program, so it needs the 8-profile grid and the 630-fixture sweep.

- [ ] **(CHK.18) `t[k] = v` THROUGH A GENERIC INDEXED ACCESS IS TS2862 WHERE PRISTINE SAYS
  TS2322 — 3 ROWS, A CODE DIVERGENCE RATHER THAN A FALSE POSITIVE
  (`keyofAndIndexedAccessErrors` lines 140-142, round 943).**
  `function test1<T extends Record<string, any>, K extends keyof T>(t: T, k: K) { t[k] = 42 }`:
  we refuse the WRITE (`Type 'T' is generic and can only be indexed for reading`), pristine
  permits it and rejects the VALUE (`Type 'number' is not assignable to type 'T[K]'`). tsc's
  rule reads the receiver's CONSTRAINT for a writable index signature before refusing; ours
  does not. Both compilers error at the same position, so this is FORM under
  `docs/logical-parity.md` § 2 — but the form is a different diagnostic identity, and the
  underlying gate is a real modelling gap that would show as a false POSITIVE the moment a
  program writes through a constrained generic index legally.

- [x] **(CHK.19) A FUNCTION-BODY TYPE ALIAS IS NOT BOUND, SO THE LIB'S `Omit` WON — 1 OURS-ONLY
  TS2314 -> 0 (`conditionalTypes1` line 297, round 945).** `getTypeParamInfo` is a whole-program,
  NAME-keyed scan with no node context, so a block-scoped `type Omit<T>` (CLAUDE.md's B83.5: the
  binder never binds a declaration nested in a function body) was invisible and the LIB's
  two-parameter `Omit` answered the arity question. Closed with round 748's
  `lexicalTypeSymbolForNode` shape one declaration kind over — a name gate computed in the SAME
  sweep that already censuses block-scoped enums, then an ancestor walk over the INV.2(c)
  `lexicalScopes` reading `scope.symbols` ONLY. **It does not re-open the INV.3 minefield the
  B83.5 entry warns about, and the reason is structural**: `declareLexical` skips any name the
  main binder already bound in that container, so a scope-space hit can only be a declaration the
  conventional tables do not have. Measured: sweep **298 -> 297**, 0 added, pristine-only FLAT,
  zero fixtures regressed; 8-profile grid `added=0 removed=0`; `cost_gate.py` moved **−24
  `globals.lookups` (−0.003%)** — tsc's own sources carry block-scoped generic aliases
  (`PropOfRaw<T>` in commandLineParser.ts among them) that now answer locally instead of running
  the global scan, and the grid proves no verdict changed. **STILL OPEN, and named here rather
  than left implicit**: `outerTypeParamNames` is supplied by the TypeAliasDeclaration caller only,
  so a CLASS's or INTERFACE's own type parameters are still `emptySet()` and
  `interface I<T> { [k: T]: string }`-style shapes keep the older answer.

- [ ] **(CHK.20) VARIADIC TUPLE TYPES ARE UNMODELLED — 30 OURS-ONLY ROWS, THE SINGLE
  LARGEST FAMILY LEFT, AND IT IS A FEATURE RATHER THAN A DEFECT (`variadicTuples1`, round
  943).** `getTupleType` maps a `RestType` element through `is RestType ->
  getTypeFromTypeNode(elem.type)` — the arm a PLAIN element gets — so **`[...T]` is built as
  the one-element tuple `[T]`**. Three lines reproduce it:
  `function f<T extends unknown[]>(t: T, m: [...T]) { t = m }` reports `Type '[T]' is not
  assignable to type 'T'`. What is missing is TypeScript 4.0's variadic tuples in full: a
  tuple type with a variadic/rest element, its normalisation, the three relation rules the
  fixture's own section header states ("for a generic type `T`, `[...T]` is assignable to
  `T`, `T` is assignable to `readonly [...T]`, and `T` is assignable to `[...T]` when `T` is
  constrained to a mutable array or tuple type"), `keyof` over one, spread-argument arity,
  and inference into a leading/trailing rest (the fixture's whole `curry` section). M3-scale;
  do NOT attempt it as a bounded rule.

- [ ] **(CHK.13) THE STRICT-BY-DEFAULT CONVENTION IS THE LARGEST *SYSTEMATIC* DIVERGENCE
  LEFT — 46 OURS-ONLY ROWS (42 by code, plus the four round 943 found wearing TS2683 /
  TS7019 / a `strictNullChecks` TS2322), AND IT IS AN OWNER DECISION, NOT A FIX (round
  941, re-sized round 943).** TS2564 / TS2454 / TS7010 fire in this compiler unless `@strict: false` is
  EXPLICITLY set (`Checker.kt`'s dispatch reads `!options.strictExplicitlyFalse`), where tsc
  requires `strict` (or the individual flag) to be ON. A real project with no `strict` in
  its tsconfig therefore gets `Property 'x' has no initializer and is not definitely
  assigned in the constructor` from us and nothing from tsc — `keyofAndIndexedAccess` alone
  is 17 rows for four plain `name: string;` class fields. Invisible to the corpus, whose
  fixtures set the directive. **Do not "fix" it without the owner**: the convention is
  load-bearing for the generated suite's expectations.

- [ ] **(CHK.7)(ii) A COMPUTED KEY'S *EXPRESSION* IS NEVER CHECKED, SO AN UNRESOLVABLE
  `[Symbol.x]` BECOMES A REQUIRED MEMBER — RE-MEASURED round 940 AND IT IS A MODELLING
  CHANGE, NOT A NAMING ONE.** `symbolProperty52`: pristine reports **TS2339 `Property
  'nonsense' does not exist on type 'SymbolConstructor'` TWICE** — once at the KEY inside
  `var obj = { [Symbol.nonsense]: 0 }` and once at the later `obj[Symbol.nonsense]` — and
  gives the literal NO such member, so `obj = {}` is silent. We emit **neither** the key's
  TS2339 (we get only the element-access one) **and** a TS2741
  `Property '[Symbol.nonsense]' is missing in type '{}'`. So the FP and the FN have ONE
  cause: `computedSymbolKey` invents `"[<dotted>]"` as a STRUCTURAL placeholder (round 723,
  and it is what makes tsc's own `Set<TElement>` literal's `[Symbol.iterator]` match) with
  nothing checking that the key expression resolves at all.
  **TWO SHAPES, and the cheap one is refused with a reason.** (a) The cause-level fix is
  tsc's `checkComputedPropertyName`: check the key EXPRESSION, emit TS2339/TS2464, and
  declare no member when it errors. That also closes pristine's TS2464 across the whole
  `computedPropertyNames*_ES6` set, which the round-939 sweep records as one of the largest
  ours-*missing* families. (b) Narrowing `computedSymbolKey` to keys whose `Symbol.<name>`
  is a REAL `SymbolConstructor` member is cheaper and is REFUSED as written: a hardcoded
  well-known list drifts from the lib and would DELETE a member for any symbol the list
  lacks — a TS2741 false positive in the other direction — while asking the type system
  means a member-resolution call from inside `getTypeOfObjectLiteral`, i.e. exactly the
  round-935 ambient-input hazard one layer down. **The whole population is 1 FP row in an
  ungated fixture on a program pristine already rejects twice; the prize is the FN.**

- [ ] **(CHK.7)(iv) STRING/NUMERIC MEMBER-NAME EQUIVALENCE IS MISSING IN THE *TYPE-LITERAL*
  SCAN ONLY, AND IT IS A FALSE **NEGATIVE** — round 939's entry has both the direction and
  the scope wrong.** Re-measured on `numericStringNamedPropertyEquivalence`: pristine emits
  7 rows, we emit 4, **ours-only is ZERO**. The CLASS scan already normalizes
  (`memberKey`'s `normalizeNumericKey`, so line 6 matches) and the INTERFACE scan matches
  lines 10/12 by accident — `1`'s text is already canonical. What is missing is
  `var a: { "1": number; 1.0: string }`: `checkDuplicateInterfaceMembers` names a numeric
  member through `getMemberNameText`, which returns the RAW text, so `"1"` and `1.0` do not
  collide and pristine's **TS2300 x2 (16,5 / 17,5) + TS2717 (17,5)** are all lost.
  **THE FIX IS ONE LINE PLUS A DISPLAY SPLIT, AND THE SPLIT IS THE REAL WORK**: group by
  `normalizeNumericKey`, but pristine prints **two different names for the same member** —
  TS2300 says `'1'` (tsc's binder message uses the SYMBOL name) and TS2717 says `'1.0'`
  (the checker's `declarationNameToString` of the later declaration, and its related TS6203
  says `'1.0'` too, at the position of the `"1"` member). `PropInfo` carries one `display`
  today, so it needs a second field. Low blast radius (a numeric member name whose text is
  not already canonical, in an interface or type literal) and it can only ADD diagnostics
  pristine already has — but it is an FN, so it does not move the v1 zero-FP metric.

- [x] **(CHK.4) THE QUALIFIED, TYPE-ANNOTATION AND WELL-KNOWN-SYMBOL ROUTES — LANDED,
  round 936, both directions, and the residue is re-scoped as (CHK.5) above.** Three
  capabilities, each a false POSITIVE in the supply direction and a false NEGATIVE in the
  excess one at the same time. (i) QUALIFIED keys — `NS.K`, `NS.Inner.IK`, a dotted
  `namespace A.B`'s const, a MERGED namespace's second block, and a const-or-plain ENUM
  member declared inside a namespace: all bind in tsc, all were TS2741 here and silent
  there. Resolved by descending `ModuleBlock` statements SYNTACTICALLY, because
  `currentFileLocals` is ambient and round 935 measured what that costs a member name; the
  one symbol-table consult left is the enum leaf, whose VALUES are in the binder's frozen
  tables and nowhere in the AST. (ii) The TYPE-ANNOTATION spellings — a no-substitution
  template-literal TYPE and a TYPE ALIAS to a literal, including a chain. **`TemplateLiteralType`
  is not a structured node in this parser** (B65.1: empty spans, the whole raw slice in
  `head.rawText`), so `templateSpans.isEmpty()` is true for a SUBSTITUTING one too and
  `head.text` answers `""` — a name matching no member, which reached the excess check as a
  real member on the first build. The raw text is the only discriminator that exists.
  (iii) WELL-KNOWN SYMBOLS in the excess check, which required one embedded-lib line:
  `IterableIterator<T>` did not declare the `[Symbol.iterator]()` member the real lib
  declares, so a literal supplying it against an `IterableIterator`-extending interface
  read as excess (the round-456 pin, and the ONLY red the suite produced). Refused, with
  tsc agreeing on every row: a widened namespace `let`, a substituting template type, an
  alias to a union, and — measured over seven of them — every computed key tsc cannot
  late-bind, which is why the well-known route demands the receiver be `Symbol` with no
  local binding of that name rather than re-admitting `computedSymbolKey` generally.
  28 pins, 13-arm ablation. The `NS.K` FP is gone; the SYMBOL axis verdict is that the
  well-known half was SMALL and the `unique symbol` half is MODELLING — see (CHK.5)(d).

- [x] **(CHK.3) LATE-BOUND COMPUTED KEYS — LANDED, round 935, BOTH DIRECTIONS IN ONE
  COMMIT. One missing capability was a false POSITIVE on one side and a false NEGATIVE on
  the other, and the round's product is that **tsc's own rule is NOT PORTABLE AS WRITTEN**.**
  Supply: `const K = "p"` / `const enum E { P = "p" }` + `{ [K]: 1 }` / `{ [E.P]: 1 }`
  satisfy a required `p` in tsc and were TS2741 here. Excess: the same keys spelling a name
  the target LACKS are TS2353 in tsc, named as WRITTEN, and were silent here. Both are now
  parity, plus every row the table was extended with before designing: a const ALIAS chain,
  a `let` with a literal ANNOTATION (const-ness is not the criterion), a `declare const`, a
  const whose literal INITIALIZER beats a union annotation, a plain (non-`const`) string
  enum, a NUMERIC enum member and a numeric const (named by the VALUE's canonical string,
  so `1e3` is "1000"), a body-local const and an inner const SHADOWING an outer one.
  Refused, with tsc agreeing on every one: a widened `let`, a genuine literal UNION, a plain
  `symbol`, a bare type parameter, a substituting template, and an AMBIENT non-`const` enum
  member with no initializer (round 746's opaque rule turns out to be tsc's own answer).
  **THE FIRST DRAFT PORTED `isTypeUsableAsPropertyName` LITERALLY — the key expression's
  TYPE — AND IT MEASURED AS A NAME THAT IS NOT A FUNCTION OF THE PROGRAM**: a FILE-LEVEL
  un-annotated `const K = "p"` answers the literal in the assignability pass and the widened
  `string` in the pass behind TS2339, so `const obj = { [K]: 1 }; obj.p` emitted the correct
  TS2322 **and** `Property 'p' does not exist on type '{}'` in ONE compile — round 933's
  two-extraction-sites signature reached through ambient state (round 911) instead of through
  a second `when`. The landed resolution is SYNTACTIC (an enum member's VALUE via
  `enumMemberEntries`; otherwise the declaration a name resolves to, by an innermost-first
  walk of the enclosing statement lists — `lookupPerFileForNode` cannot see a body local at
  all, B83.5, and a scope-chain consult would be ambient again), and the pin that fails if
  the type route returns asserts the two passes AGREE, because each pass alone is green.
  `lateBoundComputedKeyName` is asked BEFORE `computedSymbolKey` at all three naming sites,
  which is also what retires round 934's arm-A4 false positive at its source rather than by
  exclusion. 25 pins, 8-arm ablation (every arm with a uniquely-its-own failure). What is left is (CHK.4) above.

- [x] **(CHK.2) A COMPUTED OBJECT-LITERAL KEY NEVER REACHED THE EXCESS-PROPERTY CHECK —
  LANDED, round 934. A false NEGATIVE in every position, from ONE name-extraction `when`,
  and the diagnostic was being computed in full before it was dropped.** Round 933 measured
  the row and left it: ``{ p: 1, [`zz`]: 2 }`` and `{ p: 1, ["zz"]: 2 }` against
  `interface Opt { p?: number }` are TS2353 in tsc 7.0.2 and were silent here. Extended
  before designing, it is larger: a BARE numeric key `{ 7: 2 }` escapes too (so the omission
  is not about computed keys at all), and every position escapes together — `satisfies`, an
  ARGUMENT, a `return`, a NESTED literal under a computed key, a computed METHOD name.
  **The cause is the exact mirror of (CHK.1)'s**: `getTypeOfObjectLiteral` had named all of
  those keys for years, so the source TYPE carried the member and `checkExcessProperties`
  judged it excess correctly — and then looked for the AST node that declared it with a
  `when` knowing only `Identifier` and `StringLiteralNode`, found nothing, and emitted
  nothing. The lookup is now ONE shared predicate (`objLitElementMemberName`), so the type
  builder and the excess check cannot disagree about what an element names.
  **THE ROUND'S REAL PRODUCT IS THE TWO NEAR MISSES, EACH OF WHICH TURNED THE FN INTO AN
  FP ON A ROW ROUND 933's TABLE DOES NOT CONTAIN.** (i) Admitting a numeric key exposed a
  TARGET-side gap that could not matter before — `collectTargetPropertyNames` bails on a
  STRING index signature and knows nothing of a NUMERIC one — so `{ [7]: 2 }` against
  `{ [k: number]: T }` was reported where tsc is silent. (ii) Naming the key with
  `computedLiteralKey ?: computedSymbolKey` (the obvious delegation) reported `'[E.P]'` for
  `const enum E { P = "p" }` + `{ [E.P]: 1 }`, which tsc late-binds to the existing `p` and
  accepts — **`computedSymbolKey` INVENTS `"[<dotted>]"` so a well-known-symbol member can
  match structurally (round 723); it is not a claim about what the key spells and cannot
  tell `Symbol.iterator` from `E.P`.** Both are guards with a discriminating negative
  control apiece. **So the line is round 933's line in the other direction: the excess check
  acts on a computed key exactly when the key is a LITERAL spelling one fixed name**; every
  key needing the key's TYPE stays out in BOTH directions and is (CHK.3). **The message FORM
  is matched rather than recorded** — tsc keeps the delimiters (`'["zz"]'`, `''zz''`) and
  squiggles the whole written key, the span is in hand, and no ACTIVE corpus test has a
  delimited excess key (ten of the eleven such baselines are not generated; the eleventh
  belongs to another emitter). 20 pins + one round-933 pin rewritten to tsc's own answer
  (it asserted a TS2741 that tsc does not emit); six-arm ablation, all reached, four with a
  uniquely-their-own failure, four pins recorded as undiscriminated rather than claimed.
  **Every profile instrument is a CONTROL and it was measured**: across all eight profiles'
  1,249 `.ts` files an object-literal computed key matches 8 times — all eight the same
  destructuring pattern — so `+0.00%` and `added=0 removed=0` are the expected answers.

- [x] **(CHK.1) A BACKTICK-QUOTED COMPUTED MEMBER KEY NAMES A MEMBER — LANDED, round 933.
  Three FALSE POSITIVES tsc does not have, from ONE missing `when` arm, in a spelling the
  whole tsc corpus never uses.** Round 932 recorded, in passing, that `` { [`p`]: v } ``
  did not supply a required `p`. Measured against `tsc 7.0.2` this round it is three, not
  one: the object-literal supply (TS2741), an INTERFACE's own `` [`ip`] `` member (TS2339)
  and a CLASS's own `` [`cp`] `` member — the last of which resolved for the assignability
  check and simultaneously FP'd TS2339 **in one compile**, because the type-building site
  and the class-AST walker are two independent name extractions and only one of them had
  been widened. **The fix is `computedLiteralKey` growing a `NoSubstitutionTemplateLiteralNode`
  arm, plus `classMemberNameText` DELEGATING to it instead of re-spelling its `when`** — the
  archive's B451 entry says outright that this family has >= 5 independent extraction sites
  and that widening one silently leaves the others FP'ing, and the class row is what that
  looks like from the outside. **What stays refused, measured and pinned in the positive:**
  a SUBSTITUTING template (`` [`p${x}`] ``) names no fixed member and is TS2741 in tsc too.
  **What stays OPEN and is NOT pinned** (round 765's law — a known-open gap is a countdown,
  not a guard), both with tsc's answer measured: `{ [K]: v }` / `{ [E.P]: v }` supply nothing
  here and do in tsc — that needs the key's TYPE, i.e. late binding, not a spelling; and the
  EXCESS-PROPERTY direction never sees a computed key at all, so `` { [`zz`] } `` AND
  `{ ["zz"] }` both escape TS2353 where tsc emits it (a false NEGATIVE, symmetric across the
  spellings, untouched by this round). tsc additionally renders such a key's name WITH its
  delimiters in the TS2353 text (`'"zz"'`, `` '[`zz`]' ``) where we print the bare name — a
  form divergence, noted not acted on. 11 pins (`TemplateComputedMemberKeyTest`, every
  backtick row beside its quote-spelled B451 control); three-arm ablation, all reached.
  **Every profile-based instrument is STRUCTURALLY BLIND here and that is measured, not
  assumed**: the eight tsc profiles contain ZERO backtick-quoted computed member keys (the
  only `` [`…`] `` matches are array literals), which is why `cost_gate.py` reads +0.00%
  on all 20 counters and the 8-profile grid reads `added=0 removed=0` — both are CONTROLS
  here, and the corpus plus the new pins are the gate.

- [x] **(API.17) A COMPUTED OBJECT-LITERAL KEY `{ ["p"]: v }` — LANDED, round 932; § 14's gap 2,
  and the LAST silent shape anywhere in this API.** Round 930 measured a computed key as
  "usually reported" — `WOULD_NOT_COMPILE` where the contextual member is REQUIRED,
  `OCCURRENCES_INCOMPLETE` where the literal has no contextual type — and SILENT in exactly
  one shape: an OPTIONAL member, where stranding the key costs no diagnostic, so the applied
  rename compiled clean with the old name still spelled in the literal and no gate in this
  repository could see it. tsc 7.0.2 counts the key as a reference, hovers it as the member,
  navigates to the member's declaration and renames it (measured, six spans on a fixture
  carrying one). **The landing is a POPULATION change and one predicate**: `occurrenceNodes`
  now sweeps every literal for which `isMemberPosition && isMemberNameLiteral` holds, which
  subsumes (API.9)'s element accesses, (API.16)'s templates, `{ "p": v }`, `{ ["p"]: v }`,
  ``{ [`p`]: v }`` and a class's or an interface's `["p"]` — so the set a caret may land in,
  the set a sweep reports and the set a rename must edit are ONE set by construction rather
  than three definitions kept in step. **A literal the API cannot RESOLVE still belongs in it**:
  seen-and-unplaced is a stated `OCCURRENCES_INCOMPLETE` conflict, unseen is a silent miss.
  **`{ [K]: v }` is deliberately out** — it spells no fixed name and tsc reads it as a
  reference to the binding `K` alone (measured); the asymmetry with the element-access arm is
  stated in `SyntaxRoles.isMemberPosition`, because calling it a member position flips the
  completeness net's polarity for every ordinary `const` rename. **THE ROUND'S SECOND HALF WAS
  AN AUDIT FINDING**: `typeCaptureReportedType` recorded an object-literal key's TYPE as
  deliberately not closed *because the contextual type is walk-scoped state a capture cannot
  read* — and (API.10) built `typeCaptureContextualType`, a purely syntactic walk, one round
  later. Nobody came back. Measured before this round, EVERY key — computed or bare —
  answered `any`, or the COLLIDER's type where a same-spelled binding existed. Closed by
  `typeCaptureObjectLiteralKeyType`, the contextual member's type with the key's own value as
  the fallback, which is what tsc reports in both shapes. +18 pins, four inverted; ten-arm
  ablation. `docs/language-service.md` §§ 8, 9, 10b, 10d, 14.

- [x] **(API.16) A MEMBER NAMED BY A TEMPLATE ELEMENT ACCESS — LANDED, round 931; § 14's
  gap 6, the ONE genuinely silent gap in this API, is closed.** ``o[`p`]`` was outside
  (API.9)'s occurrence population, so `referencesAt` / `documentHighlightsAt` / `renameAt`
  missed it AND SAID NOTHING: round 930 proved it end to end — the rename applies, the
  template keeps spelling the old name, and the applied program has ZERO diagnostics, so
  no gate this API has can see it. tsc 7.0.2 counts it as a reference, renames it, hovers
  it as `(property) I.p: number` and completes inside it (all measured). It is now an
  ordinary occurrence in every one of those queries, with the edit covering the TEXT and
  **not the backticks** — round 926's rule one delimiter over, and the same measured span
  tsc writes. **Round 929's completion refusal is CASHED rather than overruled**: it
  refused for exactly one reason, that the sweep could not find such a member, and the
  sweep now can — the two still share ONE enumeration, so they cannot drift apart about
  what a member name is. **REFUSED, and it is a NODE-CLASS boundary rather than a
  judgement**: a template carrying a SUBSTITUTION (``o[`p${x}`]``) spells no fixed name,
  so it is neither an occurrence nor an obstacle and its caret renames nothing — which is
  tsc's answer there too (zero references, `prepareRename` refuses). **The one place a
  second mechanism was needed is HOVER**: this compiler's element-access typing keys a
  named member off a STRING literal, so routing the template through the access would
  have answered `any` — the (API.15) violation one round later — and the member is
  resolved through the receiver instead. +8 pins, two inverted; seven-arm ablation, five
  distinct red sets plus one MEASURED-REDUNDANT guard with its reach proved by a
  narrowing twin. `docs/language-service.md` §§ 8, 9, 10a, 10b, 10d, 14.

- [x] **(API.15) AN ENUM MEMBER'S DECLARATION NAME REPORTS `any` — LANDED, round 931; the one live violation
  of *prove to offer* in this API.** Measured round 930 on four shapes (plain, valued,
  `const enum`, string enum): `quickInfoAt` on the `Alpha` of `enum Plain { Alpha }`
  answers `QuickInfo(displayString = "any")`, where tsc 7.0.2 answers
  `(enum member) Plain.Alpha = 0` and where our own USE site already answers
  `Plain.Alpha`. Not an absent answer — a plausible wrong one, which is the failure mode
  (BUG.4) and (API.11) each closed one position over. **The mechanism is known and the fix
  is one leg**: `Checker.typeCaptureMemberDeclarationType` resolves a declaration name
  through its OWNER and then asks `typeCaptureCollectMembers` for the member — and an
  enum's own type is a member-LESS `Type.Object` (CLAUDE.md), so the collection finds
  nothing, the leg returns null and the fallback types the identifier as a free name.
  What it needs instead is `getDeclaredTypeOfEnumMember`, which is what the use site
  already reaches. Pinned as a DEFECT by `LanguageServiceStateTest`'s `an enum member's
  declaration name reports the WRONG type and its use reports the right one`, so closing
  it must edit that test, § 8 and § 14's gap 7 together. Definitions and references for
  the same position are already complete; only the TYPE is wrong.
  **LANDED**: `typeCaptureEnumMemberType`, eight lines, minting through
  `getDeclaredTypeOfEnumMember` — and the measured product is that the obvious
  alternative does NOT work (`getTypeOfSymbol` on an enum member symbol answers `any`,
  arm A2). Five shapes report the member's type, the same instance the use site
  reports; tsc's extra decoration is the member's VALUE, which this API deliberately
  does not render (§ 8). The defect pin is inverted in place.

- [x] **(API.12) COMPLETION INSIDE `o["` — LANDED, round 929; the last query that did not
  answer an element access.** A caret in the string of `o["…"]` is a MEMBER caret whose
  receiver is the expression before the `[`, decided by ONE classifier
  (`SourceIndex.stringMemberAnchorAt`) over (API.9)'s OWN enumeration, so "a string literal
  is a member name only in an element-access position" is one predicate shared by the
  occurrence sweep and the anchor. **Zero core changes**: the member enumeration is round
  917's, so the union rule, the accessibility filter and the `this`/export-table legs came
  for free. **The span is the literal's TEXT, quotes excluded** — tsc's own measured edit
  range and the same span a member rename writes into — and a member whose spelling is not
  an identifier (`"has space"`, `"1abc"`) is offered, which is the reason element access
  exists. **THE ROUND'S PRODUCT is that `StringLiteralNode.isUnterminated` is FALSE for a
  lone `"`** (the parser compares the raw text's last character to its first), so `bag["` at
  end of file — the state a completion request is normally made in — parsed as a terminated
  empty string and used to answer FREE_NAME with the whole lexical scope offered INSIDE the
  string; the anchor checks the arithmetic as well as the flag. **Deliberately refused**, each
  measured against tsc: a TEMPLATE `` o[`p`] `` (which tsc completes — refused because
  (API.9)'s population is string literals only, so a member written that way is one a rename
  cannot find), a caret AT the opening quote, an indexed-access TYPE, and a string completed
  from its CONTEXTUAL type. **That last measurement found a SILENT GAP one layer down: tsc
  counts `` o[`p`] `` as a reference**, so this API's references and rename miss it and do not
  say so — now § 14's gap 6. +26 pins, nine-arm ablation (five distinct non-empty sets, three
  MEASURED-REDUNDANT guards and a two-mistake REACH CONTROL), all gates green.
  `docs/language-service.md` §§ 10a, 14.

- [x] **(API.11) A MEMBER DECLARATION NAME RESOLVES TO ITS OWN SYMBOL — LANDED, round 928;
  the single largest thing refusing a member rename is gone.** A member's own declaration
  name — an interface's, a class field's, a method's, an accessor's, a static's, a
  `#private`'s, a type-literal member's, an enum member's — is bound by no scope and has no
  receiver, so it resolved to nothing: `definitionsAt` answered empty, `quickInfoAt` answered
  `any` (or the COLLIDER's type, (BUG.4) one position over), `referencesAt` answered empty for
  a member never used, and `renameAt` refused whenever another interface declared the same
  member NAME. It now resolves through its **OWNER**, the receiver's exact dual — the fourth
  resolution mechanism (`Checker.typeCaptureMemberDeclarations`). **THE HAZARD THE ITEM NAMED
  IS BIGGER THAN "resolve it to itself"**: round 884's `mergeSingleSymbol` ADOPTS, so a member
  declared in two merged `interface` blocks is one symbol carrying only the SECOND block's
  declaration — measured — and the whole list has to be reconstructed from the OWNER symbol's
  own declarations, each a container. A merged declaration, an OVERLOAD set and an ACCESSOR
  PAIR are therefore one group from any of their declaration names, in every query. Deliberate
  exclusion, in the conservative direction: an object literal's own METHOD, which is outside
  (API.10)'s key leg and stays a loud refusal. +16 pins, two changed meaning in place, nine-arm
  ablation (seven distinct sets; two arms measured REDUNDANT with their reach proved by other
  arms), `cost_gate.py` +0.00%. `docs/language-service.md` §§ 8, 9, 10b, 10d, 13, and the new
  **§ 14, State of the API**.

- [x] **(API.10) ONE SPAN, TWO SYMBOLS — LANDED, round 927; the LAST of round 922's five
  refusals.** A contextually typed object-literal KEY (`{ p: v }`) and both SHORTHANDS
  (`{ p }`, `const { p } = o`) are occurrences of the member the literal's CONTEXTUAL
  type supplies. **The capture still files ONE answer per span** — round 926 read that
  as the structural obstacle and it is not: tsc's relation between a shorthand's two
  symbols is ASYMMETRIC (the member's group CONTAINS the token; a caret ON the token
  answers the LOCAL's group alone), so what was missing was a ROLE.
  `CapturedDefinition` now carries three declaration sets differing in which of
  NAVIGATION / SEED / MEMBERSHIP they hold: `locations` all three, `related` seed +
  membership (the heritage edge, and now an object-literal key's OWN property),
  `shorthand` navigation + membership and deliberately NOT seed. The contextual type is
  computed by a SYNTACTIC walk OUT of the literal (`Checker.typeCaptureContextualType`,
  the dual of round 926's `typeCaptureDestructured`) covering eleven positions read out
  of tsc 7.0.2, because the checker's own contextual type is walk-scoped and `cpaCtxAt`
  stops at every statement edge. `renameAt` expands a shorthand in whichever direction
  it was reached from — `{ renamed: p }` vs `{ p: renamed }`, the round's discriminator,
  since both compile and both are one edit. **Still refused**: a second declaration of
  the same member name (pre-existing, and the named successor), a shorthand whose member
  cannot be placed, and a computed key. +19 pins, ten-arm ablation (nine distinct sets;
  A3/A8 share one because the round-925 verification refuses exactly what a wrong
  expansion would write), `cost_gate.py` +0.00%. `docs/language-service.md` §§ 8, 9,
  10b, 10d, 13.

- [x] **(API.9) THE MEMBER OCCURRENCE SET — LANDED, round 926; TWO OF THE THREE KINDS CLOSED
  OUTRIGHT, THE THIRD CLOSED FOR A DECLARED HERITAGE EDGE AND STILL REFUSED FOR A CONTEXTUAL
  ONE.** Round 925 measured a member's occurrence set at 2 spans against tsc's 5 and named the
  three missing kinds. Closed: **(1) a binding element's `propertyName`** (`const { p: local }`
  — a receiver question; the pattern's source is the annotation or initializer one to three
  levels up, `Checker.typeCaptureDestructured`), **(2) an element access `o["p"]`** (a
  POPULATION question; `SourceIndex.occurrenceNodes()` is `identifiers()` plus the string
  literals that name a member, and the edit span is the text BETWEEN the quotes), and **(3) an
  IMPLEMENTOR's member** via `CapturedDefinition.related` — a DECLARED heritage edge, computed
  per OCCURRENCE, which is what makes a `this.p` inside an implementor part of the interface's
  group. **Still refused: a contextually supplied key, and the binding SHORTHAND `const { p }`,
  for the same structural reason** — one span carrying two symbols, which a capture filing one
  answer per span cannot express. `referencesAt`, `documentHighlightsAt` and `renameAt` improve
  together because the set is wired once; `definitionsAt` deliberately does NOT follow the
  heritage edge, because tsc's own go-to-definition on an implementor's member answers that
  member. +20 pins, ten-arm ablation, `cost_gate.py` +0.00%, population 381,670 -> 381,672 on
  tsc's own sources. `docs/language-service.md` §§ 9, 10b, 10d.

- [x] **(API.8) RENAME — LANDED, round 925.** `RenamePlan(oldName, newName, files, refusal,
  conflicts)` / `FileRename(fileName, edits)` / `RenameEdit(start, end, newText)` /
  `RenameConflict(kind, fileName, start, end, detail)` + `RenameRefusal` (11) and
  `RenameConflictKind` (5); **`Project.renameAt(fileName, offset, newName)`**. **ZERO core
  changes** — the whole feature sits above the compiler on (API.5)'s sweep and (API.7)'s parent
  ascent. **STEP 1 WAS tsc ITSELF, and it decided three designs**: `scripts/lsp_rename.py` drives
  `tools/tsgo-7.0.2/lib/tsc --lsp -stdio`'s `textDocument/prepareRename` + `rename` over a
  22-caret fixture and prints the resulting TEXT, so `{ p }` -> `{ p: newName }`, `const { z }`
  -> `{ z: newName }` (local) vs `{ newName: z }` (property), and the lib refusal's exact wording
  were READ rather than reasoned. It also showed **two places to do BETTER than tsc**: tsc
  validates neither the new name (`const class = 1`, `const 1bad = 1`) nor collisions (it writes
  a second `const useZ` beside the first). **THE OCCURRENCE SET WAS MEASURED BEFORE ANY CODE and
  it is NOT complete for members** — on the same fixture tsc's member rename edits 5 spans and
  ours resolves 2, missing a binding element's `propertyName`, an `o["p"]` (a string literal, so
  outside the identifier population by construction) and an IMPLEMENTOR's member (a different
  symbol here). So members are not planned around, they are **refused with the evidence**:
  a spelling scan is used as a SAFETY NET — never as the answer — and an identifier spelling the
  old name that is neither in the group nor resolved elsewhere is a conflict. **The position
  split inside that net is load-bearing**: a member declaration name resolves to nothing, so
  without it an `interface I { p }` anywhere would refuse renaming an unrelated local `p`.
  **THEN THE PLAN IS VERIFIED BY APPLYING IT AND COMPILING AGAIN** (a scratch `OverlayVfs` around
  the project's own, so nothing is observable): it must re-read, it must add no diagnostic
  (**the COLLISION check**), and every renamed occurrence plus every identifier that ALREADY
  spelled the new name must resolve to exactly what it resolved to before (**the CAPTURE check** —
  renaming a file-level `a` to `b` where a body holds its own `b` compiles, produces no
  diagnostic anywhere, and means something else; arm A4 is the only thing that sees it).
  **ONE MEASURED DESIGN CORRECTION**: the expectation for a renamed occurrence is its OWN prior
  answer, not the seed — demanding the seed reports this API's own blind spot (a member's
  declaration name resolves to nothing) as a change of meaning, and refused three correct member
  renames before it was fixed (arm A10). **DIVERGENCE FROM tsc, stated**: a bare `export { p }` /
  `import { p }` is replaced PLAINLY where tsc expands to `newName as p` — our identity crosses
  the alias hop, so the local and the export are one symbol and the whole group renames together;
  expanding would make `export { p }` behave differently from `export const p`. **REFUSED, each
  with a reason**: a declaration in a library, an ALIASED import (`import { a as b }` — one new
  name cannot spell two things, and tsc picks by caret because it has two symbols), an unresolved
  import, a caret on either half of an `as`, a reserved or malformed new name (**no build**), and
  a member whose set cannot be shown complete. **PINS +35** (`-project` 390 -> 425; core UNCHANGED
  at 14,341) — 14 parse-only shape pins written FIRST. THE DISCRIMINATOR is the shorthand, asserted
  as the exact resulting TEXT of both lines, because a plain rewrite passes every count-based
  assertion and renames the object's key. **APPLY-AND-RECHECK** pins apply the plan through
  `updateFile` and assert the diagnostics are byte-identical — an independent oracle of the
  verification `renameAt` runs internally. **TWELVE-ARM ABLATION**, one mistake at a time, anchored
  replacements with an asserted occurrence count, restored from a sha256-verified snapshot.
  **GATES**: suite 14,865 -> **14,900 / 0 failures / 0 errors / 3 skipped = exactly the +35**;
  `cost_gate.py` **+0.00% on all 20 counters** (a control: no core change);
  `huge_methods.py --fail-over 0` clean on core and on `-project` explicitly. **MEASURED ON tsc's
  OWN SOURCES**: renaming `SyntaxKind` in `types.ts` produces **9,827 edits across 49 files** in
  23.9-24.5 s warm (against `referencesAt`'s 10.6-16.0 s); `createTypeChecker` is 3 edits in
  13.3-14.3 s. `docs/language-service.md` § 10d; harness `RenameCostMain`.

- [x] **(BUG.4) Quick info on a MEMBER NAME reports the wrong type, for every receiver — FIXED,
  round 924.** The item said it reports `any`; **measured against tsc 7.0.2's own LSP it reports
  the type of whatever unrelated binding shares the member's spelling**, and `any` only where
  nothing does — 16 of 23 wrong member positions read a collider, 6 read `any`, one was right by
  coincidence. **The fix is tsc's own rule**: `getTypeOfSymbolAtLocation` moves off the right-hand
  side of a property access ONTO THE ACCESS, so the type of the `p` in `o.p` is the type of `o.p`
  — and a probe of exactly that, measured before any design was committed, was already correct for
  the generic instantiation, the inherited member, the union receiver, the type-parameter receiver,
  the static side, the enum and namespace members and the flow-NARROWED member, because
  `computeRawTypeOfPropertyAccess` implements all of them. So the landed fix contains **no member
  walk**: the brief's carrier route was the right instinct at the wrong altitude, and a member-table
  read is exactly what arm A2 shows failing (the two generic pins plus narrowing). The ONE receiver
  needing (API.3d)'s carrier is `this`/`super`, which are plain identifiers in this parser and type
  as `any`; the leg is ADDITIVE, so where it cannot decide the access answers `any` rather than a
  wrong name. **NEIGHBOURS CASHED**: an element access `o["p"]` (the caret is on the literal, whose
  own `string` made the old answer right only by coincidence) and a qualified TYPE name `N.T`
  (through the export table). **STILL REFUSED**: an object literal's own key, on round 922's
  unchanged contextual-type ground. **THREE tsc DIVERGENCES named rather than asserted away**:
  `this` in a static member (`typeof C` is unmodelled), an object-literal member's literal widening,
  and a type rendered under a synonymous alias.

- [x] **(BUG.3) A caret on `this.` inside a NESTED ARROW answers NO members — FIXED, round 923.**
  **THE LAYER QUESTION WAS THE ITEM, AND THE ANSWER IS CAPTURE-ONLY.** Settled by MEASUREMENT before
  any code: a 24-line fixture covering `this` in a method, an arrow, an arrow inside an arrow, a
  `function` expression and declaration, an object-literal method, a getter, a setter, a constructor,
  a property initializer, a static member and a class expression, compiled through the ORDINARY
  diagnostic path, gives **17 diagnostics byte-identical to tsc 7.0.2** — so the CHECKER binds `this`
  in a nested arrow exactly right and the compiler-correctness worry this item raised is answered NO.
  The defect was `typeCaptureVisit` installing `currentClassForThis = frame.classForThis`: a cta
  frame is a TYPE-checking context and does not thread `this`, so the frame an arrow BODY pushes
  carries null. Fixed by **`typeCaptureThisClass`**, a pull-based ascent transparent to arrows and
  opaque to every other `this`-binder — deliberately NOT round 922's `typeCaptureEnclosingClass` (the
  accessibility question, which would answer inside a `function`) and deliberately NOT the checker's
  own `spineCaClassCtx` (right shape, bug-compatibly transparent to a nested `FunctionDeclaration`,
  the one arm where reusing it verbatim fails). Bias PROVE TO OFFER. **Side findings, stated not
  fixed**: an EXPRESSION-bodied arrow already worked (a cta frame is pushed at a `Block` enter, so
  such an arrow pushes none), and **quick info on a member NAME is a separate RECEIVER-INDEPENDENT
  gap** — `o.p`, `this.p` in a method and `this.p` in an arrow all report `any` — so the brief's
  "they share the path" is false; promoted to the successor ranking instead. **+20 pins**,
  **seven-arm ablation** (five distinct sets, one measured-redundant guard, one redundancy
  demonstration), suite 14,818 -> 14,838, `cost_gate.py` +0.00%, **8-profile grid `added=0 removed=0`
  against a rebuilt HEAD binary**. `docs/language-service.md` § 9.

- [x] **(API.6) SIGNATURE HELP — LANDED, round 921.** `SignatureHelp(signatures, activeSignature,
  activeArgument)` / `SignatureInfo(label, parameters, returnTypeText, activeParameter)` /
  `ParameterInfo(name, typeText, optional, isRest, labelStart, labelEnd)`; **`Project.signatureHelpAt(
  fileName, offset)`**, null when the caret is in no argument list and an EMPTY signature list when it
  is in one whose callee has none. A FOURTH capture list — `TypeCaptureRequest.signatureSpans:
  List<SignatureCaptureSpan>`, the only one carrying a payload beyond the span, because the ACTIVE
  ARGUMENT is a property of the COMMAS and `f(a, |)` parses to a call with one argument.
  **THE PREMISE — "three-quarters built" — HELD FOR THE CALLEE AND WAS WRONG ABOUT THE ANCHOR.**
  `getCalleeType` + `getCallSignaturesOfType` answered a method through a receiver, an import, a
  callee that is itself a call and a decorator factory with no rule of their own, exactly as ranked;
  what the completion anchor did NOT already answer is which call and which argument, because
  **signature help is the first query in this arc whose subject is a REGION the parse carries no node
  for**. Three shapes defeat containment: `f(a, b|)` is at the real END of `b` (half-open, so outside
  it) and yet is argument 1; `f(a, |)`'s second argument does not exist in the tree; and for `f(` at
  EOF or `f(a,` before a `}` the call node's own real end lies BEFORE the caret, so no descent reaches
  it. **THE PARSER RECOVERY WAS READ OUT OF `Parser.kt` BEFORE ANY CODE, as round 917 did**:
  `parseArgumentListWorker` breaks on end-of-file and on a `}` and then runs `parseExpected(CloseParen)`,
  so the `CallExpression` EXISTS in every one of those shapes — which is what makes a token-level
  anchor possible at all. So the region is **bracket-matched over the token stream** (stopping early at
  a closer that does not match the top of the stack — an unmatched `}` means the enclosing block is
  closing) and the index is **a count of this list's own commas**, where "its own" is decided by
  testing the ARGUMENTS' spans: a comma inside a nested call, an object literal or a
  `Map<string, number>` type argument is excluded by ONE test, with no per-construct rule and no need
  to lex `<`/`>` (arm A8, 4 red). **THE ACTIVE-SIGNATURE RULE, stated so it can be argued with**: the
  FIRST signature that could still become this call — room for the caret's argument (its index is
  within the parameter list, or the signature ends in a rest, or it takes none and none were passed)
  AND `signatureAcceptsArgs` over the arguments already FINISHED, which is the same verdict
  `resolveCallOverload` selects with, so a host's highlighted overload and the compiler's chosen one
  cannot drift. The argument the caret is IN is deliberately not judged — half-typed by construction,
  so judging it would flip the highlight under the user's hands. Nothing qualifying answers 0,
  reported not hidden. Arms A6 (always 0) and A7 (arity only) redden different sets, so both halves of
  the rule are load-bearing. **ONE COMPILER-SIDE SURPRISE, FIXED**: a parameter declared with a
  BINDING PATTERN is dropped from `Signature.parameters` by `getParameterSymbols` and the survivors
  keep a POSITIONAL zip of the declaration's annotations, so rendering from the symbols alone prints
  `destructured(tail: { a: number; b: number })` — one parameter short AND wearing its neighbour's
  type, i.e. a plausible-looking lie. The DECLARATION is rendered instead whenever its parameter list
  is longer (arm A10, 1 red uniquely its own). **RENDERING reuses `typeToString`** — hover's renderer —
  and deliberately NOT `signatureToString`, whose `p?: string | undefined` is a TS2345 message
  convention; parameter ranges are recorded AS THE LABEL IS BUILT (arm A11), because searching for
  `name: type` finds the wrong occurrence as soon as one parameter's type mentions another's spelling.
  A GENERIC callee renders UNINSTANTIATED (`pickFrom<T>(xs: T[], index: number): T`) — inferring `T`
  means inferring from arguments that are not finished. **REFUSED with reasons**: tagged templates (no
  parenthesized list), type arguments, `super(...)` (an ordinary identifier here, bound to nothing —
  empty list, pinned), and a spread's arity. **NOT refused, and pinned**: decorator factories and a
  call-callee. **PINS +56** (`-project` 242 -> 298; core UNCHANGED at 14,341) — 30 parse-only anchor
  pins written FIRST, 26 end-to-end. THE DISCRIMINATOR is an OVERLOADED callee asserted as an EXACT
  list of three labels: every shortcut (render the callee's type, take the overload resolution picks,
  match by name) answers ONE and passes every other pin. **ELEVEN-ARM ABLATION, one mistake at a time,
  each dry-run for a real diff and restored from a sha256-verified snapshot; all eleven compiled and
  ALL ELEVEN reddened a DISTINCT set** — A1 outermost call 1, A2 first overload only 1 (the
  discriminator), A3 no rest clamp 1, A4 no receiver path 2, A5 no export-table leg 1, A6
  activeSignature always 0 -> 2, A7 arity-only 1 (a strict subset of A6, distinguished by the pin it
  leaves GREEN), A8 all commas 4, A9 region = the call's real end 6, A10 no declaration render 1, A11
  label ranges not followed 1. `scripts/round921-ablate.sh`. **GATES: suite 14,717 -> 14,773 / 0
  failures / 0 errors / 3 skipped = EXACTLY the +56**; `cost_gate.py` **+0.00% on all 20 counters** — a
  real gate, since `Checker.kt` grew ~370 lines reachable from the hook on the hot walk;
  `huge_methods.py --fail-over 0` clean on core (750 classes, 15,976 methods) and on `-project`
  explicitly (28 classes, 280 methods); `spine_closure_audit.py` 46 handlers all supersets;
  `scripts/round920-token-gate.sh` 1,327 files / 101,287,620 chars / ZERO violations. No wall A/B:
  production executes not one new instruction — every addition sits behind a hook that returns on a
  null per-file key set. `docs/language-service.md` § 10c.

DENOMINATORS, so every % below converts. Last MEASURED warm rebuild **5,242.6 ms** (round 899, per-arm
sd 2.51%); JFR profile denominator **5,429 ms**; **1% = 54.3 ms**. Cross-round: 5,859 (pre-887) ->
5,424 (pre-895) -> 5,243 (HEAD) = **-10.5% over rounds 887-898**. **There has been no wall A/B for
twelve rounds**, and round 899 could resolve 1.88% in SIGN alone — so every item below is a fifth to
a half of what this box can judge and must be defended on counters plus a decomposition, never on a
median. `cost_gate.py` reads +0.00% by construction for all of them.

REFUSAL FLOOR: ~**0.31%** (~17 ms) for a LOW-risk change — round 897 refused there, 898 refused
MEDIUM at 0.13-0.20%, 900 refused at 0.07-0.14% and BUILT at 0.39%, 903 refused at 0.085%.

- [x] **(WARM.31) Residual boxed primitive map/set keys — REFUSED, round 904.** 14 sites,
  **2,698,745 ops/rebuild**, premium **6.58 ns**, so **17.7 ms = 0.334% for ALL of them together** and
  **0.064% for the largest single one**. `docs/perf/boxed-primitive-key-price.md`. **Do not re-open
  from a leaf profile**: the 29.4 ms that ranked it is one draw of a number that reads 72.9 and 19.0 ms
  across round 899's own two dumps of the same binary. A next agent can refuse a NEW boxed-key site
  for free — **population x 6.58 ns**, and a site needs ~1.7 M ops to clear the floor while the whole
  spine visits 856,962 nodes.

- [x] **(WARM.32) The iterator-allocation family — REFUSED, round 905.** 215 sites are **495,305
  calls over 925,502 elements** (mean list length **1.99** / **1.72**; 52.4% of `forEachChild`'s list
  positions are SINGLETON, and `anyIdentical` hits 94.4% so a hit stops the scan). Premiums **11.95 ns**
  and **2.75 ns** per call = **3.90 ms = 0.074%**, refused by 4.4x, and that is an UPPER bound (both
  arms fold into a trivial sink). `docs/perf/iterator-allocation-price.md`. **The census refuses it
  without the amplifier**: 17 ms over 495,305 calls needs 34.3 ns/call, where a WHOLE boxed
  `HashMap<Long, .>` probe is 8.53 ns (round 904). **The sibling project's -3.1% is not contradicted —
  the mechanism transfers and the PRICE does not**, because its population is per-token `withIndex()`
  chains and ours is 2-element lists. LANDED ANYWAY: the 215 sites now route through `walkList` /
  `anyIdentical` in `NodeWalk.kt` (one home, so it cannot be re-opened blind), which shrank
  `forEachChild`'s three (JIT.1) partitions **9,256 -> 5,929 bytecodes (-36%)**.

- [x] **(WARM.33) reach-machinery (b), transpose the 43 per-file memos — REFUSED, round 906, AND THE
  CANDIDATE IS A REGRESSION AT EVERY GEOMETRY.** `docs/perf/reach-memo-transposition-price.md`.
  **The whole memo-LAYOUT direction is closed**: the ceiling for ANY layout is **2.65-15.99 ms**,
  below the floor at every cache geometry, and shrinking the cache makes the candidate worse rather
  than better. **Round 875 had the SIGN wrong** — it read the ascent's scatter onto the probe's
  sequential sweep; measured, **42.2% of ascent steps go to `nodeId - 1`, 89.8% stay within 64 ids**,
  the spine walks in PREORDER so each 1-byte array is swept sequentially, and **layout A already
  answers 97.0% of accesses out of L1** (a line serves ~14.2 consultations against a transposed row's
  ~3.8). **Round 875's queued instrument could never have decided it**: an amplifier repeats one probe,
  so from the second repetition the line is L1-hot — *a locality change cannot be amplified*, and the
  round that priced it contains no clock at all, only a census plus a set-associative LRU model.
  Also corrected: this entry's own "deletes 36.9 MB/rebuild" deletes **55 KB of array headers** —
  43 arrays of n bytes and one of 43n are the same bytes. Adjacent direction closed with it: lazily
  allocating the 17 classifiers consulted <1,000x/rebuild is worth ~2-3 ms.

- [x] **(WARM.34) `lexLevelHasName`, the COUNT question — REFUSED by its own census, round 907, AND
  THE WHOLE FAMILY IS NOW CLOSED.** `docs/perf/lex-ascent-count-price.md`. **The queue's premise was
  wrong**: "an O(depth) ascent revisiting the big outer levels" describes the CHAIN (3.69 steps),
  not the PROBES (**1.544** per ascent), because 58% of level visits are refused by the untrusted /
  non-head-fn rules or are hash-free EMPTY maps — *a chain-step population is not a probe
  population*, round 902's law one step along its own family. **563,466 ascents / 870,231 real probes
  = 31.85 ms = 0.602% is the ceiling on EVERYTHING here.** The 80.7% redundancy is real and does not
  help: a repeat ascent performs **1.32** probes and a memo probe replaces them with **1**, so the
  queued ascent memo is **2.42 ms net, 9.92 ms even if free, and −10.7 ms at the measured probe
  cost — a regression**. A per-level memo is refused BY CONSTRUCTION (*a cache keyed by the same name
  at the same granularity as the map it fronts IS that map*), and a per-file absence filter is
  <= 7.30 ms. **Closure is now GENERAL, not per-lever: any one-operation oracle costing one probe
  recovers at most 0.21%.** Container closed by 901 (+0.26%) and 902 (−0.19%).

- [x] **(SPINE.1) The six spine handlers' frame bookkeeping — REFUSED AND CLOSED, round 908.**
  Denominator re-taken: **5,050 ms** (8 probe-free warm process medians), so 1% = 50.5 ms. The six
  are still 62.6% of the probed spine and **40.1% of the rebuild**, but round 733's deflation,
  MEASURED rather than applied (and with `SpineSections` run WARM for the first time), says the
  passes' own checking work is **91.4%** and every frame pop and restore is at or below one probe
  boundary — five of eleven sections read NEGATIVE once their boundary is subtracted. **Nothing
  clears the floor**: the three ancestor climbs are 19.6 ms (0.39%, refused again), the cta
  frame+ambient install 16.0 ms and load-bearing, the cta eligibility gate 14.4 ms with round 888's
  mask having already taken **87% of its population**. **The one row above 1% — 79.8 ms of
  frame-ambient install — has a ~8 ms deletable population** (the rebuild walks 2.91 frames, produces
  nothing on 91.4% of installs, and the save copies ZERO entries on 100% of 147,572) **and fails its
  own division by ~20x, because a timestamp is an OPTIMIZER BARRIER.** Round 847's per-handler ms are
  superseded — they were against 8,095 ms — and the order swapped again (`ccetSpineLeave` #1 -> #3,
  −51% in ms, while `cpaSpineLeave` fell 5% in ms and ROSE 7.62% -> 11.56% in share: round 830 live).
  **Caveat for any successor: the `dispatch` tier bypasses `spineEnterMask`, so that table prices the
  pre-888 regime and is blind to the lever the region already banked.**

- [x] **(WARM.35) The four round-903 hot-path candidates — ALL REFUSED, round 912, AND THE QUEUE'S OWN
  POPULATION FOR THE LARGEST OF THEM WAS A TRANSCRIBED SOURCE COMMENT.**
  `docs/perf/round912-candidate-census.md`. Priced by census plus round 896's divide-and-refuse —
  **no fix built, no amplifier needed**; both census processes agree to the last digit on all 22
  counters and `mappedNodeTypeKey calls = 110,780` reproduces `cost-counters.txt`'s
  `typeNode.bypassed` exactly, which is a second independent control. Against the stated 5,242.6 ms
  denominator (1% = 52.4 ms, the ~17 ms floor = 0.324%):
  **`mappedNodeTypeKey` key build — 25,987 keys of 110,780 calls = 9.36 ms = 0.179%, refused by
  1.8x**; **`narrowTypeFromFlow`'s default-arg `NarrowFlowMemo` — 31,768 = 4.77 ms = 0.091%, by
  3.6x**; **`collectTypeofGuardNames` &c `LinkedHashSet` — 22,798 = 1.48 ms = 0.028%, by 11.5x**;
  **`spineOsWithAmbient` / `spineTcDispatchWithAmbient` — 2,841 = 0.28 ms = 0.005%, KILLED BY READING,
  by 60x**. **ALL FOUR TOGETHER are 15.9 ms = 0.303%, still under the floor for ONE low-risk change.**
  To reach 17 ms they would need **654 / 535 / 746 / 5,983 ns per operation**, against a measured
  **15.09 ns** for a whole `HashMap` get that recursively hashes AND `equals` a 2.76-node AST subtree
  (round 903). **DO NOT RE-RAISE ANY OF THE FOUR.** Three mechanism findings outlive the prices:
  **(a)** the "~88 k/rebuild" this queue attached to `mappedNodeTypeKey` **was never a measurement** —
  it is a transcribed KDoc that is itself 26% stale (real call count **110,780**) applied to the wrong
  quantity (only **25,987**, 3.4x fewer, build a key; 76.5% exit at the foreign-file gate first), so
  the entry was wrong in both directions at once; **(b)** candidate 3's `inline` **is not expressible
  in Kotlin** — both wrappers hand `block` to a RECURSIVE non-inline callee, so `inline` forces
  `noinline`, which re-materialises the lambda, i.e. a candidate can be dead on grounds of the
  LANGUAGE before any population is counted, and reading the CALLEE rather than the wrapper is what
  shows it; **(c)** candidate 4's obvious shared-memo fix is a **SOUNDNESS bug, not merely a small
  prize** — `narrowTypeFromFlowCore` handles re-entrant walks at `narrowLiveDepth == 0` by design, so
  a shared instance would be cleared under a live outer walk and a wrong serve there is a WRONG
  NARROWED TYPE; and **34.2%** of memos outgrow 32 slots, so `clear()` is not obviously cheaper than
  the allocation (round 899: price a container swap NET). **NEW REUSABLE CONSTANT, the allocation twin
  of round 904's ~1.7 M map-ops bar: a pure-allocation candidate needs > 113,000 allocations/rebuild
  at a generous 150 ns, or > 340,000 at a realistic 50 ns, to clear the ~17 ms floor** — which refuses
  most per-node allocation candidates by arithmetic, the whole spine visiting 856,962 nodes.
  **AND THE ONE THING THE AUDIT NEVER NOTICED, still under the floor:** `mappedNodeTypeKey` spends
  **110,780 parent-chain climbs plus 110,780 `String`-keyed map probes (~5.5 ms)** so that 76.5% of
  calls can answer "foreign file" — comparable to the named mechanism, and structurally required by
  the gate; the WHOLE function at these generous rates is ~15 ms, still under the floor.

**SUCCESSOR, PER THE WORK ORDER NOTE ABOVE — a refusing round must name one.** With round 908 closing
the spine side and round 912 pricing the audit residue, **the checker-side pool is empty in the
literal sense: nothing checker-side is left unpriced.** **The successor is the (API.\*) arc, whose
next unchecked item is (API.3b) go-to-definition, with (API.3c) — batching a whole file's spans into
ONE build — as the item that makes the API practical for an editor.** The remaining PERF levers are
ARTIFACT-level and **both are gated, which a next agent must not rediscover**: (ART.1) is gated on the
owner's RELEASE decision and not on engineering (`native.yml` already builds Oracle + PGO and verifies
byte-identity), and (ART.2) is gated on a **CRaC JDK that is NO LONGER INSTALLED on this box**
(`/usr/lib/jvm` holds Zulu 26 and OpenJDK 25; `~/jdks` holds 17 and 21 — none of them a CRaC build), so
neither its `afterRestore` cwd fix nor a re-measurement can be compiled or verified locally.

**THE SEARCH STATE, AFTER SIX CONSECUTIVE REFUSALS (rounds 903-908), AMENDED ROUND 912 — READ THIS
BEFORE PICKING THE NEXT CANDIDATE. THE CHECKER-SIDE POOL IS NOW EMPTY, AND SINCE ROUND 912 IT IS EMPTY
OF UNPRICED CANDIDATES TOO.** 903 refused at 0.085%, 904 at 0.334% (14 sites TOGETHER), 905 at 0.074%, 906
measured a REGRESSION and closed a whole direction, 907 refused by census and closed a family. **Every
candidate ranked off the JFR profile in this arc has come in 2-21x over when measured — nine of ten
in the recorded scoreboard, six of six this session.** Meanwhile 61% of the warm rebuild is
unclassified residue, **no single JFR row is above 1.81%**, and the box cannot resolve below ~1.5%.
**That is what an exhausted search looks like.** It is not a failure — the compiler is -10.5% over
rounds 887-898 and warm xtsc is 2.05x tsc check-only — but a sixth single-row candidate should be
justified against this record rather than picked off a profile.

**THE MEASURED LEVERS THAT ARE *NOT* EXHAUSTED ARE AT THE ARTIFACT LEVEL, AND THEY ARE AN ORDER OF
MAGNITUDE LARGER THAN ANYTHING LEFT HERE.** Both are already measured, not speculative:

- [ ] **(ART.1) Ship the PGO'd native image. -21.2% check-only / -19.1% emit**, 5/5 paired in both
  modes, 46 diagnostics and all 78 emitted `.js` byte-identical (`docs/perf/aot-native-image.md`
  § 10). Needs Oracle GraalVM (`-graal` in SDKMAN; CE's `native-image --help` does not mention the
  word) and an `.iprof` trained on BOTH modes — a check-only-only profile leaves the
  Transformer/Emitter on static heuristics. This is the biggest single lever ever measured in this arc.
  **CORRECTED round 909 — the entry's premise ("CI currently ships the Community Edition arm, which
  has no PGO at all") IS STALE AND MUST NOT BE RE-INHERITED:** `native.yml:60-72` already builds
  **Oracle + PGO** via `scripts/build-native-pgo.sh`, verifies byte-identity against the JVM and
  uploads `xtsc-linux-x64`; `bench.yml` builds the Oracle **BASE** image per push deliberately (the
  PGO cycle is too slow to pay per push for a column that is not the headline). **So the engineering
  exists and what remains is the SHIPPING decision — attaching the binary to releases, already tracked
  as (AOT.1) and explicitly the owner's** (`native.yml:8`). Also **not measurable on the dev box: no
  GraalVM is installed there** (Zulu 26 / OpenJDK 25 only), so any re-measurement is a CI job or an
  install first.

- [ ] **(ART.2) CRaC — ~30 ms restore at FULL WARM SPEED** (6.8-7.3 s against 24-25 s cold, 3.4x,
  output byte-identical bar the `time:` line; `docs/perf/crac-checkpoint.md`). **Blocked on one known
  defect, not on the mechanism**: the restored process keeps the CHECKPOINT's working directory —
  round 873's bug one layer down — so a CRaC CLI must re-install the real cwd through
  `SystemVfs.workingDirectory` in an `afterRestore` hook, exactly as `CompileServer` already does per
  request. Unmeasured risk: the 340 MB image was page-cache-hot in every restore taken so far.
  **CORRECTED round 912 — AND THIS IS ALSO A LOCAL-TOOLING BLOCK, NOT ONLY A CODE ONE: the CRaC JDK
  IS NO LONGER INSTALLED ON THIS BOX.** `/usr/lib/jvm` holds Zulu 26 and OpenJDK 25 and `~/jdks` holds
  17 and 21 — none of them a CRaC build — so neither the `afterRestore` fix nor a re-measurement can
  be compiled or verified locally; it needs a Zulu CRaC install (or CI) first. Do not rediscover this
  by writing the hook and finding nothing to run it on.

**THE ROUND-903 HOT-PATH AUDIT'S FOUR UNPRICED CANDIDATES ARE NOW PRICED AND ALL FOUR ARE REFUSED —
see (WARM.35) above, and do not re-raise them from this block's former wording** (both copies of it
are collapsed into that entry; the record it stood on, "~88 k/rebuild", was a transcribed source
comment rather than a measurement).

**CLOSED IN ROUND 903, DO NOT RE-RAISE** (round 903, `docs/perf/type-node-key-price.md`): the
`nodeTypes` deep AST-value key, **refused at 0.085%** — its premium over a `(file, nodeId)`
`LongKeyMap` is 12.98 ns over 354,131 ops = 4.60 ms, and `A - B` is an UPPER bound. Round 896's
`nodeTypeResolutionInProgress` sentinel falls with it at 1.54 ms. The JFR row's other owner is
`isPerFileDependentRefNode` at 3.70 ms; family 9.04 ms against a 57.1 ms row.
