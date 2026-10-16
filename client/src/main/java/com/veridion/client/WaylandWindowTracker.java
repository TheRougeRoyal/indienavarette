package com.veridion.client;

/**
 * Wayland implementation for active window tracking.
 * Current status: Unsupported.
 * Future implementation should use XDG Desktop Portal (org.freedesktop.portal.Window).
 */
public class WaylandWindowTracker implements DesktopTracker {
    private static final WaylandWindowTracker INSTANCE = new WaylandWindowTracker();

    private WaylandWindowTracker() {}

    public static WaylandWindowTracker getInstance() {
        return INSTANCE;
    }

    @Override
    public CaptureResult capture() {
        return new CaptureResult(
            "UNSUPPORTED_CAPABILITY", 
            "Wayland", 
            "Active window metadata requires a supported portal; unavailable"
        );
    }
}
