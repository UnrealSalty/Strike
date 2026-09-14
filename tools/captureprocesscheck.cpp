#include <jni.h>
#include <assert.h>
#include <errno.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <type_traits>
#include <unistd.h>

extern "C" jint Java_com_strike_camera_DiLink5Camera_nativeLaunch(
    JNIEnv *, jclass, jstring, jstring);
extern "C" void Java_com_strike_camera_DiLink5Camera_nativeTerminate(
    JNIEnv *, jclass, jint);

static JNIEnv environment{};
static char executable[PATH_MAX];

static jstring string(const char *text) {
    return reinterpret_cast<jstring>(const_cast<char *>(text));
}

static int64_t nowMs() {
    timespec now{};
    assert(clock_gettime(CLOCK_MONOTONIC, &now) == 0);
    return static_cast<int64_t>(now.tv_sec) * 1000 + now.tv_nsec / 1000000;
}

static void readable(int fd) {
    pollfd watched{fd, POLLIN, 0};
    int ready;
    do {
        ready = poll(&watched, 1, 3000);
    } while (ready < 0 && errno == EINTR);
    assert(ready == 1 && (watched.revents & (POLLIN | POLLHUP)));
}

static sockaddr_un address(const char *name, socklen_t &length) {
    sockaddr_un socket{};
    socket.sun_family = AF_UNIX;
    assert(strlen(name) < sizeof(socket.sun_path) - 1);
    memcpy(socket.sun_path + 1, name, strlen(name));
    length = offsetof(sockaddr_un, sun_path) + strlen(name) + 1;
    return socket;
}

struct Listener {
    int fd;
    char name[80];

    explicit Listener(bool ignoreTerm = false) {
        static int serial = 0;
        snprintf(name, sizeof(name), "strike_capture_test_%d_%d%s", getpid(), ++serial,
                 ignoreTerm ? "_ignore" : "");
        fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        assert(fd >= 0);
        socklen_t length;
        sockaddr_un socket = address(name, length);
        assert(bind(fd, reinterpret_cast<sockaddr *>(&socket), length) == 0);
        assert(listen(fd, 1) == 0);
    }

    ~Listener() { close(fd); }

    int acceptReady(pid_t expected) const {
        readable(fd);
        int peer = accept4(fd, nullptr, nullptr, SOCK_CLOEXEC);
        assert(peer >= 0);
        readable(peer);
        pid_t reported = -1;
        assert(recv(peer, &reported, sizeof(reported), MSG_WAITALL) == sizeof(reported));
        assert(reported == expected);
        return peer;
    }
};

static int producer(int count, char **arguments) {
    alarm(10);
    assert(count == 6 && strcmp(arguments[1], "--all") == 0);
    assert(strcmp(arguments[2], "--time") == 0 && strcmp(arguments[3], "0") == 0);
    assert(strcmp(arguments[4], "--socket") == 0 && arguments[5][0] == '@');
    const char *libraries = getenv("LD_LIBRARY_PATH");
    const char *search = getenv("PATH");
    assert(libraries && strcmp(libraries, "/vendor/lib64:/system/lib64:/data/local/tmp") == 0);
    assert(search && strcmp(search, "/system/bin") == 0);
    assert(getenv("STRIKE_CAPTURE_TEST_SECRET") == nullptr);
    if (strstr(arguments[5], "_ignore")) assert(signal(SIGTERM, SIG_IGN) != SIG_ERR);
    int peer = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    assert(peer >= 0);
    socklen_t length;
    sockaddr_un socket = address(arguments[5] + 1, length);
    assert(connect(peer, reinterpret_cast<sockaddr *>(&socket), length) == 0);
    pid_t pid = getpid();
    assert(send(peer, &pid, sizeof(pid), MSG_NOSIGNAL) == sizeof(pid));
    char finish;
    ssize_t received;
    do {
        received = read(peer, &finish, 1);
    } while (received < 0 && errno == EINTR);
    close(peer);
    return 0;
}

static pid_t launch(const Listener &listener) {
    return Java_com_strike_camera_DiLink5Camera_nativeLaunch(
        &environment, nullptr, string(executable), string(listener.name));
}

static void terminate(pid_t pid) {
    Java_com_strike_camera_DiLink5Camera_nativeTerminate(&environment, nullptr, pid);
}

static void closesConnection(int peer) {
    readable(peer);
    char byte;
    assert(recv(peer, &byte, 1, 0) == 0);
    close(peer);
}

static void stopsOnlyItsOwnedProcess(bool ignoreTerm) {
    Listener listener(ignoreTerm);
    pid_t owned = launch(listener);
    assert(owned > 0);
    int peer = listener.acceptReady(owned);
    assert(launch(listener) < 0);

    pid_t parent = getpid();
    pid_t unrelated = fork();
    assert(unrelated >= 0);
    if (unrelated == 0) {
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) _exit(1);
        alarm(10);
        close(peer);
        for (;;) pause();
    }
    terminate(unrelated);
    assert(kill(unrelated, 0) == 0 && waitpid(unrelated, nullptr, WNOHANG) == 0);
    assert(kill(owned, 0) == 0);

    const int64_t started = nowMs();
    terminate(owned);
    assert(nowMs() - started < 3000);
    closesConnection(peer);
    assert(waitpid(owned, nullptr, WNOHANG) == -1 && errno == ECHILD);
    terminate(owned);
    assert(kill(unrelated, 0) == 0);
    assert(kill(unrelated, SIGKILL) == 0);
    assert(waitpid(unrelated, nullptr, 0) == unrelated);
}

static void producerDiesWhenItsOwnerExits() {
    Listener listener;
    int handoff[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, handoff) == 0);
    pid_t owner = fork();
    assert(owner >= 0);
    if (owner == 0) {
        close(handoff[0]);
        pid_t owned = launch(listener);
        assert(owned > 0);
        assert(send(handoff[1], &owned, sizeof(owned), MSG_NOSIGNAL) == sizeof(owned));
        char finish;
        assert(read(handoff[1], &finish, 1) == 1);
        _exit(0);
    }
    close(handoff[1]);
    readable(handoff[0]);
    pid_t owned = -1;
    assert(recv(handoff[0], &owned, sizeof(owned), MSG_WAITALL) == sizeof(owned));
    int peer = listener.acceptReady(owned);
    const char finish = 1;
    assert(send(handoff[0], &finish, 1, MSG_NOSIGNAL) == 1);
    close(handoff[0]);
    int status = 0;
    assert(waitpid(owner, &status, 0) == owner && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    closesConnection(peer);
}

int main(int count, char **arguments) {
    if (count > 1) return producer(count, arguments);
    alarm(20);
    const ssize_t length = readlink("/proc/self/exe", executable, sizeof(executable) - 1);
    assert(length > 0 && length < static_cast<ssize_t>(sizeof(executable) - 1));
    executable[length] = 0;
    using Functions = std::remove_const_t<std::remove_pointer_t<decltype(environment.functions)>>;
    Functions functions{};
    functions.GetStringUTFChars = [](JNIEnv *, jstring text, jboolean *) {
        return reinterpret_cast<const char *>(text);
    };
    functions.ReleaseStringUTFChars = [](JNIEnv *, jstring, const char *) {};
    environment.functions = &functions;
    assert(setenv("STRIKE_CAPTURE_TEST_SECRET", "must-not-reach-producer", 1) == 0);
    stopsOnlyItsOwnedProcess(false);
    stopsOnlyItsOwnedProcess(true);
    producerDiesWhenItsOwnerExits();
    alarm(0);
    puts("DiLink5 capture process tests passed");
}