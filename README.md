# xemantic-typescript-compiler

**TypeScript for the JVM and Kotlin.** TypeScript 7's own compiler — `tsgo`'s scanner,
parser, binder, **type checker**, emitter and language service — ported from Go to Kotlin
Multiplatform. **No Node.js and no Go anywhere in the toolchain.**

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

`xtsc` is not trying to out-run `tsgo` at being `tsc`. TypeScript 7 *is* the Go
compiler, it is free and official, and if a faster `tsc` is all you need, use it.
This project exists for the places `tsgo` cannot go:

1. **Type-check and compile TypeScript with nothing but a JVM** — no Node, no Go, no
   npm anywhere in the toolchain. One artifact, or a native binary.
2. **Embed TypeScript's own checker in a Kotlin application** — the type oracle and the
   IDE surface (hover, definitions, references, completions, diagnostics) are function
   calls on a live object, not an LSP process you shell out to.
3. **Generate Kotlin externals with resolved types** — from the *checked* program, not
   from `.d.ts` syntax. The gap [Dukat](https://github.com/Kotlin/dukat) and
   [Karakum](https://github.com/karakum-team/karakum) never closed.
4. **Run TypeScript as JVM bytecode** — a backend lowers the checked program to Kotlin
   IR and hands it to kotlinc's own JVM phases. Real npm libraries already run this way.

### Why a port, and not a rewrite

The checker is not a re-implementation chasing `tsc`'s behaviour: it *is* `tsgo` 7.0.2,
lowered from Go to Kotlin by a porter in this repository (`-goport`: mechanical,
go/types-driven, with recorded overrides — the generated sources are never hand-edited).
So the answers are TypeScript's own answers — the same diagnostics, the same JavaScript,
the same hover — and code checks here exactly as it does in VS Code. Following a new
TypeScript release is a re-run of the porter, not a parity project.

## 1. Embed it — the checker is a function call

No LSP process, no protocol, no `tsserver`. Open a project and ask it questions — the
language service:

```kotlin
val ls = TsgoLanguageService.open("/path/to/my-app/tsconfig.json")

ls.hover(file, offset)              // hover, as tsgo's language server renders it
ls.definition(file, offset)         // go to definition
ls.references(file, offset)         // find references
ls.completions(file, offset)        // completions
ls.diagnostics(file)                // a file's diagnostics
ls.request("textDocument/signatureHelp", paramsJson)  // any request the server answers, as JSON
```

— and the type oracle, `tsc --api`'s session in process:

```kotlin
val project = TsgoProject.open("/path/to/my-app/tsconfig.json")

val type = project.typeAtPosition(file, offset)
project.typeToString(type!!)
project.propertiesOfType(type)
project.isTypeAssignableTo(source, target)
project.resolvedSignature(project.nodeAt(file, offset)!!)  // which overload a call picked
```

Every answer comes out of the very handler code `tsc --lsp` and `tsc --api` run for that
request, and is gated against the real `tsgo` binary: **21,614 / 21,614** language-service
answers and **594,007 / 594,007** API answers equal. A project is read through an `FS` you
can supply, so unsaved editor buffers never have to touch disk.

The earlier embedding API — `-project`'s `Project`, built on the hand-written checker this
port replaced — is **deprecated**; see its KDoc for what has not carried over yet.

→ **[docs/goport-ls.md](docs/goport-ls.md)** and **[docs/goport-api.md](docs/goport-api.md)**

## 2. Generate Kotlin externals — with resolved types

Every Kotlin/JS interop generator so far has translated **declaration syntax**: Dukat
(archived) and Karakum both walk `.d.ts` ASTs and meet the same wall — conditional
types, mapped types, overload selection, generic instantiation — everything whose answer
is not in the syntax. `xtsc` sits on the other side of that wall: it *has* the checked
program. The externals generator consumes the checker's resolved types and emits Kotlin
`external` declarations whose types are the checker's answers, with one **documented**
fallback for every shape Kotlin cannot express, never a silent `dynamic`.

Every rung of the fixture ladder is green, and the gate at each one is that the
generated Kotlin **compiles** — as Kotlin/JS, annotations and all, not just as metadata:

| library | what is generated | Kotlin errors |
|---|---|---:|
| [`mitt`](https://github.com/developit/mitt) 3.0.1 | the whole `index.d.ts` | 0 |
| [`smol-toml`](https://github.com/squirrelchat/smol-toml) 1.7.1 | 7 declaration files | 0 |
| [RxJS](https://github.com/ReactiveX/rxjs) 7.8.2 | all 250 files of `dist/types` | 0 |
| `typescript.d.ts` 6.0.3 | 11,448 lines in, 9,792 lines of Kotlin out | 0 |
| [`@types/node`](https://www.npmjs.com/package/@types/node) 20.19.43 | 51 modules, one Kotlin package each, compiled together | 0 |

A package that declares many modules is generated per module: `Socket` lands in both
`node.dgram` and `node.net`, and `Socket extends stream.Duplex` crosses packages as a real
Kotlin supertype.

## 3. Run TypeScript on the JVM — as bytecode

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

(The timings predate the backend's move onto the port's checker.)

This works because the backend is the first consumer of the type graph: which overload a
call picked, what a union narrowed to, whether `+` concatenates — the checker already
knows. Stopping at IR is the point: JS, Native and Wasm are then a change of backend phase,
not a new compiler.

→ **[docs/kir-design.md](docs/kir-design.md)** (experimental — it refuses, loudly, what it
cannot yet lower)

## 4. And still a full compiler CLI

`xtsc` *is* `tsc` 7's command line, ported — same flags, same output, same exit codes:

```shell
xtsc --noEmit -p .          # type-check
xtsc -p . --outDir build    # type-check and emit
xtsc --watch -p .           # stay up, rebuild on change
xtsc -b                     # project references, with --incremental build info
```

It ships as a GraalVM native image (`xtsc-linux-x64`, built with profile-guided
optimization), which starts in milliseconds and is the way to run it from a shell. On the
JVM, start-up dominates a one-shot run, so the JVM launcher (`scripts/xtsc`) adds a compile
daemon that holds the warm compiler, and a Kotlin/Native thin client (7 ms) talks to it:

```shell
scripts/xtsc --serve &        # the warm compiler
scripts/xtsc --daemon -p .    # ~2 ms of protocol on top of a warm rebuild
```

## How fast, exactly

Speed is published for honesty, not as the pitch. TypeScript's own compiler — 78 files,
194,702 LOC — on CI (`ubuntu-latest`, JDK 26), every compiler reporting the same 65 errors:

| | check-only | emit |
|---|---:|---:|
| **xtsc** (native image) | **2.42 s** | **3.54 s** |
| **xtsc** (warm JVM) | **1.91 s** | **2.67 s** |
| xtsc (cold JVM, one-shot) | 14.62 s | 18.32 s |
| `tsc` 7.0.2 — `tsgo`, the Go compiler | 1.77 s | 2.50 s |
| `tsc` 6.0.3 — TypeScript's last JavaScript compiler | 11.26 s | 13.90 s |

Across recent runs the warm port takes **1.1–1.3×** `tsgo`'s time and the native binary
about **1.4×**.
A cold JVM is not a way to run a compiler — use the native image or the daemon. Shared CI
runners are noisy, so read the ratios, not the seconds: every push re-runs this, and the
whole series is in [bench-history/](bench-history/README.md).

## How correct

The port is gated against the `tsgo` 7.0.2 binary itself — never against hand-written
expectations:

- **Diagnostics**: all **13,127** configurations of the compiler test suites `tsgo` runs
  (its own and TypeScript's conformance suite) report exactly what `tsgo` reports.
- **Emit**: the same 13,127 configurations emit byte-identical JavaScript, declarations
  and source maps, and TypeScript's own 78 sources emit a `diff -r`-identical tree.
- **Language service and API**: 21,614 / 21,614 and 594,007 / 594,007 answers equal.
- **Command line**: 120 / 121 recorded `tsc` runs equal, including `--build` and
  `--incremental` (the 121st, type-fest, needs an 8 GB heap and is skipped below it).
- **Native**: the shipped image replays `tsgo`'s recorded output — stdout, exit status and
  written files — with no difference, on every native build; the port's runtime suite
  also runs on **Kotlin/Native**.

## Getting it

Pre-1.0 and not released yet. Snapshots are published to the
[Maven Central snapshot repository](https://central.sonatype.com/repository/maven-snapshots/):

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}

dependencies {
    implementation("com.xemantic.typescript:xemantic-typescript-compiler:0.1.0-SNAPSHOT")
}
```

Or build it:

```shell
./gradlew assemble                                       # JVM distribution + scripts/xtsc
./gradlew :xemantic-typescript-compiler-tsgo:nativeImage # the native binary (needs GraalVM)
```

## Honest limits

- **It is as fast as a JVM can make Go's design, not faster.** Warm it is close to `tsgo`;
  as a native binary it is behind; as a cold JVM process it is slow (see above).
- **The language service is embeddable, but not yet incremental.** An edit means opening
  the project again; the measured design for in-session updates is queued. Rename and
  auto-import completions are not ported yet (configure
  `{"suggest": {"autoImports": false}}`).
- **It follows one TypeScript version.** It is `tsgo` 7.0.2; a newer TypeScript arrives
  when the porter is re-run against it.
- **The JVM backend is a spike.** It refuses what it cannot lower rather than guessing.

## License

`AGPL-3.0-only WITH LicenseRef-xtsc-output-exception` — AGPL-3.0-only, with an
**[output exception](LICENSE-EXCEPTION)**: anything this compiler produces from *your*
input — JavaScript, declarations, source maps, diagnostics, emitted runtime helpers — is
yours, unencumbered.

The ported compiler is derived from [typescript-go](https://github.com/microsoft/typescript-go),
Copyright (c) Microsoft Corporation, licensed under Apache-2.0; its `LICENSE` and
`NOTICE.txt` ship inside the artifact (`META-INF/typescript-go/`) and in the module's sources.
