# xemantic-typescript-compiler

The TypeScript compiler — scanner, parser, binder, **type checker**, transformer and
emitter — rewritten from scratch in Kotlin Multiplatform. No Node.js, anywhere.

[<img alt="GitHub Release Date" src="https://img.shields.io/github/release-date/xemantic/xemantic-typescript-compiler">](https://github.com/xemantic/xemantic-typescript-compiler/releases)
[<img alt="license" src="https://img.shields.io/github/license/xemantic/xemantic-typescript-compiler?color=blue">](https://github.com/xemantic/xemantic-typescript-compiler/blob/main/LICENSE)

[<img alt="GitHub Actions Workflow Status" src="https://img.shields.io/github/actions/workflow/status/xemantic/xemantic-typescript-compiler/build-main.yml">](https://github.com/xemantic/xemantic-typescript-compiler/actions/workflows/build-main.yml)
[<img alt="GitHub branch check runs" src="https://img.shields.io/github/check-runs/xemantic/xemantic-typescript-compiler/main">](https://github.com/xemantic/xemantic-typescript-compiler/actions/workflows/build-main.yml)
[<img alt="GitHub commits since latest release" src="https://img.shields.io/github/commits-since/xemantic/xemantic-typescript-compiler/latest">](https://github.com/xemantic/xemantic-typescript-compiler/commits/main/)
[<img alt="GitHub last commit" src="https://img.shields.io/github/last-commit/xemantic/xemantic-typescript-compiler">](https://github.com/xemantic/xemantic-typescript-compiler/commits/main/)

[<img alt="GitHub contributors" src="https://img.shields.io/github/contributors/xemantic/xemantic-typescript-compiler">](https://github.com/xemantic/xemantic-typescript-compiler/graphs/contributors)
[<img alt="GitHub commit activity" src="https://img.shields.io/github/commit-activity/t/xemantic/xemantic-typescript-compiler">](https://github.com/xemantic/xemantic-typescript-compiler/commits/main/)
[<img alt="GitHub code size in bytes" src="https://img.shields.io/github/languages/code-size/xemantic/xemantic-typescript-compiler">]()
[<img alt="GitHub Created At" src="https://img.shields.io/github/created-at/xemantic/xemantic-typescript-compiler">](https://github.com/xemantic/xemantic-typescript-compiler/commits)
[<img alt="kotlin version" src="https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fxemantic%2Fxemantic-typescript-compiler%2Fmain%2Fgradle%2Flibs.versions.toml&query=versions.kotlin&label=kotlin">](https://kotlinlang.org/docs/releases.html)
[<img alt="discord users online" src="https://img.shields.io/discord/811561179280965673">](https://discord.gg/vQktqqN2Vn)
[![Bluesky](https://img.shields.io/badge/Bluesky-0285FF?logo=bluesky&logoColor=fff)](https://bsky.app/profile/xemantic.com)

---

`xtsc` is a **drop-in replacement for `tsc`** — point it at your `tsconfig.json`, get the
same diagnostics and the same JavaScript:

```shell
xtsc --noEmit -p .          # type-check
xtsc -p . --outDir build    # type-check and emit
xtsc --watch -p .           # stay up, rebuild on change
```

On speed, plainly: it is about **2.7× faster than TypeScript's JavaScript compiler**, and
still **2.3× behind `tsgo`** — the Go rewrite, which since TypeScript 7 *is* the `tsc` you
get from npm. Closing that gap is the current target and where most of the work goes.

But speed is not why this exists. It is a **whole-program type checker running on the
JVM**, not a transpiler, and that buys three things neither `tsc` does.

## 1. Embed it — the whole IDE surface is a function call

No LSP process, no protocol, no `tsserver`. Open a project, feed it your unsaved editor
buffers, ask it questions:

```kotlin
val project = Project.open("/path/to/my-app")

project.updateFile("/path/to/my-app/src/a.ts", editorBuffer)  // never touches disk

project.quickInfoAt(file, offset)          // hover
project.definitionsAt(file, offset)        // go to definition
project.completionsAt(file, offset)        // completions, with accessibility + keywords
project.signatureHelpAt(file, offset)      // every overload
project.referencesAt(file, offset)         // find references, read vs write
project.renameAt(file, offset, "newName")  // a plan — applied and RECOMPILED before you see it
project.fileSemantics(file)                // hover + definition for a whole file, in ONE compile
```

Everything crossing that surface is a value — no AST, no `Symbol`, no `Type` — and every
answer is either right or an explicit refusal *with its reason*. A go-to-definition that
jumps to a same-spelled binding looks like it worked, so this compiler declines instead.

→ **[docs/language-service.md](docs/language-service.md)** — cost model, position
conventions, and every known gap, listed.

## 2. Run TypeScript on the JVM — as bytecode

A second backend lowers the **checked** program to Kotlin IR and hands it to kotlinc's own
JVM phases. No JavaScript engine is involved; the output is `.class` files.

```kotlin
compileTypeScriptProjectToJvm(projectPath, entryFileName = "index.ts", outputDirectory = out)
```

Real, unmodified npm libraries already compile and run this way:

| library | what happens | vs. the same library on Node |
|---|---|---|
| [`mitt`](https://github.com/developit/mitt) 3.0.1 | runs, and is imported as a module by a second file | **1.41× faster** (61 ns/emit) |
| [`smol-toml`](https://github.com/squirrelchat/smol-toml) (1,082 lines, 7 files) | parses a TOML document, verified against Python's `tomllib` | 2.08× slower (47 µs/parse) |

This works because the backend is the first consumer of the type graph: which overload a
call picked, what a union narrowed to, whether `+` concatenates — the checker already
knows. Stopping at IR is the point: JS, Native and Wasm are then a change of backend phase,
not a new compiler.

→ **[docs/kir-design.md](docs/kir-design.md)** (experimental — it refuses, loudly, what it
cannot yet lower)

## 3. Keep it warm

The JVM is slow to start and fast once running, so the CLI splits the two: a compile
daemon holds the warm compiler, and a **native** thin client (7 ms, no JVM) talks to it.

```shell
xtsc --serve &        # the warm compiler
xtsc --daemon -p .    # ~2 ms of protocol on top of a warm rebuild
```

Also available: `--workers N` (parallel share-nothing checking), `--incremental`, and a
GraalVM native image for one-shot use where there is nothing to keep warm.

## How fast, exactly

TypeScript's own compiler — 78 files, 194,702 LOC — on CI (`ubuntu-latest`, JDK 26):

| | check-only | emit |
|---|---:|---:|
| **xtsc** (warm JVM) | **3.96 s** | **4.82 s** |
| xtsc (GraalVM native image) | 6.00 s | 7.06 s |
| `tsc` 6.0.3 — TypeScript's JavaScript compiler | 10.73 s | 13.16 s |
| `tsc` 7.0.2 — `tsgo`, the Go rewrite | 1.70 s | 2.44 s |

Both rows are called `tsc`, so read the comparison carefully: **2.7× faster than the
JavaScript compiler, 2.3× slower than the Go one.** TypeScript 7 ships the Go compiler as
the `typescript` package itself, so that second row is what `npm install typescript` puts
on your machine today — it is the bar, and matching it is the open problem. Every push
re-runs this; the whole series is in [bench-history/](bench-history/README.md).

## How correct

- **15,528 tests, 0 failures.** 8,837 of them are generated from **TypeScript's own test
  suite** and compare emitted JavaScript and error baselines **character-for-character**
  against the output of pristine `tsc`.
- It **compiles the TypeScript compiler itself** — all eight source profiles, up to 273
  files — with **zero false positives**. On those 194,702 lines it reports 46 errors where
  `tsc` reports 65; the gap is checks not yet implemented, not disagreement.
- The corpus also runs on **Kotlin/Native**, and the native image's output is verified
  byte-identical to the JVM's on every build.

## Getting it

Pre-1.0 and not released yet — build it:

```shell
./gradlew assemble                                       # JVM distribution + scripts/xtsc
./gradlew :xemantic-typescript-compiler-cli:nativeImage  # the native binary (needs GraalVM)
```

The published coordinates (group `com.xemantic.typescript`) will be
`xemantic-typescript-compiler` for the compiler and
`xemantic-typescript-compiler-project` for the embedding API, which pulls the core in
transitively:

```kotlin
dependencies {
    implementation("com.xemantic.typescript:xemantic-typescript-compiler-project:$xtscVersion")
}
```

## Honest limits

- **Other people's code still finds bugs.** Conformance was driven by `tsc`'s own sources,
  which is one codebase's style. Pointing the checker at an unfamiliar library still
  surfaces false positives (`yaml` 2.7.0: 24 errors where `tsgo` reports 5) — each one a
  real defect, and each one gets fixed.
- **The CLI flag surface is a subset of `tsc`'s** — enough for a `tsconfig.json`-driven
  build, not for every corner of the option space.
- **The language service is not incremental.** Every semantic query is a full rebuild;
  batch your carets (`fileSemantics`) and debounce.
- **The JVM backend is a spike.** It refuses what it cannot lower rather than guessing.

## License

`AGPL-3.0-only WITH LicenseRef-xtsc-output-exception` — AGPL-3.0-only, with an
**[output exception](LICENSE-EXCEPTION)**: anything this compiler produces from *your*
input — JavaScript, declarations, source maps, diagnostics, emitted runtime helpers — is
yours, unencumbered.
