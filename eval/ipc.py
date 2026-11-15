"""IPC and microarchitectural statistics: every benchmark on every
configuration, plus a memory-latency sweep, all with co-simulation on (so
every number comes from a run whose every instruction was checked).

    python3 eval/ipc.py BUILD [--configs ooo,inorder,nopred,small] [--out results/ipc.json]
"""

from __future__ import annotations

import argparse
import concurrent.futures as cf
import glob
import json
import math
import os
import subprocess
import tempfile


def run(build: str, cfg: str, elf: str, latency: int) -> dict:
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as f:
        path = f.name
    p = subprocess.run(
        [os.path.join(build, f"sim-{cfg}"), elf, "--cosim", "--quiet", "--latency", str(latency), "--stats", path],
        capture_output=True, text=True, timeout=3600,
    )
    with open(path) as f:
        s = json.load(f)
    os.unlink(path)
    s["config"] = cfg
    s["bench"] = os.path.basename(elf)[:-4]
    s["stdout"] = p.stdout
    return s


def geomean(xs: list[float]) -> float:
    return math.exp(sum(math.log(x) for x in xs) / len(xs))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("build")
    ap.add_argument("--configs", default="ooo,inorder,nopred,small")
    ap.add_argument("--latencies", default="5,20,50,100")
    ap.add_argument("--out")
    ap.add_argument("--jobs", type=int, default=os.cpu_count() or 4)
    a = ap.parse_args()
    configs = a.configs.split(",")
    benches = sorted(glob.glob(os.path.join(a.build, "fw", "*.elf")))
    jobs = [(c, b, 20) for c in configs for b in benches]
    jobs += [(c, b, lat) for c in ("ooo", "inorder") for b in benches for lat in map(int, a.latencies.split(",")) if lat != 20]
    with cf.ThreadPoolExecutor(a.jobs) as ex:
        results = list(ex.map(lambda j: run(a.build, *j), jobs))
    bad = [r for r in results if not r["pass"]]
    for r in bad:
        print("FAILED", r["config"], r["bench"], r["latency"], r["error"])
    main_runs = [r for r in results if r["latency"] == 20]
    names = sorted({r["bench"] for r in main_runs})
    table = {}
    for n in names:
        row = {}
        for c in configs:
            r = next(x for x in main_runs if x["bench"] == n and x["config"] == c)
            row[c] = {k: r[k] for k in ("cycles", "instret", "ipc", "cond_branches", "cond_mispredicts", "mispredicts",
                                         "branches", "icache_misses", "dcache_misses", "avg_rob", "avg_iq")}
        table[n] = row
    summary = {c: {"geomean_ipc": round(geomean([table[n][c]["ipc"] for n in names]), 4)} for c in configs}
    if "inorder" in configs:
        for c in configs:
            summary[c]["geomean_speedup_vs_inorder"] = round(
                geomean([table[n]["inorder"]["cycles"] / table[n][c]["cycles"] for n in names]), 4)
    sweep = {}
    for r in results:
        if r["config"] in ("ooo", "inorder"):
            sweep.setdefault(r["bench"], {}).setdefault(str(r["latency"]), {})[r["config"]] = r["cycles"]
    out = {"benchmarks": table, "summary": summary, "latency_sweep": sweep, "failures": len(bad)}
    print(json.dumps(summary, indent=1))
    if a.out:
        with open(a.out, "w") as f:
            json.dump(out, f, indent=1)


if __name__ == "__main__":
    main()
