#!/usr/bin/env python3
"""COST.1 — the deterministic cost gate for anything that touches the checker.

Round 713 added ~72,000 `getTypeOfExpression` calls (+11.5%, roughly 70-200 ms)
for one conformance diagnostic and nothing noticed, because the round gates are
the corpus suite and `--listAll` and NEITHER sees cost. Over 200 rounds that is
exactly how ~118 handler consultations per node accumulate. This script closes
that hole.

It runs the `compiler` profile with `--passTiming`, extracts the DETERMINISTIC
counters (call counts, walk counts, node counts — never wall time, which swings
+/-13% on a loaded box), and diffs them against the committed baseline in
`docs/perf/cost-counters.txt`, failing above a threshold.

That baseline lives under docs/, not bench/: bench/ is gitignored because its
contents are machine-specific TIMINGS, while these counters are machine-INDEPENDENT
and only move when the compiler does — which is exactly what makes them gateable.

Usage:
  scripts/cost_gate.py                     # run the profile and compare
  scripts/cost_gate.py --update            # run and REBASELINE (record the reason)
  scripts/cost_gate.py --from-log FILE     # compare an existing --passTiming log
  scripts/cost_gate.py --from-log FILE --update
  scripts/cost_gate.py --tolerance 5       # per-counter failure threshold, percent

Exit status is 0 when every counter is within tolerance, 1 when any counter is
outside it (or the run/parse failed), so it drops into a round's gate next to
the suite. An increase is not automatically wrong — it is automatically
ACCOUNTED FOR: justify it in the session note and rebaseline in the same commit.

NOTE: never run this while `./gradlew jvmTest` is in flight. It resolves the
classpath through Gradle, and a classpath resolution during a suite run kills
the suite silently, leaving an empty results dir.
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
#  xemantic-typescript-compiler - a conformant TypeScript compiler and type
#  checker that runs on JVM, native, and WebAssembly
#  Copyright (C) 2026 Kazimierz Pogoda / Xemantic
#
#  This program is free software: you can redistribute it and/or modify
#  it under the terms of the GNU Affero General Public License as
#  published by the Free Software Foundation, version 3 of the License.
#
#  This program is distributed in the hope that it will be useful,
#  but WITHOUT ANY WARRANTY; without even the implied warranty of
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#  GNU Affero General Public License for more details.
#
#  You should have received a copy of the GNU Affero General Public
#  License along with this program.  If not, see <https://www.gnu.org/licenses/>.
#
#  As a special exception, this file contains Helper Code covered by the
#  xemantic-typescript-compiler Output Exception; additional permissions
#  are granted as described in the file LICENSE-EXCEPTION.

import argparse
import glob
import os
import re
import subprocess
import sys
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASELINE = os.path.join(REPO, "docs", "perf", "cost-counters.txt")

# The counters, in report order. Each entry is (key, regex, group, default).
# Only counters that are a deterministic function of the INPUT PROGRAM and the
# COMPILER CODE belong here — no wall time, no ms, and no "top N by ms" rows
# (their printed membership is chosen by elapsed time, so it is not reproducible).
# Every counter here was verified bit-identical across two runs of the same
# binary; the one that was not is documented below and deliberately excluded.
COUNTERS = [
    # --- what the compiler actually answered (a cost drop that changes the
    #     answer is not a win; this is the tripwire for that) ---
    ("output.errors", r"^(?:OK — 0 errors|FAILED — (\d+) error\(s\))$", 1, 0),
    ("output.programFiles", r"^files:\s+\d+ root, (\d+) in program$", 1, None),
    # --- front end ---
    # DELIBERATELY ABSENT: the `== node kinds (indexSourceFile census) ==` total.
    # Measured round 717 across two runs of the same binary: 857,350 vs 854,550
    # (-0.33%) while every counter below was bit-identical. `indexSourceFile` runs
    # on the crawl's concurrent parse threads (ProjectCompiler.readAndScanBatch,
    # Dispatchers.Default) and PassTiming.nodeKindHistogram is a plain HashMap, so
    # the census loses increments to a data race and always UNDERCOUNTS. A
    # nondeterministic row in a determinism gate is worse than no row: it teaches
    # people to ignore the gate. `spine.nodes` covers "how many nodes are walked"
    # and IS exact.
    ("preparse.reused", r"pre-parse reuse .*: reused (\d+), parsed fresh (\d+)$", 1, None),
    ("preparse.fresh", r"pre-parse reuse .*: reused (\d+), parsed fresh (\d+)$", 2, None),
    # --- the spine: how many nodes the check walk visits ---
    ("spine.nodes", r"^SPINE attribution: nodes=(\d+) ", 1, None),
    # --- the type system's call volume (the round-713 counter) ---
    ("typeOfExpr.calls", r"^getTypeOfExpression: (\d+) calls, ~(\d+) distinct nodes", 1, None),
    ("typeOfExpr.distinct", r"^getTypeOfExpression: (\d+) calls, ~(\d+) distinct nodes", 2, None),
    ("typeOfExpr.outsideInit", r"^getTypeOfExpression outside init dispatch: (\d+) calls$", 1, None),
    # --- flow narrowing (18% of checker init) ---
    ("narrow.walks", r"^flow-narrowing walks: (\d+) \(outside init dispatch: (\d+)\)", 1, None),
    ("narrow.walksOutsideInit", r"^flow-narrowing walks: (\d+) \(outside init dispatch: (\d+)\)", 2, None),
    ("narrow.memoServed", r"^LIVE walkMemo served \(walks skipped\): (\d+)$", 1, None),
    # --- type-node resolution + the INV.5(c) mapped cache ---
    ("typeNode.cacheable", r"^getTypeFromTypeNode: cacheable (\d+) \(hits (\d+), misses \d+\) vs bypassed (\d+)", 1, None),
    ("typeNode.cacheHits", r"^getTypeFromTypeNode: cacheable (\d+) \(hits (\d+), misses \d+\) vs bypassed (\d+)", 2, None),
    ("typeNode.bypassed", r"^getTypeFromTypeNode: cacheable (\d+) \(hits (\d+), misses \d+\) vs bypassed (\d+)", 3, None),
    ("mapped.keyed", r"INV\.5\(c\) mappedNodeTypes: keyed (\d+) \(hits (\d+) =", 1, None),
    ("mapped.hits", r"INV\.5\(c\) mappedNodeTypes: keyed (\d+) \(hits (\d+) =", 2, None),
    ("ctxFingerprint.builds", r"INV\.5\(c4\) context fingerprint BUILDS: (\d+)$", 1, None),
    # --- name resolution volume (INV.3) ---
    ("globals.lookups", r"^total (\d+): trueGlobal \d+, shared \d+, ownLocal \d+, CONFLATED (\d+), unscoped \d+, miss (\d+)$", 1, None),
    ("globals.conflated", r"^total (\d+): trueGlobal \d+, shared \d+, ownLocal \d+, CONFLATED (\d+), unscoped \d+, miss (\d+)$", 2, None),
    ("globals.misses", r"^total (\d+): trueGlobal \d+, shared \d+, ownLocal \d+, CONFLATED (\d+), unscoped \d+, miss (\d+)$", 3, None),
]


def parse_counters(text):
    """Extract every counter from a --passTiming run log. Missing ones are reported."""
    values, missing = {}, []
    lines = text.splitlines()
    for key, pattern, group, default in COUNTERS:
        rx = re.compile(pattern)
        found = None
        for line in lines:
            m = rx.search(line)
            if m:
                raw = m.group(group)
                found = int(raw) if raw is not None else default
                break
        if found is None:
            missing.append(key)
        else:
            values[key] = found
    return values, missing


def read_baseline(path):
    if not os.path.isfile(path):
        return None, []
    values, header = {}, []
    with open(path) as f:
        for line in f:
            if line.startswith("#"):
                header.append(line.rstrip("\n"))
                continue
            parts = line.split()
            if len(parts) == 2:
                values[parts[0]] = int(parts[1])
    return values, header


def write_baseline(path, values, rev, profile):
    stamp = time.strftime("%Y-%m-%d")
    width = max(len(k) for k, *_ in COUNTERS) + 2
    with open(path, "w") as f:
        f.write("# COST.1 baseline — deterministic checker cost counters.\n")
        f.write("#\n")
        f.write("# Produced by: scripts/cost_gate.py --update  (profile: %s)\n" % profile)
        f.write("# Recorded:    %s at %s\n" % (stamp, rev))
        f.write("#\n")
        f.write("# These are COUNTS, not times: they are identical across runs and boxes,\n")
        f.write("# which is the whole point — a laptop's wall clock swings +/-13% and cannot\n")
        f.write("# see a 70 ms regression. Any change here is a real change in how much work\n")
        f.write("# the compiler does. Rebaselining is allowed and expected; doing it WITHOUT\n")
        f.write("# a justification in the session note is the failure mode this file exists\n")
        f.write("# to prevent.\n")
        for key, *_ in COUNTERS:
            if key in values:
                f.write("%s%d\n" % (key.ljust(width), values[key]))


def resolve_classpath():
    init_script = os.path.join(REPO, "build", "bench", "print-classpath.init.gradle.kts")
    os.makedirs(os.path.dirname(init_script), exist_ok=True)
    with open(init_script, "w") as f:
        f.write(
            "// Generated by scripts/cost_gate.py — do not edit.\n"
            "allprojects {\n"
            '    tasks.register("xtscPrintJvmRuntimeClasspath") {\n'
            "        doLast {\n"
            '            val cp = configurations.getByName("jvmRuntimeClasspath")\n'
            '                .resolve().joinToString(":") { it.absolutePath }\n'
            '            println("XTSC_CLASSPATH=$cp")\n'
            "        }\n"
            "    }\n"
            "}\n"
        )
    # Streamed, not captured: a cold `compileKotlinJvm` on this codebase runs for
    # minutes, and a silent subprocess is indistinguishable from a hang.
    sys.stderr.write("resolving classpath (compileKotlinJvm) ...\n")
    proc = subprocess.Popen(
        [os.path.join(REPO, "gradlew"), "--console=plain", "-I", init_script,
         "compileKotlinJvm", "xtscPrintJvmRuntimeClasspath"],
        cwd=REPO, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    classpath = None
    for line in proc.stdout:
        if line.startswith("XTSC_CLASSPATH="):
            classpath = line.rstrip("\n")[15:]
        else:
            sys.stderr.write(line)
    if proc.wait() != 0:
        sys.exit("error: gradle compileKotlinJvm failed")
    if classpath is None:
        sys.exit("error: could not resolve jvmRuntimeClasspath")
    return os.path.join(REPO, "build", "classes", "kotlin", "jvm", "main") + ":" + classpath


def bench_project(profile):
    # Same naming rule as scripts/bench-compile-tsc.sh `proj_dir_for`: the
    # `compiler` profile is the historically-named `tsc-project-<sha>`, every
    # other profile is `tsc-<profile>-<sha>`. Newest by mtime, so a re-pin of
    # the TypeScript commit does not leave the gate reading the old extraction.
    stem = "tsc-project-" if profile == "compiler" else "tsc-%s-" % profile
    candidates = [d for d in glob.glob(os.path.join(REPO, "build", "bench", stem + "*"))
                  if os.path.isdir(d)]
    if not candidates:
        sys.exit(
            "error: no benchmark project for profile '%s'. Create one first:\n"
            "  scripts/bench-compile-tsc.sh --project %s --no-emit --no-log" % (profile, profile)
        )
    return max(candidates, key=os.path.getmtime)


def warn_on_low_memory():
    """A -Xmx4g run alongside the Gradle + Kotlin daemons swaps on a small box,
    which looks exactly like a code performance bug. Say so before it happens."""
    try:
        with open("/proc/meminfo") as f:
            avail = next(int(l.split()[1]) for l in f if l.startswith("MemAvailable:"))
    except Exception:
        return
    if avail < 5 * 1024 * 1024:
        sys.stderr.write(
            "warning: only %.1f GB available — a 4g run alongside the Gradle and Kotlin\n"
            "         daemons can swap and stall. Free them with a GRACEFUL stop:\n"
            "           ./gradlew --stop && pkill -f 'KotlinCompile[D]aemon'\n"
            "         (no -9: killing the Kotlin daemon hard forces the next build to be a\n"
            "          COLD compile, which does not fit the inherited -Xmx2g and presents\n"
            "          as a hang — see CLAUDE.md; gradle.properties now sets 5g.)\n"
            % (avail / 1024.0 / 1024.0)
        )


def run_profile(profile, heap):
    classpath = resolve_classpath()
    project = bench_project(profile)
    warn_on_low_memory()
    log_dir = os.path.join(REPO, "build", "bench")
    os.makedirs(log_dir, exist_ok=True)
    log = os.path.join(log_dir, "cost-gate-%s.log" % profile)
    sys.stderr.write("running %s with --passTiming (log: %s) ...\n" % (profile, log))
    with open(log, "w") as f:
        rc = subprocess.run(
            ["java", "-Xmx%s" % heap, "-cp", classpath,
             "com.xemantic.typescript.compiler.MainKt", "--noEmit", "--passTiming", project],
            cwd=REPO, stdout=f, stderr=subprocess.STDOUT,
        ).returncode
    text = open(log).read()
    if rc != 0:
        sys.stderr.write(text[-2000:])
        sys.exit("error: compiler run failed (exit %d) — see %s" % (rc, log))
    return text


def git_rev():
    try:
        rev = subprocess.check_output(
            ["git", "rev-parse", "--short=12", "HEAD"], cwd=REPO, text=True).strip()
        dirty = subprocess.run(["git", "diff", "--quiet"], cwd=REPO).returncode != 0
        return rev + ("+dirty" if dirty else "")
    except Exception:
        return "unknown"


def main():
    ap = argparse.ArgumentParser(description="COST.1 deterministic cost gate")
    ap.add_argument("--profile", default="compiler", help="bench profile (default: compiler)")
    ap.add_argument("--from-log", help="parse this existing --passTiming log instead of running")
    ap.add_argument("--update", action="store_true", help="rewrite the baseline from this run")
    ap.add_argument("--tolerance", type=float, default=2.0, help="per-counter failure threshold, %%")
    ap.add_argument("--heap", default="4g")
    args = ap.parse_args()

    text = open(args.from_log).read() if args.from_log else run_profile(args.profile, args.heap)
    values, missing = parse_counters(text)
    if missing:
        sys.exit(
            "error: %d counter(s) absent from the run log: %s\n"
            "The --passTiming report format changed; update COUNTERS in this script "
            "so the gate keeps seeing them (a silently-dropped counter is a blind spot)."
            % (len(missing), ", ".join(missing))
        )

    base, _ = read_baseline(BASELINE)
    if base is None:
        if not args.update:
            sys.stderr.write(
                "no baseline at %s — recording one now (this is the first run).\n" % BASELINE)
        write_baseline(BASELINE, values, git_rev(), args.profile)
        for key, *_ in COUNTERS:
            print("  %-26s %12d" % (key, values[key]))
        print("\nbaseline written to %s" % os.path.relpath(BASELINE, REPO))
        return 0

    width = max(len(k) for k, *_ in COUNTERS)
    regressions, improvements, moved = [], [], False
    print("%-*s %14s %14s %10s" % (width, "counter", "baseline", "now", "delta"))
    for key, *_ in COUNTERS:
        now = values[key]
        was = base.get(key)
        if was is None:
            print("%-*s %14s %14d %10s" % (width, key, "(new)", now, "-"))
            moved = True
            continue
        delta = now - was
        pct = (delta * 100.0 / was) if was else (0.0 if delta == 0 else 100.0)
        mark = ""
        if delta:
            moved = True
            if abs(pct) > args.tolerance:
                mark = "  <== OVER TOLERANCE"
                (regressions if delta > 0 else improvements).append((key, was, now, pct))
        print("%-*s %14d %14d %+9.2f%%%s" % (width, key, was, now, pct, mark))

    print()
    if not moved:
        print("every counter unchanged — no cost movement.")
    if improvements:
        print("%d counter(s) FELL beyond +/-%.1f%% — a real reduction, rebaseline it:"
              % (len(improvements), args.tolerance))
        for key, was, now, pct in improvements:
            print("  %-26s %d -> %d (%+.2f%%)" % (key, was, now, pct))
    if regressions:
        print("%d counter(s) ROSE beyond +/-%.1f%%:" % (len(regressions), args.tolerance))
        for key, was, now, pct in regressions:
            print("  %-26s %d -> %d (%+.2f%%)" % (key, was, now, pct))
        print(
            "\nCOST GATE FAILED. This is not a veto — it is an accounting demand.\n"
            "Either remove the added work, or justify the increase in the session note\n"
            "and rebaseline in the SAME commit:  scripts/cost_gate.py --update")

    if args.update:
        write_baseline(BASELINE, values, git_rev(), args.profile)
        print("\nbaseline updated (%s)." % os.path.relpath(BASELINE, REPO))
        return 0
    return 1 if regressions else 0


if __name__ == "__main__":
    sys.exit(main())
