// A tiny runtime for Silicon programs: console output through the UART and
// the cycle and instruction counters.
#pragma once
#include <stddef.h>
#include <stdint.h>

void putch(char c);
void puts_(const char *s);
void put_dec(uint64_t v);
void put_hex(uint64_t v);
uint64_t rdcycle(void);
uint64_t rdinstret(void);
void *memcpy(void *d, const void *s, size_t n);
void *memset(void *d, int c, size_t n);
int memcmp(const void *a, const void *b, size_t n);
size_t strlen(const char *s);

// xorshift: deterministic pseudo-random numbers
static inline uint64_t rnd(uint64_t *s) {
  uint64_t x = *s;
  x ^= x << 13;
  x ^= x >> 7;
  x ^= x << 17;
  return *s = x;
}

// Prints "name: OK checksum" or "name: BAD ..." and returns 0 or 1.
int report(const char *name, uint64_t got, uint64_t want);
