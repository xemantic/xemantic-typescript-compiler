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

**Round 902 (2026-08-12) — (WARM.29): ROUND 901's SUCCESSOR — THE PARALLEL-ARRAY CONTAINER IT
PRICED AT 0.41-0.47% — IS **REFUSED, AND IT IS A REGRESSION**: MEASURED, IT COSTS **+13.75 ns PER
PROBE = −10.1 ms = −0.19%**. THE ESTIMATE WAS NOT OPTIMISTIC, IT WAS COMPUTED OVER THE WRONG
POPULATION: **A LEVEL IS SCANNED ONCE PER *PROBE*, NOT ONCE PER *EXISTENCE*, AND THE TWO MEANS
DIFFER BY 193x** (1.51 own symbols against **290.94**).**

No production behaviour changed; the round lands an instrument and a refusal. `docs/perf/lex-level-scan-price.md`.

- **(A) THE ROUND DID WHAT ROUND 901 SAID TO DO, AND THAT IS WHAT KILLED THE CANDIDATE.** Round 901
  refused a filter at 0.26% and named a successor — "replace the per-scope `HashMap` with a
  parallel-array linear scan (map fallback above ~8), 794,251 probes at ~3-6 ns instead of 33-37,
  ~22-25 ms = 0.41-0.47%" — then explicitly did **not** build it, because that rate was ESTIMATED
  and *the next instrument is a third `--lexLevelAmp` arm, not a fix*. Two arms were added (an
  unconditional scan and the HYBRID actually proposed). **Had the round trusted the estimate it would
  have shipped a 0.19% regression behind a container change touching a binder-OUTPUT type.**

- **(B) A SCOPE POPULATION IS NOT A PROBE POPULATION — ROUND 890's LAW, ONE FAMILY OVER.** Round 901
  priced the scan off `lexBoundHistogram`, which counts each `LexicalScope` **once**: `15270 8381
  3748 …`, 46.7% empty, mean **1.51**. Counting the same scopes once **per real probe** gives `0
  166388 101041 62112 44319 35255 28750 22145 15900 261681`, mean **290.94**, **212.12 scan steps per
  probe**. The unresolved-names ascent walks outwards, so it reaches the big outer levels on every
  walk: **35.5% of probes land on levels averaging 815 symbols**, and those alone are 213.2 M of the
  214.6 M symbols a scan would traverse per rebuild. *The cost of a scan is weighted by the probes;
  the cost of an allocation is weighted by the scopes. Round 901 measured the second and priced the
  first.*

- **(C) THE MEASUREMENT, WITH ITS FALSIFIER AND ITS CROSS-ROUND CONTROL.** Four arms under one
  timestamp pair each, cyclically rotated; `r = 4` and `r = 16`, two runs each, ABBA at the run level.
  Warm slopes **MAP 6.00 / FILTER 0.96 / SCAN 709.2 / HYBRID 7.42 ns per rep**; at `r = 1`, where the
  boundary cancels between arms, **the unconditional scan is +1,046 ns against the map and the hybrid
  is +13.75 ns**. Sink an exact 4x between the two `r` (nothing elided), scan and hybrid sinks EQUAL
  to the map's at both (the arms answer the same question), hybrid branch split 475,910/261,681
  matching the histogram to the unit. Round 901's two arms reproduce — MAP warm slope 6.00 vs 6.4,
  FILTER 0.96 vs 1.17, map-minus-filter first probe 28.9 ns vs 33-37 and the JFR row's 36.6.

- **(D) AND NO THRESHOLD RESCUES IT, WHICH IS THE STRONGER HALF.** The hybrid's scanned levels
  average **2.86** entries and it still loses, for two reasons that are not tuning parameters: the
  35.5% that fall back pay the array load, the null test and the length test **and then the whole map
  probe**; and 2.86 elements is 2.86 `String` dereferences to scattered objects against one cached
  hash and one `Node`. **Even if the replacement were FREE, the whole <=8 population is 13.8-17.6 ms =
  0.25-0.32%** — straddling round 897's 0.31% refusal floor. The arithmetic is closed before an
  implementation is chosen. `lexLevelHasName` is now CLOSED as a *container* question: both its levers
  are measured and both refused, the filter at +0.26% and the container at **−0.19%**.

- **(E) THE ABLATION — 6 ARMS, ALL DISCRIMINATE, AND TWO WERE **DEAD**, NOT BLIND.** B1 (scan arm
  dropped) 2 pins, B2 (hybrid dropped) 2, B3 (scan stops one short) 1 — *a strict subset of B1,
  recorded not dressed up*, B4 (array built from `symbols` **plus** `existing`) 3 **unique**, B5
  (histogram de-duplicated per scope — round 901's population injected deliberately) 2 **unique**, B6
  (size recorded as 1) 2 **unique**. **B4 and B5 read ALL PINS GREEN on the first pass and neither was
  a blind pin: both edits changed nothing.** B5 guarded on `lexScopes.contains(l)`, always true
  because `lexLevelHasName` calls `lexScope(l)` two lines above `lexAmp`; B4 polluted the array with
  `existing` keys, but the SourceFile root is the only level carrying an `existing` table past the
  untrusted-owner rule and the fixture's root bound nothing, so no amplified scope had one.
  ***Round 855 needs the sharper form: `git diff --shortstat` proves the EDIT landed, never that it
  DOES anything — and in a driver's output a dead arm and a blind pin are the same line.***

- **(F) THREE PIN SPLITS IN ONE ROUND, ALL THE SAME LESSON.** The re-weighting pin first compared
  against `lexScopeBoundKeys` and failed in the full suite at 80 against 586 — the bound count is
  lib-dominated on any small fixture (round 898's A3 / round 901's A2 for the third time in four
  rounds). The size census then began as ONE method, so B5 and B6 failed the same lone assertion; and
  its consistency assertion, first written against the CALL count, fired under both defects and
  separated neither. **An assertion that fires for two causes separates neither.**

- **(G) GATES.** Suite **14,396 / 0 / 3** (+6 = exactly the new pins; baseline 14,390).
  `cost_gate.py` **+0.00% on all 20 counters** — the expected control for a change that adds census
  hooks and one always-null field. `huge_methods.py --fail-over 0`: **0 over the limit**, 714 classes.
  **8-PROFILE `--listAll` GRID, ALL EIGHT `added=0 removed=0`** (46 each, harness 94), cross-round
  against round 901's captures, identical recipe — a control, run anyway because the hooks sit on the
  path that decides TS2304. **No wall A/B for the eleventh round running**, and nothing to A/B.

**Round 901 (2026-08-12) — (WARM.28): ROUND 899's LAST UNREFUTED CANDIDATE, `lexLevelHasName`, IS
**REAL — TWO INDEPENDENT INSTRUMENTS AGREE ON ITS RATE TO 0.5%** — AND IS **REFUSED AT ~14 ms
(0.26%)**. THE CENSUS THAT PRICED IT FOUND THE ROW'S ACTUAL CAUSE: **32,693 `HashMap`s HOLDING 47,490
KEYS BETWEEN THEM, 46.7% OF THEM EMPTY** — WORTH ~0.45%, WHICH THE FILTER WOULD HAVE FORECLOSED.**

Priced BEFORE a line of fix, one instrument, no production behaviour change.
`docs/perf/lex-level-probe-price.md`.

- **(A) THE POPULATION IS EXACTLY WHAT ROUND 899 DERIVED, AND THE DERIVATION STILL COULD NOT HAVE
  DECIDED IT.** **1,024,959 calls** against the predicted ~1.0 M. But **271,684 of the 1,009,275
  probe-path calls cost NOTHING**: `HashMap.getNode` reads `table` BEFORE it hashes the key, and a
  `mutableMapOf()` that was never written keeps `table == null`, so an empty level answers with a
  null check and a return. *Round 898's law one level down — an operation that short-circuits before
  it does anything is not one of the operations you divide by.* A census counting probes alone would
  have manufactured 7-13 ms of prize out of free work.

- **(B) THE ROW SURVIVES ITS ARITHMETIC, AND THEN SURVIVES A SECOND INSTRUMENT.** 29.8 ms over
  **813,571 REAL probes = 36.6 ns**, inside the 20-50 ns band — the second row in this arc (after
  round 900's candidate (5)) to pass, against 8 of round 894's 9 and 1 of round 899's 6 that did not.
  But that band was measured on `perFileScope`, whose keys are file PATHS in a populated table, and
  the mean queried level holds **1.5** entries, so the prior does not transfer (round 789).
  `--lexLevelAmp` measures it: **MAP warm slope 6.4 ns, FILTER warm slope 1.17 ns**, and the two-arm
  delta extrapolates to **33-37 ns for the FIRST probe** — which is the one production performs.
  **Two independent instruments, agreeing to 0.5%.** Sink is an exact 4x between r=4 and r=16, so
  neither loop was elided. *The amplifier amplifies BOTH arms in one call because at equal `r` the
  ~90 ns boundary cancels BETWEEN them, which is the only way a first-probe rate is readable at all.*

- **(C) REFUSED AT ~12.6-15.8 ms (0.23-0.29%), FOR THREE REASONS IN ASCENDING WEIGHT.** 474,954
  refusable probes, ~2.3% false positives, minus a filter test on all 737,591 real-probe calls and a
  0.5 ms eager build. **(a)** below this arc's floor — round 897 refused a LOW-risk change at 0.31%
  gross, 898 refused MEDIUM at 0.13-0.20%, 900 refused at 0.07-0.14% and built at 0.39%. **(b)**
  `cost_gate.py` reads **+0.00% by construction** (it removes probes, not resolutions), so its only
  defence would be a wall A/B at **a seventh of what this box settles** — round 899 resolved 1.88% in
  SIGN alone. **(c) THE REAL ONE: a filter in front of a container is a commitment to the
  container.** Refusing 58% of those probes banks the smaller half and removes the justification for
  replacing the container, which is worth nearly twice as much.

- **(D) WHAT THE ROUND DID *NOT* HAVE TO WORRY ABOUT, RECORDED BECAUSE IT WAS THE FLAGGED RISK.** The
  filter would sit BELOW INV.4(c)(ii)'s three load-bearing rules and guard ONE map probe whose answer
  it proves — not the function's verdict — so the untrusted-level, non-head-fn and root-exclusion
  rules are untouched by construction; and `LexicalScope.symbols` has **exactly one writer in the
  repo** (`Binder.kt` `declareLexical`), so a mask built at the end of `bindLexicalScopes` cannot go
  stale. **The soundness argument was fine. The number was not.**

- **(E) THE SUCCESSOR, PRICED FROM THIS ROUND'S OWN CENSUS.** Bound scopes by own-symbol count:
  `15270 8381 3748 1907 1171 768 456 394 174 424` — **46.7% hold ZERO** (an allocated `LinkedHashMap`
  that never receives a key), 93.2% hold <=4, **98.7% hold <=8**, tail 424 scopes. Replacing the
  per-scope `HashMap` with a parallel-array linear scan (map fallback above ~8) serves **794,251 real
  probes** across the three families at ~3-6 ns instead of 33-37: **~22-25 ms = 0.41-0.47%**, which
  clears every floor this arc has used. One writer, five readers (one audit-only). **NOT built: the
  array scan's own rate is ESTIMATED, and the next instrument is a third `--lexLevelAmp` arm, not a
  fix** (CLAUDE.md's first law). The 32,693 deleted allocations are recorded UNPRICED — an allocation
  count is not a cost (round 801).

- **(F) THE ABLATION — 6 ARMS, ALL DISCRIMINATE, FOUR UNIQUELY, AND TWO WERE BLIND FIRST TIME.** A1
  (EMPTY/REAL collapsed) 1 pin, A2 (dedup dropped) 1 **unique**, A3 (`real` frozen false) 3
  **unique**, A4 (map arm dropped) 1, A5 (hook outside its guard) 1 **unique**, A6 (mask one bit off)
  2 **unique**. A1 and A4 are caught but NOT separated — strict subsets — stated, not dressed up
  (round 807). **A2 was blind because the FIXTURE could not express the invariant** (round 898's A3):
  `queried <= bound` is vacuous on a small file where the lib binding dominates the bound count, and
  the discriminating form is the STRICT inequality against the probes. **A4 was blind because ONE
  SHARED SINK CANNOT TELL A DROPPED ARM FROM A RUNNING ONE**; splitting it per arm catches it and
  also buys the assertion that matters most — the filter is a SUPERSET of the map, so it can never
  sink less, which IS the proof-of-absence property and is what A6 exists to test.

- **(G) AND THE DRIVER CALLED A BLIND ARM A COMPILE ERROR.** Gradle prints `N tests completed, M
  failed` **only when something failed**, so its ABSENCE is a green run — the first pass reported
  that as `compile error`, a phrase that reads like infrastructure and would have buried both blind
  arms. *A driver's verdict vocabulary needs a word for "the mistake landed and nothing noticed".*

- **(H) GATES.** Suite **14,390 / 0 / 3** (+11 = exactly the new pins; baseline 14,379).
  `cost_gate.py` **+0.00% on all 20 counters** — the expected control. `huge_methods.py --fail-over
  0`: **0 over the limit**, 714 classes. **8-PROFILE `--listAll` GRID, ALL EIGHT `added=0 removed=0`**
  (46 each, harness 94), cross-round against round 900's captures, identical recipe — a CONTROL this
  round, run anyway because the hooks sit on the path that decides TS2304. No wall A/B for the tenth
  round running, and nothing to A/B.

**Round 900 (2026-08-12) — (WARM.27): ROUND 899's CANDIDATE (5) IS THE **FIRST JFR ROW IN THIS
ARC WHOSE ARITHMETIC CONFIRMS IT** — 767,521 inserts at **28.1 ns** each — AND THE COUNTER THAT
CONFIRMED IT ALSO FOUND WHY THEY EXIST: **A PROBE ARGUMENT HAD BEEN MATERIALISING ROUND 801's LAZY
VIEWS ON EVERY PRODUCTION COMPILE FOR NINETY-NINE ROUNDS.** CANDIDATE (1) **REFUSED** at 84.3 ns per
`Integer`-keyed probe.**

Both populations measured before a line of fix (CLAUDE.md's first law + round 898's admission test).
`docs/perf/suffix-name-index.md`.

- **(A) CANDIDATE (5), AND THE BINARY WAS POSED OVER THE WRONG QUANTITY.** Round 899 said ~0.5-1.0 M
  `HashSet.add`s "is implausibly large for a set built once per file and entirely plausible for one
  rebuilt per QUERY", so one counter decides it. The counter says **767,521 names inserted across
  1,143 sets**, i.e. **21.6 ms / 767,521 = 28.1 ns per add** — exactly a `HashSet.add` with a cached
  `String` hash. **The row survives its own plausibility test, the only one of round 899's six and
  round 894's nine that has.** But the sets ARE built once each (`built` memoises); they are simply
  HUGE, mean **671**. *A count of builds does not bound the work a build does — for a 100%-insert
  row the deciding quantity is INSERTS, and the binary named neither outcome.*

- **(B) THE LEVER IS THE THIRD COUNTER: THE SUFFIXES OF ONE SCAN ARE NESTED.** 1,143 suffixes are cut
  from **1,220 cached scans holding 15,331 names in total** — a 50x gap, because each suffix
  re-inserts the same tail of a shared array. Membership is then a comparison against the scan's LAST
  occurrence (`e in suffix(lo) <=> max{k : names[k]==e} >= lo`), so ONE lazily-built index per scan
  answers all of them. LAST and not first is load-bearing: a name reassigned both before and after a
  closure is the shape the structure exists for, and first-wins inverts it.

- **(C) AND THE FIRST BUILD STILL READ `materialized 1143`, WHICH IS THE ROUND'S REAL FINDING.**
  `FrontEnd.addClosureCensus(reassigned.size.toLong())` — the guard `if (mode != ON) return` is
  INSIDE the function and **Kotlin evaluates arguments strictly**, so it never got the chance to run,
  and asking a lazy view its size materialises it. The (FRONT.2) probe was building all 1,143 hash
  sets on **every production compile with the probe OFF**. Round 801 created `SuffixNameSet` to stop
  exactly that and read its own census (`created 1143, materialized 1143`) as "every set is
  eventually asked", concluding the work MOVED. **The asker was the instrument.** Post-fix the same
  census reads **`created 1143, materialized 0, inserted 0`** — nothing in production ever asks one
  its size — with **192 of 1,220** scans ever questioned, so 84% now build nothing. *A probe that
  must be free when off is not free when off if its ARGUMENT does the work.*

- **(D) THE PRICE, WITH ITS DEFLATIONS STATED.** 767,521 `HashSet.add` -> **0**, replaced by 11,619
  `HashMap.put`: **755,902 inserts removed = ~21.2 ms = ~0.39%** of a 5,429 ms rebuild at the rate
  the row and the population agree on. That rate is DERIVED from the JFR row, so a residual
  attribution bias deflates it proportionally; and no wall A/B is attempted at 0.39%, which is a
  fifth of what round 899's 12/12 sign test could resolve. **The claim is the deterministic
  population** (identical across runs) **and the arithmetic on it.**

- **(E) CANDIDATE (1) REFUSED, ON ITS OWN ARITHMETIC.** `resolveImportedSymbolGeneral` is a genuine
  double probe, and the census says **259,739 calls, all top-level, 251,380 hits (96.8%), 511,119 map
  probes** — not the **0.7-1.5 M** the 21.9 ms `containsKey` row needs. That is **84.3 ns per
  `Integer`-keyed probe** against this arc's 15-30 ns reference: **over-read ~3x, round 898's law for
  the ninth time.** The removable half is **3.8-7.5 ms = 0.07-0.14%**, below round 897's 0.31%
  refusal and at/below round 898's 0.13-0.20%. And it is not the five lines it looks: the value is
  `Symbol?` and `containsKey` is precisely what separates "absent" from "cached null", so one probe
  needs a SENTINEL — a correctness-carrying construct for 0.07-0.14%. Recorded without a price: the
  default argument `visited = mutableSetOf()` allocates on all 259,739 calls and 251,380 never touch
  it, but *an allocation count is not a cost* (round 801).

- **(F) CANDIDATE (2) NOT STARTED** — `lexLevelHasName` was ranked below both and the budget went to
  them. It remains the top open item, unrefuted, MEDIUM risk, grid required.

- **(G) THE ABLATION — 5 ARMS, ALL DISCRIMINATE, ONE WAS BLIND AND IT IS ROUND 897's A1 VERBATIM.**
  A1 (first-occurrence index) 3 pins, A2 (`> lo`) 8 pins incl. 5 pre-existing semantic ones, A3
  (index not shared) 1 **after repair**, A4 (the eager `.size` restored) 1, A5 (`contains`
  re-materialises) 4. Four have a uniquely-their-own pin; **A1 is caught but NOT separated from A2**
  — its failures are a strict subset — stated rather than dressed up (round 807). **A3 read a clean
  sweep on the first pass because sharing changes no ANSWER, only how many times the work is done**,
  so every membership pin stayed green, correctly; the repair is a COUNTER pin
  (`indexesBuilt <= scansBuilt`, `indexEntries <= scanNames`, both true by construction).

- **(H) GATES.** Suite **14,379 / 0 / 3** (+7 = exactly the new pins; baseline 14,372).
  `cost_gate.py` **+0.00% on all 20 counters** — the expected control, and here the statement that a
  change deleting hash-set inserts touches no resolution. `huge_methods.py --fail-over 0`: **0 over
  the limit**. **8-PROFILE `--listAll` GRID, ALL EIGHT `added=0 removed=0`** (46 each, harness 94),
  cross-round against round 898's captures, identical recipe — a real gate this time, not a control.
  `--verifyFlowScan`: 1,220 scans compared, **0 diverged**.

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

- [ ] **(API.2) Position→node lookup, the unblocker EVERY editor feature needs.** There is no
  `getTouchingToken` equivalent anywhere in core: `computeLineStarts` is `private` to `Parser.kt:10119`
  and `positionToLineCharacter` is a private top-level fun (`TypeScriptCompiler.kt:6073`), both
  offset→line only, i.e. the direction diagnostics need and not the one an editor does. Needs: a
  public line/offset map, and a node-at-offset walk (`forEachChild`-driven, narrowest-enclosing, with
  the token-boundary rule tsc's `getTouchingPropertyName` uses). **Cheap and self-contained — it needs
  no checker state**, which is why it comes before quick-info.

- [ ] **(API.3) Quick info + go-to-definition — THE DESIGN IS NOW DECIDED BY EVIDENCE: *POSITION-DIRECTED
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

**THE SEARCH STATE, AFTER SIX CONSECUTIVE REFUSALS (rounds 903-908) — READ THIS BEFORE PICKING THE
NEXT CANDIDATE. THE CHECKER-SIDE POOL IS NOW EMPTY.** 903 refused at 0.085%, 904 at 0.334% (14 sites TOGETHER), 905 at 0.074%, 906
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

**AND THE UNPRICED CHECKER CANDIDATES FROM ROUND 903's HOT-PATH AUDIT**, each with a named mechanism
and no measurement — every one needs the build-free population step FIRST, and the record above says
to expect them to come in small: `mappedNodeTypeKey` (`Checker.kt:104288`) builds a `StringBuilder` +
two `sortedBy` copies + a `String` + a key object per bypassed resolution (~88 k/rebuild) purely to be
a map key; `collectTypeofGuardNames` &c allocate a `LinkedHashSet` unconditionally where the caller
only asks `isNotEmpty()`, then `Set.plus` copies both operands again (`Checker.kt:54541`-`54572`);
`spineOsWithAmbient` (`:18209`) and `spineTcDispatchWithAmbient` (`:66469`) are closure-taking and NOT
`inline` on a measured-hot path; `narrowTypeFromFlow`'s `memo: NarrowFlowMemo = NarrowFlowMemo()`
default allocates through the `$default` bridge at every call site that omits it.

**CLOSED THIS ROUND, DO NOT RE-RAISE** (round 903, `docs/perf/type-node-key-price.md`): the
`nodeTypes` deep AST-value key, **refused at 0.085%** — its premium over a `(file, nodeId)`
`LongKeyMap` is 12.98 ns over 354,131 ops = 4.60 ms, and `A - B` is an UPPER bound. Round 896's
`nodeTypeResolutionInProgress` sentinel falls with it at 1.54 ms. The JFR row's other owner is
`isPerFileDependentRefNode` at 3.70 ms; family 9.04 ms against a 57.1 ms row.

**ALSO RECORDED, UNPRICED, from the round-903 hot-path audit** (candidates with a named mechanism but
no measurement — each needs the build-free population step first): `mappedNodeTypeKey`
(`Checker.kt:104288`) builds a `StringBuilder` + two `sortedBy` copies + a `String` + a key object per
bypassed resolution (~88 k/rebuild) purely to be a map key; `collectTypeofGuardNames` &c allocate a
`LinkedHashSet` unconditionally where the caller only asks `isNotEmpty()`, then `Set.plus` copies both
operands again (`Checker.kt:54541`-`54572`); `spineOsWithAmbient` (`:18209`) and
`spineTcDispatchWithAmbient` (`:66469`) are closure-taking and NOT `inline` on a measured-hot path;
`narrowTypeFromFlow`'s `memo: NarrowFlowMemo = NarrowFlowMemo()` default allocates through the
`$default` bridge at every call site that omits it.
