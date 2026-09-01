# Using the language service — the `Project` embedding API

How to embed xtsc in a build tool, an IDE plugin, a test harness or an LSP
server: open a TypeScript project, ask what is wrong with it, apply the buffers
your user is typing into, and ask again — without the edits ever reaching disk.

## 0. The whole surface, at a glance

This section is the map: every call the API exposes, what it answers, and the
properties that hold across all of them. Each row points at the section that
documents it properly.

### What it does

**It is a compiler, driven as a service.** `Project.open` points at a
`tsconfig.json`; every answer below comes from a real type-check of the real
program, not from a heuristic index. There is no separate "language service"
model to drift out of step with the compiler.

- **Diagnostics** — the whole program, one file, or an arbitrary file set,
  with the answer for a file identical however you ask for it.
- **In-memory editing** — overlay a buffer, delete a file, add one that exists
  nowhere on disk. Nothing is ever written; the on-disk tree is read-only input.
- **Positions** — offset ↔ line/character, both directions, over a
  terminator-exact line index (`\n`, `\r\n` and a lone `\r` each end one line).
- **Syntax** — what node is at this offset, and its ancestor chain.
- **Hover** — the type at a caret, including a member's own declaration name.
- **Go to definition** — locals, imports, members (`o.p`, inherited, through a
  union, a namespace, an enum, the lib), an element access `o["p"]`, a binding
  element's property name, an object-literal key, and both shorthand forms.
- **Completions** — members (accessibility-filtered) and free names, plus
  keywords, with the replacement span the host should overwrite.
- **Signature help** — every overload, the active one, the active parameter,
  and the label offsets to underline.
- **Find references and document highlights** — one population, with each
  occurrence classified `READ` / `WRITE` / `READ_WRITE`, or `UNCLASSIFIED` where
  the classification is not certain. That fourth state is the design: it does not
  default to `READ`, so a host is never told "this is only read" about a span the
  compiler could not place.
- **Rename** — an edit plan that expands shorthands rather than corrupting
  them, and that is **verified by applying it and compiling again**, so a
  collision or a capture withdraws the plan instead of reaching your buffer.
- **Batched semantics** — many carets, or a whole file, answered in one build.

### The calls

| call | answers | § |
|---|---|---|
| `Project.open(path, vfs = SystemVfs)` | opens a project; compiles nothing | § 2 |
| `configPath` | the `tsconfig.json` this project is checked against, absolute; derived without compiling, and reported even when the file is absent | § 2 |
| `files` | every file in the program — roots plus everything reachable through imports, plus declarations — in crawl order | § 4 |
| `diagnostics()` | the whole program's diagnostics | § 4, § 4b |
| `diagnostics(fileName)` | the rows for one file | § 4 |
| `diagnosticsOf(fileNames)` | the rows for a file SET, checking only those files | § 4a |
| `prepare(fileNames)` | pre-checks a working set so later semantic queries about any of them are free | § 3a |
| `positionAt(fileName, offset)` | offset → 1-based line/character | § 6 |
| `offsetAt(fileName, line, character)` | 1-based line/character → offset | § 6 |
| `nodeInfoAt(fileName, offset)` | the narrowest node at the offset, and its ancestor kinds | § 7 |
| `quickInfoAt(fileName, offset)` | the type at the caret, as display text | § 8 |
| `definitionsAt(fileName, offset)` | where the thing at the caret is declared — a list, because a symbol may have several | § 9 |
| `completionsAt(fileName, offset)` | the completion list and the span it replaces | § 10a |
| `signatureHelpAt(fileName, offset)` | the overloads at a call, with the active one and the active argument | § 10c |
| `semanticsAt(fileName, offsets)` | hover **and** definitions for many carets, in one build | § 10 |
| `fileSemantics(fileName)` | the same for every identifier in a file | § 10 |
| `referencesAt(fileName, offset)` | every occurrence of the symbol, program-wide | § 10b |
| `documentHighlightsAt(fileName, offset)` | the same, restricted to one file | § 10b |
| `renameAt(fileName, offset, newName)` | a verified edit plan, or a refusal that names its reason | § 10d |
| `updateFile(path, text)` | overlays a buffer; marks the project dirty | § 5 |
| `deleteFile(path)` | overlays a file as absent | § 5 |
| `reloadFile(path)` | forgets a path entirely — overlay and retained read alike — so what is on disk is the truth again | § 5 |
| `trustFilesystem` | opt-in: the host promises the bytes of a file never change without saying so, and builds stop re-reading | § 5a |
| `cancellation` | a signal the build polls, so a host can abandon an unwanted answer | § 14 |
| `saveState()` / `restoreState(text)` | this project's incremental state as text, for the next process | § 14 |
| `close()` | releases the overlay and the cached build; idempotent | § 11 |

`LineMap` is the line index behind `positionAt` / `offsetAt`, exposed for a host
that wants to hold one itself: `LineMap.of(text)`, then `positionAt(offset)`,
`offsetAt(line, character)`, `lineStart(line)`, `lineCount`, `textLength`. It is
immutable, retains no reference to the string it indexed, and is safe to share.

### The value types

| type | carries |
|---|---|
| `Diagnostic` (from the core) | `message`, `category`, `code`, `fileName`, `line`, `character`, `start`, `length`, `relatedInformation`, `messageChain` |
| `TextPosition` | `line`, `character` — both **1-based** |
| `NodeInfo` | `kind`, `start`, `end`, `ancestorKinds` |
| `QuickInfo` | `kind`, `displayString`, `start`, `end` |
| `DefinitionLocation` | `fileName`, `start`, `length`, `kind` |
| `SemanticInfo` | `start`, `end`, `kind`, `quickInfo`, `definitions` |
| `CompletionList` | `kind` (`MEMBER` / `FREE_NAME` / `NONE`), `prefix`, `replacementStart`, `replacementEnd`, `items`, `refusal` |
| `CompletionItem` | `name`, `kind`, `typeText`, `optional`, `readonly`, `accessibility` |
| `SignatureHelp` | `signatures`, `activeSignature`, `activeArgument` |
| `SignatureInfo` | `label`, `parameters`, `returnTypeText`, `activeParameter` |
| `ParameterInfo` | `name`, `typeText`, `optional`, `isRest`, `labelStart`, `labelEnd` |
| `ReferenceLocation` | `fileName`, `start`, `end`, `isDeclaration`, `use` (`READ` / `WRITE` / `READ_WRITE` / `UNCLASSIFIED`) |
| `RenamePlan` | `oldName`, `newName`, `files`, `refusal`, `conflicts`, and `isApplicable` — true exactly when there is no refusal and at least one file to edit, which is the one test a host needs before offering the rename |
| `FileRename` / `RenameEdit` | `fileName` + `edits`; `start`, `end`, `newText` |
| `RenameConflict` | `kind`, `fileName`, `start`, `end`, `detail` |

Every span is a 0-based offset into the file's text, and every span is half-open
— but the two families spell it differently, and mixing them up is silent:
`DefinitionLocation` and `Diagnostic` carry `start` + **`length`**, while
`QuickInfo`, `NodeInfo`, `ReferenceLocation` and `RenameEdit` carry `start` +
**`end`**. `line` and `character` are always **1-based**. And a span is not always
an identifier: a reference to a member named by a string literal covers the text
*between* the quotes, so a host that assumes otherwise will mis-highlight it.

### Three properties that hold everywhere

**Nothing reaches disk.** Every build runs `noEmit`. `updateFile` overlays; the
backing `Vfs` is never written through. A host may point the API at a user's
checkout without owning the consequences.

**Prove to offer.** Where the compiler cannot establish an answer it refuses and
says why, rather than guessing — because a plausible wrong answer is worse than
none: a go-to-definition that jumps to an unrelated same-spelled binding *looks
like it worked*. Refusals are typed (`CompletionRefusal`, `RenameRefusal`,
`RenameConflictKind`), and every position this API answers either answers
correctly or refuses. The reasoning, and the full list of gaps, is § 13.

**Cost is incremental, not per-query.** A caret-scoped query checks the buffer it
is in, not the program; a whole-project error list after an edit is usually one
narrowed build rather than a rebuild; and repeated or overlapping questions about
one project state are free. § 3 is the table a host should design against, and it
is worth reading before the first integration rather than after.

---

**Where the history went.** How each of these came to work the way it does — the
changelog, the measurements behind every design decision, and the roadmap items
that were priced and then shipped or refused — is in
[`docs/history/language-service-history.md`](history/language-service-history.md).
It is deliberately not on this page: a reader asking what the API can do is not
helped by internal work-item numbers, and a reader tracking how an answer moved
wants the opposite of a summary.

**There is no `LanguageService` type.** The editor features hang off `Project`
directly. A separate facade would be indirection with one implementation, and
the surface is still small enough to read in one screen. If it grows past that,
splitting it is a rename, not a redesign.

---

## 1. Getting it

The API lives in its own module, so a host does not link the CLI, the daemon or
the wire protocol:

```kotlin
dependencies {
    implementation("com.xemantic.typescript:xemantic-typescript-compiler-project:0.1.0-SNAPSHOT")
}
```

Within this repo, depend on the module directly:

```kotlin
implementation(project(":xemantic-typescript-compiler-project"))
```

It exposes the compiler core transitively (`api(project(":…-core"))`), because
`Diagnostic` and `Vfs` are part of its signature. JVM only for now; the sources
are in `commonMain`, so adding a native target is a build-file change rather
than a source move.

Everything below is in `com.xemantic.typescript.compiler.project`.

## 2. Quick start

```kotlin
import com.xemantic.typescript.compiler.project.Project

val project = Project.open("/path/to/my-app")   // dir with tsconfig.json, or the config file

project.diagnostics().forEach { d ->
    println("${d.fileName}:${d.line}:${d.character} TS${d.code} ${d.message}")
}

// the user types; nothing is written to disk
project.updateFile("/path/to/my-app/src/a.ts", editorBuffer)

project.diagnostics("/path/to/my-app/src/a.ts")          // just this file

// one caret, either question — ONE compile for the whole BUFFER, then free
project.quickInfoAt("/path/to/my-app/src/a.ts", 142)     // hover
project.definitionsAt("/path/to/my-app/src/a.ts", 142)   // go to definition
project.quickInfoAt("/path/to/my-app/src/a.ts", 190)     // …and this one builds nothing

// many carets, both questions — the SAME compile (§ 10; the convenient shape)
project.semanticsAt("/path/to/my-app/src/a.ts", listOf(142, 190, 240))
project.fileSemantics("/path/to/my-app/src/a.ts")

project.close()
```

`open` compiles **nothing**. It resolves and validates the path and returns; the
first query compiles. That is deliberate: it lets you stage the buffers you
already hold *before* the first build, so a host with unsaved state does not pay
for a build of the on-disk truth it is about to discard.

`configPath` tells you which `tsconfig.json` the project is checked against, as
an absolute path, resolved exactly as the compiler resolves it — a directory
argument gets `/tsconfig.json` appended, anything else IS the config. It is
derived without compiling, so reading it is free, and it is reported even when
the file is absent; a host that wants to distinguish "no config" from "a config
I have not saved yet" can stage one through `updateFile` before its first query.

`open` throws `IllegalArgumentException` if the path does not exist. That is a
guard, not politeness — a project path that does not exist used to make the
crawl walk upwards from `/`, which inside a long-lived host is not a slow query
but a wedged process.

## 3. The cost model — read this before designing your host

Not all queries cost the same, and the differences are large enough to shape how
you call them.

| call | cost | notes |
|---|---|---|
| `open` | free | resolves the path; compiles nothing |
| `positionAt` / `offsetAt` | reads the file | never builds, even on a dirty project |
| `nodeInfoAt` | parses **one file** | never builds; cached until that file is edited |
| `diagnostics()` / `diagnostics(f)` | **ONE NARROWED build** after an edit that moved no exported signature; a **full build** otherwise (that costs two: the narrowed gate, then the rebuild); none when clean | 67% of real edits to tsc's own compiler move no signature, and a served edit is **108 – 113 ms against 4,864 – 5,096 ms — a factor of 45**. See § 4b |
| `files` | **full build** when dirty, else cached | a question about the PROGRAM, which is what a build computes |
| `diagnosticsOf(files)` | **one NARROWED build** for the FIRST query of a project state; **none** when clean, repeated, a SUBSET of a set already asked about, or about any other file — a live re-entrant checker answers it | checks only those files: **108 – 113 ms at the median file** against a 4.9 s full rebuild on tsc's own sources (2026-08-24; § 13 has the full table) — see § 4a |
| `prepare(files)` | **ONE NARROWED build**, whatever the file count | checks and captures a whole working set at once, so every later semantic query about any of them is free — see § 3a |
| `quickInfoAt` | **one NARROWED build per BUFFER**, not per caret | checks only the queried file — **129 – 137 ms at the median file, 38x a full rebuild** on tsc's own sources (2026-08-24); the question asked is the file's span set, so later carets are free (§ 13); free in a `prepare`d file |
| `definitionsAt` | **one NARROWED build per BUFFER**, shared with `quickInfoAt` | same mechanism, same build — whichever of the two asks first pays; free in a `prepare`d file |
| `semanticsAt(f, offsets)` | **ONE NARROWED build**, whatever the offset count | both answers, per span; the same build the three neighbours use; free in a `prepare`d file |
| `fileSemantics(f)` | **ONE NARROWED build** | every identifier in the file; the same build again; free in a `prepare`d file |
| `completionsAt(f, o)` | **one NARROWED build, every call** | 194 – 202 ms on tsc's own sources (2026-08-24). A DIFFERENT question (a receiver's members, or a scope chain), so it does not share — **not even with `prepare`, which is an open defect, see § 13**; free at a caret that admits no completion — those do not build; keywords cost nothing extra |
| `documentHighlightsAt(f, o)` | **ONE NARROWED build** per buffer | sweeps this file's identifiers and member-name literals — which is the population all four of these share; free in a `prepare`d file |
| `referencesAt(f, o)` | ONE build clean, TWO dirty — **narrowed by SPELLING**, whole-program only where the name closure cannot be bounded | § 10b has the measured figures |
| `renameAt(f, o, n)` | TWO builds clean, THREE dirty — **narrowed the same way**, with the population widened by the NEW name | § 10d |
| `signatureHelpAt(f, o)` | **one NARROWED build, every call** | 190 – 214 ms on tsc's own sources (2026-08-24); shares nothing, same open defect as `completionsAt`; free at a caret in no argument list — those do not build |
| `renameAt(f, o, name)` | **TWO builds** (three dirty) | the sweep plus a verification build; § 10d has the measured figures. A refusal on syntax alone does not build |
| `updateFile` / `deleteFile` | free | marks dirty |

**A NARROWED build still crawls, parses and binds the whole program** — what
narrows is the per-file CHECKING, which the compiler takes as a partition
(`recheckOnly`, the INV.6 view `--workers` uses). Every caret-scoped query above
hands it the buffer the caret is in, because an editor's question about one buffer
claims nothing about the other files. `referencesAt` and `renameAt` claim something
about EVERY file — but their EVIDENCE does not: they select
the occurrences that could possibly be answers and let the partition follow from them
(§§ 10b, 10d). Measured warm and rotated in one process on tsc's
own 78 compiler sources (2026-08-24, commit d018af0a, two independent processes),
a first capture of `binder.ts` (7,787 spans) is **290 – 306 ms** against a
**4,864 – 5,096 ms** full rebuild, and the median over all 73 sweepable files is
**129 – 137 ms — 38x**. The figures this paragraph used to quote (4,581 -> 979 ms,
4.7x) are history and are 5 – 15x stale; § 13 carries both columns.
That the narrowed answer is the whole-program answer is swept span for span rather
than argued: `scripts/capture-equivalence.sh` for types and definitions,
`scripts/capture-channel-equivalence.sh` for members, scopes and signatures.

**A query on a dirty project is still a rebuild.** That is a property of the
compiler, not a shortcut taken here: `ProjectCompiler.Result` is a flat value
(paths, diagnostics, an import graph) that retains no AST, no binder output and
no checker, because the checker's construction *is* the compilation. What makes
a re-query cheap anyway is the compiler's process-global, **content-keyed** parse
cache, which every unedited file hits — so the second build of an N-file project
re-parses only what changed. For scale: a warm rebuild of the TypeScript
compiler's own 78-file sources is **4.86 – 5.10 s** (re-taken 2026-08-24, two
processes; the first rebuild in a process is ~9 s, so a host's first query is not
the steady state); a normal application project is far less.

Two consequences for a host:

- **Debounce, do not poll.** Re-asking `diagnostics()` per keystroke costs a
  compile per keystroke. Ask on idle.
- **A capture build is not the `diagnostics()` build and never becomes one.** That
  is deliberate rather than an oversight: a capture build types nodes the checker
  had no reason to type, so its diagnostics are not interchangeable with a plain
  build's and reusing it would quietly change what `diagnostics()` reports.
- **You no longer have to batch a buffer, and this advice is inverted from what it
  said before 2026-08-23.** It used to read "asking both about one caret is two
  compiles, so batch", with a measured 34x. Both halves are now closed:
  hover-then-navigate at one caret is ONE compile (the two members ask
  an identical question and read different channels of the one answer), and every
  caret in a buffer is one compile between them (the question put to
  the compiler is the FILE's occurrence set, not the caret's). Measured, the six
  carets that used to be six compiles are one, batched or not.
- **`semanticsAt` and `fileSemantics` are still the members to reach for**, for a
  reason that survived the inversion: they hand you every answer at once instead of
  making you ask for them one at a time, and `fileSemantics` is what a semantic
  highlighter wants. What changed is that reaching for the single-caret pair is no
  longer a *cost* mistake.
- **`quickInfoAt`, `definitionsAt`, `documentHighlightsAt` and `fileSemantics` are
  ONE build between them**, per buffer, until that buffer changes — they ask the
  same file-wide question. `completionsAt` and `signatureHelpAt` are not: they ask
  about a receiver's members, a scope chain or a callee's signatures, which are
  different questions and therefore different builds.
- **And since 2026-08-23 those four are ONE build across a whole WORKING SET**, if
  you ask for it: `prepare(files)` checks and captures the set in one build, after
  which every one of them is free in any of those buffers. `diagnosticsOf` needs no
  new call for the same effect — its memo is keyed by the partition the build
  walked, so a question about a subset of a set already asked about is answered by
  filtering. § 3a has both, with what invalidates them.

## 3a. Preparing a working set — the one call that changes the cost model

`prepare(files)` checks a set of buffers NOW, in one build, and every later
**semantic** query about any of them is answered without compiling.

```kotlin
// on idle, after the user stops typing
project.prepare(editor.openBuffers)          // ONE build

// afterwards, and until the next edit — no build at all
project.quickInfoAt(a, caret)                // 0
project.definitionsAt(b, caret)              // 0
project.fileSemantics(c)                     // 0
project.documentHighlightsAt(a, caret)       // 0
```

Before this, a query about a buffer cost one narrowed build, and a narrowed build
is mostly FLOOR: crawl, bind and the program-wide checker passes, which run whatever
file you ask about, against a median file's own checking of tens of ms. `N` buffers
therefore paid that floor `N` times. One `prepare` pays it once.

**That floor has since collapsed, so `prepare`'s ratio is smaller than the table
below** — it was ~345 ms when this section was written and is now 58 ms;
what this page measured itself on 2026-08-24 is the end-to-end consequence, a median
narrowed query of **108 – 137 ms** where six unprepared hovers cost 920 ms and a
`prepare` of the same six costs 466 ms. `prepare` is still the right call for a
declared working set — it is the only way to make a query about a buffer the user has
not touched free — but it is now roughly a 2x amortisation rather than a 7x one
(§ 13).

Measured on those sources, one build answering `k` queries against `k` builds
answering one each:

| queries per build | one build per query | shared | ratio |
|---:|---:|---:|---:|
| 2 | 39,173 ms / 76 builds | 21,918 / 38 | **1.79x** |
| 8 | 38,404 / 76 | 12,035 / 10 | **3.19x** |
| 26 | 39,508 / 76 | 10,347 / 3 | **3.82x** |

### That the shared answer is the per-query answer is SWEPT, not argued

A `Checker` carries caches that record which question reached a type FIRST, so
sharing one across queries could in principle make the ORDER of your queries
observable. `scripts/checker-reuse-differential.sh` compares the two arrangements
row for row. In an **editor-ordered** query sequence — a deterministic shuffle with
revisits, 101 queries over 76 files, 25 of them asked again later by a different
checker, 1,070,012 compared rows per run — **no row differs** at k = 3 and k = 8,
and a file asked twice is answered identically both times by a fresh checker AND by
a reused one. At k = 26 one row differs, and it is one the program-order sweep
already found: a redundant self-intersection that the shared arm renders correctly
and the per-query arm does not.

### What it does not do

* **It does not answer `diagnostics` or `diagnosticsOf`.** A capture build types
  nodes the checker had no reason to type, so its diagnostics are not
  interchangeable with a plain build's — the rule this page has always stated. For
  errors, call `diagnosticsOf(theSameFiles)`, which is itself one build for the whole
  set, and since 2026-08-23 is reused by every per-file question about a file in it
  (below).
* **It does not survive an edit**, and nothing here does. Prepare again on idle after
  each edit — the same rhythm you already debounce `diagnostics()` with.
* **It is not free.** It is a real build, and the first query is made dearer so that
  every later one is free. The work is proportional to the identifiers in the
  prepared files and the ANSWERS are retained until the next edit — one type and one
  definition per identifier per file, the largest value a `Project` ever holds.
  Prepare the buffers a user is looking at, not the program.

### Error reporting is the same shape, with no new call

`diagnosticsOf`'s memo is keyed by the PARTITION the build walked, so a query about
any SUBSET of a set already asked about is answered by filtering:

```kotlin
project.diagnosticsOf(editor.openBuffers)    // ONE build
project.diagnosticsOf(listOf(a))             // 0
project.diagnosticsOf(listOf(b))             // 0
```

which is what lets a per-buffer annotator run at editor speed: ask about the whole
open set once on idle, then answer each buffer from it.

### Ask for the whole open set in one call — this is a rule, not a tip

Decomposing a narrowed query found that `Σ own(F)` over 78 files is
6,841 ms against a 4,935 ms whole-program check — a **1.39x re-derivation tax**
— even though the spine walk itself partitions exactly (node counts sum to the
whole-program figure to the node). The extra 1,906 ms is shared type resolution
(lib types, foreign declarations, instantiations) that one build resolves once
and that every separate per-file query re-derives in its own fresh `Checker`
Averaged over a 108 ms median query that is **~24 ms — 22% of it**,
the largest single lever this arc has named at the median case.

The batching above is how a host collects that tax back: one `diagnosticsOf`
call over `N` files pays the floor and the shared derivation **once**; `N`
separate calls pay both **`N` times**. Measured on `docs/language-service.md`'s
own six-buffer arm (§ 13, `2fa8a39f`, 2026-08-24): the same six-file set asked
as **one call costs 321 – 342 ms**; asked **one file at a time it costs
748 – 771 ms**. That is wall time and therefore pinned by nothing — see § 13's
standing caveat — but the direction is arithmetic, not noise: one call always
pays one floor and one derivation, `N` calls always pay `N` of each, so the gap
between them can only widen with the size of the open set.

What this does **not** remove: the retained-checker replay collects the
FLOOR across queries (a held `Checker` re-entered for later `diagnosticsOf`
calls), but not the per-file re-derivation the 1.39x names — that tax is paid
once per **build**, not once per **process**, so it still falls on a host that
asks one file at a time even with the replay in play. The only way to collect it
is to name the files together.

**Automatic working-set growth is refused, deliberately.** It was priced:
growing the partition to "the queried file plus whatever was recently queried"
on every miss: it costs `k·floor + k(k+1)/2·perFile` against a cold
`k·floor + k·perFile` — a **loss at every k** — because each growth step redoes
the derivation for files already answered. A host knows its open buffers where
this layer does not, so the API will not guess a working set on the caller's
behalf; batching the visible tabs into one call is the host's job, not
something `Project` infers.

## 3b. What is measured against tsgo — its CLI, not yet its LSP

Both answer editor questions from a real type-check. **The table below compares this
API against tsgo's `--incremental` CLI in fresh processes — NOT against `tsgo --lsp`,
which is what an editor host would actually integrate.** `tsgo --lsp` is a long-lived
session with snapshot updates and lazy per-file checking: it pays no process start, no
`.tsbuildinfo` read and no tree re-stat per query, so the 182 ms floor visible in the
CLI column below is a property of the CLI harness, not of tsgo in an editor. The
long-lived comparison — `xtsc-lsp` against `tsgo --lsp`, both resident — is **measured
((LSP.3), 2026-09-01)**: see the LSP arm of
[`docs/perf/incremental-vs-tsgo.md`](perf/incremental-vs-tsgo.md), and its headline is
honest in their favour — per-edit hover is **~30-50x faster there** (12-18 ms against
our 398-630), because their lazy checker answers ONE node from NodeLinks-style caches
while every fresh caret here costs a narrowed build.

Measured on tsc's own 78 compiler sources, same tree, same two edits to one file — a
**body-only** edit (a local `const` inside an exported function) and a **signature**
edit (one added `export const`):

| | tsgo 7.0.2 CLI, `--incremental --noEmit`, fresh process per query | this API, in-session |
|---|---|---|
| full check, no prior state | **1,631 ms** | **5,352 ms** warm · **23,266 ms** cold JVM |
| nothing edited | **182 ms** | **0 ms** |
| after a body-only edit | **314 ms** | **232 ms** |
| after a signature edit | **1,654 ms** | **5,694 ms** |

**Read the caveats before the numbers.** On this project we report **46** diagnostics
where tsgo reports **65** — the margin is decomposed in
[`docs/perf/tsgo-diagnostic-gap.md`](perf/tsgo-diagnostic-gap.md) (19 genuine false
negatives of ours), and every ratio above flatters us by it. The two columns are also
different process models: tsgo's CLI cells each pay a fresh process that re-reads
`.tsbuildinfo` and re-stats the tree; ours is a live object that pays neither. Medians
of three, one machine, one day. Treat the table as a comparison of a CLI against a
resident API — it cannot say which *resident* design is faster, because tsgo's
resident mode is not in it.

### What the table can and cannot say

**Against the CLI, holding the program in memory wins the edit loop** — we answer a
body-only edit in less absolute time than a compiler whose full check is 3.3x faster,
because the CLI's per-query floor (process start, state read, re-stat) is 79% of what
that edit costs it. **Against `tsgo --lsp`, measured, the per-edit cells are theirs by
30-50x**: resident-vs-resident, their lazy per-node answering beats our
narrowed-build-per-question model, which is exactly the bin-B architecture gap
`docs/INVERSION-DESIGN.md` names and its Stage 1-2 exists to close. What survives on
our side of that measurement: the project-wide incremental `diagnostics()` wave
(46 rows / 5 files in 524 ms on didSave) has no tsgo-LSP equivalent at all.

**On first open, we lose badly and it is not close** — 23.3 s against 1.6 s, of which
~18 s is JVM start and JIT warm-up rather than compilation, and this cost is real in
*any* process model. A host that opens a project and wants diagnostics immediately
will feel it. It is an artifact-stack problem (a native image, an AOT cache, or a
resident daemon) rather than a property of the checker, but today it is real and you
should plan for it — for example by opening the project and running one query off the
critical path, before the user asks anything.

**The dependency closure buys nothing on THIS codebase and nearly everything on a
layered one.** tsc's own sources are `export *` barrels, so tsgo's per-hop pruning
recovers nothing here (1,654 ms against its own 1,631 ms cold check) and our
whole-program fallback costs the same. On an application-shaped 2,401-file tree the
same mechanism is worth almost everything to them — a signature edit costs tsgo
**304 ms against 427 cold** while we rebuild at **3,850 ms** — measured 2026-09-01 in
[`docs/perf/incremental-vs-tsgo.md`](perf/incremental-vs-tsgo.md) (Arm B), which
reopened and then refused the closure item ((INC.91)); the surviving design is the
closure-narrowed build.

### Two capability differences worth knowing before you design a host

These are from reading tsgo's LSP sources (`internal/project`, at `main` 2026-08-20),
not from the CLI table, so they survive the caveat above.

**There is a project-wide diagnostics call here, and it is incremental.**
`diagnostics()` answers about the whole program and, after an edit that moved no
exported signature, costs one narrowed build (§ 4b). tsgo's LSP has no equivalent
request: it serves `textDocument/diagnostic` per file plus push for open files, and
"what is wrong with my whole project" is answered by running `tsc --incremental` as a
separate process. If your UI has a project error list, that difference is structural
rather than a matter of tuning.

**Repeated and overlapping questions about one project state are free here.** A second
identical query builds nothing; so does a query about a subset of a set already asked
about, and so does one about a file no set covered (§ 4a). tsgo re-creates its checker
pool whenever the program is updated, so its per-file answers after an edit start from
a fresh checker — though its per-file first answer on a cold process will beat ours.

### The honest summary

(LSP.3) answered the question this table could not: resident-vs-resident, **their
edit loop is faster and it is not close** (per-edit hover 12-18 ms against our
398-630; first open 255 ms against our cold-JVM 24.8 s, though the two did different
work — theirs checked one node lazily, ours eagerly published the whole 46-row
project error list first). What is ours after measurement: the project-wide
incremental diagnostics wave their LSP structurally lacks, the embedding API, and the
no-Node-no-Go toolchain — and the per-edit gap is the inversion's to close
(`docs/INVERSION-DESIGN.md`). **A short-lived invocation** — a CI step, a pre-commit
hook, a one-shot check — remains the case tsgo's CLI is built for and where it is
straightforwardly better today.

The measurements, the harnesses and the source reading behind this section are in
[`docs/perf/incremental-vs-tsgo.md`](perf/incremental-vs-tsgo.md).

## 4. Diagnostics

```kotlin
project.diagnostics()                 // the whole program
project.diagnostics("src/a.ts")       // one file
project.files                         // every file in the program, crawl order
```

`Diagnostic` is the compiler's own type: `message`, `category`, `code`,
`fileName`, `line`, `character`, `start`, `length`, `relatedInformation`,
`messageChain`. `line` and `character` are **1-based**; `start` and `length` are
0-based offsets into the file text.

`diagnostics(fileName)` normalizes and absolutizes its argument exactly as
`updateFile` does, so either form works. A file that is not part of the program,
or has no diagnostics, yields an empty list rather than an error — "which errors
are in this buffer" has an answer for any buffer.

Every build passes `noEmit = true`. A tool that opens a project to ask questions
about it must never scatter JavaScript through the user's tree as a side effect
of a query, and with an editor overlay in play the output would correspond to
unsaved buffers anyway. Emitting stays `ProjectCompiler`'s job.

## 4a. Narrowed diagnostics — the one an editor should wire to

```kotlin
project.diagnosticsOf(listOf("src/a.ts"))          // the open buffer
project.diagnosticsOf(openBuffers)                 // every visible tab, one build
```

`diagnostics(f)` builds the whole program and keeps the rows naming one file.
`diagnosticsOf` narrows at the **source** instead: the file set is handed to the
compiler as its check partition, so the per-file check passes walk only those
files. The program is still crawled, parsed and bound in full — what is narrowed
is the *checking*, not the program — which is why the answer is the same one the
whole-program build gives for those files, including errors that can only be
found with the rest of the program in hand.

**Measured on tsc's own 78 sources (9,977,097 characters), warm, one process:**

| | |
|---|---|
| `diagnostics()` — the whole program | 4,566 – 4,767 ms |
| `diagnosticsOf` — one ordinary file | **1,165 – 1,231 ms** |
| `diagnosticsOf` — `checker.ts`, which is 31.6% of the program by itself | 3,828 ms |

**And it agrees, file for file.** `scripts/partition-equivalence.sh` runs a
partition of one for *every* file of a real project and compares its rows against
the full build's for that file: all 78 agree, and 5 of them carry the program's 46
diagnostics, so the agreement is not the vacuous kind you get from an all-clean
program. That sweep is the gate for this member; the suite cannot be, because a
corpus fixture is one or two files, where a partition of one is nearly the whole
program.

Three cost properties a host may rely on, all pinned by counting the builds that
reach the backing `Vfs`:

- a query on a **clean** project performs **no** build — the whole-program result
  is already in hand and filtering it beats compiling;
- a query on a dirty project performs exactly **one** build, however many files it
  names, so batch the visible tabs into a single call;
- a repeated identical query on an unchanged project performs **none**.

**What it deliberately does not do** is become the project's build. A partition's
diagnostics are a subset of the program's, so adopting one would make the next
`diagnostics()` report that subset *as the whole program's errors* — silently. A
whole-program query after a narrowed one therefore still costs a build. That is
the price of the narrow query being narrow, and it is what the sharpest pin in
`ProjectNarrowDiagnosticsTest` holds.

**It only pays on a large program, and that is worth knowing before you wire it.** The
saving is the *checking* of the other files; what remains — the crawl, the parse, the bind
and the program-wide passes — is a floor a narrow query pays in full. On tsc's own 78
sources that floor is 1,092 ms of a 1,107 ms query, i.e. a median file's own checking is
**15 ms**. On a three-file project the floor is 90% of a query that is already under
100 ms, and the narrow form measured **0.87x** — slightly *slower* than the whole build,
because there was nothing worth not doing. Neither case hurts a host that follows the rule
above (a clean project answers from the cached build and does not compile at all), but do
not expect a ratio on a small project.

### A file the host never named — the re-entry, shipped for diagnostics and refused for everything else

The three properties above are about a query the project has already answered, or
one whose files are a subset of a set it has. The case they do not cover is the
one an editor hits constantly: the user opens a file nobody declared, or the
annotator asks about a buffer that was not in the last batch. That used to pay a
whole narrowed build — the crawl, the parse, the bind and all ~417 checker `init`
passes. **It no longer does.**

**What it does.** The checker that answered the previous `diagnosticsOf` is still
alive, and ~110 of its 417 `init` rows never read the check partition at all: they
iterate the whole program, so they already emitted the new file's rows during the
first query and `getDiagnostics()` merely filtered them out. Only the
partition-dependent rows have anything left to do, and they are re-entered with the
widened partition. The handle is kept by `Project` for exactly as long as `cached`
is — i.e. until the next edit — and it is dropped by `updateFile`, `deleteFile` and
`close`.

**What it is worth, re-priced at HEAD for the diagnostics channel with no capture
request in either arm** (`scripts/inc40-replay-cost.sh`, tsc's own 78 sources,
three rotations after six warm-ups, in two independent JVMs):

| working set `k` | queries | fresh total | replay total | ratio | fresh per query (median) | replay per query (median) |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 77 | 10,656 / 10,783 ms | 4,728 / 4,685 ms | **2.25 / 2.30x** | **104 / 108 ms** | **25 / 25 ms** |
| 2 | 39 | 7,716 / 7,803 ms | 4,500 / 4,314 ms | 1.72 / 1.81x | 141 / 139 ms | 55 / 54 ms |
| 8 | 10 | 5,342 / 5,420 ms | 4,228 / 4,351 ms | 1.26 / 1.25x | 377 / 360 ms | 260 / 238 ms |

Two independent JVM processes, medians quoted as a band (CLAUDE.md: a sign-consistent
paired batch is not a result until a second batch replicates it). The floor they were
measured against is **54 ms** in the second batch and **61 ms [54, 62, 61, 53]** from
`scripts/partition-equivalence.sh` at the same commit. ARMING — the `RecheckHolder`
`diagnosticsOf` now passes on a project state's first narrowed query — is free within
the band (an armed 77-query sweep reads 10,546 ms against 10,783 plain, 106 against
108 per query) and changed no diagnostic row in 231 group comparisons.

The ratio FALLS as the working set grows because the thing the replay deletes — the
incremental floor — is paid once per QUERY and not per file, so a host that batches
its questions has already collected most of it (§ 4's subset rule). The row a user
feels is `k = 1`. And the replay arm's TOTAL (4,728 ms) lands on the whole-program
CHECK cost (~4,935 ms), which is the **1.39x re-derivation tax** being
collected rather than re-paid: 77 fresh checkers each re-derive the shared lib and
foreign-declaration resolutions, one live checker does not.

Note that this number is NOT comparable to the original **3.06x** or to the
**1.68x** the arc later recorded: both of those were measured with a whole-file
CAPTURE request in both arms, which is +9-17 ms of common cost per query that
dilutes the ratio. Measured that way at HEAD the same run reads **1.34x**
(replay 12,063 ms against 16,139 ms over 75 questions) — so the with-capture
lineage has continued to decay exactly as CLAUDE.md's law predicts, and it is the
capture-free diagnostics channel that is worth wiring.

**Why it serves diagnostics and NOTHING else.**
`scripts/replay-differential.sh` compares a re-entered answer against a fresh
narrowed build's, file by file, over two projects and three channels — diagnostics,
captured definitions, and the captured type at every identifier. At HEAD:

```
realism      (tsc's own sources)
  compared: files=75 diagnosticRows=46 filesCarryingDiagnostics=5
            typeSpans=373879 definitionSpans=352713
  DIVERGED: 43 of 75 file(s)   — 0 DIVERGE-DIAG, 0 DIVERGE-DEF, 43 DIVERGE-TYPE

sensitivity  (test-fixtures/partition-gate — 178 rows over 71 files carrying them,
              from 78 distinct netting passes against the profile's ONE)
  EQUIVALENT: all 75 files agree (diagnostics, types, definitions)
```

Every **diagnostic** row agrees, on both arms, and so does every one of the 352,713
**definition** spans. The **captured type** channel diverges in **43 FILES of 75** —
**796 spans of 373,879 (0.213%)**, and note the units: 43 is a count of FILES, not of
rows or of spans. (41 distinct basenames; tsc has three `utilities.ts`.)

**Those 796 spans were classified against tsc 7.0.2's own LSP, and the arm that
loses is the REPLAY.** An earlier revision of this page said the rows were the
union-alias family "in which the fresh arm is not automatically the correct one" —
an inference, never tested. Tested, **that clause is false for this
population.** Reduced per ELEMENT and nesting-aware (796 rows are
**37** distinct `(fresh, replay)` element pairs, and 192 rows carry more than one
differing element, so a row count over-reports), with every one of the 37 causes
sampled through `--lsp -stdio`:

| verdict | rows | files |
|---|---:|---:|
| **REPLAY WORSE** | **413** | 36 |
| BOTH WRONG | 375 | 17 |
| REPLAY BETTER | 8 | 4 |
| EQUIVALENT | 0 | — |

**393 of the 413 are the alias-display race, and the replay owns MORE of it for a
structural reason.** `aliasDisplayMap` is id-keyed and FIRST-WINS over INV.5(a), which
interns a union by its member-id list alone, so a registered alias renames that interned
union everywhere — whatever the reference site spelled. A fresh narrowed build resolved
essentially only the queried file; the replay carries the seed build **plus every earlier
recheck**, so more aliases are registered and more unions get renamed. tsc and the fresh
arm hover `Identifier | PrivateIdentifier`, which `utilitiesPublic.ts:857` literally
writes; the replay hovers `MemberName`. **So this is not a different defect — it is more
of the alias-display family's, one type-former up, and it degrades with how much the session has already
resolved.** The remaining 20 are outright lost resolutions (`Connection[][]` read as
`any[][]`, `Map<string, SeenPackageName>` as `Map<any, any>`), where tsc confirms the
fresh arm. Both classes are silent in the dangerous direction: a plausible-looking type,
never an error.

*The 375 BOTH-WRONG rows are neither arm's fault — they are an ordinary-build defect,
led by 213 rows where `Visitor`/`VisitResult<T>` renders `(node: TIn) => any` and tsc
renders `Visitor`. Tracked separately.*

**And opening the valve was priced before it was refused**: one hover through the replay
is **33 ms** against a fresh narrowed build's **121 ms** (3.67x, 88 ms), but only for the
first hover in a file at a program state some earlier query already built for, with no
edit since — `quickInfoAt` memoises per buffer and any edit drops the handle. 88 ms on
that row does not buy 413 worse answers in 36 of 43 files.

**What here is pinned and what is not.** *Pinned by a test*: that the diagnostics path
reaches a re-entry and that the capture-serving members still pay a build
(`ProjectRecheckWiringTest`), and that no `TypeCaptureRequest` is expressible at the
valve (it is a type, so the compiler is the pin). *Pinned by a re-runnable gate, not by
the suite*: the channel counts and the 43/75 line (`scripts/replay-differential.sh
realism`) and the classification (`Inc41ClassifyMain` + `scripts/inc41_classify.py`).
*Pinned by NOTHING* — wall time on one box, re-take rather than quote: the 33 / 121 ms
hover pair and every ratio derived from it. Do not inherit the verdict table without
re-asking the oracle (`scripts/lsp_hover_project.py`); an audit once measured a doc section
decaying at about one false claim per three rounds, which is exactly how the clause
corrected above survived. Full derivation:
`docs/inc41-replay-capture-classification.md`.

A hover that quietly drops an inference is worse than a hover that took 500 ms —
the same judgement made when capture narrowing was refused over 45 divergent
spans, a bar this population exceeds ninefold (0.11% against 0.012%). So the split is
enforced by a TYPE and not by a comment: `Project` holds the
handle only through a private one-way valve (`DiagnosticsOnlyRecheck`) whose single
member takes a `Set<String>` and returns a `List<Diagnostic>`. No
`TypeCaptureRequest` is expressible at that boundary and no `CapturedType` can leave
it, so `quickInfoAt`, `definitionsAt`, `completionsAt`, `signatureHelpAt`,
`semanticsAt` and `prepare` cannot reach it even by mistake.
`ProjectRecheckWiringTest` pins both halves — that the diagnostics path DOES reach
a re-entry (a count of builds, not a time) and that the capture-serving members
still pay a build.

**The lesson that outlives it.** A partition or replay change must be graded on the
CAPTURE sweep, not only the diagnostics sweep. The diagnostics half was *completely
silent* about the original 8-file defect: a lost collector or a lost type-parameter
scope surfaces as a wrong TYPE, never as a missing error. That is why the
diagnostics channel could be shipped on the strength of two arms agreeing and the
capture channel could not.

**When to use which.** Wire `diagnosticsOf` to the editor's per-file annotator —
it is the query an IDE actually makes, and the one whose cost falls with the size
of what the user is looking at rather than with the size of their project. Keep
`diagnostics()` for the whole-project error list a host shows on demand, on a
build, or in a background pass.

## 4b. Why `diagnostics()` is usually not a rebuild

`diagnostics()` answers about the whole program, so for a long time it rebuilt the
whole program — 4.9 s per edit on tsc's own 78 sources. It no longer does.

After an edit, the project asks ONE question: **did the edit move what an importer
of the edited file can observe?** An edit inside a function body leaves every
exported signature intact, so no other file's diagnostics can have changed, and the
answer is the previous build's rows with the edited file's rows replaced — one
narrowed build. Only an edit that actually moves an exported TYPE forces a rebuild.

This is deliberately **not** a reverse-dependency closure. Measured on tsc's own
sources, both a file-level and a symbol-level use graph re-check 100% of the
program's characters at the median edit — those files genuinely use symbols from
most other files, and the relation is transitive. Asking whether the symbols MOVED
collapses that to nothing, however dense the graph is.

**What it costs you when the guess is wrong.** A signature-changing edit costs two
builds instead of one: the narrowed build that discovers the surface moved, then the
rebuild. Measured over 40 real commits to tsc's own `src/compiler`, 27 of 40 moved
no signature — so the bet pays about two edits in three.

**When it does not apply at all**, and each is checked rather than assumed: a config
edit; an edit to a file that was not in the program (a new file changes what every
importer resolves); an edit that changes the program's file set, such as adding an
import; and an edit to a file whose export surface cannot be summarised exactly —
a SCRIPT file (no module syntax, so its top-level names are program-wide), a global
augmentation, or an export the summary cannot enumerate. Any of these is a plain
rebuild.

**The guarantee.** The incremental answer is the whole program's answer. It is
graded as a differential over those 40 real commits — the answer after an edit
against a project opened fresh on the edited text, row for row — and it agrees on
all 40, with 27 of them actually answered incrementally rather than falling back.
Row ORDER is preserved too: the edited file's new rows are spliced where its old
rows were, not appended.

You do not have to do anything to get this. It is what `diagnostics()` does.

## 5. Editing in memory

```kotlin
project.updateFile(path, text)   // an unsaved buffer; also creates files that are not on disk
project.deleteFile(path)         // shadows a file as absent; undo by updateFile-ing it back
project.reloadFile(path)         // forget everything about it — what is on disk is the truth again
```

Nothing touches disk. An overlaid file need not exist there: adding one makes
the next build discover it through the glob and resolve imports to it, **even in
a directory that exists nowhere but in the overlay**. That last part is three
separate mechanisms rather than one — module resolution probes `exists` before
reading, the crawl asks `isDirectory` per entry and descends only on yes, and
the glob finds roots through `list` alone — which is why each is pinned
separately in the tests.

`updateFile` marks the project dirty unconditionally, even when the text equals
what the last build saw. Skipping that would be a cheap optimization and a bad
contract: it would make "did my edit take effect" depend on a string comparison
you cannot see.

Overlay edits cannot be served a stale parse. The parse cache is keyed by path
*and validated against the exact content*, with no mtime, size or `stat`
anywhere in the decision — so different overlay text misses by construction.

`reloadFile` is the third change kind and the one a host reaches for when it does
NOT have the new text: an external edit, a VCS checkout, a buffer reverted to what
is on disk. It drops the overlay entry *and* anything retained under
`trustFilesystem` below, and marks the project dirty exactly as `updateFile` does.
Without it a host that has ever overlaid a buffer can never hand that path back to
the filesystem, so it must retain the last text of every buffer it ever saw and
bound that by throwing whole projects away.

### 5a. `trustFilesystem` — letting the host's promise remove the re-read

```kotlin
project.trustFilesystem = true   // opt-in; off by default
```

Every build re-reads every non-overlaid program file, although the parse is already
fully content-cached across builds: the bytes are read only to compute the content
key that proves the cached tree still applies. That is O(project) work on every
keystroke. A compiler cannot promise itself otherwise — a `Vfs` whose backing store
changes underneath is exactly what the promise excludes — but a host can, and on the
IntelliJ platform the IDE's own VFS is authoritative and guarantees change
notification.

**What you are promising, exactly.** That the BYTES of a file will not change
without this project being told, through `updateFile`, `deleteFile` or `reloadFile`.
Nothing more:

* **Additions and deletions are still discovered** on every build. Nothing caches the
  file set — the root-file glob still lists directories through the `Vfs` and module
  resolution still probes candidates with `exists` before reading them — so a file
  that appears or disappears behind the promise is still seen.
* **`tsconfig.json` and `package.json` are never taken on trust.** They decide what
  the program *is* rather than what one file says, they are read a handful of times
  per build rather than once per file, and a stale one is a wrong PROGRAM rather than
  a wrong diagnostic.

So the one thing that goes wrong when the promise is broken is a file whose content
changed on disk with nobody saying so: this project keeps reporting about the text it
last saw, silently. Setting the property back to `false` drops everything retained,
so withdrawing the promise is always safe.

**What it is worth, and the part that is free.** Two halves land together. The
retention removes the read; `Vfs.readTextIfResident` removes the per-file THREAD
HANDOFF the read forces — the crawl hands every file to an IO dispatcher so a
blocking read cannot starve the pool the parses run on, and on an application-shaped
project that handoff is what a per-file read costs. Measured, 8 instrumented draws
per arm, one JVM per arm, arms rotated across processes, with the untouched
sequential specifier-resolution row as the control:

| project shape | crawl WALL | of which read+decode |
| --- | --- | --- |
| 2,401 files of ~1 KB | 30.6 / 37.0 → **21.7 / 19.4 ms** | 132.6 / 176.1 → 1.52 / 1.39 ms |
| 78 files of ~128 KB | 13.7 / 14.2 → **9.5 / 7.8 ms** | 65.4 / 63.2 → 0.076 / 0.057 ms |

An UNSAVED BUFFER takes the resident path with no promise at all — it is in memory by
construction — so that part costs nothing and is on by default.

Pinned by `ProjectTrustedFilesystemTest`, including the documented limit (an
unreported content change IS missed, and `reloadFile` is what makes it visible
again), the two soundness claims above, and the `false` default.

## 6. Positions

Editors speak (line, character); the compiler speaks offsets.

```kotlin
val pos: TextPosition? = project.positionAt(path, offset)      // → line, character (1-based)
val off: Int?          = project.offsetAt(path, line, character)
```

Both are **1-based**, matching `Diagnostic.line` / `Diagnostic.character`, so a
diagnostic's coordinates and a converted offset are directly comparable. If your
protocol is 0-based — LSP is — convert at your own boundary, once.

Neither call builds, so you can convert coordinates on a dirty project for free.

The two failure modes differ, deliberately, and a host that receives coordinates
from elsewhere needs both: an **unknown file** (or one with no text) yields
`null`, while a **line outside the file's range** throws
`IllegalArgumentException`, as does an out-of-range offset in `positionAt`. A
`character` beyond the end of its line is clamped into the line rather than
rejected, since that is what a click past the end of a line means. So a
coordinate that arrived from a client whose buffer is one keystroke ahead of
yours can throw — catch it and treat it as "no answer yet" rather than
propagating it.

Line terminators: `\n`, `\r\n` and a lone `\r` all break a line. U+2028 / U+2029
deliberately do **not** — tsc splits there but this compiler does not, and a
coordinate that no diagnostic of ours can ever carry is worse than none.

All three terminators agree across the whole compiler, and that is pinned rather
than assumed: a lone `\r` numbered by the parser and ignored by the
checker, so that a syntax diagnostic and a semantic one disagreed about the line —
has since been closed, and `ProjectPositionTest`'s `a lone CR file's diagnostics
agree with this map too` fails on any compiler that reopens it.

## 7. Syntactic queries: what is at this position

```kotlin
val info: NodeInfo? = project.nodeInfoAt(path, offset)
// NodeInfo(kind: String, start: Int, end: Int, ancestorKinds: List<String>)
```

`kind` is the AST kind name in TypeScript's own vocabulary (`"Identifier"`,
`"JsxOpeningElement"`). `ancestorKinds` runs outwards — parent first,
`"SourceFile"` last, empty for the source file itself. `start`/`end` are the
node's **real** span (see the caveat below).

This parses one file and never builds, so it is cheap enough for
per-keystroke use: brace matching, "am I inside a comment or a string", a
document outline, deciding whether a semantic query is even worth making.

### The boundary rule you must know

The span is **half-open**: `offset == start` is inside the node, `offset == end`
is outside. This matches `Diagnostic.start`/`length`, the AST's own convention
and tsc's `getTokenAtPosition`.

The consequence is that a caret immediately *after* an identifier is not on it —
`abc|` does not resolve to `abc`. Editors usually want the preceding token
there. That preference deliberately lives in your layer, not this one: **ask at
`offset`, and if you want the touch behaviour, ask again at `offset - 1`.**
Building the preference in would make two adjacent nodes both contain the
boundary, and an ambiguous primitive cannot be layered on.

A caret in whitespace or inside a comment belongs to no node, so the innermost
*enclosing* node answers. **A caret on a real construct never should**, and in older
versions several did; if you are writing a host, that is
the failure shape to test for, because an enclosing node is a plausible-looking
answer rather than an obviously wrong one. An offset past end-of-file, a negative offset, or an
unknown file returns `null` rather than throwing — "is there a node here" has a
truthful negative answer.

> **Why `start`/`end` are computed and not just read off the node:** in this
> compiler `Node.end` is the end of the token *following* the node, not the end
> of the node — the parser reads it after a one-token lookahead. So raw AST
> spans **overlap between siblings**, and `[pos, end)` is not a containment
> test: in `const abc = 1;` the identifier `abc` carries `[6,11)` though its text
> is `[6,9)`, which puts a caret on the `=` inside `abc`. `NodeInfo` reports the
> real end, snapped back to the token stream. You never see the raw value — this
> note exists so the numbers are not surprising if you compare them against a
> tree you parsed yourself.

## 8. Semantic queries: hover

```kotlin
val info: QuickInfo? = project.quickInfoAt(path, offset)
// QuickInfo(kind: String, displayString: String, start: Int, end: Int)
```

`displayString` is the type at that position as the compiler renders it — the
same renderer that writes type names into diagnostics. `kind` is the node kind;
`start`/`end` are the real span, suitable for highlighting.

Returns `null` when there is no node at the offset, when the node has no type,
or when the file is unknown.

**The type is captured while the checker is walking that position, not asked
afterwards** — and this is not an implementation detail you can ignore if you
plan to extend the API. Measured, on one checker instance, asking after the fact
gives:

| position | captured (correct) | asked afterwards |
|---|---|---|
| top-level annotated `const` | `string` | `string` |
| body local shadowing a global | `number` | **`string`** |
| `typeof`-narrowed parameter | `string` | **`any`** |
| parameter at its use | `number` | **`any`** |
| arrow-body parameter | `string` | **`any`** |
| class-method parameter | `number` | **`any`** |

Five of six differ. A parameter answers `any` because nothing durable binds one
outside the walk — and `any` is the one answer that is silent at every use site,
so the wrong version *looks* plausible. The type at a position depends on
walk-scoped state (`currentLocalTypes` and the surrounding frame) that only
exists while the checker is at that position, so any new semantic feature must
capture during the walk too.

### A MEMBER name reports the member's type

The `p` of `o.p` is not a name any scope binds, so asking the compiler
for "the type of `p`" is the wrong question — in older versions that is what this
did, and the answer was `any` where nothing in the file shared the spelling and
**the type of whatever unrelated binding did** where something shared it. Measured
against tsc 7.0.2's own language server, twelve of fifteen member positions in a
fixture whose properties are deliberately spelled like file-level `const`s of other
types answered with the collider: `o.k` read `boolean` for a `string` property.

The rule now is tsc's own: **the type of a member name is the type of the access it
is the name of.** So everything the checker knows about member access applies with
no rule of its own —

| position | reported |
|---|---|
| `o.p` where `p: string` | `string` |
| `box.value` where `box: BoxLike<number>` | `number` — instantiated, not `T` |
| `o.inherited`, declared on a base | the base's declared type |
| `u.p` where `u: A \| B` | `string \| number` |
| `t.k` where `t: T`, `T extends Shape` | through the constraint |
| `n.q` inside `if (typeof n.q === "string")` | `string` — **narrowed** |
| `C.s`, a static | `number` |
| `this.p`, in a method or any nesting of arrows | the field's type |
| `super.p` | the **base's** member, not an override |
| `o["p"]` and `` o[`p`] ``, caret on the member-naming literal | the member's type |
| `N.T`, a qualified type name | the declared type |

The table would be incomplete without one more row — a member's own **declaration** name
(`p` in `interface Shape { p: string }`, a class field, a method, an accessor, a
static, a `#private`, a type-literal member). It is bound by no scope either, so before
an older version went through the same wrong question and reported `any`, or the type of
whatever unrelated binding shared the spelling — the same collider shape, one position
over. It now reports the member's own type, resolved through its **owner** (§ 9). An
overload set reports the whole overloaded type rather than the signature under the
caret, which is coarser than tsc and never wrong.

That row is finished by the one member declaration kind the owner leg could
not reach — an **enum member's**. An enum's declared type is a member-LESS object here
(this compiler mints one opaque type for the whole enum where tsc models it as the union
of its members), so asking the owner for `Alpha` found nothing and the name fell through
to the free-name path and reported **`any`**: not an absent answer but a wrong one, and
until recently the one live violation of *prove to offer* on this page. It now reports
the member's own type — the very instance its USE reports, minted only through the
compiler's own interning helper — so `Alpha` in `enum Plain { Alpha }` reads
`Plain.Alpha` at its declaration and at every use. tsc says `(enum member) Plain.Alpha =
0` there: it decorates the answer with the member's VALUE, and this API renders TYPES
(that is what `displayString` is), so the value is deliberately not part of it — which
also means an ambient member with no value, which tsc reports as plain
`(enum member) Amb.Iota`, is no special case here.

`this` and `super` are the one shape needing a second mechanism, for the reason
§ 9 gives about go-to-definition: they are plain identifiers in this parser, so
they type as `any` and the access does too. The class is recovered from the
walk-scoped state instead. When it cannot be — a caret in a **static** member,
whose `this` is `typeof C` and which this compiler does not model — the answer is
`any`: a non-answer, never a wrong name.

**HOVER STILL PICKS ONE SUBJECT WHERE A SPAN NAMES TWO — but only for a SHORTHAND.**
Go-to-definition, find-references and rename get the contextual member
behind an object-literal key and behind both shorthands (§ 9, § 10b). The TYPE was
left alone for a while, on the ground that a contextual type is walk-scoped state a
capture cannot read. That ground had already gone — the contextual walk is purely
syntactic — and hover now has it too: **an object-literal key, computed or not, reports the
CONTEXTUAL member's type**, or its own value's type where nothing types the literal,
which is what tsc reports in both shapes. Before it, every key answered `any`, or the
type of whatever unrelated binding happened to share the spelling. What remains is the
shorthand `{ p }`, which reports the **local** it references — true about a different
subject than tsc, which describes the contextual member. That one is a display choice,
not a resolution gap: a hover is one line of text and cannot show two.

One display note, because it looks like a bug and is not: a type reported here is
named by the compiler's own renderer, which names an interned type by whatever
alias the program gave it. With `type Alias = NS.T` in scope, hovering `T` renders
`Alias`. That is the same synonym a free type name renders and it is not specific
to members.

## 9. Semantic queries: go to definition

```kotlin
val places: List<DefinitionLocation> = project.definitionsAt(path, offset)
// DefinitionLocation(fileName: String, start: Int, length: Int, kind: String)
```

`start until start + length` is the declaration's **name** — `foo`, not the whole
class body it heads — which is what an editor wants to highlight and where tsc's
own go-to-definition navigates. A declaration with no single-token name (a
binding pattern, a computed member name) falls back to the whole declaration:
coarser, never wrong.

### Four mechanisms, because a member is not bound by any scope

Each mechanism exists because the one before it cannot reach the caret in question.

**1. A free name** is resolved through the lexical scope chain in force at that
position. **2. A member name** — the `p` of `o.p` — is resolved through its
**receiver**: `o`'s type is computed and `p`'s property symbol on that type is the
answer. **3. A member's own declaration name** — the `p` of
`interface Shape { p: string }` — is the receiver's exact dual: it has no receiver,
and what it does have is the class, interface, type literal or enum it is declared
**in**, so that **owner** is asked. **4. An object-literal key** and a
**shorthand** are resolved through the literal's **contextual type**, found by walking
*out* of the literal to whatever supplies it — an annotation, a call's parameter, a
`satisfies`, a `return`, an enclosing literal's own key, an array element position,
a ternary branch. That walk is syntactic, so it is a function of the caret's
position and not of what the checker happens to be doing when it passes; the
checker's own contextual type is walk-scoped ambient and is absent outright in some
of those positions.

That split is not an implementation detail, it is the whole correctness argument.
A scope lookup of `p` finds whatever unrelated `p` happens to share the spelling,
so a member answered that way is a *plausible location in the right file* that has
nothing to do with what the user clicked. Member resolution went through the
compiler's own member tables for the same reason: that is what makes an
**inherited** member answer with the *base's* declaration and a **generic
instantiation** answer with the declaration rather than the substituted type.

What answers, concretely:

| you point at | you get |
|---|---|
| `o.p`, `o.m()`, `this.p`, `super.p`, `C.staticP` | the property/method/accessor declaration |
| a member of an **imported** interface | the declaration, in the declaring file |
| an **inherited** member | the **base's** declaration, not the derived type |
| a member declared by **merged** interfaces (overloads) | **one location per contributing declaration** |
| a member of a **union** receiver | **one per constituent** that declares it, in constituent order |
| `N.x` / `N.T` where `N` is a namespace, module alias or enum | the export's declaration |
| `o["p"]` and `` o[`p`] ``, caret on the member-naming literal | the property declaration |
| `const { p: local } = o`, caret on the `p` | the property declaration |
| `{ p: v }` where something contextually types the literal | the **contextual** type's member |
| `{ p: v }` where nothing does | the key **itself** — it is the declaration |
| a member's own **declaration** name (`interface I { p }`, a field, a method, a static, a `#private`, a type-literal member, an enum member) | **itself** — and every other declaration of the same member: a merged block's, an overload's, an accessor pair's other half |
| `{ p }` (object literal, contextually typed) | **both** the local and the member |
| `const { p } = o` (binding shorthand) | the local and the property declaration |
| `"s".length`, `arr.push` | the **lib**'s declaration (see the lib note below) |

**`this` is a receiver, and where it points is a property of the position.** An
arrow function does not rebind `this` — TypeScript gives it whatever encloses it —
so a caret on `this.` inside an arrow, inside an arrow inside an arrow, or inside an
arrow in a constructor, getter, setter or property initializer, answers with the
enclosing class's members. `super.p` rides the same carrier and answers the **base's**
declaration — the override's, never — measured against tsc.
Everything that *does* rebind `this` answers **nothing**
rather than guessing: a `function` expression or declaration at any depth (TypeScript
types its `this` as `any`, and the compiler emits TS2683 for a member read there), an
object literal's own method, a static member (whose `this` is `typeof C`), a class
*expression* (which the compiler's `this` context cannot name), and a caret in no
class at all. The bias is *prove to offer*: an empty answer here means "not a class
instance", never "lost".

**An empty list is a normal answer**, and these are the ways to get one:

- there is no node at the offset, or the file is unknown;
- the offset is on a keyword, a literal or trivia — nothing either mechanism binds;
- the name resolves to a symbol with no declaration to point at;
- **nothing declares the member** (`(o as any).absent`) — silence, never the
  nearest same-named anything;
- **an object literal's own member declaration** (`{ om() { … } }`) — the contextual-key
  leg answers a `{ p: v }` key and its shorthands, and an object literal's METHOD is
  deliberately outside it: a contextually typed literal's method is an occurrence of the
  contextual type's member, and resolving it to itself would take it out of rename's
  completeness net without putting it in the group. tsc answers it; this is a stated
  divergence, in the conservative direction;
- **a chained namespace segment** (`A.B.x`) — the middle segment would have to be
  resolved the same way, for a case one caret to the left already answers.

**An implementor's own member answers ITSELF, not the interface's.** `class C implements
I { p = 1 }` declares its own `p`, and a caret on it navigates there — which is what tsc
answers. The relation to the base is a *reference* fact, not a navigation one, and § 10b
is where it shows up.

**More than one location is normal too.** Declaration merging is a language
feature — an `interface` declared twice, a function and a namespace of the same
name — so every contributing declaration comes back, in the compiler's own
deterministic order. Do not assume `single()`; if you need one, take the first.

**An imported name answers about the original.** `import { foo } from "./m"` and
then a use of `foo` navigates to `foo` in `./m`, not to the import statement. If
the module cannot be resolved you get the import binding itself, which is
truthful and less useful.

**The declaring file may be one you cannot open.** A definition in a library
answers with that library's name (`lib.es2020.d.ts`), which has no path on disk.
Handle that rather than assuming every `fileName` is openable — the span is still
exact, because the length is computed inside the compiler from the declaring
file's own text.

Like hover, this is captured **while the checker walks** rather than asked
afterwards, and for a reason one indirection away from hover's. The state that
answers "what does this name refer to here" is the lexical scope chain, and it is
torn down per file when the walk leaves it. Measured, on one checker instance:

| position | captured (correct) | asked afterwards |
|---|---|---|
| file-level `const` | its own declaration | the same |
| body local shadowing a file-level `const` | **the body declaration** | **the file-level one** |
| parameter at its use | **the parameter** | **nothing at all** |

The body-local row is the dangerous one: not a coarser answer, a *different
declaration* — an editor would send the user to the wrong line and look like it
worked.

## 10. Semantic queries in bulk — the one an editor should use

```kotlin
val many: List<SemanticInfo> = project.semanticsAt(path, listOf(142, 190, 240))
val whole: List<SemanticInfo> = project.fileSemantics(path)
// SemanticInfo(start: Int, end: Int, kind: String,
//              quickInfo: QuickInfo?, definitions: List<DefinitionLocation>)
```

Both are **one compile**, whatever the number of positions. The reason is that a
capture was always a *set*: the compiler is handed the spans before the build and
records the type and the definition at each of them during the single walk it was
going to perform anyway.

**So is asking one caret at a time**, which is an inversion of what
this section used to say and is worth reading as such. It measured a 34-identifier
fixture at *one compile batched against 34 unbatched (34x), and 68 when each caret was
asked both ways (62x)*, and told hosts to batch for that reason. `quickInfoAt` now names
the whole BUFFER's span set rather than the caret's, so those 34 carets are **one
compile** however they are asked, and asking both ways is **still one**.

| what | compiles, before | compiles, now |
|---|---|---|
| `fileSemantics` — all 34 spans | 1 | 1 |
| `quickInfoAt` x 34 in one buffer | 34 | **1** |
| `quickInfoAt` + `definitionsAt` x 34 | 68 | **1** |

It is a count of compiles, so this holds at any project size. What the batch still buys
is convenience — every answer at once, in one value — and what it costs is that the
FIRST query in a buffer now types the whole buffer; § 13 prices both halves.

**What comes back.** One `SemanticInfo` per **distinct span**, sorted by
`(start, end)` ascending — not one per offset. Several carets inside one
identifier are one question and collapse to one entry, and an offset that lands
in no node contributes none, so the result is neither indexed by nor the same
length as your input. Map back by containment, under the same half-open rule as
everywhere else:

```kotlin
val entry = many.firstOrNull { offset >= it.start && offset < it.end }
```

The ordering is imposed by the API rather than inherited from the compiler, whose
answer order is the order its walk happened to reach the nodes.

**`quickInfo` may be null and `definitions` may be empty** — independently. An object
literal's own METHOD name has a type and, deliberately, no definition (§ 9). An entry with neither is still returned: "there is a node here and the compiler had nothing to
say about it" is a different answer from "there is nothing here", and only your
UI knows which to draw.

**What `fileSemantics` enumerates: every `Identifier`, and nothing else.** No
keywords, no punctuation, no literals, no larger expressions. Member names are
included — they are identifiers and they are typed. The rule is deliberately one
sentence long, because the alternative is a taste-driven list that drifts; if you
want the type of a larger expression, ask `semanticsAt` for the caret you
actually have.

**An empty request does not build.** `semanticsAt(f, emptyList())`, a list of
offsets that all land outside every node, and a file with no identifiers each
answer an empty list without compiling.

**The caveats are hover's, unchanged.** It builds, that build is not the
`diagnostics()` build and never becomes it, and it reads your overlay. Batching
changes how many compiles you pay for, nothing about what one compile is.

## 10a. Completions

```kotlin
val list: CompletionList = project.completionsAt(path, offset)
// CompletionList(kind: CompletionKind, prefix: String,
//                replacementStart: Int, replacementEnd: Int,
//                items: List<CompletionItem>, refusal: CompletionRefusal?)
// CompletionItem(name: String, kind: String, typeText: String,
//                optional: Boolean, readonly: Boolean, accessibility: String)
```

**Both halves are answered.** A caret after a `.` or a `?.` gets the members of
the expression to its left. A caret at a free position gets everything the
lexical scope chain binds there. `kind` says which question was asked, and it is
reported whether or not any items come back — a receiver with no members and a
position where nothing may be completed at all are both empty lists, and you will
want to tell them apart. **Keywords are offered at a free caret**; see below.

**This is the one query whose position is not a node.** Every other semantic
member starts from a node at the caret; a completion request has none by
construction, because the user is mid-identifier or sitting right after a dot
with nothing typed. So the caret is resolved against the *token stream*, and the
receiver is then recovered from the parse. What follows from that:

- `o.` at end of file, before a `}`, or with the caret parked on the next line
  all work. Our parser always builds a property access for a `.` — it
  synthesizes an empty name and reports TS1003 — so there is a real receiver node
  even when nothing has been typed after the dot.
- A caret inside a **string, template, regular expression, numeric literal,
  comment or JSX text** answers `kind = NONE` / `refusal = NO_COMPLETION_CONTEXT`,
  **and does not compile**. A `.` inside a string or a comment is not a member
  anchor, and what a user types between two JSX tags is prose. (JSX text joined
  that list when the token index learned to see it at all.) **The one
  exception is the member-naming literal of an `o["…"]`**, which is a member position —
  see below.
- A caret inside the **member-naming literal of an element access** — `o["p"]` and,
  `` o[`p`] `` — is a MEMBER caret, and the receiver is the
  expression before the `[`.
- A caret at the very **end of the file** is a real position here, unlike
  `nodeInfoAt` (§ 7), whose spans are half-open and exclude it.

**Filtering is yours, and deliberately so.** `items` is *not* cut by `prefix`.
Ranking — prefix versus substring versus fuzzy, case sensitivity, how a
`_`-prefixed member sorts — is host policy, and a list that has already been cut
cannot be re-ranked. So you get the full candidate set and the prefix beside it.

**Two spans, and they are different quantities.** `prefix` is what the user has
typed and is what you filter by. `replacementStart until replacementEnd` is what
accepting an item must *replace*, and it covers the whole word the caret is in —
so accepting in the middle of `o.fo|o` leaves no `o` behind. With no word under
the caret the two offsets are equal and an accepted item is inserted.

**Inside `o["`, which is a member caret** (this is a change from
an older version). `kind` is `MEMBER`, the receiver is the expression before the `[`, and
the member list is the same one a dot gets — the same union rule, the same
accessibility filter, the same `this` and export-table legs. Three things are
particular to it, all measured against tsc 7.0.2:

- **`replacementStart until replacementEnd` is the literal's TEXT, quotes
  EXCLUDED**, and an item's `name` is the member's own spelling, unquoted. Accepting
  one therefore leaves exactly one pair of quotes. It is the same span a member
  rename writes into (§ 10d), so completing a name and then renaming it edit the
  same characters.
- **A member whose spelling is not an identifier is offered** — `"has space"`,
  `"1abc"` — which is the reason element access exists. An index signature
  contributes nothing, because it is not a name a user can accept.
- **A half-typed `o["` with no closing quote is answered**, which is the state a
  completion request is normally made in. `o["p"|]`, past the closing quote, is a
  free-name caret again.

**Inside a `` o[`p`] `` TEMPLATE too** — and the reason this
changed is worth reading, because it was once refused for exactly one reason and
the reason was removed rather than overruled: the refusal said "§ 10b's
occurrence sweep is string literals only, so a member written through a template is
one a rename cannot find". The sweep now finds it, so the refusal has nothing left to
protect. The two share ONE enumeration, which is what keeps them from drifting apart
about what a member name is. A no-substitution template only: a template carrying a
**substitution** (`` o[`p${x}`] ``) spells no fixed name, and tsc offers nothing inside
its head either.

Three positions inside a string are deliberately still `NONE`, each stated because
tsc answers differently: a caret **at** the opening quote, where tsc offers free
names; an **indexed-access type** (`type T = Bag["p"]`), where tsc offers free names
rather than members; and a string whose **contextual** type is a literal union or a
`keyof` (`f("|")`), which tsc completes and which is a different resolution rather
than a different anchor.

**What the member list contains.**

| receiver | what you get |
|---|---|
| object / interface / class | its own members and its bases', an override once |
| `A & B` | the members of both |
| <code>A &#124; B</code> | only the members present on **every** constituent |
| <code>T &#124; undefined</code> | `T`'s members — nullish constituents are skipped |
| namespace, module alias, enum | its export table |
| `this` | the enclosing class's members (nothing inside a `static` member) |
| primitive, type parameter | the apparent type's — the lib wrapper, the constraint |
| `any` | nothing, which is also tsc's answer |
| unresolvable | nothing — never the nearest same-named thing |

The union rule is deliberately **not** the rule go-to-definition uses on the same
receiver (§ 9, which collects every declaration). "Where is `p` declared" is
asked about a name already in your text and every declaration of it is a real
place to go; "what may I write here" must not offer something that will not
compile.

Items are **deduplicated by name and sorted by name** — the order is imposed by
the API, because a member table's own iteration order is an implementation
property.

**Accessibility is enforced** (this changed; earlier versions offered every member).
A `private` member — including a `#name` field — is offered only inside its
declaring class; a `protected` one only inside that class or a class deriving
from it. Statics obey the same rule, and a caret in a nested arrow inside a
method counts as inside that method's class. The base may be in another file:
the heritage walk follows the import.

The filter **hides only what it can prove inaccessible.** A member whose
declaring class cannot be found, a base named by an expression the compiler does
not resolve there, a heritage chain past its depth cap — every unknown leaves
the member offered. That bias is the reason the feature could be turned on at
all: a list that has silently lost a real candidate looks exactly like a
complete one. `accessibility` still reports what survived, so greying an item
out instead of hiding it is still open to you.

`kind` is how you tell a method from a property — there is no separate flag.

**What the free-name list contains.** Everything the scope chain in force at the
caret binds, from the innermost level outwards: the enclosing function's
parameters and type parameters, its locals and its nested declarations, every
enclosing block's and loop's and `catch`'s bindings, the enclosing class's and
interface's type parameters, the enclosing namespaces' members, the file's own
declarations and its imports, and finally the merged and lib **globals** filtered
by what is actually visible in *this* file (one module's exported name is not
offered inside another).

**Each spelling appears once, as the innermost binding.** A body local that
shadows an import is one item and it is the local — its `kind` is
`VariableDeclaration` where the import's would be `ImportSpecifier`. The
enumeration is literally the same chain walk go-to-definition performs to resolve
one name, run to exhaustion, which is the whole correctness argument: *a name the
list offers is a name `definitionsAt` will resolve, and a name it hides is hidden
because something nearer binds the spelling.*

**A free-name item carries no `typeText`** — it is `""`, and `optional`,
`readonly` and `accessibility` are likewise not answers there. `kind` is the
answer, and it is the icon a completion widget wants. The reason is measured, on
this repo's own compiler-profile fixture (78 files, ~10 M chars, real libs):

| quantity, at a caret in a real function body | measured |
|---|---|
| items offered | **1,628** |
| the enumeration itself | **0.39 – 0.64 ms** |
| adding `typeText` to every item | **+2.6 – 14.3 ms** (4 – 28× the enumeration) |
| items whose `typeText` would render `any`/`error` | **618 of 1,629 = 37.9 %** |

That last row is the deciding one: a free name may name a *type* — an interface,
a type alias, a namespace — for which "the type of the symbol" is not a
meaningful question, and decorating 38 % of the list with `any` is worse than
decorating none of it. (On a two-file toy project the cost argument bites too:
2,232 items there, enumeration 0.55 ms, and typing them all is 26–170 ms against
a whole query of 125–360 ms.) If you want the type of the item your user has
highlighted, ask `quickInfoAt` for that one item — the shape an LSP server's
`completionItem/resolve` already has.

**Keywords are offered** (this changed; earlier versions returned symbols only), with
`kind = "Keyword"`. Round 918 refused them because a useful list is
context-sensitive and the anchor knew what preceded the caret, not which grammar
production it sat in. It knows now.

| where the caret is | what you get |
|---|---|
| a statement may begin here | the statement and declaration starters **plus** the expression starters |
| an expression position | the expression starters only — this is what keeps `interface` out of `f(\|)` |
| a type position | `any bigint boolean keyof never null number object string symbol typeof undefined unknown void` |
| a class or interface body, a heritage clause, an import clause | **nothing** |

and each of these needs its context, or it is not offered: `await` an enclosing
`async` function, `yield` a generator, `super` a class, `return` a function,
`break` a loop or a `switch`, `continue` a loop, and `import` / `export` /
`declare` / `namespace` / `interface` / `type` / `enum` a module or namespace
body rather than a function body.

**The list is short by choice, not by omission.** Continuation keywords —
`else`, `case`, `extends`, `implements`, `as`, `satisfies`, `infer`, `readonly`,
the accessibility modifiers — are offered nowhere, because their positions are
ones this classifier declines to name. Merge your own list for those. What every
item here does guarantee is that it *compiles where it is offered*, which is the
property the member half already had. One more coarseness worth knowing: a caret
whose word is already a complete keyword (`if|`) usually reports the *expression*
position, because the parser has built the statement that keyword starts and the
caret is inside it — that loses suggestions and never invents one.

Keywords appear at free-name carets only; after a `.` no keyword may be written.
A spelling the scope chain also binds (a variable named `type` is legal) comes
back once, as the binding.

**Two known imprecisions, stated rather than hidden.** A name declared *later* in
the same block is offered (a block's bindings are a set, not a sequence — the
binding exists and is merely in its temporal dead zone, which is what tsc offers
too). And a function's body locals are visible from inside its own *parameter
defaults*, because the binder's function scope is flat; writing one there is an
error the checker reports separately.

**Cost: one compile, one caret.** The same caveats as hover (§ 8): it builds, and
that build is not the `diagnostics()` build. Batching many carets is
`semanticsAt`'s question, not this one. Measured on the compiler profile above
(2026-08-24, commit d018af0a, four rotations in each of two processes), a MEMBER
completion is **194 – 202 ms** — essentially all of it the narrowed build, with the
enumeration under a millisecond of it. The **5.3 – 8.9 s** this paragraph used to
quote is history: it predates the narrowing of the capture
path, and is **~24x stale**. **So debounce anyway** — 200 ms is not a
per-keystroke budget on a project of that size, and it is paid on EVERY
invocation, because this query shares its build with nothing (§ 13).

## 10b. Find references, and document highlights

```kotlin
val all: List<ReferenceLocation>  = project.referencesAt(path, offset)          // the program
val here: List<ReferenceLocation> = project.documentHighlightsAt(path, offset)  // this file
// ReferenceLocation(fileName: String, start: Int, end: Int,
//                   isDeclaration: Boolean, use: ReferenceUse)
```

**Identity is the declaration set, never the spelling.** Every identifier in every
program file goes into ONE build; the checker resolves each of them as it walks past
— through the lexical scope chain for a free name, through the receiver's type for a
member, exactly as § 9 does — and two occurrences are the same thing when their sets
of declarations **intersect**.

Intersection rather than equality for one measured reason: a member of a union
receiver resolves to one declaration *per constituent*, so `u.p` on
`{p: string} | {p: number}` would land in a different group from a plainly identical
`a.p`. Equality is the degenerate case and is what every single-symbol position gets.

None of the following is special-cased; all of it falls out of that rule:

| you point at | what you get |
|---|---|
| a use of an **imported** name | the export, the `import { … }` clause and every use, in both files |
| the **export** itself | the same group, from the other side |
| a name **shadowed** by a body local | only the binding the caret is on — three same-spelled bindings are three groups |
| a **merged** symbol (`interface` twice) | one group, with **every** contributing declaration flagged |
| a **member** (`o.p`) | its uses plus the declaration, in the declaring file |
| an **inherited** or generically instantiated member | the base / uninstantiated declaration, so uses through both sides are one group |
| an **overloaded** member | one group, both signatures flagged |
| a member named by a **string literal** (`o["p"]`) or a **template** (`` o[`p`] ``) | that access, with the span covering the text *between* the delimiters |
| a member named by a **binding element** (`const { p: local } = o`) | the `p`, and not the `local` it binds |
| a member's **implementors** (`class C implements I`) | every class that declares it under a declared `implements`/`extends` |
| an object-literal **key** a contextual type supplies (`{ p: v }`) | that key, from either side |
| a **shorthand** (`{ p }`, `const { p } = o`) | the token is in the **member's** group; a caret *on* it answers the **local's** |
| a member's own **declaration** name | the whole group, from either side, whether or not the member is ever used |
| one declaration of a **merged**, **overloaded** or **accessor-paired** member | the other declarations too, all flagged |

**Three kinds joined the population, and the third is not like the other two.** An element access and a binding element's property name are ordinary members
whose *name* is not an identifier after a dot; they resolve through a receiver like
everything else, and the only new thing about them is where the receiver comes from
(the access's own expression, and the type the pattern destructures).

An IMPLEMENTOR is different: `class C implements I { p = 1 }` declares its own `p`,
which is a separate declaration, and it is in `I.p`'s group because a **declared
heritage edge** ties the two. Three properties of that edge are worth knowing, and
all three were measured against tsc 7.0.2 rather than chosen:

- **Structural compatibility does not count.** A class with the same members and no
  `implements` is a different symbol — it answers its own two references, not the
  interface's thirteen.
- **It is transitive.** An `override p` in a class extending an implementor is in the
  interface's group, two edges away.
- **It does not chain between siblings.** With `interface A { p }`,
  `interface B { p }` and `class C implements A, B { p }`, a caret on `A`'s `p`
  answers `C`'s `p` and every use of `C` — and does **not** answer `b.p` or `B`'s
  `p`. Each occurrence carries its own symbol plus the bases that symbol implements,
  and two occurrences are the same thing when those sets meet; a transitive closure
  over the whole group would merge the two interfaces, and tsc does not.

**ONE SPAN, TWO SYMBOLS — and the relation between them is not symmetric.** A `{ p }` is one token that names a *local* and a *property*, and so is
a `const { p } = o`. Measured on tsc 7.0.2, the two directions differ:

- the **member's** group **contains** the token;
- a caret **on** the token answers the **local's** group and nothing else — two
  spans, not the member's whole group.

So a capture that files one answer per span is not the obstacle it looked like; what
was needed was a *role*. `CapturedDefinition` now carries three declaration sets, and
each is read by a different question: `locations` is what the caret means (and where
go-to-definition goes), `related` is a symmetric tie that also seeds a caret (the
heritage edge, and an object-literal key's own property), and `shorthand` is the
asymmetric one — it puts the token in the member's group without ever seeding from
it. That is the whole mechanism, and it is why the two groups never merge through the
span they share.

A **contextual key** is the symmetric case of the same shape. `{ p: v } satisfies
Shape` both *declares* the literal's own `p` and *refers to* `Shape.p`, so a caret
there answers the union of the two groups — including an `o.p` that reads the
literal's own property — while `Shape.p`'s own group does not contain that `o.p`.
Where **nothing** contextually types the literal, the key is only a declaration and
its group is that literal's own reads.

Not every position supplies one, and the boundary is tsc's: a generic call whose
type argument is **inferred** (`takesGeneric({ p: 1 })` assigned to `Shape`) supplies
a naked type parameter, which names no member, so its key is **not** in `Shape.p`'s
group. An **explicit** `takesGeneric<Shape>({ p: 1 })` is.

A caret on `C`'s own `p` legitimately answers **both** groups, because that member
really is both.

**READ versus WRITE is reported** (an earlier version refused to classify).
`use` is one of `READ`, `WRITE`, `READ_WRITE` or `UNCLASSIFIED`, and it is a fact
about the *occurrence*, so one symbol's hits routinely carry several values.

- `WRITE` is the left of a simple `=` — including a member, `o.p = 1`, where only
  the last segment is the write — a destructuring target in either bracket form at
  any depth, with defaults, renaming, shorthand and rest, the head of a
  `for (x of/in …)`, a parameter's own name, and a variable or binding-element
  declaration's own name.
- `READ_WRITE` is the compound assignments and `++` / `--`, prefix and postfix.
- `UNCLASSIFIED` is an occurrence that is not a value use at all: a type-position
  name, a declaration name that binds no storage (a function, class, interface,
  type alias, enum, namespace, type parameter, import or export specifier, class
  member name), an object-literal key being declared, a binding element's source
  property name, a label. It exists so that what the classifier does *not* place
  stays visible instead of being defaulted to a read — which is precisely what
  an earlier version refused to ship.

**The declaration comes back, flagged.** `isDeclaration` marks the spans that *are*
declarations rather than uses, the way tsc's `isDefinition` does — filter on it if
you want uses only. It is exact: membership in the set the compiler produced, not a
guess about which parent kinds declare a name.

### What is refused, and why

- **A caret on a MEMBER's own declaration name is no longer a special case** —
  Such a name was once bound by no scope and had no receiver, so
  it resolved to nothing and the search had to recover it from the sweep's own evidence:
  if some occurrence resolved *to* that span, the caret was one of that symbol's
  declarations. That recovery answered an **empty** list for a member declared and never
  used, and it could only ever recover the ONE declaration under the caret. It now
  resolves through its owner and to its whole symbol, so a never-used member answers
  itself and a merged / overloaded / accessor-paired member answers its siblings' names
  too. The evidence recovery survives underneath as a fallback and nothing depends on
  it.
- **Identifiers, and EVERY LITERAL IN A MEMBER-NAME POSITION** — the `"p"` of an
  `o["p"]`, of a `` o[`p`] ``, of a `{ ["p"]: v }`, of a `{ "p": v }`, of a
  ``{ [`p`]: v }`` and of a class's or an interface's `["p"]`. That is one
  predicate rather than three, so the set a caret may land in, the set this sweep
  reports and the set a rename edits cannot drift apart. A keyword, any other literal,
  punctuation or trivia answers empty **and does not build**; and a literal is swept
  only where it NAMES a member, so `const unrelated = "p"` and an unrelated `` `p` ``
  are not references to `p`, which is the difference between this and a text search.
- **A computed key that is a NAME** (`{ [K]: v }`) is a reference to the binding `K`
  and to nothing else — it spells no fixed member name, the value is decided at run
  time, and that is tsc's answer there too (measured: the const's own two spans).
- **The program, not the libraries.** A declaration in a `lib.*.d.ts` comes back
  (flagged), because the caret resolved to it; no lib file is swept for uses.

Sorted by `(fileName, start)` ascending, one entry per span. `start until end` is
half-open and **exact** — the identifier's own text, not a raw `Node.end`.

### Cost, measured

On this repo's own compiler profile — tsc's 78 source files, 9,977,097 characters,
**381,670 identifiers**, real libs, warm. Re-taken **2026-08-24 at commit d018af0a**,
two independent processes; the round-930 column is kept so the staleness is visible
rather than silently overwritten. **No number in this table is pinned by any test**
(§ 13 says why):

| query | builds | earlier | 2026-08-24 | moved? |
|---|---|---|---|---|
| a plain rebuild, for reference | 1 | 5.5 – 5.9 s | **4.86 – 5.10 s** | no |
| `documentHighlightsAt` — `binder.ts`, first caret | 1 | — | **336 ms** | — |
| `documentHighlightsAt` — `binder.ts`, later caret | **0** | 19 ms | **10 – 18 ms** | no |
| `documentHighlightsAt` — `checker.ts`, first caret | 1 | 6.0 – 7.2 s | **3.34 – 3.58 s** | **1.9x** |
| `documentHighlightsAt` — `checker.ts`, later caret | **0** | — | **104 – 115 ms** | — |
| `referencesAt` on a **clean** project — the whole-program sweep, still what a REFUSED closure costs | 1 | 8.3 – 9.9 s | **8.8 – 9.6 s** | see below |
| `referencesAt` on a **dirty** project, same | 2 | 13.0 – 13.5 s | **13.2 – 13.9 s** | see below |

**The last two rows are the load-bearing ones, and the first of them has since
MOVED — the paragraph that used to stand here said it never could, and it was
wrong.** `documentHighlightsAt` had moved already because it goes through
`captureIn`'s partition. `referencesAt` was reported as unmovable "because its CLAIM
is about every file", which conflates the claim with the EVIDENCE: an occurrence can
only be an answer if it SPELLS a name the symbol is reachable by, so the population
is selectable before it is typed even though the claim stays program-wide. See
below for what it now costs and for the shapes that still fall back.
`renameAt` (§ 10d) and a plain `diagnostics()` are unchanged and remain
whole-program operations a host must budget for.

**Widening the population cost nothing measurable, and the reason is a counter
rather than a stopwatch.** Widening the swept population from identifiers to *identifiers plus the
string literals that name a member* takes it from **381,670 to 381,672** on that
profile — tsc's own compiler sources contain exactly **two** `o["…"]` accesses in
9,977,097 characters. The heritage edge is computed per member occurrence during the
same walk and did not move the wall either: `referencesAt` on `SyntaxKind` measures
**9.1 – 13.0 s** here against the 10.6 – 16.0 s recorded before it, i.e. the same
band. `fileSemantics` is untouched by construction — it enumerates `identifiers()`,
whose contract did not change — and measures 5.6 – 10.6 s for `checker.ts`'s 16,274
spans. Absolute milliseconds are only comparable within the run that took them; the
**population** is the figure that transfers.

A later widening took it further — to identifiers plus every literal in a
member-NAME position, which adds computed and quoted object-literal keys and computed
member declarations — and the argument above is unchanged in shape: such literals are
rare in real TypeScript, and tsc's own sources are the extreme case of that. The counts
here have **not** been re-taken, so read them as that earlier measurement and not as
today's.

### The sweep is narrowed by SPELLING — what it costs, and what still falls back

**The rule.** An occurrence can only be an answer if it SPELLS one of the names the
caret's symbol is reachable by. So the population is selectable before it is typed:
`referencesAt` computes that name set, selects the occurrences that carry one, and
`captureIn` derives the check partition from the resulting request. The CLAIM is
unchanged — the answer is still about every file — and the answer itself is unchanged,
which is a differential rather than an argument (below).

**The name set is normally one name.** It grows only through `import { p as q }` and
`export { p as q }`, the two forms in which one symbol carries two written spellings,
and it grows by a fixed point that never opens a file the search had no other reason
to open: both names of a specifier are tokens of the file that DECLARES the alias, so
a file introducing an alias of a name already in the set necessarily contains that
name.

**What still sweeps the whole program**, unchanged and by design, because these bind a
symbol to a spelling written nowhere near the other one: a **default export** and the
local a **default import** binds, an **`export =`**, an **`import x = require(…)`**, a
**namespace import or export**, and any closure that reaches the spelling `default`
(`export { foo as default }`). A refusal costs exactly the behaviour that shipped
before this existed.

**Measured on the compiler profile** (78 files, 9,977,097 characters, warm, both arms
interleaved in ONE process at the same caret — the only comparison this box supports).
`partition` is a COUNTER and is the column that transfers; the milliseconds are wall
time on one box and are pinned by no test:

| caret | hits | partition | narrowed, first ask | narrowed, repeat | whole-program |
|---|---:|---|---|---|---|
| `createTypeChecker` | 3 | **2 of 78** | 5,359 ms *(also the process's first sweep)* | 130 ms | 11,112 ms |
| `emitFiles` | 4 | **2 of 78** | **553 ms** | 141 ms | 9,532 ms |
| `transformNodes` | 6 | **3 of 78** | **528 ms** | 135 ms | 9,320 ms |
| `forEachChild` | 1 | **1 of 78** | **510 ms** | 131 ms | 9,143 ms |
| `checkSourceElement` | 71 | **1 of 78** — but that file is `checker.ts`, 31.6% of the program | **1,940 ms** | 119 ms | 9,291 ms |
| `SyntaxKind` | 9,827 | **49 of 78** — the worst realistic case | **4,904 ms** | 150 ms | 9,078 ms |

Read the FIRST-ask column as the per-query cost: **510 – 553 ms for a name written in
one to three ordinary files (17 – 18x), 1,940 ms when the one file is `checker.ts`
(4.8x), and 4,904 ms for a name imported nearly everywhere (1.85x)**. The narrowing
never loses — the fallback is the old path exactly — and the first row's 5,359 ms is
what a process's FIRST reference sweep costs however it is computed, not a property of
the caret. The repeat column is `captureIn`'s memo, which the whole-program arm never
reached: a second identical search at one program state is now free.

**How it is graded, and how to re-take any of this.** Correctness is a DIFFERENTIAL and
therefore needs no baseline: `Project.narrowReferenceSweeps` is the in-binary OFF arm and
`scripts/reference-narrowing-differential.sh <projectDir> <carets>` runs both over a real
project, comparing every answer element for element — **EQUIVALENT** — 60 carets drawn by stride over all 381,775 occurrences, **59 of them actually narrowed** (the control), **0 diverged**, 12,248 hits compared element for element; mean partition **17.5 of 78 files**, aggregate 182.0 s narrowed against 561.6 s whole-program (**3.09x** on a draw that lands proportional to occurrence count, i.e. on the hottest names). Read its `narrowed=`
column as well as `diverged=`: a caret that refuses falls back and then agrees with
itself, so a run in which everything refused would print EQUIVALENT having tested
nothing. The cost table above is `ReferenceNarrowingCostMainKt`, and per-shape behaviour
is pinned by `ProjectReferenceNarrowingTest`.

**The population census that says why this works, and where it does not.** Over every
distinct name in tsc's own compiler sources, the MEDIAN name is written in **1** file
and occurs **3** times in the program; p90 is 5 files and 22 occurrences. Weighted by
where a caret actually lands the picture is much worse — the median caret's spelling is
in 28 of 78 files, because `node`, `type` and `kind` dominate the occurrence count — so
a search for a very common word narrows little, and a search for the name a user
actually asks about narrows enormously.

**In the FALLBACK** — a closure the sweep cannot bound — the sweep itself is 2.5 – 4 s
on top of the rebuild it rides, whatever the caret: resolving 381,670 identifiers
costs the same whether the answer is 168 hits in one file (a local of
`createTypeChecker`) or **9,827 hits across 49 files** (`SyntaxKind`, imported nearly
everywhere). That insensitivity to the answer's size is exactly what the narrowing removed
for every caret it can bound, and it is what the paragraph below was written about. The second build in the dirty row is
`files`' — the program's file list is a question only a build answers — so a host
that has just asked for `diagnostics()` pays one build, not two.

**Budget memory as well as time, and the number is smaller than this page used to
say.** Re-measured 2026-08-24 per memory POOL (the honest reading; summing pool peaks
taken at different instants over-reads badly and can even exceed `-Xmx`): a
`referencesAt` sweep on this profile peaks at **1,077 – 1,125 MB in G1 old gen**, with
**264 MB still live after a full GC** — i.e. most of it is transient allocation rather
than retention. **`-Xmx2g` is the floor**: measured green at 2g and 3g and 6g, and
`OutOfMemoryError` at 1g. The "**~1.9 GB**" this paragraph used to quote overstated
what is HELD by ~7x, which matters because an IDE budgets retention, not allocation.
`documentHighlightsAt` does not have this shape at all — it holds one file's.

**And that 264 MB is now ATTRIBUTED, which is what a host actually needs**
(2026-08-25, `docs/perf/language-service-retention.md` — a ten-step subtraction
ladder over `liveAfterGc`, two processes agreeing to 0.6 MB):

| retainer | MB | share | survives `close()` |
|---|---:|---:|---|
| `Project.sourceIndexes` — the parses `referencesAt` walks | 114.7 | 43.5% | no |
| `CrawlParseCache` — the BUILD's parse of the same 78 files | 103.0 | 39.0% | **yes**, process-global |
| `RealLibSnapshots.parseCache` | 2.6 | 1.0% | **yes** |
| JVM baseline + embedded lib text + the 9,827 answers | 43.7 | 16.5% | — |

Three things follow, and all three matter to someone deciding whether this fits
in an IDE's JVM. **The program was parsed TWICE and both copies were kept** —
the same 78 files, the same content, the same `computeParserFlags`, 217.7 of the
264 MB between them (step 2 below removes one copy). **Every memo this API adds
is free**: `cached`, `captures`, `prepared`, `narrowed`, `recheck` and
`lineMaps` are **0.0 MB combined**, so the weight-bounded capture lanes
are doing their job and no further memo needs a budget line. And **the
per-project MARGINAL figure is ~115 MB, not 264**: a second `Project` in the
same process re-earned 105.9 MB of shared caches and then added 115.3 MB of its
own, so budget **`103 + 115·N`** for N open projects — the 103 is paid once,
because it is keyed by content and shared.

**Step 2 removed one of the two copies, and the retained figure is now
`264 -> 177 MB` (−33%).** `Project` no longer parses the program a second time:
its syntax layer indexes tokens around the tree the compiler's crawl already
built (`parsedSourceOrNull` / `SourceIndex.around`), so the `sourceIndexes` row
falls **114.7 -> 27.5 MB** while every other row is unchanged and
`referencesAt`'s answer is bit-for-bit the same 9,827 hits. An unsaved buffer
still parses privately — that is the correct answer, not a shortfall — and the
copy is collected the next time that file is asked about after a build. **What
the 27.5 MB still is**: ~18 MB of `SourceIndex`'s own token arrays, which
nothing else in the process holds, and ~10 MB of a second copy of the source
TEXT, which is a named next lever rather than a surprise.

Both heap figures are pinned by no test and never can be (a sized assertion is a
coin flip); re-take them with `scripts/inc36-retention.sh`.

That a highlight row is a statement about a FILE and not about the API is unchanged
and is why the table names files: `binder.ts` (7,787 occurrences) and `checker.ts`
(125,289) differ by 10x in the first-caret row and by the same factor in the residue.
The current runner is **`scripts/inc31-ls-cost.sh`** (it supersedes
`scripts/round930-ls-cost.sh`, which cannot reach the completion, signature-help,
prepare-vs-completion or per-pool-heap cells); § 13 carries the whole table.

**So: `documentHighlightsAt` is the one to wire to caret movement** (debounced —
it still builds), and `referencesAt` is the one a user asks for explicitly.

## 10c. Signature help

```kotlin
val help: SignatureHelp? = project.signatureHelpAt(path, offset)
// SignatureHelp(signatures: List<SignatureInfo>, activeSignature: Int, activeArgument: Int)
// SignatureInfo(label: String, parameters: List<ParameterInfo>,
//               returnTypeText: String, activeParameter: Int)
// ParameterInfo(name: String, typeText: String, optional: Boolean, isRest: Boolean,
//               labelStart: Int, labelEnd: Int)
```

**Every overload comes back, in declaration order** — that is the feature. An
editor shows "2 of 3" and lets the user page through, so answering with the one
signature overload resolution would pick is the failure this exists to avoid.

**Two different negatives.** `null` means *the caret is in no argument list*: on
the callee, past the closing paren, in a comment, in an unknown file. A non-null
answer with an **empty** `signatures` means *the caret is in an argument list and
the callee has no signatures* — it is `any`, unresolvable, or not callable. Only
the second is worth a log line.

**The anchor is a token question, like a completion's.** There is no node at the
caret in `f(a, |)`; and for an argument list the user has not closed — `f(` at end
of file, `f(a,` before a `}` — the call node's own real end lies *before* the
caret, so no descent reaches it. The parser does build the call in every one of
those cases (it creates a `CallExpression` the moment it sees a `(` and then
reports the missing `)`), so the argument list is recovered by **bracket matching
over the token stream** and the argument index by **counting commas**, which is
what an argument index physically is. Two consequences worth knowing:

- an argument list left open ends at the token that closes the *enclosing*
  construct — the `}` of the function you are typing in — rather than running to
  end of file;
- a comma that belongs to one of the arguments is not a separator, so a nested
  call, an object literal and a `Map<string, number>` type argument all count as
  one argument with no rule of their own.

**`activeArgument` is a fact about the text; `activeParameter` is per signature.**
The first is the number of this argument list's commas before the caret, so it may
exceed every signature's parameter count. The second is what it means *for one
signature*, and it **clamps to a rest parameter**: at `push(a, b, c|)` the third
argument still highlights `...items`. A signature with no rest that has run out of
parameters reports `-1` rather than pointing at its last one.

**Which signature is active.** The first that could still become this call: one
with room for the argument the caret is on (its index is within the parameter
list, or the signature ends in a rest parameter, or it takes no parameters and
none have been passed), *and* that accepts every argument you have already
**finished** — judged by the same predicate the compiler selects an overload with.
The argument the caret is *in* is deliberately not judged: it is half-typed by
construction, so testing it would flip the highlighted overload back and forth
under the user's hands. When nothing qualifies the answer is `0`, reported rather
than hidden.

**The label, and the ranges inside it.** `label` is the whole signature on one
line — `pickFrom<T>(xs: T[], index: number): T` — with `new ` in front of a
construct signature, and the callee's own spelling when it has one (a callee that
is an expression, `(fs[i])(…)`, contributes no name rather than an invented one).
Each `ParameterInfo` carries `labelStart until labelEnd` **into that label**, which
is what an editor bolds. Every type in it goes through the same renderer
`quickInfoAt` uses, deliberately: a host must never have to reconcile two spellings
of one type.

**A generic callee renders uninstantiated** — `<T>(xs: T[], …): T`, not a
substitution. Inferring `T` would mean inferring it from arguments that are not
finished, and the declared form is what tells the reader that `T` is inferred at
all.

**What answers, concretely.** A plain function, an **overloaded** one, a **method**
through a receiver, a **constructor** (`new C(`), an **imported** function, a
**namespace** member, a callee that is itself a **call** (`f()(`), a **decorator
factory** (`@dec(`), and a function with a **destructured** parameter — that last
one is rendered from its declaration, because the compiler drops binding-pattern
parameters from a signature's symbols and rendering from the symbols alone would
print one parameter short with the survivor wearing its neighbour's type.

**What is refused, each for a reason.**

- **tagged templates** (`` tag`a${b}` ``) — no parenthesized argument list, and
  counting template substitutions as arguments is a second mechanism;
- **type arguments** (`f<|>(x)`) — not an argument list;
- **`super(...)`** — `super` is an ordinary identifier in this parser and binds to
  nothing, so it answers an empty signature list rather than the enclosing class's
  base constructor;
- **a spread's arity** — `f(...xs, |)` reports argument 1, because the commas say
  so and how many arguments `xs` contributes is not a syntactic question.

**Cost: one compile, one caret,** with hover's caveats (§ 8) — it builds, and that
build is not the `diagnostics()` build. A caret in no argument list does not build.
So debounce, exactly as for completions.

## 10d. Rename

```kotlin
val plan: RenamePlan = project.renameAt(path, offset, "betterName")
// RenamePlan(oldName: String, newName: String, files: List<FileRename>,
//            refusal: RenameRefusal?, conflicts: List<RenameConflict>)
// FileRename(fileName: String, edits: List<RenameEdit>)
// RenameEdit(start: Int, end: Int, newText: String)
// RenameConflict(kind: RenameConflictKind, fileName: String, start: Int, end: Int, detail: String)
```

**Nothing is applied.** A host owns its buffers, so the answer is a value. What is
promised is that it is *directly* applicable: one file's edits are non-overlapping and
sorted by `start` ascending, so

```kotlin
for (file in plan.files) {
    var text = buffers[file.fileName]!!
    for (edit in file.edits.asReversed()) {
        text = text.substring(0, edit.start) + edit.newText + text.substring(edit.end)
    }
    buffers[file.fileName] = text
}
```

is the whole application — no offset arithmetic, no re-derivation.

**The contract in one line: `refusal != null` if and only if `files` is empty.** A
refusal never comes with a partial plan, because a partial rename produces code that
does not compile and is worse than no rename; and a plan never comes with a refusal
attached as a warning you may ignore. `conflicts` is the EVIDENCE for a refusal the
search discovered rather than a separate severity — it is empty for a successful plan.

### The occurrence set is find-references'; the EDIT PLAN is the work

Identity is the declaration set, never the spelling (§ 10b), so a shadowed binding, an
import hop, a merged symbol and a member through its receiver all behave exactly as
`referencesAt` documents. What rename adds is that **an occurrence is not always
replaced by the new name**. Two constructs spell a binding AND a property with one
identifier, and the plain rewrite compiles while changing what the program means; two
more spell a local AND a module's public name, and there the plain rewrite is what this
API does deliberately:

| source | renaming | becomes |
|---|---|---|
| `const o: I = { p }` | the local `p` | `{ p: newName }` |
| `const o: I = { p }` | **`I.p`** | `{ newName: p }` |
| `const { p } = o` | the local `p` | `const { p: newName } = o` |
| `const { p } = o` | **the member** | `const { newName: p } = o` |
| `export { p }` | the local `p` | `export { newName }` — see below |
| `import { p } from "./m"` | the symbol | `import { newName } from "./m"` |

**The shorthand rows are the discriminator this feature is tested against**, and
The contextual-member work doubled them. `{ newName }` compiles and it has renamed the object's KEY;
and *which* of the two expansions is correct depends on which of the token's two
meanings the caret named. Both compile. Both are one edit at one span. No assertion
about the number of edits can tell them apart, which is why every rename pin for this
shape asserts the resulting TEXT.

**The last two are a deliberate divergence from tsc, and it is worth knowing.** tsc
expands both — `export { newName as p }`, `import { p as newName }` — because it holds
the local and the exported symbol as two symbols and renames only the one your caret is
on. Here they are ONE symbol (that is what lets find-references answer across the
import hop at all), so the whole group is renamed together and the plain replacement is
the consistent one: expanding would make `export { p }` behave differently from
`export const p`, whose public name a rename does change.

### Then it is CHECKED, by applying it and compiling again

The plan is applied to a scratch copy of the program — your buffers are untouched —
and that program is built. Three things must hold or the plan is withdrawn:

1. **it re-reads.** Every position the plan says it put the new name is re-parsed and
   must in fact hold it.
2. **no new diagnostic.** This is what catches a COLLISION: renaming onto a name already
   declared in a scope the rename reaches is a redeclaration error. *tsc's own language
   server does not check this* — measured, `renameAt("p" -> "useZ")` in a file that
   already has `const useZ` gives you two of them.
3. **nothing resolves anywhere else.** Every renamed occurrence, and every identifier
   that ALREADY spelled the new name, must resolve after the rename to exactly what it
   resolved to before. This is the CAPTURE check and it is the one a diagnostic count
   cannot do: renaming a file-level `a` to `b` where some function body holds its own
   `b` moves that body's reads onto the local, with types that agree and no error
   anywhere.

So the safety claims here are claims about a compiler run rather than about a reading
of the code. That is also why this costs a second build.

### What is refused, and why each is a refusal rather than a gap

| `RenameRefusal` | when |
|---|---|
| `NOT_AN_IDENTIFIER` | the caret is on a keyword, a literal, punctuation, trivia, an unknown file — **no build** |
| `NEW_NAME_IS_RESERVED` | `class`, `return`, `let`, … — **no build**. tsc does not check this and will write `const class = 1` |
| `NEW_NAME_IS_NOT_AN_IDENTIFIER` | decided by SCANNING the name, so `Ünïcödé` passes and `1bad`, `has-dash`, `two words` do not — **no build** |
| `NEW_NAME_UNCHANGED` | a no-op is not a plan — **no build** |
| `NO_SYMBOL` | the caret resolves to nothing this search can name: the `p` or the `q` of `import { p as q }`, a member declared and never used |
| `DECLARED_IN_A_LIBRARY` | some declaration is in a `lib.*.d.ts`, which has no path on disk. Renaming the uses alone does not compile. tsc refuses the same thing |
| `ALIASED_SYMBOL` | the group spells the symbol two ways because an `import { a as b }` was crossed. One new name cannot be applied to both, and picking a side would be a guess |
| `UNRESOLVED_IMPORT` | a declaration IS the import binding, i.e. the module did not resolve |
| `OCCURRENCES_INCOMPLETE` | some occurrence spelling the old name could be one of this symbol's and could not be resolved. **The member-rename refusal** — that is a member on an `any` receiver (by `o.p` or by `o["p"]`), a shorthand in a literal nothing contextually types, or an object literal's own METHOD; a *second declaration of the same member name*, an implementor, an `o["p"]`, a contextually supplied key, and a computed or quoted key are no longer among them |
| `WOULD_NOT_COMPILE` | the verification build produced diagnostics the original did not |
| `WOULD_CHANGE_MEANING` | the verification build resolved something somewhere else |

**`OCCURRENCES_INCOMPLETE` is the one to understand**, because it is the boundary of
what this API can see. The occurrence set is resolution-based; a *spelling* scan is then
used as a SAFETY NET — never as the answer — to ask whether that set can be shown
complete. An identifier spelling the old name is fine when it is in the group or when it
RESOLVED to something else; what is left is unresolved, and unresolved is not unrelated.
`conflicts` names every one:

| `RenameConflictKind` | what it is |
|---|---|
| `UNRESOLVED_OCCURRENCE` | an identifier spelling the old name in a position that could name this symbol, which the search could not resolve — a member on an `any` receiver, an object literal's own method |
| `ELEMENT_ACCESS` | an `o["p"]` — or a `` o[`p`] `` — the search could not resolve, i.e. a member of an `any`. A resolvable one is an ordinary occurrence and is renamed *inside its delimiters* ; the kind survives because an unplaceable bracket is a different report to a user than an unplaceable identifier |
| `CONTEXTUAL_SHORTHAND` | a `{ p }` or a `const { p } = o` met while renaming a MEMBER whose property could NOT be placed — a literal nothing contextually types, an un-annotated destructured parameter. A placeable one is now an ordinary occurrence and is EXPANDED ; the kind survives for `ELEMENT_ACCESS`'s reason |
| `NEW_DIAGNOSTIC` | a diagnostic the renamed program has and the original did not |
| `RESOLUTION_CHANGED` | a span that meant one thing before the rename and another after |

The position split inside that net is load-bearing: a *member* rename is judged by the
member positions and a *plain binding* rename by the free ones, so an
`interface I { p: string }` somewhere in the program does not refuse renaming an
unrelated local `p`. (It was once load-bearing for a second reason as well
— a member declaration name resolved to nothing at all — and that reason is gone.)

### What this means in practice

**Renaming a local, a parameter, a function, a class, an interface, a type alias, a type
parameter, an enum, a namespace or an imported name works** — those are resolved through
the scope chain, and the sweep covers every identifier of every program file, so the set
is complete by construction.

**A member rename works when it can be shown complete** and is refused loudly when it
cannot — and "complete" covers considerably more again. An interface
member renames across files together with **every implementor's own declaration**, every
`o["p"]` and every `` o[`p`] `` that names it (inside the delimiters, leaving them
alone), every
`const { p: … } = o` that destructures it, every **object-literal key** a contextual
type supplies, and both **shorthands**, each expanded the member's way.

**A member also renames FROM ITS OWN DECLARATION NAME**, and the
largest refusal is gone with it: an `interface Other { p }` standing beside an
`interface Shape { p }` used to block renaming either, because a declaration name
resolved to nothing and the safety net could not rule it out. It resolves through its
owner now — and to its whole SYMBOL, which is the part that matters, because the net's
real quarry is a *merged* declaration the group missed and only the symbol's whole
declaration list puts that back in the group. A **merged** member, an **overload set**
and an **accessor pair** therefore rename all of their declaration names together.

Three things still refuse it, and each is a different sort of thing:

- **a shorthand whose property cannot be placed** — a literal nothing contextually
  types, an un-annotated destructured parameter;
- **a member on an `any` receiver**, reached by `o.p` or by `o["p"]`;
- **an object literal's own METHOD** (`{ om() { … } }`), which is deliberately outside
  both this leg and the key leg — see § 9 — **once a contextual type supplies
  it**: the key then spells the member's name and resolves to nothing, which is what the
  net cannot rule out. Round 930 measured the other half: in a literal nothing
  contextually types, the group is complete and the rename goes through.

A **computed key** (`{ ["p"]: v }`) is rewritten, delimiters preserved,
in every one of its three shapes — and it was the last SILENT one. Round 930 measured
what its absence cost: where stranding it broke the program the apply-and-recheck stage
refused with `WOULD_NOT_COMPILE`, where the literal had no contextual type the
completeness gate refused with `OCCURRENCES_INCOMPLETE`, and where the contextual member
was **optional** it went through in silence, because dropping it costs no diagnostic for
the recheck to find. A quoted key (`{ "p": v }`) and a computed member DECLARATION are in
the same population for the same reason: what this API cannot place, it now at least
SEES, and an occurrence it can see and cannot place is a stated conflict.

A **template element access** (`` o[`p`] ``) was the silent one with no such saving
grace — outside the population, refused by nothing, and left spelling the old name in a
program that still compiled clean. That is closed: it is an ordinary
occurrence now, found by `referencesAt`, highlighted, hovered and rewritten, with the
edit covering the text and **not the backticks** for the same reason it excludes the
quotes. A template carrying a **substitution** (`` o[`p${x}`] ``) spells no fixed member
name and stays out — it is neither an occurrence nor an obstacle, and a caret in it
renames nothing, which is what tsc answers there too.

### Cost, measured

On this repo's own compiler profile — tsc's 78 source files, 9,977,097 characters,
381,670 identifiers, real libs, warm:

| query | builds | wall | moved? |
|---|---|---|---|
| `referencesAt`, for reference — | 1 | 0.5 – 4.9 s narrowed, 8.8 – 9.6 s in the fallback | § 10b |
| `renameAt` — an ordinary name, | 2 | **1.0 – 1.3 s** | **≈12 – 14.5x** |
| `renameAt` — `SyntaxKind`, clean, i.e. a name in 49 of 78 files | 2 | **20.0 – 21.3 s** | see below |
| `renameAt` — `SyntaxKind`, dirty | 3 | **25.0 – 26.0 s** | see below |
| a refusal decided on syntax alone | **0** | microseconds | — |

Re-taken **2026-08-24 at commit d018af0a**, two independent processes;
**unpinned by any test**, per § 13.

### A rename is narrowed by the same closure, WIDENED by the new name

The paragraph that stood here said "nothing here moved, and nothing here can: a
rename's sweep and its verification are whole-program by CLAIM". Same category error
as § 10b's, and it is retracted: the claim is program-wide, the evidence is not.
`renameAt` takes the same spelling closure and hands the resulting file set to the compiler as
a check partition, so the `SyntaxKind` rows above are the WORST case (a name written in
49 of 78 files) rather than the typical one.

**Three things make it more than a copy of the reference change, and a host should know
the second one exists.**

1. **Both of a rename's builds share ONE partition.** `verifyRename` compares the
   build's diagnostics as a `(file, code)` MULTISET before and after applying the plan;
   a partition filters diagnostics to its own files, so a narrowed "before" against a
   whole-program "after" would report every unswept file's rows as removed. What makes
   narrowing that comparison sound: a rename edits only files the plan names, all of
   which are in the partition, and an unedited file's meaning can change only through a
   name it imports — which it must then SPELL.
2. **The population is the closure UNION every occurrence of the NEW name.**
   `verifyRename`'s third check — the only one that can see a rename which compiles and
   means something else — scans for occurrences that already spell the new name and
   asserts each still resolves where it did. Without the widening that scan finds
   nothing and passes vacuously, which would make this a way of switching the safety
   net off rather than of paying less for it.
3. **The new name joins the SELECTION, not the CLOSURE.** It is not a spelling of the
   symbol being renamed, so letting it contribute alias links or escapes would make the
   partition a function of a name that names something else entirely.

**What still renames through the whole-program sweep** is exactly § 10b's refusal list:
a symbol reached through a default export, an `export =`, an `import x = require(…)` or
a namespace binding.

**Measured**, both arms interleaved in one process at the same caret, renaming to a name
the program does not contain (`RenameNarrowingCostMainKt`; `partition` is the counter
that transfers, the milliseconds are wall time on one box and are pinned by nothing):

| caret | edits | partition | narrowed | whole-program |
|---|---:|---|---|---|
| `emitFiles` | 4 | **2 of 78** | **1,304 ms** | 15,933 ms |
| `transformNodes` | 6 | **3 of 78** | **1,025 ms** | 14,871 ms |
| `checkSourceElement` | 71 | **1 of 78** — but that file is `checker.ts` | **4,725 ms** | 15,198 ms |

So an ordinary rename is **~1.0 – 1.3 s against ~15 s (12 – 14.5x)**, and one confined to
`checker.ts` is 3.2x. Both of a rename's builds are narrowed, which is why the ratios are
close to the reference ones rather than half of them.

`scripts/rename-narrowing-differential.sh` is the gate — it compares whole
`RenamePlan`s, and prints `applicable=` beside `narrowed=` because two REFUSALS compare
equal and a run with no applicable plan in it has compared two empty edit lists. It
reads **EQUIVALENT** over tsc's own sources: 8 carets, 7 narrowed, 6 applicable, 1,691
edits compared plan for plan, 0 diverged, 56.5 s narrowed against 114.2 s. **Draw few
carets** — a rename holds a whole-program sweep per arm, and a 20-caret run at `-Xmx6g`
was OOM-killed.

The second build is the verification, and it costs less than the first on a small rename
(it carries only the renamed occurrences as capture spans, against the sweep's 381,670)
and roughly as much on a large one. Budget memory as `referencesAt`'s — the sweep is
the same shape, and § 10b's corrected reading applies here too: **`-Xmx2g` is the
floor**, ~1.1 GB peaks in old gen, **~177 MB actually retained
step 2** (was 264 before it), of which the process-global `CrawlParseCache` is
103.0 MB and **0.0 MB is any memo this API keeps**. Attribution, the before/after
ladder and the remaining ~28 MB: `docs/perf/language-service-retention.md`.

The absolute numbers are a property of the run that took them; only the § 13 table is
kept current, and the runner is **`scripts/inc31-ls-cost.sh`**.

**So: this is a query a user asks for explicitly.** Do not wire it to a keystroke, and
prefer to refuse early — a bad new name costs nothing at all.

## 11. Rules that apply to everything

**Paths.** Every path crossing the API is normalized and made absolute through
the backing `Vfs` before it is used as a key. Pass absolute paths and you never
have to think about it; relative ones resolve against the `Vfs`, which for the
default `SystemVfs` is your process's working directory.

**Threading.** A `Project` is not thread-safe: one instance belongs to one
thread at a time. Builds run synchronously on the calling thread.

**Lifecycle.** `close()` releases the overlay and the cached build and is
idempotent, so you may close on every teardown path. Any query or edit
afterwards throws `IllegalStateException` — silently reopening would hand back
the on-disk truth as though your edits were still applied. The process-global
parse cache is deliberately *not* cleared: it is shared by every project in the
process and keyed by content, so dropping it would slow unrelated work to free
memory the next build would immediately re-earn.

**Testing your host.** Pass your own `Vfs` to `Project.open` to run entirely in
memory — the interface is six methods (`exists`, `isDirectory`, `readText`,
`writeText`, `list`, `resolveAbsolute`). The module's own tests do exactly this;
see `TestVfs.kt`.

> One trap if you write in-memory fixtures that assert on **module resolution**:
> unresolved-import diagnostics are suppressed unless the program has at least
> two files *and* the tsconfig sets an ES `module` kind. A config carrying only
> `target`/`strict` leaves `module` unset, and every "the import is unresolved"
> assertion then passes vacuously — as an empty diagnostic list. Give such a
> test a negative control.

## 12. A minimal hover host

The shape that matters: **one compile per idle, not one per caret.** The host
sweeps a file when it settles and answers every hover, and every go-to-definition,
out of that one build's answers.

The API does this for you — `quickInfoAt` asks the buffer's whole
span set, so a host that just calls it per caret gets the same compile count. This
class is still worth having for the reason it was written: it keeps the ANSWERS in
the host's own map, so a hover is a lookup rather than a call across the API, and it
makes the invalidation explicit where a host can see it.

```kotlin
class HoverHost(projectPath: String) {
    private val project = Project.open(projectPath)

    /** The last sweep of each file, dropped the moment that file changes. */
    private val swept = HashMap<String, List<SemanticInfo>>()

    fun didChange(path: String, text: String) {
        project.updateFile(path, text)          // free; the next query rebuilds
        swept.remove(path)                      // the answers describe old text
    }

    /** Call on idle / on save — ONE compile, and it answers the whole file. */
    fun didSettle(path: String) {
        swept[path] = project.fileSemantics(path)
    }

    /** LSP is 0-based, this API is 1-based — convert once, here. */
    private fun offsetOf(path: String, line0: Int, char0: Int): Int? =
        // A client one keystroke ahead of us can send a line we do not have.
        try {
            project.offsetAt(path, line0 + 1, char0 + 1)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun at(path: String, offset: Int): SemanticInfo? {
        val entries = swept[path] ?: return null
        // The touch case: `abc|` is outside `abc`, so try one to the left too.
        return entries.firstOrNull { offset >= it.start && offset < it.end }
            ?: entries.firstOrNull { offset - 1 >= it.start && offset - 1 < it.end }
    }

    fun hover(path: String, line0: Int, char0: Int): String? {
        val offset = offsetOf(path, line0, char0) ?: return null
        // Free on a hit. On a miss — the user hovered before the file settled —
        // fall back to the single-caret call, which costs a compile.
        return at(path, offset)?.quickInfo?.displayString
            ?: project.quickInfoAt(path, offset)?.displayString
            ?: project.quickInfoAt(path, offset - 1)?.displayString
    }

    fun definition(path: String, line0: Int, char0: Int): List<DefinitionLocation> {
        val offset = offsetOf(path, line0, char0) ?: return emptyList()
        at(path, offset)?.let { return it.definitions }
        return project.definitionsAt(path, offset)
    }

    fun diagnostics(path: String) = project.diagnostics(path)   // call on idle, not per keystroke

    fun dispose() = project.close()
}
```

Note what the cache is allowed to be: a plain map keyed by path and dropped on
edit. There is nothing to invalidate more cleverly, because a `SemanticInfo` is a
value — it holds no AST, no `Symbol` and no `Type`, so a stale entry describes
stale text and nothing worse.

## 13. State of the API — the two-minute version

Rounds 909–932 built this in twenty-three increments, and the detail is spread across as
many session notes. This section is the summary a next agent or a host author should
read instead.

> **It is PINNED, and dated.** Round 930 audited every claim below **by execution** — a
> fixture through the API, tsc 7.0.2's own language server as the oracle where the claim
> is about parity, the cost table re-taken on the profile — and every claim a test can
> defend is now defended by `LanguageServiceStateTest` (`-project`, `src/commonTest`) or
> by the class named beside it. Lines a test cannot defend are marked **(not pinnable)**.
> Audited **2026-08-18**; four claims were false and are corrected here. The reason for
> the ceremony is the thing the audit found first: this section was three rounds old and
> already listed a defect that had been fixed *before it was written*. A page of prose
> about behaviour drifts within three rounds; the pins are what stop it.

### What it answers

| question | member | maturity |
|---|---|---|
| diagnostics, whole program or one file | § 4 | complete |
| in-memory edits, including files that exist nowhere but the overlay | § 5 | complete |
| offset ⟷ (line, character) | § 6 | complete — `\n`, `\r\n` and a lone `\r` all agree with the compiler's own diagnostics |
| what node is at this position | § 7 | complete, gated over **101,287,620 characters** of real TypeScript (1,327 files, zero violations) |
| hover | § 8 | complete for values, members, member declarations and object-literal KEYS — an enum member's included, a key's own included |
| go to definition | § 9 | complete for free names, members, imports, `this`/`super`, object-literal keys and member declarations |
| the two above for many carets, or a whole file, in ONE compile | § 10 | complete — and the single-caret members cost the same, so this is now the convenient shape rather than the cheap one |
| completions, members and free names, with accessibility and keywords | § 10a | complete, `o["` and `` o[` `` included |
| find references, document highlights, read-vs-write | § 10b | complete — the population is every identifier plus every literal in a member-NAME position |
| signature help, every overload | § 10c | complete except tagged templates and `super(...)` |
| rename, verified by recompiling | § 10d | complete for bindings; for members, complete except the gaps below |
| checking a whole working set once, so every semantic query about it is free | § 3a | complete — `prepare(files)`, swept against one-build-per-query over 1.07 M rows in editor order |

Everything crossing the surface is a **value** — no AST, no `Symbol`, no `Type` — which
is what lets the compiler underneath change without breaking a host.

### What it refuses, and the one rule behind all of it

**Prove to offer.** Every refusal in this document is a place where the compiler could
have guessed and does not, because a plausible wrong answer is worse than none: a
go-to-definition that jumps to an unrelated same-spelled binding *looks like it worked*.
That is why a member is resolved through its receiver, its owner or its contextual type
and never through the scope chain; why the completion list hides only what it can *prove*
inaccessible; why `use` has an `UNCLASSIFIED` state instead of defaulting to `READ`; and
why a rename that cannot show its occurrence set complete refuses with the evidence
rather than shipping a partial plan.

The refusals are listed per member (§§ 9, 10a–10d). The ones that are gaps rather than
principles are in the list below.

**AND SINCE ROUND 932 THE RULE HOLDS WITHOUT EXCEPTION — which is this page's headline
claim, so it is worth saying exactly what it means.** Every position this API answers
either answers correctly, or refuses and says why. Nothing is silently missed and
nothing answers a plausible wrong thing. Three rounds took the last three exceptions:
one closed an enum member's declaration name answering `any` (gap 7 — a wrong
answer, not a refusal) and a template element access being missed without a word (gap
6), and another closed the computed object-literal key (gap 2), whose OPTIONAL-member
shape was the last place a rename could complete while leaving an occurrence of the old
name behind — with no diagnostic, no failing gate and nothing in any output to see it
by. Round 932 also found, by measuring the neighbour, that hover on ANY object-literal
key reported the enclosing scope rather than the member, and closed that too. The
mechanism behind the last one is worth a host author's attention: **a literal this API
cannot RESOLVE is nonetheless SWEPT**, so it becomes a stated `OCCURRENCES_INCOMPLETE`
conflict instead of a span nobody looked at.

### What it costs

**Provenance, because a wall figure without one is what made this table mislead for
a year.** Every number below was taken on **2026-08-24 at commit `d018af0a`**, on tsc's
own 78 compiler sources (`build/bench/tsc-project-*`: 9,977,097 characters, 381,670
identifiers, real libs), warm, **six warm-up cycles**, four rotations, replicated in
**two independent JVM processes** whose medians are quoted as a band. The runner is
`scripts/inc31-ls-cost.sh`. The round-930 column is kept beside it as HISTORY: those
figures predate the narrowing of the capture path and the
collapse of the incremental floor from 1,092 ms to 58 ms, and they are **10 – 24x stale**
in the narrowed rows.

**The old headline is now false and is retracted.** This section used to open "almost
every semantic query is a full rebuild". It is not: every caret-scoped query is a
NARROWED build, and at the median file that is **108 – 113 ms against a 4.9 s rebuild,
a factor of 45**. **AND NOTHING IS A FULL REBUILD BY DEFAULT ANY MORE**: project-wide
`diagnostics()` was the last one, and an edit that moves no exported signature now answers
from one narrowed build (§ 4b) — 67% of real edits to tsc's own compiler, graded EQUIVALENT
over 40 of them. **`referencesAt`
moved too** and is narrowed by the SPELLINGS its answer can carry (§ 10b),
falling back to the whole-program sweep only where that closure cannot be bounded, and
**`renameAt` moved with it** (§ 10d), its population widened by the NEW
name so that its verification's "nothing moved" check does not go vacuous. The
"moved?" column says which is which, and that column is the half a host must design
around. **The `referencesAt` rows below were taken this round (2026-08-29) and not on
the 2026-08-24 provenance the rest of the table carries.**

| query | builds | earlier | **2026-08-24** | moved? |
|---|---|---|---|---|
| a plain rebuild (`diagnostics()`), for reference | 1 | 5.0 – 5.5 s | **4,864 – 5,096 ms** | **only when a signature MOVED — see § 4b** |
| `diagnosticsOf(f)` — **median over all 78 files** | 1 narrowed | 1.1 – 1.2 s | **108 – 113 ms** (p90 202 – 219) | **≈10x** |
| `diagnosticsOf(checker.ts)` — the 3.15 MB extreme | 1 narrowed | 2.7 s | **1,744 – 1,763 ms** | 1.5x |
| `diagnosticsOf` repeated, or a SUBSET of a set already asked | **0** | 0 | **0 ms** | = |
| `diagnosticsOf(g)` — **another file, same state** | **0** | 1 narrowed | **25 ms median** (re-entry, no build) | **≈4x on 2026-08-24's own row** |
| `completionsAt` | 1, **every call** | ≈ 4.7 – 5.1 s | **194 – 202 ms** | **≈24x** |
| `signatureHelpAt` | 1, **every call** | ≈ 4.7 – 5.1 s | **190 – 214 ms** | **≈23x** |
| `quickInfoAt` — first caret, **median file** | 1 per BUFFER | — | **129 – 137 ms** | — |
| `quickInfoAt` — first caret, `binder.ts` (7,787 spans) | 1 per BUFFER | 610 ms | **290 – 306 ms** | **2.0x** |
| `quickInfoAt` — first caret, `checker.ts` (125,289 spans) | 1 per BUFFER | 3,796 ms | **3,341 – 3,581 ms** | 1.14x |
| …a SECOND caret: median file / `binder.ts` / `checker.ts` | **0** | — / 2 ms / 73 ms | **2 ms / 4 ms / 75 – 83 ms** | = (see the residue note) |
| `definitionsAt` after `quickInfoAt`, same caret | **0** | 0 | **4 ms** | = |
| `fileSemantics(binder.ts)` cold / after a hover | 1 / **0** | 5.0 – 6.2 s | **329 ms / 18 – 22 ms** | **≈15x** |
| `documentHighlightsAt(binder.ts)` cold / later caret | 1 / **0** | 5.0 – 5.5 s / 19 ms | **336 ms / 10 – 18 ms** | **≈15x** |
| `prepare(6 mid-sized buffers)` | 1 | 674 – 698 ms | **466 – 468 ms** | 1.45x |
| …then 6 hovers, 12 defs+highlights | **0** | 7 – 12 / 23 – 27 ms | **13 – 14 / 33 – 43 ms** | = |
| `referencesAt`, a name written in 1–3 files | 1 clean, 2 dirty | 8.3 – 10.2 s | **510 – 553 ms first ask, 119 – 141 repeat** | **≈17 – 18x** |
| `referencesAt` — one file, but that file is `checker.ts` | 1 clean, 2 dirty | 8.3 – 10.2 s | **1,940 ms** | ≈4.8x |
| `referencesAt` — `SyntaxKind`, imported into 49 of 78 files | 1 clean, 2 dirty | 8.3 – 10.2 s | **4,904 ms** | ≈1.85x |
| `referencesAt` — a spelling the closure cannot bound | 1 clean, 2 dirty | 8.3 – 10.2 / 13.2 – 14.8 s | **8.8 – 9.6 / 13.2 – 13.9 s** | NO — the fallback, unchanged |
| `renameAt` — an ordinary name, | 2, 3 dirty | — | **1.0 – 1.3 s** | **≈12 – 14.5x** |
| `renameAt` (`SyntaxKind`, 9,827 edits) | 2, 3 dirty | 21.0 / 19.6 – 26.7 s | **20.0 – 21.3 / 25.0 – 26.0 s** | the worst case, and the fallback |
| a refusal decided on syntax alone | **0** | microseconds | microseconds | = |

**A REAL keystroke costs the narrowed path nothing extra**, which is the row an
error-reporting host cares about most and which no earlier table had: `diagnosticsOf`
of one buffer after re-writing its own bytes is 212 ms, after an appended comment
247 ms, after an inserted statement 218 ms, and after a statement that *introduces* a
`TS2322` 215 ms — against 5,067 – 5,184 ms for the full build of the same states.
Every figure in the paragraph and the table above is **WALL TIME AND THEREFORE PINNED
BY NO TEST** (see the note that closes this section).

### What the floor is made of — and the ~20% (INC.53) took off it

Every number in the table above rests on the FLOOR: what a build costs when the
checker checks nothing. It is what an editor pays per keystroke, because an edit
drops every cached answer. Decomposed on the compiler profile (2026-08-29, eight
draws, `scripts/floor-decomposition.sh`): config + root glob 3-7 ms, crawl 10-12
(read + decode of every file, 44-56 ms of CPU across the crawl's workers),
`extractRelativeImports` 1-2, bind 6, **`Checker` construct + `getDiagnostics()`
32-44**, post-checker 2-3.

That last row is the phase, and it is **not** the pass table: `getDiagnostics()`
is **2-3 microseconds** on a floor build, so the whole of it is the CONSTRUCTOR —
of which ~20 ms was the class's ~494 **property initializers**, a constant that
reads the same on a 63 ms floor build and a 5.2 s full one. On a full compile that
is 0.4%, which is why no round had seen it; here it was about a third of the query.

**Four initializers were essentially all of it** — the other ~490 are 0.2-1.2 ms
between them — and three of them were whole-program indices with exactly one read
site each. They are now built on first ask, so a floor build builds **none** of
them, and even a full build needs only **69 of 78** files' nested-alias index:

| field | was | now, floor build |
|---|---:|---|
| `localTypeAliasIndex` (a DFS through every function body) | ~5.4 ms | per FILE, on first ask |
| `enclosingImportIndex` | ~3.4 ms | on first ask |
| `topLevelConstStringValues` | ~3.1 ms | on first ask |
| `parseBuiltinLib` | ~11.0 ms | unchanged — see below |

Field region on a floor build, four draws each side: **18.6 / 25.4 / 29.6 / 30.2 ms
-> 8.1 / 12.6 / 8.4 / 11.2**. It is claimed as a work reduction and not as a
millisecond, because a per-pass row on a ~68 ms floor reads 13.16 ms in one draw
and 8.42 in the next of the same binary; `EagerIndexCensus` counts the population
instead and `EagerIndexDeferralTest` pins it.

`parseBuiltinLib` is **refused with its price**: it splits three ways with no
dominant part (binds 3.2-5.3 ms, the lib decl-set walk 1.9-2.8, lib-set resolution
plus 45 `mergeSymbolTable` calls 3.1-5.3), and the two larger parts are per-checker
by requirement — the checker merges into and mutates lib symbols, so neither the
bind nor the merged table is shareable until round 884's `mergedSymbols`
clone-on-write lands.

### One open defect this table exposes

It is measured, it is in the table above, and it is not a gap in the architecture —
it is a bound that is now the wrong size.

*(The second defect listed here until 2026-08-29 — the two-entry `captures` LRU
thrashing on hover -> complete -> signature help -> hover — was CLOSED by (INC.32),
which replaced the entry-count bound with two weight-based lanes that cannot evict
each other. This page had not been updated; the text is kept out rather than left
standing, since a stale claim here is invisible to every gate in this repo.)*

1. **`completionsAt` and `signatureHelpAt` cannot reach a prepared check, at all —
   AND (INC.89) RE-DERIVED THIS INTO SOMETHING OTHER THAN A DEFECT WORTH FIXING.**
   Not a policy: `completionsAt`'s free-name and member sites and `signatureHelpAt`'s
   each call `captureIn(...)` **directly**, while `preparedAnswerFor` is reached only
   from `captureAround` (twice) and `referencesOf`. *(**CITED BY SYMBOL, NOT BY LINE,
   ON PURPOSE**: this paragraph's line numbers have now rotted THREE times — most
   recently by +3 within a day of being "re-verified 2026-08-31" — while the
   structural claim never moved once. `grep -a 'preparedAnswerFor' Project.kt` returns
   exactly four hits and is the check; note `grep` WITHOUT `-a` silently returns
   nothing on this file.)* So a `scopeSpans` / `memberSpans` / `signatureSpans`
   request is structurally unservable from `prepared` — and there is a **second,
   harder leg this page used to omit**: `prepare` builds a `TypeCaptureRequest` of
   TYPE spans only, so even if those three sites consulted `preparedAnswerFor` the
   prepared `Result` carries `capturedMembers`/`capturedScopes`/`capturedSignatures`
   **empty**. That leg hides a live trap, because `preparedAnswerFor` takes
   `List<TypeCaptureSpan>` and `memberSpans`/`scopeSpans` are the SAME TYPE: a member
   receiver that is a bare identifier **is** an occurrence node, so a naive
   `preparedAnswerFor(listOf(receiverSpan))` would HIT, return a result with no
   members, and the completion would render nothing — (INC.14)'s absent-answer
   hazard, and type-invisible.
   Measured (2026-09-01, `scripts/inc31-ls-cost.sh`, recorded → today):
   `completionsAt` **207 → 190 ms** immediately after `prepare(6 files)`,
   **202 → 214** immediately after a hover in the same buffer, **194 → 215** cold —
   still *the same build three ways*; an identical repeat is free (0 ms) because
   `captureIn` still probes the LRU. For contrast in the same run, `prepare(6)` is
   465 ms and `hover6` is **16 ms prepared against 824 ms unprepared**: `prepare`
   works spectacularly for every channel it covers, and these two get nothing.
   **THE REFRAME, AND IT IS THE POINT.** `member.caret` costs essentially what
   `base.noCapture` costs at both files (binder.ts **224 vs 254 ms**; checker.ts
   **2,035 vs 2,189**), and `completions.mid.cold` 215 ≈ `diagnosticsOf.mid.fresh`
   219. **The capture work for a caret completion is FREE — the 190-215 ms IS one
   narrowed build and nothing else.** So this is not a prepare-wiring defect with a
   200 ms prize; it is the incremental FLOOR, i.e. the (INC.52)-(INC.89) arc that is
   already the live work.
   **NEITHER DIRECTION IS PINNED** — `LanguageServiceStateTest` pins `completionsAt`
   and `signatureHelpAt` at 1 build each and `ProjectPreparedCheckTest` mentions
   neither, so a round that ever wires this must add the pin or (INC.16)'s law
   applies: an ablation restoring today's behaviour would read 0 RED.

   **BOTH ROUTES OUT ARE PRICED AND SHUT, RE-DERIVED AT HEAD ON 2026-09-01 RATHER
   THAN INHERITED ((INC.89)).**
   *Route 1 — widen the prepared request to carry member/scope/signature spans
   ((INC.33)).* Re-run of `scripts/inc33-widen-cost.sh` at HEAD, populations
   identical to the recorded run: widened hover on `binder.ts` **+286 → +340 ms**
   (break-even **1.40 → 1.52** completions per hover with no edit since), on
   `checker.ts` **+25.1 → +26.2 s** (break-even **12.1 → 12.9**). The floor arc DID
   land — `checker.ts` base fell 2,407 → 2,189 ms — and that makes the refusal
   **stronger**, because the base got cheaper while the per-anchor capture work did
   not, so every break-even ratio ROSE. Retention is unchanged to the digit: one
   widened `checker.ts` entry is **54.4 M records** (`scopeNames` 49,879,917,
   byte-identical across the two runs), ~92% of it the scope channel, which is
   O(anchors × globals).
   *Route 2 — a re-entrant capture against a retained checker ((INC.17)'s
   `ProgramRecheck`).* Still blocked by (INC.41): the valve is intact and still
   **diagnostics-only**, and (INC.41)'s residual is (INC.43), which is open and
   carries three separately-measured blockers of its own. Nothing has landed
   against it.
   *And the "prepare-amortised" successor the queue names — pay the widening once
   for a working set, then answer many carets — is REFUTED BY MECHANISM, not merely
   unpriced.* It survives the cost half and dies on invalidation: `sourceIndexOf`
   reads `overlay.readText(key)`, so if the host does NOT report the typed `.`
   through `updateFile`, `completionAnchorAt` is computed against the PRE-`.` text
   and answers about the wrong node — while `updateFile` (and `deleteFile`, and
   `close`; the `captures` KDoc audits that there is no fourth path) does
   `captures.clear(); prepared = null`. **So the dominant completion is invoked at a
   program state nothing can have prepared.** (INC.32) changed the EVICTION bound,
   never invalidation, so it does not touch this. What a prepare-amortised widening
   could still serve is the minority slice — an explicit Ctrl+Space on an
   already-typed `x.y`, or a popup reopened after a fresh 465 ms prepare — bought at
   243k retained member items for a `binder.ts`-sized buffer and 4.27 M for
   `checker.ts`. Not worth opening on that evidence.
   *One cheap un-refused question is left, and it is a LEAD, not a finding, because
   it is a residual obtained by subtraction.* The SIGNATURE channel is nearly free in
   both currencies — `sigs.file − base` is **+83 ms for 18,594 anchors on
   `checker.ts`** (against `spans.file − base` = +1,310) and inside the noise floor
   on `binder.ts`, with **9,520** retained sig items against the 265,688 types+defs
   that entry already holds (+3.6%). (INC.33) measured that arm but never turned it
   into a variant, offering only `spansMembers.file` as "the cheapest shippable". A
   `spans + signatureSpans` widening has therefore never been priced. IF additive it
   would be +83 ms on a 3,499 ms hover to save a 2,002 ms signature-help build —
   but additivity is an ASSUMPTION, and this repo forbids reading a residual as a
   measurement, so the honest form is ONE new arm in `Inc33WidenMain`
   (`spansSigs.file`), not a round. Note it is capped by the same argument anyway:
   signature help is re-triggered by `(` and by every `,`, which are edits.

**A capture build is MEMOIZED on its REQUEST** (`Project.captures`, `Project.kt:455`
— (INC.32) two LANES split by the request's WEIGHT, so that a cheap entry can never
evict one that cost a rebuild: a request naming more than `CAPTURE_MEMO_CARET_SPANS`
= 4 spans is buffer-sized and bounded at `CAPTURE_MEMO_BUFFERS` = 2, anything at or
below it is caret-scoped and bounded at `CAPTURE_MEMO_CARET_ENTRIES` = 4, and
`rememberCapture` (`:3427`) takes its victim only from whichever lane is over
(`:3436-3441`). Both lanes are still dropped by every edit alongside the diagnostics
cache), which changes the `builds` column in two places a host will actually hit:

* **hover then go-to-definition at ONE caret is ONE build.** Both name the caret's node
  as a single span and read different channels of the one answer, so the requests are
  equal.
* **document highlights at every later caret in an unchanged buffer is ZERO builds.**
  Its request is derived from the FILE's occurrence nodes and not from the caret — the
  caret only picks the seed afterwards.

Measured in one process on the compiler profile (warm, three rotations): re-asking one
hover **1,933 ms → 0**; `definitionsAt` after `quickInfoAt` at one caret in `binder.ts`
**506 → 0**; `documentHighlightsAt` at a second caret in `binder.ts` **592 → 19** — 19
and not 0 because the BUILD is gone while the per-caret grouping over the file's
occurrences remains. `scripts/warm-program-cost.sh` re-takes it. (Those are round-930
figures; the 2026-08-24 equivalents are in the table above, and the residue is
decomposed below.)

**What that residue IS, decomposed 2026-08-24 — and why it was REFUSED as a lever.**
On a memo HIT, `captureAround` still re-derives the file's whole occurrence set before
it can look the answer up: `SourceIndex.occurrenceNodes()` (two full iterative tree
walks and two `sortWith`s, memoized nowhere), then a `TypeCaptureSpan` per occurrence,
then a `HashSet<Long>` of every span. Measured directly, medians of 25 calls:

| file | occurrences | `occurrenceNodes()` | span map | covered set | **sum** | measured 2nd-caret hover |
|---|---:|---:|---:|---:|---:|---:|
| `semver.ts` (18 KB) | 714 | 0.84 ms | 0.13 | 0.25 | **1.21 ms** | ~1 ms |
| `binder.ts` (194 KB) | 7,787 | 1.09 ms | 0.27 | 0.91 | **2.27 ms** | **4 ms** |
| `checker.ts` (3.15 MB) | 125,289 | 47.1 ms | 5.8 | 29.8 | **82.7 ms** | **83 ms** |

The arithmetic closes to 0.4% on `checker.ts`: essentially ALL of that residue is this
prologue, and every part of it is a pure function of an immutable `SourceIndex` that is
already cached per file and dropped on edit — i.e. memoizing it is easy and safe.
**It was measured and refused anyway**, because at the median file the whole prize is
**1 – 2 ms**, which is below this repo's floor for spending a round. It is a TAIL fix
worth **~78 ms per caret movement in a >1 MB buffer** and nothing at the median, and
that is the number any future proposal must be argued on. It is not a lever for
`referencesAt` either: the same prologue over all 78 files is ~140 ms of a 9.3 s sweep
(1.5%). `Inc31ResidueMain` is the instrument that would grade it.

**…AND THE QUESTION ASKED IS THE FILE'S, NOT THE CARET'S**, which is what makes
that memo hit for a caret nobody has visited yet. `quickInfoAt`, `definitionsAt`,
`semanticsAt` and `fileSemantics` name the buffer's whole occurrence set —
`SourceIndex.occurrenceNodes()`, deliberately the population `documentHighlightsAt`
already sweeps — so **all four are ONE build per buffer, between them**, until that
buffer changes. Measured on the compiler profile, three rotations, the same binary with
the widening off and on (blocked arms, not interleaved — one arm's whole point is that
some code does not run):

| sequence | before | earlier | **2026-08-24** |
|---|---:|---:|---:|
| first hover in `checker.ts` (3.15 MB, 125,289 spans) | 2,307 ms | 3,796 ms | **3,341 – 3,581 ms** |
| a SECOND caret in `checker.ts` | 2,142 ms | 73 ms | **75 – 83 ms** |
| first hover in `binder.ts` (7,787 spans) | 481 ms | 610 ms | **290 – 306 ms** |
| a SECOND caret in `binder.ts` | 481 ms | 2 ms | **4 ms** |
| `fileSemantics(binder.ts)` after that hover | 575 ms | 17 ms | **18 – 22 ms** |
| `diagnosticsOf`, and the build floor itself | unchanged | unchanged | 108 – 113 ms median |

**The trade is stated rather than hidden: the FIRST query in a buffer gets dearer.** It
is +27% on `binder.ts` and +65% on `checker.ts`, which is tsc's largest source and 31.6%
of that whole program — so break-even is at **the second caret**, and everything after it
is free. The reason it is not worse is that a narrowed build is mostly FLOOR (§ 13's
~345 ms), and the extra spans are cheap beside it: swept over all 78 files, a whole-file
capture is **+9 to +17 ms at the median file** (372 → 381 and 373 → 390, the two draws).

**…AND THE UNIT IS THE WORKING SET, IF A HOST ASKS FOR IT.**
`prepare(files)` performs ONE narrowed build over a declared set of buffers and captures
every one of them in that walk, so those four members answer about ANY of them without
building. Measured on the compiler profile, three rotations, replicated in a second run,
over six mid-sized sources (55–83 KB, 415 KB together — deliberately not `checker.ts`,
whose 1.65 s of own checking would bury the floor the arm exists to show):

| sequence, six buffers | one build per buffer | prepared | **2026-08-24, both arms** |
|---|---:|---:|---|
| a hover in each | 2,581 / 2,484 ms | **698 / 674 to prepare, then 12 / 7** | **920 / 927 unprepared; 466 – 468 to prepare, then 13 – 14** |
| go-to-definition + highlights in each (12 more) | 2,649 / 2,513 ms | **27 / 23** | **33 – 43** |
| `diagnosticsOf` per buffer (6) | 2,338 / 2,376 ms | **526 / 539 for the set, then 0** | **748 – 771 per file; 321 – 342 for the set, then 0** |

The ratio shrank (7.1x → ~2.0x for the hover arm) and that is the arc working as
intended rather than `prepare` regressing: the thing `prepare` amortises is the FLOOR,
and later rounds took the floor from 1,092 ms to 58 ms, so there is far less
left to amortise. `prepare` is still the right call for a declared working set, and it
is still the only way to make a query about a buffer the user has not touched free.

The memory it holds has a control rather than an absolute: a reading taken right after
the edit that dropped every cached answer and one taken after the prepared queries are
**163 → 167 MB**, identical to the MB in all six rotations — ~4 MB for that working set.

**That the two answers AGREE is measured, not assumed.** A capture types nodes the
checker had no reason to type, and typing populates order-dependent caches, so a
file-wide request could plausibly render a different type for the same span — which is
the mechanism that was refused a 66 ms saving over. `scripts/caret-vs-file-capture.sh`
is the differential and needs no baseline, because a span asked alone and the same span
asked as part of its file are the same question: **904 sampled spans in 76 files, zero
divergence in either channel — and a second sweep over 979 DIFFERENT positions reads
EQUIVALENT again**, 1,883 sampled positions between the two draws.

**What it does NOT do**: a query about a DIFFERENT file is a different request and still
builds, and so is a caret on a node that is no occurrence at all — a call expression, a
numeric literal, a `this`. Those fall back to naming the one span, deliberately: a
file-wide request would not carry them, and an absent capture renders nothing with no
error anywhere. `completionsAt` and `signatureHelpAt` ask different questions
(a receiver's members, a scope chain, a callee's signatures) and share nothing. That is
(P1) proper, and § 13 carries its price.

**The `builds` column is pinned** (`LanguageServiceStateTest`, counted at the backing
`Vfs`, and `ProjectCaptureMemoTest` / `ProjectPreparedCheckTest` for the memo and the
prepared check) and **EVERY WALL FIGURE ON THIS PAGE IS PINNED BY NOTHING** — it is a
property of one box on one day, a timed assertion over a compile is a coin flip
and only figures taken in one run are comparable to each other.
That is why each is dated and stamped with the commit it was taken at, and why the
earlier column is kept beside the current one instead of being overwritten: false
claims have been written into this page before and survived several readings,
and a stale wall number is invisible to every gate in this repo. Re-take the table
with `scripts/inc31-ls-cost.sh` rather than quoting an old number beside a fresh one. Memory is the other budget: a
whole-program sweep holds a resolution per identifier and the default 512 MB of a plain
JVM is nowhere near enough (§ 10b).

A normal application project is far smaller, and the ratios are what transfer — but the
ratio this paragraph used to quote is GONE, and its disappearance is the point. It read
"batching 34 carets is **34 compiles cheaper** than asking one at a time"; since
asking one at a time is **one compile too**, because the question put to the
compiler is the buffer's and not the caret's. What is left, and is what a host should
still do: **debounce, and wire `documentHighlightsAt` rather than `referencesAt` to
caret movement.** Batching is now a convenience rather than a cost decision.

### Hosting this inside an IntelliJ platform IDE

The platform imposes two requirements that are not latency questions, and one of
them is now met.

**Cancellation — met since (INC.55).** `DaemonCodeAnalyzer` restarts analysis on
every write action, and the platform contract is that background work polls
`ProgressManager.checkCanceled()`. A build here runs on the compiler's own
deep-stack thread and this API JOINS it, so the calling thread is blocked for the
whole build and cannot abandon it from outside. Set `Project.cancellation` to a
`CancellationSignal` — on the platform, `{ indicator.isCanceled }` — and the build
polls it at every `pass("…")` boundary and every 1024 spine nodes, which is what
keeps a large buffer's walk interruptible.

A cancelled build throws `CompilationCancelledError` out of the call that asked for
it and produces no result. **Nothing in `Project` is left half-updated**, by
construction rather than by care: every cache assignment happens after the build
returns, so a throw skips all of them. The work of the abandoned build is lost —
there is no partial answer to keep — so cancel because the answer is unwanted, not
to poll.

It is an `Error` and not an `Exception` on purpose: the checker, the crawl and the
`Vfs` carry defensive `catch (Exception)` guards, and a cancellation they could
swallow would let the build carry on with a missing file — silently wrong, which is
worse than not cancelling.

**Threading — a confinement rule, not a lock.** One `Project` belongs to one thread
at a time (§ 11). That is compatible with running analysis under read actions on
pooled background threads, but it must be *confined* — one single-thread executor
per project, the way the compile server already serialises its requests — because
`Symbol` and `Type` ids are thread-local sequences that `runWithDeepStack` hands
off (INV.6(6c0)). Calling one `Project` from two threads is not merely racy; it
corrupts an id space, and no diagnostic says so.

**What does NOT apply.** A plugin runs in-process on the IDE's own long-lived JVM,
so none of the artifact levers this repo has measured — the GraalVM PGO image, the
JDK 25 AOT cache, CRaC — is available to it. What remains relevant is the JIT ramp
on the first queries after IDE start, which amortises over a long session, and
(INC.48)'s snapshot restore, which is exactly the IDE-restart case.

**Budget the memory per project, not per program.** A monorepo has many
`tsconfig.json`s and therefore many `Project` instances; § 13's retention figure is
per project.

**Route every VFS event, then turn on `trustFilesystem`.** A host that already drops
state on external change is *already* keeping the promise § 5a asks for — but usually
by the bluntest available means, throwing whole projects away and paying a cold
rebuild. The three event kinds map one-to-one onto the three calls: a document change
or save is `updateFile`, a deletion is `deleteFile`, and anything else that moved a
file's bytes is `reloadFile`. **The gap to watch is whatever your listener currently
SKIPS** — an excluded or ignored root, say. Skipping such an event is safe today only
because the next dirty build re-reads that file from disk; under the promise it is not,
so a skipped path needs `reloadFile` rather than nothing.

`reloadFile` also removes the reason a host would keep its own copy of every buffer it
has ever overlaid: an overlay entry can now be handed back to the filesystem one path
at a time, instead of being bounded by evicting the whole project.

### The known gaps, all of them

**Seven live of the ten ever numbered** — 2, 6 and 7 are closed and their numbers are
kept so the round notes keep pointing at the same thing. **None of the seven is a
silence**: each is a stated refusal, a deliberate divergence, or the architecture.

1. **No incrementality — HALF TRUE since 2026-08-22, and the half that is left is now
   measured.** Every query still *builds*; what changed is how much of the program a build
   CHECKS. `diagnosticsOf` (§ 4a) hands its file set to the compiler as a check partition
   and costs **108 – 113 ms at the median file against a 4,864 – 5,096 ms full rebuild**
   (2026-08-24, commit `d018af0a` — a factor of 45), with every one of those 78 files
   agreeing row for row with the full build. The gap that remains is not the checking:
   a median file's own checking is tens of ms, and the rest is the FLOOR — the crawl,
   the parse, the bind and the program-wide passes, which run whatever is narrowed.
   That floor was 1,092 ms when this gap was written and is now **58 ms**. So the
   architectural inversion (`docs/ARCHITECTURE-RETHINK.md`) is still what removes the
   floor, but it was never the prerequisite for narrowing the check. **The interactive
   queries are narrowed too, since 2026-08-22** — hover, go-to-definition,
   completion, signature help, the semantic sweep and document highlights each hand the
   compiler the queried buffer, and a capture build of `binder.ts` fell **4,581 -> 979 ms
   (4.7x)** warm and rotated in one process when that landed. End to end through
   this API, and with `referencesAt`, `renameAt` and a plain rebuild flat across the two
   arms as controls: `quickInfoAt` **5,004 -> 1,015 ms**, `fileSemantics` 5,178 -> 1,185,
   and `documentHighlightsAt` 5,050 -> 1,159. **Those are 2026-08-22-era figures and are
   now stale in the fast direction**: re-taken 2026-08-24, the same `binder.ts` capture
   is **290 – 306 ms** and the median file's is **129 – 137 ms** (§ 13).

   It was REFUSED when first measured, at 45 divergent spans of 381,666, and the refusal
   is what found the defect: a type reference inside a foreign file's anonymous object
   type literal collapsed to `any`, which is a pre-existing first-touch order-dependence
   in the checker rather than a property of narrowing — in 5 of the 45 it was the
   WHOLE-PROGRAM build showing `any`. That is closed; the sweep now
   reads **5 spans in 3 files with `narrowRendersMoreAny = 0`**, and in four of those five
   the narrowed arm is the better answer.

   **Project-wide `diagnostics()` WAS the one thing left, and it is CLOSED** —
   see § 4b. Not a dependency closure: a symbol-level use graph was measured on tsc's own
   sources and re-checks 100% of the program's characters at the median edit, the same as
   the file-level one, so the `export *` barrels were never the cause. What collapses it
   is asking whether an edit moved any EXPORTED SIGNATURE — a body-only edit moves none,
   so no dependent re-checks and the cost is one narrowed build (108 – 113 ms against
   4,864 – 5,096 ms). **Measured: 67% of 40 real commits to tsc's own compiler move no
   signature, and the path is graded EQUIVALENT on all 40 with 27 actually served.**

   **What is left of it, stated — and it is NOT the escape (INC.47).** `types.ts` used to
   escape: the file declaring tsc's whole type universe is one strongly-connected component
   that the file-boundary cut does not reach, and the walk was a node-budget stop at both
   2 M and 12 M nodes. That is closed — the walk is now a canonical serialization
   (discover each reachable type once, name it by its discovery INDEX, fold each one's own
   local structure in discovery order), which is linear, needs no cycle case and leaves no
   component to hash: `types.ts` is **6.21 ms for 871 exports** against 122.52 ms for one,
   the whole-program cost is **16 ms** against 131, and **no file of the 78 escapes**.
   **It bought no rate.** The same 40-commit corpus reads **67% on both arms with all 40
   per-case verdicts identical**, because a commit touching `types.ts` really does move an
   exported declaration. The 87.5% ceiling this page used to quote was a mis-read of the
   stability runner's own summary line (`if (escaped)` counts every case that TOUCHED an
   escaping file); the real ceiling was 70%. **So the residual 33% is 13 commits that each
   genuinely move a signature, and no refinement of the fingerprint can serve them** — the
   only remaining question was whether ordinary LAYERED code has a higher rate than one
   compiler's own sources — **and it does not**. Measured on 40 real commits each:
   `cronstrue` **50%**, tsc **67%**, `marked` **72%**, so the two libraries bracket the
   compiler and (INC.50)'s per-hop closure is refused by its own threshold. The rate
   tracks what a codebase's commits TOUCH, not how it is layered: cronstrue's edits are to
   the locale classes that ARE its exported surface. `cronstrue` is the control arm
   because it is the one library this checker agrees with tsgo on exactly — a library we
   report errors on has types degraded to `any`, and a degraded type is artificially
   stable.

   **AND SINCE (INC.48) IT SURVIVES THE PROCESS.** `Project.saveState()` encodes the
   signatures, the escapes, the program's file list, that build's diagnostics and a
   content hash per input; `restoreState()` adopts them, so a reopened project starts at
   the gate instead of at a rebuild. Measured on the same 78 sources, every arm agreeing
   row for row: warm **5,855 -> 94 ms (62x)** with nothing changed and 259 ms with a file
   changed on disk; in a COLD process — a real restart — **9,625-9,844 -> 155-175 ms**,
   from a 47 KB snapshot. It writes no file: the host decides where its caches live. Every
   part of the claim is validated (compiler build id, config path, a content hash per file
   AND per `.json` input), and a restored state is not trusted until one build has
   re-crawled the project, because a file ADDED while the process was down appears in no
   content hash.

   **What (INC.47) did buy is soundness.** The previous walk bounded its recursion with a
   depth cap of 24 and hashed everything below it as a single constant — a MISSED
   invalidation, i.e. a stale project-wide diagnostic, for any exported type deeper than
   that. Two pins hold it, and both are RED against the pre-(INC.47) binary.

   **`referencesAt` IS narrowed, and so is `renameAt`.**
   The sentence that used to stand here — "their claim is about every file, so there
   is nothing to narrow to" — confused the claim with the evidence: an occurrence of
   a symbol must SPELL a name that symbol is reachable by, so both select their
   population syntactically and let `captureIn` derive the check partition from it,
   while still answering about the whole program. What they cannot bound they refuse,
   and then the old whole-program sweep runs unchanged. Rename needed two things
   references did not: BOTH of its builds take the same partition (its verification
   compares diagnostics as a multiset, which a partition filters), and its population
   is the closure widened by the NEW name (its "nothing moved" check scans for
   occurrences already spelling it, and would otherwise pass vacuously).

   **What the other three capture channels cost, stated rather than left to be found.**
   `scripts/capture-channel-equivalence.sh` sweeps members, scopes and signatures, which
   the types-and-definitions gate does not cover. Scopes agree everywhere (0 of 8,986).
   Members and signatures diverge in **286 rows of 21,507**, in five display mechanisms,
   with nothing ever ABSENT in either arm: 116 rows where the narrowed arm renders the
   ALIAS tsc renders (`Intl.LocalesArgument`) against the full arm's expanded body with
   `| undefined` doubled, 2 where the narrowed arm renders a generic member's `TData`
   against the full arm's `any`, 167 where a member's own type parameter prints
   `<K extends any>` under the narrow arm and `<K>` under the full one (neither renders
   the declared constraint, so both are wrong alike), and **one** signature parameter
   rendering `any` under the narrowed arm. That last row is the whole user-visible cost.
2. ~~A computed object-literal key `{ ["p"]: v }` is outside the swept population.~~
   **CLOSED** — and it was the last SILENT shape anywhere in this
   API. Round 930 measured it as reported in two of its three shapes
   (`WOULD_NOT_COMPILE` where stranding the key breaks the program,
   `OCCURRENCES_INCOMPLETE` where the literal has no contextual type) and silent in the
   third, an **optional** contextual member, where dropping it costs no diagnostic. All
   three are now answers: the key is an ordinary occurrence, found, highlighted, hovered,
   navigated and renamed over the TEXT rather than the quotes — as are `{ "p": v }`,
   ``{ [`p`]: v }`` and a computed member DECLARATION, which fall out of the same one
   predicate. `{ [K]: v }` is deliberately NOT one: it spells no fixed name and is a
   reference to the binding `K`, which is tsc's answer too. The number is kept so the
   round notes keep referring to the same gap.
3. **An object literal's own METHOD** (`{ om() { … } }`) has no definition of its own —
   `definitionsAt` on the declaration name answers empty, see § 9. *Corrected*:
   it does **not** refuse a rename. A use of it resolves to the declaration, so the
   occurrence set is complete and `renameAt` rewrites both ends from either caret.
   *Round 932*: a computed or quoted METHOD key (`{ ["om"]() { … } }`) is SWEPT but
   still unresolved, so it is now a stated `OCCURRENCES_INCOMPLETE` conflict rather
   than a span nobody looked at — the same treatment a computed member DECLARATION in a
   class or an interface gets where the checker does not put it in a member table.
4. **A member on an `any` receiver** cannot be placed, so it refuses a member rename.
5. **A shorthand in a literal nothing contextually types** likewise.
6. ~~A member named by a TEMPLATE element access is silently missed.~~ **CLOSED round
   931** — `` o[`p`] `` is an ordinary occurrence: found, highlighted,
   hovered, renamed (over the text, not the backticks) and completed. A template with a
   **substitution** (`` o[`p${x}`] ``) spells no fixed name and is deliberately out, as
   it is in tsc. The number is kept so the round notes keep referring to the same gap.
7. ~~An enum member's declaration name reports `any`.~~ **CLOSED**
   — it reports the member's own type (`Plain.Alpha`), the same instance its use
   reports, in all five enum shapes. tsc additionally decorates the answer with the
   member's VALUE (`(enum member) Plain.Alpha = 0`); this API renders types, so that
   part is a deliberate divergence rather than a gap (§ 8). The number is kept so the
   round notes and the table below keep referring to the same gap.
8. **Hover picks one subject where a span names two** — a shorthand reports the local.
   References, definition and rename answer both.
9. **`export { p }` / `import { p }` rename plainly** where tsc expands them, because
   here they are one symbol. Stated in § 10d, deliberate.
10. **No LSP layer.** This is an embedding API; the protocol, its 0-based coordinates and
    its lifecycle are a host's job. § 12 is the shape. **(not pinnable)**

## 14. Cancellation, and state that outlives the process

Two members § 0 lists that no section documented until this one. They have nothing
in common except their audience: both exist for a host with a **lifecycle** — an
editor whose user keeps typing while a build is in flight, and an editor that gets
restarted. Neither is needed by a batch caller, and both are off by default.

### 14a. `cancellation` — abandoning an answer nobody wants

```kotlin
public var Project.cancellation: CancellationSignal?  // Project.kt:592, null by default

public fun interface CancellationSignal {             // Cancellation.kt:41
    public fun isCancelled(): Boolean
}

public class CompilationCancelledError : Error        // Cancellation.kt:69
```

**Why the host has to supply this rather than interrupt the thread.** A build runs
on the compiler's own deep-stack thread and this API *joins* it, so the calling
thread is blocked for the whole build and cannot abandon it from outside.
Cancellation is therefore cooperative: the host supplies the signal, the compiler
polls it. On the IntelliJ platform `DaemonCodeAnalyzer` restarts analysis on every
write action, so without this an editor has only bad options — block a pooled
thread producing an answer that is already stale, and delay the next, wanted one
behind it. That is a capability gap and not a latency one; no amount of narrowing
the check closes it.

**Arming it.** `CancellationSignal` is a `fun interface`, so a lambda is the whole
ceremony, and one assignment covers the project: all eight
`ProjectCompiler(…).build(…)` sites in `Project.kt` pass the field
(`:843`, `:941`, `:2103`, `:2407`, `:2871`, `:3412`, `:3496`, `:3565`), so every
build this project drives — a plain rebuild, a narrowed one, a capture, a rename's
verification — polls it.

```kotlin
project.cancellation = CancellationSignal { indicator.isCanceled }
```

**Where it is polled, and what that costs.** `Cancellation.check()` runs at every
`pass("…")` boundary (~480 per compile) and, in the spine walk, once per
`Cancellation.SPINE_POLL_INTERVAL` nodes — 1024, a power of two used as a mask, so
837 polls for the compiler profile's 856,962 nodes. The spine site is the one that
matters and is pinned separately (`the spine polls too …`): without it a single
large buffer's walk — 1.65 s on tsc's own `checker.ts` — would be uncancellable,
which is precisely the case an editor most needs to abandon. Per node the price is
an int increment, a mask and a predictable branch, against a volatile read every
1024th time.

**What a cancelled build leaves behind.** It throws `CompilationCancelledError` out
of the call that asked for it and produces **no result**. Nothing in `Project` is
left half-updated, by construction rather than by care: every cache assignment
happens *after* `ProjectCompiler.build` returns, so a throw skips all of them and
the project is exactly as it was — disarm and ask again and you get the answer a
project that was never cancelled gives. The abandoned build's work is lost; there
is no partial answer to keep, so **cancel because the answer is unwanted, not to
poll**. The signal itself is installed for exactly one build and restored in a
`finally` (`ProjectCompiler.kt:198`), so a cancelled build leaves nothing armed for
the next one.

> The install is a process-global with install-and-restore — the house pattern,
> `SystemVfs.workingDirectory` being the other instance. The stated cost: two builds
> running **concurrently in one process** share the field, so the second install
> wins and the first build polls the wrong signal. That is already outside
> `Project`'s one-thread-at-a-time contract (§ 11), and `--workers` installs none.

#### The rough edge: it is an `Error`, and a JVM host will feel it

`CompilationCancelledError` extends `Error`, not `Exception`, and that is
deliberate. The checker carries defensive `catch (Exception)` guards — narrowed
from `catch (Throwable)` on 2026-07-04 for exactly this class of reason — and the
crawl and the `Vfs` carry more. A cancellation modelled as a `RuntimeException`
would be swallowed by whichever guard it happened to be thrown inside, and the
build would carry on with a missing file or a wrong default: a silently wrong
answer, which is worse than not cancelling at all. `Error` is safe because the same
sweep left **no** `catch (Throwable)` and **no** `catch (Error)` anywhere in
`commonMain`, and the one boundary guard in `Checker`'s `init` catches
`StackOverflowError` alone. `runWithDeepStack` transfers it faithfully across the
deep-stack thread because it uses `runCatching`, which captures `Throwable`
(`DeepStack.jvm.kt:58`, `:66`).

**The consequence for a host, stated rather than left to be discovered.** On the
JVM, `ExecutorService.submit` returns a `Future` whose `get` wraps whatever the task
threw — `Error` included, since `FutureTask` catches `Throwable` — in a
`java.util.concurrent.ExecutionException`; `CompletableFuture` does the same for
`get`, and wraps in `CompletionException` for `join`. So a plugin that runs its
analysis on an executor and has one generic failure branch
(`catch (e: ExecutionException) { log.warn(…) }`) will log a warning **per cancelled
keystroke** — which is the exact frequency this API is designed to be cancelled at,
so the log fills with reports of the mechanism working. A host must unwrap the
cause and read a cancellation as *no answer*, not as a failure:

```kotlin
fun analyze(path: String): List<Diagnostic>? =
    try {
        executor.submit<List<Diagnostic>> { project.diagnostics(path) }.get()
    } catch (e: ExecutionException) {
        // Unwrap: the Error crossed the executor boundary inside this.
        when (e.cause) {
            // Not a failure. The answer was not wanted; there is nothing to report,
            // nothing to log and nothing to retry — the next query rebuilds.
            is CompilationCancelledError -> null
            // Anything else is a real failure and belongs in your log.
            else -> throw e
        }
    }
```

Two smaller notes for the same host. `CompilationCancelledError` is *not* the
platform's own cancellation type — if your host has one (the IntelliJ platform's
`ProcessCanceledException`, say) the translation is yours to make, at this boundary
and nowhere deeper. And a signal that answers true forever cancels every subsequent
build too, so a host that arms per request must clear or re-point the field rather
than leaving a spent indicator installed.

### 14b. `saveState()` / `restoreState(text)` — the state that survives a restart

```kotlin
public fun saveState(): String?                  // Project.kt:3120
public fun restoreState(text: String): Boolean   // Project.kt:3175
```

**What the snapshot is.** (INC.46) made project-wide diagnostics incremental
*within* a process (§ 4b); all of that state is in memory, so an IDE restart, a
plugin reload or a daemon recycle throws it away and the first query pays a whole
program build again. The snapshot is the part that has to survive
(`ProjectStateSnapshot`, core): the compiler **build id**, the **`configPath`**, a
**content hash per input**, the program's **file list** in crawl order, the
(INC.46) export **signatures** and **escapes**, and that build's **diagnostics**,
row for row and in order. It is encoded as JSON carrying a format version
(`FORMAT_VERSION = 1`, `ProjectStateSnapshot.kt:146`); a snapshot written at another
version decodes to null and is refused rather than read.

**It writes no file.** `saveState` answers a string and `restoreState` takes one, so
the host decides where — and whether — its caches live. An embedding API that
dropped a file into somebody's source tree unasked would be making that decision for
it; the CLI's `--incremental` (`tsconfig.xtsbuildinfo`, INV.7(d3)) remains the
convention for callers who want the other behaviour.

**When `saveState()` answers null**, which is not an error and needs no fallback
beyond skipping the write:

* **No whole-program build has established a surface yet** — call `diagnostics()`
  first (`Project.kt:3122`).
* **This compiler build may not be reused across processes at all** — a `.dirty` or
  `unknown` build id names a tree with local changes, and two such trees share the id
  without sharing the behaviour (`ProjectStateSnapshot.isReusableBuildId`,
  `Project.kt:3123`). Refusing to *write* such a snapshot is belt-and-braces: the
  restore refuses it too, and a snapshot that can never be adopted is a file a host
  would otherwise keep writing and never use.

It also calls `checkOpen()`, so it must be called **before** `close()` — afterwards
it throws `IllegalStateException` like every other member (§ 11). The hashes are
read through the overlay, so an unsaved buffer is hashed as what this project
actually checked; a later process reading the on-disk text sees a different hash and
re-checks that file, which is the right answer.

> Writing a pin for any of this: **every local dev build's id ends `.dirty`**, so a
> test that does not install the named seam
> (`ProjectStateSnapshot.allowUnstableBuildIdForTesting`, `ProjectStateSnapshot.kt:164`)
> exercises nothing locally and the real path only in CI — i.e. it passes for
> opposite reasons in the two places. `ProjectStateSnapshotTest` installs and
> restores it per test, and pins the shipped default separately
> (`the unstable-build-id seam is off by default`).

**When `restoreState(text)` answers false.** A refusal is never an error, because
the caller's fallback is the build it would have done anyway. Each of these produces
a stale answer if skipped, which is the one failure the mechanism exists to prevent
(`Project.kt:3177`–`:3200`):

* **This project has already built, or already restored.** A snapshot is a claim
  about a starting point; adopting one over live state would replace answers this
  process computed with answers it did not.
* **Unreadable, or written at another format version.** Nothing to adopt.
* **A build id that may not be reused at all, or a different compiler build.** A
  different compiler may report different rows for the same text.
* **A different `configPath`.** The state describes one project.
* **An empty program file list.**
* **Any recorded path is now unreadable.** A vanished program file changes what every
  importer resolves; a vanished `.json` input changes what the program *is*.
* **A recorded `.json` input's content changed.** A changed `tsconfig.json` — or an
  `extends` target, or a `package.json` whose `type` decides a file's module format —
  does not date one file's rows, it changes what the program is and which options
  apply, so every stored row is suspect and narrowing cannot repair it. Which `.json`
  files a build depends on is not a function of the project path, which is why they
  are *recorded as the build reads them* (`OverlayVfs.jsonReads`) rather than guessed.
* **A program file that appears in the file list but was never hashed**, so it cannot
  be proved unchanged.

A program **source** file whose content changed is *not* a refusal: it becomes this
project's dirty set, and is exactly what the (INC.46) gate is for.

**The one limit to design around: a content hash cannot see an ADDED file.** So an
adopted state is *unverified* until one build has re-crawled the project and found
the same program. `restoreState` sets that flag (`Project.kt:3207`), and while it is
set even a project whose dirty set is empty runs the gate with an empty partition
rather than answering from the snapshot directly (`Project.kt:3555`); the flag clears
only when a build's own `programFiles` has agreed (`:3606`, and on a full build
`:3499`). This is the check that would be skipped by a naive "trust the snapshot when
nothing changed" implementation, which passes every other pin — `a file added while
the process was down is not missed` is the one that does not.

**What it is worth** is in § 13's gap 1 with its provenance: warm, with nothing
changed, **5,855 → 94 ms**; 259 ms with a file changed on disk; and in a cold
process — a real restart, which is the case this exists for — **9,625–9,844 →
155–175 ms**, from a 47 KB snapshot, every arm agreeing row for row.

The host flow is two calls at the two ends of a session:

```kotlin
class ProjectSession(projectPath: String, private val cache: Cache) {

    private val project = Project.open(projectPath)

    init {
        // Restore BEFORE the first query — a project that has already built refuses.
        // false means "nothing adopted", which is not an error: the first query is
        // then simply the build it would have been anyway.
        cache.read()?.let { project.restoreState(it) }
    }

    fun diagnostics() = project.diagnostics()

    fun close() {
        // Save BEFORE close(); afterwards every member throws. null means there is
        // nothing worth storing — no whole-program build yet, or a compiler build
        // whose id may not be reused. Do not write a placeholder for it.
        project.saveState()?.let { cache.write(it) }
        project.close()
    }
}
```

### 14c. Where both are pinned

The executable spec, for a reader who wants the contract rather than the prose —
and because a page of prose about behaviour drifts within three rounds while pins do
not (§ 13):

| claim | pinned by |
|---|---|
| an armed signal that never fires changes no answer; a firing one stops the build; a cancelled build leaves the project and the global exactly as they were, mid-flight included; the spine polls as well as the passes; the type cannot be caught as an `Exception` | `ProjectCancellationTest` (`-project`, `src/commonTest`) — seven pins |
| a restored state answers what a fresh build answers, with no edit / a body edit / a signature edit; an added file is not missed; a restore's first query goes through the gate and its second builds nothing; every refusal above; `saveState` before any query is null; the unstable-build-id seam is off by default | `ProjectStateSnapshotTest` (same module) — thirteen pins |

Two things those tables deliberately do not cover. **Every wall figure quoted in
this section is pinned by nothing** — § 13's rule applies here too: a timed
assertion over a compile is a coin flip, so the numbers are dated and provenanced
instead. And the `ExecutionException` unwrapping above is a property of
`java.util.concurrent`, not of this compiler: it is stated here because it is the
predictable consequence of a deliberate design decision, but nothing in this repo
tests a host's executor.
