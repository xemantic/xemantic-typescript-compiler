# tsgo port — parse throughput against `-core` (TSGO.1 gate)

Gate (docs/tsgo-port-plan.md § 4.1): **parse throughput, JVM warm, tsc's 78 sources: within
1.5x of `-core`'s `Parser`** (go); **> 3x slower with no identified cause** (no-go).

**Verdict (2026-10-07, commit `3ce760c6e`):**

| build | tsgo arm (median of process medians) | core arm | ratio | paired | gate |
|---|---|---|---|---|---|
| HEAD as generated | **42,163 ms** (40,705–44,061, spread 8.0%, n=3) | 313.9 ms (292–355, 19.8%, n=3) | **134x** | core faster 3/3 (124x–139x) | > 3x, **cause identified** (§ 2) |
| HEAD + measurement-only fix of 7 suffix-slice sites (§ 2) | **368.5 ms** (367.6–412.1, 12.1%, n=4) | 245.7 ms (237.3–288.5, 20.8%, n=4) | **1.50x** | core faster 4/4 (1.28 / 1.46 / 1.56 / 1.72) | **at the line** |
| **`73acaf4ea`: two lowering rules, regenerated (§ 0)** | **314.6 ms** (306.1–323.1, 5.4%, n=4) | 248.8 ms (235.1–257.8, 9.1%, n=4) | **1.26x** | core faster 4/4 (1.23 / 1.34 / 1.25 / 1.26) | **go** |

So the port as generated is 134x slower — a single, mechanical, quadratic defect (Go's O(1)
string slice lowered to Kotlin's copying `substring`), not a property of the approach. With that
one lowering class fixed by hand at its seven parse-path sites, the port lands exactly on the 1.5x
line on a loaded box; § 3 ranks the remaining ~120 ms gap, and the first three items there are each
larger than the margin needed.

**This was measured on a NOT-quiet box** (8 cores, load average 3–5 during the runs, 4–6 other
JVMs from concurrent agents' compiles). The core arm's own process medians span 20% across the two
batches (240 vs 314 ms), which is the box, not the parser. Re-run `scripts/tsgo-parse-bench.sh` on
a quiet box before quoting the 1.50x as anything but "at the line".

## 0. Progress — each lowering rule, re-measured (same harness, ABBA, 4 processes per arm)

| after | tsgo arm | core arm | ratio | paired | commit |
|---|---|---|---|---|---|
| HEAD as generated | 42,163 ms | 313.9 ms | 134x | core 3/3 | `3ce760c6e` |
| **substring elimination** (window rule, § 2) | 338.5 ms (312.8–352.3, 11.7%) | 236.1 ms (224.5–243.6, 8.1%) | **1.43x** | core 4/4 (1.34–1.47) | `1c9db2fe5` |
| **single-probe comma-ok map read** | 314.6 ms (306.1–323.1, 5.4%) | 248.8 ms (235.1–257.8, 9.1%) | **1.26x** | core 4/4 (1.23–1.34) | `73acaf4ea` |
| **inline func-typed parameters** (§ 4 item 3), same-session BEFORE row | 311.6 ms (305.5–322.7, 5.5%) | 245.8 ms (241.0–266.4, 10.3%) | 1.27x | core 4/4 (1.26 / 1.33 / 1.16 / 1.27) | `163b0a743` (before) |
| **inline func-typed parameters**, AFTER | 308.9 ms (291.5–319.6, 9.1%) | 236.4 ms (232.3–249.3, 7.2%) | 1.31x | core 4/4 (1.38 / 1.24 / 1.31 / 1.23) | `69898f30c` |
| **window parameters** (`isJSDocLikeText(sourceText[start:])`) | 305.3 ms (296.5–319.9, 7.7%) | 239.1 ms (221.5–282.8, 25.6%) | 1.28x | core 4/4 (1.36 / 1.20 / 1.13 / 1.34) | this rule |

**Gate status: MET by lowering rules alone** — 1.26x, every paired ratio under 1.5x (load 1.2–1.6,
3 other JVMs on the box). § 4's items 3 (inline func-typed parameters) and 2 (flattening embedded
bases) were not needed and are NOT landed; item 5's `IndexAny` fast path is moot (rule 1 removed
`findImportOrRequire`'s copy and it left the profile). Profile after both rules (JFR, 1,998 bench-thread samples,
self charged to the nearest `com.xemantic` frame): `scanASCIIWhile` 15.5% (the boxed `Function1`
predicate — item 3), `GoSlice.load` 9.1% + `Arena.new` 9.8% ported-frame (item 2), `GoMap.probe`
8.9% under `internIdentifier` (now one probe; the rest is `String.hashCode` of a fresh token and
`HashMap.getNode`), `scan` 6.0%, `MemberExpressionBase.<init>` 5.3% (item 2's embedded chain),
`overrideParentInImmediateChildren` 5.3%, `GoMap.get` 3.8% (`getIdentifierToken`),
`appendRuneBytes` 3.0% + `fromUtf16` 1.2% (the host conversion, item 6). Remaining single-bound
copies in `gen/` (33 after the window-parameter rule) are all off the parse path.

**Inline func-typed parameters — no measurable gain (2026-10-07).** The rule landed (32 functions
inline, `scanASCIIWhile`'s `Function1` dispatch gone from the bytecode: `javap` of
`scanIdentifier` names neither `Function1` nor `scanASCIIWhile`), but the tsgo arm moved
311.6 → 308.9 ms, and a direct before/after ABBA of the tsgo arm alone (4 processes each) read
before 317.2 / 355.0 / 373.7 / 333.0 vs after 305.4 / 318.6 / 381.1 / 333.4 — after faster 2/4,
medians 344 vs 326 ms on a box whose load rose 1.4 → 2.2 during the run. Within noise, ≤ ~3%.
Why: the profile re-attributed, it did not shrink — before, `scanASCIIWhile` 16.1% + `scan`
7.8% + `scan$lambda` 1.7%; after, `scan` 14.2% + `scanIdentifier` 8.4%, and 8.4% of all samples
are `String.charAt` called from the inlined identifier loop (`goViewByte` → `s[off + k].code`).
The cost was the per-character loop over a `String` (Latin-1 coder check + bounds per char;
JFR's counted-loop bias inflates it, CLAUDE.md), which C2 had already handled with the dispatch
monomorphic per caller. The ratio's variation between the two batches (1.27x / 1.31x) is the
CORE arm (245.8 vs 236.4 ms), not the port. Next lever there is the representation (a
`ByteArray` source text, or a 128-entry predicate table per call site), not the call.

**Window parameters — no measurable gain either (2026-10-07).** The last parse-path suffix copy
(`isJSDocLikeText(p.sourceText[start:])`, one per JSDoc comment) is gone; tsgo-arm-only ABBA against
the previous rule's classes read 323.1/309.5, 297.5/316.0, 303.2/302.9, 314.1/320.4 ms
(before/after, after faster 2/4) — noise. Single-bound copies in `gen/`: 34 → 33, none on the parse
path. **Where the remaining ~1.3x lives** (profile above): the per-character `String.charAt` loop
in the scanner, `Arena.new`/`GoSlice.load` + the embedded-struct chain (§ 4 item 2, ~25%
inclusive), and `GoMap.probe`/`get` on fresh token strings — representation work, not call
lowering.

## 1. Setup

- **Harness**: `xemantic-typescript-compiler-tsgo/src/jvmTest/kotlin/ParseBenchMain.kt`,
  `ParseBenchMain <tsgo|core> <repoRoot> [warmup] [iters]`. Both arms read the same 78 files once
  (UTF-8, BOM stripped) into Kotlin UTF-16 strings, then each iteration parses all 78:
  - `tsgo`: `GoString.fromUtf16(text)` (timed separately as `conv_ms`, inside the iteration —
    a host holding Kotlin strings pays it) → `parser.parseSourceFile(opts, bytes, kind)`, after
    `parser.goInitPackage()`, same options as `OracleParityTest`;
  - `core`: `Parser(text, fileName).parse()` with default flags (what `ProjectCompiler` passes a
    plain `.ts` file).
  - checksum = per-iteration Σ(nodeCount + diagnostics), folded and printed, so no parse is dead.
    The tsgo arm counts 934,696 nodes per iteration, core 856,974 (the two ASTs differ in shape);
    the patched tsgo arm counts exactly HEAD's 934,696 — the fix changes no output.
  - Both arms run on a 1 GB-stack thread (the port's production shape; `-core` runs on a deep stack too).
  - `-Xms2g -Xmx3g`, Zulu OpenJDK 26.0.2 (CLAUDE.md: Zulu runs ~13% pessimistic COLD vs Temurin; this is warm).
- **Driver**: `scripts/tsgo-parse-bench.sh` — one Gradle invocation (under `flock`) builds `-tsgo`'s
  jvmTest classes and prints `jvmTestRuntimeClasspath` via a throwaway init script; every
  non-Gradle-cache entry (both class dirs, `-core`'s jar) is **snapshotted** into `$OUT/cp` so a
  concurrent `gen/` regeneration cannot change classes mid-run; then one arm per JVM, ABBA across
  processes (`tsgo core core tsgo …`), `--rounds 4` = 4 processes per arm, 8 warm-up + 12 measured
  iterations each. `TSGO_PREPEND=<dir>` puts an experimental class dir in front of the tsgo arm
  only (how the patched row above was taken). `-tsgo`'s jvmTest has a test-scoped
  `implementation(project(":xemantic-typescript-compiler-core"))`; commonMain does not.
- **Provenance of the snapshot**: commit `3ce760c6e`, `gen/` had one dirty path (the untracked
  `gen/binder/`, another agent's work in progress); `gen/scanner` and `gen/parser` were clean at
  HEAD. The HEAD batch used 2 warm-up + 3 measured iterations (a tsgo iteration is 42 s and does
  not warm: the ladder is flat at 40–45 s from iteration 1).
- Warm-up ladder of the patched arm: 2,650 / 918 / 826 / 426 / 467 / 380 ms, then 368–518 —
  warm by iteration 6. Core: 1,258 / 389 / 375 / 304 / 243, then 224–326.

## 2. The 134x: Go string slices lowered to copying `substring`

Profile of HEAD (JFR `settings=profile`, 5,834 samples on the bench thread, full stacks, max depth
62): **90.9% inclusive in `ScannerKt.scanASCIIWhile`**, 64.5% from `scanIdentifier`.

```kotlin
// gen/scanner/Scanner.kt — Go: text := s.text[s.pos:s.end]   (O(1), shares the backing array)
val text: String = this!!.text.substring(this!!.scannerState.pos, this!!.end)   // O(rest of file)
```

Go's `s[a:b]` on a string is a two-word header; `String.substring` copies. Taking a SUFFIX of the
source text per token makes the scanner O(tokens × file size) — quadratic, ~300k identifiers ×
~1.5 MB for `checker.ts` alone. Seven parse-path sites have this shape (a suffix of the whole
source fed to an index/prefix test):

| site | Go | per |
|---|---|---|
| `scanner.scanASCIIWhile` | `text := s.text[s.pos:s.end]` | identifier, whitespace run, number |
| `scanner.scanString` | `strings.IndexByte(s.text[s.pos:], quote)` | string literal |
| `scanner.processCommentDirective` ×2 | `strings.HasPrefix(s.text[pos:], "ts-…")` | `//` comment |
| `scanner` (`*/` search, line ~3024) | `strings.Index(text[pos:], "*/")` | block comment |
| `parser.match` | `strings.HasPrefix(text[pos:], s)` | pragma scan |
| `parser.skipTo` | `strings.Index(text[pos:], s)` | pragma scan |

**The measurement-only fix** (scratch copy of `Scanner.kt` + `Parser3.kt`, compiled with the
embeddable Kotlin 2.4.10 compiler at JVM target 21 against the snapshot, prepended to the tsgo
arm's classpath; NOT committed — the lowering owns the real fix): `scanASCIIWhile` indexes
`text[base + i]` up to `end`; the others use `startsWith(p, pos)` / `indexOf(x, pos) - pos`. That
alone is **42,163 → 368.5 ms (114x)** with byte-for-byte the same node and diagnostic counts.

**Not yet fixed and of the same class** (found by grep of single-argument `.substring(` in
`gen/`, 40+ sites): `ast.findImportOrRequire` (`strings.IndexAny(text[index:], "ir")` per `i`/`r`
character — 6.6% of the patched profile, § 3 item 5), `parser/Jsdoc.kt:261`
(`isJSDocLikeText(sourceText[start:])`) and `:1057` (`HasPrefix(sourceText[tokenEnd:], "://")`),
`scanner.Scanner.kt:324` (`commentText[i+1:]`, a comment-sized copy — linear, harmless) and the
`tspath`/`stringutil` helpers (path-sized, harmless). The checker and binder will have more.

## 3. The remaining 1.50x: profile of the patched arm

JFR over 80 measured iterations after 12 s of warm-up, 2,356 bench-thread samples, max depth 59
(`scripts/tsgo_parse_profile.py`; stdlib leaves charged to the nearest `com.xemantic` frame,
runtime/shim frames charged to the generated caller). Shares are inclusive, of a ~370 ms parse:

| # | cost | share | Go construct → Kotlin lowering |
|---|---|---|---|
| 1 | `core.Arena.new` + node construction | **21.8%** | **embedded-struct chains**: an `Identifier` is ONE allocation in Go (all embedded bases inline) and **12 objects** here (`Identifier → PrimaryExpressionBase → MemberExpressionBase → LeftHandSideExpressionBase → UpdateExpressionBase → UnaryExpressionBase → ExpressionBase → NodeBase → NodeDefault → Node → TextRange`, `+ FlowNodeBase`); `CallExpression` 11, `PropertyAccessExpression` 12, `IfStatement` 8. Most of those bases have no field but the embedded one. Plus `Arena.new` allocates a new `GoSlice` header per node (`data = data.slice(0, index+1)`) and fills the slot lazily through `GoSlice.load`'s `elem.zero()` (a megamorphic lambda). |
| 2 | `scanner.scanASCIIWhile` | **15.7%** | **func-typed parameter**: `pred: ((Int) -> Boolean)?` is a `Function1` called through `invoke(Object)` (boxed `Int`, `!!`) from one site with 6 different lambdas → megamorphic, never inlined. tsgo's Go compiler inlines/devirtualizes these. |
| 3 | `GoMap.lookup` (`internIdentifier`) + `GoMap.get` (`getIdentifierToken`) | **10.8% + 4.9%** | **comma-ok map read**: `lookup` is `containsKey` + `get` (two hash probes) + a `Tuple2` per call; the key is a freshly `substring`ed token, so `String.hashCode` is computed every time (`ArraysSupport.hashCodeOfUnsigned` 1.4% self). |
| 4 | `parser.overrideParentInImmediateChildren` | 7.3% | real tsgo work (a `forEachChild` over every finished node's children), paid through the generated `forEachChild` and the embedded chain to reach `parent`. |
| 5 | `ast.findImportOrRequire` | 6.6% | § 2's class: `strings.IndexAny(text[index:], "ir")` — a suffix copy per `i`/`r`, then `IndexAny`'s rune-by-rune shim (`runeAt` + `indexRune` per char). |
| 6 | `GoString.fromUtf16` (host conversion) | 3.9% (15.5 ms/iter) | the UTF-16 → byte-string boundary. tsc's sources include non-ASCII, so whole files take the slow `StringBuilder` + `appendRuneBytes` path. |
| 7 | JSDoc parsing | 2.3% | real work. |
| — | `goCopy`, `Tuple*`, `GoSlice.append*` | < 1.5% each | struct value copies and tuple returns are NOT a parse cost. |

`huge_methods.py --classes <tsgo main classes>`: 1,043 classes, 15,637 methods, **3 over 8,000
bytecodes**, none on the parse path: `diagnostics.keyToMessage` (54,743), `api.encoder`'s
`DecoderGenerated` (12,506) and `EncoderGenerated.getChildrenPropertyMask` (11,322). The largest
parse-path method is `scanner.scan` at 5,289. The encoder one matters for (TSGO.3)'s API serving.

## 4. Recommendations, ranked by expected gain (for the lowering / runtime owners)

1. **Never lower a Go string slice to a copying `substring` when it only feeds an index/len/prefix
   use** (the 134x → 1.50x, § 2). Two rules cover every parse-path site: (a) **call fusion**, the
   generalisation of the existing `utf8.DecodeRuneInString(s[i:])` → `decodeRuneInStringAt(s, i)`
   rule: `strings.{HasPrefix, Index, IndexByte, IndexAny, IndexRune, Contains}(s[i:], x)` →
   offset-taking shim variants (`hasPrefixAt(s, i, x)`, `indexAt(s, i, x)` returning the RELATIVE
   index, …); (b) **local views**: a local `t := s[a:b]` used only as `len(t)`/`t[k]`/`t[k:]`
   lowers to `(s, a, b)` with index arithmetic. Anything else may keep `substring`. A
   `huge_methods.py`-style census (grep `\.substring\([^,]+\)` in `gen/`) is the gate.
2. **Flatten field-less embedded bases** (~10–20%). A struct whose only field is one embedded
   struct (`ExpressionBase { NodeBase }`, …) should not be a separate object: lower the chain so the
   outermost class holds the promoted fields directly (or the innermost carrying class), and route
   promoted selections to it. Measured lever: ~10 of the 12 allocations per `Identifier`.
   Secondary: `Arena.new` should bump a length field rather than allocate a `GoSlice` header per
   node, and slab slots should be filled eagerly per slab, not through a per-load `elem.zero()`.
3. **Emit `inline` for a Go function whose func-typed parameter is only called, never stored or
   escaped** (`scanASCIIWhile`, ~10–15%). Kotlin then specializes each call site's lambda and the
   boxed `invoke(Object)` disappears. A hand override is the fallback (an ASCII predicate table).
4. **`GoMap.lookup` with one probe and no tuple** (~5–8%): `val v = b[key]; if (v != null) …
   else if (b.containsKey(key))` (the second probe only for a real miss / null-valued entry), and a
   lowering rule that destructures `v, ok := m[k]` without allocating a `Tuple2` (e.g. a runtime
   `lookupOrNull` + `contains` pair for non-null value types).
5. **Fix `findImportOrRequire`** (~6%): rule 1(a) plus an ASCII fast path in the `IndexAny` shim
   (the `chars` set is ASCII → plain `indexOf` per char, or a 128-bit mask).
6. **Host text boundary** (~4%): a host reading files should decode bytes as ISO-8859-1 straight
   into the byte-string representation (zero conversion; that IS tsgo's representation). For
   hosts that already hold UTF-16 text, `fromUtf16` should copy the ASCII prefix in bulk and only
   then fall into the per-rune path.
7. Encoder/decoder methods over 8,000 bytecodes: split before (TSGO.3) serves the API.

Expected outcome if 1–4 land: the tsgo arm at roughly 230–270 ms against core's ~240–310 ms on
this box, i.e. at or under 1.2x — the port's AST does more work than `-core`'s (934,696 vs 856,974
nodes, eager parent fix-up, dynamic-import scan), so parity is not expected; within 1.5x with
margin is.

## 5. Reproduce

```bash
scripts/tsgo-parse-bench.sh                       # build + ABBA, 4 processes per arm
scripts/tsgo-parse-bench.sh --rounds 3 --warmup 2 --iters 3   # HEAD while the slice defect stands
TSGO_PREPEND=<patched classes> scripts/tsgo-parse-bench.sh --no-build --out <dir with classpath.txt>
java -XX:StartFlightRecording=filename=tsgo.jfr,settings=profile,delay=12s -cp <cp> \
  com.xemantic.typescript.tsgo.ParseBenchMainKt tsgo . 10 80
jfr print --events jdk.ExecutionSample --stack-depth 512 tsgo.jfr > samples.txt
python3 scripts/tsgo_parse_profile.py samples.txt
python3 scripts/huge_methods.py --classes xemantic-typescript-compiler-tsgo/build/classes/kotlin/jvm/main
```

Requires `build/goport/oracle/manifest.json` (names tsc's 78 sources). Do not watch a run.

## 6. The CHECK path (TSGO.2): warm rebuilds of a whole program (2026-10-08)

The sections above gate the PARSER. This one measures what a host pays for a check: tsconfig,
`compiler.NewProgram` (parse and bind every file, the libs, module resolution) and every
diagnostic phase, rebuilt from scratch per iteration in one warm JVM.

**Harnesses** (`-tsgo/src/jvmTest/kotlin/`):

- `CheckBenchMain <tsconfig.json> [warmup] [iters] [libcache|nolib] [single|parallel]` — one
  project. File CONTENTS are read once and served from memory; `libcache` shares parsed bundled libs
  across iterations (tsgo's harness and language server do; worth ~2% here, 65 MB of allocation).
  Per iteration it prints wall, the bench thread's CPU and allocation (`ThreadMXBean`), GC pause
  time, the phases, the diagnostic count and an order-independent DIGEST of what the diagnostics say
  (file, span, code, message) — equal across iterations and between `single` and `parallel`.
- `CaseBenchMain <repoRoot> [stride] [warmup] [iters]` — the many-small-programs complement: every
  `stride`-th configuration of the diagnostics oracle (`build/goport/diag-oracle`), compiled
  check-only through the ported test harness as `DiagParityTest` does (1,313 configurations at
  stride 10).

Measured on tsc's 78 sources (`build/bench/tsc-project-*`, `lib: es2020`, 123 program files, 65
diagnostics — tsgo 7.0.2 reports the same 65), JDK 26 (Zulu), `-Xms2g -Xmx6g -XX:+UseParallelGC`,
6 warm-up + 10 measured iterations per process. **The box was shared and loaded** (load 2-13 from
other agents' builds), so WALL swings ±20% between processes; the bench thread's CPU time and its
allocation are the stable instruments and every rule below was decided on them (ABBA, ≥ 2 processes
per arm).

| | wall (median of process medians) | bench-thread CPU | allocated / check |
|---|---|---|---|
| port at `c39fec70a` (start of this pass), single-threaded | 4,329 / 4,557 ms | 3,878 / 4,089 ms | **5.8 GB** |
| port after this pass, single-threaded | 4,143 / 3,521 ms | 3,477 / 3,216 ms | **2.6 GB** |
| port after this pass, **parallel** (tsgo's default: 4 checkers) | **2,069 / 2,491 ms** | — | — |
| tsgo 7.0.2 `tsc --noEmit -p` (process wall, default 4 checkers) | 1,747-1,868 ms | | |
| tsgo 7.0.2 `--singleThreaded` (process wall) | 3,159-3,915 ms (internal "Total" 3.06 s on a quiet box) | | |
| `-core` (`BenchMain`, warm, its own checker: 46 diagnostics) | 7,650 / 7,541 ms | | |

(Two interleaved rounds, `build/perf/final.sh` in the worktree that took them; the pairs are round
0 / round 1.) So the ported checker is **~1.1-1.2x tsgo single-threaded and ~1.15-1.4x tsgo's
default**, and **2-3.6x faster than `-core`** on the same project.

### 6.1 What moved it (each a rule or runtime change, gated on every commit)

| change | measured | where |
|---|---|---|
| `xxh3.Hasher` buffers lazily and takes words without a byte slice; `hashWrite32/64` overrides use it | 4,300 → 3,955 ms wall (ABBA) | the checker's cache keys: a new `Hasher` (a 1,088-char buffer + 8 accumulators) per key |
| `&a[i] == &b[j]` → `sameSlot` | (folded into the row above) | `core.Same` |
| GoMap: open addressing instead of `HashMap` | CPU −4.2% | `LinkStore.Get`, the hottest map read: one array read per identity hit |
| `strings.LastIndex(s[:k], …)` fusion | allocation 3.49 → 2.85 GB | `parseJSDocComment`'s indent: a prefix copy per JSDoc comment, ~900 MB per parse of tsc's sources |
| `GoElem.nilSlice` eager (was a synchronized `lazy`) | ~1.6% of samples | every nil-slice read |
| short `Hasher` inputs hashed from a byte buffer; `core.Arena.New` override (a fresh zero) | CPU −2.9%, allocation 2.86 → 2.57 GB | |
| copy elision: a fresh conversion; `return x` of an owned struct local | allocation −1.7%, CPU within noise | |
| map range → `GoMap.iter` (one table copy, no probe per entry) | cases CPU −4.8%, tsc −1.6% | `initializeChecker`'s merge of every file's locals |
| **`WaitGroup.Go` starts a goroutine** (it ran synchronously) | parallel 2.07-2.49 s against 3.5-4.1 s single | tsgo's default program DEADLOCKED before (the files parser queues children under a mutex they take) |
| goroutines reuse idle threads (cached pool) | within noise | |

Measured and NOT kept: a struct map key read without a copy (`m[key.goCopy()]` on lookups) — C2's
escape analysis already removed those copies (no change in allocation or CPU); a per-map
last-key cache — 26% of lookups repeat the previous key, but those are L1 hits already.

### 6.2 Where the time goes now (JFR, single-threaded, self time charged to the nearest ported frame)

Flat: `LinkStore.Get` 10% (map lookups keyed by node/symbol identity: memory latency — Go pays the
same lookups with fewer cache misses), `getSourceFileOfNode` 3% (parent-chain walks), the rest
≤ 2.5% each across ~200 checker functions. GC pauses are 10-20% of wall at 2.6 GB/check; the
allocation left is dominated by:

1. **`parseJSDocComment`'s `p.sourceText = p.sourceText[:end-2]`** — ~19% of all bytes: Go's O(1)
   string slice is a copy of the file prefix per JSDoc comment. NOT fixable by a lowering rule: the
   scanner reads `len(s.text)` and takes suffixes of its text (`IndexByte(s.text[s.pos:], quote)`),
   so handing it the whole text with a smaller end changes tokens in edge cases (an unterminated
   string inside a JSDoc type). The fix is a representation change (a string view type for byte
   strings) or overriding the scanner's text bounds as a whole.
2. GoSlice headers (~19% of bytes, diffuse: every `append`/sub-slice allocates a header where Go
   copies a value) and struct value copies (`FlowType`, `Uint128` keys stored in maps).

In the many-small-programs harness (`CaseBenchMain`), per-program checker set-up dominates:
`newChecker`/`initializeChecker` merging the lib globals and `getNamedMembers` sorting large lib
interfaces' members (`compareNodes` → `getSourceFileOfNode` per comparison; the comparator's `Int`
result is boxed through `Function2`) — the same algorithm Go runs per checker.

### 6.3 Remaining levers, ranked

1. **The JSDoc prefix copy** (above): ~19% of allocation on a TypeScript project with doc comments.
2. **GC configuration of the host**: a larger young generation (`-Xmn4g` with `-Xmx6g`) let more of
   an iteration's garbage die young (single-threaded 3.72 → 3.39 s in one sweep; noisy).
3. **Parallel memory**: type-fest's parallel check runs out of a 6 GB heap (tsgo itself uses 5 GB
   there; single-threaded it fits) — JVM object overhead times four checkers.
4. Map lookups keyed by identity (`LinkStore`): only a per-object link slot would beat the hash
   probe, and it would have to be thread-safe across checkers.

### 6.4 Reproduce

```bash
# classpath: -tsgo's jvmTestRuntimeClasspath (the init script of scripts/tsgo-parse-bench.sh prints it)
java -Xms2g -Xmx6g -XX:+UseParallelGC -cp <cp> com.xemantic.typescript.tsgo.CheckBenchMainKt \
  build/bench/tsc-project-*/tsconfig.json 6 10 nolib single      # or: parallel
java -Xms2g -Xmx6g -XX:+UseParallelGC -cp <cp> com.xemantic.typescript.tsgo.CaseBenchMainKt . 10 3 4
tools/tsgo-7.0.2/lib/tsc --noEmit -p build/bench/tsc-project-*/tsconfig.json [--singleThreaded] --extendedDiagnostics
# profile: -XX:FlightRecorderOptions:stackdepth=1024 -XX:StartFlightRecording=filename=x.jfr,settings=profile,delay=30s,duration=45s
jfr print --events jdk.ExecutionSample --stack-depth 2048 x.jfr > samples.txt
python3 scripts/tsgo_parse_profile.py samples.txt check-bench-deep-stack 2048
```

## 7. The JVM port profiled: where cold and warm time goes, and what is un-JVM-like ((TSGO.6-f), 2026-10-09)

Instruments: async-profiler 4.1 (`scripts/tsgo-jvm-profile.sh` downloads it into `tools/async-profiler`;
`scripts/tsgo_ap_stacks.py` reads its collapsed output — by thread, leaf mechanism, nearest ported owner,
callers, frame kind, allocated class and allocation site). WARM = `CheckBenchMain` on tsc's compiler profile
(123 files, 65 diagnostics), the profiler attached after ~10 rebuilds for 30-40 s; COLD = one
`TsgoMainKt --noEmit -p .` run with the agent from JVM start. JDK 26 (Zulu), 8 cores, `-Xms2g -Xmx6g`, G1.
Every profiled run printed the same 65 diagnostics / digest; every cold run is byte-identical to tsgo.

### 7.1 Cold (10.4 s wall, ~66 s CPU)

| thread kind | share of all CPU |
|---|---:|
| JIT compiler threads | **50.4%** (C2 40.6%, C1 9.8% — the three C2 threads are busy the whole run) |
| goroutines (the check: parse, bind, 4 checkers) | 43.9% |
| GC (G1 workers + concurrent) | 5.4% |

Inside the goroutines, by the frame the sample landed in: **interpreted 34.9%**, C1 16.3%, C2 9.5% + inlined
25.9%, native/VM 13.4%. So the cold gap to tsgo (1.77 s) is the JIT ramp, not code shape: a third of the
compiler's own time runs in the interpreter, and as much CPU again goes to compiling. GC pauses summed 450 ms.
**Zero methods are over HotSpot's 8,000-bytecode `HugeMethodLimit`** (`scripts/huge_methods.py --classes
xemantic-typescript-compiler-tsgo/build/classes/kotlin/jvm/main`: 4,857 classes, 69,617 methods; largest a
`<clinit>` at 7,526, the largest hot ones `structuredTypeRelatedToWorker` 5,953 and `scanner.scan` 5,821).

The lever that moves cold is the JDK's AOT cache (JEP 483/514/515: classes loaded and linked ahead, plus
method profiles), not a code change: trained once (`-XX:AOTCacheOutput=…`, one check of the same project) and
used with `-XX:AOTCache=…`, three runs each, **10.41-11.15 s → 6.33-6.67 s (−38%)**, output identical. The cache
needs a classpath of JARs (directories are refused: `non-empty directory`), so it belongs to a packaged launcher,
as `-core`'s `scripts/xtsc` does (`docs/perf/aot-cache.md`); nothing ships it for the port yet.

### 7.2 Warm, single-threaded (before this round: 3.71 s per rebuild, 2.64 GB allocated)

Process CPU over 40 s: the bench thread 57%, **G1 workers 31% + concurrent 6%** (parallel young collections of
2.6 GB/rebuild), C2 still 6%. The bench thread by leaf mechanism:

| mechanism | share | what |
|---|---:|---|
| generated checker code | 36.7% | flat: ~200 functions, none above 2.8% |
| `runtime.GoMap` | **14.3%** | `find` 11.6% leaf; **55% of its callers are `core.LinkStore.Get`** (node/symbol → links), i.e. ~7.9% of the thread is one identity-keyed probe per link read — memory latency, the same lookups Go pays |
| generated `ast` | 13.3% | `visitNodeList`, `asIdentifier`, `forEachChild`, `text`, `kind`: flat |
| `runtime.GoSlice` | 4.9% | `load` (materializes a never-written slot), `slice`, `append1`, `len` |
| itable stubs | **4.3%** | megamorphic INTERFACE dispatch: `nodeData` methods (`localsContainerData`, `flowNodeData`, `modifiers`, `declarationData`, …) ~50%, `TypeData` (`asStructuredType`, `asObjectType`) ~21%, Kotlin `Function` comparators/zero lambdas ~20% |
| binder / parser / scanner | 2.9 / 1.9 / 1.8% | |
| `jlong_disjoint_arraycopy` | 1.7% | the JSDoc prefix copy below |
| `String.equals` | 1.2% | name-keyed symbol tables |

Allocation (2.64 GB/rebuild) by class: `byte[]` **21.4%** — 76% of it `parseJSDocComment`'s
`p.sourceText = p.sourceText[:end-2]` (§ 6.2: a COPY of the file prefix per JSDoc comment; the program's
5,318 `/** */` comments average a 180 KB prefix, so this is a real price, not a location, and every prefix
over half a G1 region is a humongous allocation), 16% `xxh3.Hasher` buffers; `GoSlice` headers **17.6%**;
`Object[]` 5.9% (map tables, slice growth); `FlowType` 5.2% (struct value copies); nil `GoMap`s 5.0%;
**`java.lang.Integer` 4.1%** (boxed comparator results); `xxh3.Uint128` 3.8% (value copies of cache keys);
`TextRange` 3.3% (three per node: the constructor's zero, `UndefinedTextRange()`, `NewTextRange`); `Tuple2`
2.9%; `AtomicLong` + its `atomic.Uint64` wrapper 2.0% (one pair per Node and Symbol); `GoDeferFrame`'s
`ArrayList` 1.0%. Closure boxes (`Ref$ObjectRef`/`IntRef`/`BooleanRef`, 1,929 sites in the bytecode) are
0.4% — not a lever.

### 7.3 What landed (runtime shims only; generated code changed only through a regeneration)

| lever | why it is the JVM's way | measured |
|---|---|---|
| `slices.BinarySearchFunc` is `inline` | the comparator call becomes monomorphic per call site, so C2 inlines it and scalar-replaces the boxed `Int` and the result `Tuple2` | `Integer` 4.1% → 0.45% of allocation |
| `GoMap.nil` shares one map per value kind (`GoElem.nilMapCache`) | a nil map is immutable and Go compares maps only to `nil` | nil maps 5% of allocation → ~0 |
| `xxh3.Uint128` immutable (`val`, no `goCopy`; porter emits no copy) | an immutable value needs no defensive copies | 12 regenerated files lose `.goCopy()` |
| `atomic.Uint64` one object on the JVM (`expect` class; `@Volatile long` + `AtomicLongFieldUpdater`) | a field updater is how the JDK embeds an atomic in an object | −1 object per Node and Symbol |
| `GoSlice.isNil` derived (`array === GoElem.NIL_ARRAY`) | a 5-field header is 32 bytes, a 6th `Boolean` made it 40 | −20% of every header |
| `GoDeferFrame`'s list made on the first `defer` | most frames defer nothing | −1% of allocation |

A/B, each arm its own JVM, ABBA × 2 batches, 6 warm-up + 10 measured rebuilds per process (medians of process
medians, [range], paired wins); class md5 base `5f9ff3f5` (= `e209fb352`), first three levers `c93b7187`, all six
`d0e826cf`:

| | base → first three | first three → all six | **base → all six (final run)** |
|---|---|---|---|
| compiler, single-threaded | 3,712 → 3,622 ms (−2.4%, 4/4) | −0.5% (2/4) | **3,693 → 3,632 ms (−1.6%, 3/4)** |
| compiler, allocation | 2,644 → 2,458 MB (−7.0%) | 2,422 → 2,327 MB (−3.9%) | **2,693 → 2,352 MB (−12.6%)**; GC pause −17% |
| compiler, parallel (4 checkers) | 2,142 → 2,038 ms (−4.9%, 4/4) | 2,129 → 1,974 ms (−7.3%, 4/4) | **2,057 → 2,010 ms (−2.3%, 3/4)**; GC pause −11% |
| services, parallel | | | **2,818 → 2,684 ms (−4.8%, 4/4)** |
| date-fns, parallel | | | 468 → 464 ms (noise; 98 MB/rebuild) |
| cold (compiler / services / date-fns) | 10.39 → 10.35 s | | 9.78 → 10.14 / 12.83 → 12.30 / 4.78 → 4.56 s (noise both ways) |

The allocation and GC-pause reductions are deterministic; the wall gains are real but small and sit near the
box's ±5% process spread in the parallel mode (three A/Bs read −4.9%, −7.3% and −2.3%). tsgo 7.0.2 on the same
box: 1.77 s parallel, ~3.1-3.3 s `--singleThreaded` — the warm JVM port is ~1.15-1.2x tsgo.

### 7.4 Measured and NOT done, with their sizes (the next levers)

1. **JSDoc prefix copy** — ~19% of the remaining allocation (now the largest class at 25%) and ~2-3% CPU.
   Exact semantics need the scanner and parser to see a string of length `end-2`; the scanner's own `s.end` is
   not enough (six `len(s.text)` reads and every window-lowered suffix search — `IndexByte(s.text[s.pos:], q)` —
   run to the true end). A representation change (a byte-string window type), never a rule.
2. **`LinkStore` probes** — ~8% of warm CPU in `GoMap.find`. A per-object link slot would be the JVM's way, but
   nodes are shared by four checkers and lib nodes outlive programs, so it needs a thread-safe, leak-free design.
3. **Megamorphic interface dispatch** — 4.3% in itable stubs. `nodeData`/`TypeData` as abstract CLASSES (vtable
   dispatch) is legal for the generated hierarchy (every implementer is a struct class with no superclass) but
   no other interface may extend them; the struct-embedding forwarding chains behind them
   (`this.jsDocTagBase.nodeBase.nodeDefault.localsContainerData()`) and the 4-7 objects per AST node they
   imply are the larger representation question (Go embeds by value).
4. **Immutable-struct copy elision in the porter** — a prototype analysis over the IR finds 392 of 1,635 named
   struct types never written in place and never pointer-compared/hashed/interface-converted; the hot ones are
   NOT among them: `TextRange` (pointer type-arguments, goSet locals in `ls`) and `FlowType` (`t.isNil()`'s
   implicit `&t` makes `getTypeAtFlowNode`'s `t` an address-taken local: `goSet` in place plus a copy on return,
   ~3% of allocation). The refinement that would free `FlowType` — an implicit `&x` through a pointer method
   whose receiver never escapes is not an address — is a porter rule worth ~1-2% of parallel wall.
5. **The GC** — 31% of the process's CPU warm is G1 young collection of ~2.3 GB/rebuild; every allocation lever
   above is a GC lever. Choosing a collector is the HOST's decision (the LSP/daemon JVM), not the port's.

### 7.5 Reproduce

```bash
scripts/tsgo-jvm-profile.sh warm build/bench/tsc-project-*/tsconfig.json cpu single 45 40
scripts/tsgo_ap_stacks.py build/tsgo-jvm-profile/warm-cpu.collapsed --threads --thread check-bench --mechanisms --owners
scripts/tsgo-jvm-profile.sh warm build/bench/tsc-project-*/tsconfig.json alloc single
scripts/tsgo_ap_stacks.py build/tsgo-jvm-profile/warm-alloc.collapsed --classes --allocated-by 'byte[]'
scripts/tsgo-jvm-profile.sh cold build/bench/tsc-project-637d5746 cpu
scripts/tsgo_ap_stacks.py build/tsgo-jvm-profile/cold-cpu.collapsed --threads --thread goroutine --frame-kinds
```

## 8. (TSGO.6-g) JDK 27, and the next levers (2026-10-09)

### 8.1 JDK 27 as a runtime arm

Temurin 27+35 (`tools/jdk-27`, gitignored). **Its default collector is still G1, not ZGC**
(`-XX:+PrintFlagsFinal`: `UseG1GC = true {ergonomic}`, `UseZGC = false {default}`); what changed by default is
**`UseCompactObjectHeaders = true`** (8-byte headers; it is a product flag, off by default, on JDK 26). The build
is untouched: `javaTarget` stays 25 (GraalVM has no JDK > 25 and `native-image` must read the classes), so JDK 27
runs the SAME bytecode (class md5 `d0e826cf`).

Arms (each its own JVM; two batches, each a forward rotation of the six arms then its reverse, so every arm ran
4 processes per regime early and late; warm = `CheckBenchMain` 6 warm-up + 10 measured rebuilds, `-Xms2g -Xmx6g`;
cold = one `TsgoMain --noEmit -p .`; allocation = every thread's (`getTotalThreadAllocatedBytes`), GC = pause time
(ZGC: its `… Pauses` beans only, its cycles are concurrent); every warm run one digest, every cold run
byte-identical to tsgo 7.0.2). `z26` = Zulu 26.0.2 (the box's `java`), `t26` = Temurin 26.0.2.1 (the vendor
control: CLAUDE.md records Zulu ~13% pessimistic cold), `t27` = Temurin 27 default, `t27nocoh` = `-XX:-UseCompactObjectHeaders`,
`t27zgc` = `-XX:+UseZGC` (generational), `t26coh` = Temurin 26 `-XX:+UseCompactObjectHeaders`. Medians of process
medians, [range of process medians]:

| regime | z26 | t26 | **t27** | t27nocoh | t27zgc | t26coh |
|---|---:|---:|---:|---:|---:|---:|
| compiler single, ms | 3,622 [3,510-3,810] | 3,715 | **3,506** [3,446-3,533] | 3,618 | 4,024 | 3,560 |
| compiler parallel, ms | 2,038 [1,983-2,214] | 2,016 | **1,930** [1,883-2,078] | 1,926 | 2,320 | 1,955 |
| services parallel, ms | 2,588 [2,537-2,648] | 2,630 | **2,562** [2,463-2,610] | 2,485 | 2,965 | 2,515 |
| date-fns parallel, ms | 471 | 442 | **454** | 455 | 514 | 453 |
| compiler cold, s | 10.17 | 9.86 | **10.66** | 10.31 | 10.88 | 10.42 |
| services cold, s | 11.44 | 11.58 | **11.94** | 12.40 | 12.71 | 12.01 |
| date-fns cold, s | 4.86 | 4.51 | **4.61** | 4.77 | 5.04 | 4.55 |
| compiler alloc/rebuild (parallel), MB | 2,550 | 2,532 | **2,336** | 2,508 | 2,794 | 2,312 |
| compiler GC pause/rebuild (parallel), ms | 263 | 247 | **181** | 201 | ~0 | 201 |
| compiler RSS (parallel), MB | 6,496 | 6,587 | **6,422** | 6,451 | 7,123 | 6,457 |

Readings:

- **Compact headers are a real, deterministic allocation lever: −7 to −9% of bytes** (2,508 → 2,336 MB on JDK 27,
  2,532 → 2,312 on JDK 26), exactly the port's shape (4-7 small objects per AST node). Their WALL effect is not
  resolvable here: `t27` vs `t27nocoh` is −3.1% single but +0.2% parallel and +3% services parallel.
- **JDK 27 vs JDK 26 (same vendor), warm: −5.6% single, −4.3% parallel on compiler, −2.6% services, +2.7% date-fns**
  — a small, mostly consistent warm win, about half of which is the compact headers (`t26coh` is −4.2% / −3.0% vs
  `t26`). Against the box's Zulu 26: −3.2% / −5.3% / −1.0%. Ranges overlap; the process spread here is ±3-5%.
- **Cold, JDK 27 is NOT faster**: +8% compiler, +3% services, +2% date-fns against Temurin 26 (Zulu sits between).
  The vendor control matters: Zulu → Temurin 26 alone is −3.1% / +1.2% / −7.2% cold.
- **ZGC loses everywhere for this workload**: +11-15% warm wall, +10% allocation (its load barriers and
  colour bits; no compressed oops), +10% RSS, +4-11% cold. Its pauses are ~0 ms, but the port is a batch job
  whose G1 pauses are ~200 ms of a ~2-3.5 s rebuild — there is nothing for a low-latency collector to buy.

**Decision.** JDK 27 is a modest warm improvement, not a clear one, and a cold regression; it is NOT made the
default. `scripts/tsgo-jvm-profile.sh` takes `TSGO_JAVA=<java>` so either arm can be profiled. **Proposal (owner,
not done — CI is a guardrail):** the daemon/LSP host — the warm regime — could run on JDK 27 (or on any JDK with
`-XX:+UseCompactObjectHeaders`, which buys the same −8% allocation on 26); the CLI and CI should stay where they are.

### 8.2 Levers 1 + 2: window fields and an immutable `FlowType` (landed)

**Predicted before building**: the JSDoc prefix copy is ~19% of a warm rebuild's allocation and ~2-3% CPU → −19%
allocation, −2..−4% single, −3..−6% parallel; `FlowType` ~3% of allocation → ~−1% parallel.

Both are porter rules (docs/goport-lowering.md § 3, "Window fields" / "Immutable structs"), the generated code
changed only by regeneration (8 files; `parser`/`scanner`/`checker/Flow.kt`, plus `ls/organizeimports`, whose
`s.SetText(text[fullStart:startPos])` now passes a window too). The runtime gains `goStrWin`, `goViewSubstring` and
the bounded `utf8` decoders (`goDecodeRuneIn`, `goDecodeLastRuneIn` — the latter also drops a copy
`goDecodeLastRune` made of `s[:end]`).

A/B (each arm its own JVM on Zulu 26, ABBA + BAAB, 6 + 10 rebuilds; class md5 `d0e826cf` → `d352ffe2`; every run
one digest, every cold run byte-identical to tsgo):

| | before | after | Δ |
|---|---:|---:|---:|
| compiler single, ms | 3,480 [3,410-3,551] | 3,414 [3,355-3,540] | −1.9% |
| compiler single, allocation MB/rebuild | 2,305 | **1,560** | **−32%** |
| compiler parallel, ms | 1,940 [1,937-1,945] | 1,904 [1,849-1,941] | −1.9% |
| compiler parallel, allocation (all threads) | 2,508 | **1,744** | **−30%** |
| compiler GC pause/rebuild, ms (single / parallel) | 251 / 235 | 228 / 210 | −9% / −10% |
| services parallel, ms | 2,711 [2,638-2,781] | **2,562** [2,519-2,640] | **−5.5%** |
| services parallel, allocation | 3,250 | 2,413 | −26% |
| date-fns parallel, ms | 503 | 460 | −8.6% (allocation −2%: mostly noise) |
| cold compiler / services / date-fns, s | 9.67 / 11.91 / 4.75 | 9.62 / 12.18 / 4.35 | noise |
| cold RSS compiler, MB | 2,356 | 1,908 | −19% |

The allocation drop is larger than predicted (−32% vs −19%): the § 7 census charged the prefix copies to
`byte[]` only, while each comment also paid the `String` object, the scanner's `goDecodeLastRune` copy and the
humongous-region churn. Allocation profile after the change (async-profiler, warm single): `byte[]` 7.1% (was 25%),
`FlowType` 2.0% of a smaller total (~31 MB, was ~120 MB: what remains are the constructions Go also makes).
Wall gains are real but small for the compiler profile (inside the ±3% process spread) and clear for services.

### 8.3 Lever 3: `LinkStore` per-object slots — REFUSED (design)

`core.LinkStore[K, V]` is a `map[K]*V` per checker per link kind (26 stores: 9 keyed by `*ast.Node`, 16 by
`*ast.Symbol`, 1 by `*ast.SourceFile`). § 7.2 measured `GoMap.find` at 11.6% of the warm bench thread, 55% of it
under `LinkStore.Get` (~6.4% of the thread). The JVM's way to answer "this object's link" is a field on the
object. Every variant of that is unsound or leaks here:

- **Nodes and symbols outlive their checker.** The bundled libs' `SourceFile`s (and the symbols their binder
  made) are shared across programs (the bench's `libcache`, the LSP, the API session, and `-core`'s
  `RealLibSnapshots`), and an LSP reuses unchanged files' trees across program versions. A slot holding
  `(store, value)` therefore retains a dead checker — and through `value` its whole type graph — until something
  overwrites it; a lib node no later checker touches keeps it forever. A `WeakReference` per slot costs an
  allocation per install and a dereference per read, which is the price being saved.
- **Four checkers read and write the same nodes concurrently** (`checker.CheckerPool`). A slot table needs a CAS on
  install, and losing a race must never drop a link: links carry the checker's in-progress flags for cycle
  detection (`resolvedType` sentinels), so a link that vanishes mid-resolution is a wrong answer or a non-
  terminating resolution, not a recomputation.
- **A slot as a cache in front of the authoritative map** (the only sound shape — the map keeps every entry, the
  slot just short-circuits the probe) still pays the map insert, still retains one stale pair per object, and
  helps only the one store that owns the slot; 26 stores × 4 checkers compete for it.
- **Dense arrays indexed by `Node.id`/`Symbol.id`** would need the id read through `ast.GetNodeId`, which ASSIGNS
  ids lazily (an atomic increment): reading it where Go does not shifts every later id away from tsgo's
  assignment order (ids key the node builder's and the emit resolver's caches). Process-global ids also grow
  without bound in a long-lived host, so the arrays would be sparse.

The cost is real (~6% of a warm thread is one identity-hash probe per link read, the same probes Go makes — Go's
are cheaper because its map hashes the pointer without a header read), but no design keeps both soundness and
bounded retention. Not built.

### 8.4 Lever 4: megamorphic interface dispatch — sized, not built (built in § 11)

Warm CPU profile after § 8.2 (single-threaded, 36,359 bench-thread samples): itable stubs **4.38%** of the thread,
callers: `ast.locals()` → `localsContainerData()` 22%, `asStructuredType` (from `resolveStructuredTypeMembers`)
14%, `modifiers()` 9%, `declarationData()` 7%, `GoElem.zeroValue` (from `Arena.new`) 6%, `flowNodeData()` 7%,
`asObjectType` 3%, the rest < 2% each. Turning `ast.nodeData` and `checker.TypeData` into abstract classes would
replace those itable calls by vtable calls (a megamorphic vtable stub is cheaper, not free): ~75% of 4.4% is
reachable, so the prize is **≤ ~1.5-2% of the thread**. The change is a representation one in the porter (every
implementer must extend the class, no interface may extend it, promoted forwarding chains stay), touching every
AST node class. Not built this round; the larger lever behind it is the 4-7 objects per node (§ 7.4 item 3).
`runtime:GoMap` is now 15.5% of the thread (the `LinkStore` probes of § 8.3 are about half).

### 8.5 Lever 5: the AOT cache for a packaged launcher (landed)

`scripts/xtsc-tsgo` runs the ported CLI (`TsgoMainKt`) through the SAME guard as `scripts/xtsc`
(`scripts/xtsc-aot-lib.sh`: content-hashed classpath, JDK build, OS, main class, cache digest; fail-safe), with
its own cache directory (`$XDG_CACHE_HOME/xtsc-tsgo`, so neither launcher's `train`/`clean` prunes the other's).
`scripts/xtsc-tsgo-aot stage` builds `jvmJar` and copies it with its runtime jars (just `kotlin-stdlib` and
`annotations`) into `build/xtsc-tsgo/lib` through a Gradle init script — no build change; `train <project>` runs
one emitting check with `-XX:AOTCacheOutput`. No new module or dependency. Cold, trained on the compiler profile
(Zulu 26, 4 alternating runs per arm, every output byte-identical to tsgo; `aot USE` printed on every cached run):

| project | uncached | cached | Δ |
|---|---:|---:|---:|
| compiler (trained on) | 9.62-10.18 s | 6.30-6.62 s | **−34%** |
| services | 11.17-12.27 s | 7.54-7.74 s | **−33%** |
| date-fns (held out) | 4.37-4.82 s | 3.60-3.69 s | **−20%** |

Controls: a cache is refused (`SKIP no-cache-file`, i.e. runs uncached) when any classpath jar changes by one byte;
any rebuild of the jar changes its bytes (CLAUDE.md), so re-`stage` and re-`train` after one.

```bash
scripts/xtsc-tsgo-aot stage && scripts/xtsc-tsgo-aot train build/bench/tsc-project-637d5746
XTSC_AOT_VERBOSE=1 scripts/xtsc-tsgo --noEmit -p <project>
```

## 9. (TSGO.6-h) An IO dispatcher beside the compute one — REFUSED, measured (2026-10-10)

**The question** (owner): would running file IO on an IO dispatcher and computation on the default (bounded) one help,
as it did in an isolated test elsewhere?

**The design it would change.** The port has no coroutines. A goroutine is a pooled daemon PLATFORM thread
(`startGoroutineThread`: an unbounded cached `ThreadPoolExecutor`, 1 GB stacks) that runs only while holding one of
N `GoProcs` run tokens (N = `TSGO_GOMAXPROCS`, default the core count) — that token pool IS the bounded compute
dispatcher, and threads beyond N exist only for BLOCKED goroutines. Every indefinite block (`WaitQueue`, `onGoStack`)
gives the token back through `blockingWait { }`. File IO did NOT: `vfs/osvfs` bounds its syscalls with three
`LimitedSemaphore`s (128 / 128 / 32) and the call itself runs on the goroutine holding its token. The non-goroutine
caller (the CLI/bench thread: config parse, include glob) holds no token. Since this round every host file-system
call (`go/os`, `internal_nativepath`) goes through one funnel, `syscall()` (`go/os/Syscall.kt`); `TSGO_IO_STATS=1`
prints `io: read=<calls>/<ms> stat=… list=… realpath=… write=… total=… bytes=…` (CLI stderr, `CheckBenchMain` stdout).

**IO share** (JDK 26 Zulu, 8 cores, page cache warm; nanoseconds summed over ALL threads):

| run | calls | IO time | of |
|---|---:|---:|---|
| compiler cold CLI | 572 (124 reads, 10.4 MB) | 177 ms | 10.0 s wall, 65 s CPU |
| services cold CLI | 1,252 (298 reads, 13.4 MB) | 68 ms | 11.9 s wall |
| date-fns cold CLI | 11,595 (1,456 reads, 8,844 stats, 1,292 lists) | 220 ms | 4.6 s wall |
| date-fns warm, `osfs` (every rebuild re-reads through osvfs) | ~36,600 / rebuild (~34,000 stats) | ~244 ms / rebuild | ~470 ms wall |
| any warm `CheckBenchMain` default (`DiskFS` caches content) | 0 through the shim | — | — |

So only the warm `osfs` date-fns case has a real IO share; everywhere else the upper bound of any overlap gain is the
IO time over N ≈ 10-25 ms, under the ±3-5% process spread.

**What was tried** (experiment commit `1820a9de1`, `TSGO_IO_DISPATCH`, same binary for every arm): `release` gives the
run token back around each host call (`blockingWait`: Go's P hand-off, made EAGER), `pool` additionally runs the call
on a dedicated unbounded IO thread pool while the goroutine waits without a token (the literal "Dispatchers.IO" split).

A/B, each arm its own JVM, batch 1 `inline release pool pool release inline`, batch 2 the reverse; warm = 6 + 10
parallel rebuilds; every warm run one digest, every cold run byte-identical to tsgo 7.0.2. Medians of process
medians [process medians]:

| regime | inline (shipped) | release | pool |
|---|---:|---:|---:|
| date-fns warm `osfs`, ms | **470** [435, 493, 457, 483] | 756 [801, 736, 768, 744] (**+61%**, 4/4 lost) | 1,122 [1,183, 1,101, 1,127, 1,118] (**+139%**, 4/4 lost) |
| date-fns warm, allocation all threads MB/rebuild | 752-756 | 1,725-1,763 (+130%) | 1,707-1,729 |
| date-fns cold, ms | 4,601 [4,608, 4,211, 4,869, 4,594] | 4,931 [4,946, 4,915, 4,949, 4,437] (+7%, 3/4 lost) | 4,956 [4,864, 5,047, 5,168, 4,866] |
| compiler cold, ms (batch 1) | 9,842 [9,775, 9,908] | 9,970 [10,308, 9,632] | 10,142 [9,992, 10,291] |
| compiler warm `osfs`, ms (ABBA, 4 processes) | 1,861 [1,881, 1,842] | 1,900 [1,928, 1,872] (noise) | — |

**Mechanism.** (1) A page-cached `stat`/`read` is KERNEL CPU work on the calling core (~4.6 µs per stat here), not a
wait: there is no idle core for a released token to fill, so the split can only add cost. An IO dispatcher pays off
when IO blocks on a device or the network — which is what an isolated test with real latency measures. (2) The token
churn is not free: a released token goes to the queue head, so after EVERY call the IO goroutine re-queues FIFO
(`queuedAcquires` 2,145 → 234,352 over 6 rebuilds, goroutine threads 842 → 1,431). Go avoids this because it hands its
P off only for a syscall still running after ~20 µs (sysmon), and on return re-takes its old P if idle. (3) With more
loader goroutines in flight at once, tsgo's unsynchronised check-then-read caches herd: the project's `package.json`
was read **174 times per rebuild instead of 8** (`undici-types/package.json` 3 instead of 1) — +33 MB of reads and
+1 GB of allocation per rebuild. Go tolerates the race by design; widening the window is what makes it expensive.
(4) The `pool` arm adds a thread hand-off per call on top (the (INC.64)/(INC.56) finding again: dispatcher hops for
sub-10 µs work are pure overhead).

**Kept**: the `syscall()` funnel and `TSGO_IO_STATS` (an instrument; off, it is one boolean read per host call) and
`CheckBenchMain`'s `osfs` mode. **Removed**: the dispatch switch and the IO pool. If a host ever serves the port from
a slow file system (network mount, cold disk), the lever to try is Go's LAZY hand-off — release the token only for a
call still running after ~20 µs — never an eager per-call hop.

```bash
TSGO_IO_STATS=1 java -cp <cp> com.xemantic.typescript.tsgo.cli.TsgoMainKt --noEmit -p <project>
TSGO_IO_STATS=1 java -cp <cp> com.xemantic.typescript.tsgo.CheckBenchMainKt <tsconfig.json> 6 10 nolib parallel osfs
```

## 10. (TSGO.6-i) A fresh warm profile after § 8.2 (2026-10-10)

Same instruments as § 7 (async-profiler 4.1, `scripts/tsgo-jvm-profile.sh` + `scripts/tsgo_ap_stacks.py`), HEAD
`bac36aaad` (class md5 of the sorted `main` class files `e97aa44e`), Zulu 26, 8 cores, `-Xms2g -Xmx6g`, G1; every
profiled rebuild printed the same diagnostics/digest (compiler 65 / `78feefbb`, services 65 / `ccb3b4c2`).

**Process CPU, compiler single-threaded** (65,775 samples over 40 s): the bench thread **57.2%**, G1 workers **27.4%**
+ concurrent marking **9.2%**, C2 6.3% — the § 7.2 shape (57 / 31 + 6 / 6); GC is still a third of the process.
**Services parallel** (132,463): goroutines 60.0%, G1 24.3% + 11.4%, C2 3.9%.

**The thread, by leaf mechanism** (single = compiler, bench thread, 37,595 samples; parallel = services, goroutines, 79,493):

| mechanism | single | parallel | § 7.2 (before § 8.2) | what |
|---|---:|---:|---:|---|
| generated checker code | 38.3% | 39.0% | 36.7% | flat; the largest owner `getResolvedSymbolImpl` 2.9% / 3.6% |
| `runtime.GoMap` | **15.4%** | 12.6% | 14.3% | `find` 14.3% / 10.6% of the thread; **56% / 46% of it under `core.LinkStore.Get`** (§ 8.3, refused), then `Relater.get` 9%, `getMergedSymbol` 6%, `getPropertyOfTypeEx` 6%, `internIdentifier` 4% |
| generated `ast` | 14.2% | 14.1% | 13.3% | `visitNodeList`, `asIdentifier`, `forEachChild`, `localsContainerData`: flat |
| `runtime.GoSlice` | 5.1% | 5.5% | 4.9% | `load` 1.6%, `slice`, `append1`, `len` |
| **itable stubs** | **4.1%** | **3.8%** | 4.3% | `nodeData` callers ~55% (`localsContainerData` 22%, `modifiers` 8%, `declarationData` 8%, `flowNodeData` 8%), `TypeData` ~21% (`asStructuredType` 16%, `asObjectType` 3%), `GoElem.zeroValue` (Arena) 6%, Kotlin `Function1` (`filterType`, `mapTypeEx`) 3% |
| `xxh3` shim (`go-shim:github_com`) | 2.6% | 2.2% | — | relation/union cache keys |
| binder / scanner / parser | 2.5 / 2.0 / 2.0% | 3.1 / 1.9 / 2.3% | 2.9 / 1.8 / 1.9% | |
| `java.lang.String` | 2.1% | 1.8% | 1.2% | `equals` of name-keyed tables |
| comparator lambda (`compareSymbols`) | 2.7% (owner) | 1.0% | — | `compareTypes` → `checker.compareSymbols!!(…)`: a func-typed FIELD called through `Function2.invoke`, whose `Int` result is boxed |
| vtable stubs | 0.6% | < 0.6% | — | `Intrinsics.areEqual` from `isSimpleTypeRelatedTo` / `GoMap.find` |

**Allocation, compiler single** (1,559 MB/rebuild, 23,167 samples): `GoSlice` headers **21.9%** (`append1` in
`getTypeAtFlowNode` 9% of them, `putRelater`'s `slice` 7%, `filterType`/`mapTypeEx` 4.5% each), `Object[]` 9.2% (map
`rehash` 27% of it, slice `grow` 24%), `byte[]` 6.9% (**74% the `xxh3.Hasher` key buffers**, 15% `scanIdentifier`'s
`goViewSubstring`), `TextRange` 5.3% (three per node, § 7.2), `Integer` **3.0%** (85% the `compareSymbols` boxing above),
`Tuple2` 2.9%, `Node` 2.8%, `xxh3.Uint128` 2.5%, `Type[]` 2.5%, `xxh3.Hasher` 2.3%, `atomic.Uint64` 2.0% (one per Node
and Symbol), `int[]` 1.9%, `FlowType` 1.8%, `SharedFlow` 1.6%, `Relater` 1.4%, `keyBuilder` 0.8%.

**What changed since § 8.2**: the JSDoc prefix copies are gone (`byte[]` 25% → 6.9%, its remainder now the relation-key
hasher); `GoMap` is the largest mechanism at 15% of the thread and half of it is still the `LinkStore` probe § 8.3
refused; the itable share is unchanged (4.1-4.4%) and its callers are the same — the lever § 8.4 sized; `Integer` boxing
is back at 3% of allocation, not through `BinarySearchFunc`'s comparator (inline since § 7.3) but one call further in,
through the `compareSymbols` func-typed field. GC is still ~35% of the process's CPU warm; nothing in the profile is a
new single mechanism above ~3%.

```bash
scripts/tsgo-jvm-profile.sh warm build/bench/tsc-project-637d5746/tsconfig.json cpu single 45 40
scripts/tsgo-jvm-profile.sh warm build/bench/tsc-project-637d5746/tsconfig.json alloc single 45 40
scripts/tsgo-jvm-profile.sh warm build/bench/tsc-services-637d5746/tsconfig.json cpu parallel 45 40
scripts/tsgo_ap_stacks.py <collapsed> --thread check-bench --mechanisms --owners          # --thread goroutine for parallel
scripts/tsgo_ap_stacks.py <collapsed> --thread check-bench --callers 'itable stub'
scripts/tsgo_ap_stacks.py <alloc collapsed> --classes; … --allocated-by 'byte[]'
```

## 11. (TSGO.6-i) Lever 4: `nodeData` and `TypeData` as abstract classes (landed) — and the allocation levers left

**The change** is a porter rule (docs/goport-lowering.md § 3, "Abstract-class interfaces"; `Program.computeAbstractIfaces`):
an interface in `ABSTRACT_IFACE_CANDIDATES` becomes an `abstract class` when the whole run proves every implementer is a
struct class, none implements two of them, and no interface extends one. Both candidates pass (213 AST data classes,
24 checker type classes, regenerated: 6 files, every implementer `: nodeData()` / `: TypeData()`, forwarding overrides
unchanged). Calls such as `ast.locals()` → `data.localsContainerData()` are now `invokevirtual`.

**Predicted before measuring**: itable stubs are 4.1% of the thread and ~75% of them are these two (≈ 3.0%); a
megamorphic vtable stub costs roughly a third to a half of an itable scan, so ≈ −1.5..−2% of the thread's samples, wall
−1..−2% (inside the ±3-5% process spread), allocation and GC unchanged (no object changes shape).

**Mechanism, measured** (warm compiler single, bench thread, same harness as § 10, arm B 37,560 samples):

| | before | after |
|---|---:|---:|
| itable stubs | 4.09% | **0.92%** (left: `GoElem.zeroValue` from `Arena.new` 23% of it, Kotlin `Function1` in `filterType`/`instantiateType` ~20%, `forEachChild` visitors) |
| vtable stubs | 0.60% | 2.34% (the same callers: `localsContainerData` 17%, `asStructuredType` 15%, `modifiers` 10%, `declarationData` 8%, `flowNodeData` 10% of it) |
| both | 4.69% | **3.26% (−1.43 points)** |

A/B (each arm its own JVM, batch 1 `A B B A`, batch 2 `B A A B`, warm = 6 + 10 rebuilds, Zulu 26; class md5 of the sorted
`main` class files A `e97aa44e` (= `bac36aaad`), B `ca5f97c6`; every warm run one digest, every cold run byte-identical
to tsgo 7.0.2; medians of process medians [range], B's paired wins of 4; cold pooled with a third rotated batch, 8
processes per arm for compiler/services):

| regime | A (interfaces) | B (abstract classes) | Δ |
|---|---:|---:|---:|
| compiler single, ms | 3,460 [3,454-3,545] | 3,465 [3,384-3,554] | +0.2% (2/4) |
| compiler parallel, ms | 1,955 [1,889-1,983] | 1,918 [1,876-1,989] | −1.9% (2/4) |
| services parallel, ms | 2,527 [2,450-2,563] | 2,506 [2,410-2,530] | −0.8% (4/4) |
| date-fns parallel, ms | 464 [446-503] | 448 [445-465] | −3.5% (4/4) |
| compiler cold, ms (8 + 8) | 9,884 [9,516-10,676] | 9,901 [9,182-10,222] | +0.2% |
| services cold, ms (8 + 8) | 11,795 [10,855-12,704] | 11,843 [11,018-12,400] | +0.4% |
| date-fns cold, ms | 4,648 [4,084-4,870] | 4,671 [4,101-4,842] | +0.5% |
| allocation/rebuild, MB (single / parallel / services) | 1,559 / 1,762 / 2,414 | 1,566 / 1,758 / 2,458 | noise (no shape changed) |
| GC pause/rebuild, ms (single / parallel / services) | 224 / 222 / 304 | 196 / 214 / 312 | noise |

**Reading.** The dispatch moved exactly as predicted (−1.4 points of the thread in stubs); the wall did not resolve it —
every warm delta is inside the ±3-5% process spread (single +0.2%, parallel −0.8..−3.5%), and cold is noise both ways
(the first cold batch read compiler +2.6%, the rotated replication −7.8%; pooled +0.2%). Kept: it is free (no shape,
allocation or answer changes, every gate equal) and removes the largest stub class from every later profile.

**Step C — allocation and GC.** Lever 4 moves neither (above). The levers the § 10 allocation profile leaves, sized
against 1,559 MB/rebuild (compiler single), none a small runtime-shim change with a clear > 3% win, so none built:

| lever | share | what it takes |
|---|---:|---|
| `GoSlice` headers | 21.9% | a representation change (Go slices are 24-byte VALUES; here every `append`/`s[a:b]` is a header object stored in a field), not a rule |
| relation-key hashing (`xxh3.Hasher` buffer 5.1% + `Hasher` 2.3% + `Uint128` from `hashBytes128` 1.8% + `keyBuilder` 0.8%) | ~10% | Go keeps the key builder on the stack. A shim cannot reuse a buffer per thread: a `Hasher` may be written again after `Sum128`, so a lent buffer would have to be copied back out whenever another hasher borrows it. A porter rule that proves the `b := keyBuilder{}` locals never escape (then one builder per checker, `Reset()` per key) is the sound shape: ~8% of allocation |
| `TextRange` | 5.3% | three per node, two of them overwritten at once; needs `TextRange` immutable, which § 7.4 found refused (pointer type arguments, `goSet` locals in `ls`) — ~3.5% if the proof can be widened |
| `compareSymbols` boxing (`Integer`) | 3.0% | a func-typed FIELD `func(*Symbol, *Symbol) int` is a `Function2`, so every call boxes its `Int`; a porter rule lowering a func-typed field/parameter with basic results to a primitive `fun interface` removes it (and part of the lambda's 2.7% of CPU) |
| `atomic.Uint64` | 2.0% | one object per Node and Symbol; embedding it as a `@Volatile long` + field updater in the owner class is a porter rule |

## 12. (TSGO.6-j) Two porter rules from § 11's allocation table (2026-10-10)

### 12.1 Lever A: pooled `keyBuilder` locals (relation/cache-key hashing)

**The change** is a porter rule (docs/goport-lowering.md § 3, "Pooled locals"; `Program.computePooledLocals`,
`lower/PooledLocals.kt`): an ESCAPE PROOF over the Go IR shows that a `var b keyBuilder` local never outlives
its function — every use is a call of a pointer method whose receiver is proved non-retaining, a field select
feeding a non-retaining shim method (`b.h.Write`) or `&b.h` / `&b` handed straight to a parameter proved
non-retaining (`hashWrite32`, `writeFlowCacheKey`), and a capture only by a LOCAL closure (assigned to a
variable that is only called: `writeGenericTypeReferences`); the function has no `go` and no `defer`. Such a
local is lowered to `val b = keyBuilder.POOL.stack().acquire()` + `try { … } finally { release() }`: a
per-thread LIFO stack (`runtime.GoLocalPool`) of builders reset to Go's zero in place (`goReset()`, the Hasher
keeping its buffer's capacity). The stack is per thread and LIFO, so the checker re-entering key building
while a key is half written (`writeGenericTypeReferences` → `getConstraintOfTypeParameter` → … →
`getRelationKey`) takes the next slot, and four parallel checkers never share one. First run: **13 of 13**
`keyBuilder` locals pooled, none refused; 14 pointer parameters proved non-retaining (the 11 `keyBuilder` methods'
receivers, `hashWrite32`/`hashWrite64`'s `h` and `writeFlowCacheKey`'s `b`).

**Predicted before measuring** (against § 10's 1,559 MB/rebuild compiler single): the `keyBuilder` (0.8%), the
`xxh3.Hasher` (2.3%) and its byte buffer (5.1% — 74% of `byte[]`) go, the `Uint128` result (1.8%) stays:
**−7..−8% allocation** (≈ −120 MB/rebuild single, proportionally parallel/services); GC pause per rebuild
**−3..−8%** (young collections ~8% rarer, each about as costly — the survivors are unchanged); on the thread a
`ThreadLocal` lookup + a 4-field reset replace three allocations and the buffer's first `copyOf` growth, so the
xxh3 shim's 2.6% shrinks a little: **wall −0.5..−2%, inside the ±3-5% process spread** — the receipt is the
allocation, not the wall.

**Measured** (A/B, each arm its own JVM, batch 1 `A B B A`, batch 2 `B A A B`, a third rotated batch `A B B A` for
compiler single, date-fns and cold; warm = 6 + 10 rebuilds, Zulu 26, `-Xms2g -Xmx6g`; class md5 of the sorted `main`
class files A `ca5f97c6` (= `32ef53e7e`), B `a47db82b`; every warm run one digest, every cold run byte-identical to
tsgo 7.0.2; medians of process medians [range], B's paired wins):

| regime | A | B (pooled) | Δ | B wins |
|---|---:|---:|---:|---:|
| allocation/rebuild, compiler single, MB | 1,563 | **1,439** | **−7.9%** | 6/6 |
| allocation/rebuild, compiler parallel, MB | 1,729 | **1,570** | **−9.2%** | 4/4 |
| allocation/rebuild, services parallel, MB | 2,410 | **2,219** | **−7.9%** | 4/4 |
| allocation/rebuild, date-fns parallel, MB | 645 | 622 | −3.6% | 6/6 |
| GC pause/rebuild, single / parallel / services, ms | 204 / 237 / 302 | 196 / 202 / 296 | −3.9% / **−14.8%** / −2.2% | 3/6, 4/4, 4/4 |
| compiler single, ms | 3,350 [3,308-3,588] | 3,408 [3,296-3,575] | +1.7% | 2/6 |
| compiler parallel, ms | 1,912 [1,890-1,945] | 1,855 [1,768-1,895] | −3.0% | 4/4 |
| services parallel, ms | 2,547 [2,480-2,586] | 2,473 [2,406-2,551] | −2.9% | 3/4 |
| date-fns parallel, ms | 452 [434-496] | 462 [426-484] | +2.2% | 3/6 |
| compiler cold, ms (6 + 6) | 9,799 [8,960-10,315] | 9,894 [8,940-10,576] | +1.0% | 2/6 |
| services cold, ms (6 + 6) | 11,491 [10,908-12,618] | 12,003 [11,306-12,537] | +4.5% | 1/6 |
| date-fns cold, ms | 4,607 [4,489-4,740] | 4,483 [4,363-4,953] | −2.7% | 2/4 |

**Reading.** The allocation moved exactly as predicted (−7.9% single, −8..−9% parallel; date-fns −3.6% because its
checks build fewer keys per MB), GC pause follows it in the parallel regimes (−15% compiler parallel) and is inside
noise single-threaded. The wall is inside the ±3-5% process spread in every regime and both directions (parallel
−3%, single +1.7% at 2/6, cold mixed); the mechanism's own cost was profiled on the B arm (warm compiler single, bench
thread, 37,532 samples): `GoLocalStack.acquire` + `ThreadLocal.get` + `GoLocalPool.stack` are **0.33%** of the thread,
the `xxh3` shim fell 2.6% → 2.3%, so the single-threaded +1.7% is not the pool. Kept: −8% allocation at no measurable
cost, every gate equal.

### 12.2 Lever B: a func-typed field with a basic result as a primitive `fun interface` (`compareSymbols`)

**The change** is a porter rule (docs/goport-lowering.md § 3, "Primitive func fields"; `Program.computePrimFuncFields`):
a func-typed struct field in `PRIM_FUNC_FIELD_CANDIDATES` (`checker.Checker.compareSymbols`, `func(*ast.Symbol,
*ast.Symbol) int`) is lowered as a generated `fun interface Checker_compareSymbols_Fn { operator fun invoke(…): Int }`
instead of a Kotlin `(Symbol?, Symbol?) -> Int` (a `Function2` whose `invoke` returns `Object`, so every call boxes
the `Int` and unboxes it again) when the run proves the porter writes every producer and consumer of the field:
assigned only a method value, a function literal or nil (each SAM-converted at the assignment, so the lambda's own
method returns `int`), and read only as a callee, in a nil comparison, or as a call argument (adapted back to a Kotlin
function type at that argument — `slices.SortFunc`, `core.GetSpellingSuggestion`, cold paths). Any other read or
write refuses the field.

**Predicted before measuring** (against § 12.1's B arm, 1,439 MB/rebuild compiler single): boxed `Integer` was 3.0%
of § 10's allocation and 85% of it this field's results (`compareNodes` returns position differences, outside
`Integer`'s cache): **−2..−2.5% allocation** (≈ −35 MB/rebuild single, less parallel — `compareTypes` runs while
unions are interned, everywhere); GC pause inside noise; on the thread the `Function2` bridge + `Integer.valueOf` +
`intValue` go from each comparison (the comparator lambda owned 2.7% single / 1.0% parallel, most of it the
comparison itself): **wall −0.3..−1%, inside the ±3-5% spread**.

**Measured** (same protocol as § 12.1, batches `A B B A` + `B A A B`; A = § 12.1's B arm, class md5 `a47db82b`
(= `167e891c2`), B `0f4825e5`; every warm run one digest, every cold run byte-identical to tsgo 7.0.2; the
`newChecker` lambda's bytecode is `LambdaMetafactory` over `(Symbol, Symbol)I` and `CompareTypes` calls
`Checker_compareSymbols_Fn.invoke(…)I`, i.e. nothing is boxed on that path):

| regime | A | B (fun interface) | Δ | B wins |
|---|---:|---:|---:|---:|
| allocation/rebuild, compiler single, MB | 1,437 | **1,394** | **−3.0%** | 4/4 |
| allocation/rebuild, compiler parallel, MB | 1,584 | 1,573 | −0.7% | 3/4 |
| allocation/rebuild, services parallel, MB | 2,258 | **2,128** | **−5.7%** | 4/4 |
| allocation/rebuild, date-fns parallel, MB | 622 | 626 | **+0.7%** | 0/4 |
| GC pause/rebuild, single / parallel / services, ms | 211 / 212 / 306 | 192 / 208 / 300 | noise | 2/4, 1/4, 2/4 |
| compiler single, ms | 3,352 [3,304-3,456] | 3,342 [3,279-3,448] | −0.3% | 2/4 |
| compiler parallel, ms | 1,848 [1,824-1,897] | 1,865 [1,797-1,957] | +0.9% | 1/4 |
| services parallel, ms | 2,490 [2,439-2,505] | 2,439 [2,423-2,554] | −2.0% | 3/4 |
| date-fns parallel, ms | 432 [421-450] | 448 [421-466] | +3.5% | 1/4 |
| compiler / services / date-fns cold, ms | 9,813 / 11,785 / 4,337 | 9,598 / 11,907 / 4,560 | −2.2% / +1.0% / +5.2% | 3/4, 2/4, 1/4 |

**Reading.** The boxing went where the field is CALLED (compiler single −3.0%, services −5.7% — more than predicted:
services sorts more union members), and the wall is inside the spread everywhere. The cost is the CONSUMER adapter:
`sortSymbols` (`slices.SortFunc`, not inline) and `getSpellingSuggestion` now wrap the interface in a fresh Kotlin
function per call (and still box per comparison inside `SortFunc`, as before), which is date-fns' +0.7% (≈ +4 MB) and
compiler parallel's smaller gain. Kept: the large projects gain 43-130 MB/rebuild, the small one loses 4 MB, every
gate equal. The adapter is the next thing to remove (a comparator overload of the shim's `SortFunc` taking the
interface would make both sites allocation-free).

## 13. (TSGO.6-k) Slice headers owned by their field, struct literals stored in place — and the cold JIT measured (2026-10-10)

### 13.1 Where the slice headers came from

A fresh warm allocation profile (HEAD `98b01e628`, compiler single, 21,181 samples) put `GoSlice` headers at **24.7%** of
allocation. By site: `append1` in `getTypeAtFlowNode` 8.9% of them (`c.sharedFlows = append(c.sharedFlows, …)`),
`slice` in `putRelater` 7.4% (`*r = Relater{maybeKeys: r.maybeKeys[:0], …}`), `slices.Insert`'s `GoSlice.of` 4.9% + its
sub-slices 1.6% (a shim composing four headers and a temporary), `filterType`/`mapTypeEx`'s local builders, and a tail of
`c.X = c.X[:n]` stack pops (`popActiveMapper`, `getTypeAtFlowBranchLabel`, `getFlowTypeOfReferenceEx`). Go writes a
24-byte header VALUE into the field; the port allocated a 32-byte header object per append and per pop.

### 13.2 What landed

| change | kind | what |
|---|---|---|
| `slices.Insert`, `slices.Clone` on the backing array | runtime shim | one header (plus one array when growing) instead of four headers, a temporary slice and a snapshot array |
| owned slice fields (docs/goport-lowering.md § 3) | porter rule | 24 fields, 42 in-place sites: a self-append/self-reslice updates the field's own header in place |
| struct literals stored in place | porter rule | `*p = T{…}` (125 sites; tsgo's pooled `Relater`/`FlowState`/`InferenceState`) assigns `*p`'s fields — no temporary object, no second copy of its struct fields, a self-field skipped |

**Predicted before measuring**: the owned fields' sites are ~26% of headers and the pool stores ~2% of allocation
(the temporary objects + `maybeKeysSet`'s copy): **−7..−9% allocation**, wall −1..−3% (inside the spread).

**Measured** (`scripts/tsgo-ab-warm.sh`, each arm its own JVM, `A B B A` + `B A A B`, 6 + 10 rebuilds, Zulu 26,
`-Xms2g -Xmx6g`; A = HEAD `98b01e628`, B = this change; every run one digest — compiler `78feefbb`/65, services
`ccb3b4c2`/65; medians of process medians [range], B's paired wins):

| regime | A | B | Δ | B wins |
|---|---:|---:|---:|---:|
| compiler single, ms | 3,912 [3,828-3,967] | 3,730 [3,647-3,768] | **−4.6%** | 4/4 |
| compiler single, allocation MB/rebuild | 1,396 | 1,237 | **−11.4%** | 4/4 |
| compiler parallel, ms | 2,260 [2,106-2,304] | 2,110 [2,049-2,193] | **−6.6%** | 4/4 |
| compiler parallel, allocation (all threads) | 1,582 | 1,410 | **−10.8%** | 4/4 |
| services parallel, ms | 2,883 [2,698-3,033] | 2,730 [2,631-2,837] | **−5.3%** | 3/4 |
| services parallel, allocation (all threads) | 2,178 | 1,932 | **−11.3%** | 4/4 |
| GC pause/rebuild, single / parallel / services, ms | 225 / 245 / 317 | 206 / 236 / 288 | −8% / −4% / −9% | 3/4, 3/4, 4/4 |

The first allocation lever in this arc whose WALL effect clears the process spread in every warm regime: the single
arm's ranges do not overlap. Allocation beat the prediction (−11% against −7..−9%): the in-place pool stores also
dropped the `maybeKeysSet` copy and the temporaries' own nested zero values.

### 13.3 Measured and NOT done: the cold JIT ramp (no code change)

Cold one-shot `tsc --noEmit -p` (Zulu 26, the frozen HEAD classes, arms alternated, every output byte-identical):

| | default tiering | `-XX:TieredStopAtLevel=1` (C1 only) | AOT cache | AOT cache + C1 only |
|---|---:|---:|---:|---:|
| compiler (cache trained here), ms | 9,900 | 6,480 | **5,990** | 5,990 |
| services, ms | 11,940 | 8,030 | | |
| date-fns core (held out), ms | 4,470 | 2,530 | 3,500 | **1,890** |
| type-fest (held out; a long type-level run), ms | 24,670 | **48,760** | 23,980 | 48,070 |

- **C1-only is −33..−44% cold on every ordinary project and +100% on type-fest**: non-profiled C1 code is reached at
  once, where tiered compilation keeps code in slow PROFILED C1 for most of a 10 s run — but C2's code is ~2x faster on
  a long computation. Neither middle setting helps: C2 promoted 10x later (`Tier4*Threshold` x10) reads 11.1-12.6 s on
  compiler (worse — the methods stay longer in profiled code) and `-XX:CICompilerCount=2` 13.2-13.5 s.
- **The AOT cache the launcher already ships (§ 8.5) is the robust choice**: equal to C1-only on the trained project,
  never worse anywhere. A per-project switch would have to predict run length, which no file count does (type-fest
  has 440 roots). JDK 27 has no AOT method-code cache (`AOTCodeCaching` is unrecognized; only stub/adapter caching).
- **`-XX:+UseParallelGC`**: warm single −8.4% (2/2), warm parallel −1.0% (noise), cold +6% — the host's (daemon, LSP)
  choice for a single-threaded warm regime, not a launcher default.

## 14. (TSGO.6-l) The link store filed by a dense index — § 8.3's refusal revisited (2026-10-10)

The profile after § 13 (warm compiler single, bench thread): `GoMap.find` **15.6%** of the thread, **54%** of it under
`core.LinkStore.Get` (~8.4% of the thread). § 8.3 refused per-object link SLOTS on three grounds — a slot on a node
outlives its checker (lib nodes are shared across programs), four checkers write the same nodes, and dense arrays by
tsgo's `Node.id` would change when ids are assigned. A DENSE INDEX owned by the port avoids all three:

- `ast.Node` and `ast.Symbol` extend `runtime.GoLinkKey` (a porter rule, `LINK_KEY_TYPES`), whose `goLinkIndex` is
  numbered at construction from per-thread blocks of 1,024 — a file's nodes, built by one goroutine, get consecutive
  indices. It is not tsgo's lazily assigned id, and nothing observable reads it.
- The `core.LinkStore` override files a key with an index in a `GoLinkTable` (three levels: 64 K indices per directory
  entry, 64 per leaf, each allocated on first write) owned by the STORE, i.e. by one checker, as the map was: no
  retention past the checker, no sharing between checkers. Every other key (`*ast.SourceFile` stores, a spent index
  past 2^31 − 4 M) keeps the map.
- What it buys is LOCALITY: the map put each link at a random slot after reading the key's header for an identity
  hash; the table puts a file's links next to each other, in the order the checker walks them.

**Predicted**: about half of the ~8.4% → −3..−5% single; memory unknown (a sparse store's leaves are mostly empty).

**Measured** (`scripts/tsgo-ab-warm.sh`, ABBA + BAAB, both arms `-Xms1g -Xmx4g`; A = § 13's tree `a604fe166`):

| regime | A | B | Δ | B wins |
|---|---:|---:|---:|---:|
| compiler single, ms | 3,731 [3,624-3,852] | 3,386 [3,289-3,578] | **−9.2%** | 4/4 |
| compiler parallel, ms | 2,170 [2,154-2,206] | 2,030 [1,925-2,365] | **−6.5%** | 3/4 |
| services parallel, ms | 2,987 [2,680-3,054] | 2,588 [2,522-2,732] | **−13.4%** | 4/4 |
| allocation/rebuild, single / parallel / services, MB | 1,240 / 1,405 / 1,934 | 1,198 / 1,358 / 1,880 | −3.3% / −3.4% / −2.8% | 4/4 each |
| peak heap after GC, services parallel (2 runs), MB | 2,091, 2,020 | 1,983, 1,878 | **−6%** | |

Twice the prediction, and the memory went DOWN: the maps' half-empty tables and rehash copies cost more than the
tables' partly empty leaves. Every run one digest (compiler `78feefbb`/65, services `ccb3b4c2`/65).

**Follow-up — the index block in the goroutine's own thread object.** A warm parse profile charged `goNextLinkIndex`
7.4% of the parse (its `ThreadLocal.get` per created node). A goroutine runs on the runtime's `GoroutineThread`, which
now carries the block in two fields (`Thread.currentThread()` is an intrinsic); other threads keep the `ThreadLocal`.
Warm parallel JVM: −0.3% (noise, 2/4). The GraalVM image — where the checker's goroutines create nodes and symbols
all through the check — tsc's compiler profile **2.585 → 2.387 s (−7.7%, 4/4 rotated pairs)**, parse time unchanged
(0.30 s; tsgo 0.12 s), output identical.

## 15. Where the shipped image stands after §§ 13-14, and owned slice LOCALS refused (2026-10-10)

**The GraalVM image** (Oracle GraalVM 25.0.4, G1; rotated, 3 runs per arm, every output byte-identical to tsgo
7.0.2). The 2026-10-09 binaries were kept as the "before" arm:

| project | default image 10-09 → today | PGO image (`scripts/tsgo-native-image-pgo.sh`) 10-09 → today | tsgo 7.0.2 |
|---|---:|---:|---:|
| tsc's compiler profile | 2.81 → 2.39 s | 2.38 → **1.84 s (−23%)** | 1.73 s |
| tsc's services profile | 3.62 → 3.32 s | 3.00 → **2.46 s (−18%)** | 2.35 s |
| date-fns core | 0.68 → 0.61 s | 0.60 → 0.53 s | 0.33 s |

The PGO image is now within ~6% of tsgo on the two large projects. Phases of the default image against tsgo
(`--extendedDiagnostics`, compiler profile): parse 0.34 / 0.12 s, bind 0.08 / 0.06 s, check 2.05 / 1.46 s; with
`--singleThreaded` parse is 0.69 / 0.26 s — a per-thread code-quality gap in the AOT image (the warm JVM parses at
tsgo's speed), which is what PGO closes. GC is not it: 16 G1 pauses sum to ~0.2 s of a 2.6 s run. `perf` cannot profile
the image on this box after a reboot (`perf_event_paranoid` is 4 again and needs root; the image is stripped).

**Owned slice locals — built, gated, REFUSED.** The local counterpart of § 13's owned fields (a `[]T` local appended
in place; its value uses taken without a copy when no in-place op can run after them; 296 locals, 425 in-place sites
with the fields). Every gate passed (Diag 13,127 equal, Emit/CLI/LS/API green, `-tsgo` 136/0). Measured against the
§ 14 tree: allocation **−3.6..−4.7%** (4/4 everywhere) but wall **+5.8% single (0/4) / +6.5% parallel / −0.7%
services** at 6 warm-up rebuilds, **−0.4% (2/4)** at 20 — a slower JIT warm-up and no steady-state gain — and the
GraalVM image unchanged (compiler 2,455 → 2,461 ms, services 3,149 → 3,180 ms medians). A local builder's header
lives in a register or a TLAB slot either way; the header objects it removed were never the cost. Reverted.

## 16. (TSGO.6-m) The config phase's directory walk: one `lstat` per entry, no eager size, no path copies (2026-10-10)

On a small project the image spends a large share outside checking: date-fns core (1,445 files), `--extendedDiagnostics`
**Config time 0.087 s against tsgo's 0.022 s** — the tsconfig include walk. A warm profile of it (`CheckBenchMain …
osfs`, the real file system every rebuild): `platformListDir` **39%** of the phase, `nextPathPartParts` **23%**, the eager
`platformSize` 6%. Three fixes, all inside Go's semantics:

- `platformListDir` (JVM `actual`): ONE `Files.readAttributes(…, NOFOLLOW_LINKS)` per entry for the kind, where
  `isSymbolicLink` + `isDirectory` + `isRegularFile` were up to three `lstat`s (Go reads `d_type` from `readdir`).
- `DirFS.readDir`'s `HostInfo` takes its size on first `size()` — Go's `DirEntry.Info()` stats lazily too, and no
  include walk asks.
- `vfsmatch.nextPathPartSingle`/`nextPathPartParts` (hash-pinned overrides): Go's `rest := s[offset:]` is O(1), the
  lowered `substring` copied the rest of the path once per component; the overrides search `s` from `offset`.

Measured, GraalVM default image, date-fns core, 5 rotated runs per arm: **Config time 107 → 77 ms (−28%)**, output
identical (CLI parity green over every recorded project's file set, Diag 13,127 equal).

## 17. (TSGO.6-m) Reading a source file into a string in one step (2026-10-10)

A JFR profile of the GraalVM image (built with `--enable-monitoring=jfr`; `perf` needs root after a reboot here) put
~20% of the image's PARSE phase in reading files: `goBytesToString`, `DirFS.readFile`, `containsNonASCII`. Go's
`string(b)` of `os.ReadFile`'s bytes is one copy; the port read the host's `ByteArray`, filled a `[]byte` `GoSlice`
element by element (boxed `Int`s), then built a string from it element by element again. `go.os.StringFileFS`
(NOT Go API) reads a host file straight into a byte string (`String(bytes, ISO_8859_1)` on the JVM, an intrinsic copy;
a `CharArray` natively), and a hash-pinned override of `vfs/internal.Common.ReadFile` asks for it when the file system
is the host's; every other FS keeps Go's path, and `decodeBytes` (the BOM handling) is Go's.

GraalVM default image, 4 rotated runs per arm, output identical: Parse time **316 → 286 ms** (compiler profile),
**232 → 195 ms** (date-fns core); Total 2.47 → 2.38 s and 0.59 → 0.56 s.
