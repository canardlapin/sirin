// Private transport, no managed callbacks. Existing pointer-only ABI wrappers.
#include "../../probes/window/src/main/resources/scala-native/sirin_shim.c"
#include <stdatomic.h>
#include <stdlib.h>
#include <time.h>
#include <pthread.h>
#include <math.h>
#include "event_queue.h"
#include "menus.h"

static _Atomic int live_requests;
static int fault_stage;
static _Atomic int late_callbacks,late_objects;
int sirin_desktop_late(void) { return late_callbacks; }
int sirin_desktop_late_objects(void) { return late_objects; }
int sirin_desktop_requests(void) { return live_requests; }

// Separate request ownership survives timeout without pointing into a closed arena.
typedef struct Request {
  _Atomic int refs, done, abandoned;
  int kind, status;
  void *object;
} Request;
static _Atomic(Request*) deferred_request;
static void request_release(Request *r) {
  if (atomic_fetch_sub(&r->refs, 1) != 1) return;
  if (r->object) {
    if(atomic_load(&r->abandoned))atomic_fetch_add(&late_objects,1);
    if (r->kind == 1) wgpuAdapterRelease(r->object);
    else wgpuDeviceRelease(r->object);
  }
  atomic_fetch_sub(&live_requests,1); free(r);
}
static void finish_request(Request *r) {
  if(atomic_load(&r->abandoned))atomic_fetch_add(&late_callbacks,1);
  atomic_store(&r->done,1);request_release(r);
}
static void deliver_request(Request *r) {
  // Deterministic completion deferral, including backends that callback inline.
  if((fault_stage==9 && r->kind==1)||(fault_stage==10 && r->kind==2))atomic_store(&deferred_request,r);
  else finish_request(r);
}
static void adapter_ready(WGPURequestAdapterStatus status, WGPUAdapter adapter,
    WGPUStringView message, void *p, void *unused) {
  (void)message;(void)unused;Request *r=p;r->status=status;r->object=adapter;deliver_request(r);
}
static void device_ready(WGPURequestDeviceStatus status, WGPUDevice device,
    WGPUStringView message, void *p, void *unused) {
  (void)message;(void)unused;Request *r=p;r->status=status;r->object=device;deliver_request(r);
}
static Request *request_new(int kind) {
  Request *r = calloc(1, sizeof(*r));
  if (r) { atomic_fetch_add(&live_requests,1); atomic_init(&r->refs, 2); atomic_init(&r->done, 0); atomic_init(&r->abandoned,0); r->kind = kind; }
  return r;
}
static uint64_t nanos(void) {
  struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
  return (uint64_t)t.tv_sec * 1000000000u + (uint64_t)t.tv_nsec;
}
static void *await_request(WGPUInstance instance, Request *r) {
  uint64_t deadline = nanos() + 5000000000u;
  if((fault_stage==9 && r->kind==1) || (fault_stage==10 && r->kind==2))deadline=0;
  while (!atomic_load(&r->done) && nanos() < deadline) wgpuInstanceProcessEvents(instance);
  void *result = NULL;
  if (atomic_load(&r->done) && r->status == 1) { result = r->object; r->object = NULL; }
  if(!atomic_load(&r->done))atomic_store(&r->abandoned,1);
  request_release(r);
  return result;
}
void bgpu_close(void *raw);
void *sirin_ax_open(SDL_Window*,uint32_t);
void sirin_ax_close(void*);
int sirin_ax_begin(void*,int64_t);
int sirin_ax_field(void*,int64_t,int,const char*,const char*,const char*,int,int,int,int,const double*,const double*,int);
int sirin_ax_publish(void*);
int sirin_ax_poll(void*,int64_t*,char*,int);
int sirin_editor_ime_control(void*,const char*,int,int,int);
int sirin_editor_activate(void*);
#define SIRIN_MAX_WINDOWS 16
typedef struct Desktop Desktop;
typedef struct DesktopApp {
  pthread_t owner; int explicit_owner, count, serial; uint32_t wake_type;
  Desktop *windows[SIRIN_MAX_WINDOWS]; size_t queued_text;
  uint64_t stale_events, refused_events;
  void *menus;
} DesktopApp;
struct Desktop {
  SDL_Window *window; SDL_MetalView metal;
  WGPUInstance instance; WGPUSurface surface; WGPUAdapter adapter;
  WGPUDevice device; WGPUQueue queue; WGPURenderPipeline blit; WGPUTextureFormat format;
  uint32_t width, height, wake_type, window_id;
  pthread_t owner; int configured, copy_dst; void *batch;
  char text[65537]; void *accessibility;
  DesktopApp *app; SirinEventQueue events;
};
static _Atomic int live_windows,live_apps;
static DesktopApp *application;
static int app_serial;
static int app_owner(DesktopApp *app) {
  return pthread_main_np() && app && app==application && pthread_equal(app->owner,pthread_self());
}
static DesktopApp *app_create(int explicit_owner,char *error) {
  if(!pthread_main_np() || application || app_serial==INT32_MAX) {
    snprintf(error,512,"application requires process main thread and no existing context");return NULL;
  }
  DesktopApp *app=calloc(1,sizeof(*app));
  if(!app){snprintf(error,512,"application allocation failed");return NULL;}
  if(!SDL_Init(SDL_INIT_VIDEO)){snprintf(error,512,"SDL initialization failed: %s",SDL_GetError());SDL_Quit();free(app);return NULL;}
  app->wake_type=SDL_RegisterEvents(1);
  if(!app->wake_type){snprintf(error,512,"SDL wake registration failed");SDL_Quit();free(app);return NULL;}
  app->owner=pthread_self();app->explicit_owner=explicit_owner;app->serial=++app_serial;
  app->menus=sirin_menus_open(app->serial,app->wake_type);
  if(!app->menus){snprintf(error,512,"native menu context failed");SDL_Quit();free(app);return NULL;}
  application=app;live_apps=1;return app;
}
static void app_destroy(DesktopApp *app) {
  sirin_menus_close(app->menus);app->menus=NULL;
  SDL_Quit();application=NULL;live_apps=0;free(app);
}
int sirin_desktop_app_open(void **out,int32_t *info,char *error) {
  *out=NULL;DesktopApp *app=app_create(1,error);if(!app)return 1;
  info[0]=app->serial;info[1]=(int32_t)app->wake_type;*out=app;return 0;
}
int sirin_desktop_app_close(void *raw) {
  DesktopApp *app=raw;if(!app_owner(app) || app->count || app->queued_text)return 1;
  app_destroy(app);return 0;
}
int sirin_desktop_menu_event_key(int code){return sirin_menus_event_key(code);}
int sirin_desktop_menu_begin(void *raw,int64_t revision){DesktopApp *a=raw;return app_owner(a)?sirin_menus_begin(a->menus,revision):1;}
int sirin_desktop_menu_add(void *raw,int parent,int node,int kind,int action,const char *label,const char *key,int mods,int enabled,int busy){DesktopApp *a=raw;return app_owner(a)?sirin_menus_add(a->menus,parent,node,kind,action,label,key,mods,enabled,busy):1;}
int sirin_desktop_menu_route(void *raw,const char *key,int mods){DesktopApp *a=raw;return app_owner(a)?sirin_menus_route(a->menus,key,mods):1;}
int sirin_desktop_menu_publish(void *raw){DesktopApp *a=raw;return app_owner(a)?sirin_menus_publish(a->menus):1;}
int sirin_desktop_menu_poll(void *raw,int64_t *out){DesktopApp *a=raw;return app_owner(a)?sirin_menus_poll(a->menus,out):-1;}
int sirin_desktop_menu_standard(void *raw,int kind){DesktopApp *a=raw;return app_owner(a)?sirin_menus_standard(a->menus,kind):1;}
int sirin_desktop_app_live(void) {return live_apps;}
int sirin_desktop_reduce_motion(void) {return sirin_menus_reduce_motion();}
int sirin_desktop_app_wake(int serial,int wake_type) {
  SDL_Event event;SDL_zero(event);event.type=(uint32_t)wake_type;event.user.code=serial;
  return SDL_PushEvent(&event)?0:1;
}
int sirin_desktop_app_stats(void *raw,uint64_t *out) {
  DesktopApp *app=raw;if(!app_owner(app))return 1;
  out[0]=(uint64_t)app->count;out[1]=app->queued_text;out[2]=app->stale_events;out[3]=app->refused_events;return 0;
}
int sirin_desktop_fault(int stage) {
  if (!pthread_main_np() || live_requests || (live_windows && (!application || !application->explicit_owner)) || stage<0 || stage>10) return 1;
  fault_stage=stage;return 0;
}
int sirin_desktop_live(void) { return live_windows; }
int sirin_desktop_first_thread(void) { return pthread_main_np(); }
static int owner(Desktop *d) {
  if(!pthread_main_np() || !d || !app_owner(application))return 0;
  for(int i=0;i<SIRIN_MAX_WINDOWS;i++)if(application->windows[i]==d)return 1;
  return 0;
}
static void destroy(Desktop *d) {
  if(d->accessibility){sirin_ax_close(d->accessibility);d->accessibility=NULL;}
  // Drain late callbacks after caller ownership was relinquished, while instance lives.
  if(d->instance && atomic_load(&live_requests)) {
    uint64_t deadline=nanos()+5000000000u;
    while(atomic_load(&live_requests) && nanos()<deadline){
      Request *late=atomic_exchange(&deferred_request,NULL);
      if(late)finish_request(late);
      else wgpuInstanceProcessEvents(d->instance);
    }
  }
  if (d->surface && d->configured) wgpuSurfaceUnconfigure(d->surface);
  if (d->blit) wgpuRenderPipelineRelease(d->blit);
  if (d->queue) wgpuQueueRelease(d->queue);
  if (d->device) wgpuDeviceRelease(d->device);
  if (d->surface) {wgpuSurfaceRelease(d->surface);d->surface=NULL;}
  if (d->batch) bgpu_close(d->batch);
  if (d->adapter) wgpuAdapterRelease(d->adapter);
  if (d->surface) wgpuSurfaceRelease(d->surface);
  if (d->instance) wgpuInstanceRelease(d->instance);
  if (d->metal) SDL_Metal_DestroyView(d->metal);
  if (d->window) SDL_DestroyWindow(d->window);
  DesktopApp *app=d->app;
  sirin_fact_clear(&d->events,&app->queued_text);
  for(int i=0;i<SIRIN_MAX_WINDOWS;i++)if(app->windows[i]==d)app->windows[i]=NULL;
  app->count--;live_windows--;free(d);
  if(!app->explicit_owner && !app->count)app_destroy(app);
}
// Development framebuffer policy: <=1280x800 physical pixels, including DPI changes.
static void constrain_window(Desktop *d) {
  int w=0,h=0,pw=0,ph=0;SDL_GetWindowSize(d->window,&w,&h);SDL_GetWindowSizeInPixels(d->window,&pw,&ph);
  if(w>0&&h>0&&pw>0&&ph>0){
    int mw=(int)(1280.0*w/pw),mh=(int)(800.0*h/ph);
    int oldw=0,oldh=0;SDL_GetWindowMaximumSize(d->window,&oldw,&oldh);
    if(mw!=oldw||mh!=oldh)SDL_SetWindowMaximumSize(d->window,mw,mh);
    if(w>mw||h>mh)SDL_SetWindowSize(d->window,w>mw?mw:w,h>mh?mh:h);
  }
}
int sirin_desktop_open(const char *title, int width, int height, void **out, char *error) {
  *out = NULL;
  if (!pthread_main_np() || width <= 0 || height <= 0 || (application && application->count>=SIRIN_MAX_WINDOWS)) {
    snprintf(error,512,"open requires process main thread, positive dimensions and at most 16 windows"); return 1;
  }
  DesktopApp *app=application?application:app_create(0,error);if(!app)return 2;
  Desktop *d = calloc(1, sizeof(*d));
  if (!d) { snprintf(error,512,"allocation failed");if(!app->explicit_owner && !app->count)app_destroy(app);return 2; }
  d->owner = pthread_self();d->app=app;
  for(int i=0;i<SIRIN_MAX_WINDOWS;i++)if(!app->windows[i]){app->windows[i]=d;break;}
  app->count++;live_windows++;
  if (fault_stage==1) goto fail;
  d->window = SDL_CreateWindow(title,width,height,SDL_WINDOW_RESIZABLE|SDL_WINDOW_HIGH_PIXEL_DENSITY|SDL_WINDOW_METAL);
  if (!d->window) goto fail;
  if (fault_stage==2) goto fail;
  constrain_window(d);
  d->metal = SDL_Metal_CreateView(d->window); if (!d->metal) goto fail;
  if (fault_stage==3) goto fail;
  d->instance = wgpuCreateInstance(NULL); if (!d->instance) goto fail;
  if (fault_stage==4) goto fail;
  WGPUSurfaceSourceMetalLayer layer = WGPU_SURFACE_SOURCE_METAL_LAYER_INIT;
  layer.layer = SDL_Metal_GetLayer(d->metal);
  WGPUSurfaceDescriptor sd = WGPU_SURFACE_DESCRIPTOR_INIT; sd.nextInChain = &layer.chain;
  d->surface = wgpuInstanceCreateSurface(d->instance,&sd); if (!d->surface) goto fail;
  if (fault_stage==5) goto fail;
  Request *r = request_new(1); if (!r) goto fail;
  WGPURequestAdapterOptions opts = WGPU_REQUEST_ADAPTER_OPTIONS_INIT;
  opts.featureLevel = WGPUFeatureLevel_Core; opts.compatibleSurface = d->surface;
  WGPURequestAdapterCallbackInfo ac = WGPU_REQUEST_ADAPTER_CALLBACK_INFO_INIT;
  ac.mode = WGPUCallbackMode_AllowProcessEvents; ac.callback = adapter_ready; ac.userdata1 = r;
  wgpuInstanceRequestAdapter(d->instance,&opts,ac);
  d->adapter = await_request(d->instance,r); if (!d->adapter) goto fail;
  if (fault_stage==6) goto fail;
  r = request_new(2); if (!r) goto fail;
  WGPUDeviceDescriptor dd = WGPU_DEVICE_DESCRIPTOR_INIT;
  dd.uncapturedErrorCallbackInfo.callback = sirin_on_uncaptured_error;
  WGPURequestDeviceCallbackInfo dc = WGPU_REQUEST_DEVICE_CALLBACK_INFO_INIT;
  dc.mode = WGPUCallbackMode_AllowProcessEvents; dc.callback = device_ready; dc.userdata1 = r;
  wgpuAdapterRequestDevice(d->adapter,&dd,dc);
  d->device = await_request(d->instance,r); if (!d->device) goto fail;
  if (fault_stage==7) goto fail;
  d->queue = wgpuDeviceGetQueue(d->device);
  if (fault_stage==8) goto fail;
  WGPUSurfaceCapabilities caps = WGPU_SURFACE_CAPABILITIES_INIT;
  if (wgpuSurfaceGetCapabilities(d->surface,d->adapter,&caps) != WGPUStatus_Success) goto fail;
  d->copy_dst = (caps.usages & WGPUTextureUsage_CopyDst) != 0;
  d->format = caps.formatCount ? caps.formats[0] : WGPUTextureFormat_Undefined;
  for (size_t i=0;i<caps.formatCount;i++) if (caps.formats[i]==WGPUTextureFormat_BGRA8UnormSrgb) d->format=caps.formats[i];
  wgpuSurfaceCapabilitiesFreeMembers(caps);
  if (!d->format) goto fail;
  d->window_id = SDL_GetWindowID(d->window);
  d->wake_type = app->wake_type;
  *out = d; return 0;
fail:
  snprintf(error,512,"native open failed: %s",SDL_GetError());destroy(d);return 3;
}
int sirin_desktop_close(void *raw) {
  Desktop *d = raw; if (!owner(d)) return 1;
  destroy(d);return 0;
}
// Native facts only. Coordinates remain logical window units; no managed callbacks.
uint64_t sirin_desktop_ticks(void) { return SDL_GetTicksNS(); }
static int logical_key(SDL_Keycode key) {
  switch(key){
    case SDLK_TAB:return 1;case SDLK_RETURN:case SDLK_KP_ENTER:return 2;case SDLK_SPACE:return 3;case SDLK_ESCAPE:return 4;
    case SDLK_UP:return 5;case SDLK_DOWN:return 6;case SDLK_PAGEUP:return 7;case SDLK_PAGEDOWN:return 8;
    case SDLK_HOME:return 9;case SDLK_END:return 10;
    case SDLK_LEFT:return 11;case SDLK_RIGHT:return 12;case SDLK_BACKSPACE:return 13;case SDLK_DELETE:return 14;
    default:return key>=32 && key<=65535 ? 1000+(int)key:0;
  }
}
static int modifiers(SDL_Keymod mods) {
  return ((mods&SDL_KMOD_GUI)?1:0)|((mods&SDL_KMOD_SHIFT)?2:0)|((mods&SDL_KMOD_ALT)?4:0)|((mods&SDL_KMOD_CTRL)?8:0);
}
static int enqueue_fact(Desktop *d,const SirinFact *fact,const char *text) {
  int status=sirin_fact_push(&d->events,fact,text,&d->app->queued_text);
  if(status)d->app->refused_events++;
  return status?-2:0;
}
/* One global SDL pump. All managed clients subsequently poll their own FIFO. */
int sirin_desktop_app_wait(void *raw,int timeout,int32_t *out) {
  DesktopApp *app=raw;if(!app_owner(app) || timeout < -1)return -1;
  memset(out,0,3*sizeof(*out));SDL_Event e;
  if(!SDL_WaitEventTimeout(&e,timeout))return 0;
  if(e.type==SDL_EVENT_QUIT) {
    out[0]=1;
    if(!app->explicit_owner)for(int i=0;i<SIRIN_MAX_WINDOWS;i++)if(app->windows[i]) {
      SirinFact fact={0};fact.values[0]=1;fact.timestamp=e.common.timestamp;
      if(enqueue_fact(app->windows[i],&fact,NULL))return -2;
    }
    return 1;
  }
  if(e.type==app->wake_type && !e.user.windowID) {
    if(e.user.code==app->serial)out[1]=1;else app->stale_events++;
    return 1;
  }
  uint32_t id=0;
  if(e.type==app->wake_type)id=e.user.windowID;
  else if(e.type>=SDL_EVENT_WINDOW_FIRST && e.type<=SDL_EVENT_WINDOW_LAST)id=e.window.windowID;
  else if(e.type==SDL_EVENT_KEY_DOWN || e.type==SDL_EVENT_KEY_UP)id=e.key.windowID;
  else if(e.type==SDL_EVENT_MOUSE_BUTTON_DOWN || e.type==SDL_EVENT_MOUSE_BUTTON_UP)id=e.button.windowID;
  else if(e.type==SDL_EVENT_MOUSE_MOTION)id=e.motion.windowID;
  else if(e.type==SDL_EVENT_MOUSE_WHEEL)id=e.wheel.windowID;
  else if(e.type==SDL_EVENT_TEXT_INPUT)id=e.text.windowID;
  else if(e.type==SDL_EVENT_TEXT_EDITING)id=e.edit.windowID;
  else return 1;
  Desktop *d=NULL;
  for(int i=0;i<SIRIN_MAX_WINDOWS;i++)if(app->windows[i] && app->windows[i]->window_id==id){d=app->windows[i];break;}
  if(!d){app->stale_events++;return 1;}
  out[2]=(int32_t)id;SirinFact fact={0};fact.timestamp=e.common.timestamp;const char *text=NULL;
  if(e.type==SDL_EVENT_WINDOW_CLOSE_REQUESTED)fact.values[0]|=1;
  if(e.type==SDL_EVENT_WINDOW_FOCUS_GAINED || e.type==SDL_EVENT_WINDOW_EXPOSED || e.type==SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED || e.type==SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED)fact.values[0]|=2;
  if(e.type==app->wake_type)fact.values[0]|=4;
  if(e.type==SDL_EVENT_KEY_DOWN || e.type==SDL_EVENT_KEY_UP){
    fact.values[7]=e.type==SDL_EVENT_KEY_DOWN?1:2;fact.values[8]=logical_key(e.key.scancode==SDL_SCANCODE_UNKNOWN?e.key.key:SDL_GetKeyFromScancode(e.key.scancode,SDL_KMOD_NONE,false));fact.values[9]=modifiers(e.key.mod);fact.values[11]=e.key.repeat;
  }
  if(e.type==SDL_EVENT_MOUSE_BUTTON_DOWN || e.type==SDL_EVENT_MOUSE_BUTTON_UP){
    fact.values[7]=e.type==SDL_EVENT_MOUSE_BUTTON_DOWN?3:4;fact.values[10]=e.button.button;fact.values[12]=e.button.clicks;
    fact.values[9]=modifiers(SDL_GetModState());fact.position[0]=e.button.x;fact.position[1]=e.button.y;
  }
  if(e.type==SDL_EVENT_MOUSE_WHEEL){
    fact.values[7]=6;fact.position[0]=e.wheel.mouse_x;fact.position[1]=e.wheel.mouse_y;
    double sign=e.wheel.direction==SDL_MOUSEWHEEL_FLIPPED?-1:1;fact.position[2]=sign*e.wheel.x;fact.position[3]=sign*e.wheel.y;
  }
  if(e.type==SDL_EVENT_MOUSE_MOTION){fact.values[7]=5;fact.values[10]=(e.motion.state&SDL_BUTTON_LMASK)?1:0;fact.position[0]=e.motion.x;fact.position[1]=e.motion.y;fact.values[9]=modifiers(SDL_GetModState());}
  if(e.type==SDL_EVENT_TEXT_INPUT){text=e.text.text;fact.values[7]=10;}
  if(e.type==SDL_EVENT_TEXT_EDITING){text=e.edit.text;fact.values[7]=11;fact.values[8]=e.edit.start;fact.values[9]=e.edit.length;}
  if(e.type==SDL_EVENT_WINDOW_FOCUS_GAINED)fact.values[0]|=8;
  if(e.type==SDL_EVENT_WINDOW_FOCUS_LOST){fact.values[0]|=16;fact.values[7]=7;}
  return enqueue_fact(d,&fact,text)?-2:1;
}
static void geometry(Desktop *d,int32_t *out,uint64_t *times) {
  constrain_window(d);
  SDL_GetWindowSizeInPixels(d->window,&out[1],&out[2]);out[3]=d->window_id;out[4]=d->wake_type;
  SDL_GetWindowSize(d->window,&out[5],&out[6]);times[1]=SDL_GetTicksNS();
}
int sirin_desktop_geometry(void *raw,int32_t *out,double *position,uint64_t *times) {
  Desktop *d=raw;if(!owner(d))return -1;
  memset(out,0,14*sizeof(*out));memset(position,0,4*sizeof(*position));times[0]=0;d->text[0]=0;
  geometry(d,out,times);return 0;
}
int sirin_desktop_poll(void *raw,int32_t *out,double *position,uint64_t *times) {
  Desktop *d=raw;if(!owner(d))return -1;
  memset(out,0,14*sizeof(*out));memset(position,0,4*sizeof(*position));times[0]=0;d->text[0]=0;
  SirinFact fact={0};int found=sirin_fact_pop(&d->events,&fact);
  if(found){memcpy(out,fact.values,sizeof(fact.values));memcpy(position,fact.position,sizeof(fact.position));times[0]=fact.timestamp;
    if(fact.text)memcpy(d->text,fact.text,fact.text_bytes);
    sirin_fact_release(&fact,&d->app->queued_text);
  }
  geometry(d,out,times);return found;
}
int sirin_desktop_wait(void *raw,int timeout,int32_t *out,double *position,uint64_t *times) {
  Desktop *d=raw;if(!owner(d) || timeout < -1)return -1;
  if(d->events.count)return sirin_desktop_poll(raw,out,position,times);
  int32_t app_fact[3];int status=sirin_desktop_app_wait(d->app,timeout,app_fact);
  if(status<0)return status;
  return sirin_desktop_poll(raw,out,position,times);
}
int sirin_desktop_hide(void *raw) {
  Desktop *d=raw;return owner(d) && SDL_HideWindow(d->window)?0:1;
}
int sirin_desktop_title_get(void *raw,char *out,int capacity) {
  Desktop *d=raw;if(!owner(d) || capacity<=0)return 1;
  const char *title=SDL_GetWindowTitle(d->window);size_t n=strlen(title);
  if(n>=(size_t)capacity)return 2;memcpy(out,title,n+1);return 0;
}
int sirin_desktop_app_request_quit(void *raw) {
  if(!app_owner(raw))return 1;
  SDL_Event event;SDL_zero(event);event.type=SDL_EVENT_QUIT;return SDL_PushEvent(&event)?0:1;
}
int sirin_desktop_title(void *raw,const char *title) {
  Desktop *d=raw;return owner(d) && title && SDL_SetWindowTitle(d->window,title)?0:1;
}
// Development/test injection enters the same SDL event queue and decoding path.
int sirin_desktop_inject(void *raw,int kind,int key,int mods,double x,double y,double delta) {
  Desktop *d=raw;if(!owner(d))return 1;
  SDL_Event e;SDL_zero(e);e.common.timestamp=SDL_GetTicksNS();
  if(kind==1 || kind==2){
    const SDL_Keycode keys[]={0,SDLK_TAB,SDLK_RETURN,SDLK_SPACE,SDLK_ESCAPE,SDLK_UP,SDLK_DOWN,SDLK_PAGEUP,SDLK_PAGEDOWN,SDLK_HOME,SDLK_END,SDLK_LEFT,SDLK_RIGHT,SDLK_BACKSPACE,SDLK_DELETE};
    if(key<0 || (key>=15 && (key<1000 || key>=66536)))return 1;
    e.type=kind==1?SDL_EVENT_KEY_DOWN:SDL_EVENT_KEY_UP;e.key.windowID=d->window_id;e.key.key=key>=1000 ? (SDL_Keycode)(key-1000):keys[key];
    e.key.mod=((mods&1)?SDL_KMOD_GUI:0)|((mods&2)?SDL_KMOD_SHIFT:0)|((mods&4)?SDL_KMOD_ALT:0)|((mods&8)?SDL_KMOD_CTRL:0);
  } else if(kind==3 || kind==4){
    e.type=kind==3?SDL_EVENT_MOUSE_BUTTON_DOWN:SDL_EVENT_MOUSE_BUTTON_UP;e.button.windowID=d->window_id;e.button.button=SDL_BUTTON_LEFT;e.button.clicks=1;e.button.x=x;e.button.y=y;
  } else if(kind==6){
    e.type=SDL_EVENT_MOUSE_WHEEL;e.wheel.windowID=d->window_id;e.wheel.mouse_x=x;e.wheel.mouse_y=y;e.wheel.y=delta;
  } else if(kind==8){e.type=SDL_EVENT_WINDOW_CLOSE_REQUESTED;e.window.windowID=d->window_id;
  } else if(kind==7){e.type=SDL_EVENT_WINDOW_FOCUS_LOST;e.window.windowID=d->window_id;}
  else return 1;
  return SDL_PushEvent(&e)?0:1;
}
int sirin_desktop_wake(int window_id, int wake_type) {
  SDL_Event event; SDL_zero(event);event.type=(uint32_t)wake_type;event.user.windowID=(uint32_t)window_id;
  return SDL_PushEvent(&event) ? 0 : 1;
}
int sirin_desktop_resize(void *raw,int w,int h) {
  Desktop *d=raw;return owner(d) && w>0 && h>0 && SDL_SetWindowSize(d->window,w,h) ? 0:1;
}
static int acquire(Desktop *d, WGPUSurfaceTexture *st) {
  if (!owner(d)) return -1;
  int w=0,h=0;SDL_GetWindowSizeInPixels(d->window,&w,&h);if(w<=0||h<=0)return 0;
  if (!d->configured || d->width!=(uint32_t)w || d->height!=(uint32_t)h) {
    WGPUSurfaceConfiguration cfg=WGPU_SURFACE_CONFIGURATION_INIT;
    cfg.device=d->device;cfg.format=d->format;cfg.usage=WGPUTextureUsage_RenderAttachment | (d->copy_dst ? WGPUTextureUsage_CopyDst : 0);
    cfg.width=w;cfg.height=h;cfg.presentMode=WGPUPresentMode_Fifo;cfg.alphaMode=WGPUCompositeAlphaMode_Auto;
    wgpuSurfaceConfigure(d->surface,&cfg);d->width=w;d->height=h;d->configured=1;
  }
  wgpuSurfaceGetCurrentTexture(d->surface,st);
  if(st->status!=WGPUSurfaceGetCurrentTextureStatus_SuccessOptimal && st->status!=WGPUSurfaceGetCurrentTextureStatus_SuccessSuboptimal) {
    if(st->texture)wgpuTextureRelease(st->texture);
    if(st->status==WGPUSurfaceGetCurrentTextureStatus_Outdated || st->status==WGPUSurfaceGetCurrentTextureStatus_Lost)d->configured=0;
    return 0;
  }
  return 1;
}
int sirin_desktop_clear(void *raw,double red,double green,double blue) {
  Desktop *d=raw;WGPUSurfaceTexture st=WGPU_SURFACE_TEXTURE_INIT;int status=acquire(d,&st);if(status!=1)return status;
  WGPUTextureView view=wgpuTextureCreateView(st.texture,NULL);
  WGPUCommandEncoder encoder=wgpuDeviceCreateCommandEncoder(d->device,NULL);
  WGPURenderPassColorAttachment color=WGPU_RENDER_PASS_COLOR_ATTACHMENT_INIT;
  color.view=view;color.loadOp=WGPULoadOp_Clear;color.storeOp=WGPUStoreOp_Store;
  color.clearValue=(WGPUColor){red,green,blue,1};
  WGPURenderPassDescriptor pass=WGPU_RENDER_PASS_DESCRIPTOR_INIT;pass.colorAttachmentCount=1;pass.colorAttachments=&color;
  WGPURenderPassEncoder rp=wgpuCommandEncoderBeginRenderPass(encoder,&pass);
  wgpuRenderPassEncoderEnd(rp);wgpuRenderPassEncoderRelease(rp);
  WGPUCommandBuffer command=wgpuCommandEncoderFinish(encoder,NULL);wgpuQueueSubmit(d->queue,1,&command);
  int result=wgpuSurfacePresent(d->surface)==WGPUStatus_Success ? 1:-1;
  wgpuCommandBufferRelease(command);wgpuCommandEncoderRelease(encoder);wgpuTextureViewRelease(view);wgpuTextureRelease(st.texture);
  return result;
}

// Presentation-only fullscreen copy: surfaces need not support CopyDst.
static int blit_pipeline(Desktop *d) {
  if(d->blit)return 0;
  const char *code="@group(0) @binding(0) var image: texture_2d<f32>; "
    "@vertex fn vs(@builtin(vertex_index) i:u32)->@builtin(position) vec4<f32>{"
    "var p=array<vec2<f32>,3>(vec2<f32>(-1,-1),vec2<f32>(3,-1),vec2<f32>(-1,3));return vec4<f32>(p[i],0,1);} "
    "@fragment fn fs(@builtin(position) p:vec4<f32>)->@location(0) vec4<f32>{return textureLoad(image,vec2<i32>(p.xy),0);}";
  WGPUShaderSourceWGSL wgsl=WGPU_SHADER_SOURCE_WGSL_INIT;wgsl.code=(WGPUStringView){code,WGPU_STRLEN};
  WGPUShaderModuleDescriptor sd=WGPU_SHADER_MODULE_DESCRIPTOR_INIT;sd.nextInChain=&wgsl.chain;
  WGPUShaderModule module=wgpuDeviceCreateShaderModule(d->device,&sd);if(!module)return 1;
  WGPUColorTargetState color=WGPU_COLOR_TARGET_STATE_INIT;color.format=d->format;color.writeMask=WGPUColorWriteMask_All;
  WGPUFragmentState fragment=WGPU_FRAGMENT_STATE_INIT;fragment.module=module;fragment.entryPoint=(WGPUStringView){"fs",2};fragment.targetCount=1;fragment.targets=&color;
  WGPURenderPipelineDescriptor pd=WGPU_RENDER_PIPELINE_DESCRIPTOR_INIT;
  pd.vertex.module=module;pd.vertex.entryPoint=(WGPUStringView){"vs",2};pd.fragment=&fragment;
  pd.primitive.topology=WGPUPrimitiveTopology_TriangleList;pd.multisample.count=1;
  d->blit=wgpuDeviceCreateRenderPipeline(d->device,&pd);wgpuShaderModuleRelease(module);return d->blit?0:1;
}
int sirin_desktop_pixels(void *raw,const unsigned char *rgba,int width,int height) {
  Desktop *d=raw;if(!owner(d)||width<=0||height<=0||width>1280||height>800)return -1;
  WGPUSurfaceTexture st=WGPU_SURFACE_TEXTURE_INIT;int status=acquire(d,&st);if(status!=1)return status;
  if(d->width!=(uint32_t)width||d->height!=(uint32_t)height){wgpuTextureRelease(st.texture);return 0;}
  if(blit_pipeline(d)){wgpuTextureRelease(st.texture);return -1;}
  WGPUTextureDescriptor td=WGPU_TEXTURE_DESCRIPTOR_INIT;td.dimension=WGPUTextureDimension_2D;
  td.size=(WGPUExtent3D){(uint32_t)width,(uint32_t)height,1};td.mipLevelCount=1;td.sampleCount=1;
  td.format=WGPUTextureFormat_RGBA8UnormSrgb;td.usage=WGPUTextureUsage_CopyDst|WGPUTextureUsage_TextureBinding;
  WGPUTexture texture=wgpuDeviceCreateTexture(d->device,&td);if(!texture){wgpuTextureRelease(st.texture);return -1;}
  WGPUTexelCopyTextureInfo dst=WGPU_TEXEL_COPY_TEXTURE_INFO_INIT;dst.texture=texture;
  WGPUTexelCopyBufferLayout layout=WGPU_TEXEL_COPY_BUFFER_LAYOUT_INIT;layout.bytesPerRow=(uint32_t)width*4;layout.rowsPerImage=height;
  WGPUExtent3D extent={(uint32_t)width,(uint32_t)height,1};
  wgpuQueueWriteTexture(d->queue,&dst,rgba,(size_t)width*height*4,&layout,&extent);
  WGPUTextureView uploaded=wgpuTextureCreateView(texture,NULL),view=wgpuTextureCreateView(st.texture,NULL);
  WGPUBindGroupLayout bindings=wgpuRenderPipelineGetBindGroupLayout(d->blit,0);
  WGPUBindGroupEntry entry=WGPU_BIND_GROUP_ENTRY_INIT;entry.binding=0;entry.textureView=uploaded;
  WGPUBindGroupDescriptor bd=WGPU_BIND_GROUP_DESCRIPTOR_INIT;bd.layout=bindings;bd.entryCount=1;bd.entries=&entry;
  WGPUBindGroup group=wgpuDeviceCreateBindGroup(d->device,&bd);
  WGPUCommandEncoder encoder=wgpuDeviceCreateCommandEncoder(d->device,NULL);
  WGPURenderPassColorAttachment color=WGPU_RENDER_PASS_COLOR_ATTACHMENT_INIT; color.view=view;color.loadOp=WGPULoadOp_Clear;color.storeOp=WGPUStoreOp_Store;color.clearValue=(WGPUColor){0,0,0,1};
  WGPURenderPassDescriptor pass=WGPU_RENDER_PASS_DESCRIPTOR_INIT;pass.colorAttachmentCount=1;pass.colorAttachments=&color;
  WGPURenderPassEncoder rp=wgpuCommandEncoderBeginRenderPass(encoder,&pass);
  wgpuRenderPassEncoderSetPipeline(rp,d->blit);wgpuRenderPassEncoderSetBindGroup(rp,0,group,0,NULL);wgpuRenderPassEncoderDraw(rp,3,1,0,0);wgpuRenderPassEncoderEnd(rp);wgpuRenderPassEncoderRelease(rp);
  WGPUCommandBuffer command=wgpuCommandEncoderFinish(encoder,NULL);wgpuQueueSubmit(d->queue,1,&command);
  int result=wgpuSurfacePresent(d->surface)==WGPUStatus_Success?1:-1;
  wgpuCommandBufferRelease(command);wgpuCommandEncoderRelease(encoder);wgpuBindGroupRelease(group);wgpuBindGroupLayoutRelease(bindings);
  wgpuTextureViewRelease(uploaded);wgpuTextureViewRelease(view);wgpuTextureRelease(texture);wgpuTextureRelease(st.texture);return result;
}

// Reuse the graphics track transport and existing text/Unicode ABIs.
#include "../../text-native/src/main/resources/scala-native/sirin_text.c"
#include "../../probes/unicode/src/main/resources/scala-native/sirin_unicode.c"
#include "../../probes/render/src/main/resources/scala-native/sirin_gpu.c"

// Host batch composition reuses the shared retained program/raster algorithm.
// All compute/copy commands submit together; one staging map owns native state.
int sirin_desktop_gpu_batch(void **raw,int count,unsigned char *out) {
  if(count<1 || count>64)return 4;
  SgpuProgram **programs=(SgpuProgram**)raw;Sgpu *s=programs[0]->owner;
  uint64_t offsets[64],strides[64],bytes=0,packed=0;
  for(int i=0;i<count;i++){
    SgpuProgram *p=programs[i];if(p->owner!=s)return 4;
    offsets[i]=bytes;strides[i]=((uint64_t)p->width*4+255)&~255ULL;
    bytes+=strides[i]*p->height;packed+=(uint64_t)p->width*p->height*4;
  }
  if(bytes>8388608 || packed>4194304 || atomic_load(&s->error))return 4;
  WGPUBufferDescriptor bd=WGPU_BUFFER_DESCRIPTOR_INIT;bd.size=bytes;bd.usage=WGPUBufferUsage_CopyDst|WGPUBufferUsage_MapRead;
  WGPUBuffer buffer=wgpuDeviceCreateBuffer(s->device,&bd);if(!buffer)return 1;
  WGPUCommandEncoder encoder=wgpuDeviceCreateCommandEncoder(s->device,NULL);
  WGPUComputePassEncoder pass=wgpuCommandEncoderBeginComputePass(encoder,NULL);
  wgpuComputePassEncoderSetPipeline(pass,s->pipeline);
  for(int i=0;i<count;i++){
    SgpuProgram*p=programs[i];wgpuComputePassEncoderSetBindGroup(pass,0,p->group,0,NULL);
    wgpuComputePassEncoderDispatchWorkgroups(pass,(p->width+7)/8,(p->height+7)/8,1);
  }
  wgpuComputePassEncoderEnd(pass);wgpuComputePassEncoderRelease(pass);
  for(int i=0;i<count;i++){
    SgpuProgram*p=programs[i];WGPUTexelCopyTextureInfo src=WGPU_TEXEL_COPY_TEXTURE_INFO_INIT;src.texture=p->texture;
    WGPUTexelCopyBufferInfo dst=WGPU_TEXEL_COPY_BUFFER_INFO_INIT;dst.buffer=buffer;dst.layout.offset=offsets[i];dst.layout.bytesPerRow=strides[i];dst.layout.rowsPerImage=p->height;
    WGPUExtent3D extent={(uint32_t)p->width,(uint32_t)p->height,1};wgpuCommandEncoderCopyTextureToBuffer(encoder,&src,&dst,&extent);
  }
  WGPUCommandBuffer command=wgpuCommandEncoderFinish(encoder,NULL);wgpuQueueSubmit(s->queue,1,&command);
  Mapping*m=calloc(1,sizeof(*m));
  if(!m){wgpuCommandBufferRelease(command);wgpuCommandEncoderRelease(encoder);wgpuBufferRelease(buffer);return 1;}
  atomic_init(&m->refs,2);atomic_init(&m->done,0);m->buffer=buffer;m->owner=s;atomic_fetch_add(&s->refs,1);
  WGPUBufferMapCallbackInfo callback=WGPU_BUFFER_MAP_CALLBACK_INFO_INIT;callback.mode=WGPUCallbackMode_AllowProcessEvents;callback.callback=mapped;callback.userdata1=m;
  wgpuBufferMapAsync(buffer,WGPUMapMode_Read,0,bytes,callback);
  wgpuDevicePoll(s->device,1,NULL);wgpuInstanceProcessEvents(s->instance);
  int ok=atomic_load(&m->done) && m->status==WGPUMapAsyncStatus_Success && !atomic_load(&s->error);
  if(ok){
    const unsigned char *pixels=wgpuBufferGetConstMappedRange(buffer,0,bytes);uint64_t position=0;
    for(int i=0;i<count;i++){
      SgpuProgram*p=programs[i];
      for(int y=0;y<p->height;y++)memcpy(out+position+(uint64_t)y*p->width*4,pixels+offsets[i]+y*strides[i],(size_t)p->width*4);
      position+=(uint64_t)p->width*p->height*4;
    }
    wgpuBufferUnmap(buffer);
  }
  wgpuCommandBufferRelease(command);wgpuCommandEncoderRelease(encoder);map_release(m);return ok?0:5;
}

// Isolated implementation namespace; exported pointer-only batch ABI is unchanged.
#define release desktop_batch_release
#define message desktop_batch_message
#define adapter_cb desktop_batch_adapter_cb
#define device_cb desktop_batch_device_cb
#define error_cb desktop_batch_error_cb
#define await desktop_batch_await
#define Mapping desktop_batch_Mapping
#define map_release desktop_batch_map_release
#define mapped desktop_batch_mapped
#define readback desktop_batch_readback
#define texture(...) desktop_batch_texture(__VA_ARGS__)
#define buffer(...) desktop_batch_buffer(__VA_ARGS__)
#define pipeline(...) desktop_batch_pipeline(__VA_ARGS__)
#define program_free desktop_batch_program_free
#define pass desktop_batch_pass
#include "../../probes/render/src/main/resources/scala-native/sirin_batch.c"
#undef release
#undef message
#undef adapter_cb
#undef device_cb
#undef error_cb
#undef await
#undef Mapping
#undef map_release
#undef mapped
#undef readback
#undef texture
#undef buffer
#undef pipeline
#undef pass
#undef program_free

#include "gpu_frames.c"

const char *sirin_desktop_text(void *raw){Desktop *d=raw;return owner(d) ? d->text:NULL;}
int sirin_desktop_text_input(void *raw,int mode,const double *area){
 Desktop *d=raw;if(!owner(d))return 1;
 if(mode==0)return SDL_StopTextInput(d->window) ? 0:1;
 if(mode==2)return SDL_ClearComposition(d->window) ? 0:1;
 if(!area || !isfinite(area[0]) || !isfinite(area[1]) || !isfinite(area[2]) || !isfinite(area[3]))return 1;
 SDL_Rect rect={(int)floor(area[0]),(int)floor(area[1]),(int)fmax(1,ceil(area[2])),(int)fmax(1,ceil(area[3]))};
 if(!SDL_SetTextInputArea(d->window,&rect,0))return 1;
 return SDL_TextInputActive(d->window) || SDL_StartTextInput(d->window) ? 0:1;
}
int sirin_desktop_clipboard(void *raw,int write,char *text,int capacity){
 Desktop *d=raw;if(!owner(d) || !text || capacity<1 || capacity>65537)return 1;
 if(write)return SDL_SetClipboardText(text) ? 0:1;
 char *value=SDL_GetClipboardText();if(!value)return 1;size_t length=strlen(value);
 if(length>=(size_t)capacity){SDL_free(value);return 2;}memcpy(text,value,length+1);SDL_free(value);return 0;
}
int sirin_desktop_ax_begin(void *raw,int64_t revision){Desktop *d=raw;if(!owner(d))return 1;if(!d->accessibility)d->accessibility=sirin_ax_open(d->window,d->wake_type);return sirin_ax_begin(d->accessibility,revision);}
int sirin_desktop_ax_field(void *raw,int64_t id,int kind,const char *label,const char *value,const char *help,int start,int length,int focused,int enabled,const double *bounds,const double *spans,int count){Desktop *d=raw;return owner(d) ? sirin_ax_field(d->accessibility,id,kind,label,value,help,start,length,focused,enabled,bounds,spans,count):1;}
int sirin_desktop_ax_publish(void *raw){Desktop *d=raw;return owner(d) ? sirin_ax_publish(d->accessibility):1;}
int sirin_desktop_ax_poll(void *raw,int64_t *facts,char *text,int capacity){Desktop *d=raw;return owner(d) && d->accessibility ? sirin_ax_poll(d->accessibility,facts,text,capacity):0;}
int sirin_desktop_ime_control(void *raw,const char *text,int start,int length,int commit){Desktop *d=raw;if(!owner(d))return 1;fprintf(stderr,"nativeIME focus=%d active=%d\n",SDL_GetKeyboardFocus()==d->window,SDL_TextInputActive(d->window));if(SDL_GetKeyboardFocus()!=d->window || !SDL_TextInputActive(d->window))return 3;void *native=SDL_GetPointerProperty(SDL_GetWindowProperties(d->window),SDL_PROP_WINDOW_COCOA_WINDOW_POINTER,NULL);return sirin_editor_ime_control(native,text,start,length,commit);}

int sirin_desktop_activate(void *raw){Desktop *d=raw;if(!owner(d))return 1;void *native=SDL_GetPointerProperty(SDL_GetWindowProperties(d->window),SDL_PROP_WINDOW_COCOA_WINDOW_POINTER,NULL);int result=sirin_editor_activate(native);SDL_RaiseWindow(d->window);SDL_PumpEvents();return result;}

int sirin_desktop_keyboard_focus(void *raw){Desktop *d=raw;return owner(d) && SDL_GetKeyboardFocus()==d->window;}
