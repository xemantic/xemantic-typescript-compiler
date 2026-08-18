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

**Round 931a (2026-08-18) — (API.15): AN ENUM MEMBER'S DECLARATION NAME REPORTS ITS OWN
TYPE. THE LAST POSITION IN THIS API ANSWERING A PLAUSIBLE **WRONG** TYPE INSTEAD OF
NOTHING IS CLOSED, AND THE ROUND'S PRODUCT IS THAT **THE CALL EVERY OTHER MEMBER LEG
MAKES ANSWERS `any` HERE** — the fix is not "ask the owner harder", it is a different
mint.**

- **STEP 1 WAS tsc, 15 CARETS.** `scripts/lsp_hover.py` over `tools/tsgo-7.0.2/lib/tsc
  --lsp -stdio`, five enum shapes plus controls:

| caret | tsc 7.0.2 | ours BEFORE | ours AFTER |
|---|---|---|---|
| `enum Plain { Alpha }` decl | `(enum member) Plain.Alpha = 0` | **`any`** | `Plain.Alpha` |
| `enum Valued { Gamma = 5 }` decl | `(enum member) Valued.Gamma = 5` | **`any`** | `Valued.Gamma` |
| `const enum Konst { Eps }` decl | `(enum member) Konst.Eps = 0` | **`any`** | `Konst.Eps` |
| `enum Str { Zeta = "z" }` decl | `(enum member) Str.Zeta = "z"` | **`any`** | `Str.Zeta` |
| `declare enum Amb { Iota }` decl | `(enum member) Amb.Iota` — **no value** | **`any`** | `Amb.Iota` |
| the USE of each | the same string as its declaration | already right | unchanged |
| `interface I { p }` decl (control) | `(property) I.p: string` | `string` | `string` |
| `enum Plain` NAME (control) | `enum Plain` | untouched | untouched |

- **THE DIVERGENCE IS DELIBERATE AND IT IS THE PAGE'S OWN CONVENTION: tsc decorates the
  answer with the member's VALUE and `QuickInfo.displayString` renders TYPES** (that is
  what it is documented as, and what every other row of § 8 carries), so agreeing about
  the type is agreeing. The ambient row is the cheapest evidence that this costs
  nothing: tsc drops the value there — a non-const ambient member with no initializer
  HAS none, CLAUDE.md's rule — and a value-free rendering has no such case to get wrong.
- **THE MECHANISM, AND WHY THE GENERAL LEG COULD NOT REACH IT.** `(API.11)`'s owner leg
  reads the owner's DECLARED type and asks it for the member; an enum's declared type is
  a member-LESS `Type.Object` (we mint one opaque type per enum where tsc models the
  union of its members), so the collection found nothing, the leg answered null and the
  name fell through to the free-name path — which types a name nothing binds as `any`.
  The new leg (`Checker.typeCaptureEnumMemberType`, 8 lines) takes the enum symbol's own
  export table and mints through **`getDeclaredTypeOfEnumMember` and nothing else**,
  which is the interning helper CLAUDE.md requires for this key space, so the reported
  type is the very instance the use site reports.
- **THE ROUND'S PRODUCT, MEASURED AS ARM A2: `getTypeOfSymbol(memberSymbol)` — the call
  every other member leg in this capture makes — answers `any` for ALL FIVE enum
  shapes.** So the interning helper is not a stylistic preference here; it is the only
  call that answers at all, and a next agent reading the leg's one-line body has that
  written into its KDoc rather than having to re-run the experiment.
- **THREE-ARM ABLATION, each arm restored from a sha256-verified snapshot, each proved
  REACHED** (round 902's dead-arm trap):

| arm | mistake | result | reached? |
|---|---|---|---|
| A1 | drop the owner-identity check (`declarations.any { it === owner }`) | **0 red — MEASURED REDUNDANT** on all six probe shapes | proved by A1b, which moves the same site |
| A1b | force that check to refuse | all five enum shapes back to `any`, control (a class field) unchanged | the leg's whole population moves — this IS the reach proof, and it reproduces round 930's measured defect exactly |
| A2 | mint with `getTypeOfSymbol` instead of `getDeclaredTypeOfEnumMember` | all five back to `any` | same |

  A1's zero is a REDUNDANT GUARD and is recorded as one, with its reason: round 748's
  lexical scope space binds a block-scoped `enum`, so the owner name always finds the
  enum under the caret — a block-scoped shadow, an import collision, a namespace
  nesting, an import ALIAS shadow and a merged pair all answer identically without it.
  It is kept as the sibling leg's rule and NOT claimed as a pin; what IS pinned is the
  import-alias shadow's ANSWER (`Local.Alpha`, never the imported `Kind.Alpha`), which
  is the only shape where the two candidate enums have DIFFERENT names and the display
  can therefore tell them apart. The first draft of that control named both enums
  `Outer` and could not discriminate in principle — a pin over a shape whose two
  outcomes RENDER THE SAME is no pin, which is round 907's "split the identity by the
  axis the mistake could be confined to" in the display layer.
- **PINS +2 and one INVERTED.** `LanguageServiceStateTest`'s deliberate defect pin
  becomes `an enum member's declaration name reports the SAME type its use reports` and
  says so in place (round 930 wrote it expecting exactly this edit); beside it, all five
  shapes and the import-alias control. `ProjectMemberDeclarationTest`'s hover test gains
  the enum row against its existing collider fixture. Suite **14,996 → 14,998 / 0
  failures / 3 skipped**.
- **GATES.** `cost_gate.py` **+0.00% on all 20 counters** — a real gate, since the round
  adds core code; `huge_methods.py --fail-over 0` clean on core (750 classes, largest
  6,353) and on `-project` explicitly (48 classes). No `SourceIndex`/parser change, so
  the round-920 token gate does not apply. `docs/language-service.md` §§ 8, 13, 14 (gap
  7 struck, the maturity row, the audit table, the status paragraph).
- **§ 14's gap list: 10 → 9 live, and the *prove to offer* rule has no live violation
  left.** Every remaining gap is a silence or a stated refusal.

**Round 930 (2026-08-18) — (API.13): § 14 AUDITED BY EXECUTION. THE ROUND'S PRODUCT IS
THAT **A PAGE OF PROSE ABOUT BEHAVIOUR DECAYS AT ABOUT ONE FALSE CLAIM PER THREE ROUNDS**
— § 14 was written in round 928 and had FOUR false claims by round 930, one of them a
defect that had been FIXED BEFORE IT WAS WRITTEN, and one of them a real defect the page
had been promising for two rounds.**

**THE AUDIT TABLE IS THE ROUND.** Every claim was established by RUNNING it — a fixture
through the API, `tools/tsgo-7.0.2/lib/tsc --lsp -stdio` as the oracle where the claim is
about parity, the cost table re-taken on the compiler profile. Reading the source to
confirm a claim was ruled out in advance, because that is exactly how the stale one
survived.

| § 14 claim | verdict | how it was established |
|---|---|---|
| positions carry a lone-`\r` defect (`(BUG.1)`, § 6 + the maturity row) | **STALE** — closed round 915 | a `\r`-terminated fixture: TS2322 line 3, TS1123 line 3, `positionAt` line 3, LF control identical |
| go to definition is complete for `this`/**`super`** (§ 14's row AND § 9's own table, line 480) | **WRONG — a defect** | `definitionsAt` on `super.pb` answered `[]` while `quickInfoAt` at the same caret answered `number`; tsc navigates to `Base.pb`. **Fixed this round** |
| an enum member's declaration name "reports nothing" | **WRONG, and worse than documented** | four enum shapes (plain / valued / `const enum` / string), all `any`; tsc: `(enum member) Plain.Alpha = 0`. Now (API.15) |
| an object literal's method "refuses a rename loudly" | **HALF WRONG** — true only where the literal is contextually typed | with no contextual type `renameAt` from either end plans both occurrences and the applied text compiles; with one, `OCCURRENCES_INCOMPLETE` at the key. **Found by measuring the round's own correction** |
| a computed key `{ ["p"]: v }` is "not reported either" | **OVERSTATED** — reported in 2 of 3 shapes | contextual+required → `WOULD_NOT_COMPILE`; no contextual type → `OCCURRENCES_INCOMPLETE`; contextual+**optional** → genuinely silent |
| a template `` o[`p`] `` is silently missed | **TRUE**, proven end to end | rename applies, template keeps the old name, and the applied program has **zero** diagnostics — nothing anywhere can see it |
| `documentHighlightsAt` 6.0–7.2 s | **TRUE of `checker.ts`**, unqualified | 6.3 s on `checker.ts`, **5.0–5.5 s on `types.ts`** — the row is a statement about a FILE |
| a plain rebuild: 5.5–5.9 s (§ 14) vs ~5.2 s (§ 3) | **BOTH DRIFTED, opposite directions** | re-taken 5.0–5.5 s warm; ~9 s for the first rebuild in a process |
| `referencesAt` 8.3–13.5 s / `renameAt` 13.3–24.5 s | **TRUE, band widened** | 8.3–10.2 clean, 13.2–14.8 dirty; rename 14.3 s (`createTypeChecker`) – 21.0 s (`SyntaxKind`), 19.6–26.7 dirty |
| the build COUNTS (1 per query, 2 dirty refs, 2/3 rename, 0 for a syntax refusal) | **TRUE, every row** | counted at the backing `Vfs`, and now pinned |
| "gated over 101 M characters" | **TRUE** | `round920-token-gate.sh` re-run: 1,327 files, 101,287,620 chars, 3,936,158 identifiers, 0 violations |
| `o["` completes / a template refuses; keywords; accessibility; overlay-only files; UNCLASSIFIED; batching; the four rename refusals; export/import plainness; hover's one subject | **TRUE** | one fixture each; already pinned elsewhere, mapped rather than duplicated |

- **THE DEFECT, AND WHY NOTHING SAW IT.** `Checker.typeCaptureMemberSymbols` — the
  receiver leg behind go-to-definition — carried a `this` carrier and no `super` one, so
  `super.p` reached `getTypeOfExpression` as a name nothing binds, typed `any`, and found
  no members. Its TYPE twin `typeCaptureThisMemberType` has had the super branch since
  (BUG.4), which is why **hover was right and definition was empty at the same caret** —
  and why every reading of § 8 confirmed a claim § 9 was failing. The fix is eight lines
  mirroring that twin: resolve the this-type, take its `baseTypes`, collect there. It is
  the base's declaration in both directions — the override's never — measured against tsc
  in both the overridden and the inherited shape (`scripts/lsp_definition.py`, new, the
  fourth oracle beside hover/refs/rename/completion). It declines in a STATIC member, the
  same decline the `this` rule already documents, and with no base class.
- **WHAT IS NOW PINNED, AND WHAT CANNOT BE.** `LanguageServiceStateTest`, 15 tests: the
  super leg and its two negative controls; the three gaps no test covered (the enum
  declaration name, the objlit method's rename in BOTH its shapes, the template's silent
  miss); the two
  computed-key refusals and the one silent shape; the tagged-template refusal at the API
  and not only at its anchor; and **the cost table's `builds` column, every row**. The
  wall column is marked **(not pinnable)** in the page itself — a timed assertion over a
  compile is a coin flip (round 868) — and is re-taken by
  `scripts/round930-ls-cost.sh` + `LanguageServiceCostMain` instead: one process, one
  project, three rotations, a discarded warm-up, so the arms are comparable to each other
  and to nothing else.
- **TWO PINS DELIBERATELY ASSERT A DEFECT** (the enum `any`, the two silent misses) and
  say so in place, with tsc's answer beside them, so that closing either is an edit here
  rather than an accident nobody notices. That is the same reason § 14 lists them at all.
- **THE MAPPING STEP PAID FOR ITSELF**: before writing anything, all sixteen § 14 claims
  were mapped onto the existing suite, which found 10 of them already pinned (positions in
  `LineMapTest`/`ProjectPositionTest` — including round 915's own `a lone CR file's
  diagnostics agree with this map too`, which is what proves the § 6 note stale — the
  `o["` pair in `ProjectStringMemberCompletionTest`, read/write in `SyntaxRoleTest`,
  batching in `ProjectSemanticsTest`, the refusals in `ProjectRenameTest`). Only the
  unpinned six were written, so the class is additive rather than a second copy.
- **GATES.** Suite **14,981 → 14,996 / 0 failures / 3 skipped** = exactly the +15.
  `cost_gate.py` **+0.00% on all 20 counters** — a real gate, since the round adds core
  code, and structurally expected since the capture path is opt-in.
  `huge_methods.py --fail-over 0` clean on core (750 classes scanned, largest 6,353) and
  on `-project` explicitly (48 classes, largest 561). Round-920 token gate re-run.
  `docs/language-service.md` §§ 3, 6, 8, 9, 10b, 10d and a rewritten § 14.
- **SUCCESSOR**: (API.15), the enum member declaration hover — one leg, mechanism named in
  the queue entry — and behind it the unchanged one, the incremental / re-entrant seam,
  which is still the largest thing about this API and the only thing that moves the cost
  table.

**Round 929 (2026-08-18) — (API.12): COMPLETION INSIDE `o["`. THE LAST QUERY THAT DID
NOT ANSWER AN ELEMENT ACCESS, AND THE ROUND'S PRODUCT IS THAT **THE PARSER'S OWN
`isUnterminated` IS FALSE FOR THE ONE STATE THIS FEATURE EXISTS FOR** — a lone opening
quote — so the classifier that reads it has to check the ARITHMETIC as well as the flag.**

- **STEP 1 WAS tsc ITSELF, 21 carets over three fixtures** (`scripts/lsp_completion.py`,
  new; it reuses `lsp_rename.py`'s client over `tools/tsgo-7.0.2/lib/tsc --lsp -stdio`).
  Every design decision below is a row of this table rather than a prediction:

| caret | tsc 7.0.2 | ours BEFORE | ours AFTER |
|---|---|---|---|
| `o["‸"]`, nothing typed | 4 items, labels UNQUOTED, edit `[441,441)` | NONE | 4 items, MEMBER |
| `o["al‸pha"]` | 4 items, edit `[461,466)` — **the TEXT, quotes excluded** | NONE | the same |
| `o["alpha‸"]` at the text's end | 4 items, edit over the whole text | NONE | the same |
| `o['‸']` single quotes | 4 items | NONE | 4 items |
| `` o[`‸`] `` TEMPLATE | 4 items | NONE | **NONE — deliberate** |
| `o["‸"]` where `o` is `any` | **0 items** | NONE | 0 items, and NOT a refusal |
| a NUMERIC index signature | only the named member | NONE | only the named member |
| a STRING index signature | only the named member | NONE | only the named member |
| an enum `E["‸"]` | its 2 members, kind `EnumMember` | NONE | its 2 members |
| `this["‸"]` in a method | 3 items, the `private` one included | NONE | the same 3 |
| **UNTERMINATED `o["‸`** before a newline | 2 items, **and NO textEdit at all** | NONE | 2 items, span to the token's end |
| **UNTERMINATED `o["‸`** at end of file | 2 items, no textEdit | **FREE_NAME — the whole scope** | 2 items |
| `o["alpha"‸]` past the closing quote | **free names** (1,074) | FREE_NAME | FREE_NAME |
| `o[‸]`, no quotes at all | free names | FREE_NAME | FREE_NAME |
| `o[‸"alpha"]`, before the opening quote | **free names** | NONE | NONE — stated |
| a plain `"alpha"` that is no member name | **null result** | NONE | NONE |
| an object-literal key `{ "‸": 1 }` | **null result** | NONE | NONE |
| an indexed-access TYPE `Bag["‸"]` | **free names**, not members | NONE | NONE — stated |
| `w("‸")` where `w` takes `keyof Bag` | the 2 keys, from the CONTEXTUAL type | NONE | NONE — a stated gap |

- **THE ITEM SAID "AN ANCHOR QUESTION, ONE CLASSIFIER" AND THAT IS EXACTLY WHAT IT WAS:
  the member enumeration is round 917's, UNCHANGED, and there is no core change at all.**
  `Project.completionsAt` drives the member half entirely from `CompletionAnchor.receiver`,
  so making the anchor answer `MEMBER` with the element access's `expression` buys the
  union rule, the accessibility filter, the `this` leg and the export-table leg for free —
  which is why the enum, the `any` receiver, the imported interface and the `private`
  member all came back right on the first run. The classifier is one function
  (`SourceIndex.stringMemberAnchorAt`) plus one enumeration
  (`SyntaxRoles.stringElementAccessAt`), and the enumeration is **deliberately (API.9)'s
  own walk** — "a string literal is a member name only in an element-access position" is
  now ONE predicate serving both the occurrence sweep and the anchor, so completion and
  rename cannot drift apart about what a member name is.
- **THE ROUND'S PRODUCT, AND IT IS A TRAP FOR ANY READER OF `StringLiteralNode`:
  `isUnterminated` IS FALSE FOR A LONE `"`.** `Parser.kt` decides it as
  `startsWithQuote && raw.last() != quote`, and for a one-character raw text the first
  character IS the last — so `bag["` at end of file parses as a TERMINATED empty string.
  That is precisely the state a completion request is normally made in, and before this
  round it answered `FREE_NAME`: the caret sits one past a one-character token, contained
  by nothing, and the whole lexical scope (1,000+ lib names) was offered INSIDE the
  string. The anchor therefore reads `isUnterminated || tokenEnd - start < 2` — arithmetic,
  not a character test, since a closed literal needs an opening and a closing quote. Arm
  A4 is exactly that term and reddens exactly the end-of-file pins.
- **THE SPAN IS THE TEXT, QUOTES EXCLUDED — round 926's rule, one query over**, and it is
  tsc's measured edit range. Accepting an item leaves exactly one pair of quotes, which is
  asserted by APPLYING the edit and recompiling (round 925's shape) rather than by reading
  the arithmetic back: a span that eats a quote writes `bag[has space]`, which does not
  parse. Since it is the same span a member rename writes into, completing a name and then
  renaming it edit the same characters.
- **A MEMBER WHOSE SPELLING IS NOT AN IDENTIFIER (`"has space"`, `"1abc"`) IS OFFERED, AND
  IT NEEDED NOTHING**: the member capture excludes only the empty and `__`-prefixed
  spellings; `typeCaptureIsWritableName`, which would have filtered them, is the FREE-NAME
  leg's and was never on this path.
- **ONE REFUSAL IS THIS ROUND'S OWN CHOICE AND IS THE (API.11) PRECEDENT APPLIED**: a
  TEMPLATE element access, which tsc completes. (API.9)'s occurrence population is string
  literals only, so a member written through a template is one a later rename cannot find
  — offering it would invite text this API cannot maintain. **And measuring that refusal
  found a SILENT GAP one layer down: tsc counts `` o[`p`] `` as a reference** (4 spans on a
  4-occurrence fixture), so our references and rename miss it and do not say so. Recorded
  as § 14's gap 6; the old gap 6 was this round's own item.
- **NINE-ARM ABLATION** (`scripts/round929-ablate.py`), one mistake at a time, anchored
  replacements with asserted occurrence counts, restored from a sha256-verified on-disk
  snapshot, with a per-arm POSITIVE RUN CONTROL (506 `-project` tests must have run).

| arm | the mistake | red | what it uniquely shows |
|---|---|---|---|
| A1 classifier-off | the anchor answers nothing for a string caret | **18** | the pre-929 boundary |
| A2 quote-span | the span starts at the QUOTE, not at the text | **10** | THE QUOTES — the accepted item writes `bag[alpha"]` |
| A3 position-blind | the lookup ignores WHICH literal the caret is in | **10** | the plain-string boundary; differs from A2 by that pin and by the anchor-only pins |
| A4 no-length-arithmetic | only the parser's `isUnterminated` is believed | 2 | the lone-quote defect, i.e. `o["` at end of file |
| A5 no-caret-at-token-end | only a caret CONTAINED by a token is considered | 4 | every unterminated shape; A4 ⊂ A5 |
| A6 past-closed-quote | a caret past a CLOSED literal's quote is admitted | **0** | MEASURED-REDUNDANT — see below |
| A7 opening-quote | the caret AT the opening quote is admitted | **0** | MEASURED-REDUNDANT, an exactly equivalent later guard |
| A8 token-kind | the caret's token KIND stops being consulted | **0** | MEASURED-REDUNDANT — a cost guard, not a correctness one |
| A9 REACH CONTROL | A6's guard AND the span's upper bound, together | 1 | that A6's line is on that caret's path and its pin is not vacuous |

  **Five distinct non-empty sets, with A1 ⊃ everything and A4 ⊂ A5.** The three zero arms
  are REACHED and not dead, proved by other arms rather than by new instrumentation (round
  928's mechanism): all three lines are strictly UPSTREAM of A2's edited line in the same
  function, and A2 reddens 10 tests, so control passes them. Each is redundant for a
  DIFFERENT stated reason — A7's condition is term-for-term identical to the span's lower
  bound, A8's decision is made again by the node-kind requirement one level down, and A6
  is the DUAL of the span's upper bound (arm A9b, run separately, is also 0 red, so either
  guard alone answers that caret). A9 is deliberately TWO mistakes and is credited to no
  pin (round 807): it exists only to show the pin is real.
- **A ZERO ARM WAS A MISSING PIN, NOT A REDUNDANT GUARD — round 902's trap, hit and
  fixed.** A6 read 0 red on its first pass with a plausible story ready; the truth is that
  `o["alpha"‸]` never reaches A6's line at all, because `]` begins a token AT the caret and
  the token-kind test refuses first. The rule is reachable only when NO token begins there
  — `o["alpha"‸ ]`, with a space — which is a pin this file did not have. Added, and A9
  then reddens it.
- **GATES.** Suite **14,955 -> 14,981 / 0 failures / 0 errors / 3 skipped = exactly the
  +26** (`-project` 480 -> 506, core UNCHANGED at 14,341). `cost_gate.py` **+0.00% on all
  20 counters** — a CONTROL here rather than a gate, and structurally so: the round adds no
  core code. `huge_methods.py --fail-over 0` clean on core and on `-project` explicitly.
  The round-920 token gate re-run because `SourceIndex` changed. `spine_closure_audit.py`
  not applicable. Warning-clean.
- **PINS +26**: 10 parse-only anchor pins in `CompletionAnchorTest` and 16 end-to-end in
  the new `ProjectStringMemberCompletionTest`. **THE DISCRIMINATOR** is round 917's own,
  reused: a receiver whose members are spelled exactly like unrelated top-level bindings,
  asserted as an EXACT list — the wrong answer here is the file's whole scope, a SUPERSET
  that contains the right names. Two pins are REGRESSION pins rather than discriminators
  (a caret past the closing quote must stay a free-name caret) and say so in place.
- **SUCCESSOR, ranked, and unchanged from round 928 bar one item now closed.**
  (1) **The incremental / re-entrant seam** — every query is a full rebuild (5.5-5.9 s warm
  on tsc's own sources) and a rename is two; § 14's cost table is the case for it, and it
  is the architecture inversion rather than an API item, which is why it needs the owner.
  (2) **A template element access in the occurrence population** — newly measured as a
  SILENT gap in references and rename, and the reason completion refuses that position;
  small, but it needs the checker to resolve `` o[`p`] `` as a member access first.
  (3) An **LSP protocol layer**, which the owner deferred.

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

- [ ] **(CHK.5) COMPUTED KEYS — STAGE (a) IS LANDED (round 937); (b), (c), (d) AND A
  NEWLY MEASURED INDEX-SIGNATURE AXIS REMAIN.**
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
  **(b) A LATE-BOUND DUPLICATE — now wanted for TWO reasons.** `{ p: 1, [K]: 2 }` is
  TS1117 in tsc and silent here; `interface Dup { p: number; [K]: string }` is TS2300 x2 +
  TS2717 in tsc, which keeps the FIRST type, and round 937 made it emit a spurious TS2322
  here instead. **That TS2322 is NOT a computed-key defect** — our member map is last-wins
  for EVERY duplicate spelling, measured at HEAD for a plain `p: number; p: string` and for
  `["p"]` — so (b) is two things: teach the duplicate SCAN the same syntactic namer (it is
  a separate AST scan, so it can just ask), and decide whether the member map should be
  first-wins for a duplicate, which is where the spurious TS2322 lives.
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
  **WHAT MUST NOT BE UNDONE**: the WELL-KNOWN-symbol route is deliberately not
  `computedSymbolKey` in general (tsc is SILENT for every computed key it cannot late-bind,
  measured over seven of them), and `getMemberName` itself stays unchanged — B451 records
  it as feeding ~20 callers including duplicate detection and abstract tracking, so the
  widening lives in `declaredMemberName` at the member-BUILDING call sites.

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
