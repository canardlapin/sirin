@group(0) @binding(0) var<storage, read> data: array<vec4f>;
@group(0) @binding(2) var<storage, read> assets: array<vec4f>;
@group(0) @binding(1) var output: texture_storage_2d<rgba8unorm, write>;

fn inside(p: vec2f, start: u32, count: u32, rule: u32) -> bool {
  var winding = 0i;
  for (var i = 0u; i < count; i++) {
    let e = data[start + i];
    if ((e.y <= p.y && e.w > p.y) || (e.w <= p.y && e.y > p.y)) {
      // Relative orientation avoids division and absolute ray-intersection
      // rounding that changed boundary samples after integer tile rebasing.
      let cross = (e.z-e.x)*(p.y-e.y)-(e.w-e.y)*(p.x-e.x);
      if ((e.w > e.y && cross > 0.0) || (e.w < e.y && cross < 0.0)) {
        winding += select(-1i, 1i, e.w > e.y);
      }
    }
  }
  return select(winding != 0i, (abs(winding) % 2i) != 0i, rule == 1u);
}
fn pixel(use_assets: bool, base: u32, width: i32, height: i32, x: i32, y: i32) -> vec4f {
  let index=base + u32(clamp(y,0,height-1)*width + clamp(x,0,width-1));
  if (use_assets) { return assets[index]; }
  return data[index];
}
fn over(src: vec4f, dst: vec4f) -> vec4f { return src + dst * (1.0-src.a); }
fn gradient(p: vec2f, base: u32) -> vec4f {
  let projection=data[base]; let stops=data[base+1];
  var t=dot(p-stops.zw,projection.xy)+projection.z;
  if (projection.w==1.0) { t=t-floor(t); }
  if (projection.w==2.0) { let q=t-2.0*floor(t/2.0); t=select(q,2.0-q,q>1.0); }
  t=clamp(t,0.0,1.0);
  var previous=data[u32(stops.x)+1u];
  var position=data[u32(stops.x)].x;
  if (t<position) { return previous; }
  for (var i=1u; i<u32(stops.y); i++) {
    let at=u32(stops.x)+i*2u;
    let next=data[at].x; let color=data[at+1u];
    if (t<next) { return mix(previous,color,(t-position)/(next-position)); }
    previous=color; position=next;
  }
  return previous;
}
fn shade(p: vec2f) -> vec4f {
  var layers: array<vec4f,9>;
  var alpha: array<f32,9>;
  var depth = 0u;
  layers[0] = vec4f(0);
  for (var i = 0u; i < u32(data[0].z); i++) {
    let base = 1u+i*8u;
    let a = data[base]; let color = data[base+1];
    if (a.x == 3.0) { depth++; layers[depth]=vec4f(0); alpha[depth]=color.a; continue; }
    if (a.x == 4.0) { let src=layers[depth]*alpha[depth]; depth--; layers[depth]=over(src,layers[depth]); continue; }
    var admitted=true;
    let info=data[base+6]; let extra=data[base+7];
    for (var j=0u; j<u32(extra.x); j++) {
      let c=data[u32(info.w)+j];
      if (!inside(p,u32(c.x),u32(c.y),u32(c.z))) { admitted=false; break; }
    }
    if (!admitted) { continue; }
    if (a.x == 1.0 && inside(p,u32(a.y),u32(a.z),u32(a.w))) {
      var painted=color;
      if (extra.y>0.0) { painted=gradient(p,u32(extra.y)-1u); }
      layers[depth]=over(painted,layers[depth]);
    }
    if (a.x == 2.0) {
      let rowx=data[base+2]; let rowy=data[base+3]; let dest=data[base+4]; let src=data[base+5];
      let local=vec2f(dot(rowx.xyz,vec3f(p,1)),dot(rowy.xyz,vec3f(p,1)));
      let uv=(local-dest.xy)/dest.zw;
      if (all(uv>=vec2f(0)) && all(uv<vec2f(1))) {
        let xy=src.xy+uv*src.zw;
        var sampled=vec4f(0);
        if (rowy.w == 0.0) { sampled=pixel(extra.z==1.0,u32(info.x),i32(info.y),i32(info.z),i32(floor(xy.x)),i32(floor(xy.y))); }
        else {
          let pos=xy-vec2f(0.5); let ix=i32(floor(pos.x)); let iy=i32(floor(pos.y)); let f=fract(pos);
          let p00=pixel(extra.z==1.0,u32(info.x),i32(info.y),i32(info.z),ix,iy);
          let p10=pixel(extra.z==1.0,u32(info.x),i32(info.y),i32(info.z),ix+1,iy);
          let p01=pixel(extra.z==1.0,u32(info.x),i32(info.y),i32(info.z),ix,iy+1);
          let p11=pixel(extra.z==1.0,u32(info.x),i32(info.y),i32(info.z),ix+1,iy+1);
          sampled=mix(mix(p00,p10,f.x),mix(p01,p11,f.x),f.y);
        }
        if (extra.w==1.0) {
          var paint=color;
          if (extra.y>0.0) { paint=gradient(p,u32(extra.y)-1u); }
          sampled=paint*sampled.a;
        }
        layers[depth]=over(sampled*rowx.w,layers[depth]);
      }
    }
  }
  return layers[0];
}
fn srgb(x: vec3f) -> vec3f {
  return select(12.92*x,1.055*pow(max(x,vec3f(0)),vec3f(1.0/2.4))-vec3f(0.055),x>vec3f(0.0031308));
}
@compute @workgroup_size(8,8)
fn main(@builtin(global_invocation_id) id: vec3u) {
  if (id.x>=u32(data[0].x) || id.y>=u32(data[0].y)) { return; }
  var value=vec4f(0);
  for (var y=0u; y<4u; y++) { for (var x=0u; x<4u; x++) {
    value += shade(vec2f(id.xy)+(vec2f(f32(x),f32(y))+vec2f(0.5))/4.0);
  }}
  value /= 16.0;
  var rgb=vec3f(0);
  if (value.a>0) { rgb=srgb(value.rgb/value.a); }
  textureStore(output,vec2i(id.xy),vec4f(rgb,value.a));
}
