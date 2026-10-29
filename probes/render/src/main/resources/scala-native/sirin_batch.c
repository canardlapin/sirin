#include <webgpu/webgpu.h>
#include <webgpu/wgpu.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <stdatomic.h>
#include <time.h>

typedef struct { int id,offset,count,refs; unsigned long long used; } Mesh;
typedef struct { int id,w,h,refs; unsigned long long used; } Asset;
typedef struct {
  _Atomic int refs,done,error;int status;
  WGPUInstance instance;WGPUAdapter adapter;WGPUDevice device;WGPUQueue queue;
  WGPURenderPipeline pipeline,layer;WGPUComputePipeline resolve;
  WGPUBuffer geometry;WGPUTexture masks,images;WGPUTextureView mask_view,image_view;
  Mesh mesh[1024];Asset mask[256],image[16];
  int mesh_capacity,mesh_limit,mask_limit,image_limit,programs;
  unsigned long long clock;
  long long bytes,mesh_upload,texture_upload,program_upload,evictions,submissions;
  char message[512],adapter_name[512];
} Bgpu;
static _Atomic int batch_live=0;
static void release(Bgpu*s){
  if(atomic_fetch_sub(&s->refs,1)!=1)return;
  if(s->device)wgpuDevicePoll(s->device,1,NULL);
  if(s->mask_view)wgpuTextureViewRelease(s->mask_view);
  if(s->image_view)wgpuTextureViewRelease(s->image_view);
  if(s->masks)wgpuTextureRelease(s->masks);
  if(s->images)wgpuTextureRelease(s->images);
  if(s->geometry)wgpuBufferRelease(s->geometry);
  if(s->pipeline)wgpuRenderPipelineRelease(s->pipeline);
  if(s->layer)wgpuRenderPipelineRelease(s->layer);
  if(s->resolve)wgpuComputePipelineRelease(s->resolve);
  if(s->queue)wgpuQueueRelease(s->queue);
  if(s->device)wgpuDeviceRelease(s->device);
  if(s->adapter)wgpuAdapterRelease(s->adapter);
  if(s->instance)wgpuInstanceRelease(s->instance);
  atomic_fetch_sub(&batch_live,1);free(s);
}
static void message(char *out,WGPUStringView m) {
  size_t n=m.data?(m.length==WGPU_STRLEN?strlen(m.data):m.length):0;
  if(n>511)n=511; if(n)memcpy(out,m.data,n);out[n]=0;
}
static void adapter_cb(WGPURequestAdapterStatus status,WGPUAdapter a,WGPUStringView m,void *p,void *unused) {
  (void)unused; Bgpu*s=p;s->status=status;s->adapter=a;message(s->message,m);atomic_store(&s->done,1);release(s);
}
static void device_cb(WGPURequestDeviceStatus status,WGPUDevice d,WGPUStringView m,void*p,void*unused) {
  (void)unused;Bgpu*s=p;s->status=status;s->device=d;message(s->message,m);atomic_store(&s->done,1);release(s);
}
static void error_cb(WGPUDevice const*d,WGPUErrorType type,WGPUStringView m,void*p,void*unused) {
  (void)d;(void)type;(void)unused;Bgpu*s=p;int zero=0;
  if(atomic_compare_exchange_strong(&s->error,&zero,-1)){message(s->message,m);atomic_store(&s->error,1);}
}
static int await(Bgpu*s) {
  struct timespec delay={0,1000000};
  for(int i=0;i<10000&&!atomic_load(&s->done);i++){wgpuInstanceProcessEvents(s->instance);nanosleep(&delay,NULL);}
  return atomic_load(&s->done)?s->status:99;
}
typedef struct { _Atomic int refs,done; WGPUMapAsyncStatus status; WGPUBuffer buffer; Bgpu*owner; } Mapping;
static void map_release(Mapping*r){if(atomic_fetch_sub(&r->refs,1)==1){
  Bgpu*s=r->owner;wgpuBufferRelease(r->buffer);free(r);release(s);
}}
static void mapped(WGPUMapAsyncStatus status,WGPUStringView m,void*p,void*unused){
  (void)m;(void)unused;Mapping*r=p;r->status=status;atomic_store(&r->done,1);map_release(r);
}
static int readback(Bgpu*s,WGPUTexture texture,int width,int height,unsigned char*out){
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

static WGPUTexture texture(Bgpu*s,int w,int h,WGPUTextureFormat format,WGPUTextureUsage usage){
  WGPUTextureDescriptor td=WGPU_TEXTURE_DESCRIPTOR_INIT;
  td.size=(WGPUExtent3D){(uint32_t)w,(uint32_t)h,1};td.format=format;td.usage=usage;
  return wgpuDeviceCreateTexture(s->device,&td);
}
static WGPUBuffer buffer(Bgpu*s,uint64_t bytes,WGPUBufferUsage usage){
  WGPUBufferDescriptor bd=WGPU_BUFFER_DESCRIPTOR_INIT;bd.size=bytes;bd.usage=usage;
  return wgpuDeviceCreateBuffer(s->device,&bd);
}
static WGPURenderPipeline pipeline(Bgpu*s,WGPUShaderModule module,const char*vs,const char*fs){
  WGPUBlendState blend={.color={WGPUBlendOperation_Add,WGPUBlendFactor_One,WGPUBlendFactor_OneMinusSrcAlpha},
    .alpha={WGPUBlendOperation_Add,WGPUBlendFactor_One,WGPUBlendFactor_OneMinusSrcAlpha}};
  WGPUColorTargetState color=WGPU_COLOR_TARGET_STATE_INIT;
  color.format=WGPUTextureFormat_RGBA32Float;color.blend=&blend;color.writeMask=WGPUColorWriteMask_All;
  WGPUFragmentState fragment=WGPU_FRAGMENT_STATE_INIT;fragment.module=module;
  fragment.entryPoint=(WGPUStringView){fs,WGPU_STRLEN};fragment.targetCount=1;fragment.targets=&color;
  WGPURenderPipelineDescriptor pd=WGPU_RENDER_PIPELINE_DESCRIPTOR_INIT;
  pd.vertex.module=module;pd.vertex.entryPoint=(WGPUStringView){vs,WGPU_STRLEN};pd.fragment=&fragment;
  pd.primitive.topology=WGPUPrimitiveTopology_TriangleList;
  WGPUPipelineLayout pl=NULL;WGPUBindGroupLayout gl=NULL;
  if(strcmp(vs,"vertex")==0){
    WGPUBindGroupLayoutEntry entries[6]={WGPU_BIND_GROUP_LAYOUT_ENTRY_INIT,WGPU_BIND_GROUP_LAYOUT_ENTRY_INIT,WGPU_BIND_GROUP_LAYOUT_ENTRY_INIT,WGPU_BIND_GROUP_LAYOUT_ENTRY_INIT,WGPU_BIND_GROUP_LAYOUT_ENTRY_INIT,WGPU_BIND_GROUP_LAYOUT_ENTRY_INIT};
    for(int i=0;i<3;i++){
      entries[i].binding=i;entries[i].visibility=i==1?WGPUShaderStage_Vertex:(WGPUShaderStage_Vertex|WGPUShaderStage_Fragment);
      entries[i].buffer.type=WGPUBufferBindingType_ReadOnlyStorage;
    }
    for(int i=3;i<5;i++){
      entries[i].binding=i;entries[i].visibility=WGPUShaderStage_Fragment;
      entries[i].texture.sampleType=WGPUTextureSampleType_UnfilterableFloat;
      entries[i].texture.viewDimension=WGPUTextureViewDimension_2D;
    }
    entries[5].binding=8;entries[5].visibility=WGPUShaderStage_Vertex|WGPUShaderStage_Fragment;entries[5].buffer.type=WGPUBufferBindingType_Uniform;
    WGPUBindGroupLayoutDescriptor gd=WGPU_BIND_GROUP_LAYOUT_DESCRIPTOR_INIT;gd.entryCount=6;gd.entries=entries;
    gl=wgpuDeviceCreateBindGroupLayout(s->device,&gd);
    WGPUPipelineLayoutDescriptor ld=WGPU_PIPELINE_LAYOUT_DESCRIPTOR_INIT;ld.bindGroupLayoutCount=1;ld.bindGroupLayouts=&gl;
    pl=wgpuDeviceCreatePipelineLayout(s->device,&ld);pd.layout=pl;
  }
  if(strcmp(vs,"layer_vertex")==0){
    WGPUBindGroupLayoutEntry e=WGPU_BIND_GROUP_LAYOUT_ENTRY_INIT;e.binding=5;e.visibility=WGPUShaderStage_Fragment;
    e.texture.sampleType=WGPUTextureSampleType_UnfilterableFloat;e.texture.viewDimension=WGPUTextureViewDimension_2D;
    WGPUBindGroupLayoutDescriptor gd=WGPU_BIND_GROUP_LAYOUT_DESCRIPTOR_INIT;gd.entryCount=1;gd.entries=&e;
    gl=wgpuDeviceCreateBindGroupLayout(s->device,&gd);
    WGPUPipelineLayoutDescriptor ld=WGPU_PIPELINE_LAYOUT_DESCRIPTOR_INIT;ld.bindGroupLayoutCount=1;ld.bindGroupLayouts=&gl;
    pl=wgpuDeviceCreatePipelineLayout(s->device,&ld);pd.layout=pl;
  }
  WGPURenderPipeline result=wgpuDeviceCreateRenderPipeline(s->device,&pd);
  if(pl)wgpuPipelineLayoutRelease(pl);if(gl)wgpuBindGroupLayoutRelease(gl);return result;
}
int bgpu_open(const char*shader,int vertices,int meshes,int masks,int images,void**out,char*error){
  *out=NULL;
  if(vertices<3||vertices>1048576||meshes<1||meshes>1024||masks<1||masks>256||images<1||images>16)return 4;
  Bgpu*s=calloc(1,sizeof(*s));if(!s)return 1;
  atomic_init(&s->refs,1);atomic_fetch_add(&batch_live,1);
  s->mesh_capacity=vertices;s->mesh_limit=meshes;s->mask_limit=masks;s->image_limit=images;
  s->instance=wgpuCreateInstance(NULL);if(!s->instance)goto fail;
  WGPURequestAdapterOptions opts=WGPU_REQUEST_ADAPTER_OPTIONS_INIT;opts.backendType=WGPUBackendType_Metal;
  WGPURequestAdapterCallbackInfo ac=WGPU_REQUEST_ADAPTER_CALLBACK_INFO_INIT;
  ac.mode=WGPUCallbackMode_AllowProcessEvents;ac.callback=adapter_cb;ac.userdata1=s;atomic_fetch_add(&s->refs,1);
  wgpuInstanceRequestAdapter(s->instance,&opts,ac);if(await(s)!=WGPURequestAdapterStatus_Success)goto fail;
  WGPUAdapterInfo ai=WGPU_ADAPTER_INFO_INIT;
  if(wgpuAdapterGetInfo(s->adapter,&ai)==WGPUStatus_Success){
    message(s->adapter_name,ai.description);if(!s->adapter_name[0])message(s->adapter_name,ai.device);
    if(!s->adapter_name[0])message(s->adapter_name,ai.vendor);
  }
  wgpuAdapterInfoFreeMembers(ai);if(!s->adapter_name[0])goto fail;
  if(!wgpuAdapterHasFeature(s->adapter,WGPUFeatureName_Float32Blendable)){snprintf(s->message,512,"Float32Blendable is required for strict color precision");goto fail;}
  WGPUFeatureName features[]={WGPUFeatureName_Float32Blendable};
  WGPUDeviceDescriptor dd=WGPU_DEVICE_DESCRIPTOR_INIT;dd.requiredFeatureCount=1;dd.requiredFeatures=features;
  dd.uncapturedErrorCallbackInfo.callback=error_cb;dd.uncapturedErrorCallbackInfo.userdata1=s;
  WGPURequestDeviceCallbackInfo dc=WGPU_REQUEST_DEVICE_CALLBACK_INFO_INIT;
  dc.mode=WGPUCallbackMode_AllowProcessEvents;dc.callback=device_cb;dc.userdata1=s;
  atomic_store(&s->done,0);atomic_fetch_add(&s->refs,1);wgpuAdapterRequestDevice(s->adapter,&dd,dc);
  if(await(s)!=WGPURequestDeviceStatus_Success)goto fail;
  s->queue=wgpuDeviceGetQueue(s->device);
  WGPUShaderSourceWGSL wgsl=WGPU_SHADER_SOURCE_WGSL_INIT;wgsl.code=(WGPUStringView){shader,WGPU_STRLEN};
  WGPUShaderModuleDescriptor sd=WGPU_SHADER_MODULE_DESCRIPTOR_INIT;sd.nextInChain=&wgsl.chain;
  WGPUShaderModule module=wgpuDeviceCreateShaderModule(s->device,&sd);
  if(!module||atomic_load(&s->error)){if(module)wgpuShaderModuleRelease(module);goto fail;}
  s->pipeline=pipeline(s,module,"vertex","fragment");s->layer=pipeline(s,module,"layer_vertex","layer_fragment");
  WGPUComputePipelineDescriptor pd=WGPU_COMPUTE_PIPELINE_DESCRIPTOR_INIT;pd.compute.module=module;
  pd.compute.entryPoint=(WGPUStringView){"resolve",7};
  WGPUBindGroupLayoutEntry entries[4]={WGPU_BIND_GROUP_LAYOUT_ENTRY_INIT,WGPU_BIND_GROUP_LAYOUT_ENTRY_INIT,WGPU_BIND_GROUP_LAYOUT_ENTRY_INIT,WGPU_BIND_GROUP_LAYOUT_ENTRY_INIT};
  entries[0].binding=5;entries[0].visibility=WGPUShaderStage_Compute;entries[0].texture.sampleType=WGPUTextureSampleType_UnfilterableFloat;
  entries[0].texture.viewDimension=WGPUTextureViewDimension_2D;
  entries[1].binding=6;entries[1].visibility=WGPUShaderStage_Compute;entries[1].storageTexture.access=WGPUStorageTextureAccess_WriteOnly;
  entries[1].storageTexture.format=WGPUTextureFormat_RGBA8Unorm;entries[1].storageTexture.viewDimension=WGPUTextureViewDimension_2D;
  entries[2].binding=7;entries[2].visibility=WGPUShaderStage_Compute;entries[2].buffer.type=WGPUBufferBindingType_Storage;
  entries[3].binding=8;entries[3].visibility=WGPUShaderStage_Compute;entries[3].buffer.type=WGPUBufferBindingType_Uniform;
  WGPUBindGroupLayoutDescriptor gd=WGPU_BIND_GROUP_LAYOUT_DESCRIPTOR_INIT;gd.entryCount=4;gd.entries=entries;
  WGPUBindGroupLayout gl=wgpuDeviceCreateBindGroupLayout(s->device,&gd);
  WGPUPipelineLayoutDescriptor ld=WGPU_PIPELINE_LAYOUT_DESCRIPTOR_INIT;ld.bindGroupLayoutCount=1;ld.bindGroupLayouts=&gl;
  WGPUPipelineLayout pl=wgpuDeviceCreatePipelineLayout(s->device,&ld);pd.layout=pl;
  s->resolve=wgpuDeviceCreateComputePipeline(s->device,&pd);wgpuPipelineLayoutRelease(pl);wgpuBindGroupLayoutRelease(gl);
  wgpuShaderModuleRelease(module);
  s->geometry=buffer(s,(uint64_t)vertices*8,WGPUBufferUsage_Storage|WGPUBufferUsage_CopyDst);
  s->masks=texture(s,1024,1024,WGPUTextureFormat_R8Unorm,WGPUTextureUsage_TextureBinding|WGPUTextureUsage_CopyDst);
  s->images=texture(s,1024,1024,WGPUTextureFormat_RGBA32Float,WGPUTextureUsage_TextureBinding|WGPUTextureUsage_CopyDst);
  if(s->masks)s->mask_view=wgpuTextureCreateView(s->masks,NULL);
  if(s->images)s->image_view=wgpuTextureCreateView(s->images,NULL);
  wgpuDevicePoll(s->device,1,NULL);
  if(!s->pipeline||!s->layer||!s->resolve||!s->geometry||!s->mask_view||!s->image_view||atomic_load(&s->error))goto fail;
  s->bytes=(long long)vertices*8+1024*1024*17LL;*out=s;return 0;
fail:
  snprintf(error,512,"%s",s->message[0]?s->message:"batch GPU initialization failed");release(s);return 2;
}
const char*bgpu_adapter(void*raw){return ((Bgpu*)raw)->adapter_name;}
const char*bgpu_error(void*raw){return ((Bgpu*)raw)->message;}
int bgpu_live(void){return atomic_load(&batch_live);}
void bgpu_close(void*raw){release(raw);}
static Mesh*find_mesh(Bgpu*s,int id){for(int i=0;i<s->mesh_limit;i++)if(s->mesh[i].id==id)return &s->mesh[i];return NULL;}
static int gap(Bgpu*s,int count){
  int p=0;
  while(p<=s->mesh_capacity-count){
    int next=s->mesh_capacity,end=p;
    for(int i=0;i<s->mesh_limit;i++){
      Mesh*m=&s->mesh[i];if(!m->id)continue;
      if(m->offset>=p&&m->offset<next)next=m->offset;
      if(m->offset<=p&&m->offset+m->count>end)end=m->offset+m->count;
    }
    if(end>p){p=end;continue;}
    if(next-p>=count)return p;
    if(next==s->mesh_capacity)break;p=next;
  }
  return -1;
}
int bgpu_mesh_acquire(void*raw,int id,const float*data,int count,int*out){
  Bgpu*s=raw;*out=-1;if(id<1||count<3||count%3||count>s->mesh_capacity)return 4;
  Mesh*m=find_mesh(s,id);
  if(m){if(m->count!=count)return 4;m->refs++;m->used=++s->clock;*out=m->offset;return 0;}
  if(!data)return 7;if(atomic_load(&s->error))return 5;
  int start;
  for(;;){
    m=NULL;Mesh*old=NULL;
    for(int i=0;i<s->mesh_limit;i++){
      Mesh*n=&s->mesh[i];if(!n->id)m=n;else if(!n->refs&&(!old||n->used<old->used))old=n;
    }
    start=gap(s,count);if(m&&start>=0)break;
    if(!old)return 6;memset(old,0,sizeof(*old));s->evictions++;
  }
  wgpuQueueWriteBuffer(s->queue,s->geometry,(uint64_t)start*8,data,(uint64_t)count*8);
  wgpuDevicePoll(s->device,1,NULL);if(atomic_load(&s->error))return 5;
  *m=(Mesh){id,start,count,1,++s->clock};s->mesh_upload+=(long long)count*8;*out=start;return 0;
}
void bgpu_mesh_release(void*raw,int id){Mesh*m=find_mesh(raw,id);if(m&&m->refs>0)m->refs--;}
static Asset*asset_array(Bgpu*s,int kind,int*limit){
  if(kind==1){*limit=s->image_limit;return s->image;}if(kind==2){*limit=s->mask_limit;return s->mask;}*limit=0;return NULL;
}
int bgpu_asset_acquire(void*raw,int kind,int id,const void*data,int w,int h,int*out){
  Bgpu*s=raw;*out=-1;int limit=0;Asset*a=asset_array(s,kind,&limit);
  int cell=kind==1?256:64;
  if(!a||id<1||w<1||h<1||w>cell||h>cell)return 4;
  for(int i=0;i<limit;i++)if(a[i].id==id){
    if(a[i].w!=w||a[i].h!=h)return 4;a[i].refs++;a[i].used=++s->clock;*out=i;return 0;
  }
  if(!data)return 7;if(atomic_load(&s->error))return 5;
  int slot=-1;
  for(int i=0;i<limit;i++)if(!a[i].id){slot=i;break;}
  if(slot<0)for(int i=0;i<limit;i++)if(!a[i].refs&&(slot<0||a[i].used<a[slot].used))slot=i;
  if(slot<0)return 6;
  if(a[slot].id){memset(&a[slot],0,sizeof(Asset));s->evictions++;}
  WGPUTexelCopyTextureInfo dst=WGPU_TEXEL_COPY_TEXTURE_INFO_INIT;
  dst.texture=kind==1?s->images:s->masks;dst.origin=(WGPUOrigin3D){(uint32_t)(slot%(1024/cell)*cell),(uint32_t)(slot/(1024/cell)*cell),0};
  WGPUTexelCopyBufferLayout layout=WGPU_TEXEL_COPY_BUFFER_LAYOUT_INIT;
  layout.bytesPerRow=w*(kind==1?16:1);layout.rowsPerImage=h;
  WGPUExtent3D extent={(uint32_t)w,(uint32_t)h,1};
  uint64_t bytes=(uint64_t)layout.bytesPerRow*h;
  wgpuQueueWriteTexture(s->queue,&dst,data,bytes,&layout,&extent);
  wgpuDevicePoll(s->device,1,NULL);if(atomic_load(&s->error))return 5;
  a[slot]=(Asset){id,w,h,1,++s->clock};s->texture_upload+=(long long)bytes;*out=slot;return 0;
}
void bgpu_asset_release(void*raw,int kind,int id){
  Bgpu*s=raw;int limit;Asset*a=asset_array(s,kind,&limit);
  for(int i=0;i<limit;i++)if(a[i].id==id&&a[i].refs>0){a[i].refs--;return;}
}
long long bgpu_stat(void*raw,int field){
  Bgpu*s=raw;long long pinned=0,entries=0;
  for(int i=0;i<s->mesh_limit;i++)if(s->mesh[i].id){entries++;if(s->mesh[i].refs)pinned++;}
  for(int i=0;i<s->mask_limit;i++)if(s->mask[i].id){entries++;if(s->mask[i].refs)pinned++;}
  for(int i=0;i<s->image_limit;i++)if(s->image[i].id){entries++;if(s->image[i].refs)pinned++;}
  switch(field){case 0:return s->programs;case 1:return s->bytes;case 2:return s->mesh_upload;
    case 3:return s->texture_upload;case 4:return s->program_upload;case 5:return s->evictions;
    case 6:return pinned;case 7:return entries;case 8:return s->submissions;default:return -1;}
}

typedef struct {int kind,first,count;float alpha;} Step;
typedef struct {
  Bgpu*owner;int w,h,layers,nsteps,nmeshes,nassets;
  WGPUBuffer data,indices,accumulation,phases;WGPUTexture target[3],output;WGPUTextureView view[3],out_view;
  WGPUBindGroup group[4],composite[3],resolve[4];long long bytes;
  Step*steps;int mesh_ids[1024],asset_ids[272],asset_kinds[272];
} BgpuProgram;
static void program_free(BgpuProgram*p){
  for(int i=0;i<4;i++){if(p->group[i])wgpuBindGroupRelease(p->group[i]);if(p->resolve[i])wgpuBindGroupRelease(p->resolve[i]);}
  for(int i=0;i<3;i++){
    if(p->composite[i])wgpuBindGroupRelease(p->composite[i]);
    if(p->view[i])wgpuTextureViewRelease(p->view[i]);if(p->target[i])wgpuTextureRelease(p->target[i]);
  }
  if(p->out_view)wgpuTextureViewRelease(p->out_view);if(p->output)wgpuTextureRelease(p->output);
  if(p->data)wgpuBufferRelease(p->data);if(p->indices)wgpuBufferRelease(p->indices);
  if(p->accumulation)wgpuBufferRelease(p->accumulation);if(p->phases)wgpuBufferRelease(p->phases);
  free(p->steps);free(p);
}
int bgpu_retain(void*raw,const float*data,int ndata,const uint32_t*indices,int nindices,
    const Step*steps,int nsteps,int w,int h,int layers,const int*mesh_ids,int nmeshes,
    const int*asset_ids,const int*asset_kinds,int nassets,void**out){
  Bgpu*s=raw;*out=NULL;
  if(w<1||h<1||w>640||h>360||layers<0||layers>2||ndata<4||ndata>1048576||nindices<0||nindices>262144||
    nsteps<0||nsteps>24576||nmeshes<0||nmeshes>1024||nassets<0||nassets>272)return 4;
  long long bytes=(long long)ndata*4+(long long)(nindices?nindices:1)*8+(long long)w*h*4*16*(layers+1)+(long long)w*h*20+1024;
  if(s->programs>=16||bytes>201326592-s->bytes)return 6;
  for(int i=0;i<nmeshes;i++){Mesh*m=find_mesh(s,mesh_ids[i]);if(!m||!m->refs)return 4;}
  for(int i=0;i<nassets;i++){
    int limit;Asset*a=asset_array(s,asset_kinds[i],&limit);int found=0;
    for(int j=0;j<limit;j++)if(a[j].id==asset_ids[i]&&a[j].refs)found=1;
    if(!found)return 4;
  }
  if(atomic_load(&s->error))return 5;
  BgpuProgram*p=calloc(1,sizeof(*p));if(!p)return 1;
  p->owner=s;p->w=w;p->h=h;p->layers=layers;p->bytes=bytes;p->nsteps=nsteps;
  if(nsteps){p->steps=malloc((size_t)nsteps*sizeof(Step));if(!p->steps)goto fail;memcpy(p->steps,steps,(size_t)nsteps*sizeof(Step));}
  p->data=buffer(s,(uint64_t)ndata*4,WGPUBufferUsage_Storage|WGPUBufferUsage_CopyDst);
  p->indices=buffer(s,(uint64_t)(nindices?nindices:1)*8,WGPUBufferUsage_Storage|WGPUBufferUsage_CopyDst);
  p->accumulation=buffer(s,(uint64_t)w*h*16,WGPUBufferUsage_Storage);
  p->phases=buffer(s,1024,WGPUBufferUsage_Uniform|WGPUBufferUsage_CopyDst);
  for(int i=0;i<=layers;i++){
    p->target[i]=texture(s,w*4,h,WGPUTextureFormat_RGBA32Float,WGPUTextureUsage_RenderAttachment|WGPUTextureUsage_TextureBinding);
    if(!p->target[i])goto fail;p->view[i]=wgpuTextureCreateView(p->target[i],NULL);if(!p->view[i])goto fail;
  }
  p->output=texture(s,w,h,WGPUTextureFormat_RGBA8Unorm,WGPUTextureUsage_StorageBinding|WGPUTextureUsage_CopySrc);
  if(!p->data||!p->indices||!p->output||!p->accumulation||!p->phases)goto fail;
  p->out_view=wgpuTextureCreateView(p->output,NULL);
  WGPUBindGroupLayout layout=wgpuRenderPipelineGetBindGroupLayout(s->pipeline,0);
  WGPUBindGroupEntry entries[6]={WGPU_BIND_GROUP_ENTRY_INIT,WGPU_BIND_GROUP_ENTRY_INIT,WGPU_BIND_GROUP_ENTRY_INIT,WGPU_BIND_GROUP_ENTRY_INIT,WGPU_BIND_GROUP_ENTRY_INIT,WGPU_BIND_GROUP_ENTRY_INIT};
  entries[0].binding=0;entries[0].buffer=p->data;entries[0].size=(uint64_t)ndata*4;
  entries[1].binding=1;entries[1].buffer=p->indices;entries[1].size=(uint64_t)(nindices?nindices:1)*8;
  entries[2].binding=2;entries[2].buffer=s->geometry;entries[2].size=(uint64_t)s->mesh_capacity*8;
  entries[3].binding=3;entries[3].textureView=s->mask_view;entries[4].binding=4;entries[4].textureView=s->image_view;
  entries[5].binding=8;entries[5].buffer=p->phases;entries[5].size=16;
  WGPUBindGroupDescriptor gd=WGPU_BIND_GROUP_DESCRIPTOR_INIT;gd.layout=layout;gd.entryCount=6;gd.entries=entries;
  for(int i=0;i<4;i++){entries[5].offset=(uint64_t)i*256;p->group[i]=wgpuDeviceCreateBindGroup(s->device,&gd);}
  wgpuBindGroupLayoutRelease(layout);
  layout=wgpuRenderPipelineGetBindGroupLayout(s->layer,0);
  for(int i=1;i<=layers;i++){
    WGPUBindGroupEntry e=WGPU_BIND_GROUP_ENTRY_INIT;e.binding=5;e.textureView=p->view[i];
    gd.layout=layout;gd.entryCount=1;gd.entries=&e;p->composite[i]=wgpuDeviceCreateBindGroup(s->device,&gd);
  }
  wgpuBindGroupLayoutRelease(layout);
  layout=wgpuComputePipelineGetBindGroupLayout(s->resolve,0);
  WGPUBindGroupEntry re[4]={WGPU_BIND_GROUP_ENTRY_INIT,WGPU_BIND_GROUP_ENTRY_INIT,WGPU_BIND_GROUP_ENTRY_INIT,WGPU_BIND_GROUP_ENTRY_INIT};
  re[0].binding=5;re[0].textureView=p->view[0];re[1].binding=6;re[1].textureView=p->out_view;
  re[2].binding=7;re[2].buffer=p->accumulation;re[2].size=(uint64_t)w*h*16;
  re[3].binding=8;re[3].buffer=p->phases;re[3].size=16;
  gd.layout=layout;gd.entryCount=4;gd.entries=re;
  for(int i=0;i<4;i++){re[3].offset=(uint64_t)i*256;p->resolve[i]=wgpuDeviceCreateBindGroup(s->device,&gd);}
  wgpuBindGroupLayoutRelease(layout);
  for(int i=0;i<4;i++)if(!p->group[i]||!p->resolve[i])goto fail;
  if(atomic_load(&s->error))goto fail;
  uint32_t phases[256]={0};for(int i=0;i<4;i++)phases[i*64]=i;
  wgpuQueueWriteBuffer(s->queue,p->phases,0,phases,sizeof(phases));
  wgpuQueueWriteBuffer(s->queue,p->data,0,data,(uint64_t)ndata*4);
  if(nindices)wgpuQueueWriteBuffer(s->queue,p->indices,0,indices,(uint64_t)nindices*8);
  wgpuDevicePoll(s->device,1,NULL);if(atomic_load(&s->error))goto fail;
  p->nmeshes=nmeshes;p->nassets=nassets;
  if(nmeshes)memcpy(p->mesh_ids,mesh_ids,(size_t)nmeshes*sizeof(int));
  if(nassets){memcpy(p->asset_ids,asset_ids,(size_t)nassets*sizeof(int));memcpy(p->asset_kinds,asset_kinds,(size_t)nassets*sizeof(int));}
  atomic_fetch_add(&s->refs,1);s->bytes+=bytes;s->programs++;s->program_upload+=(long long)ndata*4+(long long)nindices*8+1024;
  *out=p;return 0;
fail:program_free(p);return 5;
}
static WGPURenderPassEncoder pass(WGPUCommandEncoder encoder,WGPUTextureView view,int clear){
  WGPURenderPassColorAttachment color=WGPU_RENDER_PASS_COLOR_ATTACHMENT_INIT;
  color.view=view;color.loadOp=clear?WGPULoadOp_Clear:WGPULoadOp_Load;color.storeOp=WGPUStoreOp_Store;
  color.clearValue=(WGPUColor){0,0,0,0};
  WGPURenderPassDescriptor pd=WGPU_RENDER_PASS_DESCRIPTOR_INIT;pd.colorAttachmentCount=1;pd.colorAttachments=&color;
  return wgpuCommandEncoderBeginRenderPass(encoder,&pd);
}
int bgpu_replay(void*raw,unsigned char*out){
  BgpuProgram*p=raw;Bgpu*s=p->owner;if(atomic_load(&s->error))return 5;
  WGPUCommandEncoder encoder=wgpuDeviceCreateCommandEncoder(s->device,NULL);
  for(int row=0;row<4;row++){
  WGPURenderPassEncoder rp=pass(encoder,p->view[0],1);
  int depth=0;float opacity[3]={1,1,1};
  for(int i=0;i<p->nsteps;i++){
    Step*t=&p->steps[i];
    if(t->kind==0){
      wgpuRenderPassEncoderSetPipeline(rp,s->pipeline);wgpuRenderPassEncoderSetBindGroup(rp,0,p->group[row],0,NULL);
      wgpuRenderPassEncoderDraw(rp,6,(uint32_t)t->count/3,0,(uint32_t)t->first/3);
    }else{
      wgpuRenderPassEncoderEnd(rp);wgpuRenderPassEncoderRelease(rp);
      if(t->kind==1){depth++;opacity[depth]=t->alpha;rp=pass(encoder,p->view[depth],1);}
      else{
        uint32_t bits;memcpy(&bits,&opacity[depth],sizeof(bits));
        WGPUBindGroup group=p->composite[depth];depth--;rp=pass(encoder,p->view[depth],0);
        wgpuRenderPassEncoderSetPipeline(rp,s->layer);wgpuRenderPassEncoderSetBindGroup(rp,0,group,0,NULL);
        wgpuRenderPassEncoderDraw(rp,3,1,0,bits);
      }
    }
  }
  wgpuRenderPassEncoderEnd(rp);wgpuRenderPassEncoderRelease(rp);
  WGPUComputePassEncoder cp=wgpuCommandEncoderBeginComputePass(encoder,NULL);
  wgpuComputePassEncoderSetPipeline(cp,s->resolve);wgpuComputePassEncoderSetBindGroup(cp,0,p->resolve[row],0,NULL);
  wgpuComputePassEncoderDispatchWorkgroups(cp,(uint32_t)(p->w+7)/8,(uint32_t)(p->h+7)/8,1);
  wgpuComputePassEncoderEnd(cp);wgpuComputePassEncoderRelease(cp);
  }
  WGPUCommandBuffer cmd=wgpuCommandEncoderFinish(encoder,NULL);wgpuQueueSubmit(s->queue,1,&cmd);
  wgpuDevicePoll(s->device,1,NULL);wgpuCommandBufferRelease(cmd);wgpuCommandEncoderRelease(encoder);
  if(atomic_load(&s->error))return 5;s->submissions++;
  for(int i=0;i<p->nmeshes;i++){Mesh*m=find_mesh(s,p->mesh_ids[i]);if(m)m->used=++s->clock;}
  for(int i=0;i<p->nassets;i++){
    int n;Asset*a=asset_array(s,p->asset_kinds[i],&n);
    for(int j=0;j<n;j++)if(a[j].id==p->asset_ids[i])a[j].used=++s->clock;
  }
  return out?readback(s,p->output,p->w,p->h,out):0;
}
void bgpu_program_close(void*raw){
  BgpuProgram*p=raw;Bgpu*s=p->owner;wgpuDevicePoll(s->device,1,NULL);
  for(int i=0;i<p->nmeshes;i++)bgpu_mesh_release(s,p->mesh_ids[i]);
  for(int i=0;i<p->nassets;i++)bgpu_asset_release(s,p->asset_kinds[i],p->asset_ids[i]);
  s->bytes-=p->bytes;s->programs--;program_free(p);release(s);
}
