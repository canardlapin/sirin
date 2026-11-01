#ifndef SIRIN_FRAME_INVENTORY_H
#define SIRIN_FRAME_INVENTORY_H

#include <stdatomic.h>

#define SIRIN_MAX_WINDOWS 16
#define SIRIN_FRAMES_PER_DEVICE 3
#define SIRIN_MAX_FRAMES (SIRIN_MAX_WINDOWS * SIRIN_FRAMES_PER_DEVICE)

static inline int sirin_frame_take(_Atomic int *counter, int limit) {
  int value = atomic_load(counter);
  while (value < limit) {
    if (atomic_compare_exchange_weak(counter, &value, value + 1)) return 1;
  }
  return 0;
}

static inline int sirin_frame_reserve(_Atomic int *total, _Atomic int *device) {
  if (!sirin_frame_take(device, SIRIN_FRAMES_PER_DEVICE)) return 0;
  if (!sirin_frame_take(total, SIRIN_MAX_FRAMES)) {
    atomic_fetch_sub(device, 1);
    return 0;
  }
  return 1;
}

static inline void sirin_frame_release(_Atomic int *total, _Atomic int *device) {
  atomic_fetch_sub(device, 1);
  atomic_fetch_sub(total, 1);
}

#endif
