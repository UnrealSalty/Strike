#include "fast_camera_protocol.h"
#include <assert.h>
#include <stdio.h>

using namespace strike;

static CameraHandshake fourCameras() {
    CameraHandshake hello{CAMERA_MAGIC, 2, CAMERAS, BUFFER_LIMIT, {}};
    for (uint32_t camera = 0; camera < CAMERAS; ++camera) {
        hello.streams[camera] = {camera, SOURCE_WIDTH, SOURCE_HEIGHT, SOURCE_STRIDE,
                                 SOURCE_BYTES, 5, camera * 5};
    }
    return hello;
}

static void rejectsUnsafeBuffers() {
    CameraHandshake hello = fourCameras();
    assert(validHandshake(hello, 20));
    assert(!validHandshake(hello, 19));
    const uint32_t amounts[] = {0u, 21u, UINT32_MAX};
    for (uint32_t amount : amounts) {
        CameraHandshake bad = hello;
        bad.descriptors = amount;
        assert(!validHandshake(bad, amount));
    }
    for (uint32_t camera = 0; camera < CAMERAS; ++camera) {
        CameraHandshake bad = hello;
        bad.streams[camera].camera = 4;
        assert(!validHandshake(bad, 20));
        bad = hello;
        bad.streams[camera].firstFd = UINT32_MAX;
        assert(!validHandshake(bad, 20));
        bad = hello;
        bad.streams[camera].buffers = UINT32_MAX;
        assert(!validHandshake(bad, 20));
        bad = hello;
        bad.streams[camera].bytes--;
        assert(!validHandshake(bad, 20));
        bad = hello;
        bad.streams[camera].stride++;
        assert(!validHandshake(bad, 20));
    }
    hello.streams[1].firstFd = 0;
    assert(!validHandshake(hello, 20));
    hello = fourCameras();
    hello.streams[1].camera = 0;
    assert(!validHandshake(hello, 20));
}

static void validatesPaddedBufferFootprints() {
    CameraHandshake hello = fourCameras();
    CameraStream &stream = hello.streams[2];
    stream.stride = 4096;
    stream.bytes = stream.stride * SOURCE_HEIGHT + 4096;
    assert(validHandshake(hello, 20));
    stream.bytes = stream.stride * SOURCE_HEIGHT - 1;
    assert(!validHandshake(hello, 20));
    stream.bytes = MAX_BUFFER_BYTES;
    assert(validHandshake(hello, 20));
    stream.bytes++;
    assert(!validHandshake(hello, 20));
    stream.stride = MAX_STRIDE + 4;
    stream.bytes = MAX_BUFFER_BYTES;
    assert(!validHandshake(hello, 20));
    uint8_t source[4 * 16], compact[2 * 12 + 2];
    memset(source, 255, sizeof(source));
    for (unsigned row = 0; row < 4; ++row) memset(source + row * 16, row, 12);
    memset(compact, 99, sizeof(compact));
    copyEvenRows(compact + 1, source, 12, 16, 4);
    assert(compact[0] == 99 && compact[25] == 99);
    for (unsigned i = 0; i < 24; ++i) assert(compact[i + 1] == (i < 12 ? 0 : 2));
}

static void resolvesFramesAfterStreamReordering() {
    CameraHandshake hello = fourCameras();
    CameraStream first = hello.streams[0];
    hello.streams[0] = hello.streams[3];
    hello.streams[3] = first;
    assert(validHandshake(hello, 20));
    for (uint32_t camera = 0; camera < CAMERAS; ++camera) {
        for (uint32_t buffer = 0; buffer < 5; ++buffer) {
            CameraMessage frame{CAMERA_MAGIC, 3, camera, buffer, 7, SOURCE_WIDTH, SOURCE_HEIGHT, 0};
            assert(frameDescriptor(hello, frame) == static_cast<int>(camera * 5 + buffer));
            frame.buffer = 5;
            assert(frameDescriptor(hello, frame) == -1);
        }
    }
    CameraMessage bad{CAMERA_MAGIC, 3, 4, 0, 7, SOURCE_WIDTH, SOURCE_HEIGHT, 0};
    assert(frameDescriptor(hello, bad) == -1);
    bad.camera = 0;
    bad.width = 3840;
    assert(frameDescriptor(hello, bad) == -1);
}

static void preservesCameraRolesAndSampledPixels() {
    const uint32_t rearLeftRightFront[] = {2, 3, 1, 0};
    for (uint32_t column = 0; column < 4; ++column) {
        assert(stripColumn(rearLeftRightFront[column]) == column);
    }
    uint8_t input[4 * 12]{};
    for (uint32_t row = 0; row < 4; ++row) {
        for (uint32_t pair = 0; pair < 3; ++pair) {
            const size_t at = row * 12 + pair * 4;
            input[at] = 30 + pair;
            input[at + 1] = row * 10 + pair * 2;
            input[at + 2] = 90 + pair;
            input[at + 3] = row * 10 + pair * 2 + 1;
        }
    }
    uint8_t output[26]{};
    output[0] = output[25] = 255;
    copyEvenRows(output + 1, input, 12, 12, 4);
    assert(output[0] == 255 && output[25] == 255);
    for (uint32_t row = 0; row < 2; ++row) {
        for (uint32_t pixel = 0; pixel < 3; ++pixel) {
            const uint8_t *packed = output + 1 + row * 12 + pixel * 4;
            assert(packed[0] == 30 + pixel);
            assert(packed[1] == row * 20 + pixel * 2);
            assert(packed[2] == 90 + pixel);
        }
    }
    int64_t updated[4] = {100, 100, 100, 100};
    assert(freshSet(15, updated, 500000100));
    assert(!freshSet(15, updated, 500000101));
    assert(!freshSet(7, updated, 100));
    updated[2] = 200;
    assert(!freshSet(15, updated, 100));
}

#ifndef _WIN32
#include "fast_camera_socket.h"
#include <dirent.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdlib.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

static int openDescriptors() {
    DIR *directory = opendir("/proc/self/fd");
    assert(directory);
    int count = 0;
    while (readdir(directory)) ++count;
    closedir(directory);
    return count;
}

struct Producer {
    int listener, release[2], buffers[BUFFER_LIMIT];
    bool partial;
    char name[80];
    pthread_t thread;

    explicit Producer(bool partial) : partial(partial) {
        static int serial = 0;
        snprintf(name, sizeof(name), "strike_camera_test_%d_%d", getpid(), ++serial);
        listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        assert(listener >= 0);
        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        memcpy(address.sun_path + 1, name, strlen(name));
        assert(bind(listener, reinterpret_cast<sockaddr *>(&address),
                    offsetof(sockaddr_un, sun_path) + strlen(name) + 1) == 0);
        assert(listen(listener, 1) == 0);
        assert(pipe(release) == 0);
        for (uint32_t i = 0; i < BUFFER_LIMIT; ++i) {
            buffers[i] = static_cast<int>(syscall(SYS_memfd_create, "strike_camera_test", 1));
            assert(buffers[i] >= 0);
            assert(ftruncate(buffers[i], 4096 * SOURCE_HEIGHT + 4096) == 0);
            uint8_t marker = i;
            assert(write(buffers[i], &marker, 1) == 1);
            marker += 100;
            assert(pwrite(buffers[i], &marker, 1, 4096 * 2) == 1);
        }
        assert(pthread_create(&thread, nullptr, serve, this) == 0);
    }

    ~Producer() {
        const char finish = 1;
        assert(write(release[1], &finish, 1) == 1);
        pthread_join(thread, nullptr);
        close(listener);
        close(release[0]);
        close(release[1]);
        for (int fd : buffers) close(fd);
    }

    static void *serve(void *context) {
        auto &producer = *static_cast<Producer *>(context);
        int client = accept(producer.listener, nullptr, nullptr);
        assert(client >= 0);
        CameraHandshake hello = fourCameras();
        for (CameraStream &stream : hello.streams) {
            stream.stride = 4096;
            stream.bytes = 4096 * SOURCE_HEIGHT + 4096;
        }
        alignas(cmsghdr) char ancillary[CMSG_SPACE(sizeof(producer.buffers))]{};
        iovec part = {&hello, 7};
        msghdr packet{};
        packet.msg_iov = &part;
        packet.msg_iovlen = 1;
        packet.msg_control = ancillary;
        packet.msg_controllen = sizeof(ancillary);
        cmsghdr *rights = CMSG_FIRSTHDR(&packet);
        rights->cmsg_level = SOL_SOCKET;
        rights->cmsg_type = SCM_RIGHTS;
        rights->cmsg_len = CMSG_LEN(sizeof(producer.buffers));
        memcpy(CMSG_DATA(rights), producer.buffers, sizeof(producer.buffers));
        assert(sendmsg(client, &packet, MSG_NOSIGNAL) == 7);
        assert(send(client, reinterpret_cast<uint8_t *>(&hello) + 7, sizeof(hello) - 7,
                    MSG_NOSIGNAL) == sizeof(hello) - 7);
        CameraMessage frame{CAMERA_MAGIC, 3, 2, 1, 5, SOURCE_WIDTH, SOURCE_HEIGHT, 1};
        size_t bytes = producer.partial ? sizeof(frame) / 2 : sizeof(frame);
        assert(send(client, &frame, bytes, MSG_NOSIGNAL) == static_cast<ssize_t>(bytes));
        char finish;
        assert(read(producer.release[0], &finish, 1) == 1);
        close(client);
        return nullptr;
    }
};

static void readsSplitHandshakeAndClosesMappedDescriptors() {
    int before = openDescriptors();
    {
        Producer producer(false);
        int cancel = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
        {
            CameraSocket source(cancel);
            assert(source.open(producer.name, cameraClockNs() + 1000000000LL));
            CameraMessage frame{};
            assert(source.next(frame, 1000) == 1);
            uint8_t *snapshot = static_cast<uint8_t *>(malloc(SNAPSHOT_BYTES));
            assert(snapshot && source.copy(frame, snapshot));
            assert(snapshot[0] == 11 && snapshot[SOURCE_STRIDE] == 111);
            assert(source.copy(frame, snapshot));
            free(snapshot);
            uint64_t signal = 1;
            assert(write(cancel, &signal, sizeof(signal)) == sizeof(signal));
            assert(source.next(frame, 1000) == -1);
        }
        close(cancel);
    }
    assert(openDescriptors() == before);
}

static void abandonsPartialFrameWithinItsDeadline() {
    Producer producer(true);
    int cancel = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    {
        CameraSocket source(cancel);
        assert(source.open(producer.name, cameraClockNs() + 1000000000LL));
        CameraMessage frame{};
        int64_t start = cameraClockNs();
        assert(source.next(frame, 1000) == -1);
        assert(cameraClockNs() - start < 2000000000LL);
    }
    close(cancel);
}
#endif


#ifdef __ANDROID__
#include "fast_camera_renderer.h"
#include <media/NdkImageReader.h>

struct ImageReady {
    pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
    pthread_cond_t condition = PTHREAD_COND_INITIALIZER;
    bool arrived = false;
};

static void imageArrived(void *context, AImageReader *) {
    auto &ready = *static_cast<ImageReady *>(context);
    pthread_mutex_lock(&ready.mutex);
    ready.arrived = true;
    pthread_cond_signal(&ready.condition);
    pthread_mutex_unlock(&ready.mutex);
}

static void rendersCanonicalStripWithUprightColors(bool privateOutput) {
    AImageReader *reader = nullptr;
    assert(AImageReader_newWithUsage(STRIP_WIDTH, VIEW_HEIGHT,
        privateOutput ? AIMAGE_FORMAT_PRIVATE : AIMAGE_FORMAT_RGBA_8888,
        privateOutput ? AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE :
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT,
        3, &reader) == AMEDIA_OK);
    ImageReady ready;
    AImageReader_ImageListener listener{&ready, imageArrived};
    assert(AImageReader_setImageListener(reader, &listener) == AMEDIA_OK);
    ANativeWindow *window = nullptr;
    assert(AImageReader_getWindow(reader, &window) == AMEDIA_OK);
    {
        CameraRenderer renderer;
        assert(renderer.open(window));
        uint8_t *raw = static_cast<uint8_t *>(malloc(SOURCE_BYTES));
        uint8_t *snapshot = static_cast<uint8_t *>(malloc(SNAPSHOT_BYTES));
        assert(raw && snapshot);
        const uint8_t yuv[4][3] = {{76, 85, 255}, {150, 44, 21}, {29, 255, 107}, {90, 128, 128}};
        for (uint32_t camera = 0; camera < CAMERAS; ++camera) {
            for (uint32_t row = 0; row < SOURCE_HEIGHT; ++row) {
                for (uint32_t pixel = 0; pixel < SOURCE_WIDTH; pixel += 2) {
                    uint8_t *packed = raw + row * SOURCE_STRIDE + pixel * 2;
                    packed[0] = row < SOURCE_HEIGHT / 2 ? yuv[camera][1] : 128;
                    packed[1] = row < SOURCE_HEIGHT / 2 ? yuv[camera][0] : 255;
                    packed[2] = row < SOURCE_HEIGHT / 2 ? yuv[camera][2] : 128;
                    packed[3] = 0;
                }
            }
            copyEvenRows(snapshot, raw, SOURCE_STRIDE, SOURCE_STRIDE, SOURCE_HEIGHT);
            assert(renderer.upload(camera, snapshot));
        }
        free(snapshot);
        free(raw);
        assert(renderer.draw());
        timespec deadline{};
        clock_gettime(CLOCK_REALTIME, &deadline);
        deadline.tv_sec += 3;
        pthread_mutex_lock(&ready.mutex);
        while (!ready.arrived) assert(pthread_cond_timedwait(&ready.condition, &ready.mutex, &deadline) == 0);
        pthread_mutex_unlock(&ready.mutex);
        AImage *image = nullptr;
        assert(AImageReader_acquireLatestImage(reader, &image) == AMEDIA_OK);
        AHardwareBuffer *buffer = nullptr;
        assert(AImage_getHardwareBuffer(image, &buffer) == AMEDIA_OK);
        AHardwareBuffer_Desc dimensions{};
        AHardwareBuffer_describe(buffer, &dimensions);
        assert(dimensions.width == STRIP_WIDTH && dimensions.height == VIEW_HEIGHT);
        if (!privateOutput) {
        uint8_t *pixels = nullptr;
        int length = 0, stride = 0, pixelStride = 0;
        assert(AImage_getPlaneData(image, 0, &pixels, &length) == AMEDIA_OK);
        assert(AImage_getPlaneRowStride(image, 0, &stride) == AMEDIA_OK);
        assert(AImage_getPlanePixelStride(image, 0, &pixelStride) == AMEDIA_OK);
        assert(pixelStride == 4 && length >= stride * static_cast<int>(VIEW_HEIGHT - 1) + static_cast<int>(STRIP_WIDTH) * 4);
        const int expected[4][3] = {{0, 0, 254}, {90, 90, 90}, {0, 255, 1}, {254, 0, 0}};
        for (uint32_t column = 0; column < CAMERAS; ++column) {
            const uint8_t *top = pixels + 10 * stride + (column * VIEW_WIDTH + 10) * 4;
            const uint8_t *bottom = pixels + (VIEW_HEIGHT - 10) * stride + (column * VIEW_WIDTH + 10) * 4;
            for (uint32_t channel = 0; channel < 3; ++channel) {
                assert(abs(static_cast<int>(top[channel]) - expected[column][channel]) <= 3);
                assert(bottom[channel] == 255);
            }
        }
        }
        AImage_delete(image);
    }
    AImageReader_delete(reader);
    pthread_cond_destroy(&ready.condition);
    pthread_mutex_destroy(&ready.mutex);
}
#endif
int main() {
    rejectsUnsafeBuffers();
    validatesPaddedBufferFootprints();
    resolvesFramesAfterStreamReordering();
    preservesCameraRolesAndSampledPixels();
#ifndef _WIN32
    readsSplitHandshakeAndClosesMappedDescriptors();
    abandonsPartialFrameWithinItsDeadline();
#endif
#ifdef __ANDROID__
    rendersCanonicalStripWithUprightColors(false);
    rendersCanonicalStripWithUprightColors(true);
#endif
    puts("DiLink5 native camera tests passed");
}


