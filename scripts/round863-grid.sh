#!/usr/bin/env bash
# round-862 scratch gate: the 8 dashboard profiles captured from TWO class
# directories (a rebuilt before-arm and the after-arm), diffed in BOTH
# directions, plus an EMIT-mode output comparison on the compiler profile.
#
# Why an emit arm at all: (WARM.8)(c) touches `cpcRequireOnlyOrphans`, whose
# result reaches nothing but the list of emitted JS files. A `--noEmit
# --listAll` capture is therefore STRUCTURALLY unable to see the change (round
# 861 § 12.5), so it is run as a control and the emit tree is the real gate.
#
# Round 858: the classpath comes from `scripts/lib/dep-classpath.sh`, never from
# a cached `cp.txt`. Round 811: a capture containing "... and N more error(s)"
# is REFUSED, and so is an empty one.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="$1"; BEFORE="$2"; AFTER="$3"
mkdir -p "$OUT"
DEPS="$(scripts/lib/dep-classpath.sh --print)" || exit 1

declare -A DIRS=(
  [compiler]="$ROOT/build/bench/tsc-project-637d5746"
  [tsc-cli]="$ROOT/build/bench/tsc-tsc-637d5746"
  [jsTyping]="$ROOT/build/bench/tsc-jsTyping-637d5746"
  [deprecatedCompat]="$ROOT/build/bench/tsc-deprecatedCompat-637d5746"
  [typingsInstallerCore]="$ROOT/build/bench/tsc-typingsInstallerCore-637d5746"
  [services]="$ROOT/build/bench/tsc-services-637d5746"
  [server]="$ROOT/build/bench/tsc-server-637d5746"
  [harness]="$ROOT/build/bench/tsc-harness-637d5746"
)
PROFILES="compiler tsc-cli jsTyping deprecatedCompat typingsInstallerCore services server harness"

# Positive control (round 853): the two arms must be DIFFERENT binaries.
b=$(find "$BEFORE" -name '*.class' | wc -l)
a=$(find "$AFTER" -name '*.class' | wc -l)
echo "arm class counts: before=$b after=$a"
[[ -f "$AFTER/com/xemantic/typescript/compiler/JsxRuntimePragmaScanKt.class" ]] || {
  echo "REFUSED: the after arm does not contain the class this round added" >&2; exit 1; }
[[ -f "$BEFORE/com/xemantic/typescript/compiler/JsxRuntimePragmaScanKt.class" ]] && {
  echo "REFUSED: the before arm contains it — the arms are the same binary" >&2; exit 1; }

FAIL=0
for ARM in before after; do
  CP_DIR=$([[ $ARM == before ]] && echo "$BEFORE" || echo "$AFTER")
  for P in $PROFILES; do
    java -Xmx4g -cp "$CP_DIR:$DEPS" com.xemantic.typescript.compiler.MainKt \
        --noEmit --listAll "${DIRS[$P]}" > "$OUT/$ARM-$P.raw" 2>&1
    grep -a 'error TS' "$OUT/$ARM-$P.raw" | sort > "$OUT/$ARM-$P.txt"
    n=$(wc -l < "$OUT/$ARM-$P.txt")
    trunc=$(grep -ac 'more error(s)' "$OUT/$ARM-$P.raw")
    echo "$ARM $P count=$n trunc=$trunc"
    [[ "$trunc" != "0" ]] && { echo "  REFUSED: truncated" >&2; FAIL=1; }
    [[ "$n" == "0" ]] && { echo "  REFUSED: empty" >&2; FAIL=1; }
  done
done

echo "== diagnostics, both directions =="
for P in $PROFILES; do
  added=$(comm -13 "$OUT/before-$P.txt" "$OUT/after-$P.txt" | wc -l)
  removed=$(comm -23 "$OUT/before-$P.txt" "$OUT/after-$P.txt" | wc -l)
  echo "$P added=$added removed=$removed"
  [[ "$added" != "0" || "$removed" != "0" ]] && FAIL=1
done

echo "== EMIT mode, compiler profile =="
for ARM in before after; do
  CP_DIR=$([[ $ARM == before ]] && echo "$BEFORE" || echo "$AFTER")
  rm -rf "${DIRS[compiler]}/dist"
  java -Xmx4g -cp "$CP_DIR:$DEPS" com.xemantic.typescript.compiler.MainKt \
      "${DIRS[compiler]}" > "$OUT/emit-$ARM.log" 2>&1
  rm -rf "$OUT/dist-$ARM"
  cp -a "${DIRS[compiler]}/dist" "$OUT/dist-$ARM" 2>/dev/null || echo "  no dist for $ARM"
  echo "$ARM emitted files: $(find "$OUT/dist-$ARM" -type f 2>/dev/null | wc -l)"
done
rm -rf "${DIRS[compiler]}/dist"
if diff -r "$OUT/dist-before" "$OUT/dist-after" > "$OUT/emit.diff" 2>&1; then
  echo "emit tree: IDENTICAL"
else
  echo "emit tree: DIFFERS — see $OUT/emit.diff"; head -20 "$OUT/emit.diff"; FAIL=1
fi
exit "$FAIL"
