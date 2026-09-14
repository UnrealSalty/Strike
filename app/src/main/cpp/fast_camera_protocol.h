#pragma once

#include <stdint.h>
#include <stddef.h>
#include <string.h>

namespace strike {

constexpr uint32_t CAMERAS = 4;
constexpr uint32_t BUFFER_LIMIT = 20;
constexpr uint32_t SOURCE_WIDTH = 1920;
constexpr uint32_t SOURCE_HEIGHT = 1300;
constexpr uint32_t SOURCE_STRIDE = SOURCE_WIDTH * 2;
constexpr uint32_t SOURCE_BYTES = SOURCE_STRIDE * SOURCE_HEIGHT;
constexpr uint32_t MAX_STRIDE = 8192;
constexpr uint32_t MAX_BUFFER_BYTES = 16 * 1024 * 1024;
constexpr uint32_t VIEW_WIDTH = SOURCE_WIDTH / 2;
constexpr uint32_t VIEW_HEIGHT = SOURCE_HEIGHT / 2;
constexpr uint32_t STRIP_WIDTH = VIEW_WIDTH * CAMERAS;
constexpr uint32_t SNAPSHOT_BYTES = SOURCE_STRIDE * VIEW_HEIGHT;
constexpr uint32_t CAMERA_MAGIC = 0x4643414d;

// Wire layout of the pinned fast_cam_capture producer. Integers are little endian.
struct __attribute__((packed)) CameraStream {
    uint32_t camera, width, height, stride, bytes, buffers, firstFd;
};
struct __attribute__((packed)) CameraHandshake {
    uint32_t magic, type, cameras, descriptors;
    CameraStream streams[CAMERAS];
};
struct __attribute__((packed)) CameraMessage {
    uint32_t magic, type, camera, buffer, sequence, width, height;
    uint64_t timestampNs;
};
static_assert(sizeof(CameraHandshake) == 128, "camera handshake layout");
static_assert(sizeof(CameraMessage) == 36, "camera message layout");

inline bool validHandshake(const CameraHandshake &hello, uint32_t receivedFds) {
    if (hello.magic != CAMERA_MAGIC || hello.type != 2 || hello.cameras != CAMERAS ||
        hello.descriptors != receivedFds || receivedFds == 0 || receivedFds > BUFFER_LIMIT) return false;
    uint32_t cameras = 0, descriptors = 0;
    for (uint32_t i = 0; i < CAMERAS; ++i) {
        const CameraStream &stream = hello.streams[i];
        if (stream.camera >= CAMERAS || (cameras & (1u << stream.camera)) ||
            stream.width != SOURCE_WIDTH || stream.height != SOURCE_HEIGHT ||
            stream.stride < SOURCE_STRIDE || stream.stride > MAX_STRIDE || stream.stride % 4 ||
            stream.bytes < stream.stride * SOURCE_HEIGHT || stream.bytes > MAX_BUFFER_BYTES ||
            stream.buffers == 0 || stream.buffers > 5 || stream.firstFd >= receivedFds ||
            stream.buffers > receivedFds - stream.firstFd) return false;
        cameras |= 1u << stream.camera;
        for (uint32_t slot = stream.firstFd; slot < stream.firstFd + stream.buffers; ++slot) {
            if (descriptors & (1u << slot)) return false;
            descriptors |= 1u << slot;
        }
    }
    return descriptors == (1u << receivedFds) - 1;
}

inline int frameDescriptor(const CameraHandshake &hello, const CameraMessage &frame) {
    if (frame.magic != CAMERA_MAGIC || frame.type != 3 || frame.camera >= CAMERAS ||
        frame.width != SOURCE_WIDTH || frame.height != SOURCE_HEIGHT) return -1;
    for (uint32_t i = 0; i < CAMERAS; ++i) {
        const CameraStream &stream = hello.streams[i];
        if (stream.camera == frame.camera) {
            return frame.buffer < stream.buffers ? static_cast<int>(stream.firstFd + frame.buffer) : -1;
        }
    }
    return -1;
}

inline uint32_t stripColumn(uint32_t camera) {
    constexpr uint32_t columns[CAMERAS] = {3, 2, 0, 1};
    return camera < CAMERAS ? columns[camera] : CAMERAS;
}

inline void copyEvenRows(uint8_t *snapshot, const uint8_t *source,
                         size_t rowBytes, size_t stride, size_t height) {
    for (size_t row = 0; row < height / 2; ++row) {
        memcpy(snapshot + row * rowBytes, source + row * 2 * stride, rowBytes);
    }
}

inline bool freshSet(uint32_t mask, const int64_t updatedNs[CAMERAS], int64_t nowNs) {
    if (mask != 15) return false;
    for (uint32_t camera = 0; camera < CAMERAS; ++camera) {
        if (nowNs < updatedNs[camera] || nowNs - updatedNs[camera] > 500000000LL) return false;
    }
    return true;
}

}  // namespace strike