#!/usr/bin/env bash
# (INC.71) — the 8-profile BEFORE/AFTER binary grid for the INV.3(b)(ii)
# visibility sets built on first ask.
#
# THIS GRID IS COVERAGE, not a control. `moduleOnlyGlobalNames` is what stands
# between a MODULE file's local and every other file, and an empty set answers
# every such name out of the merged `globals` instead — silently, since the name
# resolves rather than erroring. The eight profiles are real multi-file programs
# whose files import from one another through barrels, which is exactly the
# traffic that set governs; ablating it reddens 492 corpus tests, so a build that
# lost it would not be subtle here either.
#
# Two BINARIES, not two flags — snapshotted class directories — so the script
# REFUSES when the two arms' Checker.class are byte-identical: an accidental
# self-comparison prints `added=0 removed=0` on all eight and reads exactly like a
# clean bill of health (CLAUDE.md rounds 853 / 946).
#
# Profiles are enumerated by the presence of a tsconfig.json and the run is
# REFUSED below 8 — the universal `build/bench/tsc-project-*` glob every grid
# harness here used before round 895 matched exactly ONE directory.
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/inc71/grid
mkdir -p "$OUT"

BEFORE=build/bench/inc71/classes-before
AFTER=build/bench/inc71/classes-after
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }

for d in "$BEFORE" "$AFTER"; do
  [[ -f "$d/com/xemantic/typescript/compiler/MainKt.class" ]] || {
    echo "REFUSED: $d holds no MainKt"; exit 1; }
done
same=1
for c in Checker; do
  a="$(sha256sum "$BEFORE/com/xemantic/typescript/compiler/$c.class" | cut -d' ' -f1)"
  b="$(sha256sum "$AFTER/com/xemantic/typescript/compiler/$c.class" | cut -d' ' -f1)"
  [[ "$a" != "$b" ]] && same=0
done
[[ $same -eq 1 ]] && { echo "REFUSED: the two arms' Checker are byte-identical"; exit 1; }

shopt -s nullglob
profiles=()
for d in build/bench/tsc-*; do
  [[ -d "$d" && -f "$d/tsconfig.json" ]] && profiles+=("$d")
done
if [[ "${#profiles[@]}" -lt 8 ]]; then
  echo "REFUSED: only ${#profiles[@]} profile(s) — materialize with" \
       "scripts/bench-compile-tsc.sh --project all --no-emit --no-log"
  exit 1
fi
echo "profiles: ${#profiles[@]}"

status=0
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  for arm in before after; do
    dir="$BEFORE"; [[ "$arm" == after ]] && dir="$AFTER"
    java -Xmx4g -cp "$dir:$DEPS" com.xemantic.typescript.compiler.MainKt \
      --noEmit --listAll "$proj" > "$OUT/$name.$arm.raw" 2>&1
    if [[ ! -s "$OUT/$name.$arm.raw" ]]; then
      echo "$name/$arm: REFUSED — empty capture"; status=1; continue
    fi
    if grep -q "and [0-9]* more error" "$OUT/$name.$arm.raw"; then
      echo "$name/$arm: REFUSED — truncated capture"; status=1; continue
    fi
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
