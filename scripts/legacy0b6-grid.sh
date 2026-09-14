#!/usr/bin/env bash
# (LEGACY.0b) step 6 — the 8-profile BEFORE/AFTER binary grid for family F6d: TS2303
# reported at EVERY alias declaration on a circular alias chain, at all four walkers.
#
# WHAT IT IS AND IS NOT, MEASURED RATHER THAN ASSUMED ((CHK.124)).  The grid COUNTS TS2303
# per profile as well as diffing, and the count is **0 in BOTH arms on all eight** (and on
# cronstrue, marked and the 600/2400-file generated projects) — so not one of the four
# changed emission paths FIRES on any of them and this is a CONTROL, not a coverage gate.
# tsc's own sources are full of `import X = require(...)`, `export =` and named re-exports,
# and none of them is circular; the corpus is what carries the positive half.
#
# What the grid DOES establish is CONFINEMENT, which is the direction that can regress here:
# the change only ever ADDS rows (each walker's cycle DETECTION is untouched — only the
# number of rows emitted per detected cycle moved), so a widened rule that started reporting
# outside a genuine cycle would print `added=N` on a profile.  It prints 0.
#
# BEFORE is HEAD c0c106c9, AFTER is the (LEGACY.0b) step 6 tree.  Two snapshotted class
# dirs, because the change is not switchable at run time.  REFUSES when the arms'
# Checker.class are byte-identical (an accidental self-comparison prints added=0 removed=0
# on all eight and reads exactly like a clean bill of health — CLAUDE.md 853/946), when a
# capture is empty or TRUNCATED (`and N more error(s)` — round 811), and below 8 profiles
# (the pre-895 `tsc-project-*` glob matched exactly ONE).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/legacy0b6/grid; mkdir -p "$OUT"
BEFORE=build/bench/legacy0b6/classes-before
AFTER=build/bench/legacy0b6/classes-after
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
for d in "$BEFORE" "$AFTER"; do
  [[ -f "$d/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: $d holds no MainKt"; exit 1; }
done
a="$(sha256sum "$BEFORE/com/xemantic/typescript/compiler/Checker.class" | cut -d' ' -f1)"
b="$(sha256sum "$AFTER/com/xemantic/typescript/compiler/Checker.class" | cut -d' ' -f1)"
[[ "$a" == "$b" ]] && { echo "REFUSED: the two arms' Checker.class are byte-identical"; exit 1; }
shopt -s nullglob
profiles=()
for d in build/bench/tsc-*; do [[ -d "$d" && -f "$d/tsconfig.json" ]] && profiles+=("$d"); done
[[ "${#profiles[@]}" -lt 8 ]] && { echo "REFUSED: only ${#profiles[@]} profile(s)"; exit 1; }
echo "profiles: ${#profiles[@]}"
status=0
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  for arm in before after; do
    dir="$BEFORE"; [[ "$arm" == after ]] && dir="$AFTER"
    java -Xmx4g -cp "$dir:$DEPS" com.xemantic.typescript.compiler.MainKt \
      --noEmit --listAll "$proj" > "$OUT/$name.$arm.raw" 2>&1
    [[ -s "$OUT/$name.$arm.raw" ]] || { echo "$name/$arm: REFUSED — empty capture"; status=1; continue; }
    grep -q "and [0-9]* more error" "$OUT/$name.$arm.raw" && { echo "$name/$arm: REFUSED — truncated"; status=1; continue; }
    grep 'error TS' "$OUT/$name.$arm.raw" | sed "s|$proj/||" | sort > "$OUT/$name.$arm.txt"
  done
  added=$(comm -13 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
  removed=$(comm -23 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
  n=$(wc -l < "$OUT/$name.after.txt")
  c2303b=$(grep -c 'error TS2303' "$OUT/$name.before.txt")
  c2303a=$(grep -c 'error TS2303' "$OUT/$name.after.txt")
  echo "$name: rows=$n added=$added removed=$removed TS2303 before=$c2303b after=$c2303a"
  [[ "$added" -ne 0 || "$removed" -ne 0 ]] && status=1
done
echo "grid status=$status"
exit $status
