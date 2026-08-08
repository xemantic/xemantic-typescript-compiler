#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
# xemantic-typescript-compiler - a conformant TypeScript compiler and type
# checker that runs on JVM, native, and WebAssembly
# Copyright (C) 2026 Kazimierz Pogoda / Xemantic
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as
# published by the Free Software Foundation, version 3 of the License.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public
# License along with this program.  If not, see <https://www.gnu.org/licenses/>.
#
# As a special exception, this file contains Helper Code covered by the
# xemantic-typescript-compiler Output Exception; additional permissions
# are granted as described in the file LICENSE-EXCEPTION.
#

# WARM interleaved A/B driver — the sensitive counterpart of ab-interleaved.sh.
#
# WHY THIS EXISTS. `ab-interleaved.sh` forks a fresh JVM per run, so every one of
# its samples pays the full JIT warm-up and its drift band is +/-2.0% = +/-536 ms.
# `BenchMain` rebuilds the whole project N times INSIDE one JVM, so after a couple
# of warm-up rebuilds C2 has compiled the checker and the remaining iterations
# time the compiler rather than the JIT. Round 771 calibrated that protocol at
# **+/-1.0% = +/-114 ms** (docs/perf/aot-native-image.md § 4) — 4.7x more sensitive
# in ABSOLUTE terms, and ten warm iterations cost less than four cold runs.
# Round 774 re-ran the A/A calibration with this script and REPRODUCED the band —
# but only on an idle box, and the failed attempt is the more useful half:
#
#   run 1, agent polling the log ~100x during it:  A/A deltas -0.19% / -6.70% / +2.47%
#                                                  process-median sd 278 ms (2.4%)
#   run 2, box left completely alone:              A/A deltas -0.36% / -0.54% / +1.02%
#                                                  process-median sd  48 ms (0.41%)
#
# Same binary on both arms in both runs. **Anything you do on this box while it
# measures — including a `tail` of this very log every few seconds — is inside the
# measurement**, because one xtsc run already takes ~3.15 of 4 cores and the spare
# capacity is what your shell commands eat. So: start the run, then LEAVE IT ALONE.
# The corollary for a real A/B: a warm run whose reported arm sd exceeds ~1% was
# not measured on a quiet box, and its verdict should be discarded rather than
# quoted, no matter how clean the median looks.
#
# WHEN TO USE WHICH (docs/ARCHITECTURE-RETHINK.md § 6):
#   * cold (`ab-interleaved.sh`) — anything whose cost is warm-up-shaped, any claim
#     about the SHIPPED one-shot CLI experience, and any comparison against the
#     archived `bench-history/` rows, which are all cold.
#   * warm (this script) — steady-state COMPUTE claims about compiler machinery,
#     i.e. "does this change make the checker do less work". It is the protocol to
#     reach for when the effect you are chasing is 100-500 ms, which the cold band
#     simply cannot see.
# Both are `--noEmit` check-only, so they compare to each other and to
# `cost_gate.py` — and NOT to the emit-mode CI ratio (§ 0.2's mode rule).
#
# THE MEASUREMENT. One fresh `java` process per SAMPLE; the sample's value is the
# MEDIAN of that process's measured iterations. Samples alternate A,B,A,B,… so both
# arms meet the same box drift — the round-493 story in ab-interleaved.sh's header
# is exactly as true warm as cold. Per-process medians, never per-iteration values,
# because iterations inside one process are serially correlated (same JIT state,
# same heap) and pooling them would fake the sample count.
#
# SELF-FALSIFICATION (not optional). A warm rebuild shares whatever state the
# pipeline does not reset — id counters, interning caches, the Vfs object — so a
# warm number is a measurement only while every iteration still answers the SAME
# program. `BenchMain` prints `files` and `errors` on every iteration for exactly
# this reason. This script ABORTS the whole run if any iteration in any process
# disagrees with any other: a drifting files/errors column means the timings below
# it measure a different compile, not a faster one.
#
# Usage:
#   # 1. keep BOTH main class dirs — never recompile between measurements
#   cp -r xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main /tmp/xtsc_A     # baseline
#   ...change code...  ./gradlew compileKotlinJvm       # B = build/classes/...
#
#   # 2. measure (3 pairs = 6 processes = round 771's calibration shape, ~14 min)
#   scripts/ab-warm.sh /tmp/xtsc_A xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main 3
#
#   # knobs (env): WARMUP=2 ITERS=8 HEAP=4g PROJ_DIR=... KEEP_DAEMONS=1 XTSC_CP=...
#
# CLASSPATH CONTRACT — read this before wondering why A and B share anything.
# `BenchMain` lives in `commonTest`, so the run needs the TEST classes too, while
# the point of an A/B is to swap only the MAIN classes. The classpath is therefore
#     <the A-or-B main class dir> : xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test : <deps>
# with the swapped dir FIRST. Two consequences:
#   * the test class dir is SHARED by both arms and comes from the CURRENT build —
#     which is fine because `BenchMainKt` touches only `ProjectCompiler`/`SystemVfs`,
#     but it means a change that alters the API BenchMain calls cannot be A/B'd
#     warm at all (the shared `BenchMainKt.class` would not link against the old
#     arm). Use the cold driver for those.
#   * there is no B-side extra-args slot (`ab-interleaved.sh` has one): BenchMain
#     takes no compiler flags, so a same-binary FLAG comparison is a cold job.
# The dependency tail is resolved once through Gradle (same init script as
# `ab-interleaved.sh` / `cost_gate.py`) and cached in build/bench/cp-warm.txt;
# the cache is used only while it is newer than build.gradle.kts, because a stale
# hand-written cp file is how `build/bench/cp.txt` came to name kotlinx-io 0.9.0
# after the build had moved to 0.9.1. Override wholesale with XTSC_CP=<deps>.
#
# THE BOX. This machine has 7.7 GB and ZERO swap, and an idle Gradle/Kotlin daemon
# squats GB for its idle timeout, so this script stops the daemons after resolving
# the classpath and before the first sample (KEEP_DAEMONS=1 opts out). Never run a
# gradle task, a second benchmark, or anything else heavy while it is measuring: a
# "single-threaded" xtsc run already takes ~3.15 of this box's 4 cores.
#
# Prerequisite: the bench project must exist. Create it once with
#   scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

[[ $# -ge 3 ]] || { sed -n '/^# WARM interleaved A\/B driver/,/^set -euo/p' "$0" | sed '$d;s/^# \{0,1\}//'; exit 2; }

DIR_A="$1"; DIR_B="$2"; PAIRS="$3"

WARMUP="${WARMUP:-2}"
ITERS="${ITERS:-8}"
HEAP="${HEAP:-4g}"
TEST_CLASSES="$REPO_ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test"
# The path moved with the module split; without this the driver would run with a
# classpath entry that does not exist and report a verdict anyway.
[[ -d "$TEST_CLASSES" ]] || {
    echo "error: test classes not found at $TEST_CLASSES — run ./gradlew jvmTest first" >&2
    exit 1
}

PROJ_DIR="${PROJ_DIR:-$(ls -d "$REPO_ROOT"/build/bench/tsc-project-* 2>/dev/null | head -1)}"
[[ -n "$PROJ_DIR" && -d "$PROJ_DIR" ]] || {
    echo "error: no bench project — run scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log first" >&2
    exit 1
}
for d in "$DIR_A" "$DIR_B"; do
    [[ -d "$d" ]] || { echo "error: class dir '$d' does not exist" >&2; exit 1; }
    [[ -f "$d/com/xemantic/typescript/compiler/MainKt.class" ]] || {
        echo "error: '$d' is not a jvm MAIN class dir (no MainKt.class)" >&2; exit 1; }
done
[[ -f "$TEST_CLASSES/com/xemantic/typescript/compiler/bench/BenchMainKt.class" ]] || {
    echo "error: $TEST_CLASSES/.../BenchMainKt.class missing — run ./gradlew compileTestKotlinJvm" >&2
    exit 1
}
(( ITERS >= 5 )) || echo "warning: ITERS=$ITERS — a per-process median over fewer than 5 iterations is thin" >&2

# --- dependency tail -------------------------------------------------------
# ROUND 858: this used to guard the cache with `cp-warm.txt -nt
# core/build.gradle.kts`, which is blind to the bump that actually matters — the
# VERSIONS live in gradle/libs.versions.toml, and a bump there leaves the module
# build file untouched. That is precisely how build/bench/cp.txt came to name
# kotlin-stdlib 2.4.0 a month after the build had moved to 2.4.10. The shared
# resolver validates against every build-definition input and asserts each named
# jar still exists; it refuses loudly rather than serving a stale tail.
. "$REPO_ROOT/scripts/lib/dep-classpath.sh"
CP_TAIL="$(xtsc_dep_classpath "$REPO_ROOT/build/bench/cp-warm.txt")" || {
    echo "error: could not resolve the dependency tail" >&2; exit 1; }

# A squatting daemon is GB of RAM on a zero-swap box; measuring beside one is how
# round 721 recorded compiles that were really something else entirely.
if [[ -z "${KEEP_DAEMONS:-}" ]]; then
    echo "stopping gradle daemons before measuring (KEEP_DAEMONS=1 to skip) ..." >&2
    "$REPO_ROOT/gradlew" --stop >/dev/null 2>&1 || true
fi

# --- one sample = one process; its value is the median of its iterations ----
# Echoes "<median_ms> <files> <errors> <n> <min_ms> <max_ms>", or "DRIFT …"/"FAIL".
run_one() {
    local dir="$1"
    local out; out="$(mktemp)"
    java "-Xmx$HEAP" -cp "$dir:$TEST_CLASSES:$CP_TAIL" \
        com.xemantic.typescript.compiler.bench.BenchMainKt "$PROJ_DIR" "$WARMUP" "$ITERS" \
        >"$out" 2>&1 || true
    python3 - "$out" <<'PY'
import json, statistics, sys
its = []
for line in open(sys.argv[1], errors="replace"):
    line = line.strip()
    if line.startswith('{"iter"'):
        try:
            its.append(json.loads(line))
        except ValueError:
            pass
if not its:
    print("FAIL")
    sys.exit(0)
fe = sorted({(o["files"], o["errors"]) for o in its})
if len(fe) != 1:
    print("DRIFT " + " ".join("%d/%d" % (f, e) for f, e in fe))
    sys.exit(0)
ms = [o["ms"] for o in its]
print("%.0f %d %d %d %.0f %.0f" % (statistics.median(ms), fe[0][0], fe[0][1],
                                   len(ms), min(ms), max(ms)))
PY
    rm -f "$out"
}

abort_falsified() {   # $1 = arm label, $2 = raw run_one output
    echo >&2
    echo "!! ABORT — the warm probe falsified itself on arm $1: $2" >&2
    echo "   Iterations inside one process reported different files/errors, or the" >&2
    echo "   process produced no iterations at all. State is leaking across in-process" >&2
    echo "   rebuilds (or the compile crashed), so these timings do NOT measure one" >&2
    echo "   program and no A/B verdict can be drawn from them. Fix the leak, or fall" >&2
    echo "   back to the COLD driver (scripts/ab-interleaved.sh), which forks per run." >&2
    exit 3
}

REF=""   # "<files>/<errors>" of the first sample; every later sample must match
declare -a A_MS=() B_MS=()
B_WINS=0

sample() {   # $1 = dir, $2 = label -> sets SAMPLE_MS, prints one line
    local raw med files errors n lo hi fe
    raw="$(run_one "$1")"
    case "$raw" in
        FAIL|DRIFT*) abort_falsified "$2" "$raw" ;;
    esac
    read -r med files errors n lo hi <<<"$raw"
    fe="$files/$errors"
    if [[ -z "$REF" ]]; then
        REF="$fe"
    elif [[ "$fe" != "$REF" ]]; then
        abort_falsified "$2" "process reported $fe, earlier processes reported $REF"
    fi
    printf '  %s: median=%sms  (n=%s, range %s-%s, files/errors %s)\n' "$2" "$med" "$n" "$lo" "$hi" "$fe"
    SAMPLE_MS="$med"
}

echo "warm A/B  project=$(basename "$PROJ_DIR")  pairs=$PAIRS  warmup=$WARMUP  iters=$ITERS  heap=$HEAP"
echo "  A=$DIR_A"
echo "  B=$DIR_B"
for ((i = 1; i <= PAIRS; i++)); do
    echo "pair $i:"
    # Alternate WITHIN the pair so neither side systematically runs first.
    if (( i % 2 == 1 )); then
        sample "$DIR_A" A; a_ms="$SAMPLE_MS"
        sample "$DIR_B" B; b_ms="$SAMPLE_MS"
    else
        sample "$DIR_B" B; b_ms="$SAMPLE_MS"
        sample "$DIR_A" A; a_ms="$SAMPLE_MS"
    fi
    A_MS+=("$a_ms"); B_MS+=("$b_ms")
    if (( b_ms < a_ms )); then B_WINS=$((B_WINS + 1)); fi
    printf '  delta=%+dms (%+.2f%%)\n' "$((b_ms - a_ms))" \
        "$(python3 -c "print(($b_ms-$a_ms)/$a_ms*100)")"
done

echo "---"
# Same statistical stance as ab-interleaved.sh — only the band changes (1.0%, the
# round-771 warm calibration, instead of 2.0%). A median over a handful of samples
# whose per-pair spread exceeds the effect is not a measurement, warm or cold.
python3 - "$PAIRS" "$B_WINS" "${A_MS[@]}" -- "${B_MS[@]}" <<'PY'
import sys, statistics
argv = sys.argv[1:]
pairs, wins = int(argv[0]), int(argv[1])
sep = argv.index('--')
A = [float(x) for x in argv[2:sep]]
B = [float(x) for x in argv[sep + 1:]]
deltas = [b - a for a, b in zip(A, B)]
ma, mb = statistics.median(A), statistics.median(B)
pct = (mb - ma) / ma * 100
spread = max(deltas) - min(deltas)
med_delta = statistics.median(deltas)
BAND = 1.0   # % — round 771 / round 774 warm calibration, docs/perf/aot-native-image.md § 4
print(f"MEDIAN warm rebuild: A={ma:.0f}ms  B={mb:.0f}ms  delta={mb-ma:+.0f}ms ({pct:+.2f}%)"
      f"   B wins {wins}/{pairs}")
print(f"per-pair delta: median={med_delta:+.0f}ms  spread={spread:.0f}ms  "
      f"range=[{min(deltas):+.0f}, {max(deltas):+.0f}]")
# Print each arm's own run-to-run spread: a differential is only as sharp as the
# larger of the two within-arm spreads (the round-756 rule).
for name, xs in (("A", A), ("B", B)):
    sd = statistics.stdev(xs) if len(xs) > 1 else 0.0
    m = statistics.median(xs)
    print(f"  arm {name}: n={len(xs)} median={m:.0f}ms sd={sd:.0f}ms "
          f"({sd / m * 100:.2f}%) range=[{min(xs):.0f}, {max(xs):.0f}]")
noisy = spread > abs(med_delta) * 3 or spread > 0.25 * ma
split = 0.25 < wins / pairs < 0.75
if noisy:
    print("VERDICT: NOISE-DOMINATED — the per-pair spread dwarfs the effect. This run "
          "decides NOTHING in either direction.")
    print("  Do: quiesce the box, raise the pair count or ITERS, or — better — decide "
          "on an IN-PROCESS counter (--passTiming / cost_gate.py), which is "
          "deterministic and immune to load.")
elif split and abs(pct) >= BAND:
    print(f"VERDICT: INCONSISTENT — median says {abs(pct):.1f}% but B wins only {wins}/{pairs}. "
          "Treat as undecided and re-run with more pairs.")
elif abs(pct) < BAND:
    print(f"VERDICT: inside the +/-{BAND:.1f}% WARM drift band — NO EFFECT. (The cold "
          "band is +/-2.0%; do not quote this run against a cold number.)")
else:
    print(f"VERDICT: {'REGRESSION' if pct > 0 else 'WIN'} of {abs(pct):.1f}% "
          f"(B wins {wins}/{pairs}) — outside the +/-{BAND:.1f}% warm band. "
          "Warm-only: it is a steady-state COMPUTE claim, not a cold-CLI claim.")
PY
