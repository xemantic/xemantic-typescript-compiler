# (WARM.10) — the WHOLE-PROGRAM REGEX CENSUS, and the one offender left

*Round 863, 2026-08-09. Seventeenth in the sequence `dispatch-table.md` (732) →
… → `warm-tail-attribution.md` (859–862) → here. Unlike its predecessors this
document is not an attribution of a region: it is a **systematic sweep of a
defect class** that rounds 860 and 862 each hit by accident, promoted so that
the next agent does not have to find the fourth member by accident either.*

> **HEADLINE — FOUR THINGS.**
>
> **(1) THE CLASS, STATED SO MEMBERSHIP IS DECIDABLE.** A regex applied to the
> FULL TEXT of every source file, whose pattern does not begin with a literal
> run of **at least four** characters, is attempted at *every position* of that
> text. `java.util.regex`'s `Pattern.compile` hands a pattern whose first node
> is a literal `Slice` to `BnM.optimize` (Boyer-Moore); `BnM.optimize` returns
> the node unchanged when the slice is shorter than 4, and never sees a pattern
> that begins with `\b`, `(?m)^`, a character class, an alternation or a
> lookaround at all. The root is then a `Start` node whose `match` **loops over
> every offset**. § 1.
>
> **(2) THE CENSUS IS COMPLETE AND THE ANSWER IS ONE.** All 110 `Regex`
> construction sites in `commonMain` were classified by SUBJECT and by literal
> prefix (§ 3, the full table). Twenty-one have a WHOLE-FILE subject. On the
> compiler profile exactly **one** of them is both ungated and prefix-less:
> `Transformer.transform`'s jsxRuntime pragma scan. Every other whole-file
> matcher is either gated to zero here by a file-kind or option test, or carries
> a usable literal prefix. § 3 and § 4.
>
> **(3) IT WAS INVISIBLE BECAUSE IT IS ON THE EMIT PATH.** Round 738's
> `skipEmitOutputs` gate means `--noEmit` never enters `Transformer.transform`,
> so `BenchMain` (check-only in three hard-coded places), `cost_gate.py` and the
> `--noEmit --listAll` 8-profile grid were blind to it **at once**. Measured
> with an EMIT-mode harness built this round: **44.1 ms = 0.55% of a warm emit
> rebuild**, 5.2% of `Transformer.transform`, scanning 9,977,097 characters and
> finding **0** pragmas. After the rewrite: **1.66 ms**, a **96.2%** fall. § 5.
>
> **(4) THE CLASS IS NOW EXHAUSTED ON THIS PROFILE, AND THAT IS MEASURED, NOT
> ARGUED.** A JFR discovery run over the same warm EMIT compile: **64 of 9,541
> samples** touch `java.util.regex`, and **54 of those 64 are
> `Transformer.transform`**. The remainder is `RealLibResolver.referencedLibNames`
> (7 samples ≈ 6 ms ≈ 0.07%) and three singletons. There is no fourth offender
> above the round's 0.2% floor. § 2.
>
> Banked by the class across rounds 860 + 862 + 863: **~273 ms**, of which
> ~230 ms is check-only and ~42 ms emit-only.

---

## 1. The mechanism, in one paragraph

`Pattern.compile()` ends with a peephole step: if the compiled `matchRoot` is a
`Slice` it is passed to `BnM.optimize`, which builds a Boyer-Moore skip table —
but **returns the node unchanged when the pattern's literal is shorter than 4
characters** ("a shift larger than the pattern length cannot be used anyway").
If the root is a `Begin` or `First` node it is used directly. Otherwise the root
becomes `Start`, whose `match` is a `for` loop over every offset from the search
start to `to - minLength`.

Consequences a reader should keep:

* `\b…`, `(?m)^…`, `(?:a|b)…`, `[abc]…`, `(?=…)…` — **no** Boyer-Moore. Every
  offset is attempted.
* A bare `^` **without** `(?m)` compiles to `Begin`, which is attempted at ONE
  position. Such a pattern is cheap and is marked `ANCHORED-BEGIN` in the table
  below — do not confuse the two.
* A literal prefix of 1–3 characters is **also** no Boyer-Moore. That is what
  bit this round: `/\*` is two characters.
* The per-offset work is small; the cost is the offset COUNT. Ten megabytes is
  ten million attempts, and that is tens of milliseconds however trivial the
  rejection.

Round 862 measured the controlled comparison inside one function: two patterns,
same text, same loop, one with a literal prefix and one without — a **7×**
difference.

---

## 2. The empirical leg — JFR, used for DISCOVERY only

The static census (§ 3) is a claim about what the code does. It was falsified
against a measurement, because a census that misreads one site's subject
reports the same thing as a census with no offender.

One warm EMIT process (`BenchMain <proj> 3 8 "" emit`, JFR
`settings=profile`, `jfr print --stack-depth 400`), aggregated by the first
`com.xemantic` frame of every sample carrying a `java.util.regex` frame:

| first xemantic frame | samples | share of all samples |
|---|---:|---:|
| `Transformer.transform` | **54** | 0.57% |
| `RealLibResolver.referencedLibNames` | 7 | 0.073% |
| `ProjectCompiler.collectRootFiles` (the include/exclude globs) | 1 | 0.010% |
| `Parser.checkTripleSlashSelfReference` | 1 | 0.010% |
| `Transformer.generateModuleTempName` | 1 | 0.010% |
| **all `java.util.regex` work** | **64 of 9,541** | **0.67%** |

Two things this is and is not. It **is** a completeness check: the class's total
cost on this profile is 0.67% of wall and 84% of it is one site, so a census
that named a different site would be refuted here. It is **not** a price — round
623's law stands, a JFR leaf-frame self-% is not a wall-clock figure, and the
0.55% quoted for the pragma scan comes from an in-situ probe, not from this
table. That the two agree to within a sample or two is a cross-check, not a
derivation. Note also that the first attempt at this table read `<truncated>`
for 59 of 60 samples: `jfr print` truncates its DISPLAY to five frames unless
`--stack-depth` is passed, which looks exactly like "the JVM did not record the
caller".

---

## 3. § THE CENSUS — all 110 `Regex` sites in `commonMain`, by subject

Classification of the SUBJECT (what the matcher is applied to):

* **WHOLE-FILE** — the full text of a source file: potentially millions of chars.
* **WHOLE-JSON** — the full text of a `package.json` / `tsconfig.json`: kilobytes.
* **SUBSTRING** — a bounded slice: a node span, a JSDoc block, one line, one token.
* **SMALL** — a diagnostic message, a name, a path, a type-display string.

`literal prefix` answers the § 1 question: **YES** = a literal run of ≥ 4
characters at the very start; **NO** = anything else; **ANCHORED-BEGIN** = a
bare `^` without `(?m)`, i.e. a `Begin` root, attempted once.

### 3.1 WHOLE-FILE subject, NO literal prefix — the candidate class

| site | pattern | frequency + gate | on this profile |
|---|---|---|---|
| **`Transformer.kt:488`** | slash-star `\s* @jsxRuntime \s+ (classic\|automatic) \s*` star-slash | per FILE, **NO GATE**, every emitted file | **THE OFFENDER — 44.1 ms, 0 matches. FIXED this round.** |
| `TypeScriptCompiler.kt:2462` | `(?m)^\s*(?:import\|export)\b` | per file at every parse site; short-circuited by `content.contains("await")` AND by `module ∈ {ES2022, ESNext, NodeNext, Preserve, System}` | **0 ms** — the profile is NodeNext, so the `\|\|` short-circuits. LATENT on a `commonjs` project. |
| `Checker.kt:10930` | `\brequire\s*\(` | per binder result, gated `isJs && !isModuleFile` | **0 ms** — 0 `.js` files. LATENT on any JS-bearing project. |
| `Checker.kt:10929` | `(?m)^\s*export\s+as\s+namespace\s+…` | per binder result, gated `isDtsFile` | **0 ms** — 0 `.d.ts` files. LATENT on a `@types` tree. |
| `Checker.kt:86466` | the same UMD pattern | gated `isDtsFile` **and** an AST `export = X` test evaluated first | 0 ms — round 859 measured this one at 0.0 ms in every draw; it is the control that proved a guard fixes it. |
| `Checker.kt:160793` | `/**` … lazy block scan | per checked result, gated only `isJsLikeFileName \|\| isDtsFile` — **no option gate**, unlike its three siblings | 0 ms here; the cheapest gate of the four JSDoc block scans. |
| `Checker.kt:160764 / 160850 / 160905` | the same lazy block scan, three more copies | gated `checkJs` (+ `noImplicitAny`/`strict` for two) | 0 ms here. Four independent full-text scans of the same text on a `checkJs` project; one shared block index would serve all four. |
| `Checker.kt:29474 / 29486` | `@(?:typedef\|property)…` | per JS file containing `@typedef`/`@property` | 0 ms here. The alternation right after `@` denies the prefix the two scans BESIDE them (`@typedef…`, `@template…`) get for free. |
| `TypeScriptCompiler.kt:1287` | `(?:require\|from)\s*\(?\s*['"]…\.json['"]` | per source file, gated `options.resolveJsonModule` | 0 ms here (option off). |
| `TypeScriptCompiler.kt:569` | `\bfrom\s*(["'])…` | gated `outDir != null && rootDir == null` + a non-empty external-file set | 0 ms here. |
| `TypeScriptCompiler.kt:2196` | `\brequire\s*\(\s*["']…` | per program file, but only when `containsDeclareRequire(text)` — round 862's scanner | 0 ms — 0 of 78 accepted. |
| `Transformer.kt:3180 / 3849` | `\b<escaped name>\b`, recompiled per import/alias | gated `isolatedModules` / `hasStaticModuleDeclarations` | 0 ms here. |
| `Checker.kt:29335` | `\b<name>\s*=[^=]` | gated `checkJs` + JS-like + a literal-initialized top-level var | 0 ms here. |
| `RealLibs.kt:284` | `///\s*<reference\s+lib\s*=\s*"…"\s*/>` | once per INCLUDED embedded lib file | **~6 ms** (7 JFR samples). 3-character prefix; grows with the `lib` list. Below the round's floor. |
| `TypeScriptCompiler.kt:2406` | `(?:\bfrom\s+\|\bimport\s*\(\s*)…` | outDir set + exactly ONE non-`node_modules` source file | 0 ms here. |

### 3.2 WHOLE-FILE subject, WITH a literal prefix — not the class

`Checker.kt:29002` (`@type`), `29452` / `29547` / `33413` / `97463` / `97474` /
`125717` / `125733` / `160179` (`@typedef`), `29456` / `29560` (`@template`),
`29571` (`@typedef`), `164531` (`BuildTree`), and `TypeScriptCompiler.kt:2195`
(`import`). Each begins with a ≥ 4-character literal, so `BnM.optimize` applies
and the scan is a Boyer-Moore skip over the text. Round 862 measured the last of
these at **a seventh** the cost of its prefix-less sibling over the same text.

### 3.3 The rest — not whole-file, and therefore not this class

* **WHOLE-JSON (17 sites)** — `Checker.kt:13079 / 13123 / 13228 / 45988 /
  46582 / 44486 / 137292`, `TypeScriptCompiler.kt:2300 / 2402 / 2423 / 2429 /
  3027 / 3028 / 3029 / 3057 / 3067`. Subjects are kilobytes, and most are
  additionally gated on a `contains` pre-filter or an option.
* **SUBSTRING (≈ 35 sites)** — node spans (`Checker.kt:76716`, `12633`,
  `12684`, `Transformer.kt:13384 / 13392`), single JSDoc blocks
  (`Checker.kt:160765 / 160794 / 160837 / 160851 / 160906 / 160907 / 29193 /
  44636 / 97479 / 125738`), single lines (`Parser.kt:219 / 606 / 607 / 608`,
  `Checker.kt:12289`, `TypeScriptCompiler.kt:2332 / 2524`), tsconfig blocks
  (`CompilerOptions.kt:867 / 941 / 961 / 965 / 991 / 1010 / 1018 / 1202 /
  1206`), and type-node text (`Checker.kt:42139 / 70722`).
  One is worth a note: **`Checker.kt:38742`** applies `\bdefault\b` to
  `source.substring(0, stmtPos)` — a PREFIX of the whole file, unbounded above —
  and collects every match into a list to take the last. It is on the TS1319
  emit path only, so it is rare, but it is the one SUBSTRING site whose subject
  scales with the file.
* **SMALL (≈ 20 sites)** — diagnostic messages (`Checker.kt:91789 / 91794 /
  91805 / 135223 / 135224 / 164603`), identifiers, paths, glob-derived patterns
  (`ProjectCompiler.kt:603`), attribute values (`Checker.kt:160998 … 171925`).
  Several are recompiled per call inside a loop; at these subject sizes
  `Pattern.compile` dominates the match, but the totals are microseconds.
* **NOT IN THE COMPILE PATH (2 sites)** — `UmdExportAsNamespace.kt:59` (round
  860) and `DeclareRequireScan.kt:65` (round 862). Both are kept LIVE **only**
  as the oracles their differential pins compare a hand-written scanner against.
  `JsxRuntimePragmaScan.kt:67` joins them this round.

---

## 4. What the census says that the measurement alone would not

**Most of the class is GATED TO ZERO ON THIS PROFILE, NOT ABSENT.** Nine
whole-file prefix-less matchers exist and cost nothing here only because tsc's
own sources carry no `.js`, no `.d.ts`, no `resolveJsonModule`, and a NodeNext
module setting. On an `allowJs` project, four independent lazy
`/**`-block scans run over the same text; on a `commonjs` project every file
containing the word `await` gets a `(?m)^` sweep at **every parse site**; on a
`@types` tree the UMD pattern comes back.

That is a statement about SHAPE, and this round deliberately does not act on it:
round 792's law is that a profile which does not contain a shape cannot falsify
a claim about it, and the arc's rule is that a change is justified by a measured
prize. These sites are recorded here so a future agent measuring a different
project shape knows where to look first, and so nobody re-derives the table.

**The one rule worth adopting now** is the cheap one: *a new whole-program regex
needs a literal prefix of at least four characters, or it does not go in.* Every
member of this class was written without anyone asking that question.

---

## 5. § THE FIX, MEASURED

`BenchMain <proj> 3 8 frontend,frontend emit`, two processes × two draws per
arm, all 8 instrumented rebuilds answering **78 files / 46 errors**. The probe
is `FrontEnd.TR_JSXPRAGMA`, one timestamp pair per FILE inside `TRANSFORM`.

| arm | draws (ms) | mean | spread | census |
|---|---|---:|---:|---|
| before (`fa2e5f27`) | 45.886 / 44.092 / 43.243 / 43.336 | **44.139** | 6.0% | 78 files, 9,977,097 chars, **0 pragmas** |
| after (`49256c56`) | 1.711 / 1.665 / 1.637 / 1.637 | **1.662** | 4.5% | identical |

* **saving 42.48 ms = 0.531%** of the before arm's warm emit wall (median of
  medians 7,994 ms), and the row falls **96.2%**.
* Cold, the same row reads **62 ms** in a single-iteration run — so it warms
  1.4×, against `checkSpine`'s 3.27×, exactly the ratio round 859 measured for
  the other members of this class.

**Round 793 — subtract the removed boundaries before quoting a row delta.**
Nothing is removed: `TR_JSXPRAGMA` opens and closes 78 times in BOTH arms, and
the probe is present in the before arm. There is no boundary correction to make.

**Round 801 — produced-vs-consumed BEFORE the timing.** Nothing MOVES. The
scan's only output is one boolean per file; the census reads 0 pragmas in both
arms, so the consumer's answer is bit-identical and the emitted tree is
byte-identical (§ 6). This is not a skip whose work reappears in a later asker —
it is the same value computed by a cheaper method.

**Round 846 — the first instrumented rebuild in a process is the slowest draw.**
It reproduces in 1 of 2 before-arm processes (45.886 > 44.092; the other pair is
43.243 < 43.336, a 0.2% inversion). Dropping every first draw moves the before
mean to 43.714 and the saving to 42.06 ms = 0.526% — inside the quoted figure.

**THE PARENT ROW CANNOT SEE THIS, AND SAYING SO IS PART OF THE RESULT.**
`Transformer.transform` reads 782 / 789 / 894 / 919 ms before and 860 / 862 /
889 / 948 ms after — its own draw spread is 17.5% and there is a ~14%
BETWEEN-PROCESS effect inside the before arm alone, i.e. three times the 42 ms
being measured. The sub-row is the measurement; the parent row is noise at this
resolution, and a before/after comparison of it would have reported the wrong
sign. That is the same lesson round 854 drew about differencing a `--passTiming`
row across arms, one level further in.

---

## 6. What the gates saw, and which one could see it

| gate | result |
|---|---|
| suite (all four modules, real XML parser) | 14,072 → **14,090 / 0 failures / 3 skipped** |
| `cost_gate.py` | **+0.00% on all 20 counters** (a control: the change is not on the check path) |
| `huge_methods.py --fail-over 0` | **0 over the limit**, 653 classes |
| 8-profile grid, BOTH directions, two class dirs (652 vs 653) | **`added=0 removed=0` on all eight**, no capture empty or truncated |
| **EMIT mode, compiler profile, `diff -r`** | **78 files each arm, IDENTICAL** |

**The grid is the control and the emit tree is the gate.** This value reaches no
diagnostic at all — only the shape of emitted JS — so a `--noEmit --listAll`
capture is structurally unable to see a mistake in it. That is the same
asymmetry round 862 recorded, and it is why the emit `diff -r` is the load-bearing
line in this table.

---

## 7. The ablation — six mistakes, one at a time (round 807)

26 pins ran per arm.

| mistake | RED | uniquely its own |
|---|---|---|
| **M1** the non-overlap cursor dropped | 2 | `a candidate overlapping the previous match is skipped exactly as findAll skips it` |
| **M2** the `\s` class widened to `Char.isWhitespace()` | 2 | `negative control - a non-breaking space is whitespace to Kotlin but not to the pattern` |
| **M3** the forward `\s+` weakened to `\s*` | 2 | `negative control - shapes the pattern does not admit report nothing` |
| **M4** the transformer takes the FIRST pragma, not the last | 1 | `the last pragma in the file wins` |
| **M5** the harness's 5th argument defaults instead of failing | 1 | `negative control - an unknown 5th argument is refused rather than defaulted` |
| **M6** the scanner finds NOTHING | 6 | both END-TO-END positive controls |

**Every mistake has at least one uniquely-its-own failure and no two failing
sets coincide.** Two notes an honest reading requires:

* **The 12,000-case differential battery never fails ALONE** — it reddens under
  M1, M2, M3 and M6 and is the unique witness of none. That is not a redundant
  guard in round 807's sense: it is the general net, the only pin that would
  catch a divergence nobody anticipated. But it cannot attribute, and the
  specific pins beside it are what do.
* **M6 is why the positive controls are in the class.** An all-empty scanner
  agrees with the oracle on every case the oracle also rejects, so the battery is
  weakest against exactly the mistake that silently disables the feature; only a
  fixture that DOES carry a pragma and checks the EMITTED runtime sees it.
* **M4 is the one that matters most**, on round 862's pattern: taking the first
  pragma instead of the last is silently WRONG rather than merely slow — it
  changes which runtime a file emits, and nothing anywhere reports it.

---

## 8. What this does NOT show

* **One profile.** Every "0 ms" in § 3.1 is a property of tsc's own sources.
  The nine gated members of the class are latent, not absent, and § 4 says which
  project shape wakes each one.
* **The emit-mode saving was measured; the emit-mode WALL was not A/B'd.** The
  arm walls (7,994 → 7,729 ms median of medians) move in the right direction by
  far more than 42 ms, which is drift, not evidence — the row is the evidence.
* **No cold measurement of the fix.** The row reads 62 ms in a cold
  single-iteration run and 44 ms warm, so the cold saving is probably slightly
  larger; that is an inference from one draw, not a measurement.
* **The JFR table prices nothing** (round 623). It is a completeness check on
  the census and is quoted as one.
* **`RealLibResolver.referencedLibNames` was left alone** at ~6 ms (0.07%), as
  were the twenty-odd recompile-per-call sites. Below the round's 0.2% floor the
  risk of touching a scanner is not worth the return, and saying which ones were
  left is part of the sweep.
