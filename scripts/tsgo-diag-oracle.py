#!/usr/bin/env python3
"""Driver for the (TSGO.2) diagnostics oracle (docs/goport-diag-oracle.md).

For every materialized configuration (scripts/tsgo-diag-cases.py), compiles it in-process with
tsgo's own harness code (`build/goport/bin/tsgo-oracle diags`) and writes the normalized
diagnostics:

    build/goport/diag-oracle/<case>/<variation>.jsonl     one JSON object per diagnostic
    build/goport/diag-oracle/manifest.json

With --runner (default ON) every configuration is ALSO compiled through the unmodified runner path
(pre-emit program + emitting program + the TS-1 count check), rendered exactly as tsgo's
`.errors.txt`, and compared byte-for-byte with tsgo's committed baseline under
typescript-go-repo/testdata/baselines/reference/submodule/<suite>/ (a submodule case) or
reference/<suite>/ (a `local/` case, tsgo's own testdata) — the evidence that the oracle IS tsgo's
harness. The layer of that baseline is recorded per configuration: `local` for tsgo's own cases, and
for a submodule case the directory its `.errors.txt.diff` lives in (submodule / submoduleAccepted /
submoduleTriaged, docs/tsgo-baselines.md) or none.

Caching: an entry is reused when sha256(case.json) and the tool's sha256 are unchanged and its
output file still hashes to the recorded value. --force ignores the cache.

Exit status is non-zero (manifest "complete": false) when ANY configuration is not `ok`, a worker
died, a status line is missing, or the determinism probe (a sample re-run in a fresh process, in
reverse order) disagrees. Consumers must refuse an incomplete manifest.
"""

import argparse
import collections
import concurrent.futures as cf
import hashlib
import json
import os
import subprocess
import sys
import time

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
CASES_ROOT = os.path.join(REPO, "build/goport/diag-src")  # made by scripts/tsgo-diag-cases.py
CASES_DIR = os.path.join(REPO, "build/goport/diag-cases")
OUT = os.path.join(REPO, "build/goport/diag-oracle")
TOOL = os.path.join(REPO, "build/goport/bin/tsgo-oracle")
TS_SUBMODULE = os.path.join(REPO, "build/goport/ts-submodule")
BASELINES = os.path.join(REPO, "typescript-go-repo/testdata/baselines/reference")
ORACLE_VERSION = 1  # bump when the JSONL format changes


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def die(msg, code=2):
    print(f"tsgo-diag-oracle: REFUSED: {msg}", file=sys.stderr)
    sys.exit(code)


def diff_layer(case, suite, configured):
    if case.startswith("local/"):
        return "local"
    name = configured
    for ext in (".tsx", ".ts"):
        if name.endswith(ext):
            name = name[: -len(ext)]
            break
    for layer in ("submoduleTriaged", "submoduleAccepted", "submodule"):
        if os.path.exists(os.path.join(BASELINES, layer, suite, name + ".errors.txt.diff")):
            return layer
    return None


def run_batch(wid, items, work, runner, label):
    lst = os.path.join(work, f"{label}{wid}.txt")
    st = os.path.join(work, f"{label}{wid}.status.jsonl")
    log = os.path.join(work, f"{label}{wid}.log")
    with open(lst, "w") as f:
        for it in items:
            f.write(f"{it['projectDir']}\t{it['outPath']}\n")
    cmd = [TOOL, "diags", "-cases-root", CASES_ROOT, "-batch", lst, "-status", st]
    if runner:
        cmd += ["-runner", "-baselines", BASELINES]
    env = dict(os.environ, XTSC_TS_SUBMODULE=TS_SUBMODULE)
    if os.path.exists(st):
        os.remove(st)
    with open(log, "w") as lf:
        rc = subprocess.run(cmd, stdout=lf, stderr=lf, env=env).returncode
    rows = {}
    if os.path.exists(st):
        for l in open(st):
            r = json.loads(l)
            rows[r["dir"]] = r
    return rc, rows, log


def run_all(items, workers, runner, work, label):
    # spread evenly, interleaved so every worker gets a mix of small and large cases
    buckets = [items[i::workers] for i in range(workers)]
    results, failures = {}, []
    with cf.ThreadPoolExecutor(workers) as ex:
        futs = {ex.submit(run_batch, w, b, work, runner, label): w for w, b in enumerate(buckets) if b}
        for fu in cf.as_completed(futs):
            rc, rows, log = fu.result()
            results.update(rows)
            if rc != 0:
                failures.append(f"worker {futs[fu]} exited {rc} (see {os.path.relpath(log, REPO)})")
            print(f"  worker {futs[fu]}: {len(rows)} done, exit {rc}", file=sys.stderr, flush=True)
    return results, failures


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--no-runner", action="store_true", help="skip the runner/baseline cross-check")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--determinism", type=int, default=100)
    args = ap.parse_args()
    runner = not args.no_runner

    man_cases = os.path.join(CASES_DIR, "manifest.json")
    if not os.path.exists(man_cases):
        die("no case manifest; run scripts/tsgo-diag-cases.py first")
    cm = json.load(open(man_cases))
    if not cm.get("complete"):
        die("the case manifest is incomplete; re-run scripts/tsgo-diag-cases.py")
    if not os.access(TOOL, os.X_OK):
        die(f"no {TOOL}")
    tool_sha = sha256(open(TOOL, "rb").read())
    if tool_sha != cm["toolSha256"]:
        die("tsgo-oracle changed since the cases were materialized; re-run scripts/tsgo-diag-cases.py")

    os.makedirs(OUT, exist_ok=True)
    work = os.path.join(OUT, ".work")
    os.makedirs(work, exist_ok=True)
    man_path = os.path.join(OUT, "manifest.json")
    key = sha256(json.dumps([ORACLE_VERSION, tool_sha, runner]).encode())
    old = {}
    if os.path.exists(man_path) and not args.force:
        try:
            m = json.load(open(man_path))
            if m.get("key") == key:
                old = {(e["case"], e["variation"]): e for e in m["entries"]}
        except (ValueError, KeyError):
            old = {}

    entries, todo = [], []
    for rel, ce in cm["cases"].items():
        for c in ce["configs"]:
            if c["status"] != "ok":
                continue
            pdir = os.path.join(CASES_DIR, c["dir"])
            cj_raw = open(os.path.join(pdir, "case.json"), "rb").read()
            cj = json.loads(cj_raw)
            out_rel = os.path.join(rel, c["variation"] + ".jsonl")
            e = {"case": rel, "variation": c["variation"], "out": out_rel, "inCorpus": ce["inCorpus"],
                 "inputSha256": sha256(cj_raw), "configuredName": cj["configuredName"],
                 "layer": diff_layer(rel, cj["suite"], cj["configuredName"]),
                 "projectDir": pdir, "outPath": os.path.join(OUT, out_rel)}
            prev = old.get((rel, c["variation"]))
            if (prev and prev.get("status") == "ok" and prev["inputSha256"] == e["inputSha256"]
                    and os.path.exists(e["outPath"])
                    and sha256(open(e["outPath"], "rb").read()) == prev["outSha256"]):
                entries.append(prev)
                continue
            entries.append(e)
            todo.append(e)

    print(f"tsgo-diag-oracle: {len(entries)} configurations, {len(entries) - len(todo)} cached, {len(todo)} to run "
          f"({args.workers} workers, runner={'on' if runner else 'off'})", file=sys.stderr)
    t0 = time.time()
    failures = []
    if todo:
        results, failures = run_all(todo, args.workers, runner, work, "w")
        for e in todo:
            r = results.get(e["projectDir"])
            if r is None:
                e["status"], e["reason"] = "missing", "no status line (worker died?)"
                continue
            e["status"] = r["status"]
            if r.get("reason"):
                e["reason"] = r["reason"]
            if r["status"] == "ok":
                e["count"], e["outSha256"], e["phaseCounts"] = r["count"], r["outSha256"], r.get("phaseCounts") or {}
                if r.get("runner"):
                    e["runner"] = r["runner"]
    secs = time.time() - t0

    # determinism: fresh process, reverse order, no runner
    det = {"checked": 0, "mismatches": []}
    good = [e for e in entries if e.get("status") == "ok"]
    if args.determinism and good:
        step = max(1, len(good) // args.determinism)
        sample = list(reversed(good[::step][: args.determinism]))
        probe = [dict(e, projectDir=os.path.join(CASES_DIR, e["case"], e["variation"]),
                      outPath=os.path.join(work, "det", e["case"], e["variation"] + ".jsonl")) for e in sample]
        res, _ = run_all(probe, 1, False, work, "det")
        for e in probe:
            det["checked"] += 1
            r = res.get(e["projectDir"])
            if not r or r.get("outSha256") != e["outSha256"]:
                det["mismatches"].append(f"{e['case']} [{e['variation']}]")

    for e in entries:
        e.pop("projectDir", None)
        e.pop("outPath", None)
    bad = [e for e in entries if e.get("status") != "ok"]
    complete = not bad and not failures and not det["mismatches"]

    summary = collections.OrderedDict()
    summary["configurations"] = len(entries)
    summary["ok"] = len(entries) - len(bad)
    summary["inCorpus"] = sum(1 for e in entries if e["inCorpus"])
    summary["withDiagnostics"] = sum(1 for e in entries if e.get("count"))
    summary["diagnostics"] = sum(e.get("count", 0) for e in entries)
    ph = collections.Counter()
    for e in entries:
        ph.update(e.get("phaseCounts") or {})
    summary["phaseCountsBeforeDedup"] = dict(ph)
    summary["layers"] = dict(collections.Counter(e["layer"] or "none" for e in entries))
    summary["suites"] = dict(collections.Counter(("local/" if e["case"].startswith("local/") else "")
                                                 + e["case"].removeprefix("local/").split("/")[0] for e in entries))
    if runner:
        summary["checkOnlyVsRunner"] = dict(collections.Counter((e.get("runner") or {}).get("checkOnlyVsRunner", "n/a") for e in entries))
        summary["baselineMatch"] = dict(collections.Counter((e.get("runner") or {}).get("baselineMatch", "n/a") for e in entries))
    manifest = {"complete": complete, "key": key, "oracleVersion": ORACLE_VERSION, "toolSha256": tool_sha,
                "runner": runner, "seconds": round(secs, 1), "workerFailures": failures, "determinism": det,
                "summary": summary, "entries": sorted(entries, key=lambda e: (e["case"], e["variation"]))}
    tmp = man_path + ".tmp"
    json.dump(manifest, open(tmp, "w"), indent=1)
    os.replace(tmp, man_path)

    print(json.dumps(summary, indent=1))
    print(f"ran {len(todo)} configurations in {secs:.1f} s; determinism {det['checked']} re-run, "
          f"{len(det['mismatches'])} mismatches")
    if runner:
        for e in entries:
            rc = e.get("runner") or {}
            if rc.get("baselineMatch") not in (None, "equal") or rc.get("checkOnlyVsRunner") == "differ":
                print(f"  RUNNER {e['case']} [{e['variation']}]: {rc}", file=sys.stderr)
    if not complete:
        for e in bad[:20]:
            print(f"  {e['status'].upper()} {e['case']} [{e['variation']}]: {e.get('reason')}", file=sys.stderr)
        for f in failures:
            print("  " + f, file=sys.stderr)
        die(f"{len(bad)} configuration(s) not ok, {len(failures)} worker failure(s), "
            f"{len(det['mismatches'])} determinism mismatch(es); manifest marked incomplete", 1)
    print(f"tsgo-diag-oracle: OK, manifest {os.path.relpath(man_path, REPO)}")


if __name__ == "__main__":
    main()
