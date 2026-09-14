#!/usr/bin/env bash
# (LEGACY.0b) step 9 — the 8-profile BEFORE/AFTER binary grid for the ORDER family's
# REACH half (13 union/member DISPLAY sites put into tsc's stable order).
#
# EXPECT A CONTROL, AND COUNT IT ((CHK.124)). (PARITY.1) measured that all 46 rows of
# seven profiles — and the bulk of `harness`'s — are `Cannot find name …` (TS2304 /
# TS2584 / TS2591), which name NO TYPE AT ALL, so the grid is structurally blind to a
# rendering change. The per-profile counts of the codes this round's emitters can move
# are therefore the receipt that the changed paths are UNREACHABLE here rather than
# merely quiet. The gates that did the work are the 3,033-subtest active-corpus screen
# (`scripts/corpus-screen.sh`), the full suite, and 7 hand-written pins measured
# against tsgo 7.0.2.
#
# REFUSES below 8 profiles (the pre-895 `tsc-project-*` glob matched exactly ONE), on an
# empty or TRUNCATED capture (`and N more error(s)` — round 811), and when the two arms'
# Checker.class are byte-identical (an accidental self-comparison prints added=0
# removed=0 everywhere and reads exactly like a clean bill of health — CLAUDE.md 853/946).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/legacy0b9/grid; mkdir -p "$OUT"
BEFORE=build/bench/legacy0b9/classes-before
AFTER=build/bench/legacy0b9/classes-after
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
  # The codes the thirteen re-ordered emitters can produce, counted per arm: if these are
  # 0 the profile could not have exercised any of them and the row below is a control.
  reach=0
  for code in TS2322 TS2339 TS2353 TS2551 TS2678 TS2820 TS2862 TS2416; do
    k=$(grep -c "error $code" "$OUT/$name.after.txt")
    reach=$((reach + k))
  done
  echo "$name: rows=$n added=$added removed=$removed | reachable-code rows (TS2322/2339/2353/2551/2678/2820/2862/2416) = $reach"
  [[ "$added" -ne 0 || "$removed" -ne 0 ]] && status=1
done
echo "grid status=$status"
exit $status
