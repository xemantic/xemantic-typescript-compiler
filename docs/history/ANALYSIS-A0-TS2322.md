# A0 Deliverable: Analysis of Pure TS2322 Tests

**Date:** 2026-03-29
**Status:** 7,662 / 10,077 tests passing (76.0%)

## Scope

Tests that would pass if we **only** improved TS2322 checking (no other error codes
appear in the diff, no FP codes from other checks).

## Test Counts

| Category | Count | Description |
|----------|-------|-------------|
| Diff tests | 42 | We produce some TS2322 but are missing others |
| None-produced | 171 | We produce 0 diagnostics; baseline expects only TS2322 |
| **Total** | **213** | All pure TS2322 tests |

Note: "diff tests" are higher-value — they're closer to passing (we already fire
some TS2322 but miss specific cases). "None-produced" tests need all TS2322 from scratch.

## Current Guard (the bottleneck)

All three check functions (`checkVarDeclAssignability`, `checkReturnAssignability`,
`checkAssignmentExpression`) share the same conservative guard in `Checker.kt`:

```kotlin
val useNewEngine = targetType !== anyType && targetType !== errorType &&
    sourceType !== anyType && sourceType !== errorType &&
    (sourceIsIntrinsic && targetIsIntrinsic || sourceIsNullish || sourceIsObjectLiteral)
```

Only these comparisons use the Type engine:
1. **intrinsic → intrinsic** (number→string, etc.)
2. **null/undefined → any target** (null→number, undefined→string, etc.)
3. **object literal → any target** (only in var decl)

Everything else falls through to the old string-based system, which has limited
type resolution capability.

## Category Breakdown

### Category 1: null/undefined → non-intrinsic target

**Diff tests:** 14 | **None-produced:** 12 | **Total:** ~26

The new engine handles null→intrinsic (null→number) but the old string-based
fallback only recognizes intrinsic type names. So `null → SomeClass` is missed.

Examples from diff tests:
- `chainedAssignment2`: `null` → `Date`, `RegExp`
- `memberVariableDeclarations1`: `null` → `Employee`
- `declarationEmitBundleWithAmbientReferences`: `null` → `T<string>`
- `indexerReturningTypeParameter1`: `null` → `{ [key: string]: T[] }`
- `typeParameterCompatibilityAccrossDeclarations`: `null` → `T`

**Root cause:** The `sourceIsNullish` guard IS in the `useNewEngine` check, so
this should already work! But these tests fail because the old system's
`resolveSimpleTypeName()` can't resolve complex target types (generics, interfaces),
so `declaredTypeStr` is null and the old system skips them. The NEW engine should
handle these — need to investigate why `getTypeFromTypeNode` fails for these targets.

**Fix area:** Likely `getTypeFromTypeNode` doesn't resolve certain type reference
patterns (generic class instantiations, index signatures). Or the `checkTypeRelatedTo`
result is wrong for null → Object types.

### Category 2: intrinsic → intrinsic in uncovered contexts

**Diff tests:** 14 | **None-produced:** 50 | **Total:** ~64

Our checker handles intrinsic→intrinsic in var init, return, and assignment. But
many tests need it in contexts we don't reach:

Missing contexts from diff tests:
- `classMemberInitializerScoping`: class member initializer (`x: number = someString`)
- `genericGetter`: getter return type mismatch
- `numberToString`: simple var init (should already work — investigate why not)

Missing contexts from none-produced (harder):
- Tests like `assignmentCompatability*` (35 tests!) — these assign function results,
  class instances, etc. The old string-based `inferSimpleExprType` can't resolve them.

**Fix area:**
1. Add class `PropertyDeclaration` with initializer to the traversal
2. Ensure getter/setter bodies are checked for return types
3. Improve `getTypeOfExpression` to resolve more expression types

### Category 3: function → function type

**Diff tests:** 10 | **None-produced:** 21 | **Total:** ~31

Pattern: `let f: () => boolean = () => {}` or `f(): string { return 42 }`
The Type engine can't compare function types structurally.

Examples:
- `errorOnContextuallyTypedReturnType`: `() => void` → `() => boolean`
- `moduleAugmentation*` (7 tests): `() => undefined` → `() => B`
- `assignmentCompatWithOverloads`: complex function + overload comparisons

**Fix area:** `checkTypeRelatedTo` / `structuredTypeRelatedTo` needs to compare
call/construct signatures when both source and target are function types.

### Category 4: generic<A> → generic<B>

**Diff tests:** 8 | **None-produced:** 18 | **Total:** ~26

Pattern: `MyList<string>` → `MyList<number>` — same generic, incompatible args.

Examples from diff tests:
- `genericCloneReturnTypes`: `Bar<string>` → `Bar<number>`
- `incompatibleGenericTypes`: `I1<boolean>` → `I1<number>`
- `getAndSetNotIdenticalType2/3`: `A<string>` → `A<number>`
- `generics4`: `C<Y>` → `C<X>`

**Fix area:** When comparing two `Type.Reference` or `Type.Interface` with same
target generic, compare type arguments pairwise.

### Category 5: union → type

**Diff tests:** 4 | **None-produced:** 23 | **Total:** ~27

Pattern: `string | undefined` → `number` (union not assignable to target).

Examples:
- `widenToAny1/2`: `string | undefined` → `number`
- `assignmentCompatability*`: various union incompatibilities

**Fix area:** Union assignability: each constituent must be assignable to target.
Currently `checkTypeRelatedTo` may handle this but the guard prevents it from firing
for unions (not intrinsic, not nullish).

### Category 6: named type ↔ intrinsic

**Diff tests:** 5 | **None-produced:** 47 | **Total:** ~52

Pattern: `SomeClass` → `number` or `number` → `SomeClass`

Examples from diff tests:
- `aliasAssignments`: `number` → `typeof import("mod")`, `typeof import("mod")` → `number`
- `genericGetter3`: `A<number>` → `string`

**Fix area:** Class/interface instances are never assignable to primitives (and vice versa),
except for wrapper types (Number→number is one-way in TypeScript).

### Category 7: named type → named type

**Diff tests:** 3 | **None-produced:** 24 | **Total:** ~27

Pattern: `ClassA` → `ClassB` where they're structurally incompatible.

**Fix area:** Full structural comparison of class/interface member lists.
Most complex category.

### Category 8: literal type ↔ literal type

**Diff tests:** 4 | **None-produced:** 10 | **Total:** ~14

Pattern: `"hello"` → `42`, `42` → `"hello"` (different literal base types)

Examples:
- `divergentAccessorsTypes4`: `"hello"` → `42`
- `divergentAccessorsTypes5`: `42` → `"hello"` and `"hello"` → `42`

**Fix area:** Literal types of different base types are trivially incompatible.
Need to handle `LiteralType` in the guard.

### Category 9: array → non-array

**Diff tests:** 1 | **None-produced:** 8 | **Total:** ~9

Pattern: `number[]` → `number` (array not assignable to primitive)

**Fix area:** Array types are never assignable to primitive types.

### Category 10: typeof → type

**Diff tests:** 2 | **None-produced:** 6 | **Total:** ~8

**Fix area:** Requires typeof expression type resolution.

### Category 11: boxed → primitive

**Diff tests:** 1 | **None-produced:** 0 | **Total:** ~1

Pattern: `Number` → `number` (wrapper object not assignable to primitive)

Example: `nativeToBoxedTypes`

**Fix area:** Boxed wrapper types (Number, String, Boolean, Symbol) are not
assignable to their primitive counterparts.

## Tests with FP Issues (5 diff tests)

These emit **wrong** TS2322 errors in addition to missing correct ones:

| Test | Issue |
|------|-------|
| `assignmentIndexedToPrimitives` | Array displayed as `'array'` instead of `'number[]'` |
| `exactOptionalPropertyTypesIdentical` | Spurious null→object FP from wrong line |
| `propertyParameterWithQuestionMark` | FP `{ ... }` → `C` on wrong line |
| `prettyFileWithErrorsAndTabs` | Missing pretty-print ANSI formatting |
| `typeArgumentsShouldDisallowNonGenericOverloads` | TS2322 on wrong line (7 vs 10), wrong direction |

## Recommended Implementation Order for A1-A4

### A1: Relax guard to include all non-any, non-error type comparisons
**Target:** ~40-60 tests | **Risk:** Medium | **Complexity:** Low

The biggest single improvement: change the `useNewEngine` guard from
`(sourceIsIntrinsic && targetIsIntrinsic || sourceIsNullish || sourceIsObjectLiteral)`
to just exclude error/any/unknown/typeParam targets while allowing any concrete type.

Specifically:
```kotlin
val useNewEngine = targetType !== anyType && targetType !== errorType &&
    sourceType !== anyType && sourceType !== errorType &&
    targetType !== unknownType && sourceType !== unknownType
```

But add regression-safe checks:
- Skip when source or target is a type parameter (needs instantiation)
- Skip when both are Object types (needs structural comparison)
- Allow: intrinsic↔intrinsic, nullish→anything, literal↔literal,
  intrinsic↔named, array→primitive

This unlocks categories 1, 2, 5, 6, 8, 9, 11 without needing structural comparison.

### A2: Add class member initializer checking
**Target:** ~5-10 tests | **Risk:** Low | **Complexity:** Low

Add `PropertyDeclaration` with initializer + type annotation to the class member
traversal in `checkTypeAssignabilityInStatements`.

### A3: Improve `getTypeOfExpression` coverage
**Target:** ~20-30 tests | **Risk:** Medium | **Complexity:** Medium

Many none-produced tests fail because `getTypeOfExpression` returns `anyType` for
expressions we can't resolve. Improving resolution of:
- Variable references (look up type annotation)
- Property access on known types
- Function call return types

### A4: Function signature comparison
**Target:** ~15-20 tests | **Risk:** Medium | **Complexity:** High

Compare function types structurally: parameter compatibility + return type
compatibility. This unlocks category 3 (31 tests).
