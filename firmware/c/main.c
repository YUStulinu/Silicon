// Runs one kernel (each benchmark file defines kernel()) and checks its result.
#include "lib.h"
uint64_t kernel(void);

int main(void) {
  uint64_t c0 = rdcycle(), i0 = rdinstret();
  uint64_t r = kernel();
  uint64_t c1 = rdcycle(), i1 = rdinstret();
  puts_(BENCH_NAME);
  puts_(": cycles ");
  put_dec(c1 - c0);
  puts_(" instructions ");
  put_dec(i1 - i0);
  putch('\n');
  return report(BENCH_NAME, r, EXPECTED);
}
