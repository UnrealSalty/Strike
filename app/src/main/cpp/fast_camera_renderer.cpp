#include "fast_camera_renderer.h"
#include "fast_camera_log.h"

namespace strike {

static const char *VERTEX = R"(
attribute vec2 position;
attribute vec2 coordinates;
varying vec2 uv;
void main() {
    gl_Position = vec4(position, 0.0, 1.0);
    uv = coordinates;
}
)";

// Each RGBA texel stores U,Y0,V,Y1. Sampling Y0 selects source column 2*x.
static const char *FRAGMENT = R"(
precision mediump float;
uniform sampler2D camera;
varying vec2 uv;
void main() {
    vec4 pixel = texture2D(camera, uv);
    float y = pixel.g;
    float u = pixel.r - 128.0 / 255.0;
    float v = pixel.b - 128.0 / 255.0;
    gl_FragColor = vec4(y + 1.402 * v, y - 0.344136 * u - 0.714136 * v,
                       y + 1.772 * u, 1.0);
}
)";

static GLuint shader(GLenum type, const char *source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled) return shader;
    char reason[256]{};
    glGetShaderInfoLog(shader, sizeof(reason), nullptr, reason);
    cameraError("shader compilation failed: %s", reason);
    glDeleteShader(shader);
    return 0;
}

static bool rendererError(const char *stage) {
    cameraError("renderer %s failed: EGL 0x%x GL 0x%x", stage, eglGetError(), glGetError());
    return false;
}

bool CameraRenderer::open(ANativeWindow *window) {
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) {
        display = EGL_NO_DISPLAY;
        return rendererError("initialize");
    }
    const EGLint attributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE
    };
    EGLConfig config;
    EGLint count = 0, format = 0;
    if (!eglChooseConfig(display, attributes, &config, 1, &count) || count == 0 ||
        !eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_ID, &format) ||
        ANativeWindow_setBuffersGeometry(window, STRIP_WIDTH, VIEW_HEIGHT, format) != 0) return rendererError("surface configuration");
    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
    if (context == EGL_NO_CONTEXT) return rendererError("context creation");
    surface = eglCreateWindowSurface(display, config, window, nullptr);
    if (surface == EGL_NO_SURFACE || !eglMakeCurrent(display, surface, surface, context)) return rendererError("surface binding");
    eglSwapInterval(display, 0);

    GLuint vertex = shader(GL_VERTEX_SHADER, VERTEX);
    GLuint fragment = shader(GL_FRAGMENT_SHADER, FRAGMENT);
    if (!vertex || !fragment) {
        if (vertex) glDeleteShader(vertex);
        if (fragment) glDeleteShader(fragment);
        return false;
    }
    program = glCreateProgram();
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) return rendererError("shader linking");
    position = glGetAttribLocation(program, "position");
    coordinates = glGetAttribLocation(program, "coordinates");
    glUseProgram(program);
    glUniform1i(glGetUniformLocation(program, "camera"), 0);
    glGenTextures(CAMERAS, textures);
    for (GLuint texture : textures) {
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, VIEW_WIDTH, VIEW_HEIGHT, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    }
    if (position < 0 || coordinates < 0 || glGetError() != GL_NO_ERROR) return rendererError("texture setup");
    return true;
}

bool CameraRenderer::upload(uint32_t camera, const uint8_t *snapshot) {
    glBindTexture(GL_TEXTURE_2D, textures[camera]);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, VIEW_WIDTH, VIEW_HEIGHT,
                    GL_RGBA, GL_UNSIGNED_BYTE, snapshot);
    if (glGetError() != GL_NO_ERROR) return rendererError("texture upload");
    return true;
}

bool CameraRenderer::draw() {
    static const GLfloat corners[] = {-1, -1, 1, -1, -1, 1, 1, 1};
    static const GLfloat source[] = {0, 1, 1, 1, 0, 0, 1, 0};
    glUseProgram(program);
    glActiveTexture(GL_TEXTURE0);
    glEnableVertexAttribArray(position);
    glEnableVertexAttribArray(coordinates);
    glVertexAttribPointer(position, 2, GL_FLOAT, GL_FALSE, 0, corners);
    glVertexAttribPointer(coordinates, 2, GL_FLOAT, GL_FALSE, 0, source);
    for (uint32_t camera = 0; camera < CAMERAS; ++camera) {
        glViewport(stripColumn(camera) * VIEW_WIDTH, 0, VIEW_WIDTH, VIEW_HEIGHT);
        glBindTexture(GL_TEXTURE_2D, textures[camera]);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
    glDisableVertexAttribArray(position);
    glDisableVertexAttribArray(coordinates);
    if (glGetError() != GL_NO_ERROR) return rendererError("drawing");
    if (eglSwapBuffers(display, surface) != EGL_TRUE) return rendererError("surface submission");
    return true;
}

CameraRenderer::~CameraRenderer() {
    if (display == EGL_NO_DISPLAY) return;
    if (context != EGL_NO_CONTEXT && surface != EGL_NO_SURFACE &&
        eglMakeCurrent(display, surface, surface, context)) {
        glDeleteTextures(CAMERAS, textures);
        if (program) glDeleteProgram(program);
    }
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
    if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
    eglTerminate(display);
    eglReleaseThread();
}

}  // namespace strike

