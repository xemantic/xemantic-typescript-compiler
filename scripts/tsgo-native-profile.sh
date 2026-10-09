#!/usr/bin/env bash
# (TSGO.6) A CPU profile of the Kotlin/Native port's `NativeCheckMain` (release kexe) with Linux `perf`.
#
# Needs `perf` (linux-tools for the running kernel) and kernel.perf_event_paranoid <= 2 for a user
# profile (1 also gives kernel samples). It is a RUNTIME sysctl: a reboot resets it to the distro default
# (4 on this box), after which `perf record` refuses - re-run `sudo sysctl kernel.perf_event_paranoid=1`.
#
# Usage: scripts/tsgo-native-profile.sh <project dir> [parallel|single] [kexe] [out dir]
#   e.g. scripts/tsgo-native-profile.sh build/bench/tsc-project-637d5746
# Writes <out>/perf.data, <out>/stacks.txt (perf script) and <out>/report.txt (self time by mechanism,
# self and inclusive top lists, from scripts/tsgo_perf_stacks.py).
#
# The release kexe has a symbol table but NO frame pointers, so `-g` (fp) unwinds garbage: the call
# graph is DWARF (`--call-graph dwarf`, ~320 MB of data for an 8 s run at 199 Hz). Knobs: FREQ (199).
# Read inclusive numbers with care: Kotlin/Native marks the heap INSIDE mutator safepoints
# (suspendIfRequested -> completeRootSetAndMark), so a checker frame's inclusive time contains the GC
# marking that happened to stop it; self time is the clean signal.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
PROJ="$(cd "$1" && pwd)"
MODE="${2:-parallel}"
KEXE="${3:-$REPO/xemantic-typescript-compiler-tsgo/build/bin/linuxX64/releaseExecutable/xemantic-typescript-compiler-tsgo.kexe}"
OUT="${4:-$REPO/build/tsgo-native-profile}"
command -v perf >/dev/null || { echo "no perf on PATH (linux-tools-\$(uname -r))" >&2; exit 2; }
para="$(cat /proc/sys/kernel/perf_event_paranoid)"
(( para <= 2 )) || { echo "kernel.perf_event_paranoid=$para: perf cannot profile; sudo sysctl kernel.perf_event_paranoid=1" >&2; exit 2; }
mkdir -p "$OUT"
rm -f "$OUT/perf.data" "$OUT/stacks.txt" "$OUT/report.txt"
( cd "$PROJ" && perf record -q -F "${FREQ:-199}" --call-graph dwarf,32768 -o "$OUT/perf.data" \
    "$KEXE" "$PROJ/tsconfig.json" "$MODE" > "$OUT/stdout.txt" 2> "$OUT/stderr.txt" ) || true
[[ -s "$OUT/perf.data" ]] || { echo "no perf.data written; see $OUT/stderr.txt" >&2; exit 1; }
perf script -i "$OUT/perf.data" -F comm,tid,period,ip,sym 2>/dev/null > "$OUT/stacks.txt"
python3 "$REPO/scripts/tsgo_perf_stacks.py" "$OUT/stacks.txt" --mechanisms --top 25 > "$OUT/report.txt"
cat "$OUT/report.txt"
echo "(callers: scripts/tsgo_perf_stacks.py $OUT/stacks.txt --callers <name>; raw: perf report -i $OUT/perf.data)"
