# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **199,416** lines (191,070 when the metric was created; the (P18.9)-(P18.37) checker-parity arc ADDED ~5,200, which are fixes and pins, not extractions — the metric counts extraction progress and this arc made none) (was 191,155 at the metric's creation; +107 of those are
(INV.1)'s store hook and +192 (INV.2)'s companion channels, helpers and lens — ADDITIONS, not extractions;
3 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both — and `TypeInstantiator`, whose ambient row is the first non-none one: FOUR
checker reads (the fourth, `instantiateTupleElements`, added by (P18.28)), one table write,
stated in the ledger). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

**(P18.48) — THE ENUM AND NULLABLE-TARGET DISPLAY RESIDUES CLOSE ((CHK.92), ALL FOUR PARTS), AND A DISPLAY RULE PUT IN THE GENERAL RENDERER BROKE THE LANGUAGE SERVICE, 18,302 → 18,338 / 0 / 3 (2026-09-08).**
**All four parts LANDED**: (a) neither side of an object-literal member mismatch is widened any more
(`'6'` → `'5'`, not `'number'` → `'number'`), with tsc's per-FLAVOUR literal keep; (b) the four
MEANING rows — an enum-target object-literal member at an ARGUMENT — now report byte-exactly; (c) one
home, `nullableTargetDisplay`, implements tsc's `DefinitelyNonNullable`-gated strip AND the
optional-declaration add, correcting BOTH directions at five call sites; (d) a one-member enum's
relation-error display collapses to the parent at four positions. **The round's lesson is
architectural and cost two round-trips: a display rule specific to RELATION ERRORS must not live in
`typeToString`.** (d) was first put in the general renderer with a TS2367 bypass bolted on, and the
full suite found it had broken `Project.quickInfoAt` — a hover on a one-member enum's member went
from `Valued.Gamma` to `Valued`, destroying the distinction (API.15)'s deliberate negative control
exists to make. **The oracle was asked rather than argued**: `tsgo --lsp -stdio` answers
`(enum member) Valued.Gamma = 5` for all four one-member shapes, i.e. tsgo does NOT collapse in hover
where both references DO collapse in a relation error — one renderer cannot serve both. The rule now
lives in `relationErrorTargetDisplay` (5 relation-error heads), the bypass is DELETED, and **needing
a second per-consumer bypass is recorded as the signal a rule is misplaced**; arm a12 is now the
PLACEMENT arm, graded on the `-project` module at 3 RED. **A stale pin was INVERTED with proof** —
`ConstAssertionTest`'s r24 asserted the known-wrong `'string'` and both references print `'"b"'`
(second such pin in three rounds; a pin whose name says "residue" is a countdown). **Four of the
item's claims were wrong**: (a) names two emitters and there are three, (d)'s exception is literal
FRESHNESS in full, (c)'s "three sites" is five, and the binding constraint for (a) was a corpus PIN
WALKER matching on exact message TEXT — which **also confounded the round's own instrument**, since a
message-text marker reddens the BEFORE arm wherever such a walker exists. REFUSED with its
measurement: (d)'s TS2367 freshness half (this checker mints no fresh enum-member type, so the four
shapes share one `Type`). Two MEANING residues queued as (CHK.113). 35 pins + 1 consumer-side pin;
13 arms, all discriminating. Grid **8 × added=0 removed=0** re-run independently; `cost_gate.py`
exit 0, `huge_methods.py` exit 0, build warning-clean.

**(P18.47) — THE REST-TUPLE MODEL IS FIXED ((CHK.111)), THE ITEM'S NAMED SEAM WAS *WRONG* RATHER THAN INCOMPLETE, AND THE REGRESSION IT CAUSED WAS IN ANOTHER MODULE, 18,271 → 18,302 / 0 / 3 (2026-09-08).**
**(CHK.111) CLOSED, and the item under-counted its own defect by 9x** — it recorded "exactly 1 false
positive" at the class property; measured, a DECLARED tuple source produced a false TS2741/TS2322 at
**all five** positions (the shielding claim holds only for an ARRAY-LITERAL source), and the gain is
**10 rows, not 5**. **The named seam is WRONG, not merely incomplete**: making rest slots OPTIONAL
fixes only the `[number]` half — `[number, string]` failed because the rest MEMBER was typed
`string[]` (the whole rest array) rather than `string` (its element) — and optionality is the wrong
CHANNEL, since it also injects `| undefined` into every element read and into `tupleArrayBase`'s
union. What works is the member carrying the rest's ELEMENT type plus a SEPARATE non-required mark,
which is also why dropping the numbered member entirely (literal tsc) is refused: it would lose
`[number, number]`'s position-1 element row. **The DISPLAY half landed in full** —
`[number, ...string[]]`, `readonly [...]`, leading and middle rests all render as both references,
with an empty `[]` unchanged as the control that the ellipsis is keyed on `tupleRestIndex`.
**The regression this round caused was in ANOTHER MODULE and only the full suite could see it**:
`typeToString` feeds the externals generator's `xtsc: unmapped <type>` markers, so the ellipsis moved
three RxJS gate expectations while the grid and all ~13k corpus baselines stayed clean. Those pins
encoded the OLD, LESS ACCURATE text and were updated **with proof, not weakened** — rxjs declares
`sources: [...ObservableInputTuple<A>]`, a tuple whose single slot IS a rest, so `[any]` spelled a
FIXED one-element tuple. **Two of the three stale expectations were reported and the third was
hidden**, because a block of `assert(<local>)` calls throws at the FIRST false one — both facts are
now CLAUDE.md entries, and (P18.44)'s rest-tuple entry, which this round makes stale, was REWRITTEN
rather than left standing. 31 pins; 14 arms — a3/a4 a round-927 pair separated only by adding a
presence-only pin, **a14 first blind** (every literal index is served by the numbered member, so
only a non-literal `t[i]` reaches the index signature), **a11 REDUNDANT by whole-output diff** over
six fixtures. The implementation agent self-reported starting a second concurrent Gradle build,
detected by an impossible class sha; both affected arms were re-run alone in the foreground and no
result comes from an overlapped run. Grid **8 × added=0 removed=0** re-run independently;
`cost_gate.py` exit 0 (largest delta **+0.03%**), `huge_methods.py` exit 0, build warning-clean.

**(P18.46) — DEFINITE ASSIGNMENT JOINS A `try`/`catch` AND REACHES AN EXPRESSION-BODIED ARROW ((CHK.110)(a)/(b)), AND THE SUPPRESSOR WAS NEITHER CANDIDATE THE ITEM NAMED, 18,234 → 18,271 / 0 / 3 (2026-09-08).**
**(CHK.110)(a)/(b) CLOSED; (c) and two newly-measured gaps moved to (CHK.112).** The item named
`markAssignments` (which really has no `TryStatement` arm) and B223 (which only sees a `var`
declared INSIDE the try) as the two candidates for (a)'s silence; the suppressor is a THIRD
mechanism — **`checkUsesOfUninitialized`'s own `TryStatement` arm**, which walked the try block
against the CALLER's live frame set, so the try's assignments escaped the try statement
unconditionally. **The evidence needed no instrumentation, because the same line was wrong in the
OPPOSITE direction**: `try {} catch {} finally { d = "c" }` was an ours-only FALSE POSITIVE both
references are silent about, since that arm walks the try block ONLY. One escape, two opposite
defects; a second pre-existing ours-only row closes with it. The merge (`tryDefinitelyAssigns`) is
decided by REACHABILITY through round 450's `daWalkStmt` — neither block reaches the continuation
→ remove, only the catch → the catch's assignments, only the try → the try's, both → the
intersection, `finally` always counts. **The first design was refuted by the fixture matrix while
the GRID stayed clean**: a `tcvHasTerminator` conservatism suppressed 6 rows both references
report, and the 8-profile grid read `added=0 removed=0` on that binary AND on the guard-free one —
**the grid cannot grade conservatism**, only the reference matrix can, now a CLAUDE.md entry. (b)
is a dispatch gap whose two arms (`spineDaEnterNode` and its `SpineDispatch.enterClosure` entry)
are a round-927 PAIR reading the same 7 RED — no pin can separate "not called" from "not written".
**The item's (b) scope was wrong in one place**: a class property initializer is silent for a
BLOCK-bodied arrow, a function expression and a bare identifier alike, so that leak path is absent
entirely and is a different mechanism → (CHK.112). 37 pins, all read from pristine; 10 arms, all
discriminating. Grid **8 × added=0 removed=0** re-run independently; `cost_gate.py` exit 0 (largest
delta **+0.03%**), `huge_methods.py` exit 0, **`spine_closure_audit.py` exit 0**, build
warning-clean under `--rerun-tasks`.

**(P18.45) — AN INLINE LITERAL CALLEE GETS ITS OWN TYPE ((CHK.109)), AND THE CALLEE *EXPRESSION* IS EVIDENCE THE CALLEE *TYPE* CANNOT CARRY, 18,212 → 18,234 / 0 / 3 (2026-09-08).**
**(CHK.109) CLOSED, 1 → 15 of the reference's 16 rows**, byte-identical to pristine on every one
(`({})()`, `({ a: 1 })()`, `[1]()`, string / number / template / boolean / regex, nested
parentheses, `?.()`, explicit type arguments at exactly ONE row, and an object literal carrying a
method); an inline arrow, function expression, async arrow and every literal RECEIVER stay silent.
tsgo 7.0.2 and pristine 6.0.3 agree on all 16, so no oracle conflict arose. **The item's named
seam was right and exactly HALF the fix, and it fails on the item's own first example**:
`getCalleeType`'s `else -> anyType` is the source of the `any`, but after fixing it `({})()` and
`(/x/)()` are still refused by `calleeObjectTableIsComplete` — (CHK.45)'s rule wants positive
evidence a member table is complete, and **an empty anonymous object is exactly what a TYPE cannot
vouch for**, since `{}` from a literal and `{}` from an unfinished resolution are the same type. The
closing rule is SYNTACTIC, and arm a3's RED set is precisely the three empty-`{}` pins and nothing
else — for a non-empty literal the type-only rule already suffices. **A parser fact cost a third
leg**: `true`/`false` are reserved words the Parser renders as an `Identifier`, so `(true)()`
reaches the Identifier arm and resolves to nothing (the leg sits on the miss path, so no ordinary
callee pays for it). **Population 3 → 15**; `(class {})()` (TS2348, needs a
`typeof (Anonymous class)` naming mechanism) and `new ({})()` (TS2351, every `new`-path emitter is
`Identifier`-gated) are a DIFFERENT diagnostic, verified inert under the change and recorded as
stated refusals. **Four display divergences are made visible and are not this item's** — `[]()`
prints `any[]` for `never[]`, `[() => 1]()` loses a parenthesization, an objlit getter prints
`readonly g: any`, a computed key prints `{ ["k"]: number; }` — **all four reproduce on the PARENT
binary at a declaration position**, all FORM, all refused rather than folded in ( fixing `[]` alone
would change the empty-array-literal type program-wide). 22 pins; 4 arms ALL discriminating with
four distinct class shas, none blind or redundant; every other `getCalleeType` caller audited and
shown unable to emit for a literal. Grid **8 × added=0 removed=0** re-run independently after both
agent arms were verified byte-identical to orchestrator-built binaries; `cost_gate.py` exit 0
(largest delta **+0.03%**), `huge_methods.py` exit 0 (834 classes, 0 over), build warning-clean.

**(P18.44) — A TUPLE'S *ARITY* BECOMES EXPRESSIBLE ((CHK.108)), THE ITEM'S SEAM WAS NECESSARY AND NOT SUFFICIENT, AND THE `WORK ORDER` NOTE CLAUDE.md POINTS AT HAD BEEN ARCHIVED OUT OF THE PLAN, 18,188 → 18,212 / 0 / 3 (2026-09-08).**
**(CHK.108) CLOSED, 11 of the reference's 12 rows**, byte-identical to pristine at all five
positions (var-decl, argument TS2345, return, assignment, class property), for a readonly target,
inside a union target, and at the INNER span of a nested literal. **The item's named seam — a
contextual-tuple form of `getTypeOfArrayLiteral` — closed 0 of 12 rows on its own**: the
contextual type was never INSTALLED for a plain array literal at any of the five positions, and
`checkArrayLiteralElementsAgainstTuple` (B407) unconditionally `return true`s. **The half the item
never mentions is the ELABORATION, and it is the bigger one** — with a genuine tuple source the
arity mismatch already reached every emitter and printed the wrong thing, so a DECLARED tuple
source with no array literal anywhere printed TS2741 / TS2739 / `Types of property 'length' are
incompatible` where both references print TS2322 with an arity sub-line; `tupleArityChain`
transcribes tsc's three rungs, which carry three DIFFERENT counts, each verified row-for-row
against pristine. **The one refused row is a measured TRADE whose cause is the rest MODEL, not
this item** — dropping the rest-tuple exclusion gains 5 correct rows and introduces 1 false
positive (`class K { p: [number, ...string[]] = [1] }` → TS2741), reproduced on the PARENT binary
because our rest tuples carry the rest slot as a REQUIRED numbered member; queued as **(CHK.111)**,
which also closes the `[number, string[]]` display. **A stale hand-written pin was asserting an
answer no reference prints** (TS2739 for `[] → [number, string]`) and the corpus structurally could
not say so — its `tupleTypes.ts` baseline is served by a pin walker that WIPES and re-pins it, so
baseline and pin can disagree indefinitely with both green. 19 pins + 1 corrected; 12 arms, of
which **a5 is REDUNDANT by whole-output diff (45 rows, 7 fixtures, byte-identical)** and **a3 first
read 0 RED because the PIN SET was blind — no array literal can reach rung 3 at all**. Grid
**8 × added=0 removed=0**, re-run INDEPENDENTLY by the orchestrator after verifying both agent arms
byte-identical to binaries it built itself; `cost_gate.py` exit 0 (largest delta **+0.03%**),
`huge_methods.py` exit 0, build warning-clean. **PROCESS:** the `WORK ORDER` note CLAUDE.md tells
every agent to read was added by `cc09770a3` and archived out with the four COMPLETED items beneath
it, leaving a pointer to a heading that had not existed for ~25 rounds — restored, with an addendum
recording that the order's tail is (INV.0) while the arc has been (CHK.\*).
