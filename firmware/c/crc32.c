// Table-driven CRC-32 of 16 KiB of pseudo-random bytes.
#include "lib.h"
#define N 16384
static uint8_t buf[N];
static uint32_t table[256];

uint64_t kernel(void) {
  for (uint32_t i = 0; i < 256; i++) {
    uint32_t c = i;
    for (int k = 0; k < 8; k++) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
    table[i] = c;
  }
  uint64_t s = 42;
  for (int i = 0; i < N; i++) buf[i] = (uint8_t)rnd(&s);
  uint32_t crc = 0xFFFFFFFFu;
  for (int i = 0; i < N; i++) crc = table[(crc ^ buf[i]) & 0xff] ^ (crc >> 8);
  return crc ^ 0xFFFFFFFFu;
}
