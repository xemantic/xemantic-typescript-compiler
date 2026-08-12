#!/usr/bin/env bash
# (WARM.19) round 895 — the 8-profile output grid for the whole-source scan filter.
#
# ONE binary, two arms: `--srcScanFilterOff` restores the pre-895 unfiltered
# path (round 795), so the comparison cannot be confounded by a stale class
# directory or a differently-built jar — the arms differ ONLY in whether the
# n-gram filter is consulted before each whole-source scan.
#
# The risk this grid exists for is a silently DELETED diagnostic: a false
# negative from the filter makes a pin walker skip its whole body, which no
# counter and no cost gate can see.
#
# Both arms run the SAME command with the SAME `--listAll` (round 811: a flag
# patched into one arm's argument vector is silently overwritten by the next
# line of bench-compile-tsc.sh, truncating that capture to 30 diagnostics and
# reporting a regression that does not exist). The differ REFUSES a capture
# containing `and N more error(s)` and refuses an empty one (round 804).
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round895-grid
mkdir -p "$OUT"

MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
CP="$MAIN:$DEPS"

[[ -f "$MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || {
  echo "REFUSED: main class dir does not hold MainKt"; exit 1; }
# round 853: a gate reading a class DIRECTORY needs a positive control that the
# code under test is actually in it — a prepended stale dir answers +0.00%
# forever and reads exactly like a clean bill of health.
[[ -f "$MAIN/com/xemantic/typescript/compiler/SourceScanFilter.class" ]] || {
  echo "REFUSED: class dir predates round 895 (no SourceScanFilter)"; exit 1; }

status=0
# ALL EIGHT profiles. `bench-compile-tsc.sh` names the compiler profile
# `tsc-project-<commit8>` (a historical name) and the other seven
# `tsc-<name>-<commit8>`, so the glob every previous grid harness here used —
# `build/bench/tsc-project-*` — matched the compiler profile and NOTHING ELSE.
# A profile dir is identified by carrying a tsconfig.json, which is also what
# refuses the round-895 output dir and the stray capture files in build/bench.
shopt -s nullglob
profiles=()
for d in build/bench/tsc-*; do
  [[ -d "$d" && -f "$d/tsconfig.json" ]] && profiles+=("$d")
done
if [[ "${#profiles[@]}" -lt 8 ]]; then
  echo "REFUSED: only ${#profiles[@]} profile(s) present — materialize with" \
       "scripts/bench-compile-tsc.sh --project all --no-emit --no-log"
  exit 1
fi
echo "profiles: ${#profiles[@]}"

for proj in "${profiles[@]}"; do
  [[ -d "$proj" ]] || continue
  name="$(basename "$proj")"
  for arm in on off; do
    args=(-Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --listAll)
    [[ "$arm" == "off" ]] && args+=(--srcScanFilterOff)
    java "${args[@]}" "$proj" > "$OUT/$name.$arm.raw" 2>&1
    grep -a 'error TS' "$OUT/$name.$arm.raw" | sort > "$OUT/$name.$arm.txt"
    if grep -qa 'more error(s)' "$OUT/$name.$arm.raw"; then
      echo "REFUSED $name/$arm: capture truncated"; status=1
    fi
    if [[ ! -s "$OUT/$name.$arm.txt" ]]; then
      echo "REFUSED $name/$arm: empty capture"; status=1
    fi
  done
  added=$(comm -13 "$OUT/$name.off.txt" "$OUT/$name.on.txt" | wc -l)
  removed=$(comm -23 "$OUT/$name.off.txt" "$OUT/$name.on.txt" | wc -l)
  n=$(wc -l < "$OUT/$name.on.txt")
  echo "$name: $n diagnostics  added=$added removed=$removed"
  [[ "$added" -eq 0 && "$removed" -eq 0 ]] || status=1
done
echo "grid exit=$status"
exit $status
