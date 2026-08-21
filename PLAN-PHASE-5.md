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

**(KIR.PERF) THE BACKEND, MEASURED FOUR TIMES AND MOVED −17% — AND THE ONE
DIRECTION THAT LOOKS OBVIOUS IS NOW REFUTED TWICE (2026-08-21).**

**THE RESULT.** `smol-toml` on the JVM goes **56.60 → 47.05 us/parse (−16.9%)**,
i.e. 2.49x slower than the same library on Node down to **2.08x**. The last four
runs cover only changes that measured inside the band and read 46.95 / 48.00 /
47.75 / 47.05, so that is a replicated number and not a draw. **`mitt` moves only at the end** — 62.25 -> 61.00,
still 1.39x faster than Node — which is the expected shape: an event emitter
barely compares characters, barely reads properties and never matches a regular
expression, so none of this session's levers has anything to do there. Every figure is a within-round paired delta on
`scripts/kir-bench.sh` with 5 interleaved processes per arm, and **both Node
arms held flat across every pair** (tsgo 452/455/453/451 ms, ours 448/445/447/453),
which is what licenses reading these as backend numbers rather than as box
weather. `docs/perf/kir-backend-levers.md` carries the table.

**LEVER 1, THE WIN: an operand the lowering already typed no longer takes the
boxed path (−13.6% on toml, ranges DISJOINT; nothing on mitt).** `===`, `!==`, `==`, `!=`, a `switch`
clause, a condition and a string conversion all went through an `Any?` entry
point, so `s.charCodeAt(p) === 0x20` — what a hand-written scanner's inner loop
is made of — boxed BOTH operands and then walked an `instanceof` chain to
rediscover what the lowering had proven. `+` has decided by the erased operand
types since the beginning (`addValues`); this is that rule reaching the rest of
the family and nothing else. The semantics are pinned rather than argued
(`KirEqualitySemanticsTest`, `KirPrimitiveOperandTest`): `NaN !== NaN` and
`0 === -0`, `-0`/`NaN` falsy but the STRING `'0'` truthy, `1 == true` and
`null == undefined` true so no MIXED case may specialize, and the left operand
evaluated first in both half-specialized directions.

**LEVER 2, REFUTED, REVERTED, AND IT IS THE MOST USEFUL THING HERE.** A
per-owner leaf census (`scripts/kir-profile.sh`, new) charges **44.3%** of the
toml arm to the property bag — `JsObject.set` 25.6%, `get` 17.3% — so it is
plainly the largest cost. Giving `JsObject` the shape its LITERAL declared,
promoted at the first UNDECLARED key (chosen precisely to leave alone the
dictionary half that killed the 2026-08-21 size-threshold attempt), measured
**+31%, ranges disjoint**. The rule worked — `HashMap` fell from 38.3% of
samples to **4.7%** — and the saving did not exist: counted in SAMPLES rather
than shares, the bag cost **709 before and 771 after**, and the rest of the
regression landed on `program.*` and regex frames that did not change.

So: **two independent attempts at making the dynamic representation cheaper
have now cost 21% and 31%. The bag is expensive in the NUMBER of operations,
not in their unit cost**, and only the nominal half removes them. (KIR.PERF.1)'s
case is now made by measurement from both directions.

**LEVER 3: three rows the census named, two of them pure overhead (−4.0%).**
`jsTruthy` decided its answer with an equality `when`, which Kotlin compiles to
a chain of `Intrinsics.areEqual` — **5.0% of samples spent asking whether a
value equals `false`**. `JsRegExp` allocated a `Matcher` per `test` and per
`exec` (`Matcher.reset` was the largest regex leaf at 10.2%); the two now share
one, which is safe because neither lets other code run between starting a match
and reading its groups. And a regex LITERAL inside a function is a fresh object
per call, so `value.replace(/_/g, '')` was re-parsing its source every time —
every distinct `(source, flags)` now compiles once. `KirRegExpTest` uses ONE
expression many ways at once, because both changes fail the same way.

**LEVER 4: `+` asks the checker what the other arithmetic operators already
ask — MEASURED NEUTRAL and kept, explicitly not counted as a win.** A bag read
erases to `Any?` however precisely the checker typed it, so `ctx.p + 1` reached
`jsAdd` with both sides boxed; asking whether the whole SUM is a `number`
decides both coercions at once and is exact. 46.95 → 48.00 us/parse with the
ranges OVERLAPPING. Kept because it is cost-monotone, pinned, and closes the one
place where `+` disagreed with `-` about whom to ask.

**LEVER 5: Kotlin's null assertions leave the GENERATED program — a fidelity fix
that also measures favourably.** Every generated function opened with an
`Intrinsics.checkNotNullParameter` per non-null reference parameter, which is an
invariant JavaScript does not have: a JS function handed `undefined` for a
declared parameter does not throw at ENTRY. The runtime's own assertions and the
lowering's `as Double` casts are untouched. 47.75 → 47.05 us/parse and mitt
62.25 → 61.00 ns/emit, both ranges overlapping.

**GATES.** KIR module 58 → **83 tests, 0 failures** (+25 pins: 9 equality, 8
primitive-operand, 5 regex, and `KirPropertyBagTest` rebuilt from 7 to 10 and
made to cross BOTH construction routes — it had been building every fixture
with `set`, so an entire representation was untested and read as covered).
`KirPropertyBagTest`'s cases are representation-independent by construction:
both refuted bag attempts passed all of them unchanged, which is exactly what
makes it the grading harness for the next one.

**THE SUCCESSOR, NAMED WITH ITS PRICE AND ITS INSTRUMENT** — see (KIR.PERF.1)
below, which now carries the census, the ceiling (the bag is 44.3%, so a
free property access is worth ~−44% at the limit) and the one design the two
refutations do not rule out.


**(BENCH.1) ANSWERED, AND ONE OF THE TWO PERFORMANCE LEVERS IT LICENSED IS A
MEASURED REFUTATION (2026-08-21, same day).**

**THE THIRD ARM SAYS THE FRONT END IS NOT THE PROBLEM.** `-core`'s own emitted
JavaScript, on the same Node, against tsgo's: **mitt 83.75 vs 84.50 ns/emit
(1.01x), toml 22.35 vs 22.75 us/parse (1.02x)** — i.e. INDISTINGUISHABLE, which
is the prediction the queue entry recorded before the run. So the 2.5x on
`smol-toml` belongs to the KIR BACKEND in its entirety, confirming the leaf
profile by a second instrument rather than by inference, and the arm is now the
standing control: any future backend claim can be read against a JavaScript
number produced by our own front end.

**LEVER 1 LANDED — `jsCall` no longer allocates an array to make one call.**
`jsCall0`..`jsCall3` pass arguments positionally and test the arity they were
called with FIRST. Measured, medians of 5 interleaved processes with both Node
arms flat: **mitt 65.75 -> 61.50 ns/emit (-6.5%, ranges DISJOINT [261..287] ->
[242..250])**, toml 57.25 -> 55.75 us/parse (-2.6%, ranges overlap). mitt is now
**1.35x FASTER** than the same library on Node. The specialization deliberately
keeps ADAPTING the callee's arity — mitt registers a one-parameter wildcard
handler that `emit` calls with two arguments, so an implementation that trusted
the declared arity would compile and fail on the library this backend exists to
run.

**AND THE PIN EXPOSED A DEFECT THAT WAS ALREADY THERE: the chain stopped at
`Function3`, so a FOUR-parameter method of an object literal was a runtime
`JsTypeError: … is not a function`.** Arities 4 and 5 now work. Nothing in the
corpus reached it because no corpus program has a four-parameter bag member.

**LEVER 3 IS REFUTED, BY MEASUREMENT, AND IS REVERTED.** Holding a small bag in
parallel arrays with a linear identity-first scan — aimed at the 28.3% the
profile charges to `HashMap`/`LinkedHashMap` — made `smol-toml` **21% SLOWER**
(55.75 -> **67.35 us/parse**), while mitt moved only 61.50 -> 59.50. **The
mechanism is that the bag population is BIMODAL and the profile's single number
hid it**: `ParseContext` is a four-field scanner state, which the scan suits,
but the parsed document's tables are the OTHER half — the root table alone has
18 keys — and every bag that outgrows the inline capacity pays the arrays AND
the promotion AND the map. The half that dominates the samples is the half the
change taxes. Two corollaries worth carrying: a hash-family share is not
evidence about ANY particular container until the container's key-count
DISTRIBUTION is censused (round 902's law, one runtime over), and an
identity-first compare is a pure loss where keys come from DATA rather than from
emitted literals — a TOML key is never the interned string the scan hopes for.

**WHAT SURVIVES THE REFUTATION: `KirPropertyBagTest`.** Its seven pins are
REPRESENTATION-INDEPENDENT — insertion order across the promotion boundary,
`delete` closing the gap, a deleted-then-reinserted key moving to the end, an
EQUAL-but-not-identical key resolving, `has` distinguishing absent from
`undefined` — so they were written against the array form, pass unchanged
against the map form, and are what the next attempt at this will be graded by.
That is the cheap half of a refuted round and it is worth keeping.

**STILL OPEN, AND NOW THE ONLY NAMED LEVER FOR THE 2.5x:** the NOMINAL half of
`docs/kir-design.md` §3.3's hybrid — `ErasedTypes.mapObject` sends a declared
`class` to a generated JVM class and sends an `interface`, a `type X = {…}` and
every object literal to the bag. `docs/kir-structural-typing.md` §7 prices the
nominal half at 12x. Lever 3's refutation is evidence FOR that direction rather
than against it: what failed was making the dynamic representation cheaper, not
removing the dynamic representation.

**KIR RUNTIME BENCHMARK (2026-08-21) — THE COMPILED LIBRARIES, TIMED AGAINST THE
JavaScript THEY WERE WRITTEN FOR. THE ANSWER DISAGREES BY LIBRARY AND BY *SIGN*,
AND THE LEAF PROFILE SAYS WHY.**

**THE MEASUREMENT.** Same TypeScript source through two toolchains — tsgo 7.0.2 ->
JavaScript -> Node 22.20.0, against xtsc `-kir` -> Kotlin IR -> JVM bytecode -> java
(Zulu 26.0.2) — drivers ours and identical for both arms, 5 interleaved processes per
arm, best-of-10 rounds inside each process, box otherwise idle.

| workload | Node (tsgo) | JVM (xtsc/KIR) | |
|---|---|---|---|
| mitt, 4M `emit`/round | 344 ms · **86.0 ns/emit** | 266 ms · **66.5 ns/emit** | **JVM 1.29x FASTER** |
| smol-toml, 20k parses/round | 452 ms · **22.6 us/parse** | 1128 ms · **56.4 us/parse** | **JVM 2.50x SLOWER** |

One-shot wall clock including startup (10 runs, the acceptance programs as shipped):
mitt **35 -> 92 ms**, toml **39 -> 115 ms**, i.e. the JVM pays ~2.6-2.9x and it is
startup, not work. Compile side: tsgo emits either project in ~0.35 s against the KIR
backend's 4.5 s (mitt) / 5.6 s (toml) in-process, which is mostly kotlinc pipeline
setup rather than lowering.

**THE CONTROL THAT MAKES IT A MEASUREMENT.** Both arms produced IDENTICAL `sink`
accumulators (128,000,000 and -5,440,000) and byte-identical acceptance output against
the `tomllib`-derived expectation — so the two compilations compute the same thing, and
a divergence would have read as a timing result rather than as the bug it is.

**WHY THE TWO LIBRARIES SPLIT — PROFILED, NOT INFERRED** (JFR, `settings=profile`,
leaf frames). **smol-toml on the JVM, 2,159 samples: ~60% is JS-semantics emulation
rather than the library's logic** — `HashMap` get/put/resize + `LinkedHashMap.newNode`
**28.3%**, `java.util.regex` **17.5%**, `Intrinsics.areEqual`/`String.equals`
**11.1%**, `Double.valueOf` **3.4%**; the lowered library code (`program.*`) is
**17.7%**. **mitt on the JVM, 567 samples: `JsRuntimeKt.jsCall` ALONE is ~60% of
leaves**, with `TypeIntrinsics.isFunctionOfArity` behind it — so even the arm that WINS
spends most of its time in the dynamic-call shim.

**THE FOUR LEVERS THE PROFILE NAMES, and their prices, read out of the source rather
than guessed.** (i) `jsCall(callee, vararg)` allocates an `Object[]` per call and walks
an `instanceof` chain; ARITY-SPECIALIZED entry points remove both, and the adaptivity
must SURVIVE — mitt registers a `Function1` wildcard handler that `emit` calls with two
arguments, which is exactly why `lowerFunctionValueCall`'s direct `invoke` is not used
for a bag member (`KirFileLowering.kt:2714`'s comment is the record). (ii) `ErasedTypes.
mapObject` sends a declared `class` to a generated JVM class and sends an `interface`,
a `type X = {…}` and every object literal to the **property bag** — so smol-toml's
`export type ParseContext = {…}` scanner state is a `LinkedHashMap` probe per `ctx.p`,
which IS the 28.3%. The design page's own §7 prices the nominal half at **12x** the
dynamic half. (iii) The bag's keys are literals at the lowering, so INTERNING them and
comparing by identity attacks the 11.1%; a small-object linear-scan representation
attacks the hashing, and neither touches the lowering. (iv) `Double.valueOf` is
BOUNDARY boxing, not the erasure — `ErasedTypes` already maps `number` to a primitive
`double`, and the boxes are minted at bag get/set, `JsArray` elements and
`FunctionN<Any?,…>` edges.

**WHAT IS NOT A LEVER: the regex family.** `JsRegExp` compiles its `Pattern` once per
instance and the profile shows no `Pattern.compile`, so the 17.5% is genuine matching
cost — `java.util.regex` is a backtracking interpreter where V8's Irregexp emits native
code. It caps how close this backend can get on a regex-heavy library, and swapping
engines would change the semantics rather than the speed.

**KOTLIN/NATIVE WAS ASKED FOR AND IS NOT RUNNABLE — a structural answer, not a missing
tool.** `KotlinIrEmitter` drives kotlinc's **JVM** phases and `JsRuntime.kt` is
`jvmMain` with 26 `java.*` references (`java.time` for `JsDate`, `System.
currentTimeMillis`, `java.util.regex`); a native leg needs a K/N pipeline driver AND a
multiplatform runtime, which is the module's own "JVM today, JS/Native/Wasm for free
later" roadmap. The K/N toolchain itself IS on this box
(`~/.konan/kotlin-native-prebuilt-linux-x86_64-2.4.10/bin/konanc`), so what is missing
is ours. Compiling hand-written Kotlin and reporting it as a native number would have
measured Kotlin, not this compiler.

**THE HARNESS.** Node 22.20.0 under `tools/` (gitignored, downloaded — no JS runtime
was installed on this box); bench projects, drivers and the interleaved runner in the
session scratchpad; a `KirBench` java main over `compileTypeScriptProjectToJvm` that
leaves the classes on disk so the generated program runs as an ordinary `java` process
with the compiler out of the picture. **NOT COMMITTED YET** — (BENCH.1) is where it
lands if the third arm proceeds. Two protocol notes it earned: a `nohup … &` gradle run
ended with NO `BUILD SUCCESSFUL` line and had to be re-run in the foreground (the
round-851 shape, caught by grepping for the verdict rather than trusting exit status),
and the emitted-JS arms need their import specifiers checked rather than assumed —
tsgo rewrites `./parse.ts` -> `./parse.js` under `rewriteRelativeImportExtensions` but
leaves mitt's extensionless `./mitt` alone, which Node ESM refuses.

**KIR SPIKE (2026-08-21, branch `spike/ts-to-kotlin-ir`) — TWO REAL PUBLISHED
LIBRARIES COMPILE TO JVM BYTECODE AND RUN, AND SIX CHECKER DEFECTS FELL OUT OF
GETTING THERE.**

**THE RESULT.** `mitt` 3.0.1 (123 lines) compiles and runs twice — as a corpus
program, and as a real MODULE that a second file imports. `smol-toml` (1,082
lines over seven files, its own source unmodified) compiles and PARSES a 40-line
TOML document; the expectation is produced by **Python's `tomllib`**, so the test
checks the compiled library against a second, independent implementation rather
than against itself. The checker reports **zero errors** on both, which is what
tsgo 7.0.2 reports.

**THE METHOD, WHICH IS THE PART THAT TRANSFERS.** Point the compiler at code
nobody wrote for it, read the FIRST refusal — it names a file, a line, a column
and a construct — fix exactly that, repeat. Roughly forty iterations produced
the whole backend surface below, and every one of them was a real gap rather
than a guess. Two instruments made it cheap: `LibraryProbe`
(`KIR_PROBE_PROJECT` / `KIR_PROBE_FILE`, an env var because Gradle does not
forward `-D` to the test JVM) and `tsgo --noEmit -p <dir>` as the front-end
oracle.

**`docs/kir-library-readiness.md` PREDICTED THE OPPOSITE AND NOW SAYS SO.** It
concluded from `yaml` and `zod` that "the blocker is the FRONT END, not the
backend"; measured on a third library, mitt reached zero checker errors
untouched and every step between "type-checks" and "runs" was backend work. The
rule is per-library, and the page now names the two commands that answer it
before any planning.

**THE SIX CHECKER DEFECTS**, each a false positive or a silent false negative
that a corpus of ONE codebase's style could not contain, each landed with pins
and an ablation:

- an imported class's `instanceof` narrowed NOTHING — the alias has neither
  `SymbolFlags.Class` nor the value flags the constructor-value leg needs;
- an imported TYPE GUARD narrowed nothing — round 512's dir-relative resolver
  lesson, one resolver over (`computeImportedFunctionLikeDecl`);
- **a guard written `const isX = (n): n is X => …` narrowed nothing at all**,
  local or imported, because it resolves to a VariableDeclaration with no
  parameter list — the style `yaml` writes ALL of its guards in;
- `export default function f` resolved to `any`, so every misuse of a
  default-imported function went unreported;
- a returned LITERAL widened against a literal-containing union (the string
  fallback re-renders the source as its base primitive, and the arrow's concise
  body never had 17.70 at all);
- a MODULE-level `const` widened where a body-local one kept its literal; an
  object literal typed its members in a vacuum in a var-decl where the RETURN
  path has given it context since round 462; and assigning a computed primitive
  dropped a narrow that assigning a CALL kept.

Plus one in the type-of-a-binding family: a `for…of` binding was typed `any`
everywhere except inside `checkPropertyAccessInStatement`, which carries its own
B70.4 copy of the element-type rule.

`yaml` — which nobody worked on — went **80 -> 24 errors** (4 environmental) on
those alone, with TS2339-on-a-union going 21 -> 0.

**ONE CANDIDATE FALSE POSITIVE WAS FOUND AND DESIGNED OUT RATHER THAN SHIPPED.**
Installing the annotation as an object literal's contextual type UNCONDITIONALLY
— which is what the return path does — turned `program.ts:1075` red on the
compiler profile, where an object literal assigns a GENERIC function to a
non-generic member and the relation cannot yet instantiate one against the
other. The context is now installed only where the target's SHAPE asks for it (a
member that is a tuple or contains literals), `output.errors` stayed at 46, and
the relation gap is recorded rather than papered over.

**GATES: 15,492 tests / 0 failures across all modules; `cost_gate.py`
`output.errors` +0.00%, `typeOfExpr.calls` +0.18% (the `for…of` subject is now
typed at the loop's enter); `huge_methods.py --fail-over 0` green.**

**BACKEND SURFACE ADDED** (each with a corpus program that compiles to bytecode
and runs, or with the library acceptance): arrays (`T[]` -> one `JsArray`,
members found by the ERASED receiver), closures (every function type erases
UNIFORMLY to `FunctionN<Any?, …, Any?>`, because TypeScript's assignability is
bivariant and the JVM's is not), object literals and interfaces as property bags
(the DYNAMIC half of the hybrid, which §7 of the structural-typing page measured
as 12x the nominal half), `Map`/`Set`/`RegExp`/`Date`/`Error` runtime classes,
enums as inlined constants, `bigint` literals, the operator families, strings
and templates (never Kotlin's same-named members — `length` is a NUMBER and
`Double.toString()` prints `6.0`), control flow (`switch` with fall-through as a
one-iteration `do…while` plus a `matched` flag, `for…of` as an index walk,
`try`/`catch`, `throw` of any value), classes 2 (`extends` a generated OR a
runtime class, `super`, statics, accessors, `instanceof`), modules with a
dependency-ordered `moduleInit` per file, destructuring with defaults at both
levels, optional chaining, overloads, and the DYNAMIC member operations
(`jsGet`/`jsSet`/`jsInvoke`/`jsIndexGet`/`jsIndexSet`) for an `any` receiver.

**TWO HARNESS DEFECTS THE LIBRARIES EXPOSED.** The corpus runner's
`waitFor(2, MINUTES)` sat one line BELOW a `readText()` of the child's stdout,
which blocks until the child exits — so a generated program that looped without
printing hung the whole suite at 100% CPU with the deadline unreached (it now
redirects to files). And a `continue` inside a `for…of` skipped the increment,
which is the same trampoline `for(;;)` already had.

**Round 948 (2026-08-19) — (CHK.25): `using` / `await using` DECLARATIONS DID NOT PARSE AT
ALL, AND THAT WAS THE LARGEST SINGLE CASCADE IN THE WHOLE PRISTINE POPULATION. LANDED
PARSE + BIND + THE GRAMMAR RULES + THE DISPOSABILITY RULE + A VERBATIM EMIT — OURS-ONLY
**282 -> 251** OVER 74 -> 71 FIXTURES, PRISTINE-ONLY **769 -> 767** (TWO TRUE POSITIVES
GAINED), ZERO FIXTURES REGRESSED, ZERO OF ~13k CORPUS BASELINES MOVED.**

**WHAT tsc's GRAMMAR AND RULES ACTUALLY ARE.** `parseStatement` / `parseDeclarationWorker` /
`parseForOrForInOrForOfStatement` each route a `using` (and an `await` whose next token is
`using`) into `parseVariableStatement` **only behind a LOOKAHEAD** — `isUsingDeclaration` /
`isAwaitUsingDeclaration`, both of which reduce to
`nextTokenIsBindingIdentifierOrStartOfDestructuringOnSameLine`: a binding identifier or a `{`
binding pattern, **on the same line**. An `[` is deliberately NOT a start, with tsc's own
comment saying why (`using[x]` is an element access), which is what makes an ARRAY binding
pattern unreachable from a statement-level `using` head. The for-head uses the `disallowOf`
variant, so `for (using of xs)` iterates the VALUE `using` unless the token after `of` is
`=` / `;` / `:`. `parseVariableDeclarationList` then folds the head into `NodeFlags`
(`Using` / `AwaitUsing`, joining `Let` / `Const` in `NodeFlags.BlockScoped`). The rules are
`checkGrammarVariableDeclaration` (TS1492 binding patterns, TS1155 must-be-initialized, both
squiggling the declarator's NAME via `getErrorSpanForNode`),
`checkGrammarVariableDeclarationList` (TS1493 / TS1494 for-in, TS1547 / TS1548 clause,
TS1545 / TS1546 ambient), `checkGrammarModifiers` (TS1491 / TS1495) and
`checkVariableLikeDeclaration`'s assignability of the initializer to
`Disposable | null | undefined` resp. `AsyncDisposable | Disposable | null | undefined`
(TS2850 / TS2851).

**WHAT LANDED, AND THE REPRESENTATION NEEDED NO NEW NODE.** A `VariableDeclarationList`'s
`flags` field in this compiler already IS the head TOKEN, so `using` is
`SyntaxKind.UsingKeyword` — no `forEachChild` arm, no `NodeKind`, no binder arm, because
`Binder.bindVariableStatement`'s `isVar` test already reads any non-`var` head as
`SymbolFlags.BlockScopedVariable`. That is not an assumption: a `using` declared inside a
block is invisible after it (TS2304) where a `var` in the same position hoists, which is the
pin. `await using` is two tokens collapsed onto a synthetic `SyntaxKind.AwaitUsingKeyword`
the scanner never produces (tsc's `NodeFlags.AwaitUsing`). **The ~98 sites that read
`declarationList.flags` all test it as `== ConstKeyword` / `!= VarKeyword`, so a new head
value is a false NEGATIVE at every one of them and never a false positive** — which is what
made a statement form this size an additive change rather than a sweep.

**THE CONTEXTUAL-KEYWORD RISK DID NOT MATERIALISE ANYWHERE, AND THE EVIDENCE IS ON BOTH
SIDES.** The eight profiles carry **336** occurrences of `using` as an identifier / property
name (`scanner.ts`'s own `using: SyntaxKind.UsingKeyword` among them) and **zero** `using`
declarations, and the 8-profile BEFORE/AFTER **binary** grid is `added=0 removed=0` on all
eight; the pins carry the shapes the profiles do not (`const using = 1`, `using.foo()`,
`using[i]`, `{ using: 1 }`, a `using` parameter, a `using` class member, and ASI).

**A PIN THAT MEASURED NOTHING, CAUGHT BY WRITING THE ABLATION ARM FIRST.** The
`disallowOf` pin was originally `for (const of of using)` — a `const` head, which does not
reach the `disallowOf` path at all. The shape that does is
`let using = 0; for (using of xs)`, and arm A5 reddens exactly it and nothing else. The old
shape survives as a regression guard (`of` is still usable as a declarator name).

**WHAT IS QUEUED RATHER THAN LANDED.** **(CHK.27)** — the downlevel EMIT (tsc's
`__addDisposableResource` / `__disposeResources`; the head is emitted VERBATIM, which is
tsc's own output at >= ESNext and the safe half of the choice, since rewriting it to `var`
would silently delete the disposal), `declare using` (TS1545), the case/default-clause rule
(TS1547 / TS1548), and the `await using` CONTEXT rules (TS2852 / TS2853 / TS2854, TS18054),
plus TS2850's nested elaboration and its TS2728 related info. **(CHK.28)** — the two rows
still on `usingDeclarationsNamedEvaluationDecoratorsAndClassFields` are **not about `using`**:
`const C = @dec class { }` takes TS1206 `Decorators are not valid here.` just as
`using C = @dec class { }` does, i.e. a decorated class EXPRESSION in an initializer, which
the `using` parse cascade had been masking.

**GATES.** Suite **15,343 -> 15,381 / 0 failures / 3 skipped** (+38 = exactly this round's
pins), **NO corpus baseline moved**; 8-profile BEFORE/AFTER **binary** grid (`scripts/round948-grid.sh`,
two snapshotted class dirs whose `Parser.class` / `Checker.class` are asserted DIFFERENT)
`added=0 removed=0` on ALL EIGHT; **EMIT-mode `diff -r` clean** over 78 + 252 emitted files on
two profiles, both arms (the `--noEmit` blind spot); the 630-fixture pristine sweep, both arms
against the two class-dir snapshots, output files DELETED before the wait: **282 -> 251
ours-only over 74 -> 71 fixtures, 769 -> 767 pristine-only, ZERO fixtures regressed**;
`cost_gate.py` **+0.00% on all 20 counters** (the expected answer — `using` is absent from the
compiler profile — with the 38 pins as the positive control that the binary changed);
`huge_methods.py --fail-over 0` green on all six module class dirs (largest core method 5,204);
`round920-token-gate.sh` **ALL INVARIANTS HOLD, 1,327 files / 101 M chars / 0 violations**;
`spine_closure_audit.py` green (46 handlers, 6 open, 40 audited). The after-arm sources rebuild
to the measured `Parser.class` / `Checker.class` / `Emitter.class` byte for byte.

**ABLATION — 13 arms, one mistake at a time, applied to and restored from a sha256-verified
snapshot (never `git checkout`), each asserting `ran 38` ACROSS TWO MODULES.**

| arm | the injected mistake | RED | uniquely |
|---|---|---:|---|
| A1 | delete the statement dispatch arms (the pre-948 grammar) | **13** | the two AST-SHAPE pins and the `await using` bind |
| A2 | the BOUND — no lookahead, every statement-position `using` is a head | **4** | `using[i];` as an element access, `using.foo()` |
| A3 | the BOUND — drop the SAME-LINE test (ASI) | **2** | — (a proper subset of A2 by construction) |
| A4 | delete the for-head arms | **5** | both `for (using … of …)` positives |
| A5 | the BOUND — `disallowOf = false` in the for head | **1** | `for (using of xs)` iterates the VALUE |
| A6 | collapse `AwaitUsingKeyword` onto `UsingKeyword` | **5** | — (detected jointly by five pins) |
| A7 | the emitter writes `var` for a `using` head | **1** | — (also A1, A6) |
| A8 | delete TS1492 | **2** | — |
| A9 | delete TS1155's owner gate | **3** | — |
| A10 | delete TS1493 / TS1494 | **2** | — |
| A11 | delete TS1491 / TS1495 | **2** | the `export using` case |
| A12 | delete TS2850 / TS2851 | **3** | — |
| A13 | the BOUND — no `Disposable`-in-lib guard on the disposability rule | **2** | — |

Union **25 of 38**; the other **13 are regression guards / negative controls** and are
recorded as such (round 807), including every "`using` is still an ordinary identifier" pin
whose shape is not statement-INITIAL — `const using = 1; … using + 1` lives inside an
initializer, so A2 cannot reach it, while its `-project` twin (`using[i];` at statement start)
is red under A2. That contrast is round 806's pin-POSITION law in one pair.

**TWO ARMS NEEDED REPAIR BEFORE THEY MEASURED ANYTHING, BOTH THE HARNESS'S FAULT.** A9's first
anchor occurred TWICE in `Checker.kt` (`spineCheckConstInitializer` has the same two-line
initializer guard) and was REFUSED by the driver's own uniqueness check; its second form
deleted a `val` still referenced below, which is a COMPILE ERROR and prints as `ran 0` —
indistinguishable from a dead build. The arm that works removes the OWNER gate, which is one
mistake and leaves the function compiling.

**Round 947 (2026-08-19) — THE PARSER-GAP BUCKET SUB-TRIAGED: ROUND 941's LABEL
("`using`, `infer X extends`") IS WRONG IN BOTH HALVES. THE BUCKET IS **SIX** FAMILIES;
`infer X extends` ALREADY PARSES; THE "PARENTHESIZED `infer`" DEFECT IS NOT THE PARSER AT
ALL. LANDED THE TWO SMALL ONES — OURS-ONLY **297 -> 282** OVER 75 -> 74 FIXTURES,
PRISTINE-ONLY FLAT AT 769, ZERO FIXTURES REGRESSED, ZERO CORPUS BASELINES MOVED.**

**THE SUB-TRIAGE FIRST, BECAUSE IT IS THE ROUND'S MAIN PRODUCT.** The bucket is 56 rows at
HEAD (round 941 read 59; round 945 had already closed the three `esDecorators-*` `accessor`
rows as part of its downlevel-target family without noticing they were counted here):

| # | family | rows | verdict |
|---|---|---:|---|
| P1 | `using` / `await using` declarations | **33** | a genuine unimplemented FEATURE — SCOPED OUT, **(CHK.25)** |
| P2 | `infer U extends T` vs the conditional's own `?` | **8** | a genuine gap needing a parse CONTEXT — SCOPED OUT, **(CHK.26)** |
| P3 | `abstract new (…) => T` | **5** | one grammar production — **LANDED** (+6 rows outside the bucket) |
| P4 | an `infer` in a CONSTRUCTOR type's RETURN | **2** | a CHECKER walker's missing arm — **LANDED** (+2 outside) |
| P5/P6 | `privateIndexer2`, `topLevelAwaitErrors.1`, `inferTypes1` 83/85 | **8** | deliberately ILLEGAL inputs — **RE-BUCKETED** to the parser-RECOVERY bucket |

**`infer X extends` PARSES AND ALWAYS HAS.** `T extends [infer U extends string] ? U :
never`, `T extends { a: infer U extends number } ? U : never` and the bare
`T extends infer U extends string ? U : never` are all silent today, because the `infer`
production hands `parseTypeParameter` the whole type parameter and that function has always
read a constraint. What `inferTypesWithExtends1` actually reports is tsc's DISAMBIGUATION
rule (`tryParseConstraintOfInferType` + `disallowConditionalTypesAnd`), which is a parse
CONTEXT threaded through `parseType`'s conditional production — i.e. an edit to the exact
production CLAUDE.md's frozen-subsystem warning is about, not a one-arm addition. Scoped out
with its mechanism written down.

**`using` IS SCOPED OUT ON SIZE, NOT DIFFICULTY, AND THE DECIDING FACT IS AN INSTRUMENT ONE.**
It is a statement form plus binding plus the block-scoped rules plus the disposability check
plus EMIT — and **439 `usingDeclarations*` baselines exist upstream, most with
`(module=…,target=…)` variations**, while this sparse clone carries essentially NO `using`
case file, so **the generated corpus gates none of it**. A landing must bring its own emit
gate; that is a round, not a rider.

**WHAT LANDED (1), THE PARSER HALF.** `parsePrimaryType` opens with tsc's own test —
`isStartOfFunctionTypeOrConstructorType` admits an `abstract` in type position ONLY when the
very next token is `new`, and `parseModifiersForConstructorType` then consumes that one
modifier before the ordinary constructor-type production runs. `ConstructorType` already
carried the `modifiers` field. **The LOOKAHEAD is the whole of what makes the arm additive**
— `abstract` is an ordinary identifier in type position (`type Named = abstract`), and
dropping the lookahead reddens exactly that pin and nothing else.

**WHAT LANDED (2), AND THE QUEUE ENTRY WAS BACKWARDS ABOUT IT.** (CHK.14) recorded the
second defect as *"an `infer` inside a PARENTHESIZED extends clause does not publish its
name"*. **Parentheses are irrelevant** — `collectInferTypeNames` recurses through
`ParenthesizedType` and always has — and it is **not the parser**: the walker had no
`ConstructorType` arm, so the UNPARENTHESIZED `T extends new () => infer U ? U : never`
fails identically while the parenthesized FUNCTION-type spelling has always worked. Its
sibling `collectInferDecls` carries the arm already, **with a comment saying it is keeping
parity with this walker**. The parity only ever went one way. That one arm also closed
`declarationEmitShadowingInferNotRenamed` (2 rows), which had been filed under
"computed keys / declaration emit".

**~40 MINUTES LOST TO A GRADLE BUILD I COULD NOT TELL FROM A SKIPPED ONE, AND THE FIX IS A
POSITIVE CONTROL.** The `ConstructorType` arm read INERT through three rounds of probing —
the fix was in the source, `javap`-visible line numbers matched, and the compiled behaviour
was the OLD one. `./gradlew … -q 2>&1 | grep -E '^(e:|w:)'` prints nothing for a successful
build, a skipped build AND a build whose output has not landed yet, so "BUILD_DONE" said
nothing. What settled it in one run was making the walker register a MARKER name derived
from the node class (`scope.addTypeParam("ZZ" + type::class.simpleName)`) and probing for
that name: a marker that must resolve is a positive control on the whole path, source to
class file to behaviour. (`strings` on the class file is NOT that control — its default
minimum length is 4, so a 2-character literal reads as absent.)

**ONE PIN FIRST READ GREEN UNDER ITS OWN ARM AND IT WAS THE PIN'S DEFECT** (round 902's law,
paying for itself again): `a standalone abstract construct signature type parses` used
`=> K` for a declared class, and the pre-947 misparse of THAT spelling — `abstract` as a type
name followed by a `new` expression over a parenthesized arrow — happens to be silent.
`=> number` cascades (TS2693), and with it the pin reddens under A1.

**ABLATION — four arms, one mistake at a time, applied to and restored from a sha256-verified
snapshot (never `git checkout`), each asserting `ran 19` ACROSS TWO MODULES.**

| arm | the injected mistake | RED | what it establishes |
|---|---|---:|---|
| A1 | delete the `abstract new` production entirely | **7** | every abstract-form positive, plus the span pin |
| A2 | the BOUND — fire on every `abstract` in type position, no `new` lookahead | **1** | uniquely: `abstract` alone in type position is still an ordinary type name |
| A3 | delete the `ConstructorType` arm from `collectInferTypeNames` | **6** | every `infer`-through-a-constructor positive, both pristine shapes |
| A4 | the BOUND — start the node at the `new` instead of the `abstract` | **1** | uniquely against A2/A3 — and RED **0** in core |

**A4 IS THE ENTRY WORTH KEEPING: A SPAN BOUND CAN BE UNOBSERVABLE FROM THE CORE AND
MEASURABLE ONE MODULE OVER.** No diagnostic in this compiler is positioned from a
`ConstructorType`'s `pos` — TS1386 (the union-parenthesization rule, which is what proves the
abstract form produces a real `ConstructorType` node rather than merely parsing) spans from
the union MEMBER start to `prevTokenEnd`. So the four-arm core ablation read the dropped span
as RED 0, which round 946's law says to treat as "the mistake was not reached" until proven
otherwise. It WAS reached; the core simply cannot see a node span. `-project` can — that is
round 910's whole subject — and three pins there make the bound measurable. **8 of the 19
pins are green in all four arms and are recorded as regression guards or controls** (round
807), including `the constructor type node starts at the abstract keyword`, which arm A4
leaves satisfied by the enclosing alias's own start.

**GATES.** Suite **15,324 -> 15,343 / 0 failures / 3 skipped** (+19 = exactly this round's
pins), **NO corpus baseline moved**; 8-profile BEFORE/AFTER **BINARY** grid `added=0
removed=0` on ALL EIGHT (`scripts/round947-grid.sh` — two snapshotted class dirs, since a
grammar production cannot be switched off at run time, so it REFUSES when the two arms'
`Parser.class` and `Checker.class` are byte-identical); the 630-fixture pristine sweep
**297 -> 282 ours-only / 769 -> 769 pristine-only, zero fixtures regressed** (both arms run
against the two class-dir snapshots, output files DELETED before the wait — round 946's
stale-file trap); `cost_gate.py` **+0.00% on all 20 counters**; `huge_methods.py
--fail-over 0` green on all six module class dirs (largest core method 5,187);
`round920-token-gate.sh` **ALL INVARIANTS HOLD, 1,327 files / 101 M chars / 0 violations**;
`spine_closure_audit.py` green. The after-arm sources rebuild to the measured
`Parser.class` / `Checker.class` byte for byte.

**NEXT.** **(CHK.25)** `using` declarations (33 rows) is the largest single item left in the
whole ours-only population and the one that needs its own round with an emit gate — **DONE,
round 948**; **(CHK.26)** the `infer`-constraint disambiguation (8 rows) is smaller but touches
the frozen conditional-type production. Otherwise the ranked list in
`docs/pristine-divergences.md` § 4 is unchanged: **(CHK.10)** definite assignment through a
late-bound `this[k]` (4 rows, confirmed genuine), then **(CHK.18)** and **(CHK.15)**.

**Round 946 (2026-08-19) — (CHK.22): THE ITERABILITY CHECK. tsc REJECTS A `for...of` OR SPREAD
OPERAND WHOSE `[Symbol.iterator]` IS OPTIONAL OR WHOSE RETURN TYPE HAS NO `next()`; THIS
COMPILER PERFORMED NO SUCH CHECK AT ALL, AT ANY TARGET. LANDED POSITIVE-EVIDENCE-ONLY:
PRISTINE-ONLY 773 -> 769 WITH OURS-ONLY FLAT AT 297 AND **ZERO** OF ~13k CORPUS BASELINES
MOVED — the first entry in this arc that moves only the FALSE-NEGATIVE column.**

**tsc's RULE, ESTABLISHED FROM ITS OWN `checker.ts` BEFORE ANYTHING WAS WRITTEN**
(`getIteratedTypeOrElementType` -> `getIterationTypesOfIterable` ->
`getIterationTypesOfIterableWorker` -> `getIterationTypesOfIterableSlow` ->
`getIterationTypesOfIteratorWorker` -> `getIterationTypesOfMethod`):

| # | step | tsc's decision | this round |
|---|---|---|---|
| 0 | `uplevelIteration = languageVersion >= ES2015 && getGlobalIterableType() !== emptyGenericType` | the errorNode is passed ONLY then — below it the array-like leg owns the position (TS2495 / TS2461 / TS2569) | gate = `defaultedTarget >= ES2015 && !noLib && !<es5-only @lib>`; **`downlevelIteration` deliberately NOT admitted** — it makes tsc CONSULT the protocol but still passes `undefined` as the errorNode |
| 1 | `getPropertyOfType(type, "__@iterator")` returns nothing | TS2488 | **SCOPED OUT — (CHK.23)** |
| 2 | `method && !(method.flags & SymbolFlags.Optional)` | an OPTIONAL `[Symbol.iterator]?()` supplies no method type, so no signatures, so TS2488 | **LANDED** (`for-of29`) |
| 3 | `filter(sigs, getMinArgumentCount(sig) === 0)` empty | TS2488 + a TS2322 related chain | left to **B438e**, which already owns the object-literal spelling |
| 4 | `getIterationTypesOfMethod(returnType, "next", …)` finds nothing | pushes the related **TS2489 `An iterator must have a 'next()' method.`**, root TS2488 | **LANDED** for a MISSING `next` (`for-of16` x2, `iteratorSpreadInArray10`); the OPTIONAL-`next` half refused |

**WHAT WAS SCOPED IN: `for...of` AND ARRAY-LITERAL SPREAD. WHAT WAS SCOPED OUT: EVERYTHING
ELSE, WITH tsc's ANSWER KNOWN FOR EVERY ROW** — the missing-member case (the big one), an
optional `next`, an empty iterator member table, a string index signature, an
argument-requiring `[Symbol.iterator]` on a CLASS, and the four other constructs (CALL-argument
spread, array destructuring, `yield*`, `for await…of`, which carry different `IterationUse`
flags and different diagnostic families). Queued as **(CHK.23)** with the table; **every one
is pinned SILENT** so a later widening has to move a named pin.

**THE DESIGN DECISION THE ROUND TURNS ON, AND IT IS NOT tsc's.** tsc's rule also rejects a
type with NO `[Symbol.iterator]`; reproducing that needs a COMPLETE model of what is iterable
(arrays, strings, tuples, `Iterable<T>`, a constrained type parameter, every union of them,
the built-in iterator families) and **one gap in such a model is a false positive on the most
common construct in the language**. So the landed check is POSITIVE-EVIDENCE-ONLY: it fires
only where the member is FOUND and provably broken and BAILS on every question it cannot
answer — a non-object type, a union, an intersection, a type parameter, `any`/`unknown`/
`error`, an unresolvable member type, no call signature, more than one zero-argument
signature, an EMPTY return-type member table, a string index signature. Every bail is a false
NEGATIVE and no bail is a false positive. That asymmetry, and nothing else, is why a new
diagnostic on `for...of` moved zero corpus baselines.

**TWO MEASUREMENTS THAT DECIDED THE SHAPE.**

1. **`this` READS AS `any` HERE.** `class C { m() { return this } n(): this { return this } }`
   makes `c.m()` and `c.n()` both silent against a `string` target — this compiler has no
   polymorphic `this` type — so `[Symbol.iterator]() { return this }`, which is THREE of the
   four rows, would have bailed at step 4. `iteratorMethodThisReturn` answers the CARRIER when
   the member's declaration provably returns `this` (a `ThisType` return annotation, or a body
   that is exactly `return this;`), which is tsc's own answer — it instantiates `this` to the
   receiver — rather than a widening. It is a stopgap and its KDoc says so; the general item is
   queued as **(CHK.24)**.
2. **THE EMBEDDED LIB DECLARES `[Symbol.iterator]` IN EXACTLY ONE PLACE**, `interface
   IterableIterator<T> extends Iterator<T>`, and `interface ArrayIterator<T> { }` /
   `interface Iterable<T> { }` are EMPTY there. So under the lib the corpus runs on, an empty
   member table is a real and common shape and must read as "not resolved", never as "has no
   `next`" — treating it as evidence would have lit up every array in the corpus. It is also
   why the reachable corpus population is only *user* types that declare the member.

**PROVENANCE.** After-arm grid, sweep and all 11 ablation arms at `Checker.kt` sha256
`e45719de…`; the before arm is HEAD (`50c08ef1`) built from a reverted working copy, and the
grid REFUSES if the two arms' `Checker.class` are byte-identical.

**AN INSTRUMENT TRAP THAT COST ~40 MINUTES AND IS WORTH THE ENTRY.** The first "before" sweep
read **334 ours-only / 776 pristine-only** — round *942*'s numbers, i.e. a binary three rounds
stale — and spot-checking single fixtures on the SAME class dir answered 297/773 correctly.
The class dir was fine: the scratchpad is shared between sessions, an eight-hour-old
`sweep-before.json` from another session was already there, and the `until [[ -f <out> ]]`
wait-loop matched it INSTANTLY while the real sweep was still running. **A wait loop keyed on a
file's EXISTENCE proves nothing unless the file is deleted first** — and the failure is in the
reassuring direction, because a stale sweep looks exactly like a fresh one.

**ABLATION — 11 arms, one mistake at a time, applied to and restored from a sha256-verified
snapshot (never `git checkout`), each asserting `ran 63`.**

| arm | the injected mistake | RED | what it establishes |
|---|---|---:|---|
| A1 | the run gate never opens — the whole fix | **6** | every positive; the union of A2 and A3 plus nothing |
| A2 | the OPTIONAL-member test (tsc's `!(flags & Optional)`) | **2** | the two `for-of29` pins, DISJOINT from A3 |
| A3 | the `this`-return route | **4** | `for-of16` x2 + the spread + the `(): this` annotation |
| A4 | the empty-member-table bail | **1** | the opaque member-less iterator stays silent |
| A5 | the string-index bail | **1** | (first cut read RED 0 — A4's guard absorbed it; the fixture now carries a NAMED member too, which is what makes the two guards separable) |
| A6 | the zero-argument signature filter | **1** | the argument-requiring CLASS shape; `Inv4SpineBatch2Test` stays green, so B438e is not double-emitted either way |
| A7 | the BOUND — adopt tsc's FULL optional-`next` rule | **1** | the refusal is a DECISION, not an omission (first cut of that guard was DEAD CODE: `if (opt) return null` above `return null`; found by its arm reading RED 0) |
| A8 | the LIB half of the run gate | **2** | `@lib: es5` and `noLib` both leave the position to the array-like leg |
| A9 | the ARRAY-LITERAL parent gate on the spread | **1** | a CALL-argument spread is not this construct |
| A10 | the `for await` gate | **1** | TS2504 owns the async protocol |
| A11 | the `.d.ts` skip | **1** | (first cut read RED 0 — the fixture had no iteration position at all; the pin now carries a `for...of`, which our parser accepts in an ambient file) |

**Three of the eleven arms first read RED 0 and all three were the ROUND's defect rather than
the pin's**: one guard was literally dead code, one pin was absorbed by a neighbouring guard,
one pin had no population. Round 902's law paying for itself three times in one batch — a
green arm must be read as "the mistake was not reached" until proven otherwise. **15 of the 30
pins are green in all 11 arms and are recorded as REGRESSION GUARDS rather than claimed as
discriminators** (round 807): the eleven built-in-iterable negatives, the "declares no
`[Symbol.iterator]`" false-negative pin, the B438e object-literal count pin, and the
destructuring and `yield*` construct pins.

**GATES.** Suite **15,294 -> 15,324 / 0 failures / 3 skipped** (+30 = exactly this round's
pins), **NO corpus baseline moved**; 8-profile before/after binary grid **`added=0 removed=0`
on ALL EIGHT** (enumerated by `tsconfig.json`, refusing below 8, refusing a truncated or empty
capture, and refusing byte-identical arms); the 630-fixture pristine sweep, both arms in this
session, **ours-only 297 -> 297 over 75 fixtures with ZERO regressed and pristine-only 773 ->
769**; `cost_gate.py` `typeOfExpr.calls +0.22%` / `typeOfExpr.distinct +0.09%` /
`globals.lookups +0.14%` / `typeNode.*` +0.02-0.04% — the per-operand type read the check
performs, i.e. a reached-ness proof, rebaselined in the same commit; `huge_methods.py
--fail-over 0` green on all six module class dirs (core 0 over, 751 classes scanned);
`spine_closure_audit.py` green (no handler gate changed — both dispatch sites are in
`spineEnterKindDispatch`, which runs unconditionally after round 888's mask).

**ONE FORM DIVERGENCE, RECORDED AND NOT MECHANISED.** `for-of29`'s message names the type as
`{ [Symbol.iterator](): Iterator<string>; }` where pristine writes
`{ [Symbol.iterator]?(): Iterator<string, any, any>; }` — our `typeToString` drops a member's
`?` and does not fill a generic's defaulted type arguments. The sweep differences
`(file, line, code)` so the row is closed; no corpus baseline carries the shape, so no
`logicalParityDivergences` entry is needed. Both gaps predate this round.

**NEXT.** (CHK.10), definite assignment through a late-bound `this[k] = …` — 4 rows, confirmed
genuine by round 943's strict-default arm. Then (CHK.18) and (CHK.15). The two entries this
round opened are (CHK.23) — the missing half of the iterability check, whose big sub-item needs
a model of what is iterable — and (CHK.24), the polymorphic `this` type.

**Round 945 (2026-08-19) — (CHK.21): THE DOWNLEVEL GATES' FILED EVIDENCE WAS MISATTRIBUTED AND
THE FAMILY'S SIGN IS THE OPPOSITE OF WHAT THE QUEUE SAID. The four pristine-only TS2488 rows are
NOT gate suppression — we are silent for those shapes at `esnext` too — and what the gates really
cost is SIX FALSE POSITIVES on any tsconfig that names no `target`. Landed by extending round
944's notion (renamed `libTarget` -> `defaultedTarget`) to the 23 downlevel-gate lines; sweep
313 -> 310, pristine-only FLAT, 8-profile grid clean, suite 15,272/0 with NO corpus baseline
moved.**

**THE FIRST ACT WAS TO RE-VERIFY THE ENTRY'S OWN EVIDENCE, AND IT DID NOT SURVIVE.** (CHK.21)
was queued as a FALSE-NEGATIVE item because `for-of16` (x2), `for-of29` and
`iteratorSpreadInArray10` carry a pristine TS2488 we do not emit, and the
`if (target < ES2015 && !downlevelIteration) return` gates were assumed to be suppressing it.
Run at an EXPLICIT target, where those gates are wide open:

| shape | ours @es5 | @unset | @es2015 | @esnext | pristine |
|---|---|---|---|---|---|
| `for (v of new MyStringIterator)` — `[Symbol.iterator]()` returns a non-Iterator `this` | silent | silent | **silent** | **silent** | TS2488 x2 (+ related TS2489) |
| `for (var v of iterableWithOptionalIterator)` — an OPTIONAL `[Symbol.iterator]?()` | silent | silent | **silent** | **silent** | TS2488 |
| `[...new SymbolIterator]` | silent | silent | **silent** | **silent** | TS2488 |

A gate that is open and still silent is not a gate: those rows are an **unimplemented iterability
check** (tsc's `getIterationTypesOfIterable` -> `getIterationTypesOfIteratorWorker`), re-filed as
**(CHK.22)**.

**WHAT THE GATES ACTUALLY COST, MEASURED ON ONE 14-LINE FILE.** `options.target` defaults to
`ES3`, indistinguishable from "the user named no target" — round 944 closed that ambiguity for
the LIB half, this is the other half. Before / after at an unset target, with the explicit `es5`
and `es2017` columns BYTE-IDENTICAL either way:

| code | shape | before | after | pristine at its default |
|---|---|---|---|---|
| TS1250 | `{ function f() {} }` in strict mode | fires | silent | silent |
| TS1501 | `/a/y` | fires | silent | silent |
| TS1503 | `/(?<nm>a)/` | fires | silent | silent |
| TS2659 | `super.` in an object-literal method | fires | silent | silent |
| TS2737 | `1n` | fires | silent | silent |
| TS18045 | `accessor p = 1` | fires | silent | silent |

**THE ORACLE FOR THAT LAST COLUMN IS THE BASELINE CORPUS, NOT AN OPINION**: every TS1250 (7
baselines), TS1501 (24), TS1503 (4), TS2396 (8), TS2659 (2), TS2737 (4), TS18045 (5) and TS2802
(10) comes from a fixture with an EXPLICIT `@target`. Pristine never emits a downlevel-gated
diagnostic at its default — which is what its own source says it must do
(`utilities.ts` `_computedOptions.target`: `target === ES3 ? undefined : target ?? LatestStandard`,
read directly, and `checker.ts:1529` `var languageVersion = getEmitScriptTarget(compilerOptions)`
is the ONE notion its whole checker compares against ES2015).

**THE FIX, AND THE THREE SITES DELIBERATELY LEFT RAW.** `libTarget` -> **`defaultedTarget`** (it
no longer named its only consumer), now read by 23 downlevel-gate lines as well as the four lib
ones. NOT `effectiveTarget`, for the MIRROR of round 944's reason: it maps an explicit `es5` UP to
ES2015 and would OPEN every gate tsc keeps shut for that program. Kept raw, with the reasons in
the accessor's KDoc: `spineDelIsStrict` and `spineStrictFileIsExprStrict`, whose shape is
`target >= ES2015 || <other disjuncts>` — a mis-transcription of tsc's NESTED rule that is correct
only while the raw target reads ES3, and which a flip would make unconditionally strict for every
file — and `checkOperationsAvailableOnPromisedType`, a per-fixture baseline pin rather than a
semantic gate.

**WHY NO INSTRUMENT SAW IT, AND A CAVEAT FOR THE NEXT SWEEP READER.** **6,436 of the 6,573 case
files in this clone name an explicit `@target`** (TypeScript pinned them when it dropped ES5); of
the 213 that do not, a purpose-built before/after sweep moved **zero rows in either direction**.
And the 630-fixture sweep's "304 fixtures with no `@target`" is largely an ARTEFACT — those
fixtures' case files are absent from the sparse clone, so the sweep compiles them at an unset
target it invented. It did still move: **313 -> 310 ours-only over 78 -> 76 fixtures, 0 added,
pristine-only FLAT at 775, zero fixtures regressed**; the three removed rows are TS18045 on
`esDecorators-classDeclaration-fields-{nonStatic,static}Accessor`, i.e. round 944's triage had
them in a different bucket. The before arm reproduced round 944's closing 313 exactly — the
provenance check.

**21 PINS ENCODED THE FALSE POSITIVE AND ARE THE ROUND'S SECOND PRODUCT.** The M0.4
spine-migration classes pinned TS2396 / TS2659 / TS2373+TS2454 at the DEFAULT target (one was
literally named "at default target"), i.e. they relied on the ES3 zero value to open a gate for
them. Re-pointed at `CompilerTestSupport.DOWNLEVEL_ES5`, which restores the exact population each
was written to measure — **and A2 below shows they are genuine discriminators, not a repair of
convenience**.

**ABLATION — 6 arms, one mistake at a time, from a sha256-VERIFIED snapshot**
(`scripts/round945-ablate.py`, each arm asserting `ran 104`, each diffed against the SNAPSHOT and
never with `git checkout`). The arms may NOT touch `defaultedTarget` itself — that accessor is
also round 944's lib fix — so every arm edits NAMED CALL SITES and `LibAvailabilityDefaultTargetTest`
rides along in the filter to prove the two halves stay separate:

| arm | the injected mistake | red | what it proves |
|---|---|---:|---|
| A1 | all 23 lines read the RAW target — the whole fix | **3** | the three unset-target pins; the 14 lib pins and all 21 re-pointed pins stay GREEN, i.e. the halves are separable |
| A2 | all 23 read `effectiveTarget` — the BOUND | **25** | the four explicit-es5 pins PLUS all 21 re-pointed ones, a set DISJOINT from A1's; this is what makes the re-pointed pins discriminators |
| A3 | the TS2737 bigint pass gate alone | **2** | the bigint pin uniquely, plus the combined one |
| A4 | `spineAccessorModifierActive` alone | **2** | the accessor pin uniquely, plus the combined one — disjoint from A3 but for the shared combined pin |
| A5 | the TS1250 inner guard alone | **0** | **a REDUNDANT GUARD, recorded as such** (round 807): with the pass-slot gate keeping the fix the walker is never scheduled, so its own guard is unreachable |
| A6 | the TS1250 pass gate AND its inner guard | **1** | the combined pin alone — TS1250/TS1501/TS1503/TS2659 have NO dedicated pin and are covered by that one test |

**A TRAP THIS ROUND WALKED INTO AND ONE IT RE-LEARNED.** The first ablation attempt refused on a
STALE LINE NUMBER (the KDoc above a gate had grown four lines when its "uses the RAW target
deliberately" comment was rewritten) — the script now DERIVES its sites from the snapshot. And
killing that run with `pkill -f "round945-ablate"` matched the invoking shell, died mid-arm and
left arm A3's ablated `Checker.kt` in the tree with no marker: round 805's trap, caught only
because the round hashes its own sources.

**GATES.** Suite **15,272 / 0 failures / 3 skipped** summed over all six modules with an XML
parser (15,262 -> +10, exactly this round's new pins), NO corpus baseline moved. **8-profile grid,
both arms from hash-verified class dirs, profiles enumerated by `tsconfig.json` and refused below
8: `added=0 removed=0` on ALL EIGHT** — a control by construction, since every profile sets
`target: es2020`. `cost_gate.py`: every counter **+0.00%**, the EXPECTED answer (round 876) for
the same reason. `huge_methods.py --fail-over 0` green on all six module class dirs (751 / 48 / 20
/ 14 / 7 / 2 classes scanned — the differing counts are the per-dir positive control). No
`spine*EnterNode` body changed, so `spine_closure_audit.py` does not apply.

**PROVENANCE.** After-arm grid, sweep and all six ablation arms at `Checker.kt`
`80c3e681...`, `CompilerOptions.kt` `f62d685e...`, `RealLibs.kt` `93a71612...`; the before arm at
HEAD's `7b78f8e8...` / `b9f80781...` / `1b951943...`.

**SECOND ACT — (CHK.9), THE INDEX-SIGNATURE PARAMETER RULE.** tsc's
`checkGrammarIndexSignatureParameters` resolves the parameter's type node and asks two
questions in order: `someType(t, StringOrNumberLiteralOrUnique) || isGenericType(t)` -> TS1337,
then `!everyType(t, isValidIndexKeyType)` -> TS1268, where a valid key is `String|Number|ESSymbol`,
a pattern-literal type, **or an intersection that is not generic and has SOME valid constituent**.
Three gaps, 12 rows, all in one fixture:

| shape | ours before | pristine |
|---|---|---|
| `{ [key: TaggedString1]: string }` where `type TaggedString1 = string & { __tag }` (x9) | TS1268 | accepted |
| `` { [x: `${string}xxx${string}` & `${string}yyy${string}`]: string } `` | TS1268 | accepted |
| `[key: T \| number]` / `[key: T & string]` inside `type Invalid<T extends string>` | TS1268 | **TS1337** |

The fix is three lines of rule: an Intersection arm in `classifyIndexParamType`, the
`IntersectionType`/`ParenthesizedType` node kinds added to the resolution trigger, and
`indexParamMentionsOuterTypeParam` — an AST walk, because there is no type-parameter scope
installed at this grammar check and the alias's own `T` resolves to `anyType`, which the
classifier reads as TS1268. **Measured: 12 -> 0 ours-only, sweep 310 -> 298, pristine-only
775 -> 773 (two TRUE POSITIVES gained), zero fixtures regressed, 8-profile grid `added=0
removed=0`, `cost_gate.py` +0.00%, suite 15,285/0/3 with no corpus baseline moved.**

**ITS ABLATION — 4 arms** (`scripts/round945b-ablate.py`, each asserting `ran 13`):

| arm | the injected mistake | red | what it proves |
|---|---|---:|---|
| B1 | the Intersection arm is deleted | **5** | every valid-key pin |
| B2 | the resolution TRIGGER narrows back to `TypeReference`/`UnionType` | **2** | the two pins whose parameter NODE is a syntactic intersection — a proper subset of B1, separating "cannot classify" from "never looked" |
| B3 | the generic test goes back to a bare `TypeReference` | **2** | the two CODE-divergence pins, disjoint from B1 and B2 |
| B4 | `some(types, isValidIndexKeyType)` read as `every` — THE BOUND | **4** | the four BRANDED pins and **not** the template-literal one, whose constituents are all valid: a pin set that tested only the template-literal intersection would have been blind to this misreading |

**THIRD ACT — (CHK.19), B83.5 IN TYPE POSITION.** `function f50() { type Omit<T extends object>
= …; type A = Omit<{ a: void; b: never }> }` reported `Generic type 'Omit' requires 2 type
argument(s)`. The cause is exactly what CLAUDE.md's B83.5 says: the binder never binds a
declaration nested in a function body, and `getTypeParamInfo` — the TS2314 walker's oracle — is a
whole-program NAME scan with no node context, so the LIB's two-parameter `Omit` answered. Round
748 closed the identical gap for `enum` with `lexicalTypeSymbolForNode`; this is that shape one
declaration kind over, and it inherits both of its invariants: **the walk reads `scope.symbols`
ONLY, never `LexicalScope.existing`** (which aliases the main binder's table), and a name gate —
collected in the SAME sweep that already censuses block-scoped enums — keeps the hot
type-reference path at one set probe. **What makes it safe is structural rather than careful**:
`declareLexical` skips any name the main binder already bound in that container, so a scope-space
hit can only be a declaration the conventional tables do not have, and no bound name can resolve
differently. Measured: sweep **298 -> 297** with 0 added, pristine-only FLAT at 773, zero
regressed; grid `added=0 removed=0`; suite **15,294/0/3**.

**AND IT MOVED A COST COUNTER, WHICH IS WORTH THE PARAGRAPH.** `globals.lookups` 749,650 ->
749,626 and `globals.misses` by the same **−24** (−0.003%): tsc's own sources DO carry
block-scoped generic aliases (`PropOfRaw<T>` in commandLineParser.ts, `Mode` in tracing.ts,
`ExportCollisionTrackerTable` in checker.ts …), and those references now answer from the local
declaration instead of running `getTypeParamInfo`'s global scan. The 8-profile grid says no
VERDICT changed, so the counter move is the skipped scan and nothing else; baseline rebaselined
with `--update` in the same commit.

**ITS ABLATION — 4 arms, AND THE HONEST RESULT IS THAT THEY DO NOT SEPARATE**
(`scripts/round945c-ablate.py`, each asserting `ran 9`):

| arm | the injected mistake | red | |
|---|---|---:|---|
| C1 | the consult is removed — the whole fix | **2** | |
| C2 | the name GATE is inverted so the consult can never fire | **2** | |
| C3 | the walk reads `LexicalScope.existing` instead of `symbols` | **2** | |
| C4 | the ancestor walk is cut to the reference's own node | **2** | |

**All four red sets are IDENTICAL** — the shadowing pin and the own-arity pin — because the
consult is one path in SERIES (gate -> walk -> `symbols` -> arity) and breaking any link disables
it in the same way. Recorded as four routes to one failure rather than as four discriminators
(round 807): what they establish is that the consult is load-bearing and that `existing` does NOT
carry a block-scoped declaration, and what they do NOT establish is any per-part attribution. The
other seven pins are controls and regression guards, including B83.5's SILENT half (a body-scoped
alias that shadows nothing was never a diagnostic) and the two that no arm can reach — the lib
arity OUTSIDE the shadowing body, and two sibling bodies each answering with their own
declaration.

**NEXT.** (CHK.10), definite assignment through a late-bound `this[k] = …` — 4 rows, confirmed
genuine by round 943's strict-default arm. Then (CHK.18) and (CHK.15). **(CHK.22) is this round's
new entry** and is a modelling item: the for-of / spread operand's `[Symbol.iterator]()` RETURN is
never checked, at any target.

**Round 944 (2026-08-19) — (CHK.17): LIB AVAILABILITY WAS DECIDED FROM A TARGET DEFAULT THAT
MEANS TWO THINGS AT ONCE — `CompilerOptions.target`'s `ES3` ZERO VALUE IS INDISTINGUISHABLE
FROM "THE USER NAMED NO TARGET" — AND THE CLAUDE.md ENTRY CALLING THAT DELIBERATE WAS
RECORDING THAT IT IS *INVISIBLE*, NOT THAT IT IS TESTED. Landed as a third target notion,
`libTarget`; sweep 316 -> 313 with pristine-only FLAT; the DOWNLEVEL half is measured, has the
OPPOSITE sign, and is queued rather than bundled in.**

**THE MEASURED TABLE — the round's whole first act, and it is what decided the scope.**

| question | instrument | unset `@target` | explicit `@target` |
|---|---|---|---|
| what does PRISTINE do | pinned tsc 6 `getEmitScriptTarget` + `getDefaultLibFileName` | `LatestStandard` = ES2025 -> `lib.es2025.full.d.ts` (an explicit `es3` too) | itself; `es5` -> `lib.d.ts` |
| what did WE do (lib availability) | `libFeatureAvailable` / `libProvidesGlobalAt` / `bindRealLibs` | raw `ES3` -> `lib.d.ts` | itself |
| ours-only rows, LIB family | 611-fixture pristine sweep | **3** (`uniqueSymbols` 221, `uniqueSymbolsDeclarations` 217 TS2583; `intersectionTypeInference3` 12 TS2550) | **0** |
| ours-only rows, DOWNLEVEL family | same | **0** | **0** |
| pristine-only rows, DOWNLEVEL family | same | **4** (`for-of16` x2, `for-of29`, `iteratorSpreadInArray10`, TS2488) | — |
| corpus reachability | case-file census | `LIB_MIN_TARGET` members 0/55 · `LIB_GLOBAL_INTRODUCING` globals 0/~30 · `and N more` baselines 0/26 name no target | — |

**SO THE TWO QUESTIONS THE TASK SEPARATED HAVE OPPOSITE SIGNS**, which is exactly why they
must not be one change: lib availability is a FALSE-POSITIVE family (we say something tsc does
not), the `target < ES2015` downlevel gates are a FALSE-NEGATIVE family (we stay silent where
tsc speaks). (CHK.17) took the first; (CHK.21) carries the second with its evidence.

**AND THE CORPUS QUESTION WAS ANSWERED BEFORE THE FIRST BUILD, WHICH IS WHAT MADE THE ROUND
SAFE.** CLAUDE.md said "the CHECKER reads RAW `options.target` for lib-availability … so
TS2488/TS2550/TS2583 are unchanged for no-`@target` tests" — a claim that the corpus is
*consistent* with the raw reading. Three greps over `typescript-repo/tests/cases` say why:
**every** case file that reaches a lib-availability mechanism names its `@target` or its
`@lib` — 0 of 55 files using a `LIB_MIN_TARGET` member name, 0 of the ~30 referencing an
`Atomics`/`BigInt`/`AsyncIterable*`/`Reflect`-class global, and 0 of the 26 baselines carrying
an `and N more` member count (the B85.2 count-shift hazard). **The corpus never tested either
answer.** The prediction "no baseline will move" was written down first and the suite then
returned exactly that: **15,248 -> 15,262 / 0 failures / 3 skipped**, the +14 being this
round's pins.

**THE FIX.** `libTarget = if (targetExplicitlySet) target else ScriptTarget.ES2024` — four
call sites (`libFeatureAvailable`, `libProvidesGlobalAt`, `bindRealLibs`'s
`RealLibResolver.resolve` + `RealLibSnapshots.bindLibFiles`, and
`prewarmParsedLibFiles`, which must stay in step or the `--workers` prewarm warms a different
key set than the checkers read). **`effectiveTarget` is the wrong notion and the pins say so
out loud**: it maps an explicit `es3`/`es5` UP to ES2015, which would hand an `@target: es5`
program the ES2015 lib and delete every genuine TS2550/TS2583 — round 941 met the identical
fork at TS18028 and refused it for the same reason. An explicit `es3` stays ES3 where tsc 6
answers LatestStandard for it too; neither instrument can see that (the corpus skips every
explicit es3/es5 config, no pristine fixture sets `@target: es3`), and it is recorded rather
than guessed at.

**A PIN THAT ASSERTED THE OPPOSITE OF THE TRUTH IS THIS ROUND'S BEST FINDING.** "the default
lib set for an explicit es5 target is the es5 one" asserted `"es2018.asynciterable" !in keys`
and went RED: **the es5 default closure already carries the whole es2015 layer AND
`es2018.asynciterable`**, pulled in behind `lib.d.ts` -> dom by the DOM libs' own
`/// <reference lib=… />`. So at an explicit `es5` a later-lib GLOBAL's TS2583 comes from the
availability GATE and not from the file being absent, and the SET half is load-bearing only
for later-lib MEMBERS. The ablation reproduces that split exactly (A3 vs A4 below), and the
pin now discriminates on `es2017.string` / `es2024.full`, which do separate the two sets.

**ABLATION — 4 arms, one mistake at a time, from a sha256-verified THREE-FILE snapshot**
(`scripts/round944-ablate.py`; a change spanning three files needs a dict snapshot, or
restoring one file and leaving another ablated is a combined arm wearing a single arm's name),
each arm diffed against the SNAPSHOT and never with `git checkout`, each asserting `ran 14`:

| arm | the injected mistake | red | what it proves |
|---|---|---:|---|
| A1 | `libTarget` reads the RAW target again — the whole fix | **5** | every unset-target pin plus the accessor |
| A2 | `libTarget` becomes `effectiveTarget` — the BOUND | **3** | the explicit-`es5` pins, DISJOINT from A1; the `Reflect` pin exists solely for this arm, because `AsyncIterableIterator` (es2018) is still unavailable at ES2015 and could not separate them |
| A3 | the availability GATE alone reverts; the SET keeps the fix | **2** | the two later-lib GLOBAL pins, uniquely |
| A4 | the lib SET alone reverts; the GATE keeps the fix | **1** | the later-lib MEMBER pin, uniquely |

A3's and A4's red sets are DISJOINT and their union is a proper subset of A1's, which is the
gate-vs-set separation stated above. **Six of the 14 pins are green in all four arms and are
recorded as REGRESSION GUARDS rather than claimed as discriminators** (round 807): the
es2017-accessor pin, the four explicit-target positive controls that no arm can reach
(`es5`/`es2017` still report an es2018 global, `es2018` resolves it, `es5` still reports a
later-lib member) and the explicit-`@lib` pin, which asserts the property that makes the
8-profile grid a control at all.

**GATES.** Suite **15,262 / 0 failures / 3 skipped**, summed over all six modules with an XML
parser, NO corpus baseline moved. **8-profile grid, both arms REBUILT this round** (profiles
enumerated by `tsconfig.json`, refused below 8): `added=0 removed=0` on ALL EIGHT — and it is a
CONTROL by construction, since every profile sets `target: es2020` AND `lib: ["es2020"]`, so
neither half of the change can reach it. **630-fixture pristine sweep 316 -> 313 rows over
79 -> 78 fixtures, pristine-only 775 -> 775, ZERO regressed**; the before arm reproduced round
943's closing number exactly, which is the provenance check. `cost_gate.py`: **every counter
+0.00%** — the EXPECTED answer, not a green light (round 876), because the compiler profile
names both `target` and `lib`, so it is a control that the explicit path is untouched; the
positive control that the binary really changed is the 14 pins, which cannot compile without
`libTarget`. `huge_methods.py --fail-over 0` green on all six module class dirs (751 / 48 / 20 /
14 / 7 / 2 classes scanned — the differing counts are the per-dir positive control). No
`spine*EnterNode` changed, so `spine_closure_audit.py` does not apply.

**PROVENANCE.** Grid (after), sweep (after) and all four ablation arms were measured at
`Checker.kt` `7b78f8e8…`, `CompilerOptions.kt` `b9f80781…`, `RealLibs.kt` `1b951943…`, which is
the committed tree byte for byte; the before arm at HEAD's `1e134146…` / `3d5fc8d9…` /
`ad5d0394…`.

**ONE CORRECTION TO ROUND 943'S OWN ENTRY.** (CHK.17) was filed as "5 rows — 3 direct, each
with a cascaded TS2322". The three direct rows are gone and **both TS2322 remain**
(`uniqueSymbols` 226, `uniqueSymbolsDeclarations` 222), so they were never a cascade: they are
a contextual-typing row about a `unique symbol` return in an object literal, and they belong
with S3 rather than S6.

**NEXT.** (CHK.9) `indexSignatures1` TS1268 x12 and (CHK.10) definite assignment through a
late-bound `this[k] = …` are the smallest genuine-FP items left; (CHK.21) is the sibling of
this round with the evidence already measured.

**Round 943 (2026-08-19) — (CHK.8b): THE 89-ROW "FP — TYPE SYSTEM / INFERENCE" BUCKET,
SUB-TRIAGED — AND THE HONEST HEADLINE IS THAT **68 OF ITS 83 GENUINE ROWS (82%) ARE FOUR
MODELLING ITEMS, I.E. A FEATURE LIST, NOT A DEFECT LIST**. Six more rows are the
strict-family CONVENTION wearing codes the classifier cannot see. What was tractable —
(CHK.16), a declaration's own type parameters being invisible to the TS2344 walker — landed
in BOTH directions, and its FALSE-NEGATIVE half is the larger one.**

**THE SUB-FAMILY TABLE** (each row re-verified against pristine's own answer, or its
ABSENCE; the rules are `scripts/pristine_triage.py`'s new `SUB_BUCKETS`, so the next round
re-runs them against a fresh sweep instead of re-deriving 38 groups by hand):

| # | sub-family | rows | mechanism | cause class | tractability |
|---|---|---:|---|---|---|
| S1 | variadic tuple types | 30 | `getTupleType` gives a `RestType` element the arm a PLAIN element gets, so **`[...T]` IS `[T]`** | genuine FP | **MODELLING** (CHK.20) |
| S3 | contextual typing through a mapped / conditional type | 14 | a callback parameter gets no contextual type -> TS7006 / TS2345 | genuine FP | **MODELLING** |
| S2 | recursive conditional / mapped types over tuples | 13 | the instantiation-depth bail (TS2589) plus a deferred conditional that never evaluates | genuine FP | **MODELLING** |
| S10 | residue — one mechanism each | 11 | ten singletons | genuine FP | MODELLING |
| S4 | the strict-family default in ANOTHER COSTUME | 6 | TS2683 (`noImplicitThis`), TS7019 (`noImplicitAny`), 3x TS2322 (`strictNullChecks`) | **deliberate convention** | (CHK.13) |
| S6 | lib availability at the DEFAULT target | 5 | `libFeatureAvailable` reads the RAW `ES3` default; tsc defaults an unset target to the LATEST | genuine FP | **SMALL-MEDIUM** (CHK.17) |
| S5 | `keyof` of an intersection / index signature / remapped mapped type | 4 | `keyof (X & T)` loses `keyof T` and the index signature's `string \| number` | genuine FP | MODELLING |
| S7 | write through a generic indexed access | 3 | TS2862 where pristine says TS2322 — same position, both reject | **form** | MEDIUM (CHK.18) |
| S8 | an alias/class/interface type parameter shadowed in the TS2344 walker | 2 | the walker resolved type ARGUMENTS with no type-parameter scope | genuine FP | **FIXED** (CHK.16) |
| S9 | a function-body type ALIAS is not bound | 1 | B83.5 in type position — the lib's `Omit` beats a local one | genuine FP | MEDIUM (CHK.19) |

**WHAT THE 31 `variadicTuples1` ROWS ACTUALLY ARE: ONE mechanism, and it is three lines
deep.** `getTupleType` maps `is RestType -> getTypeFromTypeNode(elem.type)`, the same arm a
plain element gets, so `[...T]` is BUILT as the one-element tuple `[T]` —
`function f<T extends unknown[]>(t: T, m: [...T]) { t = m }` reports `Type '[T]' is not
assignable to type 'T'`. That single absence explains the fixture's whole "Relations
involving variadic tuple types" section (where pristine errors at `y = x` and we error at
`x = y`, i.e. the rows are not merely extra, they are the MIRROR), its `keyof [...T]`, its
spread-argument arity rows and the entire `curry` inference section. The 31st row is TS7019,
a `noImplicitAny` row, i.e. S4. **This is TypeScript 4.0's variadic tuples: queue it as a
feature (CHK.20), do not attempt it as a bounded rule.**

**AND THE OTHER MEASUREMENT PRODUCT: A DIAGNOSTIC ARM FOR THE CONVENTION, PLUS THE GUARD IT
NEEDED.** `pristine_sweep.py --tsc-strict-default` injects tsc's OWN `strict: false` default
where a fixture names no strict-family directive: **318 -> 272, 47 rows removed, 1 added**.
**Its first run was WRONG in the reassuring direction and it is round 941's defect (c) one
directive over: an ABSENT directive is evidence only where the CASE FILE is present.**
`strictPropertyInitialization` has no case file in this clone and **20 TS2564 in its own
baseline** — pristine plainly had the flag ON — so the unguarded arm deleted four GENUINE
false positives, exactly (CHK.10)'s, and would have reported that queue item as an artefact.
Guarded on `po.case_index()`, and read together with a second control (*does pristine's own
baseline carry that CODE anywhere in the fixture* — zero over seventeen uninitialised class
fields is conclusive), the answer is: **the convention is 46 rows, not 42; (CHK.10) is
CONFIRMED GENUINE; and 4 of my 89 belong to (CHK.13).**

**(CHK.16), THE FIX, AND IT IS TWO DEFECTS IN ONE GATE.** `checkConstraintsInStatements`
pushed a declaration's own type parameters into scope for a `FunctionDeclaration` (round 82 —
whose comment names this very defect, "would see `I<T>` resolve T to the global `class T` if
any … and emit FP TS2344"), for a type ALIAS only when the body was an `ImportType` (B98a's
narrow gate), and for a class or an interface never. So a parameter SHADOWED by a same-named
file-level type resolved to that OUTER type and was judged against the callee's constraint.
`withDeclTypeParamScope` is now the one site and all three branches use it, heritage clauses
included. **BOTH directions were wrong, so the fix ADDS diagnostics as well as removing
them**: `type Loose<Q> = Box<Q>` with `interface Box<S extends string>` was silent and now
reports TS2344 as pristine does — and over 611 pristine fixtures that gained NO ours-only row.
The type RESOLUTION path never had the defect (`getTypeFromTypeReference` answers `Wrap<"x">`
correctly with the same interface in scope), which is what bounds the change to the walker.

**TWO METHOD NOTES WORTH MORE THAN THE TWO ROWS.** (i) **The shadowing declaration is 138
LINES BELOW the alias in pristine's fixture, so every hand-written reduction is silent** —
the bisection that found it deleted the file's TAIL (a `0:291` prefix is clean; `0:291` plus
lines 300-310, which is where `interface A` lives, is the two rows). A "reduce it and probe"
loop would have concluded there was nothing there. (ii) **The first cut fixed only the ALIAS
branch and a pin written as a REGRESSION GUARD went RED** — "an interface declaration's own
type parameter was never affected" — which is how the class/interface half was found. A
regression guard that fails is a finding, not a nuisance.

**ABLATION — 4 arms, one mistake at a time, from the sha256-verified snapshot
`d1ae7270…`, diffed against the SNAPSHOT and never with `git checkout`, every arm asserting
`ran 13`** (`scripts/round943-ablate.py`):

| arm | the injected mistake | red | what it proves |
|---|---|---:|---|
| A1 | `withDeclTypeParamScope` becomes a no-op — the whole fix | **10** | every shadow pin and every gained true positive |
| A2 | the BOUND: the parameters are pushed but their CONSTRAINTS are not resolved | **5** | includes the NEGATIVE CONTROL, which no other arm reddens — it separates "the parameter is in scope" from "its constraint is honoured", and a pin that only asserted silence could have been satisfied by the parameter resolving to anything at all |
| A3 | the CLASS branch loses the scope again | **2** | the class pin and the heritage-clause pin, uniquely |
| A4 | the INTERFACE branch loses the scope again | **2** | the two interface pins, uniquely |

A3's and A4's red sets are disjoint from each other and are the state the first cut shipped.
**Two of the 13 pins are green in all four arms and are recorded as REGRESSION GUARDS rather
than claimed as discriminators** (round 807): the concrete-violating-argument pin and the
function-declaration pin.

**GATES.** Suite **15,235 -> 15,248 / 0 failures / 3 skipped** (+13 = exactly this round's
pins), **NO corpus baseline moved**. **8-profile before/after grid**, profiles enumerated by
`tsconfig.json` and refused below 8, the BEFORE arm reused from round 942 under a sha256
IDENTITY assertion (`6eda7d97…` is both HEAD's `Checker.kt` and the source that produced
those captures — a stronger provenance claim than a rebuild): **added=0 removed=0 on ALL
EIGHT**. **630-fixture PRISTINE sweep: 318 -> 316 rows over 79 fixtures, ZERO regressed,
pristine-only 775 -> 775** (no true positive lost). `cost_gate.py` moves one family —
`mapped.keyed` **+0.14%**, `typeNode.bypassed` **+0.03%**, `typeNode.cacheable`/`cacheHits`
**-0.01%**: the constraint resolutions the walker now performs INSIDE the pushed scope, a
reached-ness proof, rebaselined in the same commit. `huge_methods.py --fail-over 0` green on
**all six** module class dirs (751 / 48 / 20 / 14 / 7 / 2 classes scanned — the counts differ
per module, which is the positive control that each census saw its own dir). No
`spine*EnterNode` changed, so `spine_closure_audit.py` does not apply.

**PROVENANCE, stated because this round's whole method is hash-verified arms**: the grid, the
sweep and the ablation were all measured at `Checker.kt` sha256 `d1ae7270…`, and the
COMMITTED source differs from that arm by exactly ONE COMMENT CHARACTER — a KDoc's "140
lines below" corrected to "138" (309 − 171) — plus one test-method NAME carrying the same
number. Reversing that single line reproduces `d1ae7270…` byte for byte, which is the check
that says so rather than a claim that it is harmless.

**NEXT.** The bucket's remaining tractable work is (CHK.17) the default-target lib set —
5 rows here but a systematic real-world FP, and the same shape as round 941's TS18028 —
then (CHK.18) and (CHK.19). (CHK.9) and (CHK.10) are unchanged and still the smallest
genuine-FP items, with (CHK.10) now CONFIRMED. **The four MODELLING items (68 rows) are the
honest answer to "what is left in the largest bucket": features, scheduled as such.**

**Round 942 (2026-08-19) — (CHK.11) + (CHK.12): THE TWO NARROWING FALSE-POSITIVE FAMILIES,
AND THEY SHARE ONE CAUSE ONE LEVEL DOWN — **tsc's `isMatchingReference` compares references
by SYMBOL and ours compares the path STRINGS `getReferencePath` builds.** 16 of the
narrowing bucket's 27 ours-only rows closed; the sweep 334 -> 318 with ZERO fixtures
regressed and a true positive GAINED.**

**THE PRISTINE-vs-OURS TABLE** (our binary over pristine's OWN inputs, `(file, line, code)`
differenced against pristine's own `.errors.txt`):

| fixture | ours-only before | after | pristine-only before | after |
|---|---:|---:|---:|---:|
| `typeGuardNarrowsIndexedAccessOfKnownProperty1` (CHK.11) | 11 | **0** | 0 | 0 |
| `typeGuardsWithInstanceOfBySymbolHasInstance` (CHK.12) | 5 | **0** | 8 | **7** |
| `controlFlowInstanceofWithSymbolHasInstance` (CHK.12's other fixture) | 7 | 7 | 0 | 0 |

**DID THE TWO FAMILIES SHARE A CAUSE? YES, and it is worth stating as one sentence**: both
are the compiler asking "is this the same reference / the same instance type" through a
representation that cannot express what tsc's does. (CHK.11) is the path STRING; (CHK.12) is
the missing `[Symbol.hasInstance]` leg plus an `instanceof` that filters a UNION candidate
with the STRUCTURAL relation where tsc uses the NOMINAL one. They were established as
separate before either was designed — the queue said one was `getTypeOfElementAccess` and the
other `resolveInstanceOfRhsType`, and both turned out to be true.

**(CHK.11), FOUR mechanisms.** `singleLevelDiscriminantSegment` — the switch's discriminant
reader accepts `name[seg]` beside `name.seg`. `getTypeOfElementAccess` flow-narrows its UNION
RECEIVER, the B1.1 gate its dotted twin has always had. `getReferencePath` NORMALISES an
identifier-spellable string index onto the DOTTED segment, because the fixture mixes both
spellings inside ONE expression (`s[0]["sub"].under["shape"]`) — a non-spellable index
(`"dash-ok"`, `0`) keeps round 461's bracket encoding, so no path can collide with a dotted
segment and `flowPathRoot`/`pathPrefixOf` already split on `[`. And `requiredEnumSwitchKeys`
+ `paramMemberChainType` accept an element-access discriminant and a MULTI-segment receiver,
which is the two TS2366 "function lacks ending return statement".

**A FIFTH MECHANISM WAS WRITTEN, MEASURED INERT AND REMOVED — and the ablation is what found
it.** Narrowing the access's own union RESULT (the 17.34d half, the exact symmetric line to
`getTypeOfPropertyAccess`) looked obviously right and reddened **NONE** of the round's 21
pins; no probe could be built where it fires either, because the `typeof` guard does not
reach an element-access reference at all (`if (typeof h[0] === "string") { … h[0] }` still
reports the declared `string | number` with it in place). A flow walk on a hot path with no
consultation that can observe it is CLAUDE.md's round-887 shape, so it went — which also gave
back part of the round's `narrow.walks` cost.

**(CHK.12), and TWO rules read off PRISTINE's own baseline rather than guessed.** `instanceof`
now asks the RHS type for a `[Symbol.hasInstance]` method whose return is a non-`asserts` TYPE
PREDICATE over parameter 0, and uses its target — round 838's `instanceTypeOfConstructorValue`
named that leg as its one deliberate omission, and it is what answers the three shapes
`prototype` and the construct signatures cannot (a GENERIC construct signature, SEVERAL
construct signatures, one returning `any`). (i) **A usable predicate DECIDES**: `value is any`
narrows NOTHING and must not fall through to the construct signature — pristine reports
`string | F` at its own lines 142/143 with a perfectly good `new (): any` beside it. (ii) **An
`instanceof` stays `checkDerived = true` even when the candidate came from a predicate**, so a
UNION candidate is DISTRIBUTED and its narrow-down direction is the NOMINAL base-chain test,
not assignability: `C1 | A` narrowed by `C1 | C2` is **C1** (`A` is structurally a supertype
of BOTH candidates, so the assignability form mapped it onto the whole union and then reported
`bar1` missing on `C2`), while `B0 | string` narrowed by `D1 extends B0` is still **D1**.
SCOPED to a union candidate, so round 425's single-candidate arm — whose `tracker instanceof
SymbolTrackerImpl` case depends on the assignability form — is byte-identical.

**AND THE QUEUE ENTRY WAS WRONG ABOUT ITS OWN SECOND FIXTURE, WHICH IS THE THIRD ROUND RUNNING
THAT RE-MEASURING FIRST HAS PAID FOR.** (CHK.12) was written as "11 rows over two fixtures";
`controlFlowInstanceofWithSymbolHasInstance` is **7 rows and SIX of them are a PARSER GAP** —
`abstract new (...args: any) => infer U` — with TS1005/TS1068/TS1128 cascading into
TS2355/TS2564/TS2304. Its one genuine narrowing row is the missing `instanceof` INTERSECTION
tail. Both queued with their three-line probes as **(CHK.14)** and **(CHK.15)**; (CHK.14) also
records a SECOND, separable defect the same probe found — the NON-abstract
`T extends (new (…) => infer U) ? U : never` parses and then reports TS2304 for `U`, i.e. an
`infer` inside a PARENTHESIZED extends clause does not publish its name.

**ABLATION — 9 arms, ONE MISTAKE AT A TIME, from a sha256-VERIFIED snapshot, diffed against
the SNAPSHOT and never with `git checkout`** (`scripts/round942-ablate.py`; every arm asserts
`ran 21`):

| arm | the injected mistake | red | what it proves |
|---|---|---:|---|
| A1 | the switch discriminant reader refuses a BRACKET segment | 1 | the numeric-index pin — and ONLY that one, because a SPELLABLE index is already normalised onto the dotted branch by A3's mechanism |
| A2 | an element access stops narrowing its UNION RECEIVER | 3 | the two element-access reads and the numeric-index pin |
| A3 | a spellable string index stops normalising onto the dotted segment | 3 | exactly the three MIXED-SPELLING pins |
| A4 | the exhaustive-switch key reader refuses an ELEMENT-ACCESS discriminant | 2 | both TS2366 pins |
| A5 | the exhaustiveness receiver walk goes back to ONE dotted segment (round 470) | 1 | the DEEP mixed-spelling TS2366 pin |
| A9 | an element access stops narrowing its own union RESULT | **0** | **nothing — which is why that mechanism was deleted rather than shipped** |
| A6 | the `[Symbol.hasInstance]` leg is removed | 7 | every CHK.12 positive plus the `any` bound |
| A7 | a UNION candidate stops being distributed nominally | 2 | the `C1 \| A` pair |
| A8 | the leg's BOUND: a wide predicate target falls THROUGH instead of deciding | 1 | the `value is any` bound pin, alone |

**TWO ARMS REFUSED ON THEIR FIRST RUN AND BOTH REFUSALS WERE THE HARNESS DOING ITS JOB**, not
noise: A4's `if (false && expr is …)` mistake DROPS Kotlin's smart cast, so the arm stopped
COMPILING and read `ran 0`; A9's anchor (`if (raw is Type.Union && getReferencePath(expr) …)`)
occurs VERBATIM in `getTypeOfPropertyAccess` too, and the driver refused a 2-hit anchor rather
than ablating the wrong function. Both were re-run with corrected mistakes. **Four pins are
green in all nine arms and are recorded as REGRESSION GUARDS rather than claimed as
discriminators**: the dotted-discriminant control, the dynamically-indexed bound, the
non-first-parameter bound, and the no-`hasInstance` construct-signature control.

**A THIRD TRAP, MEASURED: `o["a"]` where `a?: string` is `string` in this compiler, not
`string | undefined`** — optionality is a symbol attribute and is not folded into the property
type (CLAUDE.md already says so about the relation; it is equally true of the READ). A
"negative control" written on that shape passed vacuously and had to be replaced with a
MULTI-SEGMENT mixed-spelling shape, which is what pins the normalisation.

**GATES.** Suite **15,214 -> 15,235 / 0 failures / 3 skipped** (+21 = exactly this round's
pins), **NO corpus baseline moved**. **8-profile before/after BINARY grid** (two
sha256-verified arms, profiles enumerated by `tsconfig.json` and refused below 8):
**added=0 removed=0 on ALL EIGHT.** **630-fixture PRISTINE sweep, both arms: 334 -> 318 rows
over 81 -> 79 fixtures, ZERO fixtures regressed, pristine-only 776 -> 775** (a true positive
GAINED). `cost_gate.py` moves one family — `narrow.walks` **+0.15%** and `narrow.memoServed`
**+0.14%**, the element-access receiver's new flow reads, with `typeOfExpr.calls` **-0.01%**
because a narrowed receiver resolves its member without the union fold: a REACHED-NESS proof,
rebaselined in the same commit. `huge_methods.py --fail-over 0` green on **all six** module
class dirs. No `spine*EnterNode` changed, so `spine_closure_audit.py` does not apply.

**NEXT.** (CHK.14) the `abstract new` / parenthesized-`infer` parser gaps (6 rows measured
plus the 17-row `infer X extends` family and the 33-row `using` family they join); (CHK.15)
the `instanceof` intersection tail. (CHK.9) and (CHK.10) are unchanged and still the smallest
genuine-FP items; (CHK.13) remains an owner decision.

**Round 941 (2026-08-19) — (CHK.8): THE 630-FIXTURE PRISTINE SWEEP, TRIAGED — AND THE
ROUND'S FIRST PRODUCT IS THAT **THE INSTRUMENT WAS WRONG ABOUT 30% OF ITS OWN ROWS**.
121 of round 940's 397 OURS-ONLY rows were the sweep's configuration, not the compiler's
answers, and all three defects failed in the reassuring direction — a phantom divergence
looks exactly like a real one. Two false-positive families are then closed, both measured
against pristine's own fixtures, both invisible to the corpus by construction.**

**THE BUCKET TABLE — 373 ours-only rows over 84 fixtures at `967c2e53`, every row
classified, the rules in `scripts/pristine_triage.py` and the evidence in
`docs/pristine-divergences.md`.**

| rows | % | bucket | cause class | exemplar |
|---:|---:|---|---|---|
| 89 | 23.9 | FP — type system / inference | **genuine FP** | `variadicTuples1` TS2322 x15 + TS2345 x14 |
| 59 | 15.8 | HARNESS — jsx configuration | **harness artefact** | `tsxLibraryManagedAttributes` TS2874 x27 |
| 59 | 15.8 | PARSER GAP — unsupported syntax | **cascade** | `usingDeclarations*` (33), `infer X extends` (17) |
| 42 | 11.3 | CONVENTION — strict-by-default | **deliberate divergence** | `keyofAndIndexedAccess` TS2564 x17 |
| 31 | 8.3 | PARSER RECOVERY on a malformed fixture | **cascade** | `mappedTypeProperties` (23) |
| 27 | 7.2 | FP — computed keys / declaration emit | **genuine FP** | `indexSignatures1` TS1268 x12 |
| 27 | 7.2 | FP — narrowing / control flow | **genuine FP** | `typeGuardNarrowsIndexedAccessOfKnownProperty1` (11) |
| 26 | 7.0 | **FIXED — private-identifier target gate** | **genuine FP** | `strictPropertyInitialization` TS18028 x16 |
| 13 | 3.5 | **FIXED — super-call statement scan** | **genuine FP** | `derivedClassSuperProperties` TS2376 x13 |

**Cause-class totals: genuine FP 182 (48.8%) · cascade 90 (24.1%) · harness artefact 59
(15.8%) · deliberate convention 42 (11.3%).** 39 of the 182 are closed here. **NO
ACTIVE-BASELINE ROW APPEARS ANYWHERE IN THE TABLE** — the population is by construction the
fixtures the generated suite does not gate, which is exactly why the corpus is green while
these rows exist.

- **DEFECT (a), AND IT IS THE ONE WITH A LESSON: `extract_sources` FELL BACK TO
  `tests/cases` WHENEVER NO *EXACT* `<stem>.js` BASELINE EXISTED — i.e. FOR EVERY
  MULTI-VARIATION CASE — AND THE CASE FILE STILL CARRIES THE `// @target:` HARNESS
  DIRECTIVES THAT tsc STRIPS.** Every line number was then the baseline's PLUS the directive
  count, so the sweep reported every row of those fixtures as a divergence in BOTH
  directions: 27 of 630 fixtures, `commonMissingSemicolons` alone **42** phantom rows and
  `classUsedBeforeInitializedVariables` **6** (which read as a tidy six-row TS2729 FP family
  and is in fact six of pristine's own rows shifted by two). **The guard is now an ALIGNMENT
  ORACLE**: each reconstructed input is compared line-for-line against pristine's own
  `==== file ====` annotation and the verdict recorded per fixture. One fixture is
  `misaligned` today (`classMemberWithMissingIdentifier2`); every other row above has been
  read against a source pristine itself would recognise.
- **DEFECT (b): DIRECTIVES WERE READ FROM THE *EXTRACTED* TEXT, AND THE `.js` BASELINE
  ECHOES THE SOURCE VERBATIM *WITHOUT* THEM.** So a fixture whose source came from a
  baseline recovered ZERO directives: `decoratorsOnComputedProperties` read **10** phantom
  TS1166 for want of `@experimentalDecorators`, `jsxElementType` **46 -> 22** for want of
  `@jsx`. The fix refuses to re-derive the mapping in Python — `TsConfigLoader` routes every
  `compilerOptions` key through the SAME `applyDirective` the corpus harness uses, so the
  fixture's directives are copied into the scratch tsconfig VERBATIM and unknown keys are
  ignored by `applyDirective` itself.
- **DEFECT (c): A MISSING CASE FILE LEFT NO TARGET AT ALL, WHERE THE BASELINE'S OWN
  `(target=es2015)` SUFFIX IS THE LAST SURVIVING RECORD OF IT.** `derivedClassSuperProperties`
  compiled at the esnext default, where tsc's TS2376 rule is switched off entirely by
  `emitStandardClassFields` — i.e. the round's largest FP family was being measured under a
  configuration in which the reference emits nothing.
- **AND ROUND 940's FORCED `"strict": false` IS GONE**, which is what surfaced the
  strict-by-default bucket (**+97 rows**). **373 is therefore NOT comparable to 397
  row-for-row** — same commit, different instrument; only same-instrument arms are.

**THE TWO FIXES.**

- **(1) TS2376 — A `super` CALL NEED NOT BE FIRST.** Ours required `super()` to be the first
  non-prologue statement. tsc (`checkConstructorDeclaration` +
  `nodeImmediatelyReferencesSuperOrThis` + `isThisContainerOrFunctionBlock`) walks the
  constructor's own statement list until EITHER the super call OR the first statement that
  IMMEDIATELY references `this`/`super`, and only the second outcome is an error — so any
  number of statements may precede `super()` as long as none touches `this` in the
  constructor's own `this` scope. The walk stops at an arrow function (arrows evaluate
  later: `const getThis = () => this` before `super()` is legal), a function
  declaration/expression, a property declaration, and at a method-like BODY.
  **THE BOUND IS THE INTERESTING HALF AND THE FIRST CUT GOT IT WRONG**: a method-like body
  stops the walk, its NAME does not, so `get [this.propName]() {}` before `super()` IS still
  TS2376 (pristine `derivedClassSuperProperties` lines 281 and 323). A cut that skipped
  every member name lost both rows **while every "this is no longer an error" pin stayed
  green** — only the sweep's PRISTINE-ONLY column showed it. Measured: 13 -> 0 ours-only and
  pristine-only 20 -> 19, i.e. a true positive GAINED.
- **(2) TS18028 — THE PRIVATE-IDENTIFIER GATE READS THE TARGET THE USER *ASKED FOR*.**
  `CompilerOptions.target` defaults to `ES3` while tsc's `getEmitScriptTarget` defaults an
  unset `target` to the latest standard, so a raw `target <= ES5` read made every `#field`
  in a project with no `target` an error. The gate is now
  `options.targetExplicitlySet && options.target <= ScriptTarget.ES5` — **not**
  `effectiveTarget`, which maps an explicit ES5 up to ES2015 and would drop the true
  positive an explicit `@target: es5` must keep. **The corpus is structurally blind to both
  sides**: `usesUnsupportedOption` skips every explicit es3/es5 config, so no ACTIVE baseline
  exercises this gate at all.

- **FOUR-ARM ABLATION, ONE MISTAKE AT A TIME, TWO ARMS PER FIX** (`scripts/round941-ablate.py`,
  from a sha256-verified snapshot, never `git checkout`, diffed against the SNAPSHOT, each
  arm asserting **ran 21**): **A1 8 red / A2 2 / A3 1 / A4 2, four DISJOINT red sets.** A2 is
  the one worth reading — it is exactly the defect the first cut shipped, and the pin that
  catches it exists only because the sweep found it. **Eight of the 21 pins are green in all
  four arms and are recorded as REGRESSION GUARDS rather than claimed** (round 807): the
  parenthesized-`super()` and prologue-directive pins (A1 keeps both mechanisms), the three
  TS2376 positive controls, the no-initialized-property control, and the two "an explicit
  ES2015/ESNext target is silent" pins. **The driver's first run REFUSED all four arms at
  `ran 0`** — Gradle takes no `|` alternation in a single `--tests` — which is round 856's
  law paying for itself: without the ran-count assertion that would have printed as four
  clean sweeps.
- **GATES.** Suite **15,193 -> 15,214 / 0 failures / 3 skipped** (+21 = exactly this round's
  pins), **NO corpus baseline moved**. `cost_gate.py` **+0.00% on all 20 counters**
  including `output.errors 46` — expected, since neither fix is reachable from tsc's own
  sources. `huge_methods.py --fail-over 0` green on **all six** module class dirs. The
  **8-profile before/after BINARY grid** (`scripts/round941-grid.sh`, two sha256-verified
  arms) **added=0 removed=0 on ALL EIGHT**. The **630-fixture sweep, both arms in the same
  driver: 373 -> 334 rows over 84 -> 81 fixtures, ZERO fixtures regressed, pristine-only
  777 -> 776.** No `spine*EnterNode` changed, so `spine_closure_audit.py` is not applicable.
- **NEXT.** Five entries added with their bucket's evidence — **(CHK.9)** index-signature
  parameter types (12 rows, the largest single-code FP family left), **(CHK.10)** definite
  assignment through a late-bound element access (4), **(CHK.11)** element-access
  discriminant narrowing (11), **(CHK.12)** `Symbol.hasInstance` narrowing (11), and
  **(CHK.13)** the strict-by-default convention (42), which is an OWNER decision rather than
  a fix. `(CHK.5)` still continues at (c); `(CHK.7)` still keeps (ii) and (iv).

**Round 940 (2026-08-19) — (CHK.7)(i)+(iii) AND (CHK.5)(f): THREE PRISTINE DIVERGENCES,
ALL FALSE POSITIVES, ALL CLOSED — AND THE ROUND'S PRODUCT IS THAT **ROUND 939's QUEUE
ENTRY WAS WRONG ABOUT TWO OF ITS OWN FOUR ROWS, IN THE DIRECTION THAT DECIDES WHAT TO
BUILD**: (iii) is **3** extra lines and not 25, and (iv) is a false **NEGATIVE** in **one**
scan, not a false positive in "the duplicate scans". Re-measuring each row against pristine
BEFORE touching anything is what caught both, and it cost one command per fixture.**

**THE TABLE. Every row read from PRISTINE offline (`scripts/pristine_oracle.py --fixture
… --extract`), our binary run over pristine's OWN input, and every ours-only row re-read at
a SECOND target before being believed — round 939's method note, paid for twice.**

| # | fixture | PRISTINE | OURS before | OURS after | verdict |
|---|---|---|---|---|---|
| (i) | `symbolProperty1` | TS2454 only (a check we lack) | + **TS1117 ×2** | — | **FP, CLOSED** |
| (i) | `symbolProperty2` | (silent) | + **TS1117 ×2** | — | **FP, CLOSED** |
| (i) | `symbolProperty3` | TS2464 ×3 (we lack) | + **TS1117 ×2** | — | **FP, CLOSED** |
| (ii) | `symbolProperty52` | TS2339 ×2 on the KEY | **TS2741 `'[Symbol.nonsense]'`** + 1 of the 2 TS2339 | unchanged | **FP, RE-QUEUED — modelling** |
| (iii) | `privateNameDuplicateField` | 83 rows | 50 rows, **3 ours-only** (106:13, 156:13, 381:20) | 47 rows, **0 ours-only** | **FP, CLOSED** |
| (iv) | `numericStringNamedPropertyEquivalence` | 7 rows | 4 rows, **0 ours-only**, **3 MISSING** | unchanged | **FN, RE-QUEUED** |
| (f) | `dynamicNamesErrors` / `duplicateIdentifierComputedName` / `assignmentCompatWithEnumIndexer` / `symbolProperty21` | names the key AS WRITTEN | `Property 'p'` | `Property '[K]'` | **FORM, CLOSED** |

- **(i) A SPELLING IS NOT A NAME.** `evaluateComputedPropertyName` named a reference key
  `__@computed:<text>`, so two occurrences of the same DYNAMIC key were a duplicate to us
  and not to pristine. **The corpus structurally cannot see it**: the three
  `duplicateObjectLiteralProperty_computedName*` fixtures ARE active gates and we pass
  them, and pristine's own negative control
  (`duplicateObjectLiteralProperty_computedNameNegative1`) uses two DIFFERENT identifiers,
  which a spelling key satisfies exactly as a value key would.
  **THE FIX IS AN ABSTAIN, AND ITS BOUND IS THE WHOLE DESIGN.** A blanket "abstain unless
  late-bindable" regresses `duplicateObjectLiteralProperty_computedName3` — an ACTIVE gate
  whose keys are `[keys.n]` / `[keys.E1.A]` through an `import * as keys`, which pristine
  binds through the key's TYPE and round 935's SYNTACTIC resolver deliberately cannot
  follow across a file. So the namer abstains ONLY when the key's own declaration is IN
  HAND and late binding still refused it (`var s: symbol`, `var s = Symbol`, a widened
  `let`), and keeps the pre-940 spelling otherwise. **Unknown keeps the old answer**, so
  the refusal can only remove a duplicate we have EVIDENCE is not one. Arm A2 is that
  bound and it reddens the corpus gate, on the nose.
  **AND THE EVIDENCE THAT PRISTINE BINDS BY VALUE IS A PAIR, NOT A FIXTURE**: `[s]`/`[s]`
  with `var s: symbol` is SILENT and `[n]`/`[n]` with `const n = 1` is TS1117 — same
  spelling shape, opposite answers, so the discriminator is the key's VALUE. That pair is
  what licenses the new `{ 1: 1, [n]: 0 }` pin, which no single fixture shows.
- **(iii) AN ACCESSOR FOLLOWED BY A PROPERTY IS REPORTED AT THE PROPERTY ALONE, AND THE
  MECHANISM IS tsc's `PropertyExcludes = None`.** The class scan split on whether the
  accessor pair was COMPLETE and flagged the whole group otherwise; pristine flags only the
  PROPERTY whenever every accessor precedes it, complete pair or not. A property declared
  LAST never trips tsc's BINDER duplicate check (its excludes mask is empty), so only the
  checker's per-class scan reports it — and that one reports at the current member alone.
  The model reproduces **every one of `privateNameDuplicateField`'s 83 rows** and both
  halves of `duplicateClassElements` (`public x; get x; set x` → all three; `get x2; set x2;
  public x2` → only `x2`).
- **(f) TS2741 NAMES A LATE-BOUND MEMBER AS WRITTEN**, wired at `formatPropertyDisplayName`
  — the one renderer the missing-property emitters already route the symbol through — via
  round 938's `computedKeyWrittenText`, which answers null for a spelling it cannot
  reproduce exactly, so a message can never carry a name the source does not contain.

- **THE INSTRUMENT IS NOW A SCRIPT: `scripts/round940_pristine_sweep.py`.** Round 939 ran
  its sweep by hand and committed only the oracle. This selects fixture stems by an
  explicit, quotable ERE (a computed member key in MEMBER position — **630 stems, 611 with
  recoverable source**), materialises pristine's own input, honours the case's `// @target`,
  and differences (file, line, code). **BOTH ARMS, one binary each: 74 → 71 fixtures with
  ours-only rows, 403 → 397 rows, ZERO fixtures regressed**, and the six rows removed are
  exactly `symbolProperty1/2/3`. (`privateNameDuplicateField` is outside that population —
  its members are `#foo`, not computed keys — and was measured separately, 3 → 0.)
  Its 403 is NOT comparable to round 939's 23: a different, ~2× larger population.
- **8-PROFILE GRID, two sha256-VERIFIED binaries** (`scripts/round940-grid.sh`, which also
  runs the sweep per arm): **added=0 removed=0 on ALL EIGHT**, 46/46/46/46/46/46/46/94.
  Nothing on tsc's own sources moves in either direction.
- **FIVE-ARM ABLATION, one mistake at a time** (`scripts/round940-ablate.py`, from a
  sha256-verified snapshot, never `git checkout`), each arm asserting **ran 69** so a dead
  build reads as a failure rather than as a clean sweep. TWO ARMS PER FIX BY DESIGN — one
  removes the fix, one removes its BOUND — because a "this is now silent" pin cannot tell a
  correct refusal from a disabled check.

| arm | the injected mistake | red | what it proves |
|---|---|---|---|
| A1 | (i) removed — the reference arms name by SPELLING again | **4** | the three abstain pins + the value-vs-spelling discriminator |
| A2 | (i)'s BOUND removed — abstain for every unresolved key | **2** | the cross-file control **and `duplicateObjectLiteralProperty_computedName3`, the ACTIVE corpus gate** |
| A3 | (iii) removed — accessor+property flags the whole group | **7** | the four "at the FIELD alone" pins, plus `duplicateClassElements` + `gettersAndSettersErrors` (the pre-existing complete-pair rule this branch absorbed) |
| A4 | (iii)'s ORDER clause removed — always flag only the property | **4** | the mirrored-order positive controls + `duplicateClassElements` |
| A5 | (f) removed — the missing member is named by its VALUE again | **3** | the three written-key pins, and NOT the two negative controls |

- **UNDISCRIMINATED PINS, RECORDED AS SUCH RATHER THAN CLAIMED.** Green in all five arms,
  i.e. regression guards and not discriminators: `a repeated LATE-BOUND computed key is
  still a duplicate`, `a repeated LITERAL computed key is still a duplicate`, `a repeated
  well-known symbol key is still a duplicate` (all three survive a spelling key too), `two
  different unresolvable keys are not a duplicate`, `a late-bound key does not collide with
  a different member`, `two getters are still TS2300 at BOTH`, `a getter followed by a
  method is still TS2300 at BOTH`, `a clean accessor pair is silent`, and (f)'s two
  negative controls (which would discriminate an arm applying the renderer to a
  NON-computed name — no such arm was run).
- **GATES.** Suite **15,168 → 15,193 / 0 failures / 3 skipped** (+25 = exactly this round's
  pins), **NO corpus baseline moved**. `cost_gate.py` moves ONE family —
  `globals.lookups` **+0.05% (+372)** and `globals.misses` +0.05% — which is the
  late-binding namer now being consulted at the object-literal duplicate scan, i.e. a
  REACHED-ness proof for (i) on the compiler profile rather than noise; rebaselined in the
  same commit. `huge_methods.py --fail-over 0` green on **all six** module class dirs
  (core 751 classes, api 14, client 20, daemon 7, cli 2, project 48 — round 909's
  `--classes` blindness answered by naming each). No `spine*EnterNode` changed, so
  `spine_closure_audit.py` is not applicable.
- **NEXT.** `(CHK.5)` continues at **(c)**; **(f) is done**. `(CHK.7)` keeps (ii) and (iv),
  both re-measured and re-scoped below.

**Round 939 (2026-08-19) — (CHK.6): THE COMPUTED-KEY FAMILY, RE-JUDGED AGAINST *PRISTINE*.
NO CODE. THE ROUND'S PRODUCT IS AN INSTRUMENT AND A VERDICT: **`tools/tsgo-7.0.2/lib/tsc`
IS THE ONLY REFERENCE THAT *RUNS* HERE AND IT IS NOT THE REFERENCE WE DIFF AGAINST — BUT
THE PRISTINE ORACLE WAS ON DISK ALL ALONG, IN THE CORPUS'S OWN `tests/baselines/reference`,
AND IT SAYS ROUNDS 933-938 LANDED NOTHING THAT PRISTINE CONTRADICTS.** Round 938 found the
two references parting on its own territory and had to redesign around it; the worry this
round was commissioned to settle is that rounds 933-937 had tsgo-only evidence for rows no
corpus baseline covers. Measured: **the corpus protects far more of the family than the
notes claim** — `dynamicNames`, `dynamicNamesErrors`, `duplicateIdentifierComputedName`,
`destructuredLateBoundNameHasCorrectTypes`, `checkDestructuringShorthandAssigment2`, the
three `duplicateObjectLiteralProperty_computedName*` and **7 of the 10 TS2717 baselines in
the entire corpus** are ACTIVE, byte-exact gates sitting directly on these decisions.

- **THE INSTRUMENT: `scripts/pristine_oracle.py`.** Given a code, a source pattern or a
  fixture name it finds the PRISTINE baselines that exercise the shape and prints what
  pristine tsc emitted — and labels every hit **ACTIVE / not generated**, i.e. whether the
  suite is already gating it. Four things make it work rather than merely exist.
  (i) **An ABSENT `.errors.txt` beside a present case is evidence**: it means pristine was
  SILENT, which is exactly the half a "does tsc complain about this?" question needs.
  (ii) **`tests/cases` in this clone is INCOMPLETE** — 6,537 files against 9,055 error
  baselines, whole conformance directories missing — so a pattern search over it alone
  misses silently; the search runs over the `.js` and `.errors.txt` baselines too, which
  echo every input verbatim, via `grep` (0.2 s over 53,049 files; the pure-Python version
  timed out at 120 s and is why the sweep is shelled out).
  (iii) **`--extract DIR` writes pristine's own input back out**, so our compiler can be run
  over exactly what pristine tsc saw — that is what turned this round from a reading
  exercise into a measurement.
  (iv) **`.types` / `.symbols` baselines answer naming questions with no diagnostic at
  all**: `computedPropertyNames10_ES6.types` records `` [`hello bye`]() `` as the member
  `"hello bye"` and `` [`hello ${a} bye`]() `` as `[x: string]`, which is round 933's whole
  landed rule and its negative control, read straight off pristine.

**THE CLASSIFICATION.** Every landed behavioural decision of rounds 933-938, one row each.
**PRISTINE-CONFIRMED 22 · CORPUS-SILENT 10 · tsgo-ONLY 1 · PRISTINE-DIVERGENT 1**, and the
one divergence is a message FORM the round that landed it had already recorded as open.

| # | the landed decision | verdict | evidence |
|---|---|---|---|
| 933.1 | a backtick-quoted computed key NAMES a member | **PRISTINE-CONFIRMED** | `computedPropertyNames10_ES6.types` names the member `"hello bye"`; `11/13/16_ES6` carry the key on a class and on accessors and pristine is SILENT — and so are we, measured |
| 933.2 | a SUBSTITUTING template names NO member | **PRISTINE-CONFIRMED** | same `.types` baseline: it contributes `[x: string]`, an index signature |
| 933.3 | `classMemberNameText` DELEGATES (the two sites cannot drift) | CORPUS-SILENT | an internal-consistency rule; its observable (TS2322 + TS2339 in one compile) has no pristine fixture |
| 934.1 | the excess check acts on a computed key spelling one fixed name, in every position | **PRISTINE-CONFIRMED, ACTIVE** | `checkDestructuringShorthandAssigment2` |
| 934.2 | a BARE numeric key `{ 7: 2 }` is excess-checked | CORPUS-SILENT | swept all 92 TS2353 baselines: none names a bare numeric key |
| 934.3 | the excess message names the key AS WRITTEN, delimiters kept | **PRISTINE-CONFIRMED ×3** | `'[k]'` (`checkDestructuringShorthandAssigment2`, ACTIVE, squiggle over the written key), `'[Symbol.toPrimitive]'` (`symbolProperty21`), **`'"resolution-mode"'`** (`nodeModulesImportTypeModeDeclarationEmitErrors1`) — the last is the `'"zz"'` row, a quoted key rendered WITH its quotes |
| 934.4 | the NUMERIC-index-signature absorption guard | CORPUS-SILENT | |
| 934.5 | `[E.P]` is not named by `computedSymbolKey`'s invented name | CORPUS-SILENT | superseded at its source by 935's ordering |
| 934.6 | a substituting template stays OUT of the excess check | **PRISTINE-CONFIRMED** | the `.types` index-signature evidence above |
| 935.1 | a `const`/`declare const`/annotated-`let` literal key LATE-BINDS (supply side silent) | **PRISTINE-CONFIRMED, ACTIVE ×2** | `dynamicNames`: `export const o1 = { [c4]: 1, … }` then `export const o2: T0 = o1` — silent; `destructuredLateBoundNameHasCorrectTypes`: `const named = "prop"` as a destructuring key |
| 935.2 | an ENUM member's VALUE late-binds | **PRISTINE-CONFIRMED, ACTIVE** | `duplicateObjectLiteralProperty_computedName2`: `[E1.A]`/`[E1.A]` and `[E2.B]`/`[E2.B]` are duplicates in pristine, i.e. the key binds to the member's value |
| 935.3 | a numeric key names its VALUE, not its source text | **PRISTINE-CONFIRMED** | `computedPropertyNames10_ES6.types`: `[0]()` is the member `0`, `[""]()` the member `""`; `duplicateObjectLiteralProperty_computedName1` (ACTIVE) makes `1` / `[1]` / `[+1]` / `"1"` one name and `"+1"` another |
| 935.4 | the five REFUSALS (widened `let`, genuine union, plain `symbol`, bare TP, ambient value-less enum member) | **PRISTINE-CONFIRMED in kind** | `computedPropertyNames5/6/8/14/15/17/51_ES6`: pristine refuses a non-literal key outright (TS2464) rather than binding it |
| 935.5 | the EXCESS direction for the same keys | CORPUS-SILENT | |
| 935.6 | the name must be a function of the PROGRAM, not of the pass | CORPUS-SILENT | an invariant, not an output |
| 935.7 | the language service does NOT treat `[K]` as a member position | **tsgo-only** | read from tsgo's LSP; no baseline expresses a rename's extent |
| 936.1 | QUALIFIED heads late-bind (`NS.K`, nested, dotted, merged, enum-in-namespace) | **PRISTINE-CONFIRMED, ACTIVE** | `dynamicNames`: `namespace N { export const c2 = "a"; export interface T4 { [N.c2]: number … } }` plus `class T5 implements T4` — silent |
| 936.2 | the TEMPLATE-LITERAL TYPE head | CORPUS-SILENT | |
| 936.3 | the TYPE-ALIAS hops | CORPUS-SILENT | |
| 936.4 | a WELL-KNOWN `[Symbol.X]` key is EXCESS, named as written | **PRISTINE-CONFIRMED, RE-RUN** | `symbolProperty21` — pristine `TS2353 '[Symbol.toPrimitive]'` at (10,5); our binary on the extracted fixture emits the same code, position and key name |
| 936.5 | the `string`-typed key display (`{}` vs `{ [L]: number; }`) is a recorded GAP | **PRISTINE-CONFIRMED as a gap** | `indexSignatures1` prints `{ [sym]: number; }` for the same mechanism |
| 936.6 | the embedded lib gains `IterableIterator[Symbol.iterator]()` | **PRISTINE-CONFIRMED** | tsc's own `lib.es2015.iterable.d.ts` declares exactly that member (`build/generated/real-lib`) |
| 937.1 | an interface / class / type literal DECLARES and TYPES its own `[K]` member | **PRISTINE-CONFIRMED, ACTIVE** | `dynamicNames` declares `[c0]`/`[c1]`/`[s0]` on an interface, a `declare class` and a type alias, in a module and in a namespace |
| 937.2 | a computed METHOD name reaches `getTypeOfSymbolWorker` | CORPUS-SILENT | |
| 937.3 | `classMemberNameText` (the TS2339 firewall) knows late-bound keys | CORPUS-SILENT | |
| 937.4 | the `implements` / transitive-name FP fixes, incl. the two NUMERIC rows | **PRISTINE-CONFIRMED, ACTIVE** | `dynamicNames`: `declare class T13 implements T2 { a: number; 1: string; [s2]: boolean }` and `declare class C { static 1: string; static [s2] }` — silent; this baseline is what MOVED mid-round-937 and forced the sibling walkers into that commit |
| 937.5 | `checkComputedLiteralKeyMembers` RETRACTS | **PRISTINE-CONFIRMED, ACTIVE** | `dynamicNamesErrors` must carry its TS2717 exactly once (arm A7) |
| 937.6 | TS2741 for a missing late-bound member is named **`'p'`** | **PRISTINE-DIVERGENT (FORM)** | pristine names the key AS WRITTEN wherever it names one — `'[E.A]'` (`assignmentCompatWithEnumIndexer`), `'["a"]'` (`duplicateIdentifierComputedName`, ACTIVE), `'[c1]'` (`dynamicNamesErrors`, ACTIVE), `'[Symbol.toPrimitive]'` — so `'[K]'` is the pristine answer. Verified live at HEAD. **No baseline covers the exact shape, which is why the suite is green**; recorded by round 937 against tsgo, and pristine AGREES with tsgo here. Queued as (CHK.5)(f) |
| 938.1 | the member map is FIRST-WINS | **PRISTINE-CONFIRMED, ACTIVE ×7** | 7 of the 10 TS2717 baselines in the whole corpus are generated gates: `classWithDuplicateIdentifier`, `duplicateClassElements`, `gettersAndSettersErrors`, `interfaceDeclaration1`, `methodSignatureHandledDeclarationKindForSymbol`, `reassignStaticProp`, `dynamicNamesErrors` |
| 938.2 | both duplicate SCANS learn the computed namer | **PRISTINE-CONFIRMED, ACTIVE** | `duplicateIdentifierComputedName`: TS2300 `'["a"]'` |
| 938.3 | `memberNameIsBinderVisible` — a LATE-BOUND duplicate is TS2717 alone | **PRISTINE-CONFIRMED, ACTIVE** | `dynamicNamesErrors`; this is the row that made round 938 |
| 938.4 | the class scan's `group.drop(1)` (the SECOND declaration only) | **PRISTINE-CONFIRMED, ACTIVE ×2** | `classWithDuplicateIdentifier`, `duplicateIdentifierComputedName` |
| 938.5 | B357 retracts its duplicate TS2717 | **PRISTINE-CONFIRMED, ACTIVE** | `dynamicNamesErrors` (arm A7) |
| 938.6 | a WELL-KNOWN-symbol key stays invisible to duplicate detection | **PRISTINE-CONFIRMED as a deliberate FN** | `uniqueSymbolsPropertyNames`: pristine emits TS1117 ×2 + TS2300 ×5 there and we emit none — the refusal's price, now measured rather than assumed |

- **AND THE STRONGEST EVIDENCE IS NOT IN THE TABLE, BECAUSE IT IS A NEGATIVE: OUR BINARY WAS
  RUN OVER EVERY UNGATED PRISTINE FIXTURE THAT CARRIES A COMPUTED MEMBER KEY.** `--extract`
  materialises pristine's own input; the sweep ran `MainKt --noEmit --listAll` on **300** of
  them (326 stems, 26 with no recoverable source) and differenced (line, code) against
  pristine's baseline. **277 of 300 emit NOTHING pristine does not.** All 23 that do were
  read: **four are the pre-existing divergences below, and NOT ONE of the remaining nineteen
  is attributable to rounds 933-938** — they are checks this compiler does not implement, in
  other families entirely (`using` declarations and the private-modifier grammar, which we do
  not parse; index-signature PARAMETER types, which is (CHK.5)(e)'s own axis; super-call
  ordering; a `declare global { interface SymbolConstructor }` augmentation that does not
  merge; `Symbol.hasInstance` narrowing; a discriminant union with a `never` member; and
  module resolution in a multi-file extraction). Everywhere else we emit FEWER diagnostics —
  TS2464 / TS2564 / TS2699 / TS2804 / TS2411 / TS2454 — and where we do emit, the positions
  match pristine exactly.
- **FOUR PRISTINE DIVERGENCES *WERE* FOUND, AND ALL FOUR ARE OLDER THAN THIS FAMILY. THE
  PROOF IS THE DIFF, NOT AN OPINION**: `git diff 0d38189f..HEAD` (the pre-933 parent) over
  `Checker.kt` mentions `getPropertyName` / `getPropertyKeyName` / `evaluateComputedPropertyName` /
  `checkObjectLiteralDuplicates` **zero times**, and `PrivateIdentifier` zero times. They are
  queued as (CHK.7) with the fixture that shows each.
- **THE ONE WORTH READING IS THE TS1117 NAMER, BECAUSE ITS NEGATIVE CONTROL IS VACUOUS.**
  `evaluateComputedPropertyName` names an identifier key `__@computed:<text>` — a SPELLING,
  not a value — so two occurrences of the same DYNAMIC key are a duplicate to us and not to
  pristine: `var s: symbol; var x = { [s]: 0, [s]() {}, get [s]() {} }` (`symbolProperty1`,
  `symbolProperty3`) is **TS1117 ×2, a false positive**. The corpus cannot see it: the three
  `duplicateObjectLiteralProperty_computedName*` fixtures ARE active gates and we pass them,
  and pristine's own negative-control fixture for this shape
  (`duplicateObjectLiteralProperty_computedNameNegative1`) uses **two different** identifiers
  (`[x]`, `[y]`), so it is satisfied by a spelling key as well as by a value key. Same namer,
  opposite direction, is round 938's recorded gap (b2)(iii).
- **METHOD NOTE, PAID FOR TWICE: A FIXED SCRATCH `tsconfig` MANUFACTURES FALSE POSITIVES.**
  `uniqueSymbols` read two OURS-ONLY rows at `target: es2015` and one of them vanished at
  `esnext` — the missing `AsyncIterableIterator` (TS2583) cascading into a TS2322. Every
  OURS-ONLY row in this round was re-read at a second target before being believed, and the
  pristine case's own `// @target` directive is the thing a next sweep should honour.
- **GATES — NAMED AS NOT APPLICABLE RATHER THAN REPORTED GREEN.** This round adds one Python
  script and documentation; **no Kotlin changed**, so there is nothing to compile, nothing for
  `cost_gate.py` to count, no method to grow past 8,000 bytecodes and no binary to grid. The
  suite therefore stands at round 938's **15,168 / 0 failures / 3 skipped** — the tree is
  byte-identical to `022cdd42` in every compiled module. What WAS exercised is the compiled
  binary at HEAD, **300 times**, against 300 pristine fixtures.
- **NEXT.** `(CHK.5)` continues at **(c)**, unchanged. Two entries were added by this round:
  **(CHK.5)(f)** — the TS2741 key name — and **(CHK.7)**, four pre-existing pristine
  divergences with a fixture apiece.

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

**TOP OF QUEUE ON OWNER DIRECTIVE (2026-08-21): (BENCH.1) below runs before the (API.\*) arc
resumes.**

- [ ] **(KIR.PERF.1) THE NOMINAL HALF — NOW THE *ONLY* DIRECTION LEFT FOR THE BAG, AND
  ITS CASE IS MADE BY TWO REFUTATIONS RATHER THAN BY THE DESIGN DOC.** A per-owner leaf
  census of the toml JVM arm (`scripts/kir-profile.sh`) charges **44.3%** to the property
  bag — `JsObject.set` **25.6%**, `get` **17.3%**, `has` 1.4% — so a free property access
  is worth roughly **−44% at the limit**, which would put `smol-toml` at ~1.2x Node
  instead of 2.07x. Nothing else in the profile is above 20%.

  **WHAT IS CLOSED.** Making the dynamic representation cheaper. Two independent attempts:
  parallel arrays promoted by SIZE (**+21%**) and parallel arrays promoted at the first
  UNDECLARED key (**+31%**, this round). The second is the informative one because its rule
  worked — `HashMap` fell from 38.3% of samples to 4.7%, so the dictionary half really was
  left alone — and the saving still did not exist: in SAMPLES rather than shares the bag
  cost 709 before and 771 after. **A `LinkedHashMap` probe on an interned key is already
  about as cheap as a three-element scan**, and the rest of that regression landed on
  `program.*` and regex frames, which is what a bigger, two-shaped `get`/`set` does to the
  callers it used to be inlined into. So the bag is expensive in the NUMBER of operations,
  not their unit cost, and only removing operations helps.

  **THE OBLIGATION TypeScript IMPOSES, unchanged:** assignability is STRUCTURAL, so a
  nominal encoding needs a witness per declared shape plus generated implementations, with
  a bag still reachable for `any`, for an index signature, and for a shape the closure
  cannot name. `docs/kir-structural-typing.md` §7 prices the nominal half at 12x the
  dynamic one.

  **THE ONE INTERMEDIATE THE TWO REFUTATIONS DO NOT RULE OUT, worked out this round and
  NOT built:** keep `JsObject` as the JVM type at every slot — so there is no static-type
  risk at all and the fallback is always correct — and add a *guarded slot hint*:
  `getAt(name, hint)` = `if (names[hint] === name) slots[hint] else get(name)`, five
  bytecodes on the hot path, with the hint being the member's index in the RECEIVER's
  declared type. It differs from the refuted attempt in exactly the two ways that attempt
  failed: the fast path is O(1) rather than a scan, and it is small enough to inline. Its
  cost is that it needs the shaped representation back (so it is graded by the same
  `KirPropertyBagTest`) plus the declared MEMBER ORDER of an object type reaching the
  lowering, which `CheckedFacts` does not expose today — and `Type.Object.members` is
  LAZY, so a new reader must resolve it first (CLAUDE.md). A hint that is wrong is slow,
  never incorrect. **Measure it with `scripts/kir-bench.sh` and refuse it on the same
  standard as the other two: ranges disjoint, both Node arms flat.**

- [ ] **(KIR.EMIT.1) OUR ESM OUTPUT IS NOT RUNNABLE ON NODE AS EMITTED — a relative
  specifier keeps the extension it was written with.** tsgo 7.0.2 rewrites `./parse.ts` ->
  `./parse.js` under `rewriteRelativeImportExtensions` and we emit `'./parse.ts'` verbatim;
  Node ESM resolves a specifier LITERALLY and refuses both that and mitt's extensionless
  `'./mitt'`. `scripts/kir-bench.sh` post-processes the emit to run the arm at all, which is
  a benchmark expedient and NOT a fix. **Invisible to every gate we own** — the corpus pins
  emitted BYTES against tsc baselines, and no baseline asks whether Node can load the result.

- [ ] **(KIR.EMIT.2) `undefined` RENDERS AS `"null"` IN A STRING CONCATENATION.**
  `a + '|' + b` with `b` undefined prints `x|null` where JavaScript prints `x|undefined` —
  a `string | undefined` erases to `String?` and Kotlin's own `plus` renders the null. Found
  by `KirDynamicCallArityTest`, which was retargeted to avoid pinning it; the fix belongs in
  the concatenation lowering, not in the call path.

- [x] **(BENCH.1) THE THIRD JS ARM — ANSWERED 2026-08-21: the arm lands ON tsgo's (1.01x /
  1.02x), so the front end is performance-neutral and the whole 2.5x is the BACKEND. The
  harness is `scripts/kir-bench.sh` and the arm is now the standing control.** ORIGINAL ENTRY:
  THE THIRD JS ARM — OUR OWN EMITTED JavaScript, ON THE SAME NODE, AS THE CONTROL
  THAT SEPARATES "OUR COMPILER" FROM "OUR BACKEND".** The 2026-08-21 KIR runtime benchmark measured
  two arms — tsgo -> JS -> Node against xtsc `-kir` -> JVM bytecode -> java — and they disagree by
  library and by SIGN: **mitt 86.0 -> 66.5 ns/emit (JVM 1.29x FASTER), smol-toml 22.6 -> 56.4
  us/parse (JVM 2.50x SLOWER)**, medians of 5 interleaved processes, both arms producing identical
  `sink` accumulators and byte-identical acceptance output. **Two candidate causes are tangled in
  that 2.50x and no arm separates them**: the code our FRONT END produces, and the KIR backend's
  object model. The third arm holds the runtime fixed (Node) and varies only the compiler —
  `-core`'s Transformer/Emitter to JavaScript text, against tsgo's JavaScript, same sources, same
  drivers.

  **What each outcome MEANS, stated before the run (a prediction is what makes a refutation
  legible).** Arm 3 landing on arm 1 says the front end is performance-neutral and the whole 2.50x
  belongs to the backend, confirming the leaf profile by a second instrument rather than by
  inference. Arm 3 landing SLOWER than arm 1 is a genuinely new finding about our JS emitter and
  invisible to every gate we own — **the corpus pins emitted BYTES against tsc's baselines, and byte
  parity says nothing about how fast the resulting program runs on a modern JIT.**

  **The harness exists and is reusable** — drivers, projects, timing shape and the interleaved
  5-process protocol are in the 2026-08-21 session note; the only new piece is emitting the two
  bench projects with `-core` instead of tsgo. **Two traps it must carry.** (i) Node ESM needs a
  real extension: tsgo rewrites `./parse.ts` -> `./parse.js` under
  `rewriteRelativeImportExtensions` and leaves mitt's extensionless `./mitt` alone, so whatever our
  emitter does with a specifier has to be checked rather than assumed. (ii) **An arm that fails to
  RUN must fail loudly** — a JS file that throws on import prints nothing and a wall-clock harness
  reads that as a fast arm; assert the acceptance output byte-for-byte in every arm before timing
  anything, which is what caught nothing this round only because it was done first.

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

- [x] **(API.13) § 14 AUDITED BY EXECUTION AND PINNED — LANDED, round 930; four of its
  claims were false and one of them was a DEFECT.** `docs/language-service.md` § 14 is the
  page a host author and a next agent read instead of twenty session notes, and it was
  three rounds old with a fixed defect still listed as open. Every claim in it was re-run
  — a fixture through the API, `tsc --lsp -stdio` as the oracle where the claim is parity,
  the cost table re-taken on the compiler profile — and the half that a test can defend is
  now `LanguageServiceStateTest` (+15 pins). **THE ONE DEFECT: `definitionsAt` on a
  `super.p` member answered NOTHING** while `quickInfoAt` at the same caret answered
  correctly — § 9's own table and § 14's maturity row both promised the base's declaration
  — because the receiver leg carried a `this` carrier and no `super` one. Fixed (8 lines,
  mirroring `typeCaptureThisMemberType`'s existing super branch) and measured against tsc,
  which navigates to `Base.pb` in the overridden shape and `Base.mb` in the inherited one.
  **THREE CORRECTIONS**: an enum member's declaration name does not "report nothing", it
  reports **`any`** (below, and still open); an object literal's own method
  "refuses a rename loudly" only once a CONTEXTUAL TYPE supplies it — with none it
  **renames completely** from either end, which the correction had in turn to be measured
  to find; a computed key is
  not silently missed, it is **reported in two of its three shapes** and silent only where
  the contextual member is optional. **ONE CLAIM CONFIRMED THE HARD WAY**: a template
  element access really is silent — the rename applies, the template keeps the old name,
  and the resulting program compiles clean. **THE COST TABLE'S BUILD COLUMN IS NOW PINNED
  and its wall column is marked not pinnable**, with `scripts/round930-ls-cost.sh` +
  `LanguageServiceCostMain` as the re-take (one process, one project, three rotations —
  the only comparison CLAUDE.md admits). Re-taken: rebuild 5.0–5.5 s (§ 3 said ~5.2, § 14
  said 5.5–5.9 — both drifted, in opposite directions), highlights 6.3 s on `checker.ts`
  and 5.0–5.5 s on `types.ts` (the row is a statement about a FILE, which is why it looked
  wrong), references 8.3–10.2 clean / 13.2–14.8 dirty, rename 14.3 s (`createTypeChecker`)
  – 21.0 s (`SyntaxKind`). `scripts/lsp_definition.py` is new, the fourth oracle.
  Suite 14,981 → 14,996 / 0 failures / 3 skipped; `cost_gate.py` +0.00% on all 20
  counters; `huge_methods.py --fail-over 0` clean on both modules; the round-920 token
  gate re-run (1,327 files, 101,287,620 chars, zero violations — which is § 14's own
  "101 M characters" claim, verified).

- [ ] **(CHK.5) COMPUTED KEYS — STAGES (a) AND (b) ARE LANDED (rounds 937/938); (c), (d),
  THE INDEX-SIGNATURE AXIS AND FIVE NEWLY MEASURED DUPLICATE GAPS REMAIN.**
  **(a) THE MEMBER-BUILDING SITES — DONE, round 937.** `interface I { [K]: number }`,
  `class C { [K]: number }` and `type T = { [K]: number }` now declare the member, in the
  property, method, get- and set-accessor forms, for every key spelling round 935/936
  resolves. It was NOT one site: six had to be levelled onto one namer, and two of them
  (`checkImplementsClauses`, `classMemberNamesTransitive`) compare a class's AST names to
  a target built from the resolved TYPE, so levelling the type side made a PRE-EXISTING
  Identifier-only drift reachable — two false positives with no computed key in them
  (`interface I { 1: string }` + `class C implements I { 1: string }`, and the same through
  a `static 1`) were closed as part of it. `checkComputedLiteralKeyMembers` now retracts
  before it emits, because the general relation reaches its TS2322 verdict once the key
  binds. Session note has the 40-row table and the 10-arm ablation.
  **(b) A DUPLICATE MEMBER DECLARATION — DONE, round 938, and it corrected its own
  premise.** This compiler ALREADY emitted TS2300 x2 + TS2717 for a plain
  `interface I { p: number; p: string }`, byte-identical to tsc, and for a type literal, a
  class, an enum, two getters, a numeric name and a class property-vs-method. Two things
  were wrong and both are closed: the member map was LAST-WINS where tsc keeps the FIRST
  (eight measured rows, including round 937's spurious TS2322, which was this defect and
  not a computed-key one), and neither duplicate SCAN could name a computed key — the class
  one knew `["a"]`/`[0]`, the interface one had no computed arm at all. Both now ask one
  namer. **The rule that decides the diagnostic came from a PRISTINE baseline, not from
  tsgo**: TS2300/TS2687 are the BINDER's checks and a LATE-BOUND key never reaches them
  (`dynamicNamesErrors` — `interface T0 { [c0]: number; 1: number }` gets NOTHING, `T3` gets
  TS2717 alone), where tsc 7.0.2 emits TS2300 for both; following tsgo reddens that corpus
  test. Same parting on the class `drop(1)` rule. `checkComputedLiteralKeyMembers` now
  retracts before it emits. Session note has the 21-row table and the 9-arm ablation.
  **(b2) NEW — FIVE DUPLICATE GAPS MEASURED IN ROUND 938 WITH tsc's ANSWER, EACH SMALL AND
  EACH SEPARATE.** (i) a MERGED-interface TS2717 — `interface I { p: number }` +
  `interface I { p: string }` is TS2717 at the second in tsc and silent here, because both
  duplicate scans are per-DECLARATION by construction (the first-wins TYPE is already
  right); (ii) an INTERFACE property-vs-METHOD pair is TS2300 x2 in tsc and silent here —
  `checkDuplicateInterfaceMembers` collects `PropertyDeclaration`s only, where its class
  twin collects four kinds; (iii) TS1117 for a late-bound OBJECT-LITERAL key
  (`{ p: 1, [K]: 2 }`) — `getPropertyKeyName`/`evaluateComputedPropertyName` is a THIRD
  namer with its own `__@computed:` scheme and its own numeric normalization, so widening
  it is not the one-line delegation the other two were; (iv) the required-vs-OPTIONAL
  TS2717 (`p: number; p?: number` — tsc says `number | undefined`); (v) **`C.p` reads the
  INSTANCE member's type when a static and an instance member share a name** — that is the
  unfinished `staticMembers` dual-population ("no behavior change yet" in
  `resolveInterfaceMembersCore`), not a duplicate rule, and it is the one of the five that
  is a WRONG TYPE rather than a missing diagnostic.
  **(c) A CONST IMPORTED FROM ANOTHER FILE, AND A CLASS `static readonly` KEY.**
  `import { IK } from "./k"; interface I { [IK]: number }` and `[C.B]` where
  `class C { static readonly B = "p" }`: both bind in tsc, both are still a false positive
  here (measured again round 937, on the DECLARATION side as well as the literal one). The
  syntactic walk cannot cross a file by construction; the route is the frozen binder tables
  (`resolveAlias`), which are deterministic and therefore allowed under round 935's law.
  **(d) THE `unique symbol` TYPE — unchanged, and round 937 CONFIRMED why it cannot land
  alone.** `declare const S: unique symbol` types as plain `symbol` here, so `[S]` and
  `[S2]` are ONE name. Round 936 predicted that naming the key on the literal side alone
  would invert the defect; round 937 measured the SAME inversion already live for a plain
  const (`const x: I = { [K]: 1 }` was TS2353 `'[K]'`, a false positive) and closed it by
  landing both sides together. (d) needs a `unique symbol` type keyed by the DECLARATION
  (tsc's `__@<desc>@<id>`, a name that survives a rename and an import) and both sides in
  ONE commit.
  **(e) NEW — THE INDEX-SIGNATURE AXIS, measured round 937 and belonging to neither (a) nor
  (d).** A computed key whose type is `string` (`let LW = "p"`), a literal UNION, or a
  dotted path through a VALUE (`obj.k`) gives tsc's interface, class and type literal a
  STRING INDEX SIGNATURE rather than a named member — `interface I { [LW]: number }` makes
  `i.p` a `number` in tsc, and `class C { [LW]: number }` likewise, where `c.p` is still
  **TS2339, a false positive** here. Late binding must keep REFUSING these keys; closing
  them is index-signature modelling. Round 936's `{ [L]: number; }`-vs-`{}` display row is
  the same gap seen from the display side.
  **(f) DONE, round 940 — THE TS2741 KEY NAME, the family's ONE measured PRISTINE divergence (round 939).**
  For a missing late-bound member we print `Property 'p' is missing in type '{}' but required
  in type 'I'` where tsc prints `'[K]'`. **Pristine names the key AS WRITTEN wherever it names
  one** — `'[E.A]'` (`assignmentCompatWithEnumIndexer`), `'["a"]'`
  (`duplicateIdentifierComputedName`, an ACTIVE gate), `'[c1]'` (`dynamicNamesErrors`, ACTIVE),
  `'[Symbol.toPrimitive]'` (`symbolProperty21`) — so pristine and tsgo AGREE here and we are
  the outlier. Round 937 recorded it against tsgo; round 939 confirmed the convention against
  pristine and verified our answer live at HEAD. No baseline covers the exact shape
  (`const K = "p"; interface I { [K]: number }; const x: I = {}`), which is why the suite is
  green. **LANDED round 940** at [formatPropertyDisplayName] — the ONE renderer the
  missing-property emitters already route the symbol through, so all twelve of its callers
  moved together — asking round 938's `computedKeyWrittenText`, which answers null for a
  spelling it cannot reproduce exactly. Pinned three ways (`[K]`, `[E.A]`, `["a"]`) with
  the negative controls that a NON-computed member keeps its bare name and a quoted string
  member keeps B291's quoted display; ablation arm A5 reddens exactly the three.
  **WHAT MUST NOT BE UNDONE**: the WELL-KNOWN-symbol route is deliberately not
  `computedSymbolKey` in general (tsc is SILENT for every computed key it cannot late-bind,
  measured over seven of them), and `getMemberName` itself stays unchanged — B451 records
  it as feeding ~20 callers including duplicate detection and abstract tracking, so the
  widening lives in `declaredMemberName` at the member-BUILDING call sites.

- [x] **(CHK.6) THE COMPUTED-KEY FAMILY RE-JUDGED AGAINST *PRISTINE* — DONE, round 939, and
  the verdict is that rounds 933-938 landed NOTHING pristine contradicts.** Rounds 933-937
  established their ground truth by running `tools/tsgo-7.0.2/lib/tsc`, the only reference
  compiler that RUNS on this box; round 938 then found the two references parting on this
  family's own territory, which left every row no corpus baseline covers resting on an oracle
  this project deliberately does not follow. The pristine oracle turned out to be on disk all
  along — `typescript-repo/tests/baselines/reference`, generated by the pinned pristine commit
  — and is now `scripts/pristine_oracle.py` (`--code` / `--pattern` / `--fixture`, every hit
  labelled ACTIVE vs not-generated, plus `--extract DIR`, which writes pristine's own input
  back out so our binary can be run over exactly what pristine saw). **34 landed decisions
  classified: 22 PRISTINE-CONFIRMED, 10 CORPUS-SILENT, 1 tsgo-ONLY, 1 PRISTINE-DIVERGENT** —
  the TS2741 key name, a message FORM round 937 had already recorded, now (CHK.5)(f).
  **The corpus protects much more of this family than the notes claimed**: `dynamicNames`,
  `dynamicNamesErrors`, `duplicateIdentifierComputedName`,
  `destructuredLateBoundNameHasCorrectTypes`, `checkDestructuringShorthandAssigment2`, the
  three `duplicateObjectLiteralProperty_computedName*` and **7 of the 10 TS2717 baselines in
  the whole corpus** are ACTIVE byte-exact gates sitting on these exact decisions.
  **And the strongest evidence is a negative**: `--extract` materialises pristine's own input,
  so our binary was run over **300** ungated pristine fixtures carrying a computed member key
  and differenced (line, code) against pristine's baseline. **277 of 300 emit nothing pristine
  does not**; of the 23 that do, four are (CHK.7) and NOT ONE of the other nineteen is
  attributable to rounds 933-938 — they are unimplemented checks in other families (`using`
  declarations, the private-modifier grammar, index-signature PARAMETER types, super-call
  ordering, a `declare global { interface SymbolConstructor }` that does not merge,
  `Symbol.hasInstance` narrowing, a `never` discriminant, module resolution). The four that
  ARE pristine divergences are older than the family, proved by the diff rather than argued.

- [x] **(CHK.7)(i) AND (iii) — LANDED, round 940, both FALSE POSITIVES, both CLOSED; (ii)
  AND (iv) RE-MEASURED AND RE-QUEUED BELOW, because round 939's entry was wrong about both
  in the direction that decides what to build.** (i) TS1117 was keyed on a computed key's
  SPELLING, so `var s: symbol; ({ [s]: 0, [s]() {}, get [s]() {} })` was TS1117 x2 here and
  silent in `symbolProperty1`/`2`/`3`; the namer now abstains — but ONLY when the key's own
  declaration is IN HAND and late binding still refused it, because a blanket abstain
  regresses `duplicateObjectLiteralProperty_computedName3` (an ACTIVE gate whose keys arrive
  through an `import * as keys`, which pristine binds by TYPE and round 935's syntactic
  resolver cannot follow across a file). (iii) An accessor followed by a PROPERTY is TS2300
  at the property alone — tsc's `PropertyExcludes = None` means a property declared last
  never trips the binder's duplicate check — which reproduces all 83 of
  `privateNameDuplicateField`'s rows and both halves of `duplicateClassElements`.
  **Measured: `privateNameDuplicateField` 3 ours-only rows -> 0; the 630-fixture pristine
  sweep 403 -> 397 ours-only rows with ZERO fixtures regressed; the 8-profile grid
  added=0 removed=0; suite 15,168 -> 15,193 with no baseline moved.**

- [x] **(CHK.8) — THE 630-FIXTURE PRISTINE SWEEP, TRIAGED AND ITS INSTRUMENT REPAIRED;
  TWO FALSE-POSITIVE FAMILIES CLOSED (round 941).** `scripts/pristine_sweep.py` supersedes
  round 940's sweep and **121 of that round's 397 OURS-ONLY rows (30.5%) were the
  instrument's own configuration**: the case-file fallback carried the `// @target:`
  directives tsc STRIPS (a whole-file line shift, 27 fixtures); directives were read from
  the EXTRACTED text, which the `.js` baseline echoes WITHOUT them; and a missing case file
  left no target where the baseline's `(target=…)` suffix still records it. An ALIGNMENT
  ORACLE (each reconstructed input compared line-for-line against pristine's `==== file ====`
  annotation) now makes the first defect impossible to reintroduce silently. **The triage of
  the remaining 334 rows is `docs/pristine-divergences.md` and its cause-class rules are
  `scripts/pristine_triage.py`** — genuine FP 182 (48.8%) / cascade 90 / harness 59 /
  deliberate convention 42. Closed this round: TS2376 (a `super` call need not be FIRST —
  tsc walks the statement list to the first IMMEDIATE `this`/`super` reference, stopping at
  arrows, function declarations/expressions, property declarations and method-like BODIES
  but NOT at their computed NAMES) and TS18028 (the private-identifier gate reads the target
  the user ASKED FOR, not the raw `ES3` default). Sweep **373 -> 334**, zero fixtures
  regressed, pristine-only 777 -> 776 (a true positive GAINED); 8-profile grid added=0
  removed=0 on all eight; suite 15,193 -> 15,214 with no baseline moved.

- [x] **(CHK.9) INDEX-SIGNATURE PARAMETER TYPES — 12 OURS-ONLY TS1268 ROWS -> 0, AND TWO
  TRUE POSITIVES GAINED (`indexSignatures1`, round 945).** tsc's rule, read off the pinned
  sources (`checkGrammarIndexSignatureParameters` + `isValidIndexKeyType`), has three parts we
  had two of. **The intersection arm was missing entirely**, so every BRANDED string
  (`type Id = string & { __tag: 'id' }` — the shape the rule exists for) was TS1268, and an
  `IntersectionType` NODE was not even offered to the type engine, so a syntactic
  `` `${string}xxx${string}` & `${string}yyy${string}` `` never got a verdict either. **And the
  generic test read only a bare `TypeReference`**, which is why `[key: T | number]` and
  `[key: T & string]` were TS1268 where pristine says TS1337 — the cause being that an alias's
  own `T` resolves to `anyType` at that grammar check, so the question has to be asked of the
  AST. Note `someType`/`everyType` distribute over UNIONS only: an intersection is valid when
  SOME constituent is (`string & 'a'` is a legal key), and reading that as `every` is the
  round's B4 arm. Measured: sweep **310 -> 298** ours-only with 0 added, pristine-only
  **775 -> 773**, zero fixtures regressed; 8-profile grid `added=0 removed=0`.

- [ ] **(CHK.10) DEFINITE ASSIGNMENT THROUGH A LATE-BOUND ELEMENT ACCESS — 4 OURS-ONLY
  TS2564 ROWS (`strictPropertyInitialization`, ALIGNED, round 941).** `class C12 { [a]: number;
  [b]: number; ['c']: number; constructor() { this[a] = 1; this[b] = 1; this['c'] = 1 } }`
  with `const a = 'a'; const b = Symbol()`: pristine sees the definite assignment through the
  ELEMENT ACCESS and is silent, we report `Property '…' has no initializer`. Same fixture
  reports `[E.A]` (an enum member key). Small, and squarely in the computed-key arc's own
  family — note that the triage classifier exempts this fixture by name from the
  strict-by-default bucket for exactly this reason. **CONFIRMED GENUINE, round 943**: that
  fixture's case file is not in this clone, so the sweep recovers no directives for it — but
  its own baseline carries **20 TS2564**, i.e. pristine had `strictPropertyInitialization`
  ON, so these four rows are not the convention. (The `--tsc-strict-default` arm deleted them
  until it was guarded on case-file presence; see `docs/pristine-divergences.md` § 0b.)

- [x] **(CHK.11) ELEMENT-ACCESS DISCRIMINANT NARROWING — 11 OURS-ONLY ROWS -> 0
  (`typeGuardNarrowsIndexedAccessOfKnownProperty1`, round 942).** The cause is one sentence:
  **tsc's `isMatchingReference` compares references by SYMBOL and ours compares the path
  STRINGS `getReferencePath` builds**, and every discriminant reader was written against the
  DOTTED spelling alone. FOUR mechanisms, all measured: `singleLevelDiscriminantSegment` (the
  switch reader accepts `name[seg]`); `getTypeOfElementAccess` flow-narrows its UNION
  RECEIVER (B1.1's gate, which its dotted twin has always had); `getReferencePath`
  NORMALISES an identifier-spellable string index onto the dotted segment, because the
  fixture mixes both spellings inside one expression (`s[0]["sub"].under["shape"]`); and
  `requiredEnumSwitchKeys` + `paramMemberChainType` accept an element-access discriminant and
  a multi-segment receiver, which is the two TS2366. **A FIFTH — the 17.34d half, narrowing
  the access's own union RESULT — was written, measured INERT (its ablation arm reddened NONE
  of the 21 pins and no probe could be built where it fires) and REMOVED.** **Measured: 11 -> 0, sweep 334 -> 318 with zero fixtures regressed, 8-profile grid
  added=0 removed=0.** `docs/pristine-divergences.md` § 3.4.

- [x] **(CHK.12) `[Symbol.hasInstance]` NARROWING — 5 OURS-ONLY ROWS -> 0, AND THE ENTRY WAS
  WRONG ABOUT ITS OWN SECOND FIXTURE (round 942).** `instanceof` now asks the RHS type for a
  `[Symbol.hasInstance]` method whose return is a non-`asserts` TYPE PREDICATE over parameter
  0 and uses its target — round 838's `instanceTypeOfConstructorValue` named that leg as its
  one deliberate omission — which answers the three shapes `prototype` and the construct
  signatures cannot: a GENERIC construct signature, SEVERAL construct signatures, and one
  returning `any`. **Two rules read off PRISTINE's baseline and re-read off tsgo 7.0.2: a
  usable predicate DECIDES (a `value is any` target narrows NOTHING and must not fall through
  — pristine's own lines 142/143), and an `instanceof` stays `checkDerived = true` even when
  the candidate came from a predicate, so a UNION candidate is DISTRIBUTED and its
  narrow-down direction is the NOMINAL base-chain test (`C1 | A` narrowed by `C1 | C2` is
  `C1`), scoped to a union candidate so round 425's single-candidate arm is byte-identical.**
  Measured: 5 -> 0 with pristine-only 8 -> 7, i.e. a true positive GAINED.
  **The entry's other fixture is MIS-BUCKETED**: `controlFlowInstanceofWithSymbolHasInstance`
  is 7 rows of which **6 are a PARSER GAP** (`abstract new (...) => infer U`), queued as
  (CHK.14), and 1 is the `instanceof` intersection tail, queued as (CHK.15). Out of scope by
  construction: a `static [Symbol.hasInstance]` on a CLASS declaration, which
  `resolveInstanceOfRhsType` answers from the declared type before the leg is reached.
  `docs/pristine-divergences.md` § 3.5.

- [x] **(CHK.14) `abstract new (…) => T` AND THE CONSTRUCTOR-TYPE `infer` — CLOSED round 947,
  15 ours-only rows (297 -> 282), PRISTINE-ONLY FLAT at 769, zero fixtures regressed.**
  `docs/pristine-divergences.md` § 3f. **This entry's own second half was diagnosed
  backwards and the correction is the round's product**: the defect is NOT "an `infer`
  inside a PARENTHESIZED extends clause does not publish its name" — parentheses are
  irrelevant (`collectInferTypeNames` recurses through `ParenthesizedType` and always has),
  the missing arm was **`ConstructorType`**, and the UNPARENTHESIZED spelling
  `T extends new () => infer U ? U : never` failed identically while the parenthesized
  FUNCTION-type spelling always worked. It is also not a parser item: it is a one-arm gap in
  the INV.4(c)(iii) scope walker, whose sibling `collectInferDecls` carries the arm with a
  comment about keeping parity with it. Landed alongside it: `parsePrimaryType`'s
  `abstract`-then-`new` lookahead (tsc's `isStartOfFunctionTypeOrConstructorType` +
  `parseModifiersForConstructorType`), whose SPAN bound is pinned in `-project` because no
  core diagnostic reads a `ConstructorType`'s `pos`. Held as false NEGATIVES on purpose: the
  `infer` still does not RESOLVE through a constructor type (`D<new () => K>` answers `any`),
  and the recorded `modifiers` set is read by nothing — TS2511 is its named future consumer.

- [x] **(CHK.25) `using` / `await using` DECLARATIONS DID NOT PARSE — 33 OURS-ONLY ROWS OVER
  FOUR FIXTURES, THE LARGEST SINGLE CASCADE IN THE WHOLE PRISTINE POPULATION. LANDED round
  948: ours-only **282 -> 251** over 74 -> 71 fixtures, pristine-only **769 -> 767** (two
  TS2353 GAINED), zero fixtures regressed, zero corpus baselines moved.** `using x = expr;`
  reported TS1434 at the `using` and then TS2304 for every name the failed statement never
  bound. **The representation is tsc's own and needed no new node**: a
  `VariableDeclarationList`'s `flags` field already IS the head token, so `using` is
  `SyntaxKind.UsingKeyword` — no `forEachChild` arm, no `NodeKind`, no binder arm, because the
  binder's `isVar` test already reads any non-`var` head as block-scoped. `await using` is two
  tokens collapsed onto a synthetic `SyntaxKind.AwaitUsingKeyword` the scanner never produces.
  **The whole risk was the CONTEXTUAL KEYWORD and it did NOT materialise anywhere**: the eight
  profiles carry 336 occurrences of `using` as an identifier / property name and zero
  declarations, and the binary grid is byte-identical on all eight. Landed with the grammar
  rules (TS1155 / TS1492 / TS1493 / TS1494 / TS1491 / TS1495), the disposability rule
  (TS2850 / TS2851, positive-evidence-only and switched off unless the lib declares
  `Disposable`), and a VERBATIM emit of the head. `docs/pristine-divergences.md` § 3g.

- [ ] **(CHK.26) `infer U extends T` FOLLOWED BY A CONDITIONAL `?` IS PARSED AS A CONSTRAINED
  INFER WHERE tsc PARSES A CONDITIONAL — 8 OURS-ONLY ROWS, `inferTypesWithExtends1` lines 95 /
  103 / 105 (sub-triaged round 947, § 2.3 P2).** **`infer X extends` itself ALREADY PARSES**
  and has for as long as `parseTypeParameter` has handled a constraint — round 941's label for
  this bucket named the wrong thing. What fails is the DISAMBIGUATION: tsc's
  `tryParseConstraintOfInferType` parses `extends <type>` with conditional types DISALLOWED
  and rolls the whole `extends` back when the next token is `?`, **unless it is already in a
  disallow-conditional context** — so `T extends (infer U extends number ? 1 : 0) ? 1 : 0` is
  a conditional inside the parens (pristine's own comment on the line says *"ok, parsed as
  conditional"*) while `T extends infer U extends string ? U : never` keeps its constraint.
  We take the constraint unconditionally and cascade TS1005 / TS1109 / TS1128. **The rollback
  alone is NOT the fix and would break the second shape**: it needs the
  `disallowConditionalTypes` CONTEXT threaded through `parseType`'s conditional production
  (`extendsType` and a mapped type's `nameType` set it; a parenthesized type clears it) — an
  edit to the production the frozen-subsystem warning is about, which is why round 947 scoped
  it out rather than attempting it beside a landing change. `scanner.tryScan` is already the
  rollback primitive (`tryParseTypeParameters` is the reference shape). Pinned SILENT-side by
  `AbstractConstructorTypeTest.scoped out - an infer constraint is not re-read as the
  enclosing conditional`, which asserts today's TS1005 so the fix has to move it.

- [ ] **(CHK.27) THE `using` FALSE NEGATIVES ROUND 948 LEFT BEHIND — ALL FOUR ARE FEATURES
  THIS COMPILER SIMPLY DOES NOT HAVE, AND NONE COSTS AN OURS-ONLY ROW.** (i) **The DOWNLEVEL
  EMIT.** The head is emitted VERBATIM, which is tsc's own output only at a target with
  explicit resource management (>= ESNext); below it tsc rewrites the block through
  `__addDisposableResource` / `__disposeResources`, and the ~439 `usingDeclarations*` baselines
  upstream are mostly `(module=…,target=…)` variations of exactly that. Verbatim is the SAFE
  half of the choice — rewriting the head to `var` would silently delete the disposal — but a
  low target now emits a `using` a downlevel runtime cannot execute. **This clone carries no
  `using` case file, so the generated corpus still gates none of it**; an emit landing needs
  its own gate (`--outDir` + `diff -r`, since `--noEmit` makes every instrument here blind to
  transform/emit). (ii) **`declare using` — TS1545 `'using' declarations are not allowed in
  ambient contexts.`** (and TS1546); it needs an arm in `parseDeclareDeclaration`, which
  round 948 did not touch, so `declare using x: T;` still cascades. (iii) **The `case` /
  `default`-clause rule, TS1547 / TS1548**, which tsc decides from `declarationList.parent
  .parent` being a clause. (iv) **The `await using` CONTEXT rules — TS2852 / TS2853 / TS2854 and
  TS18054**; a top-level `await using` in a non-module file, or one inside a class static
  block, is silent today. Also unreproduced: TS2850's nested
  `Property '[Symbol.dispose]' is missing …` elaboration and its TS2728 related info.

- [ ] **(CHK.28) A DECORATED CLASS *EXPRESSION* IN AN INITIALIZER IS REFUSED — TS1206
  `Decorators are not valid here.`, 2 OURS-ONLY ROWS
  (`usingDeclarationsNamedEvaluationDecoratorsAndClassFields` lines 14 / 18, round 948).**
  `const C = @dec class { }` and `using C = @dec class { }` both take it; pristine accepts
  both (decorators on class expressions have been legal since TS 5.0). **It is NOT a `using`
  defect** — the `using` parse cascade had merely been masking it, which is why closing
  (CHK.25) took the fixture 10 -> 2 rather than 10 -> 0. Reproduce with
  `const C3 = @dec class { static x = 1; };` at any target; the emitter half (tsc's
  `__esDecorate` for a class expression) is a separate question from the checker's refusal.

- [ ] **(CHK.15) THE `instanceof` POSITIVE BRANCH HAS NO INTERSECTION TAIL — 1 OURS-ONLY ROW,
  BUT A GENERAL RULE (`controlFlowInstanceofWithSymbolHasInstance` line 26, round 942).**
  `s = new Set<number>(); if (s instanceof Promise) {} s.add(42)` reports
  `Property 'add' does not exist on type 'Promise<any> | Set<number>'` where pristine is
  silent: tsc's `getNarrowedType` ends in `maybeTypeOfKind(t, Instantiable) … ?
  getIntersectionType([t, c])`, so the then-branch is `Set<number> & Promise<any>` and the
  JOIN back is `Set<number>`; ours answers the CANDIDATE alone (`narrowByInstanceOf`'s
  `isMatch -> classType`), so the join is a union. `narrowByCallPredicateWorker` already
  carries the equivalent round-425 "positive-empty INTERSECTION fallback" for a PREDICATE
  target — this is the same rule at the `instanceof` site, and its blast radius is every
  `instanceof` in the program, so it needs the 8-profile grid and the 630-fixture sweep, not
  a pin alone.

- [x] **(CHK.16) A DECLARATION'S OWN TYPE PARAMETERS WERE NOT IN SCOPE FOR THE TS2344
  CONSTRAINT WALKER — LANDED, round 943, and it FIXES A FALSE NEGATIVE IN THE SAME MOVE.**
  `checkConstraintsInStatements` pushed them for a `FunctionDeclaration` (round 82, whose
  comment names this exact defect), for a type ALIAS only when the body was an `ImportType`
  (B98a's narrow gate) and for a class or interface never — so a parameter SHADOWED by a
  same-named file-level type was resolved to that type and judged against the callee's
  constraint. `withDeclTypeParamScope` is now the one site, used by the alias, class and
  interface branches, heritage clauses included. Pristine `conditionalTypes1` is two
  ours-only TS2344 from `interface A` (line 309) against `type And<A extends boolean, B
  extends boolean> = If<A, B, false>` (line 171) — **138 lines apart, which is why every
  hand-written reduction was silent and the bisection had to delete the file's TAIL**. The
  other direction was equally wrong, so the fix ADDS diagnostics: `type Loose<Q> = Box<Q>`
  with `interface Box<S extends string>` was silent and now reports TS2344 as pristine does,
  and over 611 fixtures that gained NO ours-only row. **The first cut fixed only the alias
  branch and a "regression guard" pin went RED — that is how the class/interface half was
  found.** Sweep **318 -> 316**, pristine-only 775 -> 775, zero fixtures regressed, 8-profile
  grid added=0 removed=0, suite 15,235 -> 15,248 with no baseline moved.
  `docs/pristine-divergences.md` § 3c.

- [x] **(CHK.17) LIB AVAILABILITY WAS DECIDED FROM THE *RAW* `ES3` TARGET DEFAULT WHERE tsc
  DEFAULTS AN UNSET TARGET TO THE LATEST — LANDED, round 944.** `CompilerOptions.libTarget`
  (unset -> ES2024, explicit -> itself, `es5` included) is now the one input to
  `libFeatureAvailable`, `libProvidesGlobalAt` and the lib-SET resolution in `bindRealLibs` /
  `RealLibSnapshots.prewarmParsedLibFiles`; NOT `effectiveTarget`, which maps an explicit
  `es5` UP to ES2015 and would delete that program's genuine TS2550/TS2583 (round 941's
  TS18028 fork). Sweep **316 -> 313**, pristine-only 775 -> 775, zero fixtures regressed,
  8-profile grid `added=0 removed=0` on all eight (every profile sets BOTH `target: es2020`
  and `lib: ["es2020"]`, so it is a pure control), suite **15,248 -> 15,262 / 0** with NO
  corpus baseline moved. The CLAUDE.md entry that recorded the raw reading as deliberate is
  corrected: it was INVISIBLE, not tested — 0 of 55 case files touching a `LIB_MIN_TARGET`
  member name, 0 of the ~30 referencing a `LIB_GLOBAL_INTRODUCING` global and 0 of the 26
  carrying an `and N more` count omit `@target`/`@lib`.

- [x] **(CHK.21) THE 23 `options.target < ES2015` DOWNLEVEL GATE LINES NOW READ
  `CompilerOptions.defaultedTarget` — AND THE ENTRY'S OWN EVIDENCE WAS MISATTRIBUTED, SO THE
  FAMILY'S SIGN IS THE OPPOSITE OF WHAT IT SAID (round 945).** Round 944 filed this as a
  FALSE-NEGATIVE item on four pristine-only TS2488 rows the gates were assumed to suppress.
  Run at an EXPLICIT `es2015` and `esnext`, where those gates are wide open, we are **still
  silent for all three shapes** — so no gate suppresses them and they are an unimplemented
  iterability check, re-filed as **(CHK.22)**. The real family is a FALSE-POSITIVE one that
  neither instrument could see: the raw target's `ES3` zero value made a tsconfig naming no
  `target` collect **six** diagnostics pristine does not emit (TS1250, TS1501, TS1503,
  TS2659, TS2737, TS18045 — measured on one 14-line file, before vs after, with the explicit
  `es5` and `es2017` columns byte-identical). Oracle: **every** TS1250/TS1501/TS1503/TS2396/
  TS2659/TS2737/TS18045/TS2802 baseline in the pristine corpus comes from a fixture with an
  explicit `@target`. Three raw-target sites are KEPT with reasons in the KDoc (the two
  `target >= ES2015 || …` strict-mode determinations, which a flip makes unconditionally
  strict, and one per-fixture baseline pin). `docs/pristine-divergences.md` § 3d.1.

- [x] **(CHK.22) THE for-of / SPREAD OPERAND'S `[Symbol.iterator]()` RETURN IS NOW CHECKED —
  LANDED, round 946: 4 PRISTINE-ONLY TS2488 ROWS -> 0 WITH OURS-ONLY FLAT, THE FIRST ENTRY IN
  THIS ARC THAT MOVES ONLY THE FALSE-NEGATIVE COLUMN.** `spineCheckIterableOperand` /
  `iterableOperandFailure` reproduce tsc's `getIterationTypesOfIterableSlow` ->
  `getIterationTypesOfMethod("next")` chain for `for...of` and ARRAY-LITERAL spread: an
  OPTIONAL `[Symbol.iterator]?()` is TS2488 (tsc's `method && !(method.flags & Optional)`),
  and a zero-argument `[Symbol.iterator]()` whose RETURN type has no `next` is TS2488 + the
  related **TS2489 `An iterator must have a 'next()' method.`**. **THE CHECK IS
  POSITIVE-EVIDENCE-ONLY AND THAT IS THE WHOLE FP FIREWALL**: it fires only where the member
  is FOUND and provably broken and bails on everything else, so every bail is a false
  negative and no bail is a false positive — which is why a new diagnostic on the commonest
  construct in the language moved **zero** of ~13k corpus baselines. **`this` READS AS `any`
  HERE** (no polymorphic `this` type), so `[Symbol.iterator]() { return this }` — three of
  the four rows — needed `iteratorMethodThisReturn`, a bounded declaration read that answers
  the CARRIER, which is tsc's own answer rather than a widening. Sweep **297 -> 297
  ours-only, pristine-only 773 -> 769**, zero fixtures regressed; 8-profile grid `added=0
  removed=0`; suite **15,294 -> 15,324 / 0 / 3** with no baseline moved; `cost_gate.py`
  `typeOfExpr.calls +0.22%` (the per-operand type read — a reached-ness proof), rebaselined
  in the same commit. 11-arm ablation, every arm at `ran 63`.
  `docs/pristine-divergences.md` § 3e.

- [ ] **(CHK.23) THE MISSING HALF OF THE ITERABILITY CHECK — A TYPE WITH NO
  `[Symbol.iterator]` AT ALL IS STILL ACCEPTED, AND SO ARE FOUR OTHER CONSTRUCTS (round 946,
  scoped out with tsc's answer known for every row).** § 3e.3 of `docs/pristine-divergences.md`
  is the table. The big one is the MISSING-member case, which is where tsc's rule needs a
  complete model of what is iterable — arrays, strings, tuples, `Iterable<T>`, a constrained
  type parameter, every union of them and the built-in iterator families — and one gap in
  such a model is a false positive on `for...of`; note that under the EMBEDDED lib only
  `IterableIterator<T>` declares `[Symbol.iterator]` at all, so the model cannot be built
  from member lookup alone there. The rest, each already pinned SILENT in
  `IterableOperandProtocolTest`: an OPTIONAL `next` (tsc reports it; refused because no
  pristine baseline here measures it), an iterator type with an empty member table or a
  string index signature, `[Symbol.iterator]` requiring an argument on a CLASS (B438e owns
  only the object-literal spelling and its hard-coded TS2322 chain), and the four other
  constructs — CALL-argument spread, array DESTRUCTURING, `yield*` and `for await…of`, whose
  `IterationUse` flags carry different diagnostic families (TS2504 / TS2569 / TS2461).

- [ ] **(CHK.24) THERE IS NO POLYMORPHIC `this` TYPE — `return this` AND `(): this` BOTH
  RESOLVE TO `anyType` (round 946, measured).** `class C { m() { return this } n(): this
  { return this } }` makes `c.m()` and `c.n()` answer `any`, so every `this`-returning
  builder chain in a checked program is untyped and every rule that reads such a return
  bails. Round 946 needed exactly one question answered — "does the carrier have `next`" —
  and got it from `iteratorMethodThisReturn`, a bounded read of the member's DECLARATION;
  that helper is a stopgap and says so. The general fix is tsc's `getThisType` plus the
  `ThisType` type-node arm, and its blast radius is every method-chain return in the
  program, so it needs the 8-profile grid and the 630-fixture sweep.

- [ ] **(CHK.18) `t[k] = v` THROUGH A GENERIC INDEXED ACCESS IS TS2862 WHERE PRISTINE SAYS
  TS2322 — 3 ROWS, A CODE DIVERGENCE RATHER THAN A FALSE POSITIVE
  (`keyofAndIndexedAccessErrors` lines 140-142, round 943).**
  `function test1<T extends Record<string, any>, K extends keyof T>(t: T, k: K) { t[k] = 42 }`:
  we refuse the WRITE (`Type 'T' is generic and can only be indexed for reading`), pristine
  permits it and rejects the VALUE (`Type 'number' is not assignable to type 'T[K]'`). tsc's
  rule reads the receiver's CONSTRAINT for a writable index signature before refusing; ours
  does not. Both compilers error at the same position, so this is FORM under
  `docs/logical-parity.md` § 2 — but the form is a different diagnostic identity, and the
  underlying gate is a real modelling gap that would show as a false POSITIVE the moment a
  program writes through a constrained generic index legally.

- [x] **(CHK.19) A FUNCTION-BODY TYPE ALIAS IS NOT BOUND, SO THE LIB'S `Omit` WON — 1 OURS-ONLY
  TS2314 -> 0 (`conditionalTypes1` line 297, round 945).** `getTypeParamInfo` is a whole-program,
  NAME-keyed scan with no node context, so a block-scoped `type Omit<T>` (CLAUDE.md's B83.5: the
  binder never binds a declaration nested in a function body) was invisible and the LIB's
  two-parameter `Omit` answered the arity question. Closed with round 748's
  `lexicalTypeSymbolForNode` shape one declaration kind over — a name gate computed in the SAME
  sweep that already censuses block-scoped enums, then an ancestor walk over the INV.2(c)
  `lexicalScopes` reading `scope.symbols` ONLY. **It does not re-open the INV.3 minefield the
  B83.5 entry warns about, and the reason is structural**: `declareLexical` skips any name the
  main binder already bound in that container, so a scope-space hit can only be a declaration the
  conventional tables do not have. Measured: sweep **298 -> 297**, 0 added, pristine-only FLAT,
  zero fixtures regressed; 8-profile grid `added=0 removed=0`; `cost_gate.py` moved **−24
  `globals.lookups` (−0.003%)** — tsc's own sources carry block-scoped generic aliases
  (`PropOfRaw<T>` in commandLineParser.ts among them) that now answer locally instead of running
  the global scan, and the grid proves no verdict changed. **STILL OPEN, and named here rather
  than left implicit**: `outerTypeParamNames` is supplied by the TypeAliasDeclaration caller only,
  so a CLASS's or INTERFACE's own type parameters are still `emptySet()` and
  `interface I<T> { [k: T]: string }`-style shapes keep the older answer.

- [ ] **(CHK.20) VARIADIC TUPLE TYPES ARE UNMODELLED — 30 OURS-ONLY ROWS, THE SINGLE
  LARGEST FAMILY LEFT, AND IT IS A FEATURE RATHER THAN A DEFECT (`variadicTuples1`, round
  943).** `getTupleType` maps a `RestType` element through `is RestType ->
  getTypeFromTypeNode(elem.type)` — the arm a PLAIN element gets — so **`[...T]` is built as
  the one-element tuple `[T]`**. Three lines reproduce it:
  `function f<T extends unknown[]>(t: T, m: [...T]) { t = m }` reports `Type '[T]' is not
  assignable to type 'T'`. What is missing is TypeScript 4.0's variadic tuples in full: a
  tuple type with a variadic/rest element, its normalisation, the three relation rules the
  fixture's own section header states ("for a generic type `T`, `[...T]` is assignable to
  `T`, `T` is assignable to `readonly [...T]`, and `T` is assignable to `[...T]` when `T` is
  constrained to a mutable array or tuple type"), `keyof` over one, spread-argument arity,
  and inference into a leading/trailing rest (the fixture's whole `curry` section). M3-scale;
  do NOT attempt it as a bounded rule.

- [ ] **(CHK.13) THE STRICT-BY-DEFAULT CONVENTION IS THE LARGEST *SYSTEMATIC* DIVERGENCE
  LEFT — 46 OURS-ONLY ROWS (42 by code, plus the four round 943 found wearing TS2683 /
  TS7019 / a `strictNullChecks` TS2322), AND IT IS AN OWNER DECISION, NOT A FIX (round
  941, re-sized round 943).** TS2564 / TS2454 / TS7010 fire in this compiler unless `@strict: false` is
  EXPLICITLY set (`Checker.kt`'s dispatch reads `!options.strictExplicitlyFalse`), where tsc
  requires `strict` (or the individual flag) to be ON. A real project with no `strict` in
  its tsconfig therefore gets `Property 'x' has no initializer and is not definitely
  assigned in the constructor` from us and nothing from tsc — `keyofAndIndexedAccess` alone
  is 17 rows for four plain `name: string;` class fields. Invisible to the corpus, whose
  fixtures set the directive. **Do not "fix" it without the owner**: the convention is
  load-bearing for the generated suite's expectations.

- [ ] **(CHK.7)(ii) A COMPUTED KEY'S *EXPRESSION* IS NEVER CHECKED, SO AN UNRESOLVABLE
  `[Symbol.x]` BECOMES A REQUIRED MEMBER — RE-MEASURED round 940 AND IT IS A MODELLING
  CHANGE, NOT A NAMING ONE.** `symbolProperty52`: pristine reports **TS2339 `Property
  'nonsense' does not exist on type 'SymbolConstructor'` TWICE** — once at the KEY inside
  `var obj = { [Symbol.nonsense]: 0 }` and once at the later `obj[Symbol.nonsense]` — and
  gives the literal NO such member, so `obj = {}` is silent. We emit **neither** the key's
  TS2339 (we get only the element-access one) **and** a TS2741
  `Property '[Symbol.nonsense]' is missing in type '{}'`. So the FP and the FN have ONE
  cause: `computedSymbolKey` invents `"[<dotted>]"` as a STRUCTURAL placeholder (round 723,
  and it is what makes tsc's own `Set<TElement>` literal's `[Symbol.iterator]` match) with
  nothing checking that the key expression resolves at all.
  **TWO SHAPES, and the cheap one is refused with a reason.** (a) The cause-level fix is
  tsc's `checkComputedPropertyName`: check the key EXPRESSION, emit TS2339/TS2464, and
  declare no member when it errors. That also closes pristine's TS2464 across the whole
  `computedPropertyNames*_ES6` set, which the round-939 sweep records as one of the largest
  ours-*missing* families. (b) Narrowing `computedSymbolKey` to keys whose `Symbol.<name>`
  is a REAL `SymbolConstructor` member is cheaper and is REFUSED as written: a hardcoded
  well-known list drifts from the lib and would DELETE a member for any symbol the list
  lacks — a TS2741 false positive in the other direction — while asking the type system
  means a member-resolution call from inside `getTypeOfObjectLiteral`, i.e. exactly the
  round-935 ambient-input hazard one layer down. **The whole population is 1 FP row in an
  ungated fixture on a program pristine already rejects twice; the prize is the FN.**

- [ ] **(CHK.7)(iv) STRING/NUMERIC MEMBER-NAME EQUIVALENCE IS MISSING IN THE *TYPE-LITERAL*
  SCAN ONLY, AND IT IS A FALSE **NEGATIVE** — round 939's entry has both the direction and
  the scope wrong.** Re-measured on `numericStringNamedPropertyEquivalence`: pristine emits
  7 rows, we emit 4, **ours-only is ZERO**. The CLASS scan already normalizes
  (`memberKey`'s `normalizeNumericKey`, so line 6 matches) and the INTERFACE scan matches
  lines 10/12 by accident — `1`'s text is already canonical. What is missing is
  `var a: { "1": number; 1.0: string }`: `checkDuplicateInterfaceMembers` names a numeric
  member through `getMemberNameText`, which returns the RAW text, so `"1"` and `1.0` do not
  collide and pristine's **TS2300 x2 (16,5 / 17,5) + TS2717 (17,5)** are all lost.
  **THE FIX IS ONE LINE PLUS A DISPLAY SPLIT, AND THE SPLIT IS THE REAL WORK**: group by
  `normalizeNumericKey`, but pristine prints **two different names for the same member** —
  TS2300 says `'1'` (tsc's binder message uses the SYMBOL name) and TS2717 says `'1.0'`
  (the checker's `declarationNameToString` of the later declaration, and its related TS6203
  says `'1.0'` too, at the position of the `"1"` member). `PropInfo` carries one `display`
  today, so it needs a second field. Low blast radius (a numeric member name whose text is
  not already canonical, in an interface or type literal) and it can only ADD diagnostics
  pristine already has — but it is an FN, so it does not move the v1 zero-FP metric.

- [x] **(CHK.4) THE QUALIFIED, TYPE-ANNOTATION AND WELL-KNOWN-SYMBOL ROUTES — LANDED,
  round 936, both directions, and the residue is re-scoped as (CHK.5) above.** Three
  capabilities, each a false POSITIVE in the supply direction and a false NEGATIVE in the
  excess one at the same time. (i) QUALIFIED keys — `NS.K`, `NS.Inner.IK`, a dotted
  `namespace A.B`'s const, a MERGED namespace's second block, and a const-or-plain ENUM
  member declared inside a namespace: all bind in tsc, all were TS2741 here and silent
  there. Resolved by descending `ModuleBlock` statements SYNTACTICALLY, because
  `currentFileLocals` is ambient and round 935 measured what that costs a member name; the
  one symbol-table consult left is the enum leaf, whose VALUES are in the binder's frozen
  tables and nowhere in the AST. (ii) The TYPE-ANNOTATION spellings — a no-substitution
  template-literal TYPE and a TYPE ALIAS to a literal, including a chain. **`TemplateLiteralType`
  is not a structured node in this parser** (B65.1: empty spans, the whole raw slice in
  `head.rawText`), so `templateSpans.isEmpty()` is true for a SUBSTITUTING one too and
  `head.text` answers `""` — a name matching no member, which reached the excess check as a
  real member on the first build. The raw text is the only discriminator that exists.
  (iii) WELL-KNOWN SYMBOLS in the excess check, which required one embedded-lib line:
  `IterableIterator<T>` did not declare the `[Symbol.iterator]()` member the real lib
  declares, so a literal supplying it against an `IterableIterator`-extending interface
  read as excess (the round-456 pin, and the ONLY red the suite produced). Refused, with
  tsc agreeing on every row: a widened namespace `let`, a substituting template type, an
  alias to a union, and — measured over seven of them — every computed key tsc cannot
  late-bind, which is why the well-known route demands the receiver be `Symbol` with no
  local binding of that name rather than re-admitting `computedSymbolKey` generally.
  28 pins, 13-arm ablation. The `NS.K` FP is gone; the SYMBOL axis verdict is that the
  well-known half was SMALL and the `unique symbol` half is MODELLING — see (CHK.5)(d).

- [x] **(CHK.3) LATE-BOUND COMPUTED KEYS — LANDED, round 935, BOTH DIRECTIONS IN ONE
  COMMIT. One missing capability was a false POSITIVE on one side and a false NEGATIVE on
  the other, and the round's product is that **tsc's own rule is NOT PORTABLE AS WRITTEN**.**
  Supply: `const K = "p"` / `const enum E { P = "p" }` + `{ [K]: 1 }` / `{ [E.P]: 1 }`
  satisfy a required `p` in tsc and were TS2741 here. Excess: the same keys spelling a name
  the target LACKS are TS2353 in tsc, named as WRITTEN, and were silent here. Both are now
  parity, plus every row the table was extended with before designing: a const ALIAS chain,
  a `let` with a literal ANNOTATION (const-ness is not the criterion), a `declare const`, a
  const whose literal INITIALIZER beats a union annotation, a plain (non-`const`) string
  enum, a NUMERIC enum member and a numeric const (named by the VALUE's canonical string,
  so `1e3` is "1000"), a body-local const and an inner const SHADOWING an outer one.
  Refused, with tsc agreeing on every one: a widened `let`, a genuine literal UNION, a plain
  `symbol`, a bare type parameter, a substituting template, and an AMBIENT non-`const` enum
  member with no initializer (round 746's opaque rule turns out to be tsc's own answer).
  **THE FIRST DRAFT PORTED `isTypeUsableAsPropertyName` LITERALLY — the key expression's
  TYPE — AND IT MEASURED AS A NAME THAT IS NOT A FUNCTION OF THE PROGRAM**: a FILE-LEVEL
  un-annotated `const K = "p"` answers the literal in the assignability pass and the widened
  `string` in the pass behind TS2339, so `const obj = { [K]: 1 }; obj.p` emitted the correct
  TS2322 **and** `Property 'p' does not exist on type '{}'` in ONE compile — round 933's
  two-extraction-sites signature reached through ambient state (round 911) instead of through
  a second `when`. The landed resolution is SYNTACTIC (an enum member's VALUE via
  `enumMemberEntries`; otherwise the declaration a name resolves to, by an innermost-first
  walk of the enclosing statement lists — `lookupPerFileForNode` cannot see a body local at
  all, B83.5, and a scope-chain consult would be ambient again), and the pin that fails if
  the type route returns asserts the two passes AGREE, because each pass alone is green.
  `lateBoundComputedKeyName` is asked BEFORE `computedSymbolKey` at all three naming sites,
  which is also what retires round 934's arm-A4 false positive at its source rather than by
  exclusion. 25 pins, 8-arm ablation (every arm with a uniquely-its-own failure). What is left is (CHK.4) above.

- [x] **(CHK.2) A COMPUTED OBJECT-LITERAL KEY NEVER REACHED THE EXCESS-PROPERTY CHECK —
  LANDED, round 934. A false NEGATIVE in every position, from ONE name-extraction `when`,
  and the diagnostic was being computed in full before it was dropped.** Round 933 measured
  the row and left it: ``{ p: 1, [`zz`]: 2 }`` and `{ p: 1, ["zz"]: 2 }` against
  `interface Opt { p?: number }` are TS2353 in tsc 7.0.2 and were silent here. Extended
  before designing, it is larger: a BARE numeric key `{ 7: 2 }` escapes too (so the omission
  is not about computed keys at all), and every position escapes together — `satisfies`, an
  ARGUMENT, a `return`, a NESTED literal under a computed key, a computed METHOD name.
  **The cause is the exact mirror of (CHK.1)'s**: `getTypeOfObjectLiteral` had named all of
  those keys for years, so the source TYPE carried the member and `checkExcessProperties`
  judged it excess correctly — and then looked for the AST node that declared it with a
  `when` knowing only `Identifier` and `StringLiteralNode`, found nothing, and emitted
  nothing. The lookup is now ONE shared predicate (`objLitElementMemberName`), so the type
  builder and the excess check cannot disagree about what an element names.
  **THE ROUND'S REAL PRODUCT IS THE TWO NEAR MISSES, EACH OF WHICH TURNED THE FN INTO AN
  FP ON A ROW ROUND 933's TABLE DOES NOT CONTAIN.** (i) Admitting a numeric key exposed a
  TARGET-side gap that could not matter before — `collectTargetPropertyNames` bails on a
  STRING index signature and knows nothing of a NUMERIC one — so `{ [7]: 2 }` against
  `{ [k: number]: T }` was reported where tsc is silent. (ii) Naming the key with
  `computedLiteralKey ?: computedSymbolKey` (the obvious delegation) reported `'[E.P]'` for
  `const enum E { P = "p" }` + `{ [E.P]: 1 }`, which tsc late-binds to the existing `p` and
  accepts — **`computedSymbolKey` INVENTS `"[<dotted>]"` so a well-known-symbol member can
  match structurally (round 723); it is not a claim about what the key spells and cannot
  tell `Symbol.iterator` from `E.P`.** Both are guards with a discriminating negative
  control apiece. **So the line is round 933's line in the other direction: the excess check
  acts on a computed key exactly when the key is a LITERAL spelling one fixed name**; every
  key needing the key's TYPE stays out in BOTH directions and is (CHK.3). **The message FORM
  is matched rather than recorded** — tsc keeps the delimiters (`'["zz"]'`, `''zz''`) and
  squiggles the whole written key, the span is in hand, and no ACTIVE corpus test has a
  delimited excess key (ten of the eleven such baselines are not generated; the eleventh
  belongs to another emitter). 20 pins + one round-933 pin rewritten to tsc's own answer
  (it asserted a TS2741 that tsc does not emit); six-arm ablation, all reached, four with a
  uniquely-their-own failure, four pins recorded as undiscriminated rather than claimed.
  **Every profile instrument is a CONTROL and it was measured**: across all eight profiles'
  1,249 `.ts` files an object-literal computed key matches 8 times — all eight the same
  destructuring pattern — so `+0.00%` and `added=0 removed=0` are the expected answers.

- [x] **(CHK.1) A BACKTICK-QUOTED COMPUTED MEMBER KEY NAMES A MEMBER — LANDED, round 933.
  Three FALSE POSITIVES tsc does not have, from ONE missing `when` arm, in a spelling the
  whole tsc corpus never uses.** Round 932 recorded, in passing, that `` { [`p`]: v } ``
  did not supply a required `p`. Measured against `tsc 7.0.2` this round it is three, not
  one: the object-literal supply (TS2741), an INTERFACE's own `` [`ip`] `` member (TS2339)
  and a CLASS's own `` [`cp`] `` member — the last of which resolved for the assignability
  check and simultaneously FP'd TS2339 **in one compile**, because the type-building site
  and the class-AST walker are two independent name extractions and only one of them had
  been widened. **The fix is `computedLiteralKey` growing a `NoSubstitutionTemplateLiteralNode`
  arm, plus `classMemberNameText` DELEGATING to it instead of re-spelling its `when`** — the
  archive's B451 entry says outright that this family has >= 5 independent extraction sites
  and that widening one silently leaves the others FP'ing, and the class row is what that
  looks like from the outside. **What stays refused, measured and pinned in the positive:**
  a SUBSTITUTING template (`` [`p${x}`] ``) names no fixed member and is TS2741 in tsc too.
  **What stays OPEN and is NOT pinned** (round 765's law — a known-open gap is a countdown,
  not a guard), both with tsc's answer measured: `{ [K]: v }` / `{ [E.P]: v }` supply nothing
  here and do in tsc — that needs the key's TYPE, i.e. late binding, not a spelling; and the
  EXCESS-PROPERTY direction never sees a computed key at all, so `` { [`zz`] } `` AND
  `{ ["zz"] }` both escape TS2353 where tsc emits it (a false NEGATIVE, symmetric across the
  spellings, untouched by this round). tsc additionally renders such a key's name WITH its
  delimiters in the TS2353 text (`'"zz"'`, `` '[`zz`]' ``) where we print the bare name — a
  form divergence, noted not acted on. 11 pins (`TemplateComputedMemberKeyTest`, every
  backtick row beside its quote-spelled B451 control); three-arm ablation, all reached.
  **Every profile-based instrument is STRUCTURALLY BLIND here and that is measured, not
  assumed**: the eight tsc profiles contain ZERO backtick-quoted computed member keys (the
  only `` [`…`] `` matches are array literals), which is why `cost_gate.py` reads +0.00%
  on all 20 counters and the 8-profile grid reads `added=0 removed=0` — both are CONTROLS
  here, and the corpus plus the new pins are the gate.

- [x] **(API.17) A COMPUTED OBJECT-LITERAL KEY `{ ["p"]: v }` — LANDED, round 932; § 14's gap 2,
  and the LAST silent shape anywhere in this API.** Round 930 measured a computed key as
  "usually reported" — `WOULD_NOT_COMPILE` where the contextual member is REQUIRED,
  `OCCURRENCES_INCOMPLETE` where the literal has no contextual type — and SILENT in exactly
  one shape: an OPTIONAL member, where stranding the key costs no diagnostic, so the applied
  rename compiled clean with the old name still spelled in the literal and no gate in this
  repository could see it. tsc 7.0.2 counts the key as a reference, hovers it as the member,
  navigates to the member's declaration and renames it (measured, six spans on a fixture
  carrying one). **The landing is a POPULATION change and one predicate**: `occurrenceNodes`
  now sweeps every literal for which `isMemberPosition && isMemberNameLiteral` holds, which
  subsumes (API.9)'s element accesses, (API.16)'s templates, `{ "p": v }`, `{ ["p"]: v }`,
  ``{ [`p`]: v }`` and a class's or an interface's `["p"]` — so the set a caret may land in,
  the set a sweep reports and the set a rename must edit are ONE set by construction rather
  than three definitions kept in step. **A literal the API cannot RESOLVE still belongs in it**:
  seen-and-unplaced is a stated `OCCURRENCES_INCOMPLETE` conflict, unseen is a silent miss.
  **`{ [K]: v }` is deliberately out** — it spells no fixed name and tsc reads it as a
  reference to the binding `K` alone (measured); the asymmetry with the element-access arm is
  stated in `SyntaxRoles.isMemberPosition`, because calling it a member position flips the
  completeness net's polarity for every ordinary `const` rename. **THE ROUND'S SECOND HALF WAS
  AN AUDIT FINDING**: `typeCaptureReportedType` recorded an object-literal key's TYPE as
  deliberately not closed *because the contextual type is walk-scoped state a capture cannot
  read* — and (API.10) built `typeCaptureContextualType`, a purely syntactic walk, one round
  later. Nobody came back. Measured before this round, EVERY key — computed or bare —
  answered `any`, or the COLLIDER's type where a same-spelled binding existed. Closed by
  `typeCaptureObjectLiteralKeyType`, the contextual member's type with the key's own value as
  the fallback, which is what tsc reports in both shapes. +18 pins, four inverted; ten-arm
  ablation. `docs/language-service.md` §§ 8, 9, 10b, 10d, 14.

- [x] **(API.16) A MEMBER NAMED BY A TEMPLATE ELEMENT ACCESS — LANDED, round 931; § 14's
  gap 6, the ONE genuinely silent gap in this API, is closed.** ``o[`p`]`` was outside
  (API.9)'s occurrence population, so `referencesAt` / `documentHighlightsAt` / `renameAt`
  missed it AND SAID NOTHING: round 930 proved it end to end — the rename applies, the
  template keeps spelling the old name, and the applied program has ZERO diagnostics, so
  no gate this API has can see it. tsc 7.0.2 counts it as a reference, renames it, hovers
  it as `(property) I.p: number` and completes inside it (all measured). It is now an
  ordinary occurrence in every one of those queries, with the edit covering the TEXT and
  **not the backticks** — round 926's rule one delimiter over, and the same measured span
  tsc writes. **Round 929's completion refusal is CASHED rather than overruled**: it
  refused for exactly one reason, that the sweep could not find such a member, and the
  sweep now can — the two still share ONE enumeration, so they cannot drift apart about
  what a member name is. **REFUSED, and it is a NODE-CLASS boundary rather than a
  judgement**: a template carrying a SUBSTITUTION (``o[`p${x}`]``) spells no fixed name,
  so it is neither an occurrence nor an obstacle and its caret renames nothing — which is
  tsc's answer there too (zero references, `prepareRename` refuses). **The one place a
  second mechanism was needed is HOVER**: this compiler's element-access typing keys a
  named member off a STRING literal, so routing the template through the access would
  have answered `any` — the (API.15) violation one round later — and the member is
  resolved through the receiver instead. +8 pins, two inverted; seven-arm ablation, five
  distinct red sets plus one MEASURED-REDUNDANT guard with its reach proved by a
  narrowing twin. `docs/language-service.md` §§ 8, 9, 10a, 10b, 10d, 14.

- [x] **(API.15) AN ENUM MEMBER'S DECLARATION NAME REPORTS `any` — LANDED, round 931; the one live violation
  of *prove to offer* in this API.** Measured round 930 on four shapes (plain, valued,
  `const enum`, string enum): `quickInfoAt` on the `Alpha` of `enum Plain { Alpha }`
  answers `QuickInfo(displayString = "any")`, where tsc 7.0.2 answers
  `(enum member) Plain.Alpha = 0` and where our own USE site already answers
  `Plain.Alpha`. Not an absent answer — a plausible wrong one, which is the failure mode
  (BUG.4) and (API.11) each closed one position over. **The mechanism is known and the fix
  is one leg**: `Checker.typeCaptureMemberDeclarationType` resolves a declaration name
  through its OWNER and then asks `typeCaptureCollectMembers` for the member — and an
  enum's own type is a member-LESS `Type.Object` (CLAUDE.md), so the collection finds
  nothing, the leg returns null and the fallback types the identifier as a free name.
  What it needs instead is `getDeclaredTypeOfEnumMember`, which is what the use site
  already reaches. Pinned as a DEFECT by `LanguageServiceStateTest`'s `an enum member's
  declaration name reports the WRONG type and its use reports the right one`, so closing
  it must edit that test, § 8 and § 14's gap 7 together. Definitions and references for
  the same position are already complete; only the TYPE is wrong.
  **LANDED**: `typeCaptureEnumMemberType`, eight lines, minting through
  `getDeclaredTypeOfEnumMember` — and the measured product is that the obvious
  alternative does NOT work (`getTypeOfSymbol` on an enum member symbol answers `any`,
  arm A2). Five shapes report the member's type, the same instance the use site
  reports; tsc's extra decoration is the member's VALUE, which this API deliberately
  does not render (§ 8). The defect pin is inverted in place.

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
