#include <jni.h>
#include <X11/Xlib.h>
#include <X11/Xatom.h>
#include <X11/Xutil.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void copy_text(char *dst, size_t cap, const unsigned char *src, unsigned long n) {
    if (cap == 0) return;
    if (!src || n == 0) { dst[0] = '\0'; return; }
    size_t len = n < cap - 1 ? (size_t)n : cap - 1;
    if (n >= cap) {
        while (len > 0 && (src[len] & 0xc0) == 0x80) len--;
    }
    memcpy(dst, src, len);
    dst[len] = '\0';
}
 
static jstring new_utf8_string(JNIEnv *env, const char *src) {
    size_t bytes = strlen(src), i = 0, units = 0;
    jchar *utf16 = calloc(bytes + 1, sizeof(jchar));
    if (!utf16) return NULL;
    while (i < bytes) {
        unsigned char c = (unsigned char)src[i++]; unsigned int cp;
        if (c < 0x80) cp = c;
        else if ((c & 0xe0) == 0xc0 && i < bytes) cp = ((c & 0x1f) << 6) | ((unsigned char)src[i++] & 0x3f);
        else if ((c & 0xf0) == 0xe0 && i + 1 < bytes) { cp = ((c
              & 0x0f) << 12) | (((unsigned char)src[i] & 0x3f) << 6) | ((unsigned char)src[i+1] & 0x3f); i += 2; }
        else if ((c & 0xf8) == 0xf0 && i + 2 < bytes) { cp = ((c & 7) << 18) | (((unsigned char)src[i] & 0x3f) << 12) | (((unsigned char)src[i+1] & 0x3f) << 6) | ((unsigned char)src[i+2] & 0x3f); i += 3; }
        else cp = 0xfffd;
        if (cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) cp = 0xfffd;
        if (cp <= 0xffff) utf16[units++] = (jchar)cp;
        else { cp -= 0x10000; utf16[units++] = (jchar)(0xd800 + (cp >> 10)); utf16[units++] = (jchar)(0xdc00 + (cp & 0x3ff)); }
    }
    jstring result = (*env)->NewString(env, utf16, (jsize)units);
    free(utf16);
    return result;
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
        const char *cls = instance;
        while (*cls && *cls != '\0') cls++; // Find the null terminator
        if (*cls) {
            snprintf(app, appcap, "%.*s", (int)(n - (cls - instance) - 1), cls);
        } else {
            snprintf(app, appcap, "%.*s", (int)strnlen(instance, n), instance);
        }
    }
    if (data) XFree(data);
}

JNIEXPORT jobjectArray JNICALL Java_com_veridion_client_NativeWindowTracker_captureActiveWindowInfo(JNIEnv *env, jobject obj) {
    (void)obj;
    Display *d = XOpenDisplay(NULL);
    char app[512] = "Unknown", title[2048] = "Display unavailable";
    if (d) { window_info(d, DefaultRootWindow(d), app, sizeof(app), title, sizeof(title)); XCloseDisplay(d); }
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (!string_class) return NULL;
    jobjectArray result = (*env)->NewObjectArray(env, 2, string_class, NULL);
    if (!result) return NULL;
    jstring app_value = new_utf8_string(env, app);
    jstring title_value = new_utf8_string(env, title);
    if (app_value) { (*env)->SetObjectArrayElement(env, result, 0, app_value); (*env)->DeleteLocalRef(env, app_value); }
    if (title_value) { (*env)->SetObjectArrayElement(env, result, 1, title_value); (*env)->DeleteLocalRef(env, title_value); }
    return result;
}
