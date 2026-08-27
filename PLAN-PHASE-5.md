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

### Round (CHK.61)(b) — the display half **LANDED**; the checking half is **REFUSED with its price finally taken**, and the refusal turned up a **systematic FALSE NEGATIVE** that is not (b)

**THE HEADLINE, IN TWO PARTS.** (b)'s DISPLAY half is in: an optional member's
hover now carries `| undefined` and then narrows, at tsc 7.0.2's own answers, with
`added=0 removed=0` on all eight profiles and every library row byte-identical —
because the leg is confined to the CAPTURE, which production never computes. (b)'s
CHECKING half is REFUSED, and for the first time its price is measured rather than
asserted: **15 ours-only rows on the eight profiles**, of which **9 are net new**.

**(b) IS NOT SOUND ALONE, AND THE QUEUE'S "3 rows" WAS THE WRONG NUMBER BECAUSE IT
WAS THE WRONG ARM.** `build/chk61/patch_b.py` (add `| undefined` at
`computeRawTypeOfPropertyAccess`'s three `prop != null` returns) does not merely add
rows — on the round's own four-line repro it **DELETES a true positive**:
`const zzzA: string = zzzInst.zzzOpt` reports `Type 'number' is not assignable to
type 'string'` on the shipped binary and **NOTHING** with patch_b, because the
source becomes a nullish union and `canUseTypeEngine` refuses those against a
primitive target. So (b) is only expressible together with opening that gate, and
the honest arms are:

| arm | 8-profile ours-only rows | on the round's repro |
|---|---|---|
| `p_head` (shipped) | 0 (md5 `503774c2…`) | 2 of tsc's 4 rows, one with the wrong TYPE |
| `armG` — the nullish-union gate opened, nothing else | **11** | 6 of 6 at tsc's wording |
| `armBG` — patch_b **and** the gate | **15** | **4 of 4, at tsc's own positions and wording** |

`armBG` reproduces tsc EXACTLY on the repro, and that is what the refusal costs.
Two of `armG`'s 11 are FIXED by (b) (`emitter.ts:1479`, `organizeImports.ts:862` —
a `var` whose initializer is an optional member, inferred `boolean`/`string` here
because the `| undefined` was dropped), and (b) adds six of its own.

**THE SUPPRESSOR IS ONE LINE, AND WHAT IT HIDES IS A LARGE FALSE NEGATIVE THAT HAS
NOTHING TO DO WITH (b).** `canUseTypeEngine`'s

```kotlin
if (sourceType is Type.Union && targetIsPrimitive) {
    val hasNullish = sourceType.types.any { … Null or Undefined … }
    if (!hasNullish) return true
}
```

means **`T | undefined` is silently assignable to `T`** at a variable
DECLARATION, an ASSIGNMENT and a RETURN whenever the target is a primitive. On a
six-line fixture tsc emits 6 rows and we emit 2; the ARGUMENT position and a UNION
target are the two that work. This is a shipped FN, not a (b) artefact, and it is
now queued as **(CHK.63)** with the full row list.

**THE FIVE NARROWING GAPS ARE FIVE MECHANISMS, NOT ONE — SO "close the shared cause
then (b)" WAS NOT AVAILABLE.** Reproduced in a 30-line fixture with EXPLICIT
`| undefined` members, i.e. on the shipped binary with no patch at all:

  1. an `&&` if-condition narrows **neither** operand into the then-branch
     (sourcemap.ts:164/165/166 — three rows), while a single condition does;
  2. `if (x === undefined) continue;` does not narrow the rest of a loop body
     (core.ts:2191, path.ts:585);
  3. an assignment inside the guarded branch (`if (id === undefined) { m.set(t, id = t) }`)
     does not narrow after the `if` (parser.ts:2642);
  4. definite assignment across an if/else (`let i: number|undefined` assigned in
     both arms) does not narrow (checker.ts:35649);
  5. the optional-METHOD shapes — an outer `if (h.a && h.b)` surviving into a nested
     `for`+`if` (moduleNameResolver.ts:824, project.ts:502/528), a three-deep chain
     (moduleNameResolver.ts:2265) and an `&&` chain whose earlier conjunct narrows a
     later one (checker.ts:30269, TS18048).

**AND THE `&&` GAP IS NOT WHERE IT LOOKS.** The FLOW WALK handles `&&` correctly —
`if (a && b) { a.length }` and `if (a && b) { take(a) }` are both right. The gap is
that the RETURN and ASSIGNMENT readers do not consult it for a PRIMITIVE target
(round 784's documented gate, `targetType is Interface|Reference|Object|Union|Intersection`),
so they fall back to `currentLocalTypes`, which the LEGACY if-arm machinery
(`extractNullNarrowing`) fills — and that helper returns ONE `(name, type)` pair and
does not decompose an `&&` at all. Queued as **(CHK.64)**. A declaration with a
primitive target does narrow, which is why the gap is invisible one line away.

**WHAT LANDED, AND WHY IT IS FREE.** `typeCaptureOptionalMemberType` adds the
constituent and then re-runs `getNarrowedTypeForReference`, so `if (o.p)` — and an
`&&` chain in either operand position — still hovers `number`. It is on the FLOW
WALK, not the legacy machinery, which is exactly why the display half can be right
where the checking half would not be. A UNION receiver is decided PER CONSTITUENT
(`memberIsOptionalOnReceiver`): tsc types `({p?: number} | {p: string}).p` as
`string | number | undefined`, and asking `getPropertyOfType` about the union would
make the verdict a function of constituent ORDER (round 916). Every expectation was
read out of **tsc 7.0.2's own LSP** (`scripts/lsp_hover.py`), which is also how the
two RESIDUE rows (`super.<opt>` and an INTERSECTION receiver, both `number` here
against tsc's `number | undefined`) are recorded as divergences rather than as
opinions.

**GATES, per commit, all foreground, one at a time.** Suite **16,281 / 16,283 /
16,286**, 0 failed, 3 skipped (+8/+2/+3, exactly the new subtests) — **no corpus
baseline moved on any of the three**. `cost_gate.py` exit 0 on all three (read from
the gate, not a pipeline), `output.errors` **46**, every counter digit-identical to
(CHK.62)'s standing residual (`typeOfExpr.calls` +1.41%, `globals.lookups` +1.52%,
`globals.misses` +1.74%). `huge_methods --fail-over 0` exit 0, **783** classes, 0
over. **8-profile grid `503774c23b4535130ffdebabef430cf0` on both code commits,
byte-identical PER PROFILE against a parent capture taken in this session** (parent
`Checker.class` md5 `e7963e28`, rebuilt here). `knip` **48** and `jsonrepair` **4**,
EVERY ROW byte-identical against a parent arm rebuilt in this session.
`partition-equivalence` EQUIVALENT all 78 (floors 72 ms [88, 56, 72, 63] and 66 ms
[56, 62, 66, 75] — one draw each, the spread is the harness's).

**`capture-equivalence` IS THE ONE GATE THAT MOVED, AND ITS MOVE IS AN IMPROVEMENT
CLASSIFIED PER SPAN.** `DIVERGED` **1,005 -> 985 -> 968** in 43 of 76,
`types` tracking it, `definitions` **360,414** UNCHANGED, `narrowRendersMoreAny` 0.
Both ARM DIGESTs re-recorded per commit (final `full=2642712547047802314
narrow=6791141519233628706`). The whole delta was enumerated at
`XTSC_CAPEQ_PRINT=200000` and classified per element: **all 38 moved spans are the
alias-display first-wins family** (`ModuleName`/`ModuleExportName`,
`BindingOrAssignmentPattern`/`DestructuringPattern`,
`AccessExpression`/`PropertyAccessExpression | ElementAccessExpression`), i.e.
(INC.27)'s interning-key family shuffled by a changed first-touch order — **not one
of them is an optionality rendering**, and the second commit added **zero** new
divergences while removing 17.

**NINE ABLATION ARMS, ONE MISTAKE EACH (d8 excepted and labelled), EVERY CLASS md5
DISTINCT, EACH `cmp`-DIFFED AGAINST ITS OWN SNAPSHOT AND EACH RESTORE VERIFIED BY
`cmp`.**

| arm | injected mistake | class | RED |
|---|---|---|---|
| d0 | the widening removed entirely | `082f7042` | **4** — the three optional-member rows + the union row |
| d1 | widened but never RE-NARROWED | `e2e6045b` | **4** — uniquely the four guarded controls |
| d2 | the optionality gate dropped (widen every member) | `a21c94f2` | **1** — uniquely the REQUIRED control |
| d3 | a union decided by ALL instead of ANY | `724071bb` | **1** — uniquely the union row |
| d4 | the union asked of ITSELF (round 916's one-constituent answer) | `5d9ca935` | **0 -> 1** — UNPINNED, then fixed |
| d5 | the `super` refusal dropped | `0bd50a31` | **0 — REDUNDANT** |
| d6 | the INTERSECTION refusal dropped | `4eeeb742` | **0 — REDUNDANT** |
| d7 | the already-has-`undefined` early return dropped | `8a099b44` | **0 — REDUNDANT** |
| d8 | MECHANISM PROBE, two mistakes: the `super` refusal AND the `any`-receiver guard | `893d3230` | **0 — mechanism NOT located** |

**d1 IS THE ARM THAT MATTERS AND IT PROVES THE CONFINEMENT.** The queue entry
predicted that adding the constituent would render `number | undefined` inside an
`if (o.p)` guard; d1 is exactly that mistake and it reddens exactly the four
guarded controls and nothing else — so the re-narrowing is load-bearing and the
controls are coverage for it, which is why they are labelled both ways.
**d4 REPEATS LAST ROUND'S c2 EXACTLY**: 0 RED against the optional-first union
fixture, because `getPropertyOfType`'s union arm happens to answer the first
constituent's symbol; the ORDER SIBLING (`{ zzzOrd: string } | { zzzOrd?: number }`)
makes it **1 RED, uniquely that row**. **d5/d6/d7 are REDUNDANT and are now
recorded as such in the KDoc rather than claimed** — each has a fixture exercising
its shape and each stays green with the guard removed, because a lower layer
already declines (`getUnionType` dedupes; `getPropertyOfType` has no Intersection
branch; a `super` receiver resolves no member). d8 was a deliberate two-mistake
probe to name the `super` mechanism and **failed to** — it is reported as a
non-result, not as coverage.

**HOW VACUITY WAS RULED OUT, PER PIN.** The three optional-member pins were run
against the PARENT binary rebuilt in this session (`e7963e28`) with the fixture
already in place: **3 RED**, reading `number` / `string` / `number`. The union pin
and its order sibling redden under d0/d3/d4. The four guarded controls and the
REQUIRED control pass on the parent for the trivial reason that the parent never
widens — they are labelled CONTROLS and are counted as coverage only for d1 and d2,
which redden them uniquely. The two RESIDUE pins (`super`, INTERSECTION) are
labelled residue and counted as coverage for nothing. Every expected string came
from tsc's LSP, not from this project's opinion.

**WHAT DID NOT WORK, AND WHAT SURPRISED ME.**

  * **The four-line repro of (b) is a repro of TWO defects and reading it as one
    cost the first hour.** `zzzInst.zzzOptStr` against `string` is MISSING on the
    shipped binary while `zzzInst.zzzOpt` against `string` merely has the wrong
    type — same fixture, same line shape, two different mechanisms (the dropped
    constituent, and the nullish-union gate). The tell was that patch_b made the
    first row DISAPPEAR.
  * **Reproducing the "five narrowing gaps" with EXPLICIT `| undefined` members
    needed no patch and no build.** They are all shipped defects that the gate
    hides; the (b) patch only makes them reachable. Half an hour of building arms
    was avoidable by declaring the member `number | undefined` instead of `number?`.
  * **The `&&` diagnosis was wrong twice before it was right.** "The `&&` narrowing
    is broken" survived one fixture battery (V1-V7, every `&&` form red, every
    single-condition form green) and was refuted by the next (W1/W2 — member access
    and argument, both `&&`-guarded, both correct). What varies is the READER, not
    the condition.
  * **A KDoc that claims a refusal is deliberate is a claim the ablation can
    check**, and here it was wrong three times out of three. The comment now says
    "measured redundant, kept because it states the question this leg is not
    answering" — with the note that `super` would become load-bearing the moment it
    got a carrier, which is precisely what (CHK.61)(a) did for `this` last round.

### Round (CHK.62b)+(CHK.61)(1)+(CHK.61)(a) — the price of the `this`-receiver line fell **1 -> 0** and **it landed**: three defects closed, and the reverted intersection leg was UNSOUND rather than unmasking

**THE HEADLINE: (CHK.61)(a) IS IN, AT `added=0 removed=0` ON ALL EIGHT PROFILES.** Its price
was 6 dashboard rows at (CHK.61), 3 after (CHK.62), **1** after this round's (CHK.62b), and
**0** after this round's (CHK.61)(1). All six were false positives from pre-existing gaps the
`any` was hiding; not one of them was `this`-specific, which is exactly what the refusals
predicted and what took four rounds to prove one gap at a time.

**(CHK.62b) — AN ASSIGNMENT WHOSE RHS IS A `this`-METHOD CALL DID NOT NARROW THE ASSIGNED
REFERENCE, AND IT IS A SHIPPED DEFECT, NOT A PATCH ARTEFACT.** `rhsIsDefinitelyNonNullish`'s
CALL arm reads the callee's return ANNOTATION, so it resolves the callee through
`resolvePropertyMethodDecl`, which TYPES THE RECEIVER and bails at `recvType === anyType`.
The queue recorded it as visible only under `patch_a`; that is true of `build/chk62/g2k`,
whose declared unions all come from `this.zzzFind()` — but the moment the reference's union
comes from anything else the row is on the SHIPPED binary:
`let p = zzzFindFree(); p ??= this.zzzCreate(); return { p }` reported
`p: ZzzProj | undefined` where tsc 7.0.2 is silent. The carrier is confined to that
FLOW-ONLY resolver (both callers are narrowing resolvers, so a resolution can only ever
SUPPRESS), which is what made it separable from (a).

**THE OBSERVABLE IS THE OBJECT-LITERAL MEMBER AND *ONLY* IT, WHICH A WRITE PROBE CANNOT
SEE.** At the same flow point `const q: ZzzProj = p` is SILENT on the broken binary while
`return { p }` is a TS2322 — so CLAUDE.md's standing advice ("assert the narrowed type with a
write probe") is the wrong instrument here, and a write-probe pin would have been VACUOUS.
The value pin that works is a deliberately WRONG target: `return { zzzProj }` against
`{ zzzProj: string }` makes the checker NAME the member type it built —
`{ zzzProj: ZzzProj | undefined; }` before, `{ zzzProj: ZzzProj; }` after, and tsc agrees the
member is `ZzzProj`.

**(CHK.61)(1) — THE REVERTED ACCEPTANCE LEG WAS *UNSOUND*, NOT UNMASKING A DEFECT
ELSEWHERE, AND A CENSUS OF THE NEWLY ACCEPTED PAIRS SAID SO IN ONE RUN.** The prior round
recorded `callHierarchy.ts:199 'parent' does not exist on type 'never'` as "an acceptance in
the relation feeds `typeGuardMemberDisjoint`". Printing every pair the new rule accepts on
the services profile named exactly FOUR, all one shape:
`FunctionExpression & { name: undefined; parent: … }` accepted against `{ name: Identifier }`.
The merge rule is "the intersected member is a subtype of EVERY declaration, so ANY relating
declaration suffices" — sound only when each declaration's type is spelled out INCLUDING its
optionality. We model an optional member as plain `T` ((CHK.61)(b)), so `FunctionExpression`'s
`name?: Identifier` was picked as the relating declaration where the real intersected member
is `undefined`; the next negative type-predicate narrow then subtracted that constituent and
left `never`. Widening the SOURCE declaration to `T | undefined` LOCALLY inside the rule
fixes it — it is a suppression rule, so pessimism about a source member can only decline to
suppress. **Ablation arm b1 is exactly the earlier attempt's mistake and it reddens exactly
one pin.**

**(CHK.61)(a) — LANDED.** `thisReceiverCarrierType` (`currentClassForThis`'s declared
instance type) is consulted at `computeRawTypeOfPropertyAccess` and at
`resolvePropertyMethodDecl`, only where the receiver already typed `any`/`error`. On
`build/chk60/br/b2.ts`: **3 of tsc 7.0.2's 7 rows before, all 7 after, at tsc's own
positions** (5,11) (6,11) (8,20). It also converts `WeakCallableSourceAnchorTest`'s
`this`-member REFUSAL pin into a positive one at tsc's `(2,62)` / `(3,44)` — round 765's law
firing in the useful direction, and the only suite failure the whole round produced.

**AND IT IS A LANGUAGE-SERVICE WIN, WHICH ONLY `capture-equivalence` SEES**: `definitions`
**360,376 -> 360,414**, i.e. go-to-definition on a `this.<member>` caret now RESOLVES. Both
ARM DIGESTs moved (`full=5591703872112101713 narrow=704838071822341252`) with `DIVERGED`
UNCHANGED at 1,005 spans in 43 of 76 (types=1005, definitions=0, moreAny 0) — a FULL-BUILD
fix, so the full-vs-narrow relationship is untouched; (INC.26)'s rule, re-recorded rather
than read as a regression.

**GATES, per commit, all foreground, one at a time.** Suite **16,262 / 16,267 / 16,272 /
16,273**, 0 failed, 3 skipped (+5/+5/+5/+1, exactly the new classes) — **no corpus baseline
moved on any of the four.** `cost_gate.py` PASSES on all (exit read from the gate, not a
pipeline), `output.errors` **46**, every counter within a hundredth of a percent of
(CHK.62)'s standing residual (`typeOfExpr.calls` +1.42%/+1.41%, `globals.lookups` +1.52%,
`globals.misses` +1.75%/+1.74%). `huge_methods --fail-over 0` exit 0, **783** classes, 0
over. **8-profile grid `503774c23b4535130ffdebabef430cf0` on all three code commits,
INCLUDING the one that lands (a) — `added=0 removed=0` on all eight**, verified by a
per-profile `diff` against a parent capture taken in this session. `knip` **48** and
`jsonrepair` **4**, EVERY ROW byte-identical. `partition-equivalence` EQUIVALENT all 78
(floors 56 / 57 / 66 ms, one draw each — the spread is the harness's).

**NINE ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT, EACH `cmp`-DIFFED AGAINST
ITS OWN SNAPSHOT AND EACH RESTORE VERIFIED BY `cmp` PLUS A REBUILT md5.**

| arm | injected mistake | class | RED |
|---|---|---|---|
| a0 | the (CHK.62b) carrier removed from the flow resolver | `77222597` | **3** — the three (CHK.62b) positives |
| a1 | the carrier present but answering null | `4d268baa` | **3** — the same three (a ROUND-927 PAIR with a0) |
| a2 | any resolved `this.m()` treated as non-nullish | `ac22c4c7` | **1** — uniquely the nullable-return row |
| b0 | `intersectionMergedSatisfiesTarget` removed | `ca09ebe3` | **2** — the two gap-1 positives |
| b1 | the source-side `| undefined` dropped (the earlier attempt's exact mistake) | `30b3908a` | **1** — uniquely the optional-vs-`undefined` row |
| b2 | the missing-required-property refusal dropped | `62244808` | **1** — uniquely that row |
| c0 | the (a) carrier removed from `computeRawTypeOfPropertyAccess` | `20b9f09f` | **3** — the three (a) positives |
| c1 | the carrier consulted SECOND instead of first | `103f0a7d` | **0 — REDUNDANT** |
| c2 | the carrier answers for EVERY identifier receiver | `532c340f` | **0 — UNPINNED, then fixed** |

**c1 IS A REDUNDANT GUARD AND c2 WAS A HOLE.** The fallback for a bare `this` is exactly
`anyType`, so consulting the carrier first or second is observationally identical — the KDoc
claiming the order is load-bearing was an over-claim and now says what is. c2 is the
opposite: widening the carrier from `this` to any identifier receiver is plainly wrong (an
`any`-typed parameter in the same body would acquire the class's type) and NOTHING pinned it.
The round added the fixture that does (`zzzM(zzzP: any) { const zzzA: string = zzzP.zzzReq }`)
and re-ran c2 against it: **1 RED, uniquely that row** (class `0ae9232b`). Two rows were also
relabelled from CONTROL to COVERAGE, because an arm reddens each uniquely.

**HOW VACUITY WAS RULED OUT, PER PIN.** Every positive was measured on the PARENT binary,
rebuilt in this session (`Checker.class` md5 `181c293e` — the exact digest (CHK.62) recorded
as its landed binary), through the CLI, against `tools/tsgo-7.0.2/lib/tsc` on byte-identical
source; then shown RED under the arm naming its rule. **One candidate pin was DROPPED for
vacuity before it was written**: the write probe `const zzzQ: ZzzProj = zzzProj` after the
`this`-method assignment is silent on the parent, so it would have measured nothing. Two rows
are labelled CONTROL and are NOT counted as coverage (no arm reddens them): the free-function
RHS, and the merged-member CONTRADICTION direction.

**WHAT DID NOT WORK, AND WHAT SURPRISED ME.**

  * **The first two bisection matrices concluded the wrong axis.** `m8` in the second
    (`let zzzProj: ZzzProj | undefined = zzzCreateFree(); zzzProj ??= this.zzzCreate()`) was
    silent, which read as "the DECLARATION's initializer is the axis" — it is silent because
    `zzzCreateFree(): ZzzProj` is already non-nullish, so the `??=` never mattered. A matrix
    cell that is silent for a TRIVIAL reason is indistinguishable from one that is silent for
    the reason you are testing; the third matrix, which held the declaration fixed, is what
    named the receiver.
  * **A `getNarrowedTypeForReference` debug diagnostic settled in one run what two matrices
    could not.** Printing `raw`/`narrowed` at the object-literal member showed
    `ZzzProj | undefined -> ZzzProj` for the free-function RHS and `-> ZzzProj | undefined`
    for the `this` one, which located the defect in the walk rather than in the reader.
  * **The (CHK.61)(1) revert note's diagnosis was inherited and wrong**, exactly like two of
    (CHK.62)'s four. "An acceptance in the relation feeds `typeGuardMemberDisjoint`" reads as
    a statement about someone else's code; the rule was simply unsound. **Censusing what a
    new rule ACCEPTS is a one-run instrument and it should have been the first move**, not
    the last.
  * **A KDoc-only edit was proven inert rather than re-gated**: `javap -c -p` minus `line N:`
    is byte-identical across it over 1,026,164 lines of disassembly, while the class md5
    moves (`da1d4552` -> `e7963e28`) because the LineNumberTable shifts.

### Round (CHK.62) — THREE of (CHK.61)'s four unmasked gaps are CLOSED, and **two of its four diagnoses were wrong**; (a)'s price falls **6 rows -> 3**, and the last row's true cause is now located

**THE HEADLINE IS THE RE-PRICING.** With gaps 3 and 4 closed, the `this`-receiver patch
`build/chk61/patch_a.py` — the one line that closes **every** row (CHK.60) measured — costs
**3** dashboard rows instead of 6 (grid `9c01ade7819f33aa30f5f7fb5a987e63`, per-profile
`+2 harness / +1 server`, everything else `added=0 removed=0`). The three are **two** causes:
`client.ts:356` (gap 1, the un-merged intersection source, still open) and
`editorServices.ts:4449` on two profiles, whose cause is now located and is NOT what the
predecessor named.

**TWO OF THE FOUR DIAGNOSES WERE WRONG, AND BOTH FAILED THE SAME WAY: the named axis was a
property the repro HAPPENED to have.** Gap 3 was recorded as "a function-type PROPERTY's
parameters are compared covariantly, the METHOD form is already correct". Our general
parameter contravariance is CORRECT (`(x: string) => void` is not assignable to
`(x: string | undefined) => void`, byte-identical to tsc 7.0.2), and the method form fails
identically once the shape is written out. The axis is **OPTIONALITY**: an optional source
parameter's type is `T | undefined` (tsc's `addOptionality`) and we modelled it as `T`, so
the target's `undefined` constituent had nowhere to go. Gap 2 was recorded as "a SHORTHAND
object-literal property does not flow-narrow"; the shorthand is innocent —
`{ zzzProj: zzzProj }` fails identically and so does a plain `const x: T = zzzProj` after the
same switch. **Re-bisect a handed-down diagnosis before designing its fix**; each of these
took two 20-second CLI runs to overturn and would have cost a wrong fix each.

**(CHK.62) gap 4 — AN OBJECT LITERAL THAT SPREADS AN INTERSECTION LOST EVERY MEMBER.**
`spreadGuaranteedProps` handled `Type.Union` and `Type.Object` and answered `emptyMap()` for
`Type.Intersection`, so `{ ...mk(), insertString }` with
`mk(): FileLocationRequestArgs & { endLine: number; endOffset: number }` (harness
`client.ts:242`) reported TS2739 for the five properties the spread does supply. Dual of the
union arm: a union guarantees what EVERY constituent has, an intersection what ANY does.
`SpreadOfIntersectionTest`; the negative control pins tsc 7.0.2's TS2741 message
byte-for-byte, whose SOURCE display names those five members.

**(CHK.62) gap 3 — AN OPTIONAL SOURCE PARAMETER IS `T | undefined` IN THE CONTRAVARIANT
TEST.** `signatureRelatedTo` now widens it. Measured over a six-cell matrix against tsc
7.0.2: five cells now agree exactly. Deliberately SOURCE-side only — tsc's model widens the
TARGET parameter too, which makes `(x: string) => void` NOT assignable to `(x?: string) =>
void`; tsc reports that and we are still silent. That half is a REJECTION change, is
RESIDUE, and is not pinned (round 765). `OptionalParamContravarianceTest`.

**(CHK.62) gap 2 — A CALL TO A `: never` FUNCTION DIVERGES; THE ROW IT WAS SUPPOSED TO CLOSE
IS STILL OPEN, AND THAT IS THE ROUND'S SECOND FINDING.** `Debug.assertNever(kind);` in a
switch's `default:` fell THROUGH into the post-switch merge, so the merge re-introduced the
pre-switch `T | undefined` and every narrow the other clauses established was lost. Fixed in
both narrowing walkers (fast-forward loop AND recursive arm); tsgo agrees on every repro
(`build/chk62/g2g`, `g2h`). **But re-pricing (a) shows `editorServices.ts:4449` UNMOVED.**
Bisecting on the real shape with (a) in the tree found the actual cause, and it is a third,
`this`-mediated gap: **an assignment whose RHS is a `this`-METHOD CALL does not narrow the
assigned reference** — `let p = this.find(); p ??= this.create(); return { p }` reports
`p: ZzzProj | undefined` where tsc is silent, with NO switch in it at all
(`build/chk62/g2k`, functions `zzzC`/`zzzD`; the free-function RHS `zzzA` is silent and is
the control). It is invisible without (a) because `this.create()` types `any` today. Queued.

**THE PERF DESIGN OF `flowCallDiverges` IS THE THIRD FINDING, AND IT IS THREE MEASURED
GATES, NOT ONE.** Resolving the callee on every flow call reads `typeOfExpr.calls`
**+9.61%** and `globals.lookups` **+4.43%** — COST GATE FAILED — because
`callHasNeverReturnAnnotation` reaches `resolvePropertyMethodDecl`, which **TYPES THE
RECEIVER**: the round-385 hazard `flowCalleeMayHaveAssertEffects` was written to avoid, one
predicate over. (i) requiring the parent to be an `ExpressionStatement` (a diverging call
cannot be in value position) -> +3.19% / +2.22%; (ii) using the symbol-table-only
`resolveNamespaceMemberFnDecl` instead -> `typeOfExpr.calls` **+1.42%, digit-identical to
standing**, `globals.lookups` +2.14% (still failing); (iii) pre-gating the namespace RECEIVER
on `currentFileLocals` (one map probe; a receiver that can name a `: never` function is an
import or a file-level declaration, a parameter is not) -> `globals.lookups`
**776189 -> 771524, +1.52%**, gate PASSES. A per-request memo keyed by `nodeKey` measured
**ZERO** — each such call is asked about once per compile, so the cost was never repetition.

**GATES, per commit, all foreground, one at a time.** Suite **16,247 / 16,252 / 16,257**,
0 failed, 3 skipped — +4/+5/+5, exactly the three new classes; **no corpus baseline moved on
any of the three**. `cost_gate.py` PASSES on all three (exit 0 read from the gate, not from a
pipeline — see the gotcha); `output.errors` **46** throughout; `typeOfExpr.calls`,
`narrow.walks`, `narrow.memoServed`, `spine.nodes` digit-identical to (CHK.61c/d)'s reading
on all three. `huge_methods --fail-over 0` exit 0, **783** classes, 0 over, on all three.
**8-profile grid `503774c23b4535130ffdebabef430cf0` on all three** — the standing value,
per-profile `added=0 removed=0` on all eight. `knip` **48** and `jsonrepair` **4**, EVERY ROW
byte-identical on all three. `partition-equivalence` EQUIVALENT all 78 (floors 59 / 69 / 75
ms, one draw each — the spread is the harness's own, not the change's).

**`capture-equivalence` MOVED BOTH ARM DIGESTS ON THE GAP-2 COMMIT AND THAT IS EXPECTED.**
`full=-7560141526203174980 narrow=-5179824964953234569` (was `-3735929574989657502` /
`-2075467818767010709`), with **DIVERGED 1,005 span(s) in 43 of 76, types=1005
definitions=0, moreAny 0, definitions 360,376 — every one unchanged**. Commits 1 and 2
reproduced the OLD digests exactly, so the move is attributable to the diverging-call fix
alone: it is a FULL-BUILD fix, so captured types after such a switch now render the narrowed
type in BOTH arms and the full-vs-narrow relationship is untouched — (INC.26)'s rule,
re-recorded rather than read as a regression.

**FOUR ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT, NO ZEROS.**

| arm | injected mistake | class | RED |
|---|---|---|---|
| a0 | the `Type.Intersection` arm of `spreadGuaranteedProps` reverted | `8b85adc4` | **2** — the gap-4 positive and its negative control |
| a1 | the optional-source-parameter widening reverted | `d6a568d0` | **3** — exactly the gap-3 positives |
| a2 | `flowCallDiverges` forced false | `d6e9f431` | **3** — exactly the gap-2 positives |
| a3 | only the namespace-member arm of `flowCallDiverges` off | `957b605e` | **1** — uniquely the `Debug.assertNever` positive |

Shipped classes `3fb8f92b` / `92f930ec` / `181c293e`. **The `currentFileLocals` pre-gate has
NO ablation arm and is not claimed as coverage**: it is a PERF guard with no observable
behaviour, so every pin is green with and without it — it is graded by `cost_gate.py`, and
both readings (+2.14% without, +1.52% with) are recorded above. Every arm was `cmp`-diffed
against its OWN snapshot before the build, and each restore was verified by `cmp` plus a
rebuilt class md5 equal to the pre-ablation one.

**HOW VACUITY WAS RULED OUT, PER PIN.** Every positive was first reproduced on the PARENT
binary through the CLI against `tools/tsgo-7.0.2/lib/tsc` on the same fixture, then shown
RED under the arm that names its rule. The gap-4 negative control was WRITTEN WRONG first
(it asserted TS2739/TS2740 and the shipped answer is TS2741) and the failure is what
produced the byte-exact message assertion. Two controls are labelled CONTROLS and not
coverage because no arm reddens them: the plain-interface and plain-object spreads (gap 4)
and the three already-relating variance directions (gap 3). The gap-2 controls DO
discriminate — a rule that treated any statement call as diverging silences them — which is
why they assert `rows.size == 1` and the `ZzzProj | undefined` text rather than a bare
`none`.

**WHAT DID NOT WORK, AND WHAT SURPRISED ME.**

  * **Four hand-written repros of the four gaps were all VACUOUS on the first attempt** —
    every one compiled clean on both compilers. CLAUDE.md's "a hand-written approximation of
    a tsc shape passes vacuously" cost four cycles here; what worked every time was reading
    the REAL declarations out of `build/bench/tsc-*/src` and deleting ingredients.
  * **`python3 scripts/cost_gate.py 2>&1 | grep …; echo "exit=$?"` reads the GREP's status**,
    so a FAILING gate prints a plausible table and `exit=0`. Two of this round's readings
    were taken that way before it was noticed; both were genuinely passing (no
    `ROSE beyond` lines), and the third was failing. Redirect to a file and read `$?` from
    the gate itself.
  * **The per-request memo I added to make `flowCallDiverges` cheap measured EXACTLY zero**
    (776825 vs 776826 lookups) — the population is one ask per call per compile, so there
    was nothing to memoize. It is kept because it is free and bounds a pathological input,
    but it is not what made the gate pass.
  * **Gap 2's fix is right and does not close the row it was written for.** That is the
    round's most transferable shape: a queue entry that names a row AND a mechanism can be
    right about the mechanism, right about it being a defect, and wrong that the two are the
    same thing — the only instrument that says so is re-pricing the unmasking patch.


### Round (CHK.61c)+(CHK.61d) — the two defects (CHK.61) named were BUILT, PRICED and REFUSED for the same reason, and the pricing turned up **two other defects that were free to fix**

**BOTH QUEUED ITEMS WORK, AND NEITHER IS LANDABLE.** (a) — take `currentClassForThis` as the
receiver type when `this` types `any` — is ONE line and closes **every** row (CHK.60)
measured: `build/chk60/br/b2.ts` goes from 3 of tsc's 7 rows to all 7. (b) — `| undefined`
at `computeRawTypeOfPropertyAccess`'s `prop != null` returns — is one line and produces
tsc's exact `'number | undefined'` text. Both were built, both were run over the 8-profile
grid, both libraries and the whole suite. **Each ADDS false positives to the dashboard**,
and the FPs are not theirs: they are pre-existing engine gaps the `any` was masking. Under
this arc's own convention ((CHK.51) measured a firewall as "worth 43 rows" and kept it;
(INC.42) narrowed rather than shipping dashboard FPs) that is a refusal — so what landed is
the two defects the pricing turned up on the way, each of which is free.

**(CHK.61c) A TYPE REFERENCE INSIDE A `namespace` BODY RESOLVED THE *OUTER* SCOPE FIRST.**
`getTypeFromTypeReference` asked `resolveTypeNameToSymbol` first and the enclosing namespace
only as a FALLBACK, so a namespace member whose name ALSO exists globally resolved to the
outer declaration — the opposite of TypeScript's rule, and silent in the dangerous
direction: the outer type is a REAL type, so the annotation is judged against the wrong
shape rather than against none. Four lines reproduce it with no `this` in them
(`build/chk61/p6/a.ts`). **It was found as the CAUSE of (a)'s only corpus regression**:
`variableDeclaratorResolvedDuringContextualTyping` gained `Type 'Promise<T>' is missing …
from type 'TPromise<IUploadResult>': done, cancel` because `namespace WinJS { declare class
Promise { then(): Promise } }` resolved `Promise` to the LIB `Promise<T>`. That case's
PRISTINE baseline reports nothing at that line, so the pristine oracle — not tsgo — is what
settles this one. With it fixed, (a)'s corpus cost is **ZERO**.

**(CHK.61d) `f!()` DISCARDED THE ASSERTION AT BOTH SITES THAT CLASSIFY A CALLEE.**
`getCalleeType`'s `is NonNullExpression -> getCalleeType(expr.expression)` and
`getReturnTypeOfCallExpression`'s unwrap loop both drop the `!`, so a `T | undefined` callee
arrives as a UNION and fails in two directions at once: TS2349 where tsc is silent, AND
`if (calleeType !is Type.Object) return anyType`, so the call's RETURN TYPE was never
resolved and every check over the result was silently vacuous — `const s: string = f!()`
reported NOTHING. **This is the gate on (b)**: with (b) applied and this present the
compiler profile gains **19** rows of which **17** are this class (`host.readDirectory!(…)`,
`resolutionHost.realpath!(…)`, `host.writeFile!(…)` — tsc's own sources use the idiom
everywhere); with it fixed, (b)'s cost is **3**. Nobody would have found it from its own
shape; it fell out of pricing (b).

**THE PRICED MAP OF (a): 6 ROWS, 4 CAUSES, EVERY ONE `this`-FREE.** Compiler profile
**46 -> 46**, corpus GREEN, `knip` 48 and `jsonrepair` 4 **byte-identical**; +4 harness,
+2 server. The causes are an INTERSECTION SOURCE that is never merged (`client.ts:356`), a
SHORTHAND object-literal property that does not flow-narrow (`editorServices.ts:4449`), a
FUNCTION-TYPE property whose parameters are compared covariantly where the METHOD form is
already bivariant (`project.ts:2277`), and an object literal that loses a SPREAD's members
(`client.ts:242`). All four are in the queue with repros.

**AN ACCEPTANCE LEG FOR THE FIRST OF THOSE WAS BUILT AND REVERTED, AND ITS FAILURE IS THE
ROUND'S MOST TRANSFERABLE FINDING.** `intersectionMergedSatisfiesTarget` — the symmetric
twin of the existing `intersectionMergedContradictsTarget`, consulted only AFTER "some
constituent relates" has already answered false — closes `client.ts:356` exactly, and ADDS
`callHierarchy.ts:199 'parent' does not exist on type 'never'` on two profiles. **An
acceptance in the relation is NOT diagnostic-free: it feeds `typeGuardMemberDisjoint`, so a
type that now relates gets SUBTRACTED by a narrowing and the reference collapses to
`never`.** Round 784's "a second chance can only turn a rejection into an acceptance, so
nothing that already passed can start failing" is true of the RELATION and false of the
COMPILER.

**THE PRICED MAP OF (b): 3 ROWS ON THE COMPILER PROFILE AFTER (CHK.61d), ALL NARROWING.**
`checker.ts:30269:86` (an `&&` chain `a && x.p !== undefined && x.p < 0`),
`moduleNameResolver.ts:824` (an outer `if (host.f && host.g)` surviving into a nested `for`
+ inner `if`), `moduleNameResolver.ts:2265` (a three-deep chain), plus `project.ts:502`/`528`
and `vfsUtil.ts:1034` off-profile. Every one is a property-access reference, so they may
share a cause. Two shapes are already CORRECT and need nothing: `?.` and an `if (o.p)`
guard. **And (b)'s tempting display-only confinement is NOT free** — hover for
`zzzInst.zzzOpt` says `number` where tsc says `number | undefined`, but adding the
constituent in the capture path alone renders `number | undefined` INSIDE an
`if (zzzInst.zzzOpt)` guard too, because `getTypeOfPropertyAccess` narrows only a type that
is ALREADY a union.

**GATES, all foreground, one at a time.** Suite **16,243 / 0 / 3** (+9, exactly the two new
classes), **no corpus baseline moved**. `cost_gate.py` `output.errors` **46** and all 20
counters digit-identical to (CHK.60)'s reading (the standing `typeOfExpr.calls +1.42%` is
inherited drift against a baseline last recorded at (CHK.46)). `huge_methods --fail-over 0`
exit 0, **783** classes, 0 over. **8-profile grid md5
`503774c23b4535130ffdebabef430cf0`**, per-profile diff **`added=0 removed=0` on all eight**
— the standing value, unmoved since (CHK.54). `partition-equivalence` **EQUIVALENT, all
78**, floor **56 ms** [55, 56, 57, 54] — one draw. `capture-equivalence` **1,005 span(s) in
43 of 76, types=1005 definitions=0, moreAny 0**, `definitions` **360,376**, ARM DIGESTs
`full=-3735929574989657502 narrow=-2075467818767010709` — the standing state to the digit.
**`knip` @ `dc7aca5` 48 and `jsonrepair` 3.13.1 4, EVERY ROW BYTE-IDENTICAL.** Shipped
binary md5 `92799538`, reproduced before and after the ablation sweep; parent `854a0209`.

**FIVE ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT, NO ZEROS IN THE FINAL
SWEEP.**

| arm | injected mistake | class | RED |
|---|---|---|---|
| a0 | both changes reverted (the parent, rebuilt this session) | `854a0209` | **5** — every positive |
| a1 | the namespace reorder reverted | `9384f409` | **2** — uniquely the namespace positives |
| a2 | `getCalleeType`'s NonNull arm reverted | `21555705` | **1** — uniquely the callability positive |
| a3 | the return-type restore reverted | `e40c15de` | **2** — uniquely the return-type positives |
| a4 | the `SymbolFlags.Type` filter dropped | `56d5c06a` | **1** — uniquely the value-export control |

**TWO DRAFTS OF ONE PIN WERE BLIND AND a1 IS WHAT SAID SO.** Asserting the ABSENCE of
`TS2339: Property 'zzzB' does not exist` reads GREEN against the ablated binary in BOTH
shapes of the shadowed global — written GENERIC the wrong resolution answers `errorType`
through TS2314 and no member is ever looked up; written NON-generic the member lookup is
still not what reports. Only a DIFFERING RETURN TYPE discriminates, so the two `zzzA`
declarations disagree on purpose (`number` in the namespace, `boolean` globally).
**AND THE FIRST a4 WAS A DEAD ARM**: injecting the namespace lookup ABOVE
`currentTypeParamScope` read 0 RED, because a class's own type parameter does not reach the
checker through that map at all — so the type-parameter control has NO discriminating arm
and is not claimed as coverage. The a4 that shipped drops the `SymbolFlags.Type` filter,
which IS reachable and reddens exactly one pin, making the value-export control
load-bearing.

**WHAT DID NOT WORK, AND WHAT SURPRISED ME.**

  * **The dashboard's FP metric and the "close a missing-diagnostic hole" goal are in
    tension by construction.** tsc's own sources compile with ZERO errors, so a NEW correct
    check can never REMOVE a row from a profile — it can only surface our own gaps. Every
    unmasking fix therefore reads as a dashboard regression, whatever its correctness value.
  * **The acceptance-only argument (above) is the single most surprising result** — I wrote
    "can only turn a rejection into an acceptance, so it cannot introduce a diagnostic" into
    the KDoc before building it, and the grid refuted it in one run.
  * **The `this` hover already works**, so (a) is a DIAGNOSTICS defect and not a
    language-service one: `typeCapturePropertyAccessType` has carried a `this`/`super` leg
    since (BUG.4). What DOES lie in hover is (b) — an optional member renders without its
    `| undefined`.
  * **A scripted PLAN-PHASE-5.md trim keyed on "the next `### ` heading" DESTROYED THE WHOLE
    QUEUE** (441 KB -> 106 KB) — the oldest live note is the LAST `###` in the file and the
    queue below it carries no heading of its own, so the slice ran to EOF. Recovered by
    `git checkout --` because the file was committed. CLAUDE.md's `open(p,'w').write(<expr>)`
    entry now has a sibling.

### Round (CHK.60) — an enum member is a string or number **LITERAL** in tsc, so its apparent type is the wrapper: **13** false positives removed; and item 2's blast radius mapped and found to be neither the weak rule nor optionality

**THE FP, AND WHY IT IS NOT THE WEAK RULE.** tsc's `TypeFlags.StringLike` is
`String | StringLiteral | TemplateLiteral | StringMapping`; an enum literal type carries
`StringLiteral | EnumLiteral`, so `getApparentType(E.A)` is `globalStringType`, and a
numeric member carries `NumberLiteral | EnumLiteral` and answers `globalNumberType`
(`checker.ts:15542`, read on the box). (REL.1)(b) mints a member-LESS `Type.Object` here
instead, and `propertiesRelatedTo`'s `source.members == null` arm answers
`targetProps.isEmpty()` — which rejects **every** target declaring a property, including an
all-optional one. The weak walker correctly DECLINES a target the source shares a property
with, so what emitted was the ordinary relation, exactly as the (CHK.59) entry said.

**THE FIX IS A RETRY, NOT A WIDENING.** `structuredTypeRelatedTo`'s object/object leg was
`return objectTypeRelatedTo(...)`; it is now "if that says true, true — otherwise, for an
enum-literal source, retry as the apparent PRIMITIVE". Retrying as the PRIMITIVE rather
than reaching for a wrapper here is the whole design: it routes the source through exactly
the legs a `string`/`number` source already takes — B69.8's wrapper/named-interface leg,
round 430's empty-`{}` rule, B418's index-signature rule and (CHK.32)'s anonymous-object
leg — each with its own measured guards intact. That is why a NAMED interface, a class with
an optional member, `Object` and the `String` wrapper itself all come right for free, and
why the INDEX-SIGNATURE target's answer is untouched (`objectTypeRelatedTo` accepts it
before the retry is reached, and would accept it after — measured, see arm a5).

**THE 30-ROW MATRIX IS THE INSTRUMENT** (`build/chk60/mx/m1.ts`, `m2.ts`; both arms
`tools/tsgo-7.0.2/lib/tsc --noEmit --strict --target es2020 --lib es2020`). 13 ours-only
rows removed. What it also settled, none of which reading the code produces:

  * the boundary is per **MEMBER**, not per enum — tsc accepts a MIXED enum's string
    member against `{ length?: … }` and its numeric member against `{ toFixed?() }`, and
    rejects the WHOLE mixed enum against both;
  * a **required** target member is accepted too (`{ length: number }`), which is the row
    that separates "the weak walker declined" from "the relation accepts";
  * an **index-signature** target REJECTS in tsc (`m1.ts(19,6)`) where we accept, and a
    **whole enum** source against a target sharing nothing reports TS2559 in tsc
    (`m1.ts(25,6)`, `m1.ts(29,6)`) where we are silent — three standing FALSE NEGATIVES
    this round does not touch and does not worsen. The whole-enum one is (REL.1)(b)'s
    vacuity: a member-less source against a member-less target passes both ways.

**NO PRISTINE BASELINE CARRIES THE SHAPE.** Every `.errors.txt` mentioning TS2559 was
searched and none has an enum member as an object-target source, so tsgo is the only
oracle — which is acceptable here precisely because the answer being asserted is
*nothing*, and wording cannot diverge from silence. The pristine oracle WAS consulted and
did settle one thing: `enumAssignmentCompat5` pins the enum as a TARGET, not a source, so
nothing in the corpus was at risk.

**GATES.** Suite **16,234 / 0 / 3** (+11, exactly the one new class), **no corpus baseline
moved**. `cost_gate.py` `output.errors` **46**; the run was repeated against the a0 parent
binary via `--from-log` and **all 20 counters are DIGIT-IDENTICAL** — the standing
`typeOfExpr.calls +1.42%` is inherited drift against a baseline last recorded at (CHK.46),
not this round's cost. `huge_methods --fail-over 0` exit 0, **783** classes, 0 over.
**8-profile grid** md5 **`503774c23b4535130ffdebabef430cf0`** and per-profile `diff` clean
against the rebuilt parent: **`added=0 removed=0` on all eight**. `partition-equivalence`
**EQUIVALENT, all 78**, floor **56 ms** [54, 59, 52, 56] — one draw.
`capture-equivalence` **1,005 span(s) in 43 of 76, types=1005 definitions=0, moreAny 0**,
`definitions` **360,376**, ARM DIGESTs `full=-3735929574989657502
narrow=-2075467818767010709` — the standing state to the digit, so no captured type moved.
**`knip` @ `dc7aca5` 48 -> 48 and `jsonrepair` 3.13.1 4 -> 4, EVERY ROW BYTE-IDENTICAL**
against an a0 arm rebuilt in this session. Binary md5 `96e0db8d` reproduced three times
(first build, post-a0 rebuild, post-a5 restore); parent `089f7b6f`.

**SIX ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT, each `cmp`-diffed against
the driver's own snapshot, each anchor asserted to occur exactly once (exit 3 otherwise),
each build grepped for BUILD SUCCESSFUL, the restore verified OUTSIDE the driver by
`git status --porcelain` + an md5 compare.**

| arm | injected mistake | class | RED |
|---|---|---|---|
| a0 | the whole change reverted (the parent, rebuilt this session) | `089f7b6f` | **6** — exactly the six positives |
| a1 | the IDENTITY guard dropped | `e1f48932` | **0** |
| a2 | positive evidence dropped (an UNEVALUATED member defaults to numeric) | `a75f1f59` | **0** |
| a3 | the (CHK.32) leg's anonymous-target scope wrongly inherited | `f5d160d4` | **2** — uniquely the named-interface and String-wrapper pins |
| a4 | the flavour collapsed to `string` | `c0ee90aa` | **1** — uniquely the numeric-vs-`String` control |
| a5 | the retry promoted from a FALLBACK to a SUBSTITUTION | `baaf0e2d` | **0** |

**a4 IS THE ARM WORTH READING**: it reddens ONE pin and **not** the two numeric positives,
because an ALL-OPTIONAL target cannot discriminate a flavour (`String` simply lacks
`toFixed`, and an optional target member a source lacks is legal). That is what makes the
two wrapper CONTROLS load-bearing rather than decorative, and it is why the positives alone
would have been a blind pin set for the flavour rule.

**THE THREE ZEROS ARE THREE DIFFERENT KINDS.**

  * **a1 — UNDISCRIMINATED, NOT REDUNDANT.** Under `identityRelation` the retry can only
    reach a target the structural comparison already refused, and `stringType` is an
    `Type.Intrinsic`; the route by which it WOULD matter is B69.8's wrapper arm, which is
    not relation-gated — so dropping the guard would let an enum member be declared
    IDENTICAL to `String`. Nothing in 16,234 tests asks. Kept because tsc guards the same
    work the same way (`structuredTypeRelatedTo`'s `relation !== identityRelation`).
  * **a2 — A DELIBERATE REFUSAL, AND THE ONE PLACE THIS ROUND CHOSE THE CONSERVATIVE
    ANSWER.** Defaulting an unevaluated member to numeric (what `isNumericEnumObjectType`
    does for its arithmetic caller) reddens nothing AND would fix one further measured FP:
    `enum E { A = zzzNonConst }` against `{ toFixed?() }` is silent in tsc and TS2345 here
    (`build/chk60/ue/u2.ts(6,8)`). It was refused because the neighbouring probe shows the
    hazard is real and not hypothetical: an enum whose first member is a TEMPLATE
    literal and whose second is a plain string (`build/chk60/ue/u3.ts`) — a
    TEMPLATE-valued string member — does not fold in our evaluator, so a numeric default
    would relate a STRING member to `Number`-shaped targets. That is a false NEGATIVE, the
    direction with no gate. Both rows re-queued.
  * **a5 — INDISTINGUISHABLE FROM REDUNDANT ON TODAY'S EVIDENCE.** 0 RED in the suite, and
    re-run against the 30-row matrix the output is BYTE-IDENTICAL to HEAD. The prediction
    written before building it — that the substitution order would flip the
    index-signature row `m1.ts(19,6)` — is **REFUTED**: `checkTypeRelatedTo(string,
    { [k: string]: any })` accepts by some route above B418. So "a fallback, not a
    substitution" is a claim about blast radius that this repo currently cannot
    demonstrate; it is kept as the strictly weaker change and recorded as undemonstrated.

**ITEM 2 (`this.<member>`) WAS MAPPED, NOT FIXED — AND THE QUEUE ENTRY'S DIAGNOSIS IS RIGHT
AT THE ROOT AND WRONG ABOUT THE SURFACE.** `this` is `Identifier("this")` in this parser
(`Checker.kt:8090`), so `getTypeOfExpression` types it `any` and every `this.<member>` with
it. But it is **not about optionality and not about the weak rule**: measured over
`build/chk60/br/b1.ts` and `b2.ts` against tsc 7.0.2, a **REQUIRED** `this.zzzReq: number`
assigned to a `string` variable is silent too. The hole is per POSITION:

  * **SILENT**: a var-decl initializer (`const s: string = this.zzzReq`), an assignment RHS
    (`s = this.zzzReq`), a `return` from an annotated method, and the nullish-access checks
    (TS2532 for `this.zzzOpt.toFixed()`, and the whole `string | undefined -> string` row);
  * **PRESENT**: ARGUMENT position (`zzzTake(this.zzzHandler)` reports) and TS2722.

The controls that make that a measurement rather than a guess are in the same method body:
a plain outer name and `zzzObj.zzzReq` both report from inside the method, and
`zzzInst8.zzzReq` reports at file level — so it is the RECEIVER, not the position in a
method.

**AND THE SAME FIXTURE ISOLATED A SECOND DEFECT THAT IS NOT `this`-SPECIFIC**: an OPTIONAL
property's `| undefined` is dropped for ANY receiver. `zzzInst.zzzOpt` reports
`Type 'number'` where tsc says `'number | undefined'`, and `zzzInst.zzzOptStr` against a
`string` target goes MISSING entirely because `string -> string` then passes. Four rows on
an ordinary receiver, so a `this` fix alone would not close it.

**SCOPED OUT, DELIBERATELY**, each already refused with a reason by (CHK.59) and untouched
here: two-or-more non-nullish constituents, the TS2353 argument boundary, the generic
instantiation source (symmetric by design), and the `p?: any | undefined` display residue.
The bigint leaf (item 7) was also not taken.

### Round (CHK.59) — the weak-type anchor moves to the **EXPRESSION** exactly when the code is **TS2560**; an enum member is a source at every position, and the cause was the vacuous-`{}` guard; and a fresh object literal elaborates **into** the literal

**THREE FIXES, ONE ARC, THREE COMMITS.** (CHK.58) left five measured residue items with a
fixture each. Three are closed; two are scoped out with reasons, and three NEW residues were
measured on the way.

**1. THE CALLABLE SOURCE AT THE THREE NON-ARGUMENT POSITIONS — the largest piece, and the
only thing left was the ANCHOR.** Read out of tsc 7.0.2 and corroborated by tsgo's own
`relater.go`: `checkTypeRelatedToAndOptionallyElaborate` runs `elaborateError` first, whose
first act is `elaborateDidYouMeanToCallOrConstruct` (construct signatures, then call ones).
When some signature's return type is related to the target it RE-REPORTS with the error node
set to the EXPRESSION and attaches the TS6213/TS6212 related row; otherwise the ordinary
`checkTypeRelatedToEx` runs with the position's own error node. **That predicate is the SAME
one [Checker.weakCallResultSatisfiesTarget] already used to choose TS2560 over TS2559**, so
the two coincide *by construction* in this compiler and the emitter needed exactly one extra,
CALL-ONLY anchor pair.

| source | tsc code | var decl | return | assignment |
|---|---|---|---|---|
| `() => { zzzT: number }` (result related) | TS2560 | 3:22 EXPR | 4:34 EXPR | 6:9 EXPR |
| `() => { zzzZ: string }` (result disjoint) | TS2559 | 3:7 NAME | 4:27 `return` | 6:1 LHS |
| `() => number` | TS2559 | 8:7 NAME | 9:27 `return` | 11:1 LHS |
| `new (s: string) => { zzzT: number }` | TS2560 | 13:22 EXPR | 14:34 EXPR | 16:9 EXPR |
| `() => ({ zzzT: 1 })` (an ARROW) | TS2560 | 2:22 EXPR | 3:34 EXPR | 5:9 EXPR |

**TWO FURTHER HOLES FELL OUT OF THE SAME CHANGE.** [Checker.topLevelWeakSource] classifies a
cast, an enum member, a `new` and a primitive literal and nothing else, so an ordinary
IDENTIFIER or ARROW source was silent at the VAR DECL while the return and assignment
positions reported it; the var-decl walker now falls back to the shared value walker as its
`?:`, which changes no shape that already reports (that branch renders the target through
the ANNOTATION, the walker through `typeToString`). And the assignment site's outer
`ArrowFunction` refusal comes out.

**A FUNCTION EXPRESSION STAYS REFUSED, AND THAT IS MEASURED.** tsc's `getErrorSpanForNode`
maps a `FunctionExpression` to its own NAME, so `= function zzzNamed(){}` anchors at
`zzzNamed` (col 31) and the anonymous form lands back on the var name (col 7) — two anchors,
neither of which is the expression. An `ArrowFunction` has no such mapping
(`getErrorSpanForArrowFunction` starts at its first token), which is why the arrow half IS
closed.

**2. AN ENUM MEMBER IS A SOURCE AT EVERY POSITION — AND (CHK.58) HAD THE MECHANISM WRONG.**
Its entry said [Checker.getTypeOfExpression] answers `any` for `E.A`. It does not: the type
resolves fine and the pre-existing TS2345/TS2322 rows name it `ZzzE.A`. The refusal is one
step further on — an enum-flavoured type is a member-LESS [Type.Object], so
[Checker.weakSourcePropertyNames] enumerates it to the EMPTY set and
[Checker.tryEmitWeakTypeAssignment]'s vacuous-`{}` guard (`var x: AllOptional = {}` is legal)
refused it. tsc compares the enum LITERAL type's apparent type, i.e. the `String`/`Number`
wrapper's property set, which is what the var-decl walker's AST-side
[Checker.enumMemberWeakSource] had been reproducing all along. **Consulting that classifier
AT THAT GUARD** — after the target is known weak and the source has come back empty or
unenumerable — is the cheapest possible placement (nothing else reaches it: `globals.lookups`
**+4** for the whole compiler profile) and closes the argument, return and assignment
positions in one place. The DISPLAY rule is (CHK.58)'s, unchanged.

**3. A FRESH OBJECT LITERAL ELABORATES *INTO* THE LITERAL — TWO DEFECTS IN ONE SHAPE.**
(a) ORDER: [Checker.tryEmitNestedWeakVarDecl] (one level, TS2322 at the var NAME with a
`Types of property … are incompatible` chain) ran BEFORE
[Checker.tryEmitObjectLiteralWeakLeaves] (TS2559 at the property KEY + TS6500), so a fresh
literal took the wrong walker; the two overlap ONLY for an object-literal initializer, since
the nested one additionally serves an IDENTIFIER source (pristine's
`let weak: Weak & Spoiler = propertiesWrong`) the leaf walker can never reach. (b) DISPLAY:
a leaf reports the literal's OWN property type, i.e. the **WIDENED** one for a string or
numeric literal and the literal itself for a boolean — `"utf8"` -> `string`, `12` ->
`number`, a template literal -> `string`, `false` -> `false`, an enum member -> `ZzzEL.A`.
**THE TOP-LEVEL VAR-DECL POSITION DOES NOT WIDEN** (pristine's
`nestedExcessPropertyChecking.errors.txt` line 18 reports `Type '"A"'`), because there the
fresh literal reaches the relation directly — that asymmetry is why the widening lives in
the leaf walker and nowhere else, and lines 30/40 (`Type 'false'`) gate the boolean half.

**GATES.** Suite **16,223 / 0 / 3** (+24 over (CHK.58)'s 16,199: three new classes plus one
residue pin), summed with `xml.etree` over the SEVEN-module glob (core 15,239 + project 704
+ kir 146 + daemon 66 + api 30 + client 20 + cli 18); **no corpus baseline moved at any of
the three steps**, which is load-bearing for step 3 — the leaf/nested order swap is exactly
what `nestedExcessPropertyChecking` and `weakType` gate. `cost_gate.py` exit 0
unrebaselined, `output.errors` **46**, largest counter **+1.42%** against a baseline
(CHK.58) already read **+1.40%** on, i.e. **+0.02% for this whole round** — the ordering
(cheap AST refusals -> target-weakness gate -> type the value) is intact and the enum consult
sits behind the weak-target gate by construction. `huge_methods --fail-over 0` exit 0,
**783** classes, 0 over. **8-profile grid** md5 **`503774c23b4535130ffdebabef430cf0`** after
every step and per-profile `diff` clean: **`added=0 removed=0` on all eight**, unmoved since
(CHK.54). `partition-equivalence` **EQUIVALENT, all 78**, floor **64 ms** [56, 63, 64, 66] —
one draw. `capture-equivalence` **1,005 span(s) in 43 of 76, types=1005 definitions=0,
moreAny 0**, `definitions` **360,376**, ARM DIGESTs `full=-3735929574989657502
narrow=-2075467818767010709` — the standing state. **`knip` @ `dc7aca5` 48 -> 48 and
`jsonrepair` 3.13.1 4 -> 4, EVERY ROW BYTE-IDENTICAL** to an a0 arm rebuilt in this session;
that arm's `Checker.class` md5 `58e55fae…` reproduced the session's very first build exactly.

**TEN ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT AND EVERY RED SET DISTINCT —
AND NOT ONE ARM READ 0.** Each arm `cmp`-diffed against its OWN snapshot, each anchor
asserted to occur exactly once (exit 3 otherwise), each build grepped for `e:`, restore
verified OUTSIDE the driver's `finally` plus a `git diff --quiet`, binary rebuilt after every
restore.

| arm | injected mistake | class | RED |
|---|---|---|---|
| a0 | the whole change reverted (the parent, rebuilt this session) | `58e55fae` | **21** — exactly the 21 positives added or converted |
| a1 | the 2560 anchor never moves (`useCall = false`) | `43180b25` | **4** — uniquely the four expression-anchor pins |
| a2 | the `FunctionExpression` refusal dropped | `9a5b1b87` | **1** — uniquely its refusal pin |
| a3 | the var-decl fallback to the shared value walker dropped | `e19a390c` | **6** — every var-decl row |
| a4 | the assignment site's `ArrowFunction` refusal restored | `1128de84` | **3** |
| a5 | the enum consult at the vacuous-`{}` guard dropped | `3f8df9eb` | **8** — every enum pin |
| a6 | the enum DISPLAY override dropped | `90c8eefd` | **2** — uniquely the one-member pins |
| a7 | the leaf/nested walker order reverted | `8895315f` | **5** — every leaf pin |
| a8 | the leaf literal WIDENING dropped | `51db3310` | **3 — and NOT the boolean pin** |
| a9 | the enum consult in the LEAF walker dropped | `3635e013` | **1** |

**a8 IS THE ARM WORTH READING**: it reddens the string, numeric and template pins and leaves
the BOOLEAN one green, which is what makes "a leaf widens" a RULE rather than "literals
widen" — the boolean pin is the control, and pristine's own two `Type 'false'` rows are the
corpus half of it.

**TWO DRIVER DEFECTS, BOTH CAUGHT BY THE BUILD AND NEITHER A DEAD ARM.** a5 and a9 were first
written as `val x = null ?: return false`, which Kotlin rejects (`Function invocation
'first()' expected`); the driver reported BUILD FAILED rather than 0 RED, which is the
distinction that matters — a `null as Expression?` cast made both arms land. Recorded because
"the arm did not build" and "the arm changed nothing" print identically in a careless driver.

**THREE NEW RESIDUES, ALL MEASURED, RE-QUEUED AS (CHK.60).** (i) A `this.<member>` assignment
target is silent for the weak rule at EVERY source shape, callable and not — so it is not the
anchor change: [Checker.getTypeOfExpression] answers `any` for `this.<optional member>` (the
probe `const p: string = this.zzzHandler` is silent here where tsc says
`Type 'ZzzS9 | undefined' is not assignable to type 'string'`). (ii) An OPTIONAL `any`
property renders `zzzNope?: any | undefined` where tsc renders `zzzNope?: any` — `any`
absorbs `undefined` in tsc's union construction and our `getUnionType` does not reduce that
pair; visible from every position that renders a target through the TYPE rather than the
ANNOTATION, and pinned as residue. (iii) An enum member against a weak target it SHARES a
property with (`{ length?: number }`) is SILENT in tsc and emits TS2345/TS2322 here, from the
ordinary relation rather than the weak rule.

### Round (CHK.58) — the weak rule fired at a var decl and at a call argument and **nowhere else**; and TS2560 is *"calling it would have worked"*, not *"the source is callable"*

**FOUR FIXES, ONE ARC.** The B482 walkers emitted TS2559/TS2560 at a var DECL and at a
CALL ARGUMENT and at no other position, so `function f(): W { return v }` and `x = v`
reported NOTHING for a weak target. **This is not a union defect** — (CHK.57) closed the
union half of the two positions that DID report, and the bare target was silent in these
two all along; the only row the return position had, it had with the wrong CODE (TS2322
naming the whole union, where tsc names the surviving constituent).

**THE FOUR, IN THE ORDER THEY LANDED** (each its own commit, each with the full gate set):

1. **The RETURN and ASSIGNMENT positions.** [Checker.tryEmitWeakValuePosition] is the
   shared emitter; [Checker.weakAssignmentTarget] reads the LHS's DECLARED type
   (annotation ladder first, `currentLocalTypes` second; a property access answers through
   `getTypeOfExpression`). **Twelve missing tsc rows now land byte-exact and one wrong-code
   row is corrected.**
2. **The TS2559 / TS2560 split.** [Checker.weakCallResultSatisfiesTarget] asks whether the
   FIRST call signature's return type — or failing that the first CONSTRUCT signature's —
   is related to the target, which is tsc's `reportErrorResults`. **Four of six callable
   shapes carried the wrong code.**
3. **The enum-member DISPLAY.** `E.A` for a multi-member enum, `E` for a one-member one.
4. **A `new C()` var-decl initializer** is a weak-rule source ([Checker.topLevelWeakSource]
   had a cast, an enum member and a literal branch and no `NewExpression` one).

**THE ANCHORS WERE CORROBORATED BY PRISTINE, NOT TAKEN FROM tsgo ALONE** (round 938). A
return squiggles the `return` KEYWORD — pristine puts `~~~~~~` under `return null;` for a
return-type TS2322 and [Checker.checkReturnAssignabilityCore] already used `stmt.pos` + 6 —
and an assignment squiggles the LHS REFERENCE, one `~` under the `c` of `c = d` in
`assignmentCompatWithObjectMembersOptionality2.errors.txt`, which is *this diagnostic* in
*that position*.

**THE ONE INSIGHT NO READING OF tsc's SOURCE PRODUCES.** tsc's weak check lives INSIDE
`isRelatedTo`, so when `reportErrorResults` asks "is the call RESULT related to the target"
it gets a weak-aware answer; ours does not, and a weak all-optional target accepts every
non-nullish source vacuously. Ask [Checker.checkTypeRelatedTo] alone and `() => number`
keeps TS2560. The veto is [Checker.weakParamRefusesArg] in front of the relation, and arm
b2 (drop it) reddens exactly the three shapes it decides.

**AND THE ORACLE OVERRULED THE QUEUE ON ITEM 3.** The entry read `enumMemberWeakSource`'s
`Type 'E'` as wrong and pristine's agreeing baseline as a coincidence of a one-member enum.
Measured on tsc 7.0.2, it is **one rule and both answers are right**: the enum-literal type
of a single-member enum IS the enum type, so `typeToString` prints the enum's name. The
boundary is the member COUNT, not the flavour — `{A="A",B="B"}` and `{A,B}` render `E.A`,
`{A="A"}` and `{A}` render `E`. Arms c1 and c2 redden the two sides complementarily.

**ORDER IS A COST DECISION, AND IT WAS MEASURED TWICE.** The first implementation asked the
VALUE's type before the TARGET's weakness: **+6.89% `typeOfExpr.calls`**. Giving the return
site its own `getTypeFromTypeNode` instead of sharing the engine's: **+2.9%
`typeNode.cacheable` / +11.2% `mapped.hits`**. **Both produced BYTE-IDENTICAL output** (the
pin-fixture capture `diff`s clean across all three builds), so no gate but `cost_gate.py`
could have seen either. As landed the largest counter move is **+1.40%**.

| # | shape | tsc 7.0.2 | parent `45eb6d07` | ship |
|---|---|---|---|---|
| q01 | RETURN, bare weak, literal source | TS2559 1:54 at `return` | **SILENT** | ✓ exact |
| q02 | ASSIGNMENT, bare weak | TS2559 2:1 at the LHS | **SILENT** | ✓ |
| q03 | RETURN, weak UNION | TS2559 1:61 naming the CONSTITUENT | **TS2322**, whole union | ✓ |
| q04 | ASSIGNMENT, weak UNION | TS2559 2:1 | **SILENT** | ✓ |
| q05 | INTERFACE target, both positions | TS2559 2:30 + 4:1 naming `'ZzzQ05'` | **SILENT** | ✓ both |
| q06 | identifier source (pristine's `c = d`) | TS2559 5:1 + 6:31 | **SILENT** | ✓ both |
| q07 | property-access assignment target | TS2559 3:1 | **SILENT** | ✓ |
| q15 | `new K()` source, both positions | TS2559 2:54 + 4:1 | **SILENT** | ✓ both |
| w1 | `() => number` argument | TS**2559** | TS2560 | ✓ |
| w2 | `() => { zzzT: number }` | TS2560 + TS6212 | ✓ | ✓ |
| w3 | `() => { zzzZ: string }` | TS**2559** | TS2560 | ✓ |
| w4 | `new (s) => { zzzT: number }` | TS2560 + TS6213 | ✓ | ✓ |
| w5 | `new (s) => { zzzZ: string }` | TS**2559**, no related | TS2560 | ✓ |
| w6 | `() => void` | TS**2559** | TS2560 | ✓ |
| e1 | `{A="A",B="B"}.A` / `{A,B}.A` at a var decl | `Type 'E.A'` | `Type 'E'` | ✓ |
| e1 | `{A="A"}.A` / `{A}.A` | `Type 'E'` | ✓ | ✓ |
| y2 | `new C()` at a VAR DECL | TS2559 4:7 | **SILENT** | ✓ |
| q11 | object-literal source, both positions | TS2353 at the PROPERTY | ✓ | ✓ unchanged |
| q16 | two WEAK constituents | TS2322, whole union | ✓ (return) | ✓ unchanged |
| q12 | callable source at return/assignment | TS2559 | SILENT | **SILENT — open** |
| y7 | GENERIC instantiation, both positions | TS2559 | SILENT | **SILENT — deliberate** |
| e1 | enum member at a CALL ARGUMENT | TS2559 | SILENT | **SILENT — open** |

**GATES.** Suite **16,199 / 0 / 3** (+30, exactly the four new classes: 15 + 7 + 5 + 3),
summed with `xml.etree` over the SEVEN-module glob (core 15,230 + project 704 + kir 146 +
daemon 66 + api 30 + client 20 + cli 18); **no corpus baseline moved** at any step, and
that is the load-bearing one — three of the four fixes CHANGE an existing row's code or
text. `cost_gate.py` exit 0 unrebaselined, `output.errors` **46**. `huge_methods
--fail-over 0` exit 0, **783** classes, 0 over. **8-profile grid over two session-built
binaries**: md5 **`503774c23b4535130ffdebabef430cf0`** on the parent AND on every ship
build, per-profile `diff` clean — **`added=0 removed=0` on all eight**, unmoved since
(CHK.54). `partition-equivalence` **EQUIVALENT, all 78**, floor **62 ms** [60, 61, 65, 62]
— one draw. `capture-equivalence` **1,005 span(s) in 43 of 76, types=1005 definitions=0,
moreAny 0**, `definitions` **360,376**, ARM DIGESTs `full=-3735929574989657502
narrow=-2075467818767010709` — the standing state. **`knip` @ `dc7aca5` 48 -> 48 and
`jsonrepair` 3.13.1 4 -> 4, EVERY ROW BYTE-IDENTICAL** to a parent arm built in this
session (`diff` clean both).

**TWELVE ABLATION ARMS, ONE MISTAKE EACH**, each `cmp`-diffed against its OWN snapshot,
each anchor asserted unique (exit 3 otherwise), each build grepped for `e:`, each
`Checker.class` md5 recorded and all distinct, restore verified OUTSIDE the driver and the
binary rebuilt after every restore.

| arm | injected mistake | class | RED |
|---|---|---|---|
| a0 | the whole change reverted (the parent, rebuilt this session) | `f3190558` | **8** — exactly the eight positives |
| a1 | the RETURN site removed | `30dbdf39` | **5** — q01/q03 uniquely, plus the three both-position pins |
| a2 | the ASSIGNMENT site removed | `36afe255` | **6** — q02/q04/q07 uniquely, plus the same three |
| a3 | the object-literal refusal dropped | `90277509` | **1** — uniquely q11 |
| a4 | the callable refusal dropped | `56be4f82` | **1** — uniquely q12 |
| a4b | ONLY the cheap AST callable pre-gate dropped | `cc335973` | **0** — redundant with the signature test, kept for cost |
| a5 | the single-survivor test dropped | `b197de0e` | **3** in three classes — ONE observable |
| a6 | the assignment target read NARROWED, not DECLARED | `9f60802c` | **0** — undiscriminated (see below) |
| a7 | the target-weakness pre-gate removed | `517e872e` | **0** — the COST reorder, provably output-neutral |
| b1 | the 2559/2560 split reverted | `d1ff3af8` | **4** — the four wrong-code shapes |
| b2 | the WEAK veto dropped from the call-result relation | `80a14e56` | **3** — w1/w3/w5, and NOT w6 |
| b3 / b3b | an unresolved / `any` call result answers false | `d58ec2a3` / `e79768e6` | **0** — DEAD arms |
| c1 | the enum member-COUNT boundary dropped (always `E.A`) | `68f31eb6` | **2** — the one-member pins |
| c2 | the enum display reverted (always `E`) | `15bf37a5` | **2** — the multi-member pins |
| d1 | the `new` branch removed | `8e3374f4` | **2** — exactly its two positives |

**FIVE ARMS READ 0 AND EACH IS A DIFFERENT KIND OF ZERO** — the distinction round 927 asks
for, and getting it wrong in either direction is how a guard gets deleted or credited.
**a7 is provably unobservable**: the pre-gate's every term is re-tested by
[Checker.tryEmitWeakTypeAssignment] and the pin-fixture capture is byte-identical with and
without it — it exists to keep `typeOfExpr.calls` off the value, and the counter table is
its evidence. **a4b is redundant** with the signature test one line down, kept for the same
reason. **a6 is undiscriminated, not redundant**: for an IDENTIFIER target `getTypeOfExpression`
reads `currentLocalTypes`, which holds the DECLARED type and never flow-narrows, so the two
ladders agree on every shape here; the annotation ladder is kept because it avoids a
`getTypeOfExpression` per assignment. **b3 / b3b are DEAD** — the pristine
`getDefaultSettings` shape they were written for RESOLVES its inferred return type in this
compiler, so both legs are unreached and the pin travels the ordinary path; recorded in the
pin's KDoc rather than claimed (round 807).

**AND THE PIN THE ARMS REPAIRED, FOR THE SECOND ROUND RUNNING.** The two-constituent
refusal shipped with `{ zzzA?: null } | string` and arm a5 read **0 RED** against it — a
resolved union's member ORDER is not its display order, so `firstOrNull` hands the helper
`string`, on which every weak predicate bails. With TWO WEAK constituents the arm goes RED.
(CHK.57) found the identical thing one position over; the OBVIOUS union fixture is the one
that cannot see it.

**ONE PROCESS ACCIDENT WORTH RECORDING.** A `python3 build/chk58/ablate.py 2>&1 | head -0`
typed with no arm argument ran the driver over EVERY arm; `head -0` closed the pipe, the
tool timed out at 2 minutes and killed it mid-arm, leaving the PARENT `Checker.kt` in the
tree with no marker — round 805's hazard, caught by `git status --porcelain` before the
next build. Recovered with `git checkout --` because the work was already committed
(round 789's law), and the rebuilt class md5 came back to `fda5e367`, the recorded value.

**RESIDUE, ALL MEASURED, RE-QUEUED AS (CHK.59).** Two-or-more non-nullish constituents
(needs the RELATION); a CALLABLE source at the var-decl / return / assignment positions —
**the code split UNBLOCKED it, and what is left is the ANCHOR**, since tsc squiggles the
EXPRESSION there (`w7.ts` 3:22 / 4:34 / 6:9) and not the name / `return` / LHS; an
enum-member CALL ARGUMENT (the argument walker types through `getTypeOfExpression`, which
answers `any` for `E.A`); a GENERIC instantiation source (the deliberate `Type.Reference`
bail — now SYMMETRIC across positions, which is the point); the nested object-literal LEAF
walker; and the fresh-object-literal-vs-bare-weak-ARGUMENT TS2353 boundary.


### Round (CHK.57) — the weak rule now distributes over a **UNION** target in both walker positions; the queue's own two-constituent shape was a **dead** ablation arm, and the verdict call it names is a **redundant** guard

**THE DEFECT WAS ONE `when` ARM WIDE.** [Checker.weakTargetProperties] answers null for a
[Type.Union], so every B482 walker — the ones that actually EMIT TS2559/TS2560 at a named
position — was blind to a weak type reached through one, while (CHK.54)'s SELECTION and
(CHK.56)'s TS2769 path had folded over constituents all along. `T | null` / `T | undefined`
is the commonest parameter and variable shape in real TypeScript, so this was the rule
absent from the majority of the positions where it fires.
[Checker.weakUnionRefusalConstituent] composes the two helpers the queue named — the
verdict [Checker.weakParamRefusesArg] and the display [Checker.weakRefusalDisplayTarget] —
and is wired into the single-signature CALL argument site and
[Checker.tryEmitTopLevelWeakVarDecl] as a branch **disjoint** from the bare-target one
(the bare branch's own condition is its guard, which is why the bare path is byte-identical
and arm a0's controls stay green).

**BOTH POSITIONS CLOSED, EVERY ROW BYTE-EXACT AGAINST tsc 7.0.2**, read off the compiler
rather than derived (fixtures kept per pin under `build/chk57/pinora`). Parent `fdab56c0`,
ship `f3190558`, both built in this session; `Diagnostic.character` is the CLI's **1-based**
column verbatim — (CHK.56)'s note said 1-based and I re-derived it as 0-based, which
reddened nine pins on the first run, so that fact now sits in the pin class's own KDoc.

| # | shape | tsc 7.0.2 | parent `fdab56c0` | ship `f3190558` |
|---|---|---|---|---|
| p01 | ONE sig, param `{zzzA?;zzzF?} \| null`, arg `123` | TS2559 2:24 naming the CONSTITUENT | **SILENT** | ✓ exact |
| p02 | var decl `{zzzA?;zzzF?} \| null = "utf8"` | TS2559 1:7 at the var NAME | **SILENT** | ✓ exact |
| p03 | ditto with `\| undefined` | TS2559 2:24 | **SILENT** | ✓ |
| p04 | INTERFACE constituent | TS2559 3:25 naming `'ZzzP04'` | **SILENT** | ✓ |
| p05 | NON-fresh object source `{zzzZ:1}` | TS2559 3:24 naming `'{ zzzZ: number; }'` | **SILENT** | ✓ |
| p06 | REST param `...o: (W \| null)[]` | TS2559 2:24 | **SILENT** | ✓ |
| p07 | ALIAS constituent, call AND var decl | TS2559 3:25 + 4:7 naming `'ZzzP07'` | **SILENT** | ✓ both |
| — | `Partial<{zzzA:number}> \| null`, both positions | TS2559 naming `'Partial<{ zzzA: number; }>'` | **SILENT** | ✓ both |
| — | rest/class-instance source at the ARGUMENT | TS2559 | **SILENT** | ✓ |
| p08 | source SHARES a property | (no error) | ✓ | ✓ |
| p09 | `{}` arg / explicit `null` | (no error) | ✓ | ✓ |
| p11 | OBJECT-LITERAL argument | **TS2353** 2:26 at the PROPERTY | SILENT | **SILENT — deliberate** |
| p16 | TWO WEAK constituents | TS2345 2:24 / TS2322 3:7, WHOLE union | **SILENT** | **SILENT — open** |
| p13 | CALLABLE source `() => 1` | TS2559 2:24 | SILENT | **SILENT — deliberate** |
| p14 | BARE weak target, both positions | TS2559 2:24 + 3:7 | ✓ | ✓ unchanged |
| p15 | object-literal INITIALIZER | TS2353 1:58 | ✓ | ✓ unchanged |

**THREE REFUSALS, EACH MEASURED RATHER THAN CHOSEN.**

* **Two or more non-nullish constituents are a DIFFERENT MECHANISM, not a conservatism.**
  tsc words them as ordinary assignability naming the WHOLE union — TS2345 at an argument,
  TS2322 at a var decl — which needs the RELATION to reject, where the weak rule lives in
  the walkers. Emitting TS2559 there would be the right verdict with the wrong sentence.
* **An OBJECT-LITERAL argument keeps (CHK.56)'s boundary.** tsc's freshness/excess check
  runs above the weak one and squiggles the offending PROPERTY (`p11.ts(2,26)` TS2353),
  two columns right of where the weak wording sits; we do not emit that excess row for an
  argument the relation ACCEPTED, so the shape stays silent rather than acquiring a row at
  the wrong span. The var-decl twin needs no guard and has a pin saying so:
  [Checker.topLevelWeakSource] answers null for an object literal, and `p15` — which this
  compiler ALREADY matched byte for byte — is unmoved.
* **A CALLABLE source is refused because our TS2559/TS2560 SPLIT is wrong at the BARE
  target, and I measured that rather than inheriting it.** tsc emits 2560
  (*Did you mean to call it?*) only when CALLING the source yields something assignable to
  the target — `() => number` against a weak object is **TS2559**, `() => { zzzA?: null }`
  is TS2560 — where [Checker.tryEmitWeakTypeAssignment] emits 2560 for **every** callable
  source. Distributing uniformly would have added a row with the wrong CODE, so the union
  path refuses; the bare divergence is queued as (CHK.58).

**TWO ABLATION FINDINGS WORTH MORE THAN THE FIX.**

* **The queue's own two-constituent shape (`{ zzzA?: null } | string`) is a DEAD ARM.**
  Arm a2 (single-survivor test dropped) read **0 RED** against it, and not because the pin
  was blind: with a NON-weak first constituent `firstOrNull` hands
  [Checker.tryEmitWeakTypeAssignment] a `string` target, on which it bails anyway. The
  discriminating shape is **two WEAK object constituents**; with `p16` carrying it the arm
  went to **1 RED, uniquely that pin**. Round 902's law, and the queue entry's own example
  was the shape that cannot see it.
* **`weakUnionRefusalConstituent`'s verdict call is a REDUNDANT GUARD.** Arm a6 (replace it
  with `true`) read **0 RED**, and this one IS explicable term for term: for the single
  surviving constituent, [Checker.weakParamRefusesArg]'s weak-name fold reduces to that
  constituent's own [Checker.weakTargetProperties] + all-optional + non-empty test, its
  `others.none { … }` relation escape is vacuous (`others` is empty whenever the survivor
  is weak, and `weakNames` is empty otherwise), and its source-enumerability and overlap
  tests ARE the emitter's. Kept as the sentence that says what the rule is, recorded in the
  KDoc as redundant, and **not claimed as pin coverage** (round 807).

**GATES.** Suite **16,169 / 0 / 3** (+14, exactly the one new class), summed with
`xml.etree` over the SEVEN-module glob (core 15,185 + project 704 + kir 146 + daemon 66 +
api 30 + client 20 + cli 18); **no corpus baseline moved** — the 13k baselines carry no
weak-union shape at all. `cost_gate.py` exit 0 unrebaselined, `output.errors` **46**; the
table is the parent's to within 35 calls on the largest counter (`typeOfExpr.calls`
590,782 -> 590,817 = +0.006%, `globals.lookups` +19), i.e. the union fold costs nothing on
tsc's own sources because nothing there reaches it. `huge_methods.py --fail-over 0` exit 0,
**783** classes scanned, 0 over limit. **8-profile grid over two session-built binaries**
with a `javap` control of **0 vs 1** occurrences of the new helper: capture md5
**`503774c23b4535130ffdebabef430cf0`** on the parent AND the ship, per-profile `diff` clean,
i.e. **`added=0 removed=0` on all eight** and identical to the digest (CHK.54)/(CHK.55)/
(CHK.56) recorded. `partition-equivalence` **EQUIVALENT, all 78**, floor **63 ms**
[63, 62, 51, 81] — one draw. `capture-equivalence` **1,005 span(s) in 43 of 76 file(s),
types=1005 definitions=0, moreAny 0**, `definitions` **360,376**, ARM DIGESTs
`full=-3735929574989657502 narrow=-2075467818767010709` — the standing state, unmoved.
The last two were taken on `c1970110`, which is the ship binary's bytecode: the only later
edit was a KDoc, and `javap -c -p` minus the `LineNumberTable` is **byte-identical** across
the two builds (1,024,524 lines each). **Checker.kt is ONE class, so an inserted comment
line shifts every later line number and the class md5 moves for a comment** — do not read
that as a code change, and do not read a stable md5 as proof of one either.

**LIBRARIES: `knip` @ `dc7aca5` 48 -> 48 and `jsonrepair` 3.13.1 4 -> 4, EVERY ROW
BYTE-IDENTICAL** (`diff` clean, parent and ship arms both built in this session).
The queue item predicted "it ADDS rows … the `readFileSync` family is exactly this shape —
expect it to fire on real code": **measured FALSE, and (CHK.54) is why.** Selection already
refuses a weak-union parameter for a disjoint argument, so `readFileSync(p, 'utf8')` picks
the `string` overload and the argument site never asks. The shape that reaches the new code
is a SINGLE signature (or a var decl) with a weak union — which neither library and none of
the eight profiles contains.

**SEVEN ABLATION ARMS, ONE MISTAKE EACH**, each `cmp`-diffed against its OWN snapshot, each
anchor asserted unique (exit 3 otherwise), each build grepped for `e:`, each `Checker.class`
md5 recorded and all seven distinct, restore verified OUTSIDE the driver and the binary
rebuilt after every restore (the class dir returns to `c1970110` each time, printed per arm).

| arm | injected mistake | class | RED of 14 |
|---|---|---|---|
| a0 | the whole change reverted (the parent, rebuilt this session) | `fdab56c0` | **7** — exactly the seven positives |
| a1 | the object-literal guard dropped | `d25ef27d` | **1** — uniquely the object-literal pin |
| a2 | the single-survivor test dropped (`singleOrNull` -> `firstOrNull`) | `19fce64d` | **1** — uniquely the two-constituent pin (**0** before its fixture was repaired) |
| a3 | the callable-source guard dropped | `07f884ad` | **1** — uniquely the callable pin |
| a4 | the ARGUMENT site's union retry removed | `a2ce04b6` | **6** — every call-position positive |
| a5 | the VAR-DECL site's union branch removed | `6ae581a2` | **2** — the two var-decl positives |
| a6 | the weak VERDICT not asked (always refuse) | `3bf59b3d` | **0** — a REDUNDANT guard, explained above |

**WHAT IS MEASURED AND STILL OPEN — queued as (CHK.58).** Six shapes, all read off tsc
7.0.2 this round and all recorded with the fixture that shows them; the largest is that
**a weak union is still silent in the RETURN and ASSIGNMENT positions** and that our
**TS2560 is emitted for every callable source** where tsc splits on the return type.

### Round (CHK.56) — the TS2769 diagnostic path did not ask the weak-type rule; the item's "hard part" was a **tsgo rendering** and not tsc's, and the fix ADDS **no** row on any corpus here

**THE ITEM'S OWN DESIGN LEAD WAS HALF RIGHT AND HALF A tsgo ARTEFACT.** (CHK.55)
re-queued this with tsc's exact answer — `No overload matches this call.` /
`The last overload gave the following error.` / `Type '123' has no properties in
common with type '{…}'` — and read the elaboration as the work, because
[Checker.getFirstArgumentError] walks the plain relation, which ACCEPTS a
disjoint argument against a weak parameter, finds no failing argument, and drops
the overload out of the chain. Measured this round:

* **the WORDING is confirmed**, and it really is TS2559's *no properties in
  common* sentence rather than an assignability line — so the subline is minted
  beside the existing walk, on the path where the relation SUCCEEDED;
* **the "which overload" half is a tsgo RENDERING.** `tools/tsgo-7.0.2/lib/tsc`
  prints `The last overload gave the following error.` for **2, 3 and 4**
  candidates alike; PRISTINE tsc prints `Overload N of M, '<sig>', gave the
  following error.` for every failing candidate — **42** `typescript-repo`
  baselines carry `Overload 1 of 2,` against **4** carrying the last-overload
  form, and `tsxStatelessFunctionComponentOverload4.errors.txt` carries a *no
  properties in common* subline INSIDE exactly that per-candidate chain. Our
  chain has had the pristine shape since B418, so **no "which overload" policy
  was needed at all**: the weak verdict is simply one more per-overload error
  string, and the queue item's "a TS2769 naming the wrong overload is worse than
  silence" risk never arose. Round 938's law, paid on the diagnostic with the
  most chain machinery in this repo.

**THE CHANGE.** [Checker.weakOverloadArgRefuses] is the weak verdict as the
overload helpers ask it, and ALL FOUR of them move together
([Checker.allArgumentsMatch] behind an opt-in `applyWeakRule`, so only the
overload-MATCH loop asks it and the four TS2793 implementation-signature gates are
untouched; plus `getFirstArgumentError`, `countFailingArgDiagnostics` and
`getFirstFailingArgPosition`) — a helper left behind would name an overload the
match loop thought fine, or anchor the squiggle at the wrong argument.

**TWO THINGS THAT HAD TO BE MEASURED RATHER THAN GUESSED.**

* **Which type the message NAMES for a UNION parameter.** After dropping
  `null`/`undefined`, exactly ONE surviving constituent takes the TS2559 wording
  naming THAT CONSTITUENT (`ZzzWk | null` renders `'ZzzWk'`); TWO OR MORE take the
  ordinary assignability wording naming the WHOLE union
  (`{ zzzA?: null } | string` with a `number` argument renders
  `'string | { zzzA?: null | undefined; }'` — our union order agrees byte for
  byte). The VERDICT is a refusal either way, so [Checker.weakParamRefusesArg]
  says nothing about the sentence; [Checker.weakRefusalDisplayTarget] is the
  second question.
* **An OBJECT-LITERAL argument is not the weak rule's, and that is tsc's answer
  rather than a conservatism.** tsc runs the freshness/excess check ABOVE the weak
  check in `isRelatedTo`, and a fresh literal sharing no property name with the
  target has EVERY property excess by construction — so `f({ zzzZ: 1 })` against
  two weak overloads is `Object literal may only specify known properties, and
  'zzzZ' does not exist in type '{ zzzB?: null | undefined; }'` at the PROPERTY
  (column 23), where the weak wording would name the whole literal at column 21.
  We do not emit that excess row for an argument the relation accepted, so the
  shape stays **silent** — deliberately, because a TS2769 at the wrong span is
  worse than none. A NON-fresh source of the identical type (`const o = { zzzZ: 1
  }; f(o)`) IS the weak wording and is pinned: freshness, not shape, is the axis.

**WHAT IS MEASURED AND STILL OPEN — a second, separate hole, queued as (CHK.57).**
The MUD's "is the non-overloaded case already correct here" question has a
two-part answer. A bare weak target is correct and byte-identical to tsc (m1, m7,
m8, n5, r2 below). A weak target reached through a **UNION** is not, and it is
open in BOTH the call and the var-decl positions: `zzzM6(123)` against a single
`(o: { zzzA?: null; zzzF?: string } | null)` signature and
`const v: { zzzA?: null; zzzF?: string } | null = "utf8"` are TS2559 in tsc and
**silent here**, because [Checker.weakTargetProperties] answers null for a union
and the B482 walkers never distribute. That is the walkers, not the overload
helpers — a different mechanism with its own FP surface — so it is queued rather
than folded in, and NOT pinned (round 765).

**THE MESSAGE-FAMILY MATRIX**, ours through the project CLI against
`tools/tsgo-7.0.2/lib/tsc` on byte-identical source, plus the PRISTINE chain form
read out of `typescript-repo`'s own baselines. Parent `313b0ddd`, ship `fdab56c0`,
both built in this session.

| # | shape | tsc 7.0.2 (tsgo) | pristine form | parent (313b0ddd) | after (fdab56c0) |
|---|---|---|---|---|---|
| m1 | ONE signature, weak param `{zzzA?;zzzF?}`, arg `123` | TS2559 2:21 `Type '123' has no properties in common with type '{ zzzA?: null \| undefined; zzzF?: string \| undefined; }'.` | — | same ✓ | same ✓ |
| m2 | TWO weak overloads | TS2769 3:21, last-overload subline = the TS2559 wording naming ovl 2 | `Overload N of M` per candidate | **SILENT** | TS2769 3:21, both sublines ✓ |
| m3 | THREE weak overloads | TS2769 4:21, subline names ovl 3 | per-candidate | **SILENT** | TS2769 4:21, three sublines ✓ |
| n1 | FOUR weak overloads | TS2769 5:21, subline names ovl 4 | per-candidate | **SILENT** | TS2769 5:21, four sublines ✓ |
| m4 | weak ovl FIRST, `string` ovl second, arg `"hi"` | (no error) | — | (no error) ✓ | (no error) ✓ |
| m5 | `string` ovl first, weak ovl SECOND, arg `123` | TS2769 3:21, weak subline | per-candidate | **SILENT** | ✓ |
| n2 | weak first, `string` second | TS2769 3:21, subline = assignability vs `string` | per-candidate | **SILENT** | ✓ (both sublines) |
| n3 | `string` first, weak second | TS2769 3:21, weak subline | per-candidate | **SILENT** | ✓ |
| n4 | `string`/weak/`boolean` | TS2769 4:21, subline vs `boolean` | per-candidate | **SILENT** | ✓ |
| m10 | weak ovl + a NON-weak failing ovl | TS2769 3:23, subline vs `{ zzzB: string; }` | per-candidate | **SILENT** | ✓ |
| n5 | two ovls, arity leaves ONE candidate | TS2559 3:21 (no TS2769 wrapper) | — | same ✓ | same ✓ |
| n7 | weak param in the SECOND argument | TS2769 3:26 (the 2nd arg) | per-candidate | **SILENT** | ✓ 3:26 |
| n8 | two IDENTICAL weak overloads | TS2769 3:21 | per-candidate | **SILENT** | ✓ |
| n9 | METHOD overloads on an interface | TS2769 3:23 | per-candidate | **SILENT** | ✓ |
| m7 | heritage interface param (`ZzzOpts7 extends ZzzBase7`), ONE sig | TS2559 4:21 naming `'ZzzOpts7'` | — | same ✓ | same ✓ |
| p3 | ALIAS-typed weak param, two ovls | TS2769 4:21 naming `'ZzzAl3'` | per-candidate | **SILENT** | ✓ naming `'ZzzAl3'` |
| m6 | ONE sig, union param `{…}\|null` | TS2559 2:21 naming the CONSTITUENT | — | **SILENT** | **SILENT — still open** |
| m9 | var decl `{…}\|null = "utf8"` | TS2559 1:7 naming the CONSTITUENT | — | **SILENT** | **SILENT — still open** |
| n6/q3/q4 | union param `{…}\|null` / `\|undefined` / `ZzzUdB\|null`, TWO ovls | TS2769, subline names the CONSTITUENT | per-candidate | **SILENT** | ✓ names the constituent |
| p1/q1 | ONE sig, union param with TWO non-nullish constituents | TS2345 naming the WHOLE union | — | **SILENT** | **SILENT — still open** |
| p2/q2 | ditto, TWO ovls | TS2769, subline = assignability naming the WHOLE union | per-candidate | **SILENT** | ✓ whole union, order identical |
| q5 | weak params in BOTH args, first fails | TS2769 3:22 (arg 1) | per-candidate | **SILENT** | ✓ |
| p4 | OBJECT-LITERAL arg `{zzzZ:1}`, two weak ovls | TS2769 **3:23** subline = `Object literal may only specify known properties…` | per-candidate | **SILENT** | **SILENT — deliberate** |
| p5 | `{}` arg | (no error) | — | (no error) ✓ | (no error) ✓ |
| p6 | arg shares a property | (no error) | — | (no error) ✓ | (no error) ✓ |
| r1 | NON-fresh object source `{zzzZ:1}`, two weak ovls | TS2769 4:22, weak subline | per-candidate | **SILENT** | ✓ |
| r2 | NON-fresh object source, ONE sig | TS2559 3:22 | — | same ✓ | same ✓ |
| r3 | FRESH literal `{zzzZ:1}`, ONE sig | **TS2353** 2:24 excess | — | TS2559 2:22 (pre-existing divergence) | unchanged |

**GATES.** Suite **16,155 / 0 / 3** (+11, exactly the one new class) — run TWICE, and the
first run read 16,155 / **3** / 3 because three of this round's own pins asserted a
`character` I had derived by hand instead of reading off tsc; the three failures were
mine, not a baseline's, and the second run is the recorded one. Summed with
`xml.etree` over the SEVEN-module glob (core 15,171 + project 704 + kir 146 + daemon 66 +
api 30 + client 20 + cli 18); **no corpus baseline moved.** `cost_gate.py` exit 0
unrebaselined, `output.errors` **46** — and this round's table is **DIGIT-FOR-DIGIT the
PARENT's**, measured on the a0 binary in this session and kept at
`build/chk56/costgate-parent.txt`, so the standing `typeOfExpr.calls` **+0.59%** /
`narrow.memoServed` **+0.69%** are (CHK.55)'s and this change costs **0.00%** on the
compiler profile. That is the expected control: the weak question is asked only after the
relation has already ACCEPTED an argument, and nothing on tsc's own sources reaches it.
`huge_methods.py --fail-over 0` exit 0, **783** classes scanned, 0 over limit.
8-profile grid over two binaries BOTH built in this session (parent `313b0ddd`, ship
`fdab56c0`) with a `javap` control of **0 vs 2** occurrences of the new helpers: capture
md5 **`503774c23b4535130ffdebabef430cf0`** on both, i.e. **`added=0 removed=0` on all
eight** and identical to the digest (CHK.54)/(CHK.55) recorded.
`partition-equivalence` **EQUIVALENT, all 78**, floor **58 ms** [79, 58, 55, 56] — one
draw, the leading 79 the ramp. `capture-equivalence` **1,005 span(s) in 43 of 76 file(s),
types=1005 definitions=0, moreAny 0, absentInNarrow 0, absentInFull 0**, `definitions`
**360,376**, ARM DIGESTs `full=-3735929574989657502 narrow=-2075467818767010709` — the
standing state, unmoved.

**LIBRARIES: `knip` @ `dc7aca5` 48 -> 48 and `jsonrepair` 3.13.1 4 -> 4, EVERY ROW
BYTE-IDENTICAL** (`diff` clean in both directions), BEFORE arms captured on the parent
rebuilt in this session. **The queue item's "it ADDS rows" is measured FALSE for every
corpus this repo has** — which is itself a finding: (CHK.54) already made SELECTION refuse
these signatures, and wherever a later overload accepts, the match loop still finds it and
no TS2769 is produced. The shapes this closes are hand-written; the hole was real and the
population is not in tsc's own sources, in knip or in jsonrepair.

**EIGHT ABLATION ARMS, ONE MISTAKE EACH**, each `cmp`-diffed against its OWN snapshot,
each anchor asserted unique (exit 3 otherwise), each build grepped for `e:`, each
`Checker.class` md5 recorded and all eight distinct, restore verified OUTSIDE the driver
and the binary rebuilt after every restore (the class dir returns to `fdab56c0` each time,
printed per arm).

| arm | injected mistake | class | RED of 19 |
|---|---|---|---|
| a0 | the whole change reverted (the parent, rebuilt this session) | `313b0ddd` | **6** — exactly the six positives |
| a1 | the object-literal guard dropped | `05b2134d` | **1** — uniquely the object-literal pin |
| a2 | the MATCH loop stops asking the weak rule | `d77db6e7` | **6** |
| a3 | the SUBLINE is not minted | `57c76c79` | **6** |
| a4 | the ANCHOR helper does not see the refusal | `e912398b` | **6** |
| a5 | the per-overload failure COUNT does not see it | `90ec084f` | **0** — see below |
| a6 | the display target is always the WHOLE parameter | `2ec4b282` | **2** — uniquely the two union pins |
| a7 | a union never names a constituent | `e71aad11` | **1** — uniquely the one-constituent pin |

**a2/a3/a4 ARE A ROUND-927 TRIPLE AND THE TABLE SHOULD BE READ THAT WAY.** Each alone
deletes the diagnostic entirely — no match refusal, no subline, or no anchor each leave
`checkArgumentsAgainstOverloads` with nothing to emit — so all three are load-bearing and
NONE is redundant, but their red SETS coincide and no pin separates which layer failed.
Recording them as three independent observables would credit the pins with discrimination
they do not have.

**a5 IS UNDISCRIMINATED, NOT PROVABLY UNOBSERVABLE, AND IS KEPT.** `countFailingArgDiagnostics`
feeds B418's best-matching-overload collapse, which only fires when some overload has **≥2**
per-element failures; a weak refusal answers 1, so dropping it to 0 cannot change
`any { it > 1 }`. It CAN change `minByOrNull`'s pick in a tie — an overload set carrying
both a multi-element array-literal failure and a weak refusal — which nothing here
exercises and which I did not build a fixture for. Kept because the five overload
arg-check helpers must agree with the match verdict (archive line 921), and a 0 there is
simply the wrong count.

### Round (CHK.55) — an object literal's literal properties widen, and that one fact bit at **both** overload sites: a false TS2769 at the diagnostic and a **wrong type** at selection; **knip 49 -> 48**

**THE QUEUE ITEM CARRIED THREE HOLES AND MEASUREMENT SAYS THEY ARE ONE.** (CHK.55) was
written as (a) a weak-type hole in the TS2769 path, (b) an object-literal FP, and "a
third, separate row the same matrix found" — row H, "a mechanism this round did not
locate". (b) and row H are the same mechanism at two different call sites, and the
fixture that says so exhibits both symptoms at once.

**THE ONE SENTENCE.** [Checker.getTypeOfExpression] types `{ encoding: "utf8" }` as
`{ encoding: string }` — this compiler has no fresh-literal machinery — so a target
property whose type is a literal (union) rejects the argument. tsc instead contextually
types each candidate's arguments and a fresh literal keeps its literal type.

**THE TWO SITES, AND WHY THEY LOOKED LIKE DIFFERENT DEFECTS.**

* `allArgumentsMatch` (the TS2769 DIAGNOSTIC path) has had round 728's rescue,
  `objLitLiteralPropsSatisfyParam`, all along — but it refused a target INTERFACE with
  heritage and a UNION with more than one non-nullish constituent. Symptom: a **false
  positive**, because selection had already picked the right overload.
* `signatureAcceptsArgs` (SELECTION) had **no rescue at all**, so every candidate was
  passed over and the answer came from `resolveCallOverload`'s `arityMatches[0]`
  fallback. Symptom: a **wrong type, with no diagnostic anywhere** — a hover that lies.

The 10-row matrix, ours against `tools/tsgo-7.0.2/lib/tsc` run directly on
byte-identical source through the project CLI (the parent binary rebuilt in this session,
`Checker.class` md5 `86ec37c3…`, which is the digest (CHK.54) recorded as ITS landed
binary — an independent confirmation the parent reproduced):

| # | shape | parent | tsc 7.0.2 | after |
|---|---|---|---|---|
| B1 | `execSync` reduced: heritage-interface param, `{ enc: 'utf8', stdio: [...] }` | `string` **+ TS2769** | `string` | `string` |
| B2 | B1 with the heritage FLATTENED (round 728's own shape) | `string` | `string` | `string` |
| B3 | union param `{ enc: Enc } \| Enc` (`readFileSync`) | **`number` + TS2769** | `string` | `string` |
| H  | `({e?:null}):number` / `(ZH1{e:"u"}):string` — `f({e:"u"})` | **`number`** | `string` | `string` |
| H2 | H with the target property NON-literal | `string` | `string` | `string` |
| H3 | H with the two overloads REVERSED | `string` | `string` | `string` |
| R1 | excess property | TS2769 | TS2769 | TS2769 |
| R2 | missing INHERITED required property | TS2769 | TS2769 | TS2769 |
| R3 | a property failing for a non-widening reason | TS2769 | TS2769 | TS2769 |
| R4 | two identical heritage-param overloads, both should accept | `number` **+ TS2769** | `number` | `number` |

**B3 IS THE ROW THAT IDENTIFIES THE TWO AS ONE**: it carries the false TS2769 *and*
answers the first overload's return type, from a single root. **H3 is what proves H is
the fallback and not an acceptance** — reversing the declarations makes the PARENT agree,
i.e. nothing accepted and first-declared won.

**THE HERITAGE REFUSAL WAS NEVER NECESSARY.** Round 728 refused a target with base types
on the grounds that "an inherited required property would not be enumerated below".
Measured, that is false of this compiler: `resolveInterfaceMembersCore` folds every base
type's `members` into the derived type's own table and then sets
`properties = members.values.toList()`, so both enumerations in the rescue (the
excess-property lookup and the required-property loop) already saw inherited members.
The negative direction is pinned (R2) and the refusal was `knip`'s last overload row.

**A THIRD INTERACTION, AND IT WAS FOUND BY TRYING TO *FALSIFY AN ABLATION ARM*, NOT BY
READING THE CODE.** Arm a5 (`continue` -> fall through to `weakParamRefusesArg`) read 0
RED, so a falsifier was built for it — a union parameter `{ zzzA?: 0 } \| { zzzE: "u" }`
whose weak constituent is disjoint from the literal. On the fixed binary it answered
`number` where tsc answers `string`: round 728 put the rescue on the REJECTING path (so
the happy path pays nothing), but **a weak constituent accepts any non-nullish value
structurally**, so the relation SUCCEEDS through it, the rejecting path is never taken,
and (CHK.54)'s weak rule then refuses the signature having never asked whether the
literal satisfies the OTHER constituent. The weak refusal is now guarded by the rescue,
short-circuit `&&` so it is computed only on the path the weak rule was already going to
refuse. **Pinned as a discriminating PAIR on one fixture shape** — a literal that DOES
satisfy cancels the refusal, one that does NOT leaves it standing — because the obvious
refusal (drop the second overload) leaves tsc reporting a TS2769 we do not yet produce,
which would be a countdown on (CHK.55)(a) rather than a control (round 765).

**AND ITS BY-PRODUCT IS A RETRACTION.** The `continue` beside the rescue in
`signatureAcceptsArgs` was documented as load-bearing on exactly the argument the
falsifier tested. With the guard in place the two forms are equivalent and a5 re-run
against ELEVEN pins still reads **0 RED**. It is recorded in the KDoc as **provably
unobservable**, not as coverage; `continue` is kept only because it saves recomputing
the rescue.

**WHAT I SCOPED OUT, AND WHY.** (CHK.55)(a) — the TS2769 path does not ask
`weakParamRefusesArg` — is confirmed still open by measurement, not assumed:
`zzzU(123)` against a two-weak-overload set is silent here where tsc 7.0.2 says
`Type '123' has no properties in common with type '{ zzzB?: null | undefined; zzzG?:
string | undefined; }'`. It is a MISSING error (the least damaging of the three), it ADDS
rows, and its elaboration genuinely needs its own design — `getFirstArgumentError` walks
the plain relation and finds no failing argument for the overload the weak rule rejects,
so the subline has to come from somewhere else. Re-queued as (CHK.56) with tsc's exact
message, which is `no properties in common` (TS2559's wording) and not an assignability
line — that is the design lead.

**GATES.** Suite **16,144 / 0 / 3** (+11, exactly the one new class); **no corpus
baseline moved by any of the three edits** (the suite was run twice — once after edits
1+2 at 16,142/0/3, once after edit 3). `cost_gate.py` exit 0 unrebaselined,
`output.errors` **46**; largest move `typeOfExpr.calls` **+0.59%** and
`narrow.memoServed` **+0.69%**, both the rescue being consulted on the rejecting path,
and the post-edit-3 table is DIGIT-FOR-DIGIT the post-edit-2 one, i.e. the guard costs
0.00% on this profile. Left unrebaselined deliberately: the increase is inside the ±2%
band and rebaselining would erase the one signal this change has in the counters.
`huge_methods --fail-over 0` exit 0, **783** classes scanned, 0 over limit. 8-profile
grid, both arms built in this session, `javap` control **0 vs 2** occurrences of the new
helper: **`added=0 removed=0` on all eight**, capture md5 `503774c23b45…` on parent, on
the edits-1+2 binary and on the ship binary alike. `partition-equivalence` **EQUIVALENT,
all 78**, floor **61 ms** [54, 61, 74, 60] — one draw. `capture-equivalence` **1,005 span(s)
in 43 of 76 file(s), moreAny 0, absentInNarrow 0, absentInFull 0**, `definitions`
**360,376**, ARM DIGESTs `full=-3735929574989657502 narrow=-2075467818767010709`
(recorded numerically here for the first time — earlier rounds only said "unmoved").

**LIBRARIES. `knip` @ `dc7aca5` 49 -> 48** — exactly one row removed,
`src/util/git.ts:17:55` (`execSync('git rev-parse --git-path hooks', { encoding: 'utf8',
stdio: ['pipe','pipe','ignore'], cwd })`), nothing added. **`jsonrepair` 3.13.1 4 -> 4
byte-identical.** Both BEFORE arms were captured on the parent rebuilt in this session
and are byte-identical to the session-start capture. Edit 3 moves neither library — the
shape it fixes is hand-written, which is worth saying plainly: it is a wrong TYPE with
no library row behind it, kept because a language service that answers the wrong overload
is the failure mode the owner directive names.

**SEVEN ABLATION ARMS, ONE MISTAKE EACH**, each `cmp`-diffed against its OWN snapshot,
each anchor asserted unique (exit 3 otherwise), each build grepped for `BUILD SUCCESSFUL`,
each `Checker.class` md5 recorded and all seven distinct, restore verified OUTSIDE the
driver and the binary rebuilt after every restore.

| arm | injected mistake | RED |
|---|---|---|
| a0 | the whole change reverted (the parent, rebuilt this session) | **4** — exactly the four positives |
| a1 | round 728's heritage refusal restored | **2** — the `knip` row and the both-accept/order pin |
| a2 | the union arm back to `singleOrNull` | **1** — uniquely the union pin |
| a3 | SELECTION no longer asks the rescue | **2** — uniquely row H, plus the union pin |
| a4 | the per-constituent fold accepts a union unconditionally | **2** — uniquely the union-refusal pin |
| a5 | `continue` -> fall through to the weak check | **0** — provably unobservable, see above |
| a6 | the weak refusal no longer guarded by the rescue | **1** — uniquely its own pin |

**WHAT DID NOT WORK, AND WHAT SURPRISED ME.**
* **The first draft of the refusal pins asserted `[2769, 2322]` and every one failed** —
  `diagnose()` returns the TS2322 FIRST. Four "refusals" reading red is indistinguishable
  from four broken refusals until you read the actual list; the power-assert diagram is
  what made it a ten-second fix rather than a hypothesis.
* **My first B1 fixture had an illegal heritage** (`ZzzOptsStr extends ZzzOpts` narrowing
  `encoding` to a value the base did not allow), which tsc reported as TS2430 and we did
  not. The FP still reproduced, so it would have "worked" as a pin — over a fixture whose
  premise tsc rejects. Run the oracle on the fixture before running it on the question.
* **R4 was written as an always-green order control and is a POSITIVE**: two identical
  heritage-param overloads, both of which should accept, carried a spurious TS2769 on the
  parent. A control that reddens under a0 is a positive that was mislabelled, and only
  running a0 says which it is.
* **The `continue` claim was wrong in the KDoc I had just written.** I argued it was
  load-bearing, the arm said otherwise, and the falsifier built to vindicate it found a
  *different, real* defect instead. Both halves are recorded — the retraction and the fix.

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

- [ ] **(LIB.4) `cronstrue` IS THE NEXT BACKEND DRIVER — 0 CHECKER ERRORS ON 8,812 LINES AND
  FIVE NAMED RUNGS TO A RUNNING PROGRAM.** The probe reads `typeErrors=0` over all 52 files
  (it AGREES with tsgo exactly, the only library in the screen that does) and the lowering then
  runs to a first refusal. Walking it by patching a throwaway copy and re-probing gives the
  whole ladder, in order:
  1. `rest parameters are out of the spike subset` — `stringUtilities.ts:10`, **2 sites**
  2. `destructuring in for…of` — `expressionDescriptor.ts:734`, **1 site**
  3. `` `var` is out of the spike subset — its function scoping is not modelled `` — **18 sites in 4 files**
  4. `cannot lower this binary operator` (`??`) — **2 sites**
  5. `cannot coerce Function1 to String` — `String.replace(re, fn)`, i.e. the replacer-CALLBACK
     overload, a RUNTIME gap rather than a language one
  **The count stayed flat as the rungs were peeled — it is not opening into a tail**, which is
  what makes this a bounded piece of work rather than an open-ended one. It is 8x `smol-toml`,
  zero dependencies, zero non-relative imports, and a real CLI (`cronstrue "*/5 * * * *"`), so
  landing it extends `scripts/kir-bench.sh` with a third library and a third workload shape.
  **Rung 3 is the one to price first**: `var`'s function scoping is a real semantic difference,
  not a syntax rewrite, and 18 sites is enough that refusing it keeps blocking libraries.

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

- [ ] **(CHK.63) `T | undefined` IS SILENTLY ASSIGNABLE TO `T` AT A DECLARATION, AN
  ASSIGNMENT AND A RETURN WHENEVER THE TARGET IS A PRIMITIVE — A SYSTEMATIC FALSE NEGATIVE,
  AND ITS SINGLE SUPPRESSOR IS ONE `if` (2026-08-27, found while pricing (CHK.61)(b)).**
  `canUseTypeEngine`'s `if (sourceType is Type.Union && targetIsPrimitive) { … if
  (!hasNullish) return true }` refuses a NULLISH union source against a primitive target,
  with the comment "narrowing we don't implement". On a six-line fixture tsc emits 6 rows
  and we emit 2: the ARGUMENT position and a UNION target are the two that work; the
  DECLARATION, the ASSIGNMENT and the RETURN are silent, for `| undefined` and `| null`
  alike. **Opening it is measured: 11 ours-only rows on the 8 profiles** —
  `checker.ts:35649`, `core.ts:2191`, `emitter.ts:1479`, `parser.ts:2642`, `path.ts:585`,
  `sourcemap.ts:164/165/166`, `harness/tsserverLogger.ts:28`, `server/project.ts:746`,
  `services/organizeImports.ts:862` (`build/chk61/pricing/armG-added.txt`). Two of them are
  (CHK.61)(b)'s absence and vanish when (b) lands; the rest are (CHK.64). So the order is
  (CHK.64) -> (CHK.63) -> (CHK.61)(b)'s checking half, and NOT one of them alone.

- [ ] **(CHK.64) THE ASSIGNMENT AND RETURN READERS DO NOT CONSULT THE FLOW WALK FOR A
  PRIMITIVE TARGET, SO THEY FALL BACK TO A LEGACY IF-ARM HELPER THAT CANNOT READ AN `&&`
  (2026-08-27).** Measured: the FLOW walk handles `&&` correctly (`if (a && b) { a.length }`
  and `if (a && b) { take(a) }` are both right), and a DECLARATION with a primitive target
  narrows. What does not is `zzzP = zzzA` / `return zzzA` under an `&&` — round 784's
  documented gate confines `checkReturnAssignabilityCore`'s narrowing block to an
  object-ish/union target, so those readers see only `currentLocalTypes`, filled by
  `extractNullNarrowing`, which returns ONE `(name, type)` pair and does not decompose
  `&&` at all. **Five mechanisms, each with its measured row** (all reproduce on the
  SHIPPED binary with an explicit `| undefined` member, `build/chk61b/n1`): (i) an `&&`
  if-condition narrows NEITHER operand into the then-branch (`sourcemap.ts:164/165/166`);
  (ii) `if (x === undefined) continue;` does not narrow the rest of a loop body
  (`core.ts:2191`, `path.ts:585`); (iii) an assignment inside the guarded branch does not
  narrow after the `if` (`parser.ts:2642`); (iv) definite assignment across an if/else does
  not narrow (`checker.ts:35649`); (v) the optional-METHOD shapes — an outer
  `if (h.a && h.b)` surviving into a nested `for`+`if` (`moduleNameResolver.ts:824`,
  `project.ts:502/528`), a three-deep chain (`moduleNameResolver.ts:2265`) and an `&&`
  chain whose earlier conjunct narrows a later one (`checker.ts:30269`, TS18048).
  Extending `extractNullNarrowing` to decompose `&&` into SEVERAL narrowings is
  monotone-safe by construction (more narrowing only ever SUPPRESSES), and is the cheapest
  of the five; it is worth doing first and re-pricing (CHK.63) after it.

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
