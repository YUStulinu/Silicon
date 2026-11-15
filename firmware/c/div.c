// Division and remainder: the iterative divider (gcd, digit sums, modular powers).
#include "lib.h"

static uint64_t gcd(uint64_t a, uint64_t b) {
  while (b) { uint64_t t = a % b; a = b; b = t; }
  return a;
}

uint64_t kernel(void) {
  uint64_t s = 3, h = 0;
  for (int i = 0; i < 600; i++) {
    uint64_t a = rnd(&s) % 1000000 + 1, b = rnd(&s) % 1000000 + 1;
    h += gcd(a, b);
    int64_t x = (int64_t)(rnd(&s) % 2000001) - 1000000;
    h += (uint64_t)(x / 7) ^ (uint64_t)(x % 13);
    uint64_t v = rnd(&s), d = 0;
    while (v) { d += v % 10; v /= 10; }
    h += d;
  }
  return h;
}
