//! The reference model for co-simulation.
//!
//! Rivet (an RV64GC emulator) runs the same program as the processor, one
//! instruction at a time. For every instruction the processor commits, or
//! every trap it takes, [`Golden::check`] steps Rivet once and compares: the
//! program counter, the instruction, the value written to the destination
//! register, the bytes a store wrote, and the cause of a trap. Values the two
//! cannot agree on by construction (a cycle counter, `misa`, a device
//! register) are marked by the processor as overrides and copied into Rivet.

use rivet::bus::{Bus, RAM_BASE};
use rivet::cpu::Hart;
use rivet::interp;
use std::ffi::{c_char, CStr};

/// One event from the processor's commit stage.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct Commit {
    pub pc: u64,
    pub wdata: u64,
    pub st_addr: u64,
    pub st_data: u64,
    pub cause: u64,
    pub tval: u64,
    pub inst: u32,
    pub rd: u8,
    /// The instruction writes `rd` (and `rd` is not x0).
    pub wen: u8,
    /// Size in bytes of the store this instruction made (0 if none).
    pub st_size: u8,
    /// The instruction did not commit; the processor took a trap at `pc`.
    pub is_trap: u8,
    /// `wdata` is not comparable (cycle counter, misa, device read): copy it into the model.
    pub override_rd: u8,
}

pub struct Golden {
    pub hart: Box<Hart>,
    pub tohost: u64,
    pub retired: u64,
    pub traps: u64,
    pub overrides: u64,
}

const RAM_SIZE: u64 = 64 << 20;

fn le(b: &[u8], off: usize, n: usize) -> u64 {
    let mut v = 0u64;
    for i in 0..n {
        v |= (b[off + i] as u64) << (8 * i);
    }
    v
}

impl Golden {
    /// Loads a 64-bit RISC-V ELF at its physical addresses and points the
    /// hart at its entry, in machine mode.
    pub fn load(path: &str) -> Result<Golden, String> {
        let f = std::fs::read(path).map_err(|e| format!("{path}: {e}"))?;
        if f.len() < 64 || &f[0..4] != b"\x7fELF" || f[4] != 2 {
            return Err(format!("{path} is not a 64-bit ELF"));
        }
        let mut bus = Bus::new(RAM_SIZE, true);
        let entry = le(&f, 24, 8);
        let phoff = le(&f, 32, 8) as usize;
        let phnum = le(&f, 56, 2) as usize;
        for i in 0..phnum {
            let ph = phoff + 56 * i;
            if le(&f, ph, 4) != 1 {
                continue;
            }
            let off = le(&f, ph + 8, 8) as usize;
            let paddr = le(&f, ph + 24, 8);
            let filesz = le(&f, ph + 32, 8) as usize;
            let memsz = le(&f, ph + 40, 8) as usize;
            let mut seg = vec![0u8; memsz];
            seg[..filesz].copy_from_slice(&f[off..off + filesz]);
            if !bus.ram.write_bytes(paddr, &seg) {
                return Err(format!("{path}: segment at {paddr:#x} is outside RAM"));
            }
        }
        let tohost = symbol(&f, "tohost").unwrap_or(0);
        let mut hart = Hart::new(bus);
        hart.reset(entry);
        Ok(Golden { hart, tohost, retired: 0, traps: 0, overrides: 0 })
    }

    fn read(&mut self, addr: u64, size: usize) -> Option<u64> {
        if addr < RAM_BASE {
            return None;
        }
        let instret = self.hart.instret;
        self.hart.bus.read(addr, size, instret)
    }

    /// The instruction word at pc (machine mode, no translation).
    pub fn inst_at(&mut self, pc: u64) -> u32 {
        self.read(pc, 4).unwrap_or(0) as u32
    }

    /// Executes one instruction; returns the trap cause and value if it trapped.
    pub fn step(&mut self) -> Option<(u64, u64)> {
        let h = &mut *self.hart;
        interp::step(h);
        if h.trap_pending {
            let (c, t) = (h.trap_cause, h.trap_tval);
            h.take_trap(c, t);
            self.traps += 1;
            Some((c, t))
        } else {
            h.instret += 1;
            self.retired += 1;
            None
        }
    }

    /// The value at tohost (non-zero once a riscv-test has finished).
    pub fn tohost_value(&mut self) -> u64 {
        if self.tohost == 0 {
            return 0;
        }
        self.read(self.tohost, 8).unwrap_or(0)
    }

    /// Steps the model once and compares it with the processor's event.
    pub fn check(&mut self, c: &Commit) -> Result<(), String> {
        let pc = self.hart.pc;
        if pc != c.pc {
            return Err(format!("pc: processor {:#x}, model {:#x}", c.pc, pc));
        }
        let inst = self.inst_at(pc);
        let trap = self.step();
        match (trap, c.is_trap != 0) {
            (Some((cause, tval)), true) => {
                if cause != c.cause {
                    return Err(format!("trap at {pc:#x}: processor cause {}, model cause {cause}", c.cause));
                }
                if tval != c.tval {
                    return Err(format!("trap at {pc:#x}: processor tval {:#x}, model tval {tval:#x}", c.tval));
                }
                Ok(())
            }
            (Some((cause, _)), false) => Err(format!("at {pc:#x} ({inst:08x}) the model trapped (cause {cause}) but the processor committed")),
            (None, true) => Err(format!("at {pc:#x} ({inst:08x}) the processor trapped (cause {}) but the model did not", c.cause)),
            (None, false) => {
                if inst != c.inst {
                    return Err(format!("instruction at {pc:#x}: processor {:08x}, model {inst:08x}", c.inst));
                }
                let rd = c.rd as usize & 31;
                if c.wen != 0 && rd != 0 {
                    if c.override_rd != 0 {
                        self.hart.x[rd] = c.wdata;
                        self.overrides += 1;
                    } else if self.hart.x[rd] != c.wdata {
                        return Err(format!(
                            "x{rd} after {pc:#x} ({inst:08x}): processor {:#x}, model {:#x}",
                            c.wdata, self.hart.x[rd]
                        ));
                    }
                }
                if c.st_size != 0 && c.st_addr >= RAM_BASE {
                    let n = c.st_size as usize;
                    let mask = if n == 8 { !0u64 } else { (1u64 << (8 * n)) - 1 };
                    match self.read(c.st_addr, n) {
                        Some(v) if v == c.st_data & mask => {}
                        v => {
                            return Err(format!(
                                "store at {pc:#x} to {:#x}: processor wrote {:#x}, model memory holds {:?}",
                                c.st_addr,
                                c.st_data & mask,
                                v.map(|x| format!("{x:#x}"))
                            ))
                        }
                    }
                }
                Ok(())
            }
        }
    }
}

/// Looks up a symbol in an ELF's symbol table.
pub fn symbol(f: &[u8], want: &str) -> Option<u64> {
    let shoff = le(f, 40, 8) as usize;
    let shnum = le(f, 60, 2) as usize;
    for i in 0..shnum {
        let sh = shoff + 64 * i;
        if le(f, sh + 4, 4) != 2 {
            continue;
        }
        let off = le(f, sh + 24, 8) as usize;
        let size = le(f, sh + 32, 8) as usize;
        let link = le(f, sh + 40, 4) as usize;
        let stroff = le(f, shoff + 64 * link + 24, 8) as usize;
        let mut s = 0;
        while s + 24 <= size {
            let sym = off + s;
            let name = le(f, sym, 4) as usize;
            let bytes = &f[stroff + name..];
            let end = bytes.iter().position(|&b| b == 0).unwrap_or(0);
            if &bytes[..end] == want.as_bytes() {
                return Some(le(f, sym + 8, 8));
            }
            s += 24;
        }
    }
    None
}

// ---------------------------------------------------------------- C interface

/// # Safety
/// `path` must be a NUL-terminated string.
#[no_mangle]
pub unsafe extern "C" fn golden_new(path: *const c_char, err: *mut c_char, cap: usize) -> *mut Golden {
    let p = CStr::from_ptr(path).to_string_lossy().into_owned();
    match Golden::load(&p) {
        Ok(g) => Box::into_raw(Box::new(g)),
        Err(e) => {
            write_msg(err, cap, &e);
            std::ptr::null_mut()
        }
    }
}

/// Returns 0 if the event matches the model, 1 otherwise (with a message).
///
/// # Safety
/// `g` must come from `golden_new`, `c` must be valid, `msg` must hold `cap` bytes.
#[no_mangle]
pub unsafe extern "C" fn golden_check(g: *mut Golden, c: *const Commit, msg: *mut c_char, cap: usize) -> i32 {
    match (*g).check(&*c) {
        Ok(()) => 0,
        Err(e) => {
            write_msg(msg, cap, &e);
            1
        }
    }
}

/// # Safety
/// `g` must come from `golden_new`.
#[no_mangle]
pub unsafe extern "C" fn golden_stats(g: *const Golden, retired: *mut u64, traps: *mut u64, overrides: *mut u64) {
    *retired = (*g).retired;
    *traps = (*g).traps;
    *overrides = (*g).overrides;
}

/// # Safety
/// `g` must come from `golden_new` and not be used afterwards.
#[no_mangle]
pub unsafe extern "C" fn golden_free(g: *mut Golden) {
    if !g.is_null() {
        drop(Box::from_raw(g));
    }
}

unsafe fn write_msg(buf: *mut c_char, cap: usize, s: &str) {
    if buf.is_null() || cap == 0 {
        return;
    }
    let n = s.len().min(cap - 1);
    std::ptr::copy_nonoverlapping(s.as_ptr() as *const c_char, buf, n);
    *buf.add(n) = 0;
}
