// Binary searches in a sorted table: branches that are hard to predict.
#include "lib.h"
#define N 4096
static uint64_t t[N];

uint64_t kernel(void) {
  for (int i = 0; i < N; i++) t[i] = (uint64_t)i * 3 + 1;
  uint64_t s = 7, found = 0, h = 0;
  for (int q = 0; q < 6000; q++) {
    uint64_t key = rnd(&s) % (3 * N);
    int lo = 0, hi = N - 1, pos = -1;
    while (lo <= hi) {
      int mid = (lo + hi) / 2;
      if (t[mid] == key) { pos = mid; break; }
      if (t[mid] < key) lo = mid + 1; else hi = mid - 1;
    }
    if (pos >= 0) found++;
    h = h * 3 + (uint64_t)(pos + 1);
  }
  return found ^ (h << 16);
}
