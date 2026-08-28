# Compiling a real library: what it took

**Two real libraries now compile to JVM bytecode through this backend and RUN**
(2026-08-21). The page below is kept in the order it was written, because the
first half is a prediction that the second half corrects.

| library | what it is | result |
|---|---|---|
| `mitt` 3.0.1 | the event emitter, 123 lines, one file | compiles and runs — twice: as a concatenated corpus program, and as a real MODULE a second file imports |
| `smol-toml` | a hand-written TOML parser, 1,082 lines across seven files | compiles and PARSES a 40-line TOML document, checked against **Python's `tomllib`** |

Both are their own published source, unmodified. The TOML acceptance is
differential — the expectation comes from a second, independent TOML
implementation — so it checks the compiled library against another parser
rather than against itself.

The checker reports **zero errors** on both, which is what tsgo 7.0.2 reports.
Getting there closed six checker defects, every one of them a false positive or
a silent false negative that a corpus of one codebase's style could not have
shown (see § "What this changes about the plan" and the round notes).

---

Measured 2026-08-20 on branch `spike/ts-to-kotlin-ir`. Two real, dependency-free
TypeScript libraries fetched from source and checked with xtsc, with **tsgo
7.0.2 as the oracle** (it is on the box and runs).

## The result

| library | files | tsgo errors | xtsc errors | xtsc excess |
|---|---|---|---|---|
| `yaml` 2.7.0 (library proper) | 76 | 5 | 71 | **~66** |
| `zod` 3.24.1 | 84 | 19 | 146 | **~127** |
| `knip` (2026-08-22) | 498 | 23 | 2,634 | **~2,611** — 94.1% one defect, see the 2026-08-22 update |

The remaining tsgo errors are environmental in both cases — `@types/node` is
absent offline (TS2591/TS2307), which is a known local condition, not a defect.

**The blocker to compiling a bigger library is the FRONT END, not the backend.**
That is worth stating plainly, because it is the opposite of what the spike's
own risk list predicted: the lowering refuses constructs loudly and could be
extended incrementally, but a checker that reports ~0.9 false positives per file
cannot be lowered from at all — the backend's own rule is that it must refuse to
emit a program the checker rejected.

## Why this is not a contradiction of the dashboard

xtsc is at **zero false positives on all eight tsc-source profiles**, and has
been since round 481. That is a real achievement and it is also the exact shape
of the problem: the corpus that drove conformance is *one codebase's style*.
`tsc`'s own sources are interface-heavy, factory-function-heavy and written by
people who avoid the parts of TypeScript that are hard to check. A different
real library exercises different parts, and the generalization gap shows up
immediately.

This is the already-parked "any TypeScript project" scope, now with a number
attached to it.

## The two families that dominate

Both are single, well-defined causes rather than a long tail, which is the good
news — roughly 37 of `yaml`'s 66 are the first family alone.

**1. Contextual typing does not reach into literal members.** A fresh literal
widens where tsc keeps it:

```
Type 'string' is not assignable to type '"PLAIN" | "QUOTE_DOUBLE" | "QUOTE_SINGLE"'
Type '{ value: string; type: null; comment: string; range: number[] }'
  is not assignable to
     '{ value: string; type: BLOCK_FOLDED | BLOCK_LITERAL | null; comment: string; range: Range }'
```

The second one shows the array half of the same cause: `range: number[]` against
a tuple-typed `Range`. An array literal in a tuple-typed position is not
contextually typed as a tuple, so it widens to an array.

**2. Union member access where narrowing did not apply.**

```
Property 'contents' does not exist on type
  'Alias | Scalar<any> | YAMLMap<unknown, any> | YAMLSeq<any> | Document | null'
```

The code has narrowed with a type guard; xtsc has not. This is the same weakness
CLAUDE.md already records from the other side — `getPropertyOfType`'s union arm
demands the property on *every* constituent and then returns the first — and it
is directly relevant to the backend too, since a member access on a union
receiver is exactly what union erasure must lower.

## Both families, root-caused to a minimal repro

Neither needed a debugger — pointing the compiler at unfamiliar code and
differencing against tsgo localised both in minutes.

### Family 1 — a `readonly` property with a literal initializer widens

```ts
class C { static readonly A = 'A'; readonly b = 'B' }
const viaConst = 'A'
let fromConst: 'A' = viaConst       // clean: the `const` rule works
let fromStatic: 'A' = C.A           // TS2322: Type 'string' is not assignable to type '"A"'
let fromInstance: 'B' = new C().b   // TS2322, same
```

tsgo: clean. xtsc: two errors. The literal-retention rule exists for `const`
(that control passes) and is simply absent for `readonly` class properties.

**FIXED and RE-MEASURED (WIDEN.1)(b).** The rule landed as tsc's
`isDeclarationReadonly` half of `getWidenedLiteralTypeForInitializer`, and `yaml`
went **71 -> 66** errors with none added.

**The "roughly 37 of 66" estimate above was wrong for THIS rule and is corrected
here — measured, it is worth 5.** The ~37 belongs to the broader literal-retention
FAMILY, of which the readonly property is only one member; its dominant member is a
different site entirely — **24** of the remaining rows are RETURN-POSITION literal-
union retention in `parse/cst.ts`:

```ts
export function tokenType(source: string): TokenType | null {
  switch (source) {
    case BOM: return 'byte-order-mark'   // TS2322: 'string' not assignable to 'TokenType | null'
```

So the next unit of work in this family is return-position contextual literal
retention, not anything further about properties. The lesson for this page: an
error-count attributed to a family by inspection is a hypothesis until one member
of it is fixed and the corpus re-run.

### Family 2 — `instanceof` does not narrow a class imported from another file

```ts
// nodes.ts
export class Doc { contents: string | null = null }
export class Alias { alias = 'a' }

// use.ts
import { Doc, Alias } from './nodes'
export function f(x: Alias | Doc) {
  if (x instanceof Doc) console.log(x.contents)   // TS2339: Property 'contents' does not exist
}
```

tsgo: clean. xtsc: one error per site. The **same code in a single file narrows
correctly** — a five-arm ladder over union shapes (nullable, aliased, three
constituents) is entirely clean when the classes are declared locally. So the
defect is cross-file class identity in the narrow, not the union shape and not
the guard form: an imported user-defined type guard and a bare `instanceof`
fail identically.

A cosmetic sibling shows up in the same output: with `type N = Alias` in scope,
the union `Alias | Doc` *renders* as `N | Doc`, so the alias display table is
naming a constituent after an alias that was not written at that position.

## UPDATE 2026-08-21 — a complete library now compiles AND RUNS on the JVM

**mitt 3.0.1 — a real, published, dependency-free TypeScript library — compiles
to JVM bytecode through this backend and its event emitter works.** Twice over,
and the second is the one that counts:

| | what it proves |
|---|---|
| corpus `12-mitt.ts` | the library's own `src/index.ts`, unmodified, plus a driver appended — compiles, runs, stdout matches byte for byte |
| `ProjectCorpusTest` | the same library as a MODULE: `tsconfig.json`, `src/mitt.ts`, and a `src/main.ts` that says `import mitt from './mitt'` — one program, nothing concatenated |

The checker reported **zero errors** on mitt. For this library the front end was
never the obstacle; the backend was, and the ordering below is what closed it.

### The capability ladder, in the order the libraries forced it

Each rung was found the same way — point `LibraryProbe` at the source, read the
first refusal, which names a file, a line and a column — and each is a corpus
program that compiles to bytecode and runs:

| # | capability |
|---|---|
| 09 | **arrays**: `T[]`, `Array<T>`, `ReadonlyArray<T>` and every tuple erase to one runtime `JsArray`; members are found by the receiver's ERASED type, not by the checker's rendering of its TypeScript one |
| 10 | **closures**: an arrow is a Kotlin lambda; every function type erases UNIFORMLY to `FunctionN<Any?, …, Any?>`, because TypeScript's function assignability is bivariant and the JVM's is not |
| 11 | **object literals and interfaces**: the property-bag erasure — the DYNAMIC half of the hybrid, taken first because §7 of `kir-structural-typing.md` measured it as 12× the nominal half |
| 12 | **mitt**: `Map`/`Set` as runtime classes, `jsCall`'s arity adaptation, optional parameters and defaults, parameter assignment, `>>>` |
| — | **modules**: a project directory compiled as one program |
| — | **module bodies that RUN**: a `moduleInit` per file, called in dependency order, and module variables as JVM statics reached across files through accessors |
| — | **classes 2**: `extends` (a generated class OR a runtime one), `super(…)` and `super.m()`, overrides through a base-typed reference, statics, accessors, `instanceof` |
| — | **the dynamic operations**: `jsGet`/`jsSet`/`jsInvoke`/`jsIndexGet`/`jsIndexSet`, for a receiver the checker typed `any` |
| — | **the rest smol-toml needed**: `RegExp`, `Date`, `Error`, `enum` (inlined constants), `bigint` literals, destructuring with defaults at both levels, optional chaining, overloads, `typeof` as an operator, the comma operator, `void`, statements before `super(…)`, and `Math`/`Number`/`Object`/`JSON`/`String` globals |

Two decisions inside that are worth carrying forward.

**A lib type never erases to a property bag.** `Map` is declared in a lib
`.d.ts` as an interface structurally indistinguishable from one the program
could have written, and erasing it to a bag would make `m.size` read `undefined`
and every method call fail inside the runtime — silently, in a program that
compiled. Only a declaration whose root file is one of the PROGRAM's own may
become a bag; a lib type reaches a runtime class through a short explicit table
or is refused.

**A union of function types has no single erasure.** mitt's own
`Handler | WildcardHandler` is a `Function1` on one side and a `Function2` on
the other, and JavaScript calls either with whatever the site supplies. So a
call whose callee is a union of callables, an `any`, or a property-bag member
goes through the runtime's `jsCall`, which pads missing arguments with
`undefined` and drops extra ones. This is a deliberate widening of the
"refuse rather than pretend" rule, and it is confined to calls the checker
itself treats dynamically.

### And it found a checker defect of the silent kind

`export default function f() {}` is neither an `ExportAssignment` nor bound
under the name `default` — it is bound under its OWN — so the default-import
alias resolved to nothing, i.e. to `any`, and every misuse of a default-imported
function went unreported. Measured against tsgo 7.0.2 on two files:

```ts
// d.ts
export default function twice(x: number): number { return x * 2 }
// main.ts
import twice from './d'
const probe: string = twice(1)   // tsgo: TS2322.  xtsc, before: SILENT.
```

Fixed, with the whole suite green (15,448 tests). Named imports were never
affected, which is what makes it a property of the default import rather than of
imports — and it is exactly the shape this page predicted: a corpus that drove
conformance is one codebase's style, and `export default` is not tsc's.

## What this changes about the plan

**Amended by the update above, and the amendment is a correction of THIS
section.** The ordering below was derived from `yaml` and `zod` alone, and it
generalized one library's obstacle into a rule: it says extending the lowering
"buys nothing while the checker cannot get a real library to zero". Measured on
a third library, that is false in both halves — mitt reached zero checker errors
untouched, and every step between "type-checks" and "runs" was backend work. So
the rule is really per-library, and the cheap way to know which obstacle a
candidate has is to ask both compilers before planning anything:
`tsgo --noEmit -p <dir>` for the front-end half, `LibraryProbe` for the backend
half.

The `yaml` measurement moved a long way under that method, without anyone
working on `yaml`: **80 -> 24 errors** (4 of them the absent `@types/node`, i.e.
environmental) purely from the defects the other libraries exposed —
cross-module `instanceof`, imported type guards, const-arrow guards, the
default import, return-position literals, module-level `const` literals, object
literals typed under their target's shape, and assignment narrowing over a
computed primitive. TS2339 on a union, 21 rows at the start, is now zero.

For `yaml` and `zod` specifically, the ordering below still holds:

1. **Pick one library as a second conformance corpus** — `yaml` is the right
   size: 76 files, no dependencies, a real parser/serializer, and only two FP
   families between it and clean.
2. **Close those two families**, both of which are already-known weaknesses
   rather than new discoveries.
3. **Then** extend the lowering, driven by what that library actually contains.

Note the differential itself is cheap and repeatable — `tsgo --noEmit -p <dir>`
against the same directory — so "is library X ready" is a question with a
one-command answer, and a second corpus can be adopted without guessing.

## UPDATE 2026-08-22 — knip: the front end is one defect, the backend is the wrong question

`webpro-nl/knip` at `main` (`packages/knip`: **498 files, 35,663 lines**) was put through
the two-command loop this page prescribes. It does **not** compile, and the two halves fail
for unrelated reasons — which is exactly why the loop exists.

| | xtsc | tsgo 7.0.2 |
|---|---|---|
| errors, knip's own tsconfig | **2,634** (7,131 ms) | **23**, all environmental |
| errors, same tsconfig minus `verbatimModuleSyntax` | **156** | 23 |

**94.1% of the front-end count is one absent lookup.** TS1295×1,959 + TS1287×519 = 2,478,
every one saying "this is a CommonJS file" about a package whose `package.json` says
`"type": "module"`. Under `moduleResolution: nodenext` tsc derives a file's module format
from the nearest `package.json`; we do not, so the format defaults to CommonJS and
`verbatimModuleSyntax` rejects every import and export in the program. Deleting that one
option reads 2,634 -> 156, which is what turns the attribution from a hypothesis into a
measurement — and this page's own § "Family 1" is the standing warning that an error count
attributed to a family by inspection is a hypothesis until one member of it is fixed.

> **SUPERSEDED 2026-08-25 by (CHK.30) — the TS7006 attribution below is WRONG, and knip now
> reads 156 -> 66 errors with TS7006 89 -> 1.** Those 89 rows were not "an object-literal
> shorthand method's parameters are not contextually typed": that shape, written out by hand,
> has always been silent here. A type imported from a `node_modules` PACKAGE resolved to `any`
> — knip's `PluginVisitorObject` is `VisitorObject` from `'oxc-parser'` — because the checker
> re-derived module resolution by string-matching a specifier against the program's own file
> NAMES, which cannot express a bare specifier. **This page's own standing warning is what
> caught it**: an error count attributed to a family BY INSPECTION is a hypothesis until one
> member of it is fixed, and here fixing a member refuted the family. The measurement was
> retaken on `webpro-nl/knip@main` with its 20 dependencies fetched from npm; the pre-fix
> binary reproduces the 156/89 recorded below EXACTLY, which is what licenses the delta.
> Residual now **0.13 FP/file**, and no row appeared that was not there before.

**The residual is 0.31 FP/file — better than `yaml`'s 0.9 when this page was written — and
it is this page's two families.** TS7006×89 (57%) is the METHOD-member half of family 1:
an object-literal shorthand method's parameters are not contextually typed from the
annotated return type. TS2339×23 is family 2. **The overlap with tsgo's set is zero in both
directions**, so the honest figure is 156 false positives *and* 23 false negatives —
including two real TS2322 and a TS2722 in `util/glob-core.ts` that tsgo reports and we do
not. A residual FP count is not a conformance number until the misses are counted too.

**Module resolution passed, on a shape nothing here was written for.** All **1,921**
relative specifiers carry an explicit `.ts` extension (`allowImportingTsExtensions` +
`rewriteRelativeImportExtensions`) and every one resolved.

**The backend never gets a turn, and its ladder is the wrong thing to cost.** Probing one
self-contained file (`src/util/graph-sequencer.ts`, 131 lines, no imports, `typeErrors=0`)
refuses at `a spread element is out of the spike subset`; censused against the 17 refusal
messages, the refused constructs touch **237 of 498 files (48%)** — destructuring parameter
51%, spread 33%, destructuring declaration 24%, `async`/generators 22%. But knip imports
**two native Rust N-API binaries** (`oxc-parser`, 32 sites; `oxc-resolver`) and **10 `node:`
builtins** (`fs`×21, `fs/promises`×5, `util`, `path`, `module`, `crypto`, `url`, `process`,
`perf_hooks`, `child_process`) against a `libraryClass` table of **six** entries. Those have
nothing to lower *to*; closing every refusal on the list would not move them.

**So the screening question this page should have asked first is what a candidate
IMPORTS, not how big it is.** One `grep -rhoE "from '[^.'][^']*'"` over `src` answers it in
a second, and it disqualifies knip as a backend driver before any compiler is run. knip
remains an excellent FRONT-END corpus for exactly the reason this page gives: it is a
different codebase's style, and it found a 2,478-error defect that tsc's own sources — not
`"type": "module"` — structurally cannot show.

## UPDATE 2026-08-22 (b) — six CLI candidates screened, and the errors named

Following knip, six TypeScript libraries with a CLI were fetched and put through the loop.
The import census disqualified `sql-formatter` (`nearley` imported inside `src`) with no
compiler run. Measured with `@types/node` present on both sides, each library's own
tsconfig, diffed against tsgo 7.0.2 per `(file, line, code)`:

| library | files | lines | deps | tsgo | xtsc | ours-only | files w/ a refused construct |
|---|---|---|---|---|---|---|---|
| **cronstrue** | 52 | 8,812 | none | **0** | **0** | **0** | **2 (3%)** |
| marked | 13 | 3,706 | none | 0 | 15 | 15 | 10 (76%) |
| jsonrepair | 10 | 2,746 | none | 1 | 16 | 16 | 9 (90%) |
| fflate | 3 | 3,904 | none | 2 | 17 | 17 | 3 (100%) |
| yaml | 78 | 10,878 | none | 0 | 78 | 78 | — |

**`cronstrue` is the first library outside the corpus on which this checker agrees with tsgo
exactly**, and its lowering runs to a first refusal rather than being blocked at the front
end. Five rungs separate it from a running program — rest parameters, `for…of` array
destructuring, `var`, `??`, and `String.replace(re, fn)` — and the count stayed flat as they
were peeled.

**The screen adds a criterion this page did not have: the library closest to COMPILING and
the library best for BENCHMARKING are different libraries.** `cronstrue` is the former;
`marked` (markdown → HTML) is the workload worth publishing a number for; `fflate` would be
the best number of all — DEFLATE is tight numeric loops — and is structurally blocked by 183
typed-array uses against a runtime that has none.

**126 false positives, five families, 67 rows.** Each was reduced to a repro or an exact
correspondence rather than to an inspection, and they are queued as (CHK.31)-(CHK.35):

1. **`// @ts-ignore` and `// @ts-expect-error` suppress nothing**, in both directions — the
   directive does not filter, and an unused `@ts-expect-error` does not produce TS2578. All
   9 of `fflate`'s TS2391 rows, matching its 9 `@ts-ignore` comments exactly. The feature
   *appears* implemented: both spellings are parsed as directives and one is consulted for a
   narrow commonjs suppression.
   **CLOSED 2026-08-25, (CHK.31)**: the general filter now lives at `Checker.getDiagnostics()`
   and TS2578 with it, both scoped to the files the checker WALKED. The blocking defect was a
   suppression written at an EMITTER (the commonjs relative-import branch skipped its own
   TS2307 when a directive sat above it, so the directive marked nothing and read as unused).
   The `fflate` screen was NOT re-run — the library sources are not on this box — but its exact
   shape is pinned in `CommentDirectiveSuppressionTest` and matches tsgo 7.0.2 row for row.
2. **A primitive is not related to a structural object target through its apparent type** —
   `string` against `interface Text {…}` (all 7 of `jsonrepair`'s TS2345 rows), and equally
   `number` against `{ toFixed(d?): string }`.
3. **A destructuring parameter breaks arity**, printing the inverted `Expected 1-0 arguments,
   but got 1` — 8 rows in `marked`, and round 921's recorded `getParameterSymbols` hazard
   reaching a diagnostic for the first time.
4. **`isolatedDeclarations` over-reports** — 32 rows on `yaml`, which ships with the flag on
   and is clean under tsgo.
5. **A function expression assigned through an index signature gets no contextual signature**
   — TS7019 + TS2683×4 in `marked`; possibly one path with the object-literal-method case.

~59 rows remain untriaged, led by TS2322×14 and TS2339×7. Stated rather than implied, because
this page's own history is that a family attributed by inspection is a hypothesis.

## UPDATE 2026-08-28 — `cronstrue` COMPILES; what stops it is the nominal half

(LIB.4) was worked to its end. **`cronstrue`'s English entry point — 11 files,
its own published source unmodified — now lowers and compiles to JVM bytecode**
(`successful=true`), with the checker at **0 errors agreeing with tsgo 7.0.2
exactly**. It fails at RUN time, twice for one reason, and that reason is not on
any ladder:

```
Can not set JsObject field program.ExpressionDescriptor.i18n to program.en
```

A generated **class** instance cannot flow into an **interface**-typed slot: an
interface erases to the property bag while a class is a nominal JVM class. That
is `docs/kir-structural-typing.md`'s candidate (1) — measured there at **158
`implements` edges** on tsc's own sources with a max fan-out of 9, and never
built, because § 7 priced the dynamic half at 12x the nominal one and it was
taken first. **So the remaining work for this library is one named architectural
milestone, not a queue of small gaps.**

### The ladder was twice as long as the queue's five, and its own list was stale

The queue named five rungs. Thirteen capabilities were needed, and the five it
named were rungs 1, 3, 4, 5 and (out of order) 2:

| # | capability | corpus |
|---|---|---|
| 1 | rest parameters, in all three positions | 17 |
| 3 | `var` FUNCTION scoping and hoisting | 18 |
| 4 | `??` | 19 |
| 5 | the replacer-CALLBACK overload of `String.replace` | 20 |
| — | `for…in` | 21 |
| 2 | `Object.entries`/`values`, destructuring in a loop head | 22 |
| — | `<T>expr` | 23 |
| — | `console.warn`/`info`/`debug`, and WHICH STREAM each writes to | 24 |
| — | `substr`, `sort`, `new Date(y, m, …)`, `toLocale*Case` | 25 |
| — | callback ARITY at the STORAGE side | 26 |
| — | the array callbacks' `(element, index, array)` argument list | 27 |
| — | a member call on a receiver whose recorded type is the nullish union | 28 |
| — | `new Array(n)`, `export default X`, a static method as a VALUE | 29, 18 |

**Why the queue's list was short: it was peeled by patching a throwaway copy,
which walks past whatever the patch removed.** Re-probing the UNMODIFIED library
after each fix is what found the other eight.

### Five defects, none of them a missing capability

Every one is a silent wrong answer, and four were invisible to every gate here:

1. **`for (let j …)` had no per-iteration binding.** Every closure the loop made
   shared one variable, so `fns[0]()` answered `3` where JavaScript answers `0`.
   Found because corpus 18 runs the `var` and `let` spellings SIDE BY SIDE — the
   `var` answer (`3,3,3`) is correct and the `let` answer is not, and only the
   pair shows it.
2. **`toFixed` used the machine's locale.** `(2).toFixed(1)` answered `"2,0"` on
   a comma-locale box where JavaScript defines `.` everywhere. Invisible on
   en-US, so CI could never have caught it; it appeared on a PLAIN `number`
   receiver, so it predates this arc entirely.
3. **The array callbacks were typed to take a `Function1`**, truncating
   JavaScript's `(element, index, array)`. Before this arc `map((v, i) => …)` was
   REFUSED; with the new arity adapter it would have started dropping the index
   SILENTLY — the same defect with no diagnostic.
4. **This arc's own `var` hoisting emitted into the wrong body.** `blockBodyOf`
   is the funnel for every body the lowering builds, including the ones it
   SYNTHESIZES mid-expression, so `var days = { SUN: 0, … }` put the hoisted
   declaration into the constructor of the shape class its own initializer had
   just created. Only the IR validator saw it.
5. **A checker false positive**, still open: `probe = 7; var probe: number;
   return probe;` draws TS2454 here and nothing from tsgo — an assignment BEFORE
   a `var`'s declaration does not count toward definite assignment. The mirror
   (assignment after) is silent, so it is that direction specifically.

### The method, restated

`node` runs a `.ts` file directly, so **every corpus `.expected` in 17-29 is a
JavaScript engine's own output** rather than a reading of the specification —
and each program is written so that the INTUITIVE implementation fails it
(`[10, 9].sort()` is `10,9`; `new Date(99, 0, 1)` is 1999; `substr`'s second
argument is a length). The one exception is 23, whose oracle is the `as`-spelled
twin, because node's type stripper will not parse `<T>expr`; deriving it that
way IS the claim under test.

### The benchmark, with the arm that is missing named

The KIR runtime benchmark's **arm 2 (xtsc -> JVM bytecode -> java) does not exist
for this library**: it needs a program that RUNS, which is what (LIB.6) blocks.
What was measured is arms 1 and 3 — tsgo's JavaScript and OURS, on the same
engine — which is `kir-bench.sh`'s own CONTROL, and the arm that separates the
front end from the backend.

Workload: one description of each of twelve cron expressions (parser plus the
whole description pipeline), 24,000 descriptions per round, best of 10 rounds
after 6 warm-up rounds. Driver `scripts/kir-bench/drivers/cronstrue-main.ts`.
**The equivalence gate passed in every process — `sink=11904000`, the accumulated
description lengths, identical across all 24.**

| batch | rotation | tsgo -> JS -> node | xtsc -> JS -> node | ratio |
|---|---|---:|---:|---:|
| 1 | tsgo leads | 126.0 ms | 127.0 ms | 1.008x |
| 2 | xtsc leads (mirrored) | 126.5 ms | 126.5 ms | 1.000x |

Six processes per arm per batch; per-arm sd 0.8-1.5%, so the two ratios sit
inside each other's noise and the honest reading is **parity**, not "0.8%
slower". That is a third library agreeing with the 1.01x/1.02x already recorded
for `mitt` and `smol-toml`, on a different workload shape — cron parsing and
string formatting rather than event dispatch or scanning. **5.25 us per
description** on this box.

**Compile time, same library (52 files, 8,812 lines, `--noEmit`):**

| | median | n | note |
|---|---:|---:|---|
| tsgo 7.0.2 | **42.2 ms** | 15 | native Go binary, INCLUDES its own process startup |
| xtsc, warm | **153 ms** | 10 after 6 warm-ups | in-process rebuild, EXCLUDES JVM startup |
| xtsc, cold one-shot | 1.54 s | 5 | the JVM-startup story, not a throughput one |

**3.6x on the warm pair, and the comparison is generous to us** — tsgo's number
carries its startup and the warm xtsc number does not. It sits beside the
dashboard's 3.09x for warm check-only on tsc's own sources, i.e. this codebase is
slightly worse for us than the corpus one, which is the direction § "Why this is
not a contradiction of the dashboard" predicts.
