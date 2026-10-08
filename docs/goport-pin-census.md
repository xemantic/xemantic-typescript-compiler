# goport pin census — `-core`'s hand-written `diagnose` pins against the ported tsgo

(TSGO.2) second leg (`docs/tsgo-port-plan.md` § 5, `docs/goport-diag-oracle.md` § 7). The hand-written
pins are already (source, options, expected) triples; what differs is the ENGINE behind `diagnose`. This
page is the switch, the method and the census (2026-10-08).

| what | where |
|---|---|
| the switch | `-core/src/commonTest/kotlin/EngineSwitch.kt` (`expect fun engineDiagnose`), called first by `CompilerTestSupport.diagnose` |
| the JVM engine | `-core/src/jvmTest/kotlin/EngineSwitch.jvm.kt` (`TsgoEngine`) over `-tsgo`'s `harness/Harness.kt` |
| Kotlin/Native | `-core/src/nativeTest/kotlin/EngineSwitch.native.kt`: always the default engine |
| build | `-core` `jvmTest` depends on `:xemantic-typescript-compiler-tsgo` (TEST scope only); `XTSC_ENGINE`, `XTSC_PIN_DUMP` are test-task inputs; `XTSC_TEST_HEAP` opt-in heap |
| census | `scripts/tsgo-pin-census.py` → `build/goport/pin-census/{census,cases}.json` |

## 1. The switch

```
XTSC_ENGINE=tsgo XTSC_TEST_HEAP=3g ./gradlew :xemantic-typescript-compiler-core:jvmTest
```

`XTSC_ENGINE` unset (or `core`): `engineDiagnose` answers `null` after one `System.getenv` and `diagnose`
runs exactly the code it always ran (the composed text, `TypeScriptCompiler().compile(text, fileName)`);
`TsgoEngine` is not even loaded. `XTSC_ENGINE=tsgo`: the composed text goes through the PORTED tsgo
compiler test harness (`makeUnitsFromTest`, configuration enumeration, `newCompilerTest`'s prepare block,
`CompileFiles`' pre-emit program — `docs/goport-lowering.md` § 1c) on one 1 GB-stack thread, and the result
is mapped into `-core`'s `Diagnostic`:

| field | mapping |
|---|---|
| text | `-core`'s harness format re-spelled as tsgo's: `// @useRealLibs` dropped (tsgo always uses its real libs; its harness refuses the unknown option); a directive line `-core` accepts INDENTED (`// @key:` after `trim()`) de-indented — tsgo's `optionRegex` is anchored at the line start, so left indented the `@Filename` would be source and tsgo would compile a different program (36 cases) |
| `fileName` | the harness current directory (`/.src/`) removed; an absolute `@Filename` stays absolute; a bundled lib keeps its base name |
| `start`, `length` | UTF-8 byte offsets → UTF-16 offsets into the file text |
| `line`, `character` | `-core`'s own `lineAndCharacterAt` over the UTF-16 text |
| `message`, `messageChain` | tsgo's flattened text, split into the first line and the indented chain lines (`-core`'s storage) |
| configuration | a varying directive compiles the FIRST configuration |

**The mapping is checked, not assumed**: over all 10,657 pin texts, of the 9,323 diagnostic rows both engines
report (same code, start, length, message), `fileName`/`line`/`character`/`category` disagree on 18 — all of
them key collisions (the same code at the same offset in two files of one text), none a mapping error.

## 2. Method

1. Default engine with `XTSC_PIN_DUMP=<dir>`: every pin passes as usual (21,248 tests, 0 failures); each
   composed text's `-core` answer is recorded (`core/<sha>.json`).
2. `XTSC_ENGINE=tsgo XTSC_PIN_DUMP=<dir>`: the same suite; every compiled text is written as a conformance
   case (`compiler/<sha>/<fileName>`) with the port's raw diagnostics (`.port.jsonl`, the diag oracle's format)
   and mapped ones (`.port.mapped.json`); `calls.tsv` attributes each to its test (the outermost frame of a
   test class: the test method, not a private helper; a lambda frame normalised to its test).
3. `scripts/tsgo-pin-census.py --core <results A> --tsgo <results B>` runs every dumped text through **tsgo
   itself** (`tsgo-oracle materialize` + `diags`: tsgo's own harness, docs/goport-diag-oracle.md) and buckets
   each test that fails only under tsgo:
   - **port-defect** — some text it compiled: the port's diagnostics differ from tsgo's (or the port threw);
   - **harness-gap** — some text tsgo's harness does not compile as written (a harness skip or fatal);
   - **core-divergence** — every text it compiled: port == tsgo, so the pin asserts `-core`'s behaviour;
     refined by comparing `-core`'s answer with the port's mapped one (the most substantive difference wins).

## 3. Census (2026-10-08, HEAD of this commit)

| | tests |
|---|---|
| core module tests | 21,248 (44 skipped) |
| pass under BOTH engines | 20,088 — of them **9,617 `diagnose` pins** and 10,471 that compile nothing through `diagnose` (the generated corpus, parser/emitter/project/KIR/LSP tests) |
| **fail only under tsgo** | **1,116** |
| fail only under `-core` / under both | 0 / 0 |

Pin texts: **10,657 distinct, 10,533 equal to tsgo** (every diagnostic, every field), 124 not compiled by
tsgo's harness, **0 where the port differs from tsgo**.

The 1,116, by cause:

| bucket | tests | what it is |
|---|---|---|
| **genuine port defect** | **0** | no pin text on which the ported compiler and tsgo 7.0.2 disagree |
| **harness/option mapping gap** | **51** | tsgo's harness will not compile the text as written: `SkipUnsupportedCompilerOptions` for options TypeScript 7 removed — `target: ES5` 30, `baseUrl` 6, `moduleResolution` node10/classic 5, `alwaysStrict: false` 1, `outFile` 1 — and a `@lib` list naming an unknown library (`es2015,nosuchlib`: tsgo's harness `Fatalf`s, `-core`'s reports TS6046) 5 + 3. These pins test `-core`'s removed-option ladder; tsgo's answer for them is defined only by its CLI, not its harness. Two mapping gaps were FIXED in the engine instead of being counted (`@useRealLibs`, indented directives, § 1) |
| **pin asserts a `-core` divergence from tsgo** | **1,065** | the port says exactly what tsgo says; the pin expects something else |

The 1,065, by how `-core`'s answer differs from tsgo's (per test, the most substantive difference among its texts):

| difference | tests | reading |
|---|---|---|
| tsgo reports more | 590 | `-core` false negatives the pin encodes as silence or as an exact list (top codes tsgo adds: TS2339 94, TS2322 63, TS2300 52, TS2345 29, TS2740 23, TS2304 22, TS2365 21, TS2454 19, TS2451 18, TS7006 17); also defaults (`using` → TS2318 `Disposable` under tsgo's default lib) |
| different rows | 176 | both sides add/drop rows (tsgo-only TS2662 53, TS2741 29, TS2591 24, TS2307 21; core-only TS2322 90, TS2304 63, TS2815 53, TS2301 23 — e.g. the TS2815/TS2662 static-member suggestion, `frozen quirk` pins) |
| `-core` internals | 152 | identical diagnostics; the pin asserts `-core`'s instrumentation — section probes (`CtaSectionProbeTest`, `NarrowSectionProbeTest`, `CallSectionsWarmProbeTest` …), censuses, gate counters, `PassTiming` — which no other engine has |
| core reports more | 68 | `-core` false positives the pin expects (TS2872 10, TS2339 7, TS2322 6, TS2564 5, …) |
| message text | 57 | same rows, different wording (type display, e.g. `string \| "a"` literal display) |
| message chain / related information | 8 / 6 | same rows and text, different elaboration |
| order | 8 | the same rows in a different order (tsgo's `SortAndDeduplicateDiagnostics`); pins comparing whole lists |

So under the switch every failure is accounted for, and none is the port's.

## 4. What it means

- The `-tsgo` port is now graded by ~10.7k independent hand-written texts on top of the 6,318 conformance
  configurations, with the same result: **no disagreement with tsgo**.
- The 1,065 divergence pins are `-core`'s record, not the port's: a pin that passes on `-core` and fails on the
  port, where the port equals tsgo, encodes `-core` behaviour. Under the 2026-09-21 directive (tsgo is the
  definition) the 590 + 68 + 176 + 57 + 22 rows are `-core` defects pinned as expectations; the 152 internals
  pins are engine-specific by construction.
- Retiring `-core` ((TSGO.4)) needs the pins re-expressed for the port only where they test the LANGUAGE;
  this census is the list (`build/goport/pin-census/census.json`: class, test, bucket, diff, cases, failure).

## 5. Reproduce

```
G=build/goport; R=xemantic-typescript-compiler-core/build/test-results/jvmTest
rm -rf $G/pin-dump $R; XTSC_PIN_DUMP=$PWD/$G/pin-dump ./gradlew :xemantic-typescript-compiler-core:jvmTest; cp -r $R /tmp/resA
rm -rf $R; XTSC_ENGINE=tsgo XTSC_PIN_DUMP=$PWD/$G/pin-dump XTSC_TEST_HEAP=3g ./gradlew :xemantic-typescript-compiler-core:jvmTest; cp -r $R /tmp/resB
python3 scripts/tsgo-pin-census.py --core /tmp/resA --tsgo /tmp/resB
```

Needs `build/goport/bin/tsgo-oracle` (`xemantic-typescript-compiler-goport/oracle-go/build.sh`) and
`build/goport/ts-submodule` (`scripts/tsgo-diag-cases.py`). Wall: ~5 min per suite run, ~2 min for the oracle.
