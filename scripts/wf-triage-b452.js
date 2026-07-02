/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * xemantic-typescript-compiler - a conformant TypeScript compiler and type
 * checker that runs on JVM, native, and WebAssembly
 * Copyright (C) 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * As a special exception, this file contains Helper Code covered by the
 * xemantic-typescript-compiler Output Exception; additional permissions
 * are granted as described in the file LICENSE-EXCEPTION.
 */

export const meta = {
  name: 'triage-b452',
  description: 'Skeptical read-only triage of rare-code failing tests for dedicated-walker wins',
  phases: [{ title: 'Triage' }],
}

const CANDIDATES = args && args.length ? args : [
  'abstractPropertyNegative',
  'constEnumErrors',
  'restParameterWithBindingPattern3',
  'thislessFunctionsNotContextSensitive1',
  'objectLiteralExcessProperties',
  'optionalPropertiesSyntax',
  'indexerConstraints2',
  'strictModeReservedWordInClassDeclaration',
  'jsFileCompilationBindMultipleDefaultExports',
  'untypedFunctionCallsWithTypeParameters1',
  'didYouMeanElaborationsForExpressionsWhichCouldBeCalled',
  'parametersSyntaxErrorNoCrash1',
  'transformNestedGeneratorsWithTry',
  'expressionTypeNodeShouldError',
  'genericDefaultsErrors',
  'weakType',
  'operationsAvailableOnPromisedType',
  'checkSuperCallBeforeThisAccess',
]

const SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['base', 'verdict', 'confidence', 'rarest_code', 'pieces', 'fp_risk', 'sketch'],
  properties: {
    base: { type: 'string' },
    verdict: { type: 'string', enum: ['CONTAINED', 'BLOCKER', 'TSGO_IRRELEVANT'] },
    confidence: { type: 'number' },
    rarest_code: { type: 'string' },
    pieces: { type: 'integer', description: 'how many independent gaps must be closed to flip the test' },
    fp_risk: { type: 'string', enum: ['LOW', 'MED', 'HIGH'] },
    sketch: { type: 'string', description: 'if CONTAINED: exact dedicated-walker plan incl. spans; else why blocked' },
  },
}

const results = await pipeline(
  CANDIDATES,
  (base) => agent(`You are triaging a FAILING TypeScript-compiler-port test for a possible SURGICAL dedicated-walker fix. Be SKEPTICAL: the default truth is that the remaining failures are architectural. Only call something CONTAINED if you can name a SINGLE self-contained AST/syntactic/grammar check with a CORPUS-RARE false-positive surface that would flip the WHOLE test.

Test base name: ${base}

Steps (read-only — do NOT edit any file):
1. Find + read the source: \`typescript-repo/tests/cases/{compiler,conformance}/**/${base}.ts(x)\` (use bash find).
2. Read the expected baseline: \`typescript-repo/tests/baselines/reference/${base}.errors.txt\` OR the target-variant form \`${base}(target=*).errors.txt\` (ls the dir, grep). Extract every expected \`error TSxxxx\` with its (line,col) and the message.
3. Grep our port \`src/commonMain/kotlin/Checker.kt\` (and Parser.kt/Binder.kt) for each expected code to see what already exists and why it might not fire here.
4. Count corpus FP surface: \`grep -rl "TS<rarecode>" typescript-repo/tests/baselines/reference/*.errors.txt | wc -l\` — how many baselines use the rarest code. Few = small FP surface.

Decide:
- CONTAINED = ONE dedicated walker (gated to a corpus-rare syntactic/AST shape) flips the ENTIRE test (all expected diagnostics), with LOW/MED fp risk, and you can write the exact span computation. State pieces=1 (or 2 if two tightly-coupled but both mechanical).
- BLOCKER = needs type-engine assignability / generic inference / flow narrowing / cross-file scope / mapped-conditional-type resolution / evolving-type — anything where a diagnostic depends on a TYPE we don't compute. State pieces = number of distinct gaps and the blocker name.
- TSGO_IRRELEVANT = the test targets a removed feature (legacy ES3/ES5 emit, AMD/System/UMD, removed options, classic node resolution, JSDoc @enum/@constructor).

Return ONLY the structured object. confidence is your calibrated probability that a single focused implementation session flips the WHOLE test with zero regressions. Be honest — over-classifying CONTAINED wastes a downstream implementation cycle.`,
    { label: `triage:${base}`, phase: 'Triage', schema: SCHEMA, agentType: 'Explore' })
)

const ok = results.filter(Boolean)
const contained = ok.filter(r => r.verdict === 'CONTAINED').sort((a, b) => b.confidence - a.confidence)
const blockers = ok.filter(r => r.verdict === 'BLOCKER')
log(`CONTAINED: ${contained.length} | BLOCKER: ${blockers.length} | IRRELEVANT: ${ok.filter(r=>r.verdict==='TSGO_IRRELEVANT').length}`)
return { contained, blockers, all: ok }
