#!/usr/bin/env bash
# (P18.136) — the 8-profile BEFORE/AFTER grid for (CHK.124) step 1: real EXPANDO MEMBERS
# attached to a `FunctionDeclaration` host's type, so `typeof Foo` is `{ (): void; tag: number; }`
# and every ordinary rule — display, assignability, member lookup — sees them.
#
# THIS IS A **CONTROL**, AND THE COUNT WAS TAKEN BEFORE THE ROUND RATHER THAN ASSUMED AFTER IT
# (CLAUDE.md (CHK.124): count whether the changed path FIRES before banking a green grid). The
# eight profiles are 1,249 `.ts` files and carry **0 genuine expando shapes** between them — the
# only 8 grep hits are one function-LOCAL false positive (`map.add = multiMapAdd` in `core.ts`,
# whose top-level `map` is an overload set). So no profile can attach a member and none can move.
# Corroborated independently: `cost_gate.py` reads counters IDENTICAL to the two previous rounds,
# i.e. this change moved nothing here at all.
#
# AND THE COST GATE IS BLIND TO THIS CHANGE, WHICH IS WORTH SAYING RATHER THAN GLOSSING: its
# counters are type-RESOLUTION operations, while the new per-container expando scan is a pure AST
# walk. A green cost gate here means "no resolution moved", never "the scan is free". What bounds
# the scan is its shape — memoized PER CONTAINER, not per name, so one scan answers for every
# function declared in that container ((INC.57)'s quadratic shape avoided by construction).
#
# The `full` arm (inherited from (P18.135)) diffs the WHOLE capture, not just `grep 'error TS'`
# head rows, so a change that alters only chain or display lines is visible. Both arms must read 0.
#
# The standing BEFORE rows are 46 on seven profiles and 94 on `harness` (CLAUDE.md).
#
# REFUSES when the two arms' Checker.class is byte-identical (rounds 853/946).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18-136-orch; mkdir -p "$OUT/grid"
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
