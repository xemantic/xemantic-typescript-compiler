export const meta = {
  name: 'triage-r180',
  description: 'Read-only deep triage of contained-walker candidates for round 180',
  phases: [{ title: 'Investigate', detail: 'one read-only agent per candidate' }],
}

const CANDIDATES = [
  { name: 'elidedJSImport1', codes: 'TS18042+TS2708' },
  { name: 'jsExportMemberMergedWithModuleAugmentation2', codes: 'TS2300+TS2551' },
  { name: 'disallowedBlockScopedInPresenceOfParseErrors1', codes: 'TS1156+TS2454' },
  { name: 'controlFlowAliasedDiscriminants', codes: 'TS1360+TS18048' },
  { name: 'indexerConstraints2', codes: 'TS1021+TS1337+TS2413' },
  { name: 'functionsWithModifiersInBlocks1', codes: 'TS1184+TS2393' },
  { name: 'inferentialTypingWithObjectLiteralProperties', codes: 'TS2011+TS2322' },
  { name: 'widenedTypes', codes: 'TS18050+TS2322+TS2358' },
  { name: 'strictFunctionTypesErrors', codes: 'TS2322+TS2328' },
  { name: 'es6ExportEqualsInterop', codes: 'TS2305+TS2339+TS2497+TS2498+TS2693' },
]

const SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['testName', 'classification', 'exactRule', 'currentGap', 'fpRarity', 'implSketch', 'confidence'],
  properties: {
    testName: { type: 'string' },
    classification: { type: 'string', enum: ['CONTAINED', 'NEEDS_TYPE_ENGINE', 'NEEDS_PARSER_RECOVERY', 'NEEDS_CROSS_FILE', 'NEEDS_FLOW', 'MULTI_PIECE'] },
    exactRule: { type: 'string', description: 'Precise rule from the baseline: what triggers each MISSING diagnostic and at what position.' },
    currentGap: { type: 'string', description: 'Why we do NOT emit it today, citing the specific Checker.kt/Parser.kt code path (file:line).' },
    fpRarity: { type: 'string', description: 'How many corpus .ts files (in typescript-repo/tests/cases) share the exact syntactic shape this walker would key on. Lower = safer. Cite the grep you ran.' },
    implSketch: { type: 'string', description: 'Concrete code change: which function, what to add, the FP-firewall gate.' },
    confidence: { type: 'string', enum: ['HIGH', 'MED', 'LOW'] },
  },
}

const results = await parallel(CANDIDATES.map(c => () =>
  agent(
    `You are doing READ-ONLY triage of a failing TypeScript-compiler-port test. Do NOT edit any file.

Test: ${c.name}  (missing/wrong diagnostic codes: ${c.codes})

Repo root: /home/claude/git/xemantic-typescript-compiler
- Source:   typescript-repo/tests/cases/compiler/${c.name}.ts  (NOTE: a leading "// @target:"/"// @xxx" directive line is STRIPPED, so the compiler's effective line numbers are shifted up by the number of leading directive lines).
- Baseline: typescript-repo/tests/baselines/reference/${c.name}.errors.txt (may be parameterized: ${c.name}(target=esNNNN).errors.txt). The "==== (N errors) ====" annotated section shows exact squiggle positions.
- Our actual output: in build/test-results/jvmTest/TEST-*.xml find the <testcase name="${c.name}..."> failure; the failure text contains a "--- expected / +++ actual" diff ("-" lines = expected we MISS, "+" lines = we emit wrongly). If it says "but none produced" we emit nothing.
- Checker/Parser/Scanner sources: src/commonMain/kotlin/{Checker.kt,Parser.kt,Scanner.kt,Binder.kt}. Grep for the diagnostic code (e.g. "2551", "1337") to find the emitter; read CLAUDE.md for the relevant gotcha section.

Your job: determine whether this is a CONTAINED dedicated-walker/scanner/parser win (depends only on AST shape / modifiers / names / scope / simple constant-eval — NOT the relation/inference engine, NOT cross-file global-merge, NOT full flow narrowing) or whether it needs an architectural blocker.

Be SKEPTICAL and CONCRETE — past triage consistently OVER-classifies things as CONTAINED. A test is CONTAINED only if you can name the exact function to change, the exact AST shape to match, and a corpus-FP-rarity grep showing few files share the shape. If ANY missing diagnostic needs a computed TYPE (assignability, generic inference, union narrowing, mapped/conditional types) it is NOT contained. Report honestly.

Return the structured verdict.`,
    { label: `triage:${c.name}`, phase: 'Investigate', agentType: 'Explore', schema: SCHEMA }
  )
)).then(rs => rs.filter(Boolean))

return results
