# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **202,060** lines (**+49 at (P18.216)**, a binding-identity gate on flow-assignment narrowing — the scope logic is `FlowShadowScope.kt` (new, 141 lines); a SEMANTIC parity change removing a FALSE-POSITIVE class; **+19 at (P18.215)**, a lexical consult past a walk-table `any` for a shadowing nested function — a SEMANTIC parity change closing a false-NEGATIVE class; **+73 at (P18.214)**, block scopes for the argument walker and a lexical consult in the callee resolver — a SEMANTIC parity change removing a FALSE-POSITIVE class; **+207 at (P18.213)**, block scopes for the declaration / assignment walker on the spine and its legacy twin — a SEMANTIC parity change removing a FALSE-POSITIVE class; **+117 at (P18.212)**, block scopes for the arithmetic and member-access walkers — the scope-name source is `LocalShadowGuard.kt`; a SEMANTIC parity change removing a FALSE-POSITIVE class; **+57 at (P18.211)**, the identifier-receiver TS1804x arm and its shared code chooser — the binding guard is in `LocalShadowGuard.kt`; a SEMANTIC parity change closing the checker's most visible FALSE-NEGATIVE class; **+92 at (P18.210)**, three overload-selection helpers — a SEMANTIC parity change: a wrong overload (and an `any` result) became the right one; **+101 at (P18.209)**, equality with a flow-narrowed value — a SEMANTIC parity change removing a FALSE-POSITIVE class; **-97 at (P18.208)**, the old optional-chain range walker deleted, the new guard is `OptionalChainGuard.kt` (157 lines); a SEMANTIC parity change removing a FALSE-POSITIVE class and closing four false NEGATIVES the old walker hid; **+97 at (P18.207)**, tsgo's optional-chain containment rule for equality and switch narrowing — a SEMANTIC parity change removing a FALSE-POSITIVE class; **+137 at (P18.206)**, the `??=`/`||=` branch and the nullish arrow-predicate shape — a SEMANTIC parity change removing a FALSE-POSITIVE class; **+30 at (P18.205)**, the outer-ladder split and the local-shadow guard's two call sites — the guard itself is `LocalShadowGuard.kt` (new, 239 lines); a SEMANTIC parity change removing a FALSE-POSITIVE class; **+135 at (P18.204)**, four non-nullish proofs for a flow assignment's RHS (body locals, a lexical nested callee, an element read, arrow / function-expression callees) — a SEMANTIC parity change removing a FALSE-POSITIVE class under the coming TS18048 arm; **−413 at (P18.203)**, the `emitDeclarationOnly` checker whitelist deleted — a SEMANTIC parity change closing a false-NEGATIVE class; **+32 at (P18.202)**, the alias-guard relation and the TS2344 constraint chain — the
relation rule itself is `Relater.kt` 1,758 -> 1,809; a SEMANTIC parity change removing a FALSE-POSITIVE class;
**+77 at (P18.201)**, an enclosing-scope walk and a shared scope builder for
constructor / setter / nested frames — a SEMANTIC parity change closing a silent-`any` class; **+37 at (P18.200)**, an owning-declaration class lookup and a per-file base lookup
at the argument reader — a SEMANTIC parity change closing a false-NEGATIVE class; **+32 at (P18.199)**, a type-facts nullish test and two early returns — a SEMANTIC
parity change removing a FALSE-POSITIVE class; **+152 at (P18.198)**, a narrowed-receiver re-read, a union-parameter admission and
its chain — net of a verbatim split taking `checkArgumentsAgainstSignatureCore` 7,629 -> 4,297 bytecodes; **+26 at
(P18.197)**, instantiated member reads in the intersection relation and
its elaboration — a SEMANTIC parity change removing a FALSE-POSITIVE class; **+42 at (P18.196)**, the `boolean` truthiness split and the join-time rejoin —
a SEMANTIC parity change; **+27 at (P18.195)**, the enum truthiness arms and the proper-subset split — the
value-aware classifier lives in `EnumSemantics.kt` 1,138 -> 1,234; a SEMANTIC parity change closing a false-NEGATIVE
class; **+99 at (P18.194)**, the concise-body return check routed through the block
path (net of the deleted spine check and 13 retired hardcoded anchors) — a SEMANTIC parity change closing a
false-NEGATIVE class; **+144 at (P18.193)**, the inference-outcome refactor and the contextual-return
fallback with its safeguards — a SEMANTIC parity change taking `rxjs` to 0 ours-only rows; **+157 at (P18.192)**, a union-source relation lift at three readers plus three
narrowing fixes it depended on — a SEMANTIC parity change closing a false-NEGATIVE class; **+338 at (P18.191)**, an argument-inference leg for a call's result type with
its safeguards, array element pairing, a `NonNullable` union reduction and an alias-display guard — a SEMANTIC
parity change, `rxjs` 2 -> 1; **+94 at (P18.190)**, the callee-signature predicate fallback and its
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

**(P18.216) — (CHK.173) ROUND B1: AN INNER SAME-NAMED `const` NO LONGER RE-NARROWS THE OUTER VARIABLE — ROUND A's REMAINING FALSE-POSITIVE CLASS IS CLOSED; +0 EVERYWHERE, 21,309 / 0 / 44 (2026-09-28).**
`if (!s) return; { const s = g(); } return s.length` reported "'s' is possibly 'null'" because narrowing matched an
assignment by name. A binding-identity gate now ignores assignments to a different, block-scoped binding. Also:
`x === void 0`, `(m = re.exec(s)) != null` and `s &&= …` narrow as tsgo does. 16 shipped false positives removed, 4 true
rows added; 72 pins, 12 of 12 arms RED; warm A/B inside the band. Screen 0; grid 8x0; libs 0. Next: B2.

**(P18.215) — (CHK.173) G5 RESIDUE c6: A NESTED FUNCTION NAMED LIKE AN OUTER BINDING IS TYPED AS ITSELF; +0 EVERYWHERE, 21,237 / 0 / 44 (2026-09-28).**
`function outer() { function g(n: number) {} g("x") }` beside a file-level `g` read `g` as `any`, so its arguments were
never checked. A lexical consult now runs past that `any`; the 2,160-cell matrix gains 15 true rows, 0 false. The
Round B census also found Round A still ships a false-positive class — a same-named `const` in an inner block
re-narrows the outer variable (the matrix could not see it) — and a general arity false positive (TS2345 beside
TS2554, filed as (CHK.175)). 14 pins, 4 of 4 arms RED. Screen 0; grid 8x0; libs 0. Next: Round B1 (the flow-shadow fix).

**(P18.214) — (CHK.173) G5 S2 SLICES 3 + 4: CALL ARGUMENTS AND CALLED NAMES SEE A BLOCK-SCOPED SHADOW; +0 EVERYWHERE, 21,223 / 0 / 44 (2026-09-28).**
A block `let`/`const` shadowing a parameter was passed as the parameter (false TS2345), and a block `function f`
shadowing an outer `const f: string` was called as the outer (false TS2349). The argument walker is now scoped and the
callee resolver asks the lexical scope, as the value read already did. 2,160-cell matrix: 62 false rows removed, 165
true added, 0 false added. 22 pins, 7 of 7 arms RED. Screen 0; grid 8x0; libs 0; cost_gate PASS. Next: Round B.

**(P18.213) — (CHK.173) G5 S2 SLICE 2: DECLARATIONS, ASSIGNMENTS AND RETURNS SEE A BLOCK-SCOPED SHADOW (AND A CATCH VARIABLE AS `unknown`) AS tsgo DOES; +0 EVERYWHERE, 21,201 / 0 / 44 (2026-09-28).**
`let x = 1` in a block shadowing an outer `x: string` made `x = 2` a false TS2322, and the inner type leaked past the
block. The declaration/assignment walker and its legacy twin now scope blocks, catch, case blocks and for headers. On
the 2,160-cell shadow matrix: 172 false rows removed, 104 true rows added, 0 false rows added. 48 pins, 11 of 11 arms
RED. Screen 0; grid 8x0; libs 0; `globals` probes +2.3% (file-level misses, re-baselined). Next: slices 3 + 4.

**(P18.212) — (CHK.173) G5 S2 SLICE 1: A BLOCK-SCOPED `let`/`const` SHADOWING AN OUTER NAME IS TYPED AS ITSELF IN ARITHMETIC AND MEMBER ACCESS; ALSO FIXES 24 FALSE TS18048 ROWS ROUND A SHIPPED; +0 EVERYWHERE, 21,153 / 0 / 44 (2026-09-28).**
`function f(zed: RegExp | undefined) { { const zed = 1; zed.toFixed() } }` read the inner `zed` as the outer parameter
— and after Round A that became a false "possibly 'undefined'" (found by the 2,160-cell shadow matrix; no profile or
library has the shape). Both walkers, including a legacy walker the census missed, now scope blocks, catch, case blocks
and for headers. 24 FPs removed, 12 true rows added, 0 FPs added on the matrix. 35 pins, 8 of 8 arms RED. Screen 0;
grid 8x0; libs 0; cost_gate PASS. Next: slice 2 (declarations / assignments).
