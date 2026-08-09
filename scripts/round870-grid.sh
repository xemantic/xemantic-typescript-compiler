#!/usr/bin/env bash
# (WARM.17) round 870 — the 8 dashboard profiles captured from TWO class
# directories and diffed in both directions.
#
# Round 864's grid could select its two arms with a flag inside one binary; this
# round's change is a probe-gated INSTRUMENT, so its "arms" are the committed
# binary and the one before it, and the grid has to be a two-class-dir one.
#
# Round 853's positive control, in both directions: the AFTER dir must contain
# `ModuleSymbolScanIndexKt` (a class that did not exist before this round) and the BEFORE dir
# must NOT — a mis-pointed or stale dir then cannot make the arms agree by being
# the same dir twice. Round 811: a capture containing "... and N more error(s)"
# is REFUSED, and so is an empty one. Round 858: the classpath comes from
# `scripts/lib/dep-classpath.sh`, never from a cached `cp.txt`.
#
#   usage: scripts/round870-grid.sh <before-class-dir> <after-class-dir> [outdir]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
BEFORE="${1:?before class dir}"
AFTER="${2:?after class dir}"
OUT="${3:-build/bench/r870-grid}"
mkdir -p "$OUT"
DEPS="$(scripts/lib/dep-classpath.sh --print)" || exit 1

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

javap -p -cp "$AFTER" com.xemantic.typescript.compiler.ModuleSymbolScanIndexKt >/dev/null 2>&1 || {
  echo "REFUSED: the AFTER dir has no ModuleSymbolScanIndexKt — it predates (WARM.17)" >&2; exit 1; }
if javap -p -cp "$BEFORE" com.xemantic.typescript.compiler.ModuleSymbolScanIndexKt >/dev/null 2>&1; then
  echo "REFUSED: the BEFORE dir HAS ModuleSymbolScanIndexKt — the two arms are the same build" >&2; exit 1
fi
echo "before: $(find "$BEFORE" -name '*.class' | wc -l) classes   after: $(find "$AFTER" -name '*.class' | wc -l) classes"

FAIL=0
for ARM in before after; do
  DIR="$BEFORE"; [[ $ARM == after ]] && DIR="$AFTER"
  for P in $PROFILES; do
    java -Xmx4g -cp "$DIR:$DEPS" com.xemantic.typescript.compiler.MainKt \
        --noEmit --listAll "${DIRS[$P]}" > "$OUT/$ARM-$P.raw" 2>&1
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
  added=$(comm -13 "$OUT/before-$P.txt" "$OUT/after-$P.txt" | wc -l)
  removed=$(comm -23 "$OUT/before-$P.txt" "$OUT/after-$P.txt" | wc -l)
  echo "$P added=$added removed=$removed"
  [[ "$added" != "0" || "$removed" != "0" ]] && FAIL=1
done

if [[ $FAIL -eq 0 ]]; then echo "GRID CLEAN"; else echo "GRID FAILED"; fi
exit $FAIL
