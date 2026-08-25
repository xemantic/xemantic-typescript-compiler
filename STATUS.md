# Status

**A FILE'S MODULE FORMAT NOW COMES FROM THE NEAREST `package.json` `"type"` — `TS1295+TS1287`
ON knip GO **2,478 -> 0**, AND EVERY STANDING GATE IN THIS REPO IS BLIND TO IT (2026-08-25,
(CHK.29)).** Under `nodenext`/`node16` tsc reads the nearest enclosing `package.json`; we had the
CONSUMER (`packageJsonTypes` + the lookup in `isESModuleFormat`) and one producer that reads the
corpus's PARSED SOURCE SET — and **a real project has no `package.json` among its inputs**, so on
every project build the map was empty, every file was CommonJS, and every ESM import/export
tripped `verbatimModuleSyntax`. `ProjectCompiler` now walks the `Vfs` up from each program file's
directory, memoized per DIRECTORY, gated on `isNodeNext`; reading through the `Vfs` is what puts
the language service's overlay on the same path (pinned: an overlaid `package.json` that exists
nowhere on disk flips the format on the next query).
**THE BLINDNESS AS A COUNT: the eight dashboard profiles hold `0` `package.json` files between
them**, and the corpus materialises no directory at all — so a green suite, `added=0 removed=0`
and `+0.00%` are the EXPECTED answers and none is evidence. `ProjectPackageJsonTypeTest` (11
pins, `-project`, real `ProjectCompiler` + `Vfs`) is the instrument; the six gates are controls.
**TWO CORRECTIONS tsgo FORCED, NEITHER GUESSED**: a manifest with NO `"type"` ESTABLISHES the
scope at CommonJS (the walk stops at the first one it meets, so it must not fall through to a
`"type": "module"` ancestor — the old collector `continue`d, i.e. had this wrong); and the
manifest is parsed as JSON, because knip's own has `repository.type: "git"` and two
`funding[].type` BEFORE the real key, so a first-match regex answers CommonJS for a `"type":
"module"` package — worth all 2,478 rows on its own.
**MEASURED, ONE DRAW EACH**: all seven disk fixtures now agree with tsgo 7.0.2 error for error
(they already agreed POSITION-for-position on the CommonJS rows, which isolates the defect to the
format decision); knip @ `dc7aca5` **2,634 -> 309** (147 of the 309 environmental, no
`node_modules`); emit checked in both directions and byte-identical to tsgo.
**GATES.** Suite **15,871 / 0 / 3** (+11 pins; the first ten were written BEFORE the fix and
verified RED against it, the eleventh landed in a follow-up commit); build warning-clean; `cost_gate.py` `output.errors` **46**, vector unmoved (standing
`mapped.hits` +1.63% drift unchanged, unrebaselined); `huge_methods.py --fail-over 0` exit 0,
**783** classes scanned (unchanged — the change adds methods, not classes);
`partition-equivalence` **EQUIVALENT 78/78**, floor **60 ms** `[53, 58, 60, 65]` against 59 last
round, and the walk DOES run there (the compiler profile is NodeNext); `capture-equivalence`
**1,003 / 43 / moreAny 0** with **BOTH DIGESTS BIT-IDENTICAL**; `round895-grid` 8 profiles,
`added=0 removed=0` on every one.
**Ablation: six arms, one mistake each. a3 (regex instead of JSON) is the only arm with a
uniquely-its-own pin; a2 and a4 are indistinguishable from each other and are recorded as ONE
observable; a6 (the `isNodeNext` gate removed) is red NOWHERE — no output gate here can see it.
And arm a5 as first written was a DEAD ARM, not a blind pin: it cached in a `ProjectCompiler`
INSTANCE field and `Project` builds a fresh one per build, so it printed `0 RED` exactly as a
redundant guard would.** Residue queued as (CHK.36)-(CHK.38): the TS1479 interop family is not
implemented at all, `ModuleResolver` does not condition `exports` on the importer's format, and
`esModuleInterop` is gated on the global option and never on the two files' formats.

**`// @ts-ignore` AND `// @ts-expect-error` SUPPRESSED NOTHING, IN BOTH DIRECTIONS — AND THE
DEFECT THAT BLOCKED THE FIX WAS A SUPPRESSION WRITTEN AT AN *EMITTER* (2026-08-25,
(CHK.31)).** `Checker.getDiagnostics()` — the one funnel the CLI, the daemon and `-project`
all pass through — now applies tsc's `getDiagnosticsWithPrecedingDirectives` in tsc's order:
every diagnostic preceded by a directive is dropped and marks that directive USED, then every
`@ts-expect-error` that marked nothing is reported **TS2578**. The walk-up rule already
existed with exactly one caller; the general FILTER was what was missing, exactly as the queue
item said. **THE ITEM'S SIZE WAS WRONG IN THE HELPFUL DIRECTION**: a real two-arm 8-profile
grid (pre-(CHK.31) `Checker.kt` rebuilt into the class dir, positive-controlled by `javap`)
reads **`added=0 removed=0` on all eight**, and the whole corpus moved **one** baseline.
**THAT ONE BASELINE IS THE FINDING.** `isolatedModulesExportDeclarationType`'s `/test4.ts`
puts `@ts-expect-error` over an import of `./doesntexist`; pristine reports 0 errors there
(it emits TS2307 and the directive eats it) while WE emitted no TS2307 at all, because the
commonjs relative-import branch pre-suppressed its own emission. **A diagnostic a compiler
declines to EMIT turns every `@ts-expect-error` above it into a false TS2578** — both ad-hoc
pre-suppressions are deleted and suppression happens only where it can be counted.
**THE ONE REAL DEFECT WAS FOUND BY GREPPING THE PROFILES AND COULD NOT HAVE BEEN FOUND BY
RUNNING THEM**: `disableJsDiagnostics.ts` writes the prose comment ``// Only need to add
`// @ts-ignore` for a line once.``, and since both of tsc's directive regexes anchor at the
comment's OWN start, a backward `lastIndexOf("//")` read that sentence as a live directive.
The grid is green with and WITHOUT the fix (the falsely-silenced line carries no diagnostic);
only a tsgo differential separates them. The opener is now a string-aware FORWARD scan.
**Every one of the 25 pins was read out of `tools/tsgo-7.0.2/lib/tsc`, and two contradict the
obvious guess**: `@ts-ignoreXYZ` IS a directive (no trailing word boundary in either
reference) and a directive on an INNER line of a block comment is NOT one.
**GATES.** Suite **15,860 / 0 / 3** (+25 pins); `cost_gate.py` PASSES with `output.errors`
**46** and the standing stale-baseline drifts unmoved (`mapped.hits` +1.63%);
`huge_methods.py --fail-over 0` exit 0, **783** classes scanned (782 last round, so not
blind); `partition-equivalence` **EQUIVALENT 78/78** plus 4/4 on a purpose-built
directive-carrying project, `partition-gate` sensitivity arm **EQUIVALENT 76/76**;
`capture-equivalence` **1,003 / 43 / moreAny 0** with **BOTH DIGESTS BIT-IDENTICAL**.
**Ablation: 8 arms, one mistake each — a5 (partition scoping), a6 (backward `lastIndexOf`)
and a7 (block-comment inner line) each redden EXACTLY the pin that names them; a8's own pin
is NOT uniquely discriminating and is recorded as a shared guard rather than claimed.**
`// @ts-nocheck` is deliberately untouched — a FILE-level switch, not a line-level one.

**THE PROGRAM WAS PARSED *TWICE* AND BOTH COPIES WERE KEPT — LANGUAGE-SERVICE RETENTION
**264 -> 177 MB (-33%)** (2026-08-25, (INC.36)).** A ten-step subtraction ladder over
`liveAfterGc` attributed the 264 MB a whole-program `referencesAt` sweep holds:
`Project.sourceIndexes` **114.7 MB**, the process-global `CrawlParseCache` **103.0**,
`RealLibSnapshots` 2.6, JVM baseline + lib text + the 9,827 answers 43.7 — and
**`cached`/`captures`/`prepared`/`narrowed`/`recheck`/`lineMaps` 0.0 MB COMBINED**, so
every memo (INC.12)/(INC.14)/(INC.32)/(INC.40) added is free and **`close()` frees
nothing**. The two big rows are ONE program parsed twice at the same bytes under the same
`computeParserFlags`; the class histogram says it independently — **770,460 `Identifier`s**
against 856,962 nodes in one copy, i.e. CLAUDE.md's 44.5%, DOUBLED. **The fix deletes one
copy**: `Project.sourceIndexOf` indexes tokens around the compiler's own crawl tree
(`parsedSourceOrNull` -> `SourceIndex.around`), nothing writes to the process-global cache
so round 825's threading discipline is untouched, and a dirty buffer still parses privately
— which is the CORRECT answer, collected lazily once a build sees those bytes. Measured
after arm (2 processes): peak **177.0 / 176.4** vs before's **264.0 / 264.6 / 264.5 /
264.1**, `sourceIndexes` **-27.5 / -27.6** vs **-115.3 / -116.4 / -115.8 / -115.3**, every
other row unmoved, `Identifier` HALVED to 388,790, **9,827 hits unchanged**. **The
remaining 27.5 MB is NOT a tree** — ~18 MB of `SourceIndex`'s own token arrays (byte-identical
before and after) and ~10 MB of a second copy of the source TEXT, a named next lever
(`SourceFile.text`) left unlanded rather than taken after the gates had run. **FOUR of the
five gates are CONTROLS and only the ladder is evidence**, because the compiler path never
calls the new function. Suite **15,835 / 0 / 3** (+4: 3 pins, 1 control), zero corpus
baselines moved; `cost_gate.py` PASSES with the counter vector identical to last round
(`mapped.hits` at the standing +1.63%, not moved, not rebaselined); `huge_methods.py
--fail-over 0` exit 0 with over-limit **0** and **782** classes scanned (781 last round, so
not blind); `partition-equivalence` **EQUIVALENT 78/78**; `capture-equivalence` **1,003 / 43
/ moreAny 0** with **BOTH DIGESTS UNMOVED**. Also this round: **(INC.35) DECIDED BY THE
OWNER — option (b), per-buffer only**, closed as a decision, not an implementation.
`docs/perf/language-service-retention.md`.

**(INC.38) CLOSED, DOC-ONLY (2026-08-25): THE HOST-FACING RECOMMENDATION FOR THE 1.39x
RE-DERIVATION TAX IS NOW WRITTEN DOWN.** The code half shipped already as (INC.40) (a
retained checker collects the floor across `diagnosticsOf` queries, 2.25-2.30x). What
remained — the recommendation to ask for the whole open set in ONE call rather than one
file at a time — is now `docs/language-service.md` § 3a. Numbers traced to their actual
source (§ 14's six-buffer table, `2fa8a39f`, 2026-08-24, not the (INC.14) session note,
which does not carry them verbatim): the same 6-file set costs **321-342 ms** asked once
against **748-771 ms** asked per file. No Kotlin source touched; `jvmTest`/`cost_gate.py`/
`huge_methods.py` not run (nothing to gate).

**A BARE TYPE PARAMETER WAS READ AS A *FAILED* CONSTRAINT WHERE THE HONEST ANSWER IS *UNDECIDED*,
AND A WHOLE ALIAS BODY RENDERED `any` ON EVERY ORDINARY BUILD (2026-08-24, (INC.42)).** Three
lines reproduce it, with no partition and nothing to do with `Visitor`:
`export type R1<T extends Nd> = T | readonly Nd[];` then
`export type A1<X extends Nd> = (n: number) => R1<X>;` renders **`(n: number) => any`** where tsc
7.0.2 over its own LSP (`--lsp -stdio`, round 924's oracle — read out, never hand-written)
renders `(n: number) => R1<X>`. **A CONSTRAINT MATRIX ISOLATED THE PREDICATE, AND THAT IS THE
DURABLE HALF:** an **unconstrained** inner parameter is **always** correct, and **every** row
whose inner alias's parameter carries a constraint read `any` — **including where the two
constraints are IDENTICAL**. So the shape is not "a wrong constraint"; it is "a constraint at
all". **Cause:** B57.1b skips an alias substitution when an argument fails its parameter's
constraint and judged that with `checkTypeRelatedTo`, which has **no "TypeParam source via its
constraint" rule** ((INC.30), deliberately) — so the reference answered `errorType` and rendered
`any`, and where the body is a function type the `any` lands in the RETURN position, which is how
tsc's own `type Visitor<…> = (node: TIn) => VisitResult<TOut>` rendered `(node: TIn) => any`.
**Nothing here could see it**: the capture sweeps are DIFFERENTIALS, blind by construction to a
defect both arms share ((INC.28)'s law), and a wrong-but-plausible type attaches no diagnostic,
so no corpus baseline moves. Every one of the seven pins therefore asserts the rendered STRING.
**THE FIX IS LOCAL AND BOTH ITS GATES WERE FORCED BY MEASUREMENT, NEITHER GUESSED.** The argument
is judged against its **own** already-resolved constraint — **no new rule enters
`checkTypeRelatedToCore`, so (INC.30)'s termination argument is untouched**. `aliasBodyDisplayDepth`
confines it to `resolveTypeAliasBody`: **unconfined it reads `output.errors` 46 -> 48** on the
compiler profile (an overload-resolution defect at `checker.ts:2503` that a no-longer-`any`
`VisitResult<T>` exposes, plus a TS2322 at `watchPublic.ts:576`) — **two dashboard false positives
for 213 hovers is not a trade**. `aliasGuardIsRecursionBrake` keeps today's answer where this
guard is the recursion brake rather than a constraint check, and **the brake lives in the
ENCLOSING declaration, not the referenced one**: the first gate written asked about the referenced
alias alone and left the corpus **RED**, and a **flip census** named the mechanism — the only four
decisions the relaxation flips in `excessPropertyCheckIntersectionWithRecursiveType` are
`Length<I>` and `Prepend<any, I>`, two **non-recursive** aliases referenced from inside the
self-referential `BuildTree`. Ablations, one mistake at a time: a1 (relaxation disarmed) reddens
the three value pins and nothing else; a2 (the enclosing leg deleted) reddens the recursion pin
**and** the corpus baseline, so that leg is load-bearing with a failure uniquely its own. **One
pin is recorded NON-DISCRIMINATING** — it stayed green under a2, and the prediction behind it
(that `diagnose` never reaches `resolveTypeAliasBody`) is FALSE; named rather than claimed.
**GATES.** Suite **15,831 / 0 / 3** (+7 pins), **zero corpus baselines moved**; `cost_gate.py`
PASSES with `output.errors` **46** and `mapped.hits` at the standing +1.63% (not moved by this
round, still not rebaselined); `huge_methods.py --fail-over 0` exit 0. `capture-equivalence`
**1,003 / 43 / moreAny 0** and `capture-channel` **1,273 / 64 / moreAny 168** — both exactly
(INC.28)'s baselines, unmoved. **Both capture digests moved BY DESIGN** (full
`8385940838610938556 -> -7005799195003297838`, narrow `-7423700524621287041 ->
-1948231081793666447`): the signature of a fix that corrects an ORDINARY build, and the **third**
time this arc has had to re-record rather than read a moved digest as a regression.
**SAY IT PLAINLY: THE 213 ROWS THIS ROUND WAS AIMED AT ARE *NOT* CLOSED.** `Inc41ClassifyMain`
re-run reads **796 rows / 37 pairs / 213 GAINED-INFERENCE — UNCHANGED**. Read out of the
classifier's dump rather than assumed, those rows are **not hovers on `Visitor`**: they are carets
on `visitEachChild` / `visitFunctionBody` / `discardVisitor`, function names whose rendered
**OVERLOAD SET** carries a `Visitor` parameter — the **CHECKING** path, where both arms render an
unbound parameter. It is blocked three times over, each cost measured ((INC.28)'s two corpus FPs;
this round's two dashboard FPs; and B50.5's deliberate refusal to name a pure function type, so
even with both closed we would render `(node: TIn) => VisitResult<TOut>` where tsc renders
`Visitor`). **A relation-engine item ((INC.30)) plus an alias-NAMING one, not a display bug** —
re-queued as **(INC.43)**; `docs/inc41-replay-capture-classification.md` § 6a is the authority.
