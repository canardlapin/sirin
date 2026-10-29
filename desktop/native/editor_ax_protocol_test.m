// Native-only protocol checks: no NSApplication, window, adapter or Scala upcall.
#import "editor_ax.m"
#include <assert.h>
#include <pthread.h>

static void *foreign_focus(void *raw) {
 accesskit_action_request request={0};request.target_node=7ULL<<16;request.action=ACCESSKIT_ACTION_FOCUS;
 action(&request,raw);return NULL;
}
int main(void) {
 @autoreleasepool {
 assert(SDL_Init(SDL_INIT_EVENTS));
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
