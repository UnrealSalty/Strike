#include "fast_camera_socket.h"
#include "fast_camera_renderer.h"
#include "fast_camera_log.h"


#include <android/native_window_jni.h>
#include <jni.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdlib.h>
#include <sys/eventfd.h>
#include <unistd.h>

namespace {

pthread_mutex_t lifecycle = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t readiness = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t ready;
pthread_once_t readinessOnce = PTHREAD_ONCE_INIT;
pthread_t worker{};
bool hasWorker = false;
int startup = 0;
int cancelFd = -1;
ANativeWindow *window = nullptr;
char socketName[107]{};
int framesPerSecond = 0;

void initializeReadiness() {
    pthread_condattr_t attributes;
    pthread_condattr_init(&attributes);
    pthread_condattr_setclock(&attributes, CLOCK_MONOTONIC);
    pthread_cond_init(&ready, &attributes);
    pthread_condattr_destroy(&attributes);
}

void reportStartup(bool opened) {
    pthread_mutex_lock(&readiness);
    startup = opened ? 1 : -1;
    pthread_cond_signal(&ready);
    pthread_mutex_unlock(&readiness);
}

void *capture(void *) {
    using namespace strike;
    CameraSocket source(cancelFd);
    CameraRenderer renderer;
    uint8_t *snapshot = static_cast<uint8_t *>(malloc(SNAPSHOT_BYTES));
    bool opened = snapshot && source.open(socketName, cameraClockNs() + 5000000000LL) &&
                  renderer.open(window);
    reportStartup(opened);
    if (!opened) {
        cameraError("capture startup failed");
        free(snapshot);
        return nullptr;
    }

    CameraMessage latest[CAMERAS]{};
    int64_t uploadedAt[CAMERAS]{};
    int64_t nextUpload[CAMERAS]{};
    uint32_t sequences[CAMERAS]{};
    uint32_t seen = 0, dirty = 0;
    const int64_t interval = 1000000000LL / framesPerSecond;
    bool healthy = true;
    while (healthy) {
        CameraMessage frame{};
        int received = source.next(frame, 100);
        if (received < 0) break;
        if (received == 0) continue;
        uint32_t pending = 0;
        int drained = 0;
        do {
            latest[frame.camera] = frame;
            pending |= 1u << frame.camera;
            if (++drained >= 128) break;
            received = source.next(frame, 0);
        } while (received == 1);
        if (received < 0) break;
        if (drained >= 128) continue;

        for (uint32_t camera = 0; camera < CAMERAS; ++camera) {
            const uint32_t bit = 1u << camera;
            if (!(pending & bit)) continue;
            int64_t now = cameraClockNs();
            if (now < nextUpload[camera] ||
                ((seen & bit) && static_cast<int32_t>(latest[camera].sequence - sequences[camera]) <= 0)) continue;
            if (!source.copy(latest[camera], snapshot) || !renderer.upload(camera, snapshot)) {
                healthy = false;
                break;
            }
            uploadedAt[camera] = cameraClockNs();
            nextUpload[camera] = nextUpload[camera] ? nextUpload[camera] + interval : now + interval;
            if (nextUpload[camera] < now) nextUpload[camera] = now;
            sequences[camera] = latest[camera].sequence;
            seen |= bit;
            dirty |= bit;
        }
        if (healthy && freshSet(dirty, uploadedAt, cameraClockNs())) {
            healthy = renderer.draw();
            dirty = 0;
        }
    }
    free(snapshot);

    return nullptr;
}

void *stopDeadline(void *context) {
    const int completion = *static_cast<int *>(context);
    const int64_t deadline = strike::cameraClockNs() + 2000000000LL;
    pollfd watched{completion, POLLIN, 0};
    for (;;) {
        int64_t remaining = deadline - strike::cameraClockNs();
        if (remaining <= 0) break;
        int waited = poll(&watched, 1, static_cast<int>((remaining + 999999) / 1000000));
        if (waited > 0 && (watched.revents & POLLIN)) return nullptr;
        if (waited < 0 && errno != EINTR) break;
    }
    strike::cameraError("camera driver did not stop within 2s; restarting daemon");
    _exit(70);
}
void stopWorker() {
    if (hasWorker) {
        int completion = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
        pthread_t deadline;
        if (completion < 0 || pthread_create(&deadline, nullptr, stopDeadline, &completion) != 0) {
            strike::cameraError("cannot guard camera shutdown; restarting daemon");
            _exit(70);
        }
        const uint64_t signal = 1;
        while (write(cancelFd, &signal, sizeof(signal)) < 0 && errno == EINTR) {}
        pthread_join(worker, nullptr);
        while (write(completion, &signal, sizeof(signal)) < 0 && errno == EINTR) {}
        pthread_join(deadline, nullptr);
        close(completion);
        hasWorker = false;
    }
    if (cancelFd >= 0) {
        close(cancelFd);
        cancelFd = -1;
    }
    if (window) {
        ANativeWindow_release(window);
        window = nullptr;
    }
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_strike_camera_DiLink5Camera_nativeStart(JNIEnv *env, jclass, jobject surface,
                                               jstring name, jint fps) {
    if (!surface || !name || fps < 1 || fps > 30) return JNI_FALSE;
    pthread_mutex_lock(&lifecycle);
    if (hasWorker) {
        pthread_mutex_unlock(&lifecycle);
        return JNI_FALSE;
    }
    const char *text = env->GetStringUTFChars(name, nullptr);
    if (!text) {
        pthread_mutex_unlock(&lifecycle);
        return JNI_FALSE;
    }
    const size_t length = strlen(text);
    bool valid = length > 0 && length < sizeof(socketName) && text[0] != '@' && !strchr(text, '/');
    if (valid) memcpy(socketName, text, length + 1);
    env->ReleaseStringUTFChars(name, text);
    if (!valid) {
        pthread_mutex_unlock(&lifecycle);
        return JNI_FALSE;
    }
    window = ANativeWindow_fromSurface(env, surface);
    cancelFd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (!window || cancelFd < 0) {
        stopWorker();
        pthread_mutex_unlock(&lifecycle);
        return JNI_FALSE;
    }
    framesPerSecond = fps;
    pthread_once(&readinessOnce, initializeReadiness);
    pthread_mutex_lock(&readiness);
    startup = 0;
    if (pthread_create(&worker, nullptr, capture, nullptr) != 0) {
        pthread_mutex_unlock(&readiness);
        stopWorker();
        pthread_mutex_unlock(&lifecycle);
        return JNI_FALSE;
    }
    hasWorker = true;
    const int64_t until = strike::cameraClockNs() + 5000000000LL;
    const timespec deadline = {until / 1000000000LL, until % 1000000000LL};
    while (startup == 0) {
        if (pthread_cond_timedwait(&ready, &readiness, &deadline) != 0) break;
    }
    bool opened = startup == 1;
    if (startup == 0) strike::cameraError("camera startup exceeded 5s deadline");
    pthread_mutex_unlock(&readiness);
    if (!opened) stopWorker();
    pthread_mutex_unlock(&lifecycle);
    return opened ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_strike_camera_DiLink5Camera_nativeStop(JNIEnv *, jclass) {
    pthread_mutex_lock(&lifecycle);
    stopWorker();
    pthread_mutex_unlock(&lifecycle);
}


