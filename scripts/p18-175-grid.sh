#!/usr/bin/env bash
# (P18.175) the 8-profile BEFORE/AFTER binary grid for (LIB.5) G3 — a generic ARROW or
# FUNCTION EXPRESSION nested in a STATIC member must not have its OWN type parameters
# attributed to the enclosing class (a false TS2302).
#
# THIS GRID IS A **CONTROL, AND THE COUNT IS ZERO** — `grep -rhcE 'static [A-Za-z_]+[^=]*= *<'`
# over each profile's `.ts` sources finds **0 sites on all eight**.  tsc's own code does not
# put a generic arrow in a static initializer, so this grid cannot move in either direction
# and a green run here is evidence that nothing ELSE moved, which is what a control is for.
#
# THE REAL GATE IS THE CORPUS, and it is an OVER-SUPPRESSION gate rather than a coverage one:
# 14 reference `.errors.txt` baselines carry TS2302 and **7 are live in the generated tree**
# (classTypeParametersInStatics, genericClassWithStaticsUsingTypeArguments,
# staticMethodReferencingTypeArgument1, staticMethodsReferencingClassTypeParameters,
# typeParametersInStaticAccessors / …Methods / …Properties).  Measured: **none of the 7
# contains a generic arrow or generic function expression**, so a CORRECT fix cannot move
# them — they exist to catch a fix that over-suppresses and stops reporting a genuine
# static-references-class-TP row.
#
# BEFORE is the round's snapshotted pre-change class dir, AFTER is the built tree.
# REFUSES when the arms' Checker.class are byte-identical (an accidental self-comparison
# prints added=0 removed=0 on all eight and reads exactly like a clean bill of health —
# CLAUDE.md rounds 853/946), when a capture is empty or TRUNCATED (`and N more error(s)` —
# round 811), and below 8 profiles (the pre-895 `tsc-project-*` glob matched exactly ONE).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18175/grid; mkdir -p "$OUT"
BEFORE=build/bench/p18175/classes-before
AFTER=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
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
status=0
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  for arm in before after; do
    dir="$BEFORE"; [[ "$arm" == after ]] && dir="$AFTER"
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
echo "grid status=$status"
exit $status
