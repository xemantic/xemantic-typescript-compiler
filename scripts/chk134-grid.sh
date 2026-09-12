#!/usr/bin/env bash
# (CHK.134) decomposition (1) — the 8-profile BEFORE/AFTER binary grid for `.call`/`.apply` on a
# function VALUE (the member-miss augmentation with the receiver-built `CallableFunction` member,
# tsc's `getPropertyOfType` fallback under `strictBindCallApply`).
#
# EXPECT A CONTROL FOR THE SYNTHESIS AND A GATE FOR THE MEMBER AUGMENTATION.  tsc's own
# sources set `"strictBindCallApply": false` explicitly in every profile's tsconfig, so
# every `.call`/`.apply` site there takes the LOOSE `Function.call` half (measured by the
# reach census: 28-31 `call` and 1-4 `apply` misses per profile, ALL refused `non-strict`,
# 0 resolved) — the same `any` the parent binary answered.  What the profiles DO exercise
# is the `Function`-member half (`length`, `name`, `toString`…: 3-4 `length` reads per
# profile) and the option itself: BEFORE this round the `strictBindCallApply: false`
# line was ignored and the synthesis produced one FALSE POSITIVE per profile
# (`utilities.ts:11201 stringReplace.call(s, "*", replacement)` against the last
# `String.replace` overload), which is exactly the row this grid must NOT show.
# The real gate for the synthesis is `marked` (strict, 14 resolved sites, 18 -> 18) and
# the pins.
#
# BEFORE is HEAD 6aa810489, AFTER is the (CHK.134)(1) tree.  Two snapshotted class dirs,
# because the change is not switchable at run time.  REFUSES when the arms'
# Checker.class are byte-identical (an accidental self-comparison prints
# added=0 removed=0 on all eight and reads exactly like a clean bill of health —
# CLAUDE.md 853/946), when a capture is empty or TRUNCATED (`and N more error(s)`
# — round 811), and below 8 profiles (the pre-895 `tsc-project-*` glob matched
# exactly ONE).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/chk134/grid; mkdir -p "$OUT"
BEFORE=build/bench/chk134/classes-before
AFTER=build/bench/chk134/classes-after
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
