#import <Cocoa/Cocoa.h>
#include <SDL3/SDL.h>
#include "accesskit.h"
#include <stdint.h>
#include <math.h>
#include "accessibility.h"

// AccessKit alone implements the platform accessibility objects. This object
// owns immutable copied snapshots and a bounded action queue; no managed
// callback runs from an accessibility request. Adapter operations stay on main.
@interface SirinAccessKit : NSObject
@property(nonatomic) struct accesskit_macos_subclassing_adapter *adapter;
@property(nonatomic,strong) NSArray *fields;
@property(nonatomic,strong) NSMutableArray *pending;
@property(nonatomic,strong) NSMutableArray *actions;
@property(nonatomic) uint32_t windowId;
@property(nonatomic) uint32_t wakeType;
@property(nonatomic) int64_t revision;
@property(nonatomic) BOOL alive;
@property(nonatomic) int refused;
@property(nonatomic) int32_t customActionSerial;
@property(nonatomic) double scale;
@property(nonatomic,weak) NSWindow *window;
@end
@implementation SirinAccessKit
@end

// Root=0; each positive control gets a disjoint 65536-node namespace.
static accesskit_node_id control_id(NSDictionary *f) {return [f[@"id"] unsignedLongLongValue]<<16;}
static accesskit_text_position position(NSDictionary *f,NSUInteger offset) {
 NSArray *runs=f[@"runs"];accesskit_node_id base=control_id(f);
 for(NSUInteger i=0;i<runs.count;i++) {
  NSRange r=[runs[i][@"range"] rangeValue];
  if(offset<NSMaxRange(r) || i+1==runs.count)
   return (accesskit_text_position){base+i+1,offset>=NSMaxRange(r) && r.length ? 1:0};
 }
 return (accesskit_text_position){base+1,0};
}
static BOOL offset_for(NSDictionary *f,accesskit_text_position pos,NSUInteger *offset) {
 accesskit_node_id base=control_id(f);NSArray *runs=f[@"runs"];
 if(pos.node<=base || pos.node>base+runs.count)return NO;
 NSRange r=[runs[(NSUInteger)(pos.node-base-1)][@"range"] rangeValue];
 if(pos.character_index>(r.length ? 1u:0u))return NO;
 *offset=pos.character_index ? NSMaxRange(r):r.location;return YES;
}
static accesskit_rect ak_rect(NSRect r,double scale) {return (accesskit_rect){r.origin.x*scale,r.origin.y*scale,NSMaxX(r)*scale,NSMaxY(r)*scale};}
static accesskit_role role_for(int kind) {
 static const accesskit_role roles[] = {
  ACCESSKIT_ROLE_TEXT_INPUT, ACCESSKIT_ROLE_MULTILINE_TEXT_INPUT, ACCESSKIT_ROLE_BUTTON,
  ACCESSKIT_ROLE_CHECK_BOX, ACCESSKIT_ROLE_DIALOG, ACCESSKIT_ROLE_LABEL, ACCESSKIT_ROLE_GROUP,
  ACCESSKIT_ROLE_STATUS, ACCESSKIT_ROLE_ALERT, ACCESSKIT_ROLE_LIST, ACCESSKIT_ROLE_LIST_ITEM,
  ACCESSKIT_ROLE_FORM, ACCESSKIT_ROLE_MENU, ACCESSKIT_ROLE_MENU_ITEM, ACCESSKIT_ROLE_TOOLBAR,
  ACCESSKIT_ROLE_CANVAS, ACCESSKIT_ROLE_GRAPHICS_OBJECT
 };
 return roles[kind];
}
static int flags_for(NSDictionary *f) {
 if(f[@"flags"])return [f[@"flags"] intValue];
 return 1 | ([f[@"kind"] intValue] == 2 ? 2 : 0) |
   ([f[@"enabled"] boolValue] ? 0 : 4) | ([f[@"focused"] boolValue] ? 8 : 0);
}
static BOOL has_action(NSDictionary *field, int kind) {
 for(NSDictionary *entry in field[@"actions"])
  if([entry[@"kind"] intValue] == kind)return YES;
 return NO;
}
static NSString *description_for(NSDictionary *field, NSArray *fields) {
 NSString *direct = field[@"help"];
 if(direct.length)return direct;
 NSNumber *target = field[@"describedBy"];
 if(!target.longLongValue)return nil;
 // The pinned Cocoa adapter does not derive Help from describedBy. Keep the
 // relationship and project its bounded textual target into the native description.
 for(NSDictionary *candidate in fields) {
  if(![candidate[@"id"] isEqual:target])continue;
  NSString *value = candidate[@"value"];
  if([candidate[@"kind"] intValue] == 5 && value.length)return value;
  return candidate[@"label"];
 }
 return nil;
}
static void extra_actions(struct accesskit_node *node, NSDictionary *field) {
 NSArray *actions = field[@"actions"];
 for(NSUInteger index = 0; index < actions.count; index++) {
  NSDictionary *entry = actions[index];
  int kind = [entry[@"kind"] intValue];
  if(kind == 5)accesskit_node_add_action(node, ACCESSKIT_ACTION_INCREMENT);
  else if(kind == 6)accesskit_node_add_action(node, ACCESSKIT_ACTION_DECREMENT);
  else {
   struct accesskit_custom_action *custom = accesskit_custom_action_new([entry[@"customId"] intValue]);
   NSString *name = kind == 7 ? @"Dismiss" : entry[@"name"];
   accesskit_custom_action_set_description(custom, name.UTF8String);
   accesskit_node_push_custom_action(node, custom);
   accesskit_node_add_action(node, ACCESSKIT_ACTION_CUSTOM_ACTION);
  }
 }
}
static struct accesskit_tree_update *tree(void *raw) {
 @autoreleasepool {
 SirinAccessKit *s=(__bridge SirinAccessKit*)raw;NSArray *fields;double scale;
 @synchronized(s){fields=s.alive ? s.fields:@[];scale=s.scale>0 ? s.scale:1;}
 accesskit_node_id focus=0;for(NSDictionary *f in fields)if(flags_for(f) & 8)focus=control_id(f);
 struct accesskit_tree_update *update=accesskit_tree_update_with_focus(focus);
 struct accesskit_tree_info *info=accesskit_tree_info_new(0);
 accesskit_tree_info_set_toolkit_name(info,"Sirin");accesskit_tree_update_set_tree_info(update,info);
 struct accesskit_node *root=accesskit_node_new(ACCESSKIT_ROLE_WINDOW);
 accesskit_node_set_label(root,"Sirin editable workbench");
 NSMutableDictionary *nodes = [NSMutableDictionary new];
 for(NSDictionary *f in fields) {
  struct accesskit_node *node = accesskit_node_new(role_for([f[@"kind"] intValue]));
  nodes[f[@"id"]] = [NSValue valueWithPointer:node];
 }
 // Complete parent links before pushing any owned node into the update.
 for(NSDictionary *f in fields) {
  struct accesskit_node *parent = [nodes[f[@"parent"]] pointerValue];
  accesskit_node_push_child(parent ? parent : root,control_id(f));
 }
 for(NSDictionary *f in fields) {
  accesskit_node_id base=control_id(f);int kind=[f[@"kind"] intValue];
  struct accesskit_node *node = [nodes[f[@"id"]] pointerValue];
  if([f[@"label"] length])accesskit_node_set_label(node,[f[@"label"] UTF8String]);
  NSString *description = description_for(f, fields);
  if(description.length)accesskit_node_set_description(node, description.UTF8String);
  if([f[@"labelledBy"] longLongValue])
   accesskit_node_push_labelled_by(node, [f[@"labelledBy"] unsignedLongLongValue] << 16);
  if([f[@"describedBy"] longLongValue])
   accesskit_node_push_described_by(node, [f[@"describedBy"] unsignedLongLongValue] << 16);
  if([f[@"activeDescendant"] longLongValue])
   accesskit_node_set_active_descendant(node, [f[@"activeDescendant"] unsignedLongLongValue] << 16);
  if([f[@"position"] longLongValue]) {
   accesskit_node_set_position_in_set(node, [f[@"position"] unsignedLongLongValue]);
   accesskit_node_set_size_of_set(node, [f[@"setSize"] unsignedLongLongValue]);
  }
  accesskit_node_set_bounds(node,ak_rect([f[@"bounds"] rectValue],scale));
  int flags = flags_for(f);
  if(flags & 4)accesskit_node_set_disabled(node);
  if(flags & 1)accesskit_node_add_action(node,ACCESSKIT_ACTION_FOCUS);
  if(flags & 2)accesskit_node_add_action(node,ACCESSKIT_ACTION_CLICK);
  extra_actions(node, f);
  if(flags & 16)accesskit_node_set_modal(node);
  if(flags & 32)accesskit_node_set_invalid(node,ACCESSKIT_INVALID_TRUE);
  if(flags & 64)accesskit_node_set_busy(node);
  if(flags & 256)accesskit_node_set_selected(node,(flags & 128) != 0);
  if(flags & 512)accesskit_node_set_live(node,ACCESSKIT_LIVE_POLITE);
  if(flags & 1024)accesskit_node_set_live(node,ACCESSKIT_LIVE_ASSERTIVE);
  if(f[@"checked"] && [f[@"checked"] intValue] >= 0)
   accesskit_node_set_toggled(node,[f[@"checked"] intValue] ? ACCESSKIT_TOGGLED_TRUE : ACCESSKIT_TOGGLED_FALSE);
  if(kind >= 2 && [f[@"value"] length])accesskit_node_set_value(node,[f[@"value"] UTF8String]);
  else if(kind == 5 && [f[@"label"] length])accesskit_node_set_value(node,[f[@"label"] UTF8String]);
  if(kind < 2) {
   accesskit_node_set_value(node,[f[@"value"] UTF8String]);
   accesskit_node_add_action(node,ACCESSKIT_ACTION_SET_VALUE);
   if([f[@"rich"] boolValue])accesskit_node_add_action(node,ACCESSKIT_ACTION_SET_TEXT_SELECTION);
   NSArray *runs=[f[@"rich"] boolValue] ? f[@"runs"]:@[];
   for(NSUInteger i=0;i<runs.count;i++) {
    NSDictionary *r=runs[i];NSString *value=r[@"value"];
    struct accesskit_node *run=accesskit_node_new(ACCESSKIT_ROLE_TEXT_RUN);
    accesskit_node_id id=base+i+1;accesskit_node_push_child(node,id);
    accesskit_node_set_value(run,value.UTF8String);
    NSRect bounds=[r[@"bounds"] rectValue];accesskit_node_set_bounds(run,ak_rect(bounds,scale));
    uint8_t bytes=(uint8_t)[value lengthOfBytesUsingEncoding:NSUTF8StringEncoding];float x=0,width=bounds.size.width*scale;
    accesskit_node_set_character_lengths(run,value.length ? 1:0,&bytes);
    accesskit_node_set_character_positions(run,value.length ? 1:0,&x);
    accesskit_node_set_character_widths(run,value.length ? 1:0,&width);
    accesskit_node_set_text_direction(run,[r[@"rtl"] boolValue] ? ACCESSKIT_TEXT_DIRECTION_RIGHT_TO_LEFT:ACCESSKIT_TEXT_DIRECTION_LEFT_TO_RIGHT);
    if(i>0 && [runs[i-1][@"line"] isEqual:r[@"line"]])accesskit_node_set_previous_on_line(run,id-1);
    if(i+1<runs.count && [runs[i+1][@"line"] isEqual:r[@"line"]])accesskit_node_set_next_on_line(run,id+1);
    accesskit_tree_update_push_node(update,id,run);
   }
   if([f[@"rich"] boolValue])accesskit_node_set_text_selection(node,(accesskit_text_selection){position(f,[f[@"anchor"] unsignedIntegerValue]),position(f,[f[@"head"] unsignedIntegerValue])});
  }
  accesskit_tree_update_push_node(update,base,node);
 }
 accesskit_tree_update_push_node(update,0,root);return update;
 }
}
static void action(const struct accesskit_action_request *request,void *raw) {
 @autoreleasepool {
 SirinAccessKit *s=(__bridge SirinAccessKit*)raw;BOOL wake=NO;
 @synchronized(s) {
  NSDictionary *f=nil;for(NSDictionary *candidate in s.fields)if(control_id(candidate)==request->target_node){f=candidate;break;}
  if(s.alive && f && !(flags_for(f) & 4)) {
   int kind=0;NSInteger start=0,length=0;NSString *value=@"";
   switch(request->action) {
    case ACCESSKIT_ACTION_FOCUS:if(flags_for(f) & 1)kind=1;break;
    case ACCESSKIT_ACTION_CLICK:if(flags_for(f) & 2)kind=4;break;
    case ACCESSKIT_ACTION_INCREMENT:
     if(has_action(f, 5))kind = 5;
     break;
    case ACCESSKIT_ACTION_DECREMENT:
     if(has_action(f, 6))kind = 6;
     break;
    case ACCESSKIT_ACTION_CUSTOM_ACTION:
     if(request->data.has_value && request->data.value.tag == ACCESSKIT_ACTION_DATA_CUSTOM_ACTION) {
      int32_t identifier = request->data.value.custom_action;
      for(NSDictionary *entry in f[@"actions"]) {
       if(identifier <= 0 || [entry[@"customId"] intValue] != identifier)continue;
       int declared = [entry[@"kind"] intValue];
       if(declared == 7 || declared == 8) {
        kind = declared;
       value = entry[@"name"];
       }
       break;
      }
     }
     break;
    case ACCESSKIT_ACTION_SET_VALUE:
     if([f[@"kind"] intValue]<2 && request->data.has_value && request->data.value.tag==ACCESSKIT_ACTION_DATA_VALUE) {
      value=[NSString stringWithUTF8String:request->data.value.value];kind=value ? 3:0;
     }break;
    case ACCESSKIT_ACTION_SET_TEXT_SELECTION:
     if([f[@"kind"] intValue] < 2 && [f[@"rich"] boolValue] && request->data.has_value &&
        request->data.value.tag == ACCESSKIT_ACTION_DATA_SET_TEXT_SELECTION) {
      accesskit_text_selection selection=request->data.value.set_text_selection;NSUInteger a,b;
      if(offset_for(f,selection.anchor,&a) && offset_for(f,selection.focus,&b)){start=(NSInteger)a;length=(NSInteger)b-(NSInteger)a;kind=2;}
     }break;
    default:break;
   }
   if(kind && s.actions.count<64 && value.length<=16384 && [value lengthOfBytesUsingEncoding:NSUTF8StringEncoding]<=65536) {
    [s.actions addObject:@{@"id":f[@"id"],@"revision":f[@"revision"],@"kind":@(kind),@"start":@(start),@"length":@(length),@"value":value}];wake=YES;
   }else s.refused++;
  }
 }
 // Never call SDL or managed code while holding the snapshot lock. A failed
 // wake is reported; the owner also drains the queue on its normal poll loop.
 if(wake){SDL_Event event;SDL_zero(event);event.type=s.wakeType;event.user.windowID=s.windowId;if(!SDL_PushEvent(&event))@synchronized(s){s.refused++;}}
 }
}
static void action_owned(struct accesskit_action_request *request,void *raw) {action(request,raw);accesskit_action_request_free(request);}
static void install_focus_forwarder(Class windowClass) {
 NSCAssert(NSThread.isMainThread, @"AccessKit class setup requires main thread");
 // AccessKit adds a method to the class and aborts if it is already present.
 // The patch outlives individual adapters, including after the last window closes.
 static NSMutableSet<NSString *> *patchedClasses;
 if(!patchedClasses)patchedClasses = [NSMutableSet new];
 NSString *name = NSStringFromClass(windowClass);
 if(![patchedClasses containsObject:name]) {
  accesskit_macos_add_focus_forwarder_to_window_class(name.UTF8String);
  [patchedClasses addObject:name];
 }
}
void *sirin_ax_open(SDL_Window *window,uint32_t wake) {
 if(!NSThread.isMainThread)return NULL;
 @autoreleasepool {
 NSWindow *native=(__bridge NSWindow*)SDL_GetPointerProperty(SDL_GetWindowProperties(window),SDL_PROP_WINDOW_COCOA_WINDOW_POINTER,NULL);if(!native)return NULL;
 SirinAccessKit *s=[SirinAccessKit new];s.alive=YES;s.fields=@[];s.pending=[NSMutableArray new];s.actions=[NSMutableArray new];s.windowId=SDL_GetWindowID(window);s.wakeType=wake;s.window=native;s.scale=native.backingScaleFactor;
 // AccessKit's class forwarding code has process lifetime, like the FFM library.
 install_focus_forwarder(native.class);
 s.adapter=accesskit_macos_subclassing_adapter_for_window((__bridge void*)native,tree,(__bridge void*)s,action_owned,(__bridge void*)s);
 return (__bridge_retained void*)s;
 }
}
void sirin_ax_close(void *raw) {
 if(!raw)return;NSCAssert(NSThread.isMainThread,@"AccessKit close requires main thread");
 SirinAccessKit *s=(__bridge_transfer SirinAccessKit*)raw;
 @synchronized(s){s.alive=NO;}
 // Detaches the associated object and its callbacks before releasing userdata.
 accesskit_macos_subclassing_adapter_free(s.adapter);s.adapter=NULL;
 @synchronized(s){s.fields=@[];[s.pending removeAllObjects];[s.actions removeAllObjects];}
}
int sirin_ax_begin(void *raw,int64_t revision) {
 SirinAccessKit *s=(__bridge SirinAccessKit*)raw;if(!s || !NSThread.isMainThread)return 1;
 @synchronized(s){[s.pending removeAllObjects];s.revision=revision;}return 0;
}
int sirin_ax_field(void *raw,int64_t identifier,int kind,const char *label,const char *value,const char *help,int start,int length,int focused,int enabled,const double *bounds,const double *spans,int count) {
 SirinAccessKit *s=(__bridge SirinAccessKit*)raw;
 if(!s || !NSThread.isMainThread || identifier <= 0 || (uint64_t)identifier > UINT64_MAX >> 16 ||
    kind < 0 || kind > 2 || count < 0 || count > 65536 || (count && !spans) ||
    s.pending.count >= 1024 || !bounds || !label || !value || !help)return 1;
 NSUInteger legacyCount = 0;
 for(NSDictionary *field in s.pending)if(field[@"runs"])legacyCount++;
 if(legacyCount >= 64)return 1;
 NSString *v=[NSString stringWithUTF8String:value],*l=[NSString stringWithUTF8String:label],*h=[NSString stringWithUTF8String:help];
 if(!v || !l || !h || v.length>16384 || start<0 || (NSUInteger)start>v.length || (int64_t)start+length<0 || (uint64_t)((int64_t)start+length)>v.length)return 2;
 for(int i=0;i<4;i++)if(!isfinite(bounds[i]) || (i>=2 && bounds[i]<0))return 2;
 NSMutableDictionary *ranges=[NSMutableDictionary new],*carets=[NSMutableDictionary new];
 for(int i=0;i<count;i++) {
  const double *p=spans+i*7;
  for(int j=0;j<7;j++)if(!isfinite(p[j]))return 2;
  if(p[0]<0 || p[1]<p[0] || p[1]>v.length || p[0]!=floor(p[0]) || p[1]!=floor(p[1]) || p[4]<0 || p[5]<0)return 2;
  NSNumber *key=@((NSUInteger)p[0]);NSRect rect=NSMakeRect(p[2],p[3],p[4],p[5]);
  if(p[0]==p[1]){carets[key]=@[@(p[2]),@(p[6]),[NSValue valueWithRect:rect]];continue;}
  NSDictionary *prior=ranges[key];if(prior && [prior[@"end"] unsignedIntegerValue]!=(NSUInteger)p[1])return 2;
  if(prior)rect=NSUnionRect([prior[@"bounds"] rectValue],rect);
  ranges[key]=@{@"end":@((NSUInteger)p[1]),@"bounds":[NSValue valueWithRect:rect],@"line":@(p[6])};
 }
 NSMutableArray *runs=[NSMutableArray new];NSUInteger next=0;BOOL rich=YES;
 for(NSNumber *key in [ranges.allKeys sortedArrayUsingSelector:@selector(compare:)]) {
  NSDictionary *r=ranges[key];NSUInteger from=key.unsignedIntegerValue,end=[r[@"end"] unsignedIntegerValue];if(from!=next)return 2;
  NSRange range=NSMakeRange(from,end-from);NSString *part=[v substringWithRange:range];
  // AccessKit has a byte-sized character length. Preserve value/focus/editing
  // for oversized graphemes, but omit rich text/selection rather than split it.
  if(!part.UTF8String)return 2;
  if([part lengthOfBytesUsingEncoding:NSUTF8StringEncoding]>255)rich=NO;
  NSArray *a=carets[key],*b=carets[@(end)];BOOL rtl=a && b && [a[1] isEqual:b[1]] && [a[0] doubleValue]>[b[0] doubleValue];
  [runs addObject:@{@"range":[NSValue valueWithRange:range],@"value":part,@"bounds":r[@"bounds"],@"line":r[@"line"],@"rtl":@(rtl)}];next=end;
 }
 if(kind!=2 && (next!=v.length || ((NSUInteger)start!=v.length && !ranges[@(start)]) || ((NSUInteger)(start+length)!=v.length && !ranges[@(start+length)])))return 2;
 if(kind!=2 && !runs.count) {
  NSArray *caret=carets[@0];NSValue *rect=caret ? caret[2]:[NSValue valueWithRect:NSMakeRect(bounds[0],bounds[1],0,bounds[3])];
  [runs addObject:@{@"range":[NSValue valueWithRange:NSMakeRange(0,0)],@"value":@"",@"bounds":rect,@"line":@0,@"rtl":@NO}];
 }
 @synchronized(s) {
  for(NSDictionary *f in s.pending)if([f[@"id"] longLongValue]==identifier)return 2;
  [s.pending addObject:@{@"id":@(identifier),@"kind":@(kind),@"label":l,@"value":v,@"help":h,@"anchor":@(start),@"head":@(start+length),@"focused":@(focused),@"enabled":@(enabled),@"bounds":[NSValue valueWithRect:NSMakeRect(bounds[0],bounds[1],bounds[2],bounds[3])],@"runs":[runs copy],@"rich":@(rich),@"revision":@(s.revision)}];
 }return 0;
}
static BOOL valid_id(int64_t id) { return id > 0 && (uint64_t)id <= UINT64_MAX >> 16; }
static BOOL valid_flags(int flags) { return flags >= 0 && flags <= 2047 && (flags & 1536) != 1536; }
int sirin_ax_actions(void *raw, int64_t id, const int32_t *kinds, const int32_t *lengths,
    const char *names, int count, int bytes) {
 SirinAccessKit *s = (__bridge SirinAccessKit*)raw;
 if(!s || !NSThread.isMainThread || !valid_id(id))return 1;
 if(count < 0 || count > 32 || bytes < 0 || bytes > 8192 ||
    (count && (!kinds || !lengths)) || (bytes && !names))return 2;
 @synchronized(s) {
  for(NSUInteger index = 0; index < s.pending.count; index++) {
   NSDictionary *field = s.pending[index];
   if([field[@"id"] longLongValue] != id)continue;
   NSMutableArray *entries = [NSMutableArray new];
   int offset = 0;
   for(int item = 0; item < count; item++) {
    int kind = kinds[item];
    int length = lengths[item];
    if(kind < 5 || kind > 8 || length < 0 || length > 256 || length > bytes - offset ||
       (kind == 8 ? length == 0 : length != 0))return 2;
    if(length && memchr(names + offset, 0, (size_t)length))return 2;
    NSString *copied = length ? [[NSString alloc] initWithBytes:names + offset
      length:(NSUInteger)length encoding:NSUTF8StringEncoding] : @"";
    if(!copied)return 2;
    offset += length;
    for(NSDictionary *entry in entries)
     if([entry[@"kind"] intValue] == kind && [entry[@"name"] isEqual:copied])return 2;
    [entries addObject:[@{@"kind":@(kind), @"name":copied} mutableCopy]];
   }
   if(offset != bytes)return 2;
   // Assign IDs only after validating the entire copied batch.
   for(NSMutableDictionary *entry in entries) {
    int kind = [entry[@"kind"] intValue];
    int32_t customId = 0;
    if(kind == 7 || kind == 8) {
     NSMutableArray *candidates = [s.fields mutableCopy];
     [candidates addObject:field];
     for(NSDictionary *candidate in candidates) {
      if([candidate[@"id"] longLongValue] != id)continue;
      for(NSDictionary *prior in candidate[@"actions"])
       if([prior[@"kind"] isEqual:entry[@"kind"]] && [prior[@"name"] isEqual:entry[@"name"]])
        customId = [prior[@"customId"] intValue];
     }
     if(!customId) {
      if(s.customActionSerial == INT32_MAX)return 2;
      customId = ++s.customActionSerial;
     }
    }
    entry[@"customId"] = @(customId);
   }
   NSMutableDictionary *next = [field mutableCopy];
   next[@"actions"] = [entries copy];
   s.pending[index] = next;
   return 0;
  }
 }
 return 2;
}
int sirin_ax_node(void *raw, int64_t id, int64_t parent, int kind,
    const char *label, const char *value, const char *help, int flags, int checked, const double *bounds) {
 SirinAccessKit *s = (__bridge SirinAccessKit*)raw;
 if(!s || !NSThread.isMainThread || !valid_id(id) || parent < 0 || parent == id ||
    (parent && !valid_id(parent)) || kind < 2 || kind > 16 || !valid_flags(flags) ||
    checked < -1 || checked > 1 || s.pending.count >= 1024 || !bounds || !label || !value || !help)return 1;
 NSString *l = [NSString stringWithUTF8String:label];
 NSString *v = [NSString stringWithUTF8String:value];
 NSString *h = [NSString stringWithUTF8String:help];
 if(!l || !v || !h || l.length > 16384 || v.length > 16384 || h.length > 16384)return 2;
 for(int i = 0; i < 4; i++)if(!isfinite(bounds[i]) || (i >= 2 && bounds[i] < 0))return 2;
 @synchronized(s) {
  for(NSDictionary *f in s.pending)if([f[@"id"] longLongValue] == id)return 2;
  [s.pending addObject:@{@"id":@(id), @"parent":@(parent), @"kind":@(kind), @"label":l,
    @"value":v, @"help":h, @"flags":@(flags), @"checked":@(checked), @"revision":@(s.revision),
    @"bounds":[NSValue valueWithRect:NSMakeRect(bounds[0],bounds[1],bounds[2],bounds[3])]}];
 }
 return 0;
}
int sirin_ax_link(void *raw, int64_t id, int64_t parent, int flags) {
 SirinAccessKit *s = (__bridge SirinAccessKit*)raw;
 if(!s || !NSThread.isMainThread || !valid_id(id) || parent < 0 || parent == id ||
    (parent && !valid_id(parent)) || !valid_flags(flags))return 1;
 @synchronized(s) {
  for(NSUInteger i = 0; i < s.pending.count; i++) {
   NSDictionary *f = s.pending[i];
   if([f[@"id"] longLongValue] != id)continue;
   NSMutableDictionary *next = [f mutableCopy];
   next[@"parent"] = @(parent);
   next[@"flags"] = @(flags);
   s.pending[i] = next;
   return 0;
  }
 }
 return 2;
}
int sirin_ax_relationships(void *raw, int64_t id, const int64_t *facts) {
 SirinAccessKit *s = (__bridge SirinAccessKit*)raw;
 if(!s || !NSThread.isMainThread || !valid_id(id) || !facts)return 1;
 for(int index = 0; index < 3; index++)
  if(facts[index] < 0 || (facts[index] && !valid_id(facts[index])) || facts[index] == id)return 2;
 if(facts[3] < 0 || facts[4] < 0 || facts[3] > facts[4] || facts[4] > INT32_MAX ||
    ((facts[3] == 0) != (facts[4] == 0)))return 2;
 @synchronized(s) {
  for(NSUInteger index = 0; index < s.pending.count; index++) {
   NSDictionary *field = s.pending[index];
   if([field[@"id"] longLongValue] != id)continue;
   NSMutableDictionary *next = [field mutableCopy];
   next[@"labelledBy"] = @(facts[0]);
   next[@"describedBy"] = @(facts[1]);
   next[@"activeDescendant"] = @(facts[2]);
   next[@"position"] = @(facts[3]);
   next[@"setSize"] = @(facts[4]);
   s.pending[index] = next;
   return 0;
  }
 }
 return 2;
}
static BOOL valid_tree(NSArray *fields) {
 NSMutableDictionary *parents = [NSMutableDictionary new];
 for(NSDictionary *f in fields)parents[f[@"id"]] = f[@"parent"] ?: @0;
 for(NSDictionary *f in fields) {
  NSNumber *at = f[@"id"];
  NSUInteger depth = 0;
  while(at.longLongValue != 0) {
   if(!parents[at] || ++depth > fields.count)return NO;
   at = parents[at];
  }
  for(NSString *key in @[@"labelledBy", @"describedBy", @"activeDescendant"]) {
   NSNumber *target = f[key];
   if(target.longLongValue && (!parents[target] || [target isEqual:f[@"id"]]))return NO;
  }
  NSNumber *target = f[@"activeDescendant"];
  if(target.longLongValue) {
   NSNumber *at = parents[target];
   NSUInteger depth = 0;
   while(at.longLongValue && ![at isEqual:f[@"id"]]) {
    if(++depth > fields.count)return NO;
    at = parents[at];
   }
   if(![at isEqual:f[@"id"]])return NO;
  }
 }
 return YES;
}
int sirin_ax_publish(void *raw) {
 SirinAccessKit *s=(__bridge SirinAccessKit*)raw;if(!s || !NSThread.isMainThread)return 1;
 @synchronized(s) {
  if(!valid_tree(s.pending))return 2;
  s.fields = [s.pending copy];
  s.scale = s.window.backingScaleFactor;
 }
 struct accesskit_macos_queued_events *events=accesskit_macos_subclassing_adapter_update_if_active(s.adapter,tree,raw);
 if(events)accesskit_macos_queued_events_raise(events);
 events=accesskit_macos_subclassing_adapter_update_view_focus_state(s.adapter,s.window.isKeyWindow);
 if(events)accesskit_macos_queued_events_raise(events);return 0;
}
int sirin_ax_poll(void *raw,int64_t *facts,char *text,int capacity) {
 SirinAccessKit *s=(__bridge SirinAccessKit*)raw;if(!s || !facts || !text || capacity<65537)return -1;
 @synchronized(s){if(s.refused){s.refused=0;return -2;}if(!s.actions.count)return 0;NSDictionary *a=s.actions.firstObject;[s.actions removeObjectAtIndex:0];facts[0]=[a[@"revision"] longLongValue];facts[1]=[a[@"id"] longLongValue];facts[2]=[a[@"kind"] longLongValue];facts[3]=[a[@"start"] longLongValue];facts[4]=[a[@"length"] longLongValue];const char *v=[a[@"value"] UTF8String];if(!v || strlen(v)>=65537)return -2;strcpy(text,v);return 1;}
}
// Test control exercises the platform's real NSTextInputClient, not forged SDL facts.
int sirin_editor_ime_control(void *nativeWindow,const char *text,int start,int length,int commit) {
 NSWindow *w=(__bridge NSWindow*)nativeWindow;id client=w.firstResponder;
 fprintf(stderr,"nativeIME client=%s keyWindow=%d\n",NSStringFromClass([client class]).UTF8String,w.isKeyWindow);
 if(![client conformsToProtocol:@protocol(NSTextInputClient)])return 1;
 NSString *s=[NSString stringWithUTF8String:text];if(!s || start<0 || length<0 || (NSUInteger)start+(NSUInteger)length>s.length)return 2;
 if(commit)[(id<NSTextInputClient>)client insertText:s replacementRange:NSMakeRange(NSNotFound,0)];
 else [(id<NSTextInputClient>)client setMarkedText:s selectedRange:NSMakeRange(start,length) replacementRange:NSMakeRange(NSNotFound,0)];return 0;
}

int sirin_editor_activate(void *raw){
 if(!NSThread.isMainThread)return 1;
 NSWindow *window=(__bridge NSWindow*)raw;
 [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
 if(@available(macOS 14.0,*)){[NSApp activate];}
 [window makeKeyAndOrderFront:nil];return 0;
}
