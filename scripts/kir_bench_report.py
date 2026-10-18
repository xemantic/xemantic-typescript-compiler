#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""(BENCH.3) The KIR bench's table, GENERATED from the runs `scripts/kir-bench.sh --record` commits.

    python3 scripts/kir_bench_report.py                 # regenerate docs/perf/kir-bench.md from bench-history/kir-runs/
    python3 scripts/kir_bench_report.py --self-test     # prove every refusal fires

Every KIR number used to be hand-typed prose, the failure found in the harness this one copies from: a
regeneration zeroed its tables and the prose survived unnoticed. So the table here is written only between
the markers, only from a run's JSON (schema 1, see `kir-bench.sh`), losses included and labelled — and THE
GENERATOR REFUSES ITSELF: a run whose medians are not positive, whose ratio column would disagree with its own
medians, whose rows fall short of arms x libraries, whose sample counts are not its process count, or whose
equivalence gate did not agree, is not printed at all (exit 2, the document untouched).
"""
import glob
import json
import os
import statistics
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUNS = os.path.join(REPO, "bench-history", "kir-runs")
DOC = os.path.join(REPO, "docs", "perf", "kir-bench.md")
START, END = "<!-- KIR-BENCH-TABLE-START -->", "<!-- KIR-BENCH-TABLE-END -->"

# Perry's peer classification, adopted for the label column: who is a peer and who is not.
ARMS = {
    "tsgo": ("tsgo 7.0.2 -> JS -> node", "runtime peer (baseline)"),
    "tbun": ("tsgo 7.0.2 -> JS -> bun", "runtime peer"),
    "xtsc": ("xtsc -> JS -> node", "control: our front end, same runtime"),
    "xbun": ("xtsc -> JS -> bun", "control: our front end, same runtime"),
    "kir": ("xtsc -> JVM bytecode -> java", "ours"),
    "nat": ("xtsc -> Kotlin/Native", "ours"),
    "wasi": ("xtsc -> Wasm -> wasmtime", "ours"),
    "wjs": ("xtsc -> Wasm -> node", "ours"),
}


class Refused(Exception):
    pass


def ratio_text(arm, median, base):
    if arm == "tsgo":
        return "baseline"
    rel = median / base
    return f"{1 / rel:.2f}x faster" if rel < 1 else f"{rel:.2f}x slower"


def check(run):
    """Every reason [run] must not be printed. Empty means printable."""
    reasons = []
    if run.get("schema") != 1:
        reasons.append(f"schema {run.get('schema')!r}, expected 1")
        return reasons
    arms, libs, processes = run["arms"], run["libs"], run["processes"]
    rows = run["rows"]
    if len(rows) < len(arms) * len(libs):
        reasons.append(f"{len(rows)} rows for {len(arms)} arms x {len(libs)} libraries")
    for lib in libs:
        verdict = run["equivalence"].get(lib, {}).get("verdict")
        if verdict != "agree":
            reasons.append(f"{lib}: equivalence verdict {verdict!r}, not 'agree'")
    by_key = {(r["lib"], r["arm"]): r for r in rows}
    for lib in libs:
        base_row = by_key.get((lib, "tsgo"))
        for arm in arms:
            r = by_key.get((lib, arm))
            if r is None:
                reasons.append(f"{lib}/{arm}: no row")
                continue
            if len(r["samples"]) != processes:
                reasons.append(f"{lib}/{arm}: {len(r['samples'])} samples, {processes} processes")
            if r["median"] <= 0 or any(s <= 0 for s in r["samples"]):
                reasons.append(f"{lib}/{arm}: a non-positive time ({r['median']} median)")
                continue
            if r["samples"] and statistics.median(r["samples"]) != r["median"]:
                reasons.append(f"{lib}/{arm}: median {r['median']} is not the median of its samples")
            # The ratio column is DERIVED here, and re-read against the medians it came from: a column
            # that says "faster" over a slower median is the self-contradiction this exists to refuse.
            if base_row and base_row["median"] > 0 and "ratio" in r:
                expected = ratio_text(arm, r["median"], base_row["median"])
                if r["ratio"] != expected:
                    reasons.append(f"{lib}/{arm}: ratio {r['ratio']!r} disagrees with its medians ({expected!r})")
    return reasons


def render(run):
    reasons = check(run)
    if reasons:
        raise Refused("; ".join(reasons))
    by_key = {(r["lib"], r["arm"]): r for r in run["rows"]}
    box = run["box"]
    lines = [
        f"Run `{os.path.basename(run['_path'])}` — commit `{run['commit'][:12]}`"
        + (" (dirty tree)" if run.get("dirty") else "")
        + f", {run['utc']}, {run['processes']} processes per arm, interleaved.",
        f"Box: {box['cpu']}, {box['cores']} cores, {box['mem_gb']} GB; java {box['java']}, node {box['node']}.",
        # (BENCH.2) A placement change is a recipe change: runs with different placements are not comparable.
        "Placement: " + ", ".join(f"{arm} {where}" for arm, where in
                                  run.get("placement", {a: "os scheduler (not recorded)" for a in run["arms"]}).items())
        + ".",
        "",
    ]
    for lib in run["libs"]:
        unit = run["units"][lib]
        base = by_key[(lib, "tsgo")]["median"]
        sinks = run["equivalence"][lib]["sinks"]
        lines += [
            f"**{lib}** — {unit['ops']:,} ops/round; every arm printed `sink={next(iter(sinks.values()))}`.",
            "",
            "| arm | class | median | per op | spread (min..max) | vs tsgo |",
            "|---|---|---:|---:|---:|---|",
        ]
        for arm in run["arms"]:
            r = by_key[(lib, arm)]
            label, peer = ARMS.get(arm, (arm, "?"))
            per_op = r["median"] * unit["scale"] / unit["ops"]
            lines.append(
                f"| {label} | {peer} | {r['median']} ms | {per_op:.2f} {unit['name']} | "
                f"{min(r['samples'])}..{max(r['samples'])} | {ratio_text(arm, r['median'], base)} |"
            )
        lines.append("")
    return "\n".join(lines).rstrip("\n")


def load_latest():
    paths = sorted(glob.glob(os.path.join(RUNS, "*.json")))
    if not paths:
        raise Refused(f"no runs under {RUNS}")
    run = json.load(open(paths[-1]))
    run["_path"] = paths[-1]
    return run


def write_doc(table):
    text = open(DOC).read()
    if text.count(START) != 1 or text.count(END) != 1:
        raise Refused(f"{DOC} must hold each marker exactly once")
    head, rest = text.split(START)
    _, tail = rest.split(END)
    open(DOC, "w").write(head + START + "\n" + table + "\n" + END + tail)


def self_test():
    def run(**over):
        rows = []
        for lib in ("mitt", "toml"):
            for arm, med in (("tsgo", 300), ("xtsc", 290), ("kir", 400)):
                rows.append({"lib": lib, "arm": arm, "samples": [med - 5, med, med + 5], "median": med})
        r = {"schema": 1, "commit": "0" * 40, "utc": "x", "processes": 3, "arms": ["tsgo", "xtsc", "kir"],
             "libs": ["mitt", "toml"], "rows": rows, "_path": "self-test.json",
             "equivalence": {lib: {"verdict": "agree", "sinks": {"tsgo": "1"}} for lib in ("mitt", "toml")},
             "units": {lib: {"ops": 1000, "scale": 1e6, "name": "ns/op"} for lib in ("mitt", "toml")},
             "box": {"cpu": "c", "cores": 1, "mem_gb": 1, "java": "j", "node": "n"}}
        r.update(over)
        return r

    good = run()
    assert check(good) == [], check(good)
    table = render(good)
    assert "| xtsc -> JVM bytecode -> java | ours | 400 ms | 400000.00 ns/op | 395..405 | 1.33x slower |" in table, table
    assert table.count("| baseline |") == 2, table

    def refused(r, needle):
        reasons = check(r)
        assert any(needle in x for x in reasons), (needle, reasons)
        try:
            render(r)
        except Refused:
            return
        raise AssertionError(f"rendered despite: {needle}")

    zeroed = run()
    for r in zeroed["rows"]:
        r["samples"] = [0, 0, 0]
        r["median"] = 0
    refused(zeroed, "non-positive")
    contradicted = run()
    contradicted["rows"][2]["ratio"] = "1.33x faster"  # kir is SLOWER than tsgo here
    refused(contradicted, "disagrees with its medians")
    short = run()
    short["rows"] = short["rows"][:-1]
    refused(short, "rows for 3 arms")
    thin = run()
    thin["rows"][0]["samples"] = [300]
    refused(thin, "1 samples, 3 processes")
    split = run()
    split["equivalence"]["toml"]["verdict"] = "disagree"
    refused(split, "equivalence verdict")
    print("kir_bench_report: self-test OK (5 refusals fire, the good run renders)")


def main(argv):
    if argv[1:] == ["--self-test"]:
        self_test()
        return 0
    try:
        write_doc(render(load_latest()))
    except Refused as e:
        print(f"kir_bench_report: REFUSED — {e}", file=sys.stderr)
        return 2
    print(f"kir_bench_report: wrote {os.path.relpath(DOC, REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
