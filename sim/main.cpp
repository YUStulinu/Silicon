// Silicon's simulation harness (Verilator).
//
// It loads a RISC-V ELF into a model of main memory, clocks the core, answers
// its cache-line requests after a fixed latency, plays the UART, and stops
// when the program writes `tohost` (the riscv-tests convention: 1 means
// pass, (n << 1) | 1 means failure n). With --cosim every committed
// instruction and every trap is checked against the reference model (Rivet)
// as it happens, so a divergence stops the run at the first wrong
// instruction.
//
//   sim ELF [--cosim] [--latency N] [--max-cycles N] [--trace FILE] [--stats FILE] [--quiet]

#include "VCore.h"
#include "verilated.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <string>
#include <vector>

extern "C" {
struct GoldenCommit {
  uint64_t pc, wdata, st_addr, st_data, cause, tval;
  uint32_t inst;
  uint8_t rd, wen, st_size, is_trap, override_rd;
};
void *golden_new(const char *path, char *err, size_t cap);
int golden_check(void *g, const GoldenCommit *c, char *msg, size_t cap);
void golden_stats(const void *g, uint64_t *retired, uint64_t *traps, uint64_t *overrides);
void golden_free(void *g);
}

static const uint64_t RAM_BASE = 0x80000000ull;
static const uint64_t RAM_SIZE = 64ull << 20;
static const int LINE = 32;

struct Elf {
  uint64_t entry = 0, tohost = 0;
};

static uint64_t le(const std::vector<uint8_t> &f, size_t off, int n) {
  uint64_t v = 0;
  for (int i = 0; i < n; i++) v |= (uint64_t)f[off + i] << (8 * i);
  return v;
}

static bool load_elf(const char *path, std::vector<uint8_t> &ram, Elf &out) {
  FILE *fp = fopen(path, "rb");
  if (!fp) return false;
  std::vector<uint8_t> f;
  uint8_t buf[65536];
  size_t n;
  while ((n = fread(buf, 1, sizeof buf, fp)) > 0) f.insert(f.end(), buf, buf + n);
  fclose(fp);
  if (f.size() < 64 || memcmp(f.data(), "\x7f" "ELF", 4) != 0 || f[4] != 2) return false;
  out.entry = le(f, 24, 8);
  uint64_t phoff = le(f, 32, 8), shoff = le(f, 40, 8);
  int phnum = (int)le(f, 56, 2), shnum = (int)le(f, 60, 2);
  for (int i = 0; i < phnum; i++) {
    size_t ph = phoff + 56 * i;
    if (le(f, ph, 4) != 1) continue;
    uint64_t off = le(f, ph + 8, 8), paddr = le(f, ph + 24, 8), filesz = le(f, ph + 32, 8);
    if (paddr < RAM_BASE || paddr + filesz > RAM_BASE + RAM_SIZE) return false;
    memcpy(&ram[paddr - RAM_BASE], &f[off], filesz);
  }
  for (int i = 0; i < shnum; i++) {
    size_t sh = shoff + 64 * i;
    if (le(f, sh + 4, 4) != 2) continue;
    uint64_t off = le(f, sh + 24, 8), size = le(f, sh + 32, 8);
    uint64_t stroff = le(f, shoff + 64 * le(f, sh + 40, 4) + 24, 8);
    for (uint64_t s = 0; s + 24 <= size; s += 24) {
      const char *name = (const char *)&f[stroff + le(f, off + s, 4)];
      if (strcmp(name, "tohost") == 0) out.tohost = le(f, off + s + 8, 8);
    }
  }
  return true;
}

struct Stats {
  uint64_t cycles = 0, instret = 0, traps = 0, branches = 0, condBranches = 0, mispredicts = 0, condMispredicts = 0;
  uint64_t flushes = 0, icacheMisses = 0, dcacheMisses = 0, robOcc = 0, iqOcc = 0, memRequests = 0;
  uint64_t stallRob = 0, stallIq = 0, stallLq = 0, stallSq = 0, stallRegs = 0, stallBranches = 0, fetchEmpty = 0;
};

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  const char *elf = nullptr, *tracePath = nullptr, *statsPath = nullptr;
  bool cosim = false, quiet = false, log = false;
  uint64_t corruptAt = getenv("SIM_CORRUPT_COMMIT") ? strtoull(getenv("SIM_CORRUPT_COMMIT"), nullptr, 10) : 0;
  int latency = 20;
  uint64_t maxCycles = 50'000'000;
  for (int i = 1; i < argc; i++) {
    std::string a = argv[i];
    if (a == "--cosim") cosim = true;
    else if (a == "--quiet") quiet = true;
    else if (a == "--log") log = true;
    else if (a == "--latency") latency = atoi(argv[++i]);
    else if (a == "--max-cycles") maxCycles = strtoull(argv[++i], nullptr, 10);
    else if (a == "--trace") tracePath = argv[++i];
    else if (a == "--stats") statsPath = argv[++i];
    else if (a[0] != '+') elf = argv[i];
  }
  if (!elf) {
    fprintf(stderr, "usage: sim ELF [--cosim] [--latency N] [--max-cycles N] [--trace FILE] [--stats FILE] [--quiet]\n");
    return 2;
  }
  std::vector<uint8_t> ram(RAM_SIZE, 0);
  Elf info;
  if (!load_elf(elf, ram, info)) {
    fprintf(stderr, "cannot load %s\n", elf);
    return 2;
  }
  void *golden = nullptr;
  if (cosim) {
    char err[512] = {0};
    golden = golden_new(elf, err, sizeof err);
    if (!golden) {
      fprintf(stderr, "reference model: %s\n", err);
      return 2;
    }
  }
  FILE *trace = tracePath ? fopen(tracePath, "w") : nullptr;
  if (trace) fprintf(trace, "silicon-trace 1\n");

  VCore *top = new VCore;
  top->clock = 0;
  top->reset = 1;
  for (int i = 0; i < 4; i++) {
    top->clock = !top->clock;
    top->eval();
  }
  top->reset = 0;

  // memory: one line transaction at a time
  bool memPending = false;
  int memCountdown = 0;
  bool memWrite = false;
  uint64_t memAddr = 0;
  // mmio: answered one cycle later
  bool mmioPending = false;
  uint64_t mmioData = 0;

  Stats st;
  uint64_t lastCommit = 0;
  int result = -1; // -1 running, 0 pass, >0 failure code
  std::string error;
  std::string console;

  auto lineAt = [&](uint64_t a) -> uint8_t * {
    if (a < RAM_BASE || a + LINE > RAM_BASE + RAM_SIZE) return nullptr;
    return &ram[a - RAM_BASE];
  };

  for (uint64_t cycle = 0; cycle < maxCycles && result < 0; cycle++) {
    // ---- inputs for this cycle (decided before the rising edge)
    top->io_mem_ready = !memPending;
    top->io_memResp_valid = 0;
    if (memPending && memCountdown == 0) {
      top->io_memResp_valid = 1;
      uint8_t *l = lineAt(memAddr);
      for (int w = 0; w < LINE / 4; w++) {
        uint32_t v = 0;
        if (l && !memWrite) memcpy(&v, l + 4 * w, 4);
        top->io_memResp_bits_data[w] = v;
      }
    }
    top->io_mmio_ready = !mmioPending;
    top->io_mmioResp_valid = mmioPending;
    top->io_mmioResp_bits = mmioData;

    top->clock = 0;
    top->eval();

    // ---- observe outputs (combinational, before the edge)
    if (top->io_memResp_valid) memPending = false;
    if (!memPending && top->io_mem_valid && top->io_mem_ready) {
      memPending = true;
      if (getenv("SIM_MEMLOG")) fprintf(stderr, "harness: cycle %llu request %llx write=%d\n", (unsigned long long)cycle, (unsigned long long)top->io_mem_bits_addr, (int)top->io_mem_bits_write);
      memCountdown = latency;
      memWrite = top->io_mem_bits_write;
      memAddr = top->io_mem_bits_addr;
      st.memRequests++;
      if (memWrite) {
        uint8_t *l = lineAt(memAddr);
        if (l)
          for (int w = 0; w < LINE / 4; w++) {
            uint32_t v = top->io_mem_bits_data[w];
            memcpy(l + 4 * w, &v, 4);
          }
      }
    } else if (memPending && memCountdown > 0) {
      memCountdown--;
    }
    if (mmioPending) mmioPending = false;
    if (top->io_mmio_valid && top->io_mmio_ready) {
      uint64_t a = top->io_mmio_bits_addr;
      mmioPending = true;
      mmioData = 0;
      if (top->io_mmio_bits_write) {
        if (a == 0x10000000ull) {
          char c = (char)(top->io_mmio_bits_data & 0xff);
          console += c;
          if (!quiet) putchar(c);
        }
      } else if (a >= 0x10000000ull && a < 0x10000008ull) {
        // 16550: the line status register says the transmitter is empty
        mmioData = (uint64_t)0x60 << 40; // LSR is byte 5 of the doubleword at 0x10000000
      }
    }

    // commits
    for (int s = 0; s < 2 && result < 0; s++) {
      bool v = s == 0 ? top->io_commit_0_valid : top->io_commit_1_valid;
      if (!v) continue;
      GoldenCommit c{};
#define F(x) (s == 0 ? top->io_commit_0_##x : top->io_commit_1_##x)
      c.pc = F(pc);
      c.inst = F(inst);
      c.rd = F(rd);
      c.wen = F(wen);
      c.wdata = F(wdata);
      c.st_size = F(stSize);
      c.st_addr = F(stAddr);
      c.st_data = F(stData);
      c.is_trap = F(trap);
      c.cause = F(cause);
      c.tval = F(tval);
      c.override_rd = F(overrideRd);
#undef F
      lastCommit = cycle;
      if (log)
        fprintf(stderr, "%8llu %s %016llx %08x x%-2d %016llx%s\n", (unsigned long long)cycle, c.is_trap ? "TRAP" : "    ",
                (unsigned long long)c.pc, c.inst, c.wen ? c.rd : 0, (unsigned long long)c.wdata, c.st_size ? " store" : "");
      if (c.is_trap) st.traps++;
      else st.instret++;
      // for testing the checker: corrupt the value of the n-th committed instruction
      if (corruptAt && st.instret == corruptAt && c.wen && !c.is_trap) c.wdata ^= 1;
      if (golden) {
        char msg[1024] = {0};
        if (golden_check(golden, &c, msg, sizeof msg)) {
          error = msg;
          result = 1000;
          break;
        }
      }
      if (!c.is_trap && c.st_size && info.tohost && c.st_addr == info.tohost) {
        uint64_t code = c.st_data & 0xffffffffull;
        if (code) result = code == 1 ? 0 : (int)(code >> 1);
      }
    }

    // statistics and the pipeline trace
    st.cycles++;
    st.branches += top->io_events_branchResolved;
    st.condBranches += top->io_events_condBranchResolved;
    st.mispredicts += top->io_events_mispredict;
    st.condMispredicts += top->io_events_condMispredict;
    st.flushes += top->io_events_flush;
    st.icacheMisses += top->io_events_icacheMiss;
    st.dcacheMisses += top->io_events_dcacheMiss;
    st.robOcc += top->io_events_robCount;
    st.iqOcc += top->io_events_iqCount;
    st.stallRob += top->io_events_stallRob;
    st.stallIq += top->io_events_stallIq;
    st.stallLq += top->io_events_stallLq;
    st.stallSq += top->io_events_stallSq;
    st.stallRegs += top->io_events_stallRegs;
    st.stallBranches += top->io_events_stallBranches;
    st.fetchEmpty += top->io_events_fetchEmpty;
    if (trace) {
      fprintf(trace, "C %llu\n", (unsigned long long)cycle);
      if (top->io_events_fetch_0_valid)
        fprintf(trace, "F %u %llx %08x\n", top->io_events_fetch_0_bits_seq, (unsigned long long)top->io_events_fetch_0_bits_pc, top->io_events_fetch_0_bits_inst);
      if (top->io_events_fetch_1_valid)
        fprintf(trace, "F %u %llx %08x\n", top->io_events_fetch_1_bits_seq, (unsigned long long)top->io_events_fetch_1_bits_pc, top->io_events_fetch_1_bits_inst);
      if (top->io_events_dispatch_0_valid) fprintf(trace, "D %u\n", top->io_events_dispatch_0_bits);
      if (top->io_events_dispatch_1_valid) fprintf(trace, "D %u\n", top->io_events_dispatch_1_bits);
      if (top->io_events_issue_0_valid) fprintf(trace, "I %u 0\n", top->io_events_issue_0_bits);
      if (top->io_events_issue_1_valid) fprintf(trace, "I %u 1\n", top->io_events_issue_1_bits);
      if (top->io_events_issue_2_valid) fprintf(trace, "I %u 2\n", top->io_events_issue_2_bits);
      if (top->io_events_issue_3_valid) fprintf(trace, "I %u 3\n", top->io_events_issue_3_bits);
      if (top->io_events_complete_0_valid) fprintf(trace, "W %u\n", top->io_events_complete_0_bits);
      if (top->io_events_complete_1_valid) fprintf(trace, "W %u\n", top->io_events_complete_1_bits);
      if (top->io_events_complete_2_valid) fprintf(trace, "W %u\n", top->io_events_complete_2_bits);
      if (top->io_events_complete_3_valid) fprintf(trace, "W %u\n", top->io_events_complete_3_bits);
      if (top->io_events_complete_4_valid) fprintf(trace, "W %u\n", top->io_events_complete_4_bits);
      if (top->io_events_commit_0_valid) fprintf(trace, "R %u\n", top->io_events_commit_0_bits);
      if (top->io_events_commit_1_valid) fprintf(trace, "R %u\n", top->io_events_commit_1_bits);
      if (top->io_events_flush || top->io_events_mispredict) fprintf(trace, "X %s\n", top->io_events_flush ? "flush" : "mispredict");
    }

    if (result < 0 && cycle - lastCommit > 20000) {
      error = "no instruction committed for 20000 cycles (deadlock)";
      result = 1001;
    }

    top->clock = 1;
    top->eval();
  }
  if (result < 0) {
    error = "cycle limit reached";
    result = 1002;
  }

  uint64_t gRetired = 0, gTraps = 0, gOverrides = 0;
  if (golden) golden_stats(golden, &gRetired, &gTraps, &gOverrides);
  double ipc = st.cycles ? (double)st.instret / st.cycles : 0;
  const char *verdict = result == 0 ? "PASS" : "FAIL";
  if (!quiet || result != 0) {
    fprintf(stderr, "%s %s: %llu instructions, %llu cycles, IPC %.3f%s%s\n", verdict, elf, (unsigned long long)st.instret,
            (unsigned long long)st.cycles, ipc, error.empty() ? "" : " -- ", error.c_str());
    if (result > 0 && result < 1000) fprintf(stderr, "  test %d failed\n", result);
  }
  if (statsPath) {
    std::string err;
    for (char ch : error) {
      if (ch == '"' || ch == '\\') err += '\\';
      err += ch;
    }
    FILE *f = fopen(statsPath, "w");
    fprintf(f,
            "{\"elf\": \"%s\", \"pass\": %s, \"code\": %d, \"error\": \"%s\", \"cycles\": %llu, \"instret\": %llu, \"ipc\": %.6f, "
            "\"traps\": %llu, \"branches\": %llu, \"cond_branches\": %llu, \"mispredicts\": %llu, \"cond_mispredicts\": %llu, "
            "\"flushes\": %llu, \"icache_misses\": %llu, \"dcache_misses\": %llu, \"mem_requests\": %llu, \"avg_rob\": %.3f, "
            "\"avg_iq\": %.3f, \"cosim\": %s, \"cosim_checked\": %llu, \"cosim_overrides\": %llu, \"latency\": %d, "
            "\"stall_rob\": %llu, \"stall_iq\": %llu, \"stall_lq\": %llu, \"stall_sq\": %llu, \"stall_regs\": %llu, "
            "\"stall_branches\": %llu, \"fetch_empty\": %llu}\n",
            elf, result == 0 ? "true" : "false", result, err.c_str(), (unsigned long long)st.cycles, (unsigned long long)st.instret,
            ipc, (unsigned long long)st.traps, (unsigned long long)st.branches, (unsigned long long)st.condBranches,
            (unsigned long long)st.mispredicts, (unsigned long long)st.condMispredicts, (unsigned long long)st.flushes,
            (unsigned long long)st.icacheMisses, (unsigned long long)st.dcacheMisses, (unsigned long long)st.memRequests,
            st.cycles ? (double)st.robOcc / st.cycles : 0, st.cycles ? (double)st.iqOcc / st.cycles : 0, golden ? "true" : "false",
            (unsigned long long)(gRetired + gTraps), (unsigned long long)gOverrides, latency, (unsigned long long)st.stallRob,
            (unsigned long long)st.stallIq, (unsigned long long)st.stallLq, (unsigned long long)st.stallSq,
            (unsigned long long)st.stallRegs, (unsigned long long)st.stallBranches, (unsigned long long)st.fetchEmpty);
    fclose(f);
  }
  if (trace) fclose(trace);
  if (golden) golden_free(golden);
  top->final();
  delete top;
  return result == 0 ? 0 : 1;
}
