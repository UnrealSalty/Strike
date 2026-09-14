#include <jni.h>
#include "fast_camera_log.h"
#include <fcntl.h>
#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

namespace {

pthread_mutex_t ownership = PTHREAD_MUTEX_INITIALIZER;
pid_t child = -1;

bool reaped() {
    if (child <= 0) return true;
    pid_t ended;
    do {
        ended = waitpid(child, nullptr, WNOHANG);
    } while (ended < 0 && errno == EINTR);
    if (ended == child || (ended < 0 && errno == ECHILD)) {
        child = -1;
        return true;
    }
    return false;
}

int64_t clockMs() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<int64_t>(now.tv_sec) * 1000 + now.tv_nsec / 1000000;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_strike_camera_DiLink5Camera_nativeLaunch(JNIEnv *env, jclass, jstring path, jstring name) {
    if (!path || !name) return -1;
    const char *executableText = env->GetStringUTFChars(path, nullptr);
    if (!executableText) return -1;
    const char *socketText = env->GetStringUTFChars(name, nullptr);
    if (!socketText) {
        env->ReleaseStringUTFChars(path, executableText);
        return -1;
    }
    char executable[PATH_MAX]{}, socket[108]{};
    size_t executableLength = strlen(executableText), nameLength = strlen(socketText);
    bool valid = executableLength > 0 && executableLength < sizeof(executable) &&
                 executableText[0] == '/' && nameLength > 0 && nameLength < sizeof(socket) - 1 &&
                 socketText[0] != '@' && !strchr(socketText, '/');
    if (valid) {
        memcpy(executable, executableText, executableLength + 1);
        socket[0] = '@';
        memcpy(socket + 1, socketText, nameLength + 1);
    }
    env->ReleaseStringUTFChars(name, socketText);
    env->ReleaseStringUTFChars(path, executableText);
    if (!valid || access(executable, X_OK) != 0) {
        strike::cameraError("producer executable is unavailable");
        return -1;
    }

    char all[] = "--all", duration[] = "--time", forever[] = "0", option[] = "--socket";
    char libraries[] = "LD_LIBRARY_PATH=/vendor/lib64:/system/lib64:/data/local/tmp";
    char searchPath[] = "PATH=/system/bin";
    char *arguments[] = {executable, all, duration, forever, option, socket, nullptr};
    char *environment[] = {libraries, searchPath, nullptr};
    pid_t parent = getpid();

    pthread_mutex_lock(&ownership);
    if (!reaped()) {
        pthread_mutex_unlock(&ownership);
        return -1;
    }
    int quietOutput = open("/dev/null", O_WRONLY | O_CLOEXEC);
    if (quietOutput < 0) {
        strike::cameraError("cannot open producer output sink: errno %d", errno);
        pthread_mutex_unlock(&ownership);
        return -1;
    }
    pid_t launched = fork();
    if (launched == 0) {
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) _exit(126);
        if (dup2(quietOutput, STDOUT_FILENO) < 0) _exit(126);
        close(quietOutput);
        execve(executable, arguments, environment);
        _exit(127);
    }
    close(quietOutput);
    if (launched > 0) child = launched;
    else strike::cameraError("producer fork failed: errno %d", errno);
    pthread_mutex_unlock(&ownership);
    return launched;
}

extern "C" JNIEXPORT void JNICALL
Java_com_strike_camera_DiLink5Camera_nativeTerminate(JNIEnv *, jclass, jint pid) {
    pthread_mutex_lock(&ownership);
    if (pid <= 0 || pid != child || reaped()) {
        pthread_mutex_unlock(&ownership);
        return;
    }
    kill(child, SIGTERM);
    const int64_t deadline = clockMs() + 1000;
    while (!reaped() && clockMs() < deadline) {
        timespec delay = {0, 20000000};
        nanosleep(&delay, nullptr);
    }
    if (child > 0) {
        kill(child, SIGKILL);
        const int64_t killDeadline = clockMs() + 1000;
        while (!reaped() && clockMs() < killDeadline) {
            timespec delay = {0, 20000000};
            nanosleep(&delay, nullptr);
        }
        if (child > 0) {
            strike::cameraError("producer did not exit after SIGKILL; restarting daemon");
            _exit(70);
        }
    }
    pthread_mutex_unlock(&ownership);
}

