//! `golden run ELF...`: runs programs on the reference model alone and
//! reports the riscv-tests result (tohost), so that the tests themselves can
//! be validated without the processor.

use golden::Golden;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 2 || args[0] != "run" {
        eprintln!("usage: golden run ELF... [--max N]");
        std::process::exit(2);
    }
    let mut max = 50_000_000u64;
    let mut files = vec![];
    let mut i = 1;
    while i < args.len() {
        if args[i] == "--max" {
            max = args[i + 1].parse().expect("--max N");
            i += 2;
        } else {
            files.push(args[i].clone());
            i += 1;
        }
    }
    let mut failed = 0;
    for f in &files {
        let mut g = match Golden::load(f) {
            Ok(g) => g,
            Err(e) => {
                println!("{f}: {e}");
                failed += 1;
                continue;
            }
        };
        let mut n = 0;
        let code = loop {
            g.step();
            n += 1;
            let v = g.tohost_value();
            if v != 0 {
                break v;
            }
            if n >= max {
                break 0;
            }
        };
        let ok = code == 1;
        if !ok {
            failed += 1;
        }
        let name = std::path::Path::new(f).file_name().unwrap().to_string_lossy();
        println!(
            "{:<4} {name}  ({} instructions{})",
            if ok { "PASS" } else { "FAIL" },
            g.retired,
            if ok { String::new() } else { format!(", tohost = {code:#x}") }
        );
    }
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
