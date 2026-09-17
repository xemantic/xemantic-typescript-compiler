#!/usr/bin/env bash
# (P18.130) the JavaScript property-access family — the 8-profile BEFORE/AFTER grid, BOTH modes.
#
# THE GRID IS A CONTROL THIS ROUND, AND THE REASON IS WORTH STATING: every file of all 8 profiles is
# TypeScript, and both landed mechanisms short-circuit on `isJsLikeFileName(fileName)`, so the grid
# CANNOT move. What it controls is exactly that — that admitting a family into `.js` files leaves
# every `.ts` row and every emitted byte alone, which is the direction the two funnel guards could
# have failed in: they read the FILE NAME rather than `spineIsJsLike`, whose stale value after the
# spine would otherwise suppress `.ts` rows whenever the program's last file is JavaScript. The REAL
# gate is the corpus errors screen, 153 of whose 2,898 active error subtests carry a `.js` file.
#
# REFUSES when the two arms' Checker, MemberResolver, Relater and TypeInstantiator classes are ALL
# byte-identical (CLAUDE.md rounds 853/946), when a capture is empty or TRUNCATED, and below 8 profiles.
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18-130-orch; mkdir -p "$OUT/grid"
BEFORE="$OUT/classes-before"
AFTER="$OUT/classes-after"
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
for d in "$BEFORE" "$AFTER"; do
  [[ -f "$d/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: $d holds no MainKt"; exit 1; }
done
# (P18.128) touches the Checker; refuse only when NONE of the four moved.
same=1
for cls in Checker MemberResolver Relater TypeInstantiator; do
  a="$(sha256sum "$BEFORE/com/xemantic/typescript/compiler/$cls.class" | cut -d' ' -f1)"
  b="$(sha256sum "$AFTER/com/xemantic/typescript/compiler/$cls.class" | cut -d' ' -f1)"
  echo "before $cls.class $a"
  echo "after  $cls.class $b"
  [[ "$a" != "$b" ]] && same=0
done
[[ "$same" == 1 ]] && { echo "REFUSED: the two arms' Checker/MemberResolver/Relater/TypeInstantiator classes are all byte-identical"; exit 1; }
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
