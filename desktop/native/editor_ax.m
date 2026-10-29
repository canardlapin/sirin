#import <Cocoa/Cocoa.h>
#include <SDL3/SDL.h>
#include <stdint.h>
#include <math.h>

@class SirinAXRoot;
@interface SirinAXNode : NSAccessibilityElement
@property(nonatomic,weak) SirinAXRoot *root;
@property(nonatomic,strong) NSDictionary *state;
@end
@interface SirinAXRoot : NSView
@property(nonatomic,strong) NSArray<SirinAXNode*> *nodes;
@property(nonatomic,strong) NSMutableArray *pending;
@property(nonatomic,strong) NSMutableDictionary *cache;
@property(nonatomic,strong) NSMutableArray *actions;
@property(nonatomic) uint32_t windowId;
@property(nonatomic) uint32_t wakeType;
@property(nonatomic) int64_t revision;
@property(nonatomic) BOOL alive;
@property(nonatomic) int refused;
- (BOOL)enqueue:(SirinAXNode*)node kind:(int)kind range:(NSRange)range value:(NSString*)value;
@end
@implementation SirinAXRoot
- (NSView*)hitTest:(NSPoint)point {(void)point;return nil;}
- (BOOL)isAccessibilityElement {return NO;}
- (NSArray*)accessibilityChildren {@synchronized(self){return self.alive ? self.nodes : @[];}}
- (id)accessibilityFocusedUIElement {@synchronized(self){for(SirinAXNode *n in self.nodes)if([n.state[@"focused"] boolValue])return n;return nil;}}
- (id)accessibilityHitTest:(NSPoint)point {@synchronized(self){for(SirinAXNode *n in [self.nodes reverseObjectEnumerator])if(NSPointInRect(point,[n accessibilityFrame]))return n;return self;}}
- (BOOL)enqueue:(SirinAXNode*)node kind:(int)kind range:(NSRange)range value:(NSString*)value {
 @synchronized(self){
  if(!self.alive || ![self.nodes containsObject:node] || ![node.state[@"enabled"] boolValue])return NO;
  if(self.actions.count>=64 || value.length>16384 || ![value UTF8String]){self.refused++;return NO;}
  [self.actions addObject:@{@"id":node.state[@"id"],@"revision":node.state[@"revision"],@"kind":@(kind),@"start":@(range.location),@"length":@(range.length),@"value":value}];
  SDL_Event event;SDL_zero(event);event.type=self.wakeType;event.user.windowID=self.windowId;
  if(!SDL_PushEvent(&event)){[self.actions removeLastObject];self.refused++;return NO;}return YES;
 }
}
@end
@implementation SirinAXNode
- (BOOL)isAccessibilityElement {return YES;}
- (NSString*)accessibilityRole {@synchronized(self.root){int k=[self.state[@"kind"] intValue];return k==2 ? NSAccessibilityButtonRole : k==1 ? NSAccessibilityTextAreaRole : NSAccessibilityTextFieldRole;}}
- (NSString*)accessibilityLabel {@synchronized(self.root){return self.state[@"label"];}}
- (NSString*)accessibilityIdentifier {@synchronized(self.root){return [NSString stringWithFormat:@"sirin.%@",self.state[@"id"]];}}
- (id)accessibilityValue {@synchronized(self.root){return self.state[@"value"];}}
- (NSString*)accessibilityHelp {@synchronized(self.root){return self.state[@"help"];}}
- (BOOL)isAccessibilityEnabled {@synchronized(self.root){return self.root.alive && [self.state[@"enabled"] boolValue];}}
- (BOOL)isAccessibilityFocused {@synchronized(self.root){return self.root.alive && [self.state[@"focused"] boolValue];}}
- (id)accessibilityParent {return self.root;}
- (id)accessibilityWindow {return self.root.window;}
- (NSRect)screen:(NSRect)rect {
 SirinAXRoot *r=self.root;if(!r.window)return NSZeroRect;
 NSRect local=NSMakeRect(rect.origin.x,NSHeight(r.superview.bounds)-rect.origin.y-rect.size.height,rect.size.width,rect.size.height);
 return [r.window convertRectToScreen:[r.superview convertRect:local toView:nil]];
}
- (NSRect)accessibilityFrame {@synchronized(self.root){return [self screen:[self.state[@"bounds"] rectValue]];}}
- (NSRange)accessibilitySelectedTextRange {@synchronized(self.root){return [self.state[@"selection"] rangeValue];}}
- (NSArray*)accessibilitySelectedTextRanges {return @[[NSValue valueWithRange:[self accessibilitySelectedTextRange]]];}
- (NSString*)accessibilitySelectedText {@synchronized(self.root){NSString *v=self.state[@"value"];NSRange r=[self accessibilitySelectedTextRange];return NSMaxRange(r)<=v.length ? [v substringWithRange:r] : @"";}}
- (NSInteger)accessibilityNumberOfCharacters {@synchronized(self.root){return [self.state[@"value"] length];}}
- (NSRange)accessibilityVisibleCharacterRange {@synchronized(self.root){const double *p=[self.state[@"spans"] bytes];NSUInteger count=[self.state[@"spans"] length]/(7*sizeof(double));NSUInteger start=NSNotFound,end=0;for(NSUInteger i=0;i<count;i++,p+=7)if(p[4]>0 && p[5]>0){start=MIN(start,(NSUInteger)p[0]);end=MAX(end,(NSUInteger)p[1]);}return start==NSNotFound ? NSMakeRange(0,0) : NSMakeRange(start,end-start);}}
- (NSString*)accessibilityStringForRange:(NSRange)range {@synchronized(self.root){NSString *v=self.state[@"value"];return NSMaxRange(range)<=v.length ? [v substringWithRange:range] : nil;}}
- (NSAttributedString*)accessibilityAttributedStringForRange:(NSRange)range {NSString *s=[self accessibilityStringForRange:range];return s ? [[NSAttributedString alloc]initWithString:s] : nil;}
- (NSRect)accessibilityFrameForRange:(NSRange)range {
 @synchronized(self.root){const double *p=[self.state[@"spans"] bytes];NSUInteger count=[self.state[@"spans"] length]/(7*sizeof(double));BOOL found=NO;NSRect rect=NSZeroRect;
 for(NSUInteger i=0;i<count;i++,p+=7){BOOL include=range.length ? p[1]>range.location && p[0]<NSMaxRange(range) : p[0]==range.location && p[0]==p[1];
 if(include && p[4]>0 && p[5]>0){NSRect cell=NSMakeRect(p[2],p[3],fmax(1,p[4]),p[5]);rect=found ? NSUnionRect(rect,cell) : cell;found=YES;}}
 return found ? [self screen:rect] : NSZeroRect;}
}
- (NSRange)accessibilityRangeForPosition:(NSPoint)point {
 @synchronized(self.root){const double *p=[self.state[@"spans"] bytes];NSUInteger count=[self.state[@"spans"] length]/(7*sizeof(double));double distance=HUGE_VAL;NSUInteger offset=0;
 for(NSUInteger i=0;i<count;i++,p+=7)if(p[0]==p[1] && p[4]>0 && p[5]>0){NSRect r=[self screen:NSMakeRect(p[2],p[3],1,p[5])];double dx=point.x-r.origin.x,dy=point.y-fmax(NSMinY(r),fmin(NSMaxY(r),point.y));double d=dx*dx+dy*dy;if(d<distance){distance=d;offset=(NSUInteger)p[0];}}
 return NSMakeRange(offset,0);}
}
- (NSInteger)accessibilityLineForIndex:(NSInteger)index {@synchronized(self.root){const double *p=[self.state[@"spans"] bytes];NSUInteger count=[self.state[@"spans"] length]/(7*sizeof(double));for(NSUInteger i=0;i<count;i++,p+=7)if(p[0]<=index && index<=p[1])return (NSInteger)p[6];return 0;}}
- (NSRange)accessibilityRangeForLine:(NSInteger)line {@synchronized(self.root){const double *p=[self.state[@"spans"] bytes];NSUInteger count=[self.state[@"spans"] length]/(7*sizeof(double));NSUInteger start=NSNotFound,end=0;for(NSUInteger i=0;i<count;i++,p+=7)if(p[6]==line){start=MIN(start,(NSUInteger)p[0]);end=MAX(end,(NSUInteger)p[1]);}return start==NSNotFound ? NSMakeRange(NSNotFound,0) : NSMakeRange(start,end-start);}}
- (void)setAccessibilityFocused:(BOOL)value {if(value)[self.root enqueue:self kind:1 range:NSMakeRange(0,0) value:@""];}
- (void)setAccessibilitySelectedTextRange:(NSRange)value {if(NSMaxRange(value)<=(NSUInteger)[self accessibilityNumberOfCharacters])[self.root enqueue:self kind:2 range:value value:@""];}
- (void)setAccessibilityValue:(id)value {if([value isKindOfClass:NSString.class])[self.root enqueue:self kind:3 range:NSMakeRange(0,0) value:value];}
- (BOOL)accessibilityPerformPress {return [self.root enqueue:self kind:4 range:NSMakeRange(0,0) value:@""];}
@end

void *sirin_ax_open(SDL_Window *window,uint32_t wake) {
 @autoreleasepool {
 NSWindow *native=(__bridge NSWindow*)SDL_GetPointerProperty(SDL_GetWindowProperties(window),SDL_PROP_WINDOW_COCOA_WINDOW_POINTER,NULL);if(!native)return NULL;
 SirinAXRoot *root=[[SirinAXRoot alloc]initWithFrame:native.contentView.bounds];root.autoresizingMask=NSViewWidthSizable|NSViewHeightSizable;
 root.alive=YES;root.nodes=@[];root.pending=[NSMutableArray new];root.cache=[NSMutableDictionary new];root.actions=[NSMutableArray new];root.windowId=SDL_GetWindowID(window);root.wakeType=wake;
 [native.contentView addSubview:root];[native.contentView setAccessibilityElement:NO];[native.contentView setAccessibilityChildren:@[root]];
 return (__bridge_retained void*)root;
 }
}
void sirin_ax_close(void *raw) {if(!raw)return;SirinAXRoot *root=(__bridge_transfer SirinAXRoot*)raw;@synchronized(root){root.alive=NO;root.nodes=@[];[root.cache removeAllObjects];[root.actions removeAllObjects];[root.superview setAccessibilityChildren:nil];[root removeFromSuperview];}}
int sirin_ax_begin(void *raw,int64_t revision) {SirinAXRoot *r=(__bridge SirinAXRoot*)raw;if(!r || !NSThread.isMainThread)return 1;@synchronized(r){[r.pending removeAllObjects];r.revision=revision;}return 0;}
int sirin_ax_field(void *raw,int64_t identifier,int kind,const char *label,const char *value,const char *help,int start,int length,int focused,int enabled,const double *bounds,const double *spans,int count) {
 SirinAXRoot *r=(__bridge SirinAXRoot*)raw;if(!r || !NSThread.isMainThread || count<0 || count>65536 || r.pending.count>=64 || !bounds || !label || !value || !help)return 1;
 NSString *v=[NSString stringWithUTF8String:value],*l=[NSString stringWithUTF8String:label],*h=[NSString stringWithUTF8String:help];if(!v || !l || !h || v.length>16384 || start<0 || length<0 || (NSUInteger)start+(NSUInteger)length>v.length)return 2;
 for(int i=0;i<4;i++)if(!isfinite(bounds[i]))return 2;
 @synchronized(r){[r.pending addObject:@{@"id":@(identifier),@"kind":@(kind),@"label":l,@"value":v,@"help":h,@"selection":[NSValue valueWithRange:NSMakeRange(start,length)],@"focused":@(focused),@"enabled":@(enabled),@"bounds":[NSValue valueWithRect:NSMakeRect(bounds[0],bounds[1],bounds[2],bounds[3])],@"spans":[NSData dataWithBytes:spans length:count*7*sizeof(double)],@"revision":@(r.revision)}];}return 0;
}
int sirin_ax_publish(void *raw) {
 SirinAXRoot *r=(__bridge SirinAXRoot*)raw;if(!r || !NSThread.isMainThread)return 1;
 NSMutableArray *changed=[NSMutableArray new];BOOL focusChanged=NO;
 @synchronized(r){NSMutableArray *nodes=[NSMutableArray new];NSMutableDictionary *cache=[NSMutableDictionary new];
 for(NSDictionary *state in r.pending){NSNumber *key=state[@"id"];SirinAXNode *node=r.cache[key];if(!node){node=[SirinAXNode new];node.root=r;}
 if(![node.state[@"value"] isEqual:state[@"value"]] || ![node.state[@"selection"] isEqual:state[@"selection"]])[changed addObject:node];
 if([node.state[@"focused"] boolValue]!=[state[@"focused"] boolValue])focusChanged=YES;
 node.state=state;cache[key]=node;[nodes addObject:node];}r.cache=cache;r.nodes=nodes;}
 for(SirinAXNode *node in changed){NSAccessibilityPostNotification(node,NSAccessibilityValueChangedNotification);NSAccessibilityPostNotification(node,NSAccessibilitySelectedTextChangedNotification);}
 if(focusChanged){id node=[r accessibilityFocusedUIElement];if(node)NSAccessibilityPostNotification(node,NSAccessibilityFocusedUIElementChangedNotification);}return 0;
}
int sirin_ax_poll(void *raw,int64_t *facts,char *text,int capacity) {
 SirinAXRoot *r=(__bridge SirinAXRoot*)raw;if(!r || !facts || !text || capacity<65537)return -1;
 @synchronized(r){if(r.refused){r.refused=0;return -2;}if(!r.actions.count)return 0;NSDictionary *a=r.actions.firstObject;[r.actions removeObjectAtIndex:0];facts[0]=[a[@"revision"] longLongValue];facts[1]=[a[@"id"] longLongValue];facts[2]=[a[@"kind"] longLongValue];facts[3]=[a[@"start"] longLongValue];facts[4]=[a[@"length"] longLongValue];const char *v=[a[@"value"] UTF8String];if(!v || strlen(v)>=65537)return -2;strcpy(text,v);return 1;}
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
