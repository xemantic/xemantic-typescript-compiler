#!/usr/bin/env bash
# (WARM.21) round 874 — the 8 dashboard profiles, both TAV gate arms, ONE binary.
#
# The arms are selected by `--tavGateOff` inside the committed binary (round
# 795), which is strictly stronger than a two-class-dir grid: a stale or
# mis-pointed class dir cannot make the arms agree by being the same dir twice,
# because there is only one dir.
#
# THE CONTROL THIS GRID NEEDS, AND WHY (round 793). The TAV pass emits ZERO
# TS2693/TS2708 on the compiler profile, so "the two arms produce the same
# diagnostics" could be the agreement of two compilers neither of which is
# doing anything. Both arms therefore run with `--frontEnd`, whose census
# prints `gate refused N (P%), gateOff=<bool>` — so each profile records that
# the gate REALLY refused a large population on the ON arm and refused NOTHING
# on the OFF arm, before any diff is read. An arm whose census line does not
# match its flag is a dead instrument, not a clean result.
#
# Round 811: a capture containing "... and N more error(s)" is REFUSED, and so
# is an empty one. Round 858: the classpath comes from
# `scripts/lib/dep-classpath.sh`, never from a cached `cp.txt`.
#
#   usage: scripts/round874-grid.sh [outdir]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="${1:-build/bench/r874-grid}"
mkdir -p "$OUT"
DEPS="$(scripts/lib/dep-classpath.sh --print)" || exit 1
MAIN="$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main"

# Round 853's positive control that the binary under test is the one in the dir.
javap -p -cp "$MAIN" com.xemantic.typescript.compiler.TavGate >/dev/null 2>&1 || {
  echo "REFUSED: the class dir has no TavGate — it predates (WARM.21)" >&2; exit 1; }
echo "classes: $(find "$MAIN" -name '*.class' | wc -l)"

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

FAIL=0
for ARM in gated ungated; do
  EXTRA=()
  [[ $ARM == ungated ]] && EXTRA=(--tavGateOff)
  for P in $PROFILES; do
    java -Xmx4g -cp "$MAIN:$DEPS" com.xemantic.typescript.compiler.MainKt \
        --noEmit --listAll --frontEnd "${EXTRA[@]+"${EXTRA[@]}"}" "${DIRS[$P]}" \
        > "$OUT/$ARM-$P.raw" 2>&1
    grep -a 'error TS' "$OUT/$ARM-$P.raw" | sort > "$OUT/$ARM-$P.txt"
    n=$(wc -l < "$OUT/$ARM-$P.txt")
    trunc=$(grep -ac 'more error(s)' "$OUT/$ARM-$P.raw")
    census=$(grep -a 'tav census' "$OUT/$ARM-$P.raw" | head -1)
    refused=$(sed -n 's/.*gate refused \([0-9]*\) .*/\1/p' <<<"$census")
    gateoff=$(sed -n 's/.*gateOff=\([a-z]*\).*/\1/p' <<<"$census")
    echo "$ARM $P count=$n trunc=$trunc refused=${refused:-NONE} gateOff=${gateoff:-NONE}"
    [[ "$trunc" != "0" ]] && { echo "  REFUSED: truncated" >&2; FAIL=1; }
    [[ "$n" == "0" ]] && { echo "  REFUSED: empty" >&2; FAIL=1; }
    # The instrument control: the gated arm must refuse a real population and
    # the ungated arm must refuse nothing at all.
    if [[ $ARM == gated ]]; then
      [[ "$gateoff" == "false" ]] || { echo "  REFUSED: gated arm reports gateOff=$gateoff" >&2; FAIL=1; }
      [[ "${refused:-0}" -gt 1000 ]] || { echo "  REFUSED: gated arm refused ${refused:-0}" >&2; FAIL=1; }
    else
      [[ "$gateoff" == "true" ]] || { echo "  REFUSED: ungated arm reports gateOff=$gateoff" >&2; FAIL=1; }
      [[ "${refused:-1}" == "0" ]] || { echo "  REFUSED: ungated arm refused ${refused}" >&2; FAIL=1; }
    fi
  done
done

echo "== diagnostics, both directions =="
for P in $PROFILES; do
  added=$(comm -13 "$OUT/ungated-$P.txt" "$OUT/gated-$P.txt" | wc -l)
  removed=$(comm -23 "$OUT/ungated-$P.txt" "$OUT/gated-$P.txt" | wc -l)
  echo "$P added=$added removed=$removed"
  [[ "$added" != "0" || "$removed" != "0" ]] && FAIL=1
done

if [[ $FAIL -eq 0 ]]; then echo "GRID CLEAN"; else echo "GRID FAILED"; fi
exit $FAIL
