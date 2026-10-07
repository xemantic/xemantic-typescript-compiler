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
copies in `gen/` (34) are off the parse path except `parser.parseJSDocComment`'s
`isJSDocLikeText(sourceText[start:])` — a ported callee, which needs a "window parameter" rule
(an overload taking `(base, from, to)` for a callee whose string parameter is view-eligible).

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
