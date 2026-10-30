package sirin.desktop;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
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
    private final Thread owner = Thread.currentThread();
    private final Arena arena;
    private final MemorySegment handle, facts;
    private final int serial,wakeType;
    private boolean closed;
    public record Event(boolean found,boolean quit,boolean wake,int target) {}
    public record Stats(long windows,long queuedTextBytes,long staleEvents,long refusedEvents) {}
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
