#!/usr/bin/env bash
# ROUND 853 — re-take round 848's (SERVE.1) 14-arm flag sweep on the FIXED harness.
#
# WHY THIS EXISTS. Round 848 recorded "14 arms on the compiler profile: all
# byte-identical, 46 lines, md5 4090b73e, added=0 removed=0 — including
# --flowScanBogus, whose job is to corrupt the scanner". Round 852 then found
# that `scripts/grid838.sh` (and anything else reading
# `build/bench/xtsc-classpath.txt`) had been running the ROOT project's
# pre-module-split class dir — a compiler built 2026-08-07 23:39 UTC, i.e.
# BEFORE round 848 itself. "Every arm is byte-identical" is exactly what a
# binary that ignores all 14 flags would produce, so the classification had to
# be re-taken against a binary that provably contains the code under test.
#
# RECIPE (quoted with every digest, per round 841): compiler profile,
# `--noEmit --listAll`, `grep 'error TS'`, project prefix `sed`-stripped,
# `sort`. That is grid838.sh's own recipe — the portable one.
#
# The verdict that travels across rounds is `added=0 removed=0` against the
# baseline arm captured in THIS run, never a cross-round md5 (round 841).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?usage: round853-serve1-arms.sh <outdir>}"
mkdir -p "$OUT"

CP="$(cat "$ROOT/build/bench/xtsc-classpath.txt")"
# Same guard as grid838.sh — this script is worthless without it.
case "$CP" in
  *xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main*) ;;
  *) echo "error: build/bench/xtsc-classpath.txt is stale (pre-module-split)" >&2; exit 1 ;;
esac
# And a POSITIVE control on the binary itself: the arms are meaningless unless
# the classes under test are the ones the round is about.
for c in ModeLedger; do
  if ! find "$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main" \
        -name "$c*.class" | grep -q .; then
    echo "error: $c missing from the class dir — this is a pre-848 binary" >&2; exit 1
  fi
done

PROJ="$ROOT/build/bench/tsc-project-637d5746"

run_arm() {
  local name="$1"; shift
  java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
      --noEmit --listAll "$@" "$PROJ" > "$OUT/$name.raw" 2>&1 || true
  grep -a 'error TS' "$OUT/$name.raw" | sed "s#${PROJ}/##g" | sort > "$OUT/$name.txt"
  local n trunc md5
  n=$(wc -l < "$OUT/$name.txt")
  trunc=$(grep -ac 'more error(s)' "$OUT/$name.raw" || true)
  md5=$(md5sum "$OUT/$name.txt" | cut -d' ' -f1)
  echo "$name count=$n trunc=$trunc md5=$md5"
  [[ "$trunc" == "0" ]] || { echo "  REFUSED: truncated capture" >&2; return 1; }
  [[ "$n" != "0" ]]     || { echo "  REFUSED: empty capture" >&2; return 1; }
}

FAIL=0
run_arm baseline    || FAIL=1
run_arm baseline2   || FAIL=1
run_arm flowScanLegacy        --flowScanLegacy        || FAIL=1
run_arm flowScanBogus         --flowScanBogus         || FAIL=1
run_arm flowEagerSet          --flowEagerSet          || FAIL=1
run_arm argNarrowGateOff      --argNarrowGateOff      || FAIL=1
run_arm dispatchGated         --dispatchGated         || FAIL=1
run_arm ianyGateOff           --ianyGateOff           || FAIL=1
run_arm ianyArgGateOff        --ianyArgGateOff        || FAIL=1
run_arm cmamPreGate           --cmamPreGate           || FAIL=1
run_arm ccetPreGate           --ccetPreGate           || FAIL=1
run_arm verifyDeferSuppression --verifyDeferSuppression || FAIL=1
run_arm verifyUnionRetry      --verifyUnionRetry      || FAIL=1
run_arm verifyLoopRetry       --verifyLoopRetry       || FAIL=1
run_arm verifyImplRelated     --verifyImplRelated     || FAIL=1
run_arm workers4              --workers 4             || FAIL=1

echo
echo "=== added/removed against the baseline arm captured in THIS run ==="
for f in "$OUT"/*.txt; do
  n="$(basename "$f" .txt)"
  [[ "$n" == "baseline" ]] && continue
  add=$(comm -13 "$OUT/baseline.txt" "$f" | wc -l)
  rem=$(comm -23 "$OUT/baseline.txt" "$f" | wc -l)
  echo "$n added=$add removed=$rem"
done
exit "$FAIL"
