#!/usr/bin/env bash
# Our half of the tsgo-vs-us incremental comparison — the same four cells
# scripts/tsgo-incremental-bench.sh measures, over the same tree and the same edits.
#
# Since (TSGO.8-a) "ours" is the tsgo PORT, run through tsgo's own harness and protocol
# (a fresh process per cell, `--incremental --noEmit`, state in `.tsbuildinfo`), so the
# two halves differ in the compiler only. The `-project` in-session arm this script used
# to time retired with `-core`; an incremental project system on the port is (TSGO.10).
#
# usage: inc46-vs-tsgo.sh <projectDir> <editFileRelative> <editsDir> [reps]
#   XTSC_BIN=<binary> (default: the port's PGO image, then its plain image) —
#   build one with scripts/tsgo-native-image-pgo.sh or `:xemantic-typescript-compiler-tsgo:nativeImage`.
#   Arm B's tree and edits: scripts/gen-many-small-project.py (its header has the line).
set -euo pipefail
cd "$(dirname "$0")/.."
NATIVE=xemantic-typescript-compiler-tsgo/build/native
BIN="${XTSC_BIN:-}"
if [[ -z $BIN ]]; then
  for c in "$NATIVE/xtsc-tsgo-pgo" "$NATIVE/xtsc-tsgo"; do [[ -x $c ]] && { BIN="$c"; break; }; done
fi
[[ -n $BIN && -x $BIN ]] || { echo "REFUSED: no port binary (set XTSC_BIN or build $NATIVE/xtsc-tsgo)" >&2; exit 2; }
exec env TSC_BIN="$BIN" scripts/tsgo-incremental-bench.sh "$@"
