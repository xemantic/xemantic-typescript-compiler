# Status


**THE WEAK-TYPE RULE DID NOT DISTRIBUTE OVER A **UNION** TARGET — SO IT WAS ABSENT FROM THE
MAJORITY OF THE POSITIONS WHERE IT FIRES (2026-08-27, (CHK.57)).** `weakTargetProperties`
answers null for a `Type.Union`, so every B482 walker — the ones that EMIT TS2559/TS2560 at
a named position — was blind to a weak type reached through one, while (CHK.54)'s SELECTION
and (CHK.56)'s TS2769 path had folded over constituents all along. `T | null` /
`T | undefined` is the commonest parameter and variable shape in real TypeScript.
`weakUnionRefusalConstituent` composes the verdict (`weakParamRefusesArg`) and the display
(`weakRefusalDisplayTarget`) into the single-signature CALL argument site and
`tryEmitTopLevelWeakVarDecl`, as a branch DISJOINT from the bare-target one — so the bare
path is byte-identical and its controls stay green under every arm.

**BOTH POSITIONS NOW MATCH tsc 7.0.2 EXACTLY** — code, message, line and column, read off
the compiler and never derived — as do the `| undefined`, interface-, alias- and
`Partial<…>`-constituent, non-fresh-object-source and REST-parameter variants.
**Three shapes refuse deliberately, each MEASURED**: two or more non-nullish constituents
(tsc's TS2345/TS2322 naming the whole union needs the RELATION to reject); an object-literal
ARGUMENT ((CHK.56)'s boundary — tsc's excess check squiggles the property two columns
right); and a CALLABLE source, because tsc emits TS2560 only when CALLING the source yields
an assignable value (`() => number` against a weak object is TS**2559**) where we emit 2560
for every callable — a pre-existing BARE-target divergence that would have been inherited as
a wrong-CODE row.

**TWO ABLATION FINDINGS WORTH MORE THAN THE FIX.** The queue entry's own two-constituent
example (`{ zzzA?: null } | string`) is a **DEAD ARM** — with a non-weak FIRST constituent,
dropping the single-survivor test still emits nothing, because the emitter bails on a
`string` target anyway; the discriminating shape is two WEAK object constituents, and with
it the arm went 0 -> 1 RED (round 902). And the helper's `weakParamRefusesArg` call is a
**REDUNDANT guard**, explicable term for term: for the single surviving constituent every
test it makes is re-made by `tryEmitWeakTypeAssignment`. Recorded as such, not claimed as
coverage (round 807).

**GATES.** Suite **16,169 / 0 / 3** (+14, exactly the one new class), **no corpus baseline
moved** — the 13k baselines carry no weak-union shape at all. `cost_gate.py` exit 0
unrebaselined, `output.errors` **46**, the table the parent's to +0.006% on its largest
counter. `huge_methods --fail-over 0` exit 0, **783** classes. 8-profile grid over two
session-built binaries (`javap` control 0 vs 1): capture md5 `503774c2…` on BOTH, per-profile
`diff` clean — **`added=0 removed=0` on all eight**, unmoved since (CHK.54).
`partition-equivalence` **EQUIVALENT, all 78**, floor **63 ms** [63, 62, 51, 81] — one draw.
`capture-equivalence` **1,005 / 43 of 76 / moreAny 0**, `definitions` **360,376**, both ARM
DIGESTs unmoved. **`knip` 48 -> 48 and `jsonrepair` 4 -> 4, EVERY ROW BYTE-IDENTICAL** — the
queue item's "it ADDS rows … expect it to fire on real code" is measured FALSE, and (CHK.54)
is why: selection already refuses these signatures, so `readFileSync` picks the `string`
overload and the argument site never asks.

**SEVEN ABLATION ARMS, ONE MISTAKE EACH, ALL SEVEN CLASS md5s DISTINCT.** a0 (whole change
reverted, parent rebuilt this session) **7 RED — exactly the seven positives**; a1 (object-
literal guard dropped) **1**, a2 (single-survivor test dropped) **1**, a3 (callable guard
dropped) **1**, each uniquely its own pin; a4 (argument site removed) **6**, a5 (var-decl
branch removed) **2**; a6 (the verdict not asked) **0 — a redundant guard**. Residue —
the RETURN and ASSIGNMENT positions have no weak walker at all, and the 2559/2560 split —
queued as (CHK.58) with a fixture apiece.

**THE TS2769 *DIAGNOSTIC* PATH DID NOT ASK THE WEAK-TYPE RULE — AND THE ITEM'S "HARD
PART" WAS A **tsgo RENDERING**, NOT tsc's (2026-08-27, (CHK.56)).** (CHK.54) gave overload
SELECTION the weak rule and left the diagnostic path alone, so `allArgumentsMatch`
accepted what `signatureAcceptsArgs` refused and a call whose every overload has a
disjoint all-optional parameter was SILENT. The queue item recorded the elaboration as the
work — `getFirstArgumentError` walks the plain relation, which ACCEPTS the argument, finds
no failing argument and drops the overload out of the chain. Half of that is right: the
subline really is TS2559's *no properties in common* wording and is now minted beside the
walk, on the path where the relation SUCCEEDED. **The other half is not.** tsgo 7.0.2
prints `The last overload gave the following error.` for **2, 3 and 4** candidates alike;
PRISTINE tsc prints `Overload N of M, '<sig>', gave the following error.` per candidate —
**42** `typescript-repo` baselines against **4** — and
`tsxStatelessFunctionComponentOverload4.errors.txt` carries a *no properties in common*
subline inside exactly that chain. Our chain has had the pristine shape since B418, so no
"which overload" policy was needed at all and the item's "a TS2769 naming the wrong
overload is worse than silence" risk never arose. Round 938's law, paid again.

**TWO THINGS MEASURED RATHER THAN GUESSED.** A UNION parameter names a CONSTITUENT only
when exactly one survives dropping `null`/`undefined` (`ZzzWk | null` -> `'ZzzWk'`); two or
more take the ordinary assignability wording naming the whole union — the verdict is a
refusal either way, only the sentence differs. And an OBJECT-LITERAL argument is refused
outright, because tsc's freshness/excess check runs ABOVE the weak check and a fresh
literal sharing no property name has EVERY property excess: `f({ zzzZ: 1 })` is
`Object literal may only specify known properties…` at the PROPERTY, one column right of
where the weak wording would sit. That shape stays SILENT rather than acquiring a
diagnostic at the wrong span; the NON-fresh source of the identical type is the weak
wording and is pinned.

**A SECOND HOLE MEASURED AND QUEUED AS (CHK.57).** The bare weak target is correct and
byte-identical to tsc in every position; a weak target reached through a **UNION** is
silent here in BOTH the single-signature call and the var-decl positions
(`(o: { zzzA?: null } | null)` with `123`, `const v: {…} | null = "utf8"`) where tsc says
TS2559. Different mechanism — the B482 walkers, not the overload helpers — so it is queued
rather than folded in.

**GATES.** Suite **16,155 / 0 / 3** (+11, exactly the one new class), no corpus baseline
moved — run twice, the first reading 16,155 / 3 / 3 on three of this round's own
hand-derived `character` assertions, since replaced by tsc's own coordinates. `cost_gate.py` exit 0 unrebaselined, `output.errors` **46**, and the table is
**digit-for-digit the PARENT's** (a0 binary, same session) — this change costs 0.00% on
the compiler profile, the expected control for a question asked only after the relation
ACCEPTED. `huge_methods --fail-over 0` exit 0, **783** classes. 8-profile grid over two
session-built binaries (`javap` control 0 vs 2): capture md5 `503774c2…` on both,
**`added=0 removed=0` on all eight**. `partition-equivalence` **EQUIVALENT, all 78**, floor
**58 ms** [79, 58, 55, 56] — one draw. `capture-equivalence` **1,005 / 43 of 76 /
moreAny 0**, `definitions` **360,376**, both ARM DIGESTs unmoved.
**`knip` @ `dc7aca5` 48 -> 48 and `jsonrepair` 3.13.1 4 -> 4, byte-identical** — the queue
item's "it ADDS rows" is measured FALSE on every corpus this repo has.

**EIGHT ABLATION ARMS, ONE MISTAKE EACH, ALL EIGHT CLASS md5s DISTINCT.** a0 (whole change
reverted, parent rebuilt this session) **6 RED — exactly the six positives**; a1 (the
object-literal guard dropped) **1**, uniquely its own pin; a6 (display target always the
whole parameter) **2**, uniquely the two union pins; a7 (a union never names a constituent)
**1**, uniquely the one-constituent pin. **a2/a3/a4 each read 6 and are a ROUND-927 TRIPLE**
— each alone deletes the diagnostic, so none is redundant, but no pin separates which layer
failed. **a5 reads 0 and is recorded as UNDISCRIMINATED, not provably unobservable**: it can
only matter through B418's tie-break, which nothing here exercises.

**AN OBJECT LITERAL'S LITERAL PROPERTIES WIDEN, AND THAT ONE FACT BIT AT *BOTH*
OVERLOAD SITES — A FALSE TS2769 AT THE DIAGNOSTIC AND A **WRONG TYPE** AT SELECTION
(2026-08-27, (CHK.55)).** The queue item carried (b) an object-literal FP and a third
"mechanism this round did not locate" (matrix row H) as separate holes. Measured: they
are ONE. `getTypeOfExpression` types `{ encoding: "utf8" }` as `{ encoding: string }`
— there is no fresh-literal machinery — so a target property with a literal type
rejects. The DIAGNOSTIC path had round 728's rescue but refused a target INTERFACE with
heritage and a UNION with >1 non-nullish constituent; SELECTION had **no rescue at
all**, so every candidate was passed over and `resolveCallOverload`'s `arityMatches[0]`
fallback answered. **One fixture shows both at once** — a false TS2769 *and* the first
overload's return type where tsc gives the second's — and that co-occurrence is what
identifies them as one mechanism.

**THE HERITAGE REFUSAL WAS NEVER NEEDED.** Round 728 refused a target with base types
because "an inherited required property would not be enumerated below"; measured, that
is not true here — `resolveInterfaceMembersCore` folds base members into the derived
type's own `members` and sets `properties = members.values.toList()`, so both
enumerations already saw them. That refusal was `knip`'s last overload row.

**A THIRD INTERACTION, FOUND BY TRYING TO FALSIFY AN ABLATION ARM RATHER THAN BY READING
CODE.** Round 728 put the rescue on the REJECTING path so the happy path pays nothing —
but a weak constituent accepts any non-nullish value structurally, so for
`{ zzzA?: 0 } | { zzzE: "u" }` the relation SUCCEEDS through the weak constituent, the
rejecting path is never taken, and (CHK.54)'s weak rule refuses the signature having
never asked about the OTHER constituent. Guarded, short-circuit, costing nothing on the
compiler profile. **Its by-product is a retraction**: the `continue` beside the rescue
was documented as load-bearing and its ablation reads **0 RED** once the guard exists —
recorded as PROVABLY UNOBSERVABLE, not as coverage.

**GATES.** Suite **16,144 / 0 / 3** (+11, exactly the one new class); **no corpus
baseline moved by any of the three edits**. `cost_gate.py` exit 0 unrebaselined,
`output.errors` **46**, the largest counter move **+0.59%** (`typeOfExpr.calls`) — the
rescue being consulted on the rejecting path — and the third edit is digit-for-digit
identical to the second, i.e. it costs 0.00% on this profile.
`huge_methods --fail-over 0` exit 0, **783** classes, 0 over limit. 8-profile grid over
a parent rebuilt in this session (md5 `86ec37c3…`, reproducing (CHK.54)'s recorded
landed digest) with a `javap` control of **0 vs 2**: **`added=0 removed=0` on all
eight**, capture md5 `503774c2…` unmoved. `partition-equivalence` **EQUIVALENT 78/78**,
floor **61 ms** [54, 61, 74, 60] — one draw. `capture-equivalence` **1,005 / 43 of 76 /
moreAny 0**, `definitions` **360,376**, digests `full=-3735929574989657502
narrow=-2075467818767010709`.

**LIBRARIES: `knip` @ `dc7aca5` 49 -> 48**, exactly `src/util/git.ts:17:55`
(`execSync(cmd, { encoding: 'utf8', stdio: [...] })`) and nothing added;
**`jsonrepair` 3.13.1 4 -> 4 byte-identical**. BEFORE arms captured on the rebuilt
parent and byte-identical to the session-start capture.

**SEVEN ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT.** a0 (the whole
change reverted — the parent, rebuilt) **4 RED**, exactly the four positives; a1
(heritage refusal restored) **2**; a2 (union back to `singleOrNull`) **1**, uniquely the
union pin; a3 (SELECTION no longer asks) **2**, uniquely row H; a4 (the union fold
accepts unconditionally) **2**, uniquely the union-refusal pin; a6 (the weak-rule guard
reverted) **1**, uniquely its own pin. **a5 (`continue` -> fall-through) 0 RED and
recorded as provably unobservable, not as coverage.**

**(CHK.55)(a) IS DELIBERATELY NOT CLOSED** — the TS2769 path still does not ask the weak
rule, so `zzzU(123)` is silent where tsc says `Type '123' has no properties in common
with type '{ … }'`. That is a MISSING error, the least damaging of the three, and its
elaboration needs its own design; re-queued with tsc's exact message.

**OVERLOAD SELECTION IGNORED THE WEAK-TYPE RULE, SO AN ALL-OPTIONAL PARAMETER ACCEPTED
*ANY* ARGUMENT — `readFileSync(p, 'utf8')` PICKED THE `Buffer` OVERLOAD, AND THAT WAS
FIVE OF `knip`'s ROWS (2026-08-26, (CHK.54)).** The queue item read it as "an OPTIONAL
parameter overload is selected without checking the argument"; measured over a 14-row
matrix against tsc 7.0.2, **optionality is not the axis and the argument IS checked** —
making the same parameter non-optional reproduces it identically, and the plain shape
`(x, y?: null)` / `(x, y: "u")` called with `("a", "u")` already selected the second
overload correctly on the parent. What decides it is that overload 1's parameter is a
**weak type** (`{ encoding?: null; flag?: string } | null`) and our relation says a string
literal IS assignable to one. That is deliberate — the weak rule lives in the B482
*walkers*, which emit TS2559/TS2560 at named positions, where tsc puts it inside
`checkTypeRelatedTo` so every consumer inherits it. `signatureAcceptsArgs` is a consumer
that did not.

**THE RULE IS ONE SENTENCE**: an argument sharing no property name with a weak parameter
does not select that overload, and for a UNION parameter that is decided per constituent
— tsc's `typeRelatedToSomeType`, where relating through a weak-and-disjoint constituent
does not count. Deliberately NOT pushed into the relation (that moves every assignability
verdict at once) and NOT into `allArgumentsMatch` (the TS2769 path ADDS rows — (CHK.55)).

**A SECOND, INDEPENDENT RULE THE SAME MATRIX FOUND AND (CHK.54b) LANDED: tsc TRIES A
*SPECIALIZED* OVERLOAD FIRST.** `f(x: string): A` before `f(x: "a"): B` answered `A` for
`f("a")` where tsc answers `B` — `reorderCandidates` / GH#1133 hoists every signature with
a **literal type NODE** parameter annotation ahead of the rest, stable within each group.
**The test is SYNTACTIC and must stay so**: a literal UNION is a UnionType node and does
NOT specialize (measured), so a rule derived from the resolved TYPE diverges the other way.
The matrix goes from 10 to 12 of 14 rows at tsc parity.

**GATES.** Suite **16,133 / 0 / 3** (+15, exactly the two new classes); **no corpus
baseline moved by either change**. `cost_gate.py` passes unrebaselined, exit 0, and all
**20 counters are DIGIT-FOR-DIGIT identical to the parent's** (parent rebuilt this session
and its table captured) — `output.errors` **46**, so this round costs 0.00%.
`huge_methods --fail-over 0` exit 0, **783** classes. `partition-equivalence` **EQUIVALENT,
all 78**, floor **56 ms** [56, 54, 67, 56] — one draw. `capture-equivalence` **1,005 / 43
of 76 / moreAny 0**, `definitions` **360,376**, both ARM DIGESTs unmoved. 8-profile grid
with both arms built this session, md5s `2d907a1a…` / `c23ed851…` / `86ec37c3…` and a
`javap` control (0 vs 2 vs 6): **`added=0 removed=0` on all eight**, capture md5
`503774c2…` identical across all three binaries.

**LIBRARIES: `knip` @ `dc7aca5` 54 -> 49**, exactly the five `Buffer<ArrayBuffer>` rows
removed and nothing added — the family (CHK.50) surfaced is closed bar one row.
**`jsonrepair` 3.13.1 4 -> 4 byte-identical.** The specialized-first rule moves NEITHER
library and neither profile; its only observable effect anywhere is the hand-written matrix.

**TEN ABLATION ARMS, ONE MISTAKE EACH.** a0 (whole weak change reverted, parent rebuilt)
**3 RED** — the three positives; a1/a2/a3 (empty-source guard, shared-property test,
other-constituent relation test) **1 RED each and each uniquely its own**; a4/a5/a6
**0 RED, KEPT** — a4 is additionally byte-identical on the compiler profile AND on knip's
49 rows and on two purpose-built falsifiers, a5 and a6 are argued provably unobservable.
b0 (reorder reverted) **4 of 7**; b1 (hoist by resolved type shape) **2** — uniquely the
two "does not specialize" refusals; b2 (reverse within the group) **1**. b0 against the
weak pins reads **0**, so the two changes are independent.

**THE THREE REFUSAL PINS WERE BLIND ON THE FIRST WRITING AND ONLY AN ARM SAW IT.**
`resolveCallOverload` falls back to `arityMatches[0]` when nothing accepts, so refusing a
**first-declared** overload restores exactly the answer the refusal removes: a1/a2/a3 each
demonstrably changed the selection and all three pins read **0 RED**. Declaring the weak
overload SECOND makes the refusal observable.

**A `declare global { … }` BLOCK'S EXPORTS NEVER REACHED `globals` — THE CARRIER MERGED AND
THE CONTENTS DID NOT, AND **SEVEN OF EIGHT** DECLARATION FORMS WERE WRONG (2026-08-26,
(CHK.50)).** `declare global` parses as a ModuleDeclaration named `global`, so step 1 merged
that symbol (INV.3(d)'s deliberate global contribution) and nothing merged its `exports`.
**The queue item's "the `var` form works, so the value half is fine" is measured WRONG**: `var`
was correct only in the DECLARING file — cross-file it was silently `any` — and
`function`/`namespace`/`class` were `any` in BOTH scopes, TS2304-suppressed by
`globalAugmentationNames` and typed by nothing; `interface`/`type`/`enum` were TS2304
outright; and an `interface Date { … }` augmentation reported **TS2339 on the member it had
just declared**. Only a WRITE probe sees any of that.

**FOUR EDITS, THREE OF THEM FORCED BY THE FIRST.** `init:mergeGlobalAugmentations` merges each
LEGAL block's exports (legality mirrors `spineCheckGlobalAugmentation`'s TS2669 predicate, so a
global-SCRIPT block contributes nothing — as in tsgo); `buildPerFileScopes` seeds every file
with the ADOPTED names, **without which the two halves disagree — the type resolves through
`globals` while the unresolved-name family reports TS2304 on the name it just typed**;
`isNameExportedFromNamespace` learns that a namespace ambient by CONTEXT implicitly exports
(`declare global { namespace NodeJS { … } }`, a regression this round would otherwise have
INTRODUCED); and `namespace globalThis` is refused, because it augments the global scope
itself — publishing it reddened the corpus case `extendGlobalThis`, the only baseline this
round moved and the only instrument that saw it. Queued as (CHK.53).

**(CHK.51)'s NAMED COST IS PAID.** Its `globalAugmentedInterfaceNames` set existed only
because such a block did not merge; the set and its collector are DELETED, the all-lib test
admits a `declare global` InterfaceDeclaration, and `el.zzzNotThere` on an augmented
`HTMLElement` is now TS2339 as tsgo says. Both matrices match tsgo 7.0.2 **row for row**.

**GATES.** Suite **16,118 / 0 / 3** (+11, exactly the new class); the landed shape moves no
corpus baseline. `cost_gate.py` **PASSES with NO rebaseline**, exit 0 — `output.errors` **46**,
`spine.nodes` +0.00%, largest movements `narrow.memoServed` **+0.69%** / `typeOfExpr.calls`
**+0.59%**, digit-for-digit (CHK.49)'s and (CHK.51)'s, i.e. this round contributes 0.00%.
`huge_methods --fail-over 0` exit 0, **783** classes scanned. `partition-equivalence`
**EQUIVALENT, all 78**, floor **56 ms** [56, 56, 57, 52] — one draw, no leading ramp.
`capture-equivalence` **1,005 / 43 of 76 / moreAny 0**, `definitions` **360,376** — unmoved.
8-profile BEFORE/AFTER grid, both arms built this session, md5s `b347a38a…` / `3163ffc4…` and a
`javap` control (0 vs 3): **`added=0 removed=0` on all eight**.

**THE knip ROW COUNT WENT *UP*, AND THAT IS THE HONEST RESULT: 49 -> 54.** One row GOES — a
real fix, `TS2591 Cannot find name 'Buffer'`, since `@types/node` declares it in a
`declare global` block. Six ARRIVE, and every one is a **pre-existing overload-selection
defect that `any` had been hiding**: `readFileSync(p, 'utf8')` picks the `Buffer` overload
whose parameter `"utf8"` is not assignable to. Proved pre-existing by MEASUREMENT — a six-line
repro with the interface declared as a plain local, no `declare global` anywhere, emits the
identical rows on the PARENT binary and the landed one. Queued as (CHK.54).
**`jsonrepair` 3.13.1: 4 -> 4 byte-identical, and its tsconfig loads `dom` — a real arm.**

**TEN ABLATION ARMS, ONE MISTAKE EACH, each `cmp`-diffed against its OWN snapshot with the
restore verified OUTSIDE the driver, each anchor asserted unique, each `Checker.class` md5
recorded.** a0 (whole change reverted, parent rebuilt this session) **9 RED**; a1 (the merge
loop alone) **9 — the same set**, so a0/a1 are a NESTING and not a round-927 pair: edits 2-4
are reachable only because the merge exists. a2 (per-file seed) **3**; a3 (legality gate) **1**;
a6 (`globalThis`) **1**; a7 (ambient-by-context) **1**; a8 (the (CHK.51) allowance) **2**;
a9 (ambient-module recursion) **1**. **a4 and a5 read 0 RED and are KEPT as UNDISCRIMINATED** —
and a5 was given a PURPOSE-BUILT falsifier (two blocks in one file against a
`declarations.addAll` with no membership test) which **still read 0**, because a member table
is keyed by NAME (round 813: the retry was tried and it answered).
