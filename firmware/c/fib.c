// Recursive Fibonacci: calls and returns (the return-address stack).
#include "lib.h"

static __attribute__((noinline)) uint64_t fib(uint64_t n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }

uint64_t kernel(void) { return fib(22); }
