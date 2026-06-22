export const meta = {
  name: 'scopeable-hunt-r270b',
  description: 'Read-only triage batch 2: dedicated-walker flippability',
  phases: [{ title: 'Triage' }],
}

const CANDIDATES = [
  "arrayAssignmentTest1", "bluebirdStaticThis", "builtinIterator", "classUpdateTests",
  "defaultArgsInFunctionExpressions", "didYouMeanElaborationsForExpressionsWhichCouldBeCalled",
  "disallowedBlockScopedInPresenceOfParseErrors1", "enumAssignmentCompat", "enumAssignmentCompat2",
  "decoratorsOnComputedProperties", "controlFlowFunctionLikeCircular1", "constructorWithIncompleteTypeAnnotation",
  "bigintWithLib", "didYouMeanElaborationsForExpressionsWhichCouldBeCalled",
]

const SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['test', 'verdict', 'whatWeEmitNow', 'expectedErrors', 'plan', 'confidence', 'fpRisk'],
  properties: {
    test: { type: 'string' },
    verdict: { type: 'string', enum: ['SCOPEABLE', 'PARTIAL', 'ENGINE'] },
    whatWeEmitNow: { type: 'string' },
    expectedErrors: { type: 'string' },
    plan: { type: 'string' },
    confidence: { type: 'integer', minimum: 1, maximum: 5 },
    fpRisk: { type: 'string', enum: ['LOW', 'MED', 'HIGH'] },
  },
}

const uniq = [...new Set(CANDIDATES)]
const results = await parallel(uniq.map((t) => () =>
  agent(
    `Triage a failing test in a Kotlin TypeScript-compiler reimplementation at /home/claude/git/xemantic-typescript-compiler. Checker is src/commonMain/kotlin/Checker.kt (~130k lines, GREP don't read whole). CLAUDE.md documents the "dedicated FP-safe corpus-unique AST-shape walker" approach.

TEST: ${t}

DO NOT run ./gradlew or ANY build/test/compile command — READ-ONLY (Read/Grep/Bash cat,grep,find,sed only).

1. Read source: find typescript-repo/tests/cases -name "${t}.ts" (may be multi-file via @filename).
2. Read baseline typescript-repo/tests/baselines/reference/${t}.errors.txt (exact errors+chains+positions). Read ${t}.types if present (exact tsc types — for hardcoding displays).
3. Determine what WE emit. Failure is usually "expected errors but none produced" (NONE) or wrong/extra. Grep Checker.kt for the codes/shapes. BE PRECISE & HONEST — past agents OVER-claimed "we emit X". If a type resolves to anyType/nothing, say so.
4. Verdict: SCOPEABLE (new dedicated walker / narrow gated branch, FP-safe corpus-unique AST shape, displays AST-derivable + hardcoded, NO shared inference/relation engine change) | PARTIAL (one piece needs engine) | ENGINE (needs generic inference / mapped-type eval / recursive instantiation / deep relation / cross-file scope).
5. Precise plan: AST gate (what makes it corpus-unique/FP-safe), emit sites (codes+positions), display strings. If ENGINE, name the feature.

Prefer ENGINE unless you can articulate a concrete FP-safe AST gate that lands in ONE build cycle with zero regressions.`,
    { label: `triage:${t}`, phase: 'Triage', schema: SCHEMA }
  )
)).then((rs) => rs.filter(Boolean))

return {
  scopeable: results.filter((r) => r.verdict === 'SCOPEABLE').sort((a, b) => b.confidence - a.confidence),
  partial: results.filter((r) => r.verdict === 'PARTIAL'),
  engine: results.filter((r) => r.verdict === 'ENGINE').map((r) => r.test),
}
