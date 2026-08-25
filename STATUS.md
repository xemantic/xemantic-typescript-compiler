# Status

**CONTEXTUAL TYPING SUPPLIED AN *ARITY*, NOT A *TYPE* — EVERY CONTEXTUALLY-TYPED PARAMETER IN THIS
CHECKER WAS `any`, AND A HOVER ON ONE SAID `any` FOR EVERY CODEBASE (2026-08-25, (CHK.39)). The
item's own six-shape probe went **0 of 6 reported here** to **6 of 6**, matching
`tools/tsgo-7.0.2/lib/tsc` row for row.** `spineIanyFnExprEnter` decided TS7006 from the
contextual signature's parameter COUNT (B224), so a covered parameter went quiet and then stayed
`any` to every reader of a type — a false-NEGATIVE family the whole (CHK.30) arc sat on top of.
`pullContextualTypeAt` is tsc's `getContextualType`, **PULLED from the parent chain** because the
INV.4 spine arrives at a function body carrying no contextual ambient at all (round 911).

**THE STRUCTURAL FINDING: IT NEEDS TWO CALL SITES AND THE ABLATION PARTITIONS THEM EXACTLY.** A
statement nested in a function body is EMISSION-OWNED by the legacy walk — the spine's own anchor
runs `recordOnly` for it and truncates every diagnostic — so the obvious fix (the spine frame
alone) is correct and **completely invisible**. `checkFunctionBody` is the emitting half (7 pins),
`ctaFnBodyFrame` the capture half a hover reads (3 pins), 7 + 3 = the 10 that the whole-pull arm
reddens. B85.1a is load-bearing beside them: an OPTIONAL contextual parameter is `T | undefined`,
and the bare type was the round's one measured false positive (three profiles,
`findAllReferences.ts`'s `baseSymbol?: Symbol`).

**TWO MORE DEFECTS SURFACED BECAUSE THE TYPES DID.** (CHK.39b): an object-literal METHOD's body
was not walked by the assignability walker AT ALL in a `.ts` file — `walkFunctionBodiesInExpr`'s
`if (jsLike)` is a gate about `this`, and it was silently deciding whether the body is CHECKED.
And KIR: `lowerFunctionValueCall` emitted a direct `FunctionN.invoke`, but **TypeScript's function
assignability accepts a LOWER-arity function** (mitt registers a one-parameter wildcard handler
against a two-parameter type), so both mitt corpus tests died with a `ClassCastException` the
moment the callee stopped being `any`. It goes through `jsCallN` now.

**(CHK.39c) REFUSED — and the refusal is the round's most transferable result.** Giving the
PROPERTY-ACCESS family its last two contextual sources takes all four probes to full parity with
tsgo **and leaves all 8 dashboard profiles `added=0 removed=0`** — and costs **+15 false positives
on knip (66 -> 79)**, every one a parameter whose contextual type is a UNION that the body narrows
by ASSIGNMENT (`if (typeof x === 'function') x = x()`). That walker has no assignment/`typeof`
narrowing for a parameter; the grid is structurally blind to it. Re-queued as **(CHK.41)**, and
pinned as KNOWN GAP so the next round sees it rather than rediscovering the chain.

**GATES.** Suite **15,905 / 0 / 3** (+22 pins over 15,883), **zero corpus baselines moved**.
`output.errors` **46** throughout — a real gate here, and it is what caught the optional-parameter
FP. **`typeNode.bypassed` +31.26% REBASELINED**: ~17.7k per call site, essentially all of it
`cpaComputeArgCtxTypes` (the inference-aware resolver, which is what makes `xs.map(x => …)`
work); by round 716's price that is **~+21 ms, ~0.4% of a warm rebuild**, and the unspent lever is
a per-node memo, since the two sites ask the same question about the same node.
`huge_methods.py --fail-over 0` exit 0, **783** classes scanned. `partition-equivalence`
**EQUIVALENT 78/78**, floor **61 ms**. `capture-equivalence` **1,005 / 43 / moreAny 0** with
**both digests MOVED — the expected direction**, a parameter with a real type renders differently
and `definitions` rose 360,152 -> 360,336. 8-profile grid vs a rebuilt parent (positive control on
`javap`): `added=0 removed=0`. **knip 66 -> 66, every row identical**, with the BEFORE arm rebuilt
in the same session against the same re-fetched dependency set.

**A TYPE IMPORTED FROM A `node_modules` PACKAGE RESOLVED TO `any` — SILENTLY, ON EVERY REAL
PROJECT (2026-08-25, (CHK.30)). knip **156 -> 66** ERRORS, TS7006 **89 -> 1**, AND NO ROW
APPEARED THAT WAS NOT THERE BEFORE.** The queue entry called this "an object-literal method's
parameters are not contextually typed" and its diagnosis was WRONG: written out by hand, that
shape and five variants of it are silent on a pre-fix binary. knip's `PluginVisitorObject` is
`VisitorObject`, and `VisitorObject` comes from `'oxc-parser'`. **The mechanism**: the crawl
resolves a bare specifier correctly and the package's `.d.ts` really is in the program, but the
CHECKER re-derives which file a specifier names by string-matching it against the program's file
NAMES, and that corpus-era matcher cannot express a bare specifier — a package's
`types`/`main`/`exports` entry is not a string transformation of `pkg`. Fifteen lines reproduce
it: a `node_modules/tiny/index.d.ts` imported bare gives us **0 errors** where tsgo gives four.
`ParsedSource.moduleResolutions` now carries the crawl's own `(importer, specifier) -> file`
answers into the `Checker` as the last leg of all ten alias ladders.

**IT FAILS IN THE SILENT DIRECTION, WHICH IS WHY IT SURVIVED.** `any` is legal everywhere, so
nothing MOVED at the import — the only thing that surfaced was the false-positive SHADOW, a
TS7006 on every un-annotated callback parameter whose contextual type lived in the package. 89 of
knip's 156 rows, read as a contextual-typing family.

**A SECOND, SMALLER DEFECT LANDED WITH IT**: a concise-body arrow's OWN return annotation was not
a contextual type for its body in either walker, while a BLOCK body always had it at the `return`
edge — so `(): V => { return {…} }` was right and `(): V => ({…})` was not, and nobody noticed
because the two spellings are interchangeable to a reader.

**WHAT DID NOT WORK IS THE ROUND'S MOST TRANSFERABLE FINDING.** The first arrow fix silenced
every TS7006 asked for and the POSITIVE half of the probe showed it had typed NOTHING. Pushing on
that found something larger: **every contextually-typed parameter in this checker is still `any`
to the assignability walker**, back to the plain arrow ARGUMENT — `take((node) => { const bad:
string = node.kind; })` is silent here and TS2322 under tsc. Contextual typing here supplies an
ARITY (which is what decides TS7006) and never enters the parameter into the scope those walkers
read. Queued as **(CHK.39)** with its probe; four further unread contextual SOURCES are
**(CHK.40)**.

**GATES.** Suite **15,883 / 0 / 3** (+12 pins, exactly the two new classes), zero corpus
baselines moved. `cost_gate.py` PASSES, `output.errors` **46** — a real gate here, not a
control: the vector is the standing one plus `typeOfExpr.calls` **+0.18%** / `narrow.walks`
**+0.05%**, which are one extra annotation resolution per reached concise-body arrow, far inside
±2% and not rebaselined. `huge_methods.py --fail-over 0` exit 0, **783** classes scanned
(unchanged, correctly — no new class). `partition-equivalence` **EQUIVALENT, all 78**, floor
**57 ms**. `capture-equivalence` **1,003 / 43 / moreAny 0**, **both digests BIT-IDENTICAL**.
`round895-grid` 8 profiles `added=0 removed=0`, and a BEFORE/AFTER 8-profile grid against a
rebuilt parent (positive control: `javap` finds the new method 0 times before, 1 after) is
`added=0 removed=0` on all eight.

**Ablation, five arms, one mistake each.** a1 (the crawl-map leg never answers) reddens the four
package pins and leaves the negative control green; a3 (the implicit-any walker forgets the
annotation) reddens all five arrow pins; a4 and a5 redden the SAME single pin, so they are ONE
observable, not two. **a2 is `0 RED` across the whole 15,883-test suite** — the `node_modules`
walker legs the first cut also consulted are a REDUNDANT GUARD wherever the crawl can answer, so
they were REMOVED rather than shipped un-gateable.

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
