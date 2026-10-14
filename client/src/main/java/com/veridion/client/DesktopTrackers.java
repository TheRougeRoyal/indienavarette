package com.veridion.client;

/** Selects only desktop interfaces supported by the current session. */
public final class DesktopTrackers {
    private DesktopTrackers() { }
    public static DesktopTracker current() {
        String session = System.getenv("XDG_SESSION_TYPE");
        if ("wayland".equalsIgnoreCase(session) || System.getenv("WAYLAND_DISPLAY") != null) {
            return () -> new DesktopTracker.CaptureResult("UNSUPPORTED_CAPABILITY", "Wayland", "Active window metadata requires a supported portal; unavailable");
        }
        try { return NativeWindowTracker.getInstance(); }
        catch (LinkageError e) { return () -> new DesktopTracker.CaptureResult("UNAVAILABLE", "Unknown", "X11 tracker unavailable"); }
    }
}
