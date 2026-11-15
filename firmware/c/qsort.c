// Quicksort of 3000 pseudo-random numbers; the checksum mixes the sorted
// order, so any wrong comparison or lost element changes it.
#include "lib.h"
#define N 3000
static int64_t a[N];

static void qs(int64_t *v, int lo, int hi) {
  while (lo < hi) {
    int64_t p = v[(lo + hi) / 2];
    int i = lo, j = hi;
    while (i <= j) {
      while (v[i] < p) i++;
      while (v[j] > p) j--;
      if (i <= j) {
        int64_t t = v[i]; v[i] = v[j]; v[j] = t;
        i++; j--;
      }
    }
    if (j - lo < hi - i) { qs(v, lo, j); lo = i; }
    else { qs(v, i, hi); hi = j; }
  }
}

uint64_t kernel(void) {
  uint64_t s = 88172645463325252ull;
  for (int i = 0; i < N; i++) a[i] = (int64_t)(rnd(&s) % 1000000) - 500000;
  qs(a, 0, N - 1);
  uint64_t h = 0;
  for (int i = 0; i < N; i++) {
    if (i && a[i - 1] > a[i]) return 0;
    h = h * 31 + (uint64_t)a[i];
  }
  return h;
}
