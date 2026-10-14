cmake_minimum_required(VERSION 3.10)
project(veridion_native)

set(CMAKE_C_STANDARD 11)

# Find JNI
find_package(JNI REQUIRED)

# Find X11
find_package(X11 REQUIRED)

include_directories(${JNI_INCLUDE_DIRS} ${X11_INCLUDE_DIR})

# Native source files
set(SOURCES src/window_tracker.c)

add_library(veridion_native SHARED ${SOURCES})

target_link_libraries(veridion_native ${X11_LIBRARIES})
