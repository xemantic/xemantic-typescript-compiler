#!/usr/bin/env python3
"""(WARM.4) round 847 — reduce the warm/cold spine logs to the round's tables.

Reads build/bench/round847/{warm1,warm2,cold-dispatch1,cold-dispatch2,cold-spine}.log
and prints:
  1. the SPINE sub-rows (enter/leave/scope/ures/forEachChild) warm vs cold,
  2. the per-KIND enter+leave table warm vs cold (names resolved from
     SpineDispatch.kindNames, since PassTiming prints numeric kind ids),
  3. the per-HANDLER table warm vs cold, with each handler's warm share of the
     spine and its cold->warm speed-up.

Only WITHIN-round paired figures are printed; absolutes are labelled as
this-round-only per CLAUDE.md's cross-round rule.
"""
import re
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "build/bench/round847"
SRC = ROOT / "xemantic-typescript-compiler-core/src/commonMain/kotlin/SpineDispatch.kt"


def kind_names():
    """The dense NodeKind id -> name array, read straight out of the source."""
    txt = SRC.read_text()
    m = re.search(r"val kindNames: Array<String> = arrayOf\((.*?)\n    \)", txt, re.S)
    if not m:
        return {}
    names = re.findall(r'"([A-Z0-9_]+)"', m.group(1))
    return {i: n for i, n in enumerate(names)}


KINDS = kind_names()


def read(name):
    p = OUT / name
    return p.read_text() if p.exists() else ""


def spine_rows(txt):
    """All `SPINE attribution:` lines in a log, in order."""
    out = []
    for m in re.finditer(
        r"SPINE attribution: nodes=(\d+) enter=(\d+)ms leave=(\d+)ms "
        r"scope=(\d+)ms ures=(\d+)ms forEachChild=(\d+)ms", txt):
        out.append({
            "nodes": int(m.group(1)), "enter": int(m.group(2)),
            "leave": int(m.group(3)), "scope": int(m.group(4)),
            "ures": int(m.group(5)), "forEachChild": int(m.group(6)),
        })
    return out


def check_spine_rows(txt):
    """The `checkSpine` pass row (ms) of each dumped table."""
    return [int(m.group(1)) for m in
            re.finditer(r"^\s*(\d+)\s+\d+\s+\d+\s+\d+\s+checkSpine$", txt, re.M)]


def per_kind(txt):
    """All per-kind blocks; returns a list of {kindId: (ms, nodes)}."""
    blocks = []
    for blk in txt.split("per-kind enter+leave (top 12 by total ms):")[1:]:
        d = {}
        for line in blk.splitlines():
            m = re.match(r"\s*kind (\d+): (\d+) ms over (\d+) nodes", line)
            if not m:
                if line.strip() and not line.startswith("    "):
                    break
                continue
            d[int(m.group(1))] = (int(m.group(2)), int(m.group(3)))
        blocks.append(d)
    return blocks


def per_handler(txt):
    """All `per handler nanos` blocks; returns list of {name: (raw, net, calls)}."""
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


def dispatch_meta(txt):
    out = []
    for m in re.finditer(
            r"probe timestamp-pair overhead: (\d+) ns \(over (\d+) calls\)", txt):
        out.append((int(m.group(1)), int(m.group(2))))
    return out


def fmt(x):
    return f"{x:,.0f}" if isinstance(x, float) else f"{x:,}"


def main():
    warm = read("warm1.log") + read("warm2.log")
    cold_d = read("cold-dispatch1.log") + read("cold-dispatch2.log")
    cold_s = read("cold-spine.log")

    print("=" * 78)
    print("1. SPINE sub-rows — `spine` tier")
    print("=" * 78)
    w = spine_rows(warm)
    c = spine_rows(cold_s)
    if w:
        keys = ["enter", "leave", "scope", "ures", "forEachChild"]
        wm = {k: sum(r[k] for r in w) / len(w) for k in keys}
        cm = {k: sum(r[k] for r in c) / len(c) for k in keys} if c else None
        wtot = sum(wm.values())
        ctot = sum(cm.values()) if cm else 0
        print(f"warm n={len(w)}  cold n={len(c)}   nodes={w[0]['nodes']:,}")
        print(f"{'row':<16}{'warm ms':>10}{'% spine':>9}{'cold ms':>10}"
              f"{'% spine':>9}{'cold/warm':>11}")
        for k in keys:
            cw = (cm[k] / wm[k]) if cm and wm[k] else 0
            print(f"{k:<16}{wm[k]:>10,.0f}{100*wm[k]/wtot:>8.1f}%"
                  f"{(cm[k] if cm else 0):>10,.0f}"
                  f"{(100*cm[k]/ctot if ctot else 0):>8.1f}%{cw:>10.2f}x")
        print(f"{'SUM':<16}{wtot:>10,.0f}{100:>8.1f}%{ctot:>10,.0f}"
              f"{100 if ctot else 0:>8.1f}%{(ctot/wtot if wtot else 0):>10.2f}x")
        cs_w = check_spine_rows(warm)
        cs_c = check_spine_rows(cold_s)
        if cs_w:
            print(f"\npartition check: sub-rows {wtot:,.0f} ms vs `checkSpine` row "
                  f"{sum(cs_w)/len(cs_w):,.0f} ms = "
                  f"{100*wtot/(sum(cs_w)/len(cs_w)):.1f}% (warm)")
        if cs_c:
            print(f"                 sub-rows {ctot:,.0f} ms vs `checkSpine` row "
                  f"{sum(cs_c)/len(cs_c):,.0f} ms = "
                  f"{100*ctot/(sum(cs_c)/len(cs_c)):.1f}% (cold)")

    print()
    print("=" * 78)
    print("2. per-KIND enter+leave (spine tier, top 12 rows each run)")
    print("=" * 78)
    wk = per_kind(warm)
    ck = per_kind(cold_s)

    def merge(blocks):
        acc, cnt = {}, {}
        for b in blocks:
            for k, (ms, nodes) in b.items():
                acc[k] = acc.get(k, 0) + ms
                cnt[k] = cnt.get(k, 0) + 1
        return {k: (acc[k] / cnt[k], cnt[k]) for k in acc}, \
               {k: b.get(k, (0, 0))[1] for b in blocks for k in b}

    if wk:
        wmk, nodes = merge(wk)
        cmk, _ = merge(ck) if ck else ({}, {})
        wtot = sum(v for v, _ in wmk.values())
        print(f"warm blocks n={len(wk)}  cold blocks n={len(ck)}")
        print(f"{'kind':<34}{'nodes':>10}{'warm ms':>9}{'% ':>7}"
              f"{'ns/node':>9}{'cold ms':>9}{'cold/warm':>11}")
        for k, (ms, n) in sorted(wmk.items(), key=lambda kv: -kv[1][0]):
            nm = KINDS.get(k, f"kind{k}")
            nd = nodes.get(k, 0)
            cms = cmk.get(k, (0, 0))[0]
            print(f"{nm:<34}{nd:>10,}{ms:>9,.0f}{100*ms/wtot:>6.1f}%"
                  f"{(1e6*ms/nd if nd else 0):>9,.0f}{cms:>9,.0f}"
                  f"{(cms/ms if ms else 0):>10.2f}x")

    print()
    print("=" * 78)
    print("3. per-HANDLER (`dispatch` tier = SpineDispatch.PROBE)")
    print("=" * 78)
    wh = per_handler(warm)
    ch = per_handler(cold_d)
    print(f"warm draws n={len(wh)}   cold draws n={len(ch)}")
    print(f"warm probe overhead ns/pair: {dispatch_meta(warm)}")
    print(f"cold probe overhead ns/pair: {dispatch_meta(cold_d)}")
    if wh:
        # LAST draw per process is the quoted one (the probe's own code is cold
        # on its first instrumented rebuild — round 846). warm1+warm2 give 4
        # draws, of which draws 1 and 3 are firsts; keep them all but report
        # both means so the difference is visible.
        def mean(blocks, idx=None):
            sel = blocks if idx is None else [blocks[i] for i in idx if i < len(blocks)]
            acc = {}
            for b in sel:
                for n, (raw, net, calls) in b.items():
                    a = acc.setdefault(n, [0, 0, 0, 0])
                    a[0] += raw; a[1] += net; a[2] += calls; a[3] += 1
            return {n: (v[0]/v[3], v[1]/v[3], v[2]/v[3]) for n, v in acc.items()}

        wall = mean(wh)
        wlast = mean(wh, [i for i in range(len(wh)) if i % 2 == 1])
        call = mean(ch)
        wtot = sum(v[1] for v in wall.values())
        ctot = sum(v[1] for v in call.values()) if call else 0
        print(f"\nwarm net total {wtot:,.0f} ms   cold net total {ctot:,.0f} ms"
              f"   cold/warm {ctot/wtot if wtot else 0:.2f}x")
        print(f"\n{'handler':<38}{'calls':>11}{'warm net':>10}{'% ':>7}"
              f"{'2nd-draw':>10}{'cold net':>10}{'% ':>7}{'c/w':>7}")
        for n, (raw, net, calls) in sorted(wall.items(), key=lambda kv: -kv[1][1])[:26]:
            cn = call.get(n, (0, 0, 0))[1]
            l2 = wlast.get(n, (0, 0, 0))[1]
            print(f"{n:<38}{calls:>11,.0f}{net:>10,.0f}{100*net/wtot:>6.1f}%"
                  f"{l2:>10,.0f}{cn:>10,.0f}"
                  f"{(100*cn/ctot if ctot else 0):>6.1f}%"
                  f"{(cn/net if net else 0):>7.2f}")
    # the table-prize line, both regimes
    for label, txt in (("warm", warm), ("cold", cold_d)):
        for m in re.finditer(r"TABLE PRIZE \(upper bound\): (.*)", txt):
            print(f"  {label} TABLE PRIZE: {m.group(1)}")


if __name__ == "__main__":
    sys.exit(main())
