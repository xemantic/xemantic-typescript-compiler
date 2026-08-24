#!/usr/bin/env bash
# (INC.31) Re-take docs/language-service.md's COST TABLE on a REAL project.
#
# Supersedes scripts/round930-ls-cost.sh as the table's instrument. Its runner
# reaches four cells that one structurally cannot: `completionsAt`, `signatureHelpAt`,
# what a PREPARED check does (and does not) serve them, and a per-POOL peak-heap
# reading for the whole-program sweeps. It also takes SIX warm-up cycles rather than
# one — CLAUDE.md 2026-08-10: two identical arms sit 3.3% apart at WARMUP=2 and 0.8%
# at 6, and the spread BETWEEN process medians is the one quantity a verdict gates on.
#
# The wall figures are a property of a local artifact (tsc's own sources under
# build/bench) and of the box, so they cannot be pinned by the suite — the build
# COUNTS are, by LanguageServiceStateTest. This is the other half. It REFUSES rather
# than skips when the profile is absent (CLAUDE.md rounds 853/873: a gate that passes
# quietly where its input is missing is worse than no gate), and it verifies it
# actually RAN with a positive control rather than with its own exit code (round 947:
# a success message is not evidence that the thing under test executed).
#
# Usage: scripts/inc31-ls-cost.sh [<projectDir> [rotations]]
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

PROJECT="${1:-}"
if [[ -z "$PROJECT" ]]; then
  shopt -s nullglob
  for candidate in build/bench/tsc-project-*; do
    [[ -f "$candidate/tsconfig.json" ]] && PROJECT="$candidate"
  done
  shopt -u nullglob
fi
if [[ -z "$PROJECT" ]]; then
  echo "REFUSED: no compiler profile under build/bench." >&2
  echo "         Materialize one with: scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log" >&2
  exit 2
fi
if [[ ! -f "$PROJECT/tsconfig.json" ]]; then
  echo "REFUSED: '$PROJECT' holds no tsconfig.json, so it names no project." >&2
  exit 2
fi

ROTATIONS="${2:-4}"

CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
if [[ ! -f "$CLASSES/com/xemantic/typescript/compiler/project/Inc31CostMainKt.class" ]]; then
  echo "REFUSED: the runner is not in $CLASSES — build first:" >&2
  echo "         ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2
fi

# The classpath is resolved, never read from a cache: `build/bench/cp.txt` has ZERO
# readers by design (CLAUDE.md round 858 — a stale one names jars that still exist,
# so it measures something other than what ships, with no error anywhere).
# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"

CP="$CLASSES"
CP="$CP:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main"
CP="$CP:$DEPS"

OUT="$(mktemp -t inc31-ls-cost.XXXXXX)"
trap 'rm -f "$OUT"' EXIT

echo "project: $PROJECT  rotations: $ROTATIONS"
echo "commit:  $(git rev-parse --short HEAD 2>/dev/null || echo '<unknown>')  date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
# -Xmx6g because two rows are whole-program sweeps; the FLOOR is -Xmx2g (measured
# 2026-08-24: green at 2g, OutOfMemoryError at 1g), and the extra headroom keeps GC
# out of the timed rows.
set +e
java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.Inc31CostMainKt \
  "$PROJECT" "$ROTATIONS" 2>&1 | tee "$OUT"
STATUS="${PIPESTATUS[0]}"
set -e
if [[ "$STATUS" -ne 0 ]]; then
  echo "REFUSED: the runner exited $STATUS" >&2
  exit "$STATUS"
fi

# ---- POSITIVE CONTROL -------------------------------------------------------
# Exit 0 says the JVM finished, not that anything was measured: a runner whose
# every arm answered instantly would print a tidy table of zeros and exit 0 too.
# So the wrapper asserts that the batteries it exists for actually produced rows,
# and that the anchor row — a plain full rebuild of a 78-file program — is a real
# number rather than a zero.
fail=0
for row in rebuild.full diagnosticsOf.mid.fresh completions.mid.cold \
           signatureHelp.mid.cold quickInfo.big.first referencesAt.clean; do
  if ! grep -aq "^MED $row " "$OUT"; then
    echo "REFUSED: the run produced no '$row' row — it measured nothing." >&2
    fail=1
  fi
done
REBUILD="$(grep -a '^MED rebuild.full ' "$OUT" | awk '{print $3}' || true)"
if [[ -z "$REBUILD" || "$REBUILD" -lt 100 ]]; then
  echo "REFUSED: rebuild.full read '${REBUILD:-<absent>}' ms — a whole-program rebuild" >&2
  echo "         cannot be that cheap, so the arms are not measuring a compile." >&2
  fail=1
fi
if ! grep -aq '^sweep diagnosticsOf ' "$OUT"; then
  echo "REFUSED: the per-file sweep produced no median." >&2
  fail=1
fi
if ! grep -aq '^residue ' "$OUT"; then
  echo "REFUSED: the residue decomposition produced no row." >&2
  fail=1
fi
[[ "$fail" -eq 0 ]] || exit 3
echo "positive control: OK (rebuild.full=${REBUILD}ms, all named rows present)"
