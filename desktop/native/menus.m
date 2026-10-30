#import <Cocoa/Cocoa.h>
#include <SDL3/SDL.h>
#include "menus.h"

@class SirinMenus;
@interface SirinCommandMenu : NSMenu
@property(nonatomic,weak) SirinMenus *bridge;
@end
@interface SirinMenus : NSObject
@property(nonatomic,strong) NSMenu *original;
@property(nonatomic,strong) NSMenu *published;
@property(nonatomic,strong) SirinCommandMenu *pending;
@property(nonatomic,strong) NSMutableDictionary *groups;
@property(nonatomic,strong) NSMutableSet *pendingKeys,*pendingRouted,*nodes;
@property(nonatomic,strong) NSSet *keys;
@property(nonatomic,strong) NSMutableArray *actions;
@property(nonatomic) int64_t revision,pendingRevision;
@property(nonatomic) int serial,refused;
@property(nonatomic) uint32_t wakeType;
@property(nonatomic) BOOL alive;
@end
static int key_mods(NSEventModifierFlags flags) {
 return ((flags&NSEventModifierFlagCommand)?1:0)|((flags&NSEventModifierFlagShift)?2:0)|((flags&NSEventModifierFlagOption)?4:0)|((flags&NSEventModifierFlagControl)?8:0);
}
// NSMenuItem key equivalents are deliberately different from NSEvent deletion characters.
int sirin_menus_event_key(int code) {
 switch(code) {
  case NSDeleteCharacter:return NSBackspaceCharacter;
  case NSDeleteFunctionKey:return NSDeleteCharacter;
  case NSBackTabCharacter:return NSTabCharacter;
  case NSEnterCharacter:return NSCarriageReturnCharacter;
  default:return code;
 }
}
static NSString *key_id(NSString *key,int mods) {return [NSString stringWithFormat:@"%d:%@",mods,key.lowercaseString];}
@implementation SirinCommandMenu
- (BOOL)performKeyEquivalent:(NSEvent*)event {
 SirinMenus *s=self.bridge;
 // Display native equivalents, but let the UI owner enforce IME/editor/local precedence.
 NSString *key=[event charactersByApplyingModifiers:0] ?: event.charactersIgnoringModifiers ?: @"";
 if(key.length==1){unichar normalized=(unichar)sirin_menus_event_key([key characterAtIndex:0]);key=[NSString stringWithCharacters:&normalized length:1];}
 if(s && [s.keys containsObject:key_id(key,key_mods(event.modifierFlags))])return NO;
 return [super performKeyEquivalent:event];
}
@end
@implementation SirinMenus
- (void)wake {
 SDL_Event event;SDL_zero(event);event.type=self.wakeType;event.user.code=self.serial;
 if(!SDL_PushEvent(&event))self.refused++;
}
- (void)activate:(NSMenuItem*)item {
 @synchronized(self) {
  if(!self.alive)return;
  NSDictionary *record=item.representedObject;
  // The item owns its original revision; never relabel an old click with a new one.
  if(!item.enabled || [record[@"revision"] longLongValue]!=self.revision)return;
  if(self.actions.count>=64){self.refused++;[self wake];return;}
  [self.actions addObject:record];[self wake];
 }
}
- (void)quit:(id)sender {
 (void)sender;if(!self.alive)return;
 SDL_Event event;SDL_zero(event);event.type=SDL_EVENT_QUIT;
 if(!SDL_PushEvent(&event))self.refused++;
}
@end
static NSString *app_name(void) {
 NSDictionary *info=NSBundle.mainBundle.infoDictionary;
 return info[@"CFBundleDisplayName"] ?: info[@"CFBundleName"] ?: NSProcessInfo.processInfo.processName;
}
static void standard_menu(SirinMenus *s) {
 NSString *name=app_name();NSMenu *app=[[NSMenu alloc] initWithTitle:name];app.autoenablesItems=NO;
 NSMenuItem *about=[[NSMenuItem alloc] initWithTitle:[@"About " stringByAppendingString:name] action:@selector(orderFrontStandardAboutPanel:) keyEquivalent:@""];about.target=NSApp;[app addItem:about];
 [app addItem:NSMenuItem.separatorItem];
 NSMenuItem *hide=[[NSMenuItem alloc] initWithTitle:[@"Hide " stringByAppendingString:name] action:@selector(hide:) keyEquivalent:[s.pendingKeys containsObject:@"1:h"]?@"":@"h"];hide.target=NSApp;[app addItem:hide];
 NSMenuItem *others=[[NSMenuItem alloc] initWithTitle:@"Hide Others" action:@selector(hideOtherApplications:) keyEquivalent:[s.pendingKeys containsObject:@"5:h"]?@"":@"h"];others.target=NSApp;others.keyEquivalentModifierMask=NSEventModifierFlagCommand|NSEventModifierFlagOption;[app addItem:others];
 NSMenuItem *show=[[NSMenuItem alloc] initWithTitle:@"Show All" action:@selector(unhideAllApplications:) keyEquivalent:@""];show.target=NSApp;[app addItem:show];
 [app addItem:NSMenuItem.separatorItem];
 NSMenuItem *quit=[[NSMenuItem alloc] initWithTitle:[@"Quit " stringByAppendingString:name] action:@selector(quit:) keyEquivalent:[s.pendingKeys containsObject:@"1:q"]?@"":@"q"];quit.target=s;[app addItem:quit];
 NSMenuItem *root=[[NSMenuItem alloc] initWithTitle:name action:nil keyEquivalent:@""];root.submenu=app;
 [s.pending insertItem:root atIndex:0];
}
void *sirin_menus_open(int serial,uint32_t wake_type) {
 if(!NSThread.isMainThread || !NSApp)return NULL;
 SirinMenus *s=[SirinMenus new];s.original=NSApp.mainMenu;s.serial=serial;s.wakeType=wake_type;
 s.alive=YES;s.keys=[NSSet set];s.actions=[NSMutableArray new];return (__bridge_retained void*)s;
}
void sirin_menus_close(void *raw) {
 if(!raw)return;NSCAssert(NSThread.isMainThread,@"menu close requires main thread");
 SirinMenus *s=(__bridge_transfer SirinMenus*)raw;
 @synchronized(s){s.alive=NO;[s.actions removeAllObjects];}
 if(NSApp.mainMenu==s.published)NSApp.mainMenu=s.original;
 s.pending=nil;s.published=nil;s.groups=nil;s.pendingKeys=nil;s.pendingRouted=nil;s.nodes=nil;s.keys=nil;
}
int sirin_menus_begin(void *raw,int64_t revision) {
 SirinMenus *s=(__bridge SirinMenus*)raw;
 if(!s || !NSThread.isMainThread || revision<=s.revision)return 1;
 s.pending=[[SirinCommandMenu alloc] initWithTitle:@""];s.pending.bridge=s;s.pending.autoenablesItems=NO;
 s.groups=[NSMutableDictionary dictionaryWithObject:s.pending forKey:@0];s.pendingKeys=[NSMutableSet new];s.pendingRouted=[NSMutableSet new];s.nodes=[NSMutableSet new];s.pendingRevision=revision;return 0;
}
int sirin_menus_route(void *raw,const char *key,int mods) {
 SirinMenus *s=(__bridge SirinMenus*)raw;
 if(!s || !NSThread.isMainThread || !s.pending || !key || mods<0 || mods>15)return 1;
 NSString *value=[NSString stringWithUTF8String:key];if(!value)return 2;
 if(value.length){[s.pendingKeys addObject:key_id(value,mods)];[s.pendingRouted addObject:key_id(value,mods)];}return 0;
}
int sirin_menus_add(void *raw,int parent,int node,int kind,int action,const char *label,const char *key,int mods,int enabled,int busy) {
 SirinMenus *s=(__bridge SirinMenus*)raw;
 if(!s || !NSThread.isMainThread || !s.pending || node<=0 || node>512 || kind<0 || kind>2 || mods<0 || mods>15)return 1;
 NSMenu *menu=s.groups[@(parent)];if(!menu || [s.nodes containsObject:@(node)] || s.nodes.count>=512)return 2;
 [s.nodes addObject:@(node)];
 NSString *title=label?[NSString stringWithUTF8String:label]:nil;NSString *equivalent=key?[NSString stringWithUTF8String:key]:nil;
 if(!title || !equivalent)return 2;
 if(kind==2){[menu addItem:NSMenuItem.separatorItem];return 0;}
 NSMenuItem *item=[[NSMenuItem alloc] initWithTitle:title action:kind==1?@selector(activate:):nil keyEquivalent:kind==1?equivalent:@""];
 item.enabled=enabled!=0;item.target=kind==1?s:nil;
 if(kind==0) {
  if(s.groups[@(node)])return 2;
  NSMenu *child=[[NSMenu alloc] initWithTitle:title];child.autoenablesItems=NO;item.submenu=child;s.groups[@(node)]=child;
 } else {
  if(action<=0)return 2;
  item.keyEquivalentModifierMask=((mods&1)?NSEventModifierFlagCommand:0)|((mods&2)?NSEventModifierFlagShift:0)|((mods&4)?NSEventModifierFlagOption:0)|((mods&8)?NSEventModifierFlagControl:0);
  item.representedObject=@{@"revision":@(s.pendingRevision),@"id":@(action)};
  if(busy)item.toolTip=@"Operation running";
  if(equivalent.length)[s.pendingKeys addObject:key_id(equivalent,mods)];
 }
 [menu addItem:item];return 0;
}
int sirin_menus_publish(void *raw) {
 SirinMenus *s=(__bridge SirinMenus*)raw;if(!s || !NSThread.isMainThread || !s.pending)return 1;
 standard_menu(s);
 @synchronized(s){s.keys=[s.pendingRouted copy];s.revision=s.pendingRevision;}
 NSApp.mainMenu=s.pending;s.published=s.pending;s.pending=nil;s.groups=nil;s.pendingKeys=nil;s.pendingRouted=nil;s.nodes=nil;return 0;
}
int sirin_menus_poll(void *raw,int64_t *out) {
 SirinMenus *s=(__bridge SirinMenus*)raw;if(!s || !NSThread.isMainThread || !out)return -1;
 @synchronized(s) {
  if(s.refused){s.refused=0;return -2;}
  if(!s.actions.count)return 0;
  NSDictionary *record=s.actions.firstObject;[s.actions removeObjectAtIndex:0];out[0]=[record[@"revision"] longLongValue];out[1]=[record[@"id"] longLongValue];return 1;
 }
}
int sirin_menus_standard(void *raw,int kind) {
 SirinMenus *s=(__bridge SirinMenus*)raw;if(!s || !NSThread.isMainThread || !s.alive)return 1;
 if(kind==1)[s quit:nil];else if(kind==2)[NSApp hide:nil];else if(kind==3)[NSApp hideOtherApplications:nil];else return 2;return 0;
}
