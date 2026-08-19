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

**Round 943 (2026-08-19) — (CHK.8b): THE 89-ROW "FP — TYPE SYSTEM / INFERENCE" BUCKET,
SUB-TRIAGED — AND THE HONEST HEADLINE IS THAT **68 OF ITS 83 GENUINE ROWS (82%) ARE FOUR
MODELLING ITEMS, I.E. A FEATURE LIST, NOT A DEFECT LIST**. Six more rows are the
strict-family CONVENTION wearing codes the classifier cannot see. What was tractable —
(CHK.16), a declaration's own type parameters being invisible to the TS2344 walker — landed
in BOTH directions, and its FALSE-NEGATIVE half is the larger one.**

**THE SUB-FAMILY TABLE** (each row re-verified against pristine's own answer, or its
ABSENCE; the rules are `scripts/pristine_triage.py`'s new `SUB_BUCKETS`, so the next round
re-runs them against a fresh sweep instead of re-deriving 38 groups by hand):

| # | sub-family | rows | mechanism | cause class | tractability |
|---|---|---:|---|---|---|
| S1 | variadic tuple types | 30 | `getTupleType` gives a `RestType` element the arm a PLAIN element gets, so **`[...T]` IS `[T]`** | genuine FP | **MODELLING** (CHK.20) |
| S3 | contextual typing through a mapped / conditional type | 14 | a callback parameter gets no contextual type -> TS7006 / TS2345 | genuine FP | **MODELLING** |
| S2 | recursive conditional / mapped types over tuples | 13 | the instantiation-depth bail (TS2589) plus a deferred conditional that never evaluates | genuine FP | **MODELLING** |
| S10 | residue — one mechanism each | 11 | ten singletons | genuine FP | MODELLING |
| S4 | the strict-family default in ANOTHER COSTUME | 6 | TS2683 (`noImplicitThis`), TS7019 (`noImplicitAny`), 3x TS2322 (`strictNullChecks`) | **deliberate convention** | (CHK.13) |
| S6 | lib availability at the DEFAULT target | 5 | `libFeatureAvailable` reads the RAW `ES3` default; tsc defaults an unset target to the LATEST | genuine FP | **SMALL-MEDIUM** (CHK.17) |
| S5 | `keyof` of an intersection / index signature / remapped mapped type | 4 | `keyof (X & T)` loses `keyof T` and the index signature's `string \| number` | genuine FP | MODELLING |
| S7 | write through a generic indexed access | 3 | TS2862 where pristine says TS2322 — same position, both reject | **form** | MEDIUM (CHK.18) |
| S8 | an alias/class/interface type parameter shadowed in the TS2344 walker | 2 | the walker resolved type ARGUMENTS with no type-parameter scope | genuine FP | **FIXED** (CHK.16) |
| S9 | a function-body type ALIAS is not bound | 1 | B83.5 in type position — the lib's `Omit` beats a local one | genuine FP | MEDIUM (CHK.19) |

**WHAT THE 31 `variadicTuples1` ROWS ACTUALLY ARE: ONE mechanism, and it is three lines
deep.** `getTupleType` maps `is RestType -> getTypeFromTypeNode(elem.type)`, the same arm a
plain element gets, so `[...T]` is BUILT as the one-element tuple `[T]` —
`function f<T extends unknown[]>(t: T, m: [...T]) { t = m }` reports `Type '[T]' is not
assignable to type 'T'`. That single absence explains the fixture's whole "Relations
involving variadic tuple types" section (where pristine errors at `y = x` and we error at
`x = y`, i.e. the rows are not merely extra, they are the MIRROR), its `keyof [...T]`, its
spread-argument arity rows and the entire `curry` inference section. The 31st row is TS7019,
a `noImplicitAny` row, i.e. S4. **This is TypeScript 4.0's variadic tuples: queue it as a
feature (CHK.20), do not attempt it as a bounded rule.**

**AND THE OTHER MEASUREMENT PRODUCT: A DIAGNOSTIC ARM FOR THE CONVENTION, PLUS THE GUARD IT
NEEDED.** `pristine_sweep.py --tsc-strict-default` injects tsc's OWN `strict: false` default
where a fixture names no strict-family directive: **318 -> 272, 47 rows removed, 1 added**.
**Its first run was WRONG in the reassuring direction and it is round 941's defect (c) one
directive over: an ABSENT directive is evidence only where the CASE FILE is present.**
`strictPropertyInitialization` has no case file in this clone and **20 TS2564 in its own
baseline** — pristine plainly had the flag ON — so the unguarded arm deleted four GENUINE
false positives, exactly (CHK.10)'s, and would have reported that queue item as an artefact.
Guarded on `po.case_index()`, and read together with a second control (*does pristine's own
baseline carry that CODE anywhere in the fixture* — zero over seventeen uninitialised class
fields is conclusive), the answer is: **the convention is 46 rows, not 42; (CHK.10) is
CONFIRMED GENUINE; and 4 of my 89 belong to (CHK.13).**

**(CHK.16), THE FIX, AND IT IS TWO DEFECTS IN ONE GATE.** `checkConstraintsInStatements`
pushed a declaration's own type parameters into scope for a `FunctionDeclaration` (round 82 —
whose comment names this very defect, "would see `I<T>` resolve T to the global `class T` if
any … and emit FP TS2344"), for a type ALIAS only when the body was an `ImportType` (B98a's
narrow gate), and for a class or an interface never. So a parameter SHADOWED by a same-named
file-level type resolved to that OUTER type and was judged against the callee's constraint.
`withDeclTypeParamScope` is now the one site and all three branches use it, heritage clauses
included. **BOTH directions were wrong, so the fix ADDS diagnostics as well as removing
them**: `type Loose<Q> = Box<Q>` with `interface Box<S extends string>` was silent and now
reports TS2344 as pristine does — and over 611 pristine fixtures that gained NO ours-only row.
The type RESOLUTION path never had the defect (`getTypeFromTypeReference` answers `Wrap<"x">`
correctly with the same interface in scope), which is what bounds the change to the walker.

**TWO METHOD NOTES WORTH MORE THAN THE TWO ROWS.** (i) **The shadowing declaration is 138
LINES BELOW the alias in pristine's fixture, so every hand-written reduction is silent** —
the bisection that found it deleted the file's TAIL (a `0:291` prefix is clean; `0:291` plus
lines 300-310, which is where `interface A` lives, is the two rows). A "reduce it and probe"
loop would have concluded there was nothing there. (ii) **The first cut fixed only the ALIAS
branch and a pin written as a REGRESSION GUARD went RED** — "an interface declaration's own
type parameter was never affected" — which is how the class/interface half was found. A
regression guard that fails is a finding, not a nuisance.

**ABLATION — 4 arms, one mistake at a time, from the sha256-verified snapshot
`d1ae7270…`, diffed against the SNAPSHOT and never with `git checkout`, every arm asserting
`ran 13`** (`scripts/round943-ablate.py`):

| arm | the injected mistake | red | what it proves |
|---|---|---:|---|
| A1 | `withDeclTypeParamScope` becomes a no-op — the whole fix | **10** | every shadow pin and every gained true positive |
| A2 | the BOUND: the parameters are pushed but their CONSTRAINTS are not resolved | **5** | includes the NEGATIVE CONTROL, which no other arm reddens — it separates "the parameter is in scope" from "its constraint is honoured", and a pin that only asserted silence could have been satisfied by the parameter resolving to anything at all |
| A3 | the CLASS branch loses the scope again | **2** | the class pin and the heritage-clause pin, uniquely |
| A4 | the INTERFACE branch loses the scope again | **2** | the two interface pins, uniquely |

A3's and A4's red sets are disjoint from each other and are the state the first cut shipped.
**Two of the 13 pins are green in all four arms and are recorded as REGRESSION GUARDS rather
than claimed as discriminators** (round 807): the concrete-violating-argument pin and the
function-declaration pin.

**GATES.** Suite **15,235 -> 15,248 / 0 failures / 3 skipped** (+13 = exactly this round's
pins), **NO corpus baseline moved**. **8-profile before/after grid**, profiles enumerated by
`tsconfig.json` and refused below 8, the BEFORE arm reused from round 942 under a sha256
IDENTITY assertion (`6eda7d97…` is both HEAD's `Checker.kt` and the source that produced
those captures — a stronger provenance claim than a rebuild): **added=0 removed=0 on ALL
EIGHT**. **630-fixture PRISTINE sweep: 318 -> 316 rows over 79 fixtures, ZERO regressed,
pristine-only 775 -> 775** (no true positive lost). `cost_gate.py` moves one family —
`mapped.keyed` **+0.14%**, `typeNode.bypassed` **+0.03%**, `typeNode.cacheable`/`cacheHits`
**-0.01%**: the constraint resolutions the walker now performs INSIDE the pushed scope, a
reached-ness proof, rebaselined in the same commit. `huge_methods.py --fail-over 0` green on
**all six** module class dirs (751 / 48 / 20 / 14 / 7 / 2 classes scanned — the counts differ
per module, which is the positive control that each census saw its own dir). No
`spine*EnterNode` changed, so `spine_closure_audit.py` does not apply.

**PROVENANCE, stated because this round's whole method is hash-verified arms**: the grid, the
sweep and the ablation were all measured at `Checker.kt` sha256 `d1ae7270…`, and the
COMMITTED source differs from that arm by exactly ONE COMMENT CHARACTER — a KDoc's "140
lines below" corrected to "138" (309 − 171) — plus one test-method NAME carrying the same
number. Reversing that single line reproduces `d1ae7270…` byte for byte, which is the check
that says so rather than a claim that it is harmless.

**NEXT.** The bucket's remaining tractable work is (CHK.17) the default-target lib set —
5 rows here but a systematic real-world FP, and the same shape as round 941's TS18028 —
then (CHK.18) and (CHK.19). (CHK.9) and (CHK.10) are unchanged and still the smallest
genuine-FP items, with (CHK.10) now CONFIRMED. **The four MODELLING items (68 rows) are the
honest answer to "what is left in the largest bucket": features, scheduled as such.**

**Round 942 (2026-08-19) — (CHK.11) + (CHK.12): THE TWO NARROWING FALSE-POSITIVE FAMILIES,
AND THEY SHARE ONE CAUSE ONE LEVEL DOWN — **tsc's `isMatchingReference` compares references
by SYMBOL and ours compares the path STRINGS `getReferencePath` builds.** 16 of the
narrowing bucket's 27 ours-only rows closed; the sweep 334 -> 318 with ZERO fixtures
regressed and a true positive GAINED.**

**THE PRISTINE-vs-OURS TABLE** (our binary over pristine's OWN inputs, `(file, line, code)`
differenced against pristine's own `.errors.txt`):

| fixture | ours-only before | after | pristine-only before | after |
|---|---:|---:|---:|---:|
| `typeGuardNarrowsIndexedAccessOfKnownProperty1` (CHK.11) | 11 | **0** | 0 | 0 |
| `typeGuardsWithInstanceOfBySymbolHasInstance` (CHK.12) | 5 | **0** | 8 | **7** |
| `controlFlowInstanceofWithSymbolHasInstance` (CHK.12's other fixture) | 7 | 7 | 0 | 0 |

**DID THE TWO FAMILIES SHARE A CAUSE? YES, and it is worth stating as one sentence**: both
are the compiler asking "is this the same reference / the same instance type" through a
representation that cannot express what tsc's does. (CHK.11) is the path STRING; (CHK.12) is
the missing `[Symbol.hasInstance]` leg plus an `instanceof` that filters a UNION candidate
with the STRUCTURAL relation where tsc uses the NOMINAL one. They were established as
separate before either was designed — the queue said one was `getTypeOfElementAccess` and the
other `resolveInstanceOfRhsType`, and both turned out to be true.

**(CHK.11), FOUR mechanisms.** `singleLevelDiscriminantSegment` — the switch's discriminant
reader accepts `name[seg]` beside `name.seg`. `getTypeOfElementAccess` flow-narrows its UNION
RECEIVER, the B1.1 gate its dotted twin has always had. `getReferencePath` NORMALISES an
identifier-spellable string index onto the DOTTED segment, because the fixture mixes both
spellings inside ONE expression (`s[0]["sub"].under["shape"]`) — a non-spellable index
(`"dash-ok"`, `0`) keeps round 461's bracket encoding, so no path can collide with a dotted
segment and `flowPathRoot`/`pathPrefixOf` already split on `[`. And `requiredEnumSwitchKeys`
+ `paramMemberChainType` accept an element-access discriminant and a MULTI-segment receiver,
which is the two TS2366 "function lacks ending return statement".

**A FIFTH MECHANISM WAS WRITTEN, MEASURED INERT AND REMOVED — and the ablation is what found
it.** Narrowing the access's own union RESULT (the 17.34d half, the exact symmetric line to
`getTypeOfPropertyAccess`) looked obviously right and reddened **NONE** of the round's 21
pins; no probe could be built where it fires either, because the `typeof` guard does not
reach an element-access reference at all (`if (typeof h[0] === "string") { … h[0] }` still
reports the declared `string | number` with it in place). A flow walk on a hot path with no
consultation that can observe it is CLAUDE.md's round-887 shape, so it went — which also gave
back part of the round's `narrow.walks` cost.

**(CHK.12), and TWO rules read off PRISTINE's own baseline rather than guessed.** `instanceof`
now asks the RHS type for a `[Symbol.hasInstance]` method whose return is a non-`asserts` TYPE
PREDICATE over parameter 0, and uses its target — round 838's `instanceTypeOfConstructorValue`
named that leg as its one deliberate omission, and it is what answers the three shapes
`prototype` and the construct signatures cannot (a GENERIC construct signature, SEVERAL
construct signatures, one returning `any`). (i) **A usable predicate DECIDES**: `value is any`
narrows NOTHING and must not fall through to the construct signature — pristine reports
`string | F` at its own lines 142/143 with a perfectly good `new (): any` beside it. (ii) **An
`instanceof` stays `checkDerived = true` even when the candidate came from a predicate**, so a
UNION candidate is DISTRIBUTED and its narrow-down direction is the NOMINAL base-chain test,
not assignability: `C1 | A` narrowed by `C1 | C2` is **C1** (`A` is structurally a supertype
of BOTH candidates, so the assignability form mapped it onto the whole union and then reported
`bar1` missing on `C2`), while `B0 | string` narrowed by `D1 extends B0` is still **D1**.
SCOPED to a union candidate, so round 425's single-candidate arm — whose `tracker instanceof
SymbolTrackerImpl` case depends on the assignability form — is byte-identical.

**AND THE QUEUE ENTRY WAS WRONG ABOUT ITS OWN SECOND FIXTURE, WHICH IS THE THIRD ROUND RUNNING
THAT RE-MEASURING FIRST HAS PAID FOR.** (CHK.12) was written as "11 rows over two fixtures";
`controlFlowInstanceofWithSymbolHasInstance` is **7 rows and SIX of them are a PARSER GAP** —
`abstract new (...args: any) => infer U` — with TS1005/TS1068/TS1128 cascading into
TS2355/TS2564/TS2304. Its one genuine narrowing row is the missing `instanceof` INTERSECTION
tail. Both queued with their three-line probes as **(CHK.14)** and **(CHK.15)**; (CHK.14) also
records a SECOND, separable defect the same probe found — the NON-abstract
`T extends (new (…) => infer U) ? U : never` parses and then reports TS2304 for `U`, i.e. an
`infer` inside a PARENTHESIZED extends clause does not publish its name.

**ABLATION — 9 arms, ONE MISTAKE AT A TIME, from a sha256-VERIFIED snapshot, diffed against
the SNAPSHOT and never with `git checkout`** (`scripts/round942-ablate.py`; every arm asserts
`ran 21`):

| arm | the injected mistake | red | what it proves |
|---|---|---:|---|
| A1 | the switch discriminant reader refuses a BRACKET segment | 1 | the numeric-index pin — and ONLY that one, because a SPELLABLE index is already normalised onto the dotted branch by A3's mechanism |
| A2 | an element access stops narrowing its UNION RECEIVER | 3 | the two element-access reads and the numeric-index pin |
| A3 | a spellable string index stops normalising onto the dotted segment | 3 | exactly the three MIXED-SPELLING pins |
| A4 | the exhaustive-switch key reader refuses an ELEMENT-ACCESS discriminant | 2 | both TS2366 pins |
| A5 | the exhaustiveness receiver walk goes back to ONE dotted segment (round 470) | 1 | the DEEP mixed-spelling TS2366 pin |
| A9 | an element access stops narrowing its own union RESULT | **0** | **nothing — which is why that mechanism was deleted rather than shipped** |
| A6 | the `[Symbol.hasInstance]` leg is removed | 7 | every CHK.12 positive plus the `any` bound |
| A7 | a UNION candidate stops being distributed nominally | 2 | the `C1 \| A` pair |
| A8 | the leg's BOUND: a wide predicate target falls THROUGH instead of deciding | 1 | the `value is any` bound pin, alone |

**TWO ARMS REFUSED ON THEIR FIRST RUN AND BOTH REFUSALS WERE THE HARNESS DOING ITS JOB**, not
noise: A4's `if (false && expr is …)` mistake DROPS Kotlin's smart cast, so the arm stopped
COMPILING and read `ran 0`; A9's anchor (`if (raw is Type.Union && getReferencePath(expr) …)`)
occurs VERBATIM in `getTypeOfPropertyAccess` too, and the driver refused a 2-hit anchor rather
than ablating the wrong function. Both were re-run with corrected mistakes. **Four pins are
green in all nine arms and are recorded as REGRESSION GUARDS rather than claimed as
discriminators**: the dotted-discriminant control, the dynamically-indexed bound, the
non-first-parameter bound, and the no-`hasInstance` construct-signature control.

**A THIRD TRAP, MEASURED: `o["a"]` where `a?: string` is `string` in this compiler, not
`string | undefined`** — optionality is a symbol attribute and is not folded into the property
type (CLAUDE.md already says so about the relation; it is equally true of the READ). A
"negative control" written on that shape passed vacuously and had to be replaced with a
MULTI-SEGMENT mixed-spelling shape, which is what pins the normalisation.

**GATES.** Suite **15,214 -> 15,235 / 0 failures / 3 skipped** (+21 = exactly this round's
pins), **NO corpus baseline moved**. **8-profile before/after BINARY grid** (two
sha256-verified arms, profiles enumerated by `tsconfig.json` and refused below 8):
**added=0 removed=0 on ALL EIGHT.** **630-fixture PRISTINE sweep, both arms: 334 -> 318 rows
over 81 -> 79 fixtures, ZERO fixtures regressed, pristine-only 776 -> 775** (a true positive
GAINED). `cost_gate.py` moves one family — `narrow.walks` **+0.15%** and `narrow.memoServed`
**+0.14%**, the element-access receiver's new flow reads, with `typeOfExpr.calls` **-0.01%**
because a narrowed receiver resolves its member without the union fold: a REACHED-NESS proof,
rebaselined in the same commit. `huge_methods.py --fail-over 0` green on **all six** module
class dirs. No `spine*EnterNode` changed, so `spine_closure_audit.py` does not apply.

**NEXT.** (CHK.14) the `abstract new` / parenthesized-`infer` parser gaps (6 rows measured
plus the 17-row `infer X extends` family and the 33-row `using` family they join); (CHK.15)
the `instanceof` intersection tail. (CHK.9) and (CHK.10) are unchanged and still the smallest
genuine-FP items; (CHK.13) remains an owner decision.

**Round 941 (2026-08-19) — (CHK.8): THE 630-FIXTURE PRISTINE SWEEP, TRIAGED — AND THE
ROUND'S FIRST PRODUCT IS THAT **THE INSTRUMENT WAS WRONG ABOUT 30% OF ITS OWN ROWS**.
121 of round 940's 397 OURS-ONLY rows were the sweep's configuration, not the compiler's
answers, and all three defects failed in the reassuring direction — a phantom divergence
looks exactly like a real one. Two false-positive families are then closed, both measured
against pristine's own fixtures, both invisible to the corpus by construction.**

**THE BUCKET TABLE — 373 ours-only rows over 84 fixtures at `967c2e53`, every row
classified, the rules in `scripts/pristine_triage.py` and the evidence in
`docs/pristine-divergences.md`.**

| rows | % | bucket | cause class | exemplar |
|---:|---:|---|---|---|
| 89 | 23.9 | FP — type system / inference | **genuine FP** | `variadicTuples1` TS2322 x15 + TS2345 x14 |
| 59 | 15.8 | HARNESS — jsx configuration | **harness artefact** | `tsxLibraryManagedAttributes` TS2874 x27 |
| 59 | 15.8 | PARSER GAP — unsupported syntax | **cascade** | `usingDeclarations*` (33), `infer X extends` (17) |
| 42 | 11.3 | CONVENTION — strict-by-default | **deliberate divergence** | `keyofAndIndexedAccess` TS2564 x17 |
| 31 | 8.3 | PARSER RECOVERY on a malformed fixture | **cascade** | `mappedTypeProperties` (23) |
| 27 | 7.2 | FP — computed keys / declaration emit | **genuine FP** | `indexSignatures1` TS1268 x12 |
| 27 | 7.2 | FP — narrowing / control flow | **genuine FP** | `typeGuardNarrowsIndexedAccessOfKnownProperty1` (11) |
| 26 | 7.0 | **FIXED — private-identifier target gate** | **genuine FP** | `strictPropertyInitialization` TS18028 x16 |
| 13 | 3.5 | **FIXED — super-call statement scan** | **genuine FP** | `derivedClassSuperProperties` TS2376 x13 |

**Cause-class totals: genuine FP 182 (48.8%) · cascade 90 (24.1%) · harness artefact 59
(15.8%) · deliberate convention 42 (11.3%).** 39 of the 182 are closed here. **NO
ACTIVE-BASELINE ROW APPEARS ANYWHERE IN THE TABLE** — the population is by construction the
fixtures the generated suite does not gate, which is exactly why the corpus is green while
these rows exist.

- **DEFECT (a), AND IT IS THE ONE WITH A LESSON: `extract_sources` FELL BACK TO
  `tests/cases` WHENEVER NO *EXACT* `<stem>.js` BASELINE EXISTED — i.e. FOR EVERY
  MULTI-VARIATION CASE — AND THE CASE FILE STILL CARRIES THE `// @target:` HARNESS
  DIRECTIVES THAT tsc STRIPS.** Every line number was then the baseline's PLUS the directive
  count, so the sweep reported every row of those fixtures as a divergence in BOTH
  directions: 27 of 630 fixtures, `commonMissingSemicolons` alone **42** phantom rows and
  `classUsedBeforeInitializedVariables` **6** (which read as a tidy six-row TS2729 FP family
  and is in fact six of pristine's own rows shifted by two). **The guard is now an ALIGNMENT
  ORACLE**: each reconstructed input is compared line-for-line against pristine's own
  `==== file ====` annotation and the verdict recorded per fixture. One fixture is
  `misaligned` today (`classMemberWithMissingIdentifier2`); every other row above has been
  read against a source pristine itself would recognise.
- **DEFECT (b): DIRECTIVES WERE READ FROM THE *EXTRACTED* TEXT, AND THE `.js` BASELINE
  ECHOES THE SOURCE VERBATIM *WITHOUT* THEM.** So a fixture whose source came from a
  baseline recovered ZERO directives: `decoratorsOnComputedProperties` read **10** phantom
  TS1166 for want of `@experimentalDecorators`, `jsxElementType` **46 -> 22** for want of
  `@jsx`. The fix refuses to re-derive the mapping in Python — `TsConfigLoader` routes every
  `compilerOptions` key through the SAME `applyDirective` the corpus harness uses, so the
  fixture's directives are copied into the scratch tsconfig VERBATIM and unknown keys are
  ignored by `applyDirective` itself.
- **DEFECT (c): A MISSING CASE FILE LEFT NO TARGET AT ALL, WHERE THE BASELINE'S OWN
  `(target=es2015)` SUFFIX IS THE LAST SURVIVING RECORD OF IT.** `derivedClassSuperProperties`
  compiled at the esnext default, where tsc's TS2376 rule is switched off entirely by
  `emitStandardClassFields` — i.e. the round's largest FP family was being measured under a
  configuration in which the reference emits nothing.
- **AND ROUND 940's FORCED `"strict": false` IS GONE**, which is what surfaced the
  strict-by-default bucket (**+97 rows**). **373 is therefore NOT comparable to 397
  row-for-row** — same commit, different instrument; only same-instrument arms are.

**THE TWO FIXES.**

- **(1) TS2376 — A `super` CALL NEED NOT BE FIRST.** Ours required `super()` to be the first
  non-prologue statement. tsc (`checkConstructorDeclaration` +
  `nodeImmediatelyReferencesSuperOrThis` + `isThisContainerOrFunctionBlock`) walks the
  constructor's own statement list until EITHER the super call OR the first statement that
  IMMEDIATELY references `this`/`super`, and only the second outcome is an error — so any
  number of statements may precede `super()` as long as none touches `this` in the
  constructor's own `this` scope. The walk stops at an arrow function (arrows evaluate
  later: `const getThis = () => this` before `super()` is legal), a function
  declaration/expression, a property declaration, and at a method-like BODY.
  **THE BOUND IS THE INTERESTING HALF AND THE FIRST CUT GOT IT WRONG**: a method-like body
  stops the walk, its NAME does not, so `get [this.propName]() {}` before `super()` IS still
  TS2376 (pristine `derivedClassSuperProperties` lines 281 and 323). A cut that skipped
  every member name lost both rows **while every "this is no longer an error" pin stayed
  green** — only the sweep's PRISTINE-ONLY column showed it. Measured: 13 -> 0 ours-only and
  pristine-only 20 -> 19, i.e. a true positive GAINED.
- **(2) TS18028 — THE PRIVATE-IDENTIFIER GATE READS THE TARGET THE USER *ASKED FOR*.**
  `CompilerOptions.target` defaults to `ES3` while tsc's `getEmitScriptTarget` defaults an
  unset `target` to the latest standard, so a raw `target <= ES5` read made every `#field`
  in a project with no `target` an error. The gate is now
  `options.targetExplicitlySet && options.target <= ScriptTarget.ES5` — **not**
  `effectiveTarget`, which maps an explicit ES5 up to ES2015 and would drop the true
  positive an explicit `@target: es5` must keep. **The corpus is structurally blind to both
  sides**: `usesUnsupportedOption` skips every explicit es3/es5 config, so no ACTIVE baseline
  exercises this gate at all.

- **FOUR-ARM ABLATION, ONE MISTAKE AT A TIME, TWO ARMS PER FIX** (`scripts/round941-ablate.py`,
  from a sha256-verified snapshot, never `git checkout`, diffed against the SNAPSHOT, each
  arm asserting **ran 21**): **A1 8 red / A2 2 / A3 1 / A4 2, four DISJOINT red sets.** A2 is
  the one worth reading — it is exactly the defect the first cut shipped, and the pin that
  catches it exists only because the sweep found it. **Eight of the 21 pins are green in all
  four arms and are recorded as REGRESSION GUARDS rather than claimed** (round 807): the
  parenthesized-`super()` and prologue-directive pins (A1 keeps both mechanisms), the three
  TS2376 positive controls, the no-initialized-property control, and the two "an explicit
  ES2015/ESNext target is silent" pins. **The driver's first run REFUSED all four arms at
  `ran 0`** — Gradle takes no `|` alternation in a single `--tests` — which is round 856's
  law paying for itself: without the ran-count assertion that would have printed as four
  clean sweeps.
- **GATES.** Suite **15,193 -> 15,214 / 0 failures / 3 skipped** (+21 = exactly this round's
  pins), **NO corpus baseline moved**. `cost_gate.py` **+0.00% on all 20 counters**
  including `output.errors 46` — expected, since neither fix is reachable from tsc's own
  sources. `huge_methods.py --fail-over 0` green on **all six** module class dirs. The
  **8-profile before/after BINARY grid** (`scripts/round941-grid.sh`, two sha256-verified
  arms) **added=0 removed=0 on ALL EIGHT**. The **630-fixture sweep, both arms in the same
  driver: 373 -> 334 rows over 84 -> 81 fixtures, ZERO fixtures regressed, pristine-only
  777 -> 776.** No `spine*EnterNode` changed, so `spine_closure_audit.py` is not applicable.
- **NEXT.** Five entries added with their bucket's evidence — **(CHK.9)** index-signature
  parameter types (12 rows, the largest single-code FP family left), **(CHK.10)** definite
  assignment through a late-bound element access (4), **(CHK.11)** element-access
  discriminant narrowing (11), **(CHK.12)** `Symbol.hasInstance` narrowing (11), and
  **(CHK.13)** the strict-by-default convention (42), which is an OWNER decision rather than
  a fix. `(CHK.5)` still continues at (c); `(CHK.7)` still keeps (ii) and (iv).

**Round 940 (2026-08-19) — (CHK.7)(i)+(iii) AND (CHK.5)(f): THREE PRISTINE DIVERGENCES,
ALL FALSE POSITIVES, ALL CLOSED — AND THE ROUND'S PRODUCT IS THAT **ROUND 939's QUEUE
ENTRY WAS WRONG ABOUT TWO OF ITS OWN FOUR ROWS, IN THE DIRECTION THAT DECIDES WHAT TO
BUILD**: (iii) is **3** extra lines and not 25, and (iv) is a false **NEGATIVE** in **one**
scan, not a false positive in "the duplicate scans". Re-measuring each row against pristine
BEFORE touching anything is what caught both, and it cost one command per fixture.**

**THE TABLE. Every row read from PRISTINE offline (`scripts/pristine_oracle.py --fixture
… --extract`), our binary run over pristine's OWN input, and every ours-only row re-read at
a SECOND target before being believed — round 939's method note, paid for twice.**

| # | fixture | PRISTINE | OURS before | OURS after | verdict |
|---|---|---|---|---|---|
| (i) | `symbolProperty1` | TS2454 only (a check we lack) | + **TS1117 ×2** | — | **FP, CLOSED** |
| (i) | `symbolProperty2` | (silent) | + **TS1117 ×2** | — | **FP, CLOSED** |
| (i) | `symbolProperty3` | TS2464 ×3 (we lack) | + **TS1117 ×2** | — | **FP, CLOSED** |
| (ii) | `symbolProperty52` | TS2339 ×2 on the KEY | **TS2741 `'[Symbol.nonsense]'`** + 1 of the 2 TS2339 | unchanged | **FP, RE-QUEUED — modelling** |
| (iii) | `privateNameDuplicateField` | 83 rows | 50 rows, **3 ours-only** (106:13, 156:13, 381:20) | 47 rows, **0 ours-only** | **FP, CLOSED** |
| (iv) | `numericStringNamedPropertyEquivalence` | 7 rows | 4 rows, **0 ours-only**, **3 MISSING** | unchanged | **FN, RE-QUEUED** |
| (f) | `dynamicNamesErrors` / `duplicateIdentifierComputedName` / `assignmentCompatWithEnumIndexer` / `symbolProperty21` | names the key AS WRITTEN | `Property 'p'` | `Property '[K]'` | **FORM, CLOSED** |

- **(i) A SPELLING IS NOT A NAME.** `evaluateComputedPropertyName` named a reference key
  `__@computed:<text>`, so two occurrences of the same DYNAMIC key were a duplicate to us
  and not to pristine. **The corpus structurally cannot see it**: the three
  `duplicateObjectLiteralProperty_computedName*` fixtures ARE active gates and we pass
  them, and pristine's own negative control
  (`duplicateObjectLiteralProperty_computedNameNegative1`) uses two DIFFERENT identifiers,
  which a spelling key satisfies exactly as a value key would.
  **THE FIX IS AN ABSTAIN, AND ITS BOUND IS THE WHOLE DESIGN.** A blanket "abstain unless
  late-bindable" regresses `duplicateObjectLiteralProperty_computedName3` — an ACTIVE gate
  whose keys are `[keys.n]` / `[keys.E1.A]` through an `import * as keys`, which pristine
  binds through the key's TYPE and round 935's SYNTACTIC resolver deliberately cannot
  follow across a file. So the namer abstains ONLY when the key's own declaration is IN
  HAND and late binding still refused it (`var s: symbol`, `var s = Symbol`, a widened
  `let`), and keeps the pre-940 spelling otherwise. **Unknown keeps the old answer**, so
  the refusal can only remove a duplicate we have EVIDENCE is not one. Arm A2 is that
  bound and it reddens the corpus gate, on the nose.
  **AND THE EVIDENCE THAT PRISTINE BINDS BY VALUE IS A PAIR, NOT A FIXTURE**: `[s]`/`[s]`
  with `var s: symbol` is SILENT and `[n]`/`[n]` with `const n = 1` is TS1117 — same
  spelling shape, opposite answers, so the discriminator is the key's VALUE. That pair is
  what licenses the new `{ 1: 1, [n]: 0 }` pin, which no single fixture shows.
- **(iii) AN ACCESSOR FOLLOWED BY A PROPERTY IS REPORTED AT THE PROPERTY ALONE, AND THE
  MECHANISM IS tsc's `PropertyExcludes = None`.** The class scan split on whether the
  accessor pair was COMPLETE and flagged the whole group otherwise; pristine flags only the
  PROPERTY whenever every accessor precedes it, complete pair or not. A property declared
  LAST never trips tsc's BINDER duplicate check (its excludes mask is empty), so only the
  checker's per-class scan reports it — and that one reports at the current member alone.
  The model reproduces **every one of `privateNameDuplicateField`'s 83 rows** and both
  halves of `duplicateClassElements` (`public x; get x; set x` → all three; `get x2; set x2;
  public x2` → only `x2`).
- **(f) TS2741 NAMES A LATE-BOUND MEMBER AS WRITTEN**, wired at `formatPropertyDisplayName`
  — the one renderer the missing-property emitters already route the symbol through — via
  round 938's `computedKeyWrittenText`, which answers null for a spelling it cannot
  reproduce exactly, so a message can never carry a name the source does not contain.

- **THE INSTRUMENT IS NOW A SCRIPT: `scripts/round940_pristine_sweep.py`.** Round 939 ran
  its sweep by hand and committed only the oracle. This selects fixture stems by an
  explicit, quotable ERE (a computed member key in MEMBER position — **630 stems, 611 with
  recoverable source**), materialises pristine's own input, honours the case's `// @target`,
  and differences (file, line, code). **BOTH ARMS, one binary each: 74 → 71 fixtures with
  ours-only rows, 403 → 397 rows, ZERO fixtures regressed**, and the six rows removed are
  exactly `symbolProperty1/2/3`. (`privateNameDuplicateField` is outside that population —
  its members are `#foo`, not computed keys — and was measured separately, 3 → 0.)
  Its 403 is NOT comparable to round 939's 23: a different, ~2× larger population.
- **8-PROFILE GRID, two sha256-VERIFIED binaries** (`scripts/round940-grid.sh`, which also
  runs the sweep per arm): **added=0 removed=0 on ALL EIGHT**, 46/46/46/46/46/46/46/94.
  Nothing on tsc's own sources moves in either direction.
- **FIVE-ARM ABLATION, one mistake at a time** (`scripts/round940-ablate.py`, from a
  sha256-verified snapshot, never `git checkout`), each arm asserting **ran 69** so a dead
  build reads as a failure rather than as a clean sweep. TWO ARMS PER FIX BY DESIGN — one
  removes the fix, one removes its BOUND — because a "this is now silent" pin cannot tell a
  correct refusal from a disabled check.

| arm | the injected mistake | red | what it proves |
|---|---|---|---|
| A1 | (i) removed — the reference arms name by SPELLING again | **4** | the three abstain pins + the value-vs-spelling discriminator |
| A2 | (i)'s BOUND removed — abstain for every unresolved key | **2** | the cross-file control **and `duplicateObjectLiteralProperty_computedName3`, the ACTIVE corpus gate** |
| A3 | (iii) removed — accessor+property flags the whole group | **7** | the four "at the FIELD alone" pins, plus `duplicateClassElements` + `gettersAndSettersErrors` (the pre-existing complete-pair rule this branch absorbed) |
| A4 | (iii)'s ORDER clause removed — always flag only the property | **4** | the mirrored-order positive controls + `duplicateClassElements` |
| A5 | (f) removed — the missing member is named by its VALUE again | **3** | the three written-key pins, and NOT the two negative controls |

- **UNDISCRIMINATED PINS, RECORDED AS SUCH RATHER THAN CLAIMED.** Green in all five arms,
  i.e. regression guards and not discriminators: `a repeated LATE-BOUND computed key is
  still a duplicate`, `a repeated LITERAL computed key is still a duplicate`, `a repeated
  well-known symbol key is still a duplicate` (all three survive a spelling key too), `two
  different unresolvable keys are not a duplicate`, `a late-bound key does not collide with
  a different member`, `two getters are still TS2300 at BOTH`, `a getter followed by a
  method is still TS2300 at BOTH`, `a clean accessor pair is silent`, and (f)'s two
  negative controls (which would discriminate an arm applying the renderer to a
  NON-computed name — no such arm was run).
- **GATES.** Suite **15,168 → 15,193 / 0 failures / 3 skipped** (+25 = exactly this round's
  pins), **NO corpus baseline moved**. `cost_gate.py` moves ONE family —
  `globals.lookups` **+0.05% (+372)** and `globals.misses` +0.05% — which is the
  late-binding namer now being consulted at the object-literal duplicate scan, i.e. a
  REACHED-ness proof for (i) on the compiler profile rather than noise; rebaselined in the
  same commit. `huge_methods.py --fail-over 0` green on **all six** module class dirs
  (core 751 classes, api 14, client 20, daemon 7, cli 2, project 48 — round 909's
  `--classes` blindness answered by naming each). No `spine*EnterNode` changed, so
  `spine_closure_audit.py` is not applicable.
- **NEXT.** `(CHK.5)` continues at **(c)**; **(f) is done**. `(CHK.7)` keeps (ii) and (iv),
  both re-measured and re-scoped below.

**Round 939 (2026-08-19) — (CHK.6): THE COMPUTED-KEY FAMILY, RE-JUDGED AGAINST *PRISTINE*.
NO CODE. THE ROUND'S PRODUCT IS AN INSTRUMENT AND A VERDICT: **`tools/tsgo-7.0.2/lib/tsc`
IS THE ONLY REFERENCE THAT *RUNS* HERE AND IT IS NOT THE REFERENCE WE DIFF AGAINST — BUT
THE PRISTINE ORACLE WAS ON DISK ALL ALONG, IN THE CORPUS'S OWN `tests/baselines/reference`,
AND IT SAYS ROUNDS 933-938 LANDED NOTHING THAT PRISTINE CONTRADICTS.** Round 938 found the
two references parting on its own territory and had to redesign around it; the worry this
round was commissioned to settle is that rounds 933-937 had tsgo-only evidence for rows no
corpus baseline covers. Measured: **the corpus protects far more of the family than the
notes claim** — `dynamicNames`, `dynamicNamesErrors`, `duplicateIdentifierComputedName`,
`destructuredLateBoundNameHasCorrectTypes`, `checkDestructuringShorthandAssigment2`, the
three `duplicateObjectLiteralProperty_computedName*` and **7 of the 10 TS2717 baselines in
the entire corpus** are ACTIVE, byte-exact gates sitting directly on these decisions.

- **THE INSTRUMENT: `scripts/pristine_oracle.py`.** Given a code, a source pattern or a
  fixture name it finds the PRISTINE baselines that exercise the shape and prints what
  pristine tsc emitted — and labels every hit **ACTIVE / not generated**, i.e. whether the
  suite is already gating it. Four things make it work rather than merely exist.
  (i) **An ABSENT `.errors.txt` beside a present case is evidence**: it means pristine was
  SILENT, which is exactly the half a "does tsc complain about this?" question needs.
  (ii) **`tests/cases` in this clone is INCOMPLETE** — 6,537 files against 9,055 error
  baselines, whole conformance directories missing — so a pattern search over it alone
  misses silently; the search runs over the `.js` and `.errors.txt` baselines too, which
  echo every input verbatim, via `grep` (0.2 s over 53,049 files; the pure-Python version
  timed out at 120 s and is why the sweep is shelled out).
  (iii) **`--extract DIR` writes pristine's own input back out**, so our compiler can be run
  over exactly what pristine tsc saw — that is what turned this round from a reading
  exercise into a measurement.
  (iv) **`.types` / `.symbols` baselines answer naming questions with no diagnostic at
  all**: `computedPropertyNames10_ES6.types` records `` [`hello bye`]() `` as the member
  `"hello bye"` and `` [`hello ${a} bye`]() `` as `[x: string]`, which is round 933's whole
  landed rule and its negative control, read straight off pristine.

**THE CLASSIFICATION.** Every landed behavioural decision of rounds 933-938, one row each.
**PRISTINE-CONFIRMED 22 · CORPUS-SILENT 10 · tsgo-ONLY 1 · PRISTINE-DIVERGENT 1**, and the
one divergence is a message FORM the round that landed it had already recorded as open.

| # | the landed decision | verdict | evidence |
|---|---|---|---|
| 933.1 | a backtick-quoted computed key NAMES a member | **PRISTINE-CONFIRMED** | `computedPropertyNames10_ES6.types` names the member `"hello bye"`; `11/13/16_ES6` carry the key on a class and on accessors and pristine is SILENT — and so are we, measured |
| 933.2 | a SUBSTITUTING template names NO member | **PRISTINE-CONFIRMED** | same `.types` baseline: it contributes `[x: string]`, an index signature |
| 933.3 | `classMemberNameText` DELEGATES (the two sites cannot drift) | CORPUS-SILENT | an internal-consistency rule; its observable (TS2322 + TS2339 in one compile) has no pristine fixture |
| 934.1 | the excess check acts on a computed key spelling one fixed name, in every position | **PRISTINE-CONFIRMED, ACTIVE** | `checkDestructuringShorthandAssigment2` |
| 934.2 | a BARE numeric key `{ 7: 2 }` is excess-checked | CORPUS-SILENT | swept all 92 TS2353 baselines: none names a bare numeric key |
| 934.3 | the excess message names the key AS WRITTEN, delimiters kept | **PRISTINE-CONFIRMED ×3** | `'[k]'` (`checkDestructuringShorthandAssigment2`, ACTIVE, squiggle over the written key), `'[Symbol.toPrimitive]'` (`symbolProperty21`), **`'"resolution-mode"'`** (`nodeModulesImportTypeModeDeclarationEmitErrors1`) — the last is the `'"zz"'` row, a quoted key rendered WITH its quotes |
| 934.4 | the NUMERIC-index-signature absorption guard | CORPUS-SILENT | |
| 934.5 | `[E.P]` is not named by `computedSymbolKey`'s invented name | CORPUS-SILENT | superseded at its source by 935's ordering |
| 934.6 | a substituting template stays OUT of the excess check | **PRISTINE-CONFIRMED** | the `.types` index-signature evidence above |
| 935.1 | a `const`/`declare const`/annotated-`let` literal key LATE-BINDS (supply side silent) | **PRISTINE-CONFIRMED, ACTIVE ×2** | `dynamicNames`: `export const o1 = { [c4]: 1, … }` then `export const o2: T0 = o1` — silent; `destructuredLateBoundNameHasCorrectTypes`: `const named = "prop"` as a destructuring key |
| 935.2 | an ENUM member's VALUE late-binds | **PRISTINE-CONFIRMED, ACTIVE** | `duplicateObjectLiteralProperty_computedName2`: `[E1.A]`/`[E1.A]` and `[E2.B]`/`[E2.B]` are duplicates in pristine, i.e. the key binds to the member's value |
| 935.3 | a numeric key names its VALUE, not its source text | **PRISTINE-CONFIRMED** | `computedPropertyNames10_ES6.types`: `[0]()` is the member `0`, `[""]()` the member `""`; `duplicateObjectLiteralProperty_computedName1` (ACTIVE) makes `1` / `[1]` / `[+1]` / `"1"` one name and `"+1"` another |
| 935.4 | the five REFUSALS (widened `let`, genuine union, plain `symbol`, bare TP, ambient value-less enum member) | **PRISTINE-CONFIRMED in kind** | `computedPropertyNames5/6/8/14/15/17/51_ES6`: pristine refuses a non-literal key outright (TS2464) rather than binding it |
| 935.5 | the EXCESS direction for the same keys | CORPUS-SILENT | |
| 935.6 | the name must be a function of the PROGRAM, not of the pass | CORPUS-SILENT | an invariant, not an output |
| 935.7 | the language service does NOT treat `[K]` as a member position | **tsgo-only** | read from tsgo's LSP; no baseline expresses a rename's extent |
| 936.1 | QUALIFIED heads late-bind (`NS.K`, nested, dotted, merged, enum-in-namespace) | **PRISTINE-CONFIRMED, ACTIVE** | `dynamicNames`: `namespace N { export const c2 = "a"; export interface T4 { [N.c2]: number … } }` plus `class T5 implements T4` — silent |
| 936.2 | the TEMPLATE-LITERAL TYPE head | CORPUS-SILENT | |
| 936.3 | the TYPE-ALIAS hops | CORPUS-SILENT | |
| 936.4 | a WELL-KNOWN `[Symbol.X]` key is EXCESS, named as written | **PRISTINE-CONFIRMED, RE-RUN** | `symbolProperty21` — pristine `TS2353 '[Symbol.toPrimitive]'` at (10,5); our binary on the extracted fixture emits the same code, position and key name |
| 936.5 | the `string`-typed key display (`{}` vs `{ [L]: number; }`) is a recorded GAP | **PRISTINE-CONFIRMED as a gap** | `indexSignatures1` prints `{ [sym]: number; }` for the same mechanism |
| 936.6 | the embedded lib gains `IterableIterator[Symbol.iterator]()` | **PRISTINE-CONFIRMED** | tsc's own `lib.es2015.iterable.d.ts` declares exactly that member (`build/generated/real-lib`) |
| 937.1 | an interface / class / type literal DECLARES and TYPES its own `[K]` member | **PRISTINE-CONFIRMED, ACTIVE** | `dynamicNames` declares `[c0]`/`[c1]`/`[s0]` on an interface, a `declare class` and a type alias, in a module and in a namespace |
| 937.2 | a computed METHOD name reaches `getTypeOfSymbolWorker` | CORPUS-SILENT | |
| 937.3 | `classMemberNameText` (the TS2339 firewall) knows late-bound keys | CORPUS-SILENT | |
| 937.4 | the `implements` / transitive-name FP fixes, incl. the two NUMERIC rows | **PRISTINE-CONFIRMED, ACTIVE** | `dynamicNames`: `declare class T13 implements T2 { a: number; 1: string; [s2]: boolean }` and `declare class C { static 1: string; static [s2] }` — silent; this baseline is what MOVED mid-round-937 and forced the sibling walkers into that commit |
| 937.5 | `checkComputedLiteralKeyMembers` RETRACTS | **PRISTINE-CONFIRMED, ACTIVE** | `dynamicNamesErrors` must carry its TS2717 exactly once (arm A7) |
| 937.6 | TS2741 for a missing late-bound member is named **`'p'`** | **PRISTINE-DIVERGENT (FORM)** | pristine names the key AS WRITTEN wherever it names one — `'[E.A]'` (`assignmentCompatWithEnumIndexer`), `'["a"]'` (`duplicateIdentifierComputedName`, ACTIVE), `'[c1]'` (`dynamicNamesErrors`, ACTIVE), `'[Symbol.toPrimitive]'` — so `'[K]'` is the pristine answer. Verified live at HEAD. **No baseline covers the exact shape, which is why the suite is green**; recorded by round 937 against tsgo, and pristine AGREES with tsgo here. Queued as (CHK.5)(f) |
| 938.1 | the member map is FIRST-WINS | **PRISTINE-CONFIRMED, ACTIVE ×7** | 7 of the 10 TS2717 baselines in the whole corpus are generated gates: `classWithDuplicateIdentifier`, `duplicateClassElements`, `gettersAndSettersErrors`, `interfaceDeclaration1`, `methodSignatureHandledDeclarationKindForSymbol`, `reassignStaticProp`, `dynamicNamesErrors` |
| 938.2 | both duplicate SCANS learn the computed namer | **PRISTINE-CONFIRMED, ACTIVE** | `duplicateIdentifierComputedName`: TS2300 `'["a"]'` |
| 938.3 | `memberNameIsBinderVisible` — a LATE-BOUND duplicate is TS2717 alone | **PRISTINE-CONFIRMED, ACTIVE** | `dynamicNamesErrors`; this is the row that made round 938 |
| 938.4 | the class scan's `group.drop(1)` (the SECOND declaration only) | **PRISTINE-CONFIRMED, ACTIVE ×2** | `classWithDuplicateIdentifier`, `duplicateIdentifierComputedName` |
| 938.5 | B357 retracts its duplicate TS2717 | **PRISTINE-CONFIRMED, ACTIVE** | `dynamicNamesErrors` (arm A7) |
| 938.6 | a WELL-KNOWN-symbol key stays invisible to duplicate detection | **PRISTINE-CONFIRMED as a deliberate FN** | `uniqueSymbolsPropertyNames`: pristine emits TS1117 ×2 + TS2300 ×5 there and we emit none — the refusal's price, now measured rather than assumed |

- **AND THE STRONGEST EVIDENCE IS NOT IN THE TABLE, BECAUSE IT IS A NEGATIVE: OUR BINARY WAS
  RUN OVER EVERY UNGATED PRISTINE FIXTURE THAT CARRIES A COMPUTED MEMBER KEY.** `--extract`
  materialises pristine's own input; the sweep ran `MainKt --noEmit --listAll` on **300** of
  them (326 stems, 26 with no recoverable source) and differenced (line, code) against
  pristine's baseline. **277 of 300 emit NOTHING pristine does not.** All 23 that do were
  read: **four are the pre-existing divergences below, and NOT ONE of the remaining nineteen
  is attributable to rounds 933-938** — they are checks this compiler does not implement, in
  other families entirely (`using` declarations and the private-modifier grammar, which we do
  not parse; index-signature PARAMETER types, which is (CHK.5)(e)'s own axis; super-call
  ordering; a `declare global { interface SymbolConstructor }` augmentation that does not
  merge; `Symbol.hasInstance` narrowing; a discriminant union with a `never` member; and
  module resolution in a multi-file extraction). Everywhere else we emit FEWER diagnostics —
  TS2464 / TS2564 / TS2699 / TS2804 / TS2411 / TS2454 — and where we do emit, the positions
  match pristine exactly.
- **FOUR PRISTINE DIVERGENCES *WERE* FOUND, AND ALL FOUR ARE OLDER THAN THIS FAMILY. THE
  PROOF IS THE DIFF, NOT AN OPINION**: `git diff 0d38189f..HEAD` (the pre-933 parent) over
  `Checker.kt` mentions `getPropertyName` / `getPropertyKeyName` / `evaluateComputedPropertyName` /
  `checkObjectLiteralDuplicates` **zero times**, and `PrivateIdentifier` zero times. They are
  queued as (CHK.7) with the fixture that shows each.
- **THE ONE WORTH READING IS THE TS1117 NAMER, BECAUSE ITS NEGATIVE CONTROL IS VACUOUS.**
  `evaluateComputedPropertyName` names an identifier key `__@computed:<text>` — a SPELLING,
  not a value — so two occurrences of the same DYNAMIC key are a duplicate to us and not to
  pristine: `var s: symbol; var x = { [s]: 0, [s]() {}, get [s]() {} }` (`symbolProperty1`,
  `symbolProperty3`) is **TS1117 ×2, a false positive**. The corpus cannot see it: the three
  `duplicateObjectLiteralProperty_computedName*` fixtures ARE active gates and we pass them,
  and pristine's own negative-control fixture for this shape
  (`duplicateObjectLiteralProperty_computedNameNegative1`) uses **two different** identifiers
  (`[x]`, `[y]`), so it is satisfied by a spelling key as well as by a value key. Same namer,
  opposite direction, is round 938's recorded gap (b2)(iii).
- **METHOD NOTE, PAID FOR TWICE: A FIXED SCRATCH `tsconfig` MANUFACTURES FALSE POSITIVES.**
  `uniqueSymbols` read two OURS-ONLY rows at `target: es2015` and one of them vanished at
  `esnext` — the missing `AsyncIterableIterator` (TS2583) cascading into a TS2322. Every
  OURS-ONLY row in this round was re-read at a second target before being believed, and the
  pristine case's own `// @target` directive is the thing a next sweep should honour.
- **GATES — NAMED AS NOT APPLICABLE RATHER THAN REPORTED GREEN.** This round adds one Python
  script and documentation; **no Kotlin changed**, so there is nothing to compile, nothing for
  `cost_gate.py` to count, no method to grow past 8,000 bytecodes and no binary to grid. The
  suite therefore stands at round 938's **15,168 / 0 failures / 3 skipped** — the tree is
  byte-identical to `022cdd42` in every compiled module. What WAS exercised is the compiled
  binary at HEAD, **300 times**, against 300 pristine fixtures.
- **NEXT.** `(CHK.5)` continues at **(c)**, unchanged. Two entries were added by this round:
  **(CHK.5)(f)** — the TS2741 key name — and **(CHK.7)**, four pre-existing pristine
  divergences with a fixture apiece.

**Round 938 (2026-08-19) — (CHK.5)(b): A DUPLICATE MEMBER DECLARATION. TWO SEPARABLE
DEFECTS, ONE COMMIT, AND THE ROUND'S PRODUCT IS THAT **ROUND 937's PREMISE WAS HALF WRONG,
AND MEASURING IT FIRST IS WHAT SAID WHICH HALF.** (CHK.5)(b) was written as "our member map
is last-wins for every duplicate spelling … so `interface Dup { p: number; [K]: string }`
went from 0 diagnostics to 1 of the wrong code". The first half is exactly right. The
second — the implied "and we do not report duplicates" — is not: **this compiler already
emitted TS2300 x2 + TS2717 for a plain `interface I { p: number; p: string }`, byte-identical
to tsc, and does so for a type literal, a class, an enum, two getters, a numeric member name
and a class property-vs-method too.** What was missing was narrower and both halves are now
closed: the surviving TYPE, and the fact that no COMPUTED spelling reached the duplicate
scans at all.**

- **STEP 1 WAS tsc 7.0.2, DIRECT, ON 32 SCRATCH PROJECTS — 22 shapes, then 10 more once the
  first pass showed where the line was.** Every row was read from
  `tools/tsgo-7.0.2/lib/tsc --noEmit -p .` and from our own `MainKt --noEmit --listAll` on
  the SAME directory, before anything was written; every duplicate carries a
  `const probe: 0 = x.p` so the **surviving type is read out of the TS2322 message**,
  independently of the diagnostic — the two defects are separable and had to be measured
  separately.

| the shape | tsc | ours BEFORE | ours AFTER |
|---|---|---|---|
| `interface I { p: number; p: string }` | TS2300 x2 + TS2717, `p` is `number` | **same diagnostics**, `p` is `string` | full parity |
| `interface I { p: number; p: number }` | TS2300 x2, `number` | same | unchanged — control |
| `class C { p: number; p: string }` | TS2717, `number` (+TS2300 x1 pristine / x2 tsgo) | TS2300 x1 + TS2717, `string` | `number`; TS2300 x1 kept — **pristine** |
| `type T = { p: number; p: string }` | TS2300 x2 + TS2717, `number` | same diagnostics, `string` | full parity |
| `interface I { p: number }` x2 blocks, differing | TS2717, `number` | silent, `string` | `number`; **TS2717 still missing** |
| `interface I { p: number; p: string; p: boolean }` | 3x TS2300 + 2x TS2717, `number` | same, `boolean` | full parity |
| `interface I { 1: number; 1: string }` | TS2300 x2 + TS2717, `number` | same, `string` | full parity |
| `interface Dup { p: number; [K]: string }` | TS2300 x2 + TS2717, `number` | **silent, `string`** | TS2717, `number` — TS2300 refused, see below |
| `interface Dup { ["p"]: number; [`p`]: string }` | TS2300 x2 + TS2717, `number` | **silent, `string`** | TS2300 x2 + TS2717, `number` |
| `class C { p: number; [K]: string }` | TS2717, `number` | silent, `string` | TS2717, `number` |
| `class C { ["p"]: number; [`p`]: string }` | TS2300 x2 (pristine x1) + TS2717 | silent | TS2300 x1 + TS2717 — **pristine** |
| `interface I { [K]: number; [K2]: string }`, both `"p"` | TS2717 | TS2717 | TS2717 **exactly once** — the retraction |
| `class C { static p: string; p: number }` | `c.p` `number`, `C.p` `string` | `c.p` `number`, `C.p` **`number`** | unchanged — **still open**, `staticMembers` |
| `class B { p }` + `class D extends B { p }` | the override wins | the override wins | unchanged — control |
| interface / class METHOD OVERLOAD set | silent | silent | unchanged — control |
| `get p` + `set p`, index signature + named, identical merge | silent | silent | unchanged — controls |
| `interface I { p: number; p(): void }` | TS2300 x2 | **silent** | **still open** — the scan sees properties only |
| `const o = { p: 1, [K]: 2 }` | TS1117 | **silent** | **still open** — TS1117 has its own namer |
| `interface I { p: number; p?: number }` | +TS2717 `number \| undefined` | no TS2717 | **still open** |
| `declare const L: string; interface I { [L]; [L] }` | an INDEX SIGNATURE | silent | unchanged — (CHK.5)(e) |
| `interface I { [Symbol.iterator](); [Symbol.iterator]() }` | silent | silent | unchanged — control |

- **(i) FIRST-WINS, AT BOTH MEMBER-BUILDING SITES.** tsc reaches it in the BINDER —
  `setValueDeclaration` replaces an existing `valueDeclaration` only across an ambient /
  assignment-declaration / module-kind boundary, so two same-kind property declarations
  leave the FIRST installed — and **pristine tsc's own TS2717 text is the statement of the
  rule**: `classWithDuplicateIdentifier`'s baseline says "Property 'c' must be of type
  'number', but here has type 'string'". Round 937's spurious TS2322 is this defect and not
  a computed-key one; the same wrong type was already there for a plain `p; p`, which is why
  the row belonged here rather than in (a).
- **THE GUARD IS THREE-WAY NARROW AND THE ABLATION SAYS EVERY CLAUSE IS LOAD-BEARING.**
  OWN members only — `members` is **PRE-POPULATED with the base types' members** before the
  own-member loop runs, so the obvious `members[name] != null` test would silently delete
  every property OVERRIDE in the program (arm A2 does exactly that and reddens the control);
  PROPERTY-vs-PROPERTY only, so a property beside a method or an accessor keeps today's
  resolution, both already parity; and at equal STATIC-ness only, because a static and an
  instance member of one name are LEGAL and share this one map until the `staticMembers`
  dual-population is consumed (arm A3).
- **(ii) THE DUPLICATE SCANS ARE B451's LAW ONE SITE FURTHER ON.** They are AST scans
  sitting beside the member-BUILDING sites round 937 levelled onto `declaredMemberName`, and
  they carried an older, narrower copy of the same `when`: the class scan knew `["a"]` and
  `[0]`, and the interface scan had **no computed arm at all** and looked only at
  `PropertyDeclaration`s. Both now ask one namer (`duplicateScanComputedKey`), so the
  NO-SUBSTITUTION TEMPLATE spelling and every late-bound key reach them.
- **AND THIS IS WHERE THE TWO REFERENCES PART — THE ROUND'S SECOND FINDING, AND IT COST A
  DESIGN.** The obvious reading of tsc 7.0.2 is that a late-bound duplicate is TS2300 x2 +
  TS2717; the first build did that. **`dynamicNamesErrors`' PRISTINE baseline says
  otherwise**: `interface T0 { [c0]: number; 1: number }` with `const c0 = "1"` is a
  duplicate BY NAME and gets **nothing at all**, while its late-bound sibling
  `interface T3 { [c0]: number; [c1]: string }` gets TS2717 and **no TS2300**. The mechanism
  is exact and it is why the split is principled rather than curve-fitted: **TS2300/TS2687
  are the BINDER's duplicate checks and a LITERAL computed name is bound statically, while a
  LATE-BOUND one is resolved by the CHECKER and only ever reaches the re-declaration check.**
  `memberNameIsBinderVisible` is that line. Arm A8 — following tsgo — reddens
  `dynamicNamesErrors` itself, which is the measurement rather than an argument. The same
  parting decides the neighbouring row: for a CLASS property-vs-property duplicate pristine
  tsc flags only the SECOND declaration (`classWithDuplicateIdentifier`,
  `duplicateIdentifierComputedName`) and tsgo flags both, so the walker's existing
  `group.drop(1)` was already right and was left alone. **CLAUDE.md's standing directive —
  diff against ORIGINAL tsc, do not chase tsgo divergences — is what this round exercised,
  and rounds 933-937 all used tsgo as the sole reference; where a pristine baseline exists it
  outranks it.**
- **ONE CO-EMISSION, RETRACTED RATHER THAN AVOIDED.** With the interface scan naming
  late-bound keys, B357's `checkComputedLiteralKeyMembers` reaches the same TS2717 at the
  same span for the sub-population it was built for (both members `[<identifier>]` where the
  identifier is a top-level `const` bound to a literal) — measured as an exact duplicate line.
  It now RETRACTS before it emits, CLAUDE.md's rule for a dedicated walker a general rule
  catches up with, keyed on (code, file, start): a TS2717 belongs to one property DECLARATION,
  so two cannot legitimately share a name-node start. Arm A7 shows it: without the retraction
  `dynamicNamesErrors` reports its line twice.
- **DELIBERATELY REFUSED, AND THAT REFUSAL IS THE PROFILES' FIREWALL.** The scans do NOT ask
  `getMemberName`'s `[Symbol.X]` arm, so a WELL-KNOWN-symbol key is exactly as invisible to
  duplicate detection as it was before — the eight profiles carry **57 `[Symbol.iterator](`**
  members, and admitting them would be a new duplicate population nothing in this round
  motivated.
- **PINS +22, one class, `DuplicateMemberDeclarationTest`** (15,146 -> 15,168 / 0 failures /
  3 skipped, summed over all six modules with an XML parser): eight surviving-TYPE rows, six
  negative controls that are precisely what a wrong fix breaks (the inherited override,
  static-vs-instance, property-vs-method, an interface overload set, a get/set pair, an index
  signature, two identical merged blocks), and eight diagnostic rows including the two
  refusals. **No corpus baseline moved**, in the shipped state or at any point.
- **NINE-ARM ABLATION** (`scripts/round938-ablate.py`), each arm applied to and restored from
  a sha256-verified on-disk snapshot, each diffed against the SNAPSHOT rather than HEAD, each
  asserting `ran 127` — and the filter deliberately carries the GENERATED `dynamicNames*`
  corpus classes, which is what makes A7 and A8 legible.

| arm | the mistake | red | what it uniquely shows |
|---|---|---|---|
| A1 | the member map goes back to LAST-WINS | **7** | the whole first-wins family at once |
| A2 | the guard consults the WHOLE member map, not own members | 2 | the inherited OVERRIDE control — nothing else sees it |
| A3 | the guard drops its STATIC-ness clause | 1 | static-vs-instance, and only it |
| A4 | the TYPE LITERAL site goes back to last-wins | 1 | the type-literal row — the two sites are separable |
| A5 | the CLASS scan's computed arm reverts to its pre-938 `when` | 2 | both class duplicate rows, and only those |
| A6 | the INTERFACE scan loses its computed arm | 2 | both interface computed rows, and only those |
| A7 | B357 stops retracting | 2 | the ONE-TS2717 pin **and `dynamicNamesErrors`** |
| A8 | the binder-visibility rule is dropped (i.e. tsgo's answer) | **4** | three late-bound rows **and `dynamicNamesErrors`** |
| A9 | the written-key renderer answers the BOUND key | 1 | the TS2717 message clause — **subsumed, see below** |

- **EIGHT OF THE NINE HAVE A UNIQUELY-THEIR-OWN RED. THE NINTH IS RECORDED RATHER THAN
  CLAIMED (round 807), AND THE REASON IS STRUCTURAL RATHER THAN AN OVERSIGHT.** A9's display
  can only be observed THROUGH a diagnostic that its own namer must first produce, so every
  namer arm (A5, A6, A8) deletes the diagnostic and subsumes A9's failure by construction —
  there is no fixture in which the message is wrong and the diagnostic is present on an
  A6-ablated binary. Its distinctive signal is the MESSAGE clause of a pin two other arms
  redden for a different reason; that is the honest statement, and no pin is credited with
  discrimination it does not have.
- **GATES.** Suite **15,146 -> 15,168 / 0 failures / 3 skipped**, no corpus baseline moved.
  `cost_gate.py` **+0.00% on all 20 counters**, `output.errors` unchanged at 46 — which is the
  EXPECTED answer here and is read as a control rather than a green light (round 876): the
  change adds one `HashMap.put` per property to a scan that already ran, and the profiles
  contain no duplicate member. `huge_methods.py --fail-over 0` clean on **all six** module
  class dirs (core 751 classes, 0 over, largest 7,702 — `resolveInterfaceMembersCore` grew to
  4,982 and is nowhere near the cliff). The **8-profile before/after BINARY grid**
  (`scripts/round938-grid.sh`, profiles enumerated by `tsconfig.json` and refused below 8):
  all eight **`added=0 removed=0`**, 46/94 diagnostics unchanged. `spine_closure_audit.py` not
  run: nothing on the spine changed.
- **NEXT.** `(CHK.5)` continues at **(c)** — the cross-file and class-static keys. Five gaps
  measured this round with tsc's answer and recorded there rather than pinned (round 765): a
  MERGED-interface TS2717, an interface property-vs-METHOD TS2300 pair, TS1117 for a
  late-bound object-literal key, the required-vs-OPTIONAL TS2717, and `C.p` reading the
  INSTANCE member when a static and an instance share a name — that last one is the
  unfinished `staticMembers` dual-population and not a duplicate rule at all.

**Round 937 (2026-08-18) — (CHK.5)(a): THE DECLARATION SIDE OF LATE-BOUND COMPUTED KEYS.
ONE MISSING CAPABILITY, SIX EXTRACTION SITES, AND THE ROUND'S PRODUCT IS THAT **LEVELLING
ONE SITE OF THE B451 FAMILY MAKES A *PRE-EXISTING* DRIFT IN ITS SIBLINGS REACHABLE — AND
THE SIBLING THAT BREAKS IS NOT THE ONE THAT SHARES THE FEATURE, IT IS THE ONE WHOSE TWO
HALVES READ DIFFERENT REPRESENTATIONS.** `checkImplementsClauses` and
`classMemberNamesTransitive` compare a class's AST member names against a TARGET built
from the resolved TYPE; both read `(name as? Identifier)?.text`. That was harmless while
the type side dropped a computed key too, and became TS2420 / TS2741 false positives the
moment it stopped — **including two that contain no computed key at all**
(`interface I { 1: string }` + `class C implements I { 1: string }`, and the same through
a `static 1`), which measured as false positives at HEAD and had simply never been reached.**

- **STEP 1 WAS tsc 7.0.2, DIRECT, on nine scratch projects — 40 rows, both directions,
  read from `tools/tsgo-7.0.2/lib/tsc --noEmit -p .` and from our own `MainKt --noEmit
  --listAll` on the SAME directory before anything was written.**

| the shape | tsc 7.0.2 | ours BEFORE | ours AFTER |
|---|---|---|---|
| `interface I { [K]: number }`, `i.p` into a `string` | TS2322 | **silent — FN** | TS2322 |
| `class C { [K]: number }`, `c.p` | TS2322 | **TS2339 — FP** | TS2322 |
| `type T = { [K]: number }`, `t.p` | TS2322 | **TS2339 — FP** | TS2322 |
| `interface I { [K](): number }`, `i.p()` | TS2322 | **silent — FN** | TS2322 |
| `class C { [K](): number }`, `c.p()` | TS2322 | **silent — FN** | TS2322 |
| `interface I { get [K](): number }`, `i.p` | TS2322 | **silent — FN** | TS2322 |
| `class C { static [K]: number }`, `C.p` | TS2322 | **TS2339 — FP** | TS2322 |
| `const x: I = {}` (a required `[K]`) | TS2741 `'[K]'` | **silent — FN** | TS2741 `'p'` |
| `const x: I = { [K]: 1 }` — the key on BOTH sides | silent | **TS2353 `'[K]'` — FP** | silent |
| `const x: I = { p: 1 }` | silent | **TS2353 `'p'` — FP** | silent |
| `[CE.P]` / `[SE.Q]` / `[NS.K]` / `` [TT] `` / `[N]`=`1e3` / an alias chain | TS2322 | **silent — FN** | TS2322 |
| a member INHERITED through an interface or class `extends` | TS2322 | **silent / FP** | TS2322 |
| an interface inside a namespace, keyed on that namespace's const | TS2322 | **silent — FN** | TS2322 |
| `class C implements I` where both spell `[K]` | silent | silent | silent — closed by A6 |
| `interface I { 1: string }` + `class C implements I { 1: string }` | silent | **TS2420 — FP** | silent |
| `interface T { 1: string }` + `declare class C { static 1: string }`, `t = C` | silent | **TS2741 — FP** | silent |
| `interface J { [k: string]: number }` (an index signature) | — | unchanged | unchanged — control |
| `{ [P in keyof T]: number }` (a mapped type) | — | unchanged | unchanged — control |
| `let LW = "p"` / a literal UNION / `[obj.k]` as the key | TS2322 (an INDEX SIGNATURE) | silent | **still open** — a different gap |
| `declare const S: unique symbol`, `interface I { [S]: number }`, `= {}` | TS2741 `'[S]'` | silent | **still open**, (CHK.5)(d) |
| `[C.B]` (a class `static readonly`) / `[IK]` imported from a FILE | TS2322 | **TS2339 / FP** | **still open**, (CHK.5)(c) |
| `interface Dup { p: number; [K]: string }` | TS2300 x2 + TS2717 | silent | **TS2322** — see below |

- **THE ROW THAT MADE THIS ONE ROUND RATHER THAN TWO IS THE KEY ON BOTH SIDES.** Round 936
  predicted that naming a `unique symbol` key on the literal side alone would INVERT the
  defect; measured, the inversion was **already live for a plain const** — round 935 named
  `[K]` for the object literal, the interface named nothing, and
  `const x: I = { [K]: 1 }` was reported as the excess key `'[K]'` on a program both
  compilers accept. Naming one side of a member comparison is not half a fix; it is a new
  defect, and it is why (CHK.5)(d) still must land on both sides in ONE commit.
- **THE SIX SITES, and the fourth failed in the quietest way of all.** (1) the
  class/interface member loop (property/method/get/set); (2) `getTypeFromTypeLiteral`,
  which re-spelled the `when` a third time and knew nothing about a computed key at all;
  (3) `classMemberNameText`, the TS2339 firewall's namer (`lookupInstanceMemberInResolvableChain`
  answers "definitely no such member" through it, which is the class FP);
  (4) **`getTypeOfSymbolWorker`'s method branch, which returns `anyType` for a name it
  cannot read — so the member WAS declared (a missing one was correctly TS2741) and typed
  `any`, i.e. the member existed and its return type did not**; (5) `checkImplementsClauses`
  and (6) `classMemberNamesTransitive`, the two AST-vs-TYPE comparisons above.
- **`checkComputedLiteralKeyMembers` NOW RETRACTS BEFORE IT EMITS** — CLAUDE.md's rule for
  a dedicated walker a relation rule catches up with. With `[c0]` bound, the general
  relation finds the same member incompatible and emits the same code at the same span,
  differing only in the property NAME (`'1'` against the walker's `'[c0]'`, which is what
  tsc prints). Keyed on (code, fileName, start, message), never on the position alone.
- **THE ONE NEW DIVERGENCE, STATED RATHER THAN HIDDEN: a DUPLICATE.**
  `interface Dup { p: number; [K]: string }` is TS2300 x2 + TS2717 in tsc, which keeps the
  FIRST type; our member map is last-wins for **every** duplicate spelling — measured at
  HEAD for a plain `p: number; p: string` and for `["p"]` alike, both of which produce the
  same spurious TS2322 — so the late-bound key merely joins a population that was already
  wrong. **The program is an ERROR program in tsc and we moved from 0 diagnostics to 1**,
  of the wrong code. Not pinned (round 765: a known-open gap is a countdown), recorded as
  (CHK.5)(b)'s territory, and NOT a `logicalParityDivergence` — no baseline moved.
- **THE STRING-INDEX-SIGNATURE ROWS ARE A DIFFERENT GAP AND MUST NOT BE ABSORBED HERE.**
  A key typed `string`, a literal union, or `obj.k` gives tsc's INTERFACE (and class) a
  string index signature rather than a named member — `interface I { [LW]: number }` makes
  `i.p` a `number` in tsc — and one of those (`class C2 { [LW]: number }`, `c2.p`) is still
  a TS2339 false positive here. Late binding must refuse them; closing them is index-signature
  modelling, now recorded in (CHK.5).

- **PINS +38, one class, `LateBoundDeclarationKeyTest`** (15,108 -> 15,146 / 0 failures /
  3 skipped, summed over all six modules with an XML parser): sixteen READ rows, four
  SUPPLY rows, seven refusals that are tsc's own answer (including the `unique symbol`
  row pinned as the AGREEMENT both compilers hold today rather than as the gap), five
  sibling-walker rows with their positive control, and **three cross-pass determinism
  pins** — round 935's core pin re-asked of a table that, unlike an object literal's type,
  is BUILT ONCE AND CACHED, so an ambient-dependent name would freeze whichever pass
  touched the type first (round 776's law).

- **TEN-ARM ABLATION** (`scripts/round937-ablate.py`), each arm applied to and restored
  from a sha256-verified on-disk snapshot, each diffed against the SNAPSHOT rather than
  HEAD, each asserting `ran 91`, and all three late-binding pin classes running.

| arm | the mistake | red | what it uniquely shows |
|---|---|---|---|
| A1 | the declaration-side namer loses late binding entirely | **22** | the pre-937 boundary — every declaration row at once |
| A2 | the TYPE LITERAL site reverts to its own `when` | **2** | the type-literal rows, and only those |
| A3 | a computed METHOD name no longer reaches `getTypeOfSymbolWorker` | **2** | the two method-return-type rows, and only those |
| A4 | `classMemberNameText` refuses a late-bound key | **5** | every CLASS row — the TS2339 firewall |
| A5 | the member loop's METHOD and ACCESSOR arms only | **3** | the method + get-accessor rows |
| A6 | `checkImplementsClauses` reverts to Identifier-only | **2** | the implements rows, including the numeric one |
| A7 | `classMemberNamesTransitive` reverts to Identifier-only | **2** | the class-STATIC rows through B175 |
| A8 | the walker stops retracting the relation's duplicate | **1** | `reported ONCE`, and only it |
| A9 | the member loop's PROPERTY arm alone | **17** | the sites are separable — 17 of A1's 22 |
| A10 | `fnsClassMemberNames` (namespace-local) reverts | **0** | **REDUNDANT GIVEN ITS SIBLING**, with the reason |

- **THE ZERO ARM WAS INTERROGATED, NOT ASSUMED (round 936's law), AND THE ANSWER IS THE
  THIRD POSSIBILITY THE PROTOCOL NAMES.** A10 first read 0 with NO pin covering that
  walker, which is round 902's dead-arm/blind-pin ambiguity; a dedicated pin was added
  **plus a positive control that the walker is REACHED** — and the control was verified by
  a PassLab census naming `checkFuzzyNamespaceThisReturns` as the emitter, not by assuming
  it. It still reads 0, and the mechanism is exact: **`fnsRequired` reads the INTERFACE
  side from the AST too, Identifier-only, so both halves of that walker refuse the key and
  mask each other.** That walker is AST-vs-AST SYMMETRIC and never drifted; the levelling
  is kept because it makes the class side a SUPERSET (suppression-only) and is the half a
  future widening of `fnsRequired` will need — recorded as redundant, not claimed.

- **GATES.** Suite **15,108 -> 15,146 / 0 failures / 3 skipped**; **no corpus baseline
  moved in the shipped state**, and the two that moved mid-round (`dynamicNames`,
  `dynamicNamesErrors`) are the whole reason the sibling walkers and the retraction are in
  this commit — each was judged against tsc for that exact fixture and each was a false
  positive or a duplicate, never a lost diagnostic, so no `logicalParityDivergence` was
  needed. `cost_gate.py` GREEN, largest move **+0.04%** (`typeNode.cacheable` /
  `typeNode.bypassed`; `mapped.keyed` +0.03%, `globals.lookups` +0.01%) — the profiles'
  `[SyntaxKind.X]` and `[Symbol.iterator]` DECLARATION-side member resolutions, rebaselined
  with `--update` in the same commit, and `output.errors` unchanged at 46.
  `huge_methods.py --fail-over 0` clean on **all six** module class dirs (core 751 classes,
  0 over, largest 7,702). The **8-profile before/after BINARY grid**
  (`scripts/round937-grid.sh`): all eight `added=0 removed=0`, 46/94 diagnostics unchanged
  — a RESULT rather than a control, because those profiles carry 57 `[Symbol.iterator](`
  interface members and 32 `[SyntaxKind.X]:` keys per member name.
  `spine_closure_audit.py` not run: nothing on the spine changed.

- **NEXT.** `(CHK.5)` continues at **(b)** — TS1117 / TS2300 / TS2717 for a late-bound
  duplicate, which this round gave a second reason to want; then **(c)** the cross-file and
  class-static keys; then **(d)** the `unique symbol` type, on both sides in one commit.
  Newly recorded there: the **index-signature** rows, which are neither (a) nor (d).

**Round 936 (2026-08-18) — (CHK.4): THE QUALIFIED, TYPE-ANNOTATION AND WELL-KNOWN-SYMBOL
ROUTES. THREE CAPABILITIES, SIX DEFECTS, ONE COMMIT — AND THE ROUND'S PRODUCT IS THAT
**A NODE THIS PARSER DOES NOT STRUCTURE ANSWERS A NAME QUESTION WITH A CONFIDENT EMPTY
STRING**: `TemplateLiteralType` carries `templateSpans = emptyList()` and the whole raw
source slice in `head.rawText` (B65.1), so the obvious `templateSpans.isEmpty()` test is
TRUE for a SUBSTITUTING template as well and `head.text` is `""` — a member name matching
nothing, which is strictly worse than refusing, and it reached the excess check as a real
member on the first build.**

- **STEP 1 WAS tsc 7.0.2, DIRECT, on twelve scratch projects.** Every row below was READ
  from `tools/tsgo-7.0.2/lib/tsc --noEmit -p .` and from our own `MainKt --noEmit --listAll`
  on the SAME directory. Every capability is a false POSITIVE one way and a false NEGATIVE
  the other, which is round 935's signature for one missing capability — and extending the
  table before designing turned the queue's "three one-line residues" into three families.

**Part A — the qualified and annotation routes** (`interface Req { p: number }` for supply,
`interface Opt { p?: number }` for excess).

| the key | tsc 7.0.2 | ours BEFORE | ours AFTER |
|---|---|---|---|
| `[NS.K]`, `export const K = "p"` | silent | **TS2741 — FP** | silent |
| `[NS.K]` excess (`KZ = "zz"`) | TS2353 `'[NS.KZ]'` | **silent — FN** | TS2353 `'[NS.KZ]'` |
| `[NS.In.IK]` nested namespace | silent | **TS2741 — FP** | silent |
| `[DD.EE.Z]`, `namespace DD.EE` | silent | **TS2741 — FP** | silent |
| `[M.K]`, a MERGED namespace's 2nd block | silent | **TS2741 — FP** | silent |
| `[NS.CE.P]` const enum in a namespace | silent | **TS2741 — FP** | silent |
| `[NS.SE.Q]` plain enum in a namespace | silent | **TS2741 — FP** | silent |
| `[NS.D]`, `export declare const D: "p"` | silent | **TS2741 — FP** | silent |
| `[After.K]`, namespace declared BELOW | silent | **TS2741 — FP** | silent |
| ``[TT]``, ``declare const TT: `p` `` | silent | **TS2741 — FP** | silent |
| `[A]`, `type LP = "p"` | silent | **TS2741 — FP** | silent |
| `[A]`, `type LP2 = LP` (a chain) | silent | **TS2741 — FP** | silent |
| `[A]`, ``type TL = `p` `` | silent | **TS2741 — FP** | silent |
| `[NS.LW]`, `export let LW = "p"` | TS2741 | TS2741 | TS2741 — refused, parity |
| ``[T2]``, ``declare const T2: `p${string}` `` | TS2741 | TS2741 | TS2741 — refused, parity |
| `[A]`, `type LU = "p" \| "q"` | TS2741 | TS2741 | TS2741 — refused, parity |
| `[L]`, `declare const L: string` | TS2741 `{ [L]: number; }` | TS2741 `{}` | unchanged — FORM |
| `[C.B]`, `class C { static readonly B = "p" }` | silent | **TS2741 — FP** | **still open**, (CHK.5)(c) |
| `[IK]` imported from another FILE | silent / TS2353 | **FP / FN** | **still open**, (CHK.5)(c) |

- **THE `NS.K` FP IS GONE, AND IT WAS NEVER ONE ROW.** The queue called it "a namespace
  const, the cheapest of the four"; measured, the same missing capability owns a nested
  namespace, a dotted `namespace A.B` declaration, a MERGED namespace's second block, and
  a const-or-plain ENUM member declared inside a namespace — five more FPs and their five
  excess twins, all closed by one descent.
- **THE DESCENT IS SYNTACTIC FOR ROUND 935's REASON, RESTATED ONE LEVEL UP.** The head
  could have been resolved through `currentFileLocals`, which is what
  `resolveEnumSymbolFileLevel` does — but that map is AMBIENT (round 911: not the same map
  in every pass), and round 935 measured what an ambient input costs a member name. Walking
  `ModuleBlock` statements is a function of the PROGRAM. Merging comes free because every
  statement of a level is scanned rather than the first match, and use-before-declaration
  comes free because a scan has no order. The ONE symbol-table consult left is the enum
  leaf, and it is not a choice: an auto-numbered member has no initializer to read, so its
  value exists only in the binder's frozen tables (`enumMemberEntries`, which also knows
  the one member with NO value — round 746's ambient non-`const` rule, which round 935
  measured to be tsc's own answer).
- **THE `string`-KEY ROW IS A DISPLAY DIVERGENCE AND I AM CALLING IT NEITHER "form-only"
  NOR A BUG.** tsc prints the source type as `{ [L]: number; }` (its literal gets a STRING
  INDEX SIGNATURE) and we print `{}`; the code, the span and the top-level fact are
  identical. Under `docs/logical-parity.md` § 2 that is NOT form — "a displayed type
  denoting a different set of values" is in the MEANING table, and `{}` and
  `{ [L]: number }` do denote different sets. But no program was found that OBSERVES it as
  a different verdict: `{ [L]: 1 }` against `{ [k: string]: number }` and against
  `Record<string, number>` is silent in BOTH compilers, and the excess direction is silent
  in both too. So it is a modelling gap whose only measured observable is the printed type,
  it is recorded as such in (CHK.5), and **no `logicalParityDivergence` was needed —
  no corpus baseline moved, and that mechanism switches a BASELINE off, not a scratch row.**

**Part B — the SYMBOL axis. THE VERDICT IS THAT IT SPLITS: the WELL-KNOWN half was small
and is LANDED; the `unique symbol` half is MODELLING and is stopped, with the measurement
that says so.**

| the key | tsc 7.0.2 | ours BEFORE | ours AFTER |
|---|---|---|---|
| `{ [Symbol.iterator]: 1 }` vs `Opt` | TS2353 `'[Symbol.iterator]'` | **silent — FN** | TS2353 — parity |
| `{ [Symbol.iterator]: () => 1 }` vs a target that HAS it | silent | silent | silent — parity |
| `{ [S]: 1 }` vs `Opt`, `S: unique symbol` | TS2353 `'[S]'` | silent — FN | **still open** |
| `{ [S2]: 1 }` vs `{ [S]: number }` | TS2353 `'[S2]'` | silent — FN | **still open** |
| `{ [S]: 1 }` vs `{ [S]: number }` | silent | silent | silent — parity |
| `interface HasS { [S]: number }`, `= {}` | TS2741 `'[S]'` | **silent — FN** | still open, declaration side |
| `interface HasI { [Symbol.iterator]: … }`, `= {}` | TS2741 | TS2741 — parity | unchanged |
| `{ [PS]: 1 }`, `PS: symbol` | silent | silent | silent — parity |

- **WHY THE WELL-KNOWN HALF WAS SMALL: BOTH SIDES ALREADY AGREED AND ONLY THE EXCESS CHECK
  COULD NOT SEE THE KEY.** Since round 723 `computedSymbolKey` names `[Symbol.iterator]`
  for an object literal's TYPE and for an interface's own member alike, so the supply
  direction has been right for 200 rounds; round 934 excluded that helper from the excess
  naming WHOLESALE, and the exclusion is still right in general — **tsc is SILENT for every
  computed key it cannot late-bind, measured this round over seven of them** (`[LW]` for a
  `string`, a substituting template, a literal union, a `number`, a plain `symbol`,
  `[NS.LW]`, `[obj.k]`), so re-admitting the invented `"[<dotted>]"` name generally turns
  each into a false positive. The landed route therefore demands the receiver be the
  identifier `Symbol` with no local binding of that name.
- **AND IT COST ONE EMBEDDED-LIB LINE, WHICH IS THE ONLY RED THE SUITE PRODUCED.** The
  embedded `IterableIterator<T>` did not declare the `[Symbol.iterator]()` member the real
  lib declares, so an object literal supplying it against an `IterableIterator`-extending
  interface read as EXCESS — round 456's pin, and an artefact of the approximation rather
  than of the change (with the REAL libs the same fixture is silent in both compilers,
  measured). Adding the member is strictly more faithful and the whole 13k-baseline corpus
  is green with it.
- **WHY THE `unique symbol` HALF IS MODELLING, AND THE MEASUREMENT THAT DECIDES IT.**
  `declare const S: unique symbol` types as plain `symbol` here, so `[S]` and `[S2]` are
  ONE name; the DECLARATION side declares no member for `[S]` at all; and therefore
  **naming the key on the literal side alone INVERTS the defect** — `{ [S]: 1 }` against
  an interface that HAS `[S]` is silent in both compilers today and would become a false
  positive. A spelling-keyed name is not the fix either: it must survive a rename and an
  import, which is exactly what tsc's declaration-keyed `__@<desc>@<id>` buys. So it needs
  a `unique symbol` TYPE plus the declaration side, in ONE commit. Stopped and written up
  as (CHK.5)(d) rather than attempted.

**Part C — NOT ATTEMPTED, by instruction, and (CHK.5) is written in its place** as an
executable four-stage plan: (a) the member-building sites adopt the existing SYNTACTIC
namer — cheap, no new machinery, and it closes the interface FN and the class TS2339 FP;
(b) TS1117 for a late-bound duplicate, which is NOT the member-table problem it looks like
because the duplicate check is a separate AST scan; (c) the cross-file and class-static
keys, whose route is the frozen binder tables; (d) the `unique symbol` type, the only
genuinely large piece, with the inversion above as the reason it cannot land alone.

- **PINS +28, one class, `LateBoundQualifiedKeyTest`.** Twelve supply rows, three refusal
  controls that are tsc's answer, six excess rows, five excess negative controls covering
  the keys tsc is silent about, and two cross-pass determinism pins (`none { 2339 }` plus
  exactly one TS2322) — round 935's core pin re-asked of the namespace and template routes,
  because the head resolution is exactly where an ambient answer would have gone.

- **THIRTEEN-ARM ABLATION**, each arm applied to and restored from a sha256-verified
  on-disk snapshot, each diffed against the SNAPSHOT rather than HEAD, each asserting
  `ran 53` — and **both pin classes run**, so an arm reddening round 935's rows is visible
  rather than hidden by the filter (A11 and A13 both do).

| arm | the mistake | red | what it shows |
|---|---|---|---|
| A1 | the QUALIFIED route is off | **12** | the pre-936 boundary: every namespace row, both directions |
| A2 | the ENUM leaf of the descent is dropped | **3** | the three enum-in-namespace rows and only those |
| A3 | MERGING is lost — the first matching block decides | **1** | the merged-namespace row, and only it |
| A4 | a DOTTED namespace name is truncated to its head | **1** | the `namespace DD.EE` row, and only it |
| A5 | the template-literal TYPE route is dropped | **4** | the template rows, including its cross-pass pin |
| A6 | the `${` discriminator is dropped | **1** | the substituting-template control, and only it |
| A7 | the TYPE-ALIAS hop is dropped | **4** | the alias rows including the chain |
| A8 | the well-known-symbol excess naming is dropped | **1** | the pre-936 boundary for Part B |
| A9 | the local-`Symbol`-shadow guard is dropped | **1** | the shadow control — **after the pin was repaired** |
| A10 | the receiver guard alone is dropped | **0** | REDUNDANT given its sibling, with a reason |
| A12 | the hardcoded name alone is replaced | **0** | REDUNDANT given its sibling, with a reason |
| A13 | BOTH — the route IS `computedSymbolKey` again | **5** | the narrowness, as one mistake |
| A11 | the `const` guard is dropped | **3** | the widened-`let` controls in BOTH classes |

- **TWO ZERO ARMS AND THEY ARE TWO DIFFERENT THINGS — WHICH IS THE ROUND'S SECOND
  METHODOLOGICAL FINDING.** A9 first read **0** and was a **BLIND PIN** (round 902's trap):
  its fixture declared the target `interface` INSIDE a function body, where B83.5 leaves it
  unbound, so the annotation degraded to `any`, no excess check ran at all, and
  `none { 2353 }` was vacuously true — the pin was green against a binary with the guard
  deleted. Moved to file level, it reddens. A10 and A12 read **0** and are **REDUNDANT
  GIVEN THEIR SIBLING** (round 927's law): the two halves of the narrowness mask each other
  — dropping the receiver guard leaves a hardcoded `"[Symbol.<name>]"` return that no
  longer matches the name the TYPE builder gives the same key, so the excess check finds no
  declaring node and emits nothing; replacing the return leaves the receiver guard refusing
  the key first. Neither is a claim the pins can test alone, so A13 undoes both as ONE
  mistake and reddens 5. **A zero arm is not a verdict until you have asked which of the
  two it is.**

- **GATES.** Suite **15,080 → 15,108 / 0 failures / 3 skipped** (summed over all six
  modules with an XML parser); **no corpus baseline moved**, so no `logicalParityDivergence`
  was needed. `cost_gate.py` **+0.00% on all 20 counters** including `output.errors 46` —
  and it is not a blind zero: the profiles' late-bindable keys are `[SyntaxKind.X]`, which
  the round-935 enum route already answered, so the new routes are reached only where that
  one declines. `huge_methods.py --fail-over 0` clean on EVERY module class dir (751
  classes, 0 over, largest 6,353). **The 8-profile before/after BINARY grid**
  (`scripts/round936-grid.sh`): all eight `added=0 removed=0`, 46/94 diagnostics unchanged
  — and this one is a RESULT, not a control, because those profiles carry 57
  `[Symbol.iterator](` keys, which is precisely the population Part B renames.
  `spine_closure_audit.py` not run: nothing on the spine changed.

- **NEXT.** `(CHK.5)`, in its stated order — (a) the member-building sites, which is cheap
  and needs no new machinery; then (b) TS1117; then (c) the cross-file and class-static
  keys; and only then (d) the `unique symbol` type, which must land on both sides at once.

**Round 935 (2026-08-18) — (CHK.3): LATE-BOUND COMPUTED KEYS, BOTH DIRECTIONS IN ONE
COMMIT. THE ROUND'S PRODUCT IS THAT **tsc's OWN RULE IS NOT PORTABLE AS WRITTEN: A MEMBER
NAME DERIVED FROM A *TYPE* IS NOT A FUNCTION OF THE PROGRAM HERE**, and the first draft —
which ported `isTypeUsableAsPropertyName` literally — produced the CORRECT diagnostic and a
CONTRADICTORY one in the SAME compile, which is round 933's two-extraction-sites signature
reached through ambient state instead of through a second `when`.**

- **STEP 1 WAS tsc 7.0.2, DIRECT, on ~40 scratch projects.** Every row below was READ from
  `tools/tsgo-7.0.2/lib/tsc --noEmit -p .` and from our own `MainKt --noEmit --listAll` on
  the SAME directory — never reasoned. **Both defects reproduced at HEAD exactly as rounds
  933/934 recorded them, and extending the table before designing paid for itself twice**
  (see the two corrections below).

**Direction 1 — SUPPLY** (`interface Req { p: number }`, `const r: Req = { <key>: 1 }`;
a row is a false POSITIVE when tsc is silent and we are not).

| the key | tsc 7.0.2 | ours BEFORE | ours AFTER |
|---|---|---|---|
| `[K]`, `const K = "p"` | silent | **TS2741 — FP** | silent |
| `[K2]`, `const K2 = K` | silent | **TS2741 — FP** | silent |
| `[L2]`, `let L2: "p" = "p"` | silent | **TS2741 — FP** | silent |
| `[D]`, `declare const D: "p"` | silent | **TS2741 — FP** | silent |
| `[U]`, `const U: "p" \| "q" = "p"` | silent (CFA-narrowed) | **TS2741 — FP** | silent |
| `[CE.P]`, `const enum CE { P = "p" }` | silent | **TS2741 — FP** | silent |
| `[SE.P]`, a PLAIN `enum SE { P = "p" }` | silent | **TS2741 — FP** | silent |
| `[N]`, `const N = 1e3` vs `{ 1000: number }` | silent | **TS2741 — FP** | silent |
| `[NE.P]`, `enum NE { P = 0 }` vs `Req` | TS2353 | TS2741 | **TS2353 — parity** |
| `[L]`, `let L = "p"` (widened) | TS2741 | TS2741 | TS2741 — refused, parity |
| `[U2]`, `declare const U2: "p" \| "q"` | TS2741 | TS2741 | TS2741 — refused, parity |
| `[PS]`, `declare const PS: symbol` | TS2741 | TS2741 | TS2741 — refused, parity |
| `[k]`, `<KK extends string>(k: KK)` | TS2741 | TS2741 | TS2741 — refused, parity |
| `[AE.X]`, `declare enum AE { X }` | TS2741 | TS2741 | TS2741 — refused, parity |
| `[S]` / `[Symbol.iterator]` | TS2353 | TS2741 | TS2741 — **STILL OPEN, (CHK.4)** |

**Direction 2 — EXCESS** (`interface Opt { p?: number }`; a row is a false NEGATIVE when
tsc emits and we do not). tsc names the key **as written**, which round 934 already renders.

| the key | tsc 7.0.2 | ours BEFORE | ours AFTER |
|---|---|---|---|
| `[KZ]`, `const KZ = "zz"` | TS2353 `'[KZ]'` | **silent — FN** | TS2353 `'[KZ]'` |
| `[CE.Q]` (const enum) | TS2353 `'[CE.Q]'` | **silent — FN** | TS2353 `'[CE.Q]'` |
| `[SE.Q]` (plain enum) | TS2353 `'[SE.Q]'` | **silent — FN** | TS2353 `'[SE.Q]'` |
| `[NE.P]` (numeric enum) | TS2353 `'[NE.P]'` | **silent — FN** | TS2353 `'[NE.P]'` |
| the same in an ARGUMENT / a NESTED literal | TS2353 | **silent — FN** | TS2353 |
| `[CE.P]` / `[K]` naming an EXISTING member | silent | silent | silent — the A4 FP, guarded |
| `[N]` vs a NUMERIC index signature | silent | silent | silent — round 934's guard holds |
| `[S]` / `[Symbol.iterator]` | TS2353 | silent | silent — **STILL OPEN, (CHK.4)** |

- **THE FIRST DRAFT WAS tsc's OWN RULE AND IT HAD TO BE THROWN AWAY — THIS IS THE ROUND.**
  tsc late-binds when `isTypeUsableAsPropertyName(checkComputedPropertyName(name))`, so the
  draft asked `getTypeOfExpression(key)` and accepted a `Type.StringLiteral`/`NumberLiteral`.
  It made every supply and excess row above green — **and then**
  `const K = "p"; const obj = { [K]: 1 }; obj.p` emitted the correct TS2322 **AND**
  `Property 'p' does not exist on type '{}'` **in one compile**. Diagnosed by bisecting the
  DECLARATION rather than the key: annotated (`const K: "p"`), `declare`d and FUNCTION-BODY
  consts are all clean, and only the FILE-LEVEL **un-annotated** const splits — its literal
  type exists in `currentLocalTypes` as (WIDEN.1) records it and the pass behind TS2339 does
  not have that map (round 911: a literal is typed in more than one ambient). **A name that
  a member table is built from must be a function of the PROGRAM, and a type-derived one
  here is a function of the PASS.**
- **SO THE LANDED RESOLUTION IS SYNTACTIC**, which is also what makes it cheap: an enum
  member's VALUE through `enumMemberEntries` (whose ambient-non-`const` OPAQUE rule, round
  746, turns out to be tsc's own answer — `declare enum AE { X }` is TS2741 in tsc too), or
  the declaration a name resolves to by an INNERMOST-FIRST walk of the enclosing statement
  lists. The walk is not a stylistic choice: `lookupPerFileForNode` cannot see a
  function-body local at all (B83.5) and a scope-chain consult would be ambient again.
- **TWO RULES THE EXTENDED TABLE CORRECTED, BOTH THE OPPOSITE OF THE OBVIOUS ONE.**
  (i) `const`-ness is NOT the criterion — `let L2: "p"` late-binds and a widened `const`
  would not — so a literal ANNOTATION binds for any declaration. (ii) A `const`'s literal
  INITIALIZER beats its own annotation, because a `const` reference is CFA-narrowed to it;
  that is the only reading under which `const U: "p" | "q" = "p"` late-binds in tsc, and a
  first pass that read the annotation would have called it a union and refused. **A genuine
  union needs `declare const U2: "p" | "q"`** — the narrowing makes the initialized form a
  useless control, and it read "silent" for the wrong reason on the first table.
- **ORDER IS THE FIX FOR ROUND 934's ARM-A4 FALSE POSITIVE, at its source.**
  `lateBoundComputedKeyName` is asked BEFORE `computedSymbolKey` at all three naming sites,
  so `[CE.P]` answers `p` and the invented `"[<dotted>]"` placeholder is reached only by the
  dotted paths that really are dynamic. Round 934 had to EXCLUDE that helper from the excess
  naming; it no longer has to.
- **THE LANGUAGE-SERVICE SIDE WAS RE-MEASURED AND STAYS PUT — THE QUEUE'S "it has to move in
  the same commit" IS WITHDRAWN, ON tsc's OWN ANSWER.** `SyntaxRoles.isMemberPosition`
  refuses `{ [K]: v }`; asked for the references of `Shape.p` on a file whose literal carries
  `[K]`, tsc's LSP answers **2** spans — the declaration and a plain `{ p: 2 }` — and not the
  key. **The checker and the language service deliberately disagree about what a member name
  is, in tsc as here**: the key SUPPLIES the member and SPELLS the binding, and only the
  second is what a rename may edit. The reason is recorded beside the arm.
- **THE PROFILES ARE *NOT* A CONTROL THIS TIME, unlike rounds 933 and 934 — measured, not
  assumed.** Across all eight profiles' 1,249 `.ts` files the late-bindable shape occurs in
  bulk: `[SyntaxKind.<Member>]:` object-literal keys (32 hits per member name — `parser.ts`'s
  `forEachChildTable`, `visitorPublic.ts`'s `visitEachChildTable`) plus 57 `[Symbol.iterator](`.
  So the 8-profile grid was a real test of a real population, and its `added=0 removed=0` is
  a result rather than a tautology — as is the fact that `globals.lookups` MOVED (+0.09%),
  which is those keys' enum resolutions and the only cost this round has.

- **PINS +25, one class, `LateBoundComputedKeyTest`.** Ten supply rows, five negative
  controls whose refusal is tsc's answer (widened `let`, genuine union, plain `symbol`, bare
  type parameter, ambient value-less enum member), five excess rows including the argument
  and nested positions, three excess negative controls (the A4 FP, an existing member, the
  numeric-index absorption) — and **the round's core pin, `a late-bound key is one member in
  every pass`**, which asserts `none { 2339 }` together with exactly one TS2322 and is the
  only thing in the suite that can see the type-route defect, because each pass alone is green.

- **EIGHT-ARM ABLATION, one mistake at a time, each applied to and restored from a
  sha256-verified on-disk snapshot, each with an asserted RAN-COUNT** (`scripts/round935-ablate.py`;
  the dead-arm check diffs against the SNAPSHOT rather than HEAD).

| arm | the mistake | red | what it uniquely shows |
|---|---|---|---|
| A1 | late binding is off entirely | **16** | the pre-935 boundary — every supply and every excess row in one set |
| A2 | only the EXCESS side loses it (the type builder still binds) | **5** | the five excess rows, and that the two sites are separable |
| A3 | the ENUM route is dropped | **5** | every enum row, in both directions, and only those |
| A4 | the const-INITIALIZER route is dropped | **9** | the const rows including the alias chain, the union-annotated const and the body local |
| A5 | the literal-ANNOTATION route is dropped | **2** | the `let L2: "p"` and `declare const D: "p"` rows, and only those |
| A6 | the `const` guard is dropped (a `let` initializer binds too) | **1** | the widened-`let` negative control, and only it |
| A7 | a numeric key is named by its SOURCE TEXT, not its value | **2** | the `1e3` -> `1000` pair, after the pin was repaired (below) |
| A8 | an AMBIENT value-less enum member binds to an invented number | **1** | the round-746 opaque-value control, and only it |

  **Every arm has a uniquely-its-own failure, and A7 is the round's second methodological
  finding: it first read ZERO and that was a BLIND PIN, not a redundant guard** (round 902's
  trap). The pin asserted `none { 2741 }` for `{ [N]: 1 }` against `{ 1000: number }`, and a
  MISNAMED key is not reported by the missing-property emitter at all — the EXCESS emitter
  fires first and short-circuits it, exactly as tsc's does. Probed rather than assumed
  (`const N = 7` against the same target reads TS2353 in BOTH compilers), the pin now
  asserts neither code fires and a new POSITIVE control asserts that `{ "1e3": number }` —
  the target a text-named key WOULD satisfy — is reported excess. 24 pins -> 25.

- **GATES.** Suite **15,055 → 15,080 / 0 failures / 3 skipped** (summed over all six modules
  with an XML parser); **no corpus baseline moved**, so no `logicalParityDivergence` was
  needed. `cost_gate.py`: 18 of 20 counters `+0.00%`, `globals.lookups` **748,522 → 749,220
  (+0.09%)** and `globals.misses` **732,172 → 732,840 (+0.09%)** — the enum-symbol
  resolutions the profiles' `[SyntaxKind.X]:` keys now perform, far inside the ±2% band and
  rebaselined with `--update` in this commit. `huge_methods.py --fail-over 0` clean on BOTH
  module class dirs (largest method 5,651). `spine_closure_audit.py` clean (46 handlers, 40
  audited) — a control, nothing on the spine changed. **The 8-profile grid is a BEFORE/AFTER
  BINARY grid** (`scripts/round935-grid.sh`, round 813's shape; profiles enumerated by the
  presence of a `tsconfig.json` and REFUSED below 8): all eight `added=0 removed=0`,
  46/94 diagnostics unchanged.

- **NEXT.** `(CHK.4)` — the DECLARATION side (an interface's / a class's own `[K]` member and
  the duplicate-key TS1117, all of which need member tables computed AFTER type resolution)
  and the SYMBOL axis (a `unique symbol` has no type of its own here, so `[S]` and `[S2]` are
  one name). Both are modelling items with tsc's answers already measured in the queue entry;
  the cheap residue beside them is a `NS.K` namespace-const key, which is an FP today.

**Round 934 (2026-08-18) — (CHK.2): A COMPUTED OBJECT-LITERAL KEY NEVER REACHED THE
EXCESS-PROPERTY CHECK. THE ROUND'S PRODUCT IS THAT **A DIAGNOSTIC CAN BE COMPUTED IN
FULL AND THEN DROPPED FOR WANT OF A POSITION** — `getTypeOfObjectLiteral` had named
`["zz"]`, `` [`zz`] `` and `7` for years, so the literal's TYPE carried the member and
`checkExcessProperties` correctly judged it excess; it then looked for the AST node
that declared it with a `when` knowing only `Identifier` and `StringLiteralNode`, found
nothing, and emitted nothing. **The failure is the exact mirror of round 933's**: there
one of B451's >= 5 extraction sites had been widened and another had not, so a member
resolved for one consumer and FP'd for the other IN ONE COMPILE; here the two sites
disagreed the other way and the result was SILENCE — a program tsc rejects that this
compiler accepted, with nothing anywhere to see it.

- **STEP 1 WAS tsc 7.0.2, DIRECT, on five scratch projects.** Every row below was READ
  from `tools/tsgo-7.0.2/lib/tsc --noEmit -p .` and from our own `MainKt --noEmit
  --listAll` on the SAME directory — never reasoned. **The FN reproduced at HEAD
  exactly as round 933 recorded it, and the extension found two more rows it did not
  have** (a BARE numeric key, and every position beyond a plain assignment).

**Direction 2, EXTENDED** (`interface Opt { p?: number }` unless named; a row is a false
NEGATIVE when tsc emits and we do not, a false POSITIVE when the reverse):

| the key / the shape | tsc 7.0.2 | ours BEFORE | ours AFTER |
|---|---|---|---|
| `{ p: 1, zz: 2 }` | TS2353 `'zz'` | TS2353 `'zz'` | TS2353 `'zz'` |
| `{ p: 1, "zz": 2 }` | TS2353 `'"zz"'` | TS2353 `'zz'` — form | TS2353 `'"zz"'` |
| `{ p: 1, 'zz': 2 }` | TS2353 `''zz''` | `'zz'` — form | `''zz''` |
| ``{ p: 1, [`zz`]: 2 }`` | TS2353 `` '[`zz`]' `` | **silent — FN** | `` '[`zz`]' `` |
| `{ p: 1, ["zz"]: 2 }` | TS2353 `'["zz"]'` | **silent — FN** | `'["zz"]'` |
| `{ p: 1, [ "zz" ]: 2 }` | TS2353 `'[ "zz" ]'` | **silent — FN** | `'[ "zz" ]'` |
| `{ p: 1, ["a]b"]: 2 }` | TS2353 `'["a]b"]'` | **silent — FN** | `'["a]b"]'` |
| `{ p: 1, 7: 2 }` | TS2353 `'7'` | **silent — FN, NOT in round 933's table** | `'7'` |
| `{ p: 1, [7]: 2 }` / `{ p: 1, ["7"]: 2 }` | TS2353 `'[7]'` / `'["7"]'` | **silent — FN** | as tsc |
| `{ ["mm"]() {} }` | TS2353 `'["mm"]'` | **silent — FN** | `'["mm"]'` |
| the same key in `satisfies` / an ARGUMENT / a `return` / a NESTED literal | TS2353 ×4 | **silent ×4 — FN** | as tsc ×4 |
| ``{ p: 1, [`zz${x}`]: 2 }`` — a SUBSTITUTING template | silent | silent | silent |
| `{ p: 1, ["zz"]: 2 }` vs `{ …; [k: string]: T }` | silent | silent | silent |
| `{ p: 1, [7]: 2 }` vs `{ …; [k: number]: T }` | silent | silent | **silent — see the FP below** |
| `{ p: 1, "1e3": 2 }` vs `{ …; [k: number]: T }` | TS2353 `'"1e3"'` | `'1e3'` — form | `'"1e3"'` |
| `{ [E.P]: 1 }`, `const enum E { P = "p" }` | silent (late-bound to `p`) | silent | **silent — see the FP below** |
| `{ [K]: 1 }` / `{ [E.Q]: 1 }` / `{ [S]: 1 }` / `{ [Symbol.iterator]: 1 }` | TS2353 | silent | **silent — STILL OPEN** |
| `{ get ["gg"]() {} }` | TS2353 `'["gg"]'` | silent | **silent — STILL OPEN** |

- **THE ROUND'S OWN NEAR MISS, AND IT IS WHY THE TABLE WAS EXTENDED BEFORE ANYTHING WAS
  DESIGNED: THE FIRST TWO DRAFTS EACH TURNED AN FN INTO AN **FP**, ON A ROW ROUND 933's
  FIVE-ROW TABLE DOES NOT CONTAIN.** (i) Admitting a numeric key exposed a TARGET-side
  gap that could not matter before — `collectTargetPropertyNames` bails outright on a
  STRING index signature and knows nothing of a NUMERIC one, which applies only to a
  numerically-named key — so `{ [7]: 2 }` against `{ [k: number]: T }` was reported where
  tsc is silent. (ii) Naming the computed key with `computedLiteralKey ?: computedSymbolKey`
  — the obvious "delegate to the type builder" move, and the shape round 933's own lesson
  argues for — reported `'[E.P]'` for `const enum E { P = "p" }; const o: Opt = { [E.P]: 1 }`,
  which tsc LATE-BINDS to the existing `p` and accepts. **`computedSymbolKey` INVENTS the
  name `"[<dotted>]"` so a well-known-symbol member can match STRUCTURALLY (round 723); it
  is not a claim about what the key spells, and it cannot tell `Symbol.iterator` from `E.P`.**
  Both are now guards with a discriminating negative control apiece (arms A3 and A4).
- **SO THE LINE THIS ROUND DRAWS IS ONE SENTENCE, AND IT IS ROUND 933's LINE IN THE OTHER
  DIRECTION: the excess check acts on a computed key exactly when the key is a LITERAL
  spelling one fixed name.** Every key that needs the key's TYPE — a binding `[K]`, an
  enum member `[E.P]`, a `unique symbol` `[S]`, a well-known symbol `[Symbol.iterator]` —
  stays out in BOTH directions and is the same open item, late binding. Per round 765
  those FNs are NOT pinned; the FP they would produce is.
- **THE MESSAGE FORM IS MATCHED RATHER THAN RECORDED, BECAUSE IT MEASURED FREE.** tsc
  names the key WITH its delimiters and squiggles the whole written key (`indexSignatures1`'s
  baseline puts five tildes under `[sym]` and nine under `'someKey'`); the key node's span
  is in hand at the emission, so it is one substring. **It is free because no ACTIVE corpus
  test has a delimited excess key**: of the eleven `.errors.txt` baselines whose TS2353/TS2561
  names a key with brackets or quotes, ten are not generated at all and the eleventh
  (`checkDestructuringShorthandAssigment2`, `'[k]'`) belongs to a different emitter. A bare
  identifier renders and measures exactly as before, which is why nothing moved. **Half-matching
  was the alternative and was refused**: rendering computed keys as written while leaving
  `"zz"` bare is a third convention nobody asked for.
- **ONE PIN CHANGED, AND IT CHANGED TOWARDS tsc.** Round 933's
  `` `negative control - a backtick-quoted key names ITS OWN text and not a neighbour` ``
  asserted the TS2741 this compiler happened to produce for
  ``interface Req { p: number }; const r: Req = { [`other`]: 1 }``. Measured this round,
  **tsc reports TS2353 there** — for all four spellings of that shape — because the excess
  check runs first and returns. The pin now asserts tsc's line, and asserting that the
  message names the key's own TEXT keeps the same injected mistake in view more sharply
  than TS2741 did. It is the only red the whole suite produced.
- **SIBLING SITES: ONE OF TWELVE `code = 2353` EMITTERS WAS TOUCHED.** `checkExcessProperties`
  (~17 call sites — assignment, argument, `satisfies`, `return`, array element, nested), which
  is why every position in the table moves together. The other eleven are dedicated
  corpus-shape walkers with their own gates and their own name extraction (B451's list plus
  the B482/B513/B576/B331 families); none was touched and none needs to be for this shape —
  `checkDestructuringShorthandAssigment2` shows one of them already rendering `'[k]'` with
  its brackets, i.e. this family has been divided about the message form for a long time and
  this round moves the general path onto tsc's side of it.
- **EVERY PROFILE-BASED INSTRUMENT IS A CONTROL HERE, MEASURED NOT ASSUMED — the same
  structural blindness round 933 found, re-measured for this shape.** Across all eight
  profiles' `src` (1,249 `.ts` files) an object-literal computed key matches **8 times, all
  eight the SAME line** (`parser.ts:10634`) and that line is a DESTRUCTURING pattern, not an
  object literal; a bare numeric key matches 120 times and every hit inspected is inside a
  comment or a template string. So `cost_gate.py`'s `+0.00%` on all 20 counters and the
  grid's `added=0 removed=0` on all eight profiles are the EXPECTED answers (round 853's law:
  a `+0.00%` streak is a reason to audit the instrument, and the audit is that grep).

- **PINS +20, one class, `ComputedKeyExcessPropertyTest`.** Each spelling has its own row;
  the form rows assert the exact tsc-measured message AND, for `["zz"]`, the `length == 6`
  that the cooked name (2) cannot produce. Five negative controls: an existing member, a
  string index signature, a numeric index signature over all four numeric spellings, a
  SUBSTITUTING template, and the dotted `[E.P]` key. One positive control keeps the numeric
  guard from becoming a blanket (`"1e3"` is still excess against a numeric index signature).

- **SIX-ARM ABLATION, one mistake at a time, each applied to and restored from a
  sha256-verified on-disk snapshot, each with an asserted RAN-COUNT** (`scripts/round934-ablate.py`;
  every arm read `ran 20`, and the dead-arm check diffs against the SNAPSHOT rather than HEAD).

| arm | the mistake | red | what it uniquely shows |
|---|---|---|---|
| A1 | the shared naming loses its computed arm | **10** | the pre-934 boundary — every computed spelling, in every position, in one set |
| A2 | the shared naming loses its `NumericLiteralNode` arm | **1** | the bare `7:` key, which is not a computed key at all |
| A3 | the numeric-index absorption guard is dropped | **1** | draft (i)'s false positive, and only it |
| A4 | the computed arm reuses `computedSymbolKey`'s invented name | **1** | draft (ii)'s false positive, and only it |
| A5 | message and squiggle fall back to the COOKED name | **12** | the written-span rendering — including both quoted-key rows, which no other arm touches |
| A6 | the NESTED descent reverts to the pre-934 `when` | **1** | the nested-under-a-computed-key row, and only it |

  Four arms have a uniquely-their-own failure. **Four of the twenty pins are undiscriminated
  by any arm and are recorded as such rather than claimed** (round 807's law): the
  bare-identifier control, the existing-member control, the string-index control and the
  substituting-template control all guard a FUTURE widening; the last of them is already
  ablated by round 933's A3.

- **GATES.** Suite **15,035 → 15,055 / 0 failures / 3 skipped** (summed over all six modules
  with an XML parser); **no corpus baseline moved**, so no `logicalParityDivergence` was
  needed. `cost_gate.py` +0.00% on all 20 counters. `huge_methods.py --fail-over 0` clean,
  751 classes (+1: the `ExcessProp` carrier), 0 over. `spine_closure_audit.py` clean (46
  handlers, 40 audited) — a control, nothing on the spine changed. **The 8-profile grid is a
  BEFORE/AFTER BINARY grid** (`scripts/round934-grid.sh`, round 813's shape; profiles
  enumerated by the presence of a `tsconfig.json` and REFUSED below 8): all eight
  `added=0 removed=0`, 46/94 diagnostics unchanged.

- **NEXT.** `(CHK.3)` — LATE BINDING (LANDED round 935; the residue is (CHK.4)): a
  computed key whose expression has a string-literal TYPE. It is now the SAME open item in both directions and both tables name it — supply
  (`{ [K]: v }` / `{ [E.P]: v }` do not satisfy a required member here and do in tsc) and
  excess (`[K]`, `[E.Q]`, `[S]`, `[Symbol.iterator]` are TS2353 in tsc and silent here) —
  so it should be closed once, at `computedLiteralKey`'s caller, by asking the key's type
  rather than its spelling. **The two directions must land together**: the FP guarded by
  arm A4 is exactly what a half-landing produces. `SyntaxRoles.isMemberPosition` refuses
  the same shape on the language-service side and has to move with it. A smaller residue,
  worth one paragraph rather than a round: `getTypeOfObjectLiteral`'s GetAccessor/SetAccessor
  arms do not name a computed key at all, so `{ get ["gg"]() {} }` declares no member —
  which is a SUPPLY-direction gap in (CHK.1)'s family, not this one.

**Round 933 (2026-08-18) — (CHK.1): A BACKTICK-QUOTED COMPUTED MEMBER KEY NAMES A
MEMBER. THE ROUND'S PRODUCT IS THAT **A FALSE POSITIVE CAN BE INVISIBLE TO EVERY
INSTRUMENT THIS REPOSITORY HAS AND STILL BE REAL: THE EIGHT tsc PROFILES CONTAIN
*ZERO* BACKTICK-QUOTED COMPUTED MEMBER KEYS, SO THE COST GATE, THE 8-PROFILE GRID
AND THE WHOLE 13k-BASELINE CORPUS WERE ALL GREEN ON A COMPILER THAT REJECTED THREE
PROGRAMS tsc ACCEPTS.** The gap was found only because round 932 tripped over one
row of it while doing something else, and wrote it down.

- **STEP 1 WAS tsc 7.0.2, DIRECT, on one scratch project per direction.** Every row
  below was READ from `tools/tsgo-7.0.2/lib/tsc --noEmit -p .` and from our own
  `MainKt --noEmit --listAll` on the SAME directory — never reasoned.

**Direction 1 — does the key SUPPLY a required member?** (`interface Req { p: number }`,
`const r: Req = { <key>: 1 }`; a row is a false positive when tsc is silent and we are not.)

| the key | tsc 7.0.2 | ours BEFORE | ours AFTER |
|---|---|---|---|
| `{ p: 1 }` | silent | silent | silent |
| `{ "p": 1 }` | silent | silent | silent |
| ``{ [`p`]: 1 }`` | silent | **TS2741 — FP** | silent |
| `{ ["p"]: 1 }` | silent | silent | silent |
| ``{ [`p${x}`]: 1 }`` | **TS2741** | TS2741 | TS2741 — parity, and now PINNED |
| `{ [K]: 1 }`, `K` a `const "p"` | silent | **TS2741 — FP** | TS2741 — **STILL OPEN** |
| `{ [E.P]: 1 }`, `E` a `const enum` | silent | **TS2741 — FP** | TS2741 — **STILL OPEN** |
| `{ 1: 1 }` / `{ [1]: 1 }` vs `{ 1: number }` | silent | silent | silent |
| `{ [S]: 1 }`, `S` a `unique symbol` | silent | silent | silent |

**Direction 2 — the mirror, EXCESS-PROPERTY checking** (`interface Opt { p?: number }`;
a row is a false NEGATIVE when tsc emits and we do not). **Measured, untouched, and it
is a SECOND cause**, not the same one:

| the key | tsc 7.0.2 | ours (before AND after) |
|---|---|---|
| `{ p: 1, zz: 2 }` | TS2353 `'zz'` | TS2353 `'zz'` |
| `{ p: 1, "zz": 2 }` | TS2353 `'"zz"'` | TS2353 `'zz'` — **form divergence** |
| ``{ p: 1, [`zz`]: 2 }`` | TS2353 `` '[`zz`]' `` | **silent — FN** |
| `{ p: 1, ["zz"]: 2 }` | TS2353 `'["zz"]'` | **silent — FN** |
| ``{ p: 1, [`zz${x}`]: 2 }`` | silent | silent |

**Direction 3 — does an interface's / class's / literal's OWN backtick member RESOLVE?**
(probe = assign it to an incompatible primitive, so the message NAMES the type found —
rounds 760/762: asserting silence cannot tell "resolved" from "washed to something that
swallows every access")

| the declaration | tsc | ours BEFORE | ours AFTER |
|---|---|---|---|
| ``interface I { [`ip`]: number }``, `i.ip` | TS2322 `number`→`string` | **TS2339 — FP** | TS2322 |
| `interface I { ["is"]: number }` | TS2322 | TS2322 | TS2322 |
| ``class C { [`cp`]: number }``, `c.cp` | TS2322 | **TS2322 *and* TS2339 in ONE compile** | TS2322 |
| `class C { ["cs"]: number }` | TS2322 | TS2322 | TS2322 |
| ``const o = { [`op`]: 1 }``, `o.op` | TS2322 | **TS2339 — FP** | TS2322 |

- **THE FP REPRODUCED, AND IT WAS BIGGER THAN THE ROW ROUND 932 WROTE DOWN — THREE
  DIAGNOSTICS, NOT ONE.** The named row was the object-literal supply; the interface
  and class members were never looked at and fail the same way.
- **THE CLASS ROW IS THE ROUND'S ONE REAL FINDING AND IT ONLY EXISTS BECAUSE THE FIX
  WAS APPLIED IN TWO STEPS.** After `computedLiteralKey` alone, `class C { [`cp`] }`
  read **TS2322 AND TS2339 at the same position in the same compile**: the type-building
  site had found the member and the class-AST walker (`classMemberNameText`) had not,
  because the two carry INDEPENDENT copies of the same `when`. The archive's B451 entry
  says exactly this — "member-NAME extraction has >= 5 INDEPENDENT sites that each drop
  ComputedPropertyName by default … adding computed-key support to one site silently
  leaves the others FP'ing" — and the second copy is now a DELEGATION to the first, so
  the two cannot drift again. **A widening applied to one extraction site is half a fix,
  and the tell is two contradictory diagnostics rather than a missing one.**
- **WHAT IS DELIBERATELY OUT, with tsc's own answer beside it.** A SUBSTITUTING template
  spells no fixed member and tsc reports TS2741 for it — pinned in the POSITIVE, against
  the exact message, so a later widening that swallows it reddens. `{ [K]: v }` and
  `{ [E.P]: v }` need the key's TYPE (tsc late-binds a string-literal-typed key); that is
  a modelling gap, not a spelling one, and it is NOT pinned — round 765's law: pinning a
  known-open gap is a countdown, not a guard. The excess-property FN is likewise recorded
  and unpinned; note it is SYMMETRIC across the spellings (`` [`zz`] `` and `["zz"]` both
  escape), so this round did not move it in either direction.
- **EVERY PROFILE-BASED INSTRUMENT IS A CONTROL HERE, AND THAT WAS MEASURED RATHER THAN
  ASSUMED.** `grep -rEoh '\[`[^`]*`\]\s*[:(]'` over all eight profiles' `src` returns
  ONE hit, and it is an array literal inside a spread (`fourslashImpl.ts:2443`), not a
  computed key. So `cost_gate.py`'s +0.00% on all 20 counters and the grid's
  `added=0 removed=0` on all eight profiles are the EXPECTED answers — round 853's law
  applies (a `+0.00%` streak is a reason to audit the instrument), and the audit here is
  the corpus grep plus the gate's own `MainKt` positive control, both of which passed.

- **PINS +11, none inverted.** `TemplateComputedMemberKeyTest` (core, `commonTest`) writes
  every backtick row BESIDE its quote-spelled B451 control in the same fixture, so a red
  backtick row means "the two spellings disagree" rather than "member resolution is
  broken". Every resolution pin assigns to an incompatible primitive and asserts the EXACT
  TS2322 message; the class pin asserts `none { 2339 }` AND the TS2322, which is the only
  pair that can see the two-site state above.

- **THREE-ARM ABLATION, one mistake at a time, each applied to and restored from a
  sha256-verified on-disk snapshot, each with an asserted RAN-COUNT** (`scripts/round933-ablate.py`;
  every arm read `ran 11`, and the dead-arm check diffs against the SNAPSHOT rather than
  HEAD — a `git diff` here is non-empty for an arm that changed nothing, because HEAD
  already differs by the round's own fix).

| arm | the mistake | red | what it uniquely shows |
|---|---|---|---|
| A1 | `computedLiteralKey` loses its template arm | **6** | the pre-933 boundary — all three FP surfaces plus both negative controls, in one set |
| A2 | `classMemberNameText` stops delegating and re-spells the old `when` | **1** | the SECOND site, and only it: the class pin is the only thing in the suite that sees it |
| A3 | the template arm admits the key but invents the name `"p"` | **4** | the arm reads the template's TEXT — i.e. neither negative control is vacuous |

- **GATES.** Suite **15,024 → 15,035 / 0 failures / 3 skipped** (summed over all four
  modules with an XML parser); no corpus baseline moved, so no `logicalParityDivergence`
  was needed. `cost_gate.py` +0.00% on all 20 counters. `huge_methods.py --fail-over 0`
  clean, 750 classes, 0 over. `spine_closure_audit.py` clean (46 handlers, 40 audited) —
  a control, nothing on the spine changed. **The 8-profile grid is a BEFORE/AFTER BINARY
  grid** (`scripts/round933-grid.sh`; this change has no in-binary arm, so it rebuilds the
  pre-933 source, captures, and rebuilds the fixed one — round 813's shape), profiles
  enumerated by the presence of a `tsconfig.json` and REFUSED below 8: all eight
  `added=0 removed=0`, 46/94 diagnostics unchanged.

- **NEXT.** `(CHK.2)` — the excess-property check never sees a computed key, so
  ``{ [`zz`]: 1 }`` and `{ ["zz"]: 1 }` both escape TS2353. Direction 2's table is the
  step-1 measurement already taken; the open question is WHICH of the twelve `code = 2353`
  emitters owns the object-literal case and whether it filters computed keys syntactically
  or simply never receives their names. `(CHK.3)` — late-binding a computed key whose
  expression has a string-literal TYPE (`{ [K]: v }`, `{ [E.P]: v }`), which is the same
  mechanism `SyntaxRoles.isMemberPosition` deliberately refuses on the language-service
  side, so the two must be decided together or they will disagree.

**Round 932 (2026-08-18) — (API.17): A COMPUTED OBJECT-LITERAL KEY. § 14's GAP 2 — THE
LAST SILENT SHAPE ANYWHERE IN THIS API — IS CLOSED, AND THE ROUND'S PRODUCT IS THAT
**A REFUSAL'S STATED REASON CAN EXPIRE WITHOUT ANYONE NOTICING: `typeCaptureReportedType`
REFUSED TO TYPE AN OBJECT-LITERAL KEY *BECAUSE THE CONTEXTUAL TYPE IS WALK-SCOPED STATE A
CAPTURE CANNOT READ*, AND (API.10) BUILT EXACTLY THAT MECHANISM ONE ROUND LATER.**

- **STEP 1 WAS tsc, five oracles over three fixtures** (`lsp_member_refs.py`,
  `lsp_rename.py`, `lsp_hover.py`, `lsp_definition.py`, `lsp_completion.py`). Every row
  below was READ, not reasoned:

| caret / query | tsc 7.0.2 | ours BEFORE | ours AFTER |
|---|---|---|---|
| references of `Shape.p`, one `{ ["p"]: v }` in the file | **6** spans, the key's `[232,233)` among them | 4 — silently short | 6 |
| the key's span | the TEXT, **quotes excluded** | — | the same |
| the same with the member OPTIONAL (`q?`) | 5 | 4 — **and the rename went through** | 5 |
| a NESTED computed key under a computed key | 5 | 0 | 5 |
| `` { [`p`]: v } `` template key | in the group | 0 | in the group |
| `{ "p": v }` quoted key | in the group | 0 | in the group |
| `{ [K]: v }` where `K` is a `const` | the **binding**'s 2 spans, not the member's | the same | the same |
| a key with NO contextual type | 1 — itself | 0 | 1 |
| hover on `{ p: v }` (contextual) | `(property) Shape.p: number` | **`string`** — an unrelated `const p` | `number` |
| hover on `{ ["p"]: v }` | `(property) Shape.p: number` | `string` (the literal's own) | `number` |
| hover on a free key | `(property) ["z"]: number` | `string` | `number` |
| definition on a computed key | the member's declaration | none | the same |
| rename from either end | rewrites the key, delimiters kept | refused / silent | rewrites it |
| completion inside `{ ["‸"]: }` | **null result** | NONE | NONE — parity |
| `interface I { ["ip"]: n }` + `i.ip` | 3 spans, rename rewrites all | refused | 3 spans, rewritten |

- **THE POPULATION IS THE WHOLE FEATURE FOR THE THIRD ROUND RUNNING, AND THIS TIME IT
  COLLAPSED INTO ONE PREDICATE.** `SourceIndex.occurrenceNodes` used to be identifiers
  plus a dedicated element-access enumeration; it is now identifiers plus every node for
  which `isMemberPosition && isMemberNameLiteral` holds — which SUBSUMES (API.9)'s
  element accesses and (API.16)'s templates and adds `{ "p": v }`, `{ ["p"]: v }`,
  ``{ [`p`]: v }`` and a class's or an interface's `["p"]`. `isMemberPosition` was
  already the predicate `Project.occurrenceCaret` used to decide whether a caret ON such
  a literal names anything, and already the axis the completeness net splits its
  obstacles on, so **the set a caret may land in, the set a sweep reports and the set a
  rename edits are now one set by construction** rather than three definitions kept in
  step.
- **A LITERAL THIS API CANNOT *RESOLVE* STILL BELONGS IN THE POPULATION, AND THAT IS THE
  WHOLE ARGUMENT FOR "PROVE TO OFFER" HOLDING WITHOUT EXCEPTION.** A computed METHOD key
  (`{ ["m"]() {} }`) and a computed member of a TYPE LITERAL are members the CHECKER does
  not put in a member table at all — measured, `c.cm()` hovers `any` and resolves
  nowhere — so nothing places them. Swept, they become a stated `OCCURRENCES_INCOMPLETE`
  conflict; unswept, they were a span nobody looked at. Seen-and-unplaced is a refusal;
  unseen is a silence.
- **`{ [K]: v }` IS DELIBERATELY OUT, AND THE ASYMMETRY IS LOAD-BEARING.** A computed
  name that is a BINDING spells no fixed member — the value is decided at run time — and
  tsc reads it as a reference to `K` alone (measured: two spans, and renaming it writes
  `[renamed]`). `isMemberPosition`'s computed arm therefore filters to LITERALS where its
  element-access arm does not, because calling `[K]` a member position flips the
  completeness net's polarity for every ordinary `const` rename. **A mid-round draft did
  not filter, and the regression it produced is arm C4**: `{ [K]: v }` resolved to a
  member named `K`, the const's own group lost its use, and its hover changed subject.
- **THE ROUND'S SECOND HALF WAS AN AUDIT FINDING, and it is the (API.16) product one
  round on.** `typeCaptureReportedType`'s KDoc listed an object-literal key as
  deliberately NOT closed and gave one reason: the useful answer is the CONTEXTUAL type's
  property, and a contextual type is walk-scoped state a capture cannot read. (API.10)
  then wrote `typeCaptureContextualType`, which is purely SYNTACTIC and is therefore
  precisely the mechanism that reason said did not exist. Nobody came back for it.
  Measured this round on a (BUG.4)-shaped fixture: **every** object-literal key answered
  `any`, or the COLLIDER's type where a same-spelled binding existed — `{ p: 1 }` against
  a `number` member reported `string`, the type of an unrelated file-level `const p`.
  That is the confidently-wrong answer *prove to offer* exists to prevent, and round
  930's own audit passed it as TRUE because its caret list did not include a key.
- **ONE CHECKER GAP WAS MEASURED AND LEFT ALONE, and it is stated in the fixture's KDoc**:
  a computed key whose literal is a no-substitution TEMPLATE does not supply the member it
  names (`{ [`p`]: v }` against a required `p` is TS2741), while the quoted and bare forms
  do. That is one layer below this API; the language service resolves the template key
  regardless, which is why the pin fixture's members are optional.

- **A ZERO ARM WAS A BLIND PIN, NOT A REDUNDANT GUARD — round 902's trap, caught by
  reading the FIXTURE rather than the arm.** C5 (the contextual walk stops reading a
  COMPUTED outer key) read 0 red on its first pass with a plausible story ready; the
  truth is that the nested pin's outer key was written as an IDENTIFIER (`n: { ["inner"]:
  v }`), so the shape exercised nothing. The fixture now nests under a computed key
  (`["n"]: { ["inner"]: v }`) and asserts that it does, in the pin itself — a fixture
  that must be a certain shape says so, or the next edit quietly removes the coverage.

- **PINS +18, FOUR INVERTED.** The new `ProjectComputedKeyTest` (16) carries the round's
  own shape: the occurrence set as an EXACT list against a fixture spelling `p` four more
  times in positions that are not the member, the delimiter-excluded span for a quote and
  a backtick alike, the caret-in-the-key direction, the nested computed key, `{ [K]: v }`
  in both directions, the free key, go-to-definition, four hovers, the completion
  refusal, and two renames asserted on the RESULTING TEXT. `ProjectContextualKeyTest`
  gains the computed key to its exact set and turns its refusal pin into an occurrence
  pin. The four inverted are round 930's two computed-key defect pins (now the rewrite and
  the loud refusal one shape over) and round 927's two refusal pins, each saying so in
  place.

- **WHAT REMAINS REFUSED, and NOTHING IS SILENT.** A computed or quoted METHOD key
  (`{ ["m"]() {} }`, `{ "m"() {} }`), a computed member of a TYPE LITERAL, and a binding
  element's string `propertyName` (`const { "p": local } = o`) are all SWEPT and all
  unplaced, so a rename that meets one refuses with `OCCURRENCES_INCOMPLETE` and names
  the span — measured, all three, on one fixture whose member is OPTIONAL, which is the
  shape that used to go through quietly. A caret ON one of them answers empty and refuses
  `NO_SYMBOL`. Completion inside any computed key answers `NONE`, which is tsc's own
  answer (a null result) at every one of four carets. And a computed member DECLARATION
  in a CLASS or an INTERFACE now RESOLVES — `i.ip` and `["ip"]` are one group of three
  spans and rename together, which fell out of the same declaration-name unwrap.

- **TEN-ARM ABLATION, one mistake at a time, each arm applied to and restored from a
  sha256-verified on-disk snapshot, each with an asserted RAN-COUNT** (round 931's
  dead-arm trap: a zero-red arm with a zero ran-count is not a redundant guard, it is no
  arm at all). `scripts/round932-ablate.py`; every arm read `ran 549`.

| arm | the mistake | red | what it uniquely shows |
|---|---|---|---|
| C1 | the population is element accesses ONLY | **14** | the pre-932 boundary — every query, in one set |
| C2 | the key's OWN declaration comes from the ASSIGNMENT, not the key node | **0 — MEASURED REDUNDANT** | see below |
| C3 | a declaration's name stops unwrapping a COMPUTED name to its literal | 2 | the rename SEED, and C2's reach proof |
| C4 | a computed key that is a NAME is admitted as if it spelled that name | 3 | `{ [K]: v }` — the regression this round backed out mid-flight |
| C5 | the contextual walk stops reading a COMPUTED outer key | 2 | the NESTED key, and only it |
| C6 | hover's object-literal-key arms are dropped | 2 | the audit finding: `any`, or the collider's type |
| C7 | `isMemberPosition`'s computed arm stops filtering to LITERALS | **0 — MEASURED REDUNDANT** | see below |
| C7b | THE REACH PROOF for C7: the same arm removed outright | **14** | the line is live and load-bearing in the other direction |
| C8 | a literal DECLARATION reports its raw extent, delimiters included | 1 | the span a host highlights for a computed member declaration |
| C9 | `occurrenceCaret` stops accepting a member-name literal | 6 | the FROM-the-literal direction, for all three spellings |

  **Eight distinct non-empty sets, and the two zeros are recorded as redundant guards
  with a REASON each rather than claimed as pins.** C2 is redundant *given* C3:
  `typeCaptureDeclarationName` unwraps a computed name, so asking
  `typeCaptureDeclarationLocation` for the ASSIGNMENT and for the KEY NODE answer the
  same `CapturedDeclaration` — two guards on one property at two layers, which is round
  927's A3/A8 law and its qualifier to round 807. C7's filter is redundant because
  nothing else in the population walk can reach `{ [K]: v }`'s identifier — but the arm
  it guards is emphatically live, which C7b measures at 14.
- **GATES.** Suite **15,006 → 15,024 / 0 failures / 0 errors / 3 skipped** (core
  UNCHANGED at 14,341; `-project` 531 → 549). `cost_gate.py` **+0.00% on all 20
  counters** — a real gate, since the round changes core; `huge_methods.py --fail-over
  0` clean on core (750 classes, 16,020 methods) and on `-project` explicitly (48
  classes, 465 methods); the round-920 token gate re-run because `SourceIndex` changed —
  **1,327 files, 101,287,620 chars, 3,936,158 identifiers, 0 violations**.
  `spine_closure_audit.py` not applicable. `docs/language-service.md` §§ 8, 9, 10b, 10d,
  13, 14.
- **§ 14's gap list: 8 → 7 live of the ten ever numbered, and the page's headline claim
  now holds without exception.** *Prove to offer* — every position either answers
  correctly or refuses and says why — had three live exceptions three rounds ago; round
  931 took two and this one takes the last. **Nothing anywhere in this API is silent.**

- **SUCCESSOR**: unchanged — the incremental / re-entrant seam, still the largest thing
  about this API and the only thing that moves the cost table. Below it, the three
  shapes above whose members the CHECKER does not put in a member table (a computed
  method key, a computed type-literal member), which are a checker item rather than an
  API one.

**Round 931b (2026-08-18) — (API.16): A MEMBER NAMED BY A TEMPLATE ELEMENT ACCESS.
§ 14's GAP 6 — THE ONE GENUINELY SILENT GAP IN THIS API — IS CLOSED, AND THE ROUND'S
PRODUCT IS THAT **A REFUSAL WITH ONE STATED REASON IS AN ASSET: ROUND 929's TEMPLATE
COMPLETION REFUSAL WAS CASHED, NOT OVERRULED, BECAUSE ITS REASON WAS WRITTEN DOWN AND
THIS ROUND REMOVED IT.**

- **STEP 1 WAS tsc, four oracles over one fixture** (`lsp_member_refs.py`,
  `lsp_rename.py`, `lsp_hover.py`, `lsp_completion.py`):

| caret / query | tsc 7.0.2 | ours BEFORE | ours AFTER |
|---|---|---|---|
| references of `I.p`, with a ``o[`p`]`` in the file | 4 spans, the template's `[110,111)` among them | 3 — **silently short** | 4 |
| the template's span | the TEXT, **backticks excluded** | — | the same |
| references FROM a caret inside the template | the same 4 | none | the same 4 |
| rename from the member declaration | rewrites ``o[`z`]`` | left `` `p` `` behind, **no conflict, no diagnostic** | rewrites it |
| rename FROM a caret inside the template | the whole group | nothing | the whole group |
| hover inside the template | `(property) I.p: number` | `string` (the literal's own type) | `number` |
| completion in ``o[`‸`]`` | 2 items, edit `[77,77)` | NONE — stated refusal | 2 items, edit `[77,77)` |
| completion in ``o[`al‸`]`` | edit `[94,96)` over `al` | NONE | the same |
| ``o[`p${x}`]`` — references | **0** | 0 | 0 |
| ``o[`p${x}`]`` — rename | `prepareRename` REFUSES | refuses | refuses |
| completion in a substitution template's HEAD | **null result** | NONE | NONE |

- **THE POPULATION IS THE WHOLE FEATURE, exactly as it was in round 926**: one predicate
  (`SyntaxRoles.isMemberNameLiteral`) and one enumeration now admit a no-substitution
  TEMPLATE beside the string, and `occurrenceNodes` / the completion anchor / the core
  capture all read it. **The refusal that remains is a NODE-CLASS boundary rather than a
  judgement**: a template with substitutions is a `TemplateExpression`, a different class
  with no fixed text, so it cannot be admitted by accident — and its HEAD is a
  `StringLiteralNode` that is not an element-access ARGUMENT, so it is not swept either,
  which is why it is not an obstacle to the member's rename.
- **THE ONE PLACE THE STRING'S OWN ROUTE DOES NOT TRANSFER IS HOVER, and it would have
  re-opened (API.15) one round after closing it.** (BUG.4)'s rule is "the type of the
  `"p"` in `o["p"]` is the type of `o["p"]`" — right for a string because the compiler's
  element-access typing keys a named member off a STRING literal argument. It does not
  key off a template, so ``o[`p`]`` types as `any`, and routing the template through the
  access would have replaced this position's old answer (`string` — wrong but harmless)
  with `any`, i.e. a plausible WRONG type where there had been a harmless one. The member
  is resolved through the RECEIVER instead, which is the definition leg's own walk. The
  divergence that remains is stated: no flow narrowing through the template form.
- **SEVEN-ARM ABLATION, each arm applied to a hash-verified snapshot and restored to one,
  each proved REACHED:**

| arm | mistake | red | verdict |
|---|---|---|---|
| B1 | the shared enumeration accepts strings only | **7** — including BOTH completion pins | the population is the feature, and the shared walk is why completion moves with it |
| B2 | the span keeps the backticks | **5** | the delimiter rule, and it also breaks the substitution-template control |
| B3 | the CORE capture's literal set narrowed | **7**, a different set — hover and the substitution control in, completion out | completion needs no core change, which is round 929's claim re-measured |
| B4 | `occurrenceCaret` accepts strings only | **1** | the FROM-the-template direction, and only it |
| B5 | hover routed through the access type | **1** | reproduces the `any` above |
| B6 | the token predicate additionally admits a `TemplateHead` | **0 — MEASURED REDUNDANT** | the shared walk refuses a substitution template first, so the token kind cannot decide it |
| B6b | the token predicate narrowed to `StringLiteral` | **2** (both completion pins) | THE REACH PROOF for B6: the same line is live and load-bearing in the other direction |

  B4's first pass read **`ran 0`** and was a DEAD ARM — the mistake used a type the file
  no longer imports, so nothing compiled — which is round 902's trap in its purest form:
  the driver had a `git diff --shortstat` per arm and it was GREEN. What separates them
  is the ran-count, so the driver asserts one; a zero-red arm with a zero ran-count is
  not a redundant guard, it is no arm at all.
- **PINS +8, TWO INVERTED.** `ProjectMemberOccurrenceTest` gains a KIND 4 section (the
  occurrence, the span, the caret direction, the applied rename, the substitution control
  in both directions, and hover) over its own fixture, so no count asserted by (API.9)'s
  own pins moves; `CompletionAnchorTest` gains the substitution refusal. The two
  inverted are round 930's silent-miss pin (now asserting the REWRITE) and round 929's
  template completion refusal (now asserting it completes exactly as the quoted form
  does), each saying so in place. Suite **14,998 → 15,006 / 0 failures / 3 skipped**.
- **GATES.** `cost_gate.py` **+0.00% on all 20 counters** — a real gate, since the round
  changes core; `huge_methods.py --fail-over 0` clean on core (750 classes) and on
  `-project` (48); the round-920 token gate re-run because `SourceIndex` changed —
  **1,327 files, 101,287,620 chars, 3,936,158 identifiers, 0 violations**.
  `docs/language-service.md` §§ 8, 9, 10a, 10b, 10d, 14.
- **§ 14's gap list: 9 → 8 live**, and both of round 930's deliberate defect pins are now
  inverted. What remains silent anywhere in this API is ONE shape: a computed key
  `{ ["p"]: v }` whose contextual member is OPTIONAL (gap 2).
- **SUCCESSOR**: unchanged — the incremental / re-entrant seam, still the largest thing
  about this API and the only thing that moves the cost table.

### QUEUE — work top-to-bottom; promote unblockers per protocol

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

- [ ] **(CHK.9) INDEX-SIGNATURE PARAMETER TYPES — 12 OURS-ONLY TS1268 ROWS IN ONE FIXTURE,
  THE LARGEST REMAINING SINGLE-CODE FP FAMILY (`indexSignatures1`, ALIGNED, round 941).**
  A parameter typed by a BRANDED string alias (`type TaggedString1 = string & { __brand }`),
  or by a UNION or INTERSECTION of template-literal types, is legal in pristine and refused
  by us: `interface I1 { [key: TaggedString1]: string }`, `{ [x: \`${string}xxx${string}\` &
  \`${string}yyy${string}\`]: string }`, `type Rec1 = { [key: Id]: number }`. Two of the twelve
  are a CODE divergence rather than an extra diagnostic — pristine says TS1337 at
  `[key: T | number]` / `[key: T & string]` where we say TS1268. The predicate to build is
  "does this parameter type REDUCE to string / number / symbol / a template-literal type",
  through aliases, unions and intersections. This is (CHK.5)(e)'s axis with a fixture and a
  count attached.

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

- [ ] **(CHK.14) `abstract new (…) => T` DOES NOT PARSE, AND NEITHER DOES `infer U` INSIDE A
  PARENTHESIZED CONDITIONAL EXTENDS — 6 OF THE 7 SURVIVING ROWS OF
  `controlFlowInstanceofWithSymbolHasInstance` (MEASURED round 942, isolated to three lines).**
  `type X = T extends (abstract new (...args: any) => infer U) ? U : never` gives
  TS1005 ×3 / TS1068 ×2 / TS1128 and then cascades into TS2355 / TS2564 / TS2304 for the names
  the failed parse never bound; the standalone `type X = abstract new () => number` fails the
  same way. **A SECOND, SEPARABLE defect in the same three-line probe**: the NON-abstract form
  `T extends (new (...args: any) => infer U) ? U : never` PARSES and then reports
  **TS2304 `Cannot find name 'U'`** at the true branch — an `infer` inside a PARENTHESIZED
  extends clause does not publish its name. Same class as the `using` / `infer X extends`
  cascades in `docs/pristine-divergences.md` § 2.3; one parser feature closes each.

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

- [ ] **(CHK.17) LIB AVAILABILITY IS DECIDED FROM THE *RAW* `ES3` TARGET DEFAULT WHERE tsc
  DEFAULTS AN UNSET TARGET TO THE LATEST — 5 OURS-ONLY ROWS, AND A SYSTEMATIC REAL-WORLD FP
  (round 943).** `libFeatureAvailable` answers `options.lib.isEmpty() -> options.target >= intro`
  and `RealLibResolver.resolve(libNames, options.target)` picks the lib SET from the same
  field, both off `CompilerOptions.target`'s `ES3` default; tsc's `getEmitScriptTarget`
  defaults an UNSET target to the latest standard, so its default lib is `lib.esnext.full`.
  A project with no `target` in its tsconfig therefore gets **TS2583 `Cannot find name
  'AsyncIterableIterator'. Do you need to change your target library?`** (pristine
  `uniqueSymbols` line 221 / `uniqueSymbolsDeclarations` line 217, each with a cascaded
  TS2322) and **TS2550 for `Array.from`** (`intersectionTypeInference3` line 12) where
  pristine is silent. **This is round 941's TS18028 defect one family over and the same
  shape of fix — `options.targetExplicitlySet` decides — but its blast radius is bigger:
  the lib SET, not just a gate**, so it wants its own round with the 8-profile grid, the
  sweep and a corpus run (the corpus uses the EMBEDDED lib, so the two halves must be
  measured separately). Note the CLAUDE.md entry that records the current behaviour as
  deliberate ("the CHECKER reads RAW `options.target` for lib-availability") — round 943's
  evidence is that pristine disagrees, so that entry is the thing to re-judge first.

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

- [ ] **(CHK.19) A FUNCTION-BODY TYPE ALIAS IS NOT BOUND, SO A LIB NAME WINS — 1 OURS-ONLY
  TS2314 (`conditionalTypes1` line 297, round 943).** `function f50() { type Omit<T extends
  object> = …; type A = Omit<{ a: void; b: never }> }` reports **`Generic type 'Omit'
  requires 2 type argument(s)`** because the block-scoped alias is invisible (CLAUDE.md's
  B83.5) and the LIB's two-parameter `Omit` answers instead. Round 748 closed exactly this
  for `enum` with `lexicalTypeSymbolForNode` reading the INV.2(c) scope space (`scope.symbols`
  only, never `existing`); a type ALIAS is the same shape one declaration kind over. Note the
  same fixture shows the SILENT variant of this family too — `keyRemappingKeyofResult`'s
  `type Orig` inside `function f<T>()` resolves, so the gap is specifically about a
  block-scoped name that SHADOWS an outer one.

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
