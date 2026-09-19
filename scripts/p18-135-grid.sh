#!/usr/bin/env bash
# (P18.135) — the 8-profile BEFORE/AFTER grid for the nested-generic-argument chain HEADER
# rule (`Checker.getPropertyElaborationChain`'s same-target generic branch + the new
# `RelationHeadSuppression.leafNamesTypePair` predicate).
#
# THIS GRID CARRIES A SECOND COMPARISON THE USUAL RECIPE DOES NOT, AND THE REASON IS THE
# ROUND'S OWN SUBJECT. Every grid script in this repo compares `grep 'error TS'` row sets,
# i.e. the HEAD line of each diagnostic — so it is STRUCTURALLY BLIND to a change that only
# alters CHAIN lines, which is exactly what this round changes. A green head-row grid would
# therefore have said nothing at all. The `full` arm below diffs the WHOLE capture
# (project path stripped, the volatile `time:`/`config:` lines dropped), which does see chain
# lines, and both arms must read 0.
#
# EVEN SO, THIS GRID IS A **CONTROL** AND THE COUNT IS WHY — (PARITY.1), re-verified here by
# inspection: all 46 rows of seven profiles and all 94 of `harness` are `Cannot find name ...`
# (TS2591/TS2304/TS2584), which carry NO chain lines whatever. So no profile can express this
# rule's output and none can move. The gate is the CORPUS (one row closes) plus the round's own
# pins; and per the implementer's own ablation the corpus cannot discriminate this change in
# EITHER direction, which makes the pins the only real instrument. Recording that honestly is
# the point of this header.
#
# REFUSES when the two arms' Checker.class is byte-identical (rounds 853/946).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18-135-orch; mkdir -p "$OUT/grid"
BEFORE="$OUT/classes-before"
AFTER="$OUT/classes-after"
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
for d in "$BEFORE" "$AFTER"; do
  [[ -f "$d/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: $d holds no MainKt"; exit 1; }
done
a="$(sha256sum "$BEFORE/com/xemantic/typescript/compiler/Checker.class" | cut -d' ' -f1)"
b="$(sha256sum "$AFTER/com/xemantic/typescript/compiler/Checker.class"  | cut -d' ' -f1)"
echo "before Checker.class $a"
echo "after  Checker.class $b"
[[ "$a" == "$b" ]] && { echo "REFUSED: the two arms' Checker.class is byte-identical"; exit 1; }
shopt -s nullglob
profiles=()
for d in build/bench/tsc-*; do [[ -d "$d" && -f "$d/tsconfig.json" ]] && profiles+=("$d"); done
[[ "${#profiles[@]}" -lt 8 ]] && { echo "REFUSED: only ${#profiles[@]} profile(s)"; exit 1; }
echo "profiles: ${#profiles[@]}"
status=0
chainlines=0
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  for arm in before after; do
    dir="$BEFORE"; [[ "$arm" == after ]] && dir="$AFTER"
    java -Xmx4g -cp "$dir:$DEPS" com.xemantic.typescript.compiler.MainKt \
      --noEmit --listAll "$proj" > "$OUT/grid/$name.$arm.raw" 2>&1
    [[ -s "$OUT/grid/$name.$arm.raw" ]] || { echo "$name/$arm: REFUSED — empty capture"; status=1; continue; }
    grep -q "and [0-9]* more error" "$OUT/grid/$name.$arm.raw" && { echo "$name/$arm: REFUSED — truncated"; status=1; continue; }
    grep 'error TS' "$OUT/grid/$name.$arm.raw" | sed "s|$proj/||" | sort > "$OUT/grid/$name.$arm.txt"
    # the FULL capture, normalised: this is the arm that can see a chain line.
    sed -e "s|$PWD/||g" -e "s|$proj/||g" -e '/^time:/d' -e '/^config:/d' \
        "$OUT/grid/$name.$arm.raw" > "$OUT/grid/$name.$arm.full"
  done
  added=$(comm -13 "$OUT/grid/$name.before.txt" "$OUT/grid/$name.after.txt" | wc -l)
  removed=$(comm -23 "$OUT/grid/$name.before.txt" "$OUT/grid/$name.after.txt" | wc -l)
  n=$(wc -l < "$OUT/grid/$name.after.txt")
  fulldiff=$(diff -u "$OUT/grid/$name.before.full" "$OUT/grid/$name.after.full" | grep -c '^[+-][^+-]' || true)
  # how many chain lines does this profile carry at all? a `0` here is the (PARITY.1) receipt.
  cl=$(grep -cE '^ +[|!]|^ {4,}[A-Z]' "$OUT/grid/$name.after.raw" || true)
  chainlines=$((chainlines + cl))
  echo "$name: rows=$n added=$added removed=$removed fullDiffLines=$fulldiff chainLinesInProfile=$cl"
  if [[ "$added" -ne 0 || "$removed" -ne 0 || "$fulldiff" -ne 0 ]]; then
    status=1
    echo "  --- added ---";   comm -13 "$OUT/grid/$name.before.txt" "$OUT/grid/$name.after.txt" | head -20
    echo "  --- removed ---"; comm -23 "$OUT/grid/$name.before.txt" "$OUT/grid/$name.after.txt" | head -20
    echo "  --- full ---";    diff -u "$OUT/grid/$name.before.full" "$OUT/grid/$name.after.full" | head -30
  fi
done
echo "TOTAL chain lines across all 8 profiles: $chainlines  (0 => the grid is a CONTROL for this family, measured)"
proj="$(ls -d build/bench/tsc-project-* | head -1)"
for arm in before after; do
  dir="$BEFORE"; [[ "$arm" == after ]] && dir="$AFTER"
  rm -rf "$OUT/emit-$arm"
  java -Xmx4g -cp "$dir:$DEPS" com.xemantic.typescript.compiler.MainKt \
    --outDir "$OUT/emit-$arm" "$proj" > "$OUT/emit-$arm.log" 2>&1
done
nb=$(find "$OUT/emit-before" -name '*.js' | wc -l)
na=$(find "$OUT/emit-after" -name '*.js' | wc -l)
echo "emit: before=$nb file(s) after=$na file(s)"
[[ "$nb" -lt 70 || "$na" -lt 70 ]] && { echo "REFUSED: an emit arm produced almost nothing"; status=1; }
d=$(diff -rq "$OUT/emit-before" "$OUT/emit-after" | wc -l)
echo "emit: $d differing file(s)"
[[ "$d" -ne 0 ]] && status=1
echo "grid status=$status"
exit $status
