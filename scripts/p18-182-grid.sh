#!/usr/bin/env bash
# (P18.182) the 8-profile BEFORE/AFTER binary grid for (CHK.150) rung (3), X1 — the contextual
# type of an argument through OVERLOAD selection. harness carries the one live overloaded-callback
# site the census found (`harnessGlobals.ts:23`, 3 TS7006 rows), so for harness this is a real gate.
#
# THE GRID IS A WEAK GATE HERE: the queue item's census found one live instance on the profiles
# (`harness/src/harness/harnessGlobals.ts:23`, an overloaded `assert.deepEqual`, carrying all 3 of
# harness's TS7006 rows) and no TS2322/TS2345 that could show the value half. The corpus (233
# active subtests / 159 fixtures matching the union-or-overload-with-callback heuristic) is the
# real gate; a green grid here is a control that nothing else moved.
#
# BEFORE is the orchestrator's snapshot of the (P18.181) landed binary (Checker.class md5 5bdbd9f9,
# the binary the green suite ran on); AFTER is the built tree.
# REFUSES when the arms' Checker.class are byte-identical, when a capture is empty or TRUNCATED,
# and below 8 profiles.
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18182/grid; mkdir -p "$OUT"
BEFORE=build/bench/p18182/classes-before
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
