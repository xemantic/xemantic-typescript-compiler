# Using the language service — the `Project` embedding API

How to embed xtsc in a build tool, an IDE plugin, a test harness or an LSP
server: open a TypeScript project, ask what is wrong with it, apply the buffers
your user is typing into, and ask again — without the edits ever reaching disk.

**Status (round 911, 2026-08-17).** Landed: diagnostics, in-memory edits,
line/offset conversion, syntactic node lookup, and quick info (hover).
Not yet: go-to-definition `(API.3b)`, batched capture `(API.3c)`, completions
`(API.4)`. See the `(API.*)` items in `PLAN-PHASE-5.md`.

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
project.quickInfoAt("/path/to/my-app/src/a.ts", 142)     // hover
project.definitionsAt("/path/to/my-app/src/a.ts", 142)   // go to definition

project.close()
```

`open` compiles **nothing**. It resolves and validates the path and returns; the
first query compiles. That is deliberate: it lets you stage the buffers you
already hold *before* the first build, so a host with unsaved state does not pay
for a build of the on-disk truth it is about to discard.

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
| `diagnostics()` / `diagnostics(f)` / `files` | **full build** when dirty, else cached | a second call with no edit in between is free |
| `quickInfoAt` | **full build, every call** | not cached today — see below |
| `definitionsAt` | **full build, every call** | same mechanism, same caveat |
| `updateFile` / `deleteFile` | free | marks dirty |

**A query on a dirty project is a full rebuild.** That is a property of the
compiler, not a shortcut taken here: `ProjectCompiler.Result` is a flat value
(paths, diagnostics, an import graph) that retains no AST, no binder output and
no checker, because the checker's construction *is* the compilation. What makes
a re-query cheap anyway is the compiler's process-global, **content-keyed** parse
cache, which every unedited file hits — so the second build of an N-file project
re-parses only what changed. For scale: a warm rebuild of the TypeScript
compiler's own 78-file sources is ~5.2 s; a normal application project is far
less.

Two consequences for a host:

- **Debounce, do not poll.** Re-asking `diagnostics()` per keystroke costs a
  compile per keystroke. Ask on idle.
- **`quickInfoAt` and `definitionsAt` build every call and do not reuse the
  build** — and asking both about one caret is two compiles today. That is
  deliberate rather than an oversight: a capture build types nodes the checker
  had no reason to type, so its diagnostics are not interchangeable with a plain
  build's and reusing it would quietly change what `diagnostics()` reports. The
  fix is batching — the underlying request already accepts a *set* of spans, so
  "semantic info for this whole file" is one compile rather than N. Exposing it
  is `(API.3c)`, and it is the item that makes hover practical for an editor.

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

## 5. Editing in memory

```kotlin
project.updateFile(path, text)   // an unsaved buffer; also creates files that are not on disk
project.deleteFile(path)         // shadows a file as absent; undo by updateFile-ing it back
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

> **Known defect, tracked as `(BUG.1)`:** on text using a **lone `\r`** as its
> terminator, the parser and the checker disagree about line numbering — a
> syntax diagnostic numbers the lines and a semantic one reports line 1. `\n`
> and `\r\n` are unaffected. Classic-Mac line endings are practically extinct,
> which is why this has never mattered, but if your host normalizes buffers, do
> it before handing them over.

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
*enclosing* node answers. An offset past end-of-file, a negative offset, or an
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
capture during the walk too. `(API.3)` in `PLAN-PHASE-5.md` has the full
reasoning.

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

**An empty list is a normal answer**, and there are four ways to get one:

- there is no node at the offset, or the file is unknown;
- the offset is on a keyword, a literal or trivia — nothing the scope binds;
- the name resolves to a symbol with no declaration to point at;
- **the offset is on a MEMBER name** (`o.p`, a property signature's name, an enum
  member behind its enum). This one is deliberate. Resolving a member needs the
  receiver's type and its property symbol, which is a different mechanism from
  the scope lookup behind this call; answering it with a scope lookup would find
  whatever unrelated binding happens to share the spelling, and an editor that
  jumps somewhere confidently wrong is worse than one that does not jump. Member
  definitions are an honest gap, not a bug.

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

## 10. Rules that apply to everything

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

## 11. A minimal hover host

```kotlin
class HoverHost(projectPath: String) {
    private val project = Project.open(projectPath)

    fun didChange(path: String, text: String) {
        project.updateFile(path, text)          // free; the next query rebuilds
    }

    /** LSP is 0-based, this API is 1-based — convert once, here. */
    fun hover(path: String, line0: Int, char0: Int): String? {
        // A client one keystroke ahead of us can send a line we do not have.
        val offset = try {
            project.offsetAt(path, line0 + 1, char0 + 1) ?: return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val info = project.quickInfoAt(path, offset)
            ?: project.quickInfoAt(path, offset - 1)   // the touch case: `abc|`
            ?: return null
        return info.displayString
    }

    fun diagnostics(path: String) = project.diagnostics(path)   // call on idle, not per keystroke

    fun dispose() = project.close()
}
```

## 12. What is coming, and what would change

- **`(API.3c)` batched capture** — one build answering many positions, so a
  host stops paying a compile per caret. Purely additive; `quickInfoAt` and
  `definitionsAt` keep working.
- **member go-to-definition** — `definitionsAt` refuses a member name today
  (§ 9); answering it needs the receiver's type resolved and its property
  symbol found, in the same capture hook.
- **`(API.4)` completions** — the largest, and the one that stresses the design:
  a completion request has no node at the position at all, since the user is
  mid-identifier.

None of these change what is documented above. The one thing that would is the
architectural inversion (`docs/ARCHITECTURE-RETHINK.md`) that makes the checker
lazy and re-entrant, at which point a query stops being a full rebuild. The
public surface here is deliberately value-typed — no AST, no `Symbol`, no `Type`
crosses it — precisely so that change can happen underneath you without breaking
your host.
