# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **195,036** lines (**+234 at (P18.125)**, one ADDITIVE star-export enumeration capability with exactly one non-test caller — a SEMANTIC parity change, not an extraction; unchanged at (P18.124), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.123)'s landed binary; **+38 at (P18.123)**, the JS-expando declaration rule net of a deleted per-tag aggregation loop — a SEMANTIC parity change; unchanged at (P18.122), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.121)'s landed binary; **+58 at (P18.121)**, net of a 61-line tsc-6 pass DELETED against ~119 added — a SEMANTIC parity change; **+31 at (P18.120)**, three duplicate-identifier mechanisms plus a computed-name span arm — a SEMANTIC parity change; **+97 at (P18.119)**, the `for`-header and `for…in` binding recorders plus the shared type half — a SEMANTIC parity change, not an extraction; unchanged at (P18.118), a KIR-only round whose four checker classes are byte-identical to the previous binary; **+43 at (P18.117)**, the index-signature read; **+61 at (P18.116)**, one related-span helper replacing three call sites; unchanged at (P18.115), whose two mechanisms live in `TypeScriptCompiler.kt`, 6,558 → 6,718; **+280 at (P18.114)**, the JS CommonJS `exports` model — two walkers and five helpers in, B438d out; +27 at (P18.113), all but two lines KDoc; **−118 at (P18.112)**, the tslib ES5 helper arms; **−183 at (P18.111)**, seven target-gate families; **−285 at (P18.110)**, the TS1250/TS18028 families; **−243 at (P18.109)**, the `downlevelIteration` block; unchanged at (P18.108), which deleted `outFile`'s six arms from `TypeScriptCompiler.kt`, 6,566 → 6,553; +3 at (P18.107), the amd/umd/system fold — deleted behaviour, added KDoc; **−89 at (P18.106)**, five module-resolution derivation copies → one; **−50 at (P18.105)**, the interop arms; unchanged at (P18.104), which lifted the tsconfig option-position scan into `CompilerOptions.kt` for both paths; **+8 at (P18.103)**: code −6, KDoc +14, the `alwaysStrict: false` arms; unchanged at (P18.102), which deleted **249** lines of dead System-module code from `Transformer.kt`, 17,862 → 17,613 — the first (LEGACY.1) removal; **+24 at (P18.101)** — ~150 deleted (TS2346 gate + helpers, a one-fixture TS2300 walker), ~175 for tsgo's TS2309 rule; **+93 at (P18.100)**, anchor rules; **+333 at (P18.99)**, a SEMANTIC change — two new JSDoc walkers; **+79 at (P18.98)**, a SEMANTIC parity change — four tsgo mechanisms; **+81 at (P18.94)** — union DISPLAY sites routed through the (P18.85) comparator; **-28 at (P18.93)**, a SEMANTIC parity change (duplicate members reported at every declaration) that DELETES three tsc-6 narrowings and the TS6200 amalgamation; **+78 at (P18.92)**, a SEMANTIC parity change (TS2683/TS7009 in JS files) that also RETIRES tsc-6 walker B424; **+26 at (P18.91)**, a SEMANTIC parity change (TS2303 at every alias declaration); **+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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
**(P18.123) — (LEGACY.0b) STEP 22: ONLY AN ASSIGNMENT DECLARES A JS EXPANDO, 19,741 / 0 / 65 (2026-09-17).**
Three faces of one rule: only `=` with an access LHS declares (so `this.c += 1` and a parenthesized target do not),
the reparser drops a `@type` tag above a non-declaration statement, and the unused-type-parameter aggregation is
over the declaration's whole list rather than per tag. **No row closed — both are PARTIAL, and that is the
finding.** Both are now strict SUBSETS of tsgo's output, byte-identical on every row we emit. Two corrections worth
carrying: **(P18.121)'s recorded "the aggregation predicate is already correct" was false and had been copied
forward into this round's brief**, and **the sequel does not follow** — a declaration rule only REMOVES rows, so
the missing-property half of both residues is a separate whole-family gap (the property-access check is OFF for
every `.js` file, even where the receiver's type comes from a `.ts` declaration). The naive gate flip was built and
refused on a measurement. 15 pins, 3 arms; the census is 11 case files of which only 3 are JS, which is what bounds
the risk.
**(P18.122) — (LIB.6) THE NOMINAL HALF: A GENERATED CLASS IS NOW A `JsObject`, 19,726 / 0 / 65 (2026-09-17).**
The KIR module goes 174 -> 194. A class instance could not reach an interface-typed slot, which the queue recorded
as the only thing between a real library and a running program on the JVM backend. **The item's recorded failure
was three rounds stale** — (P18.118) made its `IllegalArgumentException` unreachable — and re-measuring over 14
shapes found two failure modes: a compile-time refusal for 10, and for the 3 reached through `Any?` a
`ClassCastException` at ZERO dynamic ops, which is the silent one. **Neither of the item's two designs was
chosen**: the 158-edge interface closure is refused on three costs its own census does not price, and the "cheap"
shape does not work (a statically-bag method call never reaches the reflective fallback, and a type test shadows
it). What landed is the answer the backend had already written down for object literals — extend `JsObject`,
override the protocol over one's own slots, chain to the base through `super`, and answer a method call through a
generated `when` rather than reflection. All 14 shapes run at `dynamicOps = 0`. 20 pins, 6 arms; `huge_methods` run
over the KIR module as well as core, since the default census is core-only; the grid is inapplicable by
construction and that byte-identity is its receipt.
**(P18.121) — (LEGACY.0b) STEP 21: THREE MECHANISMS WHOSE EMITTERS WE ALREADY HAD, 19,706 / 0 / 65 (2026-09-16).**
The cluster was picked on one property — `Checker.kt` already emitted all three codes — so each row was a gate that
did not fire rather than a missing feature, and the first job was to find the existing emitter. TS8026: the
heritage arity gate suppressed on ANY governing `@augments` tag where tsgo suppresses only on a VALID count and
anchors at the heritage expression; a tsc-6 pass retired, its removal first measured at 0 screen mismatches with
the PassLab. TS2671: one shared predicate now serves the emitter and the augmentation collector, which SKIPS the
merge — tsgo's own control flow, error instead of merging — which is what removes the ours-only duplicate pair.
TS6205 REFUSED with the decisive control: we already emit it, the aggregation predicate is sound, and the blocker
is that a bare `/** @type {T} */ this.p;` declares no property here. Pending baselines 42 -> 40. **A resolution
ladder that works in a `-project` fixture can resolve NOTHING in the corpus** — the screen caught that, the fixture
did not.
