# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **196,783** lines (**+48 at (P18.140)**, one shared union-ordering helper consulted by BOTH arms of a display pair, plus the B219 walker's two sort keys — a SEMANTIC parity change, not an extraction; **+22 at (P18.139)**, one message change and its KDoc; the separator collapse lives in `CompilerOptions.kt` — a SEMANTIC parity change, not an extraction; **+478 at (P18.138)**, the JS object-literal host, `Object.defineProperty` membership and the access funnel arm with their KDoc — a SEMANTIC parity change, not an extraction; **+251 at (P18.137)**, the `const`-bound expando host and the route-(B) predicate with their KDoc — a SEMANTIC parity change, not an extraction; **+278 at (P18.136)**, the expando MEMBER model — a collector widened from a name set to positions plus right-hand sides, the attachment, and their KDoc — a SEMANTIC parity change, not an extraction; **+18 at (P18.135)**, one predicate call replacing an inline shape test plus its KDoc, net of a SHRINKING `getPropertyElaborationChain` (6,271 -> 6,260 bytecodes) — a SEMANTIC parity change; **+124 at (P18.134)**, one trust-gate relaxation plus two helpers and their KDoc — a SEMANTIC parity change, not an extraction; unchanged at (P18.133), an options-path round whose `Checker.class` is BYTECODE-IDENTICAL to (P18.132)'s landed binary — only `TypeScriptCompiler.kt` moved, 6,773 -> 6,831; **-8 at (P18.132)**, eight `&& options.baseUrl == null` FP conjuncts and two prose comments deleted with `baseUrl`'s behaviour — a REMOVAL, and the arc's first shrink since (P18.113); **+189 at (P18.131)**, the JavaScript class expando MEMBER model, the class static side and a five-line constructor-context display, net of a RETIRED 75-line tsc-6 walker — a SEMANTIC parity change, not an extraction; **+105 at (P18.130)**, the JavaScript expando firewall and the two funnel guards — a SEMANTIC parity change, not an extraction; unchanged at (P18.129), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.128)'s landed binary; **+242 at (P18.128)**, three class-parity mechanisms — a SEMANTIC change, not an extraction; unchanged at (P18.127) and (P18.126), two KIR-only rounds whose `Checker.class` is BYTE-IDENTICAL to (P18.125)'s landed binary; a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.125)'s landed binary; **+234 at (P18.125)**, one ADDITIVE star-export enumeration capability with exactly one non-test caller — a SEMANTIC parity change, not an extraction; unchanged at (P18.124), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.123)'s landed binary; **+38 at (P18.123)**, the JS-expando declaration rule net of a deleted per-tag aggregation loop — a SEMANTIC parity change; unchanged at (P18.122), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.121)'s landed binary; **+58 at (P18.121)**, net of a 61-line tsc-6 pass DELETED against ~119 added — a SEMANTIC parity change; **+31 at (P18.120)**, three duplicate-identifier mechanisms plus a computed-name span arm — a SEMANTIC parity change; **+97 at (P18.119)**, the `for`-header and `for…in` binding recorders plus the shared type half — a SEMANTIC parity change, not an extraction; unchanged at (P18.118), a KIR-only round whose four checker classes are byte-identical to the previous binary; **+43 at (P18.117)**, the index-signature read; **+61 at (P18.116)**, one related-span helper replacing three call sites; unchanged at (P18.115), whose two mechanisms live in `TypeScriptCompiler.kt`, 6,558 → 6,718; **+280 at (P18.114)**, the JS CommonJS `exports` model — two walkers and five helpers in, B438d out; +27 at (P18.113), all but two lines KDoc; **−118 at (P18.112)**, the tslib ES5 helper arms; **−183 at (P18.111)**, seven target-gate families; **−285 at (P18.110)**, the TS1250/TS18028 families; **−243 at (P18.109)**, the `downlevelIteration` block; unchanged at (P18.108), which deleted `outFile`'s six arms from `TypeScriptCompiler.kt`, 6,566 → 6,553; +3 at (P18.107), the amd/umd/system fold — deleted behaviour, added KDoc; **−89 at (P18.106)**, five module-resolution derivation copies → one; **−50 at (P18.105)**, the interop arms; unchanged at (P18.104), which lifted the tsconfig option-position scan into `CompilerOptions.kt` for both paths; **+8 at (P18.103)**: code −6, KDoc +14, the `alwaysStrict: false` arms; unchanged at (P18.102), which deleted **249** lines of dead System-module code from `Transformer.kt`, 17,862 → 17,613 — the first (LEGACY.1) removal; **+24 at (P18.101)** — ~150 deleted (TS2346 gate + helpers, a one-fixture TS2300 walker), ~175 for tsgo's TS2309 rule; **+93 at (P18.100)**, anchor rules; **+333 at (P18.99)**, a SEMANTIC change — two new JSDoc walkers; **+79 at (P18.98)**, a SEMANTIC parity change — four tsgo mechanisms; **+81 at (P18.94)** — union DISPLAY sites routed through the (P18.85) comparator; **-28 at (P18.93)**, a SEMANTIC parity change (duplicate members reported at every declaration) that DELETES three tsc-6 narrowings and the TS6200 amalgamation; **+78 at (P18.92)**, a SEMANTIC parity change (TS2683/TS7009 in JS files) that also RETIRES tsc-6 walker B424; **+26 at (P18.91)**, a SEMANTIC parity change (TS2303 at every alias declaration); **+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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

**(P18.140) — TWO ORDER ROWS, AND THREE REFUTED PREMISES, 20,027 / 0 / 53 (2026-09-20).**
Ledger 30 -> 28, both rows ACTIVE in the screen's 3,071 / 0. **The round's value is that it refuted its own brief
three times**: the `'Top' could be instantiated…` chain line is one WE ALREADY EMIT (it carried the same wrong
order, so a single ordering fix closed both lines of both occurrences and the suspected second mechanism does not
exist); `noInferUnionExcessPropertyCheck1` is NOT an engine order row but a dedicated B219 walker written for one
fixture; and its rows 7/15 needed no `Substitution` model — all three were pure order swaps. **The mechanism is a
PAIR with one arm unfixed**: `resolveSimpleTypeName`'s `UnionType` arm joined members VERBATIM while its sibling
`formatTypeForDisplay` has sorted since (LEGACY.0b) step 9, over the same node — and `StableTypeOrdering` already
carried a comment naming `typeParameterDiamond4`, so the comparator had been fixed for this shape and nothing
consulted it. The second row's rule is tsc's `compareTypes` in two keys (flags, then `compareSymbols` on the
declaration each constituent BOTTOMS OUT in), and **the decisive probe is moving the CALL above the declaration**
— with the signature first, flags and position agree, so every obvious cell is blind to the flag key. That is also
how the pin set was repaired: ablating the two keys read 1 and 2 RED only after a call-before-declaration pin was
added, without which the flag key would have shipped unpinned (round 807). Recorded and unqueued: `b: T | (() =>
T)` without `NoInfer` is silent in tsgo and emits TS2353 here — not in the corpus, so no gate sees it. 9 pins.

**(P18.139) — THREE SMALL LEDGER ROWS, AND ONE WHOSE MECHANISM DID NOT EXIST, 20,018 / 0 / 55 (2026-09-20).**
Ledger 33 -> 30, all three rows ACTIVE in the screen's 3,069 / 0 — the closure receipt. **The best result is a
FALSIFIED hypothesis**: the doubled path separator was briefed as a real path-JOIN defect and there is no join —
the `//` is AUTHORED in the fixture (line 36) and tsgo's harness normalizes unit names, so the fix belongs in
`parseMultiFileSource`, the corpus-harness directive path that `ProjectCompiler` never calls. Four independent
checks establish it (no join can produce one; exactly 1 of 1,556 `@Filename` values carries `//`; `^==== .*//`
matches one line across every baseline and the `.js` form matches zero; a real filesystem hands back no empty
component). **The collapse is narrower than `PathUtil.normalize` for a measured reason**: a full normalize moves 22
baselines, because tsgo's own `DiffFixupOld` forgives a leading `./` in the OLD baseline while recording a `//`.
**Two root-only `//` producers were found and left alone with their reachability recorded** — benign, and neither
pinnable today, since `-project`'s `InMemoryVfs` normalizes on every lookup ((CFG.1) forbids landing an
unobservable path change). TS18042's `.<name>` tail now fires only where tsgo's `IsImportSpecifier` gate allows.
**The mixin walker was NOT narrowed**: tsgo produces no such diagnostic for a non-generic owner (TS2415 at the
class instead), so our by-NAME test is a pre-existing ours-only row pinned `residue -` — a corpus-unique walker's
successor is PassLab retirement, not tuning. 16 pins, no countdown pin moved, and a (CHK.126) disarm check
confirmed no pin was keyed on a now-unproducible spelling.

**(P18.138) — (CHK.124) STEP 3: THE JAVASCRIPT OBJECT-LITERAL HOST, 20,002 / 0 / 58 (2026-09-19).**
Ledger 34 -> 33, `jsExpandoObjectDefineProperty` CLOSED, and the row is ACTIVE in the plain screen's 3,066 / 0 —
that, not `--include`, is the closure receipt. A third host kind (a JS EMPTY object literal initializing an
un-annotated `var`/`let`/`const` — all three binding kinds, where TypeScript requires `const`) plus
`Object.defineProperty` membership, reusing steps 1-2's collector rather than a second copy; all six descriptor
cells plus `writable` true/false and TS2540 are byte-identical to tsgo. **B433 is NOT retired and that is a
MEASUREMENT**: the PassLab priced it at 1 mismatch and the diff is the whole ROW, because its host is a body-local
`const` (B83.5's unbound population) and it also owns the file-level JS function-expression host, so admitting that
shape would double-emit — retiring it needs the BINDER. **The primitive-receiver arm was BUILT AND REVERTED**: it
closes the fixture's third row and breaks (P18.130)'s invariant that a `.js` row carries tsgo's MESSAGE, and the
three offending shapes reproduce identically in a `.ts` file on the parent binary, so the blocker is a standing
literal-display gap. **Eight ablation arms, one mistake at a time, and two found REDUNDANT guards that are recorded
rather than claimed**: three non-empty guards each read 0 RED alone while dropping all three lost a row and produced
a wrong display, and reading `writable` from the symbol type is 0 RED and CLI byte-identical, kept because it is
tsgo's spelling. Refused with measurements: a NUMERIC `defineProperty` name (tsgo names it canonically, round 934;
a subset member table is what (CHK.45) forbids), the expando chain, a JS function-/class-expression host. The screen
is largely a CONTROL here and the count was taken BEFORE the round — only 1 active case has the new host shape and
2 carry `defineProperty`. 23 pins.

**(P18.137) — (CHK.124) STEP 2: ROUTE (B) OPENS AND THE GRID'S GREEN IS VERIFIED, 19,979 / 0 / 59 (2026-09-19).**
**Ledger UNCHANGED at 34, deliberately — and this is the most valuable round of the three.** A member access on an
IDENTIFIER receiver whose type is a function type was COMPLETELY UNCHECKED: four real missed errors in eleven lines
of ordinary TypeScript (`declare const q: () => void; q.nope`), all four now byte-identical to tsgo in line, column
and message, while a genuine expando member stays silent. Half (a) extended the expando model to `const`-bound
function-expression hosts (tsgo's `getInitializerSymbol` arm: `const`, un-annotated, arrow/function-expression
initializer); half (b) is `cmamCallSignatureReceiverReportable`. **The crux was not emitting but NOT
DOUBLE-emitting** — B431's anchor owns its own population — and ablating that one guard reddens 3 pins with a
literal `assert(d.size == 1) -> 2` diagram; the two guards cover DIFFERENT halves, which is why both pins exist.
**THE GRID'S 8 x 0/0 WAS VERIFIED, NOT BANKED**, on a round where the profiles CAN express the shape (1,062
function-type annotations in the compiler profile): a marker arm measured route (B) entered 20-23 times per profile
with `reportable=0` and **every arrival `prop='call'`**, so `RUNTIME_PROPERTIES` is the one clause firing across
1.2M lines — the grid means "the rule is right", not "the shape is absent". The naive arm (no guard, no half (a))
reads 1 screen mismatch and 5 probe false positives. Refused with measurements and pinned `residue -`: a
construct-signature receiver, a callable interface with heritage, an imported function declaration. Two countdown
pins moved and a class KDoc section that predicted this work was retired. Libraries unchanged (cronstrue 1 -> 1,
marked 18 -> 18). 27 pins.

**(P18.136) — (CHK.124) STEP 1: REAL EXPANDO MEMBERS ON A FUNCTION TYPE, 19,952 / 0 / 59 (2026-09-19).**
Ledger 35 -> 34, and the ledger row is the SMALL half. `expandoFunctionNestedAssigments` closes because `typeof Foo`
now renders its members — THROUGH `typeToString`, which already produced tsgo's braces form byte-for-byte, so no
third hand-built expando display string was written. The big half is two shipping wrong answers: `const s: string =
g.px` was SILENT (the member was `any`) and is now tsgo-identical, and a FALSE TS2322 on `const d: { (): void; px:
number } = g` is gone. **A third is only PARTLY moved and the note says so**: `const c: typeof g = h` was silent and
now REPORTS, but as TS2322 against `'typeof g'` where tsgo says TS2741 against the structural form — missing-row to
wrong-code-row is an improvement, not parity. **A stale display rule was RETIRED**: `ExpandoReceiverDisplayTest`
asserted that both references name an expando-carrying function `typeof $name`, which was **pristine 6.0.3's
answer** and is FALSE of tsgo; both pins re-pointed and verified byte-identical on the property- and element-access
spellings. Members attach at `getTypeOfFunction` with three load-bearing orderings — the TABLE is planted before the
member TYPES (a right-hand side may read the host's own members, and the anonymous arm would otherwise plant an
EMPTY table and mask it), every member is seeded `anyType` so a cycle degrades, and the type is written at MINT time
ungated. Route (B) stays SHUT via `expandoAttachedTypeIds`, without which B431's anchor and the general path emit
the SAME row twice (measured, pinned). **The grid is a CONTROL with its count taken BEFORE the round** (0 genuine
expando shapes across 1,249 profile `.ts` files) **and the cost gate is structurally BLIND** — its counters are type
resolutions, the new scan is an AST walk; the per-CONTAINER memo is what bounds it. 17 pins.
