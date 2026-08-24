#!/usr/bin/env bash
# (INC.33) PRICE the widening of the caret channels — the measurement behind the
# refusal recorded in `Inc33WidenMain`'s KDoc.
#
# `completionsAt` / `signatureHelpAt` name a SINGLE caret span, so a completion in an
# already-hovered buffer still builds. The tempting fix is to widen the file-wide
# capture to carry member / scope / signature anchors, exactly as (INC.13) widened the
# TYPE channel for +9..+17 ms. This is what says whether that trade holds — it does
# not, by 1.4x on binder.ts and 12x on checker.ts, plus a 48x..205x retention blow-up.
#
# Modes:
#   census    populations only, no build (seconds)
#   smallmid  the two cheap files, every arm
#   big       checker.ts, every arm (one draw takes ~1 minute of builds)
#   <absent>  all three
#
# Usage: scripts/inc33-widen-cost.sh [rotations] [census|smallmid|big] [<projectDir>]
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

ROTATIONS="${1:-3}"
MODE="${2:-all}"
PROJECT="${3:-}"
if [[ -z "$PROJECT" ]]; then
  shopt -s nullglob
  for candidate in build/bench/tsc-project-*; do
    [[ -f "$candidate/tsconfig.json" ]] && PROJECT="$candidate"
  done
  shopt -u nullglob
fi
# REFUSES rather than skips when its input is absent — CLAUDE.md rounds 853/873: a
# gate that passes quietly where its artifact is missing is worse than no gate.
if [[ -z "$PROJECT" || ! -f "$PROJECT/tsconfig.json" ]]; then
  echo "REFUSED: no compiler profile under build/bench (or '$PROJECT' names no project)." >&2
  echo "         Materialize one with: scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log" >&2
  exit 2
fi

CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
if [[ ! -f "$CLASSES/com/xemantic/typescript/compiler/project/Inc33WidenMainKt.class" ]]; then
  echo "REFUSED: the runner is not in $CLASSES — build first:" >&2
  echo "         ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2
fi

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES"
CP="$CP:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main"
CP="$CP:$DEPS"

OUT="$(mktemp -t inc33-widen.XXXXXX)"
trap 'rm -f "$OUT"' EXIT

echo "project: $PROJECT  rotations: $ROTATIONS  mode: $MODE"
echo "commit:  $(git rev-parse --short HEAD 2>/dev/null || echo '<unknown>')  date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
set +e
java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.Inc33WidenMainKt \
  "$PROJECT" "$ROTATIONS" "$MODE" 2>&1 | tee "$OUT"
STATUS="${PIPESTATUS[0]}"
set -e
[[ "$STATUS" -eq 0 ]] || { echo "REFUSED: the runner exited $STATUS" >&2; exit "$STATUS"; }

# ---- POSITIVE CONTROL -------------------------------------------------------
# Exit 0 says the JVM finished, not that anything was measured (round 947): a runner
# whose every arm answered instantly would print a tidy table of zeros and exit 0 too.
fail=0
grep -aq '^CENSUS ' "$OUT" || { echo "REFUSED: no CENSUS row — no population was enumerated." >&2; fail=1; }
if [[ "$MODE" != "census" ]]; then
  grep -aq '^ANSWER ' "$OUT" || { echo "REFUSED: no ANSWER row — no capture came back." >&2; fail=1; }
  grep -aq '^MED .*\.all\.file ' "$OUT" || { echo "REFUSED: the widened arm produced no row." >&2; fail=1; }
  # The anchor: a narrowed build of a real 78-file program is not a sub-100 ms thing,
  # so a base arm under that floor means the arms are not measuring a compile at all.
  BASE="$(grep -a '^MED .*\.base\.noCapture ' "$OUT" | head -1 | awk '{print $3}' || true)"
  if [[ -z "$BASE" || "$BASE" -lt 50 ]]; then
    echo "REFUSED: base.noCapture read '${BASE:-<absent>}' ms — that is not a compile." >&2
    fail=1
  fi
  # ...and every population must be non-empty, or an arm is timing an empty request.
  if grep -aqE '^CENSUS .*(receivers=0|calls=0|scopeOwners=0|occurrenceSpans=0)' "$OUT"; then
    echo "REFUSED: a census population is EMPTY, so its arm measured nothing." >&2
    fail=1
  fi
fi
[[ "$fail" -eq 0 ]] || exit 3
echo "positive control: OK"
