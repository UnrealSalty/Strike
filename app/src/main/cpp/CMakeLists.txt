cmake_minimum_required(VERSION 3.22.1)
project(strike)

add_library(strike SHARED camera_texture.cpp)

# The JNI half of AHardwareBuffer is in libandroid, not libnativewindow.
target_link_libraries(strike EGL GLESv2 android log)
