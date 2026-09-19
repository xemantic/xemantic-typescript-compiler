#!/usr/bin/env bash
# (P18.134) — the 8-profile BEFORE/AFTER grid for the route-(A) relaxation of
# [Checker.cmamAllMissingTrustedMember]: a SYMBOL-CARRYING function type whose resolved
# member table is empty becomes trustable, so `A.foo.bar` (a namespace-qualified function
# receiver) and `o.m.bar` (an object member typed `typeof g`) report TS2339 as tsgo does.
#
# WHAT THIS GRID IS, STATED PRECISELY (CLAUDE.md (CHK.124): count whether the changed path
# FIRES before banking a green grid). It is a **CONTROL FOR THE FALSE-POSITIVE DIRECTION**,
# and a strong one — which is exactly the direction this change risks. The change makes an
# absent member on a function-typed receiver REPORTABLE where it was silently trusted away,
# so its failure mode is a false positive on correct code, and the eight profiles are ~1.2M
# lines of correct TypeScript using function-typed values pervasively. 8 x 0/0 over that is
# real evidence. It is NOT evidence about the TRUE-positive direction, and cannot be: correct
# code contains no missing members to find, so there is nothing here for the change to catch.
#
# REACH IS EVIDENCED SEPARATELY AND FOR FREE, by `cost_gate.py` on the compiler profile:
# typeOfExpr.calls +0.07%, typeOfExpr.distinct +0.15%, narrow.walks +0.08%, globals.lookups
# +0.04%. Those are small but NON-ZERO, and a path that never executes reads +0.00% across the
# board (as the option-path rounds (P18.133)/(P18.107) did). The deltas are the new
# `resolveStructuredTypeMembers` calls the syntactic pre-gate admits, i.e. the path runs here.
#
# A non-zero `added` would be a finding to attribute against tsgo, never noise to wave through:
# every new row must be one `tools/tsgo-7.0.2/lib/tsc --noEmit -p <profile>` also reports.
#
# The standing BEFORE rows are 46 on seven profiles and 94 on `harness` (CLAUDE.md).
#
# REFUSES when the two arms' Checker.class is byte-identical (CLAUDE.md rounds 853/946): the
# edit is inside Checker.kt, so an identical class means the build did not land and a green
# grid would be a frozen instrument rather than a measurement. Note the mirror trap — a STABLE
# md5 is not proof an edit landed, and an inserted COMMENT alone moves it, so this refusal is
# necessary and not sufficient; the round's positive control is the scratch-fixture row.
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18-134-orch; mkdir -p "$OUT/grid"
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
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  for arm in before after; do
    dir="$BEFORE"; [[ "$arm" == after ]] && dir="$AFTER"
    # the BEFORE captures may already exist (pre-warmed by the orchestrator); recapture
    # anyway so both arms come from ONE recipe — round 841: a capture md5 is a property of
    # (output x recipe), so mixing two recipes across arms manufactures a diff.
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
  if [[ "$added" -ne 0 || "$removed" -ne 0 ]]; then
    status=1
    echo "  --- added ---";   comm -13 "$OUT/grid/$name.before.txt" "$OUT/grid/$name.after.txt" | head -20
    echo "  --- removed ---"; comm -23 "$OUT/grid/$name.before.txt" "$OUT/grid/$name.after.txt" | head -20
  fi
done
# ── EMIT mode over the compiler profile: the one instrument here that runs the transformer
# (round 738's `skipEmitOutputs` gate makes every --noEmit instrument blind to emitted bytes).
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
