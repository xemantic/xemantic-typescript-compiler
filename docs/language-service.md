# Using the language service — the `Project` embedding API

How to embed xtsc in a build tool, an IDE plugin, a test harness or an LSP
server: open a TypeScript project, ask what is wrong with it, apply the buffers
your user is typing into, and ask again — without the edits ever reaching disk.

**Status (round 921, 2026-08-18).** Landed: diagnostics, in-memory edits,
line/offset conversion, syntactic node lookup, quick info (hover),
go-to-definition **including members** (`o.p`, inherited, imported, union,
namespace, enum, lib), **batched semantics** — many positions, or a whole file,
in one build — **completions**, both halves: members `(API.4a)` and free
names `(API.4b)`, **find-references plus document highlights** `(API.5)` — and
**signature help** `(API.6)`, every overload. Not yet: keywords (§ 10a says why
they are refused rather than guessed), rename. See the `(API.*)` items in
`PLAN-PHASE-5.md`.

> **If you are on a version before round 920, upgrade before trusting any
> position.** Two rounds of the same defect class, both fixed and both now covered
> by an invariant gate that runs over real TypeScript rather than over fixtures:
>
> - `(BUG.2)`, round 919: the token index de-synchronised at the first template
>   literal with a `${…}` substitution, and the damage ran to end of file — so
>   `nodeInfoAt`, `quickInfoAt`, `definitionsAt` and `completionsAt` all answered
>   about a huge enclosing node instead of the one at the caret. On tsc's own
>   `checker.ts`: 50,684 tokens for 3,151,772 characters, longest token 62,089.
> - `(GATE.2)`, round 920: the same thing at a **backtick inside a regular
>   expression** (a shape tsc's own `utilities.ts` contains), and — separately — a
>   **parenthesis-less arrow parameter**, an index-signature parameter, a `catch`
>   variable, `declare global`'s `global`, a JSX tag name and a construct
>   signature's `new` all carried spans no lookup could enter, so a caret on any of
>   them answered about the enclosing construct. The arrow-parameter case alone is
>   **328 sites in tsc's 78 compiler sources**.
>
> The gate is `TokenIndexInvariants` plus `TokenIndexGateTest`, and it now holds
> over **1,327 files / 101,287,620 characters / 3,936,158 identifiers** with zero
> violations.

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

// one caret, one question — one compile each
project.quickInfoAt("/path/to/my-app/src/a.ts", 142)     // hover
project.definitionsAt("/path/to/my-app/src/a.ts", 142)   // go to definition

// many carets, both questions — ONE compile (§ 10; this is the one to reach for)
project.semanticsAt("/path/to/my-app/src/a.ts", listOf(142, 190, 240))
project.fileSemantics("/path/to/my-app/src/a.ts")

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
| `semanticsAt(f, offsets)` | **ONE full build**, whatever the offset count | both answers, per span |
| `fileSemantics(f)` | **ONE full build** | every identifier in the file |
| `completionsAt(f, o)` | **full build, every call** | free at a caret that admits no completion — those do not build |
| `documentHighlightsAt(f, o)` | **ONE full build** | sweeps this file's identifiers |
| `referencesAt(f, o)` | **ONE build clean, TWO dirty** | sweeps the whole program's; § 10b has the measured figures |
| `signatureHelpAt(f, o)` | **full build, every call** | free at a caret in no argument list — those do not build |
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
  build** — so asking both about one caret is two compiles. That is deliberate
  rather than an oversight: a capture build types nodes the checker had no reason
  to type, so its diagnostics are not interchangeable with a plain build's and
  reusing it would quietly change what `diagnostics()` reports.
- **So batch.** `semanticsAt` takes many offsets and `fileSemantics` takes a
  whole file, and each is **one** compile — the positions go in as a set and the
  checker records at all of them during the single walk it was going to perform
  anyway. Measured on a 34-identifier fixture: **one sweep 100 ms, the same 34
  carets one at a time 3,373 ms (34x), and 6,209 ms (62x) when each is asked both
  ways.** The ratio is the point, not the milliseconds — it is the number of
  compiles, so it holds at any project size. Reach for the batch by default and
  keep the single-caret pair for when you genuinely have one caret and one
  question.

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
*enclosing* node answers. **A caret on a real construct never should**, and until
round 920 several did — see the box at the top; if you are writing a host, that is
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

### Two mechanisms, because a member is not bound by any scope

A **free name** is resolved through the lexical scope chain in force at that
position. A **member name** — the `p` of `o.p` — is resolved through its
**receiver**: `o`'s type is computed and `p`'s property symbol on that type is the
answer.

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
| `"s".length`, `arr.push` | the **lib**'s declaration (see the lib note below) |

**An empty list is a normal answer**, and these are the ways to get one:

- there is no node at the offset, or the file is unknown;
- the offset is on a keyword, a literal or trivia — nothing either mechanism binds;
- the name resolves to a symbol with no declaration to point at;
- **nothing declares the member** (`(o as any).absent`) — silence, never the
  nearest same-named anything;
- **an element access** (`o["p"]`) — the argument is a literal, not an identifier,
  and only identifiers are offered a definition;
- **an object-literal key being declared** (`{ p: v }`) — the useful answer is the
  *contextual* type's property, which is a third mechanism and is not built;
- **a member's own declaration name** (`interface I { p: string }`) — it already
  *is* the declaration;
- **a chained namespace segment** (`A.B.x`) — the middle segment would have to be
  resolved the same way, for a case one caret to the left already answers.

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
going to perform anyway. `quickInfoAt` and `definitionsAt` are the degenerate
one-span case, and paying a compile per caret is the thing this replaces.

Measured on a 34-identifier fixture, warm:

| what | compiles | wall |
|---|---|---|
| `fileSemantics` — all 34 spans | 1 | **100 ms** |
| `quickInfoAt` x 34 | 34 | 3,373 ms |
| `quickInfoAt` + `definitionsAt` x 34 | 68 | 6,209 ms |

The ratios (**34x** and **62x**) are what transfers to your project; the
milliseconds are a property of a tiny fixture. It is a count of compiles, so the
saving does not shrink as the project grows.

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

**`quickInfo` may be null and `definitions` may be empty** — independently. An
object-literal key being declared has a type and, deliberately, no definition
(§ 9). An entry with neither is still returned: "there is a node here and the compiler had nothing to
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
want to tell them apart. **Keywords are not offered**; see below.

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
  that list in round 920, when the token index learned to see it at all.)
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

**Accessibility is reported, not enforced.** `private` and `protected` members
**are offered**, with `accessibility` saying which they are. Whether to hide one
depends on where the caret sits relative to the declaring class, which is a
mechanism this round did not build; reporting the fact lets you apply your own
rule, where hiding on a half-implemented test would silently lose real
candidates. `kind` is how you tell a method from a property — there is no
separate flag.

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

**Keywords are not offered, deliberately.** A useful keyword list is
context-sensitive: `interface` may start a statement and may not appear inside an
expression, `await` belongs only inside an async function, `extends` only in a
heritage clause. The anchor here is a *token-level* device — it knows what
precedes the caret, not which grammar production it sits in — so an unconditional
list would offer items that do not compile, which is the one thing the member
half already refuses to do. Merge your own keyword list into ours if you want
one; nothing about the result depends on ours being empty.

**Two known imprecisions, stated rather than hidden.** A name declared *later* in
the same block is offered (a block's bindings are a set, not a sequence — the
binding exists and is merely in its temporal dead zone, which is what tsc offers
too). And a function's body locals are visible from inside its own *parameter
defaults*, because the binder's function scope is flat; writing one there is an
error the checker reports separately.

**Cost: one compile, one caret.** The same caveats as hover (§ 8): it builds, and
that build is not the `diagnostics()` build. Batching many carets is
`semanticsAt`'s question, not this one. Measured on the compiler profile above, a
free-name completion is **5.3 – 8.9 s warm** — essentially all of it the rebuild,
with the enumeration under a millisecond of it. **So debounce.** This is not a
per-keystroke query on a project of that size, and the reason is the compiler's
lack of incremental reuse (§ 3), not the completion machinery.

## 10b. Find references, and document highlights

```kotlin
val all: List<ReferenceLocation>  = project.referencesAt(path, offset)          // the program
val here: List<ReferenceLocation> = project.documentHighlightsAt(path, offset)  // this file
// ReferenceLocation(fileName: String, start: Int, end: Int, isDeclaration: Boolean)
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

**The declaration comes back, flagged.** `isDeclaration` marks the spans that *are*
declarations rather than uses, the way tsc's `isDefinition` does — filter on it if
you want uses only. It is exact: membership in the set the compiler produced, not a
guess about which parent kinds declare a name.

### What is refused, and why

- **READ versus WRITE is not reported.** `x = 1` and `x++` are trivially writes;
  `[x] = pair`, `({ x } = o)` and `for (x of xs)` are writes whose identifier sits
  under an array literal, an object literal or a `for` head. A rule built from the
  easy positions calls the destructuring ones READS, and you could not tell a
  complete answer from an incomplete one. Deciding it properly is the same
  grammar-position mechanism keyword completions are refused for (§ 10a).
- **A caret on a MEMBER's own declaration name works only when that member is used
  somewhere.** `p` in `interface I { p: string }` is bound by no scope and has no
  receiver, which is exactly why § 9 answers no definition there. The reference
  search recovers it from the sweep's own evidence — if an occurrence resolved *to*
  that span, the caret is one of that symbol's declarations — so a member declared
  and never used answers an **empty** list rather than a list of one. tsc answers
  one. Free names are unaffected: a `const`, a parameter, a function, a class, an
  interface or an import all resolve from their own declaration name.
- **Only identifiers.** A keyword, a literal, punctuation or trivia answers empty
  **and does not build**. An element access (`o["p"]`) names its member with a string
  literal, so it is neither found nor searchable — § 9's boundary, unchanged.
- **The program, not the libraries.** A declaration in a `lib.*.d.ts` comes back
  (flagged), because the caret resolved to it; no lib file is swept for uses.

Sorted by `(fileName, start)` ascending, one entry per span. `start until end` is
half-open and **exact** — the identifier's own text, not a raw `Node.end`.

### Cost, measured

On this repo's own compiler profile — tsc's 78 source files, 9,977,097 characters,
**381,670 identifiers**, real libs, warm:

| query | builds | wall |
|---|---|---|
| a plain rebuild, for reference | 1 | 5.5 – 5.9 s |
| `documentHighlightsAt` — `checker.ts`, 125,289 identifiers | 1 | **6.0 – 7.2 s** |
| `referencesAt` on a **clean** project | 1 | **8.3 – 9.9 s** |
| `referencesAt` on a **dirty** project | 2 | **13.0 – 13.5 s** |

The sweep itself is 2.5 – 4 s on top of the rebuild it rides, whatever the caret:
resolving 381,670 identifiers costs the same whether the answer is 168 hits in one
file (a local of `createTypeChecker`) or **9,827 hits across 49 files**
(`SyntaxKind`, imported nearly everywhere). The second build in the dirty row is
`files`' — the program's file list is a question only a build answers — so a host
that has just asked for `diagnostics()` pays one build, not two.

**Budget memory as well as time.** A whole-program sweep holds a resolution per
identifier: peak heap on that profile is **~1.9 GB**, and the default 512 MB of a
plain JVM is not enough. `documentHighlightsAt` does not have this shape — it holds
one file's.

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

## 13. What is coming, and what would change

- **keyword completions** — see § 10a for why they are refused rather than
  guessed. They need a grammar-position mechanism the token-level anchor does not
  have; until one exists, merge your own list into ours.
- **contextual object-literal keys** — `{ p: v }`'s own `p` still answers
  nothing, in either query: the useful target is the contextual type's property
  (§ 9), and a caret there is answered today as an ordinary free name.
- **member completion after an unparsable receiver** — a `.` the parse did not
  turn into a member access answers an empty list rather than guessing a receiver
  out of bracket-balanced text.
- **read versus write on a reference** — § 10b says why a partial answer there is
  refused, and it is the same grammar-position mechanism the keyword list needs.
- **rename** — it is find-references plus an edit plan, and the edit plan is where
  the work is: a shorthand `{ p }` and an `import { p as q }` do not rewrite the way
  a plain occurrence does.

None of these change what is documented above. The one thing that would is the
architectural inversion (`docs/ARCHITECTURE-RETHINK.md`) that makes the checker
lazy and re-entrant, at which point a query stops being a full rebuild. The
public surface here is deliberately value-typed — no AST, no `Symbol`, no `Type`
crosses it — precisely so that change can happen underneath you without breaking
your host.
