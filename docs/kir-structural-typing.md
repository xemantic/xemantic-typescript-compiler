# Structural typing on a nominal JVM — the measurement

`docs/kir-design.md` § 3.3 calls structural typing "the one that decides whether
this approach survives", and picks candidate (1): each `interface` becomes a JVM
interface, and each class and object literal becomes a class that `implements`
**every** generated interface it is structurally assignable to, computed
whole-program because the checker can answer the assignability questions.

That plan was never measured. Its cost is usually stated as the N×M closure over
every (class, interface) pair in the program, which for tsc's own sources would
be thousands times thousands and obviously unbuildable. **But the closure only
has to cover the (source, target) pairs the program ACTUALLY FORMS, and those
are exactly the pairs the checker forms while checking.** This page measures
them.

Instrument: `…kir.census.StructuralCensus`, a `CheckedNodeSink`. Run with

```
java -Xmx4g -cp <kir + core + deps> \
  com.xemantic.typescript.compiler.kir.census.StructuralCensusMainKt <project-dir> [out]
```

## 1. The headline

**The whole-program nominal closure is not a problem. On tsc's own compiler it
is 158 `implements` edges, and no single generated class carries more than 9.**

Eight corpora, all of tsc's own source trees, `noEmit`:

| corpus | files | obligations | struct. pairs (all) | closure edges | max fan-out | max fan-in | wall |
|---|---:|---:|---:|---:|---:|---:|---:|
| compiler (`tsc-project`) | 78 | 64,305 | 6,032 | **158** | **9** | 51 | 24.9 s |
| `tsc` | 80 | 64,306 | 6,032 | 158 | 9 | 51 | 23.8 s |
| `services` | 252 | 93,231 | 8,690 | 226 | 10 | 64 | 29.9 s |
| `server` | 274 | 96,497 | 9,184 | 253 | 10 | 64 | 32.1 s |
| `harness` | 312 | 99,847 | 9,542 | 264 | 10 | 64 | 33.1 s |
| `jsTyping` | 84 | 64,481 | 6,060 | 160 | 9 | 51 | 23.9 s |
| `deprecatedCompat` | 81 | 64,370 | 6,041 | 158 | 9 | 51 | 24.1 s |
| `typingsInstallerCore` | 88 | 64,568 | 6,072 | 164 | 9 | 51 | 24.9 s |

"Closure edges" is the count of distinct `(source, target)` pairs that are
STRUCTURAL, have an object-ish source and an object-ish target, and are closed
(no open type parameter anywhere) — i.e. the `implements` edges a whole-program
closure would have to add, and the itab-equivalents a Go-style implementation
would build. The four small corpora are near-identical because they all pull in
the same `src/compiler` tree; the interesting spread is compiler → harness,
which quadruples the file count and raises the closure by 1.7×.

The distributions are the real answer, and they are heavy-tailed in the
direction that makes the plan cheap. On `harness`, the largest program measured:

```
fan-out per source (implements edges one generated class carries)
  <=1  186    n=208  median=1  p95=3  max=10  mean=1.27
  <=2    7
  <=4   12
  <=8    1
  <=16   2
```

**208 of the program's types need any structural `implements` edge at all, 186
of them need exactly one, and the worst — `LanguageServiceHost` — needs ten.**
The compiler profile's worst is `SourceFile` with nine.

Fan-in is heavier but still small: `TextRange` (which is `{ pos, end }`, the
shape every AST node has) draws 64 distinct sources on `harness` and 51 on the
compiler; the next-largest is 14. Nothing else is near.

## 2. Why the "43.89% STRUCTURAL" headline is not the answer

The raw edge distribution on the compiler profile looks alarming:

```
IDENTITY           24,015   37.35%
NOMINAL_SAME_DECL     836    1.30%
NOMINAL_BASE        2,529    3.93%
FRESH_LITERAL         507    0.79%
STRUCTURAL         28,226   43.89%
NOT_ASSIGNABLE      8,192   12.74%
```

Read alone, that says structural assignability is nearly half of everything the
program does — and if each of those needed a JVM interface edge, the plan would
be dead. It does not, and the reason is the TARGET. Cross-tabulated:

```
target                 IDENTITY  NOM_SAME   NOM_BASE  FRESH_LIT  STRUCTURAL  NOT_ASGN
PRIMITIVE                13,816         0          0         13       3,208       419
UNION                     1,716         0          0        166      10,256     1,597
OBJECT_WITH_MEMBERS       6,459       298      2,406        290       4,592     1,520
FUNCTION_TYPE               127         0          0          0       3,052     1,127
ARRAY_OR_TUPLE              390       538        123          0       1,063     1,079
TYPE_PARAMETER              142         0          0          0         618     2,400
ANY_OR_UNKNOWN              415         0          0         28       1,536         0
INTERSECTION                404         0          0         10         183        21
ENUM                        546         0          0          0       3,718        29
```

Only the `OBJECT_WITH_MEMBERS` row can produce an `implements` edge. A structural
edge onto a **union** target is handled by § 3.2's erasure (the union has no
runtime representation); onto a **function type** by `kotlin.FunctionN`; onto an
**enum** by the enum's own lowering; onto a **primitive** or **`any`** by
nothing at all. 4,592 of the 28,226 structural obligations have an object target,
and one more cut removes most of those.

That cut is the SOURCE. Closed obligations with an object target, by source
class (compiler profile):

```
source                 IDENTITY  NOM_SAME   NOM_BASE  FRESH_LIT  STRUCTURAL  NOT_ASGN
PRIMITIVE                     0         0          0          0          54       197
UNION                         0         0          0          0       1,268       861
OBJECT_WITH_MEMBERS       6,181        61      2,378        269         220       123
ANY_OR_UNKNOWN                0         0          0          0       2,753         0
INTERSECTION                  0         0          0          0          52         0
```

**2,753 of them have an `any` source.** `any` has no generated class to hang an
`implements` on; it is § 8.4's separate problem, and folding it in would report
a closure the nominal encoding was never going to build. The 1,268 union sources
ARE real closure work and are decomposed — `Cat | Dog` reaching `Shape` is one
edge per object constituent — which is what takes the compiler profile's closure
from 96 pairs to 158.

What survives is 220 obligations from an object source, plus the union
decomposition. **158 distinct edges.**

Note also the row that is quietly the best news in the table:
`NOMINAL_BASE = 2,378`. tsc's own style is interfaces extending interfaces
(`Identifier extends PrimaryExpression extends … extends Node`), and every one
of those obligations is an edge a generated class ALREADY carries because the
declaration says so. Nominal typing does most of tsc's work for it; the
structural residue is a rounding error beside it.

## 3. Object literals

| corpus | literals visited | in an obligation position |
|---|---:|---:|
| compiler | 1,256 | 573 |
| harness | 3,376 | 1,523 |

"In an obligation position" is the census's operational reading of "has a
contextual type": the literal is the source of an obligation whose target the
census could derive. Slightly under half. The other half are literals in
positions this census does not model as obligations (a nested property of
another literal, an un-annotated `const`, an argument whose callee offers no
signature) — see § 5, this is a coverage statement about the instrument, not a
claim that half of tsc's object literals are contextually untyped.

For the design this is the easy population anyway: § 3.3's plan generates a
final class per object literal, and 507 FRESH_LITERAL edges on the compiler
profile is 0.79% of all obligations.

## 4. What the nominal encoding cannot express

Counted over the distinct types that a closure edge actually points at
(117 targets on `harness`, 73 on the compiler):

| construct | harness | compiler |
|---|---:|---:|
| index signature on the target | 5 | 2 |
| call signature on the target | 0 | 0 |
| **construct signature on the target** | **17** | **17** |
| optional property on the target | 47 | 29 |
| union of object types as target | 0 | 0 |
| generic instantiation as target | 2 | 2 |
| target mentions a type parameter | 0 | 0 |
| **anonymous — no declaration to name** | **63** | **47** |

Three of these matter.

**Anonymous targets are the majority.** 63 of 117 on `harness`, 47 of 73 on the
compiler. tsc writes inline type
literals as parameter types constantly — `{ useCaseSensitiveFileNames(): boolean }`,
`{ getCurrentDirectory(): string }`, `{ readFile(fileName: string): string | undefined }`.
A nominal encoding must MINT an interface for each, since there is no
declaration to name one after. That is fine in a whole-program compile and is
exactly the thing that is impossible under separate compilation — it is the
concrete form of § 3.3's "fails on separate compilation".

**Construct signatures, 17 of them, are not interfaces at all.** They are
`new (kind: SyntaxKind, pos: number, end: number) => Node` — tsc's
`objectAllocator` pattern, a class VALUE passed where a constructor type is
expected. A JVM interface cannot express "a type with this constructor"; the
lowering for those is a factory-function object, not an `implements` edge. They
are 17 of 117 and they need a different mechanism.

**Index signatures are 5 (2 on the compiler).** Small enough that § 3.3's hybrid — nominal where the
checker gives a definite object type, dynamic for the index-signature-dominated
rest — has almost nothing to fall back for at these edges. (Across ALL targets
rather than just closure edges the number is 42 of 4,012, still ~1%.)

Optional properties (47, and 29 on the compiler) do not need a mechanism at all under erasure: an
optional property lowers to a nullable field, and a class that has it satisfies
an interface that declares it optional.

## 5. What this measurement does NOT cover, and in which direction

An instrument that cannot see its own misses is not one. The controls, compiler
profile:

```
obligation sites with no derivable target : 49,019
  callee offered no signature             : 34,855
  refused as unsound to index positionally:  1,678
  argument past the parameter list        :    294
  (by kind) CALL_ARGUMENT 36,794 · RETURN_VALUE 10,952 · PROPERTY_ASSIGNMENT 1,240
lens calls that threw                     :      0
```

**43% of the obligation sites the census finds, it cannot close.** The dominant
cause is a callee with no signature at all (34,855) — a call through an `any`, a
namespace member, a generic instantiation the census reads before instantiation.
The RETURN_VALUE misses (10,952) are mostly benign: a function with no explicit
return-type annotation has an INFERRED return type, so the obligation is vacuous
and is deliberately not recorded.

`NOT_ASSIGNABLE` is 12.74%, and on a clean program should be ~0. It is not,
for three reasons, in decreasing size:

1. **Uninstantiated generics.** Overload resolution returns the signature it
   CHOSE, not one instantiated with the inferred type arguments, so an argument
   is compared against the open parameter type — `ModuleBody` against `T`.
   Restricting to closed obligations drops NOT_ASSIGNABLE from 8,192 to 2,105,
   so this is 74% of it. Such obligations are marked "open" and excluded from
   every number in §§ 1–4.
2. **The census's source type is not always narrowed.** `Map<…> | undefined`
   against `Map<…>` at a site the checker had narrowed. Flow narrowing in this
   compiler is opt-in per emission site, so a per-node type read is the declared
   type at some positions.
3. **Our own 46 false positives** on this profile (94 on `harness`).

Causes 1 and 2 both make the census's source BROADER than the checker's, which
means an obligation the checker saw as IDENTITY can be recorded as STRUCTURAL.
**Every known bias in this instrument inflates the structural population**, so
158 is an upper bound and the conclusion below is conservative.

Two further scope limits. `implements` clauses are resolved BY NAME at the
obligation's position rather than at the class's own, which is only approximately
right across files; tsc barely uses `implements`, so the leg is nearly cold —
**0 edges walked on the compiler profile and 5 on `harness`, where 12 further
heritage names did not resolve at all**, i.e. that leg is exercised almost
entirely by `StructuralCensusTest` rather than by this corpus, and a codebase
that does use `implements` would need it re-checked. And an INTERSECTION source
is not decomposed (52 obligations on the compiler profile).

## 6. Runtime cost

The sink reconstructs the checker's statement-anchor ambient at every expression
node, and hands out ~600k–800k callbacks per program. Measured wall, cold JVM,
`-Xmx4g`, one process per run, daemons stopped:

| | run 1 | run 2 |
|---|---:|---:|
| plain `--noEmit` check, compiler profile | 23.43 s | 23.24 s |
| census over the same profile | 24.85 s | 23.80 s |

So the whole census — the sink, the ambient reconstruction, an `isAssignableTo`
and a memoised nominal closure per obligation over 64,305 of them — costs
**roughly +0.5 to +1.5 s on 23.3 s**, which is at the edge of this box's own
run-to-run spread. The largest corpus measured (`harness`, 312 files) takes
33.1 s.

**The census is essentially free**, and that was not the expectation: the brief
budgeted for "several times slower than a plain check". Two reasons it is not.
The ambient reconstruction is one the capture facility already performs per
node, so the sink adds a callback and not a walk; and the per-obligation
relation queries are answered out of the checker's own relation cache, because
the census asks exactly the pairs the checker has just asked.

## 7. The verdict

Of the three outcomes the brief asked to choose between:

> - whole-program nominal closure is fine (small fan-out, thin tail)
> - closure is viable only with a fallback for a named minority of cases
> - closure is not viable; a dynamic mechanism is needed for the common case

**The data supports the second, close to the first.** The closure itself is
trivial — 158 to 264 edges, median fan-out 1, worst case 10 — and on the
hardest-styled real TypeScript there is, which is the point: tsc's own sources
are interfaces plus object literals plus factory functions with barely any
classes, the shape most hostile to a nominal encoding.

The named minority that needs something other than an `implements` edge:

1. **`any`-typed sources — 2,753 closed obligations onto object targets on the
   compiler profile, an order of magnitude more than the 220 that are the
   closure's actual work.** This is by far the largest single population in the
   whole measurement, and § 8.4 already names its mechanism (a per-call-site
   inline cache). The census's contribution is to size it: `any` is not a corner
   case in real TypeScript, it is the dominant dynamic obligation, and the
   nominal half of the hybrid will look finished while this is still open.
2. **Construct-signature targets — 17 of 117 closure targets.** A JVM interface
   cannot say "has this constructor"; these need a factory object, not an
   `implements`.
3. **Anonymous structural targets — 63 of 117.** Not a fallback, a mint: the
   backend generates an interface with no user-visible name for each. Cheap, and
   the reason the whole scheme cannot survive separate compilation.

So § 3.3's decision stands, with its ordering inverted. The document treats the
nominal half as the hard part and `any` as "out of scope until the nominal half
runs". Measured, the nominal half is small, bounded and heavy-tailed, and `any`
is 12× larger than it. The risk in this design is not the closure.

## 8. Reproducing

The tool is `xemantic-typescript-compiler-kir/src/jvmMain/kotlin/kir/census/`,
pinned by `StructuralCensusTest` (each edge class reachable by exactly one
construct in one fixture, so a classifier that collapsed two of them fails
rather than reporting a plausible distribution) and, for the seam it rides,
`CheckedSinkProjectTest` in `-core`.

Two raw reports are committed beside this page as the evidence it is written
from — `docs/kir/census-tsc-compiler.txt` (the 78-file compiler profile) and
`docs/kir/census-tsc-harness.txt` (the 312-file harness, the largest measured).
Every table above is a transcription from one of those two, and each carries the
full worked-example set — one obligation per (kind, edge, target class) bucket
with its file and offset — which is what makes a disputed classification
checkable against the source rather than against this page.

It is a MEASUREMENT TOOL and shares no code with the lowering. It must not
become a code path: what it computes is a distribution, and a distribution is
not an oracle.
