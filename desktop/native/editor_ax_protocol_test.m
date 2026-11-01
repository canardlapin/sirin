// Native-only protocol checks: no NSApplication, window, adapter or Scala upcall.
#import "editor_ax.m"
#include <assert.h>
#include <pthread.h>

@interface SirinFocusTestWindow : NSWindow
@end
@implementation SirinFocusTestWindow
@end
@interface SirinOtherFocusTestWindow : NSWindow
@end
@implementation SirinOtherFocusTestWindow
@end

static void repeated_window_class_setup(void) {
 // Exercise the real AccessKit class patch without opening an application window.
 install_focus_forwarder(SirinFocusTestWindow.class);
 install_focus_forwarder(SirinOtherFocusTestWindow.class);
 for(int i = 0; i < 3; i++) {
  install_focus_forwarder(SirinFocusTestWindow.class);
  install_focus_forwarder(SirinOtherFocusTestWindow.class);
 }
 assert([SirinFocusTestWindow instancesRespondToSelector:@selector(accessibilityFocusedUIElement)]);
 assert([SirinOtherFocusTestWindow instancesRespondToSelector:@selector(accessibilityFocusedUIElement)]);
 puts("AccessKit native lifecycle: repeated focus setup for two window classes passed");
}

static void *foreign_focus(void *raw) {
 accesskit_action_request request={0};request.target_node=7ULL<<16;request.action=ACCESSKIT_ACTION_FOCUS;
 action(&request,raw);return NULL;
}
static void semantic_tree(void) {
 SirinAccessKit *s = [SirinAccessKit new];
 s.alive = YES;
 s.fields = @[];
 s.pending = [NSMutableArray new];
 s.actions = [NSMutableArray new];
 s.wakeType = SDL_RegisterEvents(1);
 s.scale = 1;
 void *raw = (__bridge void*)s;
 double bounds[] = {0, 0, 200, 100};
 assert(sirin_ax_begin(raw, 81) == 0);
 assert(sirin_ax_node(raw, 1, 0, 4, "Confirm", "", "Review changes", 16, -1, bounds) == 0);
 assert(sirin_ax_node(raw, 2, 1, 5, "Changes stay in memory", "", "", 0, -1, bounds) == 0);
 assert(sirin_ax_node(raw, 3, 1, 3, "Remember", "", "", 1 | 2 | 8, 1, bounds) == 0);
 assert(sirin_ax_node(raw, 4, 1, 2, "Unavailable", "", "", 1 | 2 | 4, -1, bounds) == 0);
 assert(valid_tree(s.pending));
 assert(sirin_ax_node(raw, 3, 1, 3, "Duplicate", "", "", 3, 0, bounds) == 2);
 assert(sirin_ax_node(raw, 5, 5, 6, "Cycle", "", "", 0, -1, bounds) == 1);
 s.fields = [s.pending copy];
 struct accesskit_tree_update *update = tree(raw);
 char *debug = accesskit_tree_update_debug(update);
 assert(strstr(debug, "role: Dialog") && strstr(debug, "modal: true"));
 assert(strstr(debug, "children: [#131072, #196608, #262144]"));
 assert(strstr(debug, "role: Label") && strstr(debug, "Changes stay in memory"));
 assert(strstr(debug, "role: CheckBox") && strstr(debug, "toggled: True"));
 assert(strstr(debug, "focus: #196608"));
 puts(debug);
 accesskit_string_free(debug);
 accesskit_tree_update_free(update);
 accesskit_action_request request = {0};
 request.target_node = 3ULL << 16;
 request.action = ACCESSKIT_ACTION_CLICK;
 action(&request, raw);
 int64_t facts[5];
 char text[65537];
 assert(sirin_ax_poll(raw, facts, text, sizeof(text)) == 1);
 assert(facts[0] == 81 && facts[1] == 3 && facts[2] == 4);
 request.action = ACCESSKIT_ACTION_SET_VALUE;
 request.data.has_value = true;
 request.data.value.tag = ACCESSKIT_ACTION_DATA_VALUE;
 request.data.value.value = "invalid toggle shortcut";
 action(&request, raw);
 assert(sirin_ax_poll(raw, facts, text, sizeof(text)) == -2);
 request.target_node = 4ULL << 16;
 request.action = ACCESSKIT_ACTION_CLICK;
 action(&request, raw);
 assert(sirin_ax_poll(raw, facts, text, sizeof(text)) == 0);
 request.target_node = 2ULL << 16;
 request.action = ACCESSKIT_ACTION_FOCUS;
 action(&request, raw);
 assert(sirin_ax_poll(raw, facts, text, sizeof(text)) == -2);
 assert(sirin_ax_link(raw, 1, 3, 16) == 0);
 assert(!valid_tree(s.pending));
 assert(sirin_ax_publish(raw) == 2);
 assert(s.fields.count == 4 && [s.fields[0][@"parent"] longLongValue] == 0);
 assert(sirin_ax_link(raw, 1, 99, 16) == 0);
 assert(!valid_tree(s.pending));
 assert(sirin_ax_link(raw, 1, 0, 16) == 0);
 assert(valid_tree(s.pending));
 assert(sirin_ax_begin(raw, 82) == 0);
 assert(sirin_ax_node(raw, 3, 0, 3, "Remember", "", "", 3, 0, bounds) == 0);
 s.fields = [s.pending copy];
 update = tree(raw);
 debug = accesskit_tree_update_debug(update);
 assert(strstr(debug, "toggled: False"));
 accesskit_string_free(debug);
 accesskit_tree_update_free(update);
 assert(sirin_ax_begin(raw, 83) == 0);
 for(int i = 1; i <= 1024; i++)
  assert(sirin_ax_node(raw, i, 0, 5, "Label", "", "", 0, -1, bounds) == 0);
 assert(sirin_ax_node(raw, 1025, 0, 5, "Overflow", "", "", 0, -1, bounds) == 1);
 assert(sirin_ax_begin(raw, 84) == 0);
 for(int i = 1; i <= 64; i++)
  assert(sirin_ax_field(raw, i, 2, "Button", "", "", 0, 0, 0, 1, bounds, NULL, 0) == 0);
 assert(sirin_ax_field(raw, 65, 2, "Overflow", "", "", 0, 0, 0, 1, bounds, NULL, 0) == 1);
 puts("Semantic tree: dialog hierarchy, labels, checkbox state/actions, refusals and atomic validation passed");
}

int main(void) {
 @autoreleasepool {
 repeated_window_class_setup();
 assert(SDL_Init(SDL_INIT_EVENTS));
 semantic_tree();
 SirinAccessKit *s=[SirinAccessKit new];s.alive=YES;s.fields=@[];s.pending=[NSMutableArray new];s.actions=[NSMutableArray new];s.wakeType=SDL_RegisterEvents(1);s.scale=2;
 void *raw=(__bridge void*)s;double bounds[]={10,20,160,30};
 // a + emoji + combining cluster: UTF-16 ends 1/3/5, UTF-8 sizes 1/4/3.
 double spans[]={0,0,10,20,1,20,0, 1,1,20,20,1,20,0, 3,3,40,20,1,20,0, 5,5,50,20,1,20,0,
                 0,1,10,20,10,20,0, 1,3,20,20,20,20,0, 3,5,40,20,10,20,0};
 assert(sirin_ax_begin(raw,42)==0);
 assert(sirin_ax_field(raw,7,0,"Title","a😀é","Hint",1,2,1,1,bounds,spans,7)==0);
 s.fields=[s.pending copy];
 struct accesskit_tree_update *update=tree(raw);char *debug=accesskit_tree_update_debug(update);
 assert(strstr(debug,"TextInput") && strstr(debug,"TextRun") && strstr(debug,"Sirin"));
 assert(strstr(debug,"x0: 20.0") && strstr(debug,"character_widths: [40.0]"));
 puts(debug);accesskit_string_free(debug);accesskit_tree_update_free(update);
 accesskit_action_request request={0};request.target_node=7ULL<<16;request.action=ACCESSKIT_ACTION_SET_TEXT_SELECTION;
 request.data.has_value=true;request.data.value.tag=ACCESSKIT_ACTION_DATA_SET_TEXT_SELECTION;
 request.data.value.set_text_selection=(accesskit_text_selection){{(7ULL<<16)+2,0},{(7ULL<<16)+2,1}};
 action(&request,raw);int64_t facts[5];char text[65537];
 assert(sirin_ax_poll(raw,facts,text,sizeof(text))==1);
 assert(facts[0]==42 && facts[1]==7 && facts[2]==2 && facts[3]==1 && facts[4]==2);
 request.data.value.set_text_selection=(accesskit_text_selection){{(7ULL<<16)+2,1},{(7ULL<<16)+2,0}};
 action(&request,raw);assert(sirin_ax_poll(raw,facts,text,sizeof(text))==1);assert(facts[3]==3 && facts[4]==-2);
 // Out-of-run positions never become a selection or an owner action.
 request.data.value.set_text_selection.focus.character_index=2;action(&request,raw);
 assert(sirin_ax_poll(raw,facts,text,sizeof(text))==-2);assert(sirin_ax_poll(raw,facts,text,sizeof(text))==0);
 request.action=ACCESSKIT_ACTION_SET_VALUE;request.data.value.tag=ACCESSKIT_ACTION_DATA_VALUE;request.data.value.value="native value";
 action(&request,raw);assert(sirin_ax_poll(raw,facts,text,sizeof(text))==1);assert(facts[2]==3 && strcmp(text,"native value")==0);
 pthread_t thread;assert(pthread_create(&thread,NULL,foreign_focus,raw)==0);assert(pthread_join(thread,NULL)==0);
 assert(sirin_ax_poll(raw,facts,text,sizeof(text))==1 && facts[2]==1 && facts[0]==42);
 for(int i=0;i<65;i++)foreign_focus(raw);
 assert(s.actions.count==64);assert(sirin_ax_poll(raw,facts,text,sizeof(text))==-2);
 for(int i=0;i<64;i++)assert(sirin_ax_poll(raw,facts,text,sizeof(text))==1);
 assert(sirin_ax_poll(raw,facts,text,sizeof(text))==0);
 s.fields=@[];foreign_focus(raw);assert(sirin_ax_poll(raw,facts,text,sizeof(text))==0);
 assert(sirin_ax_begin(raw,43)==0);
 assert(sirin_ax_field(raw,7,0,"bad","abc","",0,0,0,1,bounds,spans,7)==2);
 assert(s.pending.count==0);
 NSMutableString *longCluster=[NSMutableString stringWithString:@"a"];for(int i=0;i<130;i++)[longCluster appendString:@"́"];
 double longSpan[]={0,131,10,20,10,20,0};
 assert(sirin_ax_field(raw,7,0,"Long cluster",longCluster.UTF8String,"",0,0,0,1,bounds,longSpan,1)==0);
 s.fields=[s.pending copy];update=tree(raw);debug=accesskit_tree_update_debug(update);
 assert(strstr(debug,"TextInput") && !strstr(debug,"TextRun"));accesskit_string_free(debug);accesskit_tree_update_free(update);
 s.alive=NO;foreign_focus(raw);assert(sirin_ax_poll(raw,facts,text,sizeof(text))==0);
 SDL_Quit();puts("AccessKit native protocol: UTF-16 selection, tree ownership, values, foreign-thread queue, overflow, retired targets and oversized grapheme fallback passed");
 }
 return 0;
}
