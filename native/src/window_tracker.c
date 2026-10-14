#include <jni.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/Xatom.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>

// Function to get the active window title on X11
void get_active_window_info(Display *display, Window root, char *app_name, char *window_title, int max_len) {
    Atom active_window_atom = XInternAtom(display, "_NET_ACTIVE_WINDOW", False);
    Atom actual_type;
    int actual_format;
    unsigned long nitems = 1;
    unsigned long bytes_after = 0;
    Window active_window;

    if (XGetWindowProperty(display, root, active_window_atom, 0, 1, False,
                           AnyPropertyType, &actual_type, &actual_format, &nitems,
                           &bytes_after, (unsigned char *)&active_window) == Success) {

        char *title = NULL;
        if (XFetchName(display, active_window, &title)) {
            strncpy(window_title, title ? title : "Unknown", max_len - 1);
            XFree(title);
        } else {
            strncpy(window_title, "Unknown", max_len - 1);
        }

        // Simplified app name retrieval for now
        strncpy(app_name, "X11 App", max_len - 1);
    } else {
        strncpy(app_name, "None", max_len - 1);
        strncpy(window_title, "None", max_len - 1);
    }
}

JNIEXPORT jstring JNICALL Java_com_veridion_client_NativeWindowTracker_getActiveWindowTitle(JNIEnv *env, jobject obj) {
    Display *display = XOpenDisplay(NULL);
    if (!display) return (*env)->NewStringUTF(env, "Error: Cannot open display");

    Window root = DefaultRootWindow(display);
    char title[256];
    char app[256];

    get_active_window_info(display, root, app, title, 256);
    XCloseDisplay(display);

    return (*env)->NewStringUTF(env, title);
}

JNIEXPORT jstring JNICALL Java_com_veridion_client_NativeWindowTracker_getActiveAppName(JNIEnv *env, jobject obj) {
    Display *display = XOpenDisplay(NULL);
    if (!display) return (*env)->NewStringUTF(env, "Error: Cannot open display");

    Window root = DefaultRootWindow(display);
    char title[256];
    char app[256];

    get_active_window_info(display, root, app, title, 256);
    XCloseDisplay(display);

    return (*env)->NewStringUTF(env, app);
}
