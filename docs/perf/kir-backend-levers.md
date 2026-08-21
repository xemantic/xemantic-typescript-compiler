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

## 4. Where it stands, replicated

The session's last three benchmark runs differ only in the neutral §3a change,
so they are three draws of one number: **46.95, 48.00 and 47.75 us/parse**, the
last on the committed tree. Against the session's opening **56.60**, that is
**−15.6%** taking the committed run and −15.2% to −17.0% across the three.

| | tsgo -> node | xtsc -> JVM, opening | xtsc -> JVM, committed |
|---|---|---|---|
| mitt | 86.25 ns/emit | 62.25 | **62.25** (1.39x FASTER) |
| smol-toml | 22.30 us/parse | 56.60 | **47.75** (2.14x slower) |

`smol-toml` goes from 2.49x slower than Node to **2.14x**. **`mitt` is flat**:
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
