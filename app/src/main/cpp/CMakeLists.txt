# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.4.1)

add_subdirectory(ggwave)

project(native-lib)

set(GGWAV_INC ${CMAKE_CURRENT_SOURCE_DIR}/ggwave/include)

include_directories(
        ${CMAKE_CURRENT_SOURCE_DIR}/inc
        ${GGWAV_INC}
)

add_library(native-lib SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/src/native_jni.cpp
)

target_link_libraries(native-lib
        ggwave
        log
)


