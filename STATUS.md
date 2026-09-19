# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **195,688** lines (**+124 at (P18.134)**, one trust-gate relaxation plus two helpers and their KDoc — a SEMANTIC parity change, not an extraction; unchanged at (P18.133), an options-path round whose `Checker.class` is BYTECODE-IDENTICAL to (P18.132)'s landed binary — only `TypeScriptCompiler.kt` moved, 6,773 -> 6,831; **-8 at (P18.132)**, eight `&& options.baseUrl == null` FP conjuncts and two prose comments deleted with `baseUrl`'s behaviour — a REMOVAL, and the arc's first shrink since (P18.113); **+189 at (P18.131)**, the JavaScript class expando MEMBER model, the class static side and a five-line constructor-context display, net of a RETIRED 75-line tsc-6 walker — a SEMANTIC parity change, not an extraction; **+105 at (P18.130)**, the JavaScript expando firewall and the two funnel guards — a SEMANTIC parity change, not an extraction; unchanged at (P18.129), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.128)'s landed binary; **+242 at (P18.128)**, three class-parity mechanisms — a SEMANTIC change, not an extraction; unchanged at (P18.127) and (P18.126), two KIR-only rounds whose `Checker.class` is BYTE-IDENTICAL to (P18.125)'s landed binary; a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.125)'s landed binary; **+234 at (P18.125)**, one ADDITIVE star-export enumeration capability with exactly one non-test caller — a SEMANTIC parity change, not an extraction; unchanged at (P18.124), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.123)'s landed binary; **+38 at (P18.123)**, the JS-expando declaration rule net of a deleted per-tag aggregation loop — a SEMANTIC parity change; unchanged at (P18.122), a KIR-only round whose `Checker.class` is BYTE-IDENTICAL to (P18.121)'s landed binary; **+58 at (P18.121)**, net of a 61-line tsc-6 pass DELETED against ~119 added — a SEMANTIC parity change; **+31 at (P18.120)**, three duplicate-identifier mechanisms plus a computed-name span arm — a SEMANTIC parity change; **+97 at (P18.119)**, the `for`-header and `for…in` binding recorders plus the shared type half — a SEMANTIC parity change, not an extraction; unchanged at (P18.118), a KIR-only round whose four checker classes are byte-identical to the previous binary; **+43 at (P18.117)**, the index-signature read; **+61 at (P18.116)**, one related-span helper replacing three call sites; unchanged at (P18.115), whose two mechanisms live in `TypeScriptCompiler.kt`, 6,558 → 6,718; **+280 at (P18.114)**, the JS CommonJS `exports` model — two walkers and five helpers in, B438d out; +27 at (P18.113), all but two lines KDoc; **−118 at (P18.112)**, the tslib ES5 helper arms; **−183 at (P18.111)**, seven target-gate families; **−285 at (P18.110)**, the TS1250/TS18028 families; **−243 at (P18.109)**, the `downlevelIteration` block; unchanged at (P18.108), which deleted `outFile`'s six arms from `TypeScriptCompiler.kt`, 6,566 → 6,553; +3 at (P18.107), the amd/umd/system fold — deleted behaviour, added KDoc; **−89 at (P18.106)**, five module-resolution derivation copies → one; **−50 at (P18.105)**, the interop arms; unchanged at (P18.104), which lifted the tsconfig option-position scan into `CompilerOptions.kt` for both paths; **+8 at (P18.103)**: code −6, KDoc +14, the `alwaysStrict: false` arms; unchanged at (P18.102), which deleted **249** lines of dead System-module code from `Transformer.kt`, 17,862 → 17,613 — the first (LEGACY.1) removal; **+24 at (P18.101)** — ~150 deleted (TS2346 gate + helpers, a one-fixture TS2300 walker), ~175 for tsgo's TS2309 rule; **+93 at (P18.100)**, anchor rules; **+333 at (P18.99)**, a SEMANTIC change — two new JSDoc walkers; **+79 at (P18.98)**, a SEMANTIC parity change — four tsgo mechanisms; **+81 at (P18.94)** — union DISPLAY sites routed through the (P18.85) comparator; **-28 at (P18.93)**, a SEMANTIC parity change (duplicate members reported at every declaration) that DELETES three tsc-6 narrowings and the TS6200 amalgamation; **+78 at (P18.92)**, a SEMANTIC parity change (TS2683/TS7009 in JS files) that also RETIRES tsc-6 walker B424; **+26 at (P18.91)**, a SEMANTIC parity change (TS2303 at every alias declaration); **+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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

**(P18.134) — A MISSING MEMBER ON A FUNCTION TYPE: ONE TRUST GATE, 19,927 / 0 / 61 (2026-09-19).**
`contextualReturnTypeOfIIFE2` closes (pending 37 -> 36). `cmamAllMissingTrustedMember`'s
`if (m.symbol != null) return false` refused every SYMBOL-CARRYING function type, so
`declare const o: { m: typeof g }` was silent where the identical `{ m: () => void }` reported —
five shapes losing a diagnostic on one line. **The round's opening hypothesis was wrong**: the axis is
not namespace-qualification (`namespace A { const foo: () => void }` + `A.foo.bar` has always reported)
but whether the type carries a declaration symbol. **tsgo has no checker-side expando exemption at all** —
its BINDER declares expando properties onto the host symbol and TS2339 falls out of ordinary lookup, the
host predicate keying on the head's `valueDeclaration` KIND at every hop of a dotted name; six probe shapes
changed answer between 6.0.3 and 7.0.2 on exactly that. **We suppress where tsgo declares** (measured:
`function z(){} z.px = 1; const s: string = z.px` is silent here, TS2322 in tsgo, and TS2565 never fires),
which is what bounded the round to the property-access-receiver route. The identifier route stays SHUT
until expando members are modelled — it is the only reason `const f = () => {}; f.bar = 1` is correctly
silent — and is the named successor ((CHK.124)). The guard was **built unguarded first** and fires on five
attributable cells; it reuses B431's collector verbatim so the two routes cannot drift, and its scan is
scoped to the declaration's own container, not its file. The grid is a false-positive CONTROL and says so;
reach is evidenced by the cost gate's non-zero deltas, which a no-op change cannot produce. 24 pins, no
countdown pin moved.

**(P18.133) — THE `simulatedVersion` DEFAULT MOVES TO 7.0; TypeScript 6's LADDER IS GONE, 19,903 / 0 / 62 (2026-09-17).**
One line, and everything else is consequence: every TS7-removed option now reports tsgo's TS5102/TS5108 *has been
removed* instead of TypeScript 6's TS5101/TS5107 *deprecated, will stop functioning*, and `ignoreDeprecations` no
longer silences it. **The measurement**: across tsgo's whole baseline corpus TS5101 and TS5107 appear ONLY as lines
tsgo deletes, and `ignoreDeprecations` is parsed and read nowhere. **The correctness argument is the stronger one** —
(LEGACY.1) had already deleted these options' behaviour, so we were telling users an option "will stop functioning
in TypeScript 7.0" and offering a flag to silence it while it was already inert. Two pending rows CLOSE (ledger
39 -> 37, skipped 64 -> 62) and a third is DROPPED rather than ledgered: `pathMappingInheritedBaseUrl`'s baseline is
PRISTINE's TS5101, so it could never close — an un-closeable row is not a pending row, and the skip now follows a
config's `extends` chain to reach it. **`keptTsc` 3 -> 1, not 3 -> 2, because the bucket counts BASELINES** and that
one case contributes two. **The round's reusable lesson is a census failure**: sizing the at-risk pins by diagnostic
CODE could not see the nine classes that depend on a code NOT being emitted (`@ignoreDeprecations: 6.0`), which was
29 of 41 first-run failures. TS5103 retired with its validity filter KEPT (`"banana" >= "6.0"` is lexicographically
true, so dropping the filter would make garbage start silencing the ladder); `module=None` refused as TS6046's, i.e.
(LEGACY.2)'s. Grid is a **counted control** — no profile sets any flipping option and no capture in either arm
carries one of the four codes.

**(P18.132) — (LEGACY.1)(g): `baseUrl` DELETED, AND (LEGACY.1) IS CLOSED, 19,885 / 0 / 64 (2026-09-17).**
The last unlanded sub-step of the TS7-removal arc, and it needed an owner decision rather than code: 27 active
subtests set `baseUrl` in an EMBEDDED tsconfig and tsgo has **no output of any kind** for one of them, so they
were pinned to pristine TypeScript 6 through the *keep tsc's* leg of the baseline fallback. With both halves of
the proposal approved, the embedded-tsconfig skip learned `baseUrl` and `moduleResolution: node/node10/classic`
— **not `target`, which is measured gradeable** (7 of 9 such cases have real tsgo baselines) — and the resolution
behaviour went. **The receipt is a count, not an argument**: `tsgoExpectedKeptTsc` 87 -> 3 while `adopted`, `new`
and `deleted` are byte-identical, i.e. only pristine-pinned subtests left. **The brief was wrong in the dangerous
direction and the agent caught it**: tsgo's harness loads an embedded tsconfig and then lets the directives
OVERRIDE it, so an embedded-only rule deletes the one case tsgo did answer; the shipped predicate exempts any
option a directive names. TS5090 stays and its predicate changed twice (tsgo does not gate it on `baseUrl` and
does exempt absolute substitutions — landing only the first change manufactures false positives). TS5102 now
carries tsgo's computed `Use '"paths": {"*": ["./src/*"]}' instead.` chain, measured over nine values, with the
6.0-default branch untouched so **(P18.133)** can move the default safely. The 8-profile grid is a **control and
counted**: no profile sets `baseUrl` or `paths`, and it reads 8 x 0/0 with emit 78 vs 78 byte-identical.

**(P18.131) — THE JAVASCRIPT EXPANDO MEMBER MODEL: A LEDGER ROW CLOSED, 19,954 / 0 / 64 (2026-09-17).**
The first closure in this family: pending baselines **40 → 39**, and the other entry goes from four missing rows to
one. A JavaScript class's members now include what its assignments put there, resolved syntactically from the
`this`/`super` binder and unioned over the `extends` chain, so a missing-member read on such a receiver is
decidable instead of refused. **A tsc-6 walker is retired** — it existed only because this family was off, and with
the general path owning the row it double-emits; the PassLab measured that in one run. **The display half was never
a JavaScript question**: the defect is a CONSTRUCTOR-context one in plain TypeScript, where the member table has
not resolved and the read lands on a different emitter, and the fix is five lines. Two further pre-existing
TypeScript divergences were found and refused with their measurements rather than folded in. Errors screen
3,103 / 0 and emit 5,688 / 0; **the 8-profile grid is a real gate here** — the display change is TypeScript-visible
— and reads 8 x 0/0; `cpaSpineLeave` still 7,898 of 8,000, all new code in helpers.

**(P18.130) — THE JAVASCRIPT PROPERTY-ACCESS FAMILY: FOUR GATES ARE TWO PAIRS, 19,931 / 0 / 65 (2026-09-17).**
The family was off for every `.js` file; it is now on for a receiver whose member table cannot carry a JavaScript
expando. **The four gates are two enter/leave PAIRS**, and characterising them split the round: the call pair costs
three corpus mismatches and delivers none of the six ledger rows, so it stays shut; the property-access pair costs
two and delivers all six. **The expando rule is the opposite of the obvious one** — an assignment declares only on
the STATIC side of a JavaScript class or function, and a TypeScript-declared receiver reports even when written from
JavaScript, so what ships is a whitelist rather than a suppression list; ungated, the family invents ten rows tsgo
does not report on a nineteen-line file. Every row we now emit in a `.js` file is byte-identical to tsgo's over
eight probes. **It closes no ledger row, and that is stated rather than hidden**: all six have JavaScript-declared
receivers, which the firewall refuses by construction, so the rest of the arc is queued as a seven-item table.
Errors screen 3,102 / 0 is the real gate (153 of 2,898 active error subtests carry a `.js` file); the 8-profile
grid is a control and reads 8 x 0/0. **`cpaSpineLeave` is now at 7,898 of the 8,000-bytecode JIT limit.**
