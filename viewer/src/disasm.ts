// A disassembler for RV64IM + Zicsr + Zifencei, enough to label the rows of
// the pipeline view.

const ABI = [
  "zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2", "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
  "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6",
];

const CSRS: Record<number, string> = {
  0x300: "mstatus", 0x301: "misa", 0x304: "mie", 0x305: "mtvec", 0x340: "mscratch", 0x341: "mepc",
  0x342: "mcause", 0x343: "mtval", 0x344: "mip", 0xb00: "mcycle", 0xb02: "minstret", 0xc00: "cycle",
  0xc02: "instret", 0xf14: "mhartid",
};

function sext(v: number, bits: number): number {
  const s = 32 - bits;
  return (v << s) >> s;
}

export function disasm(inst: number, pc: bigint = 0n): string {
  const op = inst & 0x7f;
  const rd = ABI[(inst >>> 7) & 31];
  const rs1 = ABI[(inst >>> 15) & 31];
  const rs2 = ABI[(inst >>> 20) & 31];
  const f3 = (inst >>> 12) & 7;
  const f7 = inst >>> 25;
  const immI = sext(inst >>> 20, 12);
  const immS = sext(((inst >>> 25) << 5) | ((inst >>> 7) & 31), 12);
  const immB = sext(
    (((inst >>> 31) & 1) << 12) | (((inst >>> 7) & 1) << 11) | (((inst >>> 25) & 0x3f) << 5) | (((inst >>> 8) & 0xf) << 1),
    13,
  );
  const immU = inst & 0xfffff000;
  const immJ = sext(
    (((inst >>> 31) & 1) << 20) | (((inst >>> 12) & 0xff) << 12) | (((inst >>> 20) & 1) << 11) | (((inst >>> 21) & 0x3ff) << 1),
    21,
  );
  const target = (off: number) => "0x" + (pc + BigInt(off)).toString(16);
  switch (op) {
    case 0x37:
      return `lui ${rd}, 0x${(immU >>> 12).toString(16)}`;
    case 0x17:
      return `auipc ${rd}, 0x${(immU >>> 12).toString(16)}`;
    case 0x6f:
      return rd === "zero" ? `j ${target(immJ)}` : `jal ${rd}, ${target(immJ)}`;
    case 0x67:
      if (rd === "zero" && rs1 === "ra" && immI === 0) return "ret";
      return `jalr ${rd}, ${immI}(${rs1})`;
    case 0x63:
      return `${["beq", "bne", "?", "?", "blt", "bge", "bltu", "bgeu"][f3]} ${rs1}, ${rs2}, ${target(immB)}`;
    case 0x03:
      return `${["lb", "lh", "lw", "ld", "lbu", "lhu", "lwu", "?"][f3]} ${rd}, ${immI}(${rs1})`;
    case 0x23:
      return `${["sb", "sh", "sw", "sd"][f3] ?? "?"} ${rs2}, ${immS}(${rs1})`;
    case 0x13: {
      const sh = (inst >>> 20) & 63;
      if (f3 === 1) return `slli ${rd}, ${rs1}, ${sh}`;
      if (f3 === 5) return `${inst & 0x40000000 ? "srai" : "srli"} ${rd}, ${rs1}, ${sh}`;
      if (f3 === 0 && rs1 === "zero") return `li ${rd}, ${immI}`;
      if (f3 === 0 && immI === 0) return `mv ${rd}, ${rs1}`;
      return `${["addi", "", "slti", "sltiu", "xori", "", "ori", "andi"][f3]} ${rd}, ${rs1}, ${immI}`;
    }
    case 0x1b: {
      const sh = (inst >>> 20) & 31;
      if (f3 === 0) return `addiw ${rd}, ${rs1}, ${immI}`;
      if (f3 === 1) return `slliw ${rd}, ${rs1}, ${sh}`;
      return `${inst & 0x40000000 ? "sraiw" : "srliw"} ${rd}, ${rs1}, ${sh}`;
    }
    case 0x33:
    case 0x3b: {
      const w = op === 0x3b ? "w" : "";
      if (f7 === 1) {
        const m = ["mul", "mulh", "mulhsu", "mulhu", "div", "divu", "rem", "remu"][f3];
        return `${m}${w} ${rd}, ${rs1}, ${rs2}`;
      }
      const alt = f7 === 0x20;
      const names = [alt ? "sub" : "add", "sll", "slt", "sltu", "xor", alt ? "sra" : "srl", "or", "and"];
      return `${names[f3]}${w} ${rd}, ${rs1}, ${rs2}`;
    }
    case 0x0f:
      return f3 === 1 ? "fence.i" : "fence";
    case 0x73: {
      if (inst === 0x00000073) return "ecall";
      if (inst === 0x00100073) return "ebreak";
      if (inst === 0x30200073) return "mret";
      if (inst === 0x10500073) return "wfi";
      const csr = CSRS[inst >>> 20] ?? `0x${(inst >>> 20).toString(16)}`;
      const n = ["", "csrrw", "csrrs", "csrrc", "", "csrrwi", "csrrsi", "csrrci"][f3];
      const src = f3 >= 5 ? String((inst >>> 15) & 31) : rs1;
      if (f3 === 2 && rs1 === "zero") return `csrr ${rd}, ${csr}`;
      return `${n} ${rd}, ${csr}, ${src}`;
    }
  }
  return `.word 0x${(inst >>> 0).toString(16).padStart(8, "0")}`;
}
