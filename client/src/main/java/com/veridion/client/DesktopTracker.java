package com.veridion.client;

/** User-visible, metadata-only desktop integration. */
public interface DesktopTracker {
    CaptureResult capture();
    record CaptureResult(String status, String appName, String windowTitle) { }
}
