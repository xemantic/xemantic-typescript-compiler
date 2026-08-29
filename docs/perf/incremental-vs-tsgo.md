# Incremental re-checking: ours against tsgo's

Measured 2026-08-29, one box, one day. Two harnesses over the SAME tree (tsc's own
78 compiler sources) and the SAME two edits to `src/compiler/binder.ts`:

- a **BODY-ONLY** edit — a local `const` inside an exported function, which moves no
  exported signature;
- a **SIGNATURE** edit — one added `export const`, which moves one.

`scripts/tsgo-incremental-bench.sh` and `scripts/inc46-vs-tsgo.sh`.

## Read the caveats before the table

**1. We report 46 diagnostics on this project where tsgo reports 65.** We are doing
LESS WORK, by an amount nobody has decomposed. Every ratio below flatters us by that
unknown margin. This repo already has the law — `kir-bench.sh` runs an equivalence
gate before any timing, because a wall-clock harness reads a program that does less
as the fastest arm — and this comparison does not satisfy it. Treat the numbers as
indicative of the two ARCHITECTURES, not as a compiler-vs-compiler benchmark.

**2. The two models are not the same shape.** tsgo's incremental state is
`.tsbuildinfo` ON DISK, so each of its cells is a fresh process that re-reads that
state and re-stats the tree. Ours is a live `Project`, so ours pay neither a process
start nor a state read. That asymmetry is the finding, not a flaw in the setup — but
it means "tsgo 314 ms" and "ours 232 ms" are answers to different questions.

**3. The tsgo binary is 7.0.2.** The source comparison below is against `main`
(89d5d5b28, 2026-08-20, 2,491 commits later), which this box cannot build — there is
no Go toolchain here. So: sources compared at main, timings taken at 7.0.2.

**4. Medians of 3.** Wall clock, pinned by nothing.

## The table

| cell | tsgo 7.0.2 (`--incremental --noEmit`, fresh process) | ours (in-session) |
|---|---|---|
| full check, no prior state | **1,631 ms** | **5,352 ms** warm JVM · 23,266 ms cold JVM |
| no-op — nothing edited | **182 ms** | **0 ms** |
| after a BODY-ONLY edit | **314 ms** | **232 ms** |
| after a SIGNATURE edit | **1,654 ms** | **5,694 ms** |
| speed-up achieved on a body-only edit | **5.2x** | **23x** |

## What the table says

**Their compiler is 3.3x faster than ours on a full check, and we still answer a
body-only edit faster in absolute terms** (232 ms against 314 ms). Nothing about our
checker earns that; it is entirely the state model. Their 182 ms no-op floor — process
start, `.tsbuildinfo` read, re-stat the tree — is paid on every keystroke-scale query,
and it is 79% of what a body-only edit costs them. Our floor is zero because the
program is already in memory.

**Neither implementation gets anything from a dependency closure on this codebase.**
tsgo *implements* per-hop pruning: on a signature change it walks the reverse-reference
graph and re-checks a dependent only if that dependent's own signature also moved. On
`binder.ts` it bought **nothing measurable** — 1,654 ms against a 1,631 ms cold check.
That is an independent corroboration, from a different implementation, of the
measurement that closed (INC.35) here: on tsc's own sources a file-level and a
symbol-level use graph both re-check ~100% of the program at the median edit. We skip
the closure entirely and fall back to a full rebuild; on this profile that costs us
nothing that their machinery recovers.

**The cold-start column is where we lose, and it is not close** — 23.3 s against 1.6 s.
The model that wins the edit loop loses the first impression.

## The source comparison (at `main`)

**The invalidation algorithm is unchanged in shape** since v7.0.2:
`internal/execute/incremental/affectedfileshandler.go` differs by 5 insertions, and the
substance is a rename (`EmitOnlyForcedDts` -> `EmitOnlyBuilderSignature`), a nested-emit
guard, and a bug fix — the `affectsGlobalScope` branch now RETURNS the all-files set it
had been computing and discarding. The design is what it was: a file's signature is a
hash of its forced declaration emit, falling back to the content version; an unchanged
signature stops the walk at that file.

**The editor session, by contrast, is where the work has gone.** Of 2,491 commits, the
churn is in `internal/project`: `session.go` +446, `projectcollectionbuilder.go` +159,
`snapshot.go` +107, new `refcountcache.go`, `parsecache.go` +55, and a new
`contentmapper` with ~1,100 lines of tests. The incremental core moved 326 lines total.

**And the editor path still does not reuse checked results.** `Program.UpdateProgram`
reuses parses, module resolution and `processedFiles` when the edited file's imports and
references are unchanged (`canReplaceFileInProgram`) — and then calls
`initCheckerPool()`, a FRESH checker pool, at both v7.0.2 and main. Files are re-checked
lazily per request against a new checker. There is no cross-snapshot carry-over of
diagnostics.

**Their LSP has no project-wide diagnostics call.** It serves `textDocument/diagnostic`
per file plus push for open files; `GetGlobalDiagnostics` is program-level option errors,
not "check the project". "What is wrong with my whole project, incrementally" is answered
by `tsc --incremental` in a separate process.

## What this suggests for us

1. The gate is validated by convergence — two independent implementations ask the same
   question, with different instruments (declaration-emit hash vs resolved-type
   fingerprint).
2. Not building the closure was the right call for this workload and is not obviously
   right for layered code, where their pruning would pay and our fallback would not.
   The `(LIB.*)` screened libraries are where that could be tested.
3. Cold start is the gap that matters and it is an artifact stack, not a compiler one.
4. Their allocation of effort — session lifecycle, not invalidation — is worth reading
   as a signal about where the remaining difficulty in an editor integration actually is.
