#ifndef SIRIN_EVENT_QUEUE_H
#define SIRIN_EVENT_QUEUE_H
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#define SIRIN_EVENT_CAPACITY 64
#define SIRIN_EVENT_TEXT_MAX 65536
#define SIRIN_EVENT_TEXT_BUDGET (1024u * 1024u)

typedef struct SirinFact {
  int32_t values[14];
  double position[4];
  uint64_t timestamp;
  char *text;
  size_t text_bytes;
} SirinFact;
typedef struct SirinEventQueue {
  SirinFact entries[SIRIN_EVENT_CAPACITY];
  unsigned head, count;
} SirinEventQueue;

/* 0 accepted; 1 full; 2 text budget; 3 allocation failure. Failure changes nothing. */
static inline int sirin_fact_push(SirinEventQueue *queue, const SirinFact *fact,
    const char *text, size_t *total_text) {
  if (queue->count == SIRIN_EVENT_CAPACITY) return 1;
  size_t bytes=0;
  if(text) {
    while(bytes<=SIRIN_EVENT_TEXT_MAX && text[bytes])bytes++;
    if(bytes>SIRIN_EVENT_TEXT_MAX)return 2;
    bytes++;
    if(*total_text>SIRIN_EVENT_TEXT_BUDGET || bytes>SIRIN_EVENT_TEXT_BUDGET-*total_text)return 2;
  }
  char *copy=NULL;
  if(text) {copy=malloc(bytes);if(!copy)return 3;memcpy(copy,text,bytes);}
  SirinFact stored=*fact;stored.text=copy;stored.text_bytes=bytes;
  queue->entries[(queue->head+queue->count)%SIRIN_EVENT_CAPACITY]=stored;
  queue->count++;*total_text+=bytes;return 0;
}
/* Transfers text ownership to the caller, which must release it exactly once. */
static inline int sirin_fact_pop(SirinEventQueue *queue, SirinFact *out) {
  if(!queue->count)return 0;
  *out=queue->entries[queue->head];memset(&queue->entries[queue->head],0,sizeof(*out));
  queue->head=(queue->head+1)%SIRIN_EVENT_CAPACITY;queue->count--;return 1;
}
static inline void sirin_fact_release(SirinFact *fact,size_t *total_text) {
  *total_text-=fact->text_bytes;free(fact->text);fact->text=NULL;fact->text_bytes=0;
}
static inline void sirin_fact_clear(SirinEventQueue *queue,size_t *total_text) {
  SirinFact fact;while(sirin_fact_pop(queue,&fact))sirin_fact_release(&fact,total_text);
}
#endif
