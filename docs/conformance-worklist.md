# M3.0 — the conformance worklist, priced PER CASE

Measured **round 836 (2026-08-05)**, on the nine candidate categories that M3.0 still
holds back. This file replaces the per-CATEGORY failure counts that rounds 695, 831, 833
and 834 kept re-deriving: a category's failure count says nothing about how tractable it
is, because a category's name describes its FIXTURES, not its gaps. Three rounds in a row
adopted a category on that number and had to re-cost it upward.

## How it was measured (reproduce, do not guess)

The conformance case tree is NOT in `typescript-repo`'s sparse checkout, but the blobs are
local (blobless partial clone, `promisor = true`), so:

```
git -C typescript-repo archive HEAD tests/cases/conformance/<category> | tar -x -C <scratch>
```

A throwaway `main` in `src/jvmMain` then replicates the generator's own subtest algebra —
`parseDirectives` / `computeVariations` / `tsgoSkippedTests` / `usesUnsupportedOption` /
`tsconfigInTestUsesRemovedFeature`, all verbatim from `build.gradle.kts` — calls
`TypeScriptCompiler().compile(source, "<name>.ts", config)` per subtest, and diffs

* the **diagnostic SUMMARY** (every line of the `.errors.txt` baseline before the first
  `==== ` header) against the same lines rebuilt from `result.diagnostics`, and
* each emitted output against its `//// [name.js]` section of the `.js` baseline.

That is ~40 s for all 124 cases in one JVM, against ~12 minutes for a suite arm, and it
yields the per-case diff a suite arm does not. Two caveats it cannot see, both recorded
rather than hidden: the annotated (squiggle) half of an `.errors.txt` baseline is not
compared — it is derived deterministically from the same `start`/`length`, so a matching
summary plus matching spans implies it — and the `.js` comparison ignores the source-echo
half of the baseline. The harness in the tree is deliberately temporary; re-create it from
this description rather than reviving a stale copy.

## The state after round 836

124 cases, 79 error subtests, **65 with a diagnostic diff**, **12 with a JS-emit diff**.
`conformanceDeferredErrorBaselines` defers only `.errors.txt`, so **one failing emit
subtest blocks its whole category**, and `emit=RED` below is therefore a veto, not a cost.

`miss` / `extra` are diagnostic LINES (a message chain counts its continuations).

### expressions/asOperator

| case | miss | extra | emit | codes |
| --- | --- | --- | --- | --- |
| asOperator2 | 1 | 0 | ok | TS2352 |
| asOperatorAmbiguity | 1 | 0 | ok | TS2339 |
| asOperatorContextualType | 1 | 0 | ok | TS2352 |
| asOperatorASI | — | — | **RED** | parser/ASI restart |

Both TS2352 cases want a GENERAL comparability rule where today TS2352 is ~10 dedicated
walkers and no general rule (M3.1 depth). `asOperatorASI`'s emit gap vetoes the category
whatever those cost.

### expressions/contextualTyping

| case | miss | extra | emit | codes |
| --- | --- | --- | --- | --- |
| objectLiteralContextualTyping | 1 | 0 | ok | TS2403 |
| superCallParameterContextualTyping2 | 1 | 0 | ok | TS2349 |
| functionExpressionContextualTyping2 | 1 | 1 | ok | TS2322 both ways |
| arrayLiteralExpressionContextualTyping | 4 | 0 | ok | TS2322 |
| taggedTemplateContextualTyping2 | 5 | 0 | ok | TS2345 |
| taggedTemplateContextualTyping1 | 7 | 0 | ok | TS2345 |
| argumentExpressionContextualTyping | 11 | 1 | ok | TS7031 x8, TS2345 x3 |
| generatedContextualTyping | 4 | 12 | ok | over-emits TS2352 x8 |
| parenthesizedContexualTyping2 | 30 | 3 | ok | TS2345 x22 |

Zero emit gaps, so the category is adoptable in principle — but the two tagged-template
cases plus `parenthesizedContexualTyping2` are 34 TS2345 from contextual typing through a
tag call, i.e. M3.1/M3.2 inference work, and `generatedContextualTyping` OVER-emits.

### expressions/optionalChaining

| case | miss | extra | emit | codes |
| --- | --- | --- | --- | --- |
| optionalChainingInParameterBindingPattern.2 | 2 | 0 | ok | TS2373 |
| optionalChainingInParameterInitializer.2 | 2 | 0 | ok | TS2373 |
| privateIdentifierChain.1 | 4 | 0 | ok | TS18030 x3, TS2532 |
| callChain.3 | 2 | 0 | **RED** | TS2322 |
| taggedTemplateChain | 2 | 4 | **RED** | TS1358; parse recovery |
| deleteChain | 8 | 0 | **RED** | TS2790 x8 |
| propertyAccessChain.3 | 0 | 0 | **RED** | CLOSED round 836 |
| elementAccessChain.3 | 0 | 0 | **RED** | CLOSED round 836 |
| asOperatorASI-class emit-only | — | — | **RED** | `parentheses`, `propertyAccessChain`, `superMethodCall` |

The reddest category and the least adoptable: 6 emit-red cases, all of them `?.` DOWNLEVEL
or parse-recovery emit. `deleteChain`'s 8x TS2790 ("the operand of a `delete` operator must
be optional") is the largest remaining single-mechanism error gap here.

### expressions/typeSatisfaction

| case | miss | extra | emit | codes |
| --- | --- | --- | --- | --- |
| typeSatisfaction | 0 | 1 | ok | over-emits TS2322 |
| typeSatisfaction_contextualTyping2 | 1 | 0 | ok | TS7006 |
| typeSatisfaction_propNameConstraining | 1 | 0 | ok | TS2353 — **lib gap** |
| typeSatisfaction_propertyNameFulfillment | 1 | 0 | ok | TS2353 — **lib gap** |
| typeSatisfaction_propertyValueConformance1 (x2 configs) | 1 | 0 | ok | TS2322 |
| typeSatisfaction_propertyValueConformance2 (x2 configs) | 1 | 0 | ok | TS2322 |
| typeSatisfaction_propertyValueConformance3 | 1 | 0 | ok | TS2353 — **lib gap** |
| typeSatisfaction_vacuousIntersectionOfContextualTypes | 1 | 1 | ok | TS2322 |
| typeSatisfaction_errorLocations1 | 17 | 4 | ok | TS2345 x8, TS2322 x5 |

Round 833's re-costing stands: four of these need `Record`/`Partial`, which the EMBEDDED
lib does not declare at all — a LIB gap, not a `satisfies` gap.

### statements/labeledStatements

| case | miss | extra | emit | codes |
| --- | --- | --- | --- | --- |
| labeledStatementExportDeclarationNoCrash1 (x2 module configs) | 1 | 0 | ok | TS1184 |
| labeledStatementDeclarationListInLoopNoCrash3 | 2 | 2 | ok | TS1134 x2 vs TS1123 x2 |
| labeledStatementDeclarationListInLoopNoCrash4 | 3 | 1 | ok | TS1005 x2, TS1134 |
| labeledStatementWithLabel_strict | — | — | **RED** | labelled-declaration emit |

Entirely PARSER error-recovery: the three `NoCrash` fixtures are deliberately malformed and
pin exactly which recovery diagnostics tsc produces. Note that all five `NoCrash` cases
declare `@target: es5, es2015`, so their BARE config is tsgo-skipped and only the
`(target=es2015)` variation is generated — a survey that skips the whole file on
`bareUnsupported` sees none of them.

### types/any

| case | miss | extra | emit | codes |
| --- | --- | --- | --- | --- |
| anyAsConstructor | 1 | 0 | ok | TS2347 on `new x<any>(x)` |
| assignAnyToEveryType | 1 | 1 | ok | TS2631 vs TS2708 |
| assignEveryTypeToAny | 0 | 2 | ok | over-emits TS2454 x2 |
| narrowExceptionVariableInCatchClause | 2 | 0 | ok | TS2551 |
| narrowFromAnyWithInstanceof | 2 | 0 | ok | TS2551 |
| narrowFromAnyWithTypePredicate | 4 | 0 | ok | TS2551 x2, TS2339, TS2349 |

**The best-shaped remaining target.** Zero emit gaps, six small cases, and three of them are
ONE mechanism: narrowing FROM `any` (`instanceof` and a type predicate both narrow `any` to
the guarded type in tsc, except to `Function`/`Object`), whose whole observable consequence
is a `did you mean` TS2551 on the narrowed type. `anyAsConstructor` is a single TS2347.

### types/conditional

| case | miss | extra | emit | codes |
| --- | --- | --- | --- | --- |
| inferTypesInvalidExtendsDeclaration | 1 | 0 | ok | TS2304 |
| conditionalTypesExcessProperties | 2 | 0 | ok | TS2322 |
| inferTypesWithExtends2 | 3 | 0 | ok | TS2838 x2, TS2304 |
| conditionalTypes2 | 3 | 3 | ok | TS2345 x3 |
| conditionalTypes1 | 19 | 6 | ok | TS2322 x15 |
| inferTypes1 | 10 | 12 | **RED** | `infer` PARSE failure |
| inferTypesWithExtends1 | — | — | **RED** | emit |

`inferTypes1`'s TS1005/TS1011/TS1109 over-emissions are a parse failure of `infer X extends`
constraints, which also produces the emit diff. Two emit-red cases veto the category.

### types/nonPrimitive

| case | miss | extra | emit | codes |
| --- | --- | --- | --- | --- |
| nonPrimitiveAndTypeVariables | 1 | 0 | ok | TS2322 |
| nonPrimitiveAccessProperty | 2 | 0 | ok | TS2339 |
| nonPrimitiveNarrow | 3 | 0 | ok | TS2339 x2, TS18047 |
| nonPrimitiveUnionIntersection | 4 | 4 | ok | intersection reduction |
| nonPrimitiveConstraintOfIndexAccessType | 10 | 0 | ok | TS2322 into `T[P]` |
| nonPrimitiveStrictNull | 12 | 0 | ok | flow narrowing on `object` |

Rounds 834/835 closed three cases here and exhausted the cheap end; the six residuals are
each a separate sub-step and none is a message shape. Zero emit gaps, so the category IS
adoptable once they land.

### types/typeAliases

| case | miss | extra | emit | codes |
| --- | --- | --- | --- | --- |
| classDoesNotDependOnBaseTypes | 2 | 0 | ok | TS2542 x2 |
| interfaceDoesNotDependOnBaseTypes | 2 | 0 | ok | TS2339 x2 |
| reservedNamesInAliases | 2 | 0 | ok | TS2304, TS2457 |
| typeAliasesDoNotMerge | 2 | 0 | ok | TS2395 x2 |
| typeAliasesForObjectTypes | 2 | 0 | ok | TS2300 x2 |
| typeAliases | 0 | 2 | ok | over-emits TS2403 x2 |
| intrinsicKeyword | 3 | 0 | ok | TS2795 x2, TS2503 |
| directDependenceBetweenTypeAliases | 5 | 1 | ok | TS2456 x4, TS2502 |
| intrinsicTypes | 7 | 2 | ok | TS2344 x4, TS2322 x2 |

Zero emit gaps and nine SMALL cases — the flattest remaining category by diff size, but
spread over at least six unrelated mechanisms (`intrinsic` keyword support, circular alias
detection, duplicate-declaration coverage for aliases, `readonly` index signatures,
`Uppercase<T>` relations). `typeAliasesForObjectTypes` (TS2300) and `typeAliasesDoNotMerge`
(TS2395) are both "a type alias participates in the duplicate/merged-declaration checks" and
would go green together — the cheapest pair here, at the cost of touching
`checkDuplicateDeclarations`, whose exposure across tsc's own sources is large.

## The buckets that matter, by MECHANISM

| mechanism | subtests | notes |
| --- | --- | --- |
| M3.1/M3.2 inference and relation (TS2345/TS2322 cores) | ~20 | `conditionalTypes1`, the tagged-template family, `parenthesizedContexualTyping2`, `errorLocations1`, `nonPrimitiveConstraintOfIndexAccessType` |
| parser / ASI / error recovery | ~7 | all of `labeledStatements`, `inferTypes1`, `taggedTemplateChain`, `asOperatorASI` |
| JS emit (`?.` downlevel, labelled declarations) | 12 | vetoes 4 categories outright |
| embedded-lib gaps (`Record`, `Partial`) | 4 | not checker work at all |
| flow narrowing (`any`, `object`) | ~9 | `types/any` x3, `nonPrimitiveNarrow`, `nonPrimitiveStrictNull` |
| bounded single-purpose checks | ~12 | TS2347, TS2790, TS2373, TS18030, TS2795, TS2456, TS2300/TS2395, TS2542 |

**Recommendation carried forward:** the last bucket is where a round buys whole cases, and
`types/any` is the only category where a single mechanism (narrowing FROM `any`) closes
three cases at once with no emit veto in the way. Everything else is either an M3.1-depth
engine change or blocked by an emit gap that no error-baseline deferral can carry.
