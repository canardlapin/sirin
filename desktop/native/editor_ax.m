#import <Cocoa/Cocoa.h>
#include <SDL3/SDL.h>
#include "accesskit.h"
#include <stdint.h>
#include <math.h>

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
static struct accesskit_tree_update *tree(void *raw) {
 @autoreleasepool {
 SirinAccessKit *s=(__bridge SirinAccessKit*)raw;NSArray *fields;double scale;
 @synchronized(s){fields=s.alive ? s.fields:@[];scale=s.scale>0 ? s.scale:1;}
 accesskit_node_id focus=0;for(NSDictionary *f in fields)if([f[@"focused"] boolValue])focus=control_id(f);
 struct accesskit_tree_update *update=accesskit_tree_update_with_focus(focus);
 struct accesskit_tree_info *info=accesskit_tree_info_new(0);
 accesskit_tree_info_set_toolkit_name(info,"Sirin");accesskit_tree_update_set_tree_info(update,info);
 struct accesskit_node *root=accesskit_node_new(ACCESSKIT_ROLE_WINDOW);
 accesskit_node_set_label(root,"Sirin editable workbench");
 for(NSDictionary *f in fields) {
  accesskit_node_id base=control_id(f);int kind=[f[@"kind"] intValue];
  struct accesskit_node *node=accesskit_node_new(kind==2 ? ACCESSKIT_ROLE_BUTTON:kind==1 ? ACCESSKIT_ROLE_MULTILINE_TEXT_INPUT:ACCESSKIT_ROLE_TEXT_INPUT);
  accesskit_node_push_child(root,base);
  accesskit_node_set_label(node,[f[@"label"] UTF8String]);
  accesskit_node_set_description(node,[f[@"help"] UTF8String]);
  accesskit_node_set_bounds(node,ak_rect([f[@"bounds"] rectValue],scale));
  if(![f[@"enabled"] boolValue])accesskit_node_set_disabled(node);
  accesskit_node_add_action(node,ACCESSKIT_ACTION_FOCUS);
  if(kind==2)accesskit_node_add_action(node,ACCESSKIT_ACTION_CLICK);
  else {
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
  if(s.alive && f && [f[@"enabled"] boolValue]) {
   int kind=0;NSInteger start=0,length=0;NSString *value=@"";
   switch(request->action) {
    case ACCESSKIT_ACTION_FOCUS:kind=1;break;
    case ACCESSKIT_ACTION_CLICK:if([f[@"kind"] intValue]==2)kind=4;break;
    case ACCESSKIT_ACTION_SET_VALUE:
     if([f[@"kind"] intValue]!=2 && request->data.has_value && request->data.value.tag==ACCESSKIT_ACTION_DATA_VALUE) {
      value=[NSString stringWithUTF8String:request->data.value.value];kind=value ? 3:0;
     }break;
    case ACCESSKIT_ACTION_SET_TEXT_SELECTION:
     if([f[@"kind"] intValue]!=2 && [f[@"rich"] boolValue] && request->data.has_value && request->data.value.tag==ACCESSKIT_ACTION_DATA_SET_TEXT_SELECTION) {
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
void *sirin_ax_open(SDL_Window *window,uint32_t wake) {
 if(!NSThread.isMainThread)return NULL;
 @autoreleasepool {
 NSWindow *native=(__bridge NSWindow*)SDL_GetPointerProperty(SDL_GetWindowProperties(window),SDL_PROP_WINDOW_COCOA_WINDOW_POINTER,NULL);if(!native)return NULL;
 SirinAccessKit *s=[SirinAccessKit new];s.alive=YES;s.fields=@[];s.pending=[NSMutableArray new];s.actions=[NSMutableArray new];s.windowId=SDL_GetWindowID(window);s.wakeType=wake;s.window=native;s.scale=native.backingScaleFactor;
 // AccessKit's class forwarding code has process lifetime, like the FFM library.
 accesskit_macos_add_focus_forwarder_to_window_class(NSStringFromClass(native.class).UTF8String);
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
 if(!s || !NSThread.isMainThread || identifier<=0 || (uint64_t)identifier>UINT64_MAX>>16 || kind<0 || kind>2 || count<0 || count>65536 || (count && !spans) || s.pending.count>=64 || !bounds || !label || !value || !help)return 1;
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
int sirin_ax_publish(void *raw) {
 SirinAccessKit *s=(__bridge SirinAccessKit*)raw;if(!s || !NSThread.isMainThread)return 1;
 @synchronized(s){s.fields=[s.pending copy];s.scale=s.window.backingScaleFactor;}
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
