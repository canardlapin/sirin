package sirin.desktop;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import static java.lang.foreign.ValueLayout.*;

/** JDK25 transport. Opaque native handles never escape this owner. No upcalls. */
public final class NativeWindow implements AutoCloseable {
    private static final Arena LIBRARY_ARENA = Arena.ofShared();
    private static final SymbolLookup LIBRARY = SymbolLookup.libraryLookup(
        Path.of(System.getProperty("sirin.desktop.library")), LIBRARY_ARENA);
    private static final Linker LINKER = Linker.nativeLinker();
    private static MethodHandle bind(String name, MemoryLayout result, MemoryLayout... args) {
        return LINKER.downcallHandle(LIBRARY.find(name).orElseThrow(), FunctionDescriptor.of(result, args));
    }
    private static final MethodHandle OPEN = bind("sirin_desktop_open", JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
    private static final MethodHandle CLOSE = bind("sirin_desktop_close", JAVA_INT, ADDRESS);
    private static final MethodHandle WAIT = bind("sirin_desktop_wait", JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle TICKS = bind("sirin_desktop_ticks",JAVA_LONG);
    private static final MethodHandle INJECT = bind("sirin_desktop_inject",JAVA_INT,ADDRESS,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_DOUBLE,JAVA_DOUBLE,JAVA_DOUBLE);
    private static final MethodHandle WAKE = bind("sirin_desktop_wake", JAVA_INT, JAVA_INT, JAVA_INT);
    private static final MethodHandle RESIZE = bind("sirin_desktop_resize", JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);
    private static final MethodHandle CLEAR = bind("sirin_desktop_clear", JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
    private static final MethodHandle PIXELS = bind("sirin_desktop_pixels", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT);
    private static final MethodHandle LOST = bind("sirin_desktop_renderer_lost",JAVA_INT,ADDRESS);
    private static final MethodHandle DESTROY_DEVICE = bind("sirin_desktop_renderer_destroy",JAVA_INT,ADDRESS);
    private static final MethodHandle BATCH = bind("sirin_desktop_batch_start",JAVA_INT,ADDRESS,ADDRESS,ADDRESS,ADDRESS);
    private static final MethodHandle GPU = bind("sirin_desktop_frame_present",JAVA_INT,ADDRESS,ADDRESS);
    private static final MethodHandle KEYBOARD_FOCUS = bind("sirin_desktop_keyboard_focus",JAVA_INT,ADDRESS);
    private static final MethodHandle ACTIVATE = bind("sirin_desktop_activate",JAVA_INT,ADDRESS);
    private static final MethodHandle TEXT = bind("sirin_desktop_text",ADDRESS,ADDRESS);
    private static final MethodHandle TEXT_INPUT = bind("sirin_desktop_text_input",JAVA_INT,ADDRESS,JAVA_INT,ADDRESS);
    private static final MethodHandle CLIPBOARD = bind("sirin_desktop_clipboard",JAVA_INT,ADDRESS,JAVA_INT,ADDRESS,JAVA_INT);
    private static final MethodHandle AX_BEGIN = bind("sirin_desktop_ax_begin",JAVA_INT,ADDRESS,JAVA_LONG);
    private static final MethodHandle AX_FIELD = bind("sirin_desktop_ax_field",JAVA_INT,ADDRESS,JAVA_LONG,JAVA_INT,ADDRESS,ADDRESS,ADDRESS,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT,ADDRESS,ADDRESS,JAVA_INT);
    private static final MethodHandle AX_PUBLISH = bind("sirin_desktop_ax_publish",JAVA_INT,ADDRESS);
    private static final MethodHandle AX_POLL = bind("sirin_desktop_ax_poll",JAVA_INT,ADDRESS,ADDRESS,ADDRESS,JAVA_INT);
    private static final MethodHandle IME_CONTROL = bind("sirin_desktop_ime_control",JAVA_INT,ADDRESS,ADDRESS,JAVA_INT,JAVA_INT,JAVA_INT);
    private static final MethodHandle LIVE = bind("sirin_desktop_live", JAVA_INT);
    private static final MethodHandle LATE = bind("sirin_desktop_late",JAVA_INT);
    private static final MethodHandle LATE_OBJECTS = bind("sirin_desktop_late_objects",JAVA_INT);
    private static final MethodHandle REQUESTS = bind("sirin_desktop_requests", JAVA_INT);
    private static final MethodHandle FAULT = bind("sirin_desktop_fault", JAVA_INT, JAVA_INT);
    private static final MethodHandle FIRST = bind("sirin_desktop_first_thread", JAVA_INT);
    private final Thread owner = Thread.currentThread();
    private final Arena arena;
    private final MemorySegment handle;
    private final MemorySegment facts,position,times;
    private int windowId, wakeType;
    private volatile boolean closed;
    private Event initialEvent;
    public record Event(boolean found, int flags, int width, int height, int logicalWidth, int logicalHeight, int kind, int key, int mods, int button, boolean repeat, double x, double y, double deltaX, double deltaY, long timestamp, long ticks, String text, int clicks) {
        public boolean quit() { return (flags & 1) != 0; }
        public boolean redraw() { return (flags & 2) != 0; }
        public boolean wakeup() { return (flags & 4) != 0; }
    }
    private static RuntimeException failure(Throwable error) {
        return error instanceof RuntimeException r ? r : new IllegalStateException("FFM call failed", error);
    }
    public static int liveWindows() {
        try { return (int)LIVE.invokeExact(); } catch (Throwable e) { throw failure(e); }
    }
    public static int lateCallbacks() {
        try {return (int)LATE.invokeExact();} catch(Throwable e) {throw failure(e);}
    }
    public static int lateObjects() {
        try {return (int)LATE_OBJECTS.invokeExact();} catch(Throwable e) {throw failure(e);}
    }
    public static int liveRequests() {
        try { return (int)REQUESTS.invokeExact(); } catch (Throwable e) { throw failure(e); }
    }
    static void failAt(int stage) {
        try { if ((int)FAULT.invokeExact(stage) != 0) throw new IllegalStateException("fault setup rejected"); }
        catch (Throwable e) { throw failure(e); }
    }
    public static boolean firstThread() {
        try { return (int)FIRST.invokeExact() != 0; } catch (Throwable e) { throw failure(e); }
    }
    public NativeWindow(String title, int width, int height) {
        if (!firstThread()) throw new IllegalStateException("launch with -XstartOnFirstThread on macOS");
        if (title.indexOf('\0') >= 0 || width <= 0 || height <= 0) throw new IllegalArgumentException("invalid window arguments");
        arena = Arena.ofConfined();
        facts = arena.allocate(JAVA_INT, 14);
        position=arena.allocate(JAVA_DOUBLE,4);times=arena.allocate(JAVA_LONG,2);
        MemorySegment opened = MemorySegment.NULL;
        try {
            MemorySegment pointer = arena.allocate(ADDRESS);
            MemorySegment error = arena.allocate(512);
            int status = (int)OPEN.invokeExact(arena.allocateFrom(title), width, height, pointer, error);
            if (status != 0) throw new IllegalStateException(error.getString(0));
            opened = pointer.get(ADDRESS, 0);
            handle = opened;
            initialEvent = waitEvent(0);
        } catch (Throwable e) {
            if (opened.address() != 0) try { int ignored = (int)CLOSE.invokeExact(opened); }
            catch (Throwable closeError) { e.addSuppressed(closeError); }
            arena.close(); throw failure(e);
        }
    }
    private void checkOwner() {
        if (closed) throw new IllegalStateException("window is closed");
        if (Thread.currentThread() != owner) throw new IllegalStateException("window requires its owner thread");
    }
    public Event waitEvent(int timeoutMillis) {
        checkOwner();
        if (timeoutMillis < -1) throw new IllegalArgumentException("invalid event timeout");
        if (initialEvent != null) { Event event = initialEvent; initialEvent = null; return event; }
        try {
            int found = (int)WAIT.invokeExact(handle, timeoutMillis, facts,position,times);
            if (found < 0) throw new IllegalStateException("native event wait rejected");
            windowId = facts.getAtIndex(JAVA_INT, 3); wakeType = facts.getAtIndex(JAVA_INT, 4);
            return new Event(found != 0, facts.getAtIndex(JAVA_INT, 0), facts.getAtIndex(JAVA_INT, 1), facts.getAtIndex(JAVA_INT, 2), facts.getAtIndex(JAVA_INT, 5), facts.getAtIndex(JAVA_INT, 6),
                facts.getAtIndex(JAVA_INT,7),facts.getAtIndex(JAVA_INT,8),facts.getAtIndex(JAVA_INT,9),facts.getAtIndex(JAVA_INT,10),facts.getAtIndex(JAVA_INT,11)!=0,
                position.getAtIndex(JAVA_DOUBLE,0),position.getAtIndex(JAVA_DOUBLE,1),position.getAtIndex(JAVA_DOUBLE,2),position.getAtIndex(JAVA_DOUBLE,3),times.getAtIndex(JAVA_LONG,0),times.getAtIndex(JAVA_LONG,1),((MemorySegment)TEXT.invokeExact(handle)).reinterpret(65537).getString(0),facts.getAtIndex(JAVA_INT,12));
        } catch (Throwable e) { throw failure(e); }
    }
    /** May be called on a JVM worker. Serializes with close; never touches handle memory. */
    public synchronized boolean wake() {
        if (closed) return false;
        try { return (int)WAKE.invokeExact(windowId, wakeType) == 0; }
        catch (Throwable e) { throw failure(e); }
    }
    public static long ticks() {
        try {return (long)TICKS.invokeExact();} catch(Throwable e) {throw failure(e);}
    }
    public void inject(int kind,int key,int mods,double x,double y,double delta) {
        checkOwner();
        try {if((int)INJECT.invokeExact(handle,kind,key,mods,x,y,delta)!=0)throw new IllegalArgumentException("injection rejected");}
        catch(Throwable e){throw failure(e);}
    }
    public void resize(int width, int height) {
        checkOwner();
        try { if ((int)RESIZE.invokeExact(handle, width, height) != 0) throw new IllegalArgumentException("resize rejected"); }
        catch (Throwable e) { throw failure(e); }
    }
    public boolean clear(double red, double green, double blue) {
        checkOwner();
        for (double value : new double[]{red, green, blue})
            if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("invalid color");
        try {
            int result = (int)CLEAR.invokeExact(handle, red, green, blue);
            if (result < 0) throw new IllegalStateException("presentation failed");
            return result != 0;
        } catch (Throwable e) { throw failure(e); }
    }
    public boolean present(byte[] rgba, int width, int height) {
        checkOwner();
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096 || (long)width*height*4 != rgba.length)
            throw new IllegalArgumentException("invalid pixel image");
        try (Arena upload = Arena.ofConfined()) {
            MemorySegment pixels = upload.allocate(rgba.length);
            pixels.copyFrom(MemorySegment.ofArray(rgba));
            int result = (int)PIXELS.invokeExact(handle, pixels, width, height);
            if (result < 0) throw new IllegalStateException("surface pixel upload unsupported or failed");
            return result != 0;
        } catch (Throwable e) { throw failure(e); }
    }
    /** Main prepares an independently referenced renderer handle for one worker. */
    public MemorySegment prepareRenderer(String shader) {
        checkOwner();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out=a.allocate(ADDRESS),error=a.allocate(512);
            int status=(int)BATCH.invokeExact(handle,a.allocateFrom(shader),out,error);
            if(status!=0)throw new IllegalStateException("batch renderer: "+error.getString(0));
            return out.get(ADDRESS,0);
        } catch(Throwable e){throw failure(e);}
    }
    public boolean rendererLost() {
        checkOwner();try{return (int)LOST.invokeExact(handle)!=0;}catch(Throwable e){throw failure(e);}
    }
    /** Explicit development failure control invokes actual wgpuDeviceDestroy. */
    public void destroyRenderer() {
        checkOwner();try{if((int)DESTROY_DEVICE.invokeExact(handle)!=0)throw new IllegalStateException("device destruction rejected");}catch(Throwable e){throw failure(e);}
    }
    public boolean presentGpu(MemorySegment packet) {
        checkOwner();
        try {
            int result=(int)GPU.invokeExact(handle,packet);
            if(result==-2)return false;
            if(result<0)throw new IllegalStateException("GPU frame presentation failed");
            return result!=0;
        } catch(Throwable e){throw failure(e);}
    }
    @Override public synchronized void close() {
        if (closed) return;
        checkOwner();
        try {
            if ((int)CLOSE.invokeExact(handle) != 0) throw new IllegalStateException("native close rejected");
            closed = true; arena.close();
        } catch (Throwable e) { throw failure(e); }
    }
    private static void accepted(int status) {if(status!=0)throw new IllegalStateException("native editor transport refused: "+status);}
    public boolean keyboardFocus(){checkOwner();try{return (int)KEYBOARD_FOCUS.invokeExact(handle)!=0;}catch(Throwable e){throw failure(e);}}
    public void activate(){checkOwner();try{accepted((int)ACTIVATE.invokeExact(handle));}catch(Throwable e){throw failure(e);}}
    public void textInput(int mode,double x,double y,double width,double height) {
        checkOwner();try(Arena a=Arena.ofConfined()) {
            MemorySegment rect=a.allocateFrom(JAVA_DOUBLE,x,y,width,height);
            accepted((int)TEXT_INPUT.invokeExact(handle,mode,rect));
        }catch(Throwable e){throw failure(e);}
    }
    public static final class ClipboardUnavailable extends RuntimeException {public ClipboardUnavailable(){super("Clipboard is unavailable");}}
    public String clipboard() {
        checkOwner();try(Arena a=Arena.ofConfined()) {
            MemorySegment text=a.allocate(65537);int status=(int)CLIPBOARD.invokeExact(handle,0,text,65537);if(status==2)throw new IllegalArgumentException("clipboard budget");if(status!=0)throw new ClipboardUnavailable();return text.getString(0);
        }catch(Throwable e){throw failure(e);}
    }
    public void clipboard(String value) {
        checkOwner();if(value.indexOf('\0')>=0 || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException("clipboard budget/NUL");
        try(Arena a=Arena.ofConfined()){if((int)CLIPBOARD.invokeExact(handle,1,a.allocateFrom(value),65537)!=0)throw new ClipboardUnavailable();}catch(Throwable e){throw failure(e);}
    }
    public void beginAccessibility(long revision) {checkOwner();try{accepted((int)AX_BEGIN.invokeExact(handle,revision));}catch(Throwable e){throw failure(e);}}
    public void accessibilityField(long id,int kind,String label,String value,String help,int start,int length,boolean focused,boolean enabled,double[] bounds,double[] spans) {
        checkOwner();if(bounds.length!=4 || spans.length%7!=0)throw new IllegalArgumentException("AX geometry");
        try(Arena a=Arena.ofConfined()){
            MemorySegment b=a.allocateFrom(JAVA_DOUBLE,bounds),g=a.allocateFrom(JAVA_DOUBLE,spans);
            accepted((int)AX_FIELD.invokeExact(handle,id,kind,a.allocateFrom(label),a.allocateFrom(value),a.allocateFrom(help),start,length,focused?1:0,enabled?1:0,b,g,spans.length/7));
        }catch(Throwable e){throw failure(e);}
    }
    public void publishAccessibility(){checkOwner();try{accepted((int)AX_PUBLISH.invokeExact(handle));}catch(Throwable e){throw failure(e);}}
    /** Selection facts use an anchor and signed UTF-16 extent, preserving direction. */
    public record AccessibilityAction(long revision,long target,int kind,int start,int length,String value){}
    public AccessibilityAction pollAccessibility(){
        checkOwner();try(Arena a=Arena.ofConfined()){
            MemorySegment facts=a.allocate(JAVA_LONG,5),text=a.allocate(65537);
            int result=(int)AX_POLL.invokeExact(handle,facts,text,65537);
            if(result==-2){System.err.println("AccessKit request refused (invalid action, budget or wake failure)");return null;}
            if(result<0)throw new IllegalStateException("AX action queue failure: "+result);if(result==0)return null;
            return new AccessibilityAction(facts.getAtIndex(JAVA_LONG,0),facts.getAtIndex(JAVA_LONG,1),(int)facts.getAtIndex(JAVA_LONG,2),(int)facts.getAtIndex(JAVA_LONG,3),(int)facts.getAtIndex(JAVA_LONG,4),text.getString(0));
        }catch(Throwable e){throw failure(e);}
    }
    /** Development control goes through Cocoa's actual NSTextInputClient. */
    public void imeControl(String text,int start,int length,boolean commit){checkOwner();try(Arena a=Arena.ofConfined()){accepted((int)IME_CONTROL.invokeExact(handle,a.allocateFrom(text),start,length,commit?1:0));}catch(Throwable e){throw failure(e);}}
    /** Independent C compiler layout and by-value round trip; no native window needed. */
    public static void verifyAbi() {
        MethodHandle layout = bind("sirin_abi_layout", JAVA_LONG, JAVA_INT, ADDRESS);
        MethodHandle echo = LINKER.downcallHandle(LIBRARY.find("sirin_abi_echo_color").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        try (Arena a = Arena.ofConfined()) {
            MemorySegment sizes = a.allocate(JAVA_LONG, 16);
            long count = (long)layout.invokeExact(6, sizes);
            if (count != 5 || sizes.getAtIndex(JAVA_LONG, 0) != 32) throw new IllegalStateException("color ABI size mismatch");
            MemorySegment input = a.allocate(JAVA_DOUBLE, 4), output = a.allocate(JAVA_DOUBLE, 4);
            double[] values = {0.125, -0.0, 0.75, 1.0};
            for (int i=0; i<4; i++) {
                if (sizes.getAtIndex(JAVA_LONG, i+1) != i*8) throw new IllegalStateException("color ABI offset mismatch");
                input.setAtIndex(JAVA_DOUBLE, i, values[i]);
            }
            echo.invokeExact(input, output);
            for (int i=0; i<4; i++) if (Double.doubleToRawLongBits(output.getAtIndex(JAVA_DOUBLE,i)) != Double.doubleToRawLongBits(values[i]))
                throw new IllegalStateException("color ABI round trip mismatch");
        } catch (Throwable e) { throw failure(e); }
    }
}
