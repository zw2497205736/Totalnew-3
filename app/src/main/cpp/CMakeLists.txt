cmake_minimum_required(VERSION 3.18)

project(nativeaudio LANGUAGES C CXX)

add_library(nativeaudio SHARED
    native_audio.cpp
)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)
find_library(android-lib android)

# Link OpenSLES for recording
find_library(opensles-lib OpenSLES)

target_link_libraries(nativeaudio
    ${log-lib}
    ${android-lib}
    ${opensles-lib}
)
