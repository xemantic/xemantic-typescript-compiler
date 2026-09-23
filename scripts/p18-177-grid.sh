#!/usr/bin/env bash
# (P18.177) the 8-profile BEFORE/AFTER binary grid for (CHK.141)(b) — a CONTEXTUAL `this:`
# parameter must TYPE `this` in a function expression, at every position.
#
# THIS GRID IS A **GATE IN THE ADDING DIRECTION**, and the census says it is reached: each
# profile declares **35-38** `(this: ` parameters.  Measured today they produce **ZERO**
# TS2683 rows between them, so the grid cannot see the SUPPRESSION direction at all — what it
# is for is the collateral: this change TYPES `this` where it was `any`, so every body that
# reads a member of `this` through such a signature starts being checked, and a wrong answer
# lands as a TS2322/TS2339/TS2345 somewhere those 35-38 sites reach.
#
# The eight profiles carry only TS2591 x367, TS2304 x24, TS2584 x13, TS2503 x6, TS7006 x3,
# TS2339 x2 and TS2593 x1 — **zero TS2322 and zero TS2345** — and tsc's own sources are CLEAN
# under tsgo 7.0.2.  So every row this grid ADDS is a false positive by definition and must be
# adjudicated against tsgo, never explained.
#
# THE OTHER HALF OF THE GATE IS THE CORPUS: 19 ACTIVE `.errors.txt` baselines carry TS2683,
# and this change can silence any of them — a silenced TS2683 is the INTENDED direction only
# where tsgo is also silent, so each mover must be adjudicated rather than accepted.
#
# BEFORE is the round's snapshotted pre-change class dir, AFTER is the built tree.
# REFUSES when the arms' Checker.class are byte-identical (an accidental self-comparison
# prints added=0 removed=0 on all eight and reads exactly like a clean bill of health —
# CLAUDE.md rounds 853/946), when a capture is empty or TRUNCATED (`and N more error(s)` —
# round 811), and below 8 profiles (the pre-895 `tsc-project-*` glob matched exactly ONE).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18177/grid; mkdir -p "$OUT"
BEFORE=build/bench/p18177/classes-before
AFTER=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
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
  echo "$name: rows=$n added=$added removed=$removed"
  [[ "$added" -ne 0 || "$removed" -ne 0 ]] && status=1
done
echo "grid status=$status"
exit $status
