# PLAN-PHASE-5 — Self-compile the TypeScript compiler, then performance

Owner directive (2026-07-03, re-scoping the 2026-07-02 *"fully compile any TypeScript
project"*): **fully compile the TypeScript compiler itself, then optimize
performance.** "Any TypeScript project" is the post-v1 horizon.

**v1 definition of done:** all 8 tsc-source profiles (compiler / tsc-cli / jsTyping /
deprecatedCompat / typingsInstallerCore / services / server / harness) at **zero false
positives**, all files emitted, zero crashes/hangs/OOMs — verifiable fully offline.
Byte-correct emit diffing against real tsc is the network-gated follow-up (needs
node + typescript installed). Then M5 (performance) completes the directive. Items
that do not block v1 (M2.4, M3.0, M3.5, all of M4) are parked in § "Post-v1 backlog"
near the bottom of this file — the top-to-bottom loop skips them until v1 lands.

This file is the **live queue** for Phase 17. `docs/history/PLAN-PHASE-4.md` (Phase 16 and earlier)
is archived state — its "Known architectural blockers" section remains the reference
material for the M3 items below; do not work its queue.

## Phase 17 — Self-compile the TypeScript compiler (M0–M5)

(Live session notes accumulate here, most recent first — same convention as Phase 16.)

**Round 912 (2026-08-17) — (WARM.35): THE FOUR UNPRICED CANDIDATES FROM ROUND 903's HOT-PATH AUDIT ARE
**ALL REFUSED**, THE LARGEST AT **0.18%** AND ALL FOUR TOGETHER AT **0.303% (15.9 ms)** — UNDER THE
~17 ms FLOOR FOR *ONE* LOW-RISK CHANGE. THE ROUND'S REAL PRODUCT IS THAT **THE QUEUE'S OWN POPULATION
FOR THE LARGEST OF THEM WAS A TRANSCRIBED SOURCE COMMENT**, AND THAT **TWO OF THE FOUR FIXES ARE DEAD
BEFORE ARITHMETIC — ONE IS NOT EXPRESSIBLE IN KOTLIN AND ONE IS A SOUNDNESS BUG.**

Nothing was built and no amplifier was run: every refusal is population x a generous per-operation
ceiling, checked against round 896's divide-and-refuse, exactly as round 904 refused the boxed-key
family. `docs/perf/round912-candidate-census.md` is the record.

- **THE MEASUREMENT.** Throwaway counters at each site (reverted), printed after the last measured
  rebuild on the compiler profile (78 files, 46 errors), warm `BenchMain <proj> 6 2` and `6 3`,
  instrumented medians **5,065.7** and **5,170.8 ms**. Denominator per this file: **5,242.6 ms**, so
  1% = 52.4 ms and the floor is 0.324%.

  | candidate | population/rebuild | ceiling | % | verdict |
  |---|---:|---:|---:|---|
  | `mappedNodeTypeKey` key build | **25,987** keys of **110,780** calls | 9.36 ms | 0.179% | REFUSED (1.8x) |
  | `narrowTypeFromFlow` default-arg `NarrowFlowMemo` | **31,768** | 4.77 ms | 0.091% | REFUSED (3.6x) |
  | `collectTypeofGuardNames` &c `LinkedHashSet` | **22,798** | 1.48 ms | 0.028% | REFUSED (11.5x) |
  | `spineOsWithAmbient` / `spineTcDispatchWithAmbient` | **2,841** | 0.28 ms | 0.005% | KILLED BY READING (60x) |
  | **ALL FOUR TOGETHER** | | **15.9 ms** | **0.303%** | under the floor |

  To reach 17 ms they would need **654 / 535 / 746 / 5,983 ns per operation**, against a measured
  **15.09 ns** for a whole `HashMap` get that recursively hashes AND `equals` a 2.76-node AST subtree
  (round 903) and **8.53 ns** for a boxed `HashMap<Long,·>` probe (round 904).

- **THE CONTROLS, because a census that is only self-consistent has none.** Two independent processes
  agree **to the last digit on all 22 counters**, and `mappedNodeTypeKey calls = 110,780`
  **reproduces `docs/perf/cost-counters.txt`'s `typeNode.bypassed` exactly** — an external, previously
  recorded number the census never had access to.

- **THE FINDING WORTH MORE THAN ANY OF THE PRICES: A QUEUE POPULATION CAN BE A TRANSCRIBED SOURCE
  COMMENT.** The "~88 k/rebuild" attached to `mappedNodeTypeKey` traces to an in-source comment ("this
  is not the hot loop — 88k calls"), and it is wrong in **both directions at once**: the function is
  **CALLED 110,780 times** (the comment aged 26%) and **BUILDS A KEY 25,987 times** (**3.4x fewer**
  than the queue attributed to it, because **76.5%** of calls exit at the foreign-file gate before any
  key work). A number in a KDoc is not a measurement, and the quantity a fix would act on is not
  automatically the quantity the comment counts. Now in CLAUDE.md.

- **WHAT DID NOT WORK, AND WHY THAT IS THE ROUND'S SECOND PRODUCT.** (i) **Candidate 3's `inline` is
  NOT EXPRESSIBLE**: both `spineOsWithAmbient` and `spineTcDispatchWithAmbient` hand `block` to a
  **recursive, non-inline** callee (`spineOsApplyTps` / `spineTcApplyLevels`), so `inline` forces
  `noinline`, which re-materialises the lambda exactly as today — *a candidate can be dead on grounds
  of the LANGUAGE before any population is counted, and it is reading the CALLEE, not the wrapper,
  that shows it.* Its population is **2,841 calls**, one third of one percent of a single pass over
  the spine's 856,962 nodes, so the "measured-hot path" premise was false as well and **nothing had
  ever measured it** — `grep -rn` over `docs/` finds not one mention of any of the four names.
  (ii) **Candidate 4's obvious shared-memo fix is a SOUNDNESS bug**: `narrowTypeFromFlowCore` handles
  RE-ENTRANT outermost walks at `narrowLiveDepth == 0` by design, so a single shared instance would be
  cleared and overwritten by a re-entrant walk while the outer walk still depends on it — and a wrong
  serve there is a **wrong narrowed type**, undoing round 736's depth/height soundness argument from
  underneath. **34.2%** of memos already grow past 32 slots, so a shared memo's `clear()` is not
  obviously cheaper than the allocation it replaces (round 899: price a container swap NET). *The
  cheapest-looking of the four is the riskiest.*

- **AND THE ONE THING THE AUDIT NEVER NOTICED — still under the floor, so it is recorded rather than
  queued.** `mappedNodeTypeKey` performs **110,780 parent-chain climbs plus 110,780 `String`-keyed
  `fileResults` probes (~5.5 ms)** purely so that 76.5% of calls can answer "foreign file". That is
  comparable to the *named* mechanism and structurally required by the gate; the WHOLE function, at
  these generous rates, is ~15 ms — under the floor by itself. Also recorded: two of that function's
  three reject branches (`unindexed`, `no-owner`) fire **0** times on this profile, and the legacy
  `checkArithmeticInStatement` `IfStatement` arm runs **0** times (a bound on its frequency here,
  **not** deletion evidence — round 753).

- **THE HONEST UNCERTAINTY, stated because a ceiling is only as good as its rates.** Two of the
  per-operation rates are NOT sourced from a repo-measured constant and are set 3-10x above the
  nearest anchor on purpose: the `StringBuilder` + ~4.7 appends + `toString` for a 12.79-char key
  (**150 ns**), and `entries.sortedBy { }` over a **1.277**-entry map (**200 ns** — the census's own
  surprise is that a type-param scope is in force for 71.7% of built keys, so the sort really runs; it
  is just a 1-element `Collections.sort`). **An amplifier was judged not worth a build**: candidate 1
  would have to measure **654 ns per key**, ~43x a measured full recursive-hash `HashMap` probe, so
  the refusal survives an order of magnitude of rate error and an amplifier could only make the answer
  smaller. Three of the four are pure-allocation candidates, a genre round 801 (367,189 `String`
  allocations = **0 ms**) and round 893 (warm GC ~1.7% of wall) already price near zero — this is the
  fourth confirmation.

- **NEW REUSABLE CONSTANT, the allocation twin of round 904's ~1.7 M map-ops bar:** a pure-allocation
  candidate needs **> 113,000 allocations/rebuild at a generous 150 ns, or > 340,000 at a realistic
  50 ns**, to clear the ~17 ms floor. In CLAUDE.md, and it refuses most per-node allocation candidates
  by arithmetic.

- **GATES AND SUCCESSOR.** **No code changed** — the counters were reverted, so there is no suite run,
  no `cost_gate.py`, no `huge_methods.py` and no grid to report; the corpus count is unmoved. Per the
  WORK ORDER NOTE, the named successor is the **(API.\*)** arc — **(API.3b) go-to-definition** next,
  with **(API.3c)** (batch a whole file's spans into ONE build) as the item that makes the API
  practical for an editor. **The checker-side pool is now empty in the literal sense**: round 908
  closed the spine side and this round prices the audit residue, leaving nothing checker-side
  unpriced. The two remaining perf levers are artifact-level and **both are gated** — (ART.1) on the
  owner's release decision (the engineering exists; `native.yml` already builds Oracle + PGO), and
  (ART.2) on a **CRaC JDK that is no longer installed on this box** (Zulu 26 / OpenJDK 25, plus 17 and
  21 under `~/jdks`), so neither its `afterRestore` cwd fix nor a re-measurement can be compiled or
  verified locally.

**Round 911 (2026-08-17) — (API.3a): QUICK INFO LANDS, AND THE DESIGN ROUND 910 DECIDED BY *READING* IS
NOW CONFIRMED BY *MEASUREMENT* — **FIVE OF SIX POSITIONS ANSWER DIFFERENTLY POST-HOC**, AND THE
PREDICTION IN THE QUEUE ENTRY WAS WRONG IN THE **WORSE** DIRECTION. THE ROUND'S TECHNICAL PRODUCT IS THAT
**A PER-NODE HOOK ON THE SPINE SEES NONE OF THE CHECKING AMBIENT.**

- **THE MEASUREMENT, captured-during-walk vs asked-post-hoc on ONE `Checker` instance** (core
  `TypeCaptureMeasurementTest`, 9 pins): top-level annotated `const` **`string` / `string`**; body local
  shadowing `declare const collide: string` **`number` / `string`**; `typeof`-narrowed parameter
  **`string` / `any`**; parameter at its use **`number` / `any`**; arrow-body parameter **`string` /
  `any`**; class-method parameter **`number` / `any`**. The top-level row is the honest control —
  post-hoc is NOT wrong about everything, which is exactly why the failure is dangerous. **Round 910
  predicted the narrowed case would read `string | number` (narrowing merely lost); it reads `any`,
  because nothing durable binds a parameter at all** — and `any` is the ONE answer that is silent at
  every use site, so a post-hoc hover would have looked plausible and meant nothing. A wrong prediction
  in the direction of "worse than I thought" is the useful kind: it converts the design from a judgement
  into a measurement.

- **THE FINDING THAT MOVED THE HOOK, and the round's most reusable fact: THE SPINE'S ANCHORS
  INSTALL-AND-RESTORE THE CHECKING AMBIENT PER DISPATCH, SO AT AN ARBITRARY NODE THE CHECKER HOLDS NONE
  OF IT** — `currentLocalTypes` there is the FILE-level map. Measured: the first working version answered
  `bodyLocal=string` (the global), `narrowed=any`, `parameter=any`. **The position's scope is
  `ctaFrames.last()`; the ambient FIELDS are not.** The fix reproduces `ctaM3StmtAnchorCore`'s prologue
  verbatim (`classForThis / inFn / inAsync / inGen / fnTpDecls / fnTpScope / currentFlowGraph /
  currentCheckFileName` + the namespace-chain push) and then `withCtaFrameLocals(frame)`. The ablation
  drops exactly that one call and reddens **exactly the 8 predicted pins**, with the top-level control
  and all 96 other module pins green — and it is REACHED, not dead, since its answers revert to the
  pre-fix `string/any/any`.

- **A SECOND HOOK WAS BUILT AND DELETED RATHER THAN SHIPPED**: one in `checkTypeAssignabilityInStatements`'
  statement loop **never fired once** over declaration / arrow / method bodies — that walk is not on the
  spine path for these shapes. Removing it beat shipping a per-statement production read that bought
  nothing. Recorded in CLAUDE.md, because "the legacy assignability walk is where body-scoped ambient
  lives" is the natural guess and it is false; the cta frames are.

- **THREADING AND IDENTITY.** An explicit parameter on the `recheckOnly` model —
  `Project.quickInfoAt` -> `ProjectCompiler.build` -> `compileParsed` -> `compileParsedCore` ->
  `cpcCompileMultiFile` -> `cpcBindAndCheck` -> `Checker`, answering back through
  `CompilationResult.capturedTypes` -> `ProjectCompiler.Result.capturedTypes`. **Nothing on
  `CompilerOptions`** (compared for parse-flag equality, and ~160 bytecodes per `copy()` call site, round
  815) and **no process-global mode** (those owe the round-848 ledger; a capture request is DATA).
  The single-file arm is threaded too, so the API is not silently inert there. **Node identity is the RAW
  `(pos, end)` pair**: `-project` resolves the caret with `SourceIndex`, which owns round 910's token
  snap-back, so no span semantics enter the checker at all.

- **OFF IS FREE, AND IT IS GATED AS SUCH.** Production adds one null-valued instance-field read plus a
  perfectly-predicted branch per node — the shape `SpineDispatch.mode` has had since round 732 — placed
  ABOVE the dispatch probe's early return, with **the node itself as the argument** (round 900: a guard
  cannot protect a derived one), plus one branch per FILE in `checkSpine`'s loop. The per-file field is
  null unless a span was requested in that file. No counter, diagnostic or emit path is touched.

- **GATES: suite 14,522 -> 14,548 / 0 failures / 0 errors / 3 skipped = EXACTLY the 26 new pins** (17
  `-project`, 9 core), core 14,305 -> 14,314. **`cost_gate.py` +0.00% on all 20 counters** — the real
  gate for this round, not a control, since `Checker.kt` grew 198 lines on the hot walk.
  `huge_methods.py --fail-over 0` clean on core (736 classes, `Checker.<init>` at 5,802) and on the
  module (10). `spine_closure_audit.py` 46 handlers, all closures supersets — run even though the hook is
  a prologue line rather than a masked handler, so it CANNOT be skipped by `spineEnterMask`. Warning-clean.
  No wall A/B: the change is one predicted branch over 856,962 nodes, which is far under the +-1.0% band,
  so counters are the defensible instrument (CLAUDE.md's standing rule).

- **DEFERRED, and queued as (API.3b)/(API.3c):** go-to-definition, and exposing the BATCH form —
  `TypeCaptureRequest` already takes a set of spans, so "semantic info for file X" is one compile away
  from being one compile, which is what makes hover practical for an editor. `quickInfoAt` currently
  builds per call and deliberately does not cache that build (a capture build types nodes the checker had
  no reason to type, so its diagnostics are not reusable — pinned).

**Round 910 (2026-08-17) — (API.2) LANDED IN TWO HALVES, AND THE ROUND'S REAL PRODUCT IS TWO **MEASURED
FACTS ABOUT OUR AST SPANS** THAT MAKE THE OBVIOUS IMPLEMENTATION WRONG: **`Node.end` IS THE END OF THE
TOKEN *FOLLOWING* THE NODE, SO SIBLING SPANS OVERLAP AND `[pos, end)` IS NOT A CONTAINMENT TEST.** ALSO
DECIDED THIS ROUND, BY READING RATHER THAN PREFERENCE: (API.3) IS **POSITION-DIRECTED CAPTURE**, NOT A
POST-HOC QUERY.

- **WHAT LANDED.** (a) A public `LineMap` / `TextPosition` (both 1-based, `Diagnostic`'s convention) with
  `Project.positionAt` / `offsetAt`; these read through the overlay and **deliberately do NOT build**, so
  a host can convert coordinates on a dirty project for free (pinned: `lists == 0`, `reads == 1`).
  (b) `Project.nodeInfoAt` returning a **value-typed** `NodeInfo(kind, start, end, ancestorKinds)`, over
  an `internal nodeAt` / `SourceIndex`. **No AST, `Symbol` or `Type` is published** — (API.3)'s surface
  decision stays open. **53 new pins** (LineMap 15, ProjectPosition 11, NodeSpanSemantics 6, ProjectNodeAt
  21); module 30 -> 83.

- **FINDING 1, VERIFIED IN SOURCE AND EMPIRICALLY: `Node.end` OVERSHOOTS BY A TOKEN.**
  `Parser.getEnd() = scanner.getPos()` (`Parser.kt:746`), read after the parser's one-token lookahead, so
  in `const abc = 1;` the identifier `abc` reads **`[6,11)`** where its text is `[6,9)`, and statement 1
  reads `[0,18)` where its text ends at 14. **Sibling spans therefore OVERLAP**: a caret on the `=` tests
  as inside `abc`, a caret on `let` as inside the previous statement. `SourceFile` is exact only because
  EOF is zero-width. **FINDING 2, the mirror trap: `Node.pos` is tsc's `getStart()`, NOT tsc's `pos`** —
  `Scanner.scan` sets `tokenPos` AFTER `scanLeadingTrivia()` (`Scanner.kt:331-333`), so trivia is already
  skipped and leading comments hang off `leadingComments` BELOW the span; a routine ported from tsc that
  adds a `getStart()` skip double-skips past the node's own first token. Both in CLAUDE.md, both pinned by
  `NodeSpanSemanticsTest` rather than left as prose.

- **AND THE FIX THE QUEUE ENTRY IMPLIED IS *REFUTED*, WHICH IS THE ROUND'S SHARPEST RESULT.** Bounding a
  node's end by the NEXT SIBLING'S `pos` looks sufficient and fixes the caret-on-`let` case — but in
  `const abc = 1;` the initializer starts at **12** while `abc` ends at 11, so `min(11,12) = 11` and the
  `=` at offset 10 is STILL inside `abc`. **The `=` is covered by no child at all, so no arithmetic over
  child positions can ever see it.** The sound rule is `realEnd = the greatest TOKEN end strictly below
  node.end` — one extra `Scanner` pass per parse, binary-searched, cached beside the tree. The context-free
  re-scan can only SPLIT a contextual token (a regex scans as `/`,`ab`,`/`), which adds ends and preserves
  every real boundary; a merge would make a span come out short and report the PARENT — coarser, never
  wrong-sibling, because the bound is never too high.

- **BOUNDARY CONVENTION, stated because an ambiguous primitive cannot be layered on:** half-open, so
  `offset == start` is inside and `offset == end` is outside — matching `Diagnostic.start`/`length`,
  `Node.pos`/`end`, `LineMap` and tsc's `getTokenAtPosition`. Consequence pinned: `abc|` is NOT on `abc`;
  tsserver's touch preference (`includePrecedingTokenAtEndPosition`) belongs a layer ABOVE, so a host asks
  at `offset` then `offset - 1`. Building it in would make two adjacent nodes both contain the boundary.

- **TWO PINS WERE MEASURED VACUOUS AND SAID SO RATHER THAN SHIPPED (now in CLAUDE.md).** A `.tsx`/`.jsx`
  fixture CANNOT pin that `computeParserFlags` was consulted — `Parser.isJsxFile` keys off the file
  EXTENSION, and `needsJsxFlag` drives a diagnostic, not the grammar. Nor can a top-level `await`: our
  parser produces an `AwaitExpression` with `topLevelAwait = false` too (two tests failed on the first run
  proving it). **The one option-derived GRAMMAR difference is `forceJsx` on a plain `.js` file** — that is
  the pin that landed, with a negative control.

- **THE ONE CORE CHANGE, AND WHY IT IS ONE WORD: `computeParserFlags` `internal` -> public**
  (`TypeScriptCompiler.kt`). The implementer hit it, REFUSED to hand-roll the flags, and stopped — correctly:
  its own KDoc calls it *"the single source of truth … so a crawl-time parse is provably the parse the core
  would produce"*, which is precisely the guarantee an out-of-core parse needs, and a duplicate would be
  **drift no test in the consuming module could ever see** (no `-Xfriend-paths` between modules, so nothing
  could compare against it). The flags are not cosmetic: `topLevelAwait` is true for any
  ESNext/ES2022/NodeNext/Preserve/System project and `needsJsxFlag` for every `.tsx`. The KDoc now says not
  to tidy it back.

- **(API.3) DECIDED BY READING `getTypeOfIdentifier`, NOT BY PREFERENCE** (committed separately,
  `a966ad76`): it consults `currentLocalTypes` — its own comment says *"populated during TS2322 checking
  walk"* — then `currentParamBindingNames`, `currentCheckFileName`/`fileLocalTypeMaps`, `currentFileLocals`,
  the inference-namespace chain, and only THEN the node-keyed lookup. At rest `currentLocalTypes` is an
  empty `HashMap` (`:636`) and both `current*` fields are null, so a post-hoc query **skips the first five
  reads**; for a function-body local that does not merely lose narrowing, it can resolve to an unrelated
  same-named global (the `useCaseSensitiveFileNames` failure documented in that very function). So the
  design is **capture during the walk**, and the queue entry carries the spine-closure constraint (round
  888's mask) a next agent would otherwise lose a round to.

- **FOUND IN PASSING, QUEUED AS (BUG.1): the compiler disagrees with itself about a lone `\r`** —
  `Parser.computeLineStarts` breaks the line there, `Checker.lineStartsFor` counts `\n` only, so a SYNTAX
  diagnostic and a SEMANTIC one number the lines differently on classic-Mac text. `\n` and `\r\n` are
  identical under both, which is why no corpus baseline can see it.

- **GATES: suite 14,469 -> 14,522 / 0 failures / 0 errors / 3 skipped = EXACTLY the 53 new pins**, XML-parsed
  across all six modules, with core unchanged at 14,305 (the visibility change is behaviour-free).
  `cost_gate.py` **+0.00% on all 20 counters** — here a real control rather than a tautology, since core
  bytecode DID change (`internal` -> public removes JVM name mangling), and the gate proved live by running
  a real compile (46 errors / 78 files). `huge_methods.py --fail-over 0` clean on core (732 classes) AND on
  the module (9, up from 3 — the gate saw the new code). Build warning-clean. Ablation: `realEndOf`
  returning raw `node.end` reddens **exactly the 7 predicted pins**, restored by hand from a scratchpad copy
  (never `git checkout`, which would have destroyed the round's uncommitted work).

**Round 909 (2026-08-17) — (API.1): A NEW ARC, ON OWNER DIRECTIVE — THE **PROJECT / LANGUAGESERVICE
EMBEDDING API**, WHICH IS WHAT THE CHECKER-SIDE PERF POOL BEING EMPTY (round 908) MAKES ROOM FOR.
SLICE 1 LANDED: A NEW MODULE, A PUBLIC `Project` THAT ANSWERS DIAGNOSTICS AND ACCEPTS **IN-MEMORY
EDITS**, AND **30 PINS**. THE ROUND'S TWO REAL PRODUCTS BESIDES THE CODE ARE A **VACUOUS-FIXTURE
TRAP** AND THE FINDING THAT **(ART.1) IS STALE AS WRITTEN.**

- **THE DIRECTIVE.** The owner re-prioritised delivery of the Project and LanguageService APIs over
  the perf queue (ART.1 stays opportunistic). Answered scoping: a **Kotlin embedding API first**
  (LSP/tsserver layered later, not now), in a **new module**, first slice **Project + diagnostics +
  edits only** — no editor features, and deliberately no stub facade for them.

- **WHAT LANDED.** New module `xemantic-typescript-compiler-project` (jvm() only, `explicitApi()`,
  `api(project(":…-core"))`, mirroring `-cli`; sources in `commonMain` so a native target is later a
  build-file change and not a source move). `Project.open(projectPath, vfs = SystemVfs)` +
  `configPath` / `files` / `diagnostics()` / `diagnostics(fileName)` / `updateFile` / `deleteFile` /
  `close()`, plus an `internal OverlayVfs`. **The only pre-existing file touched is
  `settings.gradle.kts`** (2 insertions) — zero bytes of core, which is why `cost_gate.py` was not
  run: on this diff it is a tautology, not a control.

- **THE ARCHITECTURAL FACT THE API HAD TO BE SHAPED AROUND, STATED IN ITS OWN KDoc RATHER THAN HIDDEN:
  A QUERY ON A DIRTY PROJECT IS A *FULL REBUILD*, AND THAT IS THE COMPILER'S PROPERTY, NOT A SHORTCUT
  TAKEN HERE.** `ProjectCompiler.Result` is a flat value (paths, diagnostics, an import graph) that
  retains **no AST, no `BinderResult` and no `Checker`** — the checker's construction IS the
  compilation (`docs/ARCHITECTURE-RETHINK.md:850`). What makes a re-query cheap anyway is the
  process-global **CONTENT-keyed** `CrawlParseCache`, and that same keying is why an overlay edit
  **cannot be served a stale parse**: there is no mtime/size/stat anywhere in the decision (round
  871). **Do not add "incremental" reuse on top of `Project`; the seam does not exist yet.**
  Every build passes `noEmit = true` — a tool that opens a project to ask questions must never
  scatter JavaScript from unsaved buffers through the user's tree.

- **THE OVERLAY IS THREE MECHANISMS, NOT ONE, AND EACH IS PINNED SEPARATELY.** An added file must
  survive three questions asked by three different layers: `ModuleResolver` probes `exists` before
  `readText` (fail it -> TS2307 however readable the text); `ProjectCompiler.walk` asks `isDirectory`
  per entry and descends only on yes (fail it -> a file in an overlay-only directory is invisible);
  the glob discovers roots through `list` alone. `list` is SORTED deliberately — program order decides
  which file first touches a shared type node, so an unsorted union would make two builds of the same
  overlay state differ. **Ablation, one mistake at a time: dropping the overlay-children clause from
  `isDirectory` reddens exactly 2 pins and nothing else.** The fix/introduce pair is airtight by
  construction (the backing store holds the opposite text, so neither an always-stale nor an
  always-empty result satisfies both), and the caching pins assert read-count EQUALITY across a second
  query and GROWTH after an edit — both directions of the dirty flag.

- **THE VACUOUS-FIXTURE TRAP, VERIFIED IN SOURCE AND NOW IN CLAUDE.md — IT COST THREE TESTS THAT WERE
  GREEN WITH AN *EMPTY* DIAGNOSTIC LIST.** Two independent gates suppress TS2307: the unresolved-module
  region returns early on `binderResults.size <= 1 && !isMultiFileSource` (`Checker.kt:45409,45853`)
  and **the real libs bind through their own path and do not count**, so a two-file fixture whose
  second file IS the missing import reduces to ONE program file; and the relative-specifier leg
  demands `options.module in ES_MODULE_KINDS` (`:46098` — ES2015/2020/2022/ESNext/Preserve) with five
  resolution keys unset, so a tsconfig carrying only `target`/`strict` leaves `module` unset and every
  unresolved-import assertion is vacuous. **An import pin needs a negative control or it measures its
  own vacuity** — this round's does.

- **(ART.1) IS STALE AS WRITTEN, AND THE QUEUE ENTRY IS CORRECTED BELOW RATHER THAN WORKED.** It says
  "CI currently ships the Community Edition arm, which has no PGO at all". In fact `native.yml:60-72`
  **already builds Oracle GraalVM + PGO** through `scripts/build-native-pgo.sh`, verifies byte-identity
  against the JVM and uploads `xtsc-linux-x64`; `bench.yml` builds the Oracle **BASE** image per push
  **deliberately** (the PGO cycle is too slow to pay per push for a non-headline column). What actually
  remains is **attaching the binary to releases — the owner decision already tracked as (AOT.1)**, not
  a perf lever. It is also **unmeasurable on this box: no GraalVM is installed** (Zulu 26 /
  OpenJDK 25 only). A comment-only `bench.yml` correction found uncommitted in the tree was landed
  separately (`4c74eae4`) because its header and its own build step contradicted each other.

- **AN INSTRUMENT BLIND SPOT THE SIXTH MODULE CREATED, ALSO IN CLAUDE.md: `huge_methods.py` IS
  `-core`-ONLY BY DEFAULT, SO ITS GREEN RUN HERE WAS A CONTROL AND NOT A GATE.** The tell was a
  `classes scanned : 732` identical to round 907's; passing `--classes
  xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main` scans the new module's **3**
  classes, 0 over the limit. Round 853's law, one module over.

- **GATES: suite 14,439 -> 14,469 / 0 failures / 0 errors / 3 skipped = EXACTLY the 30 new pins**,
  counted by XML parse across all **six** modules (the glob is `*/build/test-results/jvmTest/*.xml`;
  the root-level form matches nothing post-split). `huge_methods.py --fail-over 0` clean on core (732
  classes) AND on the new module (3). Build warning-clean. `cost_gate.py` deliberately not run
  (tautology — see above); no wall A/B and nothing to A/B.

**Round 908 (2026-08-15) — (SPINE.1): THE LAST CHECKER-SIDE ITEM IS **REFUSED AND CLOSED**. 40% OF THE
WARM REBUILD LIVES IN SIX HANDLERS AND **91-100% OF IT IS THE TYPE SYSTEM DOING ITS JOB**. THE ONE ROW
THAT LOOKED LIKE A LEVER — 79.8 ms OF FRAME-AMBIENT INSTALL — HAS A **~8 ms** DELETABLE POPULATION AND
FAILS ITS OWN DIVISION BY **~20x**, BECAUSE **A TIMESTAMP IS AN OPTIMIZER BARRIER.**

Instrument only, two `Checker.kt` lines behind a call-site mode test.

- **(A) THE DENOMINATOR, RE-TAKEN — AND ROUND 847's TABLE WAS 60% STALE IN ms.** Eight probe-free warm
  process medians: 4,794 / 4,981 / 5,206 / 5,058 / 5,003 / 4,877 / 5,203 / 5,276 → **mean 5,050 ms**,
  range 9.6%. So 1% = 50.5 ms and the ~17 ms floor is **0.34%** here. All 22 instrumented rebuilds
  answered 78 files / 46 errors.

- **(B) THE FRESH WARM PER-HANDLER TABLE, AND ROUND 830's LAW DEMONSTRATED LIVE.** (Round 847's column
  is **STALE** — against 8,095 ms — and is quoted only as a share.)

  | handler | net ms today | % warm | *r847 ms (stale)* | *r847 %* |
  |---|---:|---:|---:|---:|
  | `spineCtaM3StatementAnchor` | **620** | **12.28%** | *853* | *10.54%* |
  | `cpaSpineLeave` | **584** | **11.56%** | *617* | *7.62%* |
  | `ccetSpineLeave` | **433** | **8.57%** | *876* | *10.82%* |
  | `spineIanyEnterNode` | **147** | **2.91%** | *171* | *2.11%* |
  | `ctaSpineEnter` | **129** | **2.55%** | *359* | *4.43%* |
  | `spineArithEnterNode` | **113** | **2.24%** | *153* | *1.89%* |
  | **the six** | **2,025** | **40.1%** | *3,029* | *37.4%* |

  Same six, still 62.6% of the probed spine (847: 63.0%) — but **the order swapped again**:
  `ccetSpineLeave` went #1 -> #3 (**−51% in ms**) while `cpaSpineLeave` **fell 5% in ms and ROSE
  7.62% -> 11.56% in share**. That is round 830 exactly: *a rising share is evidence the denominator
  shrank.* Partition check against the independent `spine` tier: 3,234 vs 3,104 = **104.2%**.

- **(C) ROUND 733's DEFLATION WAS *MEASURED*, NOT APPLIED — AND `SpineSections` RAN WARM FOR THE FIRST
  TIME** (rounds 733/799 read it cold; it was given a `BenchMain` tier this round).

  | probe | object | net ms | checking | bookkeeping |
  |---|---|---:|---:|---:|
  | `cta` A | `spineCtaM3StatementAnchor` | 640 | **94%** | 37 ms |
  | `cpa` P | `checkPropertyAccessInExpr` | 462 | **~100%** | <=0 |
  | `call` | `checkSingleCallExpressionTypes` | 381 | **93%** | 28.5 ms |
  | `spinesections` | both `…SpineLeave` handlers | 912 | **91.4%** | 80 ms |

  Round 733's split re-derived warm: passes' own work **91.4%**, ambient install+restore **8.7%**,
  outside-the-ambient **~0**, the three ancestor climbs **2.1% (19.6 ms)** — *the same 2.1% it read
  cold*. **Every frame pop and every restore is at or below one probe boundary**, and five of the
  eleven sections read NEGATIVE once their own boundary is subtracted.

- **(D) NOTHING CLEARS THE FLOOR.** Largest is the three ancestor climbs at **19.6 ms (0.39%)** —
  round 733's hypothesis #1, refused again (73/213/32 ns per call at depth 6/9, and a classifier is
  consulted once per node, so a memo can never answer its own query — round 875's law). Then the `cta`
  frame+ambient install at **16.0 ms**, load-bearing; and the `cta` eligibility gate at **14.4 ms**,
  where **round 888's mask already took 87% of its population** (915,543 -> 120,026 consultations).

- **(E) THE ROW THAT LOOKED LIKE A LEVER, AND THE NEW LAW THAT KILLED IT.** The two frame-ambient
  installs measure **79.8 ms = 1.58%** — the round-869 per-scope-copy shape, and the only thing in the
  region above 1%. A census (deterministic, identical in all four draws) says the "O(frames) rebuild"
  walks **2.91 frames** (max 8), **produces nothing on 91.4% of installs**, and the save copies **ZERO
  entries on 100%** of 147,572 installs: deletable population ≈ **8 ms**, half the floor. And the row
  fails round 896's divide-by-population test by **~20x** — 676 ns for ~16 `putfield`s and an empty
  copy — because **A TIMESTAMP IS AN OPTIMIZER BARRIER: bracketing a run of field save/restores forces
  stores that production coalesces away.** Every section probe over a field-shuffling region in this
  repo is inflated for the same reason.

- **(F) TWO CORRECTIONS A NEXT AGENT NEEDS, BOTH ALSO IN CLAUDE.md.** The **`dispatch` tier bypasses
  `spineEnterMask`**, so the per-handler table above prices the **pre-888 regime** (~73 ms on its
  total) and is structurally blind to the lever that region already banked. And today's `CtaSections`
  is not comparable to round 850's, for the same reason.

- **(G) WHAT THIS CLOSES.** (SPINE.1) was the last checker-side queue item and
  `reach-machinery.md` § 9's "remaining named place with more than 1% in it". It is now measured out.
  **That makes SIX consecutive priced refusals (rounds 903-908) and an EMPTY checker-side pool.** The
  named, already-measured levers that remain are **(ART.1)** the PGO'd native image (−21.2% check-only
  / −19.1% emit, 5/5 paired, byte-identical output) and **(ART.2)** CRaC (3.4x, blocked on one known
  cwd defect with a known fix) — both an order of magnitude larger than anything left in the checker.

- **(H) GATES.** Suite **14,437 -> 14,439 / 0 failures / 0 errors / 3 skipped** = exactly the 2 new
  pins, verified by XML parse across all four modules. `cost_gate.py` **+0.00% on every counter**.
  `huge_methods.py --fail-over 0`: clean. The two `Checker.kt` lines sit behind a **call-site** mode
  test — round 900's law in its sharper form, since `sec >= 0` is true in production and a callee
  guard could not have protected the three `size` reads.

**Round 907 (2026-08-15) — (WARM.34): THE COUNT QUESTION IS **REFUSED BY ITS OWN CENSUS**, AND THE
`lexLevelHasName` FAMILY IS **CLOSED ENTIRELY**. THE QUEUE'S PREMISE WAS WRONG IN THE SAME WAY ROUND
902's OWN LAW PREDICTS: **"THE O(depth) ASCENT" DESCRIBES THE *CHAIN* (3.69 STEPS), NOT THE *PROBES*
(1.54) — A CHAIN-STEP POPULATION IS NOT A PROBE POPULATION.**

Nothing was built. `docs/perf/lex-ascent-count-price.md`.

- **(A) THE CENSUS, WITH AN EXACT PARTITION CHECK.** Three processes identical to the last digit,
  reproduced across all three of the round's builds (nine runs); per-ascent probe counts sum to
  **870,231 = every real probe the three families make**. Per warm rebuild: **563,466 ascents**,
  **2,079,962 NameScope chain steps (3.69 each)**, **870,231 real map probes (1.544 per ascent)** —
  so **the whole probe stream at round 901's measured 36.6 ns is 31.85 ms = 0.602%**. That is the
  ceiling on *everything* in this family, and it is twice what round 902 projected.

- **(B) THE PREMISE WAS WRONG, AND ITS REFUTATION IS ROUND 902's LAW ONE STEP FURTHER ALONG ROUND
  902's OWN FAMILY.** The queue said the probes "arise from an O(depth) ascent that revisits the same
  big outer levels on every walk". That describes the **chain**; **58% of level visits are refused by
  the untrusted / non-head-fn rules or are hash-free EMPTY maps** (round 901's short-circuit finding),
  so 3.69 steps become 1.54 probes. *A chain-step population is not a probe population.*

- **(C) THE REDUNDANCY IS REAL AND DOES NOT HELP, WHICH IS THE ROUND'S SHARPEST RESULT.** **80.7% of
  the stream re-probes a `(level, name)` pair already asked** — 142,632 distinct pairs at 5.17 probes
  each. Three levers, all under the floor: **(i) the ascent memo the queue named** — 36.4% of ascents
  repeat a `(scope, name, family)` key, a fine hit rate, but **a repeat ascent performs 1.32 real
  probes and a memo probe replaces them with 1**, so the net is 66,095 probes = **2.42 ms** before
  charging 358,586 misses and inserts; with the memo **entirely free** it is 9.92 ms = 0.187%, and at
  the measured probe cost it is **−10.7 ms, a regression**. **(ii) a per-level memo** — the 21.8 ms
  the 80.7% implies — is refused **by construction**: *a cache keyed by the same name at the same
  granularity as the map it fronts IS that map.* **(iii) a per-file proof-of-absence filter**, the
  only operation cheaper than a probe, bounded by a measured superset at **<= 7.30 ms = 0.138%**.
  Union of (i) and (iii), both free and assumed disjoint: 0.338%; with their own costs, 0.257%.
  **To clear 0.31% a lever must delete more than half the stream at zero cost; the best deletes 25%.**

- **(D) THE FAMILY IS CLOSED, ALL THREE LEVERS, ACROSS THREE ROUNDS.** Container: round 901's filter
  **+0.26%** and round 902's parallel array **−0.19%**. Count: this round. And the closure is now
  GENERAL rather than per-lever — the whole stream is 0.60%, and any one-operation oracle that costs
  one probe recovers at most 0.21%. Recorded in passing: **`typeParamConstraintOf` is called 0 times
  per rebuild**, and two of the five families average **under one** real probe per ascent.

- **(E) THE ABLATION'S BLIND ARM IS THE ROUND'S SECOND FINDING.** Six arms, one mistake at a time,
  every red set unique — but **C1 was blind on the first pass**: a pin asserting `steps > calls`
  **summed over five families** stayed green against a census whose ascent count — *the denominator of
  the entire result* — had been inflated to the chain-step count, because one family (`has`) is 47% of
  the sum and carried it. Repaired by splitting the counter per family, which also produced the
  per-family table. **A PIN OVER A SUM IS A PIN OVER ITS LARGEST MEMBER.** Two further pins read zero
  before the fixture was repaired (round 849, in both directions).

- **(F) GATES — AND THE GRID IS A REAL GATE HERE.** The production shape DID change: the five ascent
  functions are split into an entry and a `…From` recursion, so a top-level query can be told from a
  chain step. Suite **14,430 -> 14,437 / 0 failures / 0 errors / 3 skipped** = exactly the 7 new pins,
  verified by XML parse across all four modules. `cost_gate.py` **+0.00% on all 20 counters** — a
  control. `huge_methods.py --fail-over 0`: **0 over the limit**, 732 classes. **8-PROFILE `--listAll`
  GRID: all eight `added=0 removed=0`**, zero exceptions, against round 905's committed captures.
  No wall A/B and nothing to A/B — nothing was built. The census folded into the existing
  `--mapCensus`, so no new flag and no three-place lockstep.

**Round 906 (2026-08-14) — (WARM.33): THE LARGEST ESTIMATED ITEM IN THE QUEUE IS **REFUSED, AND IT IS A
REGRESSION AT EVERY GEOMETRY** — AND ROUND 875 HAD THE **SIGN** WRONG, NOT THE MAGNITUDE: IT READ THE
*ASCENT'S* SCATTER ONTO THE *PROBE'S* SEQUENTIAL SWEEP. **THE CEILING FOR *ANY* MEMO-LAYOUT CHANGE IS
2.65-15.99 ms, BELOW THE FLOOR EVERYWHERE. THE WHOLE DIRECTION IS CLOSED.**

Priced with **no clock in the round at all**. `docs/perf/reach-memo-transposition-price.md`.

- **(A) ROUND 875'S OWN QUEUED INSTRUMENT CANNOT WORK, AND SAYING SO IS THE ROUND'S FIRST PRODUCT.**
  It queued "a transposed-layout **amplifier** arm on the memo probe". An amplifier repeats one probe
  `r` times under a timestamp pair — so from the second repetition the line is **L1-hot**, and it
  prices an L1 hit, which is exactly the cost the change exists to remove. (The sibling Rust compiler
  hit this precisely in its PG11: a memo removed 35.6% of repeat reads and moved the mechanism
  16.18% -> 15.44%, *because the repeat read was already in L1*.) **A LOCALITY CHANGE CANNOT BE
  AMPLIFIED.** So the instrument is a CENSUS of the exact access stream plus a set-associative LRU
  **model** — three layouts x five geometries — and its answer is a **miss-count delta**, i.e. a
  deterministic counter, not a measurement.

- **(B) THE CENSUS, WITH ITS FALSIFIERS EXACT.** `scripts/round906_instrument.py` hooks all **139**
  `memo[...]` access lines (43 entry probes, 2 interleaved, 43 ascent probes, 51 writes).
  **8,888,467 memo accesses per rebuild** — probe 1,960,176 / ascent 3,166,496 / write 3,761,795 —
  over a **38.4 MiB** footprint. The 43 classifiers' probes sum to **1,909,715 = `ReachCensus.calls`
  to the digit**, and the gap histogram's 2,816,334 steps plus the two interleaved classifiers'
  350,162 reproduce 3,166,496 exactly. Two processes identical to the last digit.

- **(C) ROUND 902'S LAW AGAIN, AND AGAIN IT MATTERED: THE MEAN 2.23 IS NOT THE QUANTITY.** **13.9% of
  nodes are consulted by nobody**; the 738,192 that are consulted average **2.655**, and the
  transposable population — second-and-later consultations — is **1,221,984 (62.3%)**.

- **(D) THE FINDING THAT REVERSES THE CANDIDATE: THE ASCENT IS NOT SCATTERED.** **42.2% of ascent
  steps go to `nodeId − 1`** and **89.8% stay within 64 ids** — i.e. *inside one cache line of
  today's layout*. And the spine walks in PREORDER, so each classifier's 1-byte array is swept
  **sequentially**: a line serves **~14.2** consultations plus the ascent steps within 64 ids, where a
  45-byte transposed row serves **~3.8**. **Layout A already answers 97.0% of accesses out of L1.**
  Round 875 § 5.2 read the ascent's scatter onto the probe's sequential sweep, and got the SIGN wrong.

- **(E) THE PRICE — THE CANDIDATE IS NEGATIVE EVERYWHERE, AND THE CEILING REFUSES THE WHOLE
  DIRECTION.** Access-stream ms, zeroing separated (it is bandwidth-bound, ~4 ms, identical in every
  layout):

  | geometry | A (today) | B (transposed) | C (padded row) | ceiling on ANY layout |
  |---|---:|---:|---:|---:|
  | box (32K/512K/16M) | 16.87 | **+3.90** | +23.88 | **2.65 ms = 0.05%** |
  | shrunk / mid / hostile | 23.2-27.0 | +13.0 / +15.7 / +21.0 | +22.4 / +33.7 / +38.0 | 9.0 / 10.4 / 12.8 ms |
  | flushed (4K/64K/512K) | 30.22 | +24.20 | +46.21 | **15.99 ms = 0.30%** |

  **Shrinking the cache — the only direction in which the model's optimism could have hidden a prize
  — makes the candidate WORSE.** Layout C is the candidate's own best form and is the worst arm.

- **(F) A CORRECTION TO THIS QUEUE'S OWN ENTRY, WHICH I WROTE.** The item promised the change "deletes
  36.9 MB/rebuild of allocated+zeroed `ByteArray`". It deletes **55 KB of array headers**: 43 arrays
  of *n* bytes and one array of 43*n* bytes **are the same bytes**. The figure was inherited from
  round 875 and restated without checking. *A queue entry is a claim, and it inherits its ancestors'
  errors silently.*

- **(G) ONE ADJACENT DIRECTION PRICED AND CLOSED ON THE WAY PAST.** Lazily allocating the 17
  classifiers consulted <1,000x per rebuild saves bandwidth worth **~2-3 ms** — below the floor before
  it starts — and is recorded precisely so nobody re-opens it as the ~57 ms a naive read of the model's
  `dram` column suggests.

- **(H) GATES.** Suite **14,424 -> 14,430 / 0 failures / 3 skipped** = exactly the 6 new pins.
  `cost_gate.py` **+0.00% on all 20 counters** — a control, not a verdict. `huge_methods.py
  --fail-over 0`: **0 over the limit**. Three single-mistake ablation arms, each with **reached-ness
  evidence** (round 902), distinct red sets, tree restored and pins re-run green. **No wall A/B and
  none possible** — the round contains no clock.

**Round 905 (2026-08-14) — (WARM.32): THE ITERATOR-ALLOCATION FAMILY — THE ONE CANDIDATE IMPORTED FROM
THE SIBLING RUST COMPILER, WHERE THE SAME MECHANISM MEASURED **−3.1%** — IS **REFUSED HERE AT 0.074%
(3.90 ms), BY 4.4x**. THE MECHANISM TRANSFERS AND THE **SHAPE** DOES NOT: 215 SITES ARE **495,305
CALLS OVER 2-ELEMENT LISTS**, AND **A COUNT OF SITES IS NOT A COUNT OF CALLS.**

Priced BEFORE the fix; the extraction landed, the fix did not. `docs/perf/iterator-allocation-price.md`.

- **(A) THE CANDIDATE, AND WHERE IT CAME FROM.** Kotlin's `Iterable.any`/`forEach` are `inline` but
  their bodies are `for (e in this)` on an `Iterable` receiver, so each call asks for a **heap
  iterator** and pays `hasNext`/`next` virtual dispatch per element. `../xemantic-rust-compiler` landed
  exactly this conversion (its PH3) for **−3.1% wall**, and recorded WHY a sampled share did not
  over-promise there: *an object handed to an iterator escapes by construction*, so escape analysis was
  never going to fold it. **The transfer audit flagged two populations here** — `forEachChild`'s 70
  `list.forEach(action)` calls (once per node, three sweeps, #5 in the warm leaf table at 1.40%) and
  145 `.any { it === child }` in the INV.4 edge classifiers.

- **(B) THE CENSUS REFUSED IT ON ITS OWN, BEFORE THE AMPLIFIER.** Two processes, identical to the last
  digit: **495,305 calls over 925,502 elements**. `forEachChild` list positions 275,477 calls / 547,102
  elements (mean **1.986**; 7.0% EMPTY, **52.4% SINGLETON**); `anyIdentical` 219,828 calls / 378,400
  visited (mean **1.721**, because it **hits 94.4%** of the time and a hit stops the scan). **17 ms
  over 495,305 calls is 34.3 ns per call — and round 904 measured a WHOLE boxed `HashMap<Long, ·>`
  probe at 8.53 ns.** No per-call mechanism this cheap can clear the floor at this population.

- **(C) THE MEASUREMENT, BOTH HALVES, FITTED PER ARM.** `r` = 8/24, ABBA, mirrored across two
  processes, 16 draws, leading draw dropped (rounds 869/891). Denominator **5,290 ms** (four process
  medians 5,237.9 / 5,304.6 / 5,287.3 / 5,325.4).

  | | arm | p(8) | p(24) | cost | boundary |
  |---|---|---:|---:|---:|---:|
  | `forEachChild` | A iterator | 25.574 | 19.914 | **17.084** | 67.9 ns |
  | | B indexed | 12.806 | 7.693 | **5.136** | 61.4 ns |
  | `anyIdentical` | A iterator | 13.254 | 8.251 | **5.750** | 60.0 ns |
  | | B indexed | 8.412 | 4.801 | **2.996** | 43.3 ns |

  **Premiums 11.95 ns and 2.75 ns → 3.29 + 0.60 = 3.90 ms = 0.074%.** Pooled 4.49 ms, most-generous
  4.58 ms; all refuse. **And the premium is an UPPER bound** — both arms fold into a trivial sink, the
  cheapest possible body, where iterator overhead is maximally exposed, while production's body is a
  megamorphic `action(e)`. Falsifiers: sinks **equal between arms** in all 16 draws, every sink an exact
  multiple of `r`, no arm flat.

- **(D) ROUND 904's BOUNDARY LAW IS SHARPENED BY ITS OWN SUCCESSOR — 23% BECOMES 76%, AND THE
  MECHANISM IS NAMED.** On `anyIdentical` the single-`r` form reads **4.85 ns against a true 2.75**.
  **A boundary is a property of the ARM, not of the harness**: it absorbs everything charged per CALL,
  and an iterator-constructing arm builds its iterator *there*, so the gap between two arms' boundaries
  is itself part of what is being measured. **Round 904's "both boundaries must land near ~90 ns" free
  check is WITHDRAWN** — these four read 67.9 / 61.4 / 60.0 / 43.3 and were all correct. The surviving
  check is arithmetic: `premium + (b_A − b_B)/r` reproduces the measured single-`r` `A − B` at BOTH `r`,
  closing to 0.01 ns on both halves.

- **(E) A COUNT OF SITES IS NOT A COUNT OF CALLS — 4-6x UNDER THE QUEUE's OWN PROJECTION.** 215 sites
  produced 495,305 calls, because most children are **direct `action(x)` positions** rather than list
  positions (IDENTIFIER alone is 44.5% of nodes and has no child list at all), and **only 219,828 of
  round 875's 3.32 M edge evaluations ever reach an `.any`**. Two ceilings corrected with it: the
  iterator is **4.4%** of `forEachChild`'s 1.40% leaf row, and `.any` is **1.4%** of round 875's 44 ms
  bound on the edge half.

- **(F) THE SIBLING IS NOT CONTRADICTED, WHICH IS THE POINT WORTH KEEPING.** 11.95 ns is real — 1.4x
  round 904's *whole* boxed-key premium — and the mechanism is identical. What differs is the shape of
  the population it runs over: a Rust parser's iterator chains run per token over `withIndex()`
  compositions allocating ~24 objects a call; ours are 495 k calls over 2-element lists. **A mechanism
  transfers between codebases; a price does not.**

- **(G) WHAT LANDED, SINCE THE FIX DID NOT.** The 215 sites now route through **`walkList` /
  `anyIdentical`** in `NodeWalk.kt`, bodies the verbatim lowering of what they replace — so the family
  has ONE HOME and the fix, had it cleared, would have been one line each. Kept for two reasons: a
  future agent cannot re-open it blind, and it shrank **`forEachChild`'s three (JIT.1) partitions from
  9,256 to 5,929 bytecodes (−36%)** on the compiler's traversal primitive, which is real headroom under
  the 8,000-byte cliff. **The strongest gate was not a run: the substitution was proved PURELY TEXTUAL
  by inverting it against the parent commit** — `Checker.kt` round-trips exactly at all 145 sites,
  `NodeWalk.kt` at 69/70, the 70th differing only in which of two equivalent spellings the inverter
  chose (`arguments` is nullable on `NewExpression`, non-null on `CallExpression`, and the forward
  substitution collapses both onto the null-checking helper). **Stated rather than hidden: the
  extraction itself is not priced by a warm A/B** — its expected effect is ~0 against a ±1.0% band, so
  it is gated on behaviour, not wall.

- **(H) GATES.** Suite **14,416 -> 14,424 / 0 failures / 0 errors / 3 skipped** = exactly the 8 new
  pins. `cost_gate.py` **+0.00% on all 18 counters**. `huge_methods.py --fail-over 0`: **0 over the
  limit**. **8-PROFILE `--listAll` GRID, all eight captured, no exception and no truncation** —
  `scripts/round905-grid.sh` enumerates profiles by the presence of a `tsconfig.json` and REFUSES below
  8, which is round 895's law (every committed "8-profile grid" before it was a one-profile grid). This
  gate is a real one here, not a control: the round rewrites the enumeration primitive every walk in the
  compiler goes through.

**Round 904 (2026-08-14) — (WARM.31): THE WHOLE BOXED-PRIMITIVE-KEY FAMILY IS **REFUSED** — 14 SITES,
**2,698,745 OPERATIONS PER REBUILD, A 6.58 ns PREMIUM, 17.7 ms = 0.334% FOR ALL OF THEM TOGETHER** AND
**0.064% FOR THE LARGEST SINGLE ONE**. AND THE 29.4 ms THAT RANKED IT WAS ONE DRAW OF A **4x-UNSTABLE**
NUMBER: THE SAME LEAF FAMILY READS **72.9 ms AND 19.0 ms IN ROUND 899's OWN TWO DUMPS, SAME BINARY.**

Priced BEFORE a line of fix; no production behaviour change. `docs/perf/boxed-primitive-key-price.md`.

- **(A) THE THRESHOLD WAS COMPUTED BEFORE THE CENSUS RAN, AND IT IS WHAT CLOSED THE FAMILY.** At a
  generous 10 ns premium against the ~17 ms floor, a single site needs **~1.7 M operations per
  rebuild**. **The whole check spine visits 856,962 nodes** — so *every per-node memo in the compiler
  is refused by arithmetic before any build*, and the question reduced to whether any site is driven
  by something other than node count. None is: the largest of the fourteen is **519,478 ops = 30% of
  the single-site threshold**.

- **(B) THE CENSUS, WITH BOTH CONTROLS HITTING EXACTLY.** `--boxedKeyCensus`, deterministic, two
  processes agreeing to the last digit. Top sites per rebuild: `importedSymbolGeneralCache` 519,478,
  `Relation.cache` 456,660, `relationComparisonStack` 444,446 (**max live 27**), the enum caches
  427,024, the ten spine `nodeId` memos 319,558; total **2,698,745** over 14 sites. **Site 6 is
  exactly 2x round 900's `risgCalls` (259,739) and site 11 is >= 2x round 896's `symAdds` (24,232)** —
  two independently-recorded populations reached by a different instrument, which is what says the
  hooks are on the right operations.

- **(C) THE `-128..127` DEFLATION DOES NOT APPLY, WHICH HAD TO BE CHECKED RATHER THAN ASSUMED.**
  `Integer.valueOf` caches small ints, so a small-key site boxes nothing new — but ids here run to
  millions and `nodeId`s to 275,470, and **only 0.41% of all keys fall in the cache**. The filter that
  could have refused half the family for free refuses none of it.

- **(D) TWO SITES ARE NOT REFUSED BY PRICE BUT BY *SHAPE*, WHICH IS THE PART A FUTURE "JUST MIGRATE
  THEM ALL" PROPOSAL NEEDS.** `Binder.lexicalScopes` is **ITERATED** (`for ((_, scope) in
  result.lexicalScopes)`), and `IntKeyMap`/`LongKeyMap` have no iterator *by design* — which is
  exactly what makes the rounds-754/776/778 order hazard a compile error rather than a review item,
  and is the same exemption CLAUDE.md already grants `Binder.nodeToSymbol`. And `relationComparisonStack`
  / `relation{Source,Target}Targets` / the two in-progress sentinels are **transient add/remove stacks
  with max live 27 / 18 / 5** — their successor is a linear array, not a map at all.

- **(E) THE PREMIUM, AND THE INSTRUMENT CORRECTION IT FORCED.** Two arms, two `r`, mirrored rotation,
  8 draws per arm: a boxed `HashMap<Long, ·>` probe is **8.53 ns**, a `LongKeyMap` probe **1.96 ns**,
  premium **6.58 ns [4.88-8.27]**. **A SINGLE-`r` `A − B` OVER-READS THIS BY UP TO 23%** — the standing
  advice that the timestamp boundary "cancels between the arms at equal `r`" is **false when the arms'
  boundaries differ**, and here they do (**88.0 vs 76.1 ns**), so `A − B` reads 8.07 at `r = 8` and
  7.07 at `r = 24` against a true 6.58. Fit `p(r) = cost + boundary/r` per ARM and difference the
  COSTS; the two implied boundaries are then a free check on the fit, and they reproduce both measured
  values to 0.01 ns. **Round 903's arms were already SLOPES at two `r`, so its 12.98 ns premium and its
  refusal are unaffected** — but the two rounds together are why this is now a law rather than a note.

- **(F) THE FINDING THAT REACHES BACK INTO THE RANKING ITSELF: LEAF INSTABILITY IS PER-*MECHANISM*,
  NOT PER-ROW.** Round 868 established that LEAF attribution is unstable across processes and it is
  always quoted about one frame (`HashMap.getNode` 9.66% vs 3.70%). Run over **round 899's own two
  dumps — same binary, same round** — the boxed-primitive leaf FAMILY reads **72.9 ms and 19.0 ms**,
  a **4x** disagreement, because C2 inlined `Integer.equals` into its callers in the second process.
  **So the 29.4 ms that put this on the candidate list was one draw of a number with a 4x spread**, and
  an aggregation that SUMS inlinable stdlib leaves inherits the instability at family scale. Minimum
  two processes for any family share; `scripts/boxed_key_leaves.py` carries the warning in its own
  docstring, and its stated purpose is to LOCATE an owner, never to price one.

- **(G) A THIRD REUSABLE CONSTANT, AND A BAND THAT MUST NOT BE INHERITED.** `docs/perf/warm-leaf-profile.md`
  § 33.8's "an `Integer`-keyed probe is ~15-30 ns" is **over by 1.8-3.5x**. This *strengthens* round
  900's refusal of candidate (1) — its 84.3 ns/probe was over by **10x**, not the 3-6x recorded — and
  it means a `LongKeyMap`/`IntKeyMap` probe is ~2 ns, now confirmed twice (rounds 903 and 904). A next
  agent can refuse a NEW boxed-key site for free: **population x 6.58 ns**.

- **(H) GATES.** Suite **14,409 -> 14,416 / 0 failures / 0 errors / 3 skipped** = exactly the 7 new
  pins. `cost_gate.py` **+0.00% on all 20 counters** — the expected control. `huge_methods.py
  --fail-over 0`: 0 over the limit. Build warning-clean. **The pins DISCRIMINATE**: ablating the
  `bkPush` on the relation stack reddens exactly one pin, the one that names it, on a binary that
  otherwise builds and passes — round 902's law applied prospectively, an arm shown REACHED rather
  than merely applied. **No wall A/B, for the thirteenth round running, and nothing to A/B.**

**Round 903 (2026-08-14) — (WARM.30): THE CANDIDATE THIS FILE AND CLAUDE.md BOTH CERTIFIED AS "THE ONE
JFR ROW WORTH BELIEVING" IS **REFUSED AT 0.085%, AND ITS ROW IS OVER BY 6.3x** — BECAUSE THE
PLAUSIBILITY ARGUMENT THAT ADMITTED IT APPEALED TO A MAGNITUDE NOBODY HAD MEASURED. THE RECURSION IS
REAL AND IT IS **TWO NODES DEEP**.**

Priced BEFORE a line of fix, one instrument, no production behaviour change.
`docs/perf/type-node-key-price.md`.

- **(A) THE CANDIDATE, AND THE STATUS IT HELD.** `state.nodeTypes` (`Checker.kt:166`) is a
  `HashMap<TypeNode, Type>` keyed by the AST **value**; every concrete `TypeNode` is a `data class`
  (139 of them in `Ast.kt`, **zero** `override fun equals`), so each probe is a recursive structural
  hash — round 471's hazard. JFR: **57.1 ms**, the largest single map owner. Rounds 894-899 refuted
  eight of nine JFR-ranked candidates by dividing the row by its own population; this one **passed**
  that division at 161 ns/op, and CLAUDE.md recorded it as *"what makes that row worth believing"*.
  **That sentence is the round's subject.** A recursive `hashCode` licenses any rate you like — the
  check appealed to the depth of the recursion, and the depth had never been measured.

- **(B) THE CENSUS DECIDED IT BEFORE THE AMPLIFIER RAN.** `--typeNodeKeyCensus`:
  `calls 287,062 / hits 116,999 / misses 59,283 / bypassed 110,780`, **unindexed keys 0**.
  **Probe-weighted mean key subtree: 2.7567 nodes (max 337); 73.6% of probes present a key of at most
  TWO nodes.** At the measured 5.47 ns/node, 161 ns/op needs a **29.4**-node mean — exactly the 25-40
  the design predicted the row would require, and **10.7x** what exists. *The row was refuted by its
  own arithmetic the moment its population was known.*

- **(C) THE AMPLIFIER, THREE ARMS, AND THE PRIZE MEASURED DIRECTLY RATHER THAN BY PROXY.** `r = 8`
  and `r = 24`, 16 draws per `r`, two mirrored batches (round 891 — one rotation is a batch, not a
  result). Every sink an exact multiple of `r`; no arm flat.

  | arm | what it probes | ns/op |
  |---|---|---:|
  | A | `nodeTypes[node]` — deep hash + bucket + deep equals | **15.09** |
  | B | the same probe against a `(file, nodeId)` `LongKeyMap` | **2.11** |
  | C | `isPerFileDependentRefNode` — the second owner | **12.88** |

  `A − B` is the deep key's premium, i.e. **the prize of the proposed fix**, not a proxy for it.
  The map GET is amplified rather than `node.hashCode()` because a data-class hash is a pure function
  of an immutable object and C2 may hoist it — **and the exact-multiple sink falsifier would still
  pass**, which is the one failure mode that falsifier cannot see.

- **(D) THE DECISION: REFUSED BY 3.7x.** `(A − B) x 354,131 = 4.60 ms = 0.085%` of the 5,429 ms
  denominator, against this arc's ~0.31% (~17 ms) floor; **2.5x under it even at the most generous
  single-draw bound** (6.77 ms = 0.125%). `A − B` is an *upper* bound — arm B's key is computed
  outside every timestamp pair — so the refusal is certain rather than marginal. **Round 896's
  sentinel-set candidate falls with it**: 118,566 of those ops at this premium is 1.54 ms, against
  the 3-5 ms it was refused at on a soundness ground that no longer has to be argued.

- **(E) THE CORRECTION TO THE JFR ATTRIBUTION, WHICH STANDS WHATEVER THE VERDICT — AND WHICH THE
  ROUND FOUND BEFORE MEASURING ANYTHING.** The row has **TWO owners**, and a leaf-frame profile
  cannot separate them: `cacheable` (`Checker.kt:104175`) calls `isPerFileDependentRefNode`
  (`Checker.kt:99546`), *itself a recursive subtree walk over the same subtree the hash walks*, on
  **every** call — cacheable or not. 12.88 ns x 287,062 = **3.70 ms (0.068%)**. Family total
  **5.34 + 3.70 = 9.04 ms against a 57.1 ms row = over by 6.3x**, the ninth consecutive JFR
  over-read and at the top of the recorded 2.1-21x band. *An instrument that had bracketed only the
  map probe would have charged the walk to the hash and reported roughly double the prize.*

- **(F) ROUND 902's LAW DOES **NOT** BITE HERE, AND THAT IS INFORMATION RATHER THAN AN EXCEPTION.**
  Probe-weighted and object-weighted key sizes differ by **6.6%** (2.7567 vs 2.5856), not by round
  902's 193x. The law says which weighting to *check*; it does not say which one always wins. A round
  that assumed the probe weighting must dominate would have been right about the method and wrong
  about this population.

- **(G) WHAT THE DEEP KEY ACTUALLY BUYS, MEASURED — AND WHY IT IS NOT A LICENCE TO RE-KEY.** The
  structural key's entire semantic effect is **130 shared probes of 176,282 (0.074%)**, read off the
  two arms' sink difference and exactly `130 x r` at both `r` in both batches. The unit fixture had
  *passed* an `A == B` sink equality that the real population refutes; it is now a `<=` bound with
  the difference reported as a number. **`PerFileTypeNameCacheCollisionTest` pins the case where that
  sharing is WRONG**, so 0.074% is the size of the benefit, not of the risk.

- **(H) ARM C NEARLY SHIPPED AS A ROUND-902 DEAD ARM.** `isPerFileDependentRefNode` opens with
  `if (multiFileModuleTypeNames.isEmpty() …) return false` — on a program without such names it
  prices a field read while reading, in every driver output, exactly like a subtree walk. A REACHED
  control was added *before* the number was quoted and reports **5**, so the arm is live. Round 902's
  lesson applied one round later, prospectively rather than in the post-mortem.

- **(I) GATES.** Suite **14,409 / 0 failures / 0 errors / 3 skipped** (+13 = exactly the new pins;
  baseline 14,396). `cost_gate.py` **+0.00% on all 18 counters** — the expected control for an
  instrument-only round (round 876). `huge_methods.py --fail-over 0`: **0 over the limit**
  (`getTypeFromTypeNodeCore` grew and stays under; `Checker.<init>` 5,753/8,000). Build
  warning-clean. **No wall A/B, for the twelfth round running, and nothing to A/B** — the round lands
  an instrument and a refusal.

---

### QUEUE — work top-to-bottom; promote unblockers per protocol

**WORK ORDER NOTE (restored 2026-08-14, round 903).** This section had been ARCHIVED out of the file
during a trim, and nothing noticed for ~15 rounds because rounds 886-902 were self-directing: each
session note named its own successor. **Round 902 ended with a CLOSURE and named none, so round 903
opened with no pool at all** and had to rebuild one by surveying `docs/perf/`. That is the failure
this section exists to prevent. **A round that refuses a candidate must leave at least one named
successor here, with its price and its next instrument** — a refusal is a successful round only if
the arc can continue from it.

**THE LIVE ARC IS (API.\*), ON OWNER DIRECTIVE (2026-08-17, round 909): DELIVER THE PROJECT AND
LANGUAGESERVICE EMBEDDING APIs.** It takes precedence over the (WARM.\*)/(SPINE.\*) perf items below,
which round 908 closed out anyway — the checker-side pool is empty. Shape decided by the owner: a
**Kotlin embedding API first** (LSP / tsserver protocol layered later, not now), in the new
`xemantic-typescript-compiler-project` module. The perf items stay below as the record; (ART.1) /
(ART.2) remain the only open perf work and (ART.1) has been corrected.

- [x] **(API.1) `Project`: open, diagnostics, in-memory edits — LANDED, round 909.** New module
  `xemantic-typescript-compiler-project` (jvm(), `explicitApi()`, `api(project(":…-core"))`);
  `Project.open` / `configPath` / `files` / `diagnostics()` / `diagnostics(file)` / `updateFile` /
  `deleteFile` / `close()` + `internal OverlayVfs`; 30 pins. **A query on a dirty project is a FULL
  rebuild and that is the compiler's property** — `ProjectCompiler.Result` retains no AST/binder/
  checker — so warmth comes from the CONTENT-keyed `CrawlParseCache` alone. Do not build "incremental"
  on it; the seam does not exist yet.

- [x] **(API.2) Position→node lookup — LANDED, round 910**, in two halves: a public `LineMap` /
  `TextPosition` + `Project.positionAt` / `offsetAt` (which read through the overlay and deliberately do
  NOT build, so a host can convert coordinates on a dirty project for free), and
  `Project.nodeInfoAt` (public, value-typed) over an `internal nodeAt` / `SourceIndex`. 53 pins.
  **The queue entry's "cheap and self-contained" was half wrong**: see the two span findings in the
  round-910 note and in CLAUDE.md — `Node.end` is the end of the FOLLOWING token, so `[pos,end)` is not
  a containment test, and the fix is a token snap-back rather than the sibling arithmetic this entry
  originally implied. **Unblocked by ONE word in core**: `computeParserFlags` is now public, because
  INV.1(e) ("the parse a crawl produces is provably the parse the core would produce") is exactly the
  guarantee an out-of-core parse needs, and duplicating it would be drift no test in the consuming
  module could see. Original entry, for the record:

  <details><summary>original (API.2) text</summary>

  **Position→node lookup, the unblocker EVERY editor feature needs.** There is no
  `getTouchingToken` equivalent anywhere in core: `computeLineStarts` is `private` to `Parser.kt:10119`
  and `positionToLineCharacter` is a private top-level fun (`TypeScriptCompiler.kt:6073`), both
  offset→line only, i.e. the direction diagnostics need and not the one an editor does. Needs: a
  public line/offset map, and a node-at-offset walk (`forEachChild`-driven, narrowest-enclosing, with
  the token-boundary rule tsc's `getTouchingPropertyName` uses). **Cheap and self-contained — it needs
  no checker state**, which is why it comes before quick-info.

  </details>

- [x] **(API.3a) QUICK INFO — LANDED, round 911, AND THE DESIGN BELOW IS NOW CONFIRMED BY MEASUREMENT
  RATHER THAN BY READING.** Captured-during-walk vs asked-post-hoc on ONE `Checker` instance: top-level
  annotated `const` **`string` / `string`** (the honest control — post-hoc is not wrong about
  everything), body local shadowing a global **`number` / `string`**, `typeof`-narrowed parameter
  **`string` / `any`**, parameter at its use **`number` / `any`**, arrow-body parameter **`string` /
  `any`**, class-method parameter **`number` / `any`**. **Five of six differ, and the prediction in this
  entry was wrong in the WORSE direction**: the narrowed case does not degrade to `string | number`
  (narrowing merely lost), it degrades to **`any`** — nothing durable binds a parameter at all — which is
  the one answer that is SILENT at every use site, so a post-hoc hover would have looked plausible and
  meant nothing. **THE HOOK'S REAL LESSON, now in CLAUDE.md: a per-node hook on the spine sees NONE of
  the checking ambient**, because the anchors install-and-restore it per dispatch — the position's scope
  is `ctaFrames.last()`, and the capture must reproduce `ctaM3StmtAnchorCore`'s prologue plus
  `withCtaFrameLocals(frame)`. Without that it answered `bodyLocal=string`, `narrowed=any`,
  `parameter=any`. Threaded as an explicit parameter on the `recheckOnly` model (nothing on
  `CompilerOptions`, no process-global mode); node identity is the RAW `(pos, end)` pair, so round 910's
  span semantics stay entirely in `-project`'s `SourceIndex`. **OFF IS FREE and gated as such**:
  `cost_gate.py` +0.00% on all 20 counters, the production cost being one null-valued field read and a
  predicted branch per node, with the NODE as the argument (round 900). Public surface stays value-typed:
  `QuickInfo` + `Project.quickInfoAt`.

- [ ] **(API.3b) Go-to-definition** — the other half, deferred from round 911. The capture mechanism now
  exists and this is the same shape one field over: record the resolved `Symbol`'s `declarations`
  (each a pos/end-bearing node) at the captured position instead of its type, and answer
  `DefinitionLocation(fileName, start, length)`. **Read (API.3a)'s ambient lesson first** — a symbol
  resolved without `withCtaFrameLocals` is the same wrong answer one indirection along.

- [ ] **(API.3c) Batch a whole file's spans into ONE build.** The core `TypeCaptureRequest` already
  takes a SET of spans and `Project.quickInfoAt` deliberately does not cache its build (a capture build
  types nodes the checker had no reason to type, so its diagnostics are not reusable — pinned). So
  "semantic info for file X" is already one compile away from being one compile; exposing it turns
  hover-per-keystroke from N builds into 1. **This is the item that makes the API practical for an
  editor** and it needs no new mechanism.

  <details><summary>the design decision, recorded round 910 and confirmed round 911</summary>

  **(API.3) Quick info + go-to-definition — THE DESIGN IS NOW DECIDED BY EVIDENCE: *POSITION-DIRECTED
  CAPTURE*, NOT A POST-HOC QUERY, BECAUSE THE CHECKER'S ANSWER TO "WHAT IS THE TYPE HERE" IS A FUNCTION
  OF WALK-SCOPED AMBIENT STATE AND A POST-HOC CALL WOULD BE SILENTLY WRONG FOR EXACTLY THE INTERESTING
  CASES (round 909, by reading `getTypeOfIdentifier`).** `Checker` does all its work in `init`, so the
  instance still HOLDS its tables afterwards and "hand the Checker back and call `getTypeOfExpression`"
  looks free. It is not: `getTypeOfIdentifier` (`Checker.kt:108777`) consults, IN ORDER,
  `currentLocalTypes` (its own comment: *"populated during TS2322 checking walk"*),
  `currentParamBindingNames`, `currentCheckFileName` -> `fileLocalTypeMaps`, `currentFileLocals`, the
  inference-namespace chain, and only THEN the node-keyed `lookupPerFileForNode`. At rest
  `currentLocalTypes` is an empty `HashMap` (`:636`) and the two `current*` file fields are null, so a
  post-hoc query **skips the first five reads** and falls through to globals. **For a
  FUNCTION-BODY LOCAL that does not merely lose narrowing — it can resolve to an unrelated same-named
  global**, which is the `useCaseSensitiveFileNames` failure documented in that very function
  (a destructured param resolving to another file's function, FP TS2345 x9). Two of the ambient reads
  are FILE-scoped and cheaply re-installable from outside; `currentLocalTypes` is
  STATEMENT-POSITION-scoped, built first-wins as the walk proceeds and deliberately leaking across
  blocks in statement order — **it cannot be reconstructed for an arbitrary position without
  re-walking to that position, which is the whole argument for capture.** So: hand the compiler the
  position(s) BEFORE the build and capture type+symbol at those nodes while the real ambient is
  installed. Correct by construction, and it **batches** — one build can capture every identifier in a
  file, so "semantic info for file X" is one compile rather than N. Cost, stated: a query is a compile
  (~5.2 s warm on tsc's own sources, far less on a normal project, repeats warm through
  `CrawlParseCache`); too slow per keystroke, fine for hover-on-demand.
  **IMPLEMENTATION CONSTRAINT A NEW AGENT WILL OTHERWISE LOSE A ROUND TO: a capture handler is a spine
  handler, so it must extend `SpineDispatch.enterClosure` or round 888's `spineEnterMask` means it is
  NEVER CALLED**, and `python3 scripts/spine_closure_audit.py` must be run after touching any
  `spine*EnterNode`. **PUBLIC SURFACE STAYS VALUE-TYPED** (`QuickInfo(kind, displayString, span,
  docs)`, `DefinitionLocation(fileName, start, length)`) — no AST, no `Symbol`, no `Type`.
  **THE FIRST STEP IS STILL A MEASUREMENT, NOT CODE:** pin the above by asking a post-init `Checker`
  for the type at three positions — a top-level `const`, a function-body local, and a guard-narrowed
  reference — and record which answer wrong. That experiment becomes the regression pin for the capture
  path.

  **THE STARTING FACTS** (unchanged, and they are what make capture cheap): everything an editor needs
  is `private` in `Checker.kt` and nothing hands back live state — `getTypeOfExpression` (`:108501`),
  `getTypeOfSymbol` (`:106667`) and `typeToString` (`:120389`) are all `private fun`, and
  `BinderResult.nodeToSymbol` is public but no `BinderResult` ever escapes a compile. Capture needs only
  an `internal` seam plus a handler; it publishes none of them.

  **THE THREE ALTERNATIVES, AND WHY THEY ARE NOT THE NEXT STEP.** (a) **post-hoc query-shaped** —
  narrow `Checker` entry points answering one question after `init`: **superseded by the finding above**,
  because it is silently wrong for body locals and narrowed references (the ONE hover case a user
  notices is `let`/`const` inside a function). Directed capture is (a)'s cheapness without its defect.
  (b) **snapshot-shaped** — return a `ProgramSnapshot` holding ASTs + binder output + the live
  `Checker`: **REJECTED for now, and the reason is this repo's own history** — it freezes as versioned
  API exactly the structures the perf arc keeps rewriting (rounds 889-908 changed packed-key hashing,
  container types and memo layouts, and moved maps onto `LongKeyMap`/`IntKeyMap`, which deliberately
  have NO iterator). Publishing them constrains the work that just delivered -10.5%. It also does not
  even solve the ambient problem: a snapshot hands back the same post-hoc trap. (c) **the full
  inversion** — a lazy, re-entrant checker (`docs/ARCHITECTURE-RETHINK.md:850` names it as the LSP
  prerequisite): **the right end state and the wrong next step**, the largest job in the repo. Do not
  let hover gate on it — and do not let it be "unblocked" by an API that has already published the
  internals it must change.

  </details>

- [ ] **(BUG.1) The compiler disagrees with itself about a lone `\r`** — `Parser.computeLineStarts`
  (`Parser.kt:10119`) breaks the line there, `Checker.lineStartsFor` (`Checker.kt:17641`) counts `\n`
  only, so on classic-Mac text a SYNTAX diagnostic numbers the lines and a SEMANTIC one reports line 1.
  Found round 910 while building the embedding API's line map. **Low impact and NOT urgent** (lone-`\r`
  files are practically extinct; `\r\n` and `\n` are identical under both, which is why no corpus
  baseline catches it) — but it is a genuine self-inconsistency and tsc breaks the line in both places.
  The fix is one loop in `lineStartsFor`; the gate is a hand-written pin, since the corpus cannot see it.
  Cost note before anyone "optimises" it: `lineStartsFor` is memoized per source and its loop is the
  cheap part.

- [ ] **(API.4) Completions.** Largest of the editor features (scope enumeration + member resolution).
  Under (API.3)'s capture design its shape is already implied and it is the case that stresses the
  design hardest: a completion request has NO node at the position (the user is mid-identifier, often
  right after a `.`), so the capture anchor must be the nearest enclosing node plus the scope in force
  there — which the spine already maintains as `spineCurrentScope` (INV.4(c)(i)) and which
  `lexLevelHasName`'s ascent already walks. Do not start before (API.3) lands the capture mechanism.

DENOMINATORS, so every % below converts. Last MEASURED warm rebuild **5,242.6 ms** (round 899, per-arm
sd 2.51%); JFR profile denominator **5,429 ms**; **1% = 54.3 ms**. Cross-round: 5,859 (pre-887) ->
5,424 (pre-895) -> 5,243 (HEAD) = **-10.5% over rounds 887-898**. **There has been no wall A/B for
twelve rounds**, and round 899 could resolve 1.88% in SIGN alone — so every item below is a fifth to
a half of what this box can judge and must be defended on counters plus a decomposition, never on a
median. `cost_gate.py` reads +0.00% by construction for all of them.

REFUSAL FLOOR: ~**0.31%** (~17 ms) for a LOW-risk change — round 897 refused there, 898 refused
MEDIUM at 0.13-0.20%, 900 refused at 0.07-0.14% and BUILT at 0.39%, 903 refused at 0.085%.

- [x] **(WARM.31) Residual boxed primitive map/set keys — REFUSED, round 904.** 14 sites,
  **2,698,745 ops/rebuild**, premium **6.58 ns**, so **17.7 ms = 0.334% for ALL of them together** and
  **0.064% for the largest single one**. `docs/perf/boxed-primitive-key-price.md`. **Do not re-open
  from a leaf profile**: the 29.4 ms that ranked it is one draw of a number that reads 72.9 and 19.0 ms
  across round 899's own two dumps of the same binary. A next agent can refuse a NEW boxed-key site
  for free — **population x 6.58 ns**, and a site needs ~1.7 M ops to clear the floor while the whole
  spine visits 856,962 nodes.

- [x] **(WARM.32) The iterator-allocation family — REFUSED, round 905.** 215 sites are **495,305
  calls over 925,502 elements** (mean list length **1.99** / **1.72**; 52.4% of `forEachChild`'s list
  positions are SINGLETON, and `anyIdentical` hits 94.4% so a hit stops the scan). Premiums **11.95 ns**
  and **2.75 ns** per call = **3.90 ms = 0.074%**, refused by 4.4x, and that is an UPPER bound (both
  arms fold into a trivial sink). `docs/perf/iterator-allocation-price.md`. **The census refuses it
  without the amplifier**: 17 ms over 495,305 calls needs 34.3 ns/call, where a WHOLE boxed
  `HashMap<Long, .>` probe is 8.53 ns (round 904). **The sibling project's -3.1% is not contradicted —
  the mechanism transfers and the PRICE does not**, because its population is per-token `withIndex()`
  chains and ours is 2-element lists. LANDED ANYWAY: the 215 sites now route through `walkList` /
  `anyIdentical` in `NodeWalk.kt` (one home, so it cannot be re-opened blind), which shrank
  `forEachChild`'s three (JIT.1) partitions **9,256 -> 5,929 bytecodes (-36%)**.

- [x] **(WARM.33) reach-machinery (b), transpose the 43 per-file memos — REFUSED, round 906, AND THE
  CANDIDATE IS A REGRESSION AT EVERY GEOMETRY.** `docs/perf/reach-memo-transposition-price.md`.
  **The whole memo-LAYOUT direction is closed**: the ceiling for ANY layout is **2.65-15.99 ms**,
  below the floor at every cache geometry, and shrinking the cache makes the candidate worse rather
  than better. **Round 875 had the SIGN wrong** — it read the ascent's scatter onto the probe's
  sequential sweep; measured, **42.2% of ascent steps go to `nodeId - 1`, 89.8% stay within 64 ids**,
  the spine walks in PREORDER so each 1-byte array is swept sequentially, and **layout A already
  answers 97.0% of accesses out of L1** (a line serves ~14.2 consultations against a transposed row's
  ~3.8). **Round 875's queued instrument could never have decided it**: an amplifier repeats one probe,
  so from the second repetition the line is L1-hot — *a locality change cannot be amplified*, and the
  round that priced it contains no clock at all, only a census plus a set-associative LRU model.
  Also corrected: this entry's own "deletes 36.9 MB/rebuild" deletes **55 KB of array headers** —
  43 arrays of n bytes and one of 43n are the same bytes. Adjacent direction closed with it: lazily
  allocating the 17 classifiers consulted <1,000x/rebuild is worth ~2-3 ms.

- [x] **(WARM.34) `lexLevelHasName`, the COUNT question — REFUSED by its own census, round 907, AND
  THE WHOLE FAMILY IS NOW CLOSED.** `docs/perf/lex-ascent-count-price.md`. **The queue's premise was
  wrong**: "an O(depth) ascent revisiting the big outer levels" describes the CHAIN (3.69 steps),
  not the PROBES (**1.544** per ascent), because 58% of level visits are refused by the untrusted /
  non-head-fn rules or are hash-free EMPTY maps — *a chain-step population is not a probe
  population*, round 902's law one step along its own family. **563,466 ascents / 870,231 real probes
  = 31.85 ms = 0.602% is the ceiling on EVERYTHING here.** The 80.7% redundancy is real and does not
  help: a repeat ascent performs **1.32** probes and a memo probe replaces them with **1**, so the
  queued ascent memo is **2.42 ms net, 9.92 ms even if free, and −10.7 ms at the measured probe
  cost — a regression**. A per-level memo is refused BY CONSTRUCTION (*a cache keyed by the same name
  at the same granularity as the map it fronts IS that map*), and a per-file absence filter is
  <= 7.30 ms. **Closure is now GENERAL, not per-lever: any one-operation oracle costing one probe
  recovers at most 0.21%.** Container closed by 901 (+0.26%) and 902 (−0.19%).

- [x] **(SPINE.1) The six spine handlers' frame bookkeeping — REFUSED AND CLOSED, round 908.**
  Denominator re-taken: **5,050 ms** (8 probe-free warm process medians), so 1% = 50.5 ms. The six
  are still 62.6% of the probed spine and **40.1% of the rebuild**, but round 733's deflation,
  MEASURED rather than applied (and with `SpineSections` run WARM for the first time), says the
  passes' own checking work is **91.4%** and every frame pop and restore is at or below one probe
  boundary — five of eleven sections read NEGATIVE once their boundary is subtracted. **Nothing
  clears the floor**: the three ancestor climbs are 19.6 ms (0.39%, refused again), the cta
  frame+ambient install 16.0 ms and load-bearing, the cta eligibility gate 14.4 ms with round 888's
  mask having already taken **87% of its population**. **The one row above 1% — 79.8 ms of
  frame-ambient install — has a ~8 ms deletable population** (the rebuild walks 2.91 frames, produces
  nothing on 91.4% of installs, and the save copies ZERO entries on 100% of 147,572) **and fails its
  own division by ~20x, because a timestamp is an OPTIMIZER BARRIER.** Round 847's per-handler ms are
  superseded — they were against 8,095 ms — and the order swapped again (`ccetSpineLeave` #1 -> #3,
  −51% in ms, while `cpaSpineLeave` fell 5% in ms and ROSE 7.62% -> 11.56% in share: round 830 live).
  **Caveat for any successor: the `dispatch` tier bypasses `spineEnterMask`, so that table prices the
  pre-888 regime and is blind to the lever the region already banked.**

- [x] **(WARM.35) The four round-903 hot-path candidates — ALL REFUSED, round 912, AND THE QUEUE'S OWN
  POPULATION FOR THE LARGEST OF THEM WAS A TRANSCRIBED SOURCE COMMENT.**
  `docs/perf/round912-candidate-census.md`. Priced by census plus round 896's divide-and-refuse —
  **no fix built, no amplifier needed**; both census processes agree to the last digit on all 22
  counters and `mappedNodeTypeKey calls = 110,780` reproduces `cost-counters.txt`'s
  `typeNode.bypassed` exactly, which is a second independent control. Against the stated 5,242.6 ms
  denominator (1% = 52.4 ms, the ~17 ms floor = 0.324%):
  **`mappedNodeTypeKey` key build — 25,987 keys of 110,780 calls = 9.36 ms = 0.179%, refused by
  1.8x**; **`narrowTypeFromFlow`'s default-arg `NarrowFlowMemo` — 31,768 = 4.77 ms = 0.091%, by
  3.6x**; **`collectTypeofGuardNames` &c `LinkedHashSet` — 22,798 = 1.48 ms = 0.028%, by 11.5x**;
  **`spineOsWithAmbient` / `spineTcDispatchWithAmbient` — 2,841 = 0.28 ms = 0.005%, KILLED BY READING,
  by 60x**. **ALL FOUR TOGETHER are 15.9 ms = 0.303%, still under the floor for ONE low-risk change.**
  To reach 17 ms they would need **654 / 535 / 746 / 5,983 ns per operation**, against a measured
  **15.09 ns** for a whole `HashMap` get that recursively hashes AND `equals` a 2.76-node AST subtree
  (round 903). **DO NOT RE-RAISE ANY OF THE FOUR.** Three mechanism findings outlive the prices:
  **(a)** the "~88 k/rebuild" this queue attached to `mappedNodeTypeKey` **was never a measurement** —
  it is a transcribed KDoc that is itself 26% stale (real call count **110,780**) applied to the wrong
  quantity (only **25,987**, 3.4x fewer, build a key; 76.5% exit at the foreign-file gate first), so
  the entry was wrong in both directions at once; **(b)** candidate 3's `inline` **is not expressible
  in Kotlin** — both wrappers hand `block` to a RECURSIVE non-inline callee, so `inline` forces
  `noinline`, which re-materialises the lambda, i.e. a candidate can be dead on grounds of the
  LANGUAGE before any population is counted, and reading the CALLEE rather than the wrapper is what
  shows it; **(c)** candidate 4's obvious shared-memo fix is a **SOUNDNESS bug, not merely a small
  prize** — `narrowTypeFromFlowCore` handles re-entrant walks at `narrowLiveDepth == 0` by design, so
  a shared instance would be cleared under a live outer walk and a wrong serve there is a WRONG
  NARROWED TYPE; and **34.2%** of memos outgrow 32 slots, so `clear()` is not obviously cheaper than
  the allocation (round 899: price a container swap NET). **NEW REUSABLE CONSTANT, the allocation twin
  of round 904's ~1.7 M map-ops bar: a pure-allocation candidate needs > 113,000 allocations/rebuild
  at a generous 150 ns, or > 340,000 at a realistic 50 ns, to clear the ~17 ms floor** — which refuses
  most per-node allocation candidates by arithmetic, the whole spine visiting 856,962 nodes.
  **AND THE ONE THING THE AUDIT NEVER NOTICED, still under the floor:** `mappedNodeTypeKey` spends
  **110,780 parent-chain climbs plus 110,780 `String`-keyed map probes (~5.5 ms)** so that 76.5% of
  calls can answer "foreign file" — comparable to the named mechanism, and structurally required by
  the gate; the WHOLE function at these generous rates is ~15 ms, still under the floor.

**SUCCESSOR, PER THE WORK ORDER NOTE ABOVE — a refusing round must name one.** With round 908 closing
the spine side and round 912 pricing the audit residue, **the checker-side pool is empty in the
literal sense: nothing checker-side is left unpriced.** **The successor is the (API.\*) arc, whose
next unchecked item is (API.3b) go-to-definition, with (API.3c) — batching a whole file's spans into
ONE build — as the item that makes the API practical for an editor.** The remaining PERF levers are
ARTIFACT-level and **both are gated, which a next agent must not rediscover**: (ART.1) is gated on the
owner's RELEASE decision and not on engineering (`native.yml` already builds Oracle + PGO and verifies
byte-identity), and (ART.2) is gated on a **CRaC JDK that is NO LONGER INSTALLED on this box**
(`/usr/lib/jvm` holds Zulu 26 and OpenJDK 25; `~/jdks` holds 17 and 21 — none of them a CRaC build), so
neither its `afterRestore` cwd fix nor a re-measurement can be compiled or verified locally.

**THE SEARCH STATE, AFTER SIX CONSECUTIVE REFUSALS (rounds 903-908), AMENDED ROUND 912 — READ THIS
BEFORE PICKING THE NEXT CANDIDATE. THE CHECKER-SIDE POOL IS NOW EMPTY, AND SINCE ROUND 912 IT IS EMPTY
OF UNPRICED CANDIDATES TOO.** 903 refused at 0.085%, 904 at 0.334% (14 sites TOGETHER), 905 at 0.074%, 906
measured a REGRESSION and closed a whole direction, 907 refused by census and closed a family. **Every
candidate ranked off the JFR profile in this arc has come in 2-21x over when measured — nine of ten
in the recorded scoreboard, six of six this session.** Meanwhile 61% of the warm rebuild is
unclassified residue, **no single JFR row is above 1.81%**, and the box cannot resolve below ~1.5%.
**That is what an exhausted search looks like.** It is not a failure — the compiler is -10.5% over
rounds 887-898 and warm xtsc is 2.05x tsc check-only — but a sixth single-row candidate should be
justified against this record rather than picked off a profile.

**THE MEASURED LEVERS THAT ARE *NOT* EXHAUSTED ARE AT THE ARTIFACT LEVEL, AND THEY ARE AN ORDER OF
MAGNITUDE LARGER THAN ANYTHING LEFT HERE.** Both are already measured, not speculative:

- [ ] **(ART.1) Ship the PGO'd native image. -21.2% check-only / -19.1% emit**, 5/5 paired in both
  modes, 46 diagnostics and all 78 emitted `.js` byte-identical (`docs/perf/aot-native-image.md`
  § 10). Needs Oracle GraalVM (`-graal` in SDKMAN; CE's `native-image --help` does not mention the
  word) and an `.iprof` trained on BOTH modes — a check-only-only profile leaves the
  Transformer/Emitter on static heuristics. This is the biggest single lever ever measured in this arc.
  **CORRECTED round 909 — the entry's premise ("CI currently ships the Community Edition arm, which
  has no PGO at all") IS STALE AND MUST NOT BE RE-INHERITED:** `native.yml:60-72` already builds
  **Oracle + PGO** via `scripts/build-native-pgo.sh`, verifies byte-identity against the JVM and
  uploads `xtsc-linux-x64`; `bench.yml` builds the Oracle **BASE** image per push deliberately (the
  PGO cycle is too slow to pay per push for a column that is not the headline). **So the engineering
  exists and what remains is the SHIPPING decision — attaching the binary to releases, already tracked
  as (AOT.1) and explicitly the owner's** (`native.yml:8`). Also **not measurable on the dev box: no
  GraalVM is installed there** (Zulu 26 / OpenJDK 25 only), so any re-measurement is a CI job or an
  install first.

- [ ] **(ART.2) CRaC — ~30 ms restore at FULL WARM SPEED** (6.8-7.3 s against 24-25 s cold, 3.4x,
  output byte-identical bar the `time:` line; `docs/perf/crac-checkpoint.md`). **Blocked on one known
  defect, not on the mechanism**: the restored process keeps the CHECKPOINT's working directory —
  round 873's bug one layer down — so a CRaC CLI must re-install the real cwd through
  `SystemVfs.workingDirectory` in an `afterRestore` hook, exactly as `CompileServer` already does per
  request. Unmeasured risk: the 340 MB image was page-cache-hot in every restore taken so far.
  **CORRECTED round 912 — AND THIS IS ALSO A LOCAL-TOOLING BLOCK, NOT ONLY A CODE ONE: the CRaC JDK
  IS NO LONGER INSTALLED ON THIS BOX.** `/usr/lib/jvm` holds Zulu 26 and OpenJDK 25 and `~/jdks` holds
  17 and 21 — none of them a CRaC build — so neither the `afterRestore` fix nor a re-measurement can
  be compiled or verified locally; it needs a Zulu CRaC install (or CI) first. Do not rediscover this
  by writing the hook and finding nothing to run it on.

**THE ROUND-903 HOT-PATH AUDIT'S FOUR UNPRICED CANDIDATES ARE NOW PRICED AND ALL FOUR ARE REFUSED —
see (WARM.35) above, and do not re-raise them from this block's former wording** (both copies of it
are collapsed into that entry; the record it stood on, "~88 k/rebuild", was a transcribed source
comment rather than a measurement).

**CLOSED IN ROUND 903, DO NOT RE-RAISE** (round 903, `docs/perf/type-node-key-price.md`): the
`nodeTypes` deep AST-value key, **refused at 0.085%** — its premium over a `(file, nodeId)`
`LongKeyMap` is 12.98 ns over 354,131 ops = 4.60 ms, and `A - B` is an UPPER bound. Round 896's
`nodeTypeResolutionInProgress` sentinel falls with it at 1.54 ms. The JFR row's other owner is
`isPerFileDependentRefNode` at 3.70 ms; family 9.04 ms against a 57.1 ms row.
