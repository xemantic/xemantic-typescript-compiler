#!/usr/bin/env bash
# (LEGACY.0b) step 16 — the 8-profile BEFORE/AFTER grid, in BOTH modes.
#
# EXPECT CONTROLS, AND COUNT THEM. (P18.101) retires TS2346, adopts tsgo's value-exports
# TS2309 rule, stops counting an export specifier as a duplicate declaration, extends the
# relation-head suppression to the readonly/complexity leaves, and touches the TS2589/TS2615
# and TS2769-chain shapes. tsc's own 78 sources carry none of these shapes (46 rows per
# profile, all `Cannot find name`), so `added=0 removed=0` is the expected answer and is a
# statement about the corpora, not about the change — the corpus screen and the suite are the
# gates. The EMIT half is a control by construction (nothing here touches the transformer).
#
# REFUSES when the two arms' Checker.class AND RelationHeadSuppression.class are byte-identical
# (an accidental self-comparison prints a clean bill of health — CLAUDE.md rounds 853/946), when
# a capture is empty or TRUNCATED, and below 8 profiles.
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18-101-orch; mkdir -p "$OUT/grid"
BEFORE="$OUT/classes-before"
AFTER="$OUT/classes-after"
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
for d in "$BEFORE" "$AFTER"; do
  [[ -f "$d/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: $d holds no MainKt"; exit 1; }
done
# (P18.101) touches the Checker and RelationHeadSuppression; refuse only when NEITHER moved.
same=1
for cls in Checker RelationHeadSuppression; do
  a="$(sha256sum "$BEFORE/com/xemantic/typescript/compiler/$cls.class" | cut -d' ' -f1)"
  b="$(sha256sum "$AFTER/com/xemantic/typescript/compiler/$cls.class" | cut -d' ' -f1)"
  echo "before $cls.class $a"
  echo "after  $cls.class $b"
  [[ "$a" != "$b" ]] && same=0
done
[[ "$same" == 1 ]] && { echo "REFUSED: the two arms' Checker and RelationHeadSuppression classes are both byte-identical"; exit 1; }
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
      --noEmit --listAll "$proj" > "$OUT/grid/$name.$arm.raw" 2>&1
    [[ -s "$OUT/grid/$name.$arm.raw" ]] || { echo "$name/$arm: REFUSED — empty capture"; status=1; continue; }
    grep -q "and [0-9]* more error" "$OUT/grid/$name.$arm.raw" && { echo "$name/$arm: REFUSED — truncated"; status=1; continue; }
    grep 'error TS' "$OUT/grid/$name.$arm.raw" | sed "s|$proj/||" | sort > "$OUT/grid/$name.$arm.txt"
  done
  added=$(comm -13 "$OUT/grid/$name.before.txt" "$OUT/grid/$name.after.txt" | wc -l)
  removed=$(comm -23 "$OUT/grid/$name.before.txt" "$OUT/grid/$name.after.txt" | wc -l)
  n=$(wc -l < "$OUT/grid/$name.after.txt")
  echo "$name: rows=$n added=$added removed=$removed"
  [[ "$added" -ne 0 || "$removed" -ne 0 ]] && status=1
done
# ── EMIT mode over the compiler profile: the one instrument here that runs the transformer.
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
diff -rq "$OUT/emit-before" "$OUT/emit-after" | head -20
[[ "$d" -ne 0 ]] && status=1
echo "grid status=$status"
exit $status
