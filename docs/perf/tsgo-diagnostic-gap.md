# The 46-vs-65 diagnostic gap on the compiler profile, decomposed

(BENCH.5). Measured 2026-09-01 on `build/bench/tsc-project-637d5746` — tsc's own 78
compiler sources, `lib: ["es2020"]`, `types: []`, no `@types/node`, `strict: true`.

`docs/perf/incremental-vs-tsgo.md` caveat 1 says we report **46** rows there where tsgo
reports **65**, "by an amount nobody has decomposed", and that every ratio on that page
flatters us by that unknown margin. This page decomposes it.

**Headline: all 19 rows are genuine false negatives of ours. Zero are tsgo divergences
from pristine, zero are an options or `lib` difference, and there are zero ours-only
rows. But 18 of the 19 are emission-side or lookup-side, not work-side** — see
§ "What the gap does and does not license" before quoting any of this at a ratio.

## The instrument, and the thing it changes

**Pristine tsc runs on this box.** CLAUDE.md's standing rule — *"`tools/tsgo-7.0.2/lib/tsc`
IS THE ONLY REFERENCE COMPILER RUNNABLE ON THIS BOX"* (round 938) — **is false as of this
measurement.** Both halves are present:

- `tools/node/bin/node` — Node 22.20.0, fetched by `scripts/kir-bench.sh`;
- `build/tools/tsc-ref/node_modules/typescript/lib/tsc.js` — **the real `typescript`
  package, version 6.0.3**, left behind by an earlier emit-diff session.

So the arbiter for these 19 rows is not baseline archaeology through
`scripts/pristine_oracle.py` — it is pristine tsc itself, run over the same directory:

```
tools/node/bin/node build/tools/tsc-ref/node_modules/typescript/lib/tsc.js \
    --noEmit --pretty false -p build/bench/tsc-project-637d5746
```

Caveats that belong with that, stated before the table:

**1. `build/tools/tsc-ref` is not provisioned by anything in this repo.** No script and
no workflow creates it; it is a leftover, and it will be absent from a fresh checkout or
after a `clean`. Re-provision with
`PATH=tools/node/bin:$PATH tools/node/bin/npm install --prefix build/tools/tsc-ref typescript@6.0.3`
(network works; `npm`'s shebang is `/usr/bin/env node`, so `tools/node/bin` must be on
`PATH`). A harness that reads it must REFUSE when it is missing rather than silently
falling back to tsgo — rounds 853/873.

**2. tsc 6.0.3 is a release; the corpus baselines are a mainline commit.** `typescript-repo`
declares `6.0.0`, so the reference is three patch releases later than the tree the
`.errors.txt` baselines came from. For every shape below the two agree, and where a
conformance fixture exists the oracle agrees too (§ "Oracle corroboration").

**3. All three compilers were pointed at the same `tsconfig.json`**, so no row here is
an options difference by construction, and the 46 shared rows are byte-identical in
file, line, column, code AND message text.

**4. Our arm is ONE draw.** A concurrent Gradle build from another session emptied
`xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main` minutes after the
capture, so it was not re-drawn (round 851's signature; the second attempt died
`Could not find or load main class`). The capture is valid: `MainKt` was positively
controlled present before the run, `--listAll` was passed and the output carries no
`... and N more error(s)`, the class dir was `-core`'s (the uncommitted edits in the tree
at the time were confined to `-project`, `jvmTest` and `scripts/`), and the run's own
header reproduces the queue entry's figure independently —
`diagnostics: 46 error(s)`, `by code: TS2591x43, TS2304x2, TS2584x1`. Four scratch
repro projects (§ "Mechanism isolation") were run against that same class dir.

**5. No timing figure is quoted anywhere on this page.** Another process was on the box.

## The three-way result

| pair | rows only in the first | rows only in the second | common |
|---|---|---|---|
| **pristine tsc 6.0.3 vs tsgo 7.0.2** | **0** | **0** | **65** |
| **pristine tsc 6.0.3 vs ours** | **19** | **0** | **46** |

Pristine and tsgo agree **byte-for-byte on every one of the 65 rows** on this profile.
Round 938's law (tsgo is not pristine, and diverges in documented ways) is real and does
not bite here: there is no tsgo-only row to subtract.

## Bucket counts

| bucket | rows |
|---|---|
| 1. **ours-missing** — genuine false negative of ours; pristine and tsgo both report | **19** |
| 2. **tsgo-only divergence from pristine** | **0** |
| 3. **options / `lib` difference** | **0** |
| 4. **ours-only** — we report, pristine does not | **0** |
| *undecided* | **0** |

Direction totals: pristine 65, tsgo 65, ours 46. 19 rows in one direction, 0 in the other.

## The 19 rows

Four mechanisms. `F1` accounts for 12 of the 19 and lives in one place.

| # | file | line:col | code | message gist | bucket | family |
|---|---|---|---|---|---|---|
| 1 | performanceCore.ts | 35:84 | TS2591 | Cannot find name `'perf_hooks'` | ours-missing | F1 (a) |
| 2 | sys.ts | 1470:34 | TS2591 | Cannot find name `'fs'` | ours-missing | F1 (a) |
| 3 | sys.ts | 1471:36 | TS2591 | Cannot find name `'path'` | ours-missing | F1 (a) |
| 4 | sys.ts | 1474:36 | TS2591 | Cannot find name `'crypto'` | ours-missing | F1 (a) |
| 5 | sys.ts | 1481:35 | TS2591 | Cannot find name `'inspector'` | ours-missing | F1 (b) |
| 6 | sys.ts | 1498:35 | TS2304 | Cannot find name `'__filename'` | ours-missing | F2 |
| 7 | sys.ts | 1498:92 | TS2304 | Cannot find name `'__dirname'` | ours-missing | F2 |
| 8 | sys.ts | 1498:121 | TS2304 | Cannot find name `'__filename'` | ours-missing | F2 |
| 9 | sys.ts | 1594:180 | TS2345 | Argument of type `'unknown'` is not assignable to parameter of type `'string'` | ours-missing | F4 |
| 10 | sys.ts | 1597:69 | TS2307 | Cannot find module `'source-map-support'` | ours-missing | F1 (a)+(c) |
| 11 | sys.ts | 1629:49 | TS2591 | Cannot find name `'fs'` | ours-missing | F1 (b) |
| 12 | sys.ts | 1650:44 | TS2591 | Cannot find name `'inspector'` | ours-missing | F1 (a) |
| 13 | sys.ts | 1672:47 | TS2591 | Cannot find name `'inspector'` | ours-missing | F1 (b) |
| 14 | sys.ts | 1696:54 | TS7006 | Parameter `'err'` implicitly has an `'any'` type | ours-missing | F3 |
| 15 | sys.ts | 1696:61 | TS7031 | Binding element `'profile'` implicitly has an `'any'` type | ours-missing | F3 |
| 16 | sys.ts | 1728:41 | TS2304 | Cannot find name `'__filename'` | ours-missing | F2 |
| 17 | sys.ts | 1746:47 | TS2591 | Cannot find name `'fs'` | ours-missing | F1 (b) |
| 18 | sys.ts | 1746:73 | TS2591 | Cannot find name `'fs'` | ours-missing | F1 (b) |
| 19 | tracing.ts | 40:27 | TS2591 | Cannot find name `'fs'` | ours-missing | F1 (a) |

Concentration: **3 files of 78** (`sys.ts` 17, `performanceCore.ts` 1, `tracing.ts` 1),
and every row sits in node-runtime interop — a `require(...)`, an `import("<node module>")`
type, or a `__filename`.

### F1 — the module specifier of an `ImportTypeNode` is not reported (12 rows)

Every one of these 12 columns lands on the **string literal inside an import-TYPE node**
(`typeof import("fs")`, `import("fs").Stats`, `import("inspector").Profiler.Profile`),
never on the sibling `require(...)`. We already report the `require` on the same line —
`sys.ts:1470:42` is in the shared 46 while `sys.ts:1470:34` is not.

Pristine's mechanism is explicit in the profile's own sources:
`getTypeFromImportTypeNode` (`src/compiler/checker.ts:20007`) calls
`resolveExternalModuleName(node, node.argument.literal)` with **no `ignoreErrors`**, and
`resolveExternalModuleName` (`:4718`) substitutes
`getCannotResolveModuleNameErrorForSpecificModule` (`:27757`) — which maps a
`nodeCoreModules` specifier to the TS2591 *"Cannot find name '0'"* wording and everything
else to TS2307. That is both the code and the message split we see.

Three independent sub-gates in ours, isolated in § "Mechanism isolation":

- **(a) the `typeof` form is never reported**, at any nesting — 7 rows;
- **(b) a nested position suppresses even the qualifier form** — a qualifier-form
  import-type at file level *is* reported by us, one inside a function or an IIFE is not
  — 5 rows;
- **(c) a non-core module is never reported** (the TS2307 wording is absent from this
  path entirely) — 1 row, which is also (a).

### F2 — `__dirname` / `__filename` are seeded as ambient globals (4 rows)

`Checker.kt:190905` and `:191036` list `"__dirname", "__filename"` in `KNOWN_GLOBALS`,
directly beneath a comment explaining, correctly, why `require`/`module`/`process`/
`Buffer`/`global` are deliberately *not* there. `__dirname`/`__filename` belong on the
same side of that line and are on the wrong one: they are declared in `@types/node`, and
**in no `lib.*.d.ts` in any TypeScript distribution on this box** — checked across
`tools/tsgo-7.0.2/lib`, `typescript-go-repo/internal/bundled/libs` and
`build/tools/tsc-ref/.../typescript/lib`, zero hits. Pristine's
`getCannotFindNameDiagnosticForName` (`checker.ts:27694`) has no arm for either name, so
they fall to plain `Cannot_find_name_0` = TS2304, which is what both references emit.

**Why no baseline caught this**: the pristine corpus has exactly one fixture mentioning
`__dirname` (`parserharness`) and it **declares the name itself** (`declare var __dirname;`,
reported as TS7005). There is no conformance case with an *undeclared* `__dirname`, so
seeding the two names could never move a baseline.

### F3 — `noImplicitAny` is not applied to a callback parameter whose callee is `any` (2 rows)

`activeSession.post("Profiler.stop", (err, { profile }) => …)` where the receiver's type
degraded. Pristine emits TS7006 for the parameter and TS7031 for the binding element.
Reproduced with **no node dependency at all** (`repro4`, `anyThing.post("x", (err, { profile }) => …)`
on a plain `declare const anyThing: any`): pristine emits 4 such rows, we emit 0. So this
is not downstream of F1 — it is a general gap in the implicit-any family for a callback
in an argument position with no contextual signature.

### F4 — inference from an `any` argument yields `unknown` in tsc, not `any` (1 row)

`some(process.execArgv, arg => /…/.test(arg))`. Pristine infers `T = unknown` and then
rejects `arg` against `test(string)`. Also reproduced with no unresolved name involved:
`declare const anyThing: any; some(anyThing, arg => takesString(arg))` is
`TS2345: Argument of type 'unknown' is not assignable to parameter of type 'string'` in
pristine and silent here. tsc contributes no inference candidate from an `any` source
into `readonly T[] | undefined` and falls back to `getDefaultTypeArgumentType` =
`unknownType` for a non-JS file; we are permissive.

This is the only one of the four families that changes a **type** rather than only a
report.

## Mechanism isolation

Four scratch projects under the session scratchpad, each carrying the profile's
`compilerOptions` verbatim, each run through all three compilers.

| shape | pristine 6.0.3 | tsgo 7.0.2 | ours |
|---|---|---|---|
| `const t1: typeof import("fs")` — top level, core | TS2591 | TS2591 | **silent** |
| `const t2: import("fs").Stats` — top level, core | TS2591 | TS2591 | TS2591 |
| `const t3: typeof import("source-map-support")` — top level, non-core | TS2307 | TS2307 | **silent** |
| `const t4: import("source-map-support").Foo` — top level, non-core | TS2307 | TS2307 | **silent** |
| `import("fs").Stats` in a signature nested in a function inside an IIFE | TS2591 | TS2591 | **silent** |
| `import("inspector").Session` at file level | TS2591 | TS2591 | TS2591 |
| `__filename` / `__dirname`, file level, undeclared | TS2304 x3 | TS2304 x3 | **silent** |
| `anyThing.post("x", (err, { profile }) => …)` | TS7006 + TS7031 | TS7006 + TS7031 | **silent** |
| `some(anyThing, arg => takesString(arg))` | TS2345 `'unknown'` | TS2345 `'unknown'` | **silent** |

The qualifier-form / `typeof`-form and the top-level / nested rows are what split F1 into
its three sub-gates; without both controls the family reads as one undifferentiated
"import types are not checked", which it is not.

## Oracle corroboration

`scripts/pristine_oracle.py` agrees wherever it can decide, and cannot decide the rest —
which is why the runnable pristine compiler is the load-bearing instrument here:

- **F1, the `import x = require("m")` sibling shape**: `undeclaredModuleError` and
  `importAliasInModuleAugmentation` both carry
  `TS2591: Cannot find name 'fs'` **on the module specifier**, and both are labelled
  **ACTIVE** — i.e. the corpus already gates that form byte-exactly and we already pass
  it. The gap is confined to the *import-type* node, for which the corpus has **no
  unresolvable fixture at all** (`importingExportingTypes`, the only baseline with
  `import("fs")`, ships a `@types/node` stub).
- **F2**: no fixture exists (see above), and the oracle's absence of one is itself the
  explanation for how the seeding survived.
- **F3, F4**: no fixture isolates the shapes.

## What the gap does and does not license

**It is a correctness gap of 19 rows and it is entirely ours.** The queue entry hedged
that some of the 19 might be tsgo-only divergences, in which case the gap would be "much
less damaging". That hedge is refuted: the count is **0**, pristine and tsgo agree
perfectly, and every missing row is a real diagnostic a user would want.

**It is not a 29% work gap, and it must not be read as one.** Eighteen of the nineteen
rows are emission-side or lookup-side, on work the compiler has already done:

- **F1 (12 rows)**: the module resolution has already run and already failed — our own
  run header prints `unresolved imports: 8 (e.g. 'perf_hooks', 'fs', 'path', 'os',
  'crypto')`, naming the very specifiers whose diagnostics are missing. The failed
  resolution is computed and then not reported.
- **F2 (4 rows)**: the name lookup runs either way; it succeeds against a seeded global
  instead of failing. If anything the *correct* behaviour does marginally less work,
  since the failing path also builds a spelling suggestion.
- **F3 (2 rows)**: a per-parameter predicate on an `any` that is already computed.
- **F4 (1 row)**: the only row that changes a type, and therefore the only one behind
  which any real checking is skipped — one type-argument inference and the single
  assignability comparison it feeds.

So the honest reading for `incremental-vs-tsgo.md` caveat 1 is: **the margin is now
decomposed and it is 19 rows of four named mechanisms, none of which skips a pass, a
file, a parse, a bind or a module resolution.** Whether closing it moves a wall figure is
a separate question that this page deliberately does not answer — it can only be answered
by landing the fixes and re-measuring, and no timing was taken here. Predicting "it will
cost nothing" from the mechanism would be exactly the class of unpriced inference this
repo keeps refuting.

**The equivalence gate `kir-bench.sh` has and this comparison lacked is now buildable.**
The reference arm no longer has to be tsgo: pristine tsc runs here, so a profile-level
`added=0 / removed=0` against *pristine* is one command, and round 938's "tsgo is not
pristine" hedge becomes testable per profile instead of assumed. Both preconditions are
the caveats above — re-provision `build/tools/tsc-ref` and REFUSE when it is absent.

## Reproduce

```bash
S=/tmp/bench5; mkdir -p $S
# pristine
tools/node/bin/node build/tools/tsc-ref/node_modules/typescript/lib/tsc.js \
  --noEmit --pretty false -p build/bench/tsc-project-637d5746 > $S/pristine.txt 2>&1
# tsgo  (a native ELF -- run it directly, `--pretty false` or the output is ANSI-boxed)
tools/tsgo-7.0.2/lib/tsc --noEmit --pretty false \
  -p build/bench/tsc-project-637d5746 > $S/tsgo.txt 2>&1
# ours  (--listAll is mandatory; refuse any capture containing "and N more error(s)")
MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
java -Xmx4g -cp "$MAIN:$(scripts/lib/dep-classpath.sh --print)" \
  com.xemantic.typescript.compiler.MainKt --noEmit --listAll \
  build/bench/tsc-project-637d5746 > $S/ours.raw 2>&1
```

Normalise all three to `path|line|col|code|message`, `sort`, and `comm`. The two
reference arms must come out `0` / `0`; anything else means the reference set moved and
the classification above has to be re-derived, not patched.
