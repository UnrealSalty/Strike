cmake_minimum_required(VERSION 3.22.1)
project(strike)

add_library(strike SHARED
    camera_texture.cpp
    capture_process.cpp
    dilink5_camera.cpp
    fast_camera_renderer.cpp
    fast_camera_socket.cpp
)
target_compile_options(strike PRIVATE -fno-exceptions -fno-rtti)

# The JNI half of AHardwareBuffer is in libandroid, not libnativewindow.
target_link_libraries(strike EGL GLESv2 android log)
