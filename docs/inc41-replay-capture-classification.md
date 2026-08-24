# (INC.41) The 43 capture-channel divergences, classified against tsc's own LSP

**Status: the valve stays SHUT. Measured, not argued.**
Commit `e9f3cd7a`, tsc's own 78 compiler sources (`build/bench/tsc-project-637d5746`),
warm, one JVM per measurement, daemons stopped first.

## 0. The question, and why the standing answer was wrong

(INC.40) wired the re-entrant replay (`Recheck.kt`'s `ProgramRecheck`) into
`Project.diagnosticsOf` behind a **type-level** valve — `DiagnosticsOnlyRecheck`,
which cannot express a `TypeCaptureRequest`. The reason recorded in
`docs/language-service.md` § 4a was that `scripts/replay-differential.sh` reads
`0 DIVERGE-DIAG, 0 DIVERGE-DEF, 43 DIVERGE-TYPE of 75 files`, and that those 43 are
"overwhelmingly the union-alias display family (INC.26)/(INC.27), **in which the
fresh arm is not automatically the correct one**."

That last clause was a plausible inference from (INC.26) — where an alias renaming a
type that already had its own name was a defect of the ORDINARY build — and it was
never tested. This round tested it. **It is false for this population.** Where the
two arms disagree, the arm that ships today (fresh) matches tsc far more often than
the replay does, and the replay's characteristic mistake is *the very defect
(INC.26) is named for*, one type-former up: an alias renaming a **union** that the
reference site spelled out.

## 1. Method

Two additions, both committed:

* `Inc41ClassifyMain` — the same two arms as `ReplayDifferentialMain` (same seed,
  the same spans handed to both arms, so the arms cannot differ in what they were
  *asked*), but it dumps **every** diverging row with the span, its project-relative
  file, its line/character and the source text, instead of five truncated examples
  per file. The gate's shape is right for a gate and useless for "which arm is
  right".
* `scripts/inc41_classify.py` — reduces the rows the way (INC.23) requires. Not by
  counting rows: a divergence nested inside a 400-character signature fragments into
  one row per parameter, and (INC.23) measured a substring heuristic over-reporting a
  168-row population at **100%**. It diffs the two renderings into their minimal
  differing **elements** (a token-level `SequenceMatcher`) and counts DISTINCT
  `(fresh, replay)` element pairs.

Ground truth is **read out of tsc**, never hand-written (CLAUDE.md's rule, and round
924's two wrong predictions). `scripts/lsp_hover_project.py` is a new client for
`tools/tsgo-7.0.2/lib/tsc --lsp -stdio` that opens the project's EXISTING files by
path rather than materialising a fixture, so it can be pointed at the bench profile.
Every one of the 37 distinct element pairs was sampled — one caret each, the first
row carrying that pair — so **the sample covers 100% of the 796 rows by cause**,
not by row count.

*Caveat, stated because the repo requires it*: `tools/tsgo-7.0.2/lib/tsc` diverges
from pristine tsc in places, and where a corpus baseline exists it outranks a tsgo
measurement (round 938). For a hover/naming question its LSP is the best instrument
on this box, and several verdicts below are additionally corroborated by reading the
**declaration** out of the profile's own sources.

### A trap this round paid for

The profile's tsc sources are **CRLF**. Python's default universal-newline
translation collapses `\r\n` and shifts every offset, so a caret file built from the
compiler's own `(start, end)` lands in the wrong place — silently, on a plausible
identifier. Both new scripts read with `newline=""`.

## 2. The population

```
compared   = 373,879 captured type spans over 75 target files
divergent  =     796 spans  (0.213%)  in 43 files (41 distinct basenames —
                                       tsc has THREE `utilities.ts`)
distinct element pairs = 37
rows whose divergence is more than one element = 192
```

37 causes for 796 rows. That ratio is the whole reason (INC.23)'s rule exists.

## 3. The family table

Verdict is **per row**, taking the worst element in the row.

| verdict | rows | files | share |
|---|---|---|---|
| **REPLAY WORSE** | **413** | 36 | 51.9% |
| BOTH WRONG | 375 | 17 | 47.1% |
| REPLAY BETTER | 8 | 4 | 1.0% |
| REPLAY EQUIVALENT | 0 | — | — |

The causes, largest first (rows; the full 37 are in the classifier's output):

| pair | rows | verdict | fresh → replay | tsc 7.0.2 says |
|---|---|---|---|---|
| p000 | 213 | BOTH WRONG | `(node: TIn) => any` → `(node: TIn) => T \| readonly Node[]` | `Visitor` |
| p001 | 92 | REPLAY WORSE | `ObjectLiteralExpression \| ArrayLiteralExpression` → `AssignmentPattern` | the expansion — **the source writes it** |
| p002 | 76 | REPLAY WORSE | `Identifier \| PrivateIdentifier` → `MemberName` | the expansion — **the source writes it** |
| p003 | 74 | BOTH WRONG | `ModuleName` → `ModuleExportName` | `StringLiteral` (tsc narrowed) |
| p004 | 62 | BOTH WRONG | `ImportAttributeName` → `ModuleExportName` | `StringLiteral` (tsc narrowed) |
| p005 | 40 | REPLAY WORSE | `BindingOrAssignmentPattern` → `DestructuringPattern` | `BindingOrAssignmentPattern` |
| p006 | 34 | REPLAY WORSE | `ImportDeclaration \| ImportEqualsDeclaration \| ExportDeclaration` → `AnyImportOrReExport` | the expansion |
| p007 | 23 | REPLAY WORSE | 3-member expansion → `IsObjectLiteralOrClassExpressionMethodOrAccessor` | `MethodDeclaration \| AccessorDeclaration` |
| p008 | 21 | REPLAY WORSE | 3-member expansion → `JsxCallLike` | `JsxOpeningFragment \| JsxOpeningLikeElement` |
| p009 | 17 | BOTH WRONG | 3-member expansion → `JsxCallLike` | `JsxOpeningElement` (tsc narrowed) |
| p013 | 10 | REPLAY WORSE | `Connection[][]` → `any[][]` | `Connection[][]` — a LOST RESOLUTION |
| p015 | 8 | **REPLAY BETTER** | `SyntaxKind.SingleLine… \| SyntaxKind.MultiLine…` → `CommentKind` | `CommentKind` |
| p022/24/25 | 13 | REPLAY WORSE | `Map<string, SeenPackageName>` → `Map<any, any>` | `Map<string, SeenPackageName>` — LOST |
| p032 | 2 | REPLAY WORSE | `(key: K, valueInNewMap: U) => T` → `… => any` | `FileWatcher` — LOST |
| p033 | 2 | REPLAY WORSE | `Intl.LocalesArgument` → the expansion | `Intl.LocalesArgument` (direction REVERSED) |

Sixteen further ALIAS-vs-EXPANSION causes (3–14 rows each: `EntityNameExpression`,
`AccessExpression`, `TemplateLiteralToken`, `ClassLikeDeclaration`,
`FunctionOrConstructorTypeNode`, `ImportOrExportSpecifier`, `AssertionExpression`,
`BindingElementGrandparent`, `AnyImportSyntax`, `TypeReferenceType`, `HasDecorators`,
`IsFunctionExpression`, …) all read the same way: **tsc renders the expansion, the
fresh arm renders the expansion, the replay attaches an alias name.**

## 4. The two mechanisms, and which arm owns each

### 4a. The alias-display race — 393 of the 413 REPLAY-WORSE rows

`aliasDisplayMap` is **id-keyed and first-wins**, and round 545's INV.5(a) interns a
union by its member-id list **alone**. So an alias name, once registered, renames
that interned union *everywhere*, whatever the reference site spelled. Confirmed
against the profile's own sources:

```ts
// utilitiesPublic.ts:857 — the source spells the union out
export function idText(identifierOrPrivateName: Identifier | PrivateIdentifier): string
// types.ts:1746 — and a synonymous alias exists elsewhere
export type MemberName = Identifier | PrivateIdentifier;
```

tsc hovers `Identifier | PrivateIdentifier`. The replay hovers `MemberName`. That is
(INC.26)'s defect — *an alias must not rename a type that already has its own name* —
with a union in place of an interface.

**Why the REPLAY has more of it, and the fresh arm fewer.** The two arms differ only
in how much resolution has already happened when the capture is recorded. The fresh
arm is a narrowed build that resolved essentially only the queried file, so few
aliases are registered and most unions render structurally. The replay carries one
seed build **plus every earlier recheck** in the session, so far more alias
declarations have been resolved and far more interned unions have had a name stamped
on them. The replay is therefore not a *different* defect — it is **more of the same
defect**, and it gets monotonically worse the longer the session runs. That is the
part with real consequences for an editor: today's answer is stable for a given
query; the replay's answer would depend on what the user looked at earlier.

This is **not** fixable by a rule inside `aliasDisplayMap`, and (INC.27) already
proved why with the union key: no id-keyed or member-set-keyed table can give tsc's
several answers from one key, because tsc keys a union by `getTypeListId(types) +
getAliasId(...)` and therefore has several *instances* where we have one. (INC.27)
also measured the tempting mitigation — refuse to name a member set that two
differently-named aliases claim — at **worse** (1,128 → 1,351 spans), because its own
trigger is coverage-dependent.

### 4b. Lost resolutions to `any` — 20 rows, 3 files

`debug.ts` (`Connection[][]` → `any[][]`), `program.ts` (`Map<string,
SeenPackageName>` → `Map<any, any>`) and `tsbuildPublic.ts` (a type parameter `T` →
`any`). tsc confirms the fresh arm in all three. These are not display choices; they
are a resolution the replay does not have. They are also the family the arc has been
fighting since (INC.19)/(INC.23) — *silent, plausible, never an error*.

## 5. The prize, measured before the recommendation

`Inc41HoverPriceMain` asks **both arms for the same single caret** — the identifier
nearest each file's midpoint, a position-independent choice — over 40 target files,
4 ABBA rotations, 6 warm-up rebuilds, one JVM, daemons stopped. Vacuity control:
both arms captured a type at **160 of 160** carets.

```
arming (the seed build the handle comes from) : median 188 ms
ONE hover, fresh narrowed build               : median 121 ms   p90 234   n=160
ONE hover, replay re-entry                    : median  33 ms   p90 143   n=160
ratio 3.67x    saving 88 ms
```

**But name the row.** `quickInfoAt` memoises per BUFFER (a second caret in the same
file is already ~2 – 4 ms) and **any edit drops the handle**. So the 88 ms is bought
exactly once per *(file, program state)* pair, i.e. on

> the first hover in a file, at a program state some earlier query already built for,
> with no edit since.

Navigating by go-to-definition through several files while reading hits it. The
type-hover-type loop does not: after a keystroke, both arms rebuild. And the two
queries a user hits constantly — `completionsAt` (194 – 202 ms) and `signatureHelpAt`
(190 – 214 ms) — do not benefit at all here, because (INC.32) defect 1 is that they
call `captureIn` directly and cannot reach a prepared check **at all**. That is a
wiring bug worth more than this, and it is cheaper.

## 6. The verdict

**413 rows in 36 files would show the user a worse answer than today's** — a wrong
alias name in 393 of them and an outright `any` in 20 — against **8** that would
improve. Per span that is 413 / 373,879 = **0.11%**; the bar this arc has actually
enforced is (INC.2), which refused capture narrowing over **45** divergent spans of
381,666 (0.012%). 413 is nine times that bar, in the same direction, with the same
silent failure mode.

So (INC.41) is **not** a small fix, and it is **not** already closed:

* the 393-row half is (INC.27)'s interning-key question — REFUSED WITH A PROOF, and
  any attempt to close it by making the replay match the fresh arm is a (INC.26)
  trap: the gate's target is *neither arm having a wrong name to disagree about*;
* the 20-row half is a genuine lost resolution and is the only part that is a bug in
  the replay itself;
* the 375 BOTH-WRONG rows are a **separate, pre-existing, ordinary-build** defect
  worth its own item: 213 of them are `Visitor`/`VisitResult<T>`, where the shipped
  build renders `(node: TIn) => any` and tsc renders `Visitor`.

**Recommendation: REFUSE for now.** Opening the valve buys 88 ms on a row a user
meets occasionally and costs a wrong hover in 36 of 43 files, worsening with session
length. Two things would change the answer and both are worth more than the valve on
their own merits:

1. **(INC.32) defect 1** — wire `completionsAt`/`signatureHelpAt` to `prepared`.
   ~200 ms per keystroke-adjacent query, no correctness question at all.
2. **The 20 lost resolutions.** If they close, the replay's remaining divergence is
   purely the alias-naming race — at which point the honest framing is a
   **logical-parity conversation for the owner**: is a *differently-named-but-equal*
   type in a hover a form divergence one may trade 3.67x for? This round's answer is
   that it is not merely a naming difference — tsc renders the expansion and we would
   render a name the user's own declaration did not write — but that is a call for
   the owner, not for a round.

## 7. What grades a future attempt

* `scripts/replay-differential.sh realism` — the gate; today `43 of 75`, `0
  DIVERGE-DIAG`, `0 DIVERGE-DEF`.
* `Inc41ClassifyMain` + `scripts/inc41_classify.py` — the classification; today
  796 rows / 37 pairs / 413 REPLAY-WORSE. A change is an improvement only if the
  REPLAY-WORSE row count falls, and (INC.23)'s rule means the count that matters is
  the **element-pair** one, not the row one.
* `scripts/lsp_hover_project.py` — the oracle. Re-derive verdicts; do not inherit
  this page's table without re-asking (round 930: a doc section that summarises
  behaviour decays at about one false claim per three rounds, which is exactly how
  the "the fresh arm is not automatically right" clause survived).
* `scripts/capture-equivalence.sh` / `capture-channel-equivalence.sh` — the
  full-vs-narrow differentials, which are blind to anything both arms get wrong.
* The corpus — any change to union display touches ~13k pinned baselines.
