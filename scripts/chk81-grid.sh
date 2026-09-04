#!/usr/bin/env bash
# (CHK.81) the 8-profile BEFORE/AFTER binary grid — HEAD (c0819a96) vs the
# (CHK.81) working tree. Two snapshotted class directories (the change is not
# switchable at run time); REFUSES a self-comparison, an empty or truncated
# capture, and fewer than 8 profiles.
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/chk81/grid
mkdir -p "$OUT"
BEFORE=build/bench/chk81/classes-before
AFTER=build/bench/chk81/classes-after
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
for d in "$BEFORE" "$AFTER"; do
  [[ -f "$d/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: $d holds no MainKt"; exit 1; }
done
a="$(sha256sum "$BEFORE/com/xemantic/typescript/compiler/Checker.class" | cut -d' ' -f1)"
b="$(sha256sum "$AFTER/com/xemantic/typescript/compiler/Checker.class" | cut -d' ' -f1)"
[[ "$a" == "$b" ]] && { echo "REFUSED: the two arms' Checker.class are byte-identical"; exit 1; }
javap -p -cp "$AFTER" com.xemantic.typescript.compiler.Checker 2>/dev/null | grep -q ambientRequireAliasTarget || {
  echo "REFUSED: the AFTER arm does not hold the (CHK.81) code"; exit 1; }
javap -p -cp "$BEFORE" com.xemantic.typescript.compiler.Checker 2>/dev/null | grep -q ambientRequireAliasTarget && {
  echo "REFUSED: the BEFORE arm already holds the (CHK.81) code"; exit 1; }
shopt -s nullglob
profiles=()
for d in build/bench/tsc-*; do [[ -d "$d" && -f "$d/tsconfig.json" ]] && profiles+=("$d"); done
if [[ "${#profiles[@]}" -lt 8 ]]; then echo "REFUSED: only ${#profiles[@]} profile(s)"; exit 1; fi
echo "profiles: ${#profiles[@]}"
status=0
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  for arm in before after; do
    dir="$BEFORE"; [[ "$arm" == after ]] && dir="$AFTER"
    java -Xmx4g -cp "$dir:$DEPS" com.xemantic.typescript.compiler.MainKt --noEmit --listAll "$proj" > "$OUT/$name.$arm.raw" 2>&1
    [[ -s "$OUT/$name.$arm.raw" ]] || { echo "$name/$arm: REFUSED — empty capture"; status=1; continue; }
    grep -q "and [0-9]* more error" "$OUT/$name.$arm.raw" && { echo "$name/$arm: REFUSED — truncated"; status=1; continue; }
    grep 'error TS' "$OUT/$name.$arm.raw" | sort > "$OUT/$name.$arm.txt"
  done
  added=$(comm -13 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
  removed=$(comm -23 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
  n=$(wc -l < "$OUT/$name.after.txt")
  echo "$name: rows=$n added=$added removed=$removed"
  [[ "$added" -ne 0 || "$removed" -ne 0 ]] && status=1
done
echo "grid status=$status"
exit $status
