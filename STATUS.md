# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **198,022** lines (**−1,941 across (P18.53)-(P18.55)**, the first
sustained movement in the extraction direction since the metric was created; 191,070 when it was
created, and the (P18.9)-(P18.37) checker-parity arc ADDED ~5,200, which are fixes and pins, not
extractions). FOUR collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient
surface none for both — `TypeInstantiator` (four checker reads, one table write) and
**`NameResolver`, 2,284 lines, the name-resolution seam COMPLETE over three steps (26 checker
reads, NO writes; the row IMPROVED as the family closed, absorbing four of its own earlier
reads)**, all stated in the ledger. Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

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
