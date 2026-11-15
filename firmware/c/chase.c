// Pointer chasing through a randomly permuted linked list of 8192 nodes,
// larger than the data cache: every load depends on the previous one.
#include "lib.h"
#define N 8192
struct node { struct node *next; uint64_t val; };
static struct node nodes[N];
static uint32_t perm[N];

uint64_t kernel(void) {
  uint64_t s = 99;
  for (uint32_t i = 0; i < N; i++) perm[i] = i;
  for (uint32_t i = N - 1; i > 0; i--) {
    uint32_t j = (uint32_t)(rnd(&s) % (i + 1));
    uint32_t t = perm[i]; perm[i] = perm[j]; perm[j] = t;
  }
  for (uint32_t i = 0; i < N; i++) {
    nodes[perm[i]].next = &nodes[perm[(i + 1) % N]];
    nodes[perm[i]].val = i;
  }
  uint64_t h = 0;
  struct node *p = &nodes[perm[0]];
  for (int k = 0; k < 3 * N; k++) {
    h = h * 7 + p->val;
    p = p->next;
  }
  return h;
}
