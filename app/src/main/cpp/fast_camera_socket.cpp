#include "fast_camera_socket.h"
#include "fast_camera_log.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <linux/dma-buf.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

namespace strike {

int64_t cameraClockNs() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<int64_t>(now.tv_sec) * 1000000000LL + now.tv_nsec;
}

static int waitSocket(int socketFd, int cancelFd, short events, int64_t deadlineNs) {
    pollfd watched[2] = {{socketFd, events, 0}, {cancelFd, POLLIN, 0}};
    for (;;) {
        int64_t remaining = deadlineNs - cameraClockNs();
        int waitMs = remaining > 0 ? static_cast<int>((remaining + 999999) / 1000000) : 0;
        int ready = poll(watched, 2, waitMs);
        if (ready < 0 && errno == EINTR) continue;
        if (watched[1].revents) { errno = ECANCELED; return -1; }
        if (ready < 0 ||
            (watched[0].revents & (POLLERR | POLLNVAL))) return -1;
        if (watched[0].revents & events) return 1;
        if (watched[0].revents & POLLHUP) return -1;
        if (ready == 0) return 0;
    }
}

CameraSocket::CameraSocket(int cancelFd) : cancelFd(cancelFd) {
    for (int &fd : descriptors) fd = -1;
}

CameraSocket::~CameraSocket() {
    for (uint32_t i = 0; i < BUFFER_LIMIT; ++i) {
        if (buffers[i]) munmap(buffers[i], lengths[i]);
        if (descriptors[i] >= 0) close(descriptors[i]);
    }
    if (socketFd >= 0) close(socketFd);
}

bool CameraSocket::open(const char *name, int64_t deadlineNs) {
    sockaddr_un address{};
    size_t length = strlen(name);
    if (length == 0 || length >= sizeof(address.sun_path) - 1) return false;
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path + 1, name, length);
    socklen_t addressLength = offsetof(sockaddr_un, sun_path) + length + 1;
    while (cameraClockNs() < deadlineNs) {
        socketFd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
        if (socketFd < 0) { cameraError("socket creation failed: errno %d", errno); return false; }
        int connected = connect(socketFd, reinterpret_cast<sockaddr *>(&address), addressLength);
        if (connected == 0) return handshake(deadlineNs);
        if (errno == EINPROGRESS && waitSocket(socketFd, cancelFd, POLLOUT, deadlineNs) == 1) {
            int error = 0;
            socklen_t bytes = sizeof(error);
            if (getsockopt(socketFd, SOL_SOCKET, SO_ERROR, &error, &bytes) == 0 && error == 0) {
                return handshake(deadlineNs);
            }
        }
        close(socketFd);
        socketFd = -1;
        if (waitSocket(-1, cancelFd, POLLIN, cameraClockNs() + 100000000LL) < 0) return false;
    }
    cameraError("producer connection timed out");
    return false;
}

int CameraSocket::readable(int64_t deadlineNs) const {
    return waitSocket(socketFd, cancelFd, POLLIN, deadlineNs);
}

bool CameraSocket::handshake(int64_t deadlineNs) {
    alignas(cmsghdr) char ancillary[CMSG_SPACE(sizeof(int) * BUFFER_LIMIT)]{};
    iovec part = {&hello, sizeof(hello)};
    msghdr packet{};
    packet.msg_iov = &part;
    packet.msg_iovlen = 1;
    packet.msg_control = ancillary;
    packet.msg_controllen = sizeof(ancillary);
    if (readable(deadlineNs) != 1) return false;
    ssize_t received;
    do {
        received = recvmsg(socketFd, &packet, MSG_CMSG_CLOEXEC);
    } while (received < 0 && errno == EINTR);
    if (received <= 0) return false;

    uint32_t fdCount = 0;
    bool valid = (packet.msg_flags & (MSG_CTRUNC | MSG_TRUNC)) == 0;
    for (cmsghdr *control = CMSG_FIRSTHDR(&packet); control; control = CMSG_NXTHDR(&packet, control)) {
        if (control->cmsg_level != SOL_SOCKET || control->cmsg_type != SCM_RIGHTS ||
            control->cmsg_len < CMSG_LEN(0)) {
            valid = false;
            continue;
        }
        size_t bytes = control->cmsg_len - CMSG_LEN(0);
        if (bytes % sizeof(int)) valid = false;
        const int *passed = reinterpret_cast<const int *>(CMSG_DATA(control));
        for (size_t i = 0; i < bytes / sizeof(int); ++i) {
            if (fdCount < BUFFER_LIMIT) descriptors[fdCount++] = passed[i];
            else {
                close(passed[i]);
                valid = false;
            }
        }
    }
    if (!valid) { cameraError("invalid or truncated handshake file descriptors"); return false; }
    size_t filled = static_cast<size_t>(received);
    while (filled < sizeof(hello)) {
        if (readable(deadlineNs) != 1) return false;
        ssize_t count = recv(socketFd, reinterpret_cast<uint8_t *>(&hello) + filled,
                             sizeof(hello) - filled, 0);
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (count <= 0) return false;
        filled += static_cast<size_t>(count);
    }
    if (!validHandshake(hello, fdCount)) {
        cameraError("unsupported handshake: magic=%x type=%u cameras=%u descriptors=%u received=%u",
                    hello.magic, hello.type, hello.cameras, hello.descriptors, fdCount);
        for (const CameraStream &stream : hello.streams) {
            cameraError("stream=%u size=%ux%u stride=%u bytes=%u buffers=%u firstFd=%u",
                        stream.camera, stream.width, stream.height, stream.stride, stream.bytes,
                        stream.buffers, stream.firstFd);
        }
        return false;
    }
    for (const CameraStream &stream : hello.streams) {
        for (uint32_t slot = stream.firstFd; slot < stream.firstFd + stream.buffers; ++slot) {
            lengths[slot] = stream.bytes;
            strides[slot] = stream.stride;
        }
    }
    for (uint32_t i = 0; i < fdCount; ++i) {
        struct stat descriptor{};
        if (fstat(descriptors[i], &descriptor) != 0 ||
            (descriptor.st_size > 0 && descriptor.st_size < lengths[i])) {
            cameraError("buffer %u is smaller than declared footprint or unreadable", i);
            return false;
        }
        void *mapped = mmap(nullptr, lengths[i], PROT_READ, MAP_SHARED, descriptors[i], 0);
        if (mapped == MAP_FAILED) { cameraError("buffer %u mapping failed: errno %d", i, errno); return false; }
        buffers[i] = mapped;
    }
    return true;
}

int CameraSocket::next(CameraMessage &frame, int waitMs) {
    int ready = readable(cameraClockNs() + static_cast<int64_t>(waitMs) * 1000000LL);
    if (ready != 1) {
        if (ready < 0 && errno != ECANCELED) cameraError("camera socket disconnected");
        return ready;
    }
    size_t filled = 0;
    const int64_t deadline = cameraClockNs() + 500000000LL;
    while (filled < sizeof(frame)) {
        ssize_t count = recv(socketFd, reinterpret_cast<uint8_t *>(&frame) + filled,
                             sizeof(frame) - filled, 0);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (readable(deadline) != 1) {
                if (errno != ECANCELED) cameraError("incomplete frame notification timed out or disconnected");
                return -1;
            }
            continue;
        }
        if (count <= 0) { cameraError("frame notification disconnected: errno %d", errno); return -1; }
        filled += static_cast<size_t>(count);
    }
    if (frameDescriptor(hello, frame) >= 0) return 1;
    cameraError("invalid frame notification: magic=%x type=%u camera=%u buffer=%u size=%ux%u",
                frame.magic, frame.type, frame.camera, frame.buffer, frame.width, frame.height);
    return -1;
}

static bool cacheSync(int descriptor, uint64_t flags, bool &unavailable, bool &warned) {
    if (unavailable) return true;
    dma_buf_sync sync{flags | DMA_BUF_SYNC_READ};
    int64_t deadline = cameraClockNs() + 500000000LL;
    for (;;) {
        if (ioctl(descriptor, DMA_BUF_IOCTL_SYNC, &sync) == 0) return true;
        if (errno == ENOTTY || errno == EACCES || errno == EPERM) {
            unavailable = true;
            if (errno != ENOTTY && !warned) {
                cameraWarning("DMA sync denied (errno %d); using producer mmap access; cache coherence unverified", errno);
                warned = true;
            }
            return true;
        }
        if ((errno != EINTR && errno != EAGAIN) || cameraClockNs() >= deadline) {
            cameraError("buffer cache synchronization failed: errno %d", errno);
            return false;
        }
    }
}

bool CameraSocket::copy(const CameraMessage &frame, uint8_t *snapshot) {
    int index = frameDescriptor(hello, frame);
    if (index < 0 || !cacheSync(descriptors[index], DMA_BUF_SYNC_START, syncUnavailable[index], syncWarned)) return false;
    // Cache synchronization does not stop the producer recycling its ring.
    copyEvenRows(snapshot, static_cast<const uint8_t *>(buffers[index]),
                 SOURCE_STRIDE, strides[index], SOURCE_HEIGHT);
    return cacheSync(descriptors[index], DMA_BUF_SYNC_END, syncUnavailable[index], syncWarned);
}

}  // namespace strike


