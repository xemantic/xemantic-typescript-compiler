#!/usr/bin/env bash
# (CHK.97) stage 1 — 8-profile grid, AFTER arm only.
# The BEFORE captures are last round's AFTER captures, i.e. the output of the binary
# committed as HEAD; copied in and hash-guarded by the caller. Refuses an empty or
# TRUNCATED capture (round 811) and fewer than 8 profiles (round 895).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/chk97/grid
AFTER=build/bench/chk97/classes-after
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
[[ -f "$AFTER/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: no MainKt"; exit 1; }
shopt -s nullglob
profiles=()
for d in build/bench/tsc-*; do [[ -d "$d" && -f "$d/tsconfig.json" ]] && profiles+=("$d"); done
[[ "${#profiles[@]}" -lt 8 ]] && { echo "REFUSED: only ${#profiles[@]} profile(s)"; exit 1; }
echo "profiles: ${#profiles[@]}"
status=0
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  [[ -s "$OUT/$name.before.txt" ]] || { echo "$name: REFUSED — no BEFORE capture"; status=1; continue; }
  java -Xmx4g -cp "$AFTER:$DEPS" com.xemantic.typescript.compiler.MainKt \
    --noEmit --listAll "$proj" > "$OUT/$name.after.raw" 2>&1
  [[ -s "$OUT/$name.after.raw" ]] || { echo "$name: REFUSED — empty capture"; status=1; continue; }
  grep -q "and [0-9]* more error" "$OUT/$name.after.raw" && { echo "$name: REFUSED — truncated"; status=1; continue; }
  grep 'error TS' "$OUT/$name.after.raw" | sed "s|$proj/||" | sort > "$OUT/$name.after.txt"
  added=$(comm -13 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
  removed=$(comm -23 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
  n=$(wc -l < "$OUT/$name.after.txt")
  echo "$name: rows=$n added=$added removed=$removed"
  [[ "$added" -ne 0 || "$removed" -ne 0 ]] && status=1
done
echo "grid status=$status"
exit $status
