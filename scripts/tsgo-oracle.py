#!/usr/bin/env python3
"""tsgo encoded-AST oracle for the (TSGO.1) gate (docs/goport-oracle.md).

For every input file, asks the shipped tsgo binary (`tools/tsgo-7.0.2/lib/tsc --api`, MessagePack
stdio protocol) for `getSourceFile` and stores the raw `encoder.EncodeSourceFile` bytes:

    build/goport/oracle/<source>/<path relative to the source root>.bin
    build/goport/oracle/manifest.json

The bytes are a function of (decoded text, absolute fileName, path, ScriptKind from the extension,
ExternalModuleIndicatorOptions {JSX, Force}) and include the BINDER's node flags (tsgo's project
system binds every program file before any request is served). The two option bits are stored in
the header (bytes 20-23) and copied into the manifest, so a re-implementation can reproduce them.

Usage:
    scripts/tsgo-oracle.py                      # the default corpus (see SOURCES)
    scripts/tsgo-oracle.py --inputs list.txt    # lines "path" or "source<TAB>root<TAB>path"
    scripts/tsgo-oracle.py --list-sources       # print what the default corpus resolves to
Options: --workers N (default 4), --batch N (default 400), --force (ignore the cache),
         --determinism N (re-fetch N files in a fresh process with a different batch layout).

Exit status is non-zero, with a message, whenever ANY input failed, a worker died, or the
determinism probe disagreed. The manifest is still written then, with "complete": false and
per-file "error" fields, so the failures can be inspected; consumers must refuse it.
"""

import argparse
import concurrent.futures as cf
import hashlib
import json
import os
import struct
import subprocess
import sys
import threading
import time

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TSC = os.path.join(REPO, "tools/tsgo-7.0.2/lib/tsc")
TSGO_TAG = "typescript/v7.0.2"
OUT_DIR = os.path.join(REPO, "build/goport/oracle")
WORK_DIR = os.path.join(REPO, "build/goport/oracle-work")
EXTS = (".ts", ".tsx", ".js", ".jsx", ".mts", ".cts", ".mjs", ".cjs")
SKIP_DIRS = {"node_modules", ".git"}
# The project configuration every batch is opened with. Only `jsx`/`moduleDetection`/`module`
# (+ package.json "type" scopes) can influence the bytes, through the two header bits; the rest
# only keeps the program to exactly the listed files and cheap to build.
COMPILER_OPTIONS = {"allowJs": True, "noResolve": True, "noLib": True, "types": [], "skipLibCheck": True}
CONFIG_VERSION = 1  # bump when COMPILER_OPTIONS or the request sequence changes

# (label, root relative to REPO, sub-directories to scan; None = the root itself)
SOURCES = [
    ("conformance", "typescript-repo/tests/cases", None),
    ("tsc", "build/bench/tsc-project-637d5746/src", None),
    ("cronstrue", "build/scratch-p18171/libs/cronstrue", ["src"]),
    ("marked", "build/scratch-p18171/libs/marked", ["src", "test", "bin", "docs"]),
    ("type-fest", "build/scratch-p18265-census/type-fest", ["source", "test-d", "lint-rules", "index.d.ts"]),
    ("hono", "build/scratch-p18265-census/hono", ["src"]),
    ("rxjs", "build/scratch-p18208-census/lib/rxjs", ["src"]),
]


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def die(msg):
    print(f"tsgo-oracle: REFUSED: {msg}", file=sys.stderr)
    sys.exit(2)


# ------------------------------------------------------------------ corpus


def scan(root, subs):
    found = []
    targets = [root] if subs is None else [os.path.join(root, s) for s in subs]
    for t in targets:
        if os.path.isfile(t):
            if t.endswith(EXTS):
                found.append(t)
            continue
        if not os.path.isdir(t):
            continue
        for dp, dns, fns in os.walk(t):
            dns[:] = sorted(d for d in dns if d not in SKIP_DIRS)
            for fn in sorted(fns):
                if fn.endswith(EXTS):
                    found.append(os.path.join(dp, fn))
    return found


def default_inputs():
    items, missing = [], []
    for label, rel, subs in SOURCES:
        root = os.path.join(REPO, rel)
        if not os.path.isdir(root):
            missing.append(f"{label} ({rel})")
            continue
        for p in scan(root, subs):
            items.append((label, root, p))
    return items, missing


def read_inputs(path):
    items = []
    with open(path) as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) == 3:
                items.append((parts[0], os.path.abspath(parts[1]), os.path.abspath(parts[2])))
            else:
                p = os.path.abspath(parts[0])
                items.append(("inputs", os.path.dirname(p), p))
    return items


def sanitize(rel):
    return "".join(c if (c.isalnum() or c in "._-/@+") else "_" for c in rel)


# ------------------------------------------------------------------ protocol


class TsgoApi:
    """One `tsc --api` process speaking the MessagePack tuple protocol (internal/api/protocol_msgpack.go):
    request = [1, bin method, bin JSON params]; response = [4, bin method, bin payload] (getSourceFile:
    the raw encoded bytes) or [5, bin method, bin error message]."""

    def __init__(self, cwd):
        self.p = subprocess.Popen([TSC, "--api", "-cwd", cwd], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE)
        self.stderr = []
        threading.Thread(target=self._drain, daemon=True).start()

    def _drain(self):
        for line in self.p.stderr:
            self.stderr.append(line.decode(errors="replace"))

    @staticmethod
    def _bin(b):
        n = len(b)
        if n < 256:
            return bytes([0xC4, n]) + b
        if n < 65536:
            return b"\xc5" + struct.pack(">H", n) + b
        return b"\xc6" + struct.pack(">I", n) + b

    def _read(self, n):
        buf = self.p.stdout.read(n)
        if buf is None or len(buf) != n:
            raise RuntimeError("tsc --api closed its output: " + "".join(self.stderr[-5:]))
        return buf

    def _rbin(self):
        t = self._read(1)[0]
        if t == 0xC4:
            n = self._read(1)[0]
        elif t == 0xC5:
            n = struct.unpack(">H", self._read(2))[0]
        elif t == 0xC6:
            n = struct.unpack(">I", self._read(4))[0]
        else:
            raise RuntimeError(f"protocol: expected bin marker, got 0x{t:02x}")
        return self._read(n)

    def call(self, method, params):
        self.p.stdin.write(b"\x93\x01" + self._bin(method.encode()) + self._bin(json.dumps(params).encode()))
        self.p.stdin.flush()
        head = self._read(2)
        if head[0] != 0x93:
            raise RuntimeError(f"protocol: expected 0x93, got 0x{head[0]:02x}")
        self._rbin()  # echoed method
        payload = self._rbin()
        if head[1] == 5:
            raise RuntimeError(f"{method}: {payload.decode(errors='replace')}")
        if head[1] != 4:
            raise RuntimeError(f"{method}: unexpected message type {head[1]}")
        return payload

    def close(self):
        try:
            self.p.stdin.close()
            self.p.wait(timeout=10)
        except Exception:
            self.p.kill()


def fetch_batch(api, batch_dir, files):
    """Open a project listing exactly `files`; return {fileName: bytes or Exception}."""
    os.makedirs(batch_dir, exist_ok=True)
    cfg = os.path.join(batch_dir, "tsconfig.json")
    with open(cfg, "w") as f:
        json.dump({"compilerOptions": COMPILER_OPTIONS, "files": files}, f)
    resp = json.loads(api.call("updateSnapshot", {"openProjects": [cfg]}))
    snap = resp["snapshot"]
    projs = [p for p in resp["projects"] if p["configFileName"] == cfg]
    if len(projs) != 1:
        raise RuntimeError(f"project for {cfg} not in the snapshot")
    proj = projs[0]["id"]
    roots = set(projs[0]["rootFiles"])
    out = {}
    for fn in files:
        if fn not in roots:
            out[fn] = RuntimeError("not a root file of the oracle project (path normalization?)")
            continue
        try:
            b = api.call("getSourceFile", {"snapshot": snap, "project": proj, "file": fn})
            out[fn] = b if b else RuntimeError("getSourceFile returned no data (file not in program)")
        except RuntimeError as e:
            out[fn] = e
    api.call("release", {"snapshot": snap})
    resp = json.loads(api.call("updateSnapshot", {"closeProjects": [cfg]}))
    api.call("release", {"snapshot": resp["snapshot"]})
    return out


def worker(wid, batches, results, progress):
    api = TsgoApi(REPO)
    try:
        api.call("initialize", None)
        for bi, files in batches:
            res = fetch_batch(api, os.path.join(WORK_DIR, f"w{wid}-b{bi}"), files)
            with progress["lock"]:
                results.update(res)
                progress["done"] += len(files)
                print(f"  [{progress['done']}/{progress['total']}] worker {wid} batch {bi} ({len(files)} files)",
                      file=sys.stderr, flush=True)
    finally:
        api.close()


def fetch_all(files, workers, batch):
    batches = [(i, files[i * batch:(i + 1) * batch]) for i in range((len(files) + batch - 1) // batch)]
    # largest-first balancing by total input bytes
    loads = [[0, []] for _ in range(workers)]
    for b in sorted(batches, key=lambda b: -sum(os.path.getsize(f) for f in b[1])):
        tgt = min(loads, key=lambda x: x[0])
        tgt[0] += sum(os.path.getsize(f) for f in b[1])
        tgt[1].append(b)
    results, progress = {}, {"lock": threading.Lock(), "done": 0, "total": len(files)}
    with cf.ThreadPoolExecutor(workers) as ex:
        futs = [ex.submit(worker, w, l[1], results, progress) for w, l in enumerate(loads) if l[1]]
        for fu in futs:
            fu.result()  # a dead worker raises here -> refusal
    return results


# ------------------------------------------------------------------ main


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--inputs")
    ap.add_argument("--out", default=OUT_DIR)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--batch", type=int, default=400)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--determinism", type=int, default=50)
    ap.add_argument("--list-sources", action="store_true")
    args = ap.parse_args()

    if not os.access(TSC, os.X_OK):
        die(f"no tsgo binary at {TSC}")
    tsc_sha = sha256(open(TSC, "rb").read())
    if args.inputs:
        items, missing = read_inputs(args.inputs), []
    else:
        items, missing = default_inputs()
    if args.list_sources:
        by = {}
        for label, root, p in items:
            by.setdefault((label, root), []).append(p)
        for (label, root), ps in by.items():
            exts = {}
            for p in ps:
                e = os.path.splitext(p)[1]
                exts[e] = exts.get(e, 0) + 1
            print(f"{label:12} {len(ps):6} {os.path.relpath(root, REPO)} {exts}")
        for m in missing:
            print(f"MISSING     {m}")
        return
    if missing:
        print("tsgo-oracle: note: sources not present on this box: " + ", ".join(missing), file=sys.stderr)
    if not items:
        die("no input files")

    out_dir = os.path.abspath(args.out)
    os.makedirs(out_dir, exist_ok=True)
    man_path = os.path.join(out_dir, "manifest.json")
    old = {}
    config_key = sha256(json.dumps([CONFIG_VERSION, COMPILER_OPTIONS, tsc_sha], sort_keys=True).encode())
    if os.path.exists(man_path) and not args.force:
        try:
            m = json.load(open(man_path))
            if m.get("configKey") == config_key:
                old = {e["fileName"]: e for e in m["files"]}
        except (ValueError, KeyError):
            old = {}

    entries, seen, todo = [], set(), []
    for label, root, p in items:
        fn = os.path.normpath(os.path.abspath(p))
        if fn in seen:
            continue
        seen.add(fn)
        data = open(fn, "rb").read()
        rel = os.path.relpath(fn, root)
        e = {"source": label, "input": os.path.relpath(fn, REPO), "fileName": fn,
             "output": sanitize(os.path.join(label, rel)) + ".bin", "inSha256": sha256(data), "inSize": len(data)}
        prev = old.get(fn)
        outp = os.path.join(out_dir, e["output"])
        if (prev and not prev.get("error") and prev["inSha256"] == e["inSha256"] and prev["output"] == e["output"]
                and os.path.exists(outp) and sha256(open(outp, "rb").read()) == prev["outSha256"]):
            entries.append(prev)
            continue
        entries.append(e)
        todo.append(e)

    print(f"tsgo-oracle: {len(entries)} inputs, {len(entries) - len(todo)} cached, {len(todo)} to fetch "
          f"({args.workers} workers, batches of {args.batch})", file=sys.stderr)
    t0 = time.time()
    failures = 0
    if todo:
        try:
            results = fetch_all([e["fileName"] for e in todo], args.workers, args.batch)
        except Exception as ex:  # a worker died: record and refuse
            results = {}
            print(f"tsgo-oracle: worker failure: {ex}", file=sys.stderr)
        for e in todo:
            r = results.get(e["fileName"], RuntimeError("never fetched (worker died)"))
            if isinstance(r, Exception):
                e["error"] = str(r)
                failures += 1
                continue
            outp = os.path.join(out_dir, e["output"])
            os.makedirs(os.path.dirname(outp), exist_ok=True)
            with open(outp, "wb") as f:
                f.write(r)
            e.pop("error", None)
            e["outSha256"] = sha256(r)
            e["outSize"] = len(r)
            e["parseOptions"] = struct.unpack_from("<I", r, 20)[0]
            e["jsx"] = bool(e["parseOptions"] & 1)
            e["force"] = bool(e["parseOptions"] & 2)
            e["protocolVersion"] = r[3]
    fetch_s = time.time() - t0

    # Determinism: fresh process, reversed order, a different batch size.
    det = {"checked": 0, "mismatches": []}
    good = [e for e in entries if not e.get("error")]
    if args.determinism and good:
        step = max(1, len(good) // args.determinism)
        sample = list(reversed(good[::step][:args.determinism]))
        res = fetch_all([e["fileName"] for e in sample], 1, 7)
        for e in sample:
            r = res.get(e["fileName"])
            det["checked"] += 1
            if not isinstance(r, bytes) or sha256(r) != e["outSha256"]:
                det["mismatches"].append(e["fileName"])

    complete = failures == 0 and not det["mismatches"]
    manifest = {
        "complete": complete,
        "tsgo": TSGO_TAG, "tscSha256": tsc_sha, "configVersion": CONFIG_VERSION, "configKey": config_key,
        "compilerOptions": COMPILER_OPTIONS, "oracleDir": out_dir, "repoRoot": REPO,
        "fetchSeconds": round(fetch_s, 2), "determinism": det,
        "files": sorted(entries, key=lambda e: e["output"]),
    }
    tmp = man_path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(manifest, f, indent=1)
    os.replace(tmp, man_path)

    by = {}
    for e in entries:
        s = by.setdefault(e["source"], [0, 0, 0, 0])
        s[0] += 1
        if e.get("error"):
            s[1] += 1
        else:
            s[2] += e["outSize"]
            s[3] += e["inSize"]
    print(f"{'source':12} {'files':>6} {'failed':>6} {'in MB':>8} {'out MB':>8}")
    for k, (n, f_, ob, ib) in by.items():
        print(f"{k:12} {n:6} {f_:6} {ib / 1e6:8.2f} {ob / 1e6:8.2f}")
    fetched_bytes = sum(e["inSize"] for e in todo if not e.get("error"))
    if todo:
        print(f"fetched {len(todo) - failures} files ({fetched_bytes / 1e6:.1f} MB of source) in {fetch_s:.1f} s "
              f"= {(len(todo) - failures) / max(fetch_s, 1e-9):.0f} files/s, {fetched_bytes / 1e6 / max(fetch_s, 1e-9):.1f} MB/s")
    print(f"determinism: {det['checked']} re-fetched, {len(det['mismatches'])} mismatches")
    if failures:
        for e in entries:
            if e.get("error"):
                print(f"  FAILED {e['input']}: {e['error']}", file=sys.stderr)
        print(f"tsgo-oracle: REFUSED: {failures} input(s) failed; manifest marked incomplete", file=sys.stderr)
        sys.exit(1)
    if det["mismatches"]:
        print(f"tsgo-oracle: REFUSED: non-deterministic bytes for {det['mismatches'][:5]}", file=sys.stderr)
        sys.exit(1)
    print(f"tsgo-oracle: OK, manifest {os.path.relpath(man_path, REPO)}")


if __name__ == "__main__":
    main()
