// Included after the two renderer implementations. Native reference records only.
typedef struct {
  _Atomic int refs; Bgpu *owner; WGPUTexture texture; WGPUTextureView view;
  int w,h;
} DesktopTile;
typedef struct {
  Bgpu *owner; int w,h,count; DesktopTile *tiles[256]; int x[256],y[256];
} DesktopFrame;
static _Atomic int gpu_tiles,gpu_frames;
static _Atomic long long gpu_output_bytes;
int sirin_desktop_gpu_tiles(void){return atomic_load(&gpu_tiles);}
int sirin_desktop_gpu_frames(void){return atomic_load(&gpu_frames);}
long long sirin_desktop_gpu_output_bytes(void){return atomic_load(&gpu_output_bytes);}

static void desktop_lost_wake(int window,int type){if(window&&type)sirin_desktop_wake(window,type);}
int sirin_desktop_renderer_lost(void*raw){Desktop*d=raw;return owner(d)&&d->batch?bgpu_lost(d->batch):0;}
int sirin_desktop_renderer_destroy(void*raw){Desktop*d=raw;if(!owner(d)||!d->batch)return 4;bgpu_destroy_device(d->batch);return 0;}
// Main owns the device lifetime; the worker receives one independent reference.
int sirin_desktop_batch_start(void *raw,const char *shader,void **out,char *error){
  Desktop*d=raw;*out=NULL;if(!owner(d)||(d->batch&&!bgpu_lost(d->batch)))return 4;
  Bgpu*s=NULL;int status=bgpu_open(shader,1048576,256,256,16,(void**)&s,error);
  if(status)return status;
  // wgpu surface IDs belong to their instance. Recreate on the batch instance
  // before querying that adapter, rather than crossing independent registries.
  WGPUSurfaceSourceMetalLayer layer=WGPU_SURFACE_SOURCE_METAL_LAYER_INIT;
  layer.layer=SDL_Metal_GetLayer(d->metal);
  WGPUSurfaceDescriptor descriptor=WGPU_SURFACE_DESCRIPTOR_INIT;descriptor.nextInChain=&layer.chain;
  WGPUSurface surface=wgpuInstanceCreateSurface(s->instance,&descriptor);
  if(!surface){bgpu_close(s);return 5;}
  WGPUSurfaceCapabilities caps=WGPU_SURFACE_CAPABILITIES_INIT;
  if(wgpuSurfaceGetCapabilities(surface,s->adapter,&caps)!=WGPUStatus_Success){wgpuSurfaceRelease(surface);bgpu_close(s);return 5;}
  int supported=0;
  int copy_dst=(caps.usages&WGPUTextureUsage_CopyDst)!=0;
  for(size_t i=0;i<caps.formatCount;i++)if(caps.formats[i]==WGPUTextureFormat_BGRA8UnormSrgb)supported=1;
  wgpuSurfaceCapabilitiesFreeMembers(caps);
  if(!supported){snprintf(error,512,"shared batch surface requires BGRA8UnormSrgb");wgpuSurfaceRelease(surface);bgpu_close(s);return 4;}
  if(d->configured)wgpuSurfaceUnconfigure(d->surface);d->configured=0;
  if(d->blit){wgpuRenderPipelineRelease(d->blit);d->blit=NULL;}
  wgpuQueueRelease(d->queue);wgpuDeviceRelease(d->device);
  wgpuSurfaceRelease(d->surface);wgpuAdapterRelease(d->adapter);wgpuInstanceRelease(d->instance);
  d->surface=surface;d->instance=s->instance;wgpuInstanceAddRef(d->instance);
  d->adapter=s->adapter;wgpuAdapterAddRef(d->adapter);
  d->device=s->device;wgpuDeviceAddRef(d->device);d->queue=s->queue;wgpuQueueAddRef(d->queue);
  if(d->batch){
    Bgpu*old=d->batch;atomic_store(&old->health->notify,NULL);bgpu_close(old);
  }
  d->copy_dst=copy_dst;d->format=WGPUTextureFormat_BGRA8UnormSrgb;d->batch=s;s->deferred=1;
  atomic_store(&s->health->window,(int)d->window_id);atomic_store(&s->health->wake,(int)d->wake_type);
  atomic_store(&s->health->notify,desktop_lost_wake);
  atomic_fetch_add(&s->refs,1);*out=s;return 0;
}
static void tile_release(DesktopTile*t){
  if(atomic_fetch_sub(&t->refs,1)!=1)return;
  wgpuTextureViewRelease(t->view);wgpuTextureRelease(t->texture);
  atomic_fetch_sub(&gpu_output_bytes,(long long)t->w*t->h*4);
  atomic_fetch_sub(&gpu_tiles,1);desktop_batch_release(t->owner);free(t);
}
void sirin_desktop_tile_close(void*raw){tile_release(raw);}
int sirin_desktop_tile_export(void*raw,void**out){
  BgpuProgram*p=raw;*out=NULL;
  long long bytes=(long long)p->w*p->h*4;
  // Outputs, including frames surviving cache retirement, stay within 16MiB.
  DesktopTile*t=calloc(1,sizeof(*t));if(!t)return 1;
  atomic_init(&t->refs,1);t->owner=p->owner;t->w=p->w;t->h=p->h;
  t->texture=p->output;wgpuTextureAddRef(t->texture);
  t->view=wgpuTextureCreateView(t->texture,NULL);
  if(!t->view){wgpuTextureRelease(t->texture);free(t);return 5;}
  long long reserved=atomic_fetch_add(&gpu_output_bytes,bytes);
  if(reserved+bytes>16777216){atomic_fetch_sub(&gpu_output_bytes,bytes);wgpuTextureViewRelease(t->view);wgpuTextureRelease(t->texture);free(t);return 6;}
  atomic_fetch_add(&t->owner->refs,1);atomic_fetch_add(&gpu_tiles,1);
  *out=t;return 0;
}
int sirin_desktop_frame_open(void**raw,const int*xy,int count,int w,int h,void**out){
  *out=NULL;if(count<1||count>256||w<1||h<1||w>1280||h>800||atomic_load(&gpu_frames)>=3)return 4;
  DesktopTile**tiles=(DesktopTile**)raw;Bgpu*s=tiles[0]->owner;
  long long pixels=0;
  for(int i=0;i<count;i++){
    DesktopTile*t=tiles[i];int x=xy[i*2],y=xy[i*2+1];
    if(t->owner!=s||x<0||y<0||x+t->w>w||y+t->h>h)return 4;
    pixels+=(long long)t->w*t->h;
    for(int j=0;j<i;j++)if(x<xy[j*2]+tiles[j]->w&&xy[j*2]<x+t->w&&y<xy[j*2+1]+tiles[j]->h&&xy[j*2+1]<y+t->h)return 4;
  }
  if(pixels!=(long long)w*h)return 4; // exact, non-overlapping complete coverage
  DesktopFrame*f=calloc(1,sizeof(*f));if(!f)return 1;
  if(atomic_fetch_add(&gpu_frames,1)>=3){atomic_fetch_sub(&gpu_frames,1);free(f);return 4;}
  f->owner=s;f->w=w;f->h=h;f->count=count;
  for(int i=0;i<count;i++){f->tiles[i]=tiles[i];atomic_fetch_add(&tiles[i]->refs,1);f->x[i]=xy[i*2];f->y[i]=xy[i*2+1];}
  *out=f;return 0;
}
void sirin_desktop_frame_close(void*raw){
  DesktopFrame*f=raw;for(int i=0;i<f->count;i++)tile_release(f->tiles[i]);
  atomic_fetch_sub(&gpu_frames,1);free(f);
}
int sirin_desktop_frame_read(void*raw,unsigned char*out){
  DesktopFrame*f=raw;if(bgpu_lost(f->owner))return 8;
  for(int i=0;i<f->count;i++){
    DesktopTile*t=f->tiles[i];size_t size=(size_t)t->w*t->h*4;
    unsigned char*tile=malloc(size);if(!tile)return 1;
    int status=desktop_batch_readback(t->owner,t->texture,t->w,t->h,tile);
    if(!status)for(int y=0;y<t->h;y++)memcpy(out+((size_t)(y+f->y[i])*f->w+f->x[i])*4,tile+(size_t)y*t->w*4,(size_t)t->w*4);
    free(tile);if(status)return status;
  }
  return 0;
}
static int tile_pipeline(Desktop*d){
  if(d->blit)return 0;
  const char*code=
    "@group(0) @binding(0) var image:texture_2d<f32>;"
    "struct V{@builtin(position) p:vec4<f32>,@location(0) uv:vec2<f32>};"
    "@vertex fn vs(@builtin(vertex_index) i:u32)->V{var p=array<vec2<f32>,3>(vec2<f32>(-1,-1),vec2<f32>(3,-1),vec2<f32>(-1,3));var o:V;o.p=vec4<f32>(p[i],0,1);o.uv=vec2<f32>(p[i].x*0.5+0.5,0.5-p[i].y*0.5);return o;}"
    "fn linear(c:vec3<f32>)->vec3<f32>{return select(pow((c+0.055)/1.055,vec3<f32>(2.4)),c/12.92,c<=vec3<f32>(0.04045));}"
    "@fragment fn fs(v:V)->@location(0) vec4<f32>{let c=textureLoad(image,vec2<i32>(v.uv*vec2<f32>(textureDimensions(image))),0);return vec4<f32>(linear(c.rgb),c.a);}";
  WGPUShaderSourceWGSL wgsl=WGPU_SHADER_SOURCE_WGSL_INIT;wgsl.code=(WGPUStringView){code,WGPU_STRLEN};
  WGPUShaderModuleDescriptor sd=WGPU_SHADER_MODULE_DESCRIPTOR_INIT;sd.nextInChain=&wgsl.chain;
  WGPUShaderModule module=wgpuDeviceCreateShaderModule(d->device,&sd);if(!module)return 1;
  WGPUColorTargetState color=WGPU_COLOR_TARGET_STATE_INIT;color.format=d->format;color.writeMask=WGPUColorWriteMask_All;
  WGPUFragmentState fs=WGPU_FRAGMENT_STATE_INIT;fs.module=module;fs.entryPoint=(WGPUStringView){"fs",2};fs.targetCount=1;fs.targets=&color;
  WGPURenderPipelineDescriptor pd=WGPU_RENDER_PIPELINE_DESCRIPTOR_INIT;pd.vertex.module=module;pd.vertex.entryPoint=(WGPUStringView){"vs",2};pd.fragment=&fs;
  d->blit=wgpuDeviceCreateRenderPipeline(d->device,&pd);wgpuShaderModuleRelease(module);return d->blit?0:1;
}
static void draw_frame(Desktop*d,DesktopFrame*f,WGPUTextureView view){
  WGPUCommandEncoder encoder=wgpuDeviceCreateCommandEncoder(d->device,NULL);
  WGPURenderPassEncoder rp=desktop_batch_pass(encoder,view,1);
  WGPUBindGroupLayout layout=wgpuRenderPipelineGetBindGroupLayout(d->blit,0);
  wgpuRenderPassEncoderSetPipeline(rp,d->blit);
  for(int i=0;i<f->count;i++){
    DesktopTile*t=f->tiles[i];WGPUBindGroupEntry e=WGPU_BIND_GROUP_ENTRY_INIT;e.textureView=t->view;
    WGPUBindGroupDescriptor bd=WGPU_BIND_GROUP_DESCRIPTOR_INIT;bd.layout=layout;bd.entryCount=1;bd.entries=&e;
    WGPUBindGroup group=wgpuDeviceCreateBindGroup(d->device,&bd);
    wgpuRenderPassEncoderSetViewport(rp,(float)f->x[i],(float)f->y[i],(float)t->w,(float)t->h,0,1);
    wgpuRenderPassEncoderSetScissorRect(rp,f->x[i],f->y[i],t->w,t->h);
    wgpuRenderPassEncoderSetBindGroup(rp,0,group,0,NULL);wgpuRenderPassEncoderDraw(rp,3,1,0,0);wgpuBindGroupRelease(group);
  }
  wgpuRenderPassEncoderEnd(rp);wgpuRenderPassEncoderRelease(rp);
  WGPUCommandBuffer command=wgpuCommandEncoderFinish(encoder,NULL);wgpuQueueSubmit(d->queue,1,&command);
  wgpuCommandBufferRelease(command);wgpuCommandEncoderRelease(encoder);wgpuBindGroupLayoutRelease(layout);
}
int sirin_desktop_frame_present(void*raw,void*packet){
  Desktop*d=raw;DesktopFrame*f=packet;if(!owner(d))return -1;
  if(d->device!=f->owner->device||bgpu_lost(f->owner))return -2;
  WGPUSurfaceTexture st=WGPU_SURFACE_TEXTURE_INIT;int status=acquire(d,&st);if(status!=1)return status;
  if(d->width!=(uint32_t)f->w||d->height!=(uint32_t)f->h){wgpuTextureRelease(st.texture);return 0;}
  if(tile_pipeline(d)){wgpuTextureRelease(st.texture);return -1;}
  WGPUTextureView view=wgpuTextureCreateView(st.texture,NULL);
  draw_frame(d,f,view);
  int result=wgpuSurfacePresent(d->surface)==WGPUStatus_Success?1:-1;
  wgpuTextureViewRelease(view);wgpuTextureRelease(st.texture);return result;
}
// Explicit verification readback of the same presentation pipeline, never a frame path.
int sirin_desktop_frame_blit_read(void*raw,unsigned char*out){
  DesktopFrame*f=raw;if(bgpu_lost(f->owner))return 8;Desktop d={0};d.device=f->owner->device;d.queue=f->owner->queue;d.format=WGPUTextureFormat_BGRA8UnormSrgb;
  if(tile_pipeline(&d))return 5;
  WGPUTexture target=desktop_batch_texture(f->owner,f->w,f->h,d.format,WGPUTextureUsage_RenderAttachment|WGPUTextureUsage_CopySrc);
  if(!target){wgpuRenderPipelineRelease(d.blit);return 5;}
  WGPUTextureView view=wgpuTextureCreateView(target,NULL);draw_frame(&d,f,view);
  int status=desktop_batch_readback(f->owner,target,f->w,f->h,out);
  if(!status)for(int i=0;i<f->w*f->h;i++){unsigned char red=out[i*4];out[i*4]=out[i*4+2];out[i*4+2]=red;}
  wgpuTextureViewRelease(view);wgpuTextureRelease(target);wgpuRenderPipelineRelease(d.blit);return status;
}

// Host uploads are ordered on one queue; submit completion fences their use.
int sirin_desktop_batch_defer(void*raw){Bgpu*s=raw;if(s->programs)return 4;s->deferred=1;return 0;}
int sirin_desktop_batch_submit(void**raw,int count){
  if(count<1||count>16)return 4;
  BgpuProgram**programs=(BgpuProgram**)raw;Bgpu*s=programs[0]->owner;
  if(bgpu_error_state(s))return 5;
  for(int i=0;i<count;i++)if(programs[i]->owner!=s)return 4;
  WGPUCommandEncoder encoder=wgpuDeviceCreateCommandEncoder(s->device,NULL);
  if(bgpu_error_state(s)){if(encoder)wgpuCommandEncoderRelease(encoder);return 5;}
  for(int i=0;i<count;i++)bgpu_encode(programs[i],encoder);
  WGPUCommandBuffer command=wgpuCommandEncoderFinish(encoder,NULL);wgpuQueueSubmit(s->queue,1,&command);
  wgpuDevicePoll(s->device,1,NULL);wgpuCommandBufferRelease(command);wgpuCommandEncoderRelease(encoder);
  if(bgpu_error_state(s))return 5;
  s->submissions++;for(int i=0;i<count;i++)bgpu_touch(programs[i]);return 0;
}

// Exact-sample camera replay. Previous packets own snapshots, never p->output.
int sirin_desktop_camera(void*raw,void*previous,const float*parameters,void**out){
  BgpuProgram*p=raw;Bgpu*s=p->owner;DesktopTile*prior=previous;*out=NULL;
  if(bgpu_error_state(s))return 8;
  for(int i=0;i<12;i++)if(!isfinite(parameters[i]))return 4;
  float mode=parameters[2];
  if(fabsf(parameters[0])>4096||fabsf(parameters[1])>4096||mode<0||mode>2||floorf(mode)!=mode)return 4;
  if(mode!=0&&(!prior||prior->owner!=s||prior->w!=p->w||prior->h!=p->h))return 4;
  int dx=(int)parameters[8],dy=(int)parameters[9];
  if(mode==1&&(dx!=parameters[8]||dy!=parameters[9]||abs(dx)>=p->w||abs(dy)>=p->h))return 4;
  if(parameters[4]<0||parameters[5]<0||parameters[6]>p->w||parameters[7]>p->h||parameters[4]>parameters[6]||parameters[5]>parameters[7])return 4;
  WGPUTexture snapshot=desktop_batch_texture(s,p->w,p->h,WGPUTextureFormat_RGBA8Unorm,
    WGPUTextureUsage_CopySrc|WGPUTextureUsage_CopyDst|WGPUTextureUsage_TextureBinding);
  if(!snapshot)return 5;
  BgpuProgram packet={0};packet.owner=s;packet.w=p->w;packet.h=p->h;packet.output=snapshot;
  int status=sirin_desktop_tile_export(&packet,out);wgpuTextureRelease(snapshot);
  if(status)return status;
  DesktopTile*result=*out;
  wgpuQueueWriteBuffer(s->queue,p->camera,0,parameters,32);
  WGPUCommandEncoder encoder=wgpuDeviceCreateCommandEncoder(s->device,NULL);
  if(bgpu_error_state(s)){if(encoder)wgpuCommandEncoderRelease(encoder);tile_release(result);*out=NULL;return 8;}
  if(mode!=0){
    WGPUTexelCopyTextureInfo src=WGPU_TEXEL_COPY_TEXTURE_INFO_INIT;src.texture=prior->texture;
    WGPUTexelCopyTextureInfo dst=WGPU_TEXEL_COPY_TEXTURE_INFO_INIT;dst.texture=p->output;
    WGPUExtent3D extent={(uint32_t)p->w,(uint32_t)p->h,1};
    if(mode==1){
      src.origin.x=dx<0?(uint32_t)-dx:0;src.origin.y=dy<0?(uint32_t)-dy:0;
      dst.origin.x=dx>0?(uint32_t)dx:0;dst.origin.y=dy>0?(uint32_t)dy:0;
      extent.width-=abs(dx);extent.height-=abs(dy);
    }
    wgpuCommandEncoderCopyTextureToTexture(encoder,&src,&dst,&extent);
  }
  bgpu_encode(p,encoder);
  WGPUTexelCopyTextureInfo src=WGPU_TEXEL_COPY_TEXTURE_INFO_INIT;src.texture=p->output;
  WGPUTexelCopyTextureInfo dst=WGPU_TEXEL_COPY_TEXTURE_INFO_INIT;dst.texture=result->texture;
  WGPUExtent3D extent={(uint32_t)p->w,(uint32_t)p->h,1};
  wgpuCommandEncoderCopyTextureToTexture(encoder,&src,&dst,&extent);
  WGPUCommandBuffer command=wgpuCommandEncoderFinish(encoder,NULL);wgpuQueueSubmit(s->queue,1,&command);
  wgpuDevicePoll(s->device,1,NULL);wgpuCommandBufferRelease(command);wgpuCommandEncoderRelease(encoder);
  if(bgpu_error_state(s)){tile_release(result);*out=NULL;return 8;}
  s->submissions++;s->program_upload+=32;bgpu_touch(p);return 0;
}
