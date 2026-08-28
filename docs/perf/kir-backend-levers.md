# The KIR backend's runtime levers

What the Kotlin-IR backend spends its time on when it runs a real library, which
levers have been measured, and what each one returned. The instrument is
`scripts/kir-bench.sh` (three arms, equivalence gate before any timing) and
`scripts/kir-profile.sh` (leaf frames, two processes).

Every number here is a WITHIN-ROUND paired delta on one box. An absolute
`ns/emit` or `us/parse` from one session is not comparable to another's; the
ratio against the Node arms in the SAME run is.

## 0. The two workloads, and why they disagree

`mitt` is 123 lines of event dispatch: an array of handlers, called through a
property bag. `smol-toml` is 1,082 lines of hand-written scanner: a
`ParseContext` object read and written once per character, plus regular
expressions for numbers and dates.

They disagree by sign against Node, and the reason is that they exercise
different halves of the backend — a dynamic CALL against a dynamic PROPERTY.

## 1. The levers, in the order they were measured

### (1) Arity-specialized dynamic calls — LANDED 2026-08-21

`jsCall(callee, vararg)` allocated an `Object[]` per call and walked an
`instanceof` chain. `jsCall0`..`jsCall5` pass arguments positionally.
Measured **mitt −6.5%** (ranges disjoint), **toml −2.6%**.

The specialization still ADAPTS the callee's arity, which is not optional:
`mitt` registers a one-parameter wildcard handler that `emit` calls with two
arguments.

### (2) A small-bag linear scan — REFUSED 2026-08-21, reverted

Parallel arrays with an identity-first scan, promoted to a map on outgrowing an
inline capacity. Measured **toml +21% SLOWER**.

The mechanism is the one worth carrying: the bag population is BIMODAL and the
profile's single 28.3% hid it. `ParseContext` is a four-field scanner state that
a scan suits; the parsed document's tables are the other half, the root table
alone has 18 keys, and every bag that outgrows the inline capacity pays the
arrays AND the promotion AND the map. **A hash-family share is not evidence
about any particular container until that container's key-count distribution is
censused**, and an identity-first compare is a pure loss wherever the keys come
from DATA rather than from emitted literals.

### (3) Operands the lowering already typed — LANDED

`===`, `!==`, `==`, `!=`, a `switch` clause, a condition and a string
conversion all went through an `Any?` entry point, so a comparison the lowering
had already proven to be between two numbers boxed BOTH operands and then
rediscovered their types with an `instanceof` chain. `+` had been decided by the
erased operand types since the beginning (`addValues`); this is the same rule
applied to the rest of the family.

The semantics are the part to get right, and they are pinned rather than
argued: `NaN !== NaN` and `0 === -0` (IEEE-754, which is what Kotlin gives two
statically-primitive `Double`s), `-0` and `NaN` falsy but the string `'0'`
truthy, `1 == true` and `null == undefined` true — so no MIXED case may
specialize under abstract equality. `KirEqualitySemanticsTest` and
`KirPrimitiveOperandTest` are those pins.

Both half-specialized directions exist (`…AnyNumber` and `…NumberAny`) rather
than one canonical operand order, because reaching a single entry point would
mean swapping two expressions that may both have effects. That is pinned too.

**Measured**, 5 interleaved processes per arm, both Node arms flat across the
pair (mitt 335 -> 330 ms, toml 455 -> 452 ms):

| workload | before | after | |
|---|---|---|---|
| mitt, 4M `emit`/round | 62.25 ns/emit `[246..259]` | **61.50** `[243..248]` | −1.2% |
| smol-toml, 20k parses/round | 56.60 us/parse `[1086..1141]` | **48.90** `[954..988]` | **−13.6%**, ranges DISJOINT |

toml goes 2.49x slower than Node to **2.16x**; mitt stays 1.34x faster. The
split is what the two workloads are: a scanner compares characters once per
character, an event emitter barely compares at all.

## 2. The property bag, censused — and the second refutation of the same idea

After the operand specialization above, the leaf profile of the toml JVM arm
attributes, per owner rather than per stdlib row (`scripts/kir-profile.sh`,
round 2 of 2):

| owner | share |
|---|---|
| `JsObject.set` | **22.4%** |
| `JsObject.get` | **14.9%** |
| `JsObject.has` | 0.9% |
| `JsRegExp.test` | 20.3% |
| `jsTruthy` (its own `when`, in `Intrinsics.areEqual`) | 5.0% |
| `JsRegExp.<init>` | 2.2% |

Every one of the bag rows was inside `HashMap`. So the bag is the backend's
largest single cost on this workload, and `set` — the WRITE path — is bigger
than the read: `ctx.p++` in a scanner is a read plus a write per character.

### The attempt, and what it measured

`JsObject` was given the shape its literal declared — names and values in two
parallel arrays, found by a scan, promoted to a `LinkedHashMap` at the first
property the literal did not declare. That rule was chosen precisely to avoid
the size threshold that had already been refused: dictionaries built out of
parsed data promote at their first key and stay exactly where they were, and
only records whose names are constant-pool strings take the scan.

**Measured: toml 48.90 -> 64.25 us/parse, +31%, ranges disjoint
`[954..988]` -> `[1273..1309]`, both Node arms flat.** mitt improved
61.50 -> 59.50 ns/emit, which is the tell that the change did what it said and
still lost.

### Why, from the profile rather than from a theory

The rule worked: `HashMap` fell from 38.3% of samples to **4.7%**, so the
dictionary half really was left alone and the record half really did leave the
hash map. What did NOT happen is the saving. Counting SAMPLES rather than
shares — the run got longer, so shares alone would have hidden this — the bag
cost **709 samples before and 771 after**, and construction alone (`JsObject.
of`) appeared as a new 7.1% row. The scan replaced the hash lookup at
approximately its own price.

The rest of the regression is not in the bag at all: the program's own code
went 343 -> 489 samples and the regex family 476 -> 642, for work that did not
change. A cost that lands on unrelated frames is what a bigger, two-shaped
method does to the callers it used to be inlined into.

### The conclusion, which is what makes this a result rather than a loss

Two independent attempts at making the dynamic representation cheaper — by
size threshold and by declared shape — have now regressed a document parse by
21% and 31%. The bag is expensive because of the NUMBER of operations, not
their unit cost: a `LinkedHashMap` probe on an interned key is already about as
cheap as a three-element scan, and no arrangement of the same operations
removes them.

**Only the nominal half removes them** — `ctx.p` becoming a field of a
generated class rather than a property of a bag. That is (KIR.PERF.1), and its
case is now made by measurement from both directions rather than by the design
document alone.

What survives from both attempts is `KirPropertyBagTest`: its cases are
representation-independent by construction, both attempts passed all of them
unchanged, and they are what the next attempt will be graded by.

## 2a. The bag, censused by OPERATION — and the third refutation, which closes the family

§2 refused two shaped representations and concluded the bag is expensive in the
NUMBER of its operations rather than in their unit cost. That conclusion is now
measured rather than inferred, and the family is closed.

### The census, per parse

Counters on `JsObject`, run over the benchmark document (16 rounds x 20,000
parses = 320,000 parses):

| | per run | per parse |
|---|---:|---:|
| bags minted | 34,880,000 | **109** |
| `get` | 817,600,000 | **2,555** |
| `set` | 235,840,000 | **737** |
| ... of which OVERWRITE an existing key | 149,760,000 | 468 (**63.5%**) |
| `has` | 13,120,000 | 41 |
| **total bag operations** | | **3,333** |

Mean bag size **3.13** read-weighted, **2.75** write-weighted; largest bag in
the whole document **18** keys.

Dividing the profile's 47-52% hash-container share of a 33.65 us parse by that
population gives **~4.9 ns per bag operation**, which is exactly what a
`String`-keyed `LinkedHashMap` probe on a cached hash costs. The row SURVIVES
round 896's division test, unlike its neighbour: `jsTruthyBooleanOrNull` reads
7.2-7.4% of samples over **298 calls per parse**, i.e. **8.2 ns** for
`value != null && value` — impossible by ~20x, so that row is a location and
not a price, and it was refused without building anything.

### The read side is UNIMODAL, which §2 could not see

§2 measured the population as bimodal. That is true of ALLOCATION and false of
READS, and the read distribution is the one a lookup cost is weighted by:

| bag size at the read | share |
|---|---:|
| 3 | **93.6%** |
| 4 | 4.8% |
| 1, 2 | 0.7% |
| 5-18 | **0.9%** |

93.6% of every property read in the benchmark lands on a three-key bag — the
`ParseContext` scanner state — and the names it is asked with are the emitted
string LITERALS, which are interned. That is the most favourable population an
identity-compared scan could possibly be handed.

### So the cleanest possible scan was built, and it LOST

Neither of §2's two refused designs: **no promotion at all** on the fast path,
so `get` has ONE shape and stays inlinable — which is what the second attempt's
profile blamed — an identity-first compare, arrays sized to the censused modal
size, and every other case (an equal-but-not-identical key, a bag past the scan
limit, an absent key) handed to a cold method. `KirPropertyBagTest` and the
whole KIR module stayed green at 108/108, which is what that suite being
representation-independent is for.

| arm | median | range | n |
|---|---:|---|---|
| `LinkedHashMap` (committed), batch 1 | 692 ms | [657..752] | 5 |
| identity-scan array bag | 738 | [732..750] | 5 |
| `LinkedHashMap` pre-sized to the census | 708 | [699..754] | 5 |
| **`LinkedHashMap` (committed), batch 2** | **735** | [670..758] | 5 |

**READ THE LAST ROW BEFORE THE OTHERS.** The baseline moved **692 -> 735, +6.2%,
between its own two batches with the bytes unchanged** — so the array bag's 738
is indistinguishable from the baseline, and the +6.6% it looked like against
batch 1 was DRIFT. This is round 858's law arriving on a fourth instrument, and
it is recorded here because the first write-up of this section claimed the 6.6%
before the replication was run.

What the screen can therefore say is bounded, and it is enough: **neither
candidate is a win.** Not "a regression" — the harness cannot resolve one at
this size. The two earlier designs' +21% and +31% are large enough to survive
this band; today's two are not, and are recorded as no effect.

### What that closes, and why "no effect" is the argument

Four independent attempts at making a property access cheaper, none of which
produced a win:

| design | result |
|---|---|
| parallel arrays, promoted by SIZE | +21%, refused |
| parallel arrays, promoted at the first UNDECLARED key | +31%, refused |
| **identity scan, no promotion, single-shaped** | **no effect** |
| **`LinkedHashMap` sized to the census** | **no effect** |

The third design was handed the most favourable population a scan can have —
93.6% of reads on three interned keys — removed both earlier failure modes, and
still could not be told from a hash probe. **A hash probe on an interned key
with a cached hash is about two memory reads, and a bounds-checked
one-to-three-iteration loop is not fewer.** The pre-sizing arm says the same
from the other side: a smaller table costs more read collisions than it saves
in allocation, and reads outnumber writes 3.47 to 1.

The item's premise is a **−44%** prize at the limit. A candidate that cannot be
distinguished from the baseline is refused by that standard whatever its sign.

**The consequence for (KIR.PERF.1)'s guarded slot hint is that it is refused
too, without building it.** The hint's whole claim was that an O(1) indexed
compare beats the scan the first refutation used. But the scan is not behind a
hash probe — it is level with one — on a population where 93.6% of reads
already hit a three-key bag that a scan answers in at most three compares, and
that leaves the hint competing for the difference between "level" and "level".
Its cost, meanwhile, is real: the shaped representation plus the declared member
order reaching the lowering, which `CheckedFacts` does not expose. There is
nothing left for a slot index to remove.

**Only the NOMINAL half remains, and it is not a container change**: the ceiling
is a property read that is a `getfield` rather than any kind of lookup, worth
~16.3 us of a 33.65 us parse. `docs/kir-structural-typing.md` §7 prices it at
12x the dynamic one, and §6's per-primitive table is the reason it matters far
more on Kotlin/Native than here.

## 2b. The NOMINAL half, first slice — LANDED, and mitt is −10.7%

§2a closed the container family and left one direction: stop making the lookup
cheaper and stop doing a lookup. That is now built for the case a compiler can
see without any analysis at all — an object LITERAL whose property names are
statically known.

### What it generates

One JVM class per distinct name list per file, holding one real field per
property, **extending `JsObject`**. `{ pos: 0, line: 1, col: 2 }` becomes
`new JsShape_parse_0(0, 1, 2)` with three fields, and the class overrides `get`,
`set`, `has`, `delete`, `keys` and a `spill` hook over those fields.

**Extending the bag is the design decision that makes this affordable.**
TypeScript's assignability is structural and a generated class is not, which is
why `docs/kir-structural-typing.md` §7 prices the nominal half at 12x the
dynamic one — but that price is for changing what an object type ERASES to.
Here the erasure is untouched: every object type still erases to `JsObject`, a
shape instance IS one, and it passes wherever a bag is expected with no witness,
no coercion and no new refusal. A reader that does not know the shape — `o[k]`
with a computed key, a value that reached a parameter typed `any` — calls the
same virtual `get` and is answered by the same fields.

The dynamic half stays total. A property the shape does not declare goes to the
bag; the first `delete` or `Object.keys` SPILLS, moving the fields into the bag
in declaration order and making every later access ordinary, which is why no
slot needs a presence bit and why key order survives.

### Measured, 5 processes interleaved, equivalence gate green on both libraries

| | before | after | |
|---|---:|---:|---|
| mitt | 61.00 ns/emit | **54.50** | **−10.7%**, 1.35x → **1.54x** FASTER than Node |
| smol-toml | 33.65 us/parse | 34.25 | flat |

mitt's ranges are DISJOINT — `[209..219]` ms against `[243..249]` — and both
Node arms are flat (tsgo 335 against 329/337, xtsc 338 against 334). This is the
first measured win the nominal direction has produced.

### On Kotlin/Native it is FLAT, and that refutes §6's expectation

Verified rather than assumed, because CLAUDE.md records that Native's IR validator rejects
public fields the JVM accepts: `mitt` compiles, links and RUNS there with the shape classes
and the right sink, and measures **348 ns/emit against 354.75 — flat**.

§6 predicted the nominal half would be worth MORE on Native, since every `Any?` position is
a real allocation there. It is worth nothing yet, and the mechanism says why: the JVM's
−10.7% is C2 inlining the override at a monomorphic call site and folding the constant name
away, and Kotlin/Native has no JIT to do either — so the shape's `get` stays a real virtual
call over fields. **The nominal half pays on Native only once the access is a direct field
READ**, which is the next slice, not a virtual `get`.

### Why toml is flat, which is the honest half of the result

The shapes fire there — ten classes across its seven files — so the mechanism is
not missing. What offsets it is that `JsObject.get` had to become VIRTUAL, and
`smol-toml` builds its parsed tables dynamically (`{}` then `set`), so the reads
that were a final call into a hash probe are now a virtual call into one. The
gain on the scanner context and the loss on the tables cancel.

That also names the next slice precisely, and it needs no new front-end
information either: **where a local's initializer IS a shape construction, the
local can keep the SHAPE as its IR type**, and the property access then compiles
to the direct `IrGetField` the lowering already emits for a declared class —
no dispatch at all. What that cannot reach is a shape arriving as a PARAMETER,
which is how `smol-toml` passes its context, and that one does need the
whole-program shape inference §7 describes.

## 3. Two rows the census named that were pure overhead — LANDED

`jsTruthy` decided its answer with an equality `when` (`null, Undefined, false
-> false`), which Kotlin compiles to a chain of `Intrinsics.areEqual` calls —
**5.0% of the toml arm's samples, spent asking whether a value equals `false`.**
It is now a type-test chain deciding the same five cases in the same order.

`JsRegExp` allocated a `Matcher` per `test` and per `exec` — two `int` arrays
sized by the pattern's groups and locals, filled on every call, and
`Matcher.reset` was the single largest regex leaf at 10.2%. The two methods now
share one matcher, which is safe because neither lets any other code run
between starting a match and reading its groups out; `matcherFor` still
allocates, because its callers keep the matcher ACROSS iterations.

And a regular-expression LITERAL inside a function evaluates to a fresh object
per call — `value.replace(/_/g, '')` is that shape, and it was re-parsing its
source every time (`JsRegExp.<init>`, 2.2%). Every distinct `(source, flags)`
now compiles once and the `Pattern` is shared, which is invisible because a
`Pattern` holds no match state.

**Measured together: toml 48.90 -> 46.95 us/parse (−4.0%)**, Node arms flat
(452 -> 453 ms); mitt unchanged at 61.25 ns/emit. `KirRegExpTest` is the pin,
and it uses ONE expression many ways at once, because both changes fail the
same way — a second use reading state the first left behind.

## 3a. The checker's type reaching `+` — LANDED, and it measured NEUTRAL

The bytecode of `smol-toml`'s `skipVoid` shows `ctx.s.charCodeAt(ctx.p + 1)`
reaching `jsAdd(Object, Object)` with a `Double.valueOf` on the literal `1`,
because a property read out of a bag erases to `Any?` however precisely the
checker typed it — and `+` decides by the ERASED operand types, since it is two
operators. Asking the checker whether the whole SUM is a `number` decides both
coercions at once, and every other arithmetic operator has coerced its operands
to `Double` from the beginning (`x - 1` casts an `any` today).

The same read gave optional primitives their own truthiness entry points:
`!banNewLines` is a `Boolean?` at the JVM level and was walking the general
chain to answer a null check.

**Measured: toml 46.95 -> 48.00 us/parse with the ranges overlapping
(`[918..975]` -> `[921..970]`) and both Node arms flat — i.e. NOTHING, inside
the band.** It is kept anyway and the reason is stated rather than assumed: the
change is cost-monotone (it removes a call, an `instanceof` chain and a box and
adds a `checkcast`), it is pinned, and it closes the one place where `+`
disagreed with the rest of arithmetic about whom to ask. It is not counted as a
win.

## 3b. Kotlin's null assertions are an invariant JavaScript does not have

Every generated function opened with an `Intrinsics.checkNotNullParameter` per
non-null reference parameter — a call a recursive-descent parser crosses once
per token. It is wrong here twice over: a JavaScript function handed `undefined`
for a declared parameter does not throw at ENTRY, it throws (or does not) at the
dereference, so the assertion converted a JavaScript non-event into a failure at
the boundary carrying a Kotlin message about a Kotlin type.

`noParamAssertions` / `noCallAssertions` are now set on the generated program
only. **The runtime's own contract is untouched** — `JsRuntime` is compiled by
this repo's build with its assertions intact, so a lowering that hands `null` to
`jsStrCharCodeAt` still fails where it always did, and so do the `as Double`
casts the lowering emits at every numeric use of a bag read.

**Measured: toml 47.75 -> 47.05 us/parse and mitt 62.25 -> 61.00 ns/emit, both
ranges overlapping.** Counted as a fidelity fix that measures favourably, not as
a win.

## 4. Where it stands, replicated

The session's last four benchmark runs cover changes that measured inside the
band, so they are four draws of one number: **46.95, 48.00, 47.75 and 47.05
us/parse**, the last on the committed tree. Against the session's opening
**56.60**, that is **−16.9%** taking the committed run, and −15.2% to −17.0%
across the four.

| | tsgo -> node | xtsc -> JVM, opening | xtsc -> JVM, committed |
|---|---|---|---|
| mitt | 86.00 ns/emit | 62.25 | **61.00** (1.41x FASTER) |
| smol-toml | 22.60 us/parse | 56.60 | **47.05** (2.08x slower) |

**AMENDED — the regex engine landed and took another quarter of the toml parse.**
The three-arm run on the committed tree, 5 processes interleaved:

| | tsgo -> node | xtsc -> node | xtsc -> JVM |
|---|---|---|---|
| mitt | 82.75 ns/emit | 83.75 | **61.25** (1.35x FASTER) |
| smol-toml | 22.50 us/parse | 22.60 | **34.10** (1.52x slower) |

`smol-toml` is **47.05 -> 34.10 us/parse, −27.5%**, and 2.08x -> **1.52x** Node.
Ranges are disjoint ([663..757] ms against a pre-change 910 ms round) and BOTH
Node arms are flat (22.50 and 22.60 against 22.50 and 22.30), which is what
licenses reading it as a backend number. **`mitt` is flat again** at 61.25 —
the expected shape, since an event emitter matches no regular expression, and
the control that says this lever is the regex one rather than a drift.

Against the session's opening 56.60, the arc is now **−39.8%**.

`smol-toml` goes from 2.49x slower than Node to **2.08x**. **`mitt` is flat**:
its five readings across the session are 62.25 / 61.50 / 59.50 / 61.25 / 61.00 /
62.25, so the −1.2% the first pair showed did not survive replication and is not
claimed. That is the expected shape — an event emitter barely compares
characters, barely reads properties and never matches a regular expression, so
none of this session's levers has anything to do there.

Both Node arms held flat across every pair (tsgo 452/455/453/451/446 ms on toml),
which is what licenses reading any of this as a backend number.

The next lever is the one §2 argues for from both directions: the NOMINAL half,
(KIR.PERF.1), whose entry now carries this census and a design the two
refutations do not rule out.

## 5. The regular expressions — LANDED 2026-08-21, worth −27.5% of the toml parse

`smol-toml` validates every scalar and every key part with a regular expression,
and § 2's leaf profile puts `JsRegExp.test` at 20.3% of the JVM arm's samples.
That is a location. This is the price.

Driven the way `JsRegExp.test` drives them — `find()` on a reused matcher — over
exactly the value and key strings the benchmark document contains:

| call | per document | ns/call, `java.util.regex` | ns/call, V8 |
|---|---:|---:|---:|
| `INT_REGEX.test` x16 | 3.75 us | **241** | 39.3 |
| `KEY_PART_RE.test` x41 | 3.69 us | 90 | 27.0 |
| `FLOAT_REGEX.test` x5 | 0.79 us | 157 | 40.1 |
| `replace(/_/g,'')` x16 | 0.85 us | 53 | 45.0 |
| `LEADING_ZERO.test` x16 | 0.39 us | 24 | 18.9 |
| **total** | **9.5 us** | | **3.0 us** |

9.5 us is **20% of the 47.05 us parse**, which lands on § 2's independent JFR
reading (20.3% + 2.2%) — two instruments, one answer. Put the other way, the JVM
arm spends **42% of Node's ENTIRE parse budget** inside `java.util.regex`, and
the engine gap alone is **6.5 us = 27% of the whole JVM-vs-Node difference**.

### It is the pattern SHAPE, not the number of calls

```
^\d+$                    14.7 ns     Java's Curly fast path
^\d(?:\d)*$              38.4 ns     repetition body is a group -> generic Loop node
^\d(?:_?\d)*$            94.0 ns     body non-deterministic -> both branches per iteration
^(?:0xZ|\d(?:_?\d)*)$   128.1 ns     + alternation
the real INT_REGEX      283.4 ns
```

TOML's digit separators are literally `(_?\d)*`, so every numeric value walks
Java's backtracking `Loop` machinery once per character. A hand-written scan of
the same two patterns, gated to agree with the regex on the document population
plus fourteen adversarial inputs, costs **9.4 ns** (INT) and **6.7 ns** (KEY) —
**25x and 12x**.

### Two cheap fixes are REFUSED before being built

`test` cannot observe groups, so a group-free twin of the pattern is a legal
substitution for it: measured, it buys **0.6%**. And `matches()` in place of
`find()` on the anchored patterns buys nothing. The cost is the engine's loop
node, not group bookkeeping and not the anchor scan.

### What was built, and what it measured

A matcher for the REGULAR subset those patterns live in — no backreferences, no
lookaround, no `\b` — compiled once per `(source, flags)` beside the `Pattern`
cache and answering `test` from a **lazy DFA**: one array read per character,
no backtracking at all. `JsRegexProgram` in the runtime; the parser, Thompson
construction and DFA are ~500 lines of pure Kotlin, so `kir_native_runtime.py`
copies the whole thing to Kotlin/Native verbatim.

Measured on the four patterns, one process, best of eight rounds:

| pattern | fast | `java.util.regex` | |
|---|---:|---:|---:|
| INT_REGEX | **12.69 ns** | 211.67 | 16.7x |
| FLOAT_REGEX | **12.95** | 168.38 | 13.0x |
| LEADING_ZERO | **8.39** | 25.60 | 3.0x |
| KEY_PART_RE | **26.37** | 85.32 | 3.2x |

Whole-program, three arms, 5 processes interleaved: `smol-toml` **47.05 ->
34.10 us/parse, −27.5%**, 2.08x Node -> **1.52x**, with `mitt` flat at 61.25 and
both Node arms flat. That beats the −18% predicted above, because two smaller
members came with it: `value.replace(/_/g, '')` now takes a LITERAL path
(`String.replace`, since a literal pattern's match set IS the occurrences of
that string), and `split` stopped building a fresh `Regex(source)` per call —
which also silently ignored the expression's own flags.

### Three design decisions, each of which is what makes it safe

**It answers `test` and nothing else.** Existence of a match is the one question
on which leftmost-longest — what a DFA over a state SET decides — and
JavaScript's leftmost-first agree by construction, so alternation order and
greedy-versus-lazy, the two things a DFA cannot represent, are unobservable.
`exec`, `replace` and `split` keep the reference engine.

**Anything outside the subset is REFUSED at compile time**, and the refusal is
cached like a program. So the subset can stay small and honest: `i`/`u`/`y`, a
lookaround, a backreference, `\b`, a `\u` escape, an anchor anywhere but the
pattern's own edge, `m` together with an anchor, and a `{n,m}` past the
expansion bound all fall back. A refusal is a performance outcome and never a
behavioural one.

**The reference engine stays LIVE as the differential oracle** (the round-792
shape — the specification is kept runnable and is never demoted to a legality
gate). `jsRegexVerify` runs both and throws on a disagreement, and
`KirRegexEngineTest` sweeps the document population plus the adversarial inputs
against it. The pin a differential CANNOT provide is the one that file states
separately: a matcher that refused everything would agree with the oracle on
every input, so the acceptance of the four benchmark patterns is asserted
directly.

### It found a real divergence on the way, in the OTHER engine

`java.util.regex`'s `$` also matches BEFORE a final line terminator where
JavaScript's matches only at the end, so `/^\d+$/.test("12\n")` answered `true`
here and is `false` in every JavaScript engine. The fast matcher handles `$`
structurally and always meant the JavaScript thing, which is what made the
disagreement visible — three of its first differential runs failed on exactly
that shape. `jsEndAnchorTranslated` now spells the same meaning `\z` for the
reference engine. It is the ONE place a pattern is not passed through verbatim,
and it is a divergence being closed rather than a rewrite being risked: it fires
only on a trailing anchor, and it is also what lets `jsRegexVerify` be a plain
equality.

**And it compounds on Kotlin/Native**, where the same two patterns cost 1360 ns
and 507 ns under `kotlin.text.Regex` — see § 6. The engine is carried there
verbatim; only the ORACLE underneath it is platform-specific, and the `$`
translation is deliberately NOT carried, since Kotlin/Native's own regex engine
is not known here to accept `\z`.

## 6. Kotlin/Native — the third backend, built and measured

The design doc's claim is that "JS, Native and Wasm are a change of backend
phase, not a new compiler". That is now tested rather than asserted: both
libraries compile to `-opt` Kotlin/Native binaries through the SAME
`KirProgramLowering`, and both agree with the other three arms on the sink.

Build it with

```
./gradlew :xemantic-typescript-compiler-kir:kirNativeCompile \
    -PkirProject=<dir> -PkirEntry=main.ts -PkirOutput=<path>
```

or through `scripts/kir-native.sh <project> <entry> <output>`, which is a wrapper
over exactly that task. The Gradle task resolves its own plugin classpath from
the build, so the cached-classpath staleness this repo keeps rediscovering
(CLAUDE.md rounds 852/857/858) cannot arise, and it carries the positive control
below.

### The four-arm run, one assembly of each library, 5 processes interleaved

| | mitt (4M emits) | | toml (20k parses) | |
|---|---:|---|---:|---|
| tsgo -> JS -> node | 82.50 ns/emit | baseline | 22.50 us/parse | baseline |
| xtsc -> JS -> node | 83.25 | 1.01x slower | 22.30 | 1.01x faster |
| xtsc -> JVM -> java | **60.75** | 1.36x faster | **45.50** | 2.02x slower |
| xtsc -> NATIVE -> kexe | **353.25** | 4.28x slower | **163.30** | 7.26x slower |

Ranges were tight on every arm (native `[1404..1437]` and `[3237..3304]` ms), and
all four arms produced identical sinks.

**RE-TAKEN 2026-08-21 after § 5's regex engine, and the native arm is now part of
`kir-bench.sh` rather than a run by hand** — `KIR_BENCH_NATIVE=1`, gated and
timed like every other arm ((KIR.NATIVE.1)(c)):

| | mitt | | toml | |
|---|---:|---|---:|---|
| tsgo -> JS -> node | 82.25 ns/emit | baseline | 22.20 us/parse | baseline |
| xtsc -> JS -> node | 83.50 | 1.02x slower | 22.20 | 1.00x |
| xtsc -> JVM -> java | **61.00** | 1.35x faster | **33.65** | 1.52x slower |
| xtsc -> NATIVE -> kexe | **354.75** | 4.31x slower | **126.55** | 5.70x slower |

Native toml is **163.30 -> 126.55 us/parse, −22.5%**, and 7.26x -> **5.70x**.
Native mitt is flat at 354.75, which is the control saying this is the regex
lever. **The prediction below was directionally right and quantitatively
over**: it said native "should gain MORE than the JVM's −27.5%" and native
gained **less** (−22.5% against −27.5%), because the ~50 us estimate of regex
per native parse was itself high — 36.75 us came out, so the remaining share is
boxing, which dominates everything on this backend and is what (KIR.PERF.1) is
for.

### Why, priced primitive by primitive

One source, both backends, every result consumed so neither may delete the work
(both arms compute the same 18-digit sink):

| runtime primitive | JVM | Native | |
|---|---:|---:|---:|
| `jsAdd(Any?, Any?)` | 0.95 ns | 28.05 ns | 29x |
| `jsStrictEquals(Any?, Any?)` | 0.63 | 10.41 | 17x |
| `jsCall1` (a `Function1` callee) | 0.86 | 12.93 | 15x |
| `jsTruthy(Any?)` | 0.77 | 9.76 | 13x |
| box a `Double` into `Any?` | 0.86 | 8.61 | 10x |
| `JsObject.get` (3-key bag) | 1.60 | 13.11 | 8x |
| `JsObject.set`+`get` | 10.59 | 42.17 | 4x |
| `JsArray` index get | 3.23 | 13.53 | 4x |
| `JsRegExp.test` INT_REGEX | 259.7 | 1360.1 | 5x |
| `JsRegExp.test` KEY_PART_RE | 93.6 | 506.6 | 5x |

**The backend's cost model is BOXING, and Kotlin/Native has no escape analysis
to erase it.** Every dynamic position in the generated program is an `Any?`; on
the JVM C2 inlines and scalar-replaces most of those boxes away, so `jsAdd` of
two numbers costs under a nanosecond, while on Native each one is a real heap
allocation — 8.6 ns to box a single `Double`. That is the whole 4-7x, and it
says something the JVM numbers could not: **(KIR.PERF.1), the nominal half, is
not a JVM optimisation. It is the difference between Native being viable and
not.**

Second, smaller, and now DONE on the JVM and CARRIED here: Kotlin/Native's
`kotlin.text.Regex` is a pure-Kotlin backtracking engine, **5.2x
`java.util.regex` and 35x V8** on INT_REGEX, so § 5's ~9.5 us of regex per parse
becomes ~50 us there — ~30% of the native parse. § 5's matcher is pure Kotlin
and `kir_native_runtime.py` copies it verbatim, so native gets it for free and
should gain MORE than the JVM's −27.5%. **MEASURED, and the prediction was
over**: −22.5%, not more than −27.5% — see the re-taken table above. The
direction was right and the magnitude was not, which is what a prediction
written before the run is for.

### What it cost to make work, because none of it is guessable

1. **No phase API.** Kotlin 2.4 has `cli/pipeline/jvm`, `.../web`, `.../wasm` and
   NO `.../native`; the native compiler still runs through `K2Native`/
   `KonanDriver`. So the relationship inverts — konanc is the driver and the
   backend rides in as an `IrGenerationExtension` loaded by `-Xplugin`.
2. **A coroutines version fight.** 1.11.0 renamed `runBlocking`'s JVM entry
   point to `runBlockingK` and the compiler bundles an older copy, which a
   parent-first plugin classloader always prefers. 1.11.0 has BOTH names, so
   putting it ahead of the compiler jar satisfies both callers.
3. **Native's IR validator rejects public fields.** The JVM backend accepts the
   public static fields the lowering emits for module-level variables.
4. **KLIB SERIALIZATION SEES ONLY THE FRONTEND'S FILES.** This is the one that
   fails silently and late: a file the plugin ADDS to the module fragment is
   dropped whole, the binary still links, and it dies at run time with an
   `IrLinkageError` naming a symbol the validator had just accepted. Every
   generated declaration is re-parented into the seed's own file — which then
   needs per-file name prefixes, because eight lowered TypeScript files
   contribute eight `moduleInit`s to one package.
5. **The entry point is resolved by the FRONTEND**, which never saw the generated
   `main`, so `-e program.main` answers "could not find" however valid the IR is.
   The seed declares the `main` konanc finds; the plugin gives it a body.

### The runtime is GENERATED, not forked

`scripts/kir_native_runtime.py` derives the native runtime from the JVM one on
every build, replacing exactly what Kotlin/Native lacks — `java.math.BigInteger`
(a runtime `JsBigInt`), `java.time` (days-from-civil arithmetic),
`java.util.regex` (`kotlin.text.Regex`), a concurrent map, `String.format`,
`Character.digit`, code-point handling — and REFUSING the `java.lang.reflect`
member fallback rather than approximating it. Every replacement is anchored on
text that must occur exactly once, so a drifting JVM runtime fails the
derivation instead of silently forking it.

### The one guard, and the ablation that shows it discriminates

The plugin is found by `ServiceLoader`, and its `META-INF/services` file is a
RESOURCE — which Gradle stages in `build/processedResources/jvm/main`, not in
the classes directory. Omit it and **konanc exits 0 having compiled the empty
seed**: no error, no warning, a binary the size of a hello-world. So the task
requires the plugin's own stderr announcement and fails without it.

Ablated both ways to check it discriminates rather than decorates: removing the
resource DIRECTORY is caught by konanc itself (`plugin classpath entry points to
a non-existent location`), and removing only the registrar DECLARATION — the
path exists, the plugin does not load — is caught by nothing else and fails with
`the KIR plugin did not run`.

### What is NOT done

No `.d.ts`-driven interop, and the dynamic-member fallback throws.

**The native arm IS in `kir-bench.sh`'s equivalence gate as of 2026-08-21**
((KIR.NATIVE.1)(c)): `KIR_BENCH_NATIVE=1` builds both binaries through the same
`kirNativeCompile` task, gates their `sink=` with the other three arms and times
them in the same interleave. It is opt-in because the build is two konanc links
on a box with ZERO swap — never because the evidence is optional — so the run
prints the arms it ACTUALLY ran (`building 4 arms (tsgo xtsc kir nat)`) rather
than a fixed count, and a native build that fails REFUSES the run.

One trap it cost to find: **konanc appends `.kexe` to whatever `-o` names**, so
a check on the path handed to `-PkirOutput` is a check on a file that never
exists — and `kirNativeCompile` exits 0, since it verified the plugin's own
announcement rather than the file. The task's closing line says `.kexe` and was
the answer all along.

## 2026-08-28 — the four-arm benchmark on a second host, and what asking for it found

`KIR_BENCH_NATIVE=1 scripts/kir-bench.sh 3` on macOS/aarch64 (8 cores, 16 GB),
konanc 2.4.10. **Equivalence gate green on all FOUR arms for both libraries**
before any timing (`mitt:sink=128000000`, `toml:sink=-5440000`).

| | mitt (4 M emits/round) | | toml (20 k parses/round) | |
|---|---:|---|---:|---|
| tsgo -> JS -> node | 184 ms | baseline | 195 ms | baseline |
| xtsc -> JS -> node | 184 ms | 1.00x | 194 ms | 1.01x faster |
| xtsc -> JVM -> java | **179 ms** | **1.03x faster** | 382 ms | 1.96x slower |
| xtsc -> NATIVE -> kexe | 972 ms | 5.28x slower | 1058 ms | 5.43x slower |

Native against the JVM is **5.43x** (mitt) and **2.77x** (toml), which reproduces
this page's § 6 "4-29x per primitive, 4-7x whole-program" on a different host OS
and CPU. The JS control sits on tsgo's in both libraries, which is what licenses
reading the other two rows as BACKEND numbers.

### Asking for the native arm found three defects, two of them mine

The native runtime is GENERATED from the JVM one by `scripts/kir_native_runtime.py`,
and **nothing in `jvmTest` exercises it** — native targets are off by default and
the bench arm is opt-in. So a JVM-side runtime change can break it silently, and
this is the first run since (LIB.4) landed thirteen capabilities:

1. **The generator REFUSED** — `toFixed`'s `Locale.ROOT` fix changed a line the
   generator rewrites by anchor. That is the anchor assertion doing its job: a
   generator that skipped the rewrite would have produced a native runtime that
   still compiled and quietly meant something else.
2. **Four JVM-only APIs had no native rule** and reached konanc verbatim —
   `java.time` (the `Date` component constructor and getters), `java.util.Locale`
   (`toLocale*Case`), `System.err` (`console.warn`/`error`) and
   `java.util.regex.Matcher` (the replacer-callback overload). Rules added; the
   Date one carries a STATED divergence, below.
3. **`formatFixed` rounded ties the wrong way, and that one predates the arc.**
   `(0.5).toFixed(0)` answered `"0"` natively against `"1"` on Node and the JVM.
   `kotlin.math.round`'s tie behaviour is not the same on every target, and
   ECMAScript is explicit: of the two integers equally close, `toFixed` takes the
   LARGER. Spelled out now rather than delegated.

### The one STATED divergence

`new Date(y, m, …)` and the `getFullYear`/`getMonth`/… family are LOCAL time in
JavaScript, and the JVM runtime honours that. **Kotlin/Native's standard library
has no timezone database, so the native side computes them in UTC** — the two
agree exactly when the host is UTC and differ by its offset otherwise. Refusing
on native (the precedent the reflect fallback sets) was rejected because the
shape that reaches it is a YEAR round trip, `new Date(parseInt(s), 1)
.getFullYear()`, which no offset moves.

### The check that found (3), and how to repeat it

Corpus programs 20, 24 and 25 concatenated into one project, compiled with
`scripts/kir-native.sh`, run, and DIFFED against `node` running the same `.ts`.
21 lines, byte-identical after the fix, with the `warn`/`error` lines correctly
on stderr and absent from stdout. **That differential is the only thing here that
tests the generated runtime's ANSWERS rather than its ability to compile** — the
bench's own gate covers only what mitt and smol-toml exercise, and neither
touches any of the four rules above.
