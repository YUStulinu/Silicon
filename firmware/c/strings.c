// String and memory routines: copying, measuring, comparing, reversing.
#include "lib.h"
#define N 4000
static char a[N + 1], b[N + 1];

uint64_t kernel(void) {
  uint64_t s = 5, h = 0;
  for (int round = 0; round < 12; round++) {
    int len = 1000 + (int)(rnd(&s) % (N - 1000));
    for (int i = 0; i < len; i++) a[i] = (char)('a' + rnd(&s) % 26);
    a[len] = 0;
    memcpy(b, a, (size_t)len + 1);
    for (int i = 0, j = len - 1; i < j; i++, j--) { char c = b[i]; b[i] = b[j]; b[j] = c; }
    int c = memcmp(a, b, (size_t)len);
    h = h * 131 + strlen(b) + (uint64_t)((c > 0) - (c < 0));
    for (int i = 0; i < len; i++) h += (uint64_t)(a[i] == b[len - 1 - i]);
  }
  return h;
}
