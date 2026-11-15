// Sieve of Eratosthenes below 60000 (byte stores, data-dependent loops).
#include "lib.h"
#define N 60000
static uint8_t comp[N];

uint64_t kernel(void) {
  uint64_t count = 0, sum = 0;
  for (int i = 2; i < N; i++) {
    if (comp[i]) continue;
    count++;
    sum += (uint64_t)i;
    for (int j = 2 * i; j < N; j += i) comp[j] = 1;
  }
  return count * 1000000007ull + sum;
}
