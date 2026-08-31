#!/usr/bin/env bash
# (INC.69) ABBA-rotated floor A/B between two CORE class directories.
#
# Rotation, not a bigger sample: (INC.68) measured a BLOCKED batch of 12 draws per
# arm reporting a reproducible +2.70 ms regression in a region that calls none of
# the changed code, with both signs inverting under rotation. One JVM per arm
# (round 867), and the arms alternate A B B A across processes so a linear drift
# cancels.
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
PROJECT="${1:-build/bench/many-small-2400-dom}"
BEFORE="${2:-build/bench/inc69/classes-before}"
AFTER="${3:-build/bench/inc69/classes-after}"
CYCLES="${4:-2}"
OUT=build/bench/inc69/floor
mkdir -p "$OUT"

for d in "$BEFORE" "$AFTER"; do
  [[ -f "$d/com/xemantic/typescript/compiler/Checker.class" ]] || { echo "REFUSED: $d has no Checker"; exit 2; }
done
a="$(sha256sum "$BEFORE/com/xemantic/typescript/compiler/Checker.class" | cut -d' ' -f1)"
b="$(sha256sum "$AFTER/com/xemantic/typescript/compiler/Checker.class" | cut -d' ' -f1)"
[[ "$a" == "$b" ]] && { echo "REFUSED: the two arms' Checker are byte-identical"; exit 2; }

source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
PROJ="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
PROJ="$PROJ:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
[[ -f "$PROJ/../test/com/xemantic/typescript/compiler/project/FloorAbMainKt.class" ]] ||
  [[ -f "$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test/com/xemantic/typescript/compiler/project/FloorAbMainKt.class" ]] || {
  echo "REFUSED: runner not built"; exit 2; }

run() { # <arm> <dir> <tag> <extra>
  java -Xmx6g -cp "$PROJ:$2:$DEPS" \
    com.xemantic.typescript.compiler.project.FloorAbMainKt "$PROJECT" 4 8 ${4:-} \
    > "$OUT/$1.$3.txt" 2>&1
  grep -a "^FLOOR" "$OUT/$1.$3.txt" | sed "s/^/$1.$3 /"
}

for ((c = 1; c <= CYCLES; c++)); do
  run before "$BEFORE" "c$c-1"
  run after  "$AFTER"  "c$c-1"
  run after  "$AFTER"  "c$c-2"
  run before "$BEFORE" "c$c-2"
done

# One instrumented draw per arm, LAST, for the per-pass table.
run before "$BEFORE" rows rows
run after  "$AFTER"  rows rows

python3 - "$OUT" <<'PY'
import glob,re,sys,statistics
out=sys.argv[1]
for arm in ("before","after"):
    med=[]
    for p in sorted(glob.glob(f"{out}/{arm}.c*.txt")):
        m=re.search(r"median=(\d+)ms", open(p).read())
        if m: med.append(int(m.group(1)))
    if med:
        print(f"{arm}: process medians {med}  median-of-medians {statistics.median(med)}  mean {statistics.mean(med):.1f}")
PY
