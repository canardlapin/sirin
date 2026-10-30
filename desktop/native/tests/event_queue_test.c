#include "../event_queue.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
  SirinEventQueue a={0},b={0};size_t bytes=0;SirinFact in={0},out={0};
  char text[]="日本語";in.timestamp=123;in.values[7]=10;in.position[0]=2.5;
  assert(sirin_fact_push(&a,&in,text,&bytes)==0);
  text[0]='X';assert(bytes==10);
  assert(sirin_fact_pop(&a,&out)==1);
  assert(strcmp(out.text,"日本語")==0 && out.timestamp==123 && out.position[0]==2.5);
  sirin_fact_release(&out,&bytes);assert(bytes==0);
  for(int round=0;round<3;round++) {
    for(unsigned i=0;i<SIRIN_EVENT_CAPACITY;i++){in.timestamp=round*1000+i;assert(sirin_fact_push(&a,&in,NULL,&bytes)==0);}
    assert(sirin_fact_push(&a,&in,"not accepted",&bytes)==1 && bytes==0);
    for(unsigned i=0;i<SIRIN_EVENT_CAPACITY;i++){assert(sirin_fact_pop(&a,&out)==1);assert(out.timestamp==round*1000+i);sirin_fact_release(&out,&bytes);}
    assert(sirin_fact_pop(&a,&out)==0);
  }
  char *large=malloc(SIRIN_EVENT_TEXT_MAX+2);assert(large);memset(large,'a',SIRIN_EVENT_TEXT_MAX+1);large[SIRIN_EVENT_TEXT_MAX+1]=0;
  assert(sirin_fact_push(&a,&in,large,&bytes)==2 && bytes==0 && a.count==0);
  large[SIRIN_EVENT_TEXT_MAX]=0;
  for(int i=0;i<15;i++)assert(sirin_fact_push(i%2?&a:&b,&in,large,&bytes)==0);
  size_t before=bytes;assert(sirin_fact_push(&a,&in,large,&bytes)==2 && bytes==before);
  sirin_fact_clear(&a,&bytes);assert(bytes>0);sirin_fact_clear(&b,&bytes);assert(bytes==0);
  free(large);puts("native event FIFO: copy lifetime, UTF-8 bytes, order/wrap, full refusal, shared budget and retirement passed");return 0;
}
