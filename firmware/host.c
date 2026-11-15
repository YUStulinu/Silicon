// Host build of one kernel, to compute the value the firmware checks against.
#include <stdio.h>
#include <stdint.h>
uint64_t kernel(void);
int main(void) { printf("%llu\n", (unsigned long long)kernel()); return 0; }
