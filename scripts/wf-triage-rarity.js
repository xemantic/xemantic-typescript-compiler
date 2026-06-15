export const meta = {
  name: 'triage-rarity',
  description: 'Triage NONE/DIFF failures with a MANDATORY corpus-FP-rarity grep — finds dedicated-walker wins the agents over-classify as architectural',
  phases: [
    { title: 'Triage' },
  ],
}

// args: array of candidate base names (e.g. "expandoFunctionNestedAssigments").
const names = typeof args === 'string' ? JSON.parse(args) : args

const SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    test: { type: 'string' },
    verdict: {
      type: 'string',
      enum: ['FLIP_NOW', 'ATTEMPT', 'ARCHITECTURAL', 'MISSING_SOURCE'],
      description: 'FLIP_NOW: you READ the exact code path AND grepped the corpus and a small additive change / dedicated walker fires the diagnostic with a corpus-EXHAUSTIVE FP firewall (the shape is unique/near-unique). ATTEMPT: plausible bounded fix but FP surface or reachability uncertain. ARCHITECTURAL: needs a named Blocker (type-engine assignability/generic-inference/mapped-conditional-materialization/cross-file-scope/flow-narrowing/lib-content/JSDoc-parser/decl-emit-nameability) AND the corpus FP surface is NOT containable.',
    },
    ts_codes: { type: 'string' },
    corpus_fp_count: { type: 'integer', description: 'REQUIRED: how many corpus files (under typescript-repo/tests/cases/) you found that share the EXACT syntactic shape your proposed gate matches AND are currently passing. You MUST have actually run greps to fill this. 0-1 = corpus-exhaustive FP-safe → strongly favors FLIP_NOW even if the diagnostic looks architectural.' },
    corpus_fp_evidence: { type: 'string', description: 'The grep commands you ran and what they returned — the basis for corpus_fp_count.' },
    gap_location: { type: 'string', description: 'EXACT file:line in src/commonMain/kotlin/ where the additive change/new walker goes.' },
    recipe: { type: 'string', description: 'Concrete implementation: which function, what gate/branch, what shape it matches, why FP-safe (cite the corpus_fp_count). For ARCHITECTURAL name the blocker + missing infra.' },
    expected_diag: { type: 'string', description: 'The EXACT diagnostic to emit: code, position (line,col), full message text, related-info if any. Copy verbatim from the baseline.' },
    fp_risk: { type: 'string', enum: ['LOW', 'MED', 'HIGH'] },
    confidence: { type: 'number' },
    blocker: { type: 'string' },
  },
  required: ['test', 'verdict', 'ts_codes', 'corpus_fp_count', 'corpus_fp_evidence', 'gap_location', 'recipe', 'expected_diag', 'fp_risk', 'confidence', 'blocker'],
}

function prompt(name) {
  return `You are triaging ONE failing test in a Kotlin port of the TypeScript compiler (cwd = repo root). The test emits NO diagnostics (or wrong ones) but the baseline expects specific TS errors.

TEST base name: ${name}
SOURCE: find it under typescript-repo/tests/cases/ (compiler/ or conformance/) — the file is ${name}.ts
BASELINE: typescript-repo/tests/baselines/reference/${name}.errors.txt

GOAL: decide if this is a clean win (FLIP_NOW), a plausible attempt (ATTEMPT), or genuinely architectural (ARCHITECTURAL). The single most important signal is the CORPUS-FP-RARITY of the syntactic shape.

HARD-LEARNED LESSON you MUST apply (this fleet historically over-classifies ARCHITECTURAL): many checkJs/JS-modeling and other "architectural-looking" TS2339/TS2322/TS2367/TS2554 diagnostics are actually flippable with a DEDICATED WALKER gated to a CORPUS-EXHAUSTIVE shape. Three recent wins (B427/B428/B429) were all rated "architectural" by a prior agent fleet, yet each had a corpus-UNIQUE syntactic shape → a tiny gated walker fired the diagnostic with ZERO FP. The walkers gate to checkJs + the exact AST shape (e.g. "a class ctor containing Object.defineProperty(this,…)"; "exports.X as receiver of a deeper access"; "a declare-annotated property in a checkJs class") and are FP-safe BECAUSE only 0-1 corpus files share that shape.

STEPS (budget ~16 tool calls, then END by calling StructuredOutput):
1. Read the source .ts AND the .errors.txt baseline. Record exact @directives (target/module/strict/allowJs/checkJs/lib), the EXACT diagnostic code(s), position(s), full message text, and any !!! related TSxxxx lines.
2. Identify the SINGLE root reason we emit nothing/wrong. Grep src/commonMain/kotlin/ (Checker.kt is ~120k lines; also Parser.kt/Scanner.kt/Binder.kt/Transformer.kt/Emitter.kt/Type.kt/Types.kt) for the function that emits (or should emit) this code. Note the precise gate/branch that bails or is absent. Look at the recent dedicated walkers for the pattern: grep Checker.kt for "checkJsObjectDefinePropertyThisReads", "checkJsModuleExportsDeepReads", "checkJsConstructorThisReads", "checkJsAmbientDeclaredClassProperties".
3. **MANDATORY CORPUS-FP-RARITY GREP**: figure out the precise syntactic shape your proposed gate would match, then grep the WHOLE corpus to COUNT how many currently-PASSING files share it. Examples of good greps:
   - shape "Object.defineProperty(<fn>, ...)" → \`grep -rl "Object.defineProperty" typescript-repo/tests/cases/\` then inspect which are checkJs and which have the offending read.
   - shape "expando function property write inside a nested function" → grep for the function-name.property assignment pattern.
   - shape "JSDoc @type {unique symbol}" → \`grep -rl "unique symbol" typescript-repo/tests/cases/ | xargs grep -l "@type"\`.
   Fill corpus_fp_count with the number of PASSING files that would also match your gate (i.e. the FP surface), and corpus_fp_evidence with the commands+results. If corpus_fp_count is 0-1 (only the target(s)), a dedicated walker is FP-safe → lean FLIP_NOW even if the underlying type computation looks hard, AS LONG AS the diagnostic's message/position can be produced syntactically (without the missing type infra). If the MESSAGE TEXT requires a type the engine cannot synthesize (e.g. it must print a materialized mapped/conditional type, or a cross-file-inferred type name), then it stays ARCHITECTURAL — note exactly which part of the message blocks it.
4. Classify. corpus_fp_count 0-1 + message producible syntactically → FLIP_NOW. corpus_fp_count 0-1 but message needs type infra → ATTEMPT or ARCHITECTURAL (say which message piece blocks). corpus_fp_count >=2 (gate would FP on passing tests) → ATTEMPT/ARCHITECTURAL.

Your recipe + expected_diag must be concrete enough to implement WITHOUT re-reading the test.`
}

phase('Triage')
const results = await parallel(
  names.map((name) => () =>
    agent(prompt(name), { label: `triage:${name}`, phase: 'Triage', schema: SCHEMA, agentType: 'Explore' })
      .catch(() => null)
  )
)

const ok = results.filter(Boolean)
const order = { FLIP_NOW: 0, ATTEMPT: 1, ARCHITECTURAL: 2, MISSING_SOURCE: 3 }
ok.sort((a, b) => (order[a.verdict] - order[b.verdict]) || (a.corpus_fp_count - b.corpus_fp_count) || (b.confidence - a.confidence))

log(`Triage done: ${ok.length}/${names.length} returned.`)
for (const r of ok) {
  log(`[${r.verdict}] fp=${r.corpus_fp_count} conf=${r.confidence} ${r.test} (${r.ts_codes}) @ ${r.gap_location}`)
}
return ok
