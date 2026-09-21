# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **198,069** lines (**+190 at (P18.165)**, the general element-access WRITE reader, its three measured refusals and the one-row-per-site dedupe, with their KDoc — net of the B85.1d tail EXTRACTED rather than copied, so the arm is a fallback behind it; a SEMANTIC parity change closing a false-NEGATIVE class, not an extraction; **+83 at (P18.164)**, the shared `signatureDeclaredArity` and the union reader's KDoc, net of the 11-line inline `when` the extraction REMOVED — a SEMANTIC parity change that also retires a duplicate, not an extraction; **+180 at (P18.163)**, the module-object missing-member gate and its display with their KDoc, net of the INERT `syntheticModuleAmbientCarrier` map removed by the round's own ablation — a SEMANTIC parity change with a typed-interop payoff, not an extraction; **+11 at (P18.160)**, the widened (CHK.42) pre-pass and its KDoc — a SEMANTIC parity change removing a FALSE-POSITIVE class, not an extraction; **+52 at (P18.159)**, the merged-namespace statics attachment and its KDoc — a SEMANTIC parity change with a typed-interop payoff, not an extraction; **UNCHANGED at (P18.158)**, whose whole change is 29 lines of `NameResolver.kt` — a SEMANTIC parity change in a COLLABORATOR, which is the shape (INV.0) wants; **+119 at (P18.157)**, the external-module value type, the shadow guard and their KDoc — a SEMANTIC parity change with a MISSION payoff, not an extraction; the collaborator half is `NameResolver.kt` +29; **UNCHANGED at (P18.156)**, whose whole change is 26 lines of `NameResolver.kt` — a SEMANTIC parity change in a COLLABORATOR, which is the shape (INV.0) wants; **+92 at (P18.155)**, the property-access TS7009 arm, its refusal helper and their KDoc — a SEMANTIC parity change, not an extraction; **+210 at (P18.154)**, the literal-set intersection reduction, the value-keyed literal dedupe and their KDoc, plus B218's sort and replacement — a SEMANTIC parity change, not an extraction; **UNCHANGED at (P18.153)**, a recon round that touched no compiled code; **+22 at (P18.152)**, the default-import-clause arm and its KDoc — a SEMANTIC parity change, not an extraction; **UNCHANGED at (P18.151)**, a test-side round whose `Checker.class` is BYTE-IDENTICAL to (P18.150)'s landed binary; **+31 at (P18.150)**, two chain-depth drills and their KDoc — a SEMANTIC parity change, not an extraction; **+188 at (P18.149)**, TS2430 routed through the general elaboration chain plus the structural arm for the two member shapes the name-based walker cannot NAME, with their KDoc — a SEMANTIC parity change, not an extraction; **+24 at (P18.148)**, tsgo's second chain fold and the KDoc recording it — a SEMANTIC parity change with NO ledger movement, not an extraction; **+40 at (P18.147)**, the `apply`-tuple KDoc and the JSDoc-typed-receiver refusal — a SEMANTIC parity change whose CODE delta is one deleted string tail plus a nine-line predicate, the rest being the measurement it records; **+163 at (P18.146)**, tsgo's two-branch JSDoc `@param` check with its `arguments` walk, array-type test and KDoc, net of a RETIRED corpus-unique walker (B558, 35 lines) and B557's duplicate implicit-`this` row — a SEMANTIC parity change, not an extraction; **UNCHANGED at (P18.145)**, a TRANSFORMER round — `Transformer.kt` 17,613 -> 17,592 (**-21**), a deleted TypeScript-6 disjunct net of its KDoc; **−119 at (P18.144)**, corpus-unique walker B437 and its two private tree-walkers DELETED (135 lines) net of the arity wiring and its KDoc — a REMOVAL, and the arc's first shrink since (P18.132); **UNCHANGED at (P18.143)**, a (LEGACY.0b) row whose whole fix is nine lines of `StableTypeOrdering.kt` — a SEMANTIC parity change, not an extraction; **UNCHANGED at (P18.142)**, a second consecutive (INV.2b) round whose whole change is in `-project` and its docs — `git diff --name-only` shows no `Checker.kt`, which the item requires; **UNCHANGED at (P18.141)**, an (INV.2b) round whose whole change is in `-project` and `TypeOracle.kt` — `git diff --name-only` shows no `Checker.kt`, which is the item's own constraint; **+48 at (P18.140)**, one shared union-ordering helper consulted by BOTH arms of a display pair, plus the B219 walker's two sort keys — a SEMANTIC parity change, not an extraction; **+22 at (P18.139)**, one message change and its KDoc; the separator collapse lives in `CompilerOptions.kt` — a SEMANTIC parity change, not an extraction; **+478 at (P18.138)**, the JS object-literal host, `Object.defineProperty` membership and the access funnel arm with their KDoc — a SEMANTIC parity change, not an extraction; **+251 at (P18.137)**, the `const`-bound expando host and the route-(B) predicate with their KDoc — a SEMANTIC parity change, not an extraction; **+278 at (P18.136)**, the expando MEMBER model — a collector widened from a name set to positions plus right-hand sides, the attachment, and their KDoc — a SEMANTIC parity change, not an extraction; **+18 at (P18.135)**, one predicate call replacing an inline shape test plus its KDoc, net of a SHRINKING `getPropertyElaborationChain` (6,271 -> 6,260 bytecodes) — a SEMANTIC parity change; **+124 at (P18.134)**, one trust-gate relaxation plus two helpers and their KDoc — a SEMANTIC parity change, not an extraction; unchanged at (P18.133), an options-path round whose `Checker.class` is BYTECODE-IDENTICAL to (P18.132)'s landed binary — only `TypeScriptCompiler.kt` moved, 6,773 -> 6,831; **-8 at (P18.132)**, eight `&& options.baseUrl == null` FP conjuncts and two prose comments deleted with `baseUrl`'s behaviour — a REMOVAL, and the arc's first shrink since (P18.113); **+189 at (P18.131)**, the JavaScript class expando MEMBER model, the class static side and a five-line constructor-context display, net of a RETIRED 75-line tsc-6 walker — a SEMANTIC parity change, not an extraction; **+105 at (P18.130)**, the JavaScript expando firewall and the two funnel guards — a SEMANTIC parity change, not an extraction; unchanged at (P18.129), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.128)'s landed binary; **+242 at (P18.128)**, three class-parity mechanisms — a SEMANTIC change, not an extraction; unchanged at (P18.127) and (P18.126), two KIR-only rounds whose `Checker.class` is BYTE-IDENTICAL to (P18.125)'s landed binary; a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.125)'s landed binary; **+234 at (P18.125)**, one ADDITIVE star-export enumeration capability with exactly one non-test caller — a SEMANTIC parity change, not an extraction; unchanged at (P18.124), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.123)'s landed binary; **+38 at (P18.123)**, the JS-expando declaration rule net of a deleted per-tag aggregation loop — a SEMANTIC parity change; unchanged at (P18.122), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.121)'s landed binary; **+58 at (P18.121)**, net of a 61-line tsc-6 pass DELETED against ~119 added — a SEMANTIC parity change; **+31 at (P18.120)**, three duplicate-identifier mechanisms plus a computed-name span arm — a SEMANTIC parity change; **+97 at (P18.119)**, the `for`-header and `for…in` binding recorders plus the shared type half — a SEMANTIC parity change, not an extraction; unchanged at (P18.118), a KIR-only round whose four checker classes are byte-identical to the previous binary; **+43 at (P18.117)**, the index-signature read; **+61 at (P18.116)**, one related-span helper replacing three call sites; unchanged at (P18.115), whose two mechanisms live in `TypeScriptCompiler.kt`, 6,558 → 6,718; **+280 at (P18.114)**, the JS CommonJS `exports` model — two walkers and five helpers in, B438d out; +27 at (P18.113), all but two lines KDoc; **−118 at (P18.112)**, the tslib ES5 helper arms; **−183 at (P18.111)**, seven target-gate families; **−285 at (P18.110)**, the TS1250/TS18028 families; **−243 at (P18.109)**, the `downlevelIteration` block; unchanged at (P18.108), which deleted `outFile`'s six arms from `TypeScriptCompiler.kt`, 6,566 → 6,553; +3 at (P18.107), the amd/umd/system fold — deleted behaviour, added KDoc; **−89 at (P18.106)**, five module-resolution derivation copies → one; **−50 at (P18.105)**, the interop arms; unchanged at (P18.104), which lifted the tsconfig option-position scan into `CompilerOptions.kt` for both paths; **+8 at (P18.103)**: code −6, KDoc +14, the `alwaysStrict: false` arms; unchanged at (P18.102), which deleted **249** lines of dead System-module code from `Transformer.kt`, 17,862 → 17,613 — the first (LEGACY.1) removal; **+24 at (P18.101)** — ~150 deleted (TS2346 gate + helpers, a one-fixture TS2300 walker), ~175 for tsgo's TS2309 rule; **+93 at (P18.100)**, anchor rules; **+333 at (P18.99)**, a SEMANTIC change — two new JSDoc walkers; **+79 at (P18.98)**, a SEMANTIC parity change — four tsgo mechanisms; **+81 at (P18.94)** — union DISPLAY sites routed through the (P18.85) comparator; **-28 at (P18.93)**, a SEMANTIC parity change (duplicate members reported at every declaration) that DELETES three tsc-6 narrowings and the TS6200 amalgamation; **+78 at (P18.92)**, a SEMANTIC parity change (TS2683/TS7009 in JS files) that also RETIRES tsc-6 walker B424; **+26 at (P18.91)**, a SEMANTIC parity change (TS2303 at every alias declaration); **+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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

**(P18.165) — (CHK.136): AN ASSIGNMENT THROUGH AN ELEMENT ACCESS IS TYPE-CHECKED AT LAST; 20,246 / 0 / 44 (2026-09-21).**
`bag[key] = "not a function"` was accepted in SILENCE where the identical mismatch through a PROPERTY target
reported TS2322 — a false-NEGATIVE class that surfaces as false POSITIVES wherever real code guards such a write
with `// @ts-expect-error`. **THE ITEM'S SIZING WAS CORRECTED BY A PROBE BEFORE ANY CODE WAS WRITTEN**: read-probing
every target shape split the defect in two, and the half `marked` needs — an index whose TYPE is a union of literals,
which resolves to `any` — is a READ-path gap promoted to (CHK.139) with its prize measured (marked 10 -> 7). So
**`marked` stays at 10 and the round said so in advance.** Adjudicated against tsgo 7.0.2 first (63 fixtures): the
anchor is the LHS at full width, a compound assignment is not this check. **THE GRID WAS A REAL GATE AND A CENSUS
SAID SO BEFORE THE ARM EXISTED** (223-347 element-access assignments per profile); it caught one FP on all eight —
an ARRAY LITERAL against a tuple slot, round 459's recorded finding one reader over. The screen then caught two more —
a write type is the SETTER's parameter, and a DOUBLE EMISSION where a legacy walker had already decided the site.
**THE ABLATION THEN OVERTURNED HALF OF THAT, AND IT IS THE ROUND'S MOST USEFUL RESULT**: a UNION-receiver refusal
read 0 RED on the pins and 0 of 8,725 on the screen not because the pins were blind but because it was
UNNECESSARY (`Two.prop3` is a get/set pair, so the ACCESSOR guard reaches every row in that fixture) — and
measured one step further it was LOSSY, dropping two rows tsgo reports on a union receiver with no accessor at
all. It was REMOVED and those two rows are now pins. A guard no arm can discriminate is as often unnecessary as
it is unpinned; the only way to tell is to ask what it COSTS. **The mirror case landed in the same ablation**: the ACCESSOR guard read 0 RED through TWO pin attempts while the screen read 1 mismatch — there the guard was load-bearing and the PIN was blind, and only building the guard-off binary by hand and reading WHICH rows it loses produced a discriminator (a UNION receiver plus a value that fails the GETTER union while the SETTERS accept it). Final arm 1 pin RED, 1 screen. **The four display/anchor divergences the new rows inherit are
PRE-EXISTING AT THE PROPERTY TARGET, proved on a twin fixture** — one shared (PARITY.1) family, fixed in both
readers or neither. Screen 0 of 8,725; grid 8x0; cost_gate PASS (max +0.21%, `output.errors` 46); huge_methods 0
over; warning-clean; cronstrue 1 -> 1.

**(P18.164) — (CHK.33): A DESTRUCTURING PARAMETER NO LONGER BREAKS ARITY; `marked` 18 -> 10, 20,232 / 0 / 44 (2026-09-21).**
`Signature.parameters` drops a binding-pattern parameter while `minArgumentCount` counts it, so a reader taking its MAXIMUM
from one and its MINIMUM from the other states an impossible range — `Expected 1-0 arguments, but got 1.` on legal code.
Round 446 fixed that at the property-access reader; (CHK.97)'s union reader reintroduced it verbatim, and the axis is the
UNION RECEIVER, which is why a fixture without one is vacuous and why it survived ~700 rounds. Round 446's recovery is now
the shared `Checker.signatureDeclaredArity`. **On a 10-case matrix adjudicated against tsgo FIRST, ours went from 10 rows
every one wrong to 6 rows with tsgo's own codes and messages.** TWO more FP classes of the same cause fixed with it: a REST
parameter whose own name is a binding pattern (read as ZERO-arity — a shape neither standing instrument can see), and a
member typed by a FUNCTION TYPE (an options-bag callback property). **THE CORPUS IS BLIND TO THE WHOLE FAMILY AND THE
ABLATION MEASURED IT**: all six arms read 0 of 8,725 screen subtests and the grid is 8x0, while the pins move 7/1/15/0/1/4 —
so `marked` plus the pins were the only gate, which is why the owner's 2026-09-21 directive makes the library probe the
alignment stop-condition. Arm a4 is a measured REDUNDANT barrier (recorded, not claimed); arm a3 reddens round 446's own
pins, the receipt that the extraction is faithful. The `require(min <= parameters.size)` the item asked for is UNAVAILABLE —
every binding-pattern signature violates it by construction — so the inversion is pinned as a MESSAGE invariant.
Screen 0 of 8,725; grid 8x0; cost_gate PASS (max +0.15%); huge_methods 0 over; cronstrue 1 -> 1. **SUCCESSOR SIZED**: an
OVERLOAD SET with a destructured overload picks the wrong overload and reports a confident TS2345, through overload
SELECTION rather than a TS2554 emitter; a census counts ~55 readers of the same split.

**(P18.163) — (CHK.73)(A)+(B): A MODULE OBJECT'S MISSING MEMBER IS REPORTED AND IT RENDERS AS `typeof import("…")`, 20,215 / 0 / 44 (2026-09-21).**
CLOSED the two residues (P18.159) named. Ours now answers **all SIX of tsgo's probe rows at tsgo's own codes and
spans**, where before it answered one; `import * as ns from "./m"; ns.nope` and the ambient form were BOTH silent,
and the same object rendered as a BARE NAME (`Type 'ns'` / `Type 'pkg'`). **THE LEDGER'S BLOCKER WAS FALSE IN BOTH
HALVES AND THE FIRST CORRECTION WAS ALSO WRONG**: no star-following enumeration is needed (REFUSING suffices, and
`ambientSurfaceIsEnumerableForImport` already existed — confirmed by ablation on a matched pair), but the proposed
clause in `cmamAllMissingTrustedMember` is a measured DEAD ARM, because a PRE-EXISTING walker answers the bare
`ExpressionStatement` form and the same access in a function body is silent. The real gate was a blanket
`SymbolFlags.Alias` skip in `cmamCheckIdentSymbolValueGates`. **FOUR ABLATION ARMS READ 0 RED AND THE FOUR WERE NOT
THE SAME THING**: three were BLIND PIN SETS (each given a constructed shape and a pin — one had been gated by a
corpus baseline ALONE), and one, `syntheticModuleAmbientCarrier`, was genuinely INERT and is REMOVED, because the
only shape reaching it is one tsgo calls TS2306. **THE (PARITY.1) RECEIPT IS NOW A MEASUREMENT**: the display arm
reddens 2 pins and 0 of 8,725 screen subtests, and the dedupe arm is its exact mirror (0 pins, 2 screen) — which is
why (B) could not be a standalone round. Screen 0 of 8,725; grid 8x0 with emit byte-identical; cost_gate PASS (max
+0.15%); huge_methods 0 over; cronstrue 1 -> 1, marked 18 -> 18. Stated divergence: the display uses the BASENAME
where tsgo uses the full path — invisible to every gate here, real on a multi-directory project.

**(P18.162) — (DOC.2) TRANCHE 2 CLOSES THE ITEM: `CLAUDE.md` 663,894 -> 419,477 B (-36.8%) (2026-09-21).**
DOCS-ONLY; suite/cost/JIT gates INAPPLICABLE rather than skipped. `### Measured dead-ends` 97,878 -> 42,338 B,
`### Test assertion gotchas` 39,948 -> 30,949 B; archive 1,040,138 -> 1,146,286 B, a PURE APPEND. Buckets:
dead-ends 23 KEEP / 65 DISTIL / 25 ARCHIVE; assertions 27 KEEP / 25 DISTIL / **0 ARCHIVE**. **BOTH SUB-TARGETS
MISSED DELIBERATELY, ON ARITHMETIC**: a dead-end's VALUE IS ITS NUMBER (archive the figure and the next agent
re-runs the experiment), and 23 do-not-distil KEEPs are already 13,864 B; the assertions section is
PRESCRIPTIVE — read while WRITING code — so it archived nothing. **THE DEAD-ENDS TITLE WAS ASPIRATIONAL**:
probed against the pre-commit archive, 4 of 5 sampled entries were ABSENT from the archive the title tells you
to read — the file asserted a property of itself that did not hold, and it is now true. Conservation
re-measured independently as the DISJUNCTION: **165 = 115 archived + 50 resident, 0 in neither, 0 in both**;
isolation byte-identical; 186 grep keys, 0 unresolved. **(DOC.2) is CLOSED as DECIDED-DOWN, not exhausted** —
the residue is KEEP-class by the file's own residency rule, so any further cut changes the RULE, which is an
owner question.

**(P18.161) — (DOC.2) TRANCHE 1: `CLAUDE.md` 663,894 -> 484,016 B (-26.8%), NOTHING DELETED (2026-09-21).**
A DOCS-ONLY round; no compiled code, so the suite/cost/JIT gates are INAPPLICABLE rather than skipped.
`### Checker walker gotchas` **240,298 -> 59,928 B**, resident entries **304 -> 122** (KEEP 29 / DISTIL 92 /
ARCHIVE 183); `docs/history/CLAUDE-GOTCHAS-ARCHIVE.md` 822,487 -> 1,040,138 B as a PURE APPEND. The file is
loaded into every agent session, so ~165k tokens of every context window went to it before any work started —
this is the one lever whose payoff compounds across all 83 open items. Licensed by the file's OWN residency
rule (*"per-test/per-walker detail goes straight to the archive"*): the section was post-(DOC.1) REGROWTH from
the (CHK.\*) arc. **CONSERVATION IS A DISJUNCTION, AND THE LITERAL CHECK ASKS THE WRONG QUESTION** — "every
before-entry is in the after-archive" fails by construction for the 29 byte-identical KEEPs and would require
duplicating 23 KB; the property is *archived OR resident*, measured independently at **304 = 275 + 29, 0 in
neither, 0 in both**, with isolation byte-identical outside the section and 93 grep keys all resolving. One
gap closed by judgement: the Kotlin-externals rendering rules are COMPILE-GATE facts for a live mission leg
and got a resident stub back. Next tranches: `### Measured dead-ends` (97,663 B) + `### Test assertion
gotchas` (39,869 B), ~137 KB at the same risk profile.

