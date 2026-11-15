"""Co-simulation tests: the processor (both configurations) against the
reference model (Rivet), instruction by instruction.

    SILICON_BUILD=build python3 -m pytest tests -q

Needs the build produced by tools/build.sh (simulators, ISA tests, firmware).
SILICON_RANDOM=N sets how many random programs to run (default 40).
"""

from __future__ import annotations

import glob
import json
import os
import subprocess
import tempfile

import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD = os.environ.get("SILICON_BUILD", os.path.join(ROOT, "build"))
CONFIGS = ["ooo", "inorder"]
RANDOM = int(os.environ.get("SILICON_RANDOM", "40"))
CC = os.environ.get("RISCV_CC", "riscv64-unknown-elf-gcc")


def sim(cfg: str, elf: str, *extra: str, env: dict | None = None, timeout: int = 600):
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as f:
        stats = f.name
    p = subprocess.run(
        [os.path.join(BUILD, f"sim-{cfg}"), elf, "--cosim", "--quiet", "--stats", stats, *extra],
        capture_output=True,
        text=True,
        timeout=timeout,
        env={**os.environ, **(env or {})},
    )
    with open(stats) as f:
        s = json.load(f)
    os.unlink(stats)
    return p, s


def elfs(pattern: str) -> list[str]:
    return sorted(glob.glob(os.path.join(BUILD, pattern)))


ISA = elfs("isa/*.elf")
FIRMWARE = elfs("fw/*.elf")


def test_build_present():
    assert ISA, f"no ISA tests in {BUILD}/isa (run tools/build.sh)"
    assert len(ISA) == 66
    assert len(FIRMWARE) == 11


@pytest.mark.parametrize("elf", ISA, ids=lambda e: os.path.basename(e)[:-4])
def test_reference_model_passes_isa(elf):
    """The tests themselves are sound: the reference model alone passes them."""
    p = subprocess.run([os.path.join(BUILD, "golden/release/golden"), "run", elf], capture_output=True, text=True)
    assert p.returncode == 0, p.stdout


@pytest.mark.parametrize("cfg", CONFIGS)
@pytest.mark.parametrize("elf", ISA, ids=lambda e: os.path.basename(e)[:-4])
def test_isa(cfg, elf):
    p, s = sim(cfg, elf)
    assert s["pass"], f"{s['error']} {p.stderr[-500:]}"
    assert s["cosim_checked"] == s["instret"] + s["traps"]


@pytest.mark.parametrize("cfg", CONFIGS)
@pytest.mark.parametrize("elf", FIRMWARE, ids=lambda e: os.path.basename(e)[:-4])
def test_firmware(cfg, elf):
    p, s = sim(cfg, elf, timeout=1800)
    name = os.path.basename(elf)[:-4]
    assert s["pass"], f"{s['error']} {p.stdout[-500:]}"
    assert f"{name}: OK" in p.stdout


@pytest.fixture(scope="session")
def random_elfs():
    out = os.path.join(BUILD, "rand-pytest")
    os.makedirs(out, exist_ok=True)
    paths = []
    for seed in range(1, RANDOM + 1):
        src = os.path.join(out, f"r{seed}.S")
        elf = os.path.join(out, f"r{seed}.elf")
        subprocess.run(["python3", os.path.join(ROOT, "tests/randprog.py"), str(seed), src], check=True)
        subprocess.run(
            [CC, "-march=rv64im_zicsr_zifencei", "-mabi=lp64", "-nostdlib", "-nostartfiles", "-static",
             "-T", os.path.join(ROOT, "tests/env/link.ld"), src, "-o", elf],
            check=True,
        )
        paths.append(elf)
    return paths


@pytest.mark.parametrize("cfg", CONFIGS)
@pytest.mark.parametrize("seed", range(1, RANDOM + 1))
def test_random_program(cfg, seed, random_elfs):
    p, s = sim(cfg, random_elfs[seed - 1])
    assert s["pass"], f"seed {seed}: {s['error']}"


def test_checker_detects_a_wrong_value():
    """Corrupting one committed value must be caught by the co-simulation."""
    elf = os.path.join(BUILD, "isa", "rv64ui-add.elf")
    p, s = sim("ooo", elf, env={"SIM_CORRUPT_COMMIT": "100"})
    assert not s["pass"]
    assert "processor" in s["error"] and "model" in s["error"]


def test_inorder_is_never_faster():
    """The in-order variant issues a subset of what the out-of-order core may
    issue, so on the same program it should not take fewer cycles (up to
    small differences from cache and predictor timing)."""
    for elf in FIRMWARE:
        _, a = sim("ooo", elf, timeout=1800)
        _, b = sim("inorder", elf, timeout=1800)
        assert b["cycles"] >= 0.98 * a["cycles"], os.path.basename(elf)
