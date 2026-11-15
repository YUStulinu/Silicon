#include "lib.h"

#define UART ((volatile uint8_t *)0x10000000)

void putch(char c) { UART[0] = (uint8_t)c; }

void puts_(const char *s) {
  while (*s) putch(*s++);
}

void put_dec(uint64_t v) {
  char b[24];
  int n = 0;
  do {
    b[n++] = (char)('0' + v % 10);
    v /= 10;
  } while (v);
  while (n) putch(b[--n]);
}

void put_hex(uint64_t v) {
  puts_("0x");
  for (int i = 60; i >= 0; i -= 4) putch("0123456789abcdef"[(v >> i) & 15]);
}

uint64_t rdcycle(void) {
  uint64_t v;
  __asm__ volatile("csrr %0, mcycle" : "=r"(v));
  return v;
}

uint64_t rdinstret(void) {
  uint64_t v;
  __asm__ volatile("csrr %0, minstret" : "=r"(v));
  return v;
}

void *memcpy(void *d, const void *s, size_t n) {
  uint8_t *dp = d;
  const uint8_t *sp = s;
  if ((((uintptr_t)d | (uintptr_t)s | n) & 7) == 0) {
    uint64_t *d8 = d;
    const uint64_t *s8 = s;
    for (size_t i = 0; i < n / 8; i++) d8[i] = s8[i];
    return d;
  }
  while (n--) *dp++ = *sp++;
  return d;
}

void *memset(void *d, int c, size_t n) {
  uint8_t *p = d;
  while (n--) *p++ = (uint8_t)c;
  return d;
}

int memcmp(const void *a, const void *b, size_t n) {
  const uint8_t *x = a, *y = b;
  for (size_t i = 0; i < n; i++)
    if (x[i] != y[i]) return x[i] < y[i] ? -1 : 1;
  return 0;
}

size_t strlen(const char *s) {
  size_t n = 0;
  while (s[n]) n++;
  return n;
}

int report(const char *name, uint64_t got, uint64_t want) {
  puts_(name);
  if (got == want) {
    puts_(": OK ");
    put_hex(got);
    putch('\n');
    return 0;
  }
  puts_(": BAD got ");
  put_hex(got);
  puts_(" want ");
  put_hex(want);
  putch('\n');
  return 1;
}
