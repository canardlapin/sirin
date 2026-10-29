// Private transport, no managed callbacks. Existing pointer-only ABI wrappers.
#include "../../probes/window/src/main/resources/scala-native/sirin_shim.c"
#include <stdatomic.h>
#include <stdlib.h>
#include <time.h>
#include <pthread.h>

static _Atomic int live_requests;
static int fault_stage;
int sirin_desktop_requests(void) { return live_requests; }

// Separate request ownership survives timeout without pointing into a closed arena.
typedef struct Request {
  _Atomic int refs, done;
  int kind, status;
  void *object;
} Request;
static void request_release(Request *r) {
  if (atomic_fetch_sub(&r->refs, 1) != 1) return;
  if (r->object) {
    if (r->kind == 1) wgpuAdapterRelease(r->object);
    else wgpuDeviceRelease(r->object);
  }
  atomic_fetch_sub(&live_requests,1); free(r);
}
static void adapter_ready(WGPURequestAdapterStatus status, WGPUAdapter adapter,
    WGPUStringView message, void *p, void *unused) {
  (void)message; (void)unused; Request *r = p;
  r->status = status; r->object = adapter; atomic_store(&r->done, 1); request_release(r);
}
static void device_ready(WGPURequestDeviceStatus status, WGPUDevice device,
    WGPUStringView message, void *p, void *unused) {
  (void)message; (void)unused; Request *r = p;
  r->status = status; r->object = device; atomic_store(&r->done, 1); request_release(r);
}
static Request *request_new(int kind) {
  Request *r = calloc(1, sizeof(*r));
  if (r) { atomic_fetch_add(&live_requests,1); atomic_init(&r->refs, 2); atomic_init(&r->done, 0); r->kind = kind; }
  return r;
}
static uint64_t nanos(void) {
  struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
  return (uint64_t)t.tv_sec * 1000000000u + (uint64_t)t.tv_nsec;
}
static void *await_request(WGPUInstance instance, Request *r) {
  uint64_t deadline = nanos() + 5000000000u;
  while (!atomic_load(&r->done) && nanos() < deadline) wgpuInstanceProcessEvents(instance);
  void *result = NULL;
  if (atomic_load(&r->done) && r->status == 1) { result = r->object; r->object = NULL; }
  request_release(r);
  return result;
}
typedef struct Desktop {
  SDL_Window *window; SDL_MetalView metal;
  WGPUInstance instance; WGPUSurface surface; WGPUAdapter adapter;
  WGPUDevice device; WGPUQueue queue; WGPURenderPipeline blit; WGPUTextureFormat format;
  uint32_t width, height, wake_type, window_id;
  pthread_t owner; int configured, copy_dst;
} Desktop;
static _Atomic int live_windows;
int sirin_desktop_fault(int stage) {
  if (!pthread_main_np() || live_windows || stage<0 || stage>8) return 1;
  fault_stage=stage;return 0;
}
int sirin_desktop_live(void) { return live_windows; }
int sirin_desktop_first_thread(void) { return pthread_main_np(); }
static int owner(Desktop *d) { return d && pthread_equal(d->owner, pthread_self()) && pthread_main_np(); }
static void destroy(Desktop *d) {
  if (d->surface && d->configured) wgpuSurfaceUnconfigure(d->surface);
  if (d->blit) wgpuRenderPipelineRelease(d->blit);
  if (d->queue) wgpuQueueRelease(d->queue);
  if (d->device) wgpuDeviceRelease(d->device);
  if (d->adapter) wgpuAdapterRelease(d->adapter);
  if (d->surface) wgpuSurfaceRelease(d->surface);
  if (d->instance) wgpuInstanceRelease(d->instance);
  if (d->metal) SDL_Metal_DestroyView(d->metal);
  if (d->window) SDL_DestroyWindow(d->window);
  free(d); SDL_Quit(); live_windows = 0;
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
  if (!pthread_main_np() || live_windows || width <= 0 || height <= 0) {
    snprintf(error,512,"open requires process main thread, one window and positive dimensions"); return 1;
  }
  Desktop *d = calloc(1, sizeof(*d));
  if (!d) { snprintf(error,512,"allocation failed");return 2; }
  d->owner = pthread_self(); live_windows = 1;
  if (!SDL_Init(SDL_INIT_VIDEO)) goto fail;
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
  d->wake_type = SDL_RegisterEvents(1); if (d->wake_type == 0) goto fail;
  *out = d; return 0;
fail:
  snprintf(error,512,"native open failed: %s",SDL_GetError());destroy(d);return 3;
}
int sirin_desktop_close(void *raw) {
  Desktop *d = raw; if (!owner(d)) return 1;
  destroy(d);return 0;
}
// Output layout is explicit int32 facts: flags,widthPixels,heightPixels,windowId,wakeType.
int sirin_desktop_wait(void *raw, int timeout, int32_t *out) {
  Desktop *d = raw; if (!owner(d) || timeout < -1) return -1;
  SDL_Event e; int found=SDL_WaitEventTimeout(&e,timeout); int flags=0;
  if (found) {
    if (e.type==SDL_EVENT_QUIT || (e.type==SDL_EVENT_WINDOW_CLOSE_REQUESTED && e.window.windowID==d->window_id) ||
        (e.type==SDL_EVENT_KEY_DOWN && e.key.windowID==d->window_id && e.key.key==SDLK_ESCAPE)) flags|=1;
    if ((e.type==SDL_EVENT_WINDOW_EXPOSED || e.type==SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED ||
         e.type==SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED) && e.window.windowID==d->window_id) flags|=2;
    if (e.type==d->wake_type && e.user.windowID==d->window_id) flags|=4;
  }
  constrain_window(d);
  int w=0,h=0; SDL_GetWindowSizeInPixels(d->window,&w,&h);
  out[0]=flags;out[1]=w;out[2]=h;out[3]=d->window_id;out[4]=d->wake_type;SDL_GetWindowSize(d->window,&out[5],&out[6]);
  return found;
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
