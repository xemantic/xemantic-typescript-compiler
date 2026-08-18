# PLAN-PHASE-5 — Self-compile the TypeScript compiler, then performance

Owner directive (2026-07-03, re-scoping the 2026-07-02 *"fully compile any TypeScript
project"*): **fully compile the TypeScript compiler itself, then optimize
performance.** "Any TypeScript project" is the post-v1 horizon.

**v1 definition of done:** all 8 tsc-source profiles (compiler / tsc-cli / jsTyping /
deprecatedCompat / typingsInstallerCore / services / server / harness) at **zero false
positives**, all files emitted, zero crashes/hangs/OOMs — verifiable fully offline.
Byte-correct emit diffing against real tsc is the network-gated follow-up (needs
node + typescript installed). Then M5 (performance) completes the directive. Items
that do not block v1 (M2.4, M3.0, M3.5, all of M4) are parked in § "Post-v1 backlog"
near the bottom of this file — the top-to-bottom loop skips them until v1 lands.

This file is the **live queue** for Phase 17. `docs/history/PLAN-PHASE-4.md` (Phase 16 and earlier)
is archived state — its "Known architectural blockers" section remains the reference
material for the M3 items below; do not work its queue.

## Phase 17 — Self-compile the TypeScript compiler (M0–M5)

(Live session notes accumulate here, most recent first — same convention as Phase 16.)

**Round 929 (2026-08-18) — (API.12): COMPLETION INSIDE `o["`. THE LAST QUERY THAT DID
NOT ANSWER AN ELEMENT ACCESS, AND THE ROUND'S PRODUCT IS THAT **THE PARSER'S OWN
`isUnterminated` IS FALSE FOR THE ONE STATE THIS FEATURE EXISTS FOR** — a lone opening
quote — so the classifier that reads it has to check the ARITHMETIC as well as the flag.**

- **STEP 1 WAS tsc ITSELF, 21 carets over three fixtures** (`scripts/lsp_completion.py`,
  new; it reuses `lsp_rename.py`'s client over `tools/tsgo-7.0.2/lib/tsc --lsp -stdio`).
  Every design decision below is a row of this table rather than a prediction:

| caret | tsc 7.0.2 | ours BEFORE | ours AFTER |
|---|---|---|---|
| `o["‸"]`, nothing typed | 4 items, labels UNQUOTED, edit `[441,441)` | NONE | 4 items, MEMBER |
| `o["al‸pha"]` | 4 items, edit `[461,466)` — **the TEXT, quotes excluded** | NONE | the same |
| `o["alpha‸"]` at the text's end | 4 items, edit over the whole text | NONE | the same |
| `o['‸']` single quotes | 4 items | NONE | 4 items |
| `` o[`‸`] `` TEMPLATE | 4 items | NONE | **NONE — deliberate** |
| `o["‸"]` where `o` is `any` | **0 items** | NONE | 0 items, and NOT a refusal |
| a NUMERIC index signature | only the named member | NONE | only the named member |
| a STRING index signature | only the named member | NONE | only the named member |
| an enum `E["‸"]` | its 2 members, kind `EnumMember` | NONE | its 2 members |
| `this["‸"]` in a method | 3 items, the `private` one included | NONE | the same 3 |
| **UNTERMINATED `o["‸`** before a newline | 2 items, **and NO textEdit at all** | NONE | 2 items, span to the token's end |
| **UNTERMINATED `o["‸`** at end of file | 2 items, no textEdit | **FREE_NAME — the whole scope** | 2 items |
| `o["alpha"‸]` past the closing quote | **free names** (1,074) | FREE_NAME | FREE_NAME |
| `o[‸]`, no quotes at all | free names | FREE_NAME | FREE_NAME |
| `o[‸"alpha"]`, before the opening quote | **free names** | NONE | NONE — stated |
| a plain `"alpha"` that is no member name | **null result** | NONE | NONE |
| an object-literal key `{ "‸": 1 }` | **null result** | NONE | NONE |
| an indexed-access TYPE `Bag["‸"]` | **free names**, not members | NONE | NONE — stated |
| `w("‸")` where `w` takes `keyof Bag` | the 2 keys, from the CONTEXTUAL type | NONE | NONE — a stated gap |

- **THE ITEM SAID "AN ANCHOR QUESTION, ONE CLASSIFIER" AND THAT IS EXACTLY WHAT IT WAS:
  the member enumeration is round 917's, UNCHANGED, and there is no core change at all.**
  `Project.completionsAt` drives the member half entirely from `CompletionAnchor.receiver`,
  so making the anchor answer `MEMBER` with the element access's `expression` buys the
  union rule, the accessibility filter, the `this` leg and the export-table leg for free —
  which is why the enum, the `any` receiver, the imported interface and the `private`
  member all came back right on the first run. The classifier is one function
  (`SourceIndex.stringMemberAnchorAt`) plus one enumeration
  (`SyntaxRoles.stringElementAccessAt`), and the enumeration is **deliberately (API.9)'s
  own walk** — "a string literal is a member name only in an element-access position" is
  now ONE predicate serving both the occurrence sweep and the anchor, so completion and
  rename cannot drift apart about what a member name is.
- **THE ROUND'S PRODUCT, AND IT IS A TRAP FOR ANY READER OF `StringLiteralNode`:
  `isUnterminated` IS FALSE FOR A LONE `"`.** `Parser.kt` decides it as
  `startsWithQuote && raw.last() != quote`, and for a one-character raw text the first
  character IS the last — so `bag["` at end of file parses as a TERMINATED empty string.
  That is precisely the state a completion request is normally made in, and before this
  round it answered `FREE_NAME`: the caret sits one past a one-character token, contained
  by nothing, and the whole lexical scope (1,000+ lib names) was offered INSIDE the
  string. The anchor therefore reads `isUnterminated || tokenEnd - start < 2` — arithmetic,
  not a character test, since a closed literal needs an opening and a closing quote. Arm
  A4 is exactly that term and reddens exactly the end-of-file pins.
- **THE SPAN IS THE TEXT, QUOTES EXCLUDED — round 926's rule, one query over**, and it is
  tsc's measured edit range. Accepting an item leaves exactly one pair of quotes, which is
  asserted by APPLYING the edit and recompiling (round 925's shape) rather than by reading
  the arithmetic back: a span that eats a quote writes `bag[has space]`, which does not
  parse. Since it is the same span a member rename writes into, completing a name and then
  renaming it edit the same characters.
- **A MEMBER WHOSE SPELLING IS NOT AN IDENTIFIER (`"has space"`, `"1abc"`) IS OFFERED, AND
  IT NEEDED NOTHING**: the member capture excludes only the empty and `__`-prefixed
  spellings; `typeCaptureIsWritableName`, which would have filtered them, is the FREE-NAME
  leg's and was never on this path.
- **ONE REFUSAL IS THIS ROUND'S OWN CHOICE AND IS THE (API.11) PRECEDENT APPLIED**: a
  TEMPLATE element access, which tsc completes. (API.9)'s occurrence population is string
  literals only, so a member written through a template is one a later rename cannot find
  — offering it would invite text this API cannot maintain. **And measuring that refusal
  found a SILENT GAP one layer down: tsc counts `` o[`p`] `` as a reference** (4 spans on a
  4-occurrence fixture), so our references and rename miss it and do not say so. Recorded
  as § 14's gap 6; the old gap 6 was this round's own item.
- **NINE-ARM ABLATION** (`scripts/round929-ablate.py`), one mistake at a time, anchored
  replacements with asserted occurrence counts, restored from a sha256-verified on-disk
  snapshot, with a per-arm POSITIVE RUN CONTROL (506 `-project` tests must have run).

| arm | the mistake | red | what it uniquely shows |
|---|---|---|---|
| A1 classifier-off | the anchor answers nothing for a string caret | **18** | the pre-929 boundary |
| A2 quote-span | the span starts at the QUOTE, not at the text | **10** | THE QUOTES — the accepted item writes `bag[alpha"]` |
| A3 position-blind | the lookup ignores WHICH literal the caret is in | **10** | the plain-string boundary; differs from A2 by that pin and by the anchor-only pins |
| A4 no-length-arithmetic | only the parser's `isUnterminated` is believed | 2 | the lone-quote defect, i.e. `o["` at end of file |
| A5 no-caret-at-token-end | only a caret CONTAINED by a token is considered | 4 | every unterminated shape; A4 ⊂ A5 |
| A6 past-closed-quote | a caret past a CLOSED literal's quote is admitted | **0** | MEASURED-REDUNDANT — see below |
| A7 opening-quote | the caret AT the opening quote is admitted | **0** | MEASURED-REDUNDANT, an exactly equivalent later guard |
| A8 token-kind | the caret's token KIND stops being consulted | **0** | MEASURED-REDUNDANT — a cost guard, not a correctness one |
| A9 REACH CONTROL | A6's guard AND the span's upper bound, together | 1 | that A6's line is on that caret's path and its pin is not vacuous |

  **Five distinct non-empty sets, with A1 ⊃ everything and A4 ⊂ A5.** The three zero arms
  are REACHED and not dead, proved by other arms rather than by new instrumentation (round
  928's mechanism): all three lines are strictly UPSTREAM of A2's edited line in the same
  function, and A2 reddens 10 tests, so control passes them. Each is redundant for a
  DIFFERENT stated reason — A7's condition is term-for-term identical to the span's lower
  bound, A8's decision is made again by the node-kind requirement one level down, and A6
  is the DUAL of the span's upper bound (arm A9b, run separately, is also 0 red, so either
  guard alone answers that caret). A9 is deliberately TWO mistakes and is credited to no
  pin (round 807): it exists only to show the pin is real.
- **A ZERO ARM WAS A MISSING PIN, NOT A REDUNDANT GUARD — round 902's trap, hit and
  fixed.** A6 read 0 red on its first pass with a plausible story ready; the truth is that
  `o["alpha"‸]` never reaches A6's line at all, because `]` begins a token AT the caret and
  the token-kind test refuses first. The rule is reachable only when NO token begins there
  — `o["alpha"‸ ]`, with a space — which is a pin this file did not have. Added, and A9
  then reddens it.
- **GATES.** Suite **14,955 -> 14,981 / 0 failures / 0 errors / 3 skipped = exactly the
  +26** (`-project` 480 -> 506, core UNCHANGED at 14,341). `cost_gate.py` **+0.00% on all
  20 counters** — a CONTROL here rather than a gate, and structurally so: the round adds no
  core code. `huge_methods.py --fail-over 0` clean on core and on `-project` explicitly.
  The round-920 token gate re-run because `SourceIndex` changed. `spine_closure_audit.py`
  not applicable. Warning-clean.
- **PINS +26**: 10 parse-only anchor pins in `CompletionAnchorTest` and 16 end-to-end in
  the new `ProjectStringMemberCompletionTest`. **THE DISCRIMINATOR** is round 917's own,
  reused: a receiver whose members are spelled exactly like unrelated top-level bindings,
  asserted as an EXACT list — the wrong answer here is the file's whole scope, a SUPERSET
  that contains the right names. Two pins are REGRESSION pins rather than discriminators
  (a caret past the closing quote must stay a free-name caret) and say so in place.
- **SUCCESSOR, ranked, and unchanged from round 928 bar one item now closed.**
  (1) **The incremental / re-entrant seam** — every query is a full rebuild (5.5-5.9 s warm
  on tsc's own sources) and a rename is two; § 14's cost table is the case for it, and it
  is the architecture inversion rather than an API item, which is why it needs the owner.
  (2) **A template element access in the occurrence population** — newly measured as a
  SILENT gap in references and rename, and the reason completion refuses that position;
  small, but it needs the checker to resolve `` o[`p`] `` as a member access first.
  (3) An **LSP protocol layer**, which the owner deferred.

**Round 928 (2026-08-18) — (API.11): A MEMBER DECLARATION NAME RESOLVES TO ITS OWN SYMBOL.
THE ROUND'S PRODUCT IS THAT **"ITS OWN SYMBOL" IS NOT A `Symbol` HERE** — this compiler's
interface merge is LAST-WINS for a same-named member, so the merged symbol's declaration
list is SHORT, and the whole list has to be reconstructed one level up, from the OWNER.**

- **STEP 1 WAS tsc ITSELF, 22 carets over two fixtures** (`scripts/lsp_member_refs.py` plus a
  definition+hover driver over `tools/tsgo-7.0.2/lib/tsc --lsp -stdio`). References /
  definitions / hover at every member DECLARATION kind, beside the same measurement on this
  compiler — so the whole round is a diff of two measured tables, never a prediction:

| caret (a member's own declaration name) | tsc refs / defs | ours BEFORE | ours AFTER |
|---|---|---|---|
| `interface Shape { p }`, used | 5 / 1 | 5 / **0** | 5 / 1 |
| `both` declared in TWO merged `interface Merged` blocks | 3 / 2 | **0 / 0** and **2 / 0** | 3 / 2 from EITHER |
| a member declared and NEVER used | 1 / 1 | **0 / 0** | 1 / 1 |
| an OVERLOAD set, from any of its 3 signatures | 4 / 3 | **2 / 0** | 4 / 3 |
| a getter and its setter | 4 / 2 | **3 / 0** | 4 / 2 |
| a class property implementing an interface | 5 / 1 | 5 / 1 | 5 / 1 |
| a static, a `#private`, a method, a type-literal member, an enum member | 2 / 1 | 2 / **0** | 2 / 1 |
| an object literal's METHOD | 2 / 1 | 2 / **0** | 2 / 0 — deliberately |
| HOVER, every one of the eighteen | a real type | **`any` everywhere** | the member's type (enum member excepted) |

- **THE MECHANISM IS A FOURTH RESOLUTION ROUTE, AND IT IS THE RECEIVER'S EXACT DUAL.** A free
  name goes through the SCOPE CHAIN, a member use through its RECEIVER, an object-literal key
  through its CONTEXTUAL type; a member's own declaration name has none of the three and does
  have the class / interface / type literal / enum it is declared **in**, so that OWNER is
  asked (`Checker.typeCaptureMemberDeclarations`, one function, plus
  `typeCaptureOwnerSymbol` and `typeCaptureMemberNameIdentifier`).
- **THE HAZARD THE ITEM NAMED IS REAL AND IS *BIGGER* THAN IT LOOKED.** "Resolve it to itself"
  is not enough, and neither is "resolve it to its `Symbol` and take `Symbol.declarations`":
  **round 884's `mergeSingleSymbol` ADOPTS**, so a member declared in two merged `interface`
  blocks ends up as one symbol carrying only the SECOND block's declaration — measured, a
  caret on the FIRST answered `[decl2, decl1]` and a caret on the SECOND answered `[decl2]`.
  tsc accumulates. So the declaration list is reconstructed from the OWNER symbol's OWN
  declarations, each of which is a container that may hold a member of this name, which is
  where the information survives. Arms A2 and A3 separate the two failures.
- **WHY THAT MATTERS BEYOND TIDINESS**: making a declaration name resolve REMOVES it from the
  rename completeness net, and the net's real quarry is precisely a merged declaration the
  group missed. A leg that resolved to itself would have turned round 927's loud refusal into
  a SILENTLY SHORT rename.
- **ONE DELIBERATE EXCLUSION, IN THE CONSERVATIVE DIRECTION**: an OBJECT LITERAL's own member
  is left to (API.10)'s key leg and to what preceded it. A contextually typed literal's method
  is an occurrence of the CONTEXTUAL type's member, and (API.10) covers `PropertyAssignment`
  and the shorthands but not a method; resolving one to itself would take it out of the net
  without putting it in the group. tsc answers it — stated divergence, arm A5.
- **HOVER CAME ALONG FOR ~20 LINES AND IT WAS (BUG.4) ONE POSITION OVER**: a member declaration
  name was asked as a FREE name, so it read `any`, or the type of whatever unrelated binding
  shared the spelling. `typeCaptureMemberDeclarationType` asks the owner's type instead. An
  enum member is the one kind still unanswered (an enum's declared type carries no member
  table), and an overload set reports the whole overloaded type rather than the signature
  under the caret — coarser than tsc, never wrong.
- **PINS +16** (`-project` 464 -> 480, core UNCHANGED at 14,341; suite **14,939 -> 14,955 / 0
  failures / 3 skipped**), all in the new `ProjectMemberDeclarationTest`, plus **TWO EXISTING
  PINS CHANGED MEANING** and each says so in place: `ProjectDefinitionTest`'s "a caret on an
  interface member's own declaration name answers EMPTY" (round 913) is now "answers ITSELF",
  and `ProjectReferenceTest`'s "a member declared and never used answers EMPTY — the stated
  limit" (round 925) is now "answers itself". Four rename pins are apply-and-recheck (round
  925's strongest shape) and every positive assertion is on SPANS or on resulting TEXT, never
  on a count.
- **NINE-ARM ABLATION**, one mistake at a time, anchored replacements with asserted occurrence
  counts, restored from a sha256-verified on-disk snapshot (`scripts/round928-ablate.py`), and
  a POSITIVE RUN CONTROL per arm (480 tests must have run — round 808's empty-results trap,
  which the first pass hit because Gradle writes its failure summary to STDERR and the driver
  read only stdout, reporting seven real results as "BUILD PROBLEM").

| arm | the mistake | red | what it uniquely shows |
|---|---|---|---|
| A1 leg-off | the whole leg returns null | **24** | the pre-928 boundary, (API.9)'s heritage tie included |
| A2 own-only | THE NAIVE FIX — a declaration name resolves to ITSELF | 8 | merged / overload / accessor lose their siblings, in defs AND rename |
| A3 no-merged-containers | the owner SYMBOL's other declarations stop being containers | 3 | the merged half alone — A2 minus overloads and accessors |
| A4 no-owner-identity-check | any same-named owner elsewhere contributes members | **0** | MEASURED-REDUNDANT, see below |
| A5 objlit-not-excluded | an object literal's own member joins the leg | 1 | the (API.10) boundary this leg must not cross |
| A6 no-enum-member | an enum's member stops being a declaration name | 1 | the enum arm |
| A7 no-hover-leg | the declaration name is asked as a free name again | 1 | (BUG.4) one position over |
| A8 no-heritage-related | the (API.9) heritage tie is dropped | 9 | that this round did not break the previous one |
| A9 own-not-added | the caret's own declaration is not force-added | **0** | MEASURED-REDUNDANT, see below |

  **Seven distinct non-empty sets; A2 ⊃ A3 and A1 ⊃ everything, which is what a boundary arm
  is for.** The two zero arms are REACHED and not dead, and the proof is another arm rather
  than a counter (round 902's trap answered without new instrumentation): **A3 mutates the
  BODY of the `if` whose CONDITION A4 weakens**, and A3 reddens 3 tests, so the condition is
  evaluated with both terms true — the identity term is simply never the deciding one on any
  shape I could build, because `spineScopeLookup` consults the INV.2(c) scope space first and
  therefore finds the INNERMOST owner (the fixture's block-scoped `interface Shape` resolves
  to itself, not to the imported one). **A2 empties the loop whose result A9 backstops**, and
  the affected pins then observe exactly ONE location, which can only be the one A9's line
  adds — so that line executes, and it is redundant because the parser puts every member of a
  container in that container's own `members` list. Both are recorded as redundant GUARDS
  rather than claimed as pins (round 807's rule).
- **GATES.** `cost_gate.py` **+0.00% on all 20 counters** — OFF IS FREE, and a real gate here
  rather than a control, since the round adds core code on the capture path.
  `huge_methods.py --fail-over 0` clean on core (750 classes, 16,013 methods, 0 over) and on
  `-project` explicitly (48 classes, 458 methods, 0 over). `spine_closure_audit.py` not
  applicable (no `spine*EnterNode` changed); the round-920 token gate not applicable
  (`SourceIndex` and the parser untouched, which is also why the swept population is
  unmoved at 381,672 on tsc's own sources). Warning-clean.
- **WHAT IS STILL REFUSED**, and it is now three small things: a member on an `any` receiver
  (by `o.p` or `o["p"]`), a shorthand in a literal nothing contextually types, and an object
  literal's own METHOD. A computed key `{ ["p"]: v }` remains the one SILENT gap.
- **AND ONE PAGE THAT DID NOT EXIST**: `docs/language-service.md` § 14, **State of the API —
  the two-minute version**: what it answers with a maturity column, the one rule behind every
  refusal (*prove to offer*), the measured cost table, and all eleven known gaps in one list.
  Rounds 909-928 are nineteen increments spread over as many session notes; this is the page
  to read instead.
- **SUCCESSOR, ranked.** (1) **The incremental / re-entrant seam** — it is now the largest
  thing about this API by a wide margin: every query is a full rebuild (5.5-5.9 s warm on
  tsc's own sources) and a rename is two, and § 14's cost table is the case for it. It is the
  architecture inversion (`docs/ARCHITECTURE-RETHINK.md`) rather than an API item, which is
  exactly why it needs the owner to choose it. (2) **Completion inside `o["`** — an ANCHOR
  question, one classifier, and the last query that does not answer an element access.
  (3) An **LSP protocol layer**, which the owner deferred. **I would put (1) to the owner and
  take (2) meanwhile**: the feature surface is done enough that another feature is worth less
  than making the ones there cheap.

**Round 927 (2026-08-18) — (API.10): ONE SPAN, TWO SYMBOLS. THE LAST OF ROUND 922'S FIVE
REFUSALS IS CLOSED, AND THE ROUND'S PRODUCT IS THAT **THE CAPTURE FILING ONE ANSWER PER SPAN
WAS NEVER THE OBSTACLE** — tsc's own relation between a shorthand's two symbols is
ASYMMETRIC, so what was missing was a ROLE, not a second answer.**

- **STEP 1 WAS tsc ITSELF AND IT DECIDED THE WHOLE DESIGN.** `scripts/lsp_rename.py` +
  `lsp_member_refs.py` over `tools/tsgo-7.0.2/lib/tsc --lsp -stdio`, 32 carets in two files,
  references / rename / definition / hover at each. (A one-line fix to `lsp_rename.py`: its
  `main()` was unguarded, so IMPORTING it — which `lsp_member_refs.py` does — ran the rename
  driver instead.) What was READ rather than reasoned:

| caret | tsc 7.0.2 |
|---|---|
| `{ p: v }` in a call ARGUMENT / an ANNOTATION / a `return` / a NESTED key / an ARRAY element / `satisfies` / `as` / a TERNARY branch / a parameter DEFAULT / a class-PROPERTY initializer / an arrow's EXPRESSION body | **all eleven** in the member's group |
| the same key, caret ON it | the UNION of TWO groups — the contextual member's AND the literal's OWN property's, so an `o.p` reading the literal is in the answer while the member's own group does NOT contain it |
| `{ p: v }` with NO contextual type | the literal's own property alone; go-to-definition answers the KEY ITSELF |
| `takesGeneric<Shape>({ p })` vs `takesGeneric({ p })` assigned to `Shape` | **in** the group / **NOT** in it — the inferred parameter is a naked `T` and names no member |
| `{ ["p"]: v }` | in the group, quotes excluded from the edit; `{ [K]: v }` is the const `K` |
| `{ p }` and `const { p } = o` | **ONE span, TWO symbols, and the relation is NOT symmetric**: the MEMBER's group CONTAINS the token, a caret ON the token answers the LOCAL's group and nothing else (2 spans) |
| renaming each side of a shorthand | `{ renamed: p }` from the member, `{ p: renamed }` from the local — both compile |
| definition on a shorthand | **TWO locations** for an object literal's (local, member); the member alone for a binding pattern's |

- **THE MECHANISM: THREE DECLARATION SETS, EACH READ BY A DIFFERENT QUESTION.** A capture
  still files ONE `CapturedDefinition` per span; what it gained is a third field, and the
  three now differ in exactly which of NAVIGATION / SEED / MEMBERSHIP they carry.
  `locations` is all three — what the caret MEANS. `related` (round 926's heritage edge) is
  SEED + MEMBERSHIP, and (API.10) puts a second tie there: an object-literal key's OWN
  property, which is what makes a caret on `{ p: v } satisfies Shape` answer both groups and
  a caret on `sat.p` answer only the literal's. The new `shorthand` is NAVIGATION +
  MEMBERSHIP and deliberately **not** SEED — that is the asymmetry, and it is why the local's
  group and the member's never merge through the span they share. Round 926 called this
  boundary structural; measured, it is a missing role.
- **THE CONTEXTUAL TYPE IS COMPUTED BY WALKING *OUT*, SYNTACTICALLY — the exact dual of
  round 926's `typeCaptureDestructured`, and it had to be written rather than read off the
  checker.** `Checker.contextualType` is walk-scoped ambient a capture cannot trust at an
  arbitrary node, and `cpaCtxAt` pull-derives it over the LEGACY ctx edges and STOPS at every
  statement edge, so it cannot see an annotation at all; CLAUDE.md's own note that `ctxObj` is
  null in a ternary branch is the same gap from the other side — and tsc answers there.
  `typeCaptureContextualType` reads type NODES and resolved SIGNATURES only, so it is a
  function of the position; a call's argument instantiates the callee's signature with the
  call's EXPLICIT type arguments, which is why the explicit/inferred generic split falls out
  rather than being encoded.
- **THE FREE KEY BRANCH IS NOT A NICETY.** A key with no contextual type resolves to its OWN
  declaration, and without that every object-literal key in the program resolves to nothing —
  which is an unresolved identifier spelling the member's name, i.e. round 925's completeness
  net refusing the very member rename this round set out to enable. Arm A9 measures it.
- **PINS +19** (`-project` 445 -> 464, core UNCHANGED at 14,341; suite **14,920 -> 14,939 /
  0 failures / 3 skipped**). 17 are the new `ProjectContextualKeyTest`. **THREE EXISTING PINS
  CHANGED MEANING** and each says so in place: `ProjectDefinitionTest`'s "an object-literal
  KEY being declared answers empty" (round 913) is now "answers its own declaration, or the
  CONTEXTUAL member", and `ProjectRenameTest`'s "a member also supplied by a contextual
  shorthand is refused" (round 925) became TWO pins asserting the RESULTING TEXT of both
  expansions.
- **TEN-ARM ABLATION**, one mistake at a time, anchored replacements whose occurrence count is
  asserted, restored from a sha256-verified snapshot. `scripts/round927-ablate.py`.

| arm | the mistake | red | what it uniquely shows |
|---|---|---|---|
| A1 shorthand-not-grouped | `shorthand` is not a MEMBERSHIP term | **7** | the whole asymmetric half — the member's group loses both tokens |
| A2 shorthand-in-seed | `shorthand` joins the SEED, i.e. `related`'s role | 4 | why it is a THIRD field: a caret on the token merges the two groups |
| A3 no-expansion-direction | a shorthand always expands the local's way | 3 | THE DISCRIMINATOR — `{ p: renamed }` written where `{ renamed: p }` is meant |
| A4 no-contextual-key-leg | an object-literal key resolves to nothing again | **10** | the pre-(API.10) boundary, in every query |
| A5 key-own-property-not-related | a contextual key drops the literal's OWN property | 1 | the symmetric half — `sat.p` and the key stop being one thing |
| A6 no-ternary-passthrough | the out-walk does not cross a ternary's branches | 1 | the position the checker's own contextual type is missing outright |
| A7 no-explicit-instantiation | a call's EXPLICIT type arguments stop instantiating | 2 | the generic split is computed, not encoded |
| A8 verify-by-locations | the verification asks a shorthand's OTHER answer by the wrong key | 3 | (see below — same set as A3) |
| A9 free-key-answers-nothing | a key with no contextual type resolves to nothing | 4 | the completeness net's own refusal, re-armed |
| A10 shorthand-not-a-definition-target | go-to-definition drops the shorthand's member | 1 | the one place both meanings are handed over side by side |

**Nine distinct sets, and the tenth is the round's process finding: A3 and A8 redden EXACTLY
the same three tests, and neither is redundant.** They are two guards on the SAME property —
which way a shorthand expands — at two different layers, and round 925's verification is why
no input separates them: a wrong expansion is caught by the applied-and-recompiled check, so
BOTH mistakes turn a working member rename into a REFUSAL rather than into wrong text. They
differ only in the conflict's DETAIL ("the applied edit did not produce 'X' here" vs "after
the rename this names a different declaration set"), which no pin can assert because a pin
cannot make a claim about an ablated binary. **The general law: when a later layer refuses
exactly what an earlier layer would get wrong, the two are indistinguishable to any
ablation — record them as one observable and say which layer does what, rather than calling
either redundant (round 807's rule needs this qualifier).**

- **GATES.** `cost_gate.py` **+0.00% on all 20 counters** — OFF IS FREE, and a real gate here
  rather than a control, because this round adds core code on the capture path.
  `huge_methods.py --fail-over 0` clean on core (750 classes, 16,009 methods, 0 over) and on
  `-project` explicitly (48 classes, 458 methods, 0 over). `spine_closure_audit.py` not
  applicable — no `spine*EnterNode` changed; the round-920 token gate not applicable —
  `SourceIndex` and the parser are untouched, which is also why the swept population did not
  move. Warning-clean.
- **WHAT IS STILL REFUSED, and it is now a SHORTER list than the mechanism.** (1) **A second
  declaration of the same member name** — `interface Other { p }` beside `interface Shape { p }`
  refuses a rename of `Shape.p`, because a member's DECLARATION name is bound by no scope and
  has no receiver, so it resolves to nothing and the completeness net cannot rule it out. This
  is pre-existing (API.8/9) and is the reason this round's rename pins run on a second, smaller
  file pair. (2) A **shorthand whose member cannot be PLACED** — a literal nothing contextually
  types, an un-annotated destructured parameter — which keeps `CONTEXTUAL_SHORTHAND` as its
  conflict kind. (3) A **computed key** `{ ["p"]: v }`, which is a silent gap rather than a
  refusal: its literal is outside the swept population, and putting it there without resolving
  it would make every such key an obstacle.
- **SUCCESSOR, ranked.** (1) **A member declaration name should resolve to its own SYMBOL** —
  it is now the single largest thing refusing a member rename, one function
  (`typeCaptureMemberDeclarationAt`) and one measured hazard: resolving it to *itself* is NOT
  enough, because the net's real quarry is a MERGED declaration the group missed, and only the
  symbol's whole declaration list puts that back in the group. (2) **completion inside `o["`** —
  an ANCHOR question, not a resolution one. (3) **the incremental / re-entrant seam** — every
  query here is still a full rebuild and a rename is two; it is the right end state and the
  wrong next step, because it is the architecture inversion rather than an API item. **I would
  take (1)**: it is small, it is measured, and it is what stands between a member rename and
  "it always works".

**Round 926 (2026-08-18) — (API.9): THE MEMBER OCCURRENCE SET. ROUND 925 MEASURED IT SHORT BY
THREE KINDS AND REFUSED A MEMBER RENAME BECAUSE OF THEM; **ALL THREE ARE CLOSED**, AND THE
ROUND'S PRODUCT IS THAT THE THIRD ONE'S SHAPE WAS DECIDED BY tsc RATHER THAN BY REASONING —
TWICE, AGAINST TWO DIFFERENT WRONG DESIGNS THIS ROUND HAD ALREADY WRITTEN.**

- **STEP 1 WAS tsc ITSELF AND IT DECIDED EVERYTHING.** `scripts/lsp_member_refs.py` (new; it
  reuses `lsp_rename.py`'s client) drives `textDocument/references` and `textDocument/rename`
  over `tools/tsgo-7.0.2/lib/tsc --lsp -stdio`, and `textDocument/definition` beside it. Three
  fixtures, 25 carets. What was READ rather than reasoned:

| caret | tsc 7.0.2 answers |
|---|---|
| an interface member `Shape.p` | **13 spans / 2 files** — every `o.p`, the `o["p"]` LITERAL, five binding-element property names, TWO implementors' declarations, their `this.p`, a `u.p`, the declaration |
| the string literal of `o["p"]` | the same 13; the edit span is `[77,78)` for a literal at `[76,79)` — **the quotes are excluded** |
| a plain `"p"` elsewhere in the file | **not a reference** |
| `const { p: local } = o`, on the `p` | the member's group; on the `local`, the local's |
| `const { p } = o` (SHORTHAND) | ONE span meaning TWO things — renaming the member writes `renamed: p`, renaming the local writes `p: renamed` |
| a class with the same members and **no `implements`** | **2 spans** — its own declaration and its own `this.p`. **STRUCTURAL COMPATIBILITY DOES NOT RELATE** |
| `override p` in a class extending an implementor | in the interface's group — the edge is **TRANSITIVE** |
| `interface A {p}` + `interface B {p}` + `class C implements A, B {p}`, caret on `A.p` | **7 spans, and `b.p` is NOT among them** — the edge does **not** chain between siblings |
| **go-to-definition on an implementor's own `p`** | **THAT MEMBER**, where references answers the base's whole group |
| an object-literal key `{ p: v }` / `f({ p: "w" })` | in the group — the one kind NOT closed here |

- **KINDS 1 AND 2 ARE POPULATION AND RECEIVER QUESTIONS AND WERE CHEAP.** A binding element's
  `propertyName` is already an identifier the sweep visits; what it lacked was a RECEIVER, and
  the pattern's source is not an expression to the left of a dot — it is the annotation or
  initializer one to three levels up (`typeCaptureDestructured`, which handles a nested pattern
  by asking the level above for its own member, and answers null rather than guessing for an
  array pattern or an un-annotated parameter). An `o["p"]` needed the POPULATION widened:
  `SourceIndex.occurrenceNodes()` is `identifiers()` plus the string literals that name a
  member, and it is the ONE non-`Identifier` this API resolves. `identifiers()` is untouched —
  `fileSemantics`' contract ("every `Identifier`, and nothing else") is documented.

- **KIND 3 TOOK TWO WRONG DESIGNS, AND BOTH WERE CAUGHT BY MEASUREMENT RATHER THAN BY REVIEW.**
  (i) The first put the base's declaration into `CapturedDefinition.locations`. tsc's
  go-to-definition refutes it: an implementor's member navigates to ITSELF. So the edge is a
  SEPARATE field, `CapturedDefinition.related`, which grouping reads and `definitionsAt` does
  not — arm A10 restores the mistake. (ii) The second carried the edge only on a member's
  DECLARATION NAME and then reached the rest of the group by a transitive closure. Two pins
  failed at once: a `this.p` inside the implementor stayed out, and closing transitively merges
  the `A`/`B`/`C` fixture that tsc keeps apart. **The rule that survives both is tsc's own and
  is per-OCCURRENCE**: every occurrence carries its resolved symbol PLUS the bases that symbol
  implements, and two occurrences are the same thing when those sets MEET. Arm A5 restores the
  declaration-only edge; arm A4 removes `related` from the grouping.

- **RENAME REFUSES STRICTLY LESS, AND THE PIN THAT SHOWS IT IS THE OLD ONE REWRITTEN.** Round
  925's `a member with an implementor elsewhere is refused` and `a member also reached by a
  string element access is refused` now assert the RESULTING TEXT of the renamed lines. **The
  discriminator for the element access is the QUOTES** — the edit span is
  `SourceIndex.occurrenceSpanOf`, the text between them, so a plan built from the token span
  writes `bracket[renamedBracketed]`, which compiles and means something else (arm A1). What
  still refuses: a CONTEXTUAL SHORTHAND, and an `o["p"]` the search cannot PLACE (a member of
  an `any`), which keeps `ELEMENT_ACCESS` as its conflict kind because an unplaceable bracket
  is a different report to a user than an unplaceable identifier.

- **THE SHORTHAND IS REFUSED ON PURPOSE AND THE REASON IS STRUCTURAL, NOT EFFORT.** tsc holds
  TWO symbols for the one token of `const { p } = o` and expands in whichever direction the
  caret asks; a capture files ONE answer per span, so admitting it would make the local's group
  and the member's group share a span and merge whenever a caret landed on it. That is the same
  boundary the contextual object-literal key sits behind, and they are now the same refusal.

- **ONE VERIFICATION BUG THE ROUND FOUND IN ITS OWN WORK, and it is a general shape.** The
  rename's third check looks up what each occurrence resolved to BEFORE, keyed by the capture's
  key — the NODE's raw `pos`. An element-access edit begins one character later, so keying the
  lookup by the EDIT's start missed, read as an empty expectation, and refused every
  element-access rename as `WOULD_CHANGE_MEANING`. `PlannedEdit.nodePos` carries the key
  explicitly; arm A8 restores the mistake. **When an edit span and an identity key stop
  coinciding, every map keyed by one and read by the other fails silently and in the
  conservative direction.**

- **PINS +20**, `-project` 425 -> 445, core UNCHANGED at 14,341; suite **14,900 -> 14,920 / 0
  failures / 0 errors / 3 skipped**. 18 are the new `ProjectMemberOccurrenceTest`, whose whole
  design is one discriminator per kind: an `o["p"]` beside two unrelated `"p"` literals, a
  `{ p: local }` beside an unrelated `local`, and a `Structural` class carrying the same member
  with no `implements`. The tsc-parity assertion is an exact SET, not a size.

- **TEN-ARM ABLATION**, one mistake at a time, anchored replacements whose occurrence count is
  asserted, restored from a sha256-verified snapshot. `scripts/round926-ablate.py`.

| arm | the mistake | red | what it uniquely shows |
|---|---|---|---|
| A1 quote-span | the edit span covers the literal's whole TOKEN | **8** | THE QUOTES — the plan writes `bracket[renamedBracketed]`, which compiles |
| A2 no-element-population | the sweep is `identifiers()` again | 8 | the pre-(API.9) boundary; it differs from A1 by the caret-on-the-literal pin |
| A3 no-binding-leg | a binding element's `propertyName` resolves to nothing | 5 | kind 1, including its nested / rest / parameter routes |
| A4 related-not-grouped | `related` is not a GROUPING term | **9** | kind 3 entirely, in every query |
| A5 declaration-only-edge | the edge rides only a member DECLARATION name | 4 | this round's second wrong design — a `this.p` inside the implementor falls out |
| A6 one-level-edge | the heritage walk does not recurse | 1 | the `override` two edges away |
| A7 seed-without-related | the SEED drops its heritage half | 2 | a caret ON an implementor answers only the classes below it |
| A8 verify-by-edit-start | the verification keys its own answer by the EDIT's start | 2 | this round's own measured bug: every element-access rename reads as a change of meaning |
| A9 no-unplaceable-net | an `o["p"]` the search cannot place stops refusing | 1 | the surviving `ELEMENT_ACCESS` conflict |
| A10 definition-follows-edge | go-to-definition follows the heritage edge | 2 | this round's FIRST wrong design, and the divergence from tsc it would have been |

**All ten reddened a DISTINCT set.** **A10 IS ALSO THIS ROUND'S PROCESS FINDING, and it is round
902's trap verbatim**: written against the general construction site alone it read **0 red**, which
is indistinguishable from "the guard is redundant" — because a member DECLARATION name returns
early from `typeCaptureMemberDeclarationAt` and never reaches that line, so the injected mistake
was NOT REACHED. Fixed by patching BOTH sites, and the round added the pin the gap exposed (a
member USE inside an implementor also carries the edge, and its definition must still be that
class's member).

- **GATES.** `cost_gate.py` **+0.00% on all 20 counters** — OFF IS FREE, and here it is a real
  gate rather than a control, because this round DOES add core code on the capture path.
  `huge_methods.py --fail-over 0` clean on core (largest 5,651) and on `-project` explicitly
  (largest 246). The round-920 token gate re-run because `SourceIndex` changed: **1,327 files,
  101,287,620 chars, 3,936,158 identifiers, 0 violations.** `spine_closure_audit.py` not
  applicable — no `spine*EnterNode` changed. Warning-clean.

- **MEASURED ON tsc's OWN SOURCES** (78 files, 9,977,097 chars, real libs, warm),
  `OccurrenceCostMain` (new): the swept population goes **381,670 -> 381,672**. Tsc's own
  compiler sources contain exactly **TWO** `o["…"]` accesses, so the element-access half is
  arithmetically free; the heritage edge runs per member occurrence inside the same walk and
  did not move the wall either — `referencesAt` on `SyntaxKind` reads **9.1-13.0 s** against
  round 925's 10.6-16.0 s, i.e. the same band, with the same **9,827** hits (a control: that
  symbol is not a member, so its group must not have changed). `fileSemantics` is untouched by
  construction — it enumerates `identifiers()`, whose documented contract did not change.

- **SUCCESSOR, ranked.** (1) **contextual object-literal keys** — now the ONLY thing that
  refuses a member rename, the last of round 922's five refusals, and the same shape as the
  binding SHORTHAND this round refused for a structural reason (one span, two symbols): both
  want a capture that can file more than one answer per span, so they are one item rather than
  two. (2) **completion inside `o["`** — hover, definition, references and rename all answer an
  element access now, but a caret in the string still answers `NO_COMPLETION_CONTEXT`; that is
  an ANCHOR question, not a resolution one. (3) **the incremental / re-entrant seam** — every
  query here is a full rebuild and rename is two. **I would take (1)**: it is the last refusal
  standing between a member rename and "it always works", and this round showed the shorthand
  and the contextual key are the same mechanism.

**Round 925 (2026-08-18) — (API.8): RENAME. THE OCCURRENCE SET WAS ALREADY THERE; THE ROUND'S PRODUCT
IS THAT **THE PLAN IS VERIFIED BY APPLYING IT AND COMPILING THE PROGRAM AGAIN**, SO EVERY SAFETY CLAIM
HERE IS A COMPILER RUN RATHER THAN A READING OF THE CODE.**

- **STEP 1 WAS tsc ITSELF, AND IT DECIDED THREE DESIGNS AND FOUND TWO PLACES TO BEAT IT.** Round 924's
  technique, one method over: `tools/tsgo-7.0.2/lib/tsc --lsp -stdio` answers `textDocument/rename` and
  `prepareRename`, so `scripts/lsp_rename.py` drives 22 carets over a fixture built for the hard shapes
  and prints, per caret, the edits AND the resulting text. What was READ rather than reasoned:

| caret | tsc 7.0.2 |
|---|---|
| the local of an object shorthand `{ p }` | `p` -> **`p: newName`** (the key is preserved) |
| the local of a binding shorthand `const { z }` | `z` -> **`z: newName`** |
| the PROPERTY behind that binding shorthand | `z` -> **`newName: z`** (mirrored) |
| an interface member with an implementor and an `o["p"]` | **5 spans**, including the string literal and the implementor's `p` |
| `import { p }`, caret on the import clause | `p` -> `p as newName`; the export in the other file untouched |
| `import { p as q }`, caret on `p` | the EXPORT is renamed, in both files |
| a lib member (`"abc".length`) | `prepareRename` **ERROR**: *"You cannot rename elements that are defined in the standard TypeScript library."* |
| a string literal | `prepareRename` **ERROR**: *"You cannot rename this element."* |
| **rename to `useZ` where `const useZ` already exists** | **NO refusal** — it writes the second one |
| **rename to `class` / to `1bad`** | **NO refusal** — it writes `const class = 1` |

  The last two rows are why this feature validates the new name and checks collisions: they are not
  parity work, they are places where the reference implementation is worse.

- **THE OCCURRENCE SET WAS MEASURED BEFORE ANY CODE, AND IT IS COMPLETE FOR FREE NAMES AND NOT FOR
  MEMBERS.** The same fixture through `Project.referencesAt`: the local, the shorthand and the
  cross-file import are exactly tsc's sets; the interface member is **2 spans against tsc's 5**. The
  three missing are a binding element's `propertyName` (a receiver-based resolution the capture does not
  do), an `o["p"]` (a string literal — outside the identifier population by construction, § 10b's
  boundary) and an IMPLEMENTOR's member (a different symbol here; tsc relates base and derived).
  **That is the STOP-AND-REPORT the brief asked for, cashed as a refusal rather than as a blocker**:
  members are not planned around, they are refused WITH THE EVIDENCE.

- **THE COMPLETENESS NET, and the split that makes it usable.** A SPELLING scan, used as a safety net and
  never as the answer: an identifier spelling the old name is fine when it is in the group (it IS an
  occurrence) or when it RESOLVED to something else (the compiler proved it is a different symbol); what
  is left is unresolved, and unresolved is not unrelated. **The position split is load-bearing**: a member
  declaration name resolves to nothing here, so an unsplit net would let `interface I { p: string }`
  anywhere in the program refuse renaming an unrelated local `p`. So a member rename is judged by the
  MEMBER positions and a plain binding by the FREE ones, with two obstacles that have no resolution to
  consult at all — an element access and a property-hiding shorthand — checked only for a member.

- **THE VERIFICATION IS THE ROUND.** The plan is applied to a scratch `OverlayVfs` wrapped around the
  project's own (so nothing is observable through `updateFile`, `diagnostics` or the parse caches) and the
  program is BUILT AGAIN. Three checks, each seeing something the others cannot: (1) the plan RE-READS —
  every position it says it put the new name is re-parsed and must hold it, so an expansion whose
  arithmetic is wrong fails here and not in the user's buffer; (2) NO NEW DIAGNOSTIC, compared as a
  `(file, code)` multiset — this is the COLLISION check; (3) NOTHING MOVED — every renamed occurrence and
  every identifier that ALREADY spelled the new name must resolve to exactly what it resolved to before.
  **(3) is the CAPTURE check and it is the whole reason for the second build**: renaming a file-level `a`
  to `b` where a function body holds its own `b` moves that body's reads onto the local, with agreeing
  types and NO diagnostic anywhere. Arm A4 removes it and reddens exactly the pin that says so.

- **ONE MEASURED DESIGN CORRECTION, and it is the sort of thing only running it finds.** The expectation
  for a renamed occurrence must be ITS OWN prior answer, mapped through the edits — not "the symbol's
  declarations". While it was in the tree the stronger-looking form refused three CORRECT
  renames — an enum member, an interface member, and (in combination with an `export { newName as p }`
  rewrite that this round then dropped for its own reasons) an exported function — because a member's
  declaration name resolves to nothing here and so does an `ExportSpecifier`'s `propertyName`, so the check
  was reporting this API's own blind spots as changes of meaning. Arm A10 restores the mistake and reddens 2.

- **A DIVERGENCE FROM tsc, DECIDED AND STATED.** A bare `export { p }` and a bare `import { p }` are
  replaced PLAINLY, where tsc expands both (`export { newName as p }`) to preserve the module's public
  name. tsc can, because it holds the local and the exported symbol as TWO symbols and renames the one the
  caret is on; here they are ONE (that is what makes find-references answer across the import hop), so the
  whole group renames together and the plain form is the consistent one — expanding would additionally
  make `export { p }` behave differently from `export const p`, whose public name a rename does change.

- **REFUSED, each with a reason and a pin**: a declaration in a LIBRARY (the safety refusal — tsc's own
  words); an ALIASED import, because the group then spells the symbol two ways and one new name cannot be
  applied to both (tsc picks by caret because it has two symbols to pick between); an unresolved import; a
  caret on EITHER half of an `import { p as q }` (the `p` and the `q` both resolve to nothing, which is
  § 9's boundary); a reserved or malformed new name and a no-op rename — **all four of those without
  compiling anything**; and a member whose occurrence set cannot be shown complete.

- **PINS +35**, `-project` 390 -> 425, core UNCHANGED at 14,341. **14 are parse-only** (`RenameShapeTest`)
  and were written first. THE DISCRIMINATOR is the shorthand, asserted as the exact resulting TEXT of both
  affected lines — a plain occurrence rewrite passes every count-based assertion and renames the object's
  key. **APPLY-AND-RECHECK**: two pins apply the plan through `updateFile` and assert the program's
  diagnostics are byte-identical, which is an INDEPENDENT oracle of the verification `renameAt` runs
  internally (that one goes through a scratch overlay and the capture path; this one through the ordinary
  diagnostic path), so the two agreeing is not a tautology.

- **TWELVE-ARM ABLATION**, one mistake at a time, anchored replacements whose occurrence count is asserted
  (round 922's rule: `git diff --shortstat` is vacuous on a tree carrying the round's own work), restored
  from a sha256-verified snapshot. `scripts/round925-ablate.py`.

| arm | the mistake | red | what it uniquely shows |
|---|---|---|---|
| A1 shorthand-plain | rewrite a shorthand as a plain occurrence | **5** | THE DISCRIMINATOR — it compiles and renames the object's key |
| A2 no-lib-refusal | drop `DECLARED_IN_A_LIBRARY` | 1 | the safety refusal |
| A3 no-diagnostic-check | verification without its diagnostic half | 1 | the COLLISION check |
| A4 no-capture-check | verification without its resolution half | 1 | the CAPTURE check — a rename that compiles and lies |
| A5 name-matching | group by SPELLING instead of by declaration set | 3 | the text-search rename; it also breaks the plan/refusal contract |
| A6 no-completeness-net | no net at all | 3 | all three member obstacles at once |
| A7 no-alias-refusal | apply one new name to two spellings | 1 | the `import { a as b }` group |
| A8 no-reserved-check | tsc's own behaviour (`const class = 1`) | 1 | the reserved word, distinguished from "not an identifier" |
| A9 raw-node-end | the raw `Node.end` for the edit span | **12** | round 910's span law, which reaches into the FOLLOWING token |
| A10 expect-seed | expect the seed instead of each occurrence's own prior answer | 2 | this round's own measured mistake: it refuses CORRECT member renames |
| A11 no-shorthand-net | the net without its shorthand half | 1 | the contextually supplied key |
| A12 no-element-access-net | the net without its element-access half | 1 | the `o["p"]` |

**All twelve compiled, all twelve reddened, and all twelve sets are DISTINCT** — A11 and A12 are the two
halves A6 removes together, and A5 differs from A6 by the contract pin, which is how each is separated
from the others rather than merely being non-empty.

- **GATES.** Suite **14,865 -> 14,900 / 0 failures / 0 errors / 3 skipped = exactly the +35**, re-run on
  the byte-restored post-ablation tree. `cost_gate.py` **+0.00% on all 20 counters** — a CONTROL here
  rather than a gate, since the round changes no core code at all. `huge_methods.py --fail-over 0` clean
  on core and on `-project` explicitly (largest method there 233 bytecodes). `spine_closure_audit.py` and
  the round-920 token gate are not applicable: no `spine*EnterNode` and no `SourceIndex`/parser change.
  Warning-clean.

- **MEASURED ON tsc's OWN SOURCES** (78 files, 9,977,097 chars, 381,670 identifiers, real libs, warm),
  `RenameCostMain`: renaming `createTypeChecker` is 3 edits in 2 files, **13.3-14.3 s** against
  `referencesAt`'s 8.4-8.7 s; renaming **`SyntaxKind` is 9,827 edits across 49 files, 23.9-24.5 s**
  against `referencesAt`'s 10.6-16.0 s. So the verification build costs less than the sweep on a small
  rename (it carries only the renamed occurrences as capture spans, against the sweep's 381,670) and about
  as much on a large one. A refusal decided on syntax alone costs nothing.

- **SUCCESSOR, ranked.** (1) **go-to-definition for an element access `o["p"]`** — a small tail on a query
  that already works, and this round gave it a second reason: an `o["p"]` anywhere is what refuses
  renaming the member `p`, so closing it narrows (API.8)'s largest refusal as well. (2) **contextual
  object-literal keys** — the same shape one mechanism further out (a `{ p }` supplied contextually is the
  other member-rename refusal), and it is the last of round 922's five refusals still standing. (3) **the
  incremental / re-entrant seam** — every query here is a full rebuild and rename is now two of them.
  **I would take (1)**: it is the cheapest of the three and it is the one that makes an existing feature
  refuse less.

**Round 924 (2026-08-18) — (BUG.4): QUICK INFO ON A MEMBER NAME. THE ITEM SAID IT REPORTS
`any`; MEASURED AGAINST tsc 7.0.2's OWN LANGUAGE SERVER IT IS **WORSE THAN THAT** — IT REPORTS
THE TYPE OF WHATEVER UNRELATED BINDING SHARES THE MEMBER'S SPELLING, AND `any` ONLY WHERE
NOTHING DOES.**

- **THE STEP-1 TABLE, TAKEN AGAINST A RUNNING tsc 7.0.2 RATHER THAN AGAINST A READING OF IT.**
  `tools/tsgo-7.0.2/lib/tsc --lsp -stdio` is an LSP server, so the ground truth for a hover is
  obtainable directly: a 70-line Python client (`initialize` / `didOpen` / `textDocument/hover`)
  over the SAME fixture gives tsc's own answer at every caret. The fixture is built to
  discriminate — every member is deliberately spelled like a file-level `const` of ANOTHER type
  (`k` a `string` property and a `boolean` const, `value` a `number` property and a `string`
  const, `p` a `string` field and a `number` const) — because a member name is bound by no
  scope, so "type the name" does not fail loudly, it resolves to the collider.

| position (member spelled like a file-level `const` of another type) | BEFORE | AFTER | tsc 7.0.2 |
|---|---|---|---|
| `o.k` — `k: string`, free `const k: boolean` | **`boolean`** | `string` | `string` |
| `o?.k` through an optional chain | **`boolean`** | `string` | `string` |
| `localObj.k` — an object literal's own member | **`boolean`** | `number` | `number` |
| `o.inherited` — declared on a base, free `const inherited: number` | **`number`** | `boolean` | `boolean` |
| `o.loose` — the member really IS `any`, free `const loose: string` | **`string`** | `any` | `any` |
| `o.m` / `m` in `o.m()` — a method name | `any` | `() => number` | `(): number` |
| `box.value` — `BoxLike<number>`, free `const value: string` | **`string`** | `number` | `number` |
| `box.wrap` — a generic method | `any` | `() => number[]` | `(): number[]` |
| `u.p` — union receiver, free `const p: number` | **`number`** | `string \| number` | `string \| number` |
| `t.k` — receiver is `T extends Shape` | **`boolean`** | `string` | `string` |
| `n.q` inside `if (typeof n.q === "string")` | **`boolean`** | `string` | `string` |
| `imp.field` — imported interface, free `const field: boolean` | **`boolean`** | `string` | `string` |
| `C.s` — a static, free `const s: string` | **`string`** | `number` | `number` |
| `Color.Red` — an enum member | `any` | `Color.Red` | `Color.Red = 0` |
| `NS.nsMember` — a namespace member | `number` | `number` | `number` |
| `d.p` — a class instance member | **`number`** | `string` | `string` |
| `this.p` directly in a method | **`number`** | `string` | `string` |
| `this.p` in a nested arrow | **`number`** | `string` | `string` |
| `super.p` where the subclass OVERRIDES `p` | **`number`** | the BASE's | the BASE's |
| `ix["num"]` — caret on the string literal | `string` (coincidence) | `number` | `number` |
| `NS.Plain` — a qualified TYPE name | `any` | `Plain` | `interface NS.Plain` |
| `this.s` in a STATIC method | **`string`** | `any` (REFUSED) | `number` |
| `{ k2 }` — a shorthand key | `5` | `5` (REFUSED) | `number` |
| `sh.k2` — an object-literal member's widening | `5` | `5` | `number` |
| CONTROL free `k` at its own declaration | `boolean` | `boolean` | `boolean` |
| CONTROL the RECEIVER `o` of `o.k` | `Shape` | `Shape` | `Shape` |
| CONTROL a free TYPE name `Shape` | `Shape` | `Shape` | `interface Shape` |

- **THE MECHANISM, AND IT IS A CHANNEL RATHER THAN A MECHANISM — WHICH THE ITEM PREDICTED AND A
  MEASUREMENT CONFIRMED BEFORE ANY DESIGN WAS COMMITTED.** The rule is tsc's own:
  `getTypeOfSymbolAtLocation` moves off the right-hand side of a property access ONTO THE ACCESS
  and takes the type of that expression, so **the type of the `p` in `o.p` is the type of `o.p`**.
  A throwaway probe that did only that was measured over the whole fixture FIRST: it answers
  correctly for the generic instantiation, the inherited member, the union receiver, the
  type-parameter receiver, the static side, the enum member, the namespace member AND the
  flow-NARROWED member — because `computeRawTypeOfPropertyAccess` already implements every one of
  those and none of them needed a rule here. That probe is the reason the landed fix contains no
  member-table walk: **the brief's `propertyTypeOnCarrier` / `resolveGenericPropertyType` route was
  the right instinct and the wrong altitude** — the compiler's own access typing calls them, and
  reaching past it would have re-derived (and, at a member table, mis-derived) what it already
  does.

- **THE ONE RECEIVER THAT NEEDS THE CARRIER, WHICH IS EXACTLY (API.3d)'s.** The probe answered
  `any` for `this.p` and `super.p`, and for the same reason round 916 recorded: **`this` is
  `Identifier("this")` in this parser** — there is no `ThisExpression` node — so it reaches
  `getTypeOfExpression` as a name nothing binds, and the access inherits the `any`. The leg reads
  `currentClassForThis`, which round 923's `typeCaptureThisClass` ascent installs, and then the
  compiler's own `resolveMemberPropertyType`. It is **ADDITIVE**: where it cannot decide, the
  access's own type answers, which is `any` — a non-answer, never a wrong name. That is what makes
  the static-member refusal below a strict improvement rather than a new hole.

- **TWO NEIGHBOURS CASHED, ONE REFUSED WITH ITS REASON RE-STATED.** (i) An ELEMENT ACCESS
  `o["p"]`: the caret lands on the string literal, whose own type is `string` **whatever the
  member is** — so the old answer was right by coincidence for a `string` member and wrong for the
  `number` one measured. Same rule, one more parent test. Round 922's ranking said this needed "a
  capture channel plus a member lookup BY TEXT"; typing the access needs neither, so the remaining
  half of that refusal (go-to-DEFINITION for a non-identifier) is now smaller than it was written.
  (ii) A QUALIFIED TYPE NAME `N.T` has no access expression to type, so it goes through (API.3d)'s
  export table and reports the DECLARED type — refused when the left side is not a bare name,
  exactly as the definition leg refuses `A.B.x`. (iii) An object literal's own key stays refused
  on round 922's unchanged ground (the CONTEXTUAL type is walk-scoped state this capture does not
  read, and is absent outright in a ternary branch), so a shorthand `{ p }` keeps reporting the
  LOCAL it references — true about a different subject.

- **THREE DIVERGENCES FROM tsc SURVIVE AND EACH IS NAMED RATHER THAN ASSERTED AWAY.** `this.s`
  inside a STATIC method answers `any`: a static `this` is `typeof C`, which
  `currentClassForThis: ClassDeclaration?` cannot model (round 916's own note), so the leg declines
  — it used to answer the collider's `string`, so a wrong name became a non-answer. `sh.k2` reads
  `5` where tsc reads `number`: that is this compiler's own object-literal inference, visible
  through the access and not a capture question — and in the same run `holder.member` DOES widen to
  `number`, so it is the shorthand's `const`-literal source and not a missing widening rule.
  `NS.T` renders as `Alias` when the file also declares `type Alias = NS.T`: the display layer
  names an interned type by whatever alias the program gave it, which a second namespace type with
  no alias (`NS.Plain` -> `Plain`) proves is not specific to members.
- **PINS +27** (`-project` 363 -> 390; core UNCHANGED at 14,341 — nothing was added that a core
  test can reach without the `-project` anchor). `ProjectMemberHoverTest` IS the step-1 table turned
  into assertions, positives and negative controls together, and every expected value in it was
  read out of tsc 7.0.2 rather than predicted — which is how `sh.k2` came to be recorded as a
  DIVERGENCE and `holder.member` as an agreement, two facts a prediction had exactly backwards.

- **EIGHT-ARM ABLATION**, one mistake at a time, each anchored by an occurrence-count assertion
  (round 922: `git diff --shortstat` is vacuous on a tree carrying the round's own uncommitted
  work) and restored from a **sha256-verified** snapshot, never `git checkout`
  (`scripts/round924-ablate.py`). All eight compiled; **seven reddened a DISTINCT set**:

  | arm | red | what it proves |
  |---|---|---|
  | A1 the channel off (the pre-fix behaviour) | **21** | every positive member pin; all controls GREEN |
  | A2 a member-TABLE read instead of the access | **3** | the two generic pins + narrowing — CLAUDE.md's shared-symbol trap, caught by its own pins |
  | A3 no `resolveStructuredTypeMembers` in the `super` leg | **0** | MEASURED-REDUNDANT, recorded as that and not as coverage |
  | A4 free-name FALLBACK wherever the access says `any` | **2** | the naive fix: `o.loose` and static `this` |
  | A5 no `this`/`super` carrier leg | **4** | its own set |
  | A6 `super` answered from the THIS type | **1** | uniquely its own — the override case |
  | A7 no element-access leg | **1** | uniquely its own |
  | A8 no qualified-name leg | **1** | uniquely its own |

  A3 is kept rather than deleted: the call states the reader's precondition and costs nothing on a
  capture-only path, and its zero says `getPropertyOfType` resolves the table anyway on THIS
  fixture — which is a measurement, not a proof that no shape needs it.
- **GATES.** Suite 14,838 -> **14,865 / 0 failures / 0 errors / 3 skipped = exactly the +27**,
  XML-summed over all six modules with a real parser and re-run on the byte-restored
  post-ablation tree. `cost_gate.py` **+0.00% on all 20 counters** — and a CONTROL rather than a
  gate this round, stated as such: `typeCaptureReportedType` has ONE call site,
  `typeCaptureRecord`, which is reached only from `typeCaptureVisit`, which returns at its first
  line when no capture was requested, i.e. on every production build. Its own run compiles 78
  files to 46 errors, so the instrument is live (round 853's frozen-classpath green is what that
  sentence exists to exclude). `huge_methods.py --fail-over 0` clean on core and, per round 909's
  blind-spot rule, on `-project` explicitly. `spine_closure_audit.py` 46 handlers all supersets,
  run although no `spine*EnterNode` changed. The **8-profile `--listAll` grid** is
  `added=0 removed=0` on every profile — a TWO-BINARY grid, HEAD's `Checker.kt` rebuilt against
  the round's, since the change has no runtime switch; the expected result and therefore a control
  for the reachability argument above, not evidence for it.
- **THE DISCRIMINATORS, WRITTEN FIRST.** `a member name reports the MEMBER type and not a
  colliding free binding` together with `a generic member reports the INSTANTIATED type`. Every
  rival implementation fails one: the pre-fix behaviour fails the first (that is the bug); a fix
  that merely stops answering `any` — a free-name FALLBACK wherever the access says `any` — passes
  both and fails `a member that really is any reports any` and the static-`this` pin (arm A4); and
  a member-TABLE read passes the first and fails the second, because an interface member's symbol
  is shared by every instantiation and its cached type is the bare `T` (arm A2). The collider
  bindings live in THE SAME FILE deliberately: a top-level binding in another MODULE is not in this
  file's scope at all, so it could never have been the wrong answer and would discriminate nothing.

- **SUCCESSOR, ranked.** (1) **RENAME** — unchanged from rounds 922/923 and still the item with the
  most user-visible value; (API.5) supplies the occurrence sets and `ReferenceUse` the read/write
  split, so what is left is the EDIT PLAN, where a shorthand `{ p }` and an `import { p as q }` do
  not rewrite the way a plain occurrence does. (2) **go-to-definition for an element access** —
  round 922 ranked it as wanting "a capture channel plus member-lookup-by-text", and this round
  cashed the HOVER half without needing either, so what remains is narrower than it was written:
  offering a DECLARATION for a non-identifier node. (3) the incremental / re-entrant seam. **I
  would take (1)**: (2) shrank this round and is now a small tail on a query that already works,
  while rename is the last big thing an editor needs that this API cannot do.

**Round 923 (2026-08-18) — (BUG.3): THE LAYER QUESTION *WAS* THE ROUND, AND THE ANSWER IS THAT
**THE CHECKER IS RIGHT AND THE CAPTURE WAS WRONG** — SETTLED BY MEASUREMENT AGAINST tsc 7.0.2 ITSELF
BEFORE A LINE WAS WRITTEN.**

- **THE STEP-1 TABLE, WHICH IS THE ROUND'S PRODUCT.** Round 922 recorded "a caret on `this.` inside a
  NESTED ARROW answers no members" without saying which layer owned it, and the two possibilities had
  wildly different blast radii: if the CHECKER lost class-`this` inside an arrow then `this.typo` is
  silently unchecked in one of the most common shapes in the language, which outranks any API
  symptom. A 24-line fixture — `this` in a method, in an arrow, in an arrow inside an arrow, in a
  `function` EXPRESSION, in a `function` DECLARATION, in an object-literal method, in an arrow inside
  an object literal, in a getter, a setter, a constructor, a property initializer, a static method, a
  static-member arrow, a `function` nested inside an arrow, and a class expression's method's arrow —
  compiled through the ORDINARY diagnostic path gives **17 diagnostics BYTE-IDENTICAL to tsc 7.0.2**:

  | position | xtsc | tsc 7.0.2 |
  |---|---|---|
  | `this.p` directly in a method | TS2339 on `C` | TS2339 on `C` |
  | ... in an ARROW in that method | TS2339 on `C` | TS2339 on `C` |
  | ... in an arrow inside an arrow | TS2339 on `C` | TS2339 on `C` |
  | ... in a `function` EXPRESSION | TS2683 implicit-any `this` | TS2683 |
  | ... in a `function` DECLARATION | TS2683 | TS2683 |
  | ... in a `function` inside an arrow | TS2683 | TS2683 |
  | ... in a static method / its arrow | TS2339 on `typeof C` | TS2339 on `typeof C` |
  | ... in a property initializer / its arrow | TS2339 on `C` | TS2339 on `C` |
  | ... in a constructor | TS2339 on `C` | TS2339 on `C` |
  | ... in a getter's arrow / a setter's arrow | TS2339 on `C` | TS2339 on `C` |
  | a CORRECT `this.real` in each of those | SILENT | SILENT |

  Two divergences in the whole table and NEITHER is about arrows, both pre-existing and both recorded
  rather than touched: an object-literal method's `this.p` is a **false NEGATIVE** here (tsc emits
  TS2339 on `{ k(): void; }`), and a class EXPRESSION's instance type DISPLAYS as `(Anonymous class)`
  where tsc says `CE` — a form divergence in a type NAME. **So the verdict is (b): capture-only, zero
  corpus risk, and the compiler-correctness worry the item raised is answered NO.**

- **THE MECHANISM OF THE DEFECT, once the layer was known.** `typeCaptureVisit` installed
  `currentClassForThis = frame.classForThis` from `ctaFrames.last()`. A **cta frame is a
  TYPE-checking context and `this` is not one of the things it threads**: the `is ArrowFunction ->`
  arm of the frame push calls `ctaFnBodyFrame` with no `classForThis`, so it defaults to null, and
  the checker does not care because `this` reaches the DIAGNOSTICS through B101's own walk-scoped
  installs — which an arrow deliberately preserves (`Checker.kt` says so in as many words:
  *"a function expression rebinds `this`; clear the enclosing class (an arrow, below, preserves
  `this`)"*).

- **THE FIX IS `typeCaptureThisClass`, AND WHAT IT IS *NOT* IS THE INTERESTING PART.** A pull-based
  ascent of the parent chain, transparent to `ArrowFunction` and opaque to everything else that binds
  `this`. It is deliberately **not** round 922's `typeCaptureEnclosingClass` — that answers "which
  class body is this caret lexically in", which is the ACCESSIBILITY question, and it would happily
  offer a class's members inside a `function` expression. And it is deliberately **not** the
  checker's own `spineCaClassCtx`, even though that is the same shape and exists for exactly this
  purpose: its KDoc records that a nested `FunctionDeclaration` is **bug-compatibly transparent** to
  it, so reusing it verbatim would have passed every other pin in the round and failed precisely at
  `function inner() { this.| }`. Arm A2 is that measurement. What the ascent DOES reuse is the RULE
  those installs state — free function and function expression clear, arrow preserves, object-literal
  member clears, class member sets per static/instance.

- **BIAS: PROVE TO OFFER**, the mirror of round 922's prove-to-hide one level down. A static member
  (whose `this` is `typeof C`, which `currentClassForThis: ClassDeclaration?` cannot model), an
  object literal's method, a `function` at any depth, a CLASS EXPRESSION and a caret in no class at
  all all answer NOTHING. The class-expression stop is load-bearing rather than cosmetic: without it
  the ascent walks straight past `const K = class { m() { this.| } }` written inside a method of `C`
  and answers with **`C`'s** members — a confident, plausible, wrong list, which is the one failure
  mode a completion UI gives the user no way out of.

- **TWO SIDE FINDINGS, both stated rather than fixed.** (1) **An EXPRESSION-bodied arrow already
  worked**, and the reason is the mechanism's own shape: a cta frame is pushed at a `Block` enter, so
  an arrow with no block pushes none and `ctaFrames.last()` was still the enclosing METHOD's frame.
  The bug was never "arrows" — it was arrows that OPEN A BLOCK, which is why the item's own
  reproduction and the fix's pin have to be written with braces. (2) **Quick info on a member name is
  a SEPARATE, RECEIVER-INDEPENDENT gap and the brief's "they share the path" is false.** `quickInfoAt`
  resolves the NARROWEST node at the caret — the member identifier — and asks for the type of that
  name as if it were free, so it reports `any` for `o.k` with an ordinary receiver, for `this.p`
  inside a METHOD where completions and definitions both answer correctly, and for `this.p` inside a
  nested arrow alike. Measured all three; pinning it here would have pinned the wrong subject, and it
  is named as a successor instead.

- **PINS +20** (`-project` 343 -> 363; core UNCHANGED at 14,341 — nothing was added that a core test
  can reach without the `-project` anchor). `ProjectThisReceiverTest` IS the step-1 table turned into
  assertions, positives and negative controls together. Round 922's in-file note in
  `ProjectMemberAccessibilityTest` — which explains why `this` is not a receiver there — was UPDATED
  IN PLACE naming the round that closed the gap, never deleted.

- **THE DISCRIMINATORS, written first.** `an arrow nested in an arrow answers` together with
  `a function EXPRESSION in a method answers NOTHING`. Every rival implementation fails one of the
  two: reading the cta frame fails the first (that is the bug); "any caret lexically inside a class
  answers" — i.e. `typeCaptureEnclosingClass`, already in the file — passes the first and fails the
  second; "the innermost function-like decides, whatever it is" fails the first, because an arrow
  binds no `this` at all.

- **SEVEN-ARM ABLATION**, one mistake at a time, each restored from a **sha256-verified** snapshot
  (`scripts/round923-ablate.sh`). A1 an arrow is not transparent (the pre-fix behaviour expressed
  inside the new ascent) -> **10**. A2 a `function` is transparent, i.e. `spineCaClassCtx` reused
  verbatim -> **3**, exactly the three `function` pins. A3 a static member answers with the instance
  class -> **2**. A4 an object-literal member keeps ascending -> **1**. A5 the ascent does not stop at
  a class EXPRESSION -> **0**. A6 the install reverted to `frame.classForThis`, the whole fix off ->
  **9**. **A5 is recorded as a MEASURED-REDUNDANT guard, not as coverage** (CLAUDE.md's rule that an
  undiscriminated seam is as often a redundant guard as a blind pin): the member arm's
  `as? ClassDeclaration` cast already answers null for a class expression's method, so the two guards
  are mutually redundant there. **A7 exists to prove the pin is not vacuous** — A4 and A5 TOGETHER,
  which reddens the class-expression pin; it is explicitly NOT an attribution (round 807 forbids a
  combined arm for that) but a redundancy demonstration.

- **GATES.** Suite 14,818 -> **14,838 / 0 failures / 0 errors / 3 skipped = exactly the +20**,
  XML-summed over all six modules. `cost_gate.py` **+0.00% on all 20 counters**, and a real gate
  rather than round 853's frozen-classpath green — its own run compiles 78 files to 46 errors.
  `huge_methods.py --fail-over 0` clean on core (**750 classes, 15,983 methods, 0 over**) and, per
  round 909's blind-spot rule, on `-project` explicitly (**35 classes, 326 methods, 0 over**).
  `spine_closure_audit.py` 46 handlers, all supersets. **The 8-profile `--listAll` grid is
  `added=0 removed=0` on EVERY profile** (`scripts/round923-grid.sh`, profiles enumerated by their
  `tsconfig.json` and the run refusing below 8) — and it is a TWO-BINARY grid, HEAD's `Checker.kt`
  rebuilt against the round's, because the change has no runtime switch. That result is the expected
  one and is a CONTROL: `typeCaptureThisClass` is reachable only from `typeCaptureVisit`, which
  returns at its first line when no capture was requested, i.e. on every production build.
  Warning-clean (the only `w:` lines are pre-existing `Thread.id` deprecations in a daemon test).

- **SUCCESSOR, ranked.** (1) **RENAME** — unchanged from round 922's ranking, and still the item with
  the most user-visible value: find-references plus an edit plan, where the edit plan is the work.
  (2) **quick info on a member NAME** — newly named and newly measured here, small and mechanical
  (route the member caret through the same member resolution completions and definitions already
  use), and it fixes hover for EVERY receiver, not just `this`. (3) **element access `o["p"]`** —
  round 922's corrected reason still stands. (4) the incremental / re-entrant seam. **I would take
  (2) then (1)**: (2) is the smallest thing that removes a wrong answer a user sees on every hover.

**Round 922 (2026-08-18) — (API.7): THE SYNTACTIC-ROLE MECHANISM, AND **THREE OF THE FIVE REFUSALS
CASHED**. THE ROUND'S PRODUCT IS THE CORRECTION TO ITS OWN RANKING: THE BACKLOG WAS PROMOTED AS ONE
ITEM BECAUSE "ALL FIVE WANT THE SAME MISSING MECHANISM", AND **ONLY THREE DO** — THE OTHER TWO WERE
NEVER BLOCKED ON A GRAMMAR POSITION AT ALL, AND SAYING SO IS WORTH MORE THAN LANDING THEM BADLY.**

- **THE MECHANISM: `SyntaxRoles`, a PULL-BASED ASCENT of the parent chain** (`-project`,
  `SyntaxRoles.kt`). INV.2(a) stamps `parent` on every node, so a role is a pointer walk needing no
  side table and no second traversal — the INV.4 reach classifiers' shape, and round 875's
  measurement is why it is pull and not push (a maintained status is **11.1x** more work, because it
  computes every classifier at every node while a pull folds only the ancestors of the nodes actually
  asked about). Two questions, one traversal: `referenceUse(node)` answers about a NODE and
  `grammarPositionOf(path)` about a CARET, the second expressed on the first — an identifier is in an
  EXPRESSION position exactly when it is a value occurrence. **Every `===` in the file is deliberate**:
  AST nodes are `data class`es, so a `current in parent.elements` would be round 471's deep structural
  compare of two arbitrary expressions where identity was meant.

- **IT DOES NOT ALL LIVE IN ONE MODULE, AND THAT WAS THE RIGHT CALL.** The accessibility filter's
  caret-side question is the same ascent, but its other half — the member's DECLARING class and
  whether the caret's class derives from it — needs symbols and heritage resolution, neither of which
  crosses the module boundary. So it is a sibling ascent in `Checker.kt`
  (`typeCaptureEnclosingClass` / `typeCaptureDerivesFrom` / `typeCaptureResolveClassDeclaration`),
  which is what the brief meant by deciding the home PER QUESTION.

- **CASHED (1): MEMBER-COMPLETION ACCESSIBILITY — round 917's refusal.** `private` (including a
  `#name` field) is offered only inside its declaring class, `protected` only there or in a class
  deriving from it, statics by the same rule; the ascent goes out of a nested arrow, through its
  method, to the class, and the heritage walk follows `extends` through the same scope lookup and
  IMPORT HOP the definition legs use. **THE BIAS IS PROVE-TO-HIDE and it is the whole safety
  argument**: an unresolvable base, a missing declaring class or a chain past its depth cap leaves the
  member OFFERED, because round 917's stated objection — a list that has silently lost a real
  candidate is indistinguishable from a complete one — is only answered by hiding what is decided.
  `accessibility` is still reported on what survives.

- **CASHED (2): KEYWORD COMPLETIONS — round 918's refusal**, and BOUNDED EXPLICITLY. A STATEMENT
  caret gets the statement and declaration starters plus the expression starters; an EXPRESSION caret
  gets the expression starters ONLY (this is what keeps `interface` out of `f(|)`); a TYPE caret gets
  the fourteen primitive type names plus `keyof` and `typeof`; a class body, a heritage clause and an
  import clause get NOTHING. Context-gated: `await` on an enclosing async function, `yield` on a
  generator, `super` on a class, `return` on a function, `break` on a loop or `switch`, `continue` on
  a loop (the scan stopping at the first function-like, since a loop does not reach into a nested
  function), and `import`/`export`/`declare`/`namespace`/`interface`/`type`/`enum` on a module or
  namespace body. **NOT offered anywhere, stated rather than hidden**: every CONTINUATION keyword
  (`else`, `case`, `extends`, `implements`, `as`, `satisfies`, `infer`, `readonly`, the accessibility
  modifiers) — their positions are ones the classifier declines to name. The list is short by choice;
  what it guarantees is that every item COMPILES WHERE IT IS OFFERED, which is the property the member
  half already had. One coarseness recorded: a caret whose word is already a complete keyword (`if|`)
  usually reads as the EXPRESSION position, because the parser has built the statement that keyword
  starts — it loses suggestions and never invents one.

- **CASHED (3): READ-vs-WRITE — round 919's refusal**, `ReferenceLocation.use`. The write set is
  stated completely (simple `=` including a member's last segment, destructuring in either bracket
  form at any depth with defaults / renaming / shorthand / rest, a `for (x of/in …)` head, a
  parameter's and a variable/binding-element declaration's own name); `READ_WRITE` is the compound
  assignments and `++`/`--`; and **`UNCLASSIFIED` is a fourth state, not a default** — a
  type-position name, a declaration name that binds no storage, an object-literal key, a binding
  element's source property name, a label. That state is what keeps round 919's objection answered: an
  occurrence the classifier does not place is reported as unplaced. **The ascent is why the
  destructuring cases are free**: an array literal inside an object literal inside an array literal is
  three pass-through steps and then ONE assignment test, with no per-shape rule.

- **REFUSED, AND THE REASON IS NOW SHARPER THAN THE RANKING'S — THIS IS THE ROUND'S FINDING.** The
  backlog was promoted as ONE item on the premise that all five wanted "where is this caret in the
  grammar". **Element access (`o["p"]`) and contextual object-literal keys (`{ p: v }`) never did.**
  Recognising either shape is ONE test on the node's own parent — no ascent, no classifier, and
  `SyntaxRoles` supplies nothing either of them was waiting for. What each actually lacks is SEMANTIC:
  the element access needs the capture to accept a NON-IDENTIFIER node and to look a member up BY TEXT
  on the receiver's type (the receiver resolution itself is (API.3d)'s and is already here — the
  missing part is the CHANNEL); the object-literal key needs the CONTEXTUAL type, walk-scoped state
  this capture does not read and which is absent outright in positions such as a ternary branch, i.e.
  a third resolution mechanism beside the scope chain and the receiver. **So the correct successor
  ranking splits them**: element access is small and mechanical, contextual keys are a mechanism.

- **TWO EXISTING ANSWERS CHANGED, loudly.** `completionsAt` at a MEMBER caret no longer returns
  inaccessible members, and at a FREE_NAME caret now returns keyword items (`kind = "Keyword"`) mixed
  into the list. `ReferenceLocation` gained a `use` property. Round 917's and round 918's pins
  asserting the old refusals were **UPDATED IN PLACE with an in-file comment naming the round that
  inverted them**, never deleted.

- **A PROCESS TRAP FOUND BY RUNNING THE PROTOCOL, now in CLAUDE.md: round 855's "dry-run each arm for
  a real diff" is VACUOUS on a tree carrying the round's own uncommitted work.** `git diff
  --shortstat` printed `2 files changed, 146 insertions(+), 1 deletion(-)` for arm a1 AND for a2 —
  the round's whole diff, identically, for every arm — so it can no longer tell a landed edit from an
  unlanded one. What actually carried the run is the `patch` helper's ANCHOR-COUNT assertion (exactly
  one occurrence, or exit 3); the script now compares against the ablation's OWN SNAPSHOT, which is
  the only baseline that is a property of the arm. Round 789's "commit the harness first" is the other
  fix and remains the better one.

- **FOUND IN PASSING, unrelated to accessibility and recorded rather than fixed: a caret on `this.`
  inside a NESTED ARROW answers NO members at all** (`currentClassForThis` is null there), where the
  same caret directly inside a method answers correctly. It cost one pin, which was rewritten onto a
  named receiver — a better discriminator anyway, since it exercises the new ascent rather than the
  `this` leg. Queued below as (BUG.3).

- **PINS +45** (`-project` 298 -> 343; core UNCHANGED at 14,341 — nothing was added there that a core
  test can reach without the `-project` anchor). 32 of them are PARSE-ONLY (`SyntaxRoleTest`: no
  checker, no build, ~5 s to run), which is what makes the mechanism cheap to re-verify.
  **THE DISCRIMINATORS, each written first**: for read/write, round 919's own three shapes
  (`[x] = pair`, `({ x } = o)`, `for (x of xs)`), which the naive "left of `=` or operand of `++`"
  rule calls READS — plus `the same brackets in a value position are READS`, which fails the OTHER
  shortcut ("anything under an assignment's left-hand side"); for keywords, an EXPRESSION position
  asserted not to offer `interface` and a non-async function asserted not to offer `await`; for
  accessibility, a caret inside a SUBCLASS METHOD, which every rival rule ("inside any class",
  "inside the declaring class", "no filter") passes the easy cases and fails alone.

- **FOURTEEN-ARM ABLATION, one mistake at a time, each restored from a sha256-verified snapshot; all
  fourteen compiled and ALL FOURTEEN reddened a DISTINCT set.** A1 array literal not a pass-through
  -> 4. A2 object literal not a pass-through -> 4. A3 `for-of` head not a write -> 1. A4 a member name
  does not ascend to its access -> 2. A5 declaration names read as values -> 4. A6 type-position names
  read as values -> 2. A7 every free caret is a STATEMENT position (the unconditional list) -> 3,
  including the `interface` discriminator. A8 `await`/`yield` ungated -> 3. A9 module-level starters
  everywhere -> 2. A10 `break`/`continue` ungated -> 2. A11 keywords read at the CARET rather than at
  the word's start -> 2. A12 accessibility hides whenever the caret is inside ANY class -> 4,
  including the subclass discriminator. A13 the enclosing-class ascent stops at an arrow -> 1, exactly
  the nested-arrow pin. A14 no filter at all (the pre-(API.7) behaviour) -> 8.
  `scripts/round922-ablate.sh`.

- **GATES: suite 14,773 -> 14,818 / 0 failures / 0 errors / 3 skipped = EXACTLY the +45**, XML-summed
  over all six modules and re-run on the byte-restored post-ablation tree. `cost_gate.py` **+0.00% on
  all 20 counters** — a real gate, since `Checker.kt` grew ~130 lines reachable from the capture hook
  on the hot walk, and proven live by its own 46-error / 78-file compile. `huge_methods.py
  --fail-over 0` clean on core (**750 classes, 15,981 methods, 0 over**) and, per round 909's
  blind-spot rule, on `-project` explicitly (**35 classes, 326 methods, 0 over**; the largest new
  method is `SyntaxRoles.keywordsFor` at 194 bytecodes). `spine_closure_audit.py` 46 handlers all
  supersets. `scripts/round920-token-gate.sh` **1,327 files, 101,287,620 chars, ZERO violations** —
  run because `SourceIndex` gained a member. Warning-clean. No wall A/B: production executes not one
  new instruction, every addition sitting behind a hook that returns on a null per-file key set.

- **SUCCESSOR, ranked.** (1) **RENAME** — it is (API.5) plus an edit plan, the edit plan is the work
  (`{ p }` and `import { p as q }` do not rewrite like a plain occurrence), and `ReferenceUse` is now
  available to it, which a rename UI wants. (2) **element access `o["p"]`** — small and mechanical
  now that its reason is named: a capture channel for a non-identifier node plus a member lookup by
  text, with the receiver resolution already in place. (3) **the incremental / re-entrant seam**
  (`docs/ARCHITECTURE-RETHINK.md`) — still the only thing that changes the cost model, and still the
  change most likely to cost a month. **I would take (1).**

**Round 921 (2026-08-18) — (API.6): SIGNATURE HELP LANDS, EVERY OVERLOAD. THE RANKING'S PREMISE —
"three-quarters built" — HELD FOR THE CALLEE AND WAS WRONG ABOUT THE ANCHOR: THIS IS THE FIRST QUERY
IN THE ARC WHOSE SUBJECT IS A **REGION THE PARSE CARRIES NO NODE FOR**, AND THREE OF ITS ORDINARY
CASES DEFEAT A CONTAINMENT TEST OUTRIGHT.**

- **THE CALLEE HALF WAS EXACTLY AS RANKED, WHICH IS WORTH SAYING BECAUSE THE ANCHOR HALF WAS NOT.**
  `getCalleeType` + `getCallSignaturesOfType` — the argument checker's own pair — answered a plain
  name, a METHOD through a receiver, an imported function, a callee that is ITSELF a call and a
  DECORATOR factory with no rule of their own. Two additions were needed and both are (API.3d)'s
  second mechanism reappearing: a NAMESPACE/module/enum member is on no TYPE at all (arm A5, 1 red
  uniquely its own — measured at ZERO signatures before the export-table leg existed), and a `new`
  needs `getReturnTypeOfNewExpression`'s own resolution behind it, because a class NAME does not type
  as its own constructor while the INSTANCE type carries the construct signatures (round 475).

- **THE ANCHOR IS THE ROUND. Signature help asks about the argument LIST, and the parse has no node
  for one.** Three ordinary cases: `f(a, b|)` sits at the real END of `b`, so the half-open spans put
  it outside `b` and the answer is still argument 1; `f(a, |)`'s second argument does not exist in the
  tree; and for `f(` at end of file or `f(a,` before a `}` **the call node's own real end lies BEFORE
  the caret**, so no descent reaches it at all. **The parser recovery was read out of `Parser.kt`
  before any code was written** (round 917's discipline): `parseArgumentListWorker` breaks on
  end-of-file and on a `}` and then runs `parseExpected(CloseParen)`, so the `CallExpression` EXISTS in
  every one of those shapes — which is what makes a token-level anchor possible instead of a
  refusal. The region is BRACKET-MATCHED over the token stream, stopping early at a closer that does
  not match the top of the stack (an unmatched `}` means the enclosing block is closing, and the
  argument list ends there rather than running to EOF), and template substitutions need no rule of
  their own because (BUG.2)'s re-scan already turns their `}` into a template middle or tail.

- **THE ARGUMENT INDEX IS A COUNT OF COMMAS, AND WHICH COMMAS IS DECIDED BY THE ARGUMENTS' OWN
  SPANS.** A comma inside one of the arguments belongs to that argument's syntax, so a nested call, an
  object literal, an arrow parameter list and a `Map<string, number>` TYPE ARGUMENT are all excluded
  by ONE test — and that last one is the case no bracket-depth scan could have handled, since `<` and
  `>` are not brackets. Arm A8 (count every comma) reddens exactly those four.

- **THE ACTIVE-SIGNATURE RULE IS TWO CONDITIONS AND NO SCORING, AND BOTH HALVES ARE ABLATED.** The
  FIRST signature that could still become this call: room for the argument the caret is on (its index
  is within the parameter list, or the signature ends in a REST parameter, or it takes none and none
  were passed — a `()` signature IS satisfied by the empty argument list the caret sits in), AND
  `signatureAcceptsArgs` over the arguments already FINISHED, which is the same verdict
  `resolveCallOverload` selects an overload with, so a host's highlighted overload and the compiler's
  chosen one cannot drift apart. **The argument the caret is IN is deliberately not judged** — it is
  half-typed by construction, so judging it would flip the highlight back and forth under the user's
  hands. Nothing qualifying answers 0, reported rather than hidden. A6 (always 0) reddens 2, A7 (arity
  only) reddens 1 of those 2 — a strict subset distinguished by the pin it leaves GREEN, which is the
  round-918-A4 shape and is the measurement made a second time.

- **ONE COMPILER-SIDE SURPRISE, AND IT WOULD HAVE SHIPPED A PLAUSIBLE-LOOKING LIE.** A parameter
  declared with a BINDING PATTERN is dropped from `Signature.parameters` by `getParameterSymbols`
  unless the signature was built for display, and the surviving symbols keep a POSITIONAL zip of the
  declaration's annotations — so rendering `function destructured({ a, b }: O, tail: string)` from the
  symbols alone prints **`destructured(tail: O)`**: one parameter short AND the survivor wearing its
  neighbour's type. Not a coarse answer, a wrong one. The DECLARATION is rendered instead whenever its
  parameter list is longer, with the pattern spelled as source and each type resolved from its
  annotation (arm A10, 1 red uniquely its own).

- **ONE TYPE-PRINTING CONVENTION, ON PURPOSE.** Every type goes through `typeToString` — hover's
  renderer — and deliberately NOT through the existing `signatureToString`, whose `p?: string |
  undefined` is a TS2345 MESSAGE convention rather than a signature label. A signature label and a
  hover string describing the same type must not be able to disagree. Parameter ranges index the
  LABEL and are recorded AS IT IS BUILT (arm A11): searching for `name: type` afterwards finds the
  wrong occurrence the moment one parameter's type mentions another's spelling.

- **A GENERIC CALLEE RENDERS UNINSTANTIATED** — `pickFrom<T>(xs: T[], index: number): T`. Inferring
  `T` would mean inferring it from arguments that are not finished, so the value would change under
  every keystroke and be wrong for the argument still being written; and the declared form is what
  tells the reader that `T` is inferred at all.

- **REFUSED, each with a reason and a pin**: a TAGGED TEMPLATE (no parenthesized argument list —
  counting template substitutions is a second mechanism), TYPE ARGUMENTS (`f<|>(x)` is not an argument
  list), `super(...)` (an ordinary `Identifier` in this parser, bound to nothing, so an EMPTY
  signature list rather than the base constructor — stated so it does not read as a resolution
  failure), and a SPREAD's arity (`f(...xs, |)` reports argument 1, because the commas say so).
  **NOT refused and pinned as covered**: decorator factories and a callee that is itself a call.

- **PINS: +56** (`-project` 242 -> 298; core UNCHANGED at 14,341 — nothing was added there a core test
  can reach without the `-project` anchor). 30 parse-only anchor pins written FIRST and 26 end-to-end.
  **THE DISCRIMINATOR, written first**: an OVERLOADED callee asserted as an EXACT list of three
  labels. Every plausible shortcut — resolve the callee's type and render it, take the one overload
  resolution picks, match the callee by NAME — answers ONE signature and passes every other pin in the
  file.

- **ELEVEN-ARM ABLATION, one mistake at a time (round 807), each dry-run for a real diff (round 902),
  restored from a sha256-verified snapshot and never `git checkout` (round 851). All eleven compiled;
  ALL ELEVEN reddened a DISTINCT set.** **A1** the anchor keeps the OUTERMOST call -> 1. **A2** only
  the FIRST overload reported -> 1, the discriminator. **A3** no rest-parameter clamp -> 1, uniquely
  its own. **A4** no receiver path (only a bare name resolves a callee) -> 2. **A5** no export-table
  leg -> 1, uniquely its own. **A6** activeSignature always 0 -> 2. **A7** activeSignature by arity
  alone -> 1. **A8** every comma counts -> 4. **A9** the region is the call's own real end rather than
  bracket-matched -> 6, every incomplete-call pin plus the past-the-paren negative. **A10** no
  declaration render for a dropped binding-pattern parameter -> 1. **A11** label ranges not followed
  -> 1. `scripts/round921-ablate.sh`.

- **GATES: suite 14,717 -> 14,773 / 0 failures / 0 errors / 3 skipped = EXACTLY the +56**, XML-summed
  over all six modules. `cost_gate.py` **+0.00% on all 20 counters** — a real gate this round, since
  `Checker.kt` grew ~370 lines reachable from the capture hook on the hot walk, and proven live by its
  own 46-error / 78-file compile. `huge_methods.py --fail-over 0` clean on core (**750 classes, 15,976
  methods, 0 over**) and, per round 909's blind-spot rule, on `-project` explicitly (**28 classes, 280
  methods, 0 over**). `spine_closure_audit.py` 46 handlers all supersets, run although no
  `spine*EnterNode` changed. `scripts/round920-token-gate.sh` **1,327 files, 101,287,620 chars, ZERO
  violations** — run because `SourceIndex` gained members. Warning-clean. No wall A/B: production
  executes not one new instruction — every addition sits behind a hook that returns on a null per-file
  key set.

- **SUCCESSOR, ranked.** (1) **The refusal backlog as ONE item** — member completion's accessibility
  filter, contextual object-literal keys, element access `o["p"]`, keyword completions, and now
  read-vs-write on a reference: **all five want the same missing mechanism**, "where does this caret
  sit in the grammar / relative to a declaration", so they are one round rather than five, and the
  backlog is now long enough that the mechanism is cheaper than the refusals. (2) **Rename** — it is
  (API.5) plus an edit plan, and the edit plan is the work (`{ p }` and `import { p as q }` do not
  rewrite like a plain occurrence). (3) **The incremental/re-entrant seam**
  (`docs/ARCHITECTURE-RETHINK.md`) — the right end state and the wrong next step: every figure in this
  arc is dominated by a full rebuild, so it is the only thing that changes the cost model, and it is
  also the change most likely to cost a month. **I would take (1).**

**Round 920 (2026-08-18) — (GATE.2): THE INSTRUMENT ROUND 919 DID NOT BUILD, AND IT FOUND **FIVE MORE
DEFECTS ON ITS FIRST RUN** — INCLUDING (BUG.2) IN A SECOND COSTUME (A BACKTICK INSIDE A REGULAR
EXPRESSION, IN tsc's OWN SOURCE) AND A `[0, 0)` PARAMETER SPAN THAT MADE EVERY CARET ON A
PARENTHESIS-LESS ARROW PARAMETER — **328 SITES IN 78 FILES** — ANSWER ABOUT THE ARROW. ALL FIVE FIXED;
101 MB OF REAL TypeScript NOW PASSES TEN INVARIANTS WITH ZERO VIOLATIONS.**

- **WHAT THE GATE ASSERTS, AND WHY IT IS STATED AGAINST THE PARSE.** Ten rules, all true of ANY correct
  implementation so none needs a baseline: the tokens partition the text and the scan reaches EOF; every
  gap between two tokens holds only whitespace or a comment; a string literal never crosses a line break;
  a non-literal token is under 512 characters; **every identifier the PARSER found starts a token of
  exactly its length**, and `realEndOf` answers that end; a descent to an identifier's own position
  reaches that identifier; a path strictly nests and every node on it contains the offset; and
  offset↔coordinate round-trips against an INDEPENDENT restatement of round 915's terminator rule
  (comparing `LineMap` to a second copy of its own arithmetic would prove nothing). **The parse is the
  oracle** — it is the context-sensitive lexer this index approximates — which is what makes a MERGE
  expressible at all: a token that swallowed an identifier leaves no token starting where the parser
  says one starts. `TokenIndexInvariants` collects rather than throws and caps PER RULE, so one broken
  file reports its shape instead of its first symptom.

- **THREE CORPORA, AND THE CHOICE IS HALF THE ROUND.** (1) An adversarial shape corpus, written here —
  cheap, named, and carrying exactly the weakness that let (BUG.2) live: it can only hold shapes somebody
  thought of. (2) **The real `lib.*.d.ts` sources** (`RealLibFiles.files`, **2.39 MB**, 60-odd files, the
  largest 2.35 MB) — real TypeScript written by the TypeScript team for their own purposes, already
  embedded in this repo since the real-lib migration, so hermetic with **no vendored tree and no
  licensing question**; its weakness is the mirror (a declaration file has no regex and no JSX), which is
  why neither corpus stands alone. (3) The corpus the round was actually developed against,
  `build/bench/tsc-project-*`, is a **local artifact** and therefore lives in a RUNNER, not the suite:
  `scripts/round920-token-gate.sh` + `RealSourceTokenGateMain` **REFUSE with exit 2** on a missing tree,
  a tree with no TypeScript, or a stale class dir (with round 853's positive control that the runner's
  own class is in it). A test reading it would pass quietly in CI, which is precisely rounds 853 and 873.

- **DEFECT A — (BUG.2) IN A SECOND COSTUME, AND IT WAS IN tsc's OWN SOURCE ALL ALONG.**
  `utilities.ts` declares ``const backtickQuoteEscapedCharsRegExp = /\r\n|[\\`\u0000-…]/g;``. The
  context-free loop reads the `/` as a Slash and the **backtick inside the character class** then opens
  a `NoSubstitutionTemplateLiteral` that runs to the next backtick anywhere in the file: a **25,761-
  character token** swallowing the twelve identifiers after it. The sibling `/[\\'…]/g` opens a string
  literal instead, which our scanner terminates at the line break — so the same defect is file-wide for a
  backtick and line-wide for a quote, and only the loud half was ever going to be noticed.

- **THE FIX IS THE MECHANISM WORTH KEEPING: ASK THE PARSE.** A `RegularExpressionLiteralNode`'s `text`
  is `Scanner.getTokenText()` and a `JsxText`'s is what `scanJsxText()` returned, so `pos + text.length`
  is the EXACT end in both cases — no `Node.end` overshoot, nothing to guess. `SourceIndex` now collects
  those two node kinds, emits each verbatim and resumes the scanner past it. **The undecidable question
  is therefore never asked**: whatever the parser decided a `/` was, the index reproduces, so the index
  and the tree it describes cannot disagree — which is a stronger property than any heuristic (a
  "previous token suggests a regex" rule) could have, and it generalises to any future contextual lexeme
  the parser turns into a node. JSX text was added by the same argument one construct over (`<p>it's
  fine</p>`), and a caret inside JSX text now answers `NONE`/`NO_COMPLETION_CONTEXT` rather than
  completing prose as a free name.

- **DEFECTS B-E ARE ALL ONE SENTENCE: A NODE WHOSE SPAN NO DESCENT CAN ENTER.** (B) A parenthesis-less
  arrow's parameter, an index signature's parameter and a `catch` clause's variable were constructed with
  `Parameter`/`VariableDeclaration`'s DEFAULT `pos = 0, end = 0`, so `realEndOf` clamps to `pos` and
  `pathAt` skips them — **328 sites in tsc's 78 compiler sources**, making this the API's single most
  common wrong answer, and none of the 233 `-project` pins saw it because `(y) => …` is fine and only
  `y => …` is not. (C) `declare global`'s `global` name and (D) every JSX tag name carried an **exact**
  end where every other node in this parser carries the end of the FOLLOWING token — so snapping back to
  the token stream lands on the token BEFORE the name, an empty span again. (E) the synthetic `new` of a
  construct signature sat at `[0, 0)` and is now the `new` keyword's own span (`getPos()`, not the
  member's `pos`, which for `abstract new (): T` is the modifier's). Eight one-line parser edits; **core
  pins unchanged at 14,341**, so nothing in the corpus was pinning the wrong spans.

- **BEFORE AND AFTER, MEASURED.** Compiler profile before: **50 of 78 files** violating, 339
  `IDENTIFIER_IS_REACHABLE` and 12 `IDENTIFIER_IS_A_TOKEN` (both capped at 12 per file). All eight
  profiles after: **1,327 files, 101,287,620 characters, 11,299,274 tokens, 3,936,158 identifiers, ZERO
  violations**, longest token 2,259 (a legitimate emit-helper template) against 25,761 before.

- **COST, since the scan changed.** The oracle adds one iterative walk over the file's own AST:
  `SourceIndex.of` over 9,977,097 chars is **358 ms on, 326 ms off = +32 ms, +9.9%** (interleaved arms,
  first round discarded, five recorded). It is paid ONLY by a host's position query — nothing in the
  compile path builds an index — which is why `cost_gate.py` is **+0.00% on all 20 counters** and is a
  control here rather than a gate, exactly as in round 919.

- **THE POSITIVE CONTROL IS IN THE BINARY.** `SourceIndex.of(…, useParseAsLexerOracle = false)`
  reproduces the pre-(GATE.2) scan; it exists solely so the gate has an arm that must redden, because a
  checker that cannot see a broken index reads exactly like one whose subject is correct (round 849).
  Same shape as `--spineMaskOff`. Nothing in `Project` passes it.

- **PINS: +9** (`-project` 233 -> 242; core UNCHANGED at 14,341). Three corpus sweeps, four
  defect-specific pins (each written on what follows the defect, never on the defect itself — the
  failure is not local), one lib-corpus size assertion so a corpus that silently emptied cannot make
  every rule vacuous (round 849), and the OFF-arm control.

- **GATES: suite 14,708 -> 14,717 / 0 failures / 0 errors / 3 skipped**, XML-summed over all six
  modules. `cost_gate.py` **+0.00% on all 20 counters** (46 errors, 78 files — live).
  `huge_methods.py --fail-over 0` clean on core (**745 classes, 15,890 methods, 0 over**) and on
  `-project` explicitly (**24 classes, 249 methods, 0 over**). `spine_closure_audit.py` 46 handlers all
  supersets, run although no `spine*EnterNode` changed. Warning-clean (the 7 `w:` under `--rerun-tasks`
  are the daemon test's pre-existing `Thread.id` deprecations).

- **SUCCESSOR, ranked, and unchanged from round 919's ranking except that (1) is now safer to build
  because its caret resolution is finally trustworthy on real source.** (1) **Signature help** — a
  call's callee resolves through (API.3d)'s receiver path, the argument index is a token-level question
  the completion anchor already answers, and the only new thing is rendering a `Signature`. (2) **The
  refusal backlog as one item** — accessibility filtering, contextual object-literal keys, element
  access, keywords: all four want the same missing where-is-the-caret-in-the-grammar mechanism. (3)
  **Rename** — (API.5) plus an edit plan, and the edit plan is the work. (4) **The incremental seam**.
  **I would take (1).**

**Round 919 (2026-08-18) — (API.5): FIND REFERENCES + DOCUMENT HIGHLIGHTS LAND WITH **ZERO COMPILER
CHANGES**, AND THE ROUND'S REAL PRODUCT IS THE THING THE COST MEASUREMENT WALKED INTO: **THE TOKEN INDEX
BEHIND EVERY POSITION-DIRECTED QUERY THIS ARC HAS SHIPPED WAS DE-SYNCHRONISED BY THE FIRST `${…}` IN A
FILE, AND THE DAMAGE RAN TO END OF FILE** — so on real TypeScript, `nodeInfoAt` / `quickInfoAt` /
`definitionsAt` / `completionsAt` had been answering about a huge enclosing node since round 910. It was
invisible to every fixture because a hand-written fixture rarely carries a substituting template.**

- **(BUG.2), FOUND BY MEASURING RATHER THAN BY TESTING.** The first real-profile run of `referencesAt`
  returned **0** for a caret sitting exactly on `getTypeOfSymbol` in tsc's `checker.ts`, and
  `nodeInfoAt` there answered `Block(44581, 3125407)` — the whole file's body. The cause is one missing
  contextual re-scan: `SourceIndex.scanTokens` ran a bare `Scanner.scan()` loop, so the `}` closing a
  `${…}` read as a CloseBrace, the `|` after it as an operator, and the CLOSING BACKTICK opened a fresh
  `NoSubstitutionTemplateLiteral` running to the next backtick anywhere in the file. **checker.ts
  scanned as 50,684 tokens for 3,151,772 characters, longest token 62,089.** The class KDoc had
  PREDICTED this shape ("it could only go wrong by MERGING … a template head scanned as one whole
  template token") and then priced it wrong — it called the consequence "a COARSER answer", where a
  merge is not local at all: every later node's `realEnd` snaps back below its own `pos`, so `pathAt`
  refuses to enter it. **The general law, now in CLAUDE.md: a SPLIT is a safe approximation of a
  context-sensitive lexer and a MERGE is not, because a merge de-synchronises the stream.** The two
  splitting re-scans (`reScanSlashToken`, `reScanGreaterToken`) stay deliberately absent; only the
  template one is reproduced, exactly as `Parser` does it (a `TemplateHead` pushes, braces inside are
  counted, the closing `}` is re-scanned into a middle or a tail — so nesting works by construction).

- **THE IDENTITY QUESTION WAS VERIFIED BEFORE ANY CODE WAS WRITTEN, and the brief's proposal needed one
  correction.** A whole-program probe (spans for every identifier in every file, one build, print the
  declaration set of each) confirmed: the import alias's own `import { }` clause, every use, and the
  export are ONE set, because `typeCaptureFollowImportAlias` already hops; two merged `interface I`
  blocks give EVERY occurrence the same two-declaration set; three same-spelled bindings over two files
  give three DISJOINT sets. **The correction is that the relation must be INTERSECTION, not equality**:
  a member of a UNION receiver resolves to one declaration PER CONSTITUENT, so `u.p` on
  `{p: string} | {p: number}` would be a different group from a plainly identical `a.p`. Equality is
  the degenerate case every single-symbol position gets.

- **ZERO CORE CHANGES, WHICH IS THE ARCHITECTURAL POINT AND NOT A BOAST.** The grouping key is a set of
  declaration SPANS — a value — so no `Symbol` crosses the boundary and the entire feature sits above
  the compiler in `-project`. `cost_gate.py` is therefore a CONTROL rather than a gate this round
  (+0.00% on all 20 counters is the expected answer when the compiler is not touched at all), and
  `huge_methods.py` matters only on `-project`, where round 909's blind-spot rule applies.

- **THE ONE HOLE, STATED AND PINNED RATHER THAN PAPERED OVER.** A MEMBER's own declaration name is
  bound by no scope and has no receiver, so the capture resolves it to nothing — which is exactly why
  `definitionsAt` documents an empty answer there. It is recovered from the sweep's own evidence (an
  occurrence that resolved TO that span proves the caret is a declaration of that symbol), and the
  recovery deliberately seeds with the ONE matching declaration rather than the whole set the
  occurrence carried: adopting the set would make `p` of `interface A` group with the unrelated `p` of
  `interface B` merely because some `u.p` may refer to either (**arm A5, 1 red, uniquely its own**).
  What survives is exactly one truthful gap — **a member declared and NEVER USED answers EMPTY rather
  than a list of one**, where tsc answers one — and it has its own pin saying so.

- **READ-vs-WRITE IS REFUSED, and the reason is round 913's pattern rather than laziness.** `x = 1` and
  `x++` are trivially writes; `[x] = pair`, `({ x } = o)` and `for (x of xs)` are writes whose
  identifier sits under an array literal, an object literal and a `for` head. A rule built from the
  easy positions reports the destructuring ones as READS — and a host cannot tell a complete answer
  from an incomplete one, which is worse than no field. `isDeclaration` IS reported, because it is
  exact: membership in the declaration set the compiler produced, never a guess about which parent
  kinds declare a name.

- **MEASURED ON THE REAL PROFILE** (78 files, 9,977,097 chars, **381,670 identifiers**, real libs,
  warm, outside Gradle at `-Xmx4g`): plain rebuild **5.5-5.9 s**; `documentHighlightsAt` on
  `checker.ts` (125,289 of those identifiers) **6.0-7.2 s**, one build; `referencesAt` **8.3-9.9 s** on
  a clean project (one build) and **13.0-13.5 s** on a dirty one (two — `files`' build has to run
  first, because the program's file list is a question only a build answers). **The sweep is 2.5-4 s on
  top of the rebuild WHATEVER the caret**: a local of `createTypeChecker` with 168 hits in one file and
  `SyntaxKind` with **9,827 hits across 49 files** cost the same, because the cost is resolving all
  381,670 identifiers. **Peak heap ~1.9 GB** — the Gradle test JVM's 512 MB OOMs, which is itself worth
  documenting for a host. Key spread needed no work: round 914 already routed both packers through
  `packIdPair`.

- **PINS: +24** (`-project` 209 -> 233; core UNCHANGED at 14,341). 19 reference pins + 5 (BUG.2) pins.
  **THE DISCRIMINATOR, written first**: three bindings, one spelling, two files — a body local, a
  file-level `const` and an export elsewhere — asserted as three EXACT, DISJOINT sets, because a name
  match answers a six-element set for all three carets and a SIZE assertion would be satisfied by any
  three of them. **ONE PIN WAS WRITTEN VACUOUS AND CAUGHT BY ITS OWN ARM**: `no token swallows the text
  between two template literals` asserted `pathAt(offset).isNotEmpty()`, which is true for EVERY offset
  inside a file (the source file always answers) — arm A6 left it green. It is now `every identifier in
  the file is reachable by a descent to its own position`, which is the property the de-sync destroys,
  and A6 reddens it.

- **EIGHT-ARM ABLATION, one mistake at a time (round 807), each dry-run for a real diff (round 902),
  restored from a sha256-verified snapshot and never `git checkout` (round 851). All eight compiled;
  ALL EIGHT reddened a DISTINCT set.** **A1** identity by NAME instead of by declaration set (the grep
  arm) -> **3 red**, including the discriminator. **A2** a document highlight does not restrict to its
  file -> **1 red**. **A3** an occurrence reports the RAW `Node.end` -> **1 red**, the span-exactness
  pin. **A4** the caret-IS-a-declaration recovery removed -> **2 red**. **A5** that recovery adopts the
  whole set the occurrence carried -> **1 red, uniquely its own** (the union pin). **A6** (BUG.2) the
  template re-scan removed -> **4 red**. **A7** the import-alias hop removed (core) -> **6 red**,
  spanning three test classes. **A8** only the FIRST declaration of a symbol recorded (core) -> **4
  red**, the merged and overloaded rows. `scripts/round919-ablate.sh`.

- **GATES: suite 14,684 -> 14,708 / 0 failures / 0 errors / 3 skipped**, XML-summed over all six
  modules and re-run on the byte-restored post-ablation tree. `cost_gate.py` **+0.00% on all 20
  counters** (a control — the compiler was not touched — and proven live by its own 46-error / 78-file
  compile). `huge_methods.py --fail-over 0` clean on core (**745 classes, 15,890 methods, 0 over**) and
  on `-project` explicitly (**22 classes, 236 methods, 0 over**; the largest new method is
  `referencesOf` at 877). `spine_closure_audit.py` 46 handlers all supersets, run although no
  `spine*EnterNode` changed. Warning-clean under `--rerun-tasks`.

- **SUCCESSOR, ranked.** (1) **Signature help** — the biggest remaining editor feature, and its
  mechanism is already three-quarters built: a call's callee resolves through exactly (API.3d)'s
  receiver path, the argument index is a token-level question the completion anchor already answers,
  and the only new thing is rendering a `Signature`. (2) **The refusal backlog as one item** — member
  completion's accessibility filter, contextual object-literal keys, element access `o["p"]`, keywords:
  all four want the same missing mechanism (where the caret sits relative to a declaration / a grammar
  production), so they are one round rather than four. (3) **Rename** — it is (API.5) plus an edit
  plan, and the edit plan is the work (`{ p }` and `import { p as q }` do not rewrite like a plain
  occurrence). (4) **The incremental/re-entrant seam** (`docs/ARCHITECTURE-RETHINK.md`) — the right
  end state and the wrong next step: every figure above is dominated by a full rebuild, so it is the
  only thing that changes the cost model, and it is also the change most likely to cost a month. **I
  would take (1).**

### QUEUE — work top-to-bottom; promote unblockers per protocol

**WORK ORDER NOTE (restored 2026-08-14, round 903).** This section had been ARCHIVED out of the file
during a trim, and nothing noticed for ~15 rounds because rounds 886-902 were self-directing: each
session note named its own successor. **Round 902 ended with a CLOSURE and named none, so round 903
opened with no pool at all** and had to rebuild one by surveying `docs/perf/`. That is the failure
this section exists to prevent. **A round that refuses a candidate must leave at least one named
successor here, with its price and its next instrument** — a refusal is a successful round only if
the arc can continue from it.

**THE LIVE ARC IS (API.\*), ON OWNER DIRECTIVE (2026-08-17, round 909): DELIVER THE PROJECT AND
LANGUAGESERVICE EMBEDDING APIs.** It takes precedence over the (WARM.\*)/(SPINE.\*) perf items below,
which round 908 closed out anyway — the checker-side pool is empty. Shape decided by the owner: a
**Kotlin embedding API first** (LSP / tsserver protocol layered later, not now), in the new
`xemantic-typescript-compiler-project` module. The perf items stay below as the record; (ART.1) /
(ART.2) remain the only open perf work and (ART.1) has been corrected.

- [x] **(API.1) `Project`: open, diagnostics, in-memory edits — LANDED, round 909.** New module
  `xemantic-typescript-compiler-project` (jvm(), `explicitApi()`, `api(project(":…-core"))`);
  `Project.open` / `configPath` / `files` / `diagnostics()` / `diagnostics(file)` / `updateFile` /
  `deleteFile` / `close()` + `internal OverlayVfs`; 30 pins. **A query on a dirty project is a FULL
  rebuild and that is the compiler's property** — `ProjectCompiler.Result` retains no AST/binder/
  checker — so warmth comes from the CONTENT-keyed `CrawlParseCache` alone. Do not build "incremental"
  on it; the seam does not exist yet.

- [x] **(API.2) Position→node lookup — LANDED, round 910**, in two halves: a public `LineMap` /
  `TextPosition` + `Project.positionAt` / `offsetAt` (which read through the overlay and deliberately do
  NOT build, so a host can convert coordinates on a dirty project for free), and
  `Project.nodeInfoAt` (public, value-typed) over an `internal nodeAt` / `SourceIndex`. 53 pins.
  **The queue entry's "cheap and self-contained" was half wrong**: see the two span findings in the
  round-910 note and in CLAUDE.md — `Node.end` is the end of the FOLLOWING token, so `[pos,end)` is not
  a containment test, and the fix is a token snap-back rather than the sibling arithmetic this entry
  originally implied. **Unblocked by ONE word in core**: `computeParserFlags` is now public, because
  INV.1(e) ("the parse a crawl produces is provably the parse the core would produce") is exactly the
  guarantee an out-of-core parse needs, and duplicating it would be drift no test in the consuming
  module could see. Original entry, for the record:

  <details><summary>original (API.2) text</summary>

  **Position→node lookup, the unblocker EVERY editor feature needs.** There is no
  `getTouchingToken` equivalent anywhere in core: `computeLineStarts` is `private` to `Parser.kt:10119`
  and `positionToLineCharacter` is a private top-level fun (`TypeScriptCompiler.kt:6073`), both
  offset→line only, i.e. the direction diagnostics need and not the one an editor does. Needs: a
  public line/offset map, and a node-at-offset walk (`forEachChild`-driven, narrowest-enclosing, with
  the token-boundary rule tsc's `getTouchingPropertyName` uses). **Cheap and self-contained — it needs
  no checker state**, which is why it comes before quick-info.

  </details>

- [x] **(API.3a) QUICK INFO — LANDED, round 911, AND THE DESIGN BELOW IS NOW CONFIRMED BY MEASUREMENT
  RATHER THAN BY READING.** Captured-during-walk vs asked-post-hoc on ONE `Checker` instance: top-level
  annotated `const` **`string` / `string`** (the honest control — post-hoc is not wrong about
  everything), body local shadowing a global **`number` / `string`**, `typeof`-narrowed parameter
  **`string` / `any`**, parameter at its use **`number` / `any`**, arrow-body parameter **`string` /
  `any`**, class-method parameter **`number` / `any`**. **Five of six differ, and the prediction in this
  entry was wrong in the WORSE direction**: the narrowed case does not degrade to `string | number`
  (narrowing merely lost), it degrades to **`any`** — nothing durable binds a parameter at all — which is
  the one answer that is SILENT at every use site, so a post-hoc hover would have looked plausible and
  meant nothing. **THE HOOK'S REAL LESSON, now in CLAUDE.md: a per-node hook on the spine sees NONE of
  the checking ambient**, because the anchors install-and-restore it per dispatch — the position's scope
  is `ctaFrames.last()`, and the capture must reproduce `ctaM3StmtAnchorCore`'s prologue plus
  `withCtaFrameLocals(frame)`. Without that it answered `bodyLocal=string`, `narrowed=any`,
  `parameter=any`. Threaded as an explicit parameter on the `recheckOnly` model (nothing on
  `CompilerOptions`, no process-global mode); node identity is the RAW `(pos, end)` pair, so round 910's
  span semantics stay entirely in `-project`'s `SourceIndex`. **OFF IS FREE and gated as such**:
  `cost_gate.py` +0.00% on all 20 counters, the production cost being one null-valued field read and a
  predicted branch per node, with the NODE as the argument (round 900). Public surface stays value-typed:
  `QuickInfo` + `Project.quickInfoAt`.

- [x] **(API.3b) Go-to-definition — LANDED, round 913.** The entry read: *"the capture mechanism now
  exists and this is the same shape one field over: record the resolved `Symbol`'s `declarations`
  (each a pos/end-bearing node) at the captured position instead of its type, and answer
  `DefinitionLocation(fileName, start, length)`. **Read (API.3a)'s ambient lesson first** — a symbol
  resolved without `withCtaFrameLocals` is the same wrong answer one indirection along."* **The
  premise is WRONG in its most useful sentence, and the correction is the round's product: the
  ambient lesson does NOT transfer, because a definition's walk-scoped input is not the ambient at
  all.** `withCtaFrameLocals` restores `currentLocalTypes`, which holds TYPES and no symbols, so it
  cannot answer "what does this name refer to" for anything. What does is `spineCurrentScope` — the
  INV.2(c) lexical chain — and the spine **maintains that per NODE**, pushing it BEFORE a node's own
  enter handlers, so it is already correct at an arbitrary node and needs no reconstruction. What
  (API.3a) and (API.3b) genuinely share is only that both inputs are gone once the walk is over
  (`spineScopeClear` nulls the chain per file), which is what still makes capture mandatory:
  post-hoc, a body local resolves to a same-named FILE-LEVEL const and a parameter to nothing at all.
  Landed: `CapturedDefinition`/`CapturedDeclaration` in the core (recorded by the SAME hook as the
  type — one request, two facts), `DefinitionLocation` + `Project.definitionsAt` in `-project`,
  import-alias hop through `resolveImportedSymbolGeneral`, and an exact NAME span computed in the
  core by a forward token scan of the declaring file's own text. **19 pins, four-arm ablation, all
  gates green.**

- [x] **(API.3c) Batch a whole file's spans into ONE build.** The core `TypeCaptureRequest` already
  takes a SET of spans and `Project.quickInfoAt` deliberately does not cache its build (a capture build
  types nodes the checker had no reason to type, so its diagnostics are not reusable — pinned). So
  "semantic info for file X" is already one compile away from being one compile; exposing it turns
  hover-per-keystroke from N builds into 1. **This is the item that makes the API practical for an
  editor** and it needs no new mechanism. **LANDED round 914** —
  `Project.semanticsAt(fileName, offsets)` (the primitive) and `Project.fileSemantics(fileName)` (the
  sweep, expressed on it), answering `SemanticInfo(start, end, kind, quickInfo, definitions)`: ONE
  build for any span count, both answers per span, distinct spans sorted `(start, end)`. Measured
  **1 compile / 100 ms against 34 compiles / 3,373 ms and 68 compiles / 6,209 ms** on a
  34-identifier fixture. **THE PREMISE'S ONE ERROR, and it is the round's technical product: "it
  needs no new mechanism" is true of the CAPTURE and false of its KEY.** `TypeCaptureRequest`'s
  packed `(start, end)` key was left un-finalized with a note saying to finalize it "should a caller
  ever request spans in bulk" — and bulk is exactly what this item is: `Long.hashCode` folds
  `(a shl 32) or b` onto `a xor b`, and a node's `end` is its `start` plus a token or two, so a whole
  file's spans collapse onto a few dozen hashes (measured: **>400 spans onto <40 hashes**, round
  889's defect verbatim). It now goes through `packIdPair`, pinned by a measuring test with a raw-pack
  negative control. **26 pins, all gates green.**

  <details><summary>the design decision, recorded round 910 and confirmed round 911</summary>

  **(API.3) Quick info + go-to-definition — THE DESIGN IS NOW DECIDED BY EVIDENCE: *POSITION-DIRECTED
  CAPTURE*, NOT A POST-HOC QUERY, BECAUSE THE CHECKER'S ANSWER TO "WHAT IS THE TYPE HERE" IS A FUNCTION
  OF WALK-SCOPED AMBIENT STATE AND A POST-HOC CALL WOULD BE SILENTLY WRONG FOR EXACTLY THE INTERESTING
  CASES (round 909, by reading `getTypeOfIdentifier`).** `Checker` does all its work in `init`, so the
  instance still HOLDS its tables afterwards and "hand the Checker back and call `getTypeOfExpression`"
  looks free. It is not: `getTypeOfIdentifier` (`Checker.kt:108777`) consults, IN ORDER,
  `currentLocalTypes` (its own comment: *"populated during TS2322 checking walk"*),
  `currentParamBindingNames`, `currentCheckFileName` -> `fileLocalTypeMaps`, `currentFileLocals`, the
  inference-namespace chain, and only THEN the node-keyed `lookupPerFileForNode`. At rest
  `currentLocalTypes` is an empty `HashMap` (`:636`) and the two `current*` file fields are null, so a
  post-hoc query **skips the first five reads** and falls through to globals. **For a
  FUNCTION-BODY LOCAL that does not merely lose narrowing — it can resolve to an unrelated same-named
  global**, which is the `useCaseSensitiveFileNames` failure documented in that very function
  (a destructured param resolving to another file's function, FP TS2345 x9). Two of the ambient reads
  are FILE-scoped and cheaply re-installable from outside; `currentLocalTypes` is
  STATEMENT-POSITION-scoped, built first-wins as the walk proceeds and deliberately leaking across
  blocks in statement order — **it cannot be reconstructed for an arbitrary position without
  re-walking to that position, which is the whole argument for capture.** So: hand the compiler the
  position(s) BEFORE the build and capture type+symbol at those nodes while the real ambient is
  installed. Correct by construction, and it **batches** — one build can capture every identifier in a
  file, so "semantic info for file X" is one compile rather than N. Cost, stated: a query is a compile
  (~5.2 s warm on tsc's own sources, far less on a normal project, repeats warm through
  `CrawlParseCache`); too slow per keystroke, fine for hover-on-demand.
  **IMPLEMENTATION CONSTRAINT A NEW AGENT WILL OTHERWISE LOSE A ROUND TO: a capture handler is a spine
  handler, so it must extend `SpineDispatch.enterClosure` or round 888's `spineEnterMask` means it is
  NEVER CALLED**, and `python3 scripts/spine_closure_audit.py` must be run after touching any
  `spine*EnterNode`. **PUBLIC SURFACE STAYS VALUE-TYPED** (`QuickInfo(kind, displayString, span,
  docs)`, `DefinitionLocation(fileName, start, length)`) — no AST, no `Symbol`, no `Type`.
  **THE FIRST STEP IS STILL A MEASUREMENT, NOT CODE:** pin the above by asking a post-init `Checker`
  for the type at three positions — a top-level `const`, a function-body local, and a guard-narrowed
  reference — and record which answer wrong. That experiment becomes the regression pin for the capture
  path.

  **THE STARTING FACTS** (unchanged, and they are what make capture cheap): everything an editor needs
  is `private` in `Checker.kt` and nothing hands back live state — `getTypeOfExpression` (`:108501`),
  `getTypeOfSymbol` (`:106667`) and `typeToString` (`:120389`) are all `private fun`, and
  `BinderResult.nodeToSymbol` is public but no `BinderResult` ever escapes a compile. Capture needs only
  an `internal` seam plus a handler; it publishes none of them.

  **THE THREE ALTERNATIVES, AND WHY THEY ARE NOT THE NEXT STEP.** (a) **post-hoc query-shaped** —
  narrow `Checker` entry points answering one question after `init`: **superseded by the finding above**,
  because it is silently wrong for body locals and narrowed references (the ONE hover case a user
  notices is `let`/`const` inside a function). Directed capture is (a)'s cheapness without its defect.
  (b) **snapshot-shaped** — return a `ProgramSnapshot` holding ASTs + binder output + the live
  `Checker`: **REJECTED for now, and the reason is this repo's own history** — it freezes as versioned
  API exactly the structures the perf arc keeps rewriting (rounds 889-908 changed packed-key hashing,
  container types and memo layouts, and moved maps onto `LongKeyMap`/`IntKeyMap`, which deliberately
  have NO iterator). Publishing them constrains the work that just delivered -10.5%. It also does not
  even solve the ambient problem: a snapshot hands back the same post-hoc trap. (c) **the full
  inversion** — a lazy, re-entrant checker (`docs/ARCHITECTURE-RETHINK.md:850` names it as the LSP
  prerequisite): **the right end state and the wrong next step**, the largest job in the repo. Do not
  let hover gate on it — and do not let it be "unblocked" by an API that has already published the
  internals it must change.

  </details>

- [x] **(BUG.1) The compiler disagrees with itself about a lone `\r` — DONE, round 915.** The
  convention is now stated ONCE, as `lineBreakWidthAt` in a new `LineStarts.kt`, and every
  offset→line conversion in the compiler goes through it. The sweep the item asked for found **five**
  such converters where the entry named two, four of them wrong: `Checker.lineStartsFor`, its inverse
  `Checker.posOfLineCol`, `TypeScriptCompiler.positionToLineCharacter` (plus its inline TS2688 twin),
  the `Transformer`'s JSX dev-runtime coordinates (EMITTED output, not a diagnostic), and
  `CompilerOptions.computeLineAndColumn` — which implemented a THIRD convention, `\r` as zero-width.
  `-project`'s `LineMap` was already correct and stays a reimplementation, pinned by a differential.
  **The finding that outlives the fix**: `parseMultiFileSource` — the `// @directive` splitter behind
  the whole generated corpus — begins by replacing every `\r\n` and `\r` with `\n`, so the corpus was
  not merely unlucky, it was structurally incapable of carrying a `\r` to the Parser; only the
  project/`Vfs` path can, which is the path the `(API.*)` arc sits on. `LineTerminatorConsistencyTest`
  (core) + `ProjectPositionTest`'s lone-`\r` differential are the gate; 5 pins redden under ablation.

- [x] **(API.3d) Member go-to-definition — LANDED, round 916.** The gap round 913 recorded
  deliberately: *"a scope lookup of a member name finds whatever unrelated binding happens to share
  the spelling, and a confidently wrong navigation target is worse than none. Member definitions need
  the receiver's type resolved and its property symbol found, which is a separate mechanism and not
  this one."* It is now that separate mechanism, in the SAME capture hook and with no new public type:
  `typeCaptureMemberSymbols` resolves a member name through its RECEIVER and hands the resulting
  symbols' declarations to the existing `CapturedDeclaration` path, so a member answer is simply a
  non-empty `definitions` list where one used to be empty. **ANSWERS**: `o.p` / `o.m()` / `this.p` /
  `super.p` / `C.staticP`; a member of an IMPORTED interface (in the declaring file); an INHERITED
  member (the BASE's declaration); a MERGED member (one location per contributing declaration); a
  member of a UNION or INTERSECTION receiver (one per constituent, in constituent order); `N.x` and
  the qualified TYPE `N.T` for a namespace, module alias or enum; a LIB member (in `lib.*.d.ts`, the
  policy `definitionsAt` already documented for a free name). **REFUSED, each with a reason in the
  KDoc**: an element access (`o["p"]` — the argument is a literal, and only identifiers are offered a
  definition); an object-literal key being declared (`{ p: v }` — the useful target is the CONTEXTUAL
  type's property, a third mechanism); a member's own declaration name (it already IS the
  declaration); a chained namespace segment (`A.B.x`); an unresolvable member (silence, never the
  nearest same-named anything). **THE ROUND'S TWO FINDINGS**: the ambient the hook already installs is
  exactly enough — `this` needed `currentClassForThis`, which round 911's install already restores and
  which is deliberately NULL in a static member — and going through the compiler's own
  `resolveStructuredTypeMembers` rather than a hand-rolled table read is what makes the inherited and
  generic cases right for free. **13 pins, five-arm ablation each reddening a DISTINCT set, all gates
  green.**

- [x] **(API.4a) The completion ANCHOR + MEMBER completions — LANDED, round 917.** (API.4) was
  decomposed rather than taken whole; this is the standalone half that needed the genuinely new
  mechanism. **THE ANCHOR** (`SourceIndex.completionAnchorAt` / `CompletionAnchor`, `-project`, where
  round 910's caret already lives) answers a TOKEN-level question, because a completion request has no
  node at the caret by construction: it reports a `CompletionKind` (MEMBER / FREE_NAME / NONE), the
  typed PREFIX, and a replacement span covering the whole word rather than only the prefix. **The
  recovery rule for an incomplete `o.` is that there is nothing to recover**: this parser's `Dot ->`
  arm always builds a `PropertyAccessExpression`, synthesizing a zero-width `Identifier("")` and
  reporting TS1003, so the receiver is a real node at end of file, before a `}` and across a newline
  alike — the anchor descends to the character BEFORE the dot and walks back out to the access whose
  own dot that is (`realEnd(expression) <= dotStart < name.pos`, which at most one node in a path can
  satisfy). A `.` the parse did not turn into an access answers empty rather than guessing a receiver
  from bracket-balanced text. **THE MEMBERS** ride (API.3d)'s resolution one question wider —
  `TypeCaptureRequest.memberSpans` (a SECOND span list, so `fileSemantics` never enumerates) ->
  `CapturedMembers` / `CapturedMember(name, kind, typeText, optional, readonly, accessibility)`.
  **`Project.completionsAt(fileName, offset): CompletionList`.** Free names are an explicit
  `CompletionRefusal.FREE_NAMES_NOT_IMPLEMENTED`, never a silent empty list.

- [x] **(API.4b) FREE-NAME completions — LANDED, round 918; KEYWORDS REFUSED with a reason.** It did
  land by deleting one refusal: `CompletionRefusal.FREE_NAMES_NOT_IMPLEMENTED` is gone and no
  signature moved. **THE MECHANISM** is a THIRD span list (`TypeCaptureRequest.scopeSpans` ->
  `CapturedScope` / `CapturedName(name, kind)`), unioned into `keysByFile` exactly as `memberSpans` is,
  and it is the ONE capture that also admits a NON-`Expression` node — a free caret is anchored at the
  innermost node ENCLOSING it, routinely a Block or the source file. **THE ENUMERATION IS
  `spineScopeLookup`'s OWN WALK, RUN TO EXHAUSTION** — every level's `symbols` then its `existing`,
  innermost first, first sighting wins — then the merged/lib GLOBALS filtered through
  `globalsForFile` (INV.3(c)). That identity is the correctness argument: *a name the list offers is a
  name `definitionsAt` will resolve, and a name it hides is hidden because something nearer binds the
  spelling.* **TWO DIVERGENCES FROM THE ENTRY AS WRITTEN, both deliberate and both ablated.** (i)
  `LexicalScope.existing` IS read: round 748's `symbols`-only rule is about a RESOLVER whose soundness
  is that it cannot change how an existing name resolves, and an enumeration reading `symbols` only
  offers no file-level declaration and no import at all (arm A5, 8 red). (ii) `lexLevelHasName`'s
  UNTRUSTED-level skip is NOT applied: it belongs to a chain with a second, export-filtered threaded
  population to fall back on, and this chain has none — applying it answers nothing inside every
  namespace body (arm A3, 1 red, uniquely its own). **A FREE-NAME ITEM CARRIES NO `typeText`**, decided
  on measurement: at a caret in a real file of the compiler profile the list is **1,628 items**, the
  enumeration itself **0.39-0.64 ms**, adding a type to every item **+2.6-14.3 ms** — and **618 of
  1,629 (37.9%) would render `any`/`error`**, because a free name may name a TYPE. **KEYWORDS ARE
  REFUSED**: a useful list is context-sensitive and the anchor is token-level, so an unconditional one
  offers items that do not compile — the thing the member half already refuses to do. **22 pins**
  (18 `-project`, 4 core `ScopeCaptureMeasurementTest`), **seven-arm ablation, six DISTINCT sets**;
  A7 (drop the writable-name filter) read **0 red** and is recorded in-file as an UNDISCRIMINATED
  guard rather than claimed. All gates green.

  **WHAT IS ALREADY YOURS, do not re-derive it.** The anchor: `completionAnchorAt` already returns
  `FREE_NAME` with the correct prefix and replacement span at every free position, and already answers
  `NONE` inside strings, templates, comments and numeric literals — `CompletionAnchorTest` pins all of
  it, including the caret at the very end of the file. The public value types, the refusal enum, the
  `memberSpans` channel and the "off is free" wiring. The build-free short-circuit (a refused kind does
  not compile) — you will be REMOVING that for FREE_NAME, which makes free-name completion a compile
  where member completion already is one.

  **WHAT MUST BE BUILT, and the one structural fact that decides its shape.** The scope chain is
  **CLEARED PER FILE**: `spineCurrentScope` is nulled by the spine's per-file teardown, which is what
  `DefinitionCaptureMeasurementTest` measures — so the enumeration must happen DURING the walk, at the
  requested position, exactly as `typeCaptureRecordDefinition` does. There is no post-hoc option. The
  natural shape is a third span list (`scopeSpans`) beside `memberSpans`, keyed the same way, recording
  a `CapturedScope` at the node the anchor names — and the anchor must therefore hand in a NODE for a
  free position too, which today it does not (it returns `receiver = null`). Deciding WHICH node a free
  caret names is the first sub-problem: the caret is between nodes, so the honest candidate is the
  nearest enclosing statement or block, and its scope is the scope in force for the position.

  **THE SIZE PROBLEM IS REAL AND IS MEASURED.** CLAUDE.md round 902: `LexicalScope.symbols` holds 1.51
  symbols averaged over SCOPES but **290.94 averaged over a real PROBE**, because the ascent walks
  outwards and 35.5% of probes land on levels holding a mean of **815**. A completion list is that
  whole ascent, flattened — so it is hundreds of items on a real program, every one of which costs a
  `getTypeOfSymbol` + `typeToString` if the item is to carry a type the way a member item does.
  **Decide whether a free-name item carries `typeText` at all before building it**; making it optional
  (null for a free name, present for a member) is a strictly additive change to `CompletionItem` and
  is the cheap escape.

  **SHADOWING AND DEDUP.** Innermost wins: a name bound at two levels must appear ONCE, as the inner
  binding, which is the opposite of the member walk's merge (a member declared twice is one item
  merged from both). `lexLevelHasName`'s ascent is the traversal to copy, with its two live rules —
  `LexicalScope.symbols` only, never `existing` (round 748), and the untrusted Module/Enum levels are
  SKIPPED (INV.4(c)(ii)). Keywords are a separate, purely syntactic list keyed on the anchor's
  position and want their own `CompletionItem.kind`.

  **THE PIN THAT DISCRIMINATES** is (API.4a)'s discriminator inverted: a caret inside a function body
  whose local shadows a same-named binding in ANOTHER FILE must offer the local ONCE and must not
  offer the other file's; and the member pins must stay green, i.e. a free-name enumeration must not
  leak into a member position — the failure round 913 refused and round 916's arm A2 catches.

- [x] **(BUG.2) The `-project` token index de-synchronised at the first `${…}` — LANDED, round 919.**
  Found by (API.5)'s cost measurement, not by a test. `SourceIndex.scanTokens` ran a context-free
  `Scanner.scan()` loop and the parser re-scans the `}` that closes a template substitution
  (`reScanTemplateToken`); without that, the `}` reads as a CloseBrace, whatever follows reads as
  operators, and the CLOSING BACKTICK opens a fresh `NoSubstitutionTemplateLiteral` that runs to the
  next backtick **anywhere in the file**. Unlike a SPLIT (which only adds ends and is why the slash and
  greater-than re-scans are still deliberately absent) a MERGE de-synchronises the stream **for the
  rest of the file**, so every later node's `realEnd` snaps back, `pathAt` cannot descend into it, and
  `nodeInfoAt` / `quickInfoAt` / `definitionsAt` / `completionsAt` all answer about a huge enclosing
  node. Measured on tsc's own `checker.ts`: **50,684 tokens for 3,151,772 characters, the longest
  62,089**, and a caret on a top-level function's name resolving to the whole file's `Block`. The fix
  tracks substitution nesting exactly as `Parser` does (a `TemplateHead` pushes, braces inside are
  counted, the closing `}` is re-scanned into a middle or a tail). `TemplateTokenSyncTest`, 5 pins,
  arm A6.

- [x] **(API.5) FIND REFERENCES + DOCUMENT HIGHLIGHTS — LANDED, round 919.** `ReferenceLocation(
  fileName, start, end, isDeclaration)`; **`Project.referencesAt(fileName, offset)`** (the program)
  and **`Project.documentHighlightsAt(fileName, offset)`** (one file). **ZERO core changes** — the
  whole feature is (API.3c)'s batch turned inside out, above the compiler. **THE IDENTITY QUESTION,
  which the brief said to verify rather than inherit, VERIFIED AND ANSWERED: a DECLARATION-LOCATION SET
  is a sound proxy for "the same symbol", but the relation is INTERSECTION, not equality.** Measured on
  a probe fixture before any code was written: the import alias, its `import { }` clause, every use and
  the export are ONE set (the capture's alias hop already unifies them); two merged `interface I`
  blocks give every occurrence the SAME two-declaration set (equality would not split them); three
  same-spelled `collide` bindings over two files give three DISJOINT sets. Equality FAILS on one shape
  only, and it is a real one: a member of a UNION receiver resolves to one declaration per constituent,
  so `u.p` and a single-constituent `a.p` would be different groups. **THE ONE HOLE, stated and pinned
  rather than papered over:** a MEMBER's own declaration name is bound by no scope and has no receiver,
  so the capture resolves it to nothing (which is exactly why `definitionsAt` answers empty there). It
  is recovered from the sweep's own evidence — an occurrence that resolved TO that span proves the
  caret is a declaration — which leaves exactly one truthful gap: **a member declared and never used
  answers EMPTY rather than a list of one** (tsc answers one). Free names are unaffected. **REFUSED
  with reasons:** read-vs-write (`[x] = pair` / `({x} = o)` / `for (x of xs)` are writes under an array
  literal, an object literal and a `for` head, so a rule built from `x = 1` and `x++` reports them as
  READS and a host cannot tell a complete answer from an incomplete one — the same grammar-position
  mechanism keywords are refused for); lib files are not swept for uses; element access. **MEASURED on
  the compiler profile** (78 files, 9,977,097 chars, **381,670 identifiers**, real libs, warm): plain
  rebuild 5.5-5.9 s; `documentHighlightsAt` **6.0-7.2 s** (1 build); `referencesAt` **8.3-9.9 s** clean
  (1 build) and **13.0-13.5 s** dirty (2 — `files`' build first); the sweep is 2.5-4 s on top of the
  rebuild WHATEVER the caret (168 hits in 1 file and **9,827 hits across 49 files** for `SyntaxKind`
  cost the same); **peak heap ~1.9 GB, so 512 MB is not enough**. Key spread needed nothing: both
  packers were already finalized (round 914's `packIdPair`). **19 pins**, eight-arm ablation, **every
  arm a DISTINCT set**. `docs/language-service.md` § 10b.

- [x] **(GATE.2) A REAL-SOURCE INVARIANT GATE for the language-service position APIs — LANDED, round
  920, and it found FOUR MORE DEFECTS on its first run.** (BUG.2) was live for nine rounds behind a
  green suite because **a hand-written fixture for a lexical API does not contain what real source
  contains**; round 919 fixed the template case and did not build the instrument. This is it.
  **`TokenIndexInvariants`** (commonTest) asserts ten rules true of ANY correct implementation — the
  tokens partition the text and the scan reaches EOF; every gap holds only trivia; a string literal
  never crosses a line break; a non-literal token is short; **every identifier the PARSER found starts
  a token of exactly its length** and `realEndOf` answers that end; a descent to an identifier's own
  position reaches it; a path strictly nests; and offset↔coordinate round-trips against an
  INDEPENDENT restatement of round 915's terminator rule. **The parse is the oracle** — it is the
  context-sensitive lexer this index approximates, so a merge is exactly "an identifier with no token
  starting at it". **THREE CORPORA, and the choice is the point.** Hermetic and permanent
  (`TokenIndexGateTest`): an adversarial shape corpus plus **the real `lib.*.d.ts` sources**
  (`RealLibFiles.files`, 2.39 MB of TypeScript nobody wrote for this test, already embedded, no
  vendored tree and no licensing question). Local-only: `build/bench/tsc-project-*` via
  `scripts/round920-token-gate.sh` + `RealSourceTokenGateMain`, which **REFUSES (exit 2) rather than
  skips** — a gate reading a local artifact that passes quietly where the artifact is absent is round
  853's and round 873's failure mode. **FOUND, all four real, all fixed:** (A) **a backtick inside a
  regular expression** (tsc's own `` /\r\n|[\\`…]/g ``) opened a template literal running to the
  next backtick anywhere in the file — a **25,761-character token** that swallowed the twelve
  identifiers after it, i.e. (BUG.2) in its second costume; (B) a **parenthesis-less arrow parameter**,
  an **index-signature parameter** and a **`catch` variable** were built with the default `[0, 0)`
  span, so no descent could enter them — **328 sites in tsc's 78 sources**, the API's single most
  common wrong answer; (C) `declare global`'s **`global`** name carried an EXACT end where every other
  node carries the following token's; (D) **JSX tag names** did the same, and (E) the synthetic
  **`new`** name of a construct signature was at `[0, 0)`. **THE FIX FOR (A) IS THE MECHANISM WORTH
  KEEPING: ask the parse.** A `RegularExpressionLiteralNode` and a `JsxText` each carry their own RAW
  text, so `pos + text.length` is exact; `SourceIndex` collects them and emits them verbatim, resuming
  the scanner past each. The undecidable "does this `/` divide or quote" is therefore never asked —
  whatever the parser decided, the index reproduces, so the two cannot disagree. **AFTER: 1,327 files,
  101,287,620 characters, 11,299,274 tokens, 3,936,158 identifiers, ZERO violations**, against 50 of
  78 files failing on the compiler profile alone before. **COST**: the oracle is +32 ms on 9,977,097
  chars = **+9.9% of `SourceIndex.of`** (358 vs 326 ms), paid only by a host's position query;
  `cost_gate.py` **+0.00% on all 20 counters** because nothing in the compile path builds an index.
  **POSITIVE CONTROL**: `SourceIndex.of(…, useParseAsLexerOracle = false)` is the in-binary OFF arm —
  the shape `--spineMaskOff` has — and the gate's own control asserts it reddens.

- [x] **(API.7) THE SYNTACTIC-ROLE MECHANISM + THREE OF THE FIVE STANDING REFUSALS — LANDED, round
  922.** The backlog was promoted as ONE item on round 921's premise that all five wanted the same
  missing "where is this caret in the grammar" mechanism. **Three did and two did not, which is the
  round's product.** BUILT: `SyntaxRoles` (`-project`), a PULL-BASED parent-chain ascent —
  `referenceUse(node)` for a node's role, `grammarPositionOf(path)` / `keywordsFor(path)` for a
  caret's — plus a sibling ascent in `Checker.kt` for the half of accessibility that needs symbols and
  heritage (the home is decided PER QUESTION, not forced). Pull rather than push on round 875's
  measurement (a maintained status is 11.1x the work); identity comparisons throughout, because AST
  nodes are `data class`es (round 471). **CASHED: (a) member-completion ACCESSIBILITY** — `private`
  only inside the declaring class, `protected` there or in a derived one, statics alike, the ascent
  reaching out of a nested arrow and the heritage walk following an IMPORT; biased PROVE-TO-HIDE, so
  every unknown leaves the member offered, which is the only answer to round 917's stated objection.
  **(b) KEYWORD completions**, bounded explicitly to STATEMENT / EXPRESSION / TYPE positions with
  `await`, `yield`, `super`, `return`, `break`, `continue` and the module-level declaration starters
  each gated, and every continuation keyword refused outright. **(c) READ-vs-WRITE**
  (`ReferenceLocation.use`), with the write set stated completely and `UNCLASSIFIED` as a fourth state
  rather than a default. **STILL REFUSED, with the reason CORRECTED**: an element access (`o["p"]`)
  and a contextual object-literal key (`{ p: v }`) were never blocked on a grammar position at all —
  recognising either shape is one test on the node's parent — and what each lacks is SEMANTIC (a
  capture channel plus member-lookup-by-text; a contextual type, which is walk-scoped and absent
  outright in a ternary branch). **TWO EXISTING ANSWERS CHANGED** and their round-917 / round-918 pins
  were updated in place: member completions no longer include inaccessible members, and a free-name
  list now carries keyword items (`kind = "Keyword"`). **+45 pins** (32 parse-only), **fourteen-arm
  ablation, all fourteen a DISTINCT set**, all gates green. `docs/language-service.md` §§ 10a, 10b.

- [x] **(API.12) COMPLETION INSIDE `o["` — LANDED, round 929; the last query that did not
  answer an element access.** A caret in the string of `o["…"]` is a MEMBER caret whose
  receiver is the expression before the `[`, decided by ONE classifier
  (`SourceIndex.stringMemberAnchorAt`) over (API.9)'s OWN enumeration, so "a string literal
  is a member name only in an element-access position" is one predicate shared by the
  occurrence sweep and the anchor. **Zero core changes**: the member enumeration is round
  917's, so the union rule, the accessibility filter and the `this`/export-table legs came
  for free. **The span is the literal's TEXT, quotes excluded** — tsc's own measured edit
  range and the same span a member rename writes into — and a member whose spelling is not
  an identifier (`"has space"`, `"1abc"`) is offered, which is the reason element access
  exists. **THE ROUND'S PRODUCT is that `StringLiteralNode.isUnterminated` is FALSE for a
  lone `"`** (the parser compares the raw text's last character to its first), so `bag["` at
  end of file — the state a completion request is normally made in — parsed as a terminated
  empty string and used to answer FREE_NAME with the whole lexical scope offered INSIDE the
  string; the anchor checks the arithmetic as well as the flag. **Deliberately refused**, each
  measured against tsc: a TEMPLATE `` o[`p`] `` (which tsc completes — refused because
  (API.9)'s population is string literals only, so a member written that way is one a rename
  cannot find), a caret AT the opening quote, an indexed-access TYPE, and a string completed
  from its CONTEXTUAL type. **That last measurement found a SILENT GAP one layer down: tsc
  counts `` o[`p`] `` as a reference**, so this API's references and rename miss it and do not
  say so — now § 14's gap 6. +26 pins, nine-arm ablation (five distinct non-empty sets, three
  MEASURED-REDUNDANT guards and a two-mistake REACH CONTROL), all gates green.
  `docs/language-service.md` §§ 10a, 14.

- [x] **(API.11) A MEMBER DECLARATION NAME RESOLVES TO ITS OWN SYMBOL — LANDED, round 928;
  the single largest thing refusing a member rename is gone.** A member's own declaration
  name — an interface's, a class field's, a method's, an accessor's, a static's, a
  `#private`'s, a type-literal member's, an enum member's — is bound by no scope and has no
  receiver, so it resolved to nothing: `definitionsAt` answered empty, `quickInfoAt` answered
  `any` (or the COLLIDER's type, (BUG.4) one position over), `referencesAt` answered empty for
  a member never used, and `renameAt` refused whenever another interface declared the same
  member NAME. It now resolves through its **OWNER**, the receiver's exact dual — the fourth
  resolution mechanism (`Checker.typeCaptureMemberDeclarations`). **THE HAZARD THE ITEM NAMED
  IS BIGGER THAN "resolve it to itself"**: round 884's `mergeSingleSymbol` ADOPTS, so a member
  declared in two merged `interface` blocks is one symbol carrying only the SECOND block's
  declaration — measured — and the whole list has to be reconstructed from the OWNER symbol's
  own declarations, each a container. A merged declaration, an OVERLOAD set and an ACCESSOR
  PAIR are therefore one group from any of their declaration names, in every query. Deliberate
  exclusion, in the conservative direction: an object literal's own METHOD, which is outside
  (API.10)'s key leg and stays a loud refusal. +16 pins, two changed meaning in place, nine-arm
  ablation (seven distinct sets; two arms measured REDUNDANT with their reach proved by other
  arms), `cost_gate.py` +0.00%. `docs/language-service.md` §§ 8, 9, 10b, 10d, 13, and the new
  **§ 14, State of the API**.

- [x] **(API.10) ONE SPAN, TWO SYMBOLS — LANDED, round 927; the LAST of round 922's five
  refusals.** A contextually typed object-literal KEY (`{ p: v }`) and both SHORTHANDS
  (`{ p }`, `const { p } = o`) are occurrences of the member the literal's CONTEXTUAL
  type supplies. **The capture still files ONE answer per span** — round 926 read that
  as the structural obstacle and it is not: tsc's relation between a shorthand's two
  symbols is ASYMMETRIC (the member's group CONTAINS the token; a caret ON the token
  answers the LOCAL's group alone), so what was missing was a ROLE.
  `CapturedDefinition` now carries three declaration sets differing in which of
  NAVIGATION / SEED / MEMBERSHIP they hold: `locations` all three, `related` seed +
  membership (the heritage edge, and now an object-literal key's OWN property),
  `shorthand` navigation + membership and deliberately NOT seed. The contextual type is
  computed by a SYNTACTIC walk OUT of the literal (`Checker.typeCaptureContextualType`,
  the dual of round 926's `typeCaptureDestructured`) covering eleven positions read out
  of tsc 7.0.2, because the checker's own contextual type is walk-scoped and `cpaCtxAt`
  stops at every statement edge. `renameAt` expands a shorthand in whichever direction
  it was reached from — `{ renamed: p }` vs `{ p: renamed }`, the round's discriminator,
  since both compile and both are one edit. **Still refused**: a second declaration of
  the same member name (pre-existing, and the named successor), a shorthand whose member
  cannot be placed, and a computed key. +19 pins, ten-arm ablation (nine distinct sets;
  A3/A8 share one because the round-925 verification refuses exactly what a wrong
  expansion would write), `cost_gate.py` +0.00%. `docs/language-service.md` §§ 8, 9,
  10b, 10d, 13.

- [x] **(API.9) THE MEMBER OCCURRENCE SET — LANDED, round 926; TWO OF THE THREE KINDS CLOSED
  OUTRIGHT, THE THIRD CLOSED FOR A DECLARED HERITAGE EDGE AND STILL REFUSED FOR A CONTEXTUAL
  ONE.** Round 925 measured a member's occurrence set at 2 spans against tsc's 5 and named the
  three missing kinds. Closed: **(1) a binding element's `propertyName`** (`const { p: local }`
  — a receiver question; the pattern's source is the annotation or initializer one to three
  levels up, `Checker.typeCaptureDestructured`), **(2) an element access `o["p"]`** (a
  POPULATION question; `SourceIndex.occurrenceNodes()` is `identifiers()` plus the string
  literals that name a member, and the edit span is the text BETWEEN the quotes), and **(3) an
  IMPLEMENTOR's member** via `CapturedDefinition.related` — a DECLARED heritage edge, computed
  per OCCURRENCE, which is what makes a `this.p` inside an implementor part of the interface's
  group. **Still refused: a contextually supplied key, and the binding SHORTHAND `const { p }`,
  for the same structural reason** — one span carrying two symbols, which a capture filing one
  answer per span cannot express. `referencesAt`, `documentHighlightsAt` and `renameAt` improve
  together because the set is wired once; `definitionsAt` deliberately does NOT follow the
  heritage edge, because tsc's own go-to-definition on an implementor's member answers that
  member. +20 pins, ten-arm ablation, `cost_gate.py` +0.00%, population 381,670 -> 381,672 on
  tsc's own sources. `docs/language-service.md` §§ 9, 10b, 10d.

- [x] **(API.8) RENAME — LANDED, round 925.** `RenamePlan(oldName, newName, files, refusal,
  conflicts)` / `FileRename(fileName, edits)` / `RenameEdit(start, end, newText)` /
  `RenameConflict(kind, fileName, start, end, detail)` + `RenameRefusal` (11) and
  `RenameConflictKind` (5); **`Project.renameAt(fileName, offset, newName)`**. **ZERO core
  changes** — the whole feature sits above the compiler on (API.5)'s sweep and (API.7)'s parent
  ascent. **STEP 1 WAS tsc ITSELF, and it decided three designs**: `scripts/lsp_rename.py` drives
  `tools/tsgo-7.0.2/lib/tsc --lsp -stdio`'s `textDocument/prepareRename` + `rename` over a
  22-caret fixture and prints the resulting TEXT, so `{ p }` -> `{ p: newName }`, `const { z }`
  -> `{ z: newName }` (local) vs `{ newName: z }` (property), and the lib refusal's exact wording
  were READ rather than reasoned. It also showed **two places to do BETTER than tsc**: tsc
  validates neither the new name (`const class = 1`, `const 1bad = 1`) nor collisions (it writes
  a second `const useZ` beside the first). **THE OCCURRENCE SET WAS MEASURED BEFORE ANY CODE and
  it is NOT complete for members** — on the same fixture tsc's member rename edits 5 spans and
  ours resolves 2, missing a binding element's `propertyName`, an `o["p"]` (a string literal, so
  outside the identifier population by construction) and an IMPLEMENTOR's member (a different
  symbol here). So members are not planned around, they are **refused with the evidence**:
  a spelling scan is used as a SAFETY NET — never as the answer — and an identifier spelling the
  old name that is neither in the group nor resolved elsewhere is a conflict. **The position
  split inside that net is load-bearing**: a member declaration name resolves to nothing, so
  without it an `interface I { p }` anywhere would refuse renaming an unrelated local `p`.
  **THEN THE PLAN IS VERIFIED BY APPLYING IT AND COMPILING AGAIN** (a scratch `OverlayVfs` around
  the project's own, so nothing is observable): it must re-read, it must add no diagnostic
  (**the COLLISION check**), and every renamed occurrence plus every identifier that ALREADY
  spelled the new name must resolve to exactly what it resolved to before (**the CAPTURE check** —
  renaming a file-level `a` to `b` where a body holds its own `b` compiles, produces no
  diagnostic anywhere, and means something else; arm A4 is the only thing that sees it).
  **ONE MEASURED DESIGN CORRECTION**: the expectation for a renamed occurrence is its OWN prior
  answer, not the seed — demanding the seed reports this API's own blind spot (a member's
  declaration name resolves to nothing) as a change of meaning, and refused three correct member
  renames before it was fixed (arm A10). **DIVERGENCE FROM tsc, stated**: a bare `export { p }` /
  `import { p }` is replaced PLAINLY where tsc expands to `newName as p` — our identity crosses
  the alias hop, so the local and the export are one symbol and the whole group renames together;
  expanding would make `export { p }` behave differently from `export const p`. **REFUSED, each
  with a reason**: a declaration in a library, an ALIASED import (`import { a as b }` — one new
  name cannot spell two things, and tsc picks by caret because it has two symbols), an unresolved
  import, a caret on either half of an `as`, a reserved or malformed new name (**no build**), and
  a member whose set cannot be shown complete. **PINS +35** (`-project` 390 -> 425; core UNCHANGED
  at 14,341) — 14 parse-only shape pins written FIRST. THE DISCRIMINATOR is the shorthand, asserted
  as the exact resulting TEXT of both lines, because a plain rewrite passes every count-based
  assertion and renames the object's key. **APPLY-AND-RECHECK** pins apply the plan through
  `updateFile` and assert the diagnostics are byte-identical — an independent oracle of the
  verification `renameAt` runs internally. **TWELVE-ARM ABLATION**, one mistake at a time, anchored
  replacements with an asserted occurrence count, restored from a sha256-verified snapshot.
  **GATES**: suite 14,865 -> **14,900 / 0 failures / 0 errors / 3 skipped = exactly the +35**;
  `cost_gate.py` **+0.00% on all 20 counters** (a control: no core change);
  `huge_methods.py --fail-over 0` clean on core and on `-project` explicitly. **MEASURED ON tsc's
  OWN SOURCES**: renaming `SyntaxKind` in `types.ts` produces **9,827 edits across 49 files** in
  23.9-24.5 s warm (against `referencesAt`'s 10.6-16.0 s); `createTypeChecker` is 3 edits in
  13.3-14.3 s. `docs/language-service.md` § 10d; harness `RenameCostMain`.

- [x] **(BUG.4) Quick info on a MEMBER NAME reports the wrong type, for every receiver — FIXED,
  round 924.** The item said it reports `any`; **measured against tsc 7.0.2's own LSP it reports
  the type of whatever unrelated binding shares the member's spelling**, and `any` only where
  nothing does — 16 of 23 wrong member positions read a collider, 6 read `any`, one was right by
  coincidence. **The fix is tsc's own rule**: `getTypeOfSymbolAtLocation` moves off the right-hand
  side of a property access ONTO THE ACCESS, so the type of the `p` in `o.p` is the type of `o.p`
  — and a probe of exactly that, measured before any design was committed, was already correct for
  the generic instantiation, the inherited member, the union receiver, the type-parameter receiver,
  the static side, the enum and namespace members and the flow-NARROWED member, because
  `computeRawTypeOfPropertyAccess` implements all of them. So the landed fix contains **no member
  walk**: the brief's carrier route was the right instinct at the wrong altitude, and a member-table
  read is exactly what arm A2 shows failing (the two generic pins plus narrowing). The ONE receiver
  needing (API.3d)'s carrier is `this`/`super`, which are plain identifiers in this parser and type
  as `any`; the leg is ADDITIVE, so where it cannot decide the access answers `any` rather than a
  wrong name. **NEIGHBOURS CASHED**: an element access `o["p"]` (the caret is on the literal, whose
  own `string` made the old answer right only by coincidence) and a qualified TYPE name `N.T`
  (through the export table). **STILL REFUSED**: an object literal's own key, on round 922's
  unchanged contextual-type ground. **THREE tsc DIVERGENCES named rather than asserted away**:
  `this` in a static member (`typeof C` is unmodelled), an object-literal member's literal widening,
  and a type rendered under a synonymous alias.

- [x] **(BUG.3) A caret on `this.` inside a NESTED ARROW answers NO members — FIXED, round 923.**
  **THE LAYER QUESTION WAS THE ITEM, AND THE ANSWER IS CAPTURE-ONLY.** Settled by MEASUREMENT before
  any code: a 24-line fixture covering `this` in a method, an arrow, an arrow inside an arrow, a
  `function` expression and declaration, an object-literal method, a getter, a setter, a constructor,
  a property initializer, a static member and a class expression, compiled through the ORDINARY
  diagnostic path, gives **17 diagnostics byte-identical to tsc 7.0.2** — so the CHECKER binds `this`
  in a nested arrow exactly right and the compiler-correctness worry this item raised is answered NO.
  The defect was `typeCaptureVisit` installing `currentClassForThis = frame.classForThis`: a cta
  frame is a TYPE-checking context and does not thread `this`, so the frame an arrow BODY pushes
  carries null. Fixed by **`typeCaptureThisClass`**, a pull-based ascent transparent to arrows and
  opaque to every other `this`-binder — deliberately NOT round 922's `typeCaptureEnclosingClass` (the
  accessibility question, which would answer inside a `function`) and deliberately NOT the checker's
  own `spineCaClassCtx` (right shape, bug-compatibly transparent to a nested `FunctionDeclaration`,
  the one arm where reusing it verbatim fails). Bias PROVE TO OFFER. **Side findings, stated not
  fixed**: an EXPRESSION-bodied arrow already worked (a cta frame is pushed at a `Block` enter, so
  such an arrow pushes none), and **quick info on a member NAME is a separate RECEIVER-INDEPENDENT
  gap** — `o.p`, `this.p` in a method and `this.p` in an arrow all report `any` — so the brief's
  "they share the path" is false; promoted to the successor ranking instead. **+20 pins**,
  **seven-arm ablation** (five distinct sets, one measured-redundant guard, one redundancy
  demonstration), suite 14,818 -> 14,838, `cost_gate.py` +0.00%, **8-profile grid `added=0 removed=0`
  against a rebuilt HEAD binary**. `docs/language-service.md` § 9.

- [x] **(API.6) SIGNATURE HELP — LANDED, round 921.** `SignatureHelp(signatures, activeSignature,
  activeArgument)` / `SignatureInfo(label, parameters, returnTypeText, activeParameter)` /
  `ParameterInfo(name, typeText, optional, isRest, labelStart, labelEnd)`; **`Project.signatureHelpAt(
  fileName, offset)`**, null when the caret is in no argument list and an EMPTY signature list when it
  is in one whose callee has none. A FOURTH capture list — `TypeCaptureRequest.signatureSpans:
  List<SignatureCaptureSpan>`, the only one carrying a payload beyond the span, because the ACTIVE
  ARGUMENT is a property of the COMMAS and `f(a, |)` parses to a call with one argument.
  **THE PREMISE — "three-quarters built" — HELD FOR THE CALLEE AND WAS WRONG ABOUT THE ANCHOR.**
  `getCalleeType` + `getCallSignaturesOfType` answered a method through a receiver, an import, a
  callee that is itself a call and a decorator factory with no rule of their own, exactly as ranked;
  what the completion anchor did NOT already answer is which call and which argument, because
  **signature help is the first query in this arc whose subject is a REGION the parse carries no node
  for**. Three shapes defeat containment: `f(a, b|)` is at the real END of `b` (half-open, so outside
  it) and yet is argument 1; `f(a, |)`'s second argument does not exist in the tree; and for `f(` at
  EOF or `f(a,` before a `}` the call node's own real end lies BEFORE the caret, so no descent reaches
  it. **THE PARSER RECOVERY WAS READ OUT OF `Parser.kt` BEFORE ANY CODE, as round 917 did**:
  `parseArgumentListWorker` breaks on end-of-file and on a `}` and then runs `parseExpected(CloseParen)`,
  so the `CallExpression` EXISTS in every one of those shapes — which is what makes a token-level
  anchor possible at all. So the region is **bracket-matched over the token stream** (stopping early at
  a closer that does not match the top of the stack — an unmatched `}` means the enclosing block is
  closing) and the index is **a count of this list's own commas**, where "its own" is decided by
  testing the ARGUMENTS' spans: a comma inside a nested call, an object literal or a
  `Map<string, number>` type argument is excluded by ONE test, with no per-construct rule and no need
  to lex `<`/`>` (arm A8, 4 red). **THE ACTIVE-SIGNATURE RULE, stated so it can be argued with**: the
  FIRST signature that could still become this call — room for the caret's argument (its index is
  within the parameter list, or the signature ends in a rest, or it takes none and none were passed)
  AND `signatureAcceptsArgs` over the arguments already FINISHED, which is the same verdict
  `resolveCallOverload` selects with, so a host's highlighted overload and the compiler's chosen one
  cannot drift. The argument the caret is IN is deliberately not judged — half-typed by construction,
  so judging it would flip the highlight under the user's hands. Nothing qualifying answers 0,
  reported not hidden. Arms A6 (always 0) and A7 (arity only) redden different sets, so both halves of
  the rule are load-bearing. **ONE COMPILER-SIDE SURPRISE, FIXED**: a parameter declared with a
  BINDING PATTERN is dropped from `Signature.parameters` by `getParameterSymbols` and the survivors
  keep a POSITIONAL zip of the declaration's annotations, so rendering from the symbols alone prints
  `destructured(tail: { a: number; b: number })` — one parameter short AND wearing its neighbour's
  type, i.e. a plausible-looking lie. The DECLARATION is rendered instead whenever its parameter list
  is longer (arm A10, 1 red uniquely its own). **RENDERING reuses `typeToString`** — hover's renderer —
  and deliberately NOT `signatureToString`, whose `p?: string | undefined` is a TS2345 message
  convention; parameter ranges are recorded AS THE LABEL IS BUILT (arm A11), because searching for
  `name: type` finds the wrong occurrence as soon as one parameter's type mentions another's spelling.
  A GENERIC callee renders UNINSTANTIATED (`pickFrom<T>(xs: T[], index: number): T`) — inferring `T`
  means inferring from arguments that are not finished. **REFUSED with reasons**: tagged templates (no
  parenthesized list), type arguments, `super(...)` (an ordinary identifier here, bound to nothing —
  empty list, pinned), and a spread's arity. **NOT refused, and pinned**: decorator factories and a
  call-callee. **PINS +56** (`-project` 242 -> 298; core UNCHANGED at 14,341) — 30 parse-only anchor
  pins written FIRST, 26 end-to-end. THE DISCRIMINATOR is an OVERLOADED callee asserted as an EXACT
  list of three labels: every shortcut (render the callee's type, take the overload resolution picks,
  match by name) answers ONE and passes every other pin. **ELEVEN-ARM ABLATION, one mistake at a time,
  each dry-run for a real diff and restored from a sha256-verified snapshot; all eleven compiled and
  ALL ELEVEN reddened a DISTINCT set** — A1 outermost call 1, A2 first overload only 1 (the
  discriminator), A3 no rest clamp 1, A4 no receiver path 2, A5 no export-table leg 1, A6
  activeSignature always 0 -> 2, A7 arity-only 1 (a strict subset of A6, distinguished by the pin it
  leaves GREEN), A8 all commas 4, A9 region = the call's real end 6, A10 no declaration render 1, A11
  label ranges not followed 1. `scripts/round921-ablate.sh`. **GATES: suite 14,717 -> 14,773 / 0
  failures / 0 errors / 3 skipped = EXACTLY the +56**; `cost_gate.py` **+0.00% on all 20 counters** — a
  real gate, since `Checker.kt` grew ~370 lines reachable from the hook on the hot walk;
  `huge_methods.py --fail-over 0` clean on core (750 classes, 15,976 methods) and on `-project`
  explicitly (28 classes, 280 methods); `spine_closure_audit.py` 46 handlers all supersets;
  `scripts/round920-token-gate.sh` 1,327 files / 101,287,620 chars / ZERO violations. No wall A/B:
  production executes not one new instruction — every addition sits behind a hook that returns on a
  null per-file key set. `docs/language-service.md` § 10c.

DENOMINATORS, so every % below converts. Last MEASURED warm rebuild **5,242.6 ms** (round 899, per-arm
sd 2.51%); JFR profile denominator **5,429 ms**; **1% = 54.3 ms**. Cross-round: 5,859 (pre-887) ->
5,424 (pre-895) -> 5,243 (HEAD) = **-10.5% over rounds 887-898**. **There has been no wall A/B for
twelve rounds**, and round 899 could resolve 1.88% in SIGN alone — so every item below is a fifth to
a half of what this box can judge and must be defended on counters plus a decomposition, never on a
median. `cost_gate.py` reads +0.00% by construction for all of them.

REFUSAL FLOOR: ~**0.31%** (~17 ms) for a LOW-risk change — round 897 refused there, 898 refused
MEDIUM at 0.13-0.20%, 900 refused at 0.07-0.14% and BUILT at 0.39%, 903 refused at 0.085%.

- [x] **(WARM.31) Residual boxed primitive map/set keys — REFUSED, round 904.** 14 sites,
  **2,698,745 ops/rebuild**, premium **6.58 ns**, so **17.7 ms = 0.334% for ALL of them together** and
  **0.064% for the largest single one**. `docs/perf/boxed-primitive-key-price.md`. **Do not re-open
  from a leaf profile**: the 29.4 ms that ranked it is one draw of a number that reads 72.9 and 19.0 ms
  across round 899's own two dumps of the same binary. A next agent can refuse a NEW boxed-key site
  for free — **population x 6.58 ns**, and a site needs ~1.7 M ops to clear the floor while the whole
  spine visits 856,962 nodes.

- [x] **(WARM.32) The iterator-allocation family — REFUSED, round 905.** 215 sites are **495,305
  calls over 925,502 elements** (mean list length **1.99** / **1.72**; 52.4% of `forEachChild`'s list
  positions are SINGLETON, and `anyIdentical` hits 94.4% so a hit stops the scan). Premiums **11.95 ns**
  and **2.75 ns** per call = **3.90 ms = 0.074%**, refused by 4.4x, and that is an UPPER bound (both
  arms fold into a trivial sink). `docs/perf/iterator-allocation-price.md`. **The census refuses it
  without the amplifier**: 17 ms over 495,305 calls needs 34.3 ns/call, where a WHOLE boxed
  `HashMap<Long, .>` probe is 8.53 ns (round 904). **The sibling project's -3.1% is not contradicted —
  the mechanism transfers and the PRICE does not**, because its population is per-token `withIndex()`
  chains and ours is 2-element lists. LANDED ANYWAY: the 215 sites now route through `walkList` /
  `anyIdentical` in `NodeWalk.kt` (one home, so it cannot be re-opened blind), which shrank
  `forEachChild`'s three (JIT.1) partitions **9,256 -> 5,929 bytecodes (-36%)**.

- [x] **(WARM.33) reach-machinery (b), transpose the 43 per-file memos — REFUSED, round 906, AND THE
  CANDIDATE IS A REGRESSION AT EVERY GEOMETRY.** `docs/perf/reach-memo-transposition-price.md`.
  **The whole memo-LAYOUT direction is closed**: the ceiling for ANY layout is **2.65-15.99 ms**,
  below the floor at every cache geometry, and shrinking the cache makes the candidate worse rather
  than better. **Round 875 had the SIGN wrong** — it read the ascent's scatter onto the probe's
  sequential sweep; measured, **42.2% of ascent steps go to `nodeId - 1`, 89.8% stay within 64 ids**,
  the spine walks in PREORDER so each 1-byte array is swept sequentially, and **layout A already
  answers 97.0% of accesses out of L1** (a line serves ~14.2 consultations against a transposed row's
  ~3.8). **Round 875's queued instrument could never have decided it**: an amplifier repeats one probe,
  so from the second repetition the line is L1-hot — *a locality change cannot be amplified*, and the
  round that priced it contains no clock at all, only a census plus a set-associative LRU model.
  Also corrected: this entry's own "deletes 36.9 MB/rebuild" deletes **55 KB of array headers** —
  43 arrays of n bytes and one of 43n are the same bytes. Adjacent direction closed with it: lazily
  allocating the 17 classifiers consulted <1,000x/rebuild is worth ~2-3 ms.

- [x] **(WARM.34) `lexLevelHasName`, the COUNT question — REFUSED by its own census, round 907, AND
  THE WHOLE FAMILY IS NOW CLOSED.** `docs/perf/lex-ascent-count-price.md`. **The queue's premise was
  wrong**: "an O(depth) ascent revisiting the big outer levels" describes the CHAIN (3.69 steps),
  not the PROBES (**1.544** per ascent), because 58% of level visits are refused by the untrusted /
  non-head-fn rules or are hash-free EMPTY maps — *a chain-step population is not a probe
  population*, round 902's law one step along its own family. **563,466 ascents / 870,231 real probes
  = 31.85 ms = 0.602% is the ceiling on EVERYTHING here.** The 80.7% redundancy is real and does not
  help: a repeat ascent performs **1.32** probes and a memo probe replaces them with **1**, so the
  queued ascent memo is **2.42 ms net, 9.92 ms even if free, and −10.7 ms at the measured probe
  cost — a regression**. A per-level memo is refused BY CONSTRUCTION (*a cache keyed by the same name
  at the same granularity as the map it fronts IS that map*), and a per-file absence filter is
  <= 7.30 ms. **Closure is now GENERAL, not per-lever: any one-operation oracle costing one probe
  recovers at most 0.21%.** Container closed by 901 (+0.26%) and 902 (−0.19%).

- [x] **(SPINE.1) The six spine handlers' frame bookkeeping — REFUSED AND CLOSED, round 908.**
  Denominator re-taken: **5,050 ms** (8 probe-free warm process medians), so 1% = 50.5 ms. The six
  are still 62.6% of the probed spine and **40.1% of the rebuild**, but round 733's deflation,
  MEASURED rather than applied (and with `SpineSections` run WARM for the first time), says the
  passes' own checking work is **91.4%** and every frame pop and restore is at or below one probe
  boundary — five of eleven sections read NEGATIVE once their boundary is subtracted. **Nothing
  clears the floor**: the three ancestor climbs are 19.6 ms (0.39%, refused again), the cta
  frame+ambient install 16.0 ms and load-bearing, the cta eligibility gate 14.4 ms with round 888's
  mask having already taken **87% of its population**. **The one row above 1% — 79.8 ms of
  frame-ambient install — has a ~8 ms deletable population** (the rebuild walks 2.91 frames, produces
  nothing on 91.4% of installs, and the save copies ZERO entries on 100% of 147,572) **and fails its
  own division by ~20x, because a timestamp is an OPTIMIZER BARRIER.** Round 847's per-handler ms are
  superseded — they were against 8,095 ms — and the order swapped again (`ccetSpineLeave` #1 -> #3,
  −51% in ms, while `cpaSpineLeave` fell 5% in ms and ROSE 7.62% -> 11.56% in share: round 830 live).
  **Caveat for any successor: the `dispatch` tier bypasses `spineEnterMask`, so that table prices the
  pre-888 regime and is blind to the lever the region already banked.**

- [x] **(WARM.35) The four round-903 hot-path candidates — ALL REFUSED, round 912, AND THE QUEUE'S OWN
  POPULATION FOR THE LARGEST OF THEM WAS A TRANSCRIBED SOURCE COMMENT.**
  `docs/perf/round912-candidate-census.md`. Priced by census plus round 896's divide-and-refuse —
  **no fix built, no amplifier needed**; both census processes agree to the last digit on all 22
  counters and `mappedNodeTypeKey calls = 110,780` reproduces `cost-counters.txt`'s
  `typeNode.bypassed` exactly, which is a second independent control. Against the stated 5,242.6 ms
  denominator (1% = 52.4 ms, the ~17 ms floor = 0.324%):
  **`mappedNodeTypeKey` key build — 25,987 keys of 110,780 calls = 9.36 ms = 0.179%, refused by
  1.8x**; **`narrowTypeFromFlow`'s default-arg `NarrowFlowMemo` — 31,768 = 4.77 ms = 0.091%, by
  3.6x**; **`collectTypeofGuardNames` &c `LinkedHashSet` — 22,798 = 1.48 ms = 0.028%, by 11.5x**;
  **`spineOsWithAmbient` / `spineTcDispatchWithAmbient` — 2,841 = 0.28 ms = 0.005%, KILLED BY READING,
  by 60x**. **ALL FOUR TOGETHER are 15.9 ms = 0.303%, still under the floor for ONE low-risk change.**
  To reach 17 ms they would need **654 / 535 / 746 / 5,983 ns per operation**, against a measured
  **15.09 ns** for a whole `HashMap` get that recursively hashes AND `equals` a 2.76-node AST subtree
  (round 903). **DO NOT RE-RAISE ANY OF THE FOUR.** Three mechanism findings outlive the prices:
  **(a)** the "~88 k/rebuild" this queue attached to `mappedNodeTypeKey` **was never a measurement** —
  it is a transcribed KDoc that is itself 26% stale (real call count **110,780**) applied to the wrong
  quantity (only **25,987**, 3.4x fewer, build a key; 76.5% exit at the foreign-file gate first), so
  the entry was wrong in both directions at once; **(b)** candidate 3's `inline` **is not expressible
  in Kotlin** — both wrappers hand `block` to a RECURSIVE non-inline callee, so `inline` forces
  `noinline`, which re-materialises the lambda, i.e. a candidate can be dead on grounds of the
  LANGUAGE before any population is counted, and reading the CALLEE rather than the wrapper is what
  shows it; **(c)** candidate 4's obvious shared-memo fix is a **SOUNDNESS bug, not merely a small
  prize** — `narrowTypeFromFlowCore` handles re-entrant walks at `narrowLiveDepth == 0` by design, so
  a shared instance would be cleared under a live outer walk and a wrong serve there is a WRONG
  NARROWED TYPE; and **34.2%** of memos outgrow 32 slots, so `clear()` is not obviously cheaper than
  the allocation (round 899: price a container swap NET). **NEW REUSABLE CONSTANT, the allocation twin
  of round 904's ~1.7 M map-ops bar: a pure-allocation candidate needs > 113,000 allocations/rebuild
  at a generous 150 ns, or > 340,000 at a realistic 50 ns, to clear the ~17 ms floor** — which refuses
  most per-node allocation candidates by arithmetic, the whole spine visiting 856,962 nodes.
  **AND THE ONE THING THE AUDIT NEVER NOTICED, still under the floor:** `mappedNodeTypeKey` spends
  **110,780 parent-chain climbs plus 110,780 `String`-keyed map probes (~5.5 ms)** so that 76.5% of
  calls can answer "foreign file" — comparable to the named mechanism, and structurally required by
  the gate; the WHOLE function at these generous rates is ~15 ms, still under the floor.

**SUCCESSOR, PER THE WORK ORDER NOTE ABOVE — a refusing round must name one.** With round 908 closing
the spine side and round 912 pricing the audit residue, **the checker-side pool is empty in the
literal sense: nothing checker-side is left unpriced.** **The successor is the (API.\*) arc, whose
next unchecked item is (API.3b) go-to-definition, with (API.3c) — batching a whole file's spans into
ONE build — as the item that makes the API practical for an editor.** The remaining PERF levers are
ARTIFACT-level and **both are gated, which a next agent must not rediscover**: (ART.1) is gated on the
owner's RELEASE decision and not on engineering (`native.yml` already builds Oracle + PGO and verifies
byte-identity), and (ART.2) is gated on a **CRaC JDK that is NO LONGER INSTALLED on this box**
(`/usr/lib/jvm` holds Zulu 26 and OpenJDK 25; `~/jdks` holds 17 and 21 — none of them a CRaC build), so
neither its `afterRestore` cwd fix nor a re-measurement can be compiled or verified locally.

**THE SEARCH STATE, AFTER SIX CONSECUTIVE REFUSALS (rounds 903-908), AMENDED ROUND 912 — READ THIS
BEFORE PICKING THE NEXT CANDIDATE. THE CHECKER-SIDE POOL IS NOW EMPTY, AND SINCE ROUND 912 IT IS EMPTY
OF UNPRICED CANDIDATES TOO.** 903 refused at 0.085%, 904 at 0.334% (14 sites TOGETHER), 905 at 0.074%, 906
measured a REGRESSION and closed a whole direction, 907 refused by census and closed a family. **Every
candidate ranked off the JFR profile in this arc has come in 2-21x over when measured — nine of ten
in the recorded scoreboard, six of six this session.** Meanwhile 61% of the warm rebuild is
unclassified residue, **no single JFR row is above 1.81%**, and the box cannot resolve below ~1.5%.
**That is what an exhausted search looks like.** It is not a failure — the compiler is -10.5% over
rounds 887-898 and warm xtsc is 2.05x tsc check-only — but a sixth single-row candidate should be
justified against this record rather than picked off a profile.

**THE MEASURED LEVERS THAT ARE *NOT* EXHAUSTED ARE AT THE ARTIFACT LEVEL, AND THEY ARE AN ORDER OF
MAGNITUDE LARGER THAN ANYTHING LEFT HERE.** Both are already measured, not speculative:

- [ ] **(ART.1) Ship the PGO'd native image. -21.2% check-only / -19.1% emit**, 5/5 paired in both
  modes, 46 diagnostics and all 78 emitted `.js` byte-identical (`docs/perf/aot-native-image.md`
  § 10). Needs Oracle GraalVM (`-graal` in SDKMAN; CE's `native-image --help` does not mention the
  word) and an `.iprof` trained on BOTH modes — a check-only-only profile leaves the
  Transformer/Emitter on static heuristics. This is the biggest single lever ever measured in this arc.
  **CORRECTED round 909 — the entry's premise ("CI currently ships the Community Edition arm, which
  has no PGO at all") IS STALE AND MUST NOT BE RE-INHERITED:** `native.yml:60-72` already builds
  **Oracle + PGO** via `scripts/build-native-pgo.sh`, verifies byte-identity against the JVM and
  uploads `xtsc-linux-x64`; `bench.yml` builds the Oracle **BASE** image per push deliberately (the
  PGO cycle is too slow to pay per push for a column that is not the headline). **So the engineering
  exists and what remains is the SHIPPING decision — attaching the binary to releases, already tracked
  as (AOT.1) and explicitly the owner's** (`native.yml:8`). Also **not measurable on the dev box: no
  GraalVM is installed there** (Zulu 26 / OpenJDK 25 only), so any re-measurement is a CI job or an
  install first.

- [ ] **(ART.2) CRaC — ~30 ms restore at FULL WARM SPEED** (6.8-7.3 s against 24-25 s cold, 3.4x,
  output byte-identical bar the `time:` line; `docs/perf/crac-checkpoint.md`). **Blocked on one known
  defect, not on the mechanism**: the restored process keeps the CHECKPOINT's working directory —
  round 873's bug one layer down — so a CRaC CLI must re-install the real cwd through
  `SystemVfs.workingDirectory` in an `afterRestore` hook, exactly as `CompileServer` already does per
  request. Unmeasured risk: the 340 MB image was page-cache-hot in every restore taken so far.
  **CORRECTED round 912 — AND THIS IS ALSO A LOCAL-TOOLING BLOCK, NOT ONLY A CODE ONE: the CRaC JDK
  IS NO LONGER INSTALLED ON THIS BOX.** `/usr/lib/jvm` holds Zulu 26 and OpenJDK 25 and `~/jdks` holds
  17 and 21 — none of them a CRaC build — so neither the `afterRestore` fix nor a re-measurement can
  be compiled or verified locally; it needs a Zulu CRaC install (or CI) first. Do not rediscover this
  by writing the hook and finding nothing to run it on.

**THE ROUND-903 HOT-PATH AUDIT'S FOUR UNPRICED CANDIDATES ARE NOW PRICED AND ALL FOUR ARE REFUSED —
see (WARM.35) above, and do not re-raise them from this block's former wording** (both copies of it
are collapsed into that entry; the record it stood on, "~88 k/rebuild", was a transcribed source
comment rather than a measurement).

**CLOSED IN ROUND 903, DO NOT RE-RAISE** (round 903, `docs/perf/type-node-key-price.md`): the
`nodeTypes` deep AST-value key, **refused at 0.085%** — its premium over a `(file, nodeId)`
`LongKeyMap` is 12.98 ns over 354,131 ops = 4.60 ms, and `A - B` is an UPPER bound. Round 896's
`nodeTypeResolutionInProgress` sentinel falls with it at 1.54 ms. The JFR row's other owner is
`isPerFileDependentRefNode` at 3.70 ms; family 9.04 ms against a 57.1 ms row.
