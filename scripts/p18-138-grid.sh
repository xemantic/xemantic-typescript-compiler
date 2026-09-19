#!/usr/bin/env bash
# (P18.138) — the 8-profile BEFORE/AFTER grid for (CHK.124) step 3: the JavaScript
# OBJECT-LITERAL expando host (`var x = {}`) plus `Object.defineProperty` membership.
#
# THIS IS A **CONTROL**, AND UNLIKE (P18.137) THE REASON IS STRUCTURAL RATHER THAN MEASURED:
# every new rule this round adds is gated on a JavaScript file, and all eight profiles are tsc's
# own `.ts` sources. No profile can reach the changed path at all, so a green grid says only that
# a pure-TypeScript project is undisturbed — which is worth confirming (the host predicate ascends
# a parent chain, pre-gated by `programHasJsFile`) and is not evidence about the rule.
#
# The GATE for this family is the corpus screen's ERRORS and EMIT channels plus the round's pins
# and its tsgo probe matrix. Note the screen is itself largely a control here: re-derived, only 1
# active case has the new host shape (annotated, so refused) and 2 carry `defineProperty` (neither
# an object-literal host), which is (CHK.124)'s law applied before the round rather than after.
#
# The `full` arm (from (P18.135)) diffs the WHOLE capture, not just `grep 'error TS'` head rows,
# so a change that alters only chain or display lines is visible. Both arms must read 0.
#
# The standing BEFORE rows are 46 on seven profiles and 94 on `harness` (CLAUDE.md).
#
# REFUSES when the two arms' Checker.class is byte-identical (rounds 853/946).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18-138-orch; mkdir -p "$OUT/grid"
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
