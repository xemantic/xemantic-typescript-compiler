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

**Round 918 (2026-08-18) — (API.4b): FREE-NAME COMPLETIONS LAND, AND THE ROUND'S PRODUCT IS THAT THE TWO
RULES THE BRIEF TOLD ME TO COPY FROM `lexLevelHasName` ARE **BOTH WRONG FOR THIS CHAIN** — THEY BELONG TO AN
ASCENT THAT HAS A SECOND, THREADED POPULATION TO FALL BACK ON, AND TRANSPLANTED ONTO `spineScopeLookup`'s
CHAIN ONE OF THEM DELETES EVERY FILE-LEVEL NAME AND THE OTHER DELETES EVERY NAMESPACE MEMBER. BOTH
DIVERGENCES ARE ABLATED RATHER THAN ARGUED.**

- **IT LANDED BY DELETING ONE REFUSAL, EXACTLY AS (API.4a) DESIGNED IT TO.** `FREE_NAMES_NOT_IMPLEMENTED`
  is gone; `CompletionList`, `CompletionItem` and `Project.completionsAt`'s signature did not move. The
  mechanism is a THIRD span list — `TypeCaptureRequest.scopeSpans` -> `CapturedScope` /
  `CapturedName(name, kind)` — unioned into `keysByFile` the way `memberSpans` is, so the per-node hot-path
  guard is byte-identical and `cost_gate.py` reads **+0.00% on all 20 counters**.

- **THE ONE STRUCTURAL NOVELTY: THIS IS THE FIRST CAPTURE THAT ADMITS A NON-`Expression` NODE.** A
  completion caret is BETWEEN nodes by construction, so the anchor is the innermost node ENCLOSING it —
  `pathAt(offset).lastOrNull() ?: sourceFile` — which for `function f() { const b = 1;\n  <caret> }` is the
  function's BODY BLOCK, and a function-like's immediate body SHARES its function's scope in the binder, so
  that is exactly the scope holding `f`'s parameters and locals. No special case, no EOF branch: the same
  rule gives the enclosing block on a blank line, the class in a class body and the source file past the
  last character. `typeCaptureVisit`'s `node !is Expression` gate now yields to a scope span and to nothing
  else, so no other capture gained a population.

- **THE ENUMERATION IS `spineScopeLookup`'s OWN WALK, RUN TO EXHAUSTION**, and that identity is the whole
  correctness argument rather than a coincidence: every level's `symbols` then its `existing`, innermost
  first, first sighting wins, then the merged and lib GLOBALS filtered through `globalsForFile` (INV.3(c),
  so one module's exported name is not offered inside another). **A name the list offers is a name
  `definitionsAt` will resolve, and a name it hides is hidden because something nearer binds the spelling** —
  pinned directly, by completing at a caret and then navigating from a use of one of the names offered
  there.

- **DIVERGENCE 1 — `LexicalScope.existing` IS READ, against CLAUDE.md's round-748 rule as the brief quoted
  it.** That rule is about a RESOLVER whose soundness argument is precisely that `symbols` excludes
  everything the main binder bound, so it cannot change how any existing name resolves. An ENUMERATION has
  no such freedom: the source file's own `symbols` holds only the B83.5 block-hoisted leftovers, so a
  `symbols`-only sweep offers **no file-level declaration and no import at all**. `spineScopeLookup` has
  read both deliberately since (API.3b); this reads exactly what it reads. **Arm A5 measures it: 8 red.**

- **DIVERGENCE 2 — `lexLevelHasName`'s UNTRUSTED-LEVEL SKIP IS NOT APPLIED.** That skip exists because the
  aliased Module/Enum table carries all merged members while the unresolved-names walk it serves applies its
  own export filtering — so trusting the level would SUPPRESS a genuine TS2304. It is sound there because
  that chain has a THREADED population underneath it. This chain has none, so the skip answers **nothing at
  all** at a caret inside a namespace body, where every one of the namespace's own members is legally
  writable. The cost of not skipping is stated rather than hidden: a namespace merged across files can offer
  a sibling declaration's non-exported member. **Arm A3 measures it: 1 red, uniquely its own.**

- **A FREE-NAME ITEM CARRIES NO `typeText`, AND THE DECIDING ARGUMENT IS CORRECTNESS RATHER THAN COST.**
  Measured at a caret in a real function body of the compiler profile (78 files, ~10 M chars, real libs):
  **1,628 items**; the enumeration itself **0.39-0.64 ms**; adding a type to every item **+2.6-14.3 ms** —
  i.e. 4-28x the enumeration but noise against the query's own **5.3-8.9 s** warm rebuild. What decides it is
  that **618 of 1,629 (37.9%) would render `any`/`error`/`unknown`**, because a free name may name a TYPE —
  an interface, a type alias, a namespace — for which `getTypeOfSymbol` is not the question being asked;
  decorating 38% of a list with a lie is worse than decorating none of it. (On a two-file toy project the
  COST argument bites too, in the opposite direction from the real one: 2,232 items, enumeration 0.55 ms,
  typing them all 26-170 ms against a whole query of 125-360 ms — **20-75% of the wall.**) The field stays a
  non-null `String` and is `""`, so no signature moved; a host wanting the type of the item its user
  highlighted asks `quickInfoAt`, which is `completionItem/resolve`'s shape.

- **KEYWORDS ARE REFUSED, WITH A REASON AND A PIN.** A useful keyword list is context-sensitive —
  `interface` may start a statement and may not appear inside an expression, `await` only inside an async
  function — and the anchor is a TOKEN-level device that knows what precedes the caret, not which grammar
  production it sits in. An unconditional list would offer items that do not compile, which is the one thing
  the member half already refuses to do (a union receiver offers only members present on every constituent
  for exactly that reason).

- **TWO IMPRECISIONS PINNED AS DECISIONS RATHER THAN LEFT AS ACCIDENTS**: a `let` declared LATER in the same
  block IS offered (a block's bindings are a set, not a sequence — the binding exists and is merely in its
  temporal dead zone, which is what tsc offers too), and a function's body locals are visible from inside its
  own PARAMETER DEFAULTS, because the binder's function scope is flat.

- **PINS: +22** (`-project` 191 -> 209, core 14,337 -> 14,341). **THE DISCRIMINATOR, written first**, is
  (API.4a)'s inverted: a body local SHADOWING a name imported from ANOTHER FILE must appear ONCE and be the
  LOCAL — the wrong answer is not empty and not a crash but the same spelling meaning something else, and
  `kind` separates them (`VariableDeclaration` vs `ImportSpecifier`). **THE SHARP NEGATIVE** is a SIBLING
  scope: another function's local, and a block that closed before the caret — an enumeration built over the
  file's nodes rather than an ascent passes every positive pin and fails only there. The core
  `ScopeCaptureMeasurementTest` is the in-walk-vs-post-hoc measurement, and the answer is sharper than
  either query before it: post-hoc there is nothing to fall through TO — the ascent walks no levels and
  answers with the globals leg alone, so a parameter and a type parameter are simply GONE while the
  shadowed OUTER binding stands where the local should be, under the same name and the same `kind`.

- **SEVEN-ARM ABLATION, one mistake at a time (round 807), each dry-run for a real diff (round 902),
  restored from a sha256-verified snapshot and never `git checkout` (round 851). All seven compiled; SIX
  reddened a DISTINCT set.** **A1** the scope leg never records -> **18 red** (every free-name pin). **A2**
  no shadowing dedup, an outer level overwrites an inner one -> **1 red, uniquely its own** — the
  discriminator. **A3** apply the untrusted-level rule -> **1 red, uniquely its own** — the namespace pin.
  **A4** enumerate POST-HOC -> **14 red**, and it is distinguished from A1 by the four pins it leaves GREEN
  (`the lib globals are offered` and the three rows whose subject survives on the globals leg), which is the
  measurement made a second time. **A5** read `symbols` only -> **8 red**. **A6** no globals leg -> **6 red**,
  uniquely reddening the post-hoc control. **A7** drop the writable-name filter -> **0 red, UNDISCRIMINATED
  and recorded in-file as such** rather than claimed: no non-identifier spelling reaches this fixture's chain
  or globals, so the filter guards a shape the test does not carry, and its sort and dedup assertions are
  pinned by no arm at all.

- **GATES: suite 14,662 -> 14,684 / 0 failures / 0 errors / 3 skipped = EXACTLY the +22**, XML-summed over
  all six modules and re-run on the byte-restored post-ablation tree. `cost_gate.py` **+0.00% on all 20
  counters** — a real gate, since `Checker.kt` grew ~190 lines reachable from the hook on the hot walk, and
  proven live by its own 46-error / 78-file compile. `huge_methods.py --fail-over 0` clean on core (**745
  classes, 15,890 methods, 0 over**) and, per round 909's blind-spot rule, on `-project` explicitly (**21
  classes, 216 methods, 0 over**). `spine_closure_audit.py` 46 handlers all supersets, run although no
  `spine*EnterNode` changed. Warning-clean. No wall A/B: production executes not one new instruction — every
  addition sits behind a hook that returns on a null per-file key set.

- **WHAT THIS LEAVES.** `(API.4)` is complete in both halves. The named gaps are keywords (above),
  contextual object-literal keys (a caret on `{ p| : v }` is answered as an ordinary free name, where the
  useful answer is the CONTEXTUAL type's property — the third mechanism (API.3d) already refused), and the
  fact that a completion is still a FULL REBUILD: **5.3-8.9 s warm on the compiler profile, of which the
  enumeration is under a millisecond.** A host must debounce, and the lever for that is the architecture
  inversion, not this API.

**Round 917 (2026-08-17) — (API.4a): THE COMPLETION ANCHOR + MEMBER COMPLETIONS. THE ROUND'S PRODUCT
IS THAT THE ANCHOR — THE PART EVERY PREVIOUS (API.\*) ROUND CALLED "GENUINELY NEW" — TURNED OUT TO NEED
**NO PARSER WORK AT ALL**, BECAUSE THIS PARSER ALREADY RECOVERS A DANGLING `.` INTO A REAL
`PropertyAccessExpression`; AND THAT THE ONE THING THAT DID BITE WAS A **SPAN COLLISION**, WHERE
`o` AND `o.<nothing>` CARRY THE IDENTICAL `(pos, end)` PAIR AND FIRST-WINS ANSWERS `any`.**

- **THE ANCHOR NEEDED NO PARSER CHANGE, AND THAT WAS READ OUT OF THE SOURCE BEFORE ANY CODE WAS
  WRITTEN.** `Parser.kt`'s `Dot ->` arm is unconditional: when the token after `.` is neither an
  identifier nor a keyword it reports TS1003 and synthesizes `Identifier(text = "", pos = getPos(),
  end = getPos())`, then builds the access anyway. So `o.` at end of file, `o.` before a `}` and `o.`
  before a newline all leave a receiver node in the tree, and the anchor never has to scan raw text
  backwards balancing brackets — which was the design the queue entry anticipated and which would have
  been the round's whole risk. The rule that finds it is exact rather than heuristic: **descend to the
  character BEFORE the dot** (the dot is often the file's LAST token, so its own access node's real end
  is snapped back below it and descending to the dot answers `SourceFile`), then walk back OUT to the
  first `PropertyAccessExpression`/`QualifiedName` satisfying `realEnd(expression) <= dotStart <
  name.pos`. Two dots in one path are at different offsets, so **at most one node can satisfy it** and
  the walk direction does not matter.

- **THE ONE REAL DEFECT, AND IT IS A PROPERTY OF THE CAPTURE DESIGN RATHER THAN OF COMPLETIONS: A RAW
  SPAN DOES NOT ALWAYS IDENTIFY A NODE.** With the buffer ending IMMEDIATELY after the dot (no newline,
  no next token), the synthesized name is zero-width at EOF, so the property access's `end` — read
  after a one-token lookahead that sees only end-of-file — equals its RECEIVER's, and `holder` and
  `holder.<nothing>` are both `(45, 52)`. Preorder reaches the access first, `typeToString` of an
  empty-named property access is `any`, and first-wins then refuses the receiver's own record.
  Measured: `holder.` read EMPTY while `holder. ` (one trailing space), `holder.;` and `holder.\n` all
  read `[alpha, beta]` — a defect visible in exactly one of five shapes. **The fix states the missing
  invariant rather than special-casing EOF: among nodes sharing a span, the DEEPEST is what was asked
  about, so a later visit overwrites an earlier one IFF it is a DESCENDANT of it** (`parent`-chain
  walk, run only on a collision). Two visits of the SAME node still keep the first, i.e. round 911's
  tightest-ambient rule is untouched.

- **THE MEMBER HALF WAS (API.3d) ONE QUESTION WIDER, AS ROUND 916 PREDICTED — AND THE PREDICTION HELD
  DOWN TO THE LEG ORDER.** `this` first (its type is `currentClassForThis`, null in a static member),
  then the EXPORT TABLE (a namespace's members are on no type and an enum's own type is member-LESS),
  then `getTypeOfExpression` + `resolveStructuredTypeMembers`. What is NOT reused is the UNION RULE, and
  the divergence is deliberate and stated in both KDocs: **go-to-definition COLLECTS across a union
  and completions INTERSECT**, because "where is `p` declared" is asked about a name already in the
  text while "what may I write here" must not offer something that will not compile. Nullish
  constituents are SKIPPED rather than allowed to empty the intersection — otherwise every optional
  chain and every `strictNullChecks` union answers nothing.

- **OFF IS STILL FREE, AND IT IS STRUCTURAL RATHER THAN CAREFUL.** `memberSpans` is a SECOND span list
  whose keys are UNIONED into `keysByFile`, so the per-node hot-path guard is byte-identical and the
  member test happens only after a span has already matched. That is what keeps `fileSemantics` — which
  hands in every identifier in a file — from enumerating a type at each of them. `cost_gate.py` reads
  **+0.00% on all 20 counters**.

- **WHAT IS ANSWERED**: members of an object/interface/class receiver including inherited ones (an
  override ONCE), an intersection (both sides), a union (only members on EVERY constituent, with the
  member's type rendered as the distinct constituent types joined by `|`), a nullish union (the
  non-nullish arm), a merged interface, an imported interface, a namespace, an enum, `this`, a lib
  primitive, and an incomplete `o.` in three buffer shapes. **WHAT IS REFUSED, each with a reason**:
  free names (`FREE_NAMES_NOT_IMPLEMENTED` — an explicit refusal, not a silent empty list, which is
  round 913's own pattern); strings/templates/regexes/numeric literals/comments and out-of-file
  positions (`NO_COMPLETION_CONTEXT`, and they do NOT build); an `any` receiver and an unresolvable one
  (empty, and NOT a refusal — the receiver was reached and genuinely has no members); accessibility
  FILTERING — private and protected members are OFFERED with `accessibility` saying which they are,
  because hiding one correctly depends on where the caret sits relative to the declaring class and a
  half-done filter silently loses real candidates; a class's static side reached through an instance.

- **A PIN WAS WRITTEN AS A DISCRIMINATOR, MEASURED NOT TO BE ONE, AND RENAMED — round 807's rule
  applied to my own work.** `a receiver used NOWHERE else in the file still offers its members` was
  written to catch the round-833 lazy-member-table rule, on the theory that every other receiver in
  the fixture is resolved by its own `readX` line. It stays GREEN under arm A1: **a `declare const x:
  T` declaration alone already resolves `T`'s table.** The rule IS load-bearing and the ONE receiver
  whose table nothing else resolves is `this`, whose type comes from `resolveUncalledThisType` rather
  than from a declaration the checker visited — so A1 is discriminated by the `this` pin, and the KDoc
  now says so instead of claiming a discrimination it does not have.

- **A TEST-FIXTURE TRAP WORTH ONE LINE: `|` IS A CARET MARKER AND A UNION SEPARATOR.** The first run of
  `CompletionAnchorTest` failed one case because `marked.indexOf('|')` found the `|` of
  `{ a: number } | undefined` rather than the caret, placing the caret 46 characters early — and the
  test then measured a real anchor at the wrong offset and looked entirely correct while doing it. The
  marker is U+2038 CARET now, with an assertion that exactly one appears.

- **PINS: +49** (`-project` 142 -> 191; core UNCHANGED at 14,337 — nothing was added there that a core
  test can reach without the `-project` anchor). 23 anchor pins, parse-only and written FIRST, plus 26
  end-to-end. **THE DISCRIMINATOR** is (API.3d)'s inverted: a receiver whose members are spelled
  exactly like two unrelated top-level bindings, asserted as an EXACT list — the wrong answer here is
  not empty and not a crash, it is a SUPERSET that still contains the right names.

- **SIX-ARM ABLATION, ONE MISTAKE AT A TIME (round 807), each dry-run for a real diff (round 902),
  restored from a sha256-verified snapshot and never `git checkout` (round 851). Every arm a DISTINCT
  set; all six compiled.** **A1** no `resolveStructuredTypeMembers` -> **1 red** (`this`, see above).
  **A2** the anchor ignores the prefix -> **5 red**, three of them parse-only anchor pins.
  **A3** a union COLLECTS instead of intersecting -> **1 red, uniquely its own**. **A4** plain
  first-wins, no descendant rule -> **1 red**, exactly the end-of-buffer pin — i.e. the defect above is
  pinned by the one test that found it. **A5** no export-table leg -> **2 red** (namespace + enum).
  **A6** nullish constituents not skipped -> **1 red, uniquely its own**.

- **GATES: suite 14,613 -> 14,662 / 0 failures / 0 errors / 3 skipped**, XML-summed over all six
  modules. `cost_gate.py` **+0.00% on all 20 counters**. `huge_methods.py --fail-over 0` clean on core
  (**742 classes, 15,851 methods, 0 over**) and, per round 909's blind-spot rule, on `-project`
  explicitly (**21 classes, 213 methods, 0 over**; the largest new method is `scanTokens` at 157).
  `spine_closure_audit.py` 46 handlers all supersets, run although no `spine*EnterNode` changed.
  Warning-clean. No wall A/B: production executes not one new instruction — every addition sits behind
  a hook that returns on a null per-file key set.

- **WHAT (API.4b) NOW NEEDS**, written into its queue entry rather than left implied: the anchor
  already gives it a correct `FREE_NAME` kind, prefix and replacement span, so what is missing is the
  ENUMERATION — and the structural fact that decides its shape is that `spineCurrentScope` is nulled
  per file, so it must be captured DURING the walk (a third span list beside `memberSpans`) and the
  anchor must start handing in a node for a free position, which today it does not. The size problem is
  measured, not guessed (round 902: **290.94** symbols per real probe, 815 on the outer levels), so
  whether a free-name item carries a `typeText` at all is a decision to take BEFORE building it.

**Round 916 (2026-08-17) — (API.3d): MEMBER GO-TO-DEFINITION LANDS, AND THE ROUND'S PRODUCT IS THAT
ROUND 913's REFUSAL WAS RIGHT FOR A REASON THAT SURVIVES THE FIX — THE MEMBER MECHANISM IS *A SECOND
MECHANISM*, NOT A WIDENING OF THE FIRST, AND THE ABLATION ARM THAT PROVES IT IS THE ONE THAT RESOLVES A
MEMBER BY A SCOPE LOOKUP: IT REDDENS **TEN** PINS, INCLUDING BOTH NEGATIVE CONTROLS THAT THE
"MECHANISM ENTIRELY OFF" ARM LEAVES GREEN.**

- **THE PREMISE HELD, WHICH IS WORTH SAYING BECAUSE THE LAST THREE (API.\*) ROUNDS ALL FOUND THEIRS
  WRONG.** Round 913's sentence — *"member definitions need the receiver's type resolved and its
  property symbol found, which is a separate mechanism and not this one"* — is exactly what was built,
  in the same hook, with no new public type and no new field on `Checker`. A member answer is a
  non-empty `definitions` list where one used to be empty; `DefinitionLocation` was already the right
  value.

- **WHAT MADE IT SMALL: THE AMBIENT THE HOOK ALREADY INSTALLS IS EXACTLY ENOUGH.** The task's first
  constraint was to establish what ambient a receiver's type genuinely needs rather than write a
  second install block. It needs none: the type at the member-access node was already being captured,
  so round 911's `ctaM3StmtAnchorCore` prologue + `withCtaFrameLocals(frame)` is in force and
  `getTypeOfExpression(receiver)` answers under it. The ONE place that needed anything extra was
  `this`, and even there the answer was already installed — **`this` is `Identifier("this")` in this
  parser (there is no `ThisExpression` node), so it reaches `getTypeOfExpression` as a name nothing
  binds and types as `any`**; its real type comes from `currentClassForThis`, which the hook restores
  from the cta frame and which the frame deliberately leaves NULL inside a STATIC member, so a static
  `this` answers nothing rather than answering with instance members. Measured before that leg
  existed, `this.inst` read EMPTY.

- **WHAT MADE IT CORRECT: GOING THROUGH THE COMPILER'S OWN MEMBER RESOLUTION.** `resolveStructuredTypeMembers`
  is what makes an INHERITED member answer with the BASE's own `Symbol` (`resolveInterfaceMembersCore`
  copies the base's symbol object into the derived table) and a GENERIC instantiation answer with the
  declaration rather than the substituted type (`resolveReferenceMembers` does
  `newProp.declarations.addAll(prop.declarations)`). A hand-rolled walk of `type.members` would have
  got both wrong, and CLAUDE.md's round-833 rule bites here too: a member table is LAZY, so a reader
  that does not resolve first answers differently depending on whether an earlier line in the file
  happened to resolve that type.

- **ONE PLACE THE EXISTING CODE HAD TO CHANGE, AND IT WAS ONLY REACHABLE NOW.**
  `typeCaptureDeclarationName` had no `PropertyAssignment` arm, so an object literal's own member
  answered with the whole `size: 1` rather than with `size` — invisible until a member name resolved
  at all. Three pins assert the span, and dropping the arm again (arm A5) reddens exactly those three.

- **UNION AND INTERSECTION RECEIVERS COLLECT RATHER THAN PICK.** `getPropertyOfType`'s union rule ("the
  property exists only if EVERY constituent has it, then return the first") is an assignability
  question and is the wrong one here — a user asking where `p` is declared on `A | B` wants both
  places, and a `p` on one constituent only is still a real declaration. So the member walk is its own
  small collector with a depth cap, and the answer is deduplicated by `(file, start, length)` in
  constituent order.

- **WHAT IS DELIBERATELY STILL REFUSED, each for a stated reason** (KDoc, `docs/language-service.md`
  § 9): `o["p"]` (the argument is a literal — only identifiers are offered a definition), `{ p: v }`'s
  own key (the useful target is the CONTEXTUAL type's property, a third mechanism), a member's own
  declaration name (it already IS the declaration), `A.B.x`'s tail (the middle segment would have to be
  resolved the same way, for a case one caret to the left already answers), and anything unresolvable
  (silence, never the nearest same-named thing). The LIB question was decided by CONSISTENCY rather
  than by taste: `definitionsAt` already documents that a free name resolving into a lib answers with a
  file the host may not be able to open, so a member is not given a different rule.

- **A PIN'S MEANING WAS CHANGED, AND THAT IS LOGGED RATHER THAN QUIET.** Three pins asserted the old
  refusal (core `DefinitionCaptureMeasurementTest`'s member case, `ProjectDefinitionTest`'s
  "answers EMPTY", `ProjectSemanticsTest`'s "a type and deliberately no definition"). All three now
  assert the MEMBER's declaration at the same span, and each says in-file that its meaning changed
  because the gap closed — an unlogged pin change is indistinguishable from hiding a regression. A
  NEW negative control replaces what they used to guard: an unresolvable member whose spelling IS a
  file-level `const` must still answer nothing.

- **PINS: +13** (core +1 net: the rewritten discriminator plus a new unresolvable-member control;
  `-project` +9 net: 10 new, 1 rewritten in place). **THE DISCRIMINATOR, written first**: a member
  whose spelling collides with an unrelated top-level binding in the same file — the wrong answer is
  not empty or a crash, it is a *plausible location in the right file*, so only the OFFSET separates
  them and both are asserted.

- **FIVE-ARM ABLATION, ONE MISTAKE AT A TIME (round 807), each dry-run for a real diff (round 902),
  restored from a byte-verified copy and never `git checkout` (round 851). Every arm a DISTINCT set;
  185 tests ran in every arm and no arm failed to compile.** **A1** member path never taken -> **9
  red**, every positive member pin, both "answers empty" controls GREEN. **A2** a member resolved by a
  SCOPE LOOKUP — precisely the wrong answer round 913 refused — -> **10 red**, and it is the only arm
  that reddens the two NEGATIVE controls (`an unresolvable member answers NOTHING`, `an object-literal
  KEY being declared answers empty`), i.e. the pins written to catch a *guess* are the pins that catch
  it. **A3** no union/intersection recursion -> **1 red, uniquely its own**. **A4** no export-table leg
  -> **1 red, uniquely its own** (the namespace pin — a namespace's and an enum's members are on no
  TYPE, so a type-only implementation is silent there). **A5** no `PropertyAssignment` name arm -> **3
  red**, exactly the span assertions. Worth recording: the LIB pin cannot discriminate A2, because a
  scope lookup of `length` also lands in a lib file — it is discriminated by A1, and saying so is the
  round-807 rule against crediting a pin with discrimination it does not have.

- **GATES: suite 14,603 -> 14,613 / 0 failures / 0 errors / 3 skipped = EXACTLY the +10 net** (core
  14,336 -> 14,337, `-project` 133 -> 142), XML-summed over all six modules and re-run on the
  byte-restored post-ablation tree. **`cost_gate.py` +0.00% on all 20 counters** — a real gate, since
  the member walk is new code reachable from the capture hook on the hot walk, and proven live by its
  own 46-error / 78-file compile. `huge_methods.py --fail-over 0` clean on core (**739 classes, 15,801
  methods, 0 over**; `Checker.<init>` unmoved at **5,813** — no new field) and, per round 909's
  blind-spot rule, on `-project` explicitly (12 classes). `spine_closure_audit.py` 46 handlers all
  supersets, run although no `spine*EnterNode` changed. Warning-clean. No wall A/B: production executes
  not one new instruction — the member walk sits inside a hook that returns on a null per-file key set.

- **WHAT THIS LEAVES (API.4), asked for explicitly.** It leaves it with **less to build, but not with
  its hard half done**. What transfers is real and is the part that would have been most likely to be
  got wrong: "what does this receiver's type call things" now has a tested answer, including the lazy
  member table, the inherited-symbol rule, unions and the namespace/enum export leg — completions'
  member half is that same resolution one question wider (ENUMERATE `type.members` rather than look up
  one name). What does NOT transfer is the anchoring: every mechanism in this round starts from *a
  node that exists at the caret*, and a completion request by definition has none — the user is
  mid-identifier or sitting just after a `.`, so the capture request cannot be a span at all and needs
  a "nearest enclosing node + the scope in force there" shape that nothing here provides. The free-name
  half is also genuinely new: `spineScopeLookup` answers ONE name and completions need the chain
  ENUMERATED, which is a different traversal of `LexicalScope` (and CLAUDE.md's round-902 warning
  applies — the outer levels are large, mean 815 symbols on a real probe). So: the member seam is
  bought, the anchor and the enumeration are not.

**Round 915 (2026-08-17) — (BUG.1): THE LONE-`\r` SELF-INCONSISTENCY IS CLOSED, AND THE SWEEP THE ITEM
ASKED FOR FOUND **FIVE** OFFSET→LINE CONVERTERS WHERE THE QUEUE NAMED TWO — FOUR OF THEM WRONG, EACH A
PRIVATE COPY OF A LOOP NOBODY KNEW WAS DUPLICATED. THE ROUND'S REAL PRODUCT IS THE **SECOND** REASON
THE FAMILY WAS INVISIBLE: THE STRING ENTRY POINT BEHIND THE ENTIRE GENERATED CORPUS **NORMALISES
`\r` AWAY BEFORE THE PARSER RUNS**, SO NO CORPUS FIXTURE COULD EVER HAVE CAUGHT THIS — NOT "none
happens to have a lone `\r`", BUT "none CAN".**

- **THE PREMISE HELD.** `Parser.computeLineStarts` broke a line at `\n`, `\r\n` and a lone `\r`
  (tsc's rule); `Checker.lineStartsFor` was `for (i in source.indices) if (source[i] == '\n')`. So a
  SYNTAX diagnostic numbered a classic-Mac file's lines and a SEMANTIC one reported line 1.

- **THE SWEEP — the part a future agent cannot re-derive cheaply.** Grepping for *definitions* rather
  than call sites (`fun *LineChar*`/`fun *LineStarts*`/`line++`/`ln++`/`curLine++`) found **five**
  independent implementations of one conversion, all in `commonMain`, all private, none referring to
  each other: (1) `Parser.computeLineStarts` — CORRECT, the reference; (2) `Checker.lineStartsFor`
  (`\n` only) — the queued bug; (3) `Checker.posOfLineCol` — the INVERSE, `\n` only, so the two
  directions did not even round-trip; (4) `TypeScriptCompiler.positionToLineCharacter` (`\n` only) —
  ~14 diagnostic call sites; (5) `TypeScriptCompiler`'s inline TS2688 loop and `Transformer`'s JSX
  dev-runtime `lineNumber`/`columnNumber` loop, both `\n` only, the latter feeding EMITTED JavaScript
  rather than a diagnostic; (6) `CompilerOptions.computeLineAndColumn` for tsconfig positions, a
  THIRD convention — `\n` breaks the line and `\r` is treated as ZERO-WIDTH. Six copies, three
  conventions. **The `-project` module's `LineMap` was already right** and is deliberately left a
  reimplementation (it also carries `lineContentEnds`, which the compiler has no use for), so it is
  the one place the rule is stated twice — pinned by a differential, not by restatement.

- **WHAT LANDED.** A new `LineStarts.kt` holding the convention as ONE function,
  `lineBreakWidthAt(text, i)` — `0`, `1` or `2` — plus the two traversal shapes built on it:
  `computeLineStarts` (moved out of `Parser.kt`, now `internal`, byte-identical logic) and
  `lineAndCharacterAt` (a BOUNDED scan for the sites with nothing to memoize on). All five wrong
  copies now delegate. `posOfLineCol` reads the same memoized index the forward direction does, which
  is what makes the round-trip structural rather than coincidental. **Deliberately NOT one function**:
  a scan that stops at the offset and an index that spans the file are different algorithms, so they
  share the RULE and are pinned to agree at every offset of six terminator-mixed texts.

- **THE FINDING, and it is bigger than the bug.** `TypeScriptCompiler.compile(String)` →
  `parseMultiFileSource` opens with `.replace("\r\n", "\n").replace("\r", "\n")`. Every generated
  corpus test and every `diagnose()`-based pin goes through it, so **a `\r` cannot reach the Parser
  from that entry point at all**. The corpus's one genuinely lone-`\r` fixture
  (`templateStringMultiline3`) is normalised before it is parsed. Only the project/`Vfs` path
  preserves terminators — which is exactly the path the new `(API.*)` embedding API sits on, i.e. the
  bug was becoming reachable just as it was found. The first version of the core pin was therefore
  **VACUOUS AND PASSED**: it built `\r` text, the harness turned it into `\n` text, and "the two
  halves agree" was a statement about a file with no `\r` in it. `diagnoseVerbatim` now hands the
  pipeline a `ParsedSource` directly.

- **A SECOND VACUITY, found the same way.** A fixture beginning with `\r` is not a leading-line-break
  test either: `parseMultiFileSource` drops blank lines before the first content line, so the pin read
  line 1 for a correct compiler. Replaced with consecutive-and-trailing breaks after a real first line.

- **PINS — 10 new (`LineTerminatorConsistencyTest`, core) + 1 (`ProjectPositionTest`) − 1 (the
  `LineMapTest` case that pinned the divergence as permanent).** The sharp four are
  self-consistency: a lone-`\r` file where TS2322 must land on line 3 and TS1128 on line 5 with every
  diagnostic's `line` matching an independent restatement of the rule; the three-shape control
  (`\n` / `\r\n` / `\r` must produce the SAME `(code, line)` table — this is what catches the obvious
  double-count, which would read 5 and 9 for CRLF); consecutive + trailing breaks; and the CHARACTER
  on the line after a lone `\r`. The other six pin the shared helper's edges (empty text, a text that
  IS one `\r`, trailing breaks under all three shapes, the `\n` inside a `\r\n`, and scan-vs-index
  agreement over all offsets of six texts) and are labelled in-file as NON-discriminating.

- **ABLATION (restoring the pre-fix `\n`-only body by hand, never `git checkout`): exactly 5 pins
  red** — the 4 discriminating core ones and the 1 new `-project` one — the 6 arithmetic guards and
  all 12 other `ProjectPositionTest` cases green. Restored by hand and re-run green.

- **GATES: suite 14,593 -> 14,603 / 0 failures / 0 errors / 3 skipped = EXACTLY the +10** (core
  14,326 -> 14,336; `-project` unchanged at 133, being +1/−1), XML-summed over all six modules. **No
  corpus baseline moved**, which is the load-bearing negative: `\n` and `\r\n` had to be untouched.
  `cost_gate.py` **+0.00% on all 20 counters** — a real gate here, since the Checker's line index
  changed shape, and proven live by its own 46-error / 78-file compile. `huge_methods.py --fail-over
  0` clean on core (**739 classes, up from 738 — the gate SAW `LineStartsKt`**) and on `-project`
  (12); `Checker.<init>` unmoved at 5,813 (no new field). Warning-clean. No wall A/B: `lineStartsFor`
  is memoized per source and `computeLineStarts` is lazy, so the loop is not on any hot path — the
  counters are the defensible instrument.

**Round 914 (2026-08-17) — (API.3c): THE BATCH LANDS AND THE API IS NOW USABLE BY AN EDITOR — N SPANS COST
ONE BUILD, MEASURED AT **34x** FOR HOVER AND **62x** WHEN EACH CARET IS ALSO ASKED FOR ITS DEFINITION.
THE ROUND'S TECHNICAL PRODUCT IS THAT THE QUEUE ENTRY'S "IT NEEDS NO NEW MECHANISM" WAS TRUE OF THE
CAPTURE AND **FALSE OF ITS KEY**: THE ONE THING BULK CHANGES IS A HASH DISTRIBUTION, AND NOTHING IN THIS
REPO CAN SEE A DEGENERATE ONE.**

- **THE CORRECTION, first, because it is the only place this item could silently create a defect.**
  `TypeCaptureRequest.keysByFile` packs `(start, end)` into a `Long` and its KDoc said the packing was
  left un-finalized DELIBERATELY — "these sets hold the handful of spans a host asked about, so no
  bucket distribution exists to degenerate. Should a caller ever request spans in bulk, finalize the
  key with an odd multiply as `packIdPair` does." **`Project.fileSemantics` IS that caller**, and the
  collapse is round 889's in its purest form: `Long.hashCode` is `(int)(v xor (v ushr 32))`, so the
  pack hashes to `start xor end` — and a node's `end` is its `start` plus its own length plus the
  FOLLOWING token (round 910), i.e. the halves are not merely correlated, they are NEIGHBOURS.
  Measured on a modelled whole-file population: **>400 distinct spans onto fewer than 40 distinct
  hashes**, every bucket degenerate. Now `packIdPair`. Soundness is that function's two clauses and
  both hold: nothing unpacks the key (the answers carry the node's own `start`/`end`) and nothing
  iterates the sets. **Production pays nothing** — the per-node hook returns on a null per-file key
  set BEFORE it packs anything, which `cost_gate.py`'s +0.00% is the evidence for.

- **THE PUBLIC SHAPE, and why it is two members and one mechanism.**
  `semanticsAt(fileName, offsets: List<Int>)` is the primitive and `fileSemantics(fileName)` is the
  sweep, the second literally calling the first's helper over `SourceIndex.identifiers()`. An editor
  needs both and they are not the same question: a sweep serves semantic highlighting and hover
  prefetch, a multi-offset query serves a known set of carets. The value is
  `SemanticInfo(start, end, kind, quickInfo, definitions)` — one per DISTINCT SPAN, sorted
  `(start, end)` — so several carets in one identifier collapse to one entry and the result is
  neither indexed by nor the same length as the input. **The ordering is imposed here rather than
  inherited**, because the compiler's answer order is the order its walk reached the nodes, i.e. a
  property of the check spine. An empty request does not build.

- **THE CANDIDATE SET IS "EVERY `Identifier`", AND THE ARGUMENT IS THAT THE RULE HAS TO FIT IN A
  SENTENCE.** Anything richer is a taste-driven list that drifts. Member names are IN (they are
  identifiers and they are typed); their definition stays refused, so such an entry carries a type and
  no locations — which is one span pinning both halves of the rule. Keywords, punctuation, literals and
  larger expressions are out; a host wanting the type of `f(x)` asks `semanticsAt` for the caret it has.

- **WHAT I DELIBERATELY DID NOT DO: re-express `quickInfoAt`/`definitionsAt` on the batch.** It would
  have removed ~10 duplicated lines and made the EQUIVALENCE pin a tautology. They stay separate code,
  so "the batch says span for span what the single-caret members say" is a comparison of two
  independent paths and two independent builds, and drift between them is what it fails on. Recorded in
  `Project.semanticsOf`'s KDoc so the next reader does not "clean it up".

- **THE MEASUREMENT (34-identifier in-memory fixture, warm, two draws agreeing to 3%):**
  `fileSemantics` = **1 compile, 100-103 ms**; the same 34 carets through `quickInfoAt` = **34
  compiles, 3,373-3,377 ms (33.6x)**; each caret asked BOTH ways = **68 compiles, 6,209-6,474 ms
  (60-63x)**. The ratio is what transfers — it is a count of compiles — and the ms are a property of a
  tiny fixture.

- **THE BUILD COUNTER IS A PER-PATH READ, AND THE FIRST VERSION OF IT WAS FLAKY.** Counting ALL Vfs
  touches read 29 where 6 builds should be 30, once, and 30 on every rerun: some compiler cache warms
  across builds within one JVM and takes a source read with it, so the sum is order-dependent — a pin
  that cries wolf. Reads of `tsconfig.json` are exactly 1 per `ProjectCompiler.build` and are not
  cached across builds, which a control pin establishes rather than assumes. Three consecutive runs of
  the class, green.

- **GATES: suite 14,567 -> 14,593 / 0 failures / 0 errors / 3 skipped = EXACTLY the 26 new pins** (22
  `-project`, 4 core; module 111 -> 133, core 14,322 -> 14,326), XML-summed across all six modules.
  **`cost_gate.py` +0.00% on all 20 counters** — here a control by construction (no capture is
  requested on a production compile) and worth running because the key change is ON the hot walk's
  hook, and proven live by the compile it drives (46 errors / 78 files). `huge_methods.py
  --fail-over 0` clean on core (738 classes) AND, per round 909's blind-spot rule, on the `-project`
  module explicitly — **12 classes against round 913's 11, i.e. the gate SAW the new code**.
  `spine_closure_audit.py` 46 handlers, all supersets, run although no `spine*EnterNode` changed.
  Build warning-clean. No wall A/B: production executes not one new instruction.

- **WHAT IS LEFT, unchanged: member go-to-definition** (needs the receiver's type and its property
  symbol — the capture hook is the right place, the scope chain is not the right mechanism) and
  `(API.4)` completions. And one honest coarseness the sweep makes visible: the capture types an
  identifier NODE, so a member name and a parameter's own declaration name answer `any` rather than
  what a host would like; that is (API.3a)'s behaviour seen in bulk, not something batching introduced.

**Round 913 (2026-08-17) — (API.3b): GO-TO-DEFINITION LANDS, AND THE ROUND'S PRODUCT IS THAT **THE QUEUE
ENTRY'S OWN PREMISE WAS WRONG**: (API.3a)'s ambient lesson does NOT transfer, because a definition's
walk-scoped input is not the checking ambient at all — it is `spineCurrentScope`, which the spine
maintains PER NODE where the ambient is install-and-restore PER ANCHOR. WHAT DOES TRANSFER IS THE ONLY
THING THAT MATTERS: BOTH INPUTS ARE GONE ONCE THE WALK IS OVER, SO CAPTURE IS STILL MANDATORY.**

- **THE CORRECTION, stated first because a next agent will otherwise inherit the wrong model.** The
  entry said "a symbol resolved without `withCtaFrameLocals` is the same wrong answer one indirection
  along". `withCtaFrameLocals` restores `currentLocalTypes`, a map of `String -> Type` — it holds no
  symbols and no declarations, so it cannot answer a definition question in either direction. The
  resolution that CAN is the INV.2(c) lexical chain (`spineScopeLookup`: scope-space bindings, then the
  aliased container tables — a body's params and locals, the file's locals, the enclosing namespaces'
  exports), and `spineScopeEnterIfOwner` runs **before** `spineEnterNode`, i.e. the chain is already
  correct at an arbitrary node. **The capture is still required, for the OTHER half of the argument:**
  `spineScopeClear` nulls the chain when the spine leaves a file, so the post-hoc query has nothing to
  ascend and falls through to `lookupPerFileForNode`. So the shape of the two rounds is the same and
  the mechanism is not, and that distinction is now in the code's KDoc, in CLAUDE.md and in
  `DefinitionCaptureMeasurementTest`'s header.

- **THE MEASUREMENT (captured-during-walk vs asked-post-hoc, ONE `Checker` instance, core
  `DefinitionCaptureMeasurementTest`).** A body local shadowing a same-named file-level `const`:
  captured answers **the body declaration**, post-hoc **the file-level one** — a DIFFERENT
  DECLARATION, i.e. an editor would navigate the user to the wrong line and look like it worked. A
  parameter: captured answers **the parameter**, post-hoc answers **nothing at all** (nothing durable
  binds a parameter by name — the same finding (API.3a) made, where the type degraded to `any`). The
  control, a file-level `const`, is answered **identically by both**, which is exactly what makes the
  body-local row dangerous rather than obviously broken.

- **ONE HOOK, TWO RECORDED FACTS.** No second spine handler and no second request type: the same
  `typeCaptureVisit` now records the type AND the definition at every requested span, because both are
  functions of the same walk and separating them would double the compiles a host needs to describe
  one caret. `spineEnterMask` is untouched — the hook is a `spineEnterNode` PROLOGUE line, not a masked
  handler — and `spine_closure_audit.py` was run anyway (46 handlers, all supersets).

- **THE SPAN QUESTION, DECIDED THE OTHER WAY FROM (API.3a) AND ON PURPOSE.** A captured TYPE hands the
  RAW `(pos, end)` identity back and lets `-project`'s `SourceIndex` say how long the node really is. A
  DECLARATION cannot: it is usually in a file the caller never asked about and may not be able to read
  at all — **a `lib.*.d.ts` has no path on disk** — so pushing round 910's span problem outwards would
  hand it to the one party with nothing to solve it with. The checker holds every program file's
  `SourceFile.text`, so the exact end is computed THERE, by scanning FORWARD from the name's own `pos`
  (`Scanner.resetToPosition` + `scan`, greatest token end strictly below `Node.end`) — one or two
  tokens, no index, no cache, and the same graceful degradation `SourceIndex` documents (a context-free
  re-scan can only SPLIT a contextual token, which adds boundaries; a merge answers a SHORT span, never
  one reaching into the next declaration). The span is the **NAME** where a declaration has a
  single-token one, as tsc's own go-to-definition navigates.

- **WHAT ANSWERS NOTHING, DELIBERATELY, AND IS PINNED AS SUCH.** A MEMBER name — the `p` of `o.p`, a
  property signature's name, an enum member behind its enum — is refused rather than resolved, because
  a scope lookup of a member name finds whatever unrelated binding shares the spelling and **a
  confidently wrong navigation target is worse than none**. `typeCaptureIsFreeName` is a REJECT-list
  over parent kinds rather than an accept-list, because the referencing positions are open-ended while
  the member positions are closed, and a missed reject is a wrong answer where a missed accept is only
  a missing one. The pin is sharp: at the same span the TYPE **is** captured, which proves the refusal
  is a refusal and not a miss.

- **AN IMPORTED NAME ANSWERS ABOUT THE ORIGINAL**, through the checker's existing
  `resolveImportedSymbolGeneral` (attempted only when every declaration is an import binding — the same
  test that function applies one level down), degrading to the import statement when the module does
  not resolve. And a MERGED symbol answers with EVERY declaration: `interface Merged` twice returns two
  locations, so "take the first declaration" is the wrong host-side reflex and the API says so.

- **FOUR-ARM ABLATION, ONE MISTAKE AT A TIME (round 807's law), each arm dry-run for a real diff
  (round 902) and restored from a byte-verified copy rather than `git checkout` (round 851).** **A1**
  drop the lexical-chain leg -> 5 red, exactly the body-local/parameter family in both modules. **A2**
  take the length from the raw `Node.end` -> 5 red, every span assertion. **A3** drop the free-name
  gate -> **1 red, uniquely its own** (the member-name refusal). **A4** drop the import-alias hop ->
  **1 red, uniquely its own** (the cross-file pin). Every arm reddened a DISTINCT set; no pin was
  credited with discrimination it does not have.

- **GATES: suite 14,548 -> 14,567 / 0 failures / 0 errors / 3 skipped = EXACTLY the 19 new pins** (11
  `-project`, 8 core; core 14,314 -> 14,322, module 100 -> 111), XML-parsed across all six modules and
  re-run a second time on the byte-restored post-ablation tree. **`cost_gate.py` +0.00% on all 20
  counters** — a real gate, not a tautology, since `Checker.kt` grew ~240 lines on the hot walk, and
  proven live by the compile it drives (46 errors / 78 files). `huge_methods.py --fail-over 0` clean on
  core (738 classes, `Checker.<init>` 5,802 -> **5,813** — the one new field) **and, per round 909's
  blind-spot rule, on the module explicitly** (11 classes, up from 10: the gate SAW the new code).
  `spine_closure_audit.py` 46 handlers all supersets. Build warning-clean. No wall A/B: production
  gains nothing but the definition branch INSIDE an already-null-guarded hook, i.e. zero instructions
  when no capture is requested, which is far under the +-1.0% band.

- **DEFERRED, unchanged: (API.3c)** — batch a whole file's spans into ONE build. `TypeCaptureRequest`
  already takes a SET and now yields two answer lists per span, so "semantic info for file X" is one
  compile away from being one compile; `quickInfoAt` and `definitionsAt` currently build once EACH,
  which is the thing (API.3c) exists to fix. **And one honest gap worth its own item eventually:**
  member go-to-definition needs the receiver's type resolved and its property symbol found, which the
  capture hook is the right place for but the scope chain is not the right mechanism for.

**Round 912 (2026-08-17) — (WARM.35): THE FOUR UNPRICED CANDIDATES FROM ROUND 903's HOT-PATH AUDIT ARE
**ALL REFUSED**, THE LARGEST AT **0.18%** AND ALL FOUR TOGETHER AT **0.303% (15.9 ms)** — UNDER THE
~17 ms FLOOR FOR *ONE* LOW-RISK CHANGE. THE ROUND'S REAL PRODUCT IS THAT **THE QUEUE'S OWN POPULATION
FOR THE LARGEST OF THEM WAS A TRANSCRIBED SOURCE COMMENT**, AND THAT **TWO OF THE FOUR FIXES ARE DEAD
BEFORE ARITHMETIC — ONE IS NOT EXPRESSIBLE IN KOTLIN AND ONE IS A SOUNDNESS BUG.**

Nothing was built and no amplifier was run: every refusal is population x a generous per-operation
ceiling, checked against round 896's divide-and-refuse, exactly as round 904 refused the boxed-key
family. `docs/perf/round912-candidate-census.md` is the record.

- **THE MEASUREMENT.** Throwaway counters at each site (reverted), printed after the last measured
  rebuild on the compiler profile (78 files, 46 errors), warm `BenchMain <proj> 6 2` and `6 3`,
  instrumented medians **5,065.7** and **5,170.8 ms**. Denominator per this file: **5,242.6 ms**, so
  1% = 52.4 ms and the floor is 0.324%.

  | candidate | population/rebuild | ceiling | % | verdict |
  |---|---:|---:|---:|---|
  | `mappedNodeTypeKey` key build | **25,987** keys of **110,780** calls | 9.36 ms | 0.179% | REFUSED (1.8x) |
  | `narrowTypeFromFlow` default-arg `NarrowFlowMemo` | **31,768** | 4.77 ms | 0.091% | REFUSED (3.6x) |
  | `collectTypeofGuardNames` &c `LinkedHashSet` | **22,798** | 1.48 ms | 0.028% | REFUSED (11.5x) |
  | `spineOsWithAmbient` / `spineTcDispatchWithAmbient` | **2,841** | 0.28 ms | 0.005% | KILLED BY READING (60x) |
  | **ALL FOUR TOGETHER** | | **15.9 ms** | **0.303%** | under the floor |

  To reach 17 ms they would need **654 / 535 / 746 / 5,983 ns per operation**, against a measured
  **15.09 ns** for a whole `HashMap` get that recursively hashes AND `equals` a 2.76-node AST subtree
  (round 903) and **8.53 ns** for a boxed `HashMap<Long,·>` probe (round 904).

- **THE CONTROLS, because a census that is only self-consistent has none.** Two independent processes
  agree **to the last digit on all 22 counters**, and `mappedNodeTypeKey calls = 110,780`
  **reproduces `docs/perf/cost-counters.txt`'s `typeNode.bypassed` exactly** — an external, previously
  recorded number the census never had access to.

- **THE FINDING WORTH MORE THAN ANY OF THE PRICES: A QUEUE POPULATION CAN BE A TRANSCRIBED SOURCE
  COMMENT.** The "~88 k/rebuild" attached to `mappedNodeTypeKey` traces to an in-source comment ("this
  is not the hot loop — 88k calls"), and it is wrong in **both directions at once**: the function is
  **CALLED 110,780 times** (the comment aged 26%) and **BUILDS A KEY 25,987 times** (**3.4x fewer**
  than the queue attributed to it, because **76.5%** of calls exit at the foreign-file gate before any
  key work). A number in a KDoc is not a measurement, and the quantity a fix would act on is not
  automatically the quantity the comment counts. Now in CLAUDE.md.

- **WHAT DID NOT WORK, AND WHY THAT IS THE ROUND'S SECOND PRODUCT.** (i) **Candidate 3's `inline` is
  NOT EXPRESSIBLE**: both `spineOsWithAmbient` and `spineTcDispatchWithAmbient` hand `block` to a
  **recursive, non-inline** callee (`spineOsApplyTps` / `spineTcApplyLevels`), so `inline` forces
  `noinline`, which re-materialises the lambda exactly as today — *a candidate can be dead on grounds
  of the LANGUAGE before any population is counted, and it is reading the CALLEE, not the wrapper,
  that shows it.* Its population is **2,841 calls**, one third of one percent of a single pass over
  the spine's 856,962 nodes, so the "measured-hot path" premise was false as well and **nothing had
  ever measured it** — `grep -rn` over `docs/` finds not one mention of any of the four names.
  (ii) **Candidate 4's obvious shared-memo fix is a SOUNDNESS bug**: `narrowTypeFromFlowCore` handles
  RE-ENTRANT outermost walks at `narrowLiveDepth == 0` by design, so a single shared instance would be
  cleared and overwritten by a re-entrant walk while the outer walk still depends on it — and a wrong
  serve there is a **wrong narrowed type**, undoing round 736's depth/height soundness argument from
  underneath. **34.2%** of memos already grow past 32 slots, so a shared memo's `clear()` is not
  obviously cheaper than the allocation it replaces (round 899: price a container swap NET). *The
  cheapest-looking of the four is the riskiest.*

- **AND THE ONE THING THE AUDIT NEVER NOTICED — still under the floor, so it is recorded rather than
  queued.** `mappedNodeTypeKey` performs **110,780 parent-chain climbs plus 110,780 `String`-keyed
  `fileResults` probes (~5.5 ms)** purely so that 76.5% of calls can answer "foreign file". That is
  comparable to the *named* mechanism and structurally required by the gate; the WHOLE function, at
  these generous rates, is ~15 ms — under the floor by itself. Also recorded: two of that function's
  three reject branches (`unindexed`, `no-owner`) fire **0** times on this profile, and the legacy
  `checkArithmeticInStatement` `IfStatement` arm runs **0** times (a bound on its frequency here,
  **not** deletion evidence — round 753).

- **THE HONEST UNCERTAINTY, stated because a ceiling is only as good as its rates.** Two of the
  per-operation rates are NOT sourced from a repo-measured constant and are set 3-10x above the
  nearest anchor on purpose: the `StringBuilder` + ~4.7 appends + `toString` for a 12.79-char key
  (**150 ns**), and `entries.sortedBy { }` over a **1.277**-entry map (**200 ns** — the census's own
  surprise is that a type-param scope is in force for 71.7% of built keys, so the sort really runs; it
  is just a 1-element `Collections.sort`). **An amplifier was judged not worth a build**: candidate 1
  would have to measure **654 ns per key**, ~43x a measured full recursive-hash `HashMap` probe, so
  the refusal survives an order of magnitude of rate error and an amplifier could only make the answer
  smaller. Three of the four are pure-allocation candidates, a genre round 801 (367,189 `String`
  allocations = **0 ms**) and round 893 (warm GC ~1.7% of wall) already price near zero — this is the
  fourth confirmation.

- **NEW REUSABLE CONSTANT, the allocation twin of round 904's ~1.7 M map-ops bar:** a pure-allocation
  candidate needs **> 113,000 allocations/rebuild at a generous 150 ns, or > 340,000 at a realistic
  50 ns**, to clear the ~17 ms floor. In CLAUDE.md, and it refuses most per-node allocation candidates
  by arithmetic.

- **GATES AND SUCCESSOR.** **No code changed** — the counters were reverted, so there is no suite run,
  no `cost_gate.py`, no `huge_methods.py` and no grid to report; the corpus count is unmoved. Per the
  WORK ORDER NOTE, the named successor is the **(API.\*)** arc — **(API.3b) go-to-definition** next,
  with **(API.3c)** (batch a whole file's spans into ONE build) as the item that makes the API
  practical for an editor. **The checker-side pool is now empty in the literal sense**: round 908
  closed the spine side and this round prices the audit residue, leaving nothing checker-side
  unpriced. The two remaining perf levers are artifact-level and **both are gated** — (ART.1) on the
  owner's release decision (the engineering exists; `native.yml` already builds Oracle + PGO), and
  (ART.2) on a **CRaC JDK that is no longer installed on this box** (Zulu 26 / OpenJDK 25, plus 17 and
  21 under `~/jdks`), so neither its `afterRestore` cwd fix nor a re-measurement can be compiled or
  verified locally.

**Round 911 (2026-08-17) — (API.3a): QUICK INFO LANDS, AND THE DESIGN ROUND 910 DECIDED BY *READING* IS
NOW CONFIRMED BY *MEASUREMENT* — **FIVE OF SIX POSITIONS ANSWER DIFFERENTLY POST-HOC**, AND THE
PREDICTION IN THE QUEUE ENTRY WAS WRONG IN THE **WORSE** DIRECTION. THE ROUND'S TECHNICAL PRODUCT IS THAT
**A PER-NODE HOOK ON THE SPINE SEES NONE OF THE CHECKING AMBIENT.**

- **THE MEASUREMENT, captured-during-walk vs asked-post-hoc on ONE `Checker` instance** (core
  `TypeCaptureMeasurementTest`, 9 pins): top-level annotated `const` **`string` / `string`**; body local
  shadowing `declare const collide: string` **`number` / `string`**; `typeof`-narrowed parameter
  **`string` / `any`**; parameter at its use **`number` / `any`**; arrow-body parameter **`string` /
  `any`**; class-method parameter **`number` / `any`**. The top-level row is the honest control —
  post-hoc is NOT wrong about everything, which is exactly why the failure is dangerous. **Round 910
  predicted the narrowed case would read `string | number` (narrowing merely lost); it reads `any`,
  because nothing durable binds a parameter at all** — and `any` is the ONE answer that is silent at
  every use site, so a post-hoc hover would have looked plausible and meant nothing. A wrong prediction
  in the direction of "worse than I thought" is the useful kind: it converts the design from a judgement
  into a measurement.

- **THE FINDING THAT MOVED THE HOOK, and the round's most reusable fact: THE SPINE'S ANCHORS
  INSTALL-AND-RESTORE THE CHECKING AMBIENT PER DISPATCH, SO AT AN ARBITRARY NODE THE CHECKER HOLDS NONE
  OF IT** — `currentLocalTypes` there is the FILE-level map. Measured: the first working version answered
  `bodyLocal=string` (the global), `narrowed=any`, `parameter=any`. **The position's scope is
  `ctaFrames.last()`; the ambient FIELDS are not.** The fix reproduces `ctaM3StmtAnchorCore`'s prologue
  verbatim (`classForThis / inFn / inAsync / inGen / fnTpDecls / fnTpScope / currentFlowGraph /
  currentCheckFileName` + the namespace-chain push) and then `withCtaFrameLocals(frame)`. The ablation
  drops exactly that one call and reddens **exactly the 8 predicted pins**, with the top-level control
  and all 96 other module pins green — and it is REACHED, not dead, since its answers revert to the
  pre-fix `string/any/any`.

- **A SECOND HOOK WAS BUILT AND DELETED RATHER THAN SHIPPED**: one in `checkTypeAssignabilityInStatements`'
  statement loop **never fired once** over declaration / arrow / method bodies — that walk is not on the
  spine path for these shapes. Removing it beat shipping a per-statement production read that bought
  nothing. Recorded in CLAUDE.md, because "the legacy assignability walk is where body-scoped ambient
  lives" is the natural guess and it is false; the cta frames are.

- **THREADING AND IDENTITY.** An explicit parameter on the `recheckOnly` model —
  `Project.quickInfoAt` -> `ProjectCompiler.build` -> `compileParsed` -> `compileParsedCore` ->
  `cpcCompileMultiFile` -> `cpcBindAndCheck` -> `Checker`, answering back through
  `CompilationResult.capturedTypes` -> `ProjectCompiler.Result.capturedTypes`. **Nothing on
  `CompilerOptions`** (compared for parse-flag equality, and ~160 bytecodes per `copy()` call site, round
  815) and **no process-global mode** (those owe the round-848 ledger; a capture request is DATA).
  The single-file arm is threaded too, so the API is not silently inert there. **Node identity is the RAW
  `(pos, end)` pair**: `-project` resolves the caret with `SourceIndex`, which owns round 910's token
  snap-back, so no span semantics enter the checker at all.

- **OFF IS FREE, AND IT IS GATED AS SUCH.** Production adds one null-valued instance-field read plus a
  perfectly-predicted branch per node — the shape `SpineDispatch.mode` has had since round 732 — placed
  ABOVE the dispatch probe's early return, with **the node itself as the argument** (round 900: a guard
  cannot protect a derived one), plus one branch per FILE in `checkSpine`'s loop. The per-file field is
  null unless a span was requested in that file. No counter, diagnostic or emit path is touched.

- **GATES: suite 14,522 -> 14,548 / 0 failures / 0 errors / 3 skipped = EXACTLY the 26 new pins** (17
  `-project`, 9 core), core 14,305 -> 14,314. **`cost_gate.py` +0.00% on all 20 counters** — the real
  gate for this round, not a control, since `Checker.kt` grew 198 lines on the hot walk.
  `huge_methods.py --fail-over 0` clean on core (736 classes, `Checker.<init>` at 5,802) and on the
  module (10). `spine_closure_audit.py` 46 handlers, all closures supersets — run even though the hook is
  a prologue line rather than a masked handler, so it CANNOT be skipped by `spineEnterMask`. Warning-clean.
  No wall A/B: the change is one predicted branch over 856,962 nodes, which is far under the +-1.0% band,
  so counters are the defensible instrument (CLAUDE.md's standing rule).

- **DEFERRED, and queued as (API.3b)/(API.3c):** go-to-definition, and exposing the BATCH form —
  `TypeCaptureRequest` already takes a set of spans, so "semantic info for file X" is one compile away
  from being one compile, which is what makes hover practical for an editor. `quickInfoAt` currently
  builds per call and deliberately does not cache that build (a capture build types nodes the checker had
  no reason to type, so its diagnostics are not reusable — pinned).

**Round 910 (2026-08-17) — (API.2) LANDED IN TWO HALVES, AND THE ROUND'S REAL PRODUCT IS TWO **MEASURED
FACTS ABOUT OUR AST SPANS** THAT MAKE THE OBVIOUS IMPLEMENTATION WRONG: **`Node.end` IS THE END OF THE
TOKEN *FOLLOWING* THE NODE, SO SIBLING SPANS OVERLAP AND `[pos, end)` IS NOT A CONTAINMENT TEST.** ALSO
DECIDED THIS ROUND, BY READING RATHER THAN PREFERENCE: (API.3) IS **POSITION-DIRECTED CAPTURE**, NOT A
POST-HOC QUERY.

- **WHAT LANDED.** (a) A public `LineMap` / `TextPosition` (both 1-based, `Diagnostic`'s convention) with
  `Project.positionAt` / `offsetAt`; these read through the overlay and **deliberately do NOT build**, so
  a host can convert coordinates on a dirty project for free (pinned: `lists == 0`, `reads == 1`).
  (b) `Project.nodeInfoAt` returning a **value-typed** `NodeInfo(kind, start, end, ancestorKinds)`, over
  an `internal nodeAt` / `SourceIndex`. **No AST, `Symbol` or `Type` is published** — (API.3)'s surface
  decision stays open. **53 new pins** (LineMap 15, ProjectPosition 11, NodeSpanSemantics 6, ProjectNodeAt
  21); module 30 -> 83.

- **FINDING 1, VERIFIED IN SOURCE AND EMPIRICALLY: `Node.end` OVERSHOOTS BY A TOKEN.**
  `Parser.getEnd() = scanner.getPos()` (`Parser.kt:746`), read after the parser's one-token lookahead, so
  in `const abc = 1;` the identifier `abc` reads **`[6,11)`** where its text is `[6,9)`, and statement 1
  reads `[0,18)` where its text ends at 14. **Sibling spans therefore OVERLAP**: a caret on the `=` tests
  as inside `abc`, a caret on `let` as inside the previous statement. `SourceFile` is exact only because
  EOF is zero-width. **FINDING 2, the mirror trap: `Node.pos` is tsc's `getStart()`, NOT tsc's `pos`** —
  `Scanner.scan` sets `tokenPos` AFTER `scanLeadingTrivia()` (`Scanner.kt:331-333`), so trivia is already
  skipped and leading comments hang off `leadingComments` BELOW the span; a routine ported from tsc that
  adds a `getStart()` skip double-skips past the node's own first token. Both in CLAUDE.md, both pinned by
  `NodeSpanSemanticsTest` rather than left as prose.

- **AND THE FIX THE QUEUE ENTRY IMPLIED IS *REFUTED*, WHICH IS THE ROUND'S SHARPEST RESULT.** Bounding a
  node's end by the NEXT SIBLING'S `pos` looks sufficient and fixes the caret-on-`let` case — but in
  `const abc = 1;` the initializer starts at **12** while `abc` ends at 11, so `min(11,12) = 11` and the
  `=` at offset 10 is STILL inside `abc`. **The `=` is covered by no child at all, so no arithmetic over
  child positions can ever see it.** The sound rule is `realEnd = the greatest TOKEN end strictly below
  node.end` — one extra `Scanner` pass per parse, binary-searched, cached beside the tree. The context-free
  re-scan can only SPLIT a contextual token (a regex scans as `/`,`ab`,`/`), which adds ends and preserves
  every real boundary; a merge would make a span come out short and report the PARENT — coarser, never
  wrong-sibling, because the bound is never too high.

- **BOUNDARY CONVENTION, stated because an ambiguous primitive cannot be layered on:** half-open, so
  `offset == start` is inside and `offset == end` is outside — matching `Diagnostic.start`/`length`,
  `Node.pos`/`end`, `LineMap` and tsc's `getTokenAtPosition`. Consequence pinned: `abc|` is NOT on `abc`;
  tsserver's touch preference (`includePrecedingTokenAtEndPosition`) belongs a layer ABOVE, so a host asks
  at `offset` then `offset - 1`. Building it in would make two adjacent nodes both contain the boundary.

- **TWO PINS WERE MEASURED VACUOUS AND SAID SO RATHER THAN SHIPPED (now in CLAUDE.md).** A `.tsx`/`.jsx`
  fixture CANNOT pin that `computeParserFlags` was consulted — `Parser.isJsxFile` keys off the file
  EXTENSION, and `needsJsxFlag` drives a diagnostic, not the grammar. Nor can a top-level `await`: our
  parser produces an `AwaitExpression` with `topLevelAwait = false` too (two tests failed on the first run
  proving it). **The one option-derived GRAMMAR difference is `forceJsx` on a plain `.js` file** — that is
  the pin that landed, with a negative control.

- **THE ONE CORE CHANGE, AND WHY IT IS ONE WORD: `computeParserFlags` `internal` -> public**
  (`TypeScriptCompiler.kt`). The implementer hit it, REFUSED to hand-roll the flags, and stopped — correctly:
  its own KDoc calls it *"the single source of truth … so a crawl-time parse is provably the parse the core
  would produce"*, which is precisely the guarantee an out-of-core parse needs, and a duplicate would be
  **drift no test in the consuming module could ever see** (no `-Xfriend-paths` between modules, so nothing
  could compare against it). The flags are not cosmetic: `topLevelAwait` is true for any
  ESNext/ES2022/NodeNext/Preserve/System project and `needsJsxFlag` for every `.tsx`. The KDoc now says not
  to tidy it back.

- **(API.3) DECIDED BY READING `getTypeOfIdentifier`, NOT BY PREFERENCE** (committed separately,
  `a966ad76`): it consults `currentLocalTypes` — its own comment says *"populated during TS2322 checking
  walk"* — then `currentParamBindingNames`, `currentCheckFileName`/`fileLocalTypeMaps`, `currentFileLocals`,
  the inference-namespace chain, and only THEN the node-keyed lookup. At rest `currentLocalTypes` is an
  empty `HashMap` (`:636`) and both `current*` fields are null, so a post-hoc query **skips the first five
  reads**; for a function-body local that does not merely lose narrowing, it can resolve to an unrelated
  same-named global (the `useCaseSensitiveFileNames` failure documented in that very function). So the
  design is **capture during the walk**, and the queue entry carries the spine-closure constraint (round
  888's mask) a next agent would otherwise lose a round to.

- **FOUND IN PASSING, QUEUED AS (BUG.1): the compiler disagrees with itself about a lone `\r`** —
  `Parser.computeLineStarts` breaks the line there, `Checker.lineStartsFor` counts `\n` only, so a SYNTAX
  diagnostic and a SEMANTIC one number the lines differently on classic-Mac text. `\n` and `\r\n` are
  identical under both, which is why no corpus baseline can see it.

- **GATES: suite 14,469 -> 14,522 / 0 failures / 0 errors / 3 skipped = EXACTLY the 53 new pins**, XML-parsed
  across all six modules, with core unchanged at 14,305 (the visibility change is behaviour-free).
  `cost_gate.py` **+0.00% on all 20 counters** — here a real control rather than a tautology, since core
  bytecode DID change (`internal` -> public removes JVM name mangling), and the gate proved live by running
  a real compile (46 errors / 78 files). `huge_methods.py --fail-over 0` clean on core (732 classes) AND on
  the module (9, up from 3 — the gate saw the new code). Build warning-clean. Ablation: `realEndOf`
  returning raw `node.end` reddens **exactly the 7 predicted pins**, restored by hand from a scratchpad copy
  (never `git checkout`, which would have destroyed the round's uncommitted work).

**Round 909 (2026-08-17) — (API.1): A NEW ARC, ON OWNER DIRECTIVE — THE **PROJECT / LANGUAGESERVICE
EMBEDDING API**, WHICH IS WHAT THE CHECKER-SIDE PERF POOL BEING EMPTY (round 908) MAKES ROOM FOR.
SLICE 1 LANDED: A NEW MODULE, A PUBLIC `Project` THAT ANSWERS DIAGNOSTICS AND ACCEPTS **IN-MEMORY
EDITS**, AND **30 PINS**. THE ROUND'S TWO REAL PRODUCTS BESIDES THE CODE ARE A **VACUOUS-FIXTURE
TRAP** AND THE FINDING THAT **(ART.1) IS STALE AS WRITTEN.**

- **THE DIRECTIVE.** The owner re-prioritised delivery of the Project and LanguageService APIs over
  the perf queue (ART.1 stays opportunistic). Answered scoping: a **Kotlin embedding API first**
  (LSP/tsserver layered later, not now), in a **new module**, first slice **Project + diagnostics +
  edits only** — no editor features, and deliberately no stub facade for them.

- **WHAT LANDED.** New module `xemantic-typescript-compiler-project` (jvm() only, `explicitApi()`,
  `api(project(":…-core"))`, mirroring `-cli`; sources in `commonMain` so a native target is later a
  build-file change and not a source move). `Project.open(projectPath, vfs = SystemVfs)` +
  `configPath` / `files` / `diagnostics()` / `diagnostics(fileName)` / `updateFile` / `deleteFile` /
  `close()`, plus an `internal OverlayVfs`. **The only pre-existing file touched is
  `settings.gradle.kts`** (2 insertions) — zero bytes of core, which is why `cost_gate.py` was not
  run: on this diff it is a tautology, not a control.

- **THE ARCHITECTURAL FACT THE API HAD TO BE SHAPED AROUND, STATED IN ITS OWN KDoc RATHER THAN HIDDEN:
  A QUERY ON A DIRTY PROJECT IS A *FULL REBUILD*, AND THAT IS THE COMPILER'S PROPERTY, NOT A SHORTCUT
  TAKEN HERE.** `ProjectCompiler.Result` is a flat value (paths, diagnostics, an import graph) that
  retains **no AST, no `BinderResult` and no `Checker`** — the checker's construction IS the
  compilation (`docs/ARCHITECTURE-RETHINK.md:850`). What makes a re-query cheap anyway is the
  process-global **CONTENT-keyed** `CrawlParseCache`, and that same keying is why an overlay edit
  **cannot be served a stale parse**: there is no mtime/size/stat anywhere in the decision (round
  871). **Do not add "incremental" reuse on top of `Project`; the seam does not exist yet.**
  Every build passes `noEmit = true` — a tool that opens a project to ask questions must never
  scatter JavaScript from unsaved buffers through the user's tree.

- **THE OVERLAY IS THREE MECHANISMS, NOT ONE, AND EACH IS PINNED SEPARATELY.** An added file must
  survive three questions asked by three different layers: `ModuleResolver` probes `exists` before
  `readText` (fail it -> TS2307 however readable the text); `ProjectCompiler.walk` asks `isDirectory`
  per entry and descends only on yes (fail it -> a file in an overlay-only directory is invisible);
  the glob discovers roots through `list` alone. `list` is SORTED deliberately — program order decides
  which file first touches a shared type node, so an unsorted union would make two builds of the same
  overlay state differ. **Ablation, one mistake at a time: dropping the overlay-children clause from
  `isDirectory` reddens exactly 2 pins and nothing else.** The fix/introduce pair is airtight by
  construction (the backing store holds the opposite text, so neither an always-stale nor an
  always-empty result satisfies both), and the caching pins assert read-count EQUALITY across a second
  query and GROWTH after an edit — both directions of the dirty flag.

- **THE VACUOUS-FIXTURE TRAP, VERIFIED IN SOURCE AND NOW IN CLAUDE.md — IT COST THREE TESTS THAT WERE
  GREEN WITH AN *EMPTY* DIAGNOSTIC LIST.** Two independent gates suppress TS2307: the unresolved-module
  region returns early on `binderResults.size <= 1 && !isMultiFileSource` (`Checker.kt:45409,45853`)
  and **the real libs bind through their own path and do not count**, so a two-file fixture whose
  second file IS the missing import reduces to ONE program file; and the relative-specifier leg
  demands `options.module in ES_MODULE_KINDS` (`:46098` — ES2015/2020/2022/ESNext/Preserve) with five
  resolution keys unset, so a tsconfig carrying only `target`/`strict` leaves `module` unset and every
  unresolved-import assertion is vacuous. **An import pin needs a negative control or it measures its
  own vacuity** — this round's does.

- **(ART.1) IS STALE AS WRITTEN, AND THE QUEUE ENTRY IS CORRECTED BELOW RATHER THAN WORKED.** It says
  "CI currently ships the Community Edition arm, which has no PGO at all". In fact `native.yml:60-72`
  **already builds Oracle GraalVM + PGO** through `scripts/build-native-pgo.sh`, verifies byte-identity
  against the JVM and uploads `xtsc-linux-x64`; `bench.yml` builds the Oracle **BASE** image per push
  **deliberately** (the PGO cycle is too slow to pay per push for a non-headline column). What actually
  remains is **attaching the binary to releases — the owner decision already tracked as (AOT.1)**, not
  a perf lever. It is also **unmeasurable on this box: no GraalVM is installed** (Zulu 26 /
  OpenJDK 25 only). A comment-only `bench.yml` correction found uncommitted in the tree was landed
  separately (`4c74eae4`) because its header and its own build step contradicted each other.

- **AN INSTRUMENT BLIND SPOT THE SIXTH MODULE CREATED, ALSO IN CLAUDE.md: `huge_methods.py` IS
  `-core`-ONLY BY DEFAULT, SO ITS GREEN RUN HERE WAS A CONTROL AND NOT A GATE.** The tell was a
  `classes scanned : 732` identical to round 907's; passing `--classes
  xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main` scans the new module's **3**
  classes, 0 over the limit. Round 853's law, one module over.

- **GATES: suite 14,439 -> 14,469 / 0 failures / 0 errors / 3 skipped = EXACTLY the 30 new pins**,
  counted by XML parse across all **six** modules (the glob is `*/build/test-results/jvmTest/*.xml`;
  the root-level form matches nothing post-split). `huge_methods.py --fail-over 0` clean on core (732
  classes) AND on the new module (3). Build warning-clean. `cost_gate.py` deliberately not run
  (tautology — see above); no wall A/B and nothing to A/B.

**Round 908 (2026-08-15) — (SPINE.1): THE LAST CHECKER-SIDE ITEM IS **REFUSED AND CLOSED**. 40% OF THE
WARM REBUILD LIVES IN SIX HANDLERS AND **91-100% OF IT IS THE TYPE SYSTEM DOING ITS JOB**. THE ONE ROW
THAT LOOKED LIKE A LEVER — 79.8 ms OF FRAME-AMBIENT INSTALL — HAS A **~8 ms** DELETABLE POPULATION AND
FAILS ITS OWN DIVISION BY **~20x**, BECAUSE **A TIMESTAMP IS AN OPTIMIZER BARRIER.**

Instrument only, two `Checker.kt` lines behind a call-site mode test.

- **(A) THE DENOMINATOR, RE-TAKEN — AND ROUND 847's TABLE WAS 60% STALE IN ms.** Eight probe-free warm
  process medians: 4,794 / 4,981 / 5,206 / 5,058 / 5,003 / 4,877 / 5,203 / 5,276 → **mean 5,050 ms**,
  range 9.6%. So 1% = 50.5 ms and the ~17 ms floor is **0.34%** here. All 22 instrumented rebuilds
  answered 78 files / 46 errors.

- **(B) THE FRESH WARM PER-HANDLER TABLE, AND ROUND 830's LAW DEMONSTRATED LIVE.** (Round 847's column
  is **STALE** — against 8,095 ms — and is quoted only as a share.)

  | handler | net ms today | % warm | *r847 ms (stale)* | *r847 %* |
  |---|---:|---:|---:|---:|
  | `spineCtaM3StatementAnchor` | **620** | **12.28%** | *853* | *10.54%* |
  | `cpaSpineLeave` | **584** | **11.56%** | *617* | *7.62%* |
  | `ccetSpineLeave` | **433** | **8.57%** | *876* | *10.82%* |
  | `spineIanyEnterNode` | **147** | **2.91%** | *171* | *2.11%* |
  | `ctaSpineEnter` | **129** | **2.55%** | *359* | *4.43%* |
  | `spineArithEnterNode` | **113** | **2.24%** | *153* | *1.89%* |
  | **the six** | **2,025** | **40.1%** | *3,029* | *37.4%* |

  Same six, still 62.6% of the probed spine (847: 63.0%) — but **the order swapped again**:
  `ccetSpineLeave` went #1 -> #3 (**−51% in ms**) while `cpaSpineLeave` **fell 5% in ms and ROSE
  7.62% -> 11.56% in share**. That is round 830 exactly: *a rising share is evidence the denominator
  shrank.* Partition check against the independent `spine` tier: 3,234 vs 3,104 = **104.2%**.

- **(C) ROUND 733's DEFLATION WAS *MEASURED*, NOT APPLIED — AND `SpineSections` RAN WARM FOR THE FIRST
  TIME** (rounds 733/799 read it cold; it was given a `BenchMain` tier this round).

  | probe | object | net ms | checking | bookkeeping |
  |---|---|---:|---:|---:|
  | `cta` A | `spineCtaM3StatementAnchor` | 640 | **94%** | 37 ms |
  | `cpa` P | `checkPropertyAccessInExpr` | 462 | **~100%** | <=0 |
  | `call` | `checkSingleCallExpressionTypes` | 381 | **93%** | 28.5 ms |
  | `spinesections` | both `…SpineLeave` handlers | 912 | **91.4%** | 80 ms |

  Round 733's split re-derived warm: passes' own work **91.4%**, ambient install+restore **8.7%**,
  outside-the-ambient **~0**, the three ancestor climbs **2.1% (19.6 ms)** — *the same 2.1% it read
  cold*. **Every frame pop and every restore is at or below one probe boundary**, and five of the
  eleven sections read NEGATIVE once their own boundary is subtracted.

- **(D) NOTHING CLEARS THE FLOOR.** Largest is the three ancestor climbs at **19.6 ms (0.39%)** —
  round 733's hypothesis #1, refused again (73/213/32 ns per call at depth 6/9, and a classifier is
  consulted once per node, so a memo can never answer its own query — round 875's law). Then the `cta`
  frame+ambient install at **16.0 ms**, load-bearing; and the `cta` eligibility gate at **14.4 ms**,
  where **round 888's mask already took 87% of its population** (915,543 -> 120,026 consultations).

- **(E) THE ROW THAT LOOKED LIKE A LEVER, AND THE NEW LAW THAT KILLED IT.** The two frame-ambient
  installs measure **79.8 ms = 1.58%** — the round-869 per-scope-copy shape, and the only thing in the
  region above 1%. A census (deterministic, identical in all four draws) says the "O(frames) rebuild"
  walks **2.91 frames** (max 8), **produces nothing on 91.4% of installs**, and the save copies **ZERO
  entries on 100%** of 147,572 installs: deletable population ≈ **8 ms**, half the floor. And the row
  fails round 896's divide-by-population test by **~20x** — 676 ns for ~16 `putfield`s and an empty
  copy — because **A TIMESTAMP IS AN OPTIMIZER BARRIER: bracketing a run of field save/restores forces
  stores that production coalesces away.** Every section probe over a field-shuffling region in this
  repo is inflated for the same reason.

- **(F) TWO CORRECTIONS A NEXT AGENT NEEDS, BOTH ALSO IN CLAUDE.md.** The **`dispatch` tier bypasses
  `spineEnterMask`**, so the per-handler table above prices the **pre-888 regime** (~73 ms on its
  total) and is structurally blind to the lever that region already banked. And today's `CtaSections`
  is not comparable to round 850's, for the same reason.

- **(G) WHAT THIS CLOSES.** (SPINE.1) was the last checker-side queue item and
  `reach-machinery.md` § 9's "remaining named place with more than 1% in it". It is now measured out.
  **That makes SIX consecutive priced refusals (rounds 903-908) and an EMPTY checker-side pool.** The
  named, already-measured levers that remain are **(ART.1)** the PGO'd native image (−21.2% check-only
  / −19.1% emit, 5/5 paired, byte-identical output) and **(ART.2)** CRaC (3.4x, blocked on one known
  cwd defect with a known fix) — both an order of magnitude larger than anything left in the checker.

- **(H) GATES.** Suite **14,437 -> 14,439 / 0 failures / 0 errors / 3 skipped** = exactly the 2 new
  pins, verified by XML parse across all four modules. `cost_gate.py` **+0.00% on every counter**.
  `huge_methods.py --fail-over 0`: clean. The two `Checker.kt` lines sit behind a **call-site** mode
  test — round 900's law in its sharper form, since `sec >= 0` is true in production and a callee
  guard could not have protected the three `size` reads.

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
