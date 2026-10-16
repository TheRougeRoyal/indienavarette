package com.veridion.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeWindowTrackerTest {
    @Test
    void testCaptureDoesNotCrash() {
        NativeWindowTracker tracker = NativeWindowTracker.getInstance();
        DesktopTracker.CaptureResult result = tracker.capture();
        
        assertNotNull(result);
        assertNotNull(result.status());
        assertNotNull(result.appName());
        assertNotNull(result.windowTitle());
        
        // In headless CI, we expect UNAVAILABLE, but it shouldn't crash
        System.out.println("Tracker status: " + result.status() + " | App: " + result.appName());
    }
}
