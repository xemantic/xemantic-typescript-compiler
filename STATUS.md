# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **191,724** lines (**−8,239 across (P18.53)-(P18.65)**; steps 10a-10d are SEMANTIC changes and ADD 85, 86, 14 and 33, not extractions; 191,070 when
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

**(P18.63) — (INV.0) STEP 10b: THE VALUE SPACE, AND THE HALF THAT HAD TO BE REFUSED, 18,536 / 0 / 3 (2026-09-10).**
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

