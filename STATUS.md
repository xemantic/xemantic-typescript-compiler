# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **200,293** lines (**+94 at (P18.190)**, the callee-signature predicate fallback and its
declaration-typing helpers — a SEMANTIC parity change, `rxjs` 3 -> 2; **+157 at (P18.189)**, else-branch narrowing at the spine and both legacy
If arms with its `||`-negation, nullish-equality and guard-call helpers — a SEMANTIC parity change, `rxjs` 4 ->
3; **+50 at (P18.188)**, two `boolean`-minus-literal helpers at three narrowing
sites and the argument arm — a SEMANTIC parity change, `rxjs` 5 -> 4; **the file crossed 200,000 lines**;
**+28 at (P18.187)**, a function-wide definitely-assigned set installed
around the TS2454 walks — a SEMANTIC parity change removing a FALSE-POSITIVE class, `rxjs` 6 -> 5;
**+23 at (P18.186)**, a private-visibility relation predicate and the
inaccessible-constructor returns — a SEMANTIC parity change closing a false-NEGATIVE class; the rest is
`MemberResolver.kt` 834 -> 840 and `Relater.kt` 1,748 -> 1,758; **+41 at (P18.185)**, tsgo's `getMinArgumentCountEx` as one helper the
relation reads, with its KDoc — a SEMANTIC parity change removing a FALSE-POSITIVE class, `rxjs` 7 -> 6;
`Relater.kt` 1,746 -> 1,748; **+117 at (P18.184)**, a lexical callee-receiver resolver and binding
typer for contextual `this` with their KDoc — a SEMANTIC parity change removing a FALSE-POSITIVE class,
`rxjs` 10 -> 7; **+83 at (P18.183)**, the named-object argument gate, its classifier
and the scoped chain relaxation with their KDoc — a SEMANTIC parity change closing a false-NEGATIVE class
at call arguments, +0 rows on every profile and library; **+59 at (P18.182)**, a one-condition guard removal, a method-parameter
property-bag instantiation arm and their KDoc — a SEMANTIC parity change that takes `rxjs` 17 -> 10;
**+145 at (P18.181)**, three helpers porting tsgo's union-source
inference and union-target head with their KDoc — a SEMANTIC parity change taking a 20-cell matrix from
2 to 15 agreeing with tsgo and buying NO library row, which the note says; **+131 at (P18.180)**, one substituted-member helper, tsgo's
`inferFromObjectTypes` tail as a structural leg of the contextual-return inference, an out-of-scope
candidate filter and their KDoc — a SEMANTIC parity change taking 14 more contextual cells to
agreement with tsgo and buying NO library row, which the note says; **+61 at (P18.179)**, tsc's missing `instanceof`
positive-branch tail as one helper at two sites, with the KDoc recording the one leg-ordering cell
where this checker's lack of a subtype relation diverges from tsgo — a SEMANTIC parity change
removing a FALSE-POSITIVE class, not an extraction; **+233 at (P18.178)**, the contextual-return
inference leg, its narrow `inferTypes`, two mention/bind gates, a re-entrancy counter and the
KDoc recording two deliberate refusals with tsgo's measured answer — a SEMANTIC parity change
taking 13 of 14 contextual sources to agreement, and buying NO library row, which the note
says; **+18 at (P18.177)**, the contextual-`this` gate
split in two and tsgo's exact skip condition, net of the fold it replaces — a SEMANTIC parity
change taking a 5-position matrix from 1 to 5 agreeing with tsgo, and buying NO library row,
which the note says; **+81 at (P18.176)**, the in-scope type-parameter
predicate and the KDoc recording which three candidate signals were measured and REJECTED — a
SEMANTIC parity change that buys parity and NO library row, and says so; **+43 at (P18.175)**, one
`shadowedTypeParamNames` helper threaded through SIX nested-container boundaries of the TS2302
walker, net of the inline subtraction it replaces — a SEMANTIC parity change that only REMOVES
rows, not an extraction; **+55 at (P18.174)**, a binder-symbol consult at the
class-type reader and a lib-global shadow guard at the assignment reader, with the KDoc carrying
the corpus receipt that makes one of them load-bearing and the measurement that makes the other
redundant — a SEMANTIC parity change opening the `rxjs` arc, not an extraction;
**+82 at (P18.173)**, the element-access arm, its
slot-type helper and the widened kind test, with the KDoc recording why tsgo's literal shape is
wrong here — a SEMANTIC parity change that takes the `marked` library to ZERO ours-only rows,
not an extraction; **+161 at (P18.172)**, the cross-union contextual
member type, the literal-under-context rule, two context-install widenings and the union-aware
firewall, with the KDoc recording the FIVE pieces and the two measured-redundant/blind ablation
arms — a SEMANTIC parity change that takes `marked` to ONE row, not an extraction;
**+79 at (P18.171)**, the discriminant-property
predicate and its three helpers; the round's real weight is `Relater.kt` **1,557 -> 1,746**
(**+189 net**), a PORT of tsgo's `typeRelatedToDiscriminatedType` plus the `excluded` property
set threaded through the object relation — a SEMANTIC parity change taking a 25-cell matrix to
23 agreeing with tsgo, not an extraction; **+226 at (P18.170)**, the lexical return-identifier
resolver — a parent-chain walk, a shadow-stop and a typing helper — and the KDoc recording the
`!`-policy the 8-profile grid forced, the scope-leaking leg that was built and REMOVED, and the
one measured-redundant barrier; a SEMANTIC parity change that takes a 39-cell matrix from 15 to 36
agreeing with tsgo, not an extraction; **+85 at (P18.169)**, one `if` routing a generic
type-ALIAS heritage base through the annotation path, and its KDoc recording the three measured
faces, the caller census and the two measured-redundant barriers — a SEMANTIC parity change that
removes a FALSE-POSITIVE class and pays the language-service leg through `CaptureRecorder`, not an
extraction; **+36 at (P18.168)**, one syntactic arm in `spineItEdge` and its KDoc recording why the test is syntactic, what the suppression deliberately does NOT do, and the measured-redundant conjunct — a SEMANTIC parity change removing a FALSE-POSITIVE class, not an extraction; **+23 at (P18.167)**, the class-type-parameter scope fold and its KDoc, net of the static gate that was built, measured LOSSY and REMOVED — a SEMANTIC parity change closing a WRONG-TYPE class, not an extraction; **+113 at (P18.166)**, the literal-key read arm, the intersection WRITE slot, the shared `cheaKeyLiterals` and the intersection-display parenthesization with their KDoc — a SEMANTIC parity change closing a silent-`any` class, not an extraction; **+190 at (P18.165)**, the general element-access WRITE reader, its three measured refusals and the one-row-per-site dedupe, with their KDoc — net of the B85.1d tail EXTRACTED rather than copied, so the arm is a fallback behind it; a SEMANTIC parity change closing a false-NEGATIVE class, not an extraction; **+83 at (P18.164)**, the shared `signatureDeclaredArity` and the union reader's KDoc, net of the 11-line inline `when` the extraction REMOVED — a SEMANTIC parity change that also retires a duplicate, not an extraction; **+180 at (P18.163)**, the module-object missing-member gate and its display with their KDoc, net of the INERT `syntheticModuleAmbientCarrier` map removed by the round's own ablation — a SEMANTIC parity change with a typed-interop payoff, not an extraction; **+11 at (P18.160)**, the widened (CHK.42) pre-pass and its KDoc — a SEMANTIC parity change removing a FALSE-POSITIVE class, not an extraction; **+52 at (P18.159)**, the merged-namespace statics attachment and its KDoc — a SEMANTIC parity change with a typed-interop payoff, not an extraction; **UNCHANGED at (P18.158)**, whose whole change is 29 lines of `NameResolver.kt` — a SEMANTIC parity change in a COLLABORATOR, which is the shape (INV.0) wants; **+119 at (P18.157)**, the external-module value type, the shadow guard and their KDoc — a SEMANTIC parity change with a MISSION payoff, not an extraction; the collaborator half is `NameResolver.kt` +29; **UNCHANGED at (P18.156)**, whose whole change is 26 lines of `NameResolver.kt` — a SEMANTIC parity change in a COLLABORATOR, which is the shape (INV.0) wants; **+92 at (P18.155)**, the property-access TS7009 arm, its refusal helper and their KDoc — a SEMANTIC parity change, not an extraction; **+210 at (P18.154)**, the literal-set intersection reduction, the value-keyed literal dedupe and their KDoc, plus B218's sort and replacement — a SEMANTIC parity change, not an extraction; **UNCHANGED at (P18.153)**, a recon round that touched no compiled code; **+22 at (P18.152)**, the default-import-clause arm and its KDoc — a SEMANTIC parity change, not an extraction; **UNCHANGED at (P18.151)**, a test-side round whose `Checker.class` is BYTE-IDENTICAL to (P18.150)'s landed binary; **+31 at (P18.150)**, two chain-depth drills and their KDoc — a SEMANTIC parity change, not an extraction; **+188 at (P18.149)**, TS2430 routed through the general elaboration chain plus the structural arm for the two member shapes the name-based walker cannot NAME, with their KDoc — a SEMANTIC parity change, not an extraction; **+24 at (P18.148)**, tsgo's second chain fold and the KDoc recording it — a SEMANTIC parity change with NO ledger movement, not an extraction; **+40 at (P18.147)**, the `apply`-tuple KDoc and the JSDoc-typed-receiver refusal — a SEMANTIC parity change whose CODE delta is one deleted string tail plus a nine-line predicate, the rest being the measurement it records; **+163 at (P18.146)**, tsgo's two-branch JSDoc `@param` check with its `arguments` walk, array-type test and KDoc, net of a RETIRED corpus-unique walker (B558, 35 lines) and B557's duplicate implicit-`this` row — a SEMANTIC parity change, not an extraction; **UNCHANGED at (P18.145)**, a TRANSFORMER round — `Transformer.kt` 17,613 -> 17,592 (**-21**), a deleted TypeScript-6 disjunct net of its KDoc; **−119 at (P18.144)**, corpus-unique walker B437 and its two private tree-walkers DELETED (135 lines) net of the arity wiring and its KDoc — a REMOVAL, and the arc's first shrink since (P18.132); **UNCHANGED at (P18.143)**, a (LEGACY.0b) row whose whole fix is nine lines of `StableTypeOrdering.kt` — a SEMANTIC parity change, not an extraction; **UNCHANGED at (P18.142)**, a second consecutive (INV.2b) round whose whole change is in `-project` and its docs — `git diff --name-only` shows no `Checker.kt`, which the item requires; **UNCHANGED at (P18.141)**, an (INV.2b) round whose whole change is in `-project` and `TypeOracle.kt` — `git diff --name-only` shows no `Checker.kt`, which is the item's own constraint; **+48 at (P18.140)**, one shared union-ordering helper consulted by BOTH arms of a display pair, plus the B219 walker's two sort keys — a SEMANTIC parity change, not an extraction; **+22 at (P18.139)**, one message change and its KDoc; the separator collapse lives in `CompilerOptions.kt` — a SEMANTIC parity change, not an extraction; **+478 at (P18.138)**, the JS object-literal host, `Object.defineProperty` membership and the access funnel arm with their KDoc — a SEMANTIC parity change, not an extraction; **+251 at (P18.137)**, the `const`-bound expando host and the route-(B) predicate with their KDoc — a SEMANTIC parity change, not an extraction; **+278 at (P18.136)**, the expando MEMBER model — a collector widened from a name set to positions plus right-hand sides, the attachment, and their KDoc — a SEMANTIC parity change, not an extraction; **+18 at (P18.135)**, one predicate call replacing an inline shape test plus its KDoc, net of a SHRINKING `getPropertyElaborationChain` (6,271 -> 6,260 bytecodes) — a SEMANTIC parity change; **+124 at (P18.134)**, one trust-gate relaxation plus two helpers and their KDoc — a SEMANTIC parity change, not an extraction; unchanged at (P18.133), an options-path round whose `Checker.class` is BYTECODE-IDENTICAL to (P18.132)'s landed binary — only `TypeScriptCompiler.kt` moved, 6,773 -> 6,831; **-8 at (P18.132)**, eight `&& options.baseUrl == null` FP conjuncts and two prose comments deleted with `baseUrl`'s behaviour — a REMOVAL, and the arc's first shrink since (P18.113); **+189 at (P18.131)**, the JavaScript class expando MEMBER model, the class static side and a five-line constructor-context display, net of a RETIRED 75-line tsc-6 walker — a SEMANTIC parity change, not an extraction; **+105 at (P18.130)**, the JavaScript expando firewall and the two funnel guards — a SEMANTIC parity change, not an extraction; unchanged at (P18.129), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.128)'s landed binary; **+242 at (P18.128)**, three class-parity mechanisms — a SEMANTIC change, not an extraction; unchanged at (P18.127) and (P18.126), two KIR-only rounds whose `Checker.class` is BYTE-IDENTICAL to (P18.125)'s landed binary; a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.125)'s landed binary; **+234 at (P18.125)**, one ADDITIVE star-export enumeration capability with exactly one non-test caller — a SEMANTIC parity change, not an extraction; unchanged at (P18.124), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.123)'s landed binary; **+38 at (P18.123)**, the JS-expando declaration rule net of a deleted per-tag aggregation loop — a SEMANTIC parity change; unchanged at (P18.122), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.121)'s landed binary; **+58 at (P18.121)**, net of a 61-line tsc-6 pass DELETED against ~119 added — a SEMANTIC parity change; **+31 at (P18.120)**, three duplicate-identifier mechanisms plus a computed-name span arm — a SEMANTIC parity change; **+97 at (P18.119)**, the `for`-header and `for…in` binding recorders plus the shared type half — a SEMANTIC parity change, not an extraction; unchanged at (P18.118), a KIR-only round whose four checker classes are byte-identical to the previous binary; **+43 at (P18.117)**, the index-signature read; **+61 at (P18.116)**, one related-span helper replacing three call sites; unchanged at (P18.115), whose two mechanisms live in `TypeScriptCompiler.kt`, 6,558 → 6,718; **+280 at (P18.114)**, the JS CommonJS `exports` model — two walkers and five helpers in, B438d out; +27 at (P18.113), all but two lines KDoc; **−118 at (P18.112)**, the tslib ES5 helper arms; **−183 at (P18.111)**, seven target-gate families; **−285 at (P18.110)**, the TS1250/TS18028 families; **−243 at (P18.109)**, the `downlevelIteration` block; unchanged at (P18.108), which deleted `outFile`'s six arms from `TypeScriptCompiler.kt`, 6,566 → 6,553; +3 at (P18.107), the amd/umd/system fold — deleted behaviour, added KDoc; **−89 at (P18.106)**, five module-resolution derivation copies → one; **−50 at (P18.105)**, the interop arms; unchanged at (P18.104), which lifted the tsconfig option-position scan into `CompilerOptions.kt` for both paths; **+8 at (P18.103)**: code −6, KDoc +14, the `alwaysStrict: false` arms; unchanged at (P18.102), which deleted **249** lines of dead System-module code from `Transformer.kt`, 17,862 → 17,613 — the first (LEGACY.1) removal; **+24 at (P18.101)** — ~150 deleted (TS2346 gate + helpers, a one-fixture TS2300 walker), ~175 for tsgo's TS2309 rule; **+93 at (P18.100)**, anchor rules; **+333 at (P18.99)**, a SEMANTIC change — two new JSDoc walkers; **+79 at (P18.98)**, a SEMANTIC parity change — four tsgo mechanisms; **+81 at (P18.94)** — union DISPLAY sites routed through the (P18.85) comparator; **-28 at (P18.93)**, a SEMANTIC parity change (duplicate members reported at every declaration) that DELETES three tsc-6 narrowings and the TS6200 amalgamation; **+78 at (P18.92)**, a SEMANTIC parity change (TS2683/TS7009 in JS files) that also RETIRES tsc-6 walker B424; **+26 at (P18.91)**, a SEMANTIC parity change (TS2303 at every alias declaration); **+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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

**(P18.190) — (CHK.158): A TYPE GUARD REACHED THROUGH A VALUE NARROWS — THE PREDICATE IS READ FROM THE CALLEE'S SIGNATURE; `rxjs` 3 -> 2, 20,650 / 0 / 44 (2026-09-24).**
`const isArr = Array.isArray`, a destructured `isArray`, a guard in an object property, a guard-typed annotation and
an overloaded guard (wrong even when called directly) now narrow, with tsgo's `getEffectsSignature` rule. Four matrix
groups exact; 19 pins, eight arms RED. Screen 0; grid 8x0; huge_methods 0; cost_gate rebaselined (`typeNode.bypassed`
−2.03%, a decrease). **`rxjs` 3 -> 2** — both remaining rows are (CHK.159) inference. The builder's blind control led to
**(CHK.167), promoted to the top: a union source against an object/array target is SILENT at declaration and
argument positions** (`number[] | string` -> `number[]`, `K | number` -> `K`) — the largest false-negative class found
this session.

**(P18.189) — (CHK.157): THE ELSE BRANCH OF AN `if` NARROWS AT THE ASSIGNMENT AND RETURN READERS; `rxjs` 4 -> 3, 12 -> 45 AGREEING ROWS, 20,631 / 0 / 44 (2026-09-23).**
The census named a legacy arm; the emitter for function-declaration bodies is the SPINE, so the fix is in the spine
and both legacy walks: each `||` disjunct negated in order (tsgo's false branch), negated type-guard calls,
narrow-to-nullish, and `else if` compounding. Two pre-existing bugs fell out: `typeof x !== "…"` narrowed nothing,
and the spine registered an If's narrowing before its own frame. 22-cell matrix: agree 12 -> 45, ours-only 40 -> 5,
no row added. 20 pins, nine of ten arms RED (the `never` refusal recorded as unreachable). Screen 0; cost_gate PASS;
spine audit clean; grid 8x0; huge_methods 0. **`rxjs` 4 -> 3.**

**(P18.188) — (CHK.156): EQUALITY AND `switch` NARROWING SPLIT `boolean` INTO `true | false`; `rxjs` 5 -> 4, 20,611 / 0 / 44 (2026-09-23).**
`on === true` / `=== false` / `case true:` never removed a half of `boolean` — in the union branch, the bare branch,
the switch default, or the call-argument reader (where an exhausted `boolean` was an ours-only false positive). One
helper pair now subtracts a half without changing how `boolean` displays. 18-cell matrix: every equality/switch cell
matches tsgo; 14 pins, seven arms RED. Screen 0 (pending rows byte-identical); cost_gate identical; grid 8x0;
huge_methods 0 — `checkArgumentsAgainstSignatureCore` at 7,629/8,000. **`rxjs` 5 -> 4.** Truthiness and the optional
`boolean` display filed as (CHK.164). `Checker.kt` crossed 200,000 lines.

**(P18.187) — (CHK.155): A CAPTURED READ OF AN OUTER VARIABLE FOLLOWS tsgo's `isOuterVariable && !isNeverInitialized`; `rxjs` 6 -> 5, 20,597 / 0 / 44 (2026-09-23).**
An expression-bodied arrow's read subtracted only names assigned inside that same arrow, so an assignment in a SIBLING
closure was invisible and TS2454 fired in every unchecked body and at file level. The walks now install tsgo's
function-wide definitely-assigned set. 26-cell matrix 16 -> 24 agree, all must-still-report controls held; 11 pins,
four arms RED. Screen 0; cost_gate identical; grid 8x0; huge_methods 0. **`rxjs` 6 -> 5.** The parallel (CHK.160) census
found the `instantiateType` function-shape skip was never a measured guard and specified a return-slot first step
(+0 predicted on all profiles, 11 matrix cells fixed, one false positive removed).

**(P18.186) — (CHK.154)(b): A CLASS WITH ITS OWN CONSTRUCTOR HAS ONLY ITS OWN CONSTRUCT SIGNATURES; THE FIX EXPOSED AN rxjs OOM AND TWO RELATION DEFECTS, ALL CLOSED, 20,586 / 0 / 44 (2026-09-23).**
`new Sub("x")` against `constructor(o?: number)` was ACCEPTED because the base constructor rode along. Fixing that at
the source made `SafeSubscriber<T>` stop relating to `Subscriber<T>` (construct signatures were only skipped for
`Type.Interface` targets, and a generic instance is a `Type.Reference`) and an override check then exhausted a 6 GB
heap elaborating it; skipping them for class references exposed a baseline passing by accident, closed by porting
tsgo's private-vs-public property rule; inaccessible constructors now return as tsgo's error call does. 26-cell matrix:
every added row a tsgo row, every removed row ours-only. 13 pins, seven arms RED. Screen 0; grid 8x0 (a real gate);
KIR 313/0; rxjs 6, marked 0. Residues filed as (CHK.163).

