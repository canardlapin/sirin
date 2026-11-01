#ifndef SIRIN_VIEWPORT_BUDGET_H
#define SIRIN_VIEWPORT_BUDGET_H

#include <stdatomic.h>

#define SIRIN_APP_WIDTH 4096
#define SIRIN_APP_HEIGHT 3200
#define SIRIN_APP_TILES 2048
#define SIRIN_DEVICE_OUTPUT_BYTES (3LL * SIRIN_APP_WIDTH * SIRIN_APP_HEIGHT * 4 + 1048576LL * 4)
#define SIRIN_TOTAL_OUTPUT_BYTES (2LL * SIRIN_DEVICE_OUTPUT_BYTES)

static inline int sirin_output_take(_Atomic long long *counter, long long bytes, long long limit) {
  if (bytes <= 0 || bytes > limit) return 0;
  long long value = atomic_load(counter);
  while (value <= limit - bytes) {
    if (atomic_compare_exchange_weak(counter, &value, value + bytes)) return 1;
  }
  return 0;
}

static inline int sirin_output_reserve(_Atomic long long *total, _Atomic long long *device, long long bytes) {
  if (!sirin_output_take(device, bytes, SIRIN_DEVICE_OUTPUT_BYTES)) return 0;
  if (!sirin_output_take(total, bytes, SIRIN_TOTAL_OUTPUT_BYTES)) {
    atomic_fetch_sub(device, bytes);
    return 0;
  }
  return 1;
}

static inline void sirin_output_release(_Atomic long long *total, _Atomic long long *device, long long bytes) {
  atomic_fetch_sub(device, bytes);
  atomic_fetch_sub(total, bytes);
}

#endif
