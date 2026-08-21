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
