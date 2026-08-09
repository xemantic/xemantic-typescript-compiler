#!/usr/bin/env bash
# (WARM.11) round 864 — the 8 dashboard profiles captured under BOTH side-table
# fills and diffed in both directions, plus an EMIT-mode tree comparison.
#
# The two arms are ONE binary selected by `--flowIndexLegacy`, not two class
# directories. That is deliberate and it is the stronger control here: the flag
# picks between the pre-864 whole-tree walk and the recorded-node fill inside the
# committed binary, so a stale or mis-pointed class dir (round 853) cannot make
# the arms agree, and there is no build-to-build variance between them. The
# freshness of the binary itself is checked separately, by a member this round
# added.
#
# Round 858: the classpath comes from `scripts/lib/dep-classpath.sh`, never from
# a cached `cp.txt`. Round 811: a capture containing "... and N more error(s)"
# is REFUSED, and so is an empty one.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="${1:-build/bench/r864-grid}"
mkdir -p "$OUT"
DEPS="$(scripts/lib/dep-classpath.sh --print)" || exit 1
CP_DIR="$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main"

declare -A DIRS=(
  [compiler]="$ROOT/build/bench/tsc-project-637d5746"
  [tsc-cli]="$ROOT/build/bench/tsc-tsc-637d5746"
  [jsTyping]="$ROOT/build/bench/tsc-jsTyping-637d5746"
  [deprecatedCompat]="$ROOT/build/bench/tsc-deprecatedCompat-637d5746"
  [typingsInstallerCore]="$ROOT/build/bench/tsc-typingsInstallerCore-637d5746"
  [services]="$ROOT/build/bench/tsc-services-637d5746"
  [server]="$ROOT/build/bench/tsc-server-637d5746"
  [harness]="$ROOT/build/bench/tsc-harness-637d5746"
)
PROFILES="compiler tsc-cli jsTyping deprecatedCompat typingsInstallerCore services server harness"

# Round 853's positive control, in its sharp form: the binary under test must
# contain a member that did not exist before this round.
javap -p -cp "$CP_DIR" com.xemantic.typescript.compiler.FlowIndex 2>/dev/null \
  | grep -q 'legacy' || {
  echo "REFUSED: the class dir predates (WARM.11)" >&2; exit 1; }
echo "class dir: $(find "$CP_DIR" -name '*.class' | wc -l) classes"

FAIL=0
for ARM in legacy fresh; do
  ARGS=()
  [[ $ARM == legacy ]] && ARGS=(--flowIndexLegacy)
  for P in $PROFILES; do
    java -Xmx4g -cp "$CP_DIR:$DEPS" com.xemantic.typescript.compiler.MainKt \
        --noEmit --listAll "${ARGS[@]}" "${DIRS[$P]}" > "$OUT/$ARM-$P.raw" 2>&1
    grep -a 'error TS' "$OUT/$ARM-$P.raw" | sort > "$OUT/$ARM-$P.txt"
    n=$(wc -l < "$OUT/$ARM-$P.txt")
    trunc=$(grep -ac 'more error(s)' "$OUT/$ARM-$P.raw")
    echo "$ARM $P count=$n trunc=$trunc"
    [[ "$trunc" != "0" ]] && { echo "  REFUSED: truncated" >&2; FAIL=1; }
    [[ "$n" == "0" ]] && { echo "  REFUSED: empty" >&2; FAIL=1; }
  done
done

echo "== diagnostics, both directions =="
for P in $PROFILES; do
  added=$(comm -13 "$OUT/legacy-$P.txt" "$OUT/fresh-$P.txt" | wc -l)
  removed=$(comm -23 "$OUT/legacy-$P.txt" "$OUT/fresh-$P.txt" | wc -l)
  echo "$P added=$added removed=$removed"
  [[ "$added" != "0" || "$removed" != "0" ]] && FAIL=1
done

echo "== EMIT mode, compiler profile =="
for ARM in legacy fresh; do
  ARGS=()
  [[ $ARM == legacy ]] && ARGS=(--flowIndexLegacy)
  rm -rf "${DIRS[compiler]}/dist"
  java -Xmx4g -cp "$CP_DIR:$DEPS" com.xemantic.typescript.compiler.MainKt \
      "${ARGS[@]}" "${DIRS[compiler]}" > "$OUT/emit-$ARM.log" 2>&1
  rm -rf "$OUT/dist-$ARM"
  cp -a "${DIRS[compiler]}/dist" "$OUT/dist-$ARM" 2>/dev/null || echo "  no dist for $ARM"
  echo "$ARM emitted files: $(find "$OUT/dist-$ARM" -type f 2>/dev/null | wc -l)"
done
rm -rf "${DIRS[compiler]}/dist"
if diff -r "$OUT/dist-legacy" "$OUT/dist-fresh" > "$OUT/emit.diff" 2>&1; then
  echo "emit tree: IDENTICAL"
else
  echo "emit tree: DIFFERS — see $OUT/emit.diff"; head -20 "$OUT/emit.diff"; FAIL=1
fi
echo "FAIL=$FAIL"
exit "$FAIL"
