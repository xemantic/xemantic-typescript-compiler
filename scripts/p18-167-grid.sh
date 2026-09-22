#!/usr/bin/env bash
# (CHK.136) the 8-profile BEFORE/AFTER binary grid for the GENERAL element-access WRITE
# check (`cheaGeneralElementWrite`).
#
# THIS GRID IS A REAL GATE AND THE COUNT SAYS SO.  A census of every element-access
# assignment in the eight profiles (length-preserving comment/string/regex mask + a
# bracket-matching scanner, 25/25 audited) reads 223 on the SMALLEST arm (the compiler
# profile) and 347 on the largest, overwhelmingly COMPUTED-index (214-337); the
# string-LITERAL index is 0 on all eight, so a change specific to that class would be a
# (CHK.124) control instead.  tsc's own sources are clean under tsgo 7.0.2, so EVERY row
# this grid adds is a false positive by definition and must be adjudicated, not explained.
#
# BEFORE is HEAD f409f517b, AFTER is the (CHK.136) tree.  Two snapshotted class dirs.
# REFUSES when the arms' Checker.class are byte-identical (an accidental self-comparison
# prints added=0 removed=0 on all eight and reads exactly like a clean bill of health —
# CLAUDE.md 853/946), when a capture is empty or TRUNCATED (`and N more error(s)` —
# round 811), and below 8 profiles (the pre-895 `tsc-project-*` glob matched exactly ONE).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18167/grid; mkdir -p "$OUT"
BEFORE=build/bench/p18167/classes-before
AFTER=build/bench/p18167/classes-after
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
sha256sum "$BEFORE/com/xemantic/typescript/compiler/Checker.class" | cut -d' ' -f1 > "$OUT/.before.sha.new"
status=0
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  for arm in before after; do
    dir="$BEFORE"; [[ "$arm" == after ]] && dir="$AFTER"
    # The BEFORE arm's binary never changes within a round, so its capture is reusable —
    # but ONLY while the class it was taken from is byte-identical (round 853: an
    # instrument silently reading a frozen binary prints a clean bill of health).
    if [[ "$arm" == before && -s "$OUT/$name.before.txt" ]]; then
      want="$(sha256sum "$BEFORE/com/xemantic/typescript/compiler/Checker.class" | cut -d' ' -f1)"
      [[ -f "$OUT/.before.sha" && "$(cat "$OUT/.before.sha")" == "$want" ]] && continue
    fi
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
mv "$OUT/.before.sha.new" "$OUT/.before.sha"
echo "grid status=$status"
exit $status
