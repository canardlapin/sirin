#ifndef SIRIN_MENUS_H
#define SIRIN_MENUS_H
#include <stdint.h>
int sirin_menus_event_key(int code);
void *sirin_menus_open(int serial,uint32_t wake_type);
void sirin_menus_close(void *raw);
int sirin_menus_begin(void *raw,int64_t revision);
int sirin_menus_add(void *raw,int parent,int node,int kind,int action,const char *label,const char *key,int mods,int enabled,int busy);
int sirin_menus_route(void *raw,const char *key,int mods);
int sirin_menus_publish(void *raw);
int sirin_menus_poll(void *raw,int64_t *out);
int sirin_menus_standard(void *raw,int kind);
int sirin_menus_reduce_motion(void);
#endif
