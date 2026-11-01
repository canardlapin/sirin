#include "../frame_inventory.h"
#include <assert.h>
#include <pthread.h>
#include <stdio.h>

#define WORKERS 64

typedef struct {
  _Atomic int total;
  _Atomic int devices[WORKERS];
  pthread_mutex_t mutex;
  pthread_cond_t condition;
  int shared_device;
  int ready;
  int release;
  int accepted[WORKERS];
} Fixture;

typedef struct {
  Fixture *fixture;
  int index;
} Input;

static void *worker(void *raw) {
  Input *input = raw;
  Fixture *fixture = input->fixture;
  int index = fixture->shared_device ? 0 : input->index;
  int accepted = sirin_frame_reserve(&fixture->total, &fixture->devices[index]);
  pthread_mutex_lock(&fixture->mutex);
  fixture->accepted[input->index] = accepted;
  fixture->ready++;
  pthread_cond_broadcast(&fixture->condition);
  while (!fixture->release) pthread_cond_wait(&fixture->condition, &fixture->mutex);
  pthread_mutex_unlock(&fixture->mutex);
  if (accepted) sirin_frame_release(&fixture->total, &fixture->devices[index]);
  return NULL;
}

static void concurrent(int shared_device, int expected) {
  Fixture fixture = {0};
  fixture.shared_device = shared_device;
  atomic_init(&fixture.total, 0);
  for (int i = 0; i < WORKERS; i++) atomic_init(&fixture.devices[i], 0);
  assert(pthread_mutex_init(&fixture.mutex, NULL) == 0);
  assert(pthread_cond_init(&fixture.condition, NULL) == 0);
  pthread_t threads[WORKERS];
  Input inputs[WORKERS];
  for (int i = 0; i < WORKERS; i++) {
    inputs[i] = (Input){&fixture, i};
    assert(pthread_create(&threads[i], NULL, worker, &inputs[i]) == 0);
  }
  pthread_mutex_lock(&fixture.mutex);
  while (fixture.ready < WORKERS) pthread_cond_wait(&fixture.condition, &fixture.mutex);
  int accepted = 0;
  for (int i = 0; i < WORKERS; i++) accepted += fixture.accepted[i];
  assert(accepted == expected);
  assert(atomic_load(&fixture.total) == expected);
  if (shared_device) assert(atomic_load(&fixture.devices[0]) == 3);
  fixture.release = 1;
  pthread_cond_broadcast(&fixture.condition);
  pthread_mutex_unlock(&fixture.mutex);
  for (int i = 0; i < WORKERS; i++) assert(pthread_join(threads[i], NULL) == 0);
  assert(atomic_load(&fixture.total) == 0);
  for (int i = 0; i < WORKERS; i++) assert(atomic_load(&fixture.devices[i]) == 0);
  pthread_cond_destroy(&fixture.condition);
  pthread_mutex_destroy(&fixture.mutex);
}

int main(void) {
  _Atomic int total = 0;
  _Atomic int owners[17];
  for (int i = 0; i < 17; i++) atomic_init(&owners[i], 0);
  for (int i = 0; i < 16; i++) {
    for (int j = 0; j < 3; j++) assert(sirin_frame_reserve(&total, &owners[i]));
    assert(!sirin_frame_reserve(&total, &owners[i]));
  }
  assert(atomic_load(&total) == 48);
  assert(!sirin_frame_reserve(&total, &owners[16]));
  assert(atomic_load(&owners[16]) == 0); // Refusal rolls back the owner reservation.
  sirin_frame_release(&total, &owners[0]);
  assert(sirin_frame_reserve(&total, &owners[16]));
  sirin_frame_release(&total, &owners[16]);
  for (int i = 0; i < 16; i++) {
    int remaining = i == 0 ? 2 : 3;
    for (int j = 0; j < remaining; j++) sirin_frame_release(&total, &owners[i]);
  }
  assert(atomic_load(&total) == 0);
  for (int round = 0; round < 20; round++) {
    concurrent(1, 3);
    concurrent(0, 48);
  }
  puts("frame inventory: per-device 3, global 48, refusal rollback, reuse and 40 concurrent rounds passed");
  return 0;
}
