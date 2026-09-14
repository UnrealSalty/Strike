#pragma once

#include <stdarg.h>
#include <stdio.h>
#include <time.h>

namespace strike {

inline void cameraLog(const char *level, const char *format, va_list arguments) {
    timespec now{};
    clock_gettime(CLOCK_REALTIME, &now);
    char message[384];
    vsnprintf(message, sizeof(message), format, arguments);
    fprintf(stderr, "%lld %s Camera DiLink5: %s\n",
            static_cast<long long>(now.tv_sec) * 1000 + now.tv_nsec / 1000000, level, message);
}
inline void cameraError(const char *format, ...) {
    va_list arguments;
    va_start(arguments, format);
    cameraLog("error", format, arguments);
    va_end(arguments);
}
inline void cameraWarning(const char *format, ...) {
    va_list arguments;
    va_start(arguments, format);
    cameraLog("warn", format, arguments);
    va_end(arguments);
}

}  // namespace strike
