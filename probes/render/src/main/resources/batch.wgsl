@group(0) @binding(0) var<storage,read> data: array<vec4f>;
@group(0) @binding(1) var<storage,read> indices: array<vec2u>;
@group(0) @binding(2) var<storage,read> geometry: array<vec2f>;
@group(0) @binding(3) var masks: texture_2d<f32>;
@group(0) @binding(4) var images: texture_2d<f32>;
@group(0) @binding(8) var<uniform> phase: vec4u;
struct Vertex {
  @builtin(position) position: vec4f,
  @location(0) @interpolate(flat) record: u32,
  @location(1) @interpolate(flat) triangle: u32
}
@vertex fn vertex(@builtin(vertex_index) v: u32,@builtin(instance_index) i: u32) -> Vertex {
  let item=indices[i];let offset=data[item.y+6u].zw;
  let a=geometry[item.x]+offset;let b=geometry[item.x+1u]+offset;let c=geometry[item.x+2u]+offset;
  let lo=min(min(a,b),c)-vec2f(0.015625);let hi=max(max(a,b),c)+vec2f(0.015625);
  let corners=array<vec2f,6>(vec2f(0,0),vec2f(1,0),vec2f(1,1),vec2f(0,0),vec2f(1,1),vec2f(0,1));
  let p=mix(lo,hi,corners[v]);let shift=0.5-(f32(phase.x)+0.5)/4.0;var out: Vertex;
  out.position=vec4f(2.0*p.x/data[0].x-1.0,1.0-2.0*(p.y+shift)/data[0].y,0,1);
  out.record=item.y;out.triangle=item.x;return out;
}
// Canonical edge orientation gives adjacent triangles bit-identical tests.
// Their shared half-open boundary belongs to exactly one triangle. Hardware
// rasterization only admits a padded bounding quad; f32 predicates own coverage.
fn edge(p: vec2f,a: vec2f,b: vec2f,positive: bool) -> bool {
  let reverse=a.y>b.y || (a.y==b.y && a.x>b.x);
  let start=select(a,b,reverse);let end=select(b,a,reverse);
  let cross=(end.x-start.x)*(p.y-start.y)-(end.y-start.y)*(p.x-start.x);
  let directed=select(cross,-cross,reverse);let value=select(-directed,directed,positive);
  let delta=select(a-b,b-a,positive);
  return value>0.0 || (value==0.0 && (delta.y<0.0 || (delta.y==0.0 && delta.x>0.0)));
}
fn gradient(p: vec2f, base: u32) -> vec4f {
  let projection=data[base];let stops=data[base+1];
  var t=dot(p-stops.zw,projection.xy)+projection.z;
  if (projection.w==1.0) { t=t-floor(t); }
  if (projection.w==2.0) { let q=t-2.0*floor(t/2.0);t=select(q,2.0-q,q>1.0); }
  t=clamp(t,0.0,1.0);
  var previous=data[u32(stops.x)+1u];var position=data[u32(stops.x)].x;
  if (t<position) { return previous; }
  for (var i=1u;i<u32(stops.y);i++) {
    let at=u32(stops.x)+i*2u;let next=data[at].x;let color=data[at+1u];
    if (t<next) { return mix(previous,color,(t-position)/(next-position)); }
    previous=color;position=next;
  }
  return previous;
}
fn pixel(kind: u32,slot: u32,size: vec2i,xy: vec2i) -> vec4f {
  let p=clamp(xy,vec2i(0),size-vec2i(1));
  if (kind==2u) { let origin=vec2i(i32(slot%16u)*64,i32(slot/16u)*64);return vec4f(textureLoad(masks,origin+p,0).r); }
  let origin=vec2i(i32(slot%4u)*256,i32(slot/4u)*256);
  return textureLoad(images,origin+p,0);
}
@fragment fn fragment(in: Vertex) -> @location(0) vec4f {
  let shift=0.5-(f32(phase.x)+0.5)/4.0;
  let p=vec2f(in.position.x/4.0,in.position.y-shift);let base=in.record;let info=data[base];
  let offset=data[base+6u].zw;
  let a=geometry[in.triangle]+offset;let b=geometry[in.triangle+1u]+offset;let c=geometry[in.triangle+2u]+offset;
  let area=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
  if (area==0.0 || !edge(p,a,b,area>0.0) || !edge(p,b,c,area>0.0) || !edge(p,c,a,area>0.0)) { discard; }
  var paint=data[base+1];let brush=u32(data[base+7].x);
  if (brush>0u) { paint=gradient(p,brush-1u); }
  if (info.x==0.0) { return paint; }
  let rowx=data[base+2];let rowy=data[base+3];let dest=data[base+4];let src=data[base+5];
  let local=vec2f(dot(rowx.xyz,vec3f(p,1)),dot(rowy.xyz,vec3f(p,1)));
  let uv=(local-dest.xy)/dest.zw;
  if (any(uv<vec2f(0)) || any(uv>=vec2f(1))) { discard; }
  let xy=src.xy+uv*src.zw;
  let kind=u32(info.x);let slot=u32(info.z);let size=vec2i(data[base+6].xy);
  var sample=vec4f(0);
  if (info.y==0.0) { sample=pixel(kind,slot,size,vec2i(floor(xy))); }
  else {
    let at=xy-vec2f(0.5);let ij=vec2i(floor(at));let f=fract(at);
    sample=mix(mix(pixel(kind,slot,size,ij),pixel(kind,slot,size,ij+vec2i(1,0)),f.x),
      mix(pixel(kind,slot,size,ij+vec2i(0,1)),pixel(kind,slot,size,ij+vec2i(1,1)),f.x),f.y);
  }
  if (kind==2u) { sample=paint*sample.a; }
  return sample*rowx.w;
}

@group(0) @binding(5) var layer: texture_2d<f32>;
struct LayerVertex { @builtin(position) position: vec4f, @location(0) @interpolate(flat) alpha: f32 }
@vertex fn layer_vertex(@builtin(vertex_index) i: u32,@builtin(instance_index) alpha: u32) -> LayerVertex {
  let x=f32((i<<1u)&2u);let y=f32(i&2u);var out: LayerVertex;
  out.position=vec4f(x*2.0-1.0,y*2.0-1.0,0,1);out.alpha=bitcast<f32>(alpha);return out;
}
@fragment fn layer_fragment(in: LayerVertex) -> @location(0) vec4f {
  return textureLoad(layer,vec2i(in.position.xy),0)*in.alpha;
}
@group(0) @binding(7) var<storage,read_write> accumulation: array<vec4f>;
@group(0) @binding(6) var resolved_output: texture_storage_2d<rgba8unorm,write>;
fn srgb(x: vec3f) -> vec3f {
  return select(12.92*x,1.055*pow(max(x,vec3f(0)),vec3f(1.0/2.4))-vec3f(0.055),x>vec3f(0.0031308));
}
@compute @workgroup_size(8,8) fn resolve(@builtin(global_invocation_id) id: vec3u) {
  let size=textureDimensions(resolved_output);
  if (id.x>=size.x || id.y>=size.y) { return; }
  let index=id.y*size.x+id.x;var value=vec4f(0);
  if (phase.x>0u) { value=accumulation[index]; }
  for (var x=0u;x<4u;x++) { value+=textureLoad(layer,vec2i(vec2u(id.x*4u+x,id.y)),0); }
  accumulation[index]=value;
  if (phase.x==3u) {
    value/=16.0;var rgb=vec3f(0);
    if (value.a>0.0) { rgb=srgb(value.rgb/value.a); }
    textureStore(resolved_output,vec2i(id.xy),vec4f(rgb,value.a));
  }
}
