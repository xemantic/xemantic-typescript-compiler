#!/usr/bin/env python3
"""(TSGO.2) the hand-written pin census: -core's `diagnose(...)` pins under both engines, adjudicated by tsgo.

    scripts/tsgo-pin-census.py --core RESULTS_A --tsgo RESULTS_B [--dump build/goport/pin-dump]
                               [--out build/goport/pin-census] [--workers 4]

RESULTS_A / RESULTS_B are copies of xemantic-typescript-compiler-core/build/test-results/jvmTest after a
run with the default engine and after a run with `XTSC_ENGINE=tsgo XTSC_PIN_DUMP=<dump>` (docs/goport-pin-census.md).
The dump holds every text the tsgo engine compiled as a conformance case (`compiler/<sha>/<fileName>`), the
port's diagnostics beside it (`.port.jsonl`, the diag oracle's format without `phase`; `.port.variation`;
`.port.error` when the port threw) and `calls.tsv` (test -> case). This script runs the SAME texts through
tsgo itself (build/goport/bin/tsgo-oracle `materialize` + `diags`, docs/goport-diag-oracle.md) and compares.

Every test that fails only under tsgo lands in one bucket:

  port-defect      some case it compiled: the port's diagnostics differ from tsgo's (or the port threw where
                   tsgo compiled) -- a porting bug (or a gap in the engine's case handling) to fix
  harness-gap      some case tsgo's harness itself does not compile as-is (a harness skip, e.g. a removed
                   option, or a fatal such as an unknown directive), so tsgo's answer for the pin is not
                   defined by the harness
  core-divergence  every case it compiled: port == tsgo, so the pin asserts -core's behaviour where it differs
                   from tsgo (or a gap in the Diagnostic mapping; see the doc)
  no-cases         the test compiled nothing through diagnose (it fails for another reason under the switch)

Exit 0; writes OUT/census.json (summary + per-test rows) and OUT/cases.json (per-case verdicts).
"""

import argparse
import collections
import concurrent.futures as cf
import glob
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TOOL = os.path.join(REPO, "build/goport/bin/tsgo-oracle")
TS_SUBMODULE = os.path.join(REPO, "build/goport/ts-submodule")


def norm(method):
    """A test method name as both sides spell it: a lambda frame (`name$lambda$0`, `name$render`) is its
    test, and a JVM lambda name spells the backtick name's spaces as underscores."""
    return method.split("$", 1)[0].replace(" ", "_")


def results(d):
    """(simple class, method) -> 'pass' | 'fail' | 'skip' from JUnit XMLs."""
    out = {}
    for p in glob.glob(os.path.join(d, "*.xml")):
        r = ET.parse(p).getroot()
        for tc in r.findall("testcase"):
            cls = tc.get("classname").rsplit(".", 1)[-1]
            name = tc.get("name")
            if name.endswith("[jvm]"):
                name = name[: -len("[jvm]")]
            st = "pass"
            if tc.find("failure") is not None or tc.find("error") is not None:
                st = "fail"
            elif tc.find("skipped") is not None:
                st = "skip"
            out[(cls, name)] = st
    return out


def failure_message(d, cls, name):
    for p in glob.glob(os.path.join(d, f"TEST-*.{cls}.xml")):
        r = ET.parse(p).getroot()
        for tc in r.findall("testcase"):
            n = tc.get("name")
            if n.endswith("[jvm]"):
                n = n[: -len("[jvm]")]
            if n == name:
                f = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
                return (f.get("message") or "")[:600] if f is not None else ""
    return ""


def load_jsonl(path):
    rows = []
    for l in open(path, encoding="utf-8"):
        if l.strip():
            d = json.loads(l)
            d.pop("phase", None)
            rows.append(d)
    return rows


def run(cmd, log):
    with open(log, "w") as lf:
        return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=lf, env=dict(os.environ, XTSC_TS_SUBMODULE=TS_SUBMODULE)).stdout.decode()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--core", required=True)
    ap.add_argument("--tsgo", required=True)
    ap.add_argument("--dump", default=os.path.join(REPO, "build/goport/pin-dump"))
    ap.add_argument("--out", default=os.path.join(REPO, "build/goport/pin-census"))
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args()
    if not os.access(TOOL, os.X_OK):
        sys.exit(f"tsgo-pin-census: no {TOOL} (xemantic-typescript-compiler-goport/oracle-go/build.sh)")
    os.makedirs(args.out, exist_ok=True)
    work = os.path.join(args.out, ".work")
    os.makedirs(work, exist_ok=True)

    # ---- the cases
    rels = []
    for d in sorted(glob.glob(os.path.join(args.dump, "compiler", "*"))):
        for f in os.listdir(d):
            if ".port." not in f:
                rels.append(os.path.relpath(os.path.join(d, f), args.dump))
    print(f"{len(rels)} dumped cases", file=sys.stderr)

    # ---- materialize (tsgo's harness: configurations, skips) in parallel chunks
    proj_root = os.path.join(work, "proj")
    chunks = [rels[i:: args.workers] for i in range(args.workers)]

    def materialize(i, chunk):
        lst = os.path.join(work, f"m{i}.txt")
        open(lst, "w").write("".join(r + "\n" for r in chunk))
        out = run([TOOL, "materialize", "-cases-root", args.dump, "-out", proj_root, "-list", lst], os.path.join(work, f"m{i}.log"))
        return [json.loads(l) for l in out.splitlines() if l.startswith("{")]

    mat = []
    with cf.ThreadPoolExecutor(args.workers) as ex:
        for rows in ex.map(lambda a: materialize(*a), enumerate(chunks)):
            mat.extend(rows)
    by_case = collections.defaultdict(dict)
    for m in mat:
        by_case[m["case"]][m["variation"]] = m

    # ---- the port's variation per case, then tsgo's diagnostics for exactly that project
    todo, verdict = [], {}
    for rel in rels:
        base = os.path.join(args.dump, rel)
        pv = open(base + ".port.variation").read() if os.path.exists(base + ".port.variation") else None
        port_error = open(base + ".port.error").read() if os.path.exists(base + ".port.error") else None
        ms = by_case.get(rel, {})
        star = ms.get("*")
        if star is not None:  # the whole case is skipped or fatal in tsgo's harness
            verdict[rel] = {"verdict": "harness-gap", "why": f"tsgo harness: {star.get('status')} {star.get('reason', '')}", "portError": port_error}
            continue
        if pv is None:
            # the port threw before it chose a configuration; tsgo materialized something
            verdict[rel] = {"verdict": "port-defect" if any(m["status"] == "ok" for m in ms.values()) else "harness-gap",
                            "why": f"port threw: {port_error}", "portError": port_error}
            continue
        m = ms.get(pv)
        if m is None:
            verdict[rel] = {"verdict": "port-defect", "why": f"tsgo enumerates {sorted(ms)}, the port compiled {pv}", "portError": port_error}
            continue
        if m["status"] != "ok":
            verdict[rel] = {"verdict": "harness-gap", "why": f"tsgo harness: {m['status']} {m.get('reason', '')}", "portError": port_error}
            continue
        todo.append((rel, m["dir"], os.path.join(work, "diags", rel + "." + pv.replace("/", "_") + ".jsonl")))

    def diags(i, items):
        lst = os.path.join(work, f"d{i}.txt")
        st = os.path.join(work, f"d{i}.status.jsonl")
        if os.path.exists(st):
            os.remove(st)
        with open(lst, "w") as f:
            for _, d, o in items:
                os.makedirs(os.path.dirname(o), exist_ok=True)
                f.write(f"{d}\t{o}\n")
        run([TOOL, "diags", "-cases-root", args.dump, "-batch", lst, "-status", st], os.path.join(work, f"d{i}.log"))
        rows = {}
        if os.path.exists(st):
            for l in open(st):
                r = json.loads(l)
                rows[r["dir"]] = r
        return rows

    status = {}
    with cf.ThreadPoolExecutor(args.workers) as ex:
        for rows in ex.map(lambda a: diags(*a), enumerate([todo[i:: args.workers] for i in range(args.workers)])):
            status.update(rows)
    for rel, d, o in todo:
        base = os.path.join(args.dump, rel)
        st = status.get(d, {})
        if st.get("status") != "ok" or not os.path.exists(o):
            verdict[rel] = {"verdict": "harness-gap", "why": f"tsgo diags: {st.get('status')} {st.get('reason', '')}"}
            continue
        if os.path.exists(base + ".port.error"):
            verdict[rel] = {"verdict": "port-defect", "why": "port threw: " + open(base + ".port.error").read()[:300]}
            continue
        want, got = load_jsonl(o), load_jsonl(base + ".port.jsonl")
        if want == got:
            verdict[rel] = {"verdict": "equal"}
        else:
            i = next((k for k in range(min(len(got), len(want))) if got[k] != want[k]), min(len(got), len(want)))
            verdict[rel] = {"verdict": "port-defect", "why": f"diagnostics differ at #{i}",
                            "tsgo": want[i] if i < len(want) else None, "port": got[i] if i < len(got) else None}

    # ---- tests
    calls = collections.defaultdict(set)
    for l in open(os.path.join(args.dump, "calls.tsv"), encoding="utf-8"):
        caller, sha, name = l.rstrip("\n").split("\t")
        cls, _, method = caller.partition(".")
        calls[(cls, norm(method))].add(f"compiler/{sha}/{name}")
    a, b = results(args.core), results(args.tsgo)
    rank = {"port-defect": 0, "harness-gap": 1, "equal": 2}
    rows, summary = [], collections.Counter()
    for key in sorted(set(a) | set(b)):
        sa, sb = a.get(key, "missing"), b.get(key, "missing")
        cases = sorted(calls.get((key[0], norm(key[1])), ()))
        if sa == "pass" and sb == "pass":
            summary["pass both"] += 1
            summary["pass both (diagnose pins)" if cases else "pass both (no diagnose)"] += 1
            continue
        if sa == "skip" or sb == "skip":
            summary["skipped"] += 1
            continue
        if sa == "pass" and sb == "fail":
            if not cases:
                bucket = "no-cases"
            else:
                worst = min((verdict.get(c, {"verdict": "harness-gap"})["verdict"] for c in cases), key=lambda v: rank[v])
                bucket = {"port-defect": "port-defect", "harness-gap": "harness-gap", "equal": "core-divergence"}[worst]
            summary["fail tsgo only: " + bucket] += 1
            rows.append({"class": key[0], "test": key[1], "bucket": bucket, "cases": cases,
                         "failure": failure_message(args.tsgo, *key),
                         "caseVerdicts": {c: verdict.get(c) for c in cases if verdict.get(c, {}).get("verdict") != "equal"}})
        elif sa == "fail" and sb == "pass":
            summary["fail core only"] += 1
        elif sa == "fail" and sb == "fail":
            summary["fail both"] += 1
        else:
            summary[f"other {sa}/{sb}"] += 1
    # ---- core-divergence, refined: -core's own answer (the default-engine dump) against the port's mapped one
    def mapped(rel):
        sha, name = rel.split("/")[1], rel.split("/")[2]
        c = os.path.join(args.dump, "core", sha + ".json")
        t = os.path.join(args.dump, rel + ".port.mapped.json")
        if not (os.path.exists(c) and os.path.exists(t)):
            return None, None
        return json.load(open(c)), json.load(open(t))

    def key(d, fields):
        return tuple(json.dumps(d.get(f), sort_keys=True) for f in fields)

    def diff_kind(core, tsgo):
        """The most substantive way two answers differ (DIFF_ORDER), or 'same'."""
        if core == tsgo:
            return "same"
        if sorted(map(json.dumps, core)) == sorted(map(json.dumps, tsgo)):
            return "order"
        rows = ("code", "start", "length")
        ck, tk = collections.Counter(key(d, rows) for d in core), collections.Counter(key(d, rows) for d in tsgo)
        if ck != tk:
            if not (ck - tk):
                return "tsgo reports more"
            if not (tk - ck):
                return "core reports more"
            return "different rows"
        for f, kind in (("message", "message text"), ("category", "category"), ("fileName", "file name"),
                        ("line", "line/character"), ("character", "line/character"), ("messageChain", "message chain"),
                        ("related", "related information")):
            a = collections.Counter(key(d, rows + (f,)) for d in core)
            b = collections.Counter(key(d, rows + (f,)) for d in tsgo)
            if a != b:
                return kind
        return "order"

    DIFF_ORDER = ["different rows", "core reports more", "tsgo reports more", "message text", "category", "file name",
                  "line/character", "message chain", "related information", "order", "same", "not recorded"]
    sub = collections.Counter()
    for r in rows:
        if r["bucket"] != "core-divergence":
            continue
        kinds = []
        for c in r["cases"]:
            core, tsgo = mapped(c)
            kinds.append("not recorded" if core is None else diff_kind(core, tsgo))
        r["diff"] = min(kinds, key=DIFF_ORDER.index) if kinds else "not recorded"
        sub[r["diff"]] += 1
    summary.update({"core-divergence by diff: " + k: v for k, v in sub.items()})
    case_summary = collections.Counter(v["verdict"] for v in verdict.values())
    out = {"summary": dict(summary), "cases": dict(case_summary), "tests": rows}
    json.dump(out, open(os.path.join(args.out, "census.json"), "w"), indent=1)
    json.dump(verdict, open(os.path.join(args.out, "cases.json"), "w"), indent=1)
    for k, v in sorted(summary.items()):
        print(f"{v:7d}  {k}")
    print("cases: " + ", ".join(f"{k} {v}" for k, v in sorted(case_summary.items())))


if __name__ == "__main__":
    main()
