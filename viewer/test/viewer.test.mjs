// Tests for the trace reader and the disassembler.
import { test } from "node:test";
import assert from "node:assert/strict";
import { parseTrace, ipcSeries } from "../dist/trace.js";
import { disasm } from "../dist/disasm.js";

const sample = [
  "silicon-trace 1",
  "C 0", "F 0 80000000 00000093", "F 1 80000004 00100113",
  "C 1", "D 0", "D 1",
  "C 2", "I 0 0", "I 1 1",
  "C 3", "W 0", "W 1",
  "C 4", "R 0", "R 1", "F 2 80000008 00208463",
  "C 5", "D 2", "X mispredict", "F 2 8000000c 00000013",
  "C 6",
].join("\n");

test("the trace is read into instructions", () => {
  const t = parseTrace(sample);
  assert.equal(t.cycles, 7);
  assert.equal(t.insts.length, 4);
  assert.equal(t.committed, 2);
  const [a, b, c, d] = t.insts;
  assert.deepEqual([a.fetch, a.dispatch, a.issue, a.complete, a.commit], [0, 1, 2, 3, 4]);
  assert.equal(b.port, 1);
  assert.equal(c.squashed, true);
  assert.equal(c.dispatch, 5);
  assert.equal(d.pc, 0x8000000cn);
  assert.equal(t.redirects[0].kind, "mispredict");
});

test("IPC over a window", () => {
  const s = ipcSeries(parseTrace(sample), 2);
  assert.equal(s[4], 1);
});

test("the disassembler knows RV64IM", () => {
  assert.equal(disasm(0x00000093), "li ra, 0");
  assert.equal(disasm(0x02b50533), "mul a0, a0, a1");
  assert.equal(disasm(0x00008067), "ret");
  assert.equal(disasm(0xfe3f2023), "sw gp, -32(t5)");
  assert.equal(disasm(0x0340006f, 0x80000000n), "j 0x80000034");
  assert.equal(disasm(0x34202f73), "csrr t5, mcause");
  assert.equal(disasm(0x00000073), "ecall");
  assert.equal(disasm(0x0ff0000f), "fence");
  assert.equal(disasm(0x0210d63b), "divuw a2, ra, ra");
  assert.equal(disasm(0x00000000), ".word 0x00000000");
});
