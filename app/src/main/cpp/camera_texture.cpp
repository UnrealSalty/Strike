#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>

#define TAG "Strike/Texture"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

typedef EGLClientBuffer(EGLAPIENTRYP PFN_NATIVE_CLIENT_BUFFER)(const AHardwareBuffer *buffer);
typedef EGLImageKHR(EGLAPIENTRYP PFN_CREATE_IMAGE)(EGLDisplay, EGLContext, EGLenum,
                                                   EGLClientBuffer, const EGLint *);
typedef EGLBoolean(EGLAPIENTRYP PFN_DESTROY_IMAGE)(EGLDisplay, EGLImageKHR);
typedef void(GL_APIENTRYP PFN_TARGET_TEXTURE)(GLenum, GLeglImageOES);

PFN_NATIVE_CLIENT_BUFFER clientBufferOf;
PFN_CREATE_IMAGE createImage;
PFN_DESTROY_IMAGE destroyImage;
PFN_TARGET_TEXTURE targetTexture;
bool looked;
bool present;

// eglGetProcAddress needs no context, but every caller below has one anyway.
bool entryPoints() {
    if (looked) {
        return present;
    }
    looked = true;
    clientBufferOf =
        (PFN_NATIVE_CLIENT_BUFFER) eglGetProcAddress("eglGetNativeClientBufferANDROID");
    createImage = (PFN_CREATE_IMAGE) eglGetProcAddress("eglCreateImageKHR");
    destroyImage = (PFN_DESTROY_IMAGE) eglGetProcAddress("eglDestroyImageKHR");
    targetTexture = (PFN_TARGET_TEXTURE) eglGetProcAddress("glEGLImageTargetTexture2DOES");
    present = clientBufferOf && createImage && destroyImage && targetTexture;
    if (!present) {
        LOGE("this driver cannot sample a camera buffer: clientBuffer=%p createImage=%p "
             "destroyImage=%p targetTexture=%p",
             clientBufferOf, createImage, destroyImage, targetTexture);
    }
    return present;
}

}  // namespace

/**
 * Binds one camera buffer to an external texture. The texture takes its own
 * reference to the buffer, so the image wrapper is destroyed before returning
 * and the caller keeps the Image open only until the next frame is drawn.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_strike_camera_CameraTexture_bind(JNIEnv *env, jclass, jobject buffer, jint texture) {
    if (buffer == nullptr || !entryPoints()) {
        return JNI_FALSE;
    }
    AHardwareBuffer *hardware = AHardwareBuffer_fromHardwareBuffer(env, buffer);
    if (hardware == nullptr) {
        LOGE("the camera buffer had no gralloc handle");
        return JNI_FALSE;
    }
    EGLClientBuffer client = clientBufferOf(hardware);
    EGLDisplay display = eglGetCurrentDisplay();
    if (client == nullptr || display == EGL_NO_DISPLAY) {
        LOGE("no client buffer or no current display");
        return JNI_FALSE;
    }
    const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLImageKHR image =
        createImage(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, attributes);
    if (image == EGL_NO_IMAGE_KHR) {
        LOGE("the driver refused the camera buffer: egl 0x%x", eglGetError());
        return JNI_FALSE;
    }
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, (GLuint) texture);
    targetTexture(GL_TEXTURE_EXTERNAL_OES, image);
    GLenum failure = glGetError();
    destroyImage(display, image);
    if (failure != GL_NO_ERROR) {
        LOGE("the texture would not take the camera buffer: gl 0x%x", failure);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_strike_camera_CameraTexture_report(JNIEnv *env, jclass) {
    EGLDisplay display = eglGetCurrentDisplay();
    const char *egl =
        display == EGL_NO_DISPLAY ? nullptr : eglQueryString(display, EGL_EXTENSIONS);
    const char *gl = (const char *) glGetString(GL_EXTENSIONS);
    char report[256];
    snprintf(report, sizeof(report), "entryPoints=%s nativeBuffer=%s imageExternal=%s",
             entryPoints() ? "yes" : "no",
             egl && strstr(egl, "EGL_ANDROID_get_native_client_buffer") ? "yes" : "no",
             gl && strstr(gl, "GL_OES_EGL_image_external") ? "yes" : "no");
    return env->NewStringUTF(report);
}
