# Using the language service — the `Project` embedding API

How to embed xtsc in a build tool, an IDE plugin, a test harness or an LSP
server: open a TypeScript project, ask what is wrong with it, apply the buffers
your user is typing into, and ask again — without the edits ever reaching disk.

**Status (round 928, 2026-08-18).** Landed: diagnostics, in-memory edits,
line/offset conversion, syntactic node lookup, quick info (hover),
go-to-definition **including members** (`o.p`, inherited, imported, union,
namespace, enum, lib), **batched semantics** — many positions, or a whole file,
in one build — **completions**, both halves: members `(API.4a)` and free
names `(API.4b)`, **find-references plus document highlights** `(API.5)`,
**signature help** `(API.6)`, every overload, `(API.7)`'s three cashed refusals
(**member completions enforce `private` / `protected`**, **keyword completions**,
**read-versus-write on every reference**) — and `(API.8)`, **RENAME**: an edit
plan that expands a `{ p }` shorthand instead of renaming the object's key, and
that is **verified by applying it and compiling again**, so a collision or a
capture withdraws it rather than reaching your buffer (§ 10d) — and `(API.9)`,
**the member occurrence set**, which closes two of the three kinds round 925
measured it short by: an **element access** `o["p"]` and a **binding element's
property name** `const { p: local } = o` are now found, navigable and renamed,
and a member's **implementors** join its group through a declared heritage edge —
and `(API.10)`, **one span, two symbols**: a **contextually typed object-literal
key** `{ p: v }` is an occurrence of the member its contextual type supplies, and
both **shorthands** (`{ p }`, `const { p } = o`) belong to the member's group as well
as the local's, expanding in whichever direction the rename came from — and `(API.11)`,
**a member's own declaration name resolves to its own symbol**, so a member renames
**from its declaration**, that name navigates and hovers, and a **merged** declaration,
an **overload set** and an **accessor pair** are one group from any of their
declaration names. Not yet: a computed key `{ ["p"]: v }` is outside the swept
population, and an object literal's own member declaration is deliberately left alone.
See the `(API.*)` items in `PLAN-PHASE-5.md`, and **§ 14 for where the whole API
stands**.

> **`(API.11)` changes what FIVE queries answer at ONE kind of caret — a member's own
> DECLARATION name** (`p` in `interface I { p: string }`, a class field, a method, an
> accessor, a static, a `#private`, a type-literal member, an enum member). It was bound
> by nothing and resolved to nothing; it now resolves through its **owner**, and to the
> whole SYMBOL rather than to the one declaration under the caret. So `definitionsAt`
> answers there (it used to answer empty), `quickInfoAt` reports the member's type (it
> used to report `any`, or the type of whatever unrelated binding shared the spelling),
> `referencesAt` / `documentHighlightsAt` answer for a member that is **declared and
> never used** and put a **merged** declaration, an **overload** set and an **accessor
> pair** in one group, and `renameAt` no longer refuses because some *other* interface
> declares the same member name. Two pins changed meaning and say so in place.
>
> **`(API.10)` widens the same three queries again, and adds a field to a value
> type.** An object-literal KEY and both SHORTHANDS are now occurrences of the member
> a contextual type supplies, so `referencesAt`, `documentHighlightsAt` and `renameAt`
> return more spans for a member and `renameAt` refuses in fewer places.
> `definitionsAt` answers an object-literal key (the contextual member, or the key
> itself where nothing types the literal) and answers a shorthand with **two**
> locations, the local and the member. `CapturedDefinition` — the core-side capture,
> not the `-project` surface — gained a third declaration set, `shorthand`; it is a
> defaulted constructor parameter, so nothing that constructed one before has to
> change.
>
> **`(API.9)` changes what three queries ANSWER, and in the widening direction.**
> `referencesAt`, `documentHighlightsAt` and `renameAt` now return more spans for a
> MEMBER — an `o["p"]`, a `const { p: … }` and every implementor's own declaration —
> and `renameAt` therefore refuses in fewer places. `definitionsAt` answers an
> element access and a binding element's property name where it used to answer
> nothing; it deliberately does **not** answer the base for an implementor's own
> member (§ 9). A host that assumed every reference span is an identifier must handle
> a string literal's span, which covers the text *between* the quotes.
>
> **`(API.7)` changed two answers you may already depend on.** `completionsAt` at a
> MEMBER caret no longer returns inaccessible members (§ 10a), and at a FREE_NAME
> caret it now returns keyword items with `kind = "Keyword"` mixed into the list
> (filter on the kind if you were treating every item as a symbol).
> `ReferenceLocation` gained a `use` field (§ 10b).

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
| `completionsAt(f, o)` | **full build, every call** | free at a caret that admits no completion — those do not build; keywords cost nothing extra |
| `documentHighlightsAt(f, o)` | **ONE full build** | sweeps this file's identifiers |
| `referencesAt(f, o)` | **ONE build clean, TWO dirty** | sweeps the whole program's; § 10b has the measured figures |
| `signatureHelpAt(f, o)` | **full build, every call** | free at a caret in no argument list — those do not build |
| `renameAt(f, o, name)` | **TWO builds** (three dirty) | the sweep plus a verification build; § 10d has the measured figures. A refusal on syntax alone does not build |
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

### A MEMBER name reports the member's type

**(BUG.4)** The `p` of `o.p` is not a name any scope binds, so asking the compiler
for "the type of `p`" is the wrong question — before round 924 that is what this
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
| `o["p"]`, caret on the string literal | the member's type |
| `N.T`, a qualified type name | the declared type |

`(API.11)` adds the row this table was missing — a member's own **declaration** name
(`p` in `interface Shape { p: string }`, a class field, a method, an accessor, a
static, a `#private`, a type-literal member). It is bound by no scope either, so before
round 928 it went through the same wrong question and reported `any`, or the type of
whatever unrelated binding shared the spelling — the same collider shape, one position
over. It now reports the member's own type, resolved through its **owner** (§ 9). An
overload set reports the whole overloaded type rather than the signature under the
caret, which is coarser than tsc and never wrong; an **enum member's** declaration name
still reports nothing, and that is the one member declaration kind this does not cover.

`this` and `super` are the one shape needing a second mechanism, for the reason
§ 9 gives about go-to-definition: they are plain identifiers in this parser, so
they type as `any` and the access does too. The class is recovered from the
walk-scoped state instead. When it cannot be — a caret in a **static** member,
whose `this` is `typeof C` and which this compiler does not model — the answer is
`any`: a non-answer, never a wrong name.

**HOVER alone still picks ONE subject where a span names two.** `(API.10)` gave
go-to-definition, find-references and rename the contextual member behind an
object-literal key and behind both shorthands (§ 9, § 10b), but a hover is one line
of text and cannot show two: a `{ p: v }` key reports the type the checker computed
at that node, and a shorthand `{ p }` reports the **local** it references — true
about a different subject than tsc, which describes the contextual member for an
object literal's form. That is a display choice, not a resolution gap.

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

A **free name** is resolved through the lexical scope chain in force at that
position. A **member name** — the `p` of `o.p` — is resolved through its
**receiver**: `o`'s type is computed and `p`'s property symbol on that type is the
answer. `(API.11)` added the fourth, which is the receiver's exact dual: a member's own
**declaration** name — the `p` of `interface Shape { p: string }` — has no receiver, and
what it does have is the class, interface, type literal or enum it is declared **in**, so
that **owner** is asked. And `(API.10)` added the third: an **object-literal key** and a
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
| `o["p"]`, caret on the string literal | the property declaration — `(API.9)` |
| `const { p: local } = o`, caret on the `p` | the property declaration — `(API.9)` |
| `{ p: v }` where something contextually types the literal | the **contextual** type's member — `(API.10)` |
| `{ p: v }` where nothing does | the key **itself** — it is the declaration |
| a member's own **declaration** name (`interface I { p }`, a field, a method, a static, a `#private`, a type-literal member, an enum member) | **itself** — and every other declaration of the same member: a merged block's, an overload's, an accessor pair's other half — `(API.11)` |
| `{ p }` (object literal, contextually typed) | **both** the local and the member — `(API.10)` |
| `const { p } = o` (binding shorthand) | the local and the property declaration — `(API.10)` |
| `"s".length`, `arr.push` | the **lib**'s declaration (see the lib note below) |

**`this` is a receiver, and where it points is a property of the position.** An
arrow function does not rebind `this` — TypeScript gives it whatever encloses it —
so a caret on `this.` inside an arrow, inside an arrow inside an arrow, or inside an
arrow in a constructor, getter, setter or property initializer, answers with the
enclosing class's members. Everything that *does* rebind `this` answers **nothing**
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
- **a computed object-literal key** (`{ ["p"]: v }`) — its string literal is outside
  the swept population, and admitting it without resolving it would turn every such
  key into a rename obstacle. tsc *does* answer it; this is a stated divergence;
- **an object literal's own member declaration** (`{ om() { … } }`) — `(API.10)`'s key
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
  that list in round 920, when the token index learned to see it at all.) **The one
  exception is the string of an `o["…"]`**, which is a member position — see below.
- A caret inside the **string of an element access** (`o["p"]`) is a MEMBER caret,
  and the receiver is the expression before the `[`.
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

**Inside `o["`, which is a member caret** (`(API.12)`, and this is a change from
round 928). `kind` is `MEMBER`, the receiver is the expression before the `[`, and
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

Four positions inside a string are deliberately still `NONE`, each stated because
tsc answers differently: a **template** (`` o[`p`] ``), which tsc completes and this
does not, because § 10b's occurrence sweep is string literals only and a member
written through a template is one a rename cannot find; a caret **at** the opening
quote, where tsc offers free names; an **indexed-access type** (`type T = Bag["p"]`),
where tsc offers free names rather than members; and a string whose **contextual**
type is a literal union or a `keyof` (`f("|")`), which tsc completes and which is a
different resolution rather than a different anchor.

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

**Accessibility is enforced** (`(API.7)`, and this is a change from round 917).
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

**Keywords are offered** (`(API.7)`, and this is a change from round 918), with
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
`semanticsAt`'s question, not this one. Measured on the compiler profile above, a
free-name completion is **5.3 – 8.9 s warm** — essentially all of it the rebuild,
with the enumeration under a millisecond of it. **So debounce.** This is not a
per-keystroke query on a project of that size, and the reason is the compiler's
lack of incremental reuse (§ 3), not the completion machinery.

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
| a member named by a **string literal** (`o["p"]`) | that access, with the span covering the text *between* the quotes — `(API.9)` |
| a member named by a **binding element** (`const { p: local } = o`) | the `p`, and not the `local` it binds — `(API.9)` |
| a member's **implementors** (`class C implements I`) | every class that declares it under a declared `implements`/`extends` — `(API.9)` |
| an object-literal **key** a contextual type supplies (`{ p: v }`) | that key, from either side — `(API.10)` |
| a **shorthand** (`{ p }`, `const { p } = o`) | the token is in the **member's** group; a caret *on* it answers the **local's** — `(API.10)` |
| a member's own **declaration** name | the whole group, from either side, whether or not the member is ever used — `(API.11)` |
| one declaration of a **merged**, **overloaded** or **accessor-paired** member | the other declarations too, all flagged — `(API.11)` |

**`(API.9)`: three kinds joined the population, and the third is not like the other
two.** An element access and a binding element's property name are ordinary members
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

**`(API.10)`: ONE SPAN, TWO SYMBOLS — and the relation between them is not
symmetric.** A `{ p }` is one token that names a *local* and a *property*, and so is
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

**READ versus WRITE is reported** (`(API.7)`, and it was refused in round 919).
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
  round 919 refused to ship.

**The declaration comes back, flagged.** `isDeclaration` marks the spans that *are*
declarations rather than uses, the way tsc's `isDefinition` does — filter on it if
you want uses only. It is exact: membership in the set the compiler produced, not a
guess about which parent kinds declare a name.

### What is refused, and why

- **A caret on a MEMBER's own declaration name is no longer a special case** —
  `(API.11)`. Until round 928 such a name was bound by no scope and had no receiver, so
  it resolved to nothing and the search had to recover it from the sweep's own evidence:
  if some occurrence resolved *to* that span, the caret was one of that symbol's
  declarations. That recovery answered an **empty** list for a member declared and never
  used, and it could only ever recover the ONE declaration under the caret. It now
  resolves through its owner and to its whole symbol, so a never-used member answers
  itself and a merged / overloaded / accessor-paired member answers its siblings' names
  too. The evidence recovery survives underneath as a fallback and nothing depends on
  it.
- **A computed key** (`{ ["p"]: v }`). Its literal is not in the swept population,
  and putting it there without resolving it would make every such key a rename
  obstacle. tsc renames it; this is a stated divergence, not a disagreement about
  what the key means.
- **Identifiers, and the string literal of an `o["p"]`.** A keyword, any other
  literal, punctuation or trivia answers empty **and does not build**. A string
  literal is swept only where it names a member: `const unrelated = "p"` is not a
  reference to `p`, which is the difference between this and a text search.
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

**`(API.9)` cost nothing measurable, and the reason is a counter rather than a
stopwatch.** Widening the swept population from identifiers to *identifiers plus the
string literals that name a member* takes it from **381,670 to 381,672** on that
profile — tsc's own compiler sources contain exactly **two** `o["…"]` accesses in
9,977,097 characters. The heritage edge is computed per member occurrence during the
same walk and did not move the wall either: `referencesAt` on `SyntaxKind` measures
**9.1 – 13.0 s** here against the 10.6 – 16.0 s recorded before it, i.e. the same
band. `fileSemantics` is untouched by construction — it enumerates `identifiers()`,
whose contract did not change — and measures 5.6 – 10.6 s for `checker.ts`'s 16,274
spans. Absolute milliseconds are only comparable within the run that took them; the
**population** is the figure that transfers.

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
| `const o: I = { p }` | **`I.p`** | `{ newName: p }` — `(API.10)` |
| `const { p } = o` | the local `p` | `const { p: newName } = o` |
| `const { p } = o` | **the member** | `const { newName: p } = o` — `(API.10)` |
| `export { p }` | the local `p` | `export { newName }` — see below |
| `import { p } from "./m"` | the symbol | `import { newName } from "./m"` |

**The shorthand rows are the discriminator this feature is tested against**, and
`(API.10)` doubled them. `{ newName }` compiles and it has renamed the object's KEY;
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
| `OCCURRENCES_INCOMPLETE` | some occurrence spelling the old name could be one of this symbol's and could not be resolved. **The member-rename refusal** — since `(API.11)` that is a member on an `any` receiver (by `o.p` or by `o["p"]`), a shorthand in a literal nothing contextually types, or an object literal's own METHOD; a *second declaration of the same member name*, an implementor, an `o["p"]` and a contextually supplied key are no longer among them |
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
| `ELEMENT_ACCESS` | an `o["p"]` the search could not resolve — a member of an `any`. A resolvable one is now an ordinary occurrence and is renamed *inside its quotes* (`(API.9)`); the kind survives because an unplaceable bracket is a different report to a user than an unplaceable identifier |
| `CONTEXTUAL_SHORTHAND` | a `{ p }` or a `const { p } = o` met while renaming a MEMBER whose property could NOT be placed — a literal nothing contextually types, an un-annotated destructured parameter. A placeable one is now an ordinary occurrence and is EXPANDED (`(API.10)`); the kind survives for `ELEMENT_ACCESS`'s reason |
| `NEW_DIAGNOSTIC` | a diagnostic the renamed program has and the original did not |
| `RESOLUTION_CHANGED` | a span that meant one thing before the rename and another after |

The position split inside that net is load-bearing: a *member* rename is judged by the
member positions and a *plain binding* rename by the free ones, so an
`interface I { p: string }` somewhere in the program does not refuse renaming an
unrelated local `p`. (Before `(API.11)` it was load-bearing for a second reason as well
— a member declaration name resolved to nothing at all — and that reason is gone.)

### What this means in practice

**Renaming a local, a parameter, a function, a class, an interface, a type alias, a type
parameter, an enum, a namespace or an imported name works** — those are resolved through
the scope chain, and the sweep covers every identifier of every program file, so the set
is complete by construction.

**A member rename works when it can be shown complete** and is refused loudly when it
cannot — and since `(API.10)` "complete" covers considerably more again. An interface
member renames across files together with **every implementor's own declaration**, every
`o["p"]` that names it (inside the quotes, leaving them alone), every
`const { p: … } = o` that destructures it, every **object-literal key** a contextual
type supplies, and both **shorthands**, each expanded the member's way.

**Since `(API.11)` a member also renames FROM ITS OWN DECLARATION NAME**, and the
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
  both this leg and `(API.10)`'s key leg — see § 9.

A **computed key** (`{ ["p"]: v }`) is a silent gap rather than a refusal: it is not in
the swept population, so it is neither renamed nor reported. tsc renames it.

### Cost, measured

On this repo's own compiler profile — tsc's 78 source files, 9,977,097 characters,
381,670 identifiers, real libs, warm:

| query | builds | wall |
|---|---|---|
| `referencesAt`, for reference | 1 | 8.4 – 8.7 s |
| `renameAt` — `createTypeChecker`, 3 edits in 2 files | 2 | **13.3 – 14.3 s** |
| `referencesAt` on `SyntaxKind` | 1 | 10.6 – 16.0 s |
| `renameAt` — `SyntaxKind`, **9,827 edits in 49 files** | 2 | **23.9 – 24.5 s** |
| a refusal decided on syntax alone | **0** | microseconds |

The second build is the verification, and it costs less than the first on a small rename
(it carries only the renamed occurrences as capture spans, against the sweep's 381,670)
and roughly as much on a large one. Budget memory as `referencesAt`'s — the sweep is the
same shape, ~1.9 GB peak on that profile, and the default 512 MB of a plain JVM is not
enough.

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

- **completion inside `o["`** — hover, go-to-definition, references and rename all
  answer an element access since `(BUG.4)` and `(API.9)`, but a caret inside a string
  literal still answers `NO_COMPLETION_CONTEXT` (§ 10a). That refusal is about the
  ANCHOR — a caret in a string is prose almost everywhere else — and lifting it means
  a position classifier, not a resolution.
- **an object literal's own METHOD** (`{ om() { … } }`) is answered by nothing: it is
  outside `(API.10)`'s key leg (which takes `{ p: v }` and the shorthands) and
  deliberately outside `(API.11)`'s owner leg, because a contextually typed literal's
  method is an occurrence of the contextual type's member and resolving it to itself
  would take it out of rename's completeness net without putting it in the group.
- **an enum member's declaration name reports no type** — it navigates and it renames,
  but `quickInfoAt` on it answers nothing rather than the member's value, because an
  enum's declared type carries no member table to ask (§ 8).
- **a computed object-literal key** (`{ ["p"]: v }`) is neither found nor renamed, and
  is not reported either: its literal is outside the swept population, and putting it
  there without resolving it would turn every such key into a rename obstacle.
- **hover on a shorthand `{ p }` describes the LOCAL** (§ 8), where tsc describes the
  contextual member for an object literal's form and the local for a binding pattern's.
  References, go-to-definition and rename answer both since `(API.10)`; only the
  one-line type summary picks a side, and it picks the one the caret means.
- **member completion after an unparsable receiver** — a `.` the parse did not
  turn into a member access answers an empty list rather than guessing a receiver
  out of bracket-balanced text.

None of these change what is documented above. The one thing that would is the
architectural inversion (`docs/ARCHITECTURE-RETHINK.md`) that makes the checker
lazy and re-entrant, at which point a query stops being a full rebuild. The
public surface here is deliberately value-typed — no AST, no `Symbol`, no `Type`
crosses it — precisely so that change can happen underneath you without breaking
your host.

---

## 14. State of the API — the two-minute version

Rounds 909–929 built this in twenty increments, and the detail is spread across as
many session notes. This section is the summary a next agent or a host author should
read instead.

### What it answers

| question | member | maturity |
|---|---|---|
| diagnostics, whole program or one file | § 4 | complete |
| in-memory edits, including files that exist nowhere but the overlay | § 5 | complete |
| offset ⟷ (line, character) | § 6 | complete, one known defect (lone `\r`, § 6) |
| what node is at this position | § 7 | complete, gated over 101 M characters of real TypeScript |
| hover | § 8 | complete for values, members and member declarations; an enum member's declaration name reports nothing |
| go to definition | § 9 | complete for free names, members, imports, `this`/`super`, object-literal keys and member declarations |
| the two above for many carets, or a whole file, in ONE compile | § 10 | complete — **this is the one an editor should use** |
| completions, members and free names, with accessibility and keywords | § 10a | complete, `o["` included; a template `` o[`p`] `` refuses |
| find references, document highlights, read-vs-write | § 10b | complete |
| signature help, every overload | § 10c | complete except tagged templates and `super(...)` |
| rename, verified by recompiling | § 10d | complete for bindings; for members, complete except the three gaps below |

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

### What it costs

Per § 3, and this is the number that shapes a host: **almost every semantic query is a
full rebuild**, because `ProjectCompiler.Result` is a flat value that retains no checker.
On tsc's own 78 sources (9,977,097 characters, 381,670 identifiers, real libs, warm):

| query | builds | wall |
|---|---|---|
| a plain rebuild, for reference | 1 | 5.5 – 5.9 s |
| `fileSemantics` / `semanticsAt`, any number of carets | 1 | rebuild + the walk |
| `quickInfoAt`, `definitionsAt`, `completionsAt`, `signatureHelpAt` | 1 **each** | rebuild each |
| `documentHighlightsAt` | 1 | 6.0 – 7.2 s |
| `referencesAt` | 1 clean, 2 dirty | 8.3 – 13.5 s, ~1.9 GB peak |
| `renameAt` | 2, 3 dirty | 13.3 – 24.5 s |
| a refusal decided on syntax alone | **0** | microseconds |

A normal application project is far smaller, and the ratios are what transfer: batching
34 carets is **34×** cheaper than asking one at a time (§ 10). So: **debounce, batch, and
wire `documentHighlightsAt` rather than `referencesAt` to caret movement.**

### The known gaps, all of them

1. **No incrementality.** Every query rebuilds; a rename builds twice. This is the one
   thing that changes the cost table, and it is the architectural inversion
   (`docs/ARCHITECTURE-RETHINK.md`), not an API item.
2. **A computed object-literal key** `{ ["p"]: v }` is outside the swept population, so
   it is neither found nor renamed *and not reported either*. tsc renames it. One of the
   two silent gaps in the list; the other is 6.
3. **An object literal's own METHOD** (`{ om() { … } }`) resolves to nothing — see § 9.
   It refuses a rename loudly.
4. **A member on an `any` receiver** cannot be placed, so it refuses a member rename.
5. **A shorthand in a literal nothing contextually types** likewise.
6. **A member named by a TEMPLATE element access** (`` o[`p`] ``) is outside the
   occurrence population, so it is neither found nor renamed *and not reported
   either* — the second silent gap, measured in round 929 (tsc counts it as a
   reference). Completion refuses that position for the same reason, which is stated
   rather than silent.
7. **An enum member's declaration name reports no type** on hover.
8. **Hover picks one subject where a span names two** — a shorthand reports the local.
   References, definition and rename answer both.
9. **`export { p }` / `import { p }` rename plainly** where tsc expands them, because
   here they are one symbol. Stated in § 10d, deliberate.
10. **No LSP layer.** This is an embedding API; the protocol, its 0-based coordinates and
    its lifecycle are a host's job. § 12 is the shape.

### How each of these was decided

Every behaviour above was **read out of tsc 7.0.2's own language server** before it was
written — `tools/tsgo-7.0.2/lib/tsc --lsp -stdio`, driven by `scripts/lsp_hover.py`,
`scripts/lsp_member_refs.py`, `scripts/lsp_rename.py` and `scripts/lsp_completion.py` —
and where this API diverges,
the divergence is stated rather than discovered. That is the standing method, and it is
the cheapest thing a next round can reuse.
