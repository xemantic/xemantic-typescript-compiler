#!/usr/bin/env python3
"""(SPINE.1) round 908 — the nesting-aware ON/COARSE reduction of the three
intra-handler probes.

Round 850's estimator, restated so it can be checked: a boundary opened at a
DEEPER level executes inside every shallower level's span, so the boundary price
is the OUTERMOST level's ON-minus-COARSE delta divided by ALL the extra
boundaries the ON arm carries —

    b = (rawON(outermost) - rawCOARSE(outermost)) / (N_ON(all levels) - N_COARSE(all levels))

and then net(level) = raw(level) - b * N(all boundaries executing inside it),
which makes the two arms agree at the outermost level BY CONSTRUCTION and at the
inner levels only if the estimator is right (it is not forced there — that is the
check).

Per-SECTION net subtracts the section's own closes only, so a row whose raw
ns/close is below b is UNRESOLVED and its true cost lies in [0, raw].
"""
import re
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "build/bench/round908"

# (probe title marker, outermost level letter, all level letters)
PROBES = {
    "cta": ("(TYPE.2) intra-handler attribution", "A", "ABCDE"),
    "cpa": ("(ENGINE.2) property-access attribution", "P", "PQR"),
    "call": ("(CALL.1)", None, None),
}


def blocks(txt, marker):
    """Split a log into per-report blocks that contain `marker`."""
    out = []
    parts = re.split(r"(?m)^== ", txt)
    for p in parts:
        if marker in p.splitlines()[0] if p.splitlines() else False:
            out.append(p)
    return out


def levels(blk):
    """{letter: (net_ms, raw_ms, boundaries)} from `level X partition:` lines."""
    d = {}
    for m in re.finditer(
            r"level (\w+) (?:partition|total): (\d+) ms(?: net, (\d+) ms raw)?"
            r"(?: over (\d+) boundaries)?", blk):
        d[m.group(1)] = (int(m.group(2)),
                         int(m.group(3)) if m.group(3) else int(m.group(2)),
                         int(m.group(4)) if m.group(4) else 0)
    return d


def csv_rows(blk):
    """the `csv` payload: {section: (reached, nanos)}"""
    d = {}
    m = re.search(r"csv ==\n(.*?)\n== .*csv end ==", blk, re.S)
    if not m:
        return d
    for line in m.group(1).splitlines()[1:]:
        mm = re.match(r'"(.*)",(\d+),(\d+),(\d+)$', line)
        if mm:
            d[mm.group(1)] = (int(mm.group(2)), int(mm.group(3)))
    return d


def is_on(blk):
    return bool(re.search(r"mode:? ?=? ?ON", blk)) and "COARSE (anchors only)" not in blk


def main():
    for probe, files, marker, outer in (
        ("cta", ["intra1.log", "intra2.log"], "(TYPE.2) intra-handler attribution", "A"),
        ("cpa", ["intra1.log", "intra2.log"], "(ENGINE.2) property-access attribution", "P"),
        ("call", ["call1.log", "call2.log"], "(CALL.1)", None),
    ):
        txt = "".join((OUT / f).read_text() for f in files if (OUT / f).exists())
        bs = [p for p in re.split(r"(?m)^== ", txt)
              if p.splitlines() and marker in p.splitlines()[0]]
        if not bs:
            print(f"\n### {probe}: no blocks (marker {marker!r})")
            continue
        on = [b for b in bs if "COARSE" not in b.split("\n")[1]]
        co = [b for b in bs if "COARSE" in b.split("\n")[1]]
        print("\n" + "=" * 78)
        print(f"### {probe}   ON blocks={len(on)}  COARSE blocks={len(co)}")
        print("=" * 78)
        if not co:
            print("  (no COARSE arm found — printing ON levels raw)")
            for b in on:
                print("   ", levels(b))
            continue

        def mean_levels(bl):
            acc = {}
            for b in bl:
                for k, v in levels(b).items():
                    a = acc.setdefault(k, [0, 0, 0, 0])
                    a[0] += v[0]; a[1] += v[1]; a[2] += v[2]; a[3] += 1

            return {k: (v[0]/v[3], v[1]/v[3], v[2]/v[3]) for k, v in acc.items()}

        lon, lco = mean_levels(on), mean_levels(co)
        outer = outer or sorted(lon)[0]
        non = sum(v[2] for v in lon.values())
        nco = sum(v[2] for v in lco.values())
        draw = lon[outer][1] - lco[outer][1]
        b = draw * 1e6 / (non - nco) if non != nco else 0.0
        print(f"  outermost level {outer}: ON raw {lon[outer][1]:,.0f} ms   "
              f"COARSE raw {lco[outer][1]:,.0f} ms   delta {draw:,.0f} ms")
        print(f"  boundaries: ON {non:,.0f}   COARSE {nco:,.0f}   extra {non-nco:,.0f}")
        print(f"  ==> boundary b = {b:,.0f} ns   (round 850: cta 127, cpa 97, arg/call 202)")
        print(f"\n  {'level':<8}{'ON raw':>10}{'ON net':>10}{'CO raw':>10}{'CO net':>10}"
              f"{'agree':>8}")
        for k in sorted(lon):
            onn = lon[k][1] - b * lon[k][2] / 1e6
            con = lco[k][1] - b * lco[k][2] / 1e6 if k in lco else float("nan")
            ag = f"{100*con/onn:.0f}%" if onn and con == con else ""
            print(f"  {k:<8}{lon[k][1]:>10,.0f}{onn:>10,.0f}"
                  f"{(lco[k][1] if k in lco else 0):>10,.0f}{con:>10,.0f}{ag:>8}")

        # per-section, ON arm
        acc = {}
        for blk in on:
            for name, (reached, nanos) in csv_rows(blk).items():
                a = acc.setdefault(name, [0, 0, 0])
                a[0] += reached; a[1] += nanos; a[2] += 1
        print(f"\n  {'section':<58}{'raw ms':>9}{'closes':>10}{'ns/cl':>8}{'net ms':>9}")
        for name, (reached, nanos, n) in sorted(acc.items(), key=lambda kv: -kv[1][1]):
            r = reached / n
            raw = nanos / n / 1e6
            if r == 0:
                continue
            nsc = nanos / n / r
            net = raw - b * r / 1e6
            flag = "  UNRESOLVED" if nsc < b else ""
            print(f"  {name[:57]:<58}{raw:>9,.1f}{r:>10,.0f}{nsc:>8,.0f}"
                  f"{max(net,0):>9,.1f}{flag}")


if __name__ == "__main__":
    main()
