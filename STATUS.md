# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **192,309** lines (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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

