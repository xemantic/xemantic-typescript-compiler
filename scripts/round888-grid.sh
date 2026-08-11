#!/usr/bin/env bash
# (WARM.13b) round 888 — the 8-profile output grid for the spine skip mask.
#
# ONE binary, two arms: `--spineMaskOff` restores the pre-888 straight-line
# prologue (round 795), so the comparison cannot be confounded by a stale class
# directory or a differently-built jar — the arms differ ONLY in the mask array.
#
# Both arms run the SAME command with the SAME `--listAll` (round 811: a flag
# patched into one arm's argument vector is silently overwritten by the next
# line of bench-compile-tsc.sh, truncating that capture to 30 diagnostics and
# reporting a regression that does not exist). The differ REFUSES a capture
# containing `and N more error(s)` and refuses an empty one (round 804).
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round888-grid
mkdir -p "$OUT"

MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
CP="$MAIN:$DEPS"

[[ -f "$MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || {
  echo "REFUSED: main class dir does not hold MainKt"; exit 1; }

status=0
for proj in build/bench/tsc-project-*; do
  [[ -d "$proj" ]] || continue
  name="$(basename "$proj")"
  for arm in on off; do
    args=(-Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --listAll)
    [[ "$arm" == "off" ]] && args+=(--spineMaskOff)
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
