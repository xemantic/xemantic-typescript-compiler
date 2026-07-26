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

# Interleaved A/B driver — the measurement the PERF ground rules require.
#
# The rules (PLAN-PHASE-5.md § PERF, docs/ARCHITECTURE-RETHINK.md § 6) say a
# wall-clock claim is decided ONLY by interleaved A/B medians, because the box
# drifts within a session: round 493 measured the SAME binary at 24.6 s early
# and 26.1-27.1 s two hours later, which turned a real 0.8 s win into an apparent
# 0.7 s regression. `bench-compile-tsc.sh` iterates ONE binary; this alternates
# two, so both sides meet the same drift and background load.
#
# Usage:
#   # 1. keep BOTH class dirs — never recompile between measurements
#   cp -r build/classes/kotlin/jvm/main /tmp/xtsc_A     # baseline
#   ...change code...  ./gradlew compileKotlinJvm       # B = build/classes/...
#
#   # 2. measure (5-6 pairs is usually enough; more on a noisy box)
#   scripts/ab-interleaved.sh /tmp/xtsc_A build/classes/kotlin/jvm/main 6
#
#   # same-binary flag comparison (A gets no extra args, B gets them):
#   scripts/ab-interleaved.sh DIR DIR 4 --someExperimentFlag
#
# Reports per-pair deltas plus the median of each side and the win rate. Treat a
# result inside the +/-2% drift band as NO EFFECT, per the ground rules.
#
# NOTE on the box: a laptop running a browser/IDE can show +/-13% run-to-run,
# which swamps a 1 s effect. When the effect you are chasing is smaller than the
# spread, prefer an IN-PROCESS counter (--passTiming) over wall time — it is
# deterministic and immune to background load.
#
# Prerequisite: the bench project must exist. Create it once with
#   scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

[[ $# -ge 3 ]] || { sed -n '/^# Interleaved A\/B driver/,/^set -euo/p' "$0" | sed '$d;s/^# \{0,1\}//'; exit 2; }

DIR_A="$1"; DIR_B="$2"; PAIRS="$3"; shift 3
B_EXTRA=("$@")     # extra args given to the B side only

PROJECT="${PROJECT:-compiler}"
HEAP="${HEAP:-4g}"
PROJ_DIR="$(ls -d "$REPO_ROOT"/build/bench/tsc-project-* 2>/dev/null | head -1)"
[[ -n "$PROJ_DIR" ]] || {
    echo "error: no bench project — run scripts/bench-compile-tsc.sh --project $PROJECT --no-emit --no-log first" >&2
    exit 1
}

# Resolve the runtime classpath once (the class DIR is prepended per side).
INIT="$REPO_ROOT/build/bench/print-classpath.init.gradle.kts"
[[ -f "$INIT" ]] || { echo "error: $INIT missing — run bench-compile-tsc.sh once" >&2; exit 1; }
CP_TAIL="$("$REPO_ROOT/gradlew" -q --console=plain -I "$INIT" xtscPrintJvmRuntimeClasspath 2>/dev/null \
    | sed -n 's/^XTSC_CLASSPATH=//p' | head -1)"
[[ -n "$CP_TAIL" ]] || { echo "error: could not resolve jvmRuntimeClasspath" >&2; exit 1; }

now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }

# $1 = class dir, rest = extra args. Echoes "<wall_ms> <self_ms> <errors>".
run_one() {
    local dir="$1"; shift
    local out; out="$(mktemp)"
    local start end
    start="$(now_ms)"
    java "-Xmx$HEAP" -cp "$dir:$CP_TAIL" \
        com.xemantic.typescript.compiler.MainKt --noEmit "$@" "$PROJ_DIR" >"$out" 2>&1 || true
    end="$(now_ms)"
    local self err
    self="$(sed -n 's/^time: *\([0-9]*\) ms.*/\1/p' "$out" | head -1)"
    err="$(sed -n 's/^FAILED — \([0-9]*\) error.*/\1/p' "$out" | head -1)"
    [[ -n "$self" ]] || self=0
    [[ -n "$err" ]] || err=0
    rm -f "$out"
    echo "$((end - start)) $self $err"
}

declare -a A_MS=() B_MS=()
B_WINS=0
echo "profile=$PROJECT  pairs=$PAIRS  A=$DIR_A  B=$DIR_B ${B_EXTRA[*]:+(B extra: ${B_EXTRA[*]})}"
for ((i = 1; i <= PAIRS; i++)); do
    # Alternate WITHIN the pair so neither side systematically runs first.
    if (( i % 2 == 1 )); then
        ra="$(run_one "$DIR_A")";                  rb="$(run_one "$DIR_B" "${B_EXTRA[@]+"${B_EXTRA[@]}"}")"
    else
        rb="$(run_one "$DIR_B" "${B_EXTRA[@]+"${B_EXTRA[@]}"}")"; ra="$(run_one "$DIR_A")"
    fi
    a_self="$(cut -d' ' -f2 <<<"$ra")"; a_err="$(cut -d' ' -f3 <<<"$ra")"
    b_self="$(cut -d' ' -f2 <<<"$rb")"; b_err="$(cut -d' ' -f3 <<<"$rb")"
    A_MS+=("$a_self"); B_MS+=("$b_self")
    (( b_self < a_self )) && B_WINS=$((B_WINS + 1))
    printf 'pair %d: A=%sms (err %s)  B=%sms (err %s)  delta=%+dms\n' \
        "$i" "$a_self" "$a_err" "$b_self" "$b_err" "$((b_self - a_self))"
    [[ "$a_err" == "$b_err" ]] || echo "  !! ERROR COUNTS DIFFER — B is not behaviour-preserving; the timing is not comparable"
done

echo "---"
# The verdict must survive a NOISY box. Round 716 hit the failure mode this
# guards against: 6 pairs whose median said "12.8% WIN" while B won only 2 of 6,
# because one outlier pair (-8.5 s) dragged the median. A median over a handful
# of samples whose per-pair spread exceeds the effect is not a measurement.
python3 - "$PAIRS" "$B_WINS" "${A_MS[@]}" -- "${B_MS[@]}" <<'PY'
import sys, statistics
argv = sys.argv[1:]
pairs, wins = int(argv[0]), int(argv[1])
sep = argv.index('--')
A = [int(x) for x in argv[2:sep]]
B = [int(x) for x in argv[sep + 1:]]
deltas = [b - a for a, b in zip(A, B)]
ma, mb = statistics.median(A), statistics.median(B)
pct = (mb - ma) / ma * 100
spread = max(deltas) - min(deltas)
med_delta = statistics.median(deltas)
print(f"MEDIAN self-time: A={ma:.0f}ms  B={mb:.0f}ms  delta={mb-ma:+.0f}ms ({pct:+.2f}%)   B wins {wins}/{pairs}")
print(f"per-pair delta: median={med_delta:+.0f}ms  spread={spread:.0f}ms  "
      f"range=[{min(deltas):+.0f}, {max(deltas):+.0f}]")
noisy = spread > abs(med_delta) * 3 or spread > 0.25 * ma
split = 0.25 < wins / pairs < 0.75
if noisy:
    print("VERDICT: NOISE-DOMINATED — the per-pair spread dwarfs the effect. This run "
          "decides NOTHING in either direction.")
    print("  Do: quiesce the box (close browsers/IDEs), raise the pair count, or — "
          "better — decide on an IN-PROCESS counter (--passTiming), which is "
          "deterministic and immune to load.")
elif split and abs(pct) >= 2:
    print(f"VERDICT: INCONSISTENT — median says {abs(pct):.1f}% but B wins only {wins}/{pairs}. "
          "Treat as undecided and re-run with more pairs.")
elif abs(pct) < 2:
    print("VERDICT: inside the +/-2% drift band — NO EFFECT. Per the ground rules this "
          "does not land alone; fold it into a structural item.")
else:
    print(f"VERDICT: {'REGRESSION' if pct > 0 else 'WIN'} of {abs(pct):.1f}% "
          f"(B wins {wins}/{pairs}) — outside the drift band. Confirm before acting.")
PY
