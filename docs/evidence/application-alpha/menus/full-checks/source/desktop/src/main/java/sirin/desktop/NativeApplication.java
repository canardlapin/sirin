package sirin.desktop;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.*;
import static java.lang.foreign.ValueLayout.*;

/** Main-thread SDL lifetime and global facts. No managed upcalls. */
public final class NativeApplication implements AutoCloseable {
    private static final MethodHandle OPEN = NativeWindow.bind("sirin_desktop_app_open",JAVA_INT,ADDRESS,ADDRESS,ADDRESS);
    private static final MethodHandle CLOSE = NativeWindow.bind("sirin_desktop_app_close",JAVA_INT,ADDRESS);
    private static final MethodHandle WAIT = NativeWindow.bind("sirin_desktop_app_wait",JAVA_INT,ADDRESS,JAVA_INT,ADDRESS);
    private static final MethodHandle WAKE = NativeWindow.bind("sirin_desktop_app_wake",JAVA_INT,JAVA_INT,JAVA_INT);
    private static final MethodHandle STATS = NativeWindow.bind("sirin_desktop_app_stats",JAVA_INT,ADDRESS,ADDRESS);
    private static final MethodHandle QUIT = NativeWindow.bind("sirin_desktop_app_request_quit",JAVA_INT,ADDRESS);
    private static final MethodHandle LIVE = NativeWindow.bind("sirin_desktop_app_live",JAVA_INT);
    private static final MethodHandle MENU_BEGIN = NativeWindow.bind("sirin_desktop_menu_begin",JAVA_INT,ADDRESS,JAVA_LONG);
    private static final MethodHandle MENU_ADD = NativeWindow.bind("sirin_desktop_menu_add",JAVA_INT,ADDRESS,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT,ADDRESS,ADDRESS,JAVA_INT,JAVA_INT,JAVA_INT);
    private static final MethodHandle MENU_ROUTE = NativeWindow.bind("sirin_desktop_menu_route",JAVA_INT,ADDRESS,ADDRESS,JAVA_INT);
    private static final MethodHandle MENU_PUBLISH = NativeWindow.bind("sirin_desktop_menu_publish",JAVA_INT,ADDRESS);
    private static final MethodHandle MENU_POLL = NativeWindow.bind("sirin_desktop_menu_poll",JAVA_INT,ADDRESS,ADDRESS);
    private static final MethodHandle MENU_STANDARD = NativeWindow.bind("sirin_desktop_menu_standard",JAVA_INT,ADDRESS,JAVA_INT);
    private final Thread owner = Thread.currentThread();
    private final Arena arena;
    private final MemorySegment handle, facts;
    private final int serial,wakeType;
    private boolean closed;
    public record Event(boolean found,boolean quit,boolean wake,int target) {}
    public record Stats(long windows,long queuedTextBytes,long staleEvents,long refusedEvents) {}
    public record MenuKey(String key,int mods) {
        public MenuKey { string(key);if(mods<0 || mods>15)throw new IllegalArgumentException("menu modifiers"); }
    }
    public record MenuRow(int parent,int node,int kind,int action,String label,MenuKey key,boolean enabled,boolean busy) {
        public MenuRow {
            string(label);Objects.requireNonNull(key);
            if(parent<0 || node<1 || node>512 || kind<0 || kind>2 || (kind==1 && action<1))
                throw new IllegalArgumentException("menu row");
        }
    }
    public record MenuAction(long revision,int id) {}
    private static void string(String value) {
        if(value==null || value.indexOf('\0')>=0 || value.length()>100000)throw new IllegalArgumentException("menu string");
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(++i==value.length() || !Character.isLowSurrogate(value.charAt(i)))throw new IllegalArgumentException("invalid menu UTF-16");
            } else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("invalid menu UTF-16");
        }
    }
    static void validateMenus(List<MenuRow> rows,Set<MenuKey> routed) {
        if(rows.size()>512 || routed.size()>768)throw new IllegalArgumentException("native menu budget");
        Set<Integer> nodes=new HashSet<>(),actions=new HashSet<>();Map<Integer,Integer> groups=new HashMap<>();groups.put(0,-1);
        long text=0;
        for(MenuRow row:rows) {
            Integer parent=groups.get(row.parent());
            if(parent==null || parent+1>32 || !nodes.add(row.node()) || (row.kind()==1 && !actions.add(row.action())))
                throw new IllegalArgumentException("native menu structure");
            if(row.kind()==0)groups.put(row.node(),parent+1);
            text+=row.label().length()+row.key().key().length();
        }
        for(MenuKey key:routed)text+=key.key().length();
        if(text>100000)throw new IllegalArgumentException("native menu text budget");
    }
    /** Build privately, then swap. A validation failure leaves the published tree intact. */
    public void installMenus(long revision,List<MenuRow> rows,Set<MenuKey> routed) {
        check();validateMenus(rows,routed);
        try(Arena a=Arena.ofConfined()) {
            if((int)MENU_BEGIN.invokeExact(handle,revision)!=0)throw new IllegalStateException("native menu revision rejected");
            for(MenuRow row:rows) {
                MemorySegment label=a.allocateFrom(row.label()),key=a.allocateFrom(row.key().key());
                if((int)MENU_ADD.invokeExact(handle,row.parent(),row.node(),row.kind(),row.action(),label,key,row.key().mods(),row.enabled()?1:0,row.busy()?1:0)!=0)
                    throw new IllegalStateException("native menu row rejected");
            }
            for(MenuKey key:routed)if((int)MENU_ROUTE.invokeExact(handle,a.allocateFrom(key.key()),key.mods())!=0)
                throw new IllegalStateException("native menu routing rejected");
            if((int)MENU_PUBLISH.invokeExact(handle)!=0)throw new IllegalStateException("native menu publication rejected");
        }catch(Throwable error){throw NativeWindow.failure(error);}
    }
    public MenuAction pollMenu() {
        check();try(Arena a=Arena.ofConfined()) {
            MemorySegment value=a.allocate(JAVA_LONG,2);int status=(int)MENU_POLL.invokeExact(handle,value);
            if(status<0)throw new IllegalStateException("native menu fact transport refused: "+status);
            return status==0?null:new MenuAction(value.getAtIndex(JAVA_LONG,0),Math.toIntExact(value.getAtIndex(JAVA_LONG,1)));
        }catch(Throwable error){throw NativeWindow.failure(error);}
    }
    void standardMenu(int kind) {
        check();try{if((int)MENU_STANDARD.invokeExact(handle,kind)!=0)throw new IllegalStateException("native standard menu rejected");}
        catch(Throwable error){throw NativeWindow.failure(error);}
    }
    public NativeApplication() {
        if(!NativeWindow.firstThread())throw new IllegalStateException("launch with -XstartOnFirstThread on macOS");
        arena=Arena.ofConfined();MemorySegment opened=MemorySegment.NULL;
        try {
            MemorySegment out=arena.allocate(ADDRESS),info=arena.allocate(JAVA_INT,2),error=arena.allocate(512);
            int status=(int)OPEN.invokeExact(out,info,error);
            if(status!=0)throw new IllegalStateException(error.getString(0));
            opened=out.get(ADDRESS,0);handle=opened;serial=info.getAtIndex(JAVA_INT,0);wakeType=info.getAtIndex(JAVA_INT,1);
            facts=arena.allocate(JAVA_INT,3);
        } catch(Throwable error) {
            if(opened.address()!=0)try{int ignored=(int)CLOSE.invokeExact(opened);}catch(Throwable close){error.addSuppressed(close);}
            arena.close();throw NativeWindow.failure(error);
        }
    }
    private void check() {
        if(Thread.currentThread()!=owner)throw new IllegalStateException("application requires its owner thread");
        if(closed)throw new IllegalStateException("application is closed");
    }
    public Event waitEvent(int timeoutMillis) {
        check();if(timeoutMillis < -1)throw new IllegalArgumentException("invalid event timeout");
        try {
            int status=(int)WAIT.invokeExact(handle,timeoutMillis,facts);
            if(status<0)throw new IllegalStateException("native application event transport refused: "+status);
            return new Event(status!=0,facts.getAtIndex(JAVA_INT,0)!=0,facts.getAtIndex(JAVA_INT,1)!=0,facts.getAtIndex(JAVA_INT,2));
        } catch(Throwable error){throw NativeWindow.failure(error);}
    }
    /** A worker can wake an application with no open windows; serialized with close. */
    public synchronized boolean wake() {
        if(closed)return false;
        try{return (int)WAKE.invokeExact(serial,wakeType)==0;}catch(Throwable error){throw NativeWindow.failure(error);}
    }
    void requestQuit() {
        check();try{if((int)QUIT.invokeExact(handle)!=0)throw new IllegalStateException("quit request rejected");}
        catch(Throwable error){throw NativeWindow.failure(error);}
    }
    public Stats stats() {
        check();try(Arena a=Arena.ofConfined()) {
            MemorySegment values=a.allocate(JAVA_LONG,4);
            if((int)STATS.invokeExact(handle,values)!=0)throw new IllegalStateException("native application stats rejected");
            return new Stats(values.getAtIndex(JAVA_LONG,0),values.getAtIndex(JAVA_LONG,1),values.getAtIndex(JAVA_LONG,2),values.getAtIndex(JAVA_LONG,3));
        }catch(Throwable error){throw NativeWindow.failure(error);}
    }
    public static int liveApplications() {
        try{return (int)LIVE.invokeExact();}catch(Throwable error){throw NativeWindow.failure(error);}
    }
    @Override public synchronized void close() {
        if(closed)return;check();
        try {
            if((int)CLOSE.invokeExact(handle)!=0)throw new IllegalStateException("retire every native window before closing its application");
            closed=true;arena.close();
        }catch(Throwable error){throw NativeWindow.failure(error);}
    }
}
