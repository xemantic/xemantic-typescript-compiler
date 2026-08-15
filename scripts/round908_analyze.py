#!/usr/bin/env python3
"""(SPINE.1) round 908 — reduce build/bench/round908/*.log to the round's tables.

1. the probe-free warm denominator (every process's median, and each tier's
   own `overheadMs` so a reader can see what each probe cost);
2. the per-HANDLER table (`dispatch` tier), warm, on today's binary, beside
   round 847's STALE 8,095 ms one — shares only, never absolutes across rounds;
3. the SPINE sub-rows (`spine` tier) and the partition check against them;
4. the intra-handler ON/COARSE tables (`cta`, `cpa`, `call`), with the
   nesting-aware boundary estimator of round 850: the OUTERMOST level's
   ON-minus-COARSE delta divided by ALL extra boundaries.
"""
import re
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "build/bench/round908"
SRC = ROOT / "xemantic-typescript-compiler-core/src/commonMain/kotlin/SpineDispatch.kt"

# round 847, against an 8,095 ms rebuild — STALE, quoted only as a SHARE.
R847 = {
    "leave ccetSpineLeave": 876,
    "enter spineCtaM3StatementAnchor": 853,
    "leave cpaSpineLeave": 617,
    "enter ctaSpineEnter": 359,
    "enter spineIanyEnterNode": 171,
    "enter spineArithEnterNode": 153,
}
R847_DENOM = 8095.0


def read(name):
    p = OUT / name
    return p.read_text() if p.exists() else ""


def summaries(txt):
    return [json.loads(m.group(0)) for m in
            re.finditer(r'\{"summary":true.*?\}', txt)]


def instrumented(txt):
    return [json.loads(m.group(0)) for m in
            re.finditer(r'\{"instrumented":true.*?\}', txt)]


def per_handler(txt):
    blocks = []
    for blk in txt.split("-- per handler nanos")[1:]:
        d = {}
        for line in blk.splitlines()[1:]:
            m = re.match(r"\s*(\S+ \S+)\s+(-?\d+) ms raw,\s+(-?\d+) ms net over (\d+) calls",
                         line)
            if not m:
                if line.strip().startswith("=="):
                    break
                continue
            d[m.group(1)] = (int(m.group(2)), int(m.group(3)), int(m.group(4)))
        blocks.append(d)
    return blocks


def spine_rows(txt):
    out = []
    for m in re.finditer(
        r"SPINE attribution: nodes=(\d+) enter=(\d+)ms leave=(\d+)ms "
        r"scope=(\d+)ms ures=(\d+)ms forEachChild=(\d+)ms", txt):
        out.append({k: int(m.group(i)) for i, k in enumerate(
            ["nodes", "enter", "leave", "scope", "ures", "forEachChild"], 1)})
    return out


def mean_blocks(blocks):
    acc = {}
    for b in blocks:
        for n, v in b.items():
            a = acc.setdefault(n, [0.0, 0.0, 0.0, 0])
            a[0] += v[0]; a[1] += v[1]; a[2] += v[2]; a[3] += 1
    return {n: (v[0] / v[3], v[1] / v[3], v[2] / v[3], v[3]) for n, v in acc.items()}


def main():
    disp = read("disp1.log") + read("disp2.log")
    intra = read("intra1.log") + read("intra2.log")
    call = read("call1.log") + read("call2.log")
    allt = disp + intra + call

    print("=" * 80)
    print("0. THE DENOMINATOR — probe-free warm medians, this round")
    print("=" * 80)
    meds = []
    for nm in ["disp1", "disp2", "intra1", "intra2", "call1", "call2"]:
        for s in summaries(read(nm + ".log")):
            meds.append(s["medianMs"])
            print(f"  {nm:<8} median {s['medianMs']:>9.1f} ms   "
                  f"min {s['minMs']:>9.1f}  max {s['maxMs']:>9.1f}  "
                  f"files {s['files']} errors {s['errors']}")
    if meds:
        meds_s = sorted(meds)
        mean = sum(meds) / len(meds)
        print(f"\n  n={len(meds)}  mean {mean:,.1f} ms   median {meds_s[len(meds_s)//2]:,.1f}"
              f"   spread {100*(meds_s[-1]-meds_s[0])/mean:.1f}%")
        print(f"  1% = {mean/100:.1f} ms")
    print("\n  per-tier instrumentation overhead (ms over that process's median):")
    for nm in ["disp1", "disp2", "intra1", "intra2", "call1", "call2"]:
        for r in instrumented(read(nm + ".log")):
            print(f"    {nm:<8} {r['tier']:<12} run {r['run']}  "
                  f"+{r['overheadMs']:>8.1f} ms   errors {r['errors']} files {r['files']}")

    denom = sum(meds) / len(meds) if meds else 0.0

    print()
    print("=" * 80)
    print("2. PER-HANDLER (`dispatch` tier), WARM, today")
    print("=" * 80)
    wh = per_handler(disp)
    print(f"  draws n={len(wh)}")
    for m in re.finditer(r"probe timestamp-pair overhead: (\d+) ns \(over (\d+) calls\)", disp):
        print(f"  probe pair: {m.group(1)} ns over {int(m.group(2)):,} calls")
    if wh:
        mh = mean_blocks(wh)
        tot = sum(v[1] for v in mh.values())
        print(f"\n  {'handler':<40}{'net ms':>9}{'% probed':>10}{'% warm':>9}"
              f"{'r847 %warm':>12}")
        for n, v in sorted(mh.items(), key=lambda kv: -kv[1][1])[:16]:
            old = f"{100*R847[n]/R847_DENOM:>11.2f}%" if n in R847 else " " * 12
            print(f"  {n:<40}{v[1]:>9,.0f}{100*v[1]/tot:>9.1f}%"
                  f"{(100*v[1]/denom if denom else 0):>8.2f}%{old}")
        print(f"  {'TOTAL (probed)':<40}{tot:>9,.0f}{100:>9.1f}%"
              f"{(100*tot/denom if denom else 0):>8.2f}%")
        six = sum(mh[n][1] for n in R847 if n in mh)
        print(f"\n  the SIX (SPINE.1) handlers: {six:,.0f} ms net = "
              f"{100*six/tot:.1f}% of the probed spine, "
              f"{(100*six/denom if denom else 0):.2f}% of the warm rebuild")
        print("  (round 847: 63.0% of the probed spine, 33.4% of an 8,095 ms rebuild)")

    print()
    print("=" * 80)
    print("3. SPINE sub-rows (`spine` tier), WARM, today")
    print("=" * 80)
    sr = spine_rows(disp)
    if sr:
        keys = ["enter", "leave", "scope", "ures", "forEachChild"]
        m = {k: sum(r[k] for r in sr) / len(sr) for k in keys}
        t = sum(m.values())
        print(f"  n={len(sr)}  nodes={sr[0]['nodes']:,}")
        for k in keys:
            print(f"  {k:<16}{m[k]:>9,.0f} ms{100*m[k]/t:>8.1f}%")
        print(f"  {'SUM':<16}{t:>9,.0f} ms{100:>8.1f}%   "
              f"= {(100*t/denom if denom else 0):.1f}% of the warm rebuild")
        cs = [int(x) for x in re.findall(r"^\s*(\d+)\s+\d+\s+\d+\s+\d+\s+checkSpine$", disp, re.M)]
        if cs:
            print(f"  partition check vs the `checkSpine` pass row "
                  f"{sum(cs)/len(cs):,.0f} ms: {100*t/(sum(cs)/len(cs)):.1f}%")

    print()
    print("=" * 80)
    print("4. INTRA-HANDLER probe reports (raw text; the ON/COARSE differential)")
    print("=" * 80)
    for label, txt in (("cta/cpa", intra), ("call", call)):
        for blk in re.split(r"(?=== \(|-- )", txt):
            pass
    for name in ["intra1", "intra2", "call1", "call2"]:
        t = read(name + ".log")
        if not t:
            continue
        print(f"\n----- {name} -----")
        keep = False
        for line in t.splitlines():
            if line.startswith("{"):
                keep = False
                continue
            if line.strip():
                print(line)


if __name__ == "__main__":
    main()
