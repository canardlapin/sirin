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
    private static final MethodHandle WAIT = bind("sirin_desktop_wait", JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
    private static final MethodHandle WAKE = bind("sirin_desktop_wake", JAVA_INT, JAVA_INT, JAVA_INT);
    private static final MethodHandle RESIZE = bind("sirin_desktop_resize", JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);
    private static final MethodHandle CLEAR = bind("sirin_desktop_clear", JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
    private static final MethodHandle PIXELS = bind("sirin_desktop_pixels", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT);
    private static final MethodHandle LIVE = bind("sirin_desktop_live", JAVA_INT);
    private static final MethodHandle REQUESTS = bind("sirin_desktop_requests", JAVA_INT);
    private static final MethodHandle FAULT = bind("sirin_desktop_fault", JAVA_INT, JAVA_INT);
    private static final MethodHandle FIRST = bind("sirin_desktop_first_thread", JAVA_INT);
    private final Thread owner = Thread.currentThread();
    private final Arena arena;
    private final MemorySegment handle;
    private final MemorySegment facts;
    private int windowId, wakeType;
    private volatile boolean closed;
    private Event initialEvent;
    public record Event(boolean found, int flags, int width, int height, int logicalWidth, int logicalHeight) {
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
        facts = arena.allocate(JAVA_INT, 7);
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
            int found = (int)WAIT.invokeExact(handle, timeoutMillis, facts);
            if (found < 0) throw new IllegalStateException("native event wait rejected");
            windowId = facts.getAtIndex(JAVA_INT, 3); wakeType = facts.getAtIndex(JAVA_INT, 4);
            return new Event(found != 0, facts.getAtIndex(JAVA_INT, 0), facts.getAtIndex(JAVA_INT, 1), facts.getAtIndex(JAVA_INT, 2), facts.getAtIndex(JAVA_INT, 5), facts.getAtIndex(JAVA_INT, 6));
        } catch (Throwable e) { throw failure(e); }
    }
    /** May be called on a JVM worker. Serializes with close; never touches handle memory. */
    public synchronized boolean wake() {
        if (closed) return false;
        try { return (int)WAKE.invokeExact(windowId, wakeType) == 0; }
        catch (Throwable e) { throw failure(e); }
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
    @Override public synchronized void close() {
        if (closed) return;
        checkOwner();
        try {
            if ((int)CLOSE.invokeExact(handle) != 0) throw new IllegalStateException("native close rejected");
            closed = true; arena.close();
        } catch (Throwable e) { throw failure(e); }
    }
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
