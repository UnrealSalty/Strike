#pragma once

#include "fast_camera_protocol.h"
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/native_window.h>

namespace strike {

class CameraRenderer {
public:
    ~CameraRenderer();
    bool open(ANativeWindow *window);
    bool upload(uint32_t camera, const uint8_t *snapshot);
    bool draw();
private:
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    GLuint program = 0;
    GLuint textures[CAMERAS]{};
    GLint position = -1;
    GLint coordinates = -1;
};

}  // namespace strike