#include "../viewport_budget.h"
#include <assert.h>
#include <pthread.h>
#include <stdio.h>

#define WORKERS 64
#define UNIT (SIRIN_DEVICE_OUTPUT_BYTES / 16)

typedef struct {
  _Atomic long long total;
  _Atomic long long devices[WORKERS];
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
  int accepted = sirin_output_reserve(&fixture->total, &fixture->devices[index], UNIT);
  pthread_mutex_lock(&fixture->mutex);
  fixture->accepted[input->index] = accepted;
  fixture->ready++;
  pthread_cond_broadcast(&fixture->condition);
  while (!fixture->release) pthread_cond_wait(&fixture->condition, &fixture->mutex);
  pthread_mutex_unlock(&fixture->mutex);
  if (accepted) sirin_output_release(&fixture->total, &fixture->devices[index], UNIT);
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
  assert(atomic_load(&fixture.total) == expected * UNIT);
  if (shared_device) assert(atomic_load(&fixture.devices[0]) == 16 * UNIT);
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
  _Atomic long long total = 0;
  _Atomic long long owners[3] = {0};
  assert(SIRIN_DEVICE_OUTPUT_BYTES == 161480704LL);
  assert(SIRIN_TOTAL_OUTPUT_BYTES == 322961408LL);
  assert(!sirin_output_reserve(&total, &owners[0], 0));
  assert(!sirin_output_reserve(&total, &owners[0], -1));
  assert(!sirin_output_reserve(&total, &owners[0], SIRIN_DEVICE_OUTPUT_BYTES + 1));
  for (int i = 0; i < 2; i++) {
    assert(sirin_output_reserve(&total, &owners[i], SIRIN_DEVICE_OUTPUT_BYTES));
    assert(!sirin_output_reserve(&total, &owners[i], 1));
  }
  assert(atomic_load(&total) == SIRIN_TOTAL_OUTPUT_BYTES);
  assert(!sirin_output_reserve(&total, &owners[2], 1));
  assert(atomic_load(&owners[2]) == 0);
  sirin_output_release(&total, &owners[0], 1);
  assert(sirin_output_reserve(&total, &owners[2], 1));
  sirin_output_release(&total, &owners[2], 1);
  sirin_output_release(&total, &owners[0], SIRIN_DEVICE_OUTPUT_BYTES - 1);
  sirin_output_release(&total, &owners[1], SIRIN_DEVICE_OUTPUT_BYTES);
  assert(atomic_load(&total) == 0);
  for (int i = 0; i < 3; i++) assert(atomic_load(&owners[i]) == 0);
  for (int round = 0; round < 20; round++) {
    concurrent(1, 16);
    concurrent(0, 32);
  }
  puts("output budget: exact device/global boundaries, refusal rollback, reuse and 40 concurrent rounds passed");
  return 0;
}
