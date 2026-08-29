# The language service — how it was built, and what each round measured

The reference documentation is `docs/language-service.md`. This file is the
PROCESS behind it: the changelog of what each round changed, the measurements
that decided each design, and the roadmap items that were priced and then either
shipped or refused.

It is kept separate on purpose. The reference page answers "what can this API
do"; a reader asking that is not helped by internal work-item labels
(`(INC.44)`, `(API.9)`) or by round numbers, and the two audiences want opposite
things — one wants the current answer, the other wants to know how it moved.

**Everything here is history. For current behaviour, read the reference page.**
Where a number below has since been re-measured, the reference page is the one
that is right.

---

## Changelog — what each round changed, and what a host may have depended on

A host upgrading across these rounds needs to know which answers MOVED, which is
why this is kept rather than deleted.

**What has landed, cumulatively.** The capability summary lives in
`docs/language-service.md` § 0; this is the record of how it got there. Landed: diagnostics, in-memory edits,
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
declaration names — `(API.15)`, **an enum member's declaration name reports the
member's own type** rather than `any`, which was the last position in this surface
answering a plausible WRONG type instead of nothing — and `(API.16)`, **a member named
by a TEMPLATE element access** (`` o[`p`] ``) **is an ordinary occurrence**: found,
highlighted, hovered, renamed and completed, where before it was missed in SILENCE —
and `(API.17)`, **every literal that names a member is one population**, so a COMPUTED
object-literal key (`{ ["p"]: v }`), a quoted key (`{ "p": v }`) and a computed member
DECLARATION join it, and an object-literal key finally reports the MEMBER's type on
hover instead of the enclosing scope's. **Nothing this API answers is silent any more**:
what it cannot place, it names. Not yet: an object literal's own METHOD declaration is
deliberately left alone.
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

---

## 13. What is coming, and what would change

- **completion inside `o["`** — hover, go-to-definition, references and rename all
  answer an element access since `(BUG.4)` and `(API.9)`, but a caret inside a string
  literal still answers `NO_COMPLETION_CONTEXT` (§ 10a). That refusal is about the
  ANCHOR — a caret in a string is prose almost everywhere else — and lifting it means
  a position classifier, not a resolution.
- **an object literal's own METHOD** (`{ om() { … } }`) has no definition of its own: it
  is outside `(API.10)`'s key leg (which takes `{ p: v }` and the shorthands) and
  deliberately outside `(API.11)`'s owner leg, because a contextually typed literal's
  method is an occurrence of the contextual type's member and resolving it to itself
  would take it out of rename's completeness net without putting it in the group. Round
  930 measured what that costs a rename, and it is not uniform: with no contextual type
  the method still renames completely from either end, and with one the key becomes an
  unresolved occurrence and the rename refuses (§ 10d).
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

### (INC.12) What a query still redoes, priced

Measured on the compiler profile, warm, one process (`scripts/warm-program-cost.sh`).
A narrowed build decomposes into a FLOOR that is the same for every query and the
queried file's own checking:

| | ms | reusable when NOTHING changed? |
|---|---:|---|
| config + crawl + imports | ~12 | yes — parses are already content-cached |
| BIND, all program files | **73 – 88** | wholesale yes for an all-module program; NOT per file |
| CHECK, the ~190 program-wide `init` passes | **252 – 254** | only by reusing the `Checker` |
| the queried file's own checking | 47 at the median file, 150 on `binder.ts`, ~1,650 on `checker.ts` | never |

So **(P1) — a second query with the program unchanged — is worth the whole ~345 ms
floor**, and (INC.12) stage 1 collects the part of it that needs no compiler change:
the case where the *question* repeats. **(INC.13) then made far more questions repeat,
by asking about the BUFFER rather than the caret** — so within one buffer (P1) is
collected for hover, go-to-definition, semantics and highlights alike, and what is left
of it is a query about a DIFFERENT file, or the first query after an edit. The rest
needs the checker to become re-partitionable, which is the inversion above.

**(P2) — a query after ONE buffer changed — is worth essentially nothing today, and
that is a statement about structure rather than about effort.** Measured, it costs the
same as (P1) (`diagnosticsOf` after editing the queried file: 2,001 ms against 1,999
unedited; about another file: 498 against 505). The crawl is already 9 ms, so there is
nothing there to save; the bind cannot be redone per file (every `BinderResult` from one
`Binder` shares that binder's `(pos, end)`-keyed maps, whose keys collide across files
and are last-wins in bind order); and the ~190 program-wide passes are program-wide by
construction — round 609 measured a starved collector at 1,174 false positives. Making
(P2) cheap means making those products per-file decomposable, one at a time.


### (INC.14) Can one compiler answer many queries? Measured, then SHIPPED

The item above says the remaining 63% needs "the checker to become re-partitionable".
The question that gates that work is not the refactor but whether a `Checker` REUSED
across queries still tells the truth: `symbolTypes` persists the FIRST resolution, so
a surviving checker makes WHICH QUERY RAN FIRST observable — the mechanism that cost
three rounds in `(INC.2)`/`(INC.5)`/`(INC.6)`.

It is answerable today, with no checker surgery, because **a checker that has already
answered `k − 1` queries and is asked a `k`-th IS a checker whose partition is those
`k` files** — `recheckOnly` is a set and the spine walks it in program order either
way. `scripts/checker-reuse-differential.sh` runs the two arms and compares captured
types, captured definitions AND diagnostics, per file:

| queries per checker | one build per query | shared | ratio | rows that differ |
|---:|---:|---:|---:|---:|
| 2 | 39,173 ms / 76 builds | 21,918 ms / 38 builds | **1.79x** | 1 |
| 8 | 38,404 / 76 | 12,035 / 10 | **3.19x** | 1 |
| 26 | 39,508 / 76 | 10,347 / 3 | **3.82x** | 1 |

**One row of 741,864**, the same row in all three, and in it the SHARED arm is the
better answer — the per-query arm renders a redundant self-intersection
(`(fileName: string) => boolean & (fileName: string) => boolean`) that any sharing
removes. It is already one of the five spans `scripts/capture-equivalence.sh` gates,
so sharing introduces nothing new. No definition and no diagnostic moved.

**The one thing that census did not model was ORDER** — it walked a set of queries in
program order, where a host asks in whatever order the user touches buffers and comes
BACK to a buffer some other checker already answered. The `editor` arm closes that: a
deterministic shuffled query SEQUENCE with revisits, compared position by position,
with the COLD arm run over the same sequence so that "is the reference arm itself
order-dependent?" is a measured control rather than an assumption.

| queries per build | one build per query | shared | ratio | rows that differ |
|---:|---:|---:|---:|---:|
| 3 | 51,996 ms / 101 builds | 24,088 / 34 | **2.16x** | 0 |
| 8 | 50,771 / 101 | 13,080 / 13 | **3.88x** | 0 |
| 26 | 51,728 / 101 | 9,992 / 4 | **5.18x** | 1 |

101 queries over 76 files with 25 revisits, **1,070,012 compared rows per run**, and
`coldSelfDiverged = sharedSelfDiverged = 0` in all three — a revisited file is
answered identically by a fresh checker and by a reused one. The single k = 26 row is
byte for byte the one program order already found. So editor order introduces nothing,
and at two of three group sizes it is cleaner than program order.

**That is what `prepare` (§ 3a) turns into an API**, and it is the one place this
document's cost model changed as a result: the four caret-scoped semantic members are
now ONE build across a declared working set, not one per buffer. The `diagnosticsOf`
half needs no new call — its memo is keyed by the partition.

### (INC.17) What a re-entrant checker would buy, and the count that decides it

`prepare` (§ 3a) collects the floor for a working set the host NAMED. A query about a
file it did not name still pays the whole floor. Closing that means a checker that can
be asked about a new file without replaying its whole `init`, and the census that
decides whether that is a classification or a rewrite has been taken
(`scripts/partition-census.sh`, tsc's own 78 sources, six draws over three partition
shapes). A pass whose loops iterate `binderResults` is partition-INVARIANT by
construction; one that reaches the partition is partition-DEPENDENT and must replay:

| bucket | rows | floor ms | one-file ms |
|---|---:|---:|---:|
| partition-INVARIANT | **211** | **350.89** | 375.44 |
| partition-DEPENDENT | **205** | **15.59** | 55.05 |
| total | 416 | 366.47 | 430.49 |

**95.7% of the floor never looks at the partition**, and the replay's own fixed cost is
smaller still: 204 of the 205 dependent passes cost **0.69 ms between them**, because
201 of them read the partition exactly once — they are a single `for (result in
checkedResults)` loop. (The 205th, `checkSubsequentVarTypes`, is 14.90 ms with an EMPTY
partition, so it is a mixed pass doing program-wide work outside its loop.)

The model this produced is smaller than the one § 13's (INC.14) entry priced: nothing
has to be reset, because a program-wide pass already emitted the newly asked file's
rows during the first build — `diagnostics` is filtered to the assigned files only at
the very end.

**SUPERSEDED for the diagnostics channel — (INC.40) landed it; see § 4a, which is the
authority.** What follows is why it was held back, and it still governs the CAPTURE
channel, which (INC.41) refused. On tsc's own sources the full build's 46 diagnostics are netted by exactly
ONE pass, and all eight dashboard profiles are that same codebase — so the partition
detector this repo grades such work with compares an essentially empty population, and
a replay that produced nothing from 204 of those 205 passes would be invisible to it.
Re-arming that gate is the prerequisite, not the checker surgery.

**And reusing only the BIND is refused, with its number.** Bind is 66–74 ms of a
359–407 ms floor — 10.7% of a first hover in a mid-size buffer, 3.1% of one in
`checker.ts`, and **0** on the first query after an edit, because the program changed.
A reused checker carries its own bind, so it subsumes this entirely.

---

---

### What the round-930 audit changed

| claim as written | verdict | evidence |
|---|---|---|
| positions carry a lone-`\r` defect | **STALE** — closed in round 915, three rounds before this section was written | a `\r`-terminated fixture: TS2322 on line 3, TS1123 on line 3, `positionAt` line 3 |
| `super.p` goes to the base's declaration (§ 9's table, and the row above) | **WRONG** — it answered nothing while hover at the same caret answered correctly | fixed this round: the receiver leg had a `this` carrier and no `super` one. tsc navigates to `Base.pb`; so does this now |
| an enum member's declaration name "reports nothing" | **WRONG, and worse** — it reported `any`; **closed round 931** | four enum shapes, all `any`; tsc answers `(enum member) Plain.Alpha = 0`. `(API.15)` gave the leg its own mint; five shapes now report the member's type |
| an object literal's method "refuses a rename loudly" | **HALF WRONG** — true only where the literal is contextually typed | with no contextual type the plan carries both occurrences from either caret and the applied text compiles; with one it is `OCCURRENCES_INCOMPLETE` at the key. Found by measuring the correction — this round's own lesson, applied to itself |
| a computed key is "not reported either" | **OVERSTATED** — reported in two of its three shapes; **CLOSED round 932** | `WOULD_NOT_COMPILE` / `OCCURRENCES_INCOMPLETE` / silent-when-optional |
| hover, everywhere the audit looked | **TRUE where it looked, and it did not look at an object-literal KEY** — round 932 found every one of them reporting `any`, or the type of an unrelated same-spelled binding | `{ p: 1 }` against `interface Shape { p: number }`, beside a file-level `const p: string`, reported `string`. An audit is only as wide as its caret list |
| a template element access is silently missed | **TRUE**, proven end to end; **closed round 931** | the applied rename compiled clean with the old name still in the template. `(API.16)` widened the occurrence population to it, and the pin that asserted the silence now asserts the rewrite |
| `documentHighlightsAt` costs 6.0 – 7.2 s | **TRUE of `checker.ts`** and unqualified — 5.0 – 5.5 s on `types.ts` | the row is a statement about a FILE; the table above says so now |
| a plain rebuild is 5.5 – 5.9 s (§ 14) / ~5.2 s (§ 3) | **BOTH DRIFTED**, in opposite directions | re-taken: 5.0 – 5.5 s warm, ~9 s for the first rebuild in a process |
| everything else in this section | **TRUE** | one fixture per claim, and the pins listed above |

### How each of these was decided

Every behaviour above was **read out of tsc 7.0.2's own language server** before it was
written — `tools/tsgo-7.0.2/lib/tsc --lsp -stdio`, driven by `scripts/lsp_hover.py`,
`scripts/lsp_definition.py`, `scripts/lsp_member_refs.py`, `scripts/lsp_rename.py` and
`scripts/lsp_completion.py` — and where this API diverges,
the divergence is stated rather than discovered. That is the standing method, and it is
the cheapest thing a next round can reuse.

---

## Amendments to the state-of-the-API audit

These amended `docs/language-service.md` § 13's audit note as later rounds closed
the gaps it recorded. Kept here because they name the pins and the compiler-side
changes, which is history rather than API behaviour.

**Amended by rounds 931 and 932**, which closed gaps 6, 7 and 2 and inverted the four
pins that asserted them; the newer claims are defended by
`ProjectComputedKeyTest` and by `ProjectContextualKeyTest`. Round 932 additionally
corrected a claim this audit had passed as TRUE — hover on an object-literal key — by
the same method that found the rest: measuring it.

**Amended by round 933, one layer DOWN.** Round 932 left `ProjectComputedKeyTest`'s
fixture on OPTIONAL members because the CHECKER did not accept a backtick-quoted
computed key as supplying the member it names. That is now fixed in `Checker.kt`
(`computedLiteralKey` grew a no-substitution-template arm; `classMemberNameText` was
made to delegate to it rather than re-spell it), so all three literal key spellings —
`[2]`, `["p"]` and `` [`p`] `` — are one member name at every extraction site, in the
service and in the compiler alike. What remains open below this API is `{ [K]: v }`,
which needs the key's TYPE and is a late-binding gap, not a spelling one.