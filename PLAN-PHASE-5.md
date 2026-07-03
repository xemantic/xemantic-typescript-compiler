# PLAN-PHASE-5 — Real-world compilation, then performance

Owner directive (2026-07-02): *"I want this compiler to be able to fully compile any
TypeScript project. Then I want to also optimize the performance."*

This file is the **live queue** for Phase 17. `PLAN-PHASE-4.md` (Phase 16 and earlier)
is archived state — its "Known architectural blockers" section remains the reference
material for the M3 items below; do not work its queue.

## Phase 17 — Real-world compilation (M0–M5)

(Live session notes accumulate here, most recent first — same convention as Phase 16.)

**Round 384 (2026-07-03) — M0.1 + M0.3 landed; M0.2 baseline running.**
M0.1 (9b5bcd78): `--project` profiles + per-project TSVs in the bench script (see QUEUE
entry). M0.3 (f85cc438): parse-based specifier extraction — the parser now records
`SourceFile.moduleSpecifiers` at the real parse sites (static/dynamic/require/import-type
plus a new bounded leading-trivia scan for `/// <reference>` that honors directives after
a block-comment header, which `checkTripleSlashSelfReference`'s corpus-pinned scan does
not); `ProjectCompiler.extractSpecifiers` parses instead of regex-scanning, so
string-literal/comment/regex-literal text can no longer fabricate unresolved imports or
pull junk files into the program. 6 local tests pin the invariant (garbage never
extracted; deep-nested dynamic imports found; string-literal mention neither reaches
`unresolved` nor joins the program). Suite 8,848 / 0 / 3 (+6 local). Session ops notes:
a leftover bench run from round 383 was still executing at session start (its TSV row
landed at 23:08 — labels tell them apart); my first services verification run was killed
as polluted (its gradle step compiled pre-M0.3 code, then the M0.3 recompile swapped
class files under the running JVM — don't recompile while a bench JVM is up). M0.2
`--project all` relaunched clean on f85cc438; expected effect on compiler profile:
errors stay exactly 13,245 (extraction doesn't affect checking), unresolved drops from
120 to just node-builtin bare specifiers (env-legit until M1.3).

### Mission & strategy

Three strategic reads that shape everything below:

1. **Compliance and performance are the same road for the first 90%.** We run
   ~26 kLOC/s on corpus-shaped code but ~0.7 kLOC/s on tsc's own source — the 40× gap
   IS the false-positive paths (wasted relation checks, elaboration-chain construction,
   hundreds of per-file pin walkers). Killing FPs is the biggest available perf
   optimization, which is why "fully compile first, optimize second" is also the
   correct engineering order.
2. **The pin-walker strategy won Phase 16 and cannot win Phase 17.** Corpus-unique
   suppress-and-reemit walkers were rational for byte-exact baseline matching;
   arbitrary code never matches their gates. Phase 17's core is replacing pinned
   behavior with the real engine — with the green corpus as a permanent regression
   net, and pins **deleted** as the engine supersedes them (each deletion suite-gated,
   in the same commit as the superseding feature when practical).
3. **You cannot steer without a real-world metric.** The corpus count is saturated at
   100%; the Phase 17 dashboard is per-project FP counts, emit diffs, crash count, and
   throughput. `scripts/bench-compile-tsc.sh` + `bench/*.tsv` are the seed.

### Ground rules (delta vs Phase 16)

- The corpus suite stays a **hard zero-regression gate** forever: full suite green
  before every commit (`rm -rf build/test-results/jvmTest/binary && ./gradlew jvmTest`).
- The **success metric is the dashboard** (below), not the corpus count. STATUS.md
  tracks both.
- **Local corner-case tests per fix** (Phase 16 protocol step 2) still applies.
- **Never-crash doctrine**: any crash/hang/OOM on any input is a P0 — insert a repro
  item at the top of the queue.
- **Pins are deletable**: when an engine feature makes a corpus-unique walker
  redundant, delete the walker (suite-gated). Track net walker count in session notes.
- Everything else in CLAUDE.md § "Execution protocol" (promote-unblocker default,
  one-commit-per-substep, session notes, trim-on-write, guardrails) applies unchanged.

### Approvals granted by the owner (2026-07-02, "the last mile" → this plan)

- **Conformance-suite adoption** (test-generation change): extend
  `generateTypeScriptTests` to `tests/cases/conformance/<category>` subsets, staged
  per category, keeping the tsgo set-B filters (incl. `tsconfigInTestUsesRemovedFeature`).
- **Real-lib migration**: replace the embedded simplified lib with the real
  `typescript-repo/src/lib/*.d.ts` files (110 files, verified present offline).
- **Differential testing against real tsc** (network needed): install node +
  typescript@6.x when available; vendor real projects (zod etc.) as fixtures.
- Still user-gated: Gradle/dependency changes beyond these scopes; re-enabling the
  native target build config is pre-approved as part of M5.

### The dashboard

| Metric | Source | Phase 17 target |
|---|---|---|
| Corpus suite | jvmTest XMLs | green forever (8,842 / 0 / 3 at phase start) |
| Self-compile FPs (tsc src/compiler) | `bench/self-compile-tsc.tsv` | 13,245 → 0 (43 are env-legit until M1.3) |
| Project corpus FPs (services/server/…) | `bench/` TSVs (M0.1) | 0 |
| Conformance adoption | generated-test counts per category | all tsgo-relevant categories green |
| Crashes on any input | bench runs | 0 |
| Throughput (self-compile) | `bench/self-compile-tsc.tsv` | ≥ corpus-shaped ~26 kLOC/s (M5: numeric targets vs tsc/tsgo) |

### QUEUE — work top-to-bottom; promote unblockers per protocol

- [ ] **P0 — services-profile compile hang: exponential narrowing re-entry.** Found by
  the first M0.2 all-run (round 384): the services profile (compiler+jsTyping+services,
  251 files) burned 30+ CPU-minutes frozen inside ONE statement's
  `checkVarDeclAssignability` (bottom stack frames unchanged over 60 s; the compiler
  profile alone finishes in 5 min). Captured stack shape: `getNarrowedTypeForReference`
  → `narrowByAssertCall` → `resolvePropertyMethodDecl` → `getTypeOfExpression`
  (property-access/call) → `computeRawTypeOfPropertyAccess` → `getNarrowedTypeForReference`
  RE-ENTRY — a fresh flow walk per assert-call flow node with no memoization across the
  re-entry, stacked ~96 `narrowTypeFromFlow` frames deep. tsc's services sources are
  dense with `Debug.assert(...)` calls, so every flow-walk step over an assert re-resolves
  callee/arg types, which re-walk the flow below → superlinear-to-exponential. Fix
  direction (shares mechanism with M1.2): per-walk shared-flow-node memoization + a walk
  budget — tsc caches `sharedFlowNodes` per `getFlowTypeOfReference` invocation AND bails
  at `flowDepth === 2000` with container-scoped `flowAnalysisDisabled` (see the M1.2
  recon in the round-384 session note); also consider memoizing `narrowByAssertCall`'s
  callee resolution. Repro: `scripts/bench-compile-tsc.sh --project services` (hangs);
  minimize into a local test (N sequential assert-style calls + property accesses)
  asserting near-linear scaling. server/harness M0.2 baselines are blocked behind this
  (both include the services sources).

**M0 — Real-world measurement rig**

- [x] **M0.1 Project-corpus runner.** DONE (9b5bcd78): `--project` profiles in
  `bench-compile-tsc.sh` — compiler/tsc/jsTyping/deprecatedCompat/typingsInstallerCore/
  services/server/harness (each = named dir + transitive tsconfig-references closure,
  flattened) or `all`/comma-list; per-project TSVs (`self-compile-<name>.tsv`,
  compiler keeps the historical `self-compile-tsc.tsv`); per-project log subdirs +
  multi-project overview table.
- [x] **M0.2 Crash/robustness gate.** DONE (round 384) — the gate ran and did its job:
  5/8 profiles green with tightly-clustered baselines (compiler 13,245 err / 298 s;
  tsc-cli 13,247 / 297 s; jsTyping 13,301 / 304 s; deprecatedCompat 13,256 / 296 s;
  typingsInstallerCore 13,348 / 292 s — TS2305 dominates every profile at 8,752–8,837,
  then TS7006 ~1,555; rows in bench/*.tsv), zero exceptions/OOMs; **services HUNG → the
  P0 at the top of this queue** (killed after 30+ CPU-min frozen in one statement);
  server/harness baselines deferred behind the P0. Also caught an M0.1 bug: the src/tsc
  profile logged into the compiler profile's historical TSV — fixed (fabca29d,
  self-compile-tsc-cli.tsv).
- [x] **M0.3 Fix ProjectCompiler dynamic-import specifier extraction.** DONE
  (f85cc438): the parser records specifiers at the real parse sites into
  `SourceFile.moduleSpecifiers` (tsc's `SourceFile.imports`) — static import/export-from,
  import-equals require, dynamic `import()`/`require()` string-literal calls at any
  depth, `import("...")` types, triple-slash path/types from leading trivia;
  `extractSpecifiers` parses instead of regex-scanning. 6 local tests
  (ModuleSpecifierExtractionTest). Known FN: JSDoc `@type {import("x")}` in .js (no
  structural JSDoc model) — revisit with M4.

**M1 — Kill the systematic FP families**

- [ ] **M1.1 TS2305 export-star barrel following (−8,752; 66% of self-compile).** The
  named-import-vs-module-exports check does not follow `export * from` chains — every
  tsc file imports through the `_namespaces/ts.ts` pure re-export barrel. Find the
  TS2305 emit site(s) (grep `2305` in Checker.kt; CLAUDE.md documents several gated
  variants), add cycle-guarded, depth-bounded `export *` chain traversal to the
  export-name collection. FN-safe direction: if any `export *` target is unresolvable,
  treat the import as resolvable (emit nothing). Full-suite gate — the corpus has many
  TS2305 baselines with deliberate gates.
- [ ] **M1.2 TS2563 per-container CFA rule (−27, plus −20 TS2454 knock-ons).** Replace
  the per-FILE >2000-flow-node heuristic (B399, Checker init) with tsc's per-container
  flow-depth rule (or the closest faithful approximation), and make definite-assignment
  analysis respect the CFA-disabled bail (the TS2454s fire today AFTER the "too large"
  bail — tsc emits neither). Re-run the corpus node-count probe documented in the B399
  CLAUDE.md gotcha before changing threshold semantics (`largeControlFlowGraph` must
  still fire).
- [ ] **M1.3 `types` / `typeRoots` / `@types` resolution.** Implement the tsconfig
  `types` field and `node_modules/@types/*` acquisition in ProjectCompiler/
  ModuleResolver (this is a real-project requirement regardless of the benchmark).
  Offline caveat: real `@types/node` is not on disk — add an optional `--node-stub`
  flag to the bench script materializing a minimal ambient stub (`require`, `process`,
  `fs`/`path`/`perf_hooks`/`inspector` modules) so the self-compile count can reach 0;
  keep the no-stub run as the honest default until network provides real `@types/node`.
- [ ] **M1.4 Re-measure + strategic map.** Full project-corpus bench run; record the
  new per-family FP baseline in a session note; re-rank the remaining families (the
  ~3,100 TS7006/TS2339/TS2322/TS2345 checker-modeling tail) and insert the top 2–3 as
  concrete queue items here.

**M2 — Real-lib migration (staged; decompose further at start)**

- [ ] **M2.1 Lib graph loader.** Parse + bind the real `typescript-repo/src/lib/*.d.ts`
  selected per `target`/`lib` (the `/// <reference lib="…" />` DAG: lib.es2020 →
  es2019 + es2020.* pieces), as a process-wide immutable snapshot parsed ONCE and
  shared across programs (this snapshot is deliberately the seed of M5's incremental
  infra). Behind a CompilerOptions flag so corpus A/B comparison is possible.
- [ ] **M2.2 Corpus A/B and default flip.** Run the corpus with real libs; burn down
  the diff (baselines were produced by real-lib tsc, so divergence generally means one
  of our compensating hardcodes — fix by deletion). Flip the default when green.
- [ ] **M2.3 Unwind lib-divergence pins.** Grep anchors: `LIB_MIN_TARGET`,
  `LIB_MIN_TARGET_SOFT`, `BUILTIN_LIB_VALUE_INTERFACES`, `KNOWN_GLOBALS` (derive from
  the loaded libs), the hardcoded Date TS2740 message, hardcoded "and N more" counts,
  hardcoded overload chains copied from baselines (`WEAKSET_2769_CHAIN` etc.),
  `libFeatureAvailable`. Delete `BUILTIN_LIB_SOURCE` last.
- [ ] **M2.4 DOM libs as an opt-in set** (dom.generated.d.ts is 1 MB+ — measure the
  parse/bind cost; ties into the shared-snapshot design).

**M3 — Type-engine completion, conformance-driven (the long pole; each item
decomposes into a multi-session campaign — read PLAN-PHASE-4.md § "Known architectural
blockers" for accumulated detail before starting)**

- [ ] **M3.0 Conformance generator extension.** Extend `generateTypeScriptTests` with
  a per-category allowlist for `tests/cases/conformance/` (5,907 files; keep all tsgo
  set-B filters). Start with the categories matching M3.1 (types/typeParameters,
  types/typeRelationships, expressions/functions). Each category lands only when its
  failures are triaged into queue items — never leave a category half-red without notes.
- [ ] **M3.1 Generic instantiation + call-site inference** (remove the
  `hasUnresolvedTypeParams` relation bail; real type-argument inference incl.
  contextual return positions). This is the documented #1 engine blocker.
- [ ] **M3.2 Contextual typing engine** (parameters, returns, object/array literals,
  generic-context propagation — replaces `applyContextualParamTypesForArrow`-era
  special cases).
- [ ] **M3.3 Mapped / conditional / template-literal / indexed-access evaluation**
  (replace the AST-shape walkers; delete the superseded dedicated walkers and pins).
- [ ] **M3.4 Flow narrowing unified into identifier typing** (`getTypeOfIdentifier`
  consults the flow graph; retire the per-consumer narrowing carve-outs).
- [ ] **M3.5 Per-file scopes** (Blocker #3: stop merging all file locals into
  `globals`; per-file scope construction with explicit import visibility).

**M4 — Ecosystem completeness (interleaves with late M3)**

- [ ] **M4.1 Full nodenext resolution**: package.json `exports`/`imports` maps,
  symlink/realpath (pnpm layouts), `typesVersions`, package self-references.
- [ ] **M4.2 Real declaration emitter.** `.d.ts` output for arbitrary code (the corpus
  strips most `.d.ts` sections, so almost none exists today; `declaration: true` is
  table stakes for "any project"). Test bed: conformance decl baselines + self-compile
  d.ts diffing.
- [ ] **M4.3 JSX end-to-end** (`jsx: react-jsx`/`react`/`preserve` transforms on real
  React-shaped code).
- [ ] **M4.4 External sourcemaps** (`.js.map` files; inline maps exist).
- [ ] **M4.5 Decision point**: project references / composite / incremental scope
  (tsgo supports them; needed for large monorepos — decide build vs defer with owner).

**M5 — Performance (starts only when the project corpus compiles clean)**

- [ ] **M5.1 Profiling grid**: JFR/async-profiler over the project corpus (cold CLI,
  warm in-process via BenchMain, RSS); publish flamegraph findings in a session note
  before optimizing anything.
- [ ] **M5.2 Allocation discipline in the relation engine** (type interning /
  canonicalization — replace the documented fresh-mint caps like the
  `getPropertyTypeForRelation` depth bound with proper sharing).
- [ ] **M5.3 Cache effectiveness under scope contexts** (today `nodeTypes` is bypassed
  whenever any resolution context is active = recompute on every generic-heavy path).
- [ ] **M5.4 Parallel per-file checking** via the existing-but-unused `CheckerPool`
  (LinkStore side-tables already keep binder output immutable for this).
- [ ] **M5.5 Incremental compilation** (`.tsbuildinfo`-style reuse; the M2.1 shared
  lib snapshot is the first piece).
- [ ] **M5.6 Native target re-enable + tune** (linuxX64 was disabled in c7e3535f;
  native already wins <10 kLOC — fix the big-input inversion, likely GC/allocation).
- [ ] **M5.7 Numeric targets** (proposed; confirm with owner at M5 start): warm ≥ tsc
  throughput on 500k-LOC real code; cold CLI ≤ 1.5× tsc on medium projects; RSS ≤ tsc;
  stretch: approach tsgo on native.

### Offline asset inventory (verified 2026-07-02)

- `typescript-repo` object DB is complete (sparse checkout, full objects): any
  `src/**` path extractable via `git archive HEAD <path>`; `src/lib/` holds the 110
  real lib `.d.ts` files; `tests/cases/conformance/` holds 5,907 `.ts`/`.tsx` cases.
- Node/tsc/tsgo are NOT currently installed — differential testing (M0 optional) and
  real `@types/node` (M1.3) wait for network.
- The benchmark project cache lives under `build/bench/` (cheap to rebuild); results
  TSVs under `bench/` (gitignored, machine-specific).
