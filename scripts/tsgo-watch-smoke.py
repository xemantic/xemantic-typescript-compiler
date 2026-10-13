#!/usr/bin/env python3
"""(TSGO.5-c) `tsc --watch` differential: the same scripted edits under tsgo 7.0.2 and under a port binary.

Each scenario is a project (two directories below a fresh temp directory: tsgo watches nothing within two
components of the root) and a list of steps. A step changes files and then waits until the output holds the
expected number of "Watching for file changes." lines and stays quiet; at the end the process gets SIGTERM, which
ends a watch with exit status 0 (tsgo: signal.NotifyContext). Compared: stdout (times masked), the exit status and
the files left in the project. Only scenarios whose cycle count does not depend on timing are listed: no outputs
inside a watched directory (each emitted file would itself be an event). A file is written atomically (a temporary
beside the project, renamed in: one event), and a cycle block identical to the one before it is dropped on both
sides — on a slow machine one change can still reach tsgo as two debounce batches, a second identical cycle (seen
on a CI runner).

Usage: scripts/tsgo-watch-smoke.py --bin <port executable> [--tsgo tools/tsgo-7.0.2/lib/tsc]
"""
import argparse
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time

TIME = re.compile(rb"(?<![0-9])\d\d:\d\d:\d\d [AP]M\b")
CLEAR = b"\x1b[2J\x1b[3J\x1b[H"


def collapse(stdout):
    """stdout with each cycle block (one per clear-screen) that repeats the block before it dropped."""
    blocks = stdout.split(CLEAR)
    out = [blocks[0]]
    for b in blocks[1:]:
        if len(out) < 2 or b != out[-1]:
            out.append(b)
    return CLEAR.join(out)

SCENARIOS = [
    ("watch-noemit", ["--watch"],
     {"tsconfig.json": '{ "compilerOptions": { "strict": true, "noEmit": true }, "include": ["src"] }',
      "src/a.ts": "export const a: number = 1;\n"},
     [(1, {}),
      (2, {"src/a.ts": 'export const a: number = "x";\n'}),
      (2, {"notes.txt": "note\n"}),
      (3, {"src/b.ts": "export const b: string = 1;\n"}),
      (4, {"src/b.ts": None})]),
    ("build-watch", ["-b", "-w"],
     {"tsconfig.json": '{ "compilerOptions": { "strict": true, "rootDir": "src", "outDir": "out" }, "include": ["src"] }',
      "src/a.ts": "export const a: number = 1;\n"},
     [(1, {}),
      (2, {"src/a.ts": 'export const a: number = "x";\n'}),
      (3, {"src/b.ts": 'export const b: string = "y";\n'})]),
]


def run(exe, args, files, steps, env):
    root = tempfile.mkdtemp(prefix="xtsc-watch-")
    work = os.path.join(root, "work", "app")
    try:
        for name, text in files.items():
            p = os.path.join(work, name)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w") as f:
                f.write(text)
        out_path = os.path.join(root, "stdout")
        with open(out_path, "wb") as out:
            proc = subprocess.Popen([exe] + args, cwd=work, stdout=out, stderr=subprocess.DEVNULL, env=env)
            ok = True
            for cycles, changes in steps:
                for name, text in changes.items():
                    p = os.path.join(work, name)
                    if text is None:
                        os.remove(p)
                    else:
                        os.makedirs(os.path.dirname(p), exist_ok=True)
                        tmp = os.path.join(root, "pending")
                        with open(tmp, "w") as f:
                            f.write(text)
                        os.rename(tmp, p)
                deadline = time.time() + 120
                while time.time() < deadline and proc.poll() is None:
                    with open(out_path, "rb") as f:
                        if f.read().count(b"Watching for file changes.") >= cycles:
                            break
                    time.sleep(0.05)
                else:
                    ok = False
                time.sleep(0.8)  # quiet: no further cycle follows
            proc.send_signal(signal.SIGTERM)
            try:
                status = proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.kill()
                status = "hung"
        with open(out_path, "rb") as f:
            stdout = collapse(TIME.sub(b"HH:MM:SS XM", f.read()))
        left = sorted(os.path.relpath(os.path.join(d, n), work) for d, _, ns in os.walk(work) for n in ns)
        return ok, status, stdout, left
    finally:
        shutil.rmtree(root)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bin", required=True)
    ap.add_argument("--tsgo", default="tools/tsgo-7.0.2/lib/tsc")
    a = ap.parse_args()
    a.bin, a.tsgo = os.path.abspath(a.bin), os.path.abspath(a.tsgo)
    env = dict(os.environ)
    env.pop("TS_WATCH_DEBUG", None)
    failed = 0
    for name, args, files, steps in SCENARIOS:
        want = run(a.tsgo, args, files, steps, env)
        got = run(a.bin, args, files, steps, env)
        if not want[0]:
            print(f"{name}: tsgo itself did not reach every cycle — scenario not deterministic here")
            failed += 1
        elif want[1:] == got[1:]:
            print(f"{name}: equal (exit {got[1]}, {want[2].count(b'Watching for file changes.')} cycles)")
        else:
            failed += 1
            print(f"{name}: DIFFER")
            for label, w, g in (("exit", want[1], got[1]), ("files", want[3], got[3])):
                if w != g:
                    print(f"  {label}: tsgo {w!r} / port {g!r}")
            if want[2] != got[2]:
                print("  stdout tsgo:\n" + want[2].decode(errors="replace") + "\n  stdout port:\n" + got[2].decode(errors="replace"))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
