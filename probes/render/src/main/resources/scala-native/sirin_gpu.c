#include <webgpu/webgpu.h>
#include <webgpu/wgpu.h>
#include <thorvg_capi.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <stdatomic.h>
#include <time.h>
#include <math.h>

/* Native-owned callback state only. No Scala callback, structs by value across
 * the extern boundary, or Scala execution on wgpu-created threads. */
typedef struct { int id,offset,count,refs; unsigned long long used; } SgpuAsset;
typedef struct {
  _Atomic int refs, done, error;
  int status;
  WGPUInstance instance; WGPUAdapter adapter; WGPUDevice device; WGPUQueue queue;
  WGPUComputePipeline pipeline;
  WGPUBuffer dummy,assets;
  SgpuAsset asset[128];int asset_limit,asset_capacity;
  unsigned long long asset_clock;
  long long asset_uploaded,asset_evictions;
  int programs; long long retained_bytes, uploaded_bytes;
  char message[512], adapter_name[512];
} Sgpu;
static _Atomic int live = 0;
static void release(Sgpu *s) {
  if (atomic_fetch_sub(&s->refs,1)!=1) return;
  if(s->device) wgpuDevicePoll(s->device,1,NULL);
  if(s->assets)wgpuBufferRelease(s->assets);
  if(s->dummy)wgpuBufferRelease(s->dummy);
  if(s->pipeline) wgpuComputePipelineRelease(s->pipeline);
  if(s->queue) wgpuQueueRelease(s->queue);
  if(s->device) wgpuDeviceRelease(s->device);
  if(s->adapter) wgpuAdapterRelease(s->adapter);
  if(s->instance) wgpuInstanceRelease(s->instance);
  atomic_fetch_sub(&live,1); free(s);
}
static void message(char *out,WGPUStringView m) {
  size_t n=m.data?(m.length==WGPU_STRLEN?strlen(m.data):m.length):0;
  if(n>511)n=511; if(n)memcpy(out,m.data,n);out[n]=0;
}
static void adapter_cb(WGPURequestAdapterStatus status,WGPUAdapter a,WGPUStringView m,void *p,void *unused) {
  (void)unused; Sgpu*s=p;s->status=status;s->adapter=a;message(s->message,m);atomic_store(&s->done,1);release(s);
}
static void device_cb(WGPURequestDeviceStatus status,WGPUDevice d,WGPUStringView m,void*p,void*unused) {
  (void)unused;Sgpu*s=p;s->status=status;s->device=d;message(s->message,m);atomic_store(&s->done,1);release(s);
}
static void error_cb(WGPUDevice const*d,WGPUErrorType type,WGPUStringView m,void*p,void*unused) {
  (void)d;(void)type;(void)unused;Sgpu*s=p;int zero=0;
  if(atomic_compare_exchange_strong(&s->error,&zero,-1)){message(s->message,m);atomic_store(&s->error,1);}
}
static int await(Sgpu*s) {
  struct timespec delay={0,1000000};
  for(int i=0;i<10000&&!atomic_load(&s->done);i++){wgpuInstanceProcessEvents(s->instance);nanosleep(&delay,NULL);}
  return atomic_load(&s->done)?s->status:99;
}
int sgpu_open(const char*shader,void**out,char*error) {
  *out=NULL;Sgpu*s=calloc(1,sizeof(*s));if(!s)return 1;
  atomic_init(&s->refs,1);atomic_fetch_add(&live,1);s->asset_limit=128;s->asset_capacity=262144;
  s->instance=wgpuCreateInstance(NULL);if(!s->instance)goto fail;
  WGPURequestAdapterOptions opts=WGPU_REQUEST_ADAPTER_OPTIONS_INIT;opts.backendType=WGPUBackendType_Metal;
  WGPURequestAdapterCallbackInfo ac=WGPU_REQUEST_ADAPTER_CALLBACK_INFO_INIT;
  ac.mode=WGPUCallbackMode_AllowProcessEvents;ac.callback=adapter_cb;ac.userdata1=s;atomic_fetch_add(&s->refs,1);
  wgpuInstanceRequestAdapter(s->instance,&opts,ac);
  if(await(s)!=WGPURequestAdapterStatus_Success)goto fail;
  WGPUAdapterInfo ai=WGPU_ADAPTER_INFO_INIT;
  if(wgpuAdapterGetInfo(s->adapter,&ai)==WGPUStatus_Success){
    message(s->adapter_name,ai.description);
    if(!s->adapter_name[0])message(s->adapter_name,ai.device);
    if(!s->adapter_name[0])message(s->adapter_name,ai.vendor);
  }
  if(!s->adapter_name[0]){snprintf(s->message,512,"adapter returned no identity");wgpuAdapterInfoFreeMembers(ai);goto fail;}
  wgpuAdapterInfoFreeMembers(ai);
  WGPUDeviceDescriptor dd=WGPU_DEVICE_DESCRIPTOR_INIT;
  dd.uncapturedErrorCallbackInfo.callback=error_cb;dd.uncapturedErrorCallbackInfo.userdata1=s;
  WGPURequestDeviceCallbackInfo dc=WGPU_REQUEST_DEVICE_CALLBACK_INFO_INIT;
  dc.mode=WGPUCallbackMode_AllowProcessEvents;dc.callback=device_cb;dc.userdata1=s;
  atomic_store(&s->done,0);atomic_fetch_add(&s->refs,1);wgpuAdapterRequestDevice(s->adapter,&dd,dc);
  if(await(s)!=WGPURequestDeviceStatus_Success)goto fail;
  s->queue=wgpuDeviceGetQueue(s->device);
  WGPUShaderSourceWGSL wgsl=WGPU_SHADER_SOURCE_WGSL_INIT;wgsl.code=(WGPUStringView){shader,WGPU_STRLEN};
  WGPUShaderModuleDescriptor sd=WGPU_SHADER_MODULE_DESCRIPTOR_INIT;sd.nextInChain=&wgsl.chain;
  WGPUShaderModule module=wgpuDeviceCreateShaderModule(s->device,&sd);
  if(atomic_load(&s->error)){if(module)wgpuShaderModuleRelease(module);goto fail;}
  WGPUComputePipelineDescriptor pd=WGPU_COMPUTE_PIPELINE_DESCRIPTOR_INIT;
  pd.compute.module=module;pd.compute.entryPoint=(WGPUStringView){"main",4};
  s->pipeline=wgpuDeviceCreateComputePipeline(s->device,&pd);wgpuShaderModuleRelease(module);
  wgpuDevicePoll(s->device,1,NULL);
  if(!s->pipeline||atomic_load(&s->error))goto fail;
  WGPUBufferDescriptor dummy=WGPU_BUFFER_DESCRIPTOR_INIT;dummy.size=16;dummy.usage=WGPUBufferUsage_Storage;
  s->dummy=wgpuDeviceCreateBuffer(s->device,&dummy);
  if(!s->dummy||atomic_load(&s->error))goto fail;
  *out=s;return 0;
fail:
  snprintf(error,512,"%s",s->message[0]?s->message:"GPU initialization/request failed");release(s);return 2;
}
const char*sgpu_adapter(void*raw){return ((Sgpu*)raw)->adapter_name;}
const char*sgpu_error(void*raw){return ((Sgpu*)raw)->message;}
int sgpu_live(void){return atomic_load(&live);}
void sgpu_close(void*raw){release(raw);}

typedef struct { _Atomic int refs,done; WGPUMapAsyncStatus status; WGPUBuffer buffer; Sgpu*owner; } Mapping;
static void map_release(Mapping*r){if(atomic_fetch_sub(&r->refs,1)==1){
  Sgpu*s=r->owner;wgpuBufferRelease(r->buffer);free(r);release(s);
}}
static void mapped(WGPUMapAsyncStatus status,WGPUStringView m,void*p,void*unused){
  (void)m;(void)unused;Mapping*r=p;r->status=status;atomic_store(&r->done,1);map_release(r);
}
static int readback(Sgpu*s,WGPUTexture texture,int width,int height,unsigned char*out){
  int stride=(width*4+255)&~255;uint64_t bytes=(uint64_t)stride*height;
  WGPUBufferDescriptor bd=WGPU_BUFFER_DESCRIPTOR_INIT;bd.size=bytes;bd.usage=WGPUBufferUsage_CopyDst|WGPUBufferUsage_MapRead;
  WGPUBuffer buffer=wgpuDeviceCreateBuffer(s->device,&bd);
  WGPUCommandEncoder encoder=wgpuDeviceCreateCommandEncoder(s->device,NULL);
  WGPUTexelCopyTextureInfo src=WGPU_TEXEL_COPY_TEXTURE_INFO_INIT;src.texture=texture;
  WGPUTexelCopyBufferInfo dst=WGPU_TEXEL_COPY_BUFFER_INFO_INIT;dst.buffer=buffer;dst.layout.bytesPerRow=stride;dst.layout.rowsPerImage=height;
  WGPUExtent3D extent={(uint32_t)width,(uint32_t)height,1};
  wgpuCommandEncoderCopyTextureToBuffer(encoder,&src,&dst,&extent);
  WGPUCommandBuffer cmd=wgpuCommandEncoderFinish(encoder,NULL);wgpuQueueSubmit(s->queue,1,&cmd);
  Mapping*r=calloc(1,sizeof(*r));
  if(!r){wgpuCommandBufferRelease(cmd);wgpuCommandEncoderRelease(encoder);wgpuBufferRelease(buffer);return 1;}
  atomic_init(&r->refs,2);r->buffer=buffer;r->owner=s;atomic_fetch_add(&s->refs,1);
  WGPUBufferMapCallbackInfo cb=WGPU_BUFFER_MAP_CALLBACK_INFO_INIT;
  cb.mode=WGPUCallbackMode_AllowProcessEvents;cb.callback=mapped;cb.userdata1=r;
  wgpuBufferMapAsync(buffer,WGPUMapMode_Read,0,bytes,cb);
  wgpuDevicePoll(s->device,1,NULL);wgpuInstanceProcessEvents(s->instance);
  int ok=atomic_load(&r->done)&&r->status==WGPUMapAsyncStatus_Success;
  if(ok){const unsigned char*p=wgpuBufferGetConstMappedRange(buffer,0,bytes);
    for(int y=0;y<height;y++)memcpy(out+(size_t)y*width*4,p+(size_t)y*stride,(size_t)width*4);
    wgpuBufferUnmap(buffer);
  }
  wgpuCommandBufferRelease(cmd);wgpuCommandEncoderRelease(encoder);map_release(r);
  return ok?0:3;
}
/* Shared asset arena: offsets stay fixed while pinned by retained programs.
 * Idle LRU entries can be evicted; allocation never moves a live span. */
int sgpu_asset_configure(void*raw,int capacity,int limit){
  Sgpu*s=raw;if(s->assets||s->programs||capacity<1||capacity>1048576||limit<1||limit>128)return 4;
  s->asset_capacity=capacity;s->asset_limit=limit;return 0;
}
static SgpuAsset*asset_find(Sgpu*s,int id){
  for(int i=0;i<s->asset_limit;i++)if(s->asset[i].id==id)return &s->asset[i];return NULL;
}
static int asset_gap(Sgpu*s,int count){
  int position=0;
  while(position<=s->asset_capacity-count){
    int next=s->asset_capacity,end=position;
    for(int i=0;i<s->asset_limit;i++){
      SgpuAsset*a=&s->asset[i];if(!a->id)continue;
      if(a->offset>=position&&a->offset<next)next=a->offset;
      if(a->offset<=position&&a->offset+a->count>end)end=a->offset+a->count;
    }
    if(end>position){position=end;continue;}
    if(next-position>=count)return position;
    if(next==s->asset_capacity)break;
    position=next;
  }
  return -1;
}
int sgpu_asset_acquire(void*raw,int id,const float*data,int count,int*out){
  Sgpu*s=raw;*out=-1;if(id<=0||count<=0||count%4)return 4;
  SgpuAsset*entry=asset_find(s,id);
  if(entry){if(entry->count!=count/4)return 4;entry->refs++;entry->used=++s->asset_clock;*out=entry->offset;return 0;}
  if(!data)return 7;
  if(count/4>s->asset_capacity)return 6;
  if(atomic_load(&s->error))return 5;
  int gap=-1;
  for(;;){
    entry=NULL;SgpuAsset*old=NULL;
    for(int i=0;i<s->asset_limit;i++){
      SgpuAsset*a=&s->asset[i];if(!a->id)entry=a;
      else if(!a->refs&&(!old||a->used<old->used))old=a;
    }
    gap=asset_gap(s,count/4);if(entry&&gap>=0)break;
    if(!old)return 6;
    memset(old,0,sizeof(*old));s->asset_evictions++;
  }
  if(!s->assets){
    if((long long)s->asset_capacity*16>67108864-s->retained_bytes)return 6;
    WGPUBufferDescriptor bd=WGPU_BUFFER_DESCRIPTOR_INIT;bd.size=(uint64_t)s->asset_capacity*16;
    bd.usage=WGPUBufferUsage_Storage|WGPUBufferUsage_CopyDst;s->assets=wgpuDeviceCreateBuffer(s->device,&bd);
    if(!s->assets||atomic_load(&s->error))return 5;
  }
  wgpuQueueWriteBuffer(s->queue,s->assets,(uint64_t)gap*16,data,(uint64_t)count*4);
  wgpuDevicePoll(s->device,1,NULL);if(atomic_load(&s->error))return 5;
  *entry=(SgpuAsset){id,gap,count/4,1,++s->asset_clock};s->asset_uploaded+=(long long)count*4;
  *out=gap;return 0;
}
void sgpu_asset_release(void*raw,int id){SgpuAsset*a=asset_find(raw,id);if(a&&a->refs>0)a->refs--;}
long long sgpu_asset_stat(void*raw,int field){
  Sgpu*s=raw;long long entries=0,pinned=0,used=0;
  for(int i=0;i<s->asset_limit;i++)if(s->asset[i].id){entries++;used+=(long long)s->asset[i].count*16;if(s->asset[i].refs)pinned++;}
  switch(field){case 0:return entries;case 1:return pinned;case 2:return s->assets?(long long)s->asset_capacity*16:0;
    case 3:return used;case 4:return s->asset_uploaded;case 5:return s->asset_evictions;default:return -1;}
}

/* Explicit retained programs. Scala owner confinement serializes these fields.
 * These counts describe requested payload sizes, not driver/allocator memory. */
typedef struct {
  Sgpu *owner; WGPUBuffer buffer; WGPUTexture texture;
  WGPUTextureView view; WGPUBindGroup group; int width,height;
  long long bytes;int asset_ids[128],asset_count;
} SgpuProgram;
static void program_free(SgpuProgram*p){
  if(p->group)wgpuBindGroupRelease(p->group);
  if(p->view)wgpuTextureViewRelease(p->view);
  if(p->texture)wgpuTextureRelease(p->texture);
  if(p->buffer)wgpuBufferRelease(p->buffer);
  free(p);
}
static int retain(void*raw,const float*data,int count,int w,int h,const int*ids,int asset_count,void**out){
  Sgpu*s=raw;*out=NULL;
  if(count<4||w<1||h<1||w>4096||h>4096||(long long)w*h>1048576)return 4;
  long long bytes=(long long)count*4+(long long)w*h*4;
  if(s->programs>=64||bytes>67108864-s->retained_bytes-(s->assets?(long long)s->asset_capacity*16:0))return 6;
  if(asset_count<0||asset_count>128)return 4;
  for(int i=0;i<asset_count;i++){SgpuAsset*a=asset_find(s,ids[i]);if(!a||!a->refs)return 4;}
  if(atomic_load(&s->error))return 5;
  SgpuProgram*p=calloc(1,sizeof(*p));if(!p)return 1;
  p->owner=s;p->width=w;p->height=h;p->bytes=bytes;
  WGPUBufferDescriptor bd=WGPU_BUFFER_DESCRIPTOR_INIT;
  bd.size=(uint64_t)count*4;bd.usage=WGPUBufferUsage_Storage|WGPUBufferUsage_CopyDst;
  p->buffer=wgpuDeviceCreateBuffer(s->device,&bd);
  WGPUTextureDescriptor td=WGPU_TEXTURE_DESCRIPTOR_INIT;
  td.size=(WGPUExtent3D){w,h,1};td.format=WGPUTextureFormat_RGBA8Unorm;
  td.usage=WGPUTextureUsage_StorageBinding|WGPUTextureUsage_CopySrc;
  p->texture=wgpuDeviceCreateTexture(s->device,&td);
  if(!p->buffer||!p->texture)goto fail;
  p->view=wgpuTextureCreateView(p->texture,NULL);
  WGPUBindGroupLayout layout=wgpuComputePipelineGetBindGroupLayout(s->pipeline,0);
  WGPUBindGroupEntry entries[3]={WGPU_BIND_GROUP_ENTRY_INIT,WGPU_BIND_GROUP_ENTRY_INIT,WGPU_BIND_GROUP_ENTRY_INIT};
  entries[0].binding=0;entries[0].buffer=p->buffer;entries[0].size=bd.size;
  entries[1].binding=1;entries[1].textureView=p->view;
  entries[2].binding=2;entries[2].buffer=asset_count?s->assets:s->dummy;entries[2].size=asset_count?(uint64_t)s->asset_capacity*16:16;
  WGPUBindGroupDescriptor gd=WGPU_BIND_GROUP_DESCRIPTOR_INIT;
  gd.layout=layout;gd.entryCount=3;gd.entries=entries;
  p->group=wgpuDeviceCreateBindGroup(s->device,&gd);wgpuBindGroupLayoutRelease(layout);
  if(!p->group||atomic_load(&s->error))goto fail;
  wgpuQueueWriteBuffer(s->queue,p->buffer,0,data,bd.size);
  wgpuDevicePoll(s->device,1,NULL);
  if(atomic_load(&s->error))goto fail;
  atomic_fetch_add(&s->refs,1);s->programs++;s->retained_bytes+=bytes;s->uploaded_bytes+=(long long)bd.size;
  p->asset_count=asset_count;if(asset_count)memcpy(p->asset_ids,ids,(size_t)asset_count*sizeof(int));
  *out=p;return 0;
fail:
  program_free(p);return 5;
}
int sgpu_retain(void*raw,const float*data,int count,int w,int h,void**out){return retain(raw,data,count,w,h,NULL,0,out);}
int sgpu_retain_assets(void*raw,const float*data,int count,int w,int h,const int*ids,int asset_count,void**out){return retain(raw,data,count,w,h,ids,asset_count,out);}
int sgpu_replay(void*raw,unsigned char*out){
  SgpuProgram*p=raw;Sgpu*s=p->owner;
  if(atomic_load(&s->error))return 5;
  for(int i=0;i<p->asset_count;i++){SgpuAsset*a=asset_find(s,p->asset_ids[i]);if(a)a->used=++s->asset_clock;}
  WGPUCommandEncoder encoder=wgpuDeviceCreateCommandEncoder(s->device,NULL);
  WGPUComputePassEncoder pass=wgpuCommandEncoderBeginComputePass(encoder,NULL);
  wgpuComputePassEncoderSetPipeline(pass,s->pipeline);
  wgpuComputePassEncoderSetBindGroup(pass,0,p->group,0,NULL);
  wgpuComputePassEncoderDispatchWorkgroups(pass,(p->width+7)/8,(p->height+7)/8,1);
  wgpuComputePassEncoderEnd(pass);
  WGPUCommandBuffer cmd=wgpuCommandEncoderFinish(encoder,NULL);wgpuQueueSubmit(s->queue,1,&cmd);
  wgpuDevicePoll(s->device,1,NULL);
  wgpuCommandBufferRelease(cmd);wgpuComputePassEncoderRelease(pass);wgpuCommandEncoderRelease(encoder);
  if(atomic_load(&s->error))return 5;
  int status=readback(s,p->texture,p->width,p->height,out);
  return atomic_load(&s->error)?5:status;
}
void sgpu_program_close(void*raw){
  SgpuProgram*p=raw;Sgpu*s=p->owner;
  for(int i=0;i<p->asset_count;i++)sgpu_asset_release(s,p->asset_ids[i]);
  s->programs--;s->retained_bytes-=p->bytes;program_free(p);release(s);
}
int sgpu_programs(void*raw){return ((Sgpu*)raw)->programs;}
long long sgpu_retained_bytes(void*raw){return ((Sgpu*)raw)->retained_bytes;}
long long sgpu_uploaded_bytes(void*raw){return ((Sgpu*)raw)->uploaded_bytes;}

/* ThorVG receives exactly this device/instance and texture. No alternate GPU
 * context, readback/upload of its output, text reshaping or Scala callbacks. */
static int thorvg(Sgpu*s,WGPUTexture texture,int w,int h,const float*data);
int sgpu_render(void*raw,const float*data,int count,int w,int h,int use_thorvg,unsigned char*out){
  Sgpu*s=raw;if(count<4||w<1||h<1||w>4096||h>4096)return 4;
  if(atomic_load(&s->error))return 5;
  WGPUTextureDescriptor td=WGPU_TEXTURE_DESCRIPTOR_INIT;
  td.size=(WGPUExtent3D){w,h,1};
  /* Pinned ThorVG's blit pipeline requires BGRA8; the reference compute
   * pipeline writes RGBA8. Both textures are owned by this same wgpu device. */
  td.format=use_thorvg?WGPUTextureFormat_BGRA8Unorm:WGPUTextureFormat_RGBA8Unorm;
  td.usage=WGPUTextureUsage_CopySrc|WGPUTextureUsage_RenderAttachment|WGPUTextureUsage_TextureBinding;
  if(!use_thorvg)td.usage|=WGPUTextureUsage_StorageBinding;
  WGPUTexture texture=wgpuDeviceCreateTexture(s->device,&td);
  int result=0;
  if(use_thorvg)result=thorvg(s,texture,w,h,data);
  else {
    WGPUBufferDescriptor bd=WGPU_BUFFER_DESCRIPTOR_INIT;bd.size=(uint64_t)count*4;
    bd.usage=WGPUBufferUsage_Storage|WGPUBufferUsage_CopyDst;
    WGPUBuffer buffer=wgpuDeviceCreateBuffer(s->device,&bd);wgpuQueueWriteBuffer(s->queue,buffer,0,data,bd.size);s->uploaded_bytes+=(long long)bd.size;
    WGPUTextureView view=wgpuTextureCreateView(texture,NULL);
    WGPUBindGroupLayout layout=wgpuComputePipelineGetBindGroupLayout(s->pipeline,0);
    WGPUBindGroupEntry entries[3]={WGPU_BIND_GROUP_ENTRY_INIT,WGPU_BIND_GROUP_ENTRY_INIT,WGPU_BIND_GROUP_ENTRY_INIT};
    entries[0].binding=0;entries[0].buffer=buffer;entries[0].size=bd.size;entries[1].binding=1;entries[1].textureView=view;
    entries[2].binding=2;entries[2].buffer=s->dummy;entries[2].size=16;
    WGPUBindGroupDescriptor gd=WGPU_BIND_GROUP_DESCRIPTOR_INIT;gd.layout=layout;gd.entryCount=3;gd.entries=entries;
    WGPUBindGroup group=wgpuDeviceCreateBindGroup(s->device,&gd);
    WGPUCommandEncoder encoder=wgpuDeviceCreateCommandEncoder(s->device,NULL);
    WGPUComputePassEncoder pass=wgpuCommandEncoderBeginComputePass(encoder,NULL);
    wgpuComputePassEncoderSetPipeline(pass,s->pipeline);wgpuComputePassEncoderSetBindGroup(pass,0,group,0,NULL);
    wgpuComputePassEncoderDispatchWorkgroups(pass,(w+7)/8,(h+7)/8,1);wgpuComputePassEncoderEnd(pass);
    WGPUCommandBuffer cmd=wgpuCommandEncoderFinish(encoder,NULL);wgpuQueueSubmit(s->queue,1,&cmd);
    wgpuDevicePoll(s->device,1,NULL);
    wgpuCommandBufferRelease(cmd);wgpuComputePassEncoderRelease(pass);wgpuCommandEncoderRelease(encoder);
    wgpuBindGroupRelease(group);wgpuBindGroupLayoutRelease(layout);wgpuTextureViewRelease(view);wgpuBufferRelease(buffer);
  }
  if(!result&&!atomic_load(&s->error)){
    result=readback(s,texture,w,h,out);
    if(!result&&use_thorvg)for(int i=0;i<w*h;i++){unsigned char r=out[4*i];out[4*i]=out[4*i+2];out[4*i+2]=r;}
  }
  wgpuTextureRelease(texture);
  return atomic_load(&s->error)?5:result;
}

static float encode(float x){return x<=0.0031308f?12.92f*x:1.055f*powf(x,1.0f/2.4f)-0.055f;}
static unsigned char byte(float x){return (unsigned char)roundf(fmaxf(0,fminf(1,x))*255);}
static Tvg_Paint shape(const float*d,int start,int count,int rule){
  Tvg_Paint p=tvg_shape_new();
  float lastx=0,lasty=0;int have=0;
  for(int i=0;i<count;i++){
    const float*e=d+(start+i)*4;
    if(!have||e[0]!=lastx||e[1]!=lasty)tvg_shape_move_to(p,e[0],e[1]);
    tvg_shape_line_to(p,e[2],e[3]);lastx=e[2];lasty=e[3];have=1;
  }
  tvg_shape_set_fill_rule(p,rule?TVG_FILL_RULE_EVEN_ODD:TVG_FILL_RULE_NON_ZERO);
  return p;
}
static int thorvg(Sgpu*s,WGPUTexture texture,int w,int h,const float*d){
  int result=0;Tvg_Canvas canvas=NULL;Tvg_Paint stack[9];int depth=0;
  if(tvg_engine_init(0)!=TVG_RESULT_SUCCESS)return 10;
  canvas=tvg_wgcanvas_create(0);if(!canvas){result=11;goto done;}
  result=tvg_wgcanvas_set_target(canvas,s->device,s->instance,texture,w,h,TVG_COLORSPACE_ABGR8888S,1);
  if(result) {result+=100;goto done;}
  stack[0]=tvg_scene_new();tvg_canvas_add(canvas,stack[0]);
  for(int i=0;i<(int)d[2];i++){
    const float*o=d+4+i*32;int kind=(int)o[0];Tvg_Paint p=NULL;
    if(kind==3){p=tvg_scene_new();tvg_paint_set_opacity(p,byte(o[7]));tvg_scene_add(stack[depth],p);stack[++depth]=p;continue;}
    if(kind==4){depth--;continue;}
    if(o[29]!=0){result=14;goto done;}
    if(kind==1){p=shape(d,(int)o[1],(int)o[2],(int)o[3]);
      float a=o[7];tvg_shape_set_fill_color(p,byte(a?encode(o[4]/a):0),byte(a?encode(o[5]/a):0),byte(a?encode(o[6]/a):0),byte(a));
    }else if(kind==2){
      int width=(int)o[25],height=(int)o[26];unsigned char*pixels=malloc((size_t)width*height*4);
      if(!pixels){result=12;goto done;}
      for(int n=0;n<width*height;n++){const float*src=d+4*((int)o[24]+n);float tinted[4];
        if(o[31]!=0){for(int c=0;c<4;c++)tinted[c]=o[4+c]*src[3];src=tinted;}float a=src[3];
        pixels[4*n]=byte(a?encode(src[0]/a):0);pixels[4*n+1]=byte(a?encode(src[1]/a):0);
        pixels[4*n+2]=byte(a?encode(src[2]/a):0);pixels[4*n+3]=byte(a);}
      p=tvg_picture_new();result=tvg_picture_load_raw(p,(const uint32_t*)pixels,width,height,TVG_COLORSPACE_ABGR8888S,1);free(pixels);
      if(result){tvg_paint_unref(p,1);result+=200;goto done;}
      /* Inverse device->local matrix composed with destination/source mapping.
       * Cropped pictures require a destination clip in addition to this map. */
      float a=o[8],c=o[9],tx=o[10],b=o[12],e=o[13],ty=o[14],det=a*e-b*c;
      Tvg_Matrix m={e/det,-c/det,(c*ty-e*tx)/det,-b/det,a/det,(b*tx-a*ty)/det,0,0,1};
      float sx=o[18]/o[22],sy=o[19]/o[23],dx=o[16]-o[20]*sx,dy=o[17]-o[21]*sy;
      m.e13+=m.e11*dx+m.e12*dy;m.e23+=m.e21*dx+m.e22*dy;
      m.e11*=sx;m.e21*=sx;m.e12*=sy;m.e22*=sy;tvg_paint_set_transform(p,&m);tvg_paint_set_opacity(p,byte(o[11]));
      /* The bounded comparison currently admits full-source quads only. */
      if(o[20]!=0||o[21]!=0||o[22]!=width||o[23]!=height){tvg_paint_unref(p,1);result=13;goto done;}
    }else continue;
    /* Chain one-clip wrappers so intersecting path clips retain their meaning. */
    for(int j=0;j<(int)o[28];j++){
      const float*cl=d+4*((int)o[27]+j);Tvg_Paint wrapper=tvg_scene_new();
      tvg_scene_add(wrapper,p);Tvg_Paint clip=shape(d,(int)cl[0],(int)cl[1],(int)cl[2]);
      tvg_shape_set_fill_color(clip,255,255,255,255);tvg_paint_set_clip(wrapper,clip);p=wrapper;
    }
    result=tvg_scene_add(stack[depth],p);if(result){tvg_paint_unref(p,1);result+=300;goto done;}
  }
  result=tvg_canvas_draw(canvas,1);if(!result)result=tvg_canvas_sync(canvas);
  wgpuDevicePoll(s->device,1,NULL);
done:
  if(canvas)tvg_canvas_destroy(canvas);tvg_engine_term();return result;
}
