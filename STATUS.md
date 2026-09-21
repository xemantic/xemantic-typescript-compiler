# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **197,434** lines (**+92 at (P18.155)**, the property-access TS7009 arm, its refusal helper and their KDoc — a SEMANTIC parity change, not an extraction; **+210 at (P18.154)**, the literal-set intersection reduction, the value-keyed literal dedupe and their KDoc, plus B218's sort and replacement — a SEMANTIC parity change, not an extraction; **UNCHANGED at (P18.153)**, a recon round that touched no compiled code; **+22 at (P18.152)**, the default-import-clause arm and its KDoc — a SEMANTIC parity change, not an extraction; **UNCHANGED at (P18.151)**, a test-side round whose `Checker.class` is BYTE-IDENTICAL to (P18.150)'s landed binary; **+31 at (P18.150)**, two chain-depth drills and their KDoc — a SEMANTIC parity change, not an extraction; **+188 at (P18.149)**, TS2430 routed through the general elaboration chain plus the structural arm for the two member shapes the name-based walker cannot NAME, with their KDoc — a SEMANTIC parity change, not an extraction; **+24 at (P18.148)**, tsgo's second chain fold and the KDoc recording it — a SEMANTIC parity change with NO ledger movement, not an extraction; **+40 at (P18.147)**, the `apply`-tuple KDoc and the JSDoc-typed-receiver refusal — a SEMANTIC parity change whose CODE delta is one deleted string tail plus a nine-line predicate, the rest being the measurement it records; **+163 at (P18.146)**, tsgo's two-branch JSDoc `@param` check with its `arguments` walk, array-type test and KDoc, net of a RETIRED corpus-unique walker (B558, 35 lines) and B557's duplicate implicit-`this` row — a SEMANTIC parity change, not an extraction; **UNCHANGED at (P18.145)**, a TRANSFORMER round — `Transformer.kt` 17,613 -> 17,592 (**-21**), a deleted TypeScript-6 disjunct net of its KDoc; **−119 at (P18.144)**, corpus-unique walker B437 and its two private tree-walkers DELETED (135 lines) net of the arity wiring and its KDoc — a REMOVAL, and the arc's first shrink since (P18.132); **UNCHANGED at (P18.143)**, a (LEGACY.0b) row whose whole fix is nine lines of `StableTypeOrdering.kt` — a SEMANTIC parity change, not an extraction; **UNCHANGED at (P18.142)**, a second consecutive (INV.2b) round whose whole change is in `-project` and its docs — `git diff --name-only` shows no `Checker.kt`, which the item requires; **UNCHANGED at (P18.141)**, an (INV.2b) round whose whole change is in `-project` and `TypeOracle.kt` — `git diff --name-only` shows no `Checker.kt`, which is the item's own constraint; **+48 at (P18.140)**, one shared union-ordering helper consulted by BOTH arms of a display pair, plus the B219 walker's two sort keys — a SEMANTIC parity change, not an extraction; **+22 at (P18.139)**, one message change and its KDoc; the separator collapse lives in `CompilerOptions.kt` — a SEMANTIC parity change, not an extraction; **+478 at (P18.138)**, the JS object-literal host, `Object.defineProperty` membership and the access funnel arm with their KDoc — a SEMANTIC parity change, not an extraction; **+251 at (P18.137)**, the `const`-bound expando host and the route-(B) predicate with their KDoc — a SEMANTIC parity change, not an extraction; **+278 at (P18.136)**, the expando MEMBER model — a collector widened from a name set to positions plus right-hand sides, the attachment, and their KDoc — a SEMANTIC parity change, not an extraction; **+18 at (P18.135)**, one predicate call replacing an inline shape test plus its KDoc, net of a SHRINKING `getPropertyElaborationChain` (6,271 -> 6,260 bytecodes) — a SEMANTIC parity change; **+124 at (P18.134)**, one trust-gate relaxation plus two helpers and their KDoc — a SEMANTIC parity change, not an extraction; unchanged at (P18.133), an options-path round whose `Checker.class` is BYTECODE-IDENTICAL to (P18.132)'s landed binary — only `TypeScriptCompiler.kt` moved, 6,773 -> 6,831; **-8 at (P18.132)**, eight `&& options.baseUrl == null` FP conjuncts and two prose comments deleted with `baseUrl`'s behaviour — a REMOVAL, and the arc's first shrink since (P18.113); **+189 at (P18.131)**, the JavaScript class expando MEMBER model, the class static side and a five-line constructor-context display, net of a RETIRED 75-line tsc-6 walker — a SEMANTIC parity change, not an extraction; **+105 at (P18.130)**, the JavaScript expando firewall and the two funnel guards — a SEMANTIC parity change, not an extraction; unchanged at (P18.129), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.128)'s landed binary; **+242 at (P18.128)**, three class-parity mechanisms — a SEMANTIC change, not an extraction; unchanged at (P18.127) and (P18.126), two KIR-only rounds whose `Checker.class` is BYTE-IDENTICAL to (P18.125)'s landed binary; a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.125)'s landed binary; **+234 at (P18.125)**, one ADDITIVE star-export enumeration capability with exactly one non-test caller — a SEMANTIC parity change, not an extraction; unchanged at (P18.124), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.123)'s landed binary; **+38 at (P18.123)**, the JS-expando declaration rule net of a deleted per-tag aggregation loop — a SEMANTIC parity change; unchanged at (P18.122), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.121)'s landed binary; **+58 at (P18.121)**, net of a 61-line tsc-6 pass DELETED against ~119 added — a SEMANTIC parity change; **+31 at (P18.120)**, three duplicate-identifier mechanisms plus a computed-name span arm — a SEMANTIC parity change; **+97 at (P18.119)**, the `for`-header and `for…in` binding recorders plus the shared type half — a SEMANTIC parity change, not an extraction; unchanged at (P18.118), a KIR-only round whose four checker classes are byte-identical to the previous binary; **+43 at (P18.117)**, the index-signature read; **+61 at (P18.116)**, one related-span helper replacing three call sites; unchanged at (P18.115), whose two mechanisms live in `TypeScriptCompiler.kt`, 6,558 → 6,718; **+280 at (P18.114)**, the JS CommonJS `exports` model — two walkers and five helpers in, B438d out; +27 at (P18.113), all but two lines KDoc; **−118 at (P18.112)**, the tslib ES5 helper arms; **−183 at (P18.111)**, seven target-gate families; **−285 at (P18.110)**, the TS1250/TS18028 families; **−243 at (P18.109)**, the `downlevelIteration` block; unchanged at (P18.108), which deleted `outFile`'s six arms from `TypeScriptCompiler.kt`, 6,566 → 6,553; +3 at (P18.107), the amd/umd/system fold — deleted behaviour, added KDoc; **−89 at (P18.106)**, five module-resolution derivation copies → one; **−50 at (P18.105)**, the interop arms; unchanged at (P18.104), which lifted the tsconfig option-position scan into `CompilerOptions.kt` for both paths; **+8 at (P18.103)**: code −6, KDoc +14, the `alwaysStrict: false` arms; unchanged at (P18.102), which deleted **249** lines of dead System-module code from `Transformer.kt`, 17,862 → 17,613 — the first (LEGACY.1) removal; **+24 at (P18.101)** — ~150 deleted (TS2346 gate + helpers, a one-fixture TS2300 walker), ~175 for tsgo's TS2309 rule; **+93 at (P18.100)**, anchor rules; **+333 at (P18.99)**, a SEMANTIC change — two new JSDoc walkers; **+79 at (P18.98)**, a SEMANTIC parity change — four tsgo mechanisms; **+81 at (P18.94)** — union DISPLAY sites routed through the (P18.85) comparator; **-28 at (P18.93)**, a SEMANTIC parity change (duplicate members reported at every declaration) that DELETES three tsc-6 narrowings and the TS6200 amalgamation; **+78 at (P18.92)**, a SEMANTIC parity change (TS2683/TS7009 in JS files) that also RETIRES tsc-6 walker B424; **+26 at (P18.91)**, a SEMANTIC parity change (TS2303 at every alias declaration); **+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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

**(P18.155) — (LEGACY.0b): TS7009 AT A NON-IDENTIFIER `new` CALLEE, AND TWO DEFENCES BUILT AGAINST A PHANTOM, 20,163 / 0 / 44 (2026-09-21).**
CLOSED NO LEDGER ROW (pending stays 19) and is recorded for its MEASUREMENT. `new O.m()`, `new N.f()`, `new h.g()`,
`new Base.make()` and `new arr[0]()` are TS7009 in tsgo 7.0.2 and were ALL silent here; tsc decides it from the RESOLVED
SIGNATURE's declaration (not a constructor / construct signature / constructor type), and the comment two lines below
the new arm had already recorded that rule from the other side while implementing only its noImplicitAny-OFF half. It
lives in `checkSingleNewExpressionTypes` because that runs under the ccet FRAME ambient, where a body-local receiver
resolves; the identifier path keeps its own symbol-based anchor (it reaches a named function expression's
self-reference and `super`, which this parser makes an Identifier) and a COUNT pin holds the no-double-emit. The corpus
screen found the one real refusal by reddening a CLODULE — `class C` + `function C` do not merge here, so the construct
side is invisible to the callee type AND to `resolveQualifiedValueSymbol`, and the refusal consults B511's own AST scan.
**THE ROUND'S MOST USEFUL FINDING IS A MISTAKE IT MADE**: after ablation arm b3 read 0 RED, a CLI probe "found" two
false positives and both were defended against — but that probe had run against the ARM's class directory ((CHK.54)'s
trap), i.e. against a binary with the very conjunct under test removed. The defences then passed the pins, the screen,
the 8-profile grid, both libraries and the full suite, **because a redundant guard is invisible to every gate**, and
they cost a positive tsgo reports. Re-measured from a known binary, both are unnecessary and are reverted; the KDoc
claim that a member access loses construct signatures was false and is gone. **The trap's new costume: it bites the
measurement taken to JUSTIFY work, not just the one taken to grade it**, and its tell was an arm reading 0 RED for a
guard just "proved" load-bearing by hand. Grid a REAL gate here (the round ADDS a diagnostic): 8x `added=0 removed=0
fullDiffLines=0`, emit byte-identical, cronstrue 1 and marked 18 unchanged. Five arms re-run against the corrected
code — b1 6 RED, b3 2 RED, b2/b4/b5 1 RED each, no redundant guard. cost_gate PASS; huge_methods PASS; warning gate
clean with both compile tasks verified EXECUTED.

**(P18.154) — (LEGACY.0b): AN INTERSECTION OF LITERAL SETS IS A KEY SET, AND A MAPPED TYPE OVER ONE WAS `any`, 20,148 / 0 / 44 (2026-09-20).**
Ledger 20 -> 19, `reverseMappedTypeIntersectionConstraint.errors.txt` CLOSED and ACTIVE in the screen's 3,079 / 0
(emit 5,646 / 0). The row was labelled "PIN-SERVED — RE-TRANSCRIBE OR RETIRE" and **there was an ENGINE defect under
it**: a mapped type whose key source is an INTERSECTION produced NO TYPE AT ALL — `getTypeFromMappedType` enumerates a
string literal or a union of them and `else`-bails to `anyType` — so the reverse-mapped idiom
`{ [K in keyof T & keyof C]: T[K] }` was a silent `any`, which the corpus, the grid and `cost_gate.py` are all green
about by construction. `reducePrimitiveDomainIntersection` is tsc's reduction restricted to the CLOSED family where
every constituent is a string/number/boolean/bigint literal, one of those four primitives, or a union of such — **which
is round 777's refusal's exact COMPLEMENT** (that view requires OBJECT-capable operands), so the two can never both
apply and no object intersection is distributed at construction. 10 of 11 measured shapes now byte-identical to tsgo.
**A SECOND, WIDER DEFECT FOUND ON THE WAY: LITERAL TYPES ARE NOT INTERNED HERE** (~25 construction sites, no factory),
so the id-keyed union dedupe kept both instances and `keyof Zed | keyof Wye` rendered `"alpha" | "alpha" | "beta" |
"zoo"`; fixed by keying that dedupe on the VALUE, which moves no type's identity and therefore no relation cache or
id-pair key. **(CHK.50) fired and the screen caught it in ONE run (1 mismatch of 8,724)**: B218 now REPLACES the
general excess-property row — its own KDoc's premise was the `anyType` bail — and sorts its display by KEY rather than
by the object literal's order, which is what makes the four re-transcribed pin strings what the ENGINE computes rather
than copied text. Retirement re-measured and still REFUSED (engine-only 5 -> 11 rows, but constraint-typed and at two
positions the baseline lacks). 20 pins, SIX arms: a2 10 RED, a6 2 RED, a1/a3/a5 1 RED each — **a3 read 0 RED first and
the pin set was BLIND** (`"a" & number` had to be constructed from the mechanism), and **a4 read 0 RED and is
genuinely REDUNDANT**, recorded in its KDoc as a barrier rather than claimed. cost_gate PASS (max +0.15%,
the profiles now resolving mapped types that used to bail); huge_methods PASS; warning gate clean with both compile
tasks verified EXECUTED.

**(P18.153) — (LEGACY.0b) RECON: TWO LEDGER REASONS RE-MEASURED, NEITHER WAS ITS MECHANISM, 20,128 / 0 / 45 (2026-09-20).**
No ledger movement and no compiled code touched — the round applies this session's own finding to the two rows a next
session would most likely reach for, because both carried one-line reasons that read as cheap. **`augmentExportEquals2.js`
is a HARNESS question**: its CASE FILE declares `// @filename: file3.ts` on two consecutive lines, tsgo's harness renders
an EMPTY `//// [file3.ts]` block followed by the real one and then emits ONLY `file3.js` = `"use strict";`, where we take
the content-bearing declaration and emit all three files — so the emit path is innocent, the seam is
`parseMultiFileSource`, and whether tsgo's duplicate-`@filename` behaviour is worth copying is a DECISION to take before
any code moves. **`jsdocImportTypeNodeNamespace` is not a JSDoc question** — `type A = import('./M').default` against an
`export default <namespace>` reproduces it in a plain `.ts` file (TS2694 in tsgo, SILENT here) — **and its real obstacle
is a COUNT: `import(` occurs ZERO times in the whole active generated corpus**, so a green screen there is a statement
about the corpus and not about the change; it needs a library probe plus pins, which is why it was sized and deliberately
not started at the end of a session. **Four of the reasons examined today described a SYMPTOM — a diff's first differing
line — and were read as verdicts on the MECHANISM**, three overstating the work and one understating it; correcting one
costs a screen run and no build.

**(P18.152) — (LEGACY.0b): THE IMPORT SHAPE A USER ACTUALLY WRITES, 20,128 / 0 / 45 (2026-09-20).**
Ledger 21 -> 20, `esModuleInteropTslibHelpers.errors.txt` CLOSED and ACTIVE in the screen's 3,078 / 0. A DEFAULT
IMPORT CLAUSE needs `__importDefault` exactly as `{ default as X }` does, so under `importHelpers` with no resolvable
`tslib` it is TS2354 — and `checkImportHelpersWithoutTslib` only ever looked at `namedBindings`, so
`import path from "path"` was the one shape it could not see. **THE LEDGER REASON WAS WRONG FOR THE THIRD TIME THIS
SESSION AND ALWAYS BY THE SAME MECHANISM**: it said `ours: ==== file.ts (0 errors) ====` where we in fact emitted
THREE of the baseline's four rows. A reason built from a diff's FIRST DIFFERING LINE describes a symptom and then gets
read as a verdict on the mechanism — as in (P18.149)'s "nesting is entirely missing" and (P18.150)'s "reports no
TS2416 at all". All three overstated the work. **One measured cell decided the implementation**: a clause carrying
BOTH a default name and a `default as` specifier reports at the SPECIFIER (1:16), not the statement (1:1), so the new
arm is ordered after the specifier one and gated on the statement having emitted nothing. **Residue measured and NOT
followed**: tsgo reports at (1,1) for a TYPE-ONLY default import, which emits nothing and so can need no helper —
a tsgo defect, in no baseline either way, pinned `residue -`. **And one gate was skipped HONESTLY, on a count**: the
8-profile grid is structurally vacuous because 0 of 8 profiles set `importHelpers`, so the walker returns on its first
line — (CHK.124)'s law used to avoid reading eight zeros and calling them a gate. cost_gate byte-identical to
(P18.150); huge_methods PASS. 5 pins, 2 arms, both red.

**(P18.151) — (LEGACY.0b): A REFUSAL THAT DISSOLVED WHILE NOBODY WAS LOOKING, 20,123 / 0 / 46 (2026-09-20).**
Ledger 22 -> 21, `pathsValidation5.errors.txt` CLOSED and ACTIVE in the screen's 3,077 / 0. The whole divergence was
where a `tsconfig.json` row sorts against a source file's in the summary; tsgo's `ast.CompareDiagnostics` compares the
two PATHS as strings, so `src/main.ts` comes first, and the baseline formatter gave the config file a privileged
position. **NOTHING WAS FIXED TO MAKE THIS POSSIBLE.** (LEGACY.0b) step 15 measured this exact deletion, found it moved
SEVEN green baselines — every one a baseUrl / node10 / rootDir case tsgo 7 does not run — refused it, and wrote the
count into the comparator's own comment, which is what made the refusal checkable. Re-run today it reads **0 of 8,722**:
(LEGACY.1) removed those option values, so the seven are no longer generated. **A refusal here is a measurement taken in
a REGIME, and a neighbouring arc can dissolve it without anyone noticing** — the second time in this session, after
(P18.149)'s `complexRecursiveCollections`. Cheap rule: when a pending row's reason names a COUNT of collateral
baselines, re-take the count before inheriting the refusal. Test-side only — production has no config-first ordering
rule and `Checker.class` is byte-identical to (P18.150)'s binary, so cost_gate and huge_methods are INAPPLICABLE rather
than skipped. The one arm reddens the pin AND the corpus row: one observable (round 927), recorded not double-counted.

