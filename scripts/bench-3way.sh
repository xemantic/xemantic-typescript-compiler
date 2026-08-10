#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
# Three-way compile bench: compile the pinned TypeScript compiler sources with
# xtsc, the reference JS tsc, and the native tsgo, then write a per-run Markdown
# report under bench-history/runs/ and prepend rows to bench-history/README.md
# so improvements are observable across runs (see .github/workflows/bench.yml).
#
# All three compile the SAME materialized project (the `compiler` profile that
# scripts/bench-compile-tsc.sh builds from typescript-repo @ typeScriptCommit),
# cold single process. Wall-clock is the comparable metric (xtsc also reports its
# self-time, which excludes JVM startup/JIT).
#
# TWO MODES, both measured, both like-for-like (round 739 — queue item (BENCH.1)):
#
#   check-only  every compiler runs with --noEmit / --no-emit: nothing is
#               transformed, emitted or written. THIS is the mode the whole perf
#               arc profiles (scripts/ab-interleaved.sh and scripts/cost_gate.py
#               both pass --noEmit), so it is the mode a perf claim must quote.
#   emit        every compiler type-checks AND emits JavaScript to an outDir.
#
# Until round 739 only the emit mode was measured, and until round 738 xtsc's
# --noEmit still ran the Transformer + Emitter and discarded the result — so a
# check-only ratio had never been measured on either side. Do not compare a row
# of one mode against a row of the other.
#
# Usage: scripts/bench-3way.sh --tsc PATH --tsgo PATH [options]
#   --tsc PATH        reference JS `tsc` binary (required)
#   --tsgo PATH       native `tsgo` binary (required)
#   --xtsc-native P   OPTIONAL ahead-of-time xtsc binary (./gradlew nativeImage ->
#                     build/native/xtsc). When given, it is measured as a FOURTH
#                     compiler in every mode. Omit it and the run is unchanged.
#                     Round 771: ~1.97x the JVM cold, output byte-identical on all
#                     8 profiles — see docs/perf/aot-native-image.md.
#   --project NAME    tsc subproject profile (default compiler)
#   --xtsc-iters N    measured COLD xtsc runs, median (default 1; ~30s each in CI)
#   --ref-iters N     measured tsc/tsgo runs, median (default 3; cheap)
#   --xtsc-warmup N   in-process warm-up rebuilds before the warm arm's measured
#                     window (default 6). SIX is measured, not chosen: two
#                     identical 16-iteration ladders re-sliced per setting put two
#                     IDENTICAL process medians 3.3% apart at warmup 2, 2.0% at 3,
#                     0.8% at 6. Under three, the measured window sits in the JIT
#                     ramp and the series' own noise floor swamps the trend it is
#                     supposed to show.
#   --xtsc-warm-iters N  measured warm rebuilds, median (default 8)
#   --no-warm         skip the warm arm (cold-only, the pre-2026-08-10 behaviour)
#   --modes LIST      comma-separated subset of check-only,emit (default both)
#   --out-dir DIR     bench-history root (default bench-history)
#   --label TEXT      free-text note stored in the report
#   --help            show this help

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TSC_BIN=""; TSGO_BIN=""; XTSC_NATIVE=""; PROJECT=compiler
XTSC_ITERS=1; REF_ITERS=3
XTSC_WARMUP=6; XTSC_WARM_ITERS=8; WARM=1
MODES="check-only,emit"
OUT_DIR="$REPO_ROOT/bench-history"; LABEL=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tsc)        TSC_BIN="$2"; shift 2 ;;
        --tsgo)       TSGO_BIN="$2"; shift 2 ;;
        --xtsc-native) XTSC_NATIVE="$2"; shift 2 ;;
        --project)    PROJECT="$2"; shift 2 ;;
        --xtsc-iters) XTSC_ITERS="$2"; shift 2 ;;
        --xtsc-warmup) XTSC_WARMUP="$2"; shift 2 ;;
        --xtsc-warm-iters) XTSC_WARM_ITERS="$2"; shift 2 ;;
        --no-warm)    WARM=0; shift ;;
        --ref-iters)  REF_ITERS="$2"; shift 2 ;;
        --modes)      MODES="$2"; shift 2 ;;
        --out-dir)    OUT_DIR="$2"; shift 2 ;;
        --label)      LABEL="$2"; shift 2 ;;
        --help|-h)    sed -n '/^# Three-way/,/^set -euo/p' "$0" | sed '$d;s/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
done
IFS=',' read -r -a MODE_LIST <<<"$MODES"
for m in "${MODE_LIST[@]}"; do
    [[ "$m" == check-only || "$m" == emit ]] || {
        echo "error: unknown mode '$m' (known: check-only, emit)" >&2; exit 2; }
done
[[ -n "$TSC_BIN"  ]] || { echo "error: --tsc PATH is required" >&2; exit 2; }
[[ -n "$TSGO_BIN" ]] || { echo "error: --tsgo PATH is required" >&2; exit 2; }
command -v python3 >/dev/null || { echo "error: python3 not found" >&2; exit 1; }

# `tsc --version` prints "Version 6.0.3"; tsgo prints "Version 7.0.0-dev.NNN".
TSC_VER="$("$TSC_BIN"  --version 2>/dev/null | awk '{print $NF}' || echo '?')"
TSGO_VER="$("$TSGO_BIN" --version 2>/dev/null | awk '{print $NF}' || echo '?')"
[[ -n "$TSC_VER"  ]] || TSC_VER='?'
[[ -n "$TSGO_VER" ]] || TSGO_VER='?'
strip_ansi() { sed -E $'s/\x1b\\[[0-9;]*m//g'; }

median() { printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END{ if(NR%2) print a[(NR+1)/2]; else print int((a[NR/2]+a[NR/2+1])/2) }'; }

# --------------------------------------------------------------------------
# 1. xtsc: reuse bench-compile-tsc.sh (materializes the project, builds the
#    compiler, resolves the classpath, runs MainKt). Parse its summary.
# --------------------------------------------------------------------------
TS_COMMIT8="$(git -C "$REPO_ROOT/typescript-repo" rev-parse HEAD | cut -c1-8)"
if [[ "$PROJECT" == compiler ]]; then PROJ_DIR="$REPO_ROOT/build/bench/tsc-project-$TS_COMMIT8"
else PROJ_DIR="$REPO_ROOT/build/bench/tsc-$PROJECT-$TS_COMMIT8"; fi

run_xtsc() { # $1=mode ; sets xtsc_wall xtsc_self xtsc_err xtsc_files LOC
    local mode="$1" xlog extra=()
    [[ "$mode" == check-only ]] && extra=(--no-emit)
    echo "== xtsc ($mode) =="
    xlog="$(mktemp)"
    "$REPO_ROOT/scripts/bench-compile-tsc.sh" --project "$PROJECT" --iterations "$XTSC_ITERS" \
        --no-log ${extra[@]+"${extra[@]}"} | tee "$xlog" >&2 || { echo "xtsc bench failed" >&2; exit 1; }

    # xtsc self/errors/files come from the MainKt RUN LOG (printed by the compiler
    # itself, so correct on any OS — the bench summary's own stats are 0 on macOS
    # BSD grep). wall and LOC come from the summary (date / wc, both fine).
    # LOC is read off the `input:` line specifically: a bare `[0-9]+ LOC` also
    # matches the `throughput: N LOC/s` line, and taking the LAST match silently
    # published THROUGHPUT as the LOC count in every report before round 739.
    xtsc_wall=$(sed -n 's/^compile:.*), *\([0-9]*\) ms wall$/\1/p' "$xlog" | head -1)
    LOC=$(sed -n 's/^input:.*, \([0-9]*\) LOC$/\1/p' "$xlog" | head -1)
    [[ -n "$xtsc_wall" ]] || xtsc_wall=0
    [[ -n "$LOC" ]] || LOC=0
    local runlog
    runlog=$(grep -oE 'full output: .*' "$xlog" | head -1 | sed 's/^full output: //' || true)
    if [[ -n "$runlog" && -f "$runlog" ]]; then
        xtsc_self=$(grep -oE '^time:\s+[0-9]+' "$runlog" | grep -oE '[0-9]+' | head -1 || echo 0)
        xtsc_err=$(grep -oE 'diagnostics:\s+[0-9]+ error' "$runlog" | grep -oE '[0-9]+' | head -1 || echo 0)
        xtsc_files=$(grep -oE '[0-9]+ in program' "$runlog" | grep -oE '[0-9]+' | head -1 || echo 0)
    else
        xtsc_self=0; xtsc_err=0; xtsc_files=0
    fi
    rm -f "$xlog"
}

# --------------------------------------------------------------------------
# 1a. xtsc WARM — the arm this bench exists for.
#
# WHY THIS IS THE HEADLINE AND THE COLD ARM IS NOT. The JVM is the fastest
# target and the one the perf arc optimises; a cold single-shot measures ~75%
# JVM warm-up, so the cold ratio moves when HotSpot's ramp moves and barely
# responds to the compiler getting faster. Measured on the dev box, same commit,
# same profile: cold 27,996 ms against warm 6,481 ms check-only. A series meant
# to show the moment xtsc MATCHES tsc has to watch the number that is actually
# converging.
#
# The warm number is an in-process REBUILD, so it excludes JVM startup — it is
# the daemon / `--serve` / watch figure, not the one-shot CLI figure. tsc and
# tsgo are still whole processes, which slightly flatters xtsc: node's startup is
# a few hundred ms of tsc's ~15 s and tsgo is native. The cold arm is kept in the
# report precisely so both readings stay visible.
#
# `BenchMain` lives in commonTest, so this needs the TEST classes as well as the
# main ones, and the dependency tail comes from the shared validating resolver —
# never a hand-frozen cp file (round 858).
# --------------------------------------------------------------------------
run_xtsc_warm() { # $1=mode ; sets xtsc_warm (0 when the arm is off/unavailable)
    xtsc_warm=0
    [[ "$WARM" -eq 1 ]] || return 0
    local mode="$1" emitarg="off" out main test cp_tail
    [[ "$mode" == emit ]] && emitarg="emit"
    main="$REPO_ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main"
    test="$REPO_ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test"
    if [[ ! -f "$test/com/xemantic/typescript/compiler/bench/BenchMainKt.class" ]]; then
        echo "== xtsc-warm ($mode): compiling test classes ==" >&2
        ./gradlew -q compileTestKotlinJvm >&2 || { echo "warm arm unavailable" >&2; return 0; }
    fi
    # shellcheck source=lib/dep-classpath.sh
    . "$REPO_ROOT/scripts/lib/dep-classpath.sh"
    cp_tail="$(xtsc_dep_classpath "$REPO_ROOT/build/bench/cp-warm.txt")" || {
        echo "warm arm: could not resolve the dependency tail" >&2; return 0; }

    echo "== xtsc-warm ($mode, warmup $XTSC_WARMUP, iters $XTSC_WARM_ITERS) =="
    out="$(mktemp)"
    java -Xmx4g -cp "$main:$test:$cp_tail" \
        com.xemantic.typescript.compiler.bench.BenchMainKt \
        "$PROJ_DIR" "$XTSC_WARMUP" "$XTSC_WARM_ITERS" off "$emitarg" >"$out" 2>&1 || true
    # BenchMain self-falsifies: it prints files/errors per iteration and aborts on
    # drift. Take the median of the `iter` lines; a run that produced none is a
    # failed arm, reported as 0 rather than silently as a fast one.
    xtsc_warm="$(python3 - "$out" <<'PY'
import json, statistics, sys
its=[]
for line in open(sys.argv[1], errors="replace"):
    line=line.strip()
    if not line.startswith("{"): continue
    try: o=json.loads(line)
    except Exception: continue
    if "iter" in o: its.append(o["ms"])
print(int(statistics.median(its)) if its else 0)
PY
)"
    [[ "$xtsc_warm" != 0 ]] || { echo "::warning::warm arm produced no iterations" >&2; sed -n '1,20p' "$out" >&2; }
    rm -f "$out"
}

# --------------------------------------------------------------------------
# 1b. The ahead-of-time xtsc binary (optional), on the SAME project and mode.
#
# Uses xtsc's OWN CLI rather than bench-compile-tsc.sh, which drives `java`.
# Emit mode needs no --outDir: xtsc writes to the outDir the materialized
# tsconfig already sets, exactly as the JVM arm does.
# --------------------------------------------------------------------------
run_native() { # $1=mode ; sets nat_wall nat_self nat_err
    local mode="$1" walls=() selfs=() out start end i
    nat_wall=0; nat_self=0; nat_err=0
    [[ -n "$XTSC_NATIVE" ]] || return 0
    echo "== xtsc-native ($mode) =="
    for ((i=1;i<=XTSC_ITERS;i++)); do
        out="$(mktemp)"
        start=$(date +%s%N)
        if [[ "$mode" == check-only ]]; then
            "$XTSC_NATIVE" --noEmit "$PROJ_DIR" >"$out" 2>&1 || true
        else
            "$XTSC_NATIVE" "$PROJ_DIR" >"$out" 2>&1 || true
        fi
        end=$(date +%s%N)
        walls+=($(( (end-start)/1000000 )))
        # Self time and errors come from the compiler's own output, so they are
        # correct on any OS (the round-730 macOS BSD-grep trap hits parsed stats,
        # not these).
        selfs+=($(grep -oE '^time:[[:space:]]+[0-9]+' "$out" | grep -oE '[0-9]+' | head -1 || echo 0))
        nat_err=$(grep -oE '[0-9]+ error\(s\)' "$out" | grep -oE '[0-9]+' | head -1 || echo 0)
        rm -f "$out"
    done
    nat_wall=$(median "${walls[@]}")
    nat_self=$(median "${selfs[@]}")
}

# --------------------------------------------------------------------------
# 2. tsc / tsgo on the SAME materialized project, in the SAME mode.
# --------------------------------------------------------------------------
run_ref() { # $1=bin  $2=mode ; echoes "wall_ms err"
    local bin="$1" mode="$2" walls=() out i start end err=0
    for ((i=1;i<=REF_ITERS;i++)); do
        out="$(mktemp)"
        start=$(date +%s%N)
        if [[ "$mode" == check-only ]]; then
            "$bin" -p "$PROJ_DIR/tsconfig.json" --noEmit >"$out" 2>&1 || true
        else
            "$bin" -p "$PROJ_DIR/tsconfig.json" --outDir "$(mktemp -d)" >"$out" 2>&1 || true
        fi
        end=$(date +%s%N)
        walls+=($(( (end-start)/1000000 )))
        err=$(strip_ansi <"$out" | grep -cE 'error TS[0-9]+' || true)
        rm -f "$out"
    done
    echo "$(median "${walls[@]}") ${err:-0}"
}

# Measure every requested mode; MODE_DATA rows are consumed by the reporter below.
MODE_DATA=()
for MODE in "${MODE_LIST[@]}"; do
    run_xtsc "$MODE"
    run_xtsc_warm "$MODE"
    echo "== tsc ($TSC_VER, $MODE) ==";   read -r tsc_wall  tsc_err  < <(run_ref "$TSC_BIN"  "$MODE")
    echo "== tsgo ($TSGO_VER, $MODE) =="; read -r tsgo_wall tsgo_err < <(run_ref "$TSGO_BIN" "$MODE")
    run_native "$MODE"
    MODE_DATA+=("$MODE $xtsc_wall $xtsc_self $xtsc_err $tsc_wall $tsc_err $tsgo_wall $tsgo_err $nat_wall $nat_self $nat_err $xtsc_warm")
done

# --------------------------------------------------------------------------
# 3. Render the per-run report + update the index.
# --------------------------------------------------------------------------
XTSC_REV="$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD)"
XTSC_REV_FULL="$(git -C "$REPO_ROOT" rev-parse HEAD)"
RUN_STAMP="${BENCH_RUN_STAMP:-$(date -u +%Y%m%dT%H%M%SZ)}"
RUNNER="${BENCH_RUNNER:-$(uname -s)-$(uname -m)}"
REPO_SLUG="${GITHUB_REPOSITORY:-xemantic/xemantic-typescript-compiler}"

# All values are passed via the environment (NOT string-interpolated into the
# heredoc) — the report text contains backticks/`$` that an unquoted heredoc would
# run as command substitution. The delimiter is quoted so bash leaves the body verbatim.
export B_OUT_DIR="$OUT_DIR" B_STAMP="$RUN_STAMP" B_REV="$XTSC_REV" B_REV_FULL="$XTSC_REV_FULL"
export B_PROJECT="$PROJECT" B_RUNNER="$RUNNER" B_SLUG="$REPO_SLUG" B_LABEL="$LABEL"
export B_TS_COMMIT="$TS_COMMIT8" B_FILES="$xtsc_files" B_LOC="$LOC"
export B_TSC_VER="$TSC_VER" B_TSGO_VER="$TSGO_VER"
export B_XITERS="$XTSC_ITERS" B_REFITERS="$REF_ITERS"
export B_WARMUP="$XTSC_WARMUP" B_WARMITERS="$XTSC_WARM_ITERS"
B_MODE_DATA="$(printf '%s\n' "${MODE_DATA[@]}")"; export B_MODE_DATA

python3 - <<'PYEOF'
import os, re
g = lambda k, d="": os.environ.get(k, d)
i = lambda k: int(g(k) or 0)

out_dir  = g("B_OUT_DIR")
runs_dir = os.path.join(out_dir, "runs")
os.makedirs(runs_dir, exist_ok=True)

stamp, rev, rev_full = g("B_STAMP"), g("B_REV"), g("B_REV_FULL")
project, runner, slug, label = g("B_PROJECT"), g("B_RUNNER"), g("B_SLUG"), g("B_LABEL")
ts_commit, files, loc = g("B_TS_COMMIT"), i("B_FILES"), i("B_LOC")
tsc_ver, tsgo_ver = g("B_TSC_VER"), g("B_TSGO_VER")
xiters, refiters = i("B_XITERS"), i("B_REFITERS")
warmup, warmiters = i("B_WARMUP"), i("B_WARMITERS")

# mode xtsc_wall xtsc_self xtsc_err tsc_wall tsc_err tsgo_wall tsgo_err
#      nat_wall nat_self nat_err xtsc_warm
# (nat_* are 0 without --xtsc-native; xtsc_warm is 0 under --no-warm)
modes = []
for line in g("B_MODE_DATA").splitlines():
    p = line.split()
    if len(p) == 12:
        modes.append((p[0],) + tuple(int(x) for x in p[1:]))

def sec(ms): return f"{ms/1000:.2f}s" if ms else "n/a"
def tput(ms): return f"{loc*1000//ms:,}" if ms and loc else "n/a"
def ratio(a, b): return f"{a/b:.2f}×" if b else "n/a"

date_h = f"{stamp[0:4]}-{stamp[4:6]}-{stamp[6:8]}"
commit_url = f"https://github.com/{slug}/commit/{rev_full}"
MODE_DOC = {
    "check-only": "every compiler runs with `--noEmit`: type-check only, nothing "
                  "transformed, emitted or written. **This is the mode the perf arc "
                  "profiles** (`ab-interleaved.sh` / `cost_gate.py` both pass `--noEmit`).",
    "emit":       "every compiler type-checks AND emits JavaScript to an `outDir`.",
}

# ---- per-run report ----
lines = [
    f"# Bench run {stamp}",
    "",
    f"- **xtsc revision**: [`{rev}`]({commit_url})",
    f"- **TypeScript pin**: `{ts_commit}` (typeScriptCommit)",
    f"- **Profile**: `{project}` — {files} files, {loc:,} LOC",
    f"- **Runner**: {runner}",
    f"- **Method**: xtsc **warm** = median of {warmiters} in-process rebuilds after "
    f"{warmup} warm-up rebuilds (the headline); xtsc **cold** = median of {xiters} "
    f"single-shot process(es); tsc/tsgo = median of {refiters} processes.",
]
if label:
    lines.append(f"- **Label**: {label}")

for mode, xw, xself, xerr, tw, terr, gw, gerr, nw, nself, nerr, xwarm in modes:
    lines += [
        "",
        f"## {mode}",
        "",
        MODE_DOC.get(mode, ""),
        "",
        "| Compiler | Version | Wall | Throughput | Errors |",
        "|---|---|---:|---:|---:|",
    ]
    if xwarm:
        lines.append(f"| **xtsc (warm JVM)** | {rev} | **{sec(xwarm)}** | {tput(xwarm)} LOC/s | {xerr} |")
    lines += [
        f"| xtsc (cold JVM) | {rev} | {sec(xw)} | {tput(xw)} LOC/s | {xerr} |",
        f"| tsc | {tsc_ver} | {sec(tw)} | {tput(tw)} LOC/s | {terr} |",
        f"| tsgo | {tsgo_ver} | {sec(gw)} | {tput(gw)} LOC/s | {gerr} |",
    ]
    if nw:
        lines.append(f"| xtsc-native | {rev} (AOT) | {sec(nw)} | {tput(nw)} LOC/s | {nerr} |")
    if xwarm:
        # Direction-aware: xtsc passed tsc in the warm regime on 2026-08-10
        # (0.49x check-only), so a fixed "parity is 1.00x" line would describe a
        # milestone already behind us and point the reader at the wrong gap.
        def _vs(name, other):
            if not other:
                return f"n/a vs {name}"
            r = xwarm / other
            return (f"{1/r:.2f}x FASTER than {name}" if r < 1
                    else f"{r:.2f}x {name} — parity at 1.00x")
        lines += [
            "",
            f"**Warm JVM — the target metric: {_vs('tsc', tw)}; {_vs('tsgo', gw)}.**",
        ]
    lines += [
        "",
        f"Cold JVM (one-shot CLI): **{ratio(xw,tw)}** tsc and **{ratio(xw,gw)}** tsgo; "
        f"tsc is **{ratio(tw,gw)}** tsgo. "
        f"xtsc self-reported time (excl. JVM startup/JIT): **{sec(xself)}**.",
    ]
    if nw:
        lines += [
            "",
            f"Ahead-of-time (GraalVM native-image, Oracle + PGO): **{sec(nw)}** — "
            f"**{ratio(nw,tw)}** tsc, **{ratio(nw,gw)}** tsgo, **{ratio(xw,nw)}** the COLD "
            f"JVM arm and **{ratio(nw,xwarm) if xwarm else 'n/a'}** the WARM one. "
            f"Same compiler, same output. The AOT image is expected to TRAIL the warm JVM "
            f"(measured 8.4 s vs 6.5 s on the dev box) — its value is that it needs no "
            f"warm-up at all, so it is the one-shot CLI answer while the warm JVM is the "
            f"daemon answer (docs/perf/aot-native-image.md).",
        ]
lines += [
    "",
    "> Compare a ratio only against a ratio of the SAME mode. A single xtsc run "
    "carries the full spread of one cold JVM (the per-run ratio has ranged 1.9–2.7 "
    "on an unchanged compiler), so read the median across runs, not one row.",
    "",
    "> Errors are all env-legit offline `@types/node` artifacts (no `node_modules/@types` "
    "in CI); real tsc reports 0 on its own source. Counts differ slightly by how each "
    "compiler models the missing ambient env, not by real diagnostics.",
    "",
]
run_name = f"{stamp}-{rev}.md"
with open(os.path.join(runs_dir, run_name), "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(lines))

# ---- index (prepend newest rows, one per mode) ----
# Rows live between explicit markers so the pre-739 single-mode archive below
# them is never rewritten (its columns differ — see the README's archive note).
index = os.path.join(out_dir, "README.md")
# V2 MARKERS, 2026-08-10 — the series RESTARTS here (owner: "it does not matter
# that much, we can restart it now"). The old table is left in place below,
# untouched, under its own markers: its primary xtsc column is a COLD single-shot
# and this one's is a WARM rebuild, so the two are not the same measurement and
# must never share a column. Mixing them would show a ~4x "improvement" on the
# day the harness changed.
START, END = "<!-- BENCH-ROWS-V2-START -->", "<!-- BENCH-ROWS-V2-END -->"
col = ("| Date | xtsc rev | Mode | xtsc warm | warm/tsc | warm/tsgo | tsc | tsgo "
       "| xtsc cold | xtsc-nat | nat/tsc | err | Run |\n"
       "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n")
new_rows = "".join(
    f"| {date_h} | [`{rev}`]({commit_url}) | {mode} | {sec(xwarm) if xwarm else '—'} "
    f"| {ratio(xwarm,tw) if xwarm else '—'} | {ratio(xwarm,gw) if xwarm else '—'} "
    f"| {sec(tw)} | {sec(gw)} | {sec(xw)} "
    f"| {sec(nw) if nw else '—'} | {ratio(nw,tw) if nw else '—'} | {xerr} "
    f"| [report](runs/{run_name}) |\n"
    for mode, xw, xself, xerr, tw, terr, gw, gerr, nw, nself, nerr, xwarm in modes)

txt = open(index, encoding="utf-8").read() if os.path.exists(index) else ""
if START in txt and END in txt:
    head, rest = txt.split(START, 1)
    body, tail = rest.split(END, 1)
    body_rows = "".join(l + "\n" for l in body.splitlines()
                        if l.startswith("| 2"))          # data rows only
    txt = head + START + "\n\n" + col + new_rows + body_rows + "\n" + END + tail
else:
    # Bootstrap of the V2 block. Whatever is already in the file — including the
    # entire V1 table with its own markers — is kept BELOW as the archive, so no
    # historical row is rewritten or lost; only the primary table changes meaning.
    header = "" if txt.lstrip().startswith("# ") else "# Bench history\n\n"
    banner = ("\n> **Series restarted 2026-08-10.** The primary `xtsc` column is now a "
              "**warm** in-process rebuild (the JVM is the fastest target and the one the "
              "perf arc optimises; a cold single-shot was ~75% JVM warm-up and barely moved "
              "when the compiler got faster). The AOT arm also switched from GraalVM CE to "
              "**Oracle + PGO**. Rows below the V1 markers are the old cold-primary series "
              "and are NOT comparable to these.\n\n")
    txt = (header + banner + START + "\n\n" + col + new_rows + "\n" + END + "\n\n"
           + "## Archive — V1 series (cold-primary xtsc column)\n\n" + txt)
with open(index, "w", encoding="utf-8", newline="\n") as f:
    f.write(txt)

print(f"wrote {os.path.join('runs', run_name)} and updated index")
PYEOF

echo
echo "=== 3-way bench ($PROJECT @ $TS_COMMIT8) ==="
for row in "${MODE_DATA[@]}"; do
    read -r m xw xs xe tw te gw ge nw ns ne xwarm <<<"$row"
    echo "  -- $m --"
    if [[ "$xwarm" != 0 ]]; then
        printf '    %-6s %8s ms warm rebuild  <-- headline\n' xtsc-w "$xwarm"
    fi
    printf '    %-6s %8s ms wall  %6s errors\n' xtsc "$xw" "$xe"
    printf '    %-6s %8s ms wall  %6s errors  (%s)\n' tsc  "$tw" "$te" "$TSC_VER"
    printf '    %-6s %8s ms wall  %6s errors  (%s)\n' tsgo "$gw" "$ge" "$TSGO_VER"
    if [[ "$nw" != 0 ]]; then
        printf '    %-6s %8s ms wall  %6s errors  (AOT)\n' xtsc-n "$nw" "$ne"
    fi
done
