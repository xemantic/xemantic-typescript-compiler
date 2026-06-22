export const meta = {
  name: 'scopeable-hunt-r270c',
  description: 'Read-only triage batch 3: dedicated-walker flippability',
  phases: [{ title: 'Triage' }],
}

const CANDIDATES = [
  "assignmentToObjectAndFunction", "errorRecoveryInClassDeclaration",
  "errorRecoveryWithDotFollowedByNamespaceKeyword", "es6ImportNamedImportParsingError",
  "expressionTypeNodeShouldError", "expressionWithJSDocTypeArguments", "excessivelyLargeTupleSpread",
  "indexerConstraints2", "inferTypePredicates", "errorsOnUnionsOfOverlappingObjects01",
  "es6ExportEqualsInterop", "exhaustiveSwitchCheckCircularity",
]

const SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['test', 'verdict', 'whatWeEmitNow', 'expectedErrors', 'plan', 'confidence', 'fpRisk'],
  properties: {
    test: { type: 'string' },
    verdict: { type: 'string', enum: ['SCOPEABLE', 'PARTIAL', 'ENGINE'] },
    whatWeEmitNow: { type: 'string' }, expectedErrors: { type: 'string' }, plan: { type: 'string' },
    confidence: { type: 'integer', minimum: 1, maximum: 5 },
    fpRisk: { type: 'string', enum: ['LOW', 'MED', 'HIGH'] },
  },
}

const results = await parallel(CANDIDATES.map((t) => () =>
  agent(
    `Triage a failing test in a Kotlin TypeScript-compiler reimplementation at /home/claude/git/xemantic-typescript-compiler. Checker is src/commonMain/kotlin/Checker.kt (~130k lines, GREP don't read whole); Parser.kt for parse/recovery. CLAUDE.md documents the "dedicated FP-safe corpus-unique AST-shape walker" approach.

TEST: ${t}

DO NOT run ./gradlew or ANY build/test/compile command — READ-ONLY (Read/Grep/Bash cat,grep,find,sed only).

1. Read source: find typescript-repo/tests/cases -name "${t}.ts".
2. Read baseline typescript-repo/tests/baselines/reference/${t}.errors.txt (exact errors+chains+positions). Read ${t}.types if present.
3. Determine what WE emit. Failure is usually "expected errors but none produced" (NONE) or wrong/extra/missing. Grep Checker.kt/Parser.kt for the codes/shapes. BE PRECISE — past agents OVER-claimed "we emit X". If a type resolves to anyType/nothing, say so.
4. Verdict: SCOPEABLE (new dedicated walker/narrow gated branch, FP-safe corpus-unique AST shape, displays AST-derivable+hardcoded, NO shared inference/relation engine change) | PARTIAL | ENGINE.
5. Precise plan: AST gate (corpus-unique/FP-safe), emit sites (codes+positions), display strings. If ENGINE, name the feature.

Prefer ENGINE unless you can articulate a concrete FP-safe AST gate landing in ONE build cycle with zero regressions.`,
    { label: `triage:${t}`, phase: 'Triage', schema: SCHEMA }
  )
)).then((rs) => rs.filter(Boolean))

return {
  scopeable: results.filter((r) => r.verdict === 'SCOPEABLE').sort((a, b) => b.confidence - a.confidence),
  partial: results.filter((r) => r.verdict === 'PARTIAL').map((r) => r.test),
  engine: results.filter((r) => r.verdict === 'ENGINE').map((r) => r.test),
}
