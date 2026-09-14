#pragma once

#include "fast_camera_protocol.h"

namespace strike {

int64_t cameraClockNs();

class CameraSocket {
public:
    explicit CameraSocket(int cancelFd);
    ~CameraSocket();
    bool open(const char *name, int64_t deadlineNs);
    int next(CameraMessage &frame, int waitMs);
    bool copy(const CameraMessage &frame, uint8_t *snapshot);
private:
    bool handshake(int64_t deadlineNs);
    int readable(int64_t deadlineNs) const;
    int socketFd = -1;
    int cancelFd;
    int descriptors[BUFFER_LIMIT];
    void *buffers[BUFFER_LIMIT]{};
    uint32_t lengths[BUFFER_LIMIT]{};
    uint32_t strides[BUFFER_LIMIT]{};
    bool syncUnavailable[BUFFER_LIMIT]{};
    bool syncWarned = false;
    CameraHandshake hello{};
};

}  // namespace strike
