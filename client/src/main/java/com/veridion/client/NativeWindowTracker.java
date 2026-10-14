package com.veridion.client;

public class NativeWindowTracker {
    static {
        System.loadLibrary("veridion_native");
    }

    public native String getActiveWindowTitle();
    public native String getActiveAppName();

    public static NativeWindowTracker getInstance() {
        return INSTANCE;
    }

    private static final NativeWindowTracker INSTANCE = new NativeWindowTracker();

    private NativeWindowTracker() {}
}
