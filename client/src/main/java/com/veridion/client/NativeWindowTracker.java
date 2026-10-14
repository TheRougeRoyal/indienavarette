package com.veridion.client;

public class NativeWindowTracker implements DesktopTracker {
    static { loadLibrary(); }

    private static void loadLibrary() {
        String explicit = System.getProperty("veridion.native.path");
        if (explicit != null && !explicit.isBlank()) { System.load(java.nio.file.Path.of(explicit).toAbsolutePath().toString()); return; }
        System.loadLibrary("veridion_native");
    }

    private native String[] captureActiveWindowInfo();

    @Override public CaptureResult capture() {
        try {
            String[] info = captureActiveWindowInfo();
            if (info == null || info.length < 2) return new CaptureResult("UNAVAILABLE", "Unknown", "Window metadata unavailable");
            String status = "Display unavailable".equals(info[1]) ? "UNAVAILABLE" : "SUPPORTED";
            return new CaptureResult(status, info[0], info[1]);
        }
        catch (LinkageError | RuntimeException e) { return new CaptureResult("UNAVAILABLE", "Unknown", "Window metadata unavailable"); }
    }

    public static NativeWindowTracker getInstance() {
        return INSTANCE;
    }

    private static final NativeWindowTracker INSTANCE = new NativeWindowTracker();

    private NativeWindowTracker() {}
}
