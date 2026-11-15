// 32x32 integer matrix multiplication (multiplier-heavy, regular loads).
#include "lib.h"
#define N 32
static int64_t A[N][N], B[N][N], C[N][N];

uint64_t kernel(void) {
  uint64_t s = 1234567;
  for (int i = 0; i < N; i++)
    for (int j = 0; j < N; j++) {
      A[i][j] = (int64_t)(rnd(&s) % 201) - 100;
      B[i][j] = (int64_t)(rnd(&s) % 201) - 100;
    }
  for (int i = 0; i < N; i++)
    for (int j = 0; j < N; j++) {
      int64_t acc = 0;
      for (int k = 0; k < N; k++) acc += A[i][k] * B[k][j];
      C[i][j] = acc;
    }
  uint64_t h = 0;
  for (int i = 0; i < N; i++)
    for (int j = 0; j < N; j++) h = h * 1000003 + (uint64_t)C[i][j];
  return h;
}
