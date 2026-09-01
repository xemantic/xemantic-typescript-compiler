# Incremental re-checking: ours against tsgo's

Measured 2026-09-01, one box, one day. **Two arms**, because the single arm this page
carried until now — tsc's own 78 compiler sources — cannot express the cost that turns
out to separate the two designs.

- **Arm A**, tsc's own compiler sources: 78 files averaging 128 KB, `export *` barrels,
  so every file transitively depends on every other. This is the profile every earlier
  version of this page measured.
- **Arm B**, application-shaped: 2,401 files in 48 layers of 50 modules, each layer
  importing the one below. This arm has never existed before today. It is where the
  finding is.

Both arms use the same two edits to one file:

- a **BODY-ONLY** edit — one added line inside a function body, moving no exported
  signature;
- a **SIGNATURE** edit — two added lines that move one.

Harnesses: `scripts/tsgo-incremental-bench.sh` (tsgo, fresh process per cell) and
`scripts/inc46-vs-tsgo.sh` (ours, one in-session `Project`).

## What changed in the harness since 2026-08-29, and whether the old numbers were sound

**1. The old fixture was defective, and the page described it wrongly.** Its `orig.ts`
was CRLF (3,916 CRs) while both edit variants were LF. So each "one-line edit" was in
fact that line PLUS a whole-file newline normalisation of all 3,916 lines. The page
described the body edit as "a local `const` inside an exported function"; it was that
plus whole-file churn. Both compilers most likely still classified it correctly —
neither line endings nor whitespace survive into a declaration-emit hash or an
exported-signature fingerprint — but *that was never measured*, and "most likely"
is not a receipt. The variants are regenerated CRLF-preserving and now differ from the
original by exactly one added line (body) and two (signature). The re-measured Arm A
cells land within noise of the old ones, which is **evidence, not proof**, that the
defect did not change the classification.

**2. Both harnesses are now parametrised.** Signature for each:
`<projectDir> <editFileRelative> <editsDir> [reps]`. They were hardcoded to
`src/compiler/binder.ts` and to shared-scratchpad paths that survived only by luck — a
scratchpad is shared between sessions, so a stale variant from another run was one
directory collision away. Edit variants now live durably in
`build/bench/inc90-edits-tsc/` and `build/bench/inc90-edits-app/`, and each harness
REFUSES rather than proceeds if a variant is missing, if a variant is identical to the
original, or if the target file does not already hold the recorded original.

**3. Two receipts were added that the old runner could not print.** Both are
load-bearing, for reasons this repo has already paid for:

- **A per-cell diagnostic ROW COUNT, on both sides.** `kir-bench.sh`'s law: a wall-clock
  harness reads a program that does LESS work as the fastest arm. A cell whose row count
  differs from its own full build's is answering a different question and its
  milliseconds are not quotable. Every cell below reports one.
- **A served-vs-fell-back receipt from `Project.incrementalAnswers`.** A body-only cell
  that silently fell back to a full rebuild is otherwise *indistinguishable* from one the
  incremental mechanism served — both are reported as "our incremental time", and the
  fallback one is the faster-looking of the two only by accident. Round 790's law: a
  verifier reads 0 both when the skip is sound and when the instrument is dead.

**4. A bug was found and fixed in the tsgo harness while taking these numbers.** Its
`run` helper was called inside a command substitution, i.e. a subshell, so a row count
assigned there never escaped to the caller: the printed counts were stale values left by
whichever call had last run *outside* a substitution. Plausible numbers, wrong numbers.
`run` now PRINTS `<elapsed ms> <rows>` on one line and every call site reads both.

## Read the caveats before the tables

**1. Arm A is NOT like-for-like; Arm B is.** On tsc's own sources we report **46** rows
where tsgo 7.0.2 and pristine tsc 6.0.3 both report **65**. That margin is no longer
"an amount nobody has decomposed" — `docs/perf/tsgo-diagnostic-gap.md` decomposes it:
all 19 rows are genuine false negatives of ours, in four named mechanisms, with zero
tsgo divergences and zero ours-only rows; **18 of the 19 are emission-side or
lookup-side**, on module resolutions and name lookups the compiler has already
performed. That bounds how much the gap can flatter Arm A's timings, but it does not
zero it, and that page is explicit that whether closing the gap moves a wall figure is
unanswered. **Arm B has no such caveat**: both compilers report the identical single row
— `src/faulty.ts:3:14 TS2322: Type 'string' is not assignable to type 'number'`, same
file, line, column, code and message text. The equivalence gate this comparison always
lacked passes exactly on that arm, which is why Arm B is where the conclusions are drawn.

**2. The two models are not the same shape, and that asymmetry is the finding.** tsgo's
incremental state is `.tsbuildinfo` ON DISK, so each of its cells is a fresh process that
re-reads that state and re-stats the tree. Ours is a live `Project`, so ours pay neither
a process start nor a state read. "tsgo 297 ms" and "ours 137 ms" are therefore answers
to different questions, and § "What a user waits and what a compiler spends" gives both.

**3. The tsgo binary is 7.0.2.** The source comparison at the end is against `main`
(89d5d5b28, 2026-08-20), carried over unchanged from the 2026-08-29 reading; this box has
no Go toolchain and cannot build it. Sources compared at main, timings taken at 7.0.2.

**4. Medians of 3 or 5 as marked, wall clock, one box, pinned by nothing.** This box's
documented run-to-run swing is about ±13%. **No ratio below is quoted to more precision
than that supports** — anything under about 1.3x is reported as "comparable", not as a
win, in either direction.

## Arm A — tsc's own 78 compiler sources

Fixtures `build/bench/tsgo-bench` and `build/bench/ours-bench`; edit
`src/compiler/binder.ts`; variants in `build/bench/inc90-edits-tsc`.

| cell | tsgo 7.0.2 `--incremental --noEmit`, fresh process (3 reps) | ours, in-session `Project` (3 reps) |
|---|---|---|
| cold / no prior state | **1,667 ms** [1658, 1667, 1705] | **5,523 ms** warm JVM [5505, 5523, 5616] · 26,349 ms cold JVM |
| no-op — nothing edited | **185 ms** [174, 185, 186] | **0 ms** [0, 0, 0] |
| after a BODY-ONLY edit | **297 ms** [274, 297, 1946] | **226 ms** [192, 226, 231] |
| after a SIGNATURE edit | **1,695 ms** [1572, 1695, 1733] | **5,578 ms** [5543, 5578, 5610] |
| the plugin's own call, `diagnosticsOf(file + tsconfig)`, body edit | n/a | **217 ms** [191, 217, 263] |
| the plugin's own call, signature edit | n/a | **187 ms** [169, 187, 194] |

**Row-count receipt**: 65 in every tsgo cell, 46 in every project-wide cell of ours — no
cell is answering a smaller question than its own full build.
**Served receipt**: body-only served **3/3**, signature served **0/3**, exactly as
designed. The signature cell's 5,578 ms is our full rebuild (5,523 ms) to within noise,
which is what a clean fallback should look like.

**The tsgo body-only draws include a 1,946 ms outlier** against 274 and 297. It is
reported rather than dropped; the median is what is quotable, and one 7x draw in three
is why a mean would have been useless here.

**This arm did not move.** Against 2026-08-29 (tsgo 1,631 / 182 / 314 / 1,654; ours
5,352 warm and 23,266 cold / 0 / 232 / 5,694) every cell is inside the box's swing.
That is expected rather than disappointing: the ~25 `(INC.*)` rounds since then removed
per-FILE costs, and this profile has 78 files. A profile with 78 huge files is
structurally unable to express a per-file saving — the same law that made
`(INC.57)`'s three quadratics invisible here for ~950 rounds.

## Arm B — application-shaped, 2,401 files

`build/bench/many-small-2400-dom`: 48 layers × 50 modules, each layer importing the one
below, plus one file carrying a planted error. Edit `src/layer00/m0_0.ts` — layer 0, the
deepest-dependency worst case, with 47 layers of transitive dependents. Variants in
`build/bench/inc90-edits-app`.

| cell | tsgo 7.0.2, fresh process (5 reps) | ours, in-session (3 reps) |
|---|---|---|
| cold / no prior state | **427 ms** [391, 408, 427, 435, 468] | **3,960 ms** warm JVM [3440, 3960, 4374] · 13,692 ms cold JVM |
| no-op — nothing edited | **264 ms** [236, 243, 264, 265, 271] | **0 ms** |
| after a BODY-ONLY edit | **297 ms** [278, 280, 297, 297, 355] | **137 ms** [120, 137, 193] |
| after a SIGNATURE edit | **304 ms** [278, 283, 304, 322, 339] | **3,850 ms** [3314, 3850, 5413] |
| the plugin's own call, body edit | n/a | **106 ms** [82, 106, 115] |
| the plugin's own call, signature edit | n/a | **93 ms** [83, 93, 99] |

**Row-count receipt**: 1 row in every tsgo cell and 1 in every project-wide cell of ours,
and it is the *same* row. **Served receipt**: body-only served **3/3**, signature
**0/3**.

**The per-file cells answer 0 rows**, because the edited module has no diagnostics — the
planted error is in `src/faulty.ts`. Those two cells therefore measure the **latency** of
the plugin's query, not its content. (The same is true of Arm A's per-file cells: none of
the 46 rows sits in `binder.ts`; per `tsgo-diagnostic-gap.md` they are concentrated in
`sys.ts`, `performanceCore.ts` and `tracing.ts`.)

---

## 1. Two different questions, and both numbers belong on the page

**What a user WAITS is wall clock.** On Arm B we answer a body-only edit in **137 ms**
against tsgo's **297**, and a no-op in **0 ms** against **264**. Nothing about our
checker earns that; it is entirely the state model. Their floor — process start,
`.tsbuildinfo` read, re-stat the tree — is 264 ms, paid on every keystroke-scale query,
and it is **89% of what a body-only edit costs them**. Our floor is zero because the
program is already in memory.

**What a COMPILER SPENDS is marginal cost above its own floor.** Subtract each side's
no-op:

| Arm B, marginal cost above own floor | tsgo | ours |
|---|---|---|
| body-only edit | ~**33 ms** | ~**137 ms** |
| signature edit | ~**40 ms** | ~**3,850 ms** |

**We win the wall because of the live-session model; they win the marginal compute
because they have a real invalidation algorithm and we fall back to a full rebuild.**
Quoting only one of those two is precisely what this page exists to prevent.

## 2. The signature cliff — and it REOPENS a closed item

`(INC.35)` closed the reverse-dependency closure on the measurement that a closure buys
nothing on tsc's own sources. **Arm A corroborates that independently, from tsgo's side**:
tsgo *implements* per-hop pruning — on a signature change it walks the reverse-reference
graph and re-checks a dependent only if that dependent's own signature also moved — and
on `binder.ts` it bought **nothing**: 1,695 ms against its own 1,667 ms cold check. Two
implementations, two instruments (declaration-emit hash vs resolved-type fingerprint),
same answer on a barrel-exporting codebase.

**Arm B is the counter-example the old page explicitly predicted and never tested.** Its
closing sentence read: not building the closure "is not obviously right for layered code,
where their pruning would pay and our fallback would not". **That hypothesis is now
confirmed by measurement.** On 48 layers:

- tsgo's signature cell is **304 ms** against its own **427 ms** cold check — and, more
  tellingly, against its own **297 ms** body-only cell. Their pruning makes a signature
  edit cost what a body edit costs. The algorithm is doing its job.
- ours is **3,850 ms**, i.e. our warm cold check (3,960 ms) to within noise. We prune
  nothing.

That is **12.7x on the wall** and **~96x on marginal cost**, and it is the single
largest gap this comparison has ever measured — on the one arm where the two compilers
provably report the same thing.

## 3. The IntelliJ plugin's own path does not fall off that cliff

The cliff belongs to `Project.diagnostics()`, the project-wide question. The plugin does
not ask it. `incrementalDiagnostics()` is declared at `Project.kt:3559` and has exactly
one call site, `Project.kt:737`, inside `diagnostics()` — while the plugin asks
`diagnosticsOf(listOf(fileOnScreen, configPath))` exclusively, which narrows at the
SOURCE (INV.6) instead of reusing a previous build's exported surface.

Measured, that call costs **93–106 ms** on Arm B and **187–217 ms** on Arm A, and — the
load-bearing part — **it is independent of the edit shape**: 106 vs 93 on Arm B and
217 vs 187 on Arm A are indistinguishable at ±13%. It is immune to the signature cliff,
and it beats tsgo's 297/304 in both cells of Arm B.

**State the trade honestly: it answers a NARROWER question.** One file's rows, not the
project's. A cross-file error introduced elsewhere by the edit is not shown until that
file is visited. That is a real difference in what the user is told, not only in what
the compiler spends, and it is the reason conclusion 2 stands as a gap worth closing
even though conclusion 3 says the shipped editor path does not hit it.

## 4. Cold start is still the worst number, and it is an artifact-stack problem

**13.7 s against 0.43 s on Arm B (32x), and 26.3 s against 1.7 s on Arm A (16x)** —
mostly JVM start plus the JIT ramp, not checking. The model that wins the edit loop
loses the first impression, and loses it worse the smaller the project.

This is not a compiler problem and should not be worked as one. Cross-reference
`(INC.48)`/`(INC.49)`: with a state snapshot restored, a cold process answers its first
query in **155–175 ms**. What moves this cell is the artifact decision — GraalVM PGO,
the JDK 25 AOT cache, CRaC — priced in `docs/perf/aot-native-image.md` and
`docs/perf/crac-checkpoint.md`.

## The source comparison (carried over unchanged from 2026-08-29, at `main`)

Not re-taken today; no Go toolchain on this box. Reproduced here because it is the
mechanism behind conclusion 2.

**The invalidation algorithm is unchanged in shape** since v7.0.2:
`internal/execute/incremental/affectedfileshandler.go` differs by 5 insertions — a
rename (`EmitOnlyForcedDts` -> `EmitOnlyBuilderSignature`), a nested-emit guard, and a
bug fix where the `affectsGlobalScope` branch now RETURNS the all-files set it had been
computing and discarding. A file's signature is a hash of its forced declaration emit,
falling back to the content version; an unchanged signature stops the walk at that file.

**The editor session is where their effort has gone.** Of 2,491 commits, the churn is in
`internal/project`: `session.go` +446, `projectcollectionbuilder.go` +159, `snapshot.go`
+107, new `refcountcache.go`, `parsecache.go` +55, a new `contentmapper` with ~1,100
lines of tests. The incremental core moved 326 lines total.

**And the editor path still does not reuse checked results.** `Program.UpdateProgram`
reuses parses, module resolution and `processedFiles` when the edited file's imports and
references are unchanged (`canReplaceFileInProgram`) — and then calls
`initCheckerPool()`, a FRESH checker pool, at both v7.0.2 and main. Files are re-checked
lazily per request against a new checker; there is no cross-snapshot carry-over of
diagnostics. **Their LSP has no project-wide diagnostics call**: it serves
`textDocument/diagnostic` per file plus push for open files, and "what is wrong with my
whole project, incrementally" is answered by `tsc --incremental` in a separate process.

## Reproduce

```bash
# Arm A — tsc's own 78 compiler sources, 3 reps
scripts/tsgo-incremental-bench.sh build/bench/tsgo-bench \
    src/compiler/binder.ts build/bench/inc90-edits-tsc 3
scripts/inc46-vs-tsgo.sh          build/bench/ours-bench \
    src/compiler/binder.ts build/bench/inc90-edits-tsc 3

# Arm B — application-shaped, 2,401 files; tsgo 5 reps, ours 3.
# Run the two sequentially: the tsgo harness restores `orig.ts` and removes
# *.tsbuildinfo on exit, and both refuse a tree not holding the recorded original.
scripts/tsgo-incremental-bench.sh build/bench/many-small-2400-dom \
    src/layer00/m0_0.ts build/bench/inc90-edits-app 5
scripts/inc46-vs-tsgo.sh          build/bench/many-small-2400-dom \
    src/layer00/m0_0.ts build/bench/inc90-edits-app 3
```

Each harness prints its own row count per cell; ours also prints the served-vs-fell-back
count. **A cell whose row count differs from its arm's full build is not quotable**, and
a body-only cell that did not serve is measuring a rebuild.

## What this suggests

1. **The old page's one open hypothesis is now settled, against us.** Not building the
   reverse-dependency closure was right for tsc's own sources and is wrong for layered
   code: 3,850 ms against 304. `(INC.35)`'s refusal was sound *for the profile it was
   measured on* and should be re-opened with Arm B as its instrument — another instance
   of the standing law that a cost prior does not transfer across regimes.
2. **The gap to close is the signature edit, and only on layered projects.** Everything
   else we already win or draw on the wall.
3. **The shipped editor path is not the thing to optimise first** — `diagnosticsOf` is
   93–217 ms and edit-shape-independent. Its cost is that it answers a narrower
   question, so the project-wide path is worth fixing for *coverage*, not for the
   plugin's latency.
4. **Cold start is an artifact decision, not a compiler one**, and it is the largest
   remaining ratio on the page.
5. **Arm B should become the default arm for this comparison.** It is the only one that
   passes an equivalence gate, and it is the shape a real user's project has. Arm A's
   value from here on is as the control that a per-file change is invisible on it.
