# xemantic-typescript-compiler

TypeScript to JavaScript transpiler in Kotlin Multiplatform

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

## Why?

TypeScript has become the lingua franca for large-scale JavaScript development, but transpiling TypeScript to JavaScript typically requires a Node.js-based toolchain. The `xemantic-typescript-compiler` provides a Kotlin Multiplatform implementation of a TypeScript to JavaScript transpiler that runs natively on the JVM and as a native binary on Linux and macOS — no Node.js or JavaScript runtime required.

## Usage

The compiler is available as a command-line tool and as a JVM library.

### Native Binary

Pre-built native binaries for Linux and macOS are available from the [releases page](https://github.com/xemantic/xemantic-typescript-compiler/releases).

```shell
xemantic-typescript-compiler <input.ts> [output.js]
```

### Running on JVM

```shell
java -jar xemantic-typescript-compiler.jar <input.ts> [output.js]
```

### Embedding it — the `Project` API

A build tool, IDE plugin or test harness can open a project, query its
diagnostics, apply unsaved editor buffers in memory, and ask what the compiler
knows at a position:

```kotlin
val project = Project.open("/path/to/my-app")
project.diagnostics()
project.updateFile("/path/to/my-app/src/a.ts", editorBuffer)  // never touches disk
project.quickInfoAt("/path/to/my-app/src/a.ts", offset)       // hover
project.definitionsAt("/path/to/my-app/src/a.ts", offset)     // go to definition
project.close()
```

See **[docs/language-service.md](docs/language-service.md)** for the full guide —
including the cost model (which calls compile and which do not), the position
conventions, and what is not implemented yet.
