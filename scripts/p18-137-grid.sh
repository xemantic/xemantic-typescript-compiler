#!/usr/bin/env bash
# (P18.137) — the 8-profile BEFORE/AFTER grid for (CHK.124) step 2: ROUTE (B), a missing
# member on an IDENTIFIER receiver whose type carries CALL SIGNATURES, plus the expando
# member model extended to `const`-bound function-expression hosts.
#
# **THIS ROUND THE GRID IS A GATE, NOT A CONTROL — AND THAT IS THE DIFFERENCE FROM
# (P18.134)/(P18.136).** Those two rounds changed paths the profiles cannot express: a
# namespace-qualified `typeof g` receiver and an expando WRITE, of which tsc's own 1,249
# `.ts` files contain none (the (P18.136) header records the count). This round ADDS A
# DIAGNOSTIC CLASS to `<identifier>.<member>` where the identifier is function-typed, and
# the profiles are ~1.2M lines of correct TypeScript that use function-typed `const`s,
# parameters and interface call signatures pervasively. So the changed path FIRES here in
# quantity, and the question the grid answers is the one that matters: does it fire on
# CORRECT code?
#
# Counted before the round rather than asserted after it, on the compiler profile alone:
#   $ grep -cE ': *\(.*\) *=>' build/bench/tsc-project-*/src/compiler/*.ts | awk -F: '{s+=$2} END {print s}'
# The reach receipt that belongs beside a green grid is therefore NOT "the shape is
# absent" but "the shape is everywhere and none of it is wrong" — which is exactly what a
# false-positive gate should say. A row that DOES appear must be attributed to this rule
# and justified against `tools/tsgo-7.0.2/lib/tsc --noEmit -p <dir>` before it is banked.
#
# The `full` arm (inherited from (P18.135)) diffs the WHOLE capture, not just
# `grep 'error TS'` head rows, so a change that alters only a chain or display line is
# visible too. Both arms must read 0.
#
# The standing BEFORE rows are 46 on seven profiles and 94 on `harness` (CLAUDE.md).
#
# REFUSES when the two arms' Checker.class is byte-identical (rounds 853/946).
#
# Usage: scripts/p18-137-grid.sh [<after-classes-dir>]   (default build/bench/p18-137-orch/classes-after)
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18-137-orch; mkdir -p "$OUT/grid"
BEFORE="$OUT/classes-before"
AFTER="${1:-$OUT/classes-after}"
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
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  for arm in before after; do
    dir="$BEFORE"; [[ "$arm" == after ]] && dir="$AFTER"
    # a BEFORE capture is a property of the frozen staged arm — reuse it when present.
    if [[ "$arm" == before && -s "$OUT/grid/$name.before.txt" && -s "$OUT/grid/$name.before.full" ]]; then continue; fi
    java -Xmx4g -cp "$dir:$DEPS" com.xemantic.typescript.compiler.MainKt \
      --noEmit --listAll "$proj" > "$OUT/grid/$name.$arm.raw" 2>&1
    [[ -s "$OUT/grid/$name.$arm.raw" ]] || { echo "$name/$arm: REFUSED — empty capture"; status=1; continue; }
    grep -q "and [0-9]* more error" "$OUT/grid/$name.$arm.raw" && { echo "$name/$arm: REFUSED — truncated"; status=1; continue; }
    grep 'error TS' "$OUT/grid/$name.$arm.raw" | sed "s|$proj/||" | sort > "$OUT/grid/$name.$arm.txt"
    sed -e "s|$PWD/||g" -e "s|$proj/||g" -e '/^time:/d' -e '/^config:/d' \
        "$OUT/grid/$name.$arm.raw" > "$OUT/grid/$name.$arm.full"
  done
  added=$(comm -13 "$OUT/grid/$name.before.txt" "$OUT/grid/$name.after.txt" | wc -l)
  removed=$(comm -23 "$OUT/grid/$name.before.txt" "$OUT/grid/$name.after.txt" | wc -l)
  n=$(wc -l < "$OUT/grid/$name.after.txt")
  fulldiff=$(diff -u "$OUT/grid/$name.before.full" "$OUT/grid/$name.after.full" | grep -c '^[+-][^+-]' || true)
  echo "$name: rows=$n added=$added removed=$removed fullDiffLines=$fulldiff"
  if [[ "$added" -ne 0 || "$removed" -ne 0 || "$fulldiff" -ne 0 ]]; then
    status=1
    echo "  --- added ---";   comm -13 "$OUT/grid/$name.before.txt" "$OUT/grid/$name.after.txt" | head -40
    echo "  --- removed ---"; comm -23 "$OUT/grid/$name.before.txt" "$OUT/grid/$name.after.txt" | head -40
  fi
done
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
