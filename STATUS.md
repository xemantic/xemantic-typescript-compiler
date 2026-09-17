# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **194,764** lines (unchanged at (P18.122), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.121)'s landed binary; **+58 at (P18.121)**, net of a 61-line tsc-6 pass DELETED against ~119 added — a SEMANTIC parity change; **+31 at (P18.120)**, three duplicate-identifier mechanisms plus a computed-name span arm — a SEMANTIC parity change; **+97 at (P18.119)**, the `for`-header and `for…in` binding recorders plus the shared type half — a SEMANTIC parity change, not an extraction; unchanged at (P18.118), a KIR-only round whose four checker classes are byte-identical to the previous binary; **+43 at (P18.117)**, the index-signature read; **+61 at (P18.116)**, one related-span helper replacing three call sites; unchanged at (P18.115), whose two mechanisms live in `TypeScriptCompiler.kt`, 6,558 → 6,718; **+280 at (P18.114)**, the JS CommonJS `exports` model — two walkers and five helpers in, B438d out; +27 at (P18.113), all but two lines KDoc; **−118 at (P18.112)**, the tslib ES5 helper arms; **−183 at (P18.111)**, seven target-gate families; **−285 at (P18.110)**, the TS1250/TS18028 families; **−243 at (P18.109)**, the `downlevelIteration` block; unchanged at (P18.108), which deleted `outFile`'s six arms from `TypeScriptCompiler.kt`, 6,566 → 6,553; +3 at (P18.107), the amd/umd/system fold — deleted behaviour, added KDoc; **−89 at (P18.106)**, five module-resolution derivation copies → one; **−50 at (P18.105)**, the interop arms; unchanged at (P18.104), which lifted the tsconfig option-position scan into `CompilerOptions.kt` for both paths; **+8 at (P18.103)**: code −6, KDoc +14, the `alwaysStrict: false` arms; unchanged at (P18.102), which deleted **249** lines of dead System-module code from `Transformer.kt`, 17,862 → 17,613 — the first (LEGACY.1) removal; **+24 at (P18.101)** — ~150 deleted (TS2346 gate + helpers, a one-fixture TS2300 walker), ~175 for tsgo's TS2309 rule; **+93 at (P18.100)**, anchor rules; **+333 at (P18.99)**, a SEMANTIC change — two new JSDoc walkers; **+79 at (P18.98)**, a SEMANTIC parity change — four tsgo mechanisms; **+81 at (P18.94)** — union DISPLAY sites routed through the (P18.85) comparator; **-28 at (P18.93)**, a SEMANTIC parity change (duplicate members reported at every declaration) that DELETES three tsc-6 narrowings and the TS6200 amalgamation; **+78 at (P18.92)**, a SEMANTIC parity change (TS2683/TS7009 in JS files) that also RETIRES tsc-6 walker B424; **+26 at (P18.91)**, a SEMANTIC parity change (TS2303 at every alias declaration); **+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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
**(P18.120) — (LEGACY.0b) STEP 20: THE LAST THREE F2 DUPLICATE-IDENTIFIER ROWS, 19,694 / 0 / 67 (2026-09-16).**
Three distinct mechanisms, all landed: an INTERFACE's duplicate group reports at every member whatever its binder
visibility; a method-vs-property name across MERGED interface blocks is TS2300 at every declaration with no TS2717
and no TS2687 (the walker had been emitting tsc-6's answer); and a constructor overload's own parameter property is
reported beside the implementation's. The skip count falling 70 → 67 is the receipt that the rows are now ACTIVE
tests; `tsgoPendingBaselines` 46 → 43. **The recorded reason was wrong on the first row** — `lateBindMember` is a
real emitter and not that one — and **the obvious generalisation is a measured false positive**: un-gating
late-bound TS2300 holds for an interface and not for a class, where tsgo is order-dependent, so it was built,
measured and reverted. The only instrument that saw that over-reach was a hand-written pin: the corpus screen read
0 and the grid is a measured CONTROL here (admissions 0 on all eight profiles, 4/1/3 on the scratch fixtures). Two
countdown pins asserting pristine's answer fell and were re-measured against tsgo, never weakened.
**(P18.119) — (CHK.136): A `for`-HEADER BINDING AND A `for…in` BINDING TYPED `any`, 19,672 / 0 / 70 (2026-09-16).**
tsgo reports 7 rows on an 11-line fixture where we reported 2. A `ForStatement`'s initializer is a
`VariableDeclarationList` whose parent is the LOOP, not a `VariableStatement`, and both recorders test that parent —
so every `for`-header binding was recorded by NOTHING and typed `any` inside its own loop, annotated or not, while
`for-of` (a (CHK.29) arm) and an ordinary `let` were correct, which is why every earlier probe read healthy. It is
also the CHECKER-side root cause (P18.118) measured from the backend: `nums[i]` was `any` because `i` was. The two
recorders register against the loop's own nodeId, so the scope covers the condition and incrementor and pops at the
leave; the type half is shared structurally with the statement recorder so the rules cannot drift. **The 8-profile
grid is the gate and reads `added=0 removed=0` on all eight, with 78 emit files byte-identical**; cost gate max
+0.15% on `typeOfExpr.distinct`, moving with `calls` on a flat `spine.nodes`. 20 pins, 4 arms; the split briefly
left a 39-bytecode delegating wrapper that `HugeMethodLimitTest`'s partition pin caught — repaired by inlining it,
never by lowering the bound.
**(P18.118) — (KIR.LOWER.3)+(KIR.LOWER.4): THE LOWERING'S BAG FALLBACK, AND TWO DEFECTS THE ITEMS DO NOT NAME, 19,652 / 0 / 70 (2026-09-16).**
The first round of this session outside the checker-parity lane, taken because these two are the largest measured
KIR performance lever and a native-arm blocker sharing one mechanism: the lowering asks for a receiver's type, does
not get one, and falls back to the dynamic bag. Measured as BYTECODE SHAPE. (LOWER.3)'s headline is wrong — an
element access keeps its type; the INDEX loses it when it is a `for`-header `let`, and that is a CHECKER gap (three
missing true positives against tsgo), deliberately NOT fixed here because it is (CHK.50) at maximum radius; KIR
recovers locally, 2 ops → 0. (LOWER.4) understates itself — every `this` member READ was broken too, both paths
consulting `isDynamicReceiver` before resolving a field they could already resolve; 4 ops → 0 and parameter
properties go from a refused compile to 0, with the store prologue above the initializers (measured off tsgo's
emit; the other order prints the wrong answer). **Two defects neither item names**: `ps[0].x` emitted a `getfield`
on `java.lang.Object` and died with `NoSuchFieldError` at ZERO dynamic ops — the shape a shape-only pin waves
through, which is why every mechanism now has a behaviour case — and method calls still reached `jsInvoke` in the
loop the fields had just left. 15 cases, 8 arms; KIR module 174/0; `huge_methods` run over the KIR module as well
as core, since the default census is core-only; the grid is inapplicable (checker classes byte-identical) and that
comparison is the receipt.
