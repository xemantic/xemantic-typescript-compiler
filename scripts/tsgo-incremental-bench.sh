#!/usr/bin/env bash
# Times tsgo's `--incremental --noEmit` loop on tsc's own compiler sources, for the
# two edit shapes (INC.46)'s gate distinguishes: a BODY-ONLY edit (no exported
# signature moves) and a SIGNATURE edit (one does).
#
# Each run is a FRESH PROCESS, because that is tsgo's model: the incremental state
# lives in `.tsbuildinfo` on disk, not in a session. That asymmetry against our
# in-session API is inherent, and the write-up must say so rather than hide it.
set -euo pipefail
cd "$(dirname "$0")/.."
P=build/bench/tsgo-bench
TSGO=tools/tsgo-7.0.2/lib/tsc
F=$P/src/compiler/binder.ts
[[ -x $TSGO ]] || { echo "REFUSED: no tsgo binary at $TSGO" >&2; exit 2; }
[[ -f $P/tsconfig.json ]] || { echo "REFUSED: no project at $P" >&2; exit 2; }

run() {
  local t0 t1
  t0=$(date +%s%3N)
  "$TSGO" --incremental --noEmit -p "$P" > /tmp/claude-1000/tsgo-run.out 2>&1 || true
  t1=$(date +%s%3N)
  echo "$(( t1 - t0 ))"
}
med() { printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}'; }

cold=(); for i in 1 2 3; do rm -f "$P"/*.tsbuildinfo; cold+=("$(run)"); done
echo "cold (no tsbuildinfo)   : ${cold[*]}  median=$(med "${cold[@]}") ms"

noop=(); for i in 1 2 3; do noop+=("$(run)"); done
echo "no-op (nothing changed) : ${noop[*]}  median=$(med "${noop[@]}") ms"

body=()
for i in 1 2 3; do
  cp /tmp/claude-1000/binder.orig.ts "$F"; run > /dev/null
  cp /tmp/claude-1000/binder.body.ts "$F"; body+=("$(run)")
done
echo "after BODY-ONLY edit    : ${body[*]}  median=$(med "${body[@]}") ms"

sig=()
for i in 1 2 3; do
  cp /tmp/claude-1000/binder.orig.ts "$F"; run > /dev/null
  cp /tmp/claude-1000/binder.sig.ts "$F"; sig+=("$(run)")
done
echo "after SIGNATURE edit    : ${sig[*]}  median=$(med "${sig[@]}") ms"

cp /tmp/claude-1000/binder.orig.ts "$F"
