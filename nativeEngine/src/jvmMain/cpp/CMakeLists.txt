cmake_minimum_required(VERSION 3.22.1)
project(llama_runner_desktop VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

include("${CMAKE_CURRENT_LIST_DIR}/../../commonCpp/GgmlLlama.cmake")
include("${CMAKE_CURRENT_LIST_DIR}/../../../../runner/src/commonCpp/LlamaRunnerCommon.cmake")

set(JNI_INCLUDE_DIRS "")
if(DEFINED ENV{JAVA_HOME})
    set(JAVA_HOME "$ENV{JAVA_HOME}")
    set(_jni_include "${JAVA_HOME}/include")
    if(EXISTS "${_jni_include}/jni.h")
        list(APPEND JNI_INCLUDE_DIRS "${_jni_include}")
        if(APPLE AND EXISTS "${_jni_include}/darwin/jni_md.h")
            list(APPEND JNI_INCLUDE_DIRS "${_jni_include}/darwin")
        elseif(UNIX AND NOT APPLE AND EXISTS "${_jni_include}/linux/jni_md.h")
            list(APPEND JNI_INCLUDE_DIRS "${_jni_include}/linux")
        elseif(WIN32 AND EXISTS "${_jni_include}/win32/jni_md.h")
            list(APPEND JNI_INCLUDE_DIRS "${_jni_include}/win32")
        endif()
        message(STATUS "JNI includes from JAVA_HOME: ${JNI_INCLUDE_DIRS}")
    endif()
endif()
if(JNI_INCLUDE_DIRS STREQUAL "")
    message(FATAL_ERROR
        "JNI headers not found. Set JAVA_HOME to your JDK root.\n"
        "Ensure it points to a JDK (not JRE) with include/jni.h"
    )
endif()

add_library(
    llama_runner
    SHARED
    ${LLAMA_RUNNER_CORE_SOURCES}
    ${LLAMA_RUNNER_JNI_SOURCE}
)

target_include_directories(llama_runner PRIVATE
    ${LLAMA_RUNNER_INCLUDE_DIRS}
    ${JNI_INCLUDE_DIRS}
)

target_link_libraries(llama_runner
    PRIVATE
    llama
    common
)

set_target_properties(llama_runner PROPERTIES OUTPUT_NAME "llama_runner")

if(CMAKE_BUILD_TYPE MATCHES Release)
    target_compile_options(llama_runner PRIVATE -O3)
endif()
