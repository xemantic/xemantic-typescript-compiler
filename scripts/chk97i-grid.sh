#!/usr/bin/env bash
# (CHK.97) D5 — the 8-profile BEFORE/AFTER binary grid for generic INFERENCE through a
# union-combined signature (the intersected-callback inference view).
#
# EXPECT A CONTROL, NOT A GATE.  (PARITY.1) is explicit that the 8-profile grid is
# STRUCTURALLY BLIND to every type-DISPLAY change: all 46 rows of seven profiles
# (and every row of `harness`) are `Cannot find name ...` — TS2304 / TS2584 /
# TS2591 — which name no type at all, so not one of them can carry a union
# rendering.  `added=0 removed=0` here therefore proves that no DIAGNOSTIC moved
# and says nothing whatever about the rendering under change.  The real gates are
# the ~2,910 ACTIVE `.errors.txt` corpus baselines and this round's pins.
#
# BEFORE is HEAD 775523160, AFTER is the (CHK.97) D5 tree.  Two snapshotted class
# dirs, because the change is not switchable at run time.  REFUSES when the arms'
# Checker.class are byte-identical (an accidental self-comparison prints
# added=0 removed=0 on all eight and reads exactly like a clean bill of health —
# CLAUDE.md 853/946), when a capture is empty or TRUNCATED (`and N more error(s)`
# — round 811), and below 8 profiles (the pre-895 `tsc-project-*` glob matched
# exactly ONE).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/chk97i/grid; mkdir -p "$OUT"
BEFORE=build/bench/chk97i/classes-before
AFTER=build/bench/chk97i/classes-after
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
