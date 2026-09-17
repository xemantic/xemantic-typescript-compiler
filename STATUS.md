# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **195,278** lines (**+242 at (P18.128)**, three class-parity mechanisms — a SEMANTIC change, not an extraction; unchanged at (P18.127) and (P18.126), two KIR-only rounds whose `Checker.class` is BYTE-IDENTICAL to (P18.125)'s landed binary; a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.125)'s landed binary; **+234 at (P18.125)**, one ADDITIVE star-export enumeration capability with exactly one non-test caller — a SEMANTIC parity change, not an extraction; unchanged at (P18.124), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.123)'s landed binary; **+38 at (P18.123)**, the JS-expando declaration rule net of a deleted per-tag aggregation loop — a SEMANTIC parity change; unchanged at (P18.122), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.121)'s landed binary; **+58 at (P18.121)**, net of a 61-line tsc-6 pass DELETED against ~119 added — a SEMANTIC parity change; **+31 at (P18.120)**, three duplicate-identifier mechanisms plus a computed-name span arm — a SEMANTIC parity change; **+97 at (P18.119)**, the `for`-header and `for…in` binding recorders plus the shared type half — a SEMANTIC parity change, not an extraction; unchanged at (P18.118), a KIR-only round whose four checker classes are byte-identical to the previous binary; **+43 at (P18.117)**, the index-signature read; **+61 at (P18.116)**, one related-span helper replacing three call sites; unchanged at (P18.115), whose two mechanisms live in `TypeScriptCompiler.kt`, 6,558 → 6,718; **+280 at (P18.114)**, the JS CommonJS `exports` model — two walkers and five helpers in, B438d out; +27 at (P18.113), all but two lines KDoc; **−118 at (P18.112)**, the tslib ES5 helper arms; **−183 at (P18.111)**, seven target-gate families; **−285 at (P18.110)**, the TS1250/TS18028 families; **−243 at (P18.109)**, the `downlevelIteration` block; unchanged at (P18.108), which deleted `outFile`'s six arms from `TypeScriptCompiler.kt`, 6,566 → 6,553; +3 at (P18.107), the amd/umd/system fold — deleted behaviour, added KDoc; **−89 at (P18.106)**, five module-resolution derivation copies → one; **−50 at (P18.105)**, the interop arms; unchanged at (P18.104), which lifted the tsconfig option-position scan into `CompilerOptions.kt` for both paths; **+8 at (P18.103)**: code −6, KDoc +14, the `alwaysStrict: false` arms; unchanged at (P18.102), which deleted **249** lines of dead System-module code from `Transformer.kt`, 17,862 → 17,613 — the first (LEGACY.1) removal; **+24 at (P18.101)** — ~150 deleted (TS2346 gate + helpers, a one-fixture TS2300 walker), ~175 for tsgo's TS2309 rule; **+93 at (P18.100)**, anchor rules; **+333 at (P18.99)**, a SEMANTIC change — two new JSDoc walkers; **+79 at (P18.98)**, a SEMANTIC parity change — four tsgo mechanisms; **+81 at (P18.94)** — union DISPLAY sites routed through the (P18.85) comparator; **-28 at (P18.93)**, a SEMANTIC parity change (duplicate members reported at every declaration) that DELETES three tsc-6 narrowings and the TS6200 amalgamation; **+78 at (P18.92)**, a SEMANTIC parity change (TS2683/TS7009 in JS files) that also RETIRES tsc-6 walker B424; **+26 at (P18.91)**, a SEMANTIC parity change (TS2303 at every alias declaration); **+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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

**(P18.128) — (CHK.137)+(CHK.138): TWO GAPS THAT WERE FIVE, 19,881 / 0 / 65 (2026-09-17).**
The first is a SYMMETRIC pair rather than a false positive: the same artifact that makes a class value type as its
instance type hides a false NEGATIVE too, and a fixture that varies only whether the class declares a constructor
swaps which one you see. **And it is script-file-only** — a bare `export {}` makes it vanish, so a matrix written
the obvious way reads completely clean and nearly lost the round. Closing it forced a third mechanism, since the
false positive had been masking an abstract-class error at the right position with the wrong code. The second is
three defects including an ours-only FALSE POSITIVE pointing the opposite way to its own queue item, and its
reference has TWO emitters with different target gates, so an unset target is silent and every pin must name one.
Its receipt had to be independent of the corpus, which carries one subtest: a conformance fixture reconstructed
from its pristine baseline reads 20 of 60 rows with zero false positives. 40 pins, 23 red against the parent, six
arms. **The round also fired the previous round's countdown and corrected its premise**: a guard recorded as
measured-redundant was only redundant below ES2022, so it is now live.
**(P18.127) — (KIR.LOWER.6): A NAMED FUNCTION OR CLASS AS A VALUE, 19,840 / 0 / 65 (2026-09-17).**
The KIR module goes 242 -> 275. `[1,2].map(f)` for a top-level named `f` REFUSED — passing a named function as a
callback — and 21 of 33 characterised shapes refused with it. **The brief's premise was half wrong in the way that
matters**: the values already existed and TWO of them were defective, both predating the arm and both hidden by the
refusal — a function value minted a fresh forwarder per read, so `C.m === C.m` compiled and printed `false`, and a
rest-parameter function read as a value took the fixed-arity forwarder and threw at run time. The arm alone would
have shipped both into the commonest shape in the language. The lesson generalises: when a refusal is removed, the
values behind it have never been exercised — audit them rather than assuming the refusal was the only gap. Both
carriers are now lazy statics, identity holds across the namespace object too, and `.name` is answered on the
carrier with no reflection in either spelling. 33 pins, 28 red against the parent, eight arms; the 33 pins are the
only gate this work has, since the checker is untouched.
**(P18.126) — (KIR.LOWER.5): A DYNAMIC `new`, AND THE LOADER SHAPE RUNS END TO END, 19,807 / 0 / 65 (2026-09-17).**
The KIR module goes 223 -> 242. A pure `export * from` barrel, a namespace import, a `for...in`, a dynamic
construction and a METHOD CALL on what was constructed now run together, at one construction operation and zero
dynamic reads — the method call being the half that says the value is a real instance rather than a bag. It took
four rounds ((P18.122) storage, (P18.124) the namespace object, (P18.125) barrel enumeration, this one
construction); the library is not on this box, so it is a claim about the SHAPE. **The brief's "one arm" would have
been a silent wrong answer**: a class's value and a function's value are the same carrier, so routing `new` at it
answers a function's return value (measured). The mechanism is a constructor carrier; everything else is a type
error, with a plain function a stated divergence from node. A mirror defect closed on the way: CALLING a class used
to silently construct. Nothing reaches reflection. 19 pins, 18 red against the parent, six arms — one of which
reddens two pins from the two previous rounds, which is the receipt that all three are one mechanism.
**(P18.125) — THE TWO EXPORT GAPS ARE ONE CAPABILITY, 19,788 / 0 / 65 (2026-09-17).**
The KIR module goes 211 -> 223. A module symbol's export table IS the target file's `locals`, which is wrong for an
enumeration three ways: a star re-export contributes nothing, a renaming specifier is keyed by the DECLARED name,
and it holds names the file does not export at all. Keying by the name an importer sees makes the first two one
walk and the third fall out free. **The brief's failure mode was wrong in the direction that matters**: "a barrel
enumerates nothing" holds only for a PURE barrel, where the guard refuses loudly; a barrel with its own exports and
a star CYCLE both have a non-empty table, so the guard passed and the starred names came back null at run time —
a silent wrong answer, found by sweeping ten shapes rather than the six briefed. The capability is ADDITIVE and
that is measured, not argued: it has one non-test caller, and an arm that puts a mistake inside it moves 0 of 8,790
corpus subtests, which is also why a green grid here is a control. 18 core pins asserting symbol IDENTITY, 12 new
KIR pins, two countdown pins re-pointed; five arms. The library now needs exactly one more thing, a dynamic
construction, queued as (KIR.LOWER.5).
**(P18.124) — (LIB.7): A NAMESPACE IMPORT HAS A RUNTIME OBJECT, 19,758 / 0 / 65 (2026-09-17).**
The KIR module goes 194 -> 211. **The brief asked the wrong question**: it framed the round as a choice between two
shapes of module object, and a 21-shape characterisation found the object is needed for 4 of them while the other
17 need a qualified reference that costs nothing at run time — a round that built only the object would have
shipped every namespace-imported member as a reflective read, which the ablation prices exactly. The structural
finding is that the CHECKER is silent about a namespace member: it answers the module's whole export table and
answers nothing for a member or a call signature, so three gaps sit behind one refusal. The object is a final
`JsObject` subclass with generated protocol members and a lazy singleton, one per IMPORTING file because the IR
verifier refuses a cross-file declaration; its exports are reached through accessors and stay LIVE, which is the
argument against an eager bag — an ES module's exports are live bindings and a bag is a copy. A renamed export is
now REFUSED rather than silently mis-answered, a defect this round's own fix introduced and its sweep caught.
17 pins, 4 arms; `huge_methods` over the KIR module as well as core; the grid inapplicable by construction.
