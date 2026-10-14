package com.veridion.client;

public class NativeWindowTracker implements DesktopTracker {
    static { loadLibrary(); }

    private static void loadLibrary() {
        String explicit = System.getProperty("veridion.native.path");
        if (explicit != null && !explicit.isBlank()) { System.load(java.nio.file.Path.of(explicit).toAbsolutePath().toString()); return; }
        System.loadLibrary("veridion_native");
    }

    public native String getActiveWindowTitle();
    public native String getActiveAppName();

    @Override public CaptureResult capture() {
        try { return new CaptureResult("SUPPORTED", getActiveAppName(), getActiveWindowTitle()); }
        catch (LinkageError | RuntimeException e) { return new CaptureResult("UNAVAILABLE", "Unknown", "Window metadata unavailable"); }
    }

    public static NativeWindowTracker getInstance() {
        return INSTANCE;
    }

    private static final NativeWindowTracker INSTANCE = new NativeWindowTracker();

    private NativeWindowTracker() {}
}
