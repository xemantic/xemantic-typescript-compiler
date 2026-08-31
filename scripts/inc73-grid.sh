#!/usr/bin/env bash
# (INC.73) — the 8-profile BEFORE/AFTER binary grid for the module TYPE-NAME index
# built on first ask.
#
# THIS GRID IS THE VALUE GATE, and unusually it is the ONLY one: an ablation that
# never builds the index reddens **ZERO** of the ~13k corpus baselines, so the
# corpus is a control here and not coverage. That is consistent with where the two
# sets came from — round 471's evidence was the SERVICES self-compile (an
# un-memoized `isLibPhantomMemberOfModuleInterface` doubled it, 39 s -> 77 s) and
# round 513's is tsc's own private-codefix `Info` pattern, a name declared as an
# interface in two module files. Both live in these profiles and in no fixture.
#
# Two BINARIES, not two flags — snapshotted class directories — so the script
# REFUSES when the two arms' Checker.class are byte-identical (rounds 853 / 946).
#
# Profiles are enumerated by the presence of a tsconfig.json and the run is
# REFUSED below 8 (the pre-round-895 glob matched exactly ONE directory).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/inc73/grid
mkdir -p "$OUT"

BEFORE=${BEFORE_DIR:-build/bench/inc73/classes-before}
AFTER=${AFTER_DIR:-build/bench/inc73/classes-after}
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
