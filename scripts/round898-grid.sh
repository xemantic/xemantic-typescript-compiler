#!/usr/bin/env bash
# (WARM.25) round 898 — the 8-profile output grid, CROSS-ROUND.
#
# Round 897 lands NO behaviour change at all — only an instrument that is inert
# when off — so there is nothing to switch and the "before" side is round 896's
# committed captures in build/bench/round897-grid/*.after.txt, produced by the
# parent commit with the IDENTICAL recipe.
#
# It is run anyway, and it is not a formality: the round adds a hook to
# `Scanner.scanIdentifier`, i.e. to a line every identifier of every file of
# every profile crosses. A gate whose expected answer is "nothing moved" is a
# CONTROL (round 876), and the thing it controls here is that an inert hook is
# really inert.
#
#   java ... --noEmit --listAll <profile>   |  grep -a 'error TS'  |  sort
#
# Round 841's law: a capture md5 is a property of (OUTPUT x RECIPE), so only a
# same-recipe comparison means anything — hence the recipe is duplicated here
# verbatim rather than reused through a wrapper that might have drifted.
#
# The differ REFUSES a truncated capture (round 811: `and N more error(s)`) and
# an empty one (round 804), and REFUSES to run on fewer than 8 profiles — every
# grid harness here globbed `tsc-project-*` until round 895 and was silently a
# ONE-profile grid.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round898-grid
BEFORE=build/bench/round897-grid
mkdir -p "$OUT"

MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
CP="$MAIN:$DEPS"
# round 853: a gate reading a class DIRECTORY needs a positive control that the
# code under test is really in it.
[[ -f "$MAIN/com/xemantic/typescript/compiler/SpineDispatch.class" ]] || {
  echo "REFUSED: class dir is missing SpineDispatch"; exit 1; }

shopt -s nullglob
profiles=()
for d in build/bench/tsc-*; do
  [[ -d "$d" && -f "$d/tsconfig.json" ]] && profiles+=("$d")
done
if [[ "${#profiles[@]}" -lt 8 ]]; then
  echo "REFUSED: only ${#profiles[@]} profile(s) present"; exit 1
fi
echo "profiles: ${#profiles[@]}"

status=0
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
    --noEmit --listAll "$proj" > "$OUT/$name.after.raw" 2>&1
  grep -a 'error TS' "$OUT/$name.after.raw" | sort > "$OUT/$name.after.txt"
  if grep -qa 'more error(s)' "$OUT/$name.after.raw"; then
    echo "REFUSED $name: capture truncated"; status=1; continue
  fi
  if [[ ! -s "$OUT/$name.after.txt" ]]; then
    echo "REFUSED $name: empty capture"; status=1; continue
  fi
  if [[ ! -s "$BEFORE/$name.after.txt" ]]; then
    echo "REFUSED $name: no round-897 before-capture"; status=1; continue
  fi
  added=$(comm -13 "$BEFORE/$name.after.txt" "$OUT/$name.after.txt" | wc -l)
  removed=$(comm -23 "$BEFORE/$name.after.txt" "$OUT/$name.after.txt" | wc -l)
  n=$(wc -l < "$OUT/$name.after.txt")
  echo "$name: $n diagnostics  added=$added removed=$removed"
  [[ "$added" -eq 0 && "$removed" -eq 0 ]] || status=1
done
echo "grid exit=$status"
exit $status
