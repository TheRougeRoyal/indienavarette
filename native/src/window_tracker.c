#include <jni.h>
#include <X11/Xlib.h>
#include <X11/Xatom.h>
#include <X11/Xutil.h>
#include <stdio.h>
#include <string.h>

static void copy_text(char *dst, size_t cap, const unsigned char *src, unsigned long n) {
    size_t len = n < cap - 1 ? (size_t)n : cap - 1;
    if (src && len) memcpy(dst, src, len);
    dst[len] = '\0';
}

static Window active_window(Display *d, Window root) {
    Atom prop = XInternAtom(d, "_NET_ACTIVE_WINDOW", True), type;
    int format; unsigned long n = 0, after = 0; unsigned char *data = NULL;
    Window w = None;
    if (prop != None && XGetWindowProperty(d, root, prop, 0, 1, False, XA_WINDOW,
            &type, &format, &n, &after, &data) == Success && data && type == XA_WINDOW && format == 32 && n == 1)
        w = *(Window *)data;
    if (data) XFree(data);
    return w;
}

static void window_info(Display *d, Window root, char *app, size_t appcap, char *title, size_t titlecap) {
    snprintf(app, appcap, "Unknown"); snprintf(title, titlecap, "Window metadata unavailable");
    Window w = active_window(d, root); if (w == None) return;
    Atom utf8 = XInternAtom(d, "UTF8_STRING", True), name = XInternAtom(d, "_NET_WM_NAME", True);
    Atom type; int format; unsigned long n = 0, after = 0; unsigned char *data = NULL;
    if (name != None && utf8 != None && XGetWindowProperty(d, w, name, 0, 4096, False, utf8,
            &type, &format, &n, &after, &data) == Success && data && type == utf8 && format == 8 && n > 0)
        copy_text(title, titlecap, data, n);
    if (data) { XFree(data); data = NULL; }
    if (strcmp(title, "Window metadata unavailable") == 0 && XFetchName(d, w, (char **)&data) && data) {
        snprintf(title, titlecap, "%s", (char *)data); XFree(data); data = NULL;
    }
    Atom wmclass = XInternAtom(d, "WM_CLASS", True);
    if (wmclass != None && XGetWindowProperty(d, w, wmclass, 0, 1024, False, XA_STRING,
            &type, &format, &n, &after, &data) == Success && data && type == XA_STRING && format == 8 && n > 0) {
        const char *instance = (const char *)data;
        size_t first = strnlen(instance, n);
        const char *identity = first + 1 < n ? instance + first + 1 : instance;
        snprintf(app, appcap, "%.*s", (int)(n - (identity - instance)), identity);
    }
    if (data) XFree(data);
}

static jstring get_value(JNIEnv *env, int want_app) {
    Display *d = XOpenDisplay(NULL);
    if (!d) return (*env)->NewStringUTF(env, want_app ? "Unknown" : "Display unavailable");
    char app[512], title[2048]; window_info(d, DefaultRootWindow(d), app, sizeof(app), title, sizeof(title));
    XCloseDisplay(d);
    return (*env)->NewStringUTF(env, want_app ? app : title);
}
JNIEXPORT jstring JNICALL Java_com_veridion_client_NativeWindowTracker_getActiveWindowTitle(JNIEnv *e, jobject o) { (void)o; return get_value(e, 0); }
JNIEXPORT jstring JNICALL Java_com_veridion_client_NativeWindowTracker_getActiveAppName(JNIEnv *e, jobject o) { (void)o; return get_value(e, 1); }
